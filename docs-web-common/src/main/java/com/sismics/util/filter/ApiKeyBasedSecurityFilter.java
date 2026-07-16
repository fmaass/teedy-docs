package com.sismics.util.filter;

import com.sismics.docs.core.dao.ApiKeyDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.ApiKey;
import com.sismics.docs.core.model.jpa.User;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Security filter that authenticates requests using API keys.
 * Expects Authorization header: Bearer tdapi_<hex>
 * Stores only the SHA-256 hash; looks up the key by hash.
 */
public class ApiKeyBasedSecurityFilter extends SecurityFilter {
    private static final String API_KEY_PREFIX = "tdapi_";

    @Override
    protected String getMechanism() {
        return MECHANISM_API_KEY;
    }

    @Override
    protected AuthAttempt attempt(HttpServletRequest request) {
        String token = extractApiKey(request.getHeader("Authorization"));
        if (token == null) {
            // No API-key credential presented on this request.
            return AuthAttempt.absent();
        }

        String hash = sha256Hex(token);

        ApiKeyDao apiKeyDao = new ApiKeyDao();
        ApiKey apiKey = apiKeyDao.getByKeyHash(hash);
        if (apiKey == null) {
            // A tdapi_*-shaped Authorization header that resolves to no key is an INVALID explicit
            // credential: reject it (401) rather than silently falling through to another scheme.
            return AuthAttempt.reject();
        }

        // Load the user before the last-used bookkeeping update. A disabled/deleted account is NOT a
        // hard rejection here (an operator disabling an account must not turn every stale API-key call
        // into a 401 the client cannot recover from); it falls through to anonymous, and access is
        // denied downstream. Crucially this also means a disabled API-key request never bumps
        // lastUsedDate (the update below only runs for an eligible user).
        User user = new UserDao().getById(apiKey.getUserId());
        if (!isEligible(user)) {
            return AuthAttempt.ignore();
        }

        // Credential-epoch check: a key stamped at an epoch the user has since advanced past is dead.
        // Checked BEFORE the last-used bookkeeping so a revoked key never touches the row; falls through
        // to anonymous (denied downstream) rather than a hard 401, matching the disabled-account handling.
        if (!epochMatches(apiKey.getCredentialEpoch(), user.getCredentialEpoch())) {
            return AuthAttempt.ignore();
        }

        apiKeyDao.updateLastUsedDate(apiKey.getId());
        return AuthAttempt.authenticated(user, null);
    }

    /**
     * Extracts a {@code tdapi_*} API key from an Authorization header, or null when the header carries no
     * API-key credential. The HTTP auth-scheme is case-insensitive (RFC 7235), so {@code Bearer},
     * {@code bearer}, {@code BEARER} etc. all match; the {@code tdapi_} key prefix is matched with its
     * actual casing. This ensures a malformed/invalid {@code tdapi_*} key in ANY scheme casing is treated
     * as a (subsequently rejected) explicit credential rather than silently falling through to cookie auth.
     *
     * @param header the raw Authorization header value (may be null)
     * @return the raw API key, or null if this is not a tdapi_* bearer credential
     */
    static String extractApiKey(String header) {
        if (header == null) {
            return null;
        }
        String trimmed = header.trim();
        int sp = trimmed.indexOf(' ');
        if (sp < 0) {
            return null;
        }
        String scheme = trimmed.substring(0, sp);
        String credential = trimmed.substring(sp + 1).trim();
        if (!scheme.equalsIgnoreCase("Bearer") || !credential.startsWith(API_KEY_PREFIX)) {
            return null;
        }
        return credential;
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
