package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.model.jpa.Acl;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.Share;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.DocumentUtil;
import com.sismics.util.context.ThreadLocalContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.UUID;

/**
 * Functional unit tests for the Phase 2 credential-lifecycle DAO primitives: the conditional self
 * password-change ({@link UserDao#changeOwnPassword}), the startup admin-init bump inside
 * {@link UserDao#updateHashedPassword}, the #111 self-delete guard query
 * ({@link DocumentDao#hasActiveDocumentsSharedToOthers}), the owner-row grant lock
 * ({@link DocumentDao#lockOwnerForGrant}), and the phantom-insert guard in
 * {@link DocumentUtil#createDocument}. These run in a single rolled-back transaction (no cross-tx
 * interleaving); the deterministic race behaviour lives in {@code TestCredentialLifecycleRace}. Runs on
 * both H2 and PostgreSQL.
 */
public class TestCredentialLifecycleDao extends BaseTransactionalTest {

    // Short usernames (<= 36 chars): BaseTransactionalTest.createUser passes the username as the acting
    // audit-log user id, whose column is VARCHAR(36). Each test rolls back, so names never collide across
    // tests; within a test the callers pass distinct prefixes.
    private User user(String prefix) throws Exception {
        return createUser(prefix + "u");
    }

    private long epoch(String userId) {
        Number n = (Number) ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("select USE_CREDENTIALEPOCH_N from T_USER where USE_ID_C = :id")
                .setParameter("id", userId).getSingleResult();
        return n.longValue();
    }

    private String doc(User owner, String title) {
        Document document = new Document();
        document.setUserId(owner.getId());
        document.setLanguage("eng");
        document.setTitle(title);
        document.setCreateDate(new Date());
        return DocumentUtil.createDocument(document, owner.getId()).getId();
    }

    private void grantUserAcl(String sourceId, String targetId, String userId) {
        Acl acl = new Acl();
        acl.setSourceId(sourceId);
        acl.setPerm(PermType.READ);
        acl.setType(AclType.USER);
        acl.setTargetId(targetId);
        new AclDao().create(acl, userId);
    }

    private void trashDocument(String documentId) {
        ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_DOCUMENT set DOC_DELETEDATE_D = :now where DOC_ID_C = :id")
                .setParameter("now", new Date()).setParameter("id", documentId).executeUpdate();
    }

    // --- Conditional self password-change (B1, G3, G4) ---

    @Test
    public void changeOwnPasswordBumpsAndReturnsPostBumpEpoch() throws Exception {
        UserDao userDao = new UserDao();
        User u = user("clc_ok_");
        ThreadLocalContext.get().getEntityManager().flush();
        Assertions.assertEquals(0L, epoch(u.getId()));

        long newEpoch = userDao.changeOwnPassword(u.getId(), "NewPass123", 0L, u.getId());
        Assertions.assertEquals(1L, newEpoch, "the returned epoch is the authoritative post-bump value (G3)");
        Assertions.assertEquals(1L, epoch(u.getId()), "the row epoch advanced by one");
        Assertions.assertNotNull(userDao.authenticate(u.getUsername(), "NewPass123"), "the new password authenticates");
    }

    @Test
    public void changeOwnPasswordAbortsWhenEpochMovedByAConcurrentReset() throws Exception {
        UserDao userDao = new UserDao();
        User u = user("clc_abort_");
        userDao.changeOwnPassword(u.getId(), "OldPass123", 0L, u.getId()); // epoch 0 -> 1, known password
        ThreadLocalContext.get().getEntityManager().flush();
        Assertions.assertEquals(1L, epoch(u.getId()));

        // A recovery reset lands, advancing the epoch to 2 (the self-change verified snapshot was 1).
        userDao.bumpCredentialEpoch(u.getId()); // epoch 1 -> 2

        long result = userDao.changeOwnPassword(u.getId(), "SelfPass123", 1L, u.getId());
        Assertions.assertEquals(-1L, result, "a stale-epoch self-change is refused (G4)");
        Assertions.assertEquals(2L, epoch(u.getId()), "the epoch is NOT advanced again — the change was abandoned");
        Assertions.assertNull(userDao.authenticate(u.getUsername(), "SelfPass123"), "the abandoned password never took effect");
        Assertions.assertNotNull(userDao.authenticate(u.getUsername(), "OldPass123"), "the pre-reset password still stands");
    }

    @Test
    public void changeOwnPasswordAbortsOnAboveEpochInequality() throws Exception {
        UserDao userDao = new UserDao();
        User u = user("clc_above_");
        ThreadLocalContext.get().getEntityManager().flush();
        // Verified snapshot claims a HIGHER epoch than the row — a corrupt/stale value must also fail closed.
        long result = userDao.changeOwnPassword(u.getId(), "SelfPass123", 5L, u.getId());
        Assertions.assertEquals(-1L, result, "exact inequality (not only greater-than) aborts the change (G4)");
        Assertions.assertEquals(0L, epoch(u.getId()), "the epoch is untouched");
    }

    // --- Startup admin-password-init (inventory item, test k) ---

    @Test
    public void updateHashedPasswordBumpsEpoch() throws Exception {
        UserDao userDao = new UserDao();
        User u = user("clc_uhp_");
        ThreadLocalContext.get().getEntityManager().flush();
        Assertions.assertEquals(0L, epoch(u.getId()));
        u.setPassword("hashed-value-from-init");
        userDao.updateHashedPassword(u);
        Assertions.assertEquals(1L, epoch(u.getId()),
                "the startup admin-password-init path bumps the credential epoch (test k)");
    }

