package com.sismics.docs.application.document;

import java.util.List;

/**
 * Input to {@link DocumentCoverHandler#clearCover(ClearDocumentCoverCommand)}: the instructions for
 * {@code DELETE /document/{id}/cover}. {@code writeTargetIds} is the caller's full ACL target set
 * (user + groups) used for the WRITE authorization — the legacy resource used
 * {@code getTargetIdList(null)}.
 *
 * @param documentId     Document ID
 * @param actorUserId    Acting user id (carried on the emitted event)
 * @param writeTargetIds Caller's ACL target set (user + groups)
 */
public record ClearDocumentCoverCommand(
        String documentId,
        String actorUserId,
        List<String> writeTargetIds) {
}
