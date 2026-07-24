package com.sismics.docs.core.util;

import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.totp.GoogleAuthenticator;
import com.sismics.util.totp.GoogleAuthenticatorKey;

/**
 * Owns every TOTP-column read and write for the user account: the {@link UserDao} lookups, the three
 * compare-and-swap writers ({@code setPendingTotpKey} / {@code activateTotpKey} / {@code clearTotpKeys}),
 * and the {@link GoogleAuthenticator} secret generation and code verification. The REST edge keeps the
 * authentication/guest/origin guards, throttle admission, form validation, and response building, and
 * maps a {@link TotpException} back to the wire contract — no resource touches the TOTP columns directly.
 */
public final class TotpService {

    private TotpService() {
    }

    /**
     * Begin an enrollment: generate a fresh secret into the PENDING slot for the named user. Called again
     * while a pending enrollment exists, this replaces it; called while an active key already exists, it is
     * rejected so a hijacked live session cannot silently rotate the key. The write is guarded by a
     * compare-and-swap, so a concurrent activation between the check and the write is also rejected.
     *
     * @param username     The enrolling user's username
     * @param actingUserId Acting user id (audit attribution)
     * @return The pending secret and account label
     * @throws TotpException {@link TotpException.Reason#ALREADY_ENABLED} when TOTP is already active
     */
    public static TotpEnrollment beginEnrollment(String username, String actingUserId) {
        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(username);
        if (user.getTotpKey() != null) {
            throw new TotpException(TotpException.Reason.ALREADY_ENABLED);
        }

        GoogleAuthenticatorKey key = new GoogleAuthenticator().createCredentials();
        if (userDao.setPendingTotpKey(user.getId(), key.getKey(), actingUserId) != 1) {
            // A concurrent activation set an active key between the check above and this write.
            throw new TotpException(TotpException.Reason.ALREADY_ENABLED);
        }
        return new TotpEnrollment(key.getKey(), user.getUsername());
    }

    /**
     * Confirm a pending enrollment: verify {@code validationCode} against the pending secret, then promote
     * it to the active login factor under a compare-and-swap. A concurrent admin recovery that cleared the
     * pending key wins, and this activation then affects 0 rows and fails closed rather than resurrecting
     * the cleared key.
     *
     * @param username       The activating user's username
     * @param validationCode The submitted TOTP code
     * @param actingUserId   Acting user id (audit attribution)
     * @throws TotpException {@link TotpException.Reason#NO_PENDING} when there is no pending enrollment,
     *                       {@link TotpException.Reason#CODE_INVALID} when the code does not verify
     */
    public static void activateEnrollment(String username, int validationCode, String actingUserId) {
        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(username);
        String pendingKey = user.getTotpKeyPending();
        if (pendingKey == null) {
            throw new TotpException(TotpException.Reason.NO_PENDING);
        }

        if (!new GoogleAuthenticator().authorize(pendingKey, validationCode)) {
            throw new TotpException(TotpException.Reason.CODE_INVALID);
        }

        if (userDao.activateTotpKey(user.getId(), pendingKey, actingUserId) != 1) {
            throw new TotpException(TotpException.Reason.NO_PENDING);
        }
    }

    /**
     * Clear both the active and any pending TOTP key for a user resolved by the caller (the current user,
     * password-confirmed at the edge). The dedicated compare-and-swap writer is the only path that clears
     * the columns; the generic update path no longer copies them.
     *
     * @param userId       The user whose keys are cleared
     * @param actingUserId Acting user id (audit attribution)
     */
    public static void clearTotp(String userId, String actingUserId) {
        new UserDao().clearTotpKeys(userId, actingUserId);
    }

    /**
     * Clear both the active and any pending TOTP key for the named user (admin recovery for a user locked
     * out of their authenticator).
     *
     * @param username     The user whose keys are cleared
     * @param actingUserId Acting user id (audit attribution)
     * @throws TotpException {@link TotpException.Reason#USER_NOT_FOUND} when no such active user exists
     */
    public static void clearTotpForUsername(String username, String actingUserId) {
        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(username);
        if (user == null) {
            throw new TotpException(TotpException.Reason.USER_NOT_FOUND);
        }
        userDao.clearTotpKeys(user.getId(), actingUserId);
    }
}
