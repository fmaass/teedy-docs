package com.sismics.docs.rest;

import com.sismics.docs.core.constant.RouteStatus;
import com.sismics.docs.core.constant.RouteStepType;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.GroupDao;
import com.sismics.docs.core.dao.RouteDao;
import com.sismics.docs.core.dao.RouteModelDao;
import com.sismics.docs.core.dao.RouteStepDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.Group;
import com.sismics.docs.core.model.jpa.Route;
import com.sismics.docs.core.model.jpa.RouteModel;
import com.sismics.docs.core.model.jpa.RouteStep;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;


/**
 * Test the group resource.
 * 
 * @author bgamard
 */
public class TestGroupResource extends BaseJerseyTest {
    /**
     * Test the group resource.
     */
    @Test
    public void testGroupResource() {
        // Login admin
        String adminToken = adminToken();
        
        // Create group hierarchy
        clientUtil.createGroup("g1");
        clientUtil.createGroup("g11", "g1");
        clientUtil.createGroup("g12", "g1");
        clientUtil.createGroup("g111", "g11");
        clientUtil.createGroup("g112", "g11");
        
        // Login group1
        clientUtil.createUser("group1", "g112", "g12");
        String group1Token = clientUtil.login("group1");
        
        // Login admin2
        clientUtil.createUser("admin2", "administrators");
        String admin2Token = clientUtil.login("admin2");
        
        // Create trashme
        clientUtil.createUser("trashme");
        
        // Delete trashme with admin2
        target().path("/user/trashme")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, admin2Token)
                .delete(JsonObject.class);
        
        // Get all groups
        JsonObject json = target().path("/group")
                .queryParam("sort_column", "1")
                .queryParam("asc", "true")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonArray groups = json.getJsonArray("groups");
        Assertions.assertEquals(6, groups.size());
        JsonObject groupG11 = groups.getJsonObject(2);
        Assertions.assertEquals("g11", groupG11.getString("name"));
        Assertions.assertEquals("g1", groupG11.getString("parent"));
        
        // Check admin groups (all computed groups)
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        groups = json.getJsonArray("groups");
        Assertions.assertEquals(1, groups.size());
        Assertions.assertEquals("administrators", groups.getString(0));
        
