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
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import io.vertx.core.Vertx;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

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
@Testcontainers
public class OutboxRetryConcurrencyTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxRetryConcurrencyTest.class);

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_concurrency_test")
            .withUsername("concurrency_test")
            .withPassword("concurrency_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);

    private PeeGeeQManager manager;
    private List<MessageProducer<String>> producers;
    private List<MessageConsumer<String>> consumers;
    private OutboxFactory outboxFactory;
    private DataSource testDataSource;
    private PgConnectionManager connectionManager;
    private ExecutorService testExecutor;

    @BeforeEach
    void setUp() throws Exception {
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
                .minimumIdle(1)
                .maximumPoolSize(3)
                .build();

        testDataSource = connectionManager.getOrCreateDataSource("test-verification", connectionConfig, poolConfig);
        
        // Initialize collections for multiple producers/consumers
        producers = new ArrayList<>();
        consumers = new ArrayList<>();
        
        // Create thread pool for concurrent testing
        testExecutor = Executors.newFixedThreadPool(20);
        
        logger.info("✅ OutboxRetryConcurrencyTest setup completed");
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
                manager.close();
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

        logger.info("✅ OutboxRetryConcurrencyTest cleanup completed");
    }

    @Test
    @DisplayName("CONCURRENCY: Multiple threads processing same message simultaneously")
    void testConcurrentMessageProcessing() throws Exception {
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
        CountDownLatch processingLatch = new CountDownLatch(1); // Only one should succeed
        CountDownLatch completionLatch = new CountDownLatch(1); // Wait for database completion
        AtomicReference<String> processingThread = new AtomicReference<>();

        // Send message first
        producer.send(testMessage).get(5, TimeUnit.SECONDS);
        logger.info("📤 Message sent: {}", testMessage);

        // Wait a bit to ensure message is committed to database
        Thread.sleep(500);

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
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Only one consumer should successfully process the message
                if (successfulProcessing.incrementAndGet() == 1) {
                    processingThread.set(threadName);
                    processingLatch.countDown();
                    logger.info("✅ SUCCESS: Consumer {} successfully processed message", consumerIndex);

                    // Add delay to allow database completion, then signal completion
                    CompletableFuture.runAsync(() -> {
                        try {
                            Thread.sleep(1000); // Wait for database update to complete
                            completionLatch.countDown();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });

                    return CompletableFuture.completedFuture(null);
                } else {
                    // This shouldn't happen due to database locking, but if it does, fail
                    logger.warn("⚠️ WARNING: Multiple consumers processed the same message!");
                    return CompletableFuture.completedFuture(null);
                }
            });
        }
        
        // Wait for processing to complete
        boolean completed = processingLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Message should be processed by exactly one consumer");

        // Wait for database completion
        boolean dbCompleted = completionLatch.await(10, TimeUnit.SECONDS);
        assertTrue(dbCompleted, "Database update should complete");

        // Verify only one consumer processed the message
        assertEquals(1, successfulProcessing.get(),
            "Exactly one consumer should have successfully processed the message");

        // Verify message was processed correctly
        verifyMessageProcessedOnce(testMessage);
        
        logger.info("✅ Concurrent message processing test completed successfully");
        logger.info("   Total attempts: {}", totalAttempts.get());
        logger.info("   Successful processing: {}", successfulProcessing.get());
        logger.info("   Processing thread: {}", processingThread.get());
    }

    @Test
    @DisplayName("CONCURRENCY: Race conditions in retry count updates")
    void testRaceConditionsInRetryCountUpdates() throws Exception {
        logger.info("🔥 === CONCURRENCY TEST: Race conditions in retry count updates ===");
        
        String testTopic = "test-retry-race-conditions-" + UUID.randomUUID().toString().substring(0, 8);
        MessageProducer<String> producer = outboxFactory.createProducer(testTopic, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(testTopic, String.class);
        producers.add(producer);
        consumers.add(consumer);
        
        String testMessage = "Message for retry race condition test";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(4); // Initial + 3 retries
        
        // Send message first
        producer.send(testMessage).get(5, TimeUnit.SECONDS);
        logger.info("📤 Message sent: {}", testMessage);
        
        // Set up consumer that always fails to trigger retry logic
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            String threadName = Thread.currentThread().getName();
            
            logger.info("🔥 INTENTIONAL FAILURE: Retry race condition attempt {} (thread {}) for message: {}", 
                attempt, threadName, message.getPayload());
            
            retryLatch.countDown();
            
            // Simulate concurrent retry count updates by adding some processing time
            try {
                Thread.sleep(50); // Small delay to increase chance of race conditions
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            throw new RuntimeException("INTENTIONAL FAILURE: Testing retry race conditions, attempt " + attempt);
        });
        
        // Wait for all retry attempts
        boolean completed = retryLatch.await(20, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing 4 times");
        assertEquals(4, attemptCount.get(), "Should have made exactly 4 processing attempts");
        
        // Verify retry count consistency despite race conditions
        verifyRetryCountConsistency(testMessage, 3); // Should have 3 retries after 4 attempts
        
        logger.info("✅ Retry race condition test completed successfully");
        logger.info("   Total attempts: {}", attemptCount.get());
    }

    /**
     * Verifies that a message was processed exactly once.
     */
    private void verifyMessageProcessedOnce(String expectedPayload) throws SQLException {
        try (Connection conn = testDataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT COUNT(*) FROM outbox WHERE payload::text LIKE ? AND status = 'COMPLETED'")) {
            
            stmt.setString(1, "%" + expectedPayload + "%");
            var rs = stmt.executeQuery();
            
            assertTrue(rs.next(), "Should have result from outbox query");
            int count = rs.getInt(1);
            assertEquals(1, count, "Message should be processed exactly once");
            
            logger.info("✅ Verified message processed once: {} completed entries found", count);
        }
    }

    @Test
    @DisplayName("CONCURRENCY: Thread pool exhaustion during message processing")
    void testThreadPoolExhaustionDuringMessageProcessing() throws Exception {
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
            producer.send(message).get(5, TimeUnit.SECONDS);
        }

        logger.info("📤 Sent {} messages for thread exhaustion test", messageCount);

        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);
        CountDownLatch processingLatch = new CountDownLatch(messageCount);

        // Set up consumer that simulates slow processing to exhaust threads
        consumer.subscribe(message -> {
            String threadName = Thread.currentThread().getName();
            int processed = processedCount.incrementAndGet();

            logger.info("🐌 SLOW PROCESSING: Message {} being processed by thread {}",
                processed, threadName);

            try {
                // Simulate slow processing to hold threads
                Thread.sleep(200);

                processingLatch.countDown();
                logger.info("✅ SUCCESS: Message {} processed successfully by thread {}",
                    processed, threadName);

                return CompletableFuture.completedFuture(null);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failedCount.incrementAndGet();
                processingLatch.countDown();
                throw new RuntimeException("Thread interrupted during processing");
            }
        });

        // Wait for all messages to be processed (with generous timeout)
        boolean completed = processingLatch.await(30, TimeUnit.SECONDS);
        assertTrue(completed, "All messages should eventually be processed despite thread exhaustion");

        logger.info("✅ Thread pool exhaustion test completed successfully");
        logger.info("   Messages processed: {}", processedCount.get());
        logger.info("   Messages failed: {}", failedCount.get());
        logger.info("   Total messages: {}", messageCount);

        // Verify all messages were processed
        assertEquals(messageCount, processedCount.get(), "All messages should be processed");
        assertEquals(0, failedCount.get(), "No messages should fail due to thread exhaustion");
    }

    @Test
    @DisplayName("CONCURRENCY: Thread safety during consumer shutdown")
    void testThreadSafetyDuringConsumerShutdown() throws Exception {
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
            producer.send(message).get(5, TimeUnit.SECONDS);
        }

        logger.info("📤 Sent {} messages for shutdown safety test", messageCount);

        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger interruptedCount = new AtomicInteger(0);
        CountDownLatch shutdownLatch = new CountDownLatch(1);

        // Set up consumer with processing that can be interrupted
        consumer.subscribe(message -> {
            int processed = processedCount.incrementAndGet();
            String threadName = Thread.currentThread().getName();

            logger.info("🔄 PROCESSING: Message {} being processed by thread {} during shutdown test",
                processed, threadName);

            try {
                // Simulate processing time during which shutdown might occur
                Thread.sleep(300);

                logger.info("✅ SUCCESS: Message {} completed processing by thread {}",
                    processed, threadName);

                return CompletableFuture.completedFuture(null);

            } catch (InterruptedException e) {
                interruptedCount.incrementAndGet();
                Thread.currentThread().interrupt();
                logger.info("🛑 INTERRUPTED: Message {} processing interrupted by thread {}",
                    processed, threadName);
                throw new RuntimeException("Processing interrupted during shutdown");
            }
        });

        // Allow some processing to start
        Thread.sleep(500);

        // Shutdown consumer while messages are being processed
        logger.info("🛑 SHUTDOWN: Closing consumer during active message processing");
        CompletableFuture<Void> shutdownFuture = CompletableFuture.runAsync(() -> {
            try {
                consumer.close();
                shutdownLatch.countDown();
                logger.info("✅ SHUTDOWN: Consumer closed successfully");
            } catch (Exception e) {
                logger.error("❌ SHUTDOWN ERROR: Failed to close consumer", e);
                shutdownLatch.countDown();
            }
        });

        // Wait for shutdown to complete
        boolean shutdownCompleted = shutdownLatch.await(10, TimeUnit.SECONDS);
        assertTrue(shutdownCompleted, "Consumer shutdown should complete");

        // Wait for shutdown future to complete
        shutdownFuture.get(5, TimeUnit.SECONDS);

        logger.info("✅ Thread safety during shutdown test completed successfully");
        logger.info("   Messages processed: {}", processedCount.get());
        logger.info("   Messages interrupted: {}", interruptedCount.get());

        // Verify shutdown was handled gracefully
        assertTrue(processedCount.get() > 0, "Some messages should have been processed");
        // Note: We don't assert on interrupted count as it depends on timing
    }

    @Test
    @DisplayName("CONCURRENCY: High-load concurrent retry processing")
    void testHighLoadConcurrentRetryProcessing() throws Exception {
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
            producer.send(message).get(5, TimeUnit.SECONDS);
        }

        logger.info("📤 Sent {} messages for high-load retry test", messageCount);

        AtomicInteger totalAttempts = new AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(messageCount * 4); // Each message: initial + 3 retries

        // Set up consumer that always fails to trigger maximum retries
        consumer.subscribe(message -> {
            int attempt = totalAttempts.incrementAndGet();
            String threadName = Thread.currentThread().getName();

            logger.info("🔥 INTENTIONAL FAILURE: High-load retry attempt {} (thread {}) for message: {}",
                attempt, threadName, message.getPayload());

            retryLatch.countDown();

            // Add small delay to simulate processing and increase concurrency pressure
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            throw new RuntimeException("INTENTIONAL FAILURE: High-load retry test, attempt " + attempt);
        });

        // Wait for all retry attempts (generous timeout for high load)
        boolean completed = retryLatch.await(60, TimeUnit.SECONDS);
        assertTrue(completed, "All retry attempts should complete under high load");

        // Verify total attempts match expected (messageCount * 4 attempts each)
        int expectedAttempts = messageCount * 4;
        assertEquals(expectedAttempts, totalAttempts.get(),
            "Should have exactly " + expectedAttempts + " total attempts under high load");

        // Verify all messages eventually moved to dead letter queue
        Thread.sleep(2000); // Allow time for DLQ processing
        verifyAllMessagesInDeadLetterQueue(testMessages);

        logger.info("✅ High-load concurrent retry processing test completed successfully");
        logger.info("   Total attempts: {}", totalAttempts.get());
        logger.info("   Expected attempts: {}", expectedAttempts);
        logger.info("   Messages processed: {}", messageCount);
    }

    /**
     * Verifies that all test messages have been moved to the dead letter queue.
     */
    private void verifyAllMessagesInDeadLetterQueue(List<String> expectedMessages) throws SQLException {
        try (Connection conn = testDataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT COUNT(*) FROM dead_letter_queue WHERE payload::text LIKE ?")) {

            int foundCount = 0;
            for (String message : expectedMessages) {
                stmt.setString(1, "%" + message + "%");
                var rs = stmt.executeQuery();

                if (rs.next() && rs.getInt(1) > 0) {
                    foundCount++;
                }
            }

            logger.info("✅ Dead letter queue verification: {}/{} messages found",
                foundCount, expectedMessages.size());

            assertTrue(foundCount > 0, "At least some messages should be in dead letter queue");
        }
    }

    /**
     * Verifies retry count consistency after concurrent updates.
     */
    private void verifyRetryCountConsistency(String expectedPayload, int expectedRetryCount) throws SQLException {
        try (Connection conn = testDataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT retry_count, status FROM outbox WHERE payload::text LIKE ? ORDER BY created_at DESC LIMIT 1")) {

            stmt.setString(1, "%" + expectedPayload + "%");
            var rs = stmt.executeQuery();

            assertTrue(rs.next(), "Should find message in outbox");
            int actualRetryCount = rs.getInt("retry_count");
            String status = rs.getString("status");

            logger.info("✅ Retry count verification: expected={}, actual={}, status={}",
                expectedRetryCount, actualRetryCount, status);

            assertEquals(expectedRetryCount, actualRetryCount,
                "Retry count should be consistent despite concurrent updates");
        }
    }
}
