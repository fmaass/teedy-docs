package com.sismics.docs.rest.util;

import com.sismics.docs.rest.util.TagReductionUtil.DocumentReduction;
import com.sismics.docs.rest.util.TagReductionUtil.Reduction;
import com.sismics.docs.rest.util.TagReductionUtil.TagNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tests the redundancy predicate the tag-reduction run rests on (#293): a tag on a document is
 * redundant exactly when a tag BELOW it — at any depth, in the caller's own visible tree — is on
 * that same document.
 *
 * <p>The predicate is the whole feature. Everything the REST layer removes is what this returns,
 * on both the preview and the execute pass, so it is exercised here directly on plain inputs with
 * no database in the way. Two properties matter more than the happy path and are pinned
 * individually below: a tag the caller cannot READ never causes a removal (nor is anything about
 * it disclosed), and a document the caller cannot write is reported as skipped rather than
 * silently dropped or thrown over.
 *
 * @author fmaass
 */
public class TestTagReductionUtil {
    /** The reporter's own example from #293, three levels deep. */
    private static final List<TagNode> INSURANCE_TREE = List.of(
            new TagNode("insurance", "Insurance", null),
            new TagNode("car", "Car", "insurance"),
            new TagNode("year", "2026", "car"),
            new TagNode("home", "Home", "insurance"),
            new TagNode("travel", "Travel", null));

    private static Map<String, Set<String>> documentTags(String documentId, String... tagIds) {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        map.put(documentId, Set.of(tagIds));
        return map;
    }

    /** The tag IDs the plan removes from one document, in the order the report lists them. */
    private static List<String> removedIds(Reduction reduction, String documentId) {
        for (DocumentReduction document : reduction.documents()) {
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

    /**
     * The plain case the reporter described: parent and child both assigned, so the parent adds
     * nothing — the document is already found through the child.
     */
    @Test
    public void testAChildOnTheSameDocumentMakesItsParentRedundant() {
        Reduction reduction = TagReductionUtil.plan(INSURANCE_TREE, List.of("doc"), Set.of("doc"),
                documentTags("doc", "insurance", "car"));

        Assertions.assertEquals(List.of("insurance"), removedIds(reduction, "doc"),
                "the parent goes and the child stays");
        Assertions.assertTrue(reduction.skipped().isEmpty(), "a writable document is not skipped");
    }

    /**
     * Redundancy is TRANSITIVE, not one level: a document carrying Insurance / Car / 2026 in full
     * reduces to 2026 alone, because that one tag already implies both tags above it.
     */
    @Test
    public void testRedundancyIsTransitiveDownAWholeChain() {
        Reduction reduction = TagReductionUtil.plan(INSURANCE_TREE, List.of("doc"), Set.of("doc"),
                documentTags("doc", "insurance", "car", "year"));

        Assertions.assertEquals(List.of("insurance", "car"), removedIds(reduction, "doc"),
                "every ancestor of the deepest assigned tag goes, ordered by path");
    }

    /**
     * A branch point: two children of the same parent both assigned removes the parent once and
     * keeps BOTH children — neither is below the other, so neither implies the other.
     */
    @Test
    public void testTwoSiblingsRemoveTheirParentAndKeepEachOther() {
        Reduction reduction = TagReductionUtil.plan(INSURANCE_TREE, List.of("doc"), Set.of("doc"),
                documentTags("doc", "insurance", "car", "home"));

        Assertions.assertEquals(List.of("insurance"), removedIds(reduction, "doc"),
                "the shared parent goes exactly once, and no sibling takes another sibling with it");
    }

    /** A tag whose children are all absent from the document is not redundant. */
    @Test
    public void testAParentWithoutAnyOfItsDescendantsOnTheDocumentStays() {
        Reduction reduction = TagReductionUtil.plan(INSURANCE_TREE, List.of("doc"), Set.of("doc"),
                documentTags("doc", "insurance", "travel"));

        Assertions.assertNull(removedIds(reduction, "doc"),
                "an unrelated second tag is not a descendant, so nothing is redundant");
        Assertions.assertTrue(reduction.skipped().isEmpty(),
                "a document with nothing to reduce is not a skipped document");
    }

    /**
     * The ACL rule, in the direction that can destroy data: a child the caller may NOT read is not
     * a reason to strip the visible parent. The caller cannot see that child, so from where they
     * stand the parent is the only thing pointing at this document — removing it would silently
     * drop the document out of their own tag filter, on the strength of a tag they cannot see.
     */
    @Test
    public void testAHiddenChildNeverRemovesAVisibleParent() {
        // "hidden" is on the document but absent from the caller's visible tag list, exactly as an
        // ACL-scoped /tag/list would deliver it.
        List<TagNode> visible = List.of(new TagNode("insurance", "Insurance", null));

        Reduction reduction = TagReductionUtil.plan(visible, List.of("doc"), Set.of("doc"),
                documentTags("doc", "insurance", "hidden"));

        Assertions.assertNull(removedIds(reduction, "doc"),
                "an invisible child cannot make a visible parent redundant");
    }

    /**
     * The same rule one step further out: the caller sees a tag and a would-be grandchild, but the
     * link between them runs through a tag they cannot read. Their tag tree renders that
     * grandchild at ROOT level ({@code TagResource#list} omits a parent link the caller cannot
     * read), so as far as they can tell the two are unrelated — and a removal justified by a
     * relationship they cannot see would leak the shape of somebody else's tree.
     */
    @Test
    public void testAnInvisibleIntermediateBreaksTheChain() {
        List<TagNode> visible = List.of(
                new TagNode("insurance", "Insurance", null),
                // Its parent "car" is not in the visible list: an invisible intermediate.
                new TagNode("year", "2026", "car"));

        Reduction reduction = TagReductionUtil.plan(visible, List.of("doc"), Set.of("doc"),
                documentTags("doc", "insurance", "year"));

        Assertions.assertNull(removedIds(reduction, "doc"),
                "ancestry stops at the first tag the caller cannot read");
    }

    /**
     * A document the caller may not write is REPORTED as skipped, never modified and never an
     * error: a selection of a hundred documents that happens to include one shared read-only must
     * still reduce the other ninety-nine.
     */
    @Test
    public void testADocumentTheCallerCannotWriteIsSkipped() {
        Map<String, Set<String>> tags = new LinkedHashMap<>();
        tags.put("mine", Set.of("insurance", "car"));
        tags.put("theirs", Set.of("insurance", "car"));

        Reduction reduction = TagReductionUtil.plan(INSURANCE_TREE, List.of("mine", "theirs"),
                Set.of("mine"), tags);

        Assertions.assertEquals(List.of("insurance"), removedIds(reduction, "mine"));
        Assertions.assertNull(removedIds(reduction, "theirs"), "a skipped document is not reduced");
        Assertions.assertEquals(List.of("theirs"), reduction.skipped(),
                "and it is named in the report rather than dropped in silence");
    }

    /**
     * Two ids for the same document — a client is free to send them — must not report or remove
     * anything twice.
     */
    @Test
    public void testARepeatedDocumentIdIsReportedOnce() {
        Reduction reduction = TagReductionUtil.plan(INSURANCE_TREE, List.of("doc", "doc"),
                Set.of("doc"), documentTags("doc", "insurance", "car"));

        Assertions.assertEquals(1, reduction.documents().size(), "the document is planned once");
        Assertions.assertEquals(List.of("insurance"), removedIds(reduction, "doc"));
    }

    /**
     * A corrupt parent cycle removes NOTHING. Left unguarded the two tags of a cycle are each
     * other's ancestor, so the naive predicate calls both redundant and the document loses both
     * tags at once — the one outcome a reduction run must never produce. (The update endpoint
     * refuses to create a cycle; this walks rows read from the database, and TagMaintenanceUtil
     * guards the same corruption for the same reason.)
     */
    @Test
    public void testACycleRemovesNothing() {
        List<TagNode> cyclic = List.of(
                new TagNode("a", "A", "b"),
                new TagNode("b", "B", "a"));

        Reduction reduction = TagReductionUtil.plan(cyclic, List.of("doc"), Set.of("doc"),
                documentTags("doc", "a", "b"));

        Assertions.assertNull(removedIds(reduction, "doc"),
                "a cycle is reported as nothing to reduce, never as everything to remove");
    }

    /**
     * The report names each removed tag by its visible path, which is what the confirmation screen
     * shows: "Insurance / Car" is the only rendering that tells two same-named tags apart.
     */
    @Test
    public void testRemovedTagsCarryTheirVisibleAncestorPath() {
        Reduction reduction = TagReductionUtil.plan(INSURANCE_TREE, List.of("doc"), Set.of("doc"),
                documentTags("doc", "insurance", "car", "year"));

        List<String> paths = new ArrayList<>();
        for (TagReductionUtil.RemovedTag tag : reduction.documents().get(0).tags()) {
            paths.add(tag.path());
        }
        Assertions.assertEquals(List.of("Insurance", "Insurance / Car"), paths,
                "shallowest first, so the report reads like the tree the tags came off");
    }

    private static final TagReductionUtil.RemovedTag INSURANCE =
            new TagReductionUtil.RemovedTag("insurance", "Insurance", "Insurance");
    private static final TagReductionUtil.RemovedTag CAR =
            new TagReductionUtil.RemovedTag("car", "Car", "Insurance / Car");

    private static List<String> idsOf(List<TagReductionUtil.RemovedTag> tags) {
        List<String> ids = new ArrayList<>();
        for (TagReductionUtil.RemovedTag tag : tags) {
            ids.add(tag.id());
        }
        return ids;
    }

    /**
     * The ordinary case: every planned link was still there and the removal took all of them, so
     * the report is the plan and no second read is needed to say so.
     */
    @Test
    public void testAFullRemovalIsReportedWhole() {
        Assertions.assertEquals(List.of("insurance", "car"),
                idsOf(TagReductionUtil.reportedRemovals(List.of(INSURANCE, CAR), 2, Set.of())));
    }

    /**
     * A PARTIAL removal must not be reported as a whole one. One of the two links went in the gap
     * between reading the document's links and deleting them — a concurrent edit taking that tag
     * off — so the delete matched one row, not two. The report is what the document actually lost:
     * the tag that is no longer on it. Reporting both would tell the user a tag came off that is
     * still there.
     */
    @Test
    public void testAPartialRemovalReportsOnlyWhatIsActuallyGone() {
        // "car" is still on the document: it is the link that survived, so it was never removed.
        List<TagReductionUtil.RemovedTag> reported =
                TagReductionUtil.reportedRemovals(List.of(INSURANCE, CAR), 1, Set.of("car"));

        Assertions.assertEquals(List.of("insurance"), idsOf(reported));
    }

    /**
     * A removal that matched nothing reports nothing, whatever the plan said — there is no change
     * to write to the document's history either.
     */
    @Test
    public void testARemovalThatMatchedNothingReportsNothing() {
        Assertions.assertTrue(
                TagReductionUtil.reportedRemovals(List.of(INSURANCE, CAR), 0, Set.of("insurance", "car"))
                        .isEmpty());
    }

    /** No documents at all is an empty report, not a failure. */
    @Test
    public void testAnEmptySelectionPlansNothing() {
        Reduction reduction = TagReductionUtil.plan(INSURANCE_TREE, List.of(), Set.of(), Map.of());

        Assertions.assertTrue(reduction.documents().isEmpty());
        Assertions.assertTrue(reduction.skipped().isEmpty());
    }
}
