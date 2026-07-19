package com.sismics.util.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-frame registry of transaction-completion callbacks. A callback registered here runs once, when
 * its owning transaction frame reaches a durable outcome:
 * <ul>
 *   <li>{@link #registerAfterCommit(Runnable)} — after a durable commit;</li>
 *   <li>{@link #registerAfterRollback(Runnable)} — after a rollback;</li>
 *   <li>{@link #registerAfterCompletion(Runnable)} — after either outcome.</li>
 * </ul>
 *
 * <p>Registration is separated from firing so a modification can schedule work (index update, byte
 * deletion, temp-file cleanup) that must happen only once the transaction's durability is known. The
 * completion dispatcher is the sole caller of the dispatch methods; each callback is guarded so one
 * failure neither aborts the remaining callbacks nor flips the transaction's durable outcome — a
 * callback that throws AFTER a durable commit is a secondary failure, not a reason to roll back.</p>
 */
public class TransactionCompletionRegistry {
    private static final Logger log = LoggerFactory.getLogger(TransactionCompletionRegistry.class);

    private final List<Runnable> afterCommit = new ArrayList<>();
    private final List<Runnable> afterRollback = new ArrayList<>();
    private final List<Runnable> afterCompletion = new ArrayList<>();

    public void registerAfterCommit(Runnable callback) {
        afterCommit.add(callback);
    }

    public void registerAfterRollback(Runnable callback) {
        afterRollback.add(callback);
    }

    public void registerAfterCompletion(Runnable callback) {
        afterCompletion.add(callback);
    }

    /**
     * Runs the outcome-specific callbacks: after-commit when {@code committed}, else after-rollback.
     * Each callback is guarded; the first failure is returned so the dispatcher can record it as a
     * deferred failure on the outcome without converting a committed transaction into a rolled-back one.
     *
     * @param committed whether the owning transaction durably committed
     * @return the first callback failure, or {@code null} if none threw
     */
    public Throwable dispatchOutcome(boolean committed) {
        return runGuarded(committed ? afterCommit : afterRollback);
    }

    /**
     * Runs the after-completion callbacks (registered for either outcome), AFTER the outcome-specific
     * callbacks have run. Each callback is guarded; the first failure is returned.
     *
     * @return the first callback failure, or {@code null} if none threw
     */
    public Throwable dispatchCompletion() {
        return runGuarded(afterCompletion);
    }

    private static Throwable runGuarded(List<Runnable> callbacks) {
        Throwable firstFailure = null;
        // Index-based iteration (not a for-each): a callback that registers another callback during
        // dispatch appends to this list, and the append is picked up and run within the SAME completion
        // rather than dropped — and there is no ConcurrentModificationException.
        for (int i = 0; i < callbacks.size(); i++) {
            try {
                callbacks.get(i).run();
            } catch (Exception e) {
                // A completion callback must never abort its siblings or flip the durable outcome: log
                // and keep going so every registered callback still gets its chance to run.
                log.error("A transaction-completion callback threw; suppressing it to protect the durable outcome", e);
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        return firstFailure;
    }
}
