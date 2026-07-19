package com.sismics.docs.core.util.pdf;

/**
 * Raised when a page-operation worker exceeds its wall-clock budget at a cooperative checkpoint. PDFBox
 * parse/save is synchronous and not guaranteed to stop on a thread interrupt, so the budget is enforced by
 * explicit checks between page operations (and around parse/save) rather than by killing the worker thread.
 */
public class PdfPageOperationTimeoutException extends PdfPageOperationException {
    public PdfPageOperationTimeoutException(String message) {
        super("PageOperationTimeout", message);
    }
}
