package com.sismics.docs.rest.resource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

/**
 * Unit test for the OIDC outbound HTTP client timeouts (SEC hardening).
 *
 * <p>Discovery/JWKS/token calls run synchronously on Jetty threads; without a total
 * per-call bound a slow-drip IdP could pin a thread indefinitely. Assert an explicit
 * callTimeout (whole-call bound) plus connect/read/write timeouts are configured.</p>
 */
public class TestOidcHttpClient {

    @Test
    public void hasTotalCallTimeout() {
        long expectedMs = TimeUnit.SECONDS.toMillis(OidcResource.HTTP_CALL_TIMEOUT_SECONDS);

        // callTimeout is the total per-call bound (default is 0 = disabled). Must be set.
        Assertions.assertTrue(OidcResource.httpClient.callTimeoutMillis() > 0,
                "OIDC client must set a total callTimeout");
        Assertions.assertEquals(expectedMs, OidcResource.httpClient.callTimeoutMillis());
    }

    @Test
    public void hasConnectReadWriteTimeouts() {
        long expectedMs = TimeUnit.SECONDS.toMillis(OidcResource.HTTP_CALL_TIMEOUT_SECONDS);
        Assertions.assertEquals(expectedMs, OidcResource.httpClient.connectTimeoutMillis());
        Assertions.assertEquals(expectedMs, OidcResource.httpClient.readTimeoutMillis());
        Assertions.assertEquals(expectedMs, OidcResource.httpClient.writeTimeoutMillis());
    }
}
