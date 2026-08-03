package com.sismics.docs.rest.resource;

import com.google.common.base.Strings;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.dao.ConfigDao;
import com.sismics.docs.core.model.jpa.Config;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.docs.rest.constant.BaseFunction;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.exception.ServerException;
import com.sismics.rest.util.ValidationUtil;
import com.sismics.util.HttpUtil;
import com.sismics.util.JsonUtil;
import com.sismics.util.css.Selector;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataParam;

import jakarta.json.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * Theme REST resources.
 *
 * @author bgamard
 */
@Path("/theme")
public class ThemeResource extends BaseResource {
    /**
     * Maximum size, in UTF-8 bytes, of the custom stylesheet and of the custom script. The theme
     * configuration is a single 4000-character database column, so real custom CSS/JS never fitted
     * there; both now live in the theme directory as files and are bounded here instead. The cap
     * keeps one careless paste from filling the data directory and keeps the two text endpoints
     * from buffering an unbounded request body.
     */
    private static final int MAX_ASSET_BYTES = 256 * 1024;

    /**
     * File name of the custom stylesheet inside the theme directory.
     */
    private static final String CUSTOM_CSS_FILE = "custom.css";

    /**
     * File name of the custom script inside the theme directory.
     */
    private static final String CUSTOM_JS_FILE = "custom.js";

    /**
     * Content type of the compiled stylesheet. The charset is explicit because custom CSS may
     * carry non-ASCII content (e.g. a `content:` rule) and the SPA loads this as a real
     * stylesheet, where an undeclared charset falls back to the document's.
     */
    private static final String STYLESHEET_CONTENT_TYPE = "text/css;charset=UTF-8";

    /**
     * Content type of the custom script: a JavaScript MIME type with an explicit UTF-8 charset,
     * served alongside X-Content-Type-Options: nosniff so the response can never be re-interpreted
     * as another type.
     */
    private static final String SCRIPT_CONTENT_TYPE = "text/javascript;charset=UTF-8";

	/**
     * Returns custom CSS stylesheet.
     *
     * @api {get} /theme/stylesheet Get the CSS stylesheet
     * @apiName GetThemeStylesheet
     * @apiGroup Theme
     * @apiSuccess {String} stylesheet The whole response is the stylesheet
     * @apiPermission none
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @GET
    @Path("/stylesheet")
    @Produces("text/css")
    public Response stylesheet() {
        return Response.ok()
                .entity(buildStylesheet())
                .type(STYLESHEET_CONTENT_TYPE)
                .build();
    }

    /**
     * Replace the custom stylesheet.
     *
     * @api {put} /theme/stylesheet Replace the custom stylesheet
     * @apiDescription The request body is the raw CSS, at most 256 KiB of UTF-8.
     * @apiName PutThemeStylesheet
     * @apiGroup Theme
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) InvalidEncoding The body is not valid UTF-8
     * @apiError (client) PayloadTooLarge The stylesheet exceeds the size limit
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @param body Raw CSS
     * @return Response
     */
    @PUT
    @Path("/stylesheet")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response putStylesheet(InputStream body) {
        checkAdmin();

        // Delegates to the form endpoint's `css` handling, which is already exactly this contract:
        // write the file, then clear the legacy blob field so the old rules stop being emitted
        // ahead of the new ones (a "replace" that appended would be a bug). Passing null for the
        // other three fields preserves them.
        return theme(null, null, null, readBoundedText(body));
    }

