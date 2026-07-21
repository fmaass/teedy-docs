package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * The reconciliation claim/completion compare-and-swap DAO methods (#159): fenced claim arbitration (a
 * live lease blocks a second claimer, an expired one is reclaimed), token-fenced completion (a stale
 * claimant cannot mark a successor's work complete), the live unclaimed-completion path, and the scan /
 * drain-count predicates.
 */
public class TestFileReconciliationDao extends BaseTransactionalTest {

    private String createFileRow() throws Exception {
        User user = createUser("recon_" + UUID.randomUUID().toString().substring(0, 8));
        File file = new File();
        file.setUserId(user.getId());
        file.setName("recon.txt");
        file.setMimeType("text/plain");
        file.setVersion(0);
        file.setLatestVersion(true);
        file.setSize(10L);
        return new FileDao().create(file, user.getId());
    }

    /** Clear the persistence context and re-read so column values reflect prior bulk (CAS) updates. */
    private File refetch(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.clear();
        return new FileDao().getActiveById(id);
    }

    private Date dbNow() {
        return new FileDao().getDatabaseTime();
    }

    // --- claim arbitration: exactly one of two claimers wins; a live lease blocks the second ------------

    @Test
    public void twoClaimersExactlyOneWins() throws Exception {
        String fileId = createFileRow();
        FileDao fileDao = new FileDao();
        Date now = dbNow();
        Date cutoff = new Date(now.getTime() - 60_000); // a 1-minute lease TTL

        int first = fileDao.claimForReprocess(fileId, "token-A", now, cutoff);
        int second = fileDao.claimForReprocess(fileId, "token-B", now, cutoff);

        Assertions.assertEquals(1, first, "the first claimer wins (row was unclaimed)");
        Assertions.assertEquals(0, second, "the second claimer loses: a live lease holds the row");

        File claimed = refetch(fileId);
        Assertions.assertEquals("token-A", claimed.getProcessingToken(), "the winner's token is stamped");
        Assertions.assertNotNull(claimed.getProcessingAt(), "the lease time is stamped");
        Assertions.assertEquals(1, claimed.getProcAttempts(), "the attempt counter is bumped once");
        Assertions.assertNull(claimed.getProcessed(), "a claim does not mark the file processed");
    }

    // --- expired lease is reclaimed; a stale (past-lease) claimant cannot complete (fencing) -----------

    @Test
    public void expiredLeaseReclaimedAndStaleClaimantCannotComplete() throws Exception {
        String fileId = createFileRow();
        FileDao fileDao = new FileDao();
        Date now = dbNow();

        Assertions.assertEquals(1, fileDao.claimForReprocess(fileId, "token-old", now, new Date(now.getTime() - 60_000)),
                "the first claim wins");

        // Simulate the lease TTL elapsing: a cutoff AFTER the stored lease time makes the lease expired, so a
        // new cycle reclaims it with a fresh token.
        Date later = new Date(now.getTime() + 60_000);
        Date expiredCutoff = new Date(later.getTime() + 60_000); // strictly after the stored processingAt
        Assertions.assertEquals(1, fileDao.claimForReprocess(fileId, "token-new", later, expiredCutoff),
                "an expired lease is reclaimed by a later cycle");
        Assertions.assertEquals(2, refetch(fileId).getProcAttempts(), "the reclaim bumps the attempt counter again");

        // The stale claimant (past its lease, token-old) must NOT be able to mark completion.
        Assertions.assertEquals(0, fileDao.markProcessedIfClaimant(fileId, "token-old", dbNow()),
                "a stale claimant whose lease was reclaimed cannot mark completion (fencing)");
        Assertions.assertNull(refetch(fileId).getProcessed(), "the file is still unprocessed after the stale attempt");

        // The current claimant (token-new) completes.
        Assertions.assertEquals(1, fileDao.markProcessedIfClaimant(fileId, "token-new", dbNow()),
                "the current claim holder marks completion");
        File done = refetch(fileId);
        Assertions.assertNotNull(done.getProcessed(), "the completion marker is written");
        Assertions.assertNull(done.getProcessingToken(), "the lease token is cleared on completion");
        Assertions.assertNull(done.getProcessingAt(), "the lease time is cleared on completion");
    }

    // --- live (unclaimed) completion path: stamps only while unclaimed --------------------------------

    @Test
    public void unclaimedCompletionStampsOnlyWhenUnclaimed() throws Exception {
        String fileId = createFileRow();
        FileDao fileDao = new FileDao();

        Assertions.assertEquals(1, fileDao.markProcessedIfUnclaimed(fileId, dbNow()),
                "the live path stamps an unclaimed, unprocessed file");
        Assertions.assertNotNull(refetch(fileId).getProcessed(), "the marker is written");

        // Second file: once a reconciler has claimed it, the live unclaimed-completion is a clean no-op.
        String claimedId = createFileRow();
        Assertions.assertEquals(1, fileDao.claimForReprocess(claimedId, "token-X", dbNow(), new Date(dbNow().getTime() - 60_000)));
        Assertions.assertEquals(0, fileDao.markProcessedIfUnclaimed(claimedId, dbNow()),
                "the live completion is a no-op once a reconciler holds the claim");
        Assertions.assertNull(refetch(claimedId).getProcessed(), "the claimed file is not marked by the live path");
    }

    // --- scan predicate: excludes processed, deleted, and live-leased rows -----------------------------

    @Test
    public void scanSelectsOnlyClaimableUnprocessedActiveRows() throws Exception {
        FileDao fileDao = new FileDao();

        String pending = createFileRow();                 // unprocessed, unclaimed -> selectable
        String processed = createFileRow();
        fileDao.markProcessedIfUnclaimed(processed, dbNow()); // processed -> excluded
        String leased = createFileRow();
        Date now = dbNow();
        fileDao.claimForReprocess(leased, "live", now, new Date(now.getTime() - 60_000)); // live lease -> excluded

        Date cutoff = new Date(dbNow().getTime() - 60_000);
        List<String> selectable = fileDao.getFilesToReconcile(cutoff, 1000).stream().map(File::getId).toList();

        Assertions.assertTrue(selectable.contains(pending), "an unprocessed unclaimed file is selectable");
        Assertions.assertFalse(selectable.contains(processed), "a processed file is excluded");
        Assertions.assertFalse(selectable.contains(leased), "a live-leased file is excluded from the scan");
    }

    // --- drain count: counts unprocessed active (incl. leased-in-flight), excludes processed -----------

    @Test
    public void drainCountIncludesLeasedButNotProcessed() throws Exception {
        FileDao fileDao = new FileDao();
        long before = fileDao.countUnprocessedActiveFiles();

        String pending = createFileRow();
        String leased = createFileRow();
        Date now = dbNow();
        fileDao.claimForReprocess(leased, "live", now, new Date(now.getTime() - 60_000));
        String processed = createFileRow();
        fileDao.markProcessedIfUnclaimed(processed, dbNow());

        long after = fileDao.countUnprocessedActiveFiles();
        // pending (+1) and leased (+1) are still unprocessed; processed (0) is not counted.
        Assertions.assertEquals(before + 2, after,
                "the drain count includes an unclaimed AND a leased-in-flight file, but not a processed one");
    }
}
