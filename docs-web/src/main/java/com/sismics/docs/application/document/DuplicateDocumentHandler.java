package com.sismics.docs.application.document;

/**
 * Duplicates a document. The caller's READ permission is checked before any copy work (a non-reader is
 * denied before the source is touched), then the repository performs the copy.
 */
public class DuplicateDocumentHandler {

    private final DocumentRepository documentRepository;
    private final DocumentAuthorizationService authorizationService;

    public DuplicateDocumentHandler(DocumentRepository documentRepository,
                                    DocumentAuthorizationService authorizationService) {
        this.documentRepository = documentRepository;
        this.authorizationService = authorizationService;
    }

    /**
     * @param command The duplication request
     * @return The new document id
     * @throws DocumentAccessDeniedException when the caller cannot READ the source
     * @throws DocumentNotFoundException     when the source vanished before the copy could start
     * @throws DocumentValidationException   when the copy would exceed the requester's quota
     * @throws DocumentFileAccessException   when a source file's content cannot be read
     */
    public String handle(DuplicateDocumentCommand command) {
        if (!authorizationService.canRead(command.sourceId(), command.readTargetIds())) {
            throw new DocumentAccessDeniedException();
        }
        return documentRepository.duplicate(command);
    }
}
