package com.sismics.util.context;

import com.google.common.collect.Lists;
import com.sismics.docs.core.model.context.AppContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.EntityManager;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Context associated to a unit of work, stored in a ThreadLocal.
 *
 * <p>The context holds a STACK of transaction frames. Each frame bundles the entity manager currently
 * installed, the completion callbacks registered against it, and the async events queued while it is
 * the active frame. Frames are stacked so a nested transaction owner (e.g. a fresh OIDC provisioning
 * transaction opened inside a request) gets its own completion + async-event scope: firing or
 * discarding the nested frame's events cannot touch the enclosing frame's queue, and vice-versa. Every
 * accessor targets the CURRENT (top) frame, so the thirty-odd {@code addAsyncEvent} /
 * {@code getEntityManager} call sites keep working unchanged.</p>
 *
 * @author jtremeaux
 */
public class ThreadLocalContext {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(ThreadLocalContext.class);

    /**
     * ThreadLocal to store the context.
     */
    private static final ThreadLocal<ThreadLocalContext> threadLocalContext = new ThreadLocal<>();

    /**
     * Explicit lifecycle of a transaction frame. Owner discovery must NOT treat "an entity manager is
     * installed" as "a transaction is active": a frame can carry a closed manager or one whose
     * transaction has not begun / has already completed.
     * <ul>
     *   <li>STARTING — created, no owner has begun a transaction on it yet;</li>
     *   <li>ACTIVE — an entity manager is installed and the owner intends a live transaction;</li>
     *   <li>COMPLETING — the completion dispatcher is currently running this frame's callbacks / events;</li>
     *   <li>COMPLETED — its completion has been dispatched.</li>
     * </ul>
     */
    public enum FrameState {
        STARTING,
        ACTIVE,
        COMPLETING,
        COMPLETED
    }

    /**
     * One transaction frame: the installed entity manager, its completion registry, its queue of async
     * events, and its lifecycle state. A new frame is pushed by a nested transaction owner and popped
     * when it finalizes.
     */
    private static final class Frame {
        private EntityManager entityManager;
        private TransactionCompletionRegistry completionRegistry = new TransactionCompletionRegistry();
        private List<Object> asyncEventList = Lists.newArrayList();
        private FrameState lifecycle = FrameState.STARTING;
        /**
         * Terminal IN_DOUBT marker: set when an in-frame boundary (a checkpoint commit, or the
         * favorite-recovery rollback) could neither confirm a commit nor a rollback. Once set, any
         * subsequent completion of this frame is a no-op — the enclosing owner must NOT re-finalize it
         * (re-firing / discarding / compensating a transaction of unknown durability).
         */
        private boolean terminalInDoubt;
    }

    /**
     * Stack of transaction frames. Never empty: the constructor seeds a base frame, and {@link
     * #popFrame()} never removes it.
     */
    private final Deque<Frame> frames = new ArrayDeque<>();

    /**
     * Private constructor.
     */
    private ThreadLocalContext() {
        frames.push(new Frame());
    }

    /**
     * Returns an instance of this thread context.
     *
     * @return Thread local context
     */
    public static ThreadLocalContext get() {
        ThreadLocalContext context = threadLocalContext.get();
        if (context == null) {
            context = new ThreadLocalContext();
            threadLocalContext.set(context);
        }
        return context;
    }

    /**
     * Cleans up the instance of this thread context.
     */
    public static void cleanup() {
        threadLocalContext.set(null);
    }

    private Frame currentFrame() {
        return frames.peek();
    }

    /**
     * Getter of the current frame's entityManager. This is the MUTATING getter: on an open manager it
     * flushes then clears (disabling the L1 cache) before returning it. Kept for every call site except
     * the two owner-discovery inspections, which use {@link #peekEntityManager()}.
     *
     * @return entityManager
     */
    public EntityManager getEntityManager() {
        EntityManager entityManager = currentFrame().entityManager;
        if (entityManager != null && entityManager.isOpen()) {
            // This disables the L1 cache
            entityManager.flush();
            entityManager.clear();
        }
        return entityManager;
    }

    /**
     * Side-effect-free read of the current frame's entity manager: it does NOT flush or clear, so it
     * can inspect whether a transactional context already exists (or capture a manager to restore
     * later) without forcing the pending work of an unrelated caller to the database.
     *
     * @return entityManager, possibly null or closed, untouched
     */
    public EntityManager peekEntityManager() {
        return currentFrame().entityManager;
    }

    /**
     * Setter of the current frame's entityManager. Installing a non-null manager moves a STARTING frame
     * to ACTIVE (an owner intends a live transaction on it).
     *
     * @param entityManager entityManager
     */
    public void setEntityManager(EntityManager entityManager) {
        Frame frame = currentFrame();
        frame.entityManager = entityManager;
        if (entityManager != null && frame.lifecycle == FrameState.STARTING) {
            frame.lifecycle = FrameState.ACTIVE;
        }
    }

    /**
     * Returns the current frame's completion registry, so a caller can schedule after-commit /
     * after-rollback / after-completion work bound to the active transaction.
     *
     * @return completion registry of the current frame
     */
    public TransactionCompletionRegistry getCompletionRegistry() {
        return currentFrame().completionRegistry;
    }

