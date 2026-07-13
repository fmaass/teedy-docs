package com.sismics.docs.rest.resource;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.dao.AuthenticationTokenDao;
import com.sismics.docs.core.dao.ConfigDao;
import com.sismics.docs.core.dao.OidcStateDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.AuthenticationToken;
import com.sismics.docs.core.model.jpa.Config;
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

    /**
     * VALUE-KEYED caches (not invalidated on config save): a config change is picked up by the
     * NEXT login with no restart and no invalidation hook, and a concurrent login DURING a save
     * cannot pin stale config. Save-time invalidation would race the request-transaction commit
     * — a reader between the property write and the DB commit could refill a cache under the old
     * effective config — so instead every entry is keyed by the EFFECTIVE value it was derived
     * from. When the effective config changes, the derived keys change and the old entries are
     * simply never read again (bounded: one entry per distinct discovery URL / jwks URI seen).
     */
    private static final Map<String, JsonObject> discoveryCache = new ConcurrentHashMap<>();
    private static final Map<String, JsonObject> jwksCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> jwksLastRefreshMs = new ConcurrentHashMap<>();
    private static final long JWKS_MIN_REFRESH_INTERVAL_MS = 60 * 1000;

    /**
     * Validation memo keyed by a hash of all effective config values. {@code validateConfig}
     * records the hash of the config it last validated OK; a login whose effective config hashes
     * to the same value skips re-validation, but ANY change (a DB override saved, a property
     * flipped) yields a new hash and forces a fresh validation. Replaces the old
     * process-lifetime {@code configValidated} boolean, which could never see a config change.
     */
    private static volatile String validatedConfigHash;

    /**
     * Test seam: clears the value-keyed caches and the validation memo. Retained with the same
     * name/signature for existing tests; with value-keyed caches this is no longer required for
     * correctness across a config change (the keys change on their own), but it keeps a test's
     * process-global state isolated from the next test.
     */
    static void resetConfigCacheForTest() {
        validatedConfigHash = null;
        discoveryCache.clear();
        jwksCache.clear();
        jwksLastRefreshMs.clear();
    }

    /**
     * Test seam: seeds (or clears, when {@code discovery} is null) the value-keyed discovery cache
     * under the CURRENT effective discovery URL, so a test can inject a discovery document without
     * a live IdP. A null argument clears the whole cache. Replaces direct reflection on the old
     * scalar {@code discoveryCache} field (the cache is now a value-keyed map).
     */
    static void setDiscoveryCacheForTest(JsonObject discovery) {
        if (discovery == null) {
            discoveryCache.clear();
            return;
        }
        // Key the injected discovery under the current effective discovery URL (a snapshot read of
        // the issuer), matching how the request paths look it up.
        String discoveryUrl = snapshot().discoveryUrl();
        if (discoveryUrl != null) {
            discoveryCache.put(discoveryUrl, discovery);
        }
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

    private static final String DEFAULT_USERNAME_CLAIM = "preferred_username";
    private static final String DEFAULT_EMAIL_CLAIM = "email";
    private static final String DEFAULT_SCOPE = "openid profile email";

    /**
     * The OIDC configuration keys. Each binds a {@link ConfigType} (DB-backed value),
     * the legacy {@code docs.oidc_*} system property, and a built-in default (nullable). This
     * is the SINGLE source of truth for the key set — the accessor, the endpoints, the caches,
     * and the source-scan completeness guard all enumerate it.
     */
    enum OidcKey {
        ENABLED(ConfigType.OIDC_ENABLED, "docs.oidc_enabled", "false"),
        ISSUER(ConfigType.OIDC_ISSUER, "docs.oidc_issuer", null),
        CLIENT_ID(ConfigType.OIDC_CLIENT_ID, "docs.oidc_client_id", null),
        CLIENT_SECRET(ConfigType.OIDC_CLIENT_SECRET, "docs.oidc_client_secret", null),
        REDIRECT_URI(ConfigType.OIDC_REDIRECT_URI, "docs.oidc_redirect_uri", null),
        SCOPE(ConfigType.OIDC_SCOPE, "docs.oidc_scope", DEFAULT_SCOPE),
        AUTHORIZATION_ENDPOINT(ConfigType.OIDC_AUTHORIZATION_ENDPOINT, "docs.oidc_authorization_endpoint", null),
        TOKEN_ENDPOINT(ConfigType.OIDC_TOKEN_ENDPOINT, "docs.oidc_token_endpoint", null),
        JWKS_URI(ConfigType.OIDC_JWKS_URI, "docs.oidc_jwks_uri", null),
        USERINFO_ENDPOINT(ConfigType.OIDC_USERINFO_ENDPOINT, "docs.oidc_userinfo_endpoint", null),
        USERNAME_CLAIM(ConfigType.OIDC_USERNAME_CLAIM, "docs.oidc_username_claim", DEFAULT_USERNAME_CLAIM),
        EMAIL_CLAIM(ConfigType.OIDC_EMAIL_CLAIM, "docs.oidc_email_claim", DEFAULT_EMAIL_CLAIM),
        USERNAME_VERBATIM(ConfigType.OIDC_USERNAME_VERBATIM, "docs.oidc_username_verbatim", "false");

        private final ConfigType configType;
        private final String propertyName;
        private final String defaultValue;

        OidcKey(ConfigType configType, String propertyName, String defaultValue) {
            this.configType = configType;
            this.propertyName = propertyName;
            this.defaultValue = defaultValue;
        }

        ConfigType configType() {
            return configType;
        }

        String propertyName() {
            return propertyName;
        }
    }

    /**
     * The SINGLE accessor for every OIDC configuration value (the one chokepoint). Precedence:
     * a non-blank DB value (T_CONFIG) wins; else the {@code docs.oidc_*} system property; else the
     * built-in default. A BLANK/empty DB value is UNSET — it falls through to the property tier
     * and never overrides with emptiness. This is the ONLY method in this class permitted to read
     * a {@code docs.oidc_*} property; {@link com.sismics.docs.rest.resource.TestOidcAccessorGuard}
     * fails the build on any other {@code System.getProperty("docs.oidc_...")} read.
     *
     * @param key The configuration key
     * @return The effective value, or null when unset with no default
     */
    static String oidcConfig(OidcKey key) {
        ConfigDao configDao = new ConfigDao();
        Config config = configDao.getById(key.configType());
        String dbValue = config == null ? null : config.getValue();
        return resolveEffective(key, dbValue);
    }

    /**
     * Applies the DB → property → default precedence to a key given its ALREADY-READ DB value
     * (null when absent). Kept as the single place the property tier is read (guard-enforced), so a
     * blank DB value falls through to the property, then the default. Used both by
     * {@link #oidcConfig(OidcKey)} (per-key read) and by {@link #snapshot()} (which supplies DB
     * values from ONE atomic batch read).
     */
    private static String resolveEffective(OidcKey key, String dbValue) {
        if (dbValue != null && !StringUtils.isBlank(dbValue)) {
            return dbValue;
        }
        String property = System.getProperty(key.propertyName());
        if (property != null) {
            return property;
        }
        return key.defaultValue;
    }

    /** The effective source tier of a key, so the admin UI can hint "currently from JVM property". */
    static String oidcConfigSource(OidcKey key) {
        ConfigDao configDao = new ConfigDao();
        Config config = configDao.getById(key.configType());
        if (config != null && !StringUtils.isBlank(config.getValue())) {
            return "db";
        }
        if (System.getProperty(key.propertyName()) != null) {
            return "property";
        }
        return "default";
    }

    /**
     * An immutable, request-scoped snapshot of all effective OIDC values, read ONCE via
     * {@link #oidcConfig(OidcKey)} at the start of a request that needs OIDC config (login,
     * callback). Every read for the rest of that request goes through the snapshot, so a config
     * save landing mid-request can never produce a torn config (issuer=old but client_id=new)
     * within a single login/callback. A config change BETWEEN requests (login → callback) is
     * fine and unavoidable — the guarantee is that no SINGLE request sees a mixed old/new config.
     */
    static final class OidcConfigSnapshot {
        private final Map<OidcKey, String> values;

        private OidcConfigSnapshot(Map<OidcKey, String> values) {
            this.values = values;
        }

        /** The snapshotted effective value for a key (null when unset with no default). */
        String get(OidcKey key) {
            return values.get(key);
        }

        boolean enabled() {
            return Boolean.parseBoolean(get(OidcKey.ENABLED));
        }

        /** True when verbatim-username provisioning is enabled (default OFF). */
        boolean usernameVerbatim() {
            return Boolean.parseBoolean(get(OidcKey.USERNAME_VERBATIM));
        }

        /** The discovery-document URL derived from the snapshotted issuer (the discovery cache key). */
        String discoveryUrl() {
            String issuer = get(OidcKey.ISSUER);
            if (issuer == null) {
                return null;
            }
            return issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration";
        }

        /** SHA-256 hex of the snapshotted values (NUL-delimited, enum order) — the memo key. */
        String hash() {
            StringBuilder sb = new StringBuilder();
            for (OidcKey key : OidcKey.values()) {
                sb.append(ofNullable(get(key)).orElse("")).append('\0');
            }
            return sha256Hex(sb.toString());
        }
    }

    /**
     * Builds a request-scoped snapshot of all effective values. The DB tier is read in ONE
     * atomic query (a single {@code SELECT ... WHERE CFG_ID_C IN (...)}), so the snapshot cannot be
     * torn by a save that commits between two per-key reads — every DB value in the snapshot is from
     * the same committed instant. The property/default tiers are then applied per key via the single
     * {@link #resolveEffective} chokepoint. Every subsequent read in the request uses this immutable
     * snapshot.
     */
    static OidcConfigSnapshot snapshot() {
        Map<ConfigType, String> dbValues = readOidcConfigRows();
        java.util.EnumMap<OidcKey, String> values = new java.util.EnumMap<>(OidcKey.class);
        for (OidcKey key : OidcKey.values()) {
            values.put(key, resolveEffective(key, dbValues.get(key.configType())));
        }
        return new OidcConfigSnapshot(values);
    }

    /**
     * Reads all OIDC {@code T_CONFIG} rows in ONE query, so the DB tier of a snapshot is a
     * single atomic read (no interleaved commit can split it). Returns an empty map outside a
     * transactional context (e.g. a unit test with no EM), matching {@link ConfigDao}'s null-safe
     * behavior — the property/default tiers then apply.
     */
    private static Map<ConfigType, String> readOidcConfigRows() {
        jakarta.persistence.EntityManager em = ThreadLocalContext.get() == null
                ? null : ThreadLocalContext.get().getEntityManager();
        Map<ConfigType, String> result = new java.util.EnumMap<>(ConfigType.class);
        if (em == null) {
            return result;
        }
        List<ConfigType> ids = new ArrayList<>();
        for (OidcKey key : OidcKey.values()) {
            ids.add(key.configType());
        }
        List<Config> rows = em.createQuery("select c from Config c where c.id in :ids", Config.class)
                .setParameter("ids", ids)
                .getResultList();
        for (Config row : rows) {
            result.put(row.getId(), row.getValue());
        }
        return result;
    }

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
        // Read the whole effective config ONCE for this request (torn-read guard): every value
        // used below comes from this snapshot, never a fresh per-key accessor call.
        OidcConfigSnapshot cfg = snapshot();
        if (!cfg.enabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String configError = validateConfig(cfg);
        if (configError != null) {
            // Redirect to the SPA login error page instead of a raw 500. A 500 at the
            // login entry point auto-redirects the browser back into a loop; the error
            // page renders a message and offers a route back to the local login form.
            log.error("OIDC misconfigured: {}", configError);
            return Response.temporaryRedirect(URI.create("/#/login?error=oidc")).build();
        }

        try {
            String authorizationEndpoint = getAuthorizationEndpoint(cfg);
            String clientId = cfg.get(OidcKey.CLIENT_ID);
            String redirectUri = cfg.get(OidcKey.REDIRECT_URI);
            String scope = cfg.get(OidcKey.SCOPE);

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

            // Pin the provider fingerprint (issuer + client_id) that STARTED this login. The callback
            // rejects the flow if the CURRENT effective provider no longer matches, so an admin
            // switching the OIDC provider live (no restart) between login and callback can never
            // cause provider A's authorization code to be exchanged with provider B's token endpoint.
            OidcState oidcState = new OidcState()
                    .setId(state)
                    .setNonce(nonce)
                    .setCodeVerifier(codeVerifier)
                    .setReturnUrl(safeReturnUrl)
                    .setIssuer(cfg.get(OidcKey.ISSUER))
                    .setClientId(clientId);
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
        // Read the whole effective config ONCE for this request (torn-read guard): the token
        // exchange, JWKS verification, issuer/audience, claims and UserInfo below all use this
        // one snapshot, so a config save landing mid-callback cannot mix old and new values.
        OidcConfigSnapshot cfg = snapshot();
        if (!cfg.enabled()) {
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

        // Provider binding (fail closed): the state pinned the effective provider fingerprint
        // (issuer + client_id) at login. If an admin switched the OIDC provider live in between,
        // this authorization CODE was minted by the login-time provider and MUST NOT be exchanged
        // with the switched-to provider's token endpoint. Reject BEFORE the token exchange. A null
        // pinned fingerprint (a legacy state from before dbupdate-046) is ALSO rejected — fail
        // closed on ambiguity; the only cost is a one-time retry for a login in-flight across the
        // deploy moment (see providerFingerprintMatches).
        if (!providerFingerprintMatches(cfg, oidcState)) {
            log.warn("OIDC provider changed between login and callback; rejecting (fail closed)");
            return Response.temporaryRedirect(URI.create("/#/login?error=oidc")).build();
        }
        String expectedNonce = oidcState.getNonce();

        try {
            JsonObject tokenResponse = exchangeCodeForTokens(cfg, code, oidcState.getCodeVerifier());
            String idTokenStr = tokenResponse.getString("id_token", null);
            if (idTokenStr == null) {
                log.error("OIDC token response missing id_token");
                return Response.temporaryRedirect(URI.create("/#/login?error=oidc")).build();
            }

            DecodedJWT idToken = verifyIdToken(cfg, idTokenStr);

            // Verify nonce matches what we sent in the authorization request (fail closed).
            // Never log the nonce values themselves (session-linking secret).
            String tokenNonce = getClaimAsString(idToken, "nonce");
            if (expectedNonce == null || !expectedNonce.equals(tokenNonce)) {
                log.error("OIDC nonce mismatch");
                return Response.temporaryRedirect(URI.create("/#/login?error=oidc")).build();
            }

            String subject = idToken.getSubject();
            String issuer = cfg.get(OidcKey.ISSUER);

            // `sub` is REQUIRED by OIDC Core §2. Fail closed on a missing subject: without it
            // the identity lookup by (issuer, subject) is impossible, and provisioning an
            // unbound user (oidc_subject NULL) never collides in IDX_USER_OIDC (NULLs don't
            // collide), so every such login would mint yet another orphan account.
            if (StringUtils.isBlank(subject)) {
                log.error("OIDC token carries no sub claim; rejecting login (sub is REQUIRED per OIDC Core §2)");
                return Response.temporaryRedirect(URI.create("/#/login?error=oidc")).build();
            }

            String accessToken = tokenResponse.getString("access_token", null);
            String usernameClaimName = getUsernameClaim(cfg);
            String emailClaimName = getEmailClaim(cfg);

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
                    userInfo = fetchUserInfo(cfg, accessToken, subject);
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
                user = provisionOrRecover(userDao, usernameClaim, email, subject, issuer, cfg.usernameVerbatim());
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
    private DecodedJWT verifyIdToken(OidcConfigSnapshot cfg, String idTokenStr) throws Exception {
        DecodedJWT unverified = JWT.decode(idTokenStr);
        String kid = unverified.getKeyId();

        // Require the exp claim to be present. OIDC mandates it; JWT.require only
        // validates expiry when the claim exists, so a token with no exp would
        // otherwise be accepted as never-expiring.
        if (unverified.getExpiresAt() == null) {
            throw new Exception("ID token missing required exp claim");
        }

        List<RSAPublicKey> candidates = getSigningKeys(cfg, kid);
        if (candidates.isEmpty()) {
            candidates = refreshJwksAndRetry(cfg, kid);
        }
        if (candidates.isEmpty()) {
            throw new Exception("No matching key found in JWKS for kid=" + kid);
        }

        String issuer = cfg.get(OidcKey.ISSUER);
        String audience = cfg.get(OidcKey.CLIENT_ID);

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
        candidates = refreshJwksAndRetry(cfg, kid);
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
    private List<RSAPublicKey> refreshJwksAndRetry(OidcConfigSnapshot cfg, String kid) throws Exception {
        String jwksUri = getJwksUri(cfg);
        long now = System.currentTimeMillis();
        long last = jwksLastRefreshMs.getOrDefault(jwksUri, 0L);
        if (now - last < JWKS_MIN_REFRESH_INTERVAL_MS) {
            log.debug("JWKS refresh skipped (rate limited)");
            return List.of();
        }
        log.info("Refreshing JWKS cache (kid={} not found or verification failed)", kid);
        jwksCache.remove(jwksUri);
        jwksLastRefreshMs.put(jwksUri, now);
        return getSigningKeys(cfg, kid);
    }

    /**
     * Fetches RSA public keys from the provider's JWKS.
     * When kid is non-null, returns at most one key matching that kid.
     * When kid is null, returns all eligible RSA signing keys.
     */
    private List<RSAPublicKey> getSigningKeys(OidcConfigSnapshot cfg, String kid) throws Exception {
        JsonObject jwks = getJwks(cfg);
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

    private JsonObject exchangeCodeForTokens(OidcConfigSnapshot cfg, String code, String codeVerifier) throws Exception {
        String tokenEndpoint = getTokenEndpoint(cfg);
        String clientId = cfg.get(OidcKey.CLIENT_ID);
        String clientSecret = cfg.get(OidcKey.CLIENT_SECRET);
        String redirectUri = cfg.get(OidcKey.REDIRECT_URI);

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

    /** The configured username claim name (from the snapshot), defaulting to {@code preferred_username}. */
    private static String getUsernameClaim(OidcConfigSnapshot cfg) {
        return ofNullable(cfg.get(OidcKey.USERNAME_CLAIM))
                .filter(s -> !s.isBlank()).orElse(DEFAULT_USERNAME_CLAIM);
    }

    /** The configured email claim name (from the snapshot), defaulting to {@code email}. */
    private static String getEmailClaim(OidcConfigSnapshot cfg) {
        return ofNullable(cfg.get(OidcKey.EMAIL_CLAIM))
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
    private JsonObject fetchUserInfo(OidcConfigSnapshot cfg, String accessToken, String expectedSub) throws UserInfoException {
        String endpoint;
        String explicit = cfg.get(OidcKey.USERINFO_ENDPOINT);
        if (explicit != null && !explicit.isBlank()) {
            endpoint = explicit;
        } else {
            try {
                endpoint = getDiscovery(cfg).getString("userinfo_endpoint", null);
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
        // Hash-suffix mode: reserve room for the '_' separator + hash suffix.
        return sanitizeUsernameStem(raw, USERNAME_MAX_LENGTH - 1 - USERNAME_HASH_LEN);
    }

    /**
     * Normalizes a raw claim into a username stem truncated to {@code budget} characters. The
     * hash-suffix path passes the hash-reserved budget; the verbatim path
     * ({@link #sanitizeUsernameVerbatim(String)}) passes the FULL {@link #USERNAME_MAX_LENGTH}
     * so the sanitized {@code preferred_username} is used at its full length (Codex B4).
     *
     * @param raw Raw claim value (may contain {@code @ /} whitespace, Unicode — Core §5.1)
     * @param budget Maximum stem length to keep
     * @return A sanitized stem in the username charset, or null when nothing survives
     */
    static String sanitizeUsernameStem(String raw, int budget) {
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
        if (stem.length() > budget) {
            stem = stem.substring(0, budget);
        }
        return stem.isEmpty() ? null : stem;
    }

    /**
     * Sanitizes a raw claim into a VERBATIM username candidate at the FULL username-length
     * budget (no hash suffix reserved). Used only when {@code docs.oidc_username_verbatim} is
     * ON. The result is the sanitized {@code preferred_username} itself (charset-normalized),
     * or null when nothing survives sanitization. Uniqueness is then enforced by the DB
     * constraint (dbupdate-050); a collision falls back to the deterministic hash disambiguator.
     *
     * @param raw Raw claim value
     * @return A sanitized verbatim username, or null when nothing survives
     */
    static String sanitizeUsernameVerbatim(String raw) {
        String stem = sanitizeUsernameStem(raw, USERNAME_MAX_LENGTH);
        if (stem == null) {
            return null;
        }
        // A trailing '_' can survive a full-budget truncation; trim it so the verbatim name is tidy.
        stem = stem.replaceAll("_+$", "");
        if (stem.isEmpty()) {
            return null;
        }
        // A pure-numeric-collapse could yield a <3-char stem; the pattern requires >=3.
        return stem.matches(USERNAME_PATTERN) ? stem : null;
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
        // Read the effective verbatim flag from the request snapshot. The 6-arg overload takes an
        // explicit flag so a test can force either mode deterministically without DB config.
        return provisionOrRecover(userDao, usernameClaim, email, subject, issuer, snapshot().usernameVerbatim());
    }

    private User provisionOrRecover(UserDao userDao, String usernameClaim, String email, String subject,
                                    String issuer, boolean verbatim) {
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

        // Verbatim candidate (full-length sanitized preferred_username); null when the claim is
        // absent/unsanitizable, in which case verbatim provisioning falls back to the hash suffix.
        String verbatimUsername = verbatim ? sanitizeUsernameVerbatim(usernameClaim) : null;

        String userEmail = (email != null && isValidEmail(email)) ? email : stem + "@oidc.local";
        if (!isValidEmail(userEmail)) {
            log.warn("OIDC provisioning rejected: invalid email");
            return null;
        }

        try {
            return provisionUserFreshTx(stem, verbatimUsername, userEmail, subject, issuer);
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
     *
     * <p>Two username strategies converge here:
     * <ul>
     *   <li><b>Verbatim (opt-in):</b> when {@code verbatimUsername} is non-null it is tried FIRST,
     *       at the full username length with no hash suffix. Uniqueness is enforced by the
     *       active-case-insensitive unique constraint (dbupdate-050): on a collision (another
     *       active user already owns that name, or a true-race second insert) we do NOT retry the
     *       verbatim name (it is deterministic and would collide forever) — we DETERMINISTICALLY
     *       fall back to the hash-suffix disambiguator below (no duplicate, no 500).</li>
     *   <li><b>Hash suffix (default):</b> the sanitized stem plus a {@code _}+SHA-256 prefix of
     *       {@code issuer+NUL+subject}. On a residual collision (distinct subjects sanitizing to
     *       the same stem AND hash prefix) the hash suffix is extended deterministically and
     *       retried.</li>
     * </ul>
     * A username conflict is detected BOTH from {@link UserDao#create}'s case-insensitive
     * app-precheck ({@code AlreadyExistingUsername}) AND from the DB-level active unique index
     * ({@code IDX_USER_USERNAME_ACTIVE}) — the latter is the race backstop the app-precheck cannot
     * cover (see {@link #isUsernameConflict}).
     */
    private User provisionUserFreshTx(String stem, String verbatimUsername, String userEmail,
                                      String subject, String issuer) {
        // Verbatim attempt (opt-in) FIRST, if a candidate survived sanitization.
        if (verbatimUsername != null) {
            try {
                return insertOidcUserFreshTx(verbatimUsername, userEmail, subject, issuer);
            } catch (RuntimeException e) {
                if (isOidcSubjectConflict(e)) {
                    throw new ConstraintViolationRetry(e);
                }
                if (isUsernameConflict(e)) {
                    // The verbatim name is taken (existing active user or a true-race second
                    // insert). It is deterministic, so retrying it would collide forever — fall
                    // through to the hash-suffix disambiguator below.
                    log.warn("OIDC verbatim username collision on '{}', falling back to hash disambiguator",
                            verbatimUsername);
                } else {
                    throw e;
                }
            }
        }

        int hashLen = USERNAME_HASH_LEN;
        while (hashLen <= 64) {
            String username = deriveUsername(stem, issuer, subject, hashLen);
            if (username == null) {
                log.warn("OIDC provisioning rejected: could not derive a valid username");
                return null;
            }
            try {
                return insertOidcUserFreshTx(username, userEmail, subject, issuer);
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

    /**
     * Inserts one OIDC user with the given username on a fresh committed transaction. Any
     * persistence failure (unique-index violation on {@code IDX_USER_OIDC} or
     * {@code IDX_USER_USERNAME_ACTIVE}, or the app-precheck) surfaces as a {@link RuntimeException}
     * the caller classifies via {@link #isOidcSubjectConflict}/{@link #isUsernameConflict}.
     */
    private User insertOidcUserFreshTx(String username, String userEmail, String subject, String issuer) {
        return runOnFreshTransaction(em -> {
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
                new UserDao().create(user, username);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            log.info("Provisioned new OIDC user: {} (subject at DEBUG)", username);
            log.debug("Provisioned new OIDC user: {} (issuer={}, sub={})", username, issuer, subject);
            return user;
        });
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

    /**
     * True when the throwable chain indicates a username collision — from EITHER the
     * case-insensitive app-precheck in {@link UserDao#create} ({@code AlreadyExistingUsername}) OR
     * the DB-level active unique index {@code IDX_USER_USERNAME_ACTIVE} (dbupdate-050). The DB
     * index is the race backstop the app-precheck cannot cover: two concurrent first logins whose
     * prechecks both pass still contend at flush, and exactly one must be treated as a collision.
     */
    private static boolean isUsernameConflict(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String msg = c.getMessage();
            if ("AlreadyExistingUsername".equals(msg)) {
                return true;
            }
            if (msg != null && msg.toUpperCase().contains("IDX_USER_USERNAME_ACTIVE")) {
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
     * Validates OIDC configuration from a request snapshot. Returns an error message if invalid,
     * null if OK. Memoized by the snapshot's hash of the EFFECTIVE values: a repeat login on
     * the same config skips the work, but any config change (a DB override saved, a property
     * flipped) yields a new hash and forces a fresh validation on the NEXT login — no restart and
     * no invalidation hook. Because the whole request reads from ONE snapshot, the config validated
     * here is exactly the config the rest of this request acts on (no torn read).
     */
    private String validateConfig(OidcConfigSnapshot cfg) {
        String hash = cfg.hash();
        if (hash.equals(validatedConfigHash)) {
            return null;
        }

        String issuer = cfg.get(OidcKey.ISSUER);
        String clientId = cfg.get(OidcKey.CLIENT_ID);
        String clientSecret = cfg.get(OidcKey.CLIENT_SECRET);
        String redirectUri = cfg.get(OidcKey.REDIRECT_URI);

        if (StringUtils.isBlank(issuer)) return OidcKey.ISSUER.propertyName() + " is required";
        if (StringUtils.isBlank(clientId)) return OidcKey.CLIENT_ID.propertyName() + " is required";
        if (StringUtils.isBlank(clientSecret)) return OidcKey.CLIENT_SECRET.propertyName() + " is required";
        if (StringUtils.isBlank(redirectUri)) return OidcKey.REDIRECT_URI.propertyName() + " is required";

        log.info("OIDC configuration: issuer={}, client_id={}, redirect_uri={}, secret=[REDACTED]",
                issuer, clientId, redirectUri);

        validatedConfigHash = hash;
        return null;
    }

    private String getAuthorizationEndpoint(OidcConfigSnapshot cfg) throws Exception {
        String explicit = cfg.get(OidcKey.AUTHORIZATION_ENDPOINT);
        if (explicit != null) {
            return explicit;
        }
        return getDiscovery(cfg).getString("authorization_endpoint");
    }

    private String getTokenEndpoint(OidcConfigSnapshot cfg) throws Exception {
        String explicit = cfg.get(OidcKey.TOKEN_ENDPOINT);
        if (explicit != null) {
            return explicit;
        }
        return getDiscovery(cfg).getString("token_endpoint");
    }

    private String getJwksUri(OidcConfigSnapshot cfg) throws Exception {
        String explicit = cfg.get(OidcKey.JWKS_URI);
        if (explicit != null) {
            return explicit;
        }
        return getDiscovery(cfg).getString("jwks_uri");
    }

    private JsonObject getDiscovery(OidcConfigSnapshot cfg) throws Exception {
        String discoveryUrl = cfg.discoveryUrl();
        if (discoveryUrl == null) {
            throw new Exception("OIDC issuer is not configured");
        }
        JsonObject cached = discoveryCache.get(discoveryUrl);
        if (cached != null) {
            return cached;
        }

        String issuer = cfg.get(OidcKey.ISSUER);
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

            // Keyed by the effective discovery URL: a change of issuer keys a fresh fetch, and a
            // concurrent reader under the old issuer still reads its own (old-keyed) entry.
            discoveryCache.put(discoveryUrl, discovery);
            log.info("OIDC discovery loaded from {}", discoveryUrl);
            return discovery;
        }
    }

    private JsonObject getJwks(OidcConfigSnapshot cfg) throws Exception {
        String jwksUri = getJwksUri(cfg);
        JsonObject cached = jwksCache.get(jwksUri);
        if (cached != null) {
            return cached;
        }

        Request req = new Request.Builder().url(jwksUri).get().build();
        try (okhttp3.Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new Exception("Failed to fetch JWKS: HTTP " + resp.code());
            }
            String body = resp.body().string();
            JsonObject jwks;
            try (JsonReader reader = Json.createReader(new StringReader(body))) {
                jwks = reader.readObject();
            }
            // Keyed by the effective JWKS URI so a jwks_uri change is picked up on the next login.
            jwksCache.put(jwksUri, jwks);
            log.info("OIDC JWKS loaded from {}", jwksUri);
            return jwks;
        }
    }

    /**
     * Composes the full OIDC RP-initiated logout URL from ONE request snapshot (torn-read guard for
     * the logout path): the enabled check, the {@code end_session_endpoint} resolution (via the
     * snapshot's discovery cache key), and the {@code post_logout_redirect_uri} (from the snapshot's
     * redirect URI) all read from the SAME snapshot, so a provider change landing mid-logout can
     * never send the stored {@code id_token_hint} to one provider's endpoint with another provider's
     * redirect. The stored token is additionally BOUND to its provider fingerprint, mirroring the
     * callback validation: its {@code iss} must equal the current effective issuer AND its {@code aud}
     * must contain the current client_id. If an admin switched the OIDC provider (or client
     * registration) live since the login that minted this token, either half no longer matches and
     * the method returns null (so provider A's token is never disclosed to provider B's endpoint).
     * Returns null when OIDC is disabled, no id_token is stored, the stored token's issuer or
     * audience does not match the current provider (or cannot be parsed), discovery has not been
     * fetched, or the provider advertises no {@code end_session_endpoint} (the caller then omits
     * the external logout redirect and just clears the local session).
     *
     * @param oidcIdToken The stored OIDC id_token to pass as {@code id_token_hint} (may be null)
     * @return The composed logout URL, or null when no OIDC logout redirect applies
     */
    public static String resolveLogoutUrl(String oidcIdToken) {
        if (oidcIdToken == null) {
            return null;
        }
        OidcConfigSnapshot cfg = snapshot();

        // Provider binding: the stored id_token was minted by the provider configured at LOGIN.
        // If an admin switched the OIDC provider LIVE since then, sending this token as
        // id_token_hint to the CURRENT provider's end_session_endpoint would disclose provider A's
        // token to provider B. Bind the token to its issuer: only proceed when the token's iss
        // equals the current effective issuer. Fail safe (skip the external redirect) when the
        // token has no iss or cannot be parsed — the caller still clears the local session.
        String tokenIssuer = readIdTokenIssuer(oidcIdToken);
        if (tokenIssuer == null || !tokenIssuer.equals(cfg.get(OidcKey.ISSUER))) {
            return null;
        }

        // Provider binding, audience half: the callback path validates BOTH iss and aud
        // (verifyIdToken .withIssuer/.withAudience). Mirror that here so a token whose aud is
        // no longer the current client_id is likewise not disclosed to the current provider's
        // end_session_endpoint. aud may be a single string or an array; treat a membership match
        // as valid. Fail safe (skip the external redirect, local logout only) on any mismatch or
        // an unparseable/empty aud — identical to the issuer-mismatch path above.
        List<String> tokenAudiences = readIdTokenAudiences(oidcIdToken);
        if (tokenAudiences == null || !tokenAudiences.contains(cfg.get(OidcKey.CLIENT_ID))) {
            return null;
        }

        String endSessionEndpoint = getEndSessionEndpoint(cfg);
        if (endSessionEndpoint == null) {
            return null;
        }
        String redirectUri = ofNullable(cfg.get(OidcKey.REDIRECT_URI)).orElse("");
        String baseUrl = redirectUri.replaceAll("/api/oidc/callback$", "");
        return endSessionEndpoint
                + "?id_token_hint=" + urlEncode(oidcIdToken)
                + "&post_logout_redirect_uri=" + urlEncode(baseUrl);
    }

    /**
     * Returns the {@code end_session_endpoint} from the cached discovery document for the SNAPSHOT's
     * issuer, or null if OIDC is disabled (per the snapshot), discovery has not been fetched, or the
     * provider does not advertise one. Reads the value-keyed discovery cache under the snapshot's
     * discovery URL, so it is consistent with every other value that logout request uses.
     */
    static String getEndSessionEndpoint(OidcConfigSnapshot cfg) {
        try {
            if (!cfg.enabled()) {
                return null;
            }
            String discoveryUrl = cfg.discoveryUrl();
            JsonObject discovery = discoveryUrl == null ? null : discoveryCache.get(discoveryUrl);
            if (discovery == null) {
                return null;
            }
            return discovery.getString("end_session_endpoint", null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reads the {@code iss} claim from a stored id_token WITHOUT verifying its signature. This is
     * our own previously-verified stored token (verified at callback via {@link #verifyIdToken}) —
     * we only need to know which provider minted it so a logout binds the token to that provider.
     * Reuses the same {@link JWT#decode} the verification path uses (no signature check here).
     *
     * @param idToken The stored id_token (a JWT)
     * @return The {@code iss} claim, or null when the token cannot be parsed or carries no issuer
     */
    static String readIdTokenIssuer(String idToken) {
        try {
            String issuer = JWT.decode(idToken).getIssuer();
            return StringUtils.isBlank(issuer) ? null : issuer;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reads the {@code aud} claim from a stored id_token WITHOUT verifying its signature (same
     * rationale as {@link #readIdTokenIssuer}: our own previously-verified token, we only need the
     * bound client_id for the logout provider-binding check). {@code aud} may be a single string or
     * an array; both are returned as a list.
     *
     * @param idToken The stored id_token (a JWT)
     * @return The list of audiences, or null when the token cannot be parsed or carries no audience
     */
    static List<String> readIdTokenAudiences(String idToken) {
        try {
            List<String> audiences = JWT.decode(idToken).getAudience();
            return audiences == null || audiences.isEmpty() ? null : audiences;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * True when the provider fingerprint pinned on the login state still matches the CURRENT
     * effective provider (from the callback's snapshot). The fingerprint is the login-time issuer +
     * client_id.
     *
     * <p>Fail closed on ambiguity: a state with NO pinned fingerprint (issuer/client_id null) is
     * REJECTED (returns false), NOT treated as matching. After {@code dbupdate-046}, {@link #login}
     * always writes the fingerprint, so a null fingerprint can only be a legacy state created before
     * this deploy — the sole cost of rejecting it is that a login literally in-flight across the
     * deploy moment retries once (a benign UX blip), which is the correct trade for a security
     * invariant. No new legitimate login produces a null fingerprint. Treating null as a match would
     * be fail-open: a pre-migration state plus a live A→B provider switch within the state TTL would
     * exchange provider A's code with provider B.
     *
     * @param cfg   The callback's request-scoped config snapshot (the current effective provider)
     * @param state The in-flight login state (carries the pinned login-time fingerprint)
     * @return True only when a non-null pinned fingerprint equals the current provider
     */
    static boolean providerFingerprintMatches(OidcConfigSnapshot cfg, OidcState state) {
        String pinnedIssuer = state.getIssuer();
        String pinnedClientId = state.getClientId();
        // Fail closed: an unpinned (pre-migration) state is rejected, never accepted on ambiguity.
        if (StringUtils.isBlank(pinnedIssuer) || StringUtils.isBlank(pinnedClientId)) {
            return false;
        }
        return StringUtils.equals(pinnedIssuer, cfg.get(OidcKey.ISSUER))
                && StringUtils.equals(pinnedClientId, cfg.get(OidcKey.CLIENT_ID));
    }

    /** True when OIDC login is enabled per the effective config (DB override, else property). */
    public static boolean isOidcEnabled() {
        return Boolean.parseBoolean(oidcConfig(OidcKey.ENABLED));
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
