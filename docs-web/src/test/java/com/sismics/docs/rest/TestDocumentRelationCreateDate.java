package com.sismics.docs.rest;

import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Every entry of a document's relation list carries the LINKED document's own creation date, so the
 * reader can order "Related documents" by when the other document was created (#296). The date is the
 * OTHER side's in BOTH directions — an outgoing relation reports the target's date, an incoming one the
 * source's — which is the case a mis-wired column silently gets wrong: the queried document's own date
 * would satisfy a single-direction assertion while being the same value for every row.
 */
public class TestDocumentRelationCreateDate extends BaseJerseyTest {

    /** Seeded creation dates, distinct per document so a wrong-side column cannot pass. */
    private static final long CREATE_DATE_A = 1_500_000_000_000L;
    private static final long CREATE_DATE_B = 1_600_000_000_000L;

    @Test
    public void relationCarriesTheLinkedDocumentsCreateDateInBothDirections() {
        clientUtil.createUser("rel_date_owner");
        String owner = clientUtil.login("rel_date_owner");

        // A is created first; B is created linking TO A, so B owns the relation (B -> A).
        String docA = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, owner)
                .put(Entity.form(new Form().param("title", "rel date A").param("language", "eng")
                        .param("create_date", Long.toString(CREATE_DATE_A))), JsonObject.class)
                .getString("id");
        String docB = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, owner)
                .put(Entity.form(new Form().param("title", "rel date B").param("language", "eng")
                        .param("create_date", Long.toString(CREATE_DATE_B))
                        .param("relations", docA)), JsonObject.class)
                .getString("id");

        // B's view: the relation is OUTGOING (source=true) and must report A's creation date.
        JsonObject fromB = relation(docB, owner);
        Assertions.assertEquals(docA, fromB.getString("id"));
        Assertions.assertTrue(fromB.getBoolean("source"), "B owns the relation, so it is outgoing there");
        Assertions.assertNotNull(fromB.get("create_date"),
                "the relation must carry the linked document's creation date");
        Assertions.assertEquals(CREATE_DATE_A, fromB.getJsonNumber("create_date").longValue(),
                "an outgoing relation must carry the TARGET's creation date, not the queried document's");

        // A's view: the same link is INCOMING (source=false) and must report B's creation date.
        JsonObject fromA = relation(docA, owner);
        Assertions.assertEquals(docB, fromA.getString("id"));
        Assertions.assertFalse(fromA.getBoolean("source"), "A does not own the relation, so it is incoming there");
        Assertions.assertNotNull(fromA.get("create_date"),
                "the relation must carry the linked document's creation date");
        Assertions.assertEquals(CREATE_DATE_B, fromA.getJsonNumber("create_date").longValue(),
                "an incoming relation must carry the SOURCE's creation date, not the queried document's");
    }

    /**
     * A document row is allowed to carry NO creation date: {@code DOC_CREATEDATE_D} is declared
     * nullable in the schema (dbupdate-000-0.sql) and no later migration tightened it, while the
     * entity's {@code nullable = false} is never enforced against an existing database (hbm2ddl is
     * off, EMF). A relation to such a document must come back as an explicit JSON {@code null} —
     * the same shape every other nullable field on this response uses — and must not take the whole
     * document request down with it.
     */
    @Test
    public void relationToADocumentWithNoCreateDateRendersNull() {
        clientUtil.createUser("rel_nodate_owner");
        String owner = clientUtil.login("rel_nodate_owner");

        String docA = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, owner)
                .put(Entity.form(new Form().param("title", "no date A").param("language", "eng")
                        .param("create_date", Long.toString(CREATE_DATE_A))), JsonObject.class)
                .getString("id");
        String docB = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, owner)
                .put(Entity.form(new Form().param("title", "no date B").param("language", "eng")
                        .param("create_date", Long.toString(CREATE_DATE_B))
                        .param("relations", docA)), JsonObject.class)
                .getString("id");

        // The legacy state the schema still permits, written the only way it is reachable.
        TransactionUtil.handle(() -> ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_DOCUMENT set DOC_CREATEDATE_D = null where DOC_ID_C = :id")
                .setParameter("id", docA)
                .executeUpdate());

        JsonObject fromB = relation(docB, owner);
        Assertions.assertEquals(docA, fromB.getString("id"));
        Assertions.assertTrue(fromB.containsKey("create_date"),
                "the field stays on the wire — a null date is rendered, not omitted");
        Assertions.assertTrue(fromB.isNull("create_date"),
                "a linked document with no creation date must render as JSON null, not fail the request");
    }

    /**
     * The single relation of a document, read back through GET /document/{id}.
     */
    private JsonObject relation(String documentId, String token) {
        JsonArray relations = target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class)
                .getJsonArray("relations");
        Assertions.assertEquals(1, relations.size());
        return relations.getJsonObject(0);
    }
}
