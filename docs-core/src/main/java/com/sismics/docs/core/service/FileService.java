package com.sismics.docs.core.service;

import com.google.common.util.concurrent.AbstractScheduledService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * File service.
 *
 * @author bgamard
 */
public class FileService extends AbstractScheduledService {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    /**
     * Base name of the application's private, owner-only temp directories under the OS temp directory. Each
     * running JVM owns its OWN directory named {@code teedy_temp_<random>} (see {@link #TEMP_DIRECTORY_PREFIX})
     * rather than a single shared {@code teedy_temp}: a shared directory swept unconditionally by every
     * starting JVM would let a rolling-deploy JVM delete another live JVM's decrypted scratch. Kept out of the
     * world-listable OS temp root — with owner-only directory permissions where the platform supports them —
     * so other local users cannot enumerate or read decrypted document content.
     */
    private static final String TEMP_SUBDIRECTORY = "teedy_temp";

    /**
     * Prefix of the per-JVM temp directories — the "app-prefixed namespace" the startup sweep scans. Every
     * directory this application publishes for its own use lives under {@code <java.io.tmpdir>} with this
     * prefix and holds a lifetime {@link FileLock} on its {@link #LOCK_FILE_NAME} for as long as its owning
     * JVM lives. The trailing underscore keeps this distinct from the {@link #STAGING_DIRECTORY_PREFIX} used
     * before a directory is published.
     */
    private static final String TEMP_DIRECTORY_PREFIX = TEMP_SUBDIRECTORY + "_";

    /**
     * Prefix of the short-lived STAGING directory a JVM creates and locks BEFORE publishing its temp
     * directory. Staging happens OUTSIDE the swept {@link #TEMP_DIRECTORY_PREFIX} namespace so a concurrently
     * starting JVM's sweep can never observe (and delete) a freshly created directory whose lock is not yet
     * held. Once the directory is created and its lock acquired, it is atomically renamed into the swept
     * namespace, so it is only ever discoverable there with its lock already held.
     */
    private static final String STAGING_DIRECTORY_PREFIX = "teedy_stage_";

    /**
     * Name of the lock file inside each per-JVM temp directory. A JVM holds an exclusive {@link FileLock} on
     * this file for its whole life; the startup sweep uses {@link FileChannel#tryLock()} on a sibling's lock
     * file to tell a dead owner (lock acquirable — the OS released a crashed process's lock) from a live one
     * (lock held). It is a regular file inside the directory so a single atomic rename moves the directory and
     * its already-held lock together.
     */
    private static final String LOCK_FILE_NAME = ".lock";

    /**
     * Owner-only permissions ({@code rwx------}) required of the private temp directory.
     */
    private static final Set<java.nio.file.attribute.PosixFilePermission> OWNER_ONLY_DIR_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");

    /**
     * This JVM's own resolved, validated-safe private temp directory, cached so every
     * {@link #createTemporaryFile} and the startup sweep share one location. Held under a lifetime
     * {@link #ownDirectoryLock} for the whole life of the JVM so a concurrent instance's sweep recognises it
     * as live and never reclaims it. Reset in tests via {@link #resetTemporaryDirectoryForTest()}.
     */
    private static volatile Path resolvedTemporaryDirectory;

    /**
     * The open channel backing {@link #ownDirectoryLock}. Kept as a strong static reference for the JVM's
     * whole life: closing it (or letting it be garbage-collected) would release the lifetime lock and let a
     * concurrent instance reclaim this JVM's live scratch directory. Only ever mutated under the
     * {@code FileService.class} monitor.
     */
    private static FileChannel ownDirectoryLockChannel;

    /**
     * The lifetime lock on this JVM's own temp directory (held on {@link #LOCK_FILE_NAME}). The OS releases it
     * automatically when this process exits — that release is exactly what lets a later JVM's sweep recognise
     * a crashed run's directory as reclaimable. Only ever mutated under the {@code FileService.class} monitor.
     */
    private static FileLock ownDirectoryLock;

    /**
     * Phantom references queue.
     */
    private final ReferenceQueue<Path> referenceQueue = new ReferenceQueue<>();

