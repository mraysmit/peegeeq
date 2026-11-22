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
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

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
 *
 * <p>Flyway connects through JDBC; that is the behaviour under test. The tests read and
 * write the resulting schema through a Vert.x pool.</p>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class SchemaContractTest {

    private static final Logger log = LoggerFactory.getLogger(SchemaContractTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private Pool pool;

    @BeforeAll
    static void setup() {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:src/main/resources/db/migration")  // Use main migrations
                .cleanDisabled(false)
                .load();

        flyway.clean();
        flyway.migrate();

        log.info(" Migrations applied for contract testing");
    }

    @BeforeEach
    void setUp(Vertx vertx) {
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

    /**
     * Validates queue_messages table matches expectations from peegeeq-native module.
     *
     * Source: peegeeq-native/src/test/java/.../NativeQueueIntegrationTest.java
     * Source: peegeeq-db/src/main/java/.../PeeGeeQDatabaseSetupService.java
     */
    @Test
    void testQueueMessagesTableContract(VertxTestContext testContext) {
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

        validateTableColumns("queue_messages", requiredColumns)
            // Validate CHECK constraint on status
            .compose(v -> validateCheckConstraint("queue_messages", "status",
                List.of("AVAILABLE", "LOCKED", "PROCESSED", "FAILED", "DEAD_LETTER")))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    /**
     * Validates outbox table matches expectations from peegeeq-outbox module.
     *
     * Source: peegeeq-outbox/src/main/java/.../OutboxProducer.java (INSERT statements)
     * Source: peegeeq-outbox/src/main/java/.../OutboxConsumer.java (UPDATE/SELECT statements)
     */
    @Test
    void testOutboxTableContract(VertxTestContext testContext) {
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

        validateTableColumns("outbox", requiredColumns)
            // Validate CHECK constraint on status
            .compose(v -> validateCheckConstraint("outbox", "status",
                List.of("PENDING", "PROCESSING", "COMPLETED", "FAILED", "DEAD_LETTER")))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    /**
     * Validates dead_letter_queue table matches expectations.
     *
     * Source: peegeeq-db health checks and error handling
     */
    @Test
    void testDeadLetterQueueTableContract(VertxTestContext testContext) {
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

        validateTableColumns("dead_letter_queue", requiredColumns)
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    /**
     * Validates V010 fanout tables exist and have correct structure.
     *
     * Source: peegeeq-migrations V010 migration
     */
    @Test
    void testV010FanoutTablesContract(VertxTestContext testContext) {
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

        // outbox_topic_subscriptions
        Map<String, String> subscriptionsColumns = Map.of(
            "id", "bigint",
            "topic", "character varying",
            "group_name", "character varying",
            "durable_enabled", "boolean",
            "subscription_status", "character varying",
            "subscribed_at", "timestamp with time zone",
            "last_active_at", "timestamp with time zone"
        );

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

        validateTableColumns("outbox_topics", topicsColumns)
            .compose(v -> validateTableColumns("outbox_topic_subscriptions", subscriptionsColumns))
            .compose(v -> validateTableColumns("outbox_consumer_groups", consumerGroupsColumns))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testV012BitemporalDurableSubscriptionsContract(VertxTestContext testContext) {
        log.info("TEST: Validating V012 bitemporal durable subscriptions contract...");

        Map<String, String> durableColumns = Map.ofEntries(
            Map.entry("id", "bigint"),
            Map.entry("table_name", "character varying"),
            Map.entry("subscription_name", "character varying"),
            Map.entry("consumer_group", "character varying"),
            Map.entry("event_type", "character varying"),
            Map.entry("aggregate_id", "character varying"),
            Map.entry("subscription_status", "character varying"),
            Map.entry("start_from_event_id", "bigint"),
            Map.entry("last_processed_id", "bigint"),
            Map.entry("last_processed_at", "timestamp with time zone"),
            Map.entry("heartbeat_interval_seconds", "integer"),
            Map.entry("heartbeat_timeout_seconds", "integer"),
            Map.entry("last_heartbeat_at", "timestamp with time zone"),
            Map.entry("subscribed_at", "timestamp with time zone"),
            Map.entry("last_active_at", "timestamp with time zone")
        );

        validateTableColumns("bitemporal_subscriptions", durableColumns)
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
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
    void testCriticalIndexesExist(VertxTestContext testContext) {
        log.info("TEST: Validating critical indexes exist...");

        List<String> criticalIndexes = List.of(
            "idx_outbox_status_created",
            "idx_outbox_fanout_completion",
            "idx_queue_messages_topic_visible",
            "idx_queue_messages_status",
            "idx_outbox_consumer_groups_message_id",
            "idx_outbox_consumer_groups_group_status",
            "idx_bitemporal_event_log_id_type_agg",
            "idx_bitemporal_subs_table_status"
        );

        String sql = "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = $1";
        Future<Void> chain = Future.succeededFuture();
        for (String indexName : criticalIndexes) {
            chain = chain
                .compose(v -> queryCount(sql, Tuple.of(indexName)))
                .compose(count -> {
                    assertEquals(1L, count, "Critical index " + indexName + " must exist");
                    log.info("   Index exists: {}", indexName);
                    return Future.<Void>succeededFuture();
                });
        }
        chain
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    /**
     * Validates functions used by application code exist.
     */
    @Test
    void testRequiredFunctionsExist(VertxTestContext testContext) {
        log.info("TEST: Validating required functions exist...");

        List<String> requiredFunctions = List.of(
            "register_consumer_group_for_existing_messages",
            "cleanup_completed_message_processing",
            "update_message_processing_updated_at"
        );

        String sql = "SELECT COUNT(*) FROM pg_proc WHERE proname = $1 "
            + "AND pronamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')";
        Future<Void> chain = Future.succeededFuture();
        for (String functionName : requiredFunctions) {
            chain = chain
                .compose(v -> queryCount(sql, Tuple.of(functionName)))
                .compose(count -> {
                    assertTrue(count > 0, "Required function " + functionName + " must exist");
                    log.info("   Function exists: {}", functionName);
                    return Future.<Void>succeededFuture();
                });
        }
        chain
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    // ========== Helper Methods ==========

    private Future<Void> validateTableColumns(String tableName, Map<String, String> requiredColumns) {
        String sql = "SELECT column_name::text, data_type::text FROM information_schema.columns "
            + "WHERE table_schema = 'public' AND table_name = $1";

        return pool.preparedQuery(sql)
            .execute(Tuple.of(tableName))
            .compose(rows -> {
                Map<String, String> actualColumns = new HashMap<>();
                for (Row row : rows) {
                    actualColumns.put(row.getString("column_name"), row.getString("data_type"));
                }

                for (Map.Entry<String, String> required : requiredColumns.entrySet()) {
                    String columnName = required.getKey();
                    String expectedType = required.getValue();

                    assertTrue(actualColumns.containsKey(columnName),
                        "Table " + tableName + " must have column " + columnName);

                    String actualType = actualColumns.get(columnName);
                    assertEquals(expectedType, actualType,
                        "Column " + tableName + "." + columnName + " must be type " + expectedType);

                    log.debug("   {}.{} : {}", tableName, columnName, actualType);
                }
                return Future.<Void>succeededFuture();
            });
    }

    /**
     * Inserts one row per allowed value, then deletes the test rows, in one transaction.
     * A value that the CHECK constraint rejects fails the transaction and the test.
     */
    private Future<Void> validateCheckConstraint(String tableName, String columnName, List<String> allowedValues) {
        String insertSql = String.format(
            "INSERT INTO %s (%s, topic, payload) VALUES ($1, 'test-topic', '{}'::jsonb)",
            tableName, columnName);
        String deleteSql = String.format("DELETE FROM %s WHERE topic = 'test-topic'", tableName);

        return pool.withTransaction(connection -> {
            Future<Void> chain = Future.succeededFuture();
            for (String value : allowedValues) {
                chain = chain
                    .compose(v -> connection.preparedQuery(insertSql).execute(Tuple.of(value)))
                    .compose(rows -> {
                        log.debug("   {}.{} accepts value: {}", tableName, columnName, value);
                        return Future.<Void>succeededFuture();
                    });
            }
            return chain.compose(v -> connection.query(deleteSql).execute().<Void>mapEmpty());
        });
    }

    private Future<Long> queryCount(String sql, Tuple params) {
        return pool.preparedQuery(sql)
            .execute(params)
            .map(rows -> rows.iterator().next().getLong(0));
    }
}
