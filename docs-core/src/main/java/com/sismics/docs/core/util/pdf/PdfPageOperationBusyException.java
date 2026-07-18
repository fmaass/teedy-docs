package com.sismics.docs.core.util.pdf;

/**
 * Raised when a page-operation request is rejected because a concurrency ceiling is saturated — another
 * operation is already running for the same file, or the global slot count is exhausted. The request is
 * refused (a typed client error) rather than queued, so a caller never blocks waiting for a slot.
 */
public class PdfPageOperationBusyException extends PdfPageOperationException {
    public PdfPageOperationBusyException(String message) {
        super("TooManyRequests", message);
    }
}
