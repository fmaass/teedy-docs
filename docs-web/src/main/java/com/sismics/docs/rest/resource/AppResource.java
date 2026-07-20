package com.sismics.docs.rest.resource;

import com.google.common.base.Strings;
import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.dao.CleanupRunDao;
import com.sismics.docs.core.dao.ConfigDao;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.StatsDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.dao.dto.CleanupFileDto;
import com.sismics.docs.core.dao.dto.UserStorageDto;
import com.sismics.docs.core.event.RebuildIndexAsyncEvent;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.model.jpa.CleanupRun;
import com.sismics.docs.core.model.jpa.Config;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.service.InboxService;
import com.sismics.docs.core.service.TrashPurgeService;
import com.sismics.docs.core.util.CleanupStorageUtil;
import com.sismics.docs.core.util.ConfigUtil;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.docs.core.util.FileUtil;
import com.sismics.docs.core.util.TransactionBoundary;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.docs.core.util.StatsBucketUtil;
import com.sismics.docs.core.util.jpa.PaginatedList;
import com.sismics.docs.core.util.jpa.PaginatedLists;
import com.sismics.docs.rest.constant.BaseFunction;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.exception.ServerException;
import com.sismics.rest.util.ValidationUtil;
import com.sismics.util.EnvironmentUtil;
import com.sismics.util.JsonUtil;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;
import com.sismics.util.log4j.LogCriteria;
import com.sismics.util.log4j.LogEntry;
import com.sismics.util.log4j.MemoryAppender;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Appender;
import org.apache.log4j.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.text.MessageFormat;
import java.util.*;

/**
 * General app REST resource.
 *
 * @author jtremeaux
 */
@Path("/app")
public class AppResource extends BaseResource {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(AppResource.class);

    /**
     * Test seam (#69): a hook fired inside {@link #batchCleanStorage()} immediately BEFORE the
     * post-DB-delete commit checkpoint and the filesystem sweep. It is invoked ONLY when
     * {@link EnvironmentUtil#isUnitTest()} is true, so it is a genuine no-op in the running webapp
     * regardless of this field's value. A test sets it to throw, proving that a DB/commit-phase failure
     * aborts the run with the physical files still intact — the sweep only runs after a successful
     * commit, so a rollback never loses bytes.
     */
    static volatile Runnable cleanStorageBeforeCommitHook = null;

    /**
     * Test seam (concurrent-restore): a hook fired inside {@link #batchCleanStorage()} AFTER the commit
     * checkpoint but BEFORE the physical sweep, invoked ONLY when {@link EnvironmentUtil#isUnitTest()}
     * is true (a genuine no-op in the running webapp). A test uses it to revive a soft-deleted file's
     * row (simulating a concurrent restore between the manifest snapshot and the sweep), proving the
     * sweep skips any candidate that is no longer gone from the DB and never deletes a live file's bytes.
     */
    static volatile Runnable cleanStorageBeforeSweepHook = null;

    /**
     * Test seam (#79 concurrent sweep): a per-file hook fired inside the post-commit filesystem sweep
     * AFTER a run deletes a logical file's BASE variant but BEFORE its derivatives, WHILE the run holds
     * {@link #STORAGE_SWEEP_LOCK}. Invoked ONLY when {@link EnvironmentUtil#isUnitTest()} is true (a
     * genuine no-op in the running webapp regardless of this field). A test uses it to hold one run inside
     * the sweep lock and prove a concurrent run's sweep BLOCKS on the lock (serialization), so each
     * physically-removed logical file is counted EXACTLY once across both runs.
     */
    static volatile java.util.function.Consumer<String> cleanStorageDuringReclaimHook = null;

    /**
     * In-process lock (#79) serializing the ENTIRE post-commit filesystem sweep of {@code clean_storage}.
     * Concurrent runs are REST requests handled by threads in this single JVM, which owns the storage
     * directory, so an in-process lock is the correct race-free serialization — no filesystem markers, no
     * TOCTOU. It is acquired AFTER the checkpoint commit releases the DB locks, so it never holds a
     * database transaction across the (potentially long) filesystem I/O. Assumes ONE application instance
     * owns the storage directory (the supported single-container deployment); a future multi-instance
     * deployment sharing storage would need a distributed sweep lock instead.
     */
    private static final java.util.concurrent.locks.ReentrantLock STORAGE_SWEEP_LOCK =
            new java.util.concurrent.locks.ReentrantLock();

    /**
     * Test-only probe: true when at least one thread is blocked waiting to acquire {@link #STORAGE_SWEEP_LOCK}.
     * Lets the serialization test deterministically prove a second run's sweep is BLOCKED behind a first
     * run that holds the lock, with no wall-clock timeout deciding the outcome.
     *
     * @return whether a thread is queued on the sweep lock
     */
    static boolean sweepLockHasQueuedThreads() {
        return STORAGE_SWEEP_LOCK.hasQueuedThreads();
    }

    /**
     * Returns information about the application.
     *
     * @api {get} /app Get application information
     * @apiName GetApp
     * @apiGroup App
     * @apiSuccess {String} current_version API current version
     * @apiSuccess {String} min_version API minimum version
     * @apiSuccess {Boolean} guest_login True if guest login is enabled
     * @apiSuccess {String} default_language Default platform language
     * @apiSuccess {Number} queued_tasks Number of queued tasks waiting to be processed
     * @apiSuccess {String} total_memory Allocated JVM memory (in bytes)
     * @apiSuccess {String} free_memory Free JVM memory (in bytes)
     * @apiSuccess {String} document_count Number of documents
     * @apiSuccess {String} active_user_count Number of active users
     * @apiSuccess {String} global_storage_current Global storage currently used (in bytes)
     * @apiSuccess {String} global_storage_quota Maximum global storage (in bytes)
     * @apiSuccess {Number} trash_retention_days Trash retention window in days before automatic purge
     * @apiPermission none
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @GET
    public Response info() {
        ResourceBundle configBundle = ConfigUtil.getConfigBundle();
        String currentVersion = configBundle.getString("api.current_version");
        String minVersion = configBundle.getString("api.min_version");
        Boolean guestLogin = ConfigUtil.getConfigBooleanValue(ConfigType.GUEST_LOGIN);
        Boolean ocrEnabled = ConfigUtil.getConfigBooleanValue(ConfigType.OCR_ENABLED, true);
        String defaultLanguage = ConfigUtil.getConfigStringValue(ConfigType.DEFAULT_LANGUAGE);
        String tagSearchMode;
        try {
            tagSearchMode = ConfigUtil.getConfigStringValue(ConfigType.TAG_SEARCH_MODE);
        } catch (IllegalStateException e) {
            tagSearchMode = "PREFIX";
        }
        UserDao userDao = new UserDao();
        DocumentDao documentDao = new DocumentDao();
        String globalQuotaStr = System.getenv(Constants.GLOBAL_QUOTA_ENV);
        long globalQuota = 0;
        if (!Strings.isNullOrEmpty(globalQuotaStr)) {
            globalQuota = Long.valueOf(globalQuotaStr);
        }

        boolean headerAuthEnabled = Boolean.parseBoolean(System.getProperty("docs.header_authentication"));
        // OIDC "enabled" flows through the single OIDC config accessor (DB override, else the
        // docs.oidc_enabled property) so a DB-configured enable is reflected without a restart.
        boolean oidcEnabled = OidcResource.isOidcEnabled();

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("current_version", currentVersion.replace("-SNAPSHOT", ""))
                .add("min_version", minVersion)
                .add("guest_login", guestLogin)
                .add("ocr_enabled", ocrEnabled)
                .add("default_language", defaultLanguage)
                .add("header_authentication_enabled", headerAuthEnabled)
                .add("oidc_enabled", oidcEnabled)
                .add("queued_tasks", AppContext.getInstance().getQueuedTaskCount())
                .add("total_memory", Runtime.getRuntime().totalMemory())
                .add("free_memory", Runtime.getRuntime().freeMemory())
                .add("document_count", documentDao.getDocumentCount())
                .add("active_user_count", userDao.getActiveUserCount())
                .add("global_storage_current", userDao.getGlobalStorageCurrent())
                .add("tag_search_mode", tagSearchMode);

        // Max upload size (from env or default 500MB)
        String maxUploadStr = System.getenv("DOCS_MAX_UPLOAD_SIZE");
        long maxUploadSize = maxUploadStr != null ? Long.parseLong(maxUploadStr) : 524288000L;
        response.add("max_upload_size", maxUploadSize);

        // Trash retention window in days (single source of truth: TrashPurgeService),
        // surfaced so the SPA trash countdown reflects the configured window.
        response.add("trash_retention_days", TrashPurgeService.getRetentionDays());

        if (globalQuota > 0) {
            response.add("global_storage_quota", globalQuota);
        }

        // Configurable footer/imprint links (public chrome like guest_login). Anonymous
        // callers get these so EU imprint links can render on the login screen BEFORE
        // login. Absent when unset or empty, so today's chrome (nothing) is unchanged.
        JsonArray footerLinks = getFooterLinks();
        if (!footerLinks.isEmpty()) {
            response.add("footer_links", footerLinks);
        }

        return Response.ok().entity(response.build()).build();
    }

    /**
     * Reads the stored footer-links JSON array (the ThemeResource JSON-blob-in-one-key
     * precedent). Returns an empty array when unset or the stored value is not a JSON
     * array, so callers never have to null-check.
     *
     * @return Footer links as a JSON array (possibly empty)
     */
    private JsonArray getFooterLinks() {
        ConfigDao configDao = new ConfigDao();
        Config config = configDao.getById(ConfigType.FOOTER_LINKS);
        if (config == null || Strings.isNullOrEmpty(config.getValue())) {
            return Json.createArrayBuilder().build();
        }
        try (JsonReader reader = Json.createReader(new StringReader(config.getValue()))) {
            JsonValue value = reader.readValue();
            if (value.getValueType() == JsonValue.ValueType.ARRAY) {
                return value.asJsonArray();
            }
        } catch (Exception e) {
            log.warn("Stored FOOTER_LINKS value is not valid JSON; treating as empty", e);
        }
        return Json.createArrayBuilder().build();
    }

    /**
     * Update the configurable footer/imprint links.
     *
     * @api {post} /app/footer_links Set the footer/imprint links
     * @apiName PostAppFooterLinks
     * @apiGroup App
     * @apiParam {String} links JSON array of {label, url} objects (max 5, http(s) URLs only)
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError A link entry is invalid or the cap is exceeded
     * @apiPermission admin
     * @apiVersion 1.12.0
     *
     * @param links JSON array of footer-link objects
     * @return Response
     */
    @POST
    @Path("footer_links")
    public Response footerLinks(@FormParam("links") String links) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        // Parse and validate the submitted JSON array, then store the RE-SERIALIZED,
        // normalised array (never the raw input) so only well-formed {label, url}
        // entries — trimmed, scheme-checked, capped — are ever persisted.
        JsonArray parsed;
        try (JsonReader reader = Json.createReader(new StringReader(Strings.nullToEmpty(links)))) {
            JsonValue value = reader.readValue();
            if (value.getValueType() != JsonValue.ValueType.ARRAY) {
                throw new ClientException("ValidationError", "links must be a JSON array");
            }
            parsed = value.asJsonArray();
        } catch (ClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ClientException("ValidationError", "links must be a valid JSON array");
        }

        if (parsed.size() > 5) {
            throw new ClientException("ValidationError", "A maximum of 5 footer links is allowed");
        }

        JsonArrayBuilder normalized = Json.createArrayBuilder();
        for (int i = 0; i < parsed.size(); i++) {
            JsonValue entryValue = parsed.get(i);
            if (entryValue.getValueType() != JsonValue.ValueType.OBJECT) {
                throw new ClientException("ValidationError", "Each footer link must be an object with a label and a url");
            }
            JsonObject entry = entryValue.asJsonObject();
            String label = entry.containsKey("label") && entry.get("label").getValueType() == JsonValue.ValueType.STRING
                    ? entry.getString("label") : null;
            String url = entry.containsKey("url") && entry.get("url").getValueType() == JsonValue.ValueType.STRING
                    ? entry.getString("url") : null;
            label = ValidationUtil.validateStringNotBlank(label, "label");
            label = ValidationUtil.validateLength(label, "label", 1, 40);
            url = ValidationUtil.validateLength(url, "url", 1, 500);
            // http(s) scheme only — validateHttpUrl rejects javascript:, data:, etc.
            url = ValidationUtil.validateHttpUrl(url, "url");
            normalized.add(Json.createObjectBuilder()
                    .add("label", label)
                    .add("url", url));
        }

