package com.sismics.docs.core.util;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.TransactionBoundary.DurableState;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.jpa.EMF;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Single-transaction behavioral tests for {@link FileUtil#reserveStorage}: the per-user and global
 * limit checks (including overflow-safety), that a losing reservation performs no blob I/O, and that the
 * after-rollback blob-deletion registration fires on ROLLED_BACK but NOT on IN_DOUBT. The concurrency /
 * lost-update properties are covered by {@code TestQuotaConcurrency}.
 */
public class TestFileUtilReserve extends BaseTransactionalTest {

    private void seedStorageQuota(String userId, long quota) {
        ThreadLocalContext.get().getEntityManager()
                .createNativeQuery("update T_USER set USE_STORAGEQUOTA_N = :v where USE_ID_C = :id")
                .setParameter("v", quota)
                .setParameter("id", userId)
                .executeUpdate();
    }

    @Test
    public void reserveIncrementsOnce() throws Exception {
        User user = createUser("reserveOk");
        seedStorageCurrent(user.getId(), 1000L);

        FileUtil.reserveStorage(user.getId(), 500L, null);

        Assertions.assertEquals(Long.valueOf(1500L), new UserDao().getById(user.getId()).getStorageCurrent(),
                "a successful reservation increments storageCurrent exactly once");
    }

    @Test
    public void reservePerUserLimitRejectsAndLeavesCurrentUnchanged() throws Exception {
        User user = createUser("reserveLimit");
        // quota 100_000 (createUser default); seed near the ceiling so a 1000-byte reserve overshoots.
        seedStorageCurrent(user.getId(), 99_500L);

        Assertions.assertThrows(IOException.class,
                () -> FileUtil.reserveStorage(user.getId(), 1000L, null),
                "a reservation over the per-user quota must be rejected");
        Assertions.assertEquals(Long.valueOf(99_500L), new UserDao().getById(user.getId()).getStorageCurrent(),
                "a rejected reservation must not change storageCurrent");
    }

    @Test
    public void reserveIsOverflowSafe() throws Exception {
        User user = createUser("reserveOverflow");
        // quota and current near Long.MAX: current + fileSize would overflow to a NEGATIVE value and the
        // naive `current + fileSize > quota` check would WRONGLY accept. The overflow-safe form rejects.
        seedStorageQuota(user.getId(), Long.MAX_VALUE);
        seedStorageCurrent(user.getId(), Long.MAX_VALUE - 100L);

        Assertions.assertThrows(IOException.class,
                () -> FileUtil.reserveStorage(user.getId(), 200L, null),
                "a reservation that only fits via a long overflow must be rejected");
        Assertions.assertEquals(Long.valueOf(Long.MAX_VALUE - 100L),
                new UserDao().getById(user.getId()).getStorageCurrent(),
                "the overflow-rejected reservation must not change storageCurrent");
    }

    @Test
    public void reserveRejectsNegativeAndExtremeQuota() throws Exception {
        // A negative per-user quota (invalid, but a legacy/extreme DB value must not underflow the
        // comparison into wrongly ACCEPTING an upload) rejects.
        User negUser = createUser("reserveNegQuota");
        seedStorageQuota(negUser.getId(), -1L);
        seedStorageCurrent(negUser.getId(), 0L);
        Assertions.assertThrows(IOException.class,
                () -> FileUtil.reserveStorage(negUser.getId(), 100L, null),
                "a negative per-user quota must reject the upload, not underflow into acceptance");

        // Long.MIN_VALUE quota with a positive current: quota - current would wrap to a large positive and
        // wrongly accept a normal upload without the fail-closed guard.
        User extremeUser = createUser("reserveExtremeQuota");
        seedStorageQuota(extremeUser.getId(), Long.MIN_VALUE);
        seedStorageCurrent(extremeUser.getId(), 1000L);
        Assertions.assertThrows(IOException.class,
                () -> FileUtil.reserveStorage(extremeUser.getId(), 100L, null),
                "an extreme (Long.MIN_VALUE) quota must reject the upload, not underflow into acceptance");

        // A negative GLOBAL quota is invalid input and must also reject (defensive comparison guard).
        User globalUser = createUser("reserveNegGlobal");
        seedStorageCurrent(globalUser.getId(), 0L);
        Assertions.assertThrows(IOException.class,
                () -> FileUtil.reserveStorage(globalUser.getId(), 100L, -1L),
                "a negative global quota must reject the upload");
    }

    /**
     * A DOCS_GLOBAL_QUOTA that is UNSET means "no global limit", but one that is SET-BUT-INVALID
     * (non-numeric or negative) is a misconfiguration that must FAIL CLOSED — never be silently treated
     * as "no limit" (which would let uploads bypass the global cap). Exercised through the package-private
     * parse seam (the environment cannot be mutated in-process) plus an end-to-end reserve that must abort
     * rather than reserve.
     */
    @Test
    public void misconfiguredGlobalQuotaFailsClosed() throws Exception {
        // UNSET / empty -> legitimately no global limit.
        Assertions.assertNull(FileUtil.parseGlobalQuota(null), "unset means no global limit");
        Assertions.assertNull(FileUtil.parseGlobalQuota(""), "empty means no global limit");

        // SET-BUT-INVALID -> fail closed (throws), NOT null.
        Assertions.assertThrows(IOException.class, () -> FileUtil.parseGlobalQuota("not-a-number"),
                "a non-numeric DOCS_GLOBAL_QUOTA must fail closed, not silently disable the global cap");
        Assertions.assertThrows(IOException.class, () -> FileUtil.parseGlobalQuota("-1"),
                "a negative DOCS_GLOBAL_QUOTA must fail closed, not silently disable the global cap");

        // Valid value parses.
        Assertions.assertEquals(Long.valueOf(1000L), FileUtil.parseGlobalQuota("1000"));

        // End-to-end: an upload that would exceed a sane global cap must NOT be silently permitted when
        // the global-quota env is misconfigured — the reservation cannot even resolve the global quota,
        // so it aborts and reserves nothing.
        User user = createUser("globalMisconfig");
        seedStorageCurrent(user.getId(), 0L);
        Assertions.assertThrows(IOException.class,
                () -> FileUtil.reserveStorage(user.getId(), 10_000L, FileUtil.parseGlobalQuota("garbage")),
                "a misconfigured global quota must abort the reservation, not allow the upload");
        Assertions.assertEquals(Long.valueOf(0L), new UserDao().getById(user.getId()).getStorageCurrent(),
                "no bytes may be reserved when the global-quota config is invalid");
    }

    @Test
    public void reserveGlobalLimitRejects() throws Exception {
        User user = createUser("reserveGlobal");
        seedStorageCurrent(user.getId(), 0L);

        // Force a global limit with exactly 500 bytes of cross-user headroom.
        long globalQuota = new UserDao().getGlobalStorageCurrent() + 500L;

        Assertions.assertThrows(IOException.class,
                () -> FileUtil.reserveStorage(user.getId(), 600L, globalQuota),
                "a reservation over the global quota must be rejected");
        Assertions.assertEquals(Long.valueOf(0L), new UserDao().getById(user.getId()).getStorageCurrent(),
                "a globally-rejected reservation must not change storageCurrent");

        // A reservation within the headroom succeeds.
        FileUtil.reserveStorage(user.getId(), 500L, globalQuota);
        Assertions.assertEquals(Long.valueOf(500L), new UserDao().getById(user.getId()).getStorageCurrent(),
                "a reservation within the global headroom must succeed");
    }

    @Test
    public void losingReservationDoesNoBlobIo() throws Exception {
        User user = createUser("reserveNoBlob");
        seedStorageCurrent(user.getId(), 0L);

        Path temp = Files.createTempFile("reserve-noblob-", ".jpg");
        try (InputStream in = getSystemResourceAsStream(FILE_JPG)) {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        long before = countStorageFiles();
        try {
            // Declared size far exceeds the 100_000 quota, so reserveStorage rejects BEFORE any row is
            // created or any blob byte is written.
            Assertions.assertThrows(IOException.class, () ->
                    FileUtil.createFile("apollo.jpg", null, temp, 1_000_000L, null, user.getId(), null),
                    "an over-quota upload must be rejected");
            Assertions.assertEquals(Long.valueOf(0L), new UserDao().getById(user.getId()).getStorageCurrent(),
                    "a losing reservation must not change storageCurrent");
            Assertions.assertEquals(before, countStorageFiles(),
                    "a losing reservation must write no blob to the storage directory");
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * The after-rollback blob deletion (registered via the completion registry) fires on ROLLED_BACK but
     * NOT on IN_DOUBT — an in-doubt transaction may actually have committed, so its blob must be left for
     * the clean_storage sweep, not deleted. Exercised at the registry/dispatcher level createFile relies on.
     */
    @Test
    public void afterRollbackBlobDeletionFiresOnRollbackNotInDoubt() throws Exception {
        Assertions.assertFalse(runBlobDeletionForOutcome(DurableState.IN_DOUBT),
                "an IN_DOUBT outcome must NOT delete the blob (it may have committed)");
        Assertions.assertTrue(runBlobDeletionForOutcome(DurableState.ROLLED_BACK),
                "a ROLLED_BACK outcome must delete the orphaned blob");
    }

    /**
     * Creates a blob file, registers its after-rollback deletion on a FRESH frame, dispatches the given
     * durable outcome, and returns whether the blob was deleted.
     */
    private boolean runBlobDeletionForOutcome(DurableState state) throws IOException {
        Path blob = Files.createTempFile("orphan-blob-", ".bin");
        Files.write(blob, "ENCRYPTED".getBytes());
        ThreadLocalContext context = ThreadLocalContext.get();
        EntityManager frameEm = EMF.get().createEntityManager();
        context.pushFrame(frameEm);
        try {
            context.getCompletionRegistry().registerAfterRollback(() -> {
                try {
                    Files.deleteIfExists(blob);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            TransactionBoundary.complete(state);
        } finally {
            context.popFrame();
            if (frameEm.isOpen()) {
                frameEm.close();
            }
        }
        boolean deleted = !Files.exists(blob);
        Files.deleteIfExists(blob);
        return deleted;
    }

    private static long countStorageFiles() throws IOException {
        Path dir = DirectoryUtil.getStorageDirectory();
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            return s.count();
        }
    }
}
