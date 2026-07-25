package com.sismics.docs.core.dao.criteria;

import com.sismics.docs.core.constant.AuditLogType;

/**
 * Audit log criteria.
 *
 * <p>The scope fields ({@link #documentId} / {@link #userId} + {@link #isAdmin}) decide WHICH rows
 * the caller may see. The filter fields ({@link #type} / {@link #entityClass} / {@link #username} /
 * {@link #afterDate}) only NARROW that scope — the DAO AND-composes them into every branch of the
 * scope UNION, so a filter can never widen what the scope allows.
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

    /**
     * Narrowing filter: only rows of this audit type. Null = every type.
     */
    private AuditLogType type;

    /**
     * Narrowing filter: only rows whose LOG_CLASSENTITY_C equals this simple class name
     * (a {@code Loggable} implementor). Null = every class.
     */
    private String entityClass;

    /**
     * Narrowing filter: only rows AUTHORED by this username. Matched against
     * T_USER.USE_USERNAME_C through the join the audit query already carries — the response
     * exposes the username (never the internal id), so the filter speaks the same vocabulary the
     * caller reads. Bound under its OWN parameter name so it can never collide with the
     * {@code :userId} SCOPE binding. Null = every author.
     */
    private String username;

    /**
     * Narrowing filter: only rows created at or after this instant (epoch millis). Inclusive.
     * Null = no lower bound. This is a FILTER, unrelated to the {@link #beforeDate} cursor.
     */
    private Long afterDate;

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

    public AuditLogType getType() {
        return type;
    }

    public void setType(AuditLogType type) {
        this.type = type;
    }

    public String getEntityClass() {
        return entityClass;
    }

    public void setEntityClass(String entityClass) {
        this.entityClass = entityClass;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Long getAfterDate() {
        return afterDate;
    }

    public void setAfterDate(Long afterDate) {
        this.afterDate = afterDate;
    }
}
