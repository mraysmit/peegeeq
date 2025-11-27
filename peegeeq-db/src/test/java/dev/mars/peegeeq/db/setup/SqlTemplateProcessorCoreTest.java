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
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for SqlTemplateProcessor using TestContainers.
 *
 * <p>These tests are tagged as CORE because they:
 * <ul>
 *   <li>Run fast (each test completes in <1 second)</li>
 *   <li>Are isolated (each test focuses on a single method)</li>
 *   <li>Test one component at a time (SqlTemplateProcessor only)</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
public class SqlTemplateProcessorCoreTest extends BaseIntegrationTest {

    private PgConnectionManager connectionManager;
    private Pool reactivePool;
    private SqlTemplateProcessor sqlTemplateProcessor;

    @BeforeEach
    void setUp() throws Exception {
        // Create connection manager using the shared Vertx instance
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        // Get PostgreSQL container and create pool
        PostgreSQLContainer<?> postgres = getPostgres();
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

        // Create SQL template processor
        sqlTemplateProcessor = new SqlTemplateProcessor();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    @Test
    void testSqlTemplateProcessorCreation() {
        assertNotNull(sqlTemplateProcessor);
    }

    @Test
    void testApplyTemplateReactiveWithSimpleTable() throws Exception {
        // Apply the test-simple-table template
        Map<String, String> parameters = new HashMap<>();
        parameters.put("TABLE_NAME", "test_simple_table");

        reactivePool.withConnection(connection ->
            sqlTemplateProcessor.applyTemplateReactive(connection, "test-simple-table.sql", parameters)
        ).toCompletionStage().toCompletableFuture().get();

        // Verify table was created
        Boolean tableExists = reactivePool.withConnection(connection ->
            connection.preparedQuery("SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'test_simple_table')")
                .execute()
                .map(rowSet -> rowSet.iterator().next().getBoolean(0))
        ).toCompletionStage().toCompletableFuture().get();

        assertTrue(tableExists);

        // Clean up
        reactivePool.withConnection(connection ->
            connection.query("DROP TABLE IF EXISTS test_simple_table").execute()
                .map(rowSet -> (Void) null)
        ).toCompletionStage().toCompletableFuture().get();
    }

    @Test
    void testApplyTemplateReactiveWithMultipleParameters() throws Exception {
        // Create a table with a different name using the same template
        Map<String, String> parameters = new HashMap<>();
        parameters.put("TABLE_NAME", "test_another_table");

        reactivePool.withConnection(connection ->
            sqlTemplateProcessor.applyTemplateReactive(connection, "test-simple-table.sql", parameters)
        ).toCompletionStage().toCompletableFuture().get();

        // Verify table was created
        Boolean tableExists = reactivePool.withConnection(connection ->
            connection.preparedQuery("SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'test_another_table')")
                .execute()
                .map(rowSet -> rowSet.iterator().next().getBoolean(0))
        ).toCompletionStage().toCompletableFuture().get();

        assertTrue(tableExists);

        // Clean up
        reactivePool.withConnection(connection ->
            connection.query("DROP TABLE IF EXISTS test_another_table").execute()
                .map(rowSet -> (Void) null)
        ).toCompletionStage().toCompletableFuture().get();
    }

    @Test
    void testApplyTemplateReactiveWithNonExistentTemplate() {
        // Try to apply a non-existent template
        Map<String, String> parameters = new HashMap<>();

        assertThrows(Exception.class, () -> {
            reactivePool.withConnection(connection ->
                sqlTemplateProcessor.applyTemplateReactive(connection, "non-existent-template.sql", parameters)
            ).toCompletionStage().toCompletableFuture().get();
        });
    }
}

