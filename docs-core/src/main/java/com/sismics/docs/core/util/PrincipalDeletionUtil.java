package com.sismics.docs.core.util;

import com.sismics.docs.core.constant.AuditLogType;
import com.sismics.docs.core.constant.RouteStatus;
import com.sismics.docs.core.dao.AuditLogDao;
import com.sismics.docs.core.dao.RouteDao;
import com.sismics.docs.core.dao.RouteModelDao;
import com.sismics.docs.core.dao.RouteStepDao;
import com.sismics.docs.core.model.jpa.AuditLog;
import com.sismics.docs.core.model.jpa.Route;
import com.sismics.docs.core.model.jpa.RouteModel;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Graceful handling of workflow references when a principal (user or group) is deleted or renamed.
 * <p>
 * Deleting or renaming a principal that a workflow refers to never blocks (decision 6): route
 * models that reference the principal as a step target keep their (now unresolvable) blob and
 * simply become unstartable; active routes with an OPEN step targeting the principal are cancelled
 * so they cannot hang forever with an unreachable assignee.
 * <p>
 * This util owns no email/notification plumbing on purpose: the notice surface is the returned
 * warning payload (route models affected) plus the per-route audit log and the CANCELLED status
 * the route history exposes.
 *
 * @author teedy
 */
public class PrincipalDeletionUtil {
    /**
     * The ACL type string used by the workflow subsystem for the transient READ ACL granted to the
     * current step's target. Referenced as a literal (not via the AclType enum) so this util does
     * not depend on the workflow REST layer re-introducing the enum value.
     */
    private static final String ACL_TYPE_ROUTING = "ROUTING";

    /**
     * Returns the names of the non-deleted route models that reference a principal as a step target.
     * These models become unstartable once the principal is gone (their target no longer resolves);
     * their step blob is intentionally left untouched.
     *
     * @param principalId Principal ID (user or group)
     * @return Names of the affected route models (may be empty, never null)
     */
    @SuppressWarnings("unchecked")
    public static List<String> findAffectedRouteModelNames(String principalId) {
        List<String> modelIdList = new RouteModelDao().findModelsReferencingTarget(principalId);
        List<String> names = new ArrayList<>();
        if (modelIdList.isEmpty()) {
            return names;
        }
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select r.name from RouteModel r where r.id in :ids and r.deleteDate is null");
        q.setParameter("ids", modelIdList);
        for (Object name : q.getResultList()) {
            names.add((String) name);
        }
        return names;
    }

    /**
     * Cancels every active route that has an OPEN step targeting the given principal. For each such
     * route: the route is ended as CANCELLED, all still-open steps are closed with a system comment,
     * the transient ROUTING READ ACL granted to the (now deleted) principal on the route's document
     * is removed, and a route audit log entry is written. Idempotent per route (a route is handled
     * once even if several of its open steps target the principal).
     *
     * @param principalId Principal ID (user or group)
     * @param userId User performing the deletion (audit attribution)
     * @return Number of routes cancelled
     */
    public static int cancelRoutesTargetingPrincipal(String principalId, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        // Distinct active routes (and their document) that have an OPEN step targeting the principal.
        Query q = em.createNativeQuery("select distinct r.RTE_ID_C, r.RTE_IDDOCUMENT_C from T_ROUTE r " +
                " join T_ROUTE_STEP rs on rs.RTP_IDROUTE_C = r.RTE_ID_C " +
                " where rs.RTP_IDTARGET_C = :principalId " +
                " and rs.RTP_ENDDATE_D is null and rs.RTP_DELETEDATE_D is null " +
                " and r.RTE_STATUS_C = :activeStatus and r.RTE_DELETEDATE_D is null");
        q.setParameter("principalId", principalId);
        q.setParameter("activeStatus", RouteStatus.ACTIVE.name());
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();

        RouteDao routeDao = new RouteDao();
        RouteStepDao routeStepDao = new RouteStepDao();
        int cancelled = 0;
        for (Object[] row : rows) {
            String routeId = (String) row[0];
            String documentId = (String) row[1];

            // Halt the route and close its open steps with a system comment. The NULL transition
            // marks them system-ended: no actor rejected/approved anything (orchestrator-wide
            // standard for system-ended steps).
            routeDao.endRoute(routeId, RouteStatus.CANCELLED);
            routeStepDao.endAllOpenSteps(routeId, null, "Cancelled: step target deleted");

            // Remove the transient routing READ ACL granted to the deleted principal on the document.
            deleteRoutingAcl(documentId, principalId, userId);

            // Audit the cancellation on the route entity (matches RouteDao's audit idioms).
            Route route = em.find(Route.class, routeId);
            if (route != null) {
                AuditLogUtil.create(route, AuditLogType.UPDATE, userId);
            }

            cancelled++;
        }
        return cancelled;
    }

