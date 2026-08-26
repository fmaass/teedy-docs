package com.sismics.docs.rest.util;

import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.dao.dto.TagDto;
import com.sismics.docs.core.util.ConfigUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tag utilities.
 *
 * @author bgamard
 */
public class TagUtil {
    /**
     * Marker on a tag search term that stands for any run of characters, the empty run included.
     */
    private static final char WILDCARD = '*';

    /**
     * Recursively find children of a tag.
     *
     * @param parentTagDto Parent tag
     * @param allTagDtoList List of all tags
     * @return Children tags
     */
    public static List<TagDto> findChildren(TagDto parentTagDto, List<TagDto> allTagDtoList) {
        List<TagDto> childrenTagDtoList = new ArrayList<>();

        for (TagDto tagDto : allTagDtoList) {
            if (parentTagDto.getId().equals(tagDto.getParentId())) {
                childrenTagDtoList.add(tagDto);
                childrenTagDtoList.addAll(findChildren(tagDto, allTagDtoList));
            }
        }

        return childrenTagDtoList;
    }

    /**
     * Every name a term may reach a tag by: its own name, then its synonyms (#280).
     *
     * <p>A synonym is a second NAME for the tag, not a second tag, so it takes part in all three
     * resolution steps below and in none of the results: what comes back is always the tag. The
     * tag's own name is first so that the cheapest and most common match is tested first.</p>
     *
     * <p>Because the list this walks is the caller's own ACL-scoped tag list, a synonym cannot
     * arrive on a tag the caller may not read — which is the whole of the permission rule for
     * synonym resolution. There is no second check here, and there must not be one: a check that
     * could disagree with the list would be a second authority over the same question.</p>
     */
    private static List<String> searchableNames(TagDto tagDto) {
        List<String> synonyms = tagDto.getSynonyms();
        if (synonyms == null || synonyms.isEmpty()) {
            return Collections.singletonList(tagDto.getName());
        }
        List<String> names = new ArrayList<>(synonyms.size() + 1);
        names.add(tagDto.getName());
        names.addAll(synonyms);
        return names;
    }

    /**
     * Find tags by name (case-insensitive), a tag's synonyms counting as names of it (#280).
     *
     * <p>The term is resolved in three steps:
     * <ol>
     *   <li>a tag named exactly like the term wins on its own. This keeps a term that names a tag
     *       from also dragging in its longer prefix siblings, and it keeps a tag whose name really
     *       carries a {@code *} reachable by typing that name (the tag tree sends names verbatim);</li>
     *   <li>otherwise an {@code *} anywhere in the term makes the term a glob: each {@code *} stands
     *       for any run of characters, the empty run included, and the literals between them must
     *       occur in the name in the order they were typed. Asking explicitly outranks
     *       TAG_SEARCH_MODE, so this holds even when the mode is EXACT;</li>
     *   <li>otherwise TAG_SEARCH_MODE decides: prefix matching by default, nothing in EXACT mode
     *       (step 1 has already ruled out an exact hit).</li>
     * </ol>
     *
     * <p>A term made of asterisks only leaves no literal to match on, so it selects nothing rather
     * than everything. Because step 1 runs first, a legacy tag whose name really is {@code *} is
     * still reached by typing it -- new names may no longer contain one.
     *
     * @param name Name to search for
     * @param allTagDtoList List of all tags
     * @return List of matching tags
     */
    public static List<TagDto> findByName(String name, List<TagDto> allTagDtoList) {
        if (name.isEmpty()) {
            return Collections.emptyList();
        }

        List<TagDto> exactTagDtoList = matchByName(name, allTagDtoList, true);
        if (!exactTagDtoList.isEmpty()) {
            return exactTagDtoList;
        }

        if (name.indexOf(WILDCARD) >= 0) {
            return matchByGlob(name, allTagDtoList);
        }

        if (isExactMatchMode()) {
            return Collections.emptyList();
        }
        return matchByName(name, allTagDtoList, false);
    }

    /**
     * Collect the tags whose name equals (or starts with) the given term, ignoring case and
     * preserving the order of the input list.
     *
     * <p>The comparison is made per character rather than by folding the two strings to lower
     * case, because no single fold is correct for the Turkish dotted/dotless I. Folding with the
     * default locale breaks ASCII names on a Turkish host (capital I becomes the dotless U+0131,
     * so "Invoice" stops matching a typed "invoice"), while folding with a fixed root locale
     * breaks Turkish names (the dotted capital I, U+0130, expands to an ASCII i plus a combining
     * dot above, so a tag named with it stops matching a typed "istanbul"). {@code
     * equalsIgnoreCase} and {@code regionMatches} compare with the per-character
     * {@code Character.toUpperCase}/{@code toLowerCase}, which are locale-independent and map
     * U+0130 straight to an ASCII i, so both directions match.
     *
     * <p>{@code regionMatches} also returns false when the term is longer than the name, so the
     * prefix test needs no separate length guard.
     */
    private static List<TagDto> matchByName(String name, List<TagDto> allTagDtoList, boolean exact) {
        List<TagDto> tagDtoList = new ArrayList<>();
        for (TagDto tagDto : allTagDtoList) {
            // A tag reached through two of its own names is still ONE tag: the loop stops at the
            // first name that matches, so a term cannot put the same id in the criteria twice.
            for (String tagName : searchableNames(tagDto)) {
                if (exact ? name.equalsIgnoreCase(tagName)
                        : tagName.regionMatches(true, 0, name, 0, name.length())) {
                    tagDtoList.add(tagDto);
                    break;
                }
            }
        }
        return tagDtoList;
    }

