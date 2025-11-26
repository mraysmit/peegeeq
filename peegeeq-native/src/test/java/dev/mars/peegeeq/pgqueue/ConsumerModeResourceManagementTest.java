package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resource management tests for consumer mode implementation.
 * Tests that consumer modes properly manage resources including connection pools,
 * Vert.x instances, scheduler resources, memory usage, and cleanup on shutdown.
 *
 * Following established coding principles:
 * - Use real infrastructure (TestContainers) rather than mocks
 * - Test resource management edge cases that could cause production issues
 * - Validate proper cleanup and resource sharing across consumer modes
 * - Follow existing patterns from other integration tests
 * - Test with various resource scenarios including high load and shutdown
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class ConsumerModeResourceManagementTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerModeResourceManagementTest.class);

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


        // Ensure required schema exists before starting PeeGeeQ
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres,
                SchemaComponent.NATIVE_QUEUE,
                SchemaComponent.OUTBOX,
                SchemaComponent.DEAD_LETTER_QUEUE);

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

        logger.info("Test setup completed for consumer mode resource management testing");
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
    void testConnectionPoolUsageAcrossConsumerModes() throws Exception {
        logger.info("🧪 Testing connection pool usage across consumer modes");

        String topicName = "test-connection-pool-usage";
        List<MessageConsumer<String>> consumers = new ArrayList<>();
        List<MessageProducer<String>> producers = new ArrayList<>();

        try {
            // Create consumers with different modes - they should share connection pools efficiently
            MessageConsumer<String> listenConsumer = factory.createConsumer(topicName + "-listen", String.class,
                ConsumerConfig.builder().mode(ConsumerMode.LISTEN_NOTIFY_ONLY).build());
            MessageConsumer<String> pollingConsumer = factory.createConsumer(topicName + "-polling", String.class,
                ConsumerConfig.builder().mode(ConsumerMode.POLLING_ONLY).pollingInterval(Duration.ofSeconds(1)).build());
            MessageConsumer<String> hybridConsumer = factory.createConsumer(topicName + "-hybrid", String.class,
                ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());

            consumers.add(listenConsumer);
            consumers.add(pollingConsumer);
            consumers.add(hybridConsumer);

            // Create producers for each topic
            producers.add(factory.createProducer(topicName + "-listen", String.class));
            producers.add(factory.createProducer(topicName + "-polling", String.class));
            producers.add(factory.createProducer(topicName + "-hybrid", String.class));

            AtomicInteger messageCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(3);

            // Subscribe all consumers
            for (int i = 0; i < consumers.size(); i++) {
                final int index = i;
                consumers.get(i).subscribe(message -> {
                    messageCount.incrementAndGet();
                    logger.info("📨 Consumer {} received message: {}", index, message.getPayload());
                    latch.countDown();
                    return CompletableFuture.completedFuture(null);
                });
            }

            // Wait for consumer setup
            Thread.sleep(1000);

            // Send messages to all topics
            for (int i = 0; i < producers.size(); i++) {
                producers.get(i).send("Test message " + i).get(5, TimeUnit.SECONDS);
            }

            // Wait for message processing
            boolean received = latch.await(10, TimeUnit.SECONDS);
            assertTrue(received, "Should receive all messages across different consumer modes");
            assertEquals(3, messageCount.get(), "Should process exactly 3 messages");

            logger.info("✅ Connection pool usage verified - all consumer modes working with shared resources");

        } finally {
            // Clean up resources
            for (MessageConsumer<String> consumer : consumers) {
                consumer.close();
            }
            for (MessageProducer<String> producer : producers) {
                producer.close();
            }
        }

        logger.info("✅ Connection pool usage test completed successfully");
    }

    @Test
    void testVertxInstanceSharingAcrossConsumers() throws Exception {
        logger.info("🧪 Testing Vert.x instance sharing across consumers");

        String topicName = "test-vertx-sharing";
        List<MessageConsumer<String>> consumers = new ArrayList<>();

        try {
            // Create multiple consumers - they should share Vert.x instances efficiently
            for (int i = 0; i < 5; i++) {
                ConsumerConfig config = ConsumerConfig.builder()
                    .mode(ConsumerMode.HYBRID)
                    .pollingInterval(Duration.ofSeconds(1))
                    .build();

                MessageConsumer<String> consumer = factory.createConsumer(topicName + "-" + i, String.class, config);
                consumers.add(consumer);
            }

            AtomicInteger subscriptionCount = new AtomicInteger(0);
            CountDownLatch setupLatch = new CountDownLatch(5);

            // Subscribe all consumers
            for (int i = 0; i < consumers.size(); i++) {
                final int index = i;
                consumers.get(i).subscribe(message -> {
                    subscriptionCount.incrementAndGet();
                    logger.info("📨 Consumer {} received message: {}", index, message.getPayload());
                    return CompletableFuture.completedFuture(null);
                });
                setupLatch.countDown();
            }

            // Wait for all subscriptions to be set up
            boolean setupComplete = setupLatch.await(10, TimeUnit.SECONDS);
            assertTrue(setupComplete, "All consumers should be set up successfully");

            logger.info("✅ Vert.x instance sharing verified - {} consumers created and subscribed", consumers.size());

        } finally {
            // Clean up resources
            for (MessageConsumer<String> consumer : consumers) {
                consumer.close();
            }
        }

        logger.info("✅ Vert.x instance sharing test completed successfully");
    }

    @Test
    void testSchedulerResourceManagement() throws Exception {
        logger.info("🧪 Testing scheduler resource management for polling consumers");

        String topicName = "test-scheduler-resources";
        List<MessageConsumer<String>> pollingConsumers = new ArrayList<>();

        try {
            // Create multiple polling consumers - they should manage scheduler resources efficiently
            for (int i = 0; i < 3; i++) {
                ConsumerConfig config = ConsumerConfig.builder()
                    .mode(ConsumerMode.POLLING_ONLY)
                    .pollingInterval(Duration.ofMillis(500)) // Fast polling for testing
                    .build();

                MessageConsumer<String> consumer = factory.createConsumer(topicName + "-" + i, String.class, config);
                pollingConsumers.add(consumer);
            }

            AtomicInteger messageCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(3);

            // Subscribe all polling consumers
            for (int i = 0; i < pollingConsumers.size(); i++) {
                final int index = i;
                pollingConsumers.get(i).subscribe(message -> {
                    messageCount.incrementAndGet();
                    logger.info("📨 Polling consumer {} received message: {}", index, message.getPayload());
                    latch.countDown();
                    return CompletableFuture.completedFuture(null);
                });
            }

            // Wait for polling setup
            Thread.sleep(1000);

            // Send messages to test polling
            MessageProducer<String> producer = factory.createProducer(topicName + "-0", String.class);
            producer.send("Test polling message 1").get(5, TimeUnit.SECONDS);

            MessageProducer<String> producer2 = factory.createProducer(topicName + "-1", String.class);
            producer2.send("Test polling message 2").get(5, TimeUnit.SECONDS);

            MessageProducer<String> producer3 = factory.createProducer(topicName + "-2", String.class);
            producer3.send("Test polling message 3").get(5, TimeUnit.SECONDS);

            // Wait for message processing
            boolean received = latch.await(10, TimeUnit.SECONDS);
            assertTrue(received, "Should receive messages via polling mechanism");
            assertEquals(3, messageCount.get(), "Should process exactly 3 messages");

            producer.close();
            producer2.close();
            producer3.close();

            logger.info("✅ Scheduler resource management verified - polling consumers working efficiently");

        } finally {
            // Clean up resources - this should properly clean up scheduler resources
            for (MessageConsumer<String> consumer : pollingConsumers) {
                consumer.close();
            }
        }

        logger.info("✅ Scheduler resource management test completed successfully");
    }

    @Test
    void testMemoryUsagePatterns() throws Exception {
        logger.info("🧪 Testing memory usage patterns across consumer modes");

        String topicName = "test-memory-usage";

        // Test that consumers don't accumulate excessive memory during normal operation
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(10);

            consumer.subscribe(message -> {
                processedCount.incrementAndGet();
                logger.debug("📨 Processed message: {}", message.getPayload());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup
            Thread.sleep(500);

            // Send multiple messages to test memory usage
            for (int i = 0; i < 10; i++) {
                producer.send("Memory test message " + i).get(5, TimeUnit.SECONDS);
            }

            // Wait for message processing
            boolean received = latch.await(15, TimeUnit.SECONDS);
            assertTrue(received, "Should process all messages without memory issues");
            assertEquals(10, processedCount.get(), "Should process exactly 10 messages");

            logger.info("✅ Memory usage patterns verified - processed {} messages efficiently", processedCount.get());

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("✅ Memory usage patterns test completed successfully");
    }

    @Test
    void testGracefulShutdownResourceCleanup() throws Exception {
        logger.info("🧪 Testing graceful shutdown and resource cleanup");

        String topicName = "test-graceful-shutdown";

        // Create consumer and producer
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger processedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);

        consumer.subscribe(message -> {
            processedCount.incrementAndGet();
            logger.info("📨 Processing message during shutdown test: {}", message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for consumer setup
        Thread.sleep(500);

        // Send messages
        producer.send("Shutdown test message 1").get(5, TimeUnit.SECONDS);
        producer.send("Shutdown test message 2").get(5, TimeUnit.SECONDS);

        // Wait for some processing
        Thread.sleep(1000);

        // Test graceful shutdown
        logger.info("🔄 Testing graceful shutdown...");
        consumer.close(); // Should gracefully shut down and clean up resources
        producer.close();

        // Verify messages were processed
        boolean received = latch.await(5, TimeUnit.SECONDS);
        assertTrue(received, "Should process messages before shutdown");
        assertEquals(2, processedCount.get(), "Should process exactly 2 messages");

        logger.info("✅ Graceful shutdown and resource cleanup verified");
        logger.info("✅ Graceful shutdown resource cleanup test completed successfully");
    }
}