    /**
     * Live temporary-file references. Request threads add (via {@link #createTemporaryFile(String)}) while
     * the scheduled sweep removes (via {@link #deleteTemporaryFiles()}) concurrently, so this must be a
     * thread-safe set — a plain {@code HashSet} would risk a {@code ConcurrentModificationException} and a
     * lost/torn write.
     */
    private final Set<TemporaryPathReference> referenceSet = ConcurrentHashMap.newKeySet();

    /**
     * Test-only observer of temporary-file creation. When non-null, every path returned by
     * {@link #createTemporaryFile(String)} is offered to it. This is the single centralized creation
     * seam for plaintext temp files, so a test can capture EXACTLY the temps a code path produces —
     * including the attachment temp created in {@code EmailUtil} BEFORE it is recorded in the mail
     * content list, the gap a content-list-only observer misses. Always null in production; guarded by a
     * volatile read so it is inert (a single null check) on the hot path.
     */
    private static volatile Consumer<Path> temporaryFileListener;

    /**
     * Install (or, with {@code null}, remove) the test-only temporary-file creation observer. Not used
     * in production. A test MUST restore the previous value (typically {@code null}) in a finally block
     * so no observer leaks into a later test sharing this process-wide singleton.
     *
     * @param listener Observer notified of each created temp path, or null to uninstall
     */
    public static void setTemporaryFileListener(Consumer<Path> listener) {
        temporaryFileListener = listener;
    }

    public FileService() {
    }

    @Override
    protected void startUp() {
        log.info("File service starting up");
        // Reclaim ONLY sibling directories whose owner is dead — never a live instance's decrypted scratch. A
        // shared canonical directory swept unconditionally by every starting JVM (the previous model) would
        // delete a concurrent rolling-deploy JVM's temps. The sweep establishes this JVM's own locked
        // directory first (so it can recognise and skip it).
        sweepDeadSiblingDirectories();
    }

    @Override
    protected void shutDown() {
        log.info("File service shutting down");
        // The lifetime directory lock is intentionally NOT released here: it is tied to the JVM, not to this
        // service instance (an AppContext may stop and restart the service within one JVM, reusing the same
        // locked directory). The OS releases the lock on process exit.
    }

    @Override
    protected void runOneIteration() {
        try {
            deleteTemporaryFiles();
        } catch (Throwable e) {
            log.error("Exception during file service iteration", e);
        }
    }

    /**
     * Delete unreferenced temporary files.
     */
    private void deleteTemporaryFiles() throws Exception {
        TemporaryPathReference ref;
        while ((ref = (TemporaryPathReference) referenceQueue.poll()) != null) {
            // Backstop only: producers now delete their own temp files deterministically,
            // so the file is usually already gone by the time it is GC-collected.
            Files.deleteIfExists(Paths.get(ref.path));
            referenceSet.remove(ref);
        }
    }

    @Override
    protected Scheduler scheduler() {
        return Scheduler.newFixedDelaySchedule(0, 5, TimeUnit.SECONDS);
    }

    public Path createTemporaryFile() throws IOException {
        return createTemporaryFile(null);
    }

    /**
     * @return the number of live temporary-file references currently tracked. Test seam: lets a concurrency
     * test assert that every concurrent {@link #createTemporaryFile(String)} was retained — a thread-unsafe
     * set would lose additions under contention. Not used in production.
     */
    int referenceCount() {
        return referenceSet.size();
    }

    /**
     * Create a temporary file inside the application's private, owner-only temp subdirectory (not the shared
     * OS temp root). If recording the reference or notifying the test observer fails, the just-created temp
     * is deleted before propagating — deterministic cleanup on this creation path's own failure, so a
     * decrypted plaintext temp is never stranded.
     *
     * @param name Wanted file name
     * @return New temporary file
     */
    public Path createTemporaryFile(String name) throws IOException {
        Path path = Files.createTempFile(getTemporaryDirectory(), "sismics_docs", name);
        try {
            referenceSet.add(new TemporaryPathReference(path, referenceQueue));
            Consumer<Path> listener = temporaryFileListener;
            if (listener != null) {
                listener.accept(path);
            }
            return path;
        } catch (RuntimeException e) {
            Files.deleteIfExists(path);
            throw e;
        }
    }

