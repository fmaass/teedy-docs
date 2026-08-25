package com.sismics.docs.rest;

import java.util.Date;

import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.sismics.util.filter.TokenBasedSecurityFilter;

/**
 * Exhaustive test of the comment resource.
 * 
 * @author bgamard
 */
public class TestCommentResource extends BaseJerseyTest {
    /**
     * Test the comment resource.
     */
    @Test
    public void testCommentResource() {
        // Login comment1
        clientUtil.createUser("comment1");
        String comment1Token = clientUtil.login("comment1");
        
        // Login comment2
        clientUtil.createUser("comment2");
        String comment2Token = clientUtil.login("comment2");
        
        // Create a document with comment1
        long create1Date = new Date().getTime();
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, comment1Token)
                .put(Entity.form(new Form()
                        .param("title", "My super title document 1")
                        .param("description", "My super description for document 1")
                        .param("language", "eng")
                        .param("create_date", Long.toString(create1Date))), JsonObject.class);
        String document1Id = json.getString("id");
        Assertions.assertNotNull(document1Id);
        
        // Create a comment with comment2 (fail, no read access)
        Response response = target().path("/comment").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, comment2Token)
                .put(Entity.form(new Form()
                        .param("id", document1Id)
                        .param("content", "Comment by comment2")));
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()));
        
        // Read comments with comment2 (fail, no read access)
        response = target().path("/comment/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, comment2Token)
                .get();
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()));
        
        // Read comments with comment 1
        json = target().path("/comment/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, comment1Token)
                .get(JsonObject.class);
        Assertions.assertEquals(0, json.getJsonArray("comments").size());
        
        // Create a comment with comment1
        json = target().path("/comment").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, comment1Token)
                .put(Entity.form(new Form()
                        .param("id", document1Id)
                        .param("content", "Comment by comment1")), JsonObject.class);
        String comment1Id = json.getString("id");
        Assertions.assertNotNull(comment1Id);
        Assertions.assertEquals("Comment by comment1", json.getString("content"));
        Assertions.assertEquals("comment1", json.getString("creator"));
        Assertions.assertNotNull(json.getJsonNumber("create_date"));
        
        // Read comments with comment1
        json = target().path("/comment/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, comment1Token)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonArray("comments").size());
        Assertions.assertEquals(comment1Id, json.getJsonArray("comments").getJsonObject(0).getString("id"));
        
        // Delete a comment with comment2 (fail, no write access)
        response = target().path("/comment/" + comment1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, comment2Token)
                .delete();
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()));
        
        // Delete a comment with comment1
        json = target().path("/comment/" + comment1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, comment1Token)
                .delete(JsonObject.class);
        
        // Read comments with comment1
        json = target().path("/comment/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, comment1Token)
                .get(JsonObject.class);
        Assertions.assertEquals(0, json.getJsonArray("comments").size());
        
        // Add an ACL READ for comment2 with comment1
        json = target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, comment1Token)
                .put(Entity.form(new Form()
                        .param("source", document1Id)
                        .param("perm", "READ")
                        .param("target", "comment2")
                        .param("type", "USER")), JsonObject.class);
        
        // Create a comment with comment2
        json = target().path("/comment").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, comment2Token)
                .put(Entity.form(new Form()
                        .param("id", document1Id)
                        .param("content", "Comment by comment2")), JsonObject.class);
        String comment2Id = json.getString("id");
        
        // Read comments with comment2
        json = target().path("/comment/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, comment2Token)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonArray("comments").size());
        JsonObject comment = json.getJsonArray("comments").getJsonObject(0);
        Assertions.assertEquals(comment2Id, comment.getString("id"));
        Assertions.assertEquals("Comment by comment2", comment.getString("content"));
        Assertions.assertEquals("comment2", comment.getString("creator"));
        Assertions.assertEquals("d6e56c42f61983bba80d370138763420", comment.getString("creator_gravatar"));
        Assertions.assertNotNull(comment.getJsonNumber("create_date"));
        
        // Delete a comment with comment2
        json = target().path("/comment/" + comment2Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, comment2Token)
                .delete(JsonObject.class);
        
        // Read comments with comment2
        json = target().path("/comment/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, comment2Token)
                .get(JsonObject.class);
        Assertions.assertEquals(0, json.getJsonArray("comments").size());
    }

    /**
     * A user with neither ownership nor an ACL on a document must be denied every
     * comment operation on it (read, add, delete) — the endpoint returns NOT_FOUND
     * rather than disclosing the document's existence. Each assertion fails if the
     * corresponding READ/WRITE ACL guard in CommentResource is removed.
     */
    @Test
    public void testCommentCrossUserDenied() {
        String adminToken = adminToken();

        // Owner and an unrelated user
        clientUtil.createUser("comment_owner");
        String ownerToken = clientUtil.login("comment_owner");
        clientUtil.createUser("comment_stranger");
        String strangerToken = clientUtil.login("comment_stranger");

        // Owner creates a document and a comment on it
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .put(Entity.form(new Form()
                        .param("title", "Owner comment doc")
                        .param("language", "eng")
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        String documentId = json.getString("id");
        json = target().path("/comment").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .put(Entity.form(new Form()
                        .param("id", documentId)
                        .param("content", "Owner's private comment")), JsonObject.class);
        String ownerCommentId = json.getString("id");

        // Stranger cannot read comments (no READ ACL)
        Response response = target().path("/comment/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, strangerToken)
                .get();
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()));

        // Stranger cannot add a comment (no READ ACL)
        response = target().path("/comment").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, strangerToken)
                .put(Entity.form(new Form()
                        .param("id", documentId)
                        .param("content", "Stranger comment")));
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()));

        // Stranger cannot delete the owner's comment (no WRITE ACL / not the author)
        response = target().path("/comment/" + ownerCommentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, strangerToken)
                .delete();
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()));

        // The owner's comment is intact (delete was actually denied, not silently applied)
        json = target().path("/comment/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonArray("comments").size());

        // Cleanup
        target().path("/user/comment_owner")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        target().path("/user/comment_stranger")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    /**
     * #285 slice 1 — the author edits their OWN comment. The new content replaces the old one, the
     * comment gains an edit timestamp that every reader of the document sees, and the CREATE date is
     * left exactly as it was (the audit trail keeps both dates; an edit is not a re-post).
     */
    @Test
    public void testCommentEditOwnComment() {
        String adminToken = adminToken();
        clientUtil.createUser("comment_author");
        String authorToken = clientUtil.login("comment_author");

        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, authorToken)
                .put(Entity.form(new Form()
                        .param("title", "Editable comment doc")
                        .param("language", "eng")
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        String documentId = json.getString("id");

        json = target().path("/comment").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, authorToken)
                .put(Entity.form(new Form()
                        .param("id", documentId)
                        .param("content", "Teh original comment")), JsonObject.class);
        String commentId = json.getString("id");
        long createDate = json.getJsonNumber("create_date").longValue();
        // A freshly posted comment carries no edit stamp at all.
        Assertions.assertFalse(json.containsKey("update_date"), "a new comment is not marked edited");

        // Edit it.
        json = target().path("/comment/" + commentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, authorToken)
                .post(Entity.form(new Form()
                        .param("content", "The original comment")), JsonObject.class);
        Assertions.assertEquals(commentId, json.getString("id"));
        Assertions.assertEquals("The original comment", json.getString("content"));
        Assertions.assertEquals("comment_author", json.getString("creator"));
        Assertions.assertEquals(createDate, json.getJsonNumber("create_date").longValue(),
                "editing must not move the creation date");
        long updateDate = json.getJsonNumber("update_date").longValue();
        Assertions.assertTrue(updateDate >= createDate, "the edit stamp cannot predate the creation date");

        // The list endpoint — what every reader of the document sees — carries the same three facts.
        json = target().path("/comment/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, authorToken)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonArray("comments").size());
        JsonObject comment = json.getJsonArray("comments").getJsonObject(0);
        Assertions.assertEquals("The original comment", comment.getString("content"));
        Assertions.assertEquals(createDate, comment.getJsonNumber("create_date").longValue());
        Assertions.assertEquals(updateDate, comment.getJsonNumber("update_date").longValue());

        // Cleanup
        target().path("/user/comment_author")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    /**
     * #285 slice 1 — ONLY the author may edit. A collaborator holding WRITE on the document (who may
     * therefore DELETE the comment) is still refused the edit, as is a user with no access at all, and
     * so is an edit of an already-deleted comment. Every refusal is NOT_FOUND — the convention the
     * delete endpoint already uses — and leaves the stored comment untouched.
     */
    @Test
    public void testCommentEditByNonAuthorDenied() {
        String adminToken = adminToken();

        clientUtil.createUser("comment_editowner");
        String ownerToken = clientUtil.login("comment_editowner");
        clientUtil.createUser("comment_editwriter");
        String writerToken = clientUtil.login("comment_editwriter");
        clientUtil.createUser("comment_editstranger");
        String strangerToken = clientUtil.login("comment_editstranger");

        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .put(Entity.form(new Form()
                        .param("title", "Foreign comment edit doc")
                        .param("language", "eng")
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        String documentId = json.getString("id");

        json = target().path("/comment").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .put(Entity.form(new Form()
                        .param("id", documentId)
                        .param("content", "The owner's words")), JsonObject.class);
        String commentId = json.getString("id");
        long createDate = json.getJsonNumber("create_date").longValue();

        // The writer really does hold READ+WRITE on the document (so the refusal below is about
        // authorship, not about access): ACLs are per-permission — WRITE does not imply READ — so grant
        // both, exactly as the permissions UI does, then prove the grant took by reading the comments.
        for (String perm : new String[]{"READ", "WRITE"}) {
            target().path("/acl").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                    .put(Entity.form(new Form()
                            .param("source", documentId)
                            .param("perm", perm)
                            .param("target", "comment_editwriter")
                            .param("type", "USER")), JsonObject.class);
        }
        json = target().path("/comment/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, writerToken)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonArray("comments").size(),
                "the writer must be able to READ the comment, so the edit refusal is about authorship");

        // A WRITE-holder who is not the author is refused.
        Response response = target().path("/comment/" + commentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, writerToken)
                .post(Entity.form(new Form().param("content", "Rewritten by the writer")));
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()));

        // A user with no access at all is refused.
        response = target().path("/comment/" + commentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, strangerToken)
                .post(Entity.form(new Form().param("content", "Rewritten by a stranger")));
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()));

        // Nothing changed: same content, same create date, and no edit stamp was written.
        json = target().path("/comment/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .get(JsonObject.class);
        JsonObject comment = json.getJsonArray("comments").getJsonObject(0);
        Assertions.assertEquals("The owner's words", comment.getString("content"));
        Assertions.assertEquals(createDate, comment.getJsonNumber("create_date").longValue());
        Assertions.assertFalse(comment.containsKey("update_date"),
                "a refused edit must not stamp the comment as edited");

        // A deleted comment cannot be edited back into existence, not even by its author.
        target().path("/comment/" + commentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .delete(JsonObject.class);
        response = target().path("/comment/" + commentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, ownerToken)
                .post(Entity.form(new Form().param("content", "Back from the dead")));
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()));

        // Cleanup
        for (String username : new String[]{"comment_editowner", "comment_editwriter", "comment_editstranger"}) {
            target().path("/user/" + username)
                    .queryParam("reassign_to_username", "admin").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                    .delete();
        }
    }
}