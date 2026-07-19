package com.sismics.docs.core.util;

import com.sismics.util.context.ThreadLocalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import java.util.function.Function;

/**
 * Single authority that finalizes a transaction frame after its underlying transaction has been
 * committed or rolled back. It is the ONLY caller of {@link ThreadLocalContext#fireAllAsyncEvents()} /
 * {@link ThreadLocalContext#discardAsyncEvents()}, folding the "fire the queued async events IFF the
 * frame durably committed" guard (issue #63) into one place alongside the completion callbacks.
 *
 * <p>The durable outcome has THREE states, because a resource-local commit is not a two-valued thing:
 * <ul>
 *   <li>{@link DurableState#COMMITTED} — {@code commit()} RETURNED. Fire the after-commit callbacks and
 *       the queued async events.</li>
 *   <li>{@link DurableState#ROLLED_BACK} — a pre-commit failure whose {@code rollback()} SUCCEEDED. Run
 *       the after-rollback callbacks, discard the queued events, and (for an owner) rethrow the failure.</li>
 *   <li>{@link DurableState#IN_DOUBT} — {@code commit()} THREW (the database may have committed anyway)
 *       or {@code rollback()} THREW (the rollback is unconfirmed). Run NEITHER callback set, do NOT
 *       discard-as-rollback or run compensation, LOG, and propagate. Classifying a thrown commit as
 *       rolled back would run after-rollback compensation and drop the index / blob / email events for a
 *       row that actually committed — a permanent, silent side-effect loss.</li>
 * </ul>
 *
 * <p>Post-durable-commit failures are split into two policies:
 * <ul>
 *   <li><b>Observer</b> failure (entity-manager close, an after-commit callback, async-event submission)
 *       — the commit is already durable and the after-commit callbacks have run, so it is LOGGED and NOT
 *       propagated: the REST wire contract is preserved (no false 500). Handled by {@link #complete} and
 *       {@link #closeQuietly}.</li>
 *   <li><b>Fatal-to-continuation</b> failure — the checkpoint's re-begin failing after tx1's durable
 *       commit, or the favorite recovery's re-begin failing after its deliberate tx1 rollback. There is
 *       no usable transaction to continue in: preserve tx1's durable outcome (do NOT relabel it), close
 *       the orphaned entity manager, and PROPAGATE so the caller aborts. Handled by
 *       {@link #beginContinuation}.</li>
 * </ul>
 */
public final class TransactionBoundary {
    private static final Logger log = LoggerFactory.getLogger(TransactionBoundary.class);

    /**
     * Durable state of a finalized transaction frame.
     */
    public enum DurableState {
        COMMITTED,
        ROLLED_BACK,
        IN_DOUBT
    }

    /**
     * Result of attempting a commit or rollback: the classified durable state and the throwable, if the
     * boundary operation itself threw (a commit or rollback failure). The throwable is returned rather
     * than rethrown so the caller keeps control of propagation ordering (an original work failure must
     * stay primary over a secondary rollback failure).
     */
    public static final class ClassifiedOutcome {
        private final DurableState state;
        private final Throwable failure;

        private ClassifiedOutcome(DurableState state, Throwable failure) {
            this.state = state;
            this.failure = failure;
        }

        public DurableState getState() {
            return state;
        }

        public Throwable getFailure() {
            return failure;
        }
    }

    /**
     * Outcome of dispatching a frame's completion: the durable state plus the first deferred (observer)
     * failure, if a callback or event step threw after the transaction's durability was already decided.
     */
    public static final class Outcome {
        private final DurableState durableState;
        private final Throwable deferredFailure;

        private Outcome(DurableState durableState, Throwable deferredFailure) {
            this.durableState = durableState;
            this.deferredFailure = deferredFailure;
        }

        public DurableState getDurableState() {
            return durableState;
        }

        public boolean isCommitted() {
            return durableState == DurableState.COMMITTED;
        }

        public Throwable getDeferredFailure() {
            return deferredFailure;
        }
    }

