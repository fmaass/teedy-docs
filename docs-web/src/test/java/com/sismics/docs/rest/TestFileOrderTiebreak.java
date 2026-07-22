package com.sismics.docs.rest;

import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Regression test for #164: attachment ordering must be deterministic even when
 * two live files share the same {@code order} value — which happens when a file
 * is deleted and a new one takes the vacated slot count.
 */
public class TestFileOrderTiebreak extends BaseJerseyTest {

    @Test
    public void testDeleteThenAddProducesDeterministicOrder() throws Exception {
        clientUtil.createUser("file_order_tb");
        String token = clientUtil.login("file_order_tb");

        String docId = clientUtil.createDocument(token);

        String fileAId = clientUtil.addFileToDocument(FILE_PIA_00452_JPG, token, docId);
        String fileBId = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, token, docId);

        awaitAsyncQuiescence("files A and B processed");

        target().path("/file/" + fileAId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .delete(JsonObject.class);

        awaitAsyncQuiescence("file A deletion processed");

        String fileCId = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, token, docId);

        awaitAsyncQuiescence("file C processed");

        JsonArray files = target().path("/file/list")
                .queryParam("id", docId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class)
                .getJsonArray("files");

        Assertions.assertEquals(2, files.size());

        Assertions.assertEquals(fileBId, files.getJsonObject(0).getString("id"));
        Assertions.assertEquals(fileCId, files.getJsonObject(1).getString("id"));

        for (int run = 0; run < 5; run++) {
            JsonArray repeated = target().path("/file/list")
                    .queryParam("id", docId)
                    .request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                    .get(JsonObject.class)
                    .getJsonArray("files");
            Assertions.assertEquals(fileBId, repeated.getJsonObject(0).getString("id"),
                    "run " + run + ": first file must be B (earlier createDate)");
            Assertions.assertEquals(fileCId, repeated.getJsonObject(1).getString("id"),
                    "run " + run + ": second file must be C (later createDate)");
        }
    }
}
