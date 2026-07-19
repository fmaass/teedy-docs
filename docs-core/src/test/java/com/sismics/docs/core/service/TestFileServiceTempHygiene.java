package com.sismics.docs.core.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Cleartext-temp hygiene and multi-JVM temp-directory liveness.
 *
 * <p>Each running JVM owns its OWN app-prefixed, owner-only temp directory ({@code teedy_temp_<random>}) held
 * under a lifetime {@link java.nio.channels.FileLock}. The startup sweep reclaims ONLY the directories of DEAD
 * prior runs (lock acquirable — the OS released a crashed process's lock) and never a live instance's
 * directory (lock held), never follows a symlink, and never touches an entry that fails owner-only validation.
 * These are the deterministic invariants of #123; the reference set also survives concurrent add/remove without
 * corruption. In package {@code com.sismics.docs.core.service} so it can drive the protected
 * {@code startUp()}/{@code runOneIteration()} and the package-private test seams.</p>
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

    /**
     * #123 invariant — a DEAD prior run's directory (app-prefixed, owner-only, lock FREE) is swept and removed,
     * while a file OUTSIDE the app-prefixed namespace is never touched.
     */
    @Test
    public void deadSiblingWithFreeLockIsSweptAndRemoved() throws Exception {
        Assumptions.assumeTrue(posix(), "requires a POSIX filesystem");
        String originalTmpDir = System.getProperty("java.io.tmpdir");
        Path scratch = Files.createTempDirectory("teedy_sweep_free");
        try {
            System.setProperty("java.io.tmpdir", scratch.toString());
            FileService.resetTemporaryDirectoryForTest();

            // A dead prior run's directory: app-prefixed, owner-only, holding an UNLOCKED lock file (the OS
            // released the crashed owner's lock) plus a leftover decrypted-plaintext temp.
            Path dead = Files.createDirectory(scratch.resolve("teedy_temp_dead"), ownerOnlyAttr());
            Files.createFile(dead.resolve(".lock"));
            Path deadPlaintext = Files.createFile(dead.resolve("sismics_docs_leftover.tmp"));

            // A legacy dead directory with NO lock file yet (e.g. a pre-lock-model run): the sweep must CREATE
            // the lock file (with NOFOLLOW, which still makes a regular file when absent), acquire it, and
            // reclaim the directory.
            Path legacyDead = Files.createDirectory(scratch.resolve("teedy_temp_legacy"), ownerOnlyAttr());
            Path legacyPlaintext = Files.createFile(legacyDead.resolve("sismics_docs_legacy.tmp"));

            // A file directly in the OS temp root — outside the app-prefixed namespace — must be spared.
            Path outside = Files.createFile(scratch.resolve("unrelated_outside.tmp"));

            new FileService().startUp();

            Assertions.assertFalse(Files.exists(dead, LinkOption.NOFOLLOW_LINKS),
                    "the startup sweep must reclaim a dead prior run's directory whose lock is free");
            Assertions.assertFalse(Files.exists(deadPlaintext),
                    "the reclaimed directory's leftover plaintext temp must be deleted");
            Assertions.assertFalse(Files.exists(legacyDead, LinkOption.NOFOLLOW_LINKS),
                    "the sweep must reclaim a dead directory that has no lock file yet (creating one to probe it)");
            Assertions.assertFalse(Files.exists(legacyPlaintext),
                    "the legacy dead directory's leftover plaintext temp must be deleted");
            Assertions.assertTrue(Files.exists(outside),
                    "the startup sweep must never touch files outside the app-prefixed namespace");
        } finally {
            System.setProperty("java.io.tmpdir", originalTmpDir);
            FileService.resetTemporaryDirectoryForTest();
            deleteRecursively(scratch);
        }
    }

    /**
     * #123 invariant (the core safety guarantee) — a directory whose lock is HELD by a live instance is NOT
     * swept. A SEPARATE OS process holds the {@link java.nio.channels.FileLock}, so this JVM's {@code tryLock}
     * returns null exactly as it would against a concurrent rolling-deploy JVM; the sweep must skip it.
     */
    @Test
    public void liveSiblingWithHeldLockIsNotSwept() throws Exception {
        Assumptions.assumeTrue(posix(), "requires a POSIX filesystem");
        String originalTmpDir = System.getProperty("java.io.tmpdir");
        Path scratch = Files.createTempDirectory("teedy_sweep_held");
        Path helperSource = writeLockHolderSource();
        Process holder = null;
        try {
            System.setProperty("java.io.tmpdir", scratch.toString());
            FileService.resetTemporaryDirectoryForTest();

            Path live = Files.createDirectory(scratch.resolve("teedy_temp_live"), ownerOnlyAttr());
            Path liveLock = live.resolve(".lock");
            Path livePlaintext = Files.createFile(live.resolve("sismics_docs_inflight.tmp"));

            // A different OS process acquires and HOLDS the lock; the OS keeps it until that process exits.
            holder = spawnLockProbe(helperSource, liveLock, "hold");
            String reported = readProbeLine(holder);
            Assertions.assertEquals("LOCKED", reported,
                    "the helper process must acquire the lock before the sweep runs (got: " + reported + ")");

            new FileService().startUp();

            Assertions.assertTrue(Files.exists(live, LinkOption.NOFOLLOW_LINKS),
                    "a directory whose lock a live instance holds must NOT be swept");
            Assertions.assertTrue(Files.exists(livePlaintext),
                    "a live instance's in-flight temp must survive a concurrent JVM's startup sweep");
        } finally {
            if (holder != null) {
                holder.getOutputStream().close();
                holder.destroyForcibly();
                holder.waitFor(10, TimeUnit.SECONDS);
            }
            System.setProperty("java.io.tmpdir", originalTmpDir);
            FileService.resetTemporaryDirectoryForTest();
            Files.deleteIfExists(helperSource);
            deleteRecursively(scratch);
        }
    }

    /**
     * #123 invariant — an app-prefixed entry that FAILS the NOFOLLOW owner-only validation (a symlink, or a
     * wrong-permission directory) is never followed and never deleted.
     */
    @Test
    public void namespaceEntryFailingValidationIsNotTouched() throws Exception {
        Assumptions.assumeTrue(posix(), "requires a POSIX filesystem");
        String originalTmpDir = System.getProperty("java.io.tmpdir");
        Path scratch = Files.createTempDirectory("teedy_sweep_unsafe");
        Path victim = Files.createDirectory(scratch.resolve("victim"));
        Path victimFile = Files.createFile(victim.resolve("precious.txt"));
        try {
            System.setProperty("java.io.tmpdir", scratch.toString());
            FileService.resetTemporaryDirectoryForTest();

            // A symlink planted INSIDE the swept namespace, pointing at the victim.
            Path namespaceSymlink = scratch.resolve("teedy_temp_symlinked");
            Files.createSymbolicLink(namespaceSymlink, victim);

            // A wrong-permission (group/other-readable) directory inside the namespace.
            Path wrongPerms = Files.createDirectory(scratch.resolve("teedy_temp_wrongperms"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
            Path wrongPermsFile = Files.createFile(wrongPerms.resolve("keep.tmp"));

            new FileService().startUp();

            Assertions.assertTrue(Files.exists(victimFile),
                    "the sweep must not follow a namespaced symlink and delete files outside the app namespace");
            Assertions.assertTrue(Files.exists(namespaceSymlink, LinkOption.NOFOLLOW_LINKS),
                    "a namespaced symlink is never a valid owned directory and must be left untouched");
            Assertions.assertTrue(Files.exists(wrongPermsFile),
                    "a wrong-permission namespaced directory fails owner-only validation and must be left alone");
        } finally {
            System.setProperty("java.io.tmpdir", originalTmpDir);
            FileService.resetTemporaryDirectoryForTest();
            deleteRecursively(scratch);
        }
    }

    /**
     * #123 invariant — the live JVM's own directory is published INTO the swept namespace with its lock ALREADY
     * held (TOCTOU closed: a concurrent sweeper can never see it lock-free), no staging directory is left
     * behind, and the own directory (and an in-flight temp in it) is never a sweep target.
     */
    @Test
    public void ownLockedDirectoryIsPublishedWithLockHeldAndNeverSwept() throws Exception {
        Assumptions.assumeTrue(posix(), "requires a POSIX filesystem");
        String originalTmpDir = System.getProperty("java.io.tmpdir");
        Path scratch = Files.createTempDirectory("teedy_own_dir");
        Path helperSource = writeLockHolderSource();
        try {
            System.setProperty("java.io.tmpdir", scratch.toString());
            FileService.resetTemporaryDirectoryForTest();

            Path own = FileService.getTemporaryDirectory();
            Assertions.assertTrue(own.getFileName().toString().startsWith("teedy_temp_"),
                    "the published own directory must live in the swept app-prefixed namespace");
            Path ownLock = own.resolve(".lock");
            Assertions.assertTrue(Files.exists(ownLock),
                    "the published own directory must carry its lock file");

            // TOCTOU closed: a SEPARATE process cannot acquire the published directory's lock, proving it is
            // already held the instant the directory becomes discoverable in the swept namespace.
            Process probe = spawnLockProbe(helperSource, ownLock, "probe");
            try {
                String reported = readProbeLine(probe);
                Assertions.assertEquals("FAILED", reported,
                        "a concurrent process must NOT be able to lock the just-published own directory "
                                + "(it must already be held): " + reported);
            } finally {
                probe.getOutputStream().close();
                probe.waitFor(10, TimeUnit.SECONDS);
                probe.destroyForcibly();
            }

            // No staging directory is left behind after publication.
            try (Stream<Path> entries = Files.list(scratch)) {
                Assertions.assertTrue(
                        entries.noneMatch(p -> p.getFileName().toString().startsWith("teedy_stage_")),
                        "no staging directory may be left behind after publication");
            }

            // A temp created in the own directory must survive this JVM's own startup sweep.
            Path inFlight = new FileService().createTemporaryFile();
            new FileService().startUp();
            Assertions.assertTrue(Files.exists(own, LinkOption.NOFOLLOW_LINKS),
                    "the live JVM's own locked directory must never be a sweep target");
            Assertions.assertTrue(Files.exists(inFlight),
                    "the live JVM's own in-flight temp must survive its own startup sweep");
        } finally {
            System.setProperty("java.io.tmpdir", originalTmpDir);
            FileService.resetTemporaryDirectoryForTest();
            Files.deleteIfExists(helperSource);
            deleteRecursively(scratch);
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
            // area. The per-JVM model never adopts a canonical teedy_temp, so resolution creates a fresh
            // owner-only directory instead, and the sweep must never follow the planted symlink.
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

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static FileAttribute<?> ownerOnlyAttr() {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    }

    /**
     * Write a self-contained, JDK-only single-file Java source that acquires a {@link FileChannel} lock on the
     * given file. Run in a SEPARATE JVM (via the single-file source launcher), it holds a real cross-process
     * lock — the only way to make {@code tryLock} return null in this JVM, which is what distinguishes a live
     * owner from a dead one. Kept dependency-free so it needs no project classpath.
     */
    private static Path writeLockHolderSource() throws IOException {
        String source =
                "import java.nio.channels.FileChannel;\n" +
                "import java.nio.channels.FileLock;\n" +
                "import java.nio.file.Path;\n" +
                "import java.nio.file.Paths;\n" +
                "import java.nio.file.StandardOpenOption;\n" +
                "public class LockHolder {\n" +
                "  public static void main(String[] a) throws Exception {\n" +
                "    Path lockFile = Paths.get(a[0]);\n" +
                "    boolean hold = a.length < 2 || a[1].equals(\"hold\");\n" +
                "    try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {\n" +
                "      FileLock lock = ch.tryLock();\n" +
                "      if (lock == null) { System.out.println(\"FAILED\"); System.out.flush(); return; }\n" +
                "      System.out.println(\"LOCKED\"); System.out.flush();\n" +
                "      if (hold) { System.in.read(); } else { lock.release(); }\n" +
                "    }\n" +
                "  }\n" +
                "}\n";
        Path file = Files.createTempFile("LockHolder", ".java");
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }

    private static Process spawnLockProbe(Path helperSource, Path lockFile, String mode) throws IOException {
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(javaBin, helperSource.toString(), lockFile.toString(), mode);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    /** Read the helper process's stdout until it reports LOCKED or FAILED; returns that token, or null on EOF. */
    private static String readProbeLine(Process p) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.contains("LOCKED")) {
                return "LOCKED";
            }
            if (line.contains("FAILED")) {
                return "FAILED";
            }
        }
        return null;
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
