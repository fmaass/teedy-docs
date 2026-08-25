package com.sismics.docs.rest;

import com.sismics.docs.rest.util.TagReductionUtil;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tests the tag-reduction run (#293): removing from a document every tag that a tag below it on
 * the SAME document already implies, over the caller's current selection in the document list.
 *
 * <p>Three properties carry the whole feature and each is asserted against a read-back of the
 * document rather than against the endpoint's own answer:</p>
 * <ul>
 *   <li>the preview modifies nothing;</li>
 *   <li>the execute pass re-derives its removal set server-side, so a preview that has gone stale
 *       — or a client that invents one — cannot remove a tag the rule no longer calls redundant;</li>
 *   <li>a tag the caller cannot READ never causes a removal, and a document the caller cannot
 *       WRITE is skipped rather than touched or thrown over.</li>
 * </ul>
 *
 * @author fmaass
 */
public class TestTagReductionResource extends BaseJerseyTest {
    private String createTag(String token, String name, String parentId) {
        Form form = new Form().param("name", name).param("color", "#3399cc");
        if (parentId != null) {
            form.param("parent", parentId);
        }
        return target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(form), JsonObject.class)
                .getString("id");
    }

    private String createDocument(String token, String title, String... tagIds) {
        Form form = new Form().param("title", title).param("language", "eng");
        for (String tagId : tagIds) {
            form.param("tags", tagId);
        }
        return target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(form), JsonObject.class)
                .getString("id");
    }

    /** The tag IDs currently on a document, read back through the document endpoint. */
    private Set<String> tagsOf(String token, String documentId) {
        JsonObject json = target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Set<String> ids = new HashSet<>();
        for (JsonValue value : json.getJsonArray("tags")) {
            ids.add(value.asJsonObject().getString("id"));
        }
        return ids;
    }

    private void share(String token, String sourceId, String perm, String username) {
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("source", sourceId)
                        .param("perm", perm)
                        .param("target", username)
                        .param("type", "USER")), JsonObject.class);
    }

    private JsonObject reduce(String token, Form form) {
        return target().path("/tag/reduce").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(form), JsonObject.class);
    }

    private JsonObject reduce(String token, boolean dryRun, String... documentIds) {
        Form form = new Form().param("dryRun", Boolean.toString(dryRun));
        for (String documentId : documentIds) {
            form.param("documents", documentId);
        }
        return reduce(token, form);
    }

    /** The tag IDs the report says were (or would be) removed from one document. */
    private List<String> reported(JsonObject report, String documentId) {
        for (JsonValue value : report.getJsonArray("documents")) {
            JsonObject document = value.asJsonObject();
            if (document.getString("id").equals(documentId)) {
                List<String> ids = new ArrayList<>();
                for (JsonValue tag : document.getJsonArray("tags")) {
                    ids.add(tag.asJsonObject().getString("id"));
                }
                return ids;
            }
        }
        return null;
    }

    private static List<String> skipped(JsonObject report) {
        List<String> ids = new ArrayList<>();
        jakarta.json.JsonArray array = report.getJsonArray("skipped");
        for (int i = 0; i < array.size(); i++) {
            ids.add(array.getString(i));
        }
        return ids;
    }

    /**
     * The preview names what would go and touches nothing — the reporter's first requirement
     * ("some preview/dry-run would be good, to not destroy"). Proven by reading the document back,
     * not by trusting the response.
     */
    @Test
    public void testTheDryRunReportsTheRemovalsAndChangesNothing() {
        clientUtil.createUser("tagreduce1");
        String token = clientUtil.login("tagreduce1");
        String parentId = createTag(token, "Insurance1", null);
        String childId = createTag(token, "Car1", parentId);
        String documentId = createDocument(token, "Insurance papers", parentId, childId);

        JsonObject report = reduce(token, true, documentId);

        Assertions.assertTrue(report.getBoolean("dryRun"));
        Assertions.assertEquals(1, report.getInt("count"));
        Assertions.assertEquals(List.of(parentId), reported(report, documentId),
                "the preview names the redundant parent");
        Assertions.assertEquals("Insurance1",
                report.getJsonArray("documents").getJsonObject(0).getJsonArray("tags")
                        .getJsonObject(0).getString("name"));
        Assertions.assertEquals(Set.of(parentId, childId), tagsOf(token, documentId),
                "the preview removed nothing");
    }

    /**
     * Redundancy is transitive: a document carrying Insurance / Car / 2026 in full keeps only 2026,
     * because that tag alone already places the document under both tags above it.
     */
    @Test
    public void testExecuteReducesAWholeChainToItsDeepestTag() {
        clientUtil.createUser("tagreduce2");
        String token = clientUtil.login("tagreduce2");
        String insuranceId = createTag(token, "Insurance2", null);
        String carId = createTag(token, "Car2", insuranceId);
        String yearId = createTag(token, "Y2026", carId);
        String documentId = createDocument(token, "Car insurance 2026", insuranceId, carId, yearId);
        String untouchedId = createDocument(token, "Only the parent", insuranceId);

        JsonObject report = reduce(token, false, documentId, untouchedId);

        Assertions.assertFalse(report.getBoolean("dryRun"));
        Assertions.assertEquals(2, report.getInt("count"));
        Assertions.assertEquals(List.of(insuranceId, carId), reported(report, documentId));
        Assertions.assertNull(reported(report, untouchedId),
                "a document with nothing redundant on it is not reported as changed");
        Assertions.assertEquals(Set.of(yearId), tagsOf(token, documentId),
                "only the deepest tag survives");
        Assertions.assertEquals(Set.of(insuranceId), tagsOf(token, untouchedId),
                "and an unrelated selected document is untouched");
    }

    /** Nothing is removed unless the caller asked for it explicitly: the default is a preview. */
    @Test
    public void testOmittingTheFlagIsADryRun() {
        clientUtil.createUser("tagreduce3");
        String token = clientUtil.login("tagreduce3");
        String parentId = createTag(token, "Insurance3", null);
        String childId = createTag(token, "Car3", parentId);
        String documentId = createDocument(token, "Defaults to preview", parentId, childId);

        JsonObject report = reduce(token, new Form().param("documents", documentId));

        Assertions.assertTrue(report.getBoolean("dryRun"), "an absent flag means preview");
        Assertions.assertEquals(Set.of(parentId, childId), tagsOf(token, documentId));
    }

    /**
     * A flag that is not the word "false" previews too.
     *
     * <p>This is the fail-safe direction of a destructive default, and it is not theoretical: taken
     * as a {@code Boolean} parameter, JAX-RS resolves it through {@code Boolean.valueOf}, which
     * answers FALSE to every string that is not "true" — so a truncated or typo'd flag would arrive
     * as a real removal request.</p>
     */
    @Test
    public void testAMalformedFlagPreviews() {
        clientUtil.createUser("tagreduce12");
        String token = clientUtil.login("tagreduce12");
        String parentId = createTag(token, "Insurance12", null);
        String childId = createTag(token, "Car12", parentId);
        String documentId = createDocument(token, "Malformed flag", parentId, childId);

        JsonObject report = reduce(token, new Form()
                .param("documents", documentId)
                .param("dryRun", "fals"));

        Assertions.assertTrue(report.getBoolean("dryRun"), "anything but \"false\" previews");
        Assertions.assertEquals(Set.of(parentId, childId), tagsOf(token, documentId));
    }

    /**
     * Only the exact word "false" executes. {@code FALSE} and a padded {@code " false "} preview.
     *
     * <p>The flag is the whole safety default of a destructive endpoint, so it is a literal
     * comparison rather than a lenient one: every value that is not the documented word means the
     * caller did not clearly ask for a removal, and the safe reading of an unclear request is the
     * one that changes nothing.</p>
     */
    @Test
    public void testOnlyTheExactWordFalseExecutes() {
        clientUtil.createUser("tagreduce13");
        String token = clientUtil.login("tagreduce13");
        String parentId = createTag(token, "Insurance13", null);
        String childId = createTag(token, "Car13", parentId);
        String documentId = createDocument(token, "Case and padding", parentId, childId);

        for (String flag : List.of("FALSE", "False", " false ")) {
            JsonObject report = reduce(token, new Form()
                    .param("documents", documentId)
                    .param("dryRun", flag));

            Assertions.assertTrue(report.getBoolean("dryRun"), "\"" + flag + "\" is not the word false");
            Assertions.assertEquals(Set.of(parentId, childId), tagsOf(token, documentId),
                    "\"" + flag + "\" must not remove anything");
        }
    }

    /**
     * WRITE inherited from a TAG on the document is enough to reduce it — Teedy's primary sharing
     * model, where a document is shared by sharing the tag it carries rather than by a per-document
     * ACL. A caller with no direct document ACL at all may still edit such a document (that is what
     * {@code AclDao#checkPermission} resolves for every document write in the codebase), so the
     * reduction must reach it too rather than reporting it as untouchable.
     */
    @Test
    public void testWriteInheritedFromATagIsEnoughToReduce() {
        clientUtil.createUser("tagreduce14");
        clientUtil.createUser("tagreduce15");
        String ownerToken = clientUtil.login("tagreduce14");
        String editorToken = clientUtil.login("tagreduce15");

        String parentId = createTag(ownerToken, "Insurance14", null);
        String childId = createTag(ownerToken, "Car14", parentId);
        // The tags are shared, the DOCUMENT is not: its permissions are inherited from them.
        for (String tagId : List.of(parentId, childId)) {
            share(ownerToken, tagId, "READ", "tagreduce15");
            share(ownerToken, tagId, "WRITE", "tagreduce15");
        }
        String documentId = createDocument(ownerToken, "Shared through its tags", parentId, childId);

        JsonObject report = reduce(editorToken, false, documentId);

        Assertions.assertTrue(skipped(report).isEmpty(),
                "a document writable through a tag ACL is reducible, not skipped");
        Assertions.assertEquals(List.of(parentId), reported(report, documentId));
        Assertions.assertEquals(Set.of(childId), tagsOf(ownerToken, documentId));
    }

    /**
     * A selection may include a document shared read-only. It is reported as skipped — not an
     * error, because a hundred-document selection must still reduce the other ninety-nine — and it
     * is not modified.
     */
    @Test
    public void testADocumentTheCallerCannotWriteIsSkipped() {
        clientUtil.createUser("tagreduce4");
        clientUtil.createUser("tagreduce5");
        String ownerToken = clientUtil.login("tagreduce4");
        String readerToken = clientUtil.login("tagreduce5");

        String parentId = createTag(ownerToken, "Insurance4", null);
        String childId = createTag(ownerToken, "Car4", parentId);
        String documentId = createDocument(ownerToken, "Read-only for them", parentId, childId);
        share(ownerToken, documentId, "READ", "tagreduce5");
        share(ownerToken, parentId, "READ", "tagreduce5");
        share(ownerToken, childId, "READ", "tagreduce5");

        JsonObject report = reduce(readerToken, false, documentId);

        Assertions.assertEquals(0, report.getInt("count"));
        Assertions.assertEquals(List.of(documentId), skipped(report));
        Assertions.assertEquals(Set.of(parentId, childId), tagsOf(ownerToken, documentId),
                "a read-only share is not reducible by the reader");
    }

    /**
     * An ID that resolves to nothing the caller may reduce — deleted, trashed or never existed —
     * answers exactly as a read-only document does. The two are deliberately indistinguishable:
     * a distinct answer would be an existence oracle for other users' documents.
     */
    @Test
    public void testAnUnknownDocumentIdIsSkipped() {
        clientUtil.createUser("tagreduce6");
        String token = clientUtil.login("tagreduce6");

        JsonObject report = reduce(token, false, "d0000000-0000-0000-0000-000000000000");

        Assertions.assertEquals(0, report.getInt("count"));
        Assertions.assertEquals(List.of("d0000000-0000-0000-0000-000000000000"), skipped(report));
    }

    /**
     * The ACL rule in the direction that destroys data: a child the caller cannot READ must not
     * strip the parent they CAN read. The caller cannot see that child, so removing the parent
     * would drop the document out of the only tag filter they have for it — and would disclose
     * that something exists under that parent.
     *
     * <p>The second half of the test is the positive control: sharing the child makes the very
     * same call remove the parent, so the first half cannot be passing for an unrelated reason.</p>
     */
    @Test
    public void testAnInvisibleChildDoesNotStripAVisibleParent() {
        clientUtil.createUser("tagreduce7");
        clientUtil.createUser("tagreduce8");
        String ownerToken = clientUtil.login("tagreduce7");
        String editorToken = clientUtil.login("tagreduce8");

        String parentId = createTag(ownerToken, "Insurance7", null);
        String childId = createTag(ownerToken, "Car7", parentId);
        String documentId = createDocument(ownerToken, "Shared for editing", parentId, childId);
        share(ownerToken, documentId, "READ", "tagreduce8");
        share(ownerToken, documentId, "WRITE", "tagreduce8");
        share(ownerToken, parentId, "READ", "tagreduce8");

        JsonObject report = reduce(editorToken, false, documentId);

        Assertions.assertEquals(0, report.getInt("count"),
                "a tag the caller cannot read is not a reason to remove one they can");
        Assertions.assertTrue(skipped(report).isEmpty(), "the document itself was writable");
        Assertions.assertEquals(Set.of(parentId, childId), tagsOf(ownerToken, documentId));

        // Positive control: the same call, once the child is visible to the same caller.
        share(ownerToken, childId, "READ", "tagreduce8");
        JsonObject second = reduce(editorToken, false, documentId);

        Assertions.assertEquals(List.of(parentId), reported(second, documentId));
        Assertions.assertEquals(Set.of(childId), tagsOf(ownerToken, documentId));
    }

    /**
     * The execute pass re-derives the removal set from the CURRENT state instead of trusting the
     * preview it was confirmed from. Here the child is taken off the document between the two
     * calls, which makes the parent the only tag left — and a run that replayed the preview would
     * strip the document bare.
     */
    @Test
    public void testTheExecutePassRederivesTheRemovalSet() {
        clientUtil.createUser("tagreduce9");
        String token = clientUtil.login("tagreduce9");
        String parentId = createTag(token, "Insurance9", null);
        String childId = createTag(token, "Car9", parentId);
        String documentId = createDocument(token, "Changed since the preview", parentId, childId);

        Assertions.assertEquals(List.of(parentId), reported(reduce(token, true, documentId), documentId));

        // The child comes off the document, so the parent is no longer redundant.
        target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("title", "Changed since the preview")
                        .param("language", "eng")
                        .param("tags", parentId)), JsonObject.class);

        JsonObject report = reduce(token, false, documentId);

        Assertions.assertEquals(0, report.getInt("count"), "the stale preview is not replayed");
        Assertions.assertEquals(Set.of(parentId), tagsOf(token, documentId),
                "the document keeps the tag the current rule protects");
    }

    /**
     * The client sends document IDs and nothing else. A request that also carries a removal list —
     * a tampered client, or a replayed preview — changes nothing about what goes: the tag named
     * there survives because the rule does not call it redundant.
     */
    @Test
    public void testAClientSuppliedRemovalListIsIgnored() {
        clientUtil.createUser("tagreduce10");
        String token = clientUtil.login("tagreduce10");
        String parentId = createTag(token, "Insurance10", null);
        String childId = createTag(token, "Car10", parentId);
        String otherId = createTag(token, "Travel10", null);
        String documentId = createDocument(token, "Tampered request", parentId, childId, otherId);

        JsonObject report = reduce(token, new Form()
                .param("documents", documentId)
                .param("dryRun", "false")
                // Everything below is invented by the caller and must have no effect.
                .param("tags", childId)
                .param("tags", otherId)
                .param("remove", otherId));

        Assertions.assertEquals(List.of(parentId), reported(report, documentId));
        Assertions.assertEquals(Set.of(childId, otherId), tagsOf(token, documentId),
                "only the rule decides what goes");
    }

    /** An unauthenticated caller is refused outright. */
    @Test
    public void testAnonymousAccessIsForbidden() {
        Response response = target().path("/tag/reduce").request()
                .post(Entity.form(new Form().param("documents", "whatever")));

        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()));
    }

    /**
     * The selection is bounded. The list arrives as client-supplied form parameters and is fed
     * straight into batched {@code in (…)} reads, so an unbounded request is both a database
     * parameter-limit hazard and unbounded work in one request thread; the run is a page of the
     * document list, which no UI can push past this.
     */
    @Test
    public void testAnOversizedSelectionIsRefused() {
        clientUtil.createUser("tagreduce11");
        String token = clientUtil.login("tagreduce11");
        Form form = new Form().param("dryRun", "true");
        for (int i = 0; i <= TagReductionUtil.MAX_DOCUMENTS; i++) {
            form.param("documents", "d0000000-0000-0000-0000-" + String.format("%012d", i));
        }

        Response response = target().path("/tag/reduce").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(form));

        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("ValidationError", response.readEntity(JsonObject.class).getString("type"));
    }
}
