package com.sismics.docs.core.util;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.GroupDao;
import com.sismics.docs.core.dao.RouteModelDao;
import com.sismics.docs.core.model.jpa.Group;
import com.sismics.docs.core.model.jpa.RouteModel;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Unit tests for the startup repair of route-model step blobs that still name a GROUP target by what is
 * now only that group's id (#312).
 *
 * <p>The drift is produced the way a pre-v3.3.0 install produced it: a legacy group whose id EQUALS its
 * name is renamed through {@link GroupDao#update} — the DAO, which writes only the group row, not
 * {@code GroupResource.update}, whose blob repair is precisely what those installs lacked.
 */
public class TestRouteModelTargetRepairUtil extends BaseTransactionalTest {
    /**
     * A one-VALIDATE-step blob targeting a group by name.
     *
     * @param groupName GROUP target name
     * @return Steps JSON blob
     */
    private static String stepsTargeting(String groupName) {
        return "[{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\",\"actions\":[]}],"
                + "\"target\":{\"name\":\"" + groupName + "\",\"type\":\"GROUP\"},\"name\":\"Review\"}]";
    }

    /**
     * Persists a legacy-shaped group whose id EQUALS its name — the {@code dbupdate-008} shape
     * {@link GroupDao#create} (UUID ids) cannot produce.
     *
     * @param idAndName Value for both GRP_ID_C and GRP_NAME_C
     * @return The persisted group
     */
    private Group seedLegacyGroup(String idAndName) {
        Group group = new Group().setId(idAndName).setName(idAndName);
        ThreadLocalContext.get().getEntityManager().persist(group);
        return group;
    }

    /**
     * @param routeModelId Route model id
     * @return the target ids the derived index holds for that model
     */
    @SuppressWarnings("unchecked")
    private List<String> indexedTargetIds(String routeModelId) {
        Query q = ThreadLocalContext.get().getEntityManager().createNativeQuery(
                "select RMT_IDTARGET_C from T_ROUTE_MODEL_TARGET where RMT_IDROUTEMODEL_C = :id");
        q.setParameter("id", routeModelId);
        return q.getResultList();
    }

    /**
     * @param routeModelId Route model id
     * @return the model's stored step blob
     */
    private String storedSteps(String routeModelId) {
        return new RouteModelDao().getActiveById(routeModelId).getSteps();
    }

    /**
     * The repair rewrites a blob still naming the group by its id, re-syncs the derived index, and is a
     * no-op on a second run.
     */
    @Test
    public void repairsDriftedNameAndIsIdempotent() {
        GroupDao groupDao = new GroupDao();
        Group group = seedLegacyGroup("repairgrp");
        RouteModelDao routeModelDao = new RouteModelDao();
        String modelId = routeModelDao.create(
                new RouteModel().setName("Drifted").setSteps(stepsTargeting("repairgrp")), "admin");
        Assertions.assertEquals(List.of("repairgrp"), indexedTargetIds(modelId),
                "the index is keyed on the group id, which a rename does not change");

        // The pre-v3.3.0 rename: the group row moves, the blob does not.
        groupDao.update(group.setName("Reparaturgruppe"), "admin");
        Assertions.assertTrue(storedSteps(modelId).contains("repairgrp"),
                "precondition: the blob still names the group by what is now only its id");

        Assertions.assertEquals(1, RouteModelTargetRepairUtil.repairDriftedGroupTargetNames());
        Assertions.assertEquals(List.of("Reparaturgruppe"),
                RouteModelStepUtil.parseGroupTargetNames(storedSteps(modelId)),
                "the blob now names the group by its current name");
        Assertions.assertEquals(List.of("repairgrp"), indexedTargetIds(modelId),
                "the derived index is re-synced from the repaired blob and still resolves");

        Assertions.assertEquals(0, RouteModelTargetRepairUtil.repairDriftedGroupTargetNames(),
                "a second run finds nothing left to repair");
        Assertions.assertEquals(List.of("Reparaturgruppe"),
                RouteModelStepUtil.parseGroupTargetNames(storedSteps(modelId)));
    }

    /**
     * A blob naming a group that merely LOOKS like another group's id — because a live group is genuinely
     * named that — refers to that live group. The repair must leave it alone rather than re-target the
     * workflow at the renamed group.
     */
    @Test
    public void doesNotRewriteANameOwnedByALiveGroup() {
        GroupDao groupDao = new GroupDao();
        Group renamed = seedLegacyGroup("sharedname");
        RouteModelDao routeModelDao = new RouteModelDao();

        // The legacy group is renamed away, then a NEW group takes the name it used to have (which is
        // still its id).
        groupDao.update(renamed.setName("Umbenannt"), "admin");
        String liveGroupId = groupDao.create(new Group().setName("sharedname"), "admin");

        String protectedModelId = routeModelDao.create(
                new RouteModel().setName("Points at the live group").setSteps(stepsTargeting("sharedname")), "admin");
        Assertions.assertEquals(List.of(liveGroupId), indexedTargetIds(protectedModelId),
                "the blob resolves BY NAME to the live group, not to the renamed one");

        // A second, unambiguously drifted group in the SAME run: without it a repair that does nothing at
        // all would satisfy this scenario, so the protection assertion below would prove nothing.
        Group drifted = seedLegacyGroup("alsodrifted");
        groupDao.update(drifted.setName("Ebenfalls"), "admin");
        String driftedModelId = routeModelDao.create(
                new RouteModel().setName("Drifted too").setSteps(stepsTargeting("alsodrifted")), "admin");

        Assertions.assertEquals(1, RouteModelTargetRepairUtil.repairDriftedGroupTargetNames(),
                "exactly the unambiguously drifted model is repaired");
        Assertions.assertEquals(List.of("Ebenfalls"),
                RouteModelStepUtil.parseGroupTargetNames(storedSteps(driftedModelId)),
                "the drifted blob is rewritten to the group's current name");
        Assertions.assertEquals(List.of("sharedname"),
                RouteModelStepUtil.parseGroupTargetNames(storedSteps(protectedModelId)),
                "the target name is owned by a live group and must not be rewritten");
        Assertions.assertEquals(List.of(liveGroupId), indexedTargetIds(protectedModelId));
    }

    /**
     * A group renamed while its blob named it by a name that is NOT its id (the ordinary, already-repaired
     * shape) leaves nothing this repair can unambiguously fix — and it must not guess.
     */
    @Test
    public void leavesANonIdStaleNameAlone() {
        GroupDao groupDao = new GroupDao();
        Group group = new Group().setName("uuidgrp");
        String groupId = groupDao.create(group, "admin");
        RouteModelDao routeModelDao = new RouteModelDao();
        String modelId = routeModelDao.create(
                new RouteModel().setName("Old name blob").setSteps(stepsTargeting("uuidgrp")), "admin");
        Assertions.assertEquals(List.of(groupId), indexedTargetIds(modelId));

        groupDao.update(group.setName("Neuname"), "admin");

        // As above: one unambiguously drifted group in the same run, so a no-op repair cannot pass.
        Group drifted = seedLegacyGroup("iddrifted");
        groupDao.update(drifted.setName("Idgruppe"), "admin");
        String driftedModelId = routeModelDao.create(
                new RouteModel().setName("Drifted by id").setSteps(stepsTargeting("iddrifted")), "admin");

        Assertions.assertEquals(1, RouteModelTargetRepairUtil.repairDriftedGroupTargetNames(),
                "exactly the id-named drifted model is repaired");
        Assertions.assertEquals(List.of("Idgruppe"),
                RouteModelStepUtil.parseGroupTargetNames(storedSteps(driftedModelId)));
        Assertions.assertEquals(List.of("uuidgrp"),
                RouteModelStepUtil.parseGroupTargetNames(storedSteps(modelId)),
                "the stale name is not the group's id, so the repair has no unambiguous target to rewrite");
    }

    /**
     * The post-lock identity guard of the route-model write gate (#312), driven without threads: resolve
     * and lock a GROUP target, then move the name to another row exactly as a concurrent rename would,
     * and require the guard to notice that the name no longer means the row that was locked.
     *
     * <p>Setup: legacy group B carries the id {@code retarget}; group A carries the NAME {@code retarget}.
     * The blob's target text therefore resolves to A (name beats id). After A is renamed away, the very
     * same text resolves — by the id fallback — to B, a row this transaction never locked. Existence
     * alone would accept it and silently re-target the step at B.</p>
     */
    @Test
    public void detectsAGroupTargetRetargetedUnderTheLock() {
        GroupDao groupDao = new GroupDao();
        Group legacyB = seedLegacyGroup("retarget");
        groupDao.update(legacyB.setName("Bgruppe"), "admin");
        Group groupA = new Group().setName("retarget");
        String groupAId = groupDao.create(groupA, "admin");

        List<String> targetNames = List.of("retarget");
        Map<String, String> locked = RouteModelStepUtil.lockGroupsByName(targetNames);
        Assertions.assertEquals(Map.of("retarget", groupAId), locked,
                "the name resolves to A, whose row is now locked");
        Assertions.assertNull(RouteModelStepUtil.findGroupTargetRetargetedUnderLock(locked, targetNames),
                "nothing moved yet");

        // The concurrent rename: A gives up the name, so it now resolves to B via the id fallback.
        groupDao.update(groupA.setName("Agruppe"), "admin");
        Assertions.assertEquals("retarget",
                RouteModelStepUtil.findGroupTargetRetargetedUnderLock(locked, targetNames),
                "the name now means group B, a row that was never locked, and must be rejected");
    }

    /**
     * A GROUP name that resolved to NOTHING when the locks were taken but resolves now is the same defect
     * from the other side: the row it resolves to carries no lock either.
     */
    @Test
    public void detectsATargetThatBecameResolvableAfterTheLocks() {
        GroupDao groupDao = new GroupDao();
        List<String> targetNames = List.of("appearsgrp");
        Map<String, String> locked = RouteModelStepUtil.lockGroupsByName(targetNames);
        Assertions.assertTrue(locked.isEmpty(), "nothing resolved, so nothing was locked");

        String appearedId = groupDao.create(new Group().setName("appearsgrp"), "admin");
        Assertions.assertNotNull(appearedId);
        Assertions.assertEquals("appearsgrp",
                RouteModelStepUtil.findGroupTargetRetargetedUnderLock(locked, targetNames));
    }
}
