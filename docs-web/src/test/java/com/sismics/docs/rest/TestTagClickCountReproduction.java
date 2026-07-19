package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;

/**
 * Pins tag stats against tag-click document filters. Parent rows are asymmetric by design because
 * stats are direct while document filters include descendants.
 */
public class TestTagClickCountReproduction extends BaseJerseyTest {
    private static final String SHORT_NAME = "ORIGINAL_AUSGEHÄNDIGT";
    private static final String LONG_NAME = "ORIGINAL_AUSGEHÄNDIGT_ARCHIV";
    private static final String PARENT_NAME = "REPRO_PARENT";

    @Test
    public void testTagStatsAndNameFilterMatrix() {
        String adminToken = adminToken();
        clientUtil.createGroup("p2tagreaders");
        clientUtil.createUser("p2owner");
        clientUtil.createUser("p2directreader");
        clientUtil.createUser("p2groupreader", "p2tagreaders");
        clientUtil.createUser("p2unrelated");

        String ownerToken = clientUtil.login("p2owner");
        List<Role> roles = List.of(
                new Role("owner", ownerToken),
                new Role("group-member", clientUtil.login("p2groupreader")),
                new Role("unrelated", clientUtil.login("p2unrelated")));
        List<Observation> observations = new ArrayList<>();

        String shortTagId = createTag(ownerToken, SHORT_NAME, null);
        shareTag(ownerToken, shortTagId, "p2directreader", "USER");
        shareTag(ownerToken, shortTagId, "p2tagreaders", "GROUP");
        createDocument(ownerToken, "Reporter-verbatim document", shortTagId);

        setTagSearchMode(adminToken, "PREFIX");
        observe(observations, "A", "PREFIX", SHORT_NAME, shortTagId, roles);

        String longTagId = createTag(ownerToken, LONG_NAME, null);
        shareTag(ownerToken, longTagId, "p2directreader", "USER");
        shareTag(ownerToken, longTagId, "p2tagreaders", "GROUP");
        createDocument(ownerToken, "Prefix-extension document", longTagId);

        observe(observations, "B-shorter", "PREFIX", SHORT_NAME, shortTagId, roles);
        observe(observations, "B-longer", "PREFIX", LONG_NAME, longTagId, roles);

        setTagSearchMode(adminToken, "EXACT");
        observe(observations, "A-after-collision", "EXACT", SHORT_NAME, shortTagId, roles);
        observe(observations, "B-shorter", "EXACT", SHORT_NAME, shortTagId, roles);
        observe(observations, "B-longer", "EXACT", LONG_NAME, longTagId, roles);

        setTagSearchMode(adminToken, "PREFIX");
        String parentTagId = createTag(ownerToken, PARENT_NAME, null);
        shareTag(ownerToken, parentTagId, "p2directreader", "USER");
        shareTag(ownerToken, parentTagId, "p2tagreaders", "GROUP");
        updateTagParent(ownerToken, shortTagId, parentTagId);
        observe(observations, "C-parent", "PREFIX", PARENT_NAME, parentTagId, roles);
        observe(observations, "C-child", "PREFIX", SHORT_NAME, shortTagId, roles);

        List<Executable> assertions = new ArrayList<>();
        for (Observation observation : observations) {
            if (observation.fixture.equals("C-parent")) {
                assertions.add(() -> Assertions.assertEquals(0, observation.statsCount,
                        "Parent stats must count only directly tagged documents"));
                int expectedFilterSize = observation.role.equals("unrelated") ? 0 : 1;
                assertions.add(() -> Assertions.assertEquals(expectedFilterSize, observation.filterSize,
                        "Parent tag clicks must include visible descendant-tagged documents"));
                // The client's subtreeCount rollup closes this intentional server-side gap in the UI.
            } else {
                assertions.add(() -> Assertions.assertEquals(observation.statsCount, observation.filterSize,
                        observation.fixture + " " + observation.mode + " " + observation.role
                                + ": GET /tag/stats returned " + observation.statsCount
                                + " for tag " + observation.tagId
                                + ", but GET /document/list?search=tag:" + observation.tagName
                                + " returned " + observation.filterSize + " documents"));
            }
        }
        Assertions.assertAll(assertions);
    }

    private String createTag(String token, String name, String parentId) {
        Form form = new Form().param("name", name).param("color", "#123456");
        if (parentId != null) {
            form.param("parent", parentId);
        }
        return target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(form), JsonObject.class)
                .getString("id");
    }

    private void updateTagParent(String token, String tagId, String parentId) {
        target().path("/tag/" + tagId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("name", SHORT_NAME)
                        .param("color", "#123456")
                        .param("parent", parentId)), JsonObject.class);
    }

    private void shareTag(String token, String tagId, String aclTarget, String type) {
        target().path("/acl").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("source", tagId)
                        .param("perm", "READ")
                        .param("target", aclTarget)
                        .param("type", type)), JsonObject.class);
    }

    private void createDocument(String token, String title, String tagId) {
        target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", title)
                        .param("language", "eng")
                        .param("tags", tagId)), JsonObject.class);
    }

    private void setTagSearchMode(String adminToken, String mode) {
        target().path("/app/config").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .post(Entity.form(new Form()
                        .param("default_language", "eng")
                        .param("tag_search_mode", mode)));
    }

    private void observe(List<Observation> observations, String fixture, String mode,
            String tagName, String tagId, List<Role> roles) {
        for (Role role : roles) {
            JsonObject stats = target().path("/tag/stats").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, role.token)
                    .get(JsonObject.class).getJsonObject("stats");
            int statsCount = stats.containsKey(tagId) ? stats.getInt(tagId) : 0;
            int filterSize = target().path("/document/list")
                    .queryParam("search", "tag:" + tagName)
                    .request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, role.token)
                    .get(JsonObject.class).getJsonArray("documents").size();
            observations.add(new Observation(fixture, mode, role.name, tagName, tagId,
                    statsCount, filterSize));
        }
    }

    private record Role(String name, String token) {
    }

    private record Observation(String fixture, String mode, String role, String tagName,
            String tagId, int statsCount, int filterSize) {
    }
}
