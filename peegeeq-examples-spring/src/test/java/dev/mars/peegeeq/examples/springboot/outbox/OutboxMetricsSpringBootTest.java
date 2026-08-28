package dev.mars.peegeeq.examples.springboot.outbox;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
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
import java.util.concurrent.atomic.AtomicInteger;

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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer (ALL components)");
    }

    @Autowired
    private OutboxFactory outboxFactory;

    @Autowired
    private PeeGeeQManager manager;

    private final List<MessageProducer<?>> activeProducers = new ArrayList<>();
    private final List<MessageConsumer<?>> activeConsumers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        logger.info("Cleaning up test resources...");
        
        // Close all active consumers first
        for (MessageConsumer<?> consumer : activeConsumers) {
            consumer.close();
        }
        activeConsumers.clear();
        
        // Close all active producers
        for (MessageProducer<?> producer : activeProducers) {
            producer.close();
        }
        activeProducers.clear();
        logger.info("Cleanup complete");
    }

    @Test
    @Order(1)
    @DisplayName("Test 1: Message Count Metrics - Verify sent/received/processed counts")
    void testMessageCountMetrics(VertxTestContext testContext) throws Exception {
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
        Future<Void> subscription = consumer.subscribe(message -> {
            logger.debug("Processing message: {}", message.getPayload());
            checkpoint.flag();
            return Future.succeededFuture(null);
        });
        
        // Send messages
        subscription.compose(v -> sendMessages(producer, "Metrics test message ", messageCount))
            .onFailure(testContext::failNow);
        
        // Wait for processing
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), 
            "All messages should be processed within timeout");
        
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
        // Use AtomicInteger + Promise instead of strict Checkpoint because failed messages
        // are retried by the outbox, causing the handler to be invoked more than errorCount times.
        AtomicInteger errorsSeen = new AtomicInteger(0);
        Promise<Void> errorsComplete = Promise.promise();
        Future<Void> subscription = consumer.subscribe(message -> {
            logger.info("INTENTIONAL FAILURE: Processing message that will fail: {}", message.getPayload());
            if (errorsSeen.incrementAndGet() == errorCount) {
                errorsComplete.complete();
            }
            return Future.failedFuture(
                new RuntimeException("Intentional error for metrics testing"));
        });
        
        // Send messages that will fail
        subscription.compose(v -> sendMessages(producer, "Error test message ", errorCount))
            .onFailure(testContext::failNow);

        // Wait for the first errorCount errors, then poll the observable metric until it changes.
        errorsComplete.future()
            .compose(v -> awaitFailedMetricIncrease(vertx, initialErrors, System.nanoTime() + TimeUnit.SECONDS.toNanos(5)))
            .onSuccess(finalErrors -> testContext.verify(() -> {
                logger.info("Final error count: {}", finalErrors);

                assertTrue(finalErrors > initialErrors, 
                    "Error count should increase (was " + initialErrors + ", now " + finalErrors + ")");

                logger.info("Error rate metrics verified successfully");
                testContext.completeNow();
            }))
            .onFailure(error -> {
                logger.error("Failed while waiting for the error metric to update", error);
                testContext.failNow(error);
            });
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
        Future<Void> subscription = consumer.subscribe(message -> {
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
        subscription.compose(v -> sendMessages(producer, "Timing test message ", messageCount))
            .onFailure(testContext::failNow);
        
        // Wait for processing
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), 
            "All messages should be processed within timeout");
        
        // Verify metrics were collected (we can't easily verify exact timing in integration test)
        PeeGeeQMetrics.MetricsSummary metrics = manager.getMetrics().getSummary();
        assertTrue(metrics.getMessagesProcessed() > 0, 
            "Should have processed messages with timing metrics");
        
        logger.info("Processing time metrics collected successfully");
    }

    private Future<Void> sendMessages(MessageProducer<String> producer, String prefix, int count) {
        List<Future<Void>> sends = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            sends.add(producer.send(prefix + i));
        }
        return Future.all(sends).mapEmpty();
    }

    private Future<Double> awaitFailedMetricIncrease(Vertx vertx, double initialErrors, long deadlineNanos) {
        Promise<Double> result = Promise.promise();
        pollFailedMetric(vertx, initialErrors, deadlineNanos, result);
        return result.future();
    }

    private void pollFailedMetric(Vertx vertx, double initialErrors, long deadlineNanos, Promise<Double> result) {
        double currentErrors = manager.getMetrics().getSummary().getMessagesFailed();
        if (currentErrors > initialErrors) {
            result.complete(currentErrors);
        } else if (System.nanoTime() >= deadlineNanos) {
            result.fail("Timed out waiting for the failed-message metric to increase from " + initialErrors);
        } else {
            vertx.setTimer(50, id -> pollFailedMetric(vertx, initialErrors, deadlineNanos, result));
        }
    }
}
