package com.signalforge.knowledge;

import com.signalforge.infrastructure.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Validate stored page/chunk positions and literal citations independently of the model. */
@Component
public class DocumentEvidence {
    public record Verified(byte[] pdf,String pdfHash,String sourceUrl,String fetchedAt,String parserVersion,
                           JsonNode parsed,String status) {}
    public record Checked(String predicate,String object,String modality,String chunkId,String quote,
                          int quoteStart,int quoteEnd,List<String> errors,JsonNode raw) {}
    private static final Set<String> FIELDS=Set.of("predicate","object","modality","chunkId","quote");
    private static final Pattern NEGATION=Pattern.compile("未发生|不涉及|不存在|不持有|未持有|不从事|未开展|否认|并非|不再");
    private static final Pattern PLAN=Pattern.compile("拟|计划|将于|将针对|将在|将通过|将向|将就|预计|尚待|待批准|尚需|意向");
    private final JsonCodec json;
    public DocumentEvidence(JsonCodec json) { this.json=json; }
    public static String sha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    public Verified verify(String security,String provider,String payload) {
        var root=json.tree(payload);
        require(root.path("schemaVersion").asText().equals("1.0"));
        require(root.path("securityId").asText().equals(security) && root.path("providerId").asText().equals(provider));
        String url="https://pdf.dfcfw.com/pdf/H2_"+provider+"_1.pdf";
        require(root.path("sourceUrl").asText().equals(url));
        String encoded=root.path("pdfBase64").asText(); require(encoded.length()<=8_388_608);
        byte[] pdf=Base64.getDecoder().decode(encoded);
        require(pdf.length>=5 && pdf.length<=6*1_048_576 && new String(pdf,0,5,StandardCharsets.US_ASCII).equals("%PDF-"));
        String hash=sha(pdf); require(hash.equals(root.path("pdfHash").asText()));
        String fetched=root.path("fetchedAt").asText();
        require(!OffsetDateTime.parse(fetched).toInstant().isAfter(java.time.Instant.now().plusSeconds(300)));
        require(root.path("offsetUnit").asText().equals("UTF16_CODE_UNITS"));
        String parser=root.path("parserVersion").asText();
        require(parser.length()<=100 && (parser.startsWith("pdfbox:") || parser.startsWith("pypdf:")));
        var pages=root.path("pages"); var chunks=root.path("chunks");
        require(pages.isArray() && pages.size()>=1 && pages.size()<=20 && chunks.isArray() && chunks.size()<=120);
        Map<Integer,String> text=new HashMap<>();
        for (var page:pages) {
            int number=page.path("pageNumber").asInt(); String value=page.path("text").asText();
            require(number==text.size()+1 && value.length()<=20_000);
            require(JsonCodec.sha256(value).equals(page.path("sha256").asText())); text.put(number,value);
        }
        Set<String> ids=new HashSet<>();
        for (var chunk:chunks) {
            int page=chunk.path("pageNumber").asInt(), start=chunk.path("startOffset").asInt(-1), end=chunk.path("endOffset").asInt(-1);
            String value=chunk.path("text").asText(), id=chunk.path("id").asText();
            require(id.matches("p[0-9]{1,4}-c[0-9]{1,3}") && ids.add(id));
            require(text.containsKey(page) && start>=0 && end>start && end<=text.get(page).length());
            require(text.get(page).substring(start,end).equals(value) && value.length()<=1800);
            require(JsonCodec.sha256(value).equals(chunk.path("sha256").asText()));
        }
        var coverage=root.path("coverage");
        require(coverage.path("parsedPages").asInt()==pages.size());
        require(coverage.path("totalPages").asInt()>=pages.size() && coverage.path("totalPages").asInt()<=2000);
        require(coverage.path("complete").isBoolean());
        if (coverage.path("complete").asBoolean()) require(coverage.path("totalPages").asInt()==pages.size());
        var parsed=json.tree(json.write(Map.of("pages",pages,"chunks",chunks,"coverage",coverage,"warnings",root.path("warnings"),
                "offsetUnit","UTF16_CODE_UNITS")));
        return new Verified(pdf,hash,url,fetched,parser,parsed,chunks.isEmpty()?"NO_TEXT":coverage.path("complete").asBoolean()?"READY":"PARTIAL");
    }
    public List<Checked> candidates(JsonNode parsed,Set<String> allowedChunks,JsonNode response) {
        require(response.isObject() && response.size()==1 && response.path("candidates").isArray() && response.path("candidates").size()<=6);
        Map<String,JsonNode> chunks=new HashMap<>(); for (var chunk:parsed.path("chunks")) chunks.put(chunk.path("id").asText(),chunk);
        Set<String> seen=new HashSet<>(); List<Checked> result=new ArrayList<>();
        for (var raw:response.path("candidates")) {
            List<String> errors=new ArrayList<>(); Set<String> keys=new HashSet<>(); if (raw.isObject()) raw.propertyNames().forEach(keys::add);
            if (!raw.isObject() || !keys.equals(FIELDS) || FIELDS.stream().anyMatch(k->!raw.path(k).isString())) errors.add("INVALID_SCHEMA");
            String predicate=raw.path("predicate").asText(), object=raw.path("object").asText(), modality=raw.path("modality").asText();
            String id=raw.path("chunkId").asText(), quote=raw.path("quote").asText();
            if (!Set.of("BUSINESS","PRODUCT","EVENT").contains(predicate)) { errors.add("UNSUPPORTED_PREDICATE"); predicate="UNSUPPORTED"; }
            if (!Set.of("ASSERTED","PLANNED","NEGATED","UNCERTAIN").contains(modality)) { errors.add("UNSUPPORTED_MODALITY"); modality="UNSUPPORTED"; }
            if (object.length()<2 || object.length()>100 || object.chars().anyMatch(Character::isISOControl)) errors.add("INVALID_OBJECT");
            if (quote.length()<12 || quote.length()>400 || !quote.contains(object)) errors.add("OBJECT_OR_QUOTE_INVALID");
            var chunk=chunks.get(id); int start=-1,end=-1;
            if (chunk==null || !allowedChunks.contains(id)) errors.add("CHUNK_NOT_IN_MODEL_INPUT");
            else {
                start=chunk.path("text").asText().indexOf(quote);
                if (quote.isBlank() || start<0) errors.add("QUOTE_NOT_IN_CHUNK"); else end=start+quote.length();
            }
            if (modality.equals("ASSERTED") && (NEGATION.matcher(quote).find() || PLAN.matcher(quote).find())) errors.add("MODALITY_CONFLICT");
            if (modality.equals("PLANNED") && NEGATION.matcher(quote).find()) errors.add("NEGATION_REQUIRES_REVIEW");
            if (!seen.add(predicate+"|"+object+"|"+modality+"|"+id+"|"+quote)) errors.add("DUPLICATE_CANDIDATE");
            result.add(new Checked(predicate,object,modality,id.length()<=32?id:"invalid",quote,start,end,List.copyOf(errors),raw));
        }
        return result;
    }
    private static void require(boolean value) { if (!value) throw new IllegalArgumentException("DOCUMENT_EVIDENCE_REJECTED"); }
}
