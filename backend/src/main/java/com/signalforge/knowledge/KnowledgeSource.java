package com.signalforge.knowledge;

import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.infrastructure.PublicDataHttp;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class KnowledgeSource {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String PDF_PREFIX = "https://pdf.dfcfw.com/pdf/H2_";
    private static final int PAGE_SIZE = 8;
    private final JsonCodec json;
    private final PublicDataHttp http;
    private final PdfDocumentParser parser;

    public KnowledgeSource(JsonCodec json, PublicDataHttp http, PdfDocumentParser parser) {
        this.json = json;
        this.http = http;
        this.parser = parser;
    }

    public String collect(String security) throws Exception {
        String id = KnowledgeEvidence.security(security);
        String code = id.substring(3), market = id.substring(0, 2);
        String marketId = market.equals("SH") ? "1" : "0";
        String noticeUrl = "https://np-anotice-stock.eastmoney.com/api/security/ann?stock_list=" + code + "," + marketId
                + "&page_index=1&page_size=" + PAGE_SIZE + "&sr=-1&ann_type=A&client_source=web";
        var notices = http.json(URI.create(noticeUrl)).path("data").path("list");
        if (!notices.isArray()) throw new IllegalStateException("ANNOUNCEMENTS_UNAVAILABLE");
        var announcements = new ArrayList<Map<String, Object>>();
        var seen = new HashSet<String>();
        String issuer = "";
        for (var item : notices) {
            String providerId = item.path("art_code").asText();
            String title = item.path("title_ch").asText("").strip();
            if (title.isEmpty()) title = item.path("title").asText("").strip();
            if (!providerId.matches("AN[0-9]{10,40}") || title.isEmpty()) continue;
            JsonNode matched = null;
            for (var listed : item.path("codes")) if (code.equals(listed.path("stock_code").asText())) { matched = listed; break; }
            if (matched == null || !seen.add(providerId)) continue;
            if (issuer.isEmpty()) issuer = matched.path("short_name").asText("").strip();
            String display = publicTime(item.path("display_time").asText(""));
            String published = display == null ? publicTime(item.path("notice_date").asText("")) : display;
            var notice = new LinkedHashMap<String, Object>();
            notice.put("id", "eastmoney:" + providerId);
            notice.put("providerId", providerId);
            notice.put("title", trimTo(title, 1000));
            notice.put("publishedAt", published);
            notice.put("publicationTimeSource", display != null ? "display_time" : published != null ? "notice_date" : "UNKNOWN");
            notice.put("url", "https://data.eastmoney.com/notices/detail/" + code + "/" + providerId + ".html");
            notice.put("pdfUrl", pdfUrl(providerId));
            notice.put("bodyStatus", "NOT_COLLECTED");
            notice.put("rawRecord", item);
            announcements.add(notice);
            if (announcements.size() == PAGE_SIZE) break;
        }
        var warnings = new TreeSet<String>();
        JsonNode row = quote(marketId, code);
        String name = issuer;
        String industry = null;
        Map<String, Object> observation = null;
        if (row != null) {
            if (!row.path("f12").asText().equals(code) || !row.path("f13").asText().equals(marketId)) {
                throw new IllegalStateException("SECURITY_IDENTITY_MISMATCH");
            }
            name = row.path("f14").asText("").strip();
            if (name.toUpperCase(Locale.ROOT).contains("ETF")) throw new IllegalStateException("SECURITY_IDENTITY_MISMATCH");
            industry = row.path("f100").asText("").strip();
            if (industry.isEmpty() || industry.equals("-")) industry = null;
            observation = new LinkedHashMap<>();
            observation.put("symbol", code);
            observation.put("secid", marketId + "." + code);
            observation.put("name", name);
            observation.put("industry", industry);
            observation.put("provider", "eastmoney");
            long epoch = row.path("f124").asLong(0);
            observation.put("quoteTime", epoch > 0 ? Instant.ofEpochSecond(epoch).toString() : null);
        } else warnings.add("INDUSTRY_UNAVAILABLE");
        if (name.isEmpty()) throw new IllegalStateException("SECURITY_NOT_VERIFIED");
        if (industry == null) warnings.add("NO_INDUSTRY_CLASSIFICATION");
        if (announcements.isEmpty()) warnings.add("NO_ANNOUNCEMENTS_RETURNED");

        var stock = new LinkedHashMap<String, Object>();
        stock.put("id", id); stock.put("symbol", code); stock.put("market", market);
        stock.put("name", name); stock.put("industry", industry); stock.put("classificationScheme", "eastmoney:f100");
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("schemaVersion", "1.0"); snapshot.put("provider", "eastmoney"); snapshot.put("demoData", false);
        snapshot.put("security", stock); snapshot.put("announcements", announcements); snapshot.put("observedQuote", observation);
        snapshot.put("sources", Map.of("security", "https://quote.eastmoney.com/" + market.toLowerCase(Locale.ROOT) + code + ".html",
                "announcements", noticeUrl));
        snapshot.put("coverage", Map.of("page", 1, "pageSize", PAGE_SIZE, "returned", announcements.size(), "complete", false));
        snapshot.put("warnings", warnings); snapshot.put("pointInTimeSafe", false); snapshot.put("fetchedAt", Instant.now().toString());
        return json.write(snapshot);
    }

    private JsonNode quote(String market, String code) throws InterruptedException {
        for (var host : List.of("push2.eastmoney.com", "push2delay.eastmoney.com")) {
            URI uri = URI.create("https://" + host + "/api/qt/ulist.np/get?secids=" + market + "." + code
                    + "&fields=f12,f13,f14,f100,f124&fltt=2&invt=2");
            try {
                var rows = http.json(uri).path("data").path("diff");
                for (var row : rows) return row;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (Exception unavailable) { /* Industry enrichment is optional. */ }
        }
        return null;
    }

    public String document(String security, String providerId) throws Exception {
        String id = KnowledgeEvidence.security(security);
        String url = pdfUrl(providerId);
        byte[] bytes = http.get(URI.create(url), 6 * 1_048_576);
        var parsed = parser.parse(bytes);
        var response = new LinkedHashMap<>(parsed);
        response.put("schemaVersion", "1.0"); response.put("securityId", id); response.put("providerId", providerId);
        response.put("sourceUrl", url); response.put("fetchedAt", Instant.now().toString());
        response.put("pdfBase64", Base64.getEncoder().encodeToString(bytes)); response.put("pdfHash", DocumentEvidence.sha(bytes));
        return json.write(response);
    }

    private static String pdfUrl(String id) {
        if (!id.matches("AN[0-9]{10,40}")) throw new IllegalArgumentException("DOCUMENT_INVALID_PROVIDER_ID");
        return PDF_PREFIX + id + "_1.pdf";
    }

    private static String publicTime(String value) {
        String timestamp = value.strip().replace(' ', 'T');
        try { return OffsetDateTime.parse(timestamp).toString(); }
        catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(timestamp).atZone(MARKET_ZONE).toOffsetDateTime().toString(); }
        catch (DateTimeParseException ignored) { }
        try { return LocalDate.parse(timestamp).atStartOfDay(MARKET_ZONE).toOffsetDateTime().toString(); }
        catch (DateTimeParseException ignored) { return null; }
    }

    private static String trimTo(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, Character.isLowSurrogate(value.charAt(limit)) ? limit - 1 : limit);
    }
}
