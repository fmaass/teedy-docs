package com.sismics.docs.rest.resource;

import com.google.common.base.Strings;
import com.google.common.collect.Sets;
import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.*;
import com.sismics.docs.core.dao.criteria.GroupCriteria;
import com.sismics.docs.core.dao.criteria.UserCriteria;
import com.sismics.docs.core.dao.dto.GroupDto;
import com.sismics.docs.core.dao.dto.UserDto;
import com.sismics.docs.core.event.DocumentDeletedAsyncEvent;
import com.sismics.docs.core.event.FileDeletedAsyncEvent;
import com.sismics.docs.core.event.PasswordLostEvent;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.model.jpa.*;
import com.sismics.docs.core.util.ConfigUtil;
import com.sismics.docs.core.util.CredentialLifecycleUtil;
import com.sismics.docs.core.util.authentication.AuthenticationUtil;
import com.sismics.docs.core.util.PasswordRecoveryUtil;
import com.sismics.docs.core.util.PrincipalDeletionUtil;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.docs.rest.constant.BaseFunction;
import com.sismics.docs.rest.util.LoginThrottleStore;
import com.sismics.docs.rest.util.UserUpdateUtil;
import com.sismics.util.net.ClientAddressResolver;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.exception.ServerException;
import com.sismics.rest.util.ValidationUtil;
import com.sismics.security.UserPrincipal;
import com.sismics.util.JsonUtil;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.csrf.CsrfTokenUtil;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import com.sismics.util.totp.GoogleAuthenticator;
import com.sismics.util.totp.GoogleAuthenticatorKey;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.http.Cookie;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * User REST resources.
 * 
 * @author jtremeaux
 */
@Path("/user")
public class UserResource extends BaseResource {
    /**
     * Supported UI locale codes, mirrored from the SPA's set (the bundled English plus every lazy-loaded
     * locale in docs-web/src/main/webapp/src/i18n.ts). A per-user locale (#82) is validated against this
     * set on write so an unknown code is rejected rather than persisted. Keep in sync with the SPA.
     */
    private static final Set<String> SUPPORTED_LOCALES = Set.of(
            "en", "de", "es", "fr", "it", "pt", "pl", "el", "ru", "zh_CN", "zh_TW", "sq_AL");

