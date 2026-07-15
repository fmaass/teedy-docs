package com.sismics.util.filter;

import com.google.common.base.Strings;
import com.google.common.net.InetAddresses;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.net.CidrMatcher;

import jakarta.servlet.FilterConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;

/**
 * A header-based security filter that authenticates an user using the "X-Authenticated-User" request header as the user ID.
 * This filter is intended to be used in conjunction with an external authenticating proxy.
 *
 * <p>Because the header is trivially forgeable by any client that can reach the application, the header is only trusted
 * when the request originates from a configured trusted proxy (an IP / CIDR allowlist supplied via the
 * {@code trusted_proxies} init-parameter or the {@code docs.header_authentication_trusted_proxies} system property).
 * If header authentication is enabled but no trusted proxy is configured, all header-based authentication is refused
 * (fail-closed). The built-in {@code admin} account can never be assumed through this header.</p>
 *
 * @author pacien
 */
public class HeaderBasedSecurityFilter extends SecurityFilter {
    /**
     * Authentication header.
     */
    public static final String AUTHENTICATED_USER_HEADER = "X-Authenticated-User";

    /**
     * System property holding the comma-separated trusted-proxy allowlist (IPs and/or CIDR ranges).
     */
    public static final String TRUSTED_PROXIES_PROPERTY = "docs.header_authentication_trusted_proxies";

    /**
     * The built-in administrator account, which must never be authenticated through a header.
     */
    private static final String RESERVED_ADMIN_USERNAME = "admin";

    /**
     * True if this authentication method is enabled.
     */
    private boolean enabled;

    /**
     * Parsed trusted-proxy allowlist. When empty, header authentication is refused (fail-closed).
     */
    private List<CidrMatcher> trustedProxies;

    @Override
    public void init(FilterConfig filterConfig) {
        enabled = Boolean.parseBoolean(filterConfig.getInitParameter("enabled"))
                || Boolean.parseBoolean(System.getProperty("docs.header_authentication"));

        // Init-param takes precedence; a present-but-blank init-param means "configured, empty" (fail-closed)
        // and does NOT fall back to the system property.
        String trusted = filterConfig.getInitParameter("trusted_proxies");
        if (trusted == null) {
            trusted = System.getProperty(TRUSTED_PROXIES_PROPERTY);
        }
        trustedProxies = parseTrustedProxies(trusted);

        if (enabled && trustedProxies.isEmpty()) {
            LOG.warn("Header authentication is enabled but no trusted proxies are configured (init-param "
                    + "'trusted_proxies' / system property '" + TRUSTED_PROXIES_PROPERTY + "'); all header-based "
                    + "authentication will be refused (fail-closed).");
        }
    }

    @Override
    protected String getMechanism() {
        return MECHANISM_TRUSTED_HEADER;
    }

    @Override
    protected AuthAttempt attempt(HttpServletRequest request) {
        if (!enabled) {
            return AuthAttempt.absent();
        }

        List<String> headerValues = Collections.list(request.getHeaders(AUTHENTICATED_USER_HEADER));
        if (headerValues.isEmpty()) {
            return AuthAttempt.absent();
        }
        String username = resolveAuthenticatedUsername(request.getRemoteAddr(), headerValues);
        if (username == null) {
            // Header present but not trusted / not usable: fall through to anonymous (denied downstream).
            return AuthAttempt.ignore();
        }
        User user = new UserDao().getActiveByUsername(username);
        if (!isEligible(user)) {
            return AuthAttempt.ignore();
        }
        return AuthAttempt.authenticated(user, null);
    }

    /**
     * Applies the header-auth guards and returns the username to authenticate, or null to refuse.
     * Package-private for testing.
     *
     * <ul>
     *   <li>the request must originate from a trusted proxy (fail-closed when the allowlist is empty);</li>
     *   <li>the header must be present exactly once (the proxy must overwrite, not append);</li>
     *   <li>the built-in {@code admin} account is always refused.</li>
     * </ul>
     *
     * @param remoteAddr   the immediate TCP peer address (the proxy), never the spoofable X-Forwarded-For
     * @param headerValues all values of the authenticated-user header on the request
     * @return the username to look up, or null to refuse header authentication
     */
    String resolveAuthenticatedUsername(String remoteAddr, List<String> headerValues) {
        if (!isTrustedProxy(remoteAddr)) {
            return null;
        }
        // The proxy must overwrite the header. More than one value means a client-injected value slipped through.
        if (headerValues == null || headerValues.size() != 1) {
            return null;
        }
        String username = headerValues.get(0);
        if (username == null) {
            return null;
        }
        username = username.trim();
        if (username.isEmpty()) {
            return null;
        }
        if (RESERVED_ADMIN_USERNAME.equalsIgnoreCase(username)) {
            LOG.warn("Refused header-based authentication attempt for the reserved admin account from " + remoteAddr);
            return null;
        }
        return username;
    }

    /**
     * @return true if the given remote address is within the configured trusted-proxy allowlist
     */
    boolean isTrustedProxy(String remoteAddr) {
        if (Strings.isNullOrEmpty(remoteAddr) || trustedProxies.isEmpty()) {
            return false;
        }
        InetAddress address;
        try {
            address = InetAddresses.forString(remoteAddr);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return CidrMatcher.matchesAny(trustedProxies, address);
    }

    /**
     * Parses a comma-separated list of trusted-proxy entries (exact IPs and/or {@code addr/prefix} CIDR ranges).
     * Blank and unparseable entries are skipped (and logged). Package-private for testing. Delegates to the
     * single shared {@link CidrMatcher} parser.
     */
    static List<CidrMatcher> parseTrustedProxies(String csv) {
        return CidrMatcher.parseList(csv);
    }
}
