package com.sismics.docs.core.dao;

import com.sismics.BaseTest;
import com.sismics.docs.core.model.jpa.Group;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.UUID;

/**
 * #190 self-healing: {@link GroupDao#removeMember(String, String)} must soft-delete EVERY active row of a
 * (group, user) pair, not just one.
 *
 * <p>Databases upgraded from before migration 063 may still carry the duplicate active rows the old blind
 * insert produced. A single-row removal would report success while leaving the user a member — the visible
 * face of the defect. The duplicate is therefore reproduced for real: the 063 unique index is dropped, the
 * poisoned pair is inserted, {@code removeMember} runs, and the index is RECREATED from the shipped
 * migration's own DDL. That recreation is itself the proof — it can only succeed if no active duplicate
 * survived the removal.</p>
 *
 * <p>Runs on both H2 and PostgreSQL; the dialect-specific enforcement statement is read out of
 * {@code dbupdate-063-0.sql} so the test cannot drift from what ships.</p>
 */
public class TestGroupMembershipDuplicateRepair extends BaseTest {

    private String createUser(String prefix) {
        String[] out = new String[1];
        TransactionUtil.handle(() -> {
            User user = new User();
            user.setUsername(prefix + UUID.randomUUID());
            user.setPassword("12345678");
            user.setEmail("e@docs.com");
            user.setRoleId("admin");
            user.setStorageQuota(100_000L);
            try {
                out[0] = new UserDao().create(user, "admin");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return out[0];
    }

    private String createGroup() {
        String[] out = new String[1];
        TransactionUtil.handle(() -> {
            Group group = new Group();
            group.setName("g" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
            out[0] = new GroupDao().create(group, "admin");
        });
        return out[0];
    }

    private long activeMembershipCount(String groupId, String userId) {
        long[] out = new long[1];
        TransactionUtil.handle(() -> {
            Number n = (Number) ThreadLocalContext.get().getEntityManager()
                    .createNativeQuery("select count(*) from T_USER_GROUP where UGP_IDGROUP_C = :g and UGP_IDUSER_C = :u and UGP_DELETEDATE_D is null")
                    .setParameter("g", groupId).setParameter("u", userId).getSingleResult();
            out[0] = n.longValue();
        });
        return out[0];
    }

    private void execute(String sql) {
        TransactionUtil.handle(() -> ThreadLocalContext.get().getEntityManager()
                .createNativeQuery(sql).executeUpdate());
    }

    private boolean isPostgres() {
        boolean[] out = new boolean[1];
        TransactionUtil.handle(() -> {
            EntityManager em = ThreadLocalContext.get().getEntityManager();
            String product = em.unwrap(org.hibernate.Session.class)
                    .doReturningWork(conn -> conn.getMetaData().getDatabaseProductName());
            out[0] = product != null && product.toLowerCase().contains("postgres");
        });
        return out[0];
    }

    /**
     * The shipped 063 statement that creates the active-membership unique index, for the running dialect,
     * with its {@code !H2!}/{@code !PGSQL!} marker and trailing semicolon stripped.
     */
    private String activeIndexDdl(boolean postgres) throws Exception {
        String marker = postgres ? "!PGSQL!" : "!H2!";
        try (InputStream is = getClass().getResourceAsStream("/db/update/dbupdate-063-0.sql")) {
            Assertions.assertNotNull(is, "dbupdate-063-0.sql must be on the classpath");
            String script = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (String line : script.split("\n")) {
                if (line.startsWith(marker) && line.contains("create unique index")) {
                    String sql = line.substring(marker.length()).trim();
                    return sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
                }
            }
        }
        throw new AssertionError("no " + marker + " unique-index statement in dbupdate-063-0.sql");
    }

    @Test
    public void removeMemberClearsEveryDuplicateActiveRow() throws Exception {
        String userId = createUser("grp_dup_");
        String groupId = createGroup();
        boolean postgres = isPostgres();
        String recreateIndex = activeIndexDdl(postgres);

        // Reproduce a pre-063 database: two ACTIVE rows for one pair, which the index forbids.
        execute("drop index IDX_USER_GROUP_ACTIVE");
        try {
            execute("insert into T_USER_GROUP (UGP_ID_C, UGP_IDUSER_C, UGP_IDGROUP_C) values ('"
                    + UUID.randomUUID() + "','" + userId + "','" + groupId + "')");
            execute("insert into T_USER_GROUP (UGP_ID_C, UGP_IDUSER_C, UGP_IDGROUP_C) values ('"
                    + UUID.randomUUID() + "','" + userId + "','" + groupId + "')");
            Assertions.assertEquals(2, activeMembershipCount(groupId, userId),
                    "the fixture must carry the poisoned pair the defect produced");

            TransactionUtil.handle(() -> new GroupDao().removeMember(groupId, userId));

            Assertions.assertEquals(0, activeMembershipCount(groupId, userId),
                    "removeMember must clear EVERY active row of the pair, not just one");

            // Removing a non-member is a no-op, not an error (the endpoint's always-OK contract).
            TransactionUtil.handle(() -> new GroupDao().removeMember(groupId, userId));
            Assertions.assertEquals(0, activeMembershipCount(groupId, userId),
                    "removing a non-member must stay a no-op");
        } finally {
            // Best-effort: clear anything still active so the shared PostgreSQL schema can always get its
            // index back, then restore it. A genuine failure is carried by the assertions above.
            try {
                execute("update T_USER_GROUP set UGP_DELETEDATE_D = CURRENT_TIMESTAMP where UGP_IDGROUP_C = '"
                        + groupId + "' and UGP_IDUSER_C = '" + userId + "' and UGP_DELETEDATE_D is null");
            } catch (RuntimeException ignored) {
                // fall through to the index restore
            }
            execute(recreateIndex);
        }

        // The index is back — which it could only be if no active duplicate survived.
        Assertions.assertEquals(0, activeMembershipCount(groupId, userId),
                "the pair must be free of active rows after the repair");
    }
}
