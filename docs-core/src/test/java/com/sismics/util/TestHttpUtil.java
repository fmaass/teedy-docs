package com.sismics.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Unit test of the Expires header builder.
 */
public class TestHttpUtil {

    /** The interval FileResource passes for files, thumbnails and previews. */
    private static final long FILE_INTERVAL = 3_600_000L * 24L * 365L;

    /** The interval ThemeResource passes for theme images. */
    private static final long THEME_INTERVAL = 3_600_000L * 24L * 15L;

    private static final int THREADS = 8;

    private static final int ITERATIONS = 500;

    /**
     * A parser that accepts any RFC 1123 date, so the concurrency assertions below are about
     * thread safety alone and not about which of the two spellings of the offset is emitted.
     */
    private static final DateTimeFormatter PARSER = DateTimeFormatter.RFC_1123_DATE_TIME;

    /**
     * The RFC 7231 IMF-fixdate grammar, spelled out as a literal so a change of pattern cannot be
     * validated by the very formatter that produced it: an English three-letter day name, a
     * two-digit day-of-month, an English three-letter month, a four-digit year, a two-digit
     * wall clock and the literal GMT.
     */
    private static final String IMF_FIXDATE =
            "(Mon|Tue|Wed|Thu|Fri|Sat|Sun), \\d{2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)"
                    + " \\d{4} \\d{2}:\\d{2}:\\d{2} GMT";

    /**
     * Expected renderings, as {interval, current time in millis, header}. The instants were rendered
     * independently of the JDK (GNU date, {@code date -u -d @EPOCH}) and cover the RFC 7231
     * example, both production intervals, a day-of-month and an hour that need zero-padding, and
     * midnight on a year boundary.
     */
    private static final Object[][] EXPECTED_HEADERS = {
            {0L, 784_111_777_000L, "Sun, 06 Nov 1994 08:49:37 GMT"},
            {FILE_INTERVAL, 1_767_223_800_000L - FILE_INTERVAL, "Wed, 31 Dec 2025 23:30:00 GMT"},
            {THEME_INTERVAL, 1_767_225_600_000L - THEME_INTERVAL, "Thu, 01 Jan 2026 00:00:00 GMT"},
    };

    /**
     * The exact bytes of the header. RFC 7231 obliges a sender to use IMF-fixdate, so the value ends
     * in the literal GMT and carries the UTC wall clock; a numeric offset, a host-local clock or a
     * one-digit day-of-month would all be rejected here.
     */
    @Test
    public void testHeaderIsImfFixdateInGmt() {
        for (Object[] expected : EXPECTED_HEADERS) {
            long interval = (Long) expected[0];
            long now = (Long) expected[1];
            Assertions.assertEquals(expected[2], HttpUtil.buildExpiresHeader(interval, now),
                    "header for interval " + interval + " at " + now);
        }

        // The one-argument method the resources call takes the same route off the system clock.
        String live = HttpUtil.buildExpiresHeader(FILE_INTERVAL);
        Assertions.assertTrue(live.matches(IMF_FIXDATE), "not an IMF-fixdate: " + live);
    }

    /**
     * The header must be identical on every host. The class is re-initialised under a non-UTC
     * default timezone and a locale with its own calendar, digits and month names, so a formatter
     * that picked either of them up at construction time is caught here rather than only on a
     * machine that happens to be configured that way.
     */
    @Test
    public void testHeaderIgnoresHostLocaleAndTimeZone() throws Exception {
        Locale locale = Locale.getDefault();
        TimeZone timeZone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-ca-buddhist-nu-thai"));
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));

            URL classes = HttpUtil.class.getProtectionDomain().getCodeSource().getLocation();
            Assertions.assertNotNull(classes, "the class location is needed to re-initialise it");
            try (URLClassLoader loader = new URLClassLoader(new URL[]{classes}, null)) {
                Class<?> reloaded = loader.loadClass(HttpUtil.class.getName());
                Assertions.assertNotSame(HttpUtil.class, reloaded,
                        "the class must be re-initialised for this test to prove anything");
                Method build = reloaded.getDeclaredMethod("buildExpiresHeader", long.class, long.class);
                build.setAccessible(true);
                for (Object[] expected : EXPECTED_HEADERS) {
                    Assertions.assertEquals(expected[2], build.invoke(null, expected[0], expected[1]),
                            "header for interval " + expected[0] + " at " + expected[1]);
                }
            }
        } finally {
            TimeZone.setDefault(timeZone);
            Locale.setDefault(locale);
        }
    }

    /**
     * Half the threads request the file interval and half the theme interval, which is what a
     * browser produces when the document list pulls its thumbnails while the theme images load.
     * The two intervals land on different dates, so a formatter whose state is shared across
     * threads either throws or returns one thread's date to another.
     */
    @Test
    public void testConcurrentBuildIsThreadSafe() throws Exception {
        List<Throwable> thrown = new CopyOnWriteArrayList<>();
        List<List<String>> headers = new ArrayList<>();
        long[] intervals = new long[THREADS];
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREADS);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);

        long before = System.currentTimeMillis();
        try {
            for (int i = 0; i < THREADS; i++) {
                List<String> collected = new ArrayList<>(ITERATIONS);
                headers.add(collected);
                intervals[i] = i % 2 == 0 ? FILE_INTERVAL : THEME_INTERVAL;
                final long interval = intervals[i];
                executor.execute(() -> {
                    try {
                        start.await();
                        for (int call = 0; call < ITERATIONS; call++) {
                            collected.add(HttpUtil.buildExpiresHeader(interval));
                        }
                    } catch (Throwable e) {
                        thrown.add(e);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            start.countDown();
            Assertions.assertTrue(finished.await(120, TimeUnit.SECONDS), "the workers did not finish");
        } finally {
            executor.shutdownNow();
        }
        long after = System.currentTimeMillis();

        if (!thrown.isEmpty()) {
            Assertions.fail(thrown.size() + " of " + THREADS + " threads threw while building the header; first was "
                    + thrown.get(0), thrown.get(0));
        }

        // Silent corruption returns a plausible but wrong date instead of throwing, so every value
        // is read back: the header must encode the caller's own interval, not another thread's.
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (int i = 0; i < THREADS; i++) {
            long earliest = before + intervals[i] - 1000L;
            long latest = after + intervals[i];
            for (String header : headers.get(i)) {
                checked++;
                try {
                    long expires = ZonedDateTime.parse(header, PARSER).toInstant().toEpochMilli();
                    if (expires < earliest || expires > latest) {
                        wrong.add(header + " (interval " + intervals[i] + ")");
                    }
                } catch (RuntimeException e) {
                    wrong.add(header + " (" + e + ")");
                }
            }
        }
        Assertions.assertEquals(THREADS * ITERATIONS, checked, "not every call produced a header");
        Assertions.assertTrue(wrong.isEmpty(),
                wrong.size() + " headers did not encode their own expiry; first were "
                        + wrong.subList(0, Math.min(5, wrong.size())));
    }
}
