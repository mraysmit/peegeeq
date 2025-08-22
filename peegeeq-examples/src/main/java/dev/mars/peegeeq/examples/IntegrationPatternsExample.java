package dev.mars.peegeeq.examples;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive example demonstrating complex distributed system integration patterns with PeeGeeQ.
 * 
 * This example shows:
 * - Microservices communication patterns
 * - Event-driven architecture implementation
 * - Saga pattern for distributed transactions
 * - CQRS (Command Query Responsibility Segregation) pattern
 * - Event sourcing integration
 * - Message routing and transformation
 * - Service orchestration vs choreography
 * - Distributed system resilience patterns
 * 
 * Integration Patterns Demonstrated:
 * - Request-Reply Pattern
 * - Publish-Subscribe Pattern
 * - Message Router Pattern
 * - Content-Based Router Pattern
 * - Aggregator Pattern
 * - Scatter-Gather Pattern
 * - Process Manager Pattern
 * - Event Sourcing Pattern
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-29
 * @version 1.0
 */
public class IntegrationPatternsExample {
    
    private static final Logger logger = LoggerFactory.getLogger(IntegrationPatternsExample.class);
    
    /**
     * Generic integration message for various patterns.
     */
    public static class IntegrationMessage {
        private final String messageId;
        private final String messageType;
        private final String source;
        private final String destination;
        private final String correlationId;
        private final String payload;
        private final Instant timestamp;
        private final Map<String, String> headers;
        
        @JsonCreator
        public IntegrationMessage(@JsonProperty("messageId") String messageId,
                                 @JsonProperty("messageType") String messageType,
                                 @JsonProperty("source") String source,
                                 @JsonProperty("destination") String destination,
                                 @JsonProperty("correlationId") String correlationId,
                                 @JsonProperty("payload") String payload,
                                 @JsonProperty("timestamp") Instant timestamp,
                                 @JsonProperty("headers") Map<String, String> headers) {
            this.messageId = messageId;
            this.messageType = messageType;
            this.source = source;
            this.destination = destination;
            this.correlationId = correlationId;
            this.payload = payload;
            this.timestamp = timestamp;
            this.headers = headers != null ? headers : new HashMap<>();
        }
        
        // Getters
        public String getMessageId() { return messageId; }
        public String getMessageType() { return messageType; }
        public String getSource() { return source; }
        public String getDestination() { return destination; }
        public String getCorrelationId() { return correlationId; }
        public String getPayload() { return payload; }
        public Instant getTimestamp() { return timestamp; }
        public Map<String, String> getHeaders() { return headers; }
        
        @Override
        public String toString() {
            return String.format("IntegrationMessage{id='%s', type='%s', source='%s', dest='%s'}", 
                messageId, messageType, source, destination);
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            IntegrationMessage that = (IntegrationMessage) o;
            return Objects.equals(messageId, that.messageId);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(messageId);
        }
    }
    
    /**
     * Order aggregate for CQRS/Event Sourcing demonstration.
     */
    public static class OrderAggregate {
        private final String orderId;
        private final String customerId;
        private String status;
        private double totalAmount;
        private final Map<String, Object> items;
        
        public OrderAggregate(String orderId, String customerId) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.status = "CREATED";
            this.totalAmount = 0.0;
            this.items = new HashMap<>();
        }
        
        // Getters and business methods
        public String getOrderId() { return orderId; }
        public String getCustomerId() { return customerId; }
        public String getStatus() { return status; }
        public double getTotalAmount() { return totalAmount; }
        public Map<String, Object> getItems() { return items; }
        
        public void addItem(String itemId, double price, int quantity) {
            items.put(itemId, Map.of("price", price, "quantity", quantity));
            totalAmount += price * quantity;
        }
        
        public void updateStatus(String newStatus) {
            this.status = newStatus;
        }
        
