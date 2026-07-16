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
        // Origin partition: this handler returns ONLY genuine internal accounts. An account
        // provisioned by an external identity provider must ALWAYS authenticate through THAT
        // provider (so its disable/revocation/password rules are enforced), never via a local
        // password — including a password planted through the password-recovery flow. So refuse
        // any account whose origin is external: LDAP-provisioned (isLdap) OR OIDC-provisioned
        // (oidcIssuer/oidcSubject set). This is symmetric with the LDAP handler, which refuses
        // non-LDAP accounts. Trade-off: if the external provider is globally disabled, its users
        // cannot log in at all, which is correct for a provider-authoritative model.
        if (user != null && (user.isLdap() || user.getOidcIssuer() != null || user.getOidcSubject() != null)) {
            return null;
        }
        return user;
    }
}
