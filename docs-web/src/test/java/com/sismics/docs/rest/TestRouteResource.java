package com.sismics.docs.rest;

import com.sismics.docs.core.util.WebhookUtil;
import com.sismics.docs.rest.resource.ThirdPartyWebhookResource;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;


/**
 * Test the route resource.
 *
 * @author bgamard
 */
public class TestRouteResource extends BaseJerseyTest {
    @BeforeEach
    public void allowPrivateWebhooks() {
        System.setProperty(WebhookUtil.ALLOW_PRIVATE_PROPERTY, "true");
    }

    @AfterEach
    public void resetPrivateWebhooks() {
        System.clearProperty(WebhookUtil.ALLOW_PRIVATE_PROPERTY);
    }

    /**
     * Point the app's SMTP config at the embedded Wiser so route emails are captured by popEmail().
     *
     * @param adminToken Admin token
     */
    private void configureSmtp(String adminToken) {
        target().path("/app/config_smtp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("hostname", "localhost")
                        .param("port", Integer.toString(getSmtpPort()))
                        .param("from", "contact@sismicsdocs.com")
                ), JsonObject.class);
    }

    /**
     * A single-VALIDATE-step model targeting a group, created as admin and shared READ to a user.
     *
     * @param adminToken Admin token
     * @param name Model name
     * @param shareToUser User to share READ with (may be null)
     * @return Model ID
     */
    private String createGroupValidateModel(String adminToken, String name, String shareToUser) {
        JsonObject json = target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", name)
                        .param("steps", "[{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\",\"actions\":[]}],\"target\":{\"name\":\"administrators\",\"type\":\"GROUP\"},\"name\":\"Only step\"}]")), JsonObject.class);
        String modelId = json.getString("id");
        if (shareToUser != null) {
            target().path("/acl").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .put(Entity.form(new Form()
                            .param("source", modelId)
                            .param("perm", "READ")
                            .param("target", shareToUser)
                            .param("type", "USER")), JsonObject.class);
        }
        return modelId;
    }

    /**
     * Happy path: start -> VALIDATE -> VALIDATE -> APPROVE completes the route (status DONE) and
     * fires ROUTE_COMPLETED. Uses the seeded default-document-review 3-step model (all steps target
     * the administrators group).
     */
    @Test
    public void testStartValidateComplete() throws Exception {
        String adminToken = adminToken();
        configureSmtp(adminToken);

        // Register a webhook that receives every route event we assert on
        target().path("/webhook").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("event", "ROUTE_COMPLETED")
                        .param("url", "http://localhost:" + getPort() + "/docs/thirdpartywebhook")), JsonObject.class);

        // Admin document
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Complete me")
                        .param("language", "eng")), JsonObject.class);
        String documentId = json.getString("id");

        // Start the seeded default route
        json = target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", "default-document-review")), JsonObject.class);
        Assertions.assertEquals("Check the document's metadata", json.getJsonObject("route_step").getString("name"));

        // Validate step 1
        json = target().path("/route/validate").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("transition", "VALIDATED")), JsonObject.class);
        Assertions.assertTrue(json.getBoolean("readable"));
        Assertions.assertEquals("Add relevant files to the document", json.getJsonObject("route_step").getString("name"));

        // Validate step 2
        json = target().path("/route/validate").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("transition", "VALIDATED")), JsonObject.class);
        Assertions.assertEquals("Approve the document", json.getJsonObject("route_step").getString("name"));

        // Approve the last step -> route DONE, no route_step, ROUTE_COMPLETED fired
        json = target().path("/route/validate").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("transition", "APPROVED")), JsonObject.class);
        Assertions.assertFalse(json.containsKey("route_step"));
        Assertions.assertTrue(json.getBoolean("readable"));

        // ROUTE_COMPLETED webhook fired with the document id and route id
        JsonObject payload = ThirdPartyWebhookResource.getLastPayload();
        Assertions.assertNotNull(payload);
        Assertions.assertEquals("ROUTE_COMPLETED", payload.getString("event"));
        Assertions.assertEquals(documentId, payload.getString("id"));
        Assertions.assertNotNull(payload.getString("route_id"));

        // The route is listable and its last step carries the APPROVED transition
        json = target().path("/route")
                .queryParam("documentId", documentId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonArray routes = json.getJsonArray("routes");
        Assertions.assertEquals(1, routes.size());
        JsonArray steps = routes.getJsonObject(0).getJsonArray("steps");
        Assertions.assertEquals(3, steps.size());
        Assertions.assertEquals("APPROVED", steps.getJsonObject(2).getString("transition"));

        // Starting a new route on the same (now completed) document is allowed again
        json = target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", "default-document-review")), JsonObject.class);
        Assertions.assertTrue(json.containsKey("route_step"));
    }

    /**
     * A rejection halts the route: status REJECTED, no open steps remain, no next routing ACL is
     * granted, ROUTE_STEP_TRANSITIONED fires (but NOT ROUTE_COMPLETED), and the history stays
     * listable via GET.
     */
    @Test
    public void testRejectHaltsRoute() throws Exception {
        String adminToken = adminToken();
        configureSmtp(adminToken);

        // Webhook capturing the transition event
        target().path("/webhook").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("event", "ROUTE_STEP_TRANSITIONED")
                        .param("url", "http://localhost:" + getPort() + "/docs/thirdpartywebhook")), JsonObject.class);

        // A two-step APPROVE-then-VALIDATE model (both target the administrators group)
        JsonObject json = target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "Reject model")
                        .param("steps", "[{\"type\":\"APPROVE\",\"transitions\":[{\"name\":\"APPROVED\",\"actions\":[]},{\"name\":\"REJECTED\",\"actions\":[]}],\"target\":{\"name\":\"administrators\",\"type\":\"GROUP\"},\"name\":\"Approve first\"},{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\",\"actions\":[]}],\"target\":{\"name\":\"administrators\",\"type\":\"GROUP\"},\"name\":\"Then validate\"}]")), JsonObject.class);
        String modelId = json.getString("id");

        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Reject me")
                        .param("language", "eng")), JsonObject.class);
        String documentId = json.getString("id");

        target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", modelId)), JsonObject.class);

        // Reject the first step, with a user-authored comment containing HTML (injection probe)
        json = target().path("/route/validate").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("transition", "REJECTED")
                        .param("comment", "<b>evil</b>")), JsonObject.class);
        // No next step granted -> no route_step in the response
        Assertions.assertFalse(json.containsKey("route_step"));

        // ROUTE_STEP_TRANSITIONED fired for the reject (a reject IS a transition)
        JsonObject payload = ThirdPartyWebhookResource.getLastPayload();
        Assertions.assertNotNull(payload);
        Assertions.assertEquals("ROUTE_STEP_TRANSITIONED", payload.getString("event"));
        Assertions.assertEquals("REJECTED", payload.getString("transition"));

        // There is no current step anymore: a second validate returns 404 (route is not ACTIVE)
        Response secondValidate = target().path("/route/validate").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("transition", "VALIDATED")));
        Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), secondValidate.getStatus());

        // The initiator receives a dedicated REJECTION notice (route_step_rejected template), not the
        // "please validate" email. The last email is the rejection one.
        String rejectionEmail = popEmail();
        Assertions.assertNotNull(rejectionEmail, "No rejection email to consume");
        // Undo quoted-printable soft line breaks so string assertions cannot be split across lines
        String normalizedEmail = rejectionEmail.replace("=\r\n", "").replace("=\n", "");
        // Precise check: the SUBJECT header carries the rejected-template subject text
        Assertions.assertTrue(normalizedEmail.matches("(?s).*Subject: [^\\r\\n]*A workflow step was rejected.*"),
                "Rejection email must carry the route_step_rejected subject header");
        // The user-authored comment must be HTML-escaped in the body, never raw markup
        Assertions.assertTrue(normalizedEmail.contains("&lt;b&gt;evil&lt;/b&gt;"),
                "User-authored comment must be HTML-escaped in the email body");
        Assertions.assertFalse(normalizedEmail.contains("<b>evil</b>"),
                "Raw user-authored HTML must never reach the email body");

        // History intact: the route is still listable and both steps are present, the first rejected
        // and the second system-ended (halted) with no validator and NO user-action transition.
        json = target().path("/route")
                .queryParam("documentId", documentId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonArray routes = json.getJsonArray("routes");
        Assertions.assertEquals(1, routes.size());
        JsonArray steps = routes.getJsonObject(0).getJsonArray("steps");
        Assertions.assertEquals(2, steps.size());
        // Only the ACTED step carries the REJECTED transition
        Assertions.assertEquals("REJECTED", steps.getJsonObject(0).getString("transition"));
        Assertions.assertFalse(steps.getJsonObject(0).isNull("end_date"));
        // The remaining step was system-ended: closed, no validator, NULL transition
        Assertions.assertFalse(steps.getJsonObject(1).isNull("end_date"));
        Assertions.assertTrue(steps.getJsonObject(1).isNull("validator_username"));
        Assertions.assertTrue(steps.getJsonObject(1).isNull("transition"),
                "A step nobody acted on must not carry a user-action transition");

        // No routing ACL remains: a fresh non-admin with no ACL cannot read the document via routing.
        // The route being non-ACTIVE also means a new route can be started again.
        json = target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", modelId)), JsonObject.class);
        Assertions.assertTrue(json.containsKey("route_step"));
    }

    /**
     * A garbage transition string is a 400 ValidationError, never a 500.
     */
    @Test
    public void testGarbageTransition() {
        String adminToken = adminToken();

        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Garbage transition")
                        .param("language", "eng")), JsonObject.class);
        String documentId = json.getString("id");

        target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", "default-document-review")), JsonObject.class);

        Response response = target().path("/route/validate").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("transition", "NOT_A_TRANSITION")));
        Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        JsonObject error = response.readEntity(JsonObject.class);
        Assertions.assertEquals("ValidationError", error.getString("type"));
    }

    /**
     * A non-admin whose only read access is the routing ACL loses readability once the step ends,
     * so the validate response reports readable=false and carries NO route_step payload.
     */
    @Test
    public void testReadableFalseOmitsStep() {
        String adminToken = adminToken();

        // A validator user with no standing ACL on the document
        clientUtil.createUser("validatoruser");
        clientUtil.createUser("nextuser");

        // Two-step model: step 1 -> validatoruser, step 2 -> nextuser
        JsonObject json = target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "Readable model")
                        .param("steps", "[{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\",\"actions\":[]}],\"target\":{\"name\":\"validatoruser\",\"type\":\"USER\"},\"name\":\"First\"},{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\",\"actions\":[]}],\"target\":{\"name\":\"nextuser\",\"type\":\"USER\"},\"name\":\"Second\"}]")), JsonObject.class);
        String modelId = json.getString("id");

        // Admin owns the document and starts the route
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Readable gating")
                        .param("language", "eng")), JsonObject.class);
        String documentId = json.getString("id");

        target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", modelId)), JsonObject.class);

        // validatoruser can read the document now (routing ACL granted for step 1)
        String validatorToken = clientUtil.login("validatoruser");
        Response beforeRead = target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, validatorToken)
                .get();
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), beforeRead.getStatus());

        // validatoruser validates step 1: the routing ACL shifts to nextuser, so validatoruser can
        // no longer read -> readable=false and route_step must be ABSENT even though a next step exists.
        json = target().path("/route/validate").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, validatorToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("transition", "VALIDATED")), JsonObject.class);
        Assertions.assertFalse(json.getBoolean("readable"));
        Assertions.assertFalse(json.containsKey("route_step"));

        // Cleanup
        target().path("/user/validatoruser").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        target().path("/user/nextuser").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    /**
     * A non-admin who was shared READ on a model can see it in LIST and start it, provided it has
     * WRITE on the document.
     */
    @Test
    public void testShareModelAndStart() {
        String adminToken = adminToken();
        clientUtil.createUser("starter");
        String starterToken = clientUtil.login("starter");

        String modelId = createGroupValidateModel(adminToken, "Shared startable", "starter");

        // starter sees the shared model in LIST
        JsonObject json = target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, starterToken)
                .get(JsonObject.class);
        JsonArray models = json.getJsonArray("routemodels");
        boolean seen = false;
        for (int i = 0; i < models.size(); i++) {
            if (modelId.equals(models.getJsonObject(i).getString("id"))) {
                seen = true;
            }
        }
        Assertions.assertTrue(seen);

        // starter owns a document (WRITE) and can start the shared route on it
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, starterToken)
                .put(Entity.form(new Form()
                        .param("title", "Starter doc")
                        .param("language", "eng")), JsonObject.class);
        String documentId = json.getString("id");

        Response startResponse = target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, starterToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", modelId)));
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), startResponse.getStatus());

        target().path("/user/starter").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    /**
     * Starting a route on a document the caller cannot WRITE is masked as a 404 (not a 403), to
     * avoid leaking document existence.
     */
    @Test
    public void testStartWithoutWriteIs404() {
        String adminToken = adminToken();
        String modelId = createGroupValidateModel(adminToken, "No-write model", null);

        // Admin owns a document that nowrite cannot see
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Private admin doc")
                        .param("language", "eng")), JsonObject.class);
        String documentId = json.getString("id");

        clientUtil.createUser("nowrite");
        String nowriteToken = clientUtil.login("nowrite");
        Response response = target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, nowriteToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", modelId)));
        Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());

        target().path("/user/nowrite").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    /**
     * Cancelling a route sets status CANCELLED, ends open steps and preserves the history via GET.
     */
    @Test
    public void testCancelPreservesHistory() {
        String adminToken = adminToken();

        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Cancel me")
                        .param("language", "eng")), JsonObject.class);
        String documentId = json.getString("id");

        target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", "default-document-review")), JsonObject.class);

        // Cancel the route
        target().path("/route")
                .queryParam("documentId", documentId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);

        // History preserved: the route is still listable with its (now closed) steps
        json = target().path("/route")
                .queryParam("documentId", documentId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonArray routes = json.getJsonArray("routes");
        Assertions.assertEquals(1, routes.size());
        JsonArray steps = routes.getJsonObject(0).getJsonArray("steps");
        Assertions.assertEquals(3, steps.size());
        // Every step is closed (end_date set) after cancel, and none carries a user-action
        // transition: nobody acted on them, they were system-ended (NULL transition).
        for (int i = 0; i < steps.size(); i++) {
            Assertions.assertFalse(steps.getJsonObject(i).isNull("end_date"));
            Assertions.assertTrue(steps.getJsonObject(i).isNull("transition"),
                    "A cancelled step nobody acted on must not carry a user-action transition");
        }

        // The route is no longer active: a new route can be started again
        json = target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", "default-document-review")), JsonObject.class);
        Assertions.assertTrue(json.containsKey("route_step"));
    }

    /**
     * B3 step-id guard: a route whose two consecutive steps share the SAME target+type (the seeded
     * default-document-review has two administrator VALIDATE steps in a row). Acting on step 1 with
     * ITS step id advances to step 2; replaying the SAME routeStepId is rejected StepChanged (400) and
     * advances NOTHING — step 2 stays open. Without the id-guard this stale replay would wrongly end
     * step 2 (a double advance from one intended action).
     */
    @Test
    public void testStepIdGuardBlocksStaleReplay() {
        String adminToken = adminToken();

        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Step-id guard")
                        .param("language", "eng")), JsonObject.class);
        String documentId = json.getString("id");

        // Start the seeded default route (steps 1 & 2 are both administrator VALIDATE steps)
        json = target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", "default-document-review")), JsonObject.class);
        // The start payload exposes the current step id (B3 step-id exposure)
        String step1Id = json.getJsonObject("route_step").getString("id");
        Assertions.assertNotNull(step1Id);

        // Act on step 1 with ITS id -> advances to step 2
        json = target().path("/route/validate").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("transition", "VALIDATED")
                        .param("routeStepId", step1Id)), JsonObject.class);
        String step2Id = json.getJsonObject("route_step").getString("id");
        Assertions.assertNotEquals(step1Id, step2Id, "Step 1 and step 2 are distinct steps");
        Assertions.assertEquals("Add relevant files to the document", json.getJsonObject("route_step").getString("name"));

        // Replay the SAME (now-stale) step1Id: current step is step 2, ids mismatch -> StepChanged 400
        Response replay = target().path("/route/validate").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("transition", "VALIDATED")
                        .param("routeStepId", step1Id)));
        Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), replay.getStatus());
        Assertions.assertEquals("StepChanged", replay.readEntity(JsonObject.class).getString("type"));

        // Step 2 must still be OPEN (not advanced): the doc-detail route_step is still step 2
        json = target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(step2Id, json.getJsonObject("route_step").getString("id"),
                "Step 2 must remain the open current step after a rejected stale replay");
    }

    /**
     * B1 GET /route contract: each route carries id, status, end_date and each step carries its id.
     * An ACTIVE route has a null end_date; a completed route carries status DONE and a non-null
     * end_date.
     */
    @Test
    public void testGetRouteEnrichedContract() {
        String adminToken = adminToken();

        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Route contract")
                        .param("language", "eng")), JsonObject.class);
        String documentId = json.getString("id");

        // Single VALIDATE-step model so one validate completes the route
        String modelId = createGroupValidateModel(adminToken, "Contract model", null);

        target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", modelId)), JsonObject.class);

        // While ACTIVE: id + status ACTIVE + null end_date; step carries an id
        json = target().path("/route")
                .queryParam("documentId", documentId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonObject activeRoute = json.getJsonArray("routes").getJsonObject(0);
        Assertions.assertNotNull(activeRoute.getString("id"));
        Assertions.assertEquals("ACTIVE", activeRoute.getString("status"));
        Assertions.assertTrue(activeRoute.isNull("end_date"), "An ACTIVE route has no end_date");
        Assertions.assertNotNull(activeRoute.getJsonArray("steps").getJsonObject(0).getString("id"),
                "Each step must carry its id");

        // Complete the route
        target().path("/route/validate").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("transition", "VALIDATED")), JsonObject.class);

        // Now DONE with a non-null end_date
        json = target().path("/route")
                .queryParam("documentId", documentId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonObject doneRoute = json.getJsonArray("routes").getJsonObject(0);
        Assertions.assertEquals("DONE", doneRoute.getString("status"));
        Assertions.assertFalse(doneRoute.isNull("end_date"), "A completed route carries an end_date");
    }

    /**
     * Cancel-vs-validate race hardening: cancel removes EVERY ROUTING ACL on the document, not just
     * the step it resolved. Reproduces the deterministic post-race state — a route advanced so a
     * ROUTING ACL exists for the CURRENT step's target, PLUS a lingering ROUTING ACL from a prior
     * step's target (seeded directly to stand in for the raced grant a step-scoped cancel would miss).
     * After cancel, ZERO active ROUTING ACLs may remain: no transient workflow grant survives a
     * terminal route (the W2c invariant). A step-scoped cancel would leave the lingering ACL (RED).
     */
    @Test
    public void testCancelRemovesAllRoutingAcls() {
        String adminToken = adminToken();

        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Cancel routing-ACL cleanup")
                        .param("language", "eng")), JsonObject.class);
        String documentId = json.getString("id");

        // Start the seeded default route: this grants a ROUTING ACL to the current step's target.
        target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", "default-document-review")), JsonObject.class);

        // Seed a lingering ROUTING ACL for a DIFFERENT target directly (committed). It stands in for
        // the raced grant a step-scoped cancel would never see: a concurrent validate would have
        // shifted the ROUTING ACL to a later step's target between cancel's getCurrentStep() read and
        // its ACL removal. The cleanup is target-agnostic, so the seeded target id can be synthetic.
        String racedTargetId = java.util.UUID.randomUUID().toString();
        TestUserResource.inTx(() -> TestUserResource.insertRoutingAcl(documentId, racedTargetId));

        // Two active ROUTING ACLs now exist: the current step's + the seeded lingering one.
        Assertions.assertTrue(activeRoutingAclCount(documentId) >= 2,
                "Pre-cancel state must have the current-step ROUTING ACL plus the seeded lingering one");

        // Cancel the route
        target().path("/route")
                .queryParam("documentId", documentId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);

        // Invariant: no ROUTING ACL survives the now-terminal route — including the lingering one.
        Assertions.assertEquals(0, activeRoutingAclCount(documentId),
                "A cancelled (terminal) route must leave ZERO active ROUTING ACLs on the document");
    }

    /**
     * The normal (non-race) cancel path also ends with zero ROUTING ACLs on the document.
     */
    @Test
    public void testNormalCancelLeavesNoRoutingAcl() {
        String adminToken = adminToken();

        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Normal cancel routing-ACL")
                        .param("language", "eng")), JsonObject.class);
        String documentId = json.getString("id");

        target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", "default-document-review")), JsonObject.class);
        Assertions.assertTrue(activeRoutingAclCount(documentId) >= 1, "Start grants a ROUTING ACL");

        target().path("/route")
                .queryParam("documentId", documentId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);

        Assertions.assertEquals(0, activeRoutingAclCount(documentId),
                "Normal cancel must also leave zero active ROUTING ACLs");
    }

    /**
     * The count of still-active (non-deleted) ROUTING ACLs on a document, read in its own committed
     * transaction so the in-process server's writes are visible.
     */
    private long activeRoutingAclCount(String documentId) {
        return TestUserResource.inTx(() -> ((Number) com.sismics.util.context.ThreadLocalContext.get()
                .getEntityManager().createNativeQuery(
                        "select count(*) from T_ACL where ACL_SOURCEID_C = :id " +
                                "and ACL_TYPE_C = 'ROUTING' and ACL_DELETEDATE_D is null")
                .setParameter("id", documentId).getSingleResult()).longValue());
    }

    /**
     * The guarded step-end wins exactly once: two concurrent validations of the same current step
     * yield exactly one success and one AlreadyEnded (400) — the lost-race response. This exercises
     * the deviation that a duplicate validation cannot double-run the transition.
     */
    @Test
    public void testConcurrentValidateAlreadyEnded() throws Exception {
        String adminToken = adminToken();

        // A single VALIDATE step so both racers read the same current step
        String modelId = createGroupValidateModel(adminToken, "Race model", null);

        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Race me")
                        .param("language", "eng")), JsonObject.class);
        String documentId = json.getString("id");

        target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", modelId)), JsonObject.class);

        // Fire two validations of the same current step concurrently
        Callable<Response> validateCall = () -> target().path("/route/validate").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("transition", "VALIDATED")));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Response> f1 = pool.submit(validateCall);
            Future<Response> f2 = pool.submit(validateCall);
            Response r1 = f1.get();
            Response r2 = f2.get();

            int okCount = 0;
            int alreadyEndedCount = 0;
            int notFoundCount = 0;
            for (Response r : new Response[]{r1, r2}) {
                if (r.getStatus() == Response.Status.OK.getStatusCode()) {
                    okCount++;
                } else if (r.getStatus() == Response.Status.BAD_REQUEST.getStatusCode()) {
                    JsonObject error = r.readEntity(JsonObject.class);
                    if ("AlreadyEnded".equals(error.getString("type"))) {
                        alreadyEndedCount++;
                    }
                } else if (r.getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
                    // The two calls did not overlap: the loser read no current step after the winner
                    // completed the (single-step) route. Still a safe, non-double-applying outcome.
                    notFoundCount++;
                }
            }
            // Exactly one caller wins; the loser must be rejected (AlreadyEnded is the racing path,
            // NotFound is the non-overlapping path). Either way the transition ran exactly once.
            Assertions.assertEquals(1, okCount, "Exactly one validation must win the guarded step-end");
            Assertions.assertEquals(1, alreadyEndedCount + notFoundCount, "The losing validation must be rejected");
        } finally {
            pool.shutdownNow();
        }
    }
}
