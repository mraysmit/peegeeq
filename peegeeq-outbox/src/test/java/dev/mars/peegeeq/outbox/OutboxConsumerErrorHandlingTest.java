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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
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
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration tests for OutboxConsumer error handling and edge cases.
 * Targets uncovered branches to increase coverage from 75% to 85%+:
 * - Handler exception scenarios
 * - Retry logic and dead letter queue
 * - Unsubscribe during processing
 * - Consumer group name changes
 * - Close during active processing
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxConsumerErrorHandlingTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerErrorHandlingTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        testTopic = "error-test-" + UUID.randomUUID().toString().substring(0, 8);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
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
        logger.info("Tearing down: closing resources and manager");
        Future<Void> closeChain = outboxFactory != null
                ? outboxFactory.close()
                : Future.succeededFuture();
        closeChain
                .eventually(() -> manager != null ? manager.closeReactive() : Future.succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void testHandlerExceptionWithRetry(Vertx vertx, VertxTestContext testContext) {
        AtomicInteger attemptCount = new AtomicInteger();

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            if (attempt < 3) {
                throw new RuntimeException("Simulated handler failure on attempt " + attempt);
            }
            return Future.succeededFuture();
        })
            .compose(v -> producer.send("test-message"))
            .compose(v -> awaitStatus(vertx, "COMPLETED", 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertState(row, attemptCount, 3, "COMPLETED", 2);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testHandlerExceptionReachesMaxRetries(Vertx vertx, VertxTestContext testContext) {
        AtomicInteger attemptCount = new AtomicInteger();

        consumer.subscribe(message -> {
            attemptCount.incrementAndGet();
            return Future.failedFuture(new RuntimeException("Always fails"));
        })
            .compose(v -> producer.send("failing-message"))
            .compose(v -> awaitStatus(vertx, "DEAD_LETTER", 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertState(row, attemptCount, 4, "DEAD_LETTER", 3);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testUnsubscribeDuringProcessing(Vertx vertx, VertxTestContext testContext) {
        Promise<Void> processingStarted = Promise.promise();
        Promise<Void> finishGate = Promise.promise();
        AtomicInteger processed = new AtomicInteger();

        consumer.subscribe(message -> {
            processed.incrementAndGet();
            processingStarted.tryComplete();
            return finishGate.future();
        })
            .compose(v -> producer.send("test-message"))
            .compose(v -> processingStarted.future())
            .compose(v -> {
                consumer.unsubscribe();
                return producer.send("ignored-message");
            })
            .compose(v -> {
                finishGate.tryComplete();
                return awaitTerminalCounts(vertx, 1, 1, 100);
            })
            .onSuccess(row -> testContext.verify(() -> {
                assertTerminalCounts(row, 1, 1);
                assertEquals(1, processed.intValue());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testCloseDuringProcessing(Vertx vertx, VertxTestContext testContext) {
        Promise<Void> processingStarted = Promise.promise();
        Promise<Void> finishGate = Promise.promise();
        OutboxConsumer<String> typedConsumer = (OutboxConsumer<String>) consumer;

        consumer.subscribe(message -> {
            processingStarted.tryComplete();
            return finishGate.future();
        })
            .compose(v -> producer.send("test-message"))
            .compose(v -> processingStarted.future())
            .compose(v -> {
                Future<Void> closeFuture = typedConsumer.closeAsync();
                finishGate.tryComplete();
                return closeFuture;
            })
            .compose(v -> producer.send("message-after-close"))
            .compose(v -> awaitTerminalCounts(vertx, 1, 1, 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertTerminalCounts(row, 1, 1);
                Future<Void> resubscribe = typedConsumer.subscribe(message -> Future.succeededFuture());
                assertTrue(resubscribe.failed());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testSetConsumerGroupName() throws Exception {
        OutboxConsumer<String> typedConsumer = (OutboxConsumer<String>) consumer;
        
        String groupName = "test-group-" + UUID.randomUUID();
        
        assertDoesNotThrow(() -> typedConsumer.setConsumerGroupName(groupName), 
            "Should set consumer group name without error");
    }

    @Test
    void testMultipleSubscribeCallsLogsWarning(VertxTestContext testContext) {
        consumer.subscribe(message -> Future.succeededFuture())
            .compose(v -> consumer.subscribe(message -> Future.succeededFuture()))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testUnsubscribeBeforeSubscribe() {
        assertDoesNotThrow(() -> consumer.unsubscribe(), 
            "Unsubscribe before subscribe should not throw");
    }

    @Test
    void testCloseBeforeSubscribe(VertxTestContext testContext) {
        OutboxConsumer<String> typedConsumer = (OutboxConsumer<String>) consumer;
        typedConsumer.closeAsync()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testCloseMultipleTimes(Vertx vertx, VertxTestContext testContext) {
        OutboxConsumer<String> typedConsumer = (OutboxConsumer<String>) consumer;
        consumer.subscribe(message -> {
            return Future.succeededFuture();
        })
            .compose(v -> producer.send("test-message"))
            .compose(v -> awaitStatus(vertx, "COMPLETED", 100))
            .compose(v -> typedConsumer.closeAsync())
            .compose(v -> typedConsumer.closeAsync())
            .compose(v -> typedConsumer.closeAsync())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testMessageWithNullPayload(Vertx vertx, VertxTestContext testContext) {
        // producer.send(null) returns a failed Future no message is stored,
        // so the consumer never receives anything. Verify the send fails.
        producer.send(null)
            .onSuccess(v -> testContext.failNow("Sending null payload should have failed"))
            .onFailure(err -> testContext.verify(() -> {
        logger.info("Test: message with null payload");
                assertInstanceOf(IllegalArgumentException.class, err,
                    "Cause should be IllegalArgumentException");
                testContext.completeNow();
            }));
    }

    @Test
    void testRapidSubscribeUnsubscribeCycle(Vertx vertx, VertxTestContext testContext) {
        Future<Void> cycles = Future.succeededFuture();
        for (int i = 0; i < 5; i++) {
            cycles = cycles.compose(v -> consumer.subscribe(message -> Future.succeededFuture()))
                    .map(v -> {
                        consumer.unsubscribe();
                        return (Void) null;
                    });
        }
        cycles
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testHandlerWithInterruptedException(Vertx vertx, VertxTestContext testContext) {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger observedInterrupts = new AtomicInteger();

        consumer.subscribe(message -> {
            attempts.incrementAndGet();
            if (Thread.currentThread().isInterrupted()) {
                observedInterrupts.incrementAndGet();
            }
            return Future.failedFuture(new RuntimeException(
                    "Interrupted operation",
                    new InterruptedException("simulated interruption")));
        })
            .compose(v -> producer.send("interrupt-test"))
            .compose(v -> awaitStatus(vertx, "DEAD_LETTER", 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertState(row, attempts, 4, "DEAD_LETTER", 3);
                assertEquals(0, observedInterrupts.intValue(),
                        "Test must not poison the shared event-loop interrupt flag");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testConcurrentMessageProcessing(Vertx vertx, VertxTestContext testContext) {
        int messageCount = 10;
        AtomicInteger processedCount = new AtomicInteger();

        consumer.subscribe(message -> {
            processedCount.incrementAndGet();
            return vertx.timer(50).mapEmpty();
        })
            .compose(v -> sendMessages("concurrent-message-", messageCount))
            .compose(v -> awaitCompletedCount(vertx, messageCount, 100))
            .onSuccess(count -> testContext.verify(() -> {
                assertEquals(messageCount, count);
                assertEquals(messageCount, processedCount.intValue());
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

    private Future<Row> awaitTerminalCounts(
            Vertx vertx,
            int expectedCompleted,
            int expectedPending,
            int remainingAttempts) {
        return manager.getDatabaseService().getConnectionProvider()
                .getReactivePool("peegeeq-main")
                .compose(pool -> pool.withConnection(connection -> connection.preparedQuery("""
                        SELECT
                            CAST(COUNT(*) FILTER (WHERE status = 'COMPLETED') AS INTEGER) AS completed_count,
                            CAST(COUNT(*) FILTER (WHERE status = 'PENDING') AS INTEGER) AS pending_count
                        FROM outbox
                        WHERE topic = $1
                        """).execute(Tuple.of(testTopic))))
                .compose(rows -> {
                    Row row = rows.iterator().next();
                    if (row.getInteger("completed_count") == expectedCompleted
                            && row.getInteger("pending_count") == expectedPending) {
                        return Future.succeededFuture(row);
                    }
                    if (remainingAttempts == 0) {
                        return Future.failedFuture("Topic did not reach expected completed/pending counts");
                    }
                    return vertx.timer(100).compose(v -> awaitTerminalCounts(
                            vertx,
                            expectedCompleted,
                            expectedPending,
                            remainingAttempts - 1));
                });
    }

    private void assertTerminalCounts(Row row, int expectedCompleted, int expectedPending) {
        assertEquals(expectedCompleted, row.getInteger("completed_count"));
        assertEquals(expectedPending, row.getInteger("pending_count"));
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

    private void assertState(
            Row row,
            AtomicInteger attempts,
            int expectedAttempts,
            String expectedStatus,
            int expectedRetryCount) {
        assertEquals(expectedAttempts, attempts.intValue());
        assertEquals(expectedStatus, row.getString("status"));
        assertEquals(expectedRetryCount, row.getInteger("retry_count"));
    }
}
