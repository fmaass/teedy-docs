package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

/**
 * Test the tag resource.
 * 
 * @author bgamard
 */
public class TestTagResource extends BaseJerseyTest {
    /**
     * Test the tag resource.
     */
    @Test
    public void testTagResource() {
        // Login tag1
        clientUtil.createUser("tag1");
        String tag1Token = clientUtil.login("tag1");

        // Create a tag with a wrong name
        Response response = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .put(Entity.form(new Form()
                        .param("name", "Tag:3")
                        .param("color", "#ff0000")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));

        // Create a tag with a wrong name
        response = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .put(Entity.form(new Form()
                        .param("name", "Tag 3")
                        .param("color", "#ff0000")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));

        // Create a tag with a 7-character color that is not hexadecimal. The colour is rendered
        // straight into the tag chip's style, so "looks like a colour" is not good enough.
        response = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .put(Entity.form(new Form()
                        .param("name", "TagBadColor")
                        .param("color", "#gggggg")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));

        // Create a tag
        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .put(Entity.form(new Form()
                        .param("name", "Tag3")
                        .param("color", "#ff0000")), JsonObject.class);
        String tag3Id = json.getString("id");
        Assertions.assertNotNull(tag3Id);
        
        // Create a tag
        json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .put(Entity.form(new Form()
                        .param("name", "Tag4")
                        .param("color", "#00ff00")
                        .param("parent", tag3Id)), JsonObject.class);
        String tag4Id = json.getString("id");
        Assertions.assertNotNull(tag4Id);

        // Create a circular reference
        response = target().path("/tag/" + tag3Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .post(Entity.form(new Form()
                        .param("name", "Tag3")
                        .param("color", "#0000ff")
                        .param("parent", tag4Id)));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));

