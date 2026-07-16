package com.sismics.docs.infrastructure.runtime;

import com.sismics.docs.application.document.Clock;

import java.util.Date;

/**
 * System-time implementation of the {@link Clock} port.
 */
public class DefaultClock implements Clock {

    @Override
    public Date now() {
        return new Date();
    }
}
