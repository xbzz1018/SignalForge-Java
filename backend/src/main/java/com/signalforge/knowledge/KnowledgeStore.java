package com.signalforge.knowledge;

import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.platform.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class KnowledgeStore {
    public record Job(UUID id,UUID projectId,String securityId,String state,UUID snapshotId,int attempts,
                      long fencingToken,boolean reused,String errorCode,String createdAt) {}
    public record Creation(Job job,boolean created) {}
    public record Snapshot(UUID id,UUID projectId,String securityId,String payload,String graph,String contentHash,
                           String payloadHash,String fetchedAt,String state) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final KnowledgeEvidence evidence;
    public KnowledgeStore(JdbcTemplate db,TransactionTemplate tx,JsonCodec json,KnowledgeEvidence evidence) {
        this.db=db; this.tx=tx; this.json=json; this.evidence=evidence;
    }
    public Creation create(UUID project,UUID user,String key,String security) {
        if (key==null || !key.matches("[a-zA-Z0-9_-]{8,128}"))
            throw new ApiException(400,"INVALID_IDEMPOTENCY_KEY","幂等键应为8至128位字母、数字、下划线或连字符");
        return tx.execute(s->{
            UUID id=UUID.randomUUID();
            int inserted=db.update("""
                INSERT INTO sf_knowledge_job(id,project_id,created_by,idempotency_key,security_id,state,deadline_at)
                VALUES(?,?,?,?,?,'QUEUED',clock_timestamp()+interval '180 seconds')
                ON CONFLICT(project_id,idempotency_key) DO NOTHING
                """,id,project,user,key,security);
            Job result=db.queryForObject("SELECT * FROM sf_knowledge_job WHERE project_id=? AND idempotency_key=?",this::job,project,key);
            if (!result.securityId().equals(security)) throw new ApiException(409,"IDEMPOTENCY_CONFLICT","此幂等键已用于其他证券");
            return new Creation(result,inserted==1);
        });
    }
    public Job get(UUID project,UUID id) {
        return db.query("SELECT * FROM sf_knowledge_job WHERE project_id=? AND id=?",this::job,project,id).stream().findFirst()
                .orElseThrow(()->new ApiException(404,"KNOWLEDGE_JOB_NOT_FOUND","采集任务不存在"));
    }
    public List<Job> list(UUID project) {
        return db.query("SELECT * FROM sf_knowledge_job WHERE project_id=? ORDER BY created_at DESC LIMIT 30",this::job,project);
    }
    public Job claim() {
        return tx.execute(s->{
            db.update("""
                UPDATE sf_knowledge_job SET state='FAILED',error_code='KNOWLEDGE_DEADLINE_EXCEEDED',
                fencing_token=fencing_token+1,lease_until=NULL,updated_at=clock_timestamp()
                WHERE state IN ('QUEUED','COLLECTING','PROJECTING') AND deadline_at<=clock_timestamp()
                """);
            var jobs=db.query("""
                SELECT * FROM sf_knowledge_job WHERE state='QUEUED' OR
                (state IN ('COLLECTING','PROJECTING') AND lease_until<clock_timestamp())
                ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """,this::job);
            if (jobs.isEmpty()) return null;
            Job old=jobs.getFirst();
            if (old.attempts()>=3) {
                db.update("UPDATE sf_knowledge_job SET state='FAILED',error_code='KNOWLEDGE_RECOVERY_EXHAUSTED',lease_until=NULL WHERE id=?",old.id());
                return null;
            }
            db.update("""
                UPDATE sf_knowledge_job SET state=CASE WHEN snapshot_id IS NULL THEN 'COLLECTING' ELSE 'PROJECTING' END,
                attempts=attempts+1,fencing_token=fencing_token+1,lease_until=clock_timestamp()+interval '40 seconds',
                error_code=NULL,updated_at=clock_timestamp() WHERE id=?
                """,old.id());
            return get(old.projectId(),old.id());
        });
    }
    public boolean renew(Job claim) {
        return db.update("""
            UPDATE sf_knowledge_job SET lease_until=clock_timestamp()+interval '40 seconds'
            WHERE id=? AND fencing_token=? AND state IN ('COLLECTING','PROJECTING')
            AND lease_until>clock_timestamp() AND deadline_at>clock_timestamp()
            """,claim.id(),claim.fencingToken())==1;
    }
    public Snapshot cached(Job claim) {
        var values=db.query("""
            SELECT * FROM sf_knowledge_snapshot WHERE project_id=? AND security_id=?
            AND state='READY' AND kind='CATALOG' AND fetched_at>clock_timestamp()-interval '1 hour'
            ORDER BY fetched_at DESC LIMIT 1
            """,this::snapshot,claim.projectId(),claim.securityId());
        return values.isEmpty()?null:values.getFirst();
    }
    public Snapshot stage(Job claim,KnowledgeEvidence.Verified verified,Snapshot cached) {
        return tx.execute(s->{
            lock(claim); if (!owns(claim)) return null;
            UUID id=cached!=null?cached.id():UUID.randomUUID();
            if (cached==null) {
                var graph=evidence.graph(id,verified);
                db.update("""
                    INSERT INTO sf_knowledge_snapshot(id,project_id,security_id,content_hash,payload_hash,payload,graph_json,state,fetched_at)
                    VALUES(?,?,?,?,?,?,?::jsonb,'PENDING',?) ON CONFLICT(project_id,security_id,content_hash) DO NOTHING
                    """,id,claim.projectId(),claim.securityId(),verified.contentHash(),verified.payloadHash(),verified.payload(),
                        json.write(graph),OffsetDateTime.parse(verified.fetchedAt()));
                id=db.queryForObject("SELECT id FROM sf_knowledge_snapshot WHERE project_id=? AND security_id=? AND content_hash=?",
                        UUID.class,claim.projectId(),claim.securityId(),verified.contentHash());
            }
            Snapshot result=snapshot(claim.projectId(),id);
            db.update("""
                UPDATE sf_knowledge_job SET snapshot_id=?,state='PROJECTING',reused=?,updated_at=clock_timestamp() WHERE id=?
                """,id,cached!=null || result.state().equals("READY"),claim.id());
            db.update("INSERT INTO sf_knowledge_outbox(job_id,snapshot_id) VALUES(?,?) ON CONFLICT DO NOTHING",claim.id(),id);
            return result;
        });
    }
    public Snapshot snapshot(UUID project,UUID id) {
        return db.query("SELECT * FROM sf_knowledge_snapshot WHERE project_id=? AND id=?",this::snapshot,project,id).stream().findFirst()
                .orElseThrow(()->new ApiException(404,"GRAPH_RELEASE_NOT_FOUND","图谱版本不存在"));
    }
    /** One immutable reviewed batch; independent publications are not implicitly merged. */
    public Snapshot latestReviewed(UUID project,String security) {
        return db.query("""
            SELECT * FROM sf_knowledge_snapshot
            WHERE project_id=? AND security_id=? AND state='READY' AND kind='REVIEWED_CLAIMS'
            ORDER BY published_at DESC NULLS LAST,id DESC LIMIT 1
            """,this::snapshot,project,security).stream().findFirst().orElse(null);
    }
    public boolean complete(Job claim,Snapshot snapshot) {
        return Boolean.TRUE.equals(tx.execute(s->{
            lock(claim); if (!owns(claim)) return false;
            db.update("UPDATE sf_knowledge_snapshot SET state='READY',published_at=COALESCE(published_at,clock_timestamp()) WHERE id=?",snapshot.id());
            db.update("UPDATE sf_knowledge_outbox SET delivered_at=clock_timestamp() WHERE job_id=?",claim.id());
            db.update("UPDATE sf_knowledge_job SET state='SUCCEEDED',lease_until=NULL,updated_at=clock_timestamp() WHERE id=?",claim.id());
            return true;
        }));
    }
    public void fail(Job claim,String code) {
        tx.executeWithoutResult(s->{
            lock(claim); if (!owns(claim)) return;
            // Projection can replay idempotently, retaining the original immutable source snapshot.
            boolean retry=get(claim.projectId(),claim.id()).snapshotId()!=null && claim.attempts()<3;
            db.update("""
                UPDATE sf_knowledge_job SET state=?,error_code=?,lease_until=clock_timestamp()+interval '3 seconds',
                updated_at=clock_timestamp() WHERE id=?
                """,retry?"PROJECTING":"FAILED",code,claim.id());
        });
    }
    public Job cancel(UUID project,UUID id) {
        return tx.execute(s->{
            db.queryForList("SELECT id FROM sf_knowledge_job WHERE project_id=? AND id=? FOR UPDATE",project,id);
            get(project,id);
            db.update("""
                UPDATE sf_knowledge_job SET state='CANCELLED',fencing_token=fencing_token+1,lease_until=NULL,
                updated_at=clock_timestamp() WHERE id=? AND state IN ('QUEUED','COLLECTING','PROJECTING')
                """,id);
            return get(project,id);
        });
    }
    private void lock(Job claim) { db.queryForList("SELECT id FROM sf_knowledge_job WHERE id=? FOR UPDATE",claim.id()); }
    private boolean owns(Job claim) {
        return Boolean.TRUE.equals(db.queryForObject("""
            SELECT fencing_token=? AND state IN ('COLLECTING','PROJECTING') AND lease_until>clock_timestamp()
            AND deadline_at>clock_timestamp() FROM sf_knowledge_job WHERE id=?
            """,Boolean.class,claim.fencingToken(),claim.id()));
    }
    private Job job(ResultSet r,int n) throws SQLException {
        return new Job(r.getObject("id",UUID.class),r.getObject("project_id",UUID.class),r.getString("security_id"),
                r.getString("state"),r.getObject("snapshot_id",UUID.class),r.getInt("attempts"),r.getLong("fencing_token"),
                r.getBoolean("reused"),r.getString("error_code"),r.getTimestamp("created_at").toInstant().toString());
    }
    private Snapshot snapshot(ResultSet r,int n) throws SQLException {
        return new Snapshot(r.getObject("id",UUID.class),r.getObject("project_id",UUID.class),r.getString("security_id"),
                r.getString("payload"),r.getString("graph_json"),r.getString("content_hash"),r.getString("payload_hash"),
                r.getTimestamp("fetched_at").toInstant().toString(),r.getString("state"));
    }
}
