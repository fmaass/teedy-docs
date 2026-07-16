package com.sismics.docs.application.document;

import java.util.List;

/**
 * Authorization port for document writes. The adapter delegates to the ACL check, preserving its
 * admin bypass.
 */
public interface DocumentAuthorizationService {

    /**
     * @param documentId Document ID
     * @param targetIds  Caller's ACL target set (user + groups)
     * @return True when the caller may write the document
     */
    boolean canWrite(String documentId, List<String> targetIds);
}
