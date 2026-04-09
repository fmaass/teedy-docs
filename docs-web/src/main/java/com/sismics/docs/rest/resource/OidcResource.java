package com.sismics.docs.rest.resource;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.dao.AuthenticationTokenDao;
import com.sismics.docs.core.dao.OidcStateDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.AuthenticationToken;
import com.sismics.docs.core.model.jpa.OidcState;
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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static java.util.Optional.ofNullable;

/**
 * OIDC authentication REST resource.
 * Implements the OAuth 2.0 Authorization Code flow for OpenID Connect providers.
 */
@Path("/oidc")
public class OidcResource extends BaseResource {
    private static final Logger log = LoggerFactory.getLogger(OidcResource.class);
    private static final OkHttpClient httpClient = new OkHttpClient();

    private static final long STATE_TTL_MS = 10 * 60 * 1000;

    private static volatile JsonObject discoveryCache;
    private static volatile JsonObject jwksCache;
    private static volatile long jwksLastRefreshMs = 0;
    private static final long JWKS_MIN_REFRESH_INTERVAL_MS = 60 * 1000;
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
    public Response login(@QueryParam("returnUrl") String returnUrl) {
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
            String codeVerifier = generateCodeVerifier();
            String codeChallenge = computeCodeChallenge(codeVerifier);

            OidcStateDao oidcStateDao = new OidcStateDao();
            oidcStateDao.deleteExpired(STATE_TTL_MS);
            String safeReturnUrl = null;
            if (returnUrl != null && returnUrl.startsWith("/#/")) {
                safeReturnUrl = returnUrl;
            }

            OidcState oidcState = new OidcState()
                    .setId(state)
                    .setNonce(nonce)
                    .setCodeVerifier(codeVerifier)
                    .setReturnUrl(safeReturnUrl);
            oidcStateDao.create(oidcState);

            String authorizeUrl = authorizationEndpoint
                    + "?response_type=code"
                    + "&client_id=" + urlEncode(clientId)
                    + "&redirect_uri=" + urlEncode(redirectUri)
                    + "&scope=" + urlEncode(scope)
                    + "&state=" + urlEncode(state)
                    + "&nonce=" + urlEncode(nonce)
                    + "&code_challenge=" + urlEncode(codeChallenge)
                    + "&code_challenge_method=S256";

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

        OidcStateDao oidcStateDao = new OidcStateDao();
        OidcState oidcState = oidcStateDao.getAndDelete(state);
        if (oidcState == null) {
            log.warn("OIDC callback with invalid or expired state");
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        if (System.currentTimeMillis() - oidcState.getCreateDate().getTime() > STATE_TTL_MS) {
            log.warn("OIDC callback with expired state");
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        String expectedNonce = oidcState.getNonce();

        try {
            JsonObject tokenResponse = exchangeCodeForTokens(code, oidcState.getCodeVerifier());
            String idTokenStr = tokenResponse.getString("id_token", null);
            if (idTokenStr == null) {
                log.error("OIDC token response missing id_token");
                return Response.temporaryRedirect(URI.create("/#/login")).build();
            }

            DecodedJWT idToken = verifyIdToken(idTokenStr);

            // Verify nonce matches what we sent in the authorization request (fail closed)
            String tokenNonce = getClaimAsString(idToken, "nonce");
            if (expectedNonce == null || !expectedNonce.equals(tokenNonce)) {
                log.error("OIDC nonce mismatch: expected={}, got={}", expectedNonce, tokenNonce);
                return Response.temporaryRedirect(URI.create("/#/login")).build();
            }

            String preferredUsername = getClaimAsString(idToken, "preferred_username");
            String email = getClaimAsString(idToken, "email");
            String subject = idToken.getSubject();
            String issuer = System.getProperty(PROP_ISSUER);

            log.info("OIDC login: sub={}, preferred_username={}, email={}", subject, preferredUsername, email);

            UserDao userDao = new UserDao();
            User user = null;

            // First: stable lookup by OIDC subject (prevents email-based account takeover)
            if (subject != null) {
                user = userDao.getByOidcSubject(issuer, subject);
            }

            // Security: do NOT auto-link to existing local accounts by username/email.
            // First OIDC login always provisions a new user to prevent account takeover.
            if (user == null) {
                user = provisionUser(userDao, preferredUsername, email, subject, issuer);
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
                    .setUserAgent(StringUtils.abbreviate(request.getHeader("user-agent"), 1000))
                    .setOidcIdToken(idTokenStr);
            String tokenValue = authTokenDao.create(authToken);
            authTokenDao.deleteOldSessionToken(user.getId());

            NewCookie cookie = new NewCookie(
                    TokenBasedSecurityFilter.COOKIE_NAME, tokenValue,
                    "/", null, NewCookie.DEFAULT_VERSION, null,
                    TokenBasedSecurityFilter.TOKEN_LONG_LIFETIME,
                    (Date) null, true, true);

            String redirectTarget = oidcState.getReturnUrl();
            if (redirectTarget == null || !redirectTarget.startsWith("/#/")) {
                redirectTarget = "/#/document";
            }

            return Response.temporaryRedirect(URI.create(redirectTarget))
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

        List<RSAPublicKey> candidates = getSigningKeys(kid);
        if (candidates.isEmpty()) {
            candidates = refreshJwksAndRetry(kid);
        }
        if (candidates.isEmpty()) {
            throw new Exception("No matching key found in JWKS for kid=" + kid);
        }

        String issuer = System.getProperty(PROP_ISSUER);
        String audience = System.getProperty(PROP_CLIENT_ID);

        Exception lastException = null;
        for (RSAPublicKey publicKey : candidates) {
            try {
                Algorithm algo = Algorithm.RSA256(publicKey, null);
                JWTVerifier verifier = JWT.require(algo)
                        .withIssuer(issuer)
                        .withAudience(audience)
                        .build();
                return verifier.verify(idTokenStr);
            } catch (Exception e) {
                lastException = e;
            }
        }

        // All cached keys failed -- try one JWKS refresh before giving up
        candidates = refreshJwksAndRetry(kid);
        for (RSAPublicKey publicKey : candidates) {
            try {
                Algorithm algo = Algorithm.RSA256(publicKey, null);
                JWTVerifier verifier = JWT.require(algo)
                        .withIssuer(issuer)
                        .withAudience(audience)
                        .build();
                return verifier.verify(idTokenStr);
            } catch (Exception e) {
                lastException = e;
            }
        }

        throw new Exception("ID token verification failed against all JWKS keys"
                + " (including refresh)", lastException);
    }

    /**
     * Clears the JWKS cache and re-fetches, rate-limited to once per minute.
     */
    private List<RSAPublicKey> refreshJwksAndRetry(String kid) throws Exception {
        long now = System.currentTimeMillis();
        if (now - jwksLastRefreshMs < JWKS_MIN_REFRESH_INTERVAL_MS) {
            log.debug("JWKS refresh skipped (rate limited)");
            return List.of();
        }
        log.info("Refreshing JWKS cache (kid={} not found or verification failed)", kid);
        jwksCache = null;
        jwksLastRefreshMs = now;
        return getSigningKeys(kid);
    }

    /**
     * Fetches RSA public keys from the provider's JWKS.
     * When kid is non-null, returns at most one key matching that kid.
     * When kid is null, returns all eligible RSA signing keys.
     */
    private List<RSAPublicKey> getSigningKeys(String kid) throws Exception {
        JsonObject jwks = getJwks();
        JsonArray keys = jwks.getJsonArray("keys");
        List<RSAPublicKey> result = new ArrayList<>();

        for (int i = 0; i < keys.size(); i++) {
            JsonObject key = keys.getJsonObject(i);

            String kty = key.getString("kty", null);
            if (!"RSA".equals(kty)) {
                continue;
            }
            String use = key.getString("use", null);
            if (use != null && !"sig".equals(use)) {
                continue;
            }
            String alg = key.getString("alg", null);
            if (alg != null && !"RS256".equals(alg)) {
                continue;
            }

            if (kid != null && !kid.equals(key.getString("kid", null))) {
                continue;
            }

            String n = key.getString("n");
            String e = key.getString("e");

            byte[] nBytes = Base64.getUrlDecoder().decode(n);
            byte[] eBytes = Base64.getUrlDecoder().decode(e);
            RSAPublicKeySpec spec = new RSAPublicKeySpec(
                    new BigInteger(1, nBytes), new BigInteger(1, eBytes));

            result.add((RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec));

            if (kid != null) {
                break;
            }
        }
        return result;
    }

    private JsonObject exchangeCodeForTokens(String code, String codeVerifier) throws Exception {
        String tokenEndpoint = getTokenEndpoint();
        String clientId = System.getProperty(PROP_CLIENT_ID);
        String clientSecret = System.getProperty(PROP_CLIENT_SECRET);
        String redirectUri = System.getProperty(PROP_REDIRECT_URI);

        FormBody.Builder bodyBuilder = new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", redirectUri)
                .add("client_id", clientId)
                .add("client_secret", clientSecret);
        if (codeVerifier != null) {
            bodyBuilder.add("code_verifier", codeVerifier);
        }
        FormBody body = bodyBuilder.build();

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

    private User provisionUser(UserDao userDao, String preferredUsername, String email, String subject, String issuer) {
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
        user.setOidcIssuer(issuer);
        user.setOidcSubject(subject);
        user.setStorageQuota(Long.parseLong(ofNullable(System.getenv(Constants.GLOBAL_QUOTA_ENV))
                .orElse("1073741824")));
        user.setPassword(UUID.randomUUID().toString());
        user.setOnboarding(true);

        try {
            userDao.create(user, username);
            log.info("Provisioned new OIDC user: {} (issuer={}, sub={})", username, issuer, subject);
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

            log.info("OIDC configuration: issuer={}, client_id={}, redirect_uri={}, secret=[REDACTED]",
                    issuer, clientId, redirectUri);

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
                JsonObject discovery;
                try (JsonReader reader = Json.createReader(new StringReader(body))) {
                    discovery = reader.readObject();
                }

                String discoveredIssuer = discovery.getString("issuer", null);
                if (!issuer.equals(discoveredIssuer)) {
                    throw new Exception("OIDC discovery issuer mismatch: configured="
                            + issuer + ", discovered=" + discoveredIssuer);
                }

                discoveryCache = discovery;
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

    /**
     * Returns the end_session_endpoint from discovery, or null if not supported.
     */
    static String getEndSessionEndpoint() {
        try {
            if (!isOidcEnabled() || discoveryCache == null) {
                return null;
            }
            return discoveryCache.getString("end_session_endpoint", null);
        } catch (Exception e) {
            return null;
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

    private static String generateCodeVerifier() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String computeCodeChallenge(String codeVerifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

}
