package com.signalforge.research;

import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.finance.MarketSnapshots;
import com.signalforge.platform.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@Repository
public class TaskStore {
    public record Task(UUID id, UUID projectId, String state, long eventSequence, long fencingToken,
                       int attempts, Instant deadlineAt, Instant createdAt, Instant updatedAt,
                       String errorCode, ResearchRequest request) {
        public boolean terminal() { return isTerminal(state); }
    }
    private static boolean isTerminal(String state) {
        return List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state);
    }
    record Progress(String state, long sequence) {}
    record EventBatch(List<Event> events, boolean terminal, long sequence) {}
    public record Creation(Task task, boolean created) {}
    public record Claim(Task task, UUID runId, UUID callId, long token) {}
    public record Event(long sequence, String type, JsonNode payload, Instant createdAt) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final int leaseSeconds, deadlineSeconds, maxAttempts;
    private final MarketSnapshots snapshots;
    private final ResearchExecutors executors;

    public TaskStore(JdbcTemplate db, TransactionTemplate tx, JsonCodec json, MarketSnapshots snapshots, ResearchExecutors executors,
            @Value("${signalforge.lease-seconds}") int leaseSeconds,
            @Value("${signalforge.deadline-seconds}") int deadlineSeconds,
            @Value("${signalforge.max-attempts}") int maxAttempts) {
        this.db=db; this.tx=tx; this.json=json; this.leaseSeconds=leaseSeconds;
        this.snapshots=snapshots; this.executors=executors;
        this.deadlineSeconds=deadlineSeconds; this.maxAttempts=maxAttempts;
        if (leaseSeconds<10 || deadlineSeconds<1 || maxAttempts<1) throw new IllegalArgumentException("Invalid worker limits");
    }
    public Creation create(UUID project, UUID user, String key, ResearchRequest request) {
        if (key==null || !key.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new ApiException(400,"INVALID_IDEMPOTENCY_KEY","请提供1至128位的 Idempotency-Key");
        String body=json.write(request), hash=JsonCodec.sha256(body);
        return tx.execute(s -> {
            UUID id=UUID.randomUUID();
            int added=db.update("""
                INSERT INTO sf_task(id,project_id,created_by,idempotency_key,request_hash,request_json,state,deadline_at)
                VALUES(?,?,?,?,?,?::jsonb,'QUEUED',clock_timestamp()+make_interval(secs=>?))
                ON CONFLICT(project_id,idempotency_key) DO NOTHING
                """,id,project,user,key,hash,body,deadlineSeconds);
            if (added==0) {
                var rows=db.queryForMap("SELECT id,request_hash FROM sf_task WHERE project_id=? AND idempotency_key=?",project,key);
                if (!hash.equals(rows.get("request_hash"))) throw new ApiException(409,"IDEMPOTENCY_CONFLICT","同一幂等键已经用于其他请求");
                return new Creation(get(project,(UUID)rows.get("id")),false);
            }
            event(id,"QUEUED",Map.of("requestHash",hash));
            db.update("INSERT INTO sf_outbox(id,task_id,type) VALUES(?,?,'RESEARCH_DISPATCH')",UUID.randomUUID(),id);
            return new Creation(get(project,id),true);
        });
    }
    public Task get(UUID project, UUID id) {
        return db.query("SELECT * FROM sf_task WHERE project_id=? AND id=?",this::map,project,id).stream()
                .findFirst().orElseThrow(()->new ApiException(404,"TASK_NOT_FOUND","任务不存在"));
    }
    public List<Task> list(UUID project) {
        return list(project,null);
    }
    public List<Task> list(UUID project, String researchType) {
        if (researchType==null)
            return db.query("SELECT * FROM sf_task WHERE project_id=? ORDER BY created_at DESC,id DESC LIMIT 100",this::map,project);
        return db.query("""
            SELECT * FROM sf_task WHERE project_id=? AND request_json->>'researchType'=?
            ORDER BY created_at DESC,id DESC LIMIT 100
            """,this::map,project,researchType);
    }
    public Task cancel(UUID project, UUID id) {
        return tx.execute(s -> {
            Task t=locked(project,id);
            if (t.terminal()) return t;
            db.update("UPDATE sf_task SET state='CANCELLED',lease_until=NULL,fencing_token=fencing_token+1 WHERE id=?",id);
            closeRuns(id,"CANCELLED");
            finishDispatch(id);
            event(id,"CANCELLED",Map.of("reason","USER_REQUEST"));
            return get(project,id);
        });
    }
    public JsonNode result(UUID project, UUID id) {
        requireResultReady(project, id);
        return json.tree(db.queryForObject("SELECT result_json::text FROM sf_task_result WHERE task_id=?",String.class,id));
    }
    private Progress progress(UUID project, UUID id) {
        return db.query("SELECT state,event_seq FROM sf_task WHERE project_id=? AND id=?",
                (r,n) -> new Progress(r.getString("state"), r.getLong("event_seq")), project, id).stream()
                .findFirst().orElseThrow(() -> new ApiException(404,"TASK_NOT_FOUND","任务不存在"));
    }
    void requireResultReady(UUID project, UUID id) {
        if (!progress(project,id).state().equals("SUCCEEDED"))
            throw new ApiException(409,"RESULT_NOT_READY","任务尚未成功验收");
    }
    Progress validateCursor(UUID project, UUID id, long after) {
        var progress = progress(project, id);
        if (after < 0 || after > progress.sequence())
            throw new ApiException(400,"INVALID_EVENT_CURSOR","事件序号超出任务范围");
        return progress;
    }
    EventBatch eventBatch(UUID project, UUID id, long after) {
        var progress = validateCursor(project, id, after);
        List<Event> events = after == progress.sequence() ? List.of() : db.query("""
                SELECT sequence,type,payload,created_at FROM sf_task_event
                WHERE task_id=? AND sequence>? AND sequence<=? ORDER BY sequence LIMIT 200
                """, (r,n) -> new Event(r.getLong("sequence"),r.getString("type"),json.tree(r.getString("payload")),
                        r.getTimestamp("created_at").toInstant()), id, after, progress.sequence());
        return new EventBatch(events, isTerminal(progress.state()), progress.sequence());
    }
    public List<Event> events(UUID project, UUID id, long after) {
        return eventBatch(project, id, after).events();
    }
    public Claim claim(String worker) {
        return tx.execute(s -> {
            var candidates=db.query("""
                SELECT * FROM sf_task WHERE (state='QUEUED' AND EXISTS
                  (SELECT 1 FROM sf_outbox o WHERE o.task_id=sf_task.id AND o.type='RESEARCH_DISPATCH' AND o.delivered_at IS NULL))
                   OR (state IN ('RUNNING','VERIFYING') AND lease_until<clock_timestamp())
                ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """,this::map);
            if (candidates.isEmpty()) return null;
            Task t=candidates.getFirst();
            boolean expired=Boolean.TRUE.equals(db.queryForObject("SELECT deadline_at<=clock_timestamp() FROM sf_task WHERE id=?",Boolean.class,t.id()));
            if (expired || t.attempts()>=maxAttempts) {
                failLocked(t,expired?"DEADLINE_EXCEEDED":"RECOVERY_EXHAUSTED"); return null;
            }
            closeRuns(t.id(),"ABANDONED");
            long token=t.fencingToken()+1;
            db.update("""
                UPDATE sf_task SET state='RUNNING',attempts=attempts+1,fencing_token=?,
                lease_until=clock_timestamp()+make_interval(secs=>?),error_code=NULL WHERE id=?
                """,token,leaseSeconds,t.id());
            UUID run=UUID.randomUUID(), call=UUID.randomUUID();
            db.update("INSERT INTO sf_task_run(id,task_id,fencing_token,worker_id,status) VALUES(?,?,?,?,'RUNNING')",run,t.id(),token,worker);
            db.update("INSERT INTO sf_tool_call(id,run_id,tool_name,request_hash,status) SELECT ?,?,?,request_hash,'RUNNING' FROM sf_task WHERE id=?",call,run,executors.forType(t.request().researchType()).toolName(),t.id());
            finishDispatch(t.id());
            event(t.id(),"RUNNING",Map.of("runId",run,"fencingToken",token,"attempt",t.attempts()+1));
            return new Claim(get(t.projectId(),t.id()),run,call,token);
        });
    }
    public boolean renew(Claim c) {
        return db.update("""
            UPDATE sf_task SET lease_until=clock_timestamp()+make_interval(secs=>?)
            WHERE id=? AND fencing_token=? AND state IN ('RUNNING','VERIFYING')
            AND lease_until>clock_timestamp() AND deadline_at>clock_timestamp()
            """,leaseSeconds,c.task().id(),c.token())==1;
    }
    public boolean verifying(Claim c, String response) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            Task t=locked(c.task().projectId(),c.task().id());
            if (!owns(c) || !authorized(t)) return false;
            db.update("UPDATE sf_task SET state='VERIFYING' WHERE id=?",c.task().id());
            db.update("UPDATE sf_tool_call SET status='RETURNED',response_json=?::jsonb,finished_at=clock_timestamp() WHERE id=?",response,c.callId());
            event(c.task().id(),"VERIFYING",Map.of("runId",c.runId()));
            return true;
        }));
    }
    public boolean complete(Claim c, String response) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            Task t=locked(c.task().projectId(),c.task().id());
            if (!owns(c) || !t.state().equals("VERIFYING") || !authorized(t)) return false;
            String resultHash = JsonCodec.sha256(response);
            db.update("""
                INSERT INTO sf_task_result(task_id,run_id,request_hash,result_hash,result_json)
                SELECT id,?,request_hash,?,?::jsonb FROM sf_task WHERE id=?
                """,c.runId(),resultHash,response,t.id());
            if ("ETF_RISK_COMPARISON".equals(t.request().researchType()))
                snapshots.save(t.projectId(),t.id(),t.request(),response);
            db.update("UPDATE sf_task SET state='SUCCEEDED',lease_until=NULL WHERE id=?",t.id());
            db.update("UPDATE sf_tool_call SET status='ACCEPTED' WHERE id=?",c.callId());
            closeRuns(t.id(),"SUCCEEDED");
            event(t.id(),"SUCCEEDED",Map.of("resultHash",resultHash));
            return true;
        }));
    }
    public void fail(Claim c, String code) {
        tx.executeWithoutResult(s -> {
            Task t=locked(c.task().projectId(),c.task().id());
            if (owns(c)) {
                db.update("UPDATE sf_tool_call SET status='FAILED',error_code=?,finished_at=clock_timestamp() WHERE id=?",code,c.callId());
                failLocked(t,code);
            }
        });
    }
    public void expireDeadlines() {
        tx.executeWithoutResult(s -> {
            var tasks=db.query("SELECT * FROM sf_task WHERE state IN ('QUEUED','RUNNING','VERIFYING') AND deadline_at<=clock_timestamp() LIMIT 50 FOR UPDATE SKIP LOCKED",this::map);
            for (Task t:tasks) failLocked(t,"DEADLINE_EXCEEDED");
        });
    }
    private boolean owns(Claim c) {
        return Boolean.TRUE.equals(db.queryForObject("""
            SELECT fencing_token=? AND state IN ('RUNNING','VERIFYING')
            AND lease_until>clock_timestamp() AND deadline_at>clock_timestamp() FROM sf_task WHERE id=?
            """,Boolean.class,c.token(),c.task().id()));
    }
    private boolean authorized(Task t) {
        return !"KNOWLEDGE_QA".equals(t.request().researchType()) || Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM sf_project_member m JOIN sf_user u ON u.id=m.user_id
              JOIN sf_task t ON t.project_id=m.project_id AND t.created_by=m.user_id
              WHERE t.id=? AND m.role='OWNER' AND u.enabled)
            """,Boolean.class,t.id()));
    }
    private void failLocked(Task t, String code) {
        db.update("UPDATE sf_task SET state='FAILED',error_code=?,lease_until=NULL,fencing_token=fencing_token+1 WHERE id=?",code,t.id());
        closeRuns(t.id(),"FAILED"); event(t.id(),"FAILED",Map.of("code",code));
        finishDispatch(t.id());
    }
    private void finishDispatch(UUID task) {
        db.update("UPDATE sf_outbox SET delivered_at=clock_timestamp() WHERE task_id=? AND type='RESEARCH_DISPATCH' AND delivered_at IS NULL",task);
    }
    private void closeRuns(UUID task, String status) {
        db.update("""
            UPDATE sf_tool_call SET status='UNCONFIRMED',finished_at=clock_timestamp()
            WHERE status IN ('RUNNING','RETURNED') AND run_id IN (SELECT id FROM sf_task_run WHERE task_id=? AND status='RUNNING')
            """,task);
        db.update("UPDATE sf_task_run SET status=?,finished_at=clock_timestamp() WHERE task_id=? AND status='RUNNING'",status,task);
    }
    private Task locked(UUID project, UUID id) {
        return db.query("SELECT * FROM sf_task WHERE project_id=? AND id=? FOR UPDATE",this::map,project,id).stream()
                .findFirst().orElseThrow(()->new ApiException(404,"TASK_NOT_FOUND","任务不存在"));
    }
    private void event(UUID id, String type, Object payload) {
        Long seq=db.queryForObject("UPDATE sf_task SET event_seq=event_seq+1,updated_at=clock_timestamp() WHERE id=? RETURNING event_seq",Long.class,id);
        db.update("INSERT INTO sf_task_event(task_id,sequence,type,payload) VALUES(?,?,?,?::jsonb)",id,seq,type,json.write(payload));
    }
    private Task map(ResultSet r, int n) throws SQLException {
        return new Task(r.getObject("id",UUID.class),r.getObject("project_id",UUID.class),r.getString("state"),
                r.getLong("event_seq"),r.getLong("fencing_token"),r.getInt("attempts"),r.getTimestamp("deadline_at").toInstant(),
                r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),r.getString("error_code"),json.read(r.getString("request_json"),ResearchRequest.class));
    }
}
