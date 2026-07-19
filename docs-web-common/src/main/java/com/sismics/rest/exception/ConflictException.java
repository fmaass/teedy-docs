package com.sismics.rest.exception;

import jakarta.json.Json;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jersey exception encapsulating a state conflict the client can resolve and retry (HTTP 409). Used when a
 * request raced a concurrent write and lost — e.g. uploading a new file version on top of a base that is no
 * longer the latest version. The client should reload the current state and retry.
 *
 * @author bgamard
 */
public class ConflictException extends WebApplicationException {
    /**
     * Serial UID.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(ConflictException.class);

    /**
     * Constructor of ConflictException.
     *
     * @param type Error type (e.g. VersionConflict)
     * @param message Human readable error message
     * @param e Inner exception
     */
    public ConflictException(String type, String message, Exception e) {
        this(type, message);
        log.error(type + ": " + message, e);
    }

    /**
     * Constructor of ConflictException.
     *
     * @param type Error type (e.g. VersionConflict)
     * @param message Human readable error message
     */
    public ConflictException(String type, String message) {
        super(Response.status(Status.CONFLICT).entity(Json.createObjectBuilder()
            .add("type", type)
            .add("message", message).build()).build());
    }
}
