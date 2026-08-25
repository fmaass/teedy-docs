package com.sismics.docs.core.util;

import com.sismics.docs.core.dao.SavedFilterDao;
import com.sismics.docs.core.dao.SavedFilterExistsException;
import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.dao.criteria.TagCriteria;
import com.sismics.docs.core.dao.dto.SavedFilterDto;
import com.sismics.docs.core.dao.dto.TagDto;
import com.sismics.docs.core.model.jpa.SavedFilter;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Saved filter operations that span more than one DAO call.
 *
 * <p>The REST layer must not reach into {@code com.sismics.docs.core.dao} for new work — the
 * frozen architecture rule ratchets that legacy dependency DOWN, never up — so the saved-filter
 * update operation lives here: the resource keeps validation, authentication and the HTTP status
 * mapping, and this util owns the whole persistence conversation behind one call.</p>
 */
public class SavedFilterUtil {
    /**
     * The stored query's TAG-BEARING dimensions — the only two whose value is a comma-joined list
     * of tag IDs (#51).
     *
     * <p>This is the server-side READER of a payload the frontend owns: the dimension names are
     * {@code SavedFilterResource.ALLOWED_KEYS}, which in turn mirrors {@code FILTER_KEYS} in
     * {@code utils/savedFilterQuery.ts}, and the comma-joined-ID-set shape is that module's
     * {@code SET_VALUED_KEYS}. Nothing here SERIALISES a filter — the query string a published
     * filter carries is the one its owner captured, byte for byte. This only reads the tag ids out
     * of it, which has to happen on the server because it is an ACL judgement.</p>
     */
    private static final Set<String> TAG_BEARING_KEYS = Set.of("tags", "exclude");

    /**
     * A published saved filter as offered to ONE viewer: the filter, plus how many of the tags it
     * names that viewer cannot read (#51).
     *
     * <p>The count is the whole disclosure — never which tags, never their names. Zero means the
     * viewer may apply it; anything else means the filter is offered but unapplicable, and its
     * {@code filter}'s query has been withheld (see {@link #listPublished}).</p>
     */
    public record PublishedFilter(SavedFilterDto filter, int hiddenTagCount) {
    }

    private SavedFilterUtil() {
        // Utility class.
    }

    /**
     * Applies a name/query update to a saved filter owned by the given user, in the order the
     * contract requires: ownership FIRST (so a foreign id is answered as not-found and can never
     * be met with a name-collision error about the caller's own filter set), then the
     * case-insensitive duplicate precheck EXCLUDING the filter being updated (a no-op save and a
     * case-only self-rename must not collide with themselves), then the write.
     *
     * <p>The write itself stays in {@link SavedFilterDao#update}, which owns the entity-manager
     * boundary: it takes the manager first, loads through it, applies the mutation and flushes
     * inside the try/catch that translates the {@code (user, name)} unique violation. Splitting
     * that boundary across the util would re-open the raw-constraint-violation (500) hazard it
     * exists to close, so it is deliberately NOT moved here.</p>
     *
     * @param id Saved filter ID
     * @param userId Owner user ID (for authorization)
     * @param name New filter name
     * @param query New canonical URL query string
     * @return the updated saved filter, or null if no filter with this id is owned by the user
     * @throws SavedFilterExistsException if the name is already used by another filter of the user
     */
    public static SavedFilter update(String id, String userId, String name, String query)
            throws SavedFilterExistsException {
        SavedFilterDao dao = new SavedFilterDao();

        if (dao.getByIdAndUser(id, userId) == null) {
            return null;
        }

        for (SavedFilter existing : dao.getByUserId(userId)) {
            if (!existing.getId().equals(id) && existing.getName().equalsIgnoreCase(name)) {
                throw new SavedFilterExistsException();
            }
        }

        // The DB unique index (exact-case) remains the concurrency backstop behind this
        // precheck; the DAO translates it into the same exception.
        return dao.update(id, userId, name, query);
    }

    /**
     * Publishes or withdraws a saved filter OWNED by the given user (#51).
     *
     * @param id Saved filter ID
     * @param userId Owner user ID (for authorization)
     * @param published true to publish, false to withdraw
     * @return the updated saved filter, or null if no filter with this id is owned by the user
     */
    public static SavedFilter setPublished(String id, String userId, boolean published) {
        return new SavedFilterDao().setPublished(id, userId, published);
    }

    /**
     * Returns a saved filter by id whoever owns it — the lookup the administrator's withdrawal
     * path needs in order to decide between "no such filter" and "not yours".
     *
     * @param id Saved filter ID
     * @return Saved filter or null
     */
    public static SavedFilter getById(String id) {
        return new SavedFilterDao().getById(id);
    }

    /**
     * Withdraws a publication regardless of ownership — the administrator's management path (#51).
     *
     * @param id Saved filter ID
     * @return true if a published filter was withdrawn
     */
    public static boolean unpublish(String id) {
        return new SavedFilterDao().unpublish(id);
    }

