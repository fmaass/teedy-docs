package com.sismics.docs.core.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * Unit tests for the day-bucketing of the admin statistics time series.
 *
 * <p>Bucketing resolves each instant to a calendar day in the SERVER's local time zone (#265), so
 * statistics agree with the date-range and {@code at:} search bounds and with the PDF export date,
 * which all resolve a day the same way. These tests therefore PIN the default time zone rather than
 * relying on the host's: the structural cases pin UTC so their expected day strings are fixed and
 * deterministic on any runner, and {@link #aLateUtcInstantBucketsToTheNextServerLocalDay} pins a
 * UTC+2 zone to prove the boundary actually follows the local zone. The zone is saved and restored
 * around every test so a hostile zone can never leak into a sibling test in this JVM.
 */
public class TestStatsBucketUtil {
    private TimeZone hostTimeZone;

    @BeforeEach
    public void pinUtc() {
        hostTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterEach
    public void restoreZone() {
        TimeZone.setDefault(hostTimeZone);
    }

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
     * A 7-day window ending at a fixed "now" produces exactly 7 ascending, contiguous, zero-filled
     * day buckets spanning today and the six prior days. Zone pinned to UTC, so the day strings are
     * fixed.
     */
    @Test
    public void testWindowIsSevenZeroFilledAscendingDays() {
        Date now = utc("2026-07-12T15:30:00Z");
        List<StatsBucketUtil.Bucket> buckets = StatsBucketUtil.bucketByDay(new ArrayList<>(), 7, now);

        Assertions.assertEquals(7, buckets.size());
        for (int i = 0; i < 7; i++) {
            LocalDate expected = LocalDate.of(2026, 7, 12).minusDays(6L - i);
            Assertions.assertEquals(expected.toString(), buckets.get(i).getDate(),
                    "bucket " + i + " must be the expected contiguous day");
            Assertions.assertEquals(0L, buckets.get(i).getCount(), "empty input must zero-fill every day");
        }
    }

    /**
     * Boundary fixture (zone pinned UTC): an event at the very START of the window's first day lands
     * in that first bucket, and one at the last instant BEFORE the exclusive end lands in the last
     * bucket; midnight belongs to the next day.
     */
    @Test
    public void testWindowEdgeInstantsLandInTheCorrectBucket() {
        Date now = utc("2026-07-12T12:00:00Z");
        int window = 7;
        Date start = StatsBucketUtil.windowStart(window, now); // 2026-07-06T00:00:00Z (UTC pinned)
        Date end = StatsBucketUtil.windowEnd(now);             // 2026-07-13T00:00:00Z

        Assertions.assertEquals(utc("2026-07-06T00:00:00Z"), start);
        Assertions.assertEquals(utc("2026-07-13T00:00:00Z"), end);

        List<Date> events = new ArrayList<>();
        events.add(start);                                    // first instant of the first day
        events.add(new Date(end.getTime() - 1));              // last instant before the exclusive end (2026-07-12)
        events.add(utc("2026-07-06T23:59:59Z"));              // still the first day
        events.add(utc("2026-07-07T00:00:00Z"));              // exactly midnight → the SECOND day

        List<StatsBucketUtil.Bucket> buckets = StatsBucketUtil.bucketByDay(events, window, now);

        Assertions.assertEquals(2L, countFor(buckets, "2026-07-06"), "start-of-day + 23:59:59 both fall in the first day");
        Assertions.assertEquals(1L, countFor(buckets, "2026-07-07"), "midnight belongs to the next day");
        Assertions.assertEquals(1L, countFor(buckets, "2026-07-12"), "the last instant before the exclusive end is today's bucket");
        Assertions.assertEquals(0L, countFor(buckets, "2026-07-08"));
    }

    /**
     * The zone-critical case: under a UTC+2 host, an instant late in the UTC day (23:30 UTC) is
     * ALREADY the next calendar day locally (01:30), so statistics bucket it under the local day —
     * the same day the date-range/{@code at:} search and the PDF export resolve it to (#265). Before
     * the local-zone change the code bucketed by UTC day and this fails: the event lands under the
     * previous day.
     */
    @Test
    public void aLateUtcInstantBucketsToTheNextServerLocalDay() {
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT-2")); // Etc/GMT-2 == UTC+2, no DST
        Date now = utc("2026-08-04T12:00:00Z");                 // local 2026-08-04 14:00 → today = Aug 4 local
        Date event = utc("2026-08-03T23:30:00Z");               // local 2026-08-04 01:30 → the 4th, not the 3rd

        List<StatsBucketUtil.Bucket> buckets = StatsBucketUtil.bucketByDay(List.of(event), 7, now);

        Assertions.assertEquals(1L, countFor(buckets, "2026-08-04"),
                "a 23:30 UTC event is the NEXT local day under UTC+2 and must bucket there");
        Assertions.assertEquals(0L, countFor(buckets, "2026-08-03"),
                "it must NOT bucket to the UTC day");
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
     * 30- and 90-day windows produce the right number of contiguous day buckets.
     */
    @Test
    public void testThirtyAndNinetyDayWindowLengths() {
        Date now = utc("2026-07-12T00:00:00Z");
        Assertions.assertEquals(30, StatsBucketUtil.bucketByDay(new ArrayList<>(), 30, now).size());
        Assertions.assertEquals(90, StatsBucketUtil.bucketByDay(new ArrayList<>(), 90, now).size());
    }
}
