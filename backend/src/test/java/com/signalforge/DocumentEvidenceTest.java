package com.signalforge;

import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.knowledge.DocumentEvidence;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import tools.jackson.databind.JsonNode;

class DocumentEvidenceTest {
    final JsonCodec json=new JsonCodec();
    final DocumentEvidence evidence=new DocumentEvidence(json);
    final String text="公司将于2026年11月10日召开业绩说明会，介绍经营情况。";
    JsonNode parsed() { return json.tree(json.write(Map.of("chunks",List.of(Map.of("id","p1-c1","text",text))))); }
    Map<String,String> claim(String modality,String quote,String chunk) {
        return Map.of("predicate","EVENT","object","业绩说明会","modality",modality,"quote",quote,"chunkId",chunk);
    }
    List<DocumentEvidence.Checked> check(Object... claims) {
        return evidence.candidates(parsed(),Set.of("p1-c1"),json.tree(json.write(Map.of("candidates",Arrays.asList(claims)))));
    }
    @Test void literalPlannedStatementIsLocatedAndCannotBecomeAsserted() {
        var result=check(claim("PLANNED",text,"p1-c1")).getFirst();
        assertTrue(result.errors().isEmpty());assertEquals(text, text.substring(result.quoteStart(),result.quoteEnd()));
        assertTrue(check(claim("ASSERTED",text,"p1-c1")).getFirst().errors().contains("MODALITY_CONFLICT"));
    }
    @Test void fabricatedCitationAndUnsentChunkAreRejected() {
        assertTrue(check(claim("PLANNED",text.replace("召开","举行"),"p1-c1")).getFirst().errors().contains("QUOTE_NOT_IN_CHUNK"));
        assertTrue(check(claim("PLANNED",text,"p2-c1")).getFirst().errors().contains("CHUNK_NOT_IN_MODEL_INPUT"));
    }
    @Test void invalidShapeIsRetainedForAuditAndDuplicatesCannotPass() {
        assertTrue(check("invented").getFirst().errors().contains("INVALID_SCHEMA"));
        assertTrue(check(claim("PLANNED",text,"p1-c1"),claim("PLANNED",text,"p1-c1")).get(1).errors().contains("DUPLICATE_CANDIDATE"));
        assertThrows(IllegalArgumentException.class,()->evidence.candidates(parsed(),Set.of("p1-c1"),json.tree("[]")));
    }
    @Test void negativeStatementCannotBeFlipped() {
        var parsed=json.tree(json.write(Map.of("chunks",List.of(Map.of("id","p1-c1","text","本公司不涉及该项业务，不存在业绩说明会相关安排。")))));
        var claim=claim("ASSERTED","本公司不涉及该项业务，不存在业绩说明会相关安排。","p1-c1");
        assertTrue(evidence.candidates(parsed,Set.of("p1-c1"),json.tree(json.write(Map.of("candidates",List.of(claim))))).getFirst().errors().contains("MODALITY_CONFLICT"));
    }

    @Test void documentBinaryAndUtf16PositionsAreIndependentlyChecked() {
        String page="🙂公告正文与计划说明。";byte[] pdf="%PDF-test".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var chunk=Map.of("id","p1-c1","pageNumber",1,"text",page.substring(2),"startOffset",2,"endOffset",page.length(),"sha256",JsonCodec.sha256(page.substring(2)));
        Map<String,Object> root=new HashMap<>();root.put("schemaVersion","1.0");root.put("securityId","SH:600519");
        root.put("providerId","AN202611101234567890");root.put("sourceUrl","https://pdf.dfcfw.com/pdf/H2_AN202611101234567890_1.pdf");
        root.put("fetchedAt",java.time.Instant.now().toString());root.put("pdfBase64",Base64.getEncoder().encodeToString(pdf));root.put("pdfHash",DocumentEvidence.sha(pdf));
        root.put("offsetUnit","UTF16_CODE_UNITS");root.put("parserVersion","pypdf:test");root.put("warnings",List.of());
        root.put("pages",List.of(Map.of("pageNumber",1,"text",page,"sha256",JsonCodec.sha256(page))));root.put("chunks",List.of(chunk));
        root.put("coverage",Map.of("totalPages",1,"parsedPages",1,"complete",true));
        assertEquals("READY",evidence.verify("SH:600519","AN202611101234567890",json.write(root)).status());
        var altered=new HashMap<>(root);altered.put("pdfHash","0".repeat(64));
        assertThrows(IllegalArgumentException.class,()->evidence.verify("SH:600519","AN202611101234567890",json.write(altered)));
        var wrongChunk=new HashMap<>(chunk);wrongChunk.put("startOffset",1);root.put("chunks",List.of(wrongChunk));
        assertThrows(IllegalArgumentException.class,()->evidence.verify("SH:600519","AN202611101234567890",json.write(root)));
    }
}
