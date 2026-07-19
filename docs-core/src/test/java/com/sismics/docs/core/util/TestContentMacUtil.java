package com.sismics.docs.core.util;

import com.google.common.io.BaseEncoding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Pure unit tests for {@link ContentMacUtil}: the external-secret resolution (direct / mounted file /
 * degrade-to-off), the exact per-document keyed-MAC definition, streaming-vs-whole-stream equivalence (the
 * upload path and the backfill path MUST agree), and cross-document non-linkage.
 */
public class TestContentMacUtil extends com.sismics.BaseTest {

    @BeforeEach
    public void reset() {
        clearProps();
        ContentMacUtil.resetForTest();
    }

    @AfterEach
    public void tearDown() {
        clearProps();
        ContentMacUtil.resetForTest();
    }

    private void clearProps() {
        System.clearProperty(ContentMacUtil.KEY_PROP);
        System.clearProperty(ContentMacUtil.KEY_FILE_PROP);
    }

    /** Independent reference implementation of the settled definition (NOT the unit under test). */
    private static String reference(String master, String documentId, byte[] plaintext) throws Exception {
        Mac k = Mac.getInstance("HmacSHA256");
        k.init(new SecretKeySpec(master.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] docKey = k.doFinal(("teedy-dedup-v1:" + documentId).getBytes(StandardCharsets.UTF_8));
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(docKey, "HmacSHA256"));
        return BaseEncoding.base16().lowerCase().encode(m.doFinal(plaintext));
    }

    // --- external-secret resolution -------------------------------------------------------------------

    @Test
    public void resolve_absentIsDisabled() {
        Assertions.assertNull(ContentMacUtil.resolveMasterKey(), "no config -> null (feature off)");
        Assertions.assertFalse(ContentMacUtil.isEnabled());
    }

    /** A strong (>= 32-byte) master secret in the preferred hex encoding (`openssl rand -hex 32` shape). */
    private static final String HEX_KEY_32B = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void resolve_directValue() {
        System.setProperty(ContentMacUtil.KEY_PROP, "  " + HEX_KEY_32B + "  ");
        byte[] key = ContentMacUtil.resolveMasterKey();
        Assertions.assertNotNull(key);
        Assertions.assertArrayEquals(BaseEncoding.base16().lowerCase().decode(HEX_KEY_32B), key,
                "the value is trimmed and hex-decoded to 32 bytes");
    }

    @Test
    public void resolve_blankDirectValueIsDisabled() {
        System.setProperty(ContentMacUtil.KEY_PROP, "   ");
        Assertions.assertNull(ContentMacUtil.resolveMasterKey(), "a blank value is treated as unset");
    }

    @Test
    public void resolve_weakOrShortKeyDegradesToOff() {
        // A passphrase (not hex/base64 key material) and a short hex (16 hex = 8 bytes) both fail the
        // 32-byte floor: the feature stays OFF, exactly like an absent key — no throw, MAC null.
        System.setProperty(ContentMacUtil.KEY_PROP, "password");
        Assertions.assertNull(ContentMacUtil.resolveMasterKey(), "a weak passphrase must NOT enable the feature");
        System.setProperty(ContentMacUtil.KEY_PROP, "0123456789abcdef");
        Assertions.assertNull(ContentMacUtil.resolveMasterKey(), "a 8-byte key is below the 256-bit floor");
        // And it composes with degrade-to-off: isEnabled() is false and computeMac returns null.
        ContentMacUtil.resetForTest();
        Assertions.assertFalse(ContentMacUtil.isEnabled(), "a weak key leaves the feature OFF");
    }

    @Test
    public void resolve_base64KeyAccepted() {
        byte[] material = new byte[32];
        for (int i = 0; i < material.length; i++) {
            material[i] = (byte) (i + 1);
        }
        System.setProperty(ContentMacUtil.KEY_PROP, Base64.getEncoder().encodeToString(material));
        Assertions.assertArrayEquals(material, ContentMacUtil.resolveMasterKey(), "a 32-byte base64 key is accepted");
    }

    @Test
    public void resolve_properKeyEnablesAndProducesMac() throws Exception {
        // Drive the REAL resolution path (not the test seam): a strong key enables the feature and MACs flow.
        System.setProperty(ContentMacUtil.KEY_PROP, HEX_KEY_32B);
        ContentMacUtil.resetForTest();
        Assertions.assertTrue(ContentMacUtil.isEnabled(), "a >= 32-byte key enables the feature");
        Assertions.assertNotNull(ContentMacUtil.computeMac("doc-1", new ByteArrayInputStream("x".getBytes())),
                "an enabled feature produces a MAC");
    }

