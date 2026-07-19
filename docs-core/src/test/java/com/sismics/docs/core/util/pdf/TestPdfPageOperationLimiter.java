package com.sismics.docs.core.util.pdf;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Invariant tests for the page-operation concurrency ceilings. These assert the ceiling's guarantees
 * deterministically — a first job is pinned open on a helper thread with a latch, and the assertions run
 * while it is provably still holding its slot — rather than racing two jobs and hoping for an ordering.
 */
public class TestPdfPageOperationLimiter {

    /**
     * Pin a job open on {@code fileKey} until {@code release} is counted down, signalling {@code started}
     * once it holds its slot. Returns the helper thread so the caller can join it.
     */
    private Thread pinOpen(PdfPageOperationLimiter limiter, String fileKey,
                           CountDownLatch started, CountDownLatch release,
                           AtomicReference<Throwable> failure) {
        Thread t = new Thread(() -> {
            try {
                limiter.runExclusive(fileKey, () -> {
                    started.countDown();
                    release.await();
                    return "done";
                });
            } catch (Throwable e) {
                failure.set(e);
                started.countDown();
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    public void secondOperationOnSameFileIsRejectedNotQueued() throws Exception {
        PdfPageOperationLimiter limiter = new PdfPageOperationLimiter(4);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = pinOpen(limiter, "fileA", started, release, failure);
        Assertions.assertTrue(started.await(5, TimeUnit.SECONDS), "the first job must start");
        Assertions.assertNull(failure.get());

        // While fileA is held, a second fileA operation must be REJECTED immediately (never blocks).
        PdfPageOperationException e = Assertions.assertThrows(PdfPageOperationBusyException.class,
                () -> limiter.runExclusive("fileA", () -> "should-not-run"));
        Assertions.assertEquals("TooManyRequests", e.getType());

        // A different file is unaffected (global has room).
        Assertions.assertEquals("ok", limiter.runExclusive("fileB", () -> "ok"));

        release.countDown();
        first.join(5000);

        // Once released, fileA is free again.
        Assertions.assertEquals("ok", limiter.runExclusive("fileA", () -> "ok"));
        Assertions.assertFalse(limiter.isFileActive("fileA"));
    }

    @Test
    public void globalCeilingRejectsBeyondLimit() throws Exception {
        PdfPageOperationLimiter limiter = new PdfPageOperationLimiter(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = pinOpen(limiter, "fileA", started, release, failure);
        Assertions.assertTrue(started.await(5, TimeUnit.SECONDS));
        Assertions.assertNull(failure.get());

        // The single global slot is taken, so even a DIFFERENT file is rejected, not queued.
        PdfPageOperationException e = Assertions.assertThrows(PdfPageOperationBusyException.class,
                () -> limiter.runExclusive("fileB", () -> "should-not-run"));
        Assertions.assertEquals("TooManyRequests", e.getType());

        release.countDown();
        first.join(5000);
        Assertions.assertEquals("ok", limiter.runExclusive("fileB", () -> "ok"));
    }

    @Test
    public void distinctFilesRunConcurrentlyWithinGlobal() throws Exception {
        PdfPageOperationLimiter limiter = new PdfPageOperationLimiter(2);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = pinOpen(limiter, "fileA", started, release, failure);
        Assertions.assertTrue(started.await(5, TimeUnit.SECONDS));

        // fileA holds one of two global slots; a different file still runs concurrently.
        Assertions.assertEquals("ok", limiter.runExclusive("fileB", () -> "ok"));

        release.countDown();
        first.join(5000);
        Assertions.assertNull(failure.get());
    }

    @Test
    public void permitsAreReleasedWhenTheJobThrows() throws Exception {
        PdfPageOperationLimiter limiter = new PdfPageOperationLimiter(1);

        // A job that fails must not leak either slot.
        Assertions.assertThrows(IllegalStateException.class,
                () -> limiter.runExclusive("fileA", () -> {
                    throw new IllegalStateException("boom");
                }));

        Assertions.assertEquals(1, limiter.availableGlobalPermits(),
                "the global slot must be released after a failing job");
        Assertions.assertFalse(limiter.isFileActive("fileA"),
                "the per-file slot must be released after a failing job");

        // The limiter is fully usable again — no leaked permit.
        Assertions.assertEquals("ok", limiter.runExclusive("fileA", () -> "ok"));
    }
}
