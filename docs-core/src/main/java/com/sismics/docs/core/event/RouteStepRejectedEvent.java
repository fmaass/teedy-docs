package com.sismics.docs.core.event;

import com.google.common.base.MoreObjects;
import com.sismics.docs.core.dao.dto.UserDto;
import com.sismics.docs.core.model.jpa.Document;

/**
 * Event fired when a route step is rejected, halting the route. Carries the rejection notice to
 * the route initiator (the recipient user).
 *
 * @author bgamard
 */
public class RouteStepRejectedEvent {
    /**
     * Recipient user (the route initiator).
     */
    private UserDto user;

    /**
     * Document linked to the route.
     */
    private Document document;

    /**
     * Name of the rejected step.
     */
    private String stepName;

    /**
     * Rejection comment (may be null).
     */
    private String comment;

    public UserDto getUser() {
        return user;
    }

    public RouteStepRejectedEvent setUser(UserDto user) {
        this.user = user;
        return this;
    }

    public Document getDocument() {
        return document;
    }

    public RouteStepRejectedEvent setDocument(Document document) {
        this.document = document;
        return this;
    }

    public String getStepName() {
        return stepName;
    }

    public RouteStepRejectedEvent setStepName(String stepName) {
        this.stepName = stepName;
        return this;
    }

    public String getComment() {
        return comment;
    }

    public RouteStepRejectedEvent setComment(String comment) {
        this.comment = comment;
        return this;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("user", user)
                .add("document", document)
                .add("stepName", stepName)
                .toString();
    }
}
