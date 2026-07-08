package com.sismics.docs.core.dao;

import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.model.jpa.PasswordRecovery;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;

/**
 * Password recovery DAO.
 *
 * @author jtremeaux
 */
public class PasswordRecoveryDao {
    /**
     * Number of random bytes in a recovery token. 16 bytes = 128 bits, rendered as a
     * 32-char hex string that fits the varchar(36) PWR_ID_C column. This mirrors the
     * SecureRandom-hex pattern used by ApiKeyResource.generateToken() (also 16 bytes).
     */
    private static final int TOKEN_RANDOM_BYTES = 16;

    /**
     * Cryptographically strong RNG for recovery tokens.
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Generate a random recovery token as a lowercase hex string, replacing the prior
     * UUID.randomUUID() (which draws on a non-cryptographic seed path in some JVMs and
     * exposes only ~122 bits).
     *
     * @return Recovery token
     */
    static String generateToken() {
        byte[] bytes = new byte[TOKEN_RANDOM_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Create a new password recovery request.
     *
     * @param passwordRecovery Password recovery
     * @return Unique identifier
     */
    public String create(PasswordRecovery passwordRecovery) {
        passwordRecovery.setId(generateToken());
        passwordRecovery.setCreateDate(new Date());

        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.persist(passwordRecovery);
        
        return passwordRecovery.getId();
    }
    
    /**
     * Search an active password recovery by unique identifier.
     * 
     * @param id Unique identifier
     * @return Password recovery
     */
    public PasswordRecovery getActiveById(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        try {
            Query q = em.createQuery("select r from PasswordRecovery r where r.id = :id and r.createDate > :createDateMin and r.deleteDate is null");
            q.setParameter("id", id);
            q.setParameter("createDateMin", Date.from(Instant.now().minus(Constants.PASSWORD_RECOVERY_EXPIRATION_HOUR, ChronoUnit.HOURS)));
            return (PasswordRecovery) q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }
    
    /**
     * Deletes active password recovery by username.
     * 
     * @param username Username
     */
    public void deleteActiveByLogin(String username) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("update PasswordRecovery r set r.deleteDate = :deleteDate where r.username = :username and r.createDate > :createDateMin and r.deleteDate is null");
        q.setParameter("username", username);
        q.setParameter("deleteDate", new Date());
        q.setParameter("createDateMin", Date.from(Instant.now().minus(Constants.PASSWORD_RECOVERY_EXPIRATION_HOUR, ChronoUnit.HOURS)));
        q.executeUpdate();
    }
}
