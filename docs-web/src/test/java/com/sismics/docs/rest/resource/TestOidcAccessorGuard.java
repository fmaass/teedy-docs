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
 * Accessor-completeness guard (#44, HIGH-effort auth-surface blocker): the 13
 * {@code docs.oidc_*} configuration values must be read ONLY through the single accessor chokepoint
 * in {@link OidcResource} — {@code resolveEffective} (the DB → property → env → default resolver)
 * and {@code oidcConfigSource} (the UI source hint). A read elsewhere silently bypasses a DB
 * override (a production regression risk), so the build fails on ANY such read outside that chokepoint.
 *
 * <p>Both process-global tiers are guarded SYMMETRICALLY: a {@code docs.oidc_*} JVM property read
 * ({@code System.getProperty}) and a {@code DOCS_OIDC_*} environment read ({@code System.getenv})
 * are the same bypass, so the environment tier introduced alongside the property tier is fenced by
 * the same rules rather than being an unguarded second door.
 *
 * <p>Two complementary scans per tier, both comment-and-string-aware (the lexer of
 * {@link TestOidcSubjectLogGuard}):
 * <ol>
 *   <li><b>Auth-class strict scan.</b> Within the classes that legitimately deal with these keys —
 *       {@code OidcResource}, {@code AppResource}, {@code UserResource} — ANY {@code
 *       System.getProperty(} / {@code System.getenv(} call outside the whitelisted accessor location
 *       is a violation REGARDLESS of its argument, EXCEPT an explicit allowlist of known non-OIDC
 *       keys read there ({@code docs.logout_url}, {@code docs.header_authentication};
 *       {@code DOCS_MAX_UPLOAD_SIZE}, {@code Constants.GLOBAL_QUOTA_ENV}, the SMTP env constants).
 *       This catches a read laundered through a neutrally-named local
 *       ({@code String k = "docs.oidc_issuer"; System.getProperty(k)}, or the {@code DOCS_OIDC_*}
 *       equivalent) without any data-flow analysis.</li>
 *   <li><b>Cross-root literal/constant scan.</b> Across ALL main-source roots (docs-core, docs-web,
 *       docs-web-common), any {@code System.getProperty(...)} whose argument literally references an
 *       OIDC key (a {@code "docs.oidc..."} literal, {@code OidcKey}, {@code .propertyName()}, a
 *       {@code PROP_*}/{@code *OIDC*} constant) outside the accessor is a violation — and likewise
 *       any {@code System.getenv(...)} whose argument references one (a {@code "DOCS_OIDC..."}
 *       literal, {@code OidcKey}, {@code .envName()}, an {@code *OIDC*} constant).</li>
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

    /** Start of an environment read: System.getenv( — the mirror of {@link #GET_PROPERTY}. */
    private static final Pattern GET_ENV = Pattern.compile("System\\s*\\.\\s*getenv\\s*\\(");

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
     * docs.header_authentication} (AppResource app info), and the JVM/OS identity properties the
     * AppResource {@code /app/diagnostics} endpoint reports ({@code java.version}, {@code
     * java.vendor}, {@code os.name}, {@code os.version}, {@code os.arch}).
     */
    private static final List<String> AUTH_CLASS_ALLOWLISTED_KEYS =
            List.of("docs.logout_url", "docs.header_authentication",
                    "java.version", "java.vendor", "os.name", "os.version", "os.arch");

    /**
     * Known NON-OIDC environment variables read directly in the auth classes — the mirror of
     * {@link #AUTH_CLASS_ALLOWLISTED_KEYS} for {@code System.getenv}. Enumerated from the current
     * tree: the global-quota variable ({@code AppResource} app info, {@code OidcResource}
     * provisioning), the upload cap, and the SMTP settings the {@code /app} endpoint reports as
     * configured. Each entry is accepted either as a bare constant reference (the form the tree
     * uses, e.g. {@code Constants.GLOBAL_QUOTA_ENV}) or as a quoted string literal of the same
     * text — anything else read in an auth class is a violation.
     */
    private static final List<String> AUTH_CLASS_ALLOWLISTED_ENV_KEYS =
            List.of("Constants.GLOBAL_QUOTA_ENV", "DOCS_MAX_UPLOAD_SIZE",
                    "Constants.SMTP_HOSTNAME_ENV", "Constants.SMTP_PORT_ENV",
                    "Constants.SMTP_USERNAME_ENV");

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

    /** The {@link #OIDC_ARGUMENT} mirror for an environment read: the argument names an OIDC key. */
    private static final Pattern OIDC_ENV_ARGUMENT = Pattern.compile(
            "DOCS_OIDC"                         // "DOCS_OIDC_*" string literal
            + "|OidcKey"                        // OidcKey enum reference
            + "|envName\\s*\\(\\s*\\)"          // key.envName()
            + "|OIDC[A-Z_]*");                  // any *OIDC*-named constant

    // Lexical states, one per source character.
    private static final byte CODE = 0;
    private static final byte STRING = 1;
    private static final byte CHAR = 2;
    private static final byte LINE_COMMENT = 3;
    private static final byte BLOCK_COMMENT = 4;

    /**
     * The gate itself, over BOTH process-global tiers: no {@code docs.oidc_*} property read and no
     * {@code DOCS_OIDC_*} environment read may live outside the accessor chokepoint.
     */
    @Test
    public void noDirectOidcPropertyReadOutsideTheAccessor() throws Exception {
        List<String> violations = scanAllRoots(true);
        Assertions.assertTrue(violations.isEmpty(),
                "Every docs.oidc_* property AND DOCS_OIDC_* environment variable must be read through "
                        + "the OidcResource accessor chokepoint (DB-first precedence). In an auth class "
                        + "ANY non-allowlisted System.getProperty/System.getenv outside the accessor is "
                        + "a violation (catches a laundered variable read). "
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

    /**
     * The {@link #guardIsNotInert_removingTheWhitelistFindsTheAccessorRead} mirror for the
     * environment tier: with the accessor whitelist DISABLED, the scan MUST report the accessor's
     * own {@code System.getenv} read. Without this, an env scan that never actually inspects the
     * accessor (a typo in the pattern, a scan never invoked) would pass vacuously and the whole
     * environment tier would be unguarded.
     */
    @Test
    public void guardIsNotInert_removingTheWhitelistFindsTheAccessorEnvRead() throws Exception {
        List<String> withoutWhitelist = scanAllRoots(false);
        Assertions.assertTrue(
                withoutWhitelist.stream()
                        .anyMatch(v -> v.contains(ACCESSOR_FILE) && v.contains("System.getenv(")),
                "the un-whitelisted scan must flag the DOCS_OIDC_* getenv read inside OidcResource's "
                        + "accessor; an absent one means the environment tier is unguarded, got: "
                        + withoutWhitelist);
    }

    /**
     * A {@code DOCS_OIDC_*} environment read outside the accessor bypasses the DB and property
     * tiers exactly as a bare property read does, so the cross-root scan must flag it.
     */
    @Test
    public void guardFlagsAnOidcEnvReadOutsideTheAccessor() {
        String source = ""
                + "package com.sismics.docs.core.util;\n"
                + "class SomeHelper {\n"
                + "    static String issuer() {\n"
                + "        return System.getenv(\"DOCS_OIDC_ISSUER\");\n"
                + "    }\n"
                + "}\n";
        List<String> violations = new ArrayList<>();
        scanSource("SomeHelper.java", source, true, violations);
        Assertions.assertFalse(violations.isEmpty(),
                "a DOCS_OIDC_* getenv outside the accessor must be flagged");
        Assertions.assertTrue(violations.stream().anyMatch(v -> v.contains("DOCS_OIDC_ISSUER")),
                "the flagged violation must name the offending read; got: " + violations);
    }

    /**
     * Negative control: the environment scan must not turn every {@code System.getenv} in the tree
     * into a violation. A non-OIDC variable read outside the auth classes ({@code DATABASE_PASSWORD}
     * in {@code EMF}) is legitimate and must stay silent — otherwise the guard is unusable and gets
     * disabled.
     */
    @Test
    public void guardIgnoresANonOidcEnvRead() {
        String source = ""
                + "package com.sismics.util.jpa;\n"
                + "class EMF {\n"
                + "    static String password() {\n"
                + "        return System.getenv(\"DATABASE_PASSWORD\");\n"
                + "    }\n"
                + "}\n";
        List<String> violations = new ArrayList<>();
        scanSource("EMF.java", source, true, violations);
        Assertions.assertTrue(violations.isEmpty(),
                "a non-OIDC getenv outside the auth classes must not be flagged; got: " + violations);
    }

    /**
     * The strict auth-class rule applies to the environment tier too: a read laundered through a
     * neutrally-named local has no OIDC literal in its argument, so only the strict rule catches it.
     * The allowlisted non-OIDC reads in the same file must stay silent.
     */
    @Test
    public void guardFlagsALaunderedEnvReadInAnAuthClass() {
        String source = ""
                + "package com.sismics.docs.rest.resource;\n"
                + "class AppResource {\n"
                + "    static String quota() {\n"
                + "        return System.getenv(Constants.GLOBAL_QUOTA_ENV);\n"
                + "    }\n"
                + "    static String sneaky() {\n"
                + "        String k = \"DOCS_OIDC_CLIENT_SECRET\";\n"
                + "        return System.getenv(k);\n"
                + "    }\n"
                + "}\n";
        List<String> violations = new ArrayList<>();
        scanSource("AppResource.java", source, true, violations);
        Assertions.assertEquals(1, violations.size(),
                "exactly the laundered read must be flagged (the allowlisted quota read must not); "
                        + "got: " + violations);
        Assertions.assertTrue(violations.get(0).contains("System.getenv(k)"),
                "the flagged violation must be the laundered read; got: " + violations);
    }

    /**
     * Regression for issue #161: a whitelisted accessor whose javadoc ends with a {@code word(...)}
     * construct — and no {@code ;}/{@code &#123;}/{@code &#125;} between that prose and the method
     * body — must still resolve to its enclosing accessor method, so the legitimate read is exempt
     * and produces NO violation. Before the fix the enclosing-method regex bridged from the javadoc
     * {@code word(...)} across the comment boundary into the real signature, resolved null, and the
     * subsequent {@code List.of(...).contains(null)} threw NPE (build crash). The trigger javadoc is
     * exactly the innocuous shape that forced a real-world javadoc rewording workaround.
     */
    @Test
    public void parserResolvesWhitelistedReadDespiteJavadocParenConstruct() {
        String source = ""
                + "package com.sismics.docs.rest.resource;\n"
                + "class OidcResource {\n"
                + "    /**\n"
                + "     * Resolves a property-only override, mirroring resolveEffective(key) semantics\n"
                + "     */\n"
                + "    private static String resolveEffective(String propertyName) {\n"
                + "        return System.getProperty(propertyName);\n"
                + "    }\n"
                + "}\n";
        List<String> violations = new ArrayList<>();
        scanSource("OidcResource.java", source, true, violations);
        Assertions.assertTrue(violations.isEmpty(),
                "a whitelisted accessor read must remain exempt even when the preceding javadoc "
                        + "contains a word(...) construct; got: " + violations);
    }

    /**
     * Negative control for issue #161: the comment-masking fix must NOT weaken the guard. A bare
     * {@code docs.oidc_*} read in a NON-accessor method of the accessor file (its javadoc also
     * carries a {@code word(...)} construct) must still be flagged — the enclosing method resolves,
     * is not in the whitelist, and the read is reported.
     */
    @Test
    public void guardStillFlagsBareReadOutsideAWhitelistedMethod() {
        String source = ""
                + "package com.sismics.docs.rest.resource;\n"
                + "class OidcResource {\n"
                + "    /**\n"
                + "     * Some helper, see relatedThing(arg) for the wider contract\n"
                + "     */\n"
                + "    private static String notAnAccessor() {\n"
                + "        return System.getProperty(\"docs.oidc_issuer\");\n"
                + "    }\n"
                + "}\n";
        List<String> violations = new ArrayList<>();
        scanSource("OidcResource.java", source, true, violations);
        Assertions.assertFalse(violations.isEmpty(),
                "a docs.oidc_* read outside any whitelisted accessor method must still be flagged");
        Assertions.assertTrue(violations.stream().anyMatch(v -> v.contains("docs.oidc_issuer")),
                "the flagged violation must name the offending read; got: " + violations);
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
        scanSource(file.getFileName().toString(), source, applyWhitelist, violations);
    }

    /**
     * Scans a single source unit (the file's name and full text). Extracted from {@link #scanFile}
     * so the parser — in particular the enclosing-method resolution the accessor whitelist depends
     * on — is exercisable against synthetic source in a unit test, without materializing a file.
     */
    static void scanSource(String fileName, String source, boolean applyWhitelist,
            List<String> violations) {
        boolean hasPropertyRead =
                source.contains("System.getProperty") || source.contains("System . getProperty");
        boolean hasEnvRead = source.contains("System.getenv") || source.contains("System . getenv");
        if (!hasPropertyRead && !hasEnvRead) {
            return;
        }
        byte[] states = lexStates(source);
        if (hasPropertyRead) {
            scanReads(fileName, source, states, applyWhitelist, violations,
                    GET_PROPERTY, "System.getProperty", OIDC_ARGUMENT, AUTH_CLASS_ALLOWLISTED_KEYS);
        }
        if (hasEnvRead) {
            scanReads(fileName, source, states, applyWhitelist, violations,
                    GET_ENV, "System.getenv", OIDC_ENV_ARGUMENT, AUTH_CLASS_ALLOWLISTED_ENV_KEYS);
        }
    }

    /**
     * One tier's scan: every {@code readPattern} match in the source, judged by the accessor
     * whitelist, then by the strict auth-class rule or the cross-root literal rule. Parameterized
     * over the tier so the {@code System.getProperty} and {@code System.getenv} scans are literally
     * the same logic — an environment read of an OIDC key cannot be fenced more loosely than a
     * property read of one.
     */
    private static void scanReads(String fileName, String source, byte[] states,
            boolean applyWhitelist, List<String> violations, Pattern readPattern, String readLabel,
            Pattern oidcArgument, List<String> allowlist) {
        boolean authClass = AUTH_CLASS_FILES.contains(fileName);
        Matcher m = readPattern.matcher(source);
        while (m.find()) {
            if (states[m.start()] != CODE) {
                continue; // a read inside a comment/string is not a real read
            }
            int openParen = source.indexOf('(', m.start());
            int closeParen = matchingParen(source, states, openParen);
            int argEnd = closeParen < 0 ? source.length() : closeParen;
            String argument = source.substring(openParen + 1, argEnd);

            // Whitelist: the EXACT accessor location — the accessor file AND an accessor method.
            // An unresolvable enclosing method inside the accessor file is a clean test failure
            // below (never an NPE), because the guard then cannot decide whether the read is exempt.
            if (applyWhitelist && fileName.equals(ACCESSOR_FILE)) {
                String enclosing = enclosingMethod(source, states, m.start());
                Assertions.assertNotNull(enclosing,
                        "the accessor guard could not resolve the method enclosing a " + readLabel
                                + " read in " + ACCESSOR_FILE + " at line " + lineOf(source, m.start())
                                + " [" + snippet(source, m.start()) + "], so it cannot tell whether the "
                                + "read is inside a whitelisted accessor; treat as a guard failure");
                if (ACCESSOR_METHODS.contains(enclosing)) {
                    continue;
                }
            }

            if (authClass) {
                // Strict: any read here that is not an allowlisted non-OIDC key is a violation,
                // regardless of argument form (a laundered variable has no literal to match).
                if (isAllowlistedNonOidc(argument, allowlist)) {
                    continue;
                }
                violations.add(fileName + ": " + readLabel + "(" + collapse(argument) + ")");
            } else {
                // Other classes: only a literal/constant OIDC-key reference is a violation.
                if (oidcArgument.matcher(argument).find()) {
                    violations.add(fileName + ": " + readLabel + "(" + collapse(argument) + ")");
                }
            }
        }
    }

    /**
     * True when the argument is a known non-OIDC allowlisted key, either as a string literal
     * ({@code "docs.logout_url"}, {@code "DOCS_MAX_UPLOAD_SIZE"}) or — only for an entry shaped
     * like a qualified constant reference — as the bare reference the tree actually writes
     * ({@code Constants.GLOBAL_QUOTA_ENV}). Restricting the bare form to that shape keeps a
     * neutrally-named LOCAL from ever matching an allowlist entry and laundering a read.
     */
    private static boolean isAllowlistedNonOidc(String argument, List<String> allowlist) {
        String trimmed = argument.trim();
        for (String key : allowlist) {
            if (trimmed.equals("\"" + key + "\"")) {
                return true;
            }
            if (isQualifiedConstantReference(key) && trimmed.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** True for a {@code Owner.CONSTANT_NAME} reference — the only non-literal allowlist form. */
    private static boolean isQualifiedConstantReference(String key) {
        int dot = key.lastIndexOf('.');
        return dot > 0 && key.substring(dot + 1).matches("[A-Z][A-Z0-9_]*");
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
     *
     * <p>The scan runs over a position-preserving COPY of the source in which every comment/string
     * character is blanked ({@link #maskNonCode}), so javadoc/block-comment/line-comment prose can
     * never be parsed as a method signature. Without that, the {@code [^;{}]*} parameter span could
     * bridge from a {@code word(...)} construct in a javadoc across the {@code *\/} boundary into the
     * real method signature that follows, swallowing that declaration and yielding a spurious null
     * for a read that is genuinely inside a whitelisted method (issue #161).
     */
    private static String enclosingMethod(String source, byte[] states, int index) {
        String scannable = maskNonCode(source, states);
        Pattern method = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\([^;{}]*\\)\\s*\\{");
        Matcher m = method.matcher(scannable);
        String best = null;
        while (m.find()) {
            if (m.start() >= index) {
                break;
            }
            if (states[m.start()] != CODE) {
                continue;
            }
            int bodyStart = scannable.indexOf('{', m.start());
            if (bodyStart < 0) {
                continue;
            }
            int bodyEnd = matchingBrace(scannable, states, bodyStart);
            if (bodyStart < index && (bodyEnd < 0 || index < bodyEnd)) {
                best = m.group(1);
            }
        }
        return best;
    }

    /**
     * A copy of {@code source} with every non-code character blanked to a space (newlines kept, so
     * indices AND line structure are preserved). Comment and string prose therefore contains no
     * identifiers, parentheses or braces the method-signature regex could latch onto.
     */
    private static String maskNonCode(String source, byte[] states) {
        char[] masked = source.toCharArray();
        for (int i = 0; i < masked.length; i++) {
            if (states[i] != CODE && masked[i] != '\n') {
                masked[i] = ' ';
            }
        }
        return new String(masked);
    }

    /** 1-based line number of {@code index} within {@code source}. */
    private static int lineOf(String source, int index) {
        int line = 1;
        for (int i = 0; i < index && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** The trimmed, whitespace-collapsed source line containing {@code index}, for error messages. */
    private static String snippet(String source, int index) {
        int start = source.lastIndexOf('\n', Math.max(0, index - 1)) + 1;
        int end = source.indexOf('\n', index);
        if (end < 0) {
            end = source.length();
        }
        return collapse(source.substring(start, end));
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
