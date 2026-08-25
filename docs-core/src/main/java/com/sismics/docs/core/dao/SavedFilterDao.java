package com.sismics.docs.core.dao;

import com.sismics.docs.core.dao.dto.SavedFilterDto;
import com.sismics.docs.core.model.jpa.SavedFilter;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.sql.SQLException;
import java.util.ArrayList;
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
     * Returns a saved filter by ID, whoever owns it.
     *
     * <p>The ONLY caller that may not scope by owner is the administrator's unpublish path (#51),
     * which needs the row precisely because it is not the caller's. Every other read goes through
     * {@link #getByIdAndUser} — an owner-scoped lookup is what makes a foreign id answerable as
     * "not found" without confirming that someone else's filter exists.</p>
     *
     * @param id Saved filter ID
     * @return Saved filter or null if no such filter exists
     */
    public SavedFilter getById(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<SavedFilter> q = em.createQuery(
                "select f from SavedFilter f where f.id = :id", SavedFilter.class);
        q.setParameter("id", id);
        try {
            return q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Lists every PUBLISHED saved filter with its publisher's username, ordered by name (#51).
     *
     * <p>Joined to {@code User} as a theta join because {@link SavedFilter} deliberately holds a
     * plain owner id and no association — and RESTRICTED to live users: a soft-deleted account's
     * filters stop being offered to the instance, since nobody is left to curate, rename or
     * withdraw them. The row itself is untouched, so restoring the account restores its
     * publications.</p>
     *
     * <p>This method makes NO visibility judgement about the caller: whether a given viewer may
     * apply one of these filters depends on the tags it names and on that viewer's ACLs, which is
     * decided per request in {@code SavedFilterUtil}, not stored.</p>
     *
     * @return List of published filters
     */
    public List<SavedFilterDto> getPublished() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<Object[]> q = em.createQuery(
                "select f, u.username from SavedFilter f, User u "
                        + "where f.userId = u.id and u.deleteDate is null and f.publishDate is not null "
                        + "order by f.name", Object[].class);
        List<SavedFilterDto> dtoList = new ArrayList<>();
        for (Object[] row : q.getResultList()) {
            SavedFilter filter = (SavedFilter) row[0];
            dtoList.add(new SavedFilterDto()
                    .setId(filter.getId())
                    .setUserId(filter.getUserId())
                    .setUsername((String) row[1])
                    .setName(filter.getName())
                    .setQuery(filter.getQuery())
                    .setCreateDate(filter.getCreateDate())
                    .setPublishDate(filter.getPublishDate()));
        }
        return dtoList;
    }

    /**
     * Publishes or unpublishes a saved filter OWNED by the given user (#51).
     *
     * <p>Publication is authorship, so this path is owner-scoped exactly like
     * {@link #update}: a filter that is not the caller's is answered {@code null} and left alone.
     * Re-publishing an already-published filter keeps the ORIGINAL publish date — it is the same
     * publication, and "shared since" must not reset because someone pressed the control twice.</p>
     *
     * <p>The entity manager is taken FIRST and every later step uses that reference, for the same
     * reason as {@link #update}: {@link ThreadLocalContext#getEntityManager()} flushes on every
     * access, so re-fetching it after the mutation would push a half-applied change out through an
     * accessor rather than through this method's own flush.</p>
     *
     * @param id Saved filter ID
     * @param userId Owner user ID (for authorization)
     * @param published true to publish, false to withdraw the publication
     * @return the updated saved filter, or null if no filter with this id is owned by the user
     */
    public SavedFilter setPublished(String id, String userId, boolean published) {
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

        if (published) {
            if (savedFilter.getPublishDate() == null) {
                savedFilter.setPublishDate(new Date());
            }
        } else {
            savedFilter.setPublishDate(null);
        }
        em.flush();
        return savedFilter;
    }

    /**
     * Withdraws a saved filter's publication REGARDLESS of who owns it — the administrator's
     * management path (#51).
     *
     * <p>Deliberately narrower than {@link #setPublished}: it can only ever clear the publication,
     * never set one and never touch the name, the query or the owner. An administrator governs what
     * the instance is shown; the filter stays its author's.</p>
     *
     * @param id Saved filter ID
     * @return true if a published filter was withdrawn, false if there was nothing to withdraw
     */
    public boolean unpublish(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery(
                "update SavedFilter f set f.publishDate = null where f.id = :id and f.publishDate is not null");
        q.setParameter("id", id);
        return q.executeUpdate() > 0;
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
