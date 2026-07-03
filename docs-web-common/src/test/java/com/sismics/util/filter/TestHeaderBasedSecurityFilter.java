package com.sismics.util.filter;

import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for the header-authentication guards (OIDC-01): trusted-proxy allowlist,
 * overwrite-not-append enforcement, and refusal of the reserved admin account.
 */
public class TestHeaderBasedSecurityFilter {

    @BeforeEach
    public void clearProperty() {
        // Ensure the "empty allowlist" case is deterministic regardless of test ordering.
        System.clearProperty(HeaderBasedSecurityFilter.TRUSTED_PROXIES_PROPERTY);
    }

    /**
     * Builds an initialised, enabled filter with the given trusted-proxy allowlist.
     */
    private HeaderBasedSecurityFilter filterWithProxies(String trustedProxies) {
        HeaderBasedSecurityFilter filter = new HeaderBasedSecurityFilter();
        Map<String, String> params = new HashMap<>();
        params.put("enabled", "true");
        if (trustedProxies != null) {
            params.put("trusted_proxies", trustedProxies);
        }
        filter.init(new StubFilterConfig(params));
        return filter;
    }

    @Test
    public void forgedHeaderFromUntrustedSourceIsRejected() {
        HeaderBasedSecurityFilter filter = filterWithProxies("10.0.0.0/8,192.168.1.5,::1");
        Assertions.assertNull(filter.resolveAuthenticatedUsername("203.0.113.7", List.of("alice")));
    }

    @Test
    public void headerFromTrustedSourceResolves() {
        HeaderBasedSecurityFilter filter = filterWithProxies("10.0.0.0/8,192.168.1.5,::1");
        Assertions.assertEquals("alice", filter.resolveAuthenticatedUsername("10.1.2.3", List.of("alice")));
        Assertions.assertEquals("bob", filter.resolveAuthenticatedUsername("192.168.1.5", List.of("bob")));
        Assertions.assertEquals("carol", filter.resolveAuthenticatedUsername("::1", List.of("carol")));
    }

    @Test
    public void adminIsRefusedEvenFromTrustedSource() {
        HeaderBasedSecurityFilter filter = filterWithProxies("10.0.0.0/8");
        Assertions.assertNull(filter.resolveAuthenticatedUsername("10.1.2.3", List.of("admin")));
        Assertions.assertNull(filter.resolveAuthenticatedUsername("10.1.2.3", List.of("Admin")));
        Assertions.assertNull(filter.resolveAuthenticatedUsername("10.1.2.3", List.of(" admin ")));
    }

    @Test
    public void appendedHeaderIsRejected() {
        HeaderBasedSecurityFilter filter = filterWithProxies("10.0.0.0/8");
        Assertions.assertNull(filter.resolveAuthenticatedUsername("10.1.2.3", Arrays.asList("alice", "admin")));
        Assertions.assertNull(filter.resolveAuthenticatedUsername("10.1.2.3", Collections.emptyList()));
    }

    @Test
    public void blankUsernameIsRejected() {
        HeaderBasedSecurityFilter filter = filterWithProxies("10.0.0.0/8");
        Assertions.assertNull(filter.resolveAuthenticatedUsername("10.1.2.3", List.of("   ")));
    }

    @Test
    public void emptyAllowlistFailsClosed() {
        // No trusted_proxies configured at all -> refuse everything, even from loopback.
        HeaderBasedSecurityFilter filter = filterWithProxies(null);
        Assertions.assertNull(filter.resolveAuthenticatedUsername("127.0.0.1", List.of("alice")));
        Assertions.assertNull(filter.resolveAuthenticatedUsername("10.1.2.3", List.of("alice")));
    }

    @Test
    public void blankConfiguredAllowlistFailsClosed() {
        // A present-but-blank init-param is "configured, empty" -> fail closed.
        HeaderBasedSecurityFilter filter = filterWithProxies("   ");
        Assertions.assertNull(filter.resolveAuthenticatedUsername("127.0.0.1", List.of("alice")));
    }

    @Test
    public void blankInitParamDoesNotFallBackToSystemProperty() {
        // A present-but-blank init-param takes precedence over (does NOT fall back to) a set
        // system property, so a matching system-property allowlist must not silently re-open access.
        System.setProperty(HeaderBasedSecurityFilter.TRUSTED_PROXIES_PROPERTY, "10.0.0.0/8");
        try {
            HeaderBasedSecurityFilter filter = filterWithProxies("");
            Assertions.assertFalse(filter.isTrustedProxy("10.1.2.3"));
            Assertions.assertNull(filter.resolveAuthenticatedUsername("10.1.2.3", List.of("alice")));
        } finally {
            System.clearProperty(HeaderBasedSecurityFilter.TRUSTED_PROXIES_PROPERTY);
        }
    }

    @Test
    public void isTrustedProxyHonorsCidrBoundaries() {
        HeaderBasedSecurityFilter filter = filterWithProxies("10.10.0.0/16");
        Assertions.assertTrue(filter.isTrustedProxy("10.10.0.1"));
        Assertions.assertTrue(filter.isTrustedProxy("10.10.255.254"));
        Assertions.assertFalse(filter.isTrustedProxy("10.11.0.1"));
        Assertions.assertFalse(filter.isTrustedProxy("10.9.255.254"));
        // Exact-IP entry behaves as a /32
        HeaderBasedSecurityFilter exact = filterWithProxies("192.168.1.5");
        Assertions.assertTrue(exact.isTrustedProxy("192.168.1.5"));
        Assertions.assertFalse(exact.isTrustedProxy("192.168.1.6"));
        // IPv4 allowlist never matches an IPv6 peer
        Assertions.assertFalse(filter.isTrustedProxy("::1"));
    }

    @Test
    public void parseTrustedProxiesSkipsBlankAndInvalidEntries() {
        Assertions.assertTrue(HeaderBasedSecurityFilter.parseTrustedProxies(null).isEmpty());
        Assertions.assertTrue(HeaderBasedSecurityFilter.parseTrustedProxies("   ").isEmpty());
        Assertions.assertTrue(HeaderBasedSecurityFilter.parseTrustedProxies("not-an-ip, 999.999.0.0/8, 10.0.0.0/99").isEmpty());
        Assertions.assertEquals(2, HeaderBasedSecurityFilter.parseTrustedProxies("10.0.0.0/8, , 192.168.1.5").size());
    }

    /**
     * Minimal FilterConfig backed by a parameter map.
     */
    private static final class StubFilterConfig implements FilterConfig {
        private final Map<String, String> params;

        StubFilterConfig(Map<String, String> params) {
            this.params = params;
        }

        @Override
        public String getFilterName() {
            return "headerBasedSecurityFilter";
        }

        @Override
        public ServletContext getServletContext() {
            return null;
        }

        @Override
        public String getInitParameter(String name) {
            return params.get(name);
        }

        @Override
        public Enumeration<String> getInitParameterNames() {
            return Collections.enumeration(params.keySet());
        }
    }
}
