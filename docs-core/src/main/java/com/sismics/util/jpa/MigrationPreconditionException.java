package com.sismics.util.jpa;

/**
 * Thrown when a migration precondition is violated, BEFORE any schema change is applied.
 *
 * <p>Unlike a raw {@link java.sql.SQLException} bubbling out of a failed DDL statement (which
 * produces a cryptic constraint stacktrace), this exception carries an actionable, named
 * diagnostic: exactly which data anomaly blocks the upgrade and how to remediate it. The
 * migration runner fails closed on it, leaving the database untouched.
 *
 * @author fmaass
 */
public class MigrationPreconditionException extends Exception {
    public MigrationPreconditionException(String message) {
        super(message);
    }
}
