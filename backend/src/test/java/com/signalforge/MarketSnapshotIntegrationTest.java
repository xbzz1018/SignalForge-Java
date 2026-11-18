package com.signalforge;

import com.signalforge.finance.MarketSnapshots;
import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.research.ResearchRequest;
import com.signalforge.research.TaskStore;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties={"signalforge.worker-enabled=false","signalforge.knowledge-worker-enabled=false",
        "signalforge.evidence-worker-enabled=false","signalforge.bootstrap-enabled=false","signalforge.allow-fixtures=true"})
class MarketSnapshotIntegrationTest {
    static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        POSTGRES.start(); r.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username",POSTGRES::getUsername); r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @Autowired TaskStore store;
    @Autowired MarketSnapshots snapshots;
    @Autowired JdbcTemplate db;
    @Autowired JsonCodec json;

    @Test void snapshotsPublishAtomicallyAreScopedAndExpire() throws Exception {
        UUID project=UUID.randomUUID(),other=UUID.randomUUID(),user=UUID.randomUUID();
        db.update("INSERT INTO sf_user(id,username,password_hash) VALUES(?,?,?)",user,"snapshot-"+user,"unused");
        db.update("INSERT INTO sf_project(id,name) VALUES(?,'snapshots'),(?,'other')",project,other);
        ResearchRequest request=json.read(new String(getClass().getResourceAsStream("/risk-comparison.fixture.json").readAllBytes(),StandardCharsets.UTF_8),ResearchRequest.class);
        String fixture=new String(getClass().getResourceAsStream("/risk-response.fixture.json").readAllBytes(),StandardCharsets.UTF_8);
        var response=json.tree(fixture).deepCopy();
        for(var item:response.path("series")) {
            ((tools.jackson.databind.node.ObjectNode)item.path("sourceRefs").get(0)).put("fetchedAt",OffsetDateTime.now().toString());
        }
        String body=response.toString();
        UUID task=store.create(project,user,"snapshot",request).task().id();
        var claim=store.claim("snapshot-worker"); assertNotNull(claim);
        assertTrue(snapshots.find(project,request).isEmpty());
        assertTrue(store.verifying(claim,body));
        assertTrue(snapshots.find(project,request).isEmpty());
        assertTrue(store.complete(claim,body));
        assertEquals(2,snapshots.find(project,request).size());
        assertEquals(2,snapshots.forTask(project,task).size());
        assertTrue(snapshots.find(other,request).isEmpty());
        UUID cancelled=store.create(project,user,"cancelled",request).task().id();
        var stale=store.claim("stale-worker"); store.cancel(project,cancelled);
        assertFalse(store.complete(stale,body));
        assertEquals(2,db.queryForObject("SELECT count(*) FROM sf_market_snapshot WHERE project_id=?",Integer.class,project));
        db.update("UPDATE sf_market_snapshot SET reusable_until=clock_timestamp()-interval '1 second' WHERE project_id=?",project);
        assertTrue(snapshots.find(project,request).isEmpty());
        assertEquals(2,snapshots.forTask(project,task).size());
    }
}
