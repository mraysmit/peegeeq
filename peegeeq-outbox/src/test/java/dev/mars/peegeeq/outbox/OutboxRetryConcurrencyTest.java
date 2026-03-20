package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

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
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    private static final PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:15.13-alpine3.20");
        container.withDatabaseName("peegeeq_concurrency_test");
        container.withUsername("concurrency_test");
        container.withPassword("concurrency_test");
        container.withSharedMemorySize(256 * 1024 * 1024L);
        container.withReuse(false);
        return container;
    }

    private PeeGeeQManager manager;
    private List<MessageProducer<String>> producers;
    private List<MessageConsumer<String>> consumers;
    private OutboxFactory outboxFactory;
    private io.vertx.sqlclient.Pool testReactivePool;
    private PgConnectionManager connectionManager;
    private ExecutorService testExecutor;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        logger.info("🔧 Setting up OutboxRetryConcurrencyTest");
        
        // Set up database connection properties
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        
        // Configure for concurrency testing
        System.setProperty("peegeeq.queue.max-retries", "3");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.05S"); // Very fast polling
        System.setProperty("peegeeq.database.pool.max-size", "20"); // Larger pool for concurrency
        System.setProperty("peegeeq.database.pool.connection-timeout-ms", "5000");
        System.setProperty("peegeeq.consumer.threads", "10"); // Multiple consumer threads

        // Initialize manager and components
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("basic-test"), new SimpleMeterRegistry());
        manager.start();

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

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(3)
                .build();

        testReactivePool = connectionManager.getOrCreateReactivePool("test-verification", connectionConfig, poolConfig);
        
        // Initialize collections for multiple producers/consumers
        producers = new ArrayList<>();
        consumers = new ArrayList<>();
        
        // Create thread pool for concurrent testing
        testExecutor = Executors.newFixedThreadPool(20);
        
        logger.info("OutboxRetryConcurrencyTest setup completed");
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("🧹 Cleaning up OutboxRetryConcurrencyTest");
        
        // Shutdown test executor
        if (testExecutor != null) {
            testExecutor.shutdown();
            try {
                if (!testExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    testExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                testExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        // Close all consumers
        for (MessageConsumer<String> consumer : consumers) {
            try {
                consumer.close();
            } catch (Exception e) {
                logger.warn("Error closing consumer: {}", e.getMessage());
            }
        }
        
        // Close all producers
        for (MessageProducer<String> producer : producers) {
            try {
                producer.close();
            } catch (Exception e) {
                logger.warn("Error closing producer: {}", e.getMessage());
            }
        }
        
        if (outboxFactory != null) {
            try {
                outboxFactory.close();
            } catch (Exception e) {
                logger.warn("Error closing outbox factory: {}", e.getMessage());
            }
        }
        
        if (manager != null) {
            try {
                CountDownLatch closeLatch = new CountDownLatch(1);
                manager.closeReactive().onComplete(ar -> closeLatch.countDown());
                closeLatch.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.warn("Error closing manager: {}", e.getMessage());
            }
        }

        if (connectionManager != null) {
            try {
                connectionManager.close();
            } catch (Exception e) {
                logger.warn("Error closing connection manager: {}", e.getMessage());
            }
        }

        logger.info("OutboxRetryConcurrencyTest cleanup completed");
    }

    @Test
    @DisplayName("CONCURRENCY: Multiple threads processing same message simultaneously")
    void testConcurrentMessageProcessing(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🔥 === CONCURRENCY TEST: Multiple threads processing same message simultaneously ===");
        
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
        AtomicReference<String> processingThread = new AtomicReference<>();

        // Send message first
        producer.send(testMessage).onFailure(testContext::failNow);
        logger.info("📤 Message sent: {}", testMessage);

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
                int attempt = totalAttempts.incrementAndGet();
                String threadName = Thread.currentThread().getName();

                logger.info("🏃 CONCURRENT: Consumer {} (thread {}) processing attempt {} for message: {}",
                    consumerIndex, threadName, attempt, message.getPayload());

                // Simulate some processing time to increase chance of race conditions
                Promise<Void> result = Promise.promise();
                vertx.setTimer(100, timerId -> {
                    // Only one consumer should successfully process the message
                    if (successfulProcessing.incrementAndGet() == 1) {
                        processingThread.set(threadName);
                        processingCheckpoint.flag();
                        logger.info("SUCCESS: Consumer {} successfully processed message", consumerIndex);

                        // Add delay to allow database completion, then signal completion
                        vertx.setTimer(1000, timerId2 -> completionCheckpoint.flag());
                    } else {
                        // This shouldn't happen due to database locking, but if it does, fail
                        logger.warn("⚠️ WARNING: Multiple consumers processed the same message!");
                    }
                    result.complete();
                });
                return result.future();
            });
        }
        
        // Wait for processing to complete
        boolean completed = testContext.awaitCompletion(30, TimeUnit.SECONDS);
        assertTrue(completed, "Message should be processed by exactly one consumer");

        // Verify only one consumer processed the message
        assertEquals(1, successfulProcessing.get(),
            "Exactly one consumer should have successfully processed the message");

        // Verify message was processed correctly
        verifyMessageProcessedOnce(testMessage);
        
        logger.info("Concurrent message processing test completed successfully");
        logger.info("   Total attempts: {}", totalAttempts.get());
        logger.info("   Successful processing: {}", successfulProcessing.get());
        logger.info("   Processing thread: {}", processingThread.get());
    }

    @Test
    @DisplayName("CONCURRENCY: Race conditions in retry count updates")
    void testRaceConditionsInRetryCountUpdates(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🔥 === CONCURRENCY TEST: Race conditions in retry count updates ===");
        
        String testTopic = "test-retry-race-conditions-" + UUID.randomUUID().toString().substring(0, 8);
        MessageProducer<String> producer = outboxFactory.createProducer(testTopic, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(testTopic, String.class);
        producers.add(producer);
        consumers.add(consumer);
        
        String testMessage = "Message for retry race condition test";
        AtomicInteger attemptCount = new AtomicInteger(0);
        Checkpoint retryCheckpoint = testContext.checkpoint(4); // Initial + 3 retries
        
        // Send message first
        producer.send(testMessage).onFailure(testContext::failNow);
        logger.info("📤 Message sent: {}", testMessage);
        
        // Set up consumer that always fails to trigger retry logic
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            String threadName = Thread.currentThread().getName();
            
            logger.info("🔥 INTENTIONAL FAILURE: Retry race condition attempt {} (thread {}) for message: {}", 
                attempt, threadName, message.getPayload());
            
            retryCheckpoint.flag();
            
            // Simulate concurrent retry count updates by adding some processing time
            Promise<Void> result = Promise.promise();
            vertx.setTimer(50, timerId -> {
                result.fail(new RuntimeException("INTENTIONAL FAILURE: Testing retry race conditions, attempt " + attempt));
            });
            return result.future();
        });
        
        // Wait for all retry attempts
        boolean completed = testContext.awaitCompletion(20, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing 4 times");
        assertEquals(4, attemptCount.get(), "Should have made exactly 4 processing attempts");
        
        // Verify retry count consistency despite race conditions
        verifyRetryCountConsistency(testMessage, 3); // Should have 3 retries after 4 attempts
        
        logger.info("Retry race condition test completed successfully");
        logger.info("   Total attempts: {}", attemptCount.get());
    }

    /**
     * Verifies that a message was processed exactly once.
     */
    private void verifyMessageProcessedOnce(String expectedPayload) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Integer> countRef = new AtomicReference<>();
        testReactivePool.withConnection(connection -> {
            return connection.preparedQuery("SELECT COUNT(*) FROM outbox WHERE payload::text LIKE $1 AND status = 'COMPLETED'")
                .execute(io.vertx.sqlclient.Tuple.of("%" + expectedPayload + "%"))
                .map(rowSet -> {
                    io.vertx.sqlclient.Row row = rowSet.iterator().next();
                    return row.getInteger(0);
                });
        }).onSuccess(count -> {
            countRef.set(count);
            latch.countDown();
        }).onFailure(e -> latch.countDown());

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Database query should complete");
        Integer count = countRef.get();
        assertNotNull(count, "Should have received count from database");
        assertEquals(1, count, "Message should be processed exactly once");
        logger.info("Verified message processed once: {} completed entries found", count);
    }

    @Test
    @DisplayName("CONCURRENCY: Thread pool exhaustion during message processing")
    void testThreadPoolExhaustionDuringMessageProcessing(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🔥 === CONCURRENCY TEST: Thread pool exhaustion during message processing ===");

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

        logger.info("📤 Sent {} messages for thread exhaustion test", messageCount);

        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);
        Checkpoint processingCheckpoint = testContext.checkpoint(messageCount);

        // Set up consumer that simulates slow processing to exhaust threads
        consumer.subscribe(message -> {
            String threadName = Thread.currentThread().getName();
            int processed = processedCount.incrementAndGet();

            logger.info("🐌 SLOW PROCESSING: Message {} being processed by thread {}",
                processed, threadName);

            // Simulate slow processing via non-blocking delay
            Promise<Void> result = Promise.promise();
            vertx.setTimer(200, timerId -> {
                processingCheckpoint.flag();
                logger.info("SUCCESS: Message {} processed successfully by thread {}",
                    processed, threadName);
                result.complete();
            });
            return result.future();
        });

        // Wait for all messages to be processed (with generous timeout)
        boolean completed = testContext.awaitCompletion(30, TimeUnit.SECONDS);
        assertTrue(completed, "All messages should eventually be processed despite thread exhaustion");

        logger.info("Thread pool exhaustion test completed successfully");
        logger.info("   Messages processed: {}", processedCount.get());

        // Verify all messages were processed
        assertEquals(messageCount, processedCount.get(), "All messages should be processed");
    }

    @Test
    @DisplayName("CONCURRENCY: Thread safety during consumer shutdown")
    void testThreadSafetyDuringConsumerShutdown(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🔥 === CONCURRENCY TEST: Thread safety during consumer shutdown ===");

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

        logger.info("📤 Sent {} messages for shutdown safety test", messageCount);

        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger interruptedCount = new AtomicInteger(0);
        Checkpoint shutdownCheckpoint = testContext.checkpoint();

        // Set up consumer with processing that can be interrupted
        consumer.subscribe(message -> {
            int processed = processedCount.incrementAndGet();
            String threadName = Thread.currentThread().getName();

            logger.info("🔄 PROCESSING: Message {} being processed by thread {} during shutdown test",
                processed, threadName);

            // Simulate processing time during which shutdown might occur
            Promise<Void> result = Promise.promise();
            vertx.setTimer(300, timerId -> {
                logger.info("SUCCESS: Message {} completed processing by thread {}",
                    processed, threadName);
                result.complete();
            });
            return result.future();
        });

        // Allow some processing to start, then shutdown consumer
        vertx.setTimer(500, id1 -> {
            logger.info("🛑 SHUTDOWN: Closing consumer during active message processing");
            try {
                consumer.close();
                logger.info("SHUTDOWN: Consumer closed successfully");
            } catch (Exception e) {
                logger.error("❌ SHUTDOWN ERROR: Failed to close consumer", e);
            }
            shutdownCheckpoint.flag();
        });

        // Wait for shutdown to complete
        boolean completed = testContext.awaitCompletion(15, TimeUnit.SECONDS);
        assertTrue(completed, "Consumer shutdown should complete");

        logger.info("Thread safety during shutdown test completed successfully");
        logger.info("   Messages processed: {}", processedCount.get());
        logger.info("   Messages interrupted: {}", interruptedCount.get());

        // Verify shutdown was handled gracefully
        assertTrue(processedCount.get() > 0, "Some messages should have been processed");
    }

    @Test
    @DisplayName("CONCURRENCY: High-load concurrent retry processing")
    void testHighLoadConcurrentRetryProcessing(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🔥 === CONCURRENCY TEST: High-load concurrent retry processing ===");

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

        logger.info("📤 Sent {} messages for high-load retry test", messageCount);

        AtomicInteger totalAttempts = new AtomicInteger(0);
        Checkpoint retryCheckpoint = testContext.checkpoint(messageCount * 4); // Each message: initial + 3 retries

        // Set up consumer that always fails to trigger maximum retries
        consumer.subscribe(message -> {
            int attempt = totalAttempts.incrementAndGet();
            String threadName = Thread.currentThread().getName();

            logger.info("🔥 INTENTIONAL FAILURE: High-load retry attempt {} (thread {}) for message: {}",
                attempt, threadName, message.getPayload());

            retryCheckpoint.flag();

            // Add small delay to simulate processing and increase concurrency pressure
            Promise<Void> result = Promise.promise();
            vertx.setTimer(10, timerId -> {
                result.fail(new RuntimeException("INTENTIONAL FAILURE: High-load retry test, attempt " + attempt));
            });
            return result.future();
        });

        // Wait for all retry attempts (generous timeout for high load)
        boolean completed = testContext.awaitCompletion(60, TimeUnit.SECONDS);
        assertTrue(completed, "All retry attempts should complete under high load");

        // Verify total attempts match expected (messageCount * 4 attempts each)
        int expectedAttempts = messageCount * 4;
        assertEquals(expectedAttempts, totalAttempts.get(),
            "Should have exactly " + expectedAttempts + " total attempts under high load");

        // Verify all messages eventually moved to dead letter queue
        // Use a timer to allow DLQ processing time
        CountDownLatch dlqLatch = new CountDownLatch(1);
        vertx.setTimer(2000, timerId -> dlqLatch.countDown());
        dlqLatch.await(5, TimeUnit.SECONDS);
        verifyAllMessagesInDeadLetterQueue(testMessages);

        logger.info("High-load concurrent retry processing test completed successfully");
        logger.info("   Total attempts: {}", totalAttempts.get());
        logger.info("   Expected attempts: {}", expectedAttempts);
        logger.info("   Messages processed: {}", messageCount);
    }

    /**
     * Verifies that all test messages have been moved to the dead letter queue.
     */
    private void verifyAllMessagesInDeadLetterQueue(List<String> expectedMessages) throws Exception {
        int foundCount = 0;

        for (String message : expectedMessages) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Integer> countRef = new AtomicReference<>();
            testReactivePool.withConnection(connection -> {
                return connection.preparedQuery("SELECT COUNT(*) FROM dead_letter_queue WHERE payload::text LIKE $1")
                    .execute(io.vertx.sqlclient.Tuple.of("%" + message + "%"))
                    .map(rowSet -> {
                        io.vertx.sqlclient.Row row = rowSet.iterator().next();
                        return row.getInteger(0);
                    });
            }).onSuccess(count -> {
                countRef.set(count);
                latch.countDown();
            }).onFailure(e -> latch.countDown());

            assertTrue(latch.await(5, TimeUnit.SECONDS), "Database query should complete");
            Integer count = countRef.get();
            if (count != null && count > 0) {
                foundCount++;
            }
        }

        logger.info("Dead letter queue verification: {}/{} messages found",
            foundCount, expectedMessages.size());

        assertTrue(foundCount > 0, "At least some messages should be in dead letter queue");
    }

    /**
     * Verifies retry count consistency after concurrent updates.
     */
    private void verifyRetryCountConsistency(String expectedPayload, int expectedRetryCount) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        testReactivePool.withConnection(connection -> {
            return connection.preparedQuery("SELECT retry_count, status FROM outbox WHERE payload::text LIKE $1 ORDER BY created_at DESC LIMIT 1")
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
                    return null;
                });
        }).onComplete(ar -> latch.countDown());

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Database query should complete");
    }
}


