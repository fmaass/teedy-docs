package com.sismics.docs.rest;

import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.constant.RouteStatus;
import com.sismics.docs.core.constant.RouteStepTransition;
import com.sismics.docs.core.constant.RouteStepType;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.RouteDao;
import com.sismics.docs.core.dao.RouteModelDao;
import com.sismics.docs.core.dao.RouteStepDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.Route;
import com.sismics.docs.core.model.jpa.RouteModel;
import com.sismics.docs.core.model.jpa.RouteStep;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.HeaderBasedSecurityFilter;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import com.sismics.util.jpa.EMF;
import com.sismics.util.totp.GoogleAuthenticator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
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

        // The admin flag must map per-user across the SAME response: true for the
        // built-in admin (admin role), false for a normal user (default user role).
        Boolean adminUserAdminFlag = null;
        Boolean aliceUserAdminFlag = null;
        for (int i = 0; i < users.size(); i++) {
            JsonObject row = users.getJsonObject(i);
            if ("admin".equals(row.getString("username"))) {
                adminUserAdminFlag = row.getBoolean("admin");
            } else if ("alice".equals(row.getString("username"))) {
                aliceUserAdminFlag = row.getBoolean("admin");
            }
        }
        Assertions.assertNotNull(adminUserAdminFlag, "admin user must be present in /user/list");
        Assertions.assertNotNull(aliceUserAdminFlag, "alice user must be present in /user/list");
        Assertions.assertTrue(adminUserAdminFlag, "admin user must have admin=true");
        Assertions.assertFalse(aliceUserAdminFlag, "normal user alice must have admin=false");

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
        response = target().path("/user/session")
                .queryParam("reassign_to_username", "admin").request()
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
        json = target().path("/user/admin_user1")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
        
        // User admin deletes user admin_user1 : KO (user doesn't exist)
        response = target().path("/user/admin_user1")
                .queryParam("reassign_to_username", "admin").request()
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
        response = target().path("/user/totp1")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        Assertions.assertEquals(Response.Status.OK, Response.Status.fromStatusCode(response.getStatus()));
    }

    /**
     * Guards against OTP brute-force: repeated WRONG TOTP codes must count toward the
     * login rate limiter (lockout), exactly like wrong passwords, and a CORRECT code
     * before the threshold must clear the counter. Without recording wrong-code
     * failures the 2FA second factor could be guessed unboundedly.
     */
    @Test
    public void testTotpLoginBruteForceLockout() {
        int maxAttempts = Integer.parseInt(
                System.getenv().getOrDefault("DOCS_LOGIN_MAX_ATTEMPTS", "5"));

        // Isolate from the shared loopback-IP limiter state of other tests.
        com.sismics.docs.rest.util.LoginThrottleStore.getInstance().reset();
        try {
            // Login admin, create + enable TOTP for a fresh user
            String adminToken = adminToken();
            clientUtil.createUser("totplock");
            String totplockToken = clientUtil.login("totplock");
            JsonObject json = target().path("/user/enable_totp").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, totplockToken)
                    .post(Entity.form(new Form()), JsonObject.class);
            String secret = json.getString("secret");
            Assertions.assertNotNull(secret);

            GoogleAuthenticator googleAuthenticator = new GoogleAuthenticator();

            // A CORRECT code below the threshold logs in AND clears the counter. We
            // first burn (maxAttempts - 1) wrong codes, then a correct one, and prove
            // the counter reset by immediately doing another full round of wrong codes.
            for (int i = 0; i < maxAttempts - 1; i++) {
                Response wrong = target().path("/user/login").request()
                        .post(Entity.form(new Form()
                                .param("username", "totplock")
                                .param("password", "Test1234")
                                .param("code", "000000")
                                .param("remember", "false")));
                Assertions.assertEquals(Response.Status.FORBIDDEN.getStatusCode(), wrong.getStatus());
            }
            // Correct code clears the counter (login succeeds, not rate-limited).
            int good = googleAuthenticator.calculateCode(secret, new Date().getTime() / 30000);
            Response ok = target().path("/user/login").request()
                    .post(Entity.form(new Form()
                            .param("username", "totplock")
                            .param("password", "Test1234")
                            .param("code", Integer.toString(good))
                            .param("remember", "false")));
            Assertions.assertEquals(Response.Status.OK.getStatusCode(), ok.getStatus());

            // Now exhaust the threshold with wrong codes: after maxAttempts failures the
            // account is locked out (429), proving wrong TOTP codes count toward lockout.
            for (int i = 0; i < maxAttempts; i++) {
                Response wrong = target().path("/user/login").request()
                        .post(Entity.form(new Form()
                                .param("username", "totplock")
                                .param("password", "Test1234")
                                .param("code", "000000")
                                .param("remember", "false")));
                Assertions.assertEquals(Response.Status.FORBIDDEN.getStatusCode(), wrong.getStatus());
            }
            // Even a CORRECT code is now refused with 429 — the limiter is engaged.
            int goodButLocked = googleAuthenticator.calculateCode(secret, new Date().getTime() / 30000);
            Response locked = target().path("/user/login").request()
                    .post(Entity.form(new Form()
                            .param("username", "totplock")
                            .param("password", "Test1234")
                            .param("code", Integer.toString(goodButLocked))
                            .param("remember", "false")));
            Assertions.assertEquals(429, locked.getStatus());
            JsonObject lockedJson = locked.readEntity(JsonObject.class);
            Assertions.assertEquals("RateLimited", lockedJson.getString("type"));
        } finally {
            // Do not leak the lockout to other tests sharing the loopback IP key.
            com.sismics.docs.rest.util.LoginThrottleStore.getInstance().reset();
        }
    }

    @Test
    public void testResetPassword() throws Exception {
        // Login admin
        String adminToken = adminToken();

        // Change SMTP configuration to target the embedded GreenMail server
        target().path("/app/config_smtp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("hostname", "localhost")
                        .param("port", Integer.toString(getSmtpPort()))
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
        response = target().path("/user/absent_minded")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        Assertions.assertEquals(Response.Status.OK, Response.Status.fromStatusCode(response.getStatus()));
    }

    /**
     * Test that an administrator can reset another user's password directly, without any
     * email/SMTP round-trip (the recovery path for a lost local password when mail is
     * unconfigured — see the account recovery matrix in docs/). Proves the reset takes
     * effect: the old password stops working and the new one logs in.
     */
    @Test
    public void testAdminPasswordResetWithoutSmtp() {
        // A regular user with the default test password
        clientUtil.createUser("recovery1");

        // The user can log in with the original password
        Response response = target().path("/user/login").request()
                .post(Entity.form(new Form()
                        .param("username", "recovery1")
                        .param("password", "Test1234")
                        .param("remember", "false")));
        Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus());

        // An admin resets the user's password directly — no password_lost/email involved
        String adminToken = adminToken();
        JsonObject json = target().path("/user/recovery1").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("password", "Recovered5678")), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // The old password no longer works
        response = target().path("/user/login").request()
                .post(Entity.form(new Form()
                        .param("username", "recovery1")
                        .param("password", "Test1234")
                        .param("remember", "false")));
        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus());

        // The new password logs the user in
        response = target().path("/user/login").request()
                .post(Entity.form(new Form()
                        .param("username", "recovery1")
                        .param("password", "Recovered5678")
                        .param("remember", "false")));
        Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus());
    }

    /**
     * Admin-deleting a user referenced by a route model succeeds and returns a warning payload
     * naming the affected model. The model keeps its blob but becomes unstartable because its
     * target no longer resolves (asserted at the DAO/SecurityUtil level: the branch has no route
     * start REST endpoint yet, it belongs to the parallel workflow-REST phase).
     */
    @Test
    public void testDeleteUserReferencedByRouteModel() {
        String adminToken = adminToken();
        clientUtil.createUser("wfmodeluser");

        // Seed a route model whose step targets wfmodeluser (name-resolved into the index).
        String[] userId = new String[1];
        String modelId = inTx(() -> {
            User user = new UserDao().getActiveByUsername("wfmodeluser");
            userId[0] = user.getId();
            String steps = "[{\"type\":\"VALIDATE\",\"target\":{\"name\":\"wfmodeluser\",\"type\":\"USER\"},\"name\":\"Step 1\"}]";
            return new RouteModelDao().create(new RouteModel().setName("Model targeting wfmodeluser").setSteps(steps), "admin");
        });

        // The model references the user before deletion.
        Boolean referencedBefore = inTx(() ->
                new RouteModelDao().findModelsReferencingTarget(userId[0]).contains(modelId));
        Assertions.assertTrue(referencedBefore);

        // Delete the user: 200 + warning payload naming the affected model.
        JsonObject json = target().path("/user/wfmodeluser")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
        JsonArray affected = json.getJsonArray("route_models_affected");
        Assertions.assertNotNull(affected, "Response must warn about the affected route model");
        Assertions.assertTrue(containsString(affected, "Model targeting wfmodeluser"),
                "Warning must name the affected route model");

        // Unstartability: the model's step target no longer resolves to a live user.
        String resolved = inTx(() ->
                com.sismics.docs.core.util.SecurityUtil.getTargetIdFromName("wfmodeluser",
                        com.sismics.docs.core.constant.AclTargetType.USER));
        Assertions.assertNull(resolved, "Deleted user no longer resolves; the model is unstartable");
    }

    /**
     * Admin-deleting a user targeted by an OPEN route step cancels the route (status CANCELLED),
     * closes the open step with a system comment, removes the transient ROUTING ACL on the document,
     * and preserves step history.
     */
    @Test
    public void testDeleteUserTargetedByOpenStep() {
        String adminToken = adminToken();
        clientUtil.createUser("wfstepuser");

        String[] routeId = new String[1];
        String[] stepId = new String[1];
        String[] documentId = new String[1];
        String[] aclId = new String[1];
        inTx(() -> {
            User user = new UserDao().getActiveByUsername("wfstepuser");
            User admin = new UserDao().getActiveByUsername("admin");

            Document document = new Document();
            document.setUserId(admin.getId());
            document.setLanguage("eng");
            document.setTitle("Doc with open route");
            document.setCreateDate(new Date());
            documentId[0] = new DocumentDao().create(document, admin.getId());

            RouteDao routeDao = new RouteDao();
            routeId[0] = routeDao.create(new Route().setDocumentId(documentId[0]).setName("Route"), admin.getId());

            RouteStep step = new RouteStep()
                    .setRouteId(routeId[0])
                    .setName("Open step")
                    .setType(RouteStepType.VALIDATE)
                    .setTargetId(user.getId())
                    .setOrder(0);
            stepId[0] = new RouteStepDao().create(step);

            // Seed the transient ROUTING READ ACL that route-start would have granted the target.
            aclId[0] = insertRoutingAcl(documentId[0], user.getId());
            return null;
        });

        // Delete the user.
        JsonObject json = target().path("/user/wfstepuser")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // The route is CANCELLED, the open step is closed with the system comment (history intact),
        // and the ROUTING ACL is gone.
        inTx(() -> {
            Route route = ThreadLocalContext.get().getEntityManager().find(Route.class, routeId[0]);
            Assertions.assertEquals(RouteStatus.CANCELLED, route.getStatus());
            Assertions.assertNotNull(route.getEndDate());

            RouteStep step = ThreadLocalContext.get().getEntityManager().find(RouteStep.class, stepId[0]);
            Assertions.assertNotNull(step, "Step history must survive");
            Assertions.assertNull(step.getDeleteDate(), "Step is closed, not deleted");
            Assertions.assertNotNull(step.getEndDate(), "Open step must be closed");
            Assertions.assertEquals("Cancelled: step target deleted", step.getComment());
            Assertions.assertNull(step.getTransition(),
                    "System-ended step must have a NULL transition, not a user-action value");

            long routingAclCount = ((Number) ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("select count(*) from T_ACL where ACL_SOURCEID_C = :src and ACL_TYPE_C = 'ROUTING' and ACL_DELETEDATE_D is null")
                    .setParameter("src", documentId[0])
                    .getSingleResult()).longValue();
            Assertions.assertEquals(0, routingAclCount, "Transient ROUTING ACL must be removed");

            long aclDeleteAuditCount = ((Number) ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("select count(*) from T_AUDIT_LOG where LOG_IDENTITY_C = :aclId and LOG_CLASSENTITY_C = 'Acl' and LOG_TYPE_C = 'DELETE'")
                    .setParameter("aclId", aclId[0])
                    .getSingleResult()).longValue();
            Assertions.assertEquals(1, aclDeleteAuditCount,
                    "Each removed ROUTING ACL must get a DELETE audit log entry");
            return null;
        });
    }

    /**
     * B2: deleting a principal that owns a FUTURE step of an active route cancels the route AND
     * clears the CURRENT approver's ROUTING grant. Route: step1 -> alice (current, holds the ROUTING
     * READ ACL), step2 -> bob (future, unended). Deleting bob cancels the route (its open step2
     * targets bob) but the current grant belongs to alice. The fix clears ALL of the document's
     * ROUTING ACLs on cancellation (target-agnostic), so alice's grant is gone. Before the fix, only
     * the deleted principal's (bob's) ACLs were removed, leaving alice's READ grant on a now-cancelled
     * route (an authz leak) — that is the RED assertion (0 active ROUTING ACLs would fail).
     */
    @Test
    public void testDeleteUserOwningFutureStepClearsCurrentAcl() {
        String adminToken = adminToken();
        clientUtil.createUser("wfalice");
        clientUtil.createUser("wfbob");

        String[] documentId = new String[1];
        String[] routeId = new String[1];
        String[] aliceAclId = new String[1];
        inTx(() -> {
            User alice = new UserDao().getActiveByUsername("wfalice");
            User bob = new UserDao().getActiveByUsername("wfbob");
            User admin = new UserDao().getActiveByUsername("admin");

            Document document = new Document();
            document.setUserId(admin.getId());
            document.setLanguage("eng");
            document.setTitle("Doc with alice+bob route");
            document.setCreateDate(new Date());
            documentId[0] = new DocumentDao().create(document, admin.getId());

            routeId[0] = new RouteDao().create(new Route().setDocumentId(documentId[0]).setName("Route"), admin.getId());

            // step1 -> alice (current), step2 -> bob (future). Both created unended (open).
            new RouteStepDao().create(new RouteStep()
                    .setRouteId(routeId[0]).setName("Alice step").setType(RouteStepType.VALIDATE)
                    .setTargetId(alice.getId()).setOrder(0));
            new RouteStepDao().create(new RouteStep()
                    .setRouteId(routeId[0]).setName("Bob step").setType(RouteStepType.VALIDATE)
                    .setTargetId(bob.getId()).setOrder(1));

            // The CURRENT step's ROUTING READ grant is alice's (route-start would have granted it).
            aliceAclId[0] = insertRoutingAcl(documentId[0], alice.getId());
            return null;
        });

        // Delete bob (owner of the FUTURE step2). This cancels the route (its open step2 targets bob).
        JsonObject json = target().path("/user/wfbob")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        inTx(() -> {
            Route route = ThreadLocalContext.get().getEntityManager().find(Route.class, routeId[0]);
            Assertions.assertEquals(RouteStatus.CANCELLED, route.getStatus(), "The route must be cancelled");

            // The invariant: a cancelled route leaves ZERO active ROUTING ACLs on the document —
            // alice's current grant included, even though alice was NOT the deleted principal.
            long routingAclCount = ((Number) ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("select count(*) from T_ACL where ACL_SOURCEID_C = :src and ACL_TYPE_C = 'ROUTING' and ACL_DELETEDATE_D is null")
                    .setParameter("src", documentId[0])
                    .getSingleResult()).longValue();
            Assertions.assertEquals(0, routingAclCount,
                    "Cancelling the route must clear the current approver's ROUTING ACL too, not just the deleted principal's");

            // Alice's specific grant got a DELETE audit row (target-agnostic cleanup still audits it).
            long aliceAclDeleteAudit = ((Number) ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("select count(*) from T_AUDIT_LOG where LOG_IDENTITY_C = :aclId and LOG_CLASSENTITY_C = 'Acl' and LOG_TYPE_C = 'DELETE'")
                    .setParameter("aclId", aliceAclId[0])
                    .getSingleResult()).longValue();
            Assertions.assertEquals(1, aliceAclDeleteAudit, "The cleared current-approver ACL must get a DELETE audit row");
            return null;
        });
    }

    /**
     * Reassignment (#55): admin-deleting a user who OWNS a document that has an ACTIVE route targeting
     * SOMEONE ELSE (a still-live user) reassigns the document to the chosen target rather than trashing
     * it. Because the document survives (under the new owner) and its route still targets a live user,
     * the route legitimately CONTINUES: it stays ACTIVE and the current step's ROUTING grant to that
     * live target is preserved. Only routes with an OPEN step targeting the DEPARTING user are
     * cancelled (by cancelRoutesTargetingPrincipal, covered by testDeleteUserTargetedByOpenStep); a
     * route targeting a different, surviving user is not disturbed by the owner's departure.
     *
     * <p>(Before #55 the owner's document was trashed and its active route cancelled — see the
     * self-delete path, which still trashes, in testSelfDeleteOwnerAndTargetClearsRouteAndAcls. The
     * admin delete now reassigns, so the trash-and-cancel behaviour no longer applies here.)</p>
     */
    @Test
    public void testDeleteUserOwningDocWithActiveRouteReassignsAndKeepsRoute() {
        String adminToken = adminToken();
        clientUtil.createUser("wfowner");
        clientUtil.createUser("wftarget");
        clientUtil.createUser("wfnewowner");

        String[] documentId = new String[1];
        String[] routeId = new String[1];
        String[] newOwnerId = new String[1];
        inTx(() -> {
            User owner = new UserDao().getActiveByUsername("wfowner");
            User targetUser = new UserDao().getActiveByUsername("wftarget");
            newOwnerId[0] = new UserDao().getActiveByUsername("wfnewowner").getId();

            // wfowner OWNS the document; the route's step targets wftarget (someone else, still alive).
            Document document = new Document();
            document.setUserId(owner.getId());
            document.setLanguage("eng");
            document.setTitle("Owned doc with active route");
            document.setCreateDate(new Date());
            documentId[0] = new DocumentDao().create(document, owner.getId());

            RouteDao routeDao = new RouteDao();
            // RouteDao.create sets ACTIVE status.
            routeId[0] = routeDao.create(new Route().setDocumentId(documentId[0]).setName("Owned route"), owner.getId());

            new RouteStepDao().create(new RouteStep()
                    .setRouteId(routeId[0]).setName("Target step").setType(RouteStepType.VALIDATE)
                    .setTargetId(targetUser.getId()).setOrder(0));

            // The ROUTING READ grant belongs to wftarget (the current step's target).
            insertRoutingAcl(documentId[0], targetUser.getId());
            return null;
        });

        // Admin deletes the OWNER (wfowner), reassigning their documents to wfnewowner. The owned
        // document is reassigned (not trashed); its active route (targeting the still-live wftarget)
        // continues.
        JsonObject json = target().path("/user/wfowner")
                .queryParam("reassign_to_username", "wfnewowner").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        inTx(() -> {
            // The document is reassigned to the new owner and NOT trashed.
            java.sql.Timestamp docDeleteTs = (java.sql.Timestamp) ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("select DOC_DELETEDATE_D from T_DOCUMENT where DOC_ID_C = :id")
                    .setParameter("id", documentId[0]).getSingleResult();
            Assertions.assertNull(docDeleteTs, "The owned document must be reassigned, not trashed");
            String docOwner = (String) ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("select DOC_IDUSER_C from T_DOCUMENT where DOC_ID_C = :id")
                    .setParameter("id", documentId[0]).getSingleResult();
            Assertions.assertEquals(newOwnerId[0], docOwner, "The document must be owned by the reassignment target");

            // The route targets a still-live user and continues: it stays ACTIVE and the ROUTING grant
            // to that live target is preserved.
            Route route = ThreadLocalContext.get().getEntityManager().find(Route.class, routeId[0]);
            Assertions.assertEquals(RouteStatus.ACTIVE, route.getStatus(),
                    "A route targeting a surviving user must continue when the owner's docs are reassigned");
            Assertions.assertNull(route.getEndDate(), "An active route must not carry an end date");

            long routingAclCount = ((Number) ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("select count(*) from T_ACL where ACL_SOURCEID_C = :src and ACL_TYPE_C = 'ROUTING' and ACL_DELETEDATE_D is null")
                    .setParameter("src", documentId[0])
                    .getSingleResult()).longValue();
            Assertions.assertEquals(1, routingAclCount,
                    "The current step's ROUTING grant to the still-live target must be preserved");
            return null;
        });
    }

    /**
     * Review-blocker 2 (owner == target) integration coverage: deleting a user who BOTH owns a
     * document AND is the current step's target ends with the route CANCELLED and ZERO active ROUTING
     * ACLs — no stuck route, no surviving grant — across the two cancellation paths that touch the
     * document (the target-cancel in UserResource, then the owned-document trash in UserDao.delete).
     * The DETERMINISTIC restore-safety timestamp guarantee (ROUTING ACL delete timestamp strictly
     * below the doc trash timestamp so restore cannot resurrect it, even on a timestamp collision) is
     * proven timing-independently by
     * TestPrincipalDeletionUtil#normalizesCollidingRoutingAclTimestampBelowDocTrashTimestamp; this
     * REST test only asserts the observable end state.
     */
    @Test
    public void testSelfDeleteOwnerAndTargetClearsRouteAndAcls() {
        clientUtil.createUser("wfownertarget");
        String selfToken = clientUtil.login("wfownertarget");

        String[] documentId = new String[1];
        String[] routeId = new String[1];
        inTx(() -> {
            User user = new UserDao().getActiveByUsername("wfownertarget");

            // The user OWNS the document AND is the current step's target.
            Document document = new Document();
            document.setUserId(user.getId());
            document.setLanguage("eng");
            document.setTitle("Owned-and-targeted doc");
            document.setCreateDate(new Date());
            documentId[0] = new DocumentDao().create(document, user.getId());

            routeId[0] = new RouteDao().create(new Route().setDocumentId(documentId[0]).setName("Self-owned route"), user.getId());
            new RouteStepDao().create(new RouteStep()
                    .setRouteId(routeId[0]).setName("Self step").setType(RouteStepType.VALIDATE)
                    .setTargetId(user.getId()).setOrder(0));
            insertRoutingAcl(documentId[0], user.getId());
            return null;
        });

        // Self-delete: target-cancel path clears ACLs + owned-doc path trashes the doc.
        JsonObject json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, selfToken)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        inTx(() -> {
            Route route = ThreadLocalContext.get().getEntityManager().find(Route.class, routeId[0]);
            Assertions.assertEquals(RouteStatus.CANCELLED, route.getStatus());

            long routingAclCount = ((Number) ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("select count(*) from T_ACL where ACL_SOURCEID_C = :src and ACL_TYPE_C = 'ROUTING' and ACL_DELETEDATE_D is null")
                    .setParameter("src", documentId[0])
                    .getSingleResult()).longValue();
            Assertions.assertEquals(0, routingAclCount,
                    "owner==target deletion must leave zero active ROUTING ACLs on the owned document");
            return null;
        });
    }

    /**
     * Self-delete path exercises the identical graceful handling: a user referenced by a model and
     * targeted by an open step deletes themselves, gets the warning payload, and their route is
     * cancelled.
     */
    @Test
    public void testSelfDeleteHandlesWorkflowReferences() {
        clientUtil.createUser("wfselfuser");
        String selfToken = clientUtil.login("wfselfuser");

        String[] routeId = new String[1];
        String modelId = inTx(() -> {
            User user = new UserDao().getActiveByUsername("wfselfuser");
            User admin = new UserDao().getActiveByUsername("admin");

            Document document = new Document();
            document.setUserId(admin.getId());
            document.setLanguage("eng");
            document.setTitle("Self doc");
            document.setCreateDate(new Date());
            String docId = new DocumentDao().create(document, admin.getId());

            routeId[0] = new RouteDao().create(new Route().setDocumentId(docId).setName("Self route"), admin.getId());
            new RouteStepDao().create(new RouteStep()
                    .setRouteId(routeId[0])
                    .setName("Open step")
                    .setType(RouteStepType.VALIDATE)
                    .setTargetId(user.getId())
                    .setOrder(0));

            String steps = "[{\"type\":\"VALIDATE\",\"target\":{\"name\":\"wfselfuser\",\"type\":\"USER\"},\"name\":\"Step 1\"}]";
            return new RouteModelDao().create(new RouteModel().setName("Self model").setSteps(steps), "admin");
        });

        // Self-delete.
        JsonObject json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, selfToken)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
        JsonArray affected = json.getJsonArray("route_models_affected");
        Assertions.assertNotNull(affected);
        Assertions.assertTrue(containsString(affected, "Self model"));

        // The route was cancelled.
        RouteStatus status = inTx(() ->
                ThreadLocalContext.get().getEntityManager().find(Route.class, routeId[0]).getStatus());
        Assertions.assertEquals(RouteStatus.CANCELLED, status);
        Assertions.assertNotNull(modelId);
    }

    /**
     * Run a supplier inside a committed database transaction on the test thread, so the in-process
     * Grizzly server (its request threads use their own transactions) observes the seeded/asserted
     * state. Mirrors TransactionUtil's lifecycle but commits.
     */
    static <T> T inTx(Supplier<T> supplier) {
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext previous = ThreadLocalContext.get();
        ThreadLocalContext context = ThreadLocalContext.get();
        context.setEntityManager(em);
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        try {
            T result = supplier.get();
            tx.commit();
            return result;
        } catch (RuntimeException e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            throw e;
        } finally {
            if (em.isOpen()) {
                em.close();
            }
            previous.setEntityManager(null);
        }
    }

    /**
     * Insert a transient ("ROUTING") READ ACL directly, standing in for what the workflow route-start
     * (owned by the parallel phase) would create. Uses a native insert with the literal type string.
     *
     * @return The generated ACL ID
     */
    static String insertRoutingAcl(String documentId, String targetId) {
        String aclId = UUID.randomUUID().toString();
        Query q = ThreadLocalContext.get().getEntityManager().createNativeQuery(
                "insert into T_ACL (ACL_ID_C, ACL_PERM_C, ACL_SOURCEID_C, ACL_TARGETID_C, ACL_TYPE_C) values (:id, :perm, :src, :target, :type)");
        q.setParameter("id", aclId);
        q.setParameter("perm", PermType.READ.name());
        q.setParameter("src", documentId);
        q.setParameter("target", targetId);
        q.setParameter("type", "ROUTING");
        q.executeUpdate();
        return aclId;
    }

    /**
     * @return true if the given session cookie is no longer accepted (the user resolves to anonymous).
     */
    private boolean cookieRejected(String token) {
        JsonObject json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        return json.getBoolean("anonymous");
    }

    private void configureSmtp(String adminToken) {
        target().path("/app/config_smtp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("hostname", "localhost")
                        .param("port", Integer.toString(getSmtpPort()))
                        .param("from", "contact@sismicsdocs.com")
                ), JsonObject.class);
    }

    /**
     * password_lost never enumerates users: a blank username and a nonexistent username both return the
     * same generic OK as an existing account, and ONLY the existing account yields a recovery token + email.
     *
     * <p>This guards specifically against the pre-fix bug where a blank username validated the wrong argument
     * and resolved to the first user in the table (typically {@code admin}), minting an extra recovery
     * token + email for that account. Merely popping the newest email and checking it looks right would still
     * pass that bug, so the assertions below pin down the TOTAL email count, the recipient, and the recovery
     * ROW count per account.</p>
     */
    @Test
    public void testPasswordLostNoEnumeration() throws Exception {
        String adminToken = adminToken();
        configureSmtp(adminToken);
        clientUtil.createUser("enum_target");

        // Blank username: generic OK, no user resolved, no key created.
        JsonObject blank = target().path("/user/password_lost").request()
                .post(Entity.form(new Form().param("username", "")), JsonObject.class);
        Assertions.assertEquals("ok", blank.getString("status"));

        // Nonexistent username: identical generic OK.
        JsonObject ghost = target().path("/user/password_lost").request()
                .post(Entity.form(new Form().param("username", "no_such_ghost")), JsonObject.class);
        Assertions.assertEquals("ok", ghost.getString("status"));

        // Existing username: identical generic OK, AND a real recovery email is produced.
        JsonObject real = target().path("/user/password_lost").request()
                .post(Entity.form(new Form().param("username", "enum_target")), JsonObject.class);
        Assertions.assertEquals("ok", real.getString("status"));

        // The one recovery email is addressed to the intended target — not to some other (first-in-table)
        // account, as the blank-username bug would have produced. The rendered message includes the To
        // header, so an email misdirected to admin would fail this contains-check.
        String emailBody = popEmail();
        Assertions.assertNotNull(emailBody, "the existing account must produce a recovery email");
        Assertions.assertTrue(emailBody.contains("Please reset your password"));
        Assertions.assertTrue(emailBody.contains("enum_target@docs.com"),
                "the recovery email must be addressed to the target account");

        // TOTAL email count == 1: no second email exists. The pre-fix bug sent an extra email during the
        // blank request (already delivered by now), which this fails on. On the fixed path this is the only
        // email, so the pop returns null.
        Assertions.assertNull(popEmail(),
                "only the existing account may produce a recovery email — total count must be exactly 1");

        // And no recovery ROW leaked to another account. Exactly one active recovery row for the target, and
        // zero for admin (the account a blank-username lookup erroneously resolved to under the pre-fix bug).
        long targetRows = inTx(() -> (Long) ThreadLocalContext.get().getEntityManager()
                .createQuery("select count(r) from PasswordRecovery r where r.username = :u and r.deleteDate is null")
                .setParameter("u", "enum_target").getSingleResult());
        long adminRows = inTx(() -> (Long) ThreadLocalContext.get().getEntityManager()
                .createQuery("select count(r) from PasswordRecovery r where r.username = :u and r.deleteDate is null")
                .setParameter("u", "admin").getSingleResult());
        Assertions.assertEquals(1L, targetRows, "exactly one recovery row must exist for the target account");
        Assertions.assertEquals(0L, adminRows,
                "a blank username must not create a recovery row for any other account");
    }

    /**
     * A self-service password change rotates the session: every prior token is revoked (a stolen/cloned
     * cookie stops working) and a single fresh token is issued for the current browser.
     */
    @Test
    public void testSelfServicePasswordChangeRotatesSession() {
        clientUtil.createUser("rotate_me");
        String tokenA = clientUtil.login("rotate_me");
        String tokenB = clientUtil.login("rotate_me");

        Response response = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tokenA)
                .post(Entity.form(new Form()
                        .param("current_password", "Test1234")
                        .param("password", "Rotated9Pass")));
        Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus());
        String tokenC = clientUtil.getAuthenticationCookie(response);
        Assertions.assertNotNull(tokenC, "a fresh session cookie is issued on rotation");
        Assertions.assertNotEquals(tokenA, tokenC);

        // Both old cookies are rejected (including the one that made the change), the new one works.
        Assertions.assertTrue(cookieRejected(tokenA), "the requesting cookie must be rotated out");
        Assertions.assertTrue(cookieRejected(tokenB), "the other prior session must be revoked");
        JsonObject withNew = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tokenC)
                .get(JsonObject.class);
        Assertions.assertFalse(withNew.getBoolean("anonymous"), "the fresh cookie must be valid");

        // Exactly one active session remains.
        JsonObject sessions = target().path("/user/session").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, tokenC)
                .get(JsonObject.class);
        Assertions.assertEquals(1, sessions.getJsonArray("sessions").size());
    }

    /**
     * A header-authenticated (non-cookie) request carrying a junk auth_token cookie must NOT be handed a
     * freshly-minted, durable bearer session when it changes its password. Pre-fix, rotation minted a
     * replacement token whenever ANY non-null cookie string was present, upgrading a junk cookie into a
     * persistent session.
     */
    @Test
    public void testJunkCookieOnHeaderAuthMintsNoSession() {
        clientUtil.createUser("hdr_rotate");

        // Header auth resolves the principal from X-Authenticated-User (trusted loopback in the test env);
        // the presented auth_token cookie is junk and resolves to no session token.
        Response response = target().path("/user").request()
                .header(HeaderBasedSecurityFilter.AUTHENTICATED_USER_HEADER, "hdr_rotate")
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, "not-a-real-session-token")
                .post(Entity.form(new Form()
                        .param("current_password", "Test1234")
                        .param("password", "HdrRotate9")));
        Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus());

        // No session cookie may be minted for a request whose cookie did not resolve to a valid session.
        String minted = clientUtil.getAuthenticationCookie(response);
        Assertions.assertNull(minted,
                "a junk cookie on a header-authenticated request must not mint a persistent session");
    }

    /**
     * A lost-key password reset revokes ALL of the user's sessions (a pre-existing, possibly attacker,
     * session must not survive the reset).
     */
    @Test
    public void testLostKeyResetRevokesAllSessions() throws Exception {
        String adminToken = adminToken();
        configureSmtp(adminToken);
        clientUtil.createUser("reset_revoke");
        String tokenA = clientUtil.login("reset_revoke");
        String tokenB = clientUtil.login("reset_revoke");

        JsonObject lost = target().path("/user/password_lost").request()
                .post(Entity.form(new Form().param("username", "reset_revoke")), JsonObject.class);
        Assertions.assertEquals("ok", lost.getString("status"));
        String emailBody = popEmail();
        Assertions.assertNotNull(emailBody, "No email to consume");
        Matcher keyMatcher = Pattern.compile("/passwordreset/(.+?)\"").matcher(emailBody);
        Assertions.assertTrue(keyMatcher.find(), "Token not found");
        String key = keyMatcher.group(1).replaceAll("=", "");

        JsonObject reset = target().path("/user/password_reset").request()
                .post(Entity.form(new Form()
                        .param("key", key)
                        .param("password", "Reset9Pass")), JsonObject.class);
        Assertions.assertEquals("ok", reset.getString("status"));

        // Every pre-reset session cookie is now rejected.
        Assertions.assertTrue(cookieRejected(tokenA));
        Assertions.assertTrue(cookieRejected(tokenB));
    }

    /**
     * An admin-initiated password reset revokes ALL of the TARGET user's sessions, but leaves the admin's
     * own session untouched.
     */
    @Test
    public void testAdminPasswordResetRevokesTargetSessions() {
        clientUtil.createUser("admin_revoke");
        String targetToken = clientUtil.login("admin_revoke");
        String adminToken = adminToken();

        JsonObject json = target().path("/user/admin_revoke").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("password", "Adm1nReset9")), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // The target user's session is revoked; the admin's own session is not.
        Assertions.assertTrue(cookieRejected(targetToken), "the target user's sessions must be revoked");
        Assertions.assertFalse(cookieRejected(adminToken), "the admin's own session must be untouched");
    }

    /**
     * With no trusted proxies configured (the default), a client-supplied X-Forwarded-For header is ignored:
     * an attacker cannot rotate the header to escape the per-account / per-network login lockout.
     */
    @Test
    public void testXffRotationDoesNotBypassLockout() {
        com.sismics.docs.rest.util.LoginThrottleStore.getInstance().reset();
        try {
            clientUtil.createUser("xff_lock");
            int maxAttempts = Integer.parseInt(System.getenv().getOrDefault("DOCS_LOGIN_MAX_ATTEMPTS", "5"));

            // Exhaust the threshold with wrong passwords, rotating the spoofed X-Forwarded-For every time.
            for (int i = 0; i < maxAttempts; i++) {
                Response wrong = target().path("/user/login").request()
                        .header("X-Forwarded-For", "203.0.113." + i)
                        .post(Entity.form(new Form()
                                .param("username", "xff_lock")
                                .param("password", "wrongpass")
                                .param("remember", "false")));
                Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), wrong.getStatus());
            }

            // Even the CORRECT password, with yet another fresh spoofed header, is now locked out: the header
            // never partitioned the attempts.
            Response locked = target().path("/user/login").request()
                    .header("X-Forwarded-For", "198.51.100.42")
                    .post(Entity.form(new Form()
                            .param("username", "xff_lock")
                            .param("password", "Test1234")
                            .param("remember", "false")));
            Assertions.assertEquals(429, locked.getStatus());
        } finally {
            com.sismics.docs.rest.util.LoginThrottleStore.getInstance().reset();
        }
    }

    /**
     * #82 server-side per-user UI language: POST /user accepts an optional 'locale' that round-trips
     * on GET /user, an unknown locale is rejected (400), and an absent locale leaves the stored value
     * unchanged (and is omitted from GET until set).
     */
    @Test
    public void testUserLocale() {
        clientUtil.createUser("locale_user");
        String token = clientUtil.login("locale_user");

        // No locale set yet: GET omits the field.
        JsonObject json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertFalse(json.containsKey("locale"), "locale omitted from GET until set");

        // Set a valid locale — round-trips on GET.
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("locale", "de")), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals("de", json.getString("locale"));

        // A follow-up update that omits locale must NOT clear the stored value.
        target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("email", "locale_user2@docs.com")), JsonObject.class);
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals("de", json.getString("locale"), "absent locale leaves stored value unchanged");
        Assertions.assertEquals("locale_user2@docs.com", json.getString("email"));

        // A multi-part SPA locale code is accepted (validated against the supported set).
        target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("locale", "zh_CN")), JsonObject.class);
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals("zh_CN", json.getString("locale"));

        // An unknown locale is rejected with a validation error (400), stored value unchanged.
        Response bad = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form().param("locale", "xx_ZZ")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(bad.getStatus()));
        json = bad.readEntity(JsonObject.class);
        Assertions.assertEquals("ValidationError", json.getString("type"));
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals("zh_CN", json.getString("locale"), "rejected locale must not overwrite stored value");
    }

    /**
     * True if a JSON string array contains the given value.
     */
    static boolean containsString(JsonArray array, String value) {
        for (int i = 0; i < array.size(); i++) {
            if (value.equals(array.getString(i))) {
                return true;
            }
        }
        return false;
    }
}
