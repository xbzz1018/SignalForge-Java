package com.signalforge.knowledge;

import com.signalforge.infrastructure.JsonCodec;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeSet;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Version;
import org.springframework.stereotype.Component;

@Component
public class PdfDocumentParser {
    private static final int MAX_PAGES = 20;
    private static final int MAX_CHARS = 100_000;
    private static final int MAX_CHUNKS = 120;

    public Map<String, Object> parse(byte[] pdf) throws IOException {
        if (pdf.length < 5 || !new String(pdf, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            throw new IllegalStateException("DOCUMENT_NOT_PDF");
        }
        var pages = new ArrayList<Map<String, Object>>();
        var chunks = new ArrayList<Map<String, Object>>();
        var warnings = new TreeSet<String>();
        try (var input = new RandomAccessReadBuffer(pdf);
             var document = Loader.loadPDF(input, IOUtils.createTempFileOnlyStreamCache())) {
            if (document.isEncrypted()) throw new IllegalStateException("DOCUMENT_ENCRYPTED");
            int total = document.getNumberOfPages();
            if (total < 1 || total > 2000) throw new IllegalStateException("DOCUMENT_PAGE_LIMIT");
            int chars = 0;
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= Math.min(total, MAX_PAGES); page++) {
                int allowed = Math.min(10_000, MAX_CHARS - chars);
                var writer = new LimitedWriter(allowed);
                stripper.setStartPage(page); stripper.setEndPage(page);
                stripper.writeText(document, writer);
                String text = Normalizer.normalize(writer.text.toString().replace("\0", "").replace("\r\n", "\n"), Normalizer.Form.NFC)
                        .replaceAll("[ \\t]+", " ").replaceAll("(?<=[\\u3400-\\u9fff])\\n(?=[\\u3400-\\u9fff])", "");
                if (text.length() > allowed) { text = text.substring(0, boundary(text, allowed)); writer.truncated = true; }
                if (writer.truncated) warnings.add("PAGE_TEXT_TRUNCATED");
                chars += text.length();
                pages.add(Map.of("pageNumber", page, "text", text, "sha256", JsonCodec.sha256(text)));
                int start = 0, segment = 1;
                while (start < text.length() && chunks.size() < MAX_CHUNKS) {
                    int end = boundary(text, Math.min(start + 900, text.length()));
                    int line = text.lastIndexOf('\n', end - 1);
                    if (end < text.length() && line >= start + 450) end = line + 1;
                    String value = text.substring(start, end);
                    if (!value.isBlank()) {
                        chunks.add(Map.of("id", "p" + page + "-c" + segment, "pageNumber", page, "segment", segment,
                                "startOffset", start, "endOffset", end, "text", value, "sha256", JsonCodec.sha256(value)));
                        segment++;
                    }
                    start = end;
                }
                if (start < text.length()) warnings.add("CHUNK_LIMIT_REACHED");
                if (chars >= MAX_CHARS || chunks.size() == MAX_CHUNKS) break;
            }
            if (chunks.isEmpty()) warnings.add("NO_TEXT_REQUIRES_OCR");
            if (pages.size() < total) warnings.add("PARTIAL_DOCUMENT");
            return Map.of("parserVersion", "pdfbox:" + Version.getVersion() + "/signalforge-chunks-v1",
                    "offsetUnit", "UTF16_CODE_UNITS", "pages", pages, "chunks", chunks, "warnings", warnings,
                    "coverage", Map.of("totalPages", total, "parsedPages", pages.size(), "complete", warnings.isEmpty()));
        } catch (InvalidPasswordException encrypted) {
            throw new IllegalStateException("DOCUMENT_ENCRYPTED", encrypted);
        }
    }

    private static int boundary(String text, int end) {
        return end > 0 && end < text.length() && Character.isLowSurrogate(text.charAt(end)) ? end - 1 : end;
    }

    private static final class LimitedWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final int limit;
        private boolean truncated;
        private LimitedWriter(int limit) { this.limit = limit; }
        @Override public void write(char[] buffer, int start, int count) {
            int remaining = Math.min(count, limit - text.length());
            if (remaining > 0) text.append(buffer, start, remaining);
            if (remaining < count) truncated = true;
        }
        @Override public void flush() { }
        @Override public void close() { }
    }
}
