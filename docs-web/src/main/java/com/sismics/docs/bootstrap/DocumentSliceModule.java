package com.sismics.docs.bootstrap;

import com.sismics.docs.application.document.Clock;
import com.sismics.docs.application.document.DocumentAuthorizationService;
import com.sismics.docs.application.document.DocumentCoverHandler;
import com.sismics.docs.application.document.DocumentEventPublisher;
import com.sismics.docs.application.document.DocumentRepository;
import com.sismics.docs.application.document.DuplicateDocumentHandler;
import com.sismics.docs.application.document.GetDocumentHandler;
import com.sismics.docs.application.document.UnitOfWork;
import com.sismics.docs.application.document.UpdateDocumentHandler;
import com.sismics.docs.infrastructure.persistence.JpaDocumentAuthorizationService;
import com.sismics.docs.infrastructure.persistence.JpaDocumentEventPublisher;
import com.sismics.docs.infrastructure.persistence.JpaDocumentRepository;
import com.sismics.docs.infrastructure.persistence.JpaTransactionRunner;
import com.sismics.docs.infrastructure.runtime.DefaultClock;

/**
 * Plain-Java composition root for the document slice: it wires the object graph once via the
 * initialization-on-demand holder idiom and exposes the edge's entry points. Only the Jersey edge
 * reads the static; everything below it is constructor-injected.
 *
 * <p>The graph is stateless with respect to {@code AppContext} — no member captures it or any of its
 * services (committed events reach the bus at completion time through the A1 dispatcher, not a
 * captured reference) — so it needs no lifecycle across the per-test AppContext churn. Construction
 * is pure object wiring (no I/O, no DB), so once-at-first-use is observationally identical to
 * once-at-start.</p>
 */
public final class DocumentSliceModule {

    private final UnitOfWork unitOfWork;
    private final GetDocumentHandler getDocumentHandler;
    private final UpdateDocumentHandler updateDocumentHandler;
    private final DocumentCoverHandler documentCoverHandler;
    private final DuplicateDocumentHandler duplicateDocumentHandler;

    private DocumentSliceModule() {
        Clock clock = new DefaultClock();
        JpaTransactionRunner transactionRunner = new JpaTransactionRunner();
        DocumentRepository documentRepository = new JpaDocumentRepository(clock);
        DocumentAuthorizationService authorizationService = new JpaDocumentAuthorizationService();
        DocumentEventPublisher eventPublisher = new JpaDocumentEventPublisher(transactionRunner);

        this.unitOfWork = transactionRunner;
        this.getDocumentHandler = new GetDocumentHandler(documentRepository);
        this.updateDocumentHandler = new UpdateDocumentHandler(documentRepository, authorizationService, eventPublisher);
        this.documentCoverHandler = new DocumentCoverHandler(documentRepository, authorizationService, eventPublisher);
        this.duplicateDocumentHandler = new DuplicateDocumentHandler(documentRepository, authorizationService);
    }

    private static final class Holder {
        private static final DocumentSliceModule INSTANCE = new DocumentSliceModule();
    }

    /**
     * @return The single wired module instance
     */
    public static DocumentSliceModule get() {
        return Holder.INSTANCE;
    }

    public UnitOfWork unitOfWork() {
        return unitOfWork;
    }

    public GetDocumentHandler getDocumentHandler() {
        return getDocumentHandler;
    }

    public UpdateDocumentHandler updateDocumentHandler() {
        return updateDocumentHandler;
    }

    public DocumentCoverHandler documentCoverHandler() {
        return documentCoverHandler;
    }

    public DuplicateDocumentHandler duplicateDocumentHandler() {
        return duplicateDocumentHandler;
    }
}
