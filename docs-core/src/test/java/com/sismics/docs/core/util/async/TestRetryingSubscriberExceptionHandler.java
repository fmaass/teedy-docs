package com.sismics.docs.core.util.async;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests the bounded retry applied to async event listeners.
 * <p>
 * A synchronous Guava {@link EventBus} is used on purpose: it routes subscriber exceptions to the
 * handler and re-invocations happen on the calling thread, so behaviour is fully deterministic. The
 * unit under test (the handler) is exercised through real Guava dispatch and real throwing
 * subscribers — nothing about the handler is mocked.
 *
 * @author teedy
 */
public class TestRetryingSubscriberExceptionHandler {

    /**
     * Event payload carrying a shared invocation counter.
     */
    private static final class CountingEvent {
        final AtomicInteger invocations = new AtomicInteger();
    }

    /**
     * Subscriber that throws on the first {@code failuresBeforeSuccess} invocations, then succeeds.
     */
    private static final class FlakySubscriber {
        private final int failuresBeforeSuccess;
        int successCount;

        FlakySubscriber(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Subscribe
        @AllowConcurrentEvents
        public void on(CountingEvent event) {
            int attempt = event.invocations.incrementAndGet();
            if (attempt <= failuresBeforeSuccess) {
                throw new IllegalStateException("Transient failure on attempt " + attempt);
            }
            successCount++;
        }
    }

    /**
     * Subscriber that always throws.
     */
    private static final class AlwaysFailingSubscriber {
        final AtomicInteger invocations = new AtomicInteger();

        @Subscribe
        @AllowConcurrentEvents
        public void on(CountingEvent event) {
            invocations.incrementAndGet();
            throw new IllegalStateException("Always fails");
        }
    }

    @Test
    public void transientFailureRetriedThenSucceeds() {
        // Fails once, then succeeds — with 2 retries the second attempt (first retry) succeeds.
        EventBus bus = new EventBus(new RetryingSubscriberExceptionHandler(2, 0));
        FlakySubscriber subscriber = new FlakySubscriber(1);
        bus.register(subscriber);

        CountingEvent event = new CountingEvent();
        bus.post(event);

        Assertions.assertEquals(2, event.invocations.get(),
                "Expected 1 initial call + 1 retry invocation");
        Assertions.assertEquals(1, subscriber.successCount,
                "Listener should have succeeded on retry");
    }

    @Test
    public void listenerRetriedExactlyNTimesThenDropped() {
        // Always fails: with retryCount = 2 we expect 1 initial invocation + 2 retries = 3 total,
        // after which the event is dropped (handler logs, does not throw).
        EventBus bus = new EventBus(new RetryingSubscriberExceptionHandler(2, 0));
        AlwaysFailingSubscriber subscriber = new AlwaysFailingSubscriber();
        bus.register(subscriber);

        CountingEvent event = new CountingEvent();
        // Must not propagate — survival semantics.
        Assertions.assertDoesNotThrow(() -> bus.post(event));

        Assertions.assertEquals(3, subscriber.invocations.get(),
                "Expected 1 initial call + 2 retries");
    }

    @Test
    public void executorSurvivesBadEventAndProcessesSubsequentEvents() {
        // A permanently-failing event must not prevent later events from being processed.
        EventBus bus = new EventBus(new RetryingSubscriberExceptionHandler(2, 0));
        AlwaysFailingSubscriber badSubscriber = new AlwaysFailingSubscriber();
        FlakySubscriber goodSubscriber = new FlakySubscriber(0); // never fails
        bus.register(badSubscriber);
        bus.register(goodSubscriber);

        // First event: bad subscriber throws and is retried+dropped, good subscriber succeeds.
        bus.post(new CountingEvent());
        // Second event after the failure — the bus must still deliver.
        bus.post(new CountingEvent());

        Assertions.assertEquals(2, goodSubscriber.successCount,
                "Good subscriber must keep receiving events after a bad event");
    }

    @Test
    public void productionDefaultRetryCountZeroInvokesExactlyOnceThenDrops() {
        // The production default is AppContext.DEFAULT_ASYNC_RETRY_COUNT == 0 (asserted separately in
        // TestAppContextRetryDefault, which can read the package-private constant). Retry 0 MUST NOT
        // re-invoke the subscriber: a single invocation, then log-and-drop. This guarantees a failed
        // non-idempotent listener (e.g. FileDeletedAsyncListener's committed quota decrement) is never
        // re-applied and storage accounting is not corrupted.
        int productionDefault = 0;

        EventBus bus = new EventBus(new RetryingSubscriberExceptionHandler(productionDefault, 0));
        AlwaysFailingSubscriber subscriber = new AlwaysFailingSubscriber();
        bus.register(subscriber);

        Assertions.assertDoesNotThrow(() -> bus.post(new CountingEvent()));

        Assertions.assertEquals(1, subscriber.invocations.get(),
                "With the production default (0 retries) the listener is invoked exactly once, no re-invocation");
    }

    @Test
    public void nonIdempotentSideEffectAppliedOnceUnderDefault() {
        // Models a non-idempotent listener (like FileDeletedAsyncListener): it applies a side effect
        // (increments the counter) and then fails AFTER the side effect. Under the production default
        // (retryCount 0) the side effect must be applied exactly once and never re-applied.
        AtomicInteger sideEffectApplications = new AtomicInteger();
        Object nonIdempotentSubscriber = new Object() {
            @Subscribe
            @AllowConcurrentEvents
            public void on(CountingEvent event) {
                // Side effect committed first (cannot be undone), then a failure occurs.
                sideEffectApplications.incrementAndGet();
                throw new IllegalStateException("Failure after committing side effect");
            }
        };

        EventBus bus = new EventBus(new RetryingSubscriberExceptionHandler(0, 0));
        bus.register(nonIdempotentSubscriber);

        Assertions.assertDoesNotThrow(() -> bus.post(new CountingEvent()));

        Assertions.assertEquals(1, sideEffectApplications.get(),
                "Non-idempotent side effect must be applied exactly once under the safe default");
    }
}
