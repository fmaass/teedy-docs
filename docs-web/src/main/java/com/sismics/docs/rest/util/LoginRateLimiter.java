package com.sismics.docs.rest.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory rate limiter for login attempts.
 * Tracks failed attempts per key (IP or username) with exponential backoff.
 */
public class LoginRateLimiter {
    private static final LoginRateLimiter INSTANCE = new LoginRateLimiter();

    private static final int MAX_ATTEMPTS = Integer.parseInt(
            System.getenv().getOrDefault("DOCS_LOGIN_MAX_ATTEMPTS", "5"));
    private static final long BASE_LOCKOUT_MS = Long.parseLong(
            System.getenv().getOrDefault("DOCS_LOGIN_LOCKOUT_SECONDS", "60")) * 1000L;

    private final ConcurrentMap<String, AttemptInfo> attempts = new ConcurrentHashMap<>();

    public static LoginRateLimiter getInstance() {
        return INSTANCE;
    }

    /**
     * Check if the given key is currently rate-limited.
     *
     * @param key IP address or username
     * @return true if the key should be blocked
     */
    public boolean isBlocked(String key) {
        if (key == null) return false;
        AttemptInfo info = attempts.get(key);
        if (info == null) return false;
        if (info.failCount < MAX_ATTEMPTS) return false;
        long lockoutMs = BASE_LOCKOUT_MS * (1L << Math.min(info.failCount - MAX_ATTEMPTS, 6));
        return System.currentTimeMillis() - info.lastFailTime < lockoutMs;
    }

    /**
     * Record a failed login attempt.
     *
     * @param key IP address or username
     */
    public void recordFailure(String key) {
        if (key == null) return;
        attempts.compute(key, (k, info) -> {
            if (info == null) return new AttemptInfo(1, System.currentTimeMillis());
            return new AttemptInfo(info.failCount + 1, System.currentTimeMillis());
        });
    }

    /**
     * Clear failed attempts after a successful login.
     *
     * @param key IP address or username
     */
    public void recordSuccess(String key) {
        if (key == null) return;
        attempts.remove(key);
    }

    private static class AttemptInfo {
        final int failCount;
        final long lastFailTime;

        AttemptInfo(int failCount, long lastFailTime) {
            this.failCount = failCount;
            this.lastFailTime = lastFailTime;
        }
    }
}
