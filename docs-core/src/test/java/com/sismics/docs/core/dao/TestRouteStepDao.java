package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.constant.RouteStatus;
import com.sismics.docs.core.constant.RouteStepTransition;
import com.sismics.docs.core.constant.RouteStepType;
import com.sismics.docs.core.dao.criteria.RouteStepCriteria;
import com.sismics.docs.core.dao.dto.RouteStepDto;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.Route;
import com.sismics.docs.core.model.jpa.RouteStep;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.jpa.SortCriteria;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

/**
 * Unit tests for {@link RouteStepDao}: the guarded endRouteStep and the terminal-status filter.
 */
public class TestRouteStepDao extends BaseTransactionalTest {
    private String createDocument(User user) {
        DocumentDao documentDao = new DocumentDao();
        Document document = new Document();
        document.setUserId(user.getId());
        document.setLanguage("eng");
        document.setTitle("Step test document");
        document.setCreateDate(new Date());
        return documentDao.create(document, user.getId());
    }

    private String createStep(String routeId) {
        RouteStepDao routeStepDao = new RouteStepDao();
        RouteStep step = new RouteStep()
                .setRouteId(routeId)
                .setName("Step")
                .setType(RouteStepType.APPROVE)
                .setTargetId("administrators")
                .setOrder(0);
        return routeStepDao.create(step);
    }

    @Test
    public void endRouteStepIsGuarded() throws Exception {
        User user = createUser("step_guard");
        String documentId = createDocument(user);
        RouteDao routeDao = new RouteDao();
        String routeId = routeDao.create(new Route().setDocumentId(documentId).setName("Route"), user.getId());
        String stepId = createStep(routeId);

        RouteStepDao routeStepDao = new RouteStepDao();

        // First close: an open step is updated -> 1 row
        int first = routeStepDao.endRouteStep(stepId, RouteStepTransition.APPROVED, "First comment", user.getId());
        Assertions.assertEquals(1, first);

        // Second close: the step is already closed -> 0 rows and no overwrite
        int second = routeStepDao.endRouteStep(stepId, RouteStepTransition.REJECTED, "Second comment", user.getId());
        Assertions.assertEquals(0, second);

        List<RouteStepDto> steps = routeStepDao.findByCriteria(
                new RouteStepCriteria().setRouteId(routeId), new SortCriteria(6, true));
        Assertions.assertEquals(1, steps.size());
        Assertions.assertEquals(RouteStepTransition.APPROVED.name(), steps.get(0).getTransition());
        Assertions.assertEquals("First comment", steps.get(0).getComment());
    }

    @Test
    public void terminalStatusFilterSplitsCurrentVsHistory() throws Exception {
        User user = createUser("step_terminal");
        String documentId = createDocument(user);
        RouteDao routeDao = new RouteDao();
        String routeId = routeDao.create(new Route().setDocumentId(documentId).setName("Route"), user.getId());
        // An open step exists...
        createStep(routeId);
        // ...but the route was halted (REJECTED).
        routeDao.endRoute(routeId, RouteStatus.REJECTED);

        RouteStepDao routeStepDao = new RouteStepDao();

        // Current-step query must NOT return a step of a non-active route.
        RouteStepDto current = routeStepDao.getCurrentStep(documentId);
        Assertions.assertNull(current, "A halted route must expose no current step");

        // History query (by routeId, endDateIsNull unset) MUST still list the step.
        List<RouteStepDto> history = routeStepDao.findByCriteria(
                new RouteStepCriteria().setRouteId(routeId), new SortCriteria(6, true));
        Assertions.assertEquals(1, history.size(), "Halted route's steps must remain listable in history");
    }

    @Test
    public void currentStepReturnedForActiveRoute() throws Exception {
        User user = createUser("step_active");
        String documentId = createDocument(user);
        RouteDao routeDao = new RouteDao();
        String routeId = routeDao.create(new Route().setDocumentId(documentId).setName("Route"), user.getId());
        createStep(routeId);

        RouteStepDto current = new RouteStepDao().getCurrentStep(documentId);
        Assertions.assertNotNull(current, "Active route must expose its open step as current");
    }

    @Test
    public void endAllOpenStepsClosesRemainingSteps() throws Exception {
        User user = createUser("step_halt");
        String documentId = createDocument(user);
        RouteDao routeDao = new RouteDao();
        String routeId = routeDao.create(new Route().setDocumentId(documentId).setName("Route"), user.getId());
        createStep(routeId);
        createStep(routeId);

        int closed = new RouteStepDao().endAllOpenSteps(routeId, RouteStepTransition.REJECTED, "Halted by rejection");
        Assertions.assertEquals(2, closed);

        // A second halt closes nothing (all already closed).
        int again = new RouteStepDao().endAllOpenSteps(routeId, RouteStepTransition.REJECTED, "Halted by rejection");
        Assertions.assertEquals(0, again);
    }

    @Test
    public void endAllOpenStepsWithNullTransitionMarksSystemEnded() throws Exception {
        User user = createUser("step_sysend");
        String documentId = createDocument(user);
        RouteDao routeDao = new RouteDao();
        String routeId = routeDao.create(new Route().setDocumentId(documentId).setName("Route"), user.getId());
        String stepId = createStep(routeId);

        // A null transition closes the step without attributing a user action.
        int closed = new RouteStepDao().endAllOpenSteps(routeId, null, "Cancelled: step target deleted");
        Assertions.assertEquals(1, closed);

        // The native update bypasses the persistence context: clear it so find() reloads from the DB.
        jakarta.persistence.EntityManager em = com.sismics.util.context.ThreadLocalContext.get().getEntityManager();
        em.flush();
        em.clear();
        RouteStep step = em.find(RouteStep.class, stepId);
        Assertions.assertNotNull(step.getEndDate(), "Step must be closed");
        Assertions.assertNull(step.getTransition(), "System-ended step must have a NULL transition");
        Assertions.assertEquals("Cancelled: step target deleted", step.getComment());
    }
}
