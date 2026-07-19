package com.sismics.docs.core.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the IMAP source identity digest.
 */
public class TestImapSourceIdentity {
    @Test
    public void digestIsLowercaseHexSha256() {
        String digest = ImapSourceIdentity.digest("imap.example.com", "993", "imap", "user@x", "INBOX");
        Assertions.assertEquals(64, digest.length(), "a SHA-256 hex digest is 64 characters");
        Assertions.assertTrue(digest.matches("[0-9a-f]{64}"), "the digest must be lowercase hex only");
    }

    @Test
    public void digestMatchesFixedVector() {
        // Independently computed SHA-256 over the length-prefixed (4-byte big-endian length + UTF-8)
        // components ("h","p","imap","u","INBOX). A change to the digest algorithm (component order,
        // prefixing, hash) would break this vector — this is NOT an assert-against-self.
        Assertions.assertEquals(
                "6031cded3ca4c5b2397e27a6131e3ba0b3d4b3f7344b88bc0fbf0e0b3dffa9c5",
                ImapSourceIdentity.digest("h", "p", "imap", "u", "INBOX"),
                "the digest must match the fixed independently-computed SHA-256 vector");
    }

    @Test
    public void digestIsDeterministic() {
        Assertions.assertEquals(
                ImapSourceIdentity.digest("imap.example.com", "993", "imap", "user@x", "INBOX"),
                ImapSourceIdentity.digest("imap.example.com", "993", "imap", "user@x", "INBOX"),
                "the same source must produce the same digest");
    }

    @Test
    public void digestIsCaseSensitiveOnRawComponents() {
        // Only the hex representation is lowercased; a case-varying username is a DIFFERENT source, so
        // the digest must differ (this is what keeps the constraint identical on H2 SET IGNORECASE).
        Assertions.assertNotEquals(
                ImapSourceIdentity.digest("imap.example.com", "993", "imap", "User@x", "INBOX"),
                ImapSourceIdentity.digest("imap.example.com", "993", "imap", "user@x", "INBOX"),
                "a case difference in the username must change the digest");
    }

    @Test
    public void lengthPrefixDisambiguatesComponentBoundaries() {
        // Without a length prefix, ("ab","c") and ("a","bc") for adjacent components would concatenate
        // to the same bytes. The length prefix must keep them distinct.
        Assertions.assertNotEquals(
                ImapSourceIdentity.digest("h", "p", "imap", "ab", "c"),
                ImapSourceIdentity.digest("h", "p", "imap", "a", "bc"),
                "length-prefixing must disambiguate adjacent component boundaries");
    }

    @Test
    public void differentFolderChangesDigest() {
        Assertions.assertNotEquals(
                ImapSourceIdentity.digest("imap.example.com", "993", "imap", "user@x", "INBOX"),
                ImapSourceIdentity.digest("imap.example.com", "993", "imap", "user@x", "Archive"),
                "a different folder must change the digest");
    }
}
