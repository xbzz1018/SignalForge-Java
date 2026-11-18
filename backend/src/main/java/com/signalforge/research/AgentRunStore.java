package com.signalforge.research;

import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.platform.ApiException;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Persist before network I/O. Late responses may settle cost but cannot publish a task result. */
@Repository
public class AgentRunStore {
    public record Saved(String input, String response, String state) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final int dailyLimit;
    public AgentRunStore(JdbcTemplate db, TransactionTemplate tx,
            @Value("${signalforge.model-daily-token-limit}") int dailyLimit) {
        this.db=db; this.tx=tx; this.dailyLimit=dailyLimit;
    }
    public Saved find(UUID task) {
        return db.query("SELECT input_json,response_json::text,state,input_hash FROM sf_agent_run WHERE task_id=?", (r,n)->{
            if (!JsonCodec.sha256(r.getString(1)).equals(r.getString(4))) throw failure("AGENT_INPUT_CORRUPTED");
            return new Saved(r.getString(1),r.getString(2),r.getString(3));
        },task).stream().findFirst().orElse(null);
    }
    public boolean active(TaskStore.Claim c) {
        return Boolean.TRUE.equals(db.queryForObject("""
            SELECT t.fencing_token=? AND t.state IN ('RUNNING','VERIFYING')
              AND t.lease_until>clock_timestamp() AND t.deadline_at>clock_timestamp()
              AND EXISTS (SELECT 1 FROM sf_project_member m JOIN sf_user u ON u.id=m.user_id
                WHERE m.project_id=t.project_id AND m.user_id=t.created_by AND m.role='OWNER' AND u.enabled)
            FROM sf_task t WHERE t.id=? AND t.project_id=?
            """,Boolean.class,c.token(),c.task().id(),c.task().projectId()));
    }
    public void begin(TaskStore.Claim c, String input, int reservation) {
        tx.executeWithoutResult(s->{
            db.queryForObject("SELECT id FROM sf_task WHERE id=? FOR UPDATE",UUID.class,c.task().id());
            if (!active(c)) throw failure("AGENT_EXECUTION_REVOKED");
            if (find(c.task().id())!=null) throw failure("AGENT_OUTCOME_UNKNOWN");
            LocalDate day=db.queryForObject("SELECT (clock_timestamp() AT TIME ZONE 'UTC')::date",LocalDate.class);
            db.update("INSERT INTO sf_model_budget(project_id,budget_day) VALUES(?,?) ON CONFLICT DO NOTHING",c.task().projectId(),day);
            int updated=db.update("""
                UPDATE sf_model_budget SET reserved_tokens=reserved_tokens+?
                WHERE project_id=? AND budget_day=? AND reserved_tokens+used_tokens+?<=?
                """,reservation,c.task().projectId(),day,reservation,dailyLimit);
            if (updated!=1 && reservation>0) throw failure("MODEL_DAILY_BUDGET_EXCEEDED");
            db.update("""
                INSERT INTO sf_agent_run(task_id,run_id,input_json,input_hash,state,budget_day,reserved_tokens)
                VALUES(?,?,?,?,'SENDING',?,?)
                """,c.task().id(),c.runId(),input,JsonCodec.sha256(input),day,reservation);
        });
    }
    public void returned(TaskStore.Claim c, String response, int tokens, boolean usageComplete) {
        tx.executeWithoutResult(s->{
            var row=db.queryForMap("SELECT state,budget_day,reserved_tokens FROM sf_agent_run WHERE task_id=? AND run_id=? FOR UPDATE",c.task().id(),c.runId());
            if (!row.get("state").equals("SENDING")) return;
            int reserved=((Number)row.get("reserved_tokens")).intValue();
            int held=usageComplete?0:Math.max(0,reserved-tokens);
            db.update("UPDATE sf_agent_run SET state='RETURNED',response_json=?::jsonb,actual_tokens=?,reserved_tokens=?,received_at=clock_timestamp() WHERE task_id=?",response,tokens,held,c.task().id());
            db.update("UPDATE sf_model_budget SET reserved_tokens=reserved_tokens-?,used_tokens=used_tokens+? WHERE project_id=? AND budget_day=?",
                    reserved-held,tokens,c.task().projectId(),row.get("budget_day"));
        });
    }
    public void unknown(TaskStore.Claim c) {
        // Reservation deliberately remains charged until manual reconciliation.
        db.update("UPDATE sf_agent_run SET state='UNKNOWN' WHERE task_id=? AND run_id=? AND state='SENDING'",c.task().id(),c.runId());
    }
    public static ApiException failure(String code) { return new ApiException(503,code,"知识问答未完成："+code); }
}
