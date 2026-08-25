package com.sismics.docs.rest.util;

import com.sismics.docs.rest.util.TagMaintenanceUtil.BlockReason;
import com.sismics.docs.rest.util.TagMaintenanceUtil.TagNode;
import com.sismics.docs.rest.util.TagMaintenanceUtil.TagStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tests the fully-unused-subtree predicate that decides what tag maintenance may delete (#298
 * parts 1 and 2). The predicate is the whole safety property of the feature: everything the
 * REST layer removes is what this returns as deletable, so it is exercised here directly, on
 * plain inputs, with no database in the way.
 *
 * @author fmaass
 */
public class TestTagMaintenanceUtil {
    /** Every tag in the fixture is visible and writable unless a test says otherwise. */
    private static Set<String> idsOf(List<TagNode> nodes) {
        Set<String> ids = new LinkedHashSet<>();
        for (TagNode node : nodes) {
            ids.add(node.id());
        }
        return ids;
    }

    private static Map<String, TagStatus> byId(List<TagStatus> statusList) {
        Map<String, TagStatus> map = new HashMap<>();
        for (TagStatus status : statusList) {
            map.put(status.id(), status);
        }
        return map;
    }

    /**
     * The reporter's own example, verbatim from #298:
     * <pre>
     * top tag (0 documents)
     * |- sub tag (0 documents)
     *    |- sub-sub tag (2 documents)
     * </pre>
     * "As long as tags are sticking to any doc, do not delete them generally" — so NONE of the
     * three may be deleted, the used leaf least of all, and the unused chain above it keeps the
     * structure rather than being collapsed.
     */
    @Test
    public void testUsedDeepChildProtectsTheWholeChain() {
        List<TagNode> nodes = List.of(
                new TagNode("top", "Top", null),
                new TagNode("sub", "Sub", "top"),
                new TagNode("leaf", "Leaf", "sub"));
        Map<String, Long> counts = Map.of("leaf", 2L);

        Map<String, TagStatus> status = byId(TagMaintenanceUtil.buildStatus(
                nodes, idsOf(nodes), idsOf(nodes), counts, counts.keySet(), Set.of()));

        Assertions.assertFalse(status.get("top").deletable(), "the unused root of a used subtree");
        Assertions.assertFalse(status.get("sub").deletable(), "the unused middle of a used subtree");
        Assertions.assertFalse(status.get("leaf").deletable(), "the used leaf itself");

        // The reason must be quotable in the disabled affordance: 2 documents in this subtree.
        Assertions.assertEquals(BlockReason.DOCUMENTS, status.get("top").reason());
        Assertions.assertEquals(2L, status.get("top").subtreeDocumentCount());
        Assertions.assertEquals(2L, status.get("sub").subtreeDocumentCount());
        Assertions.assertEquals(2L, status.get("leaf").subtreeDocumentCount());
    }

    /**
     * A subtree nobody's documents touch is deletable whole, and only its topmost tag is a
     * cleanup root — deleting that one takes the descendants with it, so listing them as roots
     * too would double-count the preview.
     */
    @Test
    public void testFullyUnusedSubtreeIsDeletableFromItsRoot() {
        List<TagNode> nodes = List.of(
                new TagNode("root", "Archive2019", null),
                new TagNode("mid", "Q1", "root"),
                new TagNode("leaf", "January", "mid"));

        Map<String, TagStatus> status = byId(TagMaintenanceUtil.buildStatus(
                nodes, idsOf(nodes), idsOf(nodes), Map.of(), Set.of(), Set.of()));

        Assertions.assertTrue(status.get("root").deletable());
        Assertions.assertTrue(status.get("mid").deletable());
        Assertions.assertTrue(status.get("leaf").deletable());
        Assertions.assertTrue(status.get("root").root(), "the topmost unused tag is a cleanup root");
        Assertions.assertFalse(status.get("mid").root(), "a descendant of a cleanup root is not one");
        Assertions.assertFalse(status.get("leaf").root());
        Assertions.assertNull(status.get("root").reason());
    }

    /**
     * A sibling branch carrying documents must not make its unused sibling undeletable — the
     * predicate is about the subtree, not about the tree.
     */
    @Test
    public void testUsedSiblingDoesNotBlockAnUnusedBranch() {
        List<TagNode> nodes = List.of(
                new TagNode("parent", "Parent", null),
                new TagNode("used", "Used", "parent"),
                new TagNode("unused", "Unused", "parent"));
        Map<String, Long> counts = Map.of("used", 5L);

        Map<String, TagStatus> status = byId(TagMaintenanceUtil.buildStatus(
                nodes, idsOf(nodes), idsOf(nodes), counts, counts.keySet(), Set.of()));

        Assertions.assertTrue(status.get("unused").deletable(), "the unused branch");
        Assertions.assertTrue(status.get("unused").root(), "its parent is not deletable, so it is a root");
        Assertions.assertFalse(status.get("used").deletable());
        Assertions.assertFalse(status.get("parent").deletable());
        Assertions.assertEquals(5L, status.get("parent").subtreeDocumentCount());
        Assertions.assertEquals(0L, status.get("unused").subtreeDocumentCount());
    }

