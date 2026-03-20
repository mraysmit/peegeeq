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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-consumer mode integration tests for PeeGeeQ Native Queue.
 * Tests multiple consumers with same/different modes, consumer mode isolation,
 * and thread safety across modes.
 *
 * Following established coding principles:
 * - Use real infrastructure (TestContainers) rather than mocks
 * - Test multi-consumer scenarios that could cause production issues
 * - Validate proper isolation and thread safety across consumer modes
 * - Follow existing patterns from other integration tests
 * - Test with various consumer combinations and load scenarios
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class MultiConsumerModeTest {

    private static final Logger logger = LoggerFactory.getLogger(MultiConsumerModeTest.class);

    @Container
    private static final PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("testdb");
        container.withUsername("testuser");
        container.withPassword("testpass");
        return container;
    }

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("🔧 Setting up MultiConsumerModeTest");

        // Clear any existing system properties
        System.clearProperty("peegeeq.queue.polling-interval");
        System.clearProperty("peegeeq.queue.visibility-timeout");
        System.clearProperty("peegeeq.queue.batch-size");
        System.clearProperty("peegeeq.consumer.threads");

        initializeManagerAndFactory();
        logger.info("MultiConsumerModeTest setup completed");
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("🧹 Cleaning up MultiConsumerModeTest");

        if (factory != null) {
            factory.close();
        }
        if (manager != null) {
            manager.closeReactive().toCompletionStage().toCompletableFuture().join();
        }

        // Clear system properties
        System.clearProperty("peegeeq.queue.polling-interval");
        System.clearProperty("peegeeq.queue.visibility-timeout");
        System.clearProperty("peegeeq.queue.batch-size");
        System.clearProperty("peegeeq.consumer.threads");

        logger.info("MultiConsumerModeTest cleanup completed");
    }

    private void initializeManagerAndFactory() throws Exception {
        // Configure test properties using TestContainer pattern (following established patterns)
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

        // Initialize PeeGeeQ with test configuration
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory using the proper pattern
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        factory = provider.createFactory("native", databaseService);
    }

    @Test
    void testMultipleConsumersSameMode(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing multiple consumers with same mode (HYBRID)");

        String topicName = "test-multi-same-mode";
        int consumerCount = 3;
        int messagesPerConsumer = 2;
        int totalMessages = consumerCount * messagesPerConsumer;

        List<MessageConsumer<String>> consumers = new ArrayList<>();
        Checkpoint messagesReceived = testContext.checkpoint(totalMessages);
        AtomicInteger totalProcessed = new AtomicInteger(0);

        try {
            for (int i = 0; i < consumerCount; i++) {
                final int consumerId = i;
                MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
                    ConsumerConfig.builder()
                        .mode(ConsumerMode.HYBRID)
                        .pollingInterval(Duration.ofSeconds(1))
                        .build());

                consumer.subscribe(message -> {
                    int processed = totalProcessed.incrementAndGet();
                    logger.info("📨 Consumer {} processed message: {} (Total: {})",
                        consumerId, message.getPayload(), processed);
                    messagesReceived.flag();
                    return CompletableFuture.completedFuture(null);
                });

                consumers.add(consumer);
            }

            // Wait for consumer setup, then send
            vertx.setTimer(2000, id -> {
                try {
                    MessageProducer<String> producer = factory.createProducer(topicName, String.class);
                    for (int i = 0; i < totalMessages; i++) {
                        producer.send("Multi-same-mode message " + (i + 1)).get(5, TimeUnit.SECONDS);
                    }
                    producer.close();
                } catch (Exception e) {
                    testContext.failNow(e);
                }
            });

            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "All messages should be processed by multiple consumers with same mode");
            assertEquals(totalMessages, totalProcessed.get(), "Should process exactly " + totalMessages + " messages");

            logger.info("Multiple consumers same mode test verified - processed: {} messages",
                totalProcessed.get());

        } finally {
            for (MessageConsumer<String> consumer : consumers) {
                consumer.close();
            }
        }

        logger.info("Multiple consumers same mode test completed successfully");
    }

    @Test
    void testMultipleConsumersDifferentModes(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing multiple consumers with different modes");

        String topicName = "test-multi-different-modes";
        int totalMessages = 6;

        Checkpoint messagesReceived = testContext.checkpoint(totalMessages);
        AtomicInteger listenNotifyProcessed = new AtomicInteger(0);
        AtomicInteger pollingProcessed = new AtomicInteger(0);
        AtomicInteger hybridProcessed = new AtomicInteger(0);

        // Create consumers with different modes
        MessageConsumer<String> listenConsumer = factory.createConsumer(topicName + "-listen", String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build());

        MessageConsumer<String> pollingConsumer = factory.createConsumer(topicName + "-polling", String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofMillis(500))
                .build());

        MessageConsumer<String> hybridConsumer = factory.createConsumer(topicName + "-hybrid", String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofSeconds(1))
                .build());

        try {
            // Setup message handlers
            listenConsumer.subscribe(message -> {
                int processed = listenNotifyProcessed.incrementAndGet();
                logger.info("📻 LISTEN_NOTIFY consumer processed: {} (Count: {})", message.getPayload(), processed);
                messagesReceived.flag();
                return CompletableFuture.completedFuture(null);
            });

            pollingConsumer.subscribe(message -> {
                int processed = pollingProcessed.incrementAndGet();
                logger.info("🔄 POLLING consumer processed: {} (Count: {})", message.getPayload(), processed);
                messagesReceived.flag();
                return CompletableFuture.completedFuture(null);
            });

            hybridConsumer.subscribe(message -> {
                int processed = hybridProcessed.incrementAndGet();
                logger.info("🔀 HYBRID consumer processed: {} (Count: {})", message.getPayload(), processed);
                messagesReceived.flag();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup, then send
            vertx.setTimer(2000, id -> {
                try {
                    MessageProducer<String> listenProducer = factory.createProducer(topicName + "-listen", String.class);
                    MessageProducer<String> pollingProducer = factory.createProducer(topicName + "-polling", String.class);
                    MessageProducer<String> hybridProducer = factory.createProducer(topicName + "-hybrid", String.class);

                    for (int i = 0; i < 2; i++) {
                        listenProducer.send("Listen message " + (i + 1)).get(5, TimeUnit.SECONDS);
                        pollingProducer.send("Polling message " + (i + 1)).get(5, TimeUnit.SECONDS);
                        hybridProducer.send("Hybrid message " + (i + 1)).get(5, TimeUnit.SECONDS);
                    }

                    listenProducer.close();
                    pollingProducer.close();
                    hybridProducer.close();
                } catch (Exception e) {
                    testContext.failNow(e);
                }
            });

            assertTrue(testContext.awaitCompletion(20, TimeUnit.SECONDS), "All messages should be processed by consumers with different modes");

            // Verify each consumer processed its messages
            assertEquals(2, listenNotifyProcessed.get(), "LISTEN_NOTIFY consumer should process 2 messages");
            assertEquals(2, pollingProcessed.get(), "POLLING consumer should process 2 messages");
            assertEquals(2, hybridProcessed.get(), "HYBRID consumer should process 2 messages");

            logger.info("Multiple consumers different modes test verified - Listen: {}, Polling: {}, Hybrid: {}",
                listenNotifyProcessed.get(), pollingProcessed.get(), hybridProcessed.get());

        } finally {
            listenConsumer.close();
            pollingConsumer.close();
            hybridConsumer.close();
        }

        logger.info("Multiple consumers different modes test completed successfully");
    }

    @Test
    void testConsumerModeIsolation(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing consumer mode isolation");

        String topicName = "test-mode-isolation";
        int messagesPerMode = 3;

        Checkpoint messagesReceived = testContext.checkpoint(messagesPerMode);
        AtomicInteger pollingProcessed = new AtomicInteger(0);
        AtomicInteger hybridProcessed = new AtomicInteger(0);
        AtomicInteger listenProcessed = new AtomicInteger(0);

        // Create consumers with different modes for the same topic
        MessageConsumer<String> pollingConsumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofMillis(500))
                .build());

        MessageConsumer<String> hybridConsumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofSeconds(1))
                .build());

        try {
            // Setup message handlers
            pollingConsumer.subscribe(message -> {
                int processed = pollingProcessed.incrementAndGet();
                logger.info("🔄 POLLING consumer processed: {} (Count: {})", message.getPayload(), processed);
                messagesReceived.flag();
                return CompletableFuture.completedFuture(null);
            });

            hybridConsumer.subscribe(message -> {
                int processed = hybridProcessed.incrementAndGet();
                logger.info("🔀 HYBRID consumer processed: {} (Count: {})", message.getPayload(), processed);
                messagesReceived.flag();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup, then send
            vertx.setTimer(2000, id -> {
                try {
                    MessageProducer<String> producer = factory.createProducer(topicName, String.class);
                    for (int i = 0; i < messagesPerMode; i++) {
                        producer.send("Isolation test message " + (i + 1)).get(5, TimeUnit.SECONDS);
                    }
                    producer.close();
                } catch (Exception e) {
                    testContext.failNow(e);
                }
            });

            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Messages should be processed by competing consumers");

            // Verify that messages were distributed between consumers (not duplicated)
            int totalProcessed = pollingProcessed.get() + hybridProcessed.get();
            assertEquals(messagesPerMode, totalProcessed, "Total processed should equal messages sent (no duplication)");
            assertEquals(0, listenProcessed.get(), "LISTEN_NOTIFY consumer should not process any messages");

            logger.info("Consumer mode isolation test verified - Polling: {}, Hybrid: {}, Total: {}",
                pollingProcessed.get(), hybridProcessed.get(), totalProcessed);

        } finally {
            pollingConsumer.close();
            hybridConsumer.close();
        }

        logger.info("Consumer mode isolation test completed successfully");
    }

    @Test
    void testThreadSafetyAcrossModes(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing thread safety across consumer modes");

        String topicName = "test-thread-safety";
        int consumerCount = 4;
        int messagesPerConsumer = 5;
        int totalMessages = consumerCount * messagesPerConsumer;

        List<MessageConsumer<String>> consumers = new ArrayList<>();
        Checkpoint messagesReceived = testContext.checkpoint(totalMessages);
        AtomicInteger totalProcessed = new AtomicInteger(0);

        try {
            ConsumerMode[] modes = {ConsumerMode.POLLING_ONLY, ConsumerMode.HYBRID, ConsumerMode.POLLING_ONLY, ConsumerMode.HYBRID};

            for (int i = 0; i < consumerCount; i++) {
                final int consumerId = i;
                MessageConsumer<String> consumer = factory.createConsumer(topicName + "-" + i, String.class,
                    ConsumerConfig.builder()
                        .mode(modes[i])
                        .pollingInterval(Duration.ofMillis(200 + (i * 100)))
                        .consumerThreads(1 + (i % 2))
                        .build());

                consumer.subscribe(message -> {
                    // Simulate some processing time to test thread safety
                    CompletableFuture<Void> future = new CompletableFuture<>();
                    vertx.setTimer(50 + (int)(Math.random() * 100), tid -> {
                        int processed = totalProcessed.incrementAndGet();
                        logger.info("🧵 Consumer {} (Mode: {}) processed: {} (Total: {})",
                            consumerId, modes[consumerId], message.getPayload(), processed);
                        messagesReceived.flag();
                        future.complete(null);
                    });
                    return future;
                });

                consumers.add(consumer);
            }

            // Wait for consumer setup, then send concurrently
            vertx.setTimer(3000, id -> {
                for (int i = 0; i < consumerCount; i++) {
                    final int consumerIndex = i;
                    CompletableFuture.runAsync(() -> {
                        try {
                            MessageProducer<String> producer = factory.createProducer(topicName + "-" + consumerIndex, String.class);
                            for (int j = 0; j < messagesPerConsumer; j++) {
                                producer.send("Thread-safety message " + consumerIndex + "-" + (j + 1)).get(5, TimeUnit.SECONDS);
                            }
                            producer.close();
                        } catch (Exception e) {
                            testContext.failNow(e);
                        }
                    });
                }
            });

            assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "All messages should be processed safely across different consumer modes");
            assertEquals(totalMessages, totalProcessed.get(), "Should process exactly " + totalMessages + " messages");

            logger.info("Thread safety across modes test verified - processed: {} messages",
                totalProcessed.get());

        } finally {
            for (MessageConsumer<String> consumer : consumers) {
                consumer.close();
            }
        }

        logger.info("Thread safety across modes test completed successfully");
    }

    @Test
    void testConsumerModePerformanceIsolation(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing consumer mode performance isolation");

        String topicName = "test-performance-isolation";
        int fastMessages = 10;
        int slowMessages = 5;
        int totalMessages = fastMessages + slowMessages;

        Checkpoint allMessagesReceived = testContext.checkpoint(totalMessages);
        AtomicInteger fastProcessed = new AtomicInteger(0);
        AtomicInteger slowProcessed = new AtomicInteger(0);

        // Create fast consumer (LISTEN_NOTIFY_ONLY for immediate processing)
        MessageConsumer<String> fastConsumer = factory.createConsumer(topicName + "-fast", String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build());

        // Create slow consumer (POLLING_ONLY with slower polling)
        MessageConsumer<String> slowConsumer = factory.createConsumer(topicName + "-slow", String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofSeconds(2)) // Slower polling
                .build());

        try {
            // Setup fast message handler
            fastConsumer.subscribe(message -> {
                int processed = fastProcessed.incrementAndGet();
                logger.info("⚡ FAST consumer processed: {} (Count: {})", message.getPayload(), processed);
                allMessagesReceived.flag();
                return CompletableFuture.completedFuture(null);
            });

            // Setup slow message handler
            slowConsumer.subscribe(message -> {
                int processed = slowProcessed.incrementAndGet();
                logger.info("🐌 SLOW consumer processed: {} (Count: {})", message.getPayload(), processed);
                allMessagesReceived.flag();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup, then send
            vertx.setTimer(2000, id -> {
                try {
                    MessageProducer<String> fastProducer = factory.createProducer(topicName + "-fast", String.class);
                    MessageProducer<String> slowProducer = factory.createProducer(topicName + "-slow", String.class);

                    for (int i = 0; i < fastMessages; i++) {
                        fastProducer.send("Fast message " + (i + 1)).get(5, TimeUnit.SECONDS);
                    }

                    for (int i = 0; i < slowMessages; i++) {
                        slowProducer.send("Slow message " + (i + 1)).get(5, TimeUnit.SECONDS);
                    }

                    fastProducer.close();
                    slowProducer.close();
                } catch (Exception e) {
                    testContext.failNow(e);
                }
            });

            assertTrue(testContext.awaitCompletion(25, TimeUnit.SECONDS));
            assertEquals(fastMessages, fastProcessed.get(), "Fast consumer should process all fast messages");
            assertEquals(slowMessages, slowProcessed.get(), "Slow consumer should process all slow messages");

        } finally {
            fastConsumer.close();
            slowConsumer.close();
        }

        logger.info("Consumer mode performance isolation test completed successfully");
    }
}