    /**
     * Collect the tags whose name matches the glob term, preserving the order of the input list.
     *
     * <p>The term is not compiled into a regular expression. A tag term is user input, so every
     * regex metacharacter it contains would have to be quoted, and a term of alternating literals
     * and wildcards is exactly the shape that makes a backtracking matcher run super-linearly --
     * a denial-of-service surface on a search box. The matcher below walks the name once per
     * literal instead, never revisiting a decision.
     */
    private static List<TagDto> matchByGlob(String name, List<TagDto> allTagDtoList) {
        String[] literals = splitOnWildcards(name);
        if (literals.length == 0) {
            return Collections.emptyList();
        }
        boolean anchoredStart = name.charAt(0) != WILDCARD;
        boolean anchoredEnd = name.charAt(name.length() - 1) != WILDCARD;

        List<TagDto> tagDtoList = new ArrayList<>();
        for (TagDto tagDto : allTagDtoList) {
            // As in matchByName: the first of the tag's names that matches is enough, and the
            // tag is added once however many of them the glob happens to fit.
            for (String tagName : searchableNames(tagDto)) {
                if (globMatches(literals, anchoredStart, anchoredEnd, tagName)) {
                    tagDtoList.add(tagDto);
                    break;
                }
            }
        }
        return tagDtoList;
    }

    /**
     * Cut the term into the literal runs between its wildcards, dropping the empty ones so that
     * consecutive wildcards collapse into a single "any run" and a term of wildcards only yields
     * no literal at all.
     */
    private static String[] splitOnWildcards(String name) {
        List<String> literalList = new ArrayList<>();
        int start = 0;
        while (start < name.length()) {
            int wildcard = name.indexOf(WILDCARD, start);
            if (wildcard < 0) {
                literalList.add(name.substring(start));
                break;
            }
            if (wildcard > start) {
                literalList.add(name.substring(start, wildcard));
            }
            start = wildcard + 1;
        }
        return literalList.toArray(new String[0]);
    }

    /**
     * Match one name against the literals of a glob term.
     *
     * <p>A literal that the term anchors (one before the first wildcard, one after the last) can
     * only sit at that end of the name, so it is checked in place. Every other literal is free to
     * slide, and is taken at its EARLIEST occurrence after the previous one: an earlier position
     * leaves at least as much of the name for the literals that follow, so the leftmost choice can
     * never turn a match into a miss. That is what lets the whole match run without backtracking.
     *
     * <p>Comparisons use {@code regionMatches} with the ignore-case flag for the same reason the
     * rest of this class does: it folds per character, which is the only reading correct both for
     * ASCII names on a Turkish host and for Turkish names anywhere.
     *
     * @param literals Literal runs of the term, in order, none empty
     * @param anchoredStart Whether the term begins with a literal rather than a wildcard
     * @param anchoredEnd Whether the term ends with a literal rather than a wildcard
     * @param name Tag name to test
     */
    private static boolean globMatches(String[] literals, boolean anchoredStart, boolean anchoredEnd, String name) {
        int from = 0;
        int to = name.length();
        int first = 0;
        int end = literals.length;

        if (anchoredStart) {
            String literal = literals[0];
            if (!name.regionMatches(true, 0, literal, 0, literal.length())) {
                return false;
            }
            from = literal.length();
            first = 1;
        }
        // The two anchors are never the same literal: a term anchored at both ends keeps its
        // wildcards strictly inside it, so it carries a literal on either side of them.
        if (anchoredEnd) {
            String literal = literals[end - 1];
            int offset = name.length() - literal.length();
            if (offset < from || !name.regionMatches(true, offset, literal, 0, literal.length())) {
                return false;
            }
            to = offset;
            end--;
        }

        for (int i = first; i < end; i++) {
            String literal = literals[i];
            int found = indexOfIgnoreCase(name, literal, from, to);
            if (found < 0) {
                return false;
            }
            from = found + literal.length();
        }
        return true;
    }

    /**
     * First offset in {@code [from, to)} at which the name carries the literal, ignoring case, or
     * -1 when it does not carry it there at all.
     */
    private static int indexOfIgnoreCase(String name, String literal, int from, int to) {
        int lastStart = to - literal.length();
        for (int start = from; start <= lastStart; start++) {
            if (name.regionMatches(true, start, literal, 0, literal.length())) {
                return start;
            }
        }
        return -1;
    }

    private static boolean isExactMatchMode() {
        try {
            return "EXACT".equals(ConfigUtil.getConfigStringValue(ConfigType.TAG_SEARCH_MODE));
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
