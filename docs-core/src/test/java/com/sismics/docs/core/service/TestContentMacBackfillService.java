package com.sismics.docs.core.service;

import com.google.common.util.concurrent.Service;
import com.sismics.BaseTest;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.ContentMacUtil;
import com.sismics.docs.core.util.FileUtil;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * The #119 content-MAC backfill: it hashes legacy null-MAC files under the correct per-document key,
 * terminal-skips content it cannot recover (so it drains and never re-selects forever), is idempotent
 * across instances, and is a complete no-op when the feature is off.
 */
public class TestContentMacBackfillService extends BaseTest {

    private static final String MASTER_KEY = "backfill-master-secret";

    @BeforeEach
    public void resetBefore() {
        ContentMacUtil.resetForTest();
    }

    @AfterEach
    public void resetAfter() {
        ContentMacUtil.resetForTest();
    }

    private interface TxWork<T> {
        T run() throws Exception;
    }

    private static <T> T inTx(TxWork<T> work) throws Exception {
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext.get().setEntityManager(em);
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        try {
            T result = work.run();
            tx.commit();
            return result;
        } catch (Exception e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            throw e;
        } finally {
            if (em.isOpen()) {
                em.close();
            }
            ThreadLocalContext.cleanup();
        }
    }

    private String createUser() throws Exception {
        String username = "bf_" + UUID.randomUUID().toString().substring(0, 12);
        return inTx(() -> {
            User user = new User();
            user.setUsername(username);
            user.setPassword("12345678");
            user.setEmail(username + "@docs.com");
            user.setRoleId("admin");
            user.setStorageQuota(100_000_000L);
            return new UserDao().create(user, username);
        });
    }

    private String createDocument(String userId) throws Exception {
        return inTx(() -> {
            Document document = new Document();
            document.setUserId(userId);
            document.setLanguage("eng");
            document.setTitle("bf doc");
            document.setCreateDate(new Date());
            return new DocumentDao().create(document, userId);
        });
    }

    /** Create a real file (encrypted blob on disk) with the feature OFF, so its stored MAC starts null. */
    private String createLegacyFile(String userId, String documentId, String content) throws Exception {
        Path src = Files.createTempFile("bf-src", ".txt");
        Files.write(src, content.getBytes(StandardCharsets.UTF_8));
        try {
            return inTx(() -> FileUtil.createFile("legacy.txt", null, src, Files.size(src), "eng", userId, documentId));
        } finally {
            Files.deleteIfExists(src);
        }
    }

    private String macOf(String fileId) throws Exception {
        return inTx(() -> new FileDao().getActiveById(fileId).getContentMac());
    }

    // --- happy path: a legacy null-MAC file is hashed under its document key ---------------------------

