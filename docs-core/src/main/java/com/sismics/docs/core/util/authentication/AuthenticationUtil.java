package com.sismics.docs.core.util.authentication;

import com.google.common.collect.Lists;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.ClasspathScanner;

import java.util.List;
import java.util.stream.Collectors;

/**
 * User utilities.
 */
public class AuthenticationUtil {
    /**
     * List of authentication handlers scanned in the classpath.
     */
    private static final List<AuthenticationHandler> AUTH_HANDLERS = Lists.newArrayList(
            new ClasspathScanner<AuthenticationHandler>().findClasses(AuthenticationHandler.class, "com.sismics.docs.core.util.authentication")
                    .stream()

                    .map(clazz -> {
                try {
                    return clazz.getDeclaredConstructor().newInstance();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).collect(Collectors.toList()));

    /**
     * Authenticate a user.
     *
     * @param username Username
     * @param password Password
     * @return Authenticated user
     */
    public static User authenticate(String username, String password) {
        for (AuthenticationHandler authenticationHandler : AUTH_HANDLERS) {
            User user = authenticationHandler.authenticate(username, password);
            if (user != null) {
                return user;
            }
        }
        return null;
    }

    /**
     * Whether the account with the given id is external-origin — OIDC- or LDAP-provisioned — and therefore
     * must not be issued a durable local credential. A missing account is treated as non-external (the
     * caller's own existence checks apply). Centralizing the origin question in the authentication layer
     * keeps the partition a single concern and keeps REST callers off a direct DAO dependency.
     *
     * @param userId User ID
     * @return true if the account is OIDC- or LDAP-origin
     */
    public static boolean isExternalOrigin(String userId) {
        return isExternalOrigin(new UserDao().getById(userId));
    }

    /**
     * Whether an already-loaded account is external-origin — OIDC- or LDAP-provisioned. The value overload
     * lets a caller that already holds the {@link User} answer the origin question without a second lookup.
     * A null account is treated as non-external (the caller's own existence checks apply).
     *
     * @param user User (may be null)
     * @return true if the account is OIDC- or LDAP-origin
     */
    public static boolean isExternalOrigin(User user) {
        return user != null && (user.isLdap() || user.getOidcIssuer() != null || user.getOidcSubject() != null);
    }
}
