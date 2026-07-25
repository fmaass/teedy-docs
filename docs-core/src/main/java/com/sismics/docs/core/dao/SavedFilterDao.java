package com.sismics.docs.core.dao;

import com.sismics.docs.core.model.jpa.SavedFilter;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Saved filter DAO.
 */
public class SavedFilterDao {
    /**
     * Creates a new saved filter.
     *
     * <p>The create path FLUSHES in-request and translates the (user, name)
     * unique-constraint violation into a {@link SavedFilterExistsException} HERE.
     * A bare {@code persist()} defers the violation to the RequestContextFilter's
     * end-of-request commit, which would surface as a 500 rather than a 400. The
     * in-request flush is the concurrency backstop behind the resource's
     * case-insensitive precheck.
     *
     * @param savedFilter Saved filter to persist
     * @return Generated ID
     * @throws SavedFilterExistsException if a filter with the same (user, name) already exists
     */
    public String create(SavedFilter savedFilter) throws SavedFilterExistsException {
        savedFilter.setId(UUID.randomUUID().toString());
        savedFilter.setCreateDate(new Date());
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.persist(savedFilter);
        try {
            // Force the INSERT now so the DB unique index is checked in-request,
            // not at the deferred end-of-request commit (which would be a 500).
            em.flush();
        } catch (PersistenceException e) {
            // The failed INSERT is still queued in the persistence context. If left
            // there, ANY later flush — ThreadLocalContext.getEntityManager() flushes on
            // every access, and the transaction rollback path — re-attempts the duplicate
            // INSERT and re-throws. clear() drops the whole persistence context (and the
            // queued INSERT) so the request can surface a clean 400 and roll back without
            // re-hitting the constraint. Nothing else in this request needs the context.
            em.clear();
            if (isConstraintViolation(e)) {
                throw new SavedFilterExistsException(e);
            }
            throw e;
        }
        return savedFilter.getId();
    }

    /**
     * Lists a user's saved filters, ordered by name (exact-case).
     *
     * @param userId User ID
     * @return List of saved filters
     */
    public List<SavedFilter> getByUserId(String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<SavedFilter> q = em.createQuery(
                "select f from SavedFilter f where f.userId = :userId order by f.name", SavedFilter.class);
        q.setParameter("userId", userId);
        return q.getResultList();
    }

    /**
     * Returns a saved filter by ID scoped to its owner.
     *
     * @param id Saved filter ID
     * @param userId Owner user ID
     * @return Saved filter or null if not found or not owned
     */
    public SavedFilter getByIdAndUser(String id, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<SavedFilter> q = em.createQuery(
                "select f from SavedFilter f where f.id = :id and f.userId = :userId", SavedFilter.class);
        q.setParameter("id", id);
        q.setParameter("userId", userId);
        try {
            return q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Applies a name/query update to a saved filter owned by the given user.
     *
     * <p><b>The mutation is owned by this method, deliberately.</b> A caller that loaded the
     * entity itself, renamed it, and then called into the DAO would flush the rename OUTSIDE
     * any translation boundary: {@link ThreadLocalContext#getEntityManager()} flushes and clears
     * on EVERY access, so the very act of the DAO obtaining its entity manager would push the
     * dirty rename to the database, and the {@code IDX_SFL_USER_NAME} violation would escape as
     * a raw {@link PersistenceException} — a 500, not the 400 the create path produces. So the
     * entity manager is obtained FIRST, the entity is loaded through it, the mutation is applied,
     * and {@code flush()} runs INSIDE the same try/catch that translates the unique violation —
     * mirroring {@link #create(SavedFilter)}. Nothing between the mutation and the guarded flush
     * touches {@code ThreadLocalContext}.</p>
     *
     * <p>Only {@code name} and {@code query} are passed in, so the owner, the id and the create
     * date are structurally unreachable from an update.</p>
     *
     * @param id Saved filter ID
     * @param userId Owner user ID (for authorization)
     * @param name New filter name
     * @param query New canonical URL query string
     * @return the updated saved filter, or null if not found or not owned
     * @throws SavedFilterExistsException if the new name collides with another filter of the same user
     */
    public SavedFilter update(String id, String userId, String name, String query) throws SavedFilterExistsException {
        // FIRST: take the entity manager while nothing is dirty, so its flush-and-clear is a no-op.
        // Every later step uses THIS reference — never ThreadLocalContext again.
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        TypedQuery<SavedFilter> q = em.createQuery(
                "select f from SavedFilter f where f.id = :id and f.userId = :userId", SavedFilter.class);
        q.setParameter("id", id);
        q.setParameter("userId", userId);
        SavedFilter savedFilter;
        try {
            savedFilter = q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }

        savedFilter.setName(name);
        savedFilter.setQuery(query);
        try {
            // Force the UPDATE now so the DB unique index is checked HERE, inside the
            // translation boundary, rather than at the deferred end-of-request commit.
            em.flush();
        } catch (PersistenceException e) {
            // Same reasoning as create(): the failed UPDATE is still pending on the managed
            // entity. clear() detaches it so no later flush (nor the rollback path) re-attempts
            // it and re-throws over the top of the 400 this translates into.
            em.clear();
            if (isConstraintViolation(e)) {
                throw new SavedFilterExistsException(e);
            }
            throw e;
        }
        return savedFilter;
    }

    /**
     * Hard-deletes a saved filter owned by the given user.
     *
     * @param id Saved filter ID
     * @param userId Owner user ID (for authorization)
     * @return true if a row was deleted, false if not found or not owned
     */
    public boolean delete(String id, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery(
                "delete from SavedFilter f where f.id = :id and f.userId = :userId");
        q.setParameter("id", id);
        q.setParameter("userId", userId);
        return q.executeUpdate() > 0;
    }

    /**
     * Detects whether a persistence exception was caused by a DB integrity/constraint
     * violation, dialect-agnostically. Hibernate maps a duplicate key to
     * {@code org.hibernate.exception.ConstraintViolationException} whose SQL cause is a
     * {@link SQLException} with SQLState class "23" (integrity constraint violation) on
     * both H2 and PostgreSQL.
     */
    private static boolean isConstraintViolation(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                if (state != null && state.startsWith("23")) {
                    return true;
                }
            }
            if (cause instanceof org.hibernate.exception.ConstraintViolationException) {
                return true;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return false;
    }
}