    @Test
    public void backfill_hashesLegacyFile() throws Exception {
        String userId = createUser();
        String docId = createDocument(userId);
        String fileId = createLegacyFile(userId, docId, "legacy-content");
        Assertions.assertNull(macOf(fileId), "created with the feature off -> null MAC");

        ContentMacUtil.setMasterKeyForTest(MASTER_KEY);
        ContentMacBackfillService service = new ContentMacBackfillService();
        inTx(() -> {
            service.processFile(new FileDao().getActiveById(fileId));
            return null;
        });

        String expected = ContentMacUtil.computeMac(docId, new ByteArrayInputStream("legacy-content".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertEquals(expected, macOf(fileId), "the backfilled MAC must match the pinned definition over the decrypted plaintext");
    }

    // --- drain: the null-MAC scan empties after the batch is processed --------------------------------

    @Test
    public void backfill_drainsTheNullMacScan() throws Exception {
        String userId = createUser();
        String docId = createDocument(userId);
        String a = createLegacyFile(userId, docId, "one");
        String b = createLegacyFile(userId, docId, "two");
        ContentMacUtil.setMasterKeyForTest(MASTER_KEY);

        // Process every currently-pending null-MAC row (the shared test DB may hold rows from sibling tests;
        // this test only asserts that ITS OWN two files stop being selected after processing).
        ContentMacBackfillService service = new ContentMacBackfillService();
        inTx(() -> {
            for (File f : new FileDao().getActiveFilesWithNullMac(1000)) {
                service.processFile(f);
            }
            return null;
        });
        Assertions.assertNotNull(macOf(a), "file a is hashed after the backfill");
        Assertions.assertNotNull(macOf(b), "file b is hashed after the backfill");
        List<String> pending = inTx(() -> new FileDao().getActiveFilesWithNullMac(1000).stream().map(File::getId).toList());
        Assertions.assertFalse(pending.contains(a), "file a must not be re-selected");
        Assertions.assertFalse(pending.contains(b), "file b must not be re-selected");
    }

    // --- terminal skip: a missing blob is stamped, not left NULL to re-select forever -----------------

    @Test
    public void backfill_terminalSkipForUnrecoverableContent() throws Exception {
        String userId = createUser();
        String docId = createDocument(userId);
        // A file row with NO blob on disk (unrecoverable content).
        String fileId = inTx(() -> {
            File file = new File();
            file.setId(UUID.randomUUID().toString());
            file.setUserId(userId);
            file.setDocumentId(docId);
            file.setName("ghost.txt");
            file.setMimeType("text/plain");
            file.setVersion(0);
            file.setLatestVersion(true);
            file.setSize(10L);
            return new FileDao().create(file, userId);
        });

        ContentMacUtil.setMasterKeyForTest(MASTER_KEY);
        ContentMacBackfillService service = new ContentMacBackfillService();
        inTx(() -> {
            service.processFile(new FileDao().getActiveById(fileId));
            return null;
        });

        Assertions.assertEquals(ContentMacBackfillService.SKIP_MARKER, macOf(fileId),
                "an unrecoverable row must get a terminal skip marker");
        List<String> pending = inTx(() -> new FileDao().getActiveFilesWithNullMac(1000).stream().map(File::getId).toList());
        Assertions.assertFalse(pending.contains(fileId), "the skip-marked row must NOT be re-selected");
    }

    // --- idempotent across instances: a second backfill does not overwrite ----------------------------

    @Test
    public void backfill_idempotentAcrossInstances() throws Exception {
        String userId = createUser();
        String docId = createDocument(userId);
        String fileId = createLegacyFile(userId, docId, "idem-content");
        ContentMacUtil.setMasterKeyForTest(MASTER_KEY);

        inTx(() -> {
            new ContentMacBackfillService().processFile(new FileDao().getActiveById(fileId));
            return null;
        });
        String first = macOf(fileId);
        Assertions.assertNotNull(first);

        // A second instance re-processing the same row must be a no-op (conditional update touches 0 rows).
        inTx(() -> {
            new ContentMacBackfillService().processFile(new FileDao().getActiveById(fileId));
            return null;
        });
        Assertions.assertEquals(first, macOf(fileId), "a second backfill must not overwrite the existing MAC");
    }

    // --- FIX 1: each file is backfilled in its OWN transaction (per-file commit boundary) --------------

    /**
     * The backfill must process each file in its own transaction so it never holds more than one T_FILE row
     * lock at a time (a single-row lock can never be half of a deadlock cycle vs. a concurrent unordered
     * user-deletion bulk update). This is observable as a per-file COMMIT boundary: a poison row placed LAST
     * throws after the earlier rows have each already committed, so the earlier rows stay hashed and only the
     * poison row is left null. Under the pre-fix single-transaction batch the poison throw would roll back the
     * WHOLE batch, discarding the earlier rows' MACs — which is exactly the mutation this test detects.
     */
    @Test
    public void backfill_commitsEachFilePerTransaction() throws Exception {
        String userId = createUser();
        String docId = createDocument(userId);
        String good1 = createLegacyFile(userId, docId, "batch-one");
        String good2 = createLegacyFile(userId, docId, "batch-two");
        String poison = createLegacyFile(userId, docId, "batch-poison");
        ContentMacUtil.setMasterKeyForTest(MASTER_KEY);

        // A backfill whose processFile throws for exactly the poison row and delegates for the others.
        ContentMacBackfillService service = new ContentMacBackfillService() {
            @Override
            void processFile(File file) {
                if (poison.equals(file.getId())) {
                    throw new RuntimeException("simulated per-file failure");
                }
                super.processFile(file);
            }
        };

        // Poison LAST, so good1/good2 are processed (and, with the fix, committed) before the poison throws.
        List<File> batch = inTx(() -> List.of(
                new FileDao().getActiveById(good1),
                new FileDao().getActiveById(good2),
                new FileDao().getActiveById(poison)));
        service.processBatch(batch);

        Assertions.assertNotNull(macOf(good1),
                "an earlier file must stay committed even though a later file in the batch threw");
        Assertions.assertNotNull(macOf(good2),
                "an earlier file must stay committed even though a later file in the batch threw");
        Assertions.assertNull(macOf(poison),
                "the poison file's own transaction rolled back, leaving its MAC null");
    }

    // --- self-stop: runOneIteration stops the service once the null-MAC scan drains -------------------

    /**
     * With the feature on, a short (here empty) batch means the null-MAC scan is exhausted, so runOneIteration
     * must self-stop the service. The batch source is overridden to isolate the stop decision from shared-DB
     * state (sibling tests may hold their own null-MAC rows on the shared PostgreSQL gate).
     */
    @Test
    public void backfill_runOneIterationSelfStopsWhenBatchDrains() {
        ContentMacUtil.setMasterKeyForTest(MASTER_KEY);
        ContentMacBackfillService service = new ContentMacBackfillService() {
            @Override
            List<File> fetchNullMacBatch() {
                return List.of();
            }
        };
        service.runOneIteration();
        Assertions.assertEquals(Service.State.TERMINATED, service.state(),
                "runOneIteration must self-stop once the null-MAC scan drains (a short batch)");
    }

    // --- FIX 2: a partial batch with a swallowed TRANSIENT failure must NOT self-stop -----------------

    /**
     * The drain/self-stop decision must distinguish a genuinely-exhausted partial batch from one where a
     * file failed transiently (a caught DB/commit hiccup) and was skipped. A partial batch ({@code <
     * BATCH_SIZE}) that contains a swallowed transient failure still has a null-MAC row left, so work
     * REMAINS: the service must NOT stop — the next scheduled iteration retries the still-null row. The
     * other (non-failing) files must still be committed. Mutation proof: the pre-fix
     * {@code if (files.size() < BATCH_SIZE) stopAsync()} (stopping on ANY partial batch, regardless of a
     * swallowed failure) terminates the service here, making this test fail with a null-MAC row stranded.
     */
    @Test
    public void backfill_doesNotSelfStopWhenPartialBatchHasTransientFailure() throws Exception {
        String userId = createUser();
        String docId = createDocument(userId);
        String good = createLegacyFile(userId, docId, "committed-alongside-a-transient-failure");
        String poison = createLegacyFile(userId, docId, "transiently-failing-row");
        ContentMacUtil.setMasterKeyForTest(MASTER_KEY);

        // A partial batch (2 < BATCH_SIZE) whose poison row throws a TRANSIENT failure (caught + swallowed
        // by processBatch), the good row committing normally. fetchNullMacBatch is overridden to isolate the
        // stop decision from shared-DB state (sibling tests may hold their own null-MAC rows on the PG gate).
        List<File> batch = inTx(() -> List.of(
                new FileDao().getActiveById(good),
                new FileDao().getActiveById(poison)));
        ContentMacBackfillService service = new ContentMacBackfillService() {
            @Override
            List<File> fetchNullMacBatch() {
                return batch;
            }

            @Override
            void processFile(File file) {
                if (poison.equals(file.getId())) {
                    throw new RuntimeException("simulated transient DB/commit hiccup");
                }
                super.processFile(file);
            }
        };
        service.runOneIteration();

        Assertions.assertNotEquals(Service.State.TERMINATED, service.state(),
                "a partial batch with a swallowed transient failure must NOT self-stop the service (work remains)");
        Assertions.assertNotNull(macOf(good),
                "the non-failing file must be committed even though a sibling row failed transiently");
        Assertions.assertNull(macOf(poison),
                "the transiently-failed row stays null so the next scheduled iteration retries it");
    }

    // --- FIX 2: a partial batch fully processed (commit + terminal skip) IS a genuine drain -> stop -----

    /**
     * The counterpart to the transient-failure case: a partial batch where EVERY file was processed —
     * one committed a real MAC and one was terminal-skip-marked (undecryptable content) — is a genuine
     * drain, so the service DOES self-stop. This pins that a terminal skip counts as PROCESSED and does
     * NOT block the stop (SKIP_MARKER is written inside processFile without throwing, so it is not a
     * transient failure). Mutation proof: inverting the guard to {@code failures != 0} (treating a
     * fully-processed batch as "not drained") stops the service only when a failure occurred, so this
     * fully-processed batch would never terminate and the test fails.
     */
    @Test
    public void backfill_selfStopsWhenPartialBatchFullyProcessedIncludingTerminalSkip() throws Exception {
        String userId = createUser();
        String docId = createDocument(userId);
        String good = createLegacyFile(userId, docId, "committed-content");
        // A file row with NO blob on disk: undecryptable -> a TERMINAL skip (SKIP_MARKER), which is a
        // processed/DONE row, NOT a transient failure that would block the drain/stop.
        String ghost = inTx(() -> {
            File file = new File();
            file.setId(UUID.randomUUID().toString());
            file.setUserId(userId);
            file.setDocumentId(docId);
            file.setName("ghost.txt");
            file.setMimeType("text/plain");
            file.setVersion(0);
            file.setLatestVersion(true);
            file.setSize(10L);
            return new FileDao().create(file, userId);
        });
        ContentMacUtil.setMasterKeyForTest(MASTER_KEY);

        List<File> batch = inTx(() -> List.of(
                new FileDao().getActiveById(good),
                new FileDao().getActiveById(ghost)));
        ContentMacBackfillService service = new ContentMacBackfillService() {
            @Override
            List<File> fetchNullMacBatch() {
                return batch;
            }
        };
        service.runOneIteration();

        Assertions.assertEquals(Service.State.TERMINATED, service.state(),
                "a partial batch fully processed (a commit + a terminal skip) is a genuine drain -> self-stop");
        Assertions.assertNotNull(macOf(good), "the recoverable file is hashed");
        Assertions.assertEquals(ContentMacBackfillService.SKIP_MARKER, macOf(ghost),
                "the undecryptable row is terminal-skip-marked (processed), not a transient failure blocking the stop");
    }

    // --- no-op when the feature is off: rows stay NULL, the service stops ------------------------------

    @Test
    public void backfill_noopWhenDisabled() throws Exception {
        String userId = createUser();
        String docId = createDocument(userId);
        String fileId = createLegacyFile(userId, docId, "disabled-content");
        Assertions.assertFalse(ContentMacUtil.isEnabled(), "no master secret -> feature off");

        // runOneIteration must short-circuit (isEnabled() false) and touch no rows.
        new ContentMacBackfillService().runOneIteration();
        Assertions.assertNull(macOf(fileId), "with the feature off the backfill leaves the MAC null (no skip marker)");
    }
}
