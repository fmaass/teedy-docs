package com.sismics.docs.core.event;

import com.google.common.base.MoreObjects;

/**
 * Event fired when a route step is transitioned (validate/approve/reject). Drives the
 * ROUTE_STEP_TRANSITIONED webhook. Fires on every validate call, including a terminal rejection.
 *
 * @author bgamard
 */
public class RouteStepTransitionedAsyncEvent extends UserEvent {
    /**
     * Document ID.
     */
    private String documentId;

    /**
     * Route ID.
     */
    private String routeId;

    /**
     * Name of the step that was transitioned.
     */
    private String stepName;

    /**
     * Transition applied (APPROVED / REJECTED / VALIDATED).
     */
    private String transition;

    public String getDocumentId() {
        return documentId;
    }

    public RouteStepTransitionedAsyncEvent setDocumentId(String documentId) {
        this.documentId = documentId;
        return this;
    }

    public String getRouteId() {
        return routeId;
    }

    public RouteStepTransitionedAsyncEvent setRouteId(String routeId) {
        this.routeId = routeId;
        return this;
    }

    public String getStepName() {
        return stepName;
    }

    public RouteStepTransitionedAsyncEvent setStepName(String stepName) {
        this.stepName = stepName;
        return this;
    }

    public String getTransition() {
        return transition;
    }

    public RouteStepTransitionedAsyncEvent setTransition(String transition) {
        this.transition = transition;
        return this;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("documentId", documentId)
                .add("routeId", routeId)
                .add("stepName", stepName)
                .add("transition", transition)
                .toString();
    }
}
