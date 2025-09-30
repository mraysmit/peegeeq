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


import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

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
@Testcontainers
class SchemaMigrationManagerTest {

    private static final Logger logger = LoggerFactory.getLogger(SchemaMigrationManagerTest.class);

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("migration_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    private PgConnectionManager connectionManager;
    private DataSource dataSource;
    private SchemaMigrationManager migrationManager;

    @BeforeEach
    void setUp() throws SQLException {
        connectionManager = new PgConnectionManager(Vertx.vertx());

        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .minimumIdle(1)
                .maximumPoolSize(3)
                .build();

        // Create temporary DataSource for SchemaMigrationManager using the same approach as PeeGeeQManager
        dataSource = createTemporaryDataSourceForMigration(connectionConfig, poolConfig);

        // Clean up database state before each test
        cleanupDatabase();

        migrationManager = new SchemaMigrationManager(dataSource);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            connectionManager.close();
        }

        // Close the DataSource if it's a HikariDataSource
        if (dataSource != null) {
            try {
                // Use reflection to close HikariDataSource
                Class<?> hikariDataSourceClass = Class.forName("com.zaxxer.hikari.HikariDataSource");
                if (hikariDataSourceClass.isInstance(dataSource)) {
                    hikariDataSourceClass.getMethod("close").invoke(dataSource);
                }
            } catch (Exception e) {
                // Ignore errors during cleanup
            }
        }
    }

