package com.signalforge.research;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.signalforge.platform.ApiException;

@Component
public class RequestPolicy {
    private final boolean fixtures;
    public RequestPolicy(@Value("${signalforge.allow-fixtures}") boolean fixtures) { this.fixtures=fixtures; }
    public void validate(ResearchRequest r) {
        if (!"ETF_RISK_COMPARISON".equals(r.researchType()) || !"DETERMINISTIC".equals(r.executionMode()))
            throw new ApiException(422,"UNSUPPORTED_RESEARCH","当前版本支持确定性的 ETF_RISK_COMPARISON");
        var range=r.priceRange();
        if (!"DAILY".equals(range.frequency()) || !"NONE".equals(r.priceAdjustment()))
            invalid("当前仅支持 DAILY 与 NONE 复权口径");
        ZoneId marketZone=ZoneId.of("Asia/Shanghai");
        if (range.end().isBefore(range.start()) || range.end().isAfter(LocalDate.now(marketZone))
                || ChronoUnit.DAYS.between(range.start(),range.end())>730) invalid("行情区间无效或超过730天");
        if (r.knowledgeAsOf().isAfter(OffsetDateTime.now(ZoneOffset.UTC))
                || range.end().isAfter(r.knowledgeAsOf().atZoneSameInstant(marketZone).toLocalDate())) invalid("行情结束日不能晚于研究截止日");
        if (new HashSet<>(r.securities()).size()!=2 || new HashSet<>(r.metrics()).size()!=r.metrics().size()) invalid("证券与指标不能重复");
        boolean demo=r.securities().stream().anyMatch(s->s.market().equals("FIXTURE"));
        for (var s:r.securities()) {
            if (demo) {
                if (!fixtures || !s.market().equals("FIXTURE") || !s.securityId().matches("fixture:ETF_[AB]")) invalid("演示证券未启用或混用了真实行情");
            } else if (!s.supportsEtfHistory()) invalid("当前真实行情只支持 SSE 5开头或 SZSE 1开头的基金代码");
        }
    }
    private void invalid(String message) { throw new ApiException(422,"INVALID_RESEARCH_PARAMETERS",message); }
}
