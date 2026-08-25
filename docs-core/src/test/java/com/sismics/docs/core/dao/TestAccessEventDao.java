package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.constant.AccessTargetType;
import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.dto.AccessUserCountDto;
import com.sismics.docs.core.dao.dto.DocumentAccessStatsDto;
import com.sismics.docs.core.model.jpa.AccessEvent;
import com.sismics.docs.core.model.jpa.Acl;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link AccessEventDao} — the #300 access-event store and the aggregations the
 * counters are derived from.
 *
 * <p>The load-bearing cases are the SCOPING ones: a personal count must never include another user's
 * events, and the administrator ranking must never name a document the caller cannot read.</p>
 */
public class TestAccessEventDao extends BaseTransactionalTest {
    private String createDocument(User user, String title) {
        DocumentDao documentDao = new DocumentDao();
        Document document = new Document();
        document.setUserId(user.getId());
        document.setLanguage("eng");
        document.setTitle(title);
        document.setCreateDate(new Date());
        return documentDao.create(document, user.getId());
    }

    private void record(AccessTargetType type, String targetId, String documentId, String userId) {
        new AccessEventDao().create(new AccessEvent()
                .setType(type)
                .setTargetId(targetId)
                .setDocumentId(documentId)
                .setUserId(userId));
    }

    private void flush() {
        ThreadLocalContext.get().getEntityManager().flush();
    }

    @Test
    public void createStampsIdentityAndDate() throws Exception {
        User user = createUser("acc_create");
        String documentId = createDocument(user, "Access create doc");

        AccessEvent event = new AccessEvent()
                .setType(AccessTargetType.DOCUMENT)
                .setTargetId(documentId)
                .setDocumentId(documentId)
                .setUserId(user.getId());
        String id = new AccessEventDao().create(event);
        Assertions.assertNotNull(id, "create must assign a generated id");
        flush();

        AccessEvent stored = ThreadLocalContext.get().getEntityManager().find(AccessEvent.class, id);
        Assertions.assertNotNull(stored);
        Assertions.assertEquals(AccessTargetType.DOCUMENT, stored.getType());
        Assertions.assertEquals(documentId, stored.getTargetId());
        Assertions.assertEquals(documentId, stored.getDocumentId());
        Assertions.assertEquals(user.getId(), stored.getUserId());
        Assertions.assertNotNull(stored.getCreateDate(), "create must stamp an access date when none was set");
    }

    @Test
    public void personalCountCountsOnlyTheAskingUsersOwnEvents() throws Exception {
        User owner = createUser("acc_scope_owner");
        User other = createUser("acc_scope_other");
        String documentId = createDocument(owner, "Access scope doc");

        record(AccessTargetType.DOCUMENT, documentId, documentId, owner.getId());
        record(AccessTargetType.DOCUMENT, documentId, documentId, owner.getId());
        record(AccessTargetType.DOCUMENT, documentId, documentId, other.getId());
        record(AccessTargetType.DOCUMENT, documentId, documentId, other.getId());
        record(AccessTargetType.DOCUMENT, documentId, documentId, other.getId());
        flush();

        AccessEventDao dao = new AccessEventDao();
        Assertions.assertEquals(2L, dao.countByTargetAndUser(AccessTargetType.DOCUMENT, documentId, owner.getId()),
                "the owner's personal count must be its OWN two opens, not the five recorded on the document");
        Assertions.assertEquals(3L, dao.countByTargetAndUser(AccessTargetType.DOCUMENT, documentId, other.getId()),
                "the other user's personal count must be its own three opens");
    }

    @Test
    public void personalCountIsScopedToTheTargetKind() throws Exception {
        User user = createUser("acc_kind");
        String documentId = createDocument(user, "Access kind doc");
        // A file whose id deliberately COLLIDES with the document id: only the kind separates them, so a
        // count that ignored ACC_TYPE_C would report 3 for both.
        record(AccessTargetType.DOCUMENT, documentId, documentId, user.getId());
        record(AccessTargetType.FILE, documentId, documentId, user.getId());
        record(AccessTargetType.FILE, documentId, documentId, user.getId());
        flush();

        AccessEventDao dao = new AccessEventDao();
        Assertions.assertEquals(1L, dao.countByTargetAndUser(AccessTargetType.DOCUMENT, documentId, user.getId()));
        Assertions.assertEquals(2L, dao.countByTargetAndUser(AccessTargetType.FILE, documentId, user.getId()));
    }

