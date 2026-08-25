package com.sismics.docs.core.dao.dto;

import java.util.Date;

/**
 * A PUBLISHED saved filter as it is read by someone other than its owner (#51).
 *
 * <p>Distinct from the {@code SavedFilter} entity because the shared list needs one thing the
 * entity cannot carry: the publisher's USERNAME. Two users may each own a filter called
 * "Invoices" — the unique index is {@code (user, name)} — so a shared list without the owner's
 * name would show two indistinguishable rows.</p>
 *
 * <p>It carries no ACL verdict of its own: whether a viewer may APPLY this filter depends on the
 * viewer, so that judgement is made per request by {@code SavedFilterUtil}, never stored here.</p>
 */
public class SavedFilterDto {
    private String id;

    private String userId;

    private String username;

    private String name;

    private String query;

    private Date createDate;

    private Date publishDate;

    public String getId() { return id; }
    public SavedFilterDto setId(String id) { this.id = id; return this; }

    public String getUserId() { return userId; }
    public SavedFilterDto setUserId(String userId) { this.userId = userId; return this; }

    public String getUsername() { return username; }
    public SavedFilterDto setUsername(String username) { this.username = username; return this; }

    public String getName() { return name; }
    public SavedFilterDto setName(String name) { this.name = name; return this; }

    public String getQuery() { return query; }
    public SavedFilterDto setQuery(String query) { this.query = query; return this; }

    public Date getCreateDate() { return createDate; }
    public SavedFilterDto setCreateDate(Date createDate) { this.createDate = createDate; return this; }

    public Date getPublishDate() { return publishDate; }
    public SavedFilterDto setPublishDate(Date publishDate) { this.publishDate = publishDate; return this; }
}