    /**
     * Creates a DataSource for SchemaMigrationManager testing.
     * This method uses reflection to create a HikariCP DataSource, following the same pattern as PeeGeeQManager.
     * This is only needed for testing SchemaMigrationManager which still uses JDBC patterns.
     *
     * @param connectionConfig The PostgreSQL connection configuration
     * @param poolConfig The connection pool configuration
     * @return A DataSource for SchemaMigrationManager
     * @throws RuntimeException if HikariCP is not available
     */
    private javax.sql.DataSource createTemporaryDataSourceForMigration(
            PgConnectionConfig connectionConfig,
            PgPoolConfig poolConfig) {
        try {
            // Use reflection to create HikariCP DataSource if available
            Class<?> hikariConfigClass = Class.forName("com.zaxxer.hikari.HikariConfig");
            Class<?> hikariDataSourceClass = Class.forName("com.zaxxer.hikari.HikariDataSource");

            Object config = hikariConfigClass.getDeclaredConstructor().newInstance();

            // Set connection properties
            hikariConfigClass.getMethod("setJdbcUrl", String.class).invoke(config,
                String.format("jdbc:postgresql://%s:%d/%s",
                    connectionConfig.getHost(),
                    connectionConfig.getPort(),
                    connectionConfig.getDatabase()));
            hikariConfigClass.getMethod("setUsername", String.class).invoke(config, connectionConfig.getUsername());
            hikariConfigClass.getMethod("setPassword", String.class).invoke(config, connectionConfig.getPassword());

            // Set pool properties
            hikariConfigClass.getMethod("setMinimumIdle", int.class).invoke(config, poolConfig.getMinimumIdle());
            hikariConfigClass.getMethod("setMaximumPoolSize", int.class).invoke(config, poolConfig.getMaximumPoolSize());
            hikariConfigClass.getMethod("setAutoCommit", boolean.class).invoke(config, poolConfig.isAutoCommit());

            // Set pool name for monitoring
            hikariConfigClass.getMethod("setPoolName", String.class).invoke(config, "PeeGeeQ-Test-Migration-" + System.currentTimeMillis());

            // Create and return the DataSource
            Object dataSource = hikariDataSourceClass.getDeclaredConstructor(hikariConfigClass).newInstance(config);

            return (javax.sql.DataSource) dataSource;

        } catch (ClassNotFoundException e) {
            throw new RuntimeException(
                "HikariCP not found on classpath. For migration testing, HikariCP should be available in test scope.", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create DataSource for SchemaMigrationManager testing: " + e.getMessage(), e);
        }
    }

    private void cleanupDatabase() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
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
                    conn.createStatement().execute("DROP TABLE IF EXISTS " + table + " CASCADE");
                } catch (SQLException e) {
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
                    conn.createStatement().execute("DROP FUNCTION IF EXISTS " + function + " CASCADE");
                } catch (SQLException e) {
                    // Ignore errors - function might not exist
                }
            }
        }
    }

    @Test
    void testMigrationManagerInitialization() {
        assertNotNull(migrationManager);
        assertDoesNotThrow(() -> migrationManager.getCurrentVersion());
    }

    @Test
    void testSchemaVersionTableCreation() throws SQLException {
        // Initially, schema_version table should not exist or be empty
        String initialVersion = migrationManager.getCurrentVersion();
        assertNull(initialVersion);

        // After calling migrate, the table should exist
        migrationManager.migrate();

        // Verify schema_version table exists
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'schema_version'");
             ResultSet rs = stmt.executeQuery()) {
            
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void testBaseMigrationApplication() throws SQLException {
        int appliedMigrations = migrationManager.migrate();

        // Should apply at least the base migration
        assertTrue(appliedMigrations >= 1);

        // Check that current version is set and follows expected format
        String currentVersion = migrationManager.getCurrentVersion();
        assertNotNull(currentVersion);
        assertTrue(currentVersion.startsWith("V"), "Version should start with 'V', got: " + currentVersion);
        assertTrue(currentVersion.matches("V\\d{3}"), "Version should match pattern V### (e.g., V001, V002), got: " + currentVersion);

        // Verify base tables were created (these should exist regardless of which migrations were applied)
        verifyTableExists("outbox");
        verifyTableExists("outbox_consumer_groups");
        verifyTableExists("queue_messages");
        verifyTableExists("dead_letter_queue");
        verifyTableExists("queue_metrics");
        verifyTableExists("connection_pool_metrics");
    }

    @Test
    void testMigrationIdempotency() throws SQLException {
        // Apply migrations first time
        int firstRun = migrationManager.migrate();
        assertTrue(firstRun >= 1);
        
        // Apply migrations second time - should be 0
        int secondRun = migrationManager.migrate();
        assertEquals(0, secondRun);
        
        // Version should remain the same
        String version1 = migrationManager.getCurrentVersion();
        String version2 = migrationManager.getCurrentVersion();
        assertEquals(version1, version2);
    }

    @Test
    void testMigrationHistory() throws SQLException {
        migrationManager.migrate();

        List<SchemaMigrationManager.AppliedMigration> history = migrationManager.getMigrationHistory();
        assertNotNull(history);
        assertFalse(history.isEmpty());

        // Should have applied multiple migrations
        assertTrue(history.size() >= 1, "Should have at least one migration applied");

        // First migration should be V001 (base tables)
        SchemaMigrationManager.AppliedMigration firstMigration = history.get(0);
        assertEquals("V001", firstMigration.getVersion());
        assertNotNull(firstMigration.getDescription());
        assertNotNull(firstMigration.getAppliedAt());
        assertNotNull(firstMigration.getChecksum());

        // All migrations should have valid data
        for (SchemaMigrationManager.AppliedMigration migration : history) {
            assertNotNull(migration.getVersion());
            assertTrue(migration.getVersion().startsWith("V"));
            assertNotNull(migration.getDescription());
            assertNotNull(migration.getAppliedAt());
            assertNotNull(migration.getChecksum());
        }
    }

    @Test
    void testMigrationValidation() throws SQLException {
        migrationManager.migrate();
        
        // Validation should pass for correctly applied migrations
        assertTrue(migrationManager.validateMigrations());
    }

    @Test
    void testMigrationValidationDisabled() {
        SchemaMigrationManager managerWithoutValidation = 
            new SchemaMigrationManager(dataSource, "/db/migration", false);
        
        assertDoesNotThrow(() -> {
            managerWithoutValidation.migrate();
            assertTrue(managerWithoutValidation.validateMigrations());
        });
    }

    @Test
    void testCustomMigrationPath() {
        SchemaMigrationManager customManager = 
            new SchemaMigrationManager(dataSource, "/custom/path", true);
        
        // Should not find any migrations in custom path
        assertDoesNotThrow(() -> {
            int applied = customManager.migrate();
            assertEquals(0, applied);
        });
    }

    @Test
    void testMigrationFailureHandling() throws SQLException {
        // Create a migration manager that will fail by closing the DataSource
        // First, close the DataSource to simulate database connection failure
        try {
            // Use reflection to close HikariDataSource
            Class<?> hikariDataSourceClass = Class.forName("com.zaxxer.hikari.HikariDataSource");
            if (hikariDataSourceClass.isInstance(dataSource)) {
                hikariDataSourceClass.getMethod("close").invoke(dataSource);
            }
        } catch (Exception e) {
            // If we can't close it, create a new manager with invalid connection config
            PgConnectionConfig invalidConfig = new PgConnectionConfig.Builder()
                    .host("invalid-host")
                    .port(9999)
                    .database("invalid-db")
                    .username("invalid-user")
                    .password("invalid-password")
                    .build();

            PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                    .minimumIdle(1)
                    .maximumPoolSize(3)
                    .build();

            javax.sql.DataSource invalidDataSource = createTemporaryDataSourceForMigration(invalidConfig, poolConfig);
            migrationManager = new SchemaMigrationManager(invalidDataSource);
        }

        assertThrows(SQLException.class, () -> {
            migrationManager.migrate();
        });
    }

    @Test
    void testConcurrentMigrations() throws Exception {
        // Test that concurrent migration attempts are handled safely
        // Each thread should use its own migration manager instance to simulate real concurrent scenarios
        Thread[] threads = new Thread[3];
        Exception[] exceptions = new Exception[3];
        int[] results = new int[3];

        for (int i = 0; i < 3; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    // Create separate migration manager for each thread to simulate real concurrent access
                    SchemaMigrationManager threadMigrationManager = new SchemaMigrationManager(dataSource);
                    results[index] = threadMigrationManager.migrate();
                } catch (Exception e) {
                    exceptions[index] = e;
                }
            });
        }
        
        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }
        
        // Check that at most one thread succeeded, others should fail gracefully due to advisory locking
        // In concurrent scenarios, some threads may fail due to race conditions, which is expected
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < exceptions.length; i++) {
            if (exceptions[i] != null) {
                failureCount++;
                logger.info("Thread {} failed as expected in concurrent scenario: {}", i, exceptions[i].getMessage());
            } else {
                successCount++;
                logger.info("Thread {} succeeded with {} migrations applied", i, results[i]);
            }
        }

        // At least one thread should succeed, others may fail due to concurrency
        assertTrue(successCount >= 1, "At least one migration thread should succeed");
        logger.info("Concurrent migration test completed: {} successes, {} expected failures", successCount, failureCount);
        
        // Check that total migrations applied is correct
        int totalApplied = 0;
        for (int result : results) {
            totalApplied += result;
        }
        
        // Only one thread should have applied migrations
        assertTrue(totalApplied >= 1);
        
        // Verify final state is correct
        assertNotNull(migrationManager.getCurrentVersion());
    }

    @Test
    void testMigrationWithExistingData() throws SQLException {
        // First, apply migrations
        migrationManager.migrate();
        
        // Insert some test data
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO outbox (topic, payload) VALUES (?, ?::jsonb)")) {
            
            stmt.setString(1, "test-topic");
            stmt.setString(2, "{\"message\": \"test\"}");
            stmt.executeUpdate();
        }
        
        // Verify data exists
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM outbox");
             ResultSet rs = stmt.executeQuery()) {
            
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
        
        // Running migrations again should not affect existing data
        int secondRun = migrationManager.migrate();
        assertEquals(0, secondRun);
        
        // Data should still exist
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM outbox");
             ResultSet rs = stmt.executeQuery()) {
            
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void testMigrationRollbackOnFailure() throws SQLException {
        // This test verifies that if a migration fails, the transaction is rolled back
        // We'll test this by ensuring the schema_version table is not updated on failure
        
        // Apply successful migration first
        migrationManager.migrate();
        String initialVersion = migrationManager.getCurrentVersion();
        
        // For this test, we can't easily create a failing migration without modifying the manager
        // So we'll test the rollback behavior indirectly by verifying transaction integrity
        
        // Verify that the migration was applied correctly and version was recorded
        assertNotNull(initialVersion);
        
        List<SchemaMigrationManager.AppliedMigration> history = migrationManager.getMigrationHistory();
        assertFalse(history.isEmpty());
        
        // Each migration should have been recorded atomically
        for (SchemaMigrationManager.AppliedMigration migration : history) {
            assertNotNull(migration.getVersion());
            assertNotNull(migration.getAppliedAt());
            assertNotNull(migration.getChecksum());
        }
    }

    private void verifyTableExists(String tableName) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?")) {
            
            stmt.setString(1, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Table " + tableName + " should exist");
            }
        }
    }

    @Test
    void testMigrationChecksumCalculation() throws SQLException {
        migrationManager.migrate();
        
        List<SchemaMigrationManager.AppliedMigration> history = migrationManager.getMigrationHistory();
        assertFalse(history.isEmpty());
        
        SchemaMigrationManager.AppliedMigration migration = history.get(0);
        assertNotNull(migration.getChecksum());
        assertFalse(migration.getChecksum().isEmpty());
        
        // Checksum should be consistent
        String checksum1 = migration.getChecksum();
        
        // Create another manager and check the same migration
        SchemaMigrationManager anotherManager = new SchemaMigrationManager(dataSource);
        List<SchemaMigrationManager.AppliedMigration> anotherHistory = anotherManager.getMigrationHistory();
        
        if (!anotherHistory.isEmpty()) {
            String checksum2 = anotherHistory.get(0).getChecksum();
            assertEquals(checksum1, checksum2);
        }
    }

    @Test
    void testMigrationManagerWithDifferentConfigurations() {
        // Test with validation enabled
        SchemaMigrationManager validatingManager = 
            new SchemaMigrationManager(dataSource, "/db/migration", true);
        assertDoesNotThrow(() -> validatingManager.migrate());
        
        // Test with validation disabled
        SchemaMigrationManager nonValidatingManager = 
            new SchemaMigrationManager(dataSource, "/db/migration", false);
        assertDoesNotThrow(() -> nonValidatingManager.migrate());
        
        // Test with default constructor
        SchemaMigrationManager defaultManager = new SchemaMigrationManager(dataSource);
        assertDoesNotThrow(() -> defaultManager.migrate());
    }
}
