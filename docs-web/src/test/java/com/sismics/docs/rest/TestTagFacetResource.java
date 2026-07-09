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

    /**
     * P6: facet counts must honour active tag EXCLUSIONS. Selecting tagA while
     * excluding tagB removes documents carrying tagB from every facet/total.
     * A no-exclude request stays byte-identical to today.
     */
    @Test
    public void testTagFacetsExclusion() {
        String adminToken = adminToken();

        String tagAId = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("name", "ExclTagA").param("color", "#ff0000")), JsonObject.class)
                .getString("id");
        String tagBId = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("name", "ExclTagB").param("color", "#00ff00")), JsonObject.class)
                .getString("id");
        String tagCId = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("name", "ExclTagC").param("color", "#0000ff")), JsonObject.class)
                .getString("id");

        // doc1: A + B, doc2: A + C, doc3: A + B + C
        String doc1Id = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Excl doc 1").param("language", "eng")
                        .param("tags", tagAId).param("tags", tagBId)
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class)
                .getString("id");
        String doc2Id = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Excl doc 2").param("language", "eng")
                        .param("tags", tagAId).param("tags", tagCId)
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class)
                .getString("id");
        String doc3Id = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Excl doc 3").param("language", "eng")
                        .param("tags", tagAId).param("tags", tagBId).param("tags", tagCId)
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class)
                .getString("id");

        // Baseline: tagA selected, no exclusion. AND path.
        // Docs with A = doc1, doc2, doc3 => total 3. Co-occurring: B on doc1+doc3 (2), C on doc2+doc3 (2).
        JsonObject json = target().path("/tag/facets")
                .queryParam("tags", tagAId)
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(3, json.getInt("total"));
        Assertions.assertEquals(2, json.getJsonObject("facets").getInt(tagBId));
        Assertions.assertEquals(2, json.getJsonObject("facets").getInt(tagCId));

        // Exclude tagB: drops doc1 and doc3 (both carry B). Only doc2 (A,C) remains.
        // total 1, C:1, B must be absent.
        json = target().path("/tag/facets")
                .queryParam("tags", tagAId)
                .queryParam("exclude", tagBId)
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getInt("total"));
        JsonObject exclFacets = json.getJsonObject("facets");
        Assertions.assertEquals(1, exclFacets.getInt(tagCId));
        Assertions.assertFalse(exclFacets.containsKey(tagBId));

        // Exclude tagB in OR mode too: A OR (nothing else) minus B-docs => doc2 only.
        json = target().path("/tag/facets")
                .queryParam("tags", tagAId)
                .queryParam("mode", "or")
                .queryParam("exclude", tagBId)
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getInt("total"));
        Assertions.assertEquals(1, json.getJsonObject("facets").getInt(tagCId));
        Assertions.assertFalse(json.getJsonObject("facets").containsKey(tagBId));

        // No-selection facets with exclude: stats-equivalent minus excluded docs.
        // Exclude B removes doc1+doc3. Remaining doc2 has A,C. So A:1, C:1, B absent.
        json = target().path("/tag/facets")
                .queryParam("exclude", tagBId)
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonObject noSelFacets = json.getJsonObject("facets");
        Assertions.assertEquals(1, noSelFacets.getInt(tagAId));
        Assertions.assertEquals(1, noSelFacets.getInt(tagCId));
        Assertions.assertFalse(noSelFacets.containsKey(tagBId));

        // Backward compat: empty exclude param behaves like no exclude.
        json = target().path("/tag/facets")
                .queryParam("tags", tagAId)
                .queryParam("exclude", "")
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(3, json.getInt("total"));
        Assertions.assertEquals(2, json.getJsonObject("facets").getInt(tagBId));

        // Cleanup
        for (String d : new String[]{doc1Id, doc2Id, doc3Id}) {
            target().path("/document/" + d).request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).delete();
        }
        for (String t : new String[]{tagAId, tagBId, tagCId}) {
            target().path("/tag/" + t).request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken).delete();
        }
    }

    /**
     * P6 + SEC-03: exclusion must never let a user's counts include documents they
     * cannot read. B excludes a tag on a document that only A can see — the
     * exclusion cannot subtract (or leak) counts for documents outside B's ACL.
     */
    @Test
    public void testTagFacetsExclusionAclScoped() {
        // User A: private tag + doc that B can never see.
        clientUtil.createUser("exclacl1");
        String t1 = clientUtil.login("exclacl1");
        String tagA1 = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form().param("name", "ExclAclA1").param("color", "#ff0000")), JsonObject.class)
                .getString("id");
        target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form()
                        .param("title", "A private excl doc").param("language", "eng")
                        .param("tags", tagA1)
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);

        // User B: two own tags on two own docs. doc B1 has tagB1+tagB2, doc B2 has tagB1 only.
        clientUtil.createUser("exclacl2");
        String t2 = clientUtil.login("exclacl2");
        String tagB1 = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .put(Entity.form(new Form().param("name", "ExclAclB1").param("color", "#00ff00")), JsonObject.class)
                .getString("id");
        String tagB2 = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .put(Entity.form(new Form().param("name", "ExclAclB2").param("color", "#00ff01")), JsonObject.class)
                .getString("id");
        target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .put(Entity.form(new Form()
                        .param("title", "B doc 1").param("language", "eng")
                        .param("tags", tagB1).param("tags", tagB2)
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .put(Entity.form(new Form()
                        .param("title", "B doc 2").param("language", "eng")
                        .param("tags", tagB1)
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);

        // B selects its own tagB1 (on both B docs): total 2, tagB2 co-occurs on 1.
        JsonObject json = target().path("/tag/facets")
                .queryParam("tags", tagB1)
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class);
        Assertions.assertEquals(2, json.getInt("total"));
        Assertions.assertEquals(1, json.getJsonObject("facets").getInt(tagB2));

        // B excludes tagB2: drops B doc1, leaves B doc2 => total 1, tagB2 absent.
        json = target().path("/tag/facets")
                .queryParam("tags", tagB1)
                .queryParam("exclude", tagB2)
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getInt("total"));
        Assertions.assertFalse(json.getJsonObject("facets").containsKey(tagB2));

        // B excludes A's INVISIBLE tag while selecting tagB1: A's doc is outside B's
        // ACL, so it never contributed; the exclusion changes nothing and leaks no
        // A tag. total must stay 2 (B's own docs), never a leaked count.
        json = target().path("/tag/facets")
                .queryParam("tags", tagB1)
                .queryParam("exclude", tagA1)
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class);
        Assertions.assertEquals(2, json.getInt("total"));
        JsonObject scopedFacets = json.getJsonObject("facets");
        Assertions.assertEquals(1, scopedFacets.getInt(tagB2));
        Assertions.assertFalse(scopedFacets.containsKey(tagA1));

        // No-selection facets, B excludes A's invisible tag: B's own counts only.
        json = target().path("/tag/facets")
                .queryParam("exclude", tagA1)
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class);
        JsonObject noSel = json.getJsonObject("facets");
        Assertions.assertEquals(2, noSel.getInt(tagB1));
        Assertions.assertEquals(1, noSel.getInt(tagB2));
        Assertions.assertFalse(noSel.containsKey(tagA1));
    }

    /**
     * P6 security delta: excluding a tag the caller CANNOT READ must be a no-op,
     * even when that invisible tag sits on a document the caller CAN see (via a
     * shared tag). Otherwise the count delta discloses that the private tag is
     * attached to documents in the caller's result set.
     */
    @Test
    public void testTagFacetsExclusionInvisibleTagNoOp() {
        // User A: one doc carrying a shared tag + a PRIVATE tag.
        clientUtil.createUser("exclleak1");
        String t1 = clientUtil.login("exclleak1");
        String tagShared = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form().param("name", "ExclLeakShared").param("color", "#ff00ff")), JsonObject.class)
                .getString("id");
        String tagPrivate = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form().param("name", "ExclLeakPrivate").param("color", "#ff00fe")), JsonObject.class)
                .getString("id");
        target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form()
                        .param("title", "Shared doc with private tag").param("language", "eng")
                        .param("tags", tagShared)
                        .param("tags", tagPrivate)
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);

        // User B gets READ on the shared tag only; the doc is now visible to B,
        // the private tag on it is not.
        clientUtil.createUser("exclleak2");
        String t2 = clientUtil.login("exclleak2");
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form()
                        .param("source", tagShared)
                        .param("perm", "READ")
                        .param("target", "exclleak2")
                        .param("type", "USER")), JsonObject.class);

        // Baseline for B: shared tag selected, no exclusion -> the shared doc counts.
        JsonObject baseline = target().path("/tag/facets")
                .queryParam("tags", tagShared)
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class);
        Assertions.assertEquals(1, baseline.getInt("total"));

        // B excludes A's PRIVATE tag id: must be silently dropped, ALL counts
        // identical to the no-exclude baseline (AND path).
        JsonObject probed = target().path("/tag/facets")
                .queryParam("tags", tagShared)
                .queryParam("exclude", tagPrivate)
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class);
        Assertions.assertEquals(baseline.getInt("total"), probed.getInt("total"),
                "excluding an unreadable tag must not change the total (info disclosure)");
        Assertions.assertEquals(baseline.getJsonObject("facets"), probed.getJsonObject("facets"),
                "excluding an unreadable tag must not change any facet count");

        // OR path likewise.
        JsonObject baselineOr = target().path("/tag/facets")
                .queryParam("tags", tagShared)
                .queryParam("mode", "or")
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class);
        JsonObject probedOr = target().path("/tag/facets")
                .queryParam("tags", tagShared)
                .queryParam("mode", "or")
                .queryParam("exclude", tagPrivate)
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class);
        Assertions.assertEquals(baselineOr.getInt("total"), probedOr.getInt("total"));
        Assertions.assertEquals(baselineOr.getJsonObject("facets"), probedOr.getJsonObject("facets"));

        // No-selection facets (stats-equivalent) likewise.
        JsonObject baselineNoSel = target().path("/tag/facets")
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class);
        JsonObject probedNoSel = target().path("/tag/facets")
                .queryParam("exclude", tagPrivate)
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class);
        Assertions.assertEquals(baselineNoSel.getJsonObject("facets"), probedNoSel.getJsonObject("facets"));

        // Control: the owner A CAN read the private tag, so for A the exclusion
        // must still take effect (drops the doc from the shared-tag counts).
        JsonObject ownerProbed = target().path("/tag/facets")
                .queryParam("tags", tagShared)
                .queryParam("exclude", tagPrivate)
                .request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .get(JsonObject.class);
        Assertions.assertEquals(0, ownerProbed.getInt("total"));
    }

    /**
     * SEC-03: tag stats / facets / co-occurrence must be scoped to the caller's
     * ACL. A non-admin user must not see another user's tag IDs or document
     * counts. Admin (skipAclCheck) still sees everything.
     */
    @Test
    public void testTagStatsAclScoping() {
        // User A creates two private tags and a document carrying both
        clientUtil.createUser("tagstat1");
        String t1 = clientUtil.login("tagstat1");
        String tagA1 = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form().param("name", "AclTagA1").param("color", "#ff0000")), JsonObject.class)
                .getString("id");
        String tagA2 = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form().param("name", "AclTagA2").param("color", "#ff0001")), JsonObject.class)
                .getString("id");
        target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form()
                        .param("title", "A private doc")
                        .param("language", "eng")
                        .param("tags", tagA1)
                        .param("tags", tagA2)
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);

        // User B creates its own private tag and a document
        clientUtil.createUser("tagstat2");
        String t2 = clientUtil.login("tagstat2");
        String tagB1 = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .put(Entity.form(new Form().param("name", "AclTagB1").param("color", "#00ff00")), JsonObject.class)
                .getString("id");
        target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .put(Entity.form(new Form()
                        .param("title", "B private doc")
                        .param("language", "eng")
                        .param("tags", tagB1)
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);

        // B's stats show only B's tag, never A's
        JsonObject stats = target().path("/tag/stats").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class).getJsonObject("stats");
        Assertions.assertEquals(1, stats.getInt(tagB1));
        Assertions.assertFalse(stats.containsKey(tagA1), "B must not see A's tag in stats");
        Assertions.assertFalse(stats.containsKey(tagA2), "B must not see A's tag in stats");

        // B's facets (no selection) likewise exclude A's tags
        JsonObject facets = target().path("/tag/facets").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class).getJsonObject("facets");
        Assertions.assertTrue(facets.containsKey(tagB1));
        Assertions.assertFalse(facets.containsKey(tagA1));
        Assertions.assertFalse(facets.containsKey(tagA2));

        // B's co-occurrence matrix must not reference any of A's tags
        JsonArray pairs = target().path("/tag/co-occurrence").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class).getJsonArray("pairs");
        for (int i = 0; i < pairs.size(); i++) {
            JsonObject pair = pairs.getJsonObject(i);
            String a = pair.getString("tagA");
            String b = pair.getString("tagB");
            Assertions.assertNotEquals(tagA1, a);
            Assertions.assertNotEquals(tagA1, b);
            Assertions.assertNotEquals(tagA2, a);
            Assertions.assertNotEquals(tagA2, b);
        }

        // B pivoting facets on A's invisible tag must leak nothing (AND path: getCoOccurringTagCounts + countDocumentsWithAllTags)
        JsonObject andFacets = target().path("/tag/facets")
                .queryParam("tags", tagA1)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class);
        Assertions.assertEquals(0, andFacets.getInt("total"));
        Assertions.assertTrue(andFacets.getJsonObject("facets").isEmpty());

        // OR path pivoted on A's invisible tag: getCoOccurringTagCountsOr + countDocumentsWithAnyTag
        JsonObject orFacets = target().path("/tag/facets")
                .queryParam("tags", tagA1)
                .queryParam("mode", "or")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class);
        Assertions.assertEquals(0, orFacets.getInt("total"));
        Assertions.assertTrue(orFacets.getJsonObject("facets").isEmpty());

        // OR path with A's invisible tag + B's own tag: only B's document counts; no A tag leaks
        JsonObject mixFacets = target().path("/tag/facets")
                .queryParam("tags", tagA1 + "," + tagB1)
                .queryParam("mode", "or")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class);
        Assertions.assertEquals(1, mixFacets.getInt("total"));
        JsonObject mixMap = mixFacets.getJsonObject("facets");
        Assertions.assertFalse(mixMap.containsKey(tagA1));
        Assertions.assertFalse(mixMap.containsKey(tagA2));

        // Mixed AND: one visible (B's own) + one invisible (A's) tag. AND requires ALL
        // selected tags to be visible, so the total must be 0 (not B's doc count) and no
        // A tag may leak. Guards against an implementation that merely drops invisible tags.
        JsonObject mixAnd = target().path("/tag/facets")
                .queryParam("tags", tagB1 + "," + tagA1)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class);
        Assertions.assertEquals(0, mixAnd.getInt("total"));
        JsonObject mixAndMap = mixAnd.getJsonObject("facets");
        Assertions.assertFalse(mixAndMap.containsKey(tagA1));
        Assertions.assertFalse(mixAndMap.containsKey(tagA2));

        // A still sees its own tags but not B's
        stats = target().path("/tag/stats").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .get(JsonObject.class).getJsonObject("stats");
        Assertions.assertTrue(stats.containsKey(tagA1));
        Assertions.assertTrue(stats.containsKey(tagA2));
        Assertions.assertFalse(stats.containsKey(tagB1));

        // Admin (skipAclCheck) sees everyone's tags via stats
        String adminToken = adminToken();
        stats = target().path("/tag/stats").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class).getJsonObject("stats");
        Assertions.assertTrue(stats.containsKey(tagA1));
        Assertions.assertTrue(stats.containsKey(tagA2));
        Assertions.assertTrue(stats.containsKey(tagB1));

        // Admin skipAclCheck also holds on facets and co-occurrence (which now pass ACL target lists)
        JsonObject adminFacets = target().path("/tag/facets").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class).getJsonObject("facets");
        Assertions.assertTrue(adminFacets.containsKey(tagA1));
        Assertions.assertTrue(adminFacets.containsKey(tagA2));
        Assertions.assertTrue(adminFacets.containsKey(tagB1));

        // Admin selecting A's tag (AND) sees A's co-occurring tag and the correct total
        JsonObject adminAnd = target().path("/tag/facets")
                .queryParam("tags", tagA1)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(1, adminAnd.getInt("total"));
        Assertions.assertEquals(1, adminAnd.getJsonObject("facets").getInt(tagA2));

        // Admin selecting A's tag (OR) likewise
        JsonObject adminOr = target().path("/tag/facets")
                .queryParam("tags", tagA1)
                .queryParam("mode", "or")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(1, adminOr.getInt("total"));
        Assertions.assertEquals(1, adminOr.getJsonObject("facets").getInt(tagA2));

        // Admin co-occurrence sees A's private (tagA1,tagA2) pair
        JsonArray adminPairs = target().path("/tag/co-occurrence").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class).getJsonArray("pairs");
        boolean adminSeesApair = false;
        for (int i = 0; i < adminPairs.size(); i++) {
            JsonObject pair = adminPairs.getJsonObject(i);
            String a = pair.getString("tagA");
            String b = pair.getString("tagB");
            if ((a.equals(tagA1) && b.equals(tagA2)) || (a.equals(tagA2) && b.equals(tagA1))) {
                adminSeesApair = true;
                Assertions.assertEquals(1, pair.getInt("count"));
            }
        }
        Assertions.assertTrue(adminSeesApair, "admin must see other users' private tag pairs");
    }

    /**
     * SEC-03 (positive path): when user A shares a tag READ with user B, B must
     * see exactly that shared tag and its counts across stats, facets,
     * co-occurrence and AND/OR totals, while an un-shared A tag stays hidden.
     */
    @Test
    public void testTagStatsAclSharedTag() {
        // User A: a shared tag and a private tag on one document
        clientUtil.createUser("tagshare1");
        String t1 = clientUtil.login("tagshare1");
        String tagShared = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form().param("name", "SharedTag").param("color", "#ff00ff")), JsonObject.class)
                .getString("id");
        String tagPrivate = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form().param("name", "PrivateTag").param("color", "#ff00fe")), JsonObject.class)
                .getString("id");
        target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form()
                        .param("title", "A shared+private doc")
                        .param("language", "eng")
                        .param("tags", tagShared)
                        .param("tags", tagPrivate)
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);

        // User B, then A shares only tagShared READ with B
        clientUtil.createUser("tagshare2");
        String t2 = clientUtil.login("tagshare2");
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form()
                        .param("source", tagShared)
                        .param("perm", "READ")
                        .param("target", "tagshare2")
                        .param("type", "USER")), JsonObject.class);

        // B now sees the shared tag (count 1) in stats, never the private one
        JsonObject stats = target().path("/tag/stats").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class).getJsonObject("stats");
        Assertions.assertEquals(1, stats.getInt(tagShared));
        Assertions.assertFalse(stats.containsKey(tagPrivate));

        // Facets (no selection) mirror stats
        JsonObject facets = target().path("/tag/facets").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class).getJsonObject("facets");
        Assertions.assertTrue(facets.containsKey(tagShared));
        Assertions.assertFalse(facets.containsKey(tagPrivate));

        // Selecting the shared tag: total counts the shared document (AND), but the
        // co-occurring private tag stays hidden.
        JsonObject andSel = target().path("/tag/facets")
                .queryParam("tags", tagShared)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class);
        Assertions.assertEquals(1, andSel.getInt("total"));
        Assertions.assertFalse(andSel.getJsonObject("facets").containsKey(tagPrivate));

        JsonObject orSel = target().path("/tag/facets")
                .queryParam("tags", tagShared)
                .queryParam("mode", "or")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class);
        Assertions.assertEquals(1, orSel.getInt("total"));
        Assertions.assertFalse(orSel.getJsonObject("facets").containsKey(tagPrivate));

        // Co-occurrence: the (shared, private) pair is hidden because the private tag is invisible to B
        JsonArray pairs = target().path("/tag/co-occurrence").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class).getJsonArray("pairs");
        for (int i = 0; i < pairs.size(); i++) {
            JsonObject pair = pairs.getJsonObject(i);
            Assertions.assertNotEquals(tagPrivate, pair.getString("tagA"));
            Assertions.assertNotEquals(tagPrivate, pair.getString("tagB"));
        }
    }

    /**
     * SEC-03 (positive co-occurrence): when BOTH tags of a pair are shared READ
     * with user B, B's co-occurrence matrix returns that pair with its count,
     * while a third un-shared tag on the same document is excluded from all pairs.
     */
    @Test
    public void testTagCoOccurrenceAclSharedPair() {
        clientUtil.createUser("cooc1");
        String t1 = clientUtil.login("cooc1");
        String tag1 = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form().param("name", "CoocShared1").param("color", "#010101")), JsonObject.class)
                .getString("id");
        String tag2 = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form().param("name", "CoocShared2").param("color", "#020202")), JsonObject.class)
                .getString("id");
        String tag3 = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form().param("name", "CoocPrivate3").param("color", "#030303")), JsonObject.class)
                .getString("id");
        target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form()
                        .param("title", "Co-occurrence doc")
                        .param("language", "eng")
                        .param("tags", tag1)
                        .param("tags", tag2)
                        .param("tags", tag3)
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);

        clientUtil.createUser("cooc2");
        String t2 = clientUtil.login("cooc2");
        // Share tag1 and tag2 READ with B, leave tag3 private
        for (String sharedTag : new String[]{tag1, tag2}) {
            target().path("/acl").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                    .put(Entity.form(new Form()
                            .param("source", sharedTag)
                            .param("perm", "READ")
                            .param("target", "cooc2")
                            .param("type", "USER")), JsonObject.class);
        }

        JsonArray pairs = target().path("/tag/co-occurrence").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class).getJsonArray("pairs");

        boolean foundSharedPair = false;
        for (int i = 0; i < pairs.size(); i++) {
            JsonObject pair = pairs.getJsonObject(i);
            String a = pair.getString("tagA");
            String b = pair.getString("tagB");
            // The unshared private tag must never appear
            Assertions.assertNotEquals(tag3, a);
            Assertions.assertNotEquals(tag3, b);
            if ((a.equals(tag1) && b.equals(tag2)) || (a.equals(tag2) && b.equals(tag1))) {
                foundSharedPair = true;
                Assertions.assertEquals(1, pair.getInt("count"));
            }
        }
        Assertions.assertTrue(foundSharedPair, "B must see the pair of two tags shared with it");
    }
}
