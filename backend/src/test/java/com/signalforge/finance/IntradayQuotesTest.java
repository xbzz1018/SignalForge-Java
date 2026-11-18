package com.signalforge.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.infrastructure.PublicDataHttp;
import com.signalforge.platform.ApiException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

class IntradayQuotesTest {
    private static final URI SSE_URI = URI.create("https://web.ifzq.gtimg.cn/appstock/app/minute/query?code=sh510300");
    private static final String KEY = "signalforge:java:intraday:v1:SSE:510300";
    private static final String RESPONSE = """
            {"code":0,"data":{"sh510300":{"qt":{"sh510300":["1","沪深300ETF","510300"]},
              "data":{"date":"20261118","data":["0930 %s 1000 4000.00","0931 4.060 2000 8000.00"]}}}}
            """;
    private final JsonCodec json = new JsonCodec();
    private final PublicDataHttp http = mock(PublicDataHttp.class);
    private final IntradayQuoteSource source = new IntradayQuoteSource(http);

    @Test void parsesTradingDateAndPriceAndRejectsMismatches() throws Exception {
        var security = new IntradayQuoteSource.Security("sse", "510300");
        when(http.json(SSE_URI)).thenReturn(json.tree(RESPONSE.formatted("4.059")));
        var quote = source.load(security);
        assertEquals(LocalDate.of(2026, 11, 18), quote.tradeDate());
        assertEquals(LocalTime.of(9, 30), quote.points().getFirst().time());
        assertEquals(new BigDecimal("4.059"), quote.points().getFirst().price());
        assertThrows(ApiException.class, () -> new IntradayQuoteSource.Security("SZSE", "510300"));
        when(http.json(SSE_URI)).thenReturn(json.tree(RESPONSE.formatted("4.059").replace("\"510300\"", "\"510301\"")));
        assertThrows(IllegalArgumentException.class, () -> source.load(security));
        when(http.json(SSE_URI)).thenReturn(json.tree(RESPONSE.formatted("4.059").replace("0931", "0930")));
        assertThrows(IllegalArgumentException.class, () -> source.load(security));
    }

    @Test void cachesExpiresRefreshesAndFallsBackWhenRedisStops() throws Exception {
        var upstreamCalls = new AtomicInteger();
        when(http.json(SSE_URI)).thenAnswer(invocation -> {
            int call = upstreamCalls.incrementAndGet();
            if (call == 4) throw new PublicDataHttp.Unavailable(503);
            return json.tree(RESPONSE.formatted("4.00" + call));
        });
        try (var container = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379)) {
            container.start();
            var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(600)).build();
            var connection = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(6379)), client);
            connection.afterPropertiesSet();
            try {
                var redis = new StringRedisTemplate(connection);
                var quotes = new IntradayQuotes(source, redis, json, Duration.ofSeconds(3));
                var initial = quotes.query("sse", "510300");
                assertEquals(initial, quotes.query("SSE", "510300"));
                assertEquals(1, upstreamCalls.get());
                assertTrue(redis.getExpire(KEY, TimeUnit.MILLISECONDS) > 0);
                assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
                    while (Boolean.TRUE.equals(redis.hasKey(KEY))) Thread.sleep(30);
                });
                var reloaded = quotes.query("SSE", "510300");
                assertNotEquals(initial.points(), reloaded.points());
                var refreshed = quotes.refresh("SSE", "510300");
                assertNotEquals(reloaded.points(), refreshed.points());
                assertEquals(refreshed, quotes.query("SSE", "510300"));
                assertEquals(3, upstreamCalls.get());
                assertEquals(502, assertThrows(ApiException.class, () -> quotes.refresh("SSE", "510300")).status());
                assertEquals(refreshed, quotes.query("SSE", "510300"));
                assertEquals(4, upstreamCalls.get());
                container.stop();
                var fallback = quotes.query("SSE", "510300");
                assertEquals(new BigDecimal("4.005"), fallback.points().getFirst().price());
                assertEquals(5, upstreamCalls.get());
            } finally {
                connection.destroy();
            }
        }
    }
}
