package com.signalforge.knowledge;

import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.platform.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@Service
public class EvidenceStore {
    public record Job(UUID id,UUID projectId,String kind,String state,UUID resultId,int attempts,long fencingToken,
                      String errorCode,JsonNode request,String createdAt) {}
    public record Creation(Job job,boolean created) {}
    public record Document(UUID id,UUID catalogSnapshotId,String securityId,String providerId,String title,String sourceUrl,
                           String pdfHash,String parserVersion,String publishedAt,String fetchedAt,String status,JsonNode parsed) {}
    public record Run(UUID id,UUID jobId,UUID documentId,String state,String model,String schemaVersion,String promptVersion,
                      String inputHash,JsonNode selectedChunks,Integer actualTokens,int reservedTokens,String createdAt) {}
    public record Candidate(UUID id,UUID documentId,UUID extractionId,String predicate,String object,String modality,
                            String chunkId,String quote,int quoteStart,int quoteEnd,String status,JsonNode validationErrors,
                            JsonNode raw,String reviewReason,String reviewedAt,String reviewerId) {}
    public record Publication(UUID releaseId,UUID catalogSnapshotId,JsonNode candidateIds,String state,String createdAt) {}
    public record Budget(int limit,int reserved,int used) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final KnowledgeStore knowledge;
    private final int dailyLimit;
    public EvidenceStore(JdbcTemplate db,TransactionTemplate tx,JsonCodec json,KnowledgeStore knowledge,
                         @Value("${signalforge.model-daily-token-limit:100000}") int dailyLimit) {
        this.db=db;this.tx=tx;this.json=json;this.knowledge=knowledge;this.dailyLimit=dailyLimit;
    }
    private void key(String value) {
        if (value==null || !value.matches("[a-zA-Z0-9_-]{8,128}")) throw error(400,"INVALID_IDEMPOTENCY_KEY","请提供8至128位幂等键");
    }
    private String request(Map<String,Object> value) { return json.write(new TreeMap<>(value)); }
    private Creation existing(UUID project,String key,String payload) {
        var rows=db.query("SELECT * FROM sf_evidence_job WHERE project_id=? AND idempotency_key=?",this::job,project,key);
        if (rows.isEmpty()) return null;
        if (!JsonCodec.sha256(payload).equals(db.queryForObject("SELECT request_hash FROM sf_evidence_job WHERE id=?",String.class,rows.getFirst().id())))
            throw error(409,"IDEMPOTENCY_CONFLICT","此幂等键已用于其他处理请求");
        return new Creation(rows.getFirst(),false);
    }
    private Creation insert(UUID project,UUID user,String key,String kind,String payload) {
        key(key); UUID id=UUID.randomUUID();
        int count=db.update("""
            INSERT INTO sf_evidence_job(id,project_id,created_by,kind,idempotency_key,request_hash,request_json,state,deadline_at)
            VALUES(?,?,?,?,?,?,?::jsonb,'QUEUED',clock_timestamp()+interval '180 seconds')
            ON CONFLICT(project_id,idempotency_key) DO NOTHING
            """,id,project,user,kind,key,JsonCodec.sha256(payload),payload);
        return count==1?new Creation(get(project,id),true):existing(project,key,payload);
    }
    public Creation fetch(UUID project,UUID user,String key,UUID catalog,String provider) {
        key(key); var snapshot=knowledge.snapshot(project,catalog);
        if (!snapshot.state().equals("READY")) throw error(409,"GRAPH_NOT_READY","请选择已完成的采集版本");
        notice(snapshot,provider);
        String payload=request(Map.of("catalogSnapshotId",catalog,"providerId",provider));
        return tx.execute(s->insert(project,user,key,"DOCUMENT",payload));
    }
    private void requireCatalog(UUID project, UUID catalog) {
        if (!"CATALOG".equals(db.queryForObject("SELECT kind FROM sf_knowledge_snapshot WHERE project_id=? AND id=?",String.class,project,catalog)))
            throw error(409,"CATALOG_REQUIRED","请选择基础采集版本");
    }
    public JsonNode notice(KnowledgeStore.Snapshot snapshot,String provider) {
        requireCatalog(snapshot.projectId(), snapshot.id());
        if (!provider.matches("AN[0-9]{10,40}")) throw error(400,"INVALID_ANNOUNCEMENT","公告标识不正确");
        for (var item:json.tree(snapshot.payload()).path("announcements"))
            if (item.path("providerId").asText().equals(provider)) return item;
        throw error(404,"ANNOUNCEMENT_NOT_IN_SNAPSHOT","该公告不属于所选证券采集版本");
    }
    public Creation extract(UUID project,UUID user,String key,UUID document,ExtractionModel.Prepared prepared,boolean retry) {
        key(key); document(project,document);
        String payload=request(Map.of("documentId",document,"inputHash",prepared.inputHash()));
        return tx.execute(s->{
            // Serialize budget reservations and same-input dedup within this project.
            db.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))",project.toString());
            var old=existing(project,key,payload); if (old!=null) return old;
            var runs=db.query("""
                SELECT j.* FROM sf_evidence_job j JOIN sf_extraction_run r ON r.job_id=j.id
                WHERE j.project_id=? AND r.input_hash=? ORDER BY j.created_at DESC LIMIT 1
                """,this::job,project,prepared.inputHash());
            if (!runs.isEmpty()) {
                var prior=runs.getFirst();
                if (Set.of("QUEUED","RUNNING","SUCCEEDED").contains(prior.state())) return new Creation(prior,false);
                if (!retry) throw error(409,"EXTRACTION_RETRY_REQUIRES_CONFIRMATION","上次抽取未完成，重新尝试会再次调用模型，请选择明确重试");
            }
            LocalDate day=db.queryForObject("SELECT (clock_timestamp() AT TIME ZONE 'UTC')::date",LocalDate.class);
            db.update("INSERT INTO sf_model_budget(project_id,budget_day) VALUES(?,?) ON CONFLICT DO NOTHING",project,day);
            var budget=db.queryForMap("SELECT * FROM sf_model_budget WHERE project_id=? AND budget_day=? FOR UPDATE",project,day);
            if (((Number)budget.get("used_tokens")).intValue()+((Number)budget.get("reserved_tokens")).intValue()+ExtractionModel.RESERVE>dailyLimit)
                throw error(429,"MODEL_DAILY_BUDGET_EXCEEDED","本项目当日模型额度不足，未知结果的调用仍保留预占额度");
            var created=insert(project,user,key,"EXTRACTION",payload);
            db.update("UPDATE sf_model_budget SET reserved_tokens=reserved_tokens+? WHERE project_id=? AND budget_day=?",ExtractionModel.RESERVE,project,day);
            db.update("""
                INSERT INTO sf_extraction_run(id,project_id,job_id,document_id,input_hash,schema_version,prompt_version,model,
                  selected_chunks,request_json,state,reserved_tokens,budget_day)
                VALUES(?,?,?,?,?,?,?,?,?::jsonb,?,'RESERVED',?,?)
                """,UUID.randomUUID(),project,created.job().id(),document,prepared.inputHash(),ExtractionModel.SCHEMA,
                    ExtractionModel.PROMPT,prepared.model(),json.write(prepared.chunks()),prepared.request(),ExtractionModel.RESERVE,day);
            return created;
        });
    }
    public Creation publish(UUID project,UUID user,String key,UUID catalog,List<UUID> candidateIds) {
        key(key); knowledge.snapshot(project,catalog); requireCatalog(project,catalog);
        List<UUID> ids=candidateIds.stream().distinct().sorted().toList();
        if (ids.isEmpty() || ids.size()>8) throw error(400,"INVALID_PUBLICATION","每次请选择1至8条已审核候选");
        String payload=request(Map.of("catalogSnapshotId",catalog,"candidateIds",ids));
        return tx.execute(s->{
            for (UUID id:ids) requireApproved(project,catalog,id);
            return insert(project,user,key,"PUBLICATION",payload);
        });
    }
    public Candidate requireApproved(UUID project,UUID catalog,UUID id) {
        var candidate=candidate(project,id);
        if (!Set.of("APPROVED","PUBLISHED").contains(candidate.status()) || candidate.reviewedAt()==null)
            throw error(409,"CANDIDATE_NOT_APPROVED","仅能发布经过人工审核通过的候选");
        if (db.queryForObject("SELECT count(*) FROM sf_document_catalog WHERE project_id=? AND catalog_snapshot_id=? AND document_id=?",
                Integer.class,project,catalog,candidate.documentId())!=1)
            throw error(409,"DOCUMENT_NOT_IN_RELEASE","候选原文不属于所选采集版本");
        return candidate;
    }
    public Job get(UUID project,UUID id) {
        return db.query("SELECT * FROM sf_evidence_job WHERE project_id=? AND id=?",this::job,project,id).stream().findFirst()
                .orElseThrow(()->error(404,"EVIDENCE_JOB_NOT_FOUND","正文处理任务不存在"));
    }
    public List<Job> jobs(UUID project) { return db.query("SELECT * FROM sf_evidence_job WHERE project_id=? ORDER BY created_at DESC LIMIT 30",this::job,project); }
    public Job claim() {
        return tx.execute(s->{
            var expired=db.query("SELECT * FROM sf_evidence_job WHERE state IN ('QUEUED','RUNNING') AND deadline_at<=clock_timestamp() FOR UPDATE",this::job);
            for (var job:expired) terminate(job,"EVIDENCE_DEADLINE_EXCEEDED","FAILED");
            var rows=db.query("""
                SELECT * FROM sf_evidence_job WHERE state='QUEUED' OR (state='RUNNING' AND lease_until<clock_timestamp())
                ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """,this::job);
            if (rows.isEmpty()) return null;
            var job=rows.getFirst();
            if (job.kind().equals("EXTRACTION") && Boolean.TRUE.equals(db.queryForObject(
                    "SELECT request_sent_at IS NOT NULL AND response_json IS NULL FROM sf_extraction_run WHERE job_id=?",Boolean.class,job.id()))) {
                terminate(job,"MODEL_OUTCOME_UNKNOWN","FAILED"); return null;
            }
            if (job.attempts()>=3) { terminate(job,"EVIDENCE_RECOVERY_EXHAUSTED","FAILED"); return null; }
            db.update("""
                UPDATE sf_evidence_job SET state='RUNNING',attempts=attempts+1,fencing_token=fencing_token+1,
                lease_until=clock_timestamp()+interval '40 seconds',updated_at=clock_timestamp(),error_code=NULL WHERE id=?
                """,job.id());
            return get(job.projectId(),job.id());
        });
    }
    public boolean renew(Job job) {
        return db.update("""
            UPDATE sf_evidence_job SET lease_until=clock_timestamp()+interval '40 seconds'
            WHERE id=? AND fencing_token=? AND state='RUNNING' AND lease_until>clock_timestamp() AND deadline_at>clock_timestamp()
            """,job.id(),job.fencingToken())==1;
    }
    private void lock(Job job) { db.queryForList("SELECT id FROM sf_evidence_job WHERE id=? FOR UPDATE",job.id()); }
    private boolean owns(Job job) {
        return Boolean.TRUE.equals(db.queryForObject("""
            SELECT state='RUNNING' AND fencing_token=? AND lease_until>clock_timestamp() AND deadline_at>clock_timestamp()
            FROM sf_evidence_job WHERE id=?
            """,Boolean.class,job.fencingToken(),job.id()));
    }
    public void fail(Job job,String code) {
        tx.executeWithoutResult(s->{
            lock(job); if (!owns(job)) return;
            if (job.kind().equals("PUBLICATION") && get(job.projectId(),job.id()).resultId()!=null && job.attempts()<3) {
                db.update("UPDATE sf_evidence_job SET lease_until=clock_timestamp()+interval '3 seconds',error_code=? WHERE id=?",code,job.id());
            } else terminate(job,code,"FAILED");
        });
    }
    private void terminate(Job job,String code,String terminal) {
        db.update("UPDATE sf_evidence_job SET state=?,error_code=?,lease_until=NULL,fencing_token=fencing_token+1,updated_at=clock_timestamp() WHERE id=?",terminal,code,job.id());
        if (!job.kind().equals("EXTRACTION")) return;
        var row=db.queryForMap("SELECT * FROM sf_extraction_run WHERE job_id=? FOR UPDATE",job.id());
        if (row.get("request_sent_at")==null && row.get("actual_tokens")==null) {
            db.update("UPDATE sf_model_budget SET reserved_tokens=reserved_tokens-? WHERE project_id=? AND budget_day=?",row.get("reserved_tokens"),job.projectId(),row.get("budget_day"));
            db.update("UPDATE sf_extraction_run SET reserved_tokens=0 WHERE job_id=?",job.id());
        }
        String state=terminal.equals("CANCELLED")?"CANCELLED":row.get("request_sent_at")!=null && row.get("response_json")==null?"UNKNOWN":"FAILED";
        db.update("UPDATE sf_extraction_run SET state=? WHERE job_id=?",state,job.id());
    }
    public Job cancel(UUID project,UUID id) {
        return tx.execute(s->{
            db.queryForList("SELECT id FROM sf_evidence_job WHERE project_id=? AND id=? FOR UPDATE",project,id);
            var job=get(project,id); if (Set.of("QUEUED","RUNNING").contains(job.state())) terminate(job,null,"CANCELLED");
            return get(project,id);
        });
    }
    public UUID saveDocument(Job job,DocumentEvidence.Verified verified) {
        return tx.execute(s->{
            lock(job); if (!owns(job)) return null;
            UUID catalog=UUID.fromString(job.request().path("catalogSnapshotId").asText());
            var snapshot=knowledge.snapshot(job.projectId(),catalog); String provider=job.request().path("providerId").asText();
            var notice=notice(snapshot,provider); UUID id=UUID.randomUUID();
            String published=notice.path("publishedAt").isNull()?null:notice.path("publishedAt").asText();
            db.update("""
                INSERT INTO sf_document_version(id,project_id,catalog_snapshot_id,security_id,provider_id,title,source_url,published_at,fetched_at,
                  pdf_hash,pdf_bytes,parser_version,parsed_json,status)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?) ON CONFLICT(project_id,security_id,provider_id,pdf_hash,parser_version) DO NOTHING
                """,id,job.projectId(),catalog,snapshot.securityId(),provider,notice.path("title").asText(),verified.sourceUrl(),
                    published==null?null:OffsetDateTime.parse(published),OffsetDateTime.parse(verified.fetchedAt()),verified.pdfHash(),verified.pdf(),
                    verified.parserVersion(),json.write(verified.parsed()),verified.status());
            id=db.queryForObject("SELECT id FROM sf_document_version WHERE project_id=? AND security_id=? AND provider_id=? AND pdf_hash=? AND parser_version=?",
                    UUID.class,job.projectId(),snapshot.securityId(),provider,verified.pdfHash(),verified.parserVersion());
            db.update("INSERT INTO sf_document_catalog(project_id,catalog_snapshot_id,document_id) VALUES(?,?,?) ON CONFLICT DO NOTHING",job.projectId(),catalog,id);
            finish(job,id);return id;
        });
    }
    private void finish(Job job,UUID result) {
        db.update("UPDATE sf_evidence_job SET state='SUCCEEDED',result_id=?,lease_until=NULL,updated_at=clock_timestamp() WHERE id=?",result,job.id());
    }
    public Document document(UUID project,UUID id) {
        return db.query("SELECT * FROM sf_document_version WHERE project_id=? AND id=?",this::document,project,id).stream().findFirst()
                .orElseThrow(()->error(404,"DOCUMENT_NOT_FOUND","文档版本不存在"));
    }
    public List<Document> documents(UUID project,UUID catalog) {
        knowledge.snapshot(project,catalog);
        return db.query("""
            SELECT d.* FROM sf_document_version d JOIN sf_document_catalog c ON c.document_id=d.id AND c.project_id=d.project_id
            WHERE c.project_id=? AND c.catalog_snapshot_id=? ORDER BY d.created_at DESC LIMIT 40
            """,this::document,project,catalog);
    }
    public byte[] pdf(UUID project,UUID id) {
        document(project,id); return db.queryForObject("SELECT pdf_bytes FROM sf_document_version WHERE project_id=? AND id=?",byte[].class,project,id);
    }
    public Run runForJob(UUID project,UUID job) {
        return db.queryForObject("SELECT * FROM sf_extraction_run WHERE project_id=? AND job_id=?",this::run,project,job);
    }
    public Run run(UUID project,UUID id) {
        return db.queryForObject("SELECT * FROM sf_extraction_run WHERE project_id=? AND id=?",this::run,project,id);
    }
    public List<Run> runs(UUID project,UUID document) {
        document(project,document);return db.query("SELECT * FROM sf_extraction_run WHERE project_id=? AND document_id=? ORDER BY created_at DESC LIMIT 20",this::run,project,document);
    }
    public String modelRequest(Job job) { return db.queryForObject("SELECT request_json FROM sf_extraction_run WHERE job_id=?",String.class,job.id()); }
    public String modelResponse(Job job) { return db.queryForObject("SELECT response_json FROM sf_extraction_run WHERE job_id=?",String.class,job.id()); }
    public boolean startModel(Job job) {
        return Boolean.TRUE.equals(tx.execute(s->{
            lock(job); if (!owns(job)) return false;
            return db.update("UPDATE sf_extraction_run SET state='REQUESTED',request_sent_at=clock_timestamp() WHERE job_id=? AND request_sent_at IS NULL",job.id())==1;
        }));
    }
    public void captureResponse(Job job,ExtractionModel.Response response) {
        // Even a cancelled in-flight call must keep its actual cost. It cannot publish candidates.
        tx.executeWithoutResult(s->{
            var row=db.queryForMap("SELECT * FROM sf_extraction_run WHERE job_id=? FOR UPDATE",job.id());
            if (row.get("response_json")!=null) return;
            db.update("""
                UPDATE sf_extraction_run SET response_json=?,response_hash=?,actual_tokens=?,reserved_tokens=0,
                state=CASE WHEN state IN ('CANCELLED','FAILED','UNKNOWN') THEN state ELSE 'RESPONSE_RECEIVED' END WHERE job_id=?
                """,response.raw(),JsonCodec.sha256(response.raw()),response.tokens(),job.id());
            db.update("UPDATE sf_model_budget SET reserved_tokens=reserved_tokens-?,used_tokens=used_tokens+? WHERE project_id=? AND budget_day=?",
                    row.get("reserved_tokens"),response.tokens(),job.projectId(),row.get("budget_day"));
        });
    }
    public boolean saveCandidates(Job job,List<DocumentEvidence.Checked> candidates) {
        return Boolean.TRUE.equals(tx.execute(s->{
            lock(job); if (!owns(job)) return false;
            var run=runForJob(job.projectId(),job.id()); int index=0;
            for (var candidate:candidates) db.update("""
                INSERT INTO sf_claim_candidate(id,project_id,document_id,extraction_id,ordinal,predicate,object_text,modality,chunk_id,
                  quote,quote_start,quote_end,raw_json,validation_errors,status)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?) ON CONFLICT(extraction_id,ordinal) DO NOTHING
                """,UUID.randomUUID(),job.projectId(),run.documentId(),run.id(),index++,candidate.predicate(),candidate.object(),candidate.modality(),
                    candidate.chunkId(),candidate.quote(),candidate.quoteStart(),candidate.quoteEnd(),json.write(candidate.raw()),json.write(candidate.errors()),
                    candidate.errors().isEmpty()?"PENDING":"INVALID");
            db.update("UPDATE sf_extraction_run SET state='SUCCEEDED' WHERE job_id=?",job.id());
            finish(job,run.id());return true;
        }));
    }
    public List<Candidate> candidates(UUID project,UUID document) {
        document(project,document);return db.query(candidateSql()+" WHERE c.project_id=? AND c.document_id=? ORDER BY c.created_at,c.ordinal LIMIT 120",this::candidate,project,document);
    }
    public Candidate candidate(UUID project,UUID id) {
        return db.query(candidateSql()+" WHERE c.project_id=? AND c.id=?",this::candidate,project,id).stream().findFirst()
                .orElseThrow(()->error(404,"CANDIDATE_NOT_FOUND","候选不存在"));
    }
    private String candidateSql() { return "SELECT c.*,r.reason,r.reviewed_at,r.reviewer_id FROM sf_claim_candidate c LEFT JOIN sf_claim_review r ON r.candidate_id=c.id"; }
    public Candidate review(UUID project,UUID user,UUID id,String decision,String reason) {
        if (!Set.of("APPROVED","REJECTED").contains(decision) || reason==null || reason.strip().length()<3 || reason.length()>500)
            throw error(400,"INVALID_REVIEW","请选择通过或驳回，并填写3至500字审核说明");
        return tx.execute(s->{
            db.queryForList("SELECT id FROM sf_claim_candidate WHERE project_id=? AND id=? FOR UPDATE",project,id);
            var candidate=candidate(project,id);
            if (candidate.status().equals(decision) || (candidate.status().equals("PUBLISHED") && decision.equals("APPROVED"))) return candidate;
            if (!candidate.status().equals("PENDING")) throw error(409,"CANDIDATE_REVIEW_LOCKED","该候选无法再次审核；无效引用不能通过");
            db.update("INSERT INTO sf_claim_review(candidate_id,project_id,reviewer_id,decision,reason) VALUES(?,?,?,?,?)",id,project,user,decision,reason.strip());
            db.update("UPDATE sf_claim_candidate SET status=? WHERE id=?",decision,id);return candidate(project,id);
        });
    }
    public List<Publication> publications(UUID project,UUID catalog) {
        knowledge.snapshot(project,catalog);
        return db.query("""
            SELECT p.*,s.state FROM sf_claim_publication p JOIN sf_knowledge_snapshot s ON s.id=p.snapshot_id
            WHERE p.project_id=? AND p.catalog_snapshot_id=? AND s.state='READY' ORDER BY p.created_at DESC LIMIT 30
            """,(r,n)->new Publication(r.getObject("snapshot_id",UUID.class),r.getObject("catalog_snapshot_id",UUID.class),
                json.tree(r.getString("candidate_ids")),r.getString("state"),r.getTimestamp("created_at").toInstant().toString()),project,catalog);
    }
    public KnowledgeStore.Snapshot stagePublication(Job job,UUID id,String payload,KnowledgeEvidence.Graph graph,List<UUID> candidates) {
        return tx.execute(s->{
            lock(job); if (!owns(job)) return null;
            UUID catalog=UUID.fromString(job.request().path("catalogSnapshotId").asText());
            var base=knowledge.snapshot(job.projectId(),catalog);
            for (UUID candidate:candidates) requireApproved(job.projectId(),catalog,candidate);
            db.update("""
                INSERT INTO sf_knowledge_snapshot(id,project_id,security_id,content_hash,payload_hash,payload,graph_json,state,fetched_at,kind)
                VALUES(?,?,?,?,?,?,?::jsonb,'PENDING',?,'REVIEWED_CLAIMS') ON CONFLICT(project_id,security_id,content_hash) DO NOTHING
                """,id,job.projectId(),base.securityId(),graph.contentHash(),graph.snapshotHash(),payload,json.write(graph),OffsetDateTime.parse(base.fetchedAt()));
            UUID actual=db.queryForObject("SELECT id FROM sf_knowledge_snapshot WHERE project_id=? AND security_id=? AND content_hash=?",UUID.class,
                    job.projectId(),base.securityId(),graph.contentHash());
            db.update("INSERT INTO sf_claim_publication(snapshot_id,project_id,catalog_snapshot_id,candidate_ids) VALUES(?,?,?,?::jsonb) ON CONFLICT DO NOTHING",
                    actual,job.projectId(),catalog,json.write(candidates));
            db.update("INSERT INTO sf_claim_outbox(job_id,snapshot_id) VALUES(?,?) ON CONFLICT DO NOTHING",job.id(),actual);
            db.update("UPDATE sf_evidence_job SET result_id=? WHERE id=?",actual,job.id());return knowledge.snapshot(job.projectId(),actual);
        });
    }
    public boolean completePublication(Job job,KnowledgeStore.Snapshot snapshot) {
        return Boolean.TRUE.equals(tx.execute(s->{
            lock(job); if (!owns(job)) return false;
            UUID catalog=UUID.fromString(job.request().path("catalogSnapshotId").asText());
            for (var value:job.request().path("candidateIds")) {
                var candidate=requireApproved(job.projectId(),catalog,UUID.fromString(value.asText()));
                db.update("UPDATE sf_claim_candidate SET status='PUBLISHED' WHERE id=?",candidate.id());
            }
            db.update("UPDATE sf_knowledge_snapshot SET state='READY',published_at=COALESCE(published_at,clock_timestamp()) WHERE id=?",snapshot.id());
            db.update("UPDATE sf_claim_outbox SET delivered_at=clock_timestamp() WHERE job_id=?",job.id());finish(job,snapshot.id());return true;
        }));
    }
    public Budget budget(UUID project) {
        var rows=db.queryForList("SELECT * FROM sf_model_budget WHERE project_id=? AND budget_day=(clock_timestamp() AT TIME ZONE 'UTC')::date",project);
        return rows.isEmpty()?new Budget(dailyLimit,0,0):new Budget(dailyLimit,((Number)rows.getFirst().get("reserved_tokens")).intValue(),((Number)rows.getFirst().get("used_tokens")).intValue());
    }
    private Job job(ResultSet r,int n) throws SQLException {
        return new Job(r.getObject("id",UUID.class),r.getObject("project_id",UUID.class),r.getString("kind"),r.getString("state"),r.getObject("result_id",UUID.class),
                r.getInt("attempts"),r.getLong("fencing_token"),r.getString("error_code"),json.tree(r.getString("request_json")),r.getTimestamp("created_at").toInstant().toString());
    }
    private Document document(ResultSet r,int n) throws SQLException {
        return new Document(r.getObject("id",UUID.class),r.getObject("catalog_snapshot_id",UUID.class),r.getString("security_id"),r.getString("provider_id"),r.getString("title"),
                r.getString("source_url"),r.getString("pdf_hash"),r.getString("parser_version"),r.getTimestamp("published_at")==null?null:r.getTimestamp("published_at").toInstant().toString(),
                r.getTimestamp("fetched_at").toInstant().toString(),r.getString("status"),json.tree(r.getString("parsed_json")));
    }
    private Run run(ResultSet r,int n) throws SQLException {
        return new Run(r.getObject("id",UUID.class),r.getObject("job_id",UUID.class),r.getObject("document_id",UUID.class),r.getString("state"),r.getString("model"),r.getString("schema_version"),
                r.getString("prompt_version"),r.getString("input_hash"),json.tree(r.getString("selected_chunks")),r.getObject("actual_tokens",Integer.class),r.getInt("reserved_tokens"),r.getTimestamp("created_at").toInstant().toString());
    }
    private Candidate candidate(ResultSet r,int n) throws SQLException {
        return new Candidate(r.getObject("id",UUID.class),r.getObject("document_id",UUID.class),r.getObject("extraction_id",UUID.class),r.getString("predicate"),r.getString("object_text"),
                r.getString("modality"),r.getString("chunk_id"),r.getString("quote"),r.getInt("quote_start"),r.getInt("quote_end"),r.getString("status"),json.tree(r.getString("validation_errors")),
                json.tree(r.getString("raw_json")),r.getString("reason"),r.getTimestamp("reviewed_at")==null?null:r.getTimestamp("reviewed_at").toInstant().toString(),r.getString("reviewer_id"));
    }
    private static ApiException error(int status,String code,String message) { return new ApiException(status,code,message); }
}
