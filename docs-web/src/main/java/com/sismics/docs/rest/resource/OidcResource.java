package com.sismics.docs.rest.resource;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.dao.AuthenticationTokenDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.AuthenticationToken;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.Json;
import jakarta.json.JsonArray;
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
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Date;
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

    private static final Map<String, Long> pendingStates = new ConcurrentHashMap<>();
    private static final Map<String, String> pendingNonces = new ConcurrentHashMap<>();
    private static final long STATE_TTL_MS = 10 * 60 * 1000;

    private static volatile JsonObject discoveryCache;
    private static volatile JsonObject jwksCache;
    private static volatile boolean configValidated = false;

    private static final String PROP_ENABLED = "docs.oidc_enabled";
    private static final String PROP_ISSUER = "docs.oidc_issuer";
    private static final String PROP_CLIENT_ID = "docs.oidc_client_id";
    private static final String PROP_CLIENT_SECRET = "docs.oidc_client_secret";
    private static final String PROP_REDIRECT_URI = "docs.oidc_redirect_uri";
    private static final String PROP_SCOPE = "docs.oidc_scope";
    private static final String PROP_AUTH_ENDPOINT = "docs.oidc_authorization_endpoint";
    private static final String PROP_TOKEN_ENDPOINT = "docs.oidc_token_endpoint";
    private static final String PROP_JWKS_URI = "docs.oidc_jwks_uri";

    private static final String USERNAME_PATTERN = "^[a-zA-Z0-9_]{3,50}$";

    @GET
    @Path("login")
    public Response login() {
        if (!isOidcEnabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String configError = validateConfig();
        if (configError != null) {
            log.error("OIDC misconfigured: {}", configError);
            return Response.serverError().build();
        }

        try {
            String authorizationEndpoint = getAuthorizationEndpoint();
            String clientId = System.getProperty(PROP_CLIENT_ID);
            String redirectUri = System.getProperty(PROP_REDIRECT_URI);
            String scope = ofNullable(System.getProperty(PROP_SCOPE)).orElse("openid profile email");

            String state = UUID.randomUUID().toString();
            String nonce = UUID.randomUUID().toString();
            pendingStates.put(state, System.currentTimeMillis());
            pendingNonces.put(state, nonce);
            cleanupExpiredStates();

            String authorizeUrl = authorizationEndpoint
                    + "?response_type=code"
                    + "&client_id=" + urlEncode(clientId)
                    + "&redirect_uri=" + urlEncode(redirectUri)
                    + "&scope=" + urlEncode(scope)
                    + "&state=" + urlEncode(state)
                    + "&nonce=" + urlEncode(nonce);

            return Response.temporaryRedirect(URI.create(authorizeUrl)).build();
        } catch (Exception e) {
            log.error("Error initiating OIDC login", e);
            return Response.serverError().build();
        }
    }

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

        Long stateTimestamp = pendingStates.remove(state);
        String expectedNonce = pendingNonces.remove(state);
        if (stateTimestamp == null || System.currentTimeMillis() - stateTimestamp > STATE_TTL_MS) {
            log.warn("OIDC callback with invalid or expired state");
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        try {
            JsonObject tokenResponse = exchangeCodeForTokens(code);
            String idTokenStr = tokenResponse.getString("id_token", null);
            if (idTokenStr == null) {
                log.error("OIDC token response missing id_token");
                return Response.temporaryRedirect(URI.create("/#/login")).build();
            }

            DecodedJWT idToken = verifyIdToken(idTokenStr);

            // Verify nonce matches what we sent in the authorization request
            String tokenNonce = getClaimAsString(idToken, "nonce");
            if (expectedNonce != null && !expectedNonce.equals(tokenNonce)) {
                log.error("OIDC nonce mismatch: expected={}, got={}", expectedNonce, tokenNonce);
                return Response.temporaryRedirect(URI.create("/#/login")).build();
            }

            String preferredUsername = getClaimAsString(idToken, "preferred_username");
            String email = getClaimAsString(idToken, "email");
            String subject = idToken.getSubject();

            log.info("OIDC login: sub={}, preferred_username={}, email={}", subject, preferredUsername, email);

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
                    "/", null, NewCookie.DEFAULT_VERSION, null,
                    TokenBasedSecurityFilter.TOKEN_LONG_LIFETIME,
                    (Date) null, true, true);

            return Response.temporaryRedirect(URI.create("/#/"))
                    .cookie(cookie)
                    .build();
        } catch (Exception e) {
            log.error("Error processing OIDC callback", e);
            return Response.temporaryRedirect(URI.create("/#/login")).build();
        }
    }

    /**
     * Verifies the ID token signature using the provider's JWKS, and validates issuer + audience.
     */
    private DecodedJWT verifyIdToken(String idTokenStr) throws Exception {
        DecodedJWT unverified = JWT.decode(idTokenStr);
        String kid = unverified.getKeyId();

        RSAPublicKey publicKey = getSigningKey(kid);
        if (publicKey == null) {
            throw new Exception("No matching key found in JWKS for kid=" + kid);
        }

        Algorithm algo = Algorithm.RSA256(publicKey, null);
        JWTVerifier verifier = JWT.require(algo)
                .withIssuer(System.getProperty(PROP_ISSUER))
                .withAudience(System.getProperty(PROP_CLIENT_ID))
                .build();

        return verifier.verify(idTokenStr);
    }

    /**
     * Fetches the RSA public key matching the given kid from the provider's JWKS.
     */
    private RSAPublicKey getSigningKey(String kid) throws Exception {
        JsonObject jwks = getJwks();
        JsonArray keys = jwks.getJsonArray("keys");

        for (int i = 0; i < keys.size(); i++) {
            JsonObject key = keys.getJsonObject(i);
            if (kid == null || kid.equals(key.getString("kid", null))) {
                String n = key.getString("n");
                String e = key.getString("e");

                byte[] nBytes = Base64.getUrlDecoder().decode(n);
                byte[] eBytes = Base64.getUrlDecoder().decode(e);
                RSAPublicKeySpec spec = new RSAPublicKeySpec(
                        new BigInteger(1, nBytes), new BigInteger(1, eBytes));

                return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
            }
        }
        return null;
    }

    private JsonObject exchangeCodeForTokens(String code) throws Exception {
        String tokenEndpoint = getTokenEndpoint();
        String clientId = System.getProperty(PROP_CLIENT_ID);
        String clientSecret = System.getProperty(PROP_CLIENT_SECRET);
        String redirectUri = System.getProperty(PROP_REDIRECT_URI);

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

    private User provisionUser(UserDao userDao, String preferredUsername, String email, String subject) {
        String username = preferredUsername != null ? preferredUsername : (email != null ? email : subject);
        String userEmail = email != null ? email : username + "@oidc.local";

        if (!username.matches(USERNAME_PATTERN)) {
            log.warn("OIDC provisioning rejected: username '{}' does not match required pattern", username);
            return null;
        }

        if (userEmail.indexOf('@') < 1) {
            log.warn("OIDC provisioning rejected: invalid email '{}'", userEmail);
            return null;
        }

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
     * Validates OIDC configuration on first use. Returns an error message if invalid, null if OK.
     */
    private String validateConfig() {
        if (configValidated) {
            return null;
        }

        synchronized (OidcResource.class) {
            if (configValidated) {
                return null;
            }

            String issuer = System.getProperty(PROP_ISSUER);
            String clientId = System.getProperty(PROP_CLIENT_ID);
            String clientSecret = System.getProperty(PROP_CLIENT_SECRET);
            String redirectUri = System.getProperty(PROP_REDIRECT_URI);

            if (StringUtils.isBlank(issuer)) return PROP_ISSUER + " is required";
            if (StringUtils.isBlank(clientId)) return PROP_CLIENT_ID + " is required";
            if (StringUtils.isBlank(clientSecret)) return PROP_CLIENT_SECRET + " is required";
            if (StringUtils.isBlank(redirectUri)) return PROP_REDIRECT_URI + " is required";

            log.info("OIDC configuration: issuer={}, client_id={}, redirect_uri={}, secret={}***",
                    issuer, clientId, redirectUri,
                    clientSecret.length() > 4 ? clientSecret.substring(0, 4) : "****");

            configValidated = true;
            return null;
        }
    }

    private String getAuthorizationEndpoint() throws Exception {
        String explicit = System.getProperty(PROP_AUTH_ENDPOINT);
        if (explicit != null) {
            return explicit;
        }
        return getDiscovery().getString("authorization_endpoint");
    }

    private String getTokenEndpoint() throws Exception {
        String explicit = System.getProperty(PROP_TOKEN_ENDPOINT);
        if (explicit != null) {
            return explicit;
        }
        return getDiscovery().getString("token_endpoint");
    }

    private String getJwksUri() throws Exception {
        String explicit = System.getProperty(PROP_JWKS_URI);
        if (explicit != null) {
            return explicit;
        }
        return getDiscovery().getString("jwks_uri");
    }

    private JsonObject getDiscovery() throws Exception {
        if (discoveryCache != null) {
            return discoveryCache;
        }

        synchronized (OidcResource.class) {
            if (discoveryCache != null) {
                return discoveryCache;
            }

            String issuer = System.getProperty(PROP_ISSUER);
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

    private JsonObject getJwks() throws Exception {
        if (jwksCache != null) {
            return jwksCache;
        }

        synchronized (OidcResource.class) {
            if (jwksCache != null) {
                return jwksCache;
            }

            String jwksUri = getJwksUri();
            Request req = new Request.Builder().url(jwksUri).get().build();
            try (okhttp3.Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    throw new Exception("Failed to fetch JWKS: HTTP " + resp.code());
                }
                String body = resp.body().string();
                try (JsonReader reader = Json.createReader(new StringReader(body))) {
                    jwksCache = reader.readObject();
                }
            }

            log.info("OIDC JWKS loaded from {}", jwksUri);
            return jwksCache;
        }
    }

    private static boolean isOidcEnabled() {
        return Boolean.parseBoolean(System.getProperty(PROP_ENABLED));
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
        pendingNonces.keySet().removeIf(key -> !pendingStates.containsKey(key));
    }
}
