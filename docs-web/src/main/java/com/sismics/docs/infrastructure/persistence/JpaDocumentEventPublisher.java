package com.sismics.docs.infrastructure.persistence;

import com.sismics.docs.application.document.DocumentEventPublisher;
import com.sismics.docs.core.event.DocumentUpdatedAsyncEvent;

/**
 * Queues document domain events on the active transaction via {@link JpaTransactionRunner}, so they
 * fire only after a durable commit. It does not touch {@link com.sismics.util.context.ThreadLocalContext}
 * itself — the runner owns that.
 */
public class JpaDocumentEventPublisher implements DocumentEventPublisher {

    private final JpaTransactionRunner transactionRunner;

    public JpaDocumentEventPublisher(JpaTransactionRunner transactionRunner) {
        this.transactionRunner = transactionRunner;
    }

    @Override
    public void documentUpdated(String userId, String documentId) {
        DocumentUpdatedAsyncEvent event = new DocumentUpdatedAsyncEvent();
        event.setUserId(userId);
        event.setDocumentId(documentId);
        transactionRunner.queueEvent(event);
    }
}
