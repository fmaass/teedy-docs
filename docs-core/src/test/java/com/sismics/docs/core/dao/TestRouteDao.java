package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.constant.RouteStatus;
import com.sismics.docs.core.dao.criteria.RouteCriteria;
import com.sismics.docs.core.dao.dto.RouteDto;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.Route;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.jpa.SortCriteria;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

/**
 * Unit tests for {@link RouteDao}.
 */
public class TestRouteDao extends BaseTransactionalTest {
    private String createDocument(User user) {
        DocumentDao documentDao = new DocumentDao();
        Document document = new Document();
        document.setUserId(user.getId());
        document.setLanguage("eng");
        document.setTitle("Route test document");
        document.setCreateDate(new Date());
        return documentDao.create(document, user.getId());
    }

    @Test
    public void createSetsInitiatorAndActiveStatus() throws Exception {
        User user = createUser("route_creator");
        String documentId = createDocument(user);

        RouteDao routeDao = new RouteDao();
        Route route = new Route()
                .setDocumentId(documentId)
                .setName("Test route");
        String routeId = routeDao.create(route, user.getId());
        Assertions.assertNotNull(routeId);

        List<RouteDto> routes = routeDao.findByCriteria(
                new RouteCriteria().setDocumentId(documentId), new SortCriteria(3, false));
        Assertions.assertEquals(1, routes.size());
        Assertions.assertEquals("Test route", routes.get(0).getName());
        Assertions.assertEquals(RouteStatus.ACTIVE.name(), routes.get(0).getStatus());

        Route persisted = com.sismics.util.context.ThreadLocalContext.get()
                .getEntityManager().find(Route.class, routeId);
        Assertions.assertEquals(user.getId(), persisted.getUserId());
        Assertions.assertEquals(RouteStatus.ACTIVE, persisted.getStatus());
    }

    @Test
    public void endRouteSetsStatusAndEndDate() throws Exception {
        User user = createUser("route_ender");
        String documentId = createDocument(user);
        RouteDao routeDao = new RouteDao();
        String routeId = routeDao.create(new Route().setDocumentId(documentId).setName("Route"), user.getId());

        routeDao.endRoute(routeId, RouteStatus.DONE);

        Route persisted = com.sismics.util.context.ThreadLocalContext.get()
                .getEntityManager().find(Route.class, routeId);
        Assertions.assertEquals(RouteStatus.DONE, persisted.getStatus());
        Assertions.assertNotNull(persisted.getEndDate());
    }

    @Test
    public void deleteRouteSoftDeletes() throws Exception {
        User user = createUser("route_deleter");
        String documentId = createDocument(user);
        RouteDao routeDao = new RouteDao();
        String routeId = routeDao.create(new Route().setDocumentId(documentId).setName("Route"), user.getId());

        routeDao.deleteRoute(routeId, user.getId());

        List<RouteDto> routes = routeDao.findByCriteria(
                new RouteCriteria().setDocumentId(documentId), new SortCriteria(3, false));
        Assertions.assertTrue(routes.isEmpty());
    }
}
