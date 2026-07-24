package com.sismics.docs.application.document;

import java.util.List;

/**
 * Instructions to duplicate a readable document into a fresh copy owned by the requester.
 *
 * @param sourceId      Document to duplicate
 * @param actorUserId   User that will own the copy and whose key re-encrypts the copied files
 * @param readTargetIds Requester's ACL target set: gates the READ check and bounds which tags are copied
 */
public record DuplicateDocumentCommand(String sourceId, String actorUserId, List<String> readTargetIds) {
}
