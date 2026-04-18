package dev.mars.peegeeq.outbox;

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

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TDD tests for M1: schema name interpolated into SQL without identifier quoting.
 *
 * <p>Current defect: SQL queries in {@link OutboxFactory} use
 * {@code "%s.outbox".formatted(schema)} without quoting the schema identifier.
 * While {@code PgConnectionManager.normalizeSearchPath()} rejects non-alphanumeric
 * characters (hyphens, etc.), SQL reserved words like "order", "select", "table"
 * pass validation — they are valid Java identifiers made of letters only —
 * but generate malformed SQL when used unquoted as schema identifiers.</p>
 *
 * <p>For example, schema "order" produces {@code FROM order.outbox} which PostgreSQL
 * parses as an ORDER BY clause start, not a schema-qualified table name. The fix is
 * to always quote: {@code FROM "order".outbox}.</p>
 *
 * <p>Additionally, {@code PeeGeeQTestSchemaInitializer.ensureBitemporalCompatibility()}
 * has the same quoting gap in its own SQL.</p>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@DisplayName("M1: Schema names with special characters must be properly quoted")
class OutboxSchemaQuotingTest {
    private static final Logger logger = LoggerFactory.getLogger(OutboxSchemaQuotingTest.class);


    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory factory;

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        if (factory != null) factory.close();
        if (manager != null) {
            manager.closeReactive()
                    .onSuccess(v -> testContext.completeNow())
                    .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    /**
     * Create schema and outbox table directly via JDBC with quoted DDL,
     * bypassing the test schema initializer which itself has quoting issues.
     * Table structure mirrors V001__Create_Base_Tables.sql.
     */
    private void createSchemaWithQuotedDDL(String schema) throws Exception {
        try (var conn = java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (var stmt = conn.createStatement()) {
                String quotedSchema = "\"" + schema.replace("\"", "\"\"") + "\"";
                stmt.execute("CREATE SCHEMA IF NOT EXISTS " + quotedSchema);
                stmt.execute("SET search_path TO " + quotedSchema);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS outbox (
                        id BIGSERIAL PRIMARY KEY,
                        topic VARCHAR(255) NOT NULL,
                        payload JSONB NOT NULL,
                        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        processed_at TIMESTAMP WITH TIME ZONE,
                        processing_started_at TIMESTAMP WITH TIME ZONE,
                        status VARCHAR(50) DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED','DEAD_LETTER')),
                        retry_count INT DEFAULT 0,
                        max_retries INT DEFAULT 3,
                        next_retry_at TIMESTAMP WITH TIME ZONE,
                        version INT DEFAULT 0,
                        headers JSONB DEFAULT '{}',
                        error_message TEXT,
                        correlation_id VARCHAR(255),
                        message_group VARCHAR(255),
                        priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10),
                        idempotency_key VARCHAR(255)
                    )
                """);
                stmt.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_outbox_idempotency_key
                    ON outbox(topic, idempotency_key) WHERE idempotency_key IS NOT NULL
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS outbox_consumer_groups (
                        id BIGSERIAL PRIMARY KEY,
                        outbox_message_id BIGINT NOT NULL REFERENCES outbox(id) ON DELETE CASCADE,
                        consumer_group_name VARCHAR(255) NOT NULL,
                        status VARCHAR(50) DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
                        processed_at TIMESTAMP WITH TIME ZONE,
                        processing_started_at TIMESTAMP WITH TIME ZONE,
                        retry_count INT DEFAULT 0,
                        error_message TEXT,
                        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        UNIQUE(outbox_message_id, consumer_group_name)
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS queue_messages (
                        id BIGSERIAL PRIMARY KEY,
                        topic VARCHAR(255) NOT NULL,
                        payload JSONB NOT NULL,
                        visible_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        lock_id BIGINT,
                        lock_until TIMESTAMP WITH TIME ZONE,
                        retry_count INT DEFAULT 0,
                        max_retries INT DEFAULT 3,
                        status VARCHAR(50) DEFAULT 'AVAILABLE'
                            CHECK (status IN ('AVAILABLE','LOCKED','PROCESSED','FAILED','DEAD_LETTER')),
                        headers JSONB DEFAULT '{}',
                        error_message TEXT,
                        correlation_id VARCHAR(255),
                        message_group VARCHAR(255),
                        priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10)
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS dead_letter_queue (
                        id BIGSERIAL PRIMARY KEY,
                        original_table VARCHAR(50) NOT NULL,
                        original_id BIGINT NOT NULL,
                        topic VARCHAR(255) NOT NULL,
                        payload JSONB NOT NULL,
                        original_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        failed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        failure_reason TEXT NOT NULL,
                        retry_count INT NOT NULL,
                        headers JSONB DEFAULT '{}',
                        correlation_id VARCHAR(255),
                        message_group VARCHAR(255)
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS outbox_topic_subscriptions (
                        id BIGSERIAL PRIMARY KEY,
                        topic VARCHAR(255) NOT NULL,
                        consumer_group VARCHAR(255) NOT NULL,
                        subscription_status VARCHAR(50) DEFAULT 'ACTIVE',
                        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        last_heartbeat_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        UNIQUE(topic, consumer_group)
                    )
                """);
            }
        }
    }

    // ========================================================================
    // Positive test: simple identifiers should work (baseline)
    // ========================================================================

    @Test
    @DisplayName("Schema with simple identifier (underscore) should work for stats queries")
    void simpleSchemaNameShouldWork(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: simple schema name should work");
        String schema = "simple_tenant";

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema,
                SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration(
                "simple-test", postgres.getHost(), postgres.getFirstMappedPort(),
                postgres.getDatabaseName(), postgres.getUsername(), postgres.getPassword(),
                schema);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        factory = new OutboxFactory(new PgDatabaseService(manager), config);

        var producer = factory.createProducer("test-topic", String.class);
        producer.send("hello")
            .compose(v -> factory.getStatsAsync("test-topic"))
            .onSuccess(stats -> testContext.verify(() -> {
                assertEquals(1, stats.getPendingMessages(),
                    "Stats query with simple schema name should work");
                producer.close();
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    // ========================================================================
    // Negative tests: SQL reserved words as schema names
    // ========================================================================

    @Test
    @DisplayName("Schema 'order' (reserved word) should work when properly quoted — getStatsAsync")
    void reservedWordOrderShouldWorkForStats(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: reserved word order should work for stats");
        // "order" passes PgConnectionManager's regex [A-Za-z0-9_,\s]+ but is a SQL reserved word.
        // Unquoted SQL: FROM order.outbox — PostgreSQL parses "order" as ORDER BY keyword.
        // Quoted SQL: FROM "order".outbox — correct.
        String schema = "order";
        createSchemaWithQuotedDDL(schema);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration(
                "order-stats", postgres.getHost(), postgres.getFirstMappedPort(),
                postgres.getDatabaseName(), postgres.getUsername(), postgres.getPassword(),
                schema);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        factory = new OutboxFactory(new PgDatabaseService(manager), config);

        var producer = factory.createProducer("stats-topic", String.class);
        producer.send("hello")
            .compose(v -> factory.getStatsAsync("stats-topic"))
            .onSuccess(stats -> testContext.verify(() -> {
                // With the old bug: recover() used to swallow the SQL error and return 0.
                // With the fix: query succeeds and returns 1.
                // Either way, getting 0 when we inserted 1 is evidence of the bug.
                assertEquals(1, stats.getPendingMessages(),
                    "getStatsAsync with reserved-word schema 'order' should return 1 pending message. " +
                    "Got 0 because unquoted 'FROM order.outbox' is a SQL syntax error — the query fails instead of returning 1.");
                producer.close();
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Schema 'order' (reserved word) should work when properly quoted — countMessagesAsync")
    void reservedWordOrderShouldWorkForCount(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: reserved word order should work for count");
        String schema = "order";
        createSchemaWithQuotedDDL(schema);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration(
                "order-count", postgres.getHost(), postgres.getFirstMappedPort(),
                postgres.getDatabaseName(), postgres.getUsername(), postgres.getPassword(),
                schema);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        factory = new OutboxFactory(new PgDatabaseService(manager), config);

        var producer = factory.createProducer("count-topic", String.class);
        producer.send("hello")
            .compose(v -> factory.countMessagesAsync("count-topic"))
            .onSuccess(count -> testContext.verify(() -> {
                assertEquals(1L, count,
                    "countMessagesAsync with schema 'order' should return 1. " +
                    "Unquoted 'FROM order.outbox' is a SQL syntax error.");
                producer.close();
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Schema 'order' (reserved word) should work when properly quoted — purgeMessagesAsync")
    void reservedWordOrderShouldWorkForPurge(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: reserved word order should work for purge");
        String schema = "order";
        createSchemaWithQuotedDDL(schema);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration(
                "order-purge", postgres.getHost(), postgres.getFirstMappedPort(),
                postgres.getDatabaseName(), postgres.getUsername(), postgres.getPassword(),
                schema);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        factory = new OutboxFactory(new PgDatabaseService(manager), config);

        var producer = factory.createProducer("purge-topic", String.class);
        producer.send("to-be-purged")
            .compose(v -> factory.purgeMessagesAsync("purge-topic"))
            .onSuccess(purged -> testContext.verify(() -> {
                assertEquals(1, purged,
                    "purgeMessagesAsync with schema 'order' should purge 1 message. " +
                    "Unquoted 'DELETE FROM order.outbox' is a SQL syntax error.");
                producer.close();
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Schema 'select' (reserved word) should work when properly quoted")
    void reservedWordSelectShouldWork(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: reserved word select should work");
        // "select" is another SQL reserved word that passes the regex validator
        String schema = "select";
        createSchemaWithQuotedDDL(schema);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration(
                "select-test", postgres.getHost(), postgres.getFirstMappedPort(),
                postgres.getDatabaseName(), postgres.getUsername(), postgres.getPassword(),
                schema);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        factory = new OutboxFactory(new PgDatabaseService(manager), config);

        var producer = factory.createProducer("select-topic", String.class);
        producer.send("hello")
            .compose(v -> factory.countMessagesAsync("select-topic"))
            .onSuccess(count -> testContext.verify(() -> {
                assertEquals(1L, count,
                    "countMessagesAsync with schema 'select' should return 1. " +
                    "Unquoted 'FROM select.outbox' is a SQL syntax error.");
                producer.close();
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }
}
