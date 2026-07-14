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
import com.sismics.docs.core.util.authentication.AuthenticationUtil;
import com.sismics.docs.core.util.PrincipalDeletionUtil;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.docs.rest.constant.BaseFunction;
import com.sismics.docs.rest.util.UserUpdateUtil;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.exception.ServerException;
import com.sismics.rest.util.ValidationUtil;
import com.sismics.security.UserPrincipal;
import com.sismics.util.JsonUtil;
import com.sismics.util.context.ThreadLocalContext;
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
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied or connected as guest
     * @apiError (client) ValidationError Validation error
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param password Password
     * @param email E-Mail
     * @return Response
     */
    @POST
    public Response update(
        @FormParam("password") String password,
        @FormParam("current_password") String currentPassword,
        @FormParam("email") String email) {
        if (!authenticate() || principal.isGuest()) {
            throw new ForbiddenClientException();
        }
        
        // Validate the input data
        password = ValidationUtil.validateLength(password, "password", 8, 50, true);
        email = ValidationUtil.validateLength(email, "email", 1, 100, true);

        // If changing password, verify the current password first
        if (StringUtils.isNotBlank(password)) {
            if (StringUtils.isBlank(currentPassword)) {
                throw new ClientException("CurrentPasswordRequired", "Current password is required to change password");
            }
            if (AuthenticationUtil.authenticate(principal.getName(), currentPassword) == null) {
                throw new ForbiddenClientException();
            }
            ValidationUtil.validatePasswordStrength(password, principal.getName());
        }
        
        // Update the user
        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(principal.getName());
        UserUpdateUtil.applyEmailUpdate(user, email);
        user = userDao.update(user, principal.getId());

        // Change the password
        UserUpdateUtil.applyPasswordUpdate(userDao, user, password, principal.getId());

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
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
            user.setStorageQuota(storageQuota);
        }
        if (disabled != null) {
            // Cannot disable the admin user or the guest user
            RoleBaseFunctionDao userBaseFuction = new RoleBaseFunctionDao();
            Set<String> baseFunctionSet = userBaseFuction.findByRoleId(Sets.newHashSet(user.getRoleId()));
            if (Constants.GUEST_USER_ID.equals(username) || baseFunctionSet.contains(BaseFunction.ADMIN.name())) {
                disabled = false;
            }

            if (disabled && user.getDisableDate() == null) {
                // Recording the disabled date
                user.setDisableDate(new Date());
            } else if (!disabled && user.getDisableDate() != null) {
                // Emptying the disabled date
                user.setDisableDate(null);
            }
        }
        user = userDao.update(user, principal.getId());

        // Change the password
        UserUpdateUtil.applyPasswordUpdate(userDao, user, password, principal.getId());

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

        // Rate limiting: check if IP or username is blocked
        String clientIp = request.getHeader("x-forwarded-for");
        if (Strings.isNullOrEmpty(clientIp)) {
            clientIp = request.getRemoteAddr();
        }
        com.sismics.docs.rest.util.LoginRateLimiter rateLimiter = com.sismics.docs.rest.util.LoginRateLimiter.getInstance();
        long retryAfterIp = rateLimiter.getRetryAfterSeconds(clientIp);
        long retryAfterUser = username != null ? rateLimiter.getRetryAfterSeconds("user:" + username) : 0;
        long retryAfter = Math.max(retryAfterIp, retryAfterUser);
        if (retryAfter > 0) {
            return Response.status(429)
                    .header("Retry-After", retryAfter)
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
            rateLimiter.recordFailure(clientIp);
            if (username != null) rateLimiter.recordFailure("user:" + username);
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
            rateLimiter.recordFailure(clientIp);
            if (username != null) rateLimiter.recordFailure("user:" + username);
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
                rateLimiter.recordFailure(clientIp);
                if (username != null) rateLimiter.recordFailure("user:" + username);
                throw new ForbiddenClientException();
            }
        }

        // Clear rate limiter on fully successful auth (password + TOTP, if enabled)
        rateLimiter.recordSuccess(clientIp);
        if (username != null) rateLimiter.recordSuccess("user:" + username);

        // Get the remote IP
        String ip = request.getHeader("x-forwarded-for");
        if (Strings.isNullOrEmpty(ip)) {
            ip = request.getRemoteAddr();
        }
        
        // Create a new session token
        AuthenticationTokenDao authenticationTokenDao = new AuthenticationTokenDao();
        AuthenticationToken authenticationToken = new AuthenticationToken()
            .setUserId(user.getId())
            .setLongLasted(longLasted)
            .setIp(StringUtils.abbreviate(ip, 45))
            .setUserAgent(StringUtils.abbreviate(request.getHeader("user-agent"), 1000));
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
        return Response.ok().entity(response.build()).cookie(cookie).build();
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

        return Response.ok().entity(response.build()).cookie(cookie).build();
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

        // Gracefully handle workflow references (never blocks): collect affected route models and
        // cancel active routes with an open step targeting this user.
        List<String> affectedRouteModels = PrincipalDeletionUtil.findAffectedRouteModelNames(principal.getId());
        PrincipalDeletionUtil.cancelRoutesTargetingPrincipal(principal.getId(), principal.getId());

        // Find linked data
        DocumentDao documentDao = new DocumentDao();
        List<Document> documentList = documentDao.findByUserId(principal.getId());
        FileDao fileDao = new FileDao();
        List<File> fileList = fileDao.findByUserId(principal.getId());

        // Delete the user
        UserDao userDao = new UserDao();
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

        // Gracefully handle workflow references (never blocks): collect affected route models and
        // cancel active routes with an open step targeting this user.
        List<String> affectedRouteModels = PrincipalDeletionUtil.findAffectedRouteModelNames(user.getId());
        PrincipalDeletionUtil.cancelRoutesTargetingPrincipal(user.getId(), principal.getId());

        // Re-validate the target under a row lock held to commit, closing the TOCTOU between the
        // active-check above and the reassignment below: a concurrent deletion of the target must not
        // leave documents owned by a now-soft-deleted user (which clean_storage would later purge). If
        // the target was concurrently deleted, this returns null and the delete fails cleanly with no
        // reassignment performed.
        User lockedTarget = userDao.getActiveByIdForUpdate(reassignTarget.getId());
        if (lockedTarget == null) {
            throw new ClientException("ValidationError", "The reassignment target user does not exist or is not active");
        }

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
            // Update the last connection date (null when authenticated via header/proxy)
            String authToken = getAuthToken();
            if (authToken != null) {
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
        
        // Remove other tokens
        AuthenticationTokenDao authenticationTokenDao = new AuthenticationTokenDao();
        authenticationTokenDao.deleteByUserId(principal.getId(), authToken);
        
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

        // Check the password and get the user
        UserDao userDao = new UserDao();
        User user = userDao.authenticate(principal.getName(), password);
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

        // Validate input data
        ValidationUtil.validateStringNotBlank("username", username);

        // Prepare response
        Response response = Response.ok().entity(Json.createObjectBuilder()
                .add("status", "ok")
                .build()).build();

        // Check for user existence
        UserDao userDao = new UserDao();
        List<UserDto> userDtoList = userDao.findByCriteria(new UserCriteria().setUserName(username), null);
        if (userDtoList.isEmpty()) {
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

        // Load the password recovery key
        PasswordRecoveryDao passwordRecoveryDao = new PasswordRecoveryDao();
        PasswordRecovery passwordRecovery = passwordRecoveryDao.getActiveById(passwordResetKey);
        if (passwordRecovery != null && StringUtils.isNotBlank(password)) {
            ValidationUtil.validatePasswordStrength(password, passwordRecovery.getUsername());
        }
        if (passwordRecovery == null) {
            throw new ClientException("KeyNotFound", "Password recovery key not found");
        }

        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(passwordRecovery.getUsername());

        // Change the password
        user.setPassword(password);
        user = userDao.updatePassword(user, principal.getId());

        // Deletes password recovery requests
        passwordRecoveryDao.deleteActiveByLogin(user.getUsername());

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
