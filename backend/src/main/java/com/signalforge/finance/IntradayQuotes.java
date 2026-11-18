package com.signalforge.finance;

import com.signalforge.finance.IntradayQuoteSource.Quote;
import com.signalforge.finance.IntradayQuoteSource.Security;
import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.platform.ApiException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;

@Service
public class IntradayQuotes {
    private static final Logger log = LoggerFactory.getLogger(IntradayQuotes.class);
    private static final String PREFIX = "signalforge:java:intraday:v1:";

    private final IntradayQuoteSource source;
    private final StringRedisTemplate redis;
    private final JsonCodec json;
    private final Duration ttl;
    private final ConcurrentHashMap<String, CompletableFuture<Quote>> loading = new ConcurrentHashMap<>();

    public IntradayQuotes(IntradayQuoteSource source, StringRedisTemplate redis, JsonCodec json,
                         @Value("${signalforge.intraday-cache-ttl}") Duration ttl) {
        if (ttl.compareTo(Duration.ofMillis(1)) < 0) throw new IllegalArgumentException("Intraday cache TTL must be positive");
        this.source = source;
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
    }

    public Quote query(String market, String code) {
        return quote(new Security(market, code), false);
    }

    public Quote refresh(String market, String code) {
        return quote(new Security(market, code), true);
    }

    private Quote quote(Security security, boolean refresh) {
        String key = PREFIX + security.market() + ':' + security.code();
        if (!refresh) {
            Quote cached = read(key);
            if (cached != null && security.market().equals(cached.market()) && security.code().equals(cached.code())
                    && cached.tradeDate() != null && cached.fetchedAt() != null && !cached.points().isEmpty()) {
                return cached;
            }
        }
        var result = new CompletableFuture<Quote>();
        var existing = loading.putIfAbsent(key, result);
        if (existing != null) {
            try {
                return existing.join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException cause) throw cause;
                if (e.getCause() instanceof Error cause) throw cause;
                throw e;
            }
        }
        try {
            Quote loaded = load(security);
            write(key, loaded);
            result.complete(loaded);
            return loaded;
        } catch (RuntimeException | Error e) {
            result.completeExceptionally(e);
            throw e;
        } finally {
            loading.remove(key, result);
        }
    }

    private Quote load(Security security) {
        try {
            return source.load(security);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Intraday source unavailable for {}: {}", security.ticker(), e.toString());
            throw new ApiException(502, "INTRADAY_SOURCE_UNAVAILABLE", "暂时无法获取有效分时行情");
        }
    }

    private Quote read(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? null : json.read(value, Quote.class);
        } catch (DataAccessException | JacksonException e) {
            log.debug("Intraday cache read skipped for {}: {}", key, e.toString());
            return null;
        }
    }

    private void write(String key, Quote quote) {
        try {
            redis.opsForValue().set(key, json.write(quote), ttl);
        } catch (DataAccessException | JacksonException e) {
            log.debug("Intraday cache write skipped for {}: {}", key, e.toString());
        }
    }
}
