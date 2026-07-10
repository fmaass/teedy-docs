package com.sismics.docs.core.constant;

/**
 * Webhook events.
 *
 * @author bgamard 
 */
public enum WebhookEvent {
    DOCUMENT_CREATED,
    DOCUMENT_UPDATED,
    DOCUMENT_DELETED,
    DOCUMENT_TRASHED,
    DOCUMENT_RESTORED,
    FILE_CREATED,
    FILE_UPDATED,
    FILE_DELETED,
    ROUTE_STARTED,
    ROUTE_STEP_TRANSITIONED,
    ROUTE_COMPLETED
}
