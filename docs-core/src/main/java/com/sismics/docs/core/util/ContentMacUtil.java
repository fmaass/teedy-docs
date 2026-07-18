package com.sismics.docs.core.util;

import com.google.common.io.BaseEncoding;
import com.sismics.util.EnvironmentUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Content-hash duplicate detection (#119): derives the per-document KEYED MAC stored in
 * {@code T_FILE.FIL_CONTENTMAC_C} and drives the whole feature's on/off state.
 *
 * <p><b>External secret, never in the DB.</b> A single master secret {@code Kmaster} is provisioned at
 * DEPLOY time, either directly via the {@code DOCS_DEDUP_MASTER_KEY} environment variable / system property
 * or as a mounted secret FILE whose path is given by {@code DOCS_DEDUP_MASTER_KEY_FILE} (the Docker/K8s
 * secret convention). It is read once, cached, and NEVER stored in {@code T_CONFIG} or any table.</p>
 *
 * <p><b>Key strength (required).</b> Because a DB-read adversary holding a documentId, a stored MAC, and a
 * candidate plaintext could brute-force a WEAK master key offline, the configured value MUST supply at least
 * 256 bits (32 bytes) of key material, given as hex (preferred — generate with {@code openssl rand -hex 32})
 * or base64. A value that is ABSENT, blank, malformed, or decodes to fewer than 32 bytes DEGRADES TO OFF and
 * is treated exactly like an absent key: {@link #isEnabled()} is false, {@link #computeMac} / {@link
 * #newSink} return {@code null}, the column stays NULL, uploads are unchanged, and NO error is raised (a
 * WARN naming the requirement is logged). A too-weak secret is NEVER silently enabled.</p>
 *
 * <p><b>Per-document derivation (v1, no rotation).</b> For a document id {@code d} the document key is
 * {@code Kdoc = HMAC-SHA-256(Kmaster, "teedy-dedup-v1:" || d)} and the stored value is
 * {@code HMAC-SHA-256(Kdoc, plaintextBytes)} as lowercase hex (64 chars). The digest is taken DIRECTLY over
 * the plaintext bytes — it is NOT an HMAC over a prior SHA-256. Keying per document means the same plaintext
 * in two different documents yields DIFFERENT MACs, so a MAC can never link or be an oracle across documents.
 * {@code Kmaster} must be identical and stable across instances; v1 has no key-rotation mechanism (a changed
 * key silently orphans every existing MAC — a documented limitation, not a supported operation).</p>
 */
public final class ContentMacUtil {
    private static final Logger log = LoggerFactory.getLogger(ContentMacUtil.class);

    /** Environment variable / system property holding the master secret directly. */
    static final String KEY_ENV = "DOCS_DEDUP_MASTER_KEY";
    static final String KEY_PROP = "docs.dedup_master_key";

    /** Environment variable / system property holding a PATH to a file containing the master secret. */
    static final String KEY_FILE_ENV = "DOCS_DEDUP_MASTER_KEY_FILE";
    static final String KEY_FILE_PROP = "docs.dedup_master_key_file";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * Minimum key material the master secret must supply: 256 bits (32 bytes). Below this the keyed MAC is
     * brute-forceable offline by a DB-read adversary who holds documentId + stored MAC + a candidate
     * plaintext, so a shorter/weaker secret is REFUSED (feature stays OFF) rather than silently enabled.
     */
    private static final int MIN_KEY_BYTES = 32;

    /** Lowercase-hex matcher for the preferred key encoding (`openssl rand -hex 32`). */
    private static final Pattern HEX = Pattern.compile("[0-9a-f]+");

    /** Domain-separation prefix binding a derived key to this feature + version + a specific document id. */
    private static final String DOC_KEY_PREFIX = "teedy-dedup-v1:";

    /**
     * Cached resolution of the master secret. {@code null} once {@link #resolved} is true means the feature
     * is OFF. {@code volatile} so a lazily-resolved value publishes safely to concurrent upload threads.
     */
    private static volatile byte[] masterKey;
    private static volatile boolean resolved;

    private ContentMacUtil() {
    }

    /**
     * Resolve and cache the master secret at startup (idempotent). Logs whether dedup is ON or OFF once.
     * Safe to call when no secret is configured — that simply logs OFF and leaves the feature disabled.
     */
    public static synchronized void init() {
        ensureResolved();
        log.info("Content-hash duplicate detection (#119) is {}", isEnabled() ? "ENABLED" : "DISABLED (no master secret configured)");
    }

    /**
     * @return true when a non-blank master secret is configured (feature ON); false = degrade-to-off.
     */
    public static boolean isEnabled() {
        ensureResolved();
        return masterKey != null;
    }

    private static void ensureResolved() {
        if (resolved) {
            return;
        }
        synchronized (ContentMacUtil.class) {
            if (resolved) {
                return;
            }
            masterKey = resolveMasterKey();
            resolved = true;
        }
    }

    /**
     * Read + strength-check the master secret from the external channel: the mounted secret FILE takes
     * precedence over the direct value. The value must supply at least {@link #MIN_KEY_BYTES} bytes of key
     * material, encoded as hex (preferred, e.g. {@code openssl rand -hex 32}) or base64. A blank / missing /
     * unreadable / malformed-path file, a value that fails to decode, and a value that decodes to too few
     * bytes ALL resolve to {@code null} (feature OFF) with a WARN — never an exception, and never a
     * silently-enabled-but-weak key. Package-private so it can be exercised directly by tests.
     *
     * @return the validated key material (&gt;= 32 bytes), or {@code null} when unset/weak/malformed
     */
    static byte[] resolveMasterKey() {
        String filePath = EnvironmentUtil.getStringConfig(KEY_FILE_PROP, KEY_FILE_ENV, null);
        if (filePath != null) {
            String fromFile = readSecretFile(filePath);
            return fromFile == null ? null : validateKeyMaterial(fromFile, KEY_FILE_ENV);
        }
        String direct = EnvironmentUtil.getStringConfig(KEY_PROP, KEY_ENV, null);
        if (direct != null && !direct.isBlank()) {
            return validateKeyMaterial(direct.strip(), KEY_ENV);
        }
        return null;
    }

    /**
     * Read the mounted secret file, degrading to {@code null} (OFF) with a WARN on ANY failure — a
     * malformed path ({@link InvalidPathException}), a denied read ({@link SecurityException}), an I/O error,
     * or a missing/blank file — so no key-file misconfiguration can crash startup or an upload.
     */
    private static String readSecretFile(String filePath) {
        try {
            Path path = Paths.get(filePath);
            if (!Files.isReadable(path)) {
                log.warn("{} points at a missing/unreadable secret file ('{}'); duplicate detection stays OFF",
                        KEY_FILE_ENV, filePath);
                return null;
            }
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).strip();
            if (content.isEmpty()) {
                log.warn("{} points at a blank secret file ('{}'); duplicate detection stays OFF", KEY_FILE_ENV, filePath);
                return null;
            }
            return content;
        } catch (InvalidPathException | SecurityException | IOException e) {
            log.warn("Unable to read the dedup master secret file '{}'; duplicate detection stays OFF", filePath, e);
            return null;
        }
    }

    /**
     * Decode and strength-check a configured secret value. Returns the key material only when it decodes
     * (hex preferred, then base64) to at least {@link #MIN_KEY_BYTES} bytes; otherwise DEGRADES TO OFF
     * (returns {@code null}) with a WARN naming the requirement — a too-short / malformed key behaves
     * exactly like an absent one (feature off, MAC null, uploads unchanged). Package-private for tests.
     */
    static byte[] validateKeyMaterial(String raw, String source) {
        byte[] key = decode(raw);
        if (key == null || key.length < MIN_KEY_BYTES) {
            log.warn("{} does not provide a strong enough dedup master secret (need >= {} bytes of hex or "
                    + "base64 key material, e.g. `openssl rand -hex 32`); duplicate detection stays OFF",
                    source, MIN_KEY_BYTES);
            return null;
        }
        return key;
    }

    /** Decode key material as hex (case-insensitive, preferred) then base64; {@code null} if neither works. */
    private static byte[] decode(String raw) {
        String hex = raw.toLowerCase(Locale.ROOT);
        if (hex.length() % 2 == 0 && HEX.matcher(hex).matches()) {
            try {
                return BaseEncoding.base16().lowerCase().decode(hex);
            } catch (IllegalArgumentException ignore) {
                // fall through to base64
            }
        }
        try {
            return Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException ignore) {
            return null;
        }
    }

    /**
     * Compute the stored MAC for a document's plaintext, consuming the whole stream once. Used by the
     * compatibility create path and the async backfill (which decrypts a legacy blob and streams it here).
     *
     * @param documentId Owning document id (never null on this path)
     * @param plaintext Plaintext byte stream (the caller owns closing it)
     * @return lowercase-hex MAC, or {@code null} when the feature is OFF or {@code documentId} is null
     * @throws IOException on a read error
     */
    public static String computeMac(String documentId, InputStream plaintext) throws IOException {
        MacSink sink = newSink(documentId);
        if (sink == null) {
            return null;
        }
        byte[] buffer = new byte[8192];
        int n;
        while ((n = plaintext.read(buffer)) != -1) {
            sink.update(buffer, 0, n);
        }
        return sink.finish();
    }

    /**
     * Open a streaming MAC accumulator so an upload can compute the MAC in the SAME pass that writes the
     * plaintext temp — no separate full read. Returns {@code null} when the feature is OFF or the upload has
     * no document (an orphan upload leaves the MAC null; the backfill hashes it once it is attached).
     *
     * @param documentId Owning document id, or null for an orphan upload
     * @return a sink to feed the plaintext bytes into, or {@code null} to skip (feature off / orphan)
     */
    public static MacSink newSink(String documentId) {
        ensureResolved();
        if (masterKey == null || documentId == null) {
            return null;
        }
        try {
            byte[] docKey = deriveDocKey(masterKey, documentId);
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(docKey, HMAC_ALGORITHM));
            return new MacSink(mac);
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is a mandatory JCE algorithm and a derived key is always a valid HMAC key, so this
            // cannot happen on a supported JVM; surface it rather than silently mis-degrading to off.
            throw new IllegalStateException("Unable to initialise the dedup MAC", e);
        }
    }

    private static byte[] deriveDocKey(byte[] master, String documentId) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(master, HMAC_ALGORITHM));
        return mac.doFinal((DOC_KEY_PREFIX + documentId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A streaming HMAC accumulator over a document's plaintext. Not thread-safe (one per upload).
     */
    public static final class MacSink {
        private final Mac mac;

        private MacSink(Mac mac) {
            this.mac = mac;
        }

        public void update(byte[] bytes, int offset, int length) {
            mac.update(bytes, offset, length);
        }

        public String finish() {
            return BaseEncoding.base16().lowerCase().encode(mac.doFinal());
        }
    }

    // --- test seams (test-only; production resolves once from the external channel) ----------------------

    /**
     * Force the feature ON with an explicit master secret (tests only). Bypasses the external channel so a
     * test can drive the enabled path deterministically without mutating the process environment.
     *
     * @param rawKey Raw secret material (its UTF-8 bytes become {@code Kmaster})
     */
    public static void setMasterKeyForTest(String rawKey) {
        masterKey = rawKey.getBytes(StandardCharsets.UTF_8);
        resolved = true;
    }

    /**
     * Reset the cached resolution so the next access re-reads the external channel (tests only). With no
     * secret configured this restores the degrade-to-off state.
     */
    public static void resetForTest() {
        masterKey = null;
        resolved = false;
    }
}
