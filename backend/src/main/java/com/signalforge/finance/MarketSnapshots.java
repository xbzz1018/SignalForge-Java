package com.signalforge.finance;

import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.research.ResearchRequest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;

/** Immutable accepted inputs. Reuse is exact-range and never extends source freshness. */
@Repository
public class MarketSnapshots {
    private final JdbcTemplate db;
    private final JsonCodec json;

    public MarketSnapshots(JdbcTemplate db, JsonCodec json) { this.db=db; this.json=json; }

    public List<JsonNode> find(UUID project, ResearchRequest request) {
        var found=new ArrayList<JsonNode>();
        if (!request.priceRange().end().isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai")))) return found;
        for (var security:request.securities()) {
            found.addAll(db.query("""
                SELECT series_json::text FROM sf_market_snapshot
                WHERE project_id=? AND security_id=? AND market=? AND adjustment=?
                  AND range_start=? AND range_end=? AND reusable_until>clock_timestamp()
                ORDER BY captured_at DESC LIMIT 1
                """,(r,n)->json.tree(r.getString(1)),project,security.securityId(),security.market(),
                request.priceAdjustment(),request.priceRange().start(),request.priceRange().end()));
        }
        return found;
    }

    /** Called in the result publication transaction, after evidence verification and fencing. */
    public void save(UUID project, UUID task, ResearchRequest request, String response) {
        var root=json.tree(response);
        var series=root.path("series");
        if (!series.isArray()) return;
        for (var item:series) {
            String version=item.path("dataVersion").asText();
            String key=project+"|"+item.path("market").asText()+"|"+item.path("securityId").asText()
                    +"|"+request.priceAdjustment()+"|"+request.priceRange().start()+"|"+request.priceRange().end()+"|"+version
                    +"|"+item.path("sourceRefs").get(0).path("fetchedAt").asText();
            db.update("""
                INSERT INTO sf_market_snapshot(id,project_id,task_id,security_id,market,adjustment,
                  range_start,range_end,data_version,demo_data,series_json,reusable_until)
                VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb,?::timestamptz+interval '24 hours') ON CONFLICT(id) DO NOTHING
                """,JsonCodec.sha256(key),project,task,item.path("securityId").asText(),
                item.path("market").asText(),request.priceAdjustment(),request.priceRange().start(),
                request.priceRange().end(),version,root.path("demoData").asBoolean(),item.toString(),
                item.path("sourceRefs").get(0).path("fetchedAt").asText());
        }
    }

    public List<JsonNode> forTask(UUID project, UUID task) {
        return db.query("""
            SELECT s.series_json::text FROM sf_market_snapshot s JOIN sf_task_result r ON r.task_id=?
            WHERE s.project_id=? AND EXISTS (
              SELECT 1 FROM jsonb_array_elements(r.result_json->'series') x
              WHERE x->>'securityId'=s.security_id AND x->>'market'=s.market
                AND x->>'dataVersion'=s.data_version AND x->'sourceRefs'=s.series_json->'sourceRefs')
              AND s.range_start=(SELECT (request_json->'priceRange'->>'start')::date FROM sf_task WHERE id=r.task_id)
              AND s.range_end=(SELECT (request_json->'priceRange'->>'end')::date FROM sf_task WHERE id=r.task_id)
            ORDER BY s.market,s.security_id
            """,(r,n)->json.tree(r.getString(1)),task,project);
    }
}
