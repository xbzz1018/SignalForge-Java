package com.signalforge;

import com.signalforge.finance.EtfPriceSource;
import com.signalforge.finance.EvidenceVerifier;
import com.signalforge.finance.RiskService;
import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.research.ResearchRequest;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RiskServiceTest {
    private final JsonCodec json = new JsonCodec();
    private final EtfPriceSource source = mock(EtfPriceSource.class);
    private final RiskService risk = new RiskService(json, source, true);

    @Test void preservesRiskContractAndRecalculatesFromVerifiedSnapshots() throws Exception {
        var request = json.read(resource("risk-comparison.fixture.json"), ResearchRequest.class);
        var expected = json.tree(resource("risk-response.fixture.json")).path("series");
        String result = risk.calculate(request, List.of());
        new EvidenceVerifier(json).verify(request, result);
        var original = json.tree(result).path("series");
        var replay = json.tree(risk.calculate(request, List.of(original.get(0), original.get(1)))).path("series");
        for (int i = 0; i < original.size(); i++) {
            var calculated = original.get(i);
            assertEquals(expected.get(i).path("dataVersion"), calculated.path("dataVersion"));
            for (var metric : request.metrics()) assertEquals(expected.get(i).path("metrics").path(metric.name()).asDouble(),
                    calculated.path("metrics").path(metric.name()).asDouble(), 1e-10);
            assertEquals("SNAPSHOT_REUSED", replay.get(i).path("fetchStatus").asText());
            assertEquals(calculated.path("sourceRefs"), replay.get(i).path("sourceRefs"));
            assertEquals(calculated.path("metrics"), replay.get(i).path("metrics"));
        }
        var expired = (ObjectNode) original.get(0).deepCopy();
        ((ObjectNode) expired.path("sourceRefs").get(0)).put("fetchedAt", OffsetDateTime.now().minusDays(2).toString());
        assertThrows(java.util.concurrent.ExecutionException.class, () -> risk.calculate(request, List.of(expired)));
        assertThrows(IllegalArgumentException.class, () -> new RiskService(json, source, false).calculate(request, List.of()));
        verifyNoInteractions(source);
    }

    private String resource(String file) throws Exception {
        try (var input = getClass().getResourceAsStream("/" + file)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
