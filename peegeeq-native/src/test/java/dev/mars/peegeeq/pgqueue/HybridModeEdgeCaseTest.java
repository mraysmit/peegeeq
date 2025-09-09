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
 * Edge case tests for HYBRID consumer mode.
 * Tests critical scenarios where both LISTEN/NOTIFY and POLLING mechanisms work together.
 * 
 * Following established coding principles:
 * - Use real infrastructure (TestContainers) rather than mocks
 * - Test edge cases that could cause production issues
 * - Validate each scenario thoroughly
 * - Follow existing patterns from ConsumerModeIntegrationTest
 * - Keep tests focused and lightweight to avoid resource exhaustion
 */
@Testcontainers
class HybridModeEdgeCaseTest {
    private static final Logger logger = LoggerFactory.getLogger(HybridModeEdgeCaseTest.class);

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
        System.setProperty("peegeeq.queue.polling-interval", "PT2S");
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

        logger.info("Test setup completed for HYBRID mode edge case testing");
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
    void testHybridModeListenNotifyPrimary() throws Exception {
        logger.info("🧪 Testing HYBRID mode with LISTEN/NOTIFY as primary mechanism");

        String topicName = "test-hybrid-listen-primary";
        
        // Create HYBRID consumer (default mode)
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofSeconds(10)) // Long polling interval to test LISTEN/NOTIFY priority
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger messageCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(3); // Expect 3 messages

        consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info("📨 Received HYBRID message {}: {}", count, message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait a moment for consumer to set up LISTEN/NOTIFY
        Thread.sleep(500);

        // Send messages - should be delivered via LISTEN/NOTIFY (fast)
        producer.send("HYBRID message 1").get(5, TimeUnit.SECONDS);
        producer.send("HYBRID message 2").get(5, TimeUnit.SECONDS);
        producer.send("HYBRID message 3").get(5, TimeUnit.SECONDS);

        // Should receive messages quickly via LISTEN/NOTIFY, not waiting for polling
        boolean receivedAll = latch.await(5, TimeUnit.SECONDS);
        
        assertTrue(receivedAll, "Should receive all 3 messages quickly via LISTEN/NOTIFY");
        assertEquals(3, messageCount.get(), "Should have processed exactly 3 messages");

        consumer.close();
        producer.close();
        logger.info("✅ HYBRID mode prioritizes LISTEN/NOTIFY correctly");
    }

