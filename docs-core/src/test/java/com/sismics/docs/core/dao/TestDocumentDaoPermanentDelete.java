package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.constant.RouteStepType;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.Route;
import com.sismics.docs.core.model.jpa.RouteStep;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.Date;

/**
 * Unit tests for {@link DocumentDao#permanentDelete(String)} against routed documents.
 * The T_ROUTE.RTE_IDDOCUMENT_C FK is `on delete restrict`: the hard purge must delete
 * route steps and routes before the document row or the purge of a routed document fails.
 */
public class TestDocumentDaoPermanentDelete extends BaseTransactionalTest {
    private long count(String sql, String documentOrRouteId) {
        Query q = ThreadLocalContext.get().getEntityManager().createNativeQuery(sql);
        q.setParameter("id", documentOrRouteId);
        return ((Number) q.getSingleResult()).longValue();
    }

    @Test
    public void permanentDeleteRemovesRoutesAndSteps() throws Exception {
        User user = createUser("purge_routed");

        DocumentDao documentDao = new DocumentDao();
        Document document = new Document();
        document.setUserId(user.getId());
        document.setLanguage("eng");
        document.setTitle("Routed purge document");
        document.setCreateDate(new Date());
        String documentId = documentDao.create(document, user.getId());

        RouteDao routeDao = new RouteDao();
        String routeId = routeDao.create(new Route().setDocumentId(documentId).setName("Route"), user.getId());
        RouteStepDao routeStepDao = new RouteStepDao();
        routeStepDao.create(new RouteStep()
                .setRouteId(routeId)
                .setName("Step")
                .setType(RouteStepType.VALIDATE)
                .setTargetId("administrators")
                .setOrder(0));

        // Flush pending inserts so the native deletes see them and FKs are enforced.
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.flush();

        // The purge of a routed document must succeed (no FK violation)...
        documentDao.permanentDelete(documentId);

        // ...and leave no route/step/document rows behind.
        Assertions.assertEquals(0, count("select count(*) from T_ROUTE_STEP where RTP_IDROUTE_C = :id", routeId),
                "route steps must be hard-deleted with the document");
        Assertions.assertEquals(0, count("select count(*) from T_ROUTE where RTE_IDDOCUMENT_C = :id", documentId),
                "routes must be hard-deleted with the document");
        Assertions.assertEquals(0, count("select count(*) from T_DOCUMENT where DOC_ID_C = :id", documentId),
                "document row must be hard-deleted");
    }
}
