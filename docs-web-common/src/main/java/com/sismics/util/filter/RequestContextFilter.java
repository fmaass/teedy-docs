package com.sismics.util.filter;

import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.docs.core.util.TransactionBoundary;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.EnvironmentUtil;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.log4j.Level;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.RollingFileAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import java.io.IOException;
import java.text.MessageFormat;

/**
 * Filter used to process a couple things in the request context.
 * 
 * @author jtremeaux
 */
public class RequestContextFilter implements Filter {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(RequestContextFilter.class);

    @Override
    public void init(FilterConfig filterConfig) {
        // Initialize the app directory
        if (!filterConfig.getServletContext().getServerInfo().startsWith("Grizzly")) {
            EnvironmentUtil.setWebappContext(true);
        }
        try {
            if (log.isInfoEnabled()) {
                log.info(MessageFormat.format("Using base data directory: {0}", DirectoryUtil.getBaseDataDirectory()));
            }
        } catch (Exception e) {
            log.error("Error initializing base data directory", e);
        }
        
        
        // Initialize file logger
        RollingFileAppender fileAppender = new RollingFileAppender();
        fileAppender.setName("FILE");
        fileAppender.setFile(DirectoryUtil.getLogDirectory().resolve("docs.log").toString());
        fileAppender.setLayout(new PatternLayout("%d{DATE} %p %l %m %n"));
        fileAppender.setThreshold(Level.INFO);
        fileAppender.setAppend(true);
        fileAppender.setMaxFileSize("5MB");
        fileAppender.setMaxBackupIndex(5);
        fileAppender.activateOptions();
        org.apache.log4j.Logger.getRootLogger().addAppender(fileAppender);
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
        
        // Initialize the application context. This is a best-effort warm-up: a failure here must NOT
        // abort filter initialization (which would leave the servlet container with a null filter and
        // fail every subsequent request). AppContext.getInstance() now publishes its singleton only
        // after a successful startUp(), so a transient warm-up failure leaves no half-initialized
        // context cached and the first real request cleanly re-attempts creation. The catch is local to
        // the warm-up and logs at error (a genuinely fatal misconfiguration is not swallowed silently) —
        // it is required because TransactionUtil.handle now propagates a failed unit of work (so
        // background file processing can no longer report false success).
        try {
            TransactionUtil.handle(AppContext::getInstance);
        } catch (Exception e) {
            log.error("Error initializing the application context during filter init", e);
        }
    }

    @Override
    public void destroy() {
        AppContext.getInstance().shutDown();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        EntityManager em;

        try {
            em = EMF.get().createEntityManager();
        } catch (Exception e) {
            throw new ServletException("Cannot create entity manager", e);
        }
        ThreadLocalContext context = ThreadLocalContext.get();

        // Finalization is exception-safe and deferred: the entity manager is installed and the
        // transaction begun INSIDE the cleanup-protected region, so even a tx.begin() failure cannot
        // leak the installed frame / manager onto the pooled thread. The frame's completion (firing the
        // queued async events on a durable commit, discarding on rollback, neither on IN_DOUBT) runs
        // while the frame is still installed, and the thread-local context is cleared LAST — guaranteed,
        // even if closeQuietly threw — so no open manager and no queued event leaks into the NEXT request
        // handled on this thread (issue #63 must not resurface via the error path).
        try {
            context.setEntityManager(em);
            EntityTransaction tx = em.getTransaction();
            tx.begin();

            try {
                addCacheHeaders(response);
                filterChain.doFilter(request, response);
            } catch (Exception e) {
                // IOException are thrown if the client closes the connection before completion: fall
                // through to the status-based finalization below (unchanged behavior).
                if (!(e instanceof IOException)) {
                    log.error("An exception occured, rolling back current transaction", e);

                    // An unprocessed error came up from the application layers (Jersey...): this is a
                    // pre-commit failure. Roll the transaction back and classify: ROLLED_BACK discards the
                    // queued events and runs the after-rollback callbacks; if the rollback itself throws
                    // the outcome is IN_DOUBT (no compensation, events neither fired nor discarded). Then
                    // rethrow as a ServletException — never commit and never fire on this path.
                    TransactionBoundary.DurableState state = TransactionBoundary.tryRollback(em).getState();
                    TransactionBoundary.closeQuietly(em);
                    TransactionBoundary.complete(state);
                    throw new ServletException(e);
                }
            }

            // No unhandled application error: commit for 2xx/3xx, otherwise roll back. The completion
            // dispatcher then fires the queued async events IFF the transaction durably committed, else
            // discards them — so all modifications done during a committed request are available in the
            // listeners, and a rolled-back request never fires.
            HttpServletResponse r = (HttpServletResponse) response;
            TransactionBoundary.DurableState state = commitAndFinalize(em, r.getStatus(), r);
            TransactionBoundary.complete(state);
        } finally {
            // Guarantee cleanup() runs even if closeQuietly() somehow throws (it is written not to, but
            // the thread-local MUST be cleared to avoid poisoning the next request on this pooled thread).
            try {
                TransactionBoundary.closeQuietly(em);
            } finally {
                ThreadLocalContext.cleanup();
            }
        }
    }

