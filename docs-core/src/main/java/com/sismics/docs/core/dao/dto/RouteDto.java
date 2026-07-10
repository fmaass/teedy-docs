package com.sismics.docs.core.dao.dto;

/**
 * Route DTO.
 *
 * @author bgamard
 */
public class RouteDto {
    /**
     * Route ID.
     */
    private String id;

    /**
     * Name.
     */
    private String name;

    /**
     * Status.
     */
    private String status;

    /**
     * Initiator user ID.
     */
    private String userId;

    /**
     * Creation date.
     */
    private Long createTimestamp;

    /**
     * End date (null while the route is still ACTIVE).
     */
    private Long endTimestamp;

    /**
     * Initiator's username (nullable if the initiator user no longer exists).
     */
    private String initiatorUsername;

    public String getId() {
        return id;
    }

    public RouteDto setId(String id) {
        this.id = id;
        return this;
    }

    public String getName() {
        return name;
    }

    public RouteDto setName(String name) {
        this.name = name;
        return this;
    }

    public String getStatus() {
        return status;
    }

    public RouteDto setStatus(String status) {
        this.status = status;
        return this;
    }

    public String getUserId() {
        return userId;
    }

    public RouteDto setUserId(String userId) {
        this.userId = userId;
        return this;
    }

    public Long getCreateTimestamp() {
        return createTimestamp;
    }

    public RouteDto setCreateTimestamp(Long createTimestamp) {
        this.createTimestamp = createTimestamp;
        return this;
    }

    public Long getEndTimestamp() {
        return endTimestamp;
    }

    public RouteDto setEndTimestamp(Long endTimestamp) {
        this.endTimestamp = endTimestamp;
        return this;
    }

    public String getInitiatorUsername() {
        return initiatorUsername;
    }

    public RouteDto setInitiatorUsername(String initiatorUsername) {
        this.initiatorUsername = initiatorUsername;
        return this;
    }
}
