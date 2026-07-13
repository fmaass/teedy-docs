package com.sismics.docs.rest.resource;

import com.google.common.base.Strings;
import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.dao.ConfigDao;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.StatsDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.dao.dto.UserStorageDto;
import com.sismics.docs.core.event.RebuildIndexAsyncEvent;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.model.jpa.Config;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.service.InboxService;
import com.sismics.docs.core.service.TrashPurgeService;
import com.sismics.docs.core.util.ConfigUtil;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.docs.core.util.StatsBucketUtil;
import com.sismics.docs.core.util.jpa.PaginatedList;
import com.sismics.docs.core.util.jpa.PaginatedLists;
import com.sismics.docs.rest.constant.BaseFunction;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.exception.ServerException;
import com.sismics.rest.util.ValidationUtil;
import com.sismics.util.JsonUtil;
import com.sismics.util.context.ThreadLocalContext;
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

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
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
        if (!Strings.isNullOrEmpty(tag)) {
            configDao.update(ConfigType.INBOX_TAG, tag);
        }

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
     * Clean storage.
     *
     * @api {post} /app/batch/clean_storage Clean the file and DB storage
     * @apiName PostAppBatchCleanStorage
     * @apiGroup App
     * @apiSuccess {String} status Status OK
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

        // Get all files
        FileDao fileDao = new FileDao();
        List<File> fileList = fileDao.findAll(0, Integer.MAX_VALUE);
        Map<String, File> fileMap = new HashMap<>();
        for (File file : fileList) {
            fileMap.put(file.getId(), file);
        }
        log.info("Checking {} files", fileMap.size());

        // Check if each stored file is valid
        try (DirectoryStream<java.nio.file.Path> storedFileList = Files.newDirectoryStream(DirectoryUtil.getStorageDirectory())) {
            for (java.nio.file.Path storedFile : storedFileList) {
                String fileName = storedFile.getFileName().toString();
                String[] fileNameArray = fileName.split("_");
                if (!fileMap.containsKey(fileNameArray[0])) {
                    log.info("Deleting orphan files at this location: {}", storedFile);
                    Files.delete(storedFile);
                }
            }
        } catch (IOException e) {
            throw new ServerException("FileError", "Error deleting orphan files", e);
        }

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

        // Soft delete orphan tags
        q = em.createNativeQuery("update T_TAG set TAG_DELETEDATE_D = :dateNow where TAG_ID_C in (select t.TAG_ID_C from T_TAG t left join T_USER u on u.USE_ID_C = t.TAG_IDUSER_C and u.USE_DELETEDATE_D is null where u.USE_ID_C is null)");
        q.setParameter("dateNow", new Date());
        log.info("Deleting {} orphan tags", q.executeUpdate());

        // Soft delete orphan documents
        q = em.createNativeQuery("update T_DOCUMENT set DOC_DELETEDATE_D = :dateNow where DOC_ID_C in (select d.DOC_ID_C from T_DOCUMENT d left join T_USER u on u.USE_ID_C = d.DOC_IDUSER_C and u.USE_DELETEDATE_D is null where u.USE_ID_C is null)");
        q.setParameter("dateNow", new Date());
        log.info("Deleting {} orphan documents", q.executeUpdate());

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

        // Hard delete saved filters owned by soft-deleted users before the user hard-delete
        // (T_SAVED_FILTER.FK_SFL_IDUSER_C is ON DELETE RESTRICT, so a lingering filter would abort the purge)
        q = em.createNativeQuery("delete from T_SAVED_FILTER where SFL_IDUSER_C in (select u.USE_ID_C from T_USER u where u.USE_DELETEDATE_D is not null)");
        log.info("Deleting {} saved filters of soft deleted users", q.executeUpdate());

        // Clear main-file pointers to soft-deleted files before the file hard-delete
        // (T_DOCUMENT.FK_DOC_IDFILE_C is ON DELETE RESTRICT, so a document still pointing at a
        // soft-deleted file would abort the whole purge)
        q = em.createNativeQuery("update T_DOCUMENT set DOC_IDFILE_C = null where DOC_IDFILE_C in (select FIL_ID_C from T_FILE where FIL_DELETEDATE_D is not null)");
        log.info("Clearing {} main-file pointers to soft deleted files", q.executeUpdate());

        // Hard delete softly deleted data
        log.info("Deleting {} soft deleted document tag links", em.createQuery("delete DocumentTag where deleteDate is not null").executeUpdate());
        log.info("Deleting {} soft deleted ACLs", em.createQuery("delete Acl where deleteDate is not null").executeUpdate());
        log.info("Deleting {} soft deleted shares", em.createQuery("delete Share where deleteDate is not null").executeUpdate());
        log.info("Deleting {} soft deleted tags", em.createQuery("delete Tag where deleteDate is not null").executeUpdate());
        log.info("Deleting {} soft deleted comments", em.createQuery("delete Comment where deleteDate is not null").executeUpdate());
        log.info("Deleting {} soft deleted files", em.createQuery("delete File where deleteDate is not null").executeUpdate());
        log.info("Deleting {} soft deleted documents", em.createQuery("delete Document where deleteDate is not null").executeUpdate());

        // Hard delete soft-deleted users, EXCEPT any that live data still references under an ON DELETE
        // RESTRICT foreign key — hard-deleting such a user would abort the whole purge. A user whose
        // documents were reassigned away is retained here as a hidden ghost key-holder: soft-deleted and
        // invisible, kept solely so its privateKey still decrypts the reassigned files it originally
        // uploaded and so it does not orphan the (surviving) routes it initiated. It becomes purgeable
        // once no live data references it. The referencing live FKs are:
        //   - T_FILE.FK_FIL_IDUSER_C           — a live file's uploader (the retained decryption key)
        //   - T_ROUTE.FK_RTE_IDUSER_C          — a live route's initiator (NOT NULL)
        //   - T_ROUTE_STEP.FK_RTP_IDVALIDATORUSER_C — a live route step's validator (nullable)
        // (RTP_IDTARGET_C is a name-resolved principal, not a T_USER FK, so it does not restrict.)
        Query userPurge = em.createNativeQuery("delete from T_USER where USE_DELETEDATE_D is not null"
                + " and USE_ID_C not in (select f.FIL_IDUSER_C from T_FILE f where f.FIL_DELETEDATE_D is null)"
                + " and USE_ID_C not in (select r.RTE_IDUSER_C from T_ROUTE r where r.RTE_DELETEDATE_D is null)"
                + " and USE_ID_C not in (select rs.RTP_IDVALIDATORUSER_C from T_ROUTE_STEP rs where rs.RTP_IDVALIDATORUSER_C is not null and rs.RTP_DELETEDATE_D is null)");
        log.info("Deleting {} soft deleted users", userPurge.executeUpdate());
        log.info("Deleting {} soft deleted groups", em.createQuery("delete Group where deleteDate is not null").executeUpdate());

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
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
        if (enabled != null && Boolean.parseBoolean(enabled.getValue())) {
            // LDAP enabled
            Config adminPassword = configDao.getById(ConfigType.LDAP_ADMIN_PASSWORD);
            boolean adminPasswordSet = adminPassword != null && !Strings.isNullOrEmpty(adminPassword.getValue());
            response.add("enabled", true)
                    .add("host", ConfigUtil.getConfigStringValue(ConfigType.LDAP_HOST))
                    .add("port", ConfigUtil.getConfigIntegerValue(ConfigType.LDAP_PORT))
                    .add("usessl", ConfigUtil.getConfigBooleanValue(ConfigType.LDAP_USESSL))
                    .add("admin_dn", ConfigUtil.getConfigStringValue(ConfigType.LDAP_ADMIN_DN))
                    // The admin bind password is write-only: it is NEVER echoed back (BL-028).
                    // Only a boolean "is a password stored?" flag is exposed, so the UI shows a
                    // "leave blank to keep" affordance instead of the secret. The POST keeps the
                    // stored value when admin_password is absent/empty.
                    .add("admin_password_set", adminPasswordSet)
                    .add("base_dn", ConfigUtil.getConfigStringValue(ConfigType.LDAP_BASE_DN))
                    .add("filter", ConfigUtil.getConfigStringValue(ConfigType.LDAP_FILTER))
                    .add("default_email", ConfigUtil.getConfigStringValue(ConfigType.LDAP_DEFAULT_EMAIL))
                    .add("default_storage", ConfigUtil.getConfigLongValue(ConfigType.LDAP_DEFAULT_STORAGE));
        } else {
            // LDAP disabled
            response.add("enabled", false);
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
