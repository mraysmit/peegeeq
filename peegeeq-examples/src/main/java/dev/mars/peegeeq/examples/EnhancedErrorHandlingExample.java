package dev.mars.peegeeq.examples;

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
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.deadletter.DeadLetterMessage;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive example demonstrating sophisticated error handling patterns in PeeGeeQ.
 * 
 * This example shows:
 * - Retry strategies with exponential backoff
 * - Circuit breaker integration for consumer error handling
 * - Dead letter queue management and recovery
 * - Error classification and routing
 * - Poison message detection and handling
 * - Consumer group error isolation
 * - Monitoring and alerting for error conditions
 * - Graceful degradation patterns
 * 
 * Error Handling Strategies:
 * - RETRY: Automatic retry with exponential backoff
 * - CIRCUIT_BREAKER: Circuit breaker pattern for failing services
 * - DEAD_LETTER: Move to dead letter queue for manual inspection
 * - IGNORE: Log and continue (for non-critical errors)
 * - ALERT: Send alert and continue processing
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-29
 * @version 1.0
 */
public class EnhancedErrorHandlingExample {
    
    private static final Logger logger = LoggerFactory.getLogger(EnhancedErrorHandlingExample.class);
    
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
        
        public boolean shouldSimulateError() {
            return errorType != null && !errorType.isEmpty();
        }
        
        public ErrorTestMessage withIncrementedAttempts() {
            return new ErrorTestMessage(messageId, messageType, content, errorType, 
                processingAttempts + 1, timestamp, metadata);
        }
        
