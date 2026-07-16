package com.sismics.docs.rest;

import com.google.common.reflect.ClassPath;
import com.sismics.util.csrf.CsrfFilter;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reflectively DISCOVERS every {@code @GET} JAX-RS route across the resource package and asserts the set
 * equals a known/expected set. Unlike a literal-vs-literal comparison, this actually scans the resources:
 * adding a NEW {@code @GET} method fails the build until a reviewer classifies it — either as a mutating
 * GET (added to {@link CsrfFilter#MUTATING_GET_PATHS}, so it is CSRF-evaluated) or as a known-safe GET
 * (added to {@link #KNOWN_SAFE_GET_ROUTES}). Path parameters are normalized to {@code {}} so the check is
 * stable across the parameter regexes.
 */
public class TestCsrfGetInventory {

    /**
     * The packages Jersey scans for resources (web.xml + BaseJerseyTest, kept in sync): the legacy
     * resource package plus the Phase G document-slice edge.
     */
    private static final String[] RESOURCE_PACKAGES = {
            "com.sismics.docs.rest.resource",
            "com.sismics.docs.rest.document"
    };

    /**
     * Every {@code @GET} route that is READ-ONLY (no server side effect) and therefore intentionally NOT
     * in the CSRF mutating-GET inventory. Normalized (path params -> {@code {}}).
     */
    private static final Set<String> KNOWN_SAFE_GET_ROUTES = Set.of(
            "/acl/target/search",
            "/apikey",
            "/app",
            "/app/batch/clean_storage/dry_run",
            "/app/config_inbox",
            "/app/config_ldap",
            "/app/config_oidc",
            "/app/config_smtp",
            "/app/log",
            "/app/stats",
            "/auditlog",
            "/comment/{}",
            "/document/{}",
            "/document/{}/pdf",
            "/document/list",
            "/document/trash",
            "/favorite",
            "/file/{}/data",
            "/file/{}/versions",
            "/file/list",
            "/file/zip",
            "/group",
            "/group/{}",
            "/metadata",
            "/route",
            "/routemodel",
            "/routemodel/{}",
            "/savedfilter",
            "/tag/{}",
            "/tag/co-occurrence",
            "/tag/facets",
            "/tag/list",
            "/tag/stats",
            "/tagmatchrule",
            "/theme",
            "/theme/stylesheet",
            "/theme/image/{}",
            "/user/{}",
            "/user/list",
            "/user/session",
            "/vocabulary",
            "/vocabulary/{}/usage",
            "/vocabulary/{}",
            "/webhook"
    );

    @Test
    public void everyGetRouteIsClassified() throws Exception {
        Set<String> discovered = discoverGetRoutes();

        // The expected set = the CSRF-evaluated mutating GETs + the known read-only GETs.
        Set<String> expected = new TreeSet<>(KNOWN_SAFE_GET_ROUTES);
        expected.addAll(CsrfFilter.MUTATING_GET_PATHS);

        Assertions.assertEquals(expected, discovered,
                "A @GET route is unclassified. Add each new @GET to CsrfFilter.MUTATING_GET_PATHS "
                        + "(if it has a server side effect) or to KNOWN_SAFE_GET_ROUTES (if read-only).");

        // Every mutating-GET inventory route must actually exist as a discovered @GET route.
        Assertions.assertTrue(discovered.containsAll(CsrfFilter.MUTATING_GET_PATHS),
                "MUTATING_GET_PATHS references a route that no longer exists as a @GET");
    }

    /**
     * Scans the resource package for {@code @GET}-annotated JAX-RS methods and returns the normalized
     * routes (class {@code @Path} + method {@code @Path}, path params collapsed to {@code {}}).
     */
    private static Set<String> discoverGetRoutes() throws Exception {
        Set<String> routes = new TreeSet<>();
        ClassPath classPath = ClassPath.from(Thread.currentThread().getContextClassLoader());
        for (String resourcePackage : RESOURCE_PACKAGES) {
            for (ClassPath.ClassInfo classInfo : classPath.getTopLevelClasses(resourcePackage)) {
                Class<?> clazz = classInfo.load();
                Path classPathAnn = clazz.getAnnotation(Path.class);
                String base = classPathAnn == null ? "" : classPathAnn.value();
                if (!base.isEmpty() && !base.startsWith("/")) {
                    base = "/" + base;
                }
                for (Method method : clazz.getDeclaredMethods()) {
                    if (!method.isAnnotationPresent(GET.class)) {
                        continue;
                    }
                    String route = base;
                    Path methodPathAnn = method.getAnnotation(Path.class);
                    if (methodPathAnn != null) {
                        String mv = methodPathAnn.value();
                        route = base + (mv.startsWith("/") ? mv : "/" + mv);
                    }
                    routes.add(normalize(route));
                }
            }
        }
        return routes;
    }

    /**
     * Normalizes a route: collapse every {@code {param[: regex]}} segment to {@code {}} and drop any
     * trailing slash (except root).
     */
    private static String normalize(String route) {
        String normalized = route.replaceAll("\\{[^}]*\\}", "{}");
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
