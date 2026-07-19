package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The inbox auto-import tag must be clearable: re-saving the inbox configuration with an empty tag clears
 * the stored tag. Previously an empty tag was skipped, so once a tag was set it could never be removed (#141).
 */
public class TestInboxTagClear extends BaseJerseyTest {

    @Test
    public void inboxTagCanBeCleared() {
        String admin = clientUtil.login("admin", "admin", false);

        // Configure the inbox with a tag, then read it back.
        postInbox(admin, "some-tag-id");
        JsonObject withTag = target().path("/app/config_inbox").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, admin).get(JsonObject.class);
        Assertions.assertEquals("some-tag-id", withTag.getString("tag"));

        // Re-save with an empty tag: it must now be cleared, not retained.
        postInbox(admin, "");
        JsonObject cleared = target().path("/app/config_inbox").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, admin).get(JsonObject.class);
        Assertions.assertEquals("", cleared.getString("tag"),
                "an empty tag submission must clear the stored inbox tag");
    }

    private void postInbox(String adminToken, String tag) {
        target().path("/app/config_inbox").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("enabled", "false")
                        .param("starttls", "false")
                        .param("autoTagsEnabled", "false")
                        .param("deleteImported", "false")
                        .param("hostname", "localhost")
                        .param("port", "143")
                        .param("username", "u")
                        .param("password", "p")
                        .param("folder", "INBOX")
                        .param("tag", tag)));
    }
}
