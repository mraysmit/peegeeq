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

import dev.mars.peegeeq.db.PeeGeeQDefaults;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.consumer.ConsumerGroupRetryService;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for schema identifier quoting.
 *
 * <p>SQL reserved words such as "order", "select", and "table" pass schema-name
 * validation but must still be quoted when used as PostgreSQL identifiers.</p>
 *
 * <p>Every test uses {@link PeeGeeQTestSchemaInitializer}, ensuring the reserved-word
 * coverage exercises the supported, fully migrated schema rather than a hand-written
 * schema snapshot that can drift from production migrations.</p>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@DisplayName("M1: Schema names with special characters must be properly quoted")
class OutboxSchemaQuotingTest {
    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory factory;

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        Future<Void> factoryClose = factory != null ? factory.close() : Future.succeededFuture();
        factoryClose.transform(factoryResult -> {
                    Future<Void> managerClose = manager != null
                            ? manager.closeReactive()
                            : Future.succeededFuture();
                    return managerClose.transform(managerResult -> {
                        if (factoryResult.failed()) {
                            return Future.failedFuture(factoryResult.cause());
                        }
                        if (managerResult.failed()) {
                            return Future.failedFuture(managerResult.cause());
                        }
                        return Future.succeededFuture();
                    });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    private PeeGeeQConfiguration createTestConfiguration(String profile, String schema) {
        return new PeeGeeQConfiguration(profile, PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(schema)
                .build());
    }

    private void initializeSupportedSchema(String schema) {
        PeeGeeQTestSchemaInitializer.initializeSchema(
                postgres, schema, SchemaComponent.QUEUE_ALL);
    }

    // ========================================================================
    // Positive test: simple identifiers should work (baseline)
    // ========================================================================

    @Test
    @DisplayName("Schema with simple identifier (underscore) should work for stats queries")
    void simpleSchemaNameShouldWork(VertxTestContext testContext) throws Exception {
        String schema = "simple_tenant";

        initializeSupportedSchema(schema);

        PeeGeeQConfiguration config = createTestConfiguration("simple-test", schema);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .compose(v -> {
                    factory = new OutboxFactory(new PgDatabaseService(manager), config);
                    var producer = factory.createProducer("test-topic", String.class);
                    return producer.send("hello")
                            .compose(ignored -> factory.getStats("test-topic"))
                            .map(stats -> {
                                assertEquals(1, stats.getPendingMessages(),
                                        "Stats query with simple schema name should work");
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    // ========================================================================
    // Negative tests: SQL reserved words as schema names
    // ========================================================================

    @Test
    @DisplayName("Schema 'order' (reserved word) should work when properly quoted getStatsAsync")
    void reservedWordOrderShouldWorkForStats(VertxTestContext testContext) throws Exception {
        // "order" passes PgConnectionManager's regex [A-Za-z0-9_,\s]+ but is a SQL reserved word.
        // Unquoted SQL: FROM order.outbox PostgreSQL parses "order" as ORDER BY keyword.
        // Quoted SQL: FROM "order".outbox correct.
        String schema = "order";
        initializeSupportedSchema(schema);

        PeeGeeQConfiguration config = createTestConfiguration("order-stats", schema);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .compose(v -> {
                    factory = new OutboxFactory(new PgDatabaseService(manager), config);
                    var producer = factory.createProducer("stats-topic", String.class);
                    return producer.send("hello")
                            .compose(ignored -> factory.getStats("stats-topic"))
                            .map(stats -> {
                                // With the old bug, the SQL error was swallowed and zero was returned.
                                // With the fix, the query succeeds and returns one.
                                assertEquals(1, stats.getPendingMessages(),
                                        "getStatsAsync with reserved-word schema 'order' should return 1 pending message. " +
                                        "Got 0 because unquoted 'FROM order.outbox' is a SQL syntax error the query fails instead of returning 1.");
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Schema 'order' (reserved word) should work when properly quoted countMessagesAsync")
    void reservedWordOrderShouldWorkForCount(VertxTestContext testContext) throws Exception {
        String schema = "order";
        initializeSupportedSchema(schema);

        PeeGeeQConfiguration config = createTestConfiguration("order-count", schema);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .compose(v -> {
                    factory = new OutboxFactory(new PgDatabaseService(manager), config);
                    var producer = factory.createProducer("count-topic", String.class);
                    return producer.send("hello")
                            .compose(ignored -> factory.countMessages("count-topic"))
                            .map(count -> {
                                assertEquals(1L, count,
                                        "countMessagesAsync with schema 'order' should return 1. " +
                                        "Unquoted 'FROM order.outbox' is a SQL syntax error.");
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Schema 'order' (reserved word) should work when properly quoted purgeMessagesAsync")
    void reservedWordOrderShouldWorkForPurge(VertxTestContext testContext) throws Exception {
        String schema = "order";
        initializeSupportedSchema(schema);

        PeeGeeQConfiguration config = createTestConfiguration("order-purge", schema);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .compose(v -> {
                    factory = new OutboxFactory(new PgDatabaseService(manager), config);
                    var producer = factory.createProducer("purge-topic", String.class);
                    return producer.send("to-be-purged")
                            .compose(ignored -> factory.purgeMessages("purge-topic"))
                            .map(purged -> {
                                assertEquals(1, purged,
                                        "purgeMessagesAsync with schema 'order' should purge 1 message. " +
                                        "Unquoted 'DELETE FROM order.outbox' is a SQL syntax error.");
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Schema 'select' (reserved word) should work when properly quoted")
    void reservedWordSelectShouldWork(VertxTestContext testContext) throws Exception {
        // "select" is another SQL reserved word that passes the regex validator
        String schema = "select";
        initializeSupportedSchema(schema);

        PeeGeeQConfiguration config = createTestConfiguration("select-test", schema);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .compose(v -> {
                    factory = new OutboxFactory(new PgDatabaseService(manager), config);
                    var producer = factory.createProducer("select-topic", String.class);
                    return producer.send("hello")
                            .compose(ignored -> factory.countMessages("select-topic"))
                            .map(count -> {
                                assertEquals(1L, count,
                                        "countMessagesAsync with schema 'select' should return 1. " +
                                        "Unquoted 'FROM select.outbox' is a SQL syntax error.");
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Reserved-word schema should support retry and dead-consumer service queries")
    void reservedWordSchemaShouldSupportBackgroundServiceQueries(VertxTestContext testContext) {
        String schema = "table";
        initializeSupportedSchema(schema);

        PeeGeeQConfiguration config = createTestConfiguration("table-background-services", schema);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .compose(v -> {
                    var connectionManager = manager.getClientFactory().getConnectionManager();
                    var retryService = new ConsumerGroupRetryService(
                            connectionManager,
                            manager.getDeadLetterQueueManager(),
                            PeeGeeQDefaults.DEFAULT_POOL_ID);
                    var detector = new DeadConsumerDetector(
                            connectionManager,
                            PeeGeeQDefaults.DEFAULT_POOL_ID);

                    return retryService.processFailedMessages()
                            .compose(retryResult -> detector.detectAllDeadSubscriptionsWithDetails()
                                    .map(detectionResult -> {
                                        assertEquals(0, retryResult.retriedCount(),
                                                "Fresh reserved-word schema should have no retryable groups");
                                        assertEquals(0, retryResult.dlqCount(),
                                                "Fresh reserved-word schema should have no exhausted groups");
                                        assertEquals(0, detectionResult.deadCount(),
                                                "Fresh reserved-word schema should have no dead subscriptions");
                                        return (Void) null;
                                    }));
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }
}
