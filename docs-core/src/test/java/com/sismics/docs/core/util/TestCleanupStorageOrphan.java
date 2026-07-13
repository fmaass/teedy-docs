package com.sismics.docs.core.util;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.util.context.ThreadLocalContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.persistence.EntityManager;
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
}
