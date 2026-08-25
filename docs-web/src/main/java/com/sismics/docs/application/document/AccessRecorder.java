package com.sismics.docs.application.document;

/**
 * Port for recording that a document was read (#300).
 *
 * <p>A port rather than a direct DAO call because the slice's REST edge may depend only on the
 * application layer: the recording therefore belongs to the handler that performs the read, and the
 * persistence adapter behind this interface is the only thing that knows a table exists.</p>
 */
public interface AccessRecorder {

    /**
     * Records one document access by one user, in the caller's current transaction.
     *
     * @param userId Acting user ID
     * @param documentId Accessed document ID
     */
    void recordDocumentAccess(String userId, String documentId);
}
