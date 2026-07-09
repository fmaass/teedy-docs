package com.sismics.docs.core.model.context;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Pins the production default for async listener retry to OFF (0).
 * <p>
 * Async listeners in this codebase are not guaranteed idempotent (e.g. FileDeletedAsyncListener
 * commits a quota decrement before FileUtil.delete() can throw), so auto-retrying on exception would
 * double-apply side effects. The default MUST stay 0 (log-and-drop). This test lives in the same
 * package as {@link AppContext} so it can read the package-private constant directly.
 *
 * @author teedy
 */
public class TestAppContextRetryDefault {

    @Test
    public void asyncRetryDefaultsToZeroForSafety() {
        Assertions.assertEquals(0, AppContext.DEFAULT_ASYNC_RETRY_COUNT,
                "Async listener retry MUST default to 0 (safe log-and-drop) because listeners are not idempotent");
    }

    @Test
    public void asyncQueueDefaultsAreBounded() {
        Assertions.assertTrue(AppContext.DEFAULT_ASYNC_QUEUE_CAPACITY > 0,
                "Async work queue must have a positive bounded default capacity");
    }
}
