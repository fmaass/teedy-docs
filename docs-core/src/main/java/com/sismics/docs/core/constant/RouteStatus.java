package com.sismics.docs.core.constant;

/**
 * Route statuses.
 *
 * @author bgamard
 */
public enum RouteStatus {
    /**
     * Route in progress, has a current open step.
     */
    ACTIVE,

    /**
     * Route completed successfully (all steps approved/validated).
     */
    DONE,

    /**
     * Route halted by a rejection.
     */
    REJECTED,

    /**
     * Route cancelled (e.g. route or document deleted).
     */
    CANCELLED
}
