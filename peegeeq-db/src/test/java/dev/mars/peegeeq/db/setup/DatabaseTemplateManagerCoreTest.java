package dev.mars.peegeeq.db.setup;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for DatabaseTemplateManager using TestContainers.
 *
 * <p>These tests are tagged as CORE because they:
 * <ul>
 *   <li>Run fast (each test completes in <1 second)</li>
 *   <li>Are isolated (each test focuses on a single method)</li>
 *   <li>Test one component at a time (DatabaseTemplateManager only)</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 2.0
 */
@Tag(TestCategories.CORE)
@Execution(ExecutionMode.SAME_THREAD)  // Run tests sequentially to avoid database conflicts
public class DatabaseTemplateManagerCoreTest extends BaseIntegrationTest {

    private PgConnectionManager connectionManager;
    private Pool reactivePool;
    private DatabaseTemplateManager databaseTemplateManager;
    private PostgreSQLContainer postgres;

    @BeforeEach
    void setUp() throws Exception {
        // Create connection manager using the shared Vertx instance
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        // Get PostgreSQL container and create pool
        postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("public")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(3)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(2))
            .connectionTimeout(Duration.ofSeconds(5))
            .build();

        reactivePool = connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        // Create database template manager
        databaseTemplateManager = new DatabaseTemplateManager(manager.getVertx());
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.close()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    @Test
    void testDatabaseTemplateManagerCreation() {
        assertNotNull(databaseTemplateManager);
    }

    @Test
    void testDatabaseExists(VertxTestContext testContext) {
        // Check if the test database exists
        reactivePool.withConnection(connection ->
            databaseTemplateManager.databaseExists(connection, postgres.getDatabaseName())
        )
        .onComplete(testContext.succeeding(exists -> testContext.verify(() -> {
            assertTrue(exists);
            testContext.completeNow();
        })));
    }

    @Test
    void testDatabaseDoesNotExist(VertxTestContext testContext) {
        // Check if a non-existent database exists
        reactivePool.withConnection(connection ->
            databaseTemplateManager.databaseExists(connection, "non_existent_database_12345")
        )
        .onComplete(testContext.succeeding(exists -> testContext.verify(() -> {
            assertFalse(exists);
            testContext.completeNow();
        })));
    }

    @Test
    void testCreateDatabaseFromTemplate(VertxTestContext testContext) {
        String testDbName = "test_db_" + System.currentTimeMillis();

        databaseTemplateManager.createDatabaseFromTemplate(
            postgres.getHost(),
            postgres.getFirstMappedPort(),
            postgres.getUsername(),
            postgres.getPassword(),
            testDbName,
            "template0",
            "UTF8",
            new HashMap<>()
        )
        .compose(v -> reactivePool.withConnection(connection ->
            databaseTemplateManager.databaseExists(connection, testDbName)
        ))
        .onSuccess(exists -> testContext.verify(() -> {
            assertTrue(exists);
            // Clean up - drop the test database
            databaseTemplateManager.dropDatabaseFromAdmin(
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getUsername(),
                postgres.getPassword(),
                testDbName
            )
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
        }))
        .onFailure(cause -> {
            // Attempt cleanup on failure
            databaseTemplateManager.dropDatabaseFromAdmin(
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getUsername(),
                postgres.getPassword(),
                testDbName
            )
            .onSuccess(v -> testContext.failNow(cause))
            .onFailure(e -> testContext.failNow(cause));
        });
    }

    @Test
    void testDropDatabase(VertxTestContext testContext) {
        String testDbName = "test_db_drop_" + System.currentTimeMillis();

        // First create a database
        databaseTemplateManager.createDatabaseFromTemplate(
            postgres.getHost(),
            postgres.getFirstMappedPort(),
            postgres.getUsername(),
            postgres.getPassword(),
            testDbName,
            "template0",
            "UTF8",
            new HashMap<>()
        )
        // Verify it exists
        .compose(v -> reactivePool.withConnection(connection ->
            databaseTemplateManager.databaseExists(connection, testDbName)
        ))
        .onComplete(testContext.succeeding(existsBefore -> testContext.verify(() -> {
            assertTrue(existsBefore);
            // Drop the database
            reactivePool.withConnection(connection ->
                databaseTemplateManager.dropDatabase(connection, testDbName)
            )
            .compose(v -> reactivePool.withConnection(connection ->
                databaseTemplateManager.databaseExists(connection, testDbName)
            ))
            .onComplete(testContext.succeeding(existsAfter -> testContext.verify(() -> {
                assertFalse(existsAfter);
                testContext.completeNow();
            })));
        })));
    }

    @Test
    void testDropDatabaseFromAdmin(VertxTestContext testContext) {
        String testDbName = "test_db_drop_admin_" + System.currentTimeMillis();

        // First create a database
        databaseTemplateManager.createDatabaseFromTemplate(
            postgres.getHost(),
            postgres.getFirstMappedPort(),
            postgres.getUsername(),
            postgres.getPassword(),
            testDbName,
            "template0",
            "UTF8",
            new HashMap<>()
        )
        // Verify it exists
        .compose(v -> reactivePool.withConnection(connection ->
            databaseTemplateManager.databaseExists(connection, testDbName)
        ))
        .onComplete(testContext.succeeding(existsBefore -> testContext.verify(() -> {
            assertTrue(existsBefore);
            // Drop the database using admin method
            databaseTemplateManager.dropDatabaseFromAdmin(
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getUsername(),
                postgres.getPassword(),
                testDbName
            )
            .compose(v -> reactivePool.withConnection(connection ->
                databaseTemplateManager.databaseExists(connection, testDbName)
            ))
            .onComplete(testContext.succeeding(existsAfter -> testContext.verify(() -> {
                assertFalse(existsAfter);
                testContext.completeNow();
            })));
        })));
    }

