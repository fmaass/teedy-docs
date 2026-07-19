package com.sismics.docs.rest;

import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.AclDao;
import com.sismics.docs.core.model.jpa.Acl;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.Date;


/**
 * Test the ACL resource.
 *
 * @author bgamard
 */
public class TestAclResource extends BaseJerseyTest {
    /**
     * Test the ACL resource.
     */
    @Test
    public void testAclResource() {
        // Create aclGroup2
        clientUtil.createGroup("aclGroup2");

        // Login acl1
        clientUtil.createUser("acl1");
        String acl1Token = clientUtil.login("acl1");

        // Login acl2
        clientUtil.createUser("acl2", "aclGroup2");
        String acl2Token = clientUtil.login("acl2");

        // Create a document with acl1
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl1Token)
                .put(Entity.form(new Form()
                        .param("title", "My super title document 1")
                        .param("language", "eng")
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        String document1Id = json.getString("id");

        // Get the document as acl1
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl1Token)
                .get(JsonObject.class);
        Assertions.assertEquals(document1Id, json.getString("id"));
        JsonArray acls = json.getJsonArray("acls");
        Assertions.assertEquals(2, acls.size());

        // Get the document as acl2
        Response response = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl2Token)
                .get();
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()));

        // List all documents with acl2
        json = target().path("/document/list")
                .queryParam("sort_column", 3)
                .queryParam("asc", true)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl2Token)
                .get(JsonObject.class);
        JsonArray documents = json.getJsonArray("documents");
        Assertions.assertEquals(0, documents.size());

        // Add an ACL READ for acl2 with acl1
        json = target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl1Token)
                .put(Entity.form(new Form()
                        .param("source", document1Id)
                        .param("perm", "READ")
                        .param("target", "acl2")
                        .param("type", "USER")), JsonObject.class);
        String acl2Id = json.getString("id");

        // Add an ACL WRITE for acl2 with acl1
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl1Token)
                .put(Entity.form(new Form()
                        .param("source", document1Id)
                        .param("perm", "WRITE")
                        .param("target", "acl2")
                        .param("type", "USER")), JsonObject.class);

        // List all documents with acl2
        json = target().path("/document/list")
                .queryParam("sort_column", 3)
                .queryParam("asc", true)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl2Token)
                .get(JsonObject.class);
        documents = json.getJsonArray("documents");
        Assertions.assertEquals(1, documents.size());

        // Add an ACL WRITE for acl2 with acl1 (again)
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl1Token)
                .put(Entity.form(new Form()
                        .param("source", document1Id)
                        .param("perm", "WRITE")
                        .param("target", "acl2")
                        .param("type", "USER")), JsonObject.class);

        // Add an ACL READ for aclGroup2 with acl1
        json = target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl1Token)
                .put(Entity.form(new Form()
                        .param("source", document1Id)
                        .param("perm", "READ")
                        .param("target", "aclGroup2")
                        .param("type", "GROUP")), JsonObject.class);
        String aclGroup2Id = json.getString("id");

        // Add an ACL WRITE for aclGroup2 with acl1
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl1Token)
                .put(Entity.form(new Form()
                        .param("source", document1Id)
                        .param("perm", "WRITE")
                        .param("target", "aclGroup2")
                        .param("type", "GROUP")), JsonObject.class);

        // List all documents with acl2
        json = target().path("/document/list")
                .queryParam("sort_column", 3)
                .queryParam("asc", true)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl2Token)
                .get(JsonObject.class);
        documents = json.getJsonArray("documents");
        Assertions.assertEquals(1, documents.size());

        // Get the document as acl1
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl1Token)
                .get(JsonObject.class);
        Assertions.assertEquals(document1Id, json.getString("id"));
        acls = json.getJsonArray("acls");
        Assertions.assertEquals(6, acls.size());
        Assertions.assertTrue(json.getBoolean("writable"));

        // Get the document as acl2
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl2Token)
                .get(JsonObject.class);
        Assertions.assertEquals(document1Id, json.getString("id"));
        acls = json.getJsonArray("acls");
        Assertions.assertEquals(6, acls.size());
        Assertions.assertTrue(json.getBoolean("writable"));

        // Update the document as acl2
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl2Token)
                .post(Entity.form(new Form()
                        .param("title", "My new super document 1")
                        .param("language", "eng")), JsonObject.class);
        Assertions.assertEquals(document1Id, json.getString("id"));

        // Get the document as acl2
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl2Token)
                .get(JsonObject.class);
        Assertions.assertEquals(document1Id, json.getString("id"));
        JsonArray contributors = json.getJsonArray("contributors");
        Assertions.assertEquals(2, contributors.size());

        // Delete the ACL WRITE for acl2 with acl2
        target().path("/acl/" + document1Id + "/WRITE/" + acl2Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl2Token)
                .delete(JsonObject.class);

        // Get the document as acl2
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl2Token)
                .get(JsonObject.class);
        Assertions.assertEquals(document1Id, json.getString("id"));
        acls = json.getJsonArray("acls");
        Assertions.assertEquals(5, acls.size());
        Assertions.assertTrue(json.getBoolean("writable")); // Writable by aclGroup2

        // Delete the ACL WRITE for aclGroup2 with acl2
        target().path("/acl/" + document1Id + "/WRITE/" + aclGroup2Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl2Token)
                .delete(JsonObject.class);

        // Get the document as acl2
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl2Token)
                .get(JsonObject.class);
        Assertions.assertEquals(document1Id, json.getString("id"));
        acls = json.getJsonArray("acls");
        Assertions.assertEquals(4, acls.size());
        Assertions.assertFalse(json.getBoolean("writable"));

        // Delete the ACL READ for acl2 with acl2 (not authorized)
        response = target().path("/acl/" + document1Id + "/READ/" + acl2Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl2Token)
                .delete();
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()));

        // Delete the ACL READ for acl2 with acl1
        target().path("/acl/" + document1Id + "/READ/" + acl2Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl1Token)
                .delete(JsonObject.class);

        // Get the document as acl2 (visible by group)
        target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl2Token)
                .get(JsonObject.class);

        // Delete the ACL READ for aclGroup2 with acl1
        target().path("/acl/" + document1Id + "/READ/" + aclGroup2Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl1Token)
                .delete(JsonObject.class);

        // Get the document as acl1
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl1Token)
                .get(JsonObject.class);
        Assertions.assertEquals(document1Id, json.getString("id"));
        acls = json.getJsonArray("acls");
        Assertions.assertEquals(2, acls.size());
        String acl1Id = acls.getJsonObject(0).getString("id");

        // Get the document as acl2
        response = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl2Token)
                .get();
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()));

        // Delete the ACL READ for acl1 with acl1
        response = target().path("/acl/" + document1Id + "/READ/" + acl1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl1Token)
                .delete();
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));

        // Delete the ACL WRITE for acl1 with acl1
        response = target().path("/acl/" + document1Id + "/WRITE/" + acl1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl1Token)
                .delete();
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));

        // Search target list (acl)
        json = target().path("/acl/target/search")
                .queryParam("search", "acl")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl1Token)
                .get(JsonObject.class);
        JsonArray users = json.getJsonArray("users");
        Assertions.assertTrue(users.size() > 0);
        JsonArray groups = json.getJsonArray("groups");
        Assertions.assertTrue(groups.size() > 0);

        // Search target list (admin)
        json = target().path("/acl/target/search")
                .queryParam("search", "admin")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acl1Token)
                .get(JsonObject.class);
        users = json.getJsonArray("users");
        Assertions.assertEquals(0, users.size());
        groups = json.getJsonArray("groups");
        Assertions.assertEquals(0, groups.size());
    }

    @Test
    public void testAclTags() {
        // Login acltag1
        clientUtil.createUser("acltag1");
        String acltag1Token = clientUtil.login("acltag1");

        // Login acltag2
        clientUtil.createUser("acltag2");
        String acltag2Token = clientUtil.login("acltag2");

        // Create tag1 with acltag1
        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acltag1Token)
                .put(Entity.form(new Form()
                        .param("name", "AclTag1")
                        .param("color", "#ff0000")), JsonObject.class);
        String tag1Id = json.getString("id");
        Assertions.assertNotNull(tag1Id);

        // Create document1 with acltag1 tagged with tag1
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acltag1Token)
                .put(Entity.form(new Form()
                        .param("title", "My super document 1")
                        .param("tags", tag1Id)
                        .param("language", "eng")), JsonObject.class);
        String document1Id = json.getString("id");

        // acltag2 cannot see document1
        Response response = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acltag2Token)
                .get();
        Assertions.assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());

        // acltag2 cannot see any tag
        json = target().path("/tag/list").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acltag2Token)
                .get(JsonObject.class);
        JsonArray tags = json.getJsonArray("tags");
        Assertions.assertEquals(0, tags.size());

        // acltag2 cannot see tag1
        response = target().path("/tag/" + tag1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acltag2Token)
                .get();
        Assertions.assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());

        // acltag2 cannot see any document
        json = target().path("/document/list")
                .queryParam("sort_column", 3)
                .queryParam("asc", true)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acltag2Token)
                .get(JsonObject.class);
        JsonArray documents = json.getJsonArray("documents");
        Assertions.assertEquals(0, documents.size());

        // acltag2 cannot edit tag1
        response = target().path("/tag/" + tag1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acltag2Token)
                .post(Entity.form(new Form()
                        .param("name", "AclTag1")
                        .param("color", "#ff0000")));
        Assertions.assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());

        // acltag2 cannot edit document1
        response = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acltag2Token)
                .post(Entity.form(new Form()
                        .param("title", "My super document 1")
                        .param("tags", tag1Id)
                        .param("language", "eng")));
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus());

        // Add an ACL READ for acltag2 with acltag1 on tag1
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acltag1Token)
                .put(Entity.form(new Form()
                        .param("source", tag1Id)
                        .param("perm", "READ")
                        .param("target", "acltag2")
                        .param("type", "USER")), JsonObject.class);

        // acltag2 can see tag1
        json = target().path("/tag/" + tag1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acltag2Token)
                .get(JsonObject.class);
        Assertions.assertFalse(json.getBoolean("writable"));
        Assertions.assertEquals(3, json.getJsonArray("acls").size());

        // acltag2 still cannot edit tag1
        response = target().path("/tag/" + tag1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acltag2Token)
                .post(Entity.form(new Form()
                        .param("name", "AclTag1")
                        .param("color", "#ff0000")));
        Assertions.assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());

        // acltag2 still cannot edit document1
        response = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acltag2Token)
                .post(Entity.form(new Form()
                        .param("title", "My super document 1")
                        .param("tags", tag1Id)
                        .param("language", "eng")));
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus());

        // acltag2 can see document1 with tag1 (non-writable)
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acltag2Token)
                .get(JsonObject.class);
        tags = json.getJsonArray("tags");
        Assertions.assertEquals(1, tags.size());
        Assertions.assertFalse(json.getBoolean("writable"));
        Assertions.assertEquals(tag1Id, tags.getJsonObject(0).getString("id"));
        JsonArray inheritedAcls = json.getJsonArray("inherited_acls");
        Assertions.assertEquals(3, inheritedAcls.size());
        Assertions.assertEquals("AclTag1", inheritedAcls.getJsonObject(0).getString("source_name"));
        Assertions.assertEquals(tag1Id, inheritedAcls.getJsonObject(0).getString("source_id"));

        // acltag2 can see tag1
        json = target().path("/tag/list").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acltag2Token)
                .get(JsonObject.class);
        tags = json.getJsonArray("tags");
        Assertions.assertEquals(1, tags.size());
        Assertions.assertEquals(tag1Id, tags.getJsonObject(0).getString("id"));

        // acltag2 can see exactly one document
        json = target().path("/document/list")
                .queryParam("sort_column", 3)
                .queryParam("asc", true)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acltag2Token)
                .get(JsonObject.class);
        documents = json.getJsonArray("documents");
        Assertions.assertEquals(1, documents.size());

        // Add an ACL WRITE for acltag2 with acltag1 on tag1
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acltag1Token)
                .put(Entity.form(new Form()
                        .param("source", tag1Id)
                        .param("perm", "WRITE")
                        .param("target", "acltag2")
                        .param("type", "USER")), JsonObject.class);

        // acltag2 can see document1 with tag1 (writable)
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acltag2Token)
                .get(JsonObject.class);
        tags = json.getJsonArray("tags");
        Assertions.assertEquals(1, tags.size());
        Assertions.assertTrue(json.getBoolean("writable"));
        Assertions.assertEquals(tag1Id, tags.getJsonObject(0).getString("id"));
        inheritedAcls = json.getJsonArray("inherited_acls");
        Assertions.assertEquals(4, inheritedAcls.size());
        Assertions.assertEquals("AclTag1", inheritedAcls.getJsonObject(0).getString("source_name"));
        Assertions.assertEquals(tag1Id, inheritedAcls.getJsonObject(0).getString("source_id"));

        // acltag2 can see and edit tag1
        json = target().path("/tag/" + tag1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acltag2Token)
                .get(JsonObject.class);
        Assertions.assertTrue(json.getBoolean("writable"));
        Assertions.assertEquals(4, json.getJsonArray("acls").size());

        // acltag2 can edit tag1
        target().path("/tag/" + tag1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acltag2Token)
                .post(Entity.form(new Form()
                        .param("name", "AclTag1")
                        .param("color", "#ff0000")), JsonObject.class);

        // acltag2 can edit document1
        target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acltag2Token)
                .post(Entity.form(new Form()
                        .param("title", "My super document 1")
                        .param("tags", tag1Id)
                        .param("language", "eng")), JsonObject.class);
    }

    /**
     * #88: a tag's ACL revocation must never remove its final WRITE holder. The guard lives inside
     * {@code AclDao.delete} (under the tag row lock) and runs AFTER the resource's creator base-ACL
     * check, so the API-reachable state it protects (issue #122) is a NON-creator sole WRITE holder —
     * which arises when the creator's account is deleted ({@code UserDao.delete} soft-deletes the
     * creator's ACLs). We reproduce that by soft-deleting the creator's ACL rows, then assert the
     * surviving co-owner cannot revoke its own last WRITE (mapped to the last-write client error).
     */
    @Test
    public void testTagLastWriteLockout() {
        // Creator (acllock1) and a co-owner (acllockco)
        clientUtil.createUser("acllock1");
        String acllock1Token = clientUtil.login("acllock1");
        clientUtil.createUser("acllockco");
        String acllockcoToken = clientUtil.login("acllockco");

        // acllock1 creates a tag and grants acllockco READ + WRITE -> a full co-owner, two WRITE holders
        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acllock1Token)
                .put(Entity.form(new Form()
                        .param("name", "LockoutTag")
                        .param("color", "#00ff00")), JsonObject.class);
        String tagId = json.getString("id");
        grantAcl(acllock1Token, tagId, "READ", "acllockco");
        grantAcl(acllock1Token, tagId, "WRITE", "acllockco");

        json = target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acllock1Token)
                .get(JsonObject.class);
        String creatorId = writeTargetId(json.getJsonArray("acls"), "acllock1");
        String coOwnerId = writeTargetId(json.getJsonArray("acls"), "acllockco");
        Assertions.assertNotNull(creatorId, "creator must hold a WRITE ACL on a freshly created tag");
        Assertions.assertNotNull(coOwnerId, "co-owner must hold a WRITE ACL after the grant");

        // Mirror user deletion: soft-delete the creator's ACL rows, leaving acllockco as the
        // NON-creator sole WRITE holder — the state the creator base-ACL check no longer covers.
        softDeleteAcls(tagId, creatorId);

        // The sole owner cannot revoke its own last WRITE — the guard refuses it.
        Response response = target().path("/acl/" + tagId + "/WRITE/" + coOwnerId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acllockcoToken)
                .delete();
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        JsonObject error = response.readEntity(JsonObject.class);
        Assertions.assertEquals("AclError", error.getString("type"));
        Assertions.assertEquals("Cannot remove the last write permission on a tag", error.getString("message"));

        // The WRITE ACL is still present
        json = target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acllockcoToken)
                .get(JsonObject.class);
        Assertions.assertNotNull(writeTargetId(json.getJsonArray("acls"), "acllockco"),
                "the last WRITE ACL must survive a lockout-guarded delete");
    }

    /**
     * #88: the last-write guard must be precise — a WRITE grant that is NOT the last one is
     * removable. Two owners hold WRITE; revoking one leaves the other, so the delete
     * succeeds. A guard that blocked every WRITE removal would fail this.
     */
    @Test
    public void testTagWriteRemovableWhenNotLast() {
        // Login acllock2 (owner) and acllock3 (co-owner)
        clientUtil.createUser("acllock2");
        String acllock2Token = clientUtil.login("acllock2");
        clientUtil.createUser("acllock3");
        clientUtil.login("acllock3");

        // acllock2 creates a tag, then grants acllock3 WRITE -> two WRITE holders
        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acllock2Token)
                .put(Entity.form(new Form()
                        .param("name", "TwoOwnersTag")
                        .param("color", "#0000ff")), JsonObject.class);
        String tagId = json.getString("id");

        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acllock2Token)
                .put(Entity.form(new Form()
                        .param("source", tagId)
                        .param("perm", "WRITE")
                        .param("target", "acllock3")
                        .param("type", "USER")), JsonObject.class);

        json = target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acllock2Token)
                .get(JsonObject.class);
        String coOwnerId = writeTargetId(json.getJsonArray("acls"), "acllock3");
        Assertions.assertNotNull(coOwnerId, "co-owner must hold a WRITE ACL after the grant");

        // Revoking the co-owner's WRITE succeeds — the creator still holds WRITE
        target().path("/acl/" + tagId + "/WRITE/" + coOwnerId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acllock2Token)
                .delete(JsonObject.class);

        json = target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, acllock2Token)
                .get(JsonObject.class);
        Assertions.assertNull(writeTargetId(json.getJsonArray("acls"), "acllock3"),
                "the co-owner's WRITE ACL must be removed");
        Assertions.assertNotNull(writeTargetId(json.getJsonArray("acls"), "acllock2"),
                "the creator's WRITE ACL must remain");
    }

    /**
     * #88 (R2): the last-write guard counts DISTINCT holders, not rows. A sole holder represented
     * by two duplicate WRITE rows (a raced double-grant the API dedup normally prevents) must still
     * trip the guard — {@code AclDao.delete} removes ALL of that holder's rows, so two rows are still
     * one owner. A row-count guard would see 2, skip, and strand the tag at zero. The duplicate is
     * inserted straight through the DAO; the creator's ACLs are soft-deleted so the reachable state
     * (a NON-creator sole holder) is what the guard evaluates.
     */
    @Test
    public void testTagLastWriteLockoutIgnoresDuplicateWriteRows() {
        clientUtil.createUser("acldupw1");
        String creatorToken = clientUtil.login("acldupw1");
        clientUtil.createUser("acldupw2");
        String coOwnerToken = clientUtil.login("acldupw2");

        // Creator makes a tag and grants acldupw2 READ + WRITE.
        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, creatorToken)
                .put(Entity.form(new Form()
                        .param("name", "DupWriteTag")
                        .param("color", "#123456")), JsonObject.class);
        String tagId = json.getString("id");
        grantAcl(creatorToken, tagId, "READ", "acldupw2");
        grantAcl(creatorToken, tagId, "WRITE", "acldupw2");

        json = target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, creatorToken)
                .get(JsonObject.class);
        String creatorId = writeTargetId(json.getJsonArray("acls"), "acldupw1");
        String coOwnerId = writeTargetId(json.getJsonArray("acls"), "acldupw2");
        Assertions.assertNotNull(coOwnerId);

        // Insert a DUPLICATE WRITE row for the co-owner (bypassing the API dedup) — two rows, one
        // distinct holder — then soft-delete the creator's ACLs so acldupw2 is the sole holder.
        TransactionUtil.handle(() -> {
            Acl duplicate = new Acl();
            duplicate.setSourceId(tagId);
            duplicate.setPerm(PermType.WRITE);
            duplicate.setType(AclType.USER);
            duplicate.setTargetId(coOwnerId);
            new AclDao().create(duplicate, coOwnerId);
        });
        softDeleteAcls(tagId, creatorId);

        // Removing acldupw2's WRITE is refused: DISTINCT holders == 1 despite the two rows (a
        // row-count guard would see 2, skip, and delete both rows).
        Response response = target().path("/acl/" + tagId + "/WRITE/" + coOwnerId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, coOwnerToken)
                .delete();
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        JsonObject error = response.readEntity(JsonObject.class);
        Assertions.assertEquals("AclError", error.getString("type"));
        Assertions.assertEquals("Cannot remove the last write permission on a tag", error.getString("message"));
    }

    /** Grants an ACL on {@code sourceId} to {@code targetName} via the real API as the given principal. */
    private void grantAcl(String token, String sourceId, String perm, String targetName) {
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("source", sourceId)
                        .param("perm", perm)
                        .param("target", targetName)
                        .param("type", "USER")), JsonObject.class);
    }

    /** Soft-deletes every non-deleted ACL of {@code targetId} on {@code sourceId} (mirrors user deletion). */
    private static void softDeleteAcls(String sourceId, String targetId) {
        TransactionUtil.handle(() -> ThreadLocalContext.get().getEntityManager()
                .createQuery("update Acl a set a.deleteDate = :now where a.sourceId = :src"
                        + " and a.targetId = :target and a.deleteDate is null")
                .setParameter("now", new Date())
                .setParameter("src", sourceId)
                .setParameter("target", targetId)
                .executeUpdate());
    }

    /**
     * Returns the target id of the WRITE ACL granted to {@code name}, or null if none.
     */
    private static String writeTargetId(JsonArray acls, String name) {
        for (int i = 0; i < acls.size(); i++) {
            JsonObject acl = acls.getJsonObject(i);
            if ("WRITE".equals(acl.getString("perm")) && name.equals(acl.getString("name"))) {
                return acl.getString("id");
            }
        }
        return null;
    }

    /**
     * Regression test for the USER-&gt;GROUP fall-through in
     * {@code SecurityUtil.getTargetIdFromName}: requesting a USER ACL for a name that
     * only matches a group (no active user of that name) must be rejected as an invalid
     * target, not silently resolved to the same-named group.
     */
    @Test
    public void testAclUserTargetDoesNotFallThroughToGroup() {
        // Create a group with a name that no user shares
        clientUtil.createGroup("phantomtarget");

        // Login the document owner
        clientUtil.createUser("aclphantom1");
        String owner = clientUtil.login("aclphantom1");

        // Create a document as the owner
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, owner)
                .put(Entity.form(new Form()
                        .param("title", "Phantom target document")
                        .param("language", "eng")
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        String documentId = json.getString("id");

        // Grant a USER ACL targeting "phantomtarget" (a group name, but no such user exists)
        Response response = target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, owner)
                .put(Entity.form(new Form()
                        .param("source", documentId)
                        .param("perm", "READ")
                        .param("target", "phantomtarget")
                        .param("type", "USER")));

        // Must be rejected as an invalid target (400), not resolved to the group
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        JsonObject error = response.readEntity(JsonObject.class);
        Assertions.assertEquals("InvalidTarget", error.getString("type"));

        // And no ACL should have been created for that name
        json = target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, owner)
                .get(JsonObject.class);
        JsonArray acls = json.getJsonArray("acls");
        for (int i = 0; i < acls.size(); i++) {
            Assertions.assertNotEquals("phantomtarget", acls.getJsonObject(i).getString("name"),
                    "A phantom USER ACL was created targeting the same-named group");
        }
    }

    /**
     * Disambiguation test: when an active user and an active group share the same name,
     * a USER grant resolves to the user id (type USER) and a GROUP grant resolves to the
     * group id (type GROUP). The two ACLs are distinct entries pointing at different ids.
     */
    @Test
    public void testAclUserAndGroupSameNameDisambiguation() {
        // Create a group and a user that share the same name
        clientUtil.createGroup("acldup");
        clientUtil.createUser("acldup");

        // Login the document owner
        clientUtil.createUser("acldupowner");
        String owner = clientUtil.login("acldupowner");

        // Create a document as the owner
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, owner)
                .put(Entity.form(new Form()
                        .param("title", "Disambiguation document")
                        .param("language", "eng")
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        String documentId = json.getString("id");

        // Grant a USER ACL for "acldup" -> must resolve to the user
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, owner)
                .put(Entity.form(new Form()
                        .param("source", documentId)
                        .param("perm", "READ")
                        .param("target", "acldup")
                        .param("type", "USER")), JsonObject.class);

        // Grant a GROUP ACL for "acldup" -> must resolve to the group
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, owner)
                .put(Entity.form(new Form()
                        .param("source", documentId)
                        .param("perm", "READ")
                        .param("target", "acldup")
                        .param("type", "GROUP")), JsonObject.class);

        // Read the ACLs back: the two "acldup" grants must target different ids with
        // distinct target types (one USER, one GROUP).
        json = target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, owner)
                .get(JsonObject.class);
        JsonArray acls = json.getJsonArray("acls");

        String userAclId = null;
        String groupAclId = null;
        for (int i = 0; i < acls.size(); i++) {
            JsonObject acl = acls.getJsonObject(i);
            if (!"acldup".equals(acl.getString("name"))) {
                continue;
            }
            if ("USER".equals(acl.getString("type"))) {
                userAclId = acl.getString("id");
            } else if ("GROUP".equals(acl.getString("type"))) {
                groupAclId = acl.getString("id");
            }
        }

        Assertions.assertNotNull(userAclId, "USER grant for 'acldup' should resolve to a user target");
        Assertions.assertNotNull(groupAclId, "GROUP grant for 'acldup' should resolve to a group target");
        Assertions.assertNotEquals(userAclId, groupAclId,
                "USER and GROUP grants for the same name must resolve to different target ids");
    }
}