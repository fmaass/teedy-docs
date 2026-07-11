package com.sismics.docs.core.dao;

/**
 * Thrown by {@link SavedFilterDao#create} when an in-request {@code flush()}
 * surfaces the (user, name) unique-constraint violation.
 *
 * <p>The DAO flushes and translates the dialect-specific constraint violation
 * HERE, in-request, rather than letting it defer to the RequestContextFilter's
 * end-of-request commit — a deferred violation would surface as a 500. The
 * resource catches this and returns a 400, so a concurrent duplicate (which the
 * case-insensitive resource precheck cannot catch under a true race) still yields
 * a client error rather than a server error.
 */
public class SavedFilterExistsException extends Exception {
    private static final long serialVersionUID = 1L;

    public SavedFilterExistsException(Throwable cause) {
        super(cause);
    }
}
