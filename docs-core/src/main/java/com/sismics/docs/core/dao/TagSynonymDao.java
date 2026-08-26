package com.sismics.docs.core.dao;

import com.sismics.docs.core.model.jpa.TagSynonym;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Tag synonym DAO (#280).
 *
 * <p>Everything here is expressed over WHOLE SETS rather than single rows, because that is what
 * the surface above it does: the tag form edits a list of chips and saves the list. A per-row
 * add/remove API would have to be reassembled into a replace by every caller, and a save that is
 * several requests can leave a tag half-edited when one of them is refused.</p>
 *
 * <p>Names are compared with {@link String#CASE_INSENSITIVE_ORDER}, never by folding through
 * {@code toLowerCase()}. The fold is locale-dependent — on a Turkish host it maps ASCII {@code I}
 * to the dotless {@code ı} — and this class must reach the same verdict as
 * {@code TagUtil.findByName}, which compares per character for exactly that reason (#266).</p>
 *
 * @author fmaass
 */
public class TagSynonymDao {
    /**
     * The order synonyms are handed out in: case-insensitive, with a case-sensitive tiebreak so
     * two spellings that differ only in case never swap places between reads.
     *
     * <p>Sorted in JAVA rather than with an {@code order by}: the database's collation is a
     * deployment property (H2's default differs from a PostgreSQL cluster's {@code lc_collate}),
     * and a tag form that renders its chips in one order on one engine and another order on the
     * next would make every screenshot and every equality assertion engine-dependent.</p>
     */
    private static final Comparator<String> NAME_ORDER =
            Comparator.comparing(name -> name, String.CASE_INSENSITIVE_ORDER);

    /**
     * The live synonyms of one tag, name-ordered.
     *
     * @param tagId Tag ID
     * @return its synonym names (never null, empty when it has none)
     */
    public List<String> findByTagId(String tagId) {
        List<String> names = findByTagIds(List.of(tagId)).get(tagId);
        return names == null ? List.of() : names;
    }

    /**
     * The live synonyms of several tags at once, grouped per tag and name-ordered.
     *
     * <p>ONE query for a whole tag list: every tag read carries its synonyms (see
     * {@code TagDao.findByCriteria}), and a per-tag read would make listing the tags of an
     * instance one query per tag. A tag with no synonym is simply ABSENT from the map rather
     * than mapped to an empty list, mirroring {@code TagDao.findTagIdsByDocumentIds}.</p>
     *
     * @param tagIds Tag IDs to read
     * @return tag ID to its synonym names (never null)
     */
    @SuppressWarnings("unchecked")
    public Map<String, List<String>> findByTagIds(Collection<String> tagIds) {
        Map<String, List<String>> byTag = new HashMap<>();
        if (tagIds == null || tagIds.isEmpty()) {
            // An empty `in ()` bind is not portable, and there is nothing to ask about anyway.
            return byTag;
        }
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select s.tagId, s.name from TagSynonym s"
                + " where s.tagId in :tagIds and s.deleteDate is null");
        q.setParameter("tagIds", tagIds);
        for (Object row : q.getResultList()) {
            Object[] columns = (Object[]) row;
            byTag.computeIfAbsent((String) columns[0], key -> new ArrayList<>()).add((String) columns[1]);
        }
        for (List<String> names : byTag.values()) {
            names.sort(NAME_ORDER);
        }
        return byTag;
    }

    /**
     * Replace the WHOLE live synonym set of one tag with the given names.
     *
     * <p>The submitted names are deduplicated ignoring case — resolution is case-insensitive, so
     * two spellings of one word are one synonym and storing both would create a row that can
     * never be told from its twin. The FIRST spelling submitted wins.</p>
     *
     * <p>A name that is already there keeps its ROW: it was not edited, so it keeps the creation
     * date it had. A name whose only change is its CASE keeps the row too but takes the new
     * spelling, because that IS the edit the user made — re-inserting it would silently reset the
     * date, and ignoring it would leave the form showing something the user did not type. Only a
     * name that is genuinely gone is soft-deleted, and only a genuinely new one is inserted.</p>
     *
     * <p>The caller is responsible for VALIDATING the names first (the REST layer runs them
     * through {@code ValidationUtil.validateTagName}, the same rule tag names take) and for the
     * collision rule across tags, which is a question about what the CALLER can see and therefore
     * cannot be answered here.</p>
     *
     * @param tagId Tag ID
     * @param names The names the tag should end up with, in the caller's preferred spelling
     * @return the stored synonym names, name-ordered
     */
    public List<String> replaceForTag(String tagId, List<String> names) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        // Deduplicate ignoring case; the FIRST spelling submitted wins, because the second is
        // the same word and the caller typed the first one first.
        Map<String, String> wantedByKey = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (names != null) {
            for (String name : names) {
                if (name == null || name.isEmpty() || wantedByKey.containsKey(name)) {
                    continue;
                }
                wantedByKey.put(name, name);
            }
        }

        TypedQuery<TagSynonym> q = em.createQuery("select s from TagSynonym s"
                + " where s.tagId = :tagId and s.deleteDate is null", TagSynonym.class);
        q.setParameter("tagId", tagId);
        List<TagSynonym> live = q.getResultList();

        Date dateNow = new Date();
        Map<String, TagSynonym> liveByKey = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (TagSynonym synonym : live) {
            if (wantedByKey.containsKey(synonym.getName())) {
                liveByKey.put(synonym.getName(), synonym);
                // The same word, differently spelled: keep the row, take the new spelling.
                String spelling = wantedByKey.get(synonym.getName());
                if (!spelling.equals(synonym.getName())) {
                    synonym.setName(spelling);
                }
            } else {
                synonym.setDeleteDate(dateNow);
            }
        }

        for (String name : wantedByKey.values()) {
            if (liveByKey.containsKey(name)) {
                continue;
            }
            TagSynonym synonym = new TagSynonym();
            synonym.setId(UUID.randomUUID().toString());
            synonym.setTagId(tagId);
            synonym.setName(name);
            synonym.setCreateDate(dateNow);
            em.persist(synonym);
        }

        List<String> stored = new ArrayList<>(wantedByKey.values());
        stored.sort(NAME_ORDER);
        return stored;
    }

    /**
     * Soft-delete every live synonym of a tag.
     *
     * <p>Called from the tag deletion paths: a name that still resolved to a deleted tag would be
     * a search term with no screen able to repair it, and it would go on blocking the collision
     * rule for a name nothing can reach any more.</p>
     *
     * @param tagId Tag ID
     * @param dateNow The deletion timestamp — the caller's, so the synonyms carry the same
     *                instant as the tag and its links
     * @return the number of synonyms removed
     */
    public int deleteByTagId(String tagId, Date dateNow) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("update TagSynonym s set s.deleteDate = :dateNow"
                + " where s.tagId = :tagId and s.deleteDate is null");
        q.setParameter("dateNow", dateNow);
        q.setParameter("tagId", tagId);
        return q.executeUpdate();
    }
}
