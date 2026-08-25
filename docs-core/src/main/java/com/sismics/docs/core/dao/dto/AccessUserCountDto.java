package com.sismics.docs.core.dao.dto;

/**
 * How many times one named user accessed one target (#300). The per-user breakdown row of the
 * administrator view; never emitted to a non-administrator.
 */
public class AccessUserCountDto {
    private final String username;

    private final long count;

    public AccessUserCountDto(String username, long count) {
        this.username = username;
        this.count = count;
    }

    public String getUsername() {
        return username;
    }

    public long getCount() {
        return count;
    }
}
