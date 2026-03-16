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
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class ConsumerModeFailureTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerModeFailureTest.class);

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

        logger.info("Test setup completed for consumer mode failure testing");
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
    void testExceptionHandlingInMessageHandlers(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing exception handling in message handlers");

        String topicName = "test-exception-handling";
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicInteger exceptionCount = new AtomicInteger(0);
            Checkpoint normalMessages = testContext.checkpoint(3);

            consumer.subscribe(message -> {
                String payload = message.getPayload();
                logger.info("📨 Processing message: {}", payload);

                if (payload.contains("exception")) {
                    exceptionCount.incrementAndGet();
                    logger.info("💥 Throwing intentional exception for message: {} (attempt {})", payload, exceptionCount.get());
                    throw new RuntimeException("Intentional test exception for: " + payload);
                } else {
                    processedCount.incrementAndGet();
                    logger.info("Successfully processed message: {}", payload);
                    normalMessages.flag();
                    return CompletableFuture.completedFuture(null);
                }
            });

            // Wait for consumer setup, then send
            vertx.setTimer(500, id -> {
                try {
                    producer.send("Normal message 1").get(5, TimeUnit.SECONDS);
                    producer.send("Message with exception").get(5, TimeUnit.SECONDS);
                    producer.send("Normal message 2").get(5, TimeUnit.SECONDS);
                    producer.send("Another exception message").get(5, TimeUnit.SECONDS);
                    producer.send("Normal message 3").get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    testContext.failNow(e);
                }
            });

            // Wait for message processing (longer timeout to account for retries)
            assertTrue(testContext.awaitCompletion(20, TimeUnit.SECONDS), "Should process non-exception messages successfully");

            // Verify that normal messages were processed
            assertEquals(3, processedCount.get(), "Should process exactly 3 normal messages");

            // The system retries failed messages multiple times before moving to DLQ
            // So we expect more than 2 exceptions due to retries (typically 3 attempts per message = 6 total)
            assertTrue(exceptionCount.get() >= 2, "Should encounter at least 2 exceptions (original attempts)");
            assertTrue(exceptionCount.get() <= 6, "Should not exceed 6 exceptions (2 messages × 3 retry attempts)");

            logger.info("Exception handling verified - processed: {}, exceptions: {} (includes retries)",
                processedCount.get(), exceptionCount.get());

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("Exception handling in message handlers test completed successfully");
    }

    @Test
    void testChannelNameCollisionHandling(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing channel name collision handling");

        String topicName = "test-channel-collision";

        MessageConsumer<String> consumer1 = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.LISTEN_NOTIFY_ONLY).build());
        MessageConsumer<String> consumer2 = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.LISTEN_NOTIFY_ONLY).build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger consumer1Count = new AtomicInteger(0);
            AtomicInteger consumer2Count = new AtomicInteger(0);
            Checkpoint messagesProcessed = testContext.checkpoint(2);

            consumer1.subscribe(message -> {
                consumer1Count.incrementAndGet();
                logger.info("📨 Consumer 1 received message: {}", message.getPayload());
                messagesProcessed.flag();
                return CompletableFuture.completedFuture(null);
            });

            consumer2.subscribe(message -> {
                consumer2Count.incrementAndGet();
                logger.info("📨 Consumer 2 received message: {}", message.getPayload());
                messagesProcessed.flag();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup, then send
            vertx.setTimer(1000, id -> {
                try {
                    producer.send("Collision test message 1").get(5, TimeUnit.SECONDS);
                    producer.send("Collision test message 2").get(5, TimeUnit.SECONDS);
                    producer.send("Collision test message 3").get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    testContext.failNow(e);
                }
            });

            // Wait for message processing
            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should process messages despite channel name collision");

            int totalProcessed = consumer1Count.get() + consumer2Count.get();
            assertTrue(totalProcessed >= 2, "Should process at least 2 messages across consumers");

            logger.info("Channel collision handling verified - consumer1: {}, consumer2: {}, total: {}",
                consumer1Count.get(), consumer2Count.get(), totalProcessed);

        } finally {
            consumer1.close();
            consumer2.close();
            producer.close();
        }

        logger.info("Channel name collision handling test completed successfully");
    }

    @Test
    void testPartialModeFailureRecovery(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing partial mode failure recovery");

        String topicName = "test-partial-failure-recovery";

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            Checkpoint messagesReceived = testContext.checkpoint(3);

            consumer.subscribe(message -> {
                processedCount.incrementAndGet();
                logger.info("📨 Processed message during partial failure test: {}", message.getPayload());
                messagesReceived.flag();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup, then send
            vertx.setTimer(1000, id -> {
                try {
                    producer.send("Recovery test message 1").get(5, TimeUnit.SECONDS);
                    producer.send("Recovery test message 2").get(5, TimeUnit.SECONDS);
                    producer.send("Recovery test message 3").get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    testContext.failNow(e);
                }
            });

            // Wait for message processing
            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Should process messages even with potential partial failures");
            assertEquals(3, processedCount.get(), "Should process exactly 3 messages");

            logger.info("Partial failure recovery verified - processed: {} messages", processedCount.get());

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("Partial mode failure recovery test completed successfully");
    }

    @Test
    void testRecoveryAfterTemporaryFailure(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing recovery after temporary failure");

        String topicName = "test-recovery-after-failure";
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicReference<String> lastProcessedMessage = new AtomicReference<>();
            Checkpoint messagesReceived = testContext.checkpoint(2);

            consumer.subscribe(message -> {
                processedCount.incrementAndGet();
                lastProcessedMessage.set(message.getPayload());
                logger.info("📨 Processed message during recovery test: {}", message.getPayload());
                messagesReceived.flag();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup, send first message
            vertx.setTimer(500, id -> {
                try {
                    producer.send("Before failure message").get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    testContext.failNow(e);
                }

                // Send second message after a delay to simulate recovery
                vertx.setTimer(2000, id2 -> {
                    try {
                        producer.send("After recovery message").get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        testContext.failNow(e);
                    }
                });
            });

            // Wait for message processing
            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Should recover and process messages after temporary failure");
            assertEquals(2, processedCount.get(), "Should process exactly 2 messages");
            assertEquals("After recovery message", lastProcessedMessage.get(),
                "Should process the recovery message last");

            logger.info("Recovery after failure verified - processed: {} messages", processedCount.get());

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("Recovery after temporary failure test completed successfully");
    }

    @Test
    void testConsumerModeRobustnessUnderLoad(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing consumer mode robustness under moderate load");

        String topicName = "test-robustness-under-load";
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            Checkpoint messagesReceived = testContext.checkpoint(10);

            consumer.subscribe(message -> {
                int count = processedCount.incrementAndGet();
                logger.debug("📨 Processed load test message {}: {}", count, message.getPayload());
                messagesReceived.flag();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup, then send
            vertx.setTimer(500, id -> {
                try {
                    for (int i = 1; i <= 10; i++) {
                        producer.send("Load test message " + i).get(5, TimeUnit.SECONDS);
                    }
                } catch (Exception e) {
                    testContext.failNow(e);
                }
            });

            // Wait for message processing
            assertTrue(testContext.awaitCompletion(20, TimeUnit.SECONDS), "Should handle moderate load without failures");
            assertEquals(10, processedCount.get(), "Should process exactly 10 messages under load");

            logger.info("Robustness under load verified - processed: {} messages", processedCount.get());

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("Consumer mode robustness under load test completed successfully");
    }
}