    /**
     * Commits or rolls back the request transaction depending on the HTTP status, then closes the
     * entity manager.
     *
     * <p>The transaction is committed only for 2xx/3xx responses; any other status rolls back. This
     * method never fires the queued async events itself — it returns the durable state so the caller's
     * completion dispatcher can fire (COMMITTED), discard (ROLLED_BACK), or neither (IN_DOUBT) the queued
     * events. This is the invariant that prevents a rolled-back request from physically deleting file
     * bytes or mutating the Lucene index (issue #63).</p>
     *
     * <p>A commit that itself throws is NOT durable-clean: it is classified {@link
     * TransactionBoundary.DurableState#IN_DOUBT} (the database may have committed anyway), a 500 is
     * signalled to honour the wire contract, and the queued events are NEITHER fired NOR discarded (their
     * durability is unknown; the search-index rebuild / storage sweep are the recovery net). A rollback
     * that throws is likewise IN_DOUBT so no after-rollback compensation runs.</p>
     *
     * <p>The entity manager is always closed here; the caller's finally also closes it (a no-op backstop)
     * and clears the thread-local context.</p>
     *
     * @param em Entity manager whose transaction is finalized (may be closed or have no active transaction)
     * @param httpStatus HTTP status code of the response
     * @param response Response, used to signal a 500 if the commit itself fails
     * @return the classified durable state of the request transaction
     * @throws IOException if signalling the error response fails
     */
    static TransactionBoundary.DurableState commitAndFinalize(EntityManager em, int httpStatus, HttpServletResponse response) throws IOException {
        TransactionBoundary.DurableState state = TransactionBoundary.DurableState.ROLLED_BACK;
        if (em.isOpen() && em.getTransaction() != null && em.getTransaction().isActive()) {
            int statusClass = httpStatus / 100;
            if (statusClass == 2 || statusClass == 3) {
                state = TransactionBoundary.tryCommit(em.getTransaction()).getState();
                if (state == TransactionBoundary.DurableState.IN_DOUBT) {
                    // The commit threw (the transaction may have committed anyway). Honour the wire
                    // contract with a 500; the completion dispatcher fires nothing for IN_DOUBT.
                    response.sendError(500);
                }
            } else {
                state = TransactionBoundary.tryRollback(em).getState();
            }
            TransactionBoundary.closeQuietly(em);
        }
        return state;
    }

    /**
     * Add no-cache header.
     *
     * @param response Response
     */
    private void addCacheHeaders(ServletResponse response) {
        HttpServletResponse r = (HttpServletResponse) response;
        r.addHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        r.addHeader(HttpHeaders.EXPIRES, "0");
        r.addHeader("X-Content-Type-Options", "nosniff");
        r.addHeader("X-Frame-Options", "DENY");
        r.addHeader("Referrer-Policy", "strict-origin-when-cross-origin");
    }
}
