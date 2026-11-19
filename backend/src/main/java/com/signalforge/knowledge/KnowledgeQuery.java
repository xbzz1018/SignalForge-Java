package com.signalforge.knowledge;

import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.platform.ApiException;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Bounded read-only contract; the authenticated host supplies project authority. */
@Service
public class KnowledgeQuery {
    public enum Predicate { BUSINESS, PRODUCT, EVENT }
    public record Request(@NotBlank @Size(max=9) String securityId, UUID releaseId,
                          @Size(max=3) List<@NotNull Predicate> predicates,
                          @Min(1) @Max(8) Integer limit) {}
    public record Citation(String id, UUID candidateId, UUID documentId, String predicate, String object,
                           String modality, String quote, int pageNumber, String chunkId,
                           int quoteStart, int quoteEnd, String sourceUrl, String publishedAt,
                           String observedAt, String documentHash) {}
    public record Result(String schemaVersion, UUID projectId, String securityId, UUID releaseId,
                         String snapshotHash, String fetchedAt, String status, List<Citation> claims,
                         boolean truncated, boolean pointInTimeSafe, List<String> warnings) {}
    private final KnowledgeStore store;
    private final GraphProjection projection;
    public KnowledgeQuery(KnowledgeStore store, GraphProjection projection) {
        this.store=store; this.projection=projection;
    }
    public Result query(UUID project, Request request) {
        String security=KnowledgeEvidence.security(request.securityId());
        int limit=request.limit()==null?5:request.limit();
        if (limit<1 || limit>8 || (request.predicates()!=null &&
                (request.predicates().size()>3 || request.predicates().stream().anyMatch(Objects::isNull))))
            throw new ApiException(400,"INVALID_KNOWLEDGE_QUERY","查询条数应为1至8，关系类型仅支持业务、产品和事件");
        Set<String> predicates=new HashSet<>();
        if (request.predicates()!=null) request.predicates().forEach(p->predicates.add(p.name()));
        KnowledgeStore.Snapshot snapshot=request.releaseId()==null?
                store.latestReviewed(project,security):store.snapshot(project,request.releaseId());
        if (snapshot==null)
            return new Result("1.0",project,security,null,null,null,"NO_PUBLISHED_CLAIMS",
                    List.of(),false,false,List.of("COLLECT_EXTRACT_REVIEW_AND_PUBLISH_REQUIRED","NOT_HISTORICAL_EVIDENCE"));
        if (!snapshot.projectId().equals(project) || !snapshot.securityId().equals(security))
            throw new ApiException(404,"GRAPH_RELEASE_NOT_FOUND","该证券的图谱版本不存在");
        var graph=projection.read(snapshot);
        if (!snapshot.id().equals(graph.releaseId()) || !security.equals(graph.securityId()) ||
                !snapshot.payloadHash().equals(graph.snapshotHash()) ||
                !JsonCodec.sha256(snapshot.payload()).equals(snapshot.payloadHash())) throw incomplete();
        Map<String,KnowledgeEvidence.Evidence> evidence=new HashMap<>();
        for (var ref:graph.evidence()) if (evidence.put(ref.id(),ref)!=null) throw incomplete();
        var supportsByClaim = graph.edges().stream().filter(edge -> edge.type().equals("SUPPORTED_BY"))
                .collect(java.util.stream.Collectors.groupingBy(KnowledgeEvidence.Edge::source));
        var claims=new ArrayList<Citation>();
        for (var edge:graph.edges().stream().sorted(Comparator.comparing(KnowledgeEvidence.Edge::id)).toList()) {
            if (!edge.type().equals("HAS_SOURCE_CLAIM")) continue;
            if (!edge.source().equals(security)) throw incomplete();
            var ref=evidence.get(edge.evidenceKey());
            if (ref==null || !ref.scope().equals("REVIEWED_SOURCE_STATEMENT")) throw incomplete();
            var record=ref.record();
            UUID candidate=uuid(record.path("candidateId").asText()), document=uuid(record.path("documentId").asText());
            String predicate=record.path("predicate").asText(), modality=record.path("modality").asText();
            String quote=record.path("quote").asText(), object=record.path("object").asText();
            int page=record.path("pageNumber").asInt(-1), start=record.path("quoteStart").asInt(-1),
                    end=record.path("quoteEnd").asInt(-1);
            if (!edge.target().equals("claim:"+candidate) || !Set.of("BUSINESS","PRODUCT","EVENT").contains(predicate) ||
                    !Set.of("ASSERTED","PLANNED","NEGATED","UNCERTAIN").contains(modality) ||
                    quote.length()<12 || quote.length()>400 || object.length()<2 || !quote.contains(object) ||
                    page<1 || start<0 || end-start!=quote.length() ||
                    !record.path("offsetUnit").asText().equals("UTF16_CODE_UNITS") ||
                    !record.path("pdfHash").asText().matches("[a-f0-9]{64}") ||
                    record.path("reviewedAt").asText().isBlank()) throw incomplete();
            var supports = supportsByClaim.getOrDefault(edge.target(), List.of()).stream()
                    .filter(e -> e.target().equals("document:" + document)).toList();
            if (supports.size()!=1) throw incomplete();
            var support=evidence.get(supports.getFirst().evidenceKey());
            if (support==null || !support.scope().equals("PDF_TEXT_CITATION") ||
                    !support.record().equals(record) || !support.sourceUrl().equals(ref.sourceUrl()) ||
                    !ref.snapshotHash().equals(record.path("pdfHash").asText()) ||
                    !support.snapshotHash().equals(ref.snapshotHash())) throw incomplete();
            if (!predicates.isEmpty() && !predicates.contains(predicate)) continue;
            claims.add(new Citation(snapshot.id()+":"+edge.id(),candidate,document,predicate,object,modality,
                    quote,page,record.path("chunkId").asText(),start,end,ref.sourceUrl(),ref.publishedAt(),
                    ref.observedAt(),ref.snapshotHash()));
        }
        var warnings=new ArrayList<>(graph.warnings());
        warnings.add("SOURCE_STATEMENTS_NOT_VERIFIED_EVENTS");
        warnings.add("NOT_HISTORICAL_EVIDENCE");
        warnings.add("SELECTED_PUBLICATION_BATCH_ONLY");
        return new Result("1.0",project,security,snapshot.id(),snapshot.payloadHash(),snapshot.fetchedAt(),
                claims.isEmpty()?"NO_MATCHING_CLAIMS":"READY",List.copyOf(claims.subList(0,Math.min(limit,claims.size()))),
                claims.size()>limit,false,List.copyOf(new LinkedHashSet<>(warnings)));
    }
    private static UUID uuid(String text) {
        try { return UUID.fromString(text); } catch (IllegalArgumentException e) { throw incomplete(); }
    }
    private static ApiException incomplete() {
        return new ApiException(503,"GRAPH_EVIDENCE_INCOMPLETE","图谱证据不完整，请核对已发布版本");
    }
}
