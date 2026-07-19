package com.sismics.docs.application.document;

/**
 * Resolving a file's on-disk size failed while building the files section (the legacy
 * {@code UNKNOWN_SIZE} fallback reading the stored blob). The edge maps it to the same 500
 * {@code FileError} the legacy path produced.
 */
public class DocumentFileAccessException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * @param message Human-readable message
     * @param cause   Underlying I/O failure
     */
    public DocumentFileAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