    /**
     * Creates a new user.
     *
     * @api {put} /user Register a new user
     * @apiName PutUser
     * @apiGroup User
     * @apiParam {String{3..50}} username Username
     * @apiParam {String{8..50}} password Password
     * @apiParam {String{1..100}} email E-mail
     * @apiParam {Number} storage_quota Storage quota (in bytes)
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiError (server) PrivateKeyError Error while generating a private key
     * @apiError (client) AlreadyExistingUsername Login already used
     * @apiError (server) UnknownError Unknown server error
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @param username User's username
     * @param password Password
     * @param email E-Mail
     * @return Response
     */
    @PUT
    public Response register(
        @FormParam("username") String username,
        @FormParam("password") String password,
        @FormParam("email") String email,
        @FormParam("storage_quota") String storageQuotaStr) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);
        
        // Validate the input data
        username = ValidationUtil.validateLength(username, "username", 3, 50);
        ValidationUtil.validateUsername(username, "username");
        password = ValidationUtil.validateLength(password, "password", 8, 50);
        ValidationUtil.validatePasswordStrength(password, username);
        email = ValidationUtil.validateLength(email, "email", 1, 100);
        Long storageQuota = ValidationUtil.validateLong(storageQuotaStr, "storage_quota");
        ValidationUtil.validateNonNegative(storageQuota, "storage_quota");
        ValidationUtil.validateEmail(email, "email");
        
        // Create the user
        User user = new User();
        user.setRoleId(Constants.DEFAULT_USER_ROLE);
        user.setUsername(username);
        user.setPassword(password);
        user.setEmail(email);
        user.setStorageQuota(storageQuota);
        user.setOnboarding(true);

        // Create the user
        UserDao userDao = new UserDao();
        try {
            userDao.create(user, principal.getId());
        } catch (Exception e) {
            if ("AlreadyExistingUsername".equals(e.getMessage())) {
                throw new ClientException("AlreadyExistingUsername", "Login already used", e);
            } else {
                throw new ServerException("UnknownError", "Unknown server error", e);
            }
        }
        
        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Updates the current user informations.
     *
     * @api {post} /user Update the current user
     * @apiName PostUser
     * @apiGroup User
     * @apiParam {String{8..50}} password Password
     * @apiParam {String{1..100}} email E-mail
     * @apiParam {String} locale Preferred UI locale code (e.g. de, zh_CN); must be a supported SPA locale
     * @apiParam {String} dark_mode Preferred dark-mode flag; must be exactly "true" or "false"
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied or connected as guest
     * @apiError (client) ValidationError Validation error
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param password Password
     * @param email E-Mail
     * @param locale Preferred UI locale code
     * @param darkMode Preferred dark-mode flag ("true"/"false")
     * @return Response
     */
    @POST
    public Response update(
        @FormParam("password") String password,
        @FormParam("current_password") String currentPassword,
        @FormParam("email") String email,
        @FormParam("locale") String locale,
        @FormParam("dark_mode") String darkMode) {
        if (!authenticate() || principal.isGuest()) {
            throw new ForbiddenClientException();
        }

        // Validate the input data
        password = ValidationUtil.validateLength(password, "password", 8, 50, true);
        email = ValidationUtil.validateLength(email, "email", 1, 100, true);
        // #82: optional preferred UI locale. Absent/blank leaves the stored value unchanged; a
        // provided value must be one of the SPA-supported locale codes or the request is rejected.
        locale = ValidationUtil.validateLength(locale, "locale", 1, 10, true);
        if (locale != null && !SUPPORTED_LOCALES.contains(locale)) {
            throw new ClientException("ValidationError", "'locale' is not a supported locale");
        }
        // #147: optional preferred dark-mode flag. Absent leaves the stored value unchanged (null stays
        // distinguishable from a stored false); a provided value must be exactly "true" or "false" — any
        // other value is rejected 400 rather than silently coerced by Boolean.parseBoolean.
        darkMode = ValidationUtil.validateLength(darkMode, "dark_mode", 1, 5, true);
        if (darkMode != null && !"true".equals(darkMode) && !"false".equals(darkMode)) {
            throw new ClientException("ValidationError", "'dark_mode' must be 'true' or 'false'");
        }

        // If changing password, verify the current password first and capture the credential epoch the
        // verification observed. That epoch gates the conditional update below so a self-change that
        // verified the old password then paused cannot overwrite a recovery reset that landed in between.
        User verifiedUser = null;
        if (StringUtils.isNotBlank(password)) {
            if (StringUtils.isBlank(currentPassword)) {
                throw new ClientException("CurrentPasswordRequired", "Current password is required to change password");
            }
            verifiedUser = AuthenticationUtil.authenticate(principal.getName(), currentPassword);
            if (verifiedUser == null) {
                throw new ForbiddenClientException();
            }
            ValidationUtil.validatePasswordStrength(password, principal.getName());
        }

        // Update the user
        boolean passwordChanged = StringUtils.isNotBlank(password);
        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(principal.getName());
        UserUpdateUtil.applyEmailUpdate(user, email);
        if (locale != null) {
            user.setLocale(locale);
        }
        if (darkMode != null) {
            user.setDarkMode(Boolean.parseBoolean(darkMode));
        }
        user = userDao.update(user, principal.getId());

        // Change the password conditionally on the verification-time epoch, advancing the credential epoch
        // (which kills EVERY existing session AND API key of this user). The returned authoritative
        // post-bump epoch stamps the rotated replacement session. A concurrent recovery reset that already
        // bumped the epoch wins: the conditional update abandons the self-change and returns a negative
        // value, so the reset's password stands.
        if (passwordChanged) {
            long rotatedEpoch = CredentialLifecycleUtil.changeOwnPassword(
                    user.getId(), password, verifiedUser.getCredentialEpoch(), principal.getId());
            if (rotatedEpoch < 0) {
                throw new ClientException("ConcurrentCredentialChange",
                        "The password was changed by another operation; please retry");
            }
        }

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        Response.ResponseBuilder responseBuilder = Response.ok().entity(response.build());

        // On a self-service password change, ROTATE the session: revoke every existing token (so a cloned or
        // stolen cookie stops working, including the one that just made this request) and mint a fresh token
        // for the current browser, returned as a new cookie. Runs in this request transaction.
        if (passwordChanged) {
            NewCookie[] rotatedCookies = rotateSession(user);
            if (rotatedCookies != null) {
                responseBuilder.cookie(rotatedCookies);
            }
        }
        return responseBuilder.build();
    }

    /**
     * Rotates the current browser's session after a self-service password change: revokes ALL of the user's
     * authentication tokens (including the current one) and, when the request was made over one of this
     * user's own valid session cookies, mints a fresh token carrying over its long-lasted flag and returns it
     * as a replacement cookie.
     *
     * @param user the user whose sessions are rotated
     * @return the replacement session cookie and its additive CSRF companion cookie (as a two-element
     *         array), or null when the request did not present a valid current session token for this
     *         user (e.g. a header/API-key-authenticated request, or a junk/foreign cookie), in which case
     *         all of the user's tokens are still revoked but no session is minted
     */
    private NewCookie[] rotateSession(User user) {
        AuthenticationTokenDao authenticationTokenDao = new AuthenticationTokenDao();

        // Resolve the presented cookie to a session token and confirm it is a VALID CURRENT session for THIS
        // user. A mere non-null cookie string is not enough: a header- or API-key-authenticated request
        // carrying a junk (or another user's) auth_token cookie must NOT be handed a freshly-minted, durable
        // bearer session. Only rotate when the cookie genuinely resolves to one of this user's own sessions.
        String currentToken = getAuthToken();
        AuthenticationToken existing = currentToken == null ? null : authenticationTokenDao.get(currentToken);
        boolean validCurrentSession = existing != null && user.getId().equals(existing.getUserId());
        boolean longLasted = validCurrentSession && existing.isLongLasted();

        // Revoke ALL sessions, including the current token (a cloned cookie must not survive).
        authenticationTokenDao.deleteAllByUserId(user.getId());

        if (!validCurrentSession) {
            // No valid session token of this user's own to rotate — mint nothing.
            return null;
        }

        // Mint a fresh token for the current browser.
        String ip = ClientAddressResolver.getInstance().resolve(request);
        AuthenticationToken newToken = new AuthenticationToken()
                .setUserId(user.getId())
                .setLongLasted(longLasted)
                .setIp(StringUtils.abbreviate(ip, 45))
                .setUserAgent(StringUtils.abbreviate(request.getHeader("user-agent"), 1000))
                // Stamp the AUTHORITATIVE post-bump epoch, read fresh from the row (scalar) in this same
                // transaction. The self password-change bumped the epoch just before this rotation; the
                // native bump does not refresh the in-memory user entity, so stamping its cached (pre-bump)
                // epoch would leave the replacement session dead on arrival.
                .setCredentialEpoch(CredentialLifecycleUtil.currentEpoch(user.getId()));
        String tokenValue = authenticationTokenDao.create(newToken);

        int maxAge = longLasted ? TokenBasedSecurityFilter.TOKEN_LONG_LIFETIME : -1;
        NewCookie authCookie = new NewCookie.Builder(TokenBasedSecurityFilter.COOKIE_NAME)
                .value(tokenValue)
                .path("/")
                .maxAge(maxAge)
                .secure(true)
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.LAX)
                .build();
        return new NewCookie[] { authCookie, CsrfTokenUtil.buildSessionCookie(tokenValue) };
    }

    /**
     * Updates a user informations.
     *
     * @api {post} /user/:username Update a user
     * @apiName PostUserUsername
     * @apiGroup User
     * @apiParam {String} username Username
     * @apiParam {String{8..50}} password Password
     * @apiParam {String{1..100}} email E-mail
     * @apiParam {Number} storage_quota Storage quota (in bytes)
     * @apiParam {Boolean} disabled Disabled status
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiError (client) UserNotFound User not found
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @param username Username
     * @param password Password
     * @param email E-Mail
     * @return Response
     */
    @POST
    @Path("{username: [a-zA-Z0-9_@.-]+}")
    public Response update(
        @PathParam("username") String username,
        @FormParam("password") String password,
        @FormParam("email") String email,
        @FormParam("storage_quota") String storageQuotaStr,
        @FormParam("disabled") Boolean disabled) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);
        
        // Validate the input data
        password = ValidationUtil.validateLength(password, "password", 8, 50, true);
        email = ValidationUtil.validateLength(email, "email", 1, 100, true);
        
        // Validate password strength if changing
        if (StringUtils.isNotBlank(password)) {
            ValidationUtil.validatePasswordStrength(password, username);
        }

        // Check if the user exists
        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(username);
        if (user == null) {
            throw new ClientException("UserNotFound", "The user does not exist");
        }

        // Update the user
        UserUpdateUtil.applyEmailUpdate(user, email);
        if (StringUtils.isNotBlank(storageQuotaStr)) {
            Long storageQuota = ValidationUtil.validateLong(storageQuotaStr, "storage_quota");
            ValidationUtil.validateNonNegative(storageQuota, "storage_quota");
            user.setStorageQuota(storageQuota);
        }
        user = userDao.update(user, principal.getId());

        boolean disableTransition = false;
        if (disabled != null) {
            // Cannot disable the admin user or the guest user
            RoleBaseFunctionDao userBaseFuction = new RoleBaseFunctionDao();
            Set<String> baseFunctionSet = userBaseFuction.findByRoleId(Sets.newHashSet(user.getRoleId()));
            if (Constants.GUEST_USER_ID.equals(username) || baseFunctionSet.contains(BaseFunction.ADMIN.name())) {
                disabled = false;
            }

            // Decide and apply the disable/enable transition under a FOR UPDATE lock on the target row, held
            // for the rest of this request transaction. The transition MUST be computed from the row's CURRENT
            // disableDate read under the lock, never the pre-lock value loaded above: a concurrent admin (or the
            // target's own self-update) may have changed the state in between. Locking closes two races the
            // pre-lock decision left open — (a) a racing self-update re-enabling a just-disabled account, and
            // (b) a stale duplicate request flipping the state without the credential bump. disableDate is not
            // in userDao.update's copy list, so this locked read-modify-write is its sole writer.
            User lockedUser = CredentialLifecycleUtil.lockActiveUser(user.getId());
            if (lockedUser == null) {
                // The target was soft-deleted concurrently: fail with the endpoint's existing not-found semantics.
                throw new ClientException("UserNotFound", "The user does not exist");
            }
            boolean currentlyDisabled = lockedUser.getDisableDate() != null;
            if (disabled && !currentlyDisabled) {
                // Recording the disabled date
                disableTransition = true;
                lockedUser.setDisableDate(new Date());
            } else if (!disabled && currentlyDisabled) {
                // Emptying the disabled date (a re-enable does NOT bump, per #110)
                lockedUser.setDisableDate(null);
            }
        }

        // Change the password
        boolean passwordChanged = StringUtils.isNotBlank(password);
        UserUpdateUtil.applyPasswordUpdate(userDao, user, password, principal.getId());

        // An admin-initiated password reset revokes ALL of the target user's sessions (the account may be
        // compromised). Committed atomically with the password change in this request transaction.
        if (passwordChanged) {
            new AuthenticationTokenDao().deleteAllByUserId(user.getId());
        }

        // Uniform rule: an admin password change OR a transition to disabled advances the credential epoch,
        // invalidating EVERY existing session and API key of the target (closes #108, and #110 so a
        // re-enable — which does NOT bump — cannot resurrect credentials stamped before the disable).
        if (passwordChanged || disableTransition) {
            CredentialLifecycleUtil.bumpEpoch(user.getId());
        }

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * This resource is used to authenticate the user and create a user session.
     * The "session" is only used to identify the user, no other data is stored in the session.
     *
     * @api {post} /user/login Login a user
     * @apiDescription This resource creates an authentication token and gives it back in a cookie.
     * All authenticated resources will check this cookie to find the user currently logged in.
     * @apiName PostUserLogin
     * @apiGroup User
     * @apiParam {String} username Username
     * @apiParam {String} password Password (optional for guest login)
     * @apiParam {String} code TOTP validation code
     * @apiParam {Boolean} remember If true, create a long lasted token
     * @apiSuccess {String} auth_token A cookie named auth_token containing the token ID
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationCodeRequired A TOTP validation code is required
     * @apiPermission none
     * @apiVersion 1.5.0
     *
     * @param username Username
     * @param password Password
     * @param longLasted Remember the user next time, create a long lasted session.
     * @return Response
     */
    @POST
    @Path("login")
    public Response login(
        @FormParam("username") String username,
        @FormParam("password") String password,
        @FormParam("code") String validationCodeStr,
        @FormParam("remember") boolean longLasted) {
        // Validate the input data
        username = StringUtils.strip(username);
        password = StringUtils.strip(password);

        // Resolve the client address from the X-Forwarded-For chain (rightmost-untrusted traversal): a
        // spoofed leftmost entry cannot let an attacker rotate past the per-account / per-network limit.
        String clientIp = ClientAddressResolver.getInstance().resolve(request);
        LoginThrottleStore throttle = LoginThrottleStore.getInstance();

        // Blocked check runs BEFORE authentication, so an existing and a nonexistent account get the identical
        // 429 contract (no user enumeration). The Retry-After is bounded and identical either way.
        LoginThrottleStore.ThrottleDecision decision = throttle.checkLoginBlocked(username, clientIp);
        if (decision.isBlocked()) {
            return Response.status(429)
                    .header("Retry-After", decision.getRetryAfterSeconds())
                    .entity(Json.createObjectBuilder().add("type", "RateLimited")
                            .add("message", "Too many login attempts. Try again later.").build())
                    .build();
        }

        // Global bulkhead: cap the rate of expensive password-hash work admitted past the cheap per-source
        // checks, so a distributed low-per-source attack cannot saturate CPU. Sheds with a bounded Retry-After
        // and self-heals; never consulted by already-authenticated requests. This RESERVE happens BEFORE the
        // expensive bcrypt verify below, which is what backstops the check-then-authenticate race: the blocked
        // check (checkLoginBlocked) and the per-account failure bump (recordLoginFailure) are separate, so a
        // burst of concurrent requests at the (threshold - 1) boundary can all pass the check; capping the
        // rate of admitted expensive work here bounds how much of that burst actually runs bcrypt at once, and
        // the per-account counter still locks the account after the bounded overshoot.
        if (!throttle.tryAdmitLoginWork()) {
            return Response.status(429)
                    .header("Retry-After", throttle.loginWorkRetryAfterSeconds())
                    .entity(Json.createObjectBuilder().add("type", "RateLimited")
                            .add("message", "Too many login attempts. Try again later.").build())
                    .build();
        }

        // Get the user
        UserDao userDao = new UserDao();
        User user = null;
        if (Constants.GUEST_USER_ID.equals(username)) {
            if (ConfigUtil.getConfigBooleanValue(ConfigType.GUEST_LOGIN)) {
                // Login as guest
                user = userDao.getActiveByUsername(Constants.GUEST_USER_ID);
            }
        } else {
            // Login as a normal user
            user = AuthenticationUtil.authenticate(username, password);
        }
        if (user == null) {
            throttle.recordLoginFailure(username, clientIp);
            throw new ForbiddenClientException();
        }

        // Reject a disabled account BEFORE minting a session token / setting a cookie
        // (shared User.isDisabled() eligibility predicate). The internal and LDAP auth
        // handlers already return null for a disabled user, but the guest-login branch
        // resolves the guest directly via getActiveByUsername, which still returns a
        // disabled account — so without this guard a disabled guest gets a token minted
        // and only the NEXT request is rejected by SecurityFilter.injectUser. Placed
        // before TOTP so a disabled user is never prompted for an OTP, and the response is
        // indistinguishable from a bad password (do not leak "exists but disabled"): record
        // the same rate-limiter failure the bad-password path records.
        if (user.isDisabled()) {
            throttle.recordLoginFailure(username, clientIp);
            throw new ForbiddenClientException();
        }

        // Two factor authentication. The rate limiter is NOT cleared until the FULL
        // authentication (password AND, if enabled, TOTP) succeeds, so a wrong TOTP
        // code counts toward lockout just like a wrong password. Otherwise an attacker
        // holding the password could brute-force the 6-digit code without limit.
        if (user.getTotpKey() != null) {
            // If TOTP is enabled, ask a validation code. This is a prompt, not a failed
            // attempt, so no failure is recorded here.
            if (Strings.isNullOrEmpty(validationCodeStr)) {
                throw new ClientException("ValidationCodeRequired", "An OTP validation code is required");
            }

            // Check the validation code
            int validationCode = ValidationUtil.validateInteger(validationCodeStr, "code");
            GoogleAuthenticator googleAuthenticator = new GoogleAuthenticator();
            if (!googleAuthenticator.authorize(user.getTotpKey(), validationCode)) {
                // A wrong TOTP code is a failed authentication attempt: record it against
                // the same IP + user keys used for wrong passwords, so repeated wrong
                // codes trigger lockout.
                throttle.recordLoginFailure(username, clientIp);
                throw new ForbiddenClientException();
            }
        }

        // Clear the throttle state on fully successful auth (password + TOTP, if enabled)
        throttle.recordLoginSuccess(username, clientIp);

        // The resolved client address is also the recorded session IP (telemetry).
        String ip = clientIp;

        // Create a new session token
        AuthenticationTokenDao authenticationTokenDao = new AuthenticationTokenDao();
        AuthenticationToken authenticationToken = new AuthenticationToken()
            .setUserId(user.getId())
            .setLongLasted(longLasted)
            .setIp(StringUtils.abbreviate(ip, 45))
            .setUserAgent(StringUtils.abbreviate(request.getHeader("user-agent"), 1000))
            // Proof-time stamp: the epoch of the user the authenticating transaction just validated (also
            // covers the guest branch, which resolved the guest via getActiveByUsername into this same user).
            .setCredentialEpoch(user.getCredentialEpoch());
        String token = authenticationTokenDao.create(authenticationToken);
        
        // Cleanup old session tokens
        authenticationTokenDao.deleteOldSessionToken(user.getId());

        JsonObjectBuilder response = Json.createObjectBuilder();
        int maxAge = longLasted ? TokenBasedSecurityFilter.TOKEN_LONG_LIFETIME : -1;
        // SameSite=Lax mitigates CSRF while still permitting top-level cross-site
        // navigations (needed for the OIDC redirect return, which Strict would break).
        NewCookie cookie = new NewCookie.Builder(TokenBasedSecurityFilter.COOKIE_NAME)
                .value(token)
                .path("/")
                .maxAge(maxAge)
                .secure(true)
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.LAX)
                .build();
        // Additive CSRF companion cookie (non-HttpOnly, JS-readable) derived from this session's token id.
        return Response.ok().entity(response.build())
                .cookie(cookie, CsrfTokenUtil.buildSessionCookie(token))
                .build();
    }

    /**
     * Logs out the user and deletes the active session.
     *
     * @api {post} /user/logout Logout a user
     * @apiDescription This resource deletes the authentication token created by POST /user/login and removes the cookie.
     * @apiName PostUserLogout
     * @apiGroup User
     * @apiSuccess {String} auth_token An expired cookie named auth_token containing no value
     * @apiError (client) ForbiddenError Access denied
     * @apiError (server) AuthenticationTokenError Error deleting the authentication token
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @POST
    @Path("logout")
    public Response logout() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Delete the session token if one exists (absent for header-auth users)
        String authToken = getAuthToken();
        String oidcIdToken = null;

        if (authToken != null) {
            AuthenticationTokenDao authenticationTokenDao = new AuthenticationTokenDao();
            AuthenticationToken authenticationToken = authenticationTokenDao.get(authToken);
            if (authenticationToken != null) {
                oidcIdToken = authenticationToken.getOidcIdToken();
                try {
                    authenticationTokenDao.delete(authToken);
                } catch (Exception e) {
                    throw new ServerException("AuthenticationTokenError", "Error deleting the authentication token: " + authToken, e);
                }
            }
        }

        // Build response with cleared cookie. Flags must match the login cookie
        // (secure, httpOnly, SameSite=Lax) so the browser overwrites/expires the
        // same cookie instead of leaving the authenticated one in place.
        JsonObjectBuilder response = Json.createObjectBuilder();
        NewCookie cookie = new NewCookie.Builder(TokenBasedSecurityFilter.COOKIE_NAME)
                .value(null)
                .path("/")
                .maxAge(-1)
                .expiry(new Date(1))
                .secure(true)
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.LAX)
                .build();

        // Determine external logout redirect (priority: explicit URL > OIDC end_session > none).
        // The OIDC branch is composed from ONE config snapshot inside resolveLogoutUrl, so a
        // provider change landing mid-logout cannot produce a torn logout URL (endpoint of one
        // provider + redirect of another) or send id_token_hint to the wrong endpoint.
        String logoutUrl = System.getProperty("docs.logout_url");

        if (logoutUrl == null) {
            logoutUrl = OidcResource.resolveLogoutUrl(oidcIdToken);
        }

        if (logoutUrl != null) {
            response.add("logout_url", logoutUrl);
        }

        return Response.ok().entity(response.build())
                .cookie(cookie, CsrfTokenUtil.buildClearedSessionCookie())
                .build();
    }

    /**
     * Deletes the current user.
     *
     * @api {delete} /user Delete the current user
     * @apiDescription All associated entities will be deleted as well.
     * @apiName DeleteUser
     * @apiGroup User
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied or the user cannot be deleted
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @DELETE
    public Response delete() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Ensure that the admin or guest users are not deleted
        if (hasBaseFunction(BaseFunction.ADMIN) || principal.isGuest()) {
            throw new ClientException("ForbiddenError", "This user cannot be deleted");
        }

        // #111: lock the owner (self) row FOR UPDATE and guard under the lock, held to commit so it spans
        // the owner-scoped trash below. A concurrent direct share/ACL grant on one of this account's
        // documents takes the SAME owner-row lock, so the two serialize: a grant that commits first is seen
        // by the guard (this delete is refused); a delete that commits first leaves the waiting grant to
        // re-read the now-deleted owner/document and abort. BLOCK the delete when the account still owns
        // documents directly shared with other principals — fail closed, non-disclosive.
        User lockedSelf = CredentialLifecycleUtil.lockActiveUser(principal.getId());
        if (lockedSelf == null) {
            throw new ClientException("ForbiddenError", "This user cannot be deleted");
        }
        if (CredentialLifecycleUtil.hasSharedDocuments(principal.getId())) {
            throw new ClientException("SharedDocumentsError",
                    "This account owns documents shared with other users and cannot be deleted");
        }

        // #133: lock all tags where this user has WRITE and the tag is linked to a surviving foreign
        // document, FOR UPDATE. AclDao.delete takes the same tag-row lock, so a concurrent ACL revoke
        // on one of these tags blocks until this transaction commits — closing the write-skew where both
        // a self-delete and an ACL revoke see 2 holders and both proceed to 0.
        CredentialLifecycleUtil.lockForeignLinkedWriteTags(principal.getId());

        // #122 self-delete guard (evaluated under the self owner-row lock held above): refuse when this
        // account is the SOLE WRITE holder of a tag that a SURVIVING document (owned by another user) still
        // uses. Self-delete has no reassignment target, so silently deleting would orphan the tag (its owner
        // soft-deleted) and clean_storage would later purge it, stripping the tag from the other owner's
        // document — data loss. The only non-destructive answer is to refuse and direct the user to an admin
        // reassign-delete (DELETE /user/:username?reassign_to_username=…), which reassigns such tags.
        if (CredentialLifecycleUtil.hasSoleWriteTagLinkedToSurvivingDocument(principal.getId())) {
            throw new ClientException("SoleTagWriteHolderError",
                    "This account is the sole editor of a tag used on another user's document; an admin must "
                    + "reassign your documents and tags before your account can be deleted");
        }

        // Gracefully handle workflow references (never blocks): collect affected route models and
        // cancel active routes with an open step targeting this user.
        List<String> affectedRouteModels = PrincipalDeletionUtil.findAffectedRouteModelNames(principal.getId());
        PrincipalDeletionUtil.cancelRoutesTargetingPrincipal(principal.getId(), principal.getId());

        // Find linked data
        DocumentDao documentDao = new DocumentDao();
        List<Document> documentList = documentDao.findByUserId(principal.getId());
        UserDao userDao = new UserDao();
        FileDao fileDao = new FileDao();
        List<File> fileList = fileDao.findByUserId(principal.getId());

        // Delete the user
        userDao.delete(principal.getName(), principal.getId());

        sendDeletionEvents(documentList, fileList);

        // Return OK plus a warning payload naming any route models that referenced this user.
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        addRouteModelsAffected(response, affectedRouteModels);
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Deletes a user.
     *
     * @api {delete} /user/:username Delete a user
     * @apiDescription The departing user's documents are reassigned to the required target user
     * (their content is preserved, decryption keeps using the departing user's retained key), then
     * the departing user is soft-deleted along with their remaining associated entities.
     * @apiName DeleteUserUsername
     * @apiGroup User
     * @apiParam {String} username Username
     * @apiParam {String} reassign_to_username Query parameter: username of the surviving user to reassign this user's documents to (required)
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied or the user cannot be deleted
     * @apiError (client) UserNotFound The user does not exist
     * @apiError (client) ValidationError The reassignment target is missing, inactive, or the departing user itself
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @param username Username
     * @param reassignToUsername Username of the surviving user to reassign the departing user's documents to
     * @return Response
     */
    @DELETE
    @Path("{username: [a-zA-Z0-9_@.-]+}")
    public Response delete(@PathParam("username") String username,
            @QueryParam("reassign_to_username") String reassignToUsername) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        // Cannot delete the guest user
        if (Constants.GUEST_USER_ID.equals(username)) {
            throw new ClientException("ForbiddenError", "The guest user cannot be deleted");
        }

        // Check that the user exists
        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(username);
        if (user == null) {
            throw new ClientException("UserNotFound", "The user does not exist");
        }

        // Ensure that the admin user is not deleted
        RoleBaseFunctionDao roleBaseFunctionDao = new RoleBaseFunctionDao();
        Set<String> baseFunctionSet = roleBaseFunctionDao.findByRoleId(Sets.newHashSet(user.getRoleId()));
        if (baseFunctionSet.contains(BaseFunction.ADMIN.name())) {
            throw new ClientException("ForbiddenError", "The admin user cannot be deleted");
        }

        // Validate the reassignment target: it must be provided, resolve to an active (non-deleted)
        // user, and be DISTINCT from the departing user. The departing user's documents are reassigned
        // to it, so an absent/inactive/self target is rejected before any mutation.
        reassignToUsername = ValidationUtil.validateLength(reassignToUsername, "reassign_to_username", 1, 50, false);
        User reassignTarget = userDao.getActiveByUsername(reassignToUsername);
        if (reassignTarget == null) {
            throw new ClientException("ValidationError", "The reassignment target user does not exist or is not active");
        }
        if (reassignTarget.getId().equals(user.getId())) {
            throw new ClientException("ValidationError", "Cannot reassign a user's documents to the user being deleted");
        }

        // Collect affected route models for the response payload (a read; takes no lock). The actual route
        // cancellation — which locks each route's DOCUMENT row FOR UPDATE — is DEFERRED until AFTER the
        // user-row locks below, so this path acquires USER before DOCUMENT (see the ordering note there).
        List<String> affectedRouteModels = PrincipalDeletionUtil.findAffectedRouteModelNames(user.getId());

        // Lock BOTH the departing and the target owner rows FOR UPDATE, in a deterministic id order so
        // every multi-owner locker uses the same order (deadlock-safe). Each lock is eligibility-scoped, so
        // a concurrently-deleted participant yields null and the delete fails cleanly with no reassignment
        // (closes the TOCTOU between the active-check above and the reassignment below — documents must not
        // land on a now-soft-deleted owner). Held to commit, these locks serialize the reassignment against
        // any concurrent direct share/ACL grant on either owner's documents (which take the same owner-row
        // lock), so a grant can never ride a stale owner past the transfer into the new owner's lifecycle.
        User lockedDeparting;
        User lockedTarget;
        if (user.getId().compareTo(reassignTarget.getId()) <= 0) {
            lockedDeparting = CredentialLifecycleUtil.lockActiveUser(user.getId());
            lockedTarget = CredentialLifecycleUtil.lockActiveUser(reassignTarget.getId());
        } else {
            lockedTarget = CredentialLifecycleUtil.lockActiveUser(reassignTarget.getId());
            lockedDeparting = CredentialLifecycleUtil.lockActiveUser(user.getId());
        }
        if (lockedDeparting == null || lockedTarget == null) {
            throw new ClientException("ValidationError", "The reassignment target user does not exist or is not active");
        }

        // Cancel active routes with an open step targeting the departing user. This locks each affected
        // route's DOCUMENT row FOR UPDATE, so it MUST run AFTER the user-row locks above: this path then
        // acquires USER before DOCUMENT, the SAME order the self-delete path uses (lock self, then cancel
        // routes). Running it BEFORE the user locks (as the code previously did) made the admin path lock
        // DOCUMENT->USER while self-delete locks USER->DOCUMENT — a genuine cross-path deadlock cycle
        // (PG 40P01) when a document's route targets a self-deleting user who is also this admin-delete's
        // reassignment target. It still runs before the reassignment, the user soft-delete, and the events.
        PrincipalDeletionUtil.cancelRoutesTargetingPrincipal(user.getId(), principal.getId());

        // Reassign the departing user's ACTIVE documents (and the departing user's tags linked to them)
        // to the target, then grant the target READ+WRITE access on each reassigned document. This must
        // run BEFORE the user soft-delete and BEFORE the deletion events so the reassigned documents and
        // their files are excluded from destruction.
        Set<String> reassignedDocumentIds = new java.util.HashSet<>(
                userDao.reassignOwnedDocuments(user.getId(), lockedTarget.getId()));
        grantOwnershipAcls(reassignedDocumentIds, lockedTarget.getId());

        // Find linked data, EXCLUDING the reassigned documents and their files: firing
        // DocumentDeletedAsyncEvent would remove a now-live document from Lucene, and
        // FileDeletedAsyncEvent would physically delete the (retained) encrypted bytes.
        DocumentDao documentDao = new DocumentDao();
        List<Document> documentList = documentDao.findByUserId(user.getId()).stream()
                .filter(document -> !reassignedDocumentIds.contains(document.getId()))
                .collect(Collectors.toList());
        FileDao fileDao = new FileDao();
        List<File> fileList = fileDao.findByUserId(user.getId()).stream()
                .filter(file -> file.getDocumentId() == null || !reassignedDocumentIds.contains(file.getDocumentId()))
                .collect(Collectors.toList());

        // Delete the user, sparing the files that back the reassigned documents from the owner-scoped
        // soft-delete inside UserDao.delete.
        userDao.delete(user.getUsername(), principal.getId(), reassignedDocumentIds);

        sendDeletionEvents(documentList, fileList);

        // Return OK plus a warning payload naming any route models that referenced this user.
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        addRouteModelsAffected(response, affectedRouteModels);
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Grant the target user READ + WRITE USER ACLs on each reassigned document. Ownership
     * (DOC_IDUSER_C) does NOT by itself grant access in Teedy — access is entirely ACL-driven — so a
     * reassigned owner cannot open their new documents until these direct grants exist.
     *
     * <p>Idempotency is keyed on the existence of a DIRECT USER ACL row (via
     * {@link AclDao#hasDirectUserAcl}), NOT on effective permission: {@code checkPermission} can return
     * true through admin-bypass, tag inheritance, or a transient ROUTING ACL — none of which is a
     * durable direct grant. Skipping creation on that basis would lock the new owner out the moment the
     * transient/inherited access ends. So a direct READ and a direct WRITE row are created unless those
     * exact rows already exist (a collaborator already directly shared the document keeps a single
     * grant).</p>
     *
     * @param documentIds Reassigned document IDs
     * @param targetUserId The surviving user that becomes the owner
     */
    private void grantOwnershipAcls(Set<String> documentIds, String targetUserId) {
        AclDao aclDao = new AclDao();
        for (String documentId : documentIds) {
            for (PermType perm : new PermType[] { PermType.READ, PermType.WRITE }) {
                if (!aclDao.hasDirectUserAcl(documentId, perm, targetUserId)) {
                    Acl acl = new Acl();
                    acl.setPerm(perm);
                    acl.setType(AclType.USER);
                    acl.setSourceId(documentId);
                    acl.setTargetId(targetUserId);
                    aclDao.create(acl, principal.getId());
                }
            }
        }
    }

    /**
     * Disable time-based one-time password for a specific user.
     *
     * @api {post} /user/:username/disable_totp Disable TOTP authentication for a specific user
     * @apiName PostUserUsernameDisableTotp
     * @apiGroup User
     * @apiParam {String} username Username
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied or connected as guest
     * @apiError (client) ValidationError Validation error
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @param username Username
     * @return Response
     */
    @POST
    @Path("{username: [a-zA-Z0-9_@.-]+}/disable_totp")
    public Response disableTotpUsername(@PathParam("username") String username) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        // Get the user
        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(username);
        if (user == null) {
            throw new ForbiddenClientException();
        }

        // Remove the TOTP key
        user.setTotpKey(null);
        userDao.update(user, principal.getId());

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }
    
    /**
     * Returns the information about the connected user.
     *
     * @api {get} /user Get the current user
     * @apiName GetUser
     * @apiGroup User
     * @apiSuccess {Boolean} anonymous True if no user is connected
     * @apiSuccess {Boolean} is_default_password True if the admin has the default password
     * @apiSuccess {Boolean} onboarding True if the UI needs to display the onboarding
     * @apiSuccess {String} username Username
     * @apiSuccess {String} email E-mail
     * @apiSuccess {Number} storage_quota Storage quota (in bytes)
     * @apiSuccess {Number} storage_current Quota used (in bytes)
     * @apiSuccess {Boolean} totp_enabled True if TOTP authentication is enabled
     * @apiSuccess {String[]} base_functions Base functions
     * @apiSuccess {String[]} groups Groups
     * @apiPermission none
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @GET
    public Response info() {
        JsonObjectBuilder response = Json.createObjectBuilder();
        if (!authenticate()) {
            response.add("anonymous", true);

            // Check if admin has the default password
            UserDao userDao = new UserDao();
            User adminUser = userDao.getById("admin");
            if (adminUser != null && adminUser.getDeleteDate() == null) {
                response.add("is_default_password", Constants.DEFAULT_ADMIN_PASSWORD.equals(adminUser.getPassword()));
            }
        } else {
            // Update the last connection date (null when authenticated via header/proxy). Skip the write on
            // a HEAD: Jersey dispatches HEAD through this @GET method, and this write IS the short-session
            // expiry clock — a cross-site HEAD must not keep a session alive.
            String authToken = getAuthToken();
            if (authToken != null && "GET".equals(request.getMethod())) {
                AuthenticationTokenDao authenticationTokenDao = new AuthenticationTokenDao();
                authenticationTokenDao.updateLastConnectionDate(authToken);
            }
            
            // Build the response
            response.add("anonymous", false);
            UserDao userDao = new UserDao();
            GroupDao groupDao = new GroupDao();
            User user = userDao.getById(principal.getId());
            List<GroupDto> groupDtoList = groupDao.findByCriteria(new GroupCriteria()
                    .setUserId(user.getId())
                    .setRecursive(true), null);
            
            response.add("username", user.getUsername())
                    .add("email", user.getEmail())
                    .add("storage_quota", user.getStorageQuota())
                    .add("storage_current", user.getStorageCurrent())
                    .add("totp_enabled", user.getTotpKey() != null)
                    .add("onboarding", user.isOnboarding());

            // #82 preferred UI locale — emitted only when the user has set one (never pass null to
            // the string overload). The SPA seeds a fresh device's locale from this on login.
            if (user.getLocale() != null) {
                response.add("locale", user.getLocale());
            }

            // #147 preferred dark-mode flag — emitted only when the user has set one, so an absent
            // preference (null) stays distinguishable from an explicit false. The SPA seeds a fresh
            // device's dark mode from this on login (a local choice always wins).
            if (user.getDarkMode() != null) {
                response.add("dark_mode", user.getDarkMode());
            }

            // Base functions
            JsonArrayBuilder baseFunctions = Json.createArrayBuilder();
            for (String baseFunction : ((UserPrincipal) principal).getBaseFunctionSet()) {
                baseFunctions.add(baseFunction);
            }
            
            // Groups
            JsonArrayBuilder groups = Json.createArrayBuilder();
            for (GroupDto groupDto : groupDtoList) {
                groups.add(groupDto.getName());
            }
            
            response.add("base_functions", baseFunctions)
                    .add("groups", groups)
                    .add("is_default_password", hasBaseFunction(BaseFunction.ADMIN) && Constants.DEFAULT_ADMIN_PASSWORD.equals(user.getPassword()));
        }
        
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Returns the information about a user.
     *
     * @api {get} /user/:username Get a user
     * @apiName GetUserUsername
     * @apiGroup User
     * @apiParam {String} username Username
     * @apiSuccess {String} username Username
     * @apiSuccess {String} email E-mail
     * @apiSuccess {Boolean} totp_enabled True if TOTP authentication is enabled
     * @apiSuccess {Number} storage_quota Storage quota (in bytes)
     * @apiSuccess {Number} storage_current Quota used (in bytes)
     * @apiSuccess {String[]} groups Groups
     * @apiSuccess {Boolean} disabled True if the user is disabled
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) UserNotFound The user does not exist
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param username Username
     * @return Response
     */
    @GET
    @Path("{username: [a-zA-Z0-9_@.-]+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response view(@PathParam("username") String username) {
        if (!authenticate() || principal.isGuest()) {
            throw new ForbiddenClientException();
        }
        
        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(username);
        if (user == null) {
            throw new ClientException("UserNotFound", "The user does not exist");
        }
        
        // Groups
        GroupDao groupDao = new GroupDao();
        List<GroupDto> groupDtoList = groupDao.findByCriteria(
                new GroupCriteria().setUserId(user.getId()),
                new SortCriteria(1, true));
        JsonArrayBuilder groups = Json.createArrayBuilder();
        for (GroupDto groupDto : groupDtoList) {
            groups.add(groupDto.getName());
        }
        
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("username", user.getUsername())
                .add("groups", groups)
                .add("email", user.getEmail())
                .add("totp_enabled", user.getTotpKey() != null)
                .add("storage_quota", user.getStorageQuota())
                .add("storage_current", user.getStorageCurrent())
                .add("disabled", user.getDisableDate() != null);
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Returns all active users.
     *
     * @api {get} /user/list Get users
     * @apiName GetUserList
     * @apiGroup User
     * @apiParam {Number} sort_column Column index to sort on
     * @apiParam {Boolean} asc If true, sort in ascending order
     * @apiParam {String} group Filter on this group
     * @apiSuccess {Object[]} users List of users
     * @apiSuccess {String} users.id ID
     * @apiSuccess {String} users.username Username
     * @apiSuccess {String} users.email E-mail
     * @apiSuccess {Boolean} users.totp_enabled True if TOTP authentication is enabled
     * @apiSuccess {Number} users.storage_quota Storage quota (in bytes)
     * @apiSuccess {Number} users.storage_current Quota used (in bytes)
     * @apiSuccess {Number} users.create_date Create date (timestamp)
     * @apiSuccess {Number} users.disabled True if the user is disabled
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param sortColumn Sort index
     * @param asc If true, ascending sorting, else descending
     * @param groupName Only return users from this group
     * @return Response
     */
    @GET
    @Path("list")
    public Response list(
            @QueryParam("sort_column") Integer sortColumn,
            @QueryParam("asc") Boolean asc,
            @QueryParam("group") String groupName) {
        if (!authenticate() || principal.isGuest()) {
            throw new ForbiddenClientException();
        }
        
        JsonArrayBuilder users = Json.createArrayBuilder();
        SortCriteria sortCriteria = new SortCriteria(sortColumn, asc);

        // Validate the group
        String groupId = null;
        if (!Strings.isNullOrEmpty(groupName)) {
            GroupDao groupDao = new GroupDao();
            Group group = groupDao.getActiveByName(groupName);
            if (group != null) {
                groupId = group.getId();
            }
        }
        
        UserDao userDao = new UserDao();
        RoleBaseFunctionDao roleBaseFunctionDao = new RoleBaseFunctionDao();
        List<UserDto> userDtoList = userDao.findByCriteria(new UserCriteria().setGroupId(groupId), sortCriteria);

        // Mirror the update endpoint's disable-refusal rule (see update()): the guest
        // user and any user with the ADMIN base function cannot be disabled. Exposing
        // `admin` lets the admin UI hide the disable/enable toggle for those rows so it
        // does not present a false-success affordance. Resolve the ADMIN flag for every
        // distinct role in a single query to avoid an N+1 lookup over the user list.
        Set<String> roleIdSet = userDtoList.stream()
                .map(UserDto::getRoleId)
                .collect(Collectors.toSet());
        Set<String> adminRoleIds = roleBaseFunctionDao.getRoleIdsWithBaseFunction(BaseFunction.ADMIN.name(), roleIdSet);

        for (UserDto userDto : userDtoList) {
            boolean admin = adminRoleIds.contains(userDto.getRoleId());
            users.add(Json.createObjectBuilder()
                    .add("id", userDto.getId())
                    .add("username", userDto.getUsername())
                    .add("email", userDto.getEmail())
                    .add("totp_enabled", userDto.getTotpKey() != null)
                    .add("storage_quota", userDto.getStorageQuota())
                    .add("storage_current", userDto.getStorageCurrent())
                    .add("create_date", userDto.getCreateTimestamp())
                    .add("admin", admin)
                    .add("disabled", userDto.getDisableTimestamp() != null));
        }
        
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("users", users);
        return Response.ok().entity(response.build()).build();
    }
    
    /**
     * Returns all active sessions.
     *
     * @api {get} /user/session Get active sessions
     * @apiDescription This resource lists all active token which can be used to log in to the current user account.
     * @apiName GetUserSession
     * @apiGroup User
     * @apiSuccess {Object[]} sessions List of sessions
     * @apiSuccess {Number} create_date Create date of this token
     * @apiSuccess {String} ip IP used to log in
     * @apiSuccess {String} user_agent User agent used to log in
     * @apiSuccess {Number} last_connection_date Last connection date (timestamp)
     * @apiSuccess {Boolean} current If true, this token is the current one
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @GET
    @Path("session")
    public Response session() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        
        // Get the value of the session token
        String authToken = getAuthToken();
        
        JsonArrayBuilder sessions = Json.createArrayBuilder();

        // The guest user cannot see other sessions
        if (!principal.isGuest()) {
            AuthenticationTokenDao authenticationTokenDao = new AuthenticationTokenDao();
            for (AuthenticationToken authenticationToken : authenticationTokenDao.getByUserId(principal.getId())) {
                JsonObjectBuilder session = Json.createObjectBuilder()
                        .add("create_date", authenticationToken.getCreationDate().getTime())
                        .add("ip", JsonUtil.nullable(authenticationToken.getIp()))
                        .add("user_agent", JsonUtil.nullable(authenticationToken.getUserAgent()));
                if (authenticationToken.getLastConnectionDate() != null) {
                    session.add("last_connection_date", authenticationToken.getLastConnectionDate().getTime());
                }
                session.add("current", authenticationToken.getId().equals(authToken));
                sessions.add(session);
            }
        }
        
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("sessions", sessions);
        return Response.ok().entity(response.build()).build();
    }
    
    /**
     * Deletes all active sessions except the one used for this request.
     *
     * @api {delete} /user/session Delete all sessions
     * @apiDescription This resource deletes all active token linked to this account, except the one used to make this request.
     * @apiName DeleteUserSession
     * @apiGroup User
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied or connected as guest
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @DELETE
    @Path("session")
    public Response deleteSession() {
        if (!authenticate() || principal.isGuest()) {
            throw new ForbiddenClientException();
        }

        // Get the value of the session token
        String authToken = getAuthToken();

        // Remove all other sessions, keeping the current one. A header-authenticated user has no session
        // token (nothing to keep) — there is no token-based session to prune, so this is a no-op for them.
        if (authToken != null) {
            AuthenticationTokenDao authenticationTokenDao = new AuthenticationTokenDao();
            authenticationTokenDao.deleteAllExceptToken(principal.getId(), authToken);
        }

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Mark the onboarding experience as passed.
     *
     * @api {post} /user/onboarded Mark the onboarding experience as passed
     * @apiDescription Once the onboarding experience has been passed by the user, this resource prevent it from being displayed again.
     * @apiName PostUserOnboarded
     * @apiGroup User
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission user
     * @apiVersion 1.7.0
     *
     * @return Response
     */
    @POST
    @Path("onboarded")
    public Response onboarded() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Save it
        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(principal.getName());
        user.setOnboarding(false);
        userDao.updateOnboarding(user);

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }
    
    /**
     * Enable time-based one-time password.
     *
     * @api {post} /user/enable_totp Enable TOTP authentication
     * @apiDescription This resource enables the Time-based One-time Password authentication.
     * All following login will need a validation code generated from the given secret seed.
     * @apiName PostUserEnableTotp
     * @apiGroup User
     * @apiSuccess {String} secret Secret TOTP seed to initiate the algorithm
     * @apiError (client) ForbiddenError Access denied or connected as guest
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @POST
    @Path("enable_totp")
    public Response enableTotp() {
        if (!authenticate() || principal.isGuest()) {
            throw new ForbiddenClientException();
        }
        
        // Create a new TOTP key
        GoogleAuthenticator gAuth = new GoogleAuthenticator();
        final GoogleAuthenticatorKey key = gAuth.createCredentials();
        
        // Save it
        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(principal.getName());
        user.setTotpKey(key.getKey());
        userDao.update(user, principal.getId());
        
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("secret", key.getKey());
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Test time-based one-time password.
     *
     * @api {post} /user/test_totp Test TOTP authentication
     * @apiDescription Test a TOTP validation code.
     * @apiName PostUserTestTotp
     * @apiParam {String} code TOTP validation code
     * @apiGroup User
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError The validation code is not valid or access denied
     * @apiPermission user
     * @apiVersion 1.6.0
     *
     * @return Response
     */
    @POST
    @Path("test_totp")
    public Response testTotp(@FormParam("code") String validationCodeStr) {
        if (!authenticate() || principal.isGuest()) {
            throw new ForbiddenClientException();
        }

        // Get the user
        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(principal.getName());

        // Test the validation code
        if (user.getTotpKey() != null) {
            int validationCode = ValidationUtil.validateInteger(validationCodeStr, "code");
            GoogleAuthenticator googleAuthenticator = new GoogleAuthenticator();
            if (!googleAuthenticator.authorize(user.getTotpKey(), validationCode)) {
                throw new ForbiddenClientException();
            }
        }

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }
    
    /**
     * Disable time-based one-time password for the current user.
     *
     * @api {post} /user/disable_totp Disable TOTP authentication for the current user
     * @apiName PostUserDisableTotp
     * @apiGroup User
     * @apiParam {String{1..100}} password Password
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied or connected as guest
     * @apiError (client) ValidationError Validation error
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param password Password
     * @return Response
     */
    @POST
    @Path("disable_totp")
    public Response disableTotp(@FormParam("password") String password) {
        if (!authenticate() || principal.isGuest()) {
            throw new ForbiddenClientException();
        }
        
        // Validate the input data
        password = ValidationUtil.validateLength(password, "password", 1, 100, false);

        // Check the password and get the user through the origin-aware handler chain (NOT the raw
        // origin-blind UserDao.authenticate): the chain refuses an external-origin account, so a
        // password planted on an OIDC/LDAP account through recovery cannot be used to disable TOTP.
        UserDao userDao = new UserDao();
        User user = AuthenticationUtil.authenticate(principal.getName(), password);
        if (user == null) {
            throw new ForbiddenClientException();
        }
        
        // Remove the TOTP key
        user.setTotpKey(null);
        userDao.update(user, principal.getId());
        
        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Create a key to reset a password and send it by email.
     *
     * @api {post} /user/password_lost Create a key to reset a password and send it by email
     * @apiName PostUserPasswordLost
     * @apiGroup User
     * @apiParam {String} username Username
     * @apiSuccess {String} status Status OK
     * @apiError (client) ValidationError Validation error
     * @apiPermission none
     * @apiVersion 1.5.0
     *
     * @param username Username
     * @return Response
     */
    @POST
    @Path("password_lost")
    @Produces(MediaType.APPLICATION_JSON)
    public Response passwordLost(@FormParam("username") String username) {
        authenticate();

        // This endpoint ALWAYS returns the same generic OK, whether or not the account exists and whether or
        // not the recovery path is currently throttled, so it can never be used to enumerate users.
        Response response = Response.ok().entity(Json.createObjectBuilder()
                .add("status", "ok")
                .build()).build();

        // A missing/blank username resolves to no user: return the generic OK without touching the database
        // (the earlier code validated the wrong argument, so a blank username fell through to findByCriteria
        // and returned the first user in the table).
        if (StringUtils.isBlank(username)) {
            return response;
        }

        // Rate-limit the recovery path per account / network. When limited, silently skip token creation and
        // mail — never signal the limit through the response body, status, or a Retry-After header.
        String clientIp = ClientAddressResolver.getInstance().resolve(request);
        boolean recoveryAllowed = LoginThrottleStore.getInstance().tryRecovery(username, clientIp);
        if (!recoveryAllowed) {
            return response;
        }

        // Check for user existence
        UserDao userDao = new UserDao();
        List<UserDto> userDtoList = userDao.findByCriteria(new UserCriteria().setUserName(username), null);
        if (userDtoList.isEmpty()) {
            // Equalize the dominant work between the existing and nonexistent paths so response timing does
            // not reveal whether the account exists. Both paths run the same lookup above; only an existing
            // account additionally persists a recovery-key row (the extra DB round-trip). Spend a comparable,
            // side-effect-FREE and bounded round-trip here (a token draw plus one read-only lookup that
            // matches nothing) instead of a state-mutating UPDATE, so this unauthenticated endpoint neither
            // amplifies writes nor lets a dummy delete interleave with a concurrent real key creation for the
            // same username. The residual timing difference (a read vs. an INSERT) is small and, critically,
            // bounded: the per-account and global recovery limiters (tryRecovery, above) cap how many probes
            // an attacker can send against any one account before suppression, so too few samples are
            // collectable to resolve it.
            PasswordRecoveryUtil.equalizeNonexistentRecovery();
            return response;
        }
        UserDto user = userDtoList.get(0);

        // Create the password recovery key
        PasswordRecoveryDao passwordRecoveryDao = new PasswordRecoveryDao();
        PasswordRecovery passwordRecovery = new PasswordRecovery();
        passwordRecovery.setUsername(user.getUsername());
        passwordRecoveryDao.create(passwordRecovery);

        // Fire a password lost event AFTER the request transaction durably commits. Posting it before
        // commit would email a working recovery link even if the request then rolled back (the
        // PasswordRecovery row would not exist). The listener consumes only the already-populated
        // recovery id, so the detached entity is safe.
        PasswordLostEvent passwordLostEvent = new PasswordLostEvent();
        passwordLostEvent.setUser(user);
        passwordLostEvent.setPasswordRecovery(passwordRecovery);
        ThreadLocalContext.get().getCompletionRegistry().registerAfterCommit(
                () -> AppContext.getInstance().getMailEventBus().post(passwordLostEvent));

        // Always return OK
        return response;
    }

    /**
     * Reset the user's password.
     *
     * @api {post} /user/password_reset Reset the user's password
     * @apiName PostUserPasswordReset
     * @apiGroup User
     * @apiParam {String} key Password recovery key
     * @apiParam {String} password New password
     * @apiSuccess {String} status Status OK
     * @apiError (client) KeyNotFound Password recovery key not found
     * @apiError (client) ValidationError Validation error
     * @apiPermission none
     * @apiVersion 1.5.0
     *
     * @param passwordResetKey Password reset key
     * @param password New password
     * @return Response
     */
    @POST
    @Path("password_reset")
    @Produces(MediaType.APPLICATION_JSON)
    public Response passwordReset(
            @FormParam("key") String passwordResetKey,
            @FormParam("password") String password) {
        authenticate();

        // Validate input data
        ValidationUtil.validateRequired("key", passwordResetKey);
        password = ValidationUtil.validateLength(password, "password", 8, 50, true);

        // Read the key (without consuming it) to validate password strength against the target username
        // FIRST, so a weak password is rejected without burning the single-use key.
        PasswordRecoveryDao passwordRecoveryDao = new PasswordRecoveryDao();
        PasswordRecovery passwordRecovery = passwordRecoveryDao.getActiveById(passwordResetKey);
        if (passwordRecovery != null && StringUtils.isNotBlank(password)) {
            ValidationUtil.validatePasswordStrength(password, passwordRecovery.getUsername());
        }

        // Atomically consume the single-use key. The row-count gate inside consume() — not the read above —
        // is the sole authority: a concurrent reset presenting the same key, or an already-consumed/expired
        // key, gets null here and a KeyNotFound (the JPA context is never flushed with a managed change, so
        // nothing leaks past the gate). All the mutations below run in this one request transaction and
        // commit atomically.
        String username = passwordRecoveryDao.consume(passwordResetKey);
        if (username == null) {
            throw new ClientException("KeyNotFound", "Password recovery key not found");
        }

        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(username);

        // Change the password
        user.setPassword(password);
        user = userDao.updatePassword(user, principal.getId());

        // Advance the credential epoch so the reset invalidates EVERY existing session AND API key (closes
        // the #96 race and #97): a credential minted at the old epoch is rejected on its next request. This
        // is the authoritative writer even for a session the token-delete below might miss.
        CredentialLifecycleUtil.bumpEpoch(user.getId());

        // Invalidate every OTHER outstanding recovery key for this user (atomic with the consume above).
        passwordRecoveryDao.deleteActiveByLogin(user.getUsername());

        // Revoke ALL of the user's sessions: a recovery reset must not leave a pre-existing session (which may
        // belong to an attacker) alive. Committed atomically with the password change in this transaction.
        new AuthenticationTokenDao().deleteAllByUserId(user.getId());

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Returns the authentication token value.
     *
     * @return Token value
     */
    private String getAuthToken() {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (TokenBasedSecurityFilter.COOKIE_NAME.equals(cookie.getName())
                        && !Strings.isNullOrEmpty(cookie.getValue())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    /**
     * Add a "route_models_affected" warning array to the response when a deleted/renamed principal
     * was referenced by one or more route models. Those models keep their (now unresolvable) blob
     * and become unstartable; the UI surfaces this warning to the operator. Absent when empty.
     *
     * @param response Response builder
     * @param routeModelNames Names of the affected route models
     */
    static void addRouteModelsAffected(JsonObjectBuilder response, List<String> routeModelNames) {
        if (routeModelNames == null || routeModelNames.isEmpty()) {
            return;
        }
        JsonArrayBuilder affected = Json.createArrayBuilder();
        for (String name : routeModelNames) {
            affected.add(name);
        }
        response.add("route_models_affected", affected);
    }

    /**
     * Send the events about documents and files being deleted.
     * @param documentList A document list
     * @param fileList A file list
     */
    private void sendDeletionEvents(List<Document> documentList, List<File> fileList) {
        // Raise deleted events for documents
        for (Document document : documentList) {
            DocumentDeletedAsyncEvent documentDeletedAsyncEvent = new DocumentDeletedAsyncEvent();
            documentDeletedAsyncEvent.setUserId(principal.getId());
            documentDeletedAsyncEvent.setDocumentId(document.getId());
            ThreadLocalContext.get().addAsyncEvent(documentDeletedAsyncEvent);
        }

        // Raise deleted events for files (don't bother sending document updated event)
        for (File file : fileList) {
            FileDeletedAsyncEvent fileDeletedAsyncEvent = new FileDeletedAsyncEvent();
            fileDeletedAsyncEvent.setUserId(principal.getId());
            fileDeletedAsyncEvent.setFileId(file.getId());
            fileDeletedAsyncEvent.setFileSize(file.getSize());
            ThreadLocalContext.get().addAsyncEvent(fileDeletedAsyncEvent);
        }
    }

}
