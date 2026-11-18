package com.signalforge.knowledge;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeWorker {
    private static final Logger log=LoggerFactory.getLogger(KnowledgeWorker.class);
    private final KnowledgeStore store;
    private final KnowledgeSource source;
    private final KnowledgeEvidence evidence;
    private final GraphProjection projection;
    private final boolean enabled;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy=new AtomicBoolean();
    private volatile KnowledgeStore.Job active;
    public KnowledgeWorker(KnowledgeStore store,KnowledgeSource source,KnowledgeEvidence evidence,GraphProjection projection,
                           @Value("${signalforge.knowledge-worker-enabled:true}") boolean enabled) {
        this.store=store; this.source=source; this.evidence=evidence; this.projection=projection; this.enabled=enabled;
    }
    @Scheduled(fixedDelay=700) void dispatch() {
        if (!enabled || !busy.compareAndSet(false,true)) return;
        try {
            var claim=store.claim();
            if (claim==null) { busy.set(false); return; }
            active=claim;
            executor.submit(()->execute(claim));
        } catch (RuntimeException error) { busy.set(false); log.warn("Knowledge dispatch error={}",error.getClass().getSimpleName()); }
    }
    @Scheduled(fixedDelay=3000) void heartbeat() {
        var claim=active; if (claim!=null) store.renew(claim);
    }
    private void execute(KnowledgeStore.Job claim) {
        String stage="KNOWLEDGE_SOURCE_UNAVAILABLE";
        try {
            KnowledgeStore.Snapshot snapshot;
            if (claim.snapshotId()!=null) snapshot=store.snapshot(claim.projectId(),claim.snapshotId());
            else {
                var cached=store.cached(claim);
                KnowledgeEvidence.Verified verified=null;
                if (cached==null) {
                    String payload=source.collect(claim.securityId()); stage="KNOWLEDGE_EVIDENCE_REJECTED";
                    verified=evidence.verify(claim.securityId(),payload);
                }
                snapshot=store.stage(claim,verified,cached);
            }
            if (snapshot==null || !store.renew(claim)) return;
            stage="GRAPH_PROJECTION_UNAVAILABLE";
            projection.project(snapshot); // No PostgreSQL transaction remains open during Neo4j I/O.
            store.complete(claim,snapshot);
        } catch (Exception error) {
            log.warn("Knowledge job={} stage={} error={}",claim.id(),stage,error.getClass().getSimpleName());
            store.fail(claim,stage);
        } finally { active=null; busy.set(false); }
    }
    @PreDestroy void close() { executor.shutdownNow(); }
}
