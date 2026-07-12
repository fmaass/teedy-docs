package com.sismics.docs.core.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Buckets raw event timestamps into zero-filled UTC calendar-day buckets for the admin
 * statistics dashboard's time series.
 *
 * <p>Bucketing is done in Java (rather than in dialect-specific SQL) so the aggregate queries
 * stay portable across H2 and PostgreSQL. Every stored {@code java.util.Date}/{@code Timestamp}
 * is converted to its UTC calendar day EXPLICITLY through {@link Instant} + {@link ZoneOffset#UTC},
 * so the result is independent of the JVM's default timezone.
 */
public class StatsBucketUtil {
    /**
     * One {@code [date, count]} bucket. {@code date} is an ISO-8601 UTC day ({@code yyyy-MM-dd}).
     */
    public static class Bucket {
        private final String date;
        private final long count;

        public Bucket(String date, long count) {
            this.date = date;
            this.count = count;
        }

        public String getDate() {
            return date;
        }

        public long getCount() {
            return count;
        }
    }

    private StatsBucketUtil() {
    }

    /**
     * Returns the UTC day (midnight, {@code ZoneOffset.UTC}) that starts {@code window} days ago,
     * i.e. the inclusive lower bound of a {@code window}-day series ending at the current UTC day
     * (exclusive of tomorrow). For window=7 the range spans today and the six prior UTC days.
     *
     * @param window Number of UTC days in the series
     * @param now Reference instant (the current time)
     * @return Start instant as a {@code java.util.Date}
     */
    public static Date windowStart(int window, Date now) {
        LocalDate today = utcDay(now);
        LocalDate start = today.minusDays((long) window - 1);
        return Date.from(start.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    /**
     * Returns the exclusive upper bound of a {@code window}-day series: the start of the UTC day
     * AFTER {@code now}'s UTC day (so today's events are included).
     *
     * @param now Reference instant (the current time)
     * @return End instant as a {@code java.util.Date}
     */
    public static Date windowEnd(Date now) {
        LocalDate tomorrow = utcDay(now).plusDays(1);
        return Date.from(tomorrow.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    /**
     * Buckets the given event timestamps into a zero-filled series of {@code window} consecutive
     * UTC days ending at {@code now}'s UTC day. Every day in the window is present exactly once,
     * in ascending date order, even with zero events. Timestamps outside the window are ignored.
     *
     * @param dates Raw event timestamps (any within the window)
     * @param window Number of UTC days
     * @param now Reference instant (the current time)
     * @return One bucket per UTC day in the window, ascending
     */
    public static List<Bucket> bucketByDay(List<Date> dates, int window, Date now) {
        LocalDate today = utcDay(now);
        LocalDate start = today.minusDays((long) window - 1);

        // Seed every day in the window at zero, in ascending order (LinkedHashMap preserves it).
        Map<LocalDate, Long> counts = new LinkedHashMap<>();
        for (int i = 0; i < window; i++) {
            counts.put(start.plusDays(i), 0L);
        }

        // Tally each event into its UTC day, skipping anything outside the seeded window.
        for (Date date : dates) {
            if (date == null) {
                continue;
            }
            LocalDate day = utcDay(date);
            Long current = counts.get(day);
            if (current != null) {
                counts.put(day, current + 1);
            }
        }

        List<Bucket> buckets = new ArrayList<>(counts.size());
        for (Map.Entry<LocalDate, Long> entry : counts.entrySet()) {
            buckets.add(new Bucket(entry.getKey().toString(), entry.getValue()));
        }
        return buckets;
    }

    /**
     * Maps an instant to its UTC calendar day, explicitly through {@link Instant} +
     * {@link ZoneOffset#UTC} so the JVM's default timezone never influences the result.
     */
    private static LocalDate utcDay(Date date) {
        return Instant.ofEpochMilli(date.getTime()).atZone(ZoneOffset.UTC).toLocalDate();
    }
}
