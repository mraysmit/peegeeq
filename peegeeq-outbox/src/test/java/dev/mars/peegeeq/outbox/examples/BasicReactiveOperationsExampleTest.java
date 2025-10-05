package dev.mars.peegeeq.outbox.examples;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
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

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test for BasicReactiveOperationsExample functionality.
 * 
 * This test validates all reactive operation patterns from the original 352-line example:
 * 1. Simple reactive send operations
 * 2. Reactive send with headers
 * 3. Reactive send with correlation ID
 * 4. Full parameter reactive send
 * 5. Performance validation with timing
 * 
 * All original functionality is preserved with enhanced test assertions and documentation.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public class BasicReactiveOperationsExampleTest {

    private static final Logger logger = LoggerFactory.getLogger(BasicReactiveOperationsExampleTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_outbox_test")
            .withUsername("postgres")
            .withPassword("password");

    private PeeGeeQManager manager;
    private QueueFactory outboxFactory;
    private MessageProducer<OrderEvent> orderProducer;
    
    @BeforeEach
    void setUp() throws Exception {
        // Initialize schema first
        TestSchemaInitializer.initializeSchema(postgres);

        logger.info("Setting up Basic Reactive Operations Example Test");
        
        // Set database properties from TestContainer
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.database.schema", "public");
        
        // Initialize PeeGeeQ Manager
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test"), new SimpleMeterRegistry());
        manager.start();
        logger.info("PeeGeeQ Manager started successfully");
        
        // Create outbox factory
        DatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
        
        // Register outbox factory implementation
        OutboxFactoryRegistrar.registerWith(provider);
        
        outboxFactory = provider.createFactory("outbox", databaseService);
        orderProducer = outboxFactory.createProducer("orders", OrderEvent.class);
        
        logger.info("✓ Basic Reactive Operations Example Test setup completed");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        logger.info("Tearing down Basic Reactive Operations Example Test");
        
        try {
            if (outboxFactory != null) {
                outboxFactory.close();
            }
        } catch (Exception e) {
            logger.warn("Error closing outbox factory: {}", e.getMessage());
        }
        
        if (manager != null) {
            manager.stop();
        }
        
        // Clean up database tables
        logger.info("✓ Basic Reactive Operations Example Test teardown completed");
    }

    /**
     * Test Pattern 1: Simple Reactive Send
     * Validates basic sendReactive() functionality with Future<Void> return type
     */
    @Test
    void testSimpleReactiveSend() throws Exception {
        logger.info("=== Testing Simple Reactive Send ===");
        
        // Create test order
        OrderEvent testOrder = new OrderEvent("ORDER-001", "Test Product", 99.99, LocalDateTime.now());
        logger.info("Created test order: {}", testOrder);
        
        // Send using reactive API
        CompletableFuture<Void> future = orderProducer.sendReactive(testOrder).toCompletionStage().toCompletableFuture();
        
        // Validate successful completion
        assertDoesNotThrow(() -> future.get(5, TimeUnit.SECONDS));
        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
        
        logger.info("✓ Simple reactive send completed successfully");
    }

    /**
     * Test Pattern 2: Reactive Send with Headers
     * Validates sendReactive() with custom headers functionality
     */
    @Test
    void testReactiveSendWithHeaders() throws Exception {
        logger.info("=== Testing Reactive Send with Headers ===");
        
        // Create test order and headers
        OrderEvent testOrder = new OrderEvent("ORDER-002", "Premium Product", 199.99, LocalDateTime.now());
        Map<String, String> headers = new HashMap<>();
        headers.put("priority", "high");
        headers.put("source", "web-app");
        headers.put("customer-tier", "premium");
        
        logger.info("Created test order with headers: {}", testOrder);
        logger.info("Headers: {}", headers);
        
        // Send using reactive API with headers
        CompletableFuture<Void> future = orderProducer.sendReactive(testOrder, headers).toCompletionStage().toCompletableFuture();
        
        // Validate successful completion
        assertDoesNotThrow(() -> future.get(5, TimeUnit.SECONDS));
        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
        
        logger.info("✓ Reactive send with headers completed successfully");
    }

    /**
     * Test Pattern 3: Reactive Send with Correlation ID
     * Validates sendReactive() with correlation ID for request tracking
     */
    @Test
    void testReactiveSendWithCorrelationId() throws Exception {
        logger.info("=== Testing Reactive Send with Correlation ID ===");
        
        // Create test order, headers, and correlation ID
        OrderEvent testOrder = new OrderEvent("ORDER-003", "Tracked Product", 149.99, LocalDateTime.now());
        Map<String, String> headers = new HashMap<>();
        headers.put("tracking", "enabled");
        String correlationId = "CORR-" + System.currentTimeMillis();
        
        logger.info("Created test order with correlation ID: {}", testOrder);
        logger.info("Correlation ID: {}", correlationId);
        
        // Send using reactive API with correlation ID
        CompletableFuture<Void> future = orderProducer.sendReactive(testOrder, headers, correlationId).toCompletionStage().toCompletableFuture();
        
        // Validate successful completion
        assertDoesNotThrow(() -> future.get(5, TimeUnit.SECONDS));
        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
        
        logger.info("✓ Reactive send with correlation ID completed successfully");
    }

    /**
     * Test Pattern 4: Full Parameter Reactive Send
     * Validates sendReactive() with all parameters: headers, correlation ID, and message group
     */
    @Test
    void testFullParameterReactiveSend() throws Exception {
        logger.info("=== Testing Full Parameter Reactive Send ===");
        
        // Create test order with all parameters
        OrderEvent testOrder = new OrderEvent("ORDER-004", "Enterprise Product", 299.99, LocalDateTime.now());
        Map<String, String> headers = new HashMap<>();
        headers.put("priority", "critical");
        headers.put("source", "enterprise-api");
        headers.put("customer-tier", "enterprise");
        String correlationId = "CORR-FULL-" + System.currentTimeMillis();
        String messageGroup = "enterprise-orders";
        
        logger.info("Created test order with full parameters: {}", testOrder);
        logger.info("Headers: {}", headers);
        logger.info("Correlation ID: {}", correlationId);
        logger.info("Message Group: {}", messageGroup);
        
        // Send using reactive API with all parameters
        CompletableFuture<Void> future = orderProducer.sendReactive(testOrder, headers, correlationId, messageGroup).toCompletionStage().toCompletableFuture();
        
        // Validate successful completion
        assertDoesNotThrow(() -> future.get(5, TimeUnit.SECONDS));
        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
        
        logger.info("✓ Full parameter reactive send completed successfully");
    }

    /**
     * Test Pattern 5: Performance Validation
     * Validates reactive operations performance and timing characteristics
     */
    @Test
    void testPerformanceValidation() throws Exception {
        logger.info("=== Testing Performance Validation ===");
        
        int messageCount = 10;
        long startTime = System.currentTimeMillis();
        
        // Send multiple messages to validate performance
        CompletableFuture<Void>[] futures = new CompletableFuture[messageCount];
        
        for (int i = 0; i < messageCount; i++) {
            OrderEvent testOrder = new OrderEvent("PERF-ORDER-" + i, "Performance Test Product", 50.0 + i, LocalDateTime.now());
            Map<String, String> headers = new HashMap<>();
            headers.put("batch", "performance-test");
            headers.put("sequence", String.valueOf(i));
            
            futures[i] = orderProducer.sendReactive(testOrder, headers).toCompletionStage().toCompletableFuture();
        }
        
        // Wait for all operations to complete
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures);
        assertDoesNotThrow(() -> allFutures.get(30, TimeUnit.SECONDS));
        
        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        double avgTimePerMessage = (double) totalTime / messageCount;
        
        // Validate performance characteristics
        assertTrue(allFutures.isDone());
        assertFalse(allFutures.isCompletedExceptionally());
        assertTrue(totalTime < 30000, "Total time should be less than 30 seconds");
        assertTrue(avgTimePerMessage < 3000, "Average time per message should be less than 3 seconds");
        
        logger.info("✓ Performance validation completed successfully");
        logger.info("Total time: {}ms, Average per message: {:.2f}ms", totalTime, avgTimePerMessage);
        logger.info("Messages processed: {}, Rate: {:.2f} msg/sec", messageCount, (messageCount * 1000.0) / totalTime);
    }

    /**
     * Test event class representing an order in the system.
     * Used for all reactive operation testing scenarios.
     */
    public static class OrderEvent {
        private String orderId;
        private String productName;
        private double amount;
        private LocalDateTime timestamp;

        public OrderEvent() {}

        public OrderEvent(String orderId, String productName, double amount, LocalDateTime timestamp) {
            this.orderId = orderId;
            this.productName = productName;
            this.amount = amount;
            this.timestamp = timestamp;
        }

        // Getters and setters
        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }
        public String getProductName() { return productName; }
        public void setProductName(String productName) { this.productName = productName; }
        public double getAmount() { return amount; }
        public void setAmount(double amount) { this.amount = amount; }
        public LocalDateTime getTimestamp() { return timestamp; }
        public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

        @Override
        public String toString() {
            return String.format("OrderEvent{orderId='%s', productName='%s', amount=%.2f, timestamp=%s}", 
                orderId, productName, amount, timestamp);
        }
    }
}
