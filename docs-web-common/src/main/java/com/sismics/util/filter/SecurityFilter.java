package com.sismics.util.filter;

import com.google.common.collect.Sets;
import com.sismics.docs.core.constant.AuditLogType;
import com.sismics.docs.core.dao.AuditLogDao;
import com.sismics.docs.core.dao.GroupDao;
import com.sismics.docs.core.dao.RoleBaseFunctionDao;
import com.sismics.docs.core.dao.criteria.GroupCriteria;
import com.sismics.docs.core.dao.dto.GroupDto;
import com.sismics.docs.core.model.jpa.AuditLog;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.security.AnonymousPrincipal;
import com.sismics.security.UserPrincipal;
import com.sismics.util.context.ThreadLocalContext;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.sismics.docs.core.constant.Constants;

/**
 * An abstract security filter for user authentication, that injects corresponding users into the request.
 * Successfully authenticated users are injected as UserPrincipal, or as AnonymousPrincipal otherwise.
 *
 * <p>Credential precedence (explicit-scheme-first): the filters are ordered api-key, then token-cookie,
 * then trusted-header. The FIRST filter whose credential resolves to an eligible principal installs it.
 * Every later filter still PROBES its own credential even when a principal is already installed, so a
 * second, differently-owned credential is not silently ignored: a presented credential resolving to a
 * DIFFERENT principal than the installed one is a conflict — the request is rejected (401) and the
 * conflict is written to the audit log through an independent post-rollback transaction (the 4xx rolls
 * the ambient request transaction back, so the audit insert cannot ride on it). An INVALID api key
 * ({@code Bearer tdapi_*} shaped but not resolvable) is rejected with a 401 by the api-key filter and
 * terminates the chain; an invalid/absent cookie or trusted header simply falls through to anonymous.</p>
 *
 * @author pacien
 * @author jtremeaux
 */
public abstract class SecurityFilter implements Filter {
    /**
     * Name of the attribute containing the principal.
     */
    public static final String PRINCIPAL_ATTRIBUTE = "principal";

    /**
     * Name of the request attribute recording the proven authentication mechanism (one of
     * {@link #MECHANISM_TOKEN_COOKIE}, {@link #MECHANISM_API_KEY}, {@link #MECHANISM_TRUSTED_HEADER}).
     */
    public static final String AUTH_MECHANISM_ATTRIBUTE = "auth_mechanism";

    /**
     * Name of the request attribute recording the validated auth-token id for a token-cookie
     * authentication (the principal carries no token id; downstream code that needs it — the CSRF
     * token derivation — reads this attribute rather than re-reading and re-trusting the raw cookie).
     */
    public static final String AUTH_TOKEN_ID_ATTRIBUTE = "auth_token_id";

    /**
     * Name of the request attribute carrying the {@code authorizedEpoch}: the credential epoch of the user
     * that the winning credential proved, read from the fresh user-load that validated the request. A
     * resource minting a new credential on this request (an API key) stamps THIS value onto it — the
     * proof-time epoch — rather than re-reading a possibly-newer "current" epoch, which is what makes a
     * concurrent reset invalidate the freshly-minted credential. Set on the SAME winning-principal
     * installation path as {@link #AUTH_MECHANISM_ATTRIBUTE}, so it is present for a key minted under ANY
     * mechanism (token cookie, api key, or trusted header), not only the two credential filters.
     */
    public static final String AUTHORIZED_EPOCH_ATTRIBUTE = "authorized_epoch";

    public static final String MECHANISM_TOKEN_COOKIE = "token-cookie";
    public static final String MECHANISM_API_KEY = "api-key";
    public static final String MECHANISM_TRUSTED_HEADER = "trusted-header";

    /**
     * Logger.
     */
    static final Logger LOG = LoggerFactory.getLogger(SecurityFilter.class);

    /**
     * Classification of one filter's authentication attempt against a request.
     */
    protected static final class AuthAttempt {
        protected enum Kind {
            /** No credential of this filter's type is present. */
            ABSENT,
            /** A credential is present but invalid, and this filter rejects the request (401). */
            REJECT,
            /** A credential is present but not usable; fall through to the next filter / anonymous. */
            IGNORE,
            /** A credential resolved to an eligible principal. */
            AUTHENTICATED
        }

        private final Kind kind;
        private final User user;
        private final String authTokenId;

