package dev.mars.peegeeq.db.setup;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.HashMap;
import java.util.Map;

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
 * @version 1.0
 */
@Tag(TestCategories.CORE)
@Execution(ExecutionMode.SAME_THREAD)  // Run tests sequentially to avoid database conflicts
public class DatabaseTemplateManagerCoreTest extends BaseIntegrationTest {

    private PgConnectionManager connectionManager;
    private Pool reactivePool;
    private DatabaseTemplateManager databaseTemplateManager;
    private PostgreSQLContainer<?> postgres;

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
            .maxSize(10)
            .build();

        reactivePool = connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        // Create database template manager
        databaseTemplateManager = new DatabaseTemplateManager(manager.getVertx());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    @Test
    void testDatabaseTemplateManagerCreation() {
        assertNotNull(databaseTemplateManager);
    }

    @Test
    void testDatabaseExists() throws Exception {
        // Check if the test database exists
        Boolean exists = reactivePool.withConnection(connection ->
            databaseTemplateManager.databaseExists(connection, postgres.getDatabaseName())
        ).toCompletionStage().toCompletableFuture().get();

        assertTrue(exists);
    }

    @Test
    void testDatabaseDoesNotExist() throws Exception {
        // Check if a non-existent database exists
        Boolean exists = reactivePool.withConnection(connection ->
            databaseTemplateManager.databaseExists(connection, "non_existent_database_12345")
        ).toCompletionStage().toCompletableFuture().get();

        assertFalse(exists);
    }

    @Test
    void testCreateDatabaseFromTemplate() throws Exception {
        String testDbName = "test_db_" + System.currentTimeMillis();

        try {
            // Create database from template
            databaseTemplateManager.createDatabaseFromTemplate(
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getUsername(),
                postgres.getPassword(),
                testDbName,
                "template0",
                "UTF8",
                new HashMap<>()
            ).toCompletionStage().toCompletableFuture().get();

            // Verify database was created
            Boolean exists = reactivePool.withConnection(connection ->
                databaseTemplateManager.databaseExists(connection, testDbName)
            ).toCompletionStage().toCompletableFuture().get();

            assertTrue(exists);
        } finally {
            // Clean up - drop the test database
            try {
                databaseTemplateManager.dropDatabaseFromAdmin(
                    postgres.getHost(),
                    postgres.getFirstMappedPort(),
                    postgres.getUsername(),
                    postgres.getPassword(),
                    testDbName
                ).toCompletionStage().toCompletableFuture().get();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
    }

    @Test
    void testDropDatabase() throws Exception {
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
        ).toCompletionStage().toCompletableFuture().get();

        // Verify it exists
        Boolean existsBefore = reactivePool.withConnection(connection ->
            databaseTemplateManager.databaseExists(connection, testDbName)
        ).toCompletionStage().toCompletableFuture().get();
        assertTrue(existsBefore);

        // Drop the database
        reactivePool.withConnection(connection ->
            databaseTemplateManager.dropDatabase(connection, testDbName)
        ).toCompletionStage().toCompletableFuture().get();

        // Verify it no longer exists
        Boolean existsAfter = reactivePool.withConnection(connection ->
            databaseTemplateManager.databaseExists(connection, testDbName)
        ).toCompletionStage().toCompletableFuture().get();
        assertFalse(existsAfter);
    }

    @Test
    void testDropDatabaseFromAdmin() throws Exception {
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
        ).toCompletionStage().toCompletableFuture().get();

        // Verify it exists
        Boolean existsBefore = reactivePool.withConnection(connection ->
            databaseTemplateManager.databaseExists(connection, testDbName)
        ).toCompletionStage().toCompletableFuture().get();
        assertTrue(existsBefore);

        // Drop the database using admin method
        databaseTemplateManager.dropDatabaseFromAdmin(
            postgres.getHost(),
            postgres.getFirstMappedPort(),
            postgres.getUsername(),
            postgres.getPassword(),
            testDbName
        ).toCompletionStage().toCompletableFuture().get();

        // Verify it no longer exists
        Boolean existsAfter = reactivePool.withConnection(connection ->
            databaseTemplateManager.databaseExists(connection, testDbName)
        ).toCompletionStage().toCompletableFuture().get();
        assertFalse(existsAfter);
    }

    @Test
    void testCreateDatabaseFromTemplateWithNullTemplate() throws Exception {
        String testDbName = "test_db_null_template_" + System.currentTimeMillis();

        try {
            // Create database without specifying a template
            databaseTemplateManager.createDatabaseFromTemplate(
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getUsername(),
                postgres.getPassword(),
                testDbName,
                null,  // null template
                "UTF8",
                new HashMap<>()
            ).toCompletionStage().toCompletableFuture().get();

            // Verify database was created
            Boolean exists = reactivePool.withConnection(connection ->
                databaseTemplateManager.databaseExists(connection, testDbName)
            ).toCompletionStage().toCompletableFuture().get();

            assertTrue(exists);
        } finally {
            // Clean up
            try {
                databaseTemplateManager.dropDatabaseFromAdmin(
                    postgres.getHost(),
                    postgres.getFirstMappedPort(),
                    postgres.getUsername(),
                    postgres.getPassword(),
                    testDbName
                ).toCompletionStage().toCompletableFuture().get();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
    }

    @Test
    void testCreateDatabaseFromTemplateWithNullEncoding() throws Exception {
        String testDbName = "test_db_null_encoding_" + System.currentTimeMillis();

        try {
            // Create database without specifying encoding
            databaseTemplateManager.createDatabaseFromTemplate(
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getUsername(),
                postgres.getPassword(),
                testDbName,
                "template0",
                null,  // null encoding
                new HashMap<>()
            ).toCompletionStage().toCompletableFuture().get();

            // Verify database was created
            Boolean exists = reactivePool.withConnection(connection ->
                databaseTemplateManager.databaseExists(connection, testDbName)
            ).toCompletionStage().toCompletableFuture().get();

            assertTrue(exists);
        } finally {
            // Clean up
            try {
                databaseTemplateManager.dropDatabaseFromAdmin(
                    postgres.getHost(),
                    postgres.getFirstMappedPort(),
                    postgres.getUsername(),
                    postgres.getPassword(),
                    testDbName
                ).toCompletionStage().toCompletableFuture().get();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
    }
}