    @Test
    public void batchedFileCountsAnswerEveryRequestedIdInOneQuery() throws Exception {
        User user = createUser("acc_batch");
        User other = createUser("acc_batch_other");
        String documentId = createDocument(user, "Access batch doc");

        record(AccessTargetType.FILE, "file-a", documentId, user.getId());
        record(AccessTargetType.FILE, "file-a", documentId, user.getId());
        record(AccessTargetType.FILE, "file-b", documentId, user.getId());
        // Another user's reads of the same files must not leak into this user's map.
        record(AccessTargetType.FILE, "file-a", documentId, other.getId());
        record(AccessTargetType.FILE, "file-c", documentId, other.getId());
        flush();

        Map<String, Long> counts = new AccessEventDao().countByTargetsAndUser(
                AccessTargetType.FILE, List.of("file-a", "file-b", "file-c"), user.getId());
        Assertions.assertEquals(2L, counts.get("file-a"));
        Assertions.assertEquals(1L, counts.get("file-b"));
        Assertions.assertNull(counts.get("file-c"), "a file this user never opened must be absent, not zero-filled");
    }

    @Test
    public void batchedCountsOfNoTargetsQueriesNothing() throws Exception {
        User user = createUser("acc_batch_empty");
        Assertions.assertTrue(new AccessEventDao()
                        .countByTargetsAndUser(AccessTargetType.FILE, List.of(), user.getId()).isEmpty(),
                "an empty id set must short-circuit to an empty map (an empty SQL IN list is not portable)");
    }

    @Test
    public void globalTotalsAggregateEveryUserButKeepTheKindsApart() throws Exception {
        User a = createUser("acc_total_a");
        User b = createUser("acc_total_b");
        String documentId = createDocument(a, "Access totals doc");

        AccessEventDao dao = new AccessEventDao();
        long documentsBefore = dao.countByType(AccessTargetType.DOCUMENT);
        long filesBefore = dao.countByType(AccessTargetType.FILE);

        record(AccessTargetType.DOCUMENT, documentId, documentId, a.getId());
        record(AccessTargetType.DOCUMENT, documentId, documentId, b.getId());
        record(AccessTargetType.FILE, "file-total", documentId, b.getId());
        flush();

        Assertions.assertEquals(documentsBefore + 2, dao.countByType(AccessTargetType.DOCUMENT));
        Assertions.assertEquals(filesBefore + 1, dao.countByType(AccessTargetType.FILE));
    }

    @Test
    public void rankingOrdersByTotalAndCarriesThePerUserBreakdown() throws Exception {
        User admin = createUser("acc_rank_admin");
        User reader = createUser("acc_rank_reader");
        String popular = createDocument(admin, "Access rank popular");
        String quiet = createDocument(admin, "Access rank quiet");

        record(AccessTargetType.DOCUMENT, popular, popular, admin.getId());
        record(AccessTargetType.DOCUMENT, popular, popular, admin.getId());
        record(AccessTargetType.DOCUMENT, popular, popular, reader.getId());
        record(AccessTargetType.DOCUMENT, quiet, quiet, reader.getId());
        flush();

        // "administrators" short-circuits the ACL predicate exactly as it does everywhere else.
        List<DocumentAccessStatsDto> ranking =
                new AccessEventDao().findMostAccessedDocuments(List.of("administrators"), 10);

        DocumentAccessStatsDto first = ranking.stream().filter(r -> r.getId().equals(popular)).findFirst().orElseThrow();
        DocumentAccessStatsDto second = ranking.stream().filter(r -> r.getId().equals(quiet)).findFirst().orElseThrow();
        Assertions.assertTrue(ranking.indexOf(first) < ranking.indexOf(second),
                "the more-accessed document must rank first");
        Assertions.assertEquals("Access rank popular", first.getTitle());
        Assertions.assertEquals(3L, first.getTotal());
        Assertions.assertEquals(1L, second.getTotal());

        // Per-user breakdown, most-active user first.
        List<AccessUserCountDto> breakdown = first.getUserCounts();
        Assertions.assertEquals(2, breakdown.size());
        Assertions.assertEquals("acc_rank_admin", breakdown.get(0).getUsername());
        Assertions.assertEquals(2L, breakdown.get(0).getCount());
        Assertions.assertEquals("acc_rank_reader", breakdown.get(1).getUsername());
        Assertions.assertEquals(1L, breakdown.get(1).getCount());
    }

