package com.sismics.util.csrf;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Unit tests for the stateless CSRF token derivation and verification (no DB needed).
 */
public class TestCsrfTokenUtil {

    @Test
    public void sessionTokenIsDeterministicAndKeyed() {
        String a1 = CsrfTokenUtil.computeSessionToken("auth-token-id-1");
        String a2 = CsrfTokenUtil.computeSessionToken("auth-token-id-1");
        String b = CsrfTokenUtil.computeSessionToken("auth-token-id-2");
        Assertions.assertNotNull(a1);
        Assertions.assertEquals(a1, a2, "same auth-token id must derive the same CSRF token");
        Assertions.assertNotEquals(a1, b, "different auth-token ids must derive different CSRF tokens");
        Assertions.assertNull(CsrfTokenUtil.computeSessionToken(null));
        Assertions.assertNull(CsrfTokenUtil.computeSessionToken(""));
    }

    @Test
    public void constantTimeEqualsSemantics() {
        Assertions.assertTrue(CsrfTokenUtil.constantTimeEquals("abc", "abc"));
        Assertions.assertFalse(CsrfTokenUtil.constantTimeEquals("abc", "abd"));
        Assertions.assertFalse(CsrfTokenUtil.constantTimeEquals("abc", "ab"));
        Assertions.assertFalse(CsrfTokenUtil.constantTimeEquals(null, "abc"));
        Assertions.assertFalse(CsrfTokenUtil.constantTimeEquals("abc", null));
    }

    @Test
    public void proxyTokenRoundTrips() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        long now = 1_000_000_000L;
        String token = CsrfTokenUtil.issueProxyToken("principal-1", key, now);
        Assertions.assertTrue(CsrfTokenUtil.verifyProxyToken(token, "principal-1", key, now + 1000));
    }

    @Test
    public void proxyTokenRejectsWrongPrincipal() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        long now = 1_000_000_000L;
        String token = CsrfTokenUtil.issueProxyToken("principal-1", key, now);
        Assertions.assertFalse(CsrfTokenUtil.verifyProxyToken(token, "principal-2", key, now + 1000));
    }

    @Test
    public void proxyTokenRejectsTamperedMac() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        long now = 1_000_000_000L;
        String token = CsrfTokenUtil.issueProxyToken("principal-1", key, now);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");
        Assertions.assertFalse(CsrfTokenUtil.verifyProxyToken(tampered, "principal-1", key, now + 1000));
    }

    @Test
    public void proxyTokenRejectsWrongKey() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] otherKey = "ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8);
        long now = 1_000_000_000L;
        String token = CsrfTokenUtil.issueProxyToken("principal-1", key, now);
        Assertions.assertFalse(CsrfTokenUtil.verifyProxyToken(token, "principal-1", otherKey, now + 1000));
    }

    @Test
    public void proxyTokenRejectsExpired() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        long now = 1_000_000_000L;
        String token = CsrfTokenUtil.issueProxyToken("principal-1", key, now);
        // 12h + 1ms later
        Assertions.assertFalse(CsrfTokenUtil.verifyProxyToken(token, "principal-1", key,
                now + 12L * 3600L * 1000L + 1L));
    }

    @Test
    public void proxyTokenRejectsPreDomainSeparationMac() throws Exception {
        // Forge a token whose MAC is computed over the payload WITHOUT the domain-separation label — the
        // old, insufficient scheme. The current verifier binds PROXY_DOMAIN into the MAC (BLOCKING 5), so
        // a correctly-keyed but domain-less token minted for another purpose must NOT verify.
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        long now = 1_000_000_000L;
        String principalId = "principal-1";
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String principalB64 = b64.encodeToString(principalId.getBytes(StandardCharsets.UTF_8));
        String nonce = b64.encodeToString("noncenoncenonce!".getBytes(StandardCharsets.UTF_8));
        long expiry = now + 3_600_000L;
        String payload = "v1." + principalB64 + "." + nonce + "." + expiry;

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        String preDomainMac = b64.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        String forged = payload + "." + preDomainMac;

        Assertions.assertFalse(CsrfTokenUtil.verifyProxyToken(forged, principalId, key, now + 1000),
                "a proxy token MAC'd without domain separation must be rejected");

        // Control: a properly-issued token with the same key/principal DOES verify.
        String valid = CsrfTokenUtil.issueProxyToken(principalId, key, now);
        Assertions.assertTrue(CsrfTokenUtil.verifyProxyToken(valid, principalId, key, now + 1000));
    }

    @Test
    public void proxyTokenRejectsMalformed() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        long now = 1_000_000_000L;
        Assertions.assertFalse(CsrfTokenUtil.verifyProxyToken(null, "p", key, now));
        Assertions.assertFalse(CsrfTokenUtil.verifyProxyToken("not.enough.parts", "p", key, now));
        Assertions.assertFalse(CsrfTokenUtil.verifyProxyToken("a.b.c.d.e", "p", key, now));
    }
}
