package com.sismics.docs.core.util;

import com.sismics.BaseTest;
import com.sismics.util.context.ThreadLocalContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.persistence.EntityManager;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Characterization of {@link TransactionUtil#handle(Runnable)} on its OWNER path — the path taken
 * when the calling thread is NOT already inside a transactional context, so {@code handle} creates,
 * begins, and finalizes its own entity manager.
 *
 * <p>These tests deliberately pin CURRENT behavior, including a known-broken path: the owner path
 * SWALLOWS an exception thrown by the runnable (it logs and returns, it does not rethrow). A later
 * phase is expected to invert that, at which point {@link #handleSwallowsRunnableException()} is
 * updated on purpose. Recorded here so that inversion is a deliberate, reviewed change rather than a
 * silent regression.
 */
public class TestTransactionUtilCharacterization extends BaseTest {

    /**
     * The owner path is only reached when no open entity manager is installed on the thread. Clear
     * any context a previous test left behind so {@code handle} does not take the already-in-context
     * pass-through branch (which would propagate the exception and mask the swallow being characterized).
     */
    @BeforeEach
    public void clearContext() {
        ThreadLocalContext.cleanup();
    }

    @AfterEach
    public void cleanupContext() {
        ThreadLocalContext.cleanup();
    }

    @Test
    public void handleSwallowsRunnableException() {
        AtomicBoolean ran = new AtomicBoolean(false);

        // handle() must NOT propagate the runnable's exception on the owner path: it rolls back,
        // cleans up, and returns normally.
        Assertions.assertDoesNotThrow(() -> TransactionUtil.handle(() -> {
            ran.set(true);
            throw new IllegalStateException("characterization: runnable failure");
        }));

        Assertions.assertTrue(ran.get(), "the runnable must have executed on the owner path");

        // The catch block cleans up the thread-local context, so a fresh context with a null EM is
        // what remains — evidence the owner (rollback) path was taken rather than the pass-through.
        EntityManager remaining = ThreadLocalContext.get().getEntityManager();
        Assertions.assertNull(remaining,
                "handle() must clean up the thread-local entity manager after a failed runnable");
    }
}
