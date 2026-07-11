package com.sismics.docs.rest.resource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Accessor-completeness guard (#44, HIGH-effort auth-surface blocker): the twelve
 * {@code docs.oidc_*} configuration values must be read ONLY through the single accessor chokepoint
 * in {@link OidcResource} — {@code resolveEffective} (the DB → property → default resolver) and
 * {@code oidcConfigSource} (the UI source hint). A read elsewhere silently bypasses a DB override
 * (Saturn regression risk), so the build fails on ANY such read outside that chokepoint.
 *
 * <p>Two complementary scans, both comment-and-string-aware (the lexer of
 * {@link TestOidcSubjectLogGuard}):
 * <ol>
 *   <li><b>Auth-class strict scan.</b> Within the classes that legitimately deal with these keys —
 *       {@code OidcResource}, {@code AppResource}, {@code UserResource} — ANY {@code
 *       System.getProperty(} call outside the whitelisted accessor location is a violation
 *       REGARDLESS of its argument, EXCEPT an explicit allowlist of known non-OIDC keys read there
 *       ({@code docs.logout_url}, {@code docs.header_authentication}). This catches a read laundered
 *       through a neutrally-named local ({@code String k = "docs.oidc_issuer"; System.getProperty(k)})
 *       without any data-flow analysis.</li>
 *   <li><b>Cross-root literal/constant scan.</b> Across ALL main-source roots (docs-core, docs-web,
 *       docs-web-common), any {@code System.getProperty(...)} whose argument literally references an
 *       OIDC key (a {@code "docs.oidc..."} literal, {@code OidcKey}, {@code .propertyName()}, a
 *       {@code PROP_*}/{@code *OIDC*} constant) outside the accessor is a violation.</li>
 * </ol>
 *
 * <p>The accessor whitelist is scoped to the EXACT location (file {@code OidcResource.java} AND one
 * of the accessor method names), so a same-named method in another file/class cannot suppress an
 * offender. A dedicated mutation-check ({@link #guardIsNotInert_removingTheWhitelistFindsTheAccessorRead})
 * removes the whitelist and asserts the accessor's own read is then reported — proving the guard
 * exercises its own logic rather than passing vacuously.
 */
public class TestOidcAccessorGuard {

    /** Start of a property read: System.getProperty( — the '(' anchors the argument scan. */
    private static final Pattern GET_PROPERTY = Pattern.compile("System\\s*\\.\\s*getProperty\\s*\\(");

    /** The file that hosts the accessor chokepoint. The whitelist is scoped to THIS file. */
    private static final String ACCESSOR_FILE = "OidcResource.java";
    /** The accessor methods in {@link OidcResource} allowed to read a docs.oidc_* property. */
    private static final List<String> ACCESSOR_METHODS = List.of("resolveEffective", "oidcConfigSource");

    /** The auth classes that legitimately deal with the OIDC keys (strict-scan scope). */
    private static final List<String> AUTH_CLASS_FILES =
            List.of("OidcResource.java", "AppResource.java", "UserResource.java");

    /**
     * Known NON-OIDC property keys read directly in the auth classes. A getProperty of one of these
     * (outside the accessor) is allowed; anything else in an auth class is a violation. Enumerated
     * from the current tree: {@code docs.logout_url} (UserResource logout), {@code
     * docs.header_authentication} (AppResource app info).
     */
    private static final List<String> AUTH_CLASS_ALLOWLISTED_KEYS =
            List.of("docs.logout_url", "docs.header_authentication");

    /**
     * The argument literally references an OIDC key. Broad on the OIDC side (constant/variable names
     * caught), while a plain non-OIDC key literal is NOT matched.
     */
    private static final Pattern OIDC_ARGUMENT = Pattern.compile(
            "docs\\.oidc"                       // "docs.oidc_*" string literal
            + "|OidcKey"                        // OidcKey enum reference
            + "|propertyName\\s*\\(\\s*\\)"     // key.propertyName()
            + "|\\bPROP_[A-Z_]*"                // legacy PROP_* constant name
            + "|OIDC[A-Z_]*");                  // any *OIDC*-named constant

    // Lexical states, one per source character.
    private static final byte CODE = 0;
    private static final byte STRING = 1;
    private static final byte CHAR = 2;
    private static final byte LINE_COMMENT = 3;
    private static final byte BLOCK_COMMENT = 4;

    @Test
    public void noDirectOidcPropertyReadOutsideTheAccessor() throws Exception {
        List<String> violations = scanAllRoots(true);
        Assertions.assertTrue(violations.isEmpty(),
                "Every docs.oidc_* property must be read through the OidcResource accessor chokepoint "
                        + "(DB-first precedence). In an auth class ANY non-allowlisted System.getProperty "
                        + "outside the accessor is a violation (catches a laundered variable read). "
                        + "Offending read(s): " + violations);
    }

    /**
     * Mutation-check that the guard exercises its whitelist: with the accessor whitelist DISABLED,
     * the same scan MUST report the accessor's own read. An empty result would mean the guard never
     * actually inspects the accessor's property read and is inert.
     */
    @Test
    public void guardIsNotInert_removingTheWhitelistFindsTheAccessorRead() throws Exception {
        List<String> withoutWhitelist = scanAllRoots(false);
        Assertions.assertFalse(withoutWhitelist.isEmpty(),
                "removing the accessor whitelist must surface the accessor's own docs.oidc_* read; "
                        + "an empty result means the guard never inspects property reads");
        Assertions.assertTrue(withoutWhitelist.stream().anyMatch(v -> v.contains(ACCESSOR_FILE)),
                "the un-whitelisted scan must flag the read inside OidcResource's accessor, got: "
                        + withoutWhitelist);
    }

    private static List<String> scanAllRoots(boolean applyWhitelist) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : mainJavaRoots()) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    scanFile(file, applyWhitelist, violations);
                }
            }
        }
        return violations;
    }

    private static void scanFile(Path file, boolean applyWhitelist, List<String> violations)
            throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        if (!source.contains("System.getProperty") && !source.contains("System . getProperty")) {
            return;
        }
        String fileName = file.getFileName().toString();
        boolean authClass = AUTH_CLASS_FILES.contains(fileName);
        byte[] states = lexStates(source);
        Matcher m = GET_PROPERTY.matcher(source);
        while (m.find()) {
            if (states[m.start()] != CODE) {
                continue; // a getProperty inside a comment/string is not a real read
            }
            int openParen = source.indexOf('(', m.start());
            int closeParen = matchingParen(source, states, openParen);
            int argEnd = closeParen < 0 ? source.length() : closeParen;
            String argument = source.substring(openParen + 1, argEnd);

            // Whitelist: the EXACT accessor location — the accessor file AND an accessor method.
            if (applyWhitelist && fileName.equals(ACCESSOR_FILE)
                    && ACCESSOR_METHODS.contains(enclosingMethod(source, states, m.start()))) {
                continue;
            }

            if (authClass) {
                // Strict: any read here that is not an allowlisted non-OIDC key is a violation,
                // regardless of argument form (a laundered variable has no literal to match).
                if (isAllowlistedNonOidc(argument)) {
                    continue;
                }
                violations.add(fileName + ": System.getProperty(" + collapse(argument) + ")");
            } else {
                // Other classes: only a literal/constant OIDC-key reference is a violation.
                if (OIDC_ARGUMENT.matcher(argument).find()) {
                    violations.add(fileName + ": System.getProperty(" + collapse(argument) + ")");
                }
            }
        }
    }

    /** True when the argument is a string literal of a known non-OIDC allowlisted key. */
    private static boolean isAllowlistedNonOidc(String argument) {
        String trimmed = argument.trim();
        for (String key : AUTH_CLASS_ALLOWLISTED_KEYS) {
            if (trimmed.equals("\"" + key + "\"")) {
                return true;
            }
        }
        return false;
    }

    /** Index of the ')' matching the '(' at {@code open}, honoring literals/comments. -1 if none. */
    private static int matchingParen(String source, byte[] states, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            if (states[i] != CODE) {
                continue;
            }
            char c = source.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Best-effort name of the method enclosing {@code index}: the nearest preceding
     * {@code <name>(...) {} whose brace-span contains {@code index}. A null result is treated as
     * "outside the accessor" (a violation candidate).
     */
    private static String enclosingMethod(String source, byte[] states, int index) {
        Pattern method = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\([^;{}]*\\)\\s*\\{");
        Matcher m = method.matcher(source);
        String best = null;
        while (m.find()) {
            if (m.start() >= index) {
                break;
            }
            if (states[m.start()] != CODE) {
                continue;
            }
            int bodyStart = source.indexOf('{', m.start());
            if (bodyStart < 0) {
                continue;
            }
            int bodyEnd = matchingBrace(source, states, bodyStart);
            if (bodyStart < index && (bodyEnd < 0 || index < bodyEnd)) {
                best = m.group(1);
            }
        }
        return best;
    }

    private static int matchingBrace(String source, byte[] states, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            if (states[i] != CODE) {
                continue;
            }
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** The three module main-source roots, resolved whether surefire runs from the module or repo root. */
    private static List<Path> mainJavaRoots() {
        List<Path> roots = new ArrayList<>();
        for (String module : new String[]{"docs-core", "docs-web", "docs-web-common"}) {
            Path fromRepoRoot = Paths.get(module, "src", "main", "java");
            Path fromModule = Paths.get("..", module, "src", "main", "java");
            if (Files.exists(fromRepoRoot)) {
                roots.add(fromRepoRoot);
            } else if (Files.exists(fromModule)) {
                roots.add(fromModule);
            }
        }
        Assertions.assertFalse(roots.isEmpty(),
                "could not locate any main-source root (docs-core/docs-web/docs-web-common)");
        return roots;
    }

    /** The shared lexer from TestOidcSubjectLogGuard: classifies each char as code/literal/comment. */
    private static byte[] lexStates(String source) {
        byte[] states = new byte[source.length()];
        byte state = CODE;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            switch (state) {
                case CODE:
                    if (c == '/' && next == '/') {
                        state = LINE_COMMENT;
                        states[i] = LINE_COMMENT;
                    } else if (c == '/' && next == '*') {
                        state = BLOCK_COMMENT;
                        states[i] = BLOCK_COMMENT;
                    } else if (c == '"') {
                        state = STRING;
                        states[i] = STRING;
                    } else if (c == '\'') {
                        state = CHAR;
                        states[i] = CHAR;
                    } else {
                        states[i] = CODE;
                    }
                    break;
                case STRING:
                case CHAR:
                    states[i] = state;
                    if (c == '\\' && i + 1 < source.length()) {
                        states[i + 1] = state;
                        i++;
                    } else if ((state == STRING && c == '"') || (state == CHAR && c == '\'')) {
                        state = CODE;
                    }
                    break;
                case LINE_COMMENT:
                    states[i] = LINE_COMMENT;
                    if (c == '\n') {
                        state = CODE;
                    }
                    break;
                case BLOCK_COMMENT:
                    states[i] = BLOCK_COMMENT;
                    if (c == '*' && next == '/') {
                        states[i + 1] = BLOCK_COMMENT;
                        i++;
                        state = CODE;
                    }
                    break;
                default:
                    throw new IllegalStateException("unreachable lexer state " + state);
            }
        }
        return states;
    }

    private static String collapse(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
