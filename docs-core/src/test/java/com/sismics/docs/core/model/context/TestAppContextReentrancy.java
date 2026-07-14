package com.sismics.docs.core.model.context;

import com.sismics.BaseTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Proves that {@link AppContext#getInstance()} builds EXACTLY ONE context even when {@code startUp()}
 * calls {@code getInstance()} re-entrantly on the same thread — the real Lucene recovery path (an
 * initial index-open failure re-initializes the index and posts a rebuild-index event via
 * {@code AppContext.getInstance().getAsyncEventBus()}). Without the in-construction guard, the reentrant
 * call (the monitor is reentrant, instance still unpublished) would build a SECOND context, publish it,
 * and then be overwritten by the outer construction — leaking the inner context's services / executors.
 *
 * <p>The Lucene call is simulated with the package-private {@code AppContext.duringStartUp} hook, which
 * {@code startUp()} runs once after the event bus exists (mirroring where Lucene recovery would post).
 * The global singleton is reset via reflection to force a fresh construction and fully restored
 * afterwards so the shared test JVM returns to its pre-test state.</p>
 */
public class TestAppContextReentrancy extends BaseTest {

    @Test
    public void reentrantGetInstanceDuringStartUpBuildsExactlyOneContext() throws Exception {
        AppContext original = AppContext.getInstance();

        Field instanceField = AppContext.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        Field constructingField = AppContext.class.getDeclaredField("constructing");
        constructingField.setAccessible(true);

        AtomicInteger startUps = new AtomicInteger();
        AtomicReference<AppContext> reentrant = new AtomicReference<>();

        try {
            // Force a fresh construction: drop the published reference (do NOT shut the original down —
            // its services keep running and it is restored in the finally) and clear any in-progress handle.
            instanceField.set(null, null);
            constructingField.set(null, null);

            AppContext.duringStartUp = () -> {
                // startUp() runs this once. The first (outer) construction re-enters getInstance() here,
                // exactly as the Lucene recovery path does on the same thread.
                if (startUps.incrementAndGet() == 1) {
                    reentrant.set(AppContext.getInstance());
                }
            };

            AppContext outer = AppContext.getInstance();
            AppContext published = (AppContext) instanceField.get(null);

            Assertions.assertEquals(1, startUps.get(),
                    "exactly one context must be constructed: the re-entrant getInstance() must NOT build a second (which would run startUp again)");
            Assertions.assertSame(outer, reentrant.get(),
                    "the re-entrant getInstance() during construction must return the ONE in-construction context");
            Assertions.assertSame(outer, published,
                    "the single constructed context is the one published as the singleton");
        } finally {
            AppContext.duringStartUp = null;
            // Tear down whatever this test constructed, then restore the original published singleton so
            // the shared JVM is left exactly as it was found.
            AppContext testBuilt = (AppContext) instanceField.get(null);
            constructingField.set(null, null);
            if (testBuilt != null && testBuilt != original) {
                testBuilt.shutDown(); // stops the test-built context's services and nulls instance
            }
            instanceField.set(null, original);
        }
    }
}
