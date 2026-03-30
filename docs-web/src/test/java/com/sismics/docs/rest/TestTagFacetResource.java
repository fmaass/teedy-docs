package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;

/**
 * Test tag facets and stats endpoints.
 */
public class TestTagFacetResource extends BaseJerseyTest {
    @Test
    public void testTagFacets() {
        String adminToken = adminToken();

        // Create tags: tagA, tagB, tagC
        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "FacetTagA")
                        .param("color", "#ff0000")), JsonObject.class);
        String tagAId = json.getString("id");

        json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "FacetTagB")
                        .param("color", "#00ff00")), JsonObject.class);
        String tagBId = json.getString("id");

        json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "FacetTagC")
                        .param("color", "#0000ff")), JsonObject.class);
        String tagCId = json.getString("id");

        // Create doc1 with tagA + tagB
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Facet doc 1")
                        .param("language", "eng")
                        .param("tags", tagAId)
                        .param("tags", tagBId)
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        String doc1Id = json.getString("id");

        // Create doc2 with tagA + tagC
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Facet doc 2")
                        .param("language", "eng")
                        .param("tags", tagAId)
                        .param("tags", tagCId)
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        String doc2Id = json.getString("id");

        // Create doc3 with tagB only
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Facet doc 3")
                        .param("language", "eng")
                        .param("tags", tagBId)
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        String doc3Id = json.getString("id");

        // GET /tag/stats -- all tags with counts
        json = target().path("/tag/stats").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonObject stats = json.getJsonObject("stats");
        Assertions.assertEquals(2, stats.getInt(tagAId));
        Assertions.assertEquals(2, stats.getInt(tagBId));
        Assertions.assertEquals(1, stats.getInt(tagCId));

        // GET /tag/facets without selection -- same as stats
        json = target().path("/tag/facets").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonObject facets = json.getJsonObject("facets");
        Assertions.assertEquals(2, facets.getInt(tagAId));
        Assertions.assertEquals(2, facets.getInt(tagBId));
        Assertions.assertEquals(1, facets.getInt(tagCId));

        // GET /tag/facets with tagA selected -- should show tagB:1, tagC:1 (co-occurring)
        json = target().path("/tag/facets")
                .queryParam("tags", tagAId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        facets = json.getJsonObject("facets");
        Assertions.assertEquals(2, json.getInt("total"));
        Assertions.assertEquals(1, facets.getInt(tagBId));
        Assertions.assertEquals(1, facets.getInt(tagCId));
        Assertions.assertFalse(facets.containsKey(tagAId));

        // GET /tag/facets with tagA + tagB selected -- should show no co-occurring tags (only doc1 has both)
        json = target().path("/tag/facets")
                .queryParam("tags", tagAId + "," + tagBId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        facets = json.getJsonObject("facets");
        Assertions.assertEquals(1, json.getInt("total"));
        Assertions.assertFalse(facets.containsKey(tagAId));
        Assertions.assertFalse(facets.containsKey(tagBId));

        // GET /tag/facets with tagA selected, OR mode -- docs matching ANY of tagA = doc1, doc2
        json = target().path("/tag/facets")
                .queryParam("tags", tagAId)
                .queryParam("mode", "or")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        facets = json.getJsonObject("facets");
        Assertions.assertEquals(2, json.getInt("total"));
        Assertions.assertEquals(1, facets.getInt(tagBId));
        Assertions.assertEquals(1, facets.getInt(tagCId));

        // GET /tag/facets with tagA + tagB selected, OR mode -- docs matching ANY = doc1, doc2, doc3 (3 total)
        json = target().path("/tag/facets")
                .queryParam("tags", tagAId + "," + tagBId)
                .queryParam("mode", "or")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        facets = json.getJsonObject("facets");
        Assertions.assertEquals(3, json.getInt("total"));
        Assertions.assertEquals(1, facets.getInt(tagCId));
        Assertions.assertFalse(facets.containsKey(tagAId));
        Assertions.assertFalse(facets.containsKey(tagBId));

        // GET /document/list with tagMode=or -- doc1(A,B) + doc2(A,C) + doc3(B) all match A or B
        json = target().path("/document/list")
                .queryParam("search", "tag:FacetTagA tag:FacetTagB")
                .queryParam("search[tagMode]", "or")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(3, json.getInt("total"));

        // GET /document/list with tagMode=and (default) -- only doc1 has both A and B
        json = target().path("/document/list")
                .queryParam("search", "tag:FacetTagA tag:FacetTagB")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getInt("total"));

        // Cleanup
        target().path("/document/" + doc1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).delete();
        target().path("/document/" + doc2Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).delete();
        target().path("/document/" + doc3Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).delete();
        target().path("/tag/" + tagAId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).delete();
        target().path("/tag/" + tagBId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).delete();
        target().path("/tag/" + tagCId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).delete();
    }
}
