package dev.mars.peegeeq.outbox.examples;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;

import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;

import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Comprehensive JUnit test demonstrating sophisticated error handling patterns in PeeGeeQ Outbox Pattern.
 * 
 * This test demonstrates advanced error handling strategies including retry patterns, circuit breakers,
 * dead letter queue management, error classification, and poison message handling.
 * 
 * <h2> INTENTIONAL FAILURES - This Test Contains Expected Errors</h2>
 * <p>This test class deliberately triggers various error conditions to demonstrate proper error handling.
 * The following errors are <b>INTENTIONAL</b> and expected:</p>
 * <ul>
 *   <li><b>Transient errors</b> - Network timeouts, temporary service unavailability</li>
 *   <li><b>Validation errors</b> - Invalid data format, business rule violations</li>
 *   <li><b>System errors</b> - Database connection issues, resource exhaustion</li>
 *   <li><b>Poison messages</b> - Malformed messages that consistently fail processing</li>
 *   <li><b>Circuit breaker trips</b> - Service degradation and recovery patterns</li>
 * </ul>
 * 
 * <h2>Test Coverage</h2>
 * <ul>
 *   <li><b>Retry Strategies</b> - Exponential backoff and retry limits</li>
 *   <li><b>Circuit Breaker Integration</b> - Consumer error handling with circuit breakers</li>
 *   <li><b>Dead Letter Queue Management</b> - Failed message recovery and inspection</li>
 *   <li><b>Error Classification and Routing</b> - Different handling based on error types</li>
 *   <li><b>Poison Message Handling</b> - Detection and isolation of problematic messages</li>
 * </ul>
 * 
 * <h2>Error Handling Strategies Tested</h2>
 * <ul>
 *   <li><b>RETRY</b> - Automatic retry with exponential backoff</li>
 *   <li><b>CIRCUIT_BREAKER</b> - Circuit breaker pattern for failing services</li>
 *   <li><b>DEAD_LETTER</b> - Move to dead letter queue for manual inspection</li>
 *   <li><b>IGNORE</b> - Log and continue (for non-critical errors)</li>
 *   <li><b>ALERT</b> - Send alert and continue processing</li>
 * </ul>
 * 
 * <h2>Expected Test Results</h2>
 * <p>All tests should <b>PASS</b> by correctly handling the intentional failures:</p>
 * <ul>
 *   <li>Retry strategies work with exponential backoff</li>
 *   <li>Circuit breakers trip and recover appropriately</li>
 *   <li>Dead letter queue captures failed messages</li>
 *   <li>Error classification routes messages correctly</li>
 *   <li>Poison messages are detected and isolated</li>
 * </ul>
 * 
 * <h2>Error Log Messages</h2>
 * <p>The following ERROR/WARN log messages are <b>EXPECTED</b> and indicate proper error handling:</p>
 * <ul>
 *   <li>"Processing failed" - Expected for retry testing</li>
 *   <li>"Circuit breaker tripped" - Expected for circuit breaker testing</li>
 *   <li>"Message moved to dead letter queue" - Expected for DLQ testing</li>
 *   <li>"Poison message detected" - Expected for poison message testing</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-14
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnhancedErrorHandlingExampleTest {
    
    private static final Logger logger = LoggerFactory.getLogger(EnhancedErrorHandlingExampleTest.class);
    
    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();
    
    private PeeGeeQManager manager;
    private QueueFactory factory;
    
    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        logger.info("=== Setting up Enhanced Error Handling Example Test ===");

        // Configure PeeGeeQ to use container database
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.max-retries", "3")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .property("peegeeq.consumer.threads", "2")
                .property("peegeeq.queue.batch-size", "5")
                .build();

        // Initialize PeeGeeQ Manager
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());
        manager.start()
            .map(v -> {
                logger.info("PeeGeeQ Manager started successfully");
                // Create outbox factory - following established pattern
                PgDatabaseService databaseService = new PgDatabaseService(manager);
                PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
                // Register outbox factory implementation
                OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                factory = provider.createFactory("outbox", databaseService);
                return (Void) null;
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }
    
    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down: closing resources and manager");
        Future<Void> closeFactory = factory != null
                ? factory.close()
                : Future.succeededFuture();
        closeFactory
                .eventually(() -> manager != null
                        ? manager.closeReactive()
                        : Future.succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }
    
    @Test
    void testRetryStrategies(Vertx vertx, VertxTestContext testContext) {
        String topic = newTopic("retry");
        MessageProducer<ErrorTestMessage> producer = factory.createProducer(topic, ErrorTestMessage.class);
        MessageConsumer<ErrorTestMessage> consumer = factory.createConsumer(topic, ErrorTestMessage.class);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        consumer.subscribe(message -> processSimulated(message, attempts, processed, failures))
            .compose(v -> sendErrorTestMessage(producer, "retry-001", "TRANSIENT_ERROR", "Transient network error"))
            .compose(v -> sendErrorTestMessage(producer, "retry-002", "VALIDATION_ERROR", "Invalid data format"))
            .compose(v -> sendErrorTestMessage(producer, "retry-003", null, "Success message"))
            .compose(v -> awaitTerminalCounts(vertx, topic, 1, 2, 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertCounts(row, 1, 2);
                assertEquals(9, attempts.intValue());
                assertEquals(1, processed.intValue());
                assertEquals(8, failures.intValue());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testCircuitBreakerIntegration(Vertx vertx, VertxTestContext testContext) {
        String topic = newTopic("circuit-breaker");
        MessageProducer<ErrorTestMessage> producer = factory.createProducer(topic, ErrorTestMessage.class);
        MessageConsumer<ErrorTestMessage> consumer = factory.createConsumer(topic, ErrorTestMessage.class);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger consecutiveFailures = new AtomicInteger();
        AtomicInteger openRejections = new AtomicInteger();

        consumer.subscribe(message -> {
            attempts.incrementAndGet();
            if (consecutiveFailures.intValue() >= 3) {
                openRejections.incrementAndGet();
                return Future.failedFuture("Circuit breaker is OPEN");
            }
            consecutiveFailures.incrementAndGet();
            return Future.failedFuture("INTENTIONAL FAILURE: Circuit breaker dependency failed");
        })
            .compose(v -> sendErrorTestMessage(producer, "cb-001", "SYSTEM_ERROR", "Database connection failed"))
            .compose(v -> awaitTerminalCounts(vertx, topic, 0, 1, 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertCounts(row, 0, 1);
                assertEquals(4, attempts.intValue());
                assertEquals(3, consecutiveFailures.intValue());
                assertEquals(1, openRejections.intValue());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testDeadLetterQueueManagement(Vertx vertx, VertxTestContext testContext) {
        String topic = newTopic("dead-letter");
        MessageProducer<ErrorTestMessage> producer = factory.createProducer(topic, ErrorTestMessage.class);
        MessageConsumer<ErrorTestMessage> consumer = factory.createConsumer(topic, ErrorTestMessage.class);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        consumer.subscribe(message -> processSimulated(message, attempts, processed, failures))
            .compose(v -> sendErrorTestMessage(producer, "dlq-001", "POISON_MESSAGE", "Malformed data"))
            .compose(v -> sendErrorTestMessage(producer, "dlq-002", "VALIDATION_ERROR", "Business rule violation"))
            .compose(v -> sendErrorTestMessage(producer, "dlq-003", null, "Success message"))
            .compose(v -> sendErrorTestMessage(producer, "dlq-004", "TRANSIENT_ERROR", "Network timeout"))
            .compose(v -> awaitTerminalCounts(vertx, topic, 1, 3, 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertCounts(row, 1, 3);
                assertEquals(13, attempts.intValue());
                assertEquals(1, processed.intValue());
                assertEquals(12, failures.intValue());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testErrorClassificationAndRouting(Vertx vertx, VertxTestContext testContext) {
        String topic = newTopic("routing");
        MessageProducer<ErrorTestMessage> producer = factory.createProducer(topic, ErrorTestMessage.class);
        MessageConsumer<ErrorTestMessage> consumer = factory.createConsumer(topic, ErrorTestMessage.class);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger routed = new AtomicInteger();

        consumer.subscribe(message -> {
            attempts.incrementAndGet();
            ErrorTestMessage payload = message.getPayload();
            if (classifyError(payload.getErrorType()) == ErrorHandlingStrategy.RETRY) {
                try {
                    simulateProcessing(payload);
                } catch (ProcessingException error) {
                    return Future.failedFuture(error);
                }
            }
            routed.incrementAndGet();
            return Future.succeededFuture();
        })
            .compose(v -> sendErrorTestMessage(producer, "route-001", "TRANSIENT_ERROR", "Should be retried"))
            .compose(v -> sendErrorTestMessage(producer, "route-002", "NON_CRITICAL_ERROR", "Should be ignored"))
            .compose(v -> sendErrorTestMessage(producer, "route-003", "CRITICAL_ERROR", "Should trigger alert"))
            .compose(v -> sendErrorTestMessage(producer, "route-004", null, "Success message"))
            .compose(v -> awaitTerminalCounts(vertx, topic, 3, 1, 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertCounts(row, 3, 1);
                assertEquals(7, attempts.intValue());
                assertEquals(3, routed.intValue());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testPoisonMessageHandling(Vertx vertx, VertxTestContext testContext) {
        String topic = newTopic("poison");
        MessageProducer<ErrorTestMessage> producer = factory.createProducer(topic, ErrorTestMessage.class);
        MessageConsumer<ErrorTestMessage> consumer = factory.createConsumer(topic, ErrorTestMessage.class);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger poisonAttempts = new AtomicInteger();

        consumer.subscribe(message -> {
            if ("POISON_MESSAGE".equals(message.getPayload().getErrorType())) {
                poisonAttempts.incrementAndGet();
            }
            return processSimulated(message, attempts, processed, failures);
        })
            .compose(v -> sendErrorTestMessage(producer, "poison-001", "POISON_MESSAGE", "Malformed message"))
            .compose(v -> sendErrorTestMessage(producer, "poison-002", null, "Normal message"))
            .compose(v -> sendErrorTestMessage(producer, "poison-003", "VALIDATION_ERROR", "Invalid message"))
            .compose(v -> awaitTerminalCounts(vertx, topic, 1, 2, 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertCounts(row, 1, 2);
                assertEquals(9, attempts.intValue());
                assertEquals(1, processed.intValue());
                assertEquals(8, failures.intValue());
                assertEquals(4, poisonAttempts.intValue());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    private Future<Void> processSimulated(
            Message<ErrorTestMessage> message,
            AtomicInteger attempts,
            AtomicInteger processed,
            AtomicInteger failures) {
        attempts.incrementAndGet();
        try {
            simulateProcessing(message.getPayload());
            processed.incrementAndGet();
            return Future.succeededFuture();
        } catch (ProcessingException error) {
            failures.incrementAndGet();
            return Future.failedFuture(error);
        }
    }

    private Future<Row> awaitTerminalCounts(
            Vertx vertx,
            String topic,
            int expectedCompleted,
            int expectedDeadLetter,
            int remainingAttempts) {
        return manager.getDatabaseService().getConnectionProvider()
                .getReactivePool("peegeeq-main")
                .compose(pool -> pool.withConnection(connection -> connection.preparedQuery("""
                        SELECT
                            CAST(COUNT(*) FILTER (WHERE status = 'COMPLETED') AS INTEGER) AS completed_count,
                            CAST(COUNT(*) FILTER (WHERE status = 'DEAD_LETTER') AS INTEGER) AS dead_letter_count
                        FROM outbox
                        WHERE topic = $1
                        """).execute(Tuple.of(topic))))
                .compose(rows -> {
                    Row row = rows.iterator().next();
                    if (row.getInteger("completed_count") == expectedCompleted
                            && row.getInteger("dead_letter_count") == expectedDeadLetter) {
                        return Future.succeededFuture(row);
                    }
                    if (remainingAttempts == 0) {
                        return Future.failedFuture("Topic " + topic + " did not reach expected terminal counts");
                    }
                    return vertx.timer(100).compose(v -> awaitTerminalCounts(
                            vertx,
                            topic,
                            expectedCompleted,
                            expectedDeadLetter,
                            remainingAttempts - 1));
                });
    }

    private void assertCounts(Row row, int expectedCompleted, int expectedDeadLetter) {
        assertEquals(expectedCompleted, row.getInteger("completed_count"));
        assertEquals(expectedDeadLetter, row.getInteger("dead_letter_count"));
    }

    private String newTopic(String suffix) {
        return "ee-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Helper method to send error test messages.
     */
    private Future<Void> sendErrorTestMessage(MessageProducer<ErrorTestMessage> producer, String messageId,
                                    String errorType, String content) {
        // Use a fixed ISO 8601 timestamp string to avoid serialization issues
        String fixedTimestamp = "2025-01-01T00:00:00Z";
        ErrorTestMessage message = new ErrorTestMessage(
            messageId,
            "ERROR_TEST",
            content,
            errorType,
            0,
            fixedTimestamp,
            new HashMap<>()
        );

        Map<String, String> headers = new HashMap<>();
        headers.put("messageId", messageId);
        headers.put("errorType", errorType != null ? errorType : "SUCCESS");

        return producer.send(message, headers)
            .onSuccess(v -> logger.info("Sent test message: {} (errorType: {})", messageId, errorType));
    }

    /**
     * Simulates message processing with potential errors.
     */
    private void simulateProcessing(ErrorTestMessage message) throws ProcessingException {
        String errorType = message.getErrorType();

        if (errorType == null) {
            // Success case
            return;
        }

        switch (errorType) {
            case "TRANSIENT_ERROR":
                throw new ProcessingException("Transient network error", true);
            case "VALIDATION_ERROR":
                throw new ProcessingException("Invalid data format", false);
            case "SYSTEM_ERROR":
                throw new ProcessingException("Database connection failed", true);
            case "POISON_MESSAGE":
                throw new ProcessingException("Malformed message data", false);
            case "NON_CRITICAL_ERROR":
                throw new ProcessingException("Non-critical processing error", false);
            case "CRITICAL_ERROR":
                throw new ProcessingException("Critical system error", false);
            default:
                // Unknown error type - treat as non-retryable
                throw new ProcessingException("Unknown error type: " + errorType, false);
        }
    }

    /**
     * Classifies errors and determines handling strategy.
     */
    private ErrorHandlingStrategy classifyError(String errorType) {
        if (errorType == null) {
            return ErrorHandlingStrategy.RETRY; // Default for success
        }

        switch (errorType) {
            case "TRANSIENT_ERROR":
            case "SYSTEM_ERROR":
                return ErrorHandlingStrategy.RETRY;
            case "POISON_MESSAGE":
                return ErrorHandlingStrategy.DEAD_LETTER;
            case "NON_CRITICAL_ERROR":
                return ErrorHandlingStrategy.IGNORE;
            case "CRITICAL_ERROR":
                return ErrorHandlingStrategy.ALERT;
            case "VALIDATION_ERROR":
            default:
                return ErrorHandlingStrategy.DEAD_LETTER;
        }
    }

    /**
     * Error handling strategies for different types of failures.
     */
    public enum ErrorHandlingStrategy {
        RETRY,
        CIRCUIT_BREAKER,
        DEAD_LETTER,
        IGNORE,
        ALERT
    }

    /**
     * Custom exception for processing errors.
     */
    public static class ProcessingException extends Exception {
        private final boolean retryable;

        public ProcessingException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }

    /**
     * Message payload that can simulate different types of errors.
     */
    public static class ErrorTestMessage {
        private final String messageId;
        private final String messageType;
        private final String content;
        private final String errorType; // null for success, or error type to simulate
        private final int processingAttempts;
        private final String timestamp; // Use String instead of Instant to avoid serialization issues
        private final Map<String, String> metadata;

        @JsonCreator
        public ErrorTestMessage(@JsonProperty("messageId") String messageId,
                               @JsonProperty("messageType") String messageType,
                               @JsonProperty("content") String content,
                               @JsonProperty("errorType") String errorType,
                               @JsonProperty("processingAttempts") int processingAttempts,
                               @JsonProperty("timestamp") String timestamp,
                               @JsonProperty("metadata") Map<String, String> metadata) {
            this.messageId = messageId;
            this.messageType = messageType;
            this.content = content;
            this.errorType = errorType;
            this.processingAttempts = processingAttempts;
            this.timestamp = timestamp;
            this.metadata = metadata != null ? metadata : new HashMap<>();
        }

        // Getters
        public String getMessageId() { return messageId; }
        public String getMessageType() { return messageType; }
        public String getContent() { return content; }
        public String getErrorType() { return errorType; }
        public int getProcessingAttempts() { return processingAttempts; }
        public String getTimestamp() { return timestamp; }
        public Map<String, String> getMetadata() { return metadata; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ErrorTestMessage that = (ErrorTestMessage) o;
            return processingAttempts == that.processingAttempts &&
                   Objects.equals(messageId, that.messageId) &&
                   Objects.equals(messageType, that.messageType) &&
                   Objects.equals(content, that.content) &&
                   Objects.equals(errorType, that.errorType) &&
                   Objects.equals(timestamp, that.timestamp) &&
                   Objects.equals(metadata, that.metadata);
        }

        @Override
        public int hashCode() {
            return Objects.hash(messageId, messageType, content, errorType, processingAttempts, timestamp, metadata);
        }

        @Override
        public String toString() {
            return String.format("ErrorTestMessage{messageId='%s', messageType='%s', content='%s', errorType='%s', processingAttempts=%d, timestamp=%s, metadata=%s}",
                messageId, messageType, content, errorType, processingAttempts, timestamp, metadata);
        }
    }
}
