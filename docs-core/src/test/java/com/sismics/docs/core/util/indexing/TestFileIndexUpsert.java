package com.sismics.docs.core.util.indexing;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.util.context.ThreadLocalContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The processing pipeline indexes every file with the KEYED write (#159 blocker 4): a live first-index and
 * a reconciliation replay both call {@link LuceneIndexingHandler#updateFile}, which deletes any existing
 * entry for the id then adds, so they converge to a SINGLE index doc. The append-only
 * {@link LuceneIndexingHandler#createFile} the pipeline no longer uses would instead accumulate a duplicate
 * — the contrast proves why the keyed write is required.
 */
public class TestFileIndexUpsert extends BaseTransactionalTest {

    private LuceneIndexingHandler ramHandler() throws Exception {
        // Force a private RAM index so this test neither touches nor conflicts with the file-backed index a
        // booted AppContext would use.
        ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_CONFIG set CFG_VALUE_C = 'RAM' where CFG_ID_C = :id")
                .setParameter("id", ConfigType.LUCENE_DIRECTORY_STORAGE.name())
                .executeUpdate();
        LuceneIndexingHandler handler = new LuceneIndexingHandler();
        handler.startUp();
        return handler;
    }

    private File fileWithId(String id) {
        File file = new File();
        file.setId(id);
        file.setName(id + ".txt");
        file.setMimeType("text/plain");
        return file;
    }

    @Test
    public void keyedUpsertConvergesToOneDocWhileAppendDuplicates() throws Exception {
        LuceneIndexingHandler handler = ramHandler();
        try {
            // The path the pipeline now takes on EVERY write (live first-index AND replay): keyed upsert.
            File upsert = fileWithId("upsert-file");
            Assertions.assertTrue(handler.updateFile(upsert), "the keyed write commits");
            Assertions.assertTrue(handler.updateFile(upsert), "a second keyed write commits");
            Assertions.assertEquals(1, handler.countIndexedDocuments("upsert-file"),
                    "a keyed write converges to ONE index doc no matter how many times it runs");

            // The append-only path the pipeline no longer uses: two calls duplicate the entry.
            File append = fileWithId("append-file");
            Assertions.assertTrue(handler.createFile(append), "the append write commits");
            Assertions.assertTrue(handler.createFile(append), "a second append write commits");
            Assertions.assertEquals(2, handler.countIndexedDocuments("append-file"),
                    "append accumulates a duplicate — proving the pipeline must (and does) use the keyed write");
        } finally {
            handler.shutDown();
        }
    }
}
