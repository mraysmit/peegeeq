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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property integration tests for consumer mode implementation.
 * Tests that consumer modes work correctly with various property file configurations.
 * Validates that system properties and configuration files properly influence consumer behavior.
 *
 * Following established coding principles:
 * - Use real infrastructure (TestContainers) rather than mocks
 * - Test property integration scenarios that affect consumer mode behavior
 * - Validate that configuration changes properly influence system behavior
 * - Follow existing patterns from other integration tests
 * - Test various property combinations and edge cases
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class ConsumerModePropertyIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerModePropertyIntegrationTest.class);

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Ensure required schema exists for native queue tests
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.NATIVE_QUEUE, SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);

        logger.info("Test setup completed for consumer mode property integration testing");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        if (factory != null) {
            factory.close();
        }
        if (manager != null) {
            manager.closeReactive()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }

        logger.info("Test teardown completed");
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    private Future<Void> initializeManagerAndFactory(Properties testProps) {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        return manager.start().map(v -> {
            PgDatabaseService databaseService = new PgDatabaseService(manager);
            PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
            PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
            factory = provider.createFactory("native", databaseService);
            return (Void) null;
        });
    }

    @Test
    void testPollingIntervalPropertyIntegration(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing polling interval property integration");

        String topicName = "test-polling-interval-property";
        AtomicInteger processedCount = new AtomicInteger(0);
        Checkpoint messagesReceived = testContext.checkpoint(2);
        AtomicReference<MessageConsumer<String>> consumerRef = new AtomicReference<>();
        AtomicReference<MessageProducer<String>> producerRef = new AtomicReference<>();

        initializeManagerAndFactory(PeeGeeQTestConfig.builder()
                .from(postgres)
                .property("peegeeq.metrics.enabled", "true")
                .property("peegeeq.circuit-breaker.enabled", "true")
                .property("peegeeq.queue.polling-interval", "PT2S")
                .property("peegeeq.queue.visibility-timeout", "PT30S")
                .build())
            .onSuccess(v -> {
                MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
                    ConsumerConfig.builder()
                        .mode(ConsumerMode.POLLING_ONLY)
                        .pollingInterval(Duration.ofSeconds(2))
                        .build());
                MessageProducer<String> producer = factory.createProducer(topicName, String.class);
                consumerRef.set(consumer);
                producerRef.set(producer);

                consumer.subscribe(message -> {
                    processedCount.incrementAndGet();
                    logger.info("📨 Property integration processed: {}", message.getPayload());
                    messagesReceived.flag();
                    return Future.succeededFuture();
                })
                .onSuccess(ignored -> producer.send("Property test message 1")
                        .compose(vv -> producer.send("Property test message 2"))
                        .onFailure(testContext::failNow))
                .onFailure(testContext::failNow);
            })
            .onFailure(testContext::failNow);

        try {
            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should process messages with custom polling interval");
            assertEquals(2, processedCount.get(), "Should process exactly 2 messages");
            logger.info("Polling interval property integration verified - processed: {} messages", processedCount.get());
        } finally {
            if (consumerRef.get() != null) consumerRef.get().close();
            if (producerRef.get() != null) producerRef.get().close();
        }

        logger.info("Polling interval property integration test completed successfully");
    }

    @Test
    void testBatchSizePropertyIntegration(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing batch size property integration");

        String topicName = "test-batch-size-property";
        AtomicInteger processedCount = new AtomicInteger(0);
        Checkpoint messagesReceived = testContext.checkpoint(5);
        AtomicReference<MessageConsumer<String>> consumerRef = new AtomicReference<>();
        AtomicReference<MessageProducer<String>> producerRef = new AtomicReference<>();

        initializeManagerAndFactory(PeeGeeQTestConfig.builder()
                .from(postgres)
                .property("peegeeq.metrics.enabled", "true")
                .property("peegeeq.circuit-breaker.enabled", "true")
                .property("peegeeq.queue.polling-interval", "PT1S")
                .property("peegeeq.queue.visibility-timeout", "PT30S")
                .build())
            .onSuccess(v -> {
                MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
                    ConsumerConfig.builder()
                        .mode(ConsumerMode.POLLING_ONLY)
                        .pollingInterval(Duration.ofSeconds(1))
                        .batchSize(3)
                        .build());
                MessageProducer<String> producer = factory.createProducer(topicName, String.class);
                consumerRef.set(consumer);
                producerRef.set(producer);

                consumer.subscribe(message -> {
                    processedCount.incrementAndGet();
                    logger.info("📨 Batch property processed: {}", message.getPayload());
                    messagesReceived.flag();
                    return Future.succeededFuture();
                })
                .onSuccess(ignored -> {
                    Future<Void> chain = Future.succeededFuture();
                    for (int i = 1; i <= 5; i++) {
                        final int idx = i;
                        chain = chain.compose(vv -> producer.send("Batch message " + idx));
                    }
                    chain.onFailure(testContext::failNow);
                })
                .onFailure(testContext::failNow);
            })
            .onFailure(testContext::failNow);

        try {
            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Should process messages with custom batch size");
            assertEquals(5, processedCount.get(), "Should process exactly 5 messages");
            logger.info("Batch size property integration verified - processed: {} messages", processedCount.get());
        } finally {
            if (consumerRef.get() != null) consumerRef.get().close();
            if (producerRef.get() != null) producerRef.get().close();
        }

        logger.info("Batch size property integration test completed successfully");
    }

    @Test
    void testVisibilityTimeoutPropertyIntegration(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing visibility timeout property integration");

        String topicName = "test-visibility-timeout-property";
        AtomicInteger processedCount = new AtomicInteger(0);
        Checkpoint messagesReceived = testContext.checkpoint(2);
        AtomicReference<MessageConsumer<String>> consumerRef = new AtomicReference<>();
        AtomicReference<MessageProducer<String>> producerRef = new AtomicReference<>();

        initializeManagerAndFactory(PeeGeeQTestConfig.builder()
                .from(postgres)
                .property("peegeeq.metrics.enabled", "true")
                .property("peegeeq.circuit-breaker.enabled", "true")
                .property("peegeeq.queue.polling-interval", "PT1S")
                .property("peegeeq.queue.visibility-timeout", "PT10S")
                .build())
            .onSuccess(v -> {
                MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
                    ConsumerConfig.builder()
                        .mode(ConsumerMode.HYBRID)
                        .pollingInterval(Duration.ofSeconds(1))
                        .build());
                MessageProducer<String> producer = factory.createProducer(topicName, String.class);
                consumerRef.set(consumer);
                producerRef.set(producer);

                consumer.subscribe(message -> {
                    processedCount.incrementAndGet();
                    logger.info("📨 Visibility timeout processed: {}", message.getPayload());
                    messagesReceived.flag();
                    return Future.succeededFuture();
                })
                .onSuccess(ignored -> producer.send("Visibility test message 1")
                        .compose(vv -> producer.send("Visibility test message 2"))
                        .onFailure(testContext::failNow))
                .onFailure(testContext::failNow);
            })
            .onFailure(testContext::failNow);

        try {
            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Should process messages with custom visibility timeout");
            assertEquals(2, processedCount.get(), "Should process exactly 2 messages");
            logger.info("Visibility timeout property integration verified - processed: {} messages", processedCount.get());
        } finally {
            if (consumerRef.get() != null) consumerRef.get().close();
            if (producerRef.get() != null) producerRef.get().close();
        }

        logger.info("Visibility timeout property integration test completed successfully");
    }

    @Test
    void testMultiplePropertyCombinations(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing multiple property combinations");

        String topicName = "test-multiple-properties";
        AtomicInteger processedCount = new AtomicInteger(0);
        Checkpoint messagesReceived = testContext.checkpoint(4);
        AtomicReference<MessageConsumer<String>> consumerRef = new AtomicReference<>();
        AtomicReference<MessageProducer<String>> producerRef = new AtomicReference<>();

        initializeManagerAndFactory(PeeGeeQTestConfig.builder()
                .from(postgres)
                .property("peegeeq.metrics.enabled", "true")
                .property("peegeeq.circuit-breaker.enabled", "true")
                .property("peegeeq.queue.polling-interval", "PT0.5S")
                .property("peegeeq.queue.visibility-timeout", "PT15S")
                .build())
            .onSuccess(v -> {
                MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
                    ConsumerConfig.builder()
                        .mode(ConsumerMode.HYBRID)
                        .pollingInterval(Duration.ofMillis(500))
                        .batchSize(2)
                        .build());
                MessageProducer<String> producer = factory.createProducer(topicName, String.class);
                consumerRef.set(consumer);
                producerRef.set(producer);

                consumer.subscribe(message -> {
                    processedCount.incrementAndGet();
                    logger.info("📨 Multiple properties processed: {}", message.getPayload());
                    messagesReceived.flag();
                    return Future.succeededFuture();
                })
                .onSuccess(ignored -> {
                    Future<Void> chain = Future.succeededFuture();
                    for (int i = 1; i <= 4; i++) {
                        final int idx = i;
                        chain = chain.compose(vv -> producer.send("Multi-property message " + idx));
                    }
                    chain.onFailure(testContext::failNow);
                })
                .onFailure(testContext::failNow);
            })
            .onFailure(testContext::failNow);

        try {
            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Should process messages with multiple property combinations");
            assertEquals(4, processedCount.get(), "Should process exactly 4 messages");
            logger.info("Multiple property combinations verified - processed: {} messages", processedCount.get());
        } finally {
            if (consumerRef.get() != null) consumerRef.get().close();
            if (producerRef.get() != null) producerRef.get().close();
        }

        logger.info("Multiple property combinations test completed successfully");
    }

    @Test
    void testPropertyOverrideScenarios(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing property override scenarios");

        String topicName = "test-property-override";
        AtomicInteger processedCount = new AtomicInteger(0);
        Checkpoint messagesReceived = testContext.checkpoint(3);
        AtomicReference<MessageConsumer<String>> consumerRef = new AtomicReference<>();
        AtomicReference<MessageProducer<String>> producerRef = new AtomicReference<>();

        initializeManagerAndFactory(PeeGeeQTestConfig.builder()
                .from(postgres)
                .property("peegeeq.metrics.enabled", "true")
                .property("peegeeq.circuit-breaker.enabled", "true")
                .property("peegeeq.queue.polling-interval", "PT3S")
                .property("peegeeq.queue.visibility-timeout", "PT30S")
                .build())
            .onSuccess(v -> {
                MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
                    ConsumerConfig.builder()
                        .mode(ConsumerMode.POLLING_ONLY)
                        .pollingInterval(Duration.ofSeconds(1))
                        .build());
                MessageProducer<String> producer = factory.createProducer(topicName, String.class);
                consumerRef.set(consumer);
                producerRef.set(producer);

                consumer.subscribe(message -> {
                    processedCount.incrementAndGet();
                    logger.info("📨 Property override processed: {}", message.getPayload());
                    messagesReceived.flag();
                    return Future.succeededFuture();
                })
                .onSuccess(ignored -> {
                    Future<Void> chain = Future.succeededFuture();
                    for (int i = 1; i <= 3; i++) {
                        final int idx = i;
                        chain = chain.compose(vv -> producer.send("Override message " + idx));
                    }
                    chain.onFailure(testContext::failNow);
                })
                .onFailure(testContext::failNow);
            })
            .onFailure(testContext::failNow);

        try {
            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should process messages with overridden properties");
            assertEquals(3, processedCount.get(), "Should process exactly 3 messages");
            logger.info("Property override scenarios verified - processed: {} messages", processedCount.get());
        } finally {
            if (consumerRef.get() != null) consumerRef.get().close();
            if (producerRef.get() != null) producerRef.get().close();
        }

        logger.info("Property override scenarios test completed successfully");
    }
}


