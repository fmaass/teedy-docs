package com.sismics.docs.rest.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.Strings;
import com.google.common.net.InetAddresses;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * In-memory, multi-dimensional throttle for authentication and password-recovery abuse.
 *
 * <p>Replaces the earlier unbounded single-map limiter. State is deliberately non-transactional (it is
 * abuse telemetry, not domain data) and survives a request rollback. It is bounded and self-healing:</p>
 *
 * <ul>
 *   <li><b>Independent per-dimension budgets.</b> Separate bounded caches track failed-attempt counters
 *       per account, per network, and per (account, network) pair, so churn in one dimension cannot evict
 *       another dimension's state.</li>
 *   <li><b>Active-lockout retention.</b> A separate cache holds accounts that have crossed their
 *       lockout threshold, keyed by account hash and expiring exactly at the lockout deadline. The general
 *       account counter cache may evict a not-yet-locked partial count freely (that only forgives
 *       pre-lockout attempts), but a LOCKED account is retained in this cache and cannot be evicted before
 *       its deadline even under high-cardinality account churn. The blocked check consults it.</li>
 *   <li><b>Fixed-length hashed keys.</b> Every key is a keyed HMAC-SHA-256 of the canonical account /
 *       network, so raw usernames, e-mails, and oversized header values are never stored.</li>
 *   <li><b>Atomic admission-and-update.</b> Counter updates run through Caffeine's atomic per-key compute,
 *       never read-compare-increment.</li>
 *   <li><b>Fixed, capped penalty deadlines.</b> Lockout deadlines are computed once at write time and are
 *       never extended by a read; there is no expire-after-access an attacker could use to keep a victim
 *       locked by touching the key.</li>
 *   <li><b>Global bulkhead.</b> A token bucket per work class (login vs recovery) caps the rate of the
 *       expensive password-hash / mail work admitted past the cheap per-source checks. It sheds excess load
 *       with a bounded Retry-After but self-heals as tokens refill — never a sticky site-wide lockout — and
 *       is never consulted by already-authenticated requests.</li>
 * </ul>
 */
public class LoginThrottleStore {
    /**
     * HMAC key length in bytes.
     */
    private static final int HMAC_KEY_BYTES = 32;

    /**
     * Absolute cap on any lockout, so a Retry-After is always bounded (15 minutes).
     */
    private static final long MAX_LOCKOUT_MS = 15L * 60 * 1000;

    private static final LoginThrottleStore INSTANCE = fromEnvironment();

    private final int maxAttempts;
    private final long baseLockoutMs;
    private final long maxLockoutMs;

    private final Cache<String, Attempt> accountCache;
    private final Cache<String, Attempt> networkCache;
    private final Cache<String, Attempt> pairCache;

    /**
     * Eviction-resistant hold for accounts past their lockout threshold: account-hash to lockout deadline
     * (epoch ms). TTL-only (expires at the lockout duration) with NO size cap, so a genuine live lock can
     * never be evicted early even when the global admission rate allows a very large number of concurrent
     * locks; a stale entry is dropped solely by its deadline passing.
     */
    private final Cache<String, Long> activeLockoutCache;

    private final TokenBucket loginBucket;
    private final TokenBucket recoveryBucket;

    private final byte[] hmacKey;
    private final LongSupplier nanoClock;

    /**
     * @return the process-wide singleton
     */
    public static LoginThrottleStore getInstance() {
        return INSTANCE;
    }

    private static LoginThrottleStore fromEnvironment() {
        int maxAttempts = readIntEnv("DOCS_LOGIN_MAX_ATTEMPTS", 5, 1, 10_000);
        long baseLockoutMs = readIntEnv("DOCS_LOGIN_LOCKOUT_SECONDS", 60, 1, 86_400) * 1000L;
        // Global bulkheads: separately-high thresholds so normal traffic is never shed; operator-overridable.
        int loginRate = readIntEnv("DOCS_LOGIN_GLOBAL_MAX_PER_SEC", 50, 1, 100_000);
        int recoveryRate = readIntEnv("DOCS_RECOVERY_GLOBAL_MAX_PER_SEC", 5, 1, 100_000);
        return new LoginThrottleStore(maxAttempts, baseLockoutMs, MAX_LOCKOUT_MS,
                loginRate * 2, loginRate,
                recoveryRate * 2, recoveryRate,
                100_000, System::nanoTime);
    }

