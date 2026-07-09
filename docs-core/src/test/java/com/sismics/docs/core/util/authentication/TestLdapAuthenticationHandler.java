package com.sismics.docs.core.util.authentication;

import org.apache.directory.ldap.client.api.LdapConnectionConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LdapAuthenticationHandler} config hardening.
 *
 * <p>BL-026: {@code getConnection()} set no connect/response timeout, so every failed
 * internal login fell through to a full LDAP bind+search against a possibly-slow
 * directory, stalling Jetty workers until the pool was exhausted. The handler must set
 * conservative connect and response timeouts on the {@link LdapConnectionConfig} so a
 * slow/unreachable directory fails fast instead of hanging a worker.
 */
public class TestLdapAuthenticationHandler {

    /**
     * The connection config must carry a bounded connect timeout and a bounded overall
     * (response) timeout — never the unbounded/default behaviour. Read and write
     * operation timeouts must also be bounded so a stalled search or bind cannot hang.
     */
    @Test
    public void appliesBoundedConnectionTimeouts() {
        LdapConnectionConfig config = new LdapConnectionConfig();
        LdapAuthenticationHandler.applyConnectionTimeouts(config);

        Assertions.assertEquals(LdapAuthenticationHandler.LDAP_CONNECT_TIMEOUT_MS,
                config.getConnectTimeout(),
                "a bounded connect timeout must be set");
        Assertions.assertEquals(LdapAuthenticationHandler.LDAP_RESPONSE_TIMEOUT_MS,
                config.getTimeout(),
                "a bounded overall/response timeout must be set");
        Assertions.assertEquals(LdapAuthenticationHandler.LDAP_RESPONSE_TIMEOUT_MS,
                config.getReadOperationTimeout(),
                "a bounded read-operation timeout must be set");
        Assertions.assertEquals(LdapAuthenticationHandler.LDAP_RESPONSE_TIMEOUT_MS,
                config.getWriteOperationTimeout(),
                "a bounded write-operation timeout must be set");
    }

    /** The timeouts must be conservative, positive, finite values (fail-fast, not disabled). */
    @Test
    public void timeoutsAreConservativePositiveValues() {
        Assertions.assertTrue(LdapAuthenticationHandler.LDAP_CONNECT_TIMEOUT_MS > 0
                        && LdapAuthenticationHandler.LDAP_CONNECT_TIMEOUT_MS <= 15000L,
                "connect timeout must be a conservative positive value");
        Assertions.assertTrue(LdapAuthenticationHandler.LDAP_RESPONSE_TIMEOUT_MS > 0
                        && LdapAuthenticationHandler.LDAP_RESPONSE_TIMEOUT_MS <= 30000L,
                "response timeout must be a conservative positive value");
    }
}
