package com.sismics.docs.rest.resource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PII log guard (#45): the OIDC subject ({@code sub}) is a stable cross-session identifier.
 * It may be logged only at DEBUG (off in production); an {@code error}/{@code warn}/{@code info}
 * statement that carries it would leak a PII correlator into always-on logs.
 *
 * <p>This test reads {@link OidcResource}'s source and fails on any {@code log.error/warn/info}
 * STATEMENT — scanned to its terminating semicolon, so a multiline call cannot split its way
 * past the check — whose argument list references the subject: either the {@code sub={}}
 * placeholder in the format string, or the subject-carrying variable ({@code subject}) passed
 * as an argument. DEBUG statements are exempt by design and are not scanned.
 *
 * <p>All scanning is backed by ONE lexer ({@link #lexStates(String)}) that classifies every
 * source character as code, string/char literal, or comment. This is what makes the guard
 * evasion-proof against lexical tricks: a {@code ;} inside a string literal cannot truncate
 * the statement scan, and a quote inside a {@code //} or <code>/* *&#47;</code> comment cannot
 * invert literal state (which would let a semicolon inside a REAL format string read as code
 * and cut the statement before the leak).
 */
public class TestOidcSubjectLogGuard {

    /** log.error( / log.warn( / log.info( — the always-on levels the subject must never reach. */
    private static final Pattern LOG_CALL_START =
            Pattern.compile("\\blog\\s*\\.\\s*(error|warn|info)\\s*\\(");

    /** The subject placeholder in a format string — the interpolated value would be emitted. */
    private static final Pattern SUBJECT_PLACEHOLDER =
            Pattern.compile("sub\\s*=\\s*\\{\\}");

    /** The subject-carrying variable passed as an argument (checked in code only). */
    private static final Pattern SUBJECT_VARIABLE =
            Pattern.compile("\\bsubject\\b");

    // Lexical states, one per source character.
    private static final byte CODE = 0;
    private static final byte STRING = 1;
    private static final byte CHAR = 2;
    private static final byte LINE_COMMENT = 3;
    private static final byte BLOCK_COMMENT = 4;

    @Test
    public void noAlwaysOnLogStatementCarriesTheOidcSubject() throws Exception {
        String source = readOidcResourceSource();
        byte[] states = lexStates(source);

        List<String> violations = new ArrayList<>();
        Matcher starts = LOG_CALL_START.matcher(source);
        while (starts.find()) {
            // Text that merely LOOKS like a log call inside a comment or string is not code.
            if (states[starts.start()] != CODE) {
                continue;
            }
            // Scan from the opening '(' to the terminating ';' of THIS statement, so a
            // multiline log call is evaluated whole and cannot evade the check by wrapping.
            // The terminating ';' is the first semicolon in CODE state: one inside a string
            // or char literal cannot truncate the scan, and a quote inside a comment cannot
            // corrupt literal state to fake one.
            int stmtEnd = findStatementEnd(source, states, starts.end());
            int end = stmtEnd < 0 ? source.length() : stmtEnd;
            // A leak is either the `sub={}` placeholder (interpolates the value into the
            // format string — checked with comments blanked but literals kept) OR the
            // `subject` VARIABLE as an argument (checked with literals AND comments blanked,
            // so message prose like "(subject at DEBUG)" is not a leak).
            String placeholderView = maskStatement(source, states, starts.start(), end, false);
            String variableView = maskStatement(source, states, starts.start(), end, true);
            if (SUBJECT_PLACEHOLDER.matcher(placeholderView).find()
                    || SUBJECT_VARIABLE.matcher(variableView).find()) {
                violations.add(collapse(source.substring(starts.start(), end)));
            }
        }

        Assertions.assertTrue(violations.isEmpty(),
                "OIDC subject must never be logged at error/warn/info (leaks a PII correlator into "
                        + "always-on logs — log it only at DEBUG). Offending statement(s): " + violations);
    }

    private static String readOidcResourceSource() throws Exception {
        // Surefire's working directory is the module basedir (docs-web); resolve the source
        // under it, with a fallback up one level in case a runner starts from the repo root.
        Path rel = Paths.get("src", "main", "java", "com", "sismics", "docs", "rest",
                "resource", "OidcResource.java");
        Path candidate = rel;
        if (!Files.exists(candidate)) {
            candidate = Paths.get("docs-web").resolve(rel);
        }
        Assertions.assertTrue(Files.exists(candidate),
                "could not locate OidcResource.java source at " + candidate.toAbsolutePath());
        return Files.readString(candidate, StandardCharsets.UTF_8);
    }

    /**
     * The single lexer both scanners share: classifies every character of {@code source} as
     * CODE, STRING/CHAR literal content (delimiters included), or LINE/BLOCK comment content.
     * Backslash escapes inside literals are consumed ({@code \"} does not close a string);
     * comment openers inside literals are content; quotes inside comments are content —
     * neither can flip the other's state.
     */
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

    /**
     * Index of the first ';' in CODE state at or after {@code from}, per the shared lexer.
     * Returns -1 when none exists (truncated source — the caller then scans to EOF, which can
     * only widen detection, never narrow it).
     */
    private static int findStatementEnd(String source, byte[] states, int from) {
        for (int i = from; i < source.length(); i++) {
            if (states[i] == CODE && source.charAt(i) == ';') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Renders {@code source[start, end)} with comment characters blanked to spaces — and,
     * when {@code blankLiterals} is set, string/char literal characters blanked too — using
     * the shared lexer's states. Blanking preserves offsets, so word boundaries stay intact.
     */
    private static String maskStatement(String source, byte[] states, int start, int end,
                                        boolean blankLiterals) {
        StringBuilder sb = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            byte s = states[i];
            boolean blank = s == LINE_COMMENT || s == BLOCK_COMMENT
                    || (blankLiterals && (s == STRING || s == CHAR));
            sb.append(blank ? ' ' : source.charAt(i));
        }
        return sb.toString();
    }

    private static String collapse(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
