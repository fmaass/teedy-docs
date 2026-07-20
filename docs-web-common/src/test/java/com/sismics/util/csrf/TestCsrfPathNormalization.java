package com.sismics.util.csrf;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

/**
 * Path-normalization tests for {@link CsrfFilter#normalizedPath(HttpServletRequest)}.
 *
 * <p>The filter classifies a request by comparing a normalized path against a literal inventory, while
 * JAX-RS selects the resource method from a path with matrix parameters removed (JSR-370 3.7.1). Any
 * disagreement between those two views is a bypass: the container dispatches the request to a handler
 * that the filter believes was never addressed. {@code getRequestURI()} retains matrix parameters, so
 * without stripping them {@code /api/user;x=1} reaches {@code UserResource.info()} — a mutating GET that
 * refreshes the session clock — while the filter classifies it non-state-changing and skips token and
 * Origin evaluation entirely, even under enforcement.
 *
 * <p>These tests fail against a {@code normalizedPath} that does not strip matrix parameters.
 */
public class TestCsrfPathNormalization {

    /**
     * Minimal {@link HttpServletRequest} exposing only the two accessors {@code normalizedPath} uses.
     * A dynamic proxy keeps this to the methods actually under test; anything else is a loud failure
     * rather than a silent default.
     */
    private static HttpServletRequest request(String requestUri, String contextPath) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                TestCsrfPathNormalization.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRequestURI" -> requestUri;
                    case "getContextPath" -> contextPath;
                    case "toString" -> "StubRequest[" + requestUri + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    default -> throw new UnsupportedOperationException(
                            "normalizedPath must not depend on " + method.getName());
                });
    }

    @Test
    public void plainPathsNormalizeAsBefore() {
        Assertions.assertEquals("/user", CsrfFilter.normalizedPath(request("/api/user", "")));
        Assertions.assertEquals("/document/list", CsrfFilter.normalizedPath(request("/api/document/list", "")));
        Assertions.assertEquals("/user", CsrfFilter.normalizedPath(request("/api/user/", "")),
                "a trailing slash is still dropped");
        Assertions.assertEquals("/user", CsrfFilter.normalizedPath(request("/ctx/api/user", "/ctx")),
                "the servlet context path is still stripped");
    }

    @Test
    public void matrixParameterOnTheFinalSegmentIsStripped() {
        Assertions.assertEquals("/user", CsrfFilter.normalizedPath(request("/api/user;x=1", "")));
        Assertions.assertEquals("/user", CsrfFilter.normalizedPath(request("/api/user;a=1;b=2", "")),
                "multiple matrix parameters on one segment");
        Assertions.assertEquals("/user", CsrfFilter.normalizedPath(request("/api/user;x=1/", "")),
                "matrix parameter combined with a trailing slash");
    }

    @Test
    public void matrixParameterOnTheApiPrefixOrAMiddleSegmentIsStripped() {
        Assertions.assertEquals("/user", CsrfFilter.normalizedPath(request("/api;x=1/user", "")),
                "a matrix segment on the /api prefix must not defeat the prefix strip");
        Assertions.assertEquals("/document/export",
                CsrfFilter.normalizedPath(request("/api/document;a=b/export", "")));
        Assertions.assertEquals("/document/export",
                CsrfFilter.normalizedPath(request("/api/document/export;a=b", "")));
    }

    /**
     * The end-to-end consequence: a mutating GET carrying a matrix parameter must still be classified
     * state-changing, so it is CSRF-evaluated rather than waved through.
     */
    @Test
    public void mutatingGetWithAMatrixParameterStaysStateChanging() {
        Assertions.assertTrue(
                CsrfFilter.isStateChanging("GET", CsrfFilter.normalizedPath(request("/api/user;x=1", ""))),
                "GET /user;x=1 is dispatched to UserResource.info() and must remain CSRF-evaluated");
        Assertions.assertTrue(
                CsrfFilter.isStateChanging("HEAD", CsrfFilter.normalizedPath(request("/api/document/export;x=1", ""))),
                "HEAD /document/export;x=1 is dispatched to the export route and must remain CSRF-evaluated");
        Assertions.assertTrue(
                CsrfFilter.isStateChanging("GET", CsrfFilter.normalizedPath(request("/api;x=1/user", ""))),
                "the prefix-matrix variant must not bypass classification either");
    }

    /**
     * Stripping must not accidentally promote a genuinely safe GET into the mutating inventory.
     */
    @Test
    public void safeGetsAreStillSafeAfterStripping() {
        Assertions.assertFalse(
                CsrfFilter.isStateChanging("GET", CsrfFilter.normalizedPath(request("/api/document/list;x=1", ""))));
        Assertions.assertFalse(
                CsrfFilter.isStateChanging("GET", CsrfFilter.normalizedPath(request("/api/app;x=1", ""))));
    }
}
