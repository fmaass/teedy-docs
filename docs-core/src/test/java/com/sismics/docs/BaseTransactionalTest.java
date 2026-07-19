package com.sismics.docs;

import com.sismics.BaseTest;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.docs.core.util.EncryptionUtil;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;
import com.sismics.util.mime.MimeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import java.io.InputStream;
import java.nio.file.Files;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Base class of tests with a transactional context.
 *
 * @author jtremeaux 
 */
public abstract class BaseTransactionalTest extends BaseTest {
    @BeforeEach
    public void setUp() {
        // Initialize the entity manager
        EntityManager em = EMF.get().createEntityManager();
        ThreadLocalContext context = ThreadLocalContext.get();
        context.setEntityManager(em);
        EntityTransaction tx = em.getTransaction();
        tx.begin();
    }

    @AfterEach
    public void tearDown() {
        ThreadLocalContext.get().getEntityManager().getTransaction().rollback();
    }

    /**
     * Seeds a user's current storage usage DIRECTLY (a single-column native UPDATE), for test setup.
     * Production code mutates storageCurrent only through the locked reserve/reclaim paths; the absolute
     * setters were removed because a caller-supplied absolute value is unsafe under concurrency, so tests
     * establish an initial value by writing the column directly rather than through a production API.
     *
     * @param userId User ID
     * @param value New storage_current value
     */
    protected void seedStorageCurrent(String userId, long value) {
        ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_USER set USE_STORAGECURRENT_N = :v where USE_ID_C = :id")
                .setParameter("v", value)
                .setParameter("id", userId)
                .executeUpdate();
    }

    protected User createUser(String userName) throws Exception {
        UserDao userDao = new UserDao();
        User user = new User();
        user.setUsername(userName);
        user.setPassword("12345678");
        user.setEmail("toto@docs.com");
        user.setRoleId("admin");
        user.setStorageQuota(100_000L);
        userDao.create(user, userName);
        return user;
    }

    protected File createFile(User user, long fileSize) throws Exception {
        FileDao fileDao = new FileDao();
        try(InputStream inputStream = getSystemResourceAsStream(FILE_JPG)) {
            File file = new File();
            file.setId("apollo_portrait");
            file.setUserId(user.getId());
            file.setVersion(0);
            file.setMimeType(MimeType.IMAGE_JPEG);
            file.setSize(fileSize);
            String fileId = fileDao.create(file, user.getId());
            Cipher cipher = EncryptionUtil.getEncryptionCipher(user.getPrivateKey());
            Files.copy(new CipherInputStream(inputStream, cipher), DirectoryUtil.getStorageDirectory().resolve(fileId), REPLACE_EXISTING);
            return file;
        }
    }
}
