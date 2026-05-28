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
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Promise;
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


import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private List<MessageProducer<String>> producers;
    private List<MessageConsumer<String>> consumers;
    private OutboxFactory outboxFactory;
    private io.vertx.sqlclient.Pool testReactivePool;
    private PgConnectionManager connectionManager;

    @BeforeEach
    void setUp(VertxTestContext ctx) throws Exception {
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        // Set up database connection properties
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .property("peegeeq.queue.max-retries", "3")
                .property("peegeeq.queue.polling-interval", "PT0.05S")
                .property("peegeeq.database.pool.max-size", "20")
                .property("peegeeq.database.pool.connection-timeout-ms", "5000")
                .property("peegeeq.consumer.threads", "10")
                .build();

        // Initialize manager and components
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());
        manager.start()
            .onSuccess(v -> {
                DatabaseService databaseService = new PgDatabaseService(manager);
                outboxFactory = new OutboxFactory(databaseService, manager.getConfiguration());

                // Create test-specific DataSource for verification
                connectionManager = new PgConnectionManager(Vertx.vertx());
                PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                        .host(postgres.getHost())
                        .port(postgres.getFirstMappedPort())
                        .database(postgres.getDatabaseName())
                        .username(postgres.getUsername())
                        .password(postgres.getPassword())
                        .build();
                PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).build();
                testReactivePool = connectionManager.getOrCreateReactivePool("test-verification", connectionConfig, poolConfig);

                // Initialize collections for multiple producers/consumers
                producers = new ArrayList<>();
                consumers = new ArrayList<>();

                ctx.completeNow();
            })
            .onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) throws Exception {
        // Close all consumers
        for (MessageConsumer<String> consumer : consumers) {
            try { consumer.close(); } catch (Exception e) { logger.warn("Error closing consumer: {}", e.getMessage()); }
        }

        // Close all producers
        for (MessageProducer<String> producer : producers) {
            try { producer.close(); } catch (Exception e) { logger.warn("Error closing producer: {}", e.getMessage()); }
        }

        if (outboxFactory != null) {
            try { outboxFactory.close(); } catch (Exception e) { logger.warn("Error closing outbox factory: {}", e.getMessage()); }
        }

        (manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
            .eventually(() -> connectionManager != null
                ? connectionManager.close()
                : Future.<Void>succeededFuture())
            .onSuccess(v -> ctx.completeNow())
            .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("CONCURRENCY: Multiple threads processing same message simultaneously")
    void testConcurrentMessageProcessing(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        String testTopic = "test-concurrent-processing-" + UUID.randomUUID().toString().substring(0, 8);
        MessageProducer<String> producer = outboxFactory.createProducer(testTopic, String.class);
        producers.add(producer);
        
        // Create multiple consumers for the same topic
        int consumerCount = 5;
        List<MessageConsumer<String>> topicConsumers = new ArrayList<>();
        
        for (int i = 0; i < consumerCount; i++) {
            MessageConsumer<String> consumer = outboxFactory.createConsumer(testTopic, String.class);
            consumers.add(consumer);
            topicConsumers.add(consumer);
        }
        
        String testMessage = "Message for concurrent processing test";
        AtomicInteger totalAttempts = new AtomicInteger(0);
        AtomicInteger successfulProcessing = new AtomicInteger(0);
        Checkpoint processingCheckpoint = testContext.checkpoint();
        Checkpoint completionCheckpoint = testContext.checkpoint();

        // Send message first
        producer.send(testMessage).onFailure(testContext::failNow);

        // Wait a bit to ensure message is committed to database
        Checkpoint setupCheckpoint = testContext.checkpoint();
        vertx.setTimer(500, setupId -> {
            setupCheckpoint.flag();
        });

        // Set up all consumers to compete for the same message
        for (int i = 0; i < consumerCount; i++) {
            final int consumerIndex = i;
            MessageConsumer<String> consumer = topicConsumers.get(i);

            consumer.subscribe(message -> {
                totalAttempts.incrementAndGet();

                // Simulate some processing time to increase chance of race conditions
                Promise<Void> result = Promise.promise();
                vertx.setTimer(100, timerId -> {
                    // Only one consumer should successfully process the message
                    if (successfulProcessing.incrementAndGet() == 1) {
                        processingCheckpoint.flag();

                        // Add delay to allow database completion, verify, then signal completion
                        vertx.setTimer(1000, timerId2 ->
                            verifyMessageProcessedOnce(testMessage)
                                .onSuccess(v -> completionCheckpoint.flag())
                                .onFailure(testContext::failNow));
                    } else {
                        testContext.failNow(new AssertionError(
                            "Multiple consumers processed the same message - database locking failed"));
                    }
                    result.complete();
                });
                return result.future();
            });
        }
        
        // Wait for processing to complete
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Message should be processed by exactly one consumer");

        // Synchronous assertions after awaitCompletion are fine in the test method body
        assertEquals(1, successfulProcessing.get(),
            "Exactly one consumer should have successfully processed the message");
    }

    @Test
    @DisplayName("CONCURRENCY: Race conditions in retry count updates")
    void testRaceConditionsInRetryCountUpdates(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        String testTopic = "test-retry-race-conditions-" + UUID.randomUUID().toString().substring(0, 8);
        MessageProducer<String> producer = outboxFactory.createProducer(testTopic, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(testTopic, String.class);
        producers.add(producer);
        consumers.add(consumer);
        
        String testMessage = "Message for retry race condition test";
        AtomicInteger attemptCount = new AtomicInteger(0);
        AtomicInteger retryFlags = new AtomicInteger(0);
        Checkpoint retryCheckpoint = testContext.checkpoint(4); // Initial + 3 retries
        Checkpoint verifyCheckpoint = testContext.checkpoint();

        // Send message first
        producer.send(testMessage).onFailure(testContext::failNow);

        // Set up consumer that always fails to trigger retry logic
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();

            retryCheckpoint.flag();
            if (retryFlags.incrementAndGet() == 4) {
                // All retries done — verify retry count consistency before completing
                verifyRetryCountConsistency(testMessage, 3)
                    .onSuccess(v -> verifyCheckpoint.flag())
                    .onFailure(testContext::failNow);
            }

            // Simulate concurrent retry count updates by adding some processing time
            Promise<Void> result = Promise.promise();
            vertx.setTimer(50, timerId -> {
                result.fail(new RuntimeException("INTENTIONAL FAILURE: Testing retry race conditions, attempt " + attempt));
            });
            return result.future();
        });
        
        // Wait for all retry attempts
        assertTrue(testContext.awaitCompletion(20, TimeUnit.SECONDS), "Should have attempted processing 4 times");
        assertEquals(4, attemptCount.get(), "Should have made exactly 4 processing attempts");
    }

    /**
     * Verifies that a message was processed exactly once.
     */
    private Future<Void> verifyMessageProcessedOnce(String expectedPayload) {
        return testReactivePool.withConnection(connection ->
            connection.preparedQuery("SELECT COUNT(*) FROM outbox WHERE payload::text LIKE $1 AND status = 'COMPLETED'")
                .execute(io.vertx.sqlclient.Tuple.of("%" + expectedPayload + "%"))
                .map(rowSet -> rowSet.iterator().next().getInteger(0))
        ).map(count -> {
            assertNotNull(count, "Should have received count from database");
            assertEquals(1, count, "Message should be processed exactly once");
            logger.info("Verified message processed once: {} completed entries found", count);
            return (Void) null;
        });
    }

    @Test
    @DisplayName("CONCURRENCY: Thread pool exhaustion during message processing")
    void testThreadPoolExhaustionDuringMessageProcessing(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        String testTopic = "test-thread-exhaustion-" + UUID.randomUUID().toString().substring(0, 8);
        MessageProducer<String> producer = outboxFactory.createProducer(testTopic, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(testTopic, String.class);
        producers.add(producer);
        consumers.add(consumer);

        // Send multiple messages to exhaust thread pool
        int messageCount = 15; // More than thread pool size
        List<String> testMessages = new ArrayList<>();

        for (int i = 1; i <= messageCount; i++) {
            String message = "Thread exhaustion test message " + i;
            testMessages.add(message);
            producer.send(message).onFailure(testContext::failNow);
        }

        AtomicInteger processedCount = new AtomicInteger(0);
        Checkpoint processingCheckpoint = testContext.checkpoint(messageCount);

        // Set up consumer that simulates slow processing to exhaust threads
        consumer.subscribe(message -> {
            processedCount.incrementAndGet();

            // Simulate slow processing via non-blocking delay
            Promise<Void> result = Promise.promise();
            vertx.setTimer(200, timerId -> {
                processingCheckpoint.flag();
                result.complete();
            });
            return result.future();
        });

        // Wait for all messages to be processed (with generous timeout)
        boolean completed = testContext.awaitCompletion(30, TimeUnit.SECONDS);
        assertTrue(completed, "All messages should eventually be processed despite thread exhaustion");

        // Verify all messages were processed
        assertEquals(messageCount, processedCount.get(), "All messages should be processed");
    }

    @Test
    @DisplayName("CONCURRENCY: Thread safety during consumer shutdown")
    void testThreadSafetyDuringConsumerShutdown(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        String testTopic = "test-shutdown-safety-" + UUID.randomUUID().toString().substring(0, 8);
        MessageProducer<String> producer = outboxFactory.createProducer(testTopic, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(testTopic, String.class);
        producers.add(producer);
        consumers.add(consumer);

        // Send messages that will be processing during shutdown
        int messageCount = 10;
        for (int i = 1; i <= messageCount; i++) {
            String message = "Shutdown safety test message " + i;
            producer.send(message).onFailure(testContext::failNow);
        }

        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicBoolean closeSucceeded = new AtomicBoolean(false);
        Checkpoint shutdownCheckpoint = testContext.checkpoint();

        // Set up consumer with processing that can be interrupted
        consumer.subscribe(message -> {
            processedCount.incrementAndGet();

            // Simulate processing time during which shutdown might occur
            Promise<Void> result = Promise.promise();
            vertx.setTimer(300, timerId -> result.complete());
            return result.future();
        });

        // Allow some processing to start, then shutdown consumer
        vertx.setTimer(500, id1 -> {
            try {
                consumer.close();
                closeSucceeded.set(true);
            } catch (Exception e) {
                logger.error("Consumer close failed during shutdown test", e);
            }
            shutdownCheckpoint.flag();
        });

        // Wait for shutdown to complete
        boolean completed = testContext.awaitCompletion(15, TimeUnit.SECONDS);
        assertTrue(completed, "Consumer shutdown should complete");

        assertTrue(closeSucceeded.get(), "Consumer should have closed without exception during active processing");
        assertTrue(processedCount.get() >= 1, "At least 1 message should have completed in the window before shutdown");
    }

    @Test
    @DisplayName("CONCURRENCY: High-load concurrent retry processing")
    void testHighLoadConcurrentRetryProcessing(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        String testTopic = "test-high-load-retry-" + UUID.randomUUID().toString().substring(0, 8);
        MessageProducer<String> producer = outboxFactory.createProducer(testTopic, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(testTopic, String.class);
        producers.add(producer);
        consumers.add(consumer);

        // Send multiple messages that will all fail and retry
        int messageCount = 20;
        List<String> testMessages = new ArrayList<>();

        for (int i = 1; i <= messageCount; i++) {
            String message = "High load retry test message " + i;
            testMessages.add(message);
            producer.send(message).onFailure(testContext::failNow);
        }

        AtomicInteger totalAttempts = new AtomicInteger(0);
        int totalExpected = messageCount * 4;
        Checkpoint retryCheckpoint = testContext.checkpoint(totalExpected); // Each message: initial + 3 retries
        Checkpoint verifyCheckpoint = testContext.checkpoint();

        // Set up consumer that always fails to trigger maximum retries
        consumer.subscribe(message -> {
            int attempt = totalAttempts.incrementAndGet();

            retryCheckpoint.flag();
            if (attempt == totalExpected) {
                // All retries done — wait 2 s for DLQ processor then verify
                vertx.setTimer(2000, timerId ->
                    verifyAllMessagesInDeadLetterQueue(testMessages)
                        .onSuccess(v -> verifyCheckpoint.flag())
                        .onFailure(testContext::failNow));
            }

            // Add small delay to simulate processing and increase concurrency pressure
            Promise<Void> result = Promise.promise();
            vertx.setTimer(10, timerId -> {
                result.fail(new RuntimeException("INTENTIONAL FAILURE: High-load retry test, attempt " + attempt));
            });
            return result.future();
        });

        // Wait for all retry attempts (generous timeout for high load + 2 s DLQ timer + verification)
        assertTrue(testContext.awaitCompletion(90, TimeUnit.SECONDS), "All retry attempts should complete under high load");

        // Synchronous assertion after awaitCompletion is fine in the test method body
        int expectedAttempts = messageCount * 4;
        assertEquals(expectedAttempts, totalAttempts.get(),
            "Should have exactly " + expectedAttempts + " total attempts under high load");
    }

    /**
     * Verifies that all test messages have been moved to the dead letter queue.
     */
    private Future<Void> verifyAllMessagesInDeadLetterQueue(List<String> expectedMessages) {
        Future<Integer> foundCountFuture = Future.succeededFuture(0);
        for (String message : expectedMessages) {
            foundCountFuture = foundCountFuture.compose(acc ->
                testReactivePool.withConnection(connection ->
                    connection.preparedQuery("SELECT COUNT(*) FROM dead_letter_queue WHERE payload::text LIKE $1")
                        .execute(io.vertx.sqlclient.Tuple.of("%" + message + "%"))
                        .map(rowSet -> rowSet.iterator().next().getInteger(0))
                ).map(count -> acc + (count != null && count > 0 ? 1 : 0))
            );
        }
        return foundCountFuture.map(foundCount -> {
            logger.info("Dead letter queue verification: {}/{} messages found",
                foundCount, expectedMessages.size());
            assertEquals(expectedMessages.size(), foundCount,
                "All messages should be in dead letter queue, found " + foundCount + "/" + expectedMessages.size());
            return (Void) null;
        });
    }

    /**
     * Verifies retry count consistency after concurrent updates.
     */
    private Future<Void> verifyRetryCountConsistency(String expectedPayload, int expectedRetryCount) {
        return testReactivePool.withConnection(connection ->
            connection.preparedQuery("SELECT retry_count, status FROM outbox WHERE payload::text LIKE $1 ORDER BY created_at DESC LIMIT 1")
                .execute(io.vertx.sqlclient.Tuple.of("%" + expectedPayload + "%"))
                .map(rowSet -> {
                    assertTrue(rowSet.iterator().hasNext(), "Should find message in outbox");
                    io.vertx.sqlclient.Row row = rowSet.iterator().next();
                    int actualRetryCount = row.getInteger("retry_count");
                    String status = row.getString("status");
                    logger.info("Retry count verification: expected={}, actual={}, status={}",
                        expectedRetryCount, actualRetryCount, status);
                    assertEquals(expectedRetryCount, actualRetryCount,
                        "Retry count should be consistent despite concurrent updates");
                    return (Void) null;
                })
        );
    }
}


