package com.sismics.docs.core.util.authentication;

import com.google.common.base.Strings;
import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.dao.ConfigDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.Config;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.ConfigUtil;
import com.sismics.util.ClasspathScanner;
import org.apache.directory.api.ldap.model.cursor.EntryCursor;
import org.apache.directory.api.ldap.model.entry.Attribute;
import org.apache.directory.api.ldap.model.entry.Entry;
import org.apache.directory.api.ldap.model.entry.Value;
import org.apache.directory.api.ldap.model.message.SearchScope;
import org.apache.directory.ldap.client.api.LdapConnection;
import org.apache.directory.ldap.client.api.LdapConnectionConfig;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * LDAP authentication handler.
 *
 * @author bgamard
 */
// After InternalAuthenticationHandler (priority 100): a genuine internal account must
// authenticate by its own password first, so an LDAP directory entry that shares a
// username with a local user (e.g. "admin") cannot hijack the local account via the
// LDAP password. LDAP-provisioned users have an unusable random internal password, so
// internal auth returns null for them and the chain correctly falls through to here.
@ClasspathScanner.Priority(150) // After the internal database
public class LdapAuthenticationHandler implements AuthenticationHandler {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(LdapAuthenticationHandler.class);

    // Conservative, bounded LDAP timeouts (BL-026). Without them, getConnection() used the
    // client library's long/effectively-unbounded defaults, so every failed internal login
    // fell through to a full bind+search against the directory; a slow or unreachable
    // directory then stalled the calling Jetty worker, exhausting the pool. These are kept
    // as constants (not env-configurable) deliberately: the README is owned by another
    // phase, and a fail-fast bound of a few seconds is safe for an interactive login path.
    // A slow directory should fail the login quickly, not hang a request thread.
    static final long LDAP_CONNECT_TIMEOUT_MS = 5000L;   // TCP connect
    static final long LDAP_RESPONSE_TIMEOUT_MS = 10000L; // overall/response, read and write ops

    /**
     * Apply the bounded connect/response timeouts to a connection config so a slow or
     * unreachable directory fails fast instead of hanging a request thread (BL-026).
     *
     * @param config The LDAP connection config to harden
     */
    static void applyConnectionTimeouts(LdapConnectionConfig config) {
        config.setConnectTimeout(LDAP_CONNECT_TIMEOUT_MS);
        config.setTimeout(LDAP_RESPONSE_TIMEOUT_MS);
        config.setReadOperationTimeout(LDAP_RESPONSE_TIMEOUT_MS);
        config.setWriteOperationTimeout(LDAP_RESPONSE_TIMEOUT_MS);
    }

    /**
     * Get a LDAP connection.
     * @return LdapConnection
     */
    private LdapConnection getConnection() {
        ConfigDao configDao = new ConfigDao();
        Config ldapEnabled = configDao.getById(ConfigType.LDAP_ENABLED);
        if (ldapEnabled == null || !Boolean.parseBoolean(ldapEnabled.getValue())) {
            return null;
        }

        LdapConnectionConfig config = new LdapConnectionConfig();
        config.setLdapHost(ConfigUtil.getConfigStringValue(ConfigType.LDAP_HOST));
        config.setLdapPort(ConfigUtil.getConfigIntegerValue(ConfigType.LDAP_PORT));
        config.setUseSsl(ConfigUtil.getConfigBooleanValue(ConfigType.LDAP_USESSL));
        config.setName(ConfigUtil.getConfigStringValue(ConfigType.LDAP_ADMIN_DN));
        config.setCredentials(ConfigUtil.getConfigStringValue(ConfigType.LDAP_ADMIN_PASSWORD));
        applyConnectionTimeouts(config);

        return new LdapNetworkConnection(config);
    }

