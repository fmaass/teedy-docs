package com.sismics.docs.core.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Cleartext-temp hygiene: plaintext temps live in a private, owner-only subdirectory of the OS temp dir; the
 * startup sweep clears stale temps in that namespace only; and the reference set survives concurrent
 * add/remove without corruption or exceptions. In package {@code com.sismics.docs.core.service} so it can
 * drive the protected {@code runOneIteration()} and the package-private {@code referenceCount()} test seam.
 */
public class TestFileServiceTempHygiene {

    @Test
    public void temporaryFileLandsInPrivateOwnerOnlyDirectory() throws Exception {
        Path dir = FileService.getTemporaryDirectory();
        Assertions.assertTrue(Files.isDirectory(dir), "the private temp directory must exist");

        // On a POSIX filesystem the directory must be owner-only so other local users cannot read decrypted
        // plaintext temps.
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Set<java.nio.file.attribute.PosixFilePermission> perms = Files.getPosixFilePermissions(dir);
            Assertions.assertEquals(PosixFilePermissions.fromString("rwx------"), perms,
                    "the private temp directory must grant no group/other access");
        }

        FileService service = new FileService();
        Path temp = service.createTemporaryFile();
        try {
            Assertions.assertEquals(dir, temp.getParent(),
                    "temp files must be created inside the private temp directory, not the OS temp root");
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Test
    public void startupSweepRemovesStaleTempsInPrivateDirectoryOnly() throws Exception {
        Path dir = FileService.getTemporaryDirectory();

        // A stale plaintext temp left in the private namespace by a previous (crashed) run.
        Path stale = Files.createTempFile(dir, "sismics_docs_stale", ".tmp");
        Assertions.assertTrue(Files.exists(stale));

        // A file OUTSIDE the private namespace (directly in the OS temp root) must NOT be touched.
        Path outside = Files.createTempFile("unrelated_outside_namespace", ".tmp");
        try {
            // startUp() runs the namespace-restricted sweep.
            new FileService().startUp();

            Assertions.assertFalse(Files.exists(stale), "the startup sweep must remove stale temps in the private directory");
            Assertions.assertTrue(Files.exists(outside), "the startup sweep must never touch files outside the application namespace");
        } finally {
            Files.deleteIfExists(stale);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    public void tamperedTemporaryDirectoryFailsClosedAndSweepSparesVictim() throws Exception {
        // POSIX-only: exercises symlink planting and ownership/permission checks.
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "symlink hardening test requires a POSIX filesystem");

        String originalTmpDir = System.getProperty("java.io.tmpdir");
        Path scratch = Files.createTempDirectory("teedy_symlink_test");
        Path victim = Files.createDirectory(scratch.resolve("victim"));
        Path victimFile = Files.createFile(victim.resolve("precious.txt"));
        Path plantedTeedyTemp = scratch.resolve("teedy_temp");
        try {
            // Plant teedy_temp as a symlink to a victim directory, and point the OS temp dir at the scratch
            // area so resolution targets our planted entry (never the real shared teedy_temp).
            Files.createSymbolicLink(plantedTeedyTemp, victim);
            System.setProperty("java.io.tmpdir", scratch.toString());
            FileService.resetTemporaryDirectoryForTest();

            Path resolved = FileService.getTemporaryDirectory();
            Assertions.assertNotEquals(victim.toRealPath(), resolved.toRealPath(),
                    "a symlinked teedy_temp must never be adopted as the temp directory");
            Assertions.assertNotEquals(plantedTeedyTemp.toAbsolutePath(), resolved.toAbsolutePath(),
                    "the tampered path must be rejected in favour of a freshly created secure directory");

            // The startup sweep must operate on the safe fallback directory, never the victim behind the
            // planted symlink.
            new FileService().startUp();
            Assertions.assertTrue(Files.exists(victimFile),
                    "the startup sweep must not follow a planted symlink and delete files outside the app namespace");
        } finally {
            System.setProperty("java.io.tmpdir", originalTmpDir);
            FileService.resetTemporaryDirectoryForTest();
            Files.deleteIfExists(plantedTeedyTemp);
            deleteRecursively(scratch);
        }
    }

    @Test
    public void concurrentCreationRetainsEveryReference() throws Exception {
        FileService service = new FileService();
        int threads = 8;
        int perThread = 250;
        ConcurrentLinkedQueue<Path> created = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                awaitStart(start);
                try {
                    for (int n = 0; n < perThread; n++) {
                        created.add(service.createTemporaryFile());
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            });
            workers[i].start();
        }
        start.countDown();
        joinAll(workers);

        try {
            Assertions.assertNull(error.get(), "concurrent temp creation must not throw: " + error.get());
            Assertions.assertEquals(threads * perThread, service.referenceCount(),
                    "every concurrent add must be retained (a thread-unsafe set would lose additions)");
        } finally {
            deleteAll(created);
        }
    }

    @Test
    public void concurrentCreateAndSweepDoNotThrow() throws Exception {
        FileService service = new FileService();
        int adderThreads = 6;
        int perThread = 200;
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        Thread[] adders = new Thread[adderThreads];
        for (int i = 0; i < adderThreads; i++) {
            adders[i] = new Thread(() -> {
                awaitStart(start);
                try {
                    for (int n = 0; n < perThread; n++) {
                        // Create then delete + drop the reference, so the referent becomes GC-eligible and its
                        // phantom is enqueued for the sweeper to remove — driving concurrent adds and removes
                        // onto the same reference set. Deleting inline also leaves no temp files behind.
                        Path path = service.createTemporaryFile();
                        Files.deleteIfExists(path);
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            });
        }
        // The sweeper polls the reference queue and removes from the same set (the scheduled path), so add and
        // remove hit the set concurrently. GC pressure enqueues phantoms to exercise the remove branch.
        Thread sweeper = new Thread(() -> {
            awaitStart(start);
            try {
                for (int n = 0; n < 800; n++) {
                    service.runOneIteration();
                    System.gc();
                }
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }
        });

        for (Thread t : adders) {
            t.start();
        }
        sweeper.start();
        start.countDown();
        joinAll(adders);
        sweeper.join(60_000);

        Assertions.assertNull(error.get(), "concurrent add/remove on the reference set must not throw: " + error.get());
    }

    private static void awaitStart(CountDownLatch start) {
        try {
            Assertions.assertTrue(start.await(30, TimeUnit.SECONDS), "the start barrier must release in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static void joinAll(Thread[] threads) throws InterruptedException {
        for (Thread t : threads) {
            t.join(60_000);
        }
        for (Thread t : threads) {
            Assertions.assertFalse(t.isAlive(), "every worker thread must finish");
        }
    }

    private static void deleteAll(Iterable<Path> paths) {
        for (Path p : paths) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
    }

    /** Recursively delete a directory tree, deepest first, without following symlinks. Best-effort. */
    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }
}
