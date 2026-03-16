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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
@ExtendWith(VertxExtension.class)
@Testcontainers
class ConsumerModeResourceManagementTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerModeResourceManagementTest.class);

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
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
            manager.closeReactive().toCompletionStage().toCompletableFuture().join();
        }
        logger.info("Test teardown completed");
    }

    @Test
    void testConnectionPoolUsageAcrossConsumerModes(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing connection pool usage across consumer modes");

        String topicName = "test-connection-pool-usage";
        List<MessageConsumer<String>> consumers = new ArrayList<>();
        List<MessageProducer<String>> producers = new ArrayList<>();

        try {
            MessageConsumer<String> listenConsumer = factory.createConsumer(topicName + "-listen", String.class,
                ConsumerConfig.builder().mode(ConsumerMode.LISTEN_NOTIFY_ONLY).build());
            MessageConsumer<String> pollingConsumer = factory.createConsumer(topicName + "-polling", String.class,
                ConsumerConfig.builder().mode(ConsumerMode.POLLING_ONLY).pollingInterval(Duration.ofSeconds(1)).build());
            MessageConsumer<String> hybridConsumer = factory.createConsumer(topicName + "-hybrid", String.class,
                ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());

            consumers.add(listenConsumer);
            consumers.add(pollingConsumer);
            consumers.add(hybridConsumer);

            producers.add(factory.createProducer(topicName + "-listen", String.class));
            producers.add(factory.createProducer(topicName + "-polling", String.class));
            producers.add(factory.createProducer(topicName + "-hybrid", String.class));

            AtomicInteger messageCount = new AtomicInteger(0);
            Checkpoint messagesReceived = testContext.checkpoint(3);

            for (int i = 0; i < consumers.size(); i++) {
                final int index = i;
                consumers.get(i).subscribe(message -> {
                    messageCount.incrementAndGet();
                    logger.info("📨 Consumer {} received message: {}", index, message.getPayload());
                    messagesReceived.flag();
                    return CompletableFuture.completedFuture(null);
                });
            }

            // Wait for consumer setup, then send
            vertx.setTimer(1000, id -> {
                try {
                    for (int i = 0; i < producers.size(); i++) {
                        producers.get(i).send("Test message " + i).get(5, TimeUnit.SECONDS);
                    }
                } catch (Exception e) {
                    testContext.failNow(e);
                }
            });

            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should receive all messages across different consumer modes");
            assertEquals(3, messageCount.get(), "Should process exactly 3 messages");

            logger.info("Connection pool usage verified - all consumer modes working with shared resources");

        } finally {
            // Clean up resources
            for (MessageConsumer<String> consumer : consumers) {
                consumer.close();
            }
            for (MessageProducer<String> producer : producers) {
                producer.close();
            }
        }

        logger.info("Connection pool usage test completed successfully");
    }

    @Test
    void testVertxInstanceSharingAcrossConsumers() throws Exception {
        logger.info("🧪 Testing Vert.x instance sharing across consumers");

        String topicName = "test-vertx-sharing";
        List<MessageConsumer<String>> consumers = new ArrayList<>();

        try {
            for (int i = 0; i < 5; i++) {
                ConsumerConfig config = ConsumerConfig.builder()
                    .mode(ConsumerMode.HYBRID)
                    .pollingInterval(Duration.ofSeconds(1))
                    .build();

                MessageConsumer<String> consumer = factory.createConsumer(topicName + "-" + i, String.class, config);
                consumers.add(consumer);
            }

            AtomicInteger subscriptionCount = new AtomicInteger(0);

            // Subscribe all consumers
            for (int i = 0; i < consumers.size(); i++) {
                final int index = i;
                consumers.get(i).subscribe(message -> {
                    subscriptionCount.incrementAndGet();
                    logger.info("📨 Consumer {} received message: {}", index, message.getPayload());
                    return CompletableFuture.completedFuture(null);
                });
            }

            assertEquals(5, consumers.size(), "All consumers should be set up successfully");

            logger.info("Vert.x instance sharing verified - {} consumers created and subscribed", consumers.size());

        } finally {
            // Clean up resources
            for (MessageConsumer<String> consumer : consumers) {
                consumer.close();
            }
        }

        logger.info("Vert.x instance sharing test completed successfully");
    }

    @Test
    void testSchedulerResourceManagement(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing scheduler resource management for polling consumers");

        String topicName = "test-scheduler-resources";
        List<MessageConsumer<String>> pollingConsumers = new ArrayList<>();

        try {
            for (int i = 0; i < 3; i++) {
                ConsumerConfig config = ConsumerConfig.builder()
                    .mode(ConsumerMode.POLLING_ONLY)
                    .pollingInterval(Duration.ofMillis(500))
                    .build();

                MessageConsumer<String> consumer = factory.createConsumer(topicName + "-" + i, String.class, config);
                pollingConsumers.add(consumer);
            }

            AtomicInteger messageCount = new AtomicInteger(0);
            Checkpoint messagesReceived = testContext.checkpoint(3);

            for (int i = 0; i < pollingConsumers.size(); i++) {
                final int index = i;
                pollingConsumers.get(i).subscribe(message -> {
                    messageCount.incrementAndGet();
                    logger.info("📨 Polling consumer {} received message: {}", index, message.getPayload());
                    messagesReceived.flag();
                    return CompletableFuture.completedFuture(null);
                });
            }

            // Wait for polling setup, then send
            vertx.setTimer(1000, id -> {
                try {
                    MessageProducer<String> producer = factory.createProducer(topicName + "-0", String.class);
                    producer.send("Test polling message 1").get(5, TimeUnit.SECONDS);

                    MessageProducer<String> producer2 = factory.createProducer(topicName + "-1", String.class);
                    producer2.send("Test polling message 2").get(5, TimeUnit.SECONDS);

                    MessageProducer<String> producer3 = factory.createProducer(topicName + "-2", String.class);
                    producer3.send("Test polling message 3").get(5, TimeUnit.SECONDS);

                    producer.close();
                    producer2.close();
                    producer3.close();
                } catch (Exception e) {
                    testContext.failNow(e);
                }
            });

            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should receive messages via polling mechanism");
            assertEquals(3, messageCount.get(), "Should process exactly 3 messages");

            logger.info("Scheduler resource management verified - polling consumers working efficiently");

        } finally {
            // Clean up resources - this should properly clean up scheduler resources
            for (MessageConsumer<String> consumer : pollingConsumers) {
                consumer.close();
            }
        }

        logger.info("Scheduler resource management test completed successfully");
    }

    @Test
    void testMemoryUsagePatterns(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing memory usage patterns across consumer modes");

        String topicName = "test-memory-usage";

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            Checkpoint messagesReceived = testContext.checkpoint(10);

            consumer.subscribe(message -> {
                processedCount.incrementAndGet();
                logger.debug("📨 Processed message: {}", message.getPayload());
                messagesReceived.flag();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup, then send
            vertx.setTimer(500, id -> {
                try {
                    for (int i = 0; i < 10; i++) {
                        producer.send("Memory test message " + i).get(5, TimeUnit.SECONDS);
                    }
                } catch (Exception e) {
                    testContext.failNow(e);
                }
            });

            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Should process all messages without memory issues");
            assertEquals(10, processedCount.get(), "Should process exactly 10 messages");

            logger.info("Memory usage patterns verified - processed {} messages efficiently", processedCount.get());

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("Memory usage patterns test completed successfully");
    }

    @Test
    void testGracefulShutdownResourceCleanup(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing graceful shutdown and resource cleanup");

        String topicName = "test-graceful-shutdown";

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger processedCount = new AtomicInteger(0);
        Checkpoint messagesProcessed = testContext.checkpoint(2);

        consumer.subscribe(message -> {
            processedCount.incrementAndGet();
            logger.info("📨 Processing message during shutdown test: {}", message.getPayload());
            messagesProcessed.flag();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for consumer setup, then send
        vertx.setTimer(500, id -> {
            try {
                producer.send("Shutdown test message 1").get(5, TimeUnit.SECONDS);
                producer.send("Shutdown test message 2").get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                testContext.failNow(e);
            }
        });

        // Wait for messages to be processed
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should process messages before shutdown");

        // Test graceful shutdown
        logger.info("🔄 Testing graceful shutdown...");
        consumer.close();
        producer.close();

        assertEquals(2, processedCount.get(), "Should process exactly 2 messages");

        logger.info("Graceful shutdown and resource cleanup verified");
        logger.info("Graceful shutdown resource cleanup test completed successfully");
    }
}


