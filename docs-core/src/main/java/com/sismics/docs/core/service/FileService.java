package com.sismics.docs.core.service;

import com.google.common.util.concurrent.AbstractScheduledService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
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
     * Name of the private, owner-only subdirectory of the OS temp directory that holds this application's
     * decrypted plaintext temp files. Kept out of the world-listable OS temp root — with owner-only
     * directory permissions where the platform supports them — so other local users cannot enumerate or
     * read decrypted document content.
     */
    private static final String TEMP_SUBDIRECTORY = "teedy_temp";

    /**
     * Owner-only permissions ({@code rwx------}) required of the private temp directory.
     */
    private static final Set<java.nio.file.attribute.PosixFilePermission> OWNER_ONLY_DIR_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");

    /**
     * The resolved, validated-safe private temp directory, cached so every {@link #createTemporaryFile}
     * and the startup sweep share one location — including the freshly created fallback used when the
     * canonical location is found tampered with. Reset in tests via {@link #resetTemporaryDirectoryForTest()}.
     */
    private static volatile Path resolvedTemporaryDirectory;

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
        // Sweep leftover plaintext temp files from a previous run (e.g. after a crash that skipped the
        // deterministic per-request cleanup). Restricted to this application's private temp subdirectory —
        // never the shared OS temp root — so it can only ever remove files this application created.
        sweepTemporaryDirectory();
    }

    @Override
    protected void shutDown() {
        log.info("File service shutting down");
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
     * Resolve the application's private, owner-only temp directory. All plaintext temp files live here so
     * decrypted document content is never dropped into the world-listable OS temp root. The result is cached
     * (and re-validated cheaply on each call) so all callers — including the startup sweep — share one
     * location.
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
            Path resolved = resolveTemporaryDirectory();
            resolvedTemporaryDirectory = resolved;
            return resolved;
        }
    }

    /**
     * Establish a safe private temp directory. The canonical location is {@code <java.io.tmpdir>/teedy_temp};
     * it is used ONLY when it is a real, owner-only directory owned by this process user, validated WITHOUT
     * following symlinks. A pre-existing entry that is a symlink, wrong-owner, wrong-permission, or not a
     * directory is treated as tampering: the method fails CLOSED — it logs loudly and returns a freshly,
     * securely created directory instead, so the startup sweep can never enumerate or delete files outside the
     * application namespace (a planted {@code teedy_temp -> victim} symlink can never redirect the sweep).
     */
    private static Path resolveTemporaryDirectory() throws IOException {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), TEMP_SUBDIRECTORY);
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            if (isSafeOwnerOnlyDirectory(dir)) {
                return dir;
            }
            log.error("Temporary directory {} is not a safe owner-only directory (symlink, wrong owner, wrong " +
                    "permissions, or not a directory) — possible tampering; using a freshly created secure directory", dir);
            return createSecureTemporaryDirectory();
        }
        try {
            createOwnerOnlyDirectory(dir);
        } catch (FileAlreadyExistsException e) {
            // Lost a creation race: validate what is now there, else fail closed onto a fresh secure directory.
            if (isSafeOwnerOnlyDirectory(dir)) {
                return dir;
            }
            return createSecureTemporaryDirectory();
        }
        return dir;
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
     * Create the private temp directory restricted to its owner, atomically. On POSIX the {@code rwx------}
     * permissions are applied at creation; the create fails (rather than adopting an existing entry) if the
     * path already exists. On a non-POSIX filesystem the directory is created then best-effort restricted.
     */
    private static void createOwnerOnlyDirectory(Path dir) throws IOException {
        try {
            FileAttribute<?> ownerOnly = PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR_PERMISSIONS);
            Files.createDirectory(dir, ownerOnly);
        } catch (UnsupportedOperationException e) {
            Files.createDirectory(dir);
            restrictToOwner(dir);
        }
    }

    /**
     * Create a brand-new, uniquely named, owner-only directory under the OS temp root — the fail-closed
     * fallback when the canonical {@code teedy_temp} location is found tampered with.
     */
    private static Path createSecureTemporaryDirectory() throws IOException {
        Path parent = Paths.get(System.getProperty("java.io.tmpdir"));
        try {
            FileAttribute<?> ownerOnly = PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR_PERMISSIONS);
            return Files.createTempDirectory(parent, TEMP_SUBDIRECTORY + "_", ownerOnly);
        } catch (UnsupportedOperationException e) {
            Path dir = Files.createTempDirectory(parent, TEMP_SUBDIRECTORY + "_");
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
     * Clear the cached resolved temp directory. Test seam: lets a test that tampers with the canonical temp
     * location force a fresh re-resolution. Not used in production.
     */
    static void resetTemporaryDirectoryForTest() {
        resolvedTemporaryDirectory = null;
    }

    /**
     * Delete every regular file left in the application's private temp directory. It sweeps ONLY the
     * validated-safe directory returned by {@link #getTemporaryDirectory()} (a real, owner-only directory —
     * never a symlink), and does not follow symlinks when classifying entries, so it can only remove files
     * this application created; the shared OS temp root and any planted symlink target are never touched.
     * Best-effort: a failure to list or delete is logged, not thrown, so a startup hiccup cannot prevent the
     * service from starting.
     */
    private void sweepTemporaryDirectory() {
        Path dir;
        try {
            dir = getTemporaryDirectory();
        } catch (IOException e) {
            log.error("Unable to resolve the temporary file directory for the startup sweep", e);
            return;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Unable to delete a stale temporary file on startup: " + path, e);
                }
            });
        } catch (IOException e) {
            log.error("Unable to list the temporary file directory for the startup sweep", e);
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
