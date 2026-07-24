package com.sismics.docs.core.event;

import com.google.common.base.MoreObjects;

/**
 * Post-commit request to re-index a single file's search entry after its owning document changed, WITHOUT
 * re-running the heavy processing pipeline (raster regeneration, OCR, content extraction). Distinct from
 * {@link FileUpdatedAsyncEvent} — whose listener reprocesses — so a move only re-points the file's indexed
 * {@code document_id} while keeping its already-extracted content.
 */
public class FileReindexAsyncEvent extends UserEvent {
    /**
     * File ID to re-index.
     */
    private String fileId;

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("fileId", fileId)
                .toString();
    }
}