    /**
     * Delete the custom stylesheet.
     *
     * @api {delete} /theme/stylesheet Delete the custom stylesheet
     * @apiName DeleteThemeStylesheet
     * @apiGroup Theme
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @DELETE
    @Path("/stylesheet")
    public Response deleteStylesheet() {
        checkAdmin();

        // An EMPTY css removes the file AND clears the legacy blob field. Both sources have to go:
        // an instance upgraded from a blob-only install would otherwise keep serving its legacy
        // CSS after a "reset".
        return theme(null, null, null, "");
    }

    /**
     * Returns the custom script.
     *
     * @api {get} /theme/script Get the custom script
     * @apiDescription The whole response is the JavaScript, empty when none is configured.
     * @apiName GetThemeScript
     * @apiGroup Theme
     * @apiSuccess {String} script The whole response is the script
     * @apiPermission none
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @GET
    @Path("/script")
    @Produces("text/javascript")
    public Response script() {
        // Public on purpose: the login shell and every ordinary user load this file. It is served
        // as an EXTERNAL same-origin script — the SPA never inlines or evals it.
        return Response.ok()
                .entity(readAsset(CUSTOM_JS_FILE))
                .type(SCRIPT_CONTENT_TYPE)
                .header("X-Content-Type-Options", "nosniff")
                .build();
    }

    /**
     * Replace the custom script.
     *
     * @api {put} /theme/script Replace the custom script
     * @apiDescription The request body is the raw JavaScript, at most 256 KiB of UTF-8.
     * @apiName PutThemeScript
     * @apiGroup Theme
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) InvalidEncoding The body is not valid UTF-8
     * @apiError (client) PayloadTooLarge The script exceeds the size limit
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @param body Raw JavaScript
     * @return Response
     */
    @PUT
    @Path("/script")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response putScript(InputStream body) {
        checkAdmin();

        writeAsset(CUSTOM_JS_FILE, readBoundedText(body));

        return okStatus();
    }

