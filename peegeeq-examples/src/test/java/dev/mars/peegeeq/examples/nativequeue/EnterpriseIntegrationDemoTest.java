package dev.mars.peegeeq.examples.nativequeue;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.json.JsonObject;
import io.vertx.core.Future;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.junit.jupiter.api.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.*;

import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@org.junit.jupiter.api.extension.ExtendWith(VertxExtension.class)
class EnterpriseIntegrationDemoTest {
    private static final Logger logger = LoggerFactory.getLogger(EnterpriseIntegrationDemoTest.class);


    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }

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

        public OrderMessage() {} // Default constructor for Jackson

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
        }

        // Getters and setters
        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }

        public String getCustomerId() { return customerId; }
        public void setCustomerId(String customerId) { this.customerId = customerId; }

        public String getProductId() { return productId; }
        public void setProductId(String productId) { this.productId = productId; }

        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }

        public double getUnitPrice() { return unitPrice; }
        public void setUnitPrice(double unitPrice) { this.unitPrice = unitPrice; }

        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }

        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }

        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }

        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

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
        }

        @JsonIgnore
        public double getTotalAmount() {
            return quantity * unitPrice;
        }
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

        public TransformedMessage() {} // Default constructor for Jackson

        public TransformedMessage(String messageId, String sourceSystem, String targetSystem,
                                IntegrationPattern pattern, JsonObject originalData, JsonObject transformedData) {
            this.messageId = messageId;
            this.sourceSystem = sourceSystem;
            this.targetSystem = targetSystem;
            this.pattern = pattern;
            this.originalData = originalData.getMap();
            this.transformedData = transformedData.getMap();
            this.transformedAt = Instant.now().toString();
        }

        // Getters and setters
        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }

        public String getSourceSystem() { return sourceSystem; }
        public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; }

        public String getTargetSystem() { return targetSystem; }
        public void setTargetSystem(String targetSystem) { this.targetSystem = targetSystem; }

        public IntegrationPattern getPattern() { return pattern; }
        public void setPattern(IntegrationPattern pattern) { this.pattern = pattern; }

        public Map<String, Object> getOriginalData() { return originalData; }
        public void setOriginalData(Map<String, Object> originalData) { this.originalData = originalData; }

        public Map<String, Object> getTransformedData() { return transformedData; }
        public void setTransformedData(Map<String, Object> transformedData) { this.transformedData = transformedData; }

        public String getTransformedAt() { return transformedAt; }
        public void setTransformedAt(String transformedAt) { this.transformedAt = transformedAt; }

        public JsonObject toJson() {
            return new JsonObject()
                    .put("messageId", messageId)
                    .put("sourceSystem", sourceSystem)
                    .put("targetSystem", targetSystem)
                    .put("pattern", pattern.patternName)
                    .put("originalData", originalData)
                    .put("transformedData", transformedData)
                    .put("transformedAt", transformedAt);
        }
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
                    .put("aggregatedAt", aggregatedAt);
        }
    }

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        logger.info("Setting up Enterprise Integration Demo Test");

        // Configure database connection properties
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA).build();

        // Initialize database schema for enterprise integration test
        logger.info("Initializing database schema for enterprise integration test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer (ALL components)");

        // Initialize PeeGeeQ with integration configuration
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().onSuccess(v -> {
            // Create native factory
            var databaseService = new PgDatabaseService(manager);
            QueueFactoryProvider provider = new PgQueueFactoryProvider();

            // Register native factory implementation
            PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

            queueFactory = provider.createFactory("native", databaseService);

            logger.info("Setup complete - Ready for enterprise integration pattern testing");
            testContext.completeNow();
        }).onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        logger.info("Cleaning up Enterprise Integration Demo Test");
        (manager != null ? manager.closeReactive() : io.vertx.core.Future.succeededFuture())
                .onSuccess(v -> {
                    logger.info("Cleanup complete");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Message Transformation - Converting Between Different Message Formats")
    void testMessageTransformation(VertxTestContext testContext) throws Exception {
        logger.info("Test: message transformation");
        logger.info("Testing Message Transformation");

        String inputQueue = "integration-input-queue";
        String outputQueue = "integration-output-queue";
        
        List<TransformedMessage> transformedMessages = new ArrayList<>();
        AtomicInteger messagesProcessed = new AtomicInteger(0);
        var inputCheckpoint = testContext.checkpoint(3);
        var outputCheckpoint = testContext.checkpoint(3);

        // Create producers and consumers
        MessageProducer<OrderMessage> inputProducer = queueFactory.createProducer(inputQueue, OrderMessage.class);
        MessageConsumer<OrderMessage> inputConsumer = queueFactory.createConsumer(inputQueue, OrderMessage.class);
        MessageProducer<TransformedMessage> outputProducer = queueFactory.createProducer(outputQueue, TransformedMessage.class);
        MessageConsumer<TransformedMessage> outputConsumer = queueFactory.createConsumer(outputQueue, TransformedMessage.class);

        // Input consumer - transforms messages and forwards to output queue
        inputConsumer.subscribe(message -> {
            OrderMessage order = message.getPayload();
            
            logger.info("Transforming order: {} for system integration", order.getOrderId());

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
            }
            
            // Create transformed message
            TransformedMessage transformed = new TransformedMessage(
                "txf-" + order.getOrderId(), "ORDER-SYSTEM", targetSystem,
                IntegrationPattern.MESSAGE_TRANSFORMATION, order.toJson(), transformedData
            );
            
            // Send to output queue
            outputProducer.send(transformed)
                    .onFailure(testContext::failNow);
            
            messagesProcessed.incrementAndGet();
            inputCheckpoint.flag();
            return Future.succeededFuture();
        });

        // Output consumer - collects transformed messages
        outputConsumer.subscribe(message -> {
            TransformedMessage transformed = message.getPayload();
            
            logger.info("Received transformed message for: {}", transformed.targetSystem);
            transformedMessages.add(transformed);
            
            outputCheckpoint.flag();
            return Future.succeededFuture();
        });

        // Send orders from different regions
        logger.info("Sending orders from different regions for transformation...");
        
        // US Order
        OrderMessage usOrder = new OrderMessage(
            "ORD-US-001", "CUST-001", "PROD-001", 5, 99.99, "USD", "US", "HIGH",
            new JsonObject().put("salesChannel", "ONLINE").put("promotion", "SUMMER2024")
        );
        inputProducer.send(usOrder)
                .onFailure(testContext::failNow);

        // EU Order
        OrderMessage euOrder = new OrderMessage(
            "ORD-EU-001", "CUST-002", "PROD-002", 3, 149.99, "EUR", "EU", "NORMAL",
            new JsonObject().put("salesChannel", "RETAIL").put("vatIncluded", true)
        );
        inputProducer.send(euOrder)
                .onFailure(testContext::failNow);

        // APAC Order
        OrderMessage apacOrder = new OrderMessage(
            "ORD-APAC-001", "CUST-003", "PROD-003", 10, 79.99, "SGD", "APAC", "LOW",
            new JsonObject().put("salesChannel", "MOBILE").put("loyaltyDiscount", 0.1)
        );
        inputProducer.send(apacOrder)
                .onFailure(testContext::failNow);

        // Wait for all transformations to complete
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Should process all transformations");

        // Verify transformations
        assertEquals(3, transformedMessages.size(), "Should have 3 transformed messages");
        assertEquals(3, messagesProcessed.get(), "Should have processed 3 messages");

        logger.info("Message Transformation Results:");
        for (TransformedMessage msg : transformedMessages) {
            logger.info("  {} -> {} (Message ID: {})", msg.sourceSystem, msg.targetSystem, msg.messageId);
        }

        // Verify different target systems
        Set<String> targetSystems = new HashSet<>();
        transformedMessages.forEach(msg -> targetSystems.add(msg.targetSystem));
        assertEquals(3, targetSystems.size(), "Should have 3 different target systems");

        // Cleanup
        inputConsumer.close();
        outputConsumer.close();

        logger.info("Message Transformation test completed successfully");
        logger.info("Total messages transformed: {}", messagesProcessed.get());
    }

    @Test
    @DisplayName("Content-Based Routing - Routing Messages Based on Content")
    void testContentBasedRouting(VertxTestContext testContext) throws Exception {
        logger.info("Test: content based routing");
        logger.info("Testing Content-Based Routing");

        String inputQueue = "routing-input-queue";
        String highPriorityQueue = "routing-high-priority-queue";
        String normalPriorityQueue = "routing-normal-priority-queue";
        String lowPriorityQueue = "routing-low-priority-queue";

        Map<String, List<OrderMessage>> routedMessages = new HashMap<>();
        AtomicInteger messagesRouted = new AtomicInteger(0);
        var inputCheckpoint2 = testContext.checkpoint(6);
        var routingCheckpoint = testContext.checkpoint(6);

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

            logger.info("Routing order: {} based on content analysis", order.getOrderId());

            // Content-based routing logic
            String routingDecision = determineRoute(order);

            switch (routingDecision) {
                case "HIGH_PRIORITY":
                    highPriorityProducer.send(order)
                            .onFailure(testContext::failNow);
                    logger.info("  -> Routed to HIGH PRIORITY queue: {}", getRoutingReason(order));
                    break;
                case "NORMAL_PRIORITY":
                    normalPriorityProducer.send(order)
                            .onFailure(testContext::failNow);
                    logger.info("  -> Routed to NORMAL PRIORITY queue: {}", getRoutingReason(order));
                    break;
                case "LOW_PRIORITY":
                    lowPriorityProducer.send(order)
                            .onFailure(testContext::failNow);
                    logger.info("  -> Routed to LOW PRIORITY queue: {}", getRoutingReason(order));
                    break;
            }

            messagesRouted.incrementAndGet();
            inputCheckpoint2.flag();
            return Future.succeededFuture();
        });

        // Priority queue consumers
        highPriorityConsumer.subscribe(message -> {
            OrderMessage order = message.getPayload();
            routedMessages.computeIfAbsent("HIGH_PRIORITY", k -> new ArrayList<>()).add(order);
            logger.info("HIGH PRIORITY consumer processed: {}", order.getOrderId());
            routingCheckpoint.flag();
            return Future.succeededFuture();
        });

        normalPriorityConsumer.subscribe(message -> {
            OrderMessage order = message.getPayload();
            routedMessages.computeIfAbsent("NORMAL_PRIORITY", k -> new ArrayList<>()).add(order);
            logger.info("NORMAL PRIORITY consumer processed: {}", order.getOrderId());
            routingCheckpoint.flag();
            return Future.succeededFuture();
        });

        lowPriorityConsumer.subscribe(message -> {
            OrderMessage order = message.getPayload();
            routedMessages.computeIfAbsent("LOW_PRIORITY", k -> new ArrayList<>()).add(order);
            logger.info("LOW PRIORITY consumer processed: {}", order.getOrderId());
            routingCheckpoint.flag();
            return Future.succeededFuture();
        });

        // Send orders with different characteristics for routing
        logger.info("Sending orders with different characteristics for content-based routing...");

        // High priority: Large order amount + VIP customer
        OrderMessage vipOrder = new OrderMessage(
            "ORD-VIP-001", "VIP-CUST-001", "PROD-PREMIUM", 50, 999.99, "USD", "US", "CRITICAL",
            new JsonObject().put("customerTier", "VIP").put("expeditedShipping", true)
        );
        inputProducer.send(vipOrder)
                .onFailure(testContext::failNow);

        // High priority: Critical priority flag
        OrderMessage criticalOrder = new OrderMessage(
            "ORD-CRIT-001", "CUST-002", "PROD-URGENT", 1, 49.99, "USD", "US", "CRITICAL",
            new JsonObject().put("customerTier", "STANDARD").put("urgentDelivery", true)
        );
        inputProducer.send(criticalOrder)
                .onFailure(testContext::failNow);

        // Normal priority: Standard order
        OrderMessage standardOrder1 = new OrderMessage(
            "ORD-STD-001", "CUST-003", "PROD-STANDARD", 3, 199.99, "USD", "US", "NORMAL",
            new JsonObject().put("customerTier", "STANDARD").put("standardShipping", true)
        );
        inputProducer.send(standardOrder1)
                .onFailure(testContext::failNow);

        // Normal priority: Medium amount
        OrderMessage standardOrder2 = new OrderMessage(
            "ORD-STD-002", "CUST-004", "PROD-REGULAR", 5, 149.99, "EUR", "EU", "NORMAL",
            new JsonObject().put("customerTier", "BRONZE").put("promotion", "SPRING2024")
        );
        inputProducer.send(standardOrder2)
                .onFailure(testContext::failNow);

        // Low priority: Small order amount
        OrderMessage lowOrder1 = new OrderMessage(
            "ORD-LOW-001", "CUST-005", "PROD-BASIC", 1, 19.99, "USD", "US", "LOW",
            new JsonObject().put("customerTier", "BASIC").put("freeShipping", false)
        );
        inputProducer.send(lowOrder1)
                .onFailure(testContext::failNow);

        // Low priority: Bulk order with low unit price
        OrderMessage lowOrder2 = new OrderMessage(
            "ORD-BULK-001", "CUST-006", "PROD-BULK", 100, 2.99, "USD", "US", "LOW",
            new JsonObject().put("customerTier", "BULK").put("bulkDiscount", 0.2)
        );
        inputProducer.send(lowOrder2)
                .onFailure(testContext::failNow);

        // Wait for all routing to complete
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Should complete all routing");

        // Verify routing results
        assertEquals(6, messagesRouted.get(), "Should have routed 6 messages");

        logger.info("Content-Based Routing Results:");
        for (Map.Entry<String, List<OrderMessage>> entry : routedMessages.entrySet()) {
            logger.info("  {}: {} messages", entry.getKey(), entry.getValue().size());
            for (OrderMessage order : entry.getValue()) {
                logger.info("    - {} (${})", order.getOrderId(), order.getTotalAmount());
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

        logger.info("Content-Based Routing test completed successfully");
        logger.info("Total messages routed: {}", messagesRouted.get());
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
    }


}


