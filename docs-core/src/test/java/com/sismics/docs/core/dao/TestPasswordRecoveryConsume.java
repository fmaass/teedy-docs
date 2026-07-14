package com.sismics.docs.core.dao;

import com.sismics.BaseTest;
import com.sismics.docs.core.model.jpa.PasswordRecovery;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;

/**
 * Tests the atomic single-use consumption of a password-recovery key. The concurrent case uses two REAL
 * transactions on separate connections so the row-lock / guarded-update semantics are genuinely exercised
 * (definitive on PostgreSQL; also verified here on the shared in-memory database).
 */
public class TestPasswordRecoveryConsume extends BaseTest {

    @BeforeEach
    public void setUp() {
        ThreadLocalContext.cleanup();
    }

    @AfterEach
    public void tearDown() {
        ThreadLocalContext.cleanup();
    }

    /**
     * Runs a unit of work in its own committed transaction on a fresh entity manager and thread-local
     * context.
     */
    private <T> T inTx(Callable<T> work) {
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext ctx = ThreadLocalContext.get();
        ctx.setEntityManager(em);
        em.getTransaction().begin();
        try {
            T result = work.call();
            em.getTransaction().commit();
            return result;
        } catch (Exception e) {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            throw new RuntimeException(e);
        } finally {
            em.close();
            ThreadLocalContext.cleanup();
        }
    }

    private String createRecovery(String username) {
        PasswordRecovery recovery = new PasswordRecovery();
        recovery.setUsername(username);
        return new PasswordRecoveryDao().create(recovery);
    }

    @Test
    public void keyCanBeConsumedOnlyOnce() {
        String key = inTx(() -> createRecovery("consume_once"));

        String first = inTx(() -> new PasswordRecoveryDao().consume(key));
        Assertions.assertEquals("consume_once", first, "the first consume wins and returns the username");

        String second = inTx(() -> new PasswordRecoveryDao().consume(key));
        Assertions.assertNull(second, "a consumed key cannot be consumed again");
    }

    @Test
    public void unknownKeyConsumeReturnsNull() {
        String result = inTx(() -> new PasswordRecoveryDao().consume("does-not-exist"));
        Assertions.assertNull(result);
    }

    @Test
    public void twoConcurrentConsumesOfSameKeyYieldExactlyOneWinner() throws Exception {
        String key = inTx(() -> createRecovery("race_target"));

        CyclicBarrier barrier = new CyclicBarrier(2);
        String[] results = new String[2];
        Throwable[] errors = new Throwable[2];

        Runnable[] runnables = new Runnable[2];
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            runnables[i] = () -> {
                EntityManager em = EMF.get().createEntityManager();
                try {
                    ThreadLocalContext ctx = ThreadLocalContext.get();
                    ctx.setEntityManager(em);
                    em.getTransaction().begin();
                    barrier.await();
                    String username = new PasswordRecoveryDao().consume(key);
                    em.getTransaction().commit();
                    results[idx] = username;
                } catch (Throwable t) {
                    errors[idx] = t;
                    if (em.getTransaction().isActive()) {
                        em.getTransaction().rollback();
                    }
                } finally {
                    em.close();
                    ThreadLocalContext.cleanup();
                }
            };
        }

        Thread t0 = new Thread(runnables[0]);
        Thread t1 = new Thread(runnables[1]);
        t0.start();
        t1.start();
        t0.join();
        t1.join();

        // Both threads must complete cleanly (no exception): the guarded consume never errors, it either wins
        // (returns the username) or loses (returns null).
        Assertions.assertNull(errors[0], "thread 0 must complete without error");
        Assertions.assertNull(errors[1], "thread 1 must complete without error");

        int winners = 0;
        int nullLosers = 0;
        for (int i = 0; i < 2; i++) {
            if (results[i] != null) {
                winners++;
            } else {
                nullLosers++;
            }
        }
        Assertions.assertEquals(1, winners,
                "exactly one of two concurrent consumes of the same key may succeed");
        Assertions.assertEquals(1, nullLosers,
                "the other concurrent consume must return a clean null, never a duplicate winner");

        // The key is inactive afterwards: a further consume finds nothing.
        String after = inTx(() -> new PasswordRecoveryDao().consume(key));
        Assertions.assertNull(after);
    }
}
