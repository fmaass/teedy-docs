package com.sismics.docs.core.util;

import com.sismics.docs.core.dao.SavedFilterDao;
import com.sismics.docs.core.dao.SavedFilterExistsException;
import com.sismics.docs.core.model.jpa.SavedFilter;

/**
 * Saved filter operations that span more than one DAO call.
 *
 * <p>The REST layer must not reach into {@code com.sismics.docs.core.dao} for new work — the
 * frozen architecture rule ratchets that legacy dependency DOWN, never up — so the saved-filter
 * update operation lives here: the resource keeps validation, authentication and the HTTP status
 * mapping, and this util owns the whole persistence conversation behind one call.</p>
 */
public class SavedFilterUtil {
    private SavedFilterUtil() {
        // Utility class.
    }

    /**
     * Applies a name/query update to a saved filter owned by the given user, in the order the
     * contract requires: ownership FIRST (so a foreign id is answered as not-found and can never
     * be met with a name-collision error about the caller's own filter set), then the
     * case-insensitive duplicate precheck EXCLUDING the filter being updated (a no-op save and a
     * case-only self-rename must not collide with themselves), then the write.
     *
     * <p>The write itself stays in {@link SavedFilterDao#update}, which owns the entity-manager
     * boundary: it takes the manager first, loads through it, applies the mutation and flushes
     * inside the try/catch that translates the {@code (user, name)} unique violation. Splitting
     * that boundary across the util would re-open the raw-constraint-violation (500) hazard it
     * exists to close, so it is deliberately NOT moved here.</p>
     *
     * @param id Saved filter ID
     * @param userId Owner user ID (for authorization)
     * @param name New filter name
     * @param query New canonical URL query string
     * @return the updated saved filter, or null if no filter with this id is owned by the user
     * @throws SavedFilterExistsException if the name is already used by another filter of the user
     */
    public static SavedFilter update(String id, String userId, String name, String query)
            throws SavedFilterExistsException {
        SavedFilterDao dao = new SavedFilterDao();

        if (dao.getByIdAndUser(id, userId) == null) {
            return null;
        }

        for (SavedFilter existing : dao.getByUserId(userId)) {
            if (!existing.getId().equals(id) && existing.getName().equalsIgnoreCase(name)) {
                throw new SavedFilterExistsException();
            }
        }

        // The DB unique index (exact-case) remains the concurrency backstop behind this
        // precheck; the DAO translates it into the same exception.
        return dao.update(id, userId, name, query);
    }
}
