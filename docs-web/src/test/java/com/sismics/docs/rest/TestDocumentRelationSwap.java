package com.sismics.docs.rest;

import com.sismics.docs.core.dao.RelationDao;
import com.sismics.docs.core.model.jpa.Relation;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.UUID;

/**
 * Contract tests for {@code POST /document/relation/swap} (#191): reversing the direction of the link
 * between two documents.
 *
 * <p>The schema carries no unique constraint on {@code (REL_IDDOCFROM_C, REL_IDDOCTO_C)}
 * (dbupdate-007-0.sql), so duplicate rows and both directions at once are representable states. All four
 * are pinned here — both directions collapse onto the reverse row, a lone forward direction flips and
 * absorbs its duplicates, an already-reversed pair is left untouched and still answers 200, and unrelated
 * documents are a 404 — together with the endpoint's non-disclosive 404 discipline: an unwritable or
 * unknown document on EITHER side is reported exactly like a missing one, as {@code POST /file/reorder}
 * does and unlike {@code POST /file/:id/move}'s 403.</p>
 */
public class TestDocumentRelationSwap extends BaseJerseyTest {

    private static final String COOKIE = TokenBasedSecurityFilter.COOKIE_NAME;
    private static final String UNKNOWN_ID = "00000000-0000-0000-0000-000000000000";

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Fire the swap and return the raw response, so the status can be asserted. */
    private Response swapRequest(String documentId, String targetDocumentId, String token) {
        return target().path("/document/relation/swap").request()
                .cookie(COOKIE, token)
                .post(Entity.form(new Form().param("id", documentId).param("target", targetDocumentId)));
    }

