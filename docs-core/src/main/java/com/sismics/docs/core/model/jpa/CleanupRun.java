package com.sismics.docs.core.model.jpa;

import java.util.Date;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.google.common.base.MoreObjects;

/**
 * Storage-cleanup run record (#60). One row is written per REAL clean_storage run to
 * form a durable protocol of what was reclaimed, when, and by whom. These rows are
 * deliberately NOT touched by clean_storage itself (unlike an audit-log entry, which the
 * orphan-audit-log sweep would purge), so the protocol survives every subsequent cleanup.
 */
@Entity
@Table(name = "T_CLEANUP_RUN")
public class CleanupRun {
    /**
     * Run ID.
     */
    @Id
    @Column(name = "CLR_ID_C", length = 36)
    private String id;

    /**
     * Number of files hard-deleted by the run (DB soft-deleted files + filesystem orphans).
     */
    @Column(name = "CLR_FILECOUNT_N", nullable = false)
    private long fileCount;

    /**
     * Bytes reclaimed by the run.
     */
    @Column(name = "CLR_BYTES_N", nullable = false)
    private long bytes;

    /**
     * Acting admin user ID. Stored as a plain value (NOT a foreign key) so the protocol
     * row outlives the acting admin's own later deletion/purge.
     */
    @Column(name = "CLR_IDUSER_C", length = 36)
    private String userId;

    /**
     * Acting admin username snapshot (so the protocol is readable without a user join).
     */
    @Column(name = "CLR_USERNAME_C", length = 50)
    private String username;

    /**
     * Run timestamp.
     */
    @Column(name = "CLR_CREATEDATE_D", nullable = false)
    private Date createDate;

    public String getId() {
        return id;
    }

    public CleanupRun setId(String id) {
        this.id = id;
        return this;
    }

    public long getFileCount() {
        return fileCount;
    }

    public CleanupRun setFileCount(long fileCount) {
        this.fileCount = fileCount;
        return this;
    }

    public long getBytes() {
        return bytes;
    }

    public CleanupRun setBytes(long bytes) {
        this.bytes = bytes;
        return this;
    }

    public String getUserId() {
        return userId;
    }

    public CleanupRun setUserId(String userId) {
        this.userId = userId;
        return this;
    }

    public String getUsername() {
        return username;
    }

    public CleanupRun setUsername(String username) {
        this.username = username;
        return this;
    }

    public Date getCreateDate() {
        return createDate;
    }

    public CleanupRun setCreateDate(Date createDate) {
        this.createDate = createDate;
        return this;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("fileCount", fileCount)
                .add("bytes", bytes)
                .add("username", username)
                .toString();
    }
}
