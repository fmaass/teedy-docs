package com.sismics.util.format;

import com.google.common.io.Closer;
import com.sismics.BaseTest;
import com.sismics.docs.core.util.format.DocxFormatHandler;
import com.sismics.docs.core.util.format.FormatHandler;
import com.sismics.docs.core.util.format.OdtFormatHandler;
import com.sismics.docs.core.util.format.TextPlainFormatHandler;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Per-format, per-output validation of the office-document -> PDF conversion pipeline.
 *
 * <p>Every conversion output is re-parsed with PDFBox and asserted on BOTH the page count AND
 * the extractable text content. The whole-document "produced non-empty bytes" assertion
 * ({@link com.sismics.docs.core.util.TestFileUtil#convertToPdfTest} line 140) cannot tell a
 * correct render from a blank or garbled one, so these tests parse the result instead.</p>
 *
 * <p>These cover the iText (com.lowagie) code paths exercised during ODT/DOCX conversion via the
 * opensagres XDocReport converters and the text/CSV direct-write path, including the ODT
 * page-background-image path that reaches XDocReport's internal background-image insertion (the
 * highest-risk surface when the underlying iText 2.1.7 is replaced by OpenPDF).</p>
 */
public class TestFormatHandlerPdfConversion extends BaseTest {

    /**
     * A one-paragraph ODT that additionally declares a page-background image in its page layout,
     * embedded at {@code Pictures/bg.png} with a matching manifest entry. Rendering it forces
     * XDocReport's background-image insertion path (iText {@code Image} / content-byte drawing).
     */
    private static final String FILE_ODT_PAGE_BACKGROUND = "document_page_background.odt";

    /**
     * Render a source file to a self-contained PDF through the given handler's real
     * {@code appendToPdf} pipeline, then return the serialized PDF bytes.
     */
    private byte[] renderToPdf(FormatHandler handler, Path source) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            Closer closer = Closer.create();
            MemoryUsageSetting memUsageSettings = MemoryUsageSetting.setupMainMemoryOnly();
            handler.appendToPdf(source, doc, true, 10, memUsageSettings, closer);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            doc.save(outputStream);
            closer.close();
            return outputStream.toByteArray();
        }
    }

    /**
     * Parse the produced PDF and assert its page count and that its extracted text contains every
     * expected token.
     */
    private void assertPdf(byte[] pdfBytes, int expectedPages, String... expectedTokens) throws Exception {
        Assertions.assertTrue(pdfBytes.length > 0, "Expected the conversion to produce PDF bytes");
        try (PDDocument parsed = Loader.loadPDF(pdfBytes)) {
            Assertions.assertEquals(expectedPages, parsed.getNumberOfPages(),
                    "Unexpected page count in the converted PDF");
            String text = new PDFTextStripper().getText(parsed);
            for (String token : expectedTokens) {
                Assertions.assertTrue(text.contains(token),
                        "Expected converted PDF text to contain \"" + token + "\", was: " + text);
            }
        }
    }

    @Test
    public void csvToPdf() throws Exception {
        Path source = Paths.get(getResource(FILE_CSV).toURI());
        byte[] pdf = renderToPdf(new TextPlainFormatHandler(), source);
        assertPdf(pdf, 1, "col1", "col2", "test");
    }

    @Test
    public void docxToPdf() throws Exception {
        Path source = Paths.get(getResource(FILE_DOCX).toURI());
        byte[] pdf = renderToPdf(new DocxFormatHandler(), source);
        assertPdf(pdf, 1, "Lorem ipsum dolor sit amen.");
    }

    @Test
    public void odtToPdf() throws Exception {
        Path source = Paths.get(getResource(FILE_ODT).toURI());
        byte[] pdf = renderToPdf(new OdtFormatHandler(), source);
        assertPdf(pdf, 1, "Lorem ipsum dolor sit amen.");
    }

    @Test
    public void odtWithPageBackgroundImageToPdf() throws Exception {
        Path source = Paths.get(getResource(FILE_ODT_PAGE_BACKGROUND).toURI());
        byte[] pdf = renderToPdf(new OdtFormatHandler(), source);
        assertPdf(pdf, 1, "Lorem ipsum dolor sit amen.");
    }
}
