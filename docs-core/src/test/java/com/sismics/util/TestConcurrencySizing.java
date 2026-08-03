package com.sismics.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit test of the coupled concurrency budgets (#230).
 *
 * <p>The pure {@code (int availableProcessors)} overloads are exercised so the host running the
 * suite cannot decide the outcome — the defect was CPU-count-dependent, so a test that read the
 * real processor count would assert whatever the runner happened to have.</p>
 */
public class TestConcurrencySizing {

    /**
     * The sizing contract across the range that matters: a tiny host, the common 4/8-core box, the
     * 20-core host the defect was reproduced on, and machines large enough to hit both caps.
     * Rows are {processors, expected workers per bus, expected pool size}.
     */
    private static final int[][] EXPECTED_SIZING = {
            {1, 2, 14},
            {2, 2, 14},
            {4, 2, 14},
            {8, 4, 18},
            {16, 8, 26},
            {20, 10, 30},
            {40, 20, 50},
            {64, 20, 50},
            {128, 20, 50},
    };

    @Test
    public void testSizingAcrossHostSizes() {
        for (int[] row : EXPECTED_SIZING) {
            int availableProcessors = row[0];
            Assertions.assertEquals(row[1], ConcurrencySizing.asyncBusThreadCount(availableProcessors),
                    "async workers per bus for " + availableProcessors + " processors");
            Assertions.assertEquals(row[2], ConcurrencySizing.defaultConnectionPoolSize(availableProcessors),
                    "default connection pool size for " + availableProcessors + " processors");
        }
    }

    /**
     * The invariant the whole fix exists for: the pool must be able to serve every async worker on
     * both buses at once and still have connections left for request threads and scheduled jobs.
     * The old fixed default of 10 violated this from 6 processors upwards.
     */
    @Test
    public void testPoolAlwaysCoversBothBusesPlusHeadroom() {
        for (int processors = 1; processors <= 256; processors++) {
            int busThreads = ConcurrencySizing.asyncBusThreadCount(processors);
            int poolSize = ConcurrencySizing.defaultConnectionPoolSize(processors);
            Assertions.assertTrue(
                    poolSize >= ConcurrencySizing.ASYNC_BUS_COUNT * busThreads + ConcurrencySizing.POOL_HEADROOM,
                    "pool " + poolSize + " must cover " + ConcurrencySizing.ASYNC_BUS_COUNT + " x " + busThreads
                            + " workers plus " + ConcurrencySizing.POOL_HEADROOM + " headroom on a "
                            + processors + "-processor host");
        }
    }

    /**
     * Small hosts must never regress below the historical fixed default, and no host may claim more
     * than half of PostgreSQL's default max_connections without an explicit override.
     */
    @Test
    public void testPoolStaysWithinItsFloorAndCap() {
        for (int processors = 1; processors <= 256; processors++) {
            int poolSize = ConcurrencySizing.defaultConnectionPoolSize(processors);
            Assertions.assertTrue(poolSize >= ConcurrencySizing.MIN_POOL_SIZE,
                    "pool " + poolSize + " below the floor on a " + processors + "-processor host");
            Assertions.assertTrue(poolSize <= ConcurrencySizing.MAX_POOL_SIZE,
                    "pool " + poolSize + " above the cap on a " + processors + "-processor host");
        }
    }

    /**
     * The bus cap is the second half of the fix: uncapped, a 64-core host starts 64 workers per bus
     * and exhausts any pool bounded by the cap above, reproducing #230 from the thread side alone.
     */
    @Test
    public void testBusThreadsStayWithinTheirFloorAndCap() {
        for (int processors = 1; processors <= 256; processors++) {
            int busThreads = ConcurrencySizing.asyncBusThreadCount(processors);
            Assertions.assertTrue(busThreads >= ConcurrencySizing.MIN_BUS_THREADS,
                    "bus workers " + busThreads + " below the floor on a " + processors + "-processor host");
            Assertions.assertTrue(busThreads <= ConcurrencySizing.MAX_BUS_THREADS,
                    "bus workers " + busThreads + " above the cap on a " + processors + "-processor host");
        }
    }

    /**
     * The no-argument overloads are what production calls; they must agree with the pure overloads
     * for this host rather than being a second, drifting implementation.
     */
    @Test
    public void testNoArgOverloadsMatchThisHost() {
        int processors = Runtime.getRuntime().availableProcessors();
        Assertions.assertEquals(ConcurrencySizing.asyncBusThreadCount(processors),
                ConcurrencySizing.asyncBusThreadCount());
        Assertions.assertEquals(ConcurrencySizing.defaultConnectionPoolSize(processors),
                ConcurrencySizing.defaultConnectionPoolSize());
    }
}