        private AuthAttempt(Kind kind, User user, String authTokenId) {
            this.kind = kind;
            this.user = user;
            this.authTokenId = authTokenId;
        }

        protected static AuthAttempt absent() {
            return new AuthAttempt(Kind.ABSENT, null, null);
        }

        protected static AuthAttempt reject() {
            return new AuthAttempt(Kind.REJECT, null, null);
        }

        protected static AuthAttempt ignore() {
            return new AuthAttempt(Kind.IGNORE, null, null);
        }

        protected static AuthAttempt authenticated(User user, String authTokenId) {
            return new AuthAttempt(Kind.AUTHENTICATED, user, authTokenId);
        }
    }

    /**
     * Returns true if the supplied request already has a (non-anonymous) UserPrincipal installed.
     *
     * @param request HTTP request
     * @return True if the supplied request has an UserPrincipal
     */
    private boolean hasIdentifiedUser(HttpServletRequest request) {
        return request.getAttribute(PRINCIPAL_ATTRIBUTE) instanceof UserPrincipal;
    }

    /**
     * @return true if the user is present and eligible (neither deleted nor disabled).
     */
    protected boolean isEligible(User user) {
        return user != null && user.getDeleteDate() == null && user.getDisableDate() == null;
    }

    /**
     * Exact-equality credential-epoch check. A presented credential is honored ONLY while the epoch it was
     * stamped with at mint still equals the user's current epoch. A stamp BELOW the user's epoch is a
     * revoked (stale) credential; a stamp ABOVE it is a corrupt/future value — both are rejected (the
     * caller resolves the mismatch to {@code ignore()} → anonymous, matching the disabled/deleted
     * handling, not a hard 401). A null stamp (a malformed/legacy row) is rejected: it fails closed.
     *
     * @param stampedEpoch the epoch stamped on the credential at mint (nullable)
     * @param userEpoch the user's current credential epoch
     * @return true only when the stamp is present and exactly equals the user's epoch
     */
    protected boolean epochMatches(Long stampedEpoch, long userEpoch) {
        return stampedEpoch != null && stampedEpoch == userEpoch;
    }

    /**
     * Inject an authenticated user into the request attributes.
     *
     * @param request HTTP request
     * @param user User to inject
     */
    private void injectAuthenticatedUser(HttpServletRequest request, User user) {
        UserPrincipal userPrincipal = new UserPrincipal(user.getId(), user.getUsername());

        // Add groups
        GroupDao groupDao = new GroupDao();
        Set<String> groupRoleIdSet = new HashSet<>();
        List<GroupDto> groupDtoList = groupDao.findByCriteria(new GroupCriteria()
                .setUserId(user.getId())
                .setRecursive(true), null);
        Set<String> groupIdSet = Sets.newHashSet();
        for (GroupDto groupDto : groupDtoList) {
            groupIdSet.add(groupDto.getId());
            if (groupDto.getRoleId() != null) {
                groupRoleIdSet.add(groupDto.getRoleId());
            }
        }
        userPrincipal.setGroupIdSet(groupIdSet);

        // Add base functions
        groupRoleIdSet.add(user.getRoleId());
        RoleBaseFunctionDao userBaseFunction = new RoleBaseFunctionDao();
        Set<String> baseFunctionSet = userBaseFunction.findByRoleId(groupRoleIdSet);
        userPrincipal.setBaseFunctionSet(baseFunctionSet);

        // Add email
        userPrincipal.setEmail(user.getEmail());

        request.setAttribute(PRINCIPAL_ATTRIBUTE, userPrincipal);
    }

    /**
     * Inject an anonymous user into the request attributes.
     *
     * @param request HTTP request
     */
    private void injectAnonymousUser(HttpServletRequest request) {
        AnonymousPrincipal anonymousPrincipal = new AnonymousPrincipal();
        anonymousPrincipal.setDateTimeZone(ZoneId.of(Constants.DEFAULT_TIMEZONE_ID));

        request.setAttribute(PRINCIPAL_ATTRIBUTE, anonymousPrincipal);
    }

    @Override
    public void init(FilterConfig filterConfig) {
        // NOP
    }

