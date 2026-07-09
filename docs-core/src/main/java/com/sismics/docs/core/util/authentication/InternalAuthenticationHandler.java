package com.sismics.docs.core.util.authentication;

import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.ClasspathScanner;

/**
 * Authenticate using the internal database.
 *
 * @author bgamard
 */
@ClasspathScanner.Priority(100) // We can add handlers before this one
public class InternalAuthenticationHandler implements AuthenticationHandler {
    @Override
    public User authenticate(String username, String password) {
        UserDao userDao = new UserDao();
        User user = userDao.authenticate(username, password);
        // Refuse LDAP-provisioned accounts: an LDAP-origin user must ALWAYS authenticate
        // through the LDAP handler (so LDAP disable/revocation/password rules are enforced),
        // never via a local password. This completes the origin partition — internal handles
        // only non-LDAP users, LDAP handles only LDAP users — and mirrors how OIDC-origin
        // accounts are kept off the local-password path. Trade-off: if LDAP is globally
        // disabled, LDAP-origin users cannot log in at all, which is correct for an
        // LDAP-authoritative model (same as OIDC users when OIDC is off).
        if (user != null && user.isLdap()) {
            return null;
        }
        return user;
    }
}
