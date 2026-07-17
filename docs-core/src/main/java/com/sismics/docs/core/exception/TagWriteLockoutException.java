package com.sismics.docs.core.exception;

/**
 * Thrown by {@code AclDao.delete} when removing a WRITE grant would leave a live tag with no WRITE
 * holder — the last-owner invariant (#88). Enforced in the DAO so every caller upholds it, not just
 * the REST path, and raised under the tag's row lock so concurrent revokes cannot race past it.
 *
 * <p>Lives OUTSIDE {@code com.sismics.docs.core.dao} on purpose: the REST resource maps it to a
 * client error, and that catch must not become a new {@code rest.resource -> core.dao} edge (the
 * frozen layering ratchet). It is unchecked so the ROUTING-ACL cleanup path (the only other
 * {@code AclDao.delete} caller, which deletes READ/ROUTING grants the guard never inspects) needs no
 * change.
 */
public class TagWriteLockoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TagWriteLockoutException() {
        super("Cannot remove the last write permission on a tag");
    }
}
