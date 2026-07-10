package com.sismics.docs.core.util;

import com.sismics.docs.core.constant.AuditLogType;
import com.sismics.docs.core.constant.RouteStatus;
import com.sismics.docs.core.dao.AuditLogDao;
import com.sismics.docs.core.dao.DocumentDao;
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
        DocumentDao documentDao = new DocumentDao();
        int cancelled = 0;
        for (Object[] row : rows) {
            String routeId = (String) row[0];
            String documentId = (String) row[1];

            // GLOBAL LOCK ORDER: DOCUMENT before ROUTE. Lock the route's document row FOR UPDATE
            // before any route/step UPDATE below. Every other doc-vs-route lock site takes the same
            // order (DocumentDao.delete and RouteResource.start lock the document first, then touch
            // routes), so no path can hold a route lock while waiting on a document lock — the
            // deadlock cycle is eliminated. lockByIdForUpdate (not getActiveByIdForUpdate) is used
            // because the route's document may already be trashed in the target-cancel scenario, and
            // the row must be locked regardless of its deleteDate.
            documentDao.lockByIdForUpdate(documentId);

            // Halt the route and close its open steps with a system comment. The NULL transition
            // marks them system-ended: no actor rejected/approved anything (orchestrator-wide
            // standard for system-ended steps).
            routeDao.endRoute(routeId, RouteStatus.CANCELLED);
            routeStepDao.endAllOpenSteps(routeId, null, "Cancelled: step target deleted");

            // Remove ALL of the document's transient ROUTING READ ACLs (target-agnostic), not just
            // the deleted principal's. The deleted principal may own a FUTURE step (all steps are
            // created unended, so the OPEN-step selection above matches future steps too) while a
            // DIFFERENT principal holds the CURRENT step's ROUTING grant. A per-principal delete
            // would cancel the route yet leave the current approver's grant on a now-terminal route
            // (an authz leak). The invariant is the same one the in-place cancel enforces: a
            // cancelled route leaves ZERO ROUTING ACLs on its document.
            deleteAllRoutingAclsForDocument(documentId, userId);

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

        // Restore-safety normalization for a route that was ALREADY cancelled before this call (so
        // the block above skipped it). The prior cancellation — e.g. the user-deletion target-cancel
        // path (cancelRoutesTargetingPrincipal) that runs before the owner's document is trashed —
        // soft-deleted the ROUTING ACLs with an INDEPENDENT current-clock timestamp. When the deleted
        // user is BOTH the document owner and a step target, that timestamp can collide with the
        // document's own trash timestamp (documentDeleteDate), and restore-from-trash (exact-equality
        // on documentDeleteDate) would then resurrect the grant. Re-stamp any ROUTING ACL whose delete
        // timestamp is not strictly before documentDeleteDate to the deterministic (documentDeleteDate
        // - 1ms), guaranteeing distinctness regardless of which path first deleted it.
        normalizeRoutingAclDeleteTimestamp(documentId, documentDeleteDate);
        return cancelled;
    }

    /**
     * Ensure no soft-deleted ROUTING ACL on the document carries a delete timestamp at or after the
     * document's own trash timestamp, by re-stamping any such ACL to (documentDeleteDate - 1ms). This
     * is the restore-safety guard for ROUTING ACLs that a PRIOR cancellation soft-deleted with an
     * independent clock sample (which could collide with documentDeleteDate); the deterministic offset
     * used everywhere else is only guaranteed for ACLs this class deletes in the trash path itself.
     *
     * @param documentId Document (ACL source) ID
     * @param documentDeleteDate The document's own soft-delete timestamp
     */
    private static void normalizeRoutingAclDeleteTimestamp(String documentId, Date documentDeleteDate) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query upd = em.createNativeQuery("update T_ACL set ACL_DELETEDATE_D = :safeDate " +
                " where ACL_SOURCEID_C = :sourceId and ACL_TYPE_C = :type " +
                " and ACL_DELETEDATE_D is not null and ACL_DELETEDATE_D >= :docDeleteDate");
        upd.setParameter("safeDate", new Date(documentDeleteDate.getTime() - 1L));
        upd.setParameter("sourceId", documentId);
        upd.setParameter("type", ACL_TYPE_ROUTING);
        upd.setParameter("docDeleteDate", documentDeleteDate);
        upd.executeUpdate();
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
    private static void deleteRoutingAclsForDocument(String documentId, String userId, Date documentDeleteDate) {
        // Deterministic offset from the document's delete timestamp (never a fresh clock sample) so
        // restore-from-trash (exact-equality on documentDeleteDate) cannot resurrect this ACL.
        deleteAllRoutingAclsForDocument(documentId, userId, new Date(documentDeleteDate.getTime() - 1L));
    }

    /**
     * Soft-delete ALL transient workflow ("ROUTING") READ ACLs on a document (regardless of target),
     * writing a DELETE audit log for each, stamped with the CURRENT time. Used when a route is
     * cancelled in place (not via trash): the document itself is not deleted, so there is no
     * document-delete timestamp to coordinate with. Enforces the invariant that no ROUTING grant
     * survives a terminal route — target-agnostic, so it closes a cancel-vs-validate race where the
     * ROUTING ACL has already shifted from the cancel-resolved step to a later step.
     *
     * @param documentId Document (ACL source) ID
     * @param userId User performing the deletion (audit attribution)
     */
    public static void deleteAllRoutingAclsForDocument(String documentId, String userId) {
        deleteAllRoutingAclsForDocument(documentId, userId, new Date());
    }

    /**
     * Core soft-delete of every ROUTING ACL on a document, at the supplied delete timestamp, with a
     * per-ACL DELETE audit row. The timestamp is a parameter because the two callers need different
     * values: trash-cancel derives it from the document's own delete timestamp (restore-safety),
     * while in-place cancel uses the current clock.
     *
     * @param documentId Document (ACL source) ID
     * @param userId User performing the deletion (audit attribution)
     * @param routingAclDeleteDate Timestamp to stamp on the soft-deleted ROUTING ACL rows
     */
    @SuppressWarnings("unchecked")
    private static void deleteAllRoutingAclsForDocument(String documentId, String userId, Date routingAclDeleteDate) {
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

        Query upd = em.createNativeQuery("update T_ACL set ACL_DELETEDATE_D = :dateNow " +
                " where ACL_SOURCEID_C = :sourceId and ACL_TYPE_C = :type and ACL_DELETEDATE_D is null");
        upd.setParameter("dateNow", routingAclDeleteDate);
        upd.setParameter("sourceId", documentId);
        upd.setParameter("type", ACL_TYPE_ROUTING);
        upd.executeUpdate();
    }
}
