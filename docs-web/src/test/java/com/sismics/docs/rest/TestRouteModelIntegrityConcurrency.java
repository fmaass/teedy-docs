package com.sismics.docs.rest;

import com.sismics.docs.core.constant.AclTargetType;
import com.sismics.docs.core.dao.GroupDao;
import com.sismics.docs.core.dao.RouteModelDao;
import com.sismics.docs.core.model.jpa.Group;
import com.sismics.docs.core.model.jpa.RouteModel;
import com.sismics.docs.core.util.RouteModelStepUtil;
import com.sismics.docs.core.util.SecurityUtil;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Fast, helper-level two-transaction concurrency tests for the group-first route-model integrity
 * protocol (#30/#31). Each scenario runs two threads, each on its OWN EntityManager +
 * transaction against the shared test database (all EMs from {@link EMF} connect to the same DB —
 * H2 {@code mem:docs} locally, real PostgreSQL in CI), interleaved with latches. Dialect-specific
 * bits (lock-timeout syntax) switch on {@link EMF#isDriverPostgresql()}; the pessimistic row-lock
 * queueing semantics the interleave relies on are the same on both. These drive the protocol's building blocks
 * (lock helpers + prepare/apply repair) directly; the RESOURCE-layer writers are exercised
 * end-to-end by {@link TestRouteModelIntegrityRestConcurrency}, whose requests go through the real
 * REST endpoints.
 *
 * <p>Interleave: the writer takes its group-existence snapshot, then signals; the rename waits
 * (bounded) for that signal before repairing + renaming + committing; the writer only writes after
 * the rename committed. WITHOUT the pessimistic GROUP lock the writer's snapshot happens pre-rename
 * and its (stale) write lands last — the committed blob names a group that no longer exists. WITH
 * the lock, the writer blocks inside the lock call until the rename commits, so its snapshot
 * correctly sees the group gone and no orphan is written. Verified during implementation by
 * temporarily removing {@code setLockMode} from the two ForUpdate DAO methods: both tests then fail
 * on the authoritative stored-blob assertion (verified by removing the locks and watching them fail).
 */
public class TestRouteModelIntegrityConcurrency {

    /** Bound so a lock that never releases fails the test instead of hanging the suite forever. */
    private static final long TIMEOUT_SECONDS = 20;

    private static <T> T inTx(Supplier<T> supplier) {
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext context = ThreadLocalContext.get();
        context.setEntityManager(em);
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        try {
            T result = supplier.get();
            tx.commit();
            return result;
        } catch (RuntimeException e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            throw e;
        } finally {
            if (em.isOpen()) {
                em.close();
            }
            ThreadLocalContext.cleanup();
        }
    }

    /**
     * Bound this session's lock wait well above the test's coordination windows. Dialect-aware
     * (dialect from {@link EMF#isDriverPostgresql()}, the same hibernate.properties-driven detector
     * production DialectUtil uses): H2's default LOCK_TIMEOUT is 1000ms — too short, the writer
     * legitimately waits on the group lock while the rename runs its bounded snapshot-await;
     * PostgreSQL's default lock_timeout is 0 (wait forever) — the explicit bound keeps a broken
     * interleave from hanging to the join timeout. Session-scoped; applies to the connection the
     * surrounding transaction holds. Note the different SET syntax: H2 takes a bare value, PG
     * requires {@code = value} (milliseconds in both cases).
     */
    private static void raiseLockTimeout() {
        String sql = EMF.isDriverPostgresql() ? "SET lock_timeout = 30000" : "SET LOCK_TIMEOUT 30000";
        ThreadLocalContext.get().getEntityManager().createNativeQuery(sql).executeUpdate();
    }

    private static String stepsForGroup(String groupName) {
        return "[{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\",\"actions\":[]}]," +
                "\"target\":{\"name\":\"" + groupName + "\",\"type\":\"GROUP\"},\"name\":\"Review\"}]";
    }

    private static String storedSteps(String modelId) {
        return inTx(() -> new RouteModelDao().getActiveById(modelId).getSteps());
    }

    private static String storedGroupTargetName(String modelId) {
        List<String> names = RouteModelStepUtil.parseGroupTargetNames(storedSteps(modelId));
        Assertions.assertEquals(1, names.size(), "Fixture blob must carry exactly one GROUP target");
        return names.get(0);
    }

    /**
     * A route-model update that re-writes a blob referencing group G runs concurrently with a rename
     * of G. The committed blob must always name a live group. Without the group lock, the update
     * snapshots G-still-exists pre-rename and writes the orphaned old name last.
     */
    @Test
    public void updateVsRename() throws Exception {
        inTx(() -> {
            new GroupDao().create(new Group().setName("convgroup"), "admin");
            return null;
        });
        String modelId = inTx(() ->
                new RouteModelDao().create(new RouteModel().setName("Conv model").setSteps(stepsForGroup("convgroup")), "admin"));

        runWriterVsRename("convgroup", "convgroup2",
                (writerReachedLock, writerTookSnapshot, renameCommitted, writerError) -> new Thread(() -> {
                    EntityManager em = EMF.get().createEntityManager();
                    ThreadLocalContext.get().setEntityManager(em);
                    EntityTransaction tx = em.getTransaction();
                    tx.begin();
                    try {
                        raiseLockTimeout();
                        String steps = stepsForGroup("convgroup");
                        writerReachedLock.countDown();
                        // Group-first lock (the load-bearing serialization point). WITH the lock this
                        // BLOCKS until the rename commits, so the existence snapshot below happens
                        // post-rename — G is gone → no orphan is written. WITHOUT the lock it returns
                        // immediately and the snapshot sees the still-uncommitted G as present.
                        RouteModelStepUtil.lockGroupsByName(RouteModelStepUtil.parseGroupTargetNames(steps));
                        new RouteModelDao().getActiveByIdForUpdate(modelId);
                        Group g = new GroupDao().getActiveByName("convgroup");
                        // Signal the snapshot deterministically (replaces a timing heuristic), then
                        // serialize the actual write to land AFTER the rename commit, so a lock-less
                        // race deterministically leaves this stale write as the final blob.
                        writerTookSnapshot.countDown();
                        Assertions.assertTrue(renameCommitted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                                "rename must commit (or fail) within the timeout");
                        if (g != null) {
                            new RouteModelDao().update(new RouteModel().setId(modelId)
                                    .setName("Conv model").setSteps(steps), "admin");
                        }
                        tx.commit();
                    } catch (Throwable t) {
                        writerError.set(t);
                        if (tx.isActive()) {
                            tx.rollback();
                        }
                    } finally {
                        if (em.isOpen()) {
                            em.close();
                        }
                        ThreadLocalContext.cleanup();
                    }
                }));

        String finalGroup = storedGroupTargetName(modelId);
        String resolved = inTx(() -> SecurityUtil.getTargetIdFromName(finalGroup, AclTargetType.GROUP));
        Assertions.assertNotNull(resolved,
                "Committed blob names group '" + finalGroup + "' which must resolve to a live group");
        Assertions.assertEquals("convgroup2", finalGroup,
                "The rename won the group lock, so the blob must name the new group");
    }

    /**
     * A route model referencing group G is CREATED while G is being renamed. If the create commits a
     * model, its blob must name a live group. Without the group lock, the create snapshots
     * G-still-exists pre-rename and persists the orphaned old name.
     */
    @Test
    public void createVsRename() throws Exception {
        inTx(() -> {
            new GroupDao().create(new Group().setName("cvrgroup"), "admin");
            return null;
        });

        AtomicReference<String> createdModelId = new AtomicReference<>();

        runWriterVsRename("cvrgroup", "cvrgroup2",
                (writerReachedLock, writerTookSnapshot, renameCommitted, writerError) -> new Thread(() -> {
                    EntityManager em = EMF.get().createEntityManager();
                    ThreadLocalContext.get().setEntityManager(em);
                    EntityTransaction tx = em.getTransaction();
                    tx.begin();
                    try {
                        raiseLockTimeout();
                        String steps = stepsForGroup("cvrgroup");
                        writerReachedLock.countDown();
                        RouteModelStepUtil.lockGroupsByName(RouteModelStepUtil.parseGroupTargetNames(steps));
                        Group g = new GroupDao().getActiveByName("cvrgroup");
                        writerTookSnapshot.countDown();
                        Assertions.assertTrue(renameCommitted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                                "rename must commit (or fail) within the timeout");
                        if (g != null) {
                            createdModelId.set(new RouteModelDao().create(
                                    new RouteModel().setName("Cvr model").setSteps(steps), "admin"));
                        }
                        tx.commit();
                    } catch (Throwable t) {
                        writerError.set(t);
                        if (tx.isActive()) {
                            tx.rollback();
                        }
                    } finally {
                        if (em.isOpen()) {
                            em.close();
                        }
                        ThreadLocalContext.cleanup();
                    }
                }));

        String modelId = createdModelId.get();
        if (modelId != null) {
            String finalGroup = storedGroupTargetName(modelId);
            String resolved = inTx(() -> SecurityUtil.getTargetIdFromName(finalGroup, AclTargetType.GROUP));
            Assertions.assertNotNull(resolved,
                    "Committed created blob names group '" + finalGroup + "' which must resolve to a live group");
        }
    }

    /** Factory for the second writer thread, wired with the shared latches and error sink. */
    private interface WriterThreadFactory {
        Thread create(CountDownLatch writerReachedLock, CountDownLatch writerTookSnapshot,
                      CountDownLatch renameCommitted, AtomicReference<Throwable> writerError);
    }

    /**
     * Runs the shared rename-vs-writer interleave. The RENAME thread locks group {@code oldName}
     * first, waits (bounded) for the writer's existence snapshot, then prepares + renames + applies +
     * commits, mirroring GroupResource.update's order. The writer thread is supplied by the caller
     * (create or update). Fails the calling test if either thread throws or fails to terminate.
     */
    private void runWriterVsRename(String oldName, String newName, WriterThreadFactory writerFactory)
            throws InterruptedException {
        CountDownLatch renameLockedGroup = new CountDownLatch(1);
        CountDownLatch writerReachedLock = new CountDownLatch(1);
        CountDownLatch writerTookSnapshot = new CountDownLatch(1);
        CountDownLatch renameCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> renameError = new AtomicReference<>();
        AtomicReference<Throwable> writerError = new AtomicReference<>();

        Thread renameThread = new Thread(() -> {
            EntityManager em = EMF.get().createEntityManager();
            ThreadLocalContext.get().setEntityManager(em);
            EntityTransaction tx = em.getTransaction();
            tx.begin();
            try {
                raiseLockTimeout();
                new GroupDao().getActiveByNameForUpdate(oldName); // hold the group lock
                renameLockedGroup.countDown();
                if (!writerReachedLock.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("writer thread never reached its group lock");
                }
                // Deterministic hand-off, replacing a sleep heuristic. Locked (correct) code: the
                // writer is blocked INSIDE its group-lock call and cannot signal — this await times
                // out and the rename proceeds (the timeout IS the locked path, so the result is
                // deliberately not asserted). Lock-less (broken) code: the writer signals its stale
                // snapshot immediately and the rename proceeds without waiting.
                writerTookSnapshot.await(2, TimeUnit.SECONDS);
                // Mirror GroupResource.update: prepare (locks+preflight), rename, apply.
                RouteModelStepUtil.GroupRenameRepairPlan plan =
                        RouteModelStepUtil.prepareGroupRenameRepair(oldName, newName);
                GroupDao groupDao = new GroupDao();
                Group g = groupDao.getActiveByName(oldName);
                groupDao.update(new Group().setId(g.getId()).setName(newName), "admin");
                RouteModelStepUtil.applyGroupRenameRepair(plan);
                tx.commit();
            } catch (Throwable t) {
                renameError.set(t);
                if (tx.isActive()) {
                    tx.rollback();
                }
            } finally {
                // Always release the writer, even if the rename failed — the writer asserts on the
                // await result and would otherwise hang to its own timeout.
                renameCommitted.countDown();
                if (em.isOpen()) {
                    em.close();
                }
                ThreadLocalContext.cleanup();
            }
        });

        Thread writerThread = writerFactory.create(writerReachedLock, writerTookSnapshot, renameCommitted, writerError);

        renameThread.start();
        // The writer must not begin until the rename holds the group lock.
        Assertions.assertTrue(renameLockedGroup.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "rename must acquire the group lock first");
        writerThread.start();

        renameThread.join(TIMEOUT_SECONDS * 1000 + 5000);
        writerThread.join(TIMEOUT_SECONDS * 1000 + 5000);
        Assertions.assertFalse(renameThread.isAlive(), "rename thread must terminate within the timeout");
        Assertions.assertFalse(writerThread.isAlive(), "writer thread must terminate within the timeout");

        Assertions.assertNull(renameError.get(), "rename thread failed: " + renameError.get());
        Assertions.assertNull(writerError.get(), "writer thread failed: " + writerError.get());
    }
}