    /**
     * Result of finalizing an owned transaction (work + commit/rollback + completion dispatch): the
     * durable state, and the exception the owner must propagate (null when committed).
     */
    public static final class OwnedResult {
        private final DurableState state;
        private final Throwable propagate;

        private OwnedResult(DurableState state, Throwable propagate) {
            this.state = state;
            this.propagate = propagate;
        }

        public DurableState getState() {
            return state;
        }

        public boolean isCommitted() {
            return state == DurableState.COMMITTED;
        }

        public Throwable getPropagate() {
            return propagate;
        }
    }

    private TransactionBoundary() {
        // Utility class.
    }

    /**
     * Attempts to commit the transaction, classifying the outcome. {@link DurableState#COMMITTED} if
     * {@code commit()} returns; {@link DurableState#IN_DOUBT} if it throws (the database may have
     * committed anyway, so the caller must NOT treat this as a clean rollback). The commit failure is
     * returned on the outcome, never swallowed.
     *
     * @param tx transaction to commit
     * @return the classified outcome
     */
    public static ClassifiedOutcome tryCommit(EntityTransaction tx) {
        try {
            tx.commit();
            return new ClassifiedOutcome(DurableState.COMMITTED, null);
        } catch (RuntimeException | Error e) {
            log.error("Transaction commit failed; the durable outcome is IN_DOUBT (the database may have committed anyway)", e);
            return new ClassifiedOutcome(DurableState.IN_DOUBT, e);
        }
    }

    /**
     * Attempts to roll the entity manager's active transaction back, classifying the outcome.
     * {@link DurableState#ROLLED_BACK} if the rollback succeeds (or there was nothing active to roll
     * back); {@link DurableState#IN_DOUBT} if {@code rollback()} throws (the rollback is unconfirmed, so
     * the caller must NOT run after-rollback compensation).
     *
     * @param em entity manager whose active transaction should be rolled back
     * @return the classified outcome
     */
    public static ClassifiedOutcome tryRollback(EntityManager em) {
        if (em == null || !em.isOpen()) {
            return new ClassifiedOutcome(DurableState.ROLLED_BACK, null);
        }
        EntityTransaction tx = em.getTransaction();
        if (tx == null || !tx.isActive()) {
            return new ClassifiedOutcome(DurableState.ROLLED_BACK, null);
        }
        try {
            tx.rollback();
            return new ClassifiedOutcome(DurableState.ROLLED_BACK, null);
        } catch (RuntimeException | Error e) {
            log.error("Transaction rollback failed; the durable outcome is IN_DOUBT (the rollback is unconfirmed)", e);
            return new ClassifiedOutcome(DurableState.IN_DOUBT, e);
        }
    }

    /**
     * Finalizes an OWNED transaction after its work has been attempted: commit (on success) or rollback
     * (on failure), classify the durable state, close the entity manager, and dispatch the frame's
     * completion. Does NOT rethrow — the returned {@link OwnedResult} tells the owner what (if anything)
     * to propagate — and does NOT touch the frame stack or the thread-local context, which the owner
     * manages (base-frame cleanup for a background transaction, frame pop for a nested one).
     *
     * @param em entity manager owning the transaction
     * @param tx the (begun) transaction
     * @param workOk whether the work completed without throwing
     * @param workFailure the work's exception when {@code workOk} is false (else null)
     * @return the durable state and the exception the owner must propagate (null when committed)
     */
    public static OwnedResult finalizeOwned(EntityManager em, EntityTransaction tx, boolean workOk, Throwable workFailure) {
        DurableState state;
        Throwable propagate;
        if (workOk) {
            ClassifiedOutcome co = tryCommit(tx);
            state = co.getState();
            // COMMITTED -> nothing to propagate. IN_DOUBT (commit threw) -> propagate the commit failure.
            propagate = state == DurableState.COMMITTED ? null : co.getFailure();
        } else {
            // Work threw: roll back. The original work failure stays PRIMARY; a rollback failure is
            // secondary (logged inside tryRollback) and only downgrades the state to IN_DOUBT.
            ClassifiedOutcome co = tryRollback(em);
            state = co.getState();
            propagate = workFailure;
        }

        closeQuietly(em);
        complete(state);
        return new OwnedResult(state, propagate);
    }

