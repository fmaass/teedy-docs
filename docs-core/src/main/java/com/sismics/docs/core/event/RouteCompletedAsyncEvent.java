package com.sismics.docs.core.event;

import com.google.common.base.MoreObjects;

/**
 * Event fired when a route reaches its DONE terminal status (all steps approved/validated). Drives
 * the ROUTE_COMPLETED webhook. A terminal rejection does NOT fire this event.
 *
 * @author bgamard
 */
public class RouteCompletedAsyncEvent extends UserEvent {
    /**
     * Document ID.
     */
    private String documentId;

    /**
     * Route ID.
     */
    private String routeId;

    public String getDocumentId() {
        return documentId;
    }

    public RouteCompletedAsyncEvent setDocumentId(String documentId) {
        this.documentId = documentId;
        return this;
    }

    public String getRouteId() {
        return routeId;
    }

    public RouteCompletedAsyncEvent setRouteId(String routeId) {
        this.routeId = routeId;
        return this;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("documentId", documentId)
                .add("routeId", routeId)
                .toString();
    }
}
