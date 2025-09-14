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
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demo test for High-Frequency Messaging Patterns.
 * 
 * This test demonstrates:
 * 1. High-throughput messaging with multiple concurrent producers
 * 2. Regional message routing based on headers (US, EU, ASIA)
 * 3. Consumer group load balancing with different strategies
 * 4. Performance metrics collection and reporting
 * 5. Message distribution validation across regions
 * 
 * Based on patterns from PeeGeeQ-Complete-Guide.md Advanced Messaging Patterns section.
 * 
 * @author PeeGeeQ Development Team
 * @since 2025-09-14
 * @version 1.0
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HighFrequencyMessagingDemoTest {
    
    private static final Logger logger = LoggerFactory.getLogger(HighFrequencyMessagingDemoTest.class);
    
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_high_freq_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);
    
    private PeeGeeQManager manager;
    private QueueFactory queueFactory;
    private ExecutorService executorService;
    
    // Performance tracking
    private final AtomicInteger totalMessagesProduced = new AtomicInteger(0);
    private final AtomicInteger totalMessagesConsumed = new AtomicInteger(0);
    
    // Regional message counters
    private final Map<String, AtomicInteger> regionalCounters = new ConcurrentHashMap<>();
    
    private final Map<String, String> originalProperties = new ConcurrentHashMap<>();
    
    @BeforeEach
    void setUp() throws Exception {
        logger.info("=== High-Frequency Messaging Demo Test Setup ===");
        
        // Save original system properties
        saveOriginalProperties();
        
        // Configure system properties for TestContainers
        configureSystemPropertiesForContainer();
        
        // Initialize PeeGeeQ Manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("high-frequency-messaging-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create native factory
        var databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        queueFactory = provider.createFactory("native", databaseService);
        
        // Create thread pool for concurrent operations
        executorService = Executors.newFixedThreadPool(10);
        
        // Initialize regional counters
        regionalCounters.put("US", new AtomicInteger(0));
        regionalCounters.put("EU", new AtomicInteger(0));
        regionalCounters.put("ASIA", new AtomicInteger(0));
        
        logger.info("✅ High-frequency messaging test setup completed");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        logger.info("🧹 Cleaning up high-frequency messaging test resources");
        
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
        
        if (manager != null) {
            manager.stop();
        }
        
        // Restore original system properties
        restoreOriginalProperties();
        
        logger.info("✅ High-frequency messaging test cleanup completed");
    }
    
    @Test
    @Order(1)
    void demonstrateHighThroughputProducers() throws Exception {
        logger.info("🚀 Step 1: Demonstrating high-throughput producers");
        
        // Create producers for different business domains
        MessageProducer<OrderEvent> orderProducer = queueFactory.createProducer("order-events", OrderEvent.class);
        MessageProducer<OrderEvent> paymentProducer = queueFactory.createProducer("payment-events", OrderEvent.class);
        MessageProducer<OrderEvent> inventoryProducer = queueFactory.createProducer("inventory-events", OrderEvent.class);
        
        final int messagesPerProducer = 100;
        final String[] regions = {"US", "EU", "ASIA"};
        
        Instant startTime = Instant.now();
        
        // Launch concurrent producers
        CompletableFuture<Void> orderFuture = CompletableFuture.runAsync(() -> {
            try {
                for (int i = 1; i <= messagesPerProducer; i++) {
                    String region = regions[i % regions.length];
                    OrderEvent event = createOrderEvent("ORDER-" + i, region);
                    Map<String, String> headers = Map.of(
                        "region", region,
                        "type", "order",
                        "priority", "5"
                    );
                    
                    orderProducer.send(event, headers).join();
                    totalMessagesProduced.incrementAndGet();
                }
                logger.info("📦 Order producer completed {} messages", messagesPerProducer);
            } catch (Exception e) {
                logger.error("❌ Order producer failed", e);
                throw new RuntimeException(e);
            }
        }, executorService);
        
        CompletableFuture<Void> paymentFuture = CompletableFuture.runAsync(() -> {
            try {
                for (int i = 1; i <= messagesPerProducer; i++) {
                    String region = regions[(i + 1) % regions.length];
                    OrderEvent event = createPaymentEvent("PAYMENT-" + i, region);
                    Map<String, String> headers = Map.of(
                        "region", region,
                        "type", "payment",
                        "priority", "7"
                    );
                    
                    paymentProducer.send(event, headers).join();
                    totalMessagesProduced.incrementAndGet();
                }
                logger.info("💳 Payment producer completed {} messages", messagesPerProducer);
            } catch (Exception e) {
                logger.error("❌ Payment producer failed", e);
                throw new RuntimeException(e);
            }
        }, executorService);
        
        CompletableFuture<Void> inventoryFuture = CompletableFuture.runAsync(() -> {
            try {
                for (int i = 1; i <= messagesPerProducer; i++) {
                    String region = regions[(i + 2) % regions.length];
                    OrderEvent event = createInventoryEvent("INVENTORY-" + i, region);
                    Map<String, String> headers = Map.of(
                        "region", region,
                        "type", "inventory",
                        "priority", "3"
                    );
                    
                    inventoryProducer.send(event, headers).join();
                    totalMessagesProduced.incrementAndGet();
                }
                logger.info("📊 Inventory producer completed {} messages", messagesPerProducer);
            } catch (Exception e) {
                logger.error("❌ Inventory producer failed", e);
                throw new RuntimeException(e);
            }
        }, executorService);
        
        // Wait for all producers to complete
        CompletableFuture.allOf(orderFuture, paymentFuture, inventoryFuture).join();
        
        Instant endTime = Instant.now();
        long durationMs = endTime.toEpochMilli() - startTime.toEpochMilli();
        double throughput = (double) totalMessagesProduced.get() / (durationMs / 1000.0);
        
        logger.info("📊 High-throughput production results:");
        logger.info("   📊 Total messages produced: {}", totalMessagesProduced.get());
        logger.info("   📊 Production time: {} ms", durationMs);
        logger.info("   📊 Throughput: {:.1f} msg/sec", throughput);
        
        // Validate results
        assertEquals(messagesPerProducer * 3, totalMessagesProduced.get());
        assertTrue(throughput > 50, "Expected throughput > 50 msg/sec, got: " + throughput);
        
        // Cleanup producers
        orderProducer.close();
        paymentProducer.close();
        inventoryProducer.close();
        
        logger.info("✅ High-throughput producers demonstration completed");
    }

    @Test
    @Order(2)
    void demonstrateRegionalMessageRouting() throws Exception {
        logger.info("🚀 Step 2: Demonstrating regional message routing");

        // Reset counters
        totalMessagesConsumed.set(0);
        regionalCounters.values().forEach(counter -> counter.set(0));

        // Create regional consumers
        MessageConsumer<OrderEvent> usConsumer = queueFactory.createConsumer("order-events", OrderEvent.class);
        MessageConsumer<OrderEvent> euConsumer = queueFactory.createConsumer("payment-events", OrderEvent.class);
        MessageConsumer<OrderEvent> asiaConsumer = queueFactory.createConsumer("inventory-events", OrderEvent.class);

        // Subscribe consumers with regional filtering
        usConsumer.subscribe(message -> {
            String region = message.getHeaders().get("region");
            if ("US".equals(region)) {
                regionalCounters.get("US").incrementAndGet();
                totalMessagesConsumed.incrementAndGet();
                logger.debug("🇺🇸 US Consumer processed: {}", message.getPayload().getOrderId());
            }
            return CompletableFuture.completedFuture(null);
        });

        euConsumer.subscribe(message -> {
            String region = message.getHeaders().get("region");
            if ("EU".equals(region)) {
                regionalCounters.get("EU").incrementAndGet();
                totalMessagesConsumed.incrementAndGet();
                logger.debug("🇪🇺 EU Consumer processed: {}", message.getPayload().getOrderId());
            }
            return CompletableFuture.completedFuture(null);
        });

        asiaConsumer.subscribe(message -> {
            String region = message.getHeaders().get("region");
            if ("ASIA".equals(region)) {
                regionalCounters.get("ASIA").incrementAndGet();
                totalMessagesConsumed.incrementAndGet();
                logger.debug("🌏 ASIA Consumer processed: {}", message.getPayload().getOrderId());
            }
            return CompletableFuture.completedFuture(null);
        });

        // Wait for consumers to process existing messages
        Thread.sleep(3000);

        logger.info("📊 Regional message routing results:");
        logger.info("   📊 US messages processed: {}", regionalCounters.get("US").get());
        logger.info("   📊 EU messages processed: {}", regionalCounters.get("EU").get());
        logger.info("   📊 ASIA messages processed: {}", regionalCounters.get("ASIA").get());
        logger.info("   📊 Total messages consumed: {}", totalMessagesConsumed.get());

        // Validate regional distribution
        assertTrue(regionalCounters.get("US").get() > 0, "US should have processed messages");
        assertTrue(regionalCounters.get("EU").get() > 0, "EU should have processed messages");
        assertTrue(regionalCounters.get("ASIA").get() > 0, "ASIA should have processed messages");

        // Cleanup consumers
        usConsumer.close();
        euConsumer.close();
        asiaConsumer.close();

        logger.info("✅ Regional message routing demonstration completed");
    }

    @Test
    @Order(3)
    void demonstrateLoadBalancingStrategies() throws Exception {
        logger.info("🚀 Step 3: Demonstrating load balancing strategies");

        // Reset counters
        totalMessagesConsumed.set(0);
        final AtomicInteger consumer1Count = new AtomicInteger(0);
        final AtomicInteger consumer2Count = new AtomicInteger(0);
        final AtomicInteger consumer3Count = new AtomicInteger(0);

        // Create multiple consumers for the same queue to demonstrate load balancing
        MessageConsumer<OrderEvent> consumer1 = queueFactory.createConsumer("order-events", OrderEvent.class);
        MessageConsumer<OrderEvent> consumer2 = queueFactory.createConsumer("order-events", OrderEvent.class);
        MessageConsumer<OrderEvent> consumer3 = queueFactory.createConsumer("order-events", OrderEvent.class);

        // Subscribe consumers with different processing speeds to show load balancing
        consumer1.subscribe(message -> {
            consumer1Count.incrementAndGet();
            totalMessagesConsumed.incrementAndGet();
            logger.debug("⚡ Fast Consumer 1 processed: {}", message.getPayload().getOrderId());
            return CompletableFuture.completedFuture(null);
        });

        consumer2.subscribe(message -> {
            consumer2Count.incrementAndGet();
            totalMessagesConsumed.incrementAndGet();
            logger.debug("🐌 Slow Consumer 2 processed: {}", message.getPayload().getOrderId());
            // Simulate slower processing
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(null);
        });

        consumer3.subscribe(message -> {
            consumer3Count.incrementAndGet();
            totalMessagesConsumed.incrementAndGet();
            logger.debug("⚖️ Balanced Consumer 3 processed: {}", message.getPayload().getOrderId());
            // Simulate medium processing speed
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(null);
        });

        // Send additional messages to test load balancing
        MessageProducer<OrderEvent> testProducer = queueFactory.createProducer("order-events", OrderEvent.class);

        final int testMessages = 50;
        for (int i = 1; i <= testMessages; i++) {
            OrderEvent event = createOrderEvent("LOAD-TEST-" + i, "US");
            Map<String, String> headers = Map.of(
                "region", "US",
                "type", "load-test",
                "priority", "5"
            );

            testProducer.send(event, headers).join();
        }

        // Wait for load balancing to distribute messages
        Thread.sleep(5000);

        logger.info("📊 Load balancing results:");
        logger.info("   📊 Consumer 1 (fast): {} messages", consumer1Count.get());
        logger.info("   📊 Consumer 2 (slow): {} messages", consumer2Count.get());
        logger.info("   📊 Consumer 3 (balanced): {} messages", consumer3Count.get());
        logger.info("   📊 Total processed: {}", totalMessagesConsumed.get());

        // Validate load balancing (fast consumer should process more)
        assertTrue(consumer1Count.get() >= consumer2Count.get(),
            "Fast consumer should process at least as many messages as slow consumer");
        assertTrue(totalMessagesConsumed.get() > 0, "Messages should have been processed");

        // Cleanup
        testProducer.close();
        consumer1.close();
        consumer2.close();
        consumer3.close();

        logger.info("✅ Load balancing strategies demonstration completed");
    }
    private OrderEvent createOrderEvent(String orderId, String region) {
        return new OrderEvent(orderId, "CUST-" + region + "-001", 
            new BigDecimal("99.99"), "CREATED");
    }
    
    private OrderEvent createPaymentEvent(String paymentId, String region) {
        return new OrderEvent(paymentId, "CUST-" + region + "-002", 
            new BigDecimal("149.99"), "PAID");
    }
    
    private OrderEvent createInventoryEvent(String inventoryId, String region) {
        return new OrderEvent(inventoryId, "CUST-" + region + "-003", 
            new BigDecimal("79.99"), "RESERVED");
    }
    
    private void saveOriginalProperties() {
        String[] propertiesToSave = {
            "peegeeq.database.host", "peegeeq.database.port", "peegeeq.database.name",
            "peegeeq.database.username", "peegeeq.database.password", "peegeeq.database.schema"
        };
        
        for (String property : propertiesToSave) {
            String value = System.getProperty(property);
            if (value != null) {
                originalProperties.put(property, value);
            }
        }
    }
    
    private void configureSystemPropertiesForContainer() {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.migration.enabled", "true");
        System.setProperty("peegeeq.migration.auto-migrate", "true");
    }
    
    private void restoreOriginalProperties() {
        // Clear test properties
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.database.schema");
        System.clearProperty("peegeeq.database.ssl.enabled");
        System.clearProperty("peegeeq.migration.enabled");
        System.clearProperty("peegeeq.migration.auto-migrate");
        
        // Restore original properties
        originalProperties.forEach(System::setProperty);
    }

    /**
     * Simple OrderEvent class for testing high-frequency messaging patterns.
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
}
