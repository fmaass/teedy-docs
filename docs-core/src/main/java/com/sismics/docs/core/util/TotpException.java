package com.sismics.docs.core.util;

/**
 * Raised by {@link TotpService} when a TOTP operation is rejected. Each {@link Reason} carries the
 * outcome the REST edge maps to a specific status/type, so the wire contract (400 TotpAlreadyEnabled,
 * 400 NoPendingTotp, 403 for an invalid code or an unknown user) stays owned by the edge while the
 * UserDao lookups, compare-and-swap writers, and code verification live in the service.
 */
public class TotpException extends RuntimeException {
    /**
     * The rejection cause. The edge maps each to its status and error type; the message strings stay at
     * the edge so this core service carries no wire-facing text.
     */
    public enum Reason {
        /** An active TOTP key already exists (enrollment must be disabled first). */
        ALREADY_ENABLED,
        /** There is no pending enrollment to activate (absent or cleared/won by a concurrent write). */
        NO_PENDING,
        /** The supplied validation code did not verify against the pending secret. */
        CODE_INVALID,
        /** The named user does not exist. */
        USER_NOT_FOUND
    }

    private final Reason reason;

    public TotpException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    /**
     * @return The rejection cause
     */
    public Reason getReason() {
        return reason;
    }
}
