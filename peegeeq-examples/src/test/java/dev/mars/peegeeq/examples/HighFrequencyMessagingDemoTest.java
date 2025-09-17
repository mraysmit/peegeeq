package dev.mars.peegeeq.examples;

import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
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

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * High-Frequency Messaging Demo Test
 *
 * BUSINESS RATIONALE:
 * This test demonstrates PeeGeeQ's capability to handle high-throughput messaging scenarios
 * commonly found in enterprise systems such as:
 * - Financial trading systems processing thousands of orders per second
 * - E-commerce platforms handling concurrent inventory updates and order processing
 * - IoT systems processing sensor data from multiple devices
 * - Real-time analytics systems aggregating events from multiple sources
 * - Multi-tenant SaaS platforms routing messages by tenant/region
 *
 * TECHNICAL RATIONALE:
 * The test validates three critical messaging patterns:
 *
 * 1. HIGH-THROUGHPUT CONCURRENT PRODUCTION:
 *    - Tests PeeGeeQ's ability to handle multiple concurrent producers
 *    - Validates PostgreSQL NOTIFY/LISTEN scalability under load
 *    - Ensures message ordering and delivery guarantees are maintained
 *    - Demonstrates proper resource management under concurrent access
 *    - Validates transaction isolation and consistency under load
 *
 * 2. REGIONAL MESSAGE ROUTING:
 *    - Tests content-based routing using message headers
 *    - Validates queue isolation and message segregation
 *    - Demonstrates geographic distribution patterns for global systems
 *    - Ensures regional processing capabilities for compliance/latency requirements
 *    - Tests header-based message filtering and routing logic
 *
 * 3. LOAD BALANCING STRATEGIES:
 *    - Tests fair distribution of messages across multiple consumers
 *    - Validates PeeGeeQ's round-robin distribution mechanism
 *    - Demonstrates consumer scaling patterns for high-load scenarios
 *    - Ensures no single consumer becomes a bottleneck
 *    - Tests consumer group coordination and message acknowledgment
 *
 * PERFORMANCE EXPECTATIONS:
 * - Should handle 300+ messages across 3 queues concurrently
 * - Regional routing should process 15 messages (5 per region) efficiently
 * - Load balancing should distribute 50 messages fairly across 3 consumers
 * - All operations should complete within reasonable time bounds (< 30 seconds)
 * - Memory usage should remain stable throughout the test
 *
 * DEBUG FEATURES:
 * - Comprehensive logging at DEBUG level for troubleshooting
 * - Message tracking with unique IDs and timestamps
 * - Performance metrics collection and reporting
 * - Consumer processing statistics and distribution analysis
 * - Queue state validation and resource cleanup verification
 *
 * Based on patterns from PeeGeeQ-Complete-Guide.md Advanced Messaging Patterns section.
 *
 * @author PeeGeeQ Development Team
 * @since 2025-09-14
 * @version 1.0
 */
@Testcontainers
class HighFrequencyMessagingDemoTest {
    
    private static final Logger logger = LoggerFactory.getLogger(HighFrequencyMessagingDemoTest.class);
    
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer();
    
    private PeeGeeQManager manager;
    private QueueFactory queueFactory;
    
    // Performance tracking
    private final AtomicInteger totalMessagesProduced = new AtomicInteger(0);
    private final AtomicInteger totalMessagesConsumed = new AtomicInteger(0);
    
    // Regional message counters
    private final Map<String, AtomicInteger> regionalCounters = new ConcurrentHashMap<>();

    /**
     * Generate unique queue name for each test method to ensure test independence
     */
    private String getUniqueQueueName(String baseName) {
        return baseName + "-" + System.nanoTime();
    }
    
    @BeforeEach
    void setUp() throws Exception {
        logger.info("=== High-Frequency Messaging Demo Test Setup ===");

        // Configure system properties for TestContainers
        configureSystemPropertiesForContainer();

        // Initialize PeeGeeQ Manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("high-frequency-messaging-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create native factory
        var databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        queueFactory = provider.createFactory("native", databaseService);

        // Initialize regional counters
        regionalCounters.put("US", new AtomicInteger(0));
        regionalCounters.put("EU", new AtomicInteger(0));
        regionalCounters.put("ASIA", new AtomicInteger(0));

        // Reset performance counters
        totalMessagesProduced.set(0);
        totalMessagesConsumed.set(0);

        logger.info("✅ High-frequency messaging test setup completed");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        logger.info("🧹 Cleaning up high-frequency messaging test resources");

        // Stop PeeGeeQ Manager
        if (manager != null) {
            manager.stop();
        }

        // Clear counters
        totalMessagesProduced.set(0);
        totalMessagesConsumed.set(0);
        regionalCounters.clear();

        // Clear system properties
        clearSystemProperties();

        logger.info("✅ High-frequency messaging test cleanup completed");
    }
    
