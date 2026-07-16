package com.sismics.docs.application.document;

/**
 * Result of a successful document update: the document id echoed back to the client
 * ({@code {"id": ...}}), identical for every successful permutation of the update.
 *
 * @param id Document ID
 */
public record UpdatedDocumentResult(String id) {
}
