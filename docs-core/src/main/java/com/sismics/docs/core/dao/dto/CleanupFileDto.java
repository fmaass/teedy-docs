package com.sismics.docs.core.dao.dto;

/**
 * One file that a clean_storage run would reclaim, as surfaced by the dry-run manifest (#60).
 * A file is reclaimed because its DB row will be hard-deleted — it is already soft-deleted, its
 * uploader is gone and it backs no live document, or it is attached to a document that will be
 * deleted — OR (#72) it is a genuine filesystem orphan (no DB row) old enough to be safely reclaimed.
 * {@code size} is the file's actual on-disk footprint (original + {@code _web}/{@code _thumb}).
 */
public class CleanupFileDto {
    /**
     * Eligibility reason: the file's DB row is already soft-deleted and will be hard-deleted.
     */
    public static final String REASON_SOFT_DELETED = "soft_deleted";

    /**
     * Eligibility reason: the file's uploader is gone and it backs no live document, so the run
     * soft-deletes then hard-deletes it (the orphan-file path).
     */
    public static final String REASON_ORPHAN_UPLOADER = "orphan_uploader";

    /**
     * Eligibility reason: a still-live file whose owning document is (or will become) soft-deleted,
     * so the run soft-deletes then hard-deletes it (the #54 doc-attachment RESTRICT path).
     */
    public static final String REASON_ATTACHED_TO_DELETED_DOCUMENT = "attached_to_deleted_document";

    /**
     * Eligibility reason (#72): a genuine filesystem orphan — bytes on disk whose base id has NO
     * {@code T_FILE} row at all — old enough (older than the age threshold) to be safely reclaimed
     * without risking a concurrent in-flight upload.
     */
    public static final String REASON_FILESYSTEM_ORPHAN = "filesystem_orphan";

    private final String id;

    private final String documentId;

    private final String documentTitle;

    private final long size;

    private final String reason;

    public CleanupFileDto(String id, String documentId, String documentTitle, long size, String reason) {
        this.id = id;
        this.documentId = documentId;
        this.documentTitle = documentTitle;
        this.size = size;
        this.reason = reason;
    }

    public String getId() {
        return id;
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getDocumentTitle() {
        return documentTitle;
    }

    public long getSize() {
        return size;
    }

    public String getReason() {
        return reason;
    }
}
