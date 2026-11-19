package com.signalforge;

import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.platform.ApiException;
import com.signalforge.research.ResearchRequest;
import com.signalforge.research.TaskStore;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "signalforge.worker-enabled=false","signalforge.knowledge-worker-enabled=false",
    "signalforge.evidence-worker-enabled=false","signalforge.bootstrap-enabled=false","signalforge.allow-fixtures=true"})
class ResearchIntegrationTest {
    static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        POSTGRES.start(); r.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username",POSTGRES::getUsername); r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @AfterAll static void close() { POSTGRES.stop(); }
    @Autowired TaskStore store;
    @Autowired JdbcTemplate db;
    @Autowired JsonCodec json;
    @Autowired TransactionTemplate tx;
    @Autowired PasswordEncoder encoder;
    @Value("${local.server.port}") int port;
    UUID project,user;
    String username;
    static final String PASSWORD="test-password-long-enough";
    @BeforeEach void setup() {
        project=UUID.randomUUID(); user=UUID.randomUUID(); username="test-"+user;
        db.update("INSERT INTO sf_user(id,username,password_hash) VALUES(?,?,?)",user,username,encoder.encode(PASSWORD));
        db.update("INSERT INTO sf_project(id,name) VALUES(?,'Integration test')",project);
        db.update("INSERT INTO sf_project_member(project_id,user_id,role) VALUES(?,?,'OWNER')",project,user);
    }
    ResearchRequest request() {
        return json.read("""
            {"schemaVersion":"1.0","researchType":"ETF_RISK_COMPARISON",
            "securities":[{"securityId":"fixture:ETF_A","market":"FIXTURE"},{"securityId":"fixture:ETF_B","market":"FIXTURE"}],
            "knowledgeAsOf":"2026-06-30T23:59:59+08:00","priceRange":{"start":"2025-01-02","end":"2025-01-09","frequency":"DAILY"},
            "priceAdjustment":"NONE","metrics":["TOTAL_RETURN","ANNUALIZED_VOLATILITY","MAX_DRAWDOWN"],"executionMode":"DETERMINISTIC"}
            """,ResearchRequest.class);
    }
    String base() { return "/api/projects/"+project+"/research/tasks"; }
    @Test void concurrentIdempotencyAndConflict() throws Exception {
        try (ExecutorService pool=Executors.newFixedThreadPool(6)) {
            var jobs=new ArrayList<Future<TaskStore.Creation>>();
            for (int i=0;i<12;i++) jobs.add(pool.submit(()->store.create(project,user,"concurrent",request())));
            Set<UUID> ids=new HashSet<>(); int created=0;
            for (var job:jobs) { var result=job.get(10,TimeUnit.SECONDS); ids.add(result.task().id()); if (result.created()) created++; }
            assertEquals(1,ids.size()); assertEquals(1,created);
            UUID id=ids.iterator().next();
            assertEquals(1,db.queryForObject("SELECT count(*) FROM sf_outbox WHERE task_id=?",Integer.class,id));
            assertEquals(1,store.events(project,id,0).size());
            var r=request(); var different=new ResearchRequest(r.schemaVersion(),r.researchType(),r.securities(),r.knowledgeAsOf(),r.priceRange(),r.priceAdjustment(),List.of(ResearchRequest.Metric.TOTAL_RETURN),r.executionMode());
            assertEquals(409,assertThrows(ApiException.class,()->store.create(project,user,"concurrent",different)).status());
        }
    }
    @Test void taskOutboxAndEventsRollbackTogether() {
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{
            store.create(project,user,"rolled-back",request()); throw new IllegalStateException("abort");
        }));
        assertTrue(store.list(project).isEmpty());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM sf_outbox o JOIN sf_task t ON t.id=o.task_id WHERE t.project_id=?",Integer.class,project));
    }
    @Test void cancelledTaskRejectsLateResult() {
        UUID id=store.create(project,user,"cancel",request()).task().id();
        TaskStore.Claim c=claimFor(id);
        assertEquals("CANCELLED",store.cancel(project,id).state());
        assertFalse(store.verifying(c,"{}")); assertFalse(store.complete(c,"{}"));
        assertEquals("CANCELLED",store.cancel(project,id).state());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM sf_task_result WHERE task_id=?",Integer.class,id));
    }
    @Test void expiredLeaseCanBeReclaimedAndOldWorkerIsFenced() {
        UUID id=store.create(project,user,"recovery",request()).task().id();
        var old=claimFor(id);
        db.update("UPDATE sf_task SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",id);
        assertFalse(store.renew(old));
        var replacement=claimFor(id); assertTrue(replacement.token()>old.token());
        assertFalse(store.verifying(old,"{}"));
        assertTrue(store.verifying(replacement,"{\"valid\":true}"));
        assertFalse(store.complete(old,"{}"));
        assertTrue(store.complete(replacement,"{\"valid\":true}"));
        assertEquals("SUCCEEDED",store.get(project,id).state());
        assertTrue(store.result(project,id).path("valid").asBoolean());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM sf_task_run WHERE task_id=? AND status='ABANDONED'",Integer.class,id));
    }
    @Test void expiredDeadlineTerminatesEvenWithValidLease() {
        UUID id=store.create(project,user,"deadline",request()).task().id(); var c=claimFor(id);
        db.update("UPDATE sf_task SET deadline_at=clock_timestamp()-interval '1 second' WHERE id=?",id);
        store.expireDeadlines();
        assertEquals("DEADLINE_EXCEEDED",store.get(project,id).errorCode());
        assertFalse(store.complete(c,"{}"));
    }
    @Test void recoveryHasAnAttemptLimit() {
        UUID id=store.create(project,user,"bounded",request()).task().id();
        for (int i=0;i<3;i++) {
            claimFor(id); db.update("UPDATE sf_task SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",id);
        }
        assertNull(store.claim("fourth-worker"));
        assertEquals("RECOVERY_EXHAUSTED",store.get(project,id).errorCode());
    }
    @Test void authenticationCsrfAndProjectAuthorization() throws Exception {
        Session browser=new Session();
        assertEquals(401,browser.call("GET",base(),null,false).statusCode());
        browser.login();
        assertEquals(403,browser.call("POST",base(),json.write(request()),false).statusCode());
        UUID other=UUID.randomUUID();
        assertEquals(403,browser.call("GET","/api/projects/"+other+"/research/tasks",null,false).statusCode());
        assertEquals(403,browser.call("GET","/api/projects/"+other+"/research/tasks/"+UUID.randomUUID()+"/result",null,false).statusCode());
        db.update("UPDATE sf_project_member SET role='READER' WHERE project_id=? AND user_id=?",project,user);
        assertEquals(200,browser.call("GET",base(),null,false).statusCode());
        assertEquals(403,browser.call("POST",base(),json.write(request()),true).statusCode());
    }
    @Test void sseReconnectReplaysAllEventsAcrossBatchBoundaryBeforeClosing() throws Exception {
        var task = store.create(project, user, "multi-batch-events", request()).task();
        tx.executeWithoutResult(status -> {
            db.update("""
                INSERT INTO sf_task_event(task_id,sequence,type,payload)
                SELECT ?,n,'RUNNING','{}'::jsonb FROM generate_series(2, 204) n
                """, task.id());
            db.update("UPDATE sf_task SET event_seq=204 WHERE id=?", task.id());
            store.cancel(project, task.id());
        });
        Session browser = new Session();
        browser.login();
        var stream = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + base() + "/" + task.id() + "/events"))
                .header("Last-Event-ID", "4").timeout(Duration.ofSeconds(8)).GET().build();
        var response = browser.client.send(stream, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(java.util.stream.LongStream.rangeClosed(5, 205).boxed().toList(),
                response.body().lines().filter(line -> line.startsWith("id:"))
                        .map(line -> Long.parseLong(line.substring(3).trim())).toList());
        assertTrue(response.body().contains("id:205\nevent:CANCELLED"));
    }

    @Test void httpCreationValidationAndSseReplay() throws Exception {
        Session browser=new Session(); browser.login();
        var created=browser.call("POST",base(),json.write(request()),true); assertEquals(202,created.statusCode(),created.body());
        String id=json.tree(created.body()).path("id").asText();
        assertEquals(200,browser.call("POST",base(),json.write(request()),true).statusCode());
        assertEquals(409,browser.call("GET",base()+"/"+id+"/result",null,false).statusCode());
        assertEquals(409,browser.call("GET",base()+"/"+id+"/snapshots",null,false).statusCode());
        assertEquals(404,browser.call("GET",base()+"/"+UUID.randomUUID()+"/snapshots",null,false).statusCode());
        assertEquals(200,browser.call("POST",base()+"/"+id+"/cancel","{}",true).statusCode());
        for (String invalid : List.of("-1", "999")) {
            var invalidStream = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+base()+"/"+id+"/events"))
                    .header("Last-Event-ID",invalid).timeout(Duration.ofSeconds(5)).GET().build();
            var rejected = browser.client.send(invalidStream,HttpResponse.BodyHandlers.ofString());
            assertEquals(400, rejected.statusCode());
            assertEquals("INVALID_EVENT_CURSOR", json.tree(rejected.body()).path("code").asText());
        }
        HttpRequest stream=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+base()+"/"+id+"/events"))
                .header("Last-Event-ID","1").timeout(Duration.ofSeconds(5)).GET().build();
        var response=browser.client.send(stream,HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body()); assertTrue(response.body().contains("event:CANCELLED"));
        assertFalse(response.body().contains("event:QUEUED")); assertTrue(response.body().contains("id:2"));
        assertEquals(400,browser.call("POST",base(),json.write(request()).replace("\"securities\":[", "\"unexpected\":1,\"securities\":["),true).statusCode());
        assertEquals(400,browser.call("POST",base(),json.write(request()).replace("\"fixture:ETF_A\"", "null"),true).statusCode());
    }
    @Test void knowledgeQueryRequiresSessionCsrfAndProjectButAllowsReaders() throws Exception {
        String path="/api/projects/"+project+"/knowledge/query", body="{\"securityId\":\"600519\"}";
        Session browser=new Session();
        assertEquals(403,browser.call("POST",path,body,false).statusCode());
        browser.refreshCsrf();
        assertEquals(401,browser.call("POST",path,body,true).statusCode());
        browser.login();
        assertEquals(403,browser.call("POST",path,body,false).statusCode());
        assertEquals(403,browser.call("POST","/api/projects/"+UUID.randomUUID()+"/knowledge/query",body,true).statusCode());
        db.update("UPDATE sf_project_member SET role='READER' WHERE project_id=? AND user_id=?",project,user);
        var response=browser.call("POST",path,body,true);
        assertEquals(200,response.statusCode(),response.body());
        assertEquals("NO_PUBLISHED_CLAIMS",json.tree(response.body()).path("status").asText());
        assertEquals("private, no-store",response.headers().firstValue("cache-control").orElse(""));
        for (String invalid:List.of("{\"securityId\":\"600519\",\"limit\":9}",
                "{\"securityId\":\"600519\",\"cypher\":\"MATCH (n) RETURN n\"}",
                "{\"securityId\":\"600519\",\"predicates\":[null]}"))
            assertEquals(400,browser.call("POST",path,invalid,true).statusCode());
    }
    @Test void knowledgeQuestionUsesItsOwnContractAndExistingTaskAuthorization() throws Exception {
        assertFalse(json.write(request()).contains("\"question\""),"Optional Agent data must not change legacy risk request hashes");
        String path="/api/projects/"+project+"/knowledge/questions";
        String body="{\"securityId\":\"600519\",\"question\":\"有哪些计划事件？\",\"releaseId\":null}";
        Session browser=new Session(); browser.refreshCsrf();
        assertEquals(401,browser.call("POST",path,body,true).statusCode());
        browser.login();
        assertEquals(403,browser.call("POST",path,body,false).statusCode());
        assertEquals(403,browser.call("POST","/api/projects/"+UUID.randomUUID()+"/knowledge/questions",body,true).statusCode());
        for (String invalid:List.of("{\"securityId\":\"600519\",\"question\":\" \"}",
                "{\"securityId\":\"510300\",\"question\":\"事件\"}",
                "{\"securityId\":\"600519\",\"question\":\"事件\",\"projectId\":\"untrusted\"}"))
            assertEquals(400,browser.call("POST",path,invalid,true).statusCode());
        var created=browser.call("POST",path,body,true);
        assertEquals(202,created.statusCode(),created.body());
        String id=json.tree(created.body()).path("id").asText();
        assertEquals("KNOWLEDGE_QA",json.tree(created.body()).path("request").path("researchType").asText());
        assertEquals(200,browser.call("POST",path,body,true).statusCode());
        assertEquals(409,browser.call("POST",path,body.replace("计划事件","产品信息"),true).statusCode());
        assertEquals(400,browser.call("POST",base(),"{\"schemaVersion\":\"1.0\",\"researchType\":\"ETF_RISK_COMPARISON\",\"executionMode\":\"DETERMINISTIC\"}",true).statusCode());
        db.update("UPDATE sf_project_member SET role='READER' WHERE project_id=? AND user_id=?",project,user);
        assertEquals(200,browser.call("GET",base()+"/"+id,null,false).statusCode());
        var risk=store.create(project,user,"mixed-history-risk",request()).task();
        var riskList=browser.call("GET",base()+"?researchType=ETF_RISK_COMPARISON",null,false);
        var questionList=browser.call("GET",base()+"?researchType=KNOWLEDGE_QA",null,false);
        assertEquals(200,riskList.statusCode());
        assertEquals(200,questionList.statusCode());
        assertEquals(risk.id().toString(),json.tree(riskList.body()).path(0).path("id").asText());
        assertEquals(id,json.tree(questionList.body()).path(0).path("id").asText());
        assertEquals(1,json.tree(riskList.body()).size());
        assertEquals(1,json.tree(questionList.body()).size());
        assertEquals(400,browser.call("GET",base()+"?researchType=UNKNOWN",null,false).statusCode());
        assertEquals(403,browser.call("POST",path,body,true).statusCode());
    }
    TaskStore.Claim claimFor(UUID id) {
        // Isolate leftover queued rows from other tests; no worker runs in this context.
        db.update("UPDATE sf_task SET state='CANCELLED',lease_until=NULL WHERE id<>? AND state IN ('QUEUED','RUNNING','VERIFYING')",id);
        var c=store.claim("integration-worker"); assertNotNull(c); assertEquals(id,c.task().id()); return c;
    }
    class Session {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String csrf;
        void refreshCsrf() throws Exception { csrf=json.tree(call("GET","/api/auth/csrf",null,false).body()).path("token").asText(); }
        void login() throws Exception {
            refreshCsrf();
            var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/login"))
                    .header("Content-Type","application/x-www-form-urlencoded").header("X-CSRF-TOKEN",csrf)
                    .POST(HttpRequest.BodyPublishers.ofString("username="+URLEncoder.encode(username,StandardCharsets.UTF_8)+"&password="+PASSWORD)).build();
            assertEquals(204,client.send(req,HttpResponse.BodyHandlers.ofString()).statusCode()); refreshCsrf();
        }
        HttpResponse<String> call(String method,String path,String body,boolean includeCsrf) throws Exception {
            var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(8));
            if (includeCsrf) b.header("X-CSRF-TOKEN",csrf);
            if (body!=null) b.header("Content-Type","application/json").header("Idempotency-Key","browser");
            return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
