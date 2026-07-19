package com.sismics.util.csrf;

import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.model.jpa.Config;
import com.sismics.docs.core.util.TransactionBoundary;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;

import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.NewCookie;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

/**
 * Stateless CSRF token derivation and verification.
 *
 * <p>Two independent proofs exist, one per ambient-credential mechanism:</p>
 * <ul>
 *   <li><b>Session (token-cookie) token</b> — {@code HMAC-SHA-256(key = auth-token-id bytes,
 *       data = fixed domain-separation string)}. The auth-token id (an unguessable server-side session
 *       identifier already held only in the HttpOnly {@code auth_token} cookie) IS the secret key, so no
 *       server key and no schema change are needed. The token is recomputed server-side from the
 *       presented session and compared to the submitted header in constant time. A cross-site page
 *       cannot read the target-origin {@code csrf_token} cookie to copy it into the header, and cannot
 *       recompute it (it never sees the auth-token id).</li>
 *   <li><b>Trusted-header ("proxy") token</b> — a structured, self-describing token
 *       {@code <version>.<principalId>.<nonce>.<expiryMs>.<mac>} MAC'd with the dedicated server secret
 *       {@link ConfigType#CSRF_PROXY_KEY} ({@code K_csrf_proxy}). Proxy-authenticated sessions carry no
 *       per-session auth-token id, so they need a server-keyed token instead. The MAC binds the token to
 *       the authenticated principal and an expiry.</li>
 * </ul>
 *
 * <p>All comparisons are constant-time. No token or secret value is ever logged.</p>
 */
public final class CsrfTokenUtil {
    /** Non-HttpOnly cookie carrying the session CSRF token (host-only, Secure, SameSite=Lax, Path=/). */
    public static final String SESSION_COOKIE_NAME = "csrf_token";

    /** Host-only ({@code __Host-} prefixed) cookie carrying the trusted-header proxy CSRF token. */
    public static final String PROXY_COOKIE_NAME = "__Host-csrf_proxy";

    /** Request header carrying the submitted session CSRF token (the ONLY accepted source — never a form/query/cookie). */
    public static final String HEADER_NAME = "X-Csrf-Token";

    /** Request header carrying the submitted trusted-header (proxy) CSRF token. */
    public static final String PROXY_HEADER_NAME = "X-Csrf-Proxy";

    /** Domain-separation string for the session token (bound into the HMAC message). */
    private static final byte[] SESSION_DOMAIN = "teedy.csrf.session.v1".getBytes(StandardCharsets.UTF_8);

    /** Domain-separation label for the proxy token MAC. */
    private static final String PROXY_DOMAIN = "teedy.csrf.proxy";

    /** Current proxy-token format/key version. */
    private static final String PROXY_VERSION = "v1";

    /** Proxy token lifetime (12h) — a fresh one is minted on every proxy-authenticated request anyway. */
    private static final long PROXY_TTL_MS = 12L * 3600L * 1000L;

    /** Stored-value prefix for the {@code CSRF_PROXY_KEY} config row (leaves room for key rotation). */
    private static final String PROXY_KEY_STORED_PREFIX = "v1:";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();

    private CsrfTokenUtil() {
    }

    // ----------------------------------------------------------------------------------------------
    // Session (token-cookie) token
    // ----------------------------------------------------------------------------------------------

    /**
     * Computes the session CSRF token for a given auth-token id.
     *
     * @param authTokenId the validated auth-token id (the HMAC key)
     * @return the base64url-encoded token, or null if the id is null/blank
     */
    public static String computeSessionToken(String authTokenId) {
        if (authTokenId == null || authTokenId.isEmpty()) {
            return null;
        }
        byte[] mac = hmacSha256(authTokenId.getBytes(StandardCharsets.UTF_8), SESSION_DOMAIN);
        return B64URL.encodeToString(mac);
    }