    /**
     * Full constructor. Package-private for tests, which inject tiny bulkhead budgets and a controllable
     * clock.
     *
     * @param maxAttempts failed attempts before a dimension locks
     * @param baseLockoutMs base lockout in ms (doubled per over-threshold attempt, capped)
     * @param maxLockoutMs absolute lockout cap in ms
     * @param loginBucketCapacity login bulkhead token-bucket capacity
     * @param loginRefillPerSec login bulkhead refill rate (tokens/sec)
     * @param recoveryBucketCapacity recovery bulkhead capacity
     * @param recoveryRefillPerSec recovery bulkhead refill rate (tokens/sec)
     * @param counterMaxSize maximum entries per attempt-counter cache (account / network; pair gets 2x)
     * @param nanoClock monotonic clock supplier (nanoseconds)
     */
    LoginThrottleStore(int maxAttempts, long baseLockoutMs, long maxLockoutMs,
                       int loginBucketCapacity, double loginRefillPerSec,
                       int recoveryBucketCapacity, double recoveryRefillPerSec,
                       long counterMaxSize, LongSupplier nanoClock) {
        this.maxAttempts = maxAttempts;
        this.baseLockoutMs = baseLockoutMs;
        this.maxLockoutMs = maxLockoutMs;
        this.nanoClock = nanoClock;

        // Counter caches expire well after the longest lockout so a genuine deadline is never dropped early;
        // they are size-bounded so an unbounded key space cannot exhaust memory.
        long counterTtlMs = Math.max(2 * maxLockoutMs, 30L * 60 * 1000);
        this.accountCache = counterCache(counterTtlMs, counterMaxSize);
        this.networkCache = counterCache(counterTtlMs, counterMaxSize);
        this.pairCache = counterCache(counterTtlMs, counterMaxSize * 2);
        // TTL-only: a locked account is held until its deadline passes and is never subject to size-based
        // eviction, so a live lockout cannot be dropped early under a burst of concurrent locks.
        this.activeLockoutCache = Caffeine.newBuilder()
                .expireAfterWrite(java.time.Duration.ofMillis(maxLockoutMs))
                .build();

        this.loginBucket = new TokenBucket(loginBucketCapacity, loginRefillPerSec, nanoClock);
        this.recoveryBucket = new TokenBucket(recoveryBucketCapacity, recoveryRefillPerSec, nanoClock);

        this.hmacKey = new byte[HMAC_KEY_BYTES];
        new SecureRandom().nextBytes(this.hmacKey);
    }

    private static Cache<String, Attempt> counterCache(long ttlMs, long maxSize) {
        return Caffeine.newBuilder()
                .expireAfterWrite(java.time.Duration.ofMillis(ttlMs))
                .maximumSize(maxSize)
                .build();
    }

    // ---------------------------------------------------------------------------------------------------
    // Login path
    // ---------------------------------------------------------------------------------------------------

    /**
     * Checks whether a login for the given account / network is currently locked out. Consults the
     * eviction-resistant active-lockout hold for the account plus the network and pair counters. Must be
     * called BEFORE authentication so an existing and a nonexistent account are indistinguishable.
     *
     * @param account account identifier (username), may be null
     * @param network client network address, may be null
     * @return the throttle decision (bounded Retry-After when blocked)
     */
    public ThrottleDecision checkLoginBlocked(String account, String network) {
        long now = System.currentTimeMillis();
        long retryMs = 0;

        String accountHash = account == null ? null : hash("login:acct", canonicalAccount(account));
        if (accountHash != null) {
            Long lockedUntil = activeLockoutCache.getIfPresent(accountHash);
            if (lockedUntil != null) {
                retryMs = Math.max(retryMs, lockedUntil - now);
            }
        }
        retryMs = Math.max(retryMs, remainingLockMs(networkCache, networkHash(network), now));
        retryMs = Math.max(retryMs, remainingLockMs(pairCache, pairHash("login", account, network), now));

        if (retryMs > 0) {
            return new ThrottleDecision(true, boundedRetryAfterSeconds(retryMs));
        }
        return ThrottleDecision.ADMITTED;
    }

