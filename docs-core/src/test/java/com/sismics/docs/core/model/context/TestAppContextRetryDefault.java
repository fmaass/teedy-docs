package com.sismics.docs.core.model.context;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Pins the production default for async listener retry to OFF (0).
 * <p>
 * The registered async listeners are idempotent (FileDeletedAsyncListener no longer touches the quota;
 * reclamation is synchronous in the delete transaction), so retry is safe to enable. The default is
 * nonetheless kept at 0 (log-and-drop) as the conservative posture — retry is turned on deliberately.
 * This test lives in the same package as {@link AppContext} so it can read the package-private
 * constant directly.
 *
 * @author teedy
 */
public class TestAppContextRetryDefault {

    @Test
    public void asyncRetryDefaultsToZeroForSafety() {
        Assertions.assertEquals(0, AppContext.DEFAULT_ASYNC_RETRY_COUNT,
                "Async listener retry defaults to 0 (conservative opt-in); listeners are idempotent so enabling it is safe");
    }

    @Test
    public void asyncQueueDefaultsAreBounded() {
        Assertions.assertTrue(AppContext.DEFAULT_ASYNC_QUEUE_CAPACITY > 0,
                "Async work queue must have a positive bounded default capacity");
    }
}
