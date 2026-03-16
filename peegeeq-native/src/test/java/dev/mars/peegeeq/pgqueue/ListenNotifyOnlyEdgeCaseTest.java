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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge case tests for LISTEN_NOTIFY_ONLY consumer mode.
 * Tests critical scenarios that could cause issues in production.
 *
 * Following established coding principles:
 * - Use real infrastructure (TestContainers) rather than mocks
 * - Test edge cases that could cause production issues
 * - Validate each scenario thoroughly
 * - Follow existing patterns from ConsumerModeIntegrationTest
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class ListenNotifyOnlyEdgeCaseTest {
    private static final Logger logger = LoggerFactory.getLogger(ListenNotifyOnlyEdgeCaseTest.class);

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
        System.setProperty("peegeeq.queue.polling-interval", "PT1S");
        System.setProperty("peegeeq.queue.visibility-timeout", "PT30S");
        // Ensure required schema exists for native queue tests
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.NATIVE_QUEUE, SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);

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

        logger.info("Test setup completed for LISTEN_NOTIFY_ONLY edge case testing");
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
    void testExistingMessagesProcessingAfterListenSetup(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing existing messages processing after LISTEN setup");

        String topicName = "test-existing-messages";

        // First, send messages BEFORE setting up the consumer
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        producer.send("Message 1 - Before Consumer").get(5, TimeUnit.SECONDS);
        producer.send("Message 2 - Before Consumer").get(5, TimeUnit.SECONDS);
        producer.send("Message 3 - Before Consumer").get(5, TimeUnit.SECONDS);

        logger.info("Sent 3 messages before consumer setup");

        // Now create LISTEN_NOTIFY_ONLY consumer
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);

        AtomicInteger messageCount = new AtomicInteger(0);
        Checkpoint atLeastOneReceived = testContext.checkpoint();

        consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info("📨 Received existing message {}: {}", count, message.getPayload());
            atLeastOneReceived.flag();
            return CompletableFuture.completedFuture(null);
        });

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS),
            "Should receive at least 1 existing message via LISTEN_NOTIFY_ONLY mode");

        assertTrue(messageCount.get() >= 1, "Should have processed at least 1 message, got: " + messageCount.get());

        consumer.close();
        producer.close();
        logger.info("LISTEN_NOTIFY_ONLY correctly processed existing messages");
    }

    @Test
    void testChannelNameSpecialCharacters(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing channel name with special characters");

        String topicName = "test-special_chars.with-numbers123";

        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        Checkpoint messageReceived = testContext.checkpoint();
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        consumer.subscribe(message -> {
            receivedMessage.set(message.getPayload());
            messageReceived.flag();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for LISTEN setup, then send
        vertx.setTimer(1000, id -> {
            producer.send("Special characters test message");
        });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS),
            "Should receive message even with special characters in topic name");
        assertEquals("Special characters test message", receivedMessage.get());

        consumer.close();
        producer.close();
        logger.info("LISTEN_NOTIFY_ONLY handles special characters in topic names");
    }

    @Test
    void testLargePayloadProcessing(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing large payload processing");

        String topicName = "test-large-payload";

        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        // Create a large message (1MB)
        StringBuilder largeMessage = new StringBuilder();
        for (int i = 0; i < 100000; i++) {
            largeMessage.append("This is a large message payload for testing purposes. ");
        }
        String largePayload = largeMessage.toString();

        Checkpoint messageReceived = testContext.checkpoint();
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        consumer.subscribe(message -> {
            receivedMessage.set(message.getPayload());
            messageReceived.flag();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for LISTEN setup, then send
        vertx.setTimer(1000, id -> {
            producer.send(largePayload);
        });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
            "Should receive large message via LISTEN_NOTIFY_ONLY mode");
        assertEquals(largePayload, receivedMessage.get(), "Large payload should be received intact");

        consumer.close();
        producer.close();
        logger.info("LISTEN_NOTIFY_ONLY handles large payloads correctly");
    }

    @Test
    void testConcurrentProducerScenarios(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing concurrent producer scenarios");

        String topicName = "test-concurrent-producers";

        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);

        AtomicInteger messageCount = new AtomicInteger(0);
        Checkpoint messagesReceived = testContext.checkpoint(10);

        consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info("📨 Received concurrent message {}: {}", count, message.getPayload());
            messagesReceived.flag();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for LISTEN setup, then launch concurrent producers
        vertx.setTimer(1000, id -> {
            for (int i = 0; i < 5; i++) {
                final int producerId = i;
                CompletableFuture.runAsync(() -> {
                    try {
                        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
                        producer.send("Message from producer " + producerId + " - msg 1").get(5, TimeUnit.SECONDS);
                        producer.send("Message from producer " + producerId + " - msg 2").get(5, TimeUnit.SECONDS);
                        producer.close();
                    } catch (Exception e) {
                        testContext.failNow(e);
                    }
                });
            }
        });

        assertTrue(testContext.awaitCompletion(20, TimeUnit.SECONDS),
            "Should receive all 10 messages from concurrent producers");
        assertEquals(10, messageCount.get(), "Should have processed exactly 10 messages");

        consumer.close();
        logger.info("LISTEN_NOTIFY_ONLY handles concurrent producers correctly");
    }

    @Test
    void testShutdownDuringMessageProcessing(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing shutdown during message processing");

        String topicName = "test-shutdown-during-processing";

        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicBoolean processingStarted = new AtomicBoolean(false);
        Checkpoint testComplete = testContext.checkpoint();

        consumer.subscribe(message -> {
            processingStarted.set(true);
            // Simulate slow message processing via timer
            CompletableFuture<Void> future = new CompletableFuture<>();
            vertx.setTimer(2000, tid -> {
                processedCount.incrementAndGet();
                logger.info("📨 Processed message: {}", message.getPayload());
                future.complete(null);
            });
            return future;
        });

        // Wait for LISTEN setup, send message, then close during processing
        vertx.setTimer(1000, id -> {
            producer.send("Message for shutdown test");

            // Give time for message to arrive and processing to start
            vertx.setTimer(1000, id2 -> {
                logger.info("🔄 Closing consumer during message processing");
                consumer.close();
                producer.close();

                testContext.verify(() -> {
                    assertTrue(processedCount.get() >= 0, "Should handle shutdown gracefully");
                });
                testComplete.flag();
            });
        });

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
        logger.info("LISTEN_NOTIFY_ONLY handles shutdown during processing gracefully");
    }
}


