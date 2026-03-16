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
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class ConsumerModeBackwardCompatibilityTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerModeBackwardCompatibilityTest.class);

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

        logger.info("Test setup completed for consumer mode backward compatibility testing");
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
    void testLegacyApiWithoutConsumerConfig(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing legacy API without ConsumerConfig (should default to HYBRID)");

        String topicName = "test-legacy-api";

        // Use old API without ConsumerConfig - should default to HYBRID mode
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            Checkpoint messagesReceived = testContext.checkpoint(3);

            consumer.subscribe(message -> {
                processedCount.incrementAndGet();
                logger.info("📨 Legacy API processed message: {}", message.getPayload());
                messagesReceived.flag();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup, then send
            vertx.setTimer(1000, id -> {
                try {
                    producer.send("Legacy message 1").get(5, TimeUnit.SECONDS);
                    producer.send("Legacy message 2").get(5, TimeUnit.SECONDS);
                    producer.send("Legacy message 3").get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    testContext.failNow(e);
                }
            });

            // Wait for message processing
            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Legacy API should process messages successfully");
            assertEquals(3, processedCount.get(), "Should process exactly 3 messages with legacy API");

            logger.info("Legacy API compatibility verified - processed: {} messages", processedCount.get());

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("Legacy API without ConsumerConfig test completed successfully");
    }

    @Test
    void testMixedApiUsage(Vertx vertx, VertxTestContext testContext) throws Exception {
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
            Checkpoint messagesReceived = testContext.checkpoint(4); // 2 messages each

            legacyConsumer.subscribe(message -> {
                legacyCount.incrementAndGet();
                logger.info("📨 Legacy consumer processed: {}", message.getPayload());
                messagesReceived.flag();
                return CompletableFuture.completedFuture(null);
            });

            newConsumer.subscribe(message -> {
                newCount.incrementAndGet();
                logger.info("📨 New consumer processed: {}", message.getPayload());
                messagesReceived.flag();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup, then send
            vertx.setTimer(1000, id -> {
                try {
                    legacyProducer.send("Mixed legacy message 1").get(5, TimeUnit.SECONDS);
                    newProducer.send("Mixed new message 1").get(5, TimeUnit.SECONDS);
                    legacyProducer.send("Mixed legacy message 2").get(5, TimeUnit.SECONDS);
                    newProducer.send("Mixed new message 2").get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    testContext.failNow(e);
                }
            });

            // Wait for message processing
            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Mixed API usage should process all messages");
            assertEquals(2, legacyCount.get(), "Legacy consumer should process 2 messages");
            assertEquals(2, newCount.get(), "New consumer should process 2 messages");

            logger.info("Mixed API usage verified - legacy: {}, new: {}", legacyCount.get(), newCount.get());

        } finally {
            legacyConsumer.close();
            newConsumer.close();
            legacyProducer.close();
            newProducer.close();
        }

        logger.info("Mixed API usage test completed successfully");
    }

    @Test
    void testLegacyApiDefaultBehavior(Vertx vertx, VertxTestContext testContext) throws Exception {
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
            Checkpoint messagesReceived = testContext.checkpoint(4); // 2 messages each

            legacyConsumer.subscribe(message -> {
                legacyCount.incrementAndGet();
                logger.info("📨 Legacy default processed: {}", message.getPayload());
                messagesReceived.flag();
                return CompletableFuture.completedFuture(null);
            });

            hybridConsumer.subscribe(message -> {
                hybridCount.incrementAndGet();
                logger.info("📨 Explicit HYBRID processed: {}", message.getPayload());
                messagesReceived.flag();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup, then send
            vertx.setTimer(1000, id -> {
                try {
                    legacyProducer.send("Legacy default message 1").get(5, TimeUnit.SECONDS);
                    hybridProducer.send("Explicit hybrid message 1").get(5, TimeUnit.SECONDS);
                    legacyProducer.send("Legacy default message 2").get(5, TimeUnit.SECONDS);
                    hybridProducer.send("Explicit hybrid message 2").get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    testContext.failNow(e);
                }
            });

            // Wait for message processing
            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Both legacy and explicit HYBRID should process messages");
            assertEquals(2, legacyCount.get(), "Legacy default should process 2 messages");
            assertEquals(2, hybridCount.get(), "Explicit HYBRID should process 2 messages");

            logger.info("Legacy API default behavior verified - legacy: {}, hybrid: {}",
                legacyCount.get(), hybridCount.get());

        } finally {
            legacyConsumer.close();
            hybridConsumer.close();
            legacyProducer.close();
            hybridProducer.close();
        }

        logger.info("Legacy API default behavior test completed successfully");
    }

    @Test
    void testGradualMigrationPath(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing gradual migration path from legacy to new API");

        String topicName = "test-gradual-migration";

        // Start with legacy API
        MessageConsumer<String> legacyConsumer = factory.createConsumer(topicName, String.class);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger totalProcessed = new AtomicInteger(0);

        try {
            Checkpoint legacyMessages = testContext.checkpoint(2);

            legacyConsumer.subscribe(message -> {
                totalProcessed.incrementAndGet();
                logger.info("📨 Legacy migration processed: {}", message.getPayload());
                legacyMessages.flag();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup, then send
            vertx.setTimer(500, id -> {
                try {
                    producer.send("Migration message 1").get(5, TimeUnit.SECONDS);
                    producer.send("Migration message 2").get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    testContext.failNow(e);
                }
            });

            // Wait for processing
            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Legacy consumer should process initial messages");

            // Close legacy consumer
            legacyConsumer.close();

            // Migrate to new API with explicit configuration
            MessageConsumer<String> newConsumer = factory.createConsumer(topicName, String.class,
                ConsumerConfig.builder().mode(ConsumerMode.HYBRID).build());

            VertxTestContext phase2 = new VertxTestContext();
            Checkpoint newMessages = phase2.checkpoint(2);

            newConsumer.subscribe(message -> {
                totalProcessed.incrementAndGet();
                logger.info("📨 New API migration processed: {}", message.getPayload());
                newMessages.flag();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for new consumer setup, then send
            vertx.setTimer(500, id -> {
                try {
                    producer.send("Migration message 3").get(5, TimeUnit.SECONDS);
                    producer.send("Migration message 4").get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // Best effort - phase2 will timeout
                }
            });

            // Wait for processing
            boolean newReceived = phase2.awaitCompletion(10, TimeUnit.SECONDS);
            assertTrue(newReceived, "New consumer should process migrated messages");

            assertEquals(4, totalProcessed.get(), "Should process all 4 messages during migration");

            logger.info("Gradual migration verified - total processed: {}", totalProcessed.get());

            newConsumer.close();

        } finally {
            producer.close();
        }

        logger.info("Gradual migration path test completed successfully");
    }

    @Test
    void testLegacyApiPerformanceConsistency(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing legacy API performance consistency");

        String topicName = "test-legacy-performance";

        // Use legacy API for performance test
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            Checkpoint messagesReceived = testContext.checkpoint(5);

            long startTime = System.currentTimeMillis();

            consumer.subscribe(message -> {
                processedCount.incrementAndGet();
                logger.debug("📨 Performance test processed: {}", message.getPayload());
                messagesReceived.flag();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup, then send
            vertx.setTimer(500, id -> {
                try {
                    for (int i = 1; i <= 5; i++) {
                        producer.send("Performance message " + i).get(5, TimeUnit.SECONDS);
                    }
                } catch (Exception e) {
                    testContext.failNow(e);
                }
            });

            // Wait for message processing
            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Legacy API should handle performance test messages");

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            assertEquals(5, processedCount.get(), "Should process exactly 5 messages");
            assertTrue(duration < 10000, "Processing should complete within reasonable time (10s)");

            logger.info("Legacy API performance verified - processed: {} messages in {}ms",
                processedCount.get(), duration);

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("Legacy API performance consistency test completed successfully");
    }
}


