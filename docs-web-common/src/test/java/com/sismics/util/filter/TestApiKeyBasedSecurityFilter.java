package com.sismics.util.filter;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Authorization-header parsing of the API-key filter. The HTTP auth-scheme is
 * case-insensitive (RFC 7235), so a {@code tdapi_*} key must be recognized (and, if invalid, rejected)
 * regardless of the scheme casing — never silently falling through to cookie auth.
 */
public class TestApiKeyBasedSecurityFilter {

    @Test
    public void recognizesTdapiKeyInAnySchemeCasing() {
        Assertions.assertEquals("tdapi_abc", ApiKeyBasedSecurityFilter.extractApiKey("Bearer tdapi_abc"));
        Assertions.assertEquals("tdapi_abc", ApiKeyBasedSecurityFilter.extractApiKey("bearer tdapi_abc"));
        Assertions.assertEquals("tdapi_abc", ApiKeyBasedSecurityFilter.extractApiKey("BEARER tdapi_abc"));
        Assertions.assertEquals("tdapi_abc", ApiKeyBasedSecurityFilter.extractApiKey("BeArEr   tdapi_abc"));
    }

    @Test
    public void ignoresNonApiKeyCredentials() {
        // Not a tdapi_* token: not our credential.
        Assertions.assertNull(ApiKeyBasedSecurityFilter.extractApiKey("Bearer someoauthtoken"));
        // Different scheme.
        Assertions.assertNull(ApiKeyBasedSecurityFilter.extractApiKey("Basic dGRhcGlfYWJj"));
        // No scheme / malformed.
        Assertions.assertNull(ApiKeyBasedSecurityFilter.extractApiKey("tdapi_abc"));
        Assertions.assertNull(ApiKeyBasedSecurityFilter.extractApiKey(""));
        Assertions.assertNull(ApiKeyBasedSecurityFilter.extractApiKey(null));
    }
}
