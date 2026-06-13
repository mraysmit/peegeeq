package dev.mars.peegeeq.db.connection;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;

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
@Execution(ExecutionMode.SAME_THREAD)
public class PgConnectionManagerCoreTest extends BaseIntegrationTest {

    private PgConnectionManager connectionManager;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        connectionManager = new PgConnectionManager(manager.getVertx(), meterRegistry);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.close().onSuccess(v -> testContext.completeNow()).onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
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
        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(3)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(2))
            .connectionTimeout(Duration.ofSeconds(5))
            .build();

        Pool pool = connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);
        assertNotNull(pool);

        // Verify we get the same pool on second call
        Pool pool2 = connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);
        assertSame(pool, pool2);
    }

    @Test
    void testGetOrCreateReactivePoolWithNullConnectionConfig() {
        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).shared(false).idleTimeout(Duration.ofSeconds(2)).connectionTimeout(Duration.ofSeconds(5)).build();
        assertThrows(NullPointerException.class, () ->
            connectionManager.getOrCreateReactivePool("test-service", null, poolConfig)
        );
    }

    @Test
    void testGetOrCreateReactivePoolWithNullPoolConfig() {
        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .build();

        assertThrows(NullPointerException.class, () ->
            connectionManager.getOrCreateReactivePool("test-service", connectionConfig, null)
        );
    }

    @Test
    void testGetExistingPool() {
        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).shared(false).idleTimeout(Duration.ofSeconds(2)).connectionTimeout(Duration.ofSeconds(5)).build();

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
    void testGetReactiveConnection(VertxTestContext testContext) {
        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).shared(false).idleTimeout(Duration.ofSeconds(2)).connectionTimeout(Duration.ofSeconds(5)).build();
        connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);

        connectionManager.getReactiveConnection("test-service")
            .compose(connection -> {
                assertNotNull(connection);
                return connection.query("SELECT 1 as value")
                    .execute()
                    .map(rowSet -> rowSet.iterator().next().getInteger("value"))
                    .compose(result -> {
                        assertEquals(1, result);
                        return connection.close();
                    });
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetReactiveConnectionWithNonExistentService(VertxTestContext testContext) {
        connectionManager.getReactiveConnection("non-existent-service")
            .onComplete(testContext.failing(e -> testContext.verify(() -> {
                assertTrue(e instanceof IllegalStateException);
                assertTrue(e.getMessage().contains("No reactive pool found"));
                testContext.completeNow();
            })));
    }

    @Test
    void testWithConnection(VertxTestContext testContext) {
        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).shared(false).idleTimeout(Duration.ofSeconds(2)).connectionTimeout(Duration.ofSeconds(5)).build();
        connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);

        connectionManager.withConnection("test-service", conn ->
            conn.query("SELECT 42 as value")
                .execute()
                .map(rowSet -> rowSet.iterator().next().getInteger("value"))
        )
        .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
            assertEquals(42, result);
            testContext.completeNow();
        })));
    }

    @Test
    void testWithConnectionNonExistentService(VertxTestContext testContext) {
        connectionManager.withConnection("non-existent-service", conn ->
            conn.query("SELECT 1").execute().map(rs -> 1)
        )
        .onComplete(testContext.failing(e -> testContext.verify(() -> {
            assertTrue(e instanceof IllegalStateException);
            assertTrue(e.getMessage().contains("No reactive pool found"));
            testContext.completeNow();
        })));
    }

    @Test
    void testWithTransaction(VertxTestContext testContext) {
        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).shared(false).idleTimeout(Duration.ofSeconds(2)).connectionTimeout(Duration.ofSeconds(5)).build();
        connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);

        connectionManager.withTransaction("test-service", conn ->
            conn.query("SELECT 99 as value")
                .execute()
                .map(rowSet -> rowSet.iterator().next().getInteger("value"))
        )
        .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
            assertEquals(99, result);
            testContext.completeNow();
        })));
    }

    @Test
    void testWithTransactionNonExistentService(VertxTestContext testContext) {
        connectionManager.withTransaction("non-existent-service", conn ->
            conn.query("SELECT 1").execute().map(rs -> 1)
        )
        .onComplete(testContext.failing(e -> testContext.verify(() -> {
            assertTrue(e instanceof IllegalStateException);
            assertTrue(e.getMessage().contains("No reactive pool found"));
            testContext.completeNow();
        })));
    }

    @Test
    void testCheckHealth(VertxTestContext testContext) {
        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).shared(false).idleTimeout(Duration.ofSeconds(2)).connectionTimeout(Duration.ofSeconds(5)).build();
        connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);

        connectionManager.checkHealth("test-service")
            .onComplete(testContext.succeeding(healthy -> testContext.verify(() -> {
                assertTrue(healthy);
                testContext.completeNow();
            })));
    }

    @Test
    void testCheckHealthNonExistentService(VertxTestContext testContext) {
        connectionManager.checkHealth("non-existent-service")
            .onComplete(testContext.succeeding(healthy -> testContext.verify(() -> {
                assertFalse(healthy);
                testContext.completeNow();
            })));
    }

    @Test
    void testHasPoolsConfigured() {
        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).shared(false).idleTimeout(Duration.ofSeconds(2)).connectionTimeout(Duration.ofSeconds(5)).build();

        // Initially true (no pools)
        assertTrue(connectionManager.hasPoolsConfigured());

        // Create pool
        connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);

        // Still true
        assertTrue(connectionManager.hasPoolsConfigured());
    }

    @Test
    void testClosePool(VertxTestContext testContext) {
        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).shared(false).idleTimeout(Duration.ofSeconds(2)).connectionTimeout(Duration.ofSeconds(5)).build();
        connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);

        // Verify pool exists
        assertNotNull(connectionManager.getExistingPool("test-service"));

        connectionManager.closePool("test-service")
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                assertNull(connectionManager.getExistingPool("test-service"));
                testContext.completeNow();
            })));
    }

    @Test
    void testClosePoolNonExistentService(VertxTestContext testContext) {
        connectionManager.closePool("non-existent-service")
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testCloseAsync(VertxTestContext testContext) {
        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).shared(false).idleTimeout(Duration.ofSeconds(2)).connectionTimeout(Duration.ofSeconds(5)).build();
        connectionManager.getOrCreateReactivePool("test-service-1", connectionConfig, poolConfig);
        connectionManager.getOrCreateReactivePool("test-service-2", connectionConfig, poolConfig);

        // Verify pools exist
        assertNotNull(connectionManager.getExistingPool("test-service-1"));
        assertNotNull(connectionManager.getExistingPool("test-service-2"));

        connectionManager.close()
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                assertNull(connectionManager.getExistingPool("test-service-1"));
                assertNull(connectionManager.getExistingPool("test-service-2"));
                testContext.completeNow();
            })));
    }

    @Test
    void testCloseAsyncWithNoPools(VertxTestContext testContext) {
        connectionManager.close()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testClose(VertxTestContext testContext) {
        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).shared(false).idleTimeout(Duration.ofSeconds(2)).connectionTimeout(Duration.ofSeconds(5)).build();
        connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);

        // Verify pool exists
        assertNotNull(connectionManager.getExistingPool("test-service"));

        connectionManager.close()
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                assertNull(connectionManager.getExistingPool("test-service"));
                testContext.completeNow();
            })));
    }

    @Test
    void testSchemaConfiguration() {
        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("public, pg_catalog")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).shared(false).idleTimeout(Duration.ofSeconds(2)).connectionTimeout(Duration.ofSeconds(5)).build();

        // Create pool with schema configuration
        Pool pool = connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);
        assertNotNull(pool);
    }

}

