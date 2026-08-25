package com.sismics.docs.application.document;

/**
 * Reads a single document as a {@link DocumentView}. The READ authorization lives in the repository
 * lookup (the ACL-scoped query); a miss is surfaced as a not-found.
 *
 * <p>A successful read is also RECORDED (#300): the load is the single point where "this identified
 * user was served this document" becomes true, so it is where the access event belongs. Recording
 * after the load — never before — means a refused or missing document leaves no trace, and the event
 * says only what actually happened.</p>
 */
public class GetDocumentHandler {

    private final DocumentRepository documentRepository;

    private final AccessRecorder accessRecorder;

    public GetDocumentHandler(DocumentRepository documentRepository, AccessRecorder accessRecorder) {
        this.documentRepository = documentRepository;
        this.accessRecorder = accessRecorder;
    }

    /**
     * @param query The resolved read request
     * @return The document view
     * @throws DocumentNotFoundException when the document is absent or not readable
     */
    public DocumentView handle(GetDocumentQuery query) {
        DocumentView view = documentRepository.load(query)
                .orElseThrow(DocumentNotFoundException::new);

        // An anonymous share reader has no identity to attribute the read to, and slice 1 counts per
        // user, so a share read is deliberately not recorded rather than recorded against nobody.
        if (!query.anonymous() && query.userId() != null) {
            accessRecorder.recordDocumentAccess(query.userId(), query.id());
        }

        return view;
    }
}