    /**
     * Delete the custom script.
     *
     * @api {delete} /theme/script Delete the custom script
     * @apiName DeleteThemeScript
     * @apiGroup Theme
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @DELETE
    @Path("/script")
    public Response deleteScript() {
        checkAdmin();

        deleteAsset(CUSTOM_JS_FILE);

        return okStatus();
    }

    /**
     * Returns the theme configuration.
     *
     * @api {get} /theme Get the theme configuration
     * @apiName GetTheme
     * @apiGroup Theme
     * @apiSuccess {String} name Application name
     * @apiSuccess {String} color Navbar color
     * @apiSuccess {String} main_color Brand color the UI palette is derived from (null when unset)
     * @apiSuccess {String} css Effective custom CSS
     * @apiSuccess {Number} favicon_version Cache-bust token for the favicon (changes when the uploaded favicon is replaced)
     * @apiSuccess {Number} logo_version Cache-bust token for the logo
     * @apiSuccess {Number} background_version Cache-bust token for the background
     * @apiSuccess {String} stylesheet_version Cache-bust token for the compiled stylesheet
     * @apiSuccess {String} script_version Cache-bust token for the custom script
     * @apiPermission none
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @GET
    public Response get() {
        JsonObject themeConfig = getThemeConfig();
        JsonObjectBuilder json = Json.createObjectBuilder();
        json.add("name", themeConfig.getString("name", "Teedy"));
        json.add("color", themeConfig.getString("color", "#ffffff"));
        // main_color is nullable and null MEANS something: the derived-palette feature is off and
        // the UI keeps its stock colors.
        json.add("main_color", JsonUtil.nullable(themeConfig.getString("main_color", null)));
        // Old clients read the custom CSS from here, so this stays the EFFECTIVE stylesheet body
        // (legacy blob plus the modern file) rather than only the blob field.
        json.add("css", effectiveCss(themeConfig));
        // Cache-bust token for the favicon: the uploaded file's last-modified epoch
        // millis (0 when no custom favicon is uploaded — the bundled default is used).
        // The SPA appends this to the favicon URL so replacing the image forces a
        // re-fetch past the 15-day image cache, without depending on the theme name.
        json.add("favicon_version", imageVersion("favicon"));
        // Same idea for the two images the Branding settings screen previews: the image responses
        // cache for 15 days, so a preview needs a URL that changes on upload and on reset.
        json.add("logo_version", imageVersion("logo"));
        json.add("background_version", imageVersion("background"));
        // The text assets are keyed by a hash of the body actually served rather than by a
        // timestamp: the stylesheet body includes the GENERATED navbar rule, so changing only the
        // navbar color still busts it, and an unchanged file keeps its token across a rewrite.
        json.add("stylesheet_version", bodyVersion(buildStylesheet()));
        json.add("script_version", bodyVersion(readAsset(CUSTOM_JS_FILE)));
        return Response.ok().entity(json.build()).build();
    }

    /**
     * Returns a cache-bust token for a theme image: the last-modified time (epoch
     * millis) of the admin-uploaded file, or 0 when none is uploaded (the bundled
     * default is served, which never changes).
     *
     * @param type Image type (logo|background|favicon)
     * @return Image version token
     */
    private long imageVersion(String type) {
        java.nio.file.Path imagePath = DirectoryUtil.getThemeDirectory().resolve(type);
        if (!Files.exists(imagePath)) {
            return 0L;
        }
        try {
            return Files.getLastModifiedTime(imagePath).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Change the theme configuration.
     *
     * Every form parameter is OPTIONAL and the absent/empty distinction is load-bearing: an
     * ABSENT parameter PRESERVES the stored value, an EMPTY one CLEARS it. The settings UI posts
     * subsets of this form, and a client older than main_color never posts it at all — treating
     * "absent" as "set to null" would let either of them silently wipe fields it never touched.
     *
     * This is also the single implementation of the custom-CSS write: {@link #putStylesheet} and
     * {@link #deleteStylesheet} delegate here, so "write the file, drop the legacy blob field"
     * cannot drift between the modern and the legacy entry point.
     *
     * @api {post} /theme Change the theme configuration
     * @apiName PostTheme
     * @apiGroup Theme
     * @apiParam {String} name Application name (absent = keep, empty = reset)
     * @apiParam {String} color Navbar color (absent = keep, empty = reset)
     * @apiParam {String} main_color Brand color the UI palette is derived from (absent = keep, empty = clear)
     * @apiParam {String} css Custom CSS (absent = keep, empty = clear)
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiError (client) PayloadTooLarge The stylesheet exceeds the size limit
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @param color Navbar color
     * @param name Application name
     * @param mainColor Brand color
     * @param css Custom CSS
     * @return Response
     */
    @POST
    public Response theme(@FormParam("color") String color,
              @FormParam("name") String name,
              @FormParam("main_color") String mainColor,
              @FormParam("css") String css) {
        checkAdmin();

        // Validate input data
        ValidationUtil.validateHexColor(color, "color", true);
        ValidationUtil.validateHexColor(mainColor, "main_color", true);
        name = ValidationUtil.validateLength(name, "name", 3, 30, true);
        // The css size limit is NOT checked here: writeAsset owns that invariant for every caller,
        // and it runs before this method's first mutation. Duplicating it would invite the two
        // copies to drift.

        // A supplied css promotes the content into the file and drops the legacy blob, so the two
        // never disagree about what the effective stylesheet is. The file is written BEFORE the
        // blob is dropped: a failure in between leaves both present (the stylesheet is momentarily
        // the concatenation), where the reverse order would lose the old CSS outright.
        if (css != null) {
            writeAsset(CUSTOM_CSS_FILE, css);
        }

        // Update the JSON
        JsonObjectBuilder json = getMutableThemeConfig();
        putNullable(json, "color", color);
        putNullable(json, "name", name);
        putNullable(json, "main_color", mainColor);
        if (css != null) {
            json.remove("css");
        }

        // Persist the new configuration
        ConfigDao configDao = new ConfigDao();
        configDao.update(ConfigType.THEME, json.build().toString());

        return okStatus();
    }

    /**
     * Change a theme image.
     *
     * @api {put} /theme/image/:type Change a theme image
     * @apiDescription This resource accepts only multipart/form-data.
     * @apiName PutThemeImage
     * @apiGroup Theme
     * @apiParam {String="logo","background","favicon"} type Image type
     * @apiParam {String} image Image data
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) NoImageProvided An image is required
     * @apiError (server) CopyError Error copying the image to the theme directory
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @param type Image type
     * @param imageBodyPart Image data
     * @return Response
     */
    @PUT
    @Path("image/{type: logo|background|favicon}")
    @Consumes("multipart/form-data")
    public Response images(@PathParam("type") String type,
            @FormDataParam("image") FormDataBodyPart imageBodyPart) {
        checkAdmin();
        if (imageBodyPart == null) {
            throw new ClientException("NoImageProvided", "An image is required");
        }

        // Only a background or a logo is handled
        java.nio.file.Path filePath = DirectoryUtil.getThemeDirectory().resolve(type);

        // Copy the image to the theme directory
        try (InputStream inputStream = imageBodyPart.getValueAs(InputStream.class)) {
            Files.copy(inputStream, filePath, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new ServerException("CopyError", "Error copying the image to the theme directory", e);
        }

        return Response.ok().build();
    }

    /**
     * Reset a theme image to the bundled default.
     *
     * @api {delete} /theme/image/:type Reset a theme image
     * @apiName DeleteThemeImage
     * @apiGroup Theme
     * @apiParam {String="logo","background","favicon"} type Image type
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (server) DeleteError Error deleting the image from the theme directory
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @param type Image type
     * @return Response
     */
    @DELETE
    @Path("image/{type: logo|background|favicon}")
    public Response deleteImage(@PathParam("type") String type) {
        checkAdmin();

        // Dropping the override is the reset: getImage falls back to the bundled default whenever
        // the file is absent. Resetting an image that was never uploaded is a no-op.
        java.nio.file.Path filePath = DirectoryUtil.getThemeDirectory().resolve(type);
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            throw new ServerException("DeleteError", "Error deleting the image from the theme directory", e);
        }

        return okStatus();
    }

    /**
     * Get theme images.
     *
     * @api {get} /theme/image/:type Get a theme image
     * @apiName GetThemeImage
     * @apiGroup Theme
     * @apiParam {String="logo","background","favicon"} type Image type
     * @apiSuccess {String} image The whole response is the image
     * @apiPermission none
     * @apiVersion 1.5.0
     *
     * @param type Image type
     * @return Response
     */
    @GET
    @Produces("image/*")
    @Path("image/{type: logo|background|favicon}")
    public Response getImage(@PathParam("type") final String type) {
        final java.nio.file.Path filePath = DirectoryUtil.getThemeDirectory().resolve(type);

        // Copy the image to the response output
        return Response.ok(new StreamingOutput() {
            @Override
            public void write(OutputStream outputStream) throws IOException, WebApplicationException {
                InputStream inputStream = null;
                try {
                    if (Files.exists(filePath)) {
                        inputStream = Files.newInputStream(filePath);
                    } else {
                        inputStream = getClass().getResource("/image/" + defaultImageName(type)).openStream();
                    }
                    ByteStreams.copy(inputStream, outputStream);
                } finally {
                    try {
                        if (inputStream != null) {
                            inputStream.close();
                        }
                        outputStream.close();
                    } catch (IOException e) {
                        // Ignore
                    }
                }
            }
        })
        .header(HttpHeaders.CONTENT_TYPE, "image/*")
        .header(HttpHeaders.CACHE_CONTROL, "public")
        .header(HttpHeaders.EXPIRES, HttpUtil.buildExpiresHeader(3_600_000L * 24L * 15L))
        .build();
    }

    /**
     * Returns the bundled default image name backing a theme image type when no
     * admin-uploaded override exists in the theme directory.
     *
     * @param type Image type (logo|background|favicon)
     * @return Classpath image file name under /image
     */
    private static String defaultImageName(String type) {
        switch (type) {
            case "background":
                return "background.jpg";
            case "favicon":
                return "favicon.ico";
            default:
                return "logo.png";
        }
    }

    /**
     * Rejects the request unless the caller is an authenticated admin. Every theme MUTATION goes
     * through here; the reads are deliberately public (the login shell is anonymous).
     */
    private void checkAdmin() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);
    }

    /**
     * The standard {"status": "ok"} response body.
     *
     * @return Response
     */
    private static Response okStatus() {
        return Response.ok().entity(Json.createObjectBuilder()
                .add("status", "ok").build()).build();
    }

    /**
     * Writes a nullable theme field with absent=preserve / empty=clear semantics.
     *
     * @param json Theme configuration under construction (already seeded with the stored values)
     * @param key Field name
     * @param value Submitted value: null when the caller omitted the parameter
     */
    private static void putNullable(JsonObjectBuilder json, String key, String value) {
        if (value == null) {
            // Absent: leave whatever getMutableThemeConfig copied over in place.
            return;
        }
        if (Strings.isNullOrEmpty(value)) {
            json.add(key, JsonValue.NULL);
        } else {
            json.add(key, value);
        }
    }

    /**
     * Compiles the stylesheet actually served: the generated navbar rule first (so custom CSS can
     * override it), then the legacy blob CSS, then the modern custom.css file.
     *
     * @return Stylesheet body
     */
    private String buildStylesheet() {
        JsonObject themeConfig = getThemeConfig();
        StringBuilder sb = new StringBuilder();
        sb.append(new Selector(".navbar")
            .rule("background-color", themeConfig.getString("color", "#ffffff")));
        sb.append(effectiveCss(themeConfig));
        return sb.toString();
    }

    /**
     * The effective custom CSS: the legacy blob field followed by the modern custom.css file. In
     * practice only one of the two is populated — a modern write clears the blob — but an install
     * that has never been written since the upgrade still has only the blob, which is what keeps
     * it working without a migration.
     *
     * @param themeConfig Theme configuration
     * @return Custom CSS
     */
    private String effectiveCss(JsonObject themeConfig) {
        String legacy = themeConfig.getString("css", "");
        String file = readAsset(CUSTOM_CSS_FILE);
        if (legacy.isEmpty()) {
            return file;
        }
        if (file.isEmpty()) {
            return legacy;
        }
        return legacy + "\n" + file;
    }

    /**
     * A cache-bust token for a text asset, derived from the body actually served. Injected by the
     * SPA as ?v=<token>, so a body that did not change keeps its token (no needless re-fetch) and
     * any change to it — including one to the GENERATED part of the stylesheet — produces a new one.
     *
     * @param body Response body
     * @return Version token
     */
    private static String bodyVersion(String body) {
        if (body.isEmpty()) {
            // An EMPTY body gets an EMPTY token rather than the hash of the empty string: that is
            // how the SPA knows there is no custom script to load at all, without fetching it.
            return "";
        }
        return Hashing.sha256().hashString(body, StandardCharsets.UTF_8).toString().substring(0, 16);
    }

    /**
     * Reads a text asset from the theme directory.
     *
     * @param fileName Asset file name
     * @return Asset content, empty when the asset does not exist
     */
    private String readAsset(String fileName) {
        java.nio.file.Path filePath = DirectoryUtil.getThemeDirectory().resolve(fileName);
        if (!Files.exists(filePath)) {
            return "";
        }
        try {
            return new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ServerException("ReadError", "Error reading " + fileName + " from the theme directory", e);
        }
    }

    /**
     * Writes a text asset into the theme directory, replacing it atomically: the content goes to a
     * temporary file in the same directory and is then moved over the target, so an interrupted
     * write can never leave the live asset truncated. An empty content deletes the asset instead.
     *
     * The size cap is enforced HERE, on the bytes that actually reach the disk, and not only on the
     * bytes the client sent: those two are not the same number. A request body of lone UTF-8
     * continuation bytes sits inside the raw-length bound, yet each byte decodes to U+FFFD and
     * re-encodes to three, so a payload exactly on the limit would triple on write. Putting the
     * check at the single write seam makes the guarantee uniform for every asset and every caller.
     *
     * @param fileName Asset file name
     * @param content Asset content
     */
    private void writeAsset(String fileName, String content) {
        if (content.isEmpty()) {
            deleteAsset(fileName);
            return;
        }
        validateAssetSize(content);

        java.nio.file.Path directory = DirectoryUtil.getThemeDirectory();
        java.nio.file.Path filePath = directory.resolve(fileName);
        java.nio.file.Path tempPath = null;
        try {
            tempPath = Files.createTempFile(directory, fileName, ".tmp");
            Files.write(tempPath, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tempPath, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some filesystems cannot move atomically; a plain replace is still better than
                // writing the target in place.
                Files.move(tempPath, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
            tempPath = null;
        } catch (IOException e) {
            throw new ServerException("WriteError", "Error writing " + fileName + " to the theme directory", e);
        } finally {
            if (tempPath != null) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Deletes a text asset from the theme directory.
     *
     * @param fileName Asset file name
     */
    private void deleteAsset(String fileName) {
        try {
            Files.deleteIfExists(DirectoryUtil.getThemeDirectory().resolve(fileName));
        } catch (IOException e) {
            throw new ServerException("DeleteError", "Error deleting " + fileName + " from the theme directory", e);
        }
    }

    /**
     * Reads a text request body, bounded at {@link #MAX_ASSET_BYTES}. A declared Content-Length
     * over the limit is refused before a byte is read, and the stream itself is consumed only up
     * to limit + 1 bytes so a lying or chunked sender cannot make the server buffer megabytes.
     *
     * @param body Request body
     * @return Decoded body
     */
    private String readBoundedText(InputStream body) {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_ASSET_BYTES) {
            throw payloadTooLarge(declaredLength);
        }
        byte[] bytes;
        try {
            bytes = ByteStreams.toByteArray(ByteStreams.limit(body, MAX_ASSET_BYTES + 1L));
        } catch (IOException e) {
            throw new ServerException("ReadError", "Error reading the request body", e);
        }
        if (bytes.length > MAX_ASSET_BYTES) {
            throw payloadTooLarge(bytes.length);
        }

        // Decoded STRICTLY rather than with the replacement-character default: a body that is not
        // valid UTF-8 is a client error, and silently substituting U+FFFD would both corrupt the
        // stored asset and inflate it (one bad byte in, three bytes out).
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ClientException("InvalidEncoding", "The submitted content is not valid UTF-8");
        }
    }

    /**
     * Validates that a text asset fits the size limit, measured on its UTF-8 encoding — the form
     * of it that gets stored and served.
     *
     * @param value Asset content
     */
    private static void validateAssetSize(String value) {
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        if (length > MAX_ASSET_BYTES) {
            throw payloadTooLarge(length);
        }
    }

    /**
     * HTTP 413, in the same {type, message} envelope as every other error this API returns. There
     * is no Response.Status constant for it in JAX-RS.
     *
     * @param length Submitted length in bytes
     * @return Exception to throw
     */
    private static WebApplicationException payloadTooLarge(long length) {
        return new WebApplicationException(Response.status(413)
                .entity(Json.createObjectBuilder()
                        .add("type", "PayloadTooLarge")
                        .add("message", "The submitted content is " + length
                                + " bytes, over the " + MAX_ASSET_BYTES + " bytes limit")
                        .build())
                .type(MediaType.APPLICATION_JSON)
                .build());
    }

    /**
     * Returns the theme configuration object.
     *
     * @return Theme configuration
     */
    private JsonObject getThemeConfig() {
        ConfigDao configDao = new ConfigDao();
        Config themeConfig = configDao.getById(ConfigType.THEME);
        if (themeConfig == null) {
            return Json.createObjectBuilder().build();
        }

        try (JsonReader reader = Json.createReader(new StringReader(themeConfig.getValue()))) {
            return reader.readObject();
        }
    }

    /**
     * Returns a mutable copy of the stored theme configuration. Every stored field is carried over,
     * so a caller only has to write the fields it actually means to change — see the absent=preserve
     * contract on {@link #theme}.
     *
     * @return Json builder
     */
    private JsonObjectBuilder getMutableThemeConfig() {
        JsonObject themeConfig = getThemeConfig();
        JsonObjectBuilder json = Json.createObjectBuilder();

        for (Map.Entry<String, JsonValue> entry : themeConfig.entrySet()) {
            json.add(entry.getKey(), entry.getValue());
        }

        return json;
    }
}
