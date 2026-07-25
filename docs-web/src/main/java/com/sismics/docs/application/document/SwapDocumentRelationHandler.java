package com.sismics.docs.application.document;

/**
 * Reverses the direction of the relation between two documents. Ordering mirrors the rest of the slice:
 * the WRITE authorization is checked FIRST — on BOTH documents, since the reversal moves the link off one
 * document's outgoing list and onto the other's — then the repository applies the canonical collapse, then
 * an updated event is published for EACH document so both search-index entries and contributor lists
 * refresh on durable commit.
 *
 * <p>The handler keeps "denied" and "absent" distinct ({@link DocumentAccessDeniedException} vs
 * {@link DocumentNotFoundException}); the edge is where they are deliberately merged into one
 * non-disclosive status.</p>
 */
public class SwapDocumentRelationHandler {

    private final DocumentRepository documentRepository;
    private final DocumentAuthorizationService authorizationService;
    private final DocumentEventPublisher eventPublisher;

    public SwapDocumentRelationHandler(DocumentRepository documentRepository,
                                       DocumentAuthorizationService authorizationService,
                                       DocumentEventPublisher eventPublisher) {
        this.documentRepository = documentRepository;
        this.authorizationService = authorizationService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * @param command The swap instructions
     * @throws DocumentAccessDeniedException when the caller lacks WRITE permission on either document
     * @throws DocumentNotFoundException     when either document is absent/trashed, or the two are unrelated
     */
    public void swap(SwapDocumentRelationCommand command) {
        if (!authorizationService.canWrite(command.documentId(), command.writeTargetIds())
                || !authorizationService.canWrite(command.targetDocumentId(), command.writeTargetIds())) {
            throw new DocumentAccessDeniedException();
        }
        documentRepository.swapRelation(command);
        eventPublisher.documentUpdated(command.actorUserId(), command.documentId());
        eventPublisher.documentUpdated(command.actorUserId(), command.targetDocumentId());
    }
}
