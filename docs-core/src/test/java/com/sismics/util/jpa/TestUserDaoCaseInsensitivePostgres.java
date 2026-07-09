package com.sismics.util.jpa;

import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.context.ThreadLocalContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;
import java.util.UUID;

/**
 * Regression test for BL-020: the LDAP account-hijack guard keyed on an exact-match
 * username lookup ({@code where u.username = :username}) is defeated on PostgreSQL,
 * whose {@code =} is case-sensitive. With a local {@code admin}, an LDAP directory
 * entry whose uid case-folds to {@code admin} (e.g. {@code ADMIN}) was NOT matched by
 * the exact-match guard, so the provisioning path created a SECOND, LDAP-origin
 * {@code ADMIN} account — a shadow of the local admin.
 *
 * <p>The default H2 test database has {@code IGNORECASE=TRUE}, so this bug is
 * structurally invisible there: {@code =} already case-folds. This test therefore runs
 * against a real PostgreSQL server (testcontainers) to reproduce the production
 * behaviour, and asserts the DAO-level provisioning-boundary contract directly:
 * <ul>
 *   <li>{@link UserDao#getActiveByUsernameIgnoreCase} finds a case-variant existing
 *       user (this is what the LDAP handler now uses before provisioning), and</li>
 *   <li>{@link UserDao#create} refuses to create a case-variant duplicate, so no other
 *       caller can slip a shadow account past the guard.</li>
 * </ul>
 *
 * <p>Skipped automatically when Docker is unavailable (mirrors TestPostgresMigration).
 */
@Testcontainers(disabledWithoutDocker = true)
public class TestUserDaoCaseInsensitivePostgres {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    private static EntityManagerFactory emf;

    @BeforeAll
    public static void bootSchema() throws Exception {
        // Run the real migrations against the container, then build a dedicated EMF
        // pointed at it. This is independent of the process-wide H2 EMF singleton.
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setAutoCommit(false);
            DbOpenHelper helper = new DbOpenHelper(connection) {
                @Override
                public void onCreate() throws Exception {
                    executeAllScript(0);
                }

                @Override
                public void onUpgrade(int oldVersion, int newVersion) throws Exception {
                    for (int version = oldVersion + 1; version <= newVersion; version++) {
                        executeAllScript(version);
                    }
                }
            };
            helper.open();
            Assertions.assertTrue(helper.getExceptions().isEmpty(),
                    "migrations must run cleanly on Postgres before the test");
            connection.commit();
        }

        Properties props = new Properties();
        props.put("hibernate.connection.driver_class", "org.postgresql.Driver");
        props.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        props.put("hibernate.connection.url", POSTGRES.getJdbcUrl());
        props.put("hibernate.connection.username", POSTGRES.getUsername());
        props.put("hibernate.connection.password", POSTGRES.getPassword());
        props.put("hibernate.hbm2ddl.auto", "");
        props.put("hibernate.show_sql", "false");
        emf = Persistence.createEntityManagerFactory("transactions-optional", props);
    }

    @AfterAll
    public static void closeEmf() {
        if (emf != null) {
            emf.close();
        }
    }

    @BeforeEach
    public void setUp() {
        EntityManager em = emf.createEntityManager();
        ThreadLocalContext context = ThreadLocalContext.get();
        context.setEntityManager(em);
        EntityTransaction tx = em.getTransaction();
        tx.begin();
    }

    @AfterEach
    public void tearDown() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        if (em != null && em.getTransaction().isActive()) {
            em.getTransaction().rollback();
        }
        ThreadLocalContext.get().setEntityManager(null);
    }

    private User newUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(UUID.randomUUID().toString());
        user.setEmail(username + "@example.com");
        user.setRoleId(Constants.DEFAULT_USER_ROLE);
        user.setStorageQuota(100_000L);
        return user;
    }

    /**
     * A case-insensitive lookup must find the local {@code admin} (seeded by
     * dbupdate-000) when queried for {@code ADMIN}. This is the query the LDAP handler
     * now performs before provisioning, so the hijack guard fires on PostgreSQL exactly
     * as it does on H2.
     */
    @Test
    public void caseInsensitiveLookupFindsExistingLocalAdmin() {
        UserDao userDao = new UserDao();

        // The seeded local 'admin' exists.
        Assertions.assertNotNull(userDao.getActiveByUsername("admin"), "seed admin must exist");

        // Exact-match lookup is case-sensitive on Postgres: it does NOT find "admin" for "ADMIN".
        Assertions.assertNull(userDao.getActiveByUsername("ADMIN"),
                "sanity: exact-match lookup is case-sensitive on Postgres");

        // The new case-insensitive lookup MUST find the existing local admin — this is
        // what stops the LDAP handler from provisioning a shadow 'ADMIN'.
        User found = userDao.getActiveByUsernameIgnoreCase("ADMIN");
        Assertions.assertNotNull(found,
                "case-insensitive lookup must find the local 'admin' when queried for 'ADMIN'");
        Assertions.assertEquals("admin", found.getUsername());
    }

    /**
     * The defensive duplicate check in create() must refuse a case-variant duplicate,
     * so no caller (including a future one) can create a shadow of an existing account on
     * PostgreSQL. Pre-fix, create()'s unicity check is an exact {@code =} match, so on
     * Postgres it does NOT see the seeded 'admin' and creates a second 'ADMIN' — RED.
     */
    @Test
    public void createRefusesCaseVariantDuplicate() {
        UserDao userDao = new UserDao();

        Exception ex = Assertions.assertThrows(Exception.class,
                () -> userDao.create(newUser("ADMIN"), "admin"),
                "creating an 'ADMIN' account while the seeded 'admin' exists must be refused on Postgres");
        Assertions.assertEquals("AlreadyExistingUsername", ex.getMessage());
    }
}
