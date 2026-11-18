package com.signalforge.finance;

import com.signalforge.infrastructure.PublicDataHttp;
import com.signalforge.research.ResearchRequest.Security;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class EtfPriceSource {
    public record Bar(LocalDate date, BigDecimal close) {}
    public record Prices(List<Bar> bars, String provider, String uri, Instant fetchedAt) {}

    private final PublicDataHttp http;
    public EtfPriceSource(PublicDataHttp http) { this.http = http; }

    public Prices load(Security security, LocalDate end) throws Exception {
        String code = security.securityId();
        boolean sh = security.market().equals("SSE");
        if (!(sh && code.matches("5[0-9]{5}") || security.market().equals("SZSE") && code.matches("1[0-9]{5}"))) {
            throw new IllegalArgumentException("UNSUPPORTED_ETF");
        }
        String secid = (sh ? "1." : "0.") + code;
        URI eastmoney = URI.create("https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=" + secid
                + "&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53&klt=101&fqt=0&lmt=800&end="
                + end.format(DateTimeFormatter.BASIC_ISO_DATE));
        JsonNode root;
        try {
            root = http.json(eastmoney);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (Exception unavailable) {
            String ticker = (sh ? "sh" : "sz") + code;
            URI tencent = URI.create("https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=" + ticker
                    + ",day,," + end + ",800,");
            return new Prices(tencent(http.json(tencent), ticker, code), "tencent", tencent.toString(), Instant.now());
        }
        return new Prices(eastmoney(root, code, sh), "eastmoney", eastmoney.toString(), Instant.now());
    }

    private static List<Bar> eastmoney(JsonNode root, String code, boolean sh) {
        var data = root.path("data");
        if (root.path("rc").asInt(-1) != 0 || !data.path("code").asText().equals(code)
                || data.path("market").asInt(-1) != (sh ? 1 : 0)) throw new IllegalArgumentException("ETF_IDENTITY_MISMATCH");
        requireEtf(code, data.path("name").asText());
        var rows = data.path("klines");
        requireRows(rows);
        var bars = new ArrayList<Bar>(rows.size());
        for (var row : rows) {
            var values = row.asText().split(",", -1);
            if (values.length < 3) throw new IllegalArgumentException("INVALID_PRICE_ROW");
            bars.add(bar(values[0], values[2]));
        }
        return List.copyOf(bars);
    }

    private static List<Bar> tencent(JsonNode root, String ticker, String code) {
        var data = root.path("data").path(ticker);
        if (root.path("code").asInt(-1) != 0 || !data.isObject()) throw new IllegalArgumentException("ETF_IDENTITY_MISMATCH");
        requireEtf(code, data.path("qt").path(ticker).path(1).asText());
        var rows = data.path("day");
        requireRows(rows);
        var bars = new ArrayList<Bar>(rows.size());
        for (var row : rows) {
            if (!row.isArray() || row.size() < 3) throw new IllegalArgumentException("INVALID_PRICE_ROW");
            bars.add(bar(row.path(0).asText(), row.path(2).asText()));
        }
        return List.copyOf(bars);
    }

    static Bar bar(String date, String close) {
        if (!close.matches("[0-9]{1,12}(?:\\.[0-9]{1,24})?")) throw new IllegalArgumentException("INVALID_CLOSE");
        return new Bar(LocalDate.parse(date), new BigDecimal(close));
    }

    private static void requireRows(JsonNode rows) {
        if (!rows.isArray() || rows.isEmpty() || rows.size() > 800) throw new IllegalArgumentException("INVALID_PRICE_ROWS");
    }

    private static void requireEtf(String code, String name) {
        String label = name.toUpperCase(Locale.ROOT);
        if (label.contains("联接") || !(label.contains("ETF") || code.matches("(?:51[0-9]|52[0-9]|53[0-2]|56[0-3]|58[89]|15[0-9]|18[0-9])[0-9]{3}"))) {
            throw new IllegalArgumentException("UNSUPPORTED_ETF");
        }
    }
}
