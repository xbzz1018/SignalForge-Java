package com.signalforge;

import com.signalforge.finance.EvidenceVerifier;
import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.research.RequestPolicy;
import com.signalforge.research.ResearchRequest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceVerifierTest {
    final JsonCodec json=new JsonCodec();
    final EvidenceVerifier verifier=new EvidenceVerifier(json);
    String resource(String name) throws Exception {
        try (var in=getClass().getResourceAsStream("/"+name)) {
            assertNotNull(in); return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    ResearchRequest request() throws Exception { return json.read(resource("risk-comparison.fixture.json"),ResearchRequest.class); }
    @Test void acceptsReferenceFixtureAndPreservesRequest() throws Exception {
        new RequestPolicy(true).validate(request());
        verifier.verify(request(),resource("risk-response.fixture.json"));
    }
    @Test void rejectsTamperedPricesAndDemoLabels() throws Exception {
        String response=resource("risk-response.fixture.json");
        assertThrows(IllegalArgumentException.class,()->verifier.verify(request(),response.replace("\"close\": \"110\"","\"close\": \"111\"")));
        assertThrows(IllegalArgumentException.class,()->verifier.verify(request(),response.replace("\"demoData\": true","\"demoData\": false")));
    }
    @Test void rejectsPlausibleMetricThatDisagreesWithInput() throws Exception {
        var response = json.tree(resource("risk-response.fixture.json"));
        ((tools.jackson.databind.node.ObjectNode) response.path("series").get(0).path("metrics")).put("TOTAL_RETURN", 8.5);
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(request(), response.toString()));
    }
    @Test void rejectsRequestDriftAndUnjustifiedCoverage() throws Exception {
        String response=resource("risk-response.fixture.json");
        assertThrows(IllegalArgumentException.class,()->verifier.verify(request(),response.replace("2025-01-09", "2025-01-10")));
        assertThrows(IllegalArgumentException.class,()->verifier.verify(request(),response.replace("\"complete\": false", "\"complete\": true")));
    }
    @Test void rejectsIncompleteOrMislabeledSourceAtPublication() throws Exception {
        var request = request();
        for (var entry : java.util.Map.of("provider", "", "uri", "https://example.test/real",
                "fetchedAt", "2099-01-01T00:00:00Z").entrySet()) {
            var response = json.tree(resource("risk-response.fixture.json"));
            var source = (tools.jackson.databind.node.ObjectNode) response.path("series").get(0).path("sourceRefs").get(0);
            source.put(entry.getKey(), entry.getValue());
            assertThrows(IllegalArgumentException.class, () -> verifier.verify(request, response.toString()), entry.getKey());
        }
    }
    @Test void demoRequiresExplicitConfiguration() throws Exception {
        assertThrows(com.signalforge.platform.ApiException.class,()->new RequestPolicy(false).validate(request()));
    }
}
