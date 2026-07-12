package com.sismics.docs.core.dao.dto;

/**
 * Per-user storage usage row for the admin statistics dashboard.
 */
public class UserStorageDto {
    /**
     * Username.
     */
    private String username;

    /**
     * Current storage used (in bytes).
     */
    private long storageCurrent;

    /**
     * Storage quota (in bytes).
     */
    private long storageQuota;

    public UserStorageDto() {
    }

    public UserStorageDto(String username, long storageCurrent, long storageQuota) {
        this.username = username;
        this.storageCurrent = storageCurrent;
        this.storageQuota = storageQuota;
    }

    public String getUsername() {
        return username;
    }

    public UserStorageDto setUsername(String username) {
        this.username = username;
        return this;
    }

    public long getStorageCurrent() {
        return storageCurrent;
    }

    public UserStorageDto setStorageCurrent(long storageCurrent) {
        this.storageCurrent = storageCurrent;
        return this;
    }

    public long getStorageQuota() {
        return storageQuota;
    }

    public UserStorageDto setStorageQuota(long storageQuota) {
        this.storageQuota = storageQuota;
        return this;
    }
}
