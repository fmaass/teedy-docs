package com.sismics.docs.core.util;

/**
 * Raised when a file move loses a race under the document/row locks: a document involved is no longer
 * active, the guarded relink affected a different row count than the enumerated set, or a concurrent
 * replacement left rows of the same version chain in the source document. It maps to a retryable conflict
 * so the whole move transaction rolls back closed rather than committing a split version chain.
 */
public class FileMoveConflictException extends RuntimeException {
    public FileMoveConflictException(String message) {
        super(message);
    }
}
