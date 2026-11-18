package com.signalforge.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Bounded reads for public data endpoints; callers build URLs from validated identifiers. */
@Component
public class PublicDataHttp {
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .version(HttpClient.Version.HTTP_1_1).build();
    private final JsonCodec json;

    public PublicDataHttp(JsonCodec json) { this.json = json; }

    public JsonNode json(URI uri) throws Exception {
        return json.tree(new String(get(uri, 1_048_576), StandardCharsets.UTF_8));
    }

    public byte[] get(URI uri, int limit) throws Exception {
        var request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .header("User-Agent", "Mozilla/5.0").GET().build();
        var pending = http.sendAsync(request, info -> new BoundedHttpBody(limit));
        try {
            var response = pending.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) throw new Unavailable(response.statusCode());
            return response.body();
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    public static final class Unavailable extends RuntimeException {
        public Unavailable(int status) { super("UPSTREAM_HTTP_" + status); }
    }
}
