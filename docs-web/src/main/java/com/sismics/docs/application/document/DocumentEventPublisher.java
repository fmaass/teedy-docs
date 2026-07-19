package com.sismics.docs.application.document;

/**
 * Domain-event port for the document slice. The adapter queues the event on the active transaction
 * so it fires only after a durable commit.
 */
public interface DocumentEventPublisher {

    /**
     * Signals that a document was updated.
     *
     * @param userId     Acting user id
     * @param documentId Document ID
     */
    void documentUpdated(String userId, String documentId);
}
