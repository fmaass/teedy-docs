package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;

/**
 * Test the favorite resource (per-user document stars).
 *
 * <p>Favorites are private: a user never sees, unstars, or counts another user's stars.
 * Starring requires READ; the list is ACL-filtered at read time; a repeat star is an
 * idempotent 200.
 */
public class TestFavoriteResource extends BaseJerseyTest {
    /** Create a document owned by the token holder; return its id. */
    private String createDocument(String token, String title) {
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", title)
                        .param("language", "eng")
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        return json.getString("id");
    }

    private Response put(String token, String documentId) {
        return target().path("/favorite/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()));
    }

    private Response delete(String token, String documentId) {
        return target().path("/favorite/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .delete();
    }

    private JsonObject listFavorites(String token) {
        return target().path("/favorite").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
    }

    @Test
    public void testStarUnstarListCrud() {
        String adminToken = adminToken();
        String documentId = createDocument(adminToken, "Fav crud document");

        // List (empty) — envelope shape pinned: favorites array + paginated total.
        JsonObject json = listFavorites(adminToken);
        Assertions.assertTrue(json.containsKey("favorites"), "list envelope key");
        Assertions.assertEquals(0, json.getJsonArray("favorites").size());
        Assertions.assertEquals(0, json.getJsonNumber("total").intValue(), "empty total");

        // Star — 200 {status: ok}.
        Response r = put(adminToken, documentId);
        Assertions.assertEquals(200, r.getStatus());
        Assertions.assertEquals("ok", r.readEntity(JsonObject.class).getString("status"));

        // The favorite appears in the list as a document DTO with favorite=true.
        json = listFavorites(adminToken);
        JsonArray favorites = json.getJsonArray("favorites");
        Assertions.assertEquals(1, favorites.size());
        JsonObject item = favorites.getJsonObject(0);
        Assertions.assertEquals(documentId, item.getString("id"));
        Assertions.assertEquals("Fav crud document", item.getString("title"));
        Assertions.assertTrue(item.getBoolean("favorite"));

        // Unstar — 200.
        Assertions.assertEquals(200, delete(adminToken, documentId).getStatus());
        Assertions.assertEquals(0, listFavorites(adminToken).getJsonArray("favorites").size());
    }

    @Test
    public void testRepeatStarIsIdempotent200() {
        String adminToken = adminToken();
        String documentId = createDocument(adminToken, "Idempotent star document");

        Assertions.assertEquals(200, put(adminToken, documentId).getStatus());
        // A second star of the already-favorited document is a 200 no-op, never a 400/500.
        Assertions.assertEquals(200, put(adminToken, documentId).getStatus());
        // Still exactly one favorite.
        Assertions.assertEquals(1, listFavorites(adminToken).getJsonArray("favorites").size());

        delete(adminToken, documentId);
    }

    @Test
    public void testUnstarUnknownIs404() {
        String adminToken = adminToken();
        String documentId = createDocument(adminToken, "Never starred document");

        // Unstarring a document that was never favorited yields 404.
        Assertions.assertEquals(404, delete(adminToken, documentId).getStatus());
    }

    @Test
    public void testStarRequiresReadAndOwnershipIsolation() {
        String adminToken = adminToken();
        String documentId = createDocument(adminToken, "Private admin document");

        clientUtil.createUser("fav_user1");
        String user1Token = clientUtil.login("fav_user1");

        // user1 has no READ on admin's document: starring it is 404 (never confirming it exists).
        Assertions.assertEquals(404, put(user1Token, documentId).getStatus());

        // Admin stars it; user1 still cannot see it in their own favorites list (isolation).
        Assertions.assertEquals(200, put(adminToken, documentId).getStatus());
        Assertions.assertEquals(0, listFavorites(user1Token).getJsonArray("favorites").size());
        // Nor can user1 unstar admin's favorite (they have none) — 404.
        Assertions.assertEquals(404, delete(user1Token, documentId).getStatus());
        // Admin still sees exactly their one favorite.
        Assertions.assertEquals(1, listFavorites(adminToken).getJsonArray("favorites").size());

        delete(adminToken, documentId);
        target().path("/user/fav_user1")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).delete();
    }

    @Test
    public void testStarNonExistentDocumentIs404() {
        String adminToken = adminToken();
        Assertions.assertEquals(404, put(adminToken, "nonexistent-id").getStatus());
    }

    @Test
    public void testListOmitsDocumentAfterReadRevoked() {
        String adminToken = adminToken();
        String documentId = createDocument(adminToken, "Shared then revoked");

        clientUtil.createUser("fav_acluser");
        String userToken = clientUtil.login("fav_acluser");

        // Grant user READ, they favorite it, and it appears in their list. The ACL PUT returns
        // the target's id (used to address the DELETE path).
        String aclTargetId = target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("source", documentId)
                        .param("perm", "READ")
                        .param("target", "fav_acluser")
                        .param("type", "USER")), JsonObject.class).getString("id");
        Assertions.assertEquals(200, put(userToken, documentId).getStatus());
        Assertions.assertEquals(1, listFavorites(userToken).getJsonArray("favorites").size());

