package com.sismics.docs.core.util.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Real-PDFBox behavioural tests for the page-tree transform: reorder, delete, and per-page rotate produce
 * the expected page tree; a signed, encrypted, unparseable, or out-of-range source is rejected with the
 * right typed error; the source byte ceiling is enforced on the decrypt stream; and the wall-clock budget
 * aborts cooperatively — including after output validation. PDFs are generated in-test with per-page text
 * markers so the resulting order is observable via text extraction.
 */
public class TestPdfPageOperationUtil {

    private static final BooleanSupplier NOT_EXPIRED = () -> false;
    private static final BooleanSupplier ALWAYS_EXPIRED = () -> true;

    private final List<Path> temps = new ArrayList<>();

    @AfterEach
    public void cleanup() {
        for (Path p : temps) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // best-effort test cleanup
            }
        }
    }

    private Path temp(String suffix) throws IOException {
        Path p = Files.createTempFile("pageops-test", suffix);
        temps.add(p);
        return p;
    }

    private static MemoryUsageSetting mem() {
        return MemoryUsageSetting.setupMainMemoryOnly();
    }

    private static String marker(int i) {
        return "PAGE" + i + "MARK";
    }

    /**
     * Build a PDF whose page {@code i} contains the extractable text {@code PAGE<i>MARK}.
     */
    private Path writePdf(int pageCount) throws Exception {
        Path file = temp(".pdf");
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < pageCount; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 24);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(marker(i));
                    cs.endText();
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private Path writeEncryptedPdf() throws Exception {
        Path file = temp(".pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            // Empty user password: the document loads without a password yet reports isEncrypted() == true.
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy("owner-pw", "", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            doc.save(file.toFile());
        }
        return file;
    }

    private Path writeSignedPdf() throws Exception {
        Path file = temp(".pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setName("Test Signer");
            signature.setSignDate(Calendar.getInstance());
            PDSignatureField signatureField = new PDSignatureField(acroForm);
            signatureField.setValue(signature);
            acroForm.getFields().add(signatureField);
            doc.save(file.toFile());
        }
        return file;
    }

    private String pageText(PDDocument doc, int oneBasedPage) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(oneBasedPage);
        stripper.setEndPage(oneBasedPage);
        return stripper.getText(doc).trim();
    }

    private PdfPageManifest manifest(String pagesJson) throws Exception {
        return PdfPageManifest.parse("{\"version\":1,\"baseVersion\":0,\"pages\":" + pagesJson + "}");
    }

    @Test
    public void reordersPages() throws Exception {
        Path input = writePdf(3);
        Path output = temp(".pdf");
        PdfPageManifest m = manifest("[{\"source\":2},{\"source\":0},{\"source\":1}]");

        PdfPageOperationUtil.transform(input, output, m, mem(), NOT_EXPIRED);

        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            Assertions.assertEquals(3, doc.getNumberOfPages());
            Assertions.assertEquals(marker(2), pageText(doc, 1));
            Assertions.assertEquals(marker(0), pageText(doc, 2));
            Assertions.assertEquals(marker(1), pageText(doc, 3));
        }
    }

    @Test
    public void deletesPages() throws Exception {
        Path input = writePdf(3);
        Path output = temp(".pdf");
        // Keep pages 0 and 2, dropping the middle page.
        PdfPageManifest m = manifest("[{\"source\":0},{\"source\":2}]");

        PdfPageOperationUtil.transform(input, output, m, mem(), NOT_EXPIRED);

        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            Assertions.assertEquals(2, doc.getNumberOfPages());
            Assertions.assertEquals(marker(0), pageText(doc, 1));
            Assertions.assertEquals(marker(2), pageText(doc, 2));
        }
    }

    @Test
    public void rotatesPagesAbsolutely() throws Exception {
        Path input = writePdf(2);
        Path output = temp(".pdf");
        PdfPageManifest m = manifest("[{\"source\":0,\"rotate\":90},{\"source\":1}]");

        PdfPageOperationUtil.transform(input, output, m, mem(), NOT_EXPIRED);

        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            Assertions.assertEquals(2, doc.getNumberOfPages());
            Assertions.assertEquals(90, doc.getPage(0).getRotation(), "an explicit rotate is set absolutely");
            Assertions.assertEquals(0, doc.getPage(1).getRotation(), "an omitted rotate leaves the page upright");
        }
    }

    @Test
    public void largeSourceReducedWithinCeilingIsAllowed() throws Exception {
        // A many-page source reduced to a single output page must succeed — the ceiling bounds OUTPUT pages,
        // not the source. (The output-page ceiling itself is enforced at manifest parse, tested there.)
        Path input = writePdf(8);
        Path output = temp(".pdf");
        PdfPageManifest m = manifest("[{\"source\":7}]");

        PdfPageOperationUtil.transform(input, output, m, mem(), NOT_EXPIRED);

        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            Assertions.assertEquals(1, doc.getNumberOfPages());
            Assertions.assertEquals(marker(7), pageText(doc, 1));
        }
    }

    @Test
    public void rejectsSignedSource() throws Exception {
        Path input = writeSignedPdf();
        Path output = temp(".pdf");
        // Precondition: the fixture really carries a signature dictionary.
        try (PDDocument doc = Loader.loadPDF(input.toFile())) {
            Assertions.assertFalse(doc.getSignatureDictionaries().isEmpty(),
                    "fixture must contain a signature dictionary");
        }

        PdfPageManifest m = manifest("[{\"source\":0}]");
        PdfPageOperationException e = Assertions.assertThrows(PdfPageOperationException.class,
                () -> PdfPageOperationUtil.transform(input, output, m, mem(), NOT_EXPIRED));
        Assertions.assertEquals("SignedSource", e.getType());
    }

    @Test
    public void rejectsEncryptedSource() throws Exception {
        Path input = writeEncryptedPdf();
        Path output = temp(".pdf");
        // Precondition: the fixture is PDF-level encrypted (loads without a password, reports encrypted).
        try (PDDocument doc = Loader.loadPDF(input.toFile())) {
            Assertions.assertTrue(doc.isEncrypted(), "fixture must be encrypted");
        }

        PdfPageManifest m = manifest("[{\"source\":0}]");
        PdfPageOperationException e = Assertions.assertThrows(PdfPageOperationException.class,
                () -> PdfPageOperationUtil.transform(input, output, m, mem(), NOT_EXPIRED));
        Assertions.assertEquals("EncryptedSource", e.getType());
    }

    @Test
    public void rejectsTruncatedOrMalformedSource() throws Exception {
        // A source with a PDF header but no valid body/xref is unprocessable input, not a server fault.
        Path input = temp(".pdf");
        Files.write(input, "%PDF-1.7\nthis is not a valid pdf body and has no xref or trailer\n"
                .getBytes(StandardCharsets.UTF_8));
        Path output = temp(".pdf");

        PdfPageManifest m = manifest("[{\"source\":0}]");
        PdfPageOperationException e = Assertions.assertThrows(PdfPageOperationException.class,
                () -> PdfPageOperationUtil.transform(input, output, m, mem(), NOT_EXPIRED));
        Assertions.assertEquals("InvalidPdf", e.getType());
    }

    @Test
    public void rejectsOutOfRangeSourceIndex() throws Exception {
        Path input = writePdf(2);
        Path output = temp(".pdf");
        PdfPageManifest m = manifest("[{\"source\":0},{\"source\":5}]");

        PdfPageOperationException e = Assertions.assertThrows(PdfPageOperationException.class,
                () -> PdfPageOperationUtil.transform(input, output, m, mem(), NOT_EXPIRED));
        Assertions.assertEquals("InvalidManifest", e.getType());
    }

    @Test
    public void abortsWhenTheDeadlineHasPassed() throws Exception {
        Path input = writePdf(3);
        Path output = temp(".pdf");
        PdfPageManifest m = manifest("[{\"source\":0},{\"source\":1},{\"source\":2}]");

        Assertions.assertThrows(PdfPageOperationTimeoutException.class,
                () -> PdfPageOperationUtil.transform(input, output, m, mem(), ALWAYS_EXPIRED));
    }

    @Test
    public void abortsAtThePostValidationCheckpoint() throws Exception {
        Path input = writePdf(1);
        Path output = temp(".pdf");
        PdfPageManifest m = manifest("[{\"source\":0}]");

        // For a single-page transform the cooperative checkpoints are, in order:
        //   1 after load, 2 remove-page, 3 add-page, 4 before save, 5 before validate, 6 AFTER validate.
        // Trip only on the 6th call so generation AND validation both complete before the deadline fires —
        // proving the post-validation checkpoint gates the return (createFile is unreachable past-deadline).
        AtomicInteger calls = new AtomicInteger();
        BooleanSupplier tripAfterValidation = () -> calls.incrementAndGet() >= 6;

        Assertions.assertThrows(PdfPageOperationTimeoutException.class,
                () -> PdfPageOperationUtil.transform(input, output, m, mem(), tripAfterValidation));
        Assertions.assertEquals(6, calls.get(),
                "the timeout must fire at the post-validation checkpoint, after all prior checks passed");

        // The output was fully generated and validated before the deadline tripped.
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            Assertions.assertEquals(1, doc.getNumberOfPages());
        }
    }

    @Test
    public void copyBoundedRejectsOversizeStream() throws Exception {
        Path output = temp(".bin");
        byte[] data = new byte[101];
        PdfPageOperationException e = Assertions.assertThrows(PdfPageOperationException.class,
                () -> PdfPageOperationUtil.copyBounded(new ByteArrayInputStream(data), output, 100));
        Assertions.assertEquals("SourceTooLarge", e.getType());
    }

    @Test
    public void copyBoundedCopiesWithinLimit() throws Exception {
        Path output = temp(".bin");
        byte[] data = new byte[100];
        PdfPageOperationUtil.copyBounded(new ByteArrayInputStream(data), output, 100);
        Assertions.assertEquals(100, Files.size(output));
    }
}