    /** Fire the swap and require a 200. */
    private void swapOk(String documentId, String targetDocumentId, String token) throws Exception {
        Response response = swapRequest(documentId, targetDocumentId, token);
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()),
                "the swap must succeed");
        awaitAsyncQuiescence("the swap publishes a document-updated event for BOTH documents");
    }

    /** Point {@code fromId} at {@code toId} through the real relation-writing endpoint. */
    private void link(String fromId, String toId, String token) throws Exception {
        target().path("/document/" + fromId).request()
                .cookie(COOKIE, token)
                .post(Entity.form(new Form()
                        .param("title", "Document Title")
                        .param("language", "eng")
                        .param("relations", toId)), JsonObject.class);
        awaitAsyncQuiescence("the relation write publishes a document-updated event");
    }

    /**
     * Insert an EXTRA active row for an ordered pair, bypassing the endpoint. The API deduplicates
     * through a Set, so a duplicate is only reachable by writing the row directly — but the schema allows
     * it, and rows written by older versions or by a lost race can look exactly like this.
     */
    private void seedDuplicateRow(String fromId, String toId) {
        TestUserResource.inTx(() -> {
            Relation relation = new Relation();
            relation.setId(UUID.randomUUID().toString());
            relation.setFromDocumentId(fromId);
            relation.setToDocumentId(toId);
            ThreadLocalContext.get().getEntityManager().persist(relation);
            return null;
        });
    }

    /** Active row count for an ordered pair, read straight from the table. */
    private int activeRows(String fromId, String toId) {
        return TestUserResource.inTx(() -> new RelationDao().getActiveBetween(fromId, toId).size());
    }

    /**
     * Assert that exactly one active relation exists between the two documents and that it points from
     * {@code expectedFromId}, as seen BOTH in the table and through the document read model.
     */
    private void assertSingleRelation(String expectedFromId, String expectedToId, String token) {
        Assertions.assertEquals(1, activeRows(expectedFromId, expectedToId),
                "exactly one active row in the expected direction");
        Assertions.assertEquals(0, activeRows(expectedToId, expectedFromId),
                "no active row in the opposite direction");

        JsonArray fromRelations = readDocument(expectedFromId, token).getJsonArray("relations");
        Assertions.assertEquals(1, fromRelations.size(), "the source document lists one relation");
        Assertions.assertEquals(expectedToId, fromRelations.getJsonObject(0).getString("id"));
        Assertions.assertTrue(fromRelations.getJsonObject(0).getBoolean("source"),
                "the source document sees itself as the source of the link");

        JsonArray toRelations = readDocument(expectedToId, token).getJsonArray("relations");
        Assertions.assertEquals(1, toRelations.size(), "the target document lists one relation");
        Assertions.assertEquals(expectedFromId, toRelations.getJsonObject(0).getString("id"));
        Assertions.assertFalse(toRelations.getJsonObject(0).getBoolean("source"),
                "the target document sees itself as the destination of the link");
    }

    private JsonObject readDocument(String documentId, String token) {
        return target().path("/document/" + documentId).request()
                .cookie(COOKIE, token)
                .get(JsonObject.class);
    }

    /** Grant a permission on a document to another user, through the real ACL endpoint. */
    private void grant(String documentId, String perm, String targetUsername, String ownerToken) {
        target().path("/acl").request()
                .cookie(COOKIE, ownerToken)
                .put(Entity.form(new Form()
                        .param("source", documentId)
                        .param("perm", perm)
                        .param("target", targetUsername)
                        .param("type", "USER")), JsonObject.class);
    }

    // ---------------------------------------------------------------------------------------------
    // The four representable states (F2 contract)
    // ---------------------------------------------------------------------------------------------

    /**
     * ONLY FORWARD — the ordinary case: the single active row is flipped in place, so the documents swap
     * roles in both read models. Swapping again is a no-op (the "only reverse" branch), which is what
     * makes a retried request harmless.
     */
    @Test
    public void testForwardOnlyFlips() throws Exception {
        clientUtil.createUser("relswap_flip");
        String token = clientUtil.login("relswap_flip");
        String docA = clientUtil.createDocument(token);
        String docB = clientUtil.createDocument(token);
        link(docA, docB, token);
        assertSingleRelation(docA, docB, token);

        swapOk(docA, docB, token);
        assertSingleRelation(docB, docA, token);

        // Idempotent: the pair already points the requested way, so the second call writes nothing.
        swapOk(docA, docB, token);
        assertSingleRelation(docB, docA, token);
    }

    /**
     * BOTH DIRECTIONS ACTIVE — the reverse row already expresses the requested direction, so every
     * forward row is dropped rather than flipped into a duplicate. The pair ends on exactly one row.
     */
    @Test
    public void testBothDirectionsCollapseOntoTheReverseRow() throws Exception {
        clientUtil.createUser("relswap_both");
        String token = clientUtil.login("relswap_both");
        String docA = clientUtil.createDocument(token);
        String docB = clientUtil.createDocument(token);
        link(docA, docB, token);
        link(docB, docA, token);
        Assertions.assertEquals(1, activeRows(docA, docB), "the forward row exists before the swap");
        Assertions.assertEquals(1, activeRows(docB, docA), "the reverse row exists before the swap");

        swapOk(docA, docB, token);
        assertSingleRelation(docB, docA, token);
    }

    /**
     * ONLY REVERSE — nothing to do: the endpoint answers 200 and leaves the row untouched (same row id,
     * not a delete-and-recreate).
     */
    @Test
    public void testReverseOnlyIsUnchanged() throws Exception {
        clientUtil.createUser("relswap_rev");
        String token = clientUtil.login("relswap_rev");
        String docA = clientUtil.createDocument(token);
        String docB = clientUtil.createDocument(token);
        link(docB, docA, token);
        String rowIdBefore = TestUserResource.inTx(
                () -> new RelationDao().getActiveBetween(docB, docA).get(0).getId());

        swapOk(docA, docB, token);

        assertSingleRelation(docB, docA, token);
        String rowIdAfter = TestUserResource.inTx(
                () -> new RelationDao().getActiveBetween(docB, docA).get(0).getId());
        Assertions.assertEquals(rowIdBefore, rowIdAfter, "the existing row is left in place, not rewritten");
    }

    /**
     * NEITHER DIRECTION — the two documents are unrelated, so there is nothing to reverse: a 404, never a
     * 200 that would suggest a link exists.
     */
    @Test
    public void testUnrelatedDocumentsAreNotFound() throws Exception {
        clientUtil.createUser("relswap_none");
        String token = clientUtil.login("relswap_none");
        String docA = clientUtil.createDocument(token);
        String docB = clientUtil.createDocument(token);

        Assertions.assertEquals(Status.NOT_FOUND,
                Status.fromStatusCode(swapRequest(docA, docB, token).getStatus()),
                "unrelated documents have no relation to reverse");
        Assertions.assertEquals(0, activeRows(docA, docB));
        Assertions.assertEquals(0, activeRows(docB, docA));
    }

    /**
     * FORWARD DUPLICATES — the schema permits several active rows for the same ordered pair. One is
     * flipped and the rest are collapsed onto it, so the swap is a repair rather than a duplication.
     */
    @Test
    public void testForwardDuplicatesCollapseOntoOneFlippedRow() throws Exception {
        clientUtil.createUser("relswap_dup");
        String token = clientUtil.login("relswap_dup");
        String docA = clientUtil.createDocument(token);
        String docB = clientUtil.createDocument(token);
        link(docA, docB, token);
        seedDuplicateRow(docA, docB);
        seedDuplicateRow(docA, docB);
        Assertions.assertEquals(3, activeRows(docA, docB), "three active forward rows before the swap");

        swapOk(docA, docB, token);
        assertSingleRelation(docB, docA, token);
    }

    /**
     * A document is never related to itself, so a self-swap has nothing to reverse.
     */
    @Test
    public void testSelfSwapIsNotFound() throws Exception {
        clientUtil.createUser("relswap_self");
        String token = clientUtil.login("relswap_self");
        String docA = clientUtil.createDocument(token);

        Assertions.assertEquals(Status.NOT_FOUND,
                Status.fromStatusCode(swapRequest(docA, docA, token).getStatus()));
    }

    // ---------------------------------------------------------------------------------------------
    // 404 discipline
    // ---------------------------------------------------------------------------------------------

    /**
     * An unknown document on either side is a 404, whichever side it is named on.
     */
    @Test
    public void testUnknownDocumentIsNotFound() throws Exception {
        clientUtil.createUser("relswap_unknown");
        String token = clientUtil.login("relswap_unknown");
        String docA = clientUtil.createDocument(token);
        String docB = clientUtil.createDocument(token);
        link(docA, docB, token);

        Assertions.assertEquals(Status.NOT_FOUND,
                Status.fromStatusCode(swapRequest(UNKNOWN_ID, docB, token).getStatus()),
                "unknown document on the from side");
        Assertions.assertEquals(Status.NOT_FOUND,
                Status.fromStatusCode(swapRequest(docA, UNKNOWN_ID, token).getStatus()),
                "unknown document on the target side");

        // The relation is untouched by either rejection.
        assertSingleRelation(docA, docB, token);
    }

    /**
     * WRITE on ONE side only is a 404, not a 403 — the endpoint reports an unwritable document exactly
     * like an absent one, so it cannot be used to probe for documents outside the caller's reach. Both
     * orientations are exercised: the writable document named first, then named second.
     */
    @Test
    public void testWriteOnOneSideOnlyIsNotFound() throws Exception {
        clientUtil.createUser("relswap_owner");
        clientUtil.createUser("relswap_partial");
        String ownerToken = clientUtil.login("relswap_owner");
        String partialToken = clientUtil.login("relswap_partial");

        String docA = clientUtil.createDocument(ownerToken);
        String docB = clientUtil.createDocument(ownerToken);
        link(docA, docB, ownerToken);

        // The other user may WRITE A and only READ B.
        grant(docA, "READ", "relswap_partial", ownerToken);
        grant(docA, "WRITE", "relswap_partial", ownerToken);
        grant(docB, "READ", "relswap_partial", ownerToken);

        Assertions.assertEquals(Status.NOT_FOUND,
                Status.fromStatusCode(swapRequest(docA, docB, partialToken).getStatus()),
                "no WRITE on the target side");
        Assertions.assertEquals(Status.NOT_FOUND,
                Status.fromStatusCode(swapRequest(docB, docA, partialToken).getStatus()),
                "no WRITE on the from side");

        // Nothing was written by either rejected call.
        assertSingleRelation(docA, docB, ownerToken);

        // With WRITE on both, the same caller succeeds — so the 404s above are the permission check
        // talking, not a broken route.
        grant(docB, "WRITE", "relswap_partial", ownerToken);
        swapOk(docA, docB, partialToken);
        assertSingleRelation(docB, docA, ownerToken);
    }

    /**
     * A caller with no permission at all on either document gets the same 404 — never a 403 that would
     * confirm the documents exist.
     */
    @Test
    public void testStrangerIsNotFound() throws Exception {
        clientUtil.createUser("relswap_owner2");
        clientUtil.createUser("relswap_stranger");
        String ownerToken = clientUtil.login("relswap_owner2");
        String strangerToken = clientUtil.login("relswap_stranger");

        String docA = clientUtil.createDocument(ownerToken);
        String docB = clientUtil.createDocument(ownerToken);
        link(docA, docB, ownerToken);

        Assertions.assertEquals(Status.NOT_FOUND,
                Status.fromStatusCode(swapRequest(docA, docB, strangerToken).getStatus()));
        assertSingleRelation(docA, docB, ownerToken);
    }

    // ---------------------------------------------------------------------------------------------
    // Edge validation
    // ---------------------------------------------------------------------------------------------

    /**
     * An anonymous caller is rejected before any document is resolved.
     */
    @Test
    public void testAnonymousIsForbidden() {
        Response response = target().path("/document/relation/swap").request()
                .post(Entity.form(new Form().param("id", UNKNOWN_ID).param("target", UNKNOWN_ID)));
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()));
    }

    /**
     * Both form parameters are required.
     */
    @Test
    public void testMissingParametersAreBadRequests() throws Exception {
        clientUtil.createUser("relswap_params");
        String token = clientUtil.login("relswap_params");
        String docA = clientUtil.createDocument(token);

        Response noTarget = target().path("/document/relation/swap").request()
                .cookie(COOKIE, token)
                .post(Entity.form(new Form().param("id", docA)));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(noTarget.getStatus()));

        Response noId = target().path("/document/relation/swap").request()
                .cookie(COOKIE, token)
                .post(Entity.form(new Form().param("target", docA)));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(noId.getStatus()));
    }
}
