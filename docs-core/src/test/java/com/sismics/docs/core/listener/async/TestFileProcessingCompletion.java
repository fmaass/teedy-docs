package com.sismics.docs.core.listener.async;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.event.FileCreatedAsyncEvent;
import com.sismics.docs.core.event.FileUpdatedAsyncEvent;
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
        protected boolean writeFileIndex(File file) {
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
    public void pdfExtractionFailureIsRetryableNotMarked() throws Exception {
        User user = createUser("proc_pdf_" + UUID.randomUUID().toString().substring(0, 8));
        File file = new File();
        file.setUserId(user.getId());
        file.setName("corrupt.pdf");
        file.setMimeType("application/pdf");
        file.setVersion(0);
        file.setLatestVersion(true);
        file.setSize(8L);
        String fileId = new FileDao().create(file, user.getId());

        // Garbage bytes for a PDF mime: PdfFormatHandler's loader throws, and (blocker 3) it now SURFACES
        // that failure instead of swallowing it to null — so the pipeline classifies it retryable rather than
        // indexing empty content and stamping the file complete.
        Path notAPdf = Files.createTempFile("recon-badpdf-", ".pdf");
        Files.write(notAPdf, "this is not a pdf".getBytes(StandardCharsets.UTF_8));
        try {
            FileProcessingOutcome outcome =
                    new IndexResultListener(true).processFile(eventFor(fileId, notAPdf), true);

            Assertions.assertEquals(FileProcessingOutcome.RETRYABLE_FAILURE, outcome,
                    "a surfaced PDF extraction failure classifies retryable");
            Assertions.assertNull(refetch(fileId).getProcessed(),
                    "a PDF whose extraction failed must NOT be marked complete");
        } finally {
            Files.deleteIfExists(notAPdf);
        }
    }

    @Test
    public void staleClaimantCannotOverwriteCompletedContent() throws Exception {
        User user = createUser("proc_stale_" + UUID.randomUUID().toString().substring(0, 8));
        String fileId = createOrphanTextFile(user);
        FileDao fileDao = new FileDao();

        // Simulate a SUCCESSOR having completed: it reclaimed the row (a new token), wrote good content, and
        // stamped the marker (clearing the token). The stale claimant below holds an OLD token.
        Date now = fileDao.getDatabaseTime();
        fileDao.claimForReprocess(fileId, "successor-token", now, new Date(now.getTime() - 60_000));
        ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_FILE set FIL_CONTENT_C = 'good-content' where FIL_ID_C = :id")
                .setParameter("id", fileId)
                .executeUpdate();
        fileDao.markProcessedIfClaimant(fileId, "successor-token", fileDao.getDatabaseTime());
        ThreadLocalContext.get().getEntityManager().clear();

        // The stale claimant (old token, extracting DIFFERENT content) replays. Its write must be fenced out
        // so it cannot corrupt the successor's completed content/index.
        Path evil = Files.createTempFile("recon-evil-", ".txt");
        Files.write(evil, "evil-content".getBytes(StandardCharsets.UTF_8));
        try {
            FileCreatedAsyncEvent stale = eventFor(fileId, evil);
            stale.setReprocess(true);
            stale.setProcessingToken("stale-token");

            FileProcessingOutcome outcome = new IndexResultListener(true).processFile(stale, true);

            Assertions.assertEquals(FileProcessingOutcome.RETRYABLE_FAILURE, outcome,
                    "a fenced-out stale claimant does nothing and reports retryable");
            Assertions.assertEquals("good-content", refetch(fileId).getContent(),
                    "the stale claimant must NOT overwrite the successor's completed content");
        } finally {
            Files.deleteIfExists(evil);
        }
    }

    private void setContent(String fileId, String content) {
        ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_FILE set FIL_CONTENT_C = :c where FIL_ID_C = :id")
                .setParameter("c", content)
                .setParameter("id", fileId)
                .executeUpdate();
    }

    @Test
    public void firstCreateEventCannotOverwriteCompletedContent() throws Exception {
        User user = createUser("proc_create_" + UUID.randomUUID().toString().substring(0, 8));
        String fileId = createOrphanTextFile(user);
        FileDao fileDao = new FileDao();

        // A successor (reconciler replay) already completed: it wrote good content and stamped the marker,
        // clearing its token. So the row is processed with token == null.
        Date now = fileDao.getDatabaseTime();
        fileDao.claimForReprocess(fileId, "successor-token", now, new Date(now.getTime() - 60_000));
        setContent(fileId, "recovered-content");
        fileDao.markProcessedIfClaimant(fileId, "successor-token", fileDao.getDatabaseTime());
        ThreadLocalContext.get().getEntityManager().clear();

        // A PAUSED first-time live create (FileCreatedAsyncEvent, no token) resumes with a stale extraction.
        // Because it is a first-processing event it must fence on token IS NULL AND processed IS NULL, so the
        // set marker blocks it — it cannot clobber the recovered content.
        Path stale = Files.createTempFile("recon-stale-", ".txt");
        Files.write(stale, "stale-content".getBytes(StandardCharsets.UTF_8));
        try {
            FileProcessingOutcome outcome = new IndexResultListener(true).processFile(eventFor(fileId, stale), true);

            Assertions.assertEquals(FileProcessingOutcome.RETRYABLE_FAILURE, outcome,
                    "a fenced-out first-create event does nothing and reports retryable");
            Assertions.assertEquals("recovered-content", refetch(fileId).getContent(),
                    "a resumed first-create event must NOT overwrite a successor's completed content");
        } finally {
            Files.deleteIfExists(stale);
        }
    }

    @Test
    public void recurringUpdateEventReindexesMarkedFile() throws Exception {
        User user = createUser("proc_update_" + UUID.randomUUID().toString().substring(0, 8));
        String fileId = createOrphanTextFile(user);
        FileDao fileDao = new FileDao();

        // The file is already processed (marked) with old content, unclaimed.
        setContent(fileId, "old-content");
        fileDao.markProcessedIfUnclaimed(fileId, fileDao.getDatabaseTime());
        ThreadLocalContext.get().getEntityManager().clear();

        // A recurring live obligation (attach / manual reprocess) is a FileUpdatedAsyncEvent (isFileCreated
        // false, no token). It must still re-index an already-marked file — the weaker token-IS-NULL fence.
        Path fresh = Files.createTempFile("recon-fresh-", ".txt");
        Files.write(fresh, "new-content".getBytes(StandardCharsets.UTF_8));
        try {
            FileUpdatedAsyncEvent update = new FileUpdatedAsyncEvent();
            update.setUserId("ignored");
            update.setFileId(fileId);
            update.setLanguage("eng");
            update.setUnencryptedFile(fresh);

            FileProcessingOutcome outcome = new IndexResultListener(true).processFile(update, false);

            Assertions.assertEquals(FileProcessingOutcome.COMPLETE, outcome, "a recurring re-index completes");
            Assertions.assertEquals("new-content", refetch(fileId).getContent(),
                    "a recurring update event must re-index (re-write) an already-marked file");
        } finally {
            Files.deleteIfExists(fresh);
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
