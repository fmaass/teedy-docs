package com.sismics.docs.application.document;

import java.util.Optional;

/**
 * Read/mutation port for the document slice, expressed purely in DTO/record terms — it exposes no
 * JPA entity types and no {@code jakarta.json} types. The infrastructure adapter is the only place
 * that talks to the persistence layer.
 */
public interface DocumentRepository {

    /**
     * Loads the document as a read model, applying the READ authorization implied by the query's
     * target lists. Empty when the caller cannot read the document (or it does not exist) — the
     * handler translates that into a not-found.
     *
     * @param query The resolved read request
     * @return The document view, or empty when not readable/absent
     */
    Optional<DocumentView> load(GetDocumentQuery query);

    /**
     * Applies the partial update: mutates the document's scalar fields, its tag/relation links, and
     * its metadata per the command's presence flags, and writes the audit trail. The WRITE
     * authorization is checked by the handler BEFORE this call.
     *
     * @param command The validated update instructions
     * @throws DocumentNotFoundException   when the document does not exist
     * @throws DocumentValidationException on an invalid tag/metadata value (mapped to a 400)
     */
    void update(UpdateDocumentCommand command);

    /**
     * Sets the document's explicit cover file and reconciles the served pointer synchronously. The
     * chosen file must be attached to this document (latest version, not deleted); an unattached file
     * is a client error. The WRITE authorization is checked by the handler BEFORE this call.
     *
     * @param command The set-cover instructions
     * @throws DocumentValidationException when the file is not attached to the document (mapped to a 400)
     */
    void setCover(SetDocumentCoverCommand command);

    /**
     * Clears the document's explicit cover file and re-derives the served pointer synchronously. The
     * WRITE authorization is checked by the handler BEFORE this call.
     *
     * @param command The clear-cover instructions
     */
    void clearCover(ClearDocumentCoverCommand command);
}
