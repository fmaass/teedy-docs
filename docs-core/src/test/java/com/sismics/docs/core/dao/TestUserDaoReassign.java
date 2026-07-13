package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Unit tests for the reassignment-on-delete DAO primitives ({@link UserDao#getActiveByIdForUpdate}
 * and {@link UserDao#reassignOwnedDocuments}).
 */
public class TestUserDaoReassign extends BaseTransactionalTest {

    private String createDocument(User user, String title) {
        DocumentDao documentDao = new DocumentDao();
        Document document = new Document();
        document.setUserId(user.getId());
        document.setLanguage("eng");
        document.setTitle(title);
        document.setCreateDate(new Date());
        return documentDao.create(document, user.getId());
    }

    /**
     * Fix #4 (target concurrent-delete TOCTOU) — the load-bearing recheck: getActiveByIdForUpdate
     * returns the user while active but null once soft-deleted. This is what makes the locked
     * re-validation in the reassign flow fail cleanly when a concurrent deletion of the target commits
     * between the initial active-check and the reassignment: the recheck sees the soft-deleted row and
     * returns null, so documents are never reassigned onto a dead owner.
     */
    @Test
    public void getActiveByIdForUpdateReturnsNullForSoftDeletedUser() throws Exception {
        UserDao userDao = new UserDao();
        User target = createUser("reassign_dao_target");
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.flush();

        // Active: the lock/recheck resolves the row.
        User locked = userDao.getActiveByIdForUpdate(target.getId());
        Assertions.assertNotNull(locked, "an active user must resolve under the lock");
        Assertions.assertEquals(target.getId(), locked.getId());

        // Soft-delete the target (as a concurrent deletion would), then the recheck must return null.
        User dbTarget = userDao.getById(target.getId());
        dbTarget.setDeleteDate(new Date());
        em.flush();

        Assertions.assertNull(userDao.getActiveByIdForUpdate(target.getId()),
                "a soft-deleted user must NOT resolve under the lock (closes the reassign-target TOCTOU)");
    }

    /**
     * Fix #3 (snapshot consistency): reassignOwnedDocuments reassigns EXACTLY the documents it returns —
     * every returned id is now owned by the target, and no other document of the departing user is left
     * behind. The returned set is the single source of truth used by the caller to spare files and
     * exclude deletion events, so it must equal the set actually reassigned.
     */
    @Test
    public void reassignOwnedDocumentsReturnsExactlyTheReassignedSet() throws Exception {
        UserDao userDao = new UserDao();
        User departing = createUser("reassign_dao_departing");
        User target = createUser("reassign_dao_newowner");
        DocumentDao documentDao = new DocumentDao();

        String docA = createDocument(departing, "A");
        String docB = createDocument(departing, "B");
        String docC = createDocument(departing, "C");
        ThreadLocalContext.get().getEntityManager().flush();

        List<String> returned = userDao.reassignOwnedDocuments(departing.getId(), target.getId());
        ThreadLocalContext.get().getEntityManager().flush();

        Set<String> returnedSet = new HashSet<>(returned);
        Assertions.assertEquals(Set.of(docA, docB, docC), returnedSet,
                "the returned set must be exactly the departing user's active documents");

        // Every returned document is now owned by the target...
        for (String docId : returnedSet) {
            Document doc = documentDao.getById(docId);
            Assertions.assertEquals(target.getId(), doc.getUserId(),
                    "each returned document must be reassigned to the target");
        }
        // ...and the departing user owns no live document afterwards (the whole set moved).
        Assertions.assertTrue(documentDao.findByUserId(departing.getId()).isEmpty(),
                "the departing user must own no live document after reassignment");
    }

    /**
     * A departing user who owns no documents yields an empty reassignment set (and performs no update).
     */
    @Test
    public void reassignOwnedDocumentsReturnsEmptyWhenNoDocuments() throws Exception {
        UserDao userDao = new UserDao();
        User departing = createUser("reassign_dao_nodoc_departing");
        User target = createUser("reassign_dao_nodoc_target");
        ThreadLocalContext.get().getEntityManager().flush();

        List<String> returned = userDao.reassignOwnedDocuments(departing.getId(), target.getId());
        Assertions.assertTrue(returned.isEmpty(), "no documents to reassign yields an empty set");
    }
}
