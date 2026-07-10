package com.sismics.docs.core.event;

import com.google.common.base.MoreObjects;

/**
 * Event fired when a route is started on a document. Drives the ROUTE_STARTED webhook.
 *
 * @author bgamard
 */
public class RouteStartedAsyncEvent extends UserEvent {
    /**
     * Document ID.
     */
    private String documentId;

    /**
     * Route ID.
     */
    private String routeId;

    /**
     * Name of the first (now current) route step.
     */
    private String stepName;

    public String getDocumentId() {
        return documentId;
    }

    public RouteStartedAsyncEvent setDocumentId(String documentId) {
        this.documentId = documentId;
        return this;
    }

    public String getRouteId() {
        return routeId;
    }

    public RouteStartedAsyncEvent setRouteId(String routeId) {
        this.routeId = routeId;
        return this;
    }

    public String getStepName() {
        return stepName;
    }

    public RouteStartedAsyncEvent setStepName(String stepName) {
        this.stepName = stepName;
        return this;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("documentId", documentId)
                .add("routeId", routeId)
                .add("stepName", stepName)
                .toString();
    }
}
