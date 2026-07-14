package com.sismics.docs.core.dao;

import com.sismics.BaseTest;
import com.sismics.docs.core.util.FakeTransactionSupport;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives the REAL {@link FavoriteDao#beginFreshTransaction(EntityManager)} recovery boundary with a
 * controllable transaction (a dependency), so an unconfirmed rollback is exercised through the actual
 * DAO code path rather than the boundary helper in isolation.
 */
public class TestFavoriteDaoRecovery extends BaseTest {

    @BeforeEach
    public void setUp() {
        ThreadLocalContext.cleanup();
    }

    @AfterEach
    public void tearDown() {
        ThreadLocalContext.cleanup();
    }

    // (Issue 2) beginFreshTransaction routes the rollback through the three-state classifier: a thrown
    // rollback is IN_DOUBT — the frame is marked terminal (so the enclosing request owner does not run
    // after-rollback compensation on an unconfirmed state) and the failure propagates so create() aborts.
    @Test
    public void rollbackInDoubtMarksTerminalAndPropagates() {
        FakeTransactionSupport.FakeTx tx = new FakeTransactionSupport.FakeTx();
        tx.active = true;
        tx.rollbackThrows = true;
        boolean[] closed = {false};
        boolean[] open = {true};
        EntityManager em = FakeTransactionSupport.fakeEntityManager(tx, closed, open);

        AtomicInteger afterRollback = new AtomicInteger();
        ThreadLocalContext.get().getCompletionRegistry().registerAfterRollback(afterRollback::incrementAndGet);

        Assertions.assertThrows(RuntimeException.class,
                () -> FavoriteDao.beginFreshTransaction(em),
                "an unconfirmed rollback during favorite recovery must propagate so create() aborts");

        Assertions.assertTrue(ThreadLocalContext.get().isCurrentFrameInDoubt(),
                "an unconfirmed rollback marks the frame terminal IN_DOUBT");
        Assertions.assertEquals(0, afterRollback.get(),
                "no after-rollback compensation runs on an unconfirmed rollback");
        Assertions.assertEquals(1, tx.rollbackCount, "exactly one rollback was attempted");
    }
}
