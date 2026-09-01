package com.sismics.docs.rest;

import com.sismics.docs.core.dao.RouteModelDao;
import com.sismics.docs.core.model.jpa.Group;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Route-model GROUP target name drift on a long-migrated install (#312).
 *
 * <p>The seeded principals of a legacy install carry an id that EQUALS their name
 * ({@code dbupdate-008}: {@code GRP_ID_C='administrators'}, {@code GRP_NAME_C='administrators'}), and
 * the seeded route model's step blob ({@code RTM_STEPS_C}) names its target by that name while the
 * derived index {@code T_ROUTE_MODEL_TARGET} holds the id. Renaming such a group BEFORE the
 * rename-repair existed (it arrived in v3.3.0, {@code GroupResource.update}) left the two keys
 * disagreeing: the id survives, so the id-keyed "incomplete" flag keeps offering the model, while the
 * name lookup the start performs no longer resolves. The reporter's {@code T_GROUP} confirms exactly
 * that state — {@code GRP_ID_C='administrators'} with {@code GRP_NAME_C='Administratoren'}.
 *
 * <p>The invariant these tests pin is a CONSISTENCY one, in both directions: a model is reported
 * startable ({@code incomplete:false}) if and only if it can actually be started and saved.
 *
 * <p>The drift is established by writing {@code GRP_NAME_C} directly rather than through
 * {@code POST /group/:name} — the REST rename runs the very repair whose absence produced the defect,
 * so going through it would reproduce a healthy install, not the reporter's. The legacy id==name group
 * is likewise seeded by persisting the row: {@code GroupDao.create} assigns a UUID id and cannot
 * produce the seeded shape.
 */
public class TestRouteModelGroupNameDrift extends BaseJerseyTest {
    /**
     * A one-VALIDATE-step model blob targeting a group by name.
     *
     * @param groupName GROUP target name to write into the blob
     * @return Steps JSON blob
     */
    private static String stepsTargeting(String groupName) {
        return "[{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\",\"actions\":[]}],"
                + "\"target\":{\"name\":\"" + groupName + "\",\"type\":\"GROUP\"},\"name\":\"Review\"}]";
    }

    /**
     * Seeds a legacy-shaped group whose id EQUALS its name, the shape {@code dbupdate-008} creates and
     * {@code GroupDao.create} (UUID ids) cannot.
     *
     * @param idAndName The value used for both GRP_ID_C and GRP_NAME_C
     */
    private void seedLegacyGroup(String idAndName) {
        TransactionUtil.handle(() -> ThreadLocalContext.get().getEntityManager()
                .persist(new Group().setId(idAndName).setName(idAndName)));
    }

    /**
     * Renames a group by writing GRP_NAME_C directly, bypassing the REST rename's blob repair — the
     * pre-v3.3.0 rename this defect is about.
     *
     * @param groupId Group id (unchanged by a rename)
     * @param newName The new group name
     */
    private void driftGroupName(String groupId, String newName) {
        TransactionUtil.handle(() -> ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_GROUP set GRP_NAME_C = :name where GRP_ID_C = :id")
                .setParameter("name", newName)
                .setParameter("id", groupId)
                .executeUpdate());
    }

    /**
     * @param adminToken Admin auth token
     * @param name Model name
     * @param steps Steps blob
     * @return The new model's id
     */
    private String createModel(String adminToken, String name, String steps) {
        return target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("name", name).param("steps", steps)), JsonObject.class)
                .getString("id");
    }

    /**
     * @param adminToken Admin auth token
     * @param title Document title
     * @return The new document's id
     */
    private String createDocument(String adminToken, String title) {
        return target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form().param("title", title).param("language", "eng")), JsonObject.class)
                .getString("id");
    }

    /**
     * The {@code incomplete} flag GET /routemodel reports for one model.
     *
     * @param adminToken Admin auth token
     * @param modelId Model id
     * @return the reported flag
     */
    private boolean listedIncomplete(String adminToken, String modelId) {
        JsonArray models = target().path("/routemodel").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class)
                .getJsonArray("routemodels");
        for (int i = 0; i < models.size(); i++) {
            JsonObject model = models.getJsonObject(i);
            if (modelId.equals(model.getString("id"))) {
                return model.getBoolean("incomplete");
            }
        }
        return Assertions.fail("Model " + modelId + " is missing from GET /routemodel");
    }

    /**
     * @param adminToken Admin auth token
     * @param documentId Document to start the route on
     * @param modelId Route model to start
     * @return the raw response, entity already buffered
     */
    private Response startRoute(String adminToken, String documentId, String modelId) {
        Response response = target().path("/route/start").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("documentId", documentId).param("routeModelId", modelId)));
        response.bufferEntity();
        return response;
    }

    /**
     * @param routeModelId Route model id
     * @return how many derived T_ROUTE_MODEL_TARGET rows the model has
     */
    private long indexRowCount(String routeModelId) {
        long[] count = new long[1];
        TransactionUtil.handle(() -> count[0] = ((Number) ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("select count(*) from T_ROUTE_MODEL_TARGET where RMT_IDROUTEMODEL_C = :id")
                .setParameter("id", routeModelId)
                .getSingleResult()).longValue());
        return count[0];
    }

    /**
     * @param adminToken Admin auth token
     * @param modelId Model to update
     * @param name Model name
     * @param steps Steps blob
     * @return the raw response, entity already buffered
     */
    private Response updateModel(String adminToken, String modelId, String name, String steps) {
        Response response = target().path("/routemodel/" + modelId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form().param("name", name).param("steps", steps)));
        response.bufferEntity();
        return response;
    }

    /**
     * The reporter's case: the blob still names the group by what is now only its ID. The model keeps
     * being offered (incomplete:false), so it must keep starting — today the start answers
     * 400 InvalidRouteModel while the list still offers it.
     */
    @Test
    public void driftedGroupModelStaysStartable() {
        String adminToken = adminToken();
        seedLegacyGroup("driftgrpa");
        String modelId = createModel(adminToken, "Drifted start", stepsTargeting("driftgrpa"));
        String documentId = createDocument(adminToken, "Drifted start doc");

        driftGroupName("driftgrpa", "Driftgruppea");

        Assertions.assertFalse(listedIncomplete(adminToken, modelId),
                "the id-keyed target index still resolves, so the model is still offered as startable");

        Response response = startRoute(adminToken, documentId, modelId);
        Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus(),
                "a model the list offers must start (body was " + response.readEntity(String.class) + ")");
    }

    /**
     * The same drift on the route-model editor: an admin who opens the drifted model sees its stored
     * target name (now only the group's id) and saves it back unchanged. That save must succeed for the
     * same reason the start must — and saving the group's CURRENT name must keep working too.
     */
    @Test
    public void driftedGroupModelStaysSaveable() {
        String adminToken = adminToken();
        seedLegacyGroup("driftgrpb");
        String modelId = createModel(adminToken, "Drifted save", stepsTargeting("driftgrpb"));

        driftGroupName("driftgrpb", "Driftgruppeb");

        Response staleSave = updateModel(adminToken, modelId, "Drifted save", stepsTargeting("driftgrpb"));
        Assertions.assertEquals(Status.OK.getStatusCode(), staleSave.getStatus(),
                "re-saving the stored (drifted) blob must succeed (body was "
                        + staleSave.readEntity(String.class) + ")");

        Response currentSave = updateModel(adminToken, modelId, "Drifted save", stepsTargeting("Driftgruppeb"));
        Assertions.assertEquals(Status.OK.getStatusCode(), currentSave.getStatus(),
                "saving the group's current name must succeed (body was "
                        + currentSave.readEntity(String.class) + ")");
    }

    /**
     * The other direction of the same invariant: a target name that resolves to NOTHING — neither a
     * live group's name nor a live group's id — must be reported incomplete AND rejected. Without this
     * the fix could "agree" by accepting everything.
     */
    @Test
    public void unresolvableTargetIsFlaggedAndRejected() {
        String adminToken = adminToken();
        seedLegacyGroup("driftgrpc");
        String modelId = createModel(adminToken, "Vanished target", stepsTargeting("driftgrpc"));
        String documentId = createDocument(adminToken, "Vanished target doc");

        // The group's id AND its name both move away from the stored blob name: nothing resolves it.
        TransactionUtil.handle(() -> ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_GROUP set GRP_NAME_C = :name, GRP_DELETEDATE_D = :now"
                        + " where GRP_ID_C = :id")
                .setParameter("name", "Driftgruppec")
                .setParameter("now", new java.sql.Timestamp(System.currentTimeMillis()))
                .setParameter("id", "driftgrpc")
                .executeUpdate());

        Assertions.assertTrue(listedIncomplete(adminToken, modelId),
                "a model whose target resolves to nothing must be reported incomplete");

        Response response = startRoute(adminToken, documentId, modelId);
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus(),
                "an unresolvable target must still fail closed");
        Assertions.assertEquals("InvalidRouteModel",
                response.readEntity(JsonObject.class).getString("type"));
    }

    /**
     * The blob-derived flag's own red case: a model whose target resolves to nothing AND has NO derived
     * index row at all.
     *
     * <p>{@code RouteModelDao.syncTargets} resolves each blob target as it rebuilds the index and
     * SKIPS the ones that resolve to nothing ({@code if (targetId == null) continue;}), so a blob written
     * with an unresolvable target leaves the model with zero {@code T_ROUTE_MODEL_TARGET} rows. The
     * pre-fix flag could not see that state: its query was
     * {@code select distinct t.RMT_IDROUTEMODEL_C from T_ROUTE_MODEL_TARGET t join T_ROUTE_MODEL rm ...
     * where not exists (<live user>) and not exists (<live group>)} — every candidate id comes out of the
     * index table, so a model contributing ZERO rows to that join can never appear in the result and was
     * therefore reported {@code incomplete:false} while every start of it answered 400. The blob-derived
     * flag reads the steps themselves, so it reports the model incomplete, which is what the assertion
     * below pins.</p>
     */
    @Test
    public void unresolvableTargetWithNoIndexRowIsFlaggedAndRejected() {
        String adminToken = adminToken();
        seedLegacyGroup("driftgrpd");
        String modelId = createModel(adminToken, "No index row", stepsTargeting("driftgrpd"));
        String documentId = createDocument(adminToken, "No index row doc");
        Assertions.assertEquals(1L, indexRowCount(modelId), "precondition: the live target is indexed");

        // Write a blob naming a group that does not exist, through the DAO writer the rename repair uses.
        // Its index re-sync skips the unresolvable target, leaving the model with no index rows at all.
        TransactionUtil.handle(() -> new RouteModelDao().updateSteps(modelId, stepsTargeting("nosuchgroup")));
        Assertions.assertEquals(0L, indexRowCount(modelId),
                "precondition: the unresolvable target left no derived index row behind");

        Assertions.assertTrue(listedIncomplete(adminToken, modelId),
                "a model that cannot be started must be reported incomplete even with no index row to key on");

        Response start = startRoute(adminToken, documentId, modelId);
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), start.getStatus());
        Assertions.assertEquals("InvalidRouteModel", start.readEntity(JsonObject.class).getString("type"));

        Response save = updateModel(adminToken, modelId, "No index row", stepsTargeting("nosuchgroup"));
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), save.getStatus());
        Assertions.assertEquals("ValidationError", save.readEntity(JsonObject.class).getString("type"));
    }
}