    /**
     * A descendant the caller cannot see is still part of the subtree a cascade delete would
     * remove, so its presence alone must block the delete. Reported as PERMISSION and with a
     * subtree count of 0: the count is summed over the tags the caller may READ, so nothing
     * about the invisible tag's documents is disclosed by the refusal.
     */
    @Test
    public void testInvisibleDescendantBlocksTheDelete() {
        List<TagNode> nodes = List.of(
                new TagNode("root", "Root", null),
                new TagNode("hidden", "SomeoneElses", "root"));
        Set<String> mine = Set.of("root");

        Map<String, TagStatus> status = byId(TagMaintenanceUtil.buildStatus(
                nodes, mine, mine, Map.of(), Set.of(), Set.of()));

        Assertions.assertFalse(status.get("root").deletable());
        Assertions.assertEquals(BlockReason.OTHER, status.get("root").reason());
        Assertions.assertEquals(0L, status.get("root").subtreeDocumentCount());
        Assertions.assertNull(status.get("hidden"), "an invisible tag is not reported at all");
    }

    /** A tag the caller may read but not write is not theirs to delete. */
    @Test
    public void testReadOnlyTagIsNotDeletable() {
        List<TagNode> nodes = List.of(new TagNode("t", "Shared", null));

        Map<String, TagStatus> status = byId(TagMaintenanceUtil.buildStatus(
                nodes, Set.of("t"), Set.of(), Map.of(), Set.of(), Set.of()));

        Assertions.assertFalse(status.get("t").deletable());
        Assertions.assertEquals(BlockReason.OTHER, status.get("t").reason());
    }

    /**
     * An auto-tagging rule pointing at a tag is a live job the tag is doing even with zero
     * documents on it — deleting it would break the rule silently, so the tag counts as in use.
     */
    @Test
    public void testTagTargetedByAnAutoTagRuleIsNotUnused() {
        List<TagNode> nodes = List.of(
                new TagNode("parent", "Inbox", null),
                new TagNode("ruled", "AutoInvoice", "parent"));

        Map<String, TagStatus> status = byId(TagMaintenanceUtil.buildStatus(
                nodes, idsOf(nodes), idsOf(nodes), Map.of(), Set.of(), Set.of("ruled")));

        Assertions.assertFalse(status.get("ruled").deletable(), "the rule's target tag");
        Assertions.assertEquals(BlockReason.RULE, status.get("ruled").reason());
        Assertions.assertFalse(status.get("parent").deletable(), "its ancestor, which would cascade it away");
        Assertions.assertEquals(BlockReason.RULE, status.get("parent").reason());
    }

    /** The path is what the preview lists, so it must read as the tree does. */
    @Test
    public void testPathIsTheVisibleAncestorChain() {
        List<TagNode> nodes = List.of(
                new TagNode("a", "Archive", null),
                new TagNode("b", "2019", "a"),
                new TagNode("c", "Q1", "b"));

        Map<String, TagStatus> status = byId(TagMaintenanceUtil.buildStatus(
                nodes, idsOf(nodes), idsOf(nodes), Map.of(), Set.of(), Set.of()));

        Assertions.assertEquals("Archive", status.get("a").path());
        Assertions.assertEquals("Archive / 2019", status.get("b").path());
        Assertions.assertEquals("Archive / 2019 / Q1", status.get("c").path());
    }

    /**
     * A path is built through VISIBLE ancestors only. The tag list already renders a tag whose
     * parent is invisible at root level (TagResource#list omits the parent link), so quoting the
     * invisible parent's NAME in a maintenance path would both contradict the tree and disclose
     * a name the caller may not read.
     */
    @Test
    public void testPathStopsAtAnInvisibleAncestor() {
        List<TagNode> nodes = List.of(
                new TagNode("hidden", "Confidential", null),
                new TagNode("mine", "Mine", "hidden"));

        Map<String, TagStatus> status = byId(TagMaintenanceUtil.buildStatus(
                nodes, Set.of("mine"), Set.of("mine"), Map.of(), Set.of(), Set.of()));

        Assertions.assertEquals("Mine", status.get("mine").path());
    }

