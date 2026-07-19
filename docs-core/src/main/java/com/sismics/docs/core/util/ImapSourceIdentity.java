package com.sismics.docs.core.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes the stable identity digest of an IMAP import source.
 *
 * <p>The digest folds the source's (host, port, protocol, username, folder) into one lowercase-hex
 * SHA-256 value. Each component is written length-prefixed (a 4-byte big-endian length then the UTF-8
 * bytes) so no two distinct component tuples can collide by concatenation (e.g. account {@code "ab"} +
 * folder {@code "c"} versus account {@code "a"} + folder {@code "bc"}).</p>
 *
 * <p>Only the HEX digest is lowercased. The raw username and a non-INBOX folder name stay
 * case-sensitive: the digest is what participates in the receipt's unique index, so the constraint is
 * identical on PostgreSQL and on H2 (whose {@code SET IGNORECASE TRUE} would otherwise case-fold raw
 * string columns).</p>
 */
public final class ImapSourceIdentity {
    private ImapSourceIdentity() {
    }

    /**
     * Compute the lowercase-hex identity digest for a source.
     *
     * @param host IMAP host
     * @param port IMAP port (as configured)
     * @param protocol IMAP protocol identifier
     * @param username Account username (case-sensitive)
     * @param folder Folder name (case-sensitive)
     * @return lowercase-hex SHA-256 digest (64 characters)
     */
    public static String digest(String host, String port, String protocol, String username, String folder) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        appendLengthPrefixed(buffer, host);
        appendLengthPrefixed(buffer, port);
        appendLengthPrefixed(buffer, protocol);
        appendLengthPrefixed(buffer, username);
        appendLengthPrefixed(buffer, folder);

        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandated JDK algorithm; its absence is an unrecoverable environment fault.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        byte[] hash = sha256.digest(buffer.toByteArray());
        return toLowerHex(hash);
    }

    private static void appendLengthPrefixed(ByteArrayOutputStream buffer, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        int length = bytes.length;
        // 4-byte big-endian length prefix so component boundaries are unambiguous.
        buffer.write((length >>> 24) & 0xFF);
        buffer.write((length >>> 16) & 0xFF);
        buffer.write((length >>> 8) & 0xFF);
        buffer.write(length & 0xFF);
        buffer.write(bytes, 0, bytes.length);
    }

    private static String toLowerHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >>> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
