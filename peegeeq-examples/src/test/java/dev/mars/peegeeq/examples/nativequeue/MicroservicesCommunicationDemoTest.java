package dev.mars.peegeeq.examples.nativequeue;

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
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MicroservicesCommunicationDemoTest {

    static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();

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

    /**
     * Configure system properties for TestContainers PostgreSQL connection
     */
    private void configureSystemPropertiesForContainer() {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
    }

    @BeforeEach
    void setUp() {
        System.out.println("\n🔗 Setting up Microservices Communication Demo Test");

        // Configure system properties for TestContainers
        configureSystemPropertiesForContainer();

        // Initialize database schema for microservices communication test
        System.out.println("🔧 Initializing database schema for microservices communication test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
        System.out.println("✅ Database schema initialized successfully using centralized schema initializer (ALL components)");

        // Initialize PeeGeeQ with microservices configuration
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("development");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create native factory
        var databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        queueFactory = provider.createFactory("native", databaseService);

        System.out.println("✅ Setup complete - Ready for microservices communication pattern testing");
    }

    @AfterEach
    void tearDown() {
        System.out.println("🧹 Cleaning up Microservices Communication Demo Test");

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
    @DisplayName("Request-Response Pattern - Synchronous-style Communication Over Async Messaging")
    void testRequestResponsePattern() throws Exception {
        System.out.println("\n🔄 Testing Request-Response Pattern");

        String requestQueue = "microservices-request-queue";
        String responseQueue = "microservices-response-queue";

        Map<String, ServiceMessage> responses = new HashMap<>();
        Map<String, MicroserviceSimulator> services = new HashMap<>();
        AtomicInteger requestsProcessed = new AtomicInteger(0);
        AtomicInteger responsesReceived = new AtomicInteger(0);
        CountDownLatch requestLatch = new CountDownLatch(3);
        CountDownLatch responseLatch = new CountDownLatch(3);

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

            System.out.println("🔄 Processing request: " + request.messageType +
                             " for service: " + request.targetService);

            // Get service simulator
            MicroserviceSimulator service = services.get(request.targetService);
            if (service != null) {
                // Process request and generate response
                ServiceMessage response = service.processMessage(request);

                // Send response back
                responseProducer.send(response);

                requestsProcessed.incrementAndGet();
            }

            requestLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Response handler - collects responses
        responseConsumer.subscribe(message -> {
            ServiceMessage response = message.getPayload();

            System.out.println("🔄 Received response: " + response.messageType +
                             " from service: " + response.sourceService);

            responses.put(response.correlationId, response);
            responsesReceived.incrementAndGet();
            responseLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send requests to different services
        System.out.println("📤 Sending requests to microservices...");

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
        requestProducer.send(inventoryRequest);

        // Request 2: Process payment
        Map<String, Object> paymentPayload = new HashMap<>();
        paymentPayload.put("customerId", "CUST-001");
        paymentPayload.put("amount", 299.99);
        ServiceMessage paymentRequest = new ServiceMessage(
            "req-002", correlationId2, "order-service", "payment-service",
            "PaymentProcessRequest", paymentPayload, responseQueue
        );
        requestProducer.send(paymentRequest);

        // Request 3: Schedule shipping
        Map<String, Object> shippingPayload = new HashMap<>();
        shippingPayload.put("orderId", "ORD-001");
        shippingPayload.put("address", "123 Main St");
        ServiceMessage shippingRequest = new ServiceMessage(
            "req-003", correlationId3, "order-service", "shipping-service",
            "ShippingScheduleRequest", shippingPayload, responseQueue
        );
        requestProducer.send(shippingRequest);

        // Wait for all requests and responses
        assertTrue(requestLatch.await(30, TimeUnit.SECONDS), "Should process all requests");
        assertTrue(responseLatch.await(30, TimeUnit.SECONDS), "Should receive all responses");

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

        System.out.println("📊 Request-Response Results:");
        System.out.println("  Requests processed: " + requestsProcessed.get());
        System.out.println("  Responses received: " + responsesReceived.get());
        for (MicroserviceSimulator service : services.values()) {
            System.out.println("  " + service.serviceName + " processed: " + service.messagesProcessed.get() + " messages");
        }

        // Cleanup
        requestConsumer.close();
        responseConsumer.close();

        System.out.println("✅ Request-Response Pattern test completed successfully");
    }
}
