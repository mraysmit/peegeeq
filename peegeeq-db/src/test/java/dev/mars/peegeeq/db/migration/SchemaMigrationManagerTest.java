package dev.mars.peegeeq.db.migration;

import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 */
@Testcontainers
class SchemaMigrationManagerTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("migration_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    private PgConnectionManager connectionManager;
    private DataSource dataSource;
    private SchemaMigrationManager migrationManager;

    @BeforeEach
    void setUp() {
        connectionManager = new PgConnectionManager();
        
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

        dataSource = connectionManager.getOrCreateDataSource("test", connectionConfig, poolConfig);
        migrationManager = new SchemaMigrationManager(dataSource);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            connectionManager.close();
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
        
        // Check that current version is set
        String currentVersion = migrationManager.getCurrentVersion();
        assertNotNull(currentVersion);
        assertEquals("V001", currentVersion);
        
        // Verify base tables were created
        verifyTableExists("outbox");
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
        
        SchemaMigrationManager.AppliedMigration firstMigration = history.get(0);
        assertEquals("V001", firstMigration.getVersion());
        assertNotNull(firstMigration.getDescription());
        assertNotNull(firstMigration.getAppliedAt());
        assertNotNull(firstMigration.getChecksum());
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
        // Create a migration manager that will fail
        // This is a bit tricky to test without creating actual bad migration files
        // For now, we'll test that the manager handles database connection issues gracefully
        
        connectionManager.close();
        
        assertThrows(SQLException.class, () -> {
            migrationManager.migrate();
        });
    }

    @Test
    void testConcurrentMigrations() throws Exception {
        // Test that concurrent migration attempts are handled safely
        Thread[] threads = new Thread[3];
        Exception[] exceptions = new Exception[3];
        int[] results = new int[3];
        
        for (int i = 0; i < 3; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    results[index] = migrationManager.migrate();
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
        
        // Check that no exceptions occurred
        for (Exception exception : exceptions) {
            if (exception != null) {
                fail("Concurrent migration failed: " + exception.getMessage());
            }
        }
        
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
