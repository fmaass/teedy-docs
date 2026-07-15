package com.sismics.docs.core.service;

import com.google.common.util.concurrent.AbstractScheduledService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
     * Phantom references queue.
     */
    private final ReferenceQueue<Path> referenceQueue = new ReferenceQueue<>();
    private final Set<TemporaryPathReference> referenceSet = new HashSet<>();

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
     * Create a temporary file.
     *
     * @param name Wanted file name
     * @return New temporary file
     */
    public Path createTemporaryFile(String name) throws IOException {
        Path path = Files.createTempFile("sismics_docs", name);
        referenceSet.add(new TemporaryPathReference(path, referenceQueue));
        Consumer<Path> listener = temporaryFileListener;
        if (listener != null) {
            listener.accept(path);
        }
        return path;
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