    /**
     * Runs {@code work} on a NEW frame installed with {@code freshEm} (isolated completion + async-event
     * scope), finalizes that frame, and GUARANTEES the frame is popped and {@code requestEm} restored —
     * even if closing the fresh manager throws (a RuntimeException, or an Error, which {@link
     * #closeQuietly} deliberately lets propagate). The pop + restore live in their OWN finally nested
     * under the close, so a close failure can never strand the nested frame on a pooled thread. Used by
     * the OIDC fresh-transaction path.
     *
     * @param freshEm the fresh entity manager to own (installed in the new frame)
     * @param requestEm the enclosing request's entity manager to restore after popping
     * @param work the work to run on the fresh manager; its result is returned on a durable commit
     * @return the work's result when the fresh transaction durably committed
     */
    public static <T> T finalizeOwnedInNewFrame(EntityManager freshEm, EntityManager requestEm,
                                                Function<EntityManager, T> work) {
        ThreadLocalContext context = ThreadLocalContext.get();
        context.pushFrame(freshEm);
        try {
            EntityTransaction tx = freshEm.getTransaction();
            boolean workOk = false;
            Throwable workFailure = null;
            T result = null;
            try {
                tx.begin();
                result = work.apply(freshEm);
                workOk = true;
            } catch (RuntimeException | Error e) {
                workFailure = e;
            }

            OwnedResult owned = finalizeOwned(freshEm, tx, workOk, workFailure);
            if (owned.isCommitted()) {
                return result;
            }
            propagate(owned.getPropagate());
            throw new IllegalStateException("unreachable");
        } finally {
            // The pop + restore run in their own finally so they happen unconditionally, even if the
            // backstop close throws (e.g. a getTransaction() failure left the manager open and its
            // close/isOpen throws an Error). Every push has a guaranteed matching pop.
            try {
                closeQuietly(freshEm);
            } finally {
                context.popFrame();
                context.setEntityManager(requestEm);
            }
        }
    }

    /**
     * Dispatches the CURRENT frame's completion for the given durable state. Never throws — any observer
     * (post-durable) failure is captured on the returned outcome:
     * <ul>
     *   <li>COMMITTED: run after-commit callbacks, fire queued events, run after-completion callbacks;</li>
     *   <li>ROLLED_BACK: run after-rollback callbacks, discard queued events, run after-completion callbacks;</li>
     *   <li>IN_DOUBT: run NO callbacks and neither fire nor discard the events (their state is unknown) —
     *       just log. The frame is discarded by the owner afterwards, so the events do not fire.</li>
     * </ul>
     *
     * @param state the durable state the caller has established
     * @return the outcome, carrying the first observer failure if a completion step threw
     */
    public static Outcome complete(DurableState state) {
        ThreadLocalContext context = ThreadLocalContext.get();
        context.markCurrentFrameCompleting();
        try {
            // IN_DOUBT — either asked for directly, or the frame was already marked terminal by an
            // in-frame boundary whose commit/rollback threw. Run NO callbacks and neither fire nor
            // discard the queued events (their durability is unknown). Mark the frame terminal so a
            // later completion by the enclosing owner is also a no-op (no double dispatch / wrong
            // compensation on a transaction of unknown state).
            if (state == DurableState.IN_DOUBT || context.isCurrentFrameInDoubt()) {
                log.error("Transaction outcome IN_DOUBT: running no completion callbacks and neither firing nor "
                        + "discarding the queued async events (their durability is unknown)");
                context.markCurrentFrameInDoubt();
                return new Outcome(DurableState.IN_DOUBT, null);
            }

            boolean committed = state == DurableState.COMMITTED;
            Throwable deferredFailure = context.getCompletionRegistry().dispatchOutcome(committed);
            deferredFailure = fireOrDiscard(context, committed, deferredFailure);

            // After-completion callbacks run last; they may themselves queue events (or register more
            // callbacks). Re-run the fire/discard so anything an after-completion callback added is
            // drained within THIS same completion rather than stranded on the frame about to be popped.
            Throwable completionFailure = context.getCompletionRegistry().dispatchCompletion();
            if (deferredFailure == null) {
                deferredFailure = completionFailure;
            }
            deferredFailure = fireOrDiscard(context, committed, deferredFailure);

            return new Outcome(state, deferredFailure);
        } finally {
            context.markCurrentFrameCompleted();
        }
    }

