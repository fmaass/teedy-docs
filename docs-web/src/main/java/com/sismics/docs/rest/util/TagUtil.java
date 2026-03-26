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
     * Find tags by name (case-insensitive). Uses prefix matching by default,
     * or exact matching if TAG_SEARCH_MODE is set to "EXACT".
     *
     * @param name Name to search for
     * @param allTagDtoList List of all tags
     * @return List of matching tags
     */
    public static List<TagDto> findByName(String name, List<TagDto> allTagDtoList) {
        if (name.isEmpty()) {
            return Collections.emptyList();
        }
        boolean exactMode = isExactMatchMode();
        List<TagDto> tagDtoList = new ArrayList<>();
        String lowerName = name.toLowerCase();
        for (TagDto tagDto : allTagDtoList) {
            String tagName = tagDto.getName().toLowerCase();
            if (exactMode ? tagName.equals(lowerName) : tagName.startsWith(lowerName)) {
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
