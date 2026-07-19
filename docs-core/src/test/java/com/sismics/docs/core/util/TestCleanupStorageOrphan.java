package com.sismics.docs.core.util;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.util.context.ThreadLocalContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Unit tests for the #72 age-thresholded filesystem-orphan reclaim primitives in
 * {@link CleanupStorageUtil}: {@link CleanupStorageUtil#isAgeEligibleOrphan} and
 * {@link CleanupStorageUtil#findAgeEligibleOrphans}. The load-bearing property is the age gate — a
 * fresh no-DB-row file (a possible in-flight upload) is NEVER eligible; an old one is.
 */
public class TestCleanupStorageOrphan extends BaseTransactionalTest {
    private EntityManager em() {
        return ThreadLocalContext.get().getEntityManager();
    }

    private long old() {
        return System.currentTimeMillis() - 2L * 24L * 60L * 60L * 1000L; // 2 days ago
    }

    private long nowMs() {
        return System.currentTimeMillis();
    }

    @Test
    public void oldOrphanIsEligibleFreshIsNot() throws Exception {
        Path dir = DirectoryUtil.getStorageDirectory();
        Set<String> known = new HashSet<>(); // no DB rows

        String oldId = UUID.randomUUID().toString();
        Path oldOriginal = dir.resolve(oldId);
        Files.write(oldOriginal, new byte[]{1, 2, 3});
        Files.setLastModifiedTime(oldOriginal, FileTime.fromMillis(old()));

        String freshId = UUID.randomUUID().toString();
        Path freshOriginal = dir.resolve(freshId);
        Files.write(freshOriginal, new byte[]{4, 5, 6});
        // mtime = now (fresh)

        try {
            Assertions.assertTrue(CleanupStorageUtil.isAgeEligibleOrphan(dir, oldId, known, nowMs()),
                    "an old no-DB-row file is an eligible orphan");
            Assertions.assertFalse(CleanupStorageUtil.isAgeEligibleOrphan(dir, freshId, known, nowMs()),
                    "a fresh no-DB-row file is NOT eligible (may be an in-flight upload)");
        } finally {
            Files.deleteIfExists(oldOriginal);
            Files.deleteIfExists(freshOriginal);
        }
    }

    @Test
    public void aFileWithADbRowIsNeverAnOrphan() throws Exception {
        Path dir = DirectoryUtil.getStorageDirectory();
        String id = UUID.randomUUID().toString();
        Path original = dir.resolve(id);
        Files.write(original, new byte[]{1});
        Files.setLastModifiedTime(original, FileTime.fromMillis(old())); // old, but has a DB row

        Set<String> known = new HashSet<>();
        known.add(id);
        try {
            Assertions.assertFalse(CleanupStorageUtil.isAgeEligibleOrphan(dir, id, known, nowMs()),
                    "a base id with a T_FILE row is never a filesystem orphan, even when old");
        } finally {
            Files.deleteIfExists(original);
        }
    }

    @Test
    public void aFreshDerivativeSparesTheWholeOldGroup() throws Exception {
        // Original is old but the _web derivative is fresh → the whole group is spared (conservative).
        Path dir = DirectoryUtil.getStorageDirectory();
        String id = UUID.randomUUID().toString();
        Path original = dir.resolve(id);
        Path web = dir.resolve(id + "_web");
        Files.write(original, new byte[]{1, 2});
        Files.setLastModifiedTime(original, FileTime.fromMillis(old()));
        Files.write(web, new byte[]{3, 4}); // fresh

        Set<String> known = new HashSet<>();
        try {
            Assertions.assertFalse(CleanupStorageUtil.isAgeEligibleOrphan(dir, id, known, nowMs()),
                    "a fresh derivative makes the whole group ineligible");
        } finally {
            Files.deleteIfExists(original);
            Files.deleteIfExists(web);
        }
    }

    @Test
    public void findAgeEligibleOrphansReturnsOnlyOldNoRowGroupsWithFootprint() throws Exception {
        Path dir = DirectoryUtil.getStorageDirectory();

        String oldId = UUID.randomUUID().toString();
        Path oldOriginal = dir.resolve(oldId);
        Path oldWeb = dir.resolve(oldId + "_web");
        Files.write(oldOriginal, new byte[]{1, 2, 3, 4}); // 4 bytes
        Files.write(oldWeb, new byte[]{5, 6});            // 2 bytes → footprint 6
        Files.setLastModifiedTime(oldOriginal, FileTime.fromMillis(old()));
        Files.setLastModifiedTime(oldWeb, FileTime.fromMillis(old()));

        String freshId = UUID.randomUUID().toString();
        Path freshOriginal = dir.resolve(freshId);
        Files.write(freshOriginal, new byte[]{9});

        try {
            List<CleanupStorageUtil.OrphanCandidate> found = CleanupStorageUtil.findAgeEligibleOrphans(em(), dir, nowMs());
            Set<String> ids = new HashSet<>();
            long oldFootprint = -1;
            for (CleanupStorageUtil.OrphanCandidate c : found) {
                ids.add(c.baseId);
                if (c.baseId.equals(oldId)) {
                    oldFootprint = c.footprint;
                }
            }
            Assertions.assertTrue(ids.contains(oldId), "the old no-row group is enumerated");
            Assertions.assertFalse(ids.contains(freshId), "the fresh no-row file is excluded");
            Assertions.assertEquals(6L, oldFootprint, "footprint sums the group's variants (4 + 2)");
        } finally {
            Files.deleteIfExists(oldOriginal);
            Files.deleteIfExists(oldWeb);
            Files.deleteIfExists(freshOriginal);
        }
    }

    /**
     * #79 byte accounting: {@link CleanupStorageUtil#deleteAndMeasure} counts ONLY the bytes THIS run's
     * own delete actually freed. The in-process sweep lock serializes concurrent clean_storage runs, but a
     * non-clean_storage deleter ({@code FileDeletedAsyncListener}) can still remove a variant OUTSIDE the
     * sweep, so this per-variant accounting must exclude bytes it did not free. Here a concurrent deleter
     * removes {@code _web} mid-reclaim (via the base-delete seam); the reported footprint must be base +
     * {@code _thumb} only, not the {@code _web} bytes another actor freed.
     */
    @Test
    public void bytesFreedCountsOnlyVariantsThisRunActuallyDeleted() throws Exception {
        Path dir = DirectoryUtil.getStorageDirectory();
        String id = UUID.randomUUID().toString();
        Files.write(dir.resolve(id), new byte[]{1, 2, 3, 4});     // base: 4 bytes
        Path web = dir.resolve(id + "_web");
        Files.write(web, new byte[]{5, 6, 7});                    // _web: 3 bytes (freed by the OTHER deleter)
        Files.write(dir.resolve(id + "_thumb"), new byte[]{8});   // _thumb: 1 byte

        try {
            CleanupStorageUtil.ReclaimResult r = CleanupStorageUtil.deleteAndMeasure(dir, id, claimedId -> {
                // A concurrent NON-clean_storage deleter removes the _web variant AFTER this run deleted
                // the base but before this run reaches _web.
                try {
                    Files.deleteIfExists(web);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            Assertions.assertTrue(r.isReclaimed(), "this run reclaimed at least one variant it deleted");
            Assertions.assertEquals(5L, r.getBytesFreed(),
                    "bytes freed count ONLY the variants THIS run deleted (base 4 + _thumb 1), never the "
                            + "concurrently-removed _web (3)");
            Assertions.assertFalse(Files.exists(dir.resolve(id)), "base is gone");
            Assertions.assertFalse(Files.exists(dir.resolve(id + "_thumb")), "_thumb is gone");
        } finally {
            Files.deleteIfExists(dir.resolve(id));
            Files.deleteIfExists(dir.resolve(id + "_web"));
            Files.deleteIfExists(dir.resolve(id + "_thumb"));
        }
    }

    /**
     * #79 single-run reclaim: {@link CleanupStorageUtil#deleteAndMeasure} removes all three variants of a
     * logical file and reports their summed footprint (counted once). An already-gone id reclaims nothing.
     */
    @Test
    public void deleteAndMeasureRemovesAllVariantsAndSumsFootprintOnce() throws Exception {
        Path dir = DirectoryUtil.getStorageDirectory();
        String id = UUID.randomUUID().toString();
        Files.write(dir.resolve(id), new byte[]{1, 2, 3, 4});     // 4
        Files.write(dir.resolve(id + "_web"), new byte[]{5, 6});  // 2
        Files.write(dir.resolve(id + "_thumb"), new byte[]{7});   // 1 → footprint 7

        try {
            CleanupStorageUtil.ReclaimResult r = CleanupStorageUtil.deleteAndMeasure(dir, id, null);
            Assertions.assertTrue(r.isReclaimed(), "a present logical file is reclaimed");
            Assertions.assertEquals(7L, r.getBytesFreed(), "footprint sums all variants (4 + 2 + 1)");
            Assertions.assertFalse(Files.exists(dir.resolve(id)), "base is gone");
            Assertions.assertFalse(Files.exists(dir.resolve(id + "_web")), "_web is gone");
            Assertions.assertFalse(Files.exists(dir.resolve(id + "_thumb")), "_thumb is gone");

            // Re-running on the now-gone id reclaims nothing (no double count).
            CleanupStorageUtil.ReclaimResult again = CleanupStorageUtil.deleteAndMeasure(dir, id, null);
            Assertions.assertFalse(again.isReclaimed(), "an already-gone id reclaims nothing");
            Assertions.assertEquals(0L, again.getBytesFreed(), "an already-gone id frees no bytes");
        } finally {
            Files.deleteIfExists(dir.resolve(id));
            Files.deleteIfExists(dir.resolve(id + "_web"));
            Files.deleteIfExists(dir.resolve(id + "_thumb"));
        }
    }

    /**
     * #79 partial-failure accounting: if a LATER variant's delete throws {@link IOException}, the
     * bytes/count for variants THIS run already removed must NOT be discarded. Here base and {@code _web}
     * are regular files (deleted successfully) and {@code _thumb} is a NON-EMPTY DIRECTORY, so its
     * {@code deleteIfExists} throws {@code DirectoryNotEmptyException} (an {@link IOException}). The result
     * must report base + {@code _web} bytes (not zero), stay reclaimed, and carry the failure — the
     * un-deletable variant is left in place for a later sweep.
     */
    @Test
    public void bytesFreedSurviveALaterVariantDeleteFailure() throws Exception {
        Path dir = DirectoryUtil.getStorageDirectory();
        String id = UUID.randomUUID().toString();
        Files.write(dir.resolve(id), new byte[]{1, 2, 3, 4});      // base: 4 bytes (freed)
        Files.write(dir.resolve(id + "_web"), new byte[]{5, 6, 7}); // _web: 3 bytes (freed)
        // _thumb is a NON-EMPTY directory → its deleteIfExists throws DirectoryNotEmptyException.
        Path thumbDir = dir.resolve(id + "_thumb");
        Files.createDirectory(thumbDir);
        Path blocker = thumbDir.resolve("blocker");
        Files.write(blocker, new byte[]{9});

        try {
            CleanupStorageUtil.ReclaimResult r = CleanupStorageUtil.deleteAndMeasure(dir, id, null);
            Assertions.assertTrue(r.isReclaimed(), "base + _web were removed by this run");
            Assertions.assertEquals(7L, r.getBytesFreed(),
                    "bytes already freed (base 4 + _web 3) must SURVIVE the _thumb delete failure, not reset to 0");
            Assertions.assertNotNull(r.getFailure(), "the _thumb delete failure is reported to the caller");
            Assertions.assertFalse(Files.exists(dir.resolve(id)), "base was removed");
            Assertions.assertFalse(Files.exists(dir.resolve(id + "_web")), "_web was removed");
            Assertions.assertTrue(Files.exists(thumbDir), "the un-deletable _thumb is left for a later sweep");
        } finally {
            Files.deleteIfExists(dir.resolve(id));
            Files.deleteIfExists(dir.resolve(id + "_web"));
            Files.deleteIfExists(blocker);
            Files.deleteIfExists(thumbDir);
        }
    }
}
