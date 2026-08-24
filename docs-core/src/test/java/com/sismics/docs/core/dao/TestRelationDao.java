package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.dto.RelationDto;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Unit tests for {@link RelationDao#getByDocumentId(String)}.
 *
 * <p>The load-bearing case is the DIRECTION of the joined document: the row describes the OTHER
 * document, so both the title and the creation date must be that document's — the outgoing view
 * reports the target's, the incoming view the source's. A column taken from the queried document
 * instead would still return a date on every row and still satisfy a single-direction assertion,
 * so both directions are asserted here against two DISTINCT seeded dates.</p>
 */
public class TestRelationDao extends BaseTransactionalTest {

    /** Seeded creation dates, distinct per document so a wrong-side column cannot pass. */
    private static final long CREATE_DATE_A = 1_500_000_000_000L;
    private static final long CREATE_DATE_B = 1_600_000_000_000L;

    private String createDocument(User user, String title, long createDate) {
        Document document = new Document();
        document.setUserId(user.getId());
        document.setLanguage("eng");
        document.setTitle(title);
        document.setCreateDate(new Date(createDate));
        return new DocumentDao().create(document, user.getId());
    }

    /**
     * {@code DOC_CREATEDATE_D} is declared WITHOUT {@code not null} (dbupdate-000-0.sql) and no later
     * migration tightened it, and the entity's {@code nullable = false} never reaches an existing
     * database because schema generation is off — so a row with no creation date is representable and
     * must travel as a null instead of throwing out of the DAO.
     */
    @Test
    public void aLinkedDocumentWithoutACreateDateComesBackWithoutOne() throws Exception {
        User user = createUser("rel_dao_nodate");
        String docA = createDocument(user, "Rel DAO nodate A", CREATE_DATE_A);
        String docB = createDocument(user, "Rel DAO nodate B", CREATE_DATE_B);

        RelationDao dao = new RelationDao();
        dao.updateRelationList(docA, Set.of(docB));
        ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_DOCUMENT set DOC_CREATEDATE_D = null where DOC_ID_C = :id")
                .setParameter("id", docB)
                .executeUpdate();

        List<RelationDto> fromA = dao.getByDocumentId(docA);
        Assertions.assertEquals(1, fromA.size(), "the relation itself survives a dateless document");
        Assertions.assertEquals(docB, fromA.get(0).getId());
        Assertions.assertNull(fromA.get(0).getCreateTimestamp(),
                "a linked document with no creation date must come back with none, not throw");
    }

    @Test
    public void relationCarriesTheOtherDocumentsCreateDateInBothDirections() throws Exception {
        User user = createUser("rel_dao_owner");
        String docA = createDocument(user, "Rel DAO A", CREATE_DATE_A);
        String docB = createDocument(user, "Rel DAO B", CREATE_DATE_B);

        RelationDao dao = new RelationDao();
        dao.updateRelationList(docA, Set.of(docB));

        // A owns the link: from A the relation is outgoing and describes B.
        List<RelationDto> fromA = dao.getByDocumentId(docA);
        Assertions.assertEquals(1, fromA.size());
        RelationDto outgoing = fromA.get(0);
        Assertions.assertEquals(docB, outgoing.getId());
        Assertions.assertEquals("Rel DAO B", outgoing.getTitle());
        Assertions.assertTrue(outgoing.isSource());
        Assertions.assertEquals(Long.valueOf(CREATE_DATE_B), outgoing.getCreateTimestamp(),
                "an outgoing relation must carry the TARGET's creation date, not the queried document's");

        // The same row seen from B: incoming, and it describes A.
        List<RelationDto> fromB = dao.getByDocumentId(docB);
        Assertions.assertEquals(1, fromB.size());
        RelationDto incoming = fromB.get(0);
        Assertions.assertEquals(docA, incoming.getId());
        Assertions.assertEquals("Rel DAO A", incoming.getTitle());
        Assertions.assertFalse(incoming.isSource());
        Assertions.assertEquals(Long.valueOf(CREATE_DATE_A), incoming.getCreateTimestamp(),
                "an incoming relation must carry the SOURCE's creation date, not the queried document's");
    }
}
