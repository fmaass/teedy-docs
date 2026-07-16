package com.sismics.docs.application.document;

/**
 * Reads a single document as a {@link DocumentView}. The READ authorization lives in the repository
 * lookup (the ACL-scoped query); a miss is surfaced as a not-found.
 */
public class GetDocumentHandler {

    private final DocumentRepository documentRepository;

    public GetDocumentHandler(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    /**
     * @param query The resolved read request
     * @return The document view
     * @throws DocumentNotFoundException when the document is absent or not readable
     */
    public DocumentView handle(GetDocumentQuery query) {
        return documentRepository.load(query)
                .orElseThrow(DocumentNotFoundException::new);
    }
}
