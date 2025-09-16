package dev.mars.peegeeq.examples.patterns.integration;

import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demo test showcasing Enterprise Integration Patterns for PeeGeeQ.
 * 
 * This test demonstrates:
 * 1. Message Transformation - Converting between different message formats
 * 2. Content-Based Routing - Routing messages based on content
 * 3. Message Aggregation - Combining related messages
 * 4. Scatter-Gather Pattern - Distributing requests and collecting responses
 * 5. Saga Pattern - Managing distributed transactions
 * 
 * Based on Advanced Messaging Patterns from PeeGeeQ Complete Guide.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EnterpriseIntegrationDemoTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory queueFactory;

    // Integration pattern types
    enum IntegrationPattern {
        MESSAGE_TRANSFORMATION("message-transformation", "Transform message formats"),
        CONTENT_BASED_ROUTING("content-based-routing", "Route based on message content"),
        MESSAGE_AGGREGATION("message-aggregation", "Aggregate related messages"),
        SCATTER_GATHER("scatter-gather", "Distribute and collect responses"),
        SAGA_ORCHESTRATION("saga-orchestration", "Manage distributed transactions");

        final String patternName;
        final String description;

        IntegrationPattern(String patternName, String description) {
            this.patternName = patternName;
            this.description = description;
        }
    }

    // Order processing message for integration patterns
    static class OrderMessage {
        public final String orderId;
        public final String customerId;
        public final String productId;
        public final int quantity;
        public final double unitPrice;
        public final String currency;
        public final String region;
        public final String priority;
        public final JsonObject metadata;
        public final Instant timestamp;

        public OrderMessage(String orderId, String customerId, String productId, int quantity, 
                           double unitPrice, String currency, String region, String priority, 
                           JsonObject metadata) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.productId = productId;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.currency = currency;
            this.region = region;
            this.priority = priority;
            this.metadata = metadata;
            this.timestamp = Instant.now();
        }

        public JsonObject toJson() {
            return new JsonObject()
                    .put("orderId", orderId)
                    .put("customerId", customerId)
                    .put("productId", productId)
                    .put("quantity", quantity)
                    .put("unitPrice", unitPrice)
                    .put("currency", currency)
                    .put("region", region)
                    .put("priority", priority)
                    .put("metadata", metadata)
                    .put("timestamp", timestamp.toString());
        }

        public double getTotalAmount() {
            return quantity * unitPrice;
        }
    }

    // Transformed message for different systems
    static class TransformedMessage {
        public final String messageId;
        public final String sourceSystem;
        public final String targetSystem;
        public final IntegrationPattern pattern;
        public final JsonObject originalData;
        public final JsonObject transformedData;
        public final Instant transformedAt;

        public TransformedMessage(String messageId, String sourceSystem, String targetSystem,
                                IntegrationPattern pattern, JsonObject originalData, JsonObject transformedData) {
            this.messageId = messageId;
            this.sourceSystem = sourceSystem;
            this.targetSystem = targetSystem;
            this.pattern = pattern;
            this.originalData = originalData;
            this.transformedData = transformedData;
            this.transformedAt = Instant.now();
        }

        public JsonObject toJson() {
            return new JsonObject()
                    .put("messageId", messageId)
                    .put("sourceSystem", sourceSystem)
                    .put("targetSystem", targetSystem)
                    .put("pattern", pattern.patternName)
                    .put("originalData", originalData)
                    .put("transformedData", transformedData)
                    .put("transformedAt", transformedAt.toString());
        }
    }

    // Aggregated message combining multiple related messages
    static class AggregatedMessage {
        public final String aggregationId;
        public final String aggregationType;
        public final List<String> sourceMessageIds;
        public final JsonObject aggregatedData;
        public final int messageCount;
        public final Instant aggregatedAt;

        public AggregatedMessage(String aggregationId, String aggregationType, 
                               List<String> sourceMessageIds, JsonObject aggregatedData) {
            this.aggregationId = aggregationId;
            this.aggregationType = aggregationType;
            this.sourceMessageIds = new ArrayList<>(sourceMessageIds);
            this.aggregatedData = aggregatedData;
            this.messageCount = sourceMessageIds.size();
            this.aggregatedAt = Instant.now();
        }

        public JsonObject toJson() {
            // Create the source message IDs map explicitly
            Map<String, Object> sourceIdsMap = new HashMap<>();
            for (int i = 0; i < sourceMessageIds.size(); i++) {
                sourceIdsMap.put(String.valueOf(i), sourceMessageIds.get(i));
            }

            return new JsonObject()
                    .put("aggregationId", aggregationId)
                    .put("aggregationType", aggregationType)
                    .put("sourceMessageIds", new JsonObject(sourceIdsMap))
                    .put("aggregatedData", aggregatedData)
                    .put("messageCount", messageCount)
                    .put("aggregatedAt", aggregatedAt.toString());
        }
    }

    @BeforeEach
    void setUp() {
        System.out.println("\n🔗 Setting up Enterprise Integration Demo Test");
        
        // Configure database connection
        String jdbcUrl = postgres.getJdbcUrl();
        String username = postgres.getUsername();
        String password = postgres.getPassword();

        System.setProperty("peegeeq.database.url", jdbcUrl);
        System.setProperty("peegeeq.database.username", username);
        System.setProperty("peegeeq.database.password", password);

        // Initialize PeeGeeQ with integration configuration
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("development");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create native factory
        var databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        queueFactory = provider.createFactory("native", databaseService);

        System.out.println("✅ Setup complete - Ready for enterprise integration pattern testing");
    }

    @AfterEach
    void tearDown() {
        System.out.println("🧹 Cleaning up Enterprise Integration Demo Test");
        
        if (manager != null) {
            try {
                manager.close();
            } catch (Exception e) {
                System.err.println("⚠️ Error during manager cleanup: " + e.getMessage());
            }
        }

        // Clean up system properties
        System.clearProperty("peegeeq.database.url");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        
        System.out.println("✅ Cleanup complete");
    }

    @Test
    @Order(1)
    @DisplayName("Message Transformation - Converting Between Different Message Formats")
    void testMessageTransformation() throws Exception {
        System.out.println("\n🔄 Testing Message Transformation");

        String inputQueue = "integration-input-queue";
        String outputQueue = "integration-output-queue";
        
        List<TransformedMessage> transformedMessages = new ArrayList<>();
        AtomicInteger messagesProcessed = new AtomicInteger(0);
        CountDownLatch inputLatch = new CountDownLatch(3);
        CountDownLatch outputLatch = new CountDownLatch(3);

        // Create producers and consumers
        MessageProducer<OrderMessage> inputProducer = queueFactory.createProducer(inputQueue, OrderMessage.class);
        MessageConsumer<OrderMessage> inputConsumer = queueFactory.createConsumer(inputQueue, OrderMessage.class);
        MessageProducer<TransformedMessage> outputProducer = queueFactory.createProducer(outputQueue, TransformedMessage.class);
        MessageConsumer<TransformedMessage> outputConsumer = queueFactory.createConsumer(outputQueue, TransformedMessage.class);

        // Input consumer - transforms messages and forwards to output queue
        inputConsumer.subscribe(message -> {
            OrderMessage order = message.getPayload();
            
            System.out.println("🔄 Transforming order: " + order.orderId + " for system integration");
            
            // Transform for different target systems based on region
            JsonObject transformedData;
            String targetSystem;
            
            switch (order.region.toUpperCase()) {
                case "US":
                    // Transform for US ERP system
                    targetSystem = "US-ERP-SYSTEM";
                    transformedData = new JsonObject()
                            .put("order_number", order.orderId)
                            .put("customer_code", order.customerId)
                            .put("item_sku", order.productId)
                            .put("qty", order.quantity)
                            .put("unit_cost", order.unitPrice)
                            .put("total_amount", order.getTotalAmount())
                            .put("currency_code", order.currency)
                            .put("priority_level", order.priority.toLowerCase());
                    break;
                    
                case "EU":
                    // Transform for European system (different field names and structure)
                    targetSystem = "EU-SAP-SYSTEM";
                    transformedData = new JsonObject()
                            .put("bestellnummer", order.orderId)
                            .put("kunde_id", order.customerId)
                            .put("artikel_nummer", order.productId)
                            .put("menge", order.quantity)
                            .put("einzelpreis", order.unitPrice)
                            .put("gesamtbetrag", order.getTotalAmount())
                            .put("waehrung", order.currency)
                            .put("prioritaet", order.priority.toLowerCase());
                    break;
                    
                default:
                    // Transform for APAC system (JSON structure)
                    targetSystem = "APAC-ORACLE-SYSTEM";
                    transformedData = new JsonObject()
                            .put("orderDetails", new JsonObject()
                                .put("id", order.orderId)
                                .put("customerId", order.customerId)
                                .put("product", new JsonObject()
                                    .put("id", order.productId)
                                    .put("quantity", order.quantity)
                                    .put("price", order.unitPrice))
                                .put("total", order.getTotalAmount())
                                .put("currency", order.currency)
                                .put("priority", order.priority));
                    break;
            }
            
            // Create transformed message
            TransformedMessage transformed = new TransformedMessage(
                "txf-" + order.orderId, "ORDER-SYSTEM", targetSystem,
                IntegrationPattern.MESSAGE_TRANSFORMATION, order.toJson(), transformedData
            );
            
            // Send to output queue
            outputProducer.send(transformed);
            
            messagesProcessed.incrementAndGet();
            inputLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Output consumer - collects transformed messages
        outputConsumer.subscribe(message -> {
            TransformedMessage transformed = message.getPayload();
            
            System.out.println("📤 Received transformed message for: " + transformed.targetSystem);
            transformedMessages.add(transformed);
            
            outputLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send orders from different regions
        System.out.println("📤 Sending orders from different regions for transformation...");
        
        // US Order
        OrderMessage usOrder = new OrderMessage(
            "ORD-US-001", "CUST-001", "PROD-001", 5, 99.99, "USD", "US", "HIGH",
            new JsonObject().put("salesChannel", "ONLINE").put("promotion", "SUMMER2024")
        );
        inputProducer.send(usOrder);

        // EU Order
        OrderMessage euOrder = new OrderMessage(
            "ORD-EU-001", "CUST-002", "PROD-002", 3, 149.99, "EUR", "EU", "NORMAL",
            new JsonObject().put("salesChannel", "RETAIL").put("vatIncluded", true)
        );
        inputProducer.send(euOrder);

        // APAC Order
        OrderMessage apacOrder = new OrderMessage(
            "ORD-APAC-001", "CUST-003", "PROD-003", 10, 79.99, "SGD", "APAC", "LOW",
            new JsonObject().put("salesChannel", "MOBILE").put("loyaltyDiscount", 0.1)
        );
        inputProducer.send(apacOrder);

        // Wait for all transformations to complete
        assertTrue(inputLatch.await(30, TimeUnit.SECONDS), "Should process all input messages");
        assertTrue(outputLatch.await(30, TimeUnit.SECONDS), "Should receive all transformed messages");

        // Verify transformations
        assertEquals(3, transformedMessages.size(), "Should have 3 transformed messages");
        assertEquals(3, messagesProcessed.get(), "Should have processed 3 messages");

        System.out.println("📊 Message Transformation Results:");
        for (TransformedMessage msg : transformedMessages) {
            System.out.println("  " + msg.sourceSystem + " -> " + msg.targetSystem + 
                             " (Message ID: " + msg.messageId + ")");
        }

        // Verify different target systems
        Set<String> targetSystems = new HashSet<>();
        transformedMessages.forEach(msg -> targetSystems.add(msg.targetSystem));
        assertEquals(3, targetSystems.size(), "Should have 3 different target systems");

        // Cleanup
        inputConsumer.close();
        outputConsumer.close();

        System.out.println("✅ Message Transformation test completed successfully");
        System.out.println("📊 Total messages transformed: " + messagesProcessed.get());
    }

    @Test
    @Order(2)
    @DisplayName("Content-Based Routing - Routing Messages Based on Content")
    void testContentBasedRouting() throws Exception {
        System.out.println("\n🎯 Testing Content-Based Routing");

        String inputQueue = "routing-input-queue";
        String highPriorityQueue = "routing-high-priority-queue";
        String normalPriorityQueue = "routing-normal-priority-queue";
        String lowPriorityQueue = "routing-low-priority-queue";

        Map<String, List<OrderMessage>> routedMessages = new HashMap<>();
        AtomicInteger messagesRouted = new AtomicInteger(0);
        CountDownLatch inputLatch = new CountDownLatch(6);
        CountDownLatch routingLatch = new CountDownLatch(6);

        // Create producers and consumers
        MessageProducer<OrderMessage> inputProducer = queueFactory.createProducer(inputQueue, OrderMessage.class);
        MessageConsumer<OrderMessage> inputConsumer = queueFactory.createConsumer(inputQueue, OrderMessage.class);

        MessageProducer<OrderMessage> highPriorityProducer = queueFactory.createProducer(highPriorityQueue, OrderMessage.class);
        MessageProducer<OrderMessage> normalPriorityProducer = queueFactory.createProducer(normalPriorityQueue, OrderMessage.class);
        MessageProducer<OrderMessage> lowPriorityProducer = queueFactory.createProducer(lowPriorityQueue, OrderMessage.class);

        MessageConsumer<OrderMessage> highPriorityConsumer = queueFactory.createConsumer(highPriorityQueue, OrderMessage.class);
        MessageConsumer<OrderMessage> normalPriorityConsumer = queueFactory.createConsumer(normalPriorityQueue, OrderMessage.class);
        MessageConsumer<OrderMessage> lowPriorityConsumer = queueFactory.createConsumer(lowPriorityQueue, OrderMessage.class);

        // Input consumer - routes messages based on content
        inputConsumer.subscribe(message -> {
            OrderMessage order = message.getPayload();

            System.out.println("🎯 Routing order: " + order.orderId + " based on content analysis");

            // Content-based routing logic
            String routingDecision = determineRoute(order);

            switch (routingDecision) {
                case "HIGH_PRIORITY":
                    highPriorityProducer.send(order);
                    System.out.println("  -> Routed to HIGH PRIORITY queue: " + getRoutingReason(order));
                    break;
                case "NORMAL_PRIORITY":
                    normalPriorityProducer.send(order);
                    System.out.println("  -> Routed to NORMAL PRIORITY queue: " + getRoutingReason(order));
                    break;
                case "LOW_PRIORITY":
                    lowPriorityProducer.send(order);
                    System.out.println("  -> Routed to LOW PRIORITY queue: " + getRoutingReason(order));
                    break;
            }

            messagesRouted.incrementAndGet();
            inputLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Priority queue consumers
        highPriorityConsumer.subscribe(message -> {
            OrderMessage order = message.getPayload();
            routedMessages.computeIfAbsent("HIGH_PRIORITY", k -> new ArrayList<>()).add(order);
            System.out.println("🔥 HIGH PRIORITY consumer processed: " + order.orderId);
            routingLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        normalPriorityConsumer.subscribe(message -> {
            OrderMessage order = message.getPayload();
            routedMessages.computeIfAbsent("NORMAL_PRIORITY", k -> new ArrayList<>()).add(order);
            System.out.println("⚡ NORMAL PRIORITY consumer processed: " + order.orderId);
            routingLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        lowPriorityConsumer.subscribe(message -> {
            OrderMessage order = message.getPayload();
            routedMessages.computeIfAbsent("LOW_PRIORITY", k -> new ArrayList<>()).add(order);
            System.out.println("🐌 LOW PRIORITY consumer processed: " + order.orderId);
            routingLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send orders with different characteristics for routing
        System.out.println("📤 Sending orders with different characteristics for content-based routing...");

        // High priority: Large order amount + VIP customer
        OrderMessage vipOrder = new OrderMessage(
            "ORD-VIP-001", "VIP-CUST-001", "PROD-PREMIUM", 50, 999.99, "USD", "US", "CRITICAL",
            new JsonObject().put("customerTier", "VIP").put("expeditedShipping", true)
        );
        inputProducer.send(vipOrder);

        // High priority: Critical priority flag
        OrderMessage criticalOrder = new OrderMessage(
            "ORD-CRIT-001", "CUST-002", "PROD-URGENT", 1, 49.99, "USD", "US", "CRITICAL",
            new JsonObject().put("customerTier", "STANDARD").put("urgentDelivery", true)
        );
        inputProducer.send(criticalOrder);

        // Normal priority: Standard order
        OrderMessage standardOrder1 = new OrderMessage(
            "ORD-STD-001", "CUST-003", "PROD-STANDARD", 3, 199.99, "USD", "US", "NORMAL",
            new JsonObject().put("customerTier", "STANDARD").put("standardShipping", true)
        );
        inputProducer.send(standardOrder1);

        // Normal priority: Medium amount
        OrderMessage standardOrder2 = new OrderMessage(
            "ORD-STD-002", "CUST-004", "PROD-REGULAR", 5, 149.99, "EUR", "EU", "NORMAL",
            new JsonObject().put("customerTier", "BRONZE").put("promotion", "SPRING2024")
        );
        inputProducer.send(standardOrder2);

        // Low priority: Small order amount
        OrderMessage lowOrder1 = new OrderMessage(
            "ORD-LOW-001", "CUST-005", "PROD-BASIC", 1, 19.99, "USD", "US", "LOW",
            new JsonObject().put("customerTier", "BASIC").put("freeShipping", false)
        );
        inputProducer.send(lowOrder1);

        // Low priority: Bulk order with low unit price
        OrderMessage lowOrder2 = new OrderMessage(
            "ORD-BULK-001", "CUST-006", "PROD-BULK", 100, 2.99, "USD", "US", "LOW",
            new JsonObject().put("customerTier", "BULK").put("bulkDiscount", 0.2)
        );
        inputProducer.send(lowOrder2);

        // Wait for all routing to complete
        assertTrue(inputLatch.await(30, TimeUnit.SECONDS), "Should route all input messages");
        assertTrue(routingLatch.await(30, TimeUnit.SECONDS), "Should process all routed messages");

        // Verify routing results
        assertEquals(6, messagesRouted.get(), "Should have routed 6 messages");

        System.out.println("📊 Content-Based Routing Results:");
        for (Map.Entry<String, List<OrderMessage>> entry : routedMessages.entrySet()) {
            System.out.println("  " + entry.getKey() + ": " + entry.getValue().size() + " messages");
            for (OrderMessage order : entry.getValue()) {
                System.out.println("    - " + order.orderId + " ($" + order.getTotalAmount() + ")");
            }
        }

        // Verify expected routing
        assertTrue(routedMessages.containsKey("HIGH_PRIORITY"), "Should have high priority messages");
        assertTrue(routedMessages.containsKey("NORMAL_PRIORITY"), "Should have normal priority messages");
        assertTrue(routedMessages.containsKey("LOW_PRIORITY"), "Should have low priority messages");

        assertEquals(2, routedMessages.get("HIGH_PRIORITY").size(), "Should have 2 high priority messages");
        assertEquals(2, routedMessages.get("NORMAL_PRIORITY").size(), "Should have 2 normal priority messages");
        assertEquals(2, routedMessages.get("LOW_PRIORITY").size(), "Should have 2 low priority messages");

        // Cleanup
        inputConsumer.close();
        highPriorityConsumer.close();
        normalPriorityConsumer.close();
        lowPriorityConsumer.close();

        System.out.println("✅ Content-Based Routing test completed successfully");
        System.out.println("📊 Total messages routed: " + messagesRouted.get());
    }

    private String determineRoute(OrderMessage order) {
        // Complex routing logic based on multiple factors
        double totalAmount = order.getTotalAmount();
        String customerTier = order.metadata.getString("customerTier", "STANDARD");
        String priority = order.priority.toUpperCase();

        // High priority conditions
        if (priority.equals("CRITICAL") ||
            customerTier.equals("VIP") ||
            totalAmount > 10000.0 ||
            order.metadata.getBoolean("expeditedShipping", false) ||
            order.metadata.getBoolean("urgentDelivery", false)) {
            return "HIGH_PRIORITY";
        }

        // Low priority conditions
        if (priority.equals("LOW") ||
            totalAmount < 100.0 ||
            customerTier.equals("BASIC") ||
            customerTier.equals("BULK")) {
            return "LOW_PRIORITY";
        }

        // Default to normal priority
        return "NORMAL_PRIORITY";
    }

    private String getRoutingReason(OrderMessage order) {
        double totalAmount = order.getTotalAmount();
        String customerTier = order.metadata.getString("customerTier", "STANDARD");
        String priority = order.priority.toUpperCase();

        if (priority.equals("CRITICAL")) return "Critical priority flag";
        if (customerTier.equals("VIP")) return "VIP customer";
        if (totalAmount > 10000.0) return "Large order amount ($" + totalAmount + ")";
        if (order.metadata.getBoolean("expeditedShipping", false)) return "Expedited shipping requested";
        if (order.metadata.getBoolean("urgentDelivery", false)) return "Urgent delivery requested";
        if (totalAmount < 100.0) return "Small order amount ($" + totalAmount + ")";
        if (customerTier.equals("BASIC")) return "Basic customer tier";
        if (customerTier.equals("BULK")) return "Bulk customer tier";

        return "Standard routing criteria";
    }
}
