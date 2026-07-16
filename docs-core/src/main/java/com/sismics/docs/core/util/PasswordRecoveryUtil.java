package com.sismics.docs.core.util;

import com.sismics.docs.core.dao.PasswordRecoveryDao;

/**
 * Helpers for the password-recovery flow that keep unauthenticated REST callers off a direct DAO
 * dependency.
 */
public class PasswordRecoveryUtil {

    private PasswordRecoveryUtil() {
    }

    /**
     * Performs the side-effect-free, bounded work equivalent of a recovery-key creation round-trip, for the
     * nonexistent-account lane of {@code password_lost}. Delegates to the DAO's read-only equalizer so the
     * unauthenticated recovery endpoint spends comparable work for a username that does not exist WITHOUT the
     * REST resource taking on a new persistence edge for it and WITHOUT persisting or mutating any row.
     */
    public static void equalizeNonexistentRecovery() {
        new PasswordRecoveryDao().equalizeNonexistentRecovery();
    }
}
