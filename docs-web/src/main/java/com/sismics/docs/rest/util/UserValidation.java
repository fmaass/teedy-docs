package com.sismics.docs.rest.util;

import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.User;
import org.apache.commons.lang3.StringUtils;

/**
 * Helpers extracted from {@link com.sismics.docs.rest.resource.UserResource} to remove the
 * validation/field-assignment duplicated across the two {@code update} overloads. These are
 * exact moves of logic that previously lived inline in the resource; behavior is unchanged.
 *
 * @author jtremeaux
 */
public final class UserValidation {
    private UserValidation() {
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
