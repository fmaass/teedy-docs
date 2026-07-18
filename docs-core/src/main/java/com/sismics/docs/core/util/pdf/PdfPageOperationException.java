package com.sismics.docs.core.util.pdf;

/**
 * A client-attributable failure while applying a PDF page-operation manifest (a malformed manifest,
 * a non-PDF or over-ceiling source, a signed or encrypted source, ...). It carries a stable {@code type}
 * token so the REST layer can surface a typed {@code {type, message}} client error without leaking a stack
 * trace. Distinct from the version-contract exceptions ({@code VersionConcurrencyException} → 409,
 * {@code PreviousVersionMismatchException} → 400) which the caller maps separately.
 */
public class PdfPageOperationException extends Exception {
    private final String type;

    public PdfPageOperationException(String type, String message) {
        super(message);
        this.type = type;
    }

    /**
     * @return The stable error-type token surfaced to the client.
     */
    public String getType() {
        return type;
    }
}
