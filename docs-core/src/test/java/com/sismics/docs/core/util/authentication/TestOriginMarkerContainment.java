package com.sismics.docs.core.util.authentication;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Always-on architectural guard for the account-origin partition: no origin-transition or account-restore
 * path may appear silently in the main source.
 *
 * <p>The partition is defined by three account-origin markers on a user — {@code oidcIssuer},
 * {@code oidcSubject} (OIDC origin) and {@code ldap} (LDAP origin) — plus the user-restore action (clearing a
 * user's delete date). An account's origin is set ONCE, at fresh provisioning; it must never be transitioned,
 * relinked, or resurrected on an existing account without also invalidating that account's credentials
 * (otherwise a key minted before the transition would bypass the external-origin mint block, and a restored
 * account's pre-deletion credentials would resurrect). Today there is exactly one provisioning site per origin
 * and no user-restore path at all. This test fails if a write to any of those markers is added anywhere
 * outside the allow-listed provisioning sites, forcing a conscious review (and the accompanying epoch bump)
 * before such a path can land.</p>
 *
 * <p>The allow-list is scoped to the enclosing METHOD, not merely the file: a marker write is exempt only in
 * the single provisioning method of its file (e.g. {@code OidcResource.insertOidcUserFreshTx}). A relink or
 * transition write dropped into any OTHER method of the same allow-listed file — the exact shape of a silent
 * origin transition — is therefore flagged. The one file-level exemption is the {@code User} JPA entity, whose
 * setter/column definitions ARE the marker declarations rather than call sites.</p>
 *
 * <p>It scans source text, not runtime behaviour, so it needs no database and is engine-independent.</p>
 */
public class TestOriginMarkerContainment {

    /**
     * Writes to an account's origin markers (JPA entity setters and the removed native binding method). A
     * READ (getOidcIssuer/isLdap) is deliberately NOT a marker — the partition is enforced by reading these
     * fields; only WRITES outside provisioning are dangerous.
     */
    private static final List<String> ORIGIN_MARKERS = List.of(
            "setOidcIssuer(", "setOidcSubject(", "setLdap(", "updateOidcBinding");

    /**
     * The one file whose ENTIRE contents may carry an origin marker: the
     * {@link com.sismics.docs.core.model.jpa.User} entity. Its {@code setOidcIssuer(...)} / {@code setLdap(...)}
     * lines are the setter/column DEFINITIONS, not provisioning call sites, so method scoping does not apply.
     */
    private static final Set<String> ORIGIN_FILE_ALLOWLIST = Set.of("User.java");

    /**
     * The only call sites permitted to WRITE an origin marker, scoped to the single provisioning METHOD in each
     * file. A marker write anywhere else in the same file (a relink/transition path) is a violation, even though
     * the file itself hosts the legitimate provisioning site.
     */
    private static final Map<String, Set<String>> ORIGIN_METHOD_ALLOWLIST = Map.of(
            "OidcResource.java", Set.of("insertOidcUserFreshTx"),
            "LdapAuthenticationHandler.java", Set.of("authenticate"));

    /** Clearing a delete date restores a soft-deleted entity. On a USER that is an account restore. */
    private static final String RESTORE_MARKER = "setDeleteDate(null)";

    /**
     * The only method permitted to clear a delete date: {@code DocumentDao.restore} restores a soft-deleted
     * DOCUMENT (not a user). No user-restore path exists. Method-scoped rather than file-scoped so a planted
     * user-restore hidden in some other method of {@code DocumentDao} is still caught; adding a restore anywhere
     * else trips this guard.
     */
    private static final Map<String, Set<String>> RESTORE_METHOD_ALLOWLIST = Map.of(
            "DocumentDao.java", Set.of("restore"));

    /** Keywords that produce a {@code name(...) {} } shape but are control flow, not method declarations. */
    private static final Set<String> CONTROL_KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized");

    /** Trailing Java identifier immediately preceding a parameter list — the candidate method name. */
    private static final Pattern TRAILING_IDENTIFIER = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*$");

    /**
     * Returns the offending marker if {@code line} writes an origin/restore marker that is NOT allow-listed for
     * the given source file AND enclosing method, otherwise {@code null}. This is the sole classification rule,
     * exercised against the real tree (with the real enclosing method) AND against synthetic plants below.
     *
     * @param fileName   the source file basename
     * @param methodName the name of the method whose body encloses {@code line}, or {@code null} at
     *                   class/field-initializer scope
     * @param line       the source line
     */
    static String violationFor(String fileName, String methodName, String line) {
        for (String marker : ORIGIN_MARKERS) {
            if (line.contains(marker) && !originWriteAllowed(fileName, methodName)) {
                return marker;
            }
        }
        if (line.contains(RESTORE_MARKER) && !methodAllowed(RESTORE_METHOD_ALLOWLIST, fileName, methodName)) {
            return RESTORE_MARKER;
        }
        return null;
    }

    private static boolean originWriteAllowed(String fileName, String methodName) {
        return ORIGIN_FILE_ALLOWLIST.contains(fileName)
                || methodAllowed(ORIGIN_METHOD_ALLOWLIST, fileName, methodName);
    }

    private static boolean methodAllowed(Map<String, Set<String>> allowlist, String fileName, String methodName) {
        Set<String> methods = allowlist.get(fileName);
        return methods != null && methodName != null && methods.contains(methodName);
    }

    /**
     * No production source file outside the allow-listed provisioning METHODS writes an account-origin marker
     * or restores an account.
     */
    @Test
    public void noSilentOriginTransitionOrRestorePathInMainSource() {
        List<Path> roots = mainSourceRoots();
        Assertions.assertFalse(roots.isEmpty(),
                "could not locate the docs-core/docs-web main source trees to scan");

        List<String> violations = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.getFileName().toString().endsWith(".java")).forEach(p -> {
                    String fileName = p.getFileName().toString();
                    List<String> lines;
                    try {
                        lines = Files.readAllLines(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    String[] methods = enclosingMethodPerLine(lines);
                    for (int i = 0; i < lines.size(); i++) {
                        String marker = violationFor(fileName, methods[i], lines.get(i));
                        if (marker != null) {
                            violations.add(fileName + ":" + (i + 1) + " writes origin/restore marker '" + marker
                                    + "' in method '" + methods[i] + "' outside provisioning");
                        }
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        Assertions.assertTrue(violations.isEmpty(),
                "a silent origin-transition or account-restore path was added; every such path must also"
                        + " invalidate the account's credentials (bump the epoch) and be reviewed. Offenders:\n"
                        + String.join("\n", violations));
    }

    /**
     * The classifier is not vacuous: it flags a planted origin write and a planted account restore in
     * non-provisioning files; it flags a marker write dropped into a NON-provisioning method of an allow-listed
     * file (a relink/transition path) and a write at field-initializer scope; it flags a restore in a
     * non-restore method of the restore file; while NOT flagging the writes at their real provisioning methods
     * nor a mere READ of the origin fields. If this logic were removed the real-tree scan above could never fail.
     */
    @Test
    public void classifierFlagsPlantsAndSpareProvisioningAndReads() {
        // Planted transitions/restore in files that are not provisioning sites -> flagged (method irrelevant).
        Assertions.assertNotNull(
                violationFor("RelinkResource.java", "linkAccount", "        user.setOidcIssuer(issuer);"),
                "a planted OIDC-origin write outside provisioning must be flagged");
        Assertions.assertNotNull(
                violationFor("RelinkResource.java", "linkAccount", "        user.setLdap(true);"),
                "a planted LDAP-origin write outside provisioning must be flagged");
        Assertions.assertNotNull(
                violationFor("UserDao.java", "rebind", "        q = updateOidcBinding(id, iss, sub);"),
                "reintroducing updateOidcBinding must be flagged");
        Assertions.assertNotNull(
                violationFor("UserRestoreDao.java", "restore", "        userDb.setDeleteDate(null);"),
                "a planted user-restore must be flagged");

        // A marker write in an ALLOW-LISTED FILE but a NON-provisioning method is the exact silent-transition
        // shape this tightening must catch -> flagged.
        Assertions.assertNotNull(
                violationFor("OidcResource.java", "relinkExisting", "            user.setOidcIssuer(issuer);"),
                "an OIDC-origin write in a non-provisioning method of OidcResource must be flagged");
        Assertions.assertNotNull(
                violationFor("LdapAuthenticationHandler.java", "reauthenticate", "            user.setLdap(true);"),
                "an LDAP-origin write in a non-provisioning method of LdapAuthenticationHandler must be flagged");
        Assertions.assertNotNull(
                violationFor("DocumentDao.java", "purgeUser", "        userDb.setDeleteDate(null);"),
                "a restore in a non-restore method of DocumentDao must be flagged");

        // A marker write at class/field-initializer scope (no enclosing method) in a non-User file is a violation.
        Assertions.assertNotNull(
                violationFor("OidcResource.java", null, "    private final User u = seed().setOidcIssuer(issuer);"),
                "an origin write at field-initializer scope must be flagged");

        // The real provisioning METHODS and the document-restore method are allowed.
        Assertions.assertNull(
                violationFor("OidcResource.java", "insertOidcUserFreshTx", "            user.setOidcIssuer(issuer);"),
                "the OIDC provisioning method must be allowed");
        Assertions.assertNull(
                violationFor("LdapAuthenticationHandler.java", "authenticate", "            user.setLdap(true);"),
                "the LDAP provisioning method must be allowed");
        Assertions.assertNull(
                violationFor("DocumentDao.java", "restore", "        documentDb.setDeleteDate(null);"),
                "document restore must be allowed");

        // The User entity is file-level allowed: its setter DEFINITIONS carry the marker text at any scope.
        Assertions.assertNull(
                violationFor("User.java", null, "    public User setOidcIssuer(String oidcIssuer) {"),
                "the User entity setter definitions must be allowed file-wide");
        Assertions.assertNull(
                violationFor("User.java", "setLdap", "        this.ldap = ldap;"),
                "the User entity is file-level allowed regardless of method");

        // A READ of the origin fields (how the partition is enforced) is never a violation.
        Assertions.assertNull(
                violationFor("ApiKeyResource.java", "mint", "        if (user.getOidcIssuer() != null) {"),
                "reading getOidcIssuer must not be flagged");
        Assertions.assertNull(
                violationFor("InternalAuthenticationHandler.java", "authenticate", "        if (user.isLdap()) {"),
                "reading isLdap must not be flagged");
    }

    /**
     * The enclosing-method tracker attributes each write to the method whose body encloses it — through a
     * nested lambda (the OIDC provisioning shape) — so a write in the provisioning method and a sibling write in
     * a relink method are told apart. This is the load-bearing input to the real-tree scan; if it collapsed all
     * writes to one bucket the file-vs-method distinction would be meaningless.
     */
    @Test
    public void enclosingMethodTrackerAttributesNestedWritesToTheirMethod() {
        List<String> src = List.of(
                "public class Sample {",                        // 0
                "    private User insertOidcUserFreshTx() {",   // 1
                "        return runOnFreshTransaction(em -> {",  // 2
                "            user.setOidcIssuer(issuer);",       // 3 -> insertOidcUserFreshTx
                "            user.setOidcSubject(subject);",     // 4 -> insertOidcUserFreshTx
                "        });",                                   // 5
                "    }",                                         // 6
                "    private void relinkExisting() {",           // 7
                "        if (cond) {",                           // 8
                "            user.setOidcIssuer(issuer);",       // 9 -> relinkExisting
                "        }",                                     // 10
                "    }",                                         // 11
                "}");                                            // 12

        String[] methods = enclosingMethodPerLine(src);

        Assertions.assertEquals("insertOidcUserFreshTx", methods[3],
                "a write inside the provisioning lambda must be attributed to the provisioning method");
        Assertions.assertEquals("insertOidcUserFreshTx", methods[4],
                "the second provisioning write must also be attributed to the provisioning method");
        Assertions.assertEquals("relinkExisting", methods[9],
                "a write nested in a control block of a sibling method must be attributed to that sibling");

        // End-to-end through the classifier: the provisioning writes are allowed, the sibling relink is flagged.
        Assertions.assertNull(violationFor("OidcResource.java", methods[3], src.get(3)),
                "provisioning-method write must pass");
        Assertions.assertNotNull(violationFor("OidcResource.java", methods[9], src.get(9)),
                "sibling relink-method write must be flagged");
    }

    /**
     * The tracker must not be fooled by a Java 21 text block. A multi-line {@code """ ... """} inside the
     * provisioning method contains an UNMATCHED {@code &#123;} (plus stray quotes and a {@code //} sequence); if
     * its content leaked into brace counting the provisioning method would never appear to close and a later
     * sibling method's origin write would be mis-attributed to provisioning and slip through. This asserts the
     * sibling write is attributed to the sibling (and therefore flagged), proving the text-block handling holds.
     */
    @Test
    public void enclosingMethodTrackerSurvivesTextBlockWithUnbalancedBrace() {
        List<String> src = List.of(
                "public class Sample {",                                          // 0
                "    private User insertOidcUserFreshTx() {",                     // 1
                "        String tpl = \"\"\"",                                     // 2 opens a text block
                "            { unmatched brace \"stray quote and // not-a-comment", // 3 body: brace must be ignored
                "            second line of the block with no braces",             // 4 still inside the block
                "            \"\"\";",                                             // 5 closes the text block
                "        user.setOidcIssuer(issuer);",                            // 6 -> insertOidcUserFreshTx
                "    }",                                                           // 7
                "    private void relinkExisting() {",                            // 8
                "        user.setOidcIssuer(issuer);",                            // 9 -> relinkExisting
                "    }",                                                           // 10
                "}");                                                             // 11

        String[] methods = enclosingMethodPerLine(src);

        Assertions.assertEquals("insertOidcUserFreshTx", methods[6],
                "a write after a text block must stay attributed to the enclosing provisioning method");
        Assertions.assertEquals("relinkExisting", methods[9],
                "the text block's unmatched brace must not leak; the sibling write belongs to the sibling method");

        // End-to-end: without correct text-block handling methods[9] would read as the provisioning method and pass.
        Assertions.assertNull(violationFor("OidcResource.java", methods[6], src.get(6)),
                "provisioning-method write after a text block must pass");
        Assertions.assertNotNull(violationFor("OidcResource.java", methods[9], src.get(9)),
                "sibling relink-method write after a text block must be flagged");
    }

    /**
     * Computes, for each line of a Java source file, the name of the method whose body encloses that line (or
     * {@code null} at class/field-initializer scope). A lightweight brace-depth tracker over structural code
     * (comments and string/char literals blanked so their braces and slashes never mislead it): it records a
     * method name when a signature opens at type-body scope and holds it — through nested lambdas, anonymous
     * classes and control blocks — until the method body closes. Sufficient for this conventionally-formatted
     * source (single-line signatures); a marker write at field-initializer scope reports {@code null} and is
     * therefore never provisioning-exempt.
     */
    static String[] enclosingMethodPerLine(List<String> lines) {
        String[] result = new String[lines.size()];
        int depth = 0;
        String currentMethod = null;
        int methodEndDepth = -1;
        ScanState state = new ScanState();
        for (int i = 0; i < lines.size(); i++) {
            String code = structuralCode(lines.get(i), state);
            result[i] = currentMethod; // this line's body sits inside the current method (if any)
            if (currentMethod == null) {
                String name = methodNameIfSignature(code);
                if (name != null) {
                    currentMethod = name;
                    methodEndDepth = depth;
                }
            }
            depth += count(code, '{') - count(code, '}');
            if (currentMethod != null && depth <= methodEndDepth) {
                currentMethod = null;
                methodEndDepth = -1;
            }
        }
        return result;
    }

    /** Mutable multi-line lexer state (block comment / text block) carried across lines while sanitizing. */
    private static final class ScanState {
        boolean inBlockComment;
        boolean inTextBlock;
    }

    /**
     * Returns {@code line} with comment bodies and string/char/text-block literal contents blanked, so that only
     * real code braces and tokens remain for structural tracking. Handles {@code //} and {@code /* *}{@code /}
     * comments, double- and single-quoted literals with backslash escapes, and Java text blocks
     * ({@code """ ... """}) — whose braces, quotes and slashes, possibly spanning many lines, must never affect
     * brace depth or the comment/literal state. A text block is recognised before the ordinary single-quote rule
     * so its opening {@code """} is not misread as an empty string followed by a string.
     */
    private static String structuralCode(String line, ScanState state) {
        StringBuilder sb = new StringBuilder(line.length());
        int i = 0;
        int n = line.length();
        while (i < n) {
            if (state.inTextBlock) {
                if (isTripleQuote(line, i)) {
                    state.inTextBlock = false;
                    i += 3;
                } else {
                    i++; // text-block content dropped
                }
                continue;
            }
            char c = line.charAt(i);
            if (state.inBlockComment) {
                if (c == '*' && i + 1 < n && line.charAt(i + 1) == '/') {
                    state.inBlockComment = false;
                    i += 2;
                } else {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
                break; // rest of line is a comment
            }
            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
                state.inBlockComment = true;
                i += 2;
                continue;
            }
            if (isTripleQuote(line, i)) {
                // Text-block opener; scan for a closing """ on this line, else stay open across lines.
                i += 3;
                boolean closed = false;
                while (i < n) {
                    if (isTripleQuote(line, i)) {
                        i += 3;
                        closed = true;
                        break;
                    }
                    i++;
                }
                if (!closed) {
                    state.inTextBlock = true;
                }
                continue; // text-block body dropped
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < n) {
                    char d = line.charAt(i);
                    if (d == '\\') {
                        i += 2;
                        continue;
                    }
                    if (d == quote) {
                        i++;
                        break;
                    }
                    i++;
                }
                continue; // literal body dropped
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /** True if a Java text-block delimiter {@code """} begins at index {@code i} of {@code line}. */
    private static boolean isTripleQuote(String line, int i) {
        return i + 2 < line.length()
                && line.charAt(i) == '"' && line.charAt(i + 1) == '"' && line.charAt(i + 2) == '"';
    }

    /**
     * If {@code code} (already comment/literal-sanitized) is a single-line method/constructor signature, returns
     * the declared name, otherwise {@code null}. A signature ends with {@code &#123;}, has a parameter list, and
     * the identifier before it is not a control keyword. Called only at type-body scope (no current method), so
     * control statements — which only appear inside method bodies — cannot be mistaken for declarations.
     */
    private static String methodNameIfSignature(String code) {
        String trimmed = code.trim();
        if (!trimmed.endsWith("{")) {
            return null;
        }
        int paren = trimmed.indexOf('(');
        if (paren < 0) {
            return null;
        }
        Matcher m = TRAILING_IDENTIFIER.matcher(trimmed.substring(0, paren));
        if (!m.find()) {
            return null;
        }
        String name = m.group(1);
        return CONTROL_KEYWORDS.contains(name) ? null : name;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /**
     * Locates the main-source roots to scan by walking up from the working directory to the repository root
     * (the directory holding both {@code docs-core} and {@code docs-web}), independent of which module's
     * surefire run invokes this test. {@code docs-web-common} is included when present.
     */
    private static List<Path> mainSourceRoots() {
        Path start = Paths.get("").toAbsolutePath();
        for (Path p = start; p != null; p = p.getParent()) {
            Path core = p.resolve("docs-core/src/main/java");
            Path web = p.resolve("docs-web/src/main/java");
            if (Files.isDirectory(core) && Files.isDirectory(web)) {
                List<Path> roots = new ArrayList<>(List.of(core, web));
                Path common = p.resolve("docs-web-common/src/main/java");
                if (Files.isDirectory(common)) {
                    roots.add(common);
                }
                return roots;
            }
        }
        return List.of();
    }
}
