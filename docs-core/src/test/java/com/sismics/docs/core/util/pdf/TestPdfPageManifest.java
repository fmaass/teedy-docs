package com.sismics.docs.core.util.pdf;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Structural validation of the v1 page-operation manifest parser: the happy path, the version gate, the
 * empty-result rejection, and the malformed-input rejections. Each rejection asserts the stable error type
 * the REST layer surfaces, so a wrong type is a caught regression.
 */
public class TestPdfPageManifest {

    @Test
    public void parsesReorderDeleteRotate() throws Exception {
        String json = "{\"version\":1,\"baseVersion\":3,\"pages\":["
                + "{\"source\":2,\"rotate\":90},{\"source\":0},{\"source\":1,\"rotate\":270}]}";
        PdfPageManifest manifest = PdfPageManifest.parse(json);

        Assertions.assertEquals(3, manifest.getBaseVersion());
        List<PdfPageManifest.PageOp> pages = manifest.getPages();
        Assertions.assertEquals(3, pages.size());
        // Order is preserved verbatim (this is the reorder).
        Assertions.assertEquals(2, pages.get(0).getSource());
        Assertions.assertEquals(Integer.valueOf(90), pages.get(0).getRotate());
        Assertions.assertEquals(0, pages.get(1).getSource());
        Assertions.assertNull(pages.get(1).getRotate(), "an omitted rotate keeps the page unchanged");
        Assertions.assertEquals(1, pages.get(2).getSource());
        Assertions.assertEquals(Integer.valueOf(270), pages.get(2).getRotate());
    }

    @Test
    public void normalizesNegativeAndOverflowRotation() throws Exception {
        String json = "{\"version\":1,\"baseVersion\":0,\"pages\":[{\"source\":0,\"rotate\":-90},{\"source\":1,\"rotate\":450}]}";
        PdfPageManifest manifest = PdfPageManifest.parse(json);
        Assertions.assertEquals(Integer.valueOf(270), manifest.getPages().get(0).getRotate());
        Assertions.assertEquals(Integer.valueOf(90), manifest.getPages().get(1).getRotate());
    }

    @Test
    public void rejectsUnsupportedVersion() {
        String json = "{\"version\":2,\"baseVersion\":0,\"pages\":[{\"source\":0}]}";
        PdfPageOperationException e = Assertions.assertThrows(PdfPageOperationException.class,
                () -> PdfPageManifest.parse(json));
        Assertions.assertEquals("UnsupportedManifestVersion", e.getType());
    }

    @Test
    public void rejectsEmptyPages() {
        String json = "{\"version\":1,\"baseVersion\":0,\"pages\":[]}";
        PdfPageOperationException e = Assertions.assertThrows(PdfPageOperationException.class,
                () -> PdfPageManifest.parse(json));
        Assertions.assertEquals("EmptyResult", e.getType());
    }

    @Test
    public void rejectsOutputExceedingPageCeiling() {
        // Three output pages under a ceiling of two is rejected before any generation.
        String json = "{\"version\":1,\"baseVersion\":0,\"pages\":[{\"source\":0},{\"source\":1},{\"source\":2}]}";
        PdfPageOperationException e = Assertions.assertThrows(PdfPageOperationException.class,
                () -> PdfPageManifest.parse(json, 2));
        Assertions.assertEquals("TooManyPages", e.getType());
    }

    @Test
    public void allowsOutputWithinPageCeiling() throws Exception {
        // A large source reduced to a single output page is allowed under the ceiling (source is unbounded
        // here — only the output count is ceiling-checked).
        String json = "{\"version\":1,\"baseVersion\":0,\"pages\":[{\"source\":499}]}";
        PdfPageManifest manifest = PdfPageManifest.parse(json, 1);
        Assertions.assertEquals(1, manifest.getPages().size());
        Assertions.assertEquals(499, manifest.getPages().get(0).getSource());
    }

    @Test
    public void rejectsDuplicateSource() {
        String json = "{\"version\":1,\"baseVersion\":0,\"pages\":[{\"source\":0},{\"source\":0}]}";
        PdfPageOperationException e = Assertions.assertThrows(PdfPageOperationException.class,
                () -> PdfPageManifest.parse(json));
        Assertions.assertEquals("InvalidManifest", e.getType());
    }

    @Test
    public void rejectsNonMultipleOf90Rotation() {
        String json = "{\"version\":1,\"baseVersion\":0,\"pages\":[{\"source\":0,\"rotate\":45}]}";
        PdfPageOperationException e = Assertions.assertThrows(PdfPageOperationException.class,
                () -> PdfPageManifest.parse(json));
        Assertions.assertEquals("InvalidManifest", e.getType());
    }

    @Test
    public void rejectsMissingBaseVersion() {
        String json = "{\"version\":1,\"pages\":[{\"source\":0}]}";
        PdfPageOperationException e = Assertions.assertThrows(PdfPageOperationException.class,
                () -> PdfPageManifest.parse(json));
        Assertions.assertEquals("InvalidManifest", e.getType());
    }

    @Test
    public void rejectsNegativeSource() {
        String json = "{\"version\":1,\"baseVersion\":0,\"pages\":[{\"source\":-1}]}";
        PdfPageOperationException e = Assertions.assertThrows(PdfPageOperationException.class,
                () -> PdfPageManifest.parse(json));
        Assertions.assertEquals("InvalidManifest", e.getType());
    }

    @Test
    public void rejectsNonJson() {
        PdfPageOperationException e = Assertions.assertThrows(PdfPageOperationException.class,
                () -> PdfPageManifest.parse("not json"));
        Assertions.assertEquals("InvalidManifest", e.getType());
    }

    @Test
    public void rejectsBlank() {
        PdfPageOperationException e = Assertions.assertThrows(PdfPageOperationException.class,
                () -> PdfPageManifest.parse("   "));
        Assertions.assertEquals("InvalidManifest", e.getType());
    }
}
