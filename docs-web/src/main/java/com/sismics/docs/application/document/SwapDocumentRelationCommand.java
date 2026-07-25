package com.sismics.docs.application.document;

import java.util.List;

/**
 * Input to {@link SwapDocumentRelationHandler#swap(SwapDocumentRelationCommand)}: the instructions for
 * {@code POST /document/relation/swap}. The pair is named in its CURRENT orientation —
 * {@code documentId} is the document the relation points FROM today and {@code targetDocumentId} the one
 * it points AT — and the operation makes it read the other way round. {@code writeTargetIds} is the
 * caller's full ACL target set (user + groups); WRITE is required on BOTH documents, because reversing a
 * relation rewrites the outgoing list of each of them in turn.
 *
 * @param documentId       Document the relation currently points FROM
 * @param targetDocumentId Document the relation currently points AT
 * @param actorUserId      Acting user id (carried on the emitted events)
 * @param writeTargetIds   Caller's ACL target set (user + groups)
 */
public record SwapDocumentRelationCommand(
        String documentId,
        String targetDocumentId,
        String actorUserId,
        List<String> writeTargetIds) {
}
