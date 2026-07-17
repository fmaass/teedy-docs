package com.sismics.docs.core.util;

/**
 * Thrown by {@link FileUtil#createFile} when a new-version upload loses the single-writer race: the base it
 * named ({@code previousFileId}) is no longer the current latest version, because a concurrent writer already
 * replaced or deleted it. It is detected by the affected-row compare-and-swap that demotes the predecessor
 * returning zero rows, BEFORE any successor row is inserted, so a stale base can never create a second latest
 * version. The web layer maps this to a 409 Conflict; the client should reload and retry.
 *
 * @author bgamard
 */
public class VersionConcurrencyException extends Exception {
    /**
     * Serial UID.
     */
    private static final long serialVersionUID = 1L;

    public VersionConcurrencyException(String message) {
        super(message);
    }
}
