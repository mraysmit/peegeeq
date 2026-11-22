package dev.mars.peegeeq.migrations;


import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the integrity and completeness of the database schema.
 * Tests column types, constraints, and data integrity rules.
 */
@Tag("integration")
@Testcontainers
class SchemaValidationTest {

    private static final Logger log = LoggerFactory.getLogger(SchemaValidationTest.class);

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_schema_test");
        container.withUsername("test");
        container.withPassword("test");
        return container;
    }

    private static Flyway flyway;

    @BeforeAll
    static void setupFlyway() {
        log.info("=== Starting Schema Validation Tests ===");
        log.info("PostgreSQL container started: {}", postgres.getJdbcUrl());
        
        long startTime = System.currentTimeMillis();
        
        // Set PostgreSQL statement timeout to prevent hanging
        String jdbcUrl = postgres.getJdbcUrl() + "?options=-c%20statement_timeout=60000";
        
        flyway = Flyway.configure()
                .dataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:src/main/resources/db/migration")
                .baselineOnMigrate(true)
                .cleanDisabled(false)
                .mixed(false) // Not needed for regular CREATE INDEX
                .connectRetries(10)
                .load();

        log.info("Cleaning and migrating database...");
        flyway.clean();
        flyway.migrate();
        long duration = System.currentTimeMillis() - startTime;
        log.info("Schema setup completed in {}ms", duration);
    }

    @Test
    void testQueueMessagesTableStructure() throws SQLException {
        Map<String, String> expectedColumns = new HashMap<>();
        expectedColumns.put("id", "bigint");
        expectedColumns.put("topic", "character varying");
        expectedColumns.put("payload", "jsonb");
        expectedColumns.put("visible_at", "timestamp with time zone");
        expectedColumns.put("created_at", "timestamp with time zone");
        expectedColumns.put("lock_id", "bigint");
        expectedColumns.put("lock_until", "timestamp with time zone");
        expectedColumns.put("retry_count", "integer");
        expectedColumns.put("max_retries", "integer");
        expectedColumns.put("status", "character varying");
        expectedColumns.put("headers", "jsonb");
        expectedColumns.put("error_message", "text");
        expectedColumns.put("correlation_id", "character varying");
        expectedColumns.put("message_group", "character varying");
        expectedColumns.put("priority", "integer");

        Map<String, String> actualColumns = getTableColumns("queue_messages");

        for (Map.Entry<String, String> expected : expectedColumns.entrySet()) {
            assertTrue(actualColumns.containsKey(expected.getKey()),
                    "queue_messages should have column " + expected.getKey());

            String actualType = actualColumns.get(expected.getKey());
            assertTrue(actualType.toLowerCase().contains(expected.getValue().toLowerCase()),
                    "Column " + expected.getKey() + " should have type " + expected.getValue() + " but was " + actualType);
        }
    }

    @Test
    void testOutboxTableStructure() throws SQLException {
        // Column names only; SchemaContractTest asserts the outbox column types.
        List<String> expectedColumns = List.of(
                "id",
                "topic",
                "payload",
                "created_at",
                "processed_at",
                "processing_started_at",
                "status",
                "retry_count",
                "max_retries",
                "next_retry_at",
                "version",
                "headers",
                "error_message",
                "correlation_id",
                "message_group",
                "priority");

        Map<String, String> actualColumns = getTableColumns("outbox");

        for (String expected : expectedColumns) {
            assertTrue(actualColumns.containsKey(expected), "outbox should have column " + expected);
        }
    }

    @Test
    void testBitemporalEventLogTableStructure() throws SQLException {
        // Column names only; the types are not asserted here.
        List<String> expectedColumns = List.of(
                "id",
                "event_id",
                "event_type",
                "valid_time",
                "transaction_time",
                "payload",
                "headers",
                "version",
                "previous_version_id",
                "is_correction",
                "correction_reason",
                "correlation_id",
                "causation_id",
                "aggregate_id",
                "created_at");

        Map<String, String> actualColumns = getTableColumns("bitemporal_event_log");

        for (String expected : expectedColumns) {
            assertTrue(actualColumns.containsKey(expected), "bitemporal_event_log should have column " + expected);
        }
    }

    @Test
    void testNotNullConstraints() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // Check critical NOT NULL constraints on actual table columns
            ResultSet rs = stmt.executeQuery(
                    "SELECT column_name, is_nullable FROM information_schema.columns " +
                            "WHERE table_name = 'queue_messages' " +
                            "AND column_name IN ('id', 'topic', 'payload') " +
                            "ORDER BY column_name"
            );

            while (rs.next()) {
                String column = rs.getString("column_name");
                String isNullable = rs.getString("is_nullable");
                assertEquals("NO", isNullable, "Critical column " + column + " should be NOT NULL");
            }

                ResultSet dlqRs = stmt.executeQuery(
                    "SELECT is_nullable FROM information_schema.columns " +
                        "WHERE table_name = 'dead_letter_queue' " +
                        "AND column_name = 'failed_at'"
                );

                assertTrue(dlqRs.next(), "dead_letter_queue.failed_at column should exist");

                assertEquals("NO", dlqRs.getString("is_nullable"), "dead_letter_queue.failed_at should be NOT NULL");
        }
    }

    @Test
    void testDefaultValues() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // Check that created_at columns have default values (exclude views)
            ResultSet rs = stmt.executeQuery(
                    "SELECT c.table_name, c.column_name, c.column_default " +
                            "FROM information_schema.columns c " +
                            "JOIN information_schema.tables t ON c.table_name = t.table_name " +
                            "WHERE c.column_name = 'created_at' " +
                            "AND c.table_schema = 'public' " +
                            "AND t.table_type = 'BASE TABLE' " +
                            "ORDER BY c.table_name"
            );

            while (rs.next()) {
                String table = rs.getString("table_name");
                String columnDefault = rs.getString("column_default");
                assertNotNull(columnDefault, "Table " + table + " should have default value for created_at");
                assertTrue(columnDefault.toLowerCase().contains("now()"),
                        "Table " + table + " created_at default should call now(), was " + columnDefault);
            }
        }
    }

    @Test
    void testUniqueConstraints() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.table_constraints " +
                            "WHERE constraint_type = 'UNIQUE' " +
                            "AND table_schema = 'public'"
            );

            assertTrue(rs.next(), "COUNT(*) should return a row");
            assertTrue(rs.getInt(1) > 0, "Schema should have UNIQUE constraints");
        }
    }

    @Test
    void testCheckConstraints() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.table_constraints " +
                            "WHERE constraint_type = 'CHECK' " +
                            "AND table_schema = 'public'"
            );

            assertTrue(rs.next(), "COUNT(*) should return a row");
            int checkConstraints = rs.getInt(1);
            assertTrue(checkConstraints > 0, "Schema should have CHECK constraints for data validation");
        }
    }

    @Test
    void testIndexesHaveProperNaming() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery(
                    "SELECT indexname FROM pg_indexes " +
                            "WHERE schemaname = 'public' " +
                            "AND indexname NOT LIKE '%_pkey' " +
                            "AND indexname NOT LIKE 'flyway_%' " +
                            "ORDER BY indexname"
            );

            while (rs.next()) {
                String indexName = rs.getString("indexname");
                assertTrue(indexName.matches("idx_.*|uq_.*|.*_pkey|.*_key"),
                        "Index should follow naming convention idx_<table>_<column> or uq_<table>_<column>: " + indexName);
            }
        }
    }

    @Test
    void testViewsAreQueryable() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // Test bitemporal_current_state view
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM bitemporal_current_state");
            assertTrue(rs.next(), "COUNT(*) on bitemporal_current_state should return a row");

            // Test bitemporal_latest_events view
            rs = stmt.executeQuery("SELECT COUNT(*) FROM bitemporal_latest_events");
            assertTrue(rs.next(), "COUNT(*) on bitemporal_latest_events should return a row");

            // Test bitemporal_event_stats view
            rs = stmt.executeQuery("SELECT COUNT(*) FROM bitemporal_event_stats");
            assertTrue(rs.next(), "COUNT(*) on bitemporal_event_stats should return a row");

            // Test bitemporal_event_type_stats view
            rs = stmt.executeQuery("SELECT COUNT(*) FROM bitemporal_event_type_stats");
            assertTrue(rs.next(), "COUNT(*) on bitemporal_event_type_stats should return a row");
        }
    }

    @Test
    void testFunctionsAreCallable() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // Test get_events_as_of_time function (takes 2 timestamp parameters with defaults)
            ResultSet rs = stmt.executeQuery(
                    "SELECT * FROM get_events_as_of_time(NOW(), NOW())"
            );
            // This class inserts no bitemporal events, so the function returns no rows
            assertFalse(rs.next(), "get_events_as_of_time should return no rows for a schema with no events");
        }
    }

    @Test
    void testJsonbColumnsExist() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery(
                    "SELECT table_name, column_name FROM information_schema.columns " +
                            "WHERE data_type = 'jsonb' " +
                            "AND table_schema = 'public' " +
                            "ORDER BY table_name, column_name"
            );

            int jsonbColumns = 0;
            while (rs.next()) {
                jsonbColumns++;
                String table = rs.getString("table_name");
                String column = rs.getString("column_name");
                assertFalse(column.isEmpty(), "JSONB column should have meaningful name in table " + table);
            }

            assertTrue(jsonbColumns > 0, "Schema should have JSONB columns for flexible data storage");
        }
    }

    @Test
    void testTimestampColumnsHaveCorrectType() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery(
                    "SELECT table_name, column_name, data_type FROM information_schema.columns " +
                            "WHERE column_name LIKE '%_at' " +
                            "AND table_schema = 'public' " +
                            "ORDER BY table_name, column_name"
            );

            while (rs.next()) {
                String table = rs.getString("table_name");
                String column = rs.getString("column_name");
                String dataType = rs.getString("data_type");
                
                assertTrue(dataType.toLowerCase().contains("timestamp"),
                        "Timestamp column " + table + "." + column + " should be timestamp type, was " + dataType);
            }
        }
    }

    // Helper methods

    private Map<String, String> getTableColumns(String tableName) throws SQLException {
        Map<String, String> columns = new HashMap<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                    String.format(
                            "SELECT column_name, data_type FROM information_schema.columns " +
                                    "WHERE table_name = '%s' AND table_schema = 'public' " +
                                    "ORDER BY ordinal_position",
                            tableName
                    )
             )) {
            while (rs.next()) {
                columns.put(rs.getString("column_name"), rs.getString("data_type"));
            }
        }
        return columns;
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
    }
}
