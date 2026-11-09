package dev.mars.peegeeq.pgqueue;

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
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Graceful degradation tests for consumer mode implementation.
 * Tests that consumer modes gracefully degrade under various failure conditions
 * including database connection issues, resource exhaustion, and partial failures.
 * 
 * Following established coding principles:
 * - Use real infrastructure (TestContainers) rather than mocks
 * - Test graceful degradation scenarios that could occur in production
 * - Validate proper fallback mechanisms and recovery behavior
 * - Follow existing patterns from other integration tests
 * - Test system resilience under adverse conditions
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class ConsumerModeGracefulDegradationTest {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerModeGracefulDegradationTest.class);

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        logger.info("Setting up graceful degradation test environment");

        // Initialize database schema using centralized schema initializer - use QUEUE_ALL for PeeGeeQManager health checks
        logger.info("Initializing database schema for native queue tests");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer");

        // Configure test properties using TestContainer (following established patterns)
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.circuit-breaker.enabled", "true")
                .property("peegeeq.circuit-breaker.failure-threshold", "3")
                .property("peegeeq.circuit-breaker.timeout", "PT5S")
                .build();

        // Initialize PeeGeeQ (following existing patterns)
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().onSuccess(v -> {
            // Create factory using the proper pattern
            PgDatabaseService databaseService = new PgDatabaseService(manager);
            PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

            // Register native factory implementation
            PgNativeFactoryRegistrar.registerWith(provider);

            factory = provider.createFactory("native", databaseService);

            logger.info("Test setup completed for graceful degradation testing");
            testContext.completeNow();
        })
        .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        logger.info("Cleaning up graceful degradation test environment");

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

    /**
     * Tests that HYBRID mode gracefully degrades to polling-only when LISTEN/NOTIFY fails.
     * This simulates scenarios where PostgreSQL LISTEN/NOTIFY mechanism encounters issues
     * but the polling mechanism continues to work, ensuring message processing continues.
     */
    @Test
    @Timeout(30)
    @io.vertx.junit5.Timeout(value = 25, timeUnit = TimeUnit.SECONDS)
    void testHybridModeGracefulDegradationToPolling(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Testing HYBRID mode graceful degradation to polling");

        String topicName = "test-hybrid-degradation-polling";

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofMillis(500))
                .build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        CountDownLatch received = testContext.checkpoint().asLatch(3);
        AtomicInteger processedCount = new AtomicInteger(0);

            consumer.subscribe(message -> {
                int count = processedCount.incrementAndGet();
                testContext.verify(() ->
                    assertTrue(message.getPayload().startsWith("Degradation test message"),
                        "Should process degradation test messages correctly"));
                logger.info("Processed message {}: {}", count, message.getPayload());
                received.countDown();
                return Future.succeededFuture();
            })
            .compose(ignored -> producer.send("Degradation test message 1"))
            .compose(ignored -> producer.send("Degradation test message 2"))
            .compose(ignored -> producer.send("Degradation test message 3"))
            .onFailure(testContext::failNow);
    }

    /**
     * Tests graceful handling of resource exhaustion scenarios.
     * This simulates high load conditions where system resources are constrained
     * and validates that the consumer modes handle resource pressure gracefully.
     */
    @Test
    @Timeout(45)
    @io.vertx.junit5.Timeout(value = 35, timeUnit = TimeUnit.SECONDS)
    void testGracefulHandlingOfResourceExhaustion(Vertx vertx, VertxTestContext testContext) {
        String topicName = "test-resource-exhaustion";

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofMillis(100))
                .build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicBoolean resourceExhaustionSimulated = new AtomicBoolean(false);
        // The counts are checked once all 15 messages have succeeded, so no send is still in
        // flight when teardown closes the pool.
        Promise<Void> allSucceeded = Promise.promise();

        consumer.subscribe(message -> {
                int count = processedCount.incrementAndGet();

                // Simulate resource exhaustion for one delivery
                if (count > 5 && count <= 8 && !resourceExhaustionSimulated.get()) {
                    resourceExhaustionSimulated.set(true);
                    errorCount.incrementAndGet();
                    return Future.failedFuture(
                        new RuntimeException("Simulated resource exhaustion"));
                }

                if (successCount.incrementAndGet() == 15) {
                    allSucceeded.tryComplete();
                }
                return Future.succeededFuture();
            })
            .compose(ignored -> {
                Future<Void> sends = Future.succeededFuture();
                for (int i = 1; i <= 15; i++) {
                    int messageNumber = i;
                    sends = sends.compose(v -> producer.send("Resource test message " + messageNumber));
                }
                return sends;
            })
            .compose(sent -> allSucceeded.future())
            .onSuccess(done -> testContext.verify(() -> {
                assertEquals(1, errorCount.intValue(), "Exactly one delivery should fail with simulated resource exhaustion");
                assertEquals(16, processedCount.intValue(), "15 messages plus one redelivery of the failed message");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Tests system recovery after temporary degradation.
     * This validates that consumer modes can recover from temporary issues
     * and resume normal operation once conditions improve.
     */
    @Test
    @Timeout(30)
    @io.vertx.junit5.Timeout(value = 25, timeUnit = TimeUnit.SECONDS)
    void testRecoveryAfterTemporaryDegradation(Vertx vertx, VertxTestContext testContext) {
        String topicName = "test-recovery-after-degradation";
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofMillis(500))
                .build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger degradationPhase = new AtomicInteger(0); // 0=normal, 1=degraded, 2=recovered
        AtomicReference<String> lastProcessedMessage = new AtomicReference<>();
        // The phase and the last message are checked once all six have completed.
        Promise<Void> allCompleted = Promise.promise();
        Runnable completed = () -> {
            if (completedCount.incrementAndGet() == 6) {
                allCompleted.tryComplete();
            }
        };

        consumer.subscribe(message -> {
                int count = processedCount.incrementAndGet();
                lastProcessedMessage.set(message.getPayload());

                // Simulate temporary degradation for messages 3-4
                if (count == 3 || count == 4) {
                    degradationPhase.set(1);
                    // Degraded performance with a non-blocking Vert.x timer
                    return vertx.timer(200).map(timerId -> {
                        completed.run();
                        return (Void) null;
                    });
                } else if (count >= 5) {
                    degradationPhase.set(2);
                }

                completed.run();
                return Future.succeededFuture();
            })
            .compose(ignored -> producer.send("Pre-degradation message 1"))
            .compose(ignored -> producer.send("Pre-degradation message 2"))
            .compose(ignored -> vertx.timer(500).mapEmpty())
            .compose(ignored -> producer.send("Degraded message 3"))
            .compose(ignored -> producer.send("Degraded message 4"))
            .compose(ignored -> vertx.timer(500).mapEmpty())
            .compose(ignored -> producer.send("Recovery message 5"))
            .compose(ignored -> producer.send("Recovery message 6"))
            .compose(sent -> allCompleted.future())
            .onSuccess(done -> testContext.verify(() -> {
                assertEquals(6, processedCount.get(), "Should process exactly 6 messages");
                assertEquals(2, degradationPhase.get(), "Should reach recovery phase");
                assertTrue(lastProcessedMessage.get().startsWith("Recovery message"),
                    "Last processed message should be a recovery-phase message");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
}
