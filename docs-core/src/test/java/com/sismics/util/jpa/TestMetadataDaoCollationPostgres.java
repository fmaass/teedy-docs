package com.sismics.util.jpa;

import com.sismics.docs.core.constant.MetadataType;
import com.sismics.docs.core.dao.MetadataDao;
import com.sismics.docs.core.dao.criteria.MetadataCriteria;
import com.sismics.docs.core.dao.dto.MetadataDto;
import com.sismics.docs.core.model.jpa.Metadata;
import com.sismics.docs.core.util.jpa.SortCriteria;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Regression test for #291 (follow-up): the metadata definition list must come back in
 * alphabetical order whatever the case of the first letter, on every supported collation.
 *
 * <p>{@code GET /metadata} defaults to the definition name, but the name column was ordered raw.
 * {@code ORDER BY} on a raw text column is collation-dependent: with the C/POSIX collation
 * PostgreSQL compares byte by byte, so every upper-case name sorts before every lower-case one and
 * a definition set of {@code alpha}, {@code Beta}, {@code zulu} lists as {@code Beta, alpha, zulu}.
 *
 * <p>Neither of the two databases the suite normally runs against can show this:
 * <ul>
 *   <li>H2 runs {@code SET IGNORECASE TRUE} (dbupdate-000-0.sql), so its string comparison already
 *       folds case — the same reason {@link TestUserDaoCaseInsensitivePostgres} needs a real
 *       PostgreSQL server;</li>
 *   <li>the CI PostgreSQL container uses the image default {@code en_US.utf8} collation, whose
 *       primary comparison level ignores case.</li>
 * </ul>
 * This test therefore boots PostgreSQL with a C collation, the configuration a user reported the
 * scrambled order on, and asserts the ordering contract against it.
 *
 * <p>Skipped automatically when Docker is unavailable (mirrors TestPostgresMigration).
 */
@Testcontainers(disabledWithoutDocker = true)
public class TestMetadataDaoCollationPostgres {
    /**
     * Boots the cluster with the C collation: byte-order string comparison, the case-sensitive
     * behaviour of the reporting deployment.
     */
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8 --lc-collate=C --lc-ctype=C");

    /**
     * Definitions created by this test, in a deliberately non-alphabetical insertion order and a
     * deliberately mixed case: the case-sensitive and the case-insensitive orders of this set
     * differ, which is what makes the assertions below discriminating.
     */
    private static final List<String> NAMES = List.of("Zulu291", "alpha291", "Mike291", "bravo291");

    /**
     * The order the C collation produces for {@link #NAMES}: upper case first, byte by byte.
     */
    private static final List<String> CASE_SENSITIVE_ORDER =
            List.of("Mike291", "Zulu291", "alpha291", "bravo291");

    /**
     * The order a user expects, and the one the default listing must produce.
     */
    private static final List<String> CASE_INSENSITIVE_ORDER =
            List.of("alpha291", "bravo291", "Mike291", "Zulu291");

    private static EntityManagerFactory emf;

    @BeforeAll
    public static void bootSchema() throws Exception {
        // Run the real migrations against the container, then build a dedicated EMF pointed at it.
        // This is independent of the process-wide H2 EMF singleton.
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            // Positive control on the fixture itself: without a C collation the assertions below
            // would pass for the wrong reason, because en_US.utf8 already folds case.
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "select datcollate from pg_database where datname = current_database()")) {
                Assertions.assertTrue(resultSet.next(), "the current database must be readable");
                Assertions.assertEquals("C", resultSet.getString(1),
                        "this test only reproduces #291 on a C-collation database");
            }

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
            // Rolled back, so the definitions never outlive the test.
            em.getTransaction().rollback();
        }
        ThreadLocalContext.get().setEntityManager(null);
    }

    private List<String> namesOf(List<MetadataDto> dtoList) {
        List<String> names = new ArrayList<>();
        for (MetadataDto dto : dtoList) {
            names.add(dto.getName());
        }
        return names;
    }

    @Test
    public void testDefaultOrderIsCaseInsensitive() {
        MetadataDao metadataDao = new MetadataDao();
        for (String name : NAMES) {
            metadataDao.create(new Metadata().setName(name).setType(MetadataType.STRING), "admin");
        }
        // The listing runs a native query, so the pending inserts must be in the database first.
        ThreadLocalContext.get().getEntityManager().flush();

        // Positive control: this database really does compare names byte by byte. An explicit
        // sort_column=1 asks for the raw name column and keeps that behaviour.
        Assertions.assertEquals(CASE_SENSITIVE_ORDER,
                namesOf(metadataDao.findByCriteria(new MetadataCriteria(), new SortCriteria(1, true))),
                "sort_column=1 orders on the raw name column, which the C collation compares byte by byte");

        // The contract: with no sort column requested the definitions are alphabetical whatever the
        // case of the first letter (#291).
        Assertions.assertEquals(CASE_INSENSITIVE_ORDER,
                namesOf(metadataDao.findByCriteria(new MetadataCriteria(), null)),
                "the default definition listing must be alphabetical no matter if the first letter is capitalized");
    }
}
