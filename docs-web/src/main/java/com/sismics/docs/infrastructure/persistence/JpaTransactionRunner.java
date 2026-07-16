package com.sismics.docs.infrastructure.persistence;

import com.sismics.docs.application.document.UnitOfWork;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * {@link UnitOfWork} over the A1 transaction primitives, and the SOLE class in the new packages that
 * touches {@link ThreadLocalContext}. {@code required}/{@code query} delegate to
 * {@link TransactionUtil#handle(Runnable)} (join-or-own); {@code afterCommit} and the
 * package-private {@link #queueEvent(Object)} register on the current frame, guarded by a fail-fast
 * active-transaction precondition so work can never be silently attached to a later transaction on
 * the thread.
 *
 * <p>Its {@code ThreadLocalContext} call surface is deliberately narrow — {@code TransactionUtil
 * .handle}, {@code peekEntityManager}, {@code getCompletionRegistry}, {@code addAsyncEvent},
 * {@code isCurrentFrameCompleting} — and never {@code TransactionUtil.commit} (this seed checkpoints
 * nothing) nor {@code getEntityManager} (the flushing getter).</p>
 */
public class JpaTransactionRunner implements UnitOfWork {

    @Override
    public void required(Runnable work) {
        TransactionUtil.handle(work);
    }

    @Override
    public <T> T required(Supplier<T> work) {
        AtomicReference<T> holder = new AtomicReference<>();
        TransactionUtil.handle(() -> holder.set(work.get()));
        return holder.get();
    }

    @Override
    public <T> T query(Supplier<T> read) {
        // The A1 layer has no read-only transaction type; a SELECT still needs a live transaction
        // under resource-local Hibernate. Same machinery as required(); read-only is an intent marker.
        return required(read);
    }

    @Override
    public void afterCommit(Runnable callback) {
        requireActiveTransaction();
        ThreadLocalContext.get().getCompletionRegistry().registerAfterCommit(callback);
    }

    /**
     * Queues an async event on the current frame, to fire only after that frame's transaction durably
     * commits. Package-private: the event publisher adapter is the only caller.
     *
     * @param event The async event
     * @throws IllegalStateException when no active transaction is installed, or the frame is completing
     */
    void queueEvent(Object event) {
        requireActiveTransaction();
        ThreadLocalContext.get().addAsyncEvent(event);
    }

    /**
     * Fails fast unless the current frame carries an open entity manager with an active transaction and
     * is not mid-completion — the same precondition {@code afterCommit} and {@code queueEvent} share.
     * Registering outside a live transaction would silently attach the work to a LATER transaction on
     * this thread.
     */
    private void requireActiveTransaction() {
        ThreadLocalContext context = ThreadLocalContext.get();
        if (context.isCurrentFrameCompleting()) {
            throw new IllegalStateException("Cannot register transaction work while the current frame is completing");
        }
        EntityManager em = context.peekEntityManager();
        if (em == null || !em.isOpen()) {
            throw new IllegalStateException("Cannot register transaction work: no open entity manager is installed");
        }
        EntityTransaction tx = em.getTransaction();
        if (tx == null || !tx.isActive()) {
            throw new IllegalStateException("Cannot register transaction work: no active transaction");
        }
    }
}
