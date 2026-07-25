package com.sismics.docs.core.exception;

/**
 * Thrown when an owned entity cannot be created because its prospective owner could not be locked
 * ACTIVE — the account was soft-deleted (or never existed) by the time the creation reached the
 * owner-row lock. Raised today only by
 * {@link com.sismics.docs.core.util.TagCreationUtil#createTag} (#185).
 *
 * <p>A DEDICATED type on purpose: the REST edge maps this — and only this — to a client error. A bare
 * {@code IllegalStateException} would also catch unrelated Hibernate/JPA failures raised inside the
 * same call and silently downgrade a genuine 500 into a misleading 403.</p>
 *
 * <p>Lives OUTSIDE {@code com.sismics.docs.core.dao} on purpose: the resource's catch clause must not
 * become a new {@code rest.resource -> core.dao} edge (the frozen layering ratchet). It is unchecked
 * so callers that legitimately want the failure to abort their transaction need no signature change.</p>
 */
public class InactiveOwnerException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * @param message What could not be created, and for which owner
     */
    public InactiveOwnerException(String message) {
        super(message);
    }
}
