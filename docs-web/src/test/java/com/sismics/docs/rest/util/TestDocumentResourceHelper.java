package com.sismics.docs.rest.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure helpers extracted from DocumentResource. These pin the exact
 * sanitization behavior that guards the export ZIP against path traversal / ZIP-slip.
 */
public class TestDocumentResourceHelper {

    @Test
    public void testSanitizePathSegmentBlank() {
        Assertions.assertEquals("document", DocumentResourceHelper.sanitizePathSegment(null));
        Assertions.assertEquals("document", DocumentResourceHelper.sanitizePathSegment(""));
        Assertions.assertEquals("document", DocumentResourceHelper.sanitizePathSegment("   "));
    }

    @Test
    public void testSanitizePathSegmentReplacesNonWord() {
        Assertions.assertEquals("My_Doc_2024_", DocumentResourceHelper.sanitizePathSegment("My Doc/2024!"));
        Assertions.assertEquals("a_b", DocumentResourceHelper.sanitizePathSegment("a../b"));
        Assertions.assertEquals("plain", DocumentResourceHelper.sanitizePathSegment("plain"));
    }

    @Test
    public void testSanitizeFileNameBlank() {
        Assertions.assertEquals("file", DocumentResourceHelper.sanitizeFileName(null));
        Assertions.assertEquals("file", DocumentResourceHelper.sanitizeFileName(""));
        Assertions.assertEquals("file", DocumentResourceHelper.sanitizeFileName("  "));
    }

    @Test
    public void testSanitizeFileNameStripsPath() {
        Assertions.assertEquals("report.pdf", DocumentResourceHelper.sanitizeFileName("/etc/report.pdf"));
        Assertions.assertEquals("report.pdf", DocumentResourceHelper.sanitizeFileName("..\\..\\report.pdf"));
        Assertions.assertEquals("report.pdf", DocumentResourceHelper.sanitizeFileName("sub/dir/report.pdf"));
    }

    @Test
    public void testSanitizeFileNameTraversalOnly() {
        Assertions.assertEquals("file", DocumentResourceHelper.sanitizeFileName(".."));
        Assertions.assertEquals("file", DocumentResourceHelper.sanitizeFileName("."));
        Assertions.assertEquals("file", DocumentResourceHelper.sanitizeFileName("path/to/.."));
    }

    @Test
    public void testSanitizeFileNameKeepsSimpleName() {
        Assertions.assertEquals("hello world.txt", DocumentResourceHelper.sanitizeFileName("hello world.txt"));
    }
}