    /**
     * Tries to admit one unit of expensive login work (a password hash) through the global bulkhead.
     *
     * @return true if admitted; false when the bulkhead is shedding load
     */
    public boolean tryAdmitLoginWork() {
        return loginBucket.tryAcquire();
    }

    /**
     * @return bounded Retry-After (seconds) advertised when the login bulkhead sheds a request
     */
    public long loginWorkRetryAfterSeconds() {
        return boundedRetryAfterSeconds(loginBucket.millisUntilToken());
    }

    /**
     * Records a failed login attempt against every dimension, arming a lockout once a dimension crosses the
     * threshold. An account that crosses the threshold is also pinned in the active-lockout hold.
     */
    public void recordLoginFailure(String account, String network) {
        long now = System.currentTimeMillis();
        if (account != null) {
            String accountHash = hash("login:acct", canonicalAccount(account));
            Attempt a = bumpFailure(accountCache, accountHash, now);
            if (a.lockedUntilMs > now) {
                activeLockoutCache.put(accountHash, a.lockedUntilMs);
            }
        }
        String networkHash = networkHash(network);
        if (networkHash != null) {
            bumpFailure(networkCache, networkHash, now);
        }
        if (account != null && network != null) {
            bumpFailure(pairCache, pairHash("login", account, network), now);
        }
    }

    /**
     * Clears the login throttle state for an account / network after a fully successful authentication.
     */
    public void recordLoginSuccess(String account, String network) {
        if (account != null) {
            String accountHash = hash("login:acct", canonicalAccount(account));
            accountCache.invalidate(accountHash);
            activeLockoutCache.invalidate(accountHash);
        }
        String networkHash = networkHash(network);
        if (networkHash != null) {
            networkCache.invalidate(networkHash);
        }
        if (account != null && network != null) {
            pairCache.invalidate(pairHash("login", account, network));
        }
    }

