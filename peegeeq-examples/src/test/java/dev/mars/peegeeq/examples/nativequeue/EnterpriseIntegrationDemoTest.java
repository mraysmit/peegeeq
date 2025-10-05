package dev.mars.peegeeq.examples.nativequeue;

import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.json.JsonObject;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();

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
            // Clear system properties

            clearSystemProperties();

        }
        // Clear system properties

        clearSystemProperties();

    }

    // Order processing message for integration patterns
    static class OrderMessage {
        private String orderId;
        private String customerId;
        private String productId;
        private int quantity;
        private double unitPrice;
        private String currency;
        private String region;
        private String priority;
        private Map<String, Object> metadata;
        private String timestamp;

        public OrderMessage() {    // Clear system properties
    clearSystemProperties();
} // Default constructor for Jackson

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
            this.metadata = metadata.getMap();
            this.timestamp = Instant.now().toString();
            // Clear system properties

            clearSystemProperties();

        }

        // Getters and setters
        public String getOrderId() { return orderId;     // Clear system properties
     clearSystemProperties();
 }
        public void setOrderId(String orderId) { this.orderId = orderId;     // Clear system properties
     clearSystemProperties();
 }

        public String getCustomerId() { return customerId;     // Clear system properties
     clearSystemProperties();
 }
        public void setCustomerId(String customerId) { this.customerId = customerId;     // Clear system properties
     clearSystemProperties();
 }

        public String getProductId() { return productId;     // Clear system properties
     clearSystemProperties();
 }
        public void setProductId(String productId) { this.productId = productId;     // Clear system properties
     clearSystemProperties();
 }

        public int getQuantity() { return quantity;     // Clear system properties
     clearSystemProperties();
 }
        public void setQuantity(int quantity) { this.quantity = quantity;     // Clear system properties
     clearSystemProperties();
 }

        public double getUnitPrice() { return unitPrice;     // Clear system properties
     clearSystemProperties();
 }
        public void setUnitPrice(double unitPrice) { this.unitPrice = unitPrice;     // Clear system properties
     clearSystemProperties();
 }

        public String getCurrency() { return currency;     // Clear system properties
     clearSystemProperties();
 }
        public void setCurrency(String currency) { this.currency = currency;     // Clear system properties
     clearSystemProperties();
 }

        public String getRegion() { return region;     // Clear system properties
     clearSystemProperties();
 }
        public void setRegion(String region) { this.region = region;     // Clear system properties
     clearSystemProperties();
 }

        public String getPriority() { return priority;     // Clear system properties
     clearSystemProperties();
 }
        public void setPriority(String priority) { this.priority = priority;     // Clear system properties
     clearSystemProperties();
 }

        public Map<String, Object> getMetadata() { return metadata;     // Clear system properties
     clearSystemProperties();
 }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata;     // Clear system properties
     clearSystemProperties();
 }

        public String getTimestamp() { return timestamp;     // Clear system properties
     clearSystemProperties();
 }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp;     // Clear system properties
     clearSystemProperties();
 }

        public JsonObject toJson() {
            Map<String, Object> map = new HashMap<>();
            map.put("orderId", orderId);
            map.put("customerId", customerId);
            map.put("productId", productId);
            map.put("quantity", quantity);
            map.put("unitPrice", unitPrice);
            map.put("currency", currency);
            map.put("region", region);
            map.put("priority", priority);
            map.put("metadata", metadata);
            map.put("timestamp", timestamp);
            return new JsonObject(map);
            // Clear system properties

            clearSystemProperties();

        }

        @JsonIgnore
        public double getTotalAmount() {
            return quantity * unitPrice;
            // Clear system properties

            clearSystemProperties();

        }
        // Clear system properties

        clearSystemProperties();

    }

    // Transformed message for different systems
    static class TransformedMessage {
        private String messageId;
        private String sourceSystem;
        private String targetSystem;
        private IntegrationPattern pattern;
        private Map<String, Object> originalData;
        private Map<String, Object> transformedData;
        private String transformedAt;

        public TransformedMessage() {    // Clear system properties
    clearSystemProperties();
} // Default constructor for Jackson

        public TransformedMessage(String messageId, String sourceSystem, String targetSystem,
                                IntegrationPattern pattern, JsonObject originalData, JsonObject transformedData) {
            this.messageId = messageId;
            this.sourceSystem = sourceSystem;
            this.targetSystem = targetSystem;
            this.pattern = pattern;
            this.originalData = originalData.getMap();
            this.transformedData = transformedData.getMap();
            this.transformedAt = Instant.now().toString();
            // Clear system properties

            clearSystemProperties();

        }

        // Getters and setters
        public String getMessageId() { return messageId;     // Clear system properties
     clearSystemProperties();
 }
        public void setMessageId(String messageId) { this.messageId = messageId;     // Clear system properties
     clearSystemProperties();
 }

        public String getSourceSystem() { return sourceSystem;     // Clear system properties
     clearSystemProperties();
 }
        public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem;     // Clear system properties
     clearSystemProperties();
 }

        public String getTargetSystem() { return targetSystem;     // Clear system properties
     clearSystemProperties();
 }
        public void setTargetSystem(String targetSystem) { this.targetSystem = targetSystem;     // Clear system properties
     clearSystemProperties();
 }

        public IntegrationPattern getPattern() { return pattern;     // Clear system properties
     clearSystemProperties();
 }
        public void setPattern(IntegrationPattern pattern) { this.pattern = pattern;     // Clear system properties
     clearSystemProperties();
 }

        public Map<String, Object> getOriginalData() { return originalData;     // Clear system properties
     clearSystemProperties();
 }
        public void setOriginalData(Map<String, Object> originalData) { this.originalData = originalData;     // Clear system properties
     clearSystemProperties();
 }

        public Map<String, Object> getTransformedData() { return transformedData;     // Clear system properties
     clearSystemProperties();
 }
        public void setTransformedData(Map<String, Object> transformedData) { this.transformedData = transformedData;     // Clear system properties
     clearSystemProperties();
 }

        public String getTransformedAt() { return transformedAt;     // Clear system properties
     clearSystemProperties();
 }
        public void setTransformedAt(String transformedAt) { this.transformedAt = transformedAt;     // Clear system properties
     clearSystemProperties();
 }

        public JsonObject toJson() {
            return new JsonObject()
                    .put("messageId", messageId)
                    .put("sourceSystem", sourceSystem)
                    .put("targetSystem", targetSystem)
                    .put("pattern", pattern.patternName)
                    .put("originalData", originalData)
                    .put("transformedData", transformedData)
                    .put("transformedAt", transformedAt);
            // Clear system properties

            clearSystemProperties();

        }
        // Clear system properties

        clearSystemProperties();

    }

    // Aggregated message combining multiple related messages
    static class AggregatedMessage {
        public final String aggregationId;
        public final String aggregationType;
        public final List<String> sourceMessageIds;
        public final JsonObject aggregatedData;
        public final int messageCount;
        public final String aggregatedAt;

        public AggregatedMessage(String aggregationId, String aggregationType,
                               List<String> sourceMessageIds, JsonObject aggregatedData) {
            this.aggregationId = aggregationId;
            this.aggregationType = aggregationType;
            this.sourceMessageIds = new ArrayList<>(sourceMessageIds);
            this.aggregatedData = aggregatedData;
            this.messageCount = sourceMessageIds.size();
            this.aggregatedAt = Instant.now().toString();
            // Clear system properties

            clearSystemProperties();

        }

        public JsonObject toJson() {
            // Create the source message IDs map explicitly
            Map<String, Object> sourceIdsMap = new HashMap<>();
            for (int i = 0; i < sourceMessageIds.size(); i++) {
                sourceIdsMap.put(String.valueOf(i), sourceMessageIds.get(i));
                // Clear system properties

                clearSystemProperties();

            }

            return new JsonObject()
                    .put("aggregationId", aggregationId)
                    .put("aggregationType", aggregationType)
                    .put("sourceMessageIds", new JsonObject(sourceIdsMap))
                    .put("aggregatedData", aggregatedData)
                    .put("messageCount", messageCount)
                    .put("aggregatedAt", aggregatedAt);
            // Clear system properties

            clearSystemProperties();

        }
        // Clear system properties

        clearSystemProperties();

    }

    @BeforeEach
    void setUp() {
        // Configure system properties for TestContainers PostgreSQL connection
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        System.out.println("\n🔗 Setting up Enterprise Integration Demo Test");

        // Configure system properties for TestContainers
        configureSystemPropertiesForContainer(postgres);

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
        // Clear system properties

        clearSystemProperties();

    }

    @AfterEach
    void tearDown() {
        System.out.println("🧹 Cleaning up Enterprise Integration Demo Test");
        
        if (manager != null) {
            try {
                manager.close();
                // Clear system properties

                clearSystemProperties();

            } catch (Exception e) {
                System.err.println("⚠️ Error during manager cleanup: " + e.getMessage());
                // Clear system properties

                clearSystemProperties();

            }
            // Clear system properties

            clearSystemProperties();

        }

        // Clean up system properties
        System.clearProperty("peegeeq.database.url");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        
        System.out.println("✅ Cleanup complete");
        // Clear system properties

        clearSystemProperties();

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
            
            System.out.println("🔄 Transforming order: " + order.getOrderId() + " for system integration");

            // Transform for different target systems based on region
            JsonObject transformedData;
            String targetSystem;

            switch (order.getRegion().toUpperCase()) {
                case "US":
                    // Transform for US ERP system
                    targetSystem = "US-ERP-SYSTEM";
                    transformedData = new JsonObject()
                            .put("order_number", order.getOrderId())
                            .put("customer_code", order.getCustomerId())
                            .put("item_sku", order.getProductId())
                            .put("qty", order.getQuantity())
                            .put("unit_cost", order.getUnitPrice())
                            .put("total_amount", order.getTotalAmount())
                            .put("currency_code", order.getCurrency())
                            .put("priority_level", order.getPriority().toLowerCase());
                    break;
                    
                case "EU":
                    // Transform for European system (different field names and structure)
                    targetSystem = "EU-SAP-SYSTEM";
                    transformedData = new JsonObject()
                            .put("bestellnummer", order.getOrderId())
                            .put("kunde_id", order.getCustomerId())
                            .put("artikel_nummer", order.getProductId())
                            .put("menge", order.getQuantity())
                            .put("einzelpreis", order.getUnitPrice())
                            .put("gesamtbetrag", order.getTotalAmount())
                            .put("waehrung", order.getCurrency())
                            .put("prioritaet", order.getPriority().toLowerCase());
                    break;
                    
                default:
                    // Transform for APAC system (JSON structure)
                    targetSystem = "APAC-ORACLE-SYSTEM";
                    transformedData = new JsonObject()
                            .put("orderDetails", new JsonObject()
                                .put("id", order.getOrderId())
                                .put("customerId", order.getCustomerId())
                                .put("product", new JsonObject()
                                    .put("id", order.getProductId())
                                    .put("quantity", order.getQuantity())
                                    .put("price", order.getUnitPrice()))
                                .put("total", order.getTotalAmount())
                                .put("currency", order.getCurrency())
                                .put("priority", order.getPriority()));
                    break;
                // Clear system properties

                clearSystemProperties();

            }
            
            // Create transformed message
            TransformedMessage transformed = new TransformedMessage(
                "txf-" + order.getOrderId(), "ORDER-SYSTEM", targetSystem,
                IntegrationPattern.MESSAGE_TRANSFORMATION, order.toJson(), transformedData
            );
            
            // Send to output queue
            outputProducer.send(transformed);
            
            messagesProcessed.incrementAndGet();
            inputLatch.countDown();
            return CompletableFuture.completedFuture(null);
            // Clear system properties

            clearSystemProperties();

        });

        // Output consumer - collects transformed messages
        outputConsumer.subscribe(message -> {
            TransformedMessage transformed = message.getPayload();
            
            System.out.println("📤 Received transformed message for: " + transformed.targetSystem);
            transformedMessages.add(transformed);
            
            outputLatch.countDown();
            return CompletableFuture.completedFuture(null);
            // Clear system properties

            clearSystemProperties();

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
            // Clear system properties

            clearSystemProperties();

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
        // Clear system properties

        clearSystemProperties();

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

            System.out.println("🎯 Routing order: " + order.getOrderId() + " based on content analysis");

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
                // Clear system properties

                clearSystemProperties();

            }

            messagesRouted.incrementAndGet();
            inputLatch.countDown();
            return CompletableFuture.completedFuture(null);
            // Clear system properties

            clearSystemProperties();

        });

        // Priority queue consumers
        highPriorityConsumer.subscribe(message -> {
            OrderMessage order = message.getPayload();
            routedMessages.computeIfAbsent("HIGH_PRIORITY", k -> new ArrayList<>()).add(order);
            System.out.println("🔥 HIGH PRIORITY consumer processed: " + order.getOrderId());
            routingLatch.countDown();
            return CompletableFuture.completedFuture(null);
            // Clear system properties

            clearSystemProperties();

        });

        normalPriorityConsumer.subscribe(message -> {
            OrderMessage order = message.getPayload();
            routedMessages.computeIfAbsent("NORMAL_PRIORITY", k -> new ArrayList<>()).add(order);
            System.out.println("⚡ NORMAL PRIORITY consumer processed: " + order.getOrderId());
            routingLatch.countDown();
            return CompletableFuture.completedFuture(null);
            // Clear system properties

            clearSystemProperties();

        });

        lowPriorityConsumer.subscribe(message -> {
            OrderMessage order = message.getPayload();
            routedMessages.computeIfAbsent("LOW_PRIORITY", k -> new ArrayList<>()).add(order);
            System.out.println("🐌 LOW PRIORITY consumer processed: " + order.getOrderId());
            routingLatch.countDown();
            return CompletableFuture.completedFuture(null);
            // Clear system properties

            clearSystemProperties();

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
                System.out.println("    - " + order.getOrderId() + " ($" + order.getTotalAmount() + ")");
                // Clear system properties

                clearSystemProperties();

            }
            // Clear system properties

            clearSystemProperties();

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
        // Clear system properties

        clearSystemProperties();

    }

    private String determineRoute(OrderMessage order) {
        // Complex routing logic based on multiple factors
        double totalAmount = order.getTotalAmount();
        String customerTier = (String) order.getMetadata().getOrDefault("customerTier", "STANDARD");
        String priority = order.getPriority().toUpperCase();

        // High priority conditions
        if (priority.equals("CRITICAL") ||
            customerTier.equals("VIP") ||
            totalAmount > 10000.0 ||
            Boolean.TRUE.equals(order.getMetadata().get("expeditedShipping")) ||
            Boolean.TRUE.equals(order.getMetadata().get("urgentDelivery"))) {
            return "HIGH_PRIORITY";
            // Clear system properties

            clearSystemProperties();

        }

        // Low priority conditions
        if (priority.equals("LOW") ||
            totalAmount < 100.0 ||
            customerTier.equals("BASIC") ||
            customerTier.equals("BULK")) {
            return "LOW_PRIORITY";
            // Clear system properties

            clearSystemProperties();

        }

        // Default to normal priority
        return "NORMAL_PRIORITY";
        // Clear system properties

        clearSystemProperties();

    }

    private String getRoutingReason(OrderMessage order) {
        double totalAmount = order.getTotalAmount();
        String customerTier = (String) order.getMetadata().getOrDefault("customerTier", "STANDARD");
        String priority = order.getPriority().toUpperCase();

        if (priority.equals("CRITICAL")) return "Critical priority flag";
        if (customerTier.equals("VIP")) return "VIP customer";
        if (totalAmount > 10000.0) return "Large order amount ($" + totalAmount + ")";
        if (Boolean.TRUE.equals(order.getMetadata().get("expeditedShipping"))) return "Expedited shipping requested";
        if (Boolean.TRUE.equals(order.getMetadata().get("urgentDelivery"))) return "Urgent delivery requested";
        if (totalAmount < 100.0) return "Small order amount ($" + totalAmount + ")";
        if (customerTier.equals("BASIC")) return "Basic customer tier";
        if (customerTier.equals("BULK")) return "Bulk customer tier";

        return "Standard routing criteria";
        // Clear system properties

        clearSystemProperties();

    }

    /**
     * Configures system properties to use the TestContainer database.
     */
    private void configureSystemPropertiesForContainer(PostgreSQLContainer<?> postgres) {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Additional properties for integration testing
        System.setProperty("peegeeq.database.maxPoolSize", "10");
        System.setProperty("peegeeq.database.connectionTimeout", "30000");
        System.setProperty("peegeeq.database.idleTimeout", "600000");
        // Clear system properties

        clearSystemProperties();

    }
    // Clear system properties

    clearSystemProperties();

/**


 * Clear system properties after test completion


 */


private void clearSystemProperties() {


    System.clearProperty("peegeeq.database.host");


    System.clearProperty("peegeeq.database.port");


    System.clearProperty("peegeeq.database.name");


    System.clearProperty("peegeeq.database.username");


    System.clearProperty("peegeeq.database.password");


}

}
