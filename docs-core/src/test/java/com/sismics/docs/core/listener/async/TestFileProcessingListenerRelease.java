package com.sismics.docs.core.listener.async;

import com.sismics.BaseTest;
import com.sismics.docs.core.event.FileCreatedAsyncEvent;
import com.sismics.docs.core.event.FileEvent;
import com.sismics.docs.core.event.FileUpdatedAsyncEvent;
import com.sismics.docs.core.util.FileUtil;
import com.sismics.util.context.ThreadLocalContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies the async listener ALWAYS releases the in-memory processing marker AND deletes the plaintext
 * temp file once it has been handed an event — even when the file processing throws an {@link Error}, and
 * exactly once on the early deleted-file return path. A stranded marker would block any later rotation /
 * reprocess of that file until the JVM restarts, and an undeleted temp leaks decrypted content. These are
 * release-guarantee tests, so the file-processing WORK (a dependency of the release guarantee) is driven
 * to fail through the real {@link FileProcessingAsyncListener#processFile} seam — the marker-release
 * finally under test is the real one.
 */
public class TestFileProcessingListenerRelease extends BaseTest {

    @BeforeEach
    public void setUp() {
        ThreadLocalContext.cleanup();
    }

    @AfterEach
    public void tearDown() {
        ThreadLocalContext.cleanup();
    }

    /** Listener whose file-processing body throws an Error — the hardest case for the release finally. */
    private static final class ErrorThrowingListener extends FileProcessingAsyncListener {
        @Override
        protected void processFile(FileEvent event, boolean isFileCreated) {
            throw new AssertionError("injected processing failure");
        }
    }

    @Test
    public void errorDuringProcessingReleasesMarkerCreatedEvent() {
        String fileId = "listener-error-create-" + UUID.randomUUID();
        FileUtil.startProcessingFile(fileId);
        FileCreatedAsyncEvent event = new FileCreatedAsyncEvent();
        event.setFileId(fileId);

        Assertions.assertThrows(AssertionError.class, () -> new ErrorThrowingListener().on(event),
                "an Error in processing must still propagate");
        Assertions.assertFalse(FileUtil.isProcessingFile(fileId),
                "the marker must be released even when processing throws an Error (created event)");
    }

    @Test
    public void errorDuringProcessingReleasesMarkerUpdatedEvent() {
        String fileId = "listener-error-update-" + UUID.randomUUID();
        FileUtil.startProcessingFile(fileId);
        FileUpdatedAsyncEvent event = new FileUpdatedAsyncEvent();
        event.setFileId(fileId);

        Assertions.assertThrows(AssertionError.class, () -> new ErrorThrowingListener().on(event),
                "an Error in processing must still propagate");
        Assertions.assertFalse(FileUtil.isProcessingFile(fileId),
                "the marker must be released even when processing throws an Error (updated event)");
    }

    // The listener DELETES the plaintext temp the event carried even when processing fails (created path).
    // The temp lives in the system temp dir, not storageDir/<fileId>, so it is not the stored-file alias.
    @Test
    public void errorDuringProcessingDeletesTempCreatedEvent() throws Exception {
        String fileId = "listener-temp-create-" + UUID.randomUUID();
        Path temp = Files.createTempFile("listener-plain-", ".tmp");
        Files.write(temp, new byte[]{1, 2, 3});
        FileUtil.startProcessingFile(fileId);
        FileCreatedAsyncEvent event = new FileCreatedAsyncEvent();
        event.setFileId(fileId);
        event.setUnencryptedFile(temp);

        Assertions.assertThrows(AssertionError.class, () -> new ErrorThrowingListener().on(event));
        Assertions.assertFalse(FileUtil.isProcessingFile(fileId), "the marker is released");
        Assertions.assertFalse(Files.exists(temp),
                "the plaintext temp must be deleted after a processing failure (created event)");
    }

    // As above for the updated path.
    @Test
    public void errorDuringProcessingDeletesTempUpdatedEvent() throws Exception {
        String fileId = "listener-temp-update-" + UUID.randomUUID();
        Path temp = Files.createTempFile("listener-plain-", ".tmp");
        Files.write(temp, new byte[]{4, 5, 6});
        FileUtil.startProcessingFile(fileId);
        FileUpdatedAsyncEvent event = new FileUpdatedAsyncEvent();
        event.setFileId(fileId);
        event.setUnencryptedFile(temp);

        Assertions.assertThrows(AssertionError.class, () -> new ErrorThrowingListener().on(event));
        Assertions.assertFalse(FileUtil.isProcessingFile(fileId), "the marker is released");
        Assertions.assertFalse(Files.exists(temp),
                "the plaintext temp must be deleted after a processing failure (updated event)");
    }

    // The deleted-file early-return path releases the marker EXACTLY once through the single terminal
    // finally. Two things make this a faithful exactly-once check rather than a set-state check (which
    // Set.remove's idempotency would defeat): (1) deleteUnencryptedFile is nested with endProcessingFile
    // in the SAME finally (one call each), so its count is the terminal-release count; (2) the assertion
    // that the marker is STILL set WHEN deleteUnencryptedFile runs fails if any earlier release fired
    // first — e.g. a re-introduced early endProcessingFile before the finally — so it detects a double
    // release, not just the final state.
    @Test
    public void deletedFilePathReleasesExactlyOnce() {
        String fileId = "listener-deleted-" + UUID.randomUUID();
        FileUtil.startProcessingFile(fileId);
        AtomicInteger releaseCount = new AtomicInteger();

        FileProcessingAsyncListener listener = new FileProcessingAsyncListener() {
            @Override
            void deleteUnencryptedFile(FileEvent event) {
                Assertions.assertTrue(FileUtil.isProcessingFile(fileId),
                        "the marker is still set when the temp-cleanup runs (release is nested AFTER it)");
                releaseCount.incrementAndGet();
                super.deleteUnencryptedFile(event);
            }
        };

        // No file row with this id exists, so processFile returns on the deleted-file branch.
        FileCreatedAsyncEvent event = new FileCreatedAsyncEvent();
        event.setFileId(fileId);
        listener.on(event);

        Assertions.assertEquals(1, releaseCount.get(),
                "the terminal release path runs exactly once on the deleted-file branch");
        Assertions.assertFalse(FileUtil.isProcessingFile(fileId), "the marker is released");
    }
}
