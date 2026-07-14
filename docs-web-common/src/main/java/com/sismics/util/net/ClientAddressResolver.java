package com.sismics.util.net;

import com.google.common.base.Strings;
import com.google.common.net.InetAddresses;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resolves the client IP address of an HTTP request from the {@code X-Forwarded-For} chain, using a
 * rightmost-untrusted traversal so a client cannot spoof its address by injecting header entries.
 *
 * <p>The trusted reverse proxies are configured with a single documented setting: the
 * {@code docs.trusted_proxies} system property, falling back to the {@code DOCS_TRUSTED_PROXIES}
 * environment variable — a comma-separated list of exact IPs and/or {@code addr/prefix} CIDR ranges,
 * parsed by the single shared {@link CidrMatcher} parser. When the trusted set is empty (the default),
 * {@code X-Forwarded-For} is ignored entirely and the transport peer address is used.</p>
 *
 * <p>Algorithm: the chain is treated as (every {@code X-Forwarded-For} element, left to right) followed
 * by the transport peer ({@code remoteAddr}) as the rightmost hop. Walking right to left, trusted proxy
 * hops are removed; the first untrusted address encountered is the client. A leftmost prefix injected by
 * the client is therefore ignored. If the direct peer is itself not trusted, the header is not trusted at
 * all and the peer is returned. If the entire chain is trusted (no untrusted address exists), the leftmost
 * valid {@code X-Forwarded-For} element (the outermost claimed client) is returned. Any malformed,
 * oversized, {@code unknown}, or multi-header input falls back to the direct peer — never a bypass.</p>
 */
public final class ClientAddressResolver {
    /**
     * The X-Forwarded-For header name.
     */
    static final String HEADER = "X-Forwarded-For";

    /**
     * System property holding the comma-separated trusted-proxy allowlist.
     */
    public static final String TRUSTED_PROXIES_PROPERTY = "docs.trusted_proxies";

    /**
     * Environment variable holding the comma-separated trusted-proxy allowlist (fallback).
     */
    public static final String TRUSTED_PROXIES_ENV = "DOCS_TRUSTED_PROXIES";

    /**
     * Cap on the number of comma-separated elements a forwarded chain may contain. A longer header is
     * treated as abusive and ignored (fall back to the peer).
     */
    static final int MAX_XFF_ELEMENTS = 32;

    /**
     * Cap on the raw character length of the forwarded header. A longer header is ignored.
     */
    static final int MAX_XFF_LENGTH = 1024;

    /**
     * Lazily-built process default, sourced from configuration.
     */
    private static volatile ClientAddressResolver instance;

    /**
     * Parsed trusted-proxy allowlist. Empty means "ignore X-Forwarded-For".
     */
    private final List<CidrMatcher> trustedProxies;

    ClientAddressResolver(List<CidrMatcher> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    /**
     * @return the process-default resolver, built from configuration on first use
     */
    public static ClientAddressResolver getInstance() {
        ClientAddressResolver local = instance;
        if (local == null) {
            synchronized (ClientAddressResolver.class) {
                local = instance;
                if (local == null) {
                    local = fromConfig();
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * Builds a resolver from the documented configuration (system property, then environment variable).
     *
     * @return a configured resolver
     */
    static ClientAddressResolver fromConfig() {
        String csv = System.getProperty(TRUSTED_PROXIES_PROPERTY);
        if (csv == null) {
            csv = System.getenv(TRUSTED_PROXIES_ENV);
        }
        return new ClientAddressResolver(CidrMatcher.parseList(csv));
    }

    /**
     * Resolves the client address of the given request.
     *
     * @param request the HTTP request
     * @return the canonical client IP address
     */
    public String resolve(HttpServletRequest request) {
        List<String> headerValues = Collections.list(request.getHeaders(HEADER));
        return resolve(request.getRemoteAddr(), headerValues);
    }

    /**
     * Core resolution over an explicit peer address and forwarded-header value list. Package-private for
     * testing.
     *
     * @param remoteAddr the transport peer address
     * @param xffHeaderValues all values of the X-Forwarded-For header on the request
     * @return the canonical client IP address
     */
    String resolve(String remoteAddr, List<String> xffHeaderValues) {
        String peer = canonicalOrRaw(remoteAddr);

        // Default (no trusted proxies configured): the header is unauthenticated, use the transport peer.
        if (trustedProxies.isEmpty()) {
            return peer;
        }

        // Absent, or split across multiple header lines (suspicious): fall back to the peer.
        if (xffHeaderValues == null || xffHeaderValues.size() != 1) {
            return peer;
        }
        String raw = xffHeaderValues.get(0);
        if (Strings.isNullOrEmpty(raw) || raw.length() > MAX_XFF_LENGTH) {
            return peer;
        }
        // Keep trailing empty elements (limit = -1): "a.b.c.d," and a lone "," must be seen as an empty
        // element and fail closed, not be silently discarded by split()'s default trailing-empty trimming.
        String[] parts = raw.split(",", -1);
        if (parts.length > MAX_XFF_ELEMENTS) {
            return peer;
        }

        // Parse and canonicalize every forwarded element. Any empty/blank or otherwise malformed element
        // (including the literal "unknown" token) invalidates the whole header — fall back to the peer.
        List<InetAddress> xffAddrs = new ArrayList<>(parts.length);
        List<String> xffCanonical = new ArrayList<>(parts.length);
        for (String part : parts) {
            InetAddress addr = parseCanonical(part.trim());
            if (addr == null) {
                return peer;
            }
            xffAddrs.add(addr);
            xffCanonical.add(InetAddresses.toAddrString(addr));
        }

        // The transport peer is the rightmost hop. If it is not a trusted proxy, the forwarded header is
        // untrusted and the client is the peer itself.
        InetAddress peerAddr = parseCanonical(remoteAddr);
        if (peerAddr == null || !CidrMatcher.matchesAny(trustedProxies, peerAddr)) {
            return peer;
        }

        // Peer is trusted: walk the forwarded elements right-to-left; the first untrusted one is the client.
        for (int i = xffAddrs.size() - 1; i >= 0; i--) {
            if (!CidrMatcher.matchesAny(trustedProxies, xffAddrs.get(i))) {
                return xffCanonical.get(i);
            }
        }

        // Entire chain trusted: return the outermost claimed client (leftmost forwarded element).
        return xffCanonical.get(0);
    }

    /**
     * Canonicalizes an address string for display/keying, returning the raw input unchanged when it cannot
     * be parsed (so an unparseable peer is still usable as a throttle key, never a bypass).
     */
    private static String canonicalOrRaw(String addr) {
        InetAddress parsed = parseCanonical(addr);
        return parsed != null ? InetAddresses.toAddrString(parsed) : addr;
    }

    /**
     * Parses an address string into a canonical {@link InetAddress}, mapping an IPv4-in-IPv6 address down to
     * its IPv4 form so that a mapped address matches an IPv4 CIDR range.
     *
     * @param addr the address string
     * @return the canonical address, or null if it cannot be parsed
     */
    private static InetAddress parseCanonical(String addr) {
        if (Strings.isNullOrEmpty(addr)) {
            return null;
        }
        InetAddress parsed;
        try {
            parsed = InetAddresses.forString(addr);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (parsed instanceof Inet6Address) {
            byte[] b = parsed.getAddress();
            boolean mappedPrefix = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    mappedPrefix = false;
                    break;
                }
            }
            if (mappedPrefix && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
                try {
                    return InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]});
                } catch (Exception e) {
                    return parsed;
                }
            }
        }
        return parsed;
    }
}
