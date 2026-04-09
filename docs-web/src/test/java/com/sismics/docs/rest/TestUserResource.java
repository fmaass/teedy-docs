package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import com.sismics.util.totp.GoogleAuthenticator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exhaustive test of the user resource.
 * 
 * @author jtremeaux
 */
public class TestUserResource extends BaseJerseyTest {
    /**
     * Test the user resource.
     */
    @Test
    public void testUserResource() {
        // Check anonymous user information
        JsonObject json = target().path("/user").request()
                .acceptLanguage(Locale.US)
                .get(JsonObject.class);
        Assertions.assertTrue(json.getBoolean("is_default_password"));
        
        // Create alice user
        clientUtil.createUser("alice");

        // Login admin
        String adminToken = adminToken();
        
        // List all users
        json = target().path("/user/list")
                .queryParam("sort_column", 2)
                .queryParam("asc", false)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonArray users = json.getJsonArray("users");
        Assertions.assertTrue(users.size() > 0);
        JsonObject user = users.getJsonObject(0);
        Assertions.assertNotNull(user.getString("id"));
        Assertions.assertNotNull(user.getString("username"));
        Assertions.assertNotNull(user.getString("email"));
        Assertions.assertNotNull(user.getJsonNumber("storage_quota"));
        Assertions.assertNotNull(user.getJsonNumber("storage_current"));
        Assertions.assertNotNull(user.getJsonNumber("create_date"));
        Assertions.assertFalse(user.getBoolean("totp_enabled"));
        Assertions.assertFalse(user.getBoolean("disabled"));

        // Create a user KO (login length validation)
        Response response = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("username", "   bb  ")
                        .param("email", "bob@docs.com")
                        .param("password", "Test1234")
                        .param("storage_quota", "10")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        json = response.readEntity(JsonObject.class);
        Assertions.assertEquals("ValidationError", json.getString("type"));
        Assertions.assertTrue(json.getString("message").contains("more than 3"), json.getString("message"));

        // Create a user KO (login format validation)
        response = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("username", "bob/")
                        .param("email", "bob@docs.com")
                        .param("password", "Test1234")
                        .param("storage_quota", "10")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        json = response.readEntity(JsonObject.class);
        Assertions.assertEquals("ValidationError", json.getString("type"));
        Assertions.assertTrue(json.getString("message").contains("alphanumeric"), json.getString("message"));
        
        // Create a user KO (invalid quota)
        response = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("username", "bob")
                        .param("email", "bob@docs.com")
                        .param("password", "Test1234")
                        .param("storage_quota", "nope")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        json = response.readEntity(JsonObject.class);
        Assertions.assertEquals("ValidationError", json.getString("type"));
        Assertions.assertTrue(json.getString("message").contains("number"), json.getString("message"));

        // Create a user KO (email format validation)
        response = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("username", "bob")
                        .param("email", "bobdocs.com")
                        .param("password", "Test1234")
                        .param("storage_quota", "10")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        json = response.readEntity(JsonObject.class);
        Assertions.assertEquals("ValidationError", json.getString("type"));
        Assertions.assertTrue(json.getString("message").contains("must be an email"), json.getString("message"));

        // Create a user bob OK
        Form form = new Form()
                .param("username", " bob ")
                .param("email", " bob@docs.com ")
                .param("password", " Test1234 ")
                .param("storage_quota", "10");
        target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(form), JsonObject.class);

