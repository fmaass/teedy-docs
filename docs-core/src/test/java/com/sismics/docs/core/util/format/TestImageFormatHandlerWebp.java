package com.sismics.docs.core.util.format;

import com.google.common.io.Closer;
import com.sismics.BaseTest;
import com.sismics.util.mime.MimeType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * WebP support in the image pipeline (#233): the handler must accept {@code image/webp}, decode it
 * for the thumbnail/web rasters, and render it into a PDF export.
 *
 * <p>Decoding is provided by the pure-Java TwelveMonkeys {@code imageio-webp} reader; these tests
 * exercise the real {@link javax.imageio.ImageIO} path, so they also prove the reader is actually on
 * the runtime classpath rather than merely declared.</p>
 *
 * <p>Both container flavours are covered: a lossy VP8 fixture and a lossless VP8L one. The fixture
 * is four solid colour quadrants, which lets the decode be asserted on pixel VALUES — a reader that
 * returned a blank or mis-ordered raster would still satisfy a dimensions-only check.</p>
 */
public class TestImageFormatHandlerWebp extends BaseTest {

    /** Colour of the top-left / bottom-right quadrant of both fixtures. */
    private static final int TOP_LEFT_RGB = 0xFF0000;

    private static final int BOTTOM_RIGHT_RGB = 0xFFFF00;

    /**
     * Assert a pixel matches an expected RGB triple within a per-channel tolerance (0 for the
     * lossless fixture, a small budget for the lossy one).
     */
    private void assertPixel(BufferedImage image, int x, int y, int expectedRgb, int tolerance, String what) {
        int actual = image.getRGB(x, y) & 0xFFFFFF;
        for (int shift : new int[]{16, 8, 0}) {
            int expectedChannel = (expectedRgb >> shift) & 0xFF;
            int actualChannel = (actual >> shift) & 0xFF;
            Assertions.assertTrue(Math.abs(expectedChannel - actualChannel) <= tolerance,
                    what + ": pixel (" + x + "," + y + ") expected ~#" + Integer.toHexString(expectedRgb)
                            + " but was #" + Integer.toHexString(actual));
        }
    }

    private void assertDecodedFixture(BufferedImage image, int tolerance, String what) {
        Assertions.assertNotNull(image, what + ": no ImageIO reader decoded the WebP fixture");
        Assertions.assertEquals(FILE_WEBP_WIDTH, image.getWidth(), what + ": unexpected width");
        Assertions.assertEquals(FILE_WEBP_HEIGHT, image.getHeight(), what + ": unexpected height");
        assertPixel(image, 4, 4, TOP_LEFT_RGB, tolerance, what);
        assertPixel(image, FILE_WEBP_WIDTH - 5, FILE_WEBP_HEIGHT - 5, BOTTOM_RIGHT_RGB, tolerance, what);
    }

    @Test
    public void acceptsWebpMimeType() {
        Assertions.assertTrue(new ImageFormatHandler().accept(MimeType.IMAGE_WEBP),
                "the image handler must accept image/webp");
    }

    /**
     * The classpath-scanned lookup is what production actually calls
     * ({@link com.sismics.docs.core.util.RasterGenerationUtil}), so route through it rather than
     * trusting {@code accept} alone.
     */
    @Test
    public void formatHandlerLookupResolvesWebp() {
        FormatHandler handler = FormatHandlerUtil.find(MimeType.IMAGE_WEBP);
        Assertions.assertInstanceOf(ImageFormatHandler.class, handler,
                "FormatHandlerUtil must resolve image/webp to the image handler");
    }

    @Test
    public void generatesThumbnailFromLossyWebp() throws Exception {
        Path source = Paths.get(getResource(FILE_WEBP).toURI());
        assertDecodedFixture(new ImageFormatHandler().generateThumbnail(source), 12, "lossy VP8");
    }

    @Test
    public void generatesThumbnailFromLosslessWebp() throws Exception {
        Path source = Paths.get(getResource(FILE_WEBP_LOSSLESS).toURI());
        assertDecodedFixture(new ImageFormatHandler().generateThumbnail(source), 0, "lossless VP8L");
    }

    /**
     * Without a WebP branch in the {@code appendToPdf} dispatch the switch falls through to
     * {@code default: return}, which adds NO page at all — a PDF export would silently drop every
     * WebP page. Assert the page exists AND carries a real image XObject decoded from the fixture.
     */
    @Test
    public void appendsLossyWebpToPdf() throws Exception {
        assertRendersToPdf(FILE_WEBP, 12, "lossy VP8");
    }

    @Test
    public void appendsLosslessWebpToPdf() throws Exception {
        assertRendersToPdf(FILE_WEBP_LOSSLESS, 0, "lossless VP8L");
    }

    private void assertRendersToPdf(String fixture, int tolerance, String what) throws Exception {
        Path source = Paths.get(getResource(fixture).toURI());

        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            Closer closer = Closer.create();
            ImageFormatHandler handler = new ImageFormatHandler();
            Assertions.assertTrue(handler.accept(MimeType.IMAGE_WEBP));
            handler.appendToPdf(source, doc, true, 10, MemoryUsageSetting.setupMainMemoryOnly(), closer);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            doc.save(outputStream);
            closer.close();
            pdfBytes = outputStream.toByteArray();
        }

        try (PDDocument parsed = Loader.loadPDF(pdfBytes)) {
            Assertions.assertEquals(1, parsed.getNumberOfPages(),
                    what + ": the WebP image produced no PDF page");

            PDResources resources = parsed.getPage(0).getResources();
            PDImageXObject embedded = null;
            for (COSName name : resources.getXObjectNames()) {
                PDXObject xObject = resources.getXObject(name);
                if (xObject instanceof PDImageXObject imageXObject) {
                    embedded = imageXObject;
                }
            }
            Assertions.assertNotNull(embedded, what + ": the PDF page carries no image");
            Assertions.assertEquals(FILE_WEBP_WIDTH, embedded.getWidth(), what + ": embedded width");
            Assertions.assertEquals(FILE_WEBP_HEIGHT, embedded.getHeight(), what + ": embedded height");
            assertDecodedFixture(embedded.getImage(), tolerance, what + " (embedded)");
        }
    }
}
