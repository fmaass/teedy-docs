package com.sismics.docs.core.dao.dto;

/**
 * Tag DTO.
 *
 * @author bgamard 
 */
public class RelationDto {
    /**
     * Document ID.
     */
    private String id;
    
    /**
     * Document title.
     */
    private String title;

    /**
     * True if the document is the source of the relation.
     */
    private boolean source;

    /**
     * Creation date of the OTHER document (epoch milliseconds) — the one this row describes, never
     * the document the relations were queried for.
     *
     * <p>NULLABLE: {@code DOC_CREATEDATE_D} is declared without {@code not null} (dbupdate-000-0.sql)
     * and no later migration tightened it, so a legacy row can carry no creation date at all. The
     * entity's {@code nullable = false} does not enforce it either — schema generation is off.</p>
     */
    private Long createTimestamp;
    
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public boolean isSource() {
        return source;
    }

    public void setSource(boolean source) {
        this.source = source;
    }

    public Long getCreateTimestamp() {
        return createTimestamp;
    }

    public void setCreateTimestamp(Long createTimestamp) {
        this.createTimestamp = createTimestamp;
    }
}
