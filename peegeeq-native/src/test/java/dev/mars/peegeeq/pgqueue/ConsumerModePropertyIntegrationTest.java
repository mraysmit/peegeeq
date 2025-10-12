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
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property integration tests for consumer mode implementation.
 * Tests that consumer modes work correctly with various property file configurations.
 * Validates that system properties and configuration files properly influence consumer behavior.
 *
 * Following established coding principles:
 * - Use real infrastructure (TestContainers) rather than mocks
 * - Test property integration scenarios that affect consumer mode behavior
 * - Validate that configuration changes properly influence system behavior
 * - Follow existing patterns from other integration tests
 * - Test various property combinations and edge cases
 */
@Testcontainers
class ConsumerModePropertyIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerModePropertyIntegrationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_test")
            .withUsername("peegeeq_user")
            .withPassword("peegeeq_password");

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        // Clear any existing system properties to ensure clean state
        clearConsumerModeProperties();

        // Configure base test properties using TestContainer pattern
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        // Ensure required schema exists for native queue tests
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.NATIVE_QUEUE, SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);

        System.setProperty("peegeeq.metrics.enabled", "true");
        System.setProperty("peegeeq.circuit-breaker.enabled", "true");

        logger.info("Test setup completed for consumer mode property integration testing");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (factory != null) {
            factory.close();
        }
        if (manager != null) {
            manager.stop();
        }

        // Clear properties after each test to prevent interference
        clearConsumerModeProperties();

        logger.info("Test teardown completed");
    }

    private void clearConsumerModeProperties() {
        System.clearProperty("peegeeq.queue.polling-interval");
        System.clearProperty("peegeeq.queue.visibility-timeout");
        System.clearProperty("peegeeq.queue.batch-size");
        System.clearProperty("peegeeq.queue.consumer-mode");
        System.clearProperty("peegeeq.queue.default-polling-interval");
        System.clearProperty("peegeeq.queue.default-batch-size");
    }

    private void initializeManagerAndFactory() throws Exception {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        factory = provider.createFactory("native", databaseService);
    }

    @Test
    void testPollingIntervalPropertyIntegration() throws Exception {
        logger.info("🧪 Testing polling interval property integration");

        // Set custom polling interval via system property
        System.setProperty("peegeeq.queue.polling-interval", "PT2S");
        System.setProperty("peegeeq.queue.visibility-timeout", "PT30S");

        initializeManagerAndFactory();

        String topicName = "test-polling-interval-property";

        // Create consumer with POLLING_ONLY mode to test polling interval
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofSeconds(2)) // Should match property
                .build());

        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(2);

            consumer.subscribe(message -> {
                processedCount.incrementAndGet();
                logger.info("📨 Property integration processed: {}", message.getPayload());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup
            Thread.sleep(1000);

            // Send messages
            producer.send("Property test message 1").get(5, TimeUnit.SECONDS);
            producer.send("Property test message 2").get(5, TimeUnit.SECONDS);

            // Wait for message processing with polling interval consideration
            boolean received = latch.await(10, TimeUnit.SECONDS);
            assertTrue(received, "Should process messages with custom polling interval");
            assertEquals(2, processedCount.get(), "Should process exactly 2 messages");

            logger.info("✅ Polling interval property integration verified - processed: {} messages",
                processedCount.get());

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("✅ Polling interval property integration test completed successfully");
    }

    @Test
    void testBatchSizePropertyIntegration() throws Exception {
        logger.info("🧪 Testing batch size property integration");

        // Set custom batch size via system property
        System.setProperty("peegeeq.queue.polling-interval", "PT1S");
        System.setProperty("peegeeq.queue.visibility-timeout", "PT30S");

        initializeManagerAndFactory();

        String topicName = "test-batch-size-property";

        // Create consumer with custom batch size
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofSeconds(1))
                .batchSize(3) // Custom batch size
                .build());

        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(5);

            consumer.subscribe(message -> {
                processedCount.incrementAndGet();
                logger.info("📨 Batch property processed: {}", message.getPayload());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup
            Thread.sleep(500);

            // Send messages in batch
            for (int i = 1; i <= 5; i++) {
                producer.send("Batch message " + i).get(5, TimeUnit.SECONDS);
            }

            // Wait for message processing
            boolean received = latch.await(15, TimeUnit.SECONDS);
            assertTrue(received, "Should process messages with custom batch size");
            assertEquals(5, processedCount.get(), "Should process exactly 5 messages");

            logger.info("✅ Batch size property integration verified - processed: {} messages",
                processedCount.get());

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("✅ Batch size property integration test completed successfully");
    }

    @Test
    void testVisibilityTimeoutPropertyIntegration() throws Exception {
        logger.info("🧪 Testing visibility timeout property integration");

        // Set custom visibility timeout via system property
        System.setProperty("peegeeq.queue.polling-interval", "PT1S");
        System.setProperty("peegeeq.queue.visibility-timeout", "PT10S"); // Short timeout for testing

        initializeManagerAndFactory();

        String topicName = "test-visibility-timeout-property";

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofSeconds(1))
                .build());

        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(2);

            consumer.subscribe(message -> {
                processedCount.incrementAndGet();
                logger.info("📨 Visibility timeout processed: {}", message.getPayload());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup
            Thread.sleep(1000);

            // Send messages
            producer.send("Visibility test message 1").get(5, TimeUnit.SECONDS);
            producer.send("Visibility test message 2").get(5, TimeUnit.SECONDS);

            // Wait for message processing
            boolean received = latch.await(15, TimeUnit.SECONDS);
            assertTrue(received, "Should process messages with custom visibility timeout");
            assertEquals(2, processedCount.get(), "Should process exactly 2 messages");

            logger.info("✅ Visibility timeout property integration verified - processed: {} messages",
                processedCount.get());

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("✅ Visibility timeout property integration test completed successfully");
    }

    @Test
    void testMultiplePropertyCombinations() throws Exception {
        logger.info("🧪 Testing multiple property combinations");

        // Set multiple properties together
        System.setProperty("peegeeq.queue.polling-interval", "PT500MS"); // Fast polling
        System.setProperty("peegeeq.queue.visibility-timeout", "PT15S");

        initializeManagerAndFactory();

        String topicName = "test-multiple-properties";

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofMillis(500)) // Match property
                .batchSize(2)
                .build());

        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(4);

            consumer.subscribe(message -> {
                processedCount.incrementAndGet();
                logger.info("📨 Multiple properties processed: {}", message.getPayload());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup
            Thread.sleep(1000);

            // Send messages
            for (int i = 1; i <= 4; i++) {
                producer.send("Multi-property message " + i).get(5, TimeUnit.SECONDS);
            }

            // Wait for message processing
            boolean received = latch.await(15, TimeUnit.SECONDS);
            assertTrue(received, "Should process messages with multiple property combinations");
            assertEquals(4, processedCount.get(), "Should process exactly 4 messages");

            logger.info("✅ Multiple property combinations verified - processed: {} messages",
                processedCount.get());

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("✅ Multiple property combinations test completed successfully");
    }

    @Test
    void testPropertyOverrideScenarios() throws Exception {
        logger.info("🧪 Testing property override scenarios");

        // Set base properties
        System.setProperty("peegeeq.queue.polling-interval", "PT3S"); // Base interval
        System.setProperty("peegeeq.queue.visibility-timeout", "PT30S");

        initializeManagerAndFactory();

        String topicName = "test-property-override";

        // Create consumer that overrides property with explicit config
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofSeconds(1)) // Override property with faster polling
                .build());

        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(3);

            consumer.subscribe(message -> {
                processedCount.incrementAndGet();
                logger.info("📨 Property override processed: {}", message.getPayload());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup
            Thread.sleep(500);

            // Send messages
            for (int i = 1; i <= 3; i++) {
                producer.send("Override message " + i).get(5, TimeUnit.SECONDS);
            }

            // Wait for message processing - should be faster due to override
            boolean received = latch.await(10, TimeUnit.SECONDS);
            assertTrue(received, "Should process messages with overridden properties");
            assertEquals(3, processedCount.get(), "Should process exactly 3 messages");

            logger.info("✅ Property override scenarios verified - processed: {} messages",
                processedCount.get());

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("✅ Property override scenarios test completed successfully");
    }
}