    /**
     * The published filters ANOTHER user is offered, each judged against that viewer's tag ACLs
     * (#51).
     *
     * <p>The viewer's own filters are excluded: they are already the whole of their own list, and
     * showing them a second time under "shared by others" would be a duplicate, not a disclosure.</p>
     *
     * <p>The tag-visibility rule, settled on the issue thread: a published filter naming one or
     * more tags the viewer cannot READ is offered but NOT applicable. It is deliberately neither
     * hidden (the viewer would never learn why a colleague's filter is missing) nor silently
     * stripped of the offending tags (the frontend's tag hydration already drops unknown ids, so an
     * applied filter would quietly select a WIDER set than its author meant). So it is returned,
     * counted, and stripped of its CRITERIA instead: {@code query} comes back empty, because a
     * viewer who cannot apply a filter has no use for the tag ids inside it. What they are told is
     * a NUMBER — never a tag name, never a tag id.</p>
     *
     * <p>None of this is a security boundary; it is honesty about what a control will do. The
     * boundary is the search itself, which is ACL-scoped server-side: {@code DocumentResource.list}
     * resolves tag tokens ONLY against the caller's readable tags and passes the caller's ACL
     * target list into the criteria, so even a hand-crafted request naming an invisible tag cannot
     * widen anybody's result set.</p>
     *
     * @param viewerUserId The user reading the list (their own filters are excluded)
     * @param targetIdList The viewer's ACL target list (null/admin targets = unscoped)
     * @return Published filters offered to this viewer, ordered by name
     */
    public static List<PublishedFilter> listPublished(String viewerUserId, List<String> targetIdList) {
        List<SavedFilterDto> published = new ArrayList<>();
        for (SavedFilterDto dto : new SavedFilterDao().getPublished()) {
            if (!dto.getUserId().equals(viewerUserId)) {
                published.add(dto);
            }
        }
        if (published.isEmpty()) {
            return List.of();
        }

        // Parse each filter's tag dimensions once, then resolve the viewer's readable tags ONCE for
        // the whole list — and only when at least one published filter actually names a tag, so the
        // common case (free-text filters) costs no extra query on a list that loads with every
        // document view.
        List<Set<String>> referencedPerFilter = new ArrayList<>(published.size());
        boolean anyTagReferenced = false;
        for (SavedFilterDto dto : published) {
            Set<String> referenced = referencedTagIds(dto.getQuery());
            referencedPerFilter.add(referenced);
            anyTagReferenced |= !referenced.isEmpty();
        }
        Set<String> visibleTagIds = anyTagReferenced ? readableTagIds(targetIdList) : Set.of();

        List<PublishedFilter> result = new ArrayList<>(published.size());
        for (int i = 0; i < published.size(); i++) {
            SavedFilterDto dto = published.get(i);
            int hidden = 0;
            for (String tagId : referencedPerFilter.get(i)) {
                if (!visibleTagIds.contains(tagId)) {
                    hidden++;
                }
            }
            if (hidden > 0) {
                // Withheld, not stripped: the viewer is told the filter exists and that they cannot
                // apply it, and receives none of the criteria that would tell them which tags it is
                // built on. Blanking the query is also what keeps it out of the frontend's
                // applied-filter comparison by construction.
                dto.setQuery("");
            }
            result.add(new PublishedFilter(dto, hidden));
        }
        return result;
    }

    /**
     * The tag IDs a stored filter query names, across both tag-bearing dimensions.
     *
     * <p>The payload is FORM-URL-ENCODED, because the frontend builds it with
     * {@code URLSearchParams.toString()} — which percent-encodes the separator, so two selected
     * tags are stored as {@code tags=a%2Cb}, not {@code tags=a,b}. Each value is therefore decoded
     * BEFORE it is split on the comma; splitting the raw text would read that as one nonexistent
     * tag id and report a filter with two perfectly visible tags as unapplicable. Decoding with
     * {@link URLDecoder} (which also maps {@code +} to a space) is the exact inverse of what wrote
     * it, and mirrors the decode the resource's own {@code validateQueryString} performs.</p>
     *
     * <p>Deliberately lenient about shape: a malformed escape, a valueless key or a repeated
     * dimension yields whatever ids can be read rather than an exception. This is a READ path over
     * data the write path already validated, and it must not be able to fail a whole list because
     * one legacy row is odd. A repeated key contributes ALL of its occurrences — the safe
     * direction, since an unread id could only ever under-count what the viewer cannot see.</p>
     *
     * @param query Stored canonical URL query string
     * @return The referenced tag IDs, de-duplicated, in first-seen order
     */
    private static Set<String> referencedTagIds(String query) {
        if (query == null || query.isEmpty()) {
            return Set.of();
        }
        Set<String> tagIds = new LinkedHashSet<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = decode(pair.substring(0, eq));
            if (key == null || !TAG_BEARING_KEYS.contains(key)) {
                continue;
            }
            String value = decode(pair.substring(eq + 1));
            if (value == null) {
                continue;
            }
            for (String tagId : value.split(",")) {
                String trimmed = tagId.trim();
                if (!trimmed.isEmpty()) {
                    tagIds.add(trimmed);
                }
            }
        }
        return tagIds;
    }

    /** Form-url-decodes one component, or null when it is not decodable. */
    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The IDs of every tag the viewer may READ. Deliberately the same query the tag tree and the
     * document search use ({@code TagDao.findByCriteria} with the caller's ACL target list), so
     * "the viewer can see this tag" means exactly what it means everywhere else — including that a
     * DELETED tag is not visible to anyone, and that an administrator sees all of them.
     */
    private static Set<String> readableTagIds(List<String> targetIdList) {
        Set<String> ids = new HashSet<>();
        for (TagDto tagDto : new TagDao().findByCriteria(new TagCriteria().setTargetIdList(targetIdList), null)) {
            ids.add(tagDto.getId());
        }
        return ids;
    }
}
