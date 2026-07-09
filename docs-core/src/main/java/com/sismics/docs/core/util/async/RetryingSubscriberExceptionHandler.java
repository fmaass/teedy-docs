package com.sismics.docs.core.util.async;

import com.google.common.eventbus.SubscriberExceptionContext;
import com.google.common.eventbus.SubscriberExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Subscriber exception handler that adds an OPTIONAL bounded retry to async event processing.
 * <p>
 * Guava's {@code AsyncEventBus} catches subscriber exceptions inside the executor task and routes
 * them here, so the executor thread survives regardless. With {@code retryCount == 0} (the production
 * default) this handler simply logs the failure and drops the event — identical to the historical
 * behaviour, with no re-invocation. With {@code retryCount > 0} it re-invokes the failed subscriber
 * method up to that many times with a short backoff before finally logging the drop.
 * <p>
 * <b>WARNING — retry re-invokes subscribers and is UNSAFE for non-idempotent listeners.</b> Enabling
 * retry ({@code retryCount > 0}) re-runs a failed subscriber from the top, which duplicates any side
 * effect the subscriber already committed before the failure. The async listeners in this codebase
 * are NOT guaranteed idempotent. Concretely, {@code FileDeletedAsyncListener} commits the user's
 * storage-quota decrement ({@code updateQuota}) BEFORE {@code FileUtil.delete()} can throw — so if the
 * delete fails and this handler retries, the quota is subtracted again on every retry, corrupting the
 * user's storage accounting. Only set {@code retryCount > 0} in an environment where EVERY registered
 * async listener has been verified idempotent.
 * <p>
 * Survival semantics are preserved regardless of the retry setting: this handler never propagates an
 * exception, so one bad event can never kill the executor thread.
 *
 * @author teedy
 */
public class RetryingSubscriberExceptionHandler implements SubscriberExceptionHandler {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(RetryingSubscriberExceptionHandler.class);

    /**
     * Number of retries attempted after the initial failure.
     */
    private final int retryCount;

    /**
     * Backoff between retries, in milliseconds.
     */
    private final long backoffMs;

    /**
     * @param retryCount Number of retries after the initial failure (0 disables retry)
     * @param backoffMs Backoff between retries, in milliseconds
     */
    public RetryingSubscriberExceptionHandler(int retryCount, long backoffMs) {
        this.retryCount = Math.max(retryCount, 0);
        this.backoffMs = Math.max(backoffMs, 0L);
    }

    @Override
    public void handleException(Throwable exception, SubscriberExceptionContext context) {
        Object subscriber = context.getSubscriber();
        Object event = context.getEvent();
        Method method = context.getSubscriberMethod();

        for (int attempt = 1; attempt <= retryCount; attempt++) {
            if (backoffMs > 0L) {
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    // Preserve interrupt status and stop retrying.
                    Thread.currentThread().interrupt();
                    log.error("Async event handler {} interrupted during retry backoff, dropping event",
                            method, exception);
                    return;
                }
            }

            try {
                method.invoke(subscriber, event);
                if (log.isInfoEnabled()) {
                    log.info("Async event handler {} succeeded on retry {}/{}", method, attempt, retryCount);
                }
                return;
            } catch (InvocationTargetException retryException) {
                exception = retryException.getCause() != null ? retryException.getCause() : retryException;
            } catch (Exception retryException) {
                // Reflection failure (e.g. IllegalAccessException) is not transient, stop retrying.
                log.error("Async event handler {} could not be re-invoked, dropping event", method, retryException);
                return;
            }
        }

        log.error("Async event handler {} failed after {} retries, dropping event", method, retryCount, exception);
    }
}
