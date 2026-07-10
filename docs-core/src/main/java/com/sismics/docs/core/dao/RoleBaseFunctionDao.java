package com.sismics.docs.core.dao;

import com.google.common.collect.Sets;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.Set;

/**
 * Role base functions DAO.
 * 
 * @author jtremeaux
 */
public class RoleBaseFunctionDao {
    /**
     * Find the set of base functions of a role.
     * 
     * @param roleIdSet Set of role ID
     * @return Set of base functions
     */
    @SuppressWarnings("unchecked")
    public Set<String> findByRoleId(Set<String> roleIdSet) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        StringBuilder sb = new StringBuilder("select rbf.RBF_IDBASEFUNCTION_C from T_ROLE_BASE_FUNCTION rbf, T_ROLE r");
        sb.append(" where rbf.RBF_IDROLE_C in (:roleIdSet) and rbf.RBF_DELETEDATE_D is null");
        sb.append(" and r.ROL_ID_C = rbf.RBF_IDROLE_C and r.ROL_DELETEDATE_D is null");
        Query q = em.createNativeQuery(sb.toString());
        q.setParameter("roleIdSet", roleIdSet);
        return Sets.newHashSet(q.getResultList());
    }

    /**
     * Returns the subset of role IDs whose role grants the given base function.
     * Lets a caller resolve a per-role flag (e.g. ADMIN) for many roles in a
     * single query instead of one query per role.
     *
     * @param baseFunction Base function name
     * @param roleIdSet Set of role ID
     * @return Subset of roleIdSet whose role grants the base function
     */
    @SuppressWarnings("unchecked")
    public Set<String> getRoleIdsWithBaseFunction(String baseFunction, Set<String> roleIdSet) {
        if (roleIdSet == null || roleIdSet.isEmpty()) {
            return Sets.newHashSet();
        }
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        StringBuilder sb = new StringBuilder("select distinct rbf.RBF_IDROLE_C from T_ROLE_BASE_FUNCTION rbf, T_ROLE r");
        sb.append(" where rbf.RBF_IDROLE_C in (:roleIdSet) and rbf.RBF_IDBASEFUNCTION_C = :baseFunction");
        sb.append(" and rbf.RBF_DELETEDATE_D is null");
        sb.append(" and r.ROL_ID_C = rbf.RBF_IDROLE_C and r.ROL_DELETEDATE_D is null");
        Query q = em.createNativeQuery(sb.toString());
        q.setParameter("roleIdSet", roleIdSet);
        q.setParameter("baseFunction", baseFunction);
        return Sets.newHashSet(q.getResultList());
    }
}