    @Test
    public void rankingHonoursTheLimit() throws Exception {
        User user = createUser("acc_rank_limit");
        String first = createDocument(user, "Access limit one");
        String second = createDocument(user, "Access limit two");
        record(AccessTargetType.DOCUMENT, first, first, user.getId());
        record(AccessTargetType.DOCUMENT, first, first, user.getId());
        record(AccessTargetType.DOCUMENT, second, second, user.getId());
        flush();

        List<DocumentAccessStatsDto> ranking =
                new AccessEventDao().findMostAccessedDocuments(List.of("administrators"), 1);
        Assertions.assertEquals(1, ranking.size(), "limit must bound the ranked page");
        Assertions.assertEquals(first, ranking.get(0).getId(), "and it must keep the most-accessed row");
    }

    @Test
    public void rankingHidesDocumentsTheCallerCannotRead() throws Exception {
        User owner = createUser("acc_acl_owner");
        User caller = createUser("acc_acl_caller");
        String visible = createDocument(owner, "Access acl visible");
        String invisible = createDocument(owner, "Access acl invisible");

        // The caller holds READ on exactly one of the two documents.
        AclDao aclDao = new AclDao();
        Acl acl = new Acl();
        acl.setSourceId(visible);
        acl.setPerm(PermType.READ);
        acl.setTargetId(caller.getId());
        acl.setType(AclType.USER);
        aclDao.create(acl, owner.getId());

        record(AccessTargetType.DOCUMENT, invisible, invisible, owner.getId());
        record(AccessTargetType.DOCUMENT, invisible, invisible, owner.getId());
        record(AccessTargetType.DOCUMENT, invisible, invisible, owner.getId());
        record(AccessTargetType.DOCUMENT, visible, visible, owner.getId());
        flush();

        List<DocumentAccessStatsDto> ranking =
                new AccessEventDao().findMostAccessedDocuments(List.of(caller.getId()), 10);

        Assertions.assertTrue(ranking.stream().noneMatch(r -> r.getId().equals(invisible)),
                "a document the caller cannot READ must not appear in the ranking, not even as a title-less row");
        Assertions.assertTrue(ranking.stream().anyMatch(r -> r.getId().equals(visible)),
                "the document the caller CAN read must still be ranked (proves the predicate is not simply empty)");
    }

    @Test
    public void rankingSkipsDeletedDocuments() throws Exception {
        User user = createUser("acc_rank_deleted");
        String documentId = createDocument(user, "Access rank deleted");
        record(AccessTargetType.DOCUMENT, documentId, documentId, user.getId());
        record(AccessTargetType.DOCUMENT, documentId, documentId, user.getId());
        flush();

        Assertions.assertTrue(new AccessEventDao().findMostAccessedDocuments(List.of("administrators"), 10)
                        .stream().anyMatch(r -> r.getId().equals(documentId)),
                "control: the live document is ranked before it is trashed");

        new DocumentDao().delete(documentId, user.getId());
        flush();

        Assertions.assertTrue(new AccessEventDao().findMostAccessedDocuments(List.of("administrators"), 10)
                        .stream().noneMatch(r -> r.getId().equals(documentId)),
                "a trashed document is not a most-used document, even though its events are kept");
    }
}
