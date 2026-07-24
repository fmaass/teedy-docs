package com.sismics.docs.rest;

import com.google.common.io.Resources;
import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.dao.ConfigDao;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.service.DocumentDuplicationService;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tests the duplicate-document endpoint ({@code POST /document/{id}/duplicate}, #184): a READ-holder
 * copies a document into a fresh copy they own — content and the eight Dublin-Core fields, custom
 * metadata, readable tags (with inherited visibility), files (re-encrypted under the copier's key with
 * rotation and cover preserved) — while storage is charged exactly once and a failure leaves nothing
 * partial (no rows, no orphan blobs).
 */
public class TestDocumentDuplication extends BaseJerseyTest {

    private static final String COOKIE = TokenBasedSecurityFilter.COOKIE_NAME;

    /**
     * A READ-only requester duplicates a rich document: the copy carries the eight metadata fields, the
     * description, language, a reset create date, both files in order (with rotation and the remapped
     * cover), no relations — charged to the requester exactly once, with the source left byte-identical.
     */
    @Test
    public void testDuplicateCopiesFieldsFilesCoverRotation() throws Exception {
        clientUtil.createUser("dup_owner");
        String ownerToken = clientUtil.login("dup_owner");
        clientUtil.createUser("dup_reader");
        String readerToken = clientUtil.login("dup_reader");

        // A throwaway document to relate to — the relation must NOT be copied.
        String relatedId = clientUtil.createDocument(ownerToken);

        long pastCreateDate = 1000000000000L; // 2001, safely before "now"
        String sourceId = target().path("/document").request()
                .cookie(COOKIE, ownerToken)
                .put(Entity.form(new Form()
                        .param("title", "Original")
                        .param("description", "The description")
                        .param("language", "eng")
                        .param("subject", "Subj")
                        .param("identifier", "Ident")
                        .param("publisher", "Pub")
                        .param("format", "Fmt")
                        .param("source", "Src")
                        .param("type", "Typ")
                        .param("coverage", "Cov")
                        .param("rights", "Rgt")
                        .param("relations", relatedId)
                        .param("create_date", Long.toString(pastCreateDate))), JsonObject.class)
                .getString("id");

        String txtId = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, ownerToken, sourceId);
        String pngId = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, ownerToken, sourceId);
        awaitAsyncQuiescence("source file processing must settle");

        // Exercise both rotation and an explicit cover, so each is checked on the copy.
        target().path("/file/" + pngId + "/rotation").request()
                .cookie(COOKIE, ownerToken)
                .post(Entity.form(new Form().param("rotation", "90")), JsonObject.class);
        target().path("/document/" + sourceId + "/cover").request()
                .cookie(COOKIE, ownerToken)
                .post(Entity.form(new Form().param("file", pngId)), JsonObject.class);
        awaitAsyncQuiescence("rotation and cover must settle");

        shareRead(sourceId, ownerToken, "dup_reader");

        long expectedSize = sumFileSizes(sourceId, readerToken);
        String txtChecksumBefore = sha256(DirectoryUtil.getStorageDirectory().resolve(txtId));
        String pngChecksumBefore = sha256(DirectoryUtil.getStorageDirectory().resolve(pngId));
        long readerStorageBefore = storageCurrent(readerToken);

        long duplicateStart = System.currentTimeMillis();
        String copyId = target().path("/document/" + sourceId + "/duplicate").request()
                .cookie(COOKIE, readerToken)
                .post(null, JsonObject.class)
                .getString("id");
        Assertions.assertNotNull(copyId);
        Assertions.assertNotEquals(sourceId, copyId);
        awaitAsyncQuiescence("copy file processing must settle");

        JsonObject copy = getDocument(copyId, readerToken);
        Assertions.assertEquals("Original (copy)", copy.getString("title"));
        Assertions.assertEquals("The description", copy.getString("description"));
        Assertions.assertEquals("eng", copy.getString("language"));
        Assertions.assertEquals("Subj", copy.getString("subject"));
        Assertions.assertEquals("Ident", copy.getString("identifier"));
        Assertions.assertEquals("Pub", copy.getString("publisher"));
        Assertions.assertEquals("Fmt", copy.getString("format"));
        Assertions.assertEquals("Src", copy.getString("source"));
        Assertions.assertEquals("Typ", copy.getString("type"));
        Assertions.assertEquals("Cov", copy.getString("coverage"));
        Assertions.assertEquals("Rgt", copy.getString("rights"));
        Assertions.assertEquals("dup_reader", copy.getString("creator"));
        Assertions.assertEquals(0, copy.getJsonArray("relations").size(), "relations are not copied");

        // create_date reset to now, not carried from the 2001 source date.
        long copyCreateDate = copy.getJsonNumber("create_date").longValue();
        Assertions.assertTrue(copyCreateDate >= duplicateStart, "create_date is reset to now");
        Assertions.assertTrue(copyCreateDate > pastCreateDate, "create_date is not the source date");

        JsonArray copyFiles = listFiles(copyId, readerToken);
        Assertions.assertEquals(2, copyFiles.size());
        JsonObject copyTxt = copyFiles.getJsonObject(0);
        JsonObject copyPng = copyFiles.getJsonObject(1);
        Assertions.assertEquals("document.txt", copyTxt.getString("name"));
        Assertions.assertEquals("Einstein-Roosevelt-letter.png", copyPng.getString("name"));
        Assertions.assertNotEquals(txtId, copyTxt.getString("id"), "the copy has fresh file ids");
        Assertions.assertNotEquals(pngId, copyPng.getString("id"), "the copy has fresh file ids");
        Assertions.assertEquals(0, copyTxt.getJsonNumber("rotation").intValue());
        Assertions.assertEquals(90, copyPng.getJsonNumber("rotation").intValue(), "rotation is preserved");

        // Copied files decrypt to byte-identical originals — the source-key-read, requester-key-write round-trip.
        Assertions.assertArrayEquals(fixtureBytes(FILE_DOCUMENT_TXT),
                downloadFile(copyTxt.getString("id"), readerToken), "copied txt content round-trips");
        Assertions.assertArrayEquals(fixtureBytes(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG),
                downloadFile(copyPng.getString("id"), readerToken), "copied png content round-trips");

        Assertions.assertEquals(copyPng.getString("id"), copy.getString("file_id_cover"), "cover remapped to the new file");
        Assertions.assertEquals(copyPng.getString("id"), copy.getString("file_id"), "served cover follows the remapped cover");

        // The delta equals the summed source size, not double — the precheck never reserves.
        long readerStorageAfter = storageCurrent(readerToken);
        Assertions.assertEquals(expectedSize, readerStorageAfter - readerStorageBefore, "storage charged exactly once");

        JsonObject sourceAfter = getDocument(sourceId, ownerToken);
        Assertions.assertEquals("Original", sourceAfter.getString("title"));
        Assertions.assertEquals(pngId, sourceAfter.getString("file_id_cover"), "the source cover is unchanged");
        Assertions.assertEquals(2, listFiles(sourceId, ownerToken).size());
        Assertions.assertEquals(txtChecksumBefore, sha256(DirectoryUtil.getStorageDirectory().resolve(txtId)), "source blob unchanged");
        Assertions.assertEquals(pngChecksumBefore, sha256(DirectoryUtil.getStorageDirectory().resolve(pngId)), "source blob unchanged");
    }

    /**
     * Only tags the requester can read are copied; an owner-private tag is not. A copied tag carries its
     * inherited tag-ACL visibility to the copy: a third principal who can read the tag can read the copy
     * even though the copy has no direct ACL for them.
     */
    @Test
    public void testDuplicateCopiesReadableTagsWithInheritedVisibility() throws Exception {
        clientUtil.createUser("dup_tag_owner");
        String ownerToken = clientUtil.login("dup_tag_owner");
        clientUtil.createUser("dup_tag_reader");
        String readerToken = clientUtil.login("dup_tag_reader");
        clientUtil.createUser("dup_tag_viewer");
        String viewerToken = clientUtil.login("dup_tag_viewer");

        // A shared tag both the reader and the viewer can read, and an owner-private tag neither can.
        String sharedTagId = createTag(ownerToken, "dup-shared");
        String privateTagId = createTag(ownerToken, "dup-private");
        shareRead(sharedTagId, ownerToken, "dup_tag_reader");
        shareRead(sharedTagId, ownerToken, "dup_tag_viewer");

        String sourceId = target().path("/document").request()
                .cookie(COOKIE, ownerToken)
                .put(Entity.form(new Form()
                        .param("title", "Tagged")
                        .param("language", "eng")
                        .param("tags", sharedTagId)
                        .param("tags", privateTagId)), JsonObject.class)
                .getString("id");
        shareRead(sourceId, ownerToken, "dup_tag_reader");

        String copyId = target().path("/document/" + sourceId + "/duplicate").request()
                .cookie(COOKIE, readerToken)
                .post(null, JsonObject.class)
                .getString("id");

        JsonArray copyTags = getDocument(copyId, readerToken).getJsonArray("tags");
        Assertions.assertEquals(1, copyTags.size(), "only the readable tag is copied");
        Assertions.assertEquals(sharedTagId, copyTags.getJsonObject(0).getString("id"));

        // The viewer holds NO direct ACL on the copy — only READ on the copied tag — yet can read the copy.
        Response viewerGet = target().path("/document/" + copyId).request().cookie(COOKIE, viewerToken).get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(viewerGet.getStatus()),
                "the copied tag confers inherited read visibility of the copy");
    }

    /**
     * Custom metadata is copied for active definitions with a non-null value, through the validated write
     * path.
     */
    @Test
    public void testDuplicateCopiesActiveNonNullCustomMetadata() throws Exception {
        clientUtil.createUser("dup_meta_owner");
        String ownerToken = clientUtil.login("dup_meta_owner");
        clientUtil.createUser("dup_meta_reader");
        String readerToken = clientUtil.login("dup_meta_reader");
        String adminToken = adminToken();

        String metadataId = target().path("/metadata").request()
                .cookie(COOKIE, adminToken)
                .put(Entity.form(new Form().param("name", "dup-meta").param("type", "STRING")), JsonObject.class)
                .getString("id");
        try {
            String sourceId = target().path("/document").request()
                    .cookie(COOKIE, ownerToken)
                    .put(Entity.form(new Form()
                            .param("title", "WithMeta")
                            .param("language", "eng")
                            .param("metadata_id", metadataId)
                            .param("metadata_value", "the value")), JsonObject.class)
                    .getString("id");
            shareRead(sourceId, ownerToken, "dup_meta_reader");

            String copyId = target().path("/document/" + sourceId + "/duplicate").request()
                    .cookie(COOKIE, readerToken)
                    .post(null, JsonObject.class)
                    .getString("id");

            JsonObject meta = findMetadata(getDocument(copyId, readerToken).getJsonArray("metadata"), metadataId);
            Assertions.assertNotNull(meta, "the custom metadata definition is present on the copy");
            Assertions.assertEquals("the value", meta.getString("value"), "the custom metadata value is copied");
        } finally {
            target().path("/metadata/" + metadataId).request().cookie(COOKIE, adminToken).delete(JsonObject.class);
        }
    }

    /**
     * A requester with no ACL on the source, and a guest session (even one holding READ), are both
     * rejected with 403.
     */
    @Test
    public void testDuplicateForbiddenForNonReaderAndGuest() throws Exception {
        clientUtil.createUser("dup_forbid_owner");
        String ownerToken = clientUtil.login("dup_forbid_owner");
        clientUtil.createUser("dup_forbid_stranger");
        String strangerToken = clientUtil.login("dup_forbid_stranger");

        String sourceId = clientUtil.createDocument(ownerToken);
        clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, ownerToken, sourceId);
        awaitAsyncQuiescence("source processing must settle");

        Response stranger = target().path("/document/" + sourceId + "/duplicate").request()
                .cookie(COOKIE, strangerToken)
                .post(null);
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(stranger.getStatus()));

        // A guest holding READ is still rejected by the guest guard, not the ACL check.
        target().path("/app/guest_login").request()
                .cookie(COOKIE, adminToken())
                .post(Entity.form(new Form().param("enabled", "true")), JsonObject.class);
        shareRead(sourceId, ownerToken, "guest");
        String guestToken = clientUtil.login("guest", "", false);
        Response guest = target().path("/document/" + sourceId + "/duplicate").request()
                .cookie(COOKIE, guestToken)
                .post(null);
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(guest.getStatus()));
    }

    /**
     * A long source title is truncated so {@code <base> (copy)} fits the 100-char limit exactly.
     */
    @Test
    public void testDuplicateTitleTruncatedToFit() throws Exception {
        clientUtil.createUser("dup_title");
        String token = clientUtil.login("dup_title");

        String longTitle = "T".repeat(100);
        String sourceId = target().path("/document").request()
                .cookie(COOKIE, token)
                .put(Entity.form(new Form().param("title", longTitle).param("language", "eng")), JsonObject.class)
                .getString("id");

        String copyId = target().path("/document/" + sourceId + "/duplicate").request()
                .cookie(COOKIE, token)
                .post(null, JsonObject.class)
                .getString("id");

        String copyTitle = getDocument(copyId, token).getString("title");
        Assertions.assertEquals(100, copyTitle.length(), "the truncated title is exactly the 100-char limit");
        Assertions.assertEquals("T".repeat(93) + " (copy)", copyTitle);
    }

    /**
     * A duplicate whose summed size exceeds the requester's quota is rejected upfront with no partial
     * artifact: no copy row, no blob, no storage charged.
     */
    @Test
    public void testDuplicateOverQuotaLeavesNoArtifacts() throws Exception {
        clientUtil.createUser("dup_quota_owner");
        String ownerToken = clientUtil.login("dup_quota_owner");
        clientUtil.createUser("dup_quota_reader", 100000); // below the 292641-byte PNG
        String readerToken = clientUtil.login("dup_quota_reader");

        String sourceId = target().path("/document").request()
                .cookie(COOKIE, ownerToken)
                .put(Entity.form(new Form().param("title", "TooBig").param("language", "eng")), JsonObject.class)
                .getString("id");
        clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, ownerToken, sourceId);
        awaitAsyncQuiescence("source processing must settle");
        shareRead(sourceId, ownerToken, "dup_quota_reader");

        Set<String> blobsBefore = storageBlobNames();
        Response response = target().path("/document/" + sourceId + "/duplicate").request()
                .cookie(COOKIE, readerToken)
                .post(null);
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("QuotaReached", response.readEntity(JsonObject.class).getString("type"));

        Assertions.assertEquals(0, countDocumentsByTitle("TooBig (copy)"), "no copy document is created");
        Assertions.assertEquals(0L, storageCurrent(readerToken), "no storage is charged to the requester");
        Assertions.assertTrue(storageBlobNames().equals(blobsBefore), "no orphan blob is left behind");
    }

    /**
     * A failure PART-WAY through the file batch (a source blob gone after the first file is copied) rolls
     * the whole duplicate back and compensates the already-written blob: no copy row, no orphan blob, no
     * storage charged, and the surviving source blob untouched.
     */
    @Test
    public void testDuplicateMidBatchFailureCompensatesBlobs() throws Exception {
        clientUtil.createUser("dup_comp_owner");
        String ownerToken = clientUtil.login("dup_comp_owner");
        clientUtil.createUser("dup_comp_reader");
        String readerToken = clientUtil.login("dup_comp_reader");

        String sourceId = target().path("/document").request()
                .cookie(COOKIE, ownerToken)
                .put(Entity.form(new Form().param("title", "MidBatch").param("language", "eng")), JsonObject.class)
                .getString("id");
        String txtId = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, ownerToken, sourceId);
        String pngId = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, ownerToken, sourceId);
        awaitAsyncQuiescence("source processing must settle");
        shareRead(sourceId, ownerToken, "dup_comp_reader");

        // Delete only the SECOND source blob: its row and stored size survive, so the precheck passes and
        // the failure lands mid-batch — after the first file's blob is already written.
        Path pngBlob = DirectoryUtil.getStorageDirectory().resolve(pngId);
        String txtChecksumBefore = sha256(DirectoryUtil.getStorageDirectory().resolve(txtId));
        Files.delete(pngBlob);

        Set<String> blobsBefore = storageBlobNames();
        long readerStorageBefore = storageCurrent(readerToken);

        Response response = target().path("/document/" + sourceId + "/duplicate").request()
                .cookie(COOKIE, readerToken)
                .post(null);
        // An unreadable source blob surfaces as the 500 FileError; the request still rolls back and compensates.
        Assertions.assertEquals(Status.INTERNAL_SERVER_ERROR, Status.fromStatusCode(response.getStatus()));

        Assertions.assertEquals(0, countDocumentsByTitle("MidBatch (copy)"), "no copy document survives the rollback");
        Assertions.assertEquals(readerStorageBefore, storageCurrent(readerToken), "no storage is charged");
        Assertions.assertEquals(blobsBefore, storageBlobNames(), "the already-written blob is compensated, none orphan");
        Assertions.assertEquals(txtChecksumBefore, sha256(DirectoryUtil.getStorageDirectory().resolve(txtId)), "the surviving source blob is untouched");
    }

    /**
     * Proves duplication acquires GLOBAL_QUOTA_LOCK (and the requester's user row) BEFORE the source
     * document row — the canonical GLOBAL -> USER -> DOCUMENT order that keeps it deadlock-free against a
     * concurrent upload. A seam pauses the duplicate after the quota locks and before the document lock;
     * at that point the source document row is still lockable (not held) while GLOBAL is not (held).
     */
    @Test
    public void testDuplicateAcquiresGlobalBeforeSourceDocumentLock() throws Exception {
        clientUtil.createUser("dup_lock_owner");
        String ownerToken = clientUtil.login("dup_lock_owner");
        clientUtil.createUser("dup_lock_reader");
        String readerToken = clientUtil.login("dup_lock_reader");

        String sourceId = clientUtil.createDocument(ownerToken);
        clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, ownerToken, sourceId);
        awaitAsyncQuiescence("source processing must settle");
        shareRead(sourceId, ownerToken, "dup_lock_reader");

        CountDownLatch atPreDocumentLock = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DocumentDuplicationService.setBeforeSourceDocumentLockHookForTest(() -> {
            atPreDocumentLock.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        ExecutorService duplicateThread = Executors.newSingleThreadExecutor();
        ExecutorService probeThread = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> duplicate = duplicateThread.submit(() ->
                    target().path("/document/" + sourceId + "/duplicate").request()
                            .cookie(COOKIE, readerToken).post(null).getStatus());

            Assertions.assertTrue(atPreDocumentLock.await(20, TimeUnit.SECONDS),
                    "the duplicate must reach the pre-document-lock point");

            Future<Boolean> documentProbe = probeThread.submit(() -> {
                AtomicBoolean locked = new AtomicBoolean(false);
                TransactionUtil.handle(() -> locked.set(new DocumentDao().getActiveByIdForUpdate(sourceId) != null));
                return locked.get();
            });
            Assertions.assertEquals(Boolean.TRUE, documentProbe.get(15, TimeUnit.SECONDS),
                    "the source document row is still lockable, so duplication has not locked it before GLOBAL/USER");

            Future<Boolean> globalProbe = probeThread.submit(() -> {
                AtomicBoolean locked = new AtomicBoolean(false);
                TransactionUtil.handle(() -> locked.set(new ConfigDao().lockForUpdate(ConfigType.GLOBAL_QUOTA_LOCK)));
                return locked.get();
            });
            try {
                Boolean acquired = globalProbe.get(3, TimeUnit.SECONDS);
                Assertions.fail("GLOBAL must be held by the paused duplicate, but a probe acquired it: " + acquired);
            } catch (TimeoutException stillBlocked) {
                // Never returned within the window: GLOBAL is held (a database that waits indefinitely).
            } catch (ExecutionException failed) {
                // The probe's own acquisition must be refused by a lock timeout — not an unrelated error,
                // which would otherwise satisfy the assertion.
                Assertions.assertTrue(isPessimisticLockException(failed),
                        "the GLOBAL probe must fail with a pessimistic-lock/lock-timeout, not: " + failed.getCause());
            }

            release.countDown();
            Assertions.assertEquals(200, duplicate.get(20, TimeUnit.SECONDS).intValue(),
                    "the duplicate completes once released");
        } finally {
            DocumentDuplicationService.setBeforeSourceDocumentLockHookForTest(null);
            release.countDown();
            probeThread.shutdownNow();
            duplicateThread.shutdownNow();
        }
    }

    // --- helpers ---

    private static boolean isPessimisticLockException(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof jakarta.persistence.PessimisticLockException
                    || cause instanceof jakarta.persistence.LockTimeoutException) {
                return true;
            }
            String name = cause.getClass().getName();
            if (name.contains("PessimisticLock") || name.contains("LockTimeout")
                    || name.contains("LockAcquisition")) {
                return true;
            }
        }
        return false;
    }

    private byte[] downloadFile(String fileId, String token) {
        return target().path("/file/" + fileId + "/data").request().cookie(COOKIE, token).get(byte[].class);
    }

    private byte[] fixtureBytes(String resourceName) throws IOException {
        return Resources.toByteArray(Resources.getResource(resourceName));
    }

    private JsonObject getDocument(String documentId, String token) {
        return target().path("/document/" + documentId).request().cookie(COOKIE, token).get(JsonObject.class);
    }

    private JsonArray listFiles(String documentId, String token) {
        return target().path("/file/list").queryParam("id", documentId).request()
                .cookie(COOKIE, token).get(JsonObject.class).getJsonArray("files");
    }

    private long sumFileSizes(String documentId, String token) {
        JsonArray files = listFiles(documentId, token);
        long total = 0L;
        for (int i = 0; i < files.size(); i++) {
            total += files.getJsonObject(i).getJsonNumber("size").longValue();
        }
        return total;
    }

    private long storageCurrent(String token) {
        return target().path("/user").request().cookie(COOKIE, token)
                .get(JsonObject.class).getJsonNumber("storage_current").longValue();
    }

    private String createTag(String token, String name) {
        return target().path("/tag").request().cookie(COOKIE, token)
                .put(Entity.form(new Form().param("name", name).param("color", "#ff0000")), JsonObject.class)
                .getString("id");
    }

    private void shareRead(String sourceId, String ownerToken, String targetUsername) {
        target().path("/acl").request()
                .cookie(COOKIE, ownerToken)
                .put(Entity.form(new Form()
                        .param("source", sourceId)
                        .param("perm", "READ")
                        .param("target", targetUsername)
                        .param("type", "USER")), JsonObject.class);
    }

    private JsonObject findMetadata(JsonArray metadata, String metadataId) {
        for (int i = 0; i < metadata.size(); i++) {
            JsonObject meta = metadata.getJsonObject(i);
            if (metadataId.equals(meta.getString("id")) && meta.containsKey("value")) {
                return meta;
            }
        }
        return null;
    }

    private Set<String> storageBlobNames() throws Exception {
        try (Stream<Path> stream = Files.list(DirectoryUtil.getStorageDirectory())) {
            return stream.map(p -> p.getFileName().toString()).collect(Collectors.toCollection(HashSet::new));
        }
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(Files.readAllBytes(path));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private long countDocumentsByTitle(String title) {
        AtomicLong count = new AtomicLong();
        TransactionUtil.handle(() -> {
            EntityManager em = ThreadLocalContext.get().getEntityManager();
            Number n = (Number) em.createNativeQuery(
                    "select count(*) from T_DOCUMENT where DOC_TITLE_C = :t and DOC_DELETEDATE_D is null")
                    .setParameter("t", title).getSingleResult();
            count.set(n.longValue());
        });
        return count.get();
    }
}
