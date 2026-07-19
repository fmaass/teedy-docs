package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;

/**
 * Tests the active-user email lookup contract used for inbox sender attribution.
 */
public class TestUserDaoEmailLookup extends BaseTransactionalTest {

    private User createUser(String username, String email, Date deleteDate) throws Exception {
        User user = new User();
        user.setUsername(username);
        user.setPassword("12345678");
        user.setEmail(email);
        user.setRoleId(Constants.DEFAULT_USER_ROLE);
        user.setStorageQuota(100_000L);
        user.setDeleteDate(deleteDate);
        new UserDao().create(user, username);
        return user;
    }

    @Test
    public void multipleActiveMatchesReturnNull() throws Exception {
        User first = createUser("email_lookup_duplicate_first", "duplicate@example.com", null);
        User second = createUser("email_lookup_duplicate_second", "duplicate@example.com", null);
        ThreadLocalContext.get().getEntityManager().flush();

        Assertions.assertEquals(first.getEmail(), second.getEmail());
        Assertions.assertNull(new UserDao().getByEmail(first.getEmail()),
                "an ambiguous active email must not select an arbitrary user");
    }

    @Test
    public void softDeletedMatchDoesNotMakeActiveMatchAmbiguous() throws Exception {
        User active = createUser("email_lookup_active", "active-and-deleted@example.com", null);
        createUser("email_lookup_deleted", "active-and-deleted@example.com", new Date());
        ThreadLocalContext.get().getEntityManager().flush();

        User found = new UserDao().getByEmail(active.getEmail());
        Assertions.assertNotNull(found);
        Assertions.assertEquals(active.getId(), found.getId());
    }

    @Test
    public void singleActiveMatchIsReturned() throws Exception {
        User active = createUser("email_lookup_single", "single-active@example.com", null);
        ThreadLocalContext.get().getEntityManager().flush();

        User found = new UserDao().getByEmail(active.getEmail());
        Assertions.assertNotNull(found);
        Assertions.assertEquals(active.getId(), found.getId());
    }

    @Test
    public void noMatchReturnsNull() {
        Assertions.assertNull(new UserDao().getByEmail("missing-user@example.com"));
    }
}
