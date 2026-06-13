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

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.SharedPostgresTestExtension;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for PgConnectionManager schema enforcement.
 *
 * Validates that the configured peegeeq.database.schema property is properly applied
 * to all connection acquisition paths:
 * - getReactiveConnection()
 * - withConnection()
 * - withTransaction()
 * - checkHealth()
 *
 * Uses TestContainers with real PostgreSQL to verify schema isolation and search_path behavior.
 */
@ExtendWith(SharedPostgresTestExtension.class)
@Tag(TestCategories.INTEGRATION)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@Execution(ExecutionMode.SAME_THREAD)
public class PgConnectionManagerSchemaIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PgConnectionManagerSchemaIntegrationTest.class);

    private PgConnectionManager connectionManager;
    private Vertx vertx;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("=== Setting up PgConnectionManager Schema Integration Test ===");
        vertx = Vertx.vertx();
        connectionManager = new PgConnectionManager(vertx, null);

        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();
        createTestSchemas(postgres)
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("=== Tearing down PgConnectionManager Schema Integration Test ===");
        Future<Void> closeCm = connectionManager != null ? connectionManager.close() : Future.succeededFuture();
        Future<Void> closeVertx = vertx != null ? vertx.close() : Future.<Void>succeededFuture();
        closeCm.compose(v -> closeVertx)
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    /**
     * Creates test schemas and tables for schema isolation testing.
     * Uses INSERT ... ON CONFLICT DO NOTHING to handle parallel test execution.
     */
    private Future<Void> createTestSchemas(PostgreSQLContainer postgres) {
        logger.info("Creating test schemas: schema_a, schema_b");

        PgConnectionConfig config = new PgConnectionConfig.Builder()
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

        var setupPool = connectionManager.getOrCreateReactivePool("setup", config, poolConfig);

        return setupPool.withConnection(conn ->
            conn.query("CREATE SCHEMA IF NOT EXISTS schema_a").execute()
                .compose(v -> conn.query("CREATE SCHEMA IF NOT EXISTS schema_b").execute())
                .compose(v -> conn.query("CREATE TABLE IF NOT EXISTS schema_a.test_table (id INT PRIMARY KEY, name TEXT)").execute())
                .compose(v -> conn.query("CREATE TABLE IF NOT EXISTS schema_b.test_table (id INT PRIMARY KEY, name TEXT)").execute())
                .compose(v -> conn.query("INSERT INTO schema_a.test_table VALUES (1, 'schema_a_data') ON CONFLICT (id) DO NOTHING").execute())
                .compose(v -> conn.query("INSERT INTO schema_b.test_table VALUES (2, 'schema_b_data') ON CONFLICT (id) DO NOTHING").execute())
                .mapEmpty()
        );
    }

    @Test
    @DisplayName("Test schema enforcement via getReactiveConnection()")
    void testSchemaEnforcementViaGetConnection(VertxTestContext testContext) {
        logger.info("TEST: Schema enforcement via getReactiveConnection()");

        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        PgConnectionConfig config = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("schema_a")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(3)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(2))
            .connectionTimeout(Duration.ofSeconds(5))
            .build();

        connectionManager.getOrCreateReactivePool("test-schema-a", config, poolConfig);

        connectionManager.getReactiveConnection("test-schema-a")
            .compose(conn ->
                conn.query("SELECT name FROM test_table WHERE id = 1").execute()
                    .map(rows -> {
                        String name = rows.iterator().next().getString("name");
                        conn.close();
                        return name;
                    })
            )
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                assertEquals("schema_a_data", result, "Should query from schema_a");
                logger.info("getReactiveConnection() correctly applied schema_a");
                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("Test schema enforcement via withConnection()")
    void testSchemaEnforcementViaWithConnection(VertxTestContext testContext) {
        logger.info("TEST: Schema enforcement via withConnection()");

        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        PgConnectionConfig config = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("schema_b")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(3)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(2))
            .connectionTimeout(Duration.ofSeconds(5))
            .build();

        connectionManager.getOrCreateReactivePool("test-schema-b", config, poolConfig);

        connectionManager.withConnection("test-schema-b", conn ->
            conn.query("SELECT name FROM test_table WHERE id = 2").execute()
                .map(rows -> rows.iterator().next().getString("name"))
        )
        .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
            assertEquals("schema_b_data", result, "Should query from schema_b");
            logger.info("withConnection() correctly applied schema_b");
            testContext.completeNow();
        })));
    }

    @Test
    @DisplayName("withTransaction() enforces schema: INSERT and SELECT within transaction resolve unqualified table to configured schema")
    void testSchemaEnforcementViaWithTransaction(VertxTestContext testContext) {
        logger.info("TEST: Schema enforcement via withTransaction()");

        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        PgConnectionConfig config = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("schema_a")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(3)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(2))
            .connectionTimeout(Duration.ofSeconds(5))
            .build();

        connectionManager.getOrCreateReactivePool("test-txn-schema-a", config, poolConfig);

        connectionManager.withTransaction("test-txn-schema-a", conn ->
            conn.query("INSERT INTO test_table VALUES (10, 'txn_test')").execute()
                .compose(v -> conn.query("SELECT name FROM test_table WHERE id = 10").execute())
                .map(rows -> rows.iterator().next().getString("name"))
        )
        .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
            assertEquals("txn_test", result, "Should insert and query from schema_a");
            logger.info("withTransaction() correctly applied schema_a");
            testContext.completeNow();
        })));
    }

    @Test
    @DisplayName("withTransaction() resolves unqualified SELECT to schema_b when schema_b is configured")
    void testSchemaEnforcementWithTransactionSchemaB(VertxTestContext testContext) {
        logger.info("TEST: Schema enforcement with withTransaction using schema_b");

        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        PgConnectionConfig config = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("schema_b")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(3)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(2))
            .connectionTimeout(Duration.ofSeconds(5))
            .build();

        connectionManager.getOrCreateReactivePool("test-propagation", config, poolConfig);

        connectionManager.withTransaction("test-propagation", conn ->
            conn.query("SELECT name FROM test_table WHERE id = 2").execute()
                .map(rows -> rows.iterator().next().getString("name"))
        )
        .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
            assertEquals("schema_b_data", result, "Should query from schema_b");
            logger.info("withTransaction() correctly applied schema_b");
            testContext.completeNow();
        })));
    }

    @Test
    @DisplayName("Test health check respects configured schema")
    void testHealthCheckRespectsSchema(VertxTestContext testContext) {
        logger.info("TEST: Health check respects configured schema");

        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        PgConnectionConfig config = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("schema_a")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(3)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(2))
            .connectionTimeout(Duration.ofSeconds(5))
            .build();

        connectionManager.getOrCreateReactivePool("test-health", config, poolConfig);

        connectionManager.checkHealth("test-health")
            .onComplete(testContext.succeeding(isHealthy -> testContext.verify(() -> {
                assertTrue(isHealthy, "Health check should pass with valid schema");
                logger.info("Health check correctly applied schema_a");
                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("Test multiple services with different schemas")
    void testMultipleServicesWithDifferentSchemas(VertxTestContext testContext) {
        logger.info("TEST: Multiple services with different schemas");
        AtomicReference<String> resultARef = new AtomicReference<>();

        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        PgConnectionConfig configA = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("schema_a")
            .build();

        PgConnectionConfig configB = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("schema_b")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(3)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(2))
            .connectionTimeout(Duration.ofSeconds(5))
            .build();

        connectionManager.getOrCreateReactivePool("service-a", configA, poolConfig);
        connectionManager.getOrCreateReactivePool("service-b", configB, poolConfig);

        connectionManager.withConnection("service-a", conn ->
            conn.query("SELECT name FROM test_table WHERE id = 1").execute()
                .map(rows -> rows.iterator().next().getString("name"))
        )
        .compose(resultA -> {
            resultARef.set(resultA);
            return connectionManager.withConnection("service-b", conn ->
                conn.query("SELECT name FROM test_table WHERE id = 2").execute()
                    .map(rows -> rows.iterator().next().getString("name"))
            );
        })
        .onComplete(testContext.succeeding(resultB -> testContext.verify(() -> {
            assertEquals("schema_a_data", resultARef.get(), "Service A should query from schema_a");
            assertEquals("schema_b_data", resultB, "Service B should query from schema_b");
            logger.info("Multiple services correctly isolated by schema");
            testContext.completeNow();
        })));
    }

    @Test
    @DisplayName("Schema name containing SQL injection characters is rejected with IllegalArgumentException before pool creation")
    void testInvalidSchemaFailsFast() {
        logger.error("===== INTENTIONAL ERROR TEST ===== The next ERROR log ('Failed to create pool for test-invalid') is EXPECTED this test deliberately uses a SQL injection schema name to verify the schema validation guard rejects it");
        logger.info("TEST: Invalid schema fails fast");

        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

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
                .maxSize(3)
                .shared(false)
                .idleTimeout(Duration.ofSeconds(2))
                .connectionTimeout(Duration.ofSeconds(5))
                .build();

            connectionManager.getOrCreateReactivePool("test-invalid", config, poolConfig);
        }, "Should reject schema with SQL injection attempt");

        boolean foundIllegalArgument = false;
        Throwable cause = thrown;
        while (cause != null) {
            if (cause instanceof IllegalArgumentException) {
                foundIllegalArgument = true;
                break;
            }
            cause = cause.getCause();
        }

        boolean hasInvalidSchemaMessage = thrown.getMessage() != null &&
            (thrown.getMessage().contains("Invalid schema") ||
             thrown.getMessage().contains("allowed:"));

        assertTrue(foundIllegalArgument || hasInvalidSchemaMessage,
            "Exception should indicate invalid schema. Got: " + thrown.getClass().getName() +
            " with message: " + thrown.getMessage());

        logger.info("Invalid schema correctly rejected with exception: {}", thrown.getClass().getSimpleName());
    }

    @Test
    @DisplayName("No schema configured is an error — PeeGeeQ has no default schema")
    void testNoSchemaConfiguredIsRejected(VertxTestContext testContext) {
        // Inverted contract: the previous test asserted that a missing schema silently
        // fell back to the connection-default search_path — the accidental-default
        // defect class. A connection config without an explicit schema must throw.
        logger.info("TEST: No schema configured is rejected at build");

        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        PgConnectionConfig.Builder builder = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema(null);

        assertThrows(NullPointerException.class, builder::build,
            "Building a connection config without a schema must throw — PeeGeeQ has no default schema");
        testContext.completeNow();
    }

    @Test
    @DisplayName("closePool() removes the named pool from the manager registry")
    void testSchemaCleanupOnPoolClose(VertxTestContext testContext) {
        logger.info("TEST: Schema cleanup on pool close");

        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        PgConnectionConfig config = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("schema_a")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(3)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(2))
            .connectionTimeout(Duration.ofSeconds(5))
            .build();

        connectionManager.getOrCreateReactivePool("test-cleanup", config, poolConfig);

        assertNotNull(connectionManager.getExistingPool("test-cleanup"), "Pool should exist");

        connectionManager.closePool("test-cleanup")
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                assertNull(connectionManager.getExistingPool("test-cleanup"), "Pool should be removed");
                logger.info("Schema configuration correctly cleaned up on pool close");
                testContext.completeNow();
            })));
    }
}