package com.sismics.docs.core.util;

import com.sismics.BaseTest;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Content-hash duplicate detection (#119) driven through {@link FileUtil#createFile}: the degrade-to-off
 * path, MAC persistence, the non-mutating no-op collapse (and its strict predecessor binding), the advisory
 * renamed-duplicate hint, cross-document MAC non-linkage, and the invariant that an equal MAC against a
 * stale/superseded predecessor is a 409 rather than a spurious success. Runs on H2 and PostgreSQL.
 */
public class TestContentMacDedup extends BaseTest {

    private static final String MASTER_KEY = "test-master-secret-#119";

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
        ThreadLocalContext context = ThreadLocalContext.get();
        context.setEntityManager(em);
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
        String username = "mac_" + UUID.randomUUID().toString().substring(0, 12);
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
            document.setTitle("mac doc");
            document.setCreateDate(new Date());
            return new DocumentDao().create(document, userId);
        });
    }

    /** Write a plaintext temp with the EXACT given content (so two callers can produce identical bytes). */
    private Path writeContent(String content) throws Exception {
        Path src = Files.createTempFile("mac-src", ".txt");
        Files.write(src, content.getBytes(StandardCharsets.UTF_8));
        return src;
    }

    /** Create via the 7-arg compatibility wrapper (computes the MAC itself); returns the resulting file id. */
    private String createViaWrapper(String userId, String documentId, String previousFileId, String content) throws Exception {
        Path src = writeContent(content);
        return inTx(() -> FileUtil.createFile("f.txt", previousFileId, src, Files.size(src), "eng", userId, documentId));
    }

    /** Create via the rich 8-arg overload, computing the MAC the way FileResource does, returning the result. */
    private FileCreatedResult createRich(String userId, String documentId, String previousFileId, String content, Path src) throws Exception {
        String mac;
        try (ByteArrayInputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
            mac = ContentMacUtil.computeMac(documentId, in);
        }
        return inTx(() -> FileUtil.createFile("f.txt", previousFileId, src, Files.size(src), "eng", userId, documentId, mac));
    }

    private File activeFile(String fileId) throws Exception {
        return inTx(() -> new FileDao().getActiveById(fileId));
    }

    private long storageCurrent(String userId) throws Exception {
        return inTx(() -> {
            User u = new UserDao().getById(userId);
            return u.getStorageCurrent() == null ? 0L : u.getStorageCurrent();
        });
    }

    private List<File> latestFiles(String userId, String documentId) throws Exception {
        return inTx(() -> new FileDao().getByDocumentId(userId, documentId));
    }

    // --- degrade to off: feature disabled -> no MAC, no no-op, no error --------------------------------

    @Test
    public void degradeToOff_noMacNoNoop() throws Exception {
        // No master secret set -> resolves to disabled from the (unset) environment.
        Assertions.assertFalse(ContentMacUtil.isEnabled(), "with no master secret the feature must be OFF");

        String userId = createUser();
        String docId = createDocument(userId);
        String v0 = createViaWrapper(userId, docId, null, "same-bytes");
        Assertions.assertNull(activeFile(v0).getContentMac(), "with the feature off the stored MAC must be null");

        // An identical "new version" upload while off must CREATE a real new version (no no-op collapse).
        String v1 = createViaWrapper(userId, docId, v0, "same-bytes");
        Assertions.assertNotEquals(v0, v1, "with the feature off an identical new-version upload must create a real new version");
        Assertions.assertNull(activeFile(v1).getContentMac());
        List<File> latest = latestFiles(userId, docId);
        Assertions.assertEquals(1, latest.size(), "the chain still shows exactly one latest");
        Assertions.assertEquals(v1, latest.get(0).getId());
    }

    // --- enabled: every manual-pipeline upload persists a MAC -----------------------------------------

    @Test
    public void enabled_uploadPersistsMac() throws Exception {
        ContentMacUtil.setMasterKeyForTest(MASTER_KEY);
        String userId = createUser();
        String docId = createDocument(userId);
        String v0 = createViaWrapper(userId, docId, null, "persisted-content");
        String mac = activeFile(v0).getContentMac();
        Assertions.assertNotNull(mac, "an enabled upload must persist a MAC");
        Assertions.assertTrue(mac.matches("[0-9a-f]{64}"), "the MAC is 64 lowercase hex chars, got: " + mac);
    }

    // --- no-op: identical new version onto the current predecessor collapses ----------------------------

    @Test
    public void noop_identicalCurrentPredecessorCollapses() throws Exception {
        ContentMacUtil.setMasterKeyForTest(MASTER_KEY);
        String userId = createUser();
        String docId = createDocument(userId);
        String v0 = createViaWrapper(userId, docId, null, "collapse-me");
        long usageAfterV0 = storageCurrent(userId);
        boolean v0Latest = activeFile(v0).isLatestVersion();
        Assertions.assertTrue(v0Latest);

        // Identical content, previousFileId = the current predecessor -> NO-OP: returns the predecessor id.
        String result = createViaWrapper(userId, docId, v0, "collapse-me");
        Assertions.assertEquals(v0, result, "an identical new version onto the current predecessor must collapse to it");

        List<File> latest = latestFiles(userId, docId);
        Assertions.assertEquals(1, latest.size(), "no new version row may be created by a no-op");
        Assertions.assertEquals(v0, latest.get(0).getId(), "the predecessor stays the single latest");
        Assertions.assertTrue(activeFile(v0).isLatestVersion(), "the predecessor must not be demoted");
        Assertions.assertEquals(usageAfterV0, storageCurrent(userId), "a no-op must reserve NO quota");
    }

    // --- no-op leaves the plaintext temp for the request to clean (tempAccepted=false) ------------------

    @Test
    public void noop_reportsNoAcquiredResources_realCreateReportsAll() throws Exception {
        ContentMacUtil.setMasterKeyForTest(MASTER_KEY);
        String userId = createUser();
        String docId = createDocument(userId);

        Path realSrc = writeContent("temp-accounting");
        FileCreatedResult realResult = createRich(userId, docId, null, "temp-accounting", realSrc);
        Assertions.assertTrue(realResult.isCreated());
        Assertions.assertTrue(realResult.isTempAccepted(), "a real create hands the temp to the pipeline");
        Assertions.assertTrue(realResult.isQuotaReserved());
        Assertions.assertTrue(realResult.isBlobCommitted());

        Path noopSrc = writeContent("temp-accounting");
        FileCreatedResult noopResult = createRich(userId, docId, realResult.getFileId(), "temp-accounting", noopSrc);
        Assertions.assertFalse(noopResult.isCreated(), "an identical current-predecessor upload is a no-op");
        Assertions.assertFalse(noopResult.isTempAccepted(), "a no-op does NOT take the temp -> the request deletes it");
        Assertions.assertFalse(noopResult.isQuotaReserved(), "a no-op reserves no quota");
        Assertions.assertFalse(noopResult.isBlobCommitted(), "a no-op writes no blob");
        Assertions.assertTrue(Files.exists(noopSrc), "the no-op path must not delete the caller-owned temp");
        Assertions.assertEquals(realResult.getFileId(), noopResult.getFileId());
        Files.deleteIfExists(realSrc);
        Files.deleteIfExists(noopSrc);
    }

    // --- INVARIANT: an equal MAC against a STALE predecessor is a 409, never a spurious success --------

    @Test
    public void invariant_equalMacAgainstStalePredecessorIsConflict() throws Exception {
        ContentMacUtil.setMasterKeyForTest(MASTER_KEY);
        String userId = createUser();
        String docId = createDocument(userId);
        String v0 = createViaWrapper(userId, docId, null, "base-content");
        // A genuine new version with DIFFERENT content demotes v0 -> v0 is now stale.
        String v1 = createViaWrapper(userId, docId, v0, "different-content");
        Assertions.assertNotEquals(v0, v1);
        Assertions.assertFalse(activeFile(v0).isLatestVersion(), "v0 is demoted (stale)");

        // Now upload content IDENTICAL to v0 naming the STALE v0 as the base: the stored MAC equals the
        // computed MAC, but v0 is no longer the current latest -> must be a version conflict, not success.
        Assertions.assertThrows(VersionConcurrencyException.class,
                () -> createViaWrapper(userId, docId, v0, "base-content"),
                "an equal MAC against a superseded predecessor must be a 409, never a spurious no-op success");

        String versionId = activeFile(v1).getVersionId();
        long latestCount = inTx(() -> {
            Number n = (Number) ThreadLocalContext.get().getEntityManager().createQuery(
                            "select count(f) from File f where f.versionId = :v and f.latestVersion = true and f.deleteDate is null")
                    .setParameter("v", versionId).getSingleResult();
            return n.longValue();
        });
        Assertions.assertEquals(1L, latestCount, "no second latest may appear");
    }

    // --- predecessor-specific: a soft-deleted (doc,mac) match must NOT no-op --------------------------

    @Test
    public void predecessorSpecific_softDeletedBaseDoesNotNoop() throws Exception {
        ContentMacUtil.setMasterKeyForTest(MASTER_KEY);
        String userId = createUser();
        String docId = createDocument(userId);
        String v0 = createViaWrapper(userId, docId, null, "soft-deleted-content");
        // Soft-delete v0.
        inTx(() -> {
            new FileDao().delete(v0, userId);
            return null;
        });

        // Naming the soft-deleted v0 as the base with identical content must NOT collapse onto a dead row —
        // getActiveById returns null, so it is a typed previous-version mismatch, never a no-op success.
        Assertions.assertThrows(PreviousVersionMismatchException.class,
                () -> createViaWrapper(userId, docId, v0, "soft-deleted-content"),
                "a soft-deleted predecessor must never be a no-op target");
    }

    // --- absent previousFileId never dedups: two identical plain uploads both create -------------------

    @Test
    public void absentPreviousFileId_neverDedups() throws Exception {
        ContentMacUtil.setMasterKeyForTest(MASTER_KEY);
        String userId = createUser();
        String docId = createDocument(userId);
        String a = createViaWrapper(userId, docId, null, "identical-plain");
        String b = createViaWrapper(userId, docId, null, "identical-plain");
        Assertions.assertNotEquals(a, b, "two identical plain uploads (no previousFileId) must both create");
        Assertions.assertEquals(activeFile(a).getContentMac(), activeFile(b).getContentMac(),
                "identical content in the same document yields the same MAC");
        Assertions.assertEquals(2, latestFiles(userId, docId).size(), "both files exist");
    }

    // --- renamed-duplicate hint is informational only (creates + hint, no reparent) -------------------

    @Test
    public void renamedDuplicate_isInformationalHintOnly() throws Exception {
        ContentMacUtil.setMasterKeyForTest(MASTER_KEY);
        String userId = createUser();
        String docId = createDocument(userId);
        Path srcA = writeContent("renamed-identical");
        FileCreatedResult a = createRich(userId, docId, null, "renamed-identical", srcA);
        Assertions.assertNull(a.getDuplicateKind(), "the first upload has no twin yet");

        Path srcB = writeContent("renamed-identical");
        FileCreatedResult b = createRich(userId, docId, null, "renamed-identical", srcB);
        Assertions.assertTrue(b.isCreated(), "a renamed-identical upload still CREATES the file");
        Assertions.assertEquals(FileCreatedResult.DUPLICATE_CONTENT, b.getDuplicateKind(), "it carries a content hint");
        Assertions.assertEquals(a.getFileId(), b.getDuplicateOfId(), "the hint points at the existing identical file");
        Assertions.assertEquals(2, latestFiles(userId, docId).size(), "both files exist -> no server-side reparent/replace");
        Files.deleteIfExists(srcA);
        Files.deleteIfExists(srcB);
    }

    // --- same name, DIFFERENT content -> distinct, no hint --------------------------------------------

    @Test
    public void sameNameDifferentContent_isDistinctNoHint() throws Exception {
        ContentMacUtil.setMasterKeyForTest(MASTER_KEY);
        String userId = createUser();
        String docId = createDocument(userId);
        Path srcA = writeContent("content-one");
        FileCreatedResult a = createRich(userId, docId, null, "content-one", srcA);
        Path srcB = writeContent("content-two");
        FileCreatedResult b = createRich(userId, docId, null, "content-two", srcB);
        Assertions.assertTrue(b.isCreated());
        Assertions.assertNull(b.getDuplicateKind(), "different content must not raise a duplicate hint");
        Assertions.assertNotEquals(activeFile(a.getFileId()).getContentMac(), activeFile(b.getFileId()).getContentMac());
        Files.deleteIfExists(srcA);
        Files.deleteIfExists(srcB);
    }

    // --- cross-document non-linkage: same plaintext, two docs -> DIFFERENT MACs, no cross-doc hint ----

    @Test
    public void crossDocument_sameContentYieldsDifferentMacs() throws Exception {
        ContentMacUtil.setMasterKeyForTest(MASTER_KEY);
        String userId = createUser();
        String docA = createDocument(userId);
        String docB = createDocument(userId);
        Path srcA = writeContent("shared-plaintext");
        FileCreatedResult a = createRich(userId, docA, null, "shared-plaintext", srcA);
        Path srcB = writeContent("shared-plaintext");
        FileCreatedResult b = createRich(userId, docB, null, "shared-plaintext", srcB);
        Assertions.assertNotEquals(activeFile(a.getFileId()).getContentMac(), activeFile(b.getFileId()).getContentMac(),
                "the same plaintext in two documents must produce DIFFERENT MACs (per-document keying)");
        Assertions.assertNull(b.getDuplicateKind(), "a duplicate hint must never fire across documents");
        Files.deleteIfExists(srcA);
        Files.deleteIfExists(srcB);
    }

    // --- INVARIANT (concurrent branch): predecessor superseded BETWEEN the unlocked read and the locked
    //     re-check -> 409 DIRECTLY (never a spurious no-op success, never a fall-through into reserveStorage
    //     while holding the predecessor lock). This drives FileUtil.createFile's inner throw specifically. ---

    @Test
    public void invariant_supersededBetweenUnlockedAndLockedReadIsConflict() throws Exception {
        ContentMacUtil.setMasterKeyForTest(MASTER_KEY);
        String userId = createUser();
        String docId = createDocument(userId);
        String v0 = createViaWrapper(userId, docId, null, "race-content");
        String mac = activeFile(v0).getContentMac();
        Assertions.assertNotNull(mac);
        Assertions.assertTrue(activeFile(v0).isLatestVersion());

        CountDownLatch bHoldsLock = new CountDownLatch(1);
        CountDownLatch bMayCommit = new CountDownLatch(1);
        AtomicReference<Throwable> errorB = new AtomicReference<>();
        AtomicReference<Object> aOutcome = new AtomicReference<>();

        // B: demote v0 (flush -> hold v0's row lock, latestVersion=false uncommitted), then wait to commit.
        Thread b = new Thread(() -> runInHeldTx(errorB, em -> {
            new FileDao().demoteCurrentLatestVersion(v0, UUID.randomUUID().toString());
            em.flush();                 // push the demote UPDATE -> acquire v0's row write lock
            bHoldsLock.countDown();
            await(bMayCommit);
        }));

        // A: an identical-content new version onto v0. Its UNLOCKED read sees v0 still latest + matching MAC
        // (B uncommitted, READ COMMITTED) -> MATCH; its LOCKED re-read (SELECT FOR UPDATE) then blocks on B's
        // lock; once B commits the demote, A sees v0 non-latest at the locked read -> must raise a version
        // conflict DIRECTLY, never a spurious no-op success.
        Thread a = new Thread(() -> {
            try {
                await(bHoldsLock);
                Path src = writeContent("race-content");
                inTx(() -> {
                    raiseLockTimeout();
                    return FileUtil.createFile("f.txt", v0, src, Files.size(src), "eng", userId, docId, mac);
                });
                aOutcome.set("spurious-success");
            } catch (VersionConcurrencyException expected) {
                aOutcome.set(expected);
            } catch (Throwable t) {
                aOutcome.set(t);
            }
        });

        b.start();
        await(bHoldsLock);
        a.start();
        awaitBlockedSession();          // A's locked re-read is genuinely blocked on v0's row lock
        bMayCommit.countDown();
        joinAll(a, b);

        Assertions.assertNull(errorB.get(), "the demote failed: " + errorB.get());
        Assertions.assertTrue(aOutcome.get() instanceof VersionConcurrencyException,
                "a predecessor superseded between the unlocked read and the locked re-check must be a 409, "
                        + "never a spurious no-op success, got: " + aOutcome.get());
    }

    // --- concurrency harness (mirrors TestFileVersionContract) -----------------------------------------

    private interface TxBody {
        void run(EntityManager em) throws Exception;
    }

    private static void raiseLockTimeout() {
        String sql = EMF.isDriverPostgresql() ? "SET lock_timeout = 30000" : "SET LOCK_TIMEOUT 30000";
        ThreadLocalContext.get().getEntityManager().createNativeQuery(sql).executeUpdate();
    }

    /** Run {@code body} in its own transaction, letting it hold a row lock / await a latch before commit. */
    private void runInHeldTx(AtomicReference<Throwable> error, TxBody body) {
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext.get().setEntityManager(em);
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        try {
            raiseLockTimeout();
            body.run(em);
            tx.commit();
        } catch (Throwable t) {
            error.set(t);
            if (tx.isActive()) {
                tx.rollback();
            }
        } finally {
            if (em.isOpen()) {
                em.close();
            }
            ThreadLocalContext.cleanup();
        }
    }

    private boolean someSessionIsBlocked() {
        Supplier<Boolean> probe = () -> {
            try {
                return inTx(() -> {
                    String sql = EMF.isDriverPostgresql()
                            ? "select count(*) from pg_locks where not granted"
                            : "select count(*) from information_schema.sessions where blocker_id is not null";
                    Number n = (Number) ThreadLocalContext.get().getEntityManager().createNativeQuery(sql).getSingleResult();
                    return n.longValue() > 0;
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        return probe.get();
    }

    private void awaitBlockedSession() {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (someSessionIsBlocked()) {
                return;
            }
            try {
                Thread.sleep(15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("no database session blocked on a row lock within 20s");
    }

    private static void await(CountDownLatch latch) {
        try {
            Assertions.assertTrue(latch.await(30, TimeUnit.SECONDS), "the barrier must release in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static void joinAll(Thread... threads) throws InterruptedException {
        for (Thread t : threads) {
            t.join(35_000);
        }
        for (Thread t : threads) {
            Assertions.assertFalse(t.isAlive(), "every worker thread must finish");
        }
    }
}