    @Test
    void testCreateDatabaseFromTemplateWithNullTemplate(VertxTestContext testContext) {
        String testDbName = "test_db_null_template_" + System.currentTimeMillis();

        databaseTemplateManager.createDatabaseFromTemplate(
            postgres.getHost(),
            postgres.getFirstMappedPort(),
            postgres.getUsername(),
            postgres.getPassword(),
            testDbName,
            null,  // null template
            "UTF8",
            new HashMap<>()
        )
        // Verify database was created
        .compose(v -> reactivePool.withConnection(connection ->
            databaseTemplateManager.databaseExists(connection, testDbName)
        ))
        .onSuccess(exists -> testContext.verify(() -> {
            assertTrue(exists);
            // Clean up
            databaseTemplateManager.dropDatabaseFromAdmin(
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getUsername(),
                postgres.getPassword(),
                testDbName
            )
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
        }))
        .onFailure(cause -> {
            // Attempt cleanup on failure
            databaseTemplateManager.dropDatabaseFromAdmin(
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getUsername(),
                postgres.getPassword(),
                testDbName
            )
            .onSuccess(v -> testContext.failNow(cause))
            .onFailure(e -> testContext.failNow(cause));
        });
    }

    @Test
    void testCreateDatabaseFromTemplateWithNullEncoding(VertxTestContext testContext) {
        String testDbName = "test_db_null_encoding_" + System.currentTimeMillis();

        databaseTemplateManager.createDatabaseFromTemplate(
            postgres.getHost(),
            postgres.getFirstMappedPort(),
            postgres.getUsername(),
            postgres.getPassword(),
            testDbName,
            "template0",
            null,  // null encoding
            new HashMap<>()
        )
        // Verify database was created
        .compose(v -> reactivePool.withConnection(connection ->
            databaseTemplateManager.databaseExists(connection, testDbName)
        ))
        .onSuccess(exists -> testContext.verify(() -> {
            assertTrue(exists);
            // Clean up
            databaseTemplateManager.dropDatabaseFromAdmin(
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getUsername(),
                postgres.getPassword(),
                testDbName
            )
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
        }))
        .onFailure(cause -> {
            // Attempt cleanup on failure
            databaseTemplateManager.dropDatabaseFromAdmin(
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getUsername(),
                postgres.getPassword(),
                testDbName
            )
            .onSuccess(v -> testContext.failNow(cause))
            .onFailure(e -> testContext.failNow(cause));
        });
    }

    // ========================================
    // C1/H5 remediation: SQL injection prevention tests
    // ========================================

    @Test
    void testCreateDatabaseRejectsInjectionInDatabaseName() {
        assertThrows(IllegalArgumentException.class, () ->
            databaseTemplateManager.createDatabaseFromTemplate(
                postgres.getHost(), postgres.getFirstMappedPort(),
                postgres.getUsername(), postgres.getPassword(),
                "test; DROP TABLE users;--", "template0", "UTF8", new HashMap<>()
            )
        );
    }

    @Test
    void testCreateDatabaseRejectsInjectionInTemplateName() {
        assertThrows(IllegalArgumentException.class, () ->
            databaseTemplateManager.createDatabaseFromTemplate(
                postgres.getHost(), postgres.getFirstMappedPort(),
                postgres.getUsername(), postgres.getPassword(),
                "valid_db", "template0; DROP DATABASE postgres", "UTF8", new HashMap<>()
            )
        );
    }

    @Test
    void testCreateDatabaseRejectsInvalidEncoding() {
        assertThrows(IllegalArgumentException.class, () ->
            databaseTemplateManager.createDatabaseFromTemplate(
                postgres.getHost(), postgres.getFirstMappedPort(),
                postgres.getUsername(), postgres.getPassword(),
                "valid_db", "template0", "UTF8'; DROP TABLE x;--", new HashMap<>()
            )
        );
    }

    @Test
    void testDropDatabaseRejectsInjection(VertxTestContext testContext) {
        reactivePool.withConnection(connection ->
            databaseTemplateManager.dropDatabase(connection, "db; DROP TABLE users;--")
        )
        .onComplete(testContext.failing(cause -> testContext.verify(() -> {
            assertInstanceOf(IllegalArgumentException.class, cause);
            testContext.completeNow();
        })));
    }

    @Test
    void testCreateDatabaseAcceptsValidEncoding(VertxTestContext testContext) {
        String testDbName = "test_db_encoding_" + System.currentTimeMillis();
        
        databaseTemplateManager.createDatabaseFromTemplate(
            postgres.getHost(), postgres.getFirstMappedPort(),
            postgres.getUsername(), postgres.getPassword(),
            testDbName, "template0", "UTF8", new HashMap<>()
        )
        .compose(v -> reactivePool.withConnection(connection ->
            databaseTemplateManager.databaseExists(connection, testDbName)
        ))
        .onSuccess(exists -> testContext.verify(() -> {
            assertTrue(exists);
            databaseTemplateManager.dropDatabaseFromAdmin(
                postgres.getHost(), postgres.getFirstMappedPort(),
                postgres.getUsername(), postgres.getPassword(), testDbName
            )
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
        }))
        .onFailure(cause -> {
            databaseTemplateManager.dropDatabaseFromAdmin(
                postgres.getHost(), postgres.getFirstMappedPort(),
                postgres.getUsername(), postgres.getPassword(), testDbName
            )
            .onSuccess(v -> testContext.failNow(cause))
            .onFailure(e -> testContext.failNow(cause));
        });
    }
}
