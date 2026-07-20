package com.sismics.docs.core.listener.async;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.event.FileCreatedAsyncEvent;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.UUID;

/**
 * The durable completion marker (#159) is written ONLY when the processing pipeline reaches a real
 * COMPLETE state. A file whose index write fails is a RETRYABLE failure: it must NOT be marked complete,
 * and the reconciliation scan must still select it (so it is retried). The success contrast proves the
 * gate is real — with a committing index write the same file IS marked and drops out of the scan.
 */
public class TestFileProcessingCompletion extends BaseTransactionalTest {

    /** Listener whose index write result is forced, isolating the completion gate from a real index. */
    private static final class IndexResultListener extends FileProcessingAsyncListener {
        private final boolean indexOk;

        private IndexResultListener(boolean indexOk) {
            this.indexOk = indexOk;
        }

        @Override
        protected boolean writeFileIndex(File file, boolean keyed) {
            return indexOk;
        }
    }

    private String createOrphanTextFile(User user) {
        File file = new File();
        file.setUserId(user.getId());
        file.setName("note.txt");
        file.setMimeType("text/plain");
        file.setVersion(0);
        file.setLatestVersion(true);
        file.setSize(20L);
        return new FileDao().create(file, user.getId());
    }

    private FileCreatedAsyncEvent eventFor(String fileId, Path plaintext) {
        FileCreatedAsyncEvent event = new FileCreatedAsyncEvent();
        event.setUserId("ignored");
        event.setFileId(fileId);
        event.setLanguage("eng");
        event.setUnencryptedFile(plaintext);
        return event;
    }

    private File refetch(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.clear();
        return new FileDao().getActiveById(id);
    }

    @Test
    public void indexWriteFailureIsRetryableAndNotMarked() throws Exception {
        User user = createUser("proc_fail_" + UUID.randomUUID().toString().substring(0, 8));
        String fileId = createOrphanTextFile(user);
        Path plaintext = Files.createTempFile("recon-plain-", ".txt");
        Files.write(plaintext, "searchable text body".getBytes(StandardCharsets.UTF_8));
        try {
            FileProcessingOutcome outcome =
                    new IndexResultListener(false).processFile(eventFor(fileId, plaintext), true);

            Assertions.assertEquals(FileProcessingOutcome.RETRYABLE_FAILURE, outcome,
                    "a failed index write is a retryable failure");
            Assertions.assertNull(refetch(fileId).getProcessed(),
                    "a retryable failure must NOT write the completion marker");

            // Retried: the reconciliation scan still selects the unprocessed file.
            Date cutoff = new Date(new FileDao().getDatabaseTime().getTime() - 60_000);
            boolean selectable = new FileDao().getFilesToReconcile(cutoff, 1000).stream()
                    .anyMatch(f -> fileId.equals(f.getId()));
            Assertions.assertTrue(selectable, "the unmarked file is still selected by the reconciliation scan");
        } finally {
            Files.deleteIfExists(plaintext);
        }
    }

    @Test
    public void unsupportedFormatIsMarkedCompleteAndNotReprocessed() throws Exception {
        User user = createUser("proc_unsup_" + UUID.randomUUID().toString().substring(0, 8));
        // A MIME with no format handler: content is legitimately null, which is a terminal SUCCESS (not a
        // failure), so the file is marked complete and must NOT be re-selected on every boot.
        File file = new File();
        file.setUserId(user.getId());
        file.setName("blob.bin");
        file.setMimeType("application/x-teedy-unknown");
        file.setVersion(0);
        file.setLatestVersion(true);
        file.setSize(5L);
        String fileId = new FileDao().create(file, user.getId());

        // No unencrypted temp is needed: extraction returns as soon as no handler matches, before reading it.
        FileProcessingOutcome outcome =
                new IndexResultListener(true).processFile(eventFor(fileId, null), true);

        Assertions.assertEquals(FileProcessingOutcome.COMPLETE, outcome,
                "an unsupported format is a terminal SUCCESS (no handler), not a failure");
        Assertions.assertNotNull(refetch(fileId).getProcessed(), "the file is marked complete");

        Date cutoff = new Date(new FileDao().getDatabaseTime().getTime() - 60_000);
        boolean selectable = new FileDao().getFilesToReconcile(cutoff, 1000).stream()
                .anyMatch(f -> fileId.equals(f.getId()));
        Assertions.assertFalse(selectable, "an unsupported-format file is not reprocessed on the next boot");
    }

    @Test
    public void successMarksCompleteAndDropsOutOfScan() throws Exception {
        User user = createUser("proc_ok_" + UUID.randomUUID().toString().substring(0, 8));
        String fileId = createOrphanTextFile(user);
        Path plaintext = Files.createTempFile("recon-plain-", ".txt");
        Files.write(plaintext, "searchable text body".getBytes(StandardCharsets.UTF_8));
        try {
            FileProcessingOutcome outcome =
                    new IndexResultListener(true).processFile(eventFor(fileId, plaintext), true);

            Assertions.assertEquals(FileProcessingOutcome.COMPLETE, outcome,
                    "a fully-successful run is COMPLETE");
            File done = refetch(fileId);
            Assertions.assertNotNull(done.getProcessed(),
                    "a COMPLETE run writes the durable completion marker (live unclaimed path)");
            Assertions.assertNotNull(done.getContent(), "the extracted content is saved");

            Date cutoff = new Date(new FileDao().getDatabaseTime().getTime() - 60_000);
            boolean selectable = new FileDao().getFilesToReconcile(cutoff, 1000).stream()
                    .anyMatch(f -> fileId.equals(f.getId()));
            Assertions.assertFalse(selectable, "a marked file is no longer selected by the reconciliation scan");
        } finally {
            Files.deleteIfExists(plaintext);
        }
    }
}
