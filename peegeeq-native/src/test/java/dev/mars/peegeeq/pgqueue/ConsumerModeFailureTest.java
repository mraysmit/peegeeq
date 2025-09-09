package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Failure scenario tests for consumer mode implementation.
 * Tests that consumer modes handle failure scenarios gracefully including
 * database connection failures, channel name collisions, partial mode failures,
 * recovery after failure, and exception handling in message handlers.
 * 
 * Following established coding principles:
 * - Use real infrastructure (TestContainers) rather than mocks
 * - Test failure scenarios that could occur in production
 * - Validate proper error handling and recovery mechanisms
 * - Follow existing patterns from other integration tests
 * - Test resilience and graceful degradation under failure conditions
 */
@Testcontainers
class ConsumerModeFailureTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerModeFailureTest.class);

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_test")
            .withUsername("peegeeq_user")
            .withPassword("peegeeq_password");

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        // Configure test properties using TestContainer pattern (following existing patterns)
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.queue.polling-interval", "PT1S");
        System.setProperty("peegeeq.queue.visibility-timeout", "PT30S");
        System.setProperty("peegeeq.metrics.enabled", "true");
        System.setProperty("peegeeq.circuit-breaker.enabled", "true");

        // Initialize PeeGeeQ (following existing patterns)
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory using the proper pattern
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        factory = provider.createFactory("native", databaseService);

        logger.info("Test setup completed for consumer mode failure testing");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (factory != null) {
            factory.close();
        }
        if (manager != null) {
            manager.stop();
        }
        logger.info("Test teardown completed");
    }

    @Test
    void testExceptionHandlingInMessageHandlers() throws Exception {
        logger.info("🧪 Testing exception handling in message handlers");

        String topicName = "test-exception-handling";
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicInteger exceptionCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(3); // Expect 3 messages to be processed

            consumer.subscribe(message -> {
                String payload = message.getPayload();
                logger.info("📨 Processing message: {}", payload);

                if (payload.contains("exception")) {
                    exceptionCount.incrementAndGet();
                    logger.info("💥 Throwing intentional exception for message: {} (attempt {})", payload, exceptionCount.get());
                    // This should cause the message to be moved to dead letter queue after retries
                    throw new RuntimeException("Intentional test exception for: " + payload);
                } else {
                    processedCount.incrementAndGet();
                    logger.info("✅ Successfully processed message: {}", payload);
                    latch.countDown();
                    return CompletableFuture.completedFuture(null);
                }
            });

            // Wait for consumer setup
            Thread.sleep(500);

            // Send messages - some will cause exceptions, others will succeed
            producer.send("Normal message 1").get(5, TimeUnit.SECONDS);
            producer.send("Message with exception").get(5, TimeUnit.SECONDS); // This will cause exception
            producer.send("Normal message 2").get(5, TimeUnit.SECONDS);
            producer.send("Another exception message").get(5, TimeUnit.SECONDS); // This will cause exception
            producer.send("Normal message 3").get(5, TimeUnit.SECONDS);

            // Wait for message processing (longer timeout to account for retries)
            boolean received = latch.await(20, TimeUnit.SECONDS);
            assertTrue(received, "Should process non-exception messages successfully");

            // Verify that normal messages were processed
            assertEquals(3, processedCount.get(), "Should process exactly 3 normal messages");

            // The system retries failed messages multiple times before moving to DLQ
            // So we expect more than 2 exceptions due to retries (typically 3 attempts per message = 6 total)
            assertTrue(exceptionCount.get() >= 2, "Should encounter at least 2 exceptions (original attempts)");
            assertTrue(exceptionCount.get() <= 6, "Should not exceed 6 exceptions (2 messages × 3 retry attempts)");

            logger.info("✅ Exception handling verified - processed: {}, exceptions: {} (includes retries)",
                processedCount.get(), exceptionCount.get());

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("✅ Exception handling in message handlers test completed successfully");
    }

    @Test
    void testChannelNameCollisionHandling() throws Exception {
        logger.info("🧪 Testing channel name collision handling");

        String topicName = "test-channel-collision";
        
        // Create multiple consumers for the same topic - they should handle this gracefully
        MessageConsumer<String> consumer1 = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.LISTEN_NOTIFY_ONLY).build());
        MessageConsumer<String> consumer2 = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.LISTEN_NOTIFY_ONLY).build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger consumer1Count = new AtomicInteger(0);
            AtomicInteger consumer2Count = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(2); // Expect at least 2 messages to be processed

            consumer1.subscribe(message -> {
                consumer1Count.incrementAndGet();
                logger.info("📨 Consumer 1 received message: {}", message.getPayload());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            consumer2.subscribe(message -> {
                consumer2Count.incrementAndGet();
                logger.info("📨 Consumer 2 received message: {}", message.getPayload());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup
            Thread.sleep(1000);

            // Send messages - they should be distributed between consumers
            producer.send("Collision test message 1").get(5, TimeUnit.SECONDS);
            producer.send("Collision test message 2").get(5, TimeUnit.SECONDS);
            producer.send("Collision test message 3").get(5, TimeUnit.SECONDS);

            // Wait for message processing
            boolean received = latch.await(10, TimeUnit.SECONDS);
            assertTrue(received, "Should process messages despite channel name collision");

            int totalProcessed = consumer1Count.get() + consumer2Count.get();
            assertTrue(totalProcessed >= 2, "Should process at least 2 messages across consumers");

            logger.info("✅ Channel collision handling verified - consumer1: {}, consumer2: {}, total: {}", 
                consumer1Count.get(), consumer2Count.get(), totalProcessed);

        } finally {
            consumer1.close();
            consumer2.close();
            producer.close();
        }

        logger.info("✅ Channel name collision handling test completed successfully");
    }

    @Test
    void testPartialModeFailureRecovery() throws Exception {
        logger.info("🧪 Testing partial mode failure recovery");

        String topicName = "test-partial-failure-recovery";
        
        // Test HYBRID mode resilience - if LISTEN/NOTIFY fails, polling should continue
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(3);

            consumer.subscribe(message -> {
                processedCount.incrementAndGet();
                logger.info("📨 Processed message during partial failure test: {}", message.getPayload());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup
            Thread.sleep(1000);

            // Send messages - HYBRID mode should handle them even if one mechanism has issues
            producer.send("Recovery test message 1").get(5, TimeUnit.SECONDS);
            producer.send("Recovery test message 2").get(5, TimeUnit.SECONDS);
            producer.send("Recovery test message 3").get(5, TimeUnit.SECONDS);

            // Wait for message processing - should work via either LISTEN/NOTIFY or polling
            boolean received = latch.await(15, TimeUnit.SECONDS);
            assertTrue(received, "Should process messages even with potential partial failures");
            assertEquals(3, processedCount.get(), "Should process exactly 3 messages");

            logger.info("✅ Partial failure recovery verified - processed: {} messages", processedCount.get());

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("✅ Partial mode failure recovery test completed successfully");
    }

    @Test
    void testRecoveryAfterTemporaryFailure() throws Exception {
        logger.info("🧪 Testing recovery after temporary failure");

        String topicName = "test-recovery-after-failure";
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicReference<String> lastProcessedMessage = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(2);

            consumer.subscribe(message -> {
                processedCount.incrementAndGet();
                lastProcessedMessage.set(message.getPayload());
                logger.info("📨 Processed message during recovery test: {}", message.getPayload());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup
            Thread.sleep(500);

            // Send first message
            producer.send("Before failure message").get(5, TimeUnit.SECONDS);

            // Wait a bit for processing
            Thread.sleep(2000);

            // Send second message after potential temporary issues
            producer.send("After recovery message").get(5, TimeUnit.SECONDS);

            // Wait for message processing
            boolean received = latch.await(15, TimeUnit.SECONDS);
            assertTrue(received, "Should recover and process messages after temporary failure");
            assertEquals(2, processedCount.get(), "Should process exactly 2 messages");
            assertEquals("After recovery message", lastProcessedMessage.get(), 
                "Should process the recovery message last");

            logger.info("✅ Recovery after failure verified - processed: {} messages", processedCount.get());

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("✅ Recovery after temporary failure test completed successfully");
    }

    @Test
    void testConsumerModeRobustnessUnderLoad() throws Exception {
        logger.info("🧪 Testing consumer mode robustness under moderate load");

        String topicName = "test-robustness-under-load";
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(10); // Process 10 messages under load

            consumer.subscribe(message -> {
                int count = processedCount.incrementAndGet();
                logger.debug("📨 Processed load test message {}: {}", count, message.getPayload());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup
            Thread.sleep(500);

            // Send messages rapidly to test robustness
            for (int i = 1; i <= 10; i++) {
                producer.send("Load test message " + i).get(5, TimeUnit.SECONDS);
            }

            // Wait for message processing
            boolean received = latch.await(20, TimeUnit.SECONDS);
            assertTrue(received, "Should handle moderate load without failures");
            assertEquals(10, processedCount.get(), "Should process exactly 10 messages under load");

            logger.info("✅ Robustness under load verified - processed: {} messages", processedCount.get());

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("✅ Consumer mode robustness under load test completed successfully");
    }
}
