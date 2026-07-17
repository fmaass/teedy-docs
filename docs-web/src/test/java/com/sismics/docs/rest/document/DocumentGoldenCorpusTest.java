package com.sismics.docs.rest.document;

import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.AclDao;
import com.sismics.docs.core.dao.AuditLogDao;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.RouteStepDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.dao.criteria.AuditLogCriteria;
import com.sismics.docs.core.dao.dto.AuditLogDto;
import com.sismics.docs.core.model.jpa.Acl;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.docs.core.util.jpa.PaginatedList;
import com.sismics.docs.core.util.jpa.PaginatedLists;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.docs.rest.BaseJerseyTest;
import com.sismics.docs.rest.document.GoldenCorpus.Transcript;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Captures / replays the differential golden corpus for {@code GET /document/{id}} and
 * {@code POST /document/{id}}. Each fixture seeds its own uniquely-named graph, registers every
 * entity id it created and every volatile timestamp (from AUTHORITATIVE direct DAO reads — never the
 * response under test) with the transcript's semantic-label normalizer, hits the endpoint(s), and
 * (for mutations) captures a post-state {@code GET} readback. Documents are always created with a
 * SEEDED {@code create_date} below the corpus wall-clock cutoff so it is compared exactly.
 *
 * <p>The fixtures are captured ONCE against the pre-migration legacy code
 * ({@code -Dgolden.capture=true}) and then FROZEN; every later run is the differential assertion —
 * any drift is a code bug, never a fixture edit, and a missing fixture FAILS.</p>
 */
public class DocumentGoldenCorpusTest extends BaseJerseyTest {

    private static final String COOKIE = TokenBasedSecurityFilter.COOKIE_NAME;

    /** Seeded create_date (below the corpus wall-clock cutoff, compared exactly). */
    private static final String SEED_DATE = "1500000000000";

    /**
     * Sorts the audit log's {@code logs} array by (class, type, message, target): the wire sorts by
     * create-date desc with NO tie-break, and rows written in the same request can share a
     * millisecond, so the raw order is tie-unstable. The canonicalized fixture pins the row SET and
     * every field, not the tie order. Sorting runs BEFORE normalization, on raw values.
     */
    private static final UnaryOperator<JsonValue> SORT_AUDIT_LOGS = body -> {
        JsonObject object = body.asJsonObject();
        List<JsonObject> logs = new ArrayList<>();
        for (JsonValue log : object.getJsonArray("logs")) {
            logs.add(log.asJsonObject());
        }
        logs.sort(Comparator
                .comparing((JsonObject log) -> log.getString("class"))
                .thenComparing(log -> log.getString("type"))
                .thenComparing(log -> log.get("message").toString())
                .thenComparing(log -> log.getString("target")));
        JsonArrayBuilder sorted = Json.createArrayBuilder();
        logs.forEach(sorted::add);
        JsonObjectBuilder result = Json.createObjectBuilder();
        for (Map.Entry<String, JsonValue> entry : object.entrySet()) {
            if (entry.getKey().equals("logs")) {
                result.add("logs", sorted);
            } else {
                result.add(entry.getKey(), entry.getValue());
            }
        }
        return result.build();
    };

    // ---- authoritative out-of-band reads (direct DAO, test JVM shares the server's database) -----

    private <T> T daoRead(java.util.function.Supplier<T> read) {
        AtomicReference<T> holder = new AtomicReference<>();
        TransactionUtil.handle(() -> holder.set(read.get()));
        return holder.get();
    }

    private String userId(String username) {
        return daoRead(() -> new UserDao().getActiveByUsername(username).getId());
    }

    private Document docRow(String docId) {
        return daoRead(() -> new DocumentDao().getById(docId));
    }

    private List<File> fileRows(String docId) {
        return daoRead(() -> new FileDao().getByDocumentsIds(Collections.singleton(docId)));
    }

