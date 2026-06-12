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
 * Demo test showcasing Microservices Communication Patterns for PeeGeeQ.
 * 
 * This test demonstrates:
 * 1. Service-to-Service Communication - Async messaging between services
 * 2. Request-Response Pattern - Synchronous-style communication over async messaging
 * 3. Publish-Subscribe Pattern - Event-driven communication
 * 4. Service Orchestration - Coordinating multiple services
 * 5. Service Choreography - Decentralized service coordination
 * 
 * Based on Advanced Messaging Patterns from PeeGeeQ Complete Guide.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@org.junit.jupiter.api.extension.ExtendWith(VertxExtension.class)
class MicroservicesCommunicationDemoTest {
    private static final Logger logger = LoggerFactory.getLogger(MicroservicesCommunicationDemoTest.class);


    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }

    private PeeGeeQManager manager;
    private QueueFactory queueFactory;

    // Service communication patterns
    enum CommunicationPattern {
        REQUEST_RESPONSE("request-response", "Synchronous-style request/response"),
        PUBLISH_SUBSCRIBE("publish-subscribe", "Event-driven pub/sub"),
        SERVICE_ORCHESTRATION("service-orchestration", "Centralized coordination"),
        SERVICE_CHOREOGRAPHY("service-choreography", "Decentralized coordination");

        final String patternName;
        final String description;

        CommunicationPattern(String patternName, String description) {
            this.patternName = patternName;
            this.description = description;
        }
    }

    // Service message for microservices communication
    static class ServiceMessage {
        public String messageId;
        public String correlationId;
        public String sourceService;
        public String targetService;
        public String messageType;
        public Map<String, Object> payload;
        public String timestamp;
        public String replyTo;

        // Default constructor for Jackson
        public ServiceMessage() {}

        public ServiceMessage(String messageId, String correlationId, String sourceService,
                             String targetService, String messageType, Map<String, Object> payload, String replyTo) {
            this.messageId = messageId;
            this.correlationId = correlationId;
            this.sourceService = sourceService;
            this.targetService = targetService;
            this.messageType = messageType;
            this.payload = payload;
            this.timestamp = Instant.now().toString();
            this.replyTo = replyTo;
        }

        public JsonObject toJson() {
            return new JsonObject()
                    .put("messageId", messageId)
                    .put("correlationId", correlationId)
                    .put("sourceService", sourceService)
                    .put("targetService", targetService)
                    .put("messageType", messageType)
                    .put("payload", payload)
                    .put("timestamp", timestamp.toString())
                    .put("replyTo", replyTo);
        }
    }

    // Service event for publish-subscribe
    static class ServiceEvent {
        public final String eventId;
        public final String eventType;
        public final String sourceService;
        public final JsonObject eventData;
        public final Instant timestamp;
        public final String version;

        public ServiceEvent(String eventId, String eventType, String sourceService, 
                           JsonObject eventData, String version) {
            this.eventId = eventId;
            this.eventType = eventType;
            this.sourceService = sourceService;
            this.eventData = eventData;
            this.timestamp = Instant.now();
            this.version = version;
        }

        public JsonObject toJson() {
            return new JsonObject()
                    .put("eventId", eventId)
                    .put("eventType", eventType)
                    .put("sourceService", sourceService)
                    .put("eventData", eventData)
                    .put("timestamp", timestamp.toString())
                    .put("version", version);
        }
    }

    // Order processing workflow for orchestration/choreography
    static class OrderWorkflow {
        public final String orderId;
        public final String customerId;
        public final List<OrderItem> items;
        public volatile String status;
        public volatile double totalAmount;
        public final Map<String, String> serviceResponses = new HashMap<>();
        public final Instant createdAt;

        public OrderWorkflow(String orderId, String customerId, List<OrderItem> items) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.items = new ArrayList<>(items);
            this.status = "CREATED";
            this.totalAmount = items.stream().mapToDouble(item -> item.price * item.quantity).sum();
            this.createdAt = Instant.now();
        }

        public JsonObject toJson() {
            return new JsonObject()
                    .put("orderId", orderId)
                    .put("customerId", customerId)
                    .put("items", JsonObject.mapFrom(items.stream()
                        .collect(HashMap::new, (m, item) -> m.put(String.valueOf(m.size()), item.toJson()), HashMap::putAll)))
                    .put("status", status)
                    .put("totalAmount", totalAmount)
                    .put("serviceResponses", JsonObject.mapFrom(serviceResponses))
                    .put("createdAt", createdAt.toString());
        }
    }

    static class OrderItem {
        public final String productId;
        public final String productName;
        public final int quantity;
        public final double price;

        public OrderItem(String productId, String productName, int quantity, double price) {
            this.productId = productId;
            this.productName = productName;
            this.quantity = quantity;
            this.price = price;
        }

        public JsonObject toJson() {
            return new JsonObject()
                    .put("productId", productId)
                    .put("productName", productName)
                    .put("quantity", quantity)
                    .put("price", price);
        }
    }

    // Microservice simulator
    static class MicroserviceSimulator {
        public final String serviceName;
        public final AtomicInteger messagesProcessed = new AtomicInteger(0);
        public final AtomicInteger eventsPublished = new AtomicInteger(0);
        public final Map<String, String> serviceState = new HashMap<>();

        public MicroserviceSimulator(String serviceName) {
            this.serviceName = serviceName;
        }

        public ServiceMessage processMessage(ServiceMessage message) {
            messagesProcessed.incrementAndGet();
            
            // Simulate service processing
            Map<String, Object> responsePayload = new HashMap<>();
            String responseType = "";
            
            switch (serviceName) {
                case "inventory-service":
                    responseType = "InventoryCheckResponse";
                    Integer quantity = (Integer) message.payload.getOrDefault("quantity", 1);
                    String reservationId = "RES-" + UUID.randomUUID().toString().substring(0, 8);
                    responsePayload.put("available", true);
                    responsePayload.put("reservedQuantity", quantity);
                    responsePayload.put("reservationId", reservationId);
                    serviceState.put("lastReservation", reservationId);
                    break;
                    
                case "payment-service":
                    responseType = "PaymentProcessResponse";
                    Double amount = (Double) message.payload.getOrDefault("amount", 0.0);
                    boolean success = amount > 0 && amount < 10000; // Simulate payment limits
                    String transactionId = success ? "TXN-" + UUID.randomUUID().toString().substring(0, 8) : null;
                    responsePayload.put("success", success);
                    responsePayload.put("transactionId", transactionId);
                    responsePayload.put("amount", amount);
                    if (success) {
                        serviceState.put("lastTransaction", transactionId);
                    }
                    break;
                    
                case "shipping-service":
                    responseType = "ShippingScheduleResponse";
                    String trackingNumber = "TRACK-" + UUID.randomUUID().toString().substring(0, 8);
                    responsePayload.put("scheduled", true);
                    responsePayload.put("trackingNumber", trackingNumber);
                    responsePayload.put("estimatedDelivery", Instant.now().plusSeconds(7 * 24 * 60 * 60).toString());
                    serviceState.put("lastShipment", trackingNumber);
                    break;

                case "notification-service":
                    responseType = "NotificationSentResponse";
                    String customerId = (String) message.payload.getOrDefault("customerId", "unknown");
                    responsePayload.put("sent", true);
                    responsePayload.put("channel", "EMAIL");
                    responsePayload.put("recipient", customerId);
                    break;
            }
            
            return new ServiceMessage(
                UUID.randomUUID().toString(),
                message.correlationId,
                serviceName,
                message.sourceService,
                responseType,
                responsePayload,
                null
            );
        }

        public ServiceEvent createEvent(String eventType, JsonObject eventData) {
            eventsPublished.incrementAndGet();
            return new ServiceEvent(
                UUID.randomUUID().toString(),
                eventType,
                serviceName,
                eventData,
                "1.0"
            );
        }
    }

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        logger.info("Setting up Microservices Communication Demo Test");

        // Configure database connection properties
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA).build();

        // Initialize database schema for microservices communication test
        logger.info("Initializing database schema for microservices communication test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer (ALL components)");

        // Initialize PeeGeeQ with microservices configuration
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().onSuccess(v -> {
            // Create native factory
            var databaseService = new PgDatabaseService(manager);
            QueueFactoryProvider provider = new PgQueueFactoryProvider();

            // Register native factory implementation
            PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

            queueFactory = provider.createFactory("native", databaseService);

            logger.info("Setup complete - Ready for microservices communication pattern testing");
            testContext.completeNow();
        }).onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        logger.info("Cleaning up Microservices Communication Demo Test");
        (manager != null ? manager.closeReactive() : io.vertx.core.Future.succeededFuture())
                .onSuccess(v -> {
                    logger.info("Cleanup complete");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Request-Response Pattern - Synchronous-style Communication Over Async Messaging")
    void testRequestResponsePattern(VertxTestContext testContext) throws Exception {
        logger.info("Test: request response pattern");
        logger.info("Testing Request-Response Pattern");

        String requestQueue = "microservices-request-queue";
        String responseQueue = "microservices-response-queue";

        Map<String, ServiceMessage> responses = new HashMap<>();
        Map<String, MicroserviceSimulator> services = new HashMap<>();
        AtomicInteger requestsProcessed = new AtomicInteger(0);
        AtomicInteger responsesReceived = new AtomicInteger(0);
        var requestCheckpoint = testContext.checkpoint(3);
        var responseCheckpoint = testContext.checkpoint(3);

        // Initialize services
        services.put("inventory-service", new MicroserviceSimulator("inventory-service"));
        services.put("payment-service", new MicroserviceSimulator("payment-service"));
        services.put("shipping-service", new MicroserviceSimulator("shipping-service"));

        // Create producers and consumers
        MessageProducer<ServiceMessage> requestProducer = queueFactory.createProducer(requestQueue, ServiceMessage.class);
        MessageConsumer<ServiceMessage> requestConsumer = queueFactory.createConsumer(requestQueue, ServiceMessage.class);
        MessageProducer<ServiceMessage> responseProducer = queueFactory.createProducer(responseQueue, ServiceMessage.class);
        MessageConsumer<ServiceMessage> responseConsumer = queueFactory.createConsumer(responseQueue, ServiceMessage.class);

        // Request handler - simulates service processing
        requestConsumer.subscribe(message -> {
            ServiceMessage request = message.getPayload();

            logger.info("Processing request: {} for service: {}", request.messageType, request.targetService);

            // Get service simulator
            MicroserviceSimulator service = services.get(request.targetService);
            if (service != null) {
                // Process request and generate response
                ServiceMessage response = service.processMessage(request);

                // Send response back
                responseProducer.send(response)
                        .onFailure(testContext::failNow);

                requestsProcessed.incrementAndGet();
            }

            requestCheckpoint.flag();
            return Future.succeededFuture();
        });

        // Response handler - collects responses
        responseConsumer.subscribe(message -> {
            ServiceMessage response = message.getPayload();

            logger.info("Received response: {} from service: {}", response.messageType, response.sourceService);

            responses.put(response.correlationId, response);
            responsesReceived.incrementAndGet();
            responseCheckpoint.flag();
            return Future.succeededFuture();
        });

        // Send requests to different services
        logger.info("Sending requests to microservices...");

        String correlationId1 = "corr-001";
        String correlationId2 = "corr-002";
        String correlationId3 = "corr-003";

        // Request 1: Check inventory
        Map<String, Object> inventoryPayload = new HashMap<>();
        inventoryPayload.put("productId", "PROD-001");
        inventoryPayload.put("quantity", 5);
        ServiceMessage inventoryRequest = new ServiceMessage(
            "req-001", correlationId1, "order-service", "inventory-service",
            "InventoryCheckRequest", inventoryPayload, responseQueue
        );
        requestProducer.send(inventoryRequest)
                .onFailure(testContext::failNow);

        // Request 2: Process payment
        Map<String, Object> paymentPayload = new HashMap<>();
        paymentPayload.put("customerId", "CUST-001");
        paymentPayload.put("amount", 299.99);
        ServiceMessage paymentRequest = new ServiceMessage(
            "req-002", correlationId2, "order-service", "payment-service",
            "PaymentProcessRequest", paymentPayload, responseQueue
        );
        requestProducer.send(paymentRequest)
                .onFailure(testContext::failNow);

        // Request 3: Schedule shipping
        Map<String, Object> shippingPayload = new HashMap<>();
        shippingPayload.put("orderId", "ORD-001");
        shippingPayload.put("address", "123 Main St");
        ServiceMessage shippingRequest = new ServiceMessage(
            "req-003", correlationId3, "order-service", "shipping-service",
            "ShippingScheduleRequest", shippingPayload, responseQueue
        );
        requestProducer.send(shippingRequest)
                .onFailure(testContext::failNow);

        // Wait for all requests and responses
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Should process all requests and responses");

        // Verify request-response pattern
        assertEquals(3, requestsProcessed.get(), "Should have processed 3 requests");
        assertEquals(3, responsesReceived.get(), "Should have received 3 responses");
        assertEquals(3, responses.size(), "Should have 3 responses stored");

        // Verify specific responses
        assertTrue(responses.containsKey(correlationId1), "Should have inventory response");
        assertTrue(responses.containsKey(correlationId2), "Should have payment response");
        assertTrue(responses.containsKey(correlationId3), "Should have shipping response");

        ServiceMessage inventoryResponse = responses.get(correlationId1);
        assertEquals("InventoryCheckResponse", inventoryResponse.messageType, "Should be inventory response");
        assertEquals(true, inventoryResponse.payload.get("available"), "Inventory should be available");

        ServiceMessage paymentResponse = responses.get(correlationId2);
        assertEquals("PaymentProcessResponse", paymentResponse.messageType, "Should be payment response");
        assertEquals(true, paymentResponse.payload.get("success"), "Payment should be successful");

        ServiceMessage shippingResponse = responses.get(correlationId3);
        assertEquals("ShippingScheduleResponse", shippingResponse.messageType, "Should be shipping response");
        assertEquals(true, shippingResponse.payload.get("scheduled"), "Shipping should be scheduled");

        logger.info("Request-Response Results:");
        logger.info("  Requests processed: {}", requestsProcessed.get());
        logger.info("  Responses received: {}", responsesReceived.get());
        for (MicroserviceSimulator service : services.values()) {
            logger.info("  {} processed: {} messages", service.serviceName, service.messagesProcessed.get());
        }

        // Cleanup
        requestConsumer.close();
        responseConsumer.close();

        logger.info("Request-Response Pattern test completed successfully");
    }
}


