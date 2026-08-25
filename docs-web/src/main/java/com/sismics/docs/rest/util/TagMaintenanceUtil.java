package com.sismics.docs.rest.util;

import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.AclDao;
import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.dao.TagMatchRuleDao;
import com.sismics.docs.core.dao.criteria.TagCriteria;
import com.sismics.docs.core.dao.dto.TagDto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tag maintenance (#298 parts 1 and 2): deciding which tags the tag-management screen may remove,
 * and removing them.
 *
 * <p>The rule the reporter settled on is deliberately narrow: a tag may be deleted from the
 * management tree only when its ENTIRE subtree is unused — the tag itself is on no document and
 * neither is any descendant. Deleting the root of such a subtree takes the whole subtree with it.
 * Anything still attached to a document stays, and nothing is ever un-assigned to make a tag
 * deletable ("as long as tags are sticking to any doc, do not delete them generally", #298). An
 * unused chain of parents above a USED deep child therefore keeps its structure — the chain is not
 * deletable, and the screen says why.
 *
 * <p>"On a document" includes a document sitting in the TRASH. The trash is restorable and
 * {@link com.sismics.docs.core.dao.DocumentDao#restore} revives exactly the document-tag rows the
 * trash soft-deleted, while {@link TagDao#delete} only detaches the LIVE ones — so deleting a tag
 * the trash still holds would strand a row that a later restore re-attaches to a tag that no
 * longer exists. {@link TagDao#findTagIdsWithDocumentReference} is the authoritative usage set and
 * covers both states; the counts shown on screen stay ACTIVE-only, which is what the tag tree's
 * count has always meant, so a tag held only by the trash gets its own {@link BlockReason#TRASH}
 * rather than a "0 documents" refusal that would send the user looking for nothing.
 *
 * <p>One caveat the callers inherit: {@link #deleteSubtree} is all-or-nothing only because the
 * REST request is one transaction that rolls back on a non-2xx/3xx response
 * ({@code RequestContextFilter#commitAndFinalize}). It refuses AFTER {@link #deleteAll} may already
 * have soft-deleted part of the branch, and relies on that rollback to undo them. A caller outside
 * a request transaction gets the refusal but keeps the partial deletion.
 *
 * <p>This class lives in {@code rest.util} rather than in the resource because the resource
 * package's dependency on {@code core.dao} is frozen by
 * {@code DocumentSliceArchitectureTest#legacy_resource_dao_frozen} and may only shrink; the
 * neighbouring {@link DocumentResourceHelper} and {@link UserUpdateUtil} reach the DAOs the same
 * way. {@link #buildStatus} and {@link #recheckReason} are kept pure — no DAO, no entity manager —
 * so the two decisions the whole feature rests on are testable on plain inputs.
 *
 * @author fmaass
 */
public class TagMaintenanceUtil {
    /** Separator between the ancestor names of a tag path, as the preview lists them. */
    private static final String PATH_SEPARATOR = " / ";

    /** Why a tag's subtree may not be removed. */
    public enum BlockReason {
        /** An ACTIVE document in the subtree carries a tag. */
        DOCUMENTS,
        /** No active document, but a restorable one in the trash still holds a tag in the subtree. */
        TRASH,
        /** The subtree holds a tag an auto-tagging rule points at. */
        RULE,
        /**
         * Everything else, deliberately unexplained. This is what a subtree holding a tag the
         * caller may not read or write reports, and telling the caller THAT would confirm that a
         * tag they cannot see exists underneath one they own — an oracle for another user's tag
         * tree. It also covers a corrupt parent cycle, which has no user-actionable explanation
         * either.
         */
        OTHER
    }

    /** How a subtree deletion ended. */
    public enum DeleteOutcome {
        /** The subtree was removed. */
        DELETED,
        /** No such tag, or the caller may not write it. */
        NOT_FOUND,
        /** A tag in the subtree is on a document (active or in the trash). */
        IN_USE,
        /** A tag in the subtree is an auto-tagging rule's target. */
        IN_RULE,
        /** Refused for a reason the caller is not told, for the {@link BlockReason#OTHER} reasons. */
        NOT_DELETABLE
    }

    /**
     * One tag as read from the database for the maintenance view: identity and parent link only.
     *
     * @param id Tag ID
     * @param name Tag name
     * @param parentId Parent tag ID, or null at root level
     */
    public record TagNode(String id, String name, String parentId) {}

    /**
     * The maintenance verdict on one tag.
     *
     * @param id Tag ID
     * @param name Tag name
     * @param path Slash-joined chain of visible ancestor names, this tag last
     * @param parentId Parent tag ID, or null at root level (not part of the REST payload; the
     *                 delete loop needs it to keep a blocked tag's ancestors standing)
     * @param deletable Whether the whole subtree rooted here may be removed
     * @param root Whether this tag is the topmost deletable tag of its branch
     * @param subtreeDocumentCount ACTIVE documents on this tag and its readable descendants
     * @param reason Why it is not deletable, or null when it is
     */
    public record TagStatus(String id, String name, String path, String parentId, boolean deletable,
                            boolean root, long subtreeDocumentCount, BlockReason reason) {}

    /**
     * What a sweep did.
     *
     * @param deleted The tags removed, shallowest first
     * @param blocked The tags the pre-delete re-check kept, with the ancestors that would have
     *                been orphaned by removing them
     * @param blockReason Why the FIRST tag blocked on its own account was kept, or null when
     *                    nothing was blocked. Ancestors kept only to avoid orphaning a blocked
     *                    descendant have no reason of their own, so this is the root cause.
     */
    public record Sweep(List<TagStatus> deleted, List<TagStatus> blocked, BlockReason blockReason) {}

    /**
     * The result of a single-subtree deletion attempt.
     *
     * @param outcome How it ended
     * @param deleted The tags removed, shallowest first; empty unless the outcome is DELETED
     */
    public record DeleteResult(DeleteOutcome outcome, List<TagStatus> deleted) {}

    /** What a subtree aggregates over itself and its descendants. */
    private record Aggregate(long documentCount, boolean used, boolean allPermitted, boolean anyRuleTag,
                             boolean cyclic) {}

    /**
     * Decides, for every tag the caller can read, whether its whole subtree is unused and
     * therefore removable.
     *
     * <p>The node list is UNSCOPED on purpose. A descendant the caller cannot see is still part of
     * the subtree a cascade delete would remove, so it has to take part in the guard; without it a
     * hidden, used child would look like an empty branch. Its documents, on the other hand, are
     * NOT part of {@code subtreeDocumentCount}: the counts come from the caller's ACL-scoped map,
     * so a refusal caused by an invisible tag reports {@link BlockReason#OTHER} and a count of
     * zero rather than disclosing anything about that tag.
     *
     * @param nodes EVERY alive tag in the instance, unscoped by ACL
     * @param visibleIds Tags the caller may READ
     * @param writableIds Tags the caller may WRITE
     * @param documentCounts ACTIVE document count per tag, over the caller's readable tags
     * @param usedTagIds Tags an active OR restorable-from-trash document references, unscoped
     * @param ruleTagIds Tags an alive auto-tagging rule points at
     * @return Status of every visible tag, ordered by path
     */
    public static List<TagStatus> buildStatus(List<TagNode> nodes, Set<String> visibleIds,
                                              Set<String> writableIds, Map<String, Long> documentCounts,
                                              Set<String> usedTagIds, Set<String> ruleTagIds) {
        Map<String, TagNode> byId = new LinkedHashMap<>();
        Map<String, List<String>> childIds = new HashMap<>();
        for (TagNode node : nodes) {
            byId.put(node.id(), node);
        }
        for (TagNode node : nodes) {
            if (node.parentId() != null && byId.containsKey(node.parentId())) {
                childIds.computeIfAbsent(node.parentId(), key -> new ArrayList<>()).add(node.id());
            }
        }

        Map<String, Aggregate> aggregates = new HashMap<>();
        for (TagNode node : nodes) {
            aggregate(node.id(), childIds, visibleIds, writableIds, documentCounts, usedTagIds,
                    ruleTagIds, aggregates, new HashSet<>());
        }

        Map<String, Boolean> deletableById = new HashMap<>();
        for (TagNode node : nodes) {
            deletableById.put(node.id(), isDeletable(aggregates.get(node.id())));
        }

        List<TagStatus> statusList = new ArrayList<>();
        for (TagNode node : nodes) {
            if (!visibleIds.contains(node.id())) {
                continue;
            }
            Aggregate aggregate = aggregates.get(node.id());
            boolean deletable = deletableById.get(node.id());
            // The topmost deletable tag of a branch is the one the cleanup preview names: deleting
            // it removes every deletable tag below it, so listing those as roots too would count
            // the same removal twice.
            String parentId = node.parentId();
            boolean root = deletable
                    && (parentId == null || !byId.containsKey(parentId) || !deletableById.get(parentId));
            statusList.add(new TagStatus(node.id(), node.name(),
                    buildPath(node, byId, visibleIds), parentId, deletable, root,
                    aggregate.documentCount(), deletable ? null : blockReason(aggregate)));
        }
        statusList.sort(Comparator.comparing(TagStatus::path, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(TagStatus::id));
        return statusList;
    }

    /**
     * Folds a tag's subtree into the facts the verdict needs. Memoised, and guarded against a
     * parent cycle: the update endpoint refuses to create one, but this walks rows read from the
     * database, and a maintenance sweep that hangs a request thread on corrupt data is a worse
     * outcome than one that reports the cycle as undeletable.
     */
    private static Aggregate aggregate(String id, Map<String, List<String>> childIds,
                                       Set<String> visibleIds, Set<String> writableIds,
                                       Map<String, Long> documentCounts, Set<String> usedTagIds,
                                       Set<String> ruleTagIds, Map<String, Aggregate> memo,
                                       Set<String> onPath) {
        Aggregate cached = memo.get(id);
        if (cached != null) {
            return cached;
        }
        if (!onPath.add(id)) {
            // Reached itself through its own parent chain: report the cycle rather than recursing
            // into it. Not memoised — the value depends on where the walk entered the cycle.
            return new Aggregate(0L, false, false, false, true);
        }

        long documentCount = documentCounts.getOrDefault(id, 0L);
        boolean used = usedTagIds.contains(id);
        boolean permitted = visibleIds.contains(id) && writableIds.contains(id);
        boolean ruleTag = ruleTagIds.contains(id);
        boolean cyclic = false;
        for (String childId : childIds.getOrDefault(id, List.of())) {
            Aggregate child = aggregate(childId, childIds, visibleIds, writableIds, documentCounts,
                    usedTagIds, ruleTagIds, memo, onPath);
            documentCount += child.documentCount();
            used |= child.used();
            permitted &= child.allPermitted();
            ruleTag |= child.anyRuleTag();
            cyclic |= child.cyclic();
        }
        onPath.remove(id);

        Aggregate aggregate = new Aggregate(documentCount, used, permitted, ruleTag, cyclic);
        memo.put(id, aggregate);
        return aggregate;
    }

    private static boolean isDeletable(Aggregate aggregate) {
        return !aggregate.used() && aggregate.documentCount() == 0 && aggregate.allPermitted()
                && !aggregate.anyRuleTag() && !aggregate.cyclic();
    }

    /**
     * The single reason the screen quotes.
     *
     * <p>Order matters. Active documents come first: it is both the common case and the one the
     * user can act on. {@link BlockReason#OTHER} comes NEXT, ahead of the trash — a subtree
     * holding a tag the caller cannot reach may well be the thing that is "used", and saying so
     * would disclose it; the generic answer is the safe one and costs nothing, because an admin
     * (who sees everything) never reaches that branch. Only then can a bare
     * {@link BlockReason#TRASH} mean what it says: everything here is visible, nothing active
     * carries it, and the trash is what is holding it.
     */
    private static BlockReason blockReason(Aggregate aggregate) {
        if (aggregate.documentCount() > 0) {
            return BlockReason.DOCUMENTS;
        }
        if (!aggregate.allPermitted() || aggregate.cyclic()) {
            return BlockReason.OTHER;
        }
        if (aggregate.used()) {
            return BlockReason.TRASH;
        }
        return BlockReason.RULE;
    }

    /**
     * The tag's name preceded by its VISIBLE ancestors. The chain stops at the first invisible
     * ancestor because that is where the tag list stops too — {@code TagResource#list} omits a
     * parent link the caller cannot read, so such a tag renders at root level — and because an
     * invisible ancestor's name is not the caller's to read.
     */
    private static String buildPath(TagNode node, Map<String, TagNode> byId, Set<String> visibleIds) {
        List<String> names = new ArrayList<>();
        names.add(node.name());
        Set<String> seen = new HashSet<>();
        seen.add(node.id());
        String parentId = node.parentId();
        while (parentId != null && visibleIds.contains(parentId) && seen.add(parentId)) {
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
     * The pre-delete re-check, as a decision over three freshly read facts.
     *
     * <p>Split out and pure so it can be exercised on its own. Its inputs are read from the
     * database immediately before the tag is soft-deleted, in the same transaction, which is what
     * closes the window between the verdict {@link #buildStatus} produced and the delete itself:
     * an assignment or a permission revocation landing in between would otherwise take a tag that
     * had just become used. It is deliberately coarser than {@code buildStatus} — it asks only
     * about THIS tag, because by the time the loop reaches it every descendant has already been
     * removed or kept.
     *
     * @param permitted Whether the caller may still WRITE the tag
     * @param documentReference Whether an active or restorable document still references it
     * @param ruleTarget Whether an alive auto-tagging rule still targets it
     * @return the reason it may not go, or null when it may
     */
    public static BlockReason recheckReason(boolean permitted, boolean documentReference, boolean ruleTarget) {
        if (!permitted) {
            return BlockReason.OTHER;
        }
        if (documentReference) {
            return BlockReason.DOCUMENTS;
        }
        if (ruleTarget) {
            return BlockReason.RULE;
        }
        return null;
    }

    /** Reads the three re-check facts for one tag and decides. */
    private static BlockReason recheck(String tagId, List<String> targetIdList) {
        return recheckReason(
                new AclDao().checkPermission(tagId, PermType.WRITE, targetIdList),
                new TagDao().hasDocumentReference(tagId),
                new TagMatchRuleDao().isTagReferenced(tagId));
    }

    /**
     * The maintenance status of every tag the caller can read.
     *
     * @param targetIdList Caller ACL target list
     * @return Status of every visible tag, ordered by path
     */
    public static List<TagStatus> status(List<String> targetIdList) {
        return statusOf(loadNodes(), targetIdList);
    }

    /** The maintenance status over an already-loaded node list (one tag query per request). */
    private static List<TagStatus> statusOf(List<TagNode> nodes, List<String> targetIdList) {
        List<String> tagIdList = new ArrayList<>(nodes.size());
        for (TagNode node : nodes) {
            tagIdList.add(node.id());
        }
        AclDao aclDao = new AclDao();
        TagDao tagDao = new TagDao();
        return buildStatus(nodes,
                aclDao.filterPermitted(tagIdList, PermType.READ, targetIdList),
                aclDao.filterPermitted(tagIdList, PermType.WRITE, targetIdList),
                tagDao.getTagDocumentCounts(targetIdList),
                tagDao.findTagIdsWithDocumentReference(tagIdList),
                new TagMatchRuleDao().findReferencedTagIds());
    }

    /**
     * Removes every fully-unused subtree the caller may delete, and reports exactly what went and
     * what was kept.
     *
     * <p>The set is recomputed here rather than taken from a preview the client sends back: a tag
     * that gained a document since the preview was rendered must survive the confirm, and the
     * report names what was really removed.
     *
     * @param targetIdList Caller ACL target list
     * @param userId Acting user ID, for the audit log
     * @return what went and what the re-check kept
     */
    public static Sweep deleteUnused(List<String> targetIdList, String userId) {
        List<TagStatus> deletable = new ArrayList<>();
        for (TagStatus status : status(targetIdList)) {
            if (status.deletable()) {
                deletable.add(status);
            }
        }
        return deleteAll(deletable, targetIdList, userId);
    }

    /**
     * Removes one fully-unused subtree, root included.
     *
     * <p>All or nothing, unlike the sweep: the caller pointed at ONE branch, so removing part of it
     * and silently keeping the rest would be a different operation from the one they asked for.
     * Every member is re-checked before anything is deleted, and a single failure refuses the whole
     * request with the branch untouched.
     *
     * @param rootId Tag ID at the root of the subtree
     * @param targetIdList Caller ACL target list
     * @param userId Acting user ID, for the audit log
     * @return The outcome, with the tags removed when it succeeded
     */
    public static DeleteResult deleteSubtree(String rootId, List<String> targetIdList, String userId) {
        List<TagNode> nodes = loadNodes();
        List<TagStatus> statusList = statusOf(nodes, targetIdList);
        TagStatus root = null;
        for (TagStatus status : statusList) {
            if (status.id().equals(rootId)) {
                root = status;
                break;
            }
        }
        // A tag the caller cannot READ is not reported at all, and a tag they may read but not
        // write is not theirs to delete: both answer "not found", exactly as the single-tag delete
        // already does, so neither discloses anything about a tag outside the caller's reach.
        if (root == null || !new AclDao().checkPermission(rootId, PermType.WRITE, targetIdList)) {
            return new DeleteResult(DeleteOutcome.NOT_FOUND, List.of());
        }
        if (!root.deletable()) {
            return new DeleteResult(outcomeOf(root.reason()), List.of());
        }

        // Every tag below a deletable root is itself deletable (its subtree is a subset), so the
        // subtree is exactly the deletable descendants — no second walk needed.
        Set<String> subtreeIds = descendantIds(rootId, nodes);
        List<TagStatus> doomed = new ArrayList<>();
        for (TagStatus status : statusList) {
            if (status.id().equals(rootId) || subtreeIds.contains(status.id())) {
                doomed.add(status);
            }
        }

        // deleteAll re-checks each tag immediately before its own delete and KEEPS one that changed
        // in between. For THIS endpoint a kept member is a refusal, never a partial success: the
        // caller asked for the branch to go whole, and answering DELETED with part of it already
        // soft-deleted would report a partial deletion as a clean success with nothing to tell them
        // to look. The order is leaf-up, so a reference landing on the ROOT is seen only after the
        // descendants are gone — which is exactly the case this guard exists for.
        //
        // The soft-deletes deleteAll already applied are undone by the REQUEST transaction, not by
        // this method. RequestContextFilter opens one entity manager and one transaction per request
        // and commits ONLY for a 2xx/3xx response (commitAndFinalize); every other status rolls it
        // back. The refusal below maps to a 400 in TagResource, so those soft-deletes go with it.
        // A caller outside a request transaction gets the refusal but keeps the partial deletion —
        // stated on the class doc, because it is a real constraint on reuse.
        return subtreeResultOf(deleteAll(doomed, targetIdList, userId));
    }

    /**
     * How a sweep is reported when it was a SINGLE branch the caller asked to remove whole: a kept
     * member is a refusal, never a partial success.
     *
     * <p>Its own function because that is the only seam it has. {@link #deleteSubtree} recomputes
     * the verdict itself, so a reference staged before the call is caught by that validation and
     * never reaches here; the blocked list can only be non-empty when the state changed DURING the
     * call, which a single-threaded test cannot stage without a hook in production code. The
     * decision is therefore lifted out and exercised directly, exactly as {@link #recheckReason}
     * is.</p>
     *
     * @param sweep What the delete loop actually did
     * @return DELETED with what went, or a refusal reporting nothing deleted
     */
    public static DeleteResult subtreeResultOf(Sweep sweep) {
        if (!sweep.blocked().isEmpty()) {
            // The root cause's own reason, so a branch held by a document still answers "in use"
            // rather than the opaque refusal; a permission change still answers opaquely, because
            // outcomeOf maps OTHER that way. A purely propagated block has no reason of its own.
            return new DeleteResult(sweep.blockReason() == null
                    ? DeleteOutcome.NOT_DELETABLE : outcomeOf(sweep.blockReason()), List.of());
        }
        return new DeleteResult(DeleteOutcome.DELETED, sweep.deleted());
    }

    /** Maps a block reason onto the outcome the REST layer answers with. */
    private static DeleteOutcome outcomeOf(BlockReason reason) {
        return switch (reason) {
            case DOCUMENTS, TRASH -> DeleteOutcome.IN_USE;
            case RULE -> DeleteOutcome.IN_RULE;
            case OTHER -> DeleteOutcome.NOT_DELETABLE;
        };
    }

    /**
     * Soft-deletes the given tags LEAF-UP, re-checking each one against freshly read state
     * immediately before its own delete.
     *
     * <p>Safe to call with a list validated earlier — that is the point. The verdict a caller
     * passes in is a snapshot; this re-reads the facts for each tag inside the same transaction
     * that removes it, so an assignment or a permission revocation that landed in between keeps
     * the tag instead of losing a fresh assignment. A kept tag does NOT abort the run: it is
     * returned in {@link Sweep#blocked} and the rest of the sweep proceeds.
     *
     * <p>Keeping a tag also keeps its ANCESTORS within the same run. {@link TagDao#delete} detaches
     * the children of the tag it removes, so deleting a parent while keeping its child would move
     * that child to the root of the tree — a structural change nobody asked for. Blocking
     * propagates upward instead, which is safe because the order is leaf-up.
     *
     * @param validated Tags a verdict has already cleared
     * @param targetIdList Caller ACL target list
     * @param userId Acting user ID, for the audit log
     * @return what went and what was kept
     */
    public static Sweep deleteAll(List<TagStatus> validated, List<String> targetIdList, String userId) {
        List<TagStatus> ordered = new ArrayList<>(validated);
        ordered.sort(Comparator.comparingInt((TagStatus status) -> depthOf(status.path())).reversed());

        Set<String> doomedIds = new HashSet<>();
        for (TagStatus status : ordered) {
            doomedIds.add(status.id());
        }

        TagDao tagDao = new TagDao();
        Set<String> blockedIds = new HashSet<>();
        List<TagStatus> deleted = new ArrayList<>();
        List<TagStatus> blocked = new ArrayList<>();
        BlockReason firstReason = null;
        for (TagStatus status : ordered) {
            BlockReason ownReason = blockedIds.contains(status.id())
                    ? null : recheck(status.id(), targetIdList);
            if (ownReason != null && firstReason == null) {
                firstReason = ownReason;
            }
            boolean keep = blockedIds.contains(status.id()) || ownReason != null;
            if (keep) {
                blocked.add(status);
                blockedIds.add(status.id());
                // Keep every ancestor still in this run, so nothing below it is re-parented away.
                String ancestorId = status.parentId();
                while (ancestorId != null && doomedIds.contains(ancestorId) && blockedIds.add(ancestorId)) {
                    ancestorId = parentOf(ancestorId, ordered);
                }
                continue;
            }
            tagDao.delete(status.id(), userId);
            deleted.add(status);
        }

        // Reported shallowest-first: the report reads like the tree the tags were removed from.
        Collections.reverse(deleted);
        Collections.reverse(blocked);
        return new Sweep(deleted, blocked, firstReason);
    }

    /** The parent of a tag within the run's own list, or null when it is not part of it. */
    private static String parentOf(String tagId, List<TagStatus> ordered) {
        for (TagStatus status : ordered) {
            if (status.id().equals(tagId)) {
                return status.parentId();
            }
        }
        return null;
    }

    private static int depthOf(String path) {
        int depth = 0;
        int index = path.indexOf(PATH_SEPARATOR);
        while (index >= 0) {
            depth++;
            index = path.indexOf(PATH_SEPARATOR, index + PATH_SEPARATOR.length());
        }
        return depth;
    }

    /** Every descendant of a tag, over the unscoped node list. */
    private static Set<String> descendantIds(String rootId, List<TagNode> nodes) {
        Map<String, List<String>> childIds = new HashMap<>();
        for (TagNode node : nodes) {
            if (node.parentId() != null) {
                childIds.computeIfAbsent(node.parentId(), key -> new ArrayList<>()).add(node.id());
            }
        }
        Set<String> ids = new HashSet<>();
        List<String> queue = new ArrayList<>(childIds.getOrDefault(rootId, List.of()));
        while (!queue.isEmpty()) {
            String id = queue.remove(queue.size() - 1);
            if (ids.add(id)) {
                queue.addAll(childIds.getOrDefault(id, List.of()));
            }
        }
        return ids;
    }

    /**
     * Every alive tag in the instance, unscoped by ACL. A null criteria target list skips the ACL
     * join in {@link TagDao#findByCriteria} — which is the point: the maintenance guard needs the
     * FULL parent graph, invisible tags included, or a hidden used child looks like an empty branch.
     */
    private static List<TagNode> loadNodes() {
        List<TagNode> nodes = new ArrayList<>();
        for (TagDto tagDto : new TagDao().findByCriteria(new TagCriteria(), null)) {
            nodes.add(new TagNode(tagDto.getId(), tagDto.getName(), tagDto.getParentId()));
        }
        return nodes;
    }
}
