package com.sismics.util.filter;

import com.sismics.util.EnvironmentUtil;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * Filter used to handle CORS requests.
 *
 * <p>In development mode (only), a fixed loopback allowlist is honoured so the Vite dev server can call
 * the API cross-origin: the request {@code Origin} is echoed back ONLY when its host is a loopback
 * ({@code localhost} / {@code 127.0.0.1} / {@code [::1]}), never reflected verbatim for an arbitrary
 * origin. {@code Vary: Origin} is always emitted in dev so a per-origin response is not cached across
 * origins, and {@code X-Csrf-Token} is included in the allowed request headers. Production emits no CORS
 * headers at all.</p>
 *
 * @author bgamard
 */
public class CorsFilter implements Filter {
    /**
     * Loopback hosts allowed to call the API cross-origin in dev mode.
     */
    private static final Set<String> ALLOWED_DEV_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    @Override
    public void init(FilterConfig filterConfig) {
        // NOP
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        if (EnvironmentUtil.isDevMode()) {
            String origin = request.getHeader("origin");
            // A per-origin decision: mark the response as varying on Origin regardless of the outcome.
            response.addHeader("Vary", "Origin");
            if (origin != null && isAllowedDevOrigin(origin)) {
                response.addHeader("Access-Control-Allow-Origin", origin);
                response.addHeader("Access-Control-Allow-Credentials", "true");
                response.addHeader("Access-Control-Max-Age", "3600");
                response.addHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, Authorization, X-Csrf-Token, X-Csrf-Proxy");
                response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE");
            }
        }

        if ("OPTIONS".equals(request.getMethod())) {
            // Handle preflight request
            response.getWriter().print("{ \"status\": \"ok\" }");
        } else {
            filterChain.doFilter(req, res);
        }
    }

    /**
     * @return true if the origin is a loopback host allowed in dev mode.
     */
    private static boolean isAllowedDevOrigin(String origin) {
        try {
            URI uri = new URI(origin);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return false;
            }
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            return ALLOWED_DEV_HOSTS.contains(host.toLowerCase());
        } catch (URISyntaxException e) {
            return false;
        }
    }

    @Override
    public void destroy() {
        // NOP
    }
}