    /**
     * Fires (committed) or discards (rolled back) the current frame's queued events, capturing the first
     * observer failure. Never propagates a RuntimeException/Exception (post-outcome observer failure);
     * an Error is not caught and propagates.
     */
    private static Throwable fireOrDiscard(ThreadLocalContext context, boolean committed, Throwable deferredFailure) {
        try {
            if (committed) {
                context.fireAllAsyncEvents();
            } else {
                context.discardAsyncEvents();
            }
        } catch (RuntimeException e) {
            log.error("Failed to {} the queued async events during transaction completion",
                    committed ? "fire" : "discard", e);
            if (deferredFailure == null) {
                deferredFailure = e;
            }
        }
        return deferredFailure;
    }

    /**
     * Rotates the current frame at a boundary where a single entity manager spans several transactions:
     * finalizes the frame with {@code state} (firing or discarding the finished transaction's events and
     * running its callbacks), then opens a fresh completion + async-event scope on the SAME entity
     * manager for the next transaction. Shared by the checkpoint commit and the favorite-recovery fresh
     * transaction so the rotation logic lives in exactly one place.
     *
     * @param state the durable state of the transaction being rotated out
     * @return the outcome of finalizing the rotated-out transaction
     */
    public static Outcome rotate(DurableState state) {
        Outcome outcome = complete(state);
        ThreadLocalContext.get().resetCurrentFrame();
        return outcome;
    }

    /**
     * Begins the continuation transaction on {@code tx} after a rotation. A failure here is
     * FATAL-TO-CONTINUATION: tx1's durable outcome has already been established and preserved, but there
     * is now no usable transaction to continue in (later writes would run untransacted and
     * {@code addAsyncEvent} would target a dead frame). So the orphaned entity manager is closed and the
     * failure is PROPAGATED — the caller must abort. This is deliberately different from the observer
     * policy of {@link #complete} / {@link #closeQuietly}, which log and continue.
     *
     * @param em entity manager owning the transaction (closed here on failure)
     * @param tx the transaction to (re-)begin
     */
    public static void beginContinuation(EntityManager em, EntityTransaction tx) {
        try {
            tx.begin();
        } catch (RuntimeException | Error e) {
            log.error("Failed to begin the continuation transaction after a rotation; closing the orphaned entity "
                    + "manager and aborting (tx1's durable outcome is preserved)", e);
            closeQuietly(em);
            throw e;
        }
    }

    /**
     * Closes the entity manager if it is open, swallowing and logging any error — a close failure after
     * the transaction's durability is decided is an observer failure that must not propagate.
     *
     * @param em entity manager to close (may be null or already closed)
     */
    public static void closeQuietly(EntityManager em) {
        if (em == null) {
            return;
        }
        // Guard BOTH isOpen() and close() inside the try: a RuntimeException from either (after the
        // transaction's durability is already decided) is an observer failure that must be logged, not
        // propagated — otherwise it could turn a committed request into a 500, convert a committed OIDC
        // provisioning into a failure, or skip the caller's cleanup. Only RuntimeException/Exception is
        // suppressed; an Error (OOM, etc.) is NOT caught and propagates.
        try {
            if (em.isOpen()) {
                em.close();
            }
        } catch (RuntimeException e) {
            log.error("Error closing entity manager (suppressed post-outcome observer failure)", e);
        }
    }

    /**
     * Rethrows an unchecked throwable as-is (the transaction owners only ever carry {@link
     * RuntimeException} / {@link Error} originating from user work or the JPA provider). A checked
     * throwable — which cannot arise on these paths — is wrapped defensively.
     *
     * @param t the throwable to propagate
     */
    public static void propagate(Throwable t) {
        if (t instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (t instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Unexpected checked exception on a transaction owner path", t);
    }
}
