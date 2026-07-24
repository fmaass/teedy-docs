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
 * REST-level tests for #180: the admin delete's {@code reassign_to_username} is required ONLY when the
 * departing user still owns content that has to move — an active document, or an active tag (which can
 * sit on ANOTHER user's document and would be purged as orphaned if its owner vanished).
 *
 * <ul>
 *   <li>An account that owns nothing deletes with no target at all.</li>
 *   <li>An account that owns a document, an unlinked tag, or a tag applied to a foreign document is
 *       refused with the typed {@code ReassignRequired} error and is left completely untouched.</li>
 *   <li>Supplying a target stays valid in every case, including for an account that owns nothing —
 *       the parameter's meaning did not change for existing clients.</li>
 * </ul>
 */
public class TestUserDeleteWithoutReassign extends BaseJerseyTest {

    /** An account owning no documents and no tags is deleted without a reassignment target. */
    @Test
    public void deletesWithoutTargetWhenNothingIsOwned() {
        clientUtil.createUser("nore_empty");

        Response response = target().path("/user/nore_empty").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken())
                .delete();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()),
                "an account with nothing to reassign must delete without a target");
        Assertions.assertEquals("ok", response.readEntity(JsonObject.class).getString("status"));

        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_USER where USE_USERNAME_C = :v and USE_DELETEDATE_D is not null",
                "nore_empty"),
                "the account must be soft-deleted");
    }

    /** Supplying a target for an account that owns nothing stays valid (no client contract change). */
    @Test
    public void stillAcceptsATargetWhenNothingIsOwned() {
        clientUtil.createUser("nore_empty_t");
        clientUtil.createUser("nore_empty_target");

        Response response = target().path("/user/nore_empty_t")
                .queryParam("reassign_to_username", "nore_empty_target")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken())
                .delete();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()),
                "a target must remain accepted even when there is nothing to reassign");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_USER where USE_USERNAME_C = :v and USE_DELETEDATE_D is not null",
                "nore_empty_t"),
                "the account must be soft-deleted");
    }

    /**
     * An explicitly EMPTY {@code reassign_to_username} is a supplied-but-invalid target, NOT an
     * omission: it must still fail ValidationError (the pre-#180 behaviour for that input) rather than
     * fall through to the target-less path — otherwise a client sending an empty value would silently
     * change semantics.
     */
    @Test
    public void refusesAnExplicitlyEmptyTarget() {
        clientUtil.createUser("nore_empty_param");

        Response response = target().path("/user/nore_empty_param")
                .queryParam("reassign_to_username", "")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken())
                .delete();
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("ValidationError", response.readEntity(JsonObject.class).getString("type"),
                "an empty reassign_to_username must be rejected as an invalid target, not treated as absent");

        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_USER where USE_USERNAME_C = :v and USE_DELETEDATE_D is null",
                "nore_empty_param"),
                "a rejected delete must leave the account active");
    }

    /** An account owning an active document is refused without a target, and nothing is deleted. */
    @Test
    public void refusesWithoutTargetWhenUserOwnsADocument() {
        clientUtil.createUser("nore_doc");
        String token = clientUtil.login("nore_doc");
        String documentId = clientUtil.createDocument(token);

        Response response = target().path("/user/nore_doc").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken())
                .delete();
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("ReassignRequired", response.readEntity(JsonObject.class).getString("type"),
                "a document owner must be refused with the typed reassign-required error");

        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_USER where USE_USERNAME_C = :v and USE_DELETEDATE_D is null", "nore_doc"),
                "a refused delete must leave the account active");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_DOCUMENT where DOC_ID_C = :v and DOC_DELETEDATE_D is null", documentId),
                "a refused delete must leave the document untouched");
    }

    /**
     * An account owning an active tag — even one linked to nothing — is refused without a target: the
     * tag would be left with a soft-deleted owner and purged as orphaned.
     */
    @Test
    public void refusesWithoutTargetWhenUserOwnsOnlyATag() {
        clientUtil.createUser("nore_tag");
        String token = clientUtil.login("nore_tag");
        String tagId = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form().param("name", "NoreUnlinked").param("color", "#ff0000")), JsonObject.class)
                .getString("id");

        Response response = target().path("/user/nore_tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken())
                .delete();
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("ReassignRequired", response.readEntity(JsonObject.class).getString("type"),
                "a tag owner must be refused with the typed reassign-required error");

        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_USER where USE_USERNAME_C = :v and USE_DELETEDATE_D is null", "nore_tag"),
                "a refused delete must leave the account active");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_TAG where TAG_ID_C = :v and TAG_DELETEDATE_D is null", tagId),
                "a refused delete must leave the tag untouched");
    }

    /**
     * The #122 tag-loss guard, restated for #180: a user owning NO documents but whose tag is applied to
     * another user's document is refused without a target, and once a target is supplied the tag moves
     * to it instead of being stranded — the outcome the target-less path must never be allowed to reach.
     */
    @Test
    public void refusesWithoutTargetThenPreservesForeignLinkedTagWithTarget() {
        clientUtil.createUser("nore_link_departing");
        clientUtil.createUser("nore_link_target");
        clientUtil.createUser("nore_link_other");
        String departingToken = clientUtil.login("nore_link_departing");
        String otherToken = clientUtil.login("nore_link_other");
        String targetId = userId("nore_link_target");

        String tagId = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, departingToken)
                .put(Entity.form(new Form().param("name", "NoreForeignLinked").param("color", "#00ff00")), JsonObject.class)
                .getString("id");
        linkTag(clientUtil.createDocument(otherToken), tagId);

        // The departing user owns no documents, but dropping the reassignment here would orphan the tag.
        Response refused = target().path("/user/nore_link_departing").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken())
                .delete();
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(refused.getStatus()));
        Assertions.assertEquals("ReassignRequired", refused.readEntity(JsonObject.class).getString("type"),
                "owning a foreign-linked tag must require a reassignment target even with zero documents");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_USER where USE_USERNAME_C = :v and USE_DELETEDATE_D is null",
                "nore_link_departing"),
                "a refused delete must leave the account active");

        // With a target the delete proceeds and the tag survives under the new owner.
        Response accepted = target().path("/user/nore_link_departing")
                .queryParam("reassign_to_username", "nore_link_target")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken())
                .delete();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(accepted.getStatus()));

        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_TAG where TAG_ID_C = '" + tagId + "' and TAG_IDUSER_C = :v"
                        + " and TAG_DELETEDATE_D is null", targetId),
                "the tag must survive under the reassignment target");
        Assertions.assertEquals(1L, readCount(
                "select count(*) from T_DOCUMENT_TAG where DOT_IDTAG_C = :v and DOT_DELETEDATE_D is null", tagId),
                "the other owner's document keeps its tag link");
    }

    /** The admin user list exposes the per-user flag the delete gate enforces. */
    @Test
    public void userListExposesRequiresReassignFlag() {
        clientUtil.createUser("nore_flag_empty");
        clientUtil.createUser("nore_flag_owner");
        String ownerToken = clientUtil.login("nore_flag_owner");
        clientUtil.createDocument(ownerToken);

        JsonObject json = target().path("/user/list").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken())
                .get(JsonObject.class);

        Assertions.assertFalse(requiresReassign(json, "nore_flag_empty"),
                "a user owning nothing must be flagged as needing no reassignment target");
        Assertions.assertTrue(requiresReassign(json, "nore_flag_owner"),
                "a document owner must be flagged as needing a reassignment target");
    }

    // ---- helpers ----

    /** Reads the requires_reassign flag of one user out of a /user/list response. */
    private boolean requiresReassign(JsonObject listResponse, String username) {
        return listResponse.getJsonArray("users").getValuesAs(JsonObject.class).stream()
                .filter(user -> username.equals(user.getString("username")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("user not present in the list: " + username))
                .getBoolean("requires_reassign");
    }

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
