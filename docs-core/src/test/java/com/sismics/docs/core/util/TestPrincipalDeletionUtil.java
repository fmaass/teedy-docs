package com.sismics.docs.core.util;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.constant.RouteStatus;
import com.sismics.docs.core.constant.RouteStepType;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.RouteDao;
import com.sismics.docs.core.dao.RouteStepDao;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.Route;
import com.sismics.docs.core.model.jpa.RouteStep;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.Date;
import java.util.UUID;

/**
 * Tests {@link PrincipalDeletionUtil}'s restore-safety guarantees for ROUTING ACLs when a document
 * is trashed as part of a principal/owner deletion.
 */
public class TestPrincipalDeletionUtil extends BaseTransactionalTest {

    /**
     * Insert a ROUTING READ ACL directly (mirrors what route-start grants). If {@code deleteDate} is
     * non-null the row is inserted already soft-deleted at that exact timestamp.
     */
    private String insertRoutingAcl(String documentId, String targetId, Date deleteDate) {
        String aclId = UUID.randomUUID().toString();
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createNativeQuery("insert into T_ACL (ACL_ID_C, ACL_PERM_C, ACL_SOURCEID_C, ACL_TARGETID_C, ACL_TYPE_C, ACL_DELETEDATE_D) " +
                "values (:id, :perm, :src, :target, :type, :del)");
        q.setParameter("id", aclId);
        q.setParameter("perm", PermType.READ.name());
        q.setParameter("src", documentId);
        q.setParameter("target", targetId);
        q.setParameter("type", "ROUTING");
        q.setParameter("del", deleteDate);
        q.executeUpdate();
        return aclId;
    }

    private Long routingAclDeleteMillis(String aclId) {
        Timestamp ts = (Timestamp) ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("select ACL_DELETEDATE_D from T_ACL where ACL_ID_C = :id")
                .setParameter("id", aclId).getSingleResult();
        return ts == null ? null : ts.getTime();
    }

    /**
     * Restore-safety normalization is DETERMINISTIC and independent of clock timing: when a ROUTING
     * ACL was ALREADY soft-deleted (by a prior cancellation path) at a timestamp that COLLIDES with
     * the document's own trash timestamp, cancelActiveRoutesForDocument must re-stamp it strictly
     * below that timestamp (documentDeleteDate - 1ms), so restore-from-trash (exact-equality on the
     * document delete timestamp) cannot resurrect it. A second ROUTING ACL still active is soft-deleted
     * by the cancel itself, also at documentDeleteDate - 1ms.
     */
    @Test
    public void normalizesCollidingRoutingAclTimestampBelowDocTrashTimestamp() throws Exception {
        User owner = createUser("pduOwner");
        DocumentDao documentDao = new DocumentDao();

        Document document = new Document();
        document.setUserId(owner.getId());
        document.setLanguage("eng");
        document.setTitle("PDU restore-safety doc");
        document.setCreateDate(new Date());
        String documentId = documentDao.create(document, owner.getId());

        // An ACTIVE route with one open step targeting the owner.
        String routeId = new RouteDao().create(new Route().setDocumentId(documentId).setName("R"), owner.getId());
        new RouteStepDao().create(new RouteStep()
                .setRouteId(routeId).setName("S").setType(RouteStepType.VALIDATE)
                .setTargetId(owner.getId()).setOrder(0));

        // The document's future trash timestamp (what the owner-delete path would pass).
        Date documentDeleteDate = new Date();

        // A ROUTING ACL a PRIOR cancellation already soft-deleted at EXACTLY the doc trash timestamp
        // (the owner==target collision review-blocker 2 describes) — restore would resurrect it.
        String collidingAclId = insertRoutingAcl(documentId, owner.getId(), documentDeleteDate);
        // A still-active ROUTING ACL the cancel itself will soft-delete.
        String activeAclId = insertRoutingAcl(documentId, UUID.randomUUID().toString(), null);

        // Trash-cancel the document's routes with the known documentDeleteDate.
        int cancelled = PrincipalDeletionUtil.cancelActiveRoutesForDocument(documentId, owner.getId(), documentDeleteDate);
        Assertions.assertEquals(1, cancelled, "the one active route must be cancelled");

        // The route is CANCELLED.
        Route route = ThreadLocalContext.get().getEntityManager().find(Route.class, routeId);
        Assertions.assertEquals(RouteStatus.CANCELLED, route.getStatus());

        long expectedSafeMillis = documentDeleteDate.getTime() - 1L;

        // The colliding ACL must have been re-stamped strictly below the doc trash timestamp.
        Long collidingMillis = routingAclDeleteMillis(collidingAclId);
        Assertions.assertNotNull(collidingMillis);
        Assertions.assertTrue(collidingMillis < documentDeleteDate.getTime(),
                "a pre-deleted ROUTING ACL colliding with the doc trash timestamp must be normalized below it");
        Assertions.assertEquals(expectedSafeMillis, collidingMillis.longValue(),
                "normalization must move the colliding ACL to exactly documentDeleteDate - 1ms");

        // The active ACL the cancel soft-deleted must also sit at documentDeleteDate - 1ms.
        Long activeMillis = routingAclDeleteMillis(activeAclId);
        Assertions.assertNotNull(activeMillis, "the active ROUTING ACL must be soft-deleted by the cancel");
        Assertions.assertEquals(expectedSafeMillis, activeMillis.longValue(),
                "the cancel-deleted ROUTING ACL must be stamped at documentDeleteDate - 1ms");
    }

