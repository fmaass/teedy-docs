package com.sismics.util.format;

import com.sismics.docs.core.util.format.PdfFormatHandler;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Test of {@link PdfFormatHandler}
 *
 * @author bgamard
 */
public class TestPdfFormatHandler {
    @Test
    public void testExtractContent(@TempDir Path tempDir) throws Exception {
        Path pdfFile = tempDir.resolve("test.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("The quick brown fox jumps over the lazy dog.");
                cs.newLineAtOffset(0, -20);
                cs.showText("Document management with full-text search.");
                cs.endText();
            }
            doc.save(pdfFile.toFile());
        }

        PdfFormatHandler handler = new PdfFormatHandler();
        String content = handler.extractContent("eng", pdfFile);
        Assertions.assertNotNull(content);
        Assertions.assertTrue(content.contains("quick brown fox"));
        Assertions.assertTrue(content.contains("full-text search"));
    }
}
