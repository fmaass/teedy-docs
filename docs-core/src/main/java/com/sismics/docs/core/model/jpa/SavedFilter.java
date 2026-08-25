package com.sismics.docs.core.model.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Date;

/**
 * A user's saved document filter.
 *
 * <p>The filter payload is the CANONICAL URL query string captured from the
 * documents route (e.g. {@code tags=a,b&exclude=c&mode=or&search=foo&workflow=me}).
 * The URL is the single source of truth for the filter state — no structured JSON.
 *
 * <p>A filter belongs to exactly one user, who may PUBLISH it to the whole instance (#51).
 * Publication is visibility only: a published filter is still owned, still edited, renamed
 * and deleted by that one user, and {@link #publishDate} is the only thing publication
 * changes.
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

    /**
     * When this filter was published to every user, or null while it is private to its owner
     * (#51). Nullable BY DESIGN: null is the private state, so a row that predates the feature —
     * and any row written without naming this column — is private, never accidentally shared.
     */
    @Column(name = "SFL_PUBLISHDATE_D")
    private Date publishDate;

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

    public Date getPublishDate() { return publishDate; }
    public void setPublishDate(Date publishDate) { this.publishDate = publishDate; }
}
