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
    protected User authenticate(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer " + API_KEY_PREFIX)) {
            return null;
        }

        String token = header.substring(7); // strip "Bearer "
        String hash = sha256Hex(token);

        ApiKeyDao apiKeyDao = new ApiKeyDao();
        ApiKey apiKey = apiKeyDao.getByKeyHash(hash);
        if (apiKey == null) {
            return null;
        }

        apiKeyDao.updateLastUsedDate(apiKey.getId());
        return new UserDao().getById(apiKey.getUserId());
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
