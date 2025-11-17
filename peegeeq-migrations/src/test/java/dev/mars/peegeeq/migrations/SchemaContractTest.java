package dev.mars.peegeeq.migrations;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema Contract Test - Validates that migrations create schemas compatible with application modules.
 * 
 * This test ensures that:
 * 1. All columns expected by peegeeq-native, peegeeq-outbox, peegeeq-db exist
 * 2. Column types match what the application code expects
 * 3. Required indexes exist for performance-critical queries
 * 4. Functions and triggers used by application code are present
 * 
 * When this test fails, it indicates schema drift between migrations and application code.
 */
@Testcontainers
public class SchemaContractTest {

    private static final Logger log = LoggerFactory.getLogger(SchemaContractTest.class);

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("peegeeq_contract_test")
            .withUsername("test")
            .withPassword("test");

    private static Flyway flyway;

    @BeforeAll
    static void setup() {
        flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:src/test/resources/db/migration")  // Use test migrations (no CONCURRENTLY)
                .cleanDisabled(false)
                .load();

        flyway.clean();
        flyway.migrate();

        log.info("✓ Migrations applied for contract testing");
    }

    /**
     * Validates queue_messages table matches expectations from peegeeq-native module.
     * 
     * Source: peegeeq-native/src/test/java/.../NativeQueueIntegrationTest.java
     * Source: peegeeq-db/src/main/java/.../PeeGeeQDatabaseSetupService.java
     */
    @Test
    void testQueueMessagesTableContract() throws SQLException {
        log.info("TEST: Validating queue_messages table contract...");
        
        Map<String, String> requiredColumns = Map.ofEntries(
            Map.entry("id", "bigint"),
            Map.entry("topic", "character varying"),
            Map.entry("payload", "jsonb"),
            Map.entry("visible_at", "timestamp with time zone"),
            Map.entry("created_at", "timestamp with time zone"),
            Map.entry("lock_id", "bigint"),
            Map.entry("lock_until", "timestamp with time zone"),
            Map.entry("retry_count", "integer"),
            Map.entry("max_retries", "integer"),
            Map.entry("status", "character varying"),
            Map.entry("headers", "jsonb"),
            Map.entry("error_message", "text"),
            Map.entry("correlation_id", "character varying"),
            Map.entry("message_group", "character varying"),
            Map.entry("priority", "integer")
        );

        validateTableColumns("queue_messages", requiredColumns);
        
        // Validate CHECK constraint on status
        validateCheckConstraint("queue_messages", "status", 
            List.of("AVAILABLE", "LOCKED", "PROCESSED", "FAILED", "DEAD_LETTER"));
        
        log.info("✓ queue_messages table contract validated");
    }

    /**
     * Validates outbox table matches expectations from peegeeq-outbox module.
     * 
     * Source: peegeeq-outbox/src/main/java/.../OutboxProducer.java (INSERT statements)
     * Source: peegeeq-outbox/src/main/java/.../OutboxConsumer.java (UPDATE/SELECT statements)
     */
    @Test
    void testOutboxTableContract() throws SQLException {
        log.info("TEST: Validating outbox table contract...");
        
        Map<String, String> requiredColumns = Map.ofEntries(
            Map.entry("id", "bigint"),
            Map.entry("topic", "character varying"),
            Map.entry("payload", "jsonb"),
            Map.entry("created_at", "timestamp with time zone"),
            Map.entry("processed_at", "timestamp with time zone"),
            Map.entry("processing_started_at", "timestamp with time zone"),
            Map.entry("status", "character varying"),
            Map.entry("retry_count", "integer"),
            Map.entry("max_retries", "integer"),
            Map.entry("next_retry_at", "timestamp with time zone"),
            Map.entry("version", "integer"),
            Map.entry("headers", "jsonb"),
            Map.entry("error_message", "text"),
            Map.entry("correlation_id", "character varying"),
            Map.entry("message_group", "character varying"),
            Map.entry("priority", "integer"),
            // V010 fanout columns
            Map.entry("required_consumer_groups", "integer"),
            Map.entry("completed_consumer_groups", "integer"),
            Map.entry("completed_groups_bitmap", "bigint")
        );

        validateTableColumns("outbox", requiredColumns);
        
        // Validate CHECK constraint on status
        validateCheckConstraint("outbox", "status", 
            List.of("PENDING", "PROCESSING", "COMPLETED", "FAILED", "DEAD_LETTER"));
        
        log.info("✓ outbox table contract validated");
    }

    /**
     * Validates dead_letter_queue table matches expectations.
     * 
     * Source: peegeeq-db health checks and error handling
     */
    @Test
    void testDeadLetterQueueTableContract() throws SQLException {
        log.info("TEST: Validating dead_letter_queue table contract...");
        
        Map<String, String> requiredColumns = Map.ofEntries(
            Map.entry("id", "bigint"),
            Map.entry("original_table", "character varying"),
            Map.entry("original_id", "bigint"),
            Map.entry("topic", "character varying"),
            Map.entry("payload", "jsonb"),
            Map.entry("original_created_at", "timestamp with time zone"),
            Map.entry("failed_at", "timestamp with time zone"),
            Map.entry("failure_reason", "text"),
            Map.entry("retry_count", "integer"),
            Map.entry("headers", "jsonb"),
            Map.entry("correlation_id", "character varying"),
            Map.entry("message_group", "character varying")
        );

        validateTableColumns("dead_letter_queue", requiredColumns);
        
        log.info("✓ dead_letter_queue table contract validated");
    }

