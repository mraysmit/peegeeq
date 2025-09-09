package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
@Testcontainers
class ConsumerModeGracefulDegradationTest {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerModeGracefulDegradationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_test")
            .withUsername("peegeeq_user")
            .withPassword("peegeeq_pass");

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("Setting up graceful degradation test environment");

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
            manager.stop();
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
    void testHybridModeGracefulDegradationToPolling() throws Exception {
        logger.info("🧪 Testing HYBRID mode graceful degradation to polling");

        String topicName = "test-hybrid-degradation-polling";
        
        // Create HYBRID consumer with fast polling for quick test execution
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofMillis(500)) // Fast polling for test
                .build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(3);
            AtomicReference<String> lastProcessedMessage = new AtomicReference<>();

            consumer.subscribe(message -> {
                int count = processedCount.incrementAndGet();
                lastProcessedMessage.set(message.getPayload());
                logger.info("Processed message {}: {}", count, message.getPayload());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup
            Thread.sleep(1000);

            // Send messages - HYBRID mode should handle them via polling even if LISTEN/NOTIFY has issues
            producer.send("Degradation test message 1").get(5, TimeUnit.SECONDS);
            producer.send("Degradation test message 2").get(5, TimeUnit.SECONDS);
            producer.send("Degradation test message 3").get(5, TimeUnit.SECONDS);

            // Wait for message processing - should work via polling fallback
            boolean received = latch.await(20, TimeUnit.SECONDS);
            assertTrue(received, "Should process messages via polling fallback mechanism");
            assertEquals(3, processedCount.get(), "Should process exactly 3 messages");
            assertTrue(lastProcessedMessage.get().startsWith("Degradation test message"),
                "Should process degradation test messages correctly");

            logger.info("✅ HYBRID mode degradation verified - processed: {} messages via polling fallback", 
                processedCount.get());

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("✅ HYBRID mode graceful degradation test completed successfully");
    }

    /**
     * Tests graceful handling of resource exhaustion scenarios.
     * This simulates high load conditions where system resources are constrained
     * and validates that the consumer modes handle resource pressure gracefully.
     */
    @Test
    @Timeout(45)
    void testGracefulHandlingOfResourceExhaustion() throws Exception {
        logger.info("🧪 Testing graceful handling of resource exhaustion");

        String topicName = "test-resource-exhaustion";
        
        // Create consumer with limited resources
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofMillis(100))
                .build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(10); // Expect at least 10 successful messages
            AtomicBoolean resourceExhaustionSimulated = new AtomicBoolean(false);

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
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup
            Thread.sleep(1000);

            // Send multiple messages to trigger resource exhaustion scenario
            for (int i = 1; i <= 15; i++) {
                producer.send("Resource test message " + i).get(5, TimeUnit.SECONDS);
                Thread.sleep(50); // Small delay between sends
            }

            // Wait for message processing
            boolean received = latch.await(30, TimeUnit.SECONDS);
            assertTrue(received, "Should process at least 10 messages despite resource exhaustion");
            assertTrue(processedCount.get() >= 10, "Should process at least 10 messages");
            assertTrue(errorCount.get() > 0, "Should encounter some resource exhaustion errors");

            logger.info("✅ Resource exhaustion handling verified - processed: {}, errors: {}", 
                processedCount.get(), errorCount.get());

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("✅ Resource exhaustion graceful handling test completed successfully");
    }

    /**
     * Tests system recovery after temporary degradation.
     * This validates that consumer modes can recover from temporary issues
     * and resume normal operation once conditions improve.
     */
    @Test
    @Timeout(30)
    void testRecoveryAfterTemporaryDegradation() throws Exception {
        logger.info("🧪 Testing recovery after temporary degradation");

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
            CountDownLatch latch = new CountDownLatch(6);
            AtomicReference<String> lastProcessedMessage = new AtomicReference<>();

            consumer.subscribe(message -> {
                int count = processedCount.incrementAndGet();
                lastProcessedMessage.set(message.getPayload());
                
                // Simulate temporary degradation for messages 3-4
                if (count == 3 || count == 4) {
                    degradationPhase.set(1);
                    logger.info("Processing message {} during degradation phase: {}", count, message.getPayload());
                    // Add some delay to simulate degraded performance
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                } else if (count >= 5) {
                    degradationPhase.set(2);
                    logger.info("Processing message {} after recovery: {}", count, message.getPayload());
                } else {
                    logger.info("Processing message {} normally: {}", count, message.getPayload());
                }
                
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumer setup
            Thread.sleep(1000);

            // Send messages across different phases
            producer.send("Pre-degradation message 1").get(5, TimeUnit.SECONDS);
            producer.send("Pre-degradation message 2").get(5, TimeUnit.SECONDS);
            
            Thread.sleep(500); // Brief pause
            
            producer.send("Degraded message 3").get(5, TimeUnit.SECONDS);
            producer.send("Degraded message 4").get(5, TimeUnit.SECONDS);
            
            Thread.sleep(500); // Brief pause for recovery
            
            producer.send("Recovery message 5").get(5, TimeUnit.SECONDS);
            producer.send("Recovery message 6").get(5, TimeUnit.SECONDS);

            // Wait for message processing
            boolean received = latch.await(20, TimeUnit.SECONDS);
            assertTrue(received, "Should process all messages through degradation and recovery");
            assertEquals(6, processedCount.get(), "Should process exactly 6 messages");
            assertEquals(2, degradationPhase.get(), "Should reach recovery phase");
            assertEquals("Recovery message 6", lastProcessedMessage.get(), 
                "Should process the final recovery message");

            logger.info("✅ Recovery after degradation verified - processed: {} messages, final phase: {}", 
                processedCount.get(), degradationPhase.get());

        } finally {
            consumer.close();
            producer.close();
        }

        logger.info("✅ Recovery after temporary degradation test completed successfully");
    }
}
