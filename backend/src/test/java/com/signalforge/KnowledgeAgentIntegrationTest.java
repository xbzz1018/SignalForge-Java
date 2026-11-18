package com.signalforge;

import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.knowledge.*;
import com.signalforge.platform.ApiException;
import com.signalforge.research.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties={"signalforge.worker-enabled=false","signalforge.knowledge-worker-enabled=false",
        "signalforge.evidence-worker-enabled=false","signalforge.bootstrap-enabled=false"})
class KnowledgeAgentIntegrationTest {
    static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18-alpine");
    static final String TOKEN="test-service-token-abcdefghijklmnopqrstuvwxyz";
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        POSTGRES.start(); r.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username",POSTGRES::getUsername); r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @AfterAll static void close() { POSTGRES.stop(); }
    @Autowired TaskStore tasks;
    @Autowired AgentRunStore runs;
    @Autowired JdbcTemplate db;
    @Autowired JsonCodec json;
    UUID project,user,release;
    KnowledgeQuery query;
    KnowledgeQuery.Result evidence;
    HttpServer server;
    KnowledgeAgentExecutor executor;
    AtomicInteger requests;
    java.util.function.Function<String,String> response;
    @BeforeEach void setup() throws Exception {
        project=UUID.randomUUID(); user=UUID.randomUUID(); release=UUID.randomUUID();
        db.update("INSERT INTO sf_user(id,username,password_hash) VALUES(?,?,?)",user,"qa-"+user,"unused");
        db.update("INSERT INTO sf_project(id,name) VALUES(?,'Agent test')",project);
        db.update("INSERT INTO sf_project_member(project_id,user_id,role) VALUES(?,?,'OWNER')",project,user);
        String quote="本公司计划开展技术改造项目，相关方案仍需进一步审批。";
        evidence=new KnowledgeQuery.Result("1.0",project,"SH:600519",release,"a".repeat(64),Instant.now().toString(),"READY",
                List.of(new KnowledgeQuery.Citation(release+":claim-1",UUID.randomUUID(),UUID.randomUUID(),"EVENT","技术改造项目",
                        "PLANNED",quote,2,"p2-c1",0,quote.length(),"https://example.com/source.pdf",null,Instant.now().toString(),"b".repeat(64))),false,false,List.of());
        query=mock(KnowledgeQuery.class); when(query.query(eq(project),any())).thenAnswer(i->evidence);
        requests=new AtomicInteger(); response=this::validResponse;
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/internal/knowledge-agent",exchange->{
            requests.incrementAndGet();
            if (!("Bearer "+TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) { exchange.sendResponseHeaders(401,-1); exchange.close(); return; }
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            byte[] output=response.apply(body).getBytes(StandardCharsets.UTF_8);
            try { exchange.sendResponseHeaders(200,output.length); exchange.getResponseBody().write(output); }
            catch (java.io.IOException cancelled) { /* Expected when the Java task is cancelled. */ }
            finally { exchange.close(); }
        });
        server.start(); executor=new KnowledgeAgentExecutor(query,runs,json,"http://127.0.0.1:"+server.getAddress().getPort(),TOKEN);
    }
    @AfterEach void stopServer() {
        server.stop(0);
        tasks.list(project).forEach(task->{ if (!task.terminal()) tasks.cancel(project,task.id()); });
    }
    TaskStore.Claim claim() {
        var request=ResearchRequest.forQuestion(new KnowledgeQuestion("SH:600519","有哪些计划事件？",null));
        var task=tasks.create(project,user,UUID.randomUUID().toString(),request).task();
        var claim=tasks.claim("agent-test"); assertEquals(task.id(),claim.task().id()); return claim;
    }
    String validResponse(String input) {
        String task=json.tree(input).path("taskId").asText();
        return json.write(Map.ofEntries(Map.entry("schemaVersion","1.0"),Map.entry("taskId",task),
                Map.entry("promptVersion","knowledge-qa-1"),Map.entry("status","COMPLETED"),Map.entry("toolObserved",true),
                Map.entry("citationIds",List.of(evidence.claims().getFirst().id())),Map.entry("observedCitationIds",List.of(evidence.claims().getFirst().id())),
                Map.entry("provider","fake"),Map.entry("model","test-model"),Map.entry("turns",2),Map.entry("usageSource","provider"),
                Map.entry("usage",Map.of("inputTokens",200,"outputTokens",40,"totalTokens",240))));
    }
    @Test void publishesGroundedAnswerAndReplaysSavedResponseWithoutPayingAgain() throws Exception {
        var c=claim(); String answer=executor.execute(c);
        assertEquals("PLANNED",json.tree(answer).path("statements").get(0).path("modality").asText());
        assertEquals(evidence.claims().getFirst().quote(),json.tree(answer).path("statements").get(0).path("text").asText());
        executor.verify(c.task().request(),answer);
        // Simulate process loss after response persistence but before final publication.
        db.update("UPDATE sf_task SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",c.task().id());
        var recovered=tasks.claim("restarted-worker");
        assertEquals(answer,executor.execute(recovered)); assertEquals(1,requests.get());
        assertFalse(tasks.verifying(c,answer)); assertTrue(tasks.verifying(recovered,answer));
        executor.verify(recovered.task().request(),answer); assertTrue(tasks.complete(recovered,answer));
        assertEquals("ANSWERED",tasks.result(project,c.task().id()).path("status").asText());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM sf_market_snapshot WHERE task_id=?",Integer.class,c.task().id()));
        assertEquals(240,db.queryForObject("SELECT used_tokens FROM sf_model_budget WHERE project_id=?",Integer.class,project));
    }
    @Test void noEvidenceUsesNoModelAndRequiresNoTokenBudget() throws Exception {
        evidence=new KnowledgeQuery.Result("1.0",project,"SH:600519",null,null,null,"NO_PUBLISHED_CLAIMS",List.of(),false,false,List.of());
        var c=claim(); String answer=executor.execute(c);
        assertEquals("INSUFFICIENT_EVIDENCE",json.tree(answer).path("status").asText());
        assertEquals(0,requests.get()); executor.verify(c.task().request(),answer);
    }
    @Test void unknownSendIsNeverAutomaticallyRepeatedAfterRestart() {
        var c=claim(); runs.begin(c,"{}",65536);
        db.update("UPDATE sf_task SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",c.task().id());
        var recovered=tasks.claim("restarted");
        assertEquals("AGENT_OUTCOME_UNKNOWN",assertThrows(ApiException.class,()->executor.execute(recovered)).code());
        assertEquals(0,requests.get());
        assertEquals(65536,db.queryForObject("SELECT reserved_tokens FROM sf_model_budget WHERE project_id=?",Integer.class,project));
    }
    @Test void rejectsCitationsOutsideActualEvidenceButStillAccountsUsage() {
        response=input->validResponse(input).replace(evidence.claims().getFirst().id(),"fabricated-id");
        var c=claim();
        assertEquals("AGENT_RESPONSE_REJECTED",assertThrows(ApiException.class,()->executor.execute(c)).code());
        assertEquals(240,db.queryForObject("SELECT actual_tokens FROM sf_agent_run WHERE task_id=?",Integer.class,c.task().id()));
        assertEquals("RETURNED",runs.find(c.task().id()).state());
    }
    @Test void rechecksCreatorMembershipBeforeModelAndBeforePublishing() throws Exception {
        var c=claim(); db.update("DELETE FROM sf_project_member WHERE project_id=?",project);
        assertEquals("AGENT_EXECUTION_REVOKED",assertThrows(ApiException.class,()->executor.execute(c)).code());
        assertEquals(0,requests.get());
        db.update("INSERT INTO sf_project_member(project_id,user_id,role) VALUES(?,?,'OWNER')",project,user);
        String answer=executor.execute(c); assertTrue(tasks.verifying(c,answer));
        db.update("DELETE FROM sf_project_member WHERE project_id=?",project);
        assertFalse(tasks.complete(c,answer));
    }
    @Test void cancellationAbortsInFlightRequestAndCannotPublish() throws Exception {
        CountDownLatch started=new CountDownLatch(1), releaseResponse=new CountDownLatch(1);
        response=input->{ started.countDown(); try { releaseResponse.await(5,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return validResponse(input); };
        var c=claim();
        try (var pool=Executors.newSingleThreadExecutor()) {
            var pending=pool.submit(()->executor.execute(c));
            assertTrue(started.await(5,TimeUnit.SECONDS)); tasks.cancel(project,c.task().id());
            assertThrows(ExecutionException.class,()->pending.get(3,TimeUnit.SECONDS));
            assertEquals("CANCELLED",tasks.get(project,c.task().id()).state());
            assertEquals("UNKNOWN",runs.find(c.task().id()).state());
            assertFalse(tasks.complete(c,"{}"));
        } finally { releaseResponse.countDown(); }
    }
    @Test void budgetIsReservedAtomicallyAndFailureDoesNotCreateSendMarker() {
        var c=claim();
        db.update("INSERT INTO sf_model_budget(project_id,budget_day,used_tokens) VALUES(?,(clock_timestamp() AT TIME ZONE 'UTC')::date,90000)",project);
        assertEquals("MODEL_DAILY_BUDGET_EXCEEDED",assertThrows(ApiException.class,()->executor.execute(c)).code());
        assertNull(runs.find(c.task().id())); assertEquals(0,requests.get());
    }
}