        ConfigDao configDao = new ConfigDao();
        configDao.update(ConfigType.FOOTER_LINKS, normalized.build().toString());

        return Response.ok().build();
    }

    /**
     * Enable/disable guest login.
     *
     * @api {post} /app/guest_login Enable/disable guest login
     * @apiName PostAppGuestLogin
     * @apiGroup App
     * @apiParam {Boolean} enabled If true, enable guest login
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @param enabled If true, enable guest login
     * @return Response
     */
    @POST
    @Path("guest_login")
    public Response guestLogin(@FormParam("enabled") Boolean enabled) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        ConfigDao configDao = new ConfigDao();
        configDao.update(ConfigType.GUEST_LOGIN, enabled.toString());

        return Response.ok().build();
    }

    /**
     * Enable/disable OCR.
     *
     * @api {post} /app/ocr Enable/disable OCR
     * @apiName PostAppOcr
     * @apiGroup App
     * @apiParam {Boolean} enabled If true, enable OCR
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @param enabled If true, enable OCR
     * @return Response
     */
    @POST
    @Path("ocr")
    public Response ocr(@FormParam("enabled") Boolean enabled) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        ConfigDao configDao = new ConfigDao();
        configDao.update(ConfigType.OCR_ENABLED, enabled.toString());

        return Response.ok().build();
    }

    /**
     * General application configuration.
     *
     * @api {post} /app/config General application configuration
     * @apiName PostAppConfig
     * @apiGroup App
     * @apiParam {String} default_language Default language
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @param defaultLanguage Default language
     * @return Response
     */
    @POST
    @Path("config")
    public Response config(
            @FormParam("default_language") String defaultLanguage,
            @FormParam("tag_search_mode") String tagSearchMode) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);
        ValidationUtil.validateRequired(defaultLanguage, "default_language");
        if (!Constants.SUPPORTED_LANGUAGES.contains(defaultLanguage)) {
            throw new ClientException("ValidationError", MessageFormat.format("{0} is not a supported language", defaultLanguage));
        }

        ConfigDao configDao = new ConfigDao();
        configDao.update(ConfigType.DEFAULT_LANGUAGE, defaultLanguage);

        if (tagSearchMode != null) {
            if (!"PREFIX".equals(tagSearchMode) && !"EXACT".equals(tagSearchMode)) {
                throw new ClientException("ValidationError", "tag_search_mode must be PREFIX or EXACT");
            }
            configDao.update(ConfigType.TAG_SEARCH_MODE, tagSearchMode);
        }

        return Response.ok().build();
    }

    /**
     * Get the SMTP server configuration.
     *
     * @api {get} /app/config_smtp Get the SMTP server configuration
     * @apiName GetAppConfigSmtp
     * @apiGroup App
     * @apiSuccess {String} hostname SMTP hostname
     * @apiSuccess {String} port SMTP port
     * @apiSuccess {String} username SMTP username
     * @apiSuccess {String} from From address
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @GET
    @Path("config_smtp")
    public Response getConfigSmtp() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        ConfigDao configDao = new ConfigDao();
        Config hostnameConfig = configDao.getById(ConfigType.SMTP_HOSTNAME);
        Config portConfig = configDao.getById(ConfigType.SMTP_PORT);
        Config usernameConfig = configDao.getById(ConfigType.SMTP_USERNAME);
        Config fromConfig = configDao.getById(ConfigType.SMTP_FROM);
        JsonObjectBuilder response = Json.createObjectBuilder();
        if (Strings.isNullOrEmpty(System.getenv(Constants.SMTP_HOSTNAME_ENV))) {
            if (hostnameConfig == null) {
                response.addNull("hostname");
            } else {
                response.add("hostname", hostnameConfig.getValue());
            }
        }
        if (Strings.isNullOrEmpty(System.getenv(Constants.SMTP_PORT_ENV))) {
            if (portConfig == null) {
                response.addNull("port");
            } else {
                response.add("port", Integer.valueOf(portConfig.getValue()));
            }
        }
        if (Strings.isNullOrEmpty(System.getenv(Constants.SMTP_USERNAME_ENV))) {
            if (usernameConfig == null) {
                response.addNull("username");
            } else {
                response.add("username", usernameConfig.getValue());
            }
        }
        // The SMTP password is write-only: it is NEVER echoed back (BL-028 sibling). The
        // POST keeps the stored value when the password field is absent/empty, so the UI
        // shows a "leave blank to keep" affordance instead of the secret.
        if (fromConfig == null) {
            response.addNull("from");
        } else {
            response.add("from", fromConfig.getValue());
        }

        return Response.ok().entity(response.build()).build();
    }

    /**
     * Configure the SMTP server.
     *
     * @api {post} /app/config_smtp Configure the SMTP server
     * @apiName PostAppConfigSmtp
     * @apiGroup App
     * @apiParam {String} hostname SMTP hostname
     * @apiParam {Integer} port SMTP port
     * @apiParam {String} username SMTP username
     * @apiParam {String} password SMTP password
     * @apiParam {String} from From address
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @param hostname SMTP hostname
     * @param portStr SMTP port
     * @param username SMTP username
     * @param password SMTP password
     * @param from From address
     * @return Response
     */
    @POST
    @Path("config_smtp")
    public Response configSmtp(@FormParam("hostname") String hostname,
                               @FormParam("port") String portStr,
                               @FormParam("username") String username,
                               @FormParam("password") String password,
                               @FormParam("from") String from) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);
        if (!Strings.isNullOrEmpty(portStr)) {
            ValidationUtil.validateInteger(portStr, "port");
        }

        // Just update the changed configuration
        ConfigDao configDao = new ConfigDao();
        if (!Strings.isNullOrEmpty(hostname)) {
            configDao.update(ConfigType.SMTP_HOSTNAME, hostname);
        }
        if (!Strings.isNullOrEmpty(portStr)) {
            configDao.update(ConfigType.SMTP_PORT, portStr);
        }
        if (!Strings.isNullOrEmpty(username)) {
            configDao.update(ConfigType.SMTP_USERNAME, username);
        }
        if (!Strings.isNullOrEmpty(password)) {
            configDao.update(ConfigType.SMTP_PASSWORD, password);
        }
        if (!Strings.isNullOrEmpty(from)) {
            configDao.update(ConfigType.SMTP_FROM, from);
        }

        return Response.ok().build();
    }

    /**
     * Get the inbox configuration.
     *
     * @api {get} /app/config_inbox Get the inbox scanning configuration
     * @apiName GetAppConfigInbox
     * @apiGroup App
     * @apiSuccess {Boolean} enabled True if the inbox scanning is enabled
     * @apiSuccess {String} hostname IMAP hostname
     * @apiSuccess {String} port IMAP port
     * @apiSuccess {String} username IMAP username
     * @apiSuccess {String} folder IMAP folder
     * @apiSuccess {String} tag Tag for created documents
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @GET
    @Path("config_inbox")
    public Response getConfigInbox() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        ConfigDao configDao = new ConfigDao();
        Boolean enabled = ConfigUtil.getConfigBooleanValue(ConfigType.INBOX_ENABLED);
        Boolean autoTags = ConfigUtil.getConfigBooleanValue(ConfigType.INBOX_AUTOMATIC_TAGS);
        Boolean deleteImported = ConfigUtil.getConfigBooleanValue(ConfigType.INBOX_DELETE_IMPORTED);
        Config hostnameConfig = configDao.getById(ConfigType.INBOX_HOSTNAME);
        Config portConfig = configDao.getById(ConfigType.INBOX_PORT);
        Boolean starttls = ConfigUtil.getConfigBooleanValue(ConfigType.INBOX_STARTTLS);
        Config usernameConfig = configDao.getById(ConfigType.INBOX_USERNAME);
        Config folderConfig = configDao.getById(ConfigType.INBOX_FOLDER);
        Config tagConfig = configDao.getById(ConfigType.INBOX_TAG);
        JsonObjectBuilder response = Json.createObjectBuilder();

        response.add("enabled", enabled);
        response.add("autoTagsEnabled", autoTags);
        response.add("deleteImported", deleteImported);
        if (hostnameConfig == null) {
            response.addNull("hostname");
        } else {
            response.add("hostname", hostnameConfig.getValue());
        }
        if (portConfig == null) {
            response.addNull("port");
        } else {
            response.add("port", Integer.valueOf(portConfig.getValue()));
        }
        response.add("starttls", starttls);
        if (usernameConfig == null) {
            response.addNull("username");
        } else {
            response.add("username", usernameConfig.getValue());
        }
        // The IMAP password is write-only: it is NEVER echoed back (BL-028 sibling). The
        // POST keeps the stored value when the password field is absent/empty.
        if (folderConfig == null) {
            response.addNull("folder");
        } else {
            response.add("folder", folderConfig.getValue());
        }
        if (tagConfig == null) {
            response.addNull("tag");
        } else {
            response.add("tag", tagConfig.getValue());
        }

        // Informations about the last synchronization
        InboxService inboxService = AppContext.getInstance().getInboxService();
        JsonObjectBuilder lastSync = Json.createObjectBuilder();
        if (inboxService.getLastSyncDate() == null) {
            lastSync.addNull("date");
        } else {
            lastSync.add("date", inboxService.getLastSyncDate().getTime());
        }
        lastSync.add("error", JsonUtil.nullable(inboxService.getLastSyncError()));
        lastSync.add("count", inboxService.getLastSyncMessageCount());
        response.add("last_sync", lastSync);

        return Response.ok().entity(response.build()).build();
    }

    /**
     * Configure the inbox.
     *
     * @api {post} /app/config_inbox Configure the inbox scanning
     * @apiName PostAppConfigInbox
     * @apiGroup App
     * @apiParam {Boolean} enabled True if the inbox scanning is enabled
     * @apiParam {Boolean} autoTagsEnabled If true automatically add tags to document (prefixed by #)
     * @apiParam {Boolean} deleteImported If true delete message from mailbox after import
     * @apiParam {String} hostname IMAP hostname
     * @apiParam {Integer} port IMAP port
     * @apiParam {String} username IMAP username
     * @apiParam {String} password IMAP password
     * @apiParam {String} folder IMAP folder
     * @apiParam {String} tag Tag for created documents
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @param enabled True if the inbox scanning is enabled
     * @param hostname IMAP hostname
     * @param portStr IMAP port
     * @param username IMAP username
     * @param password IMAP password
     * @param folder IMAP folder
     * @param tag Tag for created documents
     * @return Response
     */
    @POST
    @Path("config_inbox")
    public Response configInbox(@FormParam("enabled") Boolean enabled,
                                @FormParam("autoTagsEnabled") Boolean autoTagsEnabled,
                                @FormParam("deleteImported") Boolean deleteImported,
                                @FormParam("hostname") String hostname,
                                @FormParam("port") String portStr,
                                @FormParam("starttls") Boolean starttls,
                                @FormParam("username") String username,
                                @FormParam("password") String password,
                                @FormParam("folder") String folder,
                                @FormParam("tag") String tag) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);
        ValidationUtil.validateRequired(enabled, "enabled");
        ValidationUtil.validateRequired(autoTagsEnabled, "autoTagsEnabled");
        ValidationUtil.validateRequired(deleteImported, "deleteImported");
        if (!Strings.isNullOrEmpty(portStr)) {
            ValidationUtil.validateInteger(portStr, "port");
        }
        ValidationUtil.validateRequired(starttls, "starttls");

        // Just update the changed configuration
        ConfigDao configDao = new ConfigDao();
        configDao.update(ConfigType.INBOX_ENABLED, enabled.toString());
        configDao.update(ConfigType.INBOX_AUTOMATIC_TAGS, autoTagsEnabled.toString());
        configDao.update(ConfigType.INBOX_DELETE_IMPORTED, deleteImported.toString());
        if (!Strings.isNullOrEmpty(hostname)) {
            configDao.update(ConfigType.INBOX_HOSTNAME, hostname);
        }
        if (!Strings.isNullOrEmpty(portStr)) {
            configDao.update(ConfigType.INBOX_PORT, portStr);
        }
        configDao.update(ConfigType.INBOX_STARTTLS, starttls.toString());
        if (!Strings.isNullOrEmpty(username)) {
            configDao.update(ConfigType.INBOX_USERNAME, username);
        }
        if (!Strings.isNullOrEmpty(password)) {
            configDao.update(ConfigType.INBOX_PASSWORD, password);
        }
        if (!Strings.isNullOrEmpty(folder)) {
            configDao.update(ConfigType.INBOX_FOLDER, folder);
        }
        if (tag != null) {
            // Save an explicitly-provided tag as-is, INCLUDING an empty value, so the operator can CLEAR
            // the auto-import tag by submitting an empty one — previously an empty tag was skipped and the
            // tag could never be removed once set (#141). An empty stored value applies no tag (InboxService
            // resolves it via tagDao.getById, which returns null for an empty id). A truly absent field
            // (null) still leaves the existing tag untouched.
            configDao.update(ConfigType.INBOX_TAG, tag);
        }

        // Clear the persisted UIDVALIDITY baseline: saving the inbox configuration is the explicit
        // operator action that accepts the current mailbox and lifts a persistent UIDVALIDITY-reset
        // block. The next sync re-establishes the baseline from the folder's current UIDVALIDITY and
        // RESUMES import — which MAY re-import messages still present in the folder. Messages already
        // imported are not re-imported (they were expunged in delete-mode, or marked \Seen so the
        // UNSEEN search skips them); only a message that committed but was not yet acked before the
        // reset could produce a duplicate (never a loss). This is the operator explicitly accepting
        // re-import on resume — there is no automatic safe reconciliation across a UIDVALIDITY epoch.
        configDao.update(ConfigType.INBOX_UIDVALIDITY, "");

        return Response.ok().build();
    }

    /**
     * Test the inbox.
     *
     * @api {post} /app/test_inbox Test the inbox scanning
     * @apiName PostAppTestInbox
     * @apiGroup App
     * @apiSuccess {Number} Number of unread emails in the inbox
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @POST
    @Path("test_inbox")
    public Response testInbox() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        return Response.ok().entity(Json.createObjectBuilder()
                .add("count", AppContext.getInstance().getInboxService().testInbox())
                .build()).build();
    }

    /**
     * Retrieve the application logs.
     *
     * @api {get} /app/log Get application logs
     * @apiName GetAppLog
     * @apiGroup App
     * @apiParam {String="FATAL","ERROR","WARN","INFO","DEBUG"} level Minimum log level
     * @apiParam {String} tag Filter on this logger tag
     * @apiParam {String} message Filter on this message
     * @apiParam {Number} limit Total number of logs to return
     * @apiParam {Number} offset Start at this index
     * @apiSuccess {String} total Total number of logs
     * @apiSuccess {Object[]} logs List of logs
     * @apiSuccess {String} logs.date Date
     * @apiSuccess {String} logs.level Level
     * @apiSuccess {String} logs.tag Tag
     * @apiSuccess {String} logs.message Message
     * @apiError (client) ForbiddenError Access denied
     * @apiError (server) ServerError MEMORY appender not configured
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @param minLevel Filter on logging level
     * @param tag Filter on logger name / tag
     * @param message Filter on message
     * @param limit Page limit
     * @param offset Page offset
     * @return Response
     */
    @GET
    @Path("log")
    public Response log(
            @QueryParam("level") String minLevel,
            @QueryParam("tag") String tag,
            @QueryParam("message") String message,
            @QueryParam("limit") Integer limit,
            @QueryParam("offset") Integer offset) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        // Get the memory appender
        org.apache.log4j.Logger logger = org.apache.log4j.Logger.getRootLogger();
        Appender appender = logger.getAppender("MEMORY");
        if (!(appender instanceof MemoryAppender)) {
            throw new ServerException("ServerError", "MEMORY appender not configured");
        }
        MemoryAppender memoryAppender = (MemoryAppender) appender;

        // Find the logs
        LogCriteria logCriteria = new LogCriteria()
                .setMinLevel(Level.toLevel(StringUtils.stripToNull(minLevel)))
                .setTag(StringUtils.stripToNull(tag))
                .setMessage(StringUtils.stripToNull(message));

        PaginatedList<LogEntry> paginatedList = PaginatedLists.create(limit, offset);
        memoryAppender.find(logCriteria, paginatedList);
        JsonArrayBuilder logs = Json.createArrayBuilder();
        for (LogEntry logEntry : paginatedList.getResultList()) {
            logs.add(Json.createObjectBuilder()
                    .add("date", logEntry.getTimestamp())
                    .add("level", logEntry.getLevel().toString())
                    .add("tag", logEntry.getTag())
                    .add("message", logEntry.getMessage()));
        }

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("total", paginatedList.getResultCount())
                .add("logs", logs);

        return Response.ok().entity(response.build()).build();
    }

    /**
     * Returns global usage statistics for the admin dashboard.
     *
     * <p>Admin-only (the {@code /app/log} pattern: authenticate → 403 → checkBaseFunction(ADMIN)).
     * A single JSON payload, no pagination. {@code window} selects the length of the time series
     * (7, 30 or 90 UTC days; any other value is a 400).
     *
     * <p>Count semantics (pinned): {@code documents} = non-deleted; {@code files} = non-deleted
     * rows INCLUDING historical versions (a raw count); {@code users} = non-deleted incl. disabled;
     * {@code tags} = non-deleted; {@code favorites} = raw favorite row count (aggregate only — no
     * per-user favorite visibility, consistent with the #41 privacy statement).
     *
     * <p>{@code series.documents_created} buckets on {@code DOC_CREATEDATE_D} (the document's
     * recorded, client-suppliable create date — "documents by creation date", NOT audit CREATE
     * events). {@code series.activity} counts audit-log entries for the entity classes
     * {Document, File, Comment, Route, Tag} across every CRUD type. Both series are zero-filled
     * UTC {@code [start, end)} day buckets. The activity series reflects RETAINED audit rows only
     * (clean_storage purges orphan audit logs).
     *
     * @api {get} /app/stats Get application usage statistics
     * @apiName GetAppStats
     * @apiGroup App
     * @apiParam {Number=7,30,90} window Length of the time series in UTC days
     * @apiSuccess {Number} window Echoed window
     * @apiSuccess {Object} totals Corpus totals (documents, files, users, tags, favorites)
     * @apiSuccess {Object} storage Storage: global sum + per-user top-10
     * @apiSuccess {Object} series Time series: documents_created and activity
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError window is not one of 7, 30, 90
     * @apiPermission admin
     * @apiVersion 1.13.0
     *
     * @param window Length of the time series in UTC days
     * @return Response
     */
    @GET
    @Path("stats")
    public Response stats(@QueryParam("window") Integer window) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        // Fixed windows only — an unsupported value is a client error, not a silent default.
        if (window == null || (window != 7 && window != 30 && window != 90)) {
            throw new ClientException("ValidationError", "window must be one of 7, 30, 90");
        }

        DocumentDao documentDao = new DocumentDao();
        UserDao userDao = new UserDao();
        StatsDao statsDao = new StatsDao();

        // Totals row.
        JsonObjectBuilder totals = Json.createObjectBuilder()
                .add("documents", documentDao.getDocumentCount())
                .add("files", statsDao.getFileCount())
                .add("users", statsDao.getUserCount())
                .add("tags", statsDao.getTagCount())
                .add("favorites", statsDao.getFavoriteCount());

        // Storage: reuse the existing global sum and add the per-user top-10.
        JsonArrayBuilder perUser = Json.createArrayBuilder();
        for (UserStorageDto userStorage : statsDao.getTopUserStorage(10)) {
            perUser.add(Json.createObjectBuilder()
                    .add("username", userStorage.getUsername())
                    .add("storage_current", userStorage.getStorageCurrent())
                    .add("storage_quota", userStorage.getStorageQuota()));
        }
        JsonObjectBuilder storage = Json.createObjectBuilder()
                .add("global", userDao.getGlobalStorageCurrent())
                .add("per_user", perUser);

        // Time series: fetch raw timestamps in the UTC [start, end) window and bucket in Java.
        Date now = new Date();
        Date start = StatsBucketUtil.windowStart(window, now);
        Date end = StatsBucketUtil.windowEnd(now);

        JsonArrayBuilder documentsCreated = Json.createArrayBuilder();
        for (StatsBucketUtil.Bucket bucket : StatsBucketUtil.bucketByDay(
                statsDao.getDocumentCreatedDates(start, end), window, now)) {
            documentsCreated.add(Json.createObjectBuilder()
                    .add("date", bucket.getDate())
                    .add("count", bucket.getCount()));
        }
        JsonArrayBuilder activity = Json.createArrayBuilder();
        for (StatsBucketUtil.Bucket bucket : StatsBucketUtil.bucketByDay(
                statsDao.getActivityDates(start, end), window, now)) {
            activity.add(Json.createObjectBuilder()
                    .add("date", bucket.getDate())
                    .add("count", bucket.getCount()));
        }
        JsonObjectBuilder series = Json.createObjectBuilder()
                .add("documents_created", documentsCreated)
                .add("activity", activity);

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("window", window)
                .add("totals", totals)
                .add("storage", storage)
                .add("series", series);

        return Response.ok().entity(response.build()).build();
    }

    /**
     * Destroy and rebuild the search index.
     *
     * @api {post} /app/batch/reindex Rebuild the search index
     * @apiName PostAppBatchReindex
     * @apiGroup App
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (server) IndexingError Error rebuilding the index
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @POST
    @Path("batch/reindex")
    public Response batchReindex() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        RebuildIndexAsyncEvent rebuildIndexAsyncEvent = new RebuildIndexAsyncEvent();
        ThreadLocalContext.get().addAsyncEvent(rebuildIndexAsyncEvent);

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Preview a storage cleanup (dry run): what a real clean_storage run would reclaim, WITHOUT
     * mutating any database row or filesystem file.
     *
     * @api {get} /app/batch/clean_storage/dry_run Preview the file and DB storage cleanup
     * @apiName GetAppBatchCleanStorageDryRun
     * @apiGroup App
     * @apiParam {Number} [limit=100] Maximum number of affected files to return
     * @apiParam {Number} [offset=0] Pagination offset into the affected files
     * @apiSuccess {Number} total Total number of file resources the run would remove (complete closure)
     * @apiSuccess {Number} reclaimed_bytes Disk bytes the run would free (actual on-disk footprint of the removal closure + age-eligible orphans)
     * @apiSuccess {Number} primary_pointer_cleared_count Document main-file pointers that would be cleared
     * @apiSuccess {Number} limit Effective page size
     * @apiSuccess {Number} offset Effective page offset
     * @apiSuccess {Object[]} files Page of affected files
     * @apiSuccess {String} files.id File ID
     * @apiSuccess {String} files.document_id Owning document ID (may be null)
     * @apiSuccess {String} files.document_title Owning document title (may be null)
     * @apiSuccess {Number} files.size File size in bytes
     * @apiSuccess {String} files.reason Eligibility reason (soft_deleted, orphan_uploader, attached_to_deleted_document, filesystem_orphan)
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission admin
     * @apiVersion 1.13.0
     *
     * @param limit Page size
     * @param offset Page offset
     * @return Response
     */
    @GET
    @Path("batch/clean_storage/dry_run")
    public Response batchCleanStorageDryRun(
            @QueryParam("limit") Integer limit,
            @QueryParam("offset") Integer offset) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        // Clamp pagination to a sane range: never dump the full list unpaginated.
        int effectiveLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        int effectiveOffset = offset == null ? 0 : Math.max(0, offset);

        // Side-effect-free: SELECTs + a read-only directory listing. No DB or filesystem mutation.
        CleanupStorageUtil.Manifest manifest = CleanupStorageUtil.computeManifest();

        List<CleanupFileDto> allFiles = manifest.getFiles();
        JsonArrayBuilder filesJson = Json.createArrayBuilder();
        int from = Math.min(effectiveOffset, allFiles.size());
        int to = Math.min(from + effectiveLimit, allFiles.size());
        for (CleanupFileDto file : allFiles.subList(from, to)) {
            JsonObjectBuilder fileJson = Json.createObjectBuilder()
                    .add("id", file.getId())
                    .add("size", file.getSize())
                    .add("reason", file.getReason());
            if (file.getDocumentId() != null) {
                fileJson.add("document_id", file.getDocumentId());
            } else {
                fileJson.addNull("document_id");
            }
            if (file.getDocumentTitle() != null) {
                fileJson.add("document_title", file.getDocumentTitle());
            } else {
                fileJson.addNull("document_title");
            }
            filesJson.add(fileJson);
        }

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("total", manifest.getTotalCount())
                .add("reclaimed_bytes", manifest.getReclaimedBytes())
                .add("primary_pointer_cleared_count", manifest.getPrimaryPointerClearedCount())
                .add("limit", effectiveLimit)
                .add("offset", effectiveOffset)
                .add("files", filesJson);
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Clean storage.
     *
     * @api {post} /app/batch/clean_storage Clean the file and DB storage
     * @apiName PostAppBatchCleanStorage
     * @apiGroup App
     * @apiSuccess {String} status Status OK
     * @apiSuccess {Number} file_count Number of file resources removed by this run (complete closure)
     * @apiSuccess {Number} bytes Disk bytes freed by this run
     * @apiError (client) ForbiddenError Access denied
     * @apiError (server) FileError Error deleting orphan files
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @POST
    @Path("batch/clean_storage")
    public Response batchCleanStorage() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        // SINGLE-RUN GUARD (#74): acquire a PESSIMISTIC_WRITE row lock on the CLEAN_STORAGE_LOCK sentinel
        // FIRST, before reading the eligibility snapshot or mutating anything. The lock is held for the
        // whole DB-mutating transaction (until the TransactionUtil.commit checkpoint below), so a second
        // concurrent clean_storage BLOCKS here until the first run commits, then proceeds against the
        // already-cleaned state — the two runs are serialized, never parallel, so the same file can never
        // be counted or quota-credited twice. (The presence guards in the post-commit sweep remain as a
        // second line of defence for the filesystem side.)
        //
        // FAIL CLOSED: if the sentinel row is absent (a database that never ran migration 052), the
        // guard cannot be evaluated, so we refuse the destructive run rather than proceed unlocked —
        // an unguarded run could double-credit quota under concurrency. The row is always seeded by
        // migration 052, so this only fires on a genuinely un-migrated database.
        if (!new ConfigDao().lockForUpdate(ConfigType.CLEAN_STORAGE_LOCK)) {
            throw new ServerException("CleanStorageError",
                    "The clean_storage single-run lock is unavailable (missing CLEAN_STORAGE_LOCK row — is the database migrated?)");
        }

        // Acquire the GLOBAL storage-quota lock next — the canonical quota lock order is GLOBAL sentinel
        // first, then a user row. This run reclaims quota (per-owner, locking user rows) during its DB
        // mutation phase below; taking the global lock HERE, before any application-row mutation,
        // guarantees that order is respected against concurrent uploads/deletes and cannot deadlock.
        // FAIL CLOSED like the CLEAN lock: a missing sentinel means an un-migrated database, so refuse.
        if (!new ConfigDao().lockForUpdate(ConfigType.GLOBAL_QUOTA_LOCK)) {
            throw new ServerException("CleanStorageError",
                    "The global storage-quota lock is unavailable (missing GLOBAL_QUOTA_LOCK row — is the database migrated?)");
        }

        // Capture the eligibility SNAPSHOT before mutating anything — the same side-effect-free
        // computation the dry-run preview uses (so the dry-run estimate matches this state). The file
        // ids here are the CANDIDATES this run intends to hard-delete; the physical sweep below narrows
        // them to the set CONFIRMED gone from the DB after commit (a concurrent restore can revive a
        // candidate), and the recorded protocol reflects what was ACTUALLY unlinked, not this estimate.
        CleanupStorageUtil.Manifest manifest = CleanupStorageUtil.computeManifest();
        List<String> reclaimedFileIds = new ArrayList<>();
        for (CleanupFileDto f : manifest.getFiles()) {
            reclaimedFileIds.add(f.getId());
        }

        // #69: the physical filesystem deletion MUST happen only AFTER the DB deletions are durably
        // committed. Deleting bytes before commit means a rollback (e.g. a later FK abort or a commit
        // failure) would leave files gone while their DB rows survive — irrecoverable data loss. So we do
        // ALL the DB work first, commit, and only THEN sweep the filesystem. A leftover physical file
        // whose DB row still exists is never deleted (the sweep keys on committed-gone rows); a leftover
        // physical file with no DB row is reclaimed by the age-thresholded orphan sweep (#72). This
        // method runs inside the request transaction the RequestContextFilter commits at end-of-request;
        // we take an explicit commit checkpoint (TransactionUtil.commit) before the sweep. The durable
        // T_CLEANUP_RUN protocol row is written AFTER the sweep (with the ACTUAL reclaimed figures) and
        // commits with the request's end-of-request transaction.

        // Hard delete orphan audit logs
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        StringBuilder sb = new StringBuilder("delete from T_AUDIT_LOG al where al.LOG_ID_C in (select al.LOG_ID_C from T_AUDIT_LOG al ");
        sb.append(" left join T_DOCUMENT d on d.DOC_ID_C = al.LOG_IDENTITY_C and d.DOC_DELETEDATE_D is null ");
        sb.append(" left join T_ACL a on a.ACL_ID_C = al.LOG_IDENTITY_C and a.ACL_DELETEDATE_D is null ");
        sb.append(" left join T_COMMENT c on c.COM_ID_C = al.LOG_IDENTITY_C and c.COM_DELETEDATE_D is null ");
        sb.append(" left join T_FILE f on f.FIL_ID_C = al.LOG_IDENTITY_C and f.FIL_DELETEDATE_D is null ");
        sb.append(" left join T_TAG t on t.TAG_ID_C = al.LOG_IDENTITY_C and t.TAG_DELETEDATE_D is null ");
        sb.append(" left join T_USER u on u.USE_ID_C = al.LOG_IDENTITY_C and u.USE_DELETEDATE_D is null ");
        sb.append(" left join T_GROUP g on g.GRP_ID_C = al.LOG_IDENTITY_C and g.GRP_DELETEDATE_D is null ");
        sb.append(" where d.DOC_ID_C is null and a.ACL_ID_C is null and c.COM_ID_C is null and f.FIL_ID_C is null and t.TAG_ID_C is null and u.USE_ID_C is null and g.GRP_ID_C is null)");
        Query q = em.createNativeQuery(sb.toString());
        log.info("Deleting {} orphan audit logs", q.executeUpdate());

        // Soft delete orphan ACLs
        sb = new StringBuilder("update T_ACL a set ACL_DELETEDATE_D = :dateNow where a.ACL_ID_C in (select a.ACL_ID_C from T_ACL a ");
        sb.append(" left join T_SHARE s on s.SHA_ID_C = a.ACL_TARGETID_C ");
        sb.append(" left join T_USER u on u.USE_ID_C = a.ACL_TARGETID_C ");
        sb.append(" left join T_GROUP g on g.GRP_ID_C = a.ACL_TARGETID_C ");
        sb.append(" left join T_DOCUMENT d on d.DOC_ID_C = a.ACL_SOURCEID_C ");
        sb.append(" left join T_TAG t on t.TAG_ID_C = a.ACL_SOURCEID_C ");
        sb.append(" where s.SHA_ID_C is null and u.USE_ID_C is null and g.GRP_ID_C is null or d.DOC_ID_C is null and t.TAG_ID_C is null)");
        q = em.createNativeQuery(sb.toString());
        q.setParameter("dateNow", new Date());
        log.info("Deleting {} orphan ACLs", q.executeUpdate());

        // Soft delete orphan comments
        q = em.createNativeQuery("update T_COMMENT set COM_DELETEDATE_D = :dateNow where COM_ID_C in (select c.COM_ID_C from T_COMMENT c left join T_DOCUMENT d on d.DOC_ID_C = c.COM_IDDOC_C and d.DOC_DELETEDATE_D is null where d.DOC_ID_C is null)");
        q.setParameter("dateNow", new Date());
        log.info("Deleting {} orphan comments", q.executeUpdate());

        // Soft delete orphan document tag links
        q = em.createNativeQuery("update T_DOCUMENT_TAG set DOT_DELETEDATE_D = :dateNow where DOT_ID_C in (select dt.DOT_ID_C from T_DOCUMENT_TAG dt left join T_DOCUMENT d on dt.DOT_IDDOCUMENT_C = d.DOC_ID_C and d.DOC_DELETEDATE_D is null left join T_TAG t on t.TAG_ID_C = dt.DOT_IDTAG_C and t.TAG_DELETEDATE_D is null where d.DOC_ID_C is null or t.TAG_ID_C is null)");
        q.setParameter("dateNow", new Date());
        log.info("Deleting {} orphan document tag links", q.executeUpdate());

        // Soft delete orphan shares
        q = em.createNativeQuery("update T_SHARE set SHA_DELETEDATE_D = :dateNow where SHA_ID_C in (select s.SHA_ID_C from T_SHARE s left join T_ACL a on a.ACL_TARGETID_C = s.SHA_ID_C and a.ACL_DELETEDATE_D is null where a.ACL_ID_C is null)");
        q.setParameter("dateNow", new Date());
        log.info("Deleting {} orphan shares", q.executeUpdate());

        // #137: soft-delete orphan DOCUMENTS before orphan TAGS (the orphan-tag pass now runs after the #74
        // quota reclamation below). This makes the sweep acquire the DOCUMENT row before the TAG row — the
        // canonical USER -> DOCUMENT -> TAG order the FK-forced hot tag-link path imposes, since inserting a
        // T_DOCUMENT_TAG row checks (and locks) its parent document row before its parent tag row. A batch
        // path that stamped the tag first would invert that order and could deadlock against a concurrent
        // tag-link.
        // Soft delete orphan documents. Only stamp docs that are STILL LIVE (DOC_DELETEDATE_D is null):
        // an already-trashed document keeps its original trash timestamp, which its cascade-trashed files
        // share (file.deleteDate == doc.deleteDate) — re-stamping it would break that equality and defeat
        // the #74 quota reclaim's document-deleteDate matching for such docs.
        q = em.createNativeQuery("update T_DOCUMENT set DOC_DELETEDATE_D = :dateNow where DOC_DELETEDATE_D is null and DOC_ID_C in (select d.DOC_ID_C from T_DOCUMENT d left join T_USER u on u.USE_ID_C = d.DOC_IDUSER_C and u.USE_DELETEDATE_D is null where u.USE_ID_C is null)");
        q.setParameter("dateNow", new Date());
        log.info("Deleting {} orphan documents", q.executeUpdate());

        // #74 QUOTA RECLAMATION — for every soft-deleted document about to be hard-deleted, reclaim its
        // uploaders' storage quota EXACTLY as the retention purge does (TrashPurgeService →
        // FileUtil.reclaimQuotaForDeletedDocumentFiles(files, document.getDeleteDate())). This closes the
        // quota LEAK: trashing a document (DocumentDao.delete) does NOT reclaim quota — it only sets
        // deleteDate on the doc and its files — so a trashed file still HOLDS quota until the retention
        // purge; when clean_storage hard-deletes it FIRST (bypassing TrashPurgeService), it must reclaim
        // or the bytes leak forever. This MUST run HERE — right after the orphan-document soft-delete but
        // BEFORE the orphan-file / #54 file-attachment passes below — because those passes stamp a FRESH
        // deleteDate on a doomed doc's still-live files, which would then differ from the document's
        // deleteDate and be wrongly skipped. At this point a doomed doc's files still carry their
        // held-quota state: deleteDate == doc.deleteDate (trashed with the doc) or null (a stranded
        // collaborator upload) → reclaimed; a file individually deleted earlier (deleteDate != null &&
        // != doc.deleteDate — FileResource.delete already reclaimed it) → skipped, so no double-subtract.
        // Runs in the committed DB transaction (with the hard-delete), not the post-commit sweep. Ghost-
        // uploader-safe via FileUtil.reclaimUserQuota → UserDao.updateQuotaById (never throws for a
        // retained soft-deleted uploader).
        @SuppressWarnings("unchecked")
        List<Object[]> doomedDocs = em.createNativeQuery(
                "select d.DOC_ID_C, d.DOC_DELETEDATE_D from T_DOCUMENT d where d.DOC_DELETEDATE_D is not null")
                .getResultList();
        FileDao quotaFileDao = new FileDao();
        int quotaDocs = 0;
        for (Object[] doomed : doomedDocs) {
            String doomedDocId = (String) doomed[0];
            Date doomedDeleteDate = doomed[1] instanceof Date ? (Date) doomed[1]
                    : (doomed[1] == null ? null : new Date(((java.sql.Timestamp) doomed[1]).getTime()));
            FileUtil.reclaimQuotaForDeletedDocumentFiles(
                    quotaFileDao.getAllByDocumentId(doomedDocId), doomedDeleteDate);
            quotaDocs++;
        }
        log.info("Reclaimed storage quota for files of {} soft deleted documents about to be hard-deleted", quotaDocs);

        // Soft delete orphan tags. Deliberately runs AFTER the orphan-document soft-delete (moved here for
        // #137): both are plain row updates, and keeping DOCUMENT before TAG matches the FK-forced hot
        // tag-link path's parent-lock order (document row before tag row) so a concurrent tag-link cannot
        // deadlock this sweep. The #74 quota reclamation above keeps its required "immediately after the
        // orphan-document soft-delete" position; only the orphan-tag pass moved past it.
        q = em.createNativeQuery("update T_TAG set TAG_DELETEDATE_D = :dateNow where TAG_ID_C in (select t.TAG_ID_C from T_TAG t left join T_USER u on u.USE_ID_C = t.TAG_IDUSER_C and u.USE_DELETEDATE_D is null where u.USE_ID_C is null)");
        q.setParameter("dateNow", new Date());
        log.info("Deleting {} orphan tags", q.executeUpdate());

        // Soft delete orphan files. A file is orphaned when its uploader is gone (soft-deleted or
        // absent) — EXCEPT a file that still backs a LIVE (non-deleted) document is NEVER an orphan,
        // regardless of uploader state. This is the invariant that keeps a document reassigned away
        // from a departing user intact: its files keep FIL_IDUSER_C = the departing (now soft-deleted)
        // uploader so their bytes stay decryptable, and the live document they back holds them alive.
        q = em.createNativeQuery("update T_FILE set FIL_DELETEDATE_D = :dateNow where FIL_ID_C in ("
                + " select f.FIL_ID_C from T_FILE f"
                + " left join T_USER u on u.USE_ID_C = f.FIL_IDUSER_C and u.USE_DELETEDATE_D is null"
                + " left join T_DOCUMENT d on d.DOC_ID_C = f.FIL_IDDOC_C and d.DOC_DELETEDATE_D is null"
                + " where u.USE_ID_C is null and d.DOC_ID_C is null)");
        q.setParameter("dateNow", new Date());
        log.info("Deleting {} orphan files", q.executeUpdate());

        // ── Clear every ON DELETE RESTRICT edge into a soft-deleted document BEFORE the document
        // hard-delete below, so the parent delete can never abort mid-transaction. This is the
        // structural fix for the whole class of FK aborts (the historical DOC_IDFILE_C, saved-filter,
        // route-user, and now #54 FIL_IDDOC_C cases). A "document about to be hard-deleted" is any row
        // with DOC_DELETEDATE_D is not null AT THIS POINT — which includes both API-trashed documents
        // and the orphan documents this method just soft-deleted above.
        //
        // Inbound RESTRICT FKs into T_DOCUMENT (grepped from db/update/*.sql `on delete restrict`):
        //   FK_FIL_IDDOC_C  (T_FILE.FIL_IDDOC_C)            → soft-deleted here, then hard-deleted + swept
        //   FK_DME_IDDOCUMENT_C (T_DOCUMENT_METADATA)       → hard-deleted here (no soft-delete column)
        //   FK_FAV_IDDOCUMENT_C (T_FAVORITE)                → hard-deleted here (no soft-delete column)
        //   FK_RTE_IDDOCUMENT_C (T_ROUTE) + FK_RTP_IDROUTE_C(T_ROUTE_STEP) → hard-deleted here (child first)
        //   FK_COM_IDDOC_C  (T_COMMENT)                     → already soft-deleted by the orphan-comment pass
        //   FK_DOT_IDDOCUMENT_C (T_DOCUMENT_TAG)            → already soft-deleted by the orphan-tag-link pass
        // (T_RELATION references documents but carries NO FK constraint, so it cannot abort the delete.)

        // #54 fix: a still-LIVE file attached to a soon-hard-deleted document (e.g. a collaborator's
        // file whose uploader is live, or any file on an orphan document this run just soft-deleted)
        // would keep FIL_IDDOC_C pointing at that document and abort the doc hard-delete (FK_FIL_IDDOC_C
        // is ON DELETE RESTRICT). Soft-delete those files HERE — before the file hard-delete + the
        // filesystem sweep below reclaim them — so the document becomes deletable and no bytes leak.
        q = em.createNativeQuery("update T_FILE set FIL_DELETEDATE_D = :dateNow"
                + " where FIL_DELETEDATE_D is null"
                + " and FIL_IDDOC_C in (select d.DOC_ID_C from T_DOCUMENT d where d.DOC_DELETEDATE_D is not null)");
        q.setParameter("dateNow", new Date());
        log.info("Soft deleting {} live files attached to soft deleted documents (FIL_IDDOC_C RESTRICT)", q.executeUpdate());

        // Hard delete document metadata of soft-deleted documents (T_DOCUMENT_METADATA has no
        // soft-delete column; a lingering row would abort the doc delete via FK_DME_IDDOCUMENT_C).
        q = em.createNativeQuery("delete from T_DOCUMENT_METADATA"
                + " where DME_IDDOCUMENT_C in (select d.DOC_ID_C from T_DOCUMENT d where d.DOC_DELETEDATE_D is not null)");
        log.info("Deleting {} metadata rows of soft deleted documents (DME_IDDOCUMENT_C RESTRICT)", q.executeUpdate());

        // Hard delete favorites pointing at soft-deleted documents (no soft-delete column;
        // FK_FAV_IDDOCUMENT_C would otherwise abort the doc delete).
        q = em.createNativeQuery("delete from T_FAVORITE"
                + " where FAV_IDDOCUMENT_C in (select d.DOC_ID_C from T_DOCUMENT d where d.DOC_DELETEDATE_D is not null)");
        log.info("Deleting {} favorites of soft deleted documents (FAV_IDDOCUMENT_C RESTRICT)", q.executeUpdate());

        // Hard delete routes (and their steps first) attached to soft-deleted documents. A route is
        // only ENDED (status CANCELLED) when its document is trashed — its RTE_DELETEDATE_D stays null
        // and RTE_IDDOCUMENT_C keeps pointing at the doc, so FK_RTE_IDDOCUMENT_C would abort the doc
        // delete. Steps go first (FK_RTP_IDROUTE_C is RESTRICT into T_ROUTE).
        q = em.createNativeQuery("delete from T_ROUTE_STEP where RTP_IDROUTE_C in ("
                + " select r.RTE_ID_C from T_ROUTE r"
                + " where r.RTE_IDDOCUMENT_C in (select d.DOC_ID_C from T_DOCUMENT d where d.DOC_DELETEDATE_D is not null))");
        log.info("Deleting {} route steps of routes on soft deleted documents (RTP_IDROUTE_C RESTRICT)", q.executeUpdate());
        q = em.createNativeQuery("delete from T_ROUTE"
                + " where RTE_IDDOCUMENT_C in (select d.DOC_ID_C from T_DOCUMENT d where d.DOC_DELETEDATE_D is not null)");
        log.info("Deleting {} routes of soft deleted documents (RTE_IDDOCUMENT_C RESTRICT)", q.executeUpdate());

        // ── Clear the remaining ON DELETE RESTRICT edges into T_USER for every soft-deleted user, so
        // the user hard-delete below cannot abort. UserDao.delete clears these on the normal delete
        // path, but clean_storage also hard-deletes users its OWN orphan passes soft-delete (owner
        // gone) and must not rely on any particular deletion path having run. Inbound user RESTRICT FKs:
        //   FK_SFL_IDUSER_C  (T_SAVED_FILTER)         → deleted here
        //   FK_AUT_IDUSER_C  (T_AUTHENTICATION_TOKEN) → deleted here
        //   FK_FAV_IDUSER_C  (T_FAVORITE)             → deleted here
        //   FK_TAG_IDUSER_C  (T_TAG)                  → soft-deleted+hard-deleted by the tag passes
        //   FK_DOC_IDUSER_C  (T_DOCUMENT)             → soft-deleted+hard-deleted by the document passes
        //   FK_FIL_IDUSER_C / FK_RTE_IDUSER_C / FK_RTP_IDVALIDATORUSER_C / FK_COM_IDUSER_C → the
        //       userPurge below RETAINS any user still referenced by a LIVE file/route/route-step/
        //       comment (ghost key-holder); soft-deleted routes/steps referencing the user are hard-
        //       deleted just before the purge, and soft-deleted comments by the comment hard-delete
        //       pass — so those never abort.
        q = em.createNativeQuery("delete from T_SAVED_FILTER where SFL_IDUSER_C in (select u.USE_ID_C from T_USER u where u.USE_DELETEDATE_D is not null)");
        log.info("Deleting {} saved filters of soft deleted users", q.executeUpdate());
        q = em.createNativeQuery("delete from T_AUTHENTICATION_TOKEN where AUT_IDUSER_C in (select u.USE_ID_C from T_USER u where u.USE_DELETEDATE_D is not null)");
        log.info("Deleting {} authentication tokens of soft deleted users (AUT_IDUSER_C RESTRICT)", q.executeUpdate());
        q = em.createNativeQuery("delete from T_FAVORITE where FAV_IDUSER_C in (select u.USE_ID_C from T_USER u where u.USE_DELETEDATE_D is not null)");
        log.info("Deleting {} favorites of soft deleted users (FAV_IDUSER_C RESTRICT)", q.executeUpdate());

        // Clear main-file pointers to soft-deleted files before the file hard-delete
        // (T_DOCUMENT.FK_DOC_IDFILE_C is ON DELETE RESTRICT, so a document still pointing at a
        // soft-deleted file would abort the whole purge)
        q = em.createNativeQuery("update T_DOCUMENT set DOC_IDFILE_C = null where DOC_IDFILE_C in (select FIL_ID_C from T_FILE where FIL_DELETEDATE_D is not null)");
        log.info("Clearing {} main-file pointers to soft deleted files", q.executeUpdate());

        // Soft-delete any STILL-LIVE document-tag link whose TAG is soft-deleted, before the tag
        // hard-delete (FK_DOT_IDTAG_C RESTRICT). The orphan-tag-link pass above runs BEFORE the
        // orphan-tag pass, so a departing user's tag that is linked to a SURVIVING (live) document
        // leaves a live link pointing at a now-soft-deleted tag; without this the tag hard-delete
        // aborts. Marking the link deleted here folds it into the DocumentTag hard-delete below.
        q = em.createNativeQuery("update T_DOCUMENT_TAG set DOT_DELETEDATE_D = :dateNow"
                + " where DOT_DELETEDATE_D is null"
                + " and DOT_IDTAG_C in (select t.TAG_ID_C from T_TAG t where t.TAG_DELETEDATE_D is not null)");
        q.setParameter("dateNow", new Date());
        log.info("Soft deleting {} live tag links pointing at soft deleted tags (DOT_IDTAG_C RESTRICT)", q.executeUpdate());

        // Hard delete softly deleted data
        log.info("Deleting {} soft deleted document tag links", em.createQuery("delete DocumentTag where deleteDate is not null").executeUpdate());
        log.info("Deleting {} soft deleted ACLs", em.createQuery("delete Acl where deleteDate is not null").executeUpdate());
        log.info("Deleting {} soft deleted shares", em.createQuery("delete Share where deleteDate is not null").executeUpdate());
        log.info("Deleting {} soft deleted tags", em.createQuery("delete Tag where deleteDate is not null").executeUpdate());
        log.info("Deleting {} soft deleted comments", em.createQuery("delete Comment where deleteDate is not null").executeUpdate());
        log.info("Deleting {} soft deleted files", em.createQuery("delete File where deleteDate is not null").executeUpdate());
        log.info("Deleting {} soft deleted documents", em.createQuery("delete Document where deleteDate is not null").executeUpdate());

        // Hard-delete soft-deleted routes (of soft-deleted users) and all their steps, before the user
        // purge. A route/step keeps its RTE_IDUSER_C / RTP_IDVALIDATORUSER_C FK row regardless of its own
        // deleteDate, and routes are otherwise never hard-deleted — so a soft-deleted route (or step)
        // initiated/validated by the departing user would still block the T_USER delete (the userPurge
        // below only accounts for LIVE routes/steps).
        //
        // The set of "doomed routes" is the soft-deleted routes owned by a soft-deleted user. We delete
        // ALL of their steps FIRST — REGARDLESS of each step's own RTP_DELETEDATE_D — so a LIVE step on a
        // doomed route cannot abort the route delete on FK_RTP_IDROUTE_C (same class as the #54
        // FIL_IDDOC_C bug). Today the only route-soft-delete path (RouteDao.deleteRoute) atomically
        // soft-deletes a route's steps before the route, so (soft-deleted route + live step) is not
        // reachable via the app; this is a defensive close of the RESTRICT edge that does not rely on
        // that atomicity. We ALSO clear the separate validator-user edge: any soft-deleted step whose
        // validator is a departing user (independent of its route's state).
        q = em.createNativeQuery("delete from T_ROUTE_STEP where"
                // every step of a doomed route (regardless of the step's own deleteDate)
                + " RTP_IDROUTE_C in (select r.RTE_ID_C from T_ROUTE r where r.RTE_DELETEDATE_D is not null"
                + "   and r.RTE_IDUSER_C in (select u.USE_ID_C from T_USER u where u.USE_DELETEDATE_D is not null))"
                // plus any soft-deleted step validated by a departing user (its route may not be doomed)
                + " or (RTP_DELETEDATE_D is not null and RTP_IDVALIDATORUSER_C in"
                + "   (select u.USE_ID_C from T_USER u where u.USE_DELETEDATE_D is not null))");
        log.info("Deleting {} route steps of doomed routes / validated by soft deleted users (RTP_IDROUTE_C + RTP_IDVALIDATORUSER_C RESTRICT)", q.executeUpdate());
        q = em.createNativeQuery("delete from T_ROUTE where RTE_DELETEDATE_D is not null"
                + " and RTE_IDUSER_C in (select u.USE_ID_C from T_USER u where u.USE_DELETEDATE_D is not null)");
        log.info("Deleting {} soft deleted routes referencing soft deleted users (RTE_IDUSER_C RESTRICT)", q.executeUpdate());

        // Hard delete soft-deleted users, EXCEPT any that live data still references under an ON DELETE
        // RESTRICT foreign key — hard-deleting such a user would abort the whole purge. A user whose
        // documents were reassigned away is retained here as a hidden ghost key-holder: soft-deleted and
        // invisible, kept solely so its privateKey still decrypts the reassigned files it originally
        // uploaded and so it does not orphan the (surviving) routes it initiated / comments it authored.
        // It becomes purgeable once no live data references it. The referencing live FKs are:
        //   - T_FILE.FK_FIL_IDUSER_C           — a live file's uploader (the retained decryption key)
        //   - T_ROUTE.FK_RTE_IDUSER_C          — a live route's initiator (NOT NULL)
        //   - T_ROUTE_STEP.FK_RTP_IDVALIDATORUSER_C — a live route step's validator (nullable)
        //   - T_COMMENT.FK_COM_IDUSER_C        — a live comment's author (on a surviving document)
        // (RTP_IDTARGET_C is a name-resolved principal, not a T_USER FK, so it does not restrict.)
        // Soft-deleted routes/steps/comments referencing the user were removed above / by the soft-delete
        // hard-delete passes, so only LIVE references retain the user here.
        Query userPurge = em.createNativeQuery("delete from T_USER where USE_DELETEDATE_D is not null"
                + " and USE_ID_C not in (select f.FIL_IDUSER_C from T_FILE f where f.FIL_DELETEDATE_D is null)"
                + " and USE_ID_C not in (select r.RTE_IDUSER_C from T_ROUTE r where r.RTE_DELETEDATE_D is null)"
                + " and USE_ID_C not in (select rs.RTP_IDVALIDATORUSER_C from T_ROUTE_STEP rs where rs.RTP_IDVALIDATORUSER_C is not null and rs.RTP_DELETEDATE_D is null)"
                + " and USE_ID_C not in (select c.COM_IDUSER_C from T_COMMENT c where c.COM_DELETEDATE_D is null)");
        log.info("Deleting {} soft deleted users", userPurge.executeUpdate());
        log.info("Deleting {} soft deleted groups", em.createQuery("delete Group where deleteDate is not null").executeUpdate());

        // (The durable T_CLEANUP_RUN protocol row is written AFTER the sweep below, with the ACTUAL
        // count/bytes unlinked — not the pre-sweep estimate — so the record matches what happened.)

        // Test seam (#69): simulate a DB/commit-phase failure right before the checkpoint. GUARDED to
        // unit-test mode only, so it is inert (a genuine no-op) in the running webapp even if the field
        // were ever set. When it throws, the method aborts BEFORE the commit + sweep, so the request
        // transaction rolls back and the filesystem is never touched.
        if (EnvironmentUtil.isUnitTest()) {
            Runnable beforeCommit = cleanStorageBeforeCommitHook;
            if (beforeCommit != null) {
                beforeCommit.run();
            }
        }

        // #69: COMMIT CHECKPOINT. Durably commit every DB deletion above BEFORE touching the filesystem.
        // TransactionUtil.commit() commits and begins a fresh (empty) transaction so the
        // RequestContextFilter's end-of-request commit still finds an active tx. If this commit throws
        // (e.g. a deferred FK check fails), it propagates out and NO physical file has been deleted — the
        // DB simply rolls back and no bytes are lost. Only after the commit succeeds do we sweep the
        // filesystem; the T_CLEANUP_RUN protocol row is written AFTER the sweep, in the fresh
        // transaction, with the ACTUAL reclaimed figures.
        TransactionUtil.commit();

        // Test seam: simulate a CONCURRENT RESTORE (a doc un-soft-deleted between the manifest snapshot
        // and the sweep) right after the commit. Unit-test mode only; a genuine no-op in the webapp.
        if (EnvironmentUtil.isUnitTest()) {
            Runnable beforeSweep = cleanStorageBeforeSweepHook;
            if (beforeSweep != null) {
                beforeSweep.run();
            }
        }

        // POST-COMMIT precise physical delete over the CONFIRMED-DELETED set. The reclaim snapshot
        // (reclaimedFileIds) was computed BEFORE the DB deletes; between then and now a document could
        // have been RESTORED (un-soft-deleted) concurrently, in which case its file rows survived the DB
        // delete and are still LIVE — deleting their bytes would leave a live T_FILE row with no content.
        // So we re-query, in the committed post-transaction state, which of the snapshot ids no longer
        // have a T_FILE row, and unlink ONLY those. A concurrently-restored file still has its row →
        // excluded → its bytes are never touched. (This also intrinsically excludes any concurrent
        // upload, whose id was never in the snapshot.)
        //
        // Accounting: the recorded/reported count and bytes reflect ONLY files whose physical unlink
        // SUCCEEDED — we measure each confirmed-deleted file's on-disk footprint, unlink it, and count it
        // only on success. A committed-deleted row whose unlink fails/crashes leaves a no-DB-row residue;
        // that residue is reclaimed by the age-thresholded filesystem-orphan sweep below (#72) on a
        // later run, not retried here — we simply do not over-report bytes we did not actually free.
        Set<String> confirmedDeleted = new HashSet<>();
        if (!reclaimedFileIds.isEmpty()) {
            EntityManager postEm = ThreadLocalContext.get().getEntityManager();
            @SuppressWarnings("unchecked")
            List<String> stillPresent = postEm.createNativeQuery(
                    "select f.FIL_ID_C from T_FILE f where f.FIL_ID_C in (:ids)")
                    .setParameter("ids", reclaimedFileIds)
                    .getResultList();
            Set<String> stillPresentSet = new HashSet<>(stillPresent);
            for (String id : reclaimedFileIds) {
                if (!stillPresentSet.contains(id)) {
                    confirmedDeleted.add(id);
                }
            }
        }

        // #103: END the read-only continuation transaction BEFORE the (potentially long) filesystem
        // sweep. The confirmation read above was the LAST database access of the base request frame; the
        // sweep below runs entirely off-database. Leaving this transaction open across the sweep is the
        // bug: under a PostgreSQL idle_in_transaction_session_timeout the idle session is aborted mid-
        // sweep, and the T_CLEANUP_RUN insert then 500s AFTER the bytes are already gone (a long sweep
        // makes an internal failure out of a completed reclaim). The continuation only ever RAN reads
        // (the checkpoint commit fired and reset the frame's events/callbacks), so there is nothing to
        // commit — roll it back. We deliberately do NOT complete/rotate/close the base frame: it stays
        // installed with an inactive transaction, which RequestContextFilter.commitAndFinalize tolerates
        // (it initializes ROLLED_BACK, skips the active-transaction block, and completes the frame at
        // end-of-request). If the rollback ITSELF is IN_DOUBT, mark the frame terminal and propagate.
        EntityManager requestEm = ThreadLocalContext.get().peekEntityManager();
        TransactionBoundary.ClassifiedOutcome ended = TransactionBoundary.tryRollback(requestEm);
        if (ended.getState() == TransactionBoundary.DurableState.IN_DOUBT) {
            ThreadLocalContext.get().markCurrentFrameInDoubt();
            TransactionBoundary.propagate(ended.getFailure());
        }

        java.nio.file.Path storageDir = DirectoryUtil.getStorageDirectory();
        java.util.function.Consumer<String> duringReclaimHook =
                EnvironmentUtil.isUnitTest() ? cleanStorageDuringReclaimHook : null;

        // #79: SERIALIZE the entire post-commit filesystem sweep with an IN-PROCESS lock. Concurrent
        // clean_storage runs are REST requests handled by threads in the SAME JVM — Teedy runs as a single
        // application instance that OWNS the storage directory, so an in-process lock is the correct, race-
        // free serialization (no filesystem markers, no TOCTOU). The checkpoint commit above already
        // released CLEAN_STORAGE_LOCK + GLOBAL_QUOTA_LOCK, so this JVM lock holds NO database transaction
        // during the (potentially long) filesystem I/O — a second run's DB phase is unaffected; only the
        // physical sweeps serialize. The second run, once it acquires the lock, recomputes its orphan set
        // against the CURRENT filesystem and deletes/counts only what still remains, so no file is ever
        // double-counted. NOTE: this assumes ONE application instance owns the storage directory (the
        // supported single-container deployment); a future multi-instance deployment sharing storage would
        // need a DISTRIBUTED sweep lock — do not rely on this in-process lock across instances.
        long actualCount = 0L;
        long actualBytes = 0L;
        STORAGE_SWEEP_LOCK.lock();
        try {
            for (String confirmedId : confirmedDeleted) {
                // Accounting is keyed on THIS run's own deleteIfExists results (count only when a variant
                // was actually removed by this run; sum only bytes this run freed) — belt-and-suspenders
                // under the sweep lock, and correct against the async FileDeletedAsyncListener that deletes
                // storage bytes outside the sweep. deleteAndMeasure never discards bytes already freed even
                // if a later variant's delete throws — it returns the partial accounting plus the failure.
                // (Quota was already reclaimed in the committed DB phase above, keyed on the document's
                // deleteDate — not here.)
                CleanupStorageUtil.ReclaimResult reclaim =
                        CleanupStorageUtil.deleteAndMeasure(storageDir, confirmedId, duringReclaimHook);
                if (reclaim.isReclaimed()) {
                    actualCount++;
                    actualBytes += reclaim.getBytesFreed();
                }
                if (reclaim.getFailure() != null) {
                    // Do NOT abort the run: this row is already committed-gone, so a failed unlink is
                    // harmless residue reclaimed by a later run's age-thresholded orphan sweep (#72).
                    log.error("Failed to unlink physical file {} during clean_storage; left for a later orphan sweep", confirmedId, reclaim.getFailure());
                }
            }

            // #72 AGE-THRESHOLDED FILESYSTEM-ORPHAN RECLAIM: also unlink genuine orphans — on-disk base ids
            // with NO T_FILE row (any state) whose variants are all OLDER than the age threshold. Enumerated
            // HERE, under the sweep lock, against the CURRENT filesystem + committed post-transaction DB
            // state with a fresh clock — so a serialized second run sees the orphans a first run already
            // reclaimed as gone, and a base id that gained a row concurrently, or a file still fresh (a
            // possible in-flight upload whose row has not committed), is skipped.
            //
            // #103: the DB half (the all-file-ids snapshot) runs in its OWN short owned transaction —
            // fully committed and closed here — while the actual filesystem enumeration + unlink runs
            // off-database. The base request transaction was already ended above, so nothing holds an open
            // request transaction across the sweep. The snapshot's brief owned frame reads the committed
            // post-transaction DB state, exactly as the old inline read did.
            Set<String> knownFileIds = TransactionBoundary.finalizeOwnedInNewFrame(
                    EMF.get().createEntityManager(), requestEm, CleanupStorageUtil::snapshotAllFileIds);
            long nowMs = System.currentTimeMillis();
            for (CleanupStorageUtil.OrphanCandidate orphan : CleanupStorageUtil.enumerateFilesystemOrphans(storageDir, knownFileIds, nowMs)) {
                CleanupStorageUtil.ReclaimResult reclaim =
                        CleanupStorageUtil.deleteAndMeasure(storageDir, orphan.baseId, duringReclaimHook);
                if (reclaim.isReclaimed()) {
                    actualCount++;
                    actualBytes += reclaim.getBytesFreed();
                }
                if (reclaim.getFailure() != null) {
                    log.error("Failed to unlink orphan file {} during clean_storage; left for a later orphan sweep", orphan.baseId, reclaim.getFailure());
                }
            }
        } finally {
            STORAGE_SWEEP_LOCK.unlock();
        }
        log.info("clean_storage unlinked {} files total ({} bytes freed)", actualCount, actualBytes);

        // (Quota reclamation was performed in the committed DB phase above — keyed on each soon-hard-
        // deleted document's deleteDate, mirroring TrashPurgeService — NOT here in the filesystem sweep.)

        // Durable protocol: record this run's ACTUAL outcome (files unlinked + bytes freed) in
        // T_CLEANUP_RUN — written here, after the sweep, so the record matches reality rather than the
        // pre-sweep estimate. This table is NEVER swept by clean_storage (unlike an audit-log entry,
        // whose orphan-audit-log delete would purge it), so the next cleanup preserves it. The acting
        // admin is stored as a plain value snapshot, not an FK, so the record survives that admin's
        // later deletion.
        //
        // #103: the base request transaction was ended before the sweep, so this row is written in its
        // OWN short owned transaction (a fresh entity manager, committed and closed) rather than riding
        // the end-of-request commit — which no longer exists on a durable transaction here.
        final long recordedCount = actualCount;
        final long recordedBytes = actualBytes;
        TransactionBoundary.finalizeOwnedInNewFrame(EMF.get().createEntityManager(), requestEm, frameEm -> {
            new CleanupRunDao().create(new CleanupRun()
                    .setFileCount(recordedCount)
                    .setBytes(recordedBytes)
                    .setUserId(principal.getId())
                    .setUsername(principal.getName())
                    .setCreateDate(new Date()));
            return null;
        });
        log.info("Recorded clean_storage protocol: {} files, {} bytes reclaimed", actualCount, actualBytes);

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok")
                .add("file_count", actualCount)
                .add("bytes", actualBytes);
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Get the LDAP authentication configuration.
     *
     * @api {get} /app/config_ldap Get the LDAP authentication configuration
     * @apiName GetAppConfigLdap
     * @apiGroup App
     * @apiSuccess {Boolean} enabled LDAP authentication enabled
     * @apiSuccess {String} host LDAP server host
     * @apiSuccess {Integer} port LDAP server port
     * @apiSuccess {String} admin_dn Admin DN
     * @apiSuccess {Boolean} admin_password_set True if an admin bind password is stored (the password itself is write-only and never returned)
     * @apiSuccess {String} base_dn Base DN
     * @apiSuccess {String} filter LDAP filter
     * @apiSuccess {String} default_email LDAP default email
     * @apiSuccess {Integer} default_storage LDAP default storage
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission admin
     * @apiVersion 1.9.0
     *
     * @return Response
     */
    @GET
    @Path("config_ldap")
    public Response getConfigLdap() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        ConfigDao configDao = new ConfigDao();
        Config enabled = configDao.getById(ConfigType.LDAP_ENABLED);

        JsonObjectBuilder response = Json.createObjectBuilder();
        response.add("enabled", enabled != null && Boolean.parseBoolean(enabled.getValue()));

        // #83: every NON-secret field is returned whenever its config row EXISTS, regardless of
        // whether LDAP is currently enabled. Disabling LDAP only flips LDAP_ENABLED to false —
        // the connection settings persist in T_CONFIG — so the admin UI must repopulate them on a
        // disable/re-enable cycle. Each field is read directly from its row (not via ConfigUtil,
        // which throws on a missing row) and emitted only when present, so the never-configured
        // state (no rows) simply omits them instead of failing.
        Config host = configDao.getById(ConfigType.LDAP_HOST);
        if (host != null) {
            response.add("host", host.getValue());
        }
        Config port = configDao.getById(ConfigType.LDAP_PORT);
        if (port != null) {
            response.add("port", Integer.parseInt(port.getValue()));
        }
        Config usessl = configDao.getById(ConfigType.LDAP_USESSL);
        if (usessl != null) {
            response.add("usessl", Boolean.parseBoolean(usessl.getValue()));
        }
        Config adminDn = configDao.getById(ConfigType.LDAP_ADMIN_DN);
        if (adminDn != null) {
            response.add("admin_dn", adminDn.getValue());
        }
        // The admin bind password is write-only: it is NEVER echoed back (BL-028). Only a boolean
        // "is a password stored?" flag is exposed, so the UI shows a "leave blank to keep"
        // affordance instead of the secret. The POST keeps the stored value when admin_password is
        // absent/empty. This flag is always present (false when unset).
        Config adminPassword = configDao.getById(ConfigType.LDAP_ADMIN_PASSWORD);
        response.add("admin_password_set", adminPassword != null && !Strings.isNullOrEmpty(adminPassword.getValue()));
        Config baseDn = configDao.getById(ConfigType.LDAP_BASE_DN);
        if (baseDn != null) {
            response.add("base_dn", baseDn.getValue());
        }
        Config filter = configDao.getById(ConfigType.LDAP_FILTER);
        if (filter != null) {
            response.add("filter", filter.getValue());
        }
        Config defaultEmail = configDao.getById(ConfigType.LDAP_DEFAULT_EMAIL);
        if (defaultEmail != null) {
            response.add("default_email", defaultEmail.getValue());
        }
        Config defaultStorage = configDao.getById(ConfigType.LDAP_DEFAULT_STORAGE);
        if (defaultStorage != null) {
            response.add("default_storage", Long.parseLong(defaultStorage.getValue()));
        }

        return Response.ok().entity(response.build()).build();
    }

    /**
     * Configure the LDAP authentication.
     *
     * @api {post} /app/config_ldap Configure the LDAP authentication
     * @apiName PostAppConfigLdap
     * @apiGroup App
     * @apiParam {Boolean} enabled LDAP authentication enabled
     * @apiParam {String} host LDAP server host
     * @apiParam {Integer} port LDAP server port
     * @apiParam {Boolean} usessl Use SSL (ldaps)
     * @apiParam {String} admin_dn Admin DN
     * @apiParam {String} admin_password Admin password
     * @apiParam {String} base_dn Base DN
     * @apiParam {String} filter LDAP filter
     * @apiParam {String} default_email LDAP default email
     * @apiParam {Integer} default_storage LDAP default storage
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiPermission admin
     * @apiVersion 1.9.0
     *
     * @param enabled LDAP authentication enabled
     * @param host LDAP server host
     * @param portStr LDAP server port
     * @param usessl LDAP use SSL (ldaps)
     * @param adminDn Admin DN
     * @param adminPassword Admin password
     * @param baseDn Base DN
     * @param filter LDAP filter
     * @param defaultEmail LDAP default email
     * @param defaultStorageStr LDAP default storage
     * @return Response
     */
    @POST
    @Path("config_ldap")
    public Response configLdap(@FormParam("enabled") Boolean enabled,
                               @FormParam("host") String host,
                               @FormParam("port") String portStr,
                               @FormParam("usessl") Boolean usessl,
                               @FormParam("admin_dn") String adminDn,
                               @FormParam("admin_password") String adminPassword,
                               @FormParam("base_dn") String baseDn,
                               @FormParam("filter") String filter,
                               @FormParam("default_email") String defaultEmail,
                               @FormParam("default_storage") String defaultStorageStr) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        ConfigDao configDao = new ConfigDao();

        if (enabled != null && enabled) {
            // LDAP enabled, validate everything
            ValidationUtil.validateLength(host, "host", 1, 250);
            ValidationUtil.validateInteger(portStr, "port");
            ValidationUtil.validateLength(adminDn, "admin_dn", 1, 250);
            ValidationUtil.validateLength(baseDn, "base_dn", 1, 250);
            ValidationUtil.validateLength(filter, "filter", 1, 250);
            if (!filter.contains("USERNAME")) {
                throw new ClientException("ValidationError", "'filter' must contains 'USERNAME'");
            }
            ValidationUtil.validateLength(defaultEmail, "default_email", 1, 250);
            ValidationUtil.validateLong(defaultStorageStr, "default_storage");

            // The admin bind password is write-only (BL-028): the GET never echoes it, so the
            // client sends it blank to KEEP the stored value. An empty submission therefore
            // preserves the existing password rather than wiping it. On first-time setup (no
            // stored password) a non-empty password is required.
            Config storedPassword = configDao.getById(ConfigType.LDAP_ADMIN_PASSWORD);
            boolean hasStoredPassword = storedPassword != null && !Strings.isNullOrEmpty(storedPassword.getValue());
            if (Strings.isNullOrEmpty(adminPassword)) {
                if (!hasStoredPassword) {
                    throw new ClientException("ValidationError", "admin_password must be set");
                }
            } else {
                ValidationUtil.validateLength(adminPassword, "admin_password", 1, 250);
            }

            configDao.update(ConfigType.LDAP_ENABLED, Boolean.TRUE.toString());
            configDao.update(ConfigType.LDAP_HOST, host);
            configDao.update(ConfigType.LDAP_PORT, portStr);
            configDao.update(ConfigType.LDAP_USESSL, usessl.toString());
            configDao.update(ConfigType.LDAP_ADMIN_DN, adminDn);
            if (!Strings.isNullOrEmpty(adminPassword)) {
                configDao.update(ConfigType.LDAP_ADMIN_PASSWORD, adminPassword);
            }
            configDao.update(ConfigType.LDAP_BASE_DN, baseDn);
            configDao.update(ConfigType.LDAP_FILTER, filter);
            configDao.update(ConfigType.LDAP_DEFAULT_EMAIL, defaultEmail);
            configDao.update(ConfigType.LDAP_DEFAULT_STORAGE, defaultStorageStr);
        } else {
            // LDAP disabled
            configDao.update(ConfigType.LDAP_ENABLED, Boolean.FALSE.toString());
        }

        return Response.ok().build();
    }

    /**
     * Returns the OIDC (OpenID Connect) authentication configuration for the admin UI.
     *
     * <p>ADR-0015 fence: this endpoint exposes ONLY the provider/claim settings (issuer,
     * client id, endpoints, scope, username/email claim names). It never reads or writes any
     * identity-binding, username-derivation, disabled-account-eligibility, or fail-closed
     * behavior — those are non-editable invariants of {@code OidcResource}.
     *
     * <p>The client secret is write-only (mirrors the LDAP admin password, BL-028): it is NEVER
     * echoed back — only a boolean {@code client_secret_set} flag is exposed. Each field also
     * carries its effective SOURCE ({@code db} | {@code property} | {@code default}) so the UI can
     * hint "currently from a JVM property — saving overrides it".
     *
     * @api {get} /app/config_oidc Get the OIDC authentication configuration
     * @apiName GetAppConfigOidc
     * @apiGroup App
     * @apiSuccess {Boolean} enabled OIDC authentication enabled
     * @apiSuccess {String} issuer OIDC issuer URL
     * @apiSuccess {String} client_id OIDC client id
     * @apiSuccess {Boolean} client_secret_set True if a client secret is stored (the secret itself is write-only and never returned)
     * @apiSuccess {String} redirect_uri OIDC redirect URI
     * @apiSuccess {String} scope OIDC scope
     * @apiSuccess {String} authorization_endpoint Authorization endpoint (empty when derived from discovery)
     * @apiSuccess {String} token_endpoint Token endpoint (empty when derived from discovery)
     * @apiSuccess {String} jwks_uri JWKS URI (empty when derived from discovery)
     * @apiSuccess {String} userinfo_endpoint UserInfo endpoint (empty when derived from discovery)
     * @apiSuccess {String} username_claim Username claim name
     * @apiSuccess {String} email_claim Email claim name
     * @apiSuccess {Object} sources Per-field effective source (db | property | default)
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission admin
     * @apiVersion 1.12.0
     *
     * @return Response
     */
    @GET
    @Path("config_oidc")
    public Response getConfigOidc() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        String secret = OidcResource.oidcConfig(OidcResource.OidcKey.CLIENT_SECRET);

        // Per-field effective source so the UI can flag "currently from JVM property".
        JsonObjectBuilder sources = Json.createObjectBuilder();
        for (OidcResource.OidcKey key : OidcResource.OidcKey.values()) {
            sources.add(key.name().toLowerCase(), OidcResource.oidcConfigSource(key));
        }

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("enabled", OidcResource.isOidcEnabled())
                .add("issuer", nullToEmpty(OidcResource.oidcConfig(OidcResource.OidcKey.ISSUER)))
                .add("client_id", nullToEmpty(OidcResource.oidcConfig(OidcResource.OidcKey.CLIENT_ID)))
                // The client secret is write-only: NEVER echoed back, only a boolean flag.
                .add("client_secret_set", !Strings.isNullOrEmpty(secret))
                .add("redirect_uri", nullToEmpty(OidcResource.oidcConfig(OidcResource.OidcKey.REDIRECT_URI)))
                .add("scope", nullToEmpty(OidcResource.oidcConfig(OidcResource.OidcKey.SCOPE)))
                .add("authorization_endpoint", nullToEmpty(OidcResource.oidcConfig(OidcResource.OidcKey.AUTHORIZATION_ENDPOINT)))
                .add("token_endpoint", nullToEmpty(OidcResource.oidcConfig(OidcResource.OidcKey.TOKEN_ENDPOINT)))
                .add("jwks_uri", nullToEmpty(OidcResource.oidcConfig(OidcResource.OidcKey.JWKS_URI)))
                .add("userinfo_endpoint", nullToEmpty(OidcResource.oidcConfig(OidcResource.OidcKey.USERINFO_ENDPOINT)))
                .add("username_claim", nullToEmpty(OidcResource.oidcConfig(OidcResource.OidcKey.USERNAME_CLAIM)))
                .add("email_claim", nullToEmpty(OidcResource.oidcConfig(OidcResource.OidcKey.EMAIL_CLAIM)))
                .add("username_verbatim",
                        Boolean.parseBoolean(OidcResource.oidcConfig(OidcResource.OidcKey.USERNAME_VERBATIM)))
                .add("sources", sources);

        return Response.ok().entity(response.build()).build();
    }

    /**
     * Configures the OIDC authentication (provider/claim settings only, per the ADR-0015 fence).
     *
     * <p>Writes each of the OIDC config values to T_CONFIG (DB-first precedence: a stored
     * value overrides the {@code docs.oidc_*} JVM property). The client secret is write-only: an
     * EMPTY {@code client_secret} preserves the stored secret; an explicit
     * {@code client_secret_reset=true} clears it (the write-only contract stays intact — the GET
     * never returns it). Endpoint/issuer URLs must be http(s); the claim names must be non-blank.
     *
     * @api {post} /app/config_oidc Configure the OIDC authentication
     * @apiName PostAppConfigOidc
     * @apiGroup App
     * @apiParam {Boolean} enabled OIDC authentication enabled
     * @apiParam {String} issuer OIDC issuer URL
     * @apiParam {String} client_id OIDC client id
     * @apiParam {String} client_secret OIDC client secret (write-only; blank keeps the stored value)
     * @apiParam {Boolean} client_secret_reset If true, clears the stored client secret
     * @apiParam {String} redirect_uri OIDC redirect URI
     * @apiParam {String} scope OIDC scope
     * @apiParam {String} authorization_endpoint Authorization endpoint (optional; blank = derive from discovery)
     * @apiParam {String} token_endpoint Token endpoint (optional)
     * @apiParam {String} jwks_uri JWKS URI (optional)
     * @apiParam {String} userinfo_endpoint UserInfo endpoint (optional)
     * @apiParam {String} username_claim Username claim name
     * @apiParam {String} email_claim Email claim name
     * @apiParam {Boolean} username_verbatim If true, provision the sanitized preferred_username verbatim (no hash suffix)
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiPermission admin
     * @apiVersion 1.12.0
     *
     * @return Response
     */
    @POST
    @Path("config_oidc")
    public Response configOidc(@FormParam("enabled") Boolean enabled,
                               @FormParam("issuer") String issuer,
                               @FormParam("client_id") String clientId,
                               @FormParam("client_secret") String clientSecret,
                               @FormParam("client_secret_reset") Boolean clientSecretReset,
                               @FormParam("redirect_uri") String redirectUri,
                               @FormParam("scope") String scope,
                               @FormParam("authorization_endpoint") String authorizationEndpoint,
                               @FormParam("token_endpoint") String tokenEndpoint,
                               @FormParam("jwks_uri") String jwksUri,
                               @FormParam("userinfo_endpoint") String userinfoEndpoint,
                               @FormParam("username_claim") String usernameClaim,
                               @FormParam("email_claim") String emailClaim,
                               @FormParam("username_verbatim") Boolean usernameVerbatim) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        ConfigDao configDao = new ConfigDao();

        if (enabled != null && enabled) {
            // OIDC enabled: validate the provider/claim settings.
            issuer = ValidationUtil.validateLength(issuer, "issuer", 1, 500);
            issuer = ValidationUtil.validateHttpUrl(issuer, "issuer");
            clientId = ValidationUtil.validateLength(clientId, "client_id", 1, 250);
            redirectUri = ValidationUtil.validateLength(redirectUri, "redirect_uri", 1, 500);
            redirectUri = ValidationUtil.validateHttpUrl(redirectUri, "redirect_uri");
            scope = ValidationUtil.validateLength(scope, "scope", 1, 250);
            usernameClaim = ValidationUtil.validateLength(usernameClaim, "username_claim", 1, 100);
            emailClaim = ValidationUtil.validateLength(emailClaim, "email_claim", 1, 100);
            // Optional endpoints: if present they must be http(s); if blank they are cleared so
            // OidcResource derives them from discovery (the property/default falls through).
            authorizationEndpoint = validateOptionalHttpUrl(authorizationEndpoint, "authorization_endpoint");
            tokenEndpoint = validateOptionalHttpUrl(tokenEndpoint, "token_endpoint");
            jwksUri = validateOptionalHttpUrl(jwksUri, "jwks_uri");
            userinfoEndpoint = validateOptionalHttpUrl(userinfoEndpoint, "userinfo_endpoint");

            // The client secret is write-only (BL-028 pattern): the GET never echoes it, so the
            // client sends it blank to KEEP the stored value. On first-time setup (no stored
            // secret and no override property) a non-empty secret is required.
            Config storedSecret = configDao.getById(ConfigType.OIDC_CLIENT_SECRET);
            boolean hasStoredSecret = storedSecret != null && !Strings.isNullOrEmpty(storedSecret.getValue());
            boolean hasPropertySecret = !Strings.isNullOrEmpty(OidcResource.oidcConfig(OidcResource.OidcKey.CLIENT_SECRET))
                    && !hasStoredSecret;
            boolean resetSecret = clientSecretReset != null && clientSecretReset;
            if (Strings.isNullOrEmpty(clientSecret) && !resetSecret) {
                if (!hasStoredSecret && !hasPropertySecret) {
                    throw new ClientException("ValidationError", "client_secret must be set");
                }
            } else if (!Strings.isNullOrEmpty(clientSecret)) {
                ValidationUtil.validateLength(clientSecret, "client_secret", 1, 500);
            }

            configDao.update(ConfigType.OIDC_ENABLED, Boolean.TRUE.toString());
            configDao.update(ConfigType.OIDC_ISSUER, issuer);
            configDao.update(ConfigType.OIDC_CLIENT_ID, clientId);
            configDao.update(ConfigType.OIDC_REDIRECT_URI, redirectUri);
            configDao.update(ConfigType.OIDC_SCOPE, scope);
            configDao.update(ConfigType.OIDC_AUTHORIZATION_ENDPOINT, Strings.nullToEmpty(authorizationEndpoint));
            configDao.update(ConfigType.OIDC_TOKEN_ENDPOINT, Strings.nullToEmpty(tokenEndpoint));
            configDao.update(ConfigType.OIDC_JWKS_URI, Strings.nullToEmpty(jwksUri));
            configDao.update(ConfigType.OIDC_USERINFO_ENDPOINT, Strings.nullToEmpty(userinfoEndpoint));
            configDao.update(ConfigType.OIDC_USERNAME_CLAIM, usernameClaim);
            configDao.update(ConfigType.OIDC_EMAIL_CLAIM, emailClaim);
            // Verbatim-username opt-in (default OFF preserves the safe hash-suffix behaviour).
            configDao.update(ConfigType.OIDC_USERNAME_VERBATIM,
                    Boolean.toString(usernameVerbatim != null && usernameVerbatim));
            // Secret last: an explicit reset clears it; a non-empty value overwrites; a blank
            // value with no reset leaves the stored secret untouched (write-only keep-on-empty).
            if (resetSecret) {
                configDao.update(ConfigType.OIDC_CLIENT_SECRET, "");
            } else if (!Strings.isNullOrEmpty(clientSecret)) {
                configDao.update(ConfigType.OIDC_CLIENT_SECRET, clientSecret);
            }
        } else {
            // OIDC disabled: clear the DB enable so the effective value is false. Other stored
            // values are left as-is (re-enabling restores them without re-entry).
            configDao.update(ConfigType.OIDC_ENABLED, Boolean.FALSE.toString());
        }

        return Response.ok().build();
    }

    /**
     * Validates an OPTIONAL http(s) URL: a blank value returns empty (the field is cleared so the
     * value falls through to the discovery-derived endpoint); a non-blank value must be http(s).
     */
    private static String validateOptionalHttpUrl(String value, String name) {
        if (Strings.isNullOrEmpty(value) || value.trim().isEmpty()) {
            return "";
        }
        return ValidationUtil.validateHttpUrl(value, name);
    }

    /** Null-safe empty string for JSON string fields (Json.add rejects a null value). */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

}
