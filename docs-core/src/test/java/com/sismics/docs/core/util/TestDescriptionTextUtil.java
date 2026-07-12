package com.sismics.docs.core.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests the shared HTML-to-plain-text projection used by the Lucene index and the PDF
 * export.
 */
public class TestDescriptionTextUtil {

    @Test
    public void stripsMarkupKeepsWords() {
        String text = DescriptionTextUtil.toPlainText("<p>Hello <strong>brave</strong> world</p>");
        Assertions.assertEquals("Hello brave world", text);
    }

    @Test
    public void aWordInsideMarkupIsRecoverable() {
        // The Lucene contract: a word wrapped in a tag stays findable as text.
        String text = DescriptionTextUtil.toPlainText("<ul><li>invoice</li><li>receipt</li></ul>");
        Assertions.assertTrue(text.contains("invoice"), text);
        Assertions.assertTrue(text.contains("receipt"), text);
    }

    @Test
    public void tagNamesFromStrippedPayloadAreNotIndexedAsText() {
        // A neutralized payload's element name must NOT surface as searchable text.
        String sanitized = DescriptionSanitizer.sanitize("<p>real content</p><script>alert(1)</script>");
        String text = DescriptionTextUtil.toPlainText(sanitized);
        Assertions.assertTrue(text.contains("real content"), text);
        Assertions.assertFalse(text.toLowerCase().contains("script"), text);
        Assertions.assertFalse(text.contains("alert"), text);
    }

    @Test
    public void nullAndBlankBecomeEmpty() {
        Assertions.assertEquals("", DescriptionTextUtil.toPlainText(null));
        Assertions.assertEquals("", DescriptionTextUtil.toPlainText("   "));
    }

    @Test
    public void capAppliesEllipsisWhenTooLong() {
        String html = "<p>" + "a".repeat(200) + "</p>";
        String capped = DescriptionTextUtil.toPlainText(html, 50);
        Assertions.assertEquals(50, capped.length());
        Assertions.assertTrue(capped.endsWith("…"), capped);
    }

    @Test
    public void capIsNoOpWhenWithinBound() {
        String capped = DescriptionTextUtil.toPlainText("<p>short</p>", 4000);
        Assertions.assertEquals("short", capped);
        Assertions.assertFalse(capped.endsWith("…"), capped);
    }

    @Test
    public void capStripsMarkupBeforeCounting() {
        // 4000 visible chars of markup-wrapped text must not be inflated by tag characters.
        String html = "<p>" + "x".repeat(4000) + "</p>";
        String capped = DescriptionTextUtil.toPlainText(html, 4000);
        Assertions.assertEquals(4000, capped.length());
        Assertions.assertFalse(capped.contains("<p>"), "markup must be stripped before the cap");
    }
}
