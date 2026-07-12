package com.sismics.docs.core.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

/**
 * Unit test for the rotation-aware raster helper's pure rotation step.
 *
 * <p>The load-bearing assertion is that a RECTANGULAR (non-square) image rotated 90/270 keeps its
 * FULL content with width/height SWAPPED — no crop. This is exactly what the imgscalr
 * {@code Scalr.Rotation} enum overload guarantees and what the project's custom double-angle
 * {@code com.sismics.util.Scalr.rotate} does NOT: that overload preserves the source canvas box and
 * rotates about the origin, cropping a rectangle at 90/270. Swapping {@code applyRotation} to that
 * cropping overload makes {@link #rotate90SwapsDimensionsNoCrop()} fail (the dimensions no longer
 * swap and content is lost), so the test is a real mutation guard, not a tautology.
 */
public class TestRasterGenerationUtil {

    /**
     * Build a rectangular image whose four corners carry distinct colors, so a crop (which would
     * drop a corner) is detectable after rotation.
     */
    private static BufferedImage rectangle(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                image.setRGB(x, y, Color.GRAY.getRGB());
            }
        }
        image.setRGB(0, 0, Color.RED.getRGB());          // top-left
        image.setRGB(w - 1, 0, Color.GREEN.getRGB());    // top-right
        image.setRGB(0, h - 1, Color.BLUE.getRGB());     // bottom-left
        image.setRGB(w - 1, h - 1, Color.WHITE.getRGB()); // bottom-right
        return image;
    }

    @Test
    public void rotate90SwapsDimensionsNoCrop() {
        BufferedImage src = rectangle(200, 100); // landscape

        BufferedImage rotated = RasterGenerationUtil.applyRotation(src, 90);

        // 90° swaps the axes: a 200x100 image becomes 100x200. The cropping overload would keep
        // 200x100 (and lose content), so this dimension swap is the falsifying assertion.
        Assertions.assertEquals(100, rotated.getWidth(), "90° must swap width/height (no crop)");
        Assertions.assertEquals(200, rotated.getHeight(), "90° must swap width/height (no crop)");

        // The original top-left RED corner maps to the top-right after a clockwise 90° turn: full
        // content is preserved, not cropped away.
        Assertions.assertEquals(Color.RED.getRGB(), rotated.getRGB(rotated.getWidth() - 1, 0),
                "CW 90°: original top-left corner must land at the new top-right (content preserved)");
    }

    @Test
    public void rotate270SwapsDimensionsNoCrop() {
        BufferedImage src = rectangle(200, 100);

        BufferedImage rotated = RasterGenerationUtil.applyRotation(src, 270);

        Assertions.assertEquals(100, rotated.getWidth(), "270° must swap width/height (no crop)");
        Assertions.assertEquals(200, rotated.getHeight(), "270° must swap width/height (no crop)");
    }

    @Test
    public void rotate180KeepsDimensions() {
        BufferedImage src = rectangle(200, 100);

        BufferedImage rotated = RasterGenerationUtil.applyRotation(src, 180);

        Assertions.assertEquals(200, rotated.getWidth(), "180° must keep the same dimensions");
        Assertions.assertEquals(100, rotated.getHeight(), "180° must keep the same dimensions");
        // 180° maps top-left to bottom-right.
        Assertions.assertEquals(Color.RED.getRGB(),
                rotated.getRGB(rotated.getWidth() - 1, rotated.getHeight() - 1),
                "180°: original top-left corner must land at the new bottom-right");
    }

    @Test
    public void rotate0IsIdentity() {
        BufferedImage src = rectangle(200, 100);

        BufferedImage rotated = RasterGenerationUtil.applyRotation(src, 0);

        Assertions.assertSame(src, rotated, "0° must return the image unchanged");
    }

    @Test
    public void nonMultipleOf90IsIdentity() {
        BufferedImage src = rectangle(200, 100);

        // A value that does NOT normalize to 90/180/270 is a no-op (defensive — the endpoint already
        // normalizes to {0,90,180,270}, but the helper must not crop on an unexpected value). 45 is a
        // genuine non-multiple of 90 (360 would normalize to 0, which is a different case).
        BufferedImage rotated = RasterGenerationUtil.applyRotation(src, 45);

        Assertions.assertSame(src, rotated, "a non-multiple of 90 must return the image unchanged");
        Assertions.assertEquals(200, rotated.getWidth());
        Assertions.assertEquals(100, rotated.getHeight());
    }
}
