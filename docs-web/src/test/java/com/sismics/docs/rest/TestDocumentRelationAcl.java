package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * A document's related-document list must be filtered by the caller's READ permission: a related document
 * the caller cannot read must not leak its title through the relations of a document they can read (#140).
 * Verified via the anonymous share path — a share grants anonymous READ to ONE document only, so a relation
 * to a second, unshared document must be hidden from the anonymous viewer while the owner still sees it.
 */
public class TestDocumentRelationAcl extends BaseJerseyTest {

    @Test
    public void relationsHideDocumentsTheCallerCannotRead() {
        clientUtil.createUser("rel_owner");
        String owner = clientUtil.login("rel_owner");

        // Document A (will be shared) and document B (secret), related to each other.
        JsonObject a = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, owner)
                .put(Entity.form(new Form().param("title", "doc A").param("language", "eng")), JsonObject.class);
        String docA = a.getString("id");
        JsonObject b = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, owner)
                .put(Entity.form(new Form().param("title", "SECRET doc B").param("language", "eng")
                        .param("relations", docA)), JsonObject.class);
        String docB = b.getString("id");

        // Share ONLY document A (anonymous READ on A, not B).
        JsonObject share = target().path("/share").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, owner)
                .put(Entity.form(new Form().param("id", docA).param("name", "public link")), JsonObject.class);
        String shareId = share.getString("id");

        // Anonymous, via the share: sees A, but the relation to the unreadable B must be hidden.
        JsonObject anon = target().path("/document/" + docA).queryParam("share", shareId)
                .request().get(JsonObject.class);
        JsonArray anonRelations = anon.getJsonArray("relations");
        Assertions.assertEquals(0, anonRelations.size(),
                "a related document the caller cannot read must not leak its title through relations");

        // The owner, who can read both, still sees the relation to B.
        JsonObject ownerView = target().path("/document/" + docA).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, owner).get(JsonObject.class);
        JsonArray ownerRelations = ownerView.getJsonArray("relations");
        Assertions.assertEquals(1, ownerRelations.size());
        Assertions.assertEquals(docB, ownerRelations.getJsonObject(0).getString("id"));
    }
}
