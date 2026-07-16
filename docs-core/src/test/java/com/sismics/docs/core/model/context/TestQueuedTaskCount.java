package com.sismics.docs.core.model.context;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Exercises {@link AppContext#computeQueuedTaskCount(List)} — the accumulation behind the
 * {@code queued_tasks} value that {@code GET /api/app} reports. {@link ThreadPoolExecutor#getTaskCount()}
 * and {@link ThreadPoolExecutor#getCompletedTaskCount()} both return {@code long}; the sum must not be
 * narrowed through {@code int} on the way out, or a long-lived instance past 2^31 processed tasks
 * reports a garbage (possibly negative) queue depth.
 *
 * @author teedy
 */
public class TestQueuedTaskCount {

    /**
     * A ThreadPoolExecutor whose task counters are fixed test inputs. Only the two counter getters
     * are overridden — the executor is never given work.
     */
    private static ThreadPoolExecutor stubExecutor(long taskCount, long completedTaskCount) {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {
            @Override
            public long getTaskCount() {
                return taskCount;
            }

            @Override
            public long getCompletedTaskCount() {
                return completedTaskCount;
            }
        };
    }

    @Test
    public void sumsSmallQueueDepths() {
        ThreadPoolExecutor a = stubExecutor(10, 4);
        ThreadPoolExecutor b = stubExecutor(7, 7);
        try {
            Assertions.assertEquals(6L, AppContext.computeQueuedTaskCount(List.of(a, b)));
        } finally {
            a.shutdownNow();
            b.shutdownNow();
        }
    }

    @Test
    public void survivesCountsBeyondIntRange() {
        // 3_000_000_000 pending tasks per executor is unrealistic, but getTaskCount() legitimately
        // exceeds Integer.MAX_VALUE on long-lived instances; the DIFFERENCE below stresses the
        // accumulator with a value an int cannot hold.
        ThreadPoolExecutor a = stubExecutor(3_000_000_000L, 0L);
        ThreadPoolExecutor b = stubExecutor(3_000_000_000L, 500_000_000L);
        try {
            Assertions.assertEquals(5_500_000_000L, AppContext.computeQueuedTaskCount(List.of(a, b)),
                    "Queued task count must accumulate in long arithmetic, not overflow through int");
        } finally {
            a.shutdownNow();
            b.shutdownNow();
        }
    }
}
