package com.sismics.docs.core.util;

import com.sismics.BaseTest;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tests for {@link TransactionUtil#handle(Runnable)} on its OWNER path — the path taken when the
 * calling thread is NOT already inside a transactional context, so {@code handle} creates, begins, and
 * finalizes its own entity manager — and for the static checkpoint {@link TransactionUtil#commit()}.
 *
 * <p>INVERSION FROM THE A0 CHARACTERIZATION: {@link #handleRethrowsRunnableException()} previously
 * pinned the known-broken behavior that the owner path SWALLOWED a runnable exception (logged and
 * returned normally). Phase A1 corrects that lifecycle so the exception is RETHROWN — a rolled-back
 * unit of work must surface its failure to the caller (the background file-processing path was
 * reporting success and deleting the plaintext temp file for work that never committed). The
 * assertion is therefore inverted on purpose from {@code assertDoesNotThrow} to {@code assertThrows}.</p>
 */
public class TestTransactionUtilCharacterization extends BaseTest {

    @BeforeEach
    public void clearContext() {
        ThreadLocalContext.cleanup();
    }

    @AfterEach
    public void cleanupContext() {
        ThreadLocalContext.cleanup();
    }

    @Test
    public void handleRethrowsRunnableException() {
        AtomicBoolean ran = new AtomicBoolean(false);
        IllegalStateException thrown = new IllegalStateException("runnable failure must not be swallowed");

        // The owner path must PROPAGATE the runnable's exception (was swallowed pre-A1): it rolls back,
        // finalizes the frame, cleans up, then rethrows the ORIGINAL exception.
        IllegalStateException caught = Assertions.assertThrows(IllegalStateException.class,
                () -> TransactionUtil.handle(() -> {
                    ran.set(true);
                    throw thrown;
                }));
        Assertions.assertSame(thrown, caught, "the caller must see the original runnable exception, unwrapped");

        Assertions.assertTrue(ran.get(), "the runnable must have executed on the owner path");

        // The finally cleans up the thread-local context, so a fresh context with a null entity manager
        // remains — evidence the owner (rollback) path was taken and finalized.
        EntityManager remaining = ThreadLocalContext.get().peekEntityManager();
        Assertions.assertNull(remaining,
                "handle() must clean up the thread-local entity manager after a failed runnable");
    }

    // (f) A failure in the runnable, combined with a failing after-rollback callback, must keep the
    // ORIGINAL runnable exception as the propagated (primary) exception — the secondary callback
    // failure is suppressed, never masking the real cause.
    @Test
    public void runnableFailureWithFailingRollbackCallbackKeepsOriginalPrimary() {
        RuntimeException original = new IllegalStateException("original runnable failure");

        RuntimeException caught = Assertions.assertThrows(RuntimeException.class,
                () -> TransactionUtil.handle(() -> {
                    ThreadLocalContext.get().getCompletionRegistry().registerAfterRollback(() -> {
                        throw new IllegalStateException("secondary rollback-callback failure");
                    });
                    throw original;
                }));

        Assertions.assertSame(original, caught,
                "the original runnable exception must remain primary; the failing rollback callback must be suppressed");
    }

    // (g) After the owner path commits but an after-commit callback throws, the failure must NOT
    // propagate (the commit is durable) and NOTHING may leak into the next unit of work on this thread.
    @Test
    public void postCommitCallbackFailureDoesNotPropagateOrLeak() {
        // A throwing after-commit callback runs after a durable commit: it is logged and suppressed, so
        // handle() returns normally.
        Assertions.assertDoesNotThrow(() -> TransactionUtil.handle(() ->
                ThreadLocalContext.get().getCompletionRegistry().registerAfterCommit(() -> {
                    throw new IllegalStateException("post-commit callback failure");
                })));

        // The context was cleaned up: a fresh context with no entity manager and an empty registry
        // remains, so no stale callback can fire during the next unit of work.
        Assertions.assertNull(ThreadLocalContext.get().peekEntityManager(),
                "the context must be cleaned up after a post-commit callback failure");

        AtomicBoolean secondRan = new AtomicBoolean(false);
        Assertions.assertDoesNotThrow(() -> TransactionUtil.handle(() -> secondRan.set(true)));
        Assertions.assertTrue(secondRan.get(),
                "the next unit of work runs cleanly; no stale callback leaked from the previous one");
    }

    // (h) The static checkpoint commit() fails fast (a clear IllegalStateException, not an opaque NPE)
    // when there is no open entity manager or no active transaction to commit.
    //
    // NOTE ON COVERAGE: handle()'s sibling fail-fast — when EMF.get().createEntityManager() returns
    // null — is implemented (it throws before installing anything into the context) but is not pinned
    // by its own test: forcing the static EMF factory to hand back a null manager requires static
    // mocking of EMF, and this project carries no mocking framework (mocking a collaborator that way
    // would also be brittle, as recorded in the A0 inventory). commit()'s validation exercises the
    // same fail-fast contract on the reachable path.
    @Test
    public void commitFailsFastWithoutAnEntityManager() {
        ThreadLocalContext.cleanup();
        Assertions.assertThrows(IllegalStateException.class, TransactionUtil::commit,
                "commit() must fail fast when no entity manager is installed");
    }

    @Test
    public void commitFailsFastWithAClosedEntityManager() {
        EntityManager closed = EMF.get().createEntityManager();
        closed.close();
        ThreadLocalContext.get().setEntityManager(closed);
        try {
            Assertions.assertThrows(IllegalStateException.class, TransactionUtil::commit,
                    "commit() must fail fast when the installed entity manager is closed");
        } finally {
            ThreadLocalContext.cleanup();
        }
    }

    @Test
    public void commitFailsFastWithoutAnActiveTransaction() {
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext.get().setEntityManager(em);
        try {
            Assertions.assertThrows(IllegalStateException.class, TransactionUtil::commit,
                    "commit() must fail fast when there is no active transaction to commit");
        } finally {
            TransactionBoundary.closeQuietly(em);
            ThreadLocalContext.cleanup();
        }
    }
}
