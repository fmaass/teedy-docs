package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tag synonyms over the REST edge (#280): what is stored, what resolves, what is refused, and
 * what one caller is allowed to learn about another caller's tags.
 *
 * <p>The disclosure assertions are the point of the class. The collision rule is scoped to the
 * tags the caller can READ, so an error that named an invisible tag would turn a validation
 * message into an existence oracle over other people's tags. Every "is not reported" assertion
 * below pins the OTHER half of that: the save that collides only with something invisible has to
 * SUCCEED, and both callers keep resolving their own word to their own tag.</p>
 *
 * @author fmaass
 */
public class TestTagSynonymResource extends BaseJerseyTest {

    /** A zero-width space: invisible, so it is stripped rather than refused (#305). */
    private static final String ZERO_WIDTH_SPACE = "​";

    /** A thin space: it renders as a gap, so it is INTERIOR whitespace and refused (#305). */
    private static final String THIN_SPACE = " ";

    // ---------------------------------------------------------------------------------------
    // Storage and payload
    // ---------------------------------------------------------------------------------------

    /** Synonyms are created with the tag, come back on both reads, and are replaced as a set. */
    @Test
    public void testSynonymsRideOnTheTagPayload() {
        String token = user("syn_payload");
        String tagId = createTag(token, "Insurance", "Versicherung", "Assurance");

        Assertions.assertEquals(List.of("Assurance", "Versicherung"), synonymsOfDetail(token, tagId),
                "GET /tag/{id} reports the tag's synonyms, name-ordered");
        Assertions.assertEquals(List.of("Assurance", "Versicherung"), synonymsInList(token, tagId),
                "GET /tag/list reports them too, so the tag inputs can resolve without a second call");

        updateTag(token, tagId, new Form().param("name", "Insurance").param("color", "#00ff00")
                .param("synonyms", "Versicherung"));
        Assertions.assertEquals(List.of("Versicherung"), synonymsOfDetail(token, tagId),
                "a submitted list REPLACES the set; the name left out of it is gone");
    }

    /** A tag that never had synonyms still reports the field, empty. */
    @Test
    public void testATagWithoutSynonymsReportsAnEmptyList() {
        String token = user("syn_empty");
        String tagId = createTag(token, "Plain");

        Assertions.assertEquals(List.of(), synonymsOfDetail(token, tagId));
        Assertions.assertEquals(List.of(), synonymsInList(token, tagId));
    }

    /**
     * The two ways of saying nothing are NOT the same, and this is what lets synonyms ride on the
     * existing tag write: a client that predates them saves a colour without mentioning synonyms
     * and must not wipe them, while the tag form removing its last chip must.
     */
    @Test
    public void testOmittingTheFieldKeepsSynonymsAndAnEmptyValueClearsThem() {
        String token = user("syn_absent");
        String tagId = createTag(token, "Insurance", "Versicherung");

        updateTag(token, tagId, new Form().param("name", "Insurance").param("color", "#123456"));
        Assertions.assertEquals(List.of("Versicherung"), synonymsOfDetail(token, tagId),
                "a write that does not mention synonyms must leave them exactly as they were");

        updateTag(token, tagId, new Form().param("name", "Insurance").param("color", "#123456")
                .param("synonyms", ""));
        Assertions.assertEquals(List.of(), synonymsOfDetail(token, tagId),
                "the field sent with a single empty value is the explicit \"no synonyms\"");
    }

    // ---------------------------------------------------------------------------------------
    // Normalization — the SAME rule as a tag name, not a second one
    // ---------------------------------------------------------------------------------------

    /** An invisible format character is stripped, exactly as it is from a tag name. */
    @Test
    public void testASynonymCarryingAnInvisibleCharacterIsStoredStripped() {
        String token = user("syn_invisible");
        String tagId = createTag(token, "Insurance", "Versi" + ZERO_WIDTH_SPACE + "cherung");

        Assertions.assertEquals(List.of("Versicherung"), synonymsOfDetail(token, tagId),
                "the character the user cannot see is removed rather than stored");
    }

    /** Interior whitespace is refused, whichever Unicode space it is spelled with. */
    @Test
    public void testASynonymWithInteriorWhitespaceIsRefused() {
        String token = user("syn_whitespace");

        Assertions.assertEquals(Status.BAD_REQUEST, statusOfCreate(token,
                new Form().param("name", "Insurance").param("color", "#ff0000")
                        .param("synonyms", "Versicherung" + THIN_SPACE + "Auto")),
                "a thin space inside a synonym is a space, and a tag name may not carry one");
        Assertions.assertEquals(Status.BAD_REQUEST, statusOfCreate(token,
                new Form().param("name", "Insurance2").param("color", "#ff0000")
                        .param("synonyms", "Versicherung Auto")),
                "so is an ordinary one");
    }

    /** So are the two characters the search grammar owns. */
    @Test
    public void testASynonymWithAGrammarCharacterIsRefused() {
        String token = user("syn_grammar");

        Assertions.assertEquals(Status.BAD_REQUEST, statusOfCreate(token,
                new Form().param("name", "Insurance").param("color", "#ff0000")
                        .param("synonyms", "Versicherung:1")));
        Assertions.assertEquals(Status.BAD_REQUEST, statusOfCreate(token,
                new Form().param("name", "Insurance2").param("color", "#ff0000")
                        .param("synonyms", "Versicherung*")));
    }

    /** Two spellings of one word are one synonym, not a collision with itself. */
    @Test
    public void testCaseDuplicatesInOneSubmissionAreReducedToTheFirst() {
        String token = user("syn_dupes");
        String tagId = createTag(token, "Insurance", "Versicherung", "versicherung");

        Assertions.assertEquals(List.of("Versicherung"), synonymsOfDetail(token, tagId));
    }

    // ---------------------------------------------------------------------------------------
    // Collisions, both directions
    // ---------------------------------------------------------------------------------------

    /** A synonym may not take the name of a tag the caller can see, and the error says which. */
    @Test
    public void testASynonymMayNotTakeAVisibleTagName() {
        String token = user("syn_coll_name");
        createTag(token, "Car");

        JsonObject error = errorOfCreate(token, new Form().param("name", "Insurance")
                .param("color", "#ff0000").param("synonyms", "Car"));
        Assertions.assertEquals("SynonymInUse", error.getString("type"));
        Assertions.assertTrue(error.getString("message").contains("Car"),
                "the error must name the conflict: " + error.getString("message"));
    }

    /** Nor the synonym of another visible tag — a name resolves to exactly one tag. */
    @Test
    public void testASynonymMayNotTakeAnotherTagsSynonym() {
        String token = user("syn_coll_syn");
        createTag(token, "Insurance", "Versicherung");

        JsonObject error = errorOfCreate(token, new Form().param("name", "Health")
                .param("color", "#ff0000").param("synonyms", "Versicherung"));
        Assertions.assertEquals("SynonymInUse", error.getString("type"));
        Assertions.assertTrue(error.getString("message").contains("Insurance"),
                "the error must name the tag that already owns the word: " + error.getString("message"));
    }

    /** And a tag may not be created onto a name already in use as a synonym — the other direction. */
    @Test
    public void testATagMayNotBeCreatedOnAVisibleSynonym() {
        String token = user("syn_coll_create");
        createTag(token, "Insurance", "Versicherung");

        JsonObject error = errorOfCreate(token, new Form().param("name", "Versicherung")
                .param("color", "#ff0000"));
        Assertions.assertEquals("TagNameIsSynonym", error.getString("type"));
        Assertions.assertTrue(error.getString("message").contains("Insurance"),
                "the error must name where the word went: " + error.getString("message"));
    }

    /** A RENAME onto a synonym is refused for the same reason a create is. */
    @Test
    public void testATagMayNotBeRenamedOntoAVisibleSynonym() {
        String token = user("syn_coll_rename");
        createTag(token, "Insurance", "Versicherung");
        String carId = createTag(token, "Car");

        Response response = target().path("/tag/" + carId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("name", "Versicherung").param("color", "#ff0000")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("TagNameIsSynonym", response.readEntity(JsonObject.class).getString("type"));
    }

    /** A tag's own name is not available as its own synonym either. */
    @Test
    public void testATagsOwnNameMayNotBeItsSynonym() {
        String token = user("syn_coll_self");

        JsonObject error = errorOfCreate(token, new Form().param("name", "Insurance")
                .param("color", "#ff0000").param("synonyms", "insurance"));
        Assertions.assertEquals("SynonymInUse", error.getString("type"));
    }

    /**
     * Re-saving a form nobody changed must keep working. The tag being written is excluded from
     * the comparison by id, so its own stored synonyms are not collisions with themselves.
     */
    @Test
    public void testResavingTheSameSynonymsIsAccepted() {
        String token = user("syn_idempotent");
        String tagId = createTag(token, "Insurance", "Versicherung", "Assurance");

        updateTag(token, tagId, new Form().param("name", "Insurance").param("color", "#ff0000")
                .param("synonyms", "Versicherung").param("synonyms", "Assurance"));

        Assertions.assertEquals(List.of("Assurance", "Versicherung"), synonymsOfDetail(token, tagId));
    }

    /**
     * Renaming a tag onto a synonym it KEEPS has to be refused rather than quietly produce a tag
     * whose name is also one of its synonyms. The "make the synonym the main name" swap is the
     * request below it, which demotes the old name in the same write.
     */
    @Test
    public void testATagMayNotBeRenamedOntoItsOwnSynonym() {
        String token = user("syn_selfswap");
        String tagId = createTag(token, "Insurance", "Auto");

        Response response = target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("name", "Auto").param("color", "#ff0000")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("TagNameIsSynonym", response.readEntity(JsonObject.class).getString("type"));
    }

    /**
     * The same rename IS accepted when the request also removes that synonym: those are two
     * ordinary edits at once, and refusing them would be a collision with a name that will not
     * exist by the time the transaction commits.
     */
    @Test
    public void testATagMayBeRenamedOntoASynonymTheSameRequestRemoves() {
        String token = user("syn_selfswap_ok");
        String tagId = createTag(token, "Insurance", "Auto");

        updateTag(token, tagId, new Form().param("name", "Auto").param("color", "#ff0000")
                .param("synonyms", ""));

        JsonObject detail = target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals("Auto", detail.getString("name"));
        Assertions.assertEquals(List.of(), stringsOf(detail.getJsonArray("synonyms")));
    }

    // ---------------------------------------------------------------------------------------
    // The swap: promoting a synonym to the main name — the follow-up #280 deferred (TEEDY-153)
    // ---------------------------------------------------------------------------------------

    /**
     * Promoting a synonym to the main name and demoting the old name to a synonym is ONE ordinary
     * tag write: the form sends the new name together with the synonym list the tag is left with,
     * and both halves of the collision rule read that same request. Nothing about the tag's
     * identity moves — the id, its documents and its ACLs are untouched — so every word that
     * resolved before resolves after, only through the other side of the pair.
     */
    @Test
    public void testASynonymAndTheMainNameCanBeSwappedInOneWrite() {
        String token = user("syn_swap");
        String tagId = createTag(token, "Rechnung", "Quittung", "Beleg");

        updateTag(token, tagId, new Form().param("name", "Quittung").param("color", "#ff0000")
                .param("synonyms", "Rechnung").param("synonyms", "Beleg"));

        JsonObject detail = target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals("Quittung", detail.getString("name"),
                "the promoted synonym is the tag's name after the write");
        Assertions.assertEquals(List.of("Beleg", "Rechnung"), stringsOf(detail.getJsonArray("synonyms")),
                "the demoted name joins the synonyms, and the untouched one is still there");
        Assertions.assertEquals(List.of("Beleg", "Rechnung"), synonymsInList(token, tagId),
                "and /tag/list, which the tag inputs resolve against, agrees");
    }

    /**
     * The swap keeps the documents: they link to the tag by ID, so a document tagged under the old
     * main name is still tagged, and every word of the pair still finds it.
     */
    @Test
    public void testDocumentsKeepResolvingAcrossTheSwap() {
        String token = user("syn_swap_docs");
        String tagId = createTag(token, "Rechnung", "Quittung", "Beleg");
        String docId = createDocument(token, "Invoice filed before the swap", tagId);
        createDocument(token, "Unrelated", null);

        updateTag(token, tagId, new Form().param("name", "Quittung").param("color", "#ff0000")
                .param("synonyms", "Rechnung").param("synonyms", "Beleg"));

        Assertions.assertEquals(List.of(tagId), tagIdsOfDocument(token, docId),
                "the document keeps the very same tag row — the swap renames, it does not re-tag");
        Assertions.assertEquals(Set.of(docId), searchIds(token, "tag:Quittung"),
                "the promoted word resolves as the tag's name now");
        Assertions.assertEquals(Set.of(docId), searchIds(token, "tag:Rechnung"),
                "the demoted word keeps resolving, now through the synonym");
        Assertions.assertEquals(Set.of(docId), searchIds(token, "tag:Beleg"),
                "and the synonym the swap did not touch is unaffected");
    }

    /**
     * The swap is available to a caller holding WRITE without READ as well — the tag being written
     * is inside its own collision scope, and this is the write that scope has to accept rather than
     * refuse: the old name is not a collision with itself once the request is demoting it.
     */
    @Test
    public void testAWriteOnlyCallerMaySwapTheNameWithASynonym() {
        WriteOnly fixture = seedWriteOnlyTag("synscope_promote", "Insurance", "Versicherung");

        updateTag(fixture.callerToken(), fixture.tagId(),
                new Form().param("name", "Versicherung").param("color", "#ff0000")
                        .param("synonyms", "Insurance"));

        Assertions.assertEquals("Versicherung", nameOfDetail(fixture.ownerToken(), fixture.tagId()));
        Assertions.assertEquals(List.of("Insurance"),
                synonymsOfDetail(fixture.ownerToken(), fixture.tagId()));
    }

    // ---------------------------------------------------------------------------------------
    // Permissions and disclosure
    // ---------------------------------------------------------------------------------------

    /**
     * A collision with a tag the caller CANNOT SEE is not a collision to that caller: reporting it
     * would confirm the invisible tag exists and what word it uses. Both saves succeed, and each
     * caller goes on resolving the word to their own tag.
     */
    @Test
    public void testACollisionWithAnInvisibleTagIsNotReported() {
        String ownerToken = user("syn_hidden_owner");
        String otherToken = user("syn_hidden_other");
        String ownerTagId = createTag(ownerToken, "Secret", "Geheim");
        String ownerDocId = createDocument(ownerToken, "Owner secret document", ownerTagId);

        // The name the owner uses as a synonym is free for anybody who cannot see that tag.
        String otherTagId = createTag(otherToken, "Geheim");
        String otherDocId = createDocument(otherToken, "Other plain document", otherTagId);
        // And so is using it as a synonym of something else.
        String otherSynonymTagId = createTag(otherToken, "Holidays", "Geheim2");
        Assertions.assertNotNull(otherSynonymTagId);

        Assertions.assertEquals(Set.of(ownerDocId), searchIds(ownerToken, "tag:Geheim"),
                "the owner's word still resolves to the owner's tag");
        Assertions.assertEquals(Set.of(otherDocId), searchIds(otherToken, "tag:Geheim"),
                "and the other user's word resolves to theirs — neither sees the other's document");
    }

    /** A synonym resolves the canonical tag's documents for a caller who can read the tag. */
    @Test
    public void testSearchingASynonymFindsTheCanonicalTagsDocuments() {
        String token = user("syn_search");
        String tagId = createTag(token, "Insurance", "Versicherung");
        String docId = createDocument(token, "Insurance policy", tagId);
        createDocument(token, "Unrelated", null);

        Assertions.assertEquals(Set.of(docId), searchIds(token, "tag:Versicherung"),
                "the synonym resolves to the tag, so the tag's documents come back");
        Assertions.assertEquals(Set.of(docId), searchIds(token, "tag:Insurance"),
                "and the canonical name still does");
    }

    /** A synonym never reveals a tag the caller cannot read. */
    @Test
    public void testASynonymDoesNotResolveAnInvisibleTag() {
        String ownerToken = user("syn_invisible_owner");
        String strangerToken = user("syn_invisible_stranger");
        String tagId = createTag(ownerToken, "Insurance", "Versicherung");
        createDocument(ownerToken, "Owner insurance policy", tagId);
        String strangerDocId = createDocument(strangerToken, "Stranger document", null);

        Assertions.assertEquals(Set.of(), searchIds(strangerToken, "tag:Versicherung"),
                "an unresolvable term returns nothing, and must not return the owner's document");
        Assertions.assertEquals(Set.of(strangerDocId), searchIds(strangerToken, null),
                "the stranger's own listing is unaffected");
    }

    /**
     * Editing synonyms is editing the tag, so it takes WRITE. A reader can SEE them — they are
     * part of the tag they are allowed to read — but cannot change them, and the refusal is the
     * same 404 every other tag write gives a caller without WRITE.
     */
    @Test
    public void testEditingSynonymsRequiresWrite() {
        String ownerToken = user("syn_perm_owner");
        clientUtil.createUser("syn_perm_reader");
        String readerToken = clientUtil.login("syn_perm_reader");
        String tagId = createTag(ownerToken, "Insurance", "Versicherung");
        grantRead(ownerToken, tagId, "syn_perm_reader");

        Assertions.assertEquals(List.of("Versicherung"), synonymsOfDetail(readerToken, tagId),
                "a reader sees the synonyms of a tag they may read");

        Response response = target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, readerToken)
                .post(Entity.form(new Form().param("name", "Insurance").param("color", "#ff0000")
                        .param("synonyms", "Assurance")));
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()),
                "a reader may not edit them");
        Assertions.assertEquals(List.of("Versicherung"), synonymsOfDetail(ownerToken, tagId),
                "and the refused write changed nothing");
    }

    /** Deleting the tag takes its synonyms out of resolution with it. */
    @Test
    public void testDeletingTheTagStopsItsSynonymResolving() {
        String token = user("syn_delete");
        String tagId = createTag(token, "Insurance", "Versicherung");
        createDocument(token, "Insurance policy", tagId);

        target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .delete(JsonObject.class);

        Assertions.assertEquals(Set.of(), searchIds(token, "tag:Versicherung"),
                "a deleted tag's synonym must stop resolving");
        // ...and the name it held is free again.
        String reusedId = createTag(token, "Versicherung");
        Assertions.assertNotNull(reusedId);
    }

    // ---------------------------------------------------------------------------------------
    // The collision SCOPE, when the caller can WRITE a tag it cannot READ
    // ---------------------------------------------------------------------------------------
    //
    // READ and WRITE are independent ACL rows (AclResource.add creates exactly the one perm it
    // is given), so "can edit this tag but cannot see it in a listing" is a state the API can be
    // put into. The scope of the collision rule for a write on T by caller C is therefore
    //
    //     (the tags C can READ)  ∪  {T}
    //
    // and nothing else. T belongs in it because C holds WRITE on T — already checked before the
    // rule runs — and is entitled to T's own name and synonyms. Everything outside it must be
    // invisible to the rule: a name that matches only a tag C cannot read has to be ACCEPTED,
    // with the same status and the same body as a name that matches nothing at all, or the
    // refusal itself becomes a way to ask whether a word is in use somewhere C cannot look.

    /** Owner-created tag the acting caller may WRITE but not READ, plus the caller's token. */
    private record WriteOnly(String ownerToken, String callerToken, String username, String tagId) {
    }

    private WriteOnly seedWriteOnlyTag(String prefix, String tagName, String... synonyms) {
        String ownerToken = user(prefix + "_own");
        String username = prefix + "_edit";
        clientUtil.createUser(username);
        String callerToken = clientUtil.login(username);
        String tagId = createTag(ownerToken, tagName, synonyms);
        grantAcl(ownerToken, tagId, "WRITE", username);
        return new WriteOnly(ownerToken, callerToken, username, tagId);
    }

    /**
     * A synonym that collides ONLY with a tag the caller cannot read is stored, and the answer is
     * indistinguishable from the answer to a name nothing uses — same status, same body. A 400
     * here would let anyone enumerate names by watching which ones are refused.
     */
    @Test
    public void testASynonymCollidingOnlyWithAnUnreadableTagIsAccepted() {
        WriteOnly fixture = seedWriteOnlyTag("synscope_hidden", "Insurance");
        // A tag of the owner's the caller has no grant on at all.
        createTag(fixture.ownerToken(), "Geheim");

        Response collides = postTag(fixture.callerToken(), fixture.tagId(),
                new Form().param("name", "Insurance").param("color", "#ff0000")
                        .param("synonyms", "Geheim"));
        JsonObject collidesBody = collides.readEntity(JsonObject.class);
        Assertions.assertEquals(List.of("Geheim"),
                synonymsOfDetail(fixture.ownerToken(), fixture.tagId()),
                "the synonym must actually be stored, not merely not refused");

        Response matchesNothing = postTag(fixture.callerToken(), fixture.tagId(),
                new Form().param("name", "Insurance").param("color", "#ff0000")
                        .param("synonyms", "Freieswort"));
        JsonObject matchesNothingBody = matchesNothing.readEntity(JsonObject.class);

        Assertions.assertEquals(matchesNothing.getStatus(), collides.getStatus(),
                "colliding with an unreadable tag must answer with the same status as colliding "
                        + "with nothing");
        Assertions.assertEquals(matchesNothingBody, collidesBody,
                "...and with the same body, so the response carries no signal about the "
                        + "unreadable tag");
    }

    /**
     * The retained-synonym rule has to hold for a WRITE-only caller too: renaming T onto a synonym
     * T KEEPS must be refused even though T is not in the caller's readable set.
     */
    @Test
    public void testAWriteOnlyCallerMayNotRenameOntoARetainedSynonym() {
        WriteOnly fixture = seedWriteOnlyTag("synscope_swap", "Insurance", "Auto");

        Response response = postTag(fixture.callerToken(), fixture.tagId(),
                new Form().param("name", "Auto").param("color", "#ff0000"));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("TagNameIsSynonym", response.readEntity(JsonObject.class).getString("type"));
        Assertions.assertEquals("Insurance", nameOfDetail(fixture.ownerToken(), fixture.tagId()),
                "the refused rename must have changed nothing");
    }

    /** ...and is accepted when the same request drops that synonym. */
    @Test
    public void testAWriteOnlyCallerMayRenameOntoASynonymTheSameRequestRemoves() {
        WriteOnly fixture = seedWriteOnlyTag("synscope_swap_ok", "Insurance", "Auto");

        updateTag(fixture.callerToken(), fixture.tagId(),
                new Form().param("name", "Auto").param("color", "#ff0000").param("synonyms", ""));

        Assertions.assertEquals("Auto", nameOfDetail(fixture.ownerToken(), fixture.tagId()));
        Assertions.assertEquals(List.of(), synonymsOfDetail(fixture.ownerToken(), fixture.tagId()));
    }

    /** T's own name is still not available as T's own synonym for a WRITE-only caller. */
    @Test
    public void testAWriteOnlyCallerMayNotGiveTheTagItsOwnNameAsASynonym() {
        WriteOnly fixture = seedWriteOnlyTag("synscope_self", "Insurance");

        Response response = postTag(fixture.callerToken(), fixture.tagId(),
                new Form().param("name", "Insurance").param("color", "#ff0000")
                        .param("synonyms", "insurance"));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("SynonymInUse", response.readEntity(JsonObject.class).getString("type"));
    }

    /** A tag the caller CAN read is still a collision, and is still named in the error. */
    @Test
    public void testAWriteOnlyCallerIsStillRefusedAReadableTagsName() {
        WriteOnly fixture = seedWriteOnlyTag("synscope_readable", "Insurance");
        String readableId = createTag(fixture.ownerToken(), "Car");
        grantRead(fixture.ownerToken(), readableId, fixture.username());

        Response response = postTag(fixture.callerToken(), fixture.tagId(),
                new Form().param("name", "Insurance").param("color", "#ff0000")
                        .param("synonyms", "Car"));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        JsonObject error = response.readEntity(JsonObject.class);
        Assertions.assertEquals("SynonymInUse", error.getString("type"));
        Assertions.assertTrue(error.getString("message").contains("Car"),
                "the error must name the readable tag it collides with: " + error.getString("message"));
    }

    // --- helpers ---

    private String user(String username) {
        clientUtil.createUser(username);
        return clientUtil.login(username);
    }

    private String createTag(String token, String name, String... synonyms) {
        Form form = new Form().param("name", name).param("color", "#ff0000");
        for (String synonym : synonyms) {
            form.param("synonyms", synonym);
        }
        return target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(form), JsonObject.class)
                .getString("id");
    }

    private void updateTag(String token, String tagId, Form form) {
        JsonObject json = target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(form), JsonObject.class);
        Assertions.assertEquals(tagId, json.getString("id"));
    }

    private void grantRead(String token, String tagId, String username) {
        grantAcl(token, tagId, "READ", username);
    }

    /**
     * Grant ONE permission on a tag. READ and WRITE are independent rows, so this really does
     * hand out edit-without-view when it is called with WRITE — the state the collision scope
     * has to answer for.
     */
    private void grantAcl(String token, String sourceId, String perm, String username) {
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("source", sourceId)
                        .param("perm", perm)
                        .param("target", username)
                        .param("type", "USER")), JsonObject.class);
    }

    /** POST /tag/{id} as the given caller, returning the raw response for status/body assertions. */
    private Response postTag(String token, String tagId, Form form) {
        return target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(form));
    }

    private Status statusOfCreate(String token, Form form) {
        return Status.fromStatusCode(target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(form)).getStatus());
    }

    private JsonObject errorOfCreate(String token, Form form) {
        Response response = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(form));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        return response.readEntity(JsonObject.class);
    }

    private String nameOfDetail(String token, String tagId) {
        return target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class).getString("name");
    }

    /** The ids of the tags a document carries — the swap must not move a document off its tag. */
    private List<String> tagIdsOfDocument(String token, String documentId) {
        JsonArray tags = target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class).getJsonArray("tags");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < tags.size(); i++) {
            ids.add(tags.getJsonObject(i).getString("id"));
        }
        return ids;
    }

    private List<String> synonymsOfDetail(String token, String tagId) {
        return stringsOf(target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class).getJsonArray("synonyms"));
    }

    private List<String> synonymsInList(String token, String tagId) {
        JsonArray tags = target().path("/tag/list").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class).getJsonArray("tags");
        for (int i = 0; i < tags.size(); i++) {
            JsonObject tag = tags.getJsonObject(i);
            if (tagId.equals(tag.getString("id"))) {
                return stringsOf(tag.getJsonArray("synonyms"));
            }
        }
        return Assertions.fail("tag " + tagId + " is missing from /tag/list");
    }

    private static List<String> stringsOf(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            values.add(array.getString(i));
        }
        return values;
    }

    private String createDocument(String token, String title, String tagId) {
        Form form = new Form().param("title", title).param("language", "eng");
        if (tagId != null) {
            form.param("tags", tagId);
        }
        return target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(form), JsonObject.class)
                .getString("id");
    }

    private Set<String> searchIds(String token, String query) {
        WebTarget listTarget = target().path("/document/list");
        if (query != null) {
            listTarget = listTarget.queryParam("search", query);
        }
        JsonArray documents = listTarget.request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class).getJsonArray("documents");
        List<String> idList = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            idList.add(documents.getJsonObject(i).getString("id"));
        }
        return Set.copyOf(idList);
    }
}
