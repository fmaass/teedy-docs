package com.sismics.docs.core.dao.dto;

import java.util.List;

/**
 * Tag DTO.
 *
 * @author bgamard
 */
public class TagDto {
    /**
     * Tag ID.
     */
    private String id;
    
    /**
     * Name.
     */
    private String name;
    
    /**
     * Color.
     */
    private String color;
    
    /**
     * Icon (#287): {@code emoji:<grapheme>}, {@code set:<iconId>}, or null for no icon.
     */
    private String icon;

    /**
     * Parent ID.
     */
    private String parentId;

    /**
     * Creator.
     */
    private String creator;

    /**
     * Alternative names that resolve to this tag (#280).
     *
     * <p>Carried on the tag itself rather than fetched separately, which is what makes synonym
     * resolution ACL-scoped for free: every list of these DTOs is already restricted to the tags
     * the caller may READ, so a synonym can never arrive attached to a tag that is not in it.
     * Never null — a tag with no synonym has an empty list, so no caller has to null-check.</p>
     */
    private List<String> synonyms = List.of();

    public String getId() {
        return id;
    }

    public TagDto setId(String id) {
        this.id = id;
        return this;
    }

    public String getName() {
        return name;
    }

    public TagDto setName(String name) {
        this.name = name;
        return this;
    }

    public String getColor() {
        return color;
    }

    public TagDto setColor(String color) {
        this.color = color;
        return this;
    }
    
    public String getIcon() {
        return icon;
    }

    public TagDto setIcon(String icon) {
        this.icon = icon;
        return this;
    }

    public String getParentId() {
        return parentId;
    }

    public TagDto setParentId(String parentId) {
        this.parentId = parentId;
        return this;
    }

    public String getCreator() {
        return creator;
    }

    public TagDto setCreator(String creator) {
        this.creator = creator;
        return this;
    }

    public List<String> getSynonyms() {
        return synonyms;
    }

    public TagDto setSynonyms(List<String> synonyms) {
        this.synonyms = synonyms == null ? List.of() : synonyms;
        return this;
    }
}
