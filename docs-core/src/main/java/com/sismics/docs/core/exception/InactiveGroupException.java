package com.sismics.docs.core.exception;

/**
 * Thrown when a group membership cannot be created because the group could not be locked ACTIVE — it was
 * soft-deleted (or never existed) by the time the add reached the group-row lock. Raised today only by
 * {@link com.sismics.docs.core.dao.GroupDao#addMember} (#190).
 *
 * <p>A DEDICATED type on purpose, for the same reason as {@link InactiveOwnerException}: the REST edge
 * maps this — and only this — to the endpoint's already-documented "group not found" client error. A bare
 * {@code IllegalStateException} would also catch unrelated Hibernate/JPA failures raised inside the same
 * call and silently downgrade a genuine 500.</p>
 *
 * <p>Lives OUTSIDE {@code com.sismics.docs.core.dao} on purpose: the resource's catch clause must not
 * become a new {@code rest.resource -> core.dao} edge (the frozen layering ratchet). It is unchecked so
 * callers that legitimately want the failure to abort their transaction need no signature change.</p>
 */
public class InactiveGroupException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * @param message Which group could not be locked active
     */
    public InactiveGroupException(String message) {
        super(message);
    }
}