        // Get the tag
        json = target().path("/tag/" + tag4Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .get(JsonObject.class);
        Assertions.assertEquals("Tag4", json.getString("name"));
        Assertions.assertEquals("tag1", json.getString("creator"));
        Assertions.assertEquals("#00ff00", json.getString("color"));
        Assertions.assertEquals(tag3Id, json.getString("parent"));
        Assertions.assertTrue(json.getBoolean("writable"));
        JsonArray acls = json.getJsonArray("acls");
        Assertions.assertEquals(2, acls.size());
        
        // Create a tag with space (not allowed)
        response = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .put(Entity.form(new Form()
                        .param("name", "Tag 4")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        
        // Create a document
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .put(Entity.form(new Form()
                        .param("title", "My super document 1")
                        .param("tags", tag3Id)
                        .param("language", "eng")), JsonObject.class);
        String document1Id = json.getString("id");
        
        // Create a document
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .put(Entity.form(new Form()
                        .param("title", "My super document 2")
                        .param("tags", tag4Id)
                        .param("language", "eng")), JsonObject.class);
        String document2Id = json.getString("id");

        // Search document by parent tag
        json = target().path("/document/list")
                .queryParam("search", "tag:Tag3")
                .queryParam("asc", "true")
                .queryParam("sort_column", "1")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .get(JsonObject.class);
        Assertions.assertEquals(2, json.getJsonArray("documents").size());
        Assertions.assertEquals(document1Id, json.getJsonArray("documents").getJsonObject(0).getString("id"));
        Assertions.assertEquals(document2Id, json.getJsonArray("documents").getJsonObject(1).getString("id"));

        // Search document by children tag
        json = target().path("/document/list")
                .queryParam("search", "tag:Tag4")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonArray("documents").size());
        Assertions.assertEquals(document2Id, json.getJsonArray("documents").getJsonObject(0).getString("id"));

        // Check tags on a document
        json = target().path("/document/" + document2Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .get(JsonObject.class);
        JsonArray tags = json.getJsonArray("tags");
        Assertions.assertEquals(1, tags.size());
        Assertions.assertEquals(tag4Id, tags.getJsonObject(0).getString("id"));
        
        // Update tags on a document
        response = target().path("/document/" + document2Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .post(Entity.form(new Form()
                        .param("title", "My super document 2")
                        .param("language", "eng")
                        .param("tags", tag3Id)
                        .param("tags", tag4Id)));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        
        // Check tags on a document
        json = target().path("/document/" + document2Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .get(JsonObject.class);
        tags = json.getJsonArray("tags");
        Assertions.assertEquals(2, tags.size());
        Assertions.assertEquals(tag3Id, tags.getJsonObject(0).getString("id"));
        Assertions.assertEquals(tag4Id, tags.getJsonObject(1).getString("id"));
        
        // Update tags on a document
        response = target().path("/document/" + document2Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .post(Entity.form(new Form()
                        .param("title", "My super document 2")
                        .param("language", "eng")
                        .param("tags", tag4Id)));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        
        // Check tags on a document
        json = target().path("/document/" + document2Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .get(JsonObject.class);
        tags = json.getJsonArray("tags");
        Assertions.assertEquals(1, tags.size());
        Assertions.assertEquals(tag4Id, tags.getJsonObject(0).getString("id"));
        
        // Get all tags
        json = target().path("/tag/list").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .get(JsonObject.class);
        tags = json.getJsonArray("tags");
        Assertions.assertEquals(2, tags.size());
        Assertions.assertEquals("Tag4", tags.getJsonObject(1).getString("name"));
        Assertions.assertEquals("#00ff00", tags.getJsonObject(1).getString("color"));
        Assertions.assertEquals(tag3Id, tags.getJsonObject(1).getString("parent"));
        
        // Update a tag
        json = target().path("/tag/" + tag4Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .post(Entity.form(new Form()
                        .param("name", "UpdatedName")
                        .param("color", "#0000ff")), JsonObject.class);
        Assertions.assertEquals(tag4Id, json.getString("id"));
        
        // Get all tags
        json = target().path("/tag/list").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .get(JsonObject.class);
        tags = json.getJsonArray("tags");
        Assertions.assertEquals(2, tags.size());
        Assertions.assertEquals("UpdatedName", tags.getJsonObject(1).getString("name"));
        Assertions.assertEquals("#0000ff", tags.getJsonObject(1).getString("color"));
        Assertions.assertNull(tags.getJsonObject(1).get("parent"));

        // Update a tag
        json = target().path("/tag/" + tag4Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .post(Entity.form(new Form()
                        .param("name", "UpdatedName")
                        .param("color", "#0000ff")
                        .param("parent", tag3Id)), JsonObject.class);
        Assertions.assertEquals(tag4Id, json.getString("id"));

        // Get all tags
        json = target().path("/tag/list").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .get(JsonObject.class);
        tags = json.getJsonArray("tags");
        Assertions.assertEquals(2, tags.size());
        Assertions.assertEquals(tag3Id, tags.getJsonObject(1).getString("parent"));

        // Deletes a tag
        target().path("/tag/" + tag3Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .delete();
        
        // Get all tags
        json = target().path("/tag/list").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tag1Token)
                .get(JsonObject.class);
        tags = json.getJsonArray("tags");
        Assertions.assertEquals(1, tags.size());
        Assertions.assertEquals("UpdatedName", tags.getJsonObject(0).getString("name"));
        Assertions.assertNull(tags.getJsonObject(0).get("parent"));

        // Deletes user tag1
        String adminToken = adminToken();
        target().path("/user/tag1")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    /**
     * A user must not be able to mutate or delete another user's tag. Both the
     * update (POST) and delete (DELETE) endpoints require a WRITE ACL and return
     * NOT_FOUND otherwise. Each assertion fails if the corresponding WRITE ACL
     * guard in TagResource is removed.
     */
    @Test
    public void testTagCrossUserDenied() {
        String adminToken = adminToken();

        // Owner and stranger
        clientUtil.createUser("tag_owner");
        String ownerToken = clientUtil.login("tag_owner");
        clientUtil.createUser("tag_stranger");
        String strangerToken = clientUtil.login("tag_stranger");

        // Owner creates a tag
        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .put(Entity.form(new Form()
                        .param("name", "OwnerTag")
                        .param("color", "#123456")), JsonObject.class);
        String ownerTagId = json.getString("id");
        Assertions.assertNotNull(ownerTagId);

        // Stranger cannot update the owner's tag
        Response response = target().path("/tag/" + ownerTagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, strangerToken)
                .post(Entity.form(new Form()
                        .param("name", "Hijacked")
                        .param("color", "#000000")));
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()),
                "a user must not update another user's tag");

        // Stranger cannot delete the owner's tag
        response = target().path("/tag/" + ownerTagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, strangerToken)
                .delete();
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()),
                "a user must not delete another user's tag");

        // The owner's tag is unchanged (mutation/delete were actually denied)
        json = target().path("/tag/" + ownerTagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .get(JsonObject.class);
        Assertions.assertEquals("OwnerTag", json.getString("name"));
        Assertions.assertEquals("#123456", json.getString("color"));

        // Cleanup
        target().path("/user/tag_owner")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        target().path("/user/tag_stranger")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    /**
     * An asterisk is refused in a tag name, on creation and on rename alike: it is the wildcard of
     * the search grammar, so such a name could not be searched for unambiguously.
     */
    @Test
    public void testTagNameAsteriskRejected() {
        String adminToken = adminToken();
        clientUtil.createUser("tag_star");
        String token = clientUtil.login("tag_star");

        Response response = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("name", "Report*")
                        .param("color", "#ff0000")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()),
                "an asterisk must not be accepted in a new tag name");

        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("name", "Report")
                        .param("color", "#ff0000")), JsonObject.class);
        String tagId = json.getString("id");

        response = target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("name", "Report*")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()),
                "an asterisk must not be accepted when renaming a tag");

        // The rename was really refused
        json = target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals("Report", json.getString("name"));

        // Cleanup
        target().path("/user/tag_star")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    /**
     * A tag update that carries no name is a colour- or parent-only edit and must leave the name
     * alone instead of failing on the name validation.
     */
    @Test
    public void testTagUpdateWithoutName() {
        String adminToken = adminToken();
        clientUtil.createUser("tag_noname");
        String token = clientUtil.login("tag_noname");

        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("name", "Colored")
                        .param("color", "#ff0000")), JsonObject.class);
        String tagId = json.getString("id");

        Response response = target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("color", "#00ff00")));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()),
                "a colour-only tag update must succeed");

        json = target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals("Colored", json.getString("name"), "the name must survive the update");
        Assertions.assertEquals("#00ff00", json.getString("color"));

        // Cleanup
        target().path("/user/tag_noname")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    /** U+200B ZERO WIDTH SPACE — an invisible format character (Unicode category Cf). */
    private static final String ZWSP = new String(Character.toChars(0x200B));

    /** U+200D ZERO WIDTH JOINER — an invisible format character (Cf). */
    private static final String ZWJ = new String(Character.toChars(0x200D));

    /** U+FEFF ZERO WIDTH NO-BREAK SPACE / BOM — an invisible format character (Cf). */
    private static final String BOM = new String(Character.toChars(0xFEFF));

    /** U+2009 THIN SPACE — a VISIBLE whitespace character (Unicode category Zs). */
    private static final String THIN_SPACE = new String(Character.toChars(0x2009));

    /** U+00A0 NO-BREAK SPACE — a VISIBLE whitespace character (Zs). */
    private static final String NBSP = new String(Character.toChars(0x00A0));

    /** U+3000 IDEOGRAPHIC SPACE — a VISIBLE whitespace character (Zs). */
    private static final String IDEOGRAPHIC_SPACE = new String(Character.toChars(0x3000));

    /**
     * #305, CREATE path. A name pasted from a "whitespace generator" carries characters that do not
     * render. The two classes are answered differently and the difference is the whole point:
     *
     * <ul>
     *   <li>an INVISIBLE format character (zero-width space/joiner, BOM) is removed silently — it
     *       carries no visible meaning, so refusing it would only tell the user to delete something
     *       they cannot see;</li>
     *   <li>a VISIBLE whitespace character (thin space, no-break space, ideographic space) is
     *       REFUSED with the same error an ordinary space already gets — silently deleting it would
     *       turn "Test 123" into "Test123" while the same name typed with a normal space fails.</li>
     * </ul>
     */
    @Test
    public void tagCreateNormalizesInvisiblesAndRefusesVisibleWhitespace() {
        String adminToken = adminToken();
        clientUtil.createUser("tag_ws_create");
        String token = clientUtil.login("tag_ws_create");

        // Invisible format characters are stripped: the tag is created under the clean name.
        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("name", "Rech" + ZWSP + "nung" + BOM)
                        .param("color", "#ff0000")), JsonObject.class);
        String tagId = json.getString("id");

        json = target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals("Rechnung", json.getString("name"),
                "an invisible format character must be stripped before the name is stored");

        // Visible whitespace is refused, exactly like an ordinary space.
        for (String separator : new String[] { THIN_SPACE, NBSP, IDEOGRAPHIC_SPACE }) {
            Response response = target().path("/tag").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                    .put(Entity.form(new Form()
                            .param("name", "Test" + separator + "123")
                            .param("color", "#ff0000")));
            Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()),
                    "a visible whitespace character must be refused in a tag name");
        }

        // A name that is nothing but invisible characters has no name left after stripping.
        Response response = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("name", ZWSP + ZWJ + BOM)
                        .param("color", "#ff0000")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()),
                "a name consisting only of invisible characters must be refused, not stored empty");

        // Cleanup
        target().path("/user/tag_ws_create")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    /**
     * #305, RENAME path. The same rule has to hold on update, or the validation is a front door with
     * the back door open: a tag created clean could be renamed to carry the exotic characters.
     */
    @Test
    public void tagRenameNormalizesInvisiblesAndRefusesVisibleWhitespace() {
        String adminToken = adminToken();
        clientUtil.createUser("tag_ws_rename");
        String token = clientUtil.login("tag_ws_rename");

        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("name", "Beleg")
                        .param("color", "#ff0000")), JsonObject.class);
        String tagId = json.getString("id");

        // Rename carrying invisible format characters: accepted, stored stripped.
        Response response = target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("name", "Quit" + ZWJ + "tung")));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));

        json = target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals("Quittung", json.getString("name"),
                "an invisible format character must be stripped before the rename is stored");

        // Rename carrying visible whitespace: refused, and the stored name is untouched.
        for (String separator : new String[] { THIN_SPACE, NBSP, IDEOGRAPHIC_SPACE }) {
            response = target().path("/tag/" + tagId).request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                    .post(Entity.form(new Form()
                            .param("name", "Test" + separator + "123")));
            Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()),
                    "a visible whitespace character must be refused when renaming a tag");
        }

        // A rename to nothing but invisible characters is refused too.
        response = target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("name", ZWSP + BOM)));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()),
                "a rename to only invisible characters must be refused, not stored empty");

        json = target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals("Quittung", json.getString("name"),
                "a refused rename must leave the stored name untouched");

        // Cleanup
        target().path("/user/tag_ws_rename")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    /**
     * #305 ordering. Normalization runs BEFORE the length bound, so the 36-character limit is
     * measured on the name that will actually be stored. A 36-character name that happens to carry a
     * zero-width character is 36 characters, not 37 — refusing it as overlength would tell the user
     * to shorten a name that is already exactly at the limit, over a character they cannot see.
     */
    @Test
    public void tagNameIsNormalizedBeforeTheLengthBoundIsApplied() {
        String adminToken = adminToken();
        clientUtil.createUser("tag_ws_len");
        String token = clientUtil.login("tag_ws_len");

        String maxLengthName = "R".repeat(36);
        Assertions.assertEquals(36, maxLengthName.length(), "fixture must sit exactly on the limit");

        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("name", maxLengthName.substring(0, 4) + ZWSP + maxLengthName.substring(4))
                        .param("color", "#ff0000")), JsonObject.class);
        String tagId = json.getString("id");

        json = target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals(maxLengthName, json.getString("name"),
                "a 36-character name plus an invisible character must be stored, not refused as overlength");

        // The bound itself still bites once the name really is too long.
        Response response = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("name", "R".repeat(37))
                        .param("color", "#ff0000")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()),
                "37 real characters must still be refused");

        // Cleanup
        target().path("/user/tag_ws_len")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    /**
     * #305 edge whitespace. A LEADING or TRAILING ordinary space has always been trimmed rather than
     * refused, and the exotic spaces must behave the same way — the rule is about what the name IS,
     * not about how carefully it was pasted. Interior whitespace is the thing being refused, and a
     * no-break space on the edge is not interior.
     */
    @Test
    public void tagNameEdgeWhitespaceIsTrimmedLikeAnOrdinarySpace() {
        String adminToken = adminToken();
        clientUtil.createUser("tag_ws_edge");
        String token = clientUtil.login("tag_ws_edge");

        String[] padded = {
                " Report ",                       // ordinary space, the behaviour being matched
                "\tReport\t",                     // tab
                NBSP + "Report" + NBSP,           // no-break space
                THIN_SPACE + "Report" + THIN_SPACE,
                IDEOGRAPHIC_SPACE + "Report",
                "Report" + ZWSP + NBSP,           // invisible + edge space together
        };
        int index = 0;
        for (String name : padded) {
            JsonObject json = target().path("/tag").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                    .put(Entity.form(new Form()
                            .param("name", name)
                            .param("color", "#ff0000")), JsonObject.class);
            String tagId = json.getString("id");
            json = target().path("/tag/" + tagId).request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                    .get(JsonObject.class);
            Assertions.assertEquals("Report", json.getString("name"),
                    "edge whitespace must be trimmed exactly like an ordinary space (case " + index + ")");
            index++;
        }

        // Interior whitespace is still refused — trimming the edges must not soften that.
        Response response = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("name", " Re" + THIN_SPACE + "port ")
                        .param("color", "#ff0000")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()),
                "an interior thin space must still be refused after the edges are trimmed");

        // Cleanup
        target().path("/user/tag_ws_edge")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }
}