    /**
     * Escape a value for safe substitution into an LDAP search filter, per RFC 4515.
     * Without this, a username containing filter metacharacters (e.g. "*)(uid=*") could
     * alter the search filter and match/hijack an unintended directory entry.
     *
     * @param value Raw value (the login username)
     * @return Escaped value
     */
    static String escapeLdapSearchFilter(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': sb.append("\\5c"); break;
                case '*':  sb.append("\\2a"); break;
                case '(':  sb.append("\\28"); break;
                case ')':  sb.append("\\29"); break;
                case '\0': sb.append("\\00"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public User authenticate(String username, String password) {
        // Reject a null/blank password BEFORE any search or bind. RFC 4513: a simple bind
        // with a valid DN and an empty password is an unauthenticated (anonymous) bind that
        // many directories accept as SUCCESS — that would let an attacker authenticate as any
        // LDAP user with no password. The login endpoint only strips (not rejects) the
        // password, so an empty one reaches here.
        if (Strings.isNullOrEmpty(password)) {
            return null;
        }

        // Fetch and authenticate the user
        Entry userEntry;
        try (LdapConnection ldapConnection = getConnection()) {
            if (ldapConnection == null) {
                return null;
            }
            ldapConnection.bind();

            // try-with-resources closes the EntryCursor even on the early-return / exception
            // paths (BL-026): a leaked cursor holds server-side and client resources open.
            try (EntryCursor cursor = ldapConnection.search(ConfigUtil.getConfigStringValue(ConfigType.LDAP_BASE_DN),
                    ConfigUtil.getConfigStringValue(ConfigType.LDAP_FILTER).replace("USERNAME", escapeLdapSearchFilter(username)), SearchScope.SUBTREE)) {
                if (cursor.next()) {
                    userEntry = cursor.get();
                    ldapConnection.bind(userEntry.getDn(), password);
                } else {
                    // User not found
                    return null;
                }
            }
        } catch (Exception e) {
            log.error("Error authenticating \"" + username + "\" using the LDAP", e);
            return null;
        }

        UserDao userDao = new UserDao();
        // Case-insensitive lookup (BL-020). An exact = match is case-sensitive on
        // PostgreSQL, so a login as "ADMIN" would miss the local "admin" and provision a
        // second, LDAP-origin shadow account. Folding case here makes the hijack guard
        // below (and the first-time-provisioning branch) hold on every backend, matching
        // H2's IGNORECASE behaviour.
        User user = userDao.getActiveByUsernameIgnoreCase(username);
        if (user == null) {
            // The user is valid but never authenticated, create the user now
            log.info("\"" + username + "\" authenticated for the first time, creating the internal user");
            user = new User();
            user.setRoleId(Constants.DEFAULT_USER_ROLE);
            user.setUsername(username);
            user.setPassword(UUID.randomUUID().toString()); // No authentication using the internal database
            user.setLdap(true); // Mark as LDAP-provisioned so it is never confused with an internal account
            Attribute mailAttribute = userEntry.get("mail");
            if (mailAttribute == null || mailAttribute.get() == null) {
                user.setEmail(ConfigUtil.getConfigStringValue(ConfigType.LDAP_DEFAULT_EMAIL));
            } else {
                Value value = mailAttribute.get();
                user.setEmail(value.getString());
            }
            user.setStorageQuota(ConfigUtil.getConfigLongValue(ConfigType.LDAP_DEFAULT_STORAGE));
            try {
                userDao.create(user, "admin");
            } catch (Exception e) {
                log.error("Error while creating the internal user", e);
                return null;
            }
        } else if (!user.isLdap()) {
            // Account-hijack guard: a username that already exists as an INTERNAL (or OIDC)
            // account must NOT be adoptable via an LDAP bind, even as an authentication
            // fallback. Only LDAP-provisioned accounts may be returned by this handler.
            log.warn("LDAP bind succeeded for \"" + username + "\" but a non-LDAP account with that username already exists; refusing to authenticate to avoid account hijack");
            return null;
        } else if (user.getDisableDate() != null) {
            // A disabled account must not be able to log in, mirroring internal auth
            // (UserDao.authenticate rejects users with a disable date). Without this, an
            // admin disabling an LDAP-provisioned user could not actually block their login.
            log.info("\"" + username + "\" authenticated against LDAP but the account is disabled; refusing login");
            return null;
        }

        return user;
    }
}
