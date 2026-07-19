package com.sismics.docs.core.util.pdf;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Bounds page-operation concurrency with two independent, non-blocking ceilings so an abusive or
 * accidental burst of expensive PDF rewrites cannot exhaust the server:
 *
 * <ul>
 *   <li><b>Single-writer per file</b> — at most one operation per file at a time, enforced by an atomic
 *       key-set insertion. This is a correctness invariant, not a tunable: it mirrors the single-writer
 *       assumption of the file-version compare-and-swap, so two operations can never race the same base.</li>
 *   <li><b>Bounded global slots</b> — at most {@code maxGlobalConcurrent} operations across all files,
 *       enforced by a {@link Semaphore}.</li>
 * </ul>
 *
 * <p>Both acquisitions are non-blocking: a saturated ceiling REJECTS with {@link PdfPageOperationBusyException}
 * rather than queuing, so a caller returns a typed client error instead of hanging. Both are released in a
 * {@code finally}, on every path including a job that throws, so a failure can never leak a permit.</p>
 */
public final class PdfPageOperationLimiter {
    /**
     * The unit of work run under the ceilings.
     */
    @FunctionalInterface
    public interface Job<T> {
        T call() throws Exception;
    }

    private final Semaphore globalSlots;
    private final Set<String> activeFiles = ConcurrentHashMap.newKeySet();

    /**
     * @param maxGlobalConcurrent Maximum operations running at once across all files (clamped to at least 1)
     */
    public PdfPageOperationLimiter(int maxGlobalConcurrent) {
        this.globalSlots = new Semaphore(Math.max(1, maxGlobalConcurrent));
    }

    /**
     * Run a job holding the per-file and global slots for its whole duration, releasing both afterwards.
     *
     * @param fileKey The file being operated on (the per-file exclusion key)
     * @param job The work to run
     * @return The job's result
     * @throws PdfPageOperationBusyException if either ceiling is saturated (the job never runs)
     * @throws Exception whatever the job throws (the slots are released first)
     */
    public <T> T runExclusive(String fileKey, Job<T> job) throws Exception {
        if (fileKey == null) {
            throw new IllegalArgumentException("fileKey is required");
        }
        // Take the per-file slot first: an atomic insertion that fails when a slot is already held.
        if (!activeFiles.add(fileKey)) {
            throw new PdfPageOperationBusyException(
                    "A page operation is already running for this file");
        }
        try {
            // Then a global slot. Throwing here still runs the outer finally, releasing the per-file slot.
            if (!globalSlots.tryAcquire()) {
                throw new PdfPageOperationBusyException(
                        "Too many page operations are running; retry shortly");
            }
            try {
                return job.call();
            } finally {
                globalSlots.release();
            }
        } finally {
            activeFiles.remove(fileKey);
        }
    }

    /**
     * @return Currently free global slots (test/introspection seam for the no-leak invariant).
     */
    int availableGlobalPermits() {
        return globalSlots.availablePermits();
    }

    /**
     * @return Whether an operation currently holds the per-file slot for {@code fileKey}.
     */
    boolean isFileActive(String fileKey) {
        return activeFiles.contains(fileKey);
    }
}
