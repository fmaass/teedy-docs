package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;

/**
 * Test the tag match rule resource.
 *
 * @author fmaass
 */
public class TestTagMatchRuleResource extends BaseJerseyTest {
    @Test
    public void testTagMatchRuleCrud() {
        String adminToken = adminToken();

        // Create a tag to associate rules with
        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "AutoTag1")
                        .param("color", "#ff0000")), JsonObject.class);
        String tagId = json.getString("id");
        Assertions.assertNotNull(tagId);

        // List rules (should be empty)
        json = target().path("/tagmatchrule").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonArray rules = json.getJsonArray("rules");
        Assertions.assertEquals(0, rules.size());

        // Create a rule
        json = target().path("/tagmatchrule").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("tag_id", tagId)
                        .param("rule_type", "TITLE_REGEX")
                        .param("pattern", "(?i)invoice.*\\d{4}")
                        .param("order", "1")
                        .param("enabled", "true")), JsonObject.class);
        String ruleId = json.getString("id");
        Assertions.assertNotNull(ruleId);

        // List rules (should have one)
        json = target().path("/tagmatchrule").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        rules = json.getJsonArray("rules");
        Assertions.assertEquals(1, rules.size());
        JsonObject rule = rules.getJsonObject(0);
        Assertions.assertEquals(ruleId, rule.getString("id"));
        Assertions.assertEquals(tagId, rule.getString("tag_id"));
        Assertions.assertEquals("TITLE_REGEX", rule.getString("rule_type"));
        Assertions.assertEquals("(?i)invoice.*\\d{4}", rule.getString("pattern"));
        Assertions.assertEquals(1, rule.getInt("order"));
        Assertions.assertTrue(rule.getBoolean("enabled"));

        // Update the rule
        target().path("/tagmatchrule/" + ruleId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("pattern", "receipt.*")
                        .param("enabled", "false")), JsonObject.class);

        // Verify update
        json = target().path("/tagmatchrule").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        rule = json.getJsonArray("rules").getJsonObject(0);
        Assertions.assertEquals("receipt.*", rule.getString("pattern"));
        Assertions.assertFalse(rule.getBoolean("enabled"));

        // Test regex endpoint
        json = target().path("/tagmatchrule/test").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("pattern", "invoice.*\\d{4}")
                        .param("text", "This is an invoice for 2024")), JsonObject.class);
        Assertions.assertTrue(json.getBoolean("matches"));

        json = target().path("/tagmatchrule/test").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("pattern", "receipt")
                        .param("text", "This is an invoice")), JsonObject.class);
        Assertions.assertFalse(json.getBoolean("matches"));

        // Test invalid regex
        Response response = target().path("/tagmatchrule").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("tag_id", tagId)
                        .param("rule_type", "TITLE_REGEX")
                        .param("pattern", "[invalid")));
        Assertions.assertEquals(400, response.getStatus());

        // Test invalid rule type
        response = target().path("/tagmatchrule").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("tag_id", tagId)
                        .param("rule_type", "INVALID_TYPE")
                        .param("pattern", "test")));
        Assertions.assertEquals(400, response.getStatus());

        // Delete the rule
        target().path("/tagmatchrule/" + ruleId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);

        // Verify deletion
        json = target().path("/tagmatchrule").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals(0, json.getJsonArray("rules").size());
    }
}
