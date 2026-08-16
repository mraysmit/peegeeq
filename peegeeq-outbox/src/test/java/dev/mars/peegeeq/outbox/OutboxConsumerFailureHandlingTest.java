package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.extension.ExtendWith;


import java.util.Properties;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Tests for OutboxConsumer failure handling paths to increase coverage.
 * Specifically targets:
 * - markMessageFailed() - 0% coverage (38 instructions)
 * - Error handler lambdas - 0% coverage (~93 instructions)
 * - processAvailableMessages() edge cases - 33%  80% (+92 instructions)
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxConsumerFailureHandlingTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerFailureHandlingTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        testTopic = "failure-test-" + UUID.randomUUID().toString().substring(0, 8);

        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.max-retries", "3")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        manager.start()
                .map(v -> {
                    DatabaseService databaseService = new PgDatabaseService(manager);
                    outboxFactory = new OutboxFactory(databaseService, config);
                    producer = outboxFactory.createProducer(testTopic, String.class);
                    consumer = outboxFactory.createConsumer(testTopic, String.class);
                    return (Void) null;
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down OutboxConsumerFailureHandlingTest");
        (outboxFactory != null ? outboxFactory.close() : Future.<Void>succeededFuture())
                .eventually(() -> manager != null ? manager.closeReactive() : Future.succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    /**
     * Test that exercises error handling paths by processing messages that consistently fail.
     * This tests retry logic, error lambdas, and failure tracking.
     * 
     * NOTE: Temporarily disabled - timing sensitive, requires investigation
     */
    @Test
    void testRetryLogicWithFailingMessages(Vertx vertx, VertxTestContext testContext) {
        AtomicInteger attemptCount = new AtomicInteger();
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: consumer attempt {}", attempt);
            return Future.failedFuture(new RuntimeException("Intentional failure attempt " + attempt));
        })
            .compose(v -> producer.send("test-message"))
            .compose(v -> awaitStatus(vertx, "DEAD_LETTER", 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertEquals(4, attemptCount.intValue());
                assertEquals(3, row.getInteger("retry_count"));
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Test processAvailableMessages() when consumer is closed during processing.
     * Verifies the public closed-consumer contract while processing is active.
     */
    @Test
    void testProcessAvailableMessages_ConsumerClosedDuringProcessing(io.vertx.core.Vertx vertx, VertxTestContext testContext) {
        Promise<Void> startSignal = Promise.promise();
        Promise<Void> finishGate = Promise.promise();
        OutboxConsumer<String> typedConsumer = (OutboxConsumer<String>) consumer;

        consumer.subscribe(message -> {
            logger.info("Test: process available messages  consumer closed during processing");
            startSignal.tryComplete();
            return finishGate.future();
        })
                .compose(v -> producer.send("message1"))
                .compose(v -> producer.send("message2"))
                .compose(v -> startSignal.future())
                .compose(v -> {
                    Future<Void> closeFuture = typedConsumer.closeAsync();
                    finishGate.tryComplete();
                    return closeFuture;
                })
                .onSuccess(v -> testContext.verify(() -> {
                    Future<Void> resubscribe = typedConsumer.subscribe(message -> Future.succeededFuture());
                    assertTrue(resubscribe.failed(), "Closed consumer must reject resubscription");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    /**
     * Test processAvailableMessages() with batch processing.
     * This tests the batch size logic at line 257.
     */
    @Test
    void testProcessAvailableMessages_BatchProcessing(io.vertx.core.Vertx vertx, VertxTestContext testContext) {
        int messageCount = 5;
        AtomicInteger processed = new AtomicInteger();

        consumer.subscribe(message -> {
            logger.info("Test: process available messages  batch processing");
            processed.incrementAndGet();
            return Future.succeededFuture();
        })
                .compose(v -> sendMessages("batch-message-", messageCount))
                .compose(v -> awaitCompletedCount(vertx, messageCount, 100))
                .onSuccess(count -> testContext.verify(() -> {
                    assertEquals(messageCount, count);
                    assertEquals(messageCount, processed.intValue());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    /**
     * Test error handling in getReactivePoolFuture() when pool creation fails.
     * Subscribes while the pool is still available, then closes the manager to
     * simulate pool failure. Verifies the consumer reaches a clean closed state
     * without throwing during shutdown.
     */
    @Test
    void testGetReactivePoolFuture_ErrorHandling(VertxTestContext testContext) {
        OutboxConsumer<String> typedConsumer = (OutboxConsumer<String>) consumer;

        typedConsumer.subscribe(message -> Future.succeededFuture())
                .compose(v -> manager.closeReactive())
                .onSuccess(v -> testContext.verify(() -> {
                    Future<Void> resubscribe = typedConsumer.subscribe(message -> Future.succeededFuture());
                    assertTrue(resubscribe.failed(), "Manager shutdown must close registered consumer");
                    assertInstanceOf(IllegalStateException.class, resubscribe.cause());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    /**
     * Test close() method with multiple calls and during processing.
     * This tests close() at 58% coverage to reach 80%+.
     */
    @Test
    void testClose_WhileProcessing(io.vertx.core.Vertx vertx, VertxTestContext testContext) {
        Promise<Void> startSignal2 = Promise.promise();
        Promise<Void> blockGate = Promise.promise();
        OutboxConsumer<String> typedConsumer = (OutboxConsumer<String>) consumer;

        consumer.subscribe(message -> {
            logger.info("Test: close  while processing");
            startSignal2.tryComplete();
            return blockGate.future();
        })
                .compose(v -> producer.send("test"))
                .compose(v -> startSignal2.future())
                .compose(v -> {
                    Future<Void> firstClose = typedConsumer.closeAsync();
                    blockGate.tryComplete();
                    return firstClose;
                })
                .compose(v -> typedConsumer.closeAsync())
                .onSuccess(v -> testContext.verify(() -> {
                    Future<Void> resubscribe = typedConsumer.subscribe(message -> Future.succeededFuture());
                    assertTrue(resubscribe.failed(), "Multiple closes must leave consumer closed");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    /**
     * Test parsePayloadFromJsonObject() with edge cases.
     * Current coverage: 64%  Target: 80%+
     */
    @Test
    void testParsePayloadFromJsonObject_EdgeCases(io.vertx.core.Vertx vertx, VertxTestContext testContext) {
        CopyOnWriteArrayList<String> receivedPayloads = new CopyOnWriteArrayList<>();

        consumer.subscribe(message -> {
            logger.info("Test: parse payload from json object  edge cases");
            receivedPayloads.add(message.getPayload());
            return Future.succeededFuture();
        })
                .compose(v -> sendPayloads(List.of("simple-string", "{\"complex\":\"json\"}", "")))
                .compose(v -> awaitCompletedCount(vertx, 3, 100))
                .onSuccess(count -> testContext.verify(() -> {
                    assertEquals(3, count);
                    assertEquals(3, receivedPayloads.size());
                    assertTrue(receivedPayloads.containsAll(
                            List.of("simple-string", "{\"complex\":\"json\"}", "")));
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    private Future<Void> sendMessages(String prefix, int count) {
        List<Future<?>> sends = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            sends.add(producer.send(prefix + i));
        }
        return Future.all(sends).mapEmpty();
    }

    private Future<Void> sendPayloads(List<String> payloads) {
        List<Future<?>> sends = new ArrayList<>();
        for (String payload : payloads) {
            sends.add(producer.send(payload));
        }
        return Future.all(sends).mapEmpty();
    }

    private Future<Integer> awaitCompletedCount(Vertx vertx, int expectedCount, int remainingAttempts) {
        return statusCount("COMPLETED").compose(count -> {
            if (count == expectedCount) {
                return Future.succeededFuture(count);
            }
            if (remainingAttempts == 0) {
                return Future.failedFuture(
                        "Expected " + expectedCount + " completed messages but found " + count);
            }
            return vertx.timer(100)
                    .compose(v -> awaitCompletedCount(vertx, expectedCount, remainingAttempts - 1));
        });
    }

    private Future<Integer> statusCount(String status) {
        return manager.getDatabaseService().getConnectionProvider()
                .getReactivePool("peegeeq-main")
                .compose(pool -> pool.withConnection(connection -> connection.preparedQuery("""
                        SELECT CAST(COUNT(*) AS INTEGER) AS message_count
                        FROM outbox
                        WHERE topic = $1 AND status = $2
                        """).execute(Tuple.of(testTopic, status))))
                .map(rows -> rows.iterator().next().getInteger("message_count"));
    }

    private Future<Row> awaitStatus(Vertx vertx, String expectedStatus, int remainingAttempts) {
        return manager.getDatabaseService().getConnectionProvider()
                .getReactivePool("peegeeq-main")
                .compose(pool -> pool.withConnection(connection -> connection.preparedQuery("""
                        SELECT status, retry_count
                        FROM outbox
                        WHERE topic = $1
                        ORDER BY id DESC
                        LIMIT 1
                        """).execute(Tuple.of(testTopic))))
                .compose(rows -> {
                    if (rows.size() == 1) {
                        Row row = rows.iterator().next();
                        if (expectedStatus.equals(row.getString("status"))) {
                            return Future.succeededFuture(row);
                        }
                    }
                    if (remainingAttempts == 0) {
                        return Future.failedFuture("Message did not reach " + expectedStatus);
                    }
                    return vertx.timer(100)
                            .compose(v -> awaitStatus(vertx, expectedStatus, remainingAttempts - 1));
                });
    }
}
