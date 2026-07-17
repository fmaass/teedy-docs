package com.sismics.docs.core.util;

/**
 * Thrown by {@link FileUtil#createFile} when a new-version upload names a {@code previousFileId} that is not
 * a valid base for the target document: the referenced file does not exist (or is not active), or it belongs
 * to a different document. The web layer maps this to a 400 Bad Request. Keeping it a distinct type (rather
 * than dereferencing a possibly-null document id) avoids the NullPointerException an orphan predecessor would
 * otherwise raise, which the resource turned into a misleading 500.
 *
 * @author bgamard
 */
public class PreviousVersionMismatchException extends Exception {
    /**
     * Serial UID.
     */
    private static final long serialVersionUID = 1L;

    public PreviousVersionMismatchException(String message) {
        super(message);
    }
}
