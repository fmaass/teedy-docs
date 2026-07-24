package com.sismics.docs.rest;

import com.google.common.eventbus.EventBus;
import com.sismics.docs.core.listener.async.DocumentUpdatedAsyncListener;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Tests the explicit document-cover endpoints (#174): {@code POST /document/{id}/cover} sets the
 * cover, {@code DELETE /document/{id}/cover} clears it. The served pointer (file_id) is reconciled by
 * the document-updated listener: an explicit cover that is still attached wins; a dangling explicit
 * cover (its file deleted) is cleared and the served pointer falls back to the first file by order.
 */
public class TestDocumentCover extends BaseJerseyTest {

    private static final String COOKIE = TokenBasedSecurityFilter.COOKIE_NAME;

    private JsonObject getDocument(String documentId, String token) {
        return target().path("/document/" + documentId).request()
                .cookie(COOKIE, token)
                .get(JsonObject.class);
    }

    /**
     * An explicit cover wins over file order; deleting the explicit cover clears it and the served
     * pointer falls back to the first file by order.
     */
    @Test
    public void testCoverPrecedenceAndFallback() throws Exception {
        clientUtil.createUser("cover_prec");
        String token = clientUtil.login("cover_prec");

        String documentId = clientUtil.createDocument(token);
        String file1Id = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, token, documentId);
        String file2Id = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, documentId);
        awaitAsyncQuiescence("file processing must settle before reading the served cover pointer");

        JsonObject json = getDocument(documentId, token);
        Assertions.assertTrue(json.isNull("file_id_cover"), "a fresh document has no explicit cover");
        Assertions.assertEquals(file1Id, json.getString("file_id"), "the served pointer defaults to the first file");

        Response setResponse = target().path("/document/" + documentId + "/cover").request()
                .cookie(COOKIE, token)
                .post(Entity.form(new Form().param("file", file2Id)));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(setResponse.getStatus()));
        awaitAsyncQuiescence("the cover change must reconcile the served pointer");

        json = getDocument(documentId, token);
        Assertions.assertEquals(file2Id, json.getString("file_id_cover"), "the explicit cover is recorded");
        Assertions.assertEquals(file2Id, json.getString("file_id"), "the explicit cover becomes the served pointer");

        target().path("/file/" + file2Id).request().cookie(COOKIE, token).delete(JsonObject.class);
        awaitAsyncQuiescence("deleting the cover file must clear the dangling cover and re-derive the pointer");

        json = getDocument(documentId, token);
        Assertions.assertTrue(json.isNull("file_id_cover"), "a dangling explicit cover is cleared when its file is deleted");
        Assertions.assertEquals(file1Id, json.getString("file_id"), "the served pointer falls back to the first remaining file");
    }

    /**
     * Clearing the explicit cover restores the derived (first-file-by-order) served pointer.
     */
    @Test
    public void testClearCoverRestoresDerived() throws Exception {
        clientUtil.createUser("cover_clear");
        String token = clientUtil.login("cover_clear");

        String documentId = clientUtil.createDocument(token);
        String file1Id = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, token, documentId);
        String file2Id = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, documentId);
        awaitAsyncQuiescence("file processing must settle");

        target().path("/document/" + documentId + "/cover").request()
                .cookie(COOKIE, token)
                .post(Entity.form(new Form().param("file", file2Id)), JsonObject.class);
        awaitAsyncQuiescence("the cover change must reconcile the served pointer");
        Assertions.assertEquals(file2Id, getDocument(documentId, token).getString("file_id"));

        Response clearResponse = target().path("/document/" + documentId + "/cover").request()
                .cookie(COOKIE, token)
                .delete();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(clearResponse.getStatus()));
        awaitAsyncQuiescence("clearing the cover must re-derive the served pointer");

        JsonObject json = getDocument(documentId, token);
        Assertions.assertTrue(json.isNull("file_id_cover"), "clearing removes the explicit cover");
        Assertions.assertEquals(file1Id, json.getString("file_id"), "the served pointer re-derives to the first file");
    }

    /**
     * A caller without WRITE on the document cannot set its cover (403).
     */
    @Test
    public void testSetCoverForbiddenWithoutWrite() throws Exception {
        clientUtil.createUser("cover_owner");
        String ownerToken = clientUtil.login("cover_owner");
        clientUtil.createUser("cover_stranger");
        String strangerToken = clientUtil.login("cover_stranger");

        String documentId = clientUtil.createDocument(ownerToken);
        String fileId = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, ownerToken, documentId);
        awaitAsyncQuiescence("file processing must settle");

        Response response = target().path("/document/" + documentId + "/cover").request()
                .cookie(COOKIE, strangerToken)
                .post(Entity.form(new Form().param("file", fileId)));
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()));

        Response clearResponse = target().path("/document/" + documentId + "/cover").request()
                .cookie(COOKIE, strangerToken)
                .delete();
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(clearResponse.getStatus()));
    }

    /**
     * Setting a cover to a file that is not attached to the document is a 400 client error.
     */
    @Test
    public void testSetCoverBadRequestForUnattachedFile() throws Exception {
        clientUtil.createUser("cover_unattached");
        String token = clientUtil.login("cover_unattached");

        String documentId = clientUtil.createDocument(token);
        clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, token, documentId);
        String otherDocumentId = clientUtil.createDocument(token);
        String otherFileId = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, token, otherDocumentId);
        awaitAsyncQuiescence("file processing must settle");

        Response otherDocFile = target().path("/document/" + documentId + "/cover").request()
                .cookie(COOKIE, token)
                .post(Entity.form(new Form().param("file", otherFileId)));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(otherDocFile.getStatus()));

        Response bogusFile = target().path("/document/" + documentId + "/cover").request()
                .cookie(COOKIE, token)
                .post(Entity.form(new Form().param("file", "no-such-file-id")));
        Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(bogusFile.getStatus()));
    }

    /**
     * The endpoint reconciles the served pointer synchronously in its own transaction. The
     * document-updated listener is diverted so it CANNOT reconcile during the test window; the
     * read-back then reflects only the endpoint's own work, and is stale if that synchronous reconcile
     * is removed.
     */
    @Test
    public void testSetCoverServesSynchronously() throws Exception {
        clientUtil.createUser("cover_sync");
        String token = clientUtil.login("cover_sync");

        String documentId = clientUtil.createDocument(token);
        String file1Id = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, token, documentId);
        String file2Id = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, documentId);
        awaitAsyncQuiescence("file processing must settle before the cover change");
        Assertions.assertEquals(file1Id, getDocument(documentId, token).getString("file_id"));

        EventBus bus = AppContext.getInstance().getAsyncEventBus();
        Object listener = unregisterListener(bus, DocumentUpdatedAsyncListener.class);
        Assertions.assertNotNull(listener, "the document-updated listener must be registered to divert");
        try {
            Response setResponse = target().path("/document/" + documentId + "/cover").request()
                    .cookie(COOKIE, token)
                    .post(Entity.form(new Form().param("file", file2Id)));
            Assertions.assertEquals(Status.OK, Status.fromStatusCode(setResponse.getStatus()));

            JsonObject json = getDocument(documentId, token);
            Assertions.assertEquals(file2Id, json.getString("file_id_cover"));
            Assertions.assertEquals(file2Id, json.getString("file_id"));
        } finally {
            bus.register(listener);
        }
    }

    /**
     * A guest session must not set a document cover even when the guest account HOLDS a WRITE ACL on
     * the document — the guest guard, not the ACL check, is what rejects it.
     */
    @Test
    public void testGuestCannotSetCover() throws Exception {
        clientUtil.createUser("cover_g_owner");
        String ownerToken = clientUtil.login("cover_g_owner");
        String documentId = clientUtil.createDocument(ownerToken);
        String fileId = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, ownerToken, documentId);
        awaitAsyncQuiescence("file processing must settle");
        String guestToken = guestWithWriteAcl(documentId, ownerToken);

        Response set = target().path("/document/" + documentId + "/cover").request()
                .cookie(COOKIE, guestToken)
                .post(Entity.form(new Form().param("file", fileId)));
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(set.getStatus()));
    }

    /**
     * A guest session must not clear a document cover even when the guest account holds a WRITE ACL.
     */
    @Test
    public void testGuestCannotClearCover() throws Exception {
        clientUtil.createUser("cover_gc_owner");
        String ownerToken = clientUtil.login("cover_gc_owner");
        String documentId = clientUtil.createDocument(ownerToken);
        clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, ownerToken, documentId);
        awaitAsyncQuiescence("file processing must settle");
        String guestToken = guestWithWriteAcl(documentId, ownerToken);

        Response clear = target().path("/document/" + documentId + "/cover").request()
                .cookie(COOKIE, guestToken)
                .delete();
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(clear.getStatus()));
    }

    /**
     * Enables guest login, grants the guest account WRITE on the document, and returns a guest token.
     * Asserts the guest actually resolves that WRITE (writable=true) so a later 403 can only come from
     * the guest guard, not a missing grant.
     */
    private String guestWithWriteAcl(String documentId, String ownerToken) {
        target().path("/app/guest_login").request()
                .cookie(COOKIE, adminToken())
                .post(Entity.form(new Form().param("enabled", "true")), JsonObject.class);
        for (String perm : new String[]{"READ", "WRITE"}) {
            target().path("/acl").request()
                    .cookie(COOKIE, ownerToken)
                    .put(Entity.form(new Form()
                            .param("source", documentId)
                            .param("perm", perm)
                            .param("target", "guest")
                            .param("type", "USER")), JsonObject.class);
        }
        String guestToken = clientUtil.login("guest", "", false);
        Assertions.assertTrue(getDocument(documentId, guestToken).getBoolean("writable"),
                "the guest must resolve WRITE on the document for this test to exercise the guard");
        return guestToken;
    }

    /**
     * Unregisters (and returns) the async-bus subscriber of the given type so a test can suppress a
     * production listener for its window. The subscriber instance is reached through the EventBus
     * registry because the listeners are registered anonymously; returns null when none is registered.
     */
    private static Object unregisterListener(EventBus bus, Class<?> type) throws Exception {
        Field registryField = EventBus.class.getDeclaredField("subscribers");
        registryField.setAccessible(true);
        Object registry = registryField.get(bus);
        Field mapField = registry.getClass().getDeclaredField("subscribers");
        mapField.setAccessible(true);
        Map<?, ?> byType = (Map<?, ?>) mapField.get(registry);
        for (Object set : byType.values()) {
            for (Object subscriber : (Iterable<?>) set) {
                Object target = readTarget(subscriber);
                if (type.isInstance(target)) {
                    bus.unregister(target);
                    return target;
                }
            }
        }
        return null;
    }

    private static Object readTarget(Object subscriber) throws Exception {
        for (Class<?> c = subscriber.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field targetField = c.getDeclaredField("target");
                targetField.setAccessible(true);
                return targetField.get(subscriber);
            } catch (NoSuchFieldException ignored) {
                // Walk up to the base Subscriber class.
            }
        }
        throw new NoSuchFieldException("target");
    }

    /**
     * A document whose explicit cover is set can still be permanently deleted — the cover column is not
     * a foreign key, so it never blocks a purge.
     */
    @Test
    public void testPermanentDeleteWithCoverSet() throws Exception {
        clientUtil.createUser("cover_purge");
        String token = clientUtil.login("cover_purge");

        String documentId = clientUtil.createDocument(token);
        String fileId = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, token, documentId);
        awaitAsyncQuiescence("file processing must settle");

        target().path("/document/" + documentId + "/cover").request()
                .cookie(COOKIE, token)
                .post(Entity.form(new Form().param("file", fileId)), JsonObject.class);
        awaitAsyncQuiescence("the cover change must reconcile the served pointer");
        Assertions.assertEquals(fileId, getDocument(documentId, token).getString("file_id_cover"));

        target().path("/document/" + documentId).request().cookie(COOKIE, token).delete(JsonObject.class);
        JsonObject purge = target().path("/document/" + documentId + "/permanent").request()
                .cookie(COOKIE, token)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", purge.getString("status"));

        Response afterPurge = target().path("/document/" + documentId).request().cookie(COOKIE, token).get();
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(afterPurge.getStatus()));
    }
}