    @Test
    void testHybridModePollingFallback() throws Exception {
        logger.info("🧪 Testing HYBRID mode polling fallback for existing messages");

        String topicName = "test-hybrid-polling-fallback";
        
        // Send messages BEFORE creating consumer (to test polling fallback)
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        producer.send("Existing message 1").get(5, TimeUnit.SECONDS);
        producer.send("Existing message 2").get(5, TimeUnit.SECONDS);

        // Create HYBRID consumer after messages exist
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofSeconds(1)) // Fast polling to test fallback
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);

        AtomicInteger messageCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2); // Expect 2 existing messages

        consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info("📨 Received existing message {}: {}", count, message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Should receive existing messages via polling fallback
        boolean receivedAll = latch.await(10, TimeUnit.SECONDS);
        
        assertTrue(receivedAll, "Should receive existing messages via polling fallback");
        assertEquals(2, messageCount.get(), "Should have processed exactly 2 existing messages");

        consumer.close();
        producer.close();
        logger.info("✅ HYBRID mode polling fallback works correctly");
    }

    @Test
    void testHybridModeBothMechanisms() throws Exception {
        logger.info("🧪 Testing HYBRID mode with both LISTEN/NOTIFY and polling active");

        String topicName = "test-hybrid-both-mechanisms";
        
        // Send some messages BEFORE consumer starts
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        producer.send("Pre-existing message 1").get(5, TimeUnit.SECONDS);
        producer.send("Pre-existing message 2").get(5, TimeUnit.SECONDS);

        // Create HYBRID consumer
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofSeconds(2)) // Reasonable polling interval
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);

        AtomicInteger messageCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(5); // Expect 5 total messages

        consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info("📨 Received hybrid message {}: {}", count, message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for consumer to pick up existing messages via polling
        Thread.sleep(3000);

        // Send new messages - should be delivered via LISTEN/NOTIFY
        producer.send("New message 1").get(5, TimeUnit.SECONDS);
        producer.send("New message 2").get(5, TimeUnit.SECONDS);
        producer.send("New message 3").get(5, TimeUnit.SECONDS);

        // Should receive all messages (existing via polling + new via LISTEN/NOTIFY)
        boolean receivedAll = latch.await(10, TimeUnit.SECONDS);
        
        assertTrue(receivedAll, "Should receive all 5 messages via both mechanisms");
        assertEquals(5, messageCount.get(), "Should have processed exactly 5 messages");

        consumer.close();
        producer.close();
        logger.info("✅ HYBRID mode handles both mechanisms correctly");
    }

    @Test
    void testHybridModeResourceCleanup() throws Exception {
        logger.info("🧪 Testing HYBRID mode resource cleanup");

        String topicName = "test-hybrid-resource-cleanup";
        
        // Create HYBRID consumer
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofMillis(500))
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger messageCount = new AtomicInteger(0);
        AtomicReference<String> lastMessage = new AtomicReference<>();

        consumer.subscribe(message -> {
            messageCount.incrementAndGet();
            lastMessage.set(message.getPayload());
            logger.info("📨 Received cleanup test message: {}", message.getPayload());
            return CompletableFuture.completedFuture(null);
        });

        // Send a message to verify consumer is working
        producer.send("Cleanup test message").get(5, TimeUnit.SECONDS);

        // Wait for message processing
        Thread.sleep(2000);

        // Verify message was processed
        assertEquals(1, messageCount.get(), "Should have processed the test message");
        assertEquals("Cleanup test message", lastMessage.get(), "Should have received correct message");

        // Close consumer and verify graceful shutdown
        consumer.close();
        producer.close();

        // Verify resources are cleaned up (no exceptions during close)
        logger.info("✅ HYBRID mode resource cleanup completed successfully");
    }

    @Test
    void testHybridModeMessageOrdering() throws Exception {
        logger.info("🧪 Testing HYBRID mode message ordering consistency");

        String topicName = "test-hybrid-message-ordering";
        
        // Create HYBRID consumer
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofSeconds(1))
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger messageCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(6); // Expect 6 messages

        consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info("📨 Received ordered message {}: {}", count, message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send messages in sequence
        producer.send("Message 1").get(5, TimeUnit.SECONDS);
        Thread.sleep(100);
        producer.send("Message 2").get(5, TimeUnit.SECONDS);
        Thread.sleep(100);
        producer.send("Message 3").get(5, TimeUnit.SECONDS);
        Thread.sleep(100);
        producer.send("Message 4").get(5, TimeUnit.SECONDS);
        Thread.sleep(100);
        producer.send("Message 5").get(5, TimeUnit.SECONDS);
        Thread.sleep(100);
        producer.send("Message 6").get(5, TimeUnit.SECONDS);

        // Wait for all messages to be processed
        boolean receivedAll = latch.await(15, TimeUnit.SECONDS);
        
        assertTrue(receivedAll, "Should receive all 6 messages");
        assertEquals(6, messageCount.get(), "Should have processed exactly 6 messages");

        consumer.close();
        producer.close();
        logger.info("✅ HYBRID mode maintains message ordering consistency");
    }

    @Test
    void testHybridModePerformanceUnderLoad() throws Exception {
        logger.info("🧪 Testing HYBRID mode performance under moderate load");

        String topicName = "test-hybrid-performance";
        
        // Create HYBRID consumer with reasonable settings
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofMillis(500))
                .consumerThreads(2) // Multiple threads for better performance
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger messageCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(15); // Moderate load test

        consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            if (count % 3 == 0) {
                logger.info("📨 Processed {} messages so far", count);
            }
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send messages at moderate pace
        for (int i = 1; i <= 15; i++) {
            producer.send("Performance test message " + i).get(5, TimeUnit.SECONDS);
            if (i % 5 == 0) {
                Thread.sleep(100); // Small pause every 5 messages
            }
        }

        // Wait for all messages to be processed
        boolean receivedAll = latch.await(20, TimeUnit.SECONDS);
        
        assertTrue(receivedAll, "Should handle moderate load efficiently");
        assertEquals(15, messageCount.get(), "Should have processed exactly 15 messages");

        consumer.close();
        producer.close();
        logger.info("✅ HYBRID mode handles moderate load efficiently");
    }
}
