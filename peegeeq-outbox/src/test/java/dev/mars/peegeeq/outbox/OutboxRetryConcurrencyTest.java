package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;

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
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Comprehensive test suite for thread safety and concurrency in outbox consumer retry mechanism.
 * 
 * This test suite covers critical concurrency scenarios that could occur in production:
 * - Concurrent message processing by multiple threads
 * - Race conditions in retry count updates
 * - Thread pool exhaustion scenarios
 * - Thread safety during consumer shutdown
 * - Concurrent access to message status updates
 * - Message processing during system stress
 * 
 * These tests are essential for ensuring the outbox consumer can handle high-concurrency
 * scenarios without data corruption, duplicate processing, or lost messages.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxRetryConcurrencyTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxRetryConcurrencyTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;

    @BeforeEach
    void setUp(VertxTestContext ctx) {
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        // Set up database connection properties
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.max-retries", "3")
                .property("peegeeq.queue.polling-interval", "PT0.05S")
                .property("peegeeq.database.pool.max-size", "20")
                .property("peegeeq.database.pool.connection-timeout-ms", "5000")
                .property("peegeeq.consumer.threads", "10")
                .build();

        // Initialize manager and components
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());
        manager.start()
            .map(v -> {
                DatabaseService databaseService = new PgDatabaseService(manager);
                outboxFactory = new OutboxFactory(databaseService, manager.getConfiguration());
                return (Void) null;
            })
            .onSuccess(v -> ctx.completeNow())
            .onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (outboxFactory != null ? outboxFactory.close() : Future.<Void>succeededFuture())
            .eventually(() -> manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
            .onSuccess(v -> ctx.completeNow())
            .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("CONCURRENCY: Multiple threads processing same message simultaneously")
    void testConcurrentMessageProcessing(Vertx vertx, VertxTestContext testContext) {
        String testTopic = "test-concurrent-processing-" + UUID.randomUUID().toString().substring(0, 8);
        MessageProducer<String> producer = outboxFactory.createProducer(testTopic, String.class);
        AtomicInteger attempts = new AtomicInteger();
        List<Future<?>> subscriptions = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            MessageConsumer<String> consumer = outboxFactory.createConsumer(testTopic, String.class);
            subscriptions.add(consumer.subscribe(message -> {
                attempts.incrementAndGet();
                return vertx.timer(100).mapEmpty();
            }));
        }

        Future.all(subscriptions)
            .compose(v -> producer.send("Message for concurrent processing test"))
            .compose(v -> awaitStatusCount(vertx, testTopic, "COMPLETED", 1, 100))
            .onSuccess(count -> testContext.verify(() -> {
                assertEquals(1, count);
                assertEquals(1, attempts.intValue(), "Database locking must permit exactly one handler");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("CONCURRENCY: Race conditions in retry count updates")
    void testRaceConditionsInRetryCountUpdates(Vertx vertx, VertxTestContext testContext) {
        String testTopic = "test-retry-race-conditions-" + UUID.randomUUID().toString().substring(0, 8);
        MessageProducer<String> producer = outboxFactory.createProducer(testTopic, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(testTopic, String.class);
        AtomicInteger attemptCount = new AtomicInteger();
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            return vertx.timer(50).compose(v -> Future.failedFuture(
                    new RuntimeException("INTENTIONAL FAILURE: Retry race attempt " + attempt)));
        })
            .compose(v -> producer.send("Message for retry race condition test"))
            .compose(v -> awaitLatestStatus(vertx, testTopic, "DEAD_LETTER", 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertEquals(4, attemptCount.intValue());
                assertEquals(3, row.getInteger("retry_count"));
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("CONCURRENCY: Thread pool exhaustion during message processing")
    void testThreadPoolExhaustionDuringMessageProcessing(Vertx vertx, VertxTestContext testContext) {
        String testTopic = "test-thread-exhaustion-" + UUID.randomUUID().toString().substring(0, 8);
        MessageProducer<String> producer = outboxFactory.createProducer(testTopic, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(testTopic, String.class);
        int messageCount = 15;
        AtomicInteger processedCount = new AtomicInteger();
        consumer.subscribe(message -> {
            processedCount.incrementAndGet();
            return vertx.timer(200).mapEmpty();
        })
            .compose(v -> sendMessages(producer, "Thread exhaustion test message ", messageCount))
            .compose(v -> awaitStatusCount(vertx, testTopic, "COMPLETED", messageCount, 200))
            .onSuccess(count -> testContext.verify(() -> {
                assertEquals(messageCount, count);
                assertEquals(messageCount, processedCount.intValue());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("CONCURRENCY: Thread safety during consumer shutdown")
    void testThreadSafetyDuringConsumerShutdown(Vertx vertx, VertxTestContext testContext) {
        String testTopic = "test-shutdown-safety-" + UUID.randomUUID().toString().substring(0, 8);
        MessageProducer<String> producer = outboxFactory.createProducer(testTopic, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(testTopic, String.class);
        int messageCount = 10;
        AtomicInteger processedCount = new AtomicInteger();
        consumer.subscribe(message -> {
            processedCount.incrementAndGet();
            return vertx.timer(300).mapEmpty();
        })
            .compose(v -> sendMessages(producer, "Shutdown safety test message ", messageCount))
            .compose(v -> awaitObserved(vertx, processedCount, 100))
            .compose(v -> {
                consumer.close();
                return awaitStatusCount(vertx, testTopic, "COMPLETED", 1, 100);
            })
            .onSuccess(count -> testContext.verify(() -> {
                assertTrue(processedCount.intValue() >= 1);
                assertTrue(count >= 1, "In-flight processing should complete during close");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("CONCURRENCY: High-load concurrent retry processing")
    void testHighLoadConcurrentRetryProcessing(Vertx vertx, VertxTestContext testContext) {
        String testTopic = "test-high-load-retry-" + UUID.randomUUID().toString().substring(0, 8);
        MessageProducer<String> producer = outboxFactory.createProducer(testTopic, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(testTopic, String.class);
        int messageCount = 20;
        AtomicInteger totalAttempts = new AtomicInteger();
        int totalExpected = messageCount * 4;
        consumer.subscribe(message -> {
            int attempt = totalAttempts.incrementAndGet();
            return vertx.timer(10).compose(v -> Future.failedFuture(
                    new RuntimeException("INTENTIONAL FAILURE: High-load retry attempt " + attempt)));
        })
            .compose(v -> sendMessages(producer, "High load retry test message ", messageCount))
            .compose(v -> awaitStatusCount(vertx, testTopic, "DEAD_LETTER", messageCount, 300))
            .onSuccess(count -> testContext.verify(() -> {
                assertEquals(messageCount, count);
                assertEquals(totalExpected, totalAttempts.intValue());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    private Future<Void> sendMessages(MessageProducer<String> producer, String prefix, int count) {
        List<Future<?>> sends = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            sends.add(producer.send(prefix + i));
        }
        return Future.all(sends).mapEmpty();
    }

    private Future<Integer> awaitStatusCount(
            Vertx vertx, String topic, String status, int expected, int remainingAttempts) {
        return statusCount(topic, status).compose(count -> {
            if (count == expected) {
                return Future.succeededFuture(count);
            }
            if (remainingAttempts == 0) {
                return Future.failedFuture(
                        "Expected " + expected + " " + status + " messages but found " + count);
            }
            return vertx.timer(100).compose(v ->
                    awaitStatusCount(vertx, topic, status, expected, remainingAttempts - 1));
        });
    }

    private Future<Integer> statusCount(String topic, String status) {
        return manager.getDatabaseService().getConnectionProvider()
                .getReactivePool("peegeeq-main")
                .compose(pool -> pool.withConnection(connection -> connection.preparedQuery("""
                        SELECT COUNT(*) AS message_count
                        FROM outbox
                        WHERE topic = $1 AND status = $2
                        """).execute(Tuple.of(topic, status))))
                .map(rows -> rows.iterator().next().getInteger("message_count"));
    }

    private Future<Row> awaitLatestStatus(
            Vertx vertx, String topic, String expectedStatus, int remainingAttempts) {
        return manager.getDatabaseService().getConnectionProvider()
                .getReactivePool("peegeeq-main")
                .compose(pool -> pool.withConnection(connection -> connection.preparedQuery("""
                        SELECT status, retry_count
                        FROM outbox
                        WHERE topic = $1
                        ORDER BY id DESC
                        LIMIT 1
                        """).execute(Tuple.of(topic))))
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
                    return vertx.timer(100).compose(v ->
                            awaitLatestStatus(vertx, topic, expectedStatus, remainingAttempts - 1));
                });
    }

    private Future<Void> awaitObserved(Vertx vertx, AtomicInteger count, int remainingAttempts) {
        if (count.intValue() > 0) {
            return Future.succeededFuture();
        }
        if (remainingAttempts == 0) {
            return Future.failedFuture("No handler started before shutdown");
        }
        return vertx.timer(10).compose(v -> awaitObserved(vertx, count, remainingAttempts - 1));
    }
}
