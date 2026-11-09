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
import io.vertx.core.Vertx;
import io.vertx.core.Promise;
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


import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Future;

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
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
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
        manager.start().onSuccess(v -> {
            // Create factory using the proper pattern
            PgDatabaseService databaseService = new PgDatabaseService(manager);
            PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

            // Register native factory implementation
            PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

            factory = provider.createFactory("native", databaseService);

            logger.info("Test setup completed for consumer mode backward compatibility testing");
            testContext.completeNow();
        })
        .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        closeResources()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    private Future<Void> closeResources() {
        Future<Void> factoryClose = factory != null ? factory.close() : Future.succeededFuture();
        return factoryClose.transform(factoryResult -> {
            Future<Void> managerClose = manager != null ? manager.closeReactive() : Future.succeededFuture();
            return managerClose.transform(managerResult -> combinedCloseResult(factoryResult.cause(), managerResult.cause()));
        });
    }

    private Future<Void> combinedCloseResult(Throwable factoryFailure, Throwable managerFailure) {
        if (factoryFailure != null) {
            if (managerFailure != null) {
                factoryFailure.addSuppressed(managerFailure);
            }
            return Future.failedFuture(factoryFailure);
        }
        return managerFailure == null ? Future.succeededFuture() : Future.failedFuture(managerFailure);
    }

    @Test
    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    void testLegacyApiWithoutConsumerConfig(Vertx vertx, VertxTestContext testContext) {
        String topicName = "test-legacy-api";

        // Use old API without ConsumerConfig - should default to HYBRID mode
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        // A strict checkpoint: exactly three deliveries.
        CountDownLatch messagesReceived = testContext.checkpoint().asLatch(3);

        consumer.subscribe(message -> {
                messagesReceived.countDown();
                return Future.succeededFuture();
            })
            .compose(ignored -> producer.send("Legacy message 1"))
            .compose(v -> producer.send("Legacy message 2"))
            .compose(v -> producer.send("Legacy message 3"))
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    void testMixedApiUsage(Vertx vertx, VertxTestContext testContext) {
        String legacyTopic = "test-mixed-legacy";
        String newTopic = "test-mixed-new";

        // Create one consumer with legacy API (no ConsumerConfig)
        MessageConsumer<String> legacyConsumer = factory.createConsumer(legacyTopic, String.class);

        // Create another consumer with new API (with ConsumerConfig)
        MessageConsumer<String> newConsumer = factory.createConsumer(newTopic, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.LISTEN_NOTIFY_ONLY).build());

        MessageProducer<String> legacyProducer = factory.createProducer(legacyTopic, String.class);
        MessageProducer<String> newProducer = factory.createProducer(newTopic, String.class);

        // One strict two-pass checkpoint per consumer: each processes exactly its two messages.
        CountDownLatch legacyReceived = testContext.checkpoint().asLatch(2);
        CountDownLatch newApiReceived = testContext.checkpoint().asLatch(2);

        Future.all(
                legacyConsumer.subscribe(message -> {
                    legacyReceived.countDown();
                    return Future.succeededFuture();
                }),
                newConsumer.subscribe(message -> {
                    newApiReceived.countDown();
                    return Future.succeededFuture();
                }))
            .compose(ignored -> legacyProducer.send("Mixed legacy message 1"))
            .compose(v -> newProducer.send("Mixed new message 1"))
            .compose(v -> legacyProducer.send("Mixed legacy message 2"))
            .compose(v -> newProducer.send("Mixed new message 2"))
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    void testLegacyApiDefaultBehavior(Vertx vertx, VertxTestContext testContext) {
        String legacyTopic = "test-legacy-default";
        String hybridTopic = "test-hybrid-explicit";

        // Create consumer with legacy API (should default to HYBRID)
        MessageConsumer<String> legacyConsumer = factory.createConsumer(legacyTopic, String.class);

        // Create consumer with explicit HYBRID mode
        MessageConsumer<String> hybridConsumer = factory.createConsumer(hybridTopic, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).build());

        MessageProducer<String> legacyProducer = factory.createProducer(legacyTopic, String.class);
        MessageProducer<String> hybridProducer = factory.createProducer(hybridTopic, String.class);

        // One strict two-pass checkpoint per consumer: each processes exactly its two messages.
        CountDownLatch legacyReceived = testContext.checkpoint().asLatch(2);
        CountDownLatch hybridReceived = testContext.checkpoint().asLatch(2);

        Future.all(
                legacyConsumer.subscribe(message -> {
                    legacyReceived.countDown();
                    return Future.succeededFuture();
                }),
                hybridConsumer.subscribe(message -> {
                    hybridReceived.countDown();
                    return Future.succeededFuture();
                }))
            .compose(ignored -> legacyProducer.send("Legacy default message 1"))
            .compose(v -> hybridProducer.send("Explicit hybrid message 1"))
            .compose(v -> legacyProducer.send("Legacy default message 2"))
            .compose(v -> hybridProducer.send("Explicit hybrid message 2"))
            .onFailure(testContext::failNow);
    }

    @Test
    void testGradualMigrationPath(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info(" Testing gradual migration path from legacy to new API");

        String topicName = "test-gradual-migration";

        // Start with legacy API
        MessageConsumer<String> legacyConsumer = factory.createConsumer(topicName, String.class);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger totalProcessed = new AtomicInteger(0);
        AtomicInteger legacyProcessed = new AtomicInteger(0);
        AtomicInteger migratedProcessed = new AtomicInteger(0);
        Promise<Void> legacyPhaseComplete = Promise.promise();
        Promise<Void> migratedPhaseComplete = Promise.promise();

        legacyConsumer.subscribe(message -> {
            totalProcessed.incrementAndGet();
            logger.info(" Legacy migration processed: {}", message.getPayload());
            if (legacyProcessed.incrementAndGet() == 2) {
                legacyPhaseComplete.tryComplete();
            }
            return Future.succeededFuture();
        })
        .compose(v -> producer.send("Migration message 1"))
        .compose(v -> producer.send("Migration message 2"))
        .compose(v -> legacyPhaseComplete.future())
        .compose(v -> {
            legacyConsumer.close();
            MessageConsumer<String> newConsumer = factory.createConsumer(topicName, String.class,
                ConsumerConfig.builder().mode(ConsumerMode.HYBRID).build());
            return newConsumer.subscribe(message -> {
                totalProcessed.incrementAndGet();
                logger.info(" New API migration processed: {}", message.getPayload());
                if (migratedProcessed.incrementAndGet() == 2) {
                    migratedPhaseComplete.tryComplete();
                }
                return Future.succeededFuture();
            });
        })
        .compose(v -> producer.send("Migration message 3"))
        .compose(v -> producer.send("Migration message 4"))
        .compose(v -> migratedPhaseComplete.future())
        .onSuccess(v -> testContext.verify(() -> {
            assertEquals(4, totalProcessed.get(), "Should process all 4 messages during migration");
            logger.info("Gradual migration verified - total processed: {}", totalProcessed.get());
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS),
            "Both migration phases should complete");

        logger.info("Gradual migration path test completed successfully");
    }

    @Test
    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    void testLegacyApiPerformanceConsistency(Vertx vertx, VertxTestContext testContext) {
        String topicName = "test-legacy-performance";

        // Use legacy API for performance test
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger processedCount = new AtomicInteger(0);
        Promise<Void> allProcessed = Promise.promise();
        long startTime = System.currentTimeMillis();

        consumer.subscribe(message -> {
                if (processedCount.incrementAndGet() == 5) {
                    allProcessed.tryComplete();
                }
                return Future.succeededFuture();
            })
            .compose(ignored -> {
                Future<Void> chain = Future.succeededFuture();
                for (int i = 1; i <= 5; i++) {
                    final int msgNum = i;
                    chain = chain.compose(v -> producer.send("Performance message " + msgNum));
                }
                return chain;
            })
            .compose(sent -> allProcessed.future())
            .onSuccess(done -> testContext.verify(() -> {
                long duration = System.currentTimeMillis() - startTime;
                assertEquals(5, processedCount.get(), "Should process exactly 5 messages");
                assertTrue(duration < 10000, "Processing should complete within reasonable time (10s), took " + duration + " ms");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
}
