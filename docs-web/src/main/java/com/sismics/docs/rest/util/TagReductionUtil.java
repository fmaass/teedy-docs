package com.sismics.docs.rest.util;

import com.sismics.docs.core.constant.AuditLogType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.AclDao;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.dao.criteria.TagCriteria;
import com.sismics.docs.core.dao.dto.TagDto;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.util.AuditLogUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The tag-reduction run (#293): removing from a document every tag that a tag BELOW it on the same
 * document already implies.
 *
 * <p>The rule the reporter and the maintainer settled on: a tag on a document is redundant when a
 * STRICT DESCENDANT of it is on that same document, because the document is already found through
 * that descendant (a tag search matches a tag's subtree). It is TRANSITIVE, not one level — a
 * document carrying {@code Insurance / Car / 2026} in full reduces to {@code 2026} alone, which is
 * what makes the run worth having on a document view that only shows the first few tags.
 *
 * <p>Two ACL rules bound it, and they are asymmetric on purpose:</p>
 * <ul>
 *   <li>the child-present question is asked ONLY over tags the caller may READ. A hidden child
 *       must never cause a visible parent's removal: the caller cannot see that child, so removing
 *       the parent would drop the document out of the only tag filter they have for it — and the
 *       removal itself would disclose that something exists under that parent. Ancestry therefore
 *       stops at the first tag the caller cannot read, exactly as {@code TagResource#list} stops
 *       there when it omits an unreadable parent link and renders the tag at root level;</li>
 *   <li>only documents the caller may WRITE are modified. A selected document they may not write
 *       is SKIPPED and reported — not an error, because a hundred-document selection that includes
 *       one read-only share must still reduce the other ninety-nine.</li>
 * </ul>
 *
 * <p>The rule itself — {@link #redundantTags} and the {@link #plan(List, List, Set, Map) plan}
 * overload it serves — is pure: no DAO, no entity manager, so the decision the whole feature rests
 * on is testable on plain inputs.</p>
 *
 * <p>{@link #reduce} is two passes over that rule and the second does not trust the first.
 * {@link #plan(List, List)} answers what would go; {@link #apply} re-derives that answer PER
 * DOCUMENT — permission, aliveness and the document's current links, all re-read immediately before
 * that document's own removal — because a run of up to {@link #MAX_DOCUMENTS} documents gives
 * everything the plan saw time to change. Neither the client nor the plan supplies a removal list,
 * so nothing a tampered or stale preview says can remove a tag the rule does not call redundant at
 * the moment of removal. The removal is remove-only ({@link TagDao#removeTagLinks}): no code path
 * here can add a link to a document.</p>
 *
 * <p>This class lives in {@code rest.util} rather than in the resource because the resource
 * package's dependency on {@code core.dao} is frozen by
 * {@code DocumentSliceArchitectureTest#legacy_resource_dao_frozen} and may only shrink; the
 * neighbouring {@link TagMaintenanceUtil} and {@link DocumentResourceHelper} reach the DAOs the
 * same way.</p>
 *
 * @author fmaass
 */
public class TagReductionUtil {
    /** Separator between the ancestor names of a tag path, as the preview lists them. */
    private static final String PATH_SEPARATOR = " / ";

    /**
     * The most documents one run may carry.
     *
     * <p>The list is client-supplied and feeds batched {@code in (…)} reads, so an unbounded
     * request is both a database parameter-limit hazard and unbounded work on a single request
     * thread. A run is one page of the document list — the largest page size the UI offers is 100 —
     * so this is far above anything the screen can produce and still bounded.</p>
     */
    public static final int MAX_DOCUMENTS = 500;

    /**
     * One tag as the caller can see it: identity and parent link only.
     *
     * @param id Tag ID
     * @param name Tag name
     * @param parentId Parent tag ID, or null at root level. A parent the caller cannot read is
     *                 simply absent from the node list, which makes this tag a root for them.
     */
    public record TagNode(String id, String name, String parentId) {}

    /**
     * A tag a run removed from a document, or would.
     *
     * @param id Tag ID
     * @param name Tag name
     * @param path Slash-joined chain of visible ancestor names, this tag last
     */
    public record RemovedTag(String id, String name, String path) {}

    /**
     * What one document loses.
     *
     * @param documentId Document ID
     * @param tags The tags removed from it, shallowest first
     */
    public record DocumentReduction(String documentId, List<RemovedTag> tags) {}

    /**
     * What a whole run does, or would.
     *
     * @param documents The documents with something to remove; a document with nothing redundant
     *                  on it is absent rather than reported as an empty change
     * @param skipped Selected documents left untouched because the caller cannot write them, or
     *                because they no longer exist. Deliberately ONE list: telling the two apart
     *                would be an existence oracle for other users' documents
     */
    public record Reduction(List<DocumentReduction> documents, List<String> skipped) {}

    /**
     * Decides what every selected document would lose. Pure.
     *
     * @param visibleTags Every tag the caller may READ, with its parent link as the caller sees it
     * @param documentIds The selection, in the order the caller sent it (duplicates tolerated)
     * @param reducibleDocumentIds The subset that is alive AND writable by the caller
     * @param documentTagIds Tag IDs per document, UNSCOPED — invisible tags may be present and are
     *                       ignored by the rule
     * @return what would be removed, and what was skipped
     */
    public static Reduction plan(List<TagNode> visibleTags, List<String> documentIds,
                                 Set<String> reducibleDocumentIds,
                                 Map<String, Set<String>> documentTagIds) {
        Map<String, TagNode> byId = indexById(visibleTags);

        List<DocumentReduction> documents = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String documentId : documentIds) {
            // A client is free to send the same document twice; it must not be planned twice.
            if (!seen.add(documentId)) {
                continue;
            }
            if (!reducibleDocumentIds.contains(documentId)) {
                skipped.add(documentId);
                continue;
            }
            List<RemovedTag> removed = redundantTags(byId,
                    documentTagIds.getOrDefault(documentId, Set.of()));
            if (!removed.isEmpty()) {
                documents.add(new DocumentReduction(documentId, removed));
            }
        }
        return new Reduction(documents, skipped);
    }

    /**
     * The redundant tags of ONE document, over an explicit visible-tag list. Public because it is
     * the rule itself: both passes call it, the apply pass calls it again per document against
     * that document's CURRENT links, and it is exercised directly on plain inputs.
     *
     * @param visibleTags Every tag the caller may READ
     * @param tagIdsOnDocument The document's tag IDs, unscoped (invisible ones are ignored)
     * @return the tags to remove, shallowest first
     */
    public static List<RemovedTag> redundantTags(List<TagNode> visibleTags, Set<String> tagIdsOnDocument) {
        return redundantTags(indexById(visibleTags), tagIdsOnDocument);
    }

    /**
     * What a removal is allowed to REPORT, given what it planned, how many links the delete
     * actually matched, and — only when those two disagree — the document's links as they stand
     * afterwards.
     *
     * <p>A report is a claim about a document, so it may never say more than the database did. The
     * delete matches only links that are still live, so a count below the planned size means one of
     * them went in the gap between reading the document's links and deleting them. Reporting the
     * whole plan there would tell the user a tag came off a document it is still on.</p>
     *
     * <p>The three cases:</p>
     * <ul>
     *   <li>nothing matched — nothing is reported, whatever the plan said. Not "skipped" either:
     *       see {@link #apply};</li>
     *   <li>everything matched — the plan is the report, and no second read is needed to say so;</li>
     *   <li>some matched — the report is the planned tags that are no longer on the document. Every
     *       tag it names really is off the document, which is what the report claims. It cannot
     *       distinguish a link this run deleted from one a concurrent edit deleted a moment
     *       earlier, and does not try to: the count already proves this run removed something, and
     *       attributing each individual link would need a delete per tag for no gain to the reader.</li>
     * </ul>
     *
     * @param planned The removals the rule derived
     * @param removedRows Rows the delete actually matched
     * @param remainingTagIds The document's tag IDs after the delete; read only in the partial case
     * @return the removals to report, in the planned order
     */
    public static List<RemovedTag> reportedRemovals(List<RemovedTag> planned, int removedRows,
                                                    Set<String> remainingTagIds) {
        if (removedRows <= 0) {
            return List.of();
        }
        if (removedRows >= planned.size()) {
            return planned;
        }
        List<RemovedTag> reported = new ArrayList<>(removedRows);
        for (RemovedTag tag : planned) {
            if (!remainingTagIds.contains(tag.id())) {
                reported.add(tag);
            }
        }
        return reported;
    }

    private static Map<String, TagNode> indexById(List<TagNode> visibleTags) {
        Map<String, TagNode> byId = new LinkedHashMap<>();
        for (TagNode node : visibleTags) {
            byId.put(node.id(), node);
        }
        return byId;
    }

    /**
     * The redundant tags of ONE document: every visible tag on it that has a visible descendant on
     * it too.
     *
     * <p>Computed by walking UP from each present tag and collecting the present tags above it,
     * which is the same relation read from the cheap end — a tag has one parent chain, while
     * enumerating a subtree would mean walking the whole forest per tag.</p>
     *
     * <p>A parent CYCLE contributes nothing. The update endpoint refuses to create one, but this
     * walks rows read from the database, and in a cycle every member is every other member's
     * ancestor — a naive walk would call them all redundant and take every one of them off the
     * document at once, which is the single outcome a reduction run must never produce.</p>
     */
    private static List<RemovedTag> redundantTags(Map<String, TagNode> byId,
                                                  Set<String> tagIdsOnDocument) {
        Set<String> present = new LinkedHashSet<>();
        for (String tagId : tagIdsOnDocument) {
            if (byId.containsKey(tagId)) {
                present.add(tagId);
            }
        }

        Set<String> redundantIds = new LinkedHashSet<>();
        for (String tagId : present) {
            List<String> ancestorsPresent = new ArrayList<>();
            Set<String> onPath = new HashSet<>();
            onPath.add(tagId);
            boolean cyclic = false;
            String parentId = byId.get(tagId).parentId();
            // Stops at the first tag the caller cannot read: an unreadable link is not ancestry
            // they can see, and a removal justified by it would leak the shape of another user's
            // tree.
            while (parentId != null && byId.containsKey(parentId)) {
                if (!onPath.add(parentId)) {
                    cyclic = true;
                    break;
                }
                if (present.contains(parentId)) {
                    ancestorsPresent.add(parentId);
                }
                parentId = byId.get(parentId).parentId();
            }
            if (!cyclic) {
                redundantIds.addAll(ancestorsPresent);
            }
        }

        List<RemovedTag> removed = new ArrayList<>();
        for (String tagId : redundantIds) {
            TagNode node = byId.get(tagId);
            removed.add(new RemovedTag(tagId, node.name(), buildPath(node, byId)));
        }
        // Shallowest first, so the report reads like the tree the tags came off.
        removed.sort(Comparator.comparing(RemovedTag::path, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(RemovedTag::id));
        return removed;
    }

    /**
     * The tag's name preceded by its VISIBLE ancestors — the only rendering that tells two
     * same-named tags in different branches apart on the confirmation screen.
     */
    private static String buildPath(TagNode node, Map<String, TagNode> byId) {
        List<String> names = new ArrayList<>();
        names.add(node.name());
        Set<String> seen = new HashSet<>();
        seen.add(node.id());
        String parentId = node.parentId();
        while (parentId != null && seen.add(parentId)) {
            TagNode parent = byId.get(parentId);
            if (parent == null) {
                break;
            }
            names.add(parent.name());
            parentId = parent.parentId();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = names.size() - 1; i >= 0; i--) {
            sb.append(names.get(i));
            if (i > 0) {
                sb.append(PATH_SEPARATOR);
            }
        }
        return sb.toString();
    }

    /**
     * Plans a run over the given selection and, unless this is a dry run, carries it out.
     *
     * <p>Two passes, and the second one does not trust the first. {@link #plan} answers what would
     * go; {@link #apply} re-derives that answer per document, from state read immediately before
     * that document's own removal. The client never supplies a removal list — it sends document IDs
     * — and neither does the plan, which is why a confirm arriving after the documents changed
     * removes what is redundant NOW and reports what really went.</p>
     *
     * @param documentIds The selection
     * @param targetIdList Caller ACL target list
     * @param userId Acting user ID, for the audit log
     * @param dryRun True to plan without modifying anything
     * @return what was removed (or would be), and what was skipped
     */
    public static Reduction reduce(List<String> documentIds, List<String> targetIdList, String userId,
                                   boolean dryRun) {
        Reduction planned = plan(documentIds, targetIdList);
        return dryRun ? planned : apply(planned, targetIdList, userId);
    }

    /**
     * The PREVIEW: what the run would remove from each selected document, modifying nothing.
     *
     * @param documentIds The selection
     * @param targetIdList Caller ACL target list
     * @return what would be removed, and what cannot be touched at all
     */
    public static Reduction plan(List<String> documentIds, List<String> targetIdList) {
        List<String> requested = new ArrayList<>(new LinkedHashSet<>(documentIds));
        if (requested.isEmpty()) {
            return new Reduction(List.of(), List.of());
        }

        Set<String> reducible = reducibleIds(requested, targetIdList);
        return plan(loadVisibleTags(targetIdList), requested, reducible,
                new TagDao().findTagIdsByDocumentIds(reducible));
    }

    /**
     * Carries out a plan, RE-DERIVING it per document immediately before touching that document.
     *
     * <p>Deliberately public, and deliberately given a plan it does not trust — the same shape as
     * {@code TagMaintenanceUtil#deleteAll}, and for the same reason. A plan is a snapshot: over a
     * selection of up to {@link #MAX_DOCUMENTS} documents, everything it saw can change while the
     * loop is running. So for each planned document, and inside the caller's transaction, this
     * re-reads the three facts the removal depends on and acts on those:</p>
     * <ol>
     *   <li>WRITE permission, through {@link AclDao#checkPermission} — the same primitive every
     *       document write in this codebase authorizes with, so a document writable only through a
     *       tag ACL (Teedy's usual sharing model) is reducible, and a grant revoked mid-run is not.
     *       Failure is reported as skipped, never as an error;</li>
     *   <li>the document is still ALIVE ({@link DocumentDao#getById} is active-only). The trash
     *       soft-deletes a document's tag links AND its ACLs with it, and a restore revives exactly
     *       those sets, so a document trashed mid-run is already refused by the permission check
     *       above; this states the requirement in its own right rather than resting on that, and
     *       also covers a document that has been purged outright;</li>
     *   <li>the document's CURRENT tag links, from which the redundant set is recomputed by the very
     *       rule the preview used. The plan's own {@code tags()} are never read here. A tag added
     *       since the preview is therefore not swept away, and a tag whose removal the rule no
     *       longer justifies — because the descendant that made it redundant has gone — stays.</li>
     * </ol>
     *
     * <p>The removal itself is {@link TagDao#removeTagLinks}, which only ever DELETES links: there
     * is no code path here that inserts one, so no state change can resurrect a link on a document
     * or add one the rule did not ask for. A document whose links all vanished in the meantime
     * removes nothing, writes no audit row, and is reported as neither changed nor skipped.</p>
     *
     * @param plan What the preview decided (untrusted)
     * @param targetIdList Caller ACL target list
     * @param userId Acting user ID, for the audit log
     * @return what was actually removed, and what was skipped
     */
    public static Reduction apply(Reduction plan, List<String> targetIdList, String userId) {
        List<DocumentReduction> applied = new ArrayList<>();
        List<String> skipped = new ArrayList<>(plan.skipped());
        if (plan.documents().isEmpty()) {
            return new Reduction(applied, skipped);
        }

        Map<String, TagNode> byId = indexById(loadVisibleTags(targetIdList));
        AclDao aclDao = new AclDao();
        DocumentDao documentDao = new DocumentDao();
        TagDao tagDao = new TagDao();

        for (DocumentReduction planned : plan.documents()) {
            String documentId = planned.documentId();
            // Authorization first, so a caller without WRITE is refused before any lookup — the
            // order DocumentCoverHandler and the other document writers use.
            if (!aclDao.checkPermission(documentId, PermType.WRITE, targetIdList)) {
                skipped.add(documentId);
                continue;
            }
            Document document = documentDao.getById(documentId);
            if (document == null) {
                skipped.add(documentId);
                continue;
            }

            Set<String> liveTagIds = tagDao.findTagIdsByDocumentIds(List.of(documentId))
                    .getOrDefault(documentId, Set.of());
            List<RemovedTag> removed = redundantTags(byId, liveTagIds);
            if (removed.isEmpty()) {
                continue;
            }
            List<String> removedIds = new ArrayList<>(removed.size());
            for (RemovedTag tag : removed) {
                removedIds.add(tag.id());
            }
            // ACCEPTED WINDOW — do not "fix" this with a lock or a retry. Between the read above and
            // this delete, a concurrent transaction can take the child link off the document, which
            // makes the parent no longer redundant an instant after this decided that it was. It is
            // the same read-then-write window EVERY tag edit in Teedy has, the worst case is one
            // parent tag removed from one document that was redundant a moment earlier, and the
            // project's triage rule takes documented accepted-risk over locking/ordering machinery
            // for this class (it breeds worse edge cases than it closes). Adjudicated 2026-08-25.
            int removedRows = tagDao.removeTagLinks(documentId, removedIds);
            // The delete only matches LIVE links, so a short count means one of the planned links
            // went in that window. Re-read once — only then — so the report names what the document
            // actually lost rather than what was planned.
            Set<String> remainingTagIds = removedRows > 0 && removedRows < removedIds.size()
                    ? tagDao.findTagIdsByDocumentIds(List.of(documentId)).getOrDefault(documentId, Set.of())
                    : Set.of();
            List<RemovedTag> reported = reportedRemovals(removed, removedRows, remainingTagIds);
            if (reported.isEmpty()) {
                // Nothing came off. The document is reported as UNCHANGED — absent from both lists —
                // and not as skipped: "skipped" means the run could not touch the document at all
                // (no WRITE, or it is gone), which the screen tells the user in those words. Saying
                // that about a document they can edit, whose tags simply went before the run reached
                // them, would be false. An unchanged document needs no line of its own; the run's
                // count already says nothing was removed.
                continue;
            }
            // A bulk tag change through the document endpoint leaves an UPDATE in the audit log;
            // this one changes the same rows and leaves the same trace, so the document's history
            // does not go quiet just because the tags came off in bulk. Only a document something
            // was really removed from gets one.
            AuditLogUtil.create(document, AuditLogType.UPDATE, userId);
            applied.add(new DocumentReduction(documentId, reported));
        }
        return new Reduction(applied, skipped);
    }

    /**
     * The selected documents this caller may actually reduce: ALIVE and WRITE-permitted.
     *
     * <p>The permission question is asked per document through {@link AclDao#checkPermission},
     * never through {@code filterPermitted}, whose own javadoc forbids document IDs: that method
     * mirrors only the direct-ACL branch, so every document shared by sharing the TAG it carries —
     * the way Teedy is normally shared — would look unwritable and be skipped.</p>
     */
    private static Set<String> reducibleIds(List<String> documentIds, List<String> targetIdList) {
        Set<String> alive = new DocumentDao().findAliveIds(documentIds);
        AclDao aclDao = new AclDao();
        Set<String> reducible = new LinkedHashSet<>();
        for (String documentId : documentIds) {
            // Aliveness comes from one batched read, so the per-document ACL query runs only for
            // documents that still exist.
            if (alive.contains(documentId)
                    && aclDao.checkPermission(documentId, PermType.WRITE, targetIdList)) {
                reducible.add(documentId);
            }
        }
        return reducible;
    }

    /**
     * Every tag the caller may READ, with the parent link they can see. A parent outside this list
     * is dropped by {@link #plan} exactly as {@code TagResource#list} drops it — an unreadable
     * parent is not part of the tree the caller has.
     */
    private static List<TagNode> loadVisibleTags(List<String> targetIdList) {
        List<TagNode> nodes = new ArrayList<>();
        for (TagDto tagDto : new TagDao().findByCriteria(new TagCriteria().setTargetIdList(targetIdList), null)) {
            nodes.add(new TagNode(tagDto.getId(), tagDto.getName(), tagDto.getParentId()));
        }
        return nodes;
    }
}
