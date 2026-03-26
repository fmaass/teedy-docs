package com.sismics.docs.core.service;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestFileSizeService extends BaseTransactionalTest {

    @Test
    public void processFileTest() throws Exception {
        User user = createUser("processFileTest");

        FileDao fileDao = new FileDao();
        File file = createFile(user, File.UNKNOWN_SIZE);
        FileSizeService fileSizeService = new FileSizeService();
        fileSizeService.processFile(file);
        Assertions.assertEquals(fileDao.getFile(file.getId()).getSize(), Long.valueOf(FILE_JPG_SIZE));
    }
}
