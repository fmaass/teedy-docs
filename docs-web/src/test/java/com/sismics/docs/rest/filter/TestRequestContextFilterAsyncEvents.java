package com.sismics.docs.rest.filter;

import com.google.common.eventbus.Subscribe;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.RequestContextFilter;
import jakarta.persistence.EntityManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Guards the request-lifecycle invariant of issue #63: a queued async event fires only if the
 * request transaction actually committed.
 *
 * <p>{@code FileDeletedAsyncEvent} physically deletes file bytes and {@code DocumentDeletedAsyncEvent}
 * removes Lucene entries. If those fired after a rollback, a request whose DB changes were discarded
 * would still destroy data on disk / in the index (data loss). These tests drive the real
 * {@link RequestContextFilter#doFilter} against the real async event bus (synchronous under unit
 * tests) with a spy listener, and assert the queued event is posted on commit and NOT posted on
 * rollback or commit failure.</p>
 */
public class TestRequestContextFilterAsyncEvents {

    /** Marker event used only by this test; unknown to every production listener. */
    public static final class ProbeAsyncEvent {
    }

    /** Synchronous spy listener registered on the real async event bus. */
    public static final class ProbeListener {
        final List<ProbeAsyncEvent> received = new ArrayList<>();

        @Subscribe
        public void on(ProbeAsyncEvent event) {
            received.add(event);
        }
    }

    private ProbeListener listener;

    @BeforeEach
    public void register() {
        // Ensure no stale context/transaction leaks in from a previous test on this thread; the
        // filter manages its own EntityManager and transaction per invocation.
        ThreadLocalContext.cleanup();
        listener = new ProbeListener();
        AppContext.getInstance().getAsyncEventBus().register(listener);
    }

    @AfterEach
    public void unregister() {
        AppContext.getInstance().getAsyncEventBus().unregister(listener);
        ThreadLocalContext.cleanup();
    }

    /**
     * Drives the filter once. The supplied chain body runs inside the filter's transaction (after
     * begin, before commit/rollback); it queues the probe event and may mutate the transaction. The
     * fake response reports {@code httpStatus}, which decides commit (2xx/3xx) vs rollback.
     */
    private void runFilter(int httpStatus, FilterChain chainBody) throws Exception {
        runFilter(httpStatus, false, chainBody);
    }

    /**
     * Drives the filter once. When {@code sendErrorThrows} is set, the fake response throws from
     * {@code sendError(...)} — simulating the error-path failure where the filter, having caught a
     * commit failure, cannot even signal the 500. The finalization must still discard the queued
     * events and clear the thread-local context (issue #63 must not resurface via the error path).
     */
    private void runFilter(int httpStatus, boolean sendErrorThrows, FilterChain chainBody) throws Exception {
        runFilter(httpStatus, sendErrorThrows, false, chainBody);
    }