    private List<AuditLogDto> auditRows(String docId) {
        return daoRead(() -> {
            PaginatedList<AuditLogDto> paginatedList = PaginatedLists.create(20, 0);
            AuditLogCriteria criteria = new AuditLogCriteria();
            criteria.setDocumentId(docId);
            new AuditLogDao().findByCriteria(paginatedList, criteria, new SortCriteria(1, false));
            return paginatedList.getResultList();
        });
    }

    /** Registers the document's CURRENT update_date (authoritative row read) as {@code doc.update}. */
    private Transcript registerDocUpdate(Transcript transcript, String docId) {
        return transcript.registerTimestamp(docRow(docId).getUpdateDate().getTime(), "doc.update");
    }

    // ---- request helpers -------------------------------------------------------------------------

    private Response getDoc(String documentId, String token, String... queryParams) {
        WebTarget webTarget = target().path("/document/" + documentId);
        for (int i = 0; i < queryParams.length; i += 2) {
            webTarget = webTarget.queryParam(queryParams[i], queryParams[i + 1]);
        }
        Invocation.Builder builder = webTarget.request();
        if (token != null) {
            builder = builder.cookie(COOKIE, token);
        }
        return builder.get(Response.class);
    }

    private Response postDoc(String documentId, String token, Form form) {
        Invocation.Builder builder = target().path("/document/" + documentId).request();
        if (token != null) {
            builder = builder.cookie(COOKIE, token);
        }
        return builder.post(Entity.form(form), Response.class);
    }

    private String createDocument(String token, Form form) {
        JsonObject json = target().path("/document").request()
                .cookie(COOKIE, token)
                .put(Entity.form(form), JsonObject.class);
        return json.getString("id");
    }

    private String createTag(String token, String name, String color) {
        JsonObject json = target().path("/tag").request()
                .cookie(COOKIE, token)
                .put(Entity.form(new Form().param("name", name).param("color", color)), JsonObject.class);
        return json.getString("id");
    }

    private String createMetadata(String adminToken, String name, String type, String vocabulary) {
        Form form = new Form().param("name", name).param("type", type);
        if (vocabulary != null) {
            form.param("vocabulary", vocabulary);
        }
        JsonObject json = target().path("/metadata").request()
                .cookie(COOKIE, adminToken)
                .put(Entity.form(form), JsonObject.class);
        return json.getString("id");
    }

    private void deleteMetadata(String adminToken, String metadataId) {
        target().path("/metadata/" + metadataId).request()
                .cookie(COOKIE, adminToken)
                .delete(JsonObject.class);
    }

    /**
     * Fails fast if the global metadata-definition set is not EXACTLY the expected ids — pollution
     * from another test would inflate every document's metadata array and is surfaced, never
     * normalized away.
     */
    private void assertMetadataDefinitions(String adminToken, Set<String> expectedIds) {
        JsonObject json = target().path("/metadata").request()
                .cookie(COOKIE, adminToken)
                .get(JsonObject.class);
        JsonArray metadata = json.getJsonArray("metadata");
        java.util.Set<String> actual = new java.util.HashSet<>();
        for (int i = 0; i < metadata.size(); i++) {
            actual.add(metadata.getJsonObject(i).getString("id"));
        }
        Assertions.assertEquals(expectedIds, actual,
                "Polluted metadata-definition pre-state: the corpus requires exactly its own definitions");
    }

    private void capture(String name, Transcript transcript) throws IOException {
        GoldenCorpus.assertOrCapture(name, transcript.assemble());
    }

    // ---- GET corpus ------------------------------------------------------------------------------

