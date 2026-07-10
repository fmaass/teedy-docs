package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.criteria.RouteModelCriteria;
import com.sismics.docs.core.dao.dto.RouteModelDto;
import com.sismics.docs.core.model.jpa.RouteModel;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.util.context.ThreadLocalContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Query;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for {@link RouteModelDao}, including the derived T_ROUTE_MODEL_TARGET index sync.
 */
public class TestRouteModelDao extends BaseTransactionalTest {
    /** Two-step blob, both targeting the administrators group. */
    private static final String STEPS_TWO = "[" +
            "{\"type\":\"VALIDATE\",\"target\":{\"name\":\"administrators\",\"type\":\"GROUP\"},\"name\":\"Step 1\"}," +
            "{\"type\":\"APPROVE\",\"target\":{\"name\":\"admin\",\"type\":\"USER\"},\"name\":\"Step 2\"}]";

    /** One-step blob, targeting the administrators group. */
    private static final String STEPS_ONE = "[" +
            "{\"type\":\"VALIDATE\",\"target\":{\"name\":\"administrators\",\"type\":\"GROUP\"},\"name\":\"Step 1\"}]";

    private long countTargets(String routeModelId) {
        Query q = ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("select count(*) from T_ROUTE_MODEL_TARGET where RMT_IDROUTEMODEL_C = :id");
        q.setParameter("id", routeModelId);
        return ((Number) q.getSingleResult()).longValue();
    }

    @Test
    public void crudCycle() {
        RouteModelDao dao = new RouteModelDao();
        RouteModel model = new RouteModel().setName("Model").setSteps(STEPS_ONE);
        String id = dao.create(model, "admin");
        Assertions.assertNotNull(id);
        Assertions.assertNotNull(dao.getActiveById(id));

        dao.update(new RouteModel().setId(id).setName("Model renamed").setSteps(STEPS_ONE), "admin");
        Assertions.assertEquals("Model renamed", dao.getActiveById(id).getName());

        dao.delete(id, "admin");
        Assertions.assertNull(dao.getActiveById(id));
    }

    @Test
    public void blobToIndexSync() {
        RouteModelDao dao = new RouteModelDao();

        // Create with 2 distinct targets -> 2 index rows
        String id = dao.create(new RouteModel().setName("Sync model").setSteps(STEPS_TWO), "admin");
        Assertions.assertEquals(2, countTargets(id));

        // Update to 1 target -> 1 index row
        dao.update(new RouteModel().setId(id).setName("Sync model").setSteps(STEPS_ONE), "admin");
        Assertions.assertEquals(1, countTargets(id));

        // Delete -> 0 index rows
        dao.delete(id, "admin");
        Assertions.assertEquals(0, countTargets(id));
    }

    @Test
    public void findModelsReferencingTarget() {
        RouteModelDao dao = new RouteModelDao();
        String id = dao.create(new RouteModel().setName("Ref model").setSteps(STEPS_ONE), "admin");

        List<String> referencing = dao.findModelsReferencingTarget("administrators");
        Assertions.assertTrue(referencing.contains(id));

        // Soft-deleting the model removes it from the reverse lookup
        dao.delete(id, "admin");
        Assertions.assertFalse(dao.findModelsReferencingTarget("administrators").contains(id));
    }

    @Test
    public void findByCriteriaAppliesReadAclFilter() {
        RouteModelDao dao = new RouteModelDao();
        // A model with no READ ACL for a non-admin principal must be filtered out.
        String id = dao.create(new RouteModel().setName("Private model").setSteps(STEPS_ONE), "admin");

        List<RouteModelDto> filtered = dao.findByCriteria(
                new RouteModelCriteria().setTargetIdList(Collections.singletonList("someRandomTargetId")),
                new SortCriteria(2, false));
        Assertions.assertTrue(filtered.stream().noneMatch(d -> d.getId().equals(id)),
                "Model without a READ ACL for the principal must not be returned");

        // Admin principals skip the ACL check and see all models.
        List<RouteModelDto> asAdmin = dao.findByCriteria(
                new RouteModelCriteria().setTargetIdList(Collections.singletonList("admin")),
                new SortCriteria(2, false));
        Assertions.assertTrue(asAdmin.stream().anyMatch(d -> d.getId().equals(id)));
    }

    @Test
    public void seedDefaultRouteModelPresentWithThreeIndexRows() {
        RouteModelDao dao = new RouteModelDao();
        RouteModel seed = dao.getActiveById("default-document-review");
        Assertions.assertNotNull(seed, "Default review route model must be seeded");
        Assertions.assertEquals(3, countTargets("default-document-review"),
                "Seed model has 3 steps -> 3 index rows");
    }
}
