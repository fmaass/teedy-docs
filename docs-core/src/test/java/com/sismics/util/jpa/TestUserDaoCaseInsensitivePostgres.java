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

    /**
     * The active-username unique index (dbupdate-050) is the RACE BACKSTOP the app precheck cannot
     * cover: two concurrent local creations whose case-insensitive prechecks BOTH pass (each in its
     * own transaction, before either commits) still contend at the DB. {@link UserDao#create} forces
     * a flush and translates that DB unique-index violation to the SAME clean
     * {@code AlreadyExistingUsername} the precheck throws — so a racing local/LDAP creation surfaces
     * a clean validation error, never a raw 500 PersistenceException. This drives the race on real
     * PostgreSQL (case-sensitive {@code =}, so the constraint's lower() index is what fires).
     */
    @Test
    public void createTranslatesActiveUsernameConstraintRaceToAlreadyExistingUsername() throws Exception {
        // The request EM (from setUp) is txA. Its precheck for "racer" passes (no such user yet),
        // and it persists+flushes "racer" but does NOT commit — the row is not yet visible to others.
        EntityManager emA = ThreadLocalContext.get().getEntityManager();
        new UserDao().create(newUser("racer"), "admin");
        emA.flush();

        // txB: a SEPARATE EM/transaction. Its precheck ALSO passes (txA is uncommitted, so txB does
        // not see "racer"), then create() flushes and MUST hit the unique index and translate to
        // AlreadyExistingUsername rather than a raw PersistenceException. Run txB on its own thread
        // so its blocking flush (waiting on txA's uncommitted row lock) cannot deadlock this thread;
        // committing txA below releases the lock and lets txB observe the violation.
        final Exception[] thrown = new Exception[1];
        Thread txB = new Thread(() -> {
            EntityManager emB = emf.createEntityManager();
            EntityManager prev = ThreadLocalContext.get().getEntityManager();
            EntityTransaction tx = emB.getTransaction();
            try {
                ThreadLocalContext.get().setEntityManager(emB);
                tx.begin();
                new UserDao().create(newUser("RACER"), "admin"); // case-variant → same lower() key
                tx.commit();
            } catch (Exception e) {
                thrown[0] = e;
            } finally {
                if (tx.isActive()) tx.rollback();
                emB.close();
                ThreadLocalContext.get().setEntityManager(prev);
            }
        });
        txB.start();
        // Give txB a moment to reach its flush and block on txA's row lock, then commit txA so the
        // lock releases and txB sees the unique violation.
        Thread.sleep(500);
        emA.getTransaction().commit();
        txB.join(10_000);

        Assertions.assertNotNull(thrown[0], "the racing creation must fail, not silently create a duplicate");
        Assertions.assertEquals("AlreadyExistingUsername", thrown[0].getMessage(),
                "a DB active-username constraint race must surface as AlreadyExistingUsername, not a raw 500");

        // Re-open a transaction for the tearDown rollback (setUp's was committed above).
        emA.getTransaction().begin();
    }
}
