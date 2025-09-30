package dev.mars.peegeeq.examples.springboot.outbox;

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.examples.springboot.SpringBootOutboxApplication;
import dev.mars.peegeeq.outbox.OutboxFactory;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring Boot integration tests for Outbox error handling and resilience patterns.
 * 
 * <p>Demonstrates:
 * <ul>
 *   <li>Transient error recovery with automatic retry</li>
 *   <li>Permanent error handling</li>
 *   <li>Error classification and handling strategies</li>
 *   <li>Graceful degradation under error conditions</li>
 * </ul>
 * 
 * <p>Based on OutboxErrorHandlingTest and EnhancedErrorHandlingExampleTest from peegeeq-outbox module.
 */
@SpringBootTest(
    classes = SpringBootOutboxApplication.class,
    properties = {
        "spring.profiles.active=test",
        "logging.level.dev.mars.peegeeq=INFO",
        "logging.level.dev.mars.peegeeq.examples.springboot=INFO",
        "peegeeq.queue.max-retries=3",
        "peegeeq.queue.polling-interval=PT0.1S"
    }
)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutboxErrorHandlingSpringBootTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxErrorHandlingSpringBootTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("peegeeq_error_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        logger.info("Configuring properties for OutboxErrorHandling test");
        registry.add("peegeeq.db.host", postgres::getHost);
        registry.add("peegeeq.db.port", postgres::getFirstMappedPort);
        registry.add("peegeeq.db.database", postgres::getDatabaseName);
        registry.add("peegeeq.db.username", postgres::getUsername);
        registry.add("peegeeq.db.password", postgres::getPassword);
    }

    @Autowired
    private OutboxFactory outboxFactory;

    private final List<MessageProducer<?>> activeProducers = new ArrayList<>();
    private final List<MessageConsumer<?>> activeConsumers = new ArrayList<>();

    @AfterEach
    void tearDown() throws InterruptedException {
        logger.info("Cleaning up test resources...");
        
        // Close all active consumers first
        for (MessageConsumer<?> consumer : activeConsumers) {
            try {
                consumer.close();
            } catch (Exception e) {
                logger.error("Error closing consumer", e);
            }
        }
        activeConsumers.clear();
        
        // Close all active producers
        for (MessageProducer<?> producer : activeProducers) {
            try {
                producer.close();
            } catch (Exception e) {
                logger.error("Error closing producer", e);
            }
        }
        activeProducers.clear();
        
        // Wait for connections to be released
        Thread.sleep(2000);
    }

    @Test
    @Order(1)
    @DisplayName("Test 1: Transient Error Recovery - Automatic retry succeeds")
    void testTransientErrorRecovery() throws Exception {
        logger.info("\n=== TEST 1: Transient Error Recovery ===");
        
        String topicName = "error-transient-topic";
        
        // Create producer and consumer
        MessageProducer<String> producer = outboxFactory.createProducer(topicName, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(topicName, String.class);
        activeProducers.add(producer);
        activeConsumers.add(consumer);
        
        // Track attempts
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch successLatch = new CountDownLatch(1);
        
        // Set up consumer that fails first 2 times, then succeeds
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("Processing attempt {} for message: {}", attempt, message.getPayload());
            
            if (attempt < 3) {
                // Fail the first 2 attempts (simulating transient error)
                logger.info("INTENTIONAL FAILURE: Simulating transient error on attempt {}", attempt);
                return CompletableFuture.failedFuture(
                    new RuntimeException("Simulated transient error, attempt " + attempt));
            } else {
                // Succeed on the 3rd attempt
                logger.info("SUCCESS: Processing succeeded on attempt {}", attempt);
                successLatch.countDown();
                return CompletableFuture.completedFuture(null);
            }
        });
        
        // Send message
        producer.send("Transient error test message").get(5, TimeUnit.SECONDS);
        
        // Wait for eventual success
        assertTrue(successLatch.await(30, TimeUnit.SECONDS), 
            "Message should eventually succeed after retries");
        
        // Verify retry attempts
        assertTrue(attemptCount.get() >= 3, 
            "Should have at least 3 attempts (was " + attemptCount.get() + ")");
        
        logger.info("✅ Transient error recovery verified - succeeded after {} attempts", 
            attemptCount.get());
    }

    @Test
    @Order(2)
    @DisplayName("Test 2: Permanent Error Handling - Fails after max retries")
    void testPermanentErrorHandling() throws Exception {
        logger.info("\n=== TEST 2: Permanent Error Handling ===");
        
        String topicName = "error-permanent-topic";
        
        // Create producer and consumer
        MessageProducer<String> producer = outboxFactory.createProducer(topicName, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(topicName, String.class);
        activeProducers.add(producer);
        activeConsumers.add(consumer);
        
        // Track attempts
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch attemptLatch = new CountDownLatch(4); // Initial + 3 retries
        
        // Set up consumer that always fails (simulating permanent error)
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Processing attempt {} - permanent error", attempt);
            attemptLatch.countDown();
            return CompletableFuture.failedFuture(
                new RuntimeException("Simulated permanent error"));
        });
        
        // Send message
        producer.send("Permanent error test message").get(5, TimeUnit.SECONDS);
        
        // Wait for all retry attempts
        assertTrue(attemptLatch.await(30, TimeUnit.SECONDS), 
            "Should attempt processing multiple times");
        
        // Verify max retries were attempted
        Thread.sleep(2000); // Allow time for final retry
        int finalAttempts = attemptCount.get();
        assertTrue(finalAttempts >= 4, 
            "Should have at least 4 attempts (initial + 3 retries), was " + finalAttempts);
        
        logger.info("✅ Permanent error handling verified - failed after {} attempts", 
            finalAttempts);
    }

    @Test
    @Order(3)
    @DisplayName("Test 3: Mixed Error Scenarios - Handle both transient and permanent errors")
    void testMixedErrorScenarios() throws Exception {
        logger.info("\n=== TEST 3: Mixed Error Scenarios ===");
        
        String topicName = "error-mixed-topic";
        
        // Create producer and consumer
        MessageProducer<String> producer = outboxFactory.createProducer(topicName, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(topicName, String.class);
        activeProducers.add(producer);
        activeConsumers.add(consumer);
        
        // Track processing
        AtomicInteger transientAttempts = new AtomicInteger(0);
        AtomicInteger permanentAttempts = new AtomicInteger(0);
        CountDownLatch transientSuccess = new CountDownLatch(1);
        CountDownLatch permanentFailure = new CountDownLatch(4); // Initial + 3 retries
        
        // Set up consumer with different behavior based on message content
        consumer.subscribe(message -> {
            String payload = message.getPayload();
            
            if (payload.contains("transient")) {
                int attempt = transientAttempts.incrementAndGet();
                logger.info("Processing transient message, attempt {}", attempt);
                
                if (attempt < 2) {
                    logger.info("INTENTIONAL FAILURE: Transient error on attempt {}", attempt);
                    return CompletableFuture.failedFuture(
                        new RuntimeException("Transient error"));
                } else {
                    logger.info("SUCCESS: Transient message succeeded on attempt {}", attempt);
                    transientSuccess.countDown();
                    return CompletableFuture.completedFuture(null);
                }
            } else {
                int attempt = permanentAttempts.incrementAndGet();
                logger.info("INTENTIONAL FAILURE: Permanent error on attempt {}", attempt);
                permanentFailure.countDown();
                return CompletableFuture.failedFuture(
                    new RuntimeException("Permanent error"));
            }
        });
        
        // Send both types of messages
        producer.send("transient error message").get(5, TimeUnit.SECONDS);
        producer.send("permanent error message").get(5, TimeUnit.SECONDS);
        
        // Wait for outcomes
        assertTrue(transientSuccess.await(30, TimeUnit.SECONDS), 
            "Transient error message should eventually succeed");
        assertTrue(permanentFailure.await(30, TimeUnit.SECONDS), 
            "Permanent error message should be retried multiple times");
        
        // Verify behavior
        assertTrue(transientAttempts.get() >= 2, 
            "Transient message should have at least 2 attempts");
        assertTrue(permanentAttempts.get() >= 4, 
            "Permanent error message should have at least 4 attempts");
        
        logger.info("✅ Mixed error scenarios verified - transient: {} attempts, permanent: {} attempts", 
            transientAttempts.get(), permanentAttempts.get());
    }
}

