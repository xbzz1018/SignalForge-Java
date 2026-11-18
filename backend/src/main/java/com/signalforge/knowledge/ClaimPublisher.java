package com.signalforge.knowledge;

import com.signalforge.infrastructure.JsonCodec;
import java.util.*;
import org.springframework.stereotype.Component;

/** Publish reviewed source statements, retaining plans/negations and literal PDF evidence. */
@Component
public class ClaimPublisher {
    private final EvidenceStore store;
    private final KnowledgeStore knowledge;
    private final DocumentEvidence evidence;
    private final JsonCodec json;
    public ClaimPublisher(EvidenceStore store,KnowledgeStore knowledge,DocumentEvidence evidence,JsonCodec json) {
        this.store=store;this.knowledge=knowledge;this.evidence=evidence;this.json=json;
    }
    public KnowledgeStore.Snapshot stage(EvidenceStore.Job job) {
        if (job.resultId()!=null) return knowledge.snapshot(job.projectId(),job.resultId());
        UUID catalog=UUID.fromString(job.request().path("catalogSnapshotId").asText());
        var base=knowledge.snapshot(job.projectId(),catalog);
        var original=json.read(base.graph(),KnowledgeEvidence.Graph.class);
        List<UUID> ids=new ArrayList<>();for (var value:job.request().path("candidateIds")) ids.add(UUID.fromString(value.asText()));
        ids.sort(Comparator.naturalOrder());
        var nodes=new ArrayList<>(original.nodes());var edges=new ArrayList<>(original.edges());var refs=new ArrayList<>(original.evidence());
        Set<UUID> documents=new HashSet<>();List<Map<String,Object>> statements=new ArrayList<>();
        for (UUID id:ids) {
            var candidate=store.requireApproved(job.projectId(),catalog,id);
            var document=store.document(job.projectId(),candidate.documentId());
            if (!document.securityId().equals(base.securityId())) throw new IllegalArgumentException("CANDIDATE_SECURITY_MISMATCH");
            var run=store.run(job.projectId(),candidate.extractionId());Set<String> allowed=new HashSet<>();
            for (var selected:run.selectedChunks()) allowed.add(selected.asText());
            var checked=evidence.candidates(document.parsed(),allowed,json.tree(json.write(Map.of("candidates",List.of(candidate.raw()))))).getFirst();
            if (!checked.errors().isEmpty()) throw new IllegalArgumentException("CANDIDATE_EVIDENCE_CHANGED");
            if (!DocumentEvidence.sha(store.pdf(job.projectId(),document.id())).equals(document.pdfHash()))
                throw new IllegalArgumentException("DOCUMENT_HASH_MISMATCH");
            var chunk=java.util.stream.StreamSupport.stream(document.parsed().path("chunks").spliterator(),false)
                    .filter(c->c.path("id").asText().equals(candidate.chunkId())).findFirst().orElseThrow();
            String claimId="claim:"+id, docId="document:"+document.id(), claimKey="claim-"+id, supportKey="support-"+id;
            String modality=switch(candidate.modality()) {case "PLANNED"->"计划";case "NEGATED"->"否定";case "UNCERTAIN"->"不确定";default->"原文陈述";};
            nodes.add(new KnowledgeEvidence.Node(claimId,"Claim",modality+"："+candidate.object()));
            if (documents.add(document.id())) nodes.add(new KnowledgeEvidence.Node(docId,"DocumentVersion",document.title()));
            edges.add(new KnowledgeEvidence.Edge(claimKey,base.securityId(),claimId,"HAS_SOURCE_CLAIM",claimKey));
            edges.add(new KnowledgeEvidence.Edge(supportKey,claimId,docId,"SUPPORTED_BY",supportKey));
            Map<String,Object> record=new TreeMap<>();
            record.put("candidateId",id);record.put("documentId",document.id());record.put("pdfHash",document.pdfHash());
            record.put("parserVersion",document.parserVersion());record.put("extractionId",run.id());record.put("model",run.model());
            record.put("promptVersion",run.promptVersion());record.put("schemaVersion",run.schemaVersion());
            record.put("predicate",candidate.predicate());record.put("object",candidate.object());record.put("modality",candidate.modality());
            record.put("quote",candidate.quote());record.put("chunkId",candidate.chunkId());record.put("pageNumber",chunk.path("pageNumber").asInt());
            record.put("quoteStart",candidate.quoteStart());record.put("quoteEnd",candidate.quoteEnd());
            record.put("pageStartOffset",chunk.path("startOffset").asInt()+candidate.quoteStart());
            record.put("pageEndOffset",chunk.path("startOffset").asInt()+candidate.quoteEnd());
            record.put("offsetUnit","UTF16_CODE_UNITS");record.put("reviewerId",candidate.reviewerId());
            record.put("reviewReason",candidate.reviewReason());record.put("reviewedAt",candidate.reviewedAt());
            var value=json.tree(json.write(record));String locator="/documents/"+document.id()+"/pages/"+chunk.path("pageNumber").asInt()+"/chunks/"+candidate.chunkId();
            refs.add(new KnowledgeEvidence.Evidence(claimKey,document.sourceUrl(),locator,document.fetchedAt(),document.publishedAt(),
                    document.pdfHash(),"REVIEWED_SOURCE_STATEMENT",value));
            refs.add(new KnowledgeEvidence.Evidence(supportKey,document.sourceUrl(),locator,document.fetchedAt(),document.publishedAt(),
                    document.pdfHash(),"PDF_TEXT_CITATION",value));
            statements.add(record);
        }
        UUID release=UUID.randomUUID();
        var payload=new TreeMap<String,Object>();
        var basePayload=json.tree(base.payload());
        for (String key:basePayload.propertyNames()) payload.put(key,basePayload.get(key));
        payload.put("reviewedSourceStatements",statements);payload.put("catalogSnapshotId",catalog);
        payload.put("publicationSchema","reviewed-source-claims-v1");
        String raw=json.write(payload), contentHash=JsonCodec.sha256("reviewed-source-claims-v1|"+catalog+"|"+ids);
        var warnings=new ArrayList<>(original.warnings());warnings.add("REVIEWED_SOURCE_STATEMENTS_NOT_VERIFIED_EVENTS");
        var graph=new KnowledgeEvidence.Graph(release,base.securityId(),base.fetchedAt(),contentHash,JsonCodec.sha256(raw),
                nodes,edges,refs,warnings,false,"NEO4J");
        return store.stagePublication(job,release,raw,graph,ids);
    }
}
