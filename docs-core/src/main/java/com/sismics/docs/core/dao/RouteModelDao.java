package com.sismics.docs.core.dao;

import com.google.common.base.Joiner;
import com.sismics.docs.core.constant.AclTargetType;
import com.sismics.docs.core.constant.AuditLogType;
import com.sismics.docs.core.dao.criteria.RouteModelCriteria;
import com.sismics.docs.core.dao.dto.RouteModelDto;
import com.sismics.docs.core.model.jpa.RouteModel;
import com.sismics.docs.core.util.AuditLogUtil;
import com.sismics.docs.core.util.SecurityUtil;
import com.sismics.docs.core.util.jpa.QueryParam;
import com.sismics.docs.core.util.jpa.QueryUtil;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.io.StringReader;
import java.sql.Timestamp;
import java.util.*;

/**
 * Route model DAO.
 *
 * @author bgamard
 */
public class RouteModelDao {
    /**
     * Creates a new route model.
     *
     * @param routeModel Route model
     * @param userId User ID
     * @return New ID
     */
    public String create(RouteModel routeModel, String userId) {
        // Create the UUID
        routeModel.setId(UUID.randomUUID().toString());

        // Create the route model
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        routeModel.setCreateDate(new Date());
        em.persist(routeModel);

        // Keep the derived principal->model index in sync
        syncTargets(routeModel.getId(), routeModel.getSteps());

        // Create audit log
        AuditLogUtil.create(routeModel, AuditLogType.CREATE, userId);

        return routeModel.getId();
    }

    /**
     * Update a route model.
     *
     * @param routeModel Route model to update
     * @param userId User ID
     * @return Updated route model
     */
    public RouteModel update(RouteModel routeModel, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        // Get the route model
        Query q = em.createQuery("select r from RouteModel r where r.id = :id and r.deleteDate is null");
        q.setParameter("id", routeModel.getId());
        RouteModel routeModelDb = (RouteModel) q.getSingleResult();

        // Update the route model
        routeModelDb.setName(routeModel.getName());
        routeModelDb.setSteps(routeModel.getSteps());

        // Keep the derived principal->model index in sync
        syncTargets(routeModelDb.getId(), routeModelDb.getSteps());

        // Create audit log
        AuditLogUtil.create(routeModelDb, AuditLogType.UPDATE, userId);

        return routeModelDb;
    }

