package com.sismics.docs.infrastructure.persistence;

import com.sismics.docs.application.document.AccessRecorder;
import com.sismics.docs.core.util.AccessRecordingUtil;

/**
 * {@link AccessRecorder} over {@link AccessRecordingUtil} — the persistence adapter for the document
 * slice's access recording.
 *
 * <p>The write is NOT performed in the read's transaction. It is deferred to that transaction's
 * after-commit callback and executed there in its own short transaction, so a failing insert can
 * neither fail nor delay the read; see {@link AccessRecordingUtil} for why that is the shape and what
 * happens to an event that cannot be written.</p>
 */
public class JpaAccessRecorder implements AccessRecorder {

    @Override
    public void recordDocumentAccess(String userId, String documentId) {
        AccessRecordingUtil.recordDocumentAccess(userId, documentId);
    }
}
