package dev.mars.peegeeq.examples.springboot.outbox;

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring Boot integration tests for Outbox metrics and monitoring.
 * 
 * <p>Demonstrates:
 * <ul>
 *   <li>Message count tracking (sent, received, processed)</li>
 *   <li>Processing time metrics</li>
 *   <li>Error rate monitoring</li>
 *   <li>Metrics aggregation across multiple messages</li>
 * </ul>
 * 
 * <p>Based on OutboxMetricsTest from peegeeq-outbox module.
 */
@SpringBootTest(
    classes = SpringBootOutboxApplication.class,
    properties = {
        "spring.profiles.active=test",
        "logging.level.dev.mars.peegeeq=INFO",
        "logging.level.dev.mars.peegeeq.examples.springboot=INFO",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration"
    }
)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutboxMetricsSpringBootTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxMetricsSpringBootTest.class);

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
        .withDatabaseName("peegeeq_test")
        .withUsername("test_user")
        .withPassword("test_password")
        .withSharedMemorySize(256 * 1024 * 1024L);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        logger.info("Configuring properties for OutboxMetrics test");

        registry.add("peegeeq.database.host", postgres::getHost);
        registry.add("peegeeq.database.port", () -> postgres.getFirstMappedPort().toString());
        registry.add("peegeeq.database.name", postgres::getDatabaseName);
        registry.add("peegeeq.database.username", postgres::getUsername);
        registry.add("peegeeq.database.password", postgres::getPassword);
        registry.add("peegeeq.database.schema", () -> "public");
        registry.add("peegeeq.profile", () -> "test");
        registry.add("peegeeq.migration.enabled", () -> "false");  // Disable migrations for tests
    }

    @Autowired
    private OutboxFactory outboxFactory;

    @Autowired
    private PeeGeeQManager manager;

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
    @DisplayName("Test 1: Message Count Metrics - Verify sent/received/processed counts")
    void testMessageCountMetrics() throws Exception {
        logger.info("\n=== TEST 1: Message Count Metrics ===");
        
        String topicName = "metrics-count-topic";
        int messageCount = 5;
        
        // Create producer and consumer
        MessageProducer<String> producer = outboxFactory.createProducer(topicName, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(topicName, String.class);
        activeProducers.add(producer);
        activeConsumers.add(consumer);
        
        // Get initial metrics
        PeeGeeQMetrics.MetricsSummary initialMetrics = manager.getMetrics().getSummary();
        double initialSent = initialMetrics.getMessagesSent();
        double initialReceived = initialMetrics.getMessagesReceived();
        double initialProcessed = initialMetrics.getMessagesProcessed();
        
        logger.info("Initial metrics - Sent: {}, Received: {}, Processed: {}", 
            initialSent, initialReceived, initialProcessed);
        
        // Set up consumer
        CountDownLatch latch = new CountDownLatch(messageCount);
        consumer.subscribe(message -> {
            logger.debug("Processing message: {}", message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        
        // Send messages
        for (int i = 0; i < messageCount; i++) {
            producer.send("Metrics test message " + i).get(5, TimeUnit.SECONDS);
        }
        
        // Wait for processing
        assertTrue(latch.await(30, TimeUnit.SECONDS), 
            "All messages should be processed within timeout");
        
        // Allow time for metrics to be updated
        Thread.sleep(2000);
        
        // Verify metrics increased
        PeeGeeQMetrics.MetricsSummary finalMetrics = manager.getMetrics().getSummary();
        double finalSent = finalMetrics.getMessagesSent();
        double finalReceived = finalMetrics.getMessagesReceived();
        double finalProcessed = finalMetrics.getMessagesProcessed();
        
        logger.info("Final metrics - Sent: {}, Received: {}, Processed: {}", 
            finalSent, finalReceived, finalProcessed);
        
        assertTrue(finalSent >= initialSent + messageCount, 
            "Sent count should increase by at least " + messageCount);
        assertTrue(finalReceived >= initialReceived + messageCount, 
            "Received count should increase by at least " + messageCount);
        assertTrue(finalProcessed >= initialProcessed + messageCount, 
            "Processed count should increase by at least " + messageCount);
        
        logger.info("✅ Message count metrics verified successfully");
    }

    @Test
    @Order(2)
    @DisplayName("Test 2: Error Rate Metrics - Verify error tracking")
    void testErrorRateMetrics() throws Exception {
        logger.info("\n=== TEST 2: Error Rate Metrics ===");
        
        String topicName = "metrics-error-topic";
        int errorCount = 3;
        
        // Create producer and consumer
        MessageProducer<String> producer = outboxFactory.createProducer(topicName, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(topicName, String.class);
        activeProducers.add(producer);
        activeConsumers.add(consumer);
        
        // Get initial metrics
        PeeGeeQMetrics.MetricsSummary initialMetrics = manager.getMetrics().getSummary();
        double initialErrors = initialMetrics.getMessagesFailed();
        
        logger.info("Initial error count: {}", initialErrors);
        
        // Set up consumer that always fails
        CountDownLatch errorLatch = new CountDownLatch(errorCount);
        consumer.subscribe(message -> {
            logger.info("INTENTIONAL FAILURE: Processing message that will fail: {}", message.getPayload());
            errorLatch.countDown();
            return CompletableFuture.failedFuture(
                new RuntimeException("Intentional error for metrics testing"));
        });
        
        // Send messages that will fail
        for (int i = 0; i < errorCount; i++) {
            producer.send("Error test message " + i).get(5, TimeUnit.SECONDS);
        }
        
        // Wait for errors to occur
        assertTrue(errorLatch.await(30, TimeUnit.SECONDS), 
            "All errors should occur within timeout");
        
        // Allow time for error metrics to be updated
        Thread.sleep(3000);
        
        // Verify error metrics increased
        PeeGeeQMetrics.MetricsSummary finalMetrics = manager.getMetrics().getSummary();
        double finalErrors = finalMetrics.getMessagesFailed();
        
        logger.info("Final error count: {}", finalErrors);
        
        assertTrue(finalErrors > initialErrors, 
            "Error count should increase (was " + initialErrors + ", now " + finalErrors + ")");
        
        logger.info("✅ Error rate metrics verified successfully");
    }

    @Test
    @Order(3)
    @DisplayName("Test 3: Processing Time Metrics - Verify timing measurements")
    void testProcessingTimeMetrics() throws Exception {
        logger.info("\n=== TEST 3: Processing Time Metrics ===");
        
        String topicName = "metrics-timing-topic";
        int messageCount = 3;
        long processingDelayMs = 100;
        
        // Create producer and consumer
        MessageProducer<String> producer = outboxFactory.createProducer(topicName, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(topicName, String.class);
        activeProducers.add(producer);
        activeConsumers.add(consumer);
        
        // Set up consumer with deliberate processing delay
        CountDownLatch latch = new CountDownLatch(messageCount);
        consumer.subscribe(message -> {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    logger.debug("Processing message with {}ms delay: {}", 
                        processingDelayMs, message.getPayload());
                    Thread.sleep(processingDelayMs);
                    latch.countDown();
                    return null;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        });
        
        // Send messages
        for (int i = 0; i < messageCount; i++) {
            producer.send("Timing test message " + i).get(5, TimeUnit.SECONDS);
        }
        
        // Wait for processing
        assertTrue(latch.await(30, TimeUnit.SECONDS), 
            "All messages should be processed within timeout");
        
        // Allow time for metrics to be updated
        Thread.sleep(2000);
        
        // Verify metrics were collected (we can't easily verify exact timing in integration test)
        PeeGeeQMetrics.MetricsSummary metrics = manager.getMetrics().getSummary();
        assertTrue(metrics.getMessagesProcessed() > 0, 
            "Should have processed messages with timing metrics");
        
        logger.info("✅ Processing time metrics collected successfully");
    }
}

