package com.sismics.docs.core.util.pdf;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * A parsed, validated v1 page-operation manifest.
 *
 * <p>The wire form is a JSON object:</p>
 * <pre>
 * {
 *   "version": 1,
 *   "baseVersion": 0,
 *   "pages": [
 *     { "source": 0, "rotate": 90 },
 *     { "source": 2 },
 *     { "source": 1, "rotate": 180 }
 *   ]
 * }
 * </pre>
 *
 * <p>The {@code pages} array is the declarative final page sequence: its order is the resulting page order
 * (reorder), a source page index omitted from it is deleted, and a per-entry {@code rotate} sets that
 * page's absolute rotation. This single declarative shape expresses all three v1 operations (reorder,
 * delete, per-page rotate). An empty {@code pages} array is rejected — the result must keep at least one
 * page. {@code baseVersion} is the version the client is editing; the service checks it against the file's
 * current version so the manifest carries its own expected base. Duplication, merge, and split are out of
 * v1.</p>
 */
public final class PdfPageManifest {
    /**
     * The only manifest schema version this server understands.
     */
    public static final int SUPPORTED_VERSION = 1;

    /**
     * A single surviving page in the result: which original page it is, and its absolute rotation.
     */
    public static final class PageOp {
        private final int source;
        private final Integer rotate;

        PageOp(int source, Integer rotate) {
            this.source = source;
            this.rotate = rotate;
        }

        /**
         * @return Zero-based index into the ORIGINAL page order.
         */
        public int getSource() {
            return source;
        }

        /**
         * @return Absolute page rotation to set, one of {0, 90, 180, 270}, or null to keep the page's
         *         existing rotation unchanged.
         */
        public Integer getRotate() {
            return rotate;
        }
    }

    private final int baseVersion;
    private final List<PageOp> pages;

    private PdfPageManifest(int baseVersion, List<PageOp> pages) {
        this.baseVersion = baseVersion;
        this.pages = Collections.unmodifiableList(pages);
    }

    /**
     * @return The file version the manifest was authored against (its expected base version).
     */
    public int getBaseVersion() {
        return baseVersion;
    }

    /**
     * @return The ordered, non-empty list of surviving pages.
     */
    public List<PageOp> getPages() {
        return pages;
    }

    /**
     * Parse and structurally validate a manifest, with no output-page ceiling. Source-index range is
     * validated later against the loaded PDF's page count (only known after the source is parsed).
     *
     * @param json Raw manifest JSON
     * @return The parsed manifest
     * @throws PdfPageOperationException with a typed token if the manifest is missing, malformed, of an
     *         unsupported version, or would leave no pages
     */
    public static PdfPageManifest parse(String json) throws PdfPageOperationException {
        return parse(json, Integer.MAX_VALUE);
    }

    /**
     * Parse and structurally validate a manifest, rejecting a result that would exceed {@code maxOutputPages}.
     * The ceiling bounds the OUTPUT page count (the ratified contract), so a large source reduced within the
     * ceiling is allowed and a manifest producing too many pages is rejected before any generation. Source-
     * index range is validated later against the loaded PDF's page count.
     *
     * @param json Raw manifest JSON
     * @param maxOutputPages Maximum number of pages the result may contain
     * @return The parsed manifest
     * @throws PdfPageOperationException with a typed token if the manifest is missing, malformed, of an
     *         unsupported version, would leave no pages, or would produce more than {@code maxOutputPages}
     */
    public static PdfPageManifest parse(String json, int maxOutputPages) throws PdfPageOperationException {
        if (json == null || json.isBlank()) {
            throw new PdfPageOperationException("InvalidManifest", "The manifest is required");
        }

        JsonObject root;
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            root = reader.readObject();
        } catch (JsonException | IllegalStateException | IllegalArgumentException e) {
            throw new PdfPageOperationException("InvalidManifest", "The manifest is not a valid JSON object");
        }

        int version = readInt(root, "version");
        if (version != SUPPORTED_VERSION) {
            throw new PdfPageOperationException("UnsupportedManifestVersion",
                    "Unsupported manifest version: " + version);
        }

        int baseVersion = readInt(root, "baseVersion");

        JsonValue pagesValue = root.get("pages");
        if (pagesValue == null || pagesValue.getValueType() != JsonValue.ValueType.ARRAY) {
            throw new PdfPageOperationException("InvalidManifest", "The manifest must contain a pages array");
        }
        JsonArray pagesJson = (JsonArray) pagesValue;
        if (pagesJson.isEmpty()) {
            throw new PdfPageOperationException("EmptyResult",
                    "The resulting document must contain at least one page");
        }
        if (pagesJson.size() > maxOutputPages) {
            throw new PdfPageOperationException("TooManyPages",
                    "The result would have " + pagesJson.size() + " pages, exceeding the limit of "
                            + maxOutputPages);
        }

        List<PageOp> ops = new ArrayList<>(pagesJson.size());
        Set<Integer> seenSources = new HashSet<>();
        for (int i = 0; i < pagesJson.size(); i++) {
            JsonValue entry = pagesJson.get(i);
            if (entry.getValueType() != JsonValue.ValueType.OBJECT) {
                throw new PdfPageOperationException("InvalidManifest", "Each page entry must be an object");
            }
            JsonObject page = (JsonObject) entry;

            int source = readInt(page, "source");
            if (source < 0) {
                throw new PdfPageOperationException("InvalidManifest",
                        "A page source index must be zero or greater");
            }
            if (!seenSources.add(source)) {
                // v1 never duplicates a page; a repeated source is a client error rather than a copy.
                throw new PdfPageOperationException("InvalidManifest",
                        "Duplicate page source index: " + source);
            }

            Integer rotate = null;
            JsonValue rotateValue = page.get("rotate");
            if (rotateValue != null && rotateValue.getValueType() != JsonValue.ValueType.NULL) {
                int requested = readInt(page, "rotate");
                if (requested % 90 != 0) {
                    throw new PdfPageOperationException("InvalidManifest",
                            "rotate must be a multiple of 90");
                }
                rotate = ((requested % 360) + 360) % 360;
            }

            ops.add(new PageOp(source, rotate));
        }

        return new PdfPageManifest(baseVersion, ops);
    }

    /**
     * Read a required integer member, rejecting a missing or non-integer value as a malformed manifest.
     */
    private static int readInt(JsonObject object, String key) throws PdfPageOperationException {
        JsonValue value = object.get(key);
        if (value == null || value.getValueType() != JsonValue.ValueType.NUMBER
                || !((JsonNumber) value).isIntegral()) {
            throw new PdfPageOperationException("InvalidManifest",
                    "The manifest field '" + key + "' must be an integer");
        }
        try {
            return ((JsonNumber) value).intValueExact();
        } catch (ArithmeticException e) {
            throw new PdfPageOperationException("InvalidManifest",
                    "The manifest field '" + key + "' is out of range");
        }
    }
}