        // Revoke the READ ACL: the favorite row survives, but the list omits the now-unreadable
        // document (silently, never an error).
        Assertions.assertEquals(200, target().path("/acl/" + documentId + "/READ/" + aclTargetId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).delete().getStatus());
        Assertions.assertEquals(0, listFavorites(userToken).getJsonArray("favorites").size(),
                "a favorite whose READ was revoked is omitted from the list");

        // Cleanup.
        delete(userToken, documentId);
        target().path("/user/fav_acluser")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).delete();
    }

    @Test
    public void testFavoriteFlagOnDocumentDetailAndList() {
        String adminToken = adminToken();
        String documentId = createDocument(adminToken, "Flag document");

        // Before starring: favorite=false on both detail and list.
        JsonObject detail = target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).get(JsonObject.class);
        Assertions.assertFalse(detail.getBoolean("favorite"), "detail favorite=false before star");

        JsonObject list = target().path("/document/list").queryParam("search", "Flag document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).get(JsonObject.class);
        Assertions.assertFalse(findInList(list, documentId).getBoolean("favorite"), "list favorite=false before star");

        // After starring: favorite=true on both.
        put(adminToken, documentId);
        detail = target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).get(JsonObject.class);
        Assertions.assertTrue(detail.getBoolean("favorite"), "detail favorite=true after star");

        list = target().path("/document/list").queryParam("search", "Flag document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).get(JsonObject.class);
        Assertions.assertTrue(findInList(list, documentId).getBoolean("favorite"), "list favorite=true after star");

        delete(adminToken, documentId);
    }

    @Test
    public void testFavoritesFilterComposesWithTagAndPagination() {
        String adminToken = adminToken();

        // A tag, and two documents carrying it. Only the first is favorited.
        String tagId = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("name", "FavTag" + new Date().getTime()).param("color", "#ff0000")),
                        JsonObject.class).getString("id");

        String favedId = createTaggedDocument(adminToken, "Faved tagged doc", tagId);
        String otherId = createTaggedDocument(adminToken, "Unfaved tagged doc", tagId);
        put(adminToken, favedId);

        // favorites=me + the tag filter: only the favorited, tagged document is returned.
        JsonObject json = target().path("/document/list")
                .queryParam("favorites", "me")
                .queryParam("search[tag]", "FavTag")
                .queryParam("limit", 10)
                .queryParam("offset", 0)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonArray documents = json.getJsonArray("documents");
        Assertions.assertEquals(1, documents.size(), "favorites=me composes with the tag filter");
        Assertions.assertEquals(favedId, documents.getJsonObject(0).getString("id"));
        Assertions.assertEquals(1, json.getJsonNumber("total").intValue(), "total reflects the composed filter");

        // POST /document/list inherits the same favorites param.
        JsonObject post = target().path("/document/list").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("favorites", "me")
                        .param("search[tag]", "FavTag")), JsonObject.class);
        Assertions.assertEquals(1, post.getJsonArray("documents").size(), "POST list honors favorites=me");

        // Without favorites=me, both tagged documents are returned.
        JsonObject all = target().path("/document/list")
                .queryParam("search[tag]", "FavTag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).get(JsonObject.class);
        Assertions.assertEquals(2, all.getJsonArray("documents").size());

        delete(adminToken, favedId);
    }

    @Test
    public void testListPagesWithLimitAndOffset() {
        String adminToken = adminToken();
        String d1 = createDocument(adminToken, "Fav page doc 1");
        String d2 = createDocument(adminToken, "Fav page doc 2");
        String d3 = createDocument(adminToken, "Fav page doc 3");
        put(adminToken, d1);
        put(adminToken, d2);
        put(adminToken, d3);

        // First page of two: total reflects all three favorites, the page holds two.
        JsonObject page1 = target().path("/favorite")
                .queryParam("limit", 2).queryParam("offset", 0).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).get(JsonObject.class);
        Assertions.assertEquals(3, page1.getJsonNumber("total").intValue(), "total counts all favorites");
        Assertions.assertEquals(2, page1.getJsonArray("favorites").size(), "first page holds the limit");

        // Second page holds the remaining one.
        JsonObject page2 = target().path("/favorite")
                .queryParam("limit", 2).queryParam("offset", 2).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).get(JsonObject.class);
        Assertions.assertEquals(3, page2.getJsonNumber("total").intValue());
        Assertions.assertEquals(1, page2.getJsonArray("favorites").size(), "second page holds the remainder");

        delete(adminToken, d1);
        delete(adminToken, d2);
        delete(adminToken, d3);
    }

    @Test
    public void testUnauthenticatedRejected() {
        Assertions.assertEquals(403, target().path("/favorite").request().get().getStatus());
        Assertions.assertEquals(403, target().path("/favorite/some-id").request().put(Entity.form(new Form())).getStatus());
        Assertions.assertEquals(403, target().path("/favorite/some-id").request().delete().getStatus());
    }

    private String createTaggedDocument(String token, String title, String tagId) {
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", title)
                        .param("language", "eng")
                        .param("tags", tagId)
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        return json.getString("id");
    }

    private JsonObject findInList(JsonObject listResponse, String documentId) {
        JsonArray documents = listResponse.getJsonArray("documents");
        for (int i = 0; i < documents.size(); i++) {
            JsonObject doc = documents.getJsonObject(i);
            if (documentId.equals(doc.getString("id"))) {
                return doc;
            }
        }
        throw new AssertionError("document " + documentId + " not found in list response");
    }
}
