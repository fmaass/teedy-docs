package com.sismics.docs.core.constant;

/**
 * ACL type.
 *
 * @author bgamard 
 */
public enum AclType {
    /**
     * User created ACL.
     */
    USER,

    /**
     * ACL created by the routing/workflow subsystem. A ROUTING READ ACL grants the current step's
     * target temporary read access to the routed document; it is created when a step becomes current
     * and removed when the step ends. Reinstated with the workflow feature (ADR-0013 reverses the
     * removal made in ADR-0004).
     */
    ROUTING
}