    @Test
    public void testGetCorpus() throws Exception {
        String owner = "gc_get_owner";
        clientUtil.createUser(owner);
        String token = clientUtil.login(owner);
        String ownerId = userId(owner);

        String tag1 = createTag(token, "gcTagA", "#ff0000");
        String tag2 = createTag(token, "gcTagB", "#00ff00");

        String docId = createDocument(token, new Form()
                .param("title", "GC full title")
                .param("description", "GC <b>description</b>")
                .param("subject", "GC subject")
                .param("identifier", "GC identifier")
                .param("publisher", "GC publisher")
                .param("format", "GC format")
                .param("source", "GC source")
                .param("type", "GC type")
                .param("coverage", "GC coverage")
                .param("rights", "GC rights")
                .param("tags", tag1)
                .param("tags", tag2)
                .param("language", "eng")
                .param("create_date", SEED_DATE));
        String fileId = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, docId);
        // File processing bumps the document's update_date asynchronously (updateFileId); the
        // authoritative timestamp read must happen after that work has drained.
        awaitAsyncQuiescence("file processing must settle before the corpus timestamps are read");
        long fileCreate = fileRows(docId).get(0).getCreateDate().getTime();

        java.util.function.Supplier<Transcript> base = () -> registerDocUpdate(new Transcript()
                .registerId(ownerId, "user.owner")
                .registerId(tag1, "tag.a")
                .registerId(tag2, "tag.b")
                .registerId(docId, "doc")
                .registerId(fileId, "file.1"), docId);

        capture("get_owner_omitted", base.get().addJsonSection(getDoc(docId, token)));
        capture("get_owner_files_true", base.get()
                .registerTimestamp(fileCreate, "file.create")
                .addJsonSection(getDoc(docId, token, "files", "true")));
        capture("get_owner_files_false", base.get().addJsonSection(getDoc(docId, token, "files", "false")));

        // Anonymous share GET: tags empty, inherited_acls/route_step omitted, favorite false.
        JsonObject shareJson = target().path("/share").request()
                .cookie(COOKIE, token)
                .put(Entity.form(new Form().param("id", docId)), JsonObject.class);
        String shareId = shareJson.getString("id");
        capture("get_share_anonymous", base.get()
                .registerId(shareId, "share")
                .addJsonSection(getDoc(docId, null, "share", shareId)));

        // Minimal doc: null description + null file_id + null dublin-core + ZERO tags
        // (inherited_acls present-but-EMPTY distinguishes empty from omitted).
        String minimalId = createDocument(token, new Form()
                .param("title", "GC minimal")
                .param("language", "eng")
                .param("create_date", SEED_DATE));
        capture("get_minimal_nulls", registerDocUpdate(new Transcript()
                .registerId(ownerId, "user.owner")
                .registerId(minimalId, "doc.minimal"), minimalId)
                .addJsonSection(getDoc(minimalId, token)));

