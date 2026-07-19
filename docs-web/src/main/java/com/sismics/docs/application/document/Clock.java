package com.sismics.docs.application.document;

import java.util.Date;

/**
 * Time port. The document update uses it for the "now" default applied when an empty
 * {@code create_date} is submitted.
 */
public interface Clock {

    /**
     * @return The current instant
     */
    Date now();
}