        @Override
        public String toString() {
            return String.format("ErrorTestMessage{id='%s', type='%s', errorType='%s', attempts=%d}", 
                messageId, messageType, errorType, processingAttempts);
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ErrorTestMessage that = (ErrorTestMessage) o;
            return Objects.equals(messageId, that.messageId);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(messageId);
        }
    }
    
    /**
     * Custom exception for simulating different types of processing errors.
     */
    public static class ProcessingException extends Exception {
        private final String errorType;
        private final boolean retryable;
        
        public ProcessingException(String errorType, String message, boolean retryable) {
            super(message);
            this.errorType = errorType;
            this.retryable = retryable;
        }
        
        public ProcessingException(String errorType, String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.errorType = errorType;
            this.retryable = retryable;
        }
        
        public String getErrorType() { return errorType; }
        public boolean isRetryable() { return retryable; }
    }
    
    public static void main(String[] args) throws Exception {
        logger.info("=== PeeGeeQ Enhanced Error Handling Example ===");
        logger.info("This example demonstrates sophisticated error handling patterns");
        
        // Start PostgreSQL container
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
                .withDatabaseName("peegeeq_error_demo")
                .withUsername("postgres")
                .withPassword("password")) {
            
            postgres.start();
            logger.info("PostgreSQL container started: {}", postgres.getJdbcUrl());
            
            // Configure PeeGeeQ to use the container
            configureSystemPropertiesForContainer(postgres);
            
            // Run error handling demonstrations
            runErrorHandlingDemonstrations();
            
        } catch (Exception e) {
            logger.error("Failed to run Enhanced Error Handling Example", e);
            throw e;
        }
        
        logger.info("Enhanced Error Handling Example completed successfully!");
    }
    
    /**
     * Configures system properties to use the TestContainer database.
     */
    private static void configureSystemPropertiesForContainer(PostgreSQLContainer<?> postgres) {
        logger.info("⚙️  Configuring PeeGeeQ for error handling demonstration...");
        
        // Set database connection properties
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        
        // Configure for error handling optimization
        System.setProperty("peegeeq.database.pool.min-size", "5");
        System.setProperty("peegeeq.database.pool.max-size", "20");
        System.setProperty("peegeeq.queue.retry.enabled", "true");
        System.setProperty("peegeeq.queue.retry.max-attempts", "3");
        System.setProperty("peegeeq.queue.deadletter.enabled", "true");
        System.setProperty("peegeeq.circuit-breaker.enabled", "true");
        System.setProperty("peegeeq.circuit-breaker.failure-threshold", "5");
        System.setProperty("peegeeq.circuit-breaker.timeout", "30000");
        System.setProperty("peegeeq.metrics.enabled", "true");
        System.setProperty("peegeeq.migration.enabled", "true");
        System.setProperty("peegeeq.migration.auto-migrate", "true");
        
        logger.info("Configuration complete - error handling features enabled");
    }
    
    /**
     * Runs comprehensive error handling demonstrations.
     */
    private static void runErrorHandlingDemonstrations() throws Exception {
        logger.info("Starting error handling demonstrations...");
        
        try (PeeGeeQManager manager = new PeeGeeQManager(
                new PeeGeeQConfiguration("development"), 
                new SimpleMeterRegistry())) {
            
            manager.start();
            logger.info("PeeGeeQ Manager started successfully");
            
            // Create database service and factory provider
            PgDatabaseService databaseService = new PgDatabaseService(manager);
            PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
            
            // Create queue factory (outbox pattern for better error handling)
            QueueFactory factory = provider.createFactory("outbox", databaseService);
            
            // Run all error handling demonstrations
            demonstrateRetryStrategies(factory, manager);
            demonstrateCircuitBreakerIntegration(factory, manager);
            demonstrateDeadLetterQueueManagement(factory, manager);
            demonstrateErrorClassificationAndRouting(factory, manager);
            demonstratePoisonMessageHandling(factory, manager);
            
        } catch (Exception e) {
            logger.error("Error running error handling demonstrations", e);
            throw e;
        }
    }

    /**
     * Demonstrates retry strategies with exponential backoff.
     */
    private static void demonstrateRetryStrategies(QueueFactory factory, PeeGeeQManager manager) throws Exception {
        logger.info("\n=== RETRY STRATEGIES DEMONSTRATION ===");

        MessageProducer<ErrorTestMessage> producer = factory.createProducer("retry-demo", ErrorTestMessage.class);
        MessageConsumer<ErrorTestMessage> consumer = factory.createConsumer("retry-demo", ErrorTestMessage.class);

        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger retryCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(3); // Expecting 3 successful messages

        // Consumer with retry logic
        consumer.subscribe(message -> {
            ErrorTestMessage payload = message.getPayload();
            int attempt = payload.getProcessingAttempts();

            logger.info("Processing message: {} (attempt {})", payload.getMessageId(), attempt + 1);

            try {
                // Simulate processing with potential errors
                simulateProcessing(payload);

                // Success
                int processed = processedCount.incrementAndGet();
                logger.info("✅ Successfully processed message: {} (total processed: {})",
                    payload.getMessageId(), processed);
                latch.countDown();
                return CompletableFuture.completedFuture(null);

            } catch (ProcessingException e) {
                int retries = retryCount.incrementAndGet();
                logger.warn("❌ Processing failed for message: {} (attempt {}, total retries: {}) - {}",
                    payload.getMessageId(), attempt + 1, retries, e.getMessage());

                if (e.isRetryable() && attempt < 2) { // Max 3 attempts
                    // Exponential backoff: 1s, 2s, 4s
                    long backoffMs = (long) Math.pow(2, attempt) * 1000;
                    logger.info("⏳ Scheduling retry for message: {} in {}ms", payload.getMessageId(), backoffMs);

                    // Schedule retry (in real implementation, this would be handled by the queue system)
                    CompletableFuture.delayedExecutor(backoffMs, TimeUnit.MILLISECONDS).execute(() -> {
                        try {
                            ErrorTestMessage retryMessage = payload.withIncrementedAttempts();
                            producer.send(retryMessage, Map.of("retry", "true", "attempt", String.valueOf(attempt + 1)));
                        } catch (Exception ex) {
                            logger.error("Failed to schedule retry", ex);
                        }
                    });

                    return CompletableFuture.completedFuture(null);
                } else {
                    logger.error("💀 Max retries exceeded for message: {}, moving to dead letter queue",
                        payload.getMessageId());

                    // Move to dead letter queue
                    manager.getDeadLetterQueueManager().moveToDeadLetterQueue(
                        "retry-demo",
                        Long.parseLong(payload.getMessageId().replaceAll("\\D", "")),
                        "retry-demo",
                        payload.getContent(),
                        payload.getTimestamp(),
                        "Max retries exceeded: " + e.getMessage(),
                        attempt + 1,
                        Map.of("errorType", e.getErrorType(), "retryable", String.valueOf(e.isRetryable())),
                        payload.getMessageId(),
                        "retry-demo-group"
                    );

                    latch.countDown(); // Count as processed (moved to DLQ)
                    return CompletableFuture.completedFuture(null);
                }
            }
        });

        // Send test messages with different error scenarios
        logger.info("Sending messages with different retry scenarios...");

        // Message that succeeds immediately
        sendErrorTestMessage(producer, "retry-1", "SUCCESS", "Message that succeeds", null);

        // Message that fails twice then succeeds
        sendErrorTestMessage(producer, "retry-2", "TRANSIENT_ERROR", "Message with transient error", "NETWORK_TIMEOUT");

        // Message that always fails (non-retryable)
        sendErrorTestMessage(producer, "retry-3", "PERMANENT_ERROR", "Message with permanent error", "VALIDATION_ERROR");

        // Wait for processing
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        if (!completed) {
            logger.warn("Not all messages were processed within timeout");
        }

        logger.info("Retry strategies demonstration completed");
        logger.info("Total processed: {}, Total retries: {}", processedCount.get(), retryCount.get());
    }

    /**
     * Demonstrates circuit breaker integration for consumer error handling.
     */
    private static void demonstrateCircuitBreakerIntegration(QueueFactory factory, PeeGeeQManager manager) throws Exception {
        logger.info("\n=== CIRCUIT BREAKER INTEGRATION DEMONSTRATION ===");

        MessageProducer<ErrorTestMessage> producer = factory.createProducer("circuit-breaker-demo", ErrorTestMessage.class);
        MessageConsumer<ErrorTestMessage> consumer = factory.createConsumer("circuit-breaker-demo", ErrorTestMessage.class);

        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(8);

        // Consumer with circuit breaker integration
        consumer.subscribe(message -> {
            ErrorTestMessage payload = message.getPayload();

            logger.info("Processing message: {} with circuit breaker protection", payload.getMessageId());

            // Use circuit breaker for external service calls
            try {
                String result = manager.getCircuitBreakerManager().executeSupplier("external-service", () -> {
                    try {
                        // Simulate external service call
                        simulateExternalServiceCall(payload);
                        return "Success";
                    } catch (ProcessingException e) {
                        throw new RuntimeException(e);
                    }
                });

                int processed = processedCount.incrementAndGet();
                logger.info("✅ Circuit breaker allowed processing: {} (result: {}, total: {})",
                    payload.getMessageId(), result, processed);

            } catch (Exception e) {
                int failed = failedCount.incrementAndGet();
                logger.warn("❌ Circuit breaker rejected or service failed: {} (total failed: {}) - {}",
                    payload.getMessageId(), failed, e.getMessage());
            }

            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send messages to test circuit breaker behavior
        logger.info("Sending messages to test circuit breaker behavior...");

        // First few messages succeed
        for (int i = 1; i <= 3; i++) {
            sendErrorTestMessage(producer, "cb-success-" + i, "SUCCESS", "Successful message " + i, null);
        }

        // Next few messages fail to trigger circuit breaker
        for (int i = 1; i <= 3; i++) {
            sendErrorTestMessage(producer, "cb-fail-" + i, "SERVICE_ERROR", "Failing message " + i, "SERVICE_UNAVAILABLE");
        }

        // Final messages should be rejected by circuit breaker
        for (int i = 1; i <= 2; i++) {
            sendErrorTestMessage(producer, "cb-rejected-" + i, "SUCCESS", "Should be rejected " + i, null);
        }

        // Wait for processing
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        if (!completed) {
            logger.warn("Not all messages were processed within timeout");
        }

        // Check circuit breaker metrics
        var cbMetrics = manager.getCircuitBreakerManager().getMetrics("external-service");
        logger.info("Circuit Breaker Metrics:");
        logger.info("  State: {}", cbMetrics.getState());
        logger.info("  Successful Calls: {}", cbMetrics.getSuccessfulCalls());
        logger.info("  Failed Calls: {}", cbMetrics.getFailedCalls());
        logger.info("  Failure Rate: {}%", cbMetrics.getFailureRate());

        logger.info("Circuit breaker integration demonstration completed");
        logger.info("Total processed: {}, Total failed: {}", processedCount.get(), failedCount.get());
    }

    /**
     * Demonstrates dead letter queue management and recovery.
     */
    private static void demonstrateDeadLetterQueueManagement(QueueFactory factory, PeeGeeQManager manager) throws Exception {
        logger.info("\n=== DEAD LETTER QUEUE MANAGEMENT DEMONSTRATION ===");

        MessageProducer<ErrorTestMessage> producer = factory.createProducer("dlq-demo", ErrorTestMessage.class);
        MessageConsumer<ErrorTestMessage> consumer = factory.createConsumer("dlq-demo", ErrorTestMessage.class);

        AtomicInteger processedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(4);

        // Consumer that moves failed messages to DLQ
        consumer.subscribe(message -> {
            ErrorTestMessage payload = message.getPayload();

            logger.info("Processing message: {}", payload.getMessageId());

            try {
                simulateProcessing(payload);

                int processed = processedCount.incrementAndGet();
                logger.info("✅ Successfully processed message: {} (total: {})",
                    payload.getMessageId(), processed);

            } catch (ProcessingException e) {
                logger.error("❌ Processing failed for message: {} - {}", payload.getMessageId(), e.getMessage());

                // Move to dead letter queue with detailed information
                manager.getDeadLetterQueueManager().moveToDeadLetterQueue(
                    "dlq-demo",
                    Long.parseLong(payload.getMessageId().replaceAll("\\D", "")),
                    "dlq-demo",
                    payload.getContent(),
                    payload.getTimestamp(),
                    "Processing failed: " + e.getMessage(),
                    payload.getProcessingAttempts() + 1,
                    Map.of(
                        "errorType", e.getErrorType(),
                        "retryable", String.valueOf(e.isRetryable()),
                        "originalMessageType", payload.getMessageType(),
                        "failureTimestamp", Instant.now().toString()
                    ),
                    payload.getMessageId(),
                    "dlq-demo-group"
                );

                logger.info("💀 Message moved to dead letter queue: {}", payload.getMessageId());
            }

            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send messages that will fail and go to DLQ
        logger.info("Sending messages that will be moved to dead letter queue...");

        sendErrorTestMessage(producer, "dlq-1", "POISON_MESSAGE", "Corrupted data", "PARSE_ERROR");
        sendErrorTestMessage(producer, "dlq-2", "INVALID_DATA", "Invalid business data", "VALIDATION_ERROR");
        sendErrorTestMessage(producer, "dlq-3", "SUCCESS", "This should succeed", null);
        sendErrorTestMessage(producer, "dlq-4", "SYSTEM_ERROR", "System unavailable", "DATABASE_ERROR");

        // Wait for processing
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        if (!completed) {
            logger.warn("Not all messages were processed within timeout");
        }

        // Check dead letter queue statistics
        var dlqStats = manager.getDeadLetterQueueManager().getStatistics();
        logger.info("Dead Letter Queue Statistics:");
        logger.info("  Total Messages: {}", dlqStats.getTotalMessages());
        logger.info("  Unique Topics: {}", dlqStats.getUniqueTopics());
        logger.info("  Average Retry Count: {}", dlqStats.getAverageRetryCount());

        // Retrieve and display DLQ messages
        List<DeadLetterMessage> dlqMessages = manager.getDeadLetterQueueManager()
            .getDeadLetterMessages("dlq-demo", 10, 0);

        logger.info("Dead Letter Messages:");
        for (DeadLetterMessage dlqMsg : dlqMessages) {
            logger.info("  ID: {}, Original ID: {}, Reason: {}, Retry Count: {}",
                dlqMsg.getId(), dlqMsg.getOriginalId(), dlqMsg.getFailureReason(), dlqMsg.getRetryCount());
        }

        logger.info("Dead letter queue management demonstration completed");
        logger.info("Total processed successfully: {}, Total in DLQ: {}",
            processedCount.get(), dlqMessages.size());
    }

    /**
     * Demonstrates error classification and routing based on error types.
     */
    private static void demonstrateErrorClassificationAndRouting(QueueFactory factory, PeeGeeQManager manager) throws Exception {
        logger.info("\n=== ERROR CLASSIFICATION AND ROUTING DEMONSTRATION ===");

        // Create different queues for different error types
        MessageProducer<ErrorTestMessage> producer = factory.createProducer("error-routing", ErrorTestMessage.class);
        MessageConsumer<ErrorTestMessage> consumer = factory.createConsumer("error-routing", ErrorTestMessage.class);

        // Create specialized error handling queues
        MessageProducer<ErrorTestMessage> retryProducer = factory.createProducer("retry-queue", ErrorTestMessage.class);
        MessageProducer<ErrorTestMessage> alertProducer = factory.createProducer("alert-queue", ErrorTestMessage.class);
        MessageProducer<ErrorTestMessage> ignoreProducer = factory.createProducer("ignore-queue", ErrorTestMessage.class);

        AtomicInteger processedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(5);

        // Consumer with error classification and routing
        consumer.subscribe(message -> {
            ErrorTestMessage payload = message.getPayload();

            logger.info("Processing message: {} for error classification", payload.getMessageId());

            try {
                simulateProcessing(payload);

                int processed = processedCount.incrementAndGet();
                logger.info("✅ Successfully processed message: {} (total: {})",
                    payload.getMessageId(), processed);

            } catch (ProcessingException e) {
                ErrorHandlingStrategy strategy = classifyError(e);
                logger.warn("❌ Processing failed for message: {} - Error: {}, Strategy: {}",
                    payload.getMessageId(), e.getErrorType(), strategy);

                // Route based on error classification
                try {
                    switch (strategy) {
                        case RETRY:
                            logger.info("🔄 Routing to retry queue: {}", payload.getMessageId());
                            retryProducer.send(payload.withIncrementedAttempts(),
                                Map.of("errorType", e.getErrorType(), "strategy", "RETRY"));
                            break;

                        case ALERT:
                            logger.info("🚨 Routing to alert queue: {}", payload.getMessageId());
                            alertProducer.send(payload,
                                Map.of("errorType", e.getErrorType(), "strategy", "ALERT", "severity", "HIGH"));
                            break;

                        case IGNORE:
                            logger.info("🤷 Routing to ignore queue: {}", payload.getMessageId());
                            ignoreProducer.send(payload,
                                Map.of("errorType", e.getErrorType(), "strategy", "IGNORE", "severity", "LOW"));
                            break;

                        case DEAD_LETTER:
                            logger.info("💀 Moving to dead letter queue: {}", payload.getMessageId());
                            manager.getDeadLetterQueueManager().moveToDeadLetterQueue(
                                "error-routing",
                                Long.parseLong(payload.getMessageId().replaceAll("\\D", "")),
                                "error-routing",
                                payload.getContent(),
                                payload.getTimestamp(),
                                "Classified as dead letter: " + e.getMessage(),
                                payload.getProcessingAttempts() + 1,
                                Map.of("errorType", e.getErrorType(), "strategy", "DEAD_LETTER"),
                                payload.getMessageId(),
                                "error-routing-group"
                            );
                            break;

                        default:
                            logger.warn("Unknown error handling strategy: {}", strategy);
                    }
                } catch (Exception routingException) {
                    logger.error("Failed to route error message: {}", payload.getMessageId(), routingException);
                }
            }

            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send messages with different error types for classification
        logger.info("Sending messages with different error types for classification...");

        sendErrorTestMessage(producer, "classify-1", "TRANSIENT", "Network timeout error", "NETWORK_TIMEOUT");
        sendErrorTestMessage(producer, "classify-2", "BUSINESS", "Business rule violation", "BUSINESS_RULE_ERROR");
        sendErrorTestMessage(producer, "classify-3", "SYSTEM", "Database connection error", "DATABASE_ERROR");
        sendErrorTestMessage(producer, "classify-4", "VALIDATION", "Invalid input data", "VALIDATION_ERROR");
        sendErrorTestMessage(producer, "classify-5", "SUCCESS", "This should succeed", null);

        // Wait for processing
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        if (!completed) {
            logger.warn("Not all messages were processed within timeout");
        }

        logger.info("Error classification and routing demonstration completed");
        logger.info("Total processed successfully: {}", processedCount.get());
    }

    /**
     * Demonstrates poison message detection and handling.
     */
    private static void demonstratePoisonMessageHandling(QueueFactory factory, PeeGeeQManager manager) throws Exception {
        logger.info("\n=== POISON MESSAGE HANDLING DEMONSTRATION ===");

        MessageProducer<ErrorTestMessage> producer = factory.createProducer("poison-demo", ErrorTestMessage.class);
        MessageConsumer<ErrorTestMessage> consumer = factory.createConsumer("poison-demo", ErrorTestMessage.class);

        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger poisonCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(4);

        // Consumer with poison message detection
        consumer.subscribe(message -> {
            ErrorTestMessage payload = message.getPayload();

            logger.info("Processing message: {} (attempts: {})",
                payload.getMessageId(), payload.getProcessingAttempts());

            // Check if this is a poison message (failed multiple times)
            if (payload.getProcessingAttempts() >= 3) {
                int poison = poisonCount.incrementAndGet();
                logger.error("☠️ POISON MESSAGE DETECTED: {} (attempts: {}, total poison: {})",
                    payload.getMessageId(), payload.getProcessingAttempts(), poison);

                // Handle poison message - quarantine it
                manager.getDeadLetterQueueManager().moveToDeadLetterQueue(
                    "poison-demo",
                    Long.parseLong(payload.getMessageId().replaceAll("\\D", "")),
                    "poison-demo",
                    payload.getContent(),
                    payload.getTimestamp(),
                    "POISON MESSAGE: Exceeded maximum retry attempts",
                    payload.getProcessingAttempts(),
                    Map.of(
                        "poisonMessage", "true",
                        "quarantineReason", "Exceeded maximum retry attempts",
                        "originalErrorType", payload.getErrorType() != null ? payload.getErrorType() : "unknown"
                    ),
                    payload.getMessageId(),
                    "poison-demo-group"
                );

                latch.countDown();
                return CompletableFuture.completedFuture(null);
            }

            try {
                simulateProcessing(payload);

                int processed = processedCount.incrementAndGet();
                logger.info("✅ Successfully processed message: {} (total: {})",
                    payload.getMessageId(), processed);

            } catch (ProcessingException e) {
                logger.warn("❌ Processing failed for message: {} (attempt {}) - {}",
                    payload.getMessageId(), payload.getProcessingAttempts() + 1, e.getMessage());

                // Increment attempts and retry (simulating retry mechanism)
                if (payload.getProcessingAttempts() < 2) {
                    try {
                        ErrorTestMessage retryMessage = payload.withIncrementedAttempts();
                        producer.send(retryMessage, Map.of("retry", "true"));
                        logger.info("🔄 Scheduled retry for message: {}", payload.getMessageId());
                    } catch (Exception retryException) {
                        logger.error("Failed to schedule retry", retryException);
                    }
                }
            }

            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send messages that will become poison messages
        logger.info("Sending messages that will become poison messages...");

        // Message that will always fail and become poison
        ErrorTestMessage poisonMessage = new ErrorTestMessage(
            "poison-1", "ALWAYS_FAIL", "This message will always fail",
            "PERMANENT_ERROR", 0, Instant.now(), Map.of("willFail", "true"));
        producer.send(poisonMessage);

        // Message that succeeds
        sendErrorTestMessage(producer, "poison-2", "SUCCESS", "This should succeed", null);

        // Message that fails a few times then succeeds
        sendErrorTestMessage(producer, "poison-3", "TRANSIENT", "Eventually succeeds", "NETWORK_TIMEOUT");

        // Another poison message
        ErrorTestMessage anotherPoison = new ErrorTestMessage(
            "poison-4", "CORRUPT_DATA", "Corrupted message data",
            "PARSE_ERROR", 0, Instant.now(), Map.of("corrupted", "true"));
        producer.send(anotherPoison);

        // Wait for processing
        boolean completed = latch.await(45, TimeUnit.SECONDS); // Longer timeout for retries
        if (!completed) {
            logger.warn("Not all messages were processed within timeout");
        }

        logger.info("Poison message handling demonstration completed");
        logger.info("Total processed successfully: {}, Total poison messages: {}",
            processedCount.get(), poisonCount.get());
    }

    /**
     * Helper method to send error test messages.
     */
    private static void sendErrorTestMessage(MessageProducer<ErrorTestMessage> producer,
                                           String messageId, String messageType,
                                           String content, String errorType) throws Exception {
        ErrorTestMessage message = new ErrorTestMessage(
            messageId, messageType, content, errorType, 0, Instant.now(), new HashMap<>());

        Map<String, String> headers = new HashMap<>();
        headers.put("messageType", messageType);
        if (errorType != null) {
            headers.put("errorType", errorType);
        }

        producer.send(message, headers).get(5, TimeUnit.SECONDS);
        logger.debug("Sent message: {} (type: {}, errorType: {})", messageId, messageType, errorType);
    }

    /**
     * Simulates message processing with potential errors.
     */
    private static void simulateProcessing(ErrorTestMessage message) throws ProcessingException {
        // Simulate processing time
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Check if we should simulate an error
        if (message.shouldSimulateError()) {
            String errorType = message.getErrorType();

            switch (errorType) {
                case "NETWORK_TIMEOUT":
                    // Transient error - succeeds after a few attempts
                    if (message.getProcessingAttempts() < 2) {
                        throw new ProcessingException(errorType, "Network timeout occurred", true);
                    }
                    break;

                case "VALIDATION_ERROR":
                    throw new ProcessingException(errorType, "Data validation failed", false);

                case "PARSE_ERROR":
                    throw new ProcessingException(errorType, "Failed to parse message content", false);

                case "DATABASE_ERROR":
                    throw new ProcessingException(errorType, "Database connection failed", true);

                case "BUSINESS_RULE_ERROR":
                    throw new ProcessingException(errorType, "Business rule validation failed", false);

                case "SERVICE_UNAVAILABLE":
                    throw new ProcessingException(errorType, "External service unavailable", true);

                case "PERMANENT_ERROR":
                    throw new ProcessingException(errorType, "Permanent processing error", false);

                default:
                    throw new ProcessingException(errorType, "Unknown error type: " + errorType, false);
            }
        }

        // Success case - no exception thrown
    }

    /**
     * Simulates external service call for circuit breaker testing.
     */
    private static void simulateExternalServiceCall(ErrorTestMessage message) throws ProcessingException {
        // Simulate service call time
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Simulate service failures for circuit breaker testing
        if (message.shouldSimulateError() && "SERVICE_UNAVAILABLE".equals(message.getErrorType())) {
            throw new ProcessingException("SERVICE_UNAVAILABLE", "External service is unavailable", true);
        }
    }

    /**
     * Classifies errors and determines appropriate handling strategy.
     */
    private static ErrorHandlingStrategy classifyError(ProcessingException error) {
        String errorType = error.getErrorType();

        switch (errorType) {
            case "NETWORK_TIMEOUT":
            case "DATABASE_ERROR":
            case "SERVICE_UNAVAILABLE":
                return ErrorHandlingStrategy.RETRY;

            case "BUSINESS_RULE_ERROR":
            case "VALIDATION_ERROR":
                return ErrorHandlingStrategy.ALERT;

            case "PARSE_ERROR":
            case "PERMANENT_ERROR":
                return ErrorHandlingStrategy.DEAD_LETTER;

            default:
                return error.isRetryable() ? ErrorHandlingStrategy.RETRY : ErrorHandlingStrategy.IGNORE;
        }
    }
}