        // Raw 404 branch.
        capture("get_404", new Transcript().addRawSection(getDoc("nonexistent-missing-document", token)));
    }

    @Test
    public void testShareWritableCorpus() throws Exception {
        String owner = "gc_shw_owner";
        clientUtil.createUser(owner);
        String token = clientUtil.login(owner);
        String ownerId = userId(owner);

        String docId = createDocument(token, new Form()
                .param("title", "GC share writable")
                .param("language", "eng")
                .param("create_date", SEED_DATE));
        String shareId = target().path("/share").request()
                .cookie(COOKIE, token)
                .put(Entity.form(new Form().param("id", docId)), JsonObject.class)
                .getString("id");

        // A WRITE ACL for a share token is not creatable through the REST API (SHARE targets do not
        // resolve by name), so seed the row directly — the server shares this JVM's database. The
        // writable flag is computed against the READ target list (which includes the share id), so
        // this pins writable=true for an anonymous share GET.
        TransactionUtil.handle(() -> {
            Acl acl = new Acl();
            acl.setSourceId(docId);
            acl.setPerm(PermType.WRITE);
            acl.setType(com.sismics.docs.core.constant.AclType.USER);
            acl.setTargetId(shareId);
            new AclDao().create(acl, "admin");
        });

        capture("get_share_writable_true", registerDocUpdate(new Transcript()
                .registerId(ownerId, "user.owner")
                .registerId(docId, "doc")
                .registerId(shareId, "share"), docId)
                .addJsonSection(getDoc(docId, null, "share", shareId)));
    }

    @Test
    public void testRouteStepCorpus() throws Exception {
        String adminToken = adminToken();
        String modelId = target().path("/routemodel").request()
                .cookie(COOKIE, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "gcRouteModel")
                        .param("steps", "[{\"type\":\"VALIDATE\",\"transitions\":[{\"name\":\"VALIDATED\","
                                + "\"actions\":[]}],\"target\":{\"name\":\"administrators\",\"type\":\"GROUP\"},"
                                + "\"name\":\"Only step\"}]")), JsonObject.class)
                .getString("id");

        String docId = createDocument(adminToken, new Form()
                .param("title", "GC route doc")
                .param("language", "eng")
                .param("create_date", SEED_DATE));
        target().path("/route/start").request()
                .cookie(COOKIE, adminToken)
                .post(Entity.form(new Form()
                        .param("documentId", docId)
                        .param("routeModelId", modelId)), JsonObject.class);
        String stepId = daoRead(() -> new RouteStepDao().getCurrentStep(docId).getId());

        // Active step targeting the administrators group (seeded literal id, not a UUID):
        // transitionable TRUE for admin; comment/end_date/validator_username/transition all
        // present-as-null.
        capture("get_route_step", registerDocUpdate(new Transcript()
                .registerId(docId, "doc")
                .registerId(modelId, "routemodel")
                .registerId(stepId, "route.step"), docId)
                .addJsonSection(getDoc(docId, adminToken)));
    }

    @Test
    public void testMetadataGetCorpus() throws Exception {
        String adminToken = adminToken();
        String owner = "gc_meta_owner";
        clientUtil.createUser(owner);
        String token = clientUtil.login(owner);
        String ownerId = userId(owner);

        String strId = createMetadata(adminToken, "gcMeta0str", "STRING", null);
        String intId = createMetadata(adminToken, "gcMeta1int", "INTEGER", null);
        String floatId = createMetadata(adminToken, "gcMeta2float", "FLOAT", null);
        String dateId = createMetadata(adminToken, "gcMeta3date", "DATE", null);
        String boolId = createMetadata(adminToken, "gcMeta4bool", "BOOLEAN", null);
        String vocId = createMetadata(adminToken, "gcMeta5voc", "VOCABULARY", "coverage");
        try {
            assertMetadataDefinitions(adminToken, Set.of(strId, intId, floatId, dateId, boolId, vocId));

            String docId = createDocument(token, new Form()
                    .param("title", "GC metadata doc")
                    .param("language", "eng")
                    .param("create_date", SEED_DATE));
            // Set typed values on str/int/float/date/bool; leave vocabulary UNSET (value omitted,
            // vocabulary member present). The DATE metadata value is seeded and compared exactly.
            target().path("/document/" + docId).request()
                    .cookie(COOKIE, token)
                    .post(Entity.form(new Form()
                            .param("title", "GC metadata doc")
                            .param("language", "eng")
                            .param("metadata_id", strId)
                            .param("metadata_value", "hello")
                            .param("metadata_id", intId)
                            .param("metadata_value", "42")
                            .param("metadata_id", floatId)
                            .param("metadata_value", "3.5")
                            .param("metadata_id", dateId)
                            .param("metadata_value", "1600000000000")
                            .param("metadata_id", boolId)
                            .param("metadata_value", "true")), JsonObject.class);

            capture("get_metadata_typed", registerDocUpdate(new Transcript()
                    .registerId(ownerId, "user.owner")
                    .registerId(docId, "doc")
                    .registerId(strId, "meta.str")
                    .registerId(intId, "meta.int")
                    .registerId(floatId, "meta.float")
                    .registerId(dateId, "meta.date")
                    .registerId(boolId, "meta.bool")
                    .registerId(vocId, "meta.voc"), docId)
                    .addJsonSection(getDoc(docId, token)));
        } finally {
            deleteMetadata(adminToken, strId);
            deleteMetadata(adminToken, intId);
            deleteMetadata(adminToken, floatId);
            deleteMetadata(adminToken, dateId);
            deleteMetadata(adminToken, boolId);
            deleteMetadata(adminToken, vocId);
        }
    }

    // ---- POST corpus -----------------------------------------------------------------------------

    @Test
    public void testPostCorpus() throws Exception {
        String owner = "gc_post_owner";
        clientUtil.createUser(owner);
        String token = clientUtil.login(owner);
        String ownerId = userId(owner);
        String tag1 = createTag(token, "gcPostTagA", "#111111");
        String tag2 = createTag(token, "gcPostTagB", "#222222");
        String related = createDocument(token, new Form()
                .param("title", "GC related").param("language", "eng").param("create_date", SEED_DATE));

        String docId = createDocument(token, new Form()
                .param("title", "GC post base")
                .param("description", "orig desc")
                .param("subject", "orig subject")
                .param("language", "eng")
                .param("tags", tag1)
                .param("relations", related)
                .param("create_date", SEED_DATE));

        java.util.function.Supplier<Transcript> base = () -> registerDocUpdate(new Transcript()
                .registerId(ownerId, "user.owner")
                .registerId(tag1, "tag.a")
                .registerId(tag2, "tag.b")
                .registerId(related, "doc.related")
                .registerId(docId, "doc"), docId);

        // Full update (readback proves every field applied).
        Response fullUpdate = postDoc(docId, token, new Form()
                .param("title", "GC updated title")
                .param("description", "new desc")
                .param("subject", "new subject")
                .param("identifier", "new id")
                .param("language", "fra")
                .param("tags", tag2));
        capture("post_full_update", base.get()
                .addJsonSection(fullUpdate)
                .marker("--READBACK--")
                .addJsonSection(getDoc(docId, token, "files", "true")));

        // Single-field partial update: only title; everything else preserved (readback proves it).
        Response partial = postDoc(docId, token, new Form()
                .param("title", "GC only-title")
                .param("language", "fra"));
        capture("post_partial_single", base.get()
                .addJsonSection(partial)
                .marker("--READBACK--")
                .addJsonSection(getDoc(docId, token)));

        // Whitespace on a validateLength field is trimmed in the stored value.
        Response trim = postDoc(docId, token, new Form()
                .param("title", "  GC trimmed  ")
                .param("language", "fra"));
        capture("post_trim", base.get()
                .addJsonSection(trim)
                .marker("--READBACK--")
                .addJsonSection(getDoc(docId, token)));

        // tags_reset=true clears the tag set (readback: empty tags).
        Response tagsReset = postDoc(docId, token, new Form()
                .param("title", "GC tags reset")
                .param("language", "fra")
                .param("tags_reset", "true"));
        capture("post_tags_reset", base.get()
                .addJsonSection(tagsReset)
                .marker("--READBACK--")
                .addJsonSection(getDoc(docId, token)));

        // Re-add a tag, then a PADDED sentinel " true" is treated as false (raw parse) -> tags preserved.
        postDoc(docId, token, new Form().param("title", "GC re-tag").param("language", "fra").param("tags", tag1))
                .close();
        Response paddedSentinel = postDoc(docId, token, new Form()
                .param("title", "GC padded sentinel")
                .param("language", "fra")
                .param("tags_reset", " true"));
        capture("post_padded_sentinel", base.get()
                .addJsonSection(paddedSentinel)
                .marker("--READBACK--")
                .addJsonSection(getDoc(docId, token)));

        // Padded create_date -> 400 (validateDate does not strip). Readback proves nothing changed.
        Response paddedDate = postDoc(docId, token, new Form()
                .param("title", "GC padded date")
                .param("language", "fra")
                .param("create_date", " 1500000000000"));
        capture("post_padded_createdate_400", base.get()
                .addJsonSection(paddedDate)
                .marker("--READBACK--")
                .addJsonSection(getDoc(docId, token)));

        // TagNotFound 400 mid-update: base fields must NOT persist (readback unchanged title).
        Response tagNotFound = postDoc(docId, token, new Form()
                .param("title", "GC should-not-stick")
                .param("language", "fra")
                .param("tags", "no-such-tag-id"));
        capture("post_tagnotfound_400", base.get()
                .addJsonSection(tagNotFound)
                .marker("--READBACK--")
                .addJsonSection(getDoc(docId, token)));

        // Present-but-empty create_date -> now. Invariant window check against the authoritative row
        // (the wall-clock value cannot be pinned), then the normalized transcript with the new
        // volatile create_date registered as its own label.
        long before = System.currentTimeMillis();
        Response emptyDate = postDoc(docId, token, new Form()
                .param("title", "GC empty date")
                .param("language", "fra")
                .param("create_date", ""));
        long after = System.currentTimeMillis();
        long newCreateDate = docRow(docId).getCreateDate().getTime();
        Assertions.assertTrue(newCreateDate >= before && newCreateDate <= after,
                "empty create_date must reset to now; got " + newCreateDate);
        capture("post_createdate_empty_now", base.get()
                .registerTimestamp(newCreateDate, "doc.create")
                .addJsonSection(emptyDate)
                .marker("--READBACK--")
                .addJsonSection(getDoc(docId, token)));
    }

    @Test
    public void testRelationsSilentDropCorpus() throws Exception {
        String owner = "gc_rel_owner";
        clientUtil.createUser(owner);
        String token = clientUtil.login(owner);
        String ownerId = userId(owner);
        String valid = createDocument(token, new Form()
                .param("title", "GC rel target").param("language", "eng").param("create_date", SEED_DATE));
        String docId = createDocument(token, new Form()
                .param("title", "GC rel base").param("language", "eng").param("create_date", SEED_DATE));

        // Unknown target + self-reference are SILENTLY dropped (200, no error); only the valid
        // relation lands — pinned by the readback.
        Response post = postDoc(docId, token, new Form()
                .param("title", "GC rel base")
                .param("language", "eng")
                .param("relations", "00000000-0000-4000-8000-000000000000")
                .param("relations", docId)
                .param("relations", valid));
        capture("post_relations_silent_drop", registerDocUpdate(new Transcript()
                .registerId(ownerId, "user.owner")
                .registerId(valid, "doc.related")
                .registerId(docId, "doc"), docId)
                .addJsonSection(post)
                .marker("--READBACK--")
                .addJsonSection(getDoc(docId, token)));
    }

    @Test
    public void testAuditLogReadbackCorpus() throws Exception {
        String owner = "gc_audit_owner";
        clientUtil.createUser(owner);
        String token = clientUtil.login(owner);
        String ownerId = userId(owner);

        String docId = createDocument(token, new Form()
                .param("title", "GC audit doc")
                .param("language", "eng")
                .param("create_date", SEED_DATE));

        Response post = postDoc(docId, token, new Form()
                .param("title", "GC audit doc updated")
                .param("language", "eng"));

        // Authoritative audit rows via direct DAO read. Row ids and entity ids get row-specific
        // labels keyed on (class, type, message); the creation-request rows share ONE timestamp label
        // (their relative sub-millisecond timing is not part of the contract and may collide), while
        // the update row (a later request) gets its own.
        Transcript transcript = new Transcript()
                .registerId(ownerId, "user.owner")
                .registerId(docId, "doc");
        for (AuditLogDto row : auditRows(docId)) {
            String rowKey = switch (row.getEntityClass() + "." + row.getType().name()
                    + ("Acl".equals(row.getEntityClass()) ? "." + row.getMessage() : "")) {
                case "Acl.CREATE.READ granted to gc_audit_owner" -> "audit.acl.read";
                case "Acl.CREATE.WRITE granted to gc_audit_owner" -> "audit.acl.write";
                case "Document.CREATE" -> "audit.doc.create";
                case "Document.UPDATE" -> "audit.doc.update";
                default -> "audit.other." + row.getEntityClass() + "." + row.getType().name();
            };
            transcript.registerId(row.getId(), rowKey);
            if (!row.getEntityId().equals(docId)) {
                transcript.registerId(row.getEntityId(), rowKey + ".entity");
            }
            transcript.registerTimestamp(row.getCreateTimestamp(),
                    row.getType().name().equals("UPDATE") ? "audit.updated" : "audit.created");
        }

        // The POST's audit side effect: the readback must show the document UPDATE row alongside the
        // creation rows. The logs array is canonicalized by (class,type,message,target) because the
        // wire order is create-date-tie-unstable for same-request rows (see SORT_AUDIT_LOGS).
        capture("post_auditlog_readback", transcript
                .addJsonSection(post)
                .marker("--AUDITLOG--")
                .addJsonSection(target().path("/auditlog")
                        .queryParam("document", docId)
                        .request()
                        .cookie(COOKIE, token)
                        .get(Response.class), SORT_AUDIT_LOGS));
    }

    // ---- POST error corpus -----------------------------------------------------------------------

    @Test
    public void testPostErrorCorpus() throws Exception {
        String adminToken = adminToken();
        String owner = "gc_err_owner";
        clientUtil.createUser(owner);
        String ownerToken = clientUtil.login(owner);
        String other = "gc_err_other";
        clientUtil.createUser(other);
        String otherToken = clientUtil.login(other);

        String docId = createDocument(ownerToken, new Form()
                .param("title", "GC error base")
                .param("language", "eng")
                .param("create_date", SEED_DATE));

        // Error bodies are static (no ids, no timestamps) — nothing to register.

        // Well-formed anonymous POST -> 403.
        capture("post_403_anonymous", new Transcript().addJsonSection(postDoc(docId, null, new Form()
                .param("title", "GC anon")
                .param("language", "eng"))));

        // Malformed anonymous POST -> still 403 (authenticate precedes validation).
        capture("post_403_anonymous_malformed", new Transcript().addJsonSection(postDoc(docId, null, new Form()
                .param("title", "")
                .param("language", "x"))));

        // Authenticated non-writer -> 403.
        capture("post_403_no_write", new Transcript().addJsonSection(postDoc(docId, otherToken, new Form()
                .param("title", "GC no write")
                .param("language", "eng"))));

        // Non-admin POST to a MISSING document -> 403 (the WRITE check fails before the load).
        capture("post_missing_nonadmin_403", new Transcript().addJsonSection(
                postDoc("nonexistent-missing-document", otherToken,
                        new Form().param("title", "GC missing").param("language", "eng"))));

        // Admin POST to a MISSING document -> 404 raw (only skipAclCheck callers reach the 404 branch).
        capture("post_missing_admin_404", new Transcript().addRawSection(
                postDoc("nonexistent-missing-document", adminToken,
                        new Form().param("title", "GC missing").param("language", "eng"))));

        // Validation precedes the WRITE check: a non-writer with a bad language gets 400, not 403.
        capture("post_validation_before_write_400", new Transcript().addJsonSection(postDoc(docId, otherToken,
                new Form()
                        .param("title", "GC bad lang")
                        .param("language", "zzz"))));
    }

    @Test
    public void testPostMetadataCorpus() throws Exception {
        String adminToken = adminToken();
        String owner = "gc_pmeta_owner";
        clientUtil.createUser(owner);
        String token = clientUtil.login(owner);
        String ownerId = userId(owner);

        String strId = createMetadata(adminToken, "gcPMeta0str", "STRING", null);
        String intId = createMetadata(adminToken, "gcPMeta1int", "INTEGER", null);
        try {
            assertMetadataDefinitions(adminToken, Set.of(strId, intId));

            String docId = createDocument(token, new Form()
                    .param("title", "GC pmeta doc")
                    .param("language", "eng")
                    .param("create_date", SEED_DATE));
            // Seed a value on str.
            postDoc(docId, token, new Form()
                    .param("title", "GC pmeta doc")
                    .param("language", "eng")
                    .param("metadata_id", strId)
                    .param("metadata_value", "seed")).close();

            java.util.function.Supplier<Transcript> base = () -> registerDocUpdate(new Transcript()
                    .registerId(ownerId, "user.owner")
                    .registerId(strId, "meta.str")
                    .registerId(intId, "meta.int")
                    .registerId(docId, "doc"), docId);

            // metadata_id WITHOUT metadata_value -> 200 silent no-op: metadata unchanged, base update
            // applied (title changes in the readback).
            Response noValue = postDoc(docId, token, new Form()
                    .param("title", "GC pmeta touched")
                    .param("language", "eng")
                    .param("metadata_id", intId));
            capture("post_metadata_id_no_value_noop", base.get()
                    .addJsonSection(noValue)
                    .marker("--READBACK--")
                    .addJsonSection(getDoc(docId, token)));

            // Duplicate metadata_id -> last value wins; whole value set replaced (str removed by omission).
            Response replace = postDoc(docId, token, new Form()
                    .param("title", "GC pmeta replace")
                    .param("language", "eng")
                    .param("metadata_id", intId)
                    .param("metadata_value", "1")
                    .param("metadata_id", intId)
                    .param("metadata_value", "2"));
            capture("post_metadata_replace_dup", base.get()
                    .addJsonSection(replace)
                    .marker("--READBACK--")
                    .addJsonSection(getDoc(docId, token)));

            // Length mismatch (both lists present, ids non-empty) -> 400 ValidationError, body pinned.
            Response mismatch = postDoc(docId, token, new Form()
                    .param("title", "GC pmeta mismatch")
                    .param("language", "eng")
                    .param("metadata_id", strId)
                    .param("metadata_id", intId)
                    .param("metadata_value", "only-one"));
            capture("post_metadata_length_mismatch_400", base.get()
                    .addJsonSection(mismatch)
                    .marker("--READBACK--")
                    .addJsonSection(getDoc(docId, token)));

            // Bad typed value (INTEGER <- "abc") -> 400 ValidationError, body pinned; readback proves
            // no partial mutation.
            Response badType = postDoc(docId, token, new Form()
                    .param("title", "GC pmeta bad type")
                    .param("language", "eng")
                    .param("metadata_id", intId)
                    .param("metadata_value", "abc"));
            capture("post_metadata_bad_typed_400", base.get()
                    .addJsonSection(badType)
                    .marker("--READBACK--")
                    .addJsonSection(getDoc(docId, token)));
        } finally {
            deleteMetadata(adminToken, strId);
            deleteMetadata(adminToken, intId);
        }
    }

    @Test
    public void testMetadataVocabularyMissRollbackCorpus() throws Exception {
        String adminToken = adminToken();
        String owner = "gc_voc_owner";
        clientUtil.createUser(owner);
        String token = clientUtil.login(owner);
        String ownerId = userId(owner);

        String vocId = createMetadata(adminToken, "gcVocMeta", "VOCABULARY", "coverage");
        try {
            assertMetadataDefinitions(adminToken, Set.of(vocId));

            String tagA = createTag(token, "gcVocTagA", "#333333");
            String tagB = createTag(token, "gcVocTagB", "#444444");
            String docId = createDocument(token, new Form()
                    .param("title", "GC voc base")
                    .param("language", "eng")
                    .param("tags", tagA)
                    .param("create_date", SEED_DATE));

            // A vocabulary-miss 400 arrives AFTER the scalar update and the tag replacement have run
            // in-transaction — the readback proves the WHOLE mutation rolled back (title stays
            // "GC voc base", tags stay [tagA]).
            Response post = postDoc(docId, token, new Form()
                    .param("title", "GC voc should-not-stick")
                    .param("language", "eng")
                    .param("tags", tagB)
                    .param("metadata_id", vocId)
                    .param("metadata_value", "not-in-coverage-vocabulary"));
            capture("post_metadata_vocab_miss_400_rollback", registerDocUpdate(new Transcript()
                    .registerId(ownerId, "user.owner")
                    .registerId(vocId, "meta.voc")
                    .registerId(tagA, "tag.a")
                    .registerId(tagB, "tag.b")
                    .registerId(docId, "doc"), docId)
                    .addJsonSection(post)
                    .marker("--READBACK--")
                    .addJsonSection(getDoc(docId, token)));
        } finally {
            deleteMetadata(adminToken, vocId);
        }
    }
}