    /**
     * Atomically registers one abuse-throttled attempt and reports whether it is admitted, for a path that
     * must NOT consume the login bcrypt bulkhead (TOTP activation). The per-account decision AND its counter
     * increment happen inside a single atomic compute, so a concurrent burst cannot slip past the limit the
     * way the separate {@link #checkLoginBlocked} + {@link #recordLoginFailure} pair can — their gap lets N
     * callers all read "not blocked" before any increment lands. Across N concurrent callers exactly the
     * first {@code maxAttempts} are admitted (each incrementing the account counter); once the counter
     * crosses the threshold the account is locked and the rest are blocked. The attempt is charged up front
     * like a failure, so a caller that then verifies successfully MUST call {@link #recordLoginSuccess} to
     * credit it back. The account's eviction-resistant lockout hold is AUTHORITATIVE and consulted first, so
     * a live lockout blocks even when the evictable per-account counter was size-evicted under churn; a live
     * network/pair lockout also blocks read-only, never extending its deadline. Network/pair are bumped only
     * for an admitted attempt (a blocked attempt never re-arms a lockout).
     *
     * @param account account identifier (username), may be null
     * @param network client network address, may be null
     * @return the throttle decision (bounded Retry-After when blocked)
     */
    public ThrottleDecision admitAttempt(String account, String network) {
        long now = System.currentTimeMillis();

        // The eviction-resistant hold is authoritative for the account: a live lockout must block even when
        // the evictable per-account counter was size-evicted under high-cardinality churn — otherwise the
        // atomic compute below would see a null (evicted) counter and admit a fresh set of guesses before the
        // real deadline. Re-seed the counter from the hold (only if absent) so subsequent computes stay
        // consistent with it.
        String accountHash = account == null ? null : hash("login:acct", canonicalAccount(account));
        if (accountHash != null) {
            Long heldUntil = activeLockoutCache.getIfPresent(accountHash);
            if (heldUntil != null && heldUntil > now) {
                accountCache.asMap().putIfAbsent(accountHash, new Attempt(maxAttempts, heldUntil));
                return new ThrottleDecision(true, boundedRetryAfterSeconds(heldUntil - now));
            }
        }

        // A live network/pair lockout blocks read-only, without charging the account or extending any
        // deadline (so a locked source cannot keep itself locked by retrying).
        long secondaryRetryMs = Math.max(remainingLockMs(networkCache, networkHash(network), now),
                remainingLockMs(pairCache, pairHash("login", account, network), now));
        if (secondaryRetryMs > 0) {
            return new ThrottleDecision(true, boundedRetryAfterSeconds(secondaryRetryMs));
        }

        // Per-account gate: the decision and the counter increment happen in one atomic compute, closing the
        // check-then-count race. The lockedUntil branch also catches a lock a concurrent caller armed after
        // the hold read above.
        boolean[] admitted = { true };
        long[] blockedRetryMs = { 0 };
        if (accountHash != null) {
            Attempt after = accountCache.asMap().compute(accountHash, (k, existing) -> {
                int count = existing == null ? 0 : existing.failCount;
                long lockedUntil = existing == null ? 0 : existing.lockedUntilMs;
                if (lockedUntil > now) {
                    // Live lockout: block, and DO NOT extend the deadline (fixed at write time).
                    admitted[0] = false;
                    return existing;
                }
                int newCount = saturatingIncrement(count);
                long newLockedUntil = newCount >= maxAttempts ? now + lockoutMs(newCount) : 0;
                return new Attempt(newCount, newLockedUntil);
            });
            if (!admitted[0]) {
                blockedRetryMs[0] = after == null ? 0 : Math.max(0, after.lockedUntilMs - now);
            } else if (after != null && after.lockedUntilMs > now) {
                // This admitted attempt armed the lock: pin it in the eviction-resistant hold so a later
                // blocked check (here or on the shared login path) still observes it under churn.
                activeLockoutCache.put(accountHash, after.lockedUntilMs);
            }
        }
        if (!admitted[0]) {
            return new ThrottleDecision(true, boundedRetryAfterSeconds(blockedRetryMs[0]));
        }

        // Admitted: charge the coarse network/pair telemetry for this attempt (credited back with the
        // account counter on a successful verification via recordLoginSuccess).
        String networkHash = networkHash(network);
        if (networkHash != null) {
            bumpFailure(networkCache, networkHash, now);
        }
        if (account != null && network != null) {
            bumpFailure(pairCache, pairHash("login", account, network), now);
        }
        return ThrottleDecision.ADMITTED;
    }

