package com.sismics.docs.application.document;

/**
 * Applies a partial document update. Ordering mirrors the legacy resource: the WRITE authorization is
 * checked FIRST (a non-admin caller with no WRITE permission is denied before the document is even
 * loaded, so a missing document is a 403 for them and only an admin — whose ACL check is bypassed —
 * observes the 404), then the repository applies the mutation, then the updated event is published so
 * it fires on durable commit.
 */
public class UpdateDocumentHandler {

    private final DocumentRepository documentRepository;
    private final DocumentAuthorizationService authorizationService;
    private final DocumentEventPublisher eventPublisher;

    public UpdateDocumentHandler(DocumentRepository documentRepository,
                                 DocumentAuthorizationService authorizationService,
                                 DocumentEventPublisher eventPublisher) {
        this.documentRepository = documentRepository;
        this.authorizationService = authorizationService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * @param command The validated update instructions
     * @return The echoed document id
     * @throws DocumentAccessDeniedException when the caller lacks WRITE permission
     * @throws DocumentNotFoundException     when the document does not exist (admin path only)
     * @throws DocumentValidationException   on an invalid tag/metadata value
     */
    public UpdatedDocumentResult handle(UpdateDocumentCommand command) {
        if (!authorizationService.canWrite(command.id(), command.writeTargetIds())) {
            throw new DocumentAccessDeniedException();
        }
        documentRepository.update(command);
        eventPublisher.documentUpdated(command.actorUserId(), command.id());
        return new UpdatedDocumentResult(command.id());
    }
}
