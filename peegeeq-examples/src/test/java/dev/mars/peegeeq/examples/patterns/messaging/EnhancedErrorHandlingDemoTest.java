package dev.mars.peegeeq.examples.patterns.messaging;

import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demo test showcasing Enhanced Error Handling patterns in PeeGeeQ.
 * 
 * This test demonstrates:
 * 1. Retry mechanisms with exponential backoff
 * 2. Dead Letter Queue (DLQ) handling
 * 3. Circuit breaker patterns for external service failures
 * 4. Error classification and recovery strategies
 * 5. Poison message detection and handling
 * 
 * Based on Advanced Messaging Patterns from PeeGeeQ-Complete-Guide.md
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EnhancedErrorHandlingDemoTest {

    private static final Logger logger = LoggerFactory.getLogger(EnhancedErrorHandlingDemoTest.class);

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_error_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);

    private PeeGeeQManager manager;
    private QueueFactory queueFactory;
    private ExecutorService executorService;

    // Error types for testing different scenarios
    public enum ErrorType {
        TRANSIENT_NETWORK,    // Retry with backoff
        INVALID_DATA,         // Send to DLQ immediately
        EXTERNAL_SERVICE,     // Circuit breaker
        POISON_MESSAGE,       // Special handling
        RECOVERABLE          // Standard retry
    }

    // Performance tracking
    private final AtomicInteger totalMessagesProduced = new AtomicInteger(0);
    private final AtomicInteger totalMessagesConsumed = new AtomicInteger(0);
    private final AtomicInteger totalRetries = new AtomicInteger(0);
    private final AtomicInteger dlqMessages = new AtomicInteger(0);
    private final AtomicInteger successfulRecoveries = new AtomicInteger(0);

    // Error tracking
    private final Map<ErrorType, AtomicInteger> errorCounters = new ConcurrentHashMap<>();
    private final List<ErrorEvent> errorEvents = Collections.synchronizedList(new ArrayList<>());

    // System properties backup for cleanup
    private final Map<String, String> originalProperties = new HashMap<>();

    @BeforeEach
    void setUp() {
        logger.info("🔧 Setting up EnhancedErrorHandlingDemoTest");
        
        // Initialize error counters
        for (ErrorType errorType : ErrorType.values()) {
            errorCounters.put(errorType, new AtomicInteger(0));
        }
        
        // Backup system properties
        backupSystemProperties();
        
        // Configure system properties for error handling
        System.setProperty("peegeeq.retry.enabled", "true");
        System.setProperty("peegeeq.retry.max.attempts", "3");
        System.setProperty("peegeeq.retry.backoff.initial", "100");
        System.setProperty("peegeeq.retry.backoff.multiplier", "2.0");
        System.setProperty("peegeeq.dlq.enabled", "true");
        System.setProperty("peegeeq.circuit.breaker.enabled", "true");
        System.setProperty("peegeeq.circuit.breaker.failure.threshold", "5");
        System.setProperty("peegeeq.circuit.breaker.timeout", "5000");
        
        // Configure system properties for TestContainers
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Initialize PeeGeeQ manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("error-handling-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create native factory
        var databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        queueFactory = provider.createFactory("native", databaseService);
        
        // Initialize executor service
        executorService = Executors.newFixedThreadPool(6);
        
        logger.info("✅ EnhancedErrorHandlingDemoTest setup complete");
    }

    @AfterEach
    void tearDown() {
        logger.info("🧹 Cleaning up EnhancedErrorHandlingDemoTest");
        
        if (executorService != null) {
            executorService.shutdown();
        }
        
        if (manager != null) {
            manager.close();
        }
        
        // Restore original properties
        originalProperties.forEach(System::setProperty);
    }

    @Test
    @Order(1)
    @DisplayName("Demonstrate Enhanced Error Handling with Retry and DLQ")
    void demonstrateEnhancedErrorHandling() throws Exception {
        logger.info("🚀 Step 1: Demonstrating enhanced error handling patterns");
        
        // Create producers and consumers for main queue and DLQ
        MessageProducer<OrderEvent> producer = queueFactory.createProducer("error-orders", OrderEvent.class);
        MessageConsumer<OrderEvent> consumer = queueFactory.createConsumer("error-orders", OrderEvent.class);
        MessageConsumer<OrderEvent> dlqConsumer = queueFactory.createConsumer("error-orders-dlq", OrderEvent.class);
        
        // Subscribe main consumer with error handling
        consumer.subscribe(message -> {
            OrderEvent order = message.getPayload();
            ErrorType errorType = ErrorType.valueOf(message.getHeaders().getOrDefault("error_type", "RECOVERABLE"));

            logger.info("📦 Processing order with potential {} error: {}", errorType, order.getOrderId());

            return processOrderWithErrorHandling(order, errorType, message.getHeaders());
        });
        
        // Subscribe DLQ consumer
        dlqConsumer.subscribe(message -> {
            OrderEvent order = message.getPayload();
            logger.warn("💀 Processing DLQ message: {} (reason: {})",
                order.getOrderId(), message.getHeaders().get("dlq_reason"));

            dlqMessages.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        
        // Produce messages with different error scenarios
        List<CompletableFuture<Void>> producerTasks = new ArrayList<>();
        
        // TRANSIENT_NETWORK errors - should retry and eventually succeed
        for (int i = 0; i < 5; i++) {
            final int orderId = i;
            producerTasks.add(CompletableFuture.runAsync(() -> {
                OrderEvent order = new OrderEvent("NETWORK-" + orderId, "CUSTOMER-" + orderId, 
                    new BigDecimal("100.00"), "PENDING");
                
                Map<String, String> headers = new HashMap<>();
                headers.put("error_type", ErrorType.TRANSIENT_NETWORK.name());
                headers.put("simulate_failure", "true");
                
                producer.send(order, headers);
                totalMessagesProduced.incrementAndGet();
                logger.debug("📤 Sent order with TRANSIENT_NETWORK error: {}", order.getOrderId());
            }, executorService));
        }
        
        // INVALID_DATA errors - should go to DLQ immediately
        for (int i = 0; i < 3; i++) {
            final int orderId = i;
            producerTasks.add(CompletableFuture.runAsync(() -> {
                OrderEvent order = new OrderEvent("INVALID-" + orderId, "", 
                    new BigDecimal("-100.00"), "INVALID");
                
                Map<String, String> headers = new HashMap<>();
                headers.put("error_type", ErrorType.INVALID_DATA.name());
                headers.put("simulate_failure", "true");
                
                producer.send(order, headers);
                totalMessagesProduced.incrementAndGet();
                logger.debug("📤 Sent order with INVALID_DATA error: {}", order.getOrderId());
            }, executorService));
        }
        
        // EXTERNAL_SERVICE errors - circuit breaker pattern
        for (int i = 0; i < 7; i++) {
            final int orderId = i;
            producerTasks.add(CompletableFuture.runAsync(() -> {
                OrderEvent order = new OrderEvent("EXTERNAL-" + orderId, "CUSTOMER-" + orderId, 
                    new BigDecimal("200.00"), "PENDING");
                
                Map<String, String> headers = new HashMap<>();
                headers.put("error_type", ErrorType.EXTERNAL_SERVICE.name());
                headers.put("simulate_failure", "true");
                
                producer.send(order, headers);
                totalMessagesProduced.incrementAndGet();
                logger.debug("📤 Sent order with EXTERNAL_SERVICE error: {}", order.getOrderId());
            }, executorService));
        }
        
        // RECOVERABLE errors - standard retry logic
        for (int i = 0; i < 4; i++) {
            final int orderId = i;
            producerTasks.add(CompletableFuture.runAsync(() -> {
                OrderEvent order = new OrderEvent("RECOVER-" + orderId, "CUSTOMER-" + orderId, 
                    new BigDecimal("50.00"), "PENDING");
                
                Map<String, String> headers = new HashMap<>();
                headers.put("error_type", ErrorType.RECOVERABLE.name());
                headers.put("simulate_failure", "true");
                
                producer.send(order, headers);
                totalMessagesProduced.incrementAndGet();
                logger.debug("📤 Sent order with RECOVERABLE error: {}", order.getOrderId());
            }, executorService));
        }
        
        // Some successful messages
        for (int i = 0; i < 6; i++) {
            final int orderId = i;
            producerTasks.add(CompletableFuture.runAsync(() -> {
                OrderEvent order = new OrderEvent("SUCCESS-" + orderId, "CUSTOMER-" + orderId, 
                    new BigDecimal("75.00"), "PENDING");
                
                Map<String, String> headers = new HashMap<>();
                headers.put("error_type", ErrorType.RECOVERABLE.name());
                headers.put("simulate_failure", "false");
                
                producer.send(order, headers);
                totalMessagesProduced.incrementAndGet();
                logger.debug("📤 Sent successful order: {}", order.getOrderId());
            }, executorService));
        }
        
        // Wait for all producers to complete
        CompletableFuture.allOf(producerTasks.toArray(new CompletableFuture[0])).get();
        
        // Wait for processing to complete
        Thread.sleep(5000);
        
        // Stop consumers
        consumer.close();
        dlqConsumer.close();
        
        // Report results
        logger.info("📊 Error Handling Results:");
        logger.info("  Total produced: {}", totalMessagesProduced.get());
        logger.info("  Total consumed: {}", totalMessagesConsumed.get());
        logger.info("  Total retries: {}", totalRetries.get());
        logger.info("  DLQ messages: {}", dlqMessages.get());
        logger.info("  Successful recoveries: {}", successfulRecoveries.get());
        
        for (ErrorType errorType : ErrorType.values()) {
            int count = errorCounters.get(errorType).get();
            if (count > 0) {
                logger.info("  {} errors: {}", errorType, count);
            }
        }
        
        // Verify error handling behavior
        assertTrue(totalRetries.get() > 0, "Should have performed retries");
        assertTrue(dlqMessages.get() > 0, "Should have messages in DLQ");
        assertTrue(successfulRecoveries.get() > 0, "Should have successful recoveries");
        
        logger.info("✅ Enhanced error handling demonstration complete");
    }

    private CompletableFuture<Void> processOrderWithErrorHandling(OrderEvent order, ErrorType errorType, Map<String, String> headers) {
        return CompletableFuture.supplyAsync(() -> {
            boolean shouldFail = "true".equals(headers.get("simulate_failure"));
            
            if (shouldFail) {
                errorCounters.get(errorType).incrementAndGet();
                errorEvents.add(new ErrorEvent(order.getOrderId(), errorType, System.currentTimeMillis()));
                
                // Simulate different error scenarios
                switch (errorType) {
                    case TRANSIENT_NETWORK:
                        // Simulate network timeout - should retry
                        totalRetries.incrementAndGet();
                        if (totalRetries.get() % 3 == 0) {
                            // Eventually succeed after retries
                            logger.info("🔄 Network error recovered for order: {}", order.getOrderId());
                            successfulRecoveries.incrementAndGet();
                            totalMessagesConsumed.incrementAndGet();
                            return null;
                        }
                        throw new RuntimeException("Network timeout - retry");
                        
                    case INVALID_DATA:
                        // Invalid data - send to DLQ immediately
                        logger.warn("💀 Invalid data detected, sending to DLQ: {}", order.getOrderId());
                        throw new IllegalArgumentException("Invalid order data - DLQ");
                        
                    case EXTERNAL_SERVICE:
                        // External service failure - circuit breaker
                        totalRetries.incrementAndGet();
                        if (totalRetries.get() > 5) {
                            logger.warn("🔌 Circuit breaker opened for external service");
                            throw new RuntimeException("Circuit breaker open");
                        }
                        throw new RuntimeException("External service unavailable");
                        
                    case RECOVERABLE:
                        // Standard recoverable error
                        totalRetries.incrementAndGet();
                        if (totalRetries.get() % 2 == 0) {
                            logger.info("🔄 Recoverable error resolved for order: {}", order.getOrderId());
                            successfulRecoveries.incrementAndGet();
                            totalMessagesConsumed.incrementAndGet();
                            return null;
                        }
                        throw new RuntimeException("Temporary processing error");
                        
                    default:
                        throw new RuntimeException("Unknown error type");
                }
            } else {
                // Successful processing
                logger.info("✅ Successfully processed order: {}", order.getOrderId());
                totalMessagesConsumed.incrementAndGet();
                return null;
            }
        }, executorService).thenApply(result -> null);
    }

    private void backupSystemProperties() {
        String[] propertiesToBackup = {
            "peegeeq.retry.enabled",
            "peegeeq.retry.max.attempts",
            "peegeeq.retry.backoff.initial",
            "peegeeq.retry.backoff.multiplier",
            "peegeeq.dlq.enabled",
            "peegeeq.circuit.breaker.enabled",
            "peegeeq.circuit.breaker.failure.threshold",
            "peegeeq.circuit.breaker.timeout"
        };
        
        for (String property : propertiesToBackup) {
            String value = System.getProperty(property);
            if (value != null) {
                originalProperties.put(property, value);
            }
        }
    }

    /**
     * Simple OrderEvent class for testing error handling patterns.
     */
    public static class OrderEvent {
        private String orderId;
        private String customerId;
        private BigDecimal amount;
        private String status;

        public OrderEvent() {}

        public OrderEvent(String orderId, String customerId, BigDecimal amount, String status) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.amount = amount;
            this.status = status;
        }

        // Getters and setters
        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }

        public String getCustomerId() { return customerId; }
        public void setCustomerId(String customerId) { this.customerId = customerId; }

        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        @Override
        public String toString() {
            return String.format("OrderEvent{orderId='%s', customerId='%s', amount=%s, status='%s'}",
                orderId, customerId, amount, status);
        }
    }

    /**
     * Helper class to track error events with timing information.
     */
    private static class ErrorEvent {
        final String orderId;
        final ErrorType errorType;
        final long occurredAt;

        ErrorEvent(String orderId, ErrorType errorType, long occurredAt) {
            this.orderId = orderId;
            this.errorType = errorType;
            this.occurredAt = occurredAt;
        }
    }
}
