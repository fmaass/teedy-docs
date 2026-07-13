package com.sismics.util.filter;

import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.util.DirectoryUtil;
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
        
        // Initialize the application context
        TransactionUtil.handle(AppContext::getInstance);
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
        context.setEntityManager(em);
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        
        try {
            addCacheHeaders(response);
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            ThreadLocalContext.cleanup();
            
            // IOException are thrown if the client closes the connection before completion
            if (!(e instanceof IOException)) {
                log.error("An exception occured, rolling back current transaction", e);

                // If an unprocessed error comes up from the application layers (Jersey...), rollback the transaction (should not happen)
                if (em.isOpen()) {
                    if (em.getTransaction() != null && em.getTransaction().isActive()) {
                        em.getTransaction().rollback();
                    }
                    
                    try {
                        em.close();
                    } catch (Exception ce) {
                        log.error("Error closing entity manager", ce);
                    }
                }
                throw new ServletException(e);
            }
        }

        // No error processing the request : commit / rollback the current transaction depending on
        // the HTTP code, then fire the queued async events ONLY if the transaction committed. On
        // rollback (or a commit failure) the queued events describe modifications that never reached
        // the database, so they are discarded instead of fired.
        //
        // Finalization is exception-safe: whatever commit / rollback / sendError does, the finally
        // block guarantees that on any non-committed outcome the queued async events are discarded
        // and the thread-local context is cleared. Otherwise a throw from the error path (e.g.
        // sendError or rollback failing) would leave the queued events and thread-local state in
        // place, letting them leak into — and fire during — the NEXT request handled on this thread
        // (issue #63 resurfacing via the error path).
        HttpServletResponse r = (HttpServletResponse) response;
        boolean committed = false;
        try {
            committed = commitAndFinalize(em, r.getStatus(), r);

            if (committed) {
                // Fire all pending async events after request transaction commit.
                // This way, all modifications done during this request are available in the listeners.
                context.fireAllAsyncEvents();
            }
        } finally {
            if (!committed) {
                // Any path where the transaction did not successfully commit (rollback, commit
                // failure, or an exception thrown while finalizing) MUST discard the queued events —
                // never fire them on the error path.
                context.discardAsyncEvents();
            }
            ThreadLocalContext.cleanup();
        }
    }

    /**
     * Commits or rolls back the request transaction depending on the HTTP status, then closes the
     * entity manager.
     *
     * <p>The transaction is committed only for 2xx/3xx responses; any other status rolls back. This
     * method never fires the queued async events itself — it returns whether the transaction
     * committed so the caller can decide to fire (commit) or discard (rollback / commit failure) the
     * queued events. This is the invariant that prevents a rolled-back request from physically
     * deleting file bytes or mutating the Lucene index (issue #63).</p>
     *
     * <p>The entity manager is always closed (in a finally), even if the commit, rollback, or the
     * {@code sendError} signalling throws — so a failure on the error path cannot leak an open
     * entity manager. The originating exception is allowed to propagate; the caller's finally still
     * discards the queued events and clears the thread-local context.</p>
     *
     * @param em Entity manager whose transaction is finalized (may be closed or have no active transaction)
     * @param httpStatus HTTP status code of the response
     * @param response Response, used to signal a 500 if the commit itself fails
     * @return {@code true} if and only if the transaction was committed successfully
     * @throws IOException if signalling the error response fails
     */
    static boolean commitAndFinalize(EntityManager em, int httpStatus, HttpServletResponse response) throws IOException {
        boolean committed = false;
        if (em.isOpen()) {
            if (em.getTransaction() != null && em.getTransaction().isActive()) {
                try {
                    int statusClass = httpStatus / 100;
                    if (statusClass == 2 || statusClass == 3) {
                        try {
                            em.getTransaction().commit();
                            committed = true;
                        } catch (Exception e) {
                            log.error("Error during commit", e);
                            response.sendError(500);
                        }
                    } else {
                        em.getTransaction().rollback();
                    }
                } finally {
                    // Close the entity manager even if commit / rollback / sendError threw, so the
                    // error path can never leak an open entity manager.
                    try {
                        em.close();
                    } catch (Exception e) {
                        log.error("Error closing entity manager", e);
                    }
                }
            }
        }
        return committed;
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