    /**
     * Validates V010 fanout tables exist and have correct structure.
     *
     * Source: peegeeq-migrations V010 migration
     */
    @Test
    void testV010FanoutTablesContract() throws SQLException {
        log.info("TEST: Validating V010 fanout tables contract...");

        // outbox_topics (uses topic as PRIMARY KEY, no separate id column)
        Map<String, String> topicsColumns = Map.of(
            "topic", "character varying",
            "semantics", "character varying",
            "message_retention_hours", "integer",
            "zero_subscription_retention_hours", "integer",
            "block_writes_on_zero_subscriptions", "boolean",
            "completion_tracking_mode", "character varying",
            "created_at", "timestamp with time zone",
            "updated_at", "timestamp with time zone"
        );
        validateTableColumns("outbox_topics", topicsColumns);

        // outbox_topic_subscriptions
        Map<String, String> subscriptionsColumns = Map.of(
            "id", "bigint",
            "topic", "character varying",
            "group_name", "character varying",
            "subscription_status", "character varying",
            "subscribed_at", "timestamp with time zone",
            "last_active_at", "timestamp with time zone"
        );
        validateTableColumns("outbox_topic_subscriptions", subscriptionsColumns);

        // outbox_consumer_groups (V010 renamed columns to message_id and group_name)
        Map<String, String> consumerGroupsColumns = Map.ofEntries(
            Map.entry("id", "bigint"),
            Map.entry("message_id", "bigint"),
            Map.entry("group_name", "character varying"),
            Map.entry("status", "character varying"),
            Map.entry("processed_at", "timestamp with time zone"),
            Map.entry("processing_started_at", "timestamp with time zone"),
            Map.entry("retry_count", "integer"),
            Map.entry("error_message", "text"),
            Map.entry("created_at", "timestamp with time zone")
        );
        validateTableColumns("outbox_consumer_groups", consumerGroupsColumns);

        log.info("✓ V010 fanout tables contract validated");
    }

    /**
     * Validates critical indexes exist for performance-critical queries.
     *
     * These indexes are used by:
     * - ConsumerGroupFetcher.fetchMessages() - idx_outbox_fanout_completion (V010)
     * - NativeQueueConsumer.claimMessages() - idx_queue_messages_topic_visible
     * - CompletionTracker - idx_outbox_consumer_groups_message_id
     */
    @Test
    void testCriticalIndexesExist() throws SQLException {
        log.info("TEST: Validating critical indexes exist...");

        List<String> criticalIndexes = List.of(
            "idx_outbox_status_created",
            "idx_outbox_fanout_completion",
            "idx_queue_messages_topic_visible",
            "idx_queue_messages_status",
            "idx_outbox_consumer_groups_message_id",
            "idx_outbox_consumer_groups_group_status"
        );

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {

            for (String indexName : criticalIndexes) {
                String sql = """
                    SELECT indexname FROM pg_indexes
                    WHERE schemaname = 'public' AND indexname = ?
                    """;

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, indexName);
                    try (ResultSet rs = stmt.executeQuery()) {
                        assertThat(rs.next())
                            .as("Critical index %s must exist", indexName)
                            .isTrue();
                        log.info("  ✓ Index exists: {}", indexName);
                    }
                }
            }
        }

        log.info("✓ All critical indexes validated");
    }

    /**
     * Validates functions used by application code exist.
     */
    @Test
    void testRequiredFunctionsExist() throws SQLException {
        log.info("TEST: Validating required functions exist...");

        List<String> requiredFunctions = List.of(
            "register_consumer_group_for_existing_messages",
            "cleanup_completed_message_processing",
            "update_message_processing_updated_at"
        );

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {

            for (String functionName : requiredFunctions) {
                String sql = """
                    SELECT proname FROM pg_proc
                    WHERE proname = ? AND pronamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')
                    """;

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, functionName);
                    try (ResultSet rs = stmt.executeQuery()) {
                        assertThat(rs.next())
                            .as("Required function %s must exist", functionName)
                            .isTrue();
                        log.info("  ✓ Function exists: {}", functionName);
                    }
                }
            }
        }

        log.info("✓ All required functions validated");
    }

    // ========== Helper Methods ==========

    private void validateTableColumns(String tableName, Map<String, String> requiredColumns) throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {

            String sql = """
                SELECT column_name, data_type
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                """;

            Map<String, String> actualColumns = new HashMap<>();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, tableName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        actualColumns.put(rs.getString("column_name"), rs.getString("data_type"));
                    }
                }
            }

            for (Map.Entry<String, String> required : requiredColumns.entrySet()) {
                String columnName = required.getKey();
                String expectedType = required.getValue();

                assertThat(actualColumns)
                    .as("Table %s must have column %s", tableName, columnName)
                    .containsKey(columnName);

                String actualType = actualColumns.get(columnName);
                assertThat(actualType)
                    .as("Column %s.%s must be type %s but was %s",
                        tableName, columnName, expectedType, actualType)
                    .isEqualTo(expectedType);

                log.debug("  ✓ {}.{} : {}", tableName, columnName, actualType);
            }
        }
    }

    private void validateCheckConstraint(String tableName, String columnName, List<String> allowedValues)
            throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {

            // Test that each allowed value can be inserted
            String insertSql = String.format(
                "INSERT INTO %s (%s, topic, payload) VALUES (?, 'test-topic', '{}'::jsonb)",
                tableName, columnName);
            String deleteSql = String.format("DELETE FROM %s WHERE topic = 'test-topic'", tableName);

            for (String value : allowedValues) {
                try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                    stmt.setString(1, value);
                    stmt.executeUpdate();
                    log.debug("  ✓ {}.{} accepts value: {}", tableName, columnName, value);
                }
            }

            // Cleanup
            try (PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
                stmt.executeUpdate();
            }
        }
    }
}

