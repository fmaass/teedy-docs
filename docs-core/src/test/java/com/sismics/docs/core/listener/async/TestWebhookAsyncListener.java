package com.sismics.docs.core.listener.async;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the webhook outbound client configuration (SSRF hardening).
 */
public class TestWebhookAsyncListener {

    @Test
    public void doesNotFollowRedirects() {
        // Following a 3xx would let an allowed public URL redirect to a blocked
        // loopback/link-local/metadata address, bypassing the SSRF validation.
        Assertions.assertFalse(WebhookAsyncListener.client.followRedirects(),
                "Webhook client must not follow HTTP redirects");
        Assertions.assertFalse(WebhookAsyncListener.client.followSslRedirects(),
                "Webhook client must not follow HTTPS redirects");
    }
}
