package com.signalforge.knowledge;

import com.signalforge.infrastructure.JsonCodec;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EvidenceWorker {
    private static final Logger log=LoggerFactory.getLogger(EvidenceWorker.class);
    private final EvidenceStore store;
    private final KnowledgeStore knowledge;
    private final KnowledgeSource source;
    private final DocumentEvidence evidence;
    private final ExtractionModel model;
    private final ClaimPublisher publisher;
    private final GraphProjection projection;
    private final JsonCodec json;
    private final boolean enabled;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private volatile EvidenceStore.Job active;
    public EvidenceWorker(EvidenceStore store,KnowledgeStore knowledge,KnowledgeSource source,DocumentEvidence evidence,
                          ExtractionModel model,ClaimPublisher publisher,GraphProjection projection,JsonCodec json,
                          @Value("${signalforge.evidence-worker-enabled:${signalforge.knowledge-worker-enabled:true}}") boolean enabled) {
        this.store=store;this.knowledge=knowledge;this.source=source;this.evidence=evidence;this.model=model;
        this.publisher=publisher;this.projection=projection;this.json=json;this.enabled=enabled;
    }
    @Scheduled(fixedDelay=700) void dispatch() {
        if (!enabled || !busy.compareAndSet(false,true)) return;
        try {
            var job=store.claim();if (job==null) { busy.set(false);return; }
            active=job;executor.submit(()->execute(job));
        } catch (RuntimeException error) { busy.set(false);log.warn("Evidence dispatch error={}",error.getClass().getSimpleName()); }
    }
    @Scheduled(fixedDelay=3000) void heartbeat() { var job=active;if (job!=null) store.renew(job); }
    private void execute(EvidenceStore.Job job) {
        String stage="EVIDENCE_PROCESSING_FAILED";
        try {
            switch (job.kind()) {
                case "DOCUMENT" -> {
                    stage="DOCUMENT_FETCH_FAILED";
                    var catalog=knowledge.snapshot(job.projectId(),UUID.fromString(job.request().path("catalogSnapshotId").asText()));
                    String provider=job.request().path("providerId").asText();store.notice(catalog,provider);
                    String raw=source.document(catalog.securityId(),provider);stage="DOCUMENT_EVIDENCE_REJECTED";
                    store.saveDocument(job,evidence.verify(catalog.securityId(),provider,raw));
                }
                case "EXTRACTION" -> {
                    stage="MODEL_EXTRACTION_FAILED";String raw=store.modelResponse(job);
                    if (raw==null) {
                        if (!store.startModel(job)) return;
                        var response=model.call(store.modelRequest(job));store.captureResponse(job,response);raw=response.raw();
                    }
                    var response=json.tree(raw);
                    if (!response.path("choices").path(0).path("finish_reason").asText().equals("stop"))
                        throw new IllegalStateException("MODEL_OUTPUT_TRUNCATED");
                    var content=json.tree(response.path("choices").path(0).path("message").path("content").asText());
                    var run=store.runForJob(job.projectId(),job.id());Set<String> allowed=new HashSet<>();
                    for (var item:run.selectedChunks()) allowed.add(item.asText());
                    stage="MODEL_CANDIDATES_INVALID";
                    var checked=evidence.candidates(store.document(job.projectId(),run.documentId()).parsed(),allowed,content);
                    store.saveCandidates(job,checked);
                }
                case "PUBLICATION" -> {
                    stage="CLAIM_PUBLICATION_REJECTED";var snapshot=publisher.stage(job);
                    if (snapshot==null || !store.renew(job)) return;
                    stage="CLAIM_PROJECTION_UNAVAILABLE";projection.project(snapshot);store.completePublication(job,snapshot);
                }
                default -> throw new IllegalArgumentException("UNKNOWN_EVIDENCE_KIND");
            }
        } catch (Exception error) {
            String code=error.getMessage();
            if (code==null || !code.matches("(?:MODEL|DOCUMENT|CANDIDATE|CLAIM)_[A-Z0-9_]{1,64}")) code=stage;
            log.warn("Evidence job={} kind={} code={} error={}",job.id(),job.kind(),code,error.getClass().getSimpleName());
            store.fail(job,code);
        } finally { active=null;busy.set(false); }
    }
    @PreDestroy void close() { executor.shutdownNow(); }
}
