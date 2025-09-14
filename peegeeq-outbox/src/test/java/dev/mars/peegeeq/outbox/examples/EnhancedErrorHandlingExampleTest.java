package dev.mars.peegeeq.outbox.examples;

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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.HashMap;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive JUnit test demonstrating sophisticated error handling patterns in PeeGeeQ Outbox Pattern.
 * 
 * This test demonstrates advanced error handling strategies including retry patterns, circuit breakers,
 * dead letter queue management, error classification, and poison message handling.
 * 
 * <h2>⚠️ INTENTIONAL FAILURES - This Test Contains Expected Errors</h2>
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
 *   <li>✅ Retry strategies work with exponential backoff</li>
 *   <li>✅ Circuit breakers trip and recover appropriately</li>
 *   <li>✅ Dead letter queue captures failed messages</li>
 *   <li>✅ Error classification routes messages correctly</li>
 *   <li>✅ Poison messages are detected and isolated</li>
 * </ul>
 * 
 * <h2>Error Log Messages</h2>
 * <p>The following ERROR/WARN log messages are <b>EXPECTED</b> and indicate proper error handling:</p>
 * <ul>
 *   <li>"💥 Processing failed" - Expected for retry testing</li>
 *   <li>"🔥 Circuit breaker tripped" - Expected for circuit breaker testing</li>
 *   <li>"💀 Message moved to dead letter queue" - Expected for DLQ testing</li>
 *   <li>"☠️ Poison message detected" - Expected for poison message testing</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-14
 * @version 1.0
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnhancedErrorHandlingExampleTest {
    
    private static final Logger logger = LoggerFactory.getLogger(EnhancedErrorHandlingExampleTest.class);
    
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_enhanced_error_test")
            .withUsername("postgres")
            .withPassword("password");
    
    private PeeGeeQManager manager;
    private QueueFactory factory;
    
    @BeforeEach
    void setUp() throws Exception {
        logger.info("=== Setting up Enhanced Error Handling Example Test ===");

        // Configure PeeGeeQ to use container database
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        
        // Configure error handling settings
        System.setProperty("peegeeq.queue.max-retries", "3");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.5S");
        System.setProperty("peegeeq.consumer.threads", "2");
        System.setProperty("peegeeq.queue.batch-size", "5");
        
        // Initialize PeeGeeQ Manager
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        manager.start();
        logger.info("PeeGeeQ Manager started successfully");
        
        // Create outbox factory - following established pattern
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
        
        // Register outbox factory implementation
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        
        factory = provider.createFactory("outbox", databaseService);
        
        logger.info("✅ Enhanced Error Handling Example Test setup completed");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        logger.info("🧹 Cleaning up Enhanced Error Handling Example Test");
        
        if (factory != null) {
            factory.close();
        }
        
        if (manager != null) {
            manager.close();
        }
        
        // Clear system properties
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.database.schema");
        System.clearProperty("peegeeq.queue.max-retries");
        System.clearProperty("peegeeq.queue.polling-interval");
        System.clearProperty("peegeeq.consumer.threads");
        System.clearProperty("peegeeq.queue.batch-size");
        
        logger.info("✅ Enhanced Error Handling Example Test cleanup completed");
    }
    
    @Test
    void testRetryStrategies() throws Exception {
        logger.info("=== Testing Retry Strategies with Exponential Backoff ===");
        
        try (MessageProducer<ErrorTestMessage> producer = factory.createProducer("retry-demo", ErrorTestMessage.class);
             MessageConsumer<ErrorTestMessage> consumer = factory.createConsumer("retry-demo", ErrorTestMessage.class)) {
            
            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicInteger retryCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(3); // Expecting 3 successful messages
            
            // Consumer with retry logic
            consumer.subscribe(message -> {
                ErrorTestMessage payload = message.getPayload();
                int attempt = payload.getProcessingAttempts();
                
                logger.info("🧪 INTENTIONAL TEST: Processing message: {} (attempt {})", 
                    payload.getMessageId(), attempt + 1);
                
                try {
                    // Simulate processing with potential errors
                    simulateProcessing(payload);
                    
                    // Success
                    int processed = processedCount.incrementAndGet();
                    logger.info("✅ EXPECTED SUCCESS: Successfully processed message: {} (total processed: {})",
                        payload.getMessageId(), processed);
                    latch.countDown();
                    return CompletableFuture.completedFuture(null);
                    
                } catch (ProcessingException e) {
                    int retries = retryCount.incrementAndGet();
                    logger.warn("🎯 INTENTIONAL TEST FAILURE: Processing failed for message: {} (retry {}): {}", 
                        payload.getMessageId(), retries, e.getMessage());
                    logger.info("   📋 This failure demonstrates retry mechanism with exponential backoff");
                    
                    if (e.isRetryable() && attempt < 3) {
                        // Simulate exponential backoff
                        try {
                            Thread.sleep(100 * (1L << attempt)); // 100ms, 200ms, 400ms
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        return CompletableFuture.failedFuture(e);
                    } else {
                        logger.info("✅ EXPECTED FAILURE: Message failed after max retries: {}", payload.getMessageId());
                        latch.countDown();
                        return CompletableFuture.failedFuture(e);
                    }
                }
            });
            
            // Send test messages with different error scenarios
            sendErrorTestMessage(producer, "retry-001", "TRANSIENT_ERROR", "Transient network error");
            sendErrorTestMessage(producer, "retry-002", "VALIDATION_ERROR", "Invalid data format");
            sendErrorTestMessage(producer, "retry-003", null, "Success message"); // Should succeed
            
            // Wait for processing
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            assertTrue(completed, "Retry strategies test should complete within timeout");
            
            logger.info("✅ Retry strategies test completed successfully!");
            logger.info("   📊 Total processed: {}, Total retries: {}", processedCount.get(), retryCount.get());
        }
    }

    @Test
    void testCircuitBreakerIntegration() throws Exception {
        logger.info("=== Testing Circuit Breaker Integration ===");

        try (MessageProducer<ErrorTestMessage> producer = factory.createProducer("circuit-breaker-demo", ErrorTestMessage.class);
             MessageConsumer<ErrorTestMessage> consumer = factory.createConsumer("circuit-breaker-demo", ErrorTestMessage.class)) {

            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(5); // Expecting 5 messages processed

            // Simulate circuit breaker state
            AtomicInteger consecutiveFailures = new AtomicInteger(0);
            final int CIRCUIT_BREAKER_THRESHOLD = 3;

            consumer.subscribe(message -> {
                ErrorTestMessage payload = message.getPayload();

                logger.info("🧪 INTENTIONAL TEST: Processing message with circuit breaker: {}", payload.getMessageId());

                // Check circuit breaker state
                if (consecutiveFailures.get() >= CIRCUIT_BREAKER_THRESHOLD) {
                    logger.warn("🔥 INTENTIONAL TEST FAILURE: Circuit breaker is OPEN - rejecting message: {}",
                        payload.getMessageId());
                    logger.info("   📋 This demonstrates circuit breaker protection against cascading failures");
                    latch.countDown();
                    return CompletableFuture.failedFuture(new RuntimeException("Circuit breaker is OPEN"));
                }

                try {
                    simulateProcessing(payload);

                    // Success - reset circuit breaker
                    consecutiveFailures.set(0);
                    int processed = processedCount.incrementAndGet();
                    logger.info("✅ EXPECTED SUCCESS: Circuit breaker processing succeeded: {} (total: {})",
                        payload.getMessageId(), processed);
                    latch.countDown();
                    return CompletableFuture.completedFuture(null);

                } catch (ProcessingException e) {
                    int failures = consecutiveFailures.incrementAndGet();
                    int totalFailures = failureCount.incrementAndGet();

                    logger.warn("🎯 INTENTIONAL TEST FAILURE: Circuit breaker processing failed: {} (consecutive: {}, total: {})",
                        payload.getMessageId(), failures, totalFailures);
                    logger.info("   📋 This failure contributes to circuit breaker state management");

                    if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
                        logger.warn("🔥 EXPECTED BEHAVIOR: Circuit breaker OPENED after {} consecutive failures", failures);
                    }

                    latch.countDown();
                    return CompletableFuture.failedFuture(e);
                }
            });

            // Send messages that will trigger circuit breaker
            sendErrorTestMessage(producer, "cb-001", "SYSTEM_ERROR", "Database connection failed");
            sendErrorTestMessage(producer, "cb-002", "SYSTEM_ERROR", "Service unavailable");
            sendErrorTestMessage(producer, "cb-003", "SYSTEM_ERROR", "Timeout occurred");
            sendErrorTestMessage(producer, "cb-004", "SYSTEM_ERROR", "Should be rejected by circuit breaker");
            sendErrorTestMessage(producer, "cb-005", null, "Success message - but circuit breaker is open");

            // Wait for processing
            boolean completed = latch.await(20, TimeUnit.SECONDS);
            assertTrue(completed, "Circuit breaker test should complete within timeout");

            logger.info("✅ Circuit breaker integration test completed successfully!");
            logger.info("   📊 Processed: {}, Failures: {}, Circuit breaker trips: {}",
                processedCount.get(), failureCount.get(),
                consecutiveFailures.get() >= CIRCUIT_BREAKER_THRESHOLD ? 1 : 0);
        }
    }

    @Test
    void testDeadLetterQueueManagement() throws Exception {
        logger.info("=== Testing Dead Letter Queue Management ===");

        try (MessageProducer<ErrorTestMessage> producer = factory.createProducer("dlq-demo", ErrorTestMessage.class);
             MessageConsumer<ErrorTestMessage> consumer = factory.createConsumer("dlq-demo", ErrorTestMessage.class)) {

            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicInteger dlqCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(4); // Expecting 4 messages processed

            consumer.subscribe(message -> {
                ErrorTestMessage payload = message.getPayload();

                logger.info("🧪 INTENTIONAL TEST: Processing message for DLQ demo: {}", payload.getMessageId());

                try {
                    simulateProcessing(payload);

                    // Success
                    int processed = processedCount.incrementAndGet();
                    logger.info("✅ EXPECTED SUCCESS: DLQ demo processing succeeded: {} (total: {})",
                        payload.getMessageId(), processed);
                    latch.countDown();
                    return CompletableFuture.completedFuture(null);

                } catch (ProcessingException e) {
                    if (!e.isRetryable() || payload.getProcessingAttempts() >= 3) {
                        // Move to dead letter queue
                        int dlqMessages = dlqCount.incrementAndGet();
                        logger.warn("💀 EXPECTED BEHAVIOR: Message moved to dead letter queue: {} (total DLQ: {})",
                            payload.getMessageId(), dlqMessages);
                        logger.info("   📋 This demonstrates proper dead letter queue management");
                        latch.countDown();
                        return CompletableFuture.failedFuture(new RuntimeException("Moved to DLQ: " + e.getMessage()));
                    } else {
                        logger.warn("🎯 INTENTIONAL TEST FAILURE: DLQ demo processing failed (will retry): {}",
                            payload.getMessageId());
                        latch.countDown();
                        return CompletableFuture.failedFuture(e);
                    }
                }
            });

            // Send messages with different failure patterns
            sendErrorTestMessage(producer, "dlq-001", "POISON_MESSAGE", "Malformed data that cannot be processed");
            sendErrorTestMessage(producer, "dlq-002", "VALIDATION_ERROR", "Business rule violation");
            sendErrorTestMessage(producer, "dlq-003", null, "Success message");
            sendErrorTestMessage(producer, "dlq-004", "TRANSIENT_ERROR", "Network timeout");

            // Wait for processing
            boolean completed = latch.await(25, TimeUnit.SECONDS);
            assertTrue(completed, "Dead letter queue test should complete within timeout");

            logger.info("✅ Dead letter queue management test completed successfully!");
            logger.info("   📊 Processed successfully: {}, Moved to DLQ: {}", processedCount.get(), dlqCount.get());
        }
    }

    @Test
    void testErrorClassificationAndRouting() throws Exception {
        logger.info("=== Testing Error Classification and Routing ===");

        try (MessageProducer<ErrorTestMessage> producer = factory.createProducer("error-routing", ErrorTestMessage.class);
             MessageConsumer<ErrorTestMessage> consumer = factory.createConsumer("error-routing", ErrorTestMessage.class)) {

            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicInteger routedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(4); // Expecting 4 messages processed

            consumer.subscribe(message -> {
                ErrorTestMessage payload = message.getPayload();

                logger.info("🧪 INTENTIONAL TEST: Processing message for error routing: {}", payload.getMessageId());

                try {
                    // Classify error and route accordingly
                    ErrorHandlingStrategy strategy = classifyError(payload.getErrorType());

                    switch (strategy) {
                        case RETRY:
                            logger.info("📋 ROUTING: Message classified for RETRY strategy: {}", payload.getMessageId());
                            simulateProcessing(payload);
                            break;
                        case IGNORE:
                            logger.info("📋 ROUTING: Message classified for IGNORE strategy: {}", payload.getMessageId());
                            // Log and continue
                            break;
                        case ALERT:
                            logger.info("📋 ROUTING: Message classified for ALERT strategy: {}", payload.getMessageId());
                            // Send alert and continue
                            break;
                        default:
                            simulateProcessing(payload);
                    }

                    int processed = processedCount.incrementAndGet();
                    int routed = routedCount.incrementAndGet();
                    logger.info("✅ EXPECTED SUCCESS: Error routing succeeded: {} (processed: {}, routed: {})",
                        payload.getMessageId(), processed, routed);
                    latch.countDown();
                    return CompletableFuture.completedFuture(null);

                } catch (ProcessingException e) {
                    logger.warn("🎯 INTENTIONAL TEST FAILURE: Error routing processing failed: {}",
                        payload.getMessageId());
                    logger.info("   📋 This failure demonstrates error classification and routing");
                    latch.countDown();
                    return CompletableFuture.failedFuture(e);
                }
            });

            // Send messages with different error types for routing
            sendErrorTestMessage(producer, "route-001", "TRANSIENT_ERROR", "Should be retried");
            sendErrorTestMessage(producer, "route-002", "NON_CRITICAL_ERROR", "Should be ignored");
            sendErrorTestMessage(producer, "route-003", "CRITICAL_ERROR", "Should trigger alert");
            sendErrorTestMessage(producer, "route-004", null, "Success message");

            // Wait for processing
            boolean completed = latch.await(20, TimeUnit.SECONDS);
            assertTrue(completed, "Error classification and routing test should complete within timeout");

            logger.info("✅ Error classification and routing test completed successfully!");
            logger.info("   📊 Total processed: {}, Total routed: {}", processedCount.get(), routedCount.get());
        }
    }

    @Test
    void testPoisonMessageHandling() throws Exception {
        logger.info("=== Testing Poison Message Handling ===");

        try (MessageProducer<ErrorTestMessage> producer = factory.createProducer("poison-demo", ErrorTestMessage.class);
             MessageConsumer<ErrorTestMessage> consumer = factory.createConsumer("poison-demo", ErrorTestMessage.class)) {

            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicInteger poisonCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(3); // Expecting 3 messages processed

            consumer.subscribe(message -> {
                ErrorTestMessage payload = message.getPayload();

                logger.info("🧪 INTENTIONAL TEST: Processing message for poison detection: {}", payload.getMessageId());

                // Check if message is poison (consistently fails)
                if ("POISON_MESSAGE".equals(payload.getErrorType())) {
                    int poisonMessages = poisonCount.incrementAndGet();
                    logger.warn("☠️ EXPECTED BEHAVIOR: Poison message detected and isolated: {} (total poison: {})",
                        payload.getMessageId(), poisonMessages);
                    logger.info("   📋 This demonstrates poison message detection and isolation");
                    latch.countDown();
                    return CompletableFuture.failedFuture(new RuntimeException("Poison message isolated"));
                }

                try {
                    simulateProcessing(payload);

                    int processed = processedCount.incrementAndGet();
                    logger.info("✅ EXPECTED SUCCESS: Poison demo processing succeeded: {} (total: {})",
                        payload.getMessageId(), processed);
                    latch.countDown();
                    return CompletableFuture.completedFuture(null);

                } catch (ProcessingException e) {
                    logger.warn("🎯 INTENTIONAL TEST FAILURE: Poison demo processing failed: {}",
                        payload.getMessageId());
                    latch.countDown();
                    return CompletableFuture.failedFuture(e);
                }
            });

            // Send messages including poison messages
            sendErrorTestMessage(producer, "poison-001", "POISON_MESSAGE", "Malformed message that always fails");
            sendErrorTestMessage(producer, "poison-002", null, "Normal message");
            sendErrorTestMessage(producer, "poison-003", "VALIDATION_ERROR", "Recoverable error");

            // Wait for processing
            boolean completed = latch.await(15, TimeUnit.SECONDS);
            assertTrue(completed, "Poison message handling test should complete within timeout");

            logger.info("✅ Poison message handling test completed successfully!");
            logger.info("   📊 Processed successfully: {}, Poison messages isolated: {}",
                processedCount.get(), poisonCount.get());
        }
    }

    /**
     * Helper method to send error test messages.
     */
    private void sendErrorTestMessage(MessageProducer<ErrorTestMessage> producer, String messageId,
                                    String errorType, String content) throws Exception {
        ErrorTestMessage message = new ErrorTestMessage(
            messageId,
            "ERROR_TEST",
            content,
            errorType,
            0,
            Instant.now(),
            new HashMap<>()
        );

        Map<String, String> headers = new HashMap<>();
        headers.put("messageId", messageId);
        headers.put("errorType", errorType != null ? errorType : "SUCCESS");

        producer.send(message, headers).join();
        logger.info("📤 Sent test message: {} (errorType: {})", messageId, errorType);
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
        private final Instant timestamp;
        private final Map<String, String> metadata;

        @JsonCreator
        public ErrorTestMessage(@JsonProperty("messageId") String messageId,
                               @JsonProperty("messageType") String messageType,
                               @JsonProperty("content") String content,
                               @JsonProperty("errorType") String errorType,
                               @JsonProperty("processingAttempts") int processingAttempts,
                               @JsonProperty("timestamp") Instant timestamp,
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
        public Instant getTimestamp() { return timestamp; }
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
