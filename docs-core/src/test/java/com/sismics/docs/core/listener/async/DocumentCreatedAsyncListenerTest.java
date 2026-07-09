package com.sismics.docs.core.listener.async;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.ContributorDao;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.event.DocumentCreatedAsyncEvent;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.TransactionUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;

public class DocumentCreatedAsyncListenerTest extends BaseTransactionalTest {

    /**
     * Simulates the retry path (RetryingSubscriberExceptionHandler re-invoking the subscriber):
     * deliver the same DocumentCreatedAsyncEvent twice. The creating contributor must be added
     * exactly ONCE — a re-delivery must not add a duplicate contributor row.
     */
    @Test
    public void redeliveryDoesNotDuplicateContributor() throws Exception {
        User user = createUser("docCreatedRedelivery");

        DocumentDao documentDao = new DocumentDao();
        Document document = new Document();
        document.setUserId(user.getId());
        document.setLanguage("eng");
        document.setTitle("Retry idempotency doc");
        document.setCreateDate(new Date());
        String documentId = documentDao.create(document, user.getId());

        TransactionUtil.commit();

        DocumentCreatedAsyncEvent event = new DocumentCreatedAsyncEvent();
        event.setDocumentId(documentId);
        event.setUserId(user.getId());

        DocumentCreatedAsyncListener listener = new DocumentCreatedAsyncListener();
        listener.on(event);
        // Re-delivery (retry): must not add a second contributor.
        listener.on(event);

        ContributorDao contributorDao = new ContributorDao();
        long count = contributorDao.findByDocumentId(documentId).stream()
                .filter(c -> c.getUserId().equals(user.getId()))
                .count();
        Assertions.assertEquals(1, count, "Creating contributor must be added exactly once across re-delivery");
    }
}
