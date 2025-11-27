package dev.mars.peegeeq.db.connection;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.TransactionPropagation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for PgConnectionManager using TestContainers.
 *
 * <p>These tests are tagged as CORE because they:
 * <ul>
 *   <li>Run fast (each test completes in <1 second)</li>
 *   <li>Are isolated (each test focuses on a single method)</li>
 *   <li>Test one component at a time (PgConnectionManager only)</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
public class PgConnectionManagerCoreTest extends BaseIntegrationTest {

    private PgConnectionManager connectionManager;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        connectionManager = new PgConnectionManager(manager.getVertx(), meterRegistry);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    @Test
    void testPgConnectionManagerCreation() {
        assertNotNull(connectionManager);
    }

    @Test
    void testPgConnectionManagerCreationWithNullVertx() {
        assertThrows(NullPointerException.class, () -> new PgConnectionManager(null));
    }

    @Test
    void testPgConnectionManagerCreationWithoutMeterRegistry() {
        PgConnectionManager cm = new PgConnectionManager(manager.getVertx());
        assertNotNull(cm);
        cm.close();
    }

    @Test
    void testGetOrCreateReactivePool() {
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

        Pool pool = connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);
        assertNotNull(pool);

        // Verify we get the same pool on second call
        Pool pool2 = connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);
        assertSame(pool, pool2);
    }

    @Test
    void testGetOrCreateReactivePoolWithNullConnectionConfig() {
        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();
        assertThrows(NullPointerException.class, () ->
            connectionManager.getOrCreateReactivePool("test-service", null, poolConfig)
        );
    }

    @Test
    void testGetOrCreateReactivePoolWithNullPoolConfig() {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        assertThrows(NullPointerException.class, () ->
            connectionManager.getOrCreateReactivePool("test-service", connectionConfig, null)
        );
    }

    @Test
    void testGetExistingPool() {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();

        // Initially no pool exists
        assertNull(connectionManager.getExistingPool("test-service"));

        // Create pool
        Pool pool = connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);

        // Now pool should exist
        Pool existingPool = connectionManager.getExistingPool("test-service");
        assertNotNull(existingPool);
        assertSame(pool, existingPool);
    }

    @Test
    void testGetReactiveConnection() throws Exception {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("public")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();
        connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);

        // Get a connection
        SqlConnection connection = connectionManager.getReactiveConnection("test-service")
            .toCompletionStage().toCompletableFuture().get();

        assertNotNull(connection);

        // Verify connection works
        Integer result = connection.query("SELECT 1 as value")
            .execute()
            .map(rowSet -> rowSet.iterator().next().getInteger("value"))
            .toCompletionStage().toCompletableFuture().get();

        assertEquals(1, result);

        // Close connection
        connection.close().toCompletionStage().toCompletableFuture().get();
    }

    @Test
    void testGetReactiveConnectionWithNonExistentService() throws Exception {
        try {
            connectionManager.getReactiveConnection("non-existent-service")
                .toCompletionStage().toCompletableFuture().get();
            fail("Expected exception for non-existent service");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof IllegalStateException);
            assertTrue(e.getCause().getMessage().contains("No reactive pool found"));
        }
    }

    @Test
    void testWithConnection() throws Exception {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("public")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();
        connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);

        // Execute operation with connection
        Integer result = connectionManager.withConnection("test-service", conn ->
            conn.query("SELECT 42 as value")
                .execute()
                .map(rowSet -> rowSet.iterator().next().getInteger("value"))
        ).toCompletionStage().toCompletableFuture().get();

        assertEquals(42, result);
    }

    @Test
    void testWithConnectionNonExistentService() throws Exception {
        try {
            connectionManager.withConnection("non-existent-service", conn ->
                conn.query("SELECT 1").execute().map(rs -> 1)
            ).toCompletionStage().toCompletableFuture().get();
            fail("Expected exception for non-existent service");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof IllegalStateException);
            assertTrue(e.getCause().getMessage().contains("No reactive pool found"));
        }
    }

    @Test
    void testWithTransaction() throws Exception {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();
        connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);

        // Execute operation within transaction
        Integer result = connectionManager.withTransaction("test-service", conn ->
            conn.query("SELECT 99 as value")
                .execute()
                .map(rowSet -> rowSet.iterator().next().getInteger("value"))
        ).toCompletionStage().toCompletableFuture().get();

        assertEquals(99, result);
    }

    @Test
    void testWithTransactionNonExistentService() throws Exception {
        try {
            connectionManager.withTransaction("non-existent-service", conn ->
                conn.query("SELECT 1").execute().map(rs -> 1)
            ).toCompletionStage().toCompletableFuture().get();
            fail("Expected exception for non-existent service");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof IllegalStateException);
            assertTrue(e.getCause().getMessage().contains("No reactive pool found"));
        }
    }

    @Test
    void testWithTransactionPropagation() throws Exception {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();
        connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);

        // Execute operation within transaction with propagation
        // Use NONE propagation - connection is local to this function execution
        Integer result = connectionManager.withTransaction("test-service", TransactionPropagation.NONE, conn ->
            conn.query("SELECT 77 as value")
                .execute()
                .map(rowSet -> rowSet.iterator().next().getInteger("value"))
        ).toCompletionStage().toCompletableFuture().get();

        assertEquals(77, result);
    }

    @Test
    void testCheckHealth() throws Exception {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();
        connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);

        // Check health
        Boolean healthy = connectionManager.checkHealth("test-service")
            .toCompletionStage().toCompletableFuture().get();

        assertTrue(healthy);
    }

    @Test
    void testCheckHealthNonExistentService() throws Exception {
        // Check health for non-existent service
        Boolean healthy = connectionManager.checkHealth("non-existent-service")
            .toCompletionStage().toCompletableFuture().get();

        assertFalse(healthy);
    }

    @Test
    void testIsHealthy() {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();

        // Initially healthy (no pools)
        assertTrue(connectionManager.isHealthy());

        // Create pool
        connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);

        // Still healthy
        assertTrue(connectionManager.isHealthy());
    }

    @Test
    void testClosePoolAsync() throws Exception {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();
        connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);

        // Verify pool exists
        assertNotNull(connectionManager.getExistingPool("test-service"));

        // Close pool
        connectionManager.closePoolAsync("test-service")
            .toCompletionStage().toCompletableFuture().get();

        // Verify pool is removed
        assertNull(connectionManager.getExistingPool("test-service"));
    }

    @Test
    void testClosePoolAsyncNonExistentService() throws Exception {
        // Closing non-existent pool should succeed without error
        connectionManager.closePoolAsync("non-existent-service")
            .toCompletionStage().toCompletableFuture().get();
    }

    @Test
    void testCloseAsync() throws Exception {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();
        connectionManager.getOrCreateReactivePool("test-service-1", connectionConfig, poolConfig);
        connectionManager.getOrCreateReactivePool("test-service-2", connectionConfig, poolConfig);

        // Verify pools exist
        assertNotNull(connectionManager.getExistingPool("test-service-1"));
        assertNotNull(connectionManager.getExistingPool("test-service-2"));

        // Close all pools
        connectionManager.closeAsync()
            .toCompletionStage().toCompletableFuture().get();

        // Verify all pools are removed
        assertNull(connectionManager.getExistingPool("test-service-1"));
        assertNull(connectionManager.getExistingPool("test-service-2"));
    }

    @Test
    void testCloseAsyncWithNoPools() throws Exception {
        // Closing with no pools should succeed without error
        connectionManager.closeAsync()
            .toCompletionStage().toCompletableFuture().get();
    }

    @Test
    void testClose() {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();
        connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);

        // Verify pool exists
        assertNotNull(connectionManager.getExistingPool("test-service"));

        // Close synchronously
        connectionManager.close();

        // Verify pool is removed
        assertNull(connectionManager.getExistingPool("test-service"));
    }

    @Test
    void testSchemaConfiguration() {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("public, pg_catalog")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();

        // Create pool with schema configuration
        Pool pool = connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);
        assertNotNull(pool);
    }

}

