package com.sismics.docs.rest;

import com.sismics.docs.core.event.FileUpdatedAsyncEvent;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.service.FileReconciliationService;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.docs.core.util.WebhookUtil;
import com.sismics.docs.rest.resource.ThirdPartyWebhookResource;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * End-to-end reconciliation of lost first-time processing (#159). A file whose post-commit processing was
 * lost (crash in the commit->process window) is recovered on the next boot: the startup reconciliation
 * service re-derives its content, search index entry and thumbnail — and does NOT emit a duplicate
 * FILE_CREATED webhook, while a normal upload still does.
 */
public class TestFileReconciliation extends BaseJerseyTest {
    /** The reconciliation replay decrypts to a local temp; the harness allows the loopback webhook target. */
    @BeforeEach
    public void allowPrivateWebhooks() {
        System.setProperty(WebhookUtil.ALLOW_PRIVATE_PROPERTY, "true");
    }

    @AfterEach
    public void resetPrivateWebhooks() {
        System.clearProperty(WebhookUtil.ALLOW_PRIVATE_PROPERTY);
        ThirdPartyWebhookResource.reset();
    }

    @Test
    public void crashWindowReplayRestoresDerivedDataAndSuppressesDuplicateWebhook() throws Exception {
        String adminToken = adminToken();
        clientUtil.createUser("recon_e2e");
        String token = clientUtil.login("recon_e2e");

        // Register a FILE_CREATED webhook and upload a searchable PDF the normal way.
        target().path("/webhook").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("event", "FILE_CREATED")
                        .param("url", "http://localhost:" + getPort() + "/docs/thirdpartywebhook")), JsonObject.class);

        String documentId = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_WIKIPEDIA_PDF, token, documentId);
        awaitProcessingQuiescence();

        // The live upload path fires FILE_CREATED (reprocess=false) and the file is searchable + marked.
        awaitCondition("PDF content not searchable after upload", () -> searchFindsDocument(token, documentId));
        JsonObject fired = ThirdPartyWebhookResource.getLastPayload();
        Assertions.assertNotNull(fired, "the live upload must fire a FILE_CREATED webhook");
        Assertions.assertEquals("FILE_CREATED", fired.getString("event"), "the live event is FILE_CREATED");
        Assertions.assertNotNull(readProcessed(fileId), "the live upload records the completion marker");

        Path thumb = DirectoryUtil.getStorageDirectory().resolve(fileId + "_thumb");
        Assertions.assertTrue(Files.exists(thumb), "the live upload generated a thumbnail");

        // Simulate the crash-window loss: clear the completion marker + extracted content, drop the index
        // entry, and delete the derived rasters — the durable original blob is untouched.
        TransactionUtil.handle(() -> {
            EntityManager em = ThreadLocalContext.get().getEntityManager();
            em.createNativeQuery("update T_FILE set FIL_PROCESSED_D = null, FIL_CONTENT_C = null where FIL_ID_C = :id")
                    .setParameter("id", fileId)
                    .executeUpdate();
        });
        AppContext.getInstance().getIndexingHandler().deleteDocument(fileId);
        Files.deleteIfExists(thumb);
        Files.deleteIfExists(DirectoryUtil.getStorageDirectory().resolve(fileId + "_web"));

        awaitCondition("the file must be unsearchable after the simulated loss",
                () -> !searchFindsDocument(token, documentId));

        // Reconcile. The reset payload observation makes "no duplicate webhook" checkable by absence.
        ThirdPartyWebhookResource.reset();
        new TestReconciler().runOnce();

        // Recovered: searchable again (index rebuilt via the keyed write), content + thumbnail restored, and
        // the completion marker re-recorded.
        awaitCondition("the reconciled file must be searchable again", () -> searchFindsDocument(token, documentId));
        Assertions.assertNotNull(readContent(fileId), "reconciliation re-extracted the file content");
        Assertions.assertNotNull(readProcessed(fileId), "reconciliation re-recorded the completion marker");
        Assertions.assertTrue(Files.exists(thumb), "reconciliation regenerated the thumbnail");

        // The replay (reprocess=true) must NOT emit a duplicate FILE_CREATED webhook.
        Assertions.assertNull(ThirdPartyWebhookResource.getLastPayload(),
                "a reconciliation replay must NOT fire a duplicate FILE_CREATED webhook");
    }

    @Test
    public void fileUpdatedWebhookFiresOnLiveEventButNotOnReplay() {
        String adminToken = adminToken();
        target().path("/webhook").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("event", "FILE_UPDATED")
                        .param("url", "http://localhost:" + getPort() + "/docs/thirdpartywebhook")), JsonObject.class);

        // A live FILE_UPDATED event (reprocess=false) fires the webhook.
        ThirdPartyWebhookResource.reset();
        FileUpdatedAsyncEvent live = new FileUpdatedAsyncEvent();
        live.setFileId("live-updated-file");
        AppContext.getInstance().getAsyncEventBus().post(live);
        JsonObject fired = ThirdPartyWebhookResource.getLastPayload();
        Assertions.assertNotNull(fired, "a live FILE_UPDATED event fires the webhook");
        Assertions.assertEquals("FILE_UPDATED", fired.getString("event"), "the live event is FILE_UPDATED");

        // A reconciliation replay (reprocess=true) is suppressed.
        ThirdPartyWebhookResource.reset();
        FileUpdatedAsyncEvent replay = new FileUpdatedAsyncEvent();
        replay.setFileId("replay-updated-file");
        replay.setReprocess(true);
        AppContext.getInstance().getAsyncEventBus().post(replay);
        Assertions.assertNull(ThirdPartyWebhookResource.getLastPayload(),
                "a reconciliation replay must NOT fire a duplicate FILE_UPDATED webhook");
    }

    /** Exposes the scheduled one-shot iteration (protected on the service) for a synchronous test drive. */
    private static final class TestReconciler extends FileReconciliationService {
        void runOnce() {
            runOneIteration();
        }
    }

    private boolean searchFindsDocument(String token, String documentId) {
        JsonArray documents = target().path("/document/list")
                .queryParam("search", "full:vrandecic")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class)
                .getJsonArray("documents");
        for (int i = 0; i < documents.size(); i++) {
            if (documentId.equals(documents.getJsonObject(i).getString("id"))) {
                return true;
            }
        }
        return false;
    }

    private java.util.Date readProcessed(String fileId) {
        java.util.concurrent.atomic.AtomicReference<java.util.Date> ref = new java.util.concurrent.atomic.AtomicReference<>();
        TransactionUtil.handle(() -> ref.set(new com.sismics.docs.core.dao.FileDao().getActiveById(fileId).getProcessed()));
        return ref.get();
    }

    private String readContent(String fileId) {
        java.util.concurrent.atomic.AtomicReference<String> ref = new java.util.concurrent.atomic.AtomicReference<>();
        TransactionUtil.handle(() -> ref.set(new com.sismics.docs.core.dao.FileDao().getActiveById(fileId).getContent()));
        return ref.get();
    }
}
