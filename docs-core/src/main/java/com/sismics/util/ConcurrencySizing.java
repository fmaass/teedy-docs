package com.sismics.util;

/**
 * Derives the application's two coupled concurrency budgets — the per-bus async worker count and
 * the JDBC connection pool size — from the host's CPU count.
 *
 * <p>They are coupled because every async worker that runs a listener holds a connection for the
 * duration of its transaction. Sizing them independently is what caused #230: the pool default was
 * a fixed 10 while each of the two async buses was sized {@code availableProcessors() / 2}, so a
 * 20-core host started 20 async workers against 10 connections and Hibernate's internal pool
 * reported "The internal connection pool has reached its maximum size" during a processing burst —
 * surfacing as intermittent 500s on ordinary reads. Both numbers therefore come from here, and a
 * change to one is a change to the other.</p>
 *
 * <p>The bus count is capped as well as floored: without a cap a 64-core host would start 64
 * workers per bus, which no pool bounded by a shared PostgreSQL's connection budget can serve, and
 * the defect would reproduce from the thread side alone.</p>
 */
public final class ConcurrencySizing {
    /**
     * Number of async event buses sharing the pool (the generic bus and the mail bus, both built by
     * {@code AppContext#newAsyncEventBus}). The pool has to cover both at full occupancy.
     */
    public static final int ASYNC_BUS_COUNT = 2;

    /**
     * Floor for the per-bus worker count: a 1- or 2-core host still gets two workers, so a single
     * slow listener cannot stall the bus behind it.
     */
    static final int MIN_BUS_THREADS = 2;

    /**
     * Cap for the per-bus worker count. 20 workers per bus is already more file-processing
     * concurrency than any self-hosted instance needs, and it is the number that keeps the derived
     * pool inside {@link #MAX_POOL_SIZE}.
     */
    static final int MAX_BUS_THREADS = 20;

    /**
     * Connections reserved on top of the async workers, for the Jetty request threads serving the
     * UI/API and the six DB-using scheduled services (inbox scan, file/file-size, content-MAC
     * backfill, trash purge, OIDC state purge, file reconciliation).
     */
    static final int POOL_HEADROOM = 10;

    /**
     * Floor for the pool: the historical fixed default. Small hosts must never regress below what
     * they had.
     */
    static final int MIN_POOL_SIZE = 10;

    /**
     * Cap for the pool. PostgreSQL's own default {@code max_connections} is 100 and the database is
     * commonly shared with other tenants, so a single Teedy instance claims at most half of it.
     * Operators running several instances against one server set {@code DATABASE_POOL_SIZE}
     * explicitly.
     */
    static final int MAX_POOL_SIZE = 50;

    private ConcurrencySizing() {
        // Utility class.
    }

    /**
     * Worker count for ONE async event bus on this host.
     *
     * @return Thread count per async bus
     */
    public static int asyncBusThreadCount() {
        return asyncBusThreadCount(Runtime.getRuntime().availableProcessors());
    }

    /**
     * Worker count for one async event bus, for an explicit CPU count.
     *
     * @param availableProcessors Number of processors available to the JVM
     * @return Thread count per async bus, clamped to [{@value #MIN_BUS_THREADS}, {@value #MAX_BUS_THREADS}]
     */
    public static int asyncBusThreadCount(int availableProcessors) {
        return Math.min(Math.max(availableProcessors / 2, MIN_BUS_THREADS), MAX_BUS_THREADS);
    }

    /**
     * Default JDBC connection pool size for this host, when no explicit size is configured.
     *
     * @return Connection pool size
     */
    public static int defaultConnectionPoolSize() {
        return defaultConnectionPoolSize(Runtime.getRuntime().availableProcessors());
    }

    /**
     * Default JDBC connection pool size for an explicit CPU count: enough connections for every
     * async worker on both buses at once, plus {@value #POOL_HEADROOM} for request threads and the
     * scheduled services, clamped to [{@value #MIN_POOL_SIZE}, {@value #MAX_POOL_SIZE}].
     *
     * <p>These are maxima, not eager allocations: {@code hibernate.connection.initial_pool_size}
     * stays 1, so an idle instance still holds a single connection.</p>
     *
     * @param availableProcessors Number of processors available to the JVM
     * @return Connection pool size
     */
    public static int defaultConnectionPoolSize(int availableProcessors) {
        int busThreads = asyncBusThreadCount(availableProcessors);
        return Math.min(Math.max(ASYNC_BUS_COUNT * busThreads + POOL_HEADROOM, MIN_POOL_SIZE), MAX_POOL_SIZE);
    }
}
