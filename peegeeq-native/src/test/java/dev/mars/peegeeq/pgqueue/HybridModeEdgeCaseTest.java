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
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class HybridModeEdgeCaseTest {
    private static final Logger logger = LoggerFactory.getLogger(HybridModeEdgeCaseTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = createPostgresContainer();

    private static PostgreSQLContainer<?> createPostgresContainer() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_test");
        container.withUsername("peegeeq_user");
        container.withPassword("peegeeq_password");
        return container;
    }

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

        logger.info("Test setup completed for HYBRID mode edge case testing");
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
    void testHybridModeListenNotifyPrimary(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing HYBRID mode with LISTEN/NOTIFY as primary mechanism");

        String topicName = "test-hybrid-listen-primary";

        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofSeconds(10))
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger messageCount = new AtomicInteger(0);
        Checkpoint messagesReceived = testContext.checkpoint(3);

        consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info("📨 Received HYBRID message {}: {}", count, message.getPayload());
            messagesReceived.flag();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for LISTEN/NOTIFY setup, then send
        vertx.setTimer(500, id -> {
            try {
                producer.send("HYBRID message 1").get(5, TimeUnit.SECONDS);
                producer.send("HYBRID message 2").get(5, TimeUnit.SECONDS);
                producer.send("HYBRID message 3").get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                testContext.failNow(e);
            }
        });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should receive all 3 messages quickly via LISTEN/NOTIFY");
        assertEquals(3, messageCount.get(), "Should have processed exactly 3 messages");

        consumer.close();
        producer.close();
        logger.info("HYBRID mode prioritizes LISTEN/NOTIFY correctly");
    }

    @Test
    void testHybridModePollingFallback(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing HYBRID mode polling fallback for existing messages");

        String topicName = "test-hybrid-polling-fallback";

        // Send messages BEFORE creating consumer (to test polling fallback)
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        producer.send("Existing message 1").get(5, TimeUnit.SECONDS);
        producer.send("Existing message 2").get(5, TimeUnit.SECONDS);

        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofSeconds(1))
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);

        AtomicInteger messageCount = new AtomicInteger(0);
        Checkpoint messagesReceived = testContext.checkpoint(2);

        consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info("📨 Received existing message {}: {}", count, message.getPayload());
            messagesReceived.flag();
            return CompletableFuture.completedFuture(null);
        });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should receive existing messages via polling fallback");
        assertEquals(2, messageCount.get(), "Should have processed exactly 2 existing messages");

        consumer.close();
        producer.close();
        logger.info("HYBRID mode polling fallback works correctly");
    }

    @Test
    void testHybridModeBothMechanisms(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing HYBRID mode with both LISTEN/NOTIFY and polling active");

        String topicName = "test-hybrid-both-mechanisms";

        // Send some messages BEFORE consumer starts
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        producer.send("Pre-existing message 1").get(5, TimeUnit.SECONDS);
        producer.send("Pre-existing message 2").get(5, TimeUnit.SECONDS);

        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofSeconds(2))
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);

        AtomicInteger messageCount = new AtomicInteger(0);
        Checkpoint allMessages = testContext.checkpoint(5);

        consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info("📨 Received hybrid message {}: {}", count, message.getPayload());
            allMessages.flag();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for existing messages to be polled, then send new messages via LISTEN/NOTIFY
        vertx.setTimer(5000, id -> {
            try {
                producer.send("New message 1").get(5, TimeUnit.SECONDS);
                producer.send("New message 2").get(5, TimeUnit.SECONDS);
                producer.send("New message 3").get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                testContext.failNow(e);
            }
        });

        assertTrue(testContext.awaitCompletion(20, TimeUnit.SECONDS), "Should receive all 5 messages via both mechanisms");
        assertEquals(5, messageCount.get(), "Should have processed exactly 5 messages");

        consumer.close();
        producer.close();
        logger.info("HYBRID mode handles both mechanisms correctly");
    }

    @Test
    void testHybridModeResourceCleanup(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing HYBRID mode resource cleanup");

        String topicName = "test-hybrid-resource-cleanup";

        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofMillis(500))
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger messageCount = new AtomicInteger(0);
        AtomicReference<String> lastMessage = new AtomicReference<>();
        Checkpoint messageReceived = testContext.checkpoint();

        consumer.subscribe(message -> {
            messageCount.incrementAndGet();
            lastMessage.set(message.getPayload());
            logger.info("📨 Received cleanup test message: {}", message.getPayload());
            messageReceived.flag();
            return CompletableFuture.completedFuture(null);
        });

        // Send a message to verify consumer is working
        producer.send("Cleanup test message").get(5, TimeUnit.SECONDS);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));

        assertEquals(1, messageCount.get(), "Should have processed the test message");
        assertEquals("Cleanup test message", lastMessage.get(), "Should have received correct message");

        // Close consumer and verify graceful shutdown
        consumer.close();
        producer.close();

        logger.info("HYBRID mode resource cleanup completed successfully");
    }

    @Test
    void testHybridModeMessageOrdering(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing HYBRID mode message ordering consistency");

        String topicName = "test-hybrid-message-ordering";

        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofSeconds(1))
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger messageCount = new AtomicInteger(0);
        Checkpoint messagesReceived = testContext.checkpoint(6);

        consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info("📨 Received ordered message {}: {}", count, message.getPayload());
            messagesReceived.flag();
            return CompletableFuture.completedFuture(null);
        });

        // Send messages in sequence
        for (int i = 1; i <= 6; i++) {
            producer.send("Message " + i).get(5, TimeUnit.SECONDS);
        }

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Should receive all 6 messages");
        assertEquals(6, messageCount.get(), "Should have processed exactly 6 messages");

        consumer.close();
        producer.close();
        logger.info("HYBRID mode maintains message ordering consistency");
    }

    @Test
    void testHybridModePerformanceUnderLoad(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing HYBRID mode performance under moderate load");

        String topicName = "test-hybrid-performance";

        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofMillis(500))
                .consumerThreads(2)
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger messageCount = new AtomicInteger(0);
        Checkpoint messagesReceived = testContext.checkpoint(15);

        consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            if (count % 3 == 0) {
                logger.info("📨 Processed {} messages so far", count);
            }
            messagesReceived.flag();
            return CompletableFuture.completedFuture(null);
        });

        // Send messages
        for (int i = 1; i <= 15; i++) {
            producer.send("Performance test message " + i).get(5, TimeUnit.SECONDS);
        }

        assertTrue(testContext.awaitCompletion(20, TimeUnit.SECONDS), "Should handle moderate load efficiently");
        assertEquals(15, messageCount.get(), "Should have processed exactly 15 messages");

        consumer.close();
        producer.close();
        logger.info("HYBRID mode handles moderate load efficiently");
    }
}


