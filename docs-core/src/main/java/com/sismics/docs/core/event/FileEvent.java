package com.sismics.docs.core.event;

import com.google.common.base.MoreObjects;

import java.nio.file.Path;

/**
 * New file event.
 *
 * @author bgamard
 */
public abstract class FileEvent extends UserEvent {
    /**
     * File ID.
     */
    private String fileId;
    
    /**
     * Language of the file.
     */
    private String language;
    
    /**
     * Unencrypted original file.
     */
    private Path unencryptedFile;

    /**
     * True when this event is a reconciliation REPLAY of lost first-time processing (issue #159), not a
     * user-facing file event. Only {@code FileReconciliationService} sets it; every live producer leaves it
     * false. The webhook listener skips replays so a recovered file does not emit a second FILE_CREATED /
     * FILE_UPDATED notification, while the processing listener treats a replay like any other event.
     */
    private boolean reprocess;

    /**
     * Fencing token of the reconciliation claim this replay runs under (issue #159), or null on a live
     * event. The completion marker is written only while the claim still owns this token, so a claim that
     * expired and was reclaimed by a later cycle cannot mark a successor's work complete.
     */
    private String processingToken;

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public Path getUnencryptedFile() {
        return unencryptedFile;
    }

    public FileEvent setUnencryptedFile(Path unencryptedFile) {
        this.unencryptedFile = unencryptedFile;
        return this;
    }

    public boolean isReprocess() {
        return reprocess;
    }

    public FileEvent setReprocess(boolean reprocess) {
        this.reprocess = reprocess;
        return this;
    }

    public String getProcessingToken() {
        return processingToken;
    }

    public FileEvent setProcessingToken(String processingToken) {
        this.processingToken = processingToken;
        return this;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("fileId", fileId)
            .add("language", language)
            .toString();
    }
}