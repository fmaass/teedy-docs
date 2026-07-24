package com.sismics.docs.application.document;

import java.util.List;

/**
 * Input to {@link DocumentCoverHandler#setCover(SetDocumentCoverCommand)}: the instructions for
 * {@code POST /document/{id}/cover}. {@code writeTargetIds} is the caller's full ACL target set
 * (user + groups) used for the WRITE authorization — the legacy resource used
 * {@code getTargetIdList(null)}. {@code fileId} is the chosen cover file, already validated present
 * at the edge; its attachment to the document is verified in the persistence adapter.
 *
 * @param documentId     Document ID
 * @param actorUserId    Acting user id (carried on the emitted event)
 * @param writeTargetIds Caller's ACL target set (user + groups)
 * @param fileId         File ID to use as the cover
 */
public record SetDocumentCoverCommand(
        String documentId,
        String actorUserId,
        List<String> writeTargetIds,
        String fileId) {
}
