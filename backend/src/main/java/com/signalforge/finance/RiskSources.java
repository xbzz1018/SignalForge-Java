package com.signalforge.finance;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import tools.jackson.databind.JsonNode;

final class RiskSources {
    private static final Duration REUSE_PERIOD = Duration.ofHours(24);
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private RiskSources() {}

    static boolean valid(JsonNode refs, boolean demo, boolean forReuse) {
        if (!refs.isArray() || refs.isEmpty()) return false;
        Instant now = Instant.now();
        for (var ref : refs) {
            var uri = ref.path("uri");
            var provider = ref.path("provider");
            var demoData = ref.path("demoData");
            if (!uri.isString() || uri.asText().isBlank() || uri.asText().startsWith("fixture://") != demo
                    || !provider.isString() || provider.asText().isBlank()
                    || !demoData.isBoolean() || demoData.asBoolean() != demo) return false;
            try {
                Instant fetched = OffsetDateTime.parse(ref.path("fetchedAt").asText()).toInstant();
                if (fetched.isAfter(now.plus(CLOCK_SKEW))) return false;
                if (forReuse && !fetched.isAfter(now.minus(REUSE_PERIOD))) return false;
            } catch (DateTimeParseException e) {
                return false;
            }
        }
        return true;
    }
}
