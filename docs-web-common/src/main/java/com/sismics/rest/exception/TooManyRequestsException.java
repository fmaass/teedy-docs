package com.sismics.rest.exception;

import jakarta.json.Json;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jersey exception encapsulating a request rejected because a concurrency ceiling is saturated (HTTP 429).
 * Unlike a 409 (a state conflict the client resolves by reloading), a 429 is transient: the request was
 * refused rather than queued, and the same request should simply be retried shortly. Used by the page-
 * operations endpoint when the per-file or global operation slot is unavailable.
 *
 * @author bgamard
 */
public class TooManyRequestsException extends WebApplicationException {
    /**
     * Serial UID.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(TooManyRequestsException.class);

    /**
     * HTTP status code 429 Too Many Requests (not present in the JAX-RS {@link Response.Status} enum).
     */
    private static final int TOO_MANY_REQUESTS = 429;

    /**
     * Constructor of TooManyRequestsException.
     *
     * @param type Error type (e.g. TooManyRequests)
     * @param message Human readable error message
     * @param e Inner exception
     */
    public TooManyRequestsException(String type, String message, Exception e) {
        this(type, message);
        log.error(type + ": " + message, e);
    }

    /**
     * Constructor of TooManyRequestsException.
     *
     * @param type Error type (e.g. TooManyRequests)
     * @param message Human readable error message
     */
    public TooManyRequestsException(String type, String message) {
        super(Response.status(TOO_MANY_REQUESTS).entity(Json.createObjectBuilder()
            .add("type", type)
            .add("message", message).build()).build());
    }
}
