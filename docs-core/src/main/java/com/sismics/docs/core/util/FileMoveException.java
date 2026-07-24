package com.sismics.docs.core.util;

/**
 * Raised by {@link FileMoveService} when a move is rejected during the pre-lock authorization and
 * resolution phase, before the atomic relink is attempted. Each {@link Reason} carries the outcome the
 * REST edge maps to a specific status/type, so the wire contract (404 / 403 / 400 IllegalFile / 400
 * SameDocument) stays owned by the edge while the DAO lookups and ACL checks live in the service.
 */
public class FileMoveException extends RuntimeException {
    /**
     * The rejection cause. The edge maps each to its status and error type; the message strings stay at
     * the edge so this core service carries no wire-facing text.
     */
    public enum Reason {
        /** The file row does not exist. */
        FILE_NOT_FOUND,
        /** The file exists but is not attached to any document. */
        FILE_NOT_ATTACHED,
        /** The caller lacks WRITE on the source document. */
        SOURCE_FORBIDDEN,
        /** The file already belongs to the target document. */
        SAME_DOCUMENT,
        /** The target document does not exist. */
        TARGET_NOT_FOUND,
        /** The caller lacks WRITE on the target document. */
        TARGET_FORBIDDEN
    }

    private final Reason reason;

    public FileMoveException(Reason reason) {
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
