package com.sismics.docs.core.dao.dto;

import java.util.List;

/**
 * A single page of audit log rows returned by keyset (cursor) pagination.
 *
 * <p>{@code total} is the un-cursored count of every row matching the scope, so it keeps
 * its historical "all matching rows" meaning regardless of the cursor. Termination is
 * driven by {@code hasMore} (fetched as limit+1), NOT by {@code total}: a cursored count
 * would only count rows below the cursor and mis-terminate the client.
 *
 * @author bgamard
 */
public class AuditLogPage {
    /**
     * The rows of this page, already trimmed to the requested limit.
     */
    private final List<AuditLogDto> logs;

    /**
     * Un-cursored total number of rows matching the scope.
     */
    private final int total;

    /**
     * True when at least one older row exists beyond this page.
     */
    private final boolean hasMore;

    public AuditLogPage(List<AuditLogDto> logs, int total, boolean hasMore) {
        this.logs = logs;
        this.total = total;
        this.hasMore = hasMore;
    }

    public List<AuditLogDto> getLogs() {
        return logs;
    }

    public int getTotal() {
        return total;
    }

    public boolean isHasMore() {
        return hasMore;
    }
}
