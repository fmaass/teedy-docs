package com.sismics.docs.core.dao;

import com.sismics.docs.core.model.jpa.Favorite;
import com.sismics.docs.core.util.TransactionBoundary;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Per-user document favorite DAO. Favorites are private membership rows with hard
 * delete semantics and a unique (user, document) index.
 */
public class FavoriteDao {
    /**
     * Returns the favorite linking a user to a document, or null if the user has not
     * favorited that document.
     *
     * @param userId User ID
     * @param documentId Document ID
     * @return Favorite or null
     */
    public Favorite getByUserAndDocument(String userId, String documentId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<Favorite> q = em.createQuery(
                "select f from Favorite f where f.userId = :userId and f.documentId = :documentId", Favorite.class);
        q.setParameter("userId", userId);
        q.setParameter("documentId", documentId);
        try {
            return q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Idempotently favorites a document for a user and returns the favorite's ID. If the
     * (user, document) star already exists — whether found by the in-request precheck or
     * created by a concurrent request that raced past it — the existing row's ID is
     * returned and NO exception is raised: a repeat star is a 200 no-op, never a 500.
     *
     * <p>The concurrency backstop is delicate. A duplicate INSERT flushed in-request marks
     * the JPA transaction rollback-only; RequestContextFilter commits on a 2xx response, and
     * committing a rollback-only transaction turns the intended no-op into a 500. So on ANY
     * integrity violation we do NOT merely {@code clear()} the queued INSERT (which alone
     * leaves the transaction rollback-only) — we roll the poisoned transaction back and begin
     * a fresh, clean one.
     *
     * <p>An integrity violation on insert is NOT necessarily the unique (user, document)
     * collision: a document purged between the resource's READ precheck and this insert raises
     * a document foreign-key violation instead. We do not parse constraint names or vendor
     * SQLState strings to tell the two apart (that is dialect-specific) — we re-query committed
     * state in the fresh transaction and branch on it:
     * <ul>
     *   <li>(a) the favorite row now exists → a concurrent winner committed it; return its id
     *       (idempotent no-op).</li>
     *   <li>(b) the row is absent AND the document no longer exists → the FK failed because the
     *       document was purged; raise {@link EntityNotFoundException} so the resource returns
     *       404.</li>
     *   <li>(c) the row is absent AND the document still exists → a transient failure, or a
     *       winner that has not yet committed its row; retry the insert ONCE. On a second
     *       violation, roll back and re-query once more: return the row if a winner has since
     *       committed, else propagate the exception.</li>
     * </ul>
     * On both PostgreSQL and H2 a unique violation surfaces only after the winning transaction
     * commits, so the post-rollback re-check reliably sees the winner's row. The re-insert is
     * bounded to exactly one retry.
     *
     * @param userId User ID
     * @param documentId Document ID
     * @return Favorite ID (existing or newly created)
     * @throws EntityNotFoundException if the target document no longer exists
     */
    public String create(String userId, String documentId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        // In-request precheck: the common repeat-star path never touches the DB constraint.
        Favorite existing = getByUserAndDocument(userId, documentId);
        if (existing != null) {
            return existing.getId();
        }

        Favorite favorite = newFavorite(userId, documentId);
        em.persist(favorite);
        try {
            // Force the INSERT now so a concurrent duplicate is caught in-request rather than
            // at the deferred end-of-request commit.
            em.flush();
            return favorite.getId();
        } catch (PersistenceException e) {
            if (!isConstraintViolation(e)) {
                throw e;
            }
            // Recover the transaction to a committable state before re-querying: drop the queued
            // INSERT and roll the poisoned (rollback-only) transaction back, then begin a fresh
            // one so any read below — and the filter's 2xx commit — succeeds.
            beginFreshTransaction(em);

            // (a) A concurrent winner committed the same star: idempotent no-op.
            Favorite winner = getByUserAndDocument(userId, documentId);
            if (winner != null) {
                return winner.getId();
            }
            // (b) The FK failed because the document was purged out from under us: signal not-found.
            if (!documentExists(documentId)) {
                throw new EntityNotFoundException("Document " + documentId + " no longer exists");
            }
            // (c) Row absent, document present: transient failure or a not-yet-committed winner.
            // Retry the insert exactly ONCE in the fresh transaction.
            Favorite retry = newFavorite(userId, documentId);
            em.persist(retry);
            try {
                em.flush();
                return retry.getId();
            } catch (PersistenceException retryException) {
                if (!isConstraintViolation(retryException)) {
                    throw retryException;
                }
                // Second violation: recover and re-query once more. A winner may have committed
                // between the (a) check and this retry — return its row rather than surfacing a
                // duplicate-star 500. Only a genuinely inexplicable state propagates.
                beginFreshTransaction(em);
                Favorite lateWinner = getByUserAndDocument(userId, documentId);
                if (lateWinner != null) {
                    return lateWinner.getId();
                }
                if (!documentExists(documentId)) {
                    throw new EntityNotFoundException("Document " + documentId + " no longer exists");
                }
                throw retryException;
            }
        }
    }

    private static Favorite newFavorite(String userId, String documentId) {
        Favorite favorite = new Favorite();
        favorite.setId(UUID.randomUUID().toString());
        favorite.setUserId(userId);
        favorite.setDocumentId(documentId);
        favorite.setCreateDate(new Date());
        return favorite;
    }

    /**
     * Drops any queued changes and replaces the poisoned (rollback-only) transaction with a fresh,
     * committable one, so a subsequent read and the end-of-request commit both succeed.
     *
     * <p>The poisoned transaction is rolled back here, so its frame is finalized as rolled back: its
     * after-rollback / after-completion callbacks run and its queued async events are DISCARDED (they
     * describe the failed insert that never committed). The frame is then rotated to a fresh empty
     * scope on the SAME entity manager for the replacement transaction, via the shared boundary helper
     * that also drives the checkpoint rotation.</p>
     */
    static void beginFreshTransaction(EntityManager em) {
        em.clear();
        EntityTransaction tx = em.getTransaction();

        // Classify the rollback through the three-state boundary rather than a raw tx.rollback(): a
        // thrown rollback is IN_DOUBT (the rollback is unconfirmed). Mark the frame terminal so the
        // enclosing request owner does not re-finalize it (running after-rollback compensation on an
        // unconfirmed state), then propagate so create() aborts.
        TransactionBoundary.ClassifiedOutcome co = TransactionBoundary.tryRollback(em);
        if (co.getState() == TransactionBoundary.DurableState.IN_DOUBT) {
            ThreadLocalContext.get().markCurrentFrameInDoubt();
            TransactionBoundary.complete(TransactionBoundary.DurableState.IN_DOUBT);
            TransactionBoundary.propagate(co.getFailure());
        }

        // Clean rollback: finalize the poisoned transaction's frame as rolled back (after-rollback
        // callbacks run, its queued events are discarded), open a fresh scope on the same manager, and
        // re-begin. A re-begin failure is fatal to continuation (the rollback is preserved): the orphaned
        // manager is closed and the failure propagates so create() aborts.
        TransactionBoundary.rotate(TransactionBoundary.DurableState.ROLLED_BACK);
        TransactionBoundary.beginContinuation(em, tx);
    }

    /**
     * Reports whether the target document row still exists (deleteDate-agnostic: the FK references
     * the physical row, so a trashed-but-not-purged document is still a valid FK target). Used to
     * distinguish a document-FK violation (document purged) from the unique-star collision without
     * parsing dialect-specific constraint names.
     */
    private boolean documentExists(String documentId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createNativeQuery("select count(*) from T_DOCUMENT d where d.DOC_ID_C = :documentId");
        q.setParameter("documentId", documentId);
        return ((Number) q.getSingleResult()).longValue() > 0;
    }

    /**
     * Returns the IDs of the documents a user has favorited.
     *
     * @param userId User ID
     * @return Document IDs
     */
    public List<String> getDocumentIdsByUser(String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<String> q = em.createQuery(
                "select f.documentId from Favorite f where f.userId = :userId", String.class);
        q.setParameter("userId", userId);
        return q.getResultList();
    }

    /**
     * Hard-deletes a user's favorite of a document.
     *
     * @param userId User ID (for ownership scoping)
     * @param documentId Document ID
     * @return true if a row was deleted, false if the user had not favorited the document
     */
    public boolean delete(String userId, String documentId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery(
                "delete from Favorite f where f.userId = :userId and f.documentId = :documentId");
        q.setParameter("userId", userId);
        q.setParameter("documentId", documentId);
        return q.executeUpdate() > 0;
    }

    /**
     * Detects whether a persistence exception was caused by a DB integrity/constraint
     * violation, dialect-agnostically (SQLState class "23" on both H2 and PostgreSQL, or a
     * Hibernate ConstraintViolationException).
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
