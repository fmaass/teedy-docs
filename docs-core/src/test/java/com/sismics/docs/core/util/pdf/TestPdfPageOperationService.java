package com.sismics.docs.core.util.pdf;

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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * The page-operation service's post-transform deadline gate (rc.8 cross-phase fix): the last deadline check
 * inside {@link PdfPageOperationUtil#transform} does not cover {@link FileUtil#createFile}, whose #119 dedup
 * MAC read walks the WHOLE output and whose encrypt pass walks it again. An operation already over budget
 * when it reaches version creation must return the page-operation timeout instead of running those passes
 * and committing a new version — verified here at the {@link PdfPageOperationService#createNewVersion} gate
 * by driving the deadline supplier directly. Runs on H2 and PostgreSQL.
 */
public class TestPdfPageOperationService extends BaseTest {

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
        String username = "pageop_" + UUID.randomUUID().toString().substring(0, 12);
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
            document.setTitle("page-op doc");
            document.setCreateDate(new Date());
            return new DocumentDao().create(document, userId);
        });
    }

    /** Create a real base version (encrypted blob on disk) and return its file id. */
    private String createBaseFile(String userId, String documentId) throws Exception {
        Path src = Files.createTempFile("pageop-base", ".txt");
        Files.write(src, ("base " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        try {
            return inTx(() -> FileUtil.createFile("base.pdf", null, src, Files.size(src), "eng", userId, documentId));
        } finally {
            Files.deleteIfExists(src);
        }
    }

    /** A stand-in for the transformed output the service would hand to createFile. */
    private Path writeOutput(String content) throws Exception {
        Path output = Files.createTempFile("pageop-out", ".txt");
        Files.write(output, content.getBytes(StandardCharsets.UTF_8));
        return output;
    }

    private long storageCurrent(String userId) throws Exception {
        return inTx(() -> {
            Long current = new UserDao().getById(userId).getStorageCurrent();
            return current == null ? 0L : current;
        });
    }

    private List<File> latestFiles(String userId, String docId) throws Exception {
        return inTx(() -> new FileDao().getByDocumentId(userId, docId));
    }

    // --- over-budget at the createFile step: timeout, no new version, no quota reserved ----------------

    @Test
    public void pageOp_overBudgetBeforeCreateFile_timesOutWithoutNewVersion() throws Exception {
        String userId = createUser();
        String docId = createDocument(userId);
        String v0 = createBaseFile(userId, docId);
        File base = inTx(() -> new FileDao().getActiveById(v0));

        long quotaBefore = storageCurrent(userId);
        Path output = writeOutput("over-budget output");
        long outputSize = Files.size(output);
        try {
            // The base is still the current latest, so createFile's CAS WOULD succeed here — the ONLY reason
            // no version is created is the deadline gate that fires before createFile.
            Assertions.assertThrows(PdfPageOperationTimeoutException.class, () -> inTx(() ->
                    PdfPageOperationService.createNewVersion(base, output, outputSize, "eng", userId, () -> true)),
                    "an operation already over budget at the createFile step must return the page-op timeout");

            List<File> latest = latestFiles(userId, docId);
            Assertions.assertEquals(1, latest.size(), "no new version may be created by an over-budget op");
            Assertions.assertEquals(v0, latest.get(0).getId(), "the base version must remain the current latest");
            Assertions.assertEquals(quotaBefore, storageCurrent(userId),
                    "an over-budget op must not reserve any quota");
        } finally {
            Files.deleteIfExists(output);
        }
    }

    // --- in-budget: the gate is a no-op and a new version is created (happy path intact) ---------------

    @Test
    public void pageOp_inBudget_createsNewVersion() throws Exception {
        String userId = createUser();
        String docId = createDocument(userId);
        String v0 = createBaseFile(userId, docId);
        File base = inTx(() -> new FileDao().getActiveById(v0));

        Path output = writeOutput("in-budget output");
        long outputSize = Files.size(output);
        try {
            String v1 = inTx(() ->
                    PdfPageOperationService.createNewVersion(base, output, outputSize, "eng", userId, () -> false));
            Assertions.assertNotNull(v1, "an in-budget op must create a new version");
            Assertions.assertNotEquals(v0, v1, "the created version must be distinct from the base");

            List<File> latest = latestFiles(userId, docId);
            Assertions.assertEquals(1, latest.size());
            Assertions.assertEquals(v1, latest.get(0).getId(), "the new version must be promoted to latest");
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
