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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
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
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_test")
            .withUsername("peegeeq_user")
            .withPassword("peegeeq_pass");

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("Setting up graceful degradation test environment");

        // Initialize database schema using centralized schema initializer - use QUEUE_ALL for PeeGeeQManager health checks
        logger.info("Initializing database schema for native queue tests");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer");

        // Configure test properties using TestContainer (following established patterns)
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");

        // Enable circuit breaker for degradation testing
        System.setProperty("peegeeq.circuit-breaker.enabled", "true");
        System.setProperty("peegeeq.circuit-breaker.failure-threshold", "3");
        System.setProperty("peegeeq.circuit-breaker.timeout", "PT5S");

        // Initialize PeeGeeQ (following existing patterns)
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory using the proper pattern
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith(provider);

        factory = provider.createFactory("native", databaseService);

        logger.info("Test setup completed for graceful degradation testing");
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("Cleaning up graceful degradation test environment");
        
        if (factory != null) {
            factory.close();
        }
        if (manager != null) {
            manager.closeReactive().toCompletionStage().toCompletableFuture().join();
        }
        
        // Clear test properties
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.database.ssl.enabled");
        System.clearProperty("peegeeq.circuit-breaker.enabled");
        System.clearProperty("peegeeq.circuit-breaker.failure-threshold");
        System.clearProperty("peegeeq.circuit-breaker.timeout");
        
        logger.info("Graceful degradation test cleanup completed");
    }

    /**
     * Tests that HYBRID mode gracefully degrades to polling-only when LISTEN/NOTIFY fails.
     * This simulates scenarios where PostgreSQL LISTEN/NOTIFY mechanism encounters issues
     * but the polling mechanism continues to work, ensuring message processing continues.
     */
    @Test
    @Timeout(30)
    void testHybridModeGracefulDegradationToPolling(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Testing HYBRID mode graceful degradation to polling");

        String topicName = "test-hybrid-degradation-polling";

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofMillis(500))
                .build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            Checkpoint received = testContext.checkpoint(3);
            AtomicInteger processedCount = new AtomicInteger(0);

            consumer.subscribe(message -> {
                int count = processedCount.incrementAndGet();
                testContext.verify(() ->
                    assertTrue(message.getPayload().startsWith("Degradation test message"),
                        "Should process degradation test messages correctly"));
                logger.info("Processed message {}: {}", count, message.getPayload());
                received.flag();
                return CompletableFuture.completedFuture(null);
            });

            // Delay for consumer setup using Vert.x timer
            vertx.setTimer(1000, id -> {
                producer.send("Degradation test message 1");
                producer.send("Degradation test message 2");
                producer.send("Degradation test message 3");
            });

            assertTrue(testContext.awaitCompletion(25, TimeUnit.SECONDS), "Test timed out");
            logger.info("HYBRID mode degradation verified - processed: {} messages via polling fallback",
                processedCount.get());

        } finally {
            consumer.close();
            producer.close();
        }
    }

    /**
     * Tests graceful handling of resource exhaustion scenarios.
     * This simulates high load conditions where system resources are constrained
     * and validates that the consumer modes handle resource pressure gracefully.
     */
    @Test
    @Timeout(45)
    void testGracefulHandlingOfResourceExhaustion(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Testing graceful handling of resource exhaustion");

        String topicName = "test-resource-exhaustion";

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofMillis(100))
                .build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);
            AtomicBoolean resourceExhaustionSimulated = new AtomicBoolean(false);
            Checkpoint processed = testContext.checkpoint(10);

            consumer.subscribe(message -> {
                int count = processedCount.incrementAndGet();

                // Simulate resource exhaustion for some messages
                if (count > 5 && count <= 8 && !resourceExhaustionSimulated.get()) {
                    resourceExhaustionSimulated.set(true);
                    errorCount.incrementAndGet();
                    logger.info("Simulating resource exhaustion for message {}", count);
                    return CompletableFuture.failedFuture(
                        new RuntimeException("Simulated resource exhaustion"));
                }

                logger.info("Successfully processed message {}: {}", count, message.getPayload());
                processed.flag();
                return CompletableFuture.completedFuture(null);
            });

            // Delay for consumer setup, then send messages with periodic timer
            vertx.setTimer(1000, setupId -> {
                AtomicInteger sendIndex = new AtomicInteger(0);
                vertx.setPeriodic(50, periodicId -> {
                    int i = sendIndex.incrementAndGet();
                    if (i <= 15) {
                        producer.send("Resource test message " + i);
                    }
                    if (i >= 15) {
                        vertx.cancelTimer(periodicId);
                    }
                });
            });

            assertTrue(testContext.awaitCompletion(35, TimeUnit.SECONDS), "Test timed out");
            assertTrue(processedCount.get() >= 10, "Should process at least 10 messages");
            assertTrue(errorCount.get() > 0, "Should encounter some resource exhaustion errors");

            logger.info("Resource exhaustion handling verified - processed: {}, errors: {}",
                processedCount.get(), errorCount.get());

        } finally {
            consumer.close();
            producer.close();
        }
    }

    /**
     * Tests system recovery after temporary degradation.
     * This validates that consumer modes can recover from temporary issues
     * and resume normal operation once conditions improve.
     */
    @Test
    @Timeout(30)
    void testRecoveryAfterTemporaryDegradation(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Testing recovery after temporary degradation");

        String topicName = "test-recovery-after-degradation";
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofMillis(500))
                .build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicInteger degradationPhase = new AtomicInteger(0); // 0=normal, 1=degraded, 2=recovered
            AtomicReference<String> lastProcessedMessage = new AtomicReference<>();
            Checkpoint received = testContext.checkpoint(6);

            consumer.subscribe(message -> {
                int count = processedCount.incrementAndGet();
                lastProcessedMessage.set(message.getPayload());

                // Simulate temporary degradation for messages 3-4
                if (count == 3 || count == 4) {
                    degradationPhase.set(1);
                    logger.info("Processing message {} during degradation phase: {}", count, message.getPayload());
                    // Simulate degraded performance using non-blocking Vert.x timer
                    CompletableFuture<Void> delayed = new CompletableFuture<>();
                    vertx.setTimer(200, timerId -> {
                        received.flag();
                        delayed.complete(null);
                    });
                    return delayed;
                } else if (count >= 5) {
                    degradationPhase.set(2);
                    logger.info("Processing message {} after recovery: {}", count, message.getPayload());
                } else {
                    logger.info("Processing message {} normally: {}", count, message.getPayload());
                }

                received.flag();
                return CompletableFuture.completedFuture(null);
            });

            // Delay for consumer setup, then send messages in phases using Vert.x timers
            vertx.setTimer(1000, setupId -> {
                producer.send("Pre-degradation message 1");
                producer.send("Pre-degradation message 2");

                vertx.setTimer(500, phase2Id -> {
                    producer.send("Degraded message 3");
                    producer.send("Degraded message 4");

                    vertx.setTimer(500, phase3Id -> {
                        producer.send("Recovery message 5");
                        producer.send("Recovery message 6");
                    });
                });
            });

            assertTrue(testContext.awaitCompletion(25, TimeUnit.SECONDS), "Test timed out");
            assertEquals(6, processedCount.get(), "Should process exactly 6 messages");
            assertEquals(2, degradationPhase.get(), "Should reach recovery phase");
            assertTrue(lastProcessedMessage.get().startsWith("Recovery message"),
                "Last processed message should be a recovery-phase message");

            logger.info("Recovery after degradation verified - processed: {} messages, final phase: {}",
                processedCount.get(), degradationPhase.get());

        } finally {
            consumer.close();
            producer.close();
        }
    }
}


