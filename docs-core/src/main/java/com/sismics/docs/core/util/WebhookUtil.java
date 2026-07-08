package com.sismics.docs.core.util;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Webhook URL validation utilities.
 *
 * <p>Guards against server-side request forgery (SSRF): a webhook URL is an
 * admin-configured value that the server later dereferences with an outbound POST.
 * A malicious or mistaken URL pointing at loopback, link-local (including the cloud
 * metadata endpoint 169.254.169.254), or private ranges could be used to reach
 * internal services. Only {@code http}/{@code https} schemes to public hosts are
 * permitted.</p>
 */
public final class WebhookUtil {
    /**
     * System property / env var that, when {@code true}, allows webhooks to target
     * loopback/link-local/private addresses. Defaults to false (SSRF-safe). Intended
     * for deployments whose webhooks legitimately point at internal services on a
     * trusted network, and for tests targeting a local endpoint. Scheme validation
     * (http/https only) always applies regardless of this flag.
     */
    public static final String ALLOW_PRIVATE_PROPERTY = "docs.webhook_allow_private";

    private WebhookUtil() {
    }

    /**
     * Return true if webhooks to private/loopback addresses are permitted, read from
     * the system property, falling back to the environment variable form.
     *
     * @return true if private addresses are allowed
     */
    static boolean isPrivateAllowed() {
        String prop = System.getProperty(ALLOW_PRIVATE_PROPERTY);
        if (prop == null) {
            prop = System.getenv("DOCS_WEBHOOK_ALLOW_PRIVATE");
        }
        return Boolean.parseBoolean(prop);
    }

    /**
     * Return true if the URL is a syntactically valid http/https URL whose host does
     * not resolve to a loopback, link-local, site-local (private), wildcard, or
     * multicast address.
     *
     * <p>Note: this resolves DNS at call time. It is a best-effort guard, not a
     * defense against DNS-rebinding (the resolved address at request time may differ);
     * it is applied both at configuration time and immediately before the outbound
     * call to narrow that window.</p>
     *
     * @param url URL to validate
     * @return true if the URL is allowed
     */
    public static boolean isUrlAllowed(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            return false;
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return false;
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }

        // Operators may opt in to private/loopback targets on a trusted network;
        // scheme validation above still applies.
        if (isPrivateAllowed()) {
            return true;
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return false;
        }
        if (addresses.length == 0) {
            return false;
        }

        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Return true if the address falls in a range that must not be reachable via a
     * webhook (loopback, link-local, site-local/private, wildcard, multicast).
     *
     * <p>Robust IPv6 handling closes the gaps the JDK helpers miss:</p>
     * <ul>
     *   <li>Any embedded IPv4 address is extracted and re-checked with the full IPv4
     *       block rules — covering IPv4-mapped ({@code ::ffff:a.b.c.d}), IPv4-compatible
     *       ({@code ::a.b.c.d}), 6to4 ({@code 2002:a.b.c.d::/48}) and Teredo
     *       ({@code 2001:0::/32}, embedded IPv4 = last 4 bytes XOR 0xFFFFFFFF).</li>
     *   <li>Any IPv6 that is not global unicast is blocked as defense-in-depth:
     *       unique-local {@code fc00::/7}, link-local {@code fe80::/10}, loopback
     *       {@code ::1}, unspecified {@code ::}, multicast {@code ff00::/8}.</li>
     * </ul>
     *
     * @param address Address to check
     * @return true if the address is blocked
     */
    static boolean isBlockedAddress(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            if (bytes.length != 16) {
                return true; // unexpected shape: fail closed
            }

            // Any embedded IPv4: re-check with the full IPv4 rules.
            byte[] embedded = extractEmbeddedIpv4(bytes);
            if (embedded != null) {
                try {
                    return isBlockedAddress(InetAddress.getByAddress(embedded));
                } catch (UnknownHostException e) {
                    return true;
                }
            }

            // Non-global-unicast IPv6 ranges (defense-in-depth).
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            // Unique-local fc00::/7 (0xFC or 0xFD).
            if (first == 0xFC || first == 0xFD) {
                return true;
            }
            // Link-local fe80::/10 (0xFE80..0xFEBF).
            if (first == 0xFE && (second & 0xC0) == 0x80) {
                return true;
            }
            // Multicast ff00::/8.
            if (first == 0xFF) {
                return true;
            }
            // Loopback ::1 and unspecified :: (also covered by JDK helpers above).
            if (isAllZeroExceptLast(bytes) || isAllZero(bytes)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Extract an IPv4 address embedded in a 16-byte IPv6 address, if any, returning it
     * as a 4-byte array; otherwise null.
     *
     * <p>Handles IPv4-mapped ({@code ::ffff:a.b.c.d}), IPv4-compatible
     * ({@code ::a.b.c.d}, excluding {@code ::} and {@code ::1}), 6to4
     * ({@code 2002:a.b.c.d::/48}) and Teredo ({@code 2001:0000::/32}).</p>
     *
     * @param bytes 16-byte IPv6 address
     * @return 4-byte embedded IPv4, or null
     */
    private static byte[] extractEmbeddedIpv4(byte[] bytes) {
        // 6to4: 2002:AABB:CCDD::/48 -> IPv4 = bytes[2..5]
        if ((bytes[0] & 0xFF) == 0x20 && (bytes[1] & 0xFF) == 0x02) {
            return new byte[]{bytes[2], bytes[3], bytes[4], bytes[5]};
        }

        // Teredo: 2001:0000::/32 -> embedded (client) IPv4 = last 4 bytes XOR 0xFF
        if ((bytes[0] & 0xFF) == 0x20 && (bytes[1] & 0xFF) == 0x01
                && (bytes[2] & 0xFF) == 0x00 && (bytes[3] & 0xFF) == 0x00) {
            return new byte[]{
                    (byte) (bytes[12] ^ 0xFF),
                    (byte) (bytes[13] ^ 0xFF),
                    (byte) (bytes[14] ^ 0xFF),
                    (byte) (bytes[15] ^ 0xFF)};
        }

        // High 96 bits zero: IPv4-mapped (::ffff:a.b.c.d) or IPv4-compatible (::a.b.c.d)
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return null;
            }
        }
        boolean mapped = (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
        boolean compatible = bytes[10] == 0 && bytes[11] == 0;
        if (mapped || compatible) {
            // Exclude :: and ::1 (not real embedded IPv4; handled as loopback/unspecified)
            if (compatible && (bytes[12] == 0 && bytes[13] == 0
                    && (bytes[14] == 0) && ((bytes[15] & 0xFF) == 0 || (bytes[15] & 0xFF) == 1))) {
                return null;
            }
            return new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
        }
        return null;
    }

    private static boolean isAllZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllZeroExceptLast(byte[] bytes) {
        for (int i = 0; i < bytes.length - 1; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return (bytes[bytes.length - 1] & 0xFF) == 1;
    }
}