    // --- #111 self-delete guard query (G6, B3) ---

    @Test
    public void guardFalseWithOnlyOwnerAcls() throws Exception {
        User owner = user("clc_g0_");
        doc(owner, "solo");
        ThreadLocalContext.get().getEntityManager().flush();
        Assertions.assertFalse(new DocumentDao().hasActiveDocumentsSharedToOthers(owner.getId()),
                "the owner's own base grants do not count as shared-to-others");
    }

    @Test
    public void guardTrueWhenSharedToAnotherUser() throws Exception {
        User owner = user("clc_g1_");
        User other = user("clc_g1o_");
        String docId = doc(owner, "shared");
        grantUserAcl(docId, other.getId(), owner.getId());
        ThreadLocalContext.get().getEntityManager().flush();
        Assertions.assertTrue(new DocumentDao().hasActiveDocumentsSharedToOthers(owner.getId()));
    }

    @Test
    public void guardIgnoresRoutingAcls() throws Exception {
        User owner = user("clc_gr_");
        User other = user("clc_gro_");
        String docId = doc(owner, "routed");
        Acl acl = new Acl();
        acl.setSourceId(docId);
        acl.setPerm(PermType.READ);
        acl.setType(AclType.ROUTING);
        acl.setTargetId(other.getId());
        new AclDao().create(acl, owner.getId());
        ThreadLocalContext.get().getEntityManager().flush();
        Assertions.assertFalse(new DocumentDao().hasActiveDocumentsSharedToOthers(owner.getId()),
                "a ROUTING grant must not block self-delete (B3)");
    }

    @Test
    public void guardIgnoresTagSourcedAcls() throws Exception {
        User owner = user("clc_gtag_");
        User other = user("clc_gtago_");
        doc(owner, "withTagAcl");
        // A grant whose source is NOT the document (a tag id, here a bare UUID) must not be joined by the guard.
        grantUserAcl(UUID.randomUUID().toString(), other.getId(), owner.getId());
        ThreadLocalContext.get().getEntityManager().flush();
        Assertions.assertFalse(new DocumentDao().hasActiveDocumentsSharedToOthers(owner.getId()),
                "a tag-sourced (inherited) grant must not block self-delete (B3)");
    }

    @Test
    public void guardTrueForShareLink() throws Exception {
        User owner = user("clc_gs_");
        String docId = doc(owner, "sharelink");
        Share share = new Share();
        share.setName("link");
        new ShareDao().create(share);
        grantUserAcl(docId, share.getId(), owner.getId());
        ThreadLocalContext.get().getEntityManager().flush();
        Assertions.assertTrue(new DocumentDao().hasActiveDocumentsSharedToOthers(owner.getId()),
                "a share-link grant counts as shared-to-others (G6)");
    }

    @Test
    public void guardIgnoresTrashedDocuments() throws Exception {
        User owner = user("clc_gt_");
        User other = user("clc_gto_");
        String docId = doc(owner, "trashed");
        grantUserAcl(docId, other.getId(), owner.getId());
        trashDocument(docId);
        Assertions.assertFalse(new DocumentDao().hasActiveDocumentsSharedToOthers(owner.getId()),
                "a shared but trashed document must not block self-delete");
    }

    // --- Owner-row grant lock + source discrimination (G1) ---

    @Test
    public void lockOwnerForGrantReturnsCurrentOwnerForActiveDocument() throws Exception {
        User owner = user("clc_l1_");
        String docId = doc(owner, "active");
        ThreadLocalContext.get().getEntityManager().flush();
        Assertions.assertEquals(owner.getId(), new DocumentDao().lockOwnerForGrant(docId));
    }

    @Test
    public void lockOwnerForGrantAbortsForTrashedDocument() throws Exception {
        User owner = user("clc_l2_");
        String docId = doc(owner, "toTrash");
        trashDocument(docId);
        Assertions.assertNull(new DocumentDao().lockOwnerForGrant(docId),
                "a trashed document aborts the grant, fail closed");
    }

    @Test
    public void existsByIdDistinguishesDocumentsFromOtherSources() throws Exception {
        User owner = user("clc_ex_");
        String docId = doc(owner, "real");
        ThreadLocalContext.get().getEntityManager().flush();
        DocumentDao documentDao = new DocumentDao();
        Assertions.assertTrue(documentDao.existsById(docId), "a document row exists");
        Assertions.assertFalse(documentDao.existsById("not-a-document-" + UUID.randomUUID()),
                "a non-document source (e.g. a tag) is not a document row");
    }

    // --- Phantom-insert guard on document creation (B2, G1) ---

    @Test
    public void createDocumentAbortsForInactiveOwner() throws Exception {
        User owner = user("clc_cd_");
        ThreadLocalContext.get().getEntityManager().flush();
        ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_USER set USE_DELETEDATE_D = :now where USE_ID_C = :id")
                .setParameter("now", new Date()).setParameter("id", owner.getId()).executeUpdate();

        Document document = new Document();
        document.setUserId(owner.getId());
        document.setLanguage("eng");
        document.setTitle("orphan");
        document.setCreateDate(new Date());
        Assertions.assertThrows(IllegalStateException.class,
                () -> DocumentUtil.createDocument(document, owner.getId()),
                "creating a document for a soft-deleted owner must abort (phantom-insert guard)");
    }
}
