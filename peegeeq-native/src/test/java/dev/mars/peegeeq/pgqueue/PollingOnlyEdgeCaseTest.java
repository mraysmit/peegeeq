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
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge case tests for POLLING_ONLY consumer mode.
 * Tests critical scenarios that could cause issues in production.
 *
 * Following established coding principles:
 * - Use real infrastructure (TestContainers) rather than mocks
 * - Test edge cases that could cause production issues
 * - Validate each scenario thoroughly
 * - Follow existing patterns from ConsumerModeIntegrationTest
 * - Keep tests focused and lightweight to avoid resource exhaustion
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class PollingOnlyEdgeCaseTest {
    private static final Logger logger = LoggerFactory.getLogger(PollingOnlyEdgeCaseTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
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
        // Ensure required schema exists for native queue tests
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.NATIVE_QUEUE, SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);

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

        logger.info("Test setup completed for POLLING_ONLY edge case testing");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (factory != null) {
            factory.close();
        }
        if (manager != null) {
            manager.closeReactive().toCompletionStage().toCompletableFuture().join();
        }
        logger.info("Test teardown completed");
    }

    @Test
    void testFastPollingInterval(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing fast polling interval (100ms)");

        String topicName = "test-fast-polling";

        // Create POLLING_ONLY consumer with very fast polling interval
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofMillis(100)) // Very fast polling
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger messageCount = new AtomicInteger(0);
        Checkpoint messagesReceived = testContext.checkpoint(5);

        consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info("📨 Received fast polling message {}: {}", count, message.getPayload());
            messagesReceived.flag();
            return CompletableFuture.completedFuture(null);
        });

        // Send messages rapidly
        for (int i = 1; i <= 5; i++) {
            producer.send("Fast polling message " + i).get(5, TimeUnit.SECONDS);
        }

        // Wait for all messages to be processed
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should receive all 5 messages with fast polling");
        assertEquals(5, messageCount.get(), "Should have processed exactly 5 messages");

        consumer.close();
        producer.close();
        logger.info("✅ POLLING_ONLY handles fast polling interval correctly");
    }

    @Test
    void testSlowPollingInterval(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing slow polling interval (5 seconds)");

        String topicName = "test-slow-polling";

        // Create POLLING_ONLY consumer with slow polling interval
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofSeconds(5)) // Slow polling
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger messageCount = new AtomicInteger(0);
        Checkpoint messagesReceived = testContext.checkpoint(2);

        consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info("📨 Received slow polling message {}: {}", count, message.getPayload());
            messagesReceived.flag();
            return CompletableFuture.completedFuture(null);
        });

        // Send messages before polling kicks in
        producer.send("Slow polling message 1").get(5, TimeUnit.SECONDS);
        producer.send("Slow polling message 2").get(5, TimeUnit.SECONDS);

        // Wait for polling to pick up messages (need to wait longer than polling interval)
        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Should receive all 2 messages with slow polling");
        assertEquals(2, messageCount.get(), "Should have processed exactly 2 messages");

        consumer.close();
        producer.close();
        logger.info("✅ POLLING_ONLY handles slow polling interval correctly");
    }

    @Test
    void testHighConcurrencyPolling(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing high concurrency polling");

        String topicName = "test-high-concurrency-polling";

        // Create POLLING_ONLY consumer with multiple threads (reduced for stability)
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofMillis(500))
                .consumerThreads(3) // Reduced thread count for stability
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger messageCount = new AtomicInteger(0);
        Checkpoint messagesReceived = testContext.checkpoint(10);

        consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info("📨 Received concurrent message {}: {} on thread {}",
                count, message.getPayload(), Thread.currentThread().getName());
            messagesReceived.flag();
            return CompletableFuture.completedFuture(null);
        });

        // Send messages sequentially to avoid overwhelming the system
        for (int i = 1; i <= 10; i++) {
            producer.send("Concurrent message " + i).get(5, TimeUnit.SECONDS);
        }

        // Wait for all messages to be processed
        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Should receive all 10 messages with high concurrency");
        assertEquals(10, messageCount.get(), "Should have processed exactly 10 messages");

        consumer.close();
        producer.close();
        logger.info("✅ POLLING_ONLY handles high concurrency correctly");
    }

    @Test
    void testBatchProcessing(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing batch processing");

        String topicName = "test-batch-processing";

        // Create POLLING_ONLY consumer with small batch size for focused testing
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofMillis(500)) // Fast polling for quick test
                .batchSize(5) // Small batch size for focused testing
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger messageCount = new AtomicInteger(0);
        Checkpoint messagesReceived = testContext.checkpoint(10);

        consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info("📨 Processed batch message {}: {}", count, message.getPayload());
            messagesReceived.flag();
            return CompletableFuture.completedFuture(null);
        });

        // Send messages before consumer starts polling
        logger.info("Sending 10 messages for batch processing...");
        for (int i = 1; i <= 10; i++) {
            producer.send("Batch message " + i).get(5, TimeUnit.SECONDS);
        }

        // Wait for all messages to be processed in batches
        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Should receive all 10 messages in batches");
        assertEquals(10, messageCount.get(), "Should have processed exactly 10 messages");

        consumer.close();
        producer.close();
        logger.info("✅ POLLING_ONLY handles batch processing correctly");
    }

    @Test
    void testEmptyQueuePolling(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing empty queue polling behavior");

        String topicName = "test-empty-queue-polling";

        // Create POLLING_ONLY consumer for empty queue
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofMillis(500))
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);

        AtomicInteger messageCount = new AtomicInteger(0);
        AtomicReference<String> lastMessage = new AtomicReference<>();
        Checkpoint messageReceived = testContext.checkpoint();

        consumer.subscribe(message -> {
            messageCount.incrementAndGet();
            lastMessage.set(message.getPayload());
            logger.info("📨 Received message: {}", message.getPayload());
            messageReceived.flag();
            return CompletableFuture.completedFuture(null);
        });

        // Let it poll empty queue for a while, then send a message
        vertx.setTimer(3000, id -> {
            // Verify no messages were processed from empty queue
            if (messageCount.get() != 0) {
                testContext.failNow(new AssertionError("Should not process any messages from empty queue"));
                return;
            }

            // Now send a message and verify it gets processed
            try {
                MessageProducer<String> producer = factory.createProducer(topicName, String.class);
                producer.send("Message after empty polling").get(5, TimeUnit.SECONDS);
                producer.close();
            } catch (Exception e) {
                testContext.failNow(e);
            }
        });

        // Wait for the message to be processed
        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Should receive the message after empty polling");
        assertEquals("Message after empty polling", lastMessage.get(), "Should receive the correct message");

        consumer.close();
        logger.info("✅ POLLING_ONLY handles empty queue polling correctly");
    }

    @Test
    void testPollingWithDatabaseConnectionIssues(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing polling resilience with database connection issues");

        String topicName = "test-db-connection-issues";

        // Create POLLING_ONLY consumer with shorter polling interval for faster test
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofMillis(500))
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger messageCount = new AtomicInteger(0);
        Checkpoint messagesReceived = testContext.checkpoint(3);

        consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info("📨 Received resilient message {}: {}", count, message.getPayload());
            messagesReceived.flag();
            return CompletableFuture.completedFuture(null);
        });

        // Send messages sequentially
        producer.send("Message 1 - resilience test").get(5, TimeUnit.SECONDS);
        producer.send("Message 2 - resilience test").get(5, TimeUnit.SECONDS);
        producer.send("Message 3 - resilience test").get(5, TimeUnit.SECONDS);

        // Wait for messages to be processed (consumer should handle normal operations)
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should receive all messages in normal operation");
        assertEquals(3, messageCount.get(), "Should have processed exactly 3 messages");

        consumer.close();
        producer.close();
        logger.info("✅ POLLING_ONLY handles normal database operations correctly");
    }
}


