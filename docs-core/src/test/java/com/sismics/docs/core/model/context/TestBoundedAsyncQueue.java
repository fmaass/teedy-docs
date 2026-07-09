package com.sismics.docs.core.model.context;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercises {@link AppContext#newBoundedAsyncExecutor(int, int)} — the factory that builds the
 * {@link ThreadPoolExecutor} backing the async event bus in production. Under unit tests the async
 * path uses a synchronous {@code EventBus}, so this is the only test that drives the real bounded
 * executor construction. It asserts real executor behaviour: the queue is bounded to its configured
 * capacity, and at capacity the {@code CallerRunsPolicy} runs overflow tasks on the caller thread
 * (backpressure) rather than dropping them or growing the queue without limit.
 *
 * @author teedy
 */
public class TestBoundedAsyncQueue {

    @Test
    public void queueIsBoundedToConfiguredCapacity() {
        ThreadPoolExecutor executor = AppContext.newBoundedAsyncExecutor(1, 2);
        try {
            Assertions.assertEquals(2, executor.getQueue().remainingCapacity(),
                    "Bounded queue must report its configured capacity");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void callerRunsAtCapacityAndNoTaskIsDropped() throws Exception {
        // Single worker + capacity 1. We block the worker, fill the single queue slot, then submit
        // more tasks: with an unbounded queue those would all be queued (queue grows without limit);
        // with the bounded queue + CallerRuns the submitting thread runs the overflow itself, so
        // every task still executes and none is dropped.
        int threads = 1;
        int capacity = 1;
        ThreadPoolExecutor executor = AppContext.newBoundedAsyncExecutor(threads, capacity);
        AtomicInteger executed = new AtomicInteger();
        Thread mainThread = Thread.currentThread();
        AtomicInteger ranOnCaller = new AtomicInteger();

        CountDownLatch blockWorker = new CountDownLatch(1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        try {
            // Occupy the single worker thread.
            executor.execute(() -> {
                workerStarted.countDown();
                try {
                    blockWorker.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                executed.incrementAndGet();
            });
            Assertions.assertTrue(workerStarted.await(5, TimeUnit.SECONDS), "Worker should have started");

            // Fill the single queue slot (this task will wait for the worker to free up).
            executor.execute(executed::incrementAndGet);
            Assertions.assertEquals(0, executor.getQueue().remainingCapacity(),
                    "Queue should be full after one queued task");

            // Overflow submissions: with CallerRuns these run on THIS (caller) thread immediately
            // rather than growing the queue past its bound.
            int overflow = 5;
            for (int i = 0; i < overflow; i++) {
                executor.execute(() -> {
                    if (Thread.currentThread() == mainThread) {
                        ranOnCaller.incrementAndGet();
                    }
                    executed.incrementAndGet();
                });
            }
            // The queue must never have grown beyond its bound.
            Assertions.assertEquals(0, executor.getQueue().remainingCapacity(),
                    "Bounded queue must not grow beyond capacity even under overflow");
            // The overflow tasks ran on the caller (CallerRunsPolicy backpressure), none dropped.
            Assertions.assertEquals(overflow, ranOnCaller.get(),
                    "Overflow tasks must run on the caller thread (CallerRunsPolicy)");
            Assertions.assertEquals(overflow, executed.get(),
                    "Overflow tasks must run on the caller, not be dropped");

            // Release the worker; the queued task then runs too.
            blockWorker.countDown();
            executor.shutdown();
            Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor should terminate");

            // 1 blocked worker task + 1 queued task + overflow overflow tasks all executed.
            Assertions.assertEquals(overflow + 2, executed.get(),
                    "Every submitted task must eventually execute; none dropped");
        } finally {
            blockWorker.countDown();
            executor.shutdownNow();
        }
    }
}
