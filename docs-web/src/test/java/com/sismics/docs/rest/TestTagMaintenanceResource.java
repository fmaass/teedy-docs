package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tests the tag maintenance endpoints (#298 parts 1 and 2): the per-tag deletability status, the
 * single-subtree delete behind the management tree's context action, and the instance-wide
 * unused-tag cleanup with its preview/confirm contract.
 *
 * <p>The guard these tests exist for is the destructive half: a subtree that still carries a
 * document must be REFUSED, and the refusal must leave every tag standing. Each refusal below is
 * therefore followed by a read-back of {@code /tag/list}, not just a status-code assertion.</p>
 *
 * @author fmaass
 */
public class TestTagMaintenanceResource extends BaseJerseyTest {
    private String createTag(String token, String name, String parentId) {
        Form form = new Form().param("name", name).param("color", "#3399cc");
        if (parentId != null) {
            form.param("parent", parentId);
        }
        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(form), JsonObject.class);
        return json.getString("id");
    }

    private String createDocumentWithTag(String token, String title, String tagId) {
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", title)
                        .param("tags", tagId)
                        .param("language", "eng")), JsonObject.class);
        return json.getString("id");
    }

    private JsonObject maintenanceStatus(String token, String tagId) {
        JsonObject json = target().path("/tag/maintenance").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        for (JsonValue value : json.getJsonArray("tags")) {
            JsonObject tag = value.asJsonObject();
            if (tag.getString("id").equals(tagId)) {
                return tag;
            }
        }
        return null;
    }

    private Set<String> tagIds(String token) {
        JsonObject json = target().path("/tag/list").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Set<String> ids = new HashSet<>();
        for (JsonValue value : json.getJsonArray("tags")) {
            ids.add(value.asJsonObject().getString("id"));
        }
        return ids;
    }

    private static List<String> namesOf(JsonArray tags) {
        List<String> names = new ArrayList<>();
        for (JsonValue value : tags) {
            names.add(value.asJsonObject().getString("name"));
        }
        return names;
    }

    /**
     * The reporter's own example: an unused chain above a USED deep child. Every tag in it reports
     * as undeletable with the document count to explain why, the delete is refused, and the whole
     * chain survives the refusal — the structure is kept, not collapsed.
     */
    @Test
    public void testUsedSubtreeIsRefusedAndNothingIsDeleted() {
        clientUtil.createUser("tagmaint1");
        String token = clientUtil.login("tagmaint1");

        String topId = createTag(token, "MaintTop", null);
        String subId = createTag(token, "MaintSub", topId);
        String leafId = createTag(token, "MaintLeaf", subId);
        createDocumentWithTag(token, "A document nobody expected", leafId);

        JsonObject top = maintenanceStatus(token, topId);
        Assertions.assertNotNull(top, "the maintenance status lists the caller's tags");
        Assertions.assertFalse(top.getBoolean("deletable"), "an unused root above a used leaf");
        Assertions.assertEquals("documents", top.getString("reason"));
        Assertions.assertEquals(1, top.getInt("subtreeDocuments"));
        Assertions.assertFalse(top.getBoolean("root"));
        Assertions.assertEquals("MaintTop / MaintSub / MaintLeaf",
                maintenanceStatus(token, leafId).getString("path"));

        Response response = target().path("/tag/" + topId + "/subtree").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .delete();
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("TagSubtreeInUse", response.readEntity(JsonObject.class).getString("type"));

        // The refusal changed nothing: the read-back is the acceptance, not the status code.
        Set<String> remaining = tagIds(token);
        Assertions.assertTrue(remaining.contains(topId), "the root survives a refused delete");
        Assertions.assertTrue(remaining.contains(subId), "the middle survives a refused delete");
        Assertions.assertTrue(remaining.contains(leafId), "the used leaf survives a refused delete");
    }

    /** A subtree no document touches goes whole, and the response reports exactly what went. */
    @Test
    public void testUnusedSubtreeIsDeletedWholeAndReported() {
        clientUtil.createUser("tagmaint2");
        String token = clientUtil.login("tagmaint2");

        String rootId = createTag(token, "Archive2019", null);
        String midId = createTag(token, "Q1", rootId);
        String leafId = createTag(token, "January", midId);

        JsonObject root = maintenanceStatus(token, rootId);
        Assertions.assertTrue(root.getBoolean("deletable"));
        Assertions.assertTrue(root.getBoolean("root"), "the topmost unused tag is the cleanup root");
        Assertions.assertEquals(0, root.getInt("subtreeDocuments"));
        Assertions.assertFalse(root.containsKey("reason"), "a deletable tag carries no reason");
        Assertions.assertFalse(maintenanceStatus(token, midId).getBoolean("root"),
                "a descendant of a cleanup root is not itself a root");

        JsonObject json = target().path("/tag/" + rootId + "/subtree").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
        Assertions.assertEquals(3, json.getInt("count"));
        Assertions.assertEquals(List.of("Archive2019", "Q1", "January"), namesOf(json.getJsonArray("tags")));

        Set<String> remaining = tagIds(token);
        Assertions.assertFalse(remaining.contains(rootId));
        Assertions.assertFalse(remaining.contains(midId));
        Assertions.assertFalse(remaining.contains(leafId));
    }

    /**
     * The cleanup's preview/confirm contract: what {@code GET /tag/maintenance} marks as a root is
     * exactly what {@code DELETE /tag/maintenance} removes, the used branch is untouched, and the
     * confirm reports the names it deleted.
     */
    @Test
    public void testCleanupPreviewMatchesWhatTheConfirmDeletes() {
        clientUtil.createUser("tagmaint3");
        String token = clientUtil.login("tagmaint3");

        String keepId = createTag(token, "Keep", null);
        String keptChildId = createTag(token, "KeptChild", keepId);
        String goneId = createTag(token, "Gone", null);
        String goneChildId = createTag(token, "GoneChild", goneId);
        String orphanId = createTag(token, "Orphan", null);
        createDocumentWithTag(token, "The document that keeps Keep alive", keptChildId);

        // PREVIEW: nothing is deleted by looking.
        JsonObject preview = target().path("/tag/maintenance").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Set<String> previewRoots = new HashSet<>();
        for (JsonValue value : preview.getJsonArray("tags")) {
            JsonObject tag = value.asJsonObject();
            if (tag.getBoolean("root")) {
                previewRoots.add(tag.getString("id"));
            }
        }
        Assertions.assertEquals(Set.of(goneId, orphanId), previewRoots);
        Assertions.assertEquals(5, tagIds(token).size(), "the preview deleted nothing");

        // CONFIRM.
        JsonObject json = target().path("/tag/maintenance").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
        Assertions.assertEquals(3, json.getInt("count"));
        Assertions.assertEquals(Set.of("Gone", "GoneChild", "Orphan"),
                new HashSet<>(namesOf(json.getJsonArray("tags"))));

        Set<String> remaining = tagIds(token);
        Assertions.assertEquals(Set.of(keepId, keptChildId), remaining,
                "the used branch stays and everything unused went");

        // A second sweep has nothing left to do and says so rather than failing.
        json = target().path("/tag/maintenance").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .delete(JsonObject.class);
        Assertions.assertEquals(0, json.getInt("count"));
        Assertions.assertEquals(0, json.getJsonArray("tags").size());
    }

    /**
     * A descendant the caller cannot see is still part of the subtree a cascade delete would take,
     * so its presence blocks the delete. Without this the maintenance path would remove another
     * user's tag — and their documents' tagging with it — through a parent that merely looked empty.
     */
    @Test
    public void testDescendantOwnedByAnotherUserBlocksTheDelete() {
        clientUtil.createUser("tagmaint4");
        clientUtil.createUser("tagmaint5");
        String ownerToken = clientUtil.login("tagmaint4");
        String otherToken = clientUtil.login("tagmaint5");

        String sharedId = createTag(ownerToken, "SharedRoot", null);
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .put(Entity.form(new Form()
                        .param("source", sharedId)
                        .param("perm", "READ")
                        .param("target", "tagmaint5")
                        .param("type", "USER")), JsonObject.class);
        String foreignChildId = createTag(otherToken, "TheirChild", sharedId);
        createDocumentWithTag(otherToken, "Their document", foreignChildId);

        JsonObject shared = maintenanceStatus(ownerToken, sharedId);
        Assertions.assertFalse(shared.getBoolean("deletable"), "a root with an invisible child");
        // The refusal must not confirm that a hidden descendant exists: the caller learns only that
        // the tag cannot be deleted. "permission" (or any sub-tag wording) would be an oracle for
        // the existence of another user's tag under a tag this caller owns.
        Assertions.assertEquals("other", shared.getString("reason"),
                "the blocked reason is the generic one, not one that names permissions or sub-tags");
        Assertions.assertEquals(0, shared.getInt("subtreeDocuments"),
                "the count is summed over the caller's readable tags, so the refusal discloses "
                        + "nothing about the other user's documents");
        Assertions.assertNull(maintenanceStatus(ownerToken, foreignChildId),
                "an invisible tag is not listed at all");

        Response response = target().path("/tag/" + sharedId + "/subtree").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .delete();
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        JsonObject error = response.readEntity(JsonObject.class);
        Assertions.assertEquals("TagNotDeletable", error.getString("type"));
        Assertions.assertFalse(error.getString("message").toLowerCase(java.util.Locale.ROOT).contains("sub-tag"),
                "the message must not mention sub-tags: " + error.getString("message"));
        Assertions.assertFalse(error.getString("message").toLowerCase(java.util.Locale.ROOT).contains("edit"),
                "nor permissions: " + error.getString("message"));

        Assertions.assertTrue(tagIds(ownerToken).contains(sharedId), "the root survives");
        Assertions.assertTrue(tagIds(otherToken).contains(foreignChildId), "their child survives");

        // …and the instance-wide cleanup does not reach it either.
        JsonObject json = target().path("/tag/maintenance").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .delete(JsonObject.class);
        Assertions.assertEquals(0, json.getInt("count"));
        Assertions.assertTrue(tagIds(otherToken).contains(foreignChildId));
    }

    /** Another user's tag is not deletable, and the refusal reads as "no such tag". */
    @Test
    public void testAnotherUsersTagIsNotFound() {
        clientUtil.createUser("tagmaint6");
        clientUtil.createUser("tagmaint7");
        String ownerToken = clientUtil.login("tagmaint6");
        String strangerToken = clientUtil.login("tagmaint7");

        String tagId = createTag(ownerToken, "PrivateTag", null);

        Response response = target().path("/tag/" + tagId + "/subtree").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, strangerToken)
                .delete();
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()));
        Assertions.assertTrue(tagIds(ownerToken).contains(tagId), "the owner's tag survives");
    }

    /**
     * A tag an auto-tagging rule points at is doing a job even with no document on it, so the
     * maintenance path treats it as in use. Deleting it would leave the rule pointing at nothing
     * and stop it working, with no warning anywhere.
     */
    @Test
    public void testTagTargetedByAnAutoTagRuleIsNotUnused() {
        String adminToken = adminToken();

        String tagId = createTag(adminToken, "RuledTag", null);
        target().path("/tagmatchrule").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("tag_id", tagId)
                        .param("rule_type", "TITLE_REGEX")
                        .param("pattern", "(?i)ruled.*")
                        .param("order", "1")
                        .param("enabled", "true")), JsonObject.class);

        JsonObject status = maintenanceStatus(adminToken, tagId);
        Assertions.assertNotNull(status);
        Assertions.assertFalse(status.getBoolean("deletable"), "the rule's target tag");
        Assertions.assertEquals("rule", status.getString("reason"));
        Assertions.assertEquals(0, status.getInt("subtreeDocuments"), "and it really carries no document");

        Response response = target().path("/tag/" + tagId + "/subtree").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("TagSubtreeInRule", response.readEntity(JsonObject.class).getString("type"));
        Assertions.assertTrue(tagIds(adminToken).contains(tagId), "the rule's target tag survives");
    }

    /**
     * A trashed document is RESTORABLE, and {@code DocumentDao#restore} revives exactly the
     * DocumentTag rows whose delete date equals the document's — so a tag only the trash still
     * references is not unused. Deleting it would strand those rows: the tag delete only touches
     * LIVE links ({@code dt.deleteDate is null}), so the trashed link survives and a later restore
     * re-attaches the document to a tag that no longer exists.
     */
    @Test
    public void testTagHeldOnlyByATrashedDocumentIsNotUnused() {
        clientUtil.createUser("tagmaint8");
        String token = clientUtil.login("tagmaint8");

        String tagId = createTag(token, "TrashHeld", null);
        String docId = createDocumentWithTag(token, "A document on its way to the trash", tagId);

        // Trash it — not a permanent delete. The document is still restorable.
        Response trashed = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .delete();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(trashed.getStatus()));

        JsonObject status = maintenanceStatus(token, tagId);
        Assertions.assertNotNull(status);
        Assertions.assertFalse(status.getBoolean("deletable"), "a tag the trash still references");
        // The DISPLAYED count stays active-only (that is the tag tree's #298 part-3 meaning), so the
        // reason must not be the documents one — it would quote a zero and send the user looking for
        // documents that are not in the list.
        Assertions.assertEquals(0, status.getInt("subtreeDocuments"), "no ACTIVE document carries it");
        Assertions.assertEquals("trash", status.getString("reason"));

        Response refused = target().path("/tag/" + tagId + "/subtree").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .delete();
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(refused.getStatus()));
        Assertions.assertEquals("TagSubtreeInUse", refused.readEntity(JsonObject.class).getString("type"));

        // …and the instance-wide sweep leaves it alone too.
        JsonObject sweep = target().path("/tag/maintenance").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .delete(JsonObject.class);
        Assertions.assertEquals(0, sweep.getInt("count"), "the sweep deleted nothing");
        Assertions.assertTrue(tagIds(token).contains(tagId), "the tag survives the sweep");

        // The acceptance: restore the document and its tagging is intact.
        Response restored = target().path("/document/" + docId + "/restore").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(restored.getStatus()));

        JsonObject document = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        JsonArray tags = document.getJsonArray("tags");
        boolean carriesTag = false;
        for (JsonValue value : tags) {
            if (value.asJsonObject().getString("id").equals(tagId)) {
                carriesTag = true;
            }
        }
        Assertions.assertTrue(carriesTag, "the restored document still carries the tag it was trashed with");
    }

    /** Anonymous callers reach none of it. */
    @Test
    public void testMaintenanceRequiresAuthentication() {
        Response response = target().path("/tag/maintenance").request().get();
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()));

        response = target().path("/tag/maintenance").request().delete();
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()));

        response = target().path("/tag/00000000-0000-0000-0000-000000000000/subtree").request().delete();
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()));
    }
}
