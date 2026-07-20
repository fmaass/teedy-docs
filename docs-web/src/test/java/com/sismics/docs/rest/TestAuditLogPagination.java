package com.sismics.docs.rest;

import com.sismics.docs.core.constant.AuditLogType;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.AuditLog;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Keyset (cursor) pagination of the audit log (#139).
 *
 * <p>Rows are seeded directly with controlled create-dates so ordering, ties, and page boundaries
 * are deterministic. Seeded rows use far-future timestamps so they sort ahead of the baseline rows
 * a fresh document produces, and each assertion is written to be RED against the pre-fix resource
 * (hardcoded 20 rows, offset 0, no cursor, no has_more, create-date-only order):
 * <ul>
 *   <li>{@code has_more} is absent pre-fix, so any assertion reading it fails.</li>
 *   <li>the cursor is ignored pre-fix, so every page returns the same newest rows — the
 *       no-duplicate-across-pages assertions fail.</li>
 *   <li>the limit is ignored pre-fix (always 20), so the clamp-to-100 assertion fails.</li>
 * </ul>
 *
 * @author bgamard
 */
public class TestAuditLogPagination extends BaseJerseyTest {
    /** Far-future base so seeded rows sort ahead of the document's baseline audit rows. */
    private static final long FUTURE_BASE = 4_000_000_000_000L;

    /**
     * Bad input never yields a 500: a null/zero/negative limit falls back to the default (20), and
     * an over-large limit is clamped (100). None of these are errors.
     */
    @Test
    public void testLimitBoundsNeverError() {
        String user = "auditpage_limit";
        clientUtil.createUser(user);
        String token = clientUtil.login(user);
        String docId = createDocument(token, "limit bounds document");
        String userId = userId(user);

        // 105 seeded rows + the document's baseline rows: enough to exercise the ceiling.
        seedRows(docId, userId, FUTURE_BASE, 1000L, 105, AuditLogType.CREATE);

        // Default (no limit) preserves the historical page size of 20 (NOT PaginatedLists' 10).
        JsonObject json = fetchPage(token, docId, null, null, null);
        Assertions.assertEquals(20, json.getJsonArray("logs").size(), "default limit must be 20");
        Assertions.assertTrue(json.getBoolean("has_more"), "more than one page exists");

        // Zero and negative fall back to the default without a 500.
        for (int badLimit : new int[] { 0, -5 }) {
            Response response = rawPage(token, docId, badLimit, null, null);
            Assertions.assertEquals(200, response.getStatus(), "a non-positive limit must not error");
            Assertions.assertEquals(20, response.readEntity(JsonObject.class).getJsonArray("logs").size(),
                    "a non-positive limit falls back to the default of 20");
        }

        // Over-large clamps to 100.
        json = fetchPage(token, docId, 1000, null, null);
        Assertions.assertEquals(100, json.getJsonArray("logs").size(), "limit must clamp to 100");
        Assertions.assertTrue(json.getBoolean("has_more"), "more rows remain past the clamped page");

        // A non-numeric or overflowing limit is a client mistake, not a missing resource: it must fall
        // back to the default (20), never the framework's 404 for a failed Integer query-param conversion
        // (limit is accepted as a String and parsed in the resource, mirroring before_date).
        for (String badLimit : new String[] { "abc", "2147483648", "3.5", " " }) {
            Response response = rawStringLimit(token, docId, badLimit);
            Assertions.assertEquals(200, response.getStatus(), "a malformed limit '" + badLimit + "' must not 404/500");
            Assertions.assertEquals(20, response.readEntity(JsonObject.class).getJsonArray("logs").size(),
                    "a malformed limit '" + badLimit + "' falls back to the default of 20");
        }

        deleteUser(user);
    }

    /**
     * A malformed or half-supplied cursor is a clean 400, never a silent first page.
     */
    @Test
    public void testMalformedCursorRejected() {
        String user = "auditpage_cursor";
        clientUtil.createUser(user);
        String token = clientUtil.login(user);
        String docId = createDocument(token, "cursor validation document");

        // before_date without before_id.
        Assertions.assertEquals(400, rawCursor(token, docId, 4_000_000_000_000L, null).getStatus(),
                "before_date alone is a half cursor");
        // before_id without before_date.
        Assertions.assertEquals(400, rawCursor(token, docId, null, UUID.randomUUID().toString()).getStatus(),
                "before_id alone is a half cursor");
        // Malformed before_id (illegal characters).
        Assertions.assertEquals(400, rawCursor(token, docId, 4_000_000_000_000L, "not a valid id!").getStatus(),
                "an id with illegal characters is malformed");
        // Over-long before_id (LOG_ID_C is 36 chars).
        Assertions.assertEquals(400, rawCursor(token, docId, 4_000_000_000_000L, "0123456789012345678901234567890123456789").getStatus(),
                "an over-long id is malformed");
        // Negative before_date.
        Assertions.assertEquals(400, rawCursor(token, docId, -1L, UUID.randomUUID().toString()).getStatus(),
                "a negative before_date is malformed");
        // Non-numeric before_date: a clean 400, not the framework's 404 for a failed Long query-param
        // conversion (before_date is accepted as a String and parsed in the resource).
        Assertions.assertEquals(400, rawStringCursor(token, docId, "not-a-number", UUID.randomUUID().toString()).getStatus(),
                "a non-numeric before_date is a validation error, not a 404");

        // A well-formed cursor (both parts, valid shape) is accepted.
        Response ok = rawCursor(token, docId, 4_000_000_000_000L, UUID.randomUUID().toString());
        Assertions.assertEquals(200, ok.getStatus(), "a well-formed cursor is accepted");

        deleteUser(user);
    }

    /**
     * Rows sharing an identical create-date page across a boundary with no duplicate and no skip:
     * the (create_date DESC, id DESC) tiebreaker imposes a total order. With create-date-only
     * ordering the boundary row is ambiguous and can repeat or vanish.
     */
    @Test
    public void testTiedTimestampsPageWithoutDuplicateOrSkip() {
        String user = "auditpage_tie";
        clientUtil.createUser(user);
        String token = clientUtil.login(user);
        String docId = createDocument(token, "tied timestamps document");
        String userId = userId(user);

        // Three rows at the SAME (future) instant, so they are the three newest rows.
        List<String> tiedIds = seedRows(docId, userId, FUTURE_BASE, 0L, 3, AuditLogType.UPDATE);
        Set<String> tiedSet = new HashSet<>(tiedIds);

        // Page one row at a time across the tie; each id must appear exactly once, in order.
        List<String> firstThree = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Long beforeDate = null;
        String beforeId = null;
        for (int i = 0; i < 3; i++) {
            JsonObject json = fetchPage(token, docId, 1, beforeDate, beforeId);
            JsonArray logs = json.getJsonArray("logs");
            Assertions.assertEquals(1, logs.size(), "each single-row page returns exactly one row");
            String id = logs.getJsonObject(0).getString("id");
            Assertions.assertTrue(seen.add(id), "no tied row is returned twice across the boundary");
            firstThree.add(id);
            beforeDate = logs.getJsonObject(0).getJsonNumber("create_date").longValue();
            beforeId = id;
        }
        // All three tied rows were reached exactly once (none skipped, none duplicated).
        Assertions.assertEquals(tiedSet, new HashSet<>(firstThree), "every tied row is reached exactly once");

        // Deterministic order: the tiebreaker sorts equal timestamps by id DESC.
        List<String> expectedOrder = new ArrayList<>(tiedIds);
        expectedOrder.sort((a, b) -> b.compareTo(a));
        Assertions.assertEquals(expectedOrder, firstThree, "ties break by id descending");

        deleteUser(user);
    }

    /**
     * Paging with a small limit terminates across at least three pages and visits every row once —
     * the collected id set has no duplicate and its size equals the un-cursored total.
     */
    @Test
    public void testTerminationAcrossPagesNoDuplicateNoSkip() {
        String user = "auditpage_term";
        clientUtil.createUser(user);
        String token = clientUtil.login(user);
        String docId = createDocument(token, "termination document");
        String userId = userId(user);

        List<String> seeded = seedRows(docId, userId, FUTURE_BASE, 1000L, 7, AuditLogType.CREATE);

        List<String> collected = new ArrayList<>();
        int total = -1;
        int pages = 0;
        Long beforeDate = null;
        String beforeId = null;
        boolean hasMore;
        do {
            JsonObject json = fetchPage(token, docId, 2, beforeDate, beforeId);
            total = json.getJsonNumber("total").intValue();
            JsonArray logs = json.getJsonArray("logs");
            Assertions.assertTrue(logs.size() <= 2, "a page never exceeds the limit");
            for (int i = 0; i < logs.size(); i++) {
                collected.add(logs.getJsonObject(i).getString("id"));
            }
            if (logs.size() > 0) {
                JsonObject last = logs.getJsonObject(logs.size() - 1);
                beforeDate = last.getJsonNumber("create_date").longValue();
                beforeId = last.getString("id");
            }
            hasMore = json.getBoolean("has_more");
            pages++;
            Assertions.assertTrue(pages < 1000, "paging must terminate");
        } while (hasMore);

        Assertions.assertTrue(pages >= 3, "the walk spans at least three pages");
        Assertions.assertEquals(collected.size(), new HashSet<>(collected).size(), "no row is returned on two pages");
        Assertions.assertEquals(total, collected.size(), "every counted row is reachable by paging");
        Assertions.assertTrue(collected.containsAll(seeded), "all seeded rows are reachable");

        deleteUser(user);
    }

    /**
     * An audit entry inserted between two page loads causes no duplicate and leaves every older
     * entry reachable — the defining keyset property that an offset paginator cannot provide (a
     * newer insert shifts every offset down, repeating the previous page's boundary row).
     */
    @Test
    public void testInsertBetweenPagesNoDuplicateAllReachable() {
        String user = "auditpage_insert";
        clientUtil.createUser(user);
        String token = clientUtil.login(user);
        String docId = createDocument(token, "insert-between document");
        String userId = userId(user);

        List<String> seeded = seedRows(docId, userId, FUTURE_BASE, 1000L, 6, AuditLogType.CREATE);

        // Page 1 (the two newest seeded rows).
        JsonObject page1 = fetchPage(token, docId, 2, null, null);
        JsonArray firstLogs = page1.getJsonArray("logs");
        Assertions.assertEquals(2, firstLogs.size());
        List<String> collected = new ArrayList<>();
        for (int i = 0; i < firstLogs.size(); i++) {
            collected.add(firstLogs.getJsonObject(i).getString("id"));
        }
        JsonObject page1Last = firstLogs.getJsonObject(firstLogs.size() - 1);
        long beforeDate = page1Last.getJsonNumber("create_date").longValue();
        String beforeId = page1Last.getString("id");

        // Insert a NEWER row between the loads. Under offset paging this shifts every subsequent
        // page down by one; under keyset paging the cursor pins the boundary so it does not.
        seedRows(docId, userId, FUTURE_BASE + 10_000_000L, 0L, 1, AuditLogType.UPDATE);

        // Continue paging older via the page-1 cursor to the end.
        boolean hasMore;
        int pages = 1;
        do {
            JsonObject json = fetchPage(token, docId, 2, beforeDate, beforeId);
            JsonArray logs = json.getJsonArray("logs");
            for (int i = 0; i < logs.size(); i++) {
                collected.add(logs.getJsonObject(i).getString("id"));
            }
            if (logs.size() > 0) {
                JsonObject last = logs.getJsonObject(logs.size() - 1);
                beforeDate = last.getJsonNumber("create_date").longValue();
                beforeId = last.getString("id");
            }
            hasMore = json.getBoolean("has_more");
            pages++;
            Assertions.assertTrue(pages < 1000, "paging must terminate");
        } while (hasMore);

        Assertions.assertEquals(collected.size(), new HashSet<>(collected).size(),
                "a mid-paging insert must not duplicate any row");
        Assertions.assertTrue(collected.containsAll(seeded),
                "every older seeded entry remains reachable after the insert");

        deleteUser(user);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private String createDocument(String token, String title) {
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", title)
                        .param("language", "eng")
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        return json.getString("id");
    }

    private String userId(String username) {
        final String[] holder = new String[1];
        TransactionUtil.handle(() -> holder[0] = new UserDao().getActiveByUsername(username).getId());
        return holder[0];
    }

    /**
     * Persists {@code count} audit rows for the document, with create-dates {@code base + i*step}
     * (a step of 0 makes them all tied). Returns the generated ids in creation order.
     */
    private List<String> seedRows(String documentId, String userId, long base, long step, int count, AuditLogType type) {
        final List<String> ids = new ArrayList<>();
        TransactionUtil.handle(() -> {
            EntityManager em = ThreadLocalContext.get().getEntityManager();
            for (int i = 0; i < count; i++) {
                AuditLog log = new AuditLog();
                log.setId(UUID.randomUUID().toString());
                log.setUserId(userId);
                log.setEntityId(documentId);
                log.setEntityClass("Document");
                log.setType(type);
                log.setMessage("seeded-" + i);
                log.setCreateDate(new Date(base + i * step));
                em.persist(log);
                ids.add(log.getId());
            }
        });
        return ids;
    }

    private WebTarget pageTarget(String documentId, Integer limit, Long beforeDate, String beforeId) {
        WebTarget webTarget = target().path("/auditlog").queryParam("document", documentId);
        if (limit != null) {
            webTarget = webTarget.queryParam("limit", limit);
        }
        if (beforeDate != null) {
            webTarget = webTarget.queryParam("before_date", beforeDate);
        }
        if (beforeId != null) {
            webTarget = webTarget.queryParam("before_id", beforeId);
        }
        return webTarget;
    }

    private JsonObject fetchPage(String token, String documentId, Integer limit, Long beforeDate, String beforeId) {
        return pageTarget(documentId, limit, beforeDate, beforeId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
    }

    private Response rawPage(String token, String documentId, Integer limit, Long beforeDate, String beforeId) {
        return pageTarget(documentId, limit, beforeDate, beforeId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get();
    }

    private Response rawCursor(String token, String documentId, Long beforeDate, String beforeId) {
        return rawPage(token, documentId, null, beforeDate, beforeId);
    }

    // A raw before_date STRING (not a Long) so a non-numeric value exercises the resource's own
    // parse-and-400, not Jersey's query-param Long conversion (which would 404 before list() runs).
    private Response rawStringCursor(String token, String documentId, String beforeDate, String beforeId) {
        WebTarget webTarget = target().path("/auditlog").queryParam("document", documentId);
        if (beforeDate != null) {
            webTarget = webTarget.queryParam("before_date", beforeDate);
        }
        if (beforeId != null) {
            webTarget = webTarget.queryParam("before_id", beforeId);
        }
        return webTarget.request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, token).get();
    }

    // A raw limit STRING (not an Integer) so a non-numeric/overflow value exercises the resource's own
    // parse-and-default, not Jersey's Integer query-param conversion (which would 404 before list() runs).
    private Response rawStringLimit(String token, String documentId, String limit) {
        return target().path("/auditlog").queryParam("document", documentId).queryParam("limit", limit)
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, token).get();
    }

    private void deleteUser(String username) {
        String adminToken = adminToken();
        target().path("/user/" + username)
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }
}
