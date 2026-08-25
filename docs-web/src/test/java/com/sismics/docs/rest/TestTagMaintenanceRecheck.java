package com.sismics.docs.rest;

import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.TagCreationUtil;
import com.sismics.docs.rest.util.TagMaintenanceUtil;
import com.sismics.docs.rest.util.TagMaintenanceUtil.Sweep;
import com.sismics.docs.rest.util.TagMaintenanceUtil.TagStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The unused-tag sweep decides what to delete and then deletes it. Those are two steps, and
 * anything that happens between them — a document being tagged, a WRITE grant being revoked — was
 * not part of the decision. Without a re-check the sweep would remove a tag that had just become
 * used, taking a brand-new assignment with it silently; the project's triage rule treats that as
 * data loss rather than a self-recovering error, so it is fixed rather than accepted.
 *
 * <p>This drives the two steps APART, in-process, over real DAOs in one transaction: the verdict is
 * computed, the state is then mutated exactly as a concurrent request would mutate it, and the
 * delete phase is handed the now-stale verdict. No production test hook is involved —
 * {@link TagMaintenanceUtil#deleteAll} is public precisely BECAUSE it re-validates what it is
 * given, which is the property under test here.</p>
 *
 * @author fmaass
 */
public class TestTagMaintenanceRecheck extends BaseTransactionalTest {
    /** Creates a tag owned by the user, with the base ACLs that make it theirs to delete. */
    private String createTag(String name, String userId) {
        Tag tag = new Tag();
        tag.setName(name);
        tag.setColor("#3399cc");
        tag.setUserId(userId);
        return TagCreationUtil.createTag(tag, userId);
    }

    private String createDocument(String title, String userId) {
        Document document = new Document();
        document.setUserId(userId);
        document.setLanguage("eng");
        document.setTitle(title);
        document.setCreateDate(new Date());
        return new com.sismics.docs.core.dao.DocumentDao().create(document, userId);
    }

    private static List<TagStatus> deletableOf(List<TagStatus> statusList) {
        List<TagStatus> deletable = new ArrayList<>();
        for (TagStatus status : statusList) {
            if (status.deletable()) {
                deletable.add(status);
            }
        }
        return deletable;
    }

    private static Set<String> idsOf(List<TagStatus> statusList) {
        Set<String> ids = new HashSet<>();
        for (TagStatus status : statusList) {
            ids.add(status.id());
        }
        return ids;
    }

    /**
     * A tag that gains a document AFTER the verdict is kept, and the rest of the sweep still runs.
     * The second tag is the positive control: without it a re-check that simply refused everything
     * would pass this test.
     */
    @Test
    public void testATagThatBecomesUsedAfterTheVerdictIsKept() throws Exception {
        User user = createUser("tagrecheck1");
        String userId = user.getId();
        List<String> targetIdList = List.of(userId);

        String racedTagId = createTag("RacedTag", userId);
        String untouchedTagId = createTag("UntouchedTag", userId);
        String documentId = createDocument("A document tagged mid-sweep", userId);

        // PHASE 1 — the verdict. Both tags are unused, so both are cleared for deletion.
        List<TagStatus> validated = deletableOf(TagMaintenanceUtil.status(targetIdList));
        Assertions.assertTrue(idsOf(validated).contains(racedTagId), "the raced tag was cleared for deletion");
        Assertions.assertTrue(idsOf(validated).contains(untouchedTagId), "so was the control tag");

        // BETWEEN THE PHASES — what a concurrent request does: it tags a document with one of them.
        new TagDao().updateTagList(documentId, Set.of(racedTagId));

        // PHASE 2 — the delete, handed the now-stale verdict.
        Sweep sweep = TagMaintenanceUtil.deleteAll(validated, targetIdList, userId);

        Assertions.assertTrue(idsOf(sweep.blocked()).contains(racedTagId),
                "the tag that became used is reported as kept, not silently skipped");
        Assertions.assertFalse(idsOf(sweep.deleted()).contains(racedTagId),
                "and it is NOT reported as deleted");

        TagDao tagDao = new TagDao();
        Assertions.assertNull(tagDao.getById(racedTagId).getDeleteDate(),
                "the tag itself survives, so the fresh assignment survives with it");
        Assertions.assertTrue(tagDao.hasDocumentReference(racedTagId),
                "and the assignment made between the phases is still there");

        // The control: an untouched tag still goes, so the sweep did its job.
        Assertions.assertTrue(idsOf(sweep.deleted()).contains(untouchedTagId),
                "a tag nothing happened to is still deleted");
        Assertions.assertNotNull(tagDao.getById(untouchedTagId).getDeleteDate(),
                "and it really is gone");
    }

    /**
     * The single-subtree delete is advertised as all-or-nothing, and its pre-delete re-check can
     * fire in the MIDDLE of the branch: the order is leaf-up, so a reference landing on the ROOT is
     * seen only after the descendants have already been soft-deleted. Reporting that as a clean
     * DELETED would be a partial deletion dressed as success — the caller is told the branch went
     * while part of it is still there, and nothing tells them to look.
     *
     * <p>SCOPE, stated because it is easy to over-read: the reference here is staged BEFORE the
     * call, so it is {@code deleteSubtree}'s own validation that refuses and nothing is ever
     * written. The guard against a reference landing DURING the call — the one that turns a
     * non-empty blocked list into a refusal instead of a DELETED — has no seam a single-threaded
     * test can reach through this method, and is covered directly by
     * {@code TestTagMaintenanceUtil#testASubtreeWithAKeptMemberIsRefusedRatherThanReportedDeleted}.
     * What this test pins is that the branch is refused and NOTHING in it is deleted.
     */
    @Test
    public void testASubtreeDeleteRefusesRatherThanPartiallySucceeding() throws Exception {
        User user = createUser("tagrecheck3");
        String userId = user.getId();
        List<String> targetIdList = List.of(userId);

        String rootId = createTag("AtomicRoot", userId);
        Tag child = new Tag();
        child.setName("AtomicChild");
        child.setColor("#3399cc");
        child.setUserId(userId);
        child.setParentId(rootId);
        String childId = TagCreationUtil.createTag(child, userId);
        String documentId = createDocument("A document tagged with the root mid-delete", userId);

        // The branch is unused, so the endpoint's own validation clears it.
        Assertions.assertEquals(Set.of(rootId, childId),
                idsOf(deletableOf(TagMaintenanceUtil.status(targetIdList))),
                "the whole branch is cleared for deletion before the interleaving");

        // The interleaving lands on the ROOT — the last tag the leaf-up loop reaches, so the child
        // is already gone by the time the re-check refuses. This is the ordering that produces the
        // partial deletion; a reference on the child would be caught before any write.
        new TagDao().updateTagList(documentId, Set.of(rootId));

        TagMaintenanceUtil.DeleteResult result =
                TagMaintenanceUtil.deleteSubtree(rootId, targetIdList, userId);

        Assertions.assertEquals(TagMaintenanceUtil.DeleteOutcome.IN_USE, result.outcome(),
                "a branch that could not go whole is refused, not reported as deleted — and the "
                        + "refusal keeps the root cause's own reason, which a document reference "
                        + "may safely carry (the caller can write that tag already)");
        Assertions.assertTrue(result.deleted().isEmpty(),
                "and the report claims nothing went — the rollback is what makes that true on disk");

        // The tag the reference landed on is standing regardless of the rollback: it was never
        // deleted in the first place, so the fresh assignment is safe.
        TagDao tagDao = new TagDao();
        Assertions.assertNull(tagDao.getById(rootId).getDeleteDate(), "the raced root was never deleted");
        Assertions.assertTrue(tagDao.hasDocumentReference(rootId), "and its new assignment is intact");
    }

    /**
     * Keeping a tag keeps its ANCESTORS in the same run. {@link TagDao#delete} detaches the
     * children of the tag it removes, so deleting a parent while keeping its child would re-parent
     * that child to the root of the tree — a structural change nobody asked for, and one the user
     * would have to undo by hand.
     */
    @Test
    public void testKeepingATagKeepsTheAncestorsThatWouldOrphanIt() throws Exception {
        User user = createUser("tagrecheck2");
        String userId = user.getId();
        List<String> targetIdList = List.of(userId);

        String parentId = createTag("KeepParent", userId);
        Tag child = new Tag();
        child.setName("KeepChild");
        child.setColor("#3399cc");
        child.setUserId(userId);
        child.setParentId(parentId);
        String childId = TagCreationUtil.createTag(child, userId);
        String documentId = createDocument("A document tagged with the child mid-sweep", userId);

        List<TagStatus> validated = deletableOf(TagMaintenanceUtil.status(targetIdList));
        Assertions.assertEquals(Set.of(parentId, childId), idsOf(validated),
                "the whole unused branch was cleared for deletion");

        new TagDao().updateTagList(documentId, Set.of(childId));

        Sweep sweep = TagMaintenanceUtil.deleteAll(validated, targetIdList, userId);

        Assertions.assertEquals(Set.of(parentId, childId), idsOf(sweep.blocked()),
                "the child is kept and the parent with it");
        Assertions.assertTrue(sweep.deleted().isEmpty(), "nothing was deleted");

        TagDao tagDao = new TagDao();
        Assertions.assertNull(tagDao.getById(parentId).getDeleteDate(), "the parent survives");
        Assertions.assertEquals(parentId, tagDao.getById(childId).getParentId(),
                "and the child is still under it rather than re-parented to the root");
    }
}
