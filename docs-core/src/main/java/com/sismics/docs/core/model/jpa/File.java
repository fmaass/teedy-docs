package com.sismics.docs.core.model.jpa;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.sismics.util.mime.MimeTypeUtil;

import jakarta.persistence.*;
import java.util.Date;

/**
 * File entity.
 * 
 * @author bgamard
 */
@Entity
@Table(name = "T_FILE")
public class File implements Loggable {
    /**
     * File ID.
     */
    @Id
    @Column(name = "FIL_ID_C", length = 36)
    private String id;
    
    /**
     * Document ID.
     */
    @Column(name = "FIL_IDDOC_C", length = 36)
    private String documentId;
    
    /**
     * User ID.
     */
    @Column(name = "FIL_IDUSER_C", length = 36, nullable = false)
    private String userId;
    
    /**
     * Name.
     */
    @Column(name = "FIL_NAME_C", length = 200)
    private String name;

    /**
     * MIME type.
     */
    @Column(name = "FIL_MIMETYPE_C", length = 100)
    private String mimeType;

    /**
     * OCR-ized content.
     */
    @Column(name = "FIL_CONTENT_C")
    private String content;
    
    /**
     * Creation date.
     */
    @Column(name = "FIL_CREATEDATE_D", nullable = false)
    private Date createDate;

    /**
     * Deletion date.
     */
    @Column(name = "FIL_DELETEDATE_D")
    private Date deleteDate;
    
    /**
     * Display order of this file.
     */
    @Column(name = "FIL_ORDER_N")
    private Integer order;
    
    /**
     * Clockwise display rotation baked into the derived _web/_thumb rasters, in degrees.
     * One of {0, 90, 180, 270}. A legacy/absent value is treated as 0 (upright) — see
     * {@link #getRotation()}. The original encrypted bytes are never re-encoded; this drives
     * only raster regeneration and the client cache-bust key.
     */
    @Column(name = "FIL_ROTATION_N")
    private Integer rotation;

    /**
     * Version ID.
     */
    @Column(name = "FIL_IDVERSION_C")
    private String versionId;

    /**
     * Version number (starting at 0).
     */
    @Column(name = "FIL_VERSION_N", nullable = false)
    private Integer version;

    /**
     * True if it's the latest version of the file.
     */
    @Column(name = "FIL_LATESTVERSION_B", nullable = false)
    private boolean latestVersion;

    public static final Long UNKNOWN_SIZE = -1L;

    /**
     * Can be {@link File#UNKNOWN_SIZE} if the size has not been stored in the database when the file has been uploaded
     */
    @Column(name = "FIL_SIZE_N", nullable = false)
    private Long size;

    /**
     * Per-document keyed content MAC (#119): lowercase-hex HMAC-SHA-256 of the file's plaintext under a key
     * derived from a deploy-time master secret and this file's document id. Nullable: it stays null for
     * orphan uploads (no document to key on) and whenever content-hash duplicate detection is disabled (no
     * master secret), so an old installation is completely unaffected. Never a raw hash — it is a keyed MAC,
     * hence the {@code MAC} column name rather than {@code SHA256}.
     */
    @Column(name = "FIL_CONTENTMAC_C", length = 64)
    private String contentMac;

    /**
     * Durable post-upload processing completion marker (#159). NULL = not proven complete. Non-null (a
     * DB-clock timestamp) = the full pipeline reached a terminal state (real success OR terminal-skip). The
     * startup reconciliation service replays lost processing for any active file whose marker is still null.
     * Written only through the fenced compare-and-swap in {@code FileDao}, never copied by {@link
     * com.sismics.docs.core.dao.FileDao#update}.
     */
    @Column(name = "FIL_PROCESSED_D")
    private Date processed;

    /**
     * Reconciliation claim lease timestamp (#159), written from the DB clock. Drives claimability and
     * expiry: a row is claimable while its marker is null and either it is unclaimed or this lease is older
     * than the lease TTL. Never compared against a per-JVM clock.
     */
    @Column(name = "FIL_PROCESSINGAT_D")
    private Date processingAt;

    /**
     * Per-claim fencing token (#159): a fresh UUID stamped on each reconciliation claim. Completion is
     * fenced to it, so a claimant whose lease expired and was reclaimed by a later cycle cannot mark a
     * successor's work complete.
     */
    @Column(name = "FIL_PROCESSINGTOKEN_C", length = 36)
    private String processingToken;

    /**
     * Bounded-retry counter (#159), incremented on each claim. At a cap the reconciler terminal-skip-stamps
     * the row so a persistently-retryable-failing file cannot keep the service alive forever.
     */
    @Column(name = "FIL_PROCATTEMPTS_N")
    private Integer procAttempts;

    /**
     * Private key to decrypt the file.
     * Not saved to database, of course.
     */
    @Transient
    private String privateKey;
    
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
    
    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getName() {
        return name;
    }

    public File setName(String name) {
        this.name = name;
        return this;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public Date getCreateDate() {
        return createDate;
    }

    public void setCreateDate(Date createDate) {
        this.createDate = createDate;
    }

    @Override
    public Date getDeleteDate() {
        return deleteDate;
    }

    public void setDeleteDate(Date deleteDate) {
        this.deleteDate = deleteDate;
    }
    
    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getOrder() {
        return order;
    }

    public void setOrder(Integer order) {
        this.order = order;
    }
    
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    /**
     * @return The stored rotation normalized to one of {0, 90, 180, 270}; 0 when never set.
     */
    public int getRotation() {
        return rotation == null ? 0 : rotation;
    }

    public void setRotation(Integer rotation) {
        this.rotation = rotation;
    }

    public String getVersionId() {
        return versionId;
    }

    public File setVersionId(String versionId) {
        this.versionId = versionId;
        return this;
    }

    public Integer getVersion() {
        return version;
    }

    public File setVersion(Integer version) {
        this.version = version;
        return this;
    }

    public boolean isLatestVersion() {
        return latestVersion;
    }

    public File setLatestVersion(boolean latestVersion) {
        this.latestVersion = latestVersion;
        return this;
    }

    /**
     * Can return {@link File#UNKNOWN_SIZE} if the file size is not stored in the database.
     */
    public Long getSize() {
        return size;
    }

    public File setSize(Long size) {
        this.size = size;
        return this;
    }

    public String getContentMac() {
        return contentMac;
    }

    public File setContentMac(String contentMac) {
        this.contentMac = contentMac;
        return this;
    }

    public Date getProcessed() {
        return processed;
    }

    public File setProcessed(Date processed) {
        this.processed = processed;
        return this;
    }

    public Date getProcessingAt() {
        return processingAt;
    }

    public File setProcessingAt(Date processingAt) {
        this.processingAt = processingAt;
        return this;
    }

    public String getProcessingToken() {
        return processingToken;
    }

    public File setProcessingToken(String processingToken) {
        this.processingToken = processingToken;
        return this;
    }

    public Integer getProcAttempts() {
        return procAttempts;
    }

    public File setProcAttempts(Integer procAttempts) {
        this.procAttempts = procAttempts;
        return this;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("name", name)
                .toString();
    }

    @Override
    public String toMessage() {
        // Attached document ID and name concatenated
        return (documentId == null ? Strings.repeat(" ", 36) : documentId) + name;
    }

    /**
     * Build the full file name.
     *
     * @param def Default name if the file doesn't have one.
     * @return File name
     */
    public String getFullName(String def) {
        if (Strings.isNullOrEmpty(name)) {
            return def + "." + MimeTypeUtil.getFileExtension(mimeType);
        }
        return name;
    }
}
