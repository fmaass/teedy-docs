package com.sismics.docs.core.model.context;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.eventbus.AsyncEventBus;
import com.google.common.eventbus.EventBus;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.listener.async.*;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.service.FileService;
import com.sismics.docs.core.service.FileSizeService;
import com.sismics.docs.core.service.InboxService;
import com.sismics.docs.core.service.OidcStatePurgeService;
import com.sismics.docs.core.service.TrashPurgeService;
import com.sismics.docs.core.util.PdfUtil;
import com.sismics.docs.core.util.async.RetryingSubscriberExceptionHandler;
import com.sismics.docs.core.util.indexing.IndexingHandler;
import com.sismics.util.ClasspathScanner;
import com.sismics.util.EnvironmentUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Global application context.
 *
 * @author jtremeaux
 */
public class AppContext {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(AppContext.class);

    /**
     * Configuration for the bounded async work queue and listener retry.
     */
    static final String ASYNC_QUEUE_CAPACITY_PROPERTY = "docs.async_queue_capacity";
    static final String ASYNC_QUEUE_CAPACITY_ENV = "DOCS_ASYNC_QUEUE_CAPACITY";
    static final int DEFAULT_ASYNC_QUEUE_CAPACITY = 1000;
    /**
     * Async listener retry count. Default OFF (0) as the conservative posture, though the registered
     * listeners are idempotent so retry is safe to enable. Enabling retry (count &gt; 0) makes
     * {@link RetryingSubscriberExceptionHandler} re-invoke a failed async subscriber; that is safe
     * because every listener is idempotent (quota reclamation now happens synchronously in the delete
     * transaction, not in the async listener). See that class's Javadoc for the authoritative
     * requirement and the per-listener state.
     */
    static final String ASYNC_RETRY_COUNT_PROPERTY = "docs.async_retry_count";
    static final String ASYNC_RETRY_COUNT_ENV = "DOCS_ASYNC_RETRY_COUNT";
    static final int DEFAULT_ASYNC_RETRY_COUNT = 0;
    static final String ASYNC_RETRY_BACKOFF_MS_PROPERTY = "docs.async_retry_backoff_ms";
    static final String ASYNC_RETRY_BACKOFF_MS_ENV = "DOCS_ASYNC_RETRY_BACKOFF_MS";
    static final int DEFAULT_ASYNC_RETRY_BACKOFF_MS = 200;

    /**
     * Singleton instance.
     */
    private static AppContext instance;

    /**
     * Generic asynchronous event bus.
     */
    private EventBus asyncEventBus;

    /**
     * Asynchronous bus for email sending.
     */
    private EventBus mailEventBus;

    /**
     * Indexing handler.
     */
    private IndexingHandler indexingHandler;

    /**
     * Inbox scanning service.
     */
    private InboxService inboxService;

    /**
     * File service.
     */
    private FileService fileService;

    /**
     * File size service.
     */
    private FileSizeService fileSizeService;

    /**
     * Trash purge service.
     */
    private TrashPurgeService trashPurgeService;

    /**
     * OIDC state purge service.
     */
    private OidcStatePurgeService oidcStatePurgeService;

    /**
     * Asynchronous executors.
     */
    private List<ThreadPoolExecutor> asyncExecutorList;

    /**
     * Guard so the "retry enabled" idempotency warning is logged once per context start.
     */
    private boolean asyncRetryWarningLogged;

