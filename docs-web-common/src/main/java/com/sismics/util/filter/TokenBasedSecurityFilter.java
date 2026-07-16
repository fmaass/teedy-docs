package com.sismics.util.filter;

import com.sismics.docs.core.dao.AuthenticationTokenDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.AuthenticationToken;
import com.sismics.docs.core.model.jpa.User;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.text.MessageFormat;
import java.util.Date;

/**
 * This filter is used to authenticate the user having an active session via an authentication token stored in database.
 * The filter extracts the authentication token stored in a cookie.
 * If the cookie exists and the token is valid, the filter injects a UserPrincipal into a request attribute.
 * If not, the user is anonymous, and the filter injects a AnonymousPrincipal into the request attribute.
 *
 * @author jtremeaux
 */
public class TokenBasedSecurityFilter extends SecurityFilter {
    /**
     * Name of the cookie used to store the authentication token.
     */
    public static final String COOKIE_NAME = "auth_token";

    /**
     * Default long-lived token lifetime, in days.
     */
    static final int DEFAULT_SESSION_LIFETIME_DAYS = 90;

    /**
     * Lifetime of the long-lived authentication token in seconds.
     * Configurable via DOCS_SESSION_LIFETIME_DAYS env var (default 90 days).
     */
    public static final int TOKEN_LONG_LIFETIME = resolveSessionLifetimeDays() * 3600 * 24;

    /**
     * Resolve the configured long-lived token lifetime in days, falling back to the
     * default on a missing or malformed DOCS_SESSION_LIFETIME_DAYS. This must never
     * throw: a malformed value would otherwise fail the static initializer and break
     * all token-based authentication.
     *
     * @return Session lifetime in days
     */
    static int resolveSessionLifetimeDays() {
        return resolveSessionLifetimeDays(System.getenv().get("DOCS_SESSION_LIFETIME_DAYS"));
    }

    /**
     * Parse a raw session-lifetime value in days, falling back to the default on a
     * null, blank, malformed, or non-positive value.
     *
     * @param raw Raw value (may be null)
     * @return Session lifetime in days
     */
    static int resolveSessionLifetimeDays(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_SESSION_LIFETIME_DAYS;
        }
        try {
            int days = Integer.parseInt(raw.trim());
            if (days <= 0) {
                LOG.warn("DOCS_SESSION_LIFETIME_DAYS must be positive, got '" + raw
                        + "'; falling back to default " + DEFAULT_SESSION_LIFETIME_DAYS);
                return DEFAULT_SESSION_LIFETIME_DAYS;
            }
            return days;
        } catch (NumberFormatException e) {
            LOG.warn("Invalid DOCS_SESSION_LIFETIME_DAYS '" + raw
                    + "'; falling back to default " + DEFAULT_SESSION_LIFETIME_DAYS);
            return DEFAULT_SESSION_LIFETIME_DAYS;
        }
    }
    
    /**
     * Lifetime of the authentication token in seconds, since last connection.
     */
    private static final int TOKEN_SESSION_LIFETIME = 3600 * 24;

    /**
     * Extracts and returns an authentication token from a cookie list.
     *
     * @param cookies Cookie list
     * @return nullable auth token
     */
    private String extractAuthToken(Cookie[] cookies) {
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE_NAME.equals(cookie.getName()) && !cookie.getValue().isEmpty()) {
                    return cookie.getValue();
                }
            }
        }

        return null;
    }

    /**
     * Deletes an expired authentication token.
     *
     * @param authTokenID auth token ID
     */
    private void handleExpiredToken(AuthenticationTokenDao dao, String authTokenID) {
        try {
            dao.delete(authTokenID);
        } catch (Exception e) {
            if (LOG.isErrorEnabled())
                LOG.error(MessageFormat.format("Error deleting authentication token {0} ", authTokenID), e);
        }
    }

    /**
     * Returns true if the token is expired.
     * 
     * @param authenticationToken Authentication token
     * @return Token expired
     */
    private boolean isTokenExpired(AuthenticationToken authenticationToken) {
        final long now = new Date().getTime();
        final long creationDate = authenticationToken.getCreationDate().getTime();
        if (authenticationToken.isLongLasted()) {
            return now >= creationDate + ((long) TOKEN_LONG_LIFETIME) * 1000L;
        } else {
            long date = authenticationToken.getLastConnectionDate() != null ?
                    authenticationToken.getLastConnectionDate().getTime() : creationDate;
            return now >= date + ((long) TOKEN_SESSION_LIFETIME) * 1000L;
        }
    }

    @Override
    protected String getMechanism() {
        return MECHANISM_TOKEN_COOKIE;
    }

    @Override
    protected AuthAttempt attempt(HttpServletRequest request) {
        // Get the value of the client authentication token
        String authTokenId = extractAuthToken(request.getCookies());
        if (authTokenId == null) {
            return AuthAttempt.absent();
        }

        // Get the corresponding server token. An unknown or expired cookie is not a hard rejection: it
        // falls through to anonymous (historical behaviour — a stale cookie must not 401 the request).
        AuthenticationTokenDao authTokenDao = new AuthenticationTokenDao();
        AuthenticationToken authToken = authTokenDao.get(authTokenId);
        if (authToken == null) {
            return AuthAttempt.ignore();
        }

        if (isTokenExpired(authToken)) {
            handleExpiredToken(authTokenDao, authTokenId);
            return AuthAttempt.ignore();
        }

        // Load the user before any bookkeeping side effect. A disabled/deleted account falls through to
        // anonymous so a disabled cookie request never rotates the token — the rotation below is a
        // mutation the disabled user must not trigger. Access is separately denied downstream.
        User user = new UserDao().getById(authToken.getUserId());
        if (!isEligible(user)) {
            return AuthAttempt.ignore();
        }

        // Credential-epoch check: a token stamped at an epoch the user has since advanced past (a reset,
        // admin change, disable, or self password-change) is dead. Checked BEFORE the rotation below so a
        // revoked token never extends its own lifetime; falls through to anonymous like a stale cookie.
        if (!epochMatches(authToken.getCredentialEpoch(), user.getCredentialEpoch())) {
            return AuthAttempt.ignore();
        }

        // Token rotation: if < 30 days remaining on a long-lived token, extend by 90 days
        if (authToken.isLongLasted()) {
            long now = System.currentTimeMillis();
            long creationDate = authToken.getCreationDate().getTime();
            long expiryMs = creationDate + ((long) TOKEN_LONG_LIFETIME) * 1000L;
            long thirtyDaysMs = 30L * 24 * 3600 * 1000;
            if (expiryMs - now < thirtyDaysMs) {
                authToken.setCreationDate(new Date(now));
                authTokenDao.updateCreationDate(authToken);
            }
        }

        return AuthAttempt.authenticated(user, authTokenId);
    }
}
