package com.sismics.docs.core.model.jpa;

import com.google.common.base.MoreObjects;
import com.sismics.docs.core.constant.AccessTargetType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Date;

/**
 * One recorded access of a document or a file (#300): who read what, and when.
 *
 * <p>This is the base record the access COUNTERS aggregate over — the counters are derived, never
 * stored — and the same rows are what a later access-history slice reads. Rows are append-only:
 * nothing in the application updates or deletes one.</p>
 */
@Entity
@Table(name = "T_ACCESS_EVENT")
public class AccessEvent {
    /**
     * Access event ID.
     */
    @Id
    @Column(name = "ACC_ID_C", length = 36)
    private String id;

    /**
     * Acting user ID. A plain value, not a mapped relation: the event must survive that user's
     * deletion so the history stays truthful.
     */
    @Column(name = "ACC_IDUSER_C", nullable = false, length = 36)
    private String userId;

    /**
     * Kind of the accessed target.
     */
    @Column(name = "ACC_TYPE_C", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private AccessTargetType type;

    /**
     * Accessed target ID: a document ID for {@link AccessTargetType#DOCUMENT}, a file ID for
     * {@link AccessTargetType#FILE}.
     */
    @Column(name = "ACC_IDTARGET_C", nullable = false, length = 36)
    private String targetId;

    /**
     * Owning document ID at access time. Equal to {@link #targetId} for a document event; the file's
     * document for a file event, or null when the file is attached to none. Snapshotted rather than
     * joined through {@code T_FILE} so a later file MOVE cannot rewrite where a past read happened.
     */
    @Column(name = "ACC_IDDOC_C", length = 36)
    private String documentId;

    /**
     * Access date.
     */
    @Column(name = "ACC_CREATEDATE_D", nullable = false)
    private Date createDate;

    public String getId() {
        return id;
    }

    public AccessEvent setId(String id) {
        this.id = id;
        return this;
    }

    public String getUserId() {
        return userId;
    }

    public AccessEvent setUserId(String userId) {
        this.userId = userId;
        return this;
    }

    public AccessTargetType getType() {
        return type;
    }

    public AccessEvent setType(AccessTargetType type) {
        this.type = type;
        return this;
    }

    public String getTargetId() {
        return targetId;
    }

    public AccessEvent setTargetId(String targetId) {
        this.targetId = targetId;
        return this;
    }

    public String getDocumentId() {
        return documentId;
    }

    public AccessEvent setDocumentId(String documentId) {
        this.documentId = documentId;
        return this;
    }

    public Date getCreateDate() {
        return createDate;
    }

    public AccessEvent setCreateDate(Date createDate) {
        this.createDate = createDate;
        return this;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("type", type)
                .add("targetId", targetId)
                .toString();
    }
}
