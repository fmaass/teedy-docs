package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.model.jpa.Acl;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.DocumentTag;
import com.sismics.docs.core.model.jpa.Group;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * #122 user-deletion tag-WRITE lifecycle. A tag must never be left with zero WRITE holders while a SURVIVING
 * document still uses it. Two paths:
 *
 * <ul>
 *   <li>SELF-delete has no reassignment target — {@link AclDao#hasSoleWriteTagLinkedToForeignDocument}
 *       is the guard the resource consults to REFUSE the deletion. Tested positive and negative.</li>
 *   <li>ADMIN reassign-delete reassigns the departing user's surviving-document-linked tags to the target
 *       ({@link UserDao#reassignOwnedDocuments}) and grants the target WRITE, so once
 *       {@link UserDao#delete(String, String, java.util.Set)} soft-deletes the departing user's ACLs the tag
 *       still has a WRITE holder and is owned by a live user. Tested end-to-end at the DAO layer.</li>
 * </ul>
 *
 * Single-transaction DAO tests (H2 + PostgreSQL) using the shared transactional base.
 */
public class TestUserDeleteTagWriteLifecycle extends BaseTransactionalTest {

    private String createTag(String ownerId) {
        Tag tag = new Tag();
        tag.setName("t" + UUID.randomUUID().toString().substring(0, 8));
        tag.setColor("#ff0000");
        tag.setUserId(ownerId);
        return new TagDao().create(tag, ownerId);
    }

    private String createDocument(String ownerId) {
        Document document = new Document();
        document.setUserId(ownerId);
        document.setLanguage("eng");
        document.setTitle("doc");
        document.setCreateDate(new Date());
        return new DocumentDao().create(document, ownerId);
    }

    private void linkTag(String documentId, String tagId) {
        DocumentTag dt = new DocumentTag();
        dt.setId(UUID.randomUUID().toString());
        dt.setDocumentId(documentId);
        dt.setTagId(tagId);
        ThreadLocalContext.get().getEntityManager().persist(dt);
    }

    private void grant(String sourceId, PermType perm, String targetId) {
        Acl acl = new Acl();
        acl.setSourceId(sourceId);
        acl.setPerm(perm);
        acl.setType(AclType.USER);
        acl.setTargetId(targetId);
        new AclDao().create(acl, targetId);
    }

    private boolean guard(String userId) {
        return new AclDao().hasSoleWriteTagLinkedToForeignDocument(userId);
    }

    /** Soft-deletes a user by stamping its deletion date directly (test setup for orphan-ACL cases). */
    private void softDeleteUser(String userId) {
        ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_USER set USE_DELETEDATE_D = :now where USE_ID_C = :id")
                .setParameter("now", new Date())
                .setParameter("id", userId)
                .executeUpdate();
    }

    private void flush() {
        ThreadLocalContext.get().getEntityManager().flush();
    }

    // ---- SELF-delete guard ----------------------------------------------------------------------------

    @Test
    public void soleWriteHolderOfForeignLinkedTag_guardRefuses() throws Exception {
        User departing = createUser("tw_sole_departing");
        User other = createUser("tw_sole_other");
        String tagId = createTag(departing.getId());
        grant(tagId, PermType.READ, departing.getId());
        grant(tagId, PermType.WRITE, departing.getId());
        String foreignDoc = createDocument(other.getId());
        linkTag(foreignDoc, tagId);
        flush();

        Assertions.assertTrue(guard(departing.getId()),
                "departing is the sole WRITE holder of a tag another user's document uses — deletion must be refused");
    }

    @Test
    public void tagLinkedOnlyToOwnDocument_guardAllows() throws Exception {
        User departing = createUser("tw_own_departing");
        String tagId = createTag(departing.getId());
        grant(tagId, PermType.WRITE, departing.getId());
        String ownDoc = createDocument(departing.getId());
        linkTag(ownDoc, tagId);
        flush();

        Assertions.assertFalse(guard(departing.getId()),
                "a tag used only on the departing user's OWN (to-be-trashed) document strands nothing");
    }

    @Test
    public void secondWriteHolderIsSoftDeleted_guardRefuses() throws Exception {
        // #135: the tag has TWO WRITE ACLs, but the co-holder's account is soft-deleted — an inert
        // orphan ACL. The departing user is therefore the SOLE ACTIVE WRITE holder, and deleting them
        // would strand the foreign-linked tag at zero active holders. The guard must refuse.
        User departing = createUser("tw_orphan_departing");
        User orphan = createUser("tw_orphan_gone");
        User other = createUser("tw_orphan_other");
        String tagId = createTag(departing.getId());
        grant(tagId, PermType.WRITE, departing.getId());
        grant(tagId, PermType.WRITE, orphan.getId());
        softDeleteUser(orphan.getId());
        String foreignDoc = createDocument(other.getId());
        linkTag(foreignDoc, tagId);
        flush();

        Assertions.assertTrue(guard(departing.getId()),
                "the only surviving co-holder is a soft-deleted (orphan) target — departing is the sole ACTIVE"
                        + " WRITE holder, so deletion must be refused; without the active-user filter the orphan"
                        + " inflates the count to 2 and the guard wrongly allows the deletion");
    }

    @Test
    public void secondWriteHolderIsLiveGroup_guardAllows() throws Exception {
        // #135: a LIVE GROUP co-holder (a USER-type ACL whose target is a T_GROUP id) survives the user's
        // deletion, so the foreign-linked tag is NOT stranded. The orphan filter drops only soft-deleted
        // USER targets and must not zero the group — otherwise the guard would wrongly refuse a safe delete.
        User departing = createUser("tw_grp_departing");
        User other = createUser("tw_grp_other");
        String tagId = createTag(departing.getId());
        grant(tagId, PermType.WRITE, departing.getId());
        Group group = new Group();
        group.setName("g" + UUID.randomUUID().toString().substring(0, 8));
        String groupId = new GroupDao().create(group, departing.getId());
        grant(tagId, PermType.WRITE, groupId);
        String foreignDoc = createDocument(other.getId());
        linkTag(foreignDoc, tagId);
        flush();

        Assertions.assertFalse(guard(departing.getId()),
                "a live group is a surviving WRITE holder — deletion is safe; the orphan filter must not"
                        + " zero the group target");
    }

    @Test
    public void tagWithSecondWriteHolder_guardAllows() throws Exception {
        User departing = createUser("tw_second_departing");
        User coeditor = createUser("tw_second_coeditor");
        User other = createUser("tw_second_other");
        String tagId = createTag(departing.getId());
        grant(tagId, PermType.WRITE, departing.getId());
        grant(tagId, PermType.WRITE, coeditor.getId());
        String foreignDoc = createDocument(other.getId());
        linkTag(foreignDoc, tagId);
        flush();

        Assertions.assertFalse(guard(departing.getId()),
                "a second WRITE holder survives the deletion — the tag is not stranded");
    }

    @Test
    public void tagLinkedToNoSurvivingDocument_guardAllows() throws Exception {
        User departing = createUser("tw_nodoc_departing");
        String tagId = createTag(departing.getId());
        grant(tagId, PermType.WRITE, departing.getId());
        flush();

        Assertions.assertFalse(guard(departing.getId()),
                "a tag no surviving document uses can be purged without loss — no refusal");
    }

    // ---- ADMIN reassign-delete ------------------------------------------------------------------------

    @Test
    public void adminReassignPreservesForeignLinkedTagWriteHolder() throws Exception {
        UserDao userDao = new UserDao();
        User departing = createUser("tw_reassign_departing");
        User target = createUser("tw_reassign_target");
        User other = createUser("tw_reassign_other");

        // Departing solely owns+WRITEs a tag applied to ANOTHER user's (surviving) document.
        String tagId = createTag(departing.getId());
        grant(tagId, PermType.READ, departing.getId());
        grant(tagId, PermType.WRITE, departing.getId());
        String foreignDoc = createDocument(other.getId());
        linkTag(foreignDoc, tagId);
        flush();

        Assertions.assertTrue(guard(departing.getId()), "precondition: the tag would otherwise be stranded");

        // Admin reassign-delete: reassign the departing user's tags/documents to the target, then delete.
        List<String> reassigned = userDao.reassignOwnedDocuments(departing.getId(), target.getId());
        flush();
        userDao.delete(departing.getUsername(), "admin", new HashSet<>(reassigned));
        flush();

        // The tag now belongs to a live user (the target) and still has a WRITE holder — never stranded.
        Tag movedTag = new TagDao().getById(tagId);
        Assertions.assertNotNull(movedTag, "the tag still exists");
        Assertions.assertNull(movedTag.getDeleteDate(), "the tag was not deleted");
        Assertions.assertEquals(target.getId(), movedTag.getUserId(), "the tag ownership moved to the target");
        Assertions.assertTrue(new AclDao().hasDirectUserAcl(tagId, PermType.WRITE, target.getId()),
                "the target holds a direct WRITE grant on the reassigned tag");
        Assertions.assertEquals(1L, new AclDao().countAcls(tagId, PermType.WRITE, AclType.USER),
                "exactly one WRITE holder remains — the foreign document's tag is never stranded at zero");
        // The foreign document's tag link is intact.
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Number links = (Number) em.createQuery("select count(dt.id) from DocumentTag dt"
                        + " where dt.tagId = :tagId and dt.documentId = :docId and dt.deleteDate is null")
                .setParameter("tagId", tagId).setParameter("docId", foreignDoc).getSingleResult();
        Assertions.assertEquals(1L, links.longValue(), "the other owner's document keeps its tag link");
    }
}