    /**
     * The cleanup deletes every deletable tag, and the preview names the roots. Both are read
     * off the same status list, so a divergence between what is previewed and what is deleted
     * is not representable.
     */
    @Test
    public void testDeletableSetIsTheUnionOfTheRootSubtrees() {
        List<TagNode> nodes = List.of(
                new TagNode("keep", "Keep", null),
                new TagNode("kept-child", "KeptChild", "keep"),
                new TagNode("gone", "Gone", null),
                new TagNode("gone-child", "GoneChild", "gone"),
                new TagNode("orphan", "Orphan", null));
        Map<String, Long> counts = Map.of("kept-child", 1L);

        List<TagStatus> statusList = TagMaintenanceUtil.buildStatus(
                nodes, idsOf(nodes), idsOf(nodes), counts, counts.keySet(), Set.of());

        List<String> deletable = new ArrayList<>();
        List<String> roots = new ArrayList<>();
        for (TagStatus status : statusList) {
            if (status.deletable()) {
                deletable.add(status.id());
            }
            if (status.root()) {
                roots.add(status.id());
            }
        }
        Assertions.assertEquals(new HashSet<>(List.of("gone", "gone-child", "orphan")), new HashSet<>(deletable));
        Assertions.assertEquals(new HashSet<>(List.of("gone", "orphan")), new HashSet<>(roots));
    }

    /**
     * A tag no ACTIVE document carries but a restorable one in the TRASH still references is NOT
     * unused: restoring that document revives its link, and the tag delete only detaches live
     * links, so removing it would strand a row pointing at a tag that no longer exists.
     *
     * <p>The displayed count stays ACTIVE-only — that is what the tag tree's count has always
     * meant — so the reason must be the trash one and not a documents refusal quoting a zero.
     */
    @Test
    public void testTagHeldOnlyByTheTrashIsNotUnused() {
        List<TagNode> nodes = List.of(
                new TagNode("parent", "Parent", null),
                new TagNode("trashed", "TrashHeld", "parent"));

        Map<String, TagStatus> status = byId(TagMaintenanceUtil.buildStatus(
                nodes, idsOf(nodes), idsOf(nodes), Map.of(), Set.of("trashed"), Set.of()));

        Assertions.assertFalse(status.get("trashed").deletable(), "the tag the trash still holds");
        Assertions.assertEquals(BlockReason.TRASH, status.get("trashed").reason());
        Assertions.assertEquals(0L, status.get("trashed").subtreeDocumentCount(),
                "no ACTIVE document carries it, and the count says only that");
        Assertions.assertFalse(status.get("parent").deletable(), "and its ancestor, which would cascade it away");
        Assertions.assertEquals(BlockReason.TRASH, status.get("parent").reason());
    }

    /**
     * A subtree holding a tag the caller cannot reach reports the GENERIC reason, never one that
     * says "permission" or "sub-tag": either would confirm to the caller that a tag they cannot
     * see exists underneath one they own. The generic answer is checked BEFORE the trash reason
     * for the same reason — the invisible tag may well be the thing holding the branch.
     */
    @Test
    public void testAnInvisibleDescendantNeverExplainsItself() {
        List<TagNode> nodes = List.of(
                new TagNode("root", "Root", null),
                new TagNode("hidden", "SomeoneElses", "root"));
        Set<String> mine = Set.of("root");

        Map<String, TagStatus> status = byId(TagMaintenanceUtil.buildStatus(
                nodes, mine, mine, Map.of(), Set.of("hidden"), Set.of()));

        Assertions.assertFalse(status.get("root").deletable());
        Assertions.assertEquals(BlockReason.OTHER, status.get("root").reason(),
                "the used-but-invisible descendant must not surface as TRASH either");
        Assertions.assertEquals(0L, status.get("root").subtreeDocumentCount());
    }

    /**
     * The pre-delete re-check, on its own. It runs on facts read immediately before a tag is
     * soft-deleted, in the same transaction, and is what stops an assignment that landed after the
     * verdict was computed from being deleted along with the tag.
     */
    @Test
    public void testRecheckRefusesATagThatBecameUsed() {
        Assertions.assertNull(TagMaintenanceUtil.recheckReason(true, false, false),
                "still writable, on nothing, no rule: it may go");
        Assertions.assertEquals(BlockReason.DOCUMENTS, TagMaintenanceUtil.recheckReason(true, true, false),
                "a document was attached after the verdict");
        Assertions.assertEquals(BlockReason.RULE, TagMaintenanceUtil.recheckReason(true, false, true),
                "a rule started pointing at it after the verdict");
        Assertions.assertEquals(BlockReason.OTHER, TagMaintenanceUtil.recheckReason(false, false, false),
                "the caller's write permission was revoked after the verdict");
        Assertions.assertEquals(BlockReason.OTHER, TagMaintenanceUtil.recheckReason(false, true, true),
                "a lost permission outranks the rest — it is answered without explaining itself");
    }