    /**
     * Drives the filter once, with two independent error-path injections:
     * <ul>
     *   <li>{@code sendErrorThrows}: {@code sendError(...)} throws (models the commit-failure branch
     *       where even signalling the 500 fails).</li>
     *   <li>{@code getStatusThrowsAfterChain}: {@code getStatus()} throws once the chain has run
     *       (models any finalization-phase failure — e.g. a rollback failing — that surfaces after
     *       the request body executed, exercising doFilter's finalization finally rather than the
     *       pre-existing chain-exception handler).</li>
     * </ul>
     * In every injected case the queued events must be discarded and the thread-local context
     * cleared, so nothing leaks into the next request on this thread.
     */
    private void runFilter(int httpStatus, boolean sendErrorThrows, boolean getStatusThrowsAfterChain,
                           FilterChain chainBody) throws Exception {
        boolean[] chainRan = {false};

        ServletRequest request = (ServletRequest) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ServletRequest.class},
                (proxy, method, args) -> defaultReturn(method.getReturnType()));

        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    if ("getStatus".equals(method.getName())) {
                        if (getStatusThrowsAfterChain && chainRan[0]) {
                            // Surfaces during finalization (after the chain), so it flows through
                            // doFilter's finalization finally — not the chain-exception catch.
                            throw new IllegalStateException("injected finalization failure");
                        }
                        return httpStatus;
                    }
                    if (sendErrorThrows && "sendError".equals(method.getName())) {
                        throw new java.io.IOException("injected sendError failure");
                    }
                    // addHeader / sendError / everything else: no-op with a type-correct default.
                    return defaultReturn(method.getReturnType());
                });

        FilterChain wrapped = (req, resp) -> {
            chainBody.doFilter(req, resp);
            chainRan[0] = true;
        };

        new RequestContextFilter().doFilter(request, response, wrapped);
    }

    /** Queues a duplicate-PK User persist so the filter's commit() fails at flush. */
    private static void persistDuplicatePkUser() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        User duplicate = new User();
        duplicate.setId("admin"); // collides with the seeded admin PK -> duplicate-key at commit
        duplicate.setRoleId("admin");
        duplicate.setUsername("probe-duplicate-pk");
        duplicate.setPassword("Test1234");
        duplicate.setEmail("probe@docs.com");
        duplicate.setPrivateKey("probe-private-key");
        duplicate.setStorageQuota(100_000L);
        duplicate.setStorageCurrent(0L);
        duplicate.setCreateDate(new Date());
        em.persist(duplicate);
    }

    private static Object defaultReturn(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class || type == short.class || type == byte.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == float.class) {
            return 0f;
        }
        return 0d;
    }

    @Test
    public void committedRequestFiresQueuedAsyncEvent() throws Exception {
        // 2xx response, transaction commits normally: the queued event must fire (success path
        // unchanged).
        runFilter(200, (req, resp) -> ThreadLocalContext.get().addAsyncEvent(new ProbeAsyncEvent()));

        Assertions.assertEquals(1, listener.received.size(),
                "A committed request must fire its queued async event");
    }

    @Test
    public void rolledBackRequestFiresNoAsyncEvent() throws Exception {
        // Non-2xx/3xx response: the filter rolls the transaction back. The queued event describes a
        // change that never committed, so it must NOT fire. (Mutation guard: if the fire/discard
        // gate is removed and events fire unconditionally, this assertion fails.)
        runFilter(500, (req, resp) -> ThreadLocalContext.get().addAsyncEvent(new ProbeAsyncEvent()));

        Assertions.assertEquals(0, listener.received.size(),
                "A rolled-back request must fire ZERO async events");
    }

    @Test
    public void failedCommitFiresNoAsyncEvent() throws Exception {
        // 2xx response but the commit itself fails. persistDuplicatePkUser() queues a fully-valid
        // User whose primary key ("admin") already exists in the seeded schema: it passes
        // Hibernate's NOT NULL checks, so nothing throws during the chain, but the INSERT hits a
        // duplicate-PK violation when the filter flushes at commit(). The filter's commit catch
        // block then sends 500 and the queued event must NOT fire because nothing reached the
        // database.
        //
        // addAsyncEvent only appends to the queue, so the persist stays pending until the filter's
        // commit.
        runFilter(200, (req, resp) -> {
            persistDuplicatePkUser();
            ThreadLocalContext.get().addAsyncEvent(new ProbeAsyncEvent());
        });

        Assertions.assertEquals(0, listener.received.size(),
                "A request whose commit fails must fire ZERO async events");
    }

    @Test
    public void sendErrorThrowingLeaksNoEventIntoNextRequest() throws Exception {
        // Error-path failure injection: the commit fails (duplicate PK) AND the filter's sendError()
        // itself throws, so finalization cannot complete normally. Without an exception-safe finally,
        // the queued event and the thread-local context would survive; the NEXT request on this
        // thread would then fire the stale event. We assert (a) the failed request fires nothing and
        // (b) a subsequent successful request fires ONLY its own event.
        Assertions.assertThrows(Exception.class, () -> runFilter(200, true, (req, resp) -> {
            persistDuplicatePkUser();
            ThreadLocalContext.get().addAsyncEvent(new ProbeAsyncEvent());
        }), "The injected sendError failure must propagate");

        Assertions.assertEquals(0, listener.received.size(),
                "The failed request must fire ZERO async events even when sendError throws");

        // Second request on the SAME thread: commits normally, queues its own single event.
        runFilter(200, (req, resp) -> ThreadLocalContext.get().addAsyncEvent(new ProbeAsyncEvent()));

        Assertions.assertEquals(1, listener.received.size(),
                "The next request must fire ONLY its own event; no stale event may leak from the "
                        + "failed request");
    }

    @Test
    public void finalizationFailureLeaksNoEventIntoNextRequest() throws Exception {
        // Error-path failure injection during finalization AFTER the request body ran (models e.g.
        // rollback() throwing): getStatus() throws once the chain has executed, so the throw flows
        // through doFilter's finalization finally (not the pre-existing chain-exception handler).
        // The queued event must be discarded and the thread-local context cleared, so a subsequent
        // successful request on the same thread fires only its own event.
        Assertions.assertThrows(Exception.class,
                () -> runFilter(200, false, true,
                        (req, resp) -> ThreadLocalContext.get().addAsyncEvent(new ProbeAsyncEvent())),
                "The injected finalization failure must propagate");

        Assertions.assertEquals(0, listener.received.size(),
                "The failed request must fire ZERO async events when finalization throws");

        // Second request on the SAME thread: commits normally, queues its own single event.
        runFilter(200, (req, resp) -> ThreadLocalContext.get().addAsyncEvent(new ProbeAsyncEvent()));

        Assertions.assertEquals(1, listener.received.size(),
                "The next request must fire ONLY its own event; no stale event may leak from the "
                        + "failed request");
    }
}
