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
        target().path("/user/trashme").request()
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
        target().path("/user/group1").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
        target().path("/user/admin2").request()
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
     * Renaming a group referenced by a route model succeeds and returns the warning payload (the
     * model's blob names the old group, so the rename orphans it). The blob is not rewritten.
     */
    @Test
    public void testRenameGroupReferencedByRouteModel() {
        String adminToken = adminToken();
        clientUtil.createGroup("wfrenamegroup");

        String modelId = TestUserResource.inTx(() -> {
            String steps = "[{\"type\":\"VALIDATE\",\"target\":{\"name\":\"wfrenamegroup\",\"type\":\"GROUP\"},\"name\":\"Step 1\"}]";
            return new RouteModelDao().create(new RouteModel().setName("Rename model").setSteps(steps), "admin");
        });

        // Rename the group.
        JsonObject json = target().path("/group/wfrenamegroup").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("name", "wfrenamedgroup")), JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
        JsonArray affected = json.getJsonArray("route_models_affected");
        Assertions.assertNotNull(affected, "Rename must warn about the orphaned route model");
        Assertions.assertTrue(TestUserResource.containsString(affected, "Rename model"));

        // The model blob was left untouched (still names the old group).
        String steps = TestUserResource.inTx(() -> new RouteModelDao().getActiveById(modelId).getSteps());
        Assertions.assertTrue(steps.contains("wfrenamegroup"), "Blob must not be rewritten on rename");
    }
}