    /**
     * Resolve this JVM's own private, owner-only temp directory. All plaintext temp files live here so
     * decrypted document content is never dropped into the world-listable OS temp root. The result is cached
     * (and re-validated cheaply on each call) so all callers — including the startup sweep — share one
     * location. The directory is held under a lifetime {@link FileLock}; if the cached directory has vanished
     * or become unsafe, the stale lock is released and a fresh locked directory is established.
     *
     * @return the private temp directory
     * @throws IOException if a safe directory cannot be established
     */
    public static Path getTemporaryDirectory() throws IOException {
        Path cached = resolvedTemporaryDirectory;
        if (cached != null && isSafeOwnerOnlyDirectory(cached)) {
            return cached;
        }
        synchronized (FileService.class) {
            Path current = resolvedTemporaryDirectory;
            if (current != null && isSafeOwnerOnlyDirectory(current)) {
                return current;
            }
            // The previous directory vanished or became unsafe: drop its lock before establishing a new one so
            // the old channel is not leaked for the life of the JVM.
            releaseOwnDirectoryLock();
            Path resolved = createAndLockOwnDirectory();
            resolvedTemporaryDirectory = resolved;
            return resolved;
        }
    }

    /**
     * Establish this JVM's own private temp directory with its lifetime lock ALREADY held before the directory
     * is discoverable in the swept namespace, closing the creation&rarr;lock TOCTOU:
     * <ol>
     *   <li>create an owner-only directory in a STAGING location OUTSIDE the swept
     *       {@link #TEMP_DIRECTORY_PREFIX} namespace and acquire an exclusive {@link FileLock} on its
     *       {@link #LOCK_FILE_NAME};</li>
     *   <li>atomically rename the already-locked directory INTO the swept namespace.</li>
     * </ol>
     * Because the directory only ever appears in the sweep namespace with its lock held, a concurrently
     * starting JVM can never {@code tryLock} a freshly-created-not-yet-locked directory and delete it as
     * "dead". The lock is retained for the JVM's whole life via {@link #ownDirectoryLock} /
     * {@link #ownDirectoryLockChannel}; only the OS releases it, on process exit. Caller holds the
     * {@code FileService.class} monitor.
     */
    private static Path createAndLockOwnDirectory() throws IOException {
        Path parent = Paths.get(System.getProperty("java.io.tmpdir"));
        Path staging = createOwnerOnlyDirectory(parent, STAGING_DIRECTORY_PREFIX);
        FileChannel channel = null;
        FileLock lock = null;
        boolean published = false;
        try {
            Path lockFile = staging.resolve(LOCK_FILE_NAME);
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) {
                // A brand-new, private, owner-only lock file can only fail to lock on a broken filesystem;
                // fail closed rather than publish an unlocked directory a sibling sweep could reclaim.
                throw new IOException("Unable to acquire the lifetime lock on a freshly created temp directory: "
                        + lockFile);
            }
            Path target = moveIntoSweptNamespace(parent, staging);
            ownDirectoryLockChannel = channel;
            ownDirectoryLock = lock;
            published = true;
            return target;
        } finally {
            if (!published) {
                // Roll back a failed publish: release the lock, close the channel, remove the staging tree.
                closeQuietly(lock);
                closeQuietly(channel);
                deleteDirectoryTreeQuietly(staging);
            }
        }
    }

    /**
     * Atomically rename the already-locked {@code staging} directory to a fresh, non-existent
     * {@link #TEMP_DIRECTORY_PREFIX} name under {@code parent}. The lock is held on an open file descriptor
     * (an inode-level lock on POSIX), so it survives the rename. A random UUID name makes a collision with an
     * existing (possibly live) directory effectively impossible; the existence pre-check is a belt-and-braces
     * guard so a rename can never replace another directory.
     */
    private static Path moveIntoSweptNamespace(Path parent, Path staging) throws IOException {
        for (int attempt = 0; attempt < 16; attempt++) {
            Path target = parent.resolve(TEMP_DIRECTORY_PREFIX + UUID.randomUUID());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                return target;
            } catch (FileAlreadyExistsException e) {
                // Lost a race for this name; retry with a fresh one.
            }
        }
        throw new IOException("Unable to find a free per-JVM temporary directory name under " + parent);
    }

    /**
     * True iff {@code path} is — without following symlinks — a real directory owned by this process user
     * with exactly owner-only ({@code rwx------}) permissions. On a filesystem without POSIX support,
     * ownership and permissions cannot be verified, so a real (non-symlink) directory is accepted.
     */
    private static boolean isSafeOwnerOnlyDirectory(Path path) {
        try {
            PosixFileAttributes attrs = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || !attrs.isDirectory()) {
                return false;
            }
            String me = System.getProperty("user.name");
            if (me != null && !me.equals(attrs.owner().getName())) {
                return false;
            }
            return attrs.permissions().equals(OWNER_ONLY_DIR_PERMISSIONS);
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem: accept a real (non-symlink) directory.
            return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Create a uniquely named, owner-only directory under {@code parent} with the given name prefix. On POSIX
     * the {@code rwx------} permissions are applied atomically at creation; on a non-POSIX filesystem the
     * directory is created then best-effort restricted to its owner.
     */
    private static Path createOwnerOnlyDirectory(Path parent, String prefix) throws IOException {
        try {
            FileAttribute<?> ownerOnly = PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR_PERMISSIONS);
            return Files.createTempDirectory(parent, prefix, ownerOnly);
        } catch (UnsupportedOperationException e) {
            Path dir = Files.createTempDirectory(parent, prefix);
            restrictToOwner(dir);
            return dir;
        }
    }

    private static void restrictToOwner(Path dir) {
        java.io.File file = dir.toFile();
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
        file.setExecutable(true, true);
    }

    /**
     * Release and forget this JVM's lifetime directory lock. Called only under the {@code FileService.class}
     * monitor — on re-resolution (the cached directory vanished) and from the test reset seam. In production
     * the lock is normally never released explicitly; the OS releases it at process exit.
     */
    private static void releaseOwnDirectoryLock() {
        FileLock lock = ownDirectoryLock;
        FileChannel channel = ownDirectoryLockChannel;
        ownDirectoryLock = null;
        ownDirectoryLockChannel = null;
        closeQuietly(lock);
        closeQuietly(channel);
    }

    /**
     * Clear the cached resolved temp directory AND release this JVM's lifetime directory lock. Test seam: lets
     * a test that tampers with the temp location, or that must not leak a held lock into a later test in the
     * same JVM, force a fresh re-resolution. Not used in production.
     */
    static void resetTemporaryDirectoryForTest() {
        synchronized (FileService.class) {
            releaseOwnDirectoryLock();
            resolvedTemporaryDirectory = null;
        }
    }

    /**
     * Reclaim the temp directories of DEAD prior runs without ever touching a live instance's directory. For
     * each sibling {@link #TEMP_DIRECTORY_PREFIX} directory (other than this JVM's own), the sweep validates
     * it is a safe owner-only real directory (never following a symlink) and then TRIES to acquire its lock:
     * <ul>
     *   <li>lock ACQUIRED &rarr; the owner is dead (the OS released a crashed process's lock) &rarr; sweep the
     *       plaintext temps it left behind and remove it;</li>
     *   <li>lock HELD (or this JVM already holds it) &rarr; a live instance owns it &rarr; SKIP.</li>
     * </ul>
     * Best-effort: a failure to list, lock, or delete is logged, not thrown, so a startup hiccup cannot
     * prevent the service from starting.
     */
    private void sweepDeadSiblingDirectories() {
        // Establish (and lifetime-lock) THIS JVM's own directory FIRST, and read it from the resolver rather
        // than from the field: getTemporaryDirectory() returns the published+locked directory, never the brief
        // null between publishing a directory and caching it. A stale null here would make isSameDirectory()
        // below return false for our OWN dir, so the sweep would open+close its .lock and — on POSIX — release
        // the JVM's lifetime lock, letting a peer's later sweep delete this JVM's live plaintext scratch.
        Path own;
        try {
            own = getTemporaryDirectory();
        } catch (IOException e) {
            log.error("Unable to establish the private temporary directory for the startup sweep", e);
            return;
        }
        Path parent = Paths.get(System.getProperty("java.io.tmpdir"));
        List<Path> candidates;
        try (Stream<Path> entries = Files.list(parent)) {
            candidates = entries
                    .filter(p -> p.getFileName().toString().startsWith(TEMP_DIRECTORY_PREFIX))
                    .toList();
        } catch (IOException e) {
            log.error("Unable to list the OS temp root for the startup sweep", e);
            return;
        }
        for (Path dir : candidates) {
            reclaimSiblingIfDead(dir, own);
        }
    }

    /**
     * Reclaim {@code dir} if — and only if — it is a dead prior run's temp directory. Never touches this
     * JVM's own live directory (identified before any channel is opened, which is essential: opening then
     * closing a second descriptor to our own lock file would release our POSIX lock and unprotect our live
     * scratch), never follows a symlink, and never deletes a directory whose lock a live instance holds.
     */
    private void reclaimSiblingIfDead(Path dir, Path own) {
        // Never touch our own live directory. This MUST be checked before opening a channel on its lock file:
        // on POSIX, closing any descriptor to a locked file releases the process's lock on it.
        if (isSameDirectory(dir, own)) {
            return;
        }
        // Same NOFOLLOW / owner-only validation the rest of the service uses: never follow a symlink, never
        // touch a wrong-owner or wrong-permission entry.
        if (!isSafeOwnerOnlyDirectory(dir)) {
            return;
        }
        Path lockFile = dir.resolve(LOCK_FILE_NAME);
        // NOFOLLOW: a sibling that passes the owner-only check could still hold its .lock as a symlink to THIS
        // JVM's own .lock inode; following it and closing the channel would release our lifetime lock. Opening
        // with NOFOLLOW makes a symlinked .lock fail to open (caught, logged, skipped); when .lock is absent,
        // CREATE still makes a fresh regular file (NOFOLLOW only rejects an existing symlink final component).
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                // This JVM already holds this lock — a live owner; leave it be.
                return;
            }
            if (lock == null) {
                // Another live JVM holds the lock — leave it be.
                return;
            }
            try {
                // Lock acquired: the previous owner is dead. Remove the plaintext temps it left, then the
                // now-empty directory. Deleting the lock file while we still hold the lock is safe on POSIX
                // (the lock is released when the channel closes below).
                deleteDirectoryContentsNoFollow(dir);
                Files.deleteIfExists(dir);
            } finally {
                lock.release();
            }
        } catch (IOException e) {
            log.warn("Unable to reclaim a stale temporary directory on startup: " + dir, e);
        }
    }

    /**
     * Delete every REGULAR file directly inside {@code dir}, classifying entries WITHOUT following symlinks so
     * a planted symlink is never followed out of the application namespace. Any non-regular entry (an
     * unexpected subdirectory or symlink) is left in place — the directory then simply is not removed, which
     * is the safe outcome. Best-effort per entry: a failure to delete one is logged, not thrown.
     */
    private static void deleteDirectoryContentsNoFollow(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            entries.forEach(entry -> {
                if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        Files.deleteIfExists(entry);
                    } catch (IOException e) {
                        log.warn("Unable to delete a stale temporary file on startup: " + entry, e);
                    }
                }
            });
        }
    }

    /**
     * True if {@code candidate} and {@code reference} denote the same directory. Uses {@link Files#isSameFile}
     * so symlink/normalisation differences cannot make this JVM's own directory look like a sibling; falls
     * back to path equality if the same-file probe cannot run.
     */
    private static boolean isSameDirectory(Path candidate, Path reference) {
        if (reference == null) {
            return false;
        }
        if (candidate.equals(reference)) {
            return true;
        }
        try {
            return Files.isSameFile(candidate, reference);
        } catch (IOException e) {
            return false;
        }
    }

    /** Best-effort recursive delete of a directory tree, deepest first, without following symlinks. */
    private static void deleteDirectoryTreeQuietly(Path root) {
        if (root == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    /**
     * Phantom reference to a temporary file.
     *
     * @author bgamard
     */
    static class TemporaryPathReference extends PhantomReference<Path> {
        String path;
        TemporaryPathReference(Path referent, ReferenceQueue<? super Path> q) {
            super(referent, q);
            path = referent.toAbsolutePath().toString();
        }
    }
}