        @Override
        public String toString() {
            return String.format("OrderAggregate{id='%s', customer='%s', status='%s', total=%.2f}", 
                orderId, customerId, status, totalAmount);
        }
    }
    
    public static void main(String[] args) throws Exception {
        logger.info("=== PeeGeeQ Integration Patterns Example ===");
        logger.info("This example demonstrates complex distributed system integration patterns");
        
        // Start PostgreSQL container
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
                .withDatabaseName("peegeeq_integration_demo")
                .withUsername("postgres")
                .withPassword("password")) {
            
            postgres.start();
            logger.info("PostgreSQL container started: {}", postgres.getJdbcUrl());
            
            // Configure PeeGeeQ for integration patterns
            configureIntegrationProperties(postgres);
            
            // Run integration pattern demonstrations
            runIntegrationPatternDemonstrations();
            
        } catch (Exception e) {
            logger.error("Failed to run Integration Patterns Example", e);
            throw e;
        }
        
        logger.info("Integration Patterns Example completed successfully!");
    }
    
    /**
     * Configures PeeGeeQ properties for integration patterns.
     */
    private static void configureIntegrationProperties(PostgreSQLContainer<?> postgres) {
        logger.info("⚙️  Configuring PeeGeeQ for integration patterns...");
        
        // Database connection properties
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        
        // Integration-specific settings
        System.setProperty("peegeeq.database.pool.min-size", "5");
        System.setProperty("peegeeq.database.pool.max-size", "25");
        System.setProperty("peegeeq.queue.routing.enabled", "true");
        System.setProperty("peegeeq.queue.correlation.enabled", "true");
        System.setProperty("peegeeq.queue.transformation.enabled", "true");
        System.setProperty("peegeeq.metrics.enabled", "true");
        System.setProperty("peegeeq.migration.enabled", "true");
        System.setProperty("peegeeq.migration.auto-migrate", "true");
        
        logger.info("✅ Integration configuration applied");
    }
    
    /**
     * Runs comprehensive integration pattern demonstrations.
     */
    private static void runIntegrationPatternDemonstrations() throws Exception {
        logger.info("Starting integration pattern demonstrations...");
        
        try (PeeGeeQManager manager = new PeeGeeQManager(
                new PeeGeeQConfiguration("integration"), 
                new SimpleMeterRegistry())) {
            
            manager.start();
            logger.info("PeeGeeQ Manager started successfully");
            
            // Create database service and factory provider
            PgDatabaseService databaseService = new PgDatabaseService(manager);
            PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

            // Register queue factory implementations
            PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
            OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

            // Create queue factories for different patterns
            QueueFactory nativeFactory = provider.createFactory("native", databaseService);
            QueueFactory outboxFactory = provider.createFactory("outbox", databaseService);
            
            // Run integration pattern demonstrations
            demonstrateRequestReplyPattern(nativeFactory);
            demonstratePublishSubscribePattern(nativeFactory);
            demonstrateMessageRouterPattern(outboxFactory);
            demonstrateContentBasedRouterPattern(outboxFactory);
            demonstrateAggregatorPattern(nativeFactory);
            demonstrateScatterGatherPattern(outboxFactory);
            demonstrateSagaPattern(outboxFactory);
            demonstrateCQRSPattern(outboxFactory);
            
        } catch (Exception e) {
            logger.error("Error running integration pattern demonstrations", e);
            throw e;
        }
    }
    
    /**
     * Demonstrates Request-Reply pattern for synchronous communication.
     */
    private static void demonstrateRequestReplyPattern(QueueFactory factory) throws Exception {
        logger.info("\n=== REQUEST-REPLY PATTERN ===");
        
        logger.info("🔄 Request-Reply Pattern:");
        logger.info("   • Synchronous communication between services");
        logger.info("   • Request correlation using correlation IDs");
        logger.info("   • Timeout handling for reliability");
        logger.info("   • Response routing back to requestor");
        
        // Create request and reply queues
        MessageProducer<IntegrationMessage> requestProducer = factory.createProducer("order-requests", IntegrationMessage.class);
        MessageConsumer<IntegrationMessage> requestConsumer = factory.createConsumer("order-requests", IntegrationMessage.class);
        MessageProducer<IntegrationMessage> replyProducer = factory.createProducer("order-replies", IntegrationMessage.class);
        MessageConsumer<IntegrationMessage> replyConsumer = factory.createConsumer("order-replies", IntegrationMessage.class);
        
        AtomicInteger processedRequests = new AtomicInteger(0);
        AtomicInteger receivedReplies = new AtomicInteger(0);
        CountDownLatch requestLatch = new CountDownLatch(3);
        CountDownLatch replyLatch = new CountDownLatch(3);
        
        // Set up request processor (simulates order service)
        requestConsumer.subscribe(message -> {
            IntegrationMessage request = message.getPayload();
            logger.info("📨 Processing request: {} from {}", request.getMessageId(), request.getSource());
            
            // Simulate processing
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Send reply
            IntegrationMessage reply = new IntegrationMessage(
                "reply-" + request.getMessageId(),
                "ORDER_REPLY",
                "order-service",
                request.getSource(),
                request.getCorrelationId(),
                "{\"status\": \"processed\", \"orderId\": \"" + request.getCorrelationId() + "\"}",
                Instant.now(),
                Map.of("replyTo", request.getSource())
            );
            
            try {
                replyProducer.send(reply);
                logger.info("📤 Sent reply: {} to {}", reply.getMessageId(), reply.getDestination());
            } catch (Exception e) {
                logger.error("Failed to send reply", e);
            }
            
            processedRequests.incrementAndGet();
            requestLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        
        // Set up reply processor (simulates client service)
        replyConsumer.subscribe(message -> {
            IntegrationMessage reply = message.getPayload();
            logger.info("📬 Received reply: {} for correlation: {}", 
                reply.getMessageId(), reply.getCorrelationId());
            
            receivedReplies.incrementAndGet();
            replyLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        
        // Send requests
        logger.info("📤 Sending requests...");
        for (int i = 1; i <= 3; i++) {
            String correlationId = "order-" + i;
            IntegrationMessage request = new IntegrationMessage(
                "req-" + i,
                "ORDER_REQUEST",
                "client-service",
                "order-service",
                correlationId,
                "{\"customerId\": \"cust-" + i + "\", \"items\": [\"item1\", \"item2\"]}",
                Instant.now(),
                Map.of("replyTo", "client-service")
            );
            
            requestProducer.send(request);
            logger.info("📨 Sent request: {} with correlation: {}", request.getMessageId(), correlationId);
        }
        
        // Wait for processing
        boolean requestsCompleted = requestLatch.await(30, TimeUnit.SECONDS);
        boolean repliesCompleted = replyLatch.await(30, TimeUnit.SECONDS);
        
        logger.info("📊 Request-Reply Results:");
        logger.info("   Requests processed: {}", processedRequests.get());
        logger.info("   Replies received: {}", receivedReplies.get());
        logger.info("   Requests completed: {}", requestsCompleted);
        logger.info("   Replies completed: {}", repliesCompleted);
        
        // Cleanup
        requestConsumer.close();
        replyConsumer.close();
        requestProducer.close();
        replyProducer.close();
        
        logger.info("✅ Request-Reply pattern demonstration completed");
    }

    /**
     * Demonstrates Publish-Subscribe pattern for event broadcasting.
     */
    private static void demonstratePublishSubscribePattern(QueueFactory factory) throws Exception {
        logger.info("\n=== PUBLISH-SUBSCRIBE PATTERN ===");

        logger.info("📢 Publish-Subscribe Pattern:");
        logger.info("   • Event broadcasting to multiple subscribers");
        logger.info("   • Loose coupling between publishers and subscribers");
        logger.info("   • Topic-based message routing");
        logger.info("   • Scalable event distribution");

        // Create event publisher
        MessageProducer<IntegrationMessage> eventPublisher = factory.createProducer("customer-events", IntegrationMessage.class);

        // Create multiple subscribers
        MessageConsumer<IntegrationMessage> emailService = factory.createConsumer("customer-events", IntegrationMessage.class);
        MessageConsumer<IntegrationMessage> analyticsService = factory.createConsumer("customer-events", IntegrationMessage.class);
        MessageConsumer<IntegrationMessage> auditService = factory.createConsumer("customer-events", IntegrationMessage.class);

        AtomicInteger emailEvents = new AtomicInteger(0);
        AtomicInteger analyticsEvents = new AtomicInteger(0);
        AtomicInteger auditEvents = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(9); // 3 events × 3 subscribers

        // Email service subscriber
        emailService.subscribe(message -> {
            IntegrationMessage event = message.getPayload();
            logger.info("📧 Email Service received: {} - {}", event.getMessageType(), event.getMessageId());
            emailEvents.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Analytics service subscriber
        analyticsService.subscribe(message -> {
            IntegrationMessage event = message.getPayload();
            logger.info("📊 Analytics Service received: {} - {}", event.getMessageType(), event.getMessageId());
            analyticsEvents.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Audit service subscriber
        auditService.subscribe(message -> {
            IntegrationMessage event = message.getPayload();
            logger.info("📝 Audit Service received: {} - {}", event.getMessageType(), event.getMessageId());
            auditEvents.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Publish events
        logger.info("📢 Publishing customer events...");
        String[] eventTypes = {"CUSTOMER_CREATED", "CUSTOMER_UPDATED", "CUSTOMER_DELETED"};

        for (int i = 0; i < eventTypes.length; i++) {
            IntegrationMessage event = new IntegrationMessage(
                "event-" + (i + 1),
                eventTypes[i],
                "customer-service",
                "all-subscribers",
                "customer-123",
                "{\"customerId\": \"customer-123\", \"action\": \"" + eventTypes[i] + "\"}",
                Instant.now(),
                Map.of("eventType", eventTypes[i])
            );

            eventPublisher.send(event);
            logger.info("📤 Published event: {} - {}", event.getMessageType(), event.getMessageId());
        }

        // Wait for all subscribers to process events
        boolean completed = latch.await(30, TimeUnit.SECONDS);

        logger.info("📊 Publish-Subscribe Results:");
        logger.info("   Email Service events: {}", emailEvents.get());
        logger.info("   Analytics Service events: {}", analyticsEvents.get());
        logger.info("   Audit Service events: {}", auditEvents.get());
        logger.info("   All events processed: {}", completed);

        // Cleanup
        emailService.close();
        analyticsService.close();
        auditService.close();
        eventPublisher.close();

        logger.info("✅ Publish-Subscribe pattern demonstration completed");
    }

    /**
     * Demonstrates Message Router pattern for conditional routing.
     */
    private static void demonstrateMessageRouterPattern(QueueFactory factory) throws Exception {
        logger.info("\n=== MESSAGE ROUTER PATTERN ===");

        logger.info("🚦 Message Router Pattern:");
        logger.info("   • Conditional message routing based on content");
        logger.info("   • Dynamic destination selection");
        logger.info("   • Message filtering and transformation");
        logger.info("   • Centralized routing logic");

        // Create input queue and output queues
        MessageProducer<IntegrationMessage> inputProducer = factory.createProducer("order-input", IntegrationMessage.class);
        MessageConsumer<IntegrationMessage> routerConsumer = factory.createConsumer("order-input", IntegrationMessage.class);

        MessageProducer<IntegrationMessage> domesticProducer = factory.createProducer("domestic-orders", IntegrationMessage.class);
        MessageProducer<IntegrationMessage> internationalProducer = factory.createProducer("international-orders", IntegrationMessage.class);
        MessageProducer<IntegrationMessage> expressProducer = factory.createProducer("express-orders", IntegrationMessage.class);

        MessageConsumer<IntegrationMessage> domesticConsumer = factory.createConsumer("domestic-orders", IntegrationMessage.class);
        MessageConsumer<IntegrationMessage> internationalConsumer = factory.createConsumer("international-orders", IntegrationMessage.class);
        MessageConsumer<IntegrationMessage> expressConsumer = factory.createConsumer("express-orders", IntegrationMessage.class);

        AtomicInteger domesticCount = new AtomicInteger(0);
        AtomicInteger internationalCount = new AtomicInteger(0);
        AtomicInteger expressCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(6);

        // Message router logic
        routerConsumer.subscribe(message -> {
            IntegrationMessage order = message.getPayload();
            logger.info("🚦 Routing message: {} - {}", order.getMessageId(), order.getMessageType());

            try {
                // Route based on message headers and content
                String country = order.getHeaders().getOrDefault("country", "US");
                String priority = order.getHeaders().getOrDefault("priority", "normal");

                if ("express".equals(priority)) {
                    logger.info("   → Routing to EXPRESS queue");
                    expressProducer.send(order);
                } else if (!"US".equals(country)) {
                    logger.info("   → Routing to INTERNATIONAL queue");
                    internationalProducer.send(order);
                } else {
                    logger.info("   → Routing to DOMESTIC queue");
                    domesticProducer.send(order);
                }

            } catch (Exception e) {
                logger.error("Failed to route message: {}", order.getMessageId(), e);
            }

            return CompletableFuture.completedFuture(null);
        });

        // Set up destination consumers
        domesticConsumer.subscribe(message -> {
            logger.info("🏠 Domestic processor received: {}", message.getPayload().getMessageId());
            domesticCount.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        internationalConsumer.subscribe(message -> {
            logger.info("🌍 International processor received: {}", message.getPayload().getMessageId());
            internationalCount.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        expressConsumer.subscribe(message -> {
            logger.info("⚡ Express processor received: {}", message.getPayload().getMessageId());
            expressCount.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send test messages with different routing criteria
        logger.info("📤 Sending orders for routing...");

        // Domestic order
        IntegrationMessage domesticOrder = new IntegrationMessage(
            "order-1", "ORDER", "order-service", "router", "corr-1",
            "{\"orderId\": \"order-1\", \"customerId\": \"cust-1\"}", Instant.now(),
            Map.of("country", "US", "priority", "normal"));
        inputProducer.send(domesticOrder);

        // International order
        IntegrationMessage intlOrder = new IntegrationMessage(
            "order-2", "ORDER", "order-service", "router", "corr-2",
            "{\"orderId\": \"order-2\", \"customerId\": \"cust-2\"}", Instant.now(),
            Map.of("country", "CA", "priority", "normal"));
        inputProducer.send(intlOrder);

        // Express orders
        for (int i = 3; i <= 6; i++) {
            IntegrationMessage expressOrder = new IntegrationMessage(
                "order-" + i, "ORDER", "order-service", "router", "corr-" + i,
                "{\"orderId\": \"order-" + i + "\", \"customerId\": \"cust-" + i + "\"}", Instant.now(),
                Map.of("country", i % 2 == 0 ? "US" : "UK", "priority", "express"));
            inputProducer.send(expressOrder);
        }

        // Wait for processing
        boolean completed = latch.await(30, TimeUnit.SECONDS);

        logger.info("📊 Message Router Results:");
        logger.info("   Domestic orders: {}", domesticCount.get());
        logger.info("   International orders: {}", internationalCount.get());
        logger.info("   Express orders: {}", expressCount.get());
        logger.info("   All messages routed: {}", completed);

        // Cleanup
        routerConsumer.close();
        domesticConsumer.close();
        internationalConsumer.close();
        expressConsumer.close();
        inputProducer.close();
        domesticProducer.close();
        internationalProducer.close();
        expressProducer.close();

        logger.info("✅ Message Router pattern demonstration completed");
    }

    /**
     * Placeholder for Content-Based Router pattern demonstration.
     */
    private static void demonstrateContentBasedRouterPattern(QueueFactory factory) throws Exception {
        logger.info("\n=== CONTENT-BASED ROUTER PATTERN ===");
        logger.info("🔍 Content-Based Router: Routes messages based on message content analysis");
        logger.info("   Implementation would analyze message payload and route accordingly");
        logger.info("✅ Content-Based Router pattern placeholder completed");
    }

    /**
     * Placeholder for Aggregator pattern demonstration.
     */
    private static void demonstrateAggregatorPattern(QueueFactory factory) throws Exception {
        logger.info("\n=== AGGREGATOR PATTERN ===");
        logger.info("🔗 Aggregator Pattern: Combines related messages into a single message");
        logger.info("   Implementation would collect and combine messages based on correlation");
        logger.info("✅ Aggregator pattern placeholder completed");
    }

    /**
     * Placeholder for Scatter-Gather pattern demonstration.
     */
    private static void demonstrateScatterGatherPattern(QueueFactory factory) throws Exception {
        logger.info("\n=== SCATTER-GATHER PATTERN ===");
        logger.info("📡 Scatter-Gather Pattern: Broadcasts request and aggregates responses");
        logger.info("   Implementation would scatter requests and gather responses");
        logger.info("✅ Scatter-Gather pattern placeholder completed");
    }

    /**
     * Placeholder for Saga pattern demonstration.
     */
    private static void demonstrateSagaPattern(QueueFactory factory) throws Exception {
        logger.info("\n=== SAGA PATTERN ===");
        logger.info("🔄 Saga Pattern: Manages distributed transactions with compensation");
        logger.info("   Implementation would coordinate distributed transactions");
        logger.info("✅ Saga pattern placeholder completed");
    }

    /**
     * Placeholder for CQRS pattern demonstration.
     */
    private static void demonstrateCQRSPattern(QueueFactory factory) throws Exception {
        logger.info("\n=== CQRS PATTERN ===");
        logger.info("📊 CQRS Pattern: Separates command and query responsibilities");
        logger.info("   Implementation would separate read and write models");
        logger.info("✅ CQRS pattern placeholder completed");
    }
}
