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
    USER
    // The ROUTING value was removed with the workflow/routing subsystem (ADR-0004).
    // Migration dbupdate-037-0.sql deletes any pre-existing T_ACL rows with ACL_TYPE_C='ROUTING'.
}
