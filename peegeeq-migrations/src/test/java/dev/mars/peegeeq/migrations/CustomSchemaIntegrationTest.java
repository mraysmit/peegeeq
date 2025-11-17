package dev.mars.peegeeq.migrations;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for custom schema deployment scenarios.
 * Verifies that migrations can be deployed to named schemas instead of the default 'public' schema.
 */
@Testcontainers
class CustomSchemaIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CustomSchemaIntegrationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_custom_schema_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    void testMigrationsToCustomSchemaViaJdbcUrl() throws SQLException {
        log.info("TEST: Deploying migrations to custom schema via JDBC URL parameter");
        String customSchema = "peegeeq_custom";
        
        // Configure Flyway to use custom schema via JDBC URL parameter
        String jdbcUrl = postgres.getJdbcUrl() + "?currentSchema=" + customSchema + "&options=-c%20statement_timeout=60000";
        
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword())
                .schemas(customSchema)
                .createSchemas(true)
                .locations("filesystem:src/test/resources/db/migration")
                .baselineOnMigrate(true)
                .cleanDisabled(false)
                .mixed(false)
                .connectRetries(10)
                .load();

        log.info("  Running migrations to schema: {}", customSchema);
        var result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThan(0);
        
        log.info("  Verifying tables in custom schema...");
        verifyTablesInSchema(customSchema);
        verifyTablesNotInPublicSchema();
        
        log.info("✓ Migrations successfully deployed to custom schema via JDBC URL");
    }

    @Test
    void testMigrationsToCustomSchemaViaDefaultSchema() throws SQLException {
        log.info("TEST: Deploying migrations to custom schema via defaultSchema configuration");
        String customSchema = "peegeeq_configured";
        
        // Configure Flyway with explicit defaultSchema
        String jdbcUrl = postgres.getJdbcUrl() + "?options=-c%20statement_timeout=60000";
        
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword())
                .defaultSchema(customSchema)
                .schemas(customSchema)
                .createSchemas(true)
                .locations("filesystem:src/test/resources/db/migration")
                .baselineOnMigrate(true)
                .cleanDisabled(false)
                .mixed(false)
                .connectRetries(10)
                .load();

        log.info("  Running migrations with defaultSchema: {}", customSchema);
        var result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThan(0);
        
        log.info("  Verifying tables in configured schema...");
        verifyTablesInSchema(customSchema);
        
        log.info("✓ Migrations successfully deployed to custom schema via defaultSchema config");
    }

    @Test
    void testMultipleSchemaIsolation() throws SQLException {
        log.info("TEST: Testing schema isolation - deploying to multiple schemas in same database");
        
        String schema1 = "peegeeq_tenant1";
        String schema2 = "peegeeq_tenant2";
        
        // Deploy to first schema
        log.info("  Deploying to schema: {}", schema1);
        deployToSchema(schema1);
        
        // Deploy to second schema
        log.info("  Deploying to schema: {}", schema2);
        deployToSchema(schema2);
        
        // Verify both schemas have tables
        log.info("  Verifying schema isolation...");
        verifyTablesInSchema(schema1);
        verifyTablesInSchema(schema2);
        
        // Verify schemas are isolated (data in one doesn't affect the other)
        verifySchemaIsolation(schema1, schema2);
        
        log.info("✓ Multiple schema isolation verified successfully");
    }

    @Test
    void testFlywayHistoryTableInCustomSchema() throws SQLException {
        log.info("TEST: Verifying Flyway history table is created in custom schema");
        String customSchema = "peegeeq_history";
        
        deployToSchema(customSchema);
        
        // Verify flyway_schema_history exists in custom schema
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), 
                postgres.getUsername(), 
                postgres.getPassword())) {
            
            String query = "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = ? AND table_name = ?";
            
            try (var stmt = conn.prepareStatement(query)) {
                stmt.setString(1, customSchema);
                stmt.setString(2, "flyway_schema_history");
                
                ResultSet rs = stmt.executeQuery();
                assertThat(rs.next()).isTrue();
                int count = rs.getInt(1);
                
                assertThat(count)
                        .as("flyway_schema_history should exist in custom schema")
                        .isEqualTo(1);
            }
            
            // Verify it's NOT in public schema
            try (var stmt = conn.prepareStatement(query)) {
                stmt.setString(1, "public");
                stmt.setString(2, "flyway_schema_history");
                
                ResultSet rs = stmt.executeQuery();
                assertThat(rs.next()).isTrue();
                int count = rs.getInt(1);
                
                assertThat(count)
                        .as("flyway_schema_history should NOT be in public schema")
                        .isEqualTo(0);
            }
        }
        
        log.info("✓ Flyway history table correctly placed in custom schema");
    }

    @Test
    void testSchemaCreatedAutomatically() throws SQLException {
        log.info("TEST: Verifying custom schema is created automatically if it doesn't exist");
        String nonExistentSchema = "peegeeq_autocreate";
        
        // Verify schema doesn't exist yet
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), 
                postgres.getUsername(), 
                postgres.getPassword())) {
            
            assertThat(schemaExists(conn, nonExistentSchema))
                    .as("Schema should not exist before migration")
                    .isFalse();
        }
        
        // Deploy migrations - should auto-create schema
        log.info("  Deploying migrations (schema should be auto-created)...");
        deployToSchema(nonExistentSchema);
        
        // Verify schema was created
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), 
                postgres.getUsername(), 
                postgres.getPassword())) {
            
            assertThat(schemaExists(conn, nonExistentSchema))
                    .as("Schema should be auto-created during migration")
                    .isTrue();
        }
        
        log.info("✓ Custom schema auto-created successfully");
    }

    // Helper methods

    private void deployToSchema(String schemaName) {
        String jdbcUrl = postgres.getJdbcUrl() + "?options=-c%20statement_timeout=60000";
        
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword())
                .defaultSchema(schemaName)
                .schemas(schemaName)
                .createSchemas(true)
                .locations("filesystem:src/test/resources/db/migration")
                .baselineOnMigrate(true)
                .cleanDisabled(false)
                .mixed(false)
                .connectRetries(10)
                .load();

        var result = flyway.migrate();
        assertThat(result.success).isTrue();
    }

    private void verifyTablesInSchema(String schemaName) throws SQLException {
        List<String> expectedTables = List.of(
                "schema_version",
                "outbox",
                "outbox_consumer_groups",
                "queue_messages",
                "message_processing",
                "dead_letter_queue",
                "queue_metrics",
                "connection_pool_metrics",
                "bitemporal_event_log"
        );

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), 
                postgres.getUsername(), 
                postgres.getPassword())) {

            for (String tableName : expectedTables) {
                String query = "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_schema = ? AND table_name = ?";
                
                try (var stmt = conn.prepareStatement(query)) {
                    stmt.setString(1, schemaName);
                    stmt.setString(2, tableName);
                    
                    ResultSet rs = stmt.executeQuery();
                    assertThat(rs.next()).isTrue();
                    
                    int count = rs.getInt(1);
                    assertThat(count)
                            .as("Table %s should exist in schema %s", tableName, schemaName)
                            .isEqualTo(1);
                }
            }
        }
    }

    private void verifyTablesNotInPublicSchema() throws SQLException {
        List<String> peegeeqTables = List.of(
                "outbox",
                "queue_messages",
                "dead_letter_queue"
        );

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), 
                postgres.getUsername(), 
                postgres.getPassword())) {

            for (String tableName : peegeeqTables) {
                String query = "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_schema = 'public' AND table_name = ?";
                
                try (var stmt = conn.prepareStatement(query)) {
                    stmt.setString(1, tableName);
                    
                    ResultSet rs = stmt.executeQuery();
                    assertThat(rs.next()).isTrue();
                    
                    int count = rs.getInt(1);
                    assertThat(count)
                            .as("Table %s should NOT exist in public schema", tableName)
                            .isEqualTo(0);
                }
            }
        }
    }

    private void verifySchemaIsolation(String schema1, String schema2) throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), 
                postgres.getUsername(), 
                postgres.getPassword())) {

            // Insert data into schema1
            try (var stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO " + schema1);
                stmt.execute("INSERT INTO outbox (topic, payload) VALUES ('test-topic', '{\"test\": \"data1\"}'::jsonb)");
            }

            // Insert data into schema2
            try (var stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO " + schema2);
                stmt.execute("INSERT INTO outbox (topic, payload) VALUES ('test-topic', '{\"test\": \"data2\"}'::jsonb)");
            }

            // Verify schema1 has only its data
            try (var stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO " + schema1);
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM outbox WHERE payload->>'test' = 'data1'");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1))
                        .as("Schema1 should have its own data")
                        .isEqualTo(1);
            }

            // Verify schema2 has only its data
            try (var stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO " + schema2);
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM outbox WHERE payload->>'test' = 'data2'");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1))
                        .as("Schema2 should have its own data")
                        .isEqualTo(1);
            }

            // Verify schema1 does NOT have schema2's data
            try (var stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO " + schema1);
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM outbox WHERE payload->>'test' = 'data2'");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1))
                        .as("Schema1 should NOT have schema2's data")
                        .isEqualTo(0);
            }
        }
    }

    private boolean schemaExists(Connection conn, String schemaName) throws SQLException {
        String query = "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?";
        try (var stmt = conn.prepareStatement(query)) {
            stmt.setString(1, schemaName);
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        }
    }
}
