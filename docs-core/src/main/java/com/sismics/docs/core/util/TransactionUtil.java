package com.sismics.docs.core.util;

import com.sismics.docs.core.util.TransactionBoundary.DurableState;
import com.sismics.docs.core.util.TransactionBoundary.OwnedResult;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

/**
 * Database transaction utils.
 *
 * @author jtremeaux
 */
public class TransactionUtil {
    /**
     * Encapsulate a process into a transactionnal context.
     *
     * <p>If a transactional context with a LIVE transaction is already installed on the thread, the
     * runnable runs inline in it. Otherwise this owns a fresh entity manager + transaction: on success it
     * commits (COMMITTED, firing the queued events + after-commit callbacks); on a work failure it rolls
     * back (ROLLED_BACK, discarding the events + running after-rollback callbacks) and RETHROWS the
     * failure — no longer swallowed, so the caller cannot treat a rolled-back unit of work as a success.
     * If the commit or the rollback itself throws, the outcome is IN_DOUBT (no callbacks, no
     * fire/discard) and the failure propagates.</p>
     *
     * @param runnable Runnable
     */
    public static void handle(Runnable runnable) {
        ThreadLocalContext context = ThreadLocalContext.get();
        // Owner discovery: inspect without flushing an unrelated caller's pending work. "An entity
        // manager is installed" is NOT "a transaction is active" — only pass through when the enclosing
        // transaction is genuinely live, otherwise the runnable would run untransacted.
        EntityManager existing = context.peekEntityManager();
        if (existing != null && existing.isOpen()
                && existing.getTransaction() != null && existing.getTransaction().isActive()) {
            // We are already in a transactional context, nothing to do
            runnable.run();
            return;
        }

        // A nested owner reached DURING a completion callback (the current frame is COMPLETING, its
        // transaction already finished) must NOT reuse the completing frame: reusing it would install a
        // new manager into that frame and re-dispatch its registry — re-running its callbacks (a failed
        // nested unit would make the outer committed frame also run its after-rollback set) or recursing.
        // Push a fresh, isolated frame for it and pop (not cleanup) afterwards so the enclosing owner's
        // context survives.
        boolean nested = context.isCurrentFrameCompleting();

        // Fail fast if no entity manager can be created, BEFORE installing anything into the context:
        // continuing on a null manager would throw a NullPointerException outside the finalization path,
        // which would skip the frame completion entirely (leaving queued events and the marker state
        // stranded until the JVM restarts).
        EntityManager em;
        try {
            em = EMF.get().createEntityManager();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create the entity manager for a background transaction", e);
        }
        if (em == null) {
            throw new IllegalStateException("The entity manager factory returned no entity manager for a background transaction");
        }

        // Install the frame and enter the cleanup-protected region ATOMICALLY: everything after this
        // (including tx.begin) runs under the finally, so a begin failure cannot leak the installed frame
        // or manager onto a pooled thread.
        if (nested) {
            context.pushFrame(em);
        } else {
            context.setEntityManager(em);
        }

        OwnedResult result = null;
        try {
            EntityTransaction tx = em.getTransaction();
            tx.begin();

            boolean workOk = false;
            Throwable workFailure = null;
            try {
                runnable.run();
                workOk = true;
            } catch (RuntimeException | Error e) {
                workFailure = e;
            }

            // finalizeOwned commits (on success) or rolls back (on failure), classifies the 3-state
            // outcome, closes the entity manager, and dispatches the frame's completion while it is still
            // installed. It never throws — it tells us what to propagate.
            result = TransactionBoundary.finalizeOwned(em, tx, workOk, workFailure);
        } finally {
            // Unconditional teardown, even if tx.begin() threw: close the manager (backstop), then either
            // pop the nested frame or clear the whole context. cleanup() is guaranteed to run even if
            // closeQuietly somehow threw.
            try {
                TransactionBoundary.closeQuietly(em);
            } finally {
                if (nested) {
                    context.popFrame();
                } else {
                    ThreadLocalContext.cleanup();
                }
            }
        }

        if (!result.isCommitted() && result.getPropagate() != null) {
            // ROLLED_BACK -> the original work failure. IN_DOUBT -> the work failure (rollback threw) or
            // the commit failure (commit threw). Either way the caller must see it.
            TransactionBoundary.propagate(result.getPropagate());
        }
    }

    /**
     * Commits the current transaction at a checkpoint and begins a fresh one on the SAME entity
     * manager, so a long-running unit of work can make a durable change visible mid-flight (the next
     * lock holder, a subsequent read) while the enclosing owner keeps using the manager afterwards.
     *
     * <p>The finished transaction's queued async events belong to it and are now durable, so they are
     * fired — and its after-commit callbacks run — at this boundary, then the frame is rotated to a
     * fresh empty scope for the next transaction. A failure to re-begin is FATAL-TO-CONTINUATION: tx1's
     * durable commit is preserved (it is NOT relabelled), the orphaned entity manager is closed, and the
     * failure PROPAGATES so the caller aborts — there is no usable transaction left to continue in.</p>
     */
    public static void commit() {
        ThreadLocalContext context = ThreadLocalContext.get();
        // Validate on the non-flushing peek FIRST: the mutating getEntityManager() below flushes, and a
        // flush with no active transaction would throw an opaque TransactionRequiredException before the
        // guard could produce a clear diagnostic. Fail fast on a missing / closed manager or an inactive
        // transaction here instead.
        EntityManager em = context.peekEntityManager();
        if (em == null || !em.isOpen()) {
            throw new IllegalStateException("Cannot commit the checkpoint: no open entity manager is installed");
        }
        EntityTransaction tx = em.getTransaction();
        if (tx == null || !tx.isActive()) {
            throw new IllegalStateException("Cannot commit the checkpoint: no active transaction to commit");
        }

        // Flush pending work into the transaction (the mutating getter) before committing it durably. A
        // flush failure is a clean PRE-commit failure (tx1 did not commit) — let it propagate to the
        // enclosing owner, which rolls the request transaction back.
        context.getEntityManager();

        // Classify the commit through the three-state boundary rather than a raw tx.commit(): a thrown
        // commit is IN_DOUBT (the database may have committed anyway). Mark the frame terminal so the
        // enclosing owner does NOT re-finalize it as a work failure (which would discard the events and
        // run rollback compensation on a possibly-committed transaction), then propagate — there is no
        // clean state to continue in.
        TransactionBoundary.ClassifiedOutcome co = TransactionBoundary.tryCommit(tx);
        if (co.getState() == DurableState.IN_DOUBT) {
            context.markCurrentFrameInDoubt();
            TransactionBoundary.complete(DurableState.IN_DOUBT);
            TransactionBoundary.propagate(co.getFailure());
        }

        // The checkpoint commit is durable: fire this transaction's events + run its after-commit
        // callbacks, then open a fresh scope for the next transaction.
        TransactionBoundary.rotate(DurableState.COMMITTED);

        // Re-begin the continuation transaction; a failure here closes the orphaned manager and propagates.
        TransactionBoundary.beginContinuation(em, tx);
    }
}
