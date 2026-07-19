package com.sismics.docs.rest.document;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The differential oracle for the two migrated document endpoints. A {@link Transcript} accumulates
 * normalized request/response sections (status + pinned headers + canonical body, plus post-state
 * readbacks) and is asserted against a committed golden file. Golden files are captured ONCE against
 * the pre-migration legacy code (run with {@code -Dgolden.capture=true}, optionally
 * {@code -Dgolden.dir=<dir>}) and then FROZEN: a replay mismatch is a real wire drift to fix in the
 * code, never a fixture edit, and a MISSING fixture fails the replay loudly — it is never silently
 * captured.
 *
 * <p>Normalization is deterministic, SEMANTIC, and applied identically at capture and replay:</p>
 * <ul>
 *   <li><b>Object keys sorted</b> — JSON member order is ignored; array order is significant.</li>
 *   <li><b>UUIDs are substituted by OUT-OF-BAND semantic labels</b> the fixture registers up front
 *       ({@link Transcript#registerId}): the fixture setup creates every entity, so it holds every
 *       raw id and binds it to a meaning ({@code <ID:doc>}, {@code <ID:meta.int>}, ...). A mapper
 *       that swaps two ids therefore produces the WRONG label at each slot and fails — positional
 *       first-appearance numbering (which such a swap preserves) is deliberately not used. Any
 *       UNREGISTERED UUID-shaped value in a JSON section FAILS the fixture, forcing registration
 *       completeness instead of silently falling back.</li>
 *   <li><b>Timestamps</b>: SEEDED values (below {@link #WALL_CLOCK_CUTOFF_MS}, e.g.
 *       {@code 1500000000000}) stay RAW and compare exactly. WALL-CLOCK values (at or above the
 *       cutoff) must be registered ({@link Transcript#registerTimestamp}) from an AUTHORITATIVE
 *       out-of-band read (a direct DAO read in the test JVM — never the response under test), and are
 *       substituted by their semantic label ({@code <TS:doc.update>}, {@code <TS:file.create>}).
 *       Registration is many-to-one (several raw values may share one label, e.g. the audit rows
 *       written by one request, whose relative timing is not part of the contract), but one raw value
 *       may not carry two labels. An unregistered wall-clock timestamp FAILS the fixture.</li>
 *   <li><b>LABEL MAP footer</b> — the transcript ends with each label's FULL sorted path-set
 *       ({@code s<section>:<json-path>}), so the binding between semantic labels and structural
 *       positions is itself part of the frozen fixture.</li>
 * </ul>
 */
public final class GoldenCorpus {

    private static final Path DEFAULT_DIR = Paths.get("src/test/resources/document-golden");

    /** Keys whose numeric value is a timestamp subject to wall-clock label substitution. */
    private static final Set<String> TS_KEYS = Set.of("create_date", "update_date", "end_date");

    /**
     * Fixed epoch-ms boundary between SEEDED timestamps (kept raw; fixtures seed values below this)
     * and WALL-CLOCK timestamps (label-substituted). Part of the oracle contract: moving it requires
     * re-capturing the corpus against the legacy behavior.
     */
    private static final long WALL_CLOCK_CUTOFF_MS = 1_690_000_000_000L;

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final String[] FILTER_HEADERS =
            {"Cache-Control", "Expires", "X-Content-Type-Options", "X-Frame-Options", "Referrer-Policy"};

    private GoldenCorpus() {
        // Utility class.
    }

    /**
     * One fixture's normalized transcript: an ordered sequence of response sections plus the label-map
     * footer. Create one per fixture and register every entity id / volatile timestamp the fixture's
     * sections will carry BEFORE adding the sections.
     */
    public static final class Transcript {
        private final StringBuilder text = new StringBuilder();
        private final Map<String, String> idLabels = new LinkedHashMap<>();
        private final Map<String, String> tsLabels = new LinkedHashMap<>();
        private final Map<String, TreeSet<String>> labelPaths = new TreeMap<>();
        private int sectionIndex = -1;

        /**
         * Binds a raw entity id (held by the fixture setup) to a semantic label. Substituted as
         * {@code <ID:label>}. Re-registering the same raw id under a different label fails — that is a
         * fixture bug, not a tolerable ambiguity.
         *
         * @param rawId Raw UUID the fixture created/holds
         * @param label Semantic label (e.g. {@code doc}, {@code meta.int}, {@code file.1})
         * @return this
         */
        public Transcript registerId(String rawId, String label) {
            String previous = idLabels.putIfAbsent(rawId, label);
            Assertions.assertTrue(previous == null || previous.equals(label),
                    "Fixture bug: raw id " + rawId + " registered as both '" + previous + "' and '" + label + "'");
            return this;
        }

        /**
         * Binds a raw wall-clock timestamp (fetched from an authoritative out-of-band read, e.g. a
         * direct DAO read) to a semantic label. Substituted as {@code <TS:label>}. Many raw values may
         * share one label (a batch written by one request); one raw value may not carry two labels.
         *
         * @param rawMillis Authoritative epoch-ms value
         * @param label     Semantic label (e.g. {@code doc.update}, {@code file.create})
         * @return this
         */
        public Transcript registerTimestamp(long rawMillis, String label) {
            String raw = Long.toString(rawMillis);
            String previous = tsLabels.putIfAbsent(raw, label);
            Assertions.assertTrue(previous == null || previous.equals(label),
                    "Fixture bug: timestamp " + raw + " registered as both '" + previous + "' and '" + label
                            + "' — two distinct semantics collided on one value; give them one shared label "
                            + "or make the fixture separate them in time");
            return this;
        }

        /**
         * Appends a JSON response section: status, Content-Type, the five filter headers, and the
         * canonical normalized body.
         *
         * @param response The response (its entity is consumed)
         * @return this
         */
        public Transcript addJsonSection(Response response) {
            return addJsonSection(response, UnaryOperator.identity());
        }

        /**
         * As {@link #addJsonSection(Response)}, with a fixture-specific pre-normalization applied to
         * the parsed body BEFORE canonicalization. Used where part of the wire body is legitimately
         * order-unstable (the audit log sorts by create-date with no tie-break, and same-request rows
         * can share a millisecond) — the canonicalizer pins the content while removing only the
         * tie-unstable aspect. It must be deterministic and identical at capture and replay.
         *
         * @param response      The response (its entity is consumed)
         * @param canonicalizer Deterministic body pre-transformation
         * @return this
         */
        public Transcript addJsonSection(Response response, UnaryOperator<JsonValue> canonicalizer) {
            sectionIndex++;
            text.append("STATUS ").append(response.getStatus()).append('\n');
            text.append("Content-Type: ").append(response.getHeaderString("Content-Type")).append('\n');
            for (String header : FILTER_HEADERS) {
                text.append(header).append(": ").append(response.getHeaderString(header)).append('\n');
            }
            JsonValue value = Json.createReader(new StringReader(response.readEntity(String.class))).readValue();
            value = canonicalizer.apply(value);
            text.append("BODY ");
            write(value, "$");
            text.append('\n');
            return this;
        }

        /**
         * Appends a raw (non-JSON / empty-entity) section: status + raw entity, no normalization.
         *
         * @param response The response (its entity is consumed)
         * @return this
         */
        public Transcript addRawSection(Response response) {
            sectionIndex++;
            text.append("STATUS ").append(response.getStatus()).append('\n');
            text.append("RAW ").append(response.readEntity(String.class)).append('\n');
            return this;
        }

        /**
         * Appends a plain separator line (e.g. {@code --READBACK--}) between sections.
         *
         * @param marker Separator text
         * @return this
         */
        public Transcript marker(String marker) {
            text.append(marker).append('\n');
            return this;
        }

        /**
         * @return the assembled transcript, ending with the LABEL MAP footer (label -> full sorted
         * path-set)
         */
        public String assemble() {
            StringBuilder sb = new StringBuilder(text);
            sb.append("LABEL MAP\n");
            for (Map.Entry<String, TreeSet<String>> entry : labelPaths.entrySet()) {
                sb.append(entry.getKey()).append(" at ")
                        .append(String.join(", ", entry.getValue())).append('\n');
            }
            return sb.toString();
        }

        private void recordPath(String placeholder, String path) {
            labelPaths.computeIfAbsent(placeholder, k -> new TreeSet<>())
                    .add("s" + sectionIndex + ":" + path);
        }

        private void write(JsonValue value, String path) {
            switch (value.getValueType()) {
                case OBJECT -> {
                    JsonObject object = value.asJsonObject();
                    Map<String, JsonValue> sorted = new TreeMap<>(object);
                    text.append('{');
                    boolean first = true;
                    for (Map.Entry<String, JsonValue> entry : sorted.entrySet()) {
                        if (!first) {
                            text.append(',');
                        }
                        first = false;
                        text.append(Json.createValue(entry.getKey()).toString()).append(':');
                        String childPath = path + "." + entry.getKey();
                        if (TS_KEYS.contains(entry.getKey())
                                && entry.getValue().getValueType() == JsonValue.ValueType.NUMBER) {
                            writeTimestamp(entry.getValue(), childPath);
                        } else {
                            write(entry.getValue(), childPath);
                        }
                    }
                    text.append('}');
                }
                case ARRAY -> {
                    JsonArray array = value.asJsonArray();
                    text.append('[');
                    for (int i = 0; i < array.size(); i++) {
                        if (i > 0) {
                            text.append(',');
                        }
                        write(array.get(i), path + "[" + i + "]");
                    }
                    text.append(']');
                }
                case STRING -> text.append(substituteIds(value.toString(), path));
                default -> text.append(value.toString());
            }
        }

        private void writeTimestamp(JsonValue number, String path) {
            String raw = number.toString();
            long millis;
            try {
                millis = Long.parseLong(raw);
            } catch (NumberFormatException e) {
                // A fractional timestamp would be a wire anomaly; keep it raw so it compares exactly.
                text.append(raw);
                return;
            }
            if (millis < WALL_CLOCK_CUTOFF_MS) {
                // Seeded, deterministic value: compare exactly.
                text.append(raw);
                return;
            }
            String label = tsLabels.get(raw);
            Assertions.assertNotNull(label,
                    "Unregistered wall-clock timestamp " + raw + " at " + path + " — register it via "
                            + "registerTimestamp(...) from an authoritative DAO read; the oracle never "
                            + "falls back to positional normalization");
            String placeholder = "<TS:" + label + ">";
            recordPath(placeholder, path);
            text.append('"').append(placeholder).append('"');
        }

        private String substituteIds(String quotedString, String path) {
            String result = quotedString;
            for (Map.Entry<String, String> entry : idLabels.entrySet()) {
                if (result.contains(entry.getKey())) {
                    String placeholder = "<ID:" + entry.getValue() + ">";
                    result = result.replace(entry.getKey(), placeholder);
                    recordPath(placeholder, path);
                }
            }
            Matcher leftover = UUID.matcher(result);
            if (leftover.find()) {
                Assertions.fail("Unregistered UUID-shaped value '" + leftover.group() + "' at " + path
                        + " — register it via registerId(...); the oracle never falls back to positional "
                        + "numbering");
            }
            return result;
        }
    }

    /**
     * Replays the transcript against its committed golden file, failing loudly when the fixture is
     * missing. Capture mode ({@code -Dgolden.capture=true}) writes the fixture instead — used ONCE
     * against the legacy code; {@code -Dgolden.dir} overrides the directory (e.g. a scratch location
     * during legacy capture).
     *
     * @param name       Golden file base name
     * @param transcript Assembled transcript
     * @throws IOException on a filesystem error
     */
    public static void assertOrCapture(String name, String transcript) throws IOException {
        Path dir = Paths.get(System.getProperty("golden.dir", DEFAULT_DIR.toString()));
        Path file = dir.resolve(name + ".json");
        if (Boolean.getBoolean("golden.capture")) {
            Files.createDirectories(dir);
            Files.writeString(file, transcript);
            return;
        }
        Assertions.assertTrue(Files.exists(file),
                "Missing golden fixture '" + name + "' — the differential oracle cannot pass vacuously. "
                        + "Capture it against the LEGACY code with -Dgolden.capture=true.");
        String expected = Files.readString(file);
        Assertions.assertEquals(expected, transcript,
                "Golden differential mismatch for '" + name + "' — the migrated wire shape drifted "
                        + "from the captured legacy behavior. Fix the code, never the golden.");
    }
}
