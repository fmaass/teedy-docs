package com.sismics.util;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * HTTP request utilities.
 *
 * @author jtremeaux
 */
public class HttpUtil {
    /**
     * Format of the expires header: the IMF-fixdate form (RFC 7231), which is the only date form an
     * HTTP sender may emit, with a fixed-width day-of-month and the literal "GMT". The zone is
     * pinned to UTC and the locale to English so the value never follows the host's timezone or
     * locale; HTTP-date day and month names are English by specification.
     *
     * <p>A {@link DateTimeFormatter} is immutable and safe to share between threads. Its predecessor
     * here, a static {@link java.text.SimpleDateFormat}, carries mutable calendar state: concurrent
     * file, thumbnail and theme requests corrupted it, which threw out of the formatter and turned
     * those responses into HTTP 500s.</p>
     */
    private static final DateTimeFormatter EXPIRES_FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    /**
     * Build an Expires HTTP header.
     *
     * @param futureTime Expire interval
     * @return Formatted header value
     */
    public static String buildExpiresHeader(long futureTime) {
        return buildExpiresHeader(futureTime, System.currentTimeMillis());
    }

    /**
     * Build an Expires HTTP header relative to a supplied instant, so the rendering can be pinned to
     * a known point in time instead of the wall clock.
     *
     * @param futureTime Expire interval
     * @param nowMillis Current time, in milliseconds since the epoch
     * @return Formatted header value
     */
    static String buildExpiresHeader(long futureTime, long nowMillis) {
        return EXPIRES_FORMAT.format(Instant.ofEpochMilli(nowMillis + futureTime));
    }
}
