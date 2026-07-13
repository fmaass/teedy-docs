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

    private Response put(String token, Form form) {
        return target().path("/savedfilter").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(form));
    }
}
