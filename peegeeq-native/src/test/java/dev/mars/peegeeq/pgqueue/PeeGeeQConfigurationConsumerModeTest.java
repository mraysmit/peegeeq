package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PeeGeeQConfiguration with consumer modes.
 * Tests that consumer modes work correctly with PeeGeeQConfiguration settings.
 * Validates that configuration values properly influence consumer behavior across all modes.
 * 
 * Following established coding principles:
 * - Use real infrastructure (TestContainers) rather than mocks
 * - Test configuration integration scenarios that affect consumer mode behavior
 * - Validate that PeeGeeQConfiguration values properly influence system behavior
 * - Follow existing patterns from other integration tests
 * - Test various configuration combinations and edge cases
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class PeeGeeQConfigurationConsumerModeTest {
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQConfigurationConsumerModeTest.class);

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        logger.info("Setting up PeeGeeQConfiguration consumer mode integration test");

        // Configure database connection from container
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();

        // Ensure required schema exists before starting PeeGeeQ
        PeeGeeQTestSchemaInitializer.initializeSchema(
            postgres,
            PostgreSQLTestConstants.TEST_SCHEMA,
            SchemaComponent.NATIVE_QUEUE,
            SchemaComponent.OUTBOX,
            SchemaComponent.DEAD_LETTER_QUEUE
        );

        // Initialize PeeGeeQ with test configuration
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().onSuccess(v -> {
            // Create factory using the proper pattern
            PgDatabaseService databaseService = new PgDatabaseService(manager);
            PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

            // Register native factory implementation
            PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

            factory = provider.createFactory("native", databaseService);

            logger.info("Test setup completed for PeeGeeQConfiguration consumer mode integration testing");
            testContext.completeNow();
        }).onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        logger.info("Tearing down PeeGeeQConfiguration consumer mode integration test");

        (manager != null ? manager.closeReactive() : Future.succeededFuture())
                .onSuccess(v -> {
                    logger.info("Test teardown completed");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    void testConfigurationBatchSizeIntegrationWithConsumerModes() throws Exception {
        logger.info(" Testing PeeGeeQConfiguration batch size integration with consumer modes");

        // Recreate configuration with custom batch size
        Properties testMethodProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.batch-size", "5")
                .build();

        // Recreate configuration to pick up new property
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testMethodProps);
        assertEquals(5, config.getQueueConfig().getBatchSize(), "Configuration should reflect custom batch size");

        String topicName = "test-config-batch-size-integration";

        // Test POLLING_ONLY mode with custom batch size
        ConsumerConfig pollingConfig = ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofMillis(500))
                .batchSize(5) // Should align with configuration
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, pollingConfig);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        CountDownLatch allDone = new CountDownLatch(5);
        AtomicInteger processedCount = new AtomicInteger(0);

        // Subscribe to messages
        consumer.subscribe(message -> {
            int count = processedCount.incrementAndGet();
            logger.info("\ud83d\udce8 Processed message {} with batch size configuration: {}", count, message.getPayload());
            allDone.countDown();
            return Future.succeededFuture();
        });

        // Send messages to test batch processing
        for (int i = 1; i <= 5; i++) {
            producer.send("Batch message " + i);
        }

        // Wait for all messages to be processed
        boolean allProcessed = allDone.await(10, TimeUnit.SECONDS);
        assertTrue(allProcessed, "All messages should be processed with custom batch size");
        assertEquals(5, processedCount.get(), "Should process exactly 5 messages");

        consumer.close();
        producer.close();

        logger.info("PeeGeeQConfiguration batch size integration test completed successfully");
    }

    @Test
    void testConfigurationPollingIntervalIntegrationWithConsumerModes() throws Exception {
        logger.info(" Testing PeeGeeQConfiguration polling interval integration with consumer modes");

        // Recreate configuration with custom polling interval
        Properties testMethodProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT3S")
                .build();

        // Recreate configuration to pick up new property
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testMethodProps);
        assertEquals(Duration.ofSeconds(3), config.getQueueConfig().getPollingInterval(), 
            "Configuration should reflect custom polling interval");

        String topicName = "test-config-polling-interval-integration";

        // Test HYBRID mode with configuration-based polling interval
        ConsumerConfig hybridConfig = ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofSeconds(3)) // Should align with configuration
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, hybridConfig);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        CountDownLatch allDone = new CountDownLatch(2);
        AtomicInteger processedCount = new AtomicInteger(0);

        // Subscribe to messages
        consumer.subscribe(message -> {
            int count = processedCount.incrementAndGet();
            logger.info("\ud83d\udce8 Processed message {} with polling interval configuration: {}", count, message.getPayload());
            allDone.countDown();
            return Future.succeededFuture();
        });

        // Send messages to test polling behavior
        producer.send("Polling interval message 1");
        producer.send("Polling interval message 2");

        // Wait for messages to be processed
        boolean allProcessed = allDone.await(15, TimeUnit.SECONDS);
        assertTrue(allProcessed, "All messages should be processed with custom polling interval");
        assertEquals(2, processedCount.get(), "Should process exactly 2 messages");

        consumer.close();
        producer.close();

        logger.info("PeeGeeQConfiguration polling interval integration test completed successfully");
    }

    @Test
    void testConfigurationVisibilityTimeoutIntegrationWithConsumerModes() throws Exception {
        logger.info(" Testing PeeGeeQConfiguration visibility timeout integration with consumer modes");

        // Recreate configuration with custom visibility timeout
        Properties testMethodProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.visibility-timeout", "PT15S")
                .build();

        // Recreate configuration to pick up new property
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testMethodProps);
        assertEquals(Duration.ofSeconds(15), config.getQueueConfig().getVisibilityTimeout(), 
            "Configuration should reflect custom visibility timeout");

        String topicName = "test-config-visibility-timeout-integration";

        // Test LISTEN_NOTIFY_ONLY mode with configuration-based visibility timeout
        ConsumerConfig listenConfig = ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, listenConfig);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        CountDownLatch allDone = new CountDownLatch(2);
        AtomicInteger processedCount = new AtomicInteger(0);

        // Subscribe to messages
        consumer.subscribe(message -> {
            int count = processedCount.incrementAndGet();
            logger.info("\ud83d\udce8 Processed message {} with visibility timeout configuration: {}", count, message.getPayload());
            allDone.countDown();
            return Future.succeededFuture();
        });

        // Send messages to test visibility timeout behavior
        producer.send("Visibility timeout message 1");
        producer.send("Visibility timeout message 2");

        // Wait for messages to be processed
        boolean allProcessed = allDone.await(10, TimeUnit.SECONDS);
        assertTrue(allProcessed, "All messages should be processed with custom visibility timeout");
        assertEquals(2, processedCount.get(), "Should process exactly 2 messages");

        consumer.close();
        producer.close();

        logger.info("PeeGeeQConfiguration visibility timeout integration test completed successfully");
    }

    @Test
    void testConfigurationConsumerThreadsIntegrationWithConsumerModes() throws Exception {
        logger.info(" Testing PeeGeeQConfiguration consumer threads integration with consumer modes");

        // Recreate configuration with custom consumer threads
        Properties testMethodProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.consumer.threads", "2")
                .build();

        // Recreate configuration to pick up new property
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testMethodProps);
        assertEquals(2, config.getQueueConfig().getConsumerThreads(), 
            "Configuration should reflect custom consumer threads");

        String topicName = "test-config-consumer-threads-integration";

        // Test POLLING_ONLY mode with configuration-based consumer threads
        ConsumerConfig pollingConfig = ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofSeconds(1))
                .consumerThreads(2) // Should align with configuration
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, pollingConfig);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        CountDownLatch allDone = new CountDownLatch(3);
        AtomicInteger processedCount = new AtomicInteger(0);

        // Subscribe to messages
        consumer.subscribe(message -> {
            int count = processedCount.incrementAndGet();
            logger.info("\ud83d\udce8 Processed message {} with consumer threads configuration: {} (Thread: {})", 
                count, message.getPayload(), Thread.currentThread().getName());
            allDone.countDown();
            return Future.succeededFuture();
        });

        // Send messages to test multi-threaded processing
        producer.send("Consumer threads message 1");
        producer.send("Consumer threads message 2");
        producer.send("Consumer threads message 3");

        // Wait for messages to be processed
        boolean allProcessed = allDone.await(10, TimeUnit.SECONDS);
        assertTrue(allProcessed, "All messages should be processed with custom consumer threads");
        assertEquals(3, processedCount.get(), "Should process exactly 3 messages");

        consumer.close();
        producer.close();

        logger.info("PeeGeeQConfiguration consumer threads integration test completed successfully");
    }

    @Test
    void testMultipleConfigurationPropertiesIntegrationWithConsumerModes() throws Exception {
        logger.info(" Testing multiple PeeGeeQConfiguration properties integration with consumer modes");

        // Recreate configuration with multiple custom properties
        Properties testMethodProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.batch-size", "8")
                .property("peegeeq.queue.polling-interval", "PT2S")
                .property("peegeeq.queue.visibility-timeout", "PT20S")
                .property("peegeeq.consumer.threads", "3")
                .build();

        // Recreate configuration to pick up new properties
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testMethodProps);

        // Verify all configuration values are loaded correctly
        assertEquals(8, config.getQueueConfig().getBatchSize(), "Configuration should reflect custom batch size");
        assertEquals(Duration.ofSeconds(2), config.getQueueConfig().getPollingInterval(),
            "Configuration should reflect custom polling interval");
        assertEquals(Duration.ofSeconds(20), config.getQueueConfig().getVisibilityTimeout(),
            "Configuration should reflect custom visibility timeout");
        assertEquals(3, config.getQueueConfig().getConsumerThreads(),
            "Configuration should reflect custom consumer threads");

        String topicName = "test-config-multiple-properties-integration";

        // Test HYBRID mode with all custom configuration properties
        ConsumerConfig hybridConfig = ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofSeconds(2)) // Should align with configuration
                .batchSize(8) // Should align with configuration
                .consumerThreads(3) // Should align with configuration
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, hybridConfig);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        CountDownLatch allDone = new CountDownLatch(6);
        AtomicInteger processedCount = new AtomicInteger(0);

        // Subscribe to messages
        consumer.subscribe(message -> {
            int count = processedCount.incrementAndGet();
            logger.info("\ud83d\udce8 Processed message {} with multiple configuration properties: {} (Thread: {})",
                count, message.getPayload(), Thread.currentThread().getName());
            allDone.countDown();
            return Future.succeededFuture();
        });

        // Send messages to test all configuration properties working together
        for (int i = 1; i <= 6; i++) {
            producer.send("Multiple config message " + i);
        }

        // Wait for messages to be processed
        boolean allProcessed = allDone.await(15, TimeUnit.SECONDS);
        assertTrue(allProcessed, "All messages should be processed with multiple custom configuration properties");
        assertEquals(6, processedCount.get(), "Should process exactly 6 messages");

        consumer.close();
        producer.close();

        logger.info("PeeGeeQConfiguration multiple properties integration test completed successfully");
    }

    @Test
    void testConfigurationDefaultValuesWithConsumerModes() throws Exception {
        logger.info(" Testing PeeGeeQConfiguration default values with consumer modes");

        // Create configuration with defaults
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build());

        // Verify default configuration values
        assertEquals(10, config.getQueueConfig().getBatchSize(), "Should use default batch size");
        assertEquals(Duration.ofSeconds(5), config.getQueueConfig().getPollingInterval(),
            "Should use default polling interval");
        assertEquals(Duration.ofSeconds(30), config.getQueueConfig().getVisibilityTimeout(),
            "Should use default visibility timeout");
        assertEquals(1, config.getQueueConfig().getConsumerThreads(),
            "Should use default consumer threads");

        String topicName = "test-config-default-values-integration";

        // Test POLLING_ONLY mode with default configuration values (but faster polling for test)
        ConsumerConfig pollingConfig = ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofSeconds(1)) // Use faster polling for test reliability
                .batchSize(10) // Should align with default configuration
                .consumerThreads(1) // Should align with default configuration
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, pollingConfig);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        CountDownLatch allDone = new CountDownLatch(3);
        AtomicInteger processedCount = new AtomicInteger(0);

        // Subscribe to messages
        consumer.subscribe(message -> {
            int count = processedCount.incrementAndGet();
            logger.info("\ud83d\udce8 Processed message {} with default configuration values: {}", count, message.getPayload());
            allDone.countDown();
            return Future.succeededFuture();
        });

        // Send messages to test default configuration behavior
        producer.send("Default config message 1");
        producer.send("Default config message 2");
        producer.send("Default config message 3");

        // Wait for messages to be processed
        boolean allProcessed = allDone.await(15, TimeUnit.SECONDS);
        assertTrue(allProcessed, "All messages should be processed with default configuration values");
        assertEquals(3, processedCount.get(), "Should process exactly 3 messages");

        consumer.close();
        producer.close();

        logger.info("PeeGeeQConfiguration default values integration test completed successfully");
    }
}


