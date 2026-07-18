package com.sismics.docs.rest;

import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import com.sismics.util.jpa.EMF;
import jakarta.json.JsonObject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

/**
 * REST-level tests for the #122 tag-WRITE lifecycle on user deletion (both delete paths):
 *
 * <ul>
 *   <li>SELF-delete (DELETE /user, no reassignment target) is REFUSED with a typed 400 when the account is
 *       the sole WRITE holder of a tag a surviving document (owned by another user) still uses — deleting
 *       would orphan that tag and strip it from the other owner's document. An ordinary account (no such
 *       tag) still deletes normally.</li>
 *   <li>ADMIN reassign-delete moves such a foreign-linked tag to the target and grants it WRITE, so the tag
 *       is never stranded at zero WRITE holders — even when the departing user owns no documents of their
 *       own (the case the pre-#122 early-return skipped entirely).</li>
 * </ul>
 */
public class TestUserDeleteTagWrite extends BaseJerseyTest {

    /** SELF-delete is refused when the account solely edits a tag used on another user's document. */
    @Test
    public void selfDeleteRefusedWhenSoleWriteHolderOfForeignLinkedTag() {
        clientUtil.createUser("twdel_refuse_departing");
        clientUtil.createUser("twdel_refuse_other");
        String departingToken = clientUtil.login("twdel_refuse_departing");
        String otherToken = clientUtil.login("twdel_refuse_other");

        // Departing creates a tag (creator gets READ+WRITE, so it is the sole WRITE holder).
        String tagId = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, departingToken)
                .put(Entity.form(new Form().param("name", "TwdelRefuse").param("color", "#ff0000")), JsonObject.class)
                .getString("id");
        // Another user owns a document; the departing user's tag is applied to it (state seeded directly).
        String foreignDoc = clientUtil.createDocument(otherToken);
        linkTag(foreignDoc, tagId);

        // Departing tries to delete their own account -> refused with the typed client error.
        Response response = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, departingToken)
                .delete();
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        JsonObject json = response.readEntity(JsonObject.class);
        Assertions.assertEquals("SoleTagWriteHolderError", json.getString("type"),
                "self-delete must be refused with the sole-tag-write-holder error");

        // The account is untouched, and so is the other owner's tag link.
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_USER where USE_USERNAME_C = :v and USE_DELETEDATE_D is null",
                "twdel_refuse_departing"),
                "a refused self-delete must leave the account active");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_DOCUMENT_TAG where DOT_IDTAG_C = :v and DOT_DELETEDATE_D is null", tagId),
                "the other owner's document keeps its tag link");
    }

    /** An ordinary account (no sole-write foreign-linked tag) still self-deletes normally. */
    @Test
    public void selfDeleteAllowedForOrdinaryAccount() {
        clientUtil.createUser("twdel_ok_user");
        String token = clientUtil.login("twdel_ok_user");

        Response response = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .delete();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()),
                "an ordinary account must still be able to self-delete");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_USER where USE_USERNAME_C = :v and USE_DELETEDATE_D is not null",
                "twdel_ok_user"),
                "the ordinary account is soft-deleted");
    }

    /**
     * ADMIN reassign-delete preserves a tag the departing user solely owns but applied to ANOTHER user's
     * document, even though the departing user owns no documents of their own.
     */
    @Test
    public void adminReassignPreservesForeignLinkedTag() {
        clientUtil.createUser("twdel_re_departing");
        clientUtil.createUser("twdel_re_target");
        clientUtil.createUser("twdel_re_other");
        String departingToken = clientUtil.login("twdel_re_departing");
        String otherToken = clientUtil.login("twdel_re_other");
        String targetId = userId("twdel_re_target");

        String tagId = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, departingToken)
                .put(Entity.form(new Form().param("name", "TwdelReassign").param("color", "#00ff00")), JsonObject.class)
                .getString("id");
        String foreignDoc = clientUtil.createDocument(otherToken);
        linkTag(foreignDoc, tagId);

        // Admin reassign-deletes the departing user (who owns NO documents of their own).
        Response response = target().path("/user/twdel_re_departing")
                .queryParam("reassign_to_username", "twdel_re_target")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken())
                .delete();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()),
                "the admin reassign-delete must succeed");

        // The tag survives, now owned by the target, with a live WRITE holder — never stranded.
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_TAG where TAG_ID_C = :v and TAG_DELETEDATE_D is null", tagId),
                "the foreign-linked tag must survive");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_TAG where TAG_ID_C = '" + tagId + "' and TAG_IDUSER_C = :v"
                        + " and TAG_DELETEDATE_D is null", targetId),
                "the tag ownership must move to the reassignment target");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_ACL where ACL_SOURCEID_C = '" + tagId + "' and ACL_TARGETID_C = :v"
                        + " and ACL_PERM_C = 'WRITE' and ACL_TYPE_C = 'USER' and ACL_DELETEDATE_D is null", targetId),
                "the target must hold a WRITE grant on the reassigned tag (no zero-WRITE strand)");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_DOCUMENT_TAG where DOT_IDTAG_C = :v and DOT_DELETEDATE_D is null", tagId),
                "the other owner's document keeps its tag link");
    }

    // ---- helpers ----

    /** Links a tag to a document by inserting a T_DOCUMENT_TAG row directly (committed). */
    private void linkTag(String documentId, String tagId) {
        executeSql("insert into T_DOCUMENT_TAG (DOT_ID_C, DOT_IDDOCUMENT_C, DOT_IDTAG_C) values (:id, :doc, :tag)",
                Map.of("id", UUID.randomUUID().toString(), "doc", documentId, "tag", tagId));
    }

    private String userId(String username) {
        EntityManager prev = ThreadLocalContext.get().getEntityManager();
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            ThreadLocalContext.get().setEntityManager(em);
            tx.begin();
            Object o = em.createNativeQuery("select USE_ID_C from T_USER where USE_USERNAME_C = :name and USE_DELETEDATE_D is null")
                    .setParameter("name", username).getSingleResult();
            tx.commit();
            return (String) o;
        } finally {
            if (tx.isActive()) {
                tx.rollback();
            }
            em.close();
            ThreadLocalContext.get().setEntityManager(prev);
        }
    }

    private long readCount(String sql, String paramValue) {
        EntityManager prev = ThreadLocalContext.get().getEntityManager();
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            ThreadLocalContext.get().setEntityManager(em);
            tx.begin();
            Object n = em.createNativeQuery(sql).setParameter("v", paramValue).getSingleResult();
            tx.commit();
            return ((Number) n).longValue();
        } finally {
            if (tx.isActive()) {
                tx.rollback();
            }
            em.close();
            ThreadLocalContext.get().setEntityManager(prev);
        }
    }

    private void executeSql(String sql, Map<String, Object> params) {
        EntityManager prev = ThreadLocalContext.get().getEntityManager();
        EntityManager em = EMF.get().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            ThreadLocalContext.get().setEntityManager(em);
            tx.begin();
            jakarta.persistence.Query q = em.createNativeQuery(sql);
            for (Map.Entry<String, Object> e : params.entrySet()) {
                q.setParameter(e.getKey(), e.getValue());
            }
            q.executeUpdate();
            tx.commit();
        } finally {
            if (tx.isActive()) {
                tx.rollback();
            }
            em.close();
            ThreadLocalContext.get().setEntityManager(prev);
        }
    }
}
