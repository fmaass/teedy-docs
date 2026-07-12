package com.sismics.docs.core.dao;

import com.sismics.docs.core.dao.dto.UserStorageDto;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * Read-only aggregate queries backing the admin statistics dashboard.
 *
 * <p>The date-series methods return RAW per-row timestamps for a half-open
 * {@code [start, end)} range; bucketing into UTC calendar days is done in Java by
 * {@link com.sismics.docs.core.util.StatsBucketUtil} so the SQL stays dialect-portable
 * across H2 and PostgreSQL (no engine-specific date-truncation functions).
 */
public class StatsDao {
    /**
     * Audit-log entity classes counted by the activity series. These are the simple class
     * names stored in {@code T_AUDIT_LOG.LOG_CLASSENTITY_C} (AuditLogUtil stores
     * {@code getSimpleName()}). The set is fixed and must not be broadened.
     */
    private static final List<String> ACTIVITY_ENTITY_CLASSES =
            Arrays.asList("Document", "File", "Comment", "Route", "Tag");

    /**
     * Returns the number of non-deleted files, INCLUDING historical (non-latest) versions.
     *
     * @return File count
     */
    public long getFileCount() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query query = em.createNativeQuery("select count(f.FIL_ID_C) from T_FILE f where f.FIL_DELETEDATE_D is null");
        return ((Number) query.getSingleResult()).longValue();
    }

    /**
     * Returns the number of non-deleted users, INCLUDING disabled ones (they still hold storage).
     *
     * @return User count
     */
    public long getUserCount() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query query = em.createNativeQuery("select count(u.USE_ID_C) from T_USER u where u.USE_DELETEDATE_D is null");
        return ((Number) query.getSingleResult()).longValue();
    }

    /**
     * Returns the number of non-deleted tags.
     *
     * @return Tag count
     */
    public long getTagCount() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query query = em.createNativeQuery("select count(t.TAG_ID_C) from T_TAG t where t.TAG_DELETEDATE_D is null");
        return ((Number) query.getSingleResult()).longValue();
    }

    /**
     * Returns the raw number of favorite rows (aggregate only — no per-user breakdown, per the
     * #41 privacy statement).
     *
     * @return Favorite row count
     */
    public long getFavoriteCount() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query query = em.createNativeQuery("select count(f.FAV_ID_C) from T_FAVORITE f");
        return ((Number) query.getSingleResult()).longValue();
    }

    /**
     * Returns the top-N non-deleted users by current storage, ordered by storage current
     * descending then username ascending (deterministic under ties).
     *
     * @param limit Maximum number of rows to return
     * @return Per-user storage rows
     */
    public List<UserStorageDto> getTopUserStorage(int limit) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query query = em.createNativeQuery("select u.USE_USERNAME_C, u.USE_STORAGECURRENT_N, u.USE_STORAGEQUOTA_N" +
                " from T_USER u where u.USE_DELETEDATE_D is null" +
                " order by u.USE_STORAGECURRENT_N desc, u.USE_USERNAME_C asc");
        query.setMaxResults(limit);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<UserStorageDto> result = new ArrayList<>();
        for (Object[] o : rows) {
            result.add(new UserStorageDto(
                    (String) o[0],
                    ((Number) o[1]).longValue(),
                    ((Number) o[2]).longValue()));
        }
        return result;
    }

    /**
     * Returns the creation timestamps of non-deleted documents created in {@code [start, end)},
     * bucketed later in Java. Buckets on {@code DOC_CREATEDATE_D} — the document's recorded
     * (client-suppliable) create date, NOT an audit CREATE event.
     *
     * @param start Inclusive lower bound
     * @param end Exclusive upper bound
     * @return Document create timestamps in range
     */
    public List<Date> getDocumentCreatedDates(Date start, Date end) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query query = em.createNativeQuery("select d.DOC_CREATEDATE_D from T_DOCUMENT d" +
                " where d.DOC_DELETEDATE_D is null and d.DOC_CREATEDATE_D >= :start and d.DOC_CREATEDATE_D < :end");
        query.setParameter("start", start);
        query.setParameter("end", end);
        return toDateList(query.getResultList());
    }

    /**
     * Returns the creation timestamps of activity audit-log entries in {@code [start, end)},
     * bucketed later in Java. Counts every CRUD type whose entity class is in the fixed
     * {@link #ACTIVITY_ENTITY_CLASSES} set.
     *
     * <p>Reflects RETAINED audit rows only: {@code clean_storage} hard-deletes orphan audit logs
     * (and, by a known pre-existing defect, wholesale-purges Route audit entries because it does
     * not join T_ROUTE), so purged history is not represented here.
     *
     * @param start Inclusive lower bound
     * @param end Exclusive upper bound
     * @return Audit-log create timestamps in range
     */
    public List<Date> getActivityDates(Date start, Date end) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query query = em.createNativeQuery("select l.LOG_CREATEDATE_D from T_AUDIT_LOG l" +
                " where l.LOG_CLASSENTITY_C in (:classes) and l.LOG_CREATEDATE_D >= :start and l.LOG_CREATEDATE_D < :end");
        query.setParameter("classes", ACTIVITY_ENTITY_CLASSES);
        query.setParameter("start", start);
        query.setParameter("end", end);
        return toDateList(query.getResultList());
    }

    /**
     * Normalises a native-query result list of date-typed rows into {@code java.util.Date}
     * values. JDBC hands date columns back as {@code java.sql.Timestamp} (a Date subclass) on
     * both engines; this keeps callers from depending on the exact runtime type.
     */
    @SuppressWarnings("unchecked")
    private static List<Date> toDateList(List<?> rows) {
        List<Date> result = new ArrayList<>();
        for (Object o : (List<Object>) rows) {
            if (o == null) {
                continue;
            }
            if (o instanceof Timestamp timestamp) {
                result.add(new Date(timestamp.getTime()));
            } else if (o instanceof Date date) {
                result.add(new Date(date.getTime()));
            }
        }
        return result;
    }
}