    @Test
    public void resolve_mountedSecretFileTakesPrecedence_andStripsTrailingNewline() throws Exception {
        Path secret = Files.createTempFile("dedup-secret", ".key");
        Files.write(secret, (HEX_KEY_32B + "\n").getBytes(StandardCharsets.UTF_8));
        try {
            System.setProperty(ContentMacUtil.KEY_FILE_PROP, secret.toString());
            System.setProperty(ContentMacUtil.KEY_PROP, "ignored-when-file-present");
            byte[] key = ContentMacUtil.resolveMasterKey();
            Assertions.assertArrayEquals(BaseEncoding.base16().lowerCase().decode(HEX_KEY_32B), key,
                    "the mounted file wins over the direct value and its trailing newline is stripped");
        } finally {
            Files.deleteIfExists(secret);
        }
    }

    @Test
    public void resolve_missingSecretFileIsDisabled_noThrow() {
        System.setProperty(ContentMacUtil.KEY_FILE_PROP, "/nonexistent/path/dedup.key");
        Assertions.assertNull(ContentMacUtil.resolveMasterKey(), "a missing secret file degrades to off, never throws");
    }

    @Test
    public void resolve_malformedFilePathDegradesToOff_noThrow() {
        // A NUL byte makes Paths.get throw the UNCHECKED InvalidPathException — it must be caught and degrade
        // to OFF, not crash (FIX 2).
        System.setProperty(ContentMacUtil.KEY_FILE_PROP, "bad\0path.key");
        Assertions.assertNull(ContentMacUtil.resolveMasterKey(), "a malformed key-file path degrades to off, never throws");
    }

    // --- MAC definition -------------------------------------------------------------------------------

    @Test
    public void mac_matchesTheSettledDefinition() throws Exception {
        ContentMacUtil.setMasterKeyForTest("master");
        byte[] plaintext = "the quick brown fox".getBytes(StandardCharsets.UTF_8);
        String mac = ContentMacUtil.computeMac("doc-1", new ByteArrayInputStream(plaintext));
        Assertions.assertEquals(reference("master", "doc-1", plaintext), mac,
                "the MAC must equal HMAC(Kdoc, plaintext) with Kdoc = HMAC(Kmaster, 'teedy-dedup-v1:'||docId)");
        Assertions.assertTrue(mac.matches("[0-9a-f]{64}"), "lowercase 64-hex");
    }

    @Test
    public void mac_disabledReturnsNull() throws Exception {
        // Not enabled -> null regardless of arguments (degrade-to-off), never an exception.
        Assertions.assertNull(ContentMacUtil.computeMac("doc-1", new ByteArrayInputStream("x".getBytes())));
        Assertions.assertNull(ContentMacUtil.newSink("doc-1"));
    }

    @Test
    public void mac_nullDocumentReturnsNull_orphanLeavesNoMac() throws Exception {
        ContentMacUtil.setMasterKeyForTest("master");
        Assertions.assertNull(ContentMacUtil.computeMac(null, new ByteArrayInputStream("x".getBytes())),
                "an orphan upload (null document) gets no MAC");
        Assertions.assertNull(ContentMacUtil.newSink(null));
    }

    @Test
    public void mac_streamingSinkEqualsWholeStream() throws Exception {
        ContentMacUtil.setMasterKeyForTest("master");
        byte[] plaintext = new byte[20_000];
        for (int i = 0; i < plaintext.length; i++) {
            plaintext[i] = (byte) (i * 31 + 7);
        }
        // Feed the sink in irregular chunks (the upload path) ...
        ContentMacUtil.MacSink sink = ContentMacUtil.newSink("doc-x");
        int off = 0;
        int[] chunks = {1, 4096, 8192, 100, 3000};
        int ci = 0;
        while (off < plaintext.length) {
            int len = Math.min(chunks[ci++ % chunks.length], plaintext.length - off);
            sink.update(plaintext, off, len);
            off += len;
        }
        String streamed = sink.finish();
        // ... must equal the whole-stream computation (the backfill path).
        String whole = ContentMacUtil.computeMac("doc-x", new ByteArrayInputStream(plaintext));
        Assertions.assertEquals(whole, streamed, "the streaming upload MAC must equal the whole-stream backfill MAC");
        Assertions.assertEquals(reference("master", "doc-x", plaintext), streamed);
    }

    @Test
    public void mac_crossDocumentDiffers() throws Exception {
        ContentMacUtil.setMasterKeyForTest("master");
        byte[] plaintext = "same bytes".getBytes(StandardCharsets.UTF_8);
        String macA = ContentMacUtil.computeMac("doc-A", new ByteArrayInputStream(plaintext));
        String macB = ContentMacUtil.computeMac("doc-B", new ByteArrayInputStream(plaintext));
        Assertions.assertNotEquals(macA, macB, "per-document keying: same plaintext in different docs -> different MAC");
    }
}
