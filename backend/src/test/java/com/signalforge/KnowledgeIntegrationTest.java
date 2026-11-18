package com.signalforge;

import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.knowledge.*;
import com.signalforge.platform.ApiException;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties={"signalforge.worker-enabled=false","signalforge.knowledge-worker-enabled=false",
        "signalforge.evidence-worker-enabled=false","signalforge.bootstrap-enabled=false"})
class KnowledgeIntegrationTest {
    static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18-alpine");
    static final GenericContainer<?> NEO=new GenericContainer<>("neo4j:5.26-community")
            .withEnv("NEO4J_AUTH","neo4j/local-integration-password")
            .withEnv("NEO4J_server_memory_heap_initial__size","128m")
            .withEnv("NEO4J_server_memory_heap_max__size","256m")
            .withEnv("NEO4J_server_memory_pagecache_size","128m")
            .withExposedPorts(7687,7474).waitingFor(Wait.forHttp("/").forPort(7474));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        POSTGRES.start(); NEO.start();
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl); r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("signalforge.neo4j-uri",()->"bolt://"+NEO.getHost()+":"+NEO.getMappedPort(7687));
        r.add("signalforge.neo4j-password",()->"local-integration-password");
    }
    @AfterAll static void close() { NEO.stop(); POSTGRES.stop(); }
    @Autowired KnowledgeStore store;
    @Autowired KnowledgeEvidence evidence;
    @Autowired GraphProjection graph;
    @Autowired JdbcTemplate db;
    @Autowired JsonCodec json;
    @Autowired EvidenceStore bodies;
    @Autowired DocumentEvidence citations;
    @Autowired ClaimPublisher publisher;
    @Autowired KnowledgeQuery query;
    UUID project,user;
    @BeforeEach void setup() {
        project=UUID.randomUUID(); user=UUID.randomUUID();
        db.update("INSERT INTO sf_project(id,name) VALUES(?,'Knowledge integration')",project);
        db.update("INSERT INTO sf_user(id,username,password_hash) VALUES(?,?,?)",user,"knowledge-"+user,"disabled-test-hash");
    }
    String payload() {
        return """
            {"schemaVersion":"1.0","provider":"eastmoney","demoData":false,
            "security":{"id":"SH:600519","symbol":"600519","market":"SH","name":"Test stock","industry":"Test industry","classificationScheme":"eastmoney:f100"},
            "fetchedAt":"2026-11-10T00:00:00Z","pointInTimeSafe":false,
            "observedQuote":{"industry":"Test industry"},"warnings":[],
            "sources":{"security":"https://quote.eastmoney.com/sh600519.html"},
            "announcements":[{"id":"eastmoney:AN202611101234567890","providerId":"AN202611101234567890","title":"Test notice",
            "publishedAt":"2026-11-09T10:00:00+08:00","bodyStatus":"NOT_COLLECTED",
            "url":"https://data.eastmoney.com/notices/detail/600519/AN202611101234567890.html",
            "pdfUrl":"https://pdf.dfcfw.com/pdf/H2_AN202611101234567890_1.pdf","rawRecord":{"codes":[{"stock_code":"600519"}]}}]}
            """;
    }
    KnowledgeStore.Job claimed(String key) {
        var created=store.create(project,user,key,"SH:600519");
        for (int i=0;i<30;i++) { var claim=store.claim(); if (claim!=null && claim.id().equals(created.job().id())) return claim; }
        throw new AssertionError("Expected claimed job");
    }
    @Test void idempotencyAndConflict() {
        var first=store.create(project,user,"same-request","SH:600519");
        var second=store.create(project,user,"same-request","SH:600519");
        assertTrue(first.created()); assertFalse(second.created()); assertEquals(first.job().id(),second.job().id());
        assertEquals(409,assertThrows(ApiException.class,()->store.create(project,user,"same-request","SZ:000001")).status());
        store.cancel(project,first.job().id());
    }
    @Test void projectionReplayReadsRealNeo4jAndDeduplicatesSnapshot() {
        var claim=claimed("projection-test");
        var verified=evidence.verify("SH:600519",payload());
        var snapshot=store.stage(claim,verified,null);
        assertEquals(409,assertThrows(ApiException.class,()->graph.read(snapshot)).status());
        graph.project(snapshot); graph.project(snapshot);
        assertTrue(store.complete(claim,snapshot));
        var result=graph.read(store.snapshot(project,snapshot.id()));
        assertEquals(3,result.nodes().size()); assertEquals(2,result.edges().size());
        assertEquals(2,result.evidence().size()); assertFalse(result.pointInTimeSafe());
        var second=claimed("repeat-projection");
        var duplicate=store.stage(second,verified,null);
        assertEquals(snapshot.id(),duplicate.id());
        graph.project(duplicate); assertTrue(store.complete(second,duplicate));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM sf_knowledge_snapshot WHERE project_id=?",Integer.class,project));
    }
    @Test void cancelledJobRejectsLateSourceAndPublish() {
        var claim=claimed("cancel-before-data");
        store.cancel(project,claim.id());
        assertNull(store.stage(claim,evidence.verify("SH:600519",payload()),null));
        assertFalse(store.renew(claim));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM sf_knowledge_snapshot WHERE project_id=?",Integer.class,project));
    }
    @Test void cancelledProjectionCannotPublishRelease() {
        var claim=claimed("cancel-after-source");
        var snapshot=store.stage(claim,evidence.verify("SH:600519",payload()),null);
        store.cancel(project,claim.id()); graph.project(snapshot);
        assertFalse(store.complete(claim,snapshot));
        assertEquals("PENDING",store.snapshot(project,snapshot.id()).state());
    }
    @Test void snapshotCannotCrossProjectBoundary() {
        var claim=claimed("cross-project-test");
        var snapshot=store.stage(claim,evidence.verify("SH:600519",payload()),null);
        assertEquals(404,assertThrows(ApiException.class,()->store.snapshot(UUID.randomUUID(),snapshot.id())).status());
        store.cancel(project,claim.id());
    }
    @Test void sourceIdentityAndEvidenceAreValidated() {
        assertThrows(IllegalArgumentException.class,()->evidence.verify("SZ:000001",payload()));
        assertThrows(IllegalArgumentException.class,()->evidence.verify("SH:600519",payload().replace("https://quote.eastmoney.com/","https://untrusted.invalid/")));
        assertThrows(IllegalArgumentException.class,()->evidence.verify("SH:600519",payload().replace("\"stock_code\":\"600519\"","\"stock_code\":\"000001\"")));
        assertEquals("SH:600519",KnowledgeEvidence.security("600519"));
        assertThrows(ApiException.class,()->KnowledgeEvidence.security("SH:000001"));
    }

    @AfterEach void clearEvidenceJobs() {
        for (var job:bodies.jobs(project)) bodies.cancel(project,job.id());
    }
    KnowledgeStore.Snapshot catalog(String variant) {
        var job=claimed("catalog-"+UUID.randomUUID());
        var snap=store.stage(job,evidence.verify("SH:600519",payload().replace("Test industry",variant).replace("2026-11-10T00:00:00Z",java.time.Instant.now().toString())),null);
        graph.project(snap); assertTrue(store.complete(job,snap));return store.snapshot(project,snap.id());
    }
    EvidenceStore.Job evidenceClaim(EvidenceStore.Creation created) {
        for(int i=0;i<100;i++) { var job=bodies.claim();if(job!=null && job.id().equals(created.job().id())) return job; }
        throw new AssertionError("Expected evidence lease");
    }
    static final String TEXT="公司将于2026年11月10日召开业绩说明会，向投资者介绍经营情况。";
    DocumentEvidence.Verified verifiedDocument() {
        byte[] pdf="%PDF-synthetic-test-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var page=Map.of("pageNumber",1,"text",TEXT,"sha256",JsonCodec.sha256(TEXT));
        var chunk=Map.of("pageNumber",1,"id","p1-c1","text",TEXT,"sha256",JsonCodec.sha256(TEXT),"startOffset",0,"endOffset",TEXT.length());
        var parsed=Map.of("pages",List.of(page),"chunks",List.of(chunk),"coverage",Map.of("totalPages",1,"parsedPages",1,"complete",true),"warnings",List.of(),"offsetUnit","UTF16_CODE_UNITS");
        return new DocumentEvidence.Verified(pdf,DocumentEvidence.sha(pdf),"https://pdf.dfcfw.com/pdf/H2_AN202611101234567890_1.pdf",
            "2026-11-10T00:00:00Z","pypdf:test",json.tree(json.write(parsed)),"READY");
    }
    UUID document(UUID catalog) {
        var job=evidenceClaim(bodies.fetch(project,user,"document-"+UUID.randomUUID(),catalog,"AN202611101234567890"));
        return bodies.saveDocument(job,verifiedDocument());
    }
    ExtractionModel.Prepared prepared(UUID document) {
        return new ExtractionModel(json,"test-key-never-sent","test-model").prepare(bodies.document(project,document));
    }
    List<DocumentEvidence.Checked> validCandidates() {
        return citations.candidates(verifiedDocument().parsed(),Set.of("p1-c1"),json.tree(json.write(Map.of("candidates",List.of(
            Map.of("predicate","EVENT","object","业绩说明会","modality","PLANNED","chunkId","p1-c1","quote",TEXT))))));
    }
    @Test void documentDeduplicatesAcrossCatalogsAndEnforcesProject() {
        var first=catalog("Industry one");var second=catalog("Industry two");
        UUID document=document(first.id());assertEquals(document,document(second.id()));
        assertEquals(1,bodies.documents(project,first.id()).size());assertEquals(1,bodies.documents(project,second.id()).size());
        assertEquals(404,assertThrows(ApiException.class,()->bodies.document(UUID.randomUUID(),document)).status());
        var draft=bodies.fetch(project,user,"fixed-document-key",first.id(),"AN202611101234567890");
        assertEquals(draft.job().id(),bodies.fetch(project,user,"fixed-document-key",first.id(),"AN202611101234567890").job().id());
        assertEquals(409,assertThrows(ApiException.class,()->bodies.fetch(project,user,"fixed-document-key",second.id(),"AN202611101234567890")).status());
        bodies.cancel(project,draft.job().id());
    }
    @Test void modelBudgetDeduplicationAndCancellationBeforeSending() {
        UUID doc=document(catalog("Budget industry").id());var prepared=prepared(doc);
        var first=bodies.extract(project,user,"budget-first-key",doc,prepared,false);
        assertEquals(first.job().id(),bodies.extract(project,user,"budget-second-key",doc,prepared,false).job().id());
        assertEquals(ExtractionModel.RESERVE,bodies.budget(project).reserved());
        bodies.cancel(project,first.job().id());assertEquals(0,bodies.budget(project).reserved());
        assertEquals(409,assertThrows(ApiException.class,()->bodies.extract(project,user,"budget-retry-key",doc,prepared,false)).status());
        var retry=bodies.extract(project,user,"budget-retry-key",doc,prepared,true);assertTrue(retry.created());
        bodies.cancel(project,retry.job().id());
    }
    @Test void unknownModelResultIsNeverAutomaticallyResent() {
        UUID doc=document(catalog("Unknown industry").id());
        var job=evidenceClaim(bodies.extract(project,user,"unknown-model-key",doc,prepared(doc),false));
        assertTrue(bodies.startModel(job));assertFalse(bodies.startModel(job));
        db.update("UPDATE sf_evidence_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",job.id());
        assertNull(bodies.claim());assertEquals("FAILED",bodies.get(project,job.id()).state());
        assertEquals("MODEL_OUTCOME_UNKNOWN",bodies.get(project,job.id()).errorCode());
        assertEquals(ExtractionModel.RESERVE,bodies.budget(project).reserved());
        assertEquals("UNKNOWN",bodies.runForJob(project,job.id()).state());
    }
    @Test void cancelledInFlightResponseKeepsCostAndCannotWriteCandidates() {
        UUID doc=document(catalog("Late industry").id());
        var job=evidenceClaim(bodies.extract(project,user,"late-model-key",doc,prepared(doc),false));
        assertTrue(bodies.startModel(job));bodies.cancel(project,job.id());
        var response=new ExtractionModel.Response("{\"usage\":{\"total_tokens\":123}}",123,json.tree("{}"),"stop");
        bodies.captureResponse(job,response);bodies.captureResponse(job,response);
        assertEquals(123,bodies.budget(project).used());assertEquals(0,bodies.budget(project).reserved());
        assertFalse(bodies.saveCandidates(job,validCandidates()));assertTrue(bodies.candidates(project,doc).isEmpty());
        assertEquals("CANCELLED",bodies.runForJob(project,job.id()).state());
    }
    @Test void reviewedClaimsPublishToNeo4jWithLiteralEvidenceAndReplay() {
        var base=catalog("Reviewed industry");UUID doc=document(base.id());
        var job=evidenceClaim(bodies.extract(project,user,"publish-extract-key",doc,prepared(doc),false));
        assertTrue(bodies.startModel(job));bodies.captureResponse(job,new ExtractionModel.Response("{}",100,json.tree("{}"),"stop"));
        assertTrue(bodies.saveCandidates(job,validCandidates()));var candidate=bodies.candidates(project,doc).getFirst();
        assertEquals(409,assertThrows(ApiException.class,()->bodies.publish(project,user,"unreviewed-publish",base.id(),List.of(candidate.id()))).status());
        bodies.review(project,user,candidate.id(),"APPROVED","逐字核对计划语气与证券主体");
        assertEquals(409,assertThrows(ApiException.class,()->bodies.review(project,user,candidate.id(),"REJECTED","不能反转已锁定审核")).status());
        var publishing=evidenceClaim(bodies.publish(project,user,"reviewed-publish-key",base.id(),List.of(candidate.id())));
        var release=publisher.stage(publishing);graph.project(release);graph.project(release);
        assertTrue(bodies.completePublication(publishing,release));
        var result=graph.read(store.snapshot(project,release.id()));
        assertEquals(1,result.edges().stream().filter(e->e.type().equals("HAS_SOURCE_CLAIM")).count());
        assertEquals(1,result.edges().stream().filter(e->e.type().equals("SUPPORTED_BY")).count());
        var ref=result.evidence().stream().filter(e->e.scope().equals("PDF_TEXT_CITATION")).findFirst().orElseThrow();
        assertEquals(TEXT,ref.record().path("quote").asText());assertEquals("PLANNED",ref.record().path("modality").asText());
        assertEquals(1,bodies.publications(project,base.id()).size());
        var answer=query.query(project,new KnowledgeQuery.Request("600519",null,null,5));
        assertEquals("READY",answer.status());assertEquals(release.id(),answer.releaseId());
        assertFalse(answer.pointInTimeSafe());assertEquals(1,answer.claims().size());
        assertEquals(TEXT,answer.claims().getFirst().quote());
        assertEquals("PLANNED",answer.claims().getFirst().modality());
        assertEquals(doc,answer.claims().getFirst().documentId());
        assertEquals(1,answer.claims().getFirst().pageNumber());
        assertTrue(answer.claims().getFirst().id().startsWith(release.id()+":"));
        var filtered=query.query(project,new KnowledgeQuery.Request("600519",release.id(),
                List.of(KnowledgeQuery.Predicate.PRODUCT),5));
        assertEquals("NO_MATCHING_CLAIMS",filtered.status());
        assertEquals(404,assertThrows(ApiException.class,()->query.query(UUID.randomUUID(),
                new KnowledgeQuery.Request("600519",release.id(),null,5))).status());
        var newerCatalog=catalog("Newer directory only");
        assertEquals(release.id(),query.query(project,new KnowledgeQuery.Request("600519",null,null,5)).releaseId());
        // Re-publishing the same immutable selection reuses the same graph release.
        var replay=evidenceClaim(bodies.publish(project,user,"reviewed-replay-key",base.id(),List.of(candidate.id())));
        assertEquals(release.id(),publisher.stage(replay).id());assertTrue(bodies.completePublication(replay,release));
        var catalogLookup=claimed("catalog-cache-lookup");
        assertEquals(newerCatalog.id(),store.cached(catalogLookup).id());store.cancel(project,catalogLookup.id());
    }
    @Test void cancelledPublicationCannotBecomeReadyAfterProjection() {
        var base=catalog("Cancel release industry");UUID doc=document(base.id());
        var job=evidenceClaim(bodies.extract(project,user,"cancel-release-extract",doc,prepared(doc),false));
        bodies.startModel(job);bodies.captureResponse(job,new ExtractionModel.Response("{}",100,json.tree("{}"),"stop"));
        bodies.saveCandidates(job,validCandidates());var candidate=bodies.candidates(project,doc).getFirst();
        bodies.review(project,user,candidate.id(),"APPROVED","原文位置和计划语气已核对");
        var publishing=evidenceClaim(bodies.publish(project,user,"cancel-publish-key",base.id(),List.of(candidate.id())));
        var release=publisher.stage(publishing);bodies.cancel(project,publishing.id());graph.project(release);
        assertFalse(bodies.completePublication(publishing,release));assertEquals("PENDING",store.snapshot(project,release.id()).state());
        assertTrue(bodies.publications(project,base.id()).isEmpty());
        assertNull(store.latestReviewed(project,"SH:600519"));
        assertEquals("NO_PUBLISHED_CLAIMS",query.query(project,
                new KnowledgeQuery.Request("600519",null,null,5)).status());
        assertEquals(409,assertThrows(ApiException.class,()->query.query(project,
                new KnowledgeQuery.Request("600519",release.id(),null,5))).status());
    }

    @Test void invalidCandidatesAndBudgetLimitCannotBeBypassed() {
        var base=catalog("Invalid industry");UUID doc=document(base.id());
        var limited=new EvidenceStore(db,new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.support.JdbcTransactionManager(db.getDataSource())),json,store,100);
        assertEquals(429,assertThrows(ApiException.class,()->limited.extract(project,user,"limited-model-key",doc,prepared(doc),false)).status());
        assertEquals(0,bodies.budget(project).reserved());
        var job=evidenceClaim(bodies.extract(project,user,"invalid-model-key",doc,prepared(doc),false));
        bodies.startModel(job);bodies.captureResponse(job,new ExtractionModel.Response("{}",100,json.tree("{}"),"stop"));
        var invalid=citations.candidates(verifiedDocument().parsed(),Set.of("p1-c1"),json.tree(json.write(Map.of("candidates",List.of(
            Map.of("predicate","EVENT","object","业绩说明会","modality","ASSERTED","chunkId","p1-c1","quote",TEXT))))));
        bodies.saveCandidates(job,invalid);var candidate=bodies.candidates(project,doc).getFirst();assertEquals("INVALID",candidate.status());
        assertEquals(409,assertThrows(ApiException.class,()->bodies.review(project,user,candidate.id(),"APPROVED","强制通过不应成功")).status());
    }
    @Test void publicationRetriesProjectionAndExpiredWorkerCannotCommitDocument() {
        var base=catalog("Recovery industry");
        var stale=evidenceClaim(bodies.fetch(project,user,"stale-document-key",base.id(),"AN202611101234567890"));
        db.update("UPDATE sf_evidence_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",stale.id());
        var replacement=bodies.claim();assertEquals(stale.id(),replacement.id());
        assertNull(bodies.saveDocument(stale,verifiedDocument()));UUID doc=bodies.saveDocument(replacement,verifiedDocument());
        var job=evidenceClaim(bodies.extract(project,user,"recovery-extract-key",doc,prepared(doc),false));
        bodies.startModel(job);bodies.captureResponse(job,new ExtractionModel.Response("{}",50,json.tree("{}"),"stop"));
        bodies.saveCandidates(job,validCandidates());var candidate=bodies.candidates(project,doc).getFirst();
        bodies.review(project,user,candidate.id(),"APPROVED","按计划陈述与原文核验");
        var publishing=evidenceClaim(bodies.publish(project,user,"recovery-publish-key",base.id(),List.of(candidate.id())));
        var release=publisher.stage(publishing);bodies.fail(publishing,"CLAIM_PROJECTION_UNAVAILABLE");
        db.update("UPDATE sf_evidence_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",publishing.id());
        var recovered=bodies.claim();assertEquals(publishing.id(),recovered.id());
        assertEquals(release.id(),publisher.stage(recovered).id());graph.project(release);
        assertFalse(bodies.completePublication(publishing,release));assertTrue(bodies.completePublication(recovered,release));
    }
}
