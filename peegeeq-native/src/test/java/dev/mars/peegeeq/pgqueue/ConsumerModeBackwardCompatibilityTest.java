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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Backward compatibility tests for consumer mode implementation.
 * Tests that existing API without ConsumerConfig continues to work and defaults to HYBRID mode.
 * Ensures that legacy code using the old API is not broken by the new consumer mode features.
 * 
 * Following established coding principles:
 * - Use real infrastructure (TestContainers) rather than mocks
 * - Test backward compatibility scenarios that existing users depend on
 * - Validate that old API defaults to expected behavior (HYBRID mode)
 * - Follow existing patterns from other integration tests
 * - Test seamless migration path from old to new API
 */
@Testcontainers
class ConsumerModeBackwardCompatibilityTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerModeBackwardCompatibilityTest.class);

    @Container
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

        logger.info("Test setup completed for consumer mode backward compatibility testing");
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
    void testLegacyApiWithoutConsumerConfig() throws Exception {
        logger.info("🧪 Testing legacy API without ConsumerConfig (should default to HYBRID)");

        String topicName = "test-legacy-api";
        
        // Use old API without ConsumerConfig - should default to HYBRID mode
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(3);

            consumer.subscribe(message -> {
                processedCount.incrementAndGet();
                logger.info("📨 Legacy API processed message: {}", message.getPayload());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup
            Thread.sleep(1000);

            // Send messages using legacy API
            producer.send("Legacy message 1").get(5, TimeUnit.SECONDS);
            producer.send("Legacy message 2").get(5, TimeUnit.SECONDS);
            producer.send("Legacy message 3").get(5, TimeUnit.SECONDS);

            // Wait for message processing
            boolean received = latch.await(15, TimeUnit.SECONDS);
            assertTrue(received, "Legacy API should process messages successfully");
            assertEquals(3, processedCount.get(), "Should process exactly 3 messages with legacy API");

            logger.info("✅ Legacy API compatibility verified - processed: {} messages", processedCount.get());

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("✅ Legacy API without ConsumerConfig test completed successfully");
    }

    @Test
    void testMixedApiUsage() throws Exception {
        logger.info("🧪 Testing mixed API usage (legacy and new API together)");

        String legacyTopic = "test-mixed-legacy";
        String newTopic = "test-mixed-new";
        
        // Create one consumer with legacy API (no ConsumerConfig)
        MessageConsumer<String> legacyConsumer = factory.createConsumer(legacyTopic, String.class);
        
        // Create another consumer with new API (with ConsumerConfig)
        MessageConsumer<String> newConsumer = factory.createConsumer(newTopic, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.LISTEN_NOTIFY_ONLY).build());
        
        MessageProducer<String> legacyProducer = factory.createProducer(legacyTopic, String.class);
        MessageProducer<String> newProducer = factory.createProducer(newTopic, String.class);

        try {
            AtomicInteger legacyCount = new AtomicInteger(0);
            AtomicInteger newCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(4); // 2 messages each

            legacyConsumer.subscribe(message -> {
                legacyCount.incrementAndGet();
                logger.info("📨 Legacy consumer processed: {}", message.getPayload());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            newConsumer.subscribe(message -> {
                newCount.incrementAndGet();
                logger.info("📨 New consumer processed: {}", message.getPayload());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup
            Thread.sleep(1000);

            // Send messages to both topics
            legacyProducer.send("Mixed legacy message 1").get(5, TimeUnit.SECONDS);
            newProducer.send("Mixed new message 1").get(5, TimeUnit.SECONDS);
            legacyProducer.send("Mixed legacy message 2").get(5, TimeUnit.SECONDS);
            newProducer.send("Mixed new message 2").get(5, TimeUnit.SECONDS);

            // Wait for message processing
            boolean received = latch.await(15, TimeUnit.SECONDS);
            assertTrue(received, "Mixed API usage should process all messages");
            assertEquals(2, legacyCount.get(), "Legacy consumer should process 2 messages");
            assertEquals(2, newCount.get(), "New consumer should process 2 messages");

            logger.info("✅ Mixed API usage verified - legacy: {}, new: {}", legacyCount.get(), newCount.get());

        } finally {
            legacyConsumer.close();
            newConsumer.close();
            legacyProducer.close();
            newProducer.close();
        }

        logger.info("✅ Mixed API usage test completed successfully");
    }

    @Test
    void testLegacyApiDefaultBehavior() throws Exception {
        logger.info("🧪 Testing legacy API default behavior matches HYBRID mode");

        String legacyTopic = "test-legacy-default";
        String hybridTopic = "test-hybrid-explicit";
        
        // Create consumer with legacy API (should default to HYBRID)
        MessageConsumer<String> legacyConsumer = factory.createConsumer(legacyTopic, String.class);
        
        // Create consumer with explicit HYBRID mode
        MessageConsumer<String> hybridConsumer = factory.createConsumer(hybridTopic, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).build());
        
        MessageProducer<String> legacyProducer = factory.createProducer(legacyTopic, String.class);
        MessageProducer<String> hybridProducer = factory.createProducer(hybridTopic, String.class);

        try {
            AtomicInteger legacyCount = new AtomicInteger(0);
            AtomicInteger hybridCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(4); // 2 messages each

            legacyConsumer.subscribe(message -> {
                legacyCount.incrementAndGet();
                logger.info("📨 Legacy default processed: {}", message.getPayload());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            hybridConsumer.subscribe(message -> {
                hybridCount.incrementAndGet();
                logger.info("📨 Explicit HYBRID processed: {}", message.getPayload());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup
            Thread.sleep(1000);

            // Send messages to both topics
            legacyProducer.send("Legacy default message 1").get(5, TimeUnit.SECONDS);
            hybridProducer.send("Explicit hybrid message 1").get(5, TimeUnit.SECONDS);
            legacyProducer.send("Legacy default message 2").get(5, TimeUnit.SECONDS);
            hybridProducer.send("Explicit hybrid message 2").get(5, TimeUnit.SECONDS);

            // Wait for message processing
            boolean received = latch.await(15, TimeUnit.SECONDS);
            assertTrue(received, "Both legacy and explicit HYBRID should process messages");
            assertEquals(2, legacyCount.get(), "Legacy default should process 2 messages");
            assertEquals(2, hybridCount.get(), "Explicit HYBRID should process 2 messages");

            logger.info("✅ Legacy API default behavior verified - legacy: {}, hybrid: {}", 
                legacyCount.get(), hybridCount.get());

        } finally {
            legacyConsumer.close();
            hybridConsumer.close();
            legacyProducer.close();
            hybridProducer.close();
        }

        logger.info("✅ Legacy API default behavior test completed successfully");
    }

    @Test
    void testGradualMigrationPath() throws Exception {
        logger.info("🧪 Testing gradual migration path from legacy to new API");

        String topicName = "test-gradual-migration";
        
        // Start with legacy API
        MessageConsumer<String> legacyConsumer = factory.createConsumer(topicName, String.class);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger totalProcessed = new AtomicInteger(0);

        try {
            CountDownLatch legacyLatch = new CountDownLatch(2);

            legacyConsumer.subscribe(message -> {
                totalProcessed.incrementAndGet();
                logger.info("📨 Legacy migration processed: {}", message.getPayload());
                legacyLatch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup
            Thread.sleep(500);

            // Send messages with legacy consumer
            producer.send("Migration message 1").get(5, TimeUnit.SECONDS);
            producer.send("Migration message 2").get(5, TimeUnit.SECONDS);

            // Wait for processing
            boolean legacyReceived = legacyLatch.await(10, TimeUnit.SECONDS);
            assertTrue(legacyReceived, "Legacy consumer should process initial messages");

            // Close legacy consumer
            legacyConsumer.close();

            // Migrate to new API with explicit configuration
            MessageConsumer<String> newConsumer = factory.createConsumer(topicName, String.class,
                ConsumerConfig.builder().mode(ConsumerMode.HYBRID).build());

            CountDownLatch newLatch = new CountDownLatch(2);

            newConsumer.subscribe(message -> {
                totalProcessed.incrementAndGet();
                logger.info("📨 New API migration processed: {}", message.getPayload());
                newLatch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for new consumer setup
            Thread.sleep(500);

            // Send more messages with new consumer
            producer.send("Migration message 3").get(5, TimeUnit.SECONDS);
            producer.send("Migration message 4").get(5, TimeUnit.SECONDS);

            // Wait for processing
            boolean newReceived = newLatch.await(10, TimeUnit.SECONDS);
            assertTrue(newReceived, "New consumer should process migrated messages");

            assertEquals(4, totalProcessed.get(), "Should process all 4 messages during migration");

            logger.info("✅ Gradual migration verified - total processed: {}", totalProcessed.get());

            newConsumer.close();

        } finally {
            producer.close();
        }

        logger.info("✅ Gradual migration path test completed successfully");
    }

    @Test
    void testLegacyApiPerformanceConsistency() throws Exception {
        logger.info("🧪 Testing legacy API performance consistency");

        String topicName = "test-legacy-performance";
        
        // Use legacy API for performance test
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(5); // Process 5 messages

            long startTime = System.currentTimeMillis();

            consumer.subscribe(message -> {
                processedCount.incrementAndGet();
                logger.debug("📨 Performance test processed: {}", message.getPayload());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup
            Thread.sleep(500);

            // Send messages for performance test
            for (int i = 1; i <= 5; i++) {
                producer.send("Performance message " + i).get(5, TimeUnit.SECONDS);
            }

            // Wait for message processing
            boolean received = latch.await(15, TimeUnit.SECONDS);
            assertTrue(received, "Legacy API should handle performance test messages");

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            assertEquals(5, processedCount.get(), "Should process exactly 5 messages");
            assertTrue(duration < 10000, "Processing should complete within reasonable time (10s)");

            logger.info("✅ Legacy API performance verified - processed: {} messages in {}ms", 
                processedCount.get(), duration);

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("✅ Legacy API performance consistency test completed successfully");
    }
}
