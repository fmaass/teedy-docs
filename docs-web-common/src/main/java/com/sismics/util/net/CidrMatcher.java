package com.sismics.util.net;

import com.google.common.base.Strings;
import com.google.common.net.InetAddresses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Matches an IP address against a single exact IP or CIDR range (IPv4 or IPv6).
 *
 * <p>This is the single trusted-proxy / CIDR parser shared by every component that needs to decide
 * whether a peer address falls inside a configured allowlist ({@code HeaderBasedSecurityFilter} for
 * header authentication, {@code ClientAddressResolver} for X-Forwarded-For resolution). Keeping one
 * parser avoids the two-implementations drift that a second copy would invite.</p>
 */
public final class CidrMatcher {
    /**
     * Logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CidrMatcher.class);

    private final byte[] network;
    private final int prefixLength;

    private CidrMatcher(byte[] network, int prefixLength) {
        this.network = network;
        this.prefixLength = prefixLength;
    }

    /**
     * Parses a single entry (an exact IP or an {@code addr/prefix} CIDR range).
     *
     * @param entry the entry to parse
     * @return a matcher for the given entry, or null if it cannot be parsed
     */
    public static CidrMatcher parse(String entry) {
        if (entry == null) {
            return null;
        }
        String addressPart = entry;
        Integer prefix = null;
        int slash = entry.indexOf('/');
        if (slash >= 0) {
            addressPart = entry.substring(0, slash);
            try {
                prefix = Integer.parseInt(entry.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        InetAddress address;
        try {
            address = InetAddresses.forString(addressPart.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        byte[] bytes = address.getAddress();
        int maxPrefix = bytes.length * 8;
        if (prefix == null) {
            prefix = maxPrefix;
        }
        if (prefix < 0 || prefix > maxPrefix) {
            return null;
        }
        return new CidrMatcher(bytes, prefix);
    }

    /**
     * Parses a comma-separated list of trusted entries (exact IPs and/or {@code addr/prefix} CIDR
     * ranges). Blank and unparseable entries are skipped (and logged).
     *
     * @param csv comma-separated list, may be null or blank
     * @return the parsed matchers (empty when the input is null/blank/all-unparseable)
     */
    public static List<CidrMatcher> parseList(String csv) {
        List<CidrMatcher> matchers = new ArrayList<>();
        if (Strings.isNullOrEmpty(csv)) {
            return matchers;
        }
        for (String rawEntry : csv.split(",")) {
            String entry = rawEntry.trim();
            if (entry.isEmpty()) {
                continue;
            }
            CidrMatcher matcher = parse(entry);
            if (matcher == null) {
                LOG.warn("Ignoring unparseable trusted-proxy / CIDR entry: " + entry);
            } else {
                matchers.add(matcher);
            }
        }
        return matchers;
    }

    /**
     * @param matchers the allowlist
     * @param candidate the address to test
     * @return true if the candidate matches any matcher in the list
     */
    public static boolean matchesAny(List<CidrMatcher> matchers, InetAddress candidate) {
        for (CidrMatcher matcher : matchers) {
            if (matcher.matches(candidate)) {
                return true;
            }
        }
        return false;
    }

    public boolean matches(InetAddress candidate) {
        byte[] candidateBytes = candidate.getAddress();
        if (candidateBytes.length != network.length) {
            // Different address families (IPv4 vs IPv6) never match.
            return false;
        }
        int fullBytes = prefixLength / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (candidateBytes[i] != network[i]) {
                return false;
            }
        }
        int remainingBits = prefixLength % 8;
        if (remainingBits > 0) {
            int mask = 0xFF << (8 - remainingBits);
            if ((candidateBytes[fullBytes] & mask) != (network[fullBytes] & mask)) {
                return false;
            }
        }
        return true;
    }
}
