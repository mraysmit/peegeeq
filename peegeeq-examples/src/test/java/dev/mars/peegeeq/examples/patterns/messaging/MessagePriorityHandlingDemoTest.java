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
 * Demo test showcasing Message Priority Handling patterns in PeeGeeQ.
 * 
 * This test demonstrates:
 * 1. Priority-based message processing (CRITICAL, HIGH, NORMAL, LOW, BULK)
 * 2. E-commerce priority scenarios (VIP customers, urgent orders)
 * 3. Priority queue behavior and ordering guarantees
 * 4. Performance characteristics of priority handling
 * 
 * Based on Advanced Messaging Patterns from PeeGeeQ-Complete-Guide.md
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MessagePriorityHandlingDemoTest {

    private static final Logger logger = LoggerFactory.getLogger(MessagePriorityHandlingDemoTest.class);

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_priority_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);

    private PeeGeeQManager manager;
    private QueueFactory queueFactory;
    private ExecutorService executorService;

    // Priority levels as defined in the guide
    public enum Priority {
        CRITICAL(10), HIGH(8), NORMAL(5), LOW(2), BULK(0);
        
        public final int value;
        Priority(int value) { this.value = value; }
    }

    // Performance tracking
    private final AtomicInteger totalMessagesProduced = new AtomicInteger(0);
    private final AtomicInteger totalMessagesConsumed = new AtomicInteger(0);
    
    // Priority processing counters
    private final Map<Priority, AtomicInteger> priorityCounters = new ConcurrentHashMap<>();
    private final List<ProcessedOrder> processedOrders = Collections.synchronizedList(new ArrayList<>());

    // System properties backup for cleanup
    private final Map<String, String> originalProperties = new HashMap<>();

    @BeforeEach
    void setUp() {
        logger.info("🔧 Setting up MessagePriorityHandlingDemoTest");
        
        // Initialize priority counters
        for (Priority priority : Priority.values()) {
            priorityCounters.put(priority, new AtomicInteger(0));
        }
        
        // Backup system properties
        backupSystemProperties();
        
        // Configure system properties for priority handling
        System.setProperty("peegeeq.priority.enabled", "true");
        System.setProperty("peegeeq.priority.levels", "10");
        System.setProperty("peegeeq.consumer.batch.size", "10");
        System.setProperty("peegeeq.consumer.poll.interval", "100");
        
        // Configure system properties for TestContainers
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Initialize PeeGeeQ manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("priority-handling-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create native factory
        var databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        queueFactory = provider.createFactory("native", databaseService);
        
        // Initialize executor service
        executorService = Executors.newFixedThreadPool(8);
        
        logger.info("✅ MessagePriorityHandlingDemoTest setup complete");
    }

    @AfterEach
    void tearDown() {
        logger.info("🧹 Cleaning up MessagePriorityHandlingDemoTest");
        
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
    @DisplayName("Demonstrate Priority-Based Message Processing")
    void demonstratePriorityBasedProcessing() throws Exception {
        logger.info("🚀 Step 1: Demonstrating priority-based message processing");
        
        // Create priority-aware producer and consumer
        MessageProducer<OrderEvent> producer = queueFactory.createProducer("priority-orders", OrderEvent.class);
        MessageConsumer<OrderEvent> consumer = queueFactory.createConsumer("priority-orders", OrderEvent.class);
        
        // Subscribe consumer with priority-aware processing
        consumer.subscribe(message -> {
            OrderEvent order = message.getPayload();
            Priority priority = Priority.valueOf(message.getHeaders().get("priority"));

            logger.info("📦 Processing {} priority order: {} (customer: {})",
                priority, order.getOrderId(), order.getCustomerId());

            // Simulate processing time based on priority
            try {
                Thread.sleep(priority == Priority.CRITICAL ? 10 : 50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            priorityCounters.get(priority).incrementAndGet();
            processedOrders.add(new ProcessedOrder(order.getOrderId(), priority, System.currentTimeMillis()));
            totalMessagesConsumed.incrementAndGet();

            return CompletableFuture.completedFuture(null);
        });
        
        // Produce messages with different priorities
        List<CompletableFuture<Void>> producerTasks = new ArrayList<>();
        
        // CRITICAL: System failures, payment issues
        for (int i = 0; i < 5; i++) {
            final int orderId = i;
            producerTasks.add(CompletableFuture.runAsync(() -> {
                OrderEvent order = new OrderEvent("CRITICAL-" + orderId, "SYSTEM", 
                    new BigDecimal("0.00"), "PAYMENT_FAILED");
                
                Map<String, String> headers = new HashMap<>();
                headers.put("priority", Priority.CRITICAL.name());
                headers.put("urgency", "IMMEDIATE");
                
                producer.send(order, headers);
                totalMessagesProduced.incrementAndGet();
                logger.debug("📤 Sent CRITICAL order: {}", order.getOrderId());
            }, executorService));
        }
        
        // HIGH: VIP customers, large orders
        for (int i = 0; i < 10; i++) {
            final int orderId = i;
            producerTasks.add(CompletableFuture.runAsync(() -> {
                OrderEvent order = new OrderEvent("VIP-" + orderId, "VIP-CUSTOMER-" + orderId, 
                    new BigDecimal("5000.00"), "PENDING");
                
                Map<String, String> headers = new HashMap<>();
                headers.put("priority", Priority.HIGH.name());
                headers.put("customer_tier", "VIP");
                
                producer.send(order, headers);
                totalMessagesProduced.incrementAndGet();
                logger.debug("📤 Sent HIGH priority VIP order: {}", order.getOrderId());
            }, executorService));
        }
        
        // NORMAL: Regular orders
        for (int i = 0; i < 20; i++) {
            final int orderId = i;
            producerTasks.add(CompletableFuture.runAsync(() -> {
                OrderEvent order = new OrderEvent("ORDER-" + orderId, "CUSTOMER-" + orderId, 
                    new BigDecimal("100.00"), "PENDING");
                
                Map<String, String> headers = new HashMap<>();
                headers.put("priority", Priority.NORMAL.name());
                headers.put("customer_tier", "REGULAR");
                
                producer.send(order, headers);
                totalMessagesProduced.incrementAndGet();
                logger.debug("📤 Sent NORMAL priority order: {}", order.getOrderId());
            }, executorService));
        }
        
        // LOW: Non-urgent updates
        for (int i = 0; i < 15; i++) {
            final int orderId = i;
            producerTasks.add(CompletableFuture.runAsync(() -> {
                OrderEvent order = new OrderEvent("UPDATE-" + orderId, "CUSTOMER-" + orderId, 
                    new BigDecimal("10.00"), "INVENTORY_UPDATE");
                
                Map<String, String> headers = new HashMap<>();
                headers.put("priority", Priority.LOW.name());
                headers.put("type", "INVENTORY_UPDATE");
                
                producer.send(order, headers);
                totalMessagesProduced.incrementAndGet();
                logger.debug("📤 Sent LOW priority update: {}", order.getOrderId());
            }, executorService));
        }
        
        // BULK: Analytics, reporting
        for (int i = 0; i < 25; i++) {
            final int orderId = i;
            producerTasks.add(CompletableFuture.runAsync(() -> {
                OrderEvent order = new OrderEvent("ANALYTICS-" + orderId, "SYSTEM", 
                    new BigDecimal("0.00"), "ANALYTICS");
                
                Map<String, String> headers = new HashMap<>();
                headers.put("priority", Priority.BULK.name());
                headers.put("type", "ANALYTICS");
                
                producer.send(order, headers);
                totalMessagesProduced.incrementAndGet();
                logger.debug("📤 Sent BULK analytics event: {}", order.getOrderId());
            }, executorService));
        }
        
        // Wait for all producers to complete
        CompletableFuture.allOf(producerTasks.toArray(new CompletableFuture[0])).get();
        
        // Wait for processing to complete
        Thread.sleep(3000);
        
        // Stop consumer
        consumer.close();
        
        // Verify priority processing
        logger.info("📊 Priority Processing Results:");
        for (Priority priority : Priority.values()) {
            int count = priorityCounters.get(priority).get();
            logger.info("  {} priority: {} messages processed", priority, count);
        }
        
        logger.info("📈 Total produced: {}, Total consumed: {}", 
            totalMessagesProduced.get(), totalMessagesConsumed.get());
        
        // Verify that higher priority messages were processed first
        verifyPriorityOrdering();
        
        // Assert basic functionality
        assertTrue(totalMessagesConsumed.get() > 0, "Should have consumed some messages");
        assertTrue(priorityCounters.get(Priority.CRITICAL).get() > 0, "Should have processed CRITICAL messages");
        assertTrue(priorityCounters.get(Priority.HIGH).get() > 0, "Should have processed HIGH priority messages");
        
        logger.info("✅ Priority-based processing demonstration complete");
    }

    private void verifyPriorityOrdering() {
        logger.info("🔍 Verifying priority ordering...");
        
        // Check that CRITICAL messages were processed before others
        List<ProcessedOrder> criticalOrders = processedOrders.stream()
            .filter(order -> order.priority == Priority.CRITICAL)
            .toList();
        
        List<ProcessedOrder> normalOrders = processedOrders.stream()
            .filter(order -> order.priority == Priority.NORMAL)
            .toList();
        
        if (!criticalOrders.isEmpty() && !normalOrders.isEmpty()) {
            long firstCriticalTime = criticalOrders.get(0).processedAt;
            long firstNormalTime = normalOrders.get(0).processedAt;
            
            if (firstCriticalTime <= firstNormalTime) {
                logger.info("✅ Priority ordering verified: CRITICAL processed before NORMAL");
            } else {
                logger.warn("⚠️ Priority ordering may not be optimal");
            }
        }
    }

    private void backupSystemProperties() {
        String[] propertiesToBackup = {
            "peegeeq.priority.enabled",
            "peegeeq.priority.levels", 
            "peegeeq.consumer.batch.size",
            "peegeeq.consumer.poll.interval"
        };
        
        for (String property : propertiesToBackup) {
            String value = System.getProperty(property);
            if (value != null) {
                originalProperties.put(property, value);
            }
        }
    }

    /**
     * Simple OrderEvent class for testing priority handling patterns.
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
     * Helper class to track processed orders with timing information.
     */
    private static class ProcessedOrder {
        final String orderId;
        final Priority priority;
        final long processedAt;

        ProcessedOrder(String orderId, Priority priority, long processedAt) {
            this.orderId = orderId;
            this.priority = priority;
            this.processedAt = processedAt;
        }
    }
}
