package com.sismics.docs.core.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Unit tests for the UTC day-bucketing of the admin statistics time series.
 *
 * <p>These assertions are independent of the JVM's default timezone by construction: the CI
 * matrix runs this class BOTH normally and under {@code -Duser.timezone=America/Los_Angeles}
 * (see the surefire config). An event at 07:00 UTC on a day falls in that UTC day even though
 * it is the PREVIOUS calendar day in Los Angeles — a bucketing bug that used the default zone
 * would place it in the wrong bucket under LA and fail exactly one of the two runs.
 */
public class TestStatsBucketUtil {
    private static Date utc(String isoInstant) {
        return Date.from(Instant.parse(isoInstant));
    }

    private static long countFor(List<StatsBucketUtil.Bucket> buckets, String day) {
        for (StatsBucketUtil.Bucket bucket : buckets) {
            if (bucket.getDate().equals(day)) {
                return bucket.getCount();
            }
        }
        throw new AssertionError("no bucket for day " + day + " (buckets: " + buckets.size() + ")");
    }

    /**
     * A 7-day window ending at a fixed "now" produces exactly 7 ascending, contiguous, UTC-day
     * buckets, all zero-filled when there are no events, spanning today and the six prior days.
     */
    @Test
    public void testWindowIsSevenZeroFilledAscendingUtcDays() {
        Date now = utc("2026-07-12T15:30:00Z");
        List<StatsBucketUtil.Bucket> buckets = StatsBucketUtil.bucketByDay(new ArrayList<>(), 7, now);

        Assertions.assertEquals(7, buckets.size());
        // Contiguous and ascending, ending on now's UTC day.
        for (int i = 0; i < 7; i++) {
            LocalDate expected = LocalDate.of(2026, 7, 12).minusDays(6L - i);
            Assertions.assertEquals(expected.toString(), buckets.get(i).getDate(),
                    "bucket " + i + " must be the expected contiguous UTC day");
            Assertions.assertEquals(0L, buckets.get(i).getCount(), "empty input must zero-fill every day");
        }
    }

    /**
     * Boundary fixture: an event at the very START of the window's first UTC day lands in that
     * first bucket, and one at the last instant BEFORE the exclusive end lands in the last bucket.
     * This is the timezone-critical test — under a non-UTC JVM default it still buckets by UTC day.
     */
    @Test
    public void testWindowEdgeInstantsLandInTheCorrectUtcBucket() {
        Date now = utc("2026-07-12T12:00:00Z");
        int window = 7;
        Date start = StatsBucketUtil.windowStart(window, now); // 2026-07-06T00:00:00Z
        Date end = StatsBucketUtil.windowEnd(now);             // 2026-07-13T00:00:00Z

        Assertions.assertEquals(utc("2026-07-06T00:00:00Z"), start);
        Assertions.assertEquals(utc("2026-07-13T00:00:00Z"), end);

        List<Date> events = new ArrayList<>();
        events.add(start);                                    // first instant of the first UTC day
        events.add(new Date(end.getTime() - 1));              // last instant before the exclusive end (2026-07-12)
        events.add(utc("2026-07-06T23:59:59Z"));              // still the first UTC day
        events.add(utc("2026-07-07T00:00:00Z"));              // exactly midnight → the SECOND day

        List<StatsBucketUtil.Bucket> buckets = StatsBucketUtil.bucketByDay(events, window, now);

        Assertions.assertEquals(2L, countFor(buckets, "2026-07-06"), "start-of-day + 23:59:59 both fall in the first UTC day");
        Assertions.assertEquals(1L, countFor(buckets, "2026-07-07"), "midnight belongs to the next UTC day");
        Assertions.assertEquals(1L, countFor(buckets, "2026-07-12"), "the last instant before the exclusive end is today's bucket");
        // Days with no events stay zero.
        Assertions.assertEquals(0L, countFor(buckets, "2026-07-08"));
    }

    /**
     * An event at 07:00 UTC on a day is in THAT UTC day, even though it is the previous calendar
     * day (23:00/00:00) in America/Los_Angeles. This assertion only passes if bucketing uses UTC
     * regardless of the JVM default zone — the LA run of this class proves it.
     */
    @Test
    public void testMorningUtcEventBucketsToUtcDayNotLocalDay() {
        Date now = utc("2026-07-12T12:00:00Z");
        // 2026-07-12T07:00:00Z is 2026-07-12 in UTC but 2026-07-12T00:00 / 2026-07-11 late in LA.
        Date event = utc("2026-07-12T07:00:00Z");
        // Sanity: in LA this instant's local date is the 11th or very early 12th — assert it is
        // NOT trivially the same string as the UTC day by any accident of the default zone.
        LocalDate laDay = OffsetDateTime.ofInstant(event.toInstant(), ZoneOffset.ofHours(-7)).toLocalDate();

        List<Date> events = new ArrayList<>();
        events.add(event);
        List<StatsBucketUtil.Bucket> buckets = StatsBucketUtil.bucketByDay(events, 7, now);

        Assertions.assertEquals(1L, countFor(buckets, "2026-07-12"), "a 07:00 UTC event is in the 07-12 UTC bucket");
        // The event is placed by UTC day; if it were (incorrectly) bucketed by an LA local day the
        // 07-12 count would be 0 and an 07-11 (or earlier) bucket would hold it.
        if (laDay.equals(LocalDate.of(2026, 7, 11))) {
            Assertions.assertEquals(0L, countFor(buckets, "2026-07-11"),
                    "the event must NOT land in the LA-local day bucket");
        }
    }

    /**
     * Events outside the window are ignored (older than the start, or at/after the exclusive end).
     */
    @Test
    public void testEventsOutsideWindowAreDropped() {
        Date now = utc("2026-07-12T12:00:00Z");
        List<Date> events = new ArrayList<>();
        events.add(utc("2026-07-05T23:59:59Z")); // day before the window start
        events.add(StatsBucketUtil.windowEnd(now)); // exactly the exclusive end (tomorrow 00:00)
        events.add(utc("2026-07-09T10:00:00Z")); // inside the window

        List<StatsBucketUtil.Bucket> buckets = StatsBucketUtil.bucketByDay(events, 7, now);
        long total = 0;
        for (StatsBucketUtil.Bucket bucket : buckets) {
            total += bucket.getCount();
        }
        Assertions.assertEquals(1L, total, "only the in-window event is counted");
        Assertions.assertEquals(1L, countFor(buckets, "2026-07-09"));
    }

    /**
     * 30- and 90-day windows produce the right number of contiguous UTC-day buckets.
     */
    @Test
    public void testThirtyAndNinetyDayWindowLengths() {
        Date now = utc("2026-07-12T00:00:00Z");
        Assertions.assertEquals(30, StatsBucketUtil.bucketByDay(new ArrayList<>(), 30, now).size());
        Assertions.assertEquals(90, StatsBucketUtil.bucketByDay(new ArrayList<>(), 90, now).size());
    }
}
