package com.sismics.docs.rest;

import com.sismics.docs.core.constant.AuditLogType;
import com.sismics.docs.core.dao.RouteDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.Route;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.docs.rest.resource.AuditLogResource;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Narrowing filters on GET /auditlog (#177): {@code type} / {@code class} / {@code user} /
 * {@code after_date}.
 *
 * <p>The property every test here defends is <b>filter composition</b>. {@code AuditLogDao}'s
 * per-source WHERE bodies are joined with {@code UNION} — they compose with OR. A filter appended
 * as one more entry of that list therefore becomes its OWN union branch, unconstrained by the
 * scope predicate: a non-admin asking for {@code ?type=CREATE} would receive every user's CREATE
 * rows (including the {@code Acl} rows the authorization predicate exists to hide), with
 * {@code total} inflated to match. Filters must instead be AND-composed into EVERY branch of BOTH
 * the fetch and the count.
 *
 * <p>Each test is written to be RED against that naive composition:
 * <ul>
 *   <li>{@link #testNonAdminFilterDoesNotLeakOtherUsersOrAclRows()} — the OR branch leaks another
 *       user's rows and the {@code Acl} rows.</li>
 *   <li>{@link #testDocumentScopedFilterStillDrawsFromEveryBranch()} — the OR branch leaks rows
 *       belonging to a different document, and the assertion that ONLY this document's rows come
 *       back fails.</li>
 *   <li>{@link #testTotalReflectsFilteredScopeAndIsCursorIndependent()} — {@code total} counts the
 *       leaked branch too, so it exceeds the number of rows actually reachable by paging.</li>
 * </ul>
 *
 * @author bgamard
 */
public class TestAuditLogFilters extends BaseJerseyTest {
    /**
     * A non-admin combining the global (per-user) scope with a {@code type} filter must still see
     * ONLY their own rows and still NO {@code Acl} rows: the filter narrows the authorization
     * scope, it never becomes an alternative to it.
     */
    @Test
    public void testNonAdminFilterDoesNotLeakOtherUsersOrAclRows() {
        String other = "auditfilter_other";
        String caller = "auditfilter_caller";
        clientUtil.createUser(other);
        clientUtil.createUser(caller);
        String otherToken = clientUtil.login(other);
        String callerToken = clientUtil.login(caller);

        // The OTHER user produces CREATE rows of several classes, including the two Acl rows a
        // document creation always writes. None of them may reach the caller.
        createDocument(otherToken, "other user document");
        createTag(otherToken, "otherfiltertag");

        // The caller produces their own CREATE rows.
        String callerDocId = createDocument(callerToken, "caller document");
        createTag(callerToken, "callerfiltertag");

        JsonObject json = fetch(callerToken, params("type", "CREATE"));
        JsonArray logs = json.getJsonArray("logs");
        Assertions.assertTrue(logs.size() > 0, "the caller has CREATE rows of their own");
        for (int i = 0; i < logs.size(); i++) {
            JsonObject log = logs.getJsonObject(i);
            Assertions.assertEquals(caller, log.getString("username"),
                    "a filtered non-admin page must contain only the caller's own rows");
            Assertions.assertNotEquals("Acl", log.getString("class"),
                    "the Acl exclusion must survive the filter");
            Assertions.assertEquals("CREATE", log.getString("type"), "the type filter must be applied");
        }
        // total is the count of the FILTERED, AUTHORIZED set — the page is small enough to hold it all.
        Assertions.assertEquals(logs.size(), json.getInt("total"),
                "total must count the filtered authorization scope, not a wider union branch");

        // The caller's own Document row is present, so the filter did not simply return nothing.
        Assertions.assertTrue(hasTarget(logs, callerDocId), "the caller's own CREATE rows survive the filter");

        // Same guarantee for the other filters.
        for (Map<String, String> filter : Set.of(
                params("class", "Document"),
                params("user", other),
                params("after_date", "0"))) {
            JsonArray filtered = fetch(callerToken, filter).getJsonArray("logs");
            for (int i = 0; i < filtered.size(); i++) {
                Assertions.assertEquals(caller, filtered.getJsonObject(i).getString("username"),
                        "filter " + filter + " must not widen the authorization scope");
                Assertions.assertNotEquals("Acl", filtered.getJsonObject(i).getString("class"),
                        "filter " + filter + " must not defeat the Acl exclusion");
            }
        }

        // A user filter naming somebody else yields nothing rather than that user's rows — it can
        // never clobber the :userId scope binding.
        Assertions.assertEquals(0, fetch(callerToken, params("user", other)).getJsonArray("logs").size(),
                "filtering by another username inside one's own scope matches nothing");
        Assertions.assertEquals(0, fetch(callerToken, params("user", other)).getInt("total"),
                "and its total is zero too");

        deleteUser(other);
        deleteUser(caller);
    }

    /**
     * A DOCUMENT-scoped request has five union branches (the document row, plus its file, comment,
     * acl and route rows). A filter must be AND-composed into ALL of them: a filter reaching fewer
     * than all five silently drops sources, and a filter that becomes its own branch drops the
     * document scope entirely.
     */
    @Test
    public void testDocumentScopedFilterStillDrawsFromEveryBranch() throws Exception {
        String user = "auditfilter_branches";
        clientUtil.createUser(user);
        String token = clientUtil.login(user);
        String userId = userId(user);

        // A DECOY document whose rows must never appear under the target's scope.
        String decoyId = createDocument(token, "branch decoy document");
        addComment(token, decoyId, "decoy comment");

        // The TARGET document, exercised so every one of the five branches has a CREATE row:
        //   branch 1 (the document itself)  -> class Document
        //   branch 2 (T_FILE   join)        -> class File
        //   branch 3 (T_COMMENT join)       -> class Comment
        //   branch 4 (T_ACL    join)        -> class Acl   (creation grants READ + WRITE)
        //   branch 5 (T_ROUTE  join)        -> class Route
        String targetId = createDocument(token, "branch target document");
        clientUtil.addFileToDocument(FILE_WIKIPEDIA_PDF, token, targetId);
        addComment(token, targetId, "target comment");
        seedRoute(targetId, userId);

        // Unfiltered: all five sources are present. This pins the premise the filtered assertion
        // rests on — if the fixture ever stops producing one of them, THIS fails first.
        Set<String> unfiltered = classesOf(fetch(token, params("document", targetId)).getJsonArray("logs"));
        Assertions.assertEquals(Set.of("Document", "File", "Comment", "Acl", "Route"), unfiltered,
                "the fixture must exercise all five document-scoped union branches");

        // Filtered by type=CREATE: every branch's row is a CREATE, so ALL FIVE sources must survive.
        // Under a naive OR composition the type filter becomes an unscoped branch instead, and the
        // decoy document's rows leak in.
        JsonObject json = fetch(token, params("document", targetId, "type", "CREATE"));
        JsonArray logs = json.getJsonArray("logs");
        Assertions.assertEquals(Set.of("Document", "File", "Comment", "Acl", "Route"), classesOf(logs),
                "a filter must be AND-composed into every branch, not applied to a subset");
        Assertions.assertFalse(hasTarget(logs, decoyId),
                "the document scope must survive the filter — no other document's rows may appear");
        for (int i = 0; i < logs.size(); i++) {
            Assertions.assertEquals("CREATE", logs.getJsonObject(i).getString("type"),
                    "every returned row must satisfy the filter");
        }
        Assertions.assertEquals(logs.size(), json.getInt("total"),
                "the document-scoped total must count the filtered set");

        // A class filter narrows to exactly one branch's source without disturbing the scope.
        JsonArray fileOnly = fetch(token, params("document", targetId, "class", "File")).getJsonArray("logs");
        Assertions.assertEquals(Set.of("File"), classesOf(fileOnly), "class=File yields only File rows");
        Assertions.assertTrue(fileOnly.size() > 0, "the target document has a File row");

        // And a class the document has no rows for yields an empty, not a leaking, page.
        JsonObject none = fetch(token, params("document", targetId, "class", "Group"));
        Assertions.assertEquals(0, none.getJsonArray("logs").size(), "class=Group matches nothing here");
        Assertions.assertEquals(0, none.getInt("total"), "and its total is zero");

        deleteUser(user);
    }

    /**
     * {@code total} must reflect the FILTERED authorization scope and must remain independent of
     * the {@code before_date}/{@code before_id} cursor: it is the size of the whole filtered result,
     * not of the rows below the cursor. Paging the filtered stream must reach exactly {@code total}
     * distinct rows.
     */
    @Test
    public void testTotalReflectsFilteredScopeAndIsCursorIndependent() {
        String user = "auditfilter_total";
        clientUtil.createUser(user);
        String token = clientUtil.login(user);

        // Five tags (CREATE, class Tag) plus one document (CREATE, class Document) — enough rows to
        // page through with a limit of 2 and a filter that keeps only some of them.
        for (int i = 0; i < 5; i++) {
            createTag(token, "totalfiltertag" + i);
        }
        createDocument(token, "total filter document");

        Map<String, String> filter = params("class", "Tag");
        JsonObject first = fetch(token, withLimit(filter, 2));
        int total = first.getInt("total");
        Assertions.assertEquals(5, total, "total counts every filtered row, not just this page");
        Assertions.assertEquals(2, first.getJsonArray("logs").size(), "the page honours the limit");

        // Walk the filtered stream to exhaustion; total must stay constant across cursored pages
        // (cursor-independent) and equal the number of distinct rows actually reachable.
        Set<String> seen = new HashSet<>();
        JsonObject page = first;
        int pages = 0;
        while (true) {
            JsonArray logs = page.getJsonArray("logs");
            Assertions.assertEquals(total, page.getInt("total"),
                    "total must not shrink as the cursor advances");
            for (int i = 0; i < logs.size(); i++) {
                JsonObject log = logs.getJsonObject(i);
                Assertions.assertEquals("Tag", log.getString("class"), "the filter holds on every page");
                Assertions.assertTrue(seen.add(log.getString("id")), "no row is returned on two pages");
            }
            if (!page.getBoolean("has_more")) {
                break;
            }
            JsonObject last = logs.getJsonObject(logs.size() - 1);
            Map<String, String> next = withLimit(filter, 2);
            next.put("before_date", Long.toString(last.getJsonNumber("create_date").longValue()));
            next.put("before_id", last.getString("id"));
            page = fetch(token, next);
            Assertions.assertTrue(++pages < 100, "paging must terminate");
        }
        Assertions.assertEquals(total, seen.size(), "every counted row is reachable by paging the filter");

        // The UNFILTERED total is strictly larger — proof the filter really moved the count rather
        // than the count ignoring it.
        Assertions.assertTrue(fetch(token, params()).getInt("total") > total,
                "the unfiltered total must exceed the class=Tag total");

        deleteUser(user);
    }

    /**
     * after_date is an inclusive lower bound on create_date, and is a FILTER — it composes with the
     * before_date/before_id cursor rather than replacing it.
     */
    @Test
    public void testAfterDateFilter() {
        String user = "auditfilter_after";
        clientUtil.createUser(user);
        String token = clientUtil.login(user);
        // Creating the USER writes an audit row authored by admin, not by this caller — the caller's
        // own scope is empty until they act. Give them a row of their own first.
        createDocument(token, "after date document");

        Assertions.assertTrue(fetch(token, params("after_date", "0")).getInt("total") > 0,
                "after_date=0 keeps every row");

        // A bound in the far future excludes everything.
        JsonObject future = fetch(token, params("after_date", "4000000000000"));
        Assertions.assertEquals(0, future.getJsonArray("logs").size(), "a future lower bound excludes every row");
        Assertions.assertEquals(0, future.getInt("total"), "and the total follows the filter");

        // Inclusive: a bound set exactly at an existing row's create_date keeps that row.
        createTag(token, "afterfiltertag");
        JsonArray logs = fetch(token, params()).getJsonArray("logs");
        Assertions.assertTrue(logs.size() > 0);
        JsonObject newest = logs.getJsonObject(0);
        long newestDate = newest.getJsonNumber("create_date").longValue();
        JsonArray inclusive = fetch(token, params("after_date", Long.toString(newestDate))).getJsonArray("logs");
        Assertions.assertTrue(hasId(inclusive, newest.getString("id")),
                "after_date is inclusive: the row at exactly that instant is kept");

        deleteUser(user);
    }

    /**
     * An unknown filter value is a client mistake — a clean 400 ValidationError, never the 500 that
     * an unguarded {@code AuditLogType.valueOf} would produce.
     */
    @Test
    public void testInvalidFilterValuesAreValidationErrors() {
        String user = "auditfilter_invalid";
        clientUtil.createUser(user);
        String token = clientUtil.login(user);

        Assertions.assertEquals(400, raw(token, params("type", "NOPE")).getStatus(),
                "an unknown type is a validation error, not a 500");
        Assertions.assertEquals(400, raw(token, params("type", "create")).getStatus(),
                "the type filter is case-sensitive against the enum");
        Assertions.assertEquals(400, raw(token, params("class", "Nope")).getStatus(),
                "an unknown class is a validation error");
        Assertions.assertEquals(400, raw(token, params("class", "AuditLog")).getStatus(),
                "a non-loggable class name is rejected");
        Assertions.assertEquals(400, raw(token, params("after_date", "not-a-number")).getStatus(),
                "a non-numeric after_date is a validation error, not a 404");
        Assertions.assertEquals(400, raw(token, params("after_date", "-1")).getStatus(),
                "a negative after_date is a validation error");

        // Empty and whitespace-only values are simply absent filters, not errors — clearing a UI
        // field must never become a 400 (and a blank must never reach AuditLogType.valueOf).
        for (String name : new String[] { "type", "class", "user", "after_date" }) {
            for (String blank : new String[] { "", " ", "   " }) {
                Assertions.assertEquals(200, raw(token, params(name, blank)).getStatus(),
                        "a blank " + name + " ('" + blank + "') is an absent filter, not an error");
            }
        }
        // A filter value is trimmed before use, so surrounding whitespace is not a mismatch.
        Assertions.assertEquals(200, raw(token, params("type", " CREATE ")).getStatus(),
                "a padded but valid type is accepted");
        // Every enum constant and every loggable class name is accepted.
        for (AuditLogType type : AuditLogType.values()) {
            Assertions.assertEquals(200, raw(token, params("type", type.name())).getStatus(),
                    type + " is an accepted type");
        }
        for (String clazz : AuditLogResource.ALLOWED_CLASSES) {
            Assertions.assertEquals(200, raw(token, params("class", clazz)).getStatus(),
                    clazz + " is an accepted class");
        }

        deleteUser(user);
    }

    /**
     * Half one of the enumeration rule: every {@code Loggable} implementor must be filterable,
     * because {@code AuditLogUtil.create} writes {@code getClass().getSimpleName()} for each of
     * them. Derived by scanning the model package rather than trusting a literal, so a NEW loggable
     * entity fails here instead of shipping an unfilterable row type.
     *
     * <p>Deliberately a SUBSET assertion, not an equality: the allowed set is the UNION of the
     * loggables and the direct writers' literals (see {@link #allowedClassesCoverEveryDirectWriter()}),
     * so values like {@code Export} legitimately have no Loggable behind them.
     */
    @Test
    public void allowedClassesCoverEveryLoggableImplementor() {
        JavaClasses model = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.sismics.docs.core.model.jpa");
        Set<String> loggables = new TreeSet<>();
        for (JavaClass javaClass : model) {
            if (!javaClass.isInterface()
                    && javaClass.getAllRawInterfaces().stream()
                            .anyMatch(i -> i.getName().equals("com.sismics.docs.core.model.jpa.Loggable"))) {
                loggables.add(javaClass.getSimpleName());
            }
        }
        // Positive control: a scan that silently found nothing must fail loudly, not pass vacuously.
        Assertions.assertTrue(loggables.contains("Document") && loggables.contains("Acl"),
                "the Loggable scan must find the known implementors, else it is not really scanning");
        Set<String> missing = new TreeSet<>(loggables);
        missing.removeAll(AuditLogResource.ALLOWED_CLASSES);
        Assertions.assertEquals(Set.of(), missing,
                "every Loggable implementor must be an accepted class filter value");
    }

    /**
     * Half two, and the durable guard the {@code Export} gap called for: EVERY direct writer's
     * literal must be filterable too.
     *
     * <p>{@code AuditLogUtil} is not the only path into LOG_CLASSENTITY_C — code that builds an
     * {@code AuditLog} itself and calls {@code setEntityClass("…")} bypasses the Loggable vocabulary
     * entirely. Deriving the allowed set from the model alone is therefore a false oracle: it let
     * {@code DocumentResource}'s {@code "Export"} rows render in the history view while
     * {@code ?class=Export} answered 400.
     *
     * <p>So this scans the PRODUCTION sources of all three modules for every
     * {@code setEntityClass("<literal>")} and asserts each literal is accepted. A new direct writer
     * fails HERE, naming itself, instead of shipping a row type the UI shows but cannot filter.
     */
    @Test
    public void allowedClassesCoverEveryDirectWriter() throws Exception {
        // The literal must look like a Java simple name: prose inside a javadoc that merely MENTIONS
        // setEntityClass("…") is documentation, not a writer, and must not be mistaken for one.
        Pattern literalCall = Pattern.compile("setEntityClass\\(\\s*\"([A-Za-z][A-Za-z0-9_]*)\"\\s*\\)");
        Map<String, String> literalToSource = new TreeMap<>();
        for (Path moduleMain : productionSourceRoots()) {
            try (Stream<Path> files = Files.walk(moduleMain)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Matcher m = literalCall.matcher(stripComments(Files.readString(file)));
                    while (m.find()) {
                        literalToSource.putIfAbsent(m.group(1), file.getFileName().toString());
                    }
                }
            }
        }
        // Positive control: the scan must actually reach the sources. A 0-hit scan proves nothing.
        Assertions.assertTrue(literalToSource.containsKey("Export"),
                "the source scan must find DocumentResource's Export literal, else it is not scanning production code");
        Assertions.assertTrue(literalToSource.containsKey("User"),
                "the source scan must find SecurityFilter's User literal");

        Set<String> unfilterable = new TreeSet<>(literalToSource.keySet());
        unfilterable.removeAll(AuditLogResource.ALLOWED_CLASSES);
        Assertions.assertEquals(Set.of(), unfilterable,
                "every directly-written entity class must be an accepted class filter value; offenders -> "
                        + literalToSource);
    }

    /**
     * Removes block and line comments so the scan sees CODE only. Without this the javadoc on
     * {@code ALLOWED_CLASSES} — which documents the very rule being enforced — registers as a
     * writer and the guard reports a phantom offender (observed while red-testing this test).
     * String literals containing comment markers are not a concern in the files being scanned.
     */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    /**
     * Locates the {@code src/main/java} root of every module that can write an audit row. Resolved
     * from the module base dir so it works under surefire and from an IDE alike.
     */
    private static List<Path> productionSourceRoots() {
        Path base = Paths.get("").toAbsolutePath();
        // Surefire runs with the module dir (docs-web) as CWD; the reactor root is its parent.
        Path reactorRoot = Files.isDirectory(base.resolve("docs-core")) ? base : base.getParent();
        List<Path> roots = new ArrayList<>();
        for (String module : new String[] { "docs-core", "docs-web", "docs-web-common" }) {
            Path main = reactorRoot.resolve(module).resolve("src/main/java");
            Assertions.assertTrue(Files.isDirectory(main), "expected production sources at " + main);
            roots.add(main);
        }
        return roots;
    }

    /**
     * The {@code Export} rows {@code DocumentResource} writes directly are a REAL, filterable row
     * type — exercised through the actual export endpoint, not a hand-inserted row, so the test
     * fails if the export path stops auditing or the filter stops accepting the value.
     */
    @Test
    public void testExportRowsAreFilterable() {
        String user = "auditfilter_export";
        clientUtil.createUser(user);
        String token = clientUtil.login(user);
        createDocument(token, "export filter document");

        // Drive the real export endpoint; it writes the Export audit row in-request.
        Response export = target().path("/document/export").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get();
        Assertions.assertEquals(200, export.getStatus(), "the export endpoint must succeed");
        export.readEntity(byte[].class);

        JsonObject json = fetch(token, params("class", "Export"));
        JsonArray logs = json.getJsonArray("logs");
        Assertions.assertTrue(logs.size() > 0, "the real export wrote a filterable Export row");
        for (int i = 0; i < logs.size(); i++) {
            Assertions.assertEquals("Export", logs.getJsonObject(i).getString("class"));
            Assertions.assertEquals(user, logs.getJsonObject(i).getString("username"));
        }
        Assertions.assertEquals(logs.size(), json.getInt("total"), "total follows the Export filter");

        // And the row is visible unfiltered too — the class the UI lists is the class it can filter.
        Assertions.assertTrue(classesOf(fetch(token, params()).getJsonArray("logs")).contains("Export"),
                "the unfiltered feed shows the Export row the filter accepts");

        deleteUser(user);
    }

    /**
     * Whatever entity classes this suite's REAL fixtures actually wrote must all be filterable.
     * A DB-derived cross-check of the two source-derived guards above: it would catch a writer that
     * neither scan sees (a literal built at runtime, say).
     */
    @Test
    public void everyClassActuallyWrittenIsFilterable() {
        String user = "auditfilter_written";
        clientUtil.createUser(user);
        String token = clientUtil.login(user);
        String docId = createDocument(token, "written classes document");
        addComment(token, docId, "written comment");
        createTag(token, "writtenclasstag");
        seedRoute(docId, userId(user));
        target().path("/document/export").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token).get().readEntity(byte[].class);

        final Set<String> written = new TreeSet<>();
        TransactionUtil.handle(() -> {
            @SuppressWarnings("unchecked")
            List<String> rows = ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("select distinct LOG_CLASSENTITY_C from T_AUDIT_LOG")
                    .getResultList();
            written.addAll(rows);
        });
        // Positive control: the fixtures above must really have produced rows.
        Assertions.assertTrue(written.contains("Document") && written.contains("Export"),
                "the fixture must write the classes this asserts over; found " + written);

        Set<String> unfilterable = new TreeSet<>(written);
        unfilterable.removeAll(AuditLogResource.ALLOWED_CLASSES);
        Assertions.assertEquals(Set.of(), unfilterable,
                "every entity class actually present in T_AUDIT_LOG must be filterable");

        deleteUser(user);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static Map<String, String> params(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static Map<String, String> withLimit(Map<String, String> base, int limit) {
        Map<String, String> map = new LinkedHashMap<>(base);
        map.put("limit", Integer.toString(limit));
        return map;
    }

    private WebTarget auditTarget(Map<String, String> queryParams) {
        WebTarget webTarget = target().path("/auditlog");
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            webTarget = webTarget.queryParam(entry.getKey(), entry.getValue());
        }
        return webTarget;
    }

    private JsonObject fetch(String token, Map<String, String> queryParams) {
        return auditTarget(queryParams).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
    }

    private Response raw(String token, Map<String, String> queryParams) {
        return auditTarget(queryParams).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get();
    }

    private String createDocument(String token, String title) {
        return target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", title)
                        .param("language", "eng")
                        .param("create_date", Long.toString(new Date().getTime()))), JsonObject.class)
                .getString("id");
    }

    private void createTag(String token, String name) {
        target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form().param("name", name).param("color", "#00ff00")), JsonObject.class);
    }

    private void addComment(String token, String documentId, String content) {
        target().path("/comment").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form().param("id", documentId).param("content", content)), JsonObject.class);
    }

    /**
     * Persists an ACTIVE route on the document through the real DAO, so the T_ROUTE row AND its
     * {@code Route} audit row are written exactly as a workflow start writes them. Going through
     * the workflow REST resource would additionally need a route model, several validation steps
     * and an acting user with the right ACLs — none of which this test is about.
     */
    private void seedRoute(String documentId, String userId) {
        TransactionUtil.handle(() -> new RouteDao().create(
                new Route().setDocumentId(documentId).setName("audit filter route"), userId));
    }

    private String userId(String username) {
        final String[] holder = new String[1];
        TransactionUtil.handle(() -> holder[0] = new UserDao().getActiveByUsername(username).getId());
        return holder[0];
    }

    private static Set<String> classesOf(JsonArray logs) {
        Set<String> classes = new HashSet<>();
        for (int i = 0; i < logs.size(); i++) {
            classes.add(logs.getJsonObject(i).getString("class"));
        }
        return classes;
    }

    private static boolean hasTarget(JsonArray logs, String target) {
        for (int i = 0; i < logs.size(); i++) {
            if (target.equals(logs.getJsonObject(i).getString("target"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasId(JsonArray logs, String id) {
        for (int i = 0; i < logs.size(); i++) {
            if (id.equals(logs.getJsonObject(i).getString("id"))) {
                return true;
            }
        }
        return false;
    }

    private void deleteUser(String username) {
        target().path("/user/" + username)
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken())
                .delete();
    }
}