        // Create a user bob KO : duplicate username
        response = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(form));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        json = response.readEntity(JsonObject.class);
        Assertions.assertEquals("AlreadyExistingUsername", json.getString("type"));

        // Login alice with extra whitespaces
        response = target().path("/user/login").request()
                .post(Entity.form(new Form()
                        .param("username", " alice ")
                        .param("password", " Test1234 ")));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        String aliceAuthToken = clientUtil.getAuthenticationCookie(response);

        // Login user bob twice
        String bobToken = clientUtil.login("bob");
        String bobToken2 = clientUtil.login("bob");

        // List sessions
        response = target().path("/user/session").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, bobToken)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        json = response.readEntity(JsonObject.class);
        Assertions.assertTrue(json.getJsonArray("sessions").size() > 0);
        JsonObject session = json.getJsonArray("sessions").getJsonObject(0);
        Assertions.assertEquals("127.0.0.1", session.getString("ip"));
        Assertions.assertTrue(session.getString("user_agent").startsWith("Jersey"));
        
        // Delete all sessions
        response = target().path("/user/session").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, bobToken)
                .delete();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));

        // Check bob user information with token 2 (just deleted)
        response = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, bobToken2)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        json = response.readEntity(JsonObject.class);
        Assertions.assertTrue(json.getBoolean("anonymous"));
        
        // Check alice user information
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, aliceAuthToken)
                .get(JsonObject.class);
        Assertions.assertEquals("alice@docs.com", json.getString("email"));
        Assertions.assertFalse(json.getBoolean("is_default_password"));
        Assertions.assertEquals(0L, json.getJsonNumber("storage_current").longValue());
        Assertions.assertEquals(1000000L, json.getJsonNumber("storage_quota").longValue());
        
        // Check bob user information
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, bobToken)
                .get(JsonObject.class);
        Assertions.assertTrue(json.getBoolean("onboarding"));
        Assertions.assertEquals("bob@docs.com", json.getString("email"));

        // Pass onboarding
        target().path("/user/onboarded").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, bobToken)
                .post(Entity.form(new Form()), JsonObject.class);

        // Check bob user information
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, bobToken)
                .get(JsonObject.class);
        Assertions.assertFalse(json.getBoolean("onboarding"));

        // Test login KO (user not found)
        response = target().path("/user/login").request()
                .post(Entity.form(new Form()
                        .param("username", "intruder")
                        .param("password", "Test1234")));
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()));

        // Test login KO (wrong password)
        response = target().path("/user/login").request()
                .post(Entity.form(new Form()
                        .param("username", "alice")
                        .param("password", "error")));
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()));

        // User alice updates her information + changes her email
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, aliceAuthToken)
                .post(Entity.form(new Form()
                        .param("email", " alice2@docs.com ")), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
        
        // Check the update
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, aliceAuthToken)
                .get(JsonObject.class);
        Assertions.assertEquals("alice2@docs.com", json.getString("email"));
        
        // Delete user alice
        target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, aliceAuthToken)
                .delete();
        
        // Check the deletion
        response = target().path("/user/login").request()
                .post(Entity.form(new Form()
                        .param("username", "alice")
                        .param("password", "Test1234")));
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()));

        // Delete user bob
        target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, bobToken)
                .delete();
    }

    /**
     * Test the user resource admin functions.
     */
    @Test
    public void testUserResourceAdmin() {
        // Create admin_user1 user
        clientUtil.createUser("admin_user1");

        // Login admin
        String adminToken = adminToken();

        // Check admin information
        JsonObject json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertTrue(json.getBoolean("is_default_password"));
        Assertions.assertEquals(0L, json.getJsonNumber("storage_current").longValue());
        Assertions.assertEquals(10000000000L, json.getJsonNumber("storage_quota").longValue());

        // User admin updates his information
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("email", "newadminemail@docs.com")), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // Check admin information update
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals("newadminemail@docs.com", json.getString("email"));

        // User admin update admin_user1 information
        json = target().path("/user/admin_user1").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("email", " alice2@docs.com ")), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
        
        // User admin deletes himself: forbidden
        Response response = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        json = response.readEntity(JsonObject.class);
        Assertions.assertEquals("ForbiddenError", json.getString("type"));

        // User admin disable admin_user1
        json = target().path("/user/admin_user1").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("disabled", "true")), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // User admin_user1 tries to authenticate
        response = target().path("/user/login").request()
                .post(Entity.form(new Form()
                        .param("username", "admin_user1")
                        .param("password", "Test1234")
                        .param("remember", "false")));
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus());

        // User admin enable admin_user1
        json = target().path("/user/admin_user1").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("disabled", "false")), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // User admin_user1 tries to authenticate
        response = target().path("/user/login").request()
                .post(Entity.form(new Form()
                        .param("username", "admin_user1")
                        .param("password", "Test1234")
                        .param("remember", "false")));
        Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus());

        // User admin deletes user admin_user1
        json = target().path("/user/admin_user1").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
        
        // User admin deletes user admin_user1 : KO (user doesn't exist)
        response = target().path("/user/admin_user1").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
        json = response.readEntity(JsonObject.class);
        Assertions.assertEquals("UserNotFound", json.getString("type"));
    }
    
    @Test
    public void testTotp() {
        // Login admin
        String adminToken = adminToken();

        // Create totp1 user
        clientUtil.createUser("totp1");
        String totp1Token = clientUtil.login("totp1");
        
        // Check TOTP enablement
        JsonObject json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, totp1Token)
                .get(JsonObject.class);
        Assertions.assertFalse(json.getBoolean("totp_enabled"));
        
        // Enable TOTP for totp1
        json = target().path("/user/enable_totp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, totp1Token)
                .post(Entity.form(new Form()), JsonObject.class);
        String secret = json.getString("secret");
        Assertions.assertNotNull(secret);
        
        // Try to login with totp1 without a validation code
        Response response = target().path("/user/login").request()
                .post(Entity.form(new Form()
                        .param("username", "totp1")
                        .param("password", "Test1234")
                        .param("remember", "false")));
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        json = response.readEntity(JsonObject.class);
        Assertions.assertEquals("ValidationCodeRequired", json.getString("type"));
        
        // Generate a OTP
        GoogleAuthenticator googleAuthenticator = new GoogleAuthenticator();
        int validationCode = googleAuthenticator.calculateCode(secret, new Date().getTime() / 30000);
        
        // Login with totp1 with a validation code
        target().path("/user/login").request()
                .post(Entity.form(new Form()
                        .param("username", "totp1")
                        .param("password", "Test1234")
                        .param("code", Integer.toString(validationCode))
                        .param("remember", "false")), JsonObject.class);
        
        // Check TOTP enablement
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, totp1Token)
                .get(JsonObject.class);
        Assertions.assertTrue(json.getBoolean("totp_enabled"));

        // Generate a OTP
        validationCode = googleAuthenticator.calculateCode(secret, new Date().getTime() / 30000);

        // Test a validation code
        target().path("/user/test_totp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, totp1Token)
                .post(Entity.form(new Form()
                        .param("code", Integer.toString(validationCode))), JsonObject.class);

        // Disable TOTP for totp1
        target().path("/user/disable_totp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, totp1Token)
                .post(Entity.form(new Form()
                        .param("password", "Test1234")), JsonObject.class);

        // Enable TOTP for totp1
        target().path("/user/enable_totp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, totp1Token)
                .post(Entity.form(new Form()), JsonObject.class);

        // Disable TOTP for totp1 with admin
        target().path("/user/totp1/disable_totp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()), JsonObject.class);

        // Login with totp1 without a validation code
        target().path("/user/login").request()
                .post(Entity.form(new Form()
                        .param("username", "totp1")
                        .param("password", "Test1234")
                        .param("remember", "false")), JsonObject.class);
        
        // Check TOTP enablement
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, totp1Token)
                .get(JsonObject.class);
        Assertions.assertFalse(json.getBoolean("totp_enabled"));

        // Delete totp1
        response = target().path("/user/totp1").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        Assertions.assertEquals(Response.Status.OK, Response.Status.fromStatusCode(response.getStatus()));
    }

    @Test
    public void testResetPassword() throws Exception {
        // Login admin
        String adminToken = adminToken();

        // Change SMTP configuration to target Wiser
        target().path("/app/config_smtp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("hostname", "localhost")
                        .param("port", "2500")
                        .param("from", "contact@sismicsdocs.com")
                ), JsonObject.class);

        // Create absent_minded who lost his password
        clientUtil.createUser("absent_minded");

        // User no_such_user try to recovery its password: silently do nothing to avoid leaking users
        JsonObject json = target().path("/user/password_lost").request()
                .post(Entity.form(new Form()
                        .param("username", "no_such_user")), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // User absent_minded try to recovery its password: OK
        json = target().path("/user/password_lost").request()
                .post(Entity.form(new Form()
                        .param("username", "absent_minded")), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
        String emailBody = popEmail();
        Assertions.assertNotNull(emailBody, "No email to consume");
        Assertions.assertTrue(emailBody.contains("Please reset your password"));
        Pattern keyPattern = Pattern.compile("/passwordreset/(.+?)\"");
        Matcher keyMatcher = keyPattern.matcher(emailBody);
        Assertions.assertTrue(keyMatcher.find(), "Token not found");
        String key = keyMatcher.group(1).replaceAll("=", "");

        // User absent_minded resets its password: invalid key
        Response response = target().path("/user/password_reset").request()
                .post(Entity.form(new Form()
                        .param("key", "no_such_key")
                        .param("password", "87654321")));
        Assertions.assertEquals(Response.Status.BAD_REQUEST, Response.Status.fromStatusCode(response.getStatus()));
        json = response.readEntity(JsonObject.class);
        Assertions.assertEquals("KeyNotFound", json.getString("type"));

        // User absent_minded resets its password: password invalid
        response = target().path("/user/password_reset").request()
                .post(Entity.form(new Form()
                        .param("key", key)
                        .param("password", " 1 ")));
        Assertions.assertEquals(Response.Status.BAD_REQUEST, Response.Status.fromStatusCode(response.getStatus()));
        json = response.readEntity(JsonObject.class);
        Assertions.assertEquals("ValidationError", json.getString("type"));
        Assertions.assertTrue(json.getString("message").contains("password"), json.getString("message"));

        // User absent_minded resets its password: OK
        json = target().path("/user/password_reset").request()
                .post(Entity.form(new Form()
                        .param("key", key)
                        .param("password", "Reset1Pass")), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // User absent_minded resets its password: expired key
        response = target().path("/user/password_reset").request()
                .post(Entity.form(new Form()
                        .param("key", key)
                        .param("password", "Reset1Pass")));
        Assertions.assertEquals(Response.Status.BAD_REQUEST, Response.Status.fromStatusCode(response.getStatus()));
        json = response.readEntity(JsonObject.class);
        Assertions.assertEquals("KeyNotFound", json.getString("type"));

        // Delete absent_minded
        response = target().path("/user/absent_minded").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        Assertions.assertEquals(Response.Status.OK, Response.Status.fromStatusCode(response.getStatus()));
    }
}
