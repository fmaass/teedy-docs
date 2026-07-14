package com.sismics.util.net;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for the rightmost-untrusted X-Forwarded-For resolution.
 */
public class TestClientAddressResolver {
    private static ClientAddressResolver withTrusted(String csv) {
        return new ClientAddressResolver(CidrMatcher.parseList(csv));
    }

    private static List<String> xff(String value) {
        return value == null ? Collections.emptyList() : Collections.singletonList(value);
    }

    @Test
    public void emptyTrustedSetIgnoresForwardedHeader() {
        ClientAddressResolver resolver = withTrusted(null);
        // Default: X-Forwarded-For is not trusted at all; the transport peer wins.
        Assertions.assertEquals("203.0.113.9", resolver.resolve("203.0.113.9", xff("1.2.3.4")));
    }

    @Test
    public void leftmostInjectedEntryIsIgnored() {
        // The peer is a trusted proxy that appended the real client after the spoofed leftmost entry.
        ClientAddressResolver resolver = withTrusted("10.0.0.0/8");
        String client = resolver.resolve("10.1.1.1", xff("1.2.3.4, 198.51.100.7"));
        Assertions.assertEquals("198.51.100.7", client);
    }

    @Test
    public void untrustedPeerUsesPeerNotForwardedHeader() {
        // The direct peer is not a trusted proxy: the header is not trusted, so a spoofed XFF cannot bypass.
        ClientAddressResolver resolver = withTrusted("10.0.0.0/8");
        Assertions.assertEquals("203.0.113.5", resolver.resolve("203.0.113.5", xff("1.2.3.4")));
    }

    @Test
    public void multipleTrustedHopsWalkToFirstUntrusted() {
        ClientAddressResolver resolver = withTrusted("10.0.0.0/8, 192.168.0.0/16");
        // peer 10.0.0.1 (trusted), xff = [client, 192.168.1.2 (trusted)] -> first untrusted from right = client
        String client = resolver.resolve("10.0.0.1", xff("198.51.100.7, 192.168.1.2"));
        Assertions.assertEquals("198.51.100.7", client);
    }

    @Test
    public void entireChainTrustedReturnsLeftmostForwardedElement() {
        ClientAddressResolver resolver = withTrusted("10.0.0.0/8");
        String client = resolver.resolve("10.0.0.1", xff("10.9.9.9, 10.8.8.8"));
        Assertions.assertEquals("10.9.9.9", client);
    }

    @Test
    public void malformedForwardedElementFallsBackToPeer() {
        ClientAddressResolver resolver = withTrusted("10.0.0.0/8");
        Assertions.assertEquals("10.0.0.1", resolver.resolve("10.0.0.1", xff("not-an-ip, 198.51.100.7")));
        Assertions.assertEquals("10.0.0.1", resolver.resolve("10.0.0.1", xff("unknown")));
    }

    @Test
    public void trailingEmptyForwardedElementFallsBackToPeer() {
        ClientAddressResolver resolver = withTrusted("10.0.0.0/8");
        // A trailing comma leaves an empty final element: it must be treated as malformed and fail closed to
        // the peer, not be silently dropped (which would accept the address before the comma).
        Assertions.assertEquals("10.0.0.1", resolver.resolve("10.0.0.1", xff("198.51.100.7,")));
        // A leading empty element is equally malformed.
        Assertions.assertEquals("10.0.0.1", resolver.resolve("10.0.0.1", xff(",198.51.100.7")));
        // A lone comma is two empty elements — must not throw and must fall back to the peer.
        Assertions.assertEquals("10.0.0.1", resolver.resolve("10.0.0.1", xff(",")));
        // An interior empty element (double comma) also invalidates the chain.
        Assertions.assertEquals("10.0.0.1", resolver.resolve("10.0.0.1", xff("198.51.100.7,,203.0.113.9")));
    }

    @Test
    public void multipleHeaderLinesFallBackToPeer() {
        ClientAddressResolver resolver = withTrusted("10.0.0.0/8");
        Assertions.assertEquals("10.0.0.1",
                resolver.resolve("10.0.0.1", Arrays.asList("198.51.100.7", "203.0.113.9")));
    }

    @Test
    public void oversizedChainFallsBackToPeer() {
        ClientAddressResolver resolver = withTrusted("10.0.0.0/8");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ClientAddressResolver.MAX_XFF_ELEMENTS + 5; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("198.51.100.7");
        }
        Assertions.assertEquals("10.0.0.1", resolver.resolve("10.0.0.1", xff(sb.toString())));
    }

    @Test
    public void ipv4MappedForwardedElementIsCanonicalizedToIpv4() {
        ClientAddressResolver resolver = withTrusted("10.0.0.0/8");
        // An IPv4-mapped IPv6 client, forwarded by a trusted peer, canonicalizes to its dotted-quad form.
        String client = resolver.resolve("10.0.0.1", xff("::ffff:198.51.100.7"));
        Assertions.assertEquals("198.51.100.7", client);
    }

    @Test
    public void ipv6TrustedPeerWalksToClient() {
        ClientAddressResolver resolver = withTrusted("2001:db8::/32");
        String client = resolver.resolve("2001:db8::1", xff("198.51.100.7"));
        Assertions.assertEquals("198.51.100.7", client);
    }
}
