package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unit tests for the two batch reads the tag-reduction run is built on (#293).
 *
 * <p>Both exist so that reducing a hundred selected documents is a constant number of queries
 * instead of one per document, and both are ALIVENESS filters as much as they are reads: the run
 * must not touch a trashed document, and must not reason about a link the trash or a tag deletion
 * has already soft-deleted. Those two properties are what is pinned here — the happy path is the
 * least interesting half.
 *
 * @author fmaass
 */
public class TestTagReductionDao extends BaseTransactionalTest {
    private String createDocument(User user, String title) {
        Document document = new Document();
        document.setUserId(user.getId());
        document.setLanguage("eng");
        document.setTitle(title);
        document.setCreateDate(new Date());
        return new DocumentDao().create(document, user.getId());
    }

    private String createTag(User user, String name, String parentId) {
        Tag tag = new Tag();
        tag.setName(name);
        tag.setColor("#3399cc");
        tag.setUserId(user.getId());
        tag.setParentId(parentId);
        return new TagDao().create(tag, user.getId());
    }

    private void flush() {
        ThreadLocalContext.get().getEntityManager().flush();
    }

    /** Only ALIVE documents come back, and an unknown ID is simply absent — never an error. */
    @Test
    public void testFindAliveIdsDropsTrashedAndUnknownDocuments() throws Exception {
        User user = createUser("tagreduce_alive");
        String aliveId = createDocument(user, "Alive");
        String trashedId = createDocument(user, "Trashed");
        DocumentDao documentDao = new DocumentDao();
        documentDao.delete(trashedId, user.getId());
        flush();

        Set<String> alive = documentDao.findAliveIds(List.of(aliveId, trashedId, "no-such-document"));

        Assertions.assertEquals(Set.of(aliveId), alive,
                "a trashed document is not reducible, and an unknown ID is not an error");
    }

    /** An empty request must not reach the database with an empty IN list. */
    @Test
    public void testFindAliveIdsOnAnEmptyRequestReturnsEmpty() {
        Assertions.assertTrue(new DocumentDao().findAliveIds(List.of()).isEmpty());
    }

    /** The links come back grouped per document, so one query serves the whole selection. */
    @Test
    public void testFindTagIdsByDocumentIdsGroupsPerDocument() throws Exception {
        User user = createUser("tagreduce_group");
        String parentId = createTag(user, "Parent", null);
        String childId = createTag(user, "Child", parentId);
        String firstId = createDocument(user, "First");
        String secondId = createDocument(user, "Second");
        TagDao tagDao = new TagDao();
        tagDao.updateTagList(firstId, Set.of(parentId, childId));
        tagDao.updateTagList(secondId, Set.of(childId));
        flush();

        Map<String, Set<String>> byDocument = tagDao.findTagIdsByDocumentIds(List.of(firstId, secondId));

        Assertions.assertEquals(Set.of(parentId, childId), byDocument.get(firstId));
        Assertions.assertEquals(Set.of(childId), byDocument.get(secondId));
    }

    /**
     * A link the user already removed, and a link to a tag that has since been deleted, are both
     * gone from the read. A reduction that still saw them would report removing a tag that is not
     * on the document any more.
     */
    @Test
    public void testFindTagIdsByDocumentIdsIgnoresRemovedLinksAndDeletedTags() throws Exception {
        User user = createUser("tagreduce_dead");
        String keptId = createTag(user, "Kept", null);
        String unlinkedId = createTag(user, "Unlinked", null);
        String deletedId = createTag(user, "Deleted", null);
        String documentId = createDocument(user, "Document");
        TagDao tagDao = new TagDao();
        tagDao.updateTagList(documentId, Set.of(keptId, unlinkedId, deletedId));
        flush();

        // One link removed the ordinary way, one tag deleted out from under its link.
        tagDao.updateTagList(documentId, Set.of(keptId, deletedId));
        tagDao.delete(deletedId, user.getId());
        flush();

        Map<String, Set<String>> byDocument = tagDao.findTagIdsByDocumentIds(List.of(documentId));

        Assertions.assertEquals(Set.of(keptId), byDocument.get(documentId));
    }

    /** A document with no tags at all yields no entry rather than an empty one. */
    @Test
    public void testFindTagIdsByDocumentIdsOmitsUntaggedDocuments() throws Exception {
        User user = createUser("tagreduce_untagged");
        String documentId = createDocument(user, "Untagged");
        flush();

        Assertions.assertTrue(new TagDao().findTagIdsByDocumentIds(List.of(documentId)).isEmpty());
    }

    /** The same empty-IN guard as above, on the other read. */
    @Test
    public void testFindTagIdsByDocumentIdsOnAnEmptyRequestReturnsEmpty() {
        Assertions.assertTrue(new TagDao().findTagIdsByDocumentIds(List.of()).isEmpty());
    }

    /** Every T_DOCUMENT_TAG row of a document, live or soft-deleted. */
    private long tagLinkRows(String documentId) {
        Query q = ThreadLocalContext.get().getEntityManager().createNativeQuery(
                "select count(*) from T_DOCUMENT_TAG dt where dt.DOT_IDDOCUMENT_C = :documentId");
        q.setParameter("documentId", documentId);
        return ((Number) q.getSingleResult()).longValue();
    }

    /**
     * The reason the tag-reduction run uses {@link TagDao#removeTagLinks} and not
     * {@link TagDao#updateTagList}: on a TRASHED document the synchronizing method INSERTS.
     *
     * <p>The trash stamps a document's tag links with the document's own delete date, so they read
     * as absent. {@code updateTagList} is a set-synchronizer — it re-creates every link of its
     * target set that it cannot find — so handing it a set assembled before the trash caught the
     * document adds fresh rows to a document nobody can see, which a restore then cannot
     * reconcile. This pins that mechanism rather than describing it, and pins that the remove-only
     * primitive has no such path: it matches live links only, so on the same document it removes
     * nothing, adds nothing and reports zero.</p>
     */
    @Test
    public void testRemoveTagLinksCannotInsertWhereUpdateTagListDoes() throws Exception {
        User user = createUser("tagreduce_removeonly");
        String parentId = createTag(user, "Parent", null);
        String childId = createTag(user, "Child", parentId);
        TagDao tagDao = new TagDao();
        DocumentDao documentDao = new DocumentDao();

        String syncedId = createDocument(user, "Synchronized while trashed");
        String removedId = createDocument(user, "Reduced while trashed");
        tagDao.updateTagList(syncedId, Set.of(parentId, childId));
        tagDao.updateTagList(removedId, Set.of(parentId, childId));
        flush();
        long syncedRowsBefore = tagLinkRows(syncedId);
        long removedRowsBefore = tagLinkRows(removedId);

        documentDao.delete(syncedId, user.getId());
        documentDao.delete(removedId, user.getId());
        flush();

        // The synchronizer, handed the set a caller remembered from before the trash.
        tagDao.updateTagList(syncedId, Set.of(childId), Set.of(parentId, childId));
        flush();
        Assertions.assertTrue(tagLinkRows(syncedId) > syncedRowsBefore,
                "updateTagList re-creates a link the trash soft-deleted");

        // The remove-only primitive, handed the same intent.
        int removedLinks = tagDao.removeTagLinks(removedId, Set.of(parentId));
        flush();
        Assertions.assertEquals(0, removedLinks, "there is no live link left to remove");
        Assertions.assertEquals(removedRowsBefore, tagLinkRows(removedId),
                "and nothing was inserted on the trashed document");
    }

    /** Remove-only in the ordinary case: exactly the named links go, the others stay. */
    @Test
    public void testRemoveTagLinksRemovesOnlyTheNamedLinks() throws Exception {
        User user = createUser("tagreduce_removesome");
        String parentId = createTag(user, "Parent", null);
        String childId = createTag(user, "Child", parentId);
        String otherId = createTag(user, "Other", null);
        String documentId = createDocument(user, "Document");
        TagDao tagDao = new TagDao();
        tagDao.updateTagList(documentId, Set.of(parentId, childId, otherId));
        flush();

        // A tag that is NOT on the document is named alongside one that is: it must not make the
        // call fail, and it must not create anything.
        int removed = tagDao.removeTagLinks(documentId, Set.of(parentId, "no-such-tag"));
        flush();

        Assertions.assertEquals(1, removed);
        Assertions.assertEquals(Set.of(childId, otherId),
                tagDao.findTagIdsByDocumentIds(List.of(documentId)).get(documentId));
    }
}
