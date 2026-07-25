package com.sismics.docs.core.util;

import com.sismics.docs.BaseTransactionalTest;
import com.sismics.docs.core.dao.SavedFilterDao;
import com.sismics.docs.core.dao.SavedFilterExistsException;
import com.sismics.docs.core.model.jpa.SavedFilter;
import com.sismics.docs.core.model.jpa.User;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the saved-filter update seam the REST layer calls.
 *
 * <p>The ORDER of its two guards is the contract: ownership is resolved BEFORE the duplicate
 * precheck, so a foreign id is always answered as not-found even when the caller happens to own a
 * filter with the requested name — otherwise the update path would answer a foreign id with a
 * name-collision error, a different answer than an unknown id gets.</p>
 */
public class TestSavedFilterUtil extends BaseTransactionalTest {
    private SavedFilter filter(String userId, String name, String query) {
        SavedFilter f = new SavedFilter();
        f.setUserId(userId);
        f.setName(name);
        f.setQuery(query);
        return f;
    }

    @Test
    public void updatesNameAndQuery() throws Exception {
        User user = createUser("sflu_happy");
        SavedFilterDao dao = new SavedFilterDao();
        String id = dao.create(filter(user.getId(), "Before", "search=before"));

        SavedFilter updated = SavedFilterUtil.update(id, user.getId(), "After", "search=after&mode=or");
        Assertions.assertNotNull(updated);
        Assertions.assertEquals("After", updated.getName());
        Assertions.assertEquals("search=after&mode=or", updated.getQuery());
        Assertions.assertEquals("After", dao.getByIdAndUser(id, user.getId()).getName());
    }

    @Test
    public void unknownOrForeignIdReportsNotFound() throws Exception {
        User alice = createUser("sflu_alice");
        User bob = createUser("sflu_bob");
        SavedFilterDao dao = new SavedFilterDao();
        String aliceId = dao.create(filter(alice.getId(), "Alice filter", "search=a"));

        Assertions.assertNull(SavedFilterUtil.update("no-such-id", alice.getId(), "Ghost", "search=b"));
        Assertions.assertNull(SavedFilterUtil.update(aliceId, bob.getId(), "Stolen", "search=b"));
        Assertions.assertEquals("Alice filter", dao.getByIdAndUser(aliceId, alice.getId()).getName(),
                "a rejected update must not have touched the row");
    }

    @Test
    public void ownershipIsResolvedBeforeTheDuplicatePrecheck() throws Exception {
        User alice = createUser("sflu_order_alice");
        User bob = createUser("sflu_order_bob");
        SavedFilterDao dao = new SavedFilterDao();
        String aliceId = dao.create(filter(alice.getId(), "Shared name", "search=a"));
        // Bob owns a filter with the SAME name, so the duplicate precheck WOULD fire — the
        // ownership guard must win and report not-found instead.
        dao.create(filter(bob.getId(), "Shared name", "search=b"));

        Assertions.assertNull(SavedFilterUtil.update(aliceId, bob.getId(), "Shared name", "search=c"),
                "a foreign id must be not-found, never a duplicate-name error");
    }

    @Test
    public void duplicateNameIsRejectedButSelfIsNot() throws Exception {
        User user = createUser("sflu_dup");
        SavedFilterDao dao = new SavedFilterDao();
        String keptId = dao.create(filter(user.getId(), "Invoices", "search=a"));
        String movedId = dao.create(filter(user.getId(), "Drafts", "search=b"));

        Assertions.assertThrows(SavedFilterExistsException.class,
                () -> SavedFilterUtil.update(movedId, user.getId(), "Invoices", "search=b"));
        Assertions.assertThrows(SavedFilterExistsException.class,
                () -> SavedFilterUtil.update(movedId, user.getId(), "INVOICES", "search=b"),
                "the precheck is case-insensitive");

        // Re-saving under its OWN name is the overwrite flow, not a duplicate.
        Assertions.assertNotNull(SavedFilterUtil.update(movedId, user.getId(), "Drafts", "search=c"));
        // A case-only self-rename is allowed (the DB index is exact-case).
        Assertions.assertNotNull(SavedFilterUtil.update(movedId, user.getId(), "DRAFTS", "search=c"));

        Assertions.assertEquals("Invoices", dao.getByIdAndUser(keptId, user.getId()).getName());
        Assertions.assertEquals("DRAFTS", dao.getByIdAndUser(movedId, user.getId()).getName());
    }

    @Test
    public void anotherUsersNameIsNotADuplicate() throws Exception {
        User alice = createUser("sflu_scope_alice");
        User bob = createUser("sflu_scope_bob");
        SavedFilterDao dao = new SavedFilterDao();
        dao.create(filter(alice.getId(), "Invoices", "search=a"));
        String bobId = dao.create(filter(bob.getId(), "Drafts", "search=b"));

        // The precheck is scoped to the caller's own filters.
        Assertions.assertNotNull(SavedFilterUtil.update(bobId, bob.getId(), "Invoices", "search=b"));
    }
}
