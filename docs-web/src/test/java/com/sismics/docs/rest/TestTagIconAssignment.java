package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

/**
 * Putting an icon ON a tag, over the REST API (#287).
 *
 * <p>The emoji half is where the validation lives, and the reason it is strict is that the value
 * is stored verbatim and drawn as text in every tag chip in the application. "One emoji" is
 * counted in extended grapheme clusters, so a ZWJ family counts as one; and it has to be an
 * emoji, not merely a character the Unicode `Emoji` property happens to be true for — that
 * property is true for the ASCII digits.</p>
 *
 * @author fmaass
 */
public class TestTagIconAssignment extends BaseJerseyTest {
    private String token;

    private String createTag(String name, String icon) {
        Form form = new Form().param("name", name).param("color", "#00ff00");
        if (icon != null) {
            form.param("icon", icon);
        }
        return target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(form), JsonObject.class)
                .getString("id");
    }

    private Response createTagResponse(String name, String icon) {
        return target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("name", name)
                        .param("color", "#00ff00")
                        .param("icon", icon)));
    }

    private JsonObject getTag(String tagId) {
        return target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
    }

    private void login(String username) {
        clientUtil.createUser(username);
        token = clientUtil.login(username);
    }

    /** An emoji survives create, list, get and the document the tag is on. */
    @Test
    public void testCreatingATagWithAnEmojiIcon() {
        login("tagicon_emoji_create");
        String tagId = createTag("Medal", "emoji:🎖️");

        Assertions.assertEquals("emoji:🎖️", getTag(tagId).getString("icon"));

        JsonArray tags = target().path("/tag/list").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class).getJsonArray("tags");
        Assertions.assertEquals("emoji:🎖️",
                tags.getJsonObject(0).getString("icon"),
                "the tag LIST carries the icon — it is what the tag tree and the picker read");

        // A document's embedded tag list carries it too: the document list is where a chip is
        // most often drawn, and it does not go back to /tag/list for each row.
        String documentId = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", "Tagged")
                        .param("language", "eng")
                        .param("tags", tagId)), JsonObject.class)
                .getString("id");
        JsonObject document = target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals("emoji:🎖️",
                document.getJsonArray("tags").getJsonObject(0).getString("icon"));

        JsonObject listed = target().path("/document/list")
                .queryParam("search", "Tagged").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals("emoji:🎖️",
                listed.getJsonArray("documents").getJsonObject(0)
                        .getJsonArray("tags").getJsonObject(0).getString("icon"));
    }

    /** A tag with no icon says so by OMITTING the key, exactly as it did before icons existed. */
    @Test
    public void testATagWithoutAnIconOmitsTheKey() {
        login("tagicon_absent");
        String tagId = createTag("Plain", null);

        Assertions.assertFalse(getTag(tagId).containsKey("icon"));
        JsonArray tags = target().path("/tag/list").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class).getJsonArray("tags");
        Assertions.assertFalse(tags.getJsonObject(0).containsKey("icon"));
    }

    /** An icon can be added to an existing tag, changed, and taken off again. */
    @Test
    public void testUpdatingATagsIcon() {
        login("tagicon_update_rest");
        String tagId = createTag("Changing", null);

        target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("name", "Changing").param("icon", "emoji:⭐")),
                        JsonObject.class);
        Assertions.assertEquals("emoji:⭐", getTag(tagId).getString("icon"));

        target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("name", "Changing").param("icon", "")),
                        JsonObject.class);
        Assertions.assertFalse(getTag(tagId).containsKey("icon"),
                "an empty icon parameter takes the icon off the tag");
    }

    /** Two emoji are not one emoji. */
    @Test
    public void testTwoEmojiAreRejected() {
        login("tagicon_two");
        Response response = createTagResponse("TwoEmoji", "emoji:🎖️👍");
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("ValidationError",
                response.readEntity(JsonObject.class).getString("type"));
    }

    /** A letter is not an emoji. */
    @Test
    public void testALetterIsRejected() {
        login("tagicon_letter");
        Assertions.assertEquals(Status.BAD_REQUEST,
                Status.fromStatusCode(createTagResponse("Letter", "emoji:a").getStatus()));
    }

    /**
     * A bare ASCII digit is rejected. This is the case a naive check gets wrong: Unicode says the
     * `Emoji` property is TRUE for `0`-`9`, `#` and `*`, so "every code point is an emoji" would
     * have accepted `1` as a tag icon.
     */
    @Test
    public void testABareDigitIsRejected() {
        login("tagicon_digit");
        Assertions.assertEquals(Status.BAD_REQUEST,
                Status.fromStatusCode(createTagResponse("Digit", "emoji:1").getStatus()));
    }

    /** ...but the KEYCAP built from that same digit is a real emoji and is accepted. */
    @Test
    public void testAKeycapIsAccepted() {
        login("tagicon_keycap");
        String tagId = createTag("Keycap", "emoji:1️⃣");
        Assertions.assertEquals("emoji:1️⃣", getTag(tagId).getString("icon"));
    }

    /** A ZWJ family is ONE emoji, even though it is eleven code units. */
    @Test
    public void testAZwjSequenceIsOneEmoji() {
        login("tagicon_zwj");
        String family = "👨‍👩‍👧‍👦";
        String tagId = createTag("Family", "emoji:" + family);
        Assertions.assertEquals("emoji:" + family, getTag(tagId).getString("icon"));
    }

    /** A flag is two regional indicators and one emoji. */
    @Test
    public void testAFlagIsAccepted() {
        login("tagicon_flag");
        String tagId = createTag("Flag", "emoji:🇨🇭");
        Assertions.assertEquals("emoji:🇨🇭", getTag(tagId).getString("icon"));
    }

    /** An icon reference into the set must name an icon that exists. */
    @Test
    public void testAnUnknownSetIconIsRejected() {
        login("tagicon_unknown_set");
        Response response = createTagResponse("Ghost", "set:8b1e4f22-0000-4000-8000-0000000000ff");
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("IconNotFound",
                response.readEntity(JsonObject.class).getString("type"));
    }

    /** A scheme the server does not know is refused rather than stored for a later reader to guess at. */
    @Test
    public void testAnUnknownIconSchemeIsRejected() {
        login("tagicon_scheme");
        Response response = createTagResponse("Fa", "fontawesome:star");
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("ValidationError",
                response.readEntity(JsonObject.class).getString("type"));
    }

    /** An emoji long enough to overflow the column is refused, not truncated. */
    @Test
    public void testAnOverlongEmojiSequenceIsRejected() {
        login("tagicon_overlong");
        StringBuilder chain = new StringBuilder("👨");
        for (int i = 0; i < 12; i++) {
            chain.append("‍👨");
        }
        Assertions.assertEquals(Status.BAD_REQUEST,
                Status.fromStatusCode(createTagResponse("Chain", "emoji:" + chain).getStatus()));
    }
}