    // ---------------------------------------------------------------------------------------------------
    // Recovery path (password_lost)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Records a password-recovery attempt and reports whether the recovery side-effects (token creation +
     * mail) may proceed. Never signals the limit to the caller's HTTP contract: the endpoint always returns
     * a generic OK and merely suppresses the side-effects when this returns false.
     *
     * @param account account identifier, may be null
     * @param network client network address, may be null
     * @return true to proceed with token + mail; false to silently suppress them
     */
    public boolean tryRecovery(String account, String network) {
        long now = System.currentTimeMillis();

        // Cheap per-source suppression FIRST — before touching the shared global bulkhead. Only a request
        // that passes the per-account and per-network checks is allowed to consume a global token below.
        // A source that is already locally limited must NOT spend a global token: otherwise one
        // continuously-limited attacker would drain the shared recovery bucket and suppress password
        // recovery for every other account (a denial-of-service against all users).
        boolean localBlocked = false;
        if (account != null) {
            Attempt a = bumpFailure(accountCache, hash("recovery:acct", canonicalAccount(account)), now);
            localBlocked |= a.failCount >= maxAttempts;
        }
        String networkHash = network == null ? null : hash("recovery:net", networkKey(network));
        if (networkHash != null) {
            Attempt n = bumpFailure(networkCache, networkHash, now);
            localBlocked |= n.failCount >= maxAttempts;
        }
        if (localBlocked) {
            return false;
        }

        // Passed the local checks: reserve exactly one global bulkhead token for the recovery work
        // (token creation + mail) that is actually about to run.
        return recoveryBucket.tryAcquire();
    }

    // ---------------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------------

    private Attempt bumpFailure(Cache<String, Attempt> cache, String key, long now) {
        return cache.asMap().compute(key, (k, existing) -> {
            int count = existing == null ? 1 : saturatingIncrement(existing.failCount);
            long lockedUntil = 0;
            if (count >= maxAttempts) {
                lockedUntil = now + lockoutMs(count);
            }
            return new Attempt(count, lockedUntil);
        });
    }

    private long remainingLockMs(Cache<String, Attempt> cache, String key, long now) {
        if (key == null) {
            return 0;
        }
        Attempt a = cache.getIfPresent(key);
        if (a == null) {
            return 0;
        }
        return Math.max(0, a.lockedUntilMs - now);
    }

    private long lockoutMs(int count) {
        int over = Math.min(count - maxAttempts, 6);
        long lockout = baseLockoutMs * (1L << over);
        return Math.min(lockout, maxLockoutMs);
    }

    private static int saturatingIncrement(int value) {
        return value == Integer.MAX_VALUE ? value : value + 1;
    }

    private long boundedRetryAfterSeconds(long remainingMs) {
        long capped = Math.min(Math.max(remainingMs, 0), maxLockoutMs);
        return (capped + 999) / 1000;
    }

    private String networkHash(String network) {
        return network == null ? null : hash("login:net", networkKey(network));
    }

    private String pairHash(String purpose, String account, String network) {
        if (account == null || network == null) {
            return null;
        }
        return hash(purpose + ":pair", canonicalAccount(account) + "|" + networkKey(network));
    }

    private static String canonicalAccount(String account) {
        return account.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Canonical network key: the /32 host for IPv4, the /64 prefix for IPv6. Unparseable input is used
     * verbatim (still a stable key, never a bypass).
     */
    static String networkKey(String ip) {
        if (Strings.isNullOrEmpty(ip)) {
            return "";
        }
        InetAddress addr;
        try {
            addr = InetAddresses.forString(ip);
        } catch (IllegalArgumentException e) {
            return ip;
        }
        if (addr instanceof Inet6Address) {
            byte[] b = addr.getAddress();
            for (int i = 8; i < b.length; i++) {
                b[i] = 0;
            }
            try {
                return InetAddresses.toAddrString(InetAddress.getByAddress(b)) + "/64";
            } catch (Exception e) {
                return ip;
            }
        }
        return InetAddresses.toAddrString(addr);
    }

    /**
     * Keyed hash of a namespaced value, producing a fixed-length hex string. Never stores the raw value.
     */
    private String hash(String namespace, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            mac.update(namespace.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception e) {
            // HmacSHA256 is a required JCE algorithm; a failure here is fatal misconfiguration.
            throw new IllegalStateException("Cannot compute throttle key hash", e);
        }
    }

    /**
     * Runs pending maintenance (eviction) on every cache. Test support only: Caffeine performs size-based
     * eviction lazily, so a test that wants to observe an eviction forces it here.
     */
    void runMaintenance() {
        accountCache.cleanUp();
        networkCache.cleanUp();
        pairCache.cleanUp();
        activeLockoutCache.cleanUp();
    }

    /**
     * @return true if the general (evictable) login attempt-counter for the account is still present. Test
     * support only: proves that active-lockout retention, not the counter cache, is what keeps a locked
     * account blocked after high-cardinality churn.
     */
    boolean loginAccountCounterPresent(String account) {
        return accountCache.getIfPresent(hash("login:acct", canonicalAccount(account))) != null;
    }

    /**
     * @return the account attempt-counter cache size after forcing pending maintenance. Test support only:
     * proves the counter cache is size-bounded (never grows past its configured maximum) under churn.
     */
    long accountCounterEstimatedSize() {
        accountCache.cleanUp();
        return accountCache.estimatedSize();
    }

    /**
     * Evicts ONLY the general (evictable) account attempt-counter, leaving the active-lockout hold intact.
     * Test support only: deterministically simulates the high-cardinality churn eviction that Caffeine would
     * otherwise perform lazily, so a test can prove a LOCKED account survives it.
     */
    void evictLoginAccountCounterForTest(String account) {
        accountCache.invalidate(hash("login:acct", canonicalAccount(account)));
    }

    /**
     * Installs an ALREADY-EXPIRED account lockout in the eviction-resistant hold with NO evictable-counter
     * entry. Test support only: lets a test prove admission resumes once a lockout deadline has passed
     * without waiting real time (the lockout clock is the system clock, not the injectable token-bucket one).
     */
    void installExpiredAccountLockoutForTest(String account) {
        activeLockoutCache.put(hash("login:acct", canonicalAccount(account)), System.currentTimeMillis() - 1_000);
    }

    /**
     * Clears all throttle state. Test support only: the store is a process-wide singleton whose state would
     * otherwise leak across tests sharing the loopback address.
     */
    public void reset() {
        accountCache.invalidateAll();
        networkCache.invalidateAll();
        pairCache.invalidateAll();
        activeLockoutCache.invalidateAll();
        loginBucket.reset();
        recoveryBucket.reset();
    }

    /**
     * Immutable per-key attempt record: a saturating failure count and a fixed lockout deadline (0 = not
     * locked). The deadline is computed once at write time and never extended by a read.
     */
    private static final class Attempt {
        final int failCount;
        final long lockedUntilMs;

        Attempt(int failCount, long lockedUntilMs) {
            this.failCount = failCount;
            this.lockedUntilMs = lockedUntilMs;
        }
    }

    /**
     * A simple time-based token bucket: {@code capacity} tokens, refilled at {@code refillPerSec}. Shedding
     * is transient — tokens always refill, so the bucket can never permanently lock out traffic.
     */
    static final class TokenBucket {
        private final double capacity;
        private final double refillPerSec;
        private final LongSupplier nanoClock;
        private double tokens;
        private long lastRefillNanos;

        TokenBucket(double capacity, double refillPerSec, LongSupplier nanoClock) {
            this.capacity = capacity;
            this.refillPerSec = refillPerSec;
            this.nanoClock = nanoClock;
            this.tokens = capacity;
            this.lastRefillNanos = nanoClock.getAsLong();
        }

        synchronized boolean tryAcquire() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        /**
         * @return milliseconds until at least one token is available (0 if one is available now)
         */
        synchronized long millisUntilToken() {
            refill();
            if (tokens >= 1.0) {
                return 0;
            }
            double needed = 1.0 - tokens;
            return (long) Math.ceil(needed / refillPerSec * 1000.0);
        }

        synchronized void reset() {
            tokens = capacity;
            lastRefillNanos = nanoClock.getAsLong();
        }

        private void refill() {
            long now = nanoClock.getAsLong();
            double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
            if (elapsedSec > 0) {
                tokens = Math.min(capacity, tokens + elapsedSec * refillPerSec);
                lastRefillNanos = now;
            }
        }
    }

    /**
     * A throttle decision: whether the caller is blocked, and a bounded Retry-After (seconds).
     */
    public static final class ThrottleDecision {
        static final ThrottleDecision ADMITTED = new ThrottleDecision(false, 0);

        private final boolean blocked;
        private final long retryAfterSeconds;

        ThrottleDecision(boolean blocked, long retryAfterSeconds) {
            this.blocked = blocked;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public boolean isBlocked() {
            return blocked;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private static int readIntEnv(String name, int defaultValue, int min, int max) {
        String raw = System.getenv(name);
        int value = defaultValue;
        if (raw != null) {
            try {
                value = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                value = defaultValue;
            }
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
