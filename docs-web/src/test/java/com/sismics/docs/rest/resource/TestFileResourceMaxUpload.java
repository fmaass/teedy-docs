package com.sismics.docs.rest.resource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit test of {@link FileResource#resolveMaxUploadSize()}.
 *
 * <p>RED-first for the robustness bug: before routing through the shared config helper,
 * a malformed {@code docs.max_upload_size} threw {@link NumberFormatException}, which 500s
 * every upload. The fix falls back to the 500MB default instead. Each test cleans up its
 * property in a finally block.</p>
 */
public class TestFileResourceMaxUpload {

    private static final String PROP = "docs.max_upload_size";
    private static final long DEFAULT = 500L * 1024 * 1024;

    @Test
    public void defaultWhenUnset() {
        Assertions.assertEquals(DEFAULT, FileResource.resolveMaxUploadSize());
    }

    @Test
    public void readsSystemProperty() {
        try {
            System.setProperty(PROP, "1048576");
            Assertions.assertEquals(1048576L, FileResource.resolveMaxUploadSize());
        } finally {
            System.clearProperty(PROP);
        }
    }

    @Test
    public void malformedFallsBackToDefaultInsteadOfThrowing() {
        try {
            System.setProperty(PROP, "not-a-number");
            Assertions.assertEquals(DEFAULT, FileResource.resolveMaxUploadSize(),
                    "a malformed DOCS_MAX_UPLOAD_SIZE must fall back to the default, not throw and 500 every upload");
        } finally {
            System.clearProperty(PROP);
        }
    }
}
