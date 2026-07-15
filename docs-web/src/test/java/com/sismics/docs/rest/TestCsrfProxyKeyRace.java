package com.sismics.docs.rest;

import com.sismics.docs.core.constant.ConfigType;
import com.sismics.util.csrf.CsrfTokenUtil;
import com.sismics.util.jpa.EMF;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Concurrent first-use of the CSRF proxy key ({@code getOrCreateProxyKey}) must be race-safe: two threads
 * seeding at once must NOT 500 on a duplicate-key insert (the no-rejection-in-default-config invariant),
 * must converge on the SAME persisted key, and must leave exactly ONE row. Uses the unique PK on
 * {@code T_CONFIG} as the deterministic collision point (one thread inserts, the other conflicts and
 * re-reads).
 */
public class TestCsrfProxyKeyRace extends BaseTransactionalTest {

    @Test
    public void concurrentFirstUseNeverFailsAndConverges() throws Exception {
        // Force genuine first-use: remove any key seeded by an earlier test in this JVM.
        deleteProxyKeyRow();
        Assertions.assertEquals(0, countProxyKeyRows(), "precondition: no proxy key row before the race");

        final int threadCount = 2;
        final CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<byte[]>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    // Each worker has its own (empty) ThreadLocalContext, so getOrCreateProxyKey takes the
                    // independent-frame seeding path. The barrier makes both enter it together.
                    barrier.await(10, TimeUnit.SECONDS);
                    return CsrfTokenUtil.getOrCreateProxyKey();
                }));
            }

            byte[] keyA = futures.get(0).get(30, TimeUnit.SECONDS);
            byte[] keyB = futures.get(1).get(30, TimeUnit.SECONDS);

            Assertions.assertNotNull(keyA);
            Assertions.assertNotNull(keyB);
            Assertions.assertArrayEquals(keyA, keyB,
                    "both concurrent first-use threads must converge on the same persisted key");
            Assertions.assertEquals(1, countProxyKeyRows(),
                    "concurrent first-use must persist exactly one key row (no duplicate-key 500)");
        } finally {
            pool.shutdownNow();
        }
    }

    private void deleteProxyKeyRow() {
        EntityManager em = EMF.get().createEntityManager();
        try {
            em.getTransaction().begin();
            em.createNativeQuery("delete from T_CONFIG where CFG_ID_C = 'CSRF_PROXY_KEY'").executeUpdate();
            em.getTransaction().commit();
        } finally {
            if (em.isOpen()) {
                em.close();
            }
        }
    }

    private long countProxyKeyRows() {
        EntityManager em = EMF.get().createEntityManager();
        try {
            em.getTransaction().begin();
            long count = ((Number) em.createNativeQuery(
                    "select count(*) from T_CONFIG where CFG_ID_C = '" + ConfigType.CSRF_PROXY_KEY.name() + "'")
                    .getSingleResult()).longValue();
            em.getTransaction().rollback();
            return count;
        } finally {
            if (em.isOpen()) {
                em.close();
            }
        }
    }
}
