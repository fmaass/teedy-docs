package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;

/**
 * Test tag co-occurrence matrix endpoint.
 */
public class TestTagCoOccurrenceResource extends BaseJerseyTest {
    @Test
    public void testTagCoOccurrence() {
        String adminToken = adminToken();

        // Create tags: tagA, tagB, tagC
        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "CoTagA")
                        .param("color", "#ff0000")), JsonObject.class);
        String tagAId = json.getString("id");

        json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "CoTagB")
                        .param("color", "#00ff00")), JsonObject.class);
        String tagBId = json.getString("id");

        json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "CoTagC")
                        .param("color", "#0000ff")), JsonObject.class);
        String tagCId = json.getString("id");

        // Create doc1 with tagA + tagB
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "CoOcc doc 1")
                        .param("language", "eng")
                        .param("tags", tagAId)
                        .param("tags", tagBId)
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        String doc1Id = json.getString("id");

        // Create doc2 with tagA + tagC
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "CoOcc doc 2")
                        .param("language", "eng")
                        .param("tags", tagAId)
                        .param("tags", tagCId)
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        String doc2Id = json.getString("id");

        // Create doc3 with tagB only
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "CoOcc doc 3")
                        .param("language", "eng")
                        .param("tags", tagBId)
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        String doc3Id = json.getString("id");

        // GET /tag/co-occurrence
        json = target().path("/tag/co-occurrence").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonArray pairs = json.getJsonArray("pairs");
        Assertions.assertNotNull(pairs);

        // Verify A-B pair exists with count=1
        boolean foundAB = false;
        boolean foundAC = false;
        boolean foundBC = false;
        for (int i = 0; i < pairs.size(); i++) {
            JsonObject pair = pairs.getJsonObject(i);
            String tagA = pair.getString("tagA");
            String tagB = pair.getString("tagB");
            int count = pair.getInt("count");

            // Pairs are ordered A < B lexicographically
            if ((tagA.equals(tagAId) && tagB.equals(tagBId)) || (tagA.equals(tagBId) && tagB.equals(tagAId))) {
                foundAB = true;
                Assertions.assertEquals(1, count, "A-B should co-occur on 1 document");
            }
            if ((tagA.equals(tagAId) && tagB.equals(tagCId)) || (tagA.equals(tagCId) && tagB.equals(tagAId))) {
                foundAC = true;
                Assertions.assertEquals(1, count, "A-C should co-occur on 1 document");
            }
            if ((tagA.equals(tagBId) && tagB.equals(tagCId)) || (tagA.equals(tagCId) && tagB.equals(tagBId))) {
                foundBC = true;
            }
        }

        Assertions.assertTrue(foundAB, "A-B pair should exist");
        Assertions.assertTrue(foundAC, "A-C pair should exist");
        Assertions.assertFalse(foundBC, "B-C pair should NOT exist (no doc has both)");

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
