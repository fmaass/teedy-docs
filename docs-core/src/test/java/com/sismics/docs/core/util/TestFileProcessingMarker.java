package com.sismics.docs.core.util;

import com.sismics.BaseTest;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.dao.dto.DocumentDto;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.action.ProcessFilesAction;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.mime.MimeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Date;
import java.util.UUID;

/**
 * Drives the file-processing-marker producers through a REAL request transaction that ROLLS BACK, and
 * asserts the shared "mark + register rollback compensation" contract: on rollback the in-memory
 * processing marker is released (so a later rotation / reprocess is not blocked until a JVM restart) and
 * the decrypted plaintext temp file the discarded async event owned is deleted (so decrypted content is
 * not leaked on disk) — while the encrypted stored file and any concurrent operation's marker are left
 * untouched. Ownership is exactly-once: the local-compensation handle and the registered rollback
 * callback share one guard, and a file the producer never marked owns nothing to compensate.
 *
 * <p>These are the failure paths the embedded H2 database cannot inject on its own. They are driven
 * through {@link TransactionUtil#handle} on its owner path (no transactional context installed), so the
 * real commit / rollback / completion wiring runs.</p>
 */
public class TestFileProcessingMarker extends BaseTest {

    @BeforeEach
    public void setUp() {
        ThreadLocalContext.cleanup();
    }

    @AfterEach
    public void tearDown() {
        ThreadLocalContext.cleanup();
    }

