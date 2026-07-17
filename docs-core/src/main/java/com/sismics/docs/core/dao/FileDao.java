package com.sismics.docs.core.dao;

import com.sismics.docs.core.constant.AuditLogType;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.util.AuditLogUtil;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * File DAO.
 * 
 * @author bgamard
 */
public class FileDao {
    /**
     * Creates a new file.
     * 
     * @param file File
     * @param userId User ID
     * @return New ID
     */
    public String create(File file, String userId) {
        // Create the UUID
        file.setId(UUID.randomUUID().toString());
        
        // Create the file
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        file.setCreateDate(new Date());
        em.persist(file);
        
        // Create audit log
        AuditLogUtil.create(file, AuditLogType.CREATE, userId);
        
        return file.getId();
    }
    
    /**
     * Returns the list of all files.
     *
     * @param offset Offset
     * @param limit Limit
     * @return List of files
     */
    public List<File> findAll(int offset, int limit) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f where f.deleteDate is null", File.class);
        q.setFirstResult(offset);
        q.setMaxResults(limit);
        return q.getResultList();
    }
    
    /**
     * Returns the list of all files from a user.
     * 
     * @param userId User ID
     * @return List of files
     */
    public List<File> findByUserId(String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f where f.userId = :userId and f.deleteDate is null", File.class);
        q.setParameter("userId", userId);
        return q.getResultList();
    }

    /**
     * Returns a list of active files.
     *
     * @param ids Files IDs
     * @return List of files
     */
    public List<File> getFiles(List<String> ids) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f where f.id in :ids and f.deleteDate is null", File.class);
        q.setParameter("ids", ids);
        return q.getResultList();
    }
    
    /**
     * Returns the subset of the given file IDs whose row still PHYSICALLY exists in T_FILE, regardless
     * of soft-delete state (no {@code deleteDate} filter). Only a committed hard-delete removes the row.
     * Used by {@link com.sismics.docs.core.util.FileUtil#reclaimQuotaForDeletedDocumentFiles} to re-read,
     * under the global quota lock, which of a purge's snapshotted files a concurrent purge has not already
     * hard-deleted — so their quota is reclaimed exactly once.
     *
     * @param ids File IDs to test
     * @return the subset of {@code ids} that still have a row
     */
    public Set<String> getExistingIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptySet();
        }
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<String> q = em.createQuery("select f.id from File f where f.id in :ids", String.class);
        q.setParameter("ids", ids);
        return new HashSet<>(q.getResultList());
    }

    /**
     * Returns an active file or null.
     *
     * @param id File ID
     * @return File
     */
    public File getFile(String id) {
        List<File> files = getFiles(List.of(id));
        if (files.isEmpty()) {
            return null;
        } else {
            return files.get(0);
        }
    }
    
    /**
     * Returns an active file.
     * 
     * @param id File ID
     * @param userId User ID
     * @return File
     */
    public File getFile(String id, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f where f.id = :id and f.userId = :userId and f.deleteDate is null", File.class);
        q.setParameter("id", id);
        q.setParameter("userId", userId);
        try {
            return q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }
    
    /**
     * Soft-deletes a file and, when it was the current latest version of a chain, promotes its
     * immediately-prior active version to latest — under the same affected-row compare-and-swap the
     * new-version path uses — so the chain never ends up with zero (or two) latest rows. Deleting a latest
     * row alone would strip the file from {@link #getByDocumentId}, hiding it even though older versions
     * remain; folding the repair into delete makes that invariant intrinsic to file deletion.
     *
     * <p>The gate is a conditional {@code UPDATE ... where latestVersion = true and deleteDate is null}: its
     * affected-row count decides ownership of the promotion. A returned 1 means THIS delete observed the row
     * as the current active latest (it wins the race against a concurrent new-version create demoting the
     * same row); a returned 0 means the row was already not the current latest (a concurrent create demoted
     * it, or a concurrent delete handled it), so there is nothing to promote and the row is simply
     * soft-deleted. The target chain is re-read AFTER the gate — the caller's originally-fetched entity may
     * be stale, since every DAO access flushes and clears the persistence context.</p>
     *
     * @param id File ID
     * @param userId User ID
     */
    public void delete(String id, String userId) {
        // CAS gate: clear the latest flag iff this row is still the current active latest. The bulk update
        // acquires a row write lock, serializing this delete against a concurrent new-version create.
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        int wonLatest = em.createQuery("update File f set f.latestVersion = false" +
                        " where f.id = :id and f.latestVersion = true and f.deleteDate is null")
                .setParameter("id", id)
                .executeUpdate();

        // Soft-delete the row (+ audit). It is still active (the gate only cleared the latest flag), so this
        // re-fetches it fresh and stamps the delete date.
        softDelete(id, userId);

        if (wonLatest == 0) {
            // The row was not the current active latest, so the chain's latest lives elsewhere: nothing to
            // promote.
            return;
        }

        // This delete removed the current latest: promote the immediately-prior active version so the file
        // stays visible with its remaining history.
        promotePriorVersion(id);
    }

    /**
     * Soft-deletes an active file row (stamp its delete date) and records the delete audit log. The raw
     * primitive behind {@link #delete(String, String)}; kept private so file deletion always goes through
     * the version-aware path.
     *
     * @param id File ID
     * @param userId User ID
     */
    private void softDelete(String id, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        // Get the file
        TypedQuery<File> q = em.createQuery("select f from File f where f.id = :id and f.deleteDate is null", File.class);
        q.setParameter("id", id);
        File fileDb = q.getSingleResult();

        // Delete the file
        Date dateNow = new Date();
        fileDb.setDeleteDate(dateNow);

        // Create audit log
        AuditLogUtil.create(fileDb, AuditLogType.DELETE, userId);
    }

    /**
     * Atomically demote the expected current-latest version of a chain as a compare-and-swap: clears the
     * latest flag on the row IFF it is still the current active latest, and stamps the resolved version-chain
     * id in the same statement (idempotent when already set). The affected-row count is the stale-base
     * signal: a returned 0 means the named base is no longer the current latest (a concurrent writer already
     * replaced or deleted it), and the caller must NOT insert a successor. The single {@code executeUpdate}
     * takes a row write lock, so two concurrent replacements of the same base serialize and exactly one
     * observes 1.
     *
     * @param expectedLatestId ID of the predecessor the caller believes is the current latest version
     * @param versionId Resolved version-chain id to stamp on the predecessor (and share with the successor)
     * @return the number of rows updated: 1 when the swap succeeded, 0 when the base was stale
     */
    public int demoteCurrentLatestVersion(String expectedLatestId, String versionId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("update File f set f.latestVersion = false, f.versionId = :versionId" +
                " where f.id = :id and f.latestVersion = true and f.deleteDate is null");
        q.setParameter("versionId", versionId);
        q.setParameter("id", expectedLatestId);
        return q.executeUpdate();
    }

    /**
     * Promote the immediately-prior active version of a just-deleted current-latest row to latest, retrying
     * across predecessors a concurrent delete removes underneath us.
     *
     * <p>Deleting the current latest of {@code v0 -> v1 -> v2} promotes {@code v1}. But a concurrent
     * {@code delete(v1)} (v1 is non-latest, so ITS side does not promote) may commit between this method
     * selecting {@code v1} as the candidate and its promotion UPDATE — the UPDATE then affects 0 rows. Giving
     * up there would leave the chain with ZERO latest rows. Instead, on a 0-affected promotion, re-select the
     * next active version below the deleted one and retry. The active-version set only shrinks (each failed
     * candidate is now committed-deleted), so this terminates; when no active version remains the chain is
     * legitimately empty (the file was fully deleted) and there is nothing to promote.</p>
     *
     * @param deletedId ID of the row that was the current latest and has just been soft-deleted
     */
    private void promotePriorVersion(String deletedId) {
        File deleted = ThreadLocalContext.get().getEntityManager()
                .createQuery("select f from File f where f.id = :id", File.class)
                .setParameter("id", deletedId)
                .getSingleResult();
        String versionId = deleted.getVersionId();
        if (versionId == null) {
            // Single-version file (no chain): nothing to promote.
            return;
        }
        Integer deletedVersion = deleted.getVersion();
        while (true) {
            File candidate = getHighestActiveVersionBelow(versionId, deletedVersion);
            if (candidate == null) {
                // No active version remains: the chain is legitimately empty.
                return;
            }
            int promoted = ThreadLocalContext.get().getEntityManager().createQuery(
                            "update File f set f.latestVersion = true where f.id = :id and f.deleteDate is null")
                    .setParameter("id", candidate.getId())
                    .executeUpdate();
            if (promoted > 0) {
                return;
            }
            // The candidate was concurrently deleted between selection and promotion; re-select the
            // next-lower active version and retry.
        }
    }

    /**
     * Find the highest-versioned still-active version below {@code version} within a chain, or null when
     * none remains.
     *
     * @param versionId Version-chain id
     * @param version Exclusive upper bound (the deleted row's version)
     * @return the highest active version below {@code version}, or null
     */
    private File getHighestActiveVersionBelow(String versionId, Integer version) {
        List<File> prior = ThreadLocalContext.get().getEntityManager().createQuery(
                        "select f from File f where f.versionId = :versionId" +
                        " and f.deleteDate is null and f.version < :version order by f.version desc", File.class)
                .setParameter("versionId", versionId)
                .setParameter("version", version)
                .setMaxResults(1)
                .getResultList();
        return prior.isEmpty() ? null : prior.get(0);
    }

    /**
     * Update a file.
     * 
     * @param file File to update
     * @return Updated file
     */
    public File update(File file) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        
        // Get the file
        TypedQuery<File> q = em.createQuery("select f from File f where f.id = :id and f.deleteDate is null", File.class);
        q.setParameter("id", file.getId());
        File fileDb = q.getSingleResult();

        // Update the file. The version-chain identity columns (FIL_IDVERSION_C, FIL_LATESTVERSION_B) are
        // deliberately NOT copied here: they may change ONLY through the compare-and-swap paths
        // (new-version create and current-version delete). Copying them from a caller's possibly-stale
        // entity would let a rename/processing update that loaded a row as latest, then paused across a
        // concurrent version-create that demoted it, resurrect latestVersion=true and yield two latest rows.
        fileDb.setDocumentId(file.getDocumentId());
        fileDb.setName(file.getName());
        fileDb.setContent(file.getContent());
        fileDb.setOrder(file.getOrder());
        fileDb.setMimeType(file.getMimeType());
        fileDb.setSize(file.getSize());
        fileDb.setRotation(file.getRotation());

        return file;
    }

    /**
     * Gets a file by its ID.
     * 
     * @param id File ID
     * @return File
     */
    public File getActiveById(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f where f.id = :id and f.deleteDate is null", File.class);
        q.setParameter("id", id);
        try {
            return q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }
    
    /**
     * Get files by document ID or all orphan files of a user.
     * 
     * @param userId User ID
     * @param documentId Document ID
     * @return List of files
     */
    public List<File> getByDocumentId(String userId, String documentId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        if (documentId == null) {
            TypedQuery<File> q = em.createQuery("select f from File f where f.documentId is null and f.deleteDate is null and f.latestVersion = true and f.userId = :userId order by f.createDate asc", File.class);
            q.setParameter("userId", userId);
            return q.getResultList();
        } else {
            return getByDocumentsIds(Collections.singleton(documentId));
        }
    }

    /**
     * Get files by documents IDs.
     *
     * @param documentIds Documents IDs
     * @return List of files
     */
    public List<File> getByDocumentsIds(Iterable<String> documentIds) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f where f.documentId in :documentIds and f.latestVersion = true and f.deleteDate is null order by f.order asc", File.class);
        q.setParameter("documentIds", documentIds);
        return q.getResultList();
    }

    /**
     * Get all files by document ID, including soft-deleted files.
     * Used for permanent deletion to clean up file storage.
     *
     * @param documentId Document ID
     * @return List of files
     */
    public List<File> getAllByDocumentId(String documentId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f where f.documentId = :documentId", File.class);
        q.setParameter("documentId", documentId);
        return q.getResultList();
    }

    /**
     * Get files count by documents IDs.
     *
     * @param documentIds Documents IDs
     * @return the number of files per document id
     */
    public Map<String, Long> countByDocumentsIds(Iterable<String> documentIds) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select f.documentId, count(*) from File f where f.documentId in :documentIds and f.latestVersion = true and f.deleteDate is null group by (f.documentId)");
        q.setParameter("documentIds", documentIds);
        Map<String, Long> result = new HashMap<>();
        q.getResultList().forEach(o -> {
            Object[] resultLine = (Object[]) o;
            result.put((String) resultLine[0], (Long) resultLine[1]);
        });
        return result;
    }

    /**
     * Get all files from a version.
     *
     * @param versionId Version ID
     * @return List of files
     */
    public List<File> getByVersionId(String versionId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f where f.versionId = :versionId and f.deleteDate is null order by f.order asc", File.class);
        q.setParameter("versionId", versionId);
        return q.getResultList();
    }

    public List<File> getFilesWithUnknownSize(int limit) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<File> q = em.createQuery("select f from File f where f.size = :size and f.deleteDate is null order by f.order asc", File.class);
        q.setParameter("size", File.UNKNOWN_SIZE);
        q.setMaxResults(limit);
        return q.getResultList();
    }
}
