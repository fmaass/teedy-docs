package com.sismics.docs.core.util;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.mime.MimeType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Behavioral tests for {@link UserDao#getGlobalStorageCurrent()} and the ghost-retained bytes it must
 * account for (#99). A reassign-delete (#55) leaves a departing user's retained files live while the
 * departing user is soft-deleted (a "ghost"); its {@code USE_STORAGECURRENT_N} counter is both stale and
 * excluded from an active-user sum, so global storage must be taken from the surviving FILE rows, not the
 * ghost's counter. These exercise the DAO directly with seeded ghost state (the end-to-end reassign-delete
 * path is characterized in {@code TestUserResourceReassign}).
 */
public class TestGlobalStorageGhost extends BaseTransactionalTest {

    /** Soft-deletes a user (makes it a ghost) by stamping USE_DELETEDATE_D directly, for test setup. */
    private void softDeleteUser(String userId) {
        ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_USER set USE_DELETEDATE_D = current_timestamp where USE_ID_C = :id")
                .setParameter("id", userId)
                .executeUpdate();
    }

    /** Forces a file row's stored size, for test setup (e.g. the legacy {@link File#UNKNOWN_SIZE} state). */
    private void setFileSize(String fileId, long size) {
        ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_FILE set FIL_SIZE_N = :v where FIL_ID_C = :id")
                .setParameter("v", size)
                .setParameter("id", fileId)
                .executeUpdate();
    }

    /** Creates a live known-size file row owned by {@code user}, with no on-disk content. Returns its id. */
    private String createKnownSizeFile(User user, long size) {
        File file = new File();
        file.setUserId(user.getId());
        file.setVersion(0);
        file.setLatestVersion(true);
        file.setMimeType(MimeType.IMAGE_JPEG);
        file.setSize(size);
        return new FileDao().create(file, user.getId());
    }

    /**
     * Creates a live file row owned by {@code user} with a unique id AND real encrypted on-disk content
     * (the JPG test fixture, {@link #FILE_JPG_SIZE} plaintext bytes), so an UNKNOWN_SIZE row can be sized
     * from disk. Mirrors {@code BaseTransactionalTest.createFile} but with a caller-chosen id so several
     * files can coexist. Returns its id.
     */
    private String createFileWithDiskContent(User user, String fileId, long declaredSize) throws Exception {
        try (InputStream inputStream = getSystemResourceAsStream(FILE_JPG)) {
            File file = new File();
            file.setId(fileId);
            file.setUserId(user.getId());
            file.setVersion(0);
            file.setLatestVersion(true);
            file.setMimeType(MimeType.IMAGE_JPEG);
            file.setSize(declaredSize);
            String createdId = new FileDao().create(file, user.getId());
            Cipher cipher = EncryptionUtil.getEncryptionCipher(user.getPrivateKey());
            Files.copy(new CipherInputStream(inputStream, cipher),
                    DirectoryUtil.getStorageDirectory().resolve(createdId), REPLACE_EXISTING);
            return createdId;
        }
    }

    /**
     * Mixed-ghost with an UNKNOWN_SIZE retained file: a soft-deleted (ghost) user owns two live retained
     * files — one with a known {@code FIL_SIZE_N} and one whose row is {@link File#UNKNOWN_SIZE}
     * ({@code -1}). The global sum must add the known size AND the UNKNOWN file's ACTUAL physical size
     * (resolved from the on-disk encrypted content), never the raw {@code -1} and never {@code 0}.
     */
    @Test
    public void unknownSizeGhostFileIsSizedFromDisk() throws Exception {
        UserDao userDao = new UserDao();

        // Baseline: the sum over the currently-active users (the ghost's contributions are added below).
        long baseline = userDao.getGlobalStorageCurrent();

        User ghost = createUser("ghostUnknownSize");
        // A known-size retained file (exercises the known-size ghost-bytes arm).
        long knownSize = 3_000L;
        createKnownSizeFile(ghost, knownSize);
        // An UNKNOWN_SIZE retained file with real on-disk content: created with a real declared size, then
        // forced to -1 so ONLY disk resolution can recover its true size.
        String unknownFileId = createFileWithDiskContent(ghost, "ghost_unknown_size_file", FILE_JPG_SIZE);
        setFileSize(unknownFileId, File.UNKNOWN_SIZE);

        // Make the owner a ghost: its files stay live but its counter is now excluded from the active sum.
        softDeleteUser(ghost.getId());

        long expected = baseline + knownSize + FILE_JPG_SIZE;
        Assertions.assertEquals(expected, userDao.getGlobalStorageCurrent(),
                "global storage must count the ghost's known-size retained file PLUS the UNKNOWN_SIZE file's"
                        + " actual physical size from disk (not -1, not 0)");
    }

    /**
     * A global-quota reservation must respect a ghost's retained live bytes. A ghost owns an 800-byte
     * retained file; while the owner is ACTIVE those bytes are not in the sum (an active user is counted
     * by its counter, seeded 0 here), so a baseline taken then isolates the active total. Once the owner
     * is soft-deleted the retained file enters the global sum, consuming headroom: a reservation that
     * would fit against the active-only total is REJECTED because the ghost's bytes are counted; a smaller
     * one within the true remaining headroom succeeds.
     */
    @Test
    public void reservationRespectsGhostRetainedBytes() throws Exception {
        UserDao userDao = new UserDao();

        User uploader = createUser("ghostQuotaUploader");
        seedStorageCurrent(uploader.getId(), 0L);

        User ghost = createUser("ghostQuotaHolder");
        seedStorageCurrent(ghost.getId(), 0L);
        long retainedBytes = 800L;
        createKnownSizeFile(ghost, retainedBytes);

        // Owner still ACTIVE: counter 0 and active-user files are tracked by the counter, so its 800 bytes
        // are NOT yet in the sum. This is the active-only baseline.
        long activeBaseline = userDao.getGlobalStorageCurrent();

        // Soft-delete the owner: its retained live file now contributes its 800 bytes to the global sum.
        softDeleteUser(ghost.getId());
        Assertions.assertEquals(activeBaseline + retainedBytes, userDao.getGlobalStorageCurrent(),
                "the ghost's retained live file must enter the global sum once its owner is soft-deleted");

        // Global cap with 1000 bytes of headroom above the ACTIVE-only baseline. The ghost's 800 retained
        // bytes eat into that headroom, leaving only 200.
        long globalQuota = activeBaseline + 1_000L;

        // A 500-byte reservation would fit the 1000 active-only headroom but must be REJECTED, because the
        // ghost's 800 retained bytes are counted (only 200 remains). This is the property under test: if
        // ghost bytes were ignored, this reservation would wrongly succeed.
        Assertions.assertThrows(IOException.class,
                () -> FileUtil.reserveStorage(uploader.getId(), 500L, globalQuota),
                "a reservation must be rejected when the ghost's retained bytes leave insufficient global headroom");
        Assertions.assertEquals(Long.valueOf(0L), userDao.getById(uploader.getId()).getStorageCurrent(),
                "the rejected reservation must not change storageCurrent");

        // A 200-byte reservation fits the TRUE remaining headroom (1000 - 800) and succeeds.
        FileUtil.reserveStorage(uploader.getId(), 200L, globalQuota);
        Assertions.assertEquals(Long.valueOf(200L), userDao.getById(uploader.getId()).getStorageCurrent(),
                "a reservation within the true remaining global headroom (after the ghost's bytes) must succeed");
    }
}