    /**
     * Cancels every ACTIVE route on a document (used when the document is moved to trash). For each
     * such route: the route is ended as CANCELLED, all still-open steps are closed with a system
     * comment and a NULL transition (system-ended: nobody acted), and a route audit log entry is
     * written. The transient ROUTING READ ACLs the routes granted on the document are soft-deleted
     * (with per-ACL DELETE audit rows) with a timestamp intentionally distinct from the document's
     * own delete timestamp, so a later restore-from-trash — which un-deletes the document's own ACLs
     * by matching that timestamp — does NOT resurrect the routing ACL of a cancelled route.
     *
     * @param documentId Document ID
     * @param userId User performing the deletion (audit attribution)
     * @param documentDeleteDate The document's own soft-delete timestamp; the ROUTING-ACL delete
     *        timestamp is derived deterministically from it (documentDeleteDate - 1ms) so the two are
     *        guaranteed distinct and restore-from-trash cannot resurrect the routing ACL
     * @return Number of routes cancelled
     */
    public static int cancelActiveRoutesForDocument(String documentId, String userId, Date documentDeleteDate) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        Query q = em.createNativeQuery("select r.RTE_ID_C from T_ROUTE r " +
                " where r.RTE_IDDOCUMENT_C = :documentId " +
                " and r.RTE_STATUS_C = :activeStatus and r.RTE_DELETEDATE_D is null");
        q.setParameter("documentId", documentId);
        q.setParameter("activeStatus", RouteStatus.ACTIVE.name());
        @SuppressWarnings("unchecked")
        List<String> routeIdList = q.getResultList();

        RouteDao routeDao = new RouteDao();
        RouteStepDao routeStepDao = new RouteStepDao();
        int cancelled = 0;
        for (String routeId : routeIdList) {
            routeDao.endRoute(routeId, RouteStatus.CANCELLED);
            routeStepDao.endAllOpenSteps(routeId, null, "Cancelled: document moved to trash");

            Route route = em.find(Route.class, routeId);
            if (route != null) {
                AuditLogUtil.create(route, AuditLogType.UPDATE, userId);
            }
            cancelled++;
        }

