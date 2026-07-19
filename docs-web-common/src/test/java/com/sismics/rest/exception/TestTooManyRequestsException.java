package com.sismics.rest.exception;

import jakarta.json.JsonObject;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The concurrency-rejection exception must carry a real HTTP 429 and a stable typed error body, so a client
 * can distinguish a transient "retry shortly" from a 400 (bad request) or a 409 (stale-base conflict).
 */
public class TestTooManyRequestsException {

    @Test
    public void carriesStatus429AndTypedBody() {
        TooManyRequestsException exception =
                new TooManyRequestsException("TooManyRequests", "Retry shortly");
        Response response = exception.getResponse();

        Assertions.assertEquals(429, response.getStatus(),
                "the exception must map to a real HTTP 429");

        JsonObject body = (JsonObject) response.getEntity();
        Assertions.assertEquals("TooManyRequests", body.getString("type"),
                "the response must carry a stable error type token");
        Assertions.assertEquals("Retry shortly", body.getString("message"));
    }
}
