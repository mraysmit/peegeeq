package dev.mars.peegeeq.db.migration;

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


import dev.mars.peegeeq.db.SharedPostgresExtension;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for SchemaMigrationManager.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@ExtendWith(SharedPostgresExtension.class)
@ResourceLock("database-schema") // Serialize schema migration tests to avoid conflicts
class SchemaMigrationManagerTest {

    private static final Logger logger = LoggerFactory.getLogger(SchemaMigrationManagerTest.class);

    private PgConnectionManager connectionManager;
    private Pool pool;
    private SchemaMigrationManager migrationManager;
    private Vertx vertx;

    @BeforeEach
    void setUp() throws Exception {
        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();
        vertx = Vertx.vertx();
        connectionManager = new PgConnectionManager(vertx);

        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema("migration_test") // Use separate schema to avoid interfering with other tests
                .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .minimumIdle(1)
                .maximumPoolSize(3)
                .build();

        // Create reactive pool for SchemaMigrationManager
        pool = connectionManager.getOrCreateReactivePool("migration-test", connectionConfig, poolConfig);

        // Create the migration_test schema if it doesn't exist
        pool.withConnection(connection -> {
            return connection.query("CREATE SCHEMA IF NOT EXISTS migration_test").execute()
                .compose(v -> connection.query("SET search_path TO migration_test").execute());
        }).toCompletionStage().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);

        // Clean up database state before each test (only in migration_test schema)
        cleanupDatabase();

        migrationManager = new SchemaMigrationManager(pool);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Clean up the migration_test schema BEFORE closing the pool
        if (pool != null) {
            try {
                pool.withConnection(connection -> {
                    return connection.query("DROP SCHEMA IF EXISTS migration_test CASCADE").execute();
                }).toCompletionStage().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.warn("Failed to drop migration_test schema: {}", e.getMessage());
            }
        }

        // Close the connection manager and pool
        if (connectionManager != null) {
            try {
                connectionManager.close();
            } catch (Exception e) {
                logger.warn("Failed to close connection manager: {}", e.getMessage());
            }
        }

        // Close Vertx
        if (vertx != null) {
            try {
                vertx.close().toCompletionStage().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.warn("Failed to close Vertx: {}", e.getMessage());
            }
        }
    }

    /**
     * Cleans up database state before each test using reactive patterns.
     */

    private void cleanupDatabase() throws Exception {
        // Drop all tables that might be created by migrations (in reverse dependency order)
        String[] tables = {
            "schema_version",
            "message_processing",      // Must be dropped before queue_messages (FK dependency)
            "outbox_consumer_groups",  // Must be dropped before outbox (FK dependency)
            "outbox",
            "queue_messages",
            "dead_letter_queue",
            "queue_metrics",
            "connection_pool_metrics",
            "bitemporal_event_log"
        };

        for (String table : tables) {
            try {
                pool.withConnection(connection -> {
                    return connection.query("DROP TABLE IF EXISTS " + table + " CASCADE").execute();
                }).toCompletionStage().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                // Ignore errors - table might not exist
            }
        }

        // Drop all functions that might be created by migrations
        String[] functions = {
            "notify_message_inserted()",
            "update_message_processing_updated_at()",
            "cleanup_completed_message_processing()",
            "register_consumer_group_for_existing_messages(VARCHAR)",
            "create_consumer_group_entries_for_new_message()",
            "cleanup_completed_outbox_messages()",
            "cleanup_old_metrics(INT)",
            "notify_bitemporal_event()",
            "get_events_as_of_time(TIMESTAMP WITH TIME ZONE, TIMESTAMP WITH TIME ZONE)"
        };

        for (String function : functions) {
            try {
                pool.withConnection(connection -> {
                    return connection.query("DROP FUNCTION IF EXISTS " + function + " CASCADE").execute();
                }).toCompletionStage().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                // Ignore errors - function might not exist
            }
        }
    }

    @Test
    void testMigrationManagerInitialization() {
        assertNotNull(migrationManager);
        assertNotNull(pool);
    }

    @Test
    void testSchemaVersionTableCreation() throws Exception {
        // Run migration and verify it completes successfully
        Future<Integer> migrationResult = migrationManager.migrate();
        Integer migrationsApplied = migrationResult.toCompletionStage().toCompletableFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);

        assertNotNull(migrationsApplied);
        assertTrue(migrationsApplied >= 0, "Migration should return non-negative number of applied migrations");

        // Verify schema_version table exists in migration_test schema using reactive query
        Integer tableCount = pool.withConnection(connection -> {
            return connection.preparedQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'schema_version' AND table_schema = 'migration_test'"
            ).execute().compose(rows -> {
                if (rows.size() > 0) {
                    return Future.succeededFuture(rows.iterator().next().getInteger(0));
                } else {
                    return Future.succeededFuture(0);
                }
            });
        }).toCompletionStage().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(1, tableCount.intValue(), "schema_version table should exist in migration_test schema");
    }

    @Test
    void testBaseMigrationApplication() throws Exception {
        Future<Integer> migrationResult = migrationManager.migrate();
        Integer appliedMigrations = migrationResult.toCompletionStage().toCompletableFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);

        // Should apply at least the base migration
        assertNotNull(appliedMigrations);
        assertTrue(appliedMigrations >= 1, "Should apply at least one migration, got: " + appliedMigrations);

        // Verify base tables were created by checking if outbox table exists
        verifyTableExistsReactive("outbox");
        verifyTableExistsReactive("queue_messages");
        verifyTableExistsReactive("dead_letter_queue");
    }

    @Test
    void testMigrationIdempotency() throws Exception {
        // Apply migrations first time
        Future<Integer> firstResult = migrationManager.migrate();
        Integer firstRun = firstResult.toCompletionStage().toCompletableFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(firstRun);
        assertTrue(firstRun >= 1, "First run should apply at least one migration");

        // Apply migrations second time - should be 0
        Future<Integer> secondResult = migrationManager.migrate();
        Integer secondRun = secondResult.toCompletionStage().toCompletableFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(secondRun);
        assertEquals(0, secondRun.intValue(), "Second run should apply no migrations");
    }

    /**
     * Helper method to verify a table exists using reactive patterns.
     */
    private void verifyTableExistsReactive(String tableName) throws Exception {
        Integer tableCount = pool.withConnection(connection -> {
            return connection.preparedQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ? AND table_schema = 'migration_test'"
            ).execute(io.vertx.sqlclient.Tuple.of(tableName)).compose(rows -> {
                if (rows.size() > 0) {
                    return Future.succeededFuture(rows.iterator().next().getInteger(0));
                } else {
                    return Future.succeededFuture(0);
                }
            });
        }).toCompletionStage().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(1, tableCount.intValue(), "Table '" + tableName + "' should exist in migration_test schema");
    }

    @Test
    void testCustomMigrationPath() throws Exception {
        SchemaMigrationManager customManager =
            new SchemaMigrationManager(pool, "/custom/path", true);

        // Should not find any migrations in custom path
        Future<Integer> result = customManager.migrate();
        Integer applied = result.toCompletionStage().toCompletableFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(0, applied.intValue(), "Should not find migrations in custom path");
    }
}
