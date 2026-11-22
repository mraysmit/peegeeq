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
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the integrity and completeness of the database schema.
 * Tests column types, constraints, and data integrity rules.
 *
 * <p>Flyway connects through JDBC; that is the behaviour under test. The tests read the
 * resulting schema through a Vert.x pool.</p>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class SchemaValidationTest {

    private static final Logger log = LoggerFactory.getLogger(SchemaValidationTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private Pool pool;

    @BeforeAll
    static void setupFlyway() {
        log.info("=== Starting Schema Validation Tests ===");
        log.info("PostgreSQL container started: {}", postgres.getJdbcUrl());

        long startTime = System.currentTimeMillis();

        // Set PostgreSQL statement timeout to prevent hanging
        String jdbcUrl = postgres.getJdbcUrl() + "?options=-c%20statement_timeout=60000";

        Flyway flyway = Flyway.configure()
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

    @Test
    void testQueueMessagesTableStructure(VertxTestContext testContext) {
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

        getTableColumns("queue_messages")
            .onSuccess(actualColumns -> testContext.verify(() -> {
                for (Map.Entry<String, String> expected : expectedColumns.entrySet()) {
                    assertTrue(actualColumns.containsKey(expected.getKey()),
                            "queue_messages should have column " + expected.getKey());

                    String actualType = actualColumns.get(expected.getKey());
                    assertTrue(actualType.toLowerCase().contains(expected.getValue().toLowerCase()),
                            "Column " + expected.getKey() + " should have type " + expected.getValue() + " but was " + actualType);
                }
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testOutboxTableStructure(VertxTestContext testContext) {
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

        getTableColumns("outbox")
            .onSuccess(actualColumns -> testContext.verify(() -> {
                for (String expected : expectedColumns) {
                    assertTrue(actualColumns.containsKey(expected), "outbox should have column " + expected);
                }
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testBitemporalEventLogTableStructure(VertxTestContext testContext) {
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

        getTableColumns("bitemporal_event_log")
            .onSuccess(actualColumns -> testContext.verify(() -> {
                for (String expected : expectedColumns) {
                    assertTrue(actualColumns.containsKey(expected), "bitemporal_event_log should have column " + expected);
                }
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testNotNullConstraints(VertxTestContext testContext) {
        // Check critical NOT NULL constraints on actual table columns
        query("SELECT column_name::text, is_nullable::text FROM information_schema.columns " +
                "WHERE table_name = 'queue_messages' " +
                "AND column_name IN ('id', 'topic', 'payload') " +
                "ORDER BY column_name")
            .compose(rows -> {
                assertEquals(3, rows.size(), "queue_messages should have the id, topic and payload columns");
                for (Row row : rows) {
                    assertEquals("NO", row.getString("is_nullable"),
                            "Critical column " + row.getString("column_name") + " should be NOT NULL");
                }
                return query("SELECT is_nullable::text FROM information_schema.columns " +
                        "WHERE table_name = 'dead_letter_queue' AND column_name = 'failed_at'");
            })
            .onSuccess(rows -> testContext.verify(() -> {
                assertEquals(1, rows.size(), "dead_letter_queue.failed_at column should exist");
                assertEquals("NO", rows.iterator().next().getString("is_nullable"),
                        "dead_letter_queue.failed_at should be NOT NULL");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testDefaultValues(VertxTestContext testContext) {
        // Check that created_at columns have default values (exclude views)
        query("SELECT c.table_name::text, c.column_default::text " +
                "FROM information_schema.columns c " +
                "JOIN information_schema.tables t ON c.table_name = t.table_name " +
                "WHERE c.column_name = 'created_at' " +
                "AND c.table_schema = 'public' " +
                "AND t.table_type = 'BASE TABLE' " +
                "ORDER BY c.table_name")
            .onSuccess(rows -> testContext.verify(() -> {
                assertTrue(rows.size() > 0, "Some base tables should have a created_at column");
                for (Row row : rows) {
                    String table = row.getString("table_name");
                    String columnDefault = row.getString("column_default");
                    assertNotNull(columnDefault, "Table " + table + " should have default value for created_at");
                    assertTrue(columnDefault.toLowerCase().contains("now()"),
                            "Table " + table + " created_at default should call now(), was " + columnDefault);
                }
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testUniqueConstraints(VertxTestContext testContext) {
        queryCount("SELECT COUNT(*) FROM information_schema.table_constraints " +
                "WHERE constraint_type = 'UNIQUE' AND table_schema = 'public'")
            .onSuccess(count -> testContext.verify(() -> {
                assertTrue(count > 0, "Schema should have UNIQUE constraints");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testCheckConstraints(VertxTestContext testContext) {
        queryCount("SELECT COUNT(*) FROM information_schema.table_constraints " +
                "WHERE constraint_type = 'CHECK' AND table_schema = 'public'")
            .onSuccess(count -> testContext.verify(() -> {
                assertTrue(count > 0, "Schema should have CHECK constraints for data validation");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testIndexesHaveProperNaming(VertxTestContext testContext) {
        query("SELECT indexname::text FROM pg_indexes " +
                "WHERE schemaname = 'public' " +
                "AND indexname NOT LIKE '%_pkey' " +
                "AND indexname NOT LIKE 'flyway_%' " +
                "ORDER BY indexname")
            .onSuccess(rows -> testContext.verify(() -> {
                assertTrue(rows.size() > 0, "The schema should have indexes to check");
                for (Row row : rows) {
                    String indexName = row.getString("indexname");
                    assertTrue(indexName.matches("idx_.*|uq_.*|.*_pkey|.*_key"),
                            "Index should follow naming convention idx_<table>_<column> or uq_<table>_<column>: " + indexName);
                }
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testViewsAreQueryable(VertxTestContext testContext) {
        // A view that cannot be queried fails the chain
        queryCount("SELECT COUNT(*) FROM bitemporal_current_state")
            .compose(count -> queryCount("SELECT COUNT(*) FROM bitemporal_latest_events"))
            .compose(count -> queryCount("SELECT COUNT(*) FROM bitemporal_event_stats"))
            .compose(count -> queryCount("SELECT COUNT(*) FROM bitemporal_event_type_stats"))
            .onSuccess(count -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testFunctionsAreCallable(VertxTestContext testContext) {
        // Test get_events_as_of_time function (takes 2 timestamp parameters with defaults)
        query("SELECT * FROM get_events_as_of_time(NOW(), NOW())")
            .onSuccess(rows -> testContext.verify(() -> {
                // This class inserts no bitemporal events, so the function returns no rows
                assertEquals(0, rows.size(), "get_events_as_of_time should return no rows for a schema with no events");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testJsonbColumnsExist(VertxTestContext testContext) {
        query("SELECT table_name::text, column_name::text FROM information_schema.columns " +
                "WHERE data_type = 'jsonb' " +
                "AND table_schema = 'public' " +
                "ORDER BY table_name, column_name")
            .onSuccess(rows -> testContext.verify(() -> {
                for (Row row : rows) {
                    assertFalse(row.getString("column_name").isEmpty(),
                            "JSONB column should have meaningful name in table " + row.getString("table_name"));
                }
                assertTrue(rows.size() > 0, "Schema should have JSONB columns for flexible data storage");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testTimestampColumnsHaveCorrectType(VertxTestContext testContext) {
        query("SELECT table_name::text, column_name::text, data_type::text FROM information_schema.columns " +
                "WHERE column_name LIKE '%_at' " +
                "AND table_schema = 'public' " +
                "ORDER BY table_name, column_name")
            .onSuccess(rows -> testContext.verify(() -> {
                assertTrue(rows.size() > 0, "Some columns should end in _at");
                for (Row row : rows) {
                    String dataType = row.getString("data_type");
                    assertTrue(dataType.toLowerCase().contains("timestamp"),
                            "Timestamp column " + row.getString("table_name") + "." + row.getString("column_name")
                                    + " should be timestamp type, was " + dataType);
                }
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    // Helper methods

    private Future<Map<String, String>> getTableColumns(String tableName) {
        return pool.preparedQuery("SELECT column_name::text, data_type::text FROM information_schema.columns " +
                "WHERE table_name = $1 AND table_schema = 'public' ORDER BY ordinal_position")
            .execute(Tuple.of(tableName))
            .map(rows -> {
                Map<String, String> columns = new HashMap<>();
                for (Row row : rows) {
                    columns.put(row.getString("column_name"), row.getString("data_type"));
                }
                return columns;
            });
    }

    private Future<RowSet<Row>> query(String sql) {
        return pool.query(sql).execute();
    }

    private Future<Long> queryCount(String sql) {
        return query(sql).map(rows -> rows.iterator().next().getLong(0));
    }
}
