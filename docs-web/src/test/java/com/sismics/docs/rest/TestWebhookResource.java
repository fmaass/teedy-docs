package com.sismics.docs.rest;

import com.sismics.docs.core.util.WebhookUtil;
import com.sismics.docs.rest.resource.ThirdPartyWebhookResource;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import java.util.Date;


/**
 * Test the webhook resource.
 * 
 * @author bgamard
 */
public class TestWebhookResource extends BaseJerseyTest {
    /**
     * The test targets a loopback webhook endpoint, which the SSRF guard blocks by
     * default; opt in to private targets for the duration of the test.
     */
    @BeforeEach
    public void allowPrivateWebhooks() {
        System.setProperty(WebhookUtil.ALLOW_PRIVATE_PROPERTY, "true");
    }

    @AfterEach
    public void resetPrivateWebhooks() {
        System.clearProperty(WebhookUtil.ALLOW_PRIVATE_PROPERTY);
    }

    /**
     * Test the webhook resource.
     */
    @Test
    public void testWebhookResource() {
        // Login admin
        String adminToken = adminToken();

        // Login webhook1
        clientUtil.createUser("webhook1");
        String webhook1Token = clientUtil.login("webhook1");
        
        // Get all webhooks
        JsonObject json = target().path("/webhook")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonArray webhooks = json.getJsonArray("webhooks");
        Assertions.assertEquals(0, webhooks.size());

        // Create a webhook
        target().path("/webhook").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("event", "DOCUMENT_CREATED")
                        .param("url", "http://localhost:" + getPort() + "/docs/thirdpartywebhook")), JsonObject.class);

        // Create a document
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, webhook1Token)
                .put(Entity.form(new Form()
                        .param("title", "Webhook document 1")
                        .param("language", "eng")
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class);
        String document1Id = json.getString("id");

        // Check the webhook payload
        JsonObject payload = ThirdPartyWebhookResource.getLastPayload();
        Assertions.assertEquals("DOCUMENT_CREATED", payload.getString("event"));
        Assertions.assertEquals(document1Id, payload.getString("id"));

        // Get all webhooks
        json = target().path("/webhook")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        webhooks = json.getJsonArray("webhooks");
        Assertions.assertEquals(1, webhooks.size());
        JsonObject webhook = webhooks.getJsonObject(0);
        String webhookId = webhook.getString("id");
        Assertions.assertEquals("DOCUMENT_CREATED", webhook.getString("event"));
        Assertions.assertEquals("http://localhost:" + getPort() + "/docs/thirdpartywebhook", webhook.getString("url"));
        Assertions.assertNotNull(webhook.getJsonNumber("create_date"));

        // Delete a webhook
        target().path("/webhook/" + webhookId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);

        // Get all webhooks
        json = target().path("/webhook")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        webhooks = json.getJsonArray("webhooks");
        Assertions.assertEquals(0, webhooks.size());

        // Deletes webhook1
        target().path("/user/webhook1").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }
}
