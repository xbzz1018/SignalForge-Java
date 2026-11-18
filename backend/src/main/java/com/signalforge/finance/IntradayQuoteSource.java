package com.signalforge.finance;

import com.signalforge.infrastructure.PublicDataHttp;
import com.signalforge.platform.ApiException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class IntradayQuoteSource {
    private static final String ENDPOINT = "https://web.ifzq.gtimg.cn/appstock/app/minute/query?code=";
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("HHmm", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern PRICE = Pattern.compile("[0-9]{1,12}(?:\\.[0-9]{1,12})?");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public record Point(LocalTime time, BigDecimal price) {}

    public record Quote(String market, String code, String name, LocalDate tradeDate, String provider,
                        Instant fetchedAt, List<Point> points) {
        public Quote {
            points = List.copyOf(points);
        }
    }

    record Security(String market, String code) {
        Security {
            market = market.toUpperCase(Locale.ROOT);
            boolean supported = switch (market) {
                case "SSE" -> code.matches("(?:60[0135]|688|5[0-9]{2})[0-9]{3}");
                case "SZSE" -> code.matches("(?:00[0-3]|30[01]|1[58][0-9])[0-9]{3}");
                default -> false;
            };
            if (!supported) throw new ApiException(400, "UNSUPPORTED_QUOTE_SECURITY", "仅支持沪深股票与 ETF 的分时查询");
        }

        String ticker() {
            return (market.equals("SSE") ? "sh" : "sz") + code;
        }
    }

    private final PublicDataHttp http;

    public IntradayQuoteSource(PublicDataHttp http) {
        this.http = http;
    }

    Quote load(Security security) throws Exception {
        String ticker = security.ticker();
        var root = http.json(URI.create(ENDPOINT + ticker));
        var item = root.path("data").path(ticker);
        var attributes = item.path("qt").path(ticker);
        String name = attributes.path(1).asText();
        if (root.path("code").asInt(-1) != 0 || !attributes.isArray() || name.isBlank()
                || !security.code().equals(attributes.path(2).asText())) {
            throw new IllegalArgumentException("INTRADAY_IDENTITY_MISMATCH");
        }
        var data = item.path("data");
        LocalDate tradeDate = LocalDate.parse(data.path("date").asText(), DateTimeFormatter.BASIC_ISO_DATE);
        var rows = data.path("data");
        if (!rows.isArray() || rows.isEmpty() || rows.size() > 400) {
            throw new IllegalArgumentException("INVALID_INTRADAY_ROWS");
        }
        var points = new ArrayList<Point>(rows.size());
        LocalTime last = null;
        for (var row : rows) {
            String[] fields = WHITESPACE.split(row.asText().strip(), 3);
            if (fields.length < 2 || !PRICE.matcher(fields[1]).matches()) {
                throw new IllegalArgumentException("INVALID_INTRADAY_PRICE");
            }
            LocalTime time = LocalTime.parse(fields[0], MINUTE);
            BigDecimal price = new BigDecimal(fields[1]);
            if ((last != null && !time.isAfter(last)) || price.signum() <= 0) {
                throw new IllegalArgumentException("INVALID_INTRADAY_POINT");
            }
            points.add(new Point(time, price));
            last = time;
        }
        return new Quote(security.market(), security.code(), name, tradeDate, "tencent", Instant.now(), points);
    }
}
