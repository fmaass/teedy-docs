package com.sismics.docs.core.listener.async;

import com.google.common.collect.Lists;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import com.sismics.docs.core.constant.WebhookEvent;
import com.sismics.docs.core.dao.WebhookDao;
import com.sismics.docs.core.dao.criteria.WebhookCriteria;
import com.sismics.docs.core.dao.dto.WebhookDto;
import com.sismics.docs.core.event.*;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.docs.core.util.WebhookUtil;
import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Listener for triggering webhooks.
 * 
 * @author bgamard
 */
public class WebhookAsyncListener {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(WebhookAsyncListener.class);

    /**
     * OkHttp client. Redirect following is disabled: webhooks are fire-and-forget
     * notifications with no need to follow a 3xx, and following one would let an
     * allowed public URL redirect to a loopback/link-local/private address (e.g. the
     * cloud metadata endpoint 169.254.169.254), bypassing the SSRF validation applied
     * to the configured URL.
     */
    static final OkHttpClient client = new OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    @Subscribe
    @AllowConcurrentEvents
    public void on(final DocumentCreatedAsyncEvent event) {
        triggerWebhook(WebhookEvent.DOCUMENT_CREATED, event.getDocumentId());
    }

    @Subscribe
    @AllowConcurrentEvents
    public void on(final DocumentUpdatedAsyncEvent event) {
        triggerWebhook(WebhookEvent.DOCUMENT_UPDATED, event.getDocumentId());
    }

    @Subscribe
    @AllowConcurrentEvents
    public void on(final DocumentDeletedAsyncEvent event) {
        triggerWebhook(WebhookEvent.DOCUMENT_DELETED, event.getDocumentId());
    }

    @Subscribe
    @AllowConcurrentEvents
    public void on(final DocumentTrashedAsyncEvent event) {
        triggerWebhook(WebhookEvent.DOCUMENT_TRASHED, event.getDocumentId());
    }

    @Subscribe
    @AllowConcurrentEvents
    public void on(final DocumentRestoredAsyncEvent event) {
        triggerWebhook(WebhookEvent.DOCUMENT_RESTORED, event.getDocumentId());
    }

    @Subscribe
    @AllowConcurrentEvents
    public void on(final FileCreatedAsyncEvent event) {
        // A reconciliation replay (#159) re-fires the create event only to re-run lost processing; it is not
        // a user-facing file event, so it must NOT emit a second FILE_CREATED notification. Only the
        // reconciler sets this flag, so the live upload path is unchanged.
        if (event.isReprocess()) {
            return;
        }
        triggerWebhook(WebhookEvent.FILE_CREATED, event.getFileId());
    }

    @Subscribe
    @AllowConcurrentEvents
    public void on(final FileUpdatedAsyncEvent event) {
        // As above: a reconciliation replay must not emit a duplicate FILE_UPDATED notification.
        if (event.isReprocess()) {
            return;
        }
        triggerWebhook(WebhookEvent.FILE_UPDATED, event.getFileId());
    }

    @Subscribe
    @AllowConcurrentEvents
    public void on(final FileDeletedAsyncEvent event) {
        triggerWebhook(WebhookEvent.FILE_DELETED, event.getFileId());
    }

    @Subscribe
    @AllowConcurrentEvents
    public void on(final RouteStartedAsyncEvent event) {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("route_id", event.getRouteId());
        extra.put("step_name", event.getStepName());
        triggerWebhook(WebhookEvent.ROUTE_STARTED, event.getDocumentId(), extra);
    }

    @Subscribe
    @AllowConcurrentEvents
    public void on(final RouteStepTransitionedAsyncEvent event) {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("route_id", event.getRouteId());
        extra.put("step_name", event.getStepName());
        extra.put("transition", event.getTransition());
        triggerWebhook(WebhookEvent.ROUTE_STEP_TRANSITIONED, event.getDocumentId(), extra);
    }

    @Subscribe
    @AllowConcurrentEvents
    public void on(final RouteCompletedAsyncEvent event) {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("route_id", event.getRouteId());
        triggerWebhook(WebhookEvent.ROUTE_COMPLETED, event.getDocumentId(), extra);
    }

    /**
     * Trigger the webhooks for the specified event.
     *
     * @param event Event
     * @param id ID
     */
    private void triggerWebhook(WebhookEvent event, String id) {
        triggerWebhook(event, id, null);
    }

    /**
     * Trigger the webhooks for the specified event, with optional additional string payload fields.
     * The base payload keeps the historical shape {"event": ..., "id": ...}; any {@code extraFields}
     * are appended as additional top-level string properties (used by the route webhooks to carry
     * the route id, step name and transition).
     *
     * @param event Event
     * @param id ID (document or file id; the routed document id for route events)
     * @param extraFields Additional payload fields (may be null); null values are skipped
     */
    private void triggerWebhook(WebhookEvent event, String id, Map<String, String> extraFields) {
        List<String> webhookUrlList = Lists.newArrayList();

        TransactionUtil.handle(() -> {
            WebhookDao webhookDao = new WebhookDao();
            List<WebhookDto> webhookDtoList = webhookDao.findByCriteria(new WebhookCriteria().setEvent(event), null);
            for (WebhookDto webhookDto : webhookDtoList) {
                webhookUrlList.add(webhookDto.getUrl());
            }
        });

        JsonObjectBuilder payloadBuilder = Json.createObjectBuilder()
                .add("event", event.name())
                .add("id", id == null ? "" : id);
        if (extraFields != null) {
            for (Map.Entry<String, String> entry : extraFields.entrySet()) {
                if (entry.getValue() != null) {
                    payloadBuilder.add(entry.getKey(), entry.getValue());
                }
            }
        }
        RequestBody body = RequestBody.create(payloadBuilder.build().toString(), JSON);

        for (String webhookUrl : webhookUrlList) {
            // Re-validate before the outbound call: blocks SSRF for webhooks that predate
            // configuration-time validation and narrows the DNS-rebinding window.
            if (!WebhookUtil.isUrlAllowed(webhookUrl)) {
                log.warn("Skipping webhook with disallowed URL: " + webhookUrl);
                continue;
            }
            Request request = new Request.Builder()
                    .url(webhookUrl)
                    .post(body)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                log.info("Successfully called the webhook at: " + webhookUrl + " - " + response.code());
            } catch (IOException e) {
                log.error("Error calling the webhook at: " + webhookUrl, e);
            }
        }
    }
}
