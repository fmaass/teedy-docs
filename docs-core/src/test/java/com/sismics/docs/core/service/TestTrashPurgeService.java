package com.sismics.docs.core.service;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;

/**
 * OPS-06: the scheduled trash purge must attribute the deletion/quota events to the
 * document's real owner, not "admin", so the owner's storage is released.
 */
public class TestTrashPurgeService extends BaseTransactionalTest {
    @Test
    public void purgeAttributesQuotaToOwner() throws Exception {
        // --- Setup (in the BaseTransactionalTest transaction) ---
        User owner = createUser("purgeOwner");

        DocumentDao documentDao = new DocumentDao();
        Document document = new Document();
        document.setUserId(owner.getId());
        document.setLanguage("eng");
        document.setTitle("Purge doc");
        document.setCreateDate(new Date());
        String documentId = documentDao.create(document, owner.getId());

        // A file attached to the document, and seed the owner's storage to its size.
        File file = createFile(owner, FILE_JPG_SIZE);
        file.setDocumentId(documentId);
        new FileDao().update(file);
        seedStorageCurrent(owner.getId(), FILE_JPG_SIZE);

        // Trash the document and backdate its deletion far beyond any plausible retention window.
        // Trashing (DocumentDao.delete) soft-deletes the document AND its files at the same instant;
        // backdate BOTH consistently so the setup matches production (where the purge quota reclaim
        // keys on file.deleteDate == document.deleteDate to identify cascade-trashed files).
        documentDao.delete(documentId, owner.getId());
        Date past = new Date(System.currentTimeMillis() - 3650L * 24 * 60 * 60 * 1000);
        ThreadLocalContext.get().getEntityManager().createNativeQuery(
                        "update T_DOCUMENT set DOC_DELETEDATE_D = :past where DOC_ID_C = :id")
                .setParameter("past", past)
                .setParameter("id", documentId)
                .executeUpdate();
        ThreadLocalContext.get().getEntityManager().createNativeQuery(
                        "update T_FILE set FIL_DELETEDATE_D = :past where FIL_IDDOC_C = :id")
                .setParameter("past", past)
                .setParameter("id", documentId)
                .executeUpdate();

        // Commit the setup and drop the EntityManager so the purge runs as a top-level
        // transaction (which commits and fires the async events synchronously in tests).
        EntityManager setupEm = ThreadLocalContext.get().getEntityManager();
        setupEm.getTransaction().commit();
        setupEm.close();
        ThreadLocalContext.cleanup();

        // --- Act (explicit retention so the test is deterministic, independent of the env) ---
        new TrashPurgeService().purgeExpiredTrash(30);

        // --- Assert: the OWNER's storage was released, and the document is gone ---
        long[] ownerStorage = new long[1];
        boolean[] docGone = new boolean[1];
        TransactionUtil.handle(() -> {
            ownerStorage[0] = new UserDao().getById(owner.getId()).getStorageCurrent();
            docGone[0] = new DocumentDao().getDeletedByIdSystem(documentId) == null;
        });
        Assertions.assertEquals(0L, ownerStorage[0], "purge must release the owner's storage, not admin's");
        Assertions.assertTrue(docGone[0], "the expired document must be permanently purged");

        // Re-establish a transactional context so BaseTransactionalTest.tearDown can roll back.
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext.get().setEntityManager(em);
        em.getTransaction().begin();
    }
}
