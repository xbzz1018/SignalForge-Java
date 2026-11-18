package com.signalforge;

import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.knowledge.*;
import com.signalforge.platform.ApiException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeQueryTest {
    final KnowledgeStore store=mock(KnowledgeStore.class);
    final GraphProjection projection=mock(GraphProjection.class);
    final KnowledgeQuery query=new KnowledgeQuery(store,projection);
    final UUID project=UUID.randomUUID(), release=UUID.randomUUID();
    final KnowledgeStore.Snapshot snapshot=new KnowledgeStore.Snapshot(release,project,"SH:600519","{}","{}",
            JsonCodec.sha256("{}"),JsonCodec.sha256("{}"),"2026-11-10T00:00:00Z","READY");
    @Test void noPublishedKnowledgeIsUnknownAndDoesNotCallGraphOrModel() {
        var result=query.query(project,new KnowledgeQuery.Request("600519",null,null,null));
        assertEquals("NO_PUBLISHED_CLAIMS",result.status());
        assertNull(result.releaseId()); assertFalse(result.pointInTimeSafe());
        assertTrue(result.claims().isEmpty()); verifyNoInteractions(projection);
    }
    @Test void rejectsSecurityDriftAndInvalidDirectArgumentsBeforeGraphAccess() {
        when(store.snapshot(project,release)).thenReturn(snapshot);
        assertEquals(404,assertThrows(ApiException.class,()->query.query(project,
                new KnowledgeQuery.Request("SZ:000001",release,null,5))).status());
        assertEquals(400,assertThrows(ApiException.class,()->query.query(project,
                new KnowledgeQuery.Request("600519",release,null,9))).status());
        assertEquals(400,assertThrows(ApiException.class,()->query.query(project,
                new KnowledgeQuery.Request("510300",release,null,5))).status());
        verifyNoInteractions(projection);
    }
    KnowledgeEvidence.Graph graph(List<KnowledgeEvidence.Edge> edges,List<KnowledgeEvidence.Evidence> refs) {
        return new KnowledgeEvidence.Graph(release,"SH:600519",snapshot.fetchedAt(),snapshot.contentHash(),snapshot.payloadHash(),
                List.of(),edges,refs,List.of(),false,"NEO4J");
    }
    @Test void missingCitationsFailInsteadOfInventingOrSilentlyDroppingClaims() {
        when(store.snapshot(project,release)).thenReturn(snapshot);
        when(projection.read(snapshot)).thenReturn(graph(List.of(
                new KnowledgeEvidence.Edge("bad","SH:600519","claim:bad","HAS_SOURCE_CLAIM","missing")),List.of()));
        assertEquals("GRAPH_EVIDENCE_INCOMPLETE",assertThrows(ApiException.class,()->query.query(project,
                new KnowledgeQuery.Request("600519",release,null,5))).code());
    }
    @Test void projectionFailureIsNotReportedAsNoKnowledge() {
        when(store.snapshot(project,release)).thenReturn(snapshot);
        when(projection.read(snapshot)).thenThrow(new ApiException(503,"GRAPH_UNAVAILABLE","unavailable"));
        assertEquals("GRAPH_UNAVAILABLE",assertThrows(ApiException.class,()->query.query(project,
                new KnowledgeQuery.Request("600519",release,null,5))).code());
    }
    @Test void catalogDoesNotBecomeReviewedBodyEvidence() {
        when(store.snapshot(project,release)).thenReturn(snapshot);
        when(projection.read(snapshot)).thenReturn(graph(List.of(),List.of()));
        var result=query.query(project,new KnowledgeQuery.Request("600519",release,null,5));
        assertEquals("NO_MATCHING_CLAIMS",result.status());
        assertTrue(result.warnings().contains("SELECTED_PUBLICATION_BATCH_ONLY"));
    }
}
