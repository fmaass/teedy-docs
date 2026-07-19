package com.sismics.docs.core.constant;

/**
 * Audit log types. 
 *
 * @author bgamard 
 */
public enum AuditLogType {
    /**
     * Create.
     */
    CREATE,
    
    /**
     * Update.
     */
    UPDATE,

    /**
     * Delete.
     */
    DELETE,

    /**
     * Authentication-related security event (e.g. a rejected credential conflict). Written as an
     * independent, durable audit row even when the triggering request is rolled back.
     */
    AUTHENTICATION
}