    @Override
    public void destroy() {
        // NOP
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;

        AuthAttempt attempt = attempt(request);
        switch (attempt.kind) {
            case REJECT:
                rejectUnauthorized(response, "AuthenticationFailed", "The supplied credential is not valid");
                return;
            case AUTHENTICATED:
                if (!hasIdentifiedUser(request)) {
                    injectAuthenticatedUser(request, attempt.user);
                    request.setAttribute(AUTH_MECHANISM_ATTRIBUTE, getMechanism());
                    // Carry the proof-time epoch on the SAME winning-principal path as the mechanism, so a
                    // resource minting a new credential (an API key) stamps the epoch this request's
                    // authorization observed regardless of which mechanism won (cookie, api key, header).
                    request.setAttribute(AUTHORIZED_EPOCH_ATTRIBUTE, attempt.user.getCredentialEpoch());
                    if (attempt.authTokenId != null) {
                        request.setAttribute(AUTH_TOKEN_ID_ATTRIBUTE, attempt.authTokenId);
                    }
                } else {
                    UserPrincipal installed = (UserPrincipal) request.getAttribute(PRINCIPAL_ATTRIBUTE);
                    if (!installed.getId().equals(attempt.user.getId())) {
                        // Two valid credentials for DIFFERENT principals. The winner is immutable; a losing
                        // credential must never merge roles or take over the request.
                        auditCredentialConflict(request, installed.getId(), attempt.user.getId());
                        rejectUnauthorized(response, "AuthenticationConflict",
                                "The request presents conflicting authentication credentials");
                        return;
                    }
                    // Same principal via a second credential: keep the installed (winning) context as-is.
                }
                break;
            case IGNORE:
            case ABSENT:
            default:
                break;
        }

        // Floor: guarantee a principal attribute so downstream code never sees a null principal.
        if (request.getAttribute(PRINCIPAL_ATTRIBUTE) == null) {
            injectAnonymousUser(request);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Writes a 401 JSON error and does NOT continue the filter chain. The ambient request transaction
     * is rolled back by {@code RequestContextFilter} on this 4xx status.
     */
    private void rejectUnauthorized(HttpServletResponse response, String type, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();
        writer.print("{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}");
        // Deliberately NOT flushed: the small body stays buffered so the response is committed only after
        // RequestContextFilter's post-chain completion runs (which persists the conflict audit row through
        // the independent post-rollback transaction). Flushing here would race the client ahead of that
        // durable write.
    }

    /**
     * Records a credential conflict in the audit log. The conflict rejection is a 4xx, so the ambient
     * request transaction rolls back — the audit insert therefore runs in an INDEPENDENT transaction
     * scheduled after the rollback (existing completion machinery, no A1 class modified): a callback is
     * registered on the current frame's after-rollback list, and when it fires (the frame is COMPLETING)
     * {@code TransactionUtil.handle} opens a fresh nested frame that commits the row durably.
     *
     * @param request HTTP request
     * @param installedUserId the id of the principal already installed (the winning credential)
     * @param presentedUserId the id resolved by the conflicting credential
     */
    private void auditCredentialConflict(HttpServletRequest request, String installedUserId, String presentedUserId) {
        final String mechanism = getMechanism();
        final String path = request.getRequestURI();
        // Structured log (no token/secret values — only the path, mechanism, and internal principal ids).
        LOG.warn("Authentication credential conflict: mechanism={} path={} installedPrincipal={} presentedPrincipal={}",
                mechanism, path, installedUserId, presentedUserId);

        ThreadLocalContext.get().getCompletionRegistry().registerAfterRollback(() ->
                TransactionUtil.handle(() -> {
                    AuditLog auditLog = new AuditLog();
                    auditLog.setUserId(installedUserId);
                    auditLog.setEntityId(installedUserId);
                    auditLog.setEntityClass("User");
                    auditLog.setType(AuditLogType.AUTHENTICATION);
                    auditLog.setMessage(StringUtils.abbreviate(
                            "Rejected conflicting credential (" + mechanism + ") for principal "
                                    + presentedUserId + " on " + path, 1000));
                    new AuditLogDao().create(auditLog);
                }));
    }

    /**
     * Evaluates this filter's credential type against the request.
     *
     * @param request HTTP request
     * @return the classified authentication attempt
     */
    protected abstract AuthAttempt attempt(HttpServletRequest request);

    /**
     * @return the mechanism label this filter proves (one of the {@code MECHANISM_*} constants).
     */
    protected abstract String getMechanism();

}
