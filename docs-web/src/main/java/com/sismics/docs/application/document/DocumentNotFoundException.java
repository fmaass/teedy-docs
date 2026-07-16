package com.sismics.docs.application.document;

/**
 * The requested document does not exist or is not readable by the caller. The edge maps it to a 404.
 */
public class DocumentNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
}
