package com.sismics.docs.rest;

import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import com.sismics.util.filter.HeaderBasedSecurityFilter;
import org.junit.jupiter.api.Assertions;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

import com.sismics.util.filter.TokenBasedSecurityFilter;

/**
 * Test of the security layer.
 * 
 * @author jtremeaux
 */
public class TestSecurity extends BaseJerseyTest {
    /**
     * Test of the security layer.
     */
    @Test
    public void testSecurity() {
        // Create a user
        clientUtil.createUser("testsecurity");

        // Changes a user's email KO : the user is not connected
        Response response = target().path("/user").request()
                .post(Entity.form(new Form().param("email", "testsecurity2@docs.com")));
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()));
        JsonObject json = response.readEntity(JsonObject.class);
        Assertions.assertEquals("ForbiddenError", json.getString("type"));
        Assertions.assertEquals("You don't have access to this resource", json.getString("message"));

        // User testsecurity logs in
        String testSecurityToken = clientUtil.login("testsecurity");
        
        // User testsecurity creates a new user KO : no permission
        response = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, testSecurityToken)
                .put(Entity.form(new Form()));
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()));
        Assertions.assertEquals("ForbiddenError", json.getString("type"));
        Assertions.assertEquals("You don't have access to this resource", json.getString("message"));
        
        // User testsecurity changes his email OK
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, testSecurityToken)
                .post(Entity.form(new Form()
                        .param("email", "testsecurity2@docs.com")), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // User testsecurity logs out
        response = target().path("/user/logout").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, testSecurityToken)
                .post(Entity.form(new Form()));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        testSecurityToken = clientUtil.getAuthenticationCookie(response);
        Assertions.assertTrue(StringUtils.isEmpty(testSecurityToken));

        // User testsecurity logs out KO : he is not connected anymore
        response = target().path("/user/logout").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, testSecurityToken)
                .post(Entity.form(new Form()));
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()));

        // User testsecurity logs in with a long lived session
        testSecurityToken = clientUtil.login("testsecurity", "12345678", true);

        // User testsecurity logs out
        clientUtil.logout(testSecurityToken);

        // Delete the user
        String adminToken = adminToken();
        target().path("/user/testsecurity").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    @Test
    public void testHeaderBasedAuthentication() {
        clientUtil.createUser("header_auth_test");

        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), target()
                .path("/user/session")
                .request()
                .get()
                .getStatus());

        Assertions.assertEquals(Status.OK.getStatusCode(), target()
                .path("/user/session")
                .request()
                .header(HeaderBasedSecurityFilter.AUTHENTICATED_USER_HEADER, "header_auth_test")
                .get()
                .getStatus());

        Assertions.assertEquals(Status.FORBIDDEN.getStatusCode(), target()
                .path("/user/session")
                .request()
                .header(HeaderBasedSecurityFilter.AUTHENTICATED_USER_HEADER, "idontexist")
                .get()
                .getStatus());
        
        // Delete the user
        String adminToken = adminToken();
        target().path("/user/header_auth_test").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }
}