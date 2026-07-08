package com.sismics.docs.core.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

/**
 * Unit tests for {@link WebhookUtil} SSRF guarding.
 */
public class TestWebhookUtil {

    @Test
    public void rejectsCloudMetadataEndpoint() {
        // 169.254.169.254 is link-local (cloud metadata) and must be blocked
        Assertions.assertFalse(WebhookUtil.isUrlAllowed("http://169.254.169.254/"));
        Assertions.assertFalse(WebhookUtil.isUrlAllowed("http://169.254.169.254/latest/meta-data/"));
    }

    @Test
    public void rejectsLoopback() {
        Assertions.assertFalse(WebhookUtil.isUrlAllowed("http://127.0.0.1/"));
        Assertions.assertFalse(WebhookUtil.isUrlAllowed("http://127.0.0.1:8080/hook"));
        Assertions.assertFalse(WebhookUtil.isUrlAllowed("https://localhost/hook"));
    }

    @Test
    public void rejectsPrivateRanges() {
        Assertions.assertFalse(WebhookUtil.isUrlAllowed("http://10.0.0.5/"));
        Assertions.assertFalse(WebhookUtil.isUrlAllowed("http://192.168.1.50/"));
        Assertions.assertFalse(WebhookUtil.isUrlAllowed("http://172.16.0.1/"));
        // Wildcard / any-local
        Assertions.assertFalse(WebhookUtil.isUrlAllowed("http://0.0.0.0/"));
    }

    @Test
    public void rejectsIpv6LoopbackAndPrivateUrls() {
        // IPv6 loopback and unique-local (fc00::/7) as URL literals
        Assertions.assertFalse(WebhookUtil.isUrlAllowed("http://[::1]/"));
        Assertions.assertFalse(WebhookUtil.isUrlAllowed("http://[fc00::1]/"));
        Assertions.assertFalse(WebhookUtil.isUrlAllowed("http://[fd12:3456:789a:1::1]/"));
    }

    @Test
    public void isBlockedAddressCoversIpv6UniqueLocal() throws Exception {
        // fc00::/7 unique-local: NOT caught by isSiteLocalAddress(), must be blocked explicitly
        Assertions.assertTrue(WebhookUtil.isBlockedAddress(InetAddress.getByName("fc00::1")));
        Assertions.assertTrue(WebhookUtil.isBlockedAddress(InetAddress.getByName("fd00::abcd")));
        // IPv6 loopback / link-local
        Assertions.assertTrue(WebhookUtil.isBlockedAddress(InetAddress.getByName("::1")));
        Assertions.assertTrue(WebhookUtil.isBlockedAddress(InetAddress.getByName("fe80::1")));
    }

    @Test
    public void isBlockedAddressUnwrapsIpv4MappedIpv6() throws Exception {
        // ::ffff:127.0.0.1 and ::ffff:10.0.0.1 must be unwrapped and blocked as IPv4
        InetAddress mappedLoopback = InetAddress.getByAddress(new byte[]{
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 127, 0, 0, 1});
        InetAddress mappedPrivate = InetAddress.getByAddress(new byte[]{
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 10, 0, 0, 1});
        Assertions.assertTrue(WebhookUtil.isBlockedAddress(mappedLoopback));
        Assertions.assertTrue(WebhookUtil.isBlockedAddress(mappedPrivate));
    }

    @Test
    public void isBlockedAddressUnwrapsIpv4CompatibleIpv6() throws Exception {
        // IPv4-compatible ::a.b.c.d (high 96 bits zero, NOT ::ffff:) embedding loopback/private
        InetAddress compatLoopback = InetAddress.getByAddress(new byte[]{
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 127, 0, 0, 1});
        InetAddress compatPrivate = InetAddress.getByAddress(new byte[]{
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 192, (byte) 168, 1, 50});
        Assertions.assertTrue(WebhookUtil.isBlockedAddress(compatLoopback), "::127.0.0.1 must be blocked");
        Assertions.assertTrue(WebhookUtil.isBlockedAddress(compatPrivate), "::192.168.1.50 must be blocked");
    }

