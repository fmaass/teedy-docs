package com.sismics.docs.core.dao.dto;

import java.util.List;

/**
 * One row of the administrator's most-accessed-documents ranking (#300): the document, how often it
 * was opened in total, and by whom.
 */
public class DocumentAccessStatsDto {
    private final String id;

    private final String title;

    private final long total;

    private final List<AccessUserCountDto> userCounts;

    public DocumentAccessStatsDto(String id, String title, long total, List<AccessUserCountDto> userCounts) {
        this.id = id;
        this.title = title;
        this.total = total;
        this.userCounts = userCounts;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public long getTotal() {
        return total;
    }

    public List<AccessUserCountDto> getUserCounts() {
        return userCounts;
    }
}
