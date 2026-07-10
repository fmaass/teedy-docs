package com.sismics.docs.rest;

import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import com.sismics.util.jpa.EMF;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import java.sql.Timestamp;
import java.util.List;
import java.util.function.Function;

/**
 * Tests the search / index / audit re-entanglement of the workflow (route) feature: the
 * workflow search facet ({@code workflow:me} and {@code search[searchworkflow]=me}), the
 * doc-detail {@code route_step} block, the trash-cancel behaviour, and the route audit trail.
 *
 * @author teedy
 */
public class TestDocumentWorkflowSearch extends BaseJerseyTest {

    /**
     * Starts the seeded default-document-review route (all steps target the administrators group)
     * on a fresh admin document and returns the document ID.
     */
    private String startDefaultRouteOnNewDocument(String adminToken, String title) {
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", title)
                        .param("language", "eng")), JsonObject.class);
        String documentId = json.getString("id");

        target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", "default-document-review")), JsonObject.class);
        return documentId;
    }

    /**
     * The regression matrix: /document/list must not raise UnknownParameterException in any of the
     * four cells (admin / non-admin) × (with workflow filter / without). Admin-without-filter was
     * the retirement's 500 (placeholder/bind mismatch on the skip-ACL search path).
     */
    @Test
    public void testListRegressionMatrix() {
        String adminToken = adminToken();
        clientUtil.createUser("wfmatrix");
        String userToken = clientUtil.login("wfmatrix");

        // Cell 1: admin (skip-ACL search path) + workflow filter OFF. This exact cell was the
        // retirement's 500 (UnknownParameterException): :targetIdList was bound while no emitted SQL
        // referenced it. The HTTP-200 assertion here IS the live regression guard for that invariant.
        Response r1 = target().path("/document/list")
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).get();
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), r1.getStatus());

        // Cell 2: admin, with workflow filter
        Response r2 = target().path("/document/list")
                .queryParam("search[searchworkflow]", "me")
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).get();
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), r2.getStatus());

        // Cell 3: non-admin, no workflow filter
        Response r3 = target().path("/document/list")
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, userToken).get();
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), r3.getStatus());

        // Cell 4: non-admin, with workflow filter
        Response r4 = target().path("/document/list")
                .queryParam("search[searchworkflow]", "me")
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, userToken).get();
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), r4.getStatus());

        target().path("/user/wfmatrix").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).delete();
    }

    /**
     * {@code workflow:me} (free-text form) returns the document whose current step targets the
     * caller while the route is ACTIVE, and returns nothing for a caller who is not the target.
     */
    @Test
    public void testWorkflowMeFreeTextTargetsCaller() {
        String adminToken = adminToken();

        String documentId = startDefaultRouteOnNewDocument(adminToken, "Workflow me free-text");

        // Admin is a member of the administrators group, which is the step target -> included.
        JsonObject json = target().path("/document/list")
                .queryParam("search", "workflow:me")
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertTrue(listContainsDocument(json, documentId),
                "workflow:me must include a document whose active step targets the caller");

        // A non-target user must not see the document via the workflow facet.
        clientUtil.createUser("wfnontarget");
        String otherToken = clientUtil.login("wfnontarget");
        JsonObject otherJson = target().path("/document/list")
                .queryParam("search", "workflow:me")
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, otherToken)
                .get(JsonObject.class);
        Assertions.assertFalse(listContainsDocument(otherJson, documentId),
                "workflow:me must not include a document whose active step targets someone else");

        target().path("/user/wfnontarget").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).delete();
    }

    /**
     * A rejected route is terminal: the document must no longer appear in {@code workflow:me} for its
     * former target (terminal-route exclusion — the RTE_STATUS_C = 'ACTIVE' subquery hardening).
     */
    @Test
    public void testWorkflowMeExcludesTerminalRoute() {
        String adminToken = adminToken();

        // A single APPROVE step targeting the administrators group, rejectable.
        JsonObject json = target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "Terminal exclusion model")
                        .param("steps", "[{\"type\":\"APPROVE\",\"transitions\":[{\"name\":\"APPROVED\",\"actions\":[]},{\"name\":\"REJECTED\",\"actions\":[]}],\"target\":{\"name\":\"administrators\",\"type\":\"GROUP\"},\"name\":\"Only step\"}]")), JsonObject.class);
        String modelId = json.getString("id");

        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Terminal exclusion")
                        .param("language", "eng")), JsonObject.class);
        String documentId = json.getString("id");

        target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", modelId)), JsonObject.class);

        // While ACTIVE the document is included.
        JsonObject before = target().path("/document/list")
                .queryParam("search", "workflow:me")
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertTrue(listContainsDocument(before, documentId),
                "An active-route document must appear before rejection");

        // Reject -> route REJECTED (terminal).
        target().path("/route/validate").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("transition", "REJECTED")), JsonObject.class);

        JsonObject after = target().path("/document/list")
                .queryParam("search", "workflow:me")
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertFalse(listContainsDocument(after, documentId),
                "A rejected (terminal) route must be excluded from workflow:me");
    }

    /**
     * The {@code search[searchworkflow]=me} HTTP param drives the same facet as the free-text form.
     */
    @Test
    public void testSearchWorkflowHttpParam() {
        String adminToken = adminToken();

        String documentId = startDefaultRouteOnNewDocument(adminToken, "Workflow http param");

        JsonObject json = target().path("/document/list")
                .queryParam("search[searchworkflow]", "me")
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertTrue(listContainsDocument(json, documentId),
                "search[searchworkflow]=me must include a document whose active step targets the caller");
    }

    /**
     * The list output carries active_route=true and the current_step_name while a route is active.
     */
    @Test
    public void testListOutputActiveRouteFields() {
        String adminToken = adminToken();

        String documentId = startDefaultRouteOnNewDocument(adminToken, "List output fields");

        JsonObject json = target().path("/document/list")
                .queryParam("search[searchworkflow]", "me")
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonObject doc = findDocument(json, documentId);
        Assertions.assertNotNull(doc, "The active-route document must be in the list");
        Assertions.assertTrue(doc.getBoolean("active_route"), "active_route must be true");
        Assertions.assertEquals("Check the document's metadata", doc.getString("current_step_name"));
    }

    /**
     * The doc-detail GET exposes route_step with transitionable=true for a member of the step target
     * and transitionable=false for a reader who is not a member of the target.
     */
    @Test
    public void testDocDetailRouteStepTransitionable() {
        String adminToken = adminToken();

        // A single VALIDATE step targeting a specific user (not admin, not the reader).
        clientUtil.createUser("wfstepuser");
        clientUtil.createUser("wfreader");

        JsonObject json = target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "Transitionable model")
                        .param("steps", "[{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\",\"actions\":[]}],\"target\":{\"name\":\"wfstepuser\",\"type\":\"USER\"},\"name\":\"Only step\"}]")), JsonObject.class);
        String modelId = json.getString("id");

        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Transitionable detail")
                        .param("language", "eng")), JsonObject.class);
        String documentId = json.getString("id");

        // Grant the reader a standing READ ACL so it can GET the document (but it is not the target).
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("source", documentId)
                        .param("perm", "READ")
                        .param("target", "wfreader")
                        .param("type", "USER")), JsonObject.class);

        target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", modelId)), JsonObject.class);

        // The step target sees transitionable=true.
        String stepUserToken = clientUtil.login("wfstepuser");
        JsonObject targetDetail = target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, stepUserToken)
                .get(JsonObject.class);
        Assertions.assertTrue(targetDetail.containsKey("route_step"), "route_step must be present for the target");
        Assertions.assertTrue(targetDetail.getJsonObject("route_step").getBoolean("transitionable"),
                "transitionable must be true for a member of the step target");

        // A non-target reader sees transitionable=false.
        String readerToken = clientUtil.login("wfreader");
        JsonObject readerDetail = target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, readerToken)
                .get(JsonObject.class);
        Assertions.assertTrue(readerDetail.containsKey("route_step"), "route_step must be present for a reader");
        Assertions.assertFalse(readerDetail.getJsonObject("route_step").getBoolean("transitionable"),
                "transitionable must be false for a non-target reader");

        target().path("/user/wfstepuser").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).delete();
        target().path("/user/wfreader").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).delete();
    }

    /**
     * Trashing a document with an active route CANCELs the route (steps system-ended, NULL
     * transition), the route history stays intact via GET, and restoring the document does NOT
     * resurrect the route (it stays CANCELLED).
     */
    @Test
    public void testTrashCancelsActiveRoute() {
        String adminToken = adminToken();

        String documentId = startDefaultRouteOnNewDocument(adminToken, "Trash cancels route");

        // Trash the document.
        target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);

        // While trashed the document's own reads are gone; verify the trash listing still shows it.
        JsonObject trash = target().path("/document/trash")
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertTrue(listContainsDocument(trash, documentId),
                "The trashed document must appear in the trash listing");

        // The ROUTING ACL was soft-deleted with a timestamp DISTINCT from the document's own delete
        // timestamp — this is the exact invariant that stops restore() (which un-deletes the
        // document's ACLs by exact-equality match on the document delete timestamp) from resurrecting
        // it. If the two timestamps could collide, restore would re-expose the trashed doc.
        Long documentDeleteTs = readDocumentDeleteTimestamp(documentId);
        Long routingAclDeleteTs = readRoutingAclDeleteTimestamp(documentId);
        Assertions.assertNotNull(documentDeleteTs, "The trashed document must have a delete timestamp");
        Assertions.assertNotNull(routingAclDeleteTs, "The ROUTING ACL must be soft-deleted after trash-cancel");
        Assertions.assertNotEquals(documentDeleteTs, routingAclDeleteTs,
                "The ROUTING ACL delete timestamp must differ from the document's, so restore cannot match it");

        // Restore the document so the route history is readable again via GET.
        target().path("/document/" + documentId + "/restore").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()), JsonObject.class);

        // The route is CANCELLED (no resurrection) with all steps system-ended (closed, NULL
        // transition); history intact via GET.
        JsonObject json = target().path("/route")
                .queryParam("documentId", documentId)
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonArray routes = json.getJsonArray("routes");
        Assertions.assertEquals(1, routes.size(), "The route history must survive trash + restore");
        JsonObject route = routes.getJsonObject(0);
        JsonArray steps = route.getJsonArray("steps");
        Assertions.assertEquals(3, steps.size(), "All steps must remain listable");
        for (int i = 0; i < steps.size(); i++) {
            Assertions.assertFalse(steps.getJsonObject(i).isNull("end_date"),
                    "Every step must be closed after trash-cancel");
            Assertions.assertTrue(steps.getJsonObject(i).isNull("transition"),
                    "A system-ended step must not carry a user-action transition");
        }

        // The document is readable again, but the route stays CANCELLED (no resurrection): the
        // doc-detail exposes no active route_step.
        JsonObject detail = target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertFalse(detail.containsKey("route_step"),
                "A restored document must not resurrect the cancelled route's current step");

        // The ROUTING ACL is still soft-deleted after restore: the deterministic timestamp offset
        // means restore()'s exact-equality un-delete could not match it.
        Assertions.assertEquals(0, readActiveRoutingAclCount(documentId),
                "A restored document must NOT have its cancelled route's ROUTING ACL resurrected");

        // The workflow facet must not list the restored document (route is not ACTIVE).
        JsonObject facet = target().path("/document/list")
                .queryParam("search[searchworkflow]", "me")
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertFalse(listContainsDocument(facet, documentId),
                "A restored document with a cancelled route must not appear in workflow:me");
    }

    /**
     * The document's audit trail includes the route rows (route CREATE on start and route UPDATE on
     * validate) via the restored AuditLogDao route UNION branch.
     */
    @Test
    public void testAuditTrailIncludesRouteRows() {
        String adminToken = adminToken();

        String documentId = startDefaultRouteOnNewDocument(adminToken, "Audit trail routes");

        // Validate the first step (a route UPDATE is audited on validate).
        target().path("/route/validate").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("transition", "VALIDATED")), JsonObject.class);

        JsonObject json = target().path("/auditlog")
                .queryParam("document", documentId)
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonArray logs = json.getJsonArray("logs");
        int routeLogCount = 0;
        for (int i = 0; i < logs.size(); i++) {
            if ("Route".equals(logs.getJsonObject(i).getString("class"))) {
                routeLogCount++;
            }
        }
        Assertions.assertTrue(routeLogCount >= 1,
                "The document audit trail must include at least one Route row (create/update)");
    }

    // --- helpers ---

    private boolean listContainsDocument(JsonObject listResponse, String documentId) {
        return findDocument(listResponse, documentId) != null;
    }

    private JsonObject findDocument(JsonObject listResponse, String documentId) {
        JsonArray documents = listResponse.getJsonArray("documents");
        for (int i = 0; i < documents.size(); i++) {
            if (documentId.equals(documents.getJsonObject(i).getString("id"))) {
                return documents.getJsonObject(i);
            }
        }
        return null;
    }

    /**
     * The document's own soft-delete timestamp (epoch millis), or null if not deleted.
     */
    private Long readDocumentDeleteTimestamp(String documentId) {
        return readDb(em -> {
            List<?> rows = em.createNativeQuery(
                            "select DOC_DELETEDATE_D from T_DOCUMENT where DOC_ID_C = :id")
                    .setParameter("id", documentId).getResultList();
            if (rows.isEmpty() || rows.get(0) == null) {
                return null;
            }
            return ((Timestamp) rows.get(0)).getTime();
        });
    }

    /**
     * The delete timestamp (epoch millis) of the document's soft-deleted ROUTING ACL, or null if
     * none is soft-deleted.
     */
    private Long readRoutingAclDeleteTimestamp(String documentId) {
        return readDb(em -> {
            List<?> rows = em.createNativeQuery(
                            "select ACL_DELETEDATE_D from T_ACL where ACL_SOURCEID_C = :id " +
                                    "and ACL_TYPE_C = 'ROUTING' and ACL_DELETEDATE_D is not null")
                    .setParameter("id", documentId).getResultList();
            if (rows.isEmpty() || rows.get(0) == null) {
                return null;
            }
            return ((Timestamp) rows.get(0)).getTime();
        });
    }

    /**
     * The count of still-active (non-deleted) ROUTING ACLs on the document.
     */
    private long readActiveRoutingAclCount(String documentId) {
        return readDb(em -> ((Number) em.createNativeQuery(
                        "select count(*) from T_ACL where ACL_SOURCEID_C = :id " +
                                "and ACL_TYPE_C = 'ROUTING' and ACL_DELETEDATE_D is null")
                .setParameter("id", documentId).getSingleResult()).longValue());
    }

    /**
     * Run a read-only query in its own short-lived EntityManager, restoring the prior thread context.
     */
    private <T> T readDb(Function<EntityManager, T> fn) {
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext previous = ThreadLocalContext.get();
        ThreadLocalContext context = ThreadLocalContext.get();
        context.setEntityManager(em);
        try {
            return fn.apply(em);
        } finally {
            if (em.isOpen()) {
                em.close();
            }
            if (previous != null) {
                previous.setEntityManager(null);
            }
        }
    }
}