    /**
     * Start the application context.
     */
    private void startUp() {
        resetEventBus();

        // Start indexing handler
        try {
            List<Class<? extends IndexingHandler>> indexingHandlerList = Lists.newArrayList(
                    new ClasspathScanner<IndexingHandler>().findClasses(IndexingHandler.class, "com.sismics.docs.core.util.indexing"));
            for (Class<? extends IndexingHandler> handlerClass : indexingHandlerList) {
                IndexingHandler handler = handlerClass.getDeclaredConstructor().newInstance();
                if (handler.accept()) {
                    indexingHandler = handler;
                    break;
                }
            }
            indexingHandler.startUp();
        } catch (Exception e) {
            log.error("Error starting the indexing handler", e);
        }

        // Start file service
        fileService = new FileService();
        fileService.startAsync();
        fileService.awaitRunning();

        // Start inbox service
        inboxService = new InboxService();
        inboxService.startAsync();
        inboxService.awaitRunning();

        // Start file size service
        fileSizeService = new FileSizeService();
        fileSizeService.startAsync();
        fileSizeService.awaitRunning();

        // Start trash purge service
        trashPurgeService = new TrashPurgeService();
        trashPurgeService.startAsync();
        trashPurgeService.awaitRunning();

        // Start OIDC state purge service
        oidcStatePurgeService = new OidcStatePurgeService();
        oidcStatePurgeService.startAsync();
        oidcStatePurgeService.awaitRunning();

        // Register fonts
        PdfUtil.registerFonts();

        // Change the admin password if needed
        String envAdminPassword = System.getenv(Constants.ADMIN_PASSWORD_INIT_ENV);
        if (!Strings.isNullOrEmpty(envAdminPassword)) {
            UserDao userDao = new UserDao();
            User adminUser = userDao.getById("admin");
            if (Constants.DEFAULT_ADMIN_PASSWORD.equals(adminUser.getPassword())) {
                adminUser.setPassword(envAdminPassword);
                userDao.updateHashedPassword(adminUser);
            }
        }

        // Change the admin email if needed
        String envAdminEmail = System.getenv(Constants.ADMIN_EMAIL_INIT_ENV);
        if (!Strings.isNullOrEmpty(envAdminEmail)) {
            UserDao userDao = new UserDao();
            User adminUser = userDao.getById("admin");
            if (Constants.DEFAULT_ADMIN_EMAIL.equals(adminUser.getEmail())) {
                adminUser.setEmail(envAdminEmail);
                userDao.update(adminUser, "admin");
            }
        }
    }

    /**
     * (Re)-initializes the event buses.
     */
    private void resetEventBus() {
        asyncExecutorList = new ArrayList<>();

        asyncEventBus = newAsyncEventBus();
        asyncEventBus.register(new FileProcessingAsyncListener());
        asyncEventBus.register(new FileDeletedAsyncListener());
        asyncEventBus.register(new DocumentCreatedAsyncListener());
        asyncEventBus.register(new DocumentUpdatedAsyncListener());
        asyncEventBus.register(new DocumentDeletedAsyncListener());
        asyncEventBus.register(new RebuildIndexAsyncListener());
        asyncEventBus.register(new AclCreatedAsyncListener());
        asyncEventBus.register(new AclDeletedAsyncListener());
        asyncEventBus.register(new WebhookAsyncListener());

        mailEventBus = newAsyncEventBus();
        mailEventBus.register(new PasswordLostAsyncListener());
        mailEventBus.register(new RouteStepValidateAsyncListener());
        mailEventBus.register(new RouteStepRejectedAsyncListener());
    }

    /**
     * Returns a single instance of the application context.
     *
     * @return Application context
     */
    public static AppContext getInstance() {
        if (instance == null) {
            instance = new AppContext();
            instance.startUp();
        }
        return instance;
    }

    /**
     * Creates a new asynchronous event bus.
     *
     * @return Async event bus
     */
    private EventBus newAsyncEventBus() {
        // Retry is default OFF: with retryCount 0 the handler logs-and-drops on the first exception,
        // matching the historical production behaviour (no re-invocation). Enabling it re-invokes
        // failed subscribers; this is safe because the registered listeners are idempotent — quota
        // reclamation happens synchronously in the delete transaction, not here (see
        // RetryingSubscriberExceptionHandler's Javadoc for the per-listener state).
        int retryCount = EnvironmentUtil.getIntConfig(
                ASYNC_RETRY_COUNT_PROPERTY, ASYNC_RETRY_COUNT_ENV, DEFAULT_ASYNC_RETRY_COUNT, 0);
        long retryBackoffMs = EnvironmentUtil.getIntConfig(
                ASYNC_RETRY_BACKOFF_MS_PROPERTY, ASYNC_RETRY_BACKOFF_MS_ENV, DEFAULT_ASYNC_RETRY_BACKOFF_MS, 0);
        if (retryCount > 0 && !asyncRetryWarningLogged) {
            asyncRetryWarningLogged = true;
            log.info("Async listener retry is enabled (docs.async_retry_count={}). Failed subscribers " +
                    "are re-invoked; the registered listeners are idempotent, so re-delivery does not " +
                    "double-apply side effects. Keep any newly added listener idempotent.", retryCount);
        }
        RetryingSubscriberExceptionHandler exceptionHandler =
                new RetryingSubscriberExceptionHandler(retryCount, retryBackoffMs);

        if (EnvironmentUtil.isUnitTest()) {
            return new EventBus(exceptionHandler);
        } else {
            int threadCount = Math.max(Runtime.getRuntime().availableProcessors() / 2, 2);

            // Bounded work queue so a burst of events cannot exhaust memory. When the queue is full,
            // CallerRunsPolicy makes the producing thread run the task itself, applying backpressure
            // instead of dropping events or growing the queue without limit.
            int queueCapacity = EnvironmentUtil.getIntConfig(
                    ASYNC_QUEUE_CAPACITY_PROPERTY, ASYNC_QUEUE_CAPACITY_ENV, DEFAULT_ASYNC_QUEUE_CAPACITY, 1);
            ThreadPoolExecutor executor = newBoundedAsyncExecutor(threadCount, queueCapacity);
            asyncExecutorList.add(executor);
            return new AsyncEventBus(executor, exceptionHandler);
        }
    }

