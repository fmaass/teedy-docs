package com.sismics.docs.core.model.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Date;

/**
 * Per-user saved document filter.
 *
 * <p>The filter payload is the CANONICAL URL query string captured from the
 * documents route (e.g. {@code tags=a,b&exclude=c&mode=or&search=foo&workflow=me}).
 * The URL is the single source of truth for the filter state — no structured JSON.
 */
@Entity
@Table(name = "T_SAVED_FILTER")
public class SavedFilter {
    @Id
    @Column(name = "SFL_ID_C", length = 36)
    private String id;

    @Column(name = "SFL_IDUSER_C", nullable = false, length = 36)
    private String userId;

    @Column(name = "SFL_NAME_C", nullable = false, length = 100)
    private String name;

    @Column(name = "SFL_QUERY_C", nullable = false, length = 2000)
    private String query;

    @Column(name = "SFL_CREATEDATE_D", nullable = false)
    private Date createDate;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public Date getCreateDate() { return createDate; }
    public void setCreateDate(Date createDate) { this.createDate = createDate; }
}
