package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.criteria.TagCriteria;
import com.sismics.docs.core.dao.dto.TagDto;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Unit tests for tag synonym storage (#280).
 *
 * <p>A synonym is a second name that resolves to one tag, so the two properties that matter at
 * this layer are that the set is REPLACED as a whole (the tag form edits a list of chips, not
 * individual rows) and that a synonym never outlives its tag — a name still resolving to a tag
 * that has been deleted is exactly the state the collision rule above it cannot repair.</p>
 *
 * @author fmaass
 */
public class TestTagSynonymDao extends BaseTransactionalTest {
    private String createTag(User user, String name) {
        Tag tag = new Tag();
        tag.setName(name);
        tag.setColor("#3399cc");
        tag.setUserId(user.getId());
        return new TagDao().create(tag, user.getId());
    }

    private void flush() {
        ThreadLocalContext.get().getEntityManager().flush();
    }

    /** Rows for one tag, whatever their state — the soft-delete assertions read this. */
    private long countRows(String tagId, boolean deletedOnly) {
        Query q = ThreadLocalContext.get().getEntityManager().createQuery(
                "select count(s) from TagSynonym s where s.tagId = :tagId and s.deleteDate is "
                        + (deletedOnly ? "not null" : "null"));
        q.setParameter("tagId", tagId);
        return (Long) q.getSingleResult();
    }

    /** The stored set comes back name-ordered, so a form renders its chips the same way twice. */
    @Test
    public void testReplaceStoresTheWholeSetNameOrdered() throws Exception {
        User user = createUser("synonym_store");
        String tagId = createTag(user, "Insurance");

        new TagSynonymDao().replaceForTag(tagId, List.of("Versicherung", "Assurance"));
        flush();

        Assertions.assertEquals(List.of("Assurance", "Versicherung"),
                new TagSynonymDao().findByTagId(tagId));
    }

    /**
     * A replace is a replace: a name that is gone from the submitted list stops resolving, a new
     * one starts, and a name that survived keeps the ROW it already had — re-inserting it would
     * throw away its creation date for an edit that did not touch it.
     */
    @Test
    public void testReplaceRemovesAddsAndKeepsTheSurvivingRow() throws Exception {
        User user = createUser("synonym_replace");
        String tagId = createTag(user, "Insurance");
        TagSynonymDao dao = new TagSynonymDao();
        dao.replaceForTag(tagId, List.of("Versicherung", "Assurance"));
        flush();
        String survivorId = (String) ThreadLocalContext.get().getEntityManager()
                .createQuery("select s.id from TagSynonym s where s.tagId = :tagId"
                        + " and s.name = 'Versicherung' and s.deleteDate is null")
                .setParameter("tagId", tagId).getSingleResult();

        dao.replaceForTag(tagId, List.of("Versicherung", "Seguro"));
        flush();

        Assertions.assertEquals(List.of("Seguro", "Versicherung"), dao.findByTagId(tagId));
        Assertions.assertEquals(survivorId, ThreadLocalContext.get().getEntityManager()
                        .createQuery("select s.id from TagSynonym s where s.tagId = :tagId"
                                + " and s.name = 'Versicherung' and s.deleteDate is null")
                        .setParameter("tagId", tagId).getSingleResult(),
                "an untouched synonym must keep its row rather than being re-created");
        Assertions.assertEquals(1L, countRows(tagId, true),
                "the removed synonym is soft-deleted, not erased");
    }

    /**
     * Two spellings of one word are one synonym. Resolution is case-insensitive, so storing both
     * would create a row that can never be told from its twin — and the collision rule above this
     * layer would then have to answer for a duplicate it did not create.
     */
    @Test
    public void testReplaceDedupesIgnoringCase() throws Exception {
        User user = createUser("synonym_dedupe");
        String tagId = createTag(user, "Insurance");

        new TagSynonymDao().replaceForTag(tagId, List.of("Versicherung", "versicherung", "VERSICHERUNG"));
        flush();

        Assertions.assertEquals(List.of("Versicherung"), new TagSynonymDao().findByTagId(tagId),
                "the first spelling wins and its twins are dropped");
    }

    /** Deleting the tag takes its synonyms with it — soft, like everything else on a tag. */
    @Test
    public void testDeletingTheTagSoftDeletesItsSynonyms() throws Exception {
        User user = createUser("synonym_tagdelete");
        String tagId = createTag(user, "Insurance");
        new TagSynonymDao().replaceForTag(tagId, List.of("Versicherung"));
        flush();

        new TagDao().delete(tagId, user.getId());
        flush();

        Assertions.assertTrue(new TagSynonymDao().findByTagId(tagId).isEmpty(),
                "a deleted tag's synonym must stop resolving");
        Assertions.assertEquals(1L, countRows(tagId, true),
                "the synonym row is soft-deleted with the tag, not erased");
    }

    /** One query for a whole tag list: grouped per tag, and soft-deleted rows stay out. */
    @Test
    public void testFindByTagIdsGroupsPerTagAndSkipsDeleted() throws Exception {
        User user = createUser("synonym_group");
        String insuranceId = createTag(user, "Insurance");
        String carId = createTag(user, "Car");
        TagSynonymDao dao = new TagSynonymDao();
        dao.replaceForTag(insuranceId, List.of("Versicherung", "Assurance"));
        dao.replaceForTag(carId, List.of("Auto"));
        flush();
        dao.replaceForTag(insuranceId, List.of("Versicherung"));
        flush();

        Map<String, List<String>> byTag = dao.findByTagIds(List.of(insuranceId, carId));

        Assertions.assertEquals(List.of("Versicherung"), byTag.get(insuranceId));
        Assertions.assertEquals(List.of("Auto"), byTag.get(carId));
    }

    /** An empty request must not reach the database with an empty IN list. */
    @Test
    public void testFindByTagIdsOnAnEmptyRequestReturnsEmpty() {
        Assertions.assertTrue(new TagSynonymDao().findByTagIds(List.of()).isEmpty());
    }

    /**
     * Every tag read carries its synonyms. This is what makes the search resolution ACL-scoped
     * for free: the criteria read is already restricted to the tags the caller may see, so a
     * synonym can never be attached to a tag that is not in that list.
     */
    @Test
    public void testFindByCriteriaCarriesTheSynonyms() throws Exception {
        User user = createUser("synonym_criteria");
        String tagId = createTag(user, "Insurance");
        String plainId = createTag(user, "Car");
        new TagSynonymDao().replaceForTag(tagId, List.of("Versicherung"));
        flush();

        List<TagDto> tagDtoList = new TagDao().findByCriteria(new TagCriteria(), null);

        TagDto withSynonym = tagDtoList.stream().filter(t -> t.getId().equals(tagId)).findFirst().orElseThrow();
        TagDto withoutSynonym = tagDtoList.stream().filter(t -> t.getId().equals(plainId)).findFirst().orElseThrow();
        Assertions.assertEquals(List.of("Versicherung"), withSynonym.getSynonyms());
        Assertions.assertEquals(List.of(), withoutSynonym.getSynonyms(),
                "a tag with no synonym carries an empty list, never null");
    }
}
