package com.sismics.docs.core.util.action;

import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.dao.dto.DocumentDto;
import com.sismics.docs.core.event.FileUpdatedAsyncEvent;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.docs.core.util.EncryptionUtil;
import com.sismics.docs.core.util.FileUtil;
import com.sismics.util.context.ThreadLocalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.json.JsonObject;
import java.nio.file.Path;
import java.util.List;

/**
 * Action to process all files.
 *
 * @author bgamard
 */
public class ProcessFilesAction implements Action {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(ProcessFilesAction.class);

    @Override
    public void execute(DocumentDto documentDto, JsonObject action) {
        FileDao fileDao = new FileDao();
        List<File> fileList = fileDao.getByDocumentId(null, documentDto.getId());

        for (File file : fileList) {
            String fileId = file.getId();
            // Null until THIS file is actually marked. Only a marked file owns a marker to compensate; a
            // pre-mark failure (e.g. a decrypt error) owns nothing, so it must NOT compensate — doing so
            // would call endProcessingFile on a marker this operation never acquired, clearing a
            // concurrent operation's live marker for the same file id.
            Runnable localCompensate = null;
            try {
                // Get the creating user
                UserDao userDao = new UserDao();
                User user = userDao.getById(file.getUserId());

                // Decrypt the file
                Path storedFile = DirectoryUtil.getStorageDirectory().resolve(fileId);
                Path unencryptedFile = EncryptionUtil.decryptFile(storedFile, user.getPrivateKey());

                // Start the asynchronous processing (marks the file and registers rollback compensation)
                localCompensate = FileUtil.markProcessingWithRollbackCleanup(fileId, unencryptedFile);
                FileUpdatedAsyncEvent event = new FileUpdatedAsyncEvent();
                event.setUserId("admin");
                event.setLanguage(documentDto.getLanguage());
                event.setFileId(fileId);
                event.setUnencryptedFile(unencryptedFile);
                ThreadLocalContext.get().addAsyncEvent(event);
            } catch (Exception e) {
                log.error("Error processing a file", e);
                // This runs inside an outer transaction that may still COMMIT (the failure is swallowed
                // here, no rollback fires). If the file was marked but its event was never queued, neither
                // the async listener nor a rollback will ever release it — compensate now. The one-shot
                // handle is shared with the registered rollback callback, so this is exactly-once even if
                // the outer transaction later rolls back anyway.
                if (localCompensate != null) {
                    localCompensate.run();
                }
            }
        }
    }

    @Override
    public void validate(JsonObject action) {
        // No parameter, so always OK
    }
}
