package com.sismics.docs.core.dao;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.model.jpa.AuthenticationToken;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the explicit token-revocation primitives introduced for the recovery/rotation matrix.
 */
public class TestAuthenticationTokenDao extends BaseTransactionalTest {

    private String createToken(AuthenticationTokenDao dao, String userId) {
        return dao.create(new AuthenticationToken().setUserId(userId).setLongLasted(false));
    }

    @Test
    public void deleteAllByUserIdRevokesEveryToken() throws Exception {
        User user = createUser("tok_all");
        AuthenticationTokenDao dao = new AuthenticationTokenDao();
        createToken(dao, user.getId());
        createToken(dao, user.getId());
        createToken(dao, user.getId());
        ThreadLocalContext.get().getEntityManager().flush();

        int deleted = dao.deleteAllByUserId(user.getId());
        Assertions.assertEquals(3, deleted);
        Assertions.assertTrue(dao.getByUserId(user.getId()).isEmpty());
    }

    @Test
    public void deleteAllExceptTokenKeepsOnlyTheCurrentSession() throws Exception {
        User user = createUser("tok_keep");
        AuthenticationTokenDao dao = new AuthenticationTokenDao();
        String keep = createToken(dao, user.getId());
        createToken(dao, user.getId());
        createToken(dao, user.getId());
        ThreadLocalContext.get().getEntityManager().flush();

        int deleted = dao.deleteAllExceptToken(user.getId(), keep);
        Assertions.assertEquals(2, deleted);
        Assertions.assertEquals(1, dao.getByUserId(user.getId()).size());
        Assertions.assertNotNull(dao.get(keep));
    }

    @Test
    public void deleteAllExceptTokenRejectsNullKeepId() throws Exception {
        User user = createUser("tok_null");
        AuthenticationTokenDao dao = new AuthenticationTokenDao();
        createToken(dao, user.getId());
        ThreadLocalContext.get().getEntityManager().flush();

        // A null keepId would delete nothing (a.id != null matches no rows) — the opposite of the intent.
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> dao.deleteAllExceptToken(user.getId(), null));
    }
}
