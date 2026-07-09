package com.sismics.docs.rest.util;

import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.User;
import org.apache.commons.lang3.StringUtils;

/**
 * Applies optional field updates to a {@link User} for the user-update endpoints:
 * an e-mail is overwritten only when a value is submitted, and a password is
 * (re)hashed and persisted only when a non-blank new password is submitted.
 * Both helpers preserve the existing value when nothing was submitted.
 */
public final class UserUpdateUtil {
    private UserUpdateUtil() {
        // Utility class
    }

    /**
     * Apply an optional e-mail change to a user: only overwrite when a value was submitted.
     *
     * @param user User to update
     * @param email Submitted e-mail (may be null to preserve the existing one)
     */
    public static void applyEmailUpdate(User user, String email) {
        if (email != null) {
            user.setEmail(email);
        }
    }

    /**
     * Persist a password change when a new password was submitted. When the password is blank
     * (or null) the user's password is left unchanged.
     *
     * @param userDao User DAO
     * @param user User to update
     * @param password Submitted password (blank/null to preserve the existing one)
     * @param actingUserId Acting user ID (for the update audit log)
     */
    public static void applyPasswordUpdate(UserDao userDao, User user, String password, String actingUserId) {
        if (StringUtils.isNotBlank(password)) {
            user.setPassword(password);
            userDao.updatePassword(user, actingUserId);
        }
    }
}
