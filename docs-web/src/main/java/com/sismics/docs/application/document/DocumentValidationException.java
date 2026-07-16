package com.sismics.docs.application.document;

/**
 * A client-supplied value failed validation during the update (an invalid tag reference, a bad
 * metadata value). The {@code type} preserves the legacy error type so the edge can reproduce the
 * exact 400 body — {@code "ValidationError"} or {@code "TagNotFound"}.
 */
public class DocumentValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String type;

    /**
     * @param type    Legacy error type (e.g. {@code ValidationError}, {@code TagNotFound})
     * @param message Human-readable message
     */
    public DocumentValidationException(String type, String message) {
        super(message);
        this.type = type;
    }

    /**
     * @return The legacy error type
     */
    public String getType() {
        return type;
    }
}
