package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

/**
 * Tests the batched user-id -> username resolution used to attach a file's creator to the file-list
 * responses without a per-file lookup.
 */
public class TestUserDaoUsernameLookup extends BaseTransactionalTest {

    private User createUser(String username, Date deleteDate) throws Exception {
        User user = new User();
        user.setUsername(username);
        user.setPassword("12345678");
        user.setEmail(username + "@example.com");
        user.setRoleId(Constants.DEFAULT_USER_ROLE);
        user.setStorageQuota(100_000L);
        user.setDeleteDate(deleteDate);
        new UserDao().create(user, username);
        return user;
    }

    @Test
    public void resolvesEveryRequestedIdInOneMap() throws Exception {
        User a = createUser("username_lookup_a", null);
        User b = createUser("username_lookup_b", null);
        ThreadLocalContext.get().getEntityManager().flush();

        Map<String, String> resolved = new UserDao().getUsernamesByIds(Arrays.asList(a.getId(), b.getId()));
        Assertions.assertEquals(2, resolved.size());
        Assertions.assertEquals("username_lookup_a", resolved.get(a.getId()));
        Assertions.assertEquals("username_lookup_b", resolved.get(b.getId()));
    }

    @Test
    public void softDeletedUserStillResolves() throws Exception {
        // A file's creator must stay displayable after its uploader is removed, matching the
        // document-creator join which does not filter on delete date.
        User deleted = createUser("username_lookup_deleted", new Date());
        ThreadLocalContext.get().getEntityManager().flush();

        Map<String, String> resolved = new UserDao().getUsernamesByIds(Collections.singletonList(deleted.getId()));
        Assertions.assertEquals("username_lookup_deleted", resolved.get(deleted.getId()));
    }

    @Test
    public void unknownIdIsAbsentFromTheMap() throws Exception {
        User a = createUser("username_lookup_present", null);
        ThreadLocalContext.get().getEntityManager().flush();

        Map<String, String> resolved = new UserDao().getUsernamesByIds(Arrays.asList(a.getId(), "no-such-user-id"));
        Assertions.assertEquals(1, resolved.size());
        Assertions.assertEquals("username_lookup_present", resolved.get(a.getId()));
        Assertions.assertFalse(resolved.containsKey("no-such-user-id"));
    }

    @Test
    public void emptyInputReturnsEmptyMap() {
        Assertions.assertTrue(new UserDao().getUsernamesByIds(Collections.emptyList()).isEmpty());
        Assertions.assertTrue(new UserDao().getUsernamesByIds(null).isEmpty());
    }
}
