package com.sismics.docs.core.dao.criteria;


/**
 * Audit log criteria.
 *
 * @author bgamard 
 */
public class AuditLogCriteria {
    /**
     * Document ID.
     */
    private String documentId;

    /**
     * User ID.
     */
    private String userId;

    /**
     * The search is done for an admin user.
     */
    private boolean isAdmin = false;

    /**
     * Keyset cursor: create date (epoch millis) of the last row of the previous page.
     * Paired with {@link #beforeId}; both null means "first page" (no cursor).
     */
    private Long beforeDate;

    /**
     * Keyset cursor: id (LOG_ID_C) of the last row of the previous page. Paired with
     * {@link #beforeDate}; the tuple (create_date, id) makes the DESC order total.
     */
    private String beforeId;

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public boolean isAdmin() {
        return isAdmin;
    }

    public AuditLogCriteria setAdmin(boolean admin) {
        isAdmin = admin;
        return this;
    }

    public Long getBeforeDate() {
        return beforeDate;
    }

    public void setBeforeDate(Long beforeDate) {
        this.beforeDate = beforeDate;
    }

    public String getBeforeId() {
        return beforeId;
    }

    public void setBeforeId(String beforeId) {
        this.beforeId = beforeId;
    }
}
