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
     * Trailing marker on a tag search term that forces prefix matching for that term.
     */
    private static final String WILDCARD = "*";

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
     * Find tags by name (case-insensitive).
     *
     * <p>The term is resolved in three steps:
     * <ol>
     *   <li>a tag named exactly like the term wins on its own. This keeps a term that names a tag
     *       from also dragging in its longer prefix siblings, and it keeps a tag whose name really
     *       ends with {@code *} reachable by typing that name (the tag tree sends names verbatim);</li>
     *   <li>otherwise a single trailing {@code *} is the prefix operator: it is stripped and the
     *       remainder prefix-matches. Asking explicitly outranks TAG_SEARCH_MODE, so this holds even
     *       when the mode is EXACT;</li>
     *   <li>otherwise TAG_SEARCH_MODE decides: prefix matching by default, nothing in EXACT mode
     *       (step 1 has already ruled out an exact hit).</li>
     * </ol>
     *
     * <p>{@code *} is deliberately not general glob syntax -- tag names are free text, so an
     * asterisk anywhere but at the end is an ordinary character of the name, and a bare {@code *}
     * has no prefix left to match and selects nothing rather than everything.
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

        if (name.endsWith(WILDCARD)) {
            String prefix = name.substring(0, name.length() - WILDCARD.length());
            if (prefix.isEmpty()) {
                return Collections.emptyList();
            }
            return matchByName(prefix, allTagDtoList, false);
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
            String tagName = tagDto.getName();
            if (exact ? name.equalsIgnoreCase(tagName)
                    : tagName.regionMatches(true, 0, name, 0, name.length())) {
                tagDtoList.add(tagDto);
            }
        }
        return tagDtoList;
    }

    private static boolean isExactMatchMode() {
        try {
            return "EXACT".equals(ConfigUtil.getConfigStringValue(ConfigType.TAG_SEARCH_MODE));
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
