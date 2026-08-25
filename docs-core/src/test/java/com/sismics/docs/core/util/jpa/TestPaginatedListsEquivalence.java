package com.sismics.docs.core.util.jpa;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.TagDao;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Differential test of {@link PaginatedLists#executePaginatedQuery}: whatever mechanism produces the
 * page and the total, both must stay identical to the two-statement path this class re-implements as
 * an independent oracle (a count wrapped around the sorted query, plus the sorted query itself).
 *
 * <p>The fixture is shaped like the document-list query the search path builds: a {@code select
 * distinct} over a left join that multiplies rows per document. That shape is what separates a
 * correct total from a plausible-looking wrong one — a window count evaluated before {@code distinct}
 * counts join rows, not documents, and would report 16 where the list holds 12. The out-of-range page
 * pins the other half: a page that returns no rows must still report the true total, which is exactly
 * what a total carried on the returned rows cannot do by itself.</p>
 */
public class TestPaginatedListsEquivalence extends BaseTransactionalTest {
    /** Distinct documents in the fixture. */
    private static final int DOC_COUNT = 12;

    /**
     * The document-list query shape: distinct documents, left-joined to their tags so the raw join
     * emits more rows than the list contains.
     */
    private static final String QUERY =
            "select distinct d.DOC_ID_C c0, d.DOC_TITLE_C c1, d.DOC_CREATEDATE_D c2 " +
            " from T_DOCUMENT d " +
            " left join T_DOCUMENT_TAG dt on dt.DOT_IDDOCUMENT_C = d.DOC_ID_C and dt.DOT_DELETEDATE_D is null " +
            " where d.DOC_IDUSER_C = :userId and d.DOC_DELETEDATE_D is null";

    private String userId;

    private void seed() throws Exception {
        User user = createUser("paginated_" + System.nanoTime());
        userId = user.getId();

        TagDao tagDao = new TagDao();
        List<String> tagIds = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Tag tag = new Tag();
            tag.setName("pag-tag-" + i);
            tag.setColor("#ff0000");
            tag.setUserId(userId);
            tagIds.add(tagDao.create(tag, userId));
        }

        DocumentDao documentDao = new DocumentDao();
        long base = 1_500_000_000_000L;
        for (int i = 0; i < DOC_COUNT; i++) {
            Document document = new Document();
            document.setUserId(userId);
            document.setLanguage("eng");
            // Titles and create dates are both a total order, so an ordering assertion can never be
            // satisfied by an arbitrary tie-break.
            document.setTitle(String.format("Doc %02d", i));
            document.setCreateDate(new Date(base + i * 1000L));
            String documentId = documentDao.create(document, userId);
            if (i % 3 == 0) {
                // Two tags on every third document: the raw join emits 16 rows for 12 documents.
                tagDao.updateTagList(documentId, Set.of(tagIds.get(0), tagIds.get(1)));
            }
        }
        ThreadLocalContext.get().getEntityManager().flush();
    }

    private QueryParam queryParam() {
        Map<String, Object> parameterMap = new HashMap<>();
        parameterMap.put("userId", userId);
        return new QueryParam(QUERY, parameterMap);
    }

    /** The pre-change count path: count(*) over the sorted query, as its own statement. */
    private int referenceCount(SortCriteria sortCriteria) {
        QueryParam sorted = QueryUtil.getSortedQueryParam(queryParam(), sortCriteria);
        QueryParam counted = new QueryParam(
                "select count(*) as result_count from (" + sorted.getQueryString() + ") as t1",
                sorted.getParameterMap());
        return ((Number) QueryUtil.getNativeQuery(counted).getSingleResult()).intValue();
    }

    /** The pre-change page path: the sorted query, paginated by the JDBC driver. */
    @SuppressWarnings("unchecked")
    private List<String> referencePage(SortCriteria sortCriteria, int offset, int limit) {
        QueryParam sorted = QueryUtil.getSortedQueryParam(queryParam(), sortCriteria);
        Query q = QueryUtil.getNativeQuery(sorted);
        q.setFirstResult(offset);
        q.setMaxResults(limit);
        List<String> ids = new ArrayList<>();
        for (Object[] o : (List<Object[]>) q.getResultList()) {
            ids.add((String) o[0]);
        }
        return ids;
    }

    private List<String> idsOf(List<Object[]> rows) {
        List<String> ids = new ArrayList<>();
        for (Object[] o : rows) {
            ids.add((String) o[0]);
        }
        return ids;
    }

    @Test
    public void pageAndTotalMatchTheTwoStatementPath() throws Exception {
        seed();

        int[][] windows = {{0, 5}, {5, 5}, {10, 5}, {0, 12}, {3, 4}};
        SortCriteria[] sorts = {
                new SortCriteria(1, true),   // title ascending
                new SortCriteria(1, false),  // title descending
                new SortCriteria(2, true),   // create date ascending
                new SortCriteria(0, true)    // id ascending
        };

        for (SortCriteria sortCriteria : sorts) {
            int expectedCount = referenceCount(sortCriteria);
            Assertions.assertEquals(DOC_COUNT, expectedCount,
                    "the oracle must count documents, not join rows");
            for (int[] window : windows) {
                PaginatedList<Object> paginatedList = PaginatedLists.create(window[1], window[0]);
                List<Object[]> rows = PaginatedLists.executePaginatedQuery(paginatedList, queryParam(), sortCriteria);

                Assertions.assertEquals(expectedCount, paginatedList.getResultCount(),
                        "total for sort column " + sortCriteria.getColumn() + " at offset " + window[0]);
                Assertions.assertEquals(referencePage(sortCriteria, window[0], window[1]), idsOf(rows),
                        "page ids and their order for sort column " + sortCriteria.getColumn()
                                + " at offset " + window[0]);
            }
        }
    }

    @Test
    public void outOfRangePageStillReportsTheTrueTotal() throws Exception {
        seed();

        SortCriteria sortCriteria = new SortCriteria(1, true);
        PaginatedList<Object> paginatedList = PaginatedLists.create(5, 50);
        List<Object[]> rows = PaginatedLists.executePaginatedQuery(paginatedList, queryParam(), sortCriteria);

        Assertions.assertTrue(rows.isEmpty(), "offset past the end returns no rows");
        Assertions.assertEquals(DOC_COUNT, paginatedList.getResultCount(),
                "an empty page must still report how many results exist");
    }

    @Test
    public void emptyResultReportsZero() throws Exception {
        seed();
        Map<String, Object> parameterMap = new HashMap<>();
        parameterMap.put("userId", "no-such-user");
        QueryParam empty = new QueryParam(QUERY, parameterMap);

        PaginatedList<Object> paginatedList = PaginatedLists.create(10, 0);
        List<Object[]> rows = PaginatedLists.executePaginatedQuery(paginatedList, empty, new SortCriteria(1, true));

        Assertions.assertTrue(rows.isEmpty());
        Assertions.assertEquals(0, paginatedList.getResultCount());
    }

    @Test
    public void rowsCarryOnlyTheQueriedColumns() throws Exception {
        seed();
        PaginatedList<Object> paginatedList = PaginatedLists.create(3, 0);
        List<Object[]> rows = PaginatedLists.executePaginatedQuery(paginatedList, queryParam(), new SortCriteria(1, true));

        Assertions.assertFalse(rows.isEmpty());
        for (Object[] row : rows) {
            Assertions.assertEquals(3, row.length,
                    "callers read the row by column index; the pagination mechanism must not leak a column into it");
        }
    }
}