        // Soft-delete every ROUTING ACL on the document (target-agnostic) with a timestamp distinct
        // from the document's own delete timestamp so restore-from-trash cannot resurrect it. Done
        // unconditionally when at least one route was cancelled: an ACTIVE route always granted a
        // routing ACL to its current step's target.
        if (cancelled > 0) {
            deleteRoutingAclsForDocument(documentId, userId, documentDeleteDate);
        }
        return cancelled;
    }

    /**
     * Soft-delete ALL transient workflow ("ROUTING") READ ACLs on a document (regardless of target),
     * writing a DELETE audit log for each. The delete timestamp is derived DETERMINISTICALLY from the
     * document's own delete timestamp (documentDeleteDate - 1ms) — never sampled from the clock
     * independently — so it is guaranteed distinct from documentDeleteDate. restore() un-deletes the
     * document's ACLs by exact-equality match on documentDeleteDate, so a distinct timestamp is what
     * keeps a cancelled route's ACL from being resurrected. Sampling the clock here would risk the two
     * timestamps colliding (sub-millisecond cancellation / coarse clock resolution).
     *
     * @param documentId Document (ACL source) ID
     * @param userId User performing the deletion (audit attribution)
     * @param documentDeleteDate The document's own soft-delete timestamp
     */
    @SuppressWarnings("unchecked")
    private static void deleteRoutingAclsForDocument(String documentId, String userId, Date documentDeleteDate) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        Query q = em.createNativeQuery("select ACL_ID_C, ACL_PERM_C from T_ACL " +
                " where ACL_SOURCEID_C = :sourceId and ACL_TYPE_C = :type and ACL_DELETEDATE_D is null");
        q.setParameter("sourceId", documentId);
        q.setParameter("type", ACL_TYPE_ROUTING);
        List<Object[]> aclRows = q.getResultList();
        if (aclRows.isEmpty()) {
            return;
        }

        AuditLogDao auditLogDao = new AuditLogDao();
        for (Object[] aclRow : aclRows) {
            AuditLog auditLog = new AuditLog();
            auditLog.setUserId(userId == null ? "admin" : userId);
            auditLog.setEntityId((String) aclRow[0]);
            auditLog.setEntityClass("Acl");
            auditLog.setType(AuditLogType.DELETE);
            auditLog.setMessage((String) aclRow[1]);
            auditLogDao.create(auditLog);
        }

        // Deterministic offset from the document's delete timestamp (never a fresh clock sample).
        Date routingAclDeleteDate = new Date(documentDeleteDate.getTime() - 1L);
        Query upd = em.createNativeQuery("update T_ACL set ACL_DELETEDATE_D = :dateNow " +
                " where ACL_SOURCEID_C = :sourceId and ACL_TYPE_C = :type and ACL_DELETEDATE_D is null");
        upd.setParameter("dateNow", routingAclDeleteDate);
        upd.setParameter("sourceId", documentId);
        upd.setParameter("type", ACL_TYPE_ROUTING);
        upd.executeUpdate();
    }

    /**
     * Soft-delete the transient workflow ("ROUTING") READ ACLs on a document for a given target,
     * writing a DELETE audit log for each (mirroring the AclDao delete idiom). Uses a literal type
     * string rather than the AclType enum because the routing enum value is owned by the workflow
     * REST layer — which also means a ROUTING row cannot be loaded as an Acl entity (enum mapping
     * would fail), so the audit rows are written via AuditLogDao with the exact fields
     * AuditLogUtil.create would produce (entityClass "Acl", message = perm name).
     *
     * @param documentId Document (ACL source) ID
     * @param targetId Target principal ID
     * @param userId User performing the deletion (audit attribution)
     */
    @SuppressWarnings("unchecked")
    private static void deleteRoutingAcl(String documentId, String targetId, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();

        // Audit each ACL row before soft-deleting it.
        Query q = em.createNativeQuery("select ACL_ID_C, ACL_PERM_C from T_ACL " +
                " where ACL_SOURCEID_C = :sourceId and ACL_TARGETID_C = :targetId " +
                " and ACL_TYPE_C = :type and ACL_DELETEDATE_D is null");
        q.setParameter("sourceId", documentId);
        q.setParameter("targetId", targetId);
        q.setParameter("type", ACL_TYPE_ROUTING);
        List<Object[]> aclRows = q.getResultList();
        if (aclRows.isEmpty()) {
            return;
        }

        AuditLogDao auditLogDao = new AuditLogDao();
        for (Object[] aclRow : aclRows) {
            AuditLog auditLog = new AuditLog();
            auditLog.setUserId(userId == null ? "admin" : userId);
            auditLog.setEntityId((String) aclRow[0]);
            auditLog.setEntityClass("Acl");
            auditLog.setType(AuditLogType.DELETE);
            auditLog.setMessage((String) aclRow[1]);
            auditLogDao.create(auditLog);
        }

        Query upd = em.createNativeQuery("update T_ACL set ACL_DELETEDATE_D = :dateNow " +
                " where ACL_SOURCEID_C = :sourceId and ACL_TARGETID_C = :targetId " +
                " and ACL_TYPE_C = :type and ACL_DELETEDATE_D is null");
        upd.setParameter("dateNow", new Date());
        upd.setParameter("sourceId", documentId);
        upd.setParameter("targetId", targetId);
        upd.setParameter("type", ACL_TYPE_ROUTING);
        upd.executeUpdate();
    }
}
