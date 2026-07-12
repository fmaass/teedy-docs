package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * DB-level tests for the StatsDao date-series queries, hitting the NATIVE {@code >= :start} /
 * {@code < :end} boundary predicates DIRECTLY (no Java bucketer in the loop, so a
 * {@code >=}→{@code >} or {@code <}→{@code <=} mutation of either query is observable here — the
 * REST-level test's zero-filled window would otherwise mask an end-boundary widening).
 *
 * <p>All fixture timestamps are explicit UTC instants, so the assertions hold regardless of the
 * JVM's ambient timezone.
 */
public class TestStatsDao extends BaseTransactionalTest {

    private static final Instant START = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-06-08T00:00:00Z");

    private void insertDocument(String id, String userId, Instant createInstant) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.createNativeQuery("insert into T_DOCUMENT (DOC_ID_C, DOC_IDUSER_C, DOC_TITLE_C, DOC_LANGUAGE_C, DOC_CREATEDATE_D, DOC_UPDATEDATE_D)"
                        + " values (:id, :user, :title, 'eng', :date, :date)")
                .setParameter("id", id)
                .setParameter("user", userId)
                .setParameter("title", "boundary-" + id)
                .setParameter("date", Date.from(createInstant))
                .executeUpdate();
    }

    private void insertAudit(String cls, Instant createInstant) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.createNativeQuery("insert into T_AUDIT_LOG (LOG_ID_C, LOG_IDENTITY_C, LOG_CLASSENTITY_C, LOG_TYPE_C, LOG_IDUSER_C, LOG_CREATEDATE_D)"
                        + " values (:id, :identity, :cls, 'CREATE', 'admin', :date)")
                .setParameter("id", UUID.randomUUID().toString())
                .setParameter("identity", UUID.randomUUID().toString())
                .setParameter("cls", cls)
                .setParameter("date", Date.from(createInstant))
                .executeUpdate();
    }

    /**
     * documents_created boundaries: a row AT start is INCLUDED, a row at the last instant before
     * end is INCLUDED, a row exactly AT end is EXCLUDED, and rows outside the range are excluded.
     */
    @Test
    public void testDocumentCreatedBoundaries() throws Exception {
        User user = createUser("stats_dao_docs_user");
        StatsDao statsDao = new StatsDao();

        insertDocument("bnd-before", user.getId(), START.minusMillis(1)); // just before start → EXCLUDED
        insertDocument("bnd-start", user.getId(), START);                 // exactly start → INCLUDED
        insertDocument("bnd-mid", user.getId(), START.plusSeconds(3600)); // inside → INCLUDED
        insertDocument("bnd-lastbeforeend", user.getId(), END.minusMillis(1)); // last before end → INCLUDED
        insertDocument("bnd-end", user.getId(), END);                     // exactly end → EXCLUDED
        ThreadLocalContext.get().getEntityManager().flush();

        List<Date> dates = statsDao.getDocumentCreatedDates(Date.from(START), Date.from(END));
        long inWindow = dates.stream().filter(d -> {
            long t = d.getTime();
            return t >= START.toEpochMilli() && t < END.toEpochMilli();
        }).count();
        // Exactly the three in-window rows (start, mid, last-before-end) are returned; the row AT
        // end and the row before start are excluded by the native >= / < predicates.
        Assertions.assertEquals(3, inWindow, "start + mid + last-before-end are in-window; before-start and at-end are not");
        // The exact-at-end row must NOT be present — this is the assertion a `< :end`→`<= :end`
        // mutation breaks (it would return the at-end row, making the count 4).
        Assertions.assertTrue(dates.stream().noneMatch(d -> d.getTime() == END.toEpochMilli()),
                "a document created exactly at the exclusive end must NOT be returned");
        // The exact-at-start row MUST be present — a `>= :start`→`> :start` mutation drops it.
        Assertions.assertTrue(dates.stream().anyMatch(d -> d.getTime() == START.toEpochMilli()),
                "a document created exactly at the inclusive start must be returned");
    }

    /**
     * activity boundaries: identical inclusive-start / exclusive-end semantics on LOG_CREATEDATE_D,
     * and only in-set entity classes are returned.
     */
    @Test
    public void testActivityBoundariesAndClassFilter() throws Exception {
        StatsDao statsDao = new StatsDao();

        insertAudit("Document", START.minusMillis(1)); // before start → EXCLUDED
        insertAudit("Document", START);                // exactly start → INCLUDED
        insertAudit("File", START.plusSeconds(10));    // inside, in-set → INCLUDED
        insertAudit("Comment", END.minusMillis(1));    // last before end, in-set → INCLUDED
        insertAudit("Document", END);                  // exactly end → EXCLUDED
        insertAudit("Group", START.plusSeconds(20));   // inside window but OUT-of-set → EXCLUDED
        ThreadLocalContext.get().getEntityManager().flush();

        List<Date> dates = statsDao.getActivityDates(Date.from(START), Date.from(END));
        long inWindow = dates.stream().filter(d -> {
            long t = d.getTime();
            return t >= START.toEpochMilli() && t < END.toEpochMilli();
        }).count();
        // start(Document) + File + Comment = 3 in-set, in-window rows; the Group row (in-window but
        // out-of-set), the before-start row, and the at-end row are all excluded.
        Assertions.assertEquals(3, inWindow, "only in-set, in-window audit rows are returned");
        Assertions.assertTrue(dates.stream().noneMatch(d -> d.getTime() == END.toEpochMilli()),
                "an audit row exactly at the exclusive end must NOT be returned");
        Assertions.assertTrue(dates.stream().anyMatch(d -> d.getTime() == START.toEpochMilli()),
                "an audit row exactly at the inclusive start must be returned");
    }
}
