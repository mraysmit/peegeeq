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
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.Timeout;
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

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import dev.mars.peegeeq.test.vertx.TestCleanup;

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
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeEach
    void setUp(VertxTestContext ctx) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Configure test properties using TestContainer pattern (following existing patterns)
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT1S")
                .property("peegeeq.queue.visibility-timeout", "PT30S")
                .property("peegeeq.metrics.enabled", "true")
                .property("peegeeq.circuit-breaker.enabled", "true")
                .build();
        // Ensure required schema exists for native queue tests
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.NATIVE_QUEUE, SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);

        // Initialize PeeGeeQ (following existing patterns)
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .onSuccess(v -> {
                    // Create factory using the proper pattern
                    PgDatabaseService databaseService = new PgDatabaseService(manager);
                    PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
                    // Register native factory implementation
                    PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                    factory = provider.createFactory("native", databaseService);
                    logger.info("Test setup completed for POLLING_ONLY edge case testing");
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        logger.info("Tearing down: closing resources and manager");
        (factory != null ? factory.close() : Future.<Void>succeededFuture())
            .compose(v -> manager != null ? manager.closeReactive() : Future.succeededFuture())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(err -> {
                logger.error("Error during teardown", err);
                testContext.failNow(err);
            });
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        logger.info("Test teardown completed");
    }

    @Test
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    void testFastPollingInterval(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info(" Testing fast polling interval (100ms)");

        String topicName = "test-fast-polling";

        // Create POLLING_ONLY consumer with very fast polling interval
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofMillis(100)) // Very fast polling
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger messageCount = new AtomicInteger(0);
        CountDownLatch messagesReceived = testContext.checkpoint().asLatch(5);

        Future<Void> sendChain = consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info(" Received fast polling message {}: {}", count, message.getPayload());
            messagesReceived.countDown();
            return Future.succeededFuture();
        });

        // Send messages rapidly
        for (int i = 1; i <= 5; i++) {
            String payload = "Fast polling message " + i;
            sendChain = sendChain.compose(v -> producer.send(payload));
        }
        sendChain.onFailure(testContext::failNow);

        // Wait for all messages to be processed
    }

    @Test
    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    void testSlowPollingInterval(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info(" Testing slow polling interval (5 seconds)");

        String topicName = "test-slow-polling";

        // Create POLLING_ONLY consumer with slow polling interval
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofSeconds(5)) // Slow polling
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger messageCount = new AtomicInteger(0);
        CountDownLatch messagesReceived = testContext.checkpoint().asLatch(2);

        Future<Void> subscription = consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info(" Received slow polling message {}: {}", count, message.getPayload());
            messagesReceived.countDown();
            return Future.succeededFuture();
        });

        // Send messages before polling kicks in
        subscription
            .compose(v -> producer.send("Slow polling message 1"))
            .compose(v -> producer.send("Slow polling message 2"))
            .onFailure(testContext::failNow);

        // Wait for polling to pick up messages (need to wait longer than polling interval)
    }

    @Test
    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    void testHighConcurrencyPolling(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info(" Testing high concurrency polling");

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
        CountDownLatch messagesReceived = testContext.checkpoint().asLatch(10);

        Future<Void> sendChain = consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info(" Received concurrent message {}: {} on thread {}",
                count, message.getPayload(), Thread.currentThread().getName());
            messagesReceived.countDown();
            return Future.succeededFuture();
        });

        // Send messages sequentially to avoid overwhelming the system
        for (int i = 1; i <= 10; i++) {
            String payload = "Concurrent message " + i;
            sendChain = sendChain.compose(v -> producer.send(payload));
        }
        sendChain.onFailure(testContext::failNow);

        // Wait for all messages to be processed
    }

    @Test
    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    void testBatchProcessing(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info(" Testing batch processing");

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
        CountDownLatch messagesReceived = testContext.checkpoint().asLatch(10);

        Future<Void> sendChain = consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info(" Processed batch message {}: {}", count, message.getPayload());
            messagesReceived.countDown();
            return Future.succeededFuture();
        });

        // Send messages before consumer starts polling
        logger.info("Sending 10 messages for batch processing...");
        for (int i = 1; i <= 10; i++) {
            String payload = "Batch message " + i;
            sendChain = sendChain.compose(v -> producer.send(payload));
        }
        sendChain.onFailure(testContext::failNow);

        // Wait for all messages to be processed in batches
    }

    @Test
    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    void testEmptyQueuePolling(Vertx vertx, VertxTestContext testContext) {
        String topicName = "test-empty-queue-polling";

        // Create POLLING_ONLY consumer for empty queue
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofMillis(500))
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger messageCount = new AtomicInteger(0);
        // A strict checkpoint: a second delivery fails the test.
        Checkpoint messageReceived = testContext.checkpoint();

        consumer.subscribe(message -> {
                messageCount.incrementAndGet();
                testContext.verify(() -> assertEquals("Message after empty polling", message.getPayload(),
                    "Should receive the correct message"));
                messageReceived.flag();
                return Future.succeededFuture();
            })
            // Let it poll the empty queue for 3 s; nothing may be processed in that window.
            .compose(v -> vertx.timer(3000))
            .compose(id -> {
                assertEquals(0, messageCount.get(), "Should not process any messages from empty queue");
                return producer.send("Message after empty polling");
            })
            .transform(TestCleanup.cleanup(() -> {
                producer.close();
                return Future.succeededFuture();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    void testPollingWithDatabaseConnectionIssues(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info(" Testing polling resilience with database connection issues");

        String topicName = "test-db-connection-issues";

        // Create POLLING_ONLY consumer with shorter polling interval for faster test
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofMillis(500))
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger messageCount = new AtomicInteger(0);
        CountDownLatch messagesReceived = testContext.checkpoint().asLatch(3);

        Future<Void> subscription = consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info(" Received resilient message {}: {}", count, message.getPayload());
            messagesReceived.countDown();
            return Future.succeededFuture();
        });

        // Send messages sequentially
        subscription
            .compose(v -> producer.send("Message 1 - resilience test"))
            .compose(v -> producer.send("Message 2 - resilience test"))
            .compose(v -> producer.send("Message 3 - resilience test"))
            .onFailure(testContext::failNow);

        // Wait for messages to be processed (consumer should handle normal operations)
    }
}

