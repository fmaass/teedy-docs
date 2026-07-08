package com.sismics.docs.core.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Semaphore;

/**
 * Unit test of the export resource-exhaustion guard.
 */
public class TestExportGuard {
    @AfterEach
    public void tearDown() {
        System.clearProperty(ExportGuard.MAX_CONCURRENT_PROPERTY);
        System.clearProperty(ExportGuard.MAX_DOCUMENTS_PROPERTY);
        System.clearProperty(ExportGuard.ENABLED_PROPERTY);
    }

    /**
     * Defaults when nothing is configured.
     */
    @Test
    public void testDefaults() {
        Assertions.assertTrue(ExportGuard.isEnabled());
        Assertions.assertEquals(ExportGuard.DEFAULT_MAX_DOCUMENTS, ExportGuard.getMaxDocuments());
        Assertions.assertEquals(ExportGuard.DEFAULT_MAX_CONCURRENT, ExportGuard.getMaxConcurrent());
    }

    /**
     * The feature flag and int settings are read from the system property, and malformed /
     * non-positive values fall back to the default.
     */
    @Test
    public void testSettingsRead() {
        System.setProperty(ExportGuard.ENABLED_PROPERTY, "false");
        Assertions.assertFalse(ExportGuard.isEnabled());

        System.setProperty(ExportGuard.MAX_DOCUMENTS_PROPERTY, "7");
        Assertions.assertEquals(7, ExportGuard.getMaxDocuments());

        System.setProperty(ExportGuard.MAX_DOCUMENTS_PROPERTY, "not-a-number");
        Assertions.assertEquals(ExportGuard.DEFAULT_MAX_DOCUMENTS, ExportGuard.getMaxDocuments());

        System.setProperty(ExportGuard.MAX_DOCUMENTS_PROPERTY, "0");
        Assertions.assertEquals(ExportGuard.DEFAULT_MAX_DOCUMENTS, ExportGuard.getMaxDocuments());
    }

    /**
     * The concurrency cap rejects the (N+1)th simultaneous export.
     */
    @Test
    public void testConcurrencyCap() {
        System.setProperty(ExportGuard.MAX_CONCURRENT_PROPERTY, "2");

        Semaphore a = ExportGuard.tryAcquire();
        Semaphore b = ExportGuard.tryAcquire();
        Assertions.assertNotNull(a);
        Assertions.assertNotNull(b);
        // Cap reached: a third acquisition is refused.
        Assertions.assertNull(ExportGuard.tryAcquire());

        // Releasing frees a slot again.
        ExportGuard.release(a);
        Semaphore c = ExportGuard.tryAcquire();
        Assertions.assertNotNull(c);

        ExportGuard.release(b);
        ExportGuard.release(c);
    }

    /**
     * REGRESSION (cross-model BLOCKING finding): a config change that rebuilds the semaphore
     * while an export is in flight must NOT cause release() to target a different semaphore.
     * The permit is released on the exact instance it was acquired from, so:
     *  - the original semaphore does not leak a permit (its availablePermits returns to full);
     *  - the new (rebuilt) semaphore is not over-released (its availablePermits stays at its
     *    own capacity, never exceeding it).
     */
    @Test
    public void testReleaseTargetsAcquiredInstanceAcrossRebuild() {
        System.setProperty(ExportGuard.MAX_CONCURRENT_PROPERTY, "2");

        // Acquire a permit on the "old" semaphore (in-flight export).
        Semaphore acquired = ExportGuard.tryAcquire();
        Assertions.assertNotNull(acquired);
        Assertions.assertEquals(1, acquired.availablePermits(), "old semaphore should have 1 of 2 left");

        // Config change mid-flight forces a rebuild with a different permit count.
        System.setProperty(ExportGuard.MAX_CONCURRENT_PROPERTY, "3");
        Semaphore rebuilt = ExportGuard.tryAcquire();
        Assertions.assertNotNull(rebuilt);
        // The rebuilt semaphore is a genuinely different instance.
        Assertions.assertNotSame(acquired, rebuilt, "config change should have rebuilt the semaphore");
        Assertions.assertEquals(2, rebuilt.availablePermits(), "new semaphore (cap 3) should have 2 left");

        // Release the ORIGINAL export's permit. It must go back to the instance it came from.
        ExportGuard.release(acquired);

        // No leak on the original: it is back to full capacity (2).
        Assertions.assertEquals(2, acquired.availablePermits(), "original permit must not leak");
        // No over-release on the new one: it still reflects only its own outstanding permit.
        Assertions.assertEquals(2, rebuilt.availablePermits(), "new semaphore must not be over-released");
        Assertions.assertTrue(rebuilt.availablePermits() <= 3, "new semaphore must never exceed its cap");

        // Clean up the rebuilt semaphore's outstanding permit.
        ExportGuard.release(rebuilt);
        Assertions.assertEquals(3, rebuilt.availablePermits());
    }

    /**
     * release(null) is a safe no-op (the caller never acquired a permit).
     */
    @Test
    public void testReleaseNullIsNoop() {
        Assertions.assertDoesNotThrow(() -> ExportGuard.release(null));
    }
}