        // Check group1 groups (all computed groups)
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, group1Token)
                .get(JsonObject.class);
        groups = json.getJsonArray("groups");
        List<String> groupList = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            groupList.add(groups.getString(i));
        }
        Assertions.assertEquals(4, groups.size());
        Assertions.assertTrue(groupList.contains("g1"));
        Assertions.assertTrue(groupList.contains("g12"));
        Assertions.assertTrue(groupList.contains("g11"));
        Assertions.assertTrue(groupList.contains("g112"));
        
        // Check group1 groups with admin (only direct groups)
        json = target().path("/user/group1").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        groups = json.getJsonArray("groups");
        Assertions.assertEquals(2, groups.size());
        Assertions.assertEquals("g112", groups.getString(0));
        Assertions.assertEquals("g12", groups.getString(1));
        
        // List all users in group1
        json = target().path("/user/list")
                .queryParam("group", "g112")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        JsonArray users = json.getJsonArray("users");
        Assertions.assertEquals(1, users.size());
        
        // Add group1 to g112 (again)
        target().path("/group/g112").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("username", "group1")), JsonObject.class);
        
        // Check group1 groups (all computed groups)
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, group1Token)
                .get(JsonObject.class);
        groups = json.getJsonArray("groups");
        Assertions.assertEquals(4, groups.size());
        
        // Update group g12
        target().path("/group/g12").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("name", "g12new")
                        .param("parent", "g11")), JsonObject.class);
        
        // Check group1 groups with admin (only direct groups)
        json = target().path("/user/group1").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        groups = json.getJsonArray("groups");
        Assertions.assertEquals(2, groups.size());
        Assertions.assertEquals("g112", groups.getString(0));
        Assertions.assertEquals("g12new", groups.getString(1));
        
        // Get group g12new
        json = target().path("/group/g12new").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertEquals("g12new", json.getString("name"));
        Assertions.assertEquals("g11", json.getString("parent"));
        JsonArray members = json.getJsonArray("members");
        Assertions.assertEquals(1, members.size());
        Assertions.assertEquals("group1", members.getString(0));
        
        // Remove group1 from g12new
        target().path("/group/g12new/group1").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        
        // Check group1 groups (all computed groups)
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, group1Token)
                .get(JsonObject.class);
        groups = json.getJsonArray("groups");
        groupList = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            groupList.add(groups.getString(i));
        }
        Assertions.assertEquals(3, groups.size());
        Assertions.assertTrue(groupList.contains("g1"));
        Assertions.assertTrue(groupList.contains("g11"));
        Assertions.assertTrue(groupList.contains("g112"));
        
        // Delete group g1
        target().path("/group/g1").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);

        // Delete group administrators
        Response response = target().path("/group/administrators").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        Assertions.assertEquals(Response.Status.BAD_REQUEST, Response.Status.fromStatusCode(response.getStatus()));
        json = response.readEntity(JsonObject.class);
        Assertions.assertEquals("ForbiddenError", json.getString("type"));
        Assertions.assertEquals("The administrators group cannot be deleted", json.getString("message"));
        
        // Check group1 groups (all computed groups)
        json = target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, group1Token)
                .get(JsonObject.class);
        groups = json.getJsonArray("groups");
        groupList = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            groupList.add(groups.getString(i));
        }
        Assertions.assertEquals(2, groups.size());
        Assertions.assertTrue(groupList.contains("g11"));
        Assertions.assertTrue(groupList.contains("g112"));

        // Delete all remaining groups and users
        target().path("/group/g11").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        target().path("/group/g12new").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        target().path("/group/g111").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        target().path("/group/g112").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        target().path("/user/group1")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        target().path("/user/admin2")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    /**
     * Deleting a group referenced by a route model and targeted by an open route step succeeds with
     * the warning payload; the active route is cancelled and its open step closed (history intact).
     */
    @Test
    public void testDeleteGroupHandlesWorkflowReferences() {
        String adminToken = adminToken();
        clientUtil.createGroup("wfgroup");

        String[] routeId = new String[1];
        String[] stepId = new String[1];
        String modelId = TestUserResource.inTx(() -> {
            Group group = new GroupDao().getActiveByName("wfgroup");
            User admin = new UserDao().getActiveByUsername("admin");

            Document document = new Document();
            document.setUserId(admin.getId());
            document.setLanguage("eng");
            document.setTitle("Group route doc");
            document.setCreateDate(new Date());
            String docId = new DocumentDao().create(document, admin.getId());

            routeId[0] = new RouteDao().create(new Route().setDocumentId(docId).setName("Group route"), admin.getId());
            stepId[0] = new RouteStepDao().create(new RouteStep()
                    .setRouteId(routeId[0])
                    .setName("Open step")
                    .setType(RouteStepType.VALIDATE)
                    .setTargetId(group.getId())
                    .setOrder(0));
            TestUserResource.insertRoutingAcl(docId, group.getId());

            String steps = "[{\"type\":\"VALIDATE\",\"target\":{\"name\":\"wfgroup\",\"type\":\"GROUP\"},\"name\":\"Step 1\"}]";
            return new RouteModelDao().create(new RouteModel().setName("Group model").setSteps(steps), "admin");
        });

        // Delete the group.
        JsonObject json = target().path("/group/wfgroup").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
        JsonArray affected = json.getJsonArray("route_models_affected");
        Assertions.assertNotNull(affected);
        Assertions.assertTrue(TestUserResource.containsString(affected, "Group model"));
        Assertions.assertNotNull(modelId);

        // The route was cancelled and the step history preserved.
        TestUserResource.inTx(() -> {
            Route route = ThreadLocalContext.get().getEntityManager().find(Route.class, routeId[0]);
            Assertions.assertEquals(RouteStatus.CANCELLED, route.getStatus());
            RouteStep step = ThreadLocalContext.get().getEntityManager().find(RouteStep.class, stepId[0]);
            Assertions.assertNotNull(step);
            Assertions.assertNull(step.getDeleteDate());
            Assertions.assertNotNull(step.getEndDate());
            Assertions.assertEquals("Cancelled: step target deleted", step.getComment());
            Assertions.assertNull(step.getTransition(),
                    "System-ended step must have a NULL transition, not a user-action value");
            return null;
        });
    }

    /**
     * Renaming a group referenced by a route model rewrites the matching GROUP step targets in every
     * affected model's stored blob to the new name (repair-on-rename, #31), still returns the warning
     * payload, keeps the derived T_ROUTE_MODEL_TARGET index intact, and leaves the repaired model
     * REALLY startable: a post-rename POST /route/start succeeds and the created step targets the
     * renamed group.
     */
    @Test
    public void testRenameGroupReferencedByRouteModel() {
        String adminToken = adminToken();
        clientUtil.createGroup("wfrenamegroup");

        String[] groupId = new String[1];
        String modelId = TestUserResource.inTx(() -> {
            groupId[0] = new GroupDao().getActiveByName("wfrenamegroup").getId();
            String steps = "[{\"type\":\"VALIDATE\",\"target\":{\"name\":\"wfrenamegroup\",\"type\":\"GROUP\"},\"name\":\"Step 1\"}]";
            return new RouteModelDao().create(new RouteModel().setName("Rename model").setSteps(steps), "admin");
        });

        // Rename the group.
        JsonObject json = target().path("/group/wfrenamegroup").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("name", "wfrenamedgroup")), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
        JsonArray affected = json.getJsonArray("route_models_affected");
        Assertions.assertNotNull(affected, "Rename must warn about the affected route model");
        Assertions.assertTrue(TestUserResource.containsString(affected, "Rename model"));

        // The stored blob was repaired: it now names the NEW group and no longer the old one.
        String steps = TestUserResource.inTx(() -> new RouteModelDao().getActiveById(modelId).getSteps());
        Assertions.assertTrue(steps.contains("wfrenamedgroup"), "Blob must be repaired to the new group name");
        Assertions.assertFalse(steps.contains("wfrenamegroup"), "Old group name must be gone from the blob");

        // The derived principal->model index survives the repair (same group ID — a rename does not
        // change it). Without this, a SECOND rename or a group delete would silently miss the model.
        Boolean stillIndexed = TestUserResource.inTx(() ->
                new RouteModelDao().findModelsReferencingTarget(groupId[0]).contains(modelId));
        Assertions.assertTrue(stillIndexed,
                "T_ROUTE_MODEL_TARGET index must still reference the model after repair");

        // REAL startability: a post-rename route start on the repaired model succeeds and the created
        // step targets the renamed group (start resolves the blob's target names live).
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("title", "Rename route doc")
                        .param("language", "eng")), JsonObject.class);
        String documentId = json.getString("id");

        json = target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", documentId)
                        .param("routeModelId", modelId)), JsonObject.class);
        JsonObject routeStep = json.getJsonObject("route_step");
        Assertions.assertNotNull(routeStep, "Post-rename route start must create a current step");
        Assertions.assertEquals("Step 1", routeStep.getString("name"));

        // The created step targets the renamed group's ID (resolved live from the repaired blob).
        String stepTargetId = TestUserResource.inTx(() ->
                new RouteStepDao().getCurrentStep(documentId).getTargetId());
        Assertions.assertEquals(groupId[0], stepTargetId, "The started step must target the renamed group");
    }

    /**
     * A group rename that would push a near-limit RTM_STEPS_C blob over its varchar(5000) ceiling is
     * rejected with a ValidationError naming the offending route model, and NEITHER the group name
     * NOR the blob is mutated (all-or-nothing preflight, #31).
     */
    @Test
    public void testRenameGroupRejectedWhenBlobWouldOverflow() {
        String adminToken = adminToken();
        clientUtil.createGroup("shortgrp");

        // Old group name is "shortgrp" (8 chars); the new name is longer so the repaired blob (which
        // rewrites the target name) grows. The new name is also longer than the alphanumeric group
        // name limit reach... it must satisfy validateAlphanumeric (letters/digits only) and length
        // <= 50. Use a 40-char alphanumeric name (+32 over "shortgrp").
        String longName = "shortgrpAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // 8 + 32 = 40 chars, +32 vs old
        int nameGrowth = longName.length() - "shortgrp".length();

        // Build a valid single-step blob whose length, after the target name grows by nameGrowth,
        // crosses 5000. Pad the step "name" so the STORED blob sits just under 5000 and the repaired
        // blob would exceed it. Note: the repaired blob is re-serialized (jakarta rebuilds it), so we
        // size against the stored form and leave margin for serialization differences.
        String modelId = TestUserResource.inTx(() -> {
            String prefix = "[{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\",\"actions\":[]}]," +
                    "\"target\":{\"name\":\"shortgrp\",\"type\":\"GROUP\"},\"name\":\"";
            String suffix = "\"}]";
            // Target a stored length of 5000 - (nameGrowth/2) so growth pushes it over 5000.
            int targetLen = 5000 - (nameGrowth / 2);
            int padLen = targetLen - prefix.length() - suffix.length();
            StringBuilder pad = new StringBuilder();
            while (pad.length() < padLen) {
                pad.append('p');
            }
            String steps = prefix + pad + suffix;
            Assertions.assertTrue(steps.length() <= 5000, "Fixture blob must start within the 5000 limit");
            Assertions.assertTrue(steps.length() + nameGrowth > 5000,
                    "Fixture must overflow once the group name grows");
            return new RouteModelDao().create(new RouteModel().setName("Overflow model").setSteps(steps), "admin");
        });

        // The rename is rejected.
        Response resp = target().path("/group/shortgrp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("name", longName)));
        Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
        JsonObject error = resp.readEntity(JsonObject.class);
        Assertions.assertEquals("ValidationError", error.getString("type"));
        Assertions.assertTrue(error.getString("message").contains("Overflow model"),
                "Rejection must name the offending route model");

        // The group name is unchanged (still resolvable by its old name, and the new name never took).
        String groupStillOld = TestUserResource.inTx(() ->
                com.sismics.docs.core.util.SecurityUtil.getTargetIdFromName("shortgrp",
                        com.sismics.docs.core.constant.AclTargetType.GROUP));
        Assertions.assertNotNull(groupStillOld, "Group name must be unchanged after a rejected rename");
        String groupNewAbsent = TestUserResource.inTx(() ->
                com.sismics.docs.core.util.SecurityUtil.getTargetIdFromName(longName,
                        com.sismics.docs.core.constant.AclTargetType.GROUP));
        Assertions.assertNull(groupNewAbsent, "The new group name must not exist after a rejected rename");

        // The blob still names the old group (untouched).
        String steps = TestUserResource.inTx(() -> new RouteModelDao().getActiveById(modelId).getSteps());
        Assertions.assertTrue(steps.contains("shortgrp"), "Blob must be untouched after a rejected rename");

        // Clean up the group so its lingering presence does not pollute the shared-DB group count
        // asserted by testGroupResource (the H2 mem DB persists across tests in the JVM).
        target().path("/group/shortgrp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    /**
     * A same-name group update (e.g. a parent change) skips the repair path (old == new) but KEEPS
     * the pre-existing observable warning contract: route_models_affected still names the models
     * referencing the group, exactly as any group update did before repair-on-rename existed.
     */
    @Test
    public void testRenameGroupToSameNameSkipsRepair() {
        String adminToken = adminToken();
        clientUtil.createGroup("samegrp");

        String modelId = TestUserResource.inTx(() -> {
            String steps = "[{\"type\":\"VALIDATE\",\"target\":{\"name\":\"samegrp\",\"type\":\"GROUP\"},\"name\":\"Step 1\"}]";
            return new RouteModelDao().create(new RouteModel().setName("Same name model").setSteps(steps), "admin");
        });

        JsonObject json = target().path("/group/samegrp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("name", "samegrp")), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // Prior observable contract preserved: the warning payload still names the affected models
        // on a same-name update, even though no repair ran.
        JsonArray affected = json.getJsonArray("route_models_affected");
        Assertions.assertNotNull(affected, "Same-name update must still report affected route models");
        Assertions.assertTrue(TestUserResource.containsString(affected, "Same name model"),
                "Warning must name the referencing route model");

        String steps = TestUserResource.inTx(() -> new RouteModelDao().getActiveById(modelId).getSteps());
        Assertions.assertTrue(steps.contains("samegrp"), "Blob unchanged on a same-name rename");

        // Clean up (see testRenameGroupRejectedWhenBlobWouldOverflow).
        target().path("/group/samegrp").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }
}
