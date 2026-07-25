package com.sismics.docs.core.dao;

/**
 * Signals that a saved filter name is already taken for the user, whether detected by an
 * in-request {@code flush()} surfacing the (user, name) unique-constraint violation
 * ({@link SavedFilterDao#create}, {@link SavedFilterDao#update}) or by the case-insensitive
 * precheck ahead of it.
 *
 * <p>The DAO flushes and translates the dialect-specific constraint violation
 * HERE, in-request, rather than letting it defer to the RequestContextFilter's
 * end-of-request commit — a deferred violation would surface as a 500. The
 * resource catches this and returns a 400, so a concurrent duplicate (which the
 * case-insensitive precheck cannot catch under a true race) still yields
 * a client error rather than a server error.
 */
public class SavedFilterExistsException extends Exception {
    private static final long serialVersionUID = 1L;

    /** The DB unique index rejected the write; {@code cause} is the underlying violation. */
    public SavedFilterExistsException(Throwable cause) {
        super(cause);
    }

    /** The case-insensitive precheck rejected the name before any write was attempted. */
    public SavedFilterExistsException() {
        super();
    }
}
