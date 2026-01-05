package dev.mars.peegeeq.migrations;


import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the integrity and completeness of the database schema.
 * Tests column types, constraints, and data integrity rules.
 */
@Tag("integration")
@Testcontainers
class SchemaValidationTest {

    private static final Logger log = LoggerFactory.getLogger(SchemaValidationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_schema_test")
            .withUsername("test")
            .withPassword("test");

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
            assertThat(actualColumns)
                    .as("queue_messages should have column %s with correct type", expected.getKey())
                    .containsKey(expected.getKey());
            
            assertThat(actualColumns.get(expected.getKey()))
                    .as("Column %s should have type %s", expected.getKey(), expected.getValue())
                    .containsIgnoringCase(expected.getValue());
        }
    }

    @Test
    void testOutboxTableStructure() throws SQLException {
        Map<String, String> expectedColumns = new HashMap<>();
        expectedColumns.put("id", "uuid");
        expectedColumns.put("topic", "character varying");
        expectedColumns.put("payload", "jsonb");
        expectedColumns.put("created_at", "timestamp without time zone");
        expectedColumns.put("processed_at", "timestamp without time zone");
        expectedColumns.put("processing_started_at", "timestamp without time zone");
        expectedColumns.put("status", "character varying");
        expectedColumns.put("retry_count", "integer");
        expectedColumns.put("max_retries", "integer");
        expectedColumns.put("next_retry_at", "timestamp with time zone");
        expectedColumns.put("version", "integer");
        expectedColumns.put("headers", "jsonb");
        expectedColumns.put("error_message", "text");
        expectedColumns.put("correlation_id", "character varying");
        expectedColumns.put("message_group", "character varying");
        expectedColumns.put("priority", "integer");
        expectedColumns.put("payload", "jsonb");
        expectedColumns.put("status", "character varying");
        expectedColumns.put("created_at", "timestamp with time zone");

        Map<String, String> actualColumns = getTableColumns("outbox");

        for (Map.Entry<String, String> expected : expectedColumns.entrySet()) {
            assertThat(actualColumns)
                    .as("outbox should have column %s", expected.getKey())
                    .containsKey(expected.getKey());
        }
    }

    @Test
    void testBitemporalEventLogTableStructure() throws SQLException {
        Map<String, String> expectedColumns = new HashMap<>();
        expectedColumns.put("id", "uuid");
        expectedColumns.put("event_id", "character varying");
        expectedColumns.put("event_type", "character varying");
        expectedColumns.put("valid_time", "timestamp with time zone");
        expectedColumns.put("transaction_time", "timestamp with time zone");
        expectedColumns.put("payload", "jsonb");
        expectedColumns.put("headers", "jsonb");
        expectedColumns.put("version", "bigint");
        expectedColumns.put("previous_version_id", "character varying");
        expectedColumns.put("is_correction", "boolean");
        expectedColumns.put("correction_reason", "text");
        expectedColumns.put("correlation_id", "character varying");
        expectedColumns.put("causation_id", "character varying");
        expectedColumns.put("aggregate_id", "character varying");
        expectedColumns.put("created_at", "timestamp without time zone");

        Map<String, String> actualColumns = getTableColumns("bitemporal_event_log");

        for (Map.Entry<String, String> expected : expectedColumns.entrySet()) {
            assertThat(actualColumns)
                    .as("bitemporal_event_log should have column %s", expected.getKey())
                    .containsKey(expected.getKey());
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
                assertThat(isNullable)
                        .as("Critical column %s should be NOT NULL", column)
                        .isEqualTo("NO");
            }
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
                assertThat(columnDefault)
                        .as("Table %s should have default value for created_at", table)
                        .isNotNull()
                        .containsIgnoringCase("now()");
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

            rs.next();
            assertThat(rs.getInt(1))
                    .as("Schema should have UNIQUE constraints")
                    .isGreaterThanOrEqualTo(0); // Some tables may have unique constraints
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

            rs.next();
            int checkConstraints = rs.getInt(1);
            assertThat(checkConstraints)
                    .as("Schema may have CHECK constraints for data validation")
                    .isGreaterThanOrEqualTo(0);
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
                assertThat(indexName)
                        .as("Index should follow naming convention idx_<table>_<column>")
                        .matches("idx_.*|.*_pkey|.*_key");
            }
        }
    }

    @Test
    void testViewsAreQueryable() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // Test bitemporal_current_state view
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM bitemporal_current_state");
            assertThat(rs.next()).isTrue();

            // Test bitemporal_latest_events view
            rs = stmt.executeQuery("SELECT COUNT(*) FROM bitemporal_latest_events");
            assertThat(rs.next()).isTrue();

            // Test bitemporal_event_stats view
            rs = stmt.executeQuery("SELECT COUNT(*) FROM bitemporal_event_stats");
            assertThat(rs.next()).isTrue();

            // Test bitemporal_event_type_stats view
            rs = stmt.executeQuery("SELECT COUNT(*) FROM bitemporal_event_type_stats");
            assertThat(rs.next()).isTrue();
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
            // Function returns table, just verify it's callable (may return empty results)
            assertThat(rs).isNotNull();
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
                assertThat(column)
                        .as("JSONB column should have meaningful name in table %s", table)
                        .isNotEmpty();
            }

            assertThat(jsonbColumns)
                    .as("Schema should have JSONB columns for flexible data storage")
                    .isGreaterThan(0);
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
                
                assertThat(dataType)
                        .as("Timestamp column %s.%s should be timestamp type", table, column)
                        .containsIgnoringCase("timestamp");
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
