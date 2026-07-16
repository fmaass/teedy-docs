package com.sismics.docs.application.document;

/**
 * The caller lacks WRITE permission on the document. The edge maps it to a 403.
 */
public class DocumentAccessDeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
}
