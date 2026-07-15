package com.sismics.util.csrf;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pins the mutating-GET inventory and the state-changing classification. A NEW mutating GET must be added
 * to {@link CsrfFilter#MUTATING_GET_PATHS} AND to the expected set here, so the build fails until both are
 * updated (change-detector).
 */
public class TestCsrfInventory {

    @Test
    public void mutatingGetInventoryIsExactlyTheKnownFour() {
        Set<String> expected = Set.of(
                "/oidc/login",
                "/oidc/callback",
                "/document/export",
                "/user");
        Assertions.assertEquals(expected, CsrfFilter.MUTATING_GET_PATHS,
                "the mutating-GET inventory must be exactly the known four routes");
    }

    @Test
    public void cleanStorageDryRunIsReadOnly() {
        // The dry-run batch endpoint is a read-only GET and must NOT be classified state-changing.
        Assertions.assertFalse(CsrfFilter.isStateChanging("GET", "/app/batch/clean_storage/dry_run"));
        Assertions.assertFalse(CsrfFilter.isStateChanging("HEAD", "/app/batch/clean_storage/dry_run"));
    }

    @Test
    public void stateChangingClassification() {
        // Unsafe methods are always state-changing regardless of path.
        Assertions.assertTrue(CsrfFilter.isStateChanging("POST", "/user"));
        Assertions.assertTrue(CsrfFilter.isStateChanging("PUT", "/document"));
        Assertions.assertTrue(CsrfFilter.isStateChanging("DELETE", "/tag/x"));
        // Safe methods are state-changing only on the mutating-GET inventory.
        Assertions.assertTrue(CsrfFilter.isStateChanging("GET", "/user"));
        Assertions.assertTrue(CsrfFilter.isStateChanging("HEAD", "/document/export"));
        Assertions.assertFalse(CsrfFilter.isStateChanging("GET", "/document/list"));
        Assertions.assertFalse(CsrfFilter.isStateChanging("OPTIONS", "/user"));
    }

    @Test
    public void parameterizedMutatingTemplateIsEvaluatedByTheFilterMatcher() {
        // The filter matches mutating-GET routes by TEMPLATE PATTERN (same representation the inventory
        // test discovers), so a PARAMETERIZED mutating GET added to the inventory is actually evaluated at
        // runtime — a concrete path with any single-segment id matches, across-boundary paths do not.
        List<Pattern> patterns = CsrfFilter.compileTemplates(Set.of("/document/{}/publish"));
        Assertions.assertTrue(CsrfFilter.matchesAny(patterns, "/document/abc123/publish"),
                "a concrete path must match its parameterized template");
        Assertions.assertTrue(CsrfFilter.matchesAny(patterns, "/document/UUID-x_y.9/publish"));
        Assertions.assertFalse(CsrfFilter.matchesAny(patterns, "/document/abc/def/publish"),
                "a single {} must match exactly one path segment, not span a boundary");
        Assertions.assertFalse(CsrfFilter.matchesAny(patterns, "/document/publish"));

        // A literal template must not be over-matched by a longer path.
        Pattern userPattern = CsrfFilter.templateToPattern("/user");
        Assertions.assertTrue(userPattern.matcher("/user").matches());
        Assertions.assertFalse(userPattern.matcher("/user/list").matches());
    }

    @Test
    public void parseAuthorityNormalizesAndRejects() {
        // Default port normalized and host lower-cased; https:443 == https (no explicit port).
        CsrfFilter.Authority a = CsrfFilter.parseAuthority("https://Teedy.Example.com");
        CsrfFilter.Authority b = CsrfFilter.parseAuthority("https://teedy.example.com:443");
        Assertions.assertNotNull(a);
        Assertions.assertNotNull(b);
        Assertions.assertTrue(a.matches(b), "default https port must equal explicit :443");

        // A different port does not match.
        CsrfFilter.Authority c = CsrfFilter.parseAuthority("https://teedy.example.com:8443");
        Assertions.assertFalse(a.matches(c));

        // Userinfo-bearing, scheme-relative, and malformed inputs are rejected.
        Assertions.assertNull(CsrfFilter.parseAuthority("https://user:pass@teedy.example.com"));
        Assertions.assertNull(CsrfFilter.parseAuthority("//teedy.example.com"));
        Assertions.assertNull(CsrfFilter.parseAuthority("not a uri"));
    }
}
