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
 * Stored-XSS chokepoint guard (#38, HIGH-effort security blocker): every write of a
 * {@code Document} entity's description in the main sources must route its value through
 * the {@link com.sismics.docs.core.util.DescriptionSanitizer} chokepoint — at a REST
 * ingress ({@code sanitizeDescription(...)}), an inbox/EML ingress
 * ({@code DescriptionSanitizer.sanitize(...)}), or the intrinsic DAO boundary
 * (DocumentDao.update, likewise {@code DescriptionSanitizer.sanitize(...)}). A raw
 * {@code entity.setDescription(rawValue)} anywhere would store unsanitized HTML and
 * reintroduce the stored-XSS surface, so the build fails on ANY such site.
 *
 * <p>Scope: the ENTITY {@code Document.setDescription(} only. The read-projection DTO
 * ({@code DocumentDto}) has its own {@code setDescription} populated from trusted DB rows
 * during query assembly — those are not persistence writes and are excluded by receiver.
 *
 * <p>Comment-and-string-aware (the lexer of {@link TestOidcAccessorGuard}). A mutation
 * check ({@link #guardIsNotInert_dropTheSanitizerAllowlistFindsEveryWriter}) drops the
 * sanitizer allowlist and asserts every entity writer is then reported — proving the
 * guard inspects real call sites rather than passing vacuously.
 */
public class TestDescriptionSanitizerGuard {

    /** A call to setDescription — the '(' anchors the argument scan. */
    private static final Pattern SET_DESCRIPTION = Pattern.compile("\\.\\s*setDescription\\s*\\(");

    /**
     * Tokens that prove the argument passed through the sanitizer chokepoint. Any entity
     * setDescription argument must contain one of these.
     */
    private static final List<String> SANITIZED_ARGUMENT_TOKENS = List.of(
            "DescriptionSanitizer.sanitize",                // docs-core ingress + DAO boundary
            "DocumentResourceHelper.sanitizeDescription",   // hoisted REST helper (wraps the sanitizer)
            "sanitizeDescription");                         // unqualified helper call

    /**
     * Receiver prefixes whose setDescription target is the read-projection DTO, not the
     * persisted entity. These are excluded (they copy trusted DB values during query
     * assembly, never a client write).
     */
    private static final List<String> DTO_RECEIVERS = List.of("documentDto");

    // Lexical states, one per source character (shared with TestOidcAccessorGuard's model).
    private static final byte CODE = 0;
    private static final byte STRING = 1;
    private static final byte CHAR = 2;
    private static final byte LINE_COMMENT = 3;
    private static final byte BLOCK_COMMENT = 4;

    @Test
    public void everyEntityDescriptionWriterRoutesThroughTheSanitizer() throws Exception {
        List<String> violations = scanAllRoots(true);
        Assertions.assertTrue(violations.isEmpty(),
                "Every Document entity setDescription(...) must pass its value through the "
                        + "DescriptionSanitizer chokepoint (sanitizeDescription at a REST ingress, or "
                        + "DescriptionSanitizer.sanitize at the inbox/EML ingress and the DocumentDao "
                        + "boundary). A raw write stores unsanitized HTML. Offending site(s): " + violations);
    }

    /**
     * Mutation-check: with the sanitizer allowlist disabled, EVERY entity writer must be
     * reported. An empty result would mean the guard never actually inspects the writers
     * and is inert.
     */
    @Test
    public void guardIsNotInert_dropTheSanitizerAllowlistFindsEveryWriter() throws Exception {
        List<String> withoutAllowlist = scanAllRoots(false);
        Assertions.assertFalse(withoutAllowlist.isEmpty(),
                "removing the sanitizer allowlist must surface the real entity setDescription writers; "
                        + "an empty result means the guard inspects nothing");
        // The known ingress/boundary writers must all appear when the allowlist is dropped.
        Assertions.assertTrue(withoutAllowlist.stream().anyMatch(v -> v.contains("DocumentResource.java")),
                "expected the REST writers to be flagged without the allowlist, got: " + withoutAllowlist);
        Assertions.assertTrue(withoutAllowlist.stream().anyMatch(v -> v.contains("DocumentDao.java")),
                "expected the DAO boundary writer to be flagged without the allowlist, got: " + withoutAllowlist);
        Assertions.assertTrue(withoutAllowlist.stream().anyMatch(v -> v.contains("InboxService.java")),
                "expected the inbox writer to be flagged without the allowlist, got: " + withoutAllowlist);
    }

    private static List<String> scanAllRoots(boolean applyAllowlist) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : mainJavaRoots()) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    scanFile(file, applyAllowlist, violations);
                }
            }
        }
        return violations;
    }

    private static void scanFile(Path file, boolean applyAllowlist, List<String> violations)
            throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        if (!source.contains("setDescription")) {
            return;
        }
        String fileName = file.getFileName().toString();
        byte[] states = lexStates(source);
        Matcher m = SET_DESCRIPTION.matcher(source);
        while (m.find()) {
            if (states[m.start()] != CODE) {
                continue; // a setDescription inside a comment/string is not a real call
            }
            // Exclude DTO receivers: the token immediately before ".setDescription".
            if (isDtoReceiver(source, states, m.start())) {
                continue;
            }
            int openParen = source.indexOf('(', m.start());
            int closeParen = matchingParen(source, states, openParen);
            int argEnd = closeParen < 0 ? source.length() : closeParen;
            String argument = source.substring(openParen + 1, argEnd);

            if (applyAllowlist && (isSanitized(argument)
                    || argumentIsSanitizedVariable(source, states, argument, m.start()))) {
                continue;
            }
            violations.add(fileName + ": setDescription(" + collapse(argument) + ")");
        }
    }

    /** True when the argument routes through the sanitizer chokepoint. */
    private static boolean isSanitized(String argument) {
        for (String token : SANITIZED_ARGUMENT_TOKENS) {
            if (argument.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the argument is a bare variable that is assigned from a sanitizer
     * expression BEFORE this call site (the REST pattern {@code description =
     * sanitizeDescription(...); ...; document.setDescription(description);}). Scans only
     * assignments at or before the call site so a later re-assignment can never launder it.
     */
    private static boolean argumentIsSanitizedVariable(String source, byte[] states,
                                                       String argument, int callIndex) {
        String var = argument.trim();
        // Only a bare identifier can be traced; a compound expression must contain the
        // token itself (handled by isSanitized).
        if (!var.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            return false;
        }
        for (String token : SANITIZED_ARGUMENT_TOKENS) {
            Pattern assign = Pattern.compile("\\b" + Pattern.quote(var) + "\\s*=\\s*" + Pattern.quote(token));
            Matcher am = assign.matcher(source);
            while (am.find()) {
                if (am.start() < callIndex && states[am.start()] == CODE) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when the receiver immediately preceding {@code .setDescription} is a DTO. */
    private static boolean isDtoReceiver(String source, byte[] states, int dotIndex) {
        // Walk back over whitespace then over the identifier before the dot.
        int i = dotIndex - 1;
        while (i >= 0 && Character.isWhitespace(source.charAt(i))) {
            i--;
        }
        int end = i + 1;
        while (i >= 0 && (Character.isJavaIdentifierPart(source.charAt(i)))) {
            i--;
        }
        String receiver = source.substring(i + 1, end);
        return DTO_RECEIVERS.contains(receiver);
    }

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

    /** The shared lexer: classifies each char as code/literal/comment. */
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
