package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test the saved-filter resource.
 */
public class TestSavedFilterResource extends BaseJerseyTest {
    @Test
    public void testSavedFilterCrud() {
        String adminToken = adminToken();

        // List (empty) — envelope shape is pinned, not just the status.
        JsonObject json = target().path("/savedfilter").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertTrue(json.containsKey("saved_filters"), "list envelope key");
        Assertions.assertEquals(0, json.getJsonArray("saved_filters").size());

        // Create — PUT returns {id, name, query}.
        json = target().path("/savedfilter").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "Invoices")
                        .param("query", "tags=t1,t2&exclude=t3&mode=or&search=acme&workflow=me")), JsonObject.class);
        String id = json.getString("id");
        Assertions.assertNotNull(id);
        Assertions.assertEquals("Invoices", json.getString("name"));
        Assertions.assertEquals("tags=t1,t2&exclude=t3&mode=or&search=acme&workflow=me", json.getString("query"));

        // List (one) — every field name pinned.
        json = target().path("/savedfilter").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonArray filters = json.getJsonArray("saved_filters");
        Assertions.assertEquals(1, filters.size());
        JsonObject item = filters.getJsonObject(0);
        Assertions.assertEquals(id, item.getString("id"));
        Assertions.assertEquals("Invoices", item.getString("name"));
        Assertions.assertEquals("tags=t1,t2&exclude=t3&mode=or&search=acme&workflow=me", item.getString("query"));
        Assertions.assertTrue(item.containsKey("create_date"), "create_date is present");
        Assertions.assertTrue(item.getJsonNumber("create_date").longValue() > 0);

        // Delete — {status: ok}.
        json = target().path("/savedfilter/" + id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // List (empty again).
        json = target().path("/savedfilter").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(0, json.getJsonArray("saved_filters").size());
    }

    @Test
    public void testWorkflowOnlyFilterIsSaveable() {
        String adminToken = adminToken();

        // A workflow-only filter (no tags/search) MUST be saveable — the store's
        // hasActiveFilters excludes workflow, but the resource validates the raw query.
        JsonObject json = target().path("/savedfilter").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("name", "Only workflow").param("query", "workflow=me")), JsonObject.class);
        Assertions.assertEquals("workflow=me", json.getString("query"));

        target().path("/savedfilter/" + json.getString("id")).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
    }

    @Test
    public void testValidation() {
        String adminToken = adminToken();

        // Missing name.
        Assertions.assertEquals(400, put(adminToken, new Form().param("query", "search=x")).getStatus());
        // Missing query.
        Assertions.assertEquals(400, put(adminToken, new Form().param("name", "No query")).getStatus());
        // Name too long (> 100).
        Assertions.assertEquals(400, put(adminToken,
                new Form().param("name", "x".repeat(101)).param("query", "search=x")).getStatus());
        // Unsupported query key.
        Assertions.assertEquals(400, put(adminToken,
                new Form().param("name", "Bad key").param("query", "search=x&evil=1")).getStatus());
        // Repeated key (vue-router would yield an array; initFromUrl assumes scalars).
        Assertions.assertEquals(400, put(adminToken,
                new Form().param("name", "Repeated").param("query", "search=a&search=b")).getStatus());
        // Empty pair (leading/double '&').
        Assertions.assertEquals(400, put(adminToken,
                new Form().param("name", "Empty pair").param("query", "search=a&&mode=or")).getStatus());
    }

    @Test
    public void testDuplicateNameRejected() {
        String adminToken = adminToken();

        JsonObject created = target().path("/savedfilter").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("name", "Dup").param("query", "search=a")), JsonObject.class);

        // Exact-case duplicate.
        Assertions.assertEquals(400, put(adminToken,
                new Form().param("name", "Dup").param("query", "search=b")).getStatus());
        // Case-insensitive duplicate is rejected too (single-request UX precheck).
        Assertions.assertEquals(400, put(adminToken,
                new Form().param("name", "DUP").param("query", "search=c")).getStatus());

        target().path("/savedfilter/" + created.getString("id")).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
    }

    @Test
    public void testOwnershipAndForeignDeleteIs404() {
        String adminToken = adminToken();

        JsonObject json = target().path("/savedfilter").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("name", "Admin filter").param("query", "search=a")), JsonObject.class);
        String adminFilterId = json.getString("id");

        clientUtil.createUser("sfl_user1");
        String user1Token = clientUtil.login("sfl_user1");

        // User1 does not see admin's filters.
        json = target().path("/savedfilter").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, user1Token)
                .get(JsonObject.class);
        Assertions.assertEquals(0, json.getJsonArray("saved_filters").size());

        // Deleting a filter owned by another user is 404 (never 403).
        Response response = target().path("/savedfilter/" + adminFilterId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, user1Token)
                .delete();
        Assertions.assertEquals(404, response.getStatus());

        // An unknown id is also 404.
        response = target().path("/savedfilter/nonexistent-id").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        Assertions.assertEquals(404, response.getStatus());

        // Two users may hold the SAME filter name (per-user scoping).
        json = target().path("/savedfilter").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, user1Token)
                .put(Entity.form(new Form().param("name", "Admin filter").param("query", "search=z")), JsonObject.class);
        Assertions.assertNotNull(json.getString("id"));

        // Cleanup.
        target().path("/savedfilter/" + adminFilterId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        target().path("/user/sfl_user1")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    @Test
    public void testUnauthenticatedRejected() {
        Assertions.assertEquals(403, target().path("/savedfilter").request().get().getStatus());
    }

    /**
     * Happy path of the update endpoint (POST /savedfilter/{id}): both fields change, the
     * response echoes the stored values, and the persisted row reflects them.
     */
    @Test
    public void testUpdateRenamesAndRecapturesQuery() {
        String adminToken = adminToken();
        String id = create(adminToken, "Before", "search=before").getString("id");
        long createDate = listById(adminToken, id).getJsonNumber("create_date").longValue();

        JsonObject json = target().path("/savedfilter/" + id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("name", "After")
                        .param("query", "tags=t1&mode=or&search=after")), JsonObject.class);
        Assertions.assertEquals(id, json.getString("id"), "the id is echoed, never reassigned");
        Assertions.assertEquals("After", json.getString("name"));
        Assertions.assertEquals("tags=t1&mode=or&search=after", json.getString("query"));

        JsonObject stored = listById(adminToken, id);
        Assertions.assertEquals("After", stored.getString("name"));
        Assertions.assertEquals("tags=t1&mode=or&search=after", stored.getString("query"));
        Assertions.assertEquals(createDate, stored.getJsonNumber("create_date").longValue(),
                "an update never rewrites the create date");

        delete(adminToken, id);
    }

    /**
     * Only {@code name} and {@code query} are mutable. Extra form fields naming the immutable
     * columns are inert — the resource binds neither, and the DAO takes only the two values.
     */
    @Test
    public void testUpdateCannotTouchImmutableFields() {
        String adminToken = adminToken();
        String id = create(adminToken, "Immutable", "search=a").getString("id");
        JsonObject before = listById(adminToken, id);

        clientUtil.createUser("sfl_imm_user");
        String otherToken = clientUtil.login("sfl_imm_user");

        JsonObject json = target().path("/savedfilter/" + id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("name", "Immutable")
                        .param("query", "search=b")
                        .param("id", "forged-id")
                        .param("user_id", "sfl_imm_user")
                        .param("create_date", "0")), JsonObject.class);
        Assertions.assertEquals(id, json.getString("id"));

        JsonObject after = listById(adminToken, id);
        Assertions.assertEquals(before.getJsonNumber("create_date").longValue(),
                after.getJsonNumber("create_date").longValue(), "create_date is immutable");
        Assertions.assertEquals("search=b", after.getString("query"));

        // The owner did not move: the other user still sees nothing.
        JsonObject otherList = target().path("/savedfilter").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, otherToken)
                .get(JsonObject.class);
        Assertions.assertEquals(0, otherList.getJsonArray("saved_filters").size(), "the owner is immutable");

        delete(adminToken, id);
        target().path("/user/sfl_imm_user").queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    /**
     * The update path applies the create path's validation VERBATIM — an update accepting an
     * empty, overlong or unsupported-key value would be a hole around the create contract.
     */
    @Test
    public void testUpdateValidation() {
        String adminToken = adminToken();
        String id = create(adminToken, "Validated", "search=a").getString("id");

        // Missing name / empty name.
        Assertions.assertEquals(400, post(adminToken, id, new Form().param("query", "search=x")).getStatus());
        Assertions.assertEquals(400, post(adminToken, id,
                new Form().param("name", "").param("query", "search=x")).getStatus());
        // Missing query / empty query.
        Assertions.assertEquals(400, post(adminToken, id, new Form().param("name", "Valid")).getStatus());
        Assertions.assertEquals(400, post(adminToken, id,
                new Form().param("name", "Valid").param("query", "")).getStatus());
        // Overlength name (> 100) and overlength query (> 2000).
        Assertions.assertEquals(400, post(adminToken, id,
                new Form().param("name", "x".repeat(101)).param("query", "search=x")).getStatus());
        Assertions.assertEquals(400, post(adminToken, id,
                new Form().param("name", "Valid").param("query", "search=" + "x".repeat(2000))).getStatus());
        // ALLOWED_KEYS is enforced on update too — otherwise a rename smuggles an unvalidated query in.
        Assertions.assertEquals(400, post(adminToken, id,
                new Form().param("name", "Valid").param("query", "search=x&evil=1")).getStatus());
        // Repeated key and empty pair, same as create.
        Assertions.assertEquals(400, post(adminToken, id,
                new Form().param("name", "Valid").param("query", "search=a&search=b")).getStatus());
        Assertions.assertEquals(400, post(adminToken, id,
                new Form().param("name", "Valid").param("query", "search=a&&mode=or")).getStatus());

        // None of the rejected calls changed the stored row.
        JsonObject stored = listById(adminToken, id);
        Assertions.assertEquals("Validated", stored.getString("name"));
        Assertions.assertEquals("search=a", stored.getString("query"));

        delete(adminToken, id);
    }

    /**
     * The duplicate-name precheck excludes the filter being updated, so a no-op save and a
     * case-only self-rename both succeed while a collision with a DIFFERENT filter is a 400.
     */
    @Test
    public void testUpdateDuplicateNameRejectedButSelfAllowed() {
        String adminToken = adminToken();
        String keptId = create(adminToken, "Invoices", "search=a").getString("id");
        String movedId = create(adminToken, "Drafts", "search=b").getString("id");

        // Collision with another filter — exact case and case-insensitive.
        Assertions.assertEquals(400, post(adminToken, movedId,
                new Form().param("name", "Invoices").param("query", "search=b")).getStatus());
        Assertions.assertEquals(400, post(adminToken, movedId,
                new Form().param("name", "INVOICES").param("query", "search=b")).getStatus());

        // Re-saving a filter under its OWN name is not a duplicate (this is the overwrite flow).
        JsonObject json = target().path("/savedfilter/" + movedId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("name", "Drafts").param("query", "search=c")), JsonObject.class);
        Assertions.assertEquals("search=c", json.getString("query"));

        // A case-only self-rename is allowed (the index is exact-case).
        json = target().path("/savedfilter/" + movedId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("name", "DRAFTS").param("query", "search=c")), JsonObject.class);
        Assertions.assertEquals("DRAFTS", json.getString("name"));

        delete(adminToken, keptId);
        delete(adminToken, movedId);
    }

    /**
     * Owner-scoped 404-never-403: a foreign id and an unknown id are indistinguishable, and the
     * not-found answer precedes the duplicate precheck (a foreign id colliding with a name the
     * CALLER owns is still a 404, not a 400).
     */
    @Test
    public void testUpdateForeignAndUnknownIdAre404() {
        String adminToken = adminToken();
        String adminFilterId = create(adminToken, "Admin filter", "search=a").getString("id");

        clientUtil.createUser("sfl_upd_user");
        String userToken = clientUtil.login("sfl_upd_user");
        // The caller owns a filter with the SAME name — the ownership check must still win.
        String userFilterId = create(userToken, "Admin filter", "search=z").getString("id");

        Assertions.assertEquals(404, post(userToken, adminFilterId,
                new Form().param("name", "Admin filter").param("query", "search=y")).getStatus());
        Assertions.assertEquals(404, post(adminToken, "nonexistent-id",
                new Form().param("name", "Ghost").param("query", "search=y")).getStatus());

        // The foreign row is untouched.
        Assertions.assertEquals("search=a", listById(adminToken, adminFilterId).getString("query"));

        delete(userToken, userFilterId);
        delete(adminToken, adminFilterId);
        target().path("/user/sfl_upd_user").queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    @Test
    public void testUpdateUnauthenticatedRejected() {
        Assertions.assertEquals(403, target().path("/savedfilter/some-id").request()
                .post(Entity.form(new Form().param("name", "n").param("query", "search=a"))).getStatus());
    }

    private JsonObject create(String token, String name, String query) {
        return target().path("/savedfilter").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form().param("name", name).param("query", query)), JsonObject.class);
    }

    private JsonObject listById(String token, String id) {
        JsonArray filters = target().path("/savedfilter").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class)
                .getJsonArray("saved_filters");
        for (int i = 0; i < filters.size(); i++) {
            JsonObject item = filters.getJsonObject(i);
            if (id.equals(item.getString("id"))) {
                return item;
            }
        }
        throw new AssertionError("saved filter " + id + " is not in the caller's list");
    }

    private void delete(String token, String id) {
        target().path("/savedfilter/" + id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .delete(JsonObject.class);
    }

    private Response post(String token, String id, Form form) {
        return target().path("/savedfilter/" + id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(form));
    }

    private Response put(String token, Form form) {
        return target().path("/savedfilter").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(form));
    }
}
