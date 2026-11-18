package com.signalforge;

import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.infrastructure.PublicDataHttp;
import com.signalforge.knowledge.DocumentEvidence;
import com.signalforge.knowledge.KnowledgeEvidence;
import com.signalforge.knowledge.KnowledgeSource;
import com.signalforge.knowledge.PdfDocumentParser;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeSourceTest {
    private final JsonCodec json = new JsonCodec();
    private final PublicDataHttp http = mock(PublicDataHttp.class);
    private final KnowledgeSource source = new KnowledgeSource(json, http, new PdfDocumentParser());
    private static final String ANNOUNCEMENT = "AN2026111800001";

    @Test void keepsOnlyAnnouncedIssuerRecordsAndPreservesRawEvidence() throws Exception {
        when(http.json(argThat(uri -> uri != null && uri.getHost().equals("np-anotice-stock.eastmoney.com")))).thenReturn(json.tree("""
            {"data":{"list":[
              {"art_code":"AN2026111800001","title_ch":"Test notice","notice_date":"2026-11-18 00:00:00",
                "codes":[{"stock_code":"000001"},{"stock_code":"600519","short_name":"Issuer"}]},
              {"art_code":"AN2026111800002","title_ch":"Other issuer","codes":[{"stock_code":"000001"}]}
            ]}}
            """));
        when(http.json(argThat(uri -> uri != null && uri.getHost().equals("push2.eastmoney.com")))).thenReturn(json.tree("""
            {"data":{"diff":[{"f12":"600519","f13":1,"f14":"Issuer","f100":"Industry"}]}}
            """));
        String raw = source.collect("600519");
        var evidence = new KnowledgeEvidence(json);
        var result = evidence.verify("SH:600519", raw);
        var notices = json.tree(raw).path("announcements");
        assertEquals(1, notices.size());
        assertEquals(ANNOUNCEMENT, notices.get(0).path("providerId").asText());
        assertEquals(2, evidence.graph(java.util.UUID.randomUUID(), result).edges().size());
    }

    @Test void extractsNativePdfTextWithVerifiableOffsets() throws Exception {
        byte[] bytes;
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                content.newLineAtOffset(60, 700);
                content.showText("The issuer plans to hold a public results briefing.");
                content.endText();
            }
            document.save(output);
            bytes = output.toByteArray();
        }
        when(http.get(any(), eq(6 * 1_048_576))).thenReturn(bytes);
        var result = new DocumentEvidence(json).verify("SH:600519", ANNOUNCEMENT, source.document("600519", ANNOUNCEMENT));
        assertArrayEquals(bytes, result.pdf());
        assertTrue(result.parserVersion().startsWith("pdfbox:"));
        assertEquals("READY", result.status());
        var chunk = result.parsed().path("chunks").get(0);
        assertTrue(chunk.path("text").asText().contains("public results briefing"));
        assertEquals(1, chunk.path("pageNumber").asInt());
    }
}