    /**
     * Construct the {@link ThreadPoolExecutor} backing an async event bus: a fixed pool with a
     * bounded {@link LinkedBlockingQueue} work queue and a {@link ThreadPoolExecutor.CallerRunsPolicy}
     * rejection policy. When the queue is full, the producing thread runs the task itself, applying
     * backpressure instead of dropping events or growing the queue without limit.
     *
     * <p>Package-private and static so the bounded-queue behaviour can be exercised directly by a
     * unit test — the production async path uses a synchronous {@link EventBus} under unit tests
     * (see {@link EnvironmentUtil#isUnitTest()}), so this factory is otherwise unreachable in tests.
     *
     * @param threadCount   Core and maximum pool size
     * @param queueCapacity Bounded work-queue capacity
     * @return A configured bounded thread pool executor
     */
    static ThreadPoolExecutor newBoundedAsyncExecutor(int threadCount, int queueCapacity) {
        return new ThreadPoolExecutor(threadCount, threadCount,
                1L, TimeUnit.MINUTES,
                new LinkedBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * Return the current number of queued tasks waiting to be processed.
     *
     * @return Number of queued tasks
     */
    public int getQueuedTaskCount() {
        int queueSize = 0;
        for (ThreadPoolExecutor executor : asyncExecutorList) {
            queueSize += executor.getTaskCount() - executor.getCompletedTaskCount();
        }
        return queueSize;
    }

    /**
     * Return the total remaining capacity of the bounded async work queues, i.e. the number of
     * additional tasks that can be queued before the rejection policy kicks in. Returns 0 when no
     * bounded executor is active (e.g. in the synchronous unit-test bus).
     *
     * @return Remaining async queue capacity
     */
    public int getQueuedTaskCapacity() {
        int capacity = 0;
        for (ThreadPoolExecutor executor : asyncExecutorList) {
            capacity += executor.getQueue().remainingCapacity();
        }
        return capacity;
    }

    public EventBus getAsyncEventBus() {
        return asyncEventBus;
    }

    public EventBus getMailEventBus() {
        return mailEventBus;
    }

    public IndexingHandler getIndexingHandler() {
        return indexingHandler;
    }

    public InboxService getInboxService() {
        return inboxService;
    }

    public FileService getFileService() {
        return fileService;
    }

    public void shutDown() {
        for (ExecutorService executor : asyncExecutorList) {
            // Shutdown executor, don't accept any more tasks (can cause error with nested events)
            try {
                executor.shutdown();
                executor.awaitTermination(1, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                // NOP
            }
        }

        if (indexingHandler != null) {
            indexingHandler.shutDown();
        }

        if (inboxService != null) {
            inboxService.stopAsync();
            inboxService.awaitTerminated();
        }

        if (fileService != null) {
            fileService.stopAsync();
        }

        if (fileSizeService != null) {
            fileSizeService.stopAsync();
        }

        if (trashPurgeService != null) {
            trashPurgeService.stopAsync();
        }

        if (oidcStatePurgeService != null) {
            oidcStatePurgeService.stopAsync();
        }

        instance = null;
    }
}
