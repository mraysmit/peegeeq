package dev.mars.peegeeq.migrations;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PeeGeeQ database migrations.
 * Tests verify that all migrations execute successfully and create the expected schema.
 *
 * <p>Flyway connects through JDBC; that is the behaviour under test. The tests read the
 * resulting schema through a Vert.x pool.</p>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class MigrationIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MigrationIntegrationTest.class);

    private static final String COLUMNS_SQL =
            "SELECT column_name::text FROM information_schema.columns WHERE table_name = '%s' ORDER BY ordinal_position";

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private static Flyway flyway;

    private Vertx vertx;
    private Pool pool;

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

    @BeforeEach
    void setUp(Vertx vertx) {
        this.vertx = vertx;
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        pool = PgBuilder.pool()
            .with(new PoolOptions().setMaxSize(2).setShared(false))
            .connectingTo(connectOptions)
            .using(vertx)
            .build();
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        pool.close()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
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

        assertTrue(result.success, "Migration should succeed");
        assertTrue(result.migrationsExecuted > 0, "At least one migration should run, ran " + result.migrationsExecuted);
        assertEquals(List.of(), result.warnings, "Migration should report no warnings");

        log.info(" Migration execution successful");
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

        assertTrue(firstResult.success, "First migration run should succeed");
        assertTrue(secondResult.success, "Second migration run should succeed");
        assertEquals(0, secondResult.migrationsExecuted, "No new migrations should run on the second run");

        log.info(" Migrations are idempotent - no changes on second run");
    }

    @Test
    void testAllRequiredTablesExist(VertxTestContext testContext) {
        log.info("TEST: Verifying all required tables exist...");
        List<String> expectedTables = List.of(
                "queue_messages",
                "outbox",
                "outbox_consumer_groups",
                "dead_letter_queue",
                "bitemporal_event_log",
                "bitemporal_subscriptions",
                "message_processing",
                "outbox_topics",  // V010
                "outbox_topic_subscriptions"  // V010
        );

        cleanAndMigrate()
            .compose(v -> queryStrings("SELECT tablename::text FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename"))
            .onSuccess(actualTables -> testContext.verify(() -> {
                assertEquals(List.of(), missing(expectedTables, actualTables), "All required tables should exist");

                // V019 (2026-08-09): the write-only metrics persistence tables are DROPPED. Their
                // ABSENCE is the contract now - recreating them without a reader reintroduces dead
                // weight (queue_metrics was written on a timer and read by nothing, ever).
                assertFalse(actualTables.contains("queue_metrics"), "V019 must have dropped the write-only metrics tables");
                assertFalse(actualTables.contains("connection_pool_metrics"), "V019 must have dropped the write-only metrics tables");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testV010FanoutColumnsExist(VertxTestContext testContext) {
        log.info("TEST: Verifying V010 fanout columns added to outbox table...");
        cleanAndMigrate()
            .compose(v -> queryStrings(
                "SELECT column_name::text FROM information_schema.columns " +
                "WHERE table_name = 'outbox' AND column_name IN " +
                "('required_consumer_groups', 'completed_consumer_groups', 'completed_groups_bitmap')"))
            .onSuccess(foundColumns -> testContext.verify(() -> {
                assertEquals(
                    List.of("completed_consumer_groups", "completed_groups_bitmap", "required_consumer_groups"),
                    foundColumns.stream().sorted().collect(Collectors.toList()),
                    "V010 fanout columns should exist in outbox table");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testV010OutboxTopicsTableStructure(VertxTestContext testContext) {
        log.info("TEST: Verifying outbox_topics table structure...");
        // Key columns from V010
        List<String> expectedColumns = List.of(
                "topic",
                "semantics",
                "message_retention_hours",
                "zero_subscription_retention_hours",
                "block_writes_on_zero_subscriptions",
                "completion_tracking_mode",
                "created_at",
                "updated_at");

        assertColumnsPresent(testContext, "outbox_topics", expectedColumns,
            "outbox_topics should have all expected columns");
    }

    @Test
    void testV010OutboxTopicSubscriptionsTableStructure(VertxTestContext testContext) {
        log.info("TEST: Verifying outbox_topic_subscriptions table structure...");
        // Key columns from V010
        List<String> expectedColumns = List.of(
                "id",
                "topic",
                "group_name",
                "durable_enabled",
                "subscription_status",
                "subscribed_at",
                "last_active_at",
                "heartbeat_interval_seconds",
                "heartbeat_timeout_seconds",
                "last_heartbeat_at",
                "backfill_status");

        assertColumnsPresent(testContext, "outbox_topic_subscriptions", expectedColumns,
            "outbox_topic_subscriptions should have all expected columns");
    }

    @Test
    void testV012BitemporalSubscriptionsTableStructure(VertxTestContext testContext) {
        log.info("TEST: Verifying bitemporal_subscriptions table structure...");
        List<String> expectedColumns = List.of(
                "id",
                "table_name",
                "subscription_name",
                "consumer_group",
                "event_type",
                "aggregate_id",
                "subscription_status",
                "start_from_event_id",
                "last_processed_id",
                "last_processed_at",
                "heartbeat_interval_seconds",
                "heartbeat_timeout_seconds",
                "last_heartbeat_at",
                "subscribed_at",
                "last_active_at");

        assertColumnsPresent(testContext, "bitemporal_subscriptions", expectedColumns,
            "bitemporal_subscriptions should have all expected columns");
    }

    @Test
    void testV010ConsumerGroupsColumnRenamed(VertxTestContext testContext) {
        log.info("TEST: Verifying outbox_consumer_groups column rename from V010...");
        cleanAndMigrate()
            .compose(v -> queryStrings(String.format(COLUMNS_SQL, "outbox_consumer_groups")))
            .onSuccess(columns -> testContext.verify(() -> {
                // Should have new column name, not old
                assertTrue(columns.contains("message_id"), "Should use message_id (new name)");
                assertTrue(columns.contains("group_name"), "Should use group_name (new name)");

                assertFalse(columns.contains("outbox_message_id"), "Should NOT have old column outbox_message_id");
                assertFalse(columns.contains("consumer_group_name"), "Should NOT have old column consumer_group_name");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testAllRequiredIndexesExist(VertxTestContext testContext) {
        log.info("TEST: Verifying all required indexes exist...");
        // Critical indexes that must exist
        List<String> requiredIndexes = List.of(
                "idx_queue_messages_status",
                "idx_queue_messages_topic_visible",
                "idx_outbox_status_created",
                "idx_outbox_next_retry",
                "uq_bitemporal_event_id",
                "idx_bitemporal_valid_time",
                "idx_bitemporal_latest_events",
                "idx_bitemporal_event_log_id_type_agg",
                "idx_bitemporal_subs_table_status",
                "idx_bitemporal_no_fork");

        cleanAndMigrate()
            .compose(v -> queryStrings("SELECT indexname::text FROM pg_indexes WHERE schemaname = 'public' ORDER BY indexname"))
            .onSuccess(actualIndexes -> testContext.verify(() -> {
                assertEquals(List.of(), missing(requiredIndexes, actualIndexes), "All required indexes should exist");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testAllRequiredViewsExist(VertxTestContext testContext) {
        List<String> expectedViews = List.of(
                "bitemporal_current_state",
                "bitemporal_latest_events",
                "bitemporal_event_stats",
                "bitemporal_event_type_stats");

        cleanAndMigrate()
            .compose(v -> queryStrings("SELECT viewname::text FROM pg_views WHERE schemaname = 'public' ORDER BY viewname"))
            .onSuccess(actualViews -> testContext.verify(() -> {
                assertEquals(List.of(), missing(expectedViews, actualViews), "All required views should exist");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testAllRequiredFunctionsExist(VertxTestContext testContext) {
        List<String> expectedFunctions = List.of(
                "notify_message_inserted",
                "update_message_processing_updated_at",
                "cleanup_completed_message_processing",
                "register_consumer_group_for_existing_messages",
                "create_consumer_group_entries_for_new_message",
                "cleanup_completed_outbox_messages",
                "notify_bitemporal_event",
                "get_events_as_of_time");

        cleanAndMigrate()
            .compose(v -> queryStrings("SELECT proname::text FROM pg_proc " +
                "WHERE pronamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public') " +
                "ORDER BY proname"))
            .onSuccess(actualFunctions -> testContext.verify(() -> {
                assertEquals(List.of(), missing(expectedFunctions, actualFunctions), "All required functions should exist");

                // V019 (2026-08-09): cleanup_old_metrics was the retention function for the write-only
                // metrics tables — defined, never invoked by anything, dropped with its tables. Its
                // ABSENCE is the contract now.
                assertFalse(actualFunctions.contains("cleanup_old_metrics"),
                        "V019 must have dropped the never-invoked metrics retention function");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testAllRequiredTriggersExist(VertxTestContext testContext) {
        List<String> expectedTriggers = List.of(
                "trigger_outbox_notify",
                "trigger_queue_messages_notify",
                "trigger_message_processing_updated_at",
                "trigger_create_consumer_group_entries",
                "trigger_notify_bitemporal_event",
                "trigger_set_required_consumer_groups");

        cleanAndMigrate()
            .compose(v -> queryStrings("SELECT tgname::text FROM pg_trigger WHERE tgisinternal = false ORDER BY tgname"))
            .onSuccess(actualTriggers -> testContext.verify(() -> {
                assertEquals(List.of(), missing(expectedTriggers, actualTriggers), "All required triggers should exist");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testPrimaryKeysExist(VertxTestContext testContext) {
        String primaryKeyCountSql = "SELECT COUNT(*) FROM information_schema.table_constraints " +
                "WHERE table_name = '%s' AND constraint_type = 'PRIMARY KEY'";

        cleanAndMigrate()
            .compose(v -> queryCount(String.format(primaryKeyCountSql, "queue_messages")))
            .compose(count -> {
                assertEquals(1L, count, "queue_messages should have a primary key");
                return queryCount(String.format(primaryKeyCountSql, "outbox"));
            })
            .onSuccess(count -> testContext.verify(() -> {
                assertEquals(1L, count, "outbox should have a primary key");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testForeignKeysExist(VertxTestContext testContext) {
        cleanAndMigrate()
            .compose(v -> queryCount("SELECT COUNT(*) FROM information_schema.table_constraints " +
                "WHERE constraint_type = 'FOREIGN KEY'"))
            .onSuccess(count -> testContext.verify(() -> {
                assertTrue(count > 0, "Schema should have foreign key constraints");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testListenNotifyFunctionsWork(VertxTestContext testContext) {
        // Insert a test message to fire the notify trigger
        String insertSql = "INSERT INTO queue_messages (topic, payload, status, priority, created_at) " +
                "VALUES ('test_topic', '{\"test\": \"payload\"}'::jsonb, 'AVAILABLE', 5, NOW())";

        cleanAndMigrate()
            .compose(v -> pool.withTransaction(connection ->
                connection.query(insertSql).execute().mapEmpty()))
            // The insert succeeds only when the trigger function exists and runs
            .compose(v -> queryCount("SELECT COUNT(*) FROM queue_messages WHERE topic = 'test_topic'"))
            .onSuccess(count -> testContext.verify(() -> {
                assertEquals(1L, count, "Test message should be inserted successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testBitemporalEventLogStructure(VertxTestContext testContext) {
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
                "aggregate_id",
                "created_at");

        assertColumnsPresent(testContext, "bitemporal_event_log", expectedColumns,
            "bitemporal_event_log should have all required columns");
    }

    @Test
    void testDeadLetterQueueStructure(VertxTestContext testContext) {
        List<String> expectedColumns = List.of(
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
                "message_group");

        assertColumnsPresent(testContext, "dead_letter_queue", expectedColumns,
            "dead_letter_queue should have all required columns");
    }

    @Test
    void testSchemaCommentsExist(VertxTestContext testContext) {
        cleanAndMigrate()
            .compose(v -> queryCount("SELECT COUNT(*) FROM pg_description d " +
                "JOIN pg_class c ON d.objoid = c.oid WHERE c.relkind = 'r'"))
            .onSuccess(count -> testContext.verify(() -> {
                assertTrue(count > 0, "Tables should have comments for documentation");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    // Helper methods

    /** Cleans and migrates the public schema. Flyway blocks, so it runs on a worker thread. */
    private Future<Void> cleanAndMigrate() {
        return vertx.executeBlocking(() -> {
            flyway.clean();
            flyway.migrate();
            return null;
        }, false).mapEmpty();
    }

    private void assertColumnsPresent(VertxTestContext testContext, String tableName,
                                      List<String> expectedColumns, String message) {
        cleanAndMigrate()
            .compose(v -> queryStrings(String.format(COLUMNS_SQL, tableName)))
            .onSuccess(columns -> testContext.verify(() -> {
                assertEquals(List.of(), missing(expectedColumns, columns), message);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    private static List<String> missing(List<String> expected, List<String> actual) {
        return expected.stream().filter(name -> !actual.contains(name)).collect(Collectors.toList());
    }

    private Future<List<String>> queryStrings(String sql) {
        return pool.query(sql)
            .execute()
            .map(rows -> {
                List<String> values = new ArrayList<>();
                for (Row row : rows) {
                    values.add(row.getString(0));
                }
                return values;
            });
    }

    private Future<Long> queryCount(String sql) {
        return pool.query(sql)
            .execute()
            .map(rows -> rows.iterator().next().getLong(0));
    }
}