    /**
     * Lock-order convergence: the target-cancel path (cancelRoutesTargetingPrincipal) enforces the
     * global DOCUMENT-before-ROUTE lock order by locking the route's document row FOR UPDATE before
     * cancelling the route. The document may ALREADY be trashed in this scenario, so the lock must be
     * acquired regardless of the document's deleteDate (via DocumentDao.lockByIdForUpdate, not the
     * active-only getActiveByIdForUpdate). This exercises that path against a trashed-document route
     * and asserts the cancellation still succeeds: the route is CANCELLED and its ROUTING ACL cleared.
     * If the document lock were the active-only variant it would find nothing to lock here and the
     * lock-order guarantee would silently not hold; this proves the trashed-doc row is lockable.
     */
    @Test
    public void targetCancelLocksTrashedDocumentThenCancelsRoute() throws Exception {
        User owner = createUser("pduTrashOwner");
        User target = createUser("pduTrashTarget");
        DocumentDao documentDao = new DocumentDao();

        Document document = new Document();
        document.setUserId(owner.getId());
        document.setLanguage("eng");
        document.setTitle("PDU trashed-doc route");
        document.setCreateDate(new Date());
        String documentId = documentDao.create(document, owner.getId());

        // An ACTIVE route with an open step targeting the to-be-deleted principal.
        String routeId = new RouteDao().create(new Route().setDocumentId(documentId).setName("R"), owner.getId());
        new RouteStepDao().create(new RouteStep()
                .setRouteId(routeId).setName("S").setType(RouteStepType.VALIDATE)
                .setTargetId(target.getId()).setOrder(0));
        String aclId = insertRoutingAcl(documentId, target.getId(), null);

        // Trash the document directly (set its deleteDate) WITHOUT going through DocumentDao.delete,
        // so the route stays ACTIVE but its document row is non-active — the exact state the
        // target-cancel path must be able to lock by id regardless of deleteDate.
        ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_DOCUMENT set DOC_DELETEDATE_D = :d where DOC_ID_C = :id")
                .setParameter("d", new Date()).setParameter("id", documentId).executeUpdate();

        // Sanity: the active-only lock finds nothing (proving the doc is non-active), but the
        // lock-by-id (used by the target-cancel path) locks the trashed row successfully.
        Assertions.assertNull(documentDao.getActiveByIdForUpdate(documentId),
                "the document must be non-active (trashed) for this scenario");
        Assertions.assertTrue(documentDao.lockByIdForUpdate(documentId),
                "a trashed document row must still be lockable by id (document-before-route order)");

        // The target-cancel path locks the document first, then cancels the route.
        int cancelled = PrincipalDeletionUtil.cancelRoutesTargetingPrincipal(target.getId(), owner.getId());
        Assertions.assertEquals(1, cancelled, "the route targeting the deleted principal must be cancelled");

        Route route = ThreadLocalContext.get().getEntityManager().find(Route.class, routeId);
        Assertions.assertEquals(RouteStatus.CANCELLED, route.getStatus());
        Assertions.assertNotNull(routingAclDeleteMillis(aclId),
                "the target's ROUTING ACL must be cleared (soft-deleted) by the cancellation");
    }
}
