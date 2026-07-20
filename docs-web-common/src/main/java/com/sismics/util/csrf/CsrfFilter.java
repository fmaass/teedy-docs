package com.sismics.util.csrf;

import com.sismics.security.UserPrincipal;
import com.sismics.util.EnvironmentUtil;
import com.sismics.util.filter.SecurityFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Cross-Site Request Forgery protection filter (mapped AFTER the security filters, so the authenticated
 * principal + proven mechanism are already installed).
 *
 * <p><b>Report-only by default.</b> Enforcement is gated behind {@code DOCS_CSRF_ENFORCE}
 * (default OFF). With enforcement off, the filter NEVER rejects a request: it evaluates, and on a
 * would-block it emits a single structured WARN telemetry line (mechanism, path, reason — never a token
 * or secret value) and continues. This is the E1 preparation phase; enforcement (E2) is a later RC.</p>
 *
 * <p>Evaluated requests are the state-changing ones: any method outside {GET, HEAD, OPTIONS}, plus the
 * enumerated mutating-GET inventory (a GET/HEAD whose path has a server side effect). The proof is a
 * token submitted ONLY via the {@code X-Csrf-Token} request header, checked in constant time, together
 * with a parsed-URI Origin/Referer provenance check against the configured base URL. Requests
 * authenticated by API key are exempt (a bearer API client is not a browser and cannot be CSRF'd);
 * the OIDC callback GET is exempt by normalized route.</p>
 *
 * <p><b>Bootstrap (H2):</b> on EVERY authenticated ambient-credential request — including one it would
 * block — the filter issues-or-repairs the mechanism's CSRF cookie on the response when it is absent or
 * stale, so a pre-enforcement session that never re-logged-in still obtains a token, and the H3
 * retry-once recovery has a fresh cookie to read.</p>
 */
public class CsrfFilter implements Filter {
    private static final Logger LOG = LoggerFactory.getLogger(CsrfFilter.class);

    /**
     * The enumerated mutating-GET inventory: GET/HEAD requests whose handler has a server side effect and
     * are therefore CSRF-evaluated. Entries are ROUTE TEMPLATES in the same representation the inventory
     * test discovers (path parameters collapsed to {@code {}}), and requests are matched against them by
     * pattern (each {@code {}} matches exactly one path segment). This is the SAME representation on both
     * sides, so a future PARAMETERIZED mutating GET (e.g. {@code /document/{}/publish}) added here is
     * actually evaluated at runtime, not just accepted by the inventory test.
     *
     * <ul>
     *   <li>{@code /oidc/login} — writes OIDC state (exempt-by-path in practice: unauthenticated);</li>
     *   <li>{@code /oidc/callback} — consumes state + mints a cookie (exempt-by-route);</li>
     *   <li>{@code /document/export} — writes an audit row + acquires an export permit;</li>
     *   <li>{@code /user} — refreshes {@code lastConnectionDate}, the short-session expiry clock.</li>
     * </ul>
     */
    public static final Set<String> MUTATING_GET_PATHS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "/oidc/login",
            "/oidc/callback",
            "/document/export",
            "/user"
    )));

    /** {@link #MUTATING_GET_PATHS} compiled to segment-wildcard patterns (each {@code {}} → one segment). */
    private static final List<Pattern> MUTATING_GET_PATTERNS = compileTemplates(MUTATING_GET_PATHS);

    /** Normalized route + method of the OIDC callback, exempt from CSRF evaluation. */
    private static final String OIDC_CALLBACK_PATH = "/oidc/callback";

    /**
     * Last evaluation of a state-changing request, exposed as a test seam so integration tests can assert
     * the report-only classification / would-block telemetry without scraping logs. Never read in prod.
     */
    private static final AtomicReference<Evaluation> LAST_EVALUATION = new AtomicReference<>();

    @Override
    public void init(FilterConfig filterConfig) {
        // NOP
    }

    @Override
    public void destroy() {
        // NOP
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;

        Object principalObj = request.getAttribute(SecurityFilter.PRINCIPAL_ATTRIBUTE);
        String mechanism = (String) request.getAttribute(SecurityFilter.AUTH_MECHANISM_ATTRIBUTE);

        // Only ambient-credential (browser-carried) authenticated requests can be CSRF'd. Anonymous and
        // API-key requests carry no ambient credential and are exempt.
        if (!(principalObj instanceof UserPrincipal) || SecurityFilter.MECHANISM_API_KEY.equals(mechanism)) {
            chain.doFilter(request, response);
            return;
        }

        String principalId = ((UserPrincipal) principalObj).getId();
        String authTokenId = (String) request.getAttribute(SecurityFilter.AUTH_TOKEN_ID_ATTRIBUTE);

        // BOOTSTRAP (H2): issue-or-repair the mechanism's CSRF cookie BEFORE the body is written and
        // before any block decision, so even a would-block request returns a fresh cookie.
        bootstrapCookie(request, response, mechanism, authTokenId, principalId);

        // Classify.
        String normalizedPath = normalizedPath(request);
        boolean stateChanging = isStateChanging(request.getMethod(), normalizedPath);
        boolean oidcCallbackExempt = OIDC_CALLBACK_PATH.equals(normalizedPath)
                && "GET".equalsIgnoreCase(request.getMethod());

        if (!stateChanging || oidcCallbackExempt) {
            chain.doFilter(request, response);
            return;
        }

        Evaluation eval = evaluate(request, mechanism, authTokenId, principalId, normalizedPath);
        LAST_EVALUATION.set(eval);

        boolean enforce = EnvironmentUtil.getBooleanConfig("docs.csrf_enforce", "DOCS_CSRF_ENFORCE", false);
        if (eval.wouldBlock) {
            // Structured telemetry (no token/secret values).
            LOG.warn("CSRF would-block: enforce={} mechanism={} method={} path={} reason={}",
                    enforce, eval.mechanism, eval.method, eval.path, eval.reason);
            if (enforce) {
                // E2: reject BEFORE the handler runs (safe even for non-idempotent requests). The bootstrap
                // above already re-set the expected cookie, so the client's retry-once (H3) can read it.
                rejectForbidden(response);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    /**
     * Issues-or-repairs the CSRF cookie for the authenticated session on the response when absent/stale.
     */
    private void bootstrapCookie(HttpServletRequest request, HttpServletResponse response,
                                 String mechanism, String authTokenId, String principalId) {
        if (SecurityFilter.MECHANISM_TOKEN_COOKIE.equals(mechanism)) {
            String expected = CsrfTokenUtil.computeSessionToken(authTokenId);
            if (expected == null) {
                return;
            }
            String present = readCookie(request, CsrfTokenUtil.SESSION_COOKIE_NAME);
            if (!expected.equals(present)) {
                setCookie(response, CsrfTokenUtil.SESSION_COOKIE_NAME, expected, false);
            }
        } else if (SecurityFilter.MECHANISM_TRUSTED_HEADER.equals(mechanism)) {
            // Proxy sessions get a fresh server-keyed token minted each request (short TTL, no stored state).
            // Guard the key seeding so a transient failure never breaks the request in report-only mode.
            try {
                byte[] key = CsrfTokenUtil.getOrCreateProxyKey();
                String token = CsrfTokenUtil.issueProxyToken(principalId, key, System.currentTimeMillis());
                setCookie(response, CsrfTokenUtil.PROXY_COOKIE_NAME, token, true);
            } catch (RuntimeException e) {
                LOG.warn("CSRF proxy-token bootstrap skipped: unable to resolve the proxy key", e);
            }
        }
    }

    /**
     * Evaluates the CSRF proof (token + Origin/Referer provenance) for a state-changing request.
     */
    private Evaluation evaluate(HttpServletRequest request, String mechanism, String authTokenId,
                                String principalId, String normalizedPath) {
        String method = request.getMethod();

        boolean tokenValid;
        String presented;
        if (SecurityFilter.MECHANISM_TRUSTED_HEADER.equals(mechanism)) {
            // Trusted-header sessions carry no session token id; the proof is the server-keyed proxy token
            // submitted in its own header (the SPA/importer mirror the __Host-csrf_proxy cookie there).
            presented = request.getHeader(CsrfTokenUtil.PROXY_HEADER_NAME);
            boolean valid;
            try {
                byte[] key = CsrfTokenUtil.getOrCreateProxyKey();
                valid = CsrfTokenUtil.verifyProxyToken(presented, principalId, key, System.currentTimeMillis());
            } catch (RuntimeException e) {
                // Cannot resolve the key: treat as would-block in report-only (never rejects).
                LOG.warn("CSRF proxy-token verification skipped: unable to resolve the proxy key", e);
                valid = false;
            }
            tokenValid = valid;
        } else {
            presented = request.getHeader(CsrfTokenUtil.HEADER_NAME);
            String expected = CsrfTokenUtil.computeSessionToken(authTokenId);
            tokenValid = presented != null && CsrfTokenUtil.constantTimeEquals(presented, expected);
        }

        Provenance provenance = checkProvenance(request);

        String reason = null;
        if (!tokenValid) {
            reason = presented == null ? "token-missing" : "token-mismatch";
        } else if (provenance == Provenance.FAIL) {
            reason = "origin-mismatch";
        }
        boolean wouldBlock = reason != null;
        return new Evaluation(normalizedPath, mechanism, method, true, wouldBlock, reason);
    }

    /**
     * Parsed-URI Origin/Referer provenance check against the configured base URL. Exact-origin match when
     * Origin is present; canonical Referer fallback; provenance UNAVAILABLE when neither is present (the
     * header token is then the sole proof). {@code Origin: null}, malformed, multi-valued, or
     * userinfo-bearing values are treated as FAIL.
     */
    private Provenance checkProvenance(HttpServletRequest request) {
        return classifyProvenance(
                Collections.list(request.getHeaders("Origin")),
                Collections.list(request.getHeaders("Referer")),
                configuredAuthority());
    }

    /**
     * Parsed-URI Origin/Referer provenance classification (extracted for unit testing). Origin/Referer
     * well-formedness is validated FIRST, independent of the base URL: a null, malformed, multi-valued, or
     * userinfo-bearing provenance header is ALWAYS a failure. Only a well-formed value is then compared to
     * the configured base URL (which requires it to be set); with no base URL, a well-formed value is
     * UNAVAILABLE (cannot validate). Neither header present ⇒ UNAVAILABLE (the header token is the sole proof).
     */
    static Provenance classifyProvenance(List<String> origins, List<String> referers, Authority base) {
        if (origins != null && !origins.isEmpty()) {
            if (origins.size() > 1) {
                return Provenance.FAIL;
            }
            String origin = origins.get(0);
            if (origin == null || "null".equalsIgnoreCase(origin.trim())) {
                return Provenance.FAIL;
            }
            Authority originAuthority = parseAuthority(origin.trim());
            if (originAuthority == null) {
                return Provenance.FAIL;
            }
            if (base == null) {
                // Well-formed origin but no configured base URL to compare against: cannot validate.
                return Provenance.UNAVAILABLE;
            }
            return base.matches(originAuthority) ? Provenance.PASS : Provenance.FAIL;
        }

        if (referers != null && !referers.isEmpty()) {
            if (referers.size() > 1) {
                return Provenance.FAIL;
            }
            String referer = referers.get(0);
            if (referer == null || referer.isBlank()) {
                return Provenance.FAIL;
            }
            Authority refererAuthority = parseAuthority(referer.trim());
            if (refererAuthority == null) {
                return Provenance.FAIL;
            }
            if (base == null) {
                return Provenance.UNAVAILABLE;
            }
            return base.matches(refererAuthority) ? Provenance.PASS : Provenance.FAIL;
        }

        return Provenance.UNAVAILABLE;
    }

    /**
     * @return the authority (scheme, canonical host, effective port) of the configured base URL, or null
     * when unset/unparseable.
     */
    private Authority configuredAuthority() {
        String baseUrl = EnvironmentUtil.getStringConfig("docs.base_url", "DOCS_BASE_URL", null);
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        return parseAuthority(baseUrl.trim());
    }

    /**
     * Parses an absolute URI into a normalized authority, rejecting userinfo-bearing and non-absolute
     * inputs. The default port for the scheme is normalized to the explicit port; host is lower-cased.
     */
    static Authority parseAuthority(String raw) {
        try {
            URI uri = new URI(raw);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            if (uri.getUserInfo() != null) {
                return null;
            }
            scheme = scheme.toLowerCase();
            host = host.toLowerCase();
            int port = uri.getPort();
            if (port == -1) {
                port = defaultPort(scheme);
            }
            if (port == -1) {
                return null;
            }
            return new Authority(scheme, host, port);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static int defaultPort(String scheme) {
        if ("https".equals(scheme)) {
            return 443;
        }
        if ("http".equals(scheme)) {
            return 80;
        }
        return -1;
    }

    /**
     * @return true if the request method + normalized path is a state-changing request subject to CSRF.
     */
    static boolean isStateChanging(String method, String normalizedPath) {
        String m = method == null ? "" : method.toUpperCase();
        boolean safeMethod = m.equals("GET") || m.equals("HEAD") || m.equals("OPTIONS");
        if (!safeMethod) {
            return true;
        }
        // A GET or HEAD is state-changing only when its concrete path MATCHES a mutating-GET template
        // (OPTIONS is never in the inventory). Template matching — not exact-set membership — is what makes
        // a parameterized mutating-GET route actually evaluated.
        return (m.equals("GET") || m.equals("HEAD")) && matchesAny(MUTATING_GET_PATTERNS, normalizedPath);
    }

    /**
     * @return true if the concrete path matches any of the compiled route-template patterns.
     */
    static boolean matchesAny(List<Pattern> patterns, String path) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compiles route templates (with {@code {}} for a single path-parameter segment) into patterns.
     * Exposed package-private so a test can prove a parameterized template evaluates a concrete path.
     */
    static List<Pattern> compileTemplates(Collection<String> templates) {
        List<Pattern> patterns = new ArrayList<>();
        for (String template : templates) {
            patterns.add(templateToPattern(template));
        }
        return patterns;
    }

    /**
     * Converts one route template to a regex: each {@code {}} segment matches exactly one path segment
     * ({@code [^/]+}); every literal segment is quoted.
     */
    static Pattern templateToPattern(String template) {
        String[] segments = template.split("/", -1);
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                regex.append("/");
            }
            String segment = segments[i];
            if (segment.equals("{}")) {
                regex.append("[^/]+");
            } else {
                regex.append(Pattern.quote(segment));
            }
        }
        return Pattern.compile(regex.toString());
    }

    /**
     * Normalizes the request path: strips the servlet context path and a leading {@code /api} segment, so
     * the same inventory matches both production ({@code /api/user}) and the test harness ({@code /user}).
     */
    static String normalizedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = uri;
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        // Strip matrix parameters (";name=value") from every segment BEFORE any other matching.
        // JAX-RS ignores them when selecting a resource method (JSR-370 3.7.1) but getRequestURI()
        // retains them, so "/api/user;x=1" reaches the same handler as "/api/user" while failing a
        // literal inventory comparison. Left in place, that lets a mutating GET be classified
        // non-state-changing and skip token/Origin evaluation entirely. Stripping happens before the
        // "/api" handling so a matrix segment on the prefix itself ("/api;x=1/user") cannot evade it.
        if (path.indexOf(';') >= 0) {
            String[] segments = path.split("/", -1);
            StringBuilder cleaned = new StringBuilder(path.length());
            for (int i = 0; i < segments.length; i++) {
                String segment = segments[i];
                int semicolon = segment.indexOf(';');
                if (semicolon >= 0) {
                    segment = segment.substring(0, semicolon);
                }
                if (i > 0) {
                    cleaned.append('/');
                }
                cleaned.append(segment);
            }
            path = cleaned.toString();
        }
        if (path.equals("/api")) {
            path = "";
        } else if (path.startsWith("/api/")) {
            path = path.substring("/api".length());
        }
        // Drop a trailing slash (except the root) for stable matching.
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static String readCookie(HttpServletRequest request, String name) {
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (jakarta.servlet.http.Cookie cookie : cookies) {
                if (name.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    /**
     * Adds a host-only, Secure, SameSite=Lax, non-HttpOnly Set-Cookie header (Path=/). The raw header is
     * built directly so the {@code __Host-} prefix constraints (no Domain, Secure, Path=/) hold exactly.
     */
    private static void setCookie(HttpServletResponse response, String name, String value, boolean hostPrefix) {
        if (response.isCommitted()) {
            return;
        }
        // __Host- cookies require Secure, Path=/, and NO Domain — which host-only always satisfies here.
        String header = name + "=" + value + "; Path=/; Secure; SameSite=Lax";
        response.addHeader("Set-Cookie", header);
    }

    private void rejectForbidden(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();
        // A stable machine-readable signal so the client retries once after re-reading the re-set cookie.
        writer.print("{\"type\":\"CsrfError\",\"message\":\"CSRF validation failed\"}");
        writer.flush();
    }

    enum Provenance {
        PASS, UNAVAILABLE, FAIL
    }

    /**
     * Normalized origin authority (scheme + host + effective port), compared exactly.
     */
    static final class Authority {
        private final String scheme;
        private final String host;
        private final int port;

        Authority(String scheme, String host, int port) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
        }

        boolean matches(Authority other) {
            return other != null
                    && scheme.equals(other.scheme)
                    && host.equals(other.host)
                    && port == other.port;
        }
    }

    /**
     * A single CSRF evaluation record, exposed to tests via the seam below.
     */
    public static final class Evaluation {
        public final String path;
        public final String mechanism;
        public final String method;
        public final boolean stateChanging;
        public final boolean wouldBlock;
        public final String reason;

        Evaluation(String path, String mechanism, String method, boolean stateChanging, boolean wouldBlock, String reason) {
            this.path = path;
            this.mechanism = mechanism;
            this.method = method;
            this.stateChanging = stateChanging;
            this.wouldBlock = wouldBlock;
            this.reason = reason;
        }
    }

    /**
     * Test seam: the most recent state-changing evaluation. Never used in production.
     */
    public static Evaluation getLastEvaluationForTest() {
        return LAST_EVALUATION.get();
    }

    /**
     * Test seam: clears the recorded evaluation before a test drives a request.
     */
    public static void resetEvaluationForTest() {
        LAST_EVALUATION.set(null);
    }
}
