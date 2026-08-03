package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Form;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What a tag term that matches NO tag does to a document search.
 *
 * <p>The two polarities are not symmetric. Requiring a tag nobody carries can only return nothing,
 * so an unmatched {@code tag:} term has to make the search empty. Excluding a tag nobody carries
 * excludes nothing, so an unmatched {@code !tag:} term must contribute no filter at all and leave
 * the result exactly as it would have been without it.
 *
 * <p>The assertions compare the returned document SET against the unfiltered listing rather than a
 * count, so a filter that accidentally drops or adds a document cannot pass.
 */
public class TestDocumentTagSearchNegation extends BaseJerseyTest {

    private static final String NO_SUCH_TAG = "zzznosuchtag";

    /**
     * A user of its own with three documents: one tagged Rechnung, one tagged Steuer, one untagged.
     * The listing of a fresh user holds exactly these three, which is what makes "the same set as
     * the unfiltered listing" a meaningful assertion.
     */
    private record Fixture(String token, String rechnungDocId, String steuerDocId, String untaggedDocId) {
    }

    private Fixture seed(String username) {
        clientUtil.createUser(username);
        String token = clientUtil.login(username);
        return new Fixture(token,
                createDocument(token, "Tag negation invoice", createTag(token, "Rechnung")),
                createDocument(token, "Tag negation tax", createTag(token, "Steuer")),
                createDocument(token, "Tag negation untagged", null));
    }

    /**
     * Excluding a tag that exists nowhere must exclude nothing.
     */
    @Test
    public void testNegatedUnmatchedTagExcludesNothing() {
        Fixture fixture = seed("tagneg_plain");
        Set<String> all = searchIds(fixture.token(), null);
        Assertions.assertEquals(3, all.size(), "the fixture must seed exactly three visible documents");

        Assertions.assertEquals(all, searchIds(fixture.token(), "!tag:" + NO_SUCH_TAG),
                "excluding a tag that does not exist must not drop any document");
    }

    /**
     * The same holds for a glob that matches no tag, including the all-asterisk terms that match no
     * tag by construction.
     */
    @Test
    public void testNegatedUnmatchedGlobExcludesNothing() {
        Fixture fixture = seed("tagneg_glob");
        Set<String> all = searchIds(fixture.token(), null);
        Assertions.assertEquals(3, all.size(), "the fixture must seed exactly three visible documents");

        Assertions.assertAll(
                () -> Assertions.assertEquals(all, searchIds(fixture.token(), "!tag:*" + NO_SUCH_TAG + "*"),
                        "an unmatched contains-glob must not drop any document"),
                () -> Assertions.assertEquals(all, searchIds(fixture.token(), "!tag:" + NO_SUCH_TAG + "*"),
                        "an unmatched prefix-glob must not drop any document"),
                () -> Assertions.assertEquals(all, searchIds(fixture.token(), "!tag:*"),
                        "a bare asterisk matches no tag, so negated it must not drop any document"),
                () -> Assertions.assertEquals(all, searchIds(fixture.token(), "!tag:**"),
                        "the same for a longer run of asterisks")
        );
    }

    /**
     * The inclusion polarity is unchanged: a term nothing carries still empties the result.
     */
    @Test
    public void testUnmatchedInclusionStillReturnsNothing() {
        Fixture fixture = seed("tagneg_incl");

        Assertions.assertAll(
                () -> Assertions.assertEquals(Set.of(), searchIds(fixture.token(), "tag:" + NO_SUCH_TAG)),
                () -> Assertions.assertEquals(Set.of(), searchIds(fixture.token(), "tag:*" + NO_SUCH_TAG + "*")),
                () -> Assertions.assertEquals(Set.of(), searchIds(fixture.token(), "tag:*"))
        );
    }

    /**
     * A negated term that DOES match still excludes what it matched -- plainly and through a glob.
     */
    @Test
    public void testNegatedMatchingTagStillExcludes() {
        Fixture fixture = seed("tagneg_match");

        Assertions.assertEquals(Set.of(fixture.steuerDocId(), fixture.untaggedDocId()),
                searchIds(fixture.token(), "!tag:Rechnung"),
                "the document carrying the excluded tag must be gone");
        // Both seeded tag names carry an "e", so the glob excludes both tagged documents.
        Assertions.assertEquals(Set.of(fixture.untaggedDocId()),
                searchIds(fixture.token(), "!tag:*e*"),
                "a negated glob must exclude every tag it matched");
    }

    /**
     * The legacy {@code search[nottag]} parameter resolves its value through the same code, so it
     * behaves identically on both sides of the fix.
     */
    @Test
    public void testLegacyNotTagParamBehavesLikeTheGrammar() {
        Fixture fixture = seed("tagneg_param");
        Set<String> all = notTagParamIds(fixture.token(), null);
        Assertions.assertEquals(3, all.size(), "the fixture must seed exactly three visible documents");

        Assertions.assertAll(
                () -> Assertions.assertEquals(all, notTagParamIds(fixture.token(), NO_SUCH_TAG),
                        "an unmatched nottag parameter must not drop any document"),
                () -> Assertions.assertEquals(all, notTagParamIds(fixture.token(), "*" + NO_SUCH_TAG + "*"),
                        "an unmatched nottag glob must not drop any document"),
                () -> Assertions.assertEquals(Set.of(fixture.steuerDocId(), fixture.untaggedDocId()),
                        notTagParamIds(fixture.token(), "Rechnung"),
                        "a matching nottag parameter must still exclude")
        );
    }

    // --- helpers ---

    private String createTag(String token, String name) {
        return target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form().param("name", name).param("color", "#ffff00")), JsonObject.class)
                .getString("id");
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

    /**
     * Document ids returned by the search grammar, or by the unfiltered listing when the query is
     * null.
     */
    private Set<String> searchIds(String token, String query) {
        WebTarget listTarget = target().path("/document/list");
        if (query != null) {
            listTarget = listTarget.queryParam("search", query);
        }
        return idsOf(listTarget.request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, token).get(JsonObject.class));
    }

    /**
     * Document ids returned by the legacy {@code search[nottag]} parameter, or by the unfiltered
     * listing when the value is null.
     */
    private Set<String> notTagParamIds(String token, String value) {
        WebTarget listTarget = target().path("/document/list");
        if (value != null) {
            listTarget = listTarget.queryParam("search[nottag]", value);
        }
        return idsOf(listTarget.request().cookie(TokenBasedSecurityFilter.COOKIE_NAME, token).get(JsonObject.class));
    }

    private static Set<String> idsOf(JsonObject listResponse) {
        JsonArray documents = listResponse.getJsonArray("documents");
        List<String> idList = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            idList.add(documents.getJsonObject(i).getString("id"));
        }
        return Set.copyOf(idList);
    }
}