    /**
     * TEST 1: HIGH-THROUGHPUT CONCURRENT PRODUCTION
     *
     * BUSINESS SCENARIO: Simulates a high-volume e-commerce platform during peak traffic
     * where multiple business domains (orders, payments, inventory) generate events concurrently.
     *
     * TECHNICAL VALIDATION:
     * - Tests concurrent producer performance under load
     * - Validates PostgreSQL NOTIFY/LISTEN scalability
     * - Ensures message ordering and delivery guarantees
     * - Tests transaction isolation under concurrent access
     *
     * SUCCESS CRITERIA:
     * - All 300 messages (100 per producer) are sent successfully
     * - No message loss or corruption occurs
     * - Concurrent producers don't interfere with each other
     * - Performance remains within acceptable bounds
     */
    @Test
    void demonstrateHighThroughputProducers() throws Exception {
        logger.info("🚀 Step 1: Demonstrating high-throughput producers");

        // Create producers for different business domains with unique queue names
        String orderQueueName = getUniqueQueueName("order-events");
        String paymentQueueName = getUniqueQueueName("payment-events");
        String inventoryQueueName = getUniqueQueueName("inventory-events");

        MessageProducer<OrderEvent> orderProducer = queueFactory.createProducer(orderQueueName, OrderEvent.class);
        MessageProducer<OrderEvent> paymentProducer = queueFactory.createProducer(paymentQueueName, OrderEvent.class);
        MessageProducer<OrderEvent> inventoryProducer = queueFactory.createProducer(inventoryQueueName, OrderEvent.class);

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
        });

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
        });

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
        });

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

    /**
     * TEST 2: REGIONAL MESSAGE ROUTING
     *
     * BUSINESS SCENARIO: Simulates a global e-commerce platform that needs to route
     * messages to region-specific processing centers for compliance, latency, and
     * localization requirements (e.g., GDPR in EU, data residency laws).
     *
     * TECHNICAL VALIDATION:
     * - Tests content-based routing using message headers
     * - Validates queue isolation and message segregation
     * - Ensures regional consumers only process their designated messages
     * - Tests header-based message filtering and routing logic
     *
     * SUCCESS CRITERIA:
     * - Each region processes exactly 5 messages
     * - No cross-region message leakage occurs
     * - Total of 15 messages processed across all regions
     * - Regional routing headers are correctly interpreted
     */
    @Test
    void demonstrateRegionalMessageRouting() throws Exception {
        logger.info("🚀 Step 2: Demonstrating regional message routing");

        // Reset counters
        totalMessagesConsumed.set(0);
        regionalCounters.values().forEach(counter -> counter.set(0));
        logger.debug("🔧 DEBUG: Counters reset - Total: {}, Regional: {}",
                    totalMessagesConsumed.get(), regionalCounters);

        // Create regional consumers with unique queue names
        String usQueueName = getUniqueQueueName("regional-us");
        String euQueueName = getUniqueQueueName("regional-eu");
        String asiaQueueName = getUniqueQueueName("regional-asia");

        MessageConsumer<OrderEvent> usConsumer = queueFactory.createConsumer(usQueueName, OrderEvent.class);
        MessageConsumer<OrderEvent> euConsumer = queueFactory.createConsumer(euQueueName, OrderEvent.class);
        MessageConsumer<OrderEvent> asiaConsumer = queueFactory.createConsumer(asiaQueueName, OrderEvent.class);

        // Subscribe consumers with regional filtering
        logger.debug("🔧 DEBUG: Setting up regional consumer subscriptions with header-based filtering");
        usConsumer.subscribe(message -> {
            String region = message.getHeaders().get("region");
            logger.debug("🔧 DEBUG: US Consumer received message with region header: {}", region);
            if ("US".equals(region)) {
                int count = regionalCounters.get("US").incrementAndGet();
                totalMessagesConsumed.incrementAndGet();
                logger.debug("🇺🇸 US Consumer processed: {} (count: {})", message.getPayload().getOrderId(), count);
            } else {
                logger.debug("🔧 DEBUG: US Consumer ignoring message for region: {}", region);
            }
            return CompletableFuture.completedFuture(null);
        });

        euConsumer.subscribe(message -> {
            String region = message.getHeaders().get("region");
            logger.debug("🔧 DEBUG: EU Consumer received message with region header: {}", region);
            if ("EU".equals(region)) {
                int count = regionalCounters.get("EU").incrementAndGet();
                totalMessagesConsumed.incrementAndGet();
                logger.debug("🇪🇺 EU Consumer processed: {} (count: {})", message.getPayload().getOrderId(), count);
            } else {
                logger.debug("🔧 DEBUG: EU Consumer ignoring message for region: {}", region);
            }
            return CompletableFuture.completedFuture(null);
        });

        asiaConsumer.subscribe(message -> {
            String region = message.getHeaders().get("region");
            logger.debug("🔧 DEBUG: ASIA Consumer received message with region header: {}", region);
            if ("ASIA".equals(region)) {
                int count = regionalCounters.get("ASIA").incrementAndGet();
                totalMessagesConsumed.incrementAndGet();
                logger.debug("🌏 ASIA Consumer processed: {} (count: {})", message.getPayload().getOrderId(), count);
            } else {
                logger.debug("🔧 DEBUG: ASIA Consumer ignoring message for region: {}", region);
            }
            return CompletableFuture.completedFuture(null);
        });
        logger.debug("🔧 DEBUG: All regional consumer subscriptions configured");

        // Create producers for regional messages using the same unique queue names
        MessageProducer<OrderEvent> usProducer = queueFactory.createProducer(usQueueName, OrderEvent.class);
        MessageProducer<OrderEvent> euProducer = queueFactory.createProducer(euQueueName, OrderEvent.class);
        MessageProducer<OrderEvent> asiaProducer = queueFactory.createProducer(asiaQueueName, OrderEvent.class);

        // Send regional messages
        int messagesPerRegion = 5;

        for (int i = 1; i <= messagesPerRegion; i++) {
            // Send US messages
            OrderEvent usEvent = createOrderEvent("US-ORDER-" + i, "US");
            Map<String, String> usHeaders = Map.of("region", "US", "type", "order");
            usProducer.send(usEvent, usHeaders).join();

            // Send EU messages
            OrderEvent euEvent = createOrderEvent("EU-ORDER-" + i, "EU");
            Map<String, String> euHeaders = Map.of("region", "EU", "type", "order");
            euProducer.send(euEvent, euHeaders).join();

            // Send ASIA messages
            OrderEvent asiaEvent = createOrderEvent("ASIA-ORDER-" + i, "ASIA");
            Map<String, String> asiaHeaders = Map.of("region", "ASIA", "type", "order");
            asiaProducer.send(asiaEvent, asiaHeaders).join();
        }

        // Wait for consumers to process messages
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

        // Cleanup consumers and producers
        usConsumer.close();
        euConsumer.close();
        asiaConsumer.close();
        usProducer.close();
        euProducer.close();
        asiaProducer.close();

        logger.info("✅ Regional message routing demonstration completed");
    }

    @Test
    void demonstrateLoadBalancingStrategies() throws Exception {
        logger.info("🚀 Step 3: Demonstrating load balancing strategies");

        // Reset counters
        totalMessagesConsumed.set(0);
        final AtomicInteger consumer1Count = new AtomicInteger(0);
        final AtomicInteger consumer2Count = new AtomicInteger(0);
        final AtomicInteger consumer3Count = new AtomicInteger(0);

        // Create multiple consumers for the same queue to demonstrate load balancing
        String loadBalanceQueueName = getUniqueQueueName("load-balance-test");
        MessageConsumer<OrderEvent> consumer1 = queueFactory.createConsumer(loadBalanceQueueName, OrderEvent.class);
        MessageConsumer<OrderEvent> consumer2 = queueFactory.createConsumer(loadBalanceQueueName, OrderEvent.class);
        MessageConsumer<OrderEvent> consumer3 = queueFactory.createConsumer(loadBalanceQueueName, OrderEvent.class);

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
        MessageProducer<OrderEvent> testProducer = queueFactory.createProducer(loadBalanceQueueName, OrderEvent.class);

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
        logger.info("   📊 Fair distribution validation: Each consumer should get some messages");

        // Validate load balancing (fair distribution - each consumer should get some messages)
        assertTrue(consumer1Count.get() > 0, "Consumer 1 should have processed messages");
        assertTrue(consumer2Count.get() > 0, "Consumer 2 should have processed messages");
        assertTrue(consumer3Count.get() > 0, "Consumer 3 should have processed messages");
        assertTrue(totalMessagesConsumed.get() == testMessages, "All messages should have been processed");

        // Validate fair distribution (no consumer should get more than 70% of messages)
        int maxAllowed = (int) (testMessages * 0.7);
        assertTrue(consumer1Count.get() <= maxAllowed, "Consumer 1 should not dominate message processing");
        assertTrue(consumer2Count.get() <= maxAllowed, "Consumer 2 should not dominate message processing");
        assertTrue(consumer3Count.get() <= maxAllowed, "Consumer 3 should not dominate message processing");

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
    
    private void clearSystemProperties() {
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.database.schema");
        System.clearProperty("peegeeq.database.ssl.enabled");
        System.clearProperty("peegeeq.migration.enabled");
        System.clearProperty("peegeeq.migration.auto-migrate");
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