    @Test
    public void isBlockedAddressUnwraps6to4() throws Exception {
        // 6to4 2002:AABB:CCDD::/48 -> embedded IPv4 = bytes 2..5. Embed 127.0.0.1.
        InetAddress sixToFour = InetAddress.getByAddress(new byte[]{
                0x20, 0x02, 127, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        Assertions.assertTrue(WebhookUtil.isBlockedAddress(sixToFour), "6to4 embedding 127.0.0.1 must be blocked");
    }

    @Test
    public void isBlockedAddressUnwrapsTeredo() throws Exception {
        // Teredo 2001:0000::/32 -> embedded client IPv4 = last 4 bytes XOR 0xFF.
        // Embed 192.168.1.50 => last 4 bytes = ~192.~168.~1.~50 = 63.87.254.205
        InetAddress teredo = InetAddress.getByAddress(new byte[]{
                0x20, 0x01, 0x00, 0x00, 0, 0, 0, 0, 0, 0, 0, 0,
                (byte) (192 ^ 0xFF), (byte) (168 ^ 0xFF), (byte) (1 ^ 0xFF), (byte) (50 ^ 0xFF)});
        Assertions.assertTrue(WebhookUtil.isBlockedAddress(teredo), "Teredo embedding 192.168.1.50 must be blocked");
    }

    @Test
    public void rejectsIpv6CompatibleAndTransitionUrls() {
        // IPv4-compatible literal ::127.0.0.1 as URL, and 6to4 literal embedding 127.0.0.1
        Assertions.assertFalse(WebhookUtil.isUrlAllowed("http://[::127.0.0.1]/"));
        Assertions.assertFalse(WebhookUtil.isUrlAllowed("http://[2002:7f00:0001::]/"));
    }

    @Test
    public void isBlockedAddressAllowsPublicIpv6() throws Exception {
        // A public IPv6 (Google DNS 2001:4860:4860::8888) must not be blocked
        Assertions.assertFalse(WebhookUtil.isBlockedAddress(InetAddress.getByName("2001:4860:4860::8888")));
        // A public 6to4 embedding a public IPv4 (8.8.8.8) must not be blocked
        InetAddress sixToFourPublic = InetAddress.getByAddress(new byte[]{
                0x20, 0x02, 8, 8, 8, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        Assertions.assertFalse(WebhookUtil.isBlockedAddress(sixToFourPublic), "6to4 embedding 8.8.8.8 must be allowed");
    }

    @Test
    public void rejectsNonHttpSchemes() {
        Assertions.assertFalse(WebhookUtil.isUrlAllowed("file:///etc/passwd"));
        Assertions.assertFalse(WebhookUtil.isUrlAllowed("ftp://example.com/"));
        Assertions.assertFalse(WebhookUtil.isUrlAllowed("gopher://example.com/"));
    }

    @Test
    public void rejectsMalformedOrBlank() {
        Assertions.assertFalse(WebhookUtil.isUrlAllowed(null));
        Assertions.assertFalse(WebhookUtil.isUrlAllowed(""));
        Assertions.assertFalse(WebhookUtil.isUrlAllowed("   "));
        Assertions.assertFalse(WebhookUtil.isUrlAllowed("not a url"));
        Assertions.assertFalse(WebhookUtil.isUrlAllowed("http://"));
    }

    @Test
    public void allowPrivateEscapeHatchPermitsLoopback() {
        try {
            System.setProperty(WebhookUtil.ALLOW_PRIVATE_PROPERTY, "true");
            // With the opt-in, loopback/private are allowed...
            Assertions.assertTrue(WebhookUtil.isUrlAllowed("http://127.0.0.1:8080/hook"));
            Assertions.assertTrue(WebhookUtil.isUrlAllowed("http://10.0.0.5/"));
            // ...but non-http schemes are still rejected
            Assertions.assertFalse(WebhookUtil.isUrlAllowed("file:///etc/passwd"));
        } finally {
            System.clearProperty(WebhookUtil.ALLOW_PRIVATE_PROPERTY);
        }
    }

    @Test
    public void allowsPublicHttpsHost() {
        // A public, routable host must be allowed (resolves to a public address).
        // Skip when DNS is unavailable (offline/sandboxed build) rather than fail.
        boolean dnsAvailable;
        try {
            InetAddress.getByName("example.com");
            dnsAvailable = true;
        } catch (Exception e) {
            dnsAvailable = false;
        }
        Assumptions.assumeTrue(dnsAvailable, "DNS unavailable; skipping public-host check");
        Assertions.assertTrue(WebhookUtil.isUrlAllowed("https://example.com/webhook"));
    }
}
