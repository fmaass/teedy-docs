package com.sismics.docs.rest.resource;

import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.docs.rest.BaseJerseyTest;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import com.sismics.util.jpa.EMF;
import jakarta.json.JsonObject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Transaction-scope safety of {@code clean_storage}'s post-commit filesystem sweep (#103).
 *
 * <p>The post-commit sweep can be long (thousands of unlinks). The bug: the checkpoint commit's
 * continuation transaction was left open across the whole sweep, so under a PostgreSQL
 * {@code idle_in_transaction_session_timeout > 0} the request's backend session is aborted mid-sweep —
 * the files are already gone but the {@code T_CLEANUP_RUN} protocol insert then fails and the request
 * 500s. The fix ends that read-only continuation transaction BEFORE the sweep and records the run in its
 * own short transaction, so the sweep runs entirely off-database.</p>
 *
 * <p>This test arms a short idle-in-transaction timeout ON THE REQUEST BACKEND SESSION (via the
 * before-sweep hook, which runs on the request thread against the request's own entity manager — not a
 * helper connection) and holds the sweep past that timeout ON A PLANTED AGE-ELIGIBLE FILESYSTEM ORPHAN.
 * The orphan is planted by the before-sweep hook — AFTER the eligibility snapshot — so it is discovered
 * ONLY by the post-commit orphan enumeration (whose own DB query opens a transaction), never in the
 * confirmed-deleted set: the pause therefore lands PAST the point a transaction is (re)opened, where a
 * still-open continuation transaction would be aborted. On H2 (no such timeout) the run simply takes
 * longer; the assertions still hold, so the class is dual-engine.</p>
 */
public class TestCleanStorageSweepTransactionScope extends BaseJerseyTest {
    @AfterEach
    public void clearHook() {
        AppResource.cleanStorageBeforeCommitHook = null;
        AppResource.cleanStorageBeforeSweepHook = null;
        AppResource.cleanStorageDuringReclaimHook = null;
    }

    /**
     * A long post-commit sweep must NOT abort the request's DB session: the run completes 2xx and writes
     * its {@code T_CLEANUP_RUN} protocol row even when the sweep runs longer than an armed Postgres
     * idle-in-transaction timeout — proving the sweep holds no open request transaction across the
     * filesystem I/O.
     */
    @Test
    public void longSweepDoesNotAbortSessionAndRecordsRun() throws Exception {
        String adminToken = adminToken();
        Path storageDir = DirectoryUtil.getStorageDirectory();

        // Quiesce any pre-existing age-eligible orphan (left on the shared storage directory by earlier
        // tests in this fork) so the orphan planted below is the sole reclaimable file of the real run.
        target().path("/app/batch/clean_storage").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()), JsonObject.class);

        String orphanId = UUID.randomUUID().toString();
        long twoDaysAgo = System.currentTimeMillis() - 2L * 24L * 60L * 60L * 1000L;
        AtomicBoolean orphanPlanted = new AtomicBoolean(false);

        // Before-sweep hook: runs on the request thread, AFTER the checkpoint commit and the eligibility
        // snapshot. (1) On Postgres arm a short idle-in-transaction timeout on THIS request backend
        // session — the continuation transaction's own connection — so a sweep that leaves it open gets
        // aborted. (2) Plant an OLD (age-eligible) filesystem orphan with no DB row; because it appears
        // only after the snapshot, the run finds it solely via the post-commit orphan enumeration.
        AppResource.cleanStorageBeforeSweepHook = () -> {
            if (EMF.isDriverPostgresql()) {
                ThreadLocalContext.get().getEntityManager()
                        .createNativeQuery("SET idle_in_transaction_session_timeout = 1000").executeUpdate();
            }
            try {
                for (String suffix : new String[]{"", "_web", "_thumb"}) {
                    Path p = storageDir.resolve(orphanId + suffix);
                    Files.write(p, new byte[]{1, 2, 3, 4});
                    Files.setLastModifiedTime(p, FileTime.fromMillis(twoDaysAgo));
                }
                orphanPlanted.set(true);
            } catch (IOException e) {
                throw new IllegalStateException("failed to plant the age-eligible orphan", e);
            }
        };

        // Per-file sweep hook: fires WHILE the run holds the sweep lock, after deleting the orphan's base
        // variant — i.e. inside the orphan-reclaim loop, past the enumeration's DB query. Pause here
        // longer than the armed idle timeout, so a run that kept its request transaction open across the
        // sweep has its backend session aborted before it can record the run.
        AtomicInteger sweepPauses = new AtomicInteger(0);
        AppResource.cleanStorageDuringReclaimHook = (fileId) -> {
            if (orphanId.equals(fileId)) {
                sweepPauses.incrementAndGet();
                try {
                    Thread.sleep(3000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        long runsBefore = countCleanupRuns();

        Response response = target().path("/app/batch/clean_storage").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()));

        Assertions.assertTrue(orphanPlanted.get(),
                "precondition: the before-sweep hook planted the age-eligible orphan");
        Assertions.assertTrue(sweepPauses.get() >= 1,
                "precondition: the sweep paused on the planted orphan (inside the orphan-reclaim loop)");

        // The run COMPLETES rather than 500ing from an idle-in-transaction session abort during the sweep.
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()),
                "a long post-commit sweep must not abort the request's DB session and 500 the run");

        // The planted orphan's bytes were actually reclaimed (the sweep ran to completion).
        for (String suffix : new String[]{"", "_web", "_thumb"}) {
            Assertions.assertFalse(Files.exists(storageDir.resolve(orphanId + suffix)),
                    "the planted orphan variant '" + suffix + "' was reclaimed by the sweep");
        }

        // The durable T_CLEANUP_RUN protocol row was written — the insert that runs AFTER the sweep
        // succeeded, so the continuation transaction was not left open (and aborted) across the sweep.
        Assertions.assertEquals(runsBefore + 1, countCleanupRuns(),
                "the run recorded its T_CLEANUP_RUN protocol row after the long sweep");
    }

    /**
     * Counts the durable {@code T_CLEANUP_RUN} protocol rows in an isolated committed transaction
     * (restoring the ambient entity manager afterward), so it reads the committed state a completed run
     * left behind.
     */
    private long countCleanupRuns() {
        EntityManager prev = ThreadLocalContext.get().getEntityManager();
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            ThreadLocalContext.get().setEntityManager(em);
            tx.begin();
            Number count = (Number) em.createNativeQuery("select count(*) from T_CLEANUP_RUN").getSingleResult();
            tx.commit();
            return count.longValue();
        } finally {
            if (tx.isActive()) {
                tx.rollback();
            }
            em.close();
            ThreadLocalContext.get().setEntityManager(prev);
        }
    }
}
