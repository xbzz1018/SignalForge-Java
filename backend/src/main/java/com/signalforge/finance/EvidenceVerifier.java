package com.signalforge.finance;

import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.research.ResearchRequest;
import java.util.ArrayList;
import java.util.HashSet;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class EvidenceVerifier {
    private final JsonCodec json;
    public EvidenceVerifier(JsonCodec json) { this.json=json; }
    public void verify(ResearchRequest request, String response) {
        JsonNode root=json.tree(response);
        require(json.read(root.path("effectiveRequest").toString(),ResearchRequest.class).equals(request));
        boolean demo=request.securities().getFirst().market().equals("FIXTURE");
        require(root.path("demoData").isBoolean() && root.path("demoData").asBoolean()==demo);
        require(root.path("calculationVersion").asText().equals("risk-v1"));
        require(root.path("annualizationFactor").asInt()==252 && root.path("volatilityDdof").asInt()==1);
        require(root.path("metricUnit").asText().equals("PERCENT"));
        require(root.path("returnDefinition").asText().equals("DAILY_SIMPLE"));
        require(root.path("timezone").asText().equals("Asia/Shanghai"));
        JsonNode series=root.path("series"); require(series.isArray() && series.size()==2);
        for (int index=0;index<2;index++) {
            var security=request.securities().get(index); var item=series.get(index);
            require(item.path("securityId").asText().equals(security.securityId()));
            require(item.path("market").asText().equals(security.market()));
            require(item.path("adjustment").asText().equals(request.priceAdjustment()));
            var bars=item.path("bars"); require(bars.isArray() && bars.size()>=3 && bars.size()<=800);
            var prices = new ArrayList<EtfPriceSource.Bar>(bars.size());
            for (JsonNode bar : bars) {
                var price = EtfPriceSource.bar(bar.path("date").asText(), bar.path("close").asText());
                require(!price.date().isBefore(request.priceRange().start()) && !price.date().isAfter(request.priceRange().end()));
                prices.add(price);
            }
            require(RiskCalculation.version(security, request.priceAdjustment(), prices).equals(item.path("dataVersion").asText()));
            var expectedMetrics = RiskCalculation.metrics(prices);
            var coverage=item.path("coverage");
            require(coverage.path("observations").asInt()==bars.size());
            require(coverage.path("firstDate").asText().equals(bars.get(0).path("date").asText()));
            require(coverage.path("lastDate").asText().equals(bars.get(bars.size()-1).path("date").asText()));
            require(!coverage.path("calendarVerified").asBoolean(true));
            require(coverage.path("complete").isBoolean() && !coverage.path("complete").asBoolean());
            var refs=item.path("sourceRefs"); require(refs.isArray() && !refs.isEmpty());
            for (var ref:refs) {
                require(!ref.path("uri").asText().isBlank());
                java.time.OffsetDateTime.parse(ref.path("fetchedAt").asText());
                require(ref.path("demoData").isBoolean() && ref.path("demoData").asBoolean()==demo);
            }
            var metrics=item.path("metrics");
            var names=new HashSet<String>(metrics.propertyNames());
            require(names.equals(request.metrics().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet())));
            for (var metric:request.metrics()) {
                var reported = metrics.path(metric.name());
                require(reported.isNumber());
                double expected = expectedMetrics.get(metric);
                double tolerance = Math.max(1e-10, Math.ulp(expected) * 16);
                require(Math.abs(reported.asDouble() - expected) <= tolerance);
            }
        }
    }
    private void require(boolean value) { if (!value) throw new IllegalArgumentException("EVIDENCE_REJECTED"); }
}