    /**
     * Pushes a new frame (its own entity manager, completion registry, and async-event queue) onto the
     * stack, making it the current frame. Used by a nested transaction owner; balanced by {@link
     * #popFrame()}.
     *
     * @param entityManager entity manager to install in the new frame
     */
    public void pushFrame(EntityManager entityManager) {
        Frame frame = new Frame();
        frame.entityManager = entityManager;
        if (entityManager != null) {
            frame.lifecycle = FrameState.ACTIVE;
        }
        frames.push(frame);
    }

    /**
     * Pops the current frame, restoring the enclosing frame (its entity manager, registry, and queued
     * events intact). The base frame is never popped, so an unbalanced pop is a no-op rather than
     * leaving the thread with no frame.
     */
    public void popFrame() {
        if (frames.size() > 1) {
            frames.pop();
        }
    }

    /**
     * Opens a fresh completion + async-event scope on the CURRENT frame, keeping the same entity
     * manager. Used at a transaction rotation (a checkpoint commit, or the favorite-recovery fresh
     * transaction) where one entity manager spans several transactions: the finalized transaction's
     * callbacks and events have already been dispatched, and the next transaction starts with an empty
     * registry and queue.
     */
    public void resetCurrentFrame() {
        Frame frame = currentFrame();
        frame.completionRegistry = new TransactionCompletionRegistry();
        frame.asyncEventList = Lists.newArrayList();
        frame.lifecycle = FrameState.ACTIVE;
        frame.terminalInDoubt = false;
    }

    /**
     * Marks the current frame terminally IN_DOUBT: a subsequent completion of this frame does nothing.
     * Set by an in-frame boundary (checkpoint commit / favorite-recovery rollback) whose commit or
     * rollback threw, so the enclosing owner does not re-finalize a transaction of unknown durability.
     */
    public void markCurrentFrameInDoubt() {
        currentFrame().terminalInDoubt = true;
    }

    /**
     * @return true if the current frame has been marked terminally IN_DOUBT.
     */
    public boolean isCurrentFrameInDoubt() {
        return currentFrame().terminalInDoubt;
    }

    /**
     * @return the number of frames currently on the stack (1 = only the base frame). Exposed for tests
     * that assert an owner balanced its push/pop and did not leak a nested frame.
     */
    public int frameDepth() {
        return frames.size();
    }

    /**
     * Marks the current frame as COMPLETING (its completion dispatcher is running). Set by the boundary
     * around a completion dispatch so re-entrant work can observe it via {@link #isCurrentFrameCompleting()}.
     */
    public void markCurrentFrameCompleting() {
        currentFrame().lifecycle = FrameState.COMPLETING;
    }

    /**
     * Marks the current frame as COMPLETED (its completion has been dispatched).
     */
    public void markCurrentFrameCompleted() {
        currentFrame().lifecycle = FrameState.COMPLETED;
    }

    /**
     * @return true while the current frame's completion is being dispatched. A callback or async
     * subscriber running during dispatch can use this to detect re-entrancy; an event it queues is
     * appended to the same frame and drained within the SAME completion (see {@link #fireAllAsyncEvents()}),
     * never silently dropped onto a frame about to be popped.
     */
    public boolean isCurrentFrameCompleting() {
        return currentFrame().lifecycle == FrameState.COMPLETING;
    }

    /**
     * Add an async event to the current frame's queue, to be fired after that frame's transaction
     * durably commits.
     *
     * @param asyncEvent Async event
     */
    public void addAsyncEvent(Object asyncEvent) {
        currentFrame().asyncEventList.add(asyncEvent);
    }

    /**
     * Fire all of the current frame's pending async events.
     *
     * <p>Called only by the completion dispatcher, and only when the frame durably committed, so the
     * queued events describe modifications that actually reached the database.</p>
     */
    public void fireAllAsyncEvents() {
        // Drain by repeatedly removing the head rather than iterating: a synchronous subscriber (or an
        // after-commit / after-completion callback) that queues another event onto THIS frame during
        // dispatch appends to the same list, and the append-and-drain loop fires it within the same
        // completion instead of leaving it stranded on a frame about to be popped (and instead of a
        // ConcurrentModificationException).
        List<Object> asyncEventList = currentFrame().asyncEventList;
        while (!asyncEventList.isEmpty()) {
            Object asyncEvent = asyncEventList.remove(0);
            try {
                AppContext.getInstance().getAsyncEventBus().post(asyncEvent);
            } catch (RuntimeException e) {
                // Per-event resilience: one failed submission must not strand every LATER committed
                // event (they belong to a durable transaction and would otherwise be lost when the frame
                // pops). Log and continue the drain. Errors (OOM, etc.) are NOT caught — they propagate.
                log.error("Failed to submit a committed async event; continuing the drain of the remaining events", e);
            }
        }
    }

    /**
     * Discard the current frame's pending async events without firing them.
     *
     * <p>Called only by the completion dispatcher, and only when the frame rolled back (or its commit
     * failed): the queued events describe modifications that never reached the database, so firing them
     * would physically delete file bytes or mutate the Lucene index for changes that did not commit.</p>
     */
    public void discardAsyncEvents() {
        currentFrame().asyncEventList.clear();
    }
}