    /**
     * Builds the non-HttpOnly, host-only (no Domain), Secure, SameSite=Lax {@code csrf_token} cookie for a
     * freshly-established session, carrying the derived session token. Additive to the existing auth
     * cookie; issued at each auth-cookie site so the SPA has the token immediately after login/rotation.
     *
     * @param authTokenId the freshly minted auth-token id
     * @return the session CSRF cookie
     */
    public static NewCookie buildSessionCookie(String authTokenId) {
        return new NewCookie.Builder(SESSION_COOKIE_NAME)
                .value(computeSessionToken(authTokenId))
                .path("/")
                .secure(true)
                .httpOnly(false)
                .sameSite(NewCookie.SameSite.LAX)
                .build();
    }

    /**
     * Builds an expired {@code csrf_token} cookie (same flags) so logout clears it alongside the session.
     *
     * @return the cleared session CSRF cookie
     */
    public static NewCookie buildClearedSessionCookie() {
        return new NewCookie.Builder(SESSION_COOKIE_NAME)
                .value("")
                .path("/")
                .maxAge(0)
                .expiry(new Date(1))
                .secure(true)
                .httpOnly(false)
                .sameSite(NewCookie.SameSite.LAX)
                .build();
    }

    // ----------------------------------------------------------------------------------------------
    // Trusted-header (proxy) token
    // ----------------------------------------------------------------------------------------------

    /**
     * Issues a fresh proxy CSRF token bound to the authenticated principal.
     *
     * @param principalId the authenticated internal principal id
     * @param key the {@code K_csrf_proxy} secret
     * @param nowMs current time in millis
     * @return the structured token string
     */
    public static String issueProxyToken(String principalId, byte[] key, long nowMs) {
        String nonce = B64URL.encodeToString(randomBytes(16));
        long expiry = nowMs + PROXY_TTL_MS;
        String payload = proxyPayload(principalId, nonce, expiry);
        String mac = B64URL.encodeToString(proxyMac(key, payload));
        return payload + "." + mac;
    }

    /**
     * Verifies a submitted proxy CSRF token against the expected principal, checking the MAC and expiry
     * in constant time.
     *
     * @param token the submitted token (may be null)
     * @param expectedPrincipalId the currently-authenticated principal id
     * @param key the {@code K_csrf_proxy} secret
     * @param nowMs current time in millis
     * @return true if the token is well-formed, unexpired, MAC-valid, and bound to the expected principal
     */
    public static boolean verifyProxyToken(String token, String expectedPrincipalId, byte[] key, long nowMs) {
        if (token == null || expectedPrincipalId == null) {
            return false;
        }
        // <version>.<principalId>.<nonce>.<expiryMs>.<mac>
        String[] parts = token.split("\\.");
        if (parts.length != 5) {
            return false;
        }
        String version = parts[0];
        String principalIdB64 = parts[1];
        String nonce = parts[2];
        String expiryStr = parts[3];
        String macB64 = parts[4];

        if (!PROXY_VERSION.equals(version)) {
            return false;
        }
        String payload = version + "." + principalIdB64 + "." + nonce + "." + expiryStr;
        String expectedMac = B64URL.encodeToString(proxyMac(key, payload));
        if (!constantTimeEquals(expectedMac, macB64)) {
            return false;
        }

        long expiry;
        try {
            expiry = Long.parseLong(expiryStr);
        } catch (NumberFormatException e) {
            return false;
        }
        if (nowMs >= expiry) {
            return false;
        }

        String presentedPrincipalId = new String(B64URL_DEC.decode(principalIdB64), StandardCharsets.UTF_8);
        return constantTimeEquals(presentedPrincipalId, expectedPrincipalId);
    }

    private static String proxyPayload(String principalId, String nonce, long expiry) {
        String principalIdB64 = B64URL.encodeToString(principalId.getBytes(StandardCharsets.UTF_8));
        return PROXY_VERSION + "." + principalIdB64 + "." + nonce + "." + expiry;
    }

