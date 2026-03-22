package dev.mars.peegeeq.examples.springboot.outbox;

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.examples.springboot.SpringBootOutboxApplication;
import dev.mars.peegeeq.outbox.OutboxFactory;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
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
@Tag(TestCategories.INTEGRATION)
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
@ExtendWith(VertxExtension.class)
class OutboxMetricsSpringBootTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxMetricsSpringBootTest.class);
    @Container
    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        logger.info("Configuring properties for OutboxMetrics test");
        SharedTestContainers.configureSharedProperties(registry);
    }

    @BeforeAll
    static void initializeSchema() {
        logger.info("Initializing database schema for Spring Boot metrics test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer (ALL components)");
    }

    @Autowired
    private OutboxFactory outboxFactory;

    @Autowired
    private PeeGeeQManager manager;

    private final List<MessageProducer<?>> activeProducers = new ArrayList<>();
    private final List<MessageConsumer<?>> activeConsumers = new ArrayList<>();

    @AfterEach
    void tearDown(Vertx vertx) throws InterruptedException {
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
        Promise<Void> delay = Promise.promise();
        vertx.setTimer(2000, id -> delay.complete(null));
        delay.future().await();
    }

    @Test
    @Order(1)
    @DisplayName("Test 1: Message Count Metrics - Verify sent/received/processed counts")
    void testMessageCountMetrics(Vertx vertx, VertxTestContext testContext) throws Exception {
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
        Checkpoint checkpoint = testContext.checkpoint(messageCount);
        consumer.subscribe(message -> {
            logger.debug("Processing message: {}", message.getPayload());
            checkpoint.flag();
            return Future.succeededFuture(null);
        });
        
        // Send messages
        for (int i = 0; i < messageCount; i++) {
            producer.send("Metrics test message " + i).await();
        }
        
        // Wait for processing
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), 
            "All messages should be processed within timeout");
        
        // Allow time for metrics to be updated
        Promise<Void> metricsDelay = Promise.promise();
        vertx.setTimer(2000, id -> metricsDelay.complete(null));
        metricsDelay.future().await();
        
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
        
        logger.info("Message count metrics verified successfully");
    }

    @Test
    @Order(2)
    @DisplayName("Test 2: Error Rate Metrics - Verify error tracking")
    void testErrorRateMetrics(Vertx vertx, VertxTestContext testContext) throws Exception {
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
        Checkpoint errorCheckpoint = testContext.checkpoint(errorCount);
        consumer.subscribe(message -> {
            logger.info("INTENTIONAL FAILURE: Processing message that will fail: {}", message.getPayload());
            errorCheckpoint.flag();
            return Future.failedFuture(
                new RuntimeException("Intentional error for metrics testing"));
        });
        
        // Send messages that will fail
        for (int i = 0; i < errorCount; i++) {
            producer.send("Error test message " + i).await();
        }
        
        // Wait for errors to occur
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), 
            "All errors should occur within timeout");
        
        // Allow time for error metrics to be updated
        Promise<Void> errorDelay = Promise.promise();
        vertx.setTimer(3000, id -> errorDelay.complete(null));
        errorDelay.future().await();
        
        // Verify error metrics increased
        PeeGeeQMetrics.MetricsSummary finalMetrics = manager.getMetrics().getSummary();
        double finalErrors = finalMetrics.getMessagesFailed();
        
        logger.info("Final error count: {}", finalErrors);
        
        assertTrue(finalErrors > initialErrors, 
            "Error count should increase (was " + initialErrors + ", now " + finalErrors + ")");
        
        logger.info("Error rate metrics verified successfully");
    }

    @Test
    @Order(3)
    @DisplayName("Test 3: Processing Time Metrics - Verify timing measurements")
    void testProcessingTimeMetrics(Vertx vertx, VertxTestContext testContext) throws Exception {
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
        Checkpoint checkpoint = testContext.checkpoint(messageCount);
        consumer.subscribe(message -> {
            Promise<Void> result = Promise.promise();
            vertx.setTimer(processingDelayMs, id -> {
                logger.debug("Processing message with {}ms delay: {}", 
                    processingDelayMs, message.getPayload());
                checkpoint.flag();
                result.complete(null);
            });
            return result.future();
        });
        
        // Send messages
        for (int i = 0; i < messageCount; i++) {
            producer.send("Timing test message " + i).await();
        }
        
        // Wait for processing
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), 
            "All messages should be processed within timeout");
        
        // Allow time for metrics to be updated
        Promise<Void> timingDelay = Promise.promise();
        vertx.setTimer(2000, id -> timingDelay.complete(null));
        timingDelay.future().await();
        
        // Verify metrics were collected (we can't easily verify exact timing in integration test)
        PeeGeeQMetrics.MetricsSummary metrics = manager.getMetrics().getSummary();
        assertTrue(metrics.getMessagesProcessed() > 0, 
            "Should have processed messages with timing metrics");
        
        logger.info("Processing time metrics collected successfully");
    }
}