    /** A tag status carrying only what the sweep-report decision reads. */
    private static TagStatus reported(String id, String name) {
        return new TagStatus(id, name, name, null, true, true, 0L, null);
    }

    /**
     * A single-branch delete is advertised as all-or-nothing, and the pre-delete re-check can fire
     * in the MIDDLE of the branch: the loop runs leaf-up, so a reference landing on the ROOT is
     * seen only after the descendants have been soft-deleted. Reporting that as DELETED would be a
     * partial deletion dressed as a clean success — the caller is told the branch went while part
     * of it stands, with nothing to tell them to look.
     *
     * <p>The refusal is also what undoes the writes: the whole REST request is one transaction that
     * commits only for a 2xx/3xx response, so the 400 this maps to rolls the soft-deletes back.</p>
     */
    @Test
    public void testASubtreeWithAKeptMemberIsRefusedRatherThanReportedDeleted() {
        TagStatus root = reported("root", "Root");
        TagStatus child = reported("child", "Child");

        TagMaintenanceUtil.DeleteResult result = TagMaintenanceUtil.subtreeResultOf(
                new TagMaintenanceUtil.Sweep(List.of(child), List.of(root), BlockReason.DOCUMENTS));

        Assertions.assertEquals(TagMaintenanceUtil.DeleteOutcome.IN_USE, result.outcome(),
                "the branch is refused, and keeps the root cause's own reason");
        Assertions.assertTrue(result.deleted().isEmpty(),
                "and the report claims NOTHING went — the child's soft-delete is rolled back with the request");
    }

    /** A member kept because the caller lost write access is refused without saying so. */
    @Test
    public void testAKeptMemberFromAPermissionChangeIsRefusedOpaquely() {
        TagMaintenanceUtil.DeleteResult result = TagMaintenanceUtil.subtreeResultOf(
                new TagMaintenanceUtil.Sweep(List.of(), List.of(reported("root", "Root")), BlockReason.OTHER));

        Assertions.assertEquals(TagMaintenanceUtil.DeleteOutcome.NOT_DELETABLE, result.outcome());
        Assertions.assertTrue(result.deleted().isEmpty());
    }

    /**
     * An ancestor kept ONLY to avoid orphaning a blocked descendant carries no reason of its own,
     * so the refusal falls back to the opaque one rather than reading a null reason.
     */
    @Test
    public void testAPurelyPropagatedBlockRefusesWithoutAReason() {
        TagMaintenanceUtil.DeleteResult result = TagMaintenanceUtil.subtreeResultOf(
                new TagMaintenanceUtil.Sweep(List.of(), List.of(reported("root", "Root")), null));

        Assertions.assertEquals(TagMaintenanceUtil.DeleteOutcome.NOT_DELETABLE, result.outcome());
    }

    /** Nothing kept: the branch really went, and the report says what did. */
    @Test
    public void testASweepWithNothingKeptReportsTheDeletion() {
        TagStatus root = reported("root", "Root");

        TagMaintenanceUtil.DeleteResult result = TagMaintenanceUtil.subtreeResultOf(
                new TagMaintenanceUtil.Sweep(List.of(root), List.of(), null));

        Assertions.assertEquals(TagMaintenanceUtil.DeleteOutcome.DELETED, result.outcome());
        Assertions.assertEquals(List.of(root), result.deleted());
    }

    /**
     * The subtree walk must terminate on a parent cycle. The update endpoint refuses to create
     * one, but the walk runs over rows read from the database, and a maintenance sweep that
     * hangs the request thread on corrupt data is a worse outcome than one that reports the
     * cycle as undeletable.
     */
    @Test
    public void testParentCycleDoesNotHang() {
        List<TagNode> nodes = List.of(
                new TagNode("x", "X", "y"),
                new TagNode("y", "Y", "x"));

        List<TagStatus> statusList = Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(5),
                () -> TagMaintenanceUtil.buildStatus(nodes, idsOf(nodes), idsOf(nodes), Map.of(), Set.of(), Set.of()));

        Assertions.assertEquals(2, statusList.size());
        for (TagStatus status : statusList) {
            Assertions.assertFalse(status.deletable(), "a tag in a parent cycle is never deletable");
        }
    }
}