    /**
     * Gets an active route model by its ID.
     *
     * @param id Route model ID
     * @return Route model
     */
    public RouteModel getActiveById(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        try {
            Query q = em.createQuery("select r from RouteModel r where r.id = :id and r.deleteDate is null");
            q.setParameter("id", id);
            return (RouteModel) q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Gets an active route model by its ID, acquiring a pessimistic write lock (SELECT ... FOR UPDATE,
     * dialect-portable via LockModeType.PESSIMISTIC_WRITE) on the row for the remainder of the
     * caller's transaction. Used by the group-rename repair path to serialize its read-modify-write of
     * the {@code RTM_STEPS_C} blob against a concurrent route-model update (which locks the referenced
     * GROUP rows first, so the two orders compose into the group-first protocol).
     *
     * @param id Route model ID
     * @return The locked active route model, or null if it does not exist or is deleted
     */
    public RouteModel getActiveByIdForUpdate(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        TypedQuery<RouteModel> q = em.createQuery("select r from RouteModel r where r.id = :id and r.deleteDate is null", RouteModel.class);
        q.setParameter("id", id);
        q.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        try {
            return q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Overwrites only the steps blob of a route model (and re-syncs its derived target index),
     * without touching its name. Used by the group-rename repair path, which has already locked the
     * row via {@link #getActiveByIdForUpdate}. No audit log is written here — the enclosing group
     * rename is the audited operation.
     *
     * @param id Route model ID
     * @param steps New steps JSON blob
     */
    public void updateSteps(String id, String steps) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select r from RouteModel r where r.id = :id and r.deleteDate is null");
        q.setParameter("id", id);
        RouteModel routeModelDb = (RouteModel) q.getSingleResult();
        routeModelDb.setSteps(steps);
        syncTargets(routeModelDb.getId(), steps);
    }

    /**
     * Returns the list of all route models.
     *
     * @return List of route models
     */
    @SuppressWarnings("unchecked")
    public List<RouteModel> findAll() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select r from RouteModel r where r.deleteDate is null");
        return q.getResultList();
    }

    /**
     * Deletes a route model.
     *
     * @param id Route model ID
     * @param userId User ID
     */
    public void delete(String id, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        // Get the route model
        Query q = em.createQuery("select r from RouteModel r where r.id = :id and r.deleteDate is null");
        q.setParameter("id", id);
        RouteModel routeModelDb = (RouteModel) q.getSingleResult();

        // Delete the route model
        Date dateNow = new Date();
        routeModelDb.setDeleteDate(dateNow);

        // Drop the derived index rows for this model
        clearTargets(id);

        // Create audit log
        AuditLogUtil.create(routeModelDb, AuditLogType.DELETE, userId);
    }

    /**
     * Returns the list of all route models.
     *
     * @param criteria Search criteria
     * @param sortCriteria Sort criteria
     * @return List of route models
     */
    public List<RouteModelDto> findByCriteria(RouteModelCriteria criteria, SortCriteria sortCriteria) {
        Map<String, Object> parameterMap = new HashMap<String, Object>();
        List<String> criteriaList = new ArrayList<>();

        StringBuilder sb = new StringBuilder("select rm.RTM_ID_C c0, rm.RTM_NAME_C c1, rm.RTM_CREATEDATE_D c2");
        sb.append(" from T_ROUTE_MODEL rm ");

        // Add search criterias
        if (criteria.getTargetIdList() != null && !SecurityUtil.skipAclCheck(criteria.getTargetIdList())) {
            sb.append(" left join T_ACL a on a.ACL_TARGETID_C in (:targetIdList) and a.ACL_SOURCEID_C = rm.RTM_ID_C and a.ACL_PERM_C = 'READ' and a.ACL_DELETEDATE_D is null ");
            criteriaList.add("a.ACL_ID_C is not null");
            parameterMap.put("targetIdList", criteria.getTargetIdList());
        }

        criteriaList.add("rm.RTM_DELETEDATE_D is null");

        sb.append(" where ");
        sb.append(Joiner.on(" and ").join(criteriaList));

        // Perform the search
        QueryParam queryParam = QueryUtil.getSortedQueryParam(new QueryParam(sb.toString(), parameterMap), sortCriteria);
        @SuppressWarnings("unchecked")
        List<Object[]> l = QueryUtil.getNativeQuery(queryParam).getResultList();

        // Assemble results
        List<RouteModelDto> dtoList = new ArrayList<>();
        for (Object[] o : l) {
            int i = 0;
            RouteModelDto dto = new RouteModelDto();
            dto.setId((String) o[i++]);
            dto.setName((String) o[i++]);
            dto.setCreateTimestamp(((Timestamp) o[i++]).getTime());
            dtoList.add(dto);
        }
        return dtoList;
    }

    /**
     * Returns the IDs of the non-deleted route models that reference a given principal (user or
     * group) as a step target. Reads the derived index table only. Used by the (later) principal
     * deletion guard.
     *
     * @param targetId Principal ID (user or group)
     * @return Distinct list of referencing route model IDs
     */
    @SuppressWarnings("unchecked")
    public List<String> findModelsReferencingTarget(String targetId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createNativeQuery("select distinct t.RMT_IDROUTEMODEL_C from T_ROUTE_MODEL_TARGET t " +
                " join T_ROUTE_MODEL rm on rm.RTM_ID_C = t.RMT_IDROUTEMODEL_C and rm.RTM_DELETEDATE_D is null " +
                " where t.RMT_IDTARGET_C = :targetId");
        q.setParameter("targetId", targetId);
        return q.getResultList();
    }

    /**
     * Returns the IDs of the non-deleted route models that are "incomplete": at least one step of their
     * stored blob has a target that no longer resolves to an active principal. Used by the LIST endpoint
     * to flag models that can no longer be started faithfully.
     *
     * <p><b>Derived from the blob, through the resolver the start itself uses</b>
     * ({@link SecurityUtil#getRouteTargetIdFromName}) — deliberately NOT from the derived
     * {@code T_ROUTE_MODEL_TARGET} index (#312). The index is keyed on the principal ID while the start
     * resolves the blob's NAME, and the two keys can disagree in both directions: a legacy blob naming a
     * renamed group by what is now only its id keeps an index row (so the id-keyed query called it
     * complete) while the name lookup failed, and a blob target that resolves to nothing produces no
     * index row at all (so the id-keyed query, seeing no row, also called it complete). Evaluating the
     * blob makes the flag mean exactly "this model can be started", which is the contract the UI relies
     * on when it offers the model.</p>
     *
     * <p>The model count is small (admin-authored workflows), so the per-model blob parse this costs is
     * paid on a list of a handful of rows.</p>
     *
     * @return Distinct list of incomplete route model IDs
     */
    public List<String> findIncompleteModelIds() {
        List<String> incompleteIdList = new ArrayList<>();
        for (RouteModel routeModel : findAll()) {
            if (!allStepTargetsResolve(routeModel.getSteps())) {
                incompleteIdList.add(routeModel.getId());
            }
        }
        return incompleteIdList;
    }

    /**
     * True when EVERY step of the blob has a target that resolves to an active principal — i.e. when
     * {@code RouteResource.resolveSteps} would accept the model. A blob that is empty, malformed, or
     * carries an unknown target type is NOT startable, so it answers false rather than throwing: this
     * runs on a read path (the model list) that must never fail on one bad row.
     *
     * @param steps Steps JSON blob
     * @return true if every step target resolves
     */
    private boolean allStepTargetsResolve(String steps) {
        if (steps == null || steps.isEmpty()) {
            return false;
        }
        try (JsonReader reader = Json.createReader(new StringReader(steps))) {
            JsonArray stepsJson = reader.readArray();
            if (stepsJson.isEmpty()) {
                return false;
            }
            for (int i = 0; i < stepsJson.size(); i++) {
                JsonObject target = stepsJson.getJsonObject(i).getJsonObject("target");
                if (target == null) {
                    return false;
                }
                AclTargetType targetType = AclTargetType.valueOf(target.getString("type"));
                if (SecurityUtil.getRouteTargetIdFromName(target.getString("name"), targetType) == null) {
                    return false;
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        return true;
    }

    /**
     * Rebuild the derived index rows for a route model from its steps JSON blob: drop the existing
     * rows and re-insert one per step target that resolves to a live principal. Runs in the same
     * transaction as the model create/update.
     *
     * @param routeModelId Route model ID
     * @param steps Steps JSON blob
     */
    private void syncTargets(String routeModelId, String steps) {
        clearTargets(routeModelId);

        if (steps == null || steps.isEmpty()) {
            return;
        }

        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Date dateNow = new Date();
        try (JsonReader reader = Json.createReader(new StringReader(steps))) {
            JsonArray stepsJson = reader.readArray();
            for (int i = 0; i < stepsJson.size(); i++) {
                JsonObject step = stepsJson.getJsonObject(i);
                JsonObject target = step.getJsonObject("target");
                if (target == null) {
                    continue;
                }
                AclTargetType targetType = AclTargetType.valueOf(target.getString("type"));
                String targetName = target.getString("name");
                // The route-model resolver (name, then a GROUP's id) — the same one the start and the
                // write gate use, so a legacy blob naming a renamed group by its id still indexes (#312).
                String targetId = SecurityUtil.getRouteTargetIdFromName(targetName, targetType);
                if (targetId == null) {
                    continue;
                }
                em.createNativeQuery("insert into T_ROUTE_MODEL_TARGET (RMT_ID_C, RMT_IDROUTEMODEL_C, RMT_IDTARGET_C, RMT_TYPE_C, RMT_CREATEDATE_D) values (:id, :routeModelId, :targetId, :type, :createDate)")
                        .setParameter("id", UUID.randomUUID().toString())
                        .setParameter("routeModelId", routeModelId)
                        .setParameter("targetId", targetId)
                        .setParameter("type", targetType.name())
                        .setParameter("createDate", dateNow)
                        .executeUpdate();
            }
        }
    }

    /**
     * Delete all derived index rows for a route model.
     *
     * @param routeModelId Route model ID
     */
    private void clearTargets(String routeModelId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.createNativeQuery("delete from T_ROUTE_MODEL_TARGET where RMT_IDROUTEMODEL_C = :routeModelId")
                .setParameter("routeModelId", routeModelId)
                .executeUpdate();
    }
}
