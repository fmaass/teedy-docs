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
import com.sismics.util.context.ThreadLocalContext;
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
import java.time.Duration;
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

    /**
     * Total per-call bound so a slow-drip IdP cannot pin a Jetty thread indefinitely.
     * connect/read/write timeouts cap individual socket phases; callTimeout caps the
     * whole call (DNS + connect + all redirects + reading the body).
     */
    static final long HTTP_CALL_TIMEOUT_SECONDS = 10;
    static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(HTTP_CALL_TIMEOUT_SECONDS))
            .readTimeout(Duration.ofSeconds(HTTP_CALL_TIMEOUT_SECONDS))
            .writeTimeout(Duration.ofSeconds(HTTP_CALL_TIMEOUT_SECONDS))
            .callTimeout(Duration.ofSeconds(HTTP_CALL_TIMEOUT_SECONDS))
            .build();

    private static final long STATE_TTL_MS = 10 * 60 * 1000;

    private static volatile JsonObject discoveryCache;
    private static volatile JsonObject jwksCache;
    private static volatile long jwksLastRefreshMs = 0;
    private static final long JWKS_MIN_REFRESH_INTERVAL_MS = 60 * 1000;
    private static volatile boolean configValidated = false;

    /**
     * Test seam: clears cached config/discovery so a test can exercise the
     * misconfiguration path after a valid config has already been validated.
     */
    static void resetConfigCacheForTest() {
        configValidated = false;
        discoveryCache = null;
        jwksCache = null;
    }

    /**
     * Shared account-eligibility gate for the OIDC callback: an administratively disabled
     * account must not be granted an auth token or cookie. This delegates to the single
     * {@link User#isDisabled()} predicate used by every authentication path (cookie, API key,
     * header) so disabled-user enforcement has one source of truth.
     *
     * @param user Resolved OIDC user (never null at the call site)
     * @return True if the account may be granted a session
     */
    public static boolean isOidcUserEligible(User user) {
        return user != null && !user.isDisabled();
    }

    private static final String PROP_ENABLED = "docs.oidc_enabled";
    private static final String PROP_ISSUER = "docs.oidc_issuer";
    private static final String PROP_CLIENT_ID = "docs.oidc_client_id";
    private static final String PROP_CLIENT_SECRET = "docs.oidc_client_secret";
    private static final String PROP_REDIRECT_URI = "docs.oidc_redirect_uri";
    private static final String PROP_SCOPE = "docs.oidc_scope";
    private static final String PROP_AUTH_ENDPOINT = "docs.oidc_authorization_endpoint";
    private static final String PROP_TOKEN_ENDPOINT = "docs.oidc_token_endpoint";
    private static final String PROP_JWKS_URI = "docs.oidc_jwks_uri";
    private static final String PROP_USERINFO_ENDPOINT = "docs.oidc_userinfo_endpoint";
    private static final String PROP_USERNAME_CLAIM = "docs.oidc_username_claim";
    private static final String PROP_EMAIL_CLAIM = "docs.oidc_email_claim";

    private static final String DEFAULT_USERNAME_CLAIM = "preferred_username";
    private static final String DEFAULT_EMAIL_CLAIM = "email";

    private static final String USERNAME_PATTERN = "^[a-zA-Z0-9_]{3,50}$";

    /**
     * Allowed characters in the pre-hash username stem before non-conforming runs are
     * collapsed to {@code _}. {@code preferred_username} may legitimately carry
     * {@code @}, {@code /}, whitespace or Unicode (OIDC Core §5.1), none of which
     * Teedy's username charset ({@link #USERNAME_PATTERN}) permits, so the stem is
     * sanitized to this set and then suffixed with a deterministic hash.
     */
    private static final int USERNAME_MAX_LENGTH = 50;
    /** Length of the lowercase-hex SHA-256 disambiguation suffix appended to every OIDC username. */
    private static final int USERNAME_HASH_LEN = 12;

    @GET
    @Path("login")
    public Response login(@QueryParam("returnUrl") String returnUrl) {
        if (!isOidcEnabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String configError = validateConfig();
        if (configError != null) {
            // Redirect to the SPA login error page instead of a raw 500. A 500 at the
            // login entry point auto-redirects the browser back into a loop; the error
            // page renders a message and offers a route back to the local login form.
            log.error("OIDC misconfigured: {}", configError);
            return Response.temporaryRedirect(URI.create("/#/login?error=oidc")).build();
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
            return Response.temporaryRedirect(URI.create("/#/login?error=oidc")).build();
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
                return Response.temporaryRedirect(URI.create("/#/login?error=oidc")).build();
            }

            DecodedJWT idToken = verifyIdToken(idTokenStr);

            // Verify nonce matches what we sent in the authorization request (fail closed).
            // Never log the nonce values themselves (session-linking secret).
            String tokenNonce = getClaimAsString(idToken, "nonce");
            if (expectedNonce == null || !expectedNonce.equals(tokenNonce)) {
                log.error("OIDC nonce mismatch");
                return Response.temporaryRedirect(URI.create("/#/login?error=oidc")).build();
            }

            String subject = idToken.getSubject();
            String issuer = System.getProperty(PROP_ISSUER);

            // `sub` is REQUIRED by OIDC Core §2. Fail closed on a missing subject: without it
            // the identity lookup by (issuer, subject) is impossible, and provisioning an
            // unbound user (oidc_subject NULL) never collides in IDX_USER_OIDC (NULLs don't
            // collide), so every such login would mint yet another orphan account.
            if (StringUtils.isBlank(subject)) {
                log.error("OIDC token carries no sub claim; rejecting login (sub is REQUIRED per OIDC Core §2)");
                return Response.temporaryRedirect(URI.create("/#/login?error=oidc")).build();
            }

            String accessToken = tokenResponse.getString("access_token", null);
            String usernameClaimName = getUsernameClaim();
            String emailClaimName = getEmailClaim();

            // Read the configured claims from the ID token first; a provider that ships a
            // minimal ID token (default Authelia >=4.38) delivers them via UserInfo instead,
            // so fall back to a sub-verified UserInfo call when a configured claim is absent.
            // Fail closed: an ATTEMPTED UserInfo fetch that fails (HTTP error, bad content,
            // sub mismatch) rejects the login — only "no endpoint known" (null) or a
            // sub-verified response without the claim proceed on the WARN fallback.
            String usernameClaim = getClaimAsString(idToken, usernameClaimName);
            String email = getClaimAsString(idToken, emailClaimName);
            if (usernameClaim == null || email == null) {
                JsonObject userInfo;
                try {
                    userInfo = fetchUserInfo(accessToken, subject);
                } catch (UserInfoException e) {
                    log.error("OIDC UserInfo fetch failed; rejecting login (fail closed): {}", e.getMessage());
                    return Response.temporaryRedirect(URI.create("/#/login?error=oidc")).build();
                }
                if (userInfo != null) {
                    if (usernameClaim == null) {
                        usernameClaim = getJsonString(userInfo, usernameClaimName);
                    }
                    if (email == null) {
                        email = getJsonString(userInfo, emailClaimName);
                    }
                }
            }

            // Per-login PII (sub/email/username claim) is DEBUG-only.
            log.debug("OIDC login: sub={}, usernameClaim={}, email={}", subject, usernameClaim, email);

            UserDao userDao = new UserDao();

            // First: stable lookup by OIDC subject (prevents email-based account takeover)
            User user = userDao.getByOidcSubject(issuer, subject);
            boolean repeatLogin = user != null;

            // Security: do NOT auto-link to existing local accounts by username/email.
            // First OIDC login always provisions a new user to prevent account takeover.
            if (!repeatLogin) {
                user = provisionOrRecover(userDao, usernameClaim, email, subject, issuer);
                if (user == null) {
                    // The OIDC subject is PII and must stay out of ERROR (DEBUG-only policy).
                    log.error("Failed to provision OIDC user (subject at DEBUG)");
                    log.debug("Failed to provision OIDC user: sub={}", subject);
                    return Response.temporaryRedirect(URI.create("/#/login?error=oidc")).build();
                }
            }

            // SINGLE eligibility chokepoint: reject an administratively disabled account
            // unconditionally once the user is FINAL — however it was resolved. The lookup
            // path can return a later-disabled identity, and provisionOrRecover's
            // conflict-recovery branch returns a re-read PRE-EXISTING row (the race winner),
            // which is not guaranteed enabled either. This runs BEFORE any profile mutation
            // (the rejection redirect is a 3xx, so the request transaction COMMITS — a prior
            // email refresh would silently persist) and BEFORE minting a token or cookie.
            if (!isOidcUserEligible(user)) {
                // The OIDC subject is PII and must stay out of WARN (DEBUG-only policy).
                log.warn("OIDC login refused for a disabled account");
                log.debug("OIDC login refused for disabled account: sub={}", subject);
                return Response.temporaryRedirect(URI.create("/#/login?error=oidc")).build();
            }

            if (repeatLogin) {
                // Repeat login: refresh the stored email from a fresh, valid claim. Username is
                // immutable (workflow blobs store USER targets by username). The synthetic
                // <username>@oidc.local fallback is provisioning-only and must never overwrite a
                // real stored address when the claim is temporarily absent (profile-data-loss guard).
                maybeUpdateEmail(userDao, user, email);
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

            // SameSite=Lax (not Strict): the cookie is set on a top-level redirect
            // back from the IdP, and Strict would drop it on that cross-site navigation.
            NewCookie cookie = new NewCookie.Builder(TokenBasedSecurityFilter.COOKIE_NAME)
                    .value(tokenValue)
                    .path("/")
                    .maxAge(TokenBasedSecurityFilter.TOKEN_LONG_LIFETIME)
                    .secure(true)
                    .httpOnly(true)
                    .sameSite(NewCookie.SameSite.LAX)
                    .build();

            String redirectTarget = oidcState.getReturnUrl();
            if (redirectTarget == null || !redirectTarget.startsWith("/#/")) {
                redirectTarget = "/#/document";
            }

            return Response.temporaryRedirect(URI.create(redirectTarget))
                    .cookie(cookie)
                    .build();
        } catch (Exception e) {
            log.error("Error processing OIDC callback", e);
            return Response.temporaryRedirect(URI.create("/#/login?error=oidc")).build();
        }
    }

    /**
     * Verifies the ID token signature using the provider's JWKS, and validates issuer + audience.
     */
    private DecodedJWT verifyIdToken(String idTokenStr) throws Exception {
        DecodedJWT unverified = JWT.decode(idTokenStr);
        String kid = unverified.getKeyId();

        // Require the exp claim to be present. OIDC mandates it; JWT.require only
        // validates expiry when the claim exists, so a token with no exp would
        // otherwise be accepted as never-expiring.
        if (unverified.getExpiresAt() == null) {
            throw new Exception("ID token missing required exp claim");
        }

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
                        .acceptExpiresAt(60)
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
                        .acceptExpiresAt(60)
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

    // ---------------------------------------------------------------------------------------------
    // Claim configuration
    // ---------------------------------------------------------------------------------------------

    /** The configured username claim name, defaulting to {@code preferred_username}. */
    private static String getUsernameClaim() {
        return ofNullable(System.getProperty(PROP_USERNAME_CLAIM))
                .filter(s -> !s.isBlank()).orElse(DEFAULT_USERNAME_CLAIM);
    }

    /** The configured email claim name, defaulting to {@code email}. */
    private static String getEmailClaim() {
        return ofNullable(System.getProperty(PROP_EMAIL_CLAIM))
                .filter(s -> !s.isBlank()).orElse(DEFAULT_EMAIL_CLAIM);
    }

    // ---------------------------------------------------------------------------------------------
    // UserInfo fallback (Authelia >=4.38 minimal-ID-token case)
    // ---------------------------------------------------------------------------------------------

    /**
     * Upper bound on an accepted UserInfo response body: real UserInfo documents are a few
     * hundred bytes; anything larger is either a misdirected endpoint or abuse.
     */
    static final int MAX_USERINFO_RESPONSE_BYTES = 64 * 1024;

    /**
     * Signals that a required UserInfo fetch was ATTEMPTED and failed — endpoint resolution
     * error, HTTP/transport failure, unsupported content type, oversized or unparseable
     * body, missing access token, or a {@code sub} mismatch. The callback treats this as a
     * hard login rejection (fail closed): claims the flow needs could not be obtained
     * trustworthily, so no session may be minted and nothing provisioned/updated.
     */
    static final class UserInfoException extends Exception {
        UserInfoException(String message) {
            super(message);
        }

        UserInfoException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Fetches the UserInfo document with the access token and verifies its {@code sub}
     * exactly equals the verified ID-token {@code sub} (OIDC Core §5.3.2).
     *
     * <p>Contract (fail closed): only two outcomes let the login proceed — a successful,
     * sub-verified response (returned), or NO UserInfo endpoint being known at all (returns
     * null; the caller falls back with a WARN). Every ATTEMPTED-but-failed path — endpoint
     * resolution failure, missing access token with a known endpoint, HTTP error, wrong
     * content type, oversized body, parse failure, or {@code sub} mismatch — throws
     * {@link UserInfoException} and MUST reject the login: proceeding after a failed fetch
     * would mint a session on unverified or another subject's profile data.
     *
     * @param accessToken Access token from the token response
     * @param expectedSub The verified ID-token subject the UserInfo sub MUST equal
     * @return The UserInfo JSON object, or null when no endpoint is known (WARN fallback)
     * @throws UserInfoException When the fetch was attempted (or required) and failed
     */
    private JsonObject fetchUserInfo(String accessToken, String expectedSub) throws UserInfoException {
        String endpoint;
        String explicit = System.getProperty(PROP_USERINFO_ENDPOINT);
        if (explicit != null && !explicit.isBlank()) {
            endpoint = explicit;
        } else {
            try {
                endpoint = getDiscovery().getString("userinfo_endpoint", null);
            } catch (Exception e) {
                throw new UserInfoException("could not resolve the UserInfo endpoint from discovery", e);
            }
        }
        if (StringUtils.isBlank(endpoint)) {
            // The only WARN-fallback case: no endpoint is known (no property, no discovery entry).
            log.warn("OIDC UserInfo fallback unavailable: provider advertises no userinfo_endpoint");
            return null;
        }
        if (StringUtils.isBlank(accessToken)) {
            throw new UserInfoException("token response carried no access_token for the required UserInfo fetch");
        }

        Request req = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + accessToken)
                .get()
                .build();
        try (okhttp3.Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new UserInfoException("UserInfo fetch failed: HTTP " + resp.code());
            }
            if (!isJsonContentType(resp.header("Content-Type"))) {
                throw new UserInfoException("UserInfo response has unsupported Content-Type: "
                        + resp.header("Content-Type"));
            }
            // Bound the body in RAW BYTES before decoding (peek one byte past the cap to detect
            // overflow). Comparing decoded char count would under-count a multibyte body and let
            // an oversized response through, so measure the encoded byte length instead.
            okhttp3.ResponseBody peeked = resp.peekBody(MAX_USERINFO_RESPONSE_BYTES + 1L);
            byte[] bytes = peeked.bytes();
            if (bytes.length > MAX_USERINFO_RESPONSE_BYTES) {
                throw new UserInfoException("UserInfo response exceeds " + MAX_USERINFO_RESPONSE_BYTES + " bytes");
            }
            String body = new String(bytes, StandardCharsets.UTF_8);
            JsonObject userInfo;
            try (JsonReader reader = Json.createReader(new StringReader(body))) {
                userInfo = reader.readObject();
            } catch (Exception e) {
                throw new UserInfoException("UserInfo response is not a valid JSON object", e);
            }
            // Fail closed: the UserInfo sub MUST match the verified ID-token sub.
            String userInfoSub = getJsonString(userInfo, "sub");
            if (userInfoSub == null || !userInfoSub.equals(expectedSub)) {
                throw new UserInfoException("UserInfo sub mismatch (fail closed)");
            }
            return userInfo;
        } catch (UserInfoException e) {
            throw e;
        } catch (Exception e) {
            throw new UserInfoException("UserInfo fetch failed", e);
        }
    }

    /**
     * True when the response Content-Type is JSON: the {@code application/json} media type or
     * any {@code +json}-suffixed structured type (RFC 6839), e.g. {@code application/hal+json}.
     * The media type is parsed by stripping any parameters ({@code ; charset=...}) and matching
     * on the bare type, rather than a substring {@code contains} that would also accept
     * unrelated types such as {@code text/notapplication/json-ish}.
     *
     * @param contentType Raw Content-Type header value (may be null)
     * @return true when the media type is JSON or a +json suffix type
     */
    static boolean isJsonContentType(String contentType) {
        if (StringUtils.isBlank(contentType)) {
            return false;
        }
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase();
        return mediaType.equals("application/json") || mediaType.endsWith("+json");
    }

    // ---------------------------------------------------------------------------------------------
    // Username derivation
    // ---------------------------------------------------------------------------------------------

    /**
     * Normalizes a raw claim value into a Teedy username stem: NFKC-normalize, trim, and
     * collapse every run of characters outside {@code [A-Za-z0-9_@.-]} into a single
     * {@code _}, then reduce the {@code @ . -} to {@code _} so the stem only carries the
     * final {@link #USERNAME_PATTERN} charset ({@code [A-Za-z0-9_]}). Returns null when the
     * result would be empty. The stem is truncated so the mandatory hash suffix fits within
     * {@link #USERNAME_MAX_LENGTH}.
     *
     * @param raw Raw claim value (may contain {@code @ /} whitespace, Unicode — Core §5.1)
     * @return A sanitized stem in the username charset, or null when nothing survives
     */
    static String sanitizeUsernameStem(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC).trim();
        // Collapse anything outside the username charset into '_'.
        String stem = normalized.replaceAll("[^A-Za-z0-9_]+", "_");
        // Trim leading/trailing underscores introduced by the collapse.
        stem = stem.replaceAll("^_+", "").replaceAll("_+$", "");
        if (stem.isEmpty()) {
            return null;
        }
        // Reserve room for the '_' separator + hash suffix.
        int stemBudget = USERNAME_MAX_LENGTH - 1 - USERNAME_HASH_LEN;
        if (stem.length() > stemBudget) {
            stem = stem.substring(0, stemBudget);
        }
        return stem;
    }

    /**
     * Derives a deterministic, collision-resistant username: the sanitized stem plus an
     * underscore and a lowercase-hex SHA-256 prefix of {@code issuer + NUL + subject}. The
     * hash makes the username a function of the stable OIDC identity, so two different
     * subjects that sanitize to the same stem never contend for one username (no
     * order-dependent ownership of a popular name), and the same identity always derives
     * the same username. On a residual hash-prefix collision the prefix is extended.
     *
     * @param stem Sanitized username stem (never null/empty)
     * @param issuer OIDC issuer
     * @param subject OIDC subject
     * @param hashLen Length of the hash prefix to append
     * @return The derived username, or null if it cannot fit the pattern
     */
    static String deriveUsername(String stem, String issuer, String subject, int hashLen) {
        String fullHash = sha256Hex(issuer + "\0" + subject);
        int len = Math.min(hashLen, fullHash.length());
        String suffix = fullHash.substring(0, len);
        // Truncate the stem so stem + '_' + suffix fits USERNAME_MAX_LENGTH.
        int stemBudget = USERNAME_MAX_LENGTH - 1 - suffix.length();
        String s = stem;
        if (s.length() > stemBudget) {
            s = s.substring(0, Math.max(0, stemBudget));
        }
        // A pathological all-collapse stem could be empty after truncation; fall back to "u".
        if (s.isEmpty()) {
            s = "u";
        }
        String candidate = s + "_" + suffix;
        return candidate.matches(USERNAME_PATTERN) ? candidate : null;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Provisioning + concurrent-first-login recovery
    // ---------------------------------------------------------------------------------------------

    /**
     * Provisions a new OIDC user, recovering from a concurrent first login. The provisioning
     * runs on a FRESH transaction ({@link #provisionUserFreshTx}) because the unique-index
     * violation on {@code IDX_USER_OIDC} surfaces at flush and marks that transaction
     * rollback-only (PostgreSQL aborts it), so an in-transaction re-read cannot recover.
     * On the loser side we re-read {@code (issuer, subject)} on ANOTHER clean transaction and
     * continue with the winner row — never retrying the whole callback (whose single-use code
     * would fail the repeated token exchange with {@code invalid_grant}).
     */
    private User provisionOrRecover(UserDao userDao, String usernameClaim, String email, String subject, String issuer) {
        String stem = sanitizeUsernameStem(usernameClaim);
        if (stem == null) {
            stem = sanitizeUsernameStem(email);
        }
        if (stem == null) {
            stem = sanitizeUsernameStem(subject);
        }
        if (stem == null) {
            stem = "user";
        }

        String userEmail = (email != null && isValidEmail(email)) ? email : stem + "@oidc.local";
        if (!isValidEmail(userEmail)) {
            log.warn("OIDC provisioning rejected: invalid email");
            return null;
        }

        try {
            return provisionUserFreshTx(stem, userEmail, subject, issuer);
        } catch (ConstraintViolationRetry retry) {
            // A concurrent first login won the IDX_USER_OIDC insert. Re-read on a clean
            // transaction and continue with the winner.
            User winner = readOidcUserFreshTx(issuer, subject);
            if (winner != null) {
                log.info("OIDC concurrent first-login converged on the winner row (subject at DEBUG)");
                log.debug("OIDC concurrent first-login converged on the winner row: sub={}", subject);
                return winner;
            }
            log.error("OIDC unique-conflict recovery found no winner row (subject at DEBUG)");
            log.debug("OIDC unique-conflict recovery found no winner row: sub={}", subject);
            return null;
        } catch (Exception e) {
            log.error("Error creating OIDC user", e);
            return null;
        }
    }

    /**
     * Marker used to signal a {@code (issuer, subject)} unique-index conflict up to the
     * recovery path without leaking the persistence exception type.
     */
    private static final class ConstraintViolationRetry extends RuntimeException {
        ConstraintViolationRetry(Throwable cause) {
            super(cause);
        }
    }

    /**
     * Runs {@code work} on a NEW EntityManager + resource-local transaction, installed in
     * ThreadLocalContext for the duration and then restored to the request EntityManager.
     * The request transaction stays open across this (it is needed afterwards for token
     * creation), so we do NOT use {@link com.sismics.docs.core.util.TransactionUtil#handle}
     * (which reuses the request EM) nor {@link ThreadLocalContext#cleanup} (which would
     * discard the request context and its queued events).
     */
    private <T> T runOnFreshTransaction(java.util.function.Function<jakarta.persistence.EntityManager, T> work) {
        ThreadLocalContext context = ThreadLocalContext.get();
        jakarta.persistence.EntityManager requestEm = context.getEntityManager();
        jakarta.persistence.EntityManager freshEm = com.sismics.util.jpa.EMF.get().createEntityManager();
        // Everything after creation runs under the finally: a failure in getTransaction()
        // or begin() must still close the fresh EM and restore the request EM (no leak).
        jakarta.persistence.EntityTransaction tx = null;
        try {
            context.setEntityManager(freshEm);
            tx = freshEm.getTransaction();
            tx.begin();
            T result = work.apply(freshEm);
            tx.commit();
            return result;
        } finally {
            try {
                if (tx != null && tx.isActive()) {
                    tx.rollback();
                }
            } catch (Exception e) {
                log.error("Error rolling back OIDC provisioning transaction", e);
            }
            try {
                freshEm.close();
            } catch (Exception e) {
                log.error("Error closing OIDC provisioning entity manager", e);
            }
            context.setEntityManager(requestEm);
        }
    }

    /**
     * Provisions the user on a fresh transaction. The commit is where a concurrent insert
     * of the same {@code (issuer, subject)} surfaces the {@code IDX_USER_OIDC} violation; we
     * detect it and translate to {@link ConstraintViolationRetry} for the recovery path.
     * On a residual username collision (distinct subjects sanitizing to the same stem AND
     * the same hash prefix) the hash suffix is extended deterministically before retrying.
     */
    private User provisionUserFreshTx(String stem, String userEmail, String subject, String issuer) {
        int hashLen = USERNAME_HASH_LEN;
        while (hashLen <= 64) {
            String username = deriveUsername(stem, issuer, subject, hashLen);
            if (username == null) {
                log.warn("OIDC provisioning rejected: could not derive a valid username");
                return null;
            }
            try {
                final String finalUsername = username;
                return runOnFreshTransaction(em -> {
                    User user = new User();
                    user.setRoleId(Constants.DEFAULT_USER_ROLE);
                    user.setUsername(finalUsername);
                    user.setEmail(userEmail);
                    user.setOidcIssuer(issuer);
                    user.setOidcSubject(subject);
                    user.setStorageQuota(Long.parseLong(ofNullable(System.getenv(Constants.GLOBAL_QUOTA_ENV))
                            .orElse("1073741824")));
                    user.setPassword(UUID.randomUUID().toString());
                    user.setOnboarding(true);
                    try {
                        new UserDao().create(user, finalUsername);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    log.info("Provisioned new OIDC user: {} (subject at DEBUG)", finalUsername);
                    log.debug("Provisioned new OIDC user: {} (issuer={}, sub={})", finalUsername, issuer, subject);
                    return user;
                });
            } catch (RuntimeException e) {
                if (isOidcSubjectConflict(e)) {
                    throw new ConstraintViolationRetry(e);
                }
                if (isUsernameConflict(e)) {
                    // Distinct subject hashed to the same stem+prefix: lengthen and retry.
                    hashLen += 4;
                    log.warn("OIDC username collision on '{}', extending hash prefix", username);
                    continue;
                }
                throw e;
            }
        }
        log.error("OIDC provisioning exhausted username disambiguation (subject at DEBUG)");
        log.debug("OIDC provisioning exhausted username disambiguation for sub={}", subject);
        return null;
    }

    private User readOidcUserFreshTx(String issuer, String subject) {
        try {
            return runOnFreshTransaction(em -> new UserDao().getByOidcSubject(issuer, subject));
        } catch (Exception e) {
            log.error("Error re-reading OIDC user during conflict recovery", e);
            return null;
        }
    }

    /**
     * True when the throwable chain indicates a violation of the {@code IDX_USER_OIDC}
     * unique index on {@code (USE_OIDC_ISSUER_C, USE_OIDC_SUBJECT_C)}.
     */
    private static boolean isOidcSubjectConflict(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String msg = c.getMessage();
            if (msg != null && msg.toUpperCase().contains("IDX_USER_OIDC")) {
                return true;
            }
        }
        return false;
    }

    /** True when the throwable chain is the username-unicity precheck from {@link UserDao#create}. */
    private static boolean isUsernameConflict(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if ("AlreadyExistingUsername".equals(c.getMessage())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Refreshes a stored user's email from a fresh, valid claim on repeat login. A blank or
     * invalid claim (or an absent one, arriving here as null) leaves the stored address
     * untouched — the synthetic {@code <stem>@oidc.local} fallback is provisioning-only and
     * must never overwrite a real address when the claim is temporarily absent.
     *
     * <p>A genuine persistence failure PROPAGATES and fails the callback (error redirect):
     * the pre-write guard above already decided the claim is valid, so a failed write must
     * not be swallowed into a session carrying a silently-stale profile.
     */
    private void maybeUpdateEmail(UserDao userDao, User user, String email) {
        if (email == null || !isValidEmail(email)) {
            return;
        }
        if (email.equals(user.getEmail())) {
            return;
        }
        user.setEmail(email);
        userDao.update(user, user.getId());
        log.info("Updated OIDC user email on repeat login (username={})", user.getUsername());
    }

    private static boolean isValidEmail(String email) {
        return email != null && email.indexOf('@') >= 1 && email.indexOf('@') < email.length() - 1;
    }

    private static String getJsonString(JsonObject obj, String key) {
        if (obj == null || !obj.containsKey(key) || obj.isNull(key)) {
            return null;
        }
        try {
            return obj.getString(key);
        } catch (ClassCastException e) {
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
