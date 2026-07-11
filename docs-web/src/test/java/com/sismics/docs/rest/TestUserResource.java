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
        com.sismics.docs.rest.util.LoginRateLimiter.getInstance().reset();
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
            com.sismics.docs.rest.util.LoginRateLimiter.getInstance().reset();
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
        response = target().path("/user/absent_minded").request()
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
        JsonObject json = target().path("/user/wfmodeluser").request()
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
        JsonObject json = target().path("/user/wfstepuser").request()
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
        JsonObject json = target().path("/user/wfbob").request()
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
     * B3: deleting a user who OWNS a document that has an ACTIVE route targeting SOMEONE ELSE cancels
     * that route and clears its ROUTING ACLs as the document is trashed. The structural fix does NOT
     * route owned documents through DocumentDao.delete (that would trash files by documentId and
     * over-trash a collaborator's file, breaking BL-021's stranded-file quota reclaim). Instead
     * UserDao.delete, for each owned document it is about to bulk-trash, first locks the document row
     * FOR UPDATE and calls cancelActiveRoutesForDocument — which cancels the active route, system-ends
     * its open steps, and clears ALL its ROUTING ACLs with the deterministic (documentDeleteDate - 1ms)
     * timestamp — sharing the SAME dateNow as the trash; file trashing stays owner-scoped. Before the
     * fix, UserDao.delete bulk-trashed owned documents directly with no route handling, leaving the
     * route ACTIVE and the target's ROUTING grant stranded (a stuck route that can never advance —
     * trashed docs are excluded from reads). This test also asserts the W2c restore-safety invariant:
     * the soft-deleted ROUTING ACL's delete timestamp is DISTINCT from the document's own trash
     * timestamp, so a later restore-from-trash (which un-deletes the doc's own ACLs by exact-equality
     * on the doc timestamp) cannot resurrect the cancelled route's grant. RED without the fix: the
     * route stays ACTIVE and the active ROUTING ACL count is non-zero.
     */
    @Test
    public void testDeleteUserOwningDocWithActiveRouteCancelsIt() {
        String adminToken = adminToken();
        clientUtil.createUser("wfowner");
        clientUtil.createUser("wftarget");

        String[] documentId = new String[1];
        String[] routeId = new String[1];
        inTx(() -> {
            User owner = new UserDao().getActiveByUsername("wfowner");
            User targetUser = new UserDao().getActiveByUsername("wftarget");

            // wfowner OWNS the document; the route's step targets wftarget (someone else).
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

        // Delete the OWNER (wfowner). Their owned document is trashed; its active route must be
        // cancelled and its ROUTING ACLs cleared in the process.
        JsonObject json = target().path("/user/wfowner").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        inTx(() -> {
            Route route = ThreadLocalContext.get().getEntityManager().find(Route.class, routeId[0]);
            Assertions.assertEquals(RouteStatus.CANCELLED, route.getStatus(),
                    "The owned document's active route must be cancelled, not left ACTIVE and stuck");
            Assertions.assertNotNull(route.getEndDate(), "A cancelled route must carry an end date");

            long routingAclCount = ((Number) ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("select count(*) from T_ACL where ACL_SOURCEID_C = :src and ACL_TYPE_C = 'ROUTING' and ACL_DELETEDATE_D is null")
                    .setParameter("src", documentId[0])
                    .getSingleResult()).longValue();
            Assertions.assertEquals(0, routingAclCount,
                    "The stranded ROUTING ACL on the owned document must be cleared when its route is cancelled");

            // W2c restore-safety: the ROUTING ACL's delete timestamp must be DISTINCT from the
            // document's own trash timestamp. restore() un-deletes the doc's own ACLs by
            // exact-equality on the doc's delete timestamp, so a distinct routing-ACL timestamp is
            // what keeps the cancelled route's grant from being resurrected. The per-doc path uses
            // (documentDeleteDate - 1ms), guaranteeing the two differ even when the deleted user is
            // both the owner and a step target (no independent clock sample to collide).
            java.sql.Timestamp docDeleteTs = (java.sql.Timestamp) ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("select DOC_DELETEDATE_D from T_DOCUMENT where DOC_ID_C = :id")
                    .setParameter("id", documentId[0]).getSingleResult();
            java.sql.Timestamp routingAclDeleteTs = (java.sql.Timestamp) ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("select ACL_DELETEDATE_D from T_ACL where ACL_SOURCEID_C = :src " +
                            "and ACL_TYPE_C = 'ROUTING' and ACL_DELETEDATE_D is not null")
                    .setParameter("src", documentId[0]).getSingleResult();
            Assertions.assertNotNull(docDeleteTs, "The owned document must be trashed");
            Assertions.assertNotNull(routingAclDeleteTs, "The ROUTING ACL must be soft-deleted");
            Assertions.assertNotEquals(docDeleteTs.getTime(), routingAclDeleteTs.getTime(),
                    "The ROUTING ACL delete timestamp must differ from the doc trash timestamp (restore-safety)");
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
