package com.sismics.docs.core.util;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.GroupDao;
import com.sismics.docs.core.dao.RouteModelDao;
import com.sismics.docs.core.model.jpa.Group;
import com.sismics.docs.core.model.jpa.RouteModel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Unit tests for {@link RouteModelStepUtil}: the pure blob helpers ({@code parseGroupTargetNames},
 * {@code rewriteGroupTargetName}) and the transactional {@code repairGroupRename} against real H2
 * rows (no mocks of the unit under test, per Testing Integrity).
 */
public class TestRouteModelStepUtil extends BaseTransactionalTest {

    private static String stepGroup(String name) {
        return "{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\",\"actions\":[]}]," +
                "\"target\":{\"name\":\"" + name + "\",\"type\":\"GROUP\"},\"name\":\"Step\"}";
    }

    private static String stepUser(String name) {
        return "{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\",\"actions\":[]}]," +
                "\"target\":{\"name\":\"" + name + "\",\"type\":\"USER\"},\"name\":\"Step\"}";
    }

    @Test
    public void parseGroupTargetNamesSortedDistinctGroupOnly() {
        // Two GROUP targets (one duplicated) and a USER target -> distinct, sorted, group-only.
        String steps = "[" + stepGroup("bbb") + "," + stepUser("alice") + "," + stepGroup("aaa") + "," + stepGroup("bbb") + "]";
        List<String> names = RouteModelStepUtil.parseGroupTargetNames(steps);
        Assertions.assertEquals(List.of("aaa", "bbb"), names);
    }

    @Test
    public void parseGroupTargetNamesEmptyAndMalformed() {
        Assertions.assertTrue(RouteModelStepUtil.parseGroupTargetNames(null).isEmpty());
        Assertions.assertTrue(RouteModelStepUtil.parseGroupTargetNames("").isEmpty());
        Assertions.assertTrue(RouteModelStepUtil.parseGroupTargetNames("not json").isEmpty());
    }

    @Test
    public void rewriteGroupTargetNameOnlyMatchingGroup() {
        String steps = "[" + stepGroup("old") + "," + stepUser("old") + "," + stepGroup("keep") + "]";
        String rewritten = RouteModelStepUtil.rewriteGroupTargetName(steps, "old", "new");
        List<String> groups = RouteModelStepUtil.parseGroupTargetNames(rewritten);
        // The GROUP "old" became "new"; the GROUP "keep" is untouched; the USER "old" is not a group.
        Assertions.assertEquals(List.of("keep", "new"), groups);
        Assertions.assertTrue(rewritten.contains("\"USER\""), "USER step must survive the rewrite");
        // The USER target named "old" must NOT have been rewritten.
        Assertions.assertTrue(rewritten.contains("\"name\":\"old\""),
                "USER target named 'old' must be left as-is");
    }

    @Test
    public void rewriteGroupTargetNameNoMatchReturnsEquivalent() {
        String steps = "[" + stepGroup("x") + "]";
        String rewritten = RouteModelStepUtil.rewriteGroupTargetName(steps, "absent", "new");
        Assertions.assertEquals(List.of("x"), RouteModelStepUtil.parseGroupTargetNames(rewritten));
    }

    @Test
    public void repairGroupRenameRewritesReferencingBlobs() {
        new GroupDao().create(new Group().setName("repairgrp"), "admin");
        RouteModelDao dao = new RouteModelDao();
        String id = dao.create(new RouteModel().setName("M").setSteps("[" + stepGroup("repairgrp") + "]"), "admin");
        GroupDao groupDao = new GroupDao();
        String groupId = groupDao.getActiveByName("repairgrp").getId();

        // Resource order: prepare (locks + preflight), rename the group, then apply.
        RouteModelStepUtil.GroupRenameRepairPlan plan =
                RouteModelStepUtil.prepareGroupRenameRepair("repairgrp", "repairgrp2");
        Assertions.assertEquals(List.of("M"), plan.getModelNames());
        groupDao.update(new Group().setId(groupId).setName("repairgrp2"), "admin");
        List<String> repaired = RouteModelStepUtil.applyGroupRenameRepair(plan);
        Assertions.assertEquals(List.of("M"), repaired);

        String stored = dao.getActiveById(id).getSteps();
        Assertions.assertEquals(List.of("repairgrp2"), RouteModelStepUtil.parseGroupTargetNames(stored));

        // The derived T_ROUTE_MODEL_TARGET index still references the model by the (unchanged) group
        // id: apply-after-rename resolves the new name correctly during the index re-sync.
        Assertions.assertTrue(dao.findModelsReferencingTarget(groupId).contains(id),
                "Index must still reference the model after apply");
    }

    @Test
    public void repairGroupRenameSkippedOnSameName() {
        new GroupDao().create(new Group().setName("samegrp2"), "admin");
        RouteModelDao dao = new RouteModelDao();
        String id = dao.create(new RouteModel().setName("M2").setSteps("[" + stepGroup("samegrp2") + "]"), "admin");

        RouteModelStepUtil.GroupRenameRepairPlan plan =
                RouteModelStepUtil.prepareGroupRenameRepair("samegrp2", "samegrp2");
        Assertions.assertTrue(plan.isEmpty(), "Same-name rename plans nothing");
        Assertions.assertTrue(RouteModelStepUtil.applyGroupRenameRepair(plan).isEmpty(),
                "Applying an empty plan repairs nothing");
        Assertions.assertEquals(List.of("samegrp2"),
                RouteModelStepUtil.parseGroupTargetNames(dao.getActiveById(id).getSteps()));
    }

    @Test
    public void repairGroupRenameOverflowThrowsAndDoesNotMutate() {
        new GroupDao().create(new Group().setName("ovfgrp"), "admin");
        RouteModelDao dao = new RouteModelDao();

        // A blob just under the limit whose repaired form (longer group name) would overflow.
        String prefix = "[{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\",\"actions\":[]}]," +
                "\"target\":{\"name\":\"ovfgrp\",\"type\":\"GROUP\"},\"name\":\"";
        String suffix = "\"}]";
        String newName = "ovfgrpZZZZZZZZZZZZZZZZZZZZZZZZZZ"; // +25 chars
        int growth = newName.length() - "ovfgrp".length();
        int targetLen = RouteModelStepUtil.STEPS_MAX_LENGTH - (growth / 2);
        StringBuilder pad = new StringBuilder();
        while (pad.length() < targetLen - prefix.length() - suffix.length()) {
            pad.append('p');
        }
        String steps = prefix + pad + suffix;
        Assertions.assertTrue(steps.length() <= RouteModelStepUtil.STEPS_MAX_LENGTH);
        String id = dao.create(new RouteModel().setName("Ovf").setSteps(steps), "admin");

        // Prepare throws BEFORE anything is mutated (the caller never reaches the rename).
        RouteModelStepUtil.RouteModelStepOverflowException thrown = Assertions.assertThrows(
                RouteModelStepUtil.RouteModelStepOverflowException.class,
                () -> RouteModelStepUtil.prepareGroupRenameRepair("ovfgrp", newName));
        Assertions.assertTrue(thrown.getModelNames().contains("Ovf"), "Overflow names the model");

        // The stored blob is untouched: it still names the old group.
        Assertions.assertEquals(List.of("ovfgrp"),
                RouteModelStepUtil.parseGroupTargetNames(dao.getActiveById(id).getSteps()));
    }
}
