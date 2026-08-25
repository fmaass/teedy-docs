package com.sismics.docs.rest;

import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.AclDao;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.model.jpa.Acl;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.DocumentUtil;
import com.sismics.docs.core.util.TagCreationUtil;
import com.sismics.docs.rest.util.TagReductionUtil;
import com.sismics.docs.rest.util.TagReductionUtil.Reduction;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * The tag-reduction run decides what to remove and then removes it. Those are two steps, and over a
 * selection of up to 500 documents anything can happen between them: another request tags one of
 * them, untags one of them, moves one to the trash, or a WRITE grant is revoked. None of that was
 * part of the decision.
 *
 * <p>This drives the two steps APART, in-process, over real DAOs in one transaction — the shape
 * {@link TestTagMaintenanceRecheck} uses for the unused-tag sweep: the plan is computed, the state
 * is then mutated exactly as a concurrent request would mutate it, and {@link
 * TagReductionUtil#apply} is handed the now-stale plan. No production test hook is involved;
 * {@code apply} is public precisely BECAUSE it re-derives what it is given, which is the property
 * under test here.</p>
 *
 * @author fmaass
 */
public class TestTagReductionRecheck extends BaseTransactionalTest {
    /** Creates a tag owned by the user, with the base ACLs that make it theirs. */
    private String createTag(String name, String parentId, String userId) {
        Tag tag = new Tag();
        tag.setName(name);
        tag.setColor("#3399cc");
        tag.setUserId(userId);
        tag.setParentId(parentId);
        return TagCreationUtil.createTag(tag, userId);
    }

    /** Creates a document with its base ACLs, so the ACL checks under test have something to read. */
    private String createDocument(String title, String userId) {
        Document document = new Document();
        document.setUserId(userId);
        document.setLanguage("eng");
        document.setTitle(title);
        document.setCreateDate(new Date());
        return DocumentUtil.createDocument(document, userId).getId();
    }

    /** Every T_DOCUMENT_TAG row of a document, LIVE OR SOFT-DELETED — an insert cannot hide here. */
    private long tagLinkRows(String documentId) {
        Query q = ThreadLocalContext.get().getEntityManager().createNativeQuery(
                "select count(*) from T_DOCUMENT_TAG dt where dt.DOT_IDDOCUMENT_C = :documentId");
        q.setParameter("documentId", documentId);
        return ((Number) q.getSingleResult()).longValue();
    }

    /** UPDATE audit rows written for a document. */
    private long updateAuditRows(String documentId) {
        Query q = ThreadLocalContext.get().getEntityManager().createNativeQuery(
                "select count(*) from T_AUDIT_LOG l where l.LOG_IDENTITY_C = :documentId"
                        + " and l.LOG_TYPE_C = 'UPDATE'");
        q.setParameter("documentId", documentId);
        return ((Number) q.getSingleResult()).longValue();
    }

    private static List<String> removedIds(Reduction reduction, String documentId) {
        for (TagReductionUtil.DocumentReduction document : reduction.documents()) {
            if (document.documentId().equals(documentId)) {
                List<String> ids = new ArrayList<>();
                for (TagReductionUtil.RemovedTag tag : document.tags()) {
                    ids.add(tag.id());
                }
                return ids;
            }
        }
        return null;
    }

    private void grant(AclDao aclDao, String sourceId, PermType perm, String targetId, String actorId) {
        Acl acl = new Acl();
        acl.setSourceId(sourceId);
        acl.setPerm(perm);
        acl.setType(AclType.USER);
        acl.setTargetId(targetId);
        aclDao.create(acl, actorId);
    }

    private Set<String> liveTagIds(String documentId) {
        return new TagDao().findTagIdsByDocumentIds(List.of(documentId))
                .getOrDefault(documentId, Set.of());
    }

    /**
     * A tag put on the document AFTER the plan survives the run.
     *
     * <p>This is the data-loss case a full-set synchronization produces: the plan sees {Parent,
     * Child}, decides "Parent goes", and a caller that then synchronizes the document to the
     * remembered remainder deletes the brand-new third tag it never knew about. The reduction
     * removes links instead of writing a set, and re-reads the links first, so the new tag is
     * neither seen as redundant nor swept away.</p>
     */
    @Test
    public void testATagAddedAfterThePlanIsNotSweptAway() throws Exception {
        User user = createUser("tagreduce_add");
        String userId = user.getId();
        List<String> targetIdList = List.of(userId);

        String parentId = createTag("AddParent", null, userId);
        String childId = createTag("AddChild", parentId, userId);
        String otherId = createTag("AddedMidRun", null, userId);
        String documentId = createDocument("A document tagged mid-run", userId);
        new TagDao().updateTagList(documentId, Set.of(parentId, childId));

        // PHASE 1 — the plan: the parent is redundant because the child is on the document.
        Reduction planned = TagReductionUtil.plan(List.of(documentId), targetIdList);
        Assertions.assertEquals(List.of(parentId), removedIds(planned, documentId));

        // BETWEEN THE PHASES — what a concurrent request does: it adds another tag.
        new TagDao().updateTagList(documentId, Set.of(parentId, childId, otherId));

        // PHASE 2 — the run, handed the now-stale plan.
        Reduction applied = TagReductionUtil.apply(planned, targetIdList, userId);

        Assertions.assertEquals(List.of(parentId), removedIds(applied, documentId),
                "the parent still goes — that part of the plan is still true");
        Assertions.assertEquals(Set.of(childId, otherId), liveTagIds(documentId),
                "the tag added after the plan is still on the document");
    }

    /**
     * A plan whose reason has since disappeared removes NOTHING.
     *
     * <p>The sharpest test of the seam: between the phases the child — the only thing that made the
     * parent redundant — is taken off the document, so the parent is now the document's single tag.
     * A run that replayed its plan would strip the document bare; re-deriving the rule against the
     * current links answers "nothing to remove" instead.</p>
     */
    @Test
    public void testAPlanWhoseReasonDisappearedRemovesNothing() throws Exception {
        User user = createUser("tagreduce_stale");
        String userId = user.getId();
        List<String> targetIdList = List.of(userId);

        String parentId = createTag("StaleParent", null, userId);
        String childId = createTag("StaleChild", parentId, userId);
        String documentId = createDocument("A document untagged mid-run", userId);
        new TagDao().updateTagList(documentId, Set.of(parentId, childId));

        Reduction planned = TagReductionUtil.plan(List.of(documentId), targetIdList);
        Assertions.assertEquals(List.of(parentId), removedIds(planned, documentId));

        // BETWEEN THE PHASES — the child comes off, so the parent is no longer implied by anything.
        new TagDao().updateTagList(documentId, Set.of(parentId));

        Reduction applied = TagReductionUtil.apply(planned, targetIdList, userId);

        Assertions.assertNull(removedIds(applied, documentId), "the stale plan is not replayed");
        // The bucket: a document the run could touch but had nothing to remove from is UNCHANGED —
        // absent from both lists. "Skipped" is the screen's wording for a document the caller
        // cannot edit or that is gone, and neither is true here.
        Assertions.assertFalse(applied.skipped().contains(documentId),
                "a document with nothing left to remove is unchanged, not skipped");
        Assertions.assertEquals(Set.of(parentId), liveTagIds(documentId),
                "the document keeps the tag the current rule protects");
        Assertions.assertEquals(0L, updateAuditRows(documentId),
                "nothing changed, so nothing is written to the document's history");
    }

    /**
     * A document moved to the trash between the phases is skipped, and NOTHING is written to it.
     *
     * <p>Two properties, and the second is the one that matters most. The trash soft-deletes a
     * document's tag links with it, stamping them with the document's own delete date; a restore
     * revives exactly that set. A caller that synchronized such a document to a remembered tag set
     * would see no live links, conclude the tags are missing, and INSERT fresh ones — links the
     * restore then cannot reconcile ({@code TestTagReductionDao} pins that mechanism on the two DAO
     * primitives directly). The run only ever deletes links, so no insert is possible at all; the
     * document is reported as skipped because the trash soft-deletes its ACLs too, which the
     * per-document permission re-check sees, and the aliveness re-check states the same
     * requirement in its own right.</p>
     */
    @Test
    public void testADocumentTrashedAfterThePlanIsSkippedAndUntouched() throws Exception {
        User user = createUser("tagreduce_trash");
        String userId = user.getId();
        List<String> targetIdList = List.of(userId);

        String parentId = createTag("TrashParent", null, userId);
        String childId = createTag("TrashChild", parentId, userId);
        String documentId = createDocument("A document trashed mid-run", userId);
        new TagDao().updateTagList(documentId, Set.of(parentId, childId));

        Reduction planned = TagReductionUtil.plan(List.of(documentId), targetIdList);
        Assertions.assertEquals(List.of(parentId), removedIds(planned, documentId));
        long linkRowsBefore = tagLinkRows(documentId);

        // BETWEEN THE PHASES — the document goes to the trash, taking its links with it.
        new DocumentDao().delete(documentId, userId);

        Reduction applied = TagReductionUtil.apply(planned, targetIdList, userId);

        Assertions.assertTrue(applied.skipped().contains(documentId),
                "a trashed document is reported as skipped");
        Assertions.assertNull(removedIds(applied, documentId), "and not as changed");
        Assertions.assertEquals(linkRowsBefore, tagLinkRows(documentId),
                "no link row was inserted on the trashed document");
        Assertions.assertEquals(0L, updateAuditRows(documentId),
                "and no audit row was written for a document nothing was done to");
    }

    /**
     * WRITE revoked between the phases stops the removal on that document, and the rest of the run
     * still goes through.
     *
     * <p>The second document is the positive control: without it a re-check that simply refused
     * everything would pass this test.</p>
     */
    @Test
    public void testWriteRevokedAfterThePlanSkipsThatDocument() throws Exception {
        User owner = createUser("tagreduce_owner");
        User editor = createUser("tagreduce_editor");
        String ownerId = owner.getId();
        String editorId = editor.getId();
        List<String> editorTargets = List.of(editorId);

        String parentId = createTag("RevokeParent", null, ownerId);
        String childId = createTag("RevokeChild", parentId, ownerId);
        String revokedId = createDocument("A document whose grant is revoked", ownerId);
        String keptId = createDocument("A document whose grant is not", ownerId);
        TagDao tagDao = new TagDao();
        tagDao.updateTagList(revokedId, Set.of(parentId, childId));
        tagDao.updateTagList(keptId, Set.of(parentId, childId));

        // The tags are shared READ-ONLY on purpose: WRITE on a tag would be inherited by every
        // document carrying it (that is what AclDao#checkPermission resolves), and the grant this
        // test revokes has to be the DOCUMENT's own. Reading the tags is all the rule needs.
        AclDao aclDao = new AclDao();
        for (String tagId : List.of(parentId, childId)) {
            grant(aclDao, tagId, PermType.READ, editorId, ownerId);
        }
        for (String documentId : List.of(revokedId, keptId)) {
            grant(aclDao, documentId, PermType.READ, editorId, ownerId);
            grant(aclDao, documentId, PermType.WRITE, editorId, ownerId);
        }

        Reduction planned = TagReductionUtil.plan(List.of(revokedId, keptId), editorTargets);
        Assertions.assertEquals(List.of(parentId), removedIds(planned, revokedId));
        Assertions.assertEquals(List.of(parentId), removedIds(planned, keptId));

        // BETWEEN THE PHASES — the editor's WRITE on ONE document is revoked. The tags stay
        // readable, so what changes is exactly that document's own grant.
        aclDao.delete(revokedId, PermType.WRITE, editorId, ownerId, AclType.USER);

        Reduction applied = TagReductionUtil.apply(planned, editorTargets, editorId);

        Assertions.assertTrue(applied.skipped().contains(revokedId),
                "the document whose grant went is reported as skipped");
        Assertions.assertNull(removedIds(applied, revokedId), "and not as changed");
        Assertions.assertEquals(Set.of(parentId, childId), liveTagIds(revokedId),
                "its tags are untouched");
        Assertions.assertEquals(0L, updateAuditRows(revokedId), "and nothing entered its history");

        Assertions.assertEquals(List.of(parentId), removedIds(applied, keptId),
                "the rest of the run still happens");
        Assertions.assertEquals(Set.of(childId), liveTagIds(keptId));
    }
}