    /**
     * Computes the proxy-token MAC, binding the fixed domain-separation label {@link #PROXY_DOMAIN} into
     * the MAC'd data (not just declared): a token minted with the same key for a DIFFERENT purpose cannot
     * verify as a proxy CSRF token. The label is a constant, so it is not carried in the token string.
     */
    private static byte[] proxyMac(byte[] key, String payload) {
        return hmacSha256(key, (PROXY_DOMAIN + "." + payload).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the {@code K_csrf_proxy} secret from {@link ConfigType#CSRF_PROXY_KEY}, seeding a fresh
     * random value on first use — IDEMPOTENTLY, DURABLY, and independently of the ambient request
     * transaction. First use must be race-safe (two concurrent first-use requests must not 500 on a
     * duplicate-key insert) and must persist even if the triggering request rolls back (so a later
     * enforced-mode rejection cannot lose the just-signed key). The seed therefore commits in its OWN
     * short transaction frame (the same independent-write technique as the credential-conflict audit,
     * using the existing {@code TransactionBoundary} fresh-frame API — no A1 class modified); on a
     * unique-constraint conflict the row is simply re-read.
     *
     * @return the raw secret key bytes
     */
    public static byte[] getOrCreateProxyKey() {
        // Fast path: read within the ambient request transaction.
        byte[] ambient = readProxyKey(ThreadLocalContext.get().getEntityManager());
        if (ambient != null) {
            return ambient;
        }
        // Seed durably in an independent frame; on a race the insert conflicts and we re-read.
        byte[] seeded = trySeedProxyKeyDurably();
        if (seeded != null) {
            return seeded;
        }
        byte[] reread = readProxyKeyInNewFrame();
        if (reread != null) {
            return reread;
        }
        throw new IllegalStateException("Unable to seed or read the CSRF proxy key");
    }

    private static byte[] readProxyKey(EntityManager em) {
        if (em == null) {
            return null;
        }
        Config config = em.find(Config.class, ConfigType.CSRF_PROXY_KEY);
        if (config == null || config.getValue() == null || !config.getValue().startsWith(PROXY_KEY_STORED_PREFIX)) {
            return null;
        }
        return tryDecodeStoredKey(config.getValue());
    }

    /**
     * Reads the key in its own committed transaction frame (independent of the request), restoring the
     * request's entity manager afterwards.
     */
    private static byte[] readProxyKeyInNewFrame() {
        ThreadLocalContext context = ThreadLocalContext.get();
        EntityManager requestEm = context.peekEntityManager();
        EntityManager freshEm = EMF.get().createEntityManager();
        try {
            return TransactionBoundary.finalizeOwnedInNewFrame(freshEm, requestEm,
                    CsrfTokenUtil::readProxyKey);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Attempts to seed the key in its own committed transaction frame. Returns the key on a successful
     * insert (or when the frame observed an existing valid row), or {@code null} when the commit failed —
     * typically a unique-constraint conflict from a concurrent first-use request that seeded first, in
     * which case the caller re-reads the now-committed value. Never propagates (never 500s the request).
     */
    private static byte[] trySeedProxyKeyDurably() {
        ThreadLocalContext context = ThreadLocalContext.get();
        EntityManager requestEm = context.peekEntityManager();
        EntityManager freshEm = EMF.get().createEntityManager();
        try {
            return TransactionBoundary.finalizeOwnedInNewFrame(freshEm, requestEm, em -> {
                byte[] existing = readProxyKey(em);
                if (existing != null) {
                    return existing;
                }
                byte[] fresh = randomBytes(32);
                Config config = new Config();
                config.setId(ConfigType.CSRF_PROXY_KEY);
                config.setValue(PROXY_KEY_STORED_PREFIX + B64URL.encodeToString(fresh));
                em.persist(config);
                // Force the INSERT now so a unique-constraint race surfaces inside this frame (which then
                // rolls back and propagates), rather than at commit outside our control.
                em.flush();
                return fresh;
            });
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static byte[] tryDecodeStoredKey(String value) {
        try {
            return B64URL_DEC.decode(value.substring(PROXY_KEY_STORED_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Primitives
    // ----------------------------------------------------------------------------------------------

    /**
     * Constant-time string comparison (length-safe): compares the UTF-8 bytes with
     * {@link MessageDigest#isEqual}. A null argument never matches.
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA-256 not available", e);
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        SECURE_RANDOM.nextBytes(b);
        return b;
    }
}
