package com.sismics.docs.core.model.jpa;

import com.google.common.base.MoreObjects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Date;

/**
 * An alternative name that resolves to one tag (#280).
 *
 * <p>Deliberately NOT {@link Loggable}: a synonym is part of the tag's payload, edited and saved
 * with it, so the audit trail that matters is the tag's own UPDATE entry. A second, per-name log
 * line would record the same edit twice under two identities.</p>
 *
 * @author fmaass
 */
@Entity
@Table(name = "T_TAG_SYNONYM")
public class TagSynonym {
    /**
     * Synonym ID.
     */
    @Id
    @Column(name = "TSY_ID_C", length = 36)
    private String id;

    /**
     * The tag this name resolves to.
     */
    @Column(name = "TSY_IDTAG_C", nullable = false, length = 36)
    private String tagId;

    /**
     * The alternative name. Held to the same length as a tag name, because it goes through the
     * same validation rule and has to be storable wherever a tag name is.
     */
    @Column(name = "TSY_NAME_C", nullable = false, length = 36)
    private String name;

    /**
     * Creation date.
     */
    @Column(name = "TSY_CREATEDATE_D", nullable = false)
    private Date createDate;

    /**
     * Deletion date.
     */
    @Column(name = "TSY_DELETEDATE_D")
    private Date deleteDate;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTagId() {
        return tagId;
    }

    public void setTagId(String tagId) {
        this.tagId = tagId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Date getCreateDate() {
        return createDate;
    }

    public void setCreateDate(Date createDate) {
        this.createDate = createDate;
    }

    public Date getDeleteDate() {
        return deleteDate;
    }

    public void setDeleteDate(Date deleteDate) {
        this.deleteDate = deleteDate;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("tagId", tagId)
                .add("name", name)
                .toString();
    }
}
