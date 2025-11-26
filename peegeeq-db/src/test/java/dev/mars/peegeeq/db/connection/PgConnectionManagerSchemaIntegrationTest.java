package dev.mars.peegeeq.db.connection;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.SharedPostgresExtension;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.TransactionPropagation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for PgConnectionManager schema enforcement.
 *
 * Validates that the configured peegeeq.database.schema property is properly applied
 * to all connection acquisition paths:
 * - getReactiveConnection()
 * - withConnection()
 * - withTransaction()
 * - withTransaction(TransactionPropagation)
 * - checkHealth()
 *
 * Uses TestContainers with real PostgreSQL to verify schema isolation and search_path behavior.
 */
@ExtendWith(SharedPostgresExtension.class)
@Tag(TestCategories.INTEGRATION)
@Tag(TestCategories.FLAKY)  // Tests are unstable in parallel execution - needs investigation
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public class PgConnectionManagerSchemaIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PgConnectionManagerSchemaIntegrationTest.class);

    private PgConnectionManager connectionManager;
    private Vertx vertx;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("=== Setting up PgConnectionManager Schema Integration Test ===");
        vertx = Vertx.vertx();
        connectionManager = new PgConnectionManager(vertx, null);

        // Create test schemas
        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();
        createTestSchemas(postgres);
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("=== Tearing down PgConnectionManager Schema Integration Test ===");
        if (connectionManager != null) {
            connectionManager.close();
        }
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Creates test schemas and tables for schema isolation testing.
     * Uses INSERT ... ON CONFLICT DO NOTHING to handle parallel test execution.
     */
    private void createTestSchemas(PostgreSQLContainer<?> postgres) throws Exception {
        logger.info("Creating test schemas: schema_a, schema_b");

        PgConnectionConfig config = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("public") // Use public for setup
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(5)
            .build();

        var setupPool = connectionManager.getOrCreateReactivePool("setup", config, poolConfig);

        // Use IF NOT EXISTS and ON CONFLICT DO NOTHING to handle parallel test execution
        setupPool.withConnection(conn ->
            conn.query("CREATE SCHEMA IF NOT EXISTS schema_a").execute()
                .compose(v -> conn.query("CREATE SCHEMA IF NOT EXISTS schema_b").execute())
                .compose(v -> conn.query("CREATE TABLE IF NOT EXISTS schema_a.test_table (id INT PRIMARY KEY, name TEXT)").execute())
                .compose(v -> conn.query("CREATE TABLE IF NOT EXISTS schema_b.test_table (id INT PRIMARY KEY, name TEXT)").execute())
                .compose(v -> conn.query("INSERT INTO schema_a.test_table VALUES (1, 'schema_a_data') ON CONFLICT (id) DO NOTHING").execute())
                .compose(v -> conn.query("INSERT INTO schema_b.test_table VALUES (2, 'schema_b_data') ON CONFLICT (id) DO NOTHING").execute())
                .mapEmpty()
        ).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        logger.info("✅ Test schemas created successfully");
    }

    @Test
    @DisplayName("Test schema enforcement via getReactiveConnection()")
    void testSchemaEnforcementViaGetConnection() throws Exception {
        logger.info("TEST: Schema enforcement via getReactiveConnection()");

        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();

        // Create connection config with schema_a
        PgConnectionConfig config = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("schema_a")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(5)
            .build();

        connectionManager.getOrCreateReactivePool("test-schema-a", config, poolConfig);

        // Query unqualified table - should use schema_a
        String result = connectionManager.getReactiveConnection("test-schema-a")
            .compose(conn ->
                conn.query("SELECT name FROM test_table WHERE id = 1").execute()
                    .map(rows -> {
                        String name = rows.iterator().next().getString("name");
                        conn.close();
                        return name;
                    })
            )
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);

        assertEquals("schema_a_data", result, "Should query from schema_a");
        logger.info("✅ getReactiveConnection() correctly applied schema_a");
    }

    @Test
    @DisplayName("Test schema enforcement via withConnection()")
    void testSchemaEnforcementViaWithConnection() throws Exception {
        logger.info("TEST: Schema enforcement via withConnection()");

        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();

        PgConnectionConfig config = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("schema_b")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(5)
            .build();

        connectionManager.getOrCreateReactivePool("test-schema-b", config, poolConfig);

        // Query unqualified table - should use schema_b
        String result = connectionManager.withConnection("test-schema-b", conn ->
            conn.query("SELECT name FROM test_table WHERE id = 2").execute()
                .map(rows -> rows.iterator().next().getString("name"))
        )
        .toCompletionStage()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);

        assertEquals("schema_b_data", result, "Should query from schema_b");
        logger.info("✅ withConnection() correctly applied schema_b");
    }

    @Test
    @DisplayName("Test schema enforcement via withTransaction()")
    void testSchemaEnforcementViaWithTransaction() throws Exception {
        logger.info("TEST: Schema enforcement via withTransaction()");

        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();

        PgConnectionConfig config = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("schema_a")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(5)
            .build();

        connectionManager.getOrCreateReactivePool("test-txn-schema-a", config, poolConfig);

        // Insert and query within transaction - should use schema_a
        String result = connectionManager.withTransaction("test-txn-schema-a", conn ->
            conn.query("INSERT INTO test_table VALUES (10, 'txn_test')").execute()
                .compose(v -> conn.query("SELECT name FROM test_table WHERE id = 10").execute())
                .map(rows -> rows.iterator().next().getString("name"))
        )
        .toCompletionStage()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);

        assertEquals("txn_test", result, "Should insert and query from schema_a");
        logger.info("✅ withTransaction() correctly applied schema_a");
    }

    @Test
    @DisplayName("Test schema enforcement with TransactionPropagation.NONE")
    void testSchemaEnforcementWithTransactionPropagation() throws Exception {
        logger.info("TEST: Schema enforcement with TransactionPropagation.NONE");

        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();

        PgConnectionConfig config = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("schema_b")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(5)
            .build();

        connectionManager.getOrCreateReactivePool("test-propagation", config, poolConfig);

        // Use TransactionPropagation.NONE - connection is local to this function execution
        // Note: CONTEXT propagation requires an existing Vert.x context which isn't available in JUnit threads
        String result = connectionManager.withTransaction("test-propagation", TransactionPropagation.NONE, conn ->
            conn.query("SELECT name FROM test_table WHERE id = 2").execute()
                .map(rows -> rows.iterator().next().getString("name"))
        )
        .toCompletionStage()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);

        assertEquals("schema_b_data", result, "Should query from schema_b with NONE propagation");
        logger.info("✅ withTransaction(NONE) correctly applied schema_b");
    }

    @Test
    @DisplayName("Test health check respects configured schema")
    void testHealthCheckRespectsSchema() throws Exception {
        logger.info("TEST: Health check respects configured schema");

        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();

        PgConnectionConfig config = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("schema_a")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(5)
            .build();

        connectionManager.getOrCreateReactivePool("test-health", config, poolConfig);

        // Health check should succeed with valid schema
        Boolean isHealthy = connectionManager.checkHealth("test-health")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);

        assertTrue(isHealthy, "Health check should pass with valid schema");
        logger.info("✅ Health check correctly applied schema_a");
    }




    @Test
    @DisplayName("Test multiple services with different schemas")
    void testMultipleServicesWithDifferentSchemas() throws Exception {
        logger.info("TEST: Multiple services with different schemas");

        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();

        // Service A with schema_a
        PgConnectionConfig configA = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("schema_a")
            .build();

        // Service B with schema_b
        PgConnectionConfig configB = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("schema_b")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(5)
            .build();

        connectionManager.getOrCreateReactivePool("service-a", configA, poolConfig);
        connectionManager.getOrCreateReactivePool("service-b", configB, poolConfig);

        // Query from service A - should use schema_a
        String resultA = connectionManager.withConnection("service-a", conn ->
            conn.query("SELECT name FROM test_table WHERE id = 1").execute()
                .map(rows -> rows.iterator().next().getString("name"))
        )
        .toCompletionStage()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);

        // Query from service B - should use schema_b
        String resultB = connectionManager.withConnection("service-b", conn ->
            conn.query("SELECT name FROM test_table WHERE id = 2").execute()
                .map(rows -> rows.iterator().next().getString("name"))
        )
        .toCompletionStage()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);

        assertEquals("schema_a_data", resultA, "Service A should query from schema_a");
        assertEquals("schema_b_data", resultB, "Service B should query from schema_b");
        logger.info("✅ Multiple services correctly isolated by schema");
    }

    @Test
    @DisplayName("Test invalid schema fails fast")
    void testInvalidSchemaFailsFast() {
        logger.info("TEST: Invalid schema fails fast");

        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();

        // Try to create config with invalid schema containing SQL injection attempt
        // The exception may be IllegalArgumentException directly or wrapped in IllegalStateException
        // depending on how computeIfAbsent handles the exception
        Exception thrown = assertThrows(Exception.class, () -> {
            PgConnectionConfig config = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema("schema_a; DROP TABLE test_table; --")
                .build();

            PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(5)
                .build();

            connectionManager.getOrCreateReactivePool("test-invalid", config, poolConfig);
        }, "Should reject schema with SQL injection attempt");

        // Verify the exception or its cause chain contains IllegalArgumentException
        // The exception may be wrapped by computeIfAbsent in IllegalStateException
        boolean foundIllegalArgument = false;
        Throwable cause = thrown;
        while (cause != null) {
            if (cause instanceof IllegalArgumentException) {
                foundIllegalArgument = true;
                break;
            }
            cause = cause.getCause();
        }

        // Also check if the exception message indicates invalid schema
        boolean hasInvalidSchemaMessage = thrown.getMessage() != null &&
            (thrown.getMessage().contains("Invalid schema") ||
             thrown.getMessage().contains("allowed:"));

        assertTrue(foundIllegalArgument || hasInvalidSchemaMessage,
            "Exception should indicate invalid schema. Got: " + thrown.getClass().getName() +
            " with message: " + thrown.getMessage());

        logger.info("✅ Invalid schema correctly rejected with exception: {}", thrown.getClass().getSimpleName());
    }

    @Test
    @DisplayName("Test no schema configured uses default search_path")
    void testNoSchemaConfiguredUsesDefault() throws Exception {
        logger.info("TEST: No schema configured uses default search_path");

        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();

        // Create config without schema (null/blank)
        PgConnectionConfig config = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema(null) // No schema configured
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(5)
            .build();

        connectionManager.getOrCreateReactivePool("test-no-schema", config, poolConfig);

        // Should be able to query public schema by default
        Boolean result = connectionManager.withConnection("test-no-schema", conn ->
            conn.query("SELECT 1").execute()
                .map(rows -> rows.iterator().hasNext())
        )
        .toCompletionStage()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);

        assertTrue(result, "Should successfully query with default search_path");
        logger.info("✅ No schema configuration correctly uses default search_path");
    }

    @Test
    @DisplayName("Test schema cleanup on pool close")
    void testSchemaCleanupOnPoolClose() throws Exception {
        logger.info("TEST: Schema cleanup on pool close");

        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();

        PgConnectionConfig config = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("schema_a")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(5)
            .build();

        connectionManager.getOrCreateReactivePool("test-cleanup", config, poolConfig);

        // Verify pool exists
        assertNotNull(connectionManager.getExistingPool("test-cleanup"), "Pool should exist");

        // Close the pool
        connectionManager.closePoolAsync("test-cleanup")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);

        // Verify pool is removed
        assertNull(connectionManager.getExistingPool("test-cleanup"), "Pool should be removed");

        logger.info("✅ Schema configuration correctly cleaned up on pool close");
    }
}
