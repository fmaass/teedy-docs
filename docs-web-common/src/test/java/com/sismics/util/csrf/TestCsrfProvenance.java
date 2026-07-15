package com.sismics.util.csrf;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Unit tests for the parsed-URI Origin/Referer provenance classification. Driven directly (the JDK HTTP
 * client used in the integration suite silently strips the restricted {@code Origin}/{@code Referer}
 * request headers, so this rule cannot be exercised end-to-end there).
 */
public class TestCsrfProvenance {

    private static final CsrfFilter.Authority BASE = CsrfFilter.parseAuthority("https://teedy.example.com");

    @Test
    public void neitherHeaderIsUnavailable() {
        Assertions.assertEquals(CsrfFilter.Provenance.UNAVAILABLE,
                CsrfFilter.classifyProvenance(List.of(), List.of(), BASE));
    }

    @Test
    public void matchingOriginPasses() {
        Assertions.assertEquals(CsrfFilter.Provenance.PASS,
                CsrfFilter.classifyProvenance(List.of("https://teedy.example.com"), List.of(), BASE));
        // Default-port equivalence.
        Assertions.assertEquals(CsrfFilter.Provenance.PASS,
                CsrfFilter.classifyProvenance(List.of("https://teedy.example.com:443"), List.of(), BASE));
    }

    @Test
    public void mismatchedOriginFails() {
        Assertions.assertEquals(CsrfFilter.Provenance.FAIL,
                CsrfFilter.classifyProvenance(List.of("https://evil.example.com"), List.of(), BASE));
        Assertions.assertEquals(CsrfFilter.Provenance.FAIL,
                CsrfFilter.classifyProvenance(List.of("https://teedy.example.com:8443"), List.of(), BASE));
        // Scheme mismatch.
        Assertions.assertEquals(CsrfFilter.Provenance.FAIL,
                CsrfFilter.classifyProvenance(List.of("http://teedy.example.com"), List.of(), BASE));
    }

    @Test
    public void originNullMalformedMultiUserinfoAllFail() {
        Assertions.assertEquals(CsrfFilter.Provenance.FAIL,
                CsrfFilter.classifyProvenance(List.of("null"), List.of(), BASE));
        Assertions.assertEquals(CsrfFilter.Provenance.FAIL,
                CsrfFilter.classifyProvenance(List.of("not a uri"), List.of(), BASE));
        Assertions.assertEquals(CsrfFilter.Provenance.FAIL,
                CsrfFilter.classifyProvenance(List.of("https://a.example.com", "https://b.example.com"), List.of(), BASE));
        Assertions.assertEquals(CsrfFilter.Provenance.FAIL,
                CsrfFilter.classifyProvenance(List.of("https://user:pass@teedy.example.com"), List.of(), BASE));
    }

    @Test
    public void refererFallbackWhenNoOrigin() {
        Assertions.assertEquals(CsrfFilter.Provenance.PASS,
                CsrfFilter.classifyProvenance(List.of(), List.of("https://teedy.example.com/#/document"), BASE));
        Assertions.assertEquals(CsrfFilter.Provenance.FAIL,
                CsrfFilter.classifyProvenance(List.of(), List.of("https://evil.example.com/x"), BASE));
    }

    @Test
    public void wellFormedButNoConfiguredBaseIsUnavailable() {
        Assertions.assertEquals(CsrfFilter.Provenance.UNAVAILABLE,
                CsrfFilter.classifyProvenance(List.of("https://teedy.example.com"), List.of(), null));
    }

    @Test
    public void malformedOriginFailsEvenWithoutBase() {
        // Well-formedness rejects are independent of the base URL being configured.
        Assertions.assertEquals(CsrfFilter.Provenance.FAIL,
                CsrfFilter.classifyProvenance(List.of("null"), List.of(), null));
    }
}
