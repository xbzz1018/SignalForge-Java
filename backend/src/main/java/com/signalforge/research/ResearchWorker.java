package com.signalforge.research;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ResearchWorker {
    private static final Logger log=LoggerFactory.getLogger(ResearchWorker.class);
    private final TaskStore store;
    private final ResearchExecutors executors;
    private final boolean enabled;
    private final String workerId=UUID.randomUUID().toString();
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy=new AtomicBoolean();
    private volatile TaskStore.Claim active;
    public ResearchWorker(TaskStore store,ResearchExecutors executors,
            @Value("${signalforge.worker-enabled}") boolean enabled) {
        this.store=store; this.executors=executors; this.enabled=enabled;
    }
    @Scheduled(fixedDelayString="${signalforge.worker-poll-ms:500}")
    void dispatch() {
        if (!enabled) return;
        store.expireDeadlines();
        if (!busy.compareAndSet(false,true)) return;
        try {
            var claim=store.claim(workerId);
            if (claim==null) { busy.set(false); return; }
            active=claim;
            executor.submit(()->execute(claim));
        } catch (RuntimeException e) { busy.set(false); log.warn("Research dispatch failed",e); }
    }
    @Scheduled(fixedDelay=3000)
    void heartbeat() {
        var claim=active;
        if (claim!=null && !store.renew(claim)) log.debug("Execution no longer owns task={} run={}",claim.task().id(),claim.runId());
    }
    private void execute(TaskStore.Claim c) {
        String stage="FINANCE_UNAVAILABLE";
        try {
            var executor=executors.forType(c.task().request().researchType());
            if ("KNOWLEDGE_QA".equals(c.task().request().researchType())) stage="AGENT_SERVICE_UNAVAILABLE";
            String response=executor.execute(c);
            stage="KNOWLEDGE_QA".equals(c.task().request().researchType())?"INVALID_AGENT_RESPONSE":"INVALID_FINANCE_RESPONSE";
            if (!store.verifying(c,response)) return;
            stage="EVIDENCE_REJECTED"; executor.verify(c.task().request(),response);
            store.complete(c,response);
        } catch (Exception e) {
            if (e instanceof com.signalforge.platform.ApiException api) stage=api.code();
            log.warn("Research task={} run={} stage={} error={}",c.task().id(),c.runId(),stage,e.getClass().getSimpleName());
            store.fail(c,stage);
        } finally { active=null; busy.set(false); }
    }
    @PreDestroy void close() { executor.shutdownNow(); }
}
