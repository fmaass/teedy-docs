package com.sismics.docs.rest.util;

import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.dao.TagSynonymDao;
import com.sismics.docs.core.dao.criteria.TagCriteria;
import com.sismics.docs.core.dao.dto.TagDto;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.util.ValidationUtil;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The rules a tag write has to satisfy once tags can carry synonyms (#280).
 *
 * <p>Two things live here rather than in {@code TagResource}: the NAME RULE, which is the tag
 * name rule and nothing else, and the COLLISION RULE, which is the only genuinely new judgement
 * this feature adds. It is also the class that reads and writes synonym rows, which keeps
 * {@code rest.resource} from growing a new dependency on {@code core.dao} — the layering ratchet
 * this project freezes (DocumentSliceArchitectureTest) only ever shrinks, so new DAO work belongs
 * on this side of it.</p>
 *
 * <h2>The collision rule, and why it is scoped to the caller</h2>
 *
 * <p>A synonym must resolve to exactly one tag, so a name may be a tag's name or a tag's synonym,
 * never both. That is checked in BOTH directions at save time — a synonym may not take a name
 * already in use, and a tag may not be created or renamed onto a name already in use as a synonym
 * — with an error that NAMES the tag it collides with, because a silent precedence rule would
 * make one of the two names stop working with nothing on screen to say so (maintainer design on
 * issue #280, approved by the reporter).</p>
 *
 * <p>The check is made over ONE scope — <b>the tags the caller can READ, plus the tag being
 * written</b> ({@link #collisionScope}) — and that scope is load-bearing rather than a
 * simplification. Both halves of it are:</p>
 *
 * <ul>
 *   <li><b>Nothing outside it may be consulted.</b> An error that named a tag the caller cannot
 *       see would confirm that it exists, what it is called, and that somebody uses that word;
 *       worse, a mere REFUSAL would answer "is this word taken somewhere I cannot look" even
 *       without naming anything — an enumeration oracle from an endpoint any account may call.
 *       So a name colliding only with an invisible tag's name or synonym is ACCEPTED, with the
 *       same status and body as a name colliding with nothing, and the two names then coexist.
 *       Nothing downstream is ambiguous, because resolution is scoped the same way: each caller
 *       resolves the word over their own readable tags, and neither sees the other's. Should the
 *       two ever meet in one caller's list — a READ grant made later — {@code
 *       TagUtil.findByName} answers with both tags rather than picking one (see its tests),
 *       which loses no document.</li>
 *   <li><b>The tag being written is always inside it</b>, even when the caller cannot READ it.
 *       READ and WRITE are independent ACL rows, so edit-without-view is a reachable state, and
 *       a rule that dropped the tag out of its own scope would stop applying to it: renaming it
 *       onto a synonym it keeps would be accepted, which is the swap flow this slice defers.</li>
 * </ul>
 *
 * @author fmaass
 */
public final class TagSynonymUtil {
    /**
     * The same length bound a tag name has. A synonym is a name for the tag, stored in a column
     * the migration deliberately declares as wide as {@code TAG_NAME_C}.
     */
    private static final int MAX_LENGTH = 36;

    private TagSynonymUtil() {
    }

    /**
     * Whether the caller supplied the synonym field at all.
     *
     * <p>The distinction is the whole reason synonyms can ride on the existing tag write without
     * breaking API clients that predate them: an ABSENT field leaves the tag's synonyms alone,
     * so a client that only knows about name/colour/parent cannot wipe them by saving a colour.
     * A field sent with a single EMPTY value is the explicit "no synonyms" the tag form sends
     * when its last chip is removed. JAX-RS gives an absent repeated form parameter an empty
     * list and {@code synonyms=} a one-element list holding {@code ""}, which is exactly the two
     * cases apart.</p>
     *
     * @param synonyms The raw repeated form parameter
     * @return true when the caller is setting the synonym list
     */
    public static boolean isProvided(List<String> synonyms) {
        return synonyms != null && !synonyms.isEmpty();
    }

    /**
     * Put every submitted synonym through the TAG NAME rule and return what would be stored.
     *
     * <p>The rule is not re-derived here: {@link ValidationUtil#validateTagName} (and through it
     * {@code TagNameNormalizer}, #305) is the single place that decides what a tag name may
     * contain, and a synonym that could carry a character its tag cannot would be a name the user
     * can search for but never see — the very defect #305 fixed. So invisible format characters
     * are stripped, the edges are trimmed, interior whitespace is refused, and the colon and
     * asterisk the search grammar owns are refused, all with the same {@code IllegalTagName} a
     * tag name produces. The length bound is measured on the NORMALIZED name, as it is for a tag
     * name, so a 36-character synonym carrying a zero-width character is 36 characters.</p>
     *
     * <p>Blank entries are dropped rather than refused: they are how the form says "no synonyms"
     * and how a repeated parameter carries an empty slot, not something a user typed. Two
     * spellings of one word are likewise reduced to the first rather than refused — resolution is
     * case-insensitive, so they ARE one synonym, and reporting a collision between a name and
     * itself would be an error message about nothing. The reduction happens here as well as in
     * the DAO so that what the collision rule judges is exactly what will be stored.</p>
     *
     * @param synonyms The raw repeated form parameter
     * @return the normalized names, in the order submitted, blanks and case-duplicates removed
     * @throws ClientException if one of them is not a usable name
     */
    public static List<String> validateNames(List<String> synonyms) {
        List<String> names = new ArrayList<>();
        if (synonyms == null) {
            return names;
        }
        // CASE_INSENSITIVE_ORDER, not toLowerCase(): the fold is locale-dependent (see the note
        // in checkCollisions), while this comparator folds per character.
        TreeSet<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String raw : synonyms) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String name = ValidationUtil.validateTagName(raw);
            name = ValidationUtil.validateLength(name, "synonym", 1, MAX_LENGTH, false);
            if (seen.add(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Refuse a tag write whose name or synonyms collide with something the caller can already
     * see, and say what it collides with.
     *
     * <p>Both directions are checked against ONE list — {@link #collisionScope} — so the answer
     * cannot be self-inconsistent and no lookup can reach a tag the rule may not consider. The
     * tag being written is compared against the state this write LEAVES IT IN rather than
     * against its stored row: its own name is not a collision with itself, re-saving an
     * unchanged form must not start failing, and a name that only collides with something the
     * same request removes is not a collision at all.</p>
     *
     * @param name The tag's name after normalization, or null/empty when the write does not set one
     * @param synonyms The tag's synonyms after normalization, or null when the write does not set them
     * @param tagId The tag being updated, or null when it is being created
     * @param targetIdList The caller's ACL target list — the scope of the whole check
     * @throws ClientException naming the conflicting tag
     */
    public static void checkCollisions(String name, List<String> synonyms, String tagId,
                                       List<String> targetIdList) {
        List<TagDto> scope = collisionScope(tagId, targetIdList);


        // FIRST, because it is the most specific reading of the request and the one that must
        // answer the same way on a create and on an update: a tag's own name is not available as
        // its own synonym. The two would be indistinguishable to every search, and removing the
        // "synonym" later would look like it had done nothing.
        if (synonyms != null && name != null && !name.isEmpty()) {
            for (String submitted : synonyms) {
                if (name.equalsIgnoreCase(submitted)) {
                    throw new ClientException("SynonymInUse", MessageFormat.format(
                            "\"{0}\" cannot be a synonym: it is already the name of the tag \"{1}\"",
                            submitted, name));
                }
            }
        }

        if (name != null && !name.isEmpty()) {
            for (TagDto tagDto : scope) {
                // The tag being written is compared against the synonyms this write LEAVES IT
                // WITH, not the ones it currently has. Both halves matter: renaming a tag onto
                // its own synonym without touching the chips is the swap flow (out of slice for
                // #280), so it has to be refused rather than quietly produce a tag whose name is
                // also its synonym; but renaming onto a synonym the SAME request removes is two
                // ordinary edits at once, and refusing that would be a collision with a name
                // that will not exist by the time the transaction commits.
                List<String> tagSynonyms = tagDto.getId().equals(tagId) && synonyms != null
                        ? synonyms
                        : tagDto.getSynonyms();
                for (String synonym : tagSynonyms) {
                    if (synonym.equalsIgnoreCase(name)) {
                        // Turning an existing tag INTO a synonym of another (and the reverse) is
                        // the swap/merge flow, which #280 deliberately leaves to a later ticket —
                        // so the honest answer here is a refusal that says where the name went.
                        throw new ClientException("TagNameIsSynonym", MessageFormat.format(
                                "The name \"{0}\" is already a synonym of the tag \"{1}\"",
                                name, tagDto.getName()));
                    }
                }
            }
        }

        if (synonyms == null) {
            return;
        }
        // Compared per character (equalsIgnoreCase), never through toLowerCase(): the fold is
        // locale-dependent and on a Turkish host would let "Invoice" and "ınvoice" past each
        // other — the same reasoning TagUtil.findByName is written on (#266). The verdict here
        // and the verdict there have to agree, or a save would accept a name that then resolves
        // to two tags.
        for (String submitted : synonyms) {
            for (TagDto tagDto : scope) {
                if (tagDto.getId().equals(tagId)) {
                    // The tag's OWN stored state is skipped: its name is judged above, against
                    // the name this write is setting, and its own synonyms are the ones being
                    // replaced — so re-saving an unchanged form cannot start failing.
                    continue;
                }
                if (tagDto.getName().equalsIgnoreCase(submitted)) {
                    throw new ClientException("SynonymInUse", MessageFormat.format(
                            "\"{0}\" cannot be a synonym: it is already the name of the tag \"{1}\"",
                            submitted, tagDto.getName()));
                }
                for (String synonym : tagDto.getSynonyms()) {
                    if (synonym.equalsIgnoreCase(submitted)) {
                        throw new ClientException("SynonymInUse", MessageFormat.format(
                                "\"{0}\" is already a synonym of the tag \"{1}\"",
                                submitted, tagDto.getName()));
                    }
                }
            }
        }
    }

    /**
     * The tags the collision rule may look at for a write on {@code tagId} by this caller:
     *
     * <pre>    (the tags the caller can READ)  ∪  {the tag being written}</pre>
     *
     * <p>and nothing else. Every lookup the rule performs — name to tag, synonym to tag, and the
     * written tag's own synonyms — runs over this ONE list, so there is no second query that
     * could reach past it.</p>
     *
     * <p><b>Why the union, and not just the readable set.</b> READ and WRITE are independent ACL
     * rows ({@code AclResource.add} creates exactly the one permission it is given), so a caller
     * holding WRITE on a tag it cannot READ is a state the API can be put into — and the tag
     * editor can grant edit alone. Scoping to the readable set only would drop the tag being
     * written out of its OWN rule: renaming it onto a synonym it keeps would then be accepted,
     * which is exactly the swap flow #280 defers. The caller is entitled to that tag's name and
     * synonyms — the WRITE was already checked before this runs — so it belongs in the scope,
     * and it is read BY ID rather than through the ACL-scoped query for that reason.</p>
     *
     * <p><b>Why nothing else.</b> A tag outside this scope must be invisible to the rule, not
     * merely unnamed by its error. If an unreadable tag could make a write fail, the STATUS of
     * the response would answer "is this word in use somewhere I cannot look" — an enumeration
     * oracle over other people's tag names, from an endpoint any account may call. So a name
     * matching only such a tag is accepted, with the same status and the same body as a name
     * matching nothing at all, and the code path it takes is the same one too.</p>
     *
     * <p>The by-id read runs only when the tag is absent from the readable set, and that branch
     * turns on the CALLER'S OWN permissions — never on whether any other tag matches — so it
     * cannot distinguish anything about tags the caller may not see.</p>
     *
     * @param tagId The tag being written, or null when it is being created
     * @param targetIdList The caller's ACL target list
     * @return the tags the rule may consult
     */
    private static List<TagDto> collisionScope(String tagId, List<String> targetIdList) {
        TagDao tagDao = new TagDao();
        List<TagDto> readable = tagDao.findByCriteria(
                new TagCriteria().setTargetIdList(targetIdList), null);
        if (tagId == null) {
            return readable;
        }
        for (TagDto tagDto : readable) {
            if (tagDto.getId().equals(tagId)) {
                return readable;
            }
        }
        List<TagDto> scope = new ArrayList<>(readable);
        // Unscoped BY ID, deliberately: this reads exactly the tag the caller already holds
        // WRITE on and nothing else, so it widens the scope by that tag alone. An empty result
        // (the tag was deleted concurrently) simply leaves the scope as the readable set.
        scope.addAll(tagDao.findByCriteria(new TagCriteria().setId(tagId), null));
        return scope;
    }

    /**
     * Store the tag's synonyms, replacing whatever it had.
     *
     * <p>Call it only after {@link #checkCollisions}: this writes, it does not judge.</p>
     *
     * @param tagId Tag ID
     * @param names The normalized names the tag should end up with
     * @return the stored names, name-ordered
     */
    public static List<String> store(String tagId, List<String> names) {
        return new TagSynonymDao().replaceForTag(tagId, names);
    }
}
