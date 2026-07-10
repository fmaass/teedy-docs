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
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
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
                String targetId = SecurityUtil.getTargetIdFromName(targetName, targetType);
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
