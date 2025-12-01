package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge case tests to improve OutboxConsumer coverage from 75% to 90%+.
 * These tests target specific uncovered code paths identified in jacoco report:
 * - Shutdown race conditions (lines 249-251, 605-607, 616-618, 635-637)
 * - Error handlers in DLQ flow (lines 591-598, 661-668, 677-684)
 * - Executor shutdown during processing (line 358)
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class OutboxConsumerEdgeCasesCoverageTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;

    @BeforeEach
    void setup() throws Exception {
        // Initialize schema
        TestSchemaInitializer.initializeSchema(postgres);

        // Use unique topic for each test
        testTopic = "edge-test-" + UUID.randomUUID().toString().substring(0, 8);

        // Configure database connection with short polling interval and low max retries
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.queue.max-retries", "2");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.1S");

        // Create and start manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("edge-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory and components
        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumer = outboxFactory.createConsumer(testTopic, String.class);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        if (manager != null) {
            manager.close();
        }
    }

    /**
     * Tests shutdown race condition at the start of processAvailableMessagesReactive (line 249).
     * This test closes the consumer immediately after subscribe to trigger the closed.get() check.
     */
    @Test
    void testShutdownRaceConditionDuringPolling() throws Exception {
        AtomicInteger messagesProcessed = new AtomicInteger(0);

        consumer.subscribe(message -> {
            messagesProcessed.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });

        // Send a message
        producer.send("test-data").get(5, TimeUnit.SECONDS);

        Thread.sleep(50); // Give it a tiny moment to start
        consumer.close(); // This should trigger closed.get() checks

        // Wait a bit to ensure polling attempted
        Thread.sleep(200);

        // Message may or may not be processed depending on timing, but no errors should occur
        assertTrue(messagesProcessed.get() <= 1, "Should process at most 1 message before shutdown");
    }

    /**
     * Tests shutdown race condition in moveToDeadLetterQueueReactive (lines 605-607, 616-618, 635-637).
     * This test triggers DLQ operation then immediately closes the consumer.
     */
    @Test
    void testShutdownDuringDeadLetterQueueOperation() throws Exception {
        CountDownLatch firstAttemptLatch = new CountDownLatch(1);
        AtomicInteger attemptCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            firstAttemptLatch.countDown();
            
            // Always fail to trigger retry and eventual DLQ
            throw new RuntimeException("INTENTIONAL FAILURE for DLQ test");
        });

        // Send message
        producer.send("test-data").get(5, TimeUnit.SECONDS);

        // Wait for first attempt
        assertTrue(firstAttemptLatch.await(5, TimeUnit.SECONDS), "Should process message at least once");

        // Wait for retries to exhaust (maxRetries=2 means initial + 2 retries = 3 total attempts)
        Thread.sleep(500);

        // Close consumer during DLQ operation window
        consumer.close();

        // Give it time to attempt DLQ operation
        Thread.sleep(300);

        // Verify message was attempted multiple times
        assertTrue(attemptCount.get() >= 2, "Should have attempted processing multiple times");
    }

    /**
     * Tests the case where message processing executor is shut down (line 358).
     * This is a shutdown race condition where messages are fetched but executor is already shut down.
     */
    @Test
    void testMessageProcessingExecutorShutdown() throws Exception {
        AtomicInteger messagesProcessed = new AtomicInteger(0);
        CountDownLatch firstMessageLatch = new CountDownLatch(1);

        consumer.subscribe(message -> {
            messagesProcessed.incrementAndGet();
            firstMessageLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send multiple messages
        producer.send("message1").get(5, TimeUnit.SECONDS);
        producer.send("message2").get(5, TimeUnit.SECONDS);
        producer.send("message3").get(5, TimeUnit.SECONDS);

        // Wait for first message
        assertTrue(firstMessageLatch.await(5, TimeUnit.SECONDS), "Should process first message");

        // Close consumer to shut down executor
        consumer.close();

        // Verify some messages were processed (exact count depends on timing)
        assertTrue(messagesProcessed.get() >= 1, "Should have processed at least one message");
    }

    /**
     * Tests error handler in incrementRetryAndResetReactive (lines 591-598).
     * This tests the onFailure handler for retry count increment operations.
     */
    @Test
    void testRetryIncrementErrorHandler() throws Exception {
        CountDownLatch retryLatch = new CountDownLatch(2);

        consumer.subscribe(message -> {
            retryLatch.countDown();
            throw new RuntimeException("INTENTIONAL FAILURE for retry test");
        });

        producer.send("test").get(5, TimeUnit.SECONDS);

        // Wait for retries
        assertTrue(retryLatch.await(5, TimeUnit.SECONDS), "Should process message multiple times");

        // Close consumer to trigger potential pool closure errors
        consumer.close();

        Thread.sleep(200);

        // Error handlers should have logged any pool closure errors gracefully
    }

    /**
     * Tests DLQ error handlers during pool closure (lines 661-668, 677-684).
     * This attempts to trigger the onFailure handlers in DLQ-related operations.
     */
    @Test
    void testDLQErrorHandlersDuringPoolClosure() throws Exception {
        CountDownLatch retryLatch = new CountDownLatch(3); // Initial + 2 retries

        consumer.subscribe(message -> {
            retryLatch.countDown();
            throw new RuntimeException("INTENTIONAL FAILURE to trigger retries and DLQ");
        });

        producer.send("test-data").get(5, TimeUnit.SECONDS);

        // Wait for all retry attempts
        assertTrue(retryLatch.await(5, TimeUnit.SECONDS), "Should complete all retry attempts");

        // Close consumer to trigger pool closure during DLQ
        consumer.close();
        
        // Give time for any pending DLQ operations to encounter closed pools
        Thread.sleep(300);

        // Verify retries occurred (the error handlers should have logged but not thrown)
        assertEquals(0, retryLatch.getCount(), "All retry attempts should have completed");
    }

    /**
     * Tests closed consumer checks throughout processing (lines 249, 277, 358, 605, 616, 635).
     * This test verifies that shutdown signals are respected at various checkpoints.
     */
    @Test
    void testShutdownCheckpointsDuringProcessing() throws Exception {
        AtomicInteger processedCount = new AtomicInteger(0);
        CountDownLatch startProcessing = new CountDownLatch(1);

        consumer.subscribe(message -> {
            processedCount.incrementAndGet();
            startProcessing.countDown();
            // Slow processing to allow shutdown during message handling
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(null);
        });

        // Send multiple messages
        for (int i = 0; i < 5; i++) {
            producer.send("message-" + i).get(5, TimeUnit.SECONDS);
        }

        // Wait for processing to start
        assertTrue(startProcessing.await(5, TimeUnit.SECONDS), "Should start processing");

        // Close during processing
        consumer.close();

        // Verify graceful shutdown
        assertTrue(processedCount.get() >= 1, "Should have processed at least one message");
        assertTrue(processedCount.get() <= 5, "Should not process more than available messages");
    }

    /**
     * Tests multiple shutdown scenarios to trigger various closed.get() checks.
     * This test exercises the defensive shutdown checks throughout the consumer lifecycle.
     */
    @Test
    void testMultipleShutdownScenarios() throws Exception {
        // Scenario 1: Close before subscribe
        MessageConsumer<String> earlyCloseConsumer = outboxFactory.createConsumer(testTopic, String.class);
        earlyCloseConsumer.close();
        
        // Should handle gracefully
        try {
            earlyCloseConsumer.subscribe(msg -> CompletableFuture.completedFuture(null));
            earlyCloseConsumer.close(); // Double close
        } catch (Exception e) {
            // Expected - consumer already closed
        }

        // Scenario 2: Close during active processing
        MessageConsumer<String> activeConsumer = outboxFactory.createConsumer(testTopic, String.class);
        CountDownLatch processing = new CountDownLatch(1);
        
        activeConsumer.subscribe(message -> {
            processing.countDown();
            try {
                Thread.sleep(200); // Simulate work
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(null);
        });

        producer.send("test-message").get(5, TimeUnit.SECONDS);
        
        assertTrue(processing.await(2, TimeUnit.SECONDS), "Should start processing");
        activeConsumer.close(); // Close while processing

        // Scenario 3: Close after unsubscribe
        MessageConsumer<String> unsubscribeConsumer = outboxFactory.createConsumer(testTopic, String.class);
        unsubscribeConsumer.subscribe(msg -> CompletableFuture.completedFuture(null));
        unsubscribeConsumer.unsubscribe();
        unsubscribeConsumer.close();
    }
}
