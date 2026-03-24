package com.sismics.docs.rest.resource;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.dao.AuthenticationTokenDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.AuthenticationToken;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Optional.ofNullable;

/**
 * OIDC authentication REST resource.
 * Implements the OAuth 2.0 Authorization Code flow for OpenID Connect providers.
 */
@Path("/oidc")
public class OidcResource extends BaseResource {
    private static final Logger log = LoggerFactory.getLogger(OidcResource.class);
    private static final OkHttpClient httpClient = new OkHttpClient();

    /**
     * Pending state tokens with creation timestamp for CSRF protection.
     * Entries are cleaned up on use; stale entries expire after 10 minutes.
     */
    private static final Map<String, Long> pendingStates = new ConcurrentHashMap<>();
    private static final long STATE_TTL_MS = 10 * 60 * 1000;

    /**
     * Cached OIDC discovery metadata (lazily fetched once).
     */
    private static volatile JsonObject discoveryCache;

    /**
     * Initiates the OIDC login flow by redirecting to the provider's authorization endpoint.
     *
     * @return 302 redirect to the OIDC provider
     */
    @GET
    @Path("login")
    public Response login() {
        if (!isOidcEnabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        try {
            JsonObject discovery = getDiscovery();
            String authorizationEndpoint = discovery.getString("authorization_endpoint");
            String clientId = System.getProperty("docs.oidc_client_id");
            String redirectUri = System.getProperty("docs.oidc_redirect_uri");
            String scope = ofNullable(System.getProperty("docs.oidc_scope")).orElse("openid profile email");

            String state = UUID.randomUUID().toString();
            pendingStates.put(state, System.currentTimeMillis());
            cleanupExpiredStates();

            String authorizeUrl = authorizationEndpoint
                    + "?response_type=code"
                    + "&client_id=" + urlEncode(clientId)
                    + "&redirect_uri=" + urlEncode(redirectUri)
                    + "&scope=" + urlEncode(scope)
                    + "&state=" + urlEncode(state);

            return Response.temporaryRedirect(URI.create(authorizeUrl)).build();
        } catch (Exception e) {
            log.error("Error initiating OIDC login", e);
            return Response.serverError().build();
        }
    }

    /**
     * Handles the OIDC callback after the user authenticates with the provider.
     * Exchanges the authorization code for tokens, extracts user identity,
     * finds or creates the Teedy user, issues a session cookie, and redirects to the app.
     *
     * @param code  Authorization code from the provider
     * @param state State parameter for CSRF validation
     * @return 302 redirect to the application with auth_token cookie set
     */
    @GET
    @Path("callback")
    public Response callback(@QueryParam("code") String code,
                             @QueryParam("state") String state,
                             @QueryParam("error") String error) {
        if (!isOidcEnabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (error != null) {
            log.error("OIDC provider returned error: {}", error);
            return Response.temporaryRedirect(URI.create("/#/login")).build();
        }

        if (StringUtils.isBlank(code) || StringUtils.isBlank(state)) {
            log.warn("OIDC callback missing code or state parameter");
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        // Validate state (CSRF protection)
        Long stateTimestamp = pendingStates.remove(state);
        if (stateTimestamp == null || System.currentTimeMillis() - stateTimestamp > STATE_TTL_MS) {
            log.warn("OIDC callback with invalid or expired state");
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        try {
            // Exchange authorization code for tokens
            JsonObject tokenResponse = exchangeCodeForTokens(code);
            String idTokenStr = tokenResponse.getString("id_token", null);
            if (idTokenStr == null) {
                log.error("OIDC token response missing id_token");
                return Response.temporaryRedirect(URI.create("/#/login")).build();
            }

            // Decode the ID token to extract claims
            DecodedJWT idToken = JWT.decode(idTokenStr);
            String preferredUsername = getClaimAsString(idToken, "preferred_username");
            String email = getClaimAsString(idToken, "email");
            String subject = idToken.getSubject();

            if (log.isInfoEnabled()) {
                log.info("OIDC login: sub={}, preferred_username={}, email={}", subject, preferredUsername, email);
            }

            // Resolve the Teedy user: try username first, then email, then auto-create
            UserDao userDao = new UserDao();
            User user = null;

            if (preferredUsername != null) {
                user = userDao.getActiveByUsername(preferredUsername);
            }
            if (user == null && email != null) {
                user = userDao.getByEmail(email);
            }
            if (user == null) {
                user = provisionUser(userDao, preferredUsername, email, subject);
                if (user == null) {
                    log.error("Failed to provision OIDC user: sub={}", subject);
                    return Response.temporaryRedirect(URI.create("/#/login")).build();
                }
            }

            // Create a long-lived session token (same as regular login with "remember me")
            String ip = request.getHeader("x-forwarded-for");
            if (StringUtils.isBlank(ip)) {
                ip = request.getRemoteAddr();
            }

            AuthenticationTokenDao authTokenDao = new AuthenticationTokenDao();
            AuthenticationToken authToken = new AuthenticationToken()
                    .setUserId(user.getId())
                    .setLongLasted(true)
                    .setIp(StringUtils.abbreviate(ip, 45))
                    .setUserAgent(StringUtils.abbreviate(request.getHeader("user-agent"), 1000));
            String tokenValue = authTokenDao.create(authToken);
            authTokenDao.deleteOldSessionToken(user.getId());

            NewCookie cookie = new NewCookie(
                    TokenBasedSecurityFilter.COOKIE_NAME, tokenValue,
                    "/", null, null,
                    TokenBasedSecurityFilter.TOKEN_LONG_LIFETIME, false);

            return Response.temporaryRedirect(URI.create("/#/"))
                    .cookie(cookie)
                    .build();
        } catch (Exception e) {
            log.error("Error processing OIDC callback", e);
            return Response.temporaryRedirect(URI.create("/#/login")).build();
        }
    }

    /**
     * Exchanges the authorization code for tokens via the provider's token endpoint.
     */
    private JsonObject exchangeCodeForTokens(String code) throws Exception {
        JsonObject discovery = getDiscovery();
        String tokenEndpoint = discovery.getString("token_endpoint");
        String clientId = System.getProperty("docs.oidc_client_id");
        String clientSecret = System.getProperty("docs.oidc_client_secret");
        String redirectUri = System.getProperty("docs.oidc_redirect_uri");

        FormBody body = new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", redirectUri)
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .build();

        Request req = new Request.Builder()
                .url(tokenEndpoint)
                .post(body)
                .build();

        try (okhttp3.Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new Exception("Token exchange failed: HTTP " + resp.code());
            }
            String responseBody = resp.body().string();
            try (JsonReader reader = Json.createReader(new StringReader(responseBody))) {
                return reader.readObject();
            }
        }
    }

    /**
     * Creates a new Teedy user from OIDC claims (mirrors JwtBasedSecurityFilter provisioning).
     */
    private User provisionUser(UserDao userDao, String preferredUsername, String email, String subject) {
        String username = preferredUsername != null ? preferredUsername : (email != null ? email : subject);
        String userEmail = email != null ? email : username + "@oidc.local";

        User user = new User();
        user.setRoleId(Constants.DEFAULT_USER_ROLE);
        user.setUsername(username);
        user.setEmail(userEmail);
        user.setStorageQuota(Long.parseLong(ofNullable(System.getenv(Constants.GLOBAL_QUOTA_ENV))
                .orElse("1073741824")));
        user.setPassword(UUID.randomUUID().toString());
        user.setOnboarding(true);

        try {
            userDao.create(user, username);
            log.info("Provisioned new OIDC user: {}", username);
            return user;
        } catch (Exception e) {
            log.error("Error creating OIDC user: {}", username, e);
            return null;
        }
    }

    /**
     * Fetches and caches the OIDC discovery document from {issuer}/.well-known/openid-configuration.
     */
    private JsonObject getDiscovery() throws Exception {
        if (discoveryCache != null) {
            return discoveryCache;
        }

        synchronized (OidcResource.class) {
            if (discoveryCache != null) {
                return discoveryCache;
            }

            String issuer = System.getProperty("docs.oidc_issuer");
            String discoveryUrl = issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration";

            Request req = new Request.Builder().url(discoveryUrl).get().build();
            try (okhttp3.Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    throw new Exception("Failed to fetch OIDC discovery: HTTP " + resp.code());
                }
                String body = resp.body().string();
                try (JsonReader reader = Json.createReader(new StringReader(body))) {
                    discoveryCache = reader.readObject();
                }
            }

            log.info("OIDC discovery loaded from {}", discoveryUrl);
            return discoveryCache;
        }
    }

    private static boolean isOidcEnabled() {
        return Boolean.parseBoolean(System.getProperty("docs.oidc_enabled"));
    }

    private static String getClaimAsString(DecodedJWT jwt, String claim) {
        var c = jwt.getClaim(claim);
        return c.isMissing() || c.isNull() ? null : c.asString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void cleanupExpiredStates() {
        long now = System.currentTimeMillis();
        pendingStates.entrySet().removeIf(e -> now - e.getValue() > STATE_TTL_MS);
    }
}
