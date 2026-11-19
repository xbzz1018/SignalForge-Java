package com.signalforge.finance;

import com.signalforge.finance.EtfPriceSource.Bar;
import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.research.ResearchRequest;
import com.signalforge.research.ResearchRequest.Security;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class RiskService {
    private static final Map<String, List<String>> FIXTURES = Map.of(
            "fixture:ETF_A", List.of("100", "110", "99", "108.9", "103.455", "113.8005"),
            "fixture:ETF_B", List.of("100", "98", "102", "101", "104", "106"));
    private static final List<Integer> FIXTURE_DAYS = List.of(2, 3, 6, 7, 8, 9);
    private final JsonCodec json;
    private final EtfPriceSource source;
    private final boolean fixtures;

    public RiskService(JsonCodec json, EtfPriceSource source, @Value("${signalforge.allow-fixtures}") boolean fixtures) {
        this.json = json;
        this.source = source;
        this.fixtures = fixtures;
    }

    public String calculate(ResearchRequest request, List<JsonNode> accepted) throws Exception {
        boolean demo = request.securities().getFirst().market().equals("FIXTURE");
        if (demo && !fixtures) throw new IllegalArgumentException("FIXTURE_DATA_DISABLED");
        Map<Security, JsonNode> cached = new HashMap<>();
        for (var snapshot : accepted) {
            var security = new Security(snapshot.path("securityId").asText(), snapshot.path("market").asText());
            if (!request.securities().contains(security) || cached.putIfAbsent(security, snapshot) != null) {
                throw new IllegalArgumentException("INVALID_PRICE_SNAPSHOT");
            }
        }
        var series = new ArrayList<Map<String, Object>>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = request.securities().stream()
                    .map(security -> pool.submit(() -> series(request, security, demo, cached.get(security)))).toList();
            try {
                for (var job : jobs) series.add(job.get());
            } finally {
                jobs.forEach(job -> { if (!job.isDone()) job.cancel(true); });
            }
        }
        return json.write(Map.ofEntries(Map.entry("effectiveRequest", request), Map.entry("demoData", demo),
                Map.entry("calculationVersion", "risk-v1"), Map.entry("annualizationFactor", RiskCalculation.PERIODS_PER_YEAR),
                Map.entry("volatilityDdof", 1), Map.entry("metricUnit", "PERCENT"), Map.entry("returnDefinition", "DAILY_SIMPLE"),
                Map.entry("timezone", "Asia/Shanghai"), Map.entry("series", series), Map.entry("warnings", List.of(
                        demo ? "Synthetic demonstration data" : "Fetched historical prices are not an as-published historical data replay",
                        "Trading calendar coverage is unverified; metrics apply only to returned samples",
                        "Knowledge cutoff is a request constraint, not proof of historical price availability"))));
    }

    private Map<String, Object> series(ResearchRequest request, Security security, boolean demo, JsonNode snapshot) throws Exception {
        List<Bar> bars;
        Object refs;
        String status;
        String adjustment = request.priceAdjustment();
        var range = request.priceRange();
        if (snapshot != null) {
            if (!adjustment.equals(snapshot.path("adjustment").asText())) throw new IllegalArgumentException("INVALID_PRICE_SNAPSHOT");
            var rows = snapshot.path("bars");
            if (!rows.isArray() || rows.size() < RiskCalculation.MIN_OBSERVATIONS || rows.size() > RiskCalculation.MAX_OBSERVATIONS) throw new IllegalArgumentException("INVALID_PRICE_SNAPSHOT");
            var replay = new ArrayList<Bar>(rows.size());
            for (var row : rows) replay.add(EtfPriceSource.bar(row.path("date").asText(), row.path("close").asText()));
            if (replay.stream().anyMatch(bar -> outside(bar.date(), range))
                    || !RiskCalculation.version(security, adjustment, replay).equals(snapshot.path("dataVersion").asText())) {
                throw new IllegalArgumentException("INVALID_PRICE_SNAPSHOT");
            }
            var sources = snapshot.path("sourceRefs");
            if (!RiskSources.valid(sources, demo, true)) throw new IllegalArgumentException("INVALID_PRICE_SNAPSHOT");
            bars = replay;
            refs = sources;
            status = "SNAPSHOT_REUSED";
        } else if (demo) {
            var prices = FIXTURES.get(security.securityId());
            if (prices == null || !security.market().equals("FIXTURE")) throw new IllegalArgumentException("INVALID_FIXTURE");
            var generated = new ArrayList<Bar>(prices.size());
            for (int i = 0; i < prices.size(); i++) generated.add(new Bar(LocalDate.of(2025, 1, FIXTURE_DAYS.get(i)), new BigDecimal(prices.get(i))));
            bars = generated;
            refs = sourceRef("signalforge-synthetic-v1", "fixture://signalforge/risk-v1/" + security.securityId().substring(8), Instant.now(), true);
            status = "SYNTHETIC";
        } else {
            var prices = source.load(security, range.end());
            bars = prices.bars();
            refs = sourceRef(prices.provider(), prices.uri(), prices.fetchedAt(), false);
            status = "PROVIDER_FETCH";
        }
        bars = bars.stream().filter(bar -> !outside(bar.date(), range)).toList();
        var calculated = RiskCalculation.metrics(bars);
        var requested = new LinkedHashMap<String, Double>();
        for (var metric : request.metrics()) requested.put(metric.name(), calculated.get(metric));
        return Map.of("securityId", security.securityId(), "market", security.market(), "adjustment", adjustment,
                "bars", bars.stream().map(bar -> Map.of("date", bar.date().toString(), "close", RiskCalculation.close(bar))).toList(),
                "metrics", requested, "dataVersion", RiskCalculation.version(security, adjustment, bars), "fetchStatus", status,
                "coverage", Map.of("observations", bars.size(), "firstDate", bars.getFirst().date().toString(),
                    "lastDate", bars.getLast().date().toString(), "calendarVerified", false, "complete", false,
                    "missingHandling", "REJECT_MISSING_CLOSE_NO_IMPUTATION"), "sourceRefs", refs);
    }

    private static boolean outside(LocalDate date, ResearchRequest.PriceRange range) {
        return date.isBefore(range.start()) || date.isAfter(range.end());
    }

    private static List<Map<String, Object>> sourceRef(String provider, String uri, Instant fetchedAt, boolean demo) {
        return List.of(Map.of("provider", provider, "uri", uri, "fetchedAt", fetchedAt.toString(), "demoData", demo));
    }
}
