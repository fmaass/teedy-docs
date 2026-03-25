package com.sismics.docs.core.dao;

import com.sismics.docs.core.model.jpa.OidcState;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.Date;

/**
 * OIDC state DAO.
 */
public class OidcStateDao {

    /**
     * Persists a new OIDC state record.
     *
     * @param oidcState the state to persist (id must already be set)
     */
    public void create(OidcState oidcState) {
        oidcState.setCreateDate(new Date());
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.persist(oidcState);
    }

    /**
     * Atomically retrieves and deletes an OIDC state by its id.
     *
     * @param id the state parameter from the OIDC callback
     * @return the state record, or null if not found
     */
    public OidcState getAndDelete(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        OidcState state = em.find(OidcState.class, id);
        if (state != null) {
            em.remove(state);
        }
        return state;
    }

    /**
     * Deletes all state records older than the given TTL.
     *
     * @param ttlMs maximum age in milliseconds
     * @return number of records deleted
     */
    public int deleteExpired(long ttlMs) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createNativeQuery("delete from T_OIDC_STATE where OIS_CREATEDATE_D < :cutoff");
        q.setParameter("cutoff", new Date(System.currentTimeMillis() - ttlMs));
        return q.executeUpdate();
    }
}
