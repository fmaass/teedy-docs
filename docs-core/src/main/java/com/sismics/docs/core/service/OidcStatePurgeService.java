package com.sismics.docs.core.service;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.AbstractScheduledService;
import com.sismics.docs.core.dao.OidcStateDao;
import com.sismics.docs.core.util.TransactionUtil;

/**
 * Service that periodically purges expired OIDC state records (RR-34).
 * A login attempt inserts a T_OIDC_STATE row that is normally consumed on
 * callback; abandoned attempts (user closes the tab, IdP never redirects back)
 * leave rows behind. The login path already opportunistically deletes expired
 * rows, but only when a new login occurs -- this service bounds accumulation
 * on its own schedule.
 */
public class OidcStatePurgeService extends AbstractScheduledService {
    private static final Logger log = LoggerFactory.getLogger(OidcStatePurgeService.class);

    /**
     * Rows older than this are safe to delete: a state parameter is only valid
     * for the duration of a single authorization round-trip, far below one hour.
     */
    static final long STATE_TTL_MS = 60L * 60 * 1000;

    @Override
    protected void startUp() {
        log.info("OIDC state purge service starting up");
    }

    @Override
    protected void shutDown() {
        log.info("OIDC state purge service shutting down");
    }

    @Override
    protected void runOneIteration() {
        try {
            purgeExpiredState();
        } catch (Throwable e) {
            log.error("Exception during OIDC state purge", e);
        }
    }

    @Override
    protected Scheduler scheduler() {
        return Scheduler.newFixedDelaySchedule(1, 60, TimeUnit.MINUTES);
    }

    void purgeExpiredState() {
        purgeExpiredState(STATE_TTL_MS);
    }

    /**
     * Delete OIDC state rows older than the given TTL.
     * Package-private with an explicit TTL so tests are deterministic.
     *
     * @param ttlMs maximum age in milliseconds
     */
    void purgeExpiredState(long ttlMs) {
        int[] deleted = new int[1];
        TransactionUtil.handle(() -> deleted[0] = new OidcStateDao().deleteExpired(ttlMs));
        if (deleted[0] > 0) {
            log.info("Purged {} expired OIDC state records", deleted[0]);
        }
    }
}
