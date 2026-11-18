package com.signalforge.knowledge;

import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.platform.ApiException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class KnowledgeEvidence {
    public record Node(String id,String kind,String label) {}
    public record Edge(String id,String source,String target,String type,String evidenceKey) {}
    public record Evidence(String id,String sourceUrl,String recordPath,String observedAt,String publishedAt,
                           String snapshotHash,String scope,JsonNode record) {}
    public record Graph(UUID releaseId,String securityId,String fetchedAt,String contentHash,String snapshotHash,
                        List<Node> nodes,List<Edge> edges,List<Evidence> evidence,List<String> warnings,
                        boolean pointInTimeSafe,String storage) {}
    public record Verified(String payload,String contentHash,String payloadHash,String fetchedAt) {}
    private final JsonCodec json;
    public KnowledgeEvidence(JsonCodec json) { this.json=json; }

    public static String security(String raw) {
        if (raw==null) throw invalid();
        String value=raw.strip().toUpperCase(Locale.ROOT);
        if (!value.matches("(?:(?:SH|SZ):)?[0-9]{6}")) throw invalid();
        String code=value.substring(value.length()-6);
        String market=code.matches("(?:600|601|603|605|688|689)[0-9]{3}")?"SH":
                code.matches("(?:000|001|002|003|300|301)[0-9]{3}")?"SZ":null;
        if (market==null || (value.contains(":") && !value.startsWith(market+":"))) throw invalid();
        return market+":"+code;
    }
    private static ApiException invalid() {
        return new ApiException(400,"UNSUPPORTED_SECURITY","第一版支持沪深 A 股股票代码，例如 600519、SZ:000001");
    }
    public Verified verify(String requested,String payload) {
        require(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=1_048_576);
        var root=json.tree(payload); var stock=root.path("security");
        require(root.path("schemaVersion").asText().equals("1.0") && root.path("provider").asText().equals("eastmoney"));
        require(root.path("demoData").isBoolean() && !root.path("demoData").asBoolean());
        require(stock.path("id").asText().equals(requested));
        require(stock.path("symbol").asText().equals(requested.substring(3)) && stock.path("market").asText().equals(requested.substring(0,2)));
        require(!stock.path("name").asText().isBlank() && stock.path("name").asText().length()<=200);
        require(stock.path("classificationScheme").asText().equals("eastmoney:f100"));
        String source="https://quote.eastmoney.com/"+requested.substring(0,2).toLowerCase(Locale.ROOT)+requested.substring(3)+".html";
        require(root.path("sources").path("security").asText().equals(source));
        if (!stock.path("industry").isNull()) {
            require(stock.path("industry").asText().length()<=200);
            require(root.path("observedQuote").path("industry").asText().equals(stock.path("industry").asText()));
        }
        String fetched=root.path("fetchedAt").asText();
        require(!OffsetDateTime.parse(fetched).toInstant().isAfter(Instant.now().plusSeconds(300)));
        require(root.path("pointInTimeSafe").isBoolean() && !root.path("pointInTimeSafe").asBoolean());
        var notices=root.path("announcements"); require(notices.isArray() && notices.size()<=8);
        Set<String> ids=new HashSet<>();
        for (var notice:notices) {
            String id=notice.path("providerId").asText(); require(id.matches("AN[0-9]{10,40}") && ids.add(id));
            require(notice.path("id").asText().equals("eastmoney:"+id));
            require(!notice.path("title").asText().isBlank() && notice.path("title").asText().length()<=1000);
            require(notice.path("url").asText().equals("https://data.eastmoney.com/notices/detail/"+requested.substring(3)+"/"+id+".html"));
            require(notice.path("pdfUrl").asText().equals("https://pdf.dfcfw.com/pdf/H2_"+id+"_1.pdf"));
            require(notice.path("bodyStatus").asText().equals("NOT_COLLECTED"));
            boolean matches=false;
            for (var code:notice.path("rawRecord").path("codes"))
                if (code.path("stock_code").asText().equals(requested.substring(3))) matches=true;
            require(matches);
            if (!notice.path("publishedAt").isNull()) OffsetDateTime.parse(notice.path("publishedAt").asText());
        }
        // The Java authority computes its own deterministic identity; timestamps don't create duplicates.
        var content=new TreeMap<String,Object>();
        for (String key:root.propertyNames()) if (!Set.of("fetchedAt","contentHash").contains(key)) content.put(key,canonical(root.get(key)));
        return new Verified(payload,JsonCodec.sha256(json.write(content)),JsonCodec.sha256(payload),fetched);
    }
    private Object canonical(JsonNode value) {
        if (value.isObject()) {
            var fields=new TreeMap<String,Object>();
            for (String key:value.propertyNames()) fields.put(key,canonical(value.get(key)));
            return fields;
        }
        if (value.isArray()) { var values=new ArrayList<>(); for (var item:value) values.add(canonical(item)); return values; }
        return value;
    }
    public Graph graph(UUID release,Verified snapshot) {
        var root=json.tree(snapshot.payload()); var stock=root.path("security");
        String sid=stock.path("id").asText(), fetched=snapshot.fetchedAt();
        var nodes=new ArrayList<Node>(); var edges=new ArrayList<Edge>(); var evidence=new ArrayList<Evidence>();
        nodes.add(new Node(sid,"Security",stock.path("name").asText()+" · "+sid));
        String industry=stock.path("industry").asText("");
        if (!industry.isBlank()) {
            String iid="industry:"+JsonCodec.sha256("eastmoney:f100:"+industry).substring(0,24);
            nodes.add(new Node(iid,"Industry",industry));
            edges.add(new Edge("classification",sid,iid,"CLASSIFIED_AS","classification"));
            evidence.add(new Evidence("classification",root.path("sources").path("security").asText(),"/observedQuote/industry",fetched,null,
                    snapshot.payloadHash(),"PROVIDER_CLASSIFICATION",root.path("observedQuote")));
        }
        int index=0;
        for (var notice:root.path("announcements")) {
            String id=notice.path("id").asText(); String key="announcement-"+index;
            nodes.add(new Node(id,"Announcement",notice.path("title").asText()));
            edges.add(new Edge(key,sid,id,"HAS_ANNOUNCEMENT",key));
            evidence.add(new Evidence(key,notice.path("url").asText(),"/announcements/"+index+"/rawRecord",fetched,
                    notice.path("publishedAt").isNull()?null:notice.path("publishedAt").asText(),snapshot.payloadHash(),
                    "ANNOUNCEMENT_CATALOG_ONLY",notice));
            index++;
        }
        var warnings=new ArrayList<String>(); for (var warning:root.path("warnings")) warnings.add(warning.asText());
        return new Graph(release,sid,fetched,snapshot.contentHash(),snapshot.payloadHash(),nodes,edges,evidence,warnings,false,"NEO4J");
    }
    private void require(boolean value) { if (!value) throw new IllegalArgumentException("KNOWLEDGE_EVIDENCE_REJECTED"); }
}
