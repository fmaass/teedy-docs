package com.sismics.docs.core.service;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.OidcStateDao;
import com.sismics.docs.core.model.jpa.OidcState;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.UUID;

/**
 * RR-34: expired OIDC state rows must be purged. Verifies the purge query
 * deletes rows older than the TTL and leaves fresh rows intact.
 */
public class TestOidcStatePurgeService extends BaseTransactionalTest {

    @Test
    public void deleteExpiredRemovesOnlyStaleRows() {
        OidcStateDao dao = new OidcStateDao();
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        // A fresh row (created now) and a stale row (backdated well past the TTL).
        String freshId = UUID.randomUUID().toString();
        String staleId = UUID.randomUUID().toString();
        dao.create(new OidcState().setId(freshId).setNonce("n1").setCodeVerifier("v1"));
        dao.create(new OidcState().setId(staleId).setNonce("n2").setCodeVerifier("v2"));

        // Backdate the stale row one day into the past.
        em.createNativeQuery("update T_OIDC_STATE set OIS_CREATEDATE_D = :past where OIS_ID_C = :id")
                .setParameter("past", new Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000))
                .setParameter("id", staleId)
                .executeUpdate();
        em.flush();

        // Purge everything older than one hour.
        int deleted = dao.deleteExpired(60L * 60 * 1000);
        em.clear();

        Assertions.assertEquals(1, deleted, "exactly the stale row must be purged");
        Assertions.assertNull(em.find(OidcState.class, staleId), "the stale row must be gone");
        Assertions.assertNotNull(em.find(OidcState.class, freshId), "the fresh row must survive");
    }
}
