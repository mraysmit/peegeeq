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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for PeeGeeQ database migrations.
 * Tests verify that all migrations execute successfully and create the expected schema.
 */
@Tag("integration")
@Testcontainers
class MigrationIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MigrationIntegrationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_migration_test")
            .withUsername("test")
            .withPassword("test");

    private static Flyway flyway;

    @BeforeAll
    static void setupFlyway() {
        log.info("=== Starting Migration Integration Tests ===");
        log.info("PostgreSQL container: {}", postgres.getJdbcUrl());
        log.info("Configuring Flyway...");
        
        // Set PostgreSQL statement timeout to prevent hanging
        String jdbcUrl = postgres.getJdbcUrl() + "?options=-c%20statement_timeout=60000";
        
        flyway = Flyway.configure()
                .dataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:src/main/resources/db/migration")
                .baselineOnMigrate(true)
                .cleanDisabled(false) // Allow clean in tests
                .mixed(false) // Not needed for regular CREATE INDEX
                .connectRetries(10)
                .load();
        
        log.info("Flyway configured successfully with statement timeout");
    }

    @Test
    void testMigrationsExecuteSuccessfully() {
        log.info("TEST: Executing migrations...");
        long startTime = System.currentTimeMillis();
        
        // Clean and migrate
        log.info("  Cleaning database...");
        flyway.clean();
        
        log.info("  Running migrations...");
        var result = flyway.migrate();
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("  Completed in {}ms - {} migrations executed", duration, result.migrationsExecuted);

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThan(0);
        assertThat(result.warnings).isEmpty();
        
        log.info("✓ Migration execution successful");
    }

    @Test
    void testMigrationsAreIdempotent() {
        log.info("TEST: Testing migration idempotency...");
        // Run migrations twice - should be idempotent
        flyway.clean();
        log.info("  First migration run...");
        var firstResult = flyway.migrate();
        log.info("  Second migration run...");
        var secondResult = flyway.migrate();

        assertThat(firstResult.success).isTrue();
        assertThat(secondResult.success).isTrue();
        assertThat(secondResult.migrationsExecuted).isZero(); // No new migrations on second run
        
        log.info("✓ Migrations are idempotent - no changes on second run");
    }

    @Test
    void testAllRequiredTablesExist() throws SQLException {
        log.info("TEST: Verifying all required tables exist...");
        flyway.clean();
        flyway.migrate();

        List<String> expectedTables = List.of(
                "queue_messages",
                "outbox",
                "outbox_consumer_groups",
                "dead_letter_queue",
                "bitemporal_event_log",
                "queue_metrics",
                "connection_pool_metrics",
                "message_processing",
                "outbox_topics",  // V010
                "outbox_topic_subscriptions"  // V010
        );

        List<String> actualTables = getTableNames();

        assertThat(actualTables)
                .as("All required tables should exist")
                .containsAll(expectedTables);
        
        log.info("✓ All {} required tables verified (including V010 tables)", expectedTables.size());
    }

    @Test
    void testV010FanoutColumnsExist() throws SQLException {
        log.info("TEST: Verifying V010 fanout columns added to outbox table...");
        flyway.clean();
        flyway.migrate();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            
            // Check for V010 fanout columns
            ResultSet rs = stmt.executeQuery(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_name = 'outbox' AND column_name IN " +
                "('required_consumer_groups', 'completed_consumer_groups', 'completed_groups_bitmap')"
            );
            
            List<String> foundColumns = new ArrayList<>();
            while (rs.next()) {
                foundColumns.add(rs.getString(1));
            }
            
            assertThat(foundColumns)
                .as("V010 fanout columns should exist in outbox table")
                .containsExactlyInAnyOrder(
                    "required_consumer_groups",
                    "completed_consumer_groups", 
                    "completed_groups_bitmap"
                );
        }
        
        log.info("✓ All V010 fanout columns verified");
    }

    @Test
    void testV010OutboxTopicsTableStructure() throws SQLException {
        log.info("TEST: Verifying outbox_topics table structure...");
        flyway.clean();
        flyway.migrate();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            
            ResultSet rs = stmt.executeQuery(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_name = 'outbox_topics' ORDER BY ordinal_position"
            );
            
            List<String> columns = new ArrayList<>();
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
            
            // Key columns from V010
            assertThat(columns)
                .as("outbox_topics should have all expected columns")
                .contains(
                    "topic",
                    "semantics",
                    "message_retention_hours",
                    "zero_subscription_retention_hours",
                    "block_writes_on_zero_subscriptions",
                    "completion_tracking_mode",
                    "created_at",
                    "updated_at"
                );
        }
        
        log.info("✓ outbox_topics table structure verified");
    }

    @Test
    void testV010OutboxTopicSubscriptionsTableStructure() throws SQLException {
        log.info("TEST: Verifying outbox_topic_subscriptions table structure...");
        flyway.clean();
        flyway.migrate();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            
            ResultSet rs = stmt.executeQuery(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_name = 'outbox_topic_subscriptions' ORDER BY ordinal_position"
            );
            
            List<String> columns = new ArrayList<>();
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
            
            // Key columns from V010
            assertThat(columns)
                .as("outbox_topic_subscriptions should have all expected columns")
                .contains(
                    "id",
                    "topic",
                    "group_name",
                    "subscription_status",
                    "subscribed_at",
                    "last_active_at",
                    "heartbeat_interval_seconds",
                    "heartbeat_timeout_seconds",
                    "last_heartbeat_at",
                    "backfill_status"
                );
        }
        
        log.info("✓ outbox_topic_subscriptions table structure verified");
    }

    @Test
    void testV010ConsumerGroupsColumnRenamed() throws SQLException {
        log.info("TEST: Verifying outbox_consumer_groups column rename from V010...");
        flyway.clean();
        flyway.migrate();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            
            ResultSet rs = stmt.executeQuery(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_name = 'outbox_consumer_groups'"
            );
            
            List<String> columns = new ArrayList<>();
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
            
            // Should have new column name, not old
            assertThat(columns)
                .as("Should use message_id (new name)")
                .contains("message_id", "group_name");
                
            assertThat(columns)
                .as("Should NOT have old column names")
                .doesNotContain("outbox_message_id", "consumer_group_name");
        }
        
        log.info("✓ Column rename verified - using message_id and group_name");
    }

    @Test
    void testAllRequiredIndexesExist() throws SQLException {
        log.info("TEST: Verifying all required indexes exist...");
        flyway.clean();
        flyway.migrate();

        List<String> actualIndexes = getIndexNames();

        // Critical indexes that must exist
        List<String> requiredIndexes = List.of(
                "idx_queue_messages_status",
                "idx_queue_messages_topic_visible",
                "idx_outbox_status_created",
                "idx_outbox_next_retry",
                "idx_bitemporal_event_id",
                "idx_bitemporal_valid_time",
                "idx_bitemporal_latest_events"
        );

        assertThat(actualIndexes)
                .as("All required indexes should exist")
                .containsAll(requiredIndexes);
    }

    @Test
    void testAllRequiredViewsExist() throws SQLException {
        flyway.clean();
        flyway.migrate();

        List<String> expectedViews = List.of(
                "bitemporal_current_state",
                "bitemporal_latest_events",
                "bitemporal_event_stats",
                "bitemporal_event_type_stats"
        );

        List<String> actualViews = getViewNames();

        assertThat(actualViews)
                .as("All required views should exist")
                .containsAll(expectedViews);
    }

    @Test
    void testAllRequiredFunctionsExist() throws SQLException {
        flyway.clean();
        flyway.migrate();

        List<String> expectedFunctions = List.of(
                "notify_message_inserted",
                "update_message_processing_updated_at",
                "cleanup_completed_message_processing",
                "register_consumer_group_for_existing_messages",
                "create_consumer_group_entries_for_new_message",
                "cleanup_completed_outbox_messages",
                "notify_bitemporal_event",
                "cleanup_old_metrics",
                "get_events_as_of_time"
        );

        List<String> actualFunctions = getFunctionNames();

        assertThat(actualFunctions)
                .as("All required functions should exist")
                .containsAll(expectedFunctions);
    }

    @Test
    void testAllRequiredTriggersExist() throws SQLException {
        flyway.clean();
        flyway.migrate();

        List<String> expectedTriggers = List.of(
                "trigger_outbox_notify",
                "trigger_queue_messages_notify",
                "trigger_message_processing_updated_at",
                "trigger_create_consumer_group_entries",
                "trigger_notify_bitemporal_event",
                "trigger_set_required_consumer_groups"
        );

        List<String> actualTriggers = getTriggerNames();

        assertThat(actualTriggers)
                .as("All required triggers should exist")
                .containsAll(expectedTriggers);
    }

    @Test
    void testPrimaryKeysExist() throws SQLException {
        flyway.clean();
        flyway.migrate();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // Check queue_messages primary key
            ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.table_constraints " +
                            "WHERE table_name = 'queue_messages' " +
                            "AND constraint_type = 'PRIMARY KEY'"
            );
            rs.next();
            assertThat(rs.getInt(1))
                    .as("queue_messages should have a primary key")
                    .isEqualTo(1);

            // Check outbox primary key
            rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.table_constraints " +
                            "WHERE table_name = 'outbox' " +
                            "AND constraint_type = 'PRIMARY KEY'"
            );
            rs.next();
            assertThat(rs.getInt(1))
                    .as("outbox should have a primary key")
                    .isEqualTo(1);
        }
    }

    @Test
    void testForeignKeysExist() throws SQLException {
        flyway.clean();
        flyway.migrate();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.table_constraints " +
                            "WHERE constraint_type = 'FOREIGN KEY'"
            );
            rs.next();
            assertThat(rs.getInt(1))
                    .as("Schema should have foreign key constraints")
                    .isGreaterThan(0);
        }
    }

    @Test
    void testListenNotifyFunctionsWork() throws SQLException {
        flyway.clean();
        flyway.migrate();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // Insert a test message to trigger NOTIFY
            stmt.execute(
                    "INSERT INTO queue_messages (topic, payload, status, priority, created_at) " +
                            "VALUES ('test_topic', '{\"test\": \"payload\"}'::jsonb, 'AVAILABLE', 5, NOW())"
            );

            // Verify trigger fired (function exists and is callable)
            ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM queue_messages WHERE topic = 'test_topic'"
            );
            rs.next();
            assertThat(rs.getInt(1))
                    .as("Test message should be inserted successfully")
                    .isEqualTo(1);
        }
    }

    @Test
    void testBitemporalEventLogStructure() throws SQLException {
        flyway.clean();
        flyway.migrate();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // Verify bitemporal_event_log has required columns
            ResultSet rs = stmt.executeQuery(
                    "SELECT column_name FROM information_schema.columns " +
                            "WHERE table_name = 'bitemporal_event_log' " +
                            "ORDER BY ordinal_position"
            );

            List<String> columns = new ArrayList<>();
            while (rs.next()) {
                columns.add(rs.getString(1));
            }

            assertThat(columns)
                    .as("bitemporal_event_log should have all required columns")
                    .contains(
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
                            "aggregate_id",
                            "created_at"
                    );
        }
    }

    @Test
    void testDeadLetterQueueStructure() throws SQLException {
        flyway.clean();
        flyway.migrate();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery(
                    "SELECT column_name FROM information_schema.columns " +
                            "WHERE table_name = 'dead_letter_queue' " +
                            "ORDER BY ordinal_position"
            );

            List<String> columns = new ArrayList<>();
            while (rs.next()) {
                columns.add(rs.getString(1));
            }

            assertThat(columns)
                    .as("dead_letter_queue should have all required columns")
                    .contains(
                            "id",
                            "original_table",
                            "original_id",
                            "topic",
                            "payload",
                            "original_created_at",
                            "failed_at",
                            "failure_reason",
                            "retry_count",
                            "headers",
                            "correlation_id",
                            "message_group"
                    );
        }
    }

    @Test
    void testSchemaCommentsExist() throws SQLException {
        flyway.clean();
        flyway.migrate();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // Check table comments
            ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM pg_description d " +
                            "JOIN pg_class c ON d.objoid = c.oid " +
                            "WHERE c.relkind = 'r'"
            );
            rs.next();
            assertThat(rs.getInt(1))
                    .as("Tables should have comments for documentation")
                    .isGreaterThan(0);
        }
    }

    // Helper methods

    private List<String> getTableNames() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                    "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename"
             )) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    private List<String> getIndexNames() throws SQLException {
        List<String> indexes = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                    "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' ORDER BY indexname"
             )) {
            while (rs.next()) {
                indexes.add(rs.getString(1));
            }
        }
        return indexes;
    }

    private List<String> getViewNames() throws SQLException {
        List<String> views = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                    "SELECT viewname FROM pg_views WHERE schemaname = 'public' ORDER BY viewname"
             )) {
            while (rs.next()) {
                views.add(rs.getString(1));
            }
        }
        return views;
    }

    private List<String> getFunctionNames() throws SQLException {
        List<String> functions = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                    "SELECT proname FROM pg_proc " +
                            "WHERE pronamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public') " +
                            "ORDER BY proname"
             )) {
            while (rs.next()) {
                functions.add(rs.getString(1));
            }
        }
        return functions;
    }

    private List<String> getTriggerNames() throws SQLException {
        List<String> triggers = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                    "SELECT tgname FROM pg_trigger " +
                            "WHERE tgisinternal = false " +
                            "ORDER BY tgname"
             )) {
            while (rs.next()) {
                triggers.add(rs.getString(1));
            }
        }
        return triggers;
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
    }
}
