package com.sismics.docs.core.util;

/**
 * Rich outcome of {@link FileUtil#createFile}. It reports not only the resulting file id but the EXPLICIT
 * resources the call acquired, so a caller cleans up off the real acquired-resource flags rather than
 * inferring them from {@code created}:
 *
 * <ul>
 *   <li>a REAL create reserved quota, wrote (committed) the encrypted blob, and handed the plaintext temp
 *       off to the async processing pipeline — {@code quotaReserved / blobCommitted / tempAccepted} are all
 *       true, and the request must NOT delete the temp (the pipeline owns it);</li>
 *   <li>a content-dedup NO-OP (#119) reserved nothing, demoted nothing, wrote no blob and queued no
 *       processing — every flag is false, so the request's own {@code finally} deletes the plaintext temp
 *       (no leak).</li>
 * </ul>
 *
 * <p>{@code duplicateKind} / {@code duplicateOfId} carry an ADVISORY content-duplicate hint (never a
 * server-side action): on a real create they point at an existing active file with identical content in the
 * same document (the "add it as a version instead" hint), and on a no-op they point at the current version
 * the upload collapsed onto.</p>
 */
public final class FileCreatedResult {
    /** Content-duplicate hint kind carried to the client (only value today). */
    public static final String DUPLICATE_CONTENT = "content";

    private final String fileId;
    private final boolean created;
    private final String duplicateKind;
    private final String duplicateOfId;
    private final boolean quotaReserved;
    private final boolean blobCommitted;
    private final boolean tempAccepted;

    private FileCreatedResult(String fileId, boolean created, String duplicateKind, String duplicateOfId,
                              boolean quotaReserved, boolean blobCommitted, boolean tempAccepted) {
        this.fileId = fileId;
        this.created = created;
        this.duplicateKind = duplicateKind;
        this.duplicateOfId = duplicateOfId;
        this.quotaReserved = quotaReserved;
        this.blobCommitted = blobCommitted;
        this.tempAccepted = tempAccepted;
    }

    /**
     * A real create: quota reserved, blob committed, plaintext temp handed to the async pipeline.
     *
     * @param fileId New file id
     * @param duplicateKind Advisory duplicate-hint kind, or null
     * @param duplicateOfId Advisory id of the existing identical file, or null
     */
    static FileCreatedResult created(String fileId, String duplicateKind, String duplicateOfId) {
        return new FileCreatedResult(fileId, true, duplicateKind, duplicateOfId, true, true, true);
    }

    /**
     * A content-dedup no-op collapsing onto an existing current version: nothing was acquired.
     *
     * @param existingFileId The current version the upload is identical to (also the returned file id)
     */
    static FileCreatedResult noop(String existingFileId) {
        return new FileCreatedResult(existingFileId, false, DUPLICATE_CONTENT, existingFileId, false, false, false);
    }

    public String getFileId() {
        return fileId;
    }

    public boolean isCreated() {
        return created;
    }

    public String getDuplicateKind() {
        return duplicateKind;
    }

    public String getDuplicateOfId() {
        return duplicateOfId;
    }

    public boolean isQuotaReserved() {
        return quotaReserved;
    }

    public boolean isBlobCommitted() {
        return blobCommitted;
    }

    public boolean isTempAccepted() {
        return tempAccepted;
    }
}
