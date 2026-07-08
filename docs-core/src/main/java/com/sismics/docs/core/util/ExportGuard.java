package com.sismics.docs.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resource-exhaustion guard for the full-account export endpoint
 * ({@code GET /api/document/export}).
 *
 * <p>The export eagerly enumerates every document owned by the caller and buffers a
 * JSON manifest of all documents and files in heap while streaming the ZIP. Without a
 * bound, a caller with a very large account — or several callers exporting at once —
 * can exhaust the heap and OOM the running service (RR-52). This class provides:</p>
 *
 * <ul>
 *   <li>a document-count <em>cap</em> checked as a PREFLIGHT (before the eager load), so
 *       an over-cap account is rejected without ever loading its documents;</li>
 *   <li>a small <em>concurrency</em> limit so only a bounded number of exports buffer a
 *       manifest at once;</li>
 *   <li>an optional feature <em>flag</em> to disable the endpoint entirely.</li>
 * </ul>
 *
 * <p>All three are configured with the same "system property first, then environment
 * variable" convention used elsewhere (see {@code WebhookUtil}, {@code TrashPurgeService}).
 * Values are read at call time so tests can set them via a system property seam.</p>
 */
public final class ExportGuard {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(ExportGuard.class);

    /**
     * System property / env var (DOCS_EXPORT_ENABLED) controlling whether the export
     * endpoint is enabled at all. Defaults to true.
     */
    public static final String ENABLED_PROPERTY = "docs.export_enabled";
    public static final String ENABLED_ENV = "DOCS_EXPORT_ENABLED";

    /**
     * System property / env var (DOCS_EXPORT_MAX_DOCUMENTS) capping the number of
     * documents a single export may contain. Defaults to {@value #DEFAULT_MAX_DOCUMENTS}.
     */
    public static final String MAX_DOCUMENTS_PROPERTY = "docs.export_max_documents";
    public static final String MAX_DOCUMENTS_ENV = "DOCS_EXPORT_MAX_DOCUMENTS";
    public static final int DEFAULT_MAX_DOCUMENTS = 10000;

    /**
     * System property / env var (DOCS_EXPORT_MAX_CONCURRENT) capping the number of
     * simultaneous in-flight exports. Defaults to {@value #DEFAULT_MAX_CONCURRENT}.
     */
    public static final String MAX_CONCURRENT_PROPERTY = "docs.export_max_concurrent";
    public static final String MAX_CONCURRENT_ENV = "DOCS_EXPORT_MAX_CONCURRENT";
    public static final int DEFAULT_MAX_CONCURRENT = 2;

    /**
     * Lazily (re)built semaphore guarding concurrent exports, together with the permit
     * count it was built for. Rebuilt when the configured concurrency changes so tests
     * (and live config changes) take effect without a restart.
     */
    private static final AtomicReference<Semaphore> SEMAPHORE = new AtomicReference<>();
    private static volatile int semaphorePermits = -1;

    private ExportGuard() {
    }

    /**
     * @return true if the export endpoint is enabled (default true).
     */
    public static boolean isEnabled() {
        String value = readSetting(ENABLED_PROPERTY, ENABLED_ENV);
        if (value == null || value.isBlank()) {
            return true;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /**
     * @return the maximum number of documents a single export may contain.
     */
    public static int getMaxDocuments() {
        return readInt(MAX_DOCUMENTS_PROPERTY, MAX_DOCUMENTS_ENV, DEFAULT_MAX_DOCUMENTS);
    }

    /**
     * @return the maximum number of simultaneous exports.
     */
    public static int getMaxConcurrent() {
        return readInt(MAX_CONCURRENT_PROPERTY, MAX_CONCURRENT_ENV, DEFAULT_MAX_CONCURRENT);
    }

    /**
     * Try to acquire a concurrency permit for one export.
     *
     * <p>Returns the exact {@link Semaphore} instance a permit was acquired on, or
     * {@code null} if the concurrency cap is reached. The caller MUST pass the returned
     * (non-null) instance to {@link #release(Semaphore)} in a finally block. Returning the
     * instance (rather than releasing via {@link #semaphore()} again) is deliberate: a
     * config change mid-flight can rebuild the semaphore, so releasing must target the
     * SAME instance the permit was taken from — otherwise the original permit leaks and the
     * new semaphore is over-released, weakening the cap.</p>
     *
     * @return the semaphore a permit was acquired on, or null if none was available
     */
    public static Semaphore tryAcquire() {
        Semaphore semaphore = semaphore();
        return semaphore.tryAcquire() ? semaphore : null;
    }

    /**
     * Release a permit on the exact semaphore instance it was acquired from.
     *
     * @param semaphore The semaphore returned by {@link #tryAcquire()} (ignored if null)
     */
    public static void release(Semaphore semaphore) {
        if (semaphore != null) {
            semaphore.release();
        }
    }

    /**
     * Get (rebuilding if the configured permit count changed) the concurrency semaphore.
     */
    private static synchronized Semaphore semaphore() {
        int permits = Math.max(1, getMaxConcurrent());
        Semaphore current = SEMAPHORE.get();
        if (current == null || semaphorePermits != permits) {
            current = new Semaphore(permits, true);
            SEMAPHORE.set(current);
            semaphorePermits = permits;
        }
        return current;
    }

    /**
     * Read a positive-int setting from the system property, falling back to the env var,
     * then to the default. A missing or malformed value falls back to the default.
     */
    private static int readInt(String property, String env, int defaultValue) {
        String value = readSetting(property, env);
        if (value != null && !value.isBlank()) {
            try {
                int parsed = Integer.parseInt(value.trim());
                if (parsed > 0) {
                    return parsed;
                }
                log.warn("Non-positive value for {} ({}), using default {}", property, value, defaultValue);
            } catch (NumberFormatException e) {
                log.warn("Invalid value for {} ({}), using default {}", property, value, defaultValue);
            }
        }
        return defaultValue;
    }

    /**
     * Read a setting from the system property first, falling back to the environment
     * variable form. Returns null if neither is set.
     */
    private static String readSetting(String property, String env) {
        String value = System.getProperty(property);
        if (value == null) {
            value = System.getenv(env);
        }
        return value;
    }
}