    /** A short unique id that fits the 36-char id / audit-log columns. */
    private static String shortId(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    private User createUser(String userName) throws Exception {
        UserDao userDao = new UserDao();
        User user = new User();
        user.setUsername(userName);
        user.setPassword("12345678");
        user.setEmail(userName + "@docs.com");
        user.setRoleId("admin");
        user.setStorageQuota(1_000_000L);
        userDao.create(user, userName);
        return user;
    }

    private String createDocument(User user) {
        Document document = new Document();
        document.setUserId(user.getId());
        document.setLanguage("eng");
        document.setTitle("marker test doc");
        document.setCreateDate(new Date());
        return new DocumentDao().create(document, user.getId());
    }

    /** Encrypt the sample JPG into the storage dir under {@code fileId}, mirroring a stored upload. */
    private void storeEncrypted(String fileId, String privateKey) throws Exception {
        try (InputStream in = getSystemResourceAsStream(FILE_JPG)) {
            Cipher cipher = EncryptionUtil.getEncryptionCipher(privateKey);
            Files.copy(new CipherInputStream(in, cipher),
                    DirectoryUtil.getStorageDirectory().resolve(fileId), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path writePlaintextTemp() throws Exception {
        Path temp = Files.createTempFile("plaintext-marker-", ".tmp");
        try (InputStream in = getSystemResourceAsStream(FILE_JPG)) {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        return temp;
    }

    // The shared helper: a rollback after marking releases the marker AND deletes the plaintext temp.
    @Test
    public void helperRollbackReleasesMarkerAndDeletesPlaintextTemp() throws Exception {
        String fileId = "marker-helper-" + UUID.randomUUID();
        Path temp = writePlaintextTemp();
        Assertions.assertTrue(Files.exists(temp));

        Assertions.assertThrows(IllegalStateException.class, () ->
                TransactionUtil.handle(() -> {
                    FileUtil.markProcessingWithRollbackCleanup(fileId, temp);
                    Assertions.assertTrue(FileUtil.isProcessingFile(fileId), "the marker is set while marking");
                    throw new IllegalStateException("work failed after marking");
                }));

        Assertions.assertFalse(FileUtil.isProcessingFile(fileId),
                "the processing marker must be released on rollback (not stranded until a JVM restart)");
        Assertions.assertFalse(Files.exists(temp),
                "the decrypted plaintext temp file must be deleted on rollback");
    }

    // Mutual exclusivity: on COMMIT the rollback compensation must NOT fire (the listener owns the
    // release on the commit path). If it fired here it would release the marker prematurely / double.
    @Test
    public void helperCommitDoesNotRunRollbackCompensation() throws Exception {
        String fileId = "marker-commit-" + UUID.randomUUID();
        Path temp = writePlaintextTemp();
        try {
            TransactionUtil.handle(() -> FileUtil.markProcessingWithRollbackCleanup(fileId, temp));

            Assertions.assertTrue(FileUtil.isProcessingFile(fileId),
                    "on the commit path the rollback compensation must NOT release the marker "
                            + "(the async listener owns the release)");
            Assertions.assertTrue(Files.exists(temp),
                    "on the commit path the rollback compensation must NOT delete the temp "
                            + "(the async listener owns it)");
        } finally {
            FileUtil.endProcessingFile(fileId);
            Files.deleteIfExists(temp);
        }
    }

    // EXACTLY-ONCE OWNERSHIP: the one-shot token shared by the local-compensation handle and the
    // registered rollback callback guarantees the release runs at most once. Proven by an OBSERVABLE
    // effect: after local compensation releases the marker, a DIFFERENT operation re-acquires it; the
    // stale rollback callback (fired by the outer rollback) must NOT clear that re-acquired marker.
    // Without the token the second release would clear a live foreign marker (a set-state-only assertion
    // could not detect this — Set.remove is idempotent).
    @Test
    public void oneShotTokenReleasesExactlyOnceAcrossLocalThenRollback() throws Exception {
        String fileId = shortId("mk1-");
        Path temp = writePlaintextTemp();

        Assertions.assertThrows(IllegalStateException.class, () ->
                TransactionUtil.handle(() -> {
                    Runnable localCompensate = FileUtil.markProcessingWithRollbackCleanup(fileId, temp);
                    // The batch swallow path: producer failed after marking, so compensate locally now.
                    localCompensate.run();
                    Assertions.assertFalse(FileUtil.isProcessingFile(fileId),
                            "local compensation released the marker");
                    Assertions.assertFalse(Files.exists(temp), "local compensation deleted the temp");
                    // A concurrent operation re-acquires the same marker before the outer transaction ends.
                    FileUtil.startProcessingFile(fileId);
                    throw new IllegalStateException("outer rollback after local compensation");
                }));

        Assertions.assertTrue(FileUtil.isProcessingFile(fileId),
                "the stale rollback callback must NOT clear the re-acquired marker (one-shot token consumed)");
        FileUtil.endProcessingFile(fileId);
    }

    // The REAL createFile producer: a work failure after createFile has marked + queued rolls back, and
    // must leave NO stranded marker and NO lingering plaintext temp.
    @Test
    public void createFileRollbackReleasesMarkerAndDeletesTemp() throws Exception {
        String userName = shortId("mkc-");
        Path temp = writePlaintextTemp();
        long fileSize = Files.size(temp);
        String[] createdFileId = {null};

        Assertions.assertThrows(IllegalStateException.class, () ->
                TransactionUtil.handle(() -> {
                    try {
                        User user = createUser(userName);
                        String fileId = FileUtil.createFile("apollo.jpg", null, temp, fileSize, null,
                                user.getId(), null);
                        createdFileId[0] = fileId;
                        Assertions.assertTrue(FileUtil.isProcessingFile(fileId),
                                "createFile marks the file as processing");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    // Force the request transaction to roll back after the producer marked + queued.
                    throw new IllegalStateException("request failed after createFile");
                }));

        Assertions.assertNotNull(createdFileId[0], "createFile ran and marked the file");
        Assertions.assertFalse(FileUtil.isProcessingFile(createdFileId[0]),
                "createFile's marker must be released when the request rolls back");
        Assertions.assertFalse(Files.exists(temp),
                "createFile's plaintext temp must be deleted when the request rolls back");
    }

    // Stands in for the FileResource attach + manual-reprocess producers, whose temp is produced by
    // EncryptionUtil.decryptFile (a DISTINCT file when the key is non-null). Driving those two REST
    // methods to a forced rollback is impractical — a successful attach/process commits, and there is no
    // seam to inject a mid-request failure after the mark through the real HTTP path. They hand the exact
    // object exercised here (a real decryptFile temp) to the same helper, so a rollback must delete that
    // decrypted temp while leaving the encrypted stored file intact.
    @Test
    public void decryptedTempDeletedOnRollbackStoredFileSurvives() throws Exception {
        String userName = shortId("mkd-");
        String fileId = shortId("mkd-file-");
        Path[] decrypted = {null};

        Assertions.assertThrows(IllegalStateException.class, () ->
                TransactionUtil.handle(() -> {
                    try {
                        User user = createUser(userName);
                        storeEncrypted(fileId, user.getPrivateKey());
                        Path stored = DirectoryUtil.getStorageDirectory().resolve(fileId);
                        Path temp = EncryptionUtil.decryptFile(stored, user.getPrivateKey());
                        decrypted[0] = temp;
                        Assertions.assertNotEquals(stored, temp, "a real key yields a distinct temp file");
                        Assertions.assertTrue(Files.exists(temp), "the decrypted temp exists");
                        FileUtil.markProcessingWithRollbackCleanup(fileId, temp);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    throw new IllegalStateException("rollback after marking");
                }));

        Assertions.assertFalse(FileUtil.isProcessingFile(fileId), "marker released on rollback");
        Assertions.assertFalse(Files.exists(decrypted[0]), "the decrypted temp is deleted on rollback");
        Assertions.assertTrue(Files.exists(DirectoryUtil.getStorageDirectory().resolve(fileId)),
                "the encrypted stored file must NOT be deleted");
    }

    // NULL-KEY ALIAS GUARD: decryptFile returns the STORED file path itself when the key is null. The
    // rollback compensation must recognise that alias and NOT delete the real stored content.
    @Test
    public void nullKeyStoredAliasIsNotDeletedOnRollback() throws Exception {
        String fileId = shortId("mka-file-");
        Path stored = DirectoryUtil.getStorageDirectory().resolve(fileId);
        Files.write(stored, "STORED_ENCRYPTED_CONTENT".getBytes());
        try {
            Path alias = EncryptionUtil.decryptFile(stored, null);
            Assertions.assertEquals(stored, alias, "a null key returns the stored path itself (the alias)");

            Assertions.assertThrows(IllegalStateException.class, () ->
                    TransactionUtil.handle(() -> {
                        FileUtil.markProcessingWithRollbackCleanup(fileId, alias);
                        throw new IllegalStateException("rollback with a stored-file alias");
                    }));

            Assertions.assertFalse(FileUtil.isProcessingFile(fileId), "marker released on rollback");
            Assertions.assertTrue(Files.exists(stored),
                    "the stored-file alias must NOT be deleted on rollback");
        } finally {
            Files.deleteIfExists(stored);
        }
    }

    // The batch producer routed through the helper: a request rollback after the batch marked + queued
    // releases every marker it set (no per-file stranding).
    @Test
    public void batchRollbackReleasesMarkers() throws Exception {
        String userName = shortId("mkb-");
        String[] fileIdHolder = {null};

        Assertions.assertThrows(IllegalStateException.class, () ->
                TransactionUtil.handle(() -> {
                    try {
                        User user = createUser(userName);
                        String documentId = createDocument(user);

                        FileDao fileDao = new FileDao();
                        File file = new File();
                        file.setUserId(user.getId());
                        file.setDocumentId(documentId);
                        file.setVersion(0);
                        file.setLatestVersion(true);
                        file.setMimeType(MimeType.IMAGE_JPEG);
                        file.setSize(FILE_JPG_SIZE);
                        // FileDao.create assigns its own UUID and returns it — store the blob under THAT.
                        String fileId = fileDao.create(file, user.getId());
                        fileIdHolder[0] = fileId;
                        storeEncrypted(fileId, user.getPrivateKey());

                        DocumentDto documentDto = new DocumentDto();
                        documentDto.setId(documentId);
                        documentDto.setLanguage("eng");
                        new ProcessFilesAction().execute(documentDto, null);

                        Assertions.assertTrue(FileUtil.isProcessingFile(fileId),
                                "the batch marked the file as processing");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    throw new IllegalStateException("request failed after batch marked + queued");
                }));

        Assertions.assertFalse(FileUtil.isProcessingFile(fileIdHolder[0]),
                "the batch's marker must be released when the request rolls back");
    }

    // PRE-MARK FAILURE: a batch file whose decrypt fails BEFORE marking owns no marker, so the batch must
    // NOT compensate. If a concurrent operation holds a live marker for the same file id, a wrongful
    // compensation would clear it. Here the stored file is absent, so decryptFile throws before the mark;
    // the foreign marker set beforehand must survive.
    @Test
    public void batchPreMarkFailureDoesNotClearForeignMarker() throws Exception {
        String userName = shortId("mkp-");
        String[] fileIdHolder = {null};

        try {
            TransactionUtil.handle(() -> {
                try {
                    User user = createUser(userName);
                    String documentId = createDocument(user);

                    // Create the file ROW but DO NOT write its stored bytes: decryptFile will throw
                    // (NoSuchFile) before the batch reaches the mark.
                    FileDao fileDao = new FileDao();
                    File file = new File();
                    file.setUserId(user.getId());
                    file.setDocumentId(documentId);
                    file.setVersion(0);
                    file.setLatestVersion(true);
                    file.setMimeType(MimeType.IMAGE_JPEG);
                    file.setSize(0L);
                    String fileId = fileDao.create(file, user.getId());
                    fileIdHolder[0] = fileId;

                    // A concurrent operation is already processing this file id (its live marker must be
                    // preserved — a wrongful pre-mark compensation would clear it).
                    FileUtil.startProcessingFile(fileId);

                    DocumentDto documentDto = new DocumentDto();
                    documentDto.setId(documentId);
                    documentDto.setLanguage("eng");
                    // Swallows the per-file decrypt failure internally; the outer transaction commits.
                    new ProcessFilesAction().execute(documentDto, null);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            Assertions.assertTrue(FileUtil.isProcessingFile(fileIdHolder[0]),
                    "a pre-mark decrypt failure must NOT clear a concurrent operation's live marker");
        } finally {
            if (fileIdHolder[0] != null) {
                FileUtil.endProcessingFile(fileIdHolder[0]);
            }
        }
    }
}
