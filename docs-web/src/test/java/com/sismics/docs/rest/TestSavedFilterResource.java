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

    // --- #51: publishing a saved filter to every user -----------------------------------

    /**
     * The owner's own round trip: a filter is private when created, publishing marks it and stamps
     * a date, and withdrawing the publication takes both away. Read back from the LIST, not from
     * the mutation's own answer — the stored state is the claim under test.
     */
    @Test
    public void testOwnerPublishesAndWithdraws() {
        String adminToken = adminToken();
        clientUtil.createUser("sfl_pub_owner");
        String ownerToken = clientUtil.login("sfl_pub_owner");
        String id = create(ownerToken, "Publishable", "search=a").getString("id");

        JsonObject stored = listById(ownerToken, id);
        Assertions.assertFalse(stored.getBoolean("published"), "a new filter is private to its owner");
        Assertions.assertTrue(stored.isNull("publish_date"), "an unpublished filter has no publish date");

        Assertions.assertEquals(200, publish(ownerToken, id).getStatus());
        stored = listById(ownerToken, id);
        Assertions.assertTrue(stored.getBoolean("published"));
        Assertions.assertTrue(stored.getJsonNumber("publish_date").longValue() > 0);

        Assertions.assertEquals(200, unpublish(ownerToken, id).getStatus());
        stored = listById(ownerToken, id);
        Assertions.assertFalse(stored.getBoolean("published"));
        Assertions.assertTrue(stored.isNull("publish_date"));

        delete(ownerToken, id);
        deleteUser(adminToken, "sfl_pub_owner");
    }

    /**
     * The point of the feature: another user SEES a published filter, in a section of its own, named
     * with its publisher — and it never leaks into either user's "my filters" list.
     */
    @Test
    public void testPublishedFilterReachesASecondUserInItsOwnSection() {
        String adminToken = adminToken();
        clientUtil.createUser("sfl_pub_publisher");
        String publisherToken = clientUtil.login("sfl_pub_publisher");
        clientUtil.createUser("sfl_pub_reader");
        String readerToken = clientUtil.login("sfl_pub_reader");

        String id = create(publisherToken, "Shared invoices", "search=acme").getString("id");
        Assertions.assertNull(sharedById(readerToken, id), "an unpublished filter reaches nobody else");

        publish(publisherToken, id);

        JsonObject shared = sharedById(readerToken, id);
        Assertions.assertNotNull(shared, "a published filter is offered to every user");
        Assertions.assertEquals("Shared invoices", shared.getString("name"));
        Assertions.assertEquals("search=acme", shared.getString("query"));
        Assertions.assertEquals("sfl_pub_publisher", shared.getString("username"),
                "the shared section names the publisher, so two same-named filters can be told apart");
        Assertions.assertEquals(0, shared.getInt("hidden_tag_count"));
        Assertions.assertTrue(shared.getJsonNumber("publish_date").longValue() > 0);

        // It is not the reader's own filter, and the publisher does not see her own filter twice.
        Assertions.assertEquals(0, savedFilters(readerToken).size(), "a shared filter is not the reader's own");
        Assertions.assertNull(sharedById(publisherToken, id),
                "the publisher's own filter stays in her own section, not in the shared one");

        unpublish(publisherToken, id);
        Assertions.assertNull(sharedById(readerToken, id), "withdrawing the publication takes it back");

        delete(publisherToken, id);
        deleteUser(adminToken, "sfl_pub_publisher");
        deleteUser(adminToken, "sfl_pub_reader");
    }

    /**
     * USE, not EDIT: a published filter is applicable by everyone and writable by nobody but its
     * owner. Every refusal is proven by READING THE ROW BACK — a status code alone would not show
     * that nothing changed.
     */
    @Test
    public void testANonOwnerCanNeitherEditNorDeleteNorPublishAPublishedFilter() {
        String adminToken = adminToken();
        clientUtil.createUser("sfl_pub_keeper");
        String keeperToken = clientUtil.login("sfl_pub_keeper");
        clientUtil.createUser("sfl_pub_nonowner");
        String otherToken = clientUtil.login("sfl_pub_nonowner");

        String id = create(keeperToken, "Owned", "search=original").getString("id");
        publish(keeperToken, id);

        // Rename / re-capture: refused.
        Assertions.assertEquals(404, post(otherToken, id,
                new Form().param("name", "Hijacked").param("query", "search=hijacked")).getStatus());
        // Delete: refused.
        Assertions.assertEquals(404, target().path("/savedfilter/" + id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, otherToken).delete().getStatus());
        // Re-publish (an authorship act, even on an already-published filter): refused.
        Assertions.assertEquals(404, publish(otherToken, id).getStatus());

        JsonObject stored = listById(keeperToken, id);
        Assertions.assertEquals("Owned", stored.getString("name"), "the refused rename changed nothing");
        Assertions.assertEquals("search=original", stored.getString("query"));
        Assertions.assertTrue(stored.getBoolean("published"), "the filter is still published");

        unpublish(keeperToken, id);
        delete(keeperToken, id);
        deleteUser(adminToken, "sfl_pub_keeper");
        deleteUser(adminToken, "sfl_pub_nonowner");
    }

    /**
     * The administrator's power is MANAGEMENT, not authorship: he may withdraw anyone's publication
     * and nothing else. The same call from a plain user is refused.
     */
    @Test
    public void testAdminMayWithdrawAnyPublicationButNotEditIt() {
        String adminToken = adminToken();
        clientUtil.createUser("sfl_pub_author");
        String authorToken = clientUtil.login("sfl_pub_author");
        clientUtil.createUser("sfl_pub_bystander");
        String bystanderToken = clientUtil.login("sfl_pub_bystander");

        String id = create(authorToken, "Author filter", "search=author").getString("id");
        publish(authorToken, id);

        // A plain user may not withdraw someone else's publication. The filter is public knowledge
        // (it is in his shared list), so the refusal is a forbidden, not a not-found.
        Assertions.assertEquals(403, unpublish(bystanderToken, id).getStatus());
        Assertions.assertNotNull(sharedById(bystanderToken, id), "the refused withdrawal changed nothing");

        // The administrator may.
        Assertions.assertEquals(200, unpublish(adminToken, id).getStatus());
        Assertions.assertNull(sharedById(bystanderToken, id), "the publication is gone");

        // But the filter itself survives, still the author's, and the administrator cannot edit it.
        JsonObject stored = listById(authorToken, id);
        Assertions.assertEquals("Author filter", stored.getString("name"));
        Assertions.assertEquals("search=author", stored.getString("query"));
        Assertions.assertFalse(stored.getBoolean("published"));
        Assertions.assertEquals(404, post(adminToken, id,
                new Form().param("name", "Admin rename").param("query", "search=admin")).getStatus());
        Assertions.assertEquals("Author filter", listById(authorToken, id).getString("name"),
                "an administrator governs what is shared, not what it says");

        // Now that it is private again, a bystander is not even told it exists.
        Assertions.assertEquals(404, unpublish(bystanderToken, id).getStatus());
        // The administrator may not publish it on the author's behalf either.
        Assertions.assertEquals(404, publish(adminToken, id).getStatus());
        Assertions.assertFalse(listById(authorToken, id).getBoolean("published"));

        delete(authorToken, id);
        deleteUser(adminToken, "sfl_pub_author");
        deleteUser(adminToken, "sfl_pub_bystander");
    }

    /**
     * The tag-visibility rule (#51, settled on-thread): a published filter that names a tag the
     * VIEWER cannot read is offered as UNAPPLICABLE, counted but never named, and its criteria are
     * withheld — and it becomes ordinary the moment the viewer is granted the tag.
     */
    @Test
    public void testAPublishedFilterNamingAnInvisibleTagIsFlaggedWithoutNamingIt() {
        String adminToken = adminToken();
        clientUtil.createUser("sfl_pub_viewer");
        String viewerToken = clientUtil.login("sfl_pub_viewer");

        clientUtil.createUser("sfl_pub_tagowner");
        String tagOwnerToken = clientUtil.login("sfl_pub_tagowner");

        // A tag only the author can read (creating a tag grants its ACLs to its creator alone).
        String tagId = createTag(tagOwnerToken, "SflSecretTag");
        String id = create(tagOwnerToken, "Secret filter", "tags=" + tagId + "&search=acme").getString("id");
        publish(tagOwnerToken, id);

        JsonObject shared = sharedById(viewerToken, id);
        Assertions.assertNotNull(shared, "the filter is still OFFERED — it is not hidden, it is unapplicable");
        Assertions.assertEquals(1, shared.getInt("hidden_tag_count"),
                "the viewer is told how many tags they cannot see");
        Assertions.assertEquals("", shared.getString("query"),
                "a filter the viewer cannot apply hands them none of its criteria");

        // Nothing in the whole response names the tag the viewer cannot read.
        String body = target().path("/savedfilter").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, viewerToken)
                .get(String.class);
        Assertions.assertFalse(body.contains("SflSecretTag"), "the invisible tag's NAME must not leak");
        Assertions.assertFalse(body.contains(tagId), "the invisible tag's id must not leak either");

        // Grant the viewer READ on the tag: the very same filter becomes ordinary.
        grantTagRead(tagOwnerToken, tagId, "sfl_pub_viewer");

        shared = sharedById(viewerToken, id);
        Assertions.assertEquals(0, shared.getInt("hidden_tag_count"));
        Assertions.assertEquals("tags=" + tagId + "&search=acme", shared.getString("query"),
                "an applicable filter hands over its criteria in full");

        unpublish(tagOwnerToken, id);
        delete(tagOwnerToken, id);
        deleteTag(tagOwnerToken, tagId);
        deleteUser(adminToken, "sfl_pub_tagowner");
        deleteUser(adminToken, "sfl_pub_viewer");
    }

    /**
     * The stored payload is FORM-URL-ENCODED, because the frontend builds it with
     * {@code URLSearchParams.toString()} — which percent-encodes the separator, so a two-tag
     * selection is stored as {@code tags=a%2Cb}, never {@code tags=a,b}
     * (SavedFilters.spec.ts pins that exact string).
     *
     * <p>The load-bearing case is the ALL-VISIBLE one: the viewer may read BOTH tags, so the
     * correct count is 0 and the filter is ordinary. A reader that splits on the comma BEFORE
     * decoding sees one token, {@code "a%2Cb"}, which matches no tag id at all and reports 1 —
     * greying out a filter that is perfectly applicable and withholding its criteria. The
     * half-visible filter beside it pins the ordinary direction on the same encoded shape.</p>
     */
    @Test
    public void testTagIdsAreReadFromTheEncodedPayloadTheFrontendWrites() {
        String adminToken = adminToken();
        clientUtil.createUser("sfl_pub_enc_owner");
        String ownerToken = clientUtil.login("sfl_pub_enc_owner");
        clientUtil.createUser("sfl_pub_enc_viewer");
        String viewerToken = clientUtil.login("sfl_pub_enc_viewer");

        String firstTagId = createTag(ownerToken, "SflEncTagOne");
        String secondTagId = createTag(ownerToken, "SflEncTagTwo");
        String secretTagId = createTag(ownerToken, "SflEncHidden");
        grantTagRead(ownerToken, firstTagId, "sfl_pub_enc_viewer");
        grantTagRead(ownerToken, secondTagId, "sfl_pub_enc_viewer");

        // Exactly what the frontend stores for a two-tag selection, both tags readable.
        String visibleQuery = "tags=" + firstTagId + "%2C" + secondTagId;
        String visibleId = create(ownerToken, "Two visible tags", visibleQuery).getString("id");
        publish(ownerToken, visibleId);

        JsonObject shared = sharedById(viewerToken, visibleId);
        Assertions.assertNotNull(shared);
        Assertions.assertEquals(0, shared.getInt("hidden_tag_count"),
                "both encoded tag ids are readable by the viewer, so nothing is hidden");
        Assertions.assertEquals(visibleQuery, shared.getString("query"),
                "an applicable filter keeps its stored payload byte for byte");

        // The same encoded shape, one member of the pair unreadable.
        String mixedId = create(ownerToken, "One hidden tag", "tags=" + firstTagId + "%2C" + secretTagId)
                .getString("id");
        publish(ownerToken, mixedId);

        JsonObject mixed = sharedById(viewerToken, mixedId);
        Assertions.assertEquals(1, mixed.getInt("hidden_tag_count"));
        // The readable half is not disclosed either — an unapplicable filter hands over none of
        // its criteria.
        Assertions.assertEquals("", mixed.getString("query"));

        unpublish(ownerToken, visibleId);
        unpublish(ownerToken, mixedId);
        delete(ownerToken, visibleId);
        delete(ownerToken, mixedId);
        deleteTag(ownerToken, firstTagId);
        deleteTag(ownerToken, secondTagId);
        deleteTag(ownerToken, secretTagId);
        deleteUser(adminToken, "sfl_pub_enc_owner");
        deleteUser(adminToken, "sfl_pub_enc_viewer");
    }

    @Test
    public void testPublishRoutesRejectAnonymousCallers() {
        Assertions.assertEquals(403, target().path("/savedfilter/some-id/publish").request()
                .post(Entity.form(new Form())).getStatus());
        Assertions.assertEquals(403, target().path("/savedfilter/some-id/publish").request()
                .delete().getStatus());
    }

    private String createTag(String token, String name) {
        return target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form().param("name", name).param("color", "#ff0000")), JsonObject.class)
                .getString("id");
    }

    private void deleteTag(String token, String id) {
        target().path("/tag/" + id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token).delete();
    }

    private void grantTagRead(String ownerToken, String tagId, String username) {
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .put(Entity.form(new Form()
                        .param("source", tagId)
                        .param("perm", "READ")
                        .param("target", username)
                        .param("type", "USER")), JsonObject.class);
    }

    private Response publish(String token, String id) {
        return target().path("/savedfilter/" + id + "/publish").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()));
    }

    private Response unpublish(String token, String id) {
        return target().path("/savedfilter/" + id + "/publish").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .delete();
    }

    private JsonArray savedFilters(String token) {
        return target().path("/savedfilter").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class)
                .getJsonArray("saved_filters");
    }

    /** The caller's shared-section entry for a filter, or null when it is not offered to them. */
    private JsonObject sharedById(String token, String id) {
        JsonArray shared = target().path("/savedfilter").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class)
                .getJsonArray("shared_filters");
        for (int i = 0; i < shared.size(); i++) {
            JsonObject item = shared.getJsonObject(i);
            if (id.equals(item.getString("id"))) {
                return item;
            }
        }
        return null;
    }

    private void deleteUser(String adminToken, String username) {
        target().path("/user/" + username).queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
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
