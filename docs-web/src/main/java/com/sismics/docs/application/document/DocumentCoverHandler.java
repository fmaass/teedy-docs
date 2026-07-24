package com.sismics.docs.application.document;

/**
 * Sets or clears a document's explicit cover file. Ordering mirrors the legacy resource: the WRITE
 * authorization is checked FIRST (so a caller without WRITE is denied before any lookup), then the
 * repository reconciles the served cover pointer synchronously in the caller's transaction, then the
 * updated event is published so it fires on durable commit — the same seam
 * {@link UpdateDocumentHandler} uses.
 */
public class DocumentCoverHandler {

    private final DocumentRepository documentRepository;
    private final DocumentAuthorizationService authorizationService;
    private final DocumentEventPublisher eventPublisher;

    public DocumentCoverHandler(DocumentRepository documentRepository,
                                DocumentAuthorizationService authorizationService,
                                DocumentEventPublisher eventPublisher) {
        this.documentRepository = documentRepository;
        this.authorizationService = authorizationService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Sets the explicit cover file.
     *
     * @param command The set-cover instructions
     * @throws DocumentAccessDeniedException when the caller lacks WRITE permission
     * @throws DocumentValidationException   when the file is not attached to the document (mapped to a 400)
     */
    public void setCover(SetDocumentCoverCommand command) {
        if (!authorizationService.canWrite(command.documentId(), command.writeTargetIds())) {
            throw new DocumentAccessDeniedException();
        }
        documentRepository.setCover(command);
        eventPublisher.documentUpdated(command.actorUserId(), command.documentId());
    }

    /**
     * Clears the explicit cover file, restoring the derived (first-file-by-order) cover.
     *
     * @param command The clear-cover instructions
     * @throws DocumentAccessDeniedException when the caller lacks WRITE permission
     */
    public void clearCover(ClearDocumentCoverCommand command) {
        if (!authorizationService.canWrite(command.documentId(), command.writeTargetIds())) {
            throw new DocumentAccessDeniedException();
        }
        documentRepository.clearCover(command);
        eventPublisher.documentUpdated(command.actorUserId(), command.documentId());
    }
}
