package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;


/**
 * Test the route model resource.
 *
 * @author bgamard
 */
public class TestRouteModelResource extends BaseJerseyTest {
    /**
     * Steps blob targeting a single named user. The route model is only faithfully startable while
     * that user exists.
     *
     * @param username Target username
     * @return Steps JSON
     */
    private String stepsForUser(String username) {
        return "[{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\",\"actions\":[]}]," +
                "\"target\":{\"name\":\"" + username + "\",\"type\":\"USER\"},\"name\":\"Review\"}]";
    }

    /**
     * All route models visible to the given token's LIST.
     */
    private JsonArray adminModels(String token) {
        return target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class)
                .getJsonArray("routemodels");
    }

    private int adminModelCount(String token) {
        return adminModels(token).size();
    }

    /**
     * Find a model by ID in a LIST array, or null.
     */
    private JsonObject findModel(JsonArray models, String modelId) {
        for (int i = 0; i < models.size(); i++) {
            if (modelId.equals(models.getJsonObject(i).getString("id"))) {
                return models.getJsonObject(i);
            }
        }
        return null;
    }

    /**
     * Test the route model CRUD, the LIST authz split (any authenticated user, READ-ACL-filtered;
     * admin-only mutations) and the derived ACL panel on GET.
     */
    @Test
    public void testRouteModelResource() {
        // Login admin
        String adminToken = adminToken();

        // Login routeModel1
        clientUtil.createUser("routeModel1");
        String routeModel1Token = clientUtil.login("routeModel1");

        // Create a tag
        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "TagRoute")
                        .param("color", "#ff0000")), JsonObject.class);
        String tagRouteId = json.getString("id");

        // The seeded default-document-review is always visible to admin (READ granted to administrators)
        int adminModelCountBefore = adminModelCount(adminToken);
        Assertions.assertTrue(adminModelCountBefore >= 1);

        // Create a route model
        json = target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "Workflow validation 1")
                        .param("steps", "[{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\",\"actions\":[]}],\"target\":{\"name\":\"administrators\",\"type\":\"GROUP\"},\"name\":\"Check the document's metadata\"}]")), JsonObject.class);
        String routeModelId = json.getString("id");

        // Admin now sees one more model; the created one is present and, having all-live targets, not incomplete
        JsonArray routeModels = adminModels(adminToken);
        Assertions.assertEquals(adminModelCountBefore + 1, routeModels.size());
        JsonObject created = findModel(routeModels, routeModelId);
        Assertions.assertNotNull(created);
        Assertions.assertEquals("Workflow validation 1", created.getString("name"));
        Assertions.assertFalse(created.getBoolean("incomplete"));

        // routeModel1 has no READ ACL on this model yet: LIST is READ-ACL-filtered, so it does not see it
        json = target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, routeModel1Token)
                .get(JsonObject.class);
        Assertions.assertNull(findModel(json.getJsonArray("routemodels"), routeModelId));

        // Share the model to routeModel1 via PUT /api/acl READ
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("source", routeModelId)
                        .param("perm", "READ")
                        .param("target", "routeModel1")
                        .param("type", "USER")), JsonObject.class);

        // Now routeModel1 sees the shared model in LIST
        json = target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, routeModel1Token)
                .get(JsonObject.class);
        Assertions.assertNotNull(findModel(json.getJsonArray("routemodels"), routeModelId));

        // GET keeps the ACL panel (AclUtil.addAcls): admin sees the acls array
        json = target().path("/routemodel/" + routeModelId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(routeModelId, json.getString("id"));
        Assertions.assertEquals("Workflow validation 1", json.getString("name"));
        JsonArray acls = json.getJsonArray("acls");
        Assertions.assertEquals(3, acls.size()); // 2 for admin (READ+WRITE), 1 for routeModel1 (READ)

        // Update the route model (admin)
        target().path("/routemodel/" + routeModelId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("name", "Workflow validation 2")
                        .param("steps", "[{\"type\":\"APPROVE\",\"transitions\":[{\"name\":\"APPROVED\",\"actions\":[{\"type\":\"ADD_TAG\",\"tag\":\"" + tagRouteId + "\"}]},{\"name\":\"REJECTED\",\"actions\":[]}],\"target\":{\"name\":\"administrators\",\"type\":\"GROUP\"},\"name\":\"Check the document's metadata\"}]")), JsonObject.class);

        json = target().path("/routemodel/" + routeModelId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals("Workflow validation 2", json.getString("name"));

        // Delete the route model
        target().path("/routemodel/" + routeModelId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);

        // The deleted model is gone from LIST
        Assertions.assertNull(findModel(adminModels(adminToken), routeModelId));

        // Deletes routeModel1 user
        target().path("/user/routeModel1").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    /**
     * Non-admin users are forbidden from every route model mutation (GET/PUT/POST/DELETE are admin
     * only); LIST is the only endpoint open to a non-admin.
     */
    @Test
    public void testRouteModelAdminOnly() {
        String adminToken = adminToken();
        clientUtil.createUser("rmnonadmin");
        String nonAdminToken = clientUtil.login("rmnonadmin");

        // Create a model as admin, then share READ to the non-admin so it appears in LIST
        JsonObject json = target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "Admin only model")
                        .param("steps", "[{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\",\"actions\":[]}],\"target\":{\"name\":\"administrators\",\"type\":\"GROUP\"},\"name\":\"Review\"}]")), JsonObject.class);
        String modelId = json.getString("id");
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("source", modelId)
                        .param("perm", "READ")
                        .param("target", "rmnonadmin")
                        .param("type", "USER")), JsonObject.class);

        // LIST is allowed for the non-admin
        Response listResponse = target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, nonAdminToken)
                .get();
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), listResponse.getStatus());

        // GET is admin-only -> forbidden
        Response getResponse = target().path("/routemodel/" + modelId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, nonAdminToken)
                .get();
        Assertions.assertEquals(Response.Status.FORBIDDEN.getStatusCode(), getResponse.getStatus());

        // PUT (create) is admin-only -> forbidden
        Response putResponse = target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, nonAdminToken)
                .put(Entity.form(new Form()
                        .param("name", "Nope")
                        .param("steps", "[{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\",\"actions\":[]}],\"target\":{\"name\":\"administrators\",\"type\":\"GROUP\"},\"name\":\"Review\"}]")));
        Assertions.assertEquals(Response.Status.FORBIDDEN.getStatusCode(), putResponse.getStatus());

        // POST (update) is admin-only -> forbidden
        Response postResponse = target().path("/routemodel/" + modelId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, nonAdminToken)
                .post(Entity.form(new Form()
                        .param("name", "Nope")
                        .param("steps", "[{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\",\"actions\":[]}],\"target\":{\"name\":\"administrators\",\"type\":\"GROUP\"},\"name\":\"Review\"}]")));
        Assertions.assertEquals(Response.Status.FORBIDDEN.getStatusCode(), postResponse.getStatus());

        // DELETE is admin-only -> forbidden
        Response deleteResponse = target().path("/routemodel/" + modelId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, nonAdminToken)
                .delete();
        Assertions.assertEquals(Response.Status.FORBIDDEN.getStatusCode(), deleteResponse.getStatus());

        // Cleanup
        target().path("/user/rmnonadmin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    /**
     * A single-step blob whose target carries the given ACL target type + name. Used to exercise
     * every ACL-target category through the step-target validator.
     */
    private String stepsForTarget(String targetType, String targetName) {
        return "[{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\",\"actions\":[]}]," +
                "\"target\":{\"name\":\"" + targetName + "\",\"type\":\"" + targetType + "\"},\"name\":\"Review\"}]";
    }

    /**
     * PUT /routemodel with the given steps blob, returning the raw response (not consumed as JSON so
     * error status can be asserted).
     */
    private Response createModel(String token, String name, String steps) {
        return target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form().param("name", name).param("steps", steps)));
    }

    /**
     * The step-target validator accepts USER and GROUP targets and rejects SHARE and unknown ACL
     * target types with a ValidationError — one assertion per ACL target category (Testing
     * Integrity: every input category gets a case). SHARE targets have no name to resolve and would
     * silently produce an unstartable model; an unknown enum value must fail closed rather than skip
     * the switch.
     */
    @Test
    public void testRouteModelStepTargetValidation() {
        String adminToken = adminToken();
        clientUtil.createUser("rmtargetuser");

        // USER target: valid -> 200
        Response userResp = createModel(adminToken, "Valid user target", stepsForTarget("USER", "rmtargetuser"));
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), userResp.getStatus());

        // GROUP target: valid -> 200 (administrators is seeded)
        Response groupResp = createModel(adminToken, "Valid group target", stepsForTarget("GROUP", "administrators"));
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), groupResp.getStatus());

        // SHARE target: rejected with a ValidationError naming SHARE as unsupported
        Response shareResp = createModel(adminToken, "Share target", stepsForTarget("SHARE", "anything"));
        Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), shareResp.getStatus());
        JsonObject shareError = shareResp.readEntity(JsonObject.class);
        Assertions.assertEquals("ValidationError", shareError.getString("type"));

        // Unknown target type: rejected as an invalid ACL target type (fails at enum parse)
        Response garbageResp = createModel(adminToken, "Garbage target", stepsForTarget("NOTATYPE", "anything"));
        Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), garbageResp.getStatus());
        JsonObject garbageError = garbageResp.readEntity(JsonObject.class);
        Assertions.assertEquals("ValidationError", garbageError.getString("type"));

        // Update (POST) must reject a SHARE target too, not just create.
        JsonObject created = createModel(adminToken, "Update target model",
                stepsForTarget("GROUP", "administrators")).readEntity(JsonObject.class);
        String modelId = created.getString("id");
        Response updateShareResp = target().path("/routemodel/" + modelId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("name", "Update target model")
                        .param("steps", stepsForTarget("SHARE", "anything"))));
        Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), updateShareResp.getStatus());
        Assertions.assertEquals("ValidationError",
                updateShareResp.readEntity(JsonObject.class).getString("type"));

        // Cleanup
        target().path("/user/rmtargetuser").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    /**
     * The LIST incomplete flag flips to true once a step target no longer resolves to an active
     * principal (here: the target user is deleted).
     */
    @Test
    public void testRouteModelIncompleteFlag() {
        String adminToken = adminToken();

        // A user that is a step target
        clientUtil.createUser("incompletetarget");

        // Model targeting that user
        JsonObject json = target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "Incomplete candidate")
                        .param("steps", stepsForUser("incompletetarget"))), JsonObject.class);
        String modelId = json.getString("id");

        // While the target exists the model is complete
        json = target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonArray models = json.getJsonArray("routemodels");
        boolean foundComplete = false;
        for (int i = 0; i < models.size(); i++) {
            JsonObject m = models.getJsonObject(i);
            if (modelId.equals(m.getString("id"))) {
                Assertions.assertFalse(m.getBoolean("incomplete"));
                foundComplete = true;
            }
        }
        Assertions.assertTrue(foundComplete);

        // Delete the target user
        target().path("/user/incompletetarget").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();

        // Now the model is flagged incomplete
        json = target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        models = json.getJsonArray("routemodels");
        boolean foundIncomplete = false;
        for (int i = 0; i < models.size(); i++) {
            JsonObject m = models.getJsonObject(i);
            if (modelId.equals(m.getString("id"))) {
                Assertions.assertTrue(m.getBoolean("incomplete"));
                foundIncomplete = true;
            }
        }
        Assertions.assertTrue(foundIncomplete);
    }
}
