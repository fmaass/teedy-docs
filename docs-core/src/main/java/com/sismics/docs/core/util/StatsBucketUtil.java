package com.sismics.docs.core.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Buckets raw event timestamps into zero-filled calendar-day buckets, in the SERVER's local time
 * zone, for the admin statistics dashboard's time series.
 *
 * <p>Bucketing is done in Java (rather than in dialect-specific SQL) so the aggregate queries stay
 * portable across H2 and PostgreSQL. Every stored {@code java.util.Date}/{@code Timestamp} is
 * converted to its calendar day through {@link Instant} + {@link ZoneId#systemDefault()} — the
 * server's local zone — so a document created near midnight is counted under the same calendar day
 * that the date-range/{@code at:} search bounds and the PDF export date resolve it to (#265). The
 * accepted trade is that the series now follows the host's zone rather than being host-independent.
 */
public class StatsBucketUtil {
    /**
     * One {@code [date, count]} bucket. {@code date} is an ISO-8601 local day ({@code yyyy-MM-dd}).
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
     * Returns the local day (midnight, {@link ZoneId#systemDefault()}) that starts {@code window}
     * days ago, i.e. the inclusive lower bound of a {@code window}-day series ending at the current
     * local day (exclusive of tomorrow). For window=7 the range spans today and the six prior days.
     *
     * @param window Number of local days in the series
     * @param now Reference instant (the current time)
     * @return Start instant as a {@code java.util.Date}
     */
    public static Date windowStart(int window, Date now) {
        LocalDate today = localDay(now);
        LocalDate start = today.minusDays((long) window - 1);
        return Date.from(start.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    /**
     * Returns the exclusive upper bound of a {@code window}-day series: the start of the local day
     * AFTER {@code now}'s local day (so today's events are included).
     *
     * @param now Reference instant (the current time)
     * @return End instant as a {@code java.util.Date}
     */
    public static Date windowEnd(Date now) {
        LocalDate tomorrow = localDay(now).plusDays(1);
        return Date.from(tomorrow.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    /**
     * Buckets the given event timestamps into a zero-filled series of {@code window} consecutive
     * local days ending at {@code now}'s local day. Every day in the window is present exactly once,
     * in ascending date order, even with zero events. Timestamps outside the window are ignored.
     *
     * @param dates Raw event timestamps (any within the window)
     * @param window Number of local days
     * @param now Reference instant (the current time)
     * @return One bucket per local day in the window, ascending
     */
    public static List<Bucket> bucketByDay(List<Date> dates, int window, Date now) {
        LocalDate today = localDay(now);
        LocalDate start = today.minusDays((long) window - 1);

        // Seed every day in the window at zero, in ascending order (LinkedHashMap preserves it).
        Map<LocalDate, Long> counts = new LinkedHashMap<>();
        for (int i = 0; i < window; i++) {
            counts.put(start.plusDays(i), 0L);
        }

        // Tally each event into its local day, skipping anything outside the seeded window.
        for (Date date : dates) {
            if (date == null) {
                continue;
            }
            LocalDate day = localDay(date);
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
     * Maps an instant to its calendar day in the server's default zone ({@link ZoneId#systemDefault()}),
     * matching how the date-range/{@code at:} search bounds and the PDF export date resolve a day (#265).
     */
    private static LocalDate localDay(Date date) {
        return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
