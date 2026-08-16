package dev.mars.peegeeq.outbox.examples;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Integration tests for the implemented IntegrationPatternsExample patterns:
 * 1. Request-Reply Pattern - Synchronous communication with correlation IDs
 * 2. Publish-Subscribe Pattern - Event broadcasting to multiple subscribers
 * 3. Message Router Pattern - Conditional routing based on message content
 *
 * Tests use outbox queue implementation for reliable message processing.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public class IntegrationPatternsExampleTest {

    private static final Logger logger = LoggerFactory.getLogger(IntegrationPatternsExampleTest.class);
    
    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory outboxFactory;
    
    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        logger.info("Setting up Integration Patterns Example Test");

        // Configure system properties for TestContainer
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        // Initialize PeeGeeQ manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
            .onSuccess(v -> {
                // Register queue factory implementations
                PgDatabaseService databaseService = new PgDatabaseService(manager);
                PgQueueFactoryProvider factoryProvider = new PgQueueFactoryProvider();
                // Register outbox factory
                OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) factoryProvider);
                // Create outbox factory for testing
                outboxFactory = factoryProvider.createFactory("outbox", databaseService, new HashMap<>());
                logger.info(" Integration Patterns Example Test setup completed");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down: closing resources and manager");
        logger.info("Tearing down Integration Patterns Example Test");

        Future<Void> closeChain = outboxFactory != null
                ? outboxFactory.close()
                : Future.succeededFuture();
        closeChain
                .eventually(() -> manager != null ? manager.closeReactive() : Future.succeededFuture())
                .onSuccess(v -> {
                    logger.info("Integration Patterns Example Test teardown completed");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    /**
     * Test Pattern 1: Request-Reply Pattern
     * Validates synchronous communication with correlation IDs and timeout handling
     */
    @Test
    void testRequestReplyPattern(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Request-Reply Pattern ===");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String requestTopic = "rr-requests-" + suffix;
        String replyTopic = "rr-replies-" + suffix;

        // Create request and reply queues
        MessageProducer<IntegrationMessage> requestProducer = outboxFactory.createProducer(requestTopic, IntegrationMessage.class);
        MessageConsumer<IntegrationMessage> requestConsumer = outboxFactory.createConsumer(requestTopic, IntegrationMessage.class);
        MessageProducer<IntegrationMessage> replyProducer = outboxFactory.createProducer(replyTopic, IntegrationMessage.class);
        MessageConsumer<IntegrationMessage> replyConsumer = outboxFactory.createConsumer(replyTopic, IntegrationMessage.class);

        AtomicInteger processedRequests = new AtomicInteger();
        AtomicInteger receivedReplies = new AtomicInteger();

        // Set up request processor (simulates order service)
        Future<Void> requestSubscription = requestConsumer.subscribe(message -> {
            IntegrationMessage request = message.getPayload();
            logger.info("Processing request: {} from {}", request.getMessageId(), request.getSource());

            IntegrationMessage reply = new IntegrationMessage(
                    "reply-" + request.getMessageId(),
                    "ORDER_REPLY",
                    "order-service",
                    request.getSource(),
                    request.getCorrelationId(),
                    "{\"status\": \"processed\", \"orderId\": \"" + request.getCorrelationId() + "\"}",
                    "2025-01-01T00:00:00Z",
                    Map.of("replyTo", request.getSource())
            );

            return vertx.timer(50)
                    .compose(v -> replyProducer.send(reply))
                    .map(v -> {
                        processedRequests.incrementAndGet();
                        logger.info("Sent reply: {} to {}", reply.getMessageId(), reply.getDestination());
                        return (Void) null;
                    });
        });

        // Set up reply processor (simulates client service)
        Future<Void> replySubscription = replyConsumer.subscribe(message -> {
            IntegrationMessage reply = message.getPayload();
            logger.info("Received reply: {} for correlation: {}",
                reply.getMessageId(), reply.getCorrelationId());

            receivedReplies.incrementAndGet();
            return Future.succeededFuture();
        });

        Future.all(List.of(replySubscription, requestSubscription))
                .compose(v -> sendRequests(requestProducer))
                .compose(v -> awaitCompletedCount(vertx, requestTopic, 3, 100))
                .compose(v -> awaitCompletedCount(vertx, replyTopic, 3, 100))
                .onSuccess(v -> testContext.verify(() -> {
                    assertEquals(3, processedRequests.intValue(), "Should process 3 requests");
                    assertEquals(3, receivedReplies.intValue(), "Should receive 3 replies");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    /**
     * Test Pattern 2: Publish-Subscribe Pattern
     * Validates event broadcasting using separate queues for each subscriber (outbox pattern)
     */
    @Test
    void testPublishSubscribePattern(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Publish-Subscribe Pattern ===");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String emailTopic = "pub-email-" + suffix;
        String analyticsTopic = "pub-analytics-" + suffix;
        String auditTopic = "pub-audit-" + suffix;

        // In outbox pattern, we use separate queues for each subscriber to simulate pub-sub
        MessageProducer<IntegrationMessage> emailProducer = outboxFactory.createProducer(emailTopic, IntegrationMessage.class);
        MessageProducer<IntegrationMessage> analyticsProducer = outboxFactory.createProducer(analyticsTopic, IntegrationMessage.class);
        MessageProducer<IntegrationMessage> auditProducer = outboxFactory.createProducer(auditTopic, IntegrationMessage.class);

        // Create subscribers for each service
        MessageConsumer<IntegrationMessage> emailService = outboxFactory.createConsumer(emailTopic, IntegrationMessage.class);
        MessageConsumer<IntegrationMessage> analyticsService = outboxFactory.createConsumer(analyticsTopic, IntegrationMessage.class);
        MessageConsumer<IntegrationMessage> auditService = outboxFactory.createConsumer(auditTopic, IntegrationMessage.class);

        AtomicInteger emailEvents = new AtomicInteger();
        AtomicInteger analyticsEvents = new AtomicInteger();
        AtomicInteger auditEvents = new AtomicInteger();

        // Email service subscriber
        Future<Void> emailSubscription = emailService.subscribe(message -> {
            IntegrationMessage event = message.getPayload();
            logger.info(" Email Service received: {} - {}", event.getMessageType(), event.getMessageId());
            emailEvents.incrementAndGet();
            return Future.succeededFuture();
        });

        // Analytics service subscriber
        Future<Void> analyticsSubscription = analyticsService.subscribe(message -> {
            IntegrationMessage event = message.getPayload();
            logger.info("\ud83d\udcca Analytics Service received: {} - {}", event.getMessageType(), event.getMessageId());
            analyticsEvents.incrementAndGet();
            return Future.succeededFuture();
        });

        // Audit service subscriber
        Future<Void> auditSubscription = auditService.subscribe(message -> {
            IntegrationMessage event = message.getPayload();
            logger.info("\ud83d\udcdd Audit Service received: {} - {}", event.getMessageType(), event.getMessageId());
            auditEvents.incrementAndGet();
            return Future.succeededFuture();
        });

        Future.all(List.of(emailSubscription, analyticsSubscription, auditSubscription))
                .compose(v -> publishEvents(emailProducer, analyticsProducer, auditProducer))
                .compose(v -> awaitCompletedCount(vertx, emailTopic, 3, 100))
                .compose(v -> awaitCompletedCount(vertx, analyticsTopic, 3, 100))
                .compose(v -> awaitCompletedCount(vertx, auditTopic, 3, 100))
                .onSuccess(v -> testContext.verify(() -> {
                    assertEquals(3, emailEvents.intValue(), "Email service should receive 3 events");
                    assertEquals(3, analyticsEvents.intValue(), "Analytics service should receive 3 events");
                    assertEquals(3, auditEvents.intValue(), "Audit service should receive 3 events");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    /**
     * Test Pattern 3: Message Router Pattern
     * Validates conditional routing based on message headers and content
     */
    @Test
    void testMessageRouterPattern(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Message Router Pattern ===");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String inputTopic = "route-input-" + suffix;
        String domesticTopic = "route-domestic-" + suffix;
        String internationalTopic = "route-intl-" + suffix;
        String expressTopic = "route-express-" + suffix;

        // Create input queue and output queues
        MessageProducer<IntegrationMessage> inputProducer = outboxFactory.createProducer(inputTopic, IntegrationMessage.class);
        MessageConsumer<IntegrationMessage> routerConsumer = outboxFactory.createConsumer(inputTopic, IntegrationMessage.class);

        MessageProducer<IntegrationMessage> domesticProducer = outboxFactory.createProducer(domesticTopic, IntegrationMessage.class);
        MessageProducer<IntegrationMessage> internationalProducer = outboxFactory.createProducer(internationalTopic, IntegrationMessage.class);
        MessageProducer<IntegrationMessage> expressProducer = outboxFactory.createProducer(expressTopic, IntegrationMessage.class);

        MessageConsumer<IntegrationMessage> domesticConsumer = outboxFactory.createConsumer(domesticTopic, IntegrationMessage.class);
        MessageConsumer<IntegrationMessage> internationalConsumer = outboxFactory.createConsumer(internationalTopic, IntegrationMessage.class);
        MessageConsumer<IntegrationMessage> expressConsumer = outboxFactory.createConsumer(expressTopic, IntegrationMessage.class);

        AtomicInteger domesticCount = new AtomicInteger();
        AtomicInteger internationalCount = new AtomicInteger();
        AtomicInteger expressCount = new AtomicInteger();

        // Set up router logic
        Future<Void> routerSubscription = routerConsumer.subscribe(message -> {
            IntegrationMessage order = message.getPayload();
            String country = order.getHeaders().get("country");
            String priority = order.getHeaders().get("priority");

            logger.info(" Routing order: {} (country: {}, priority: {})",
                order.getMessageId(), country, priority);

            if ("express".equals(priority)) {
                return expressProducer.send(order).mapEmpty();
            }
            if ("US".equals(country)) {
                return domesticProducer.send(order).mapEmpty();
            }
            return internationalProducer.send(order).mapEmpty();
        });

        // Set up destination consumers
        Future<Void> domesticSubscription = domesticConsumer.subscribe(message -> {
            logger.info("Domestic processor received: {}", message.getPayload().getMessageId());
            domesticCount.incrementAndGet();
            return Future.succeededFuture();
        });

        Future<Void> internationalSubscription = internationalConsumer.subscribe(message -> {
            logger.info("\ud83c\udf0d International processor received: {}", message.getPayload().getMessageId());
            internationalCount.incrementAndGet();
            return Future.succeededFuture();
        });

        Future<Void> expressSubscription = expressConsumer.subscribe(message -> {
            logger.info("\u26a1 Express processor received: {}", message.getPayload().getMessageId());
            expressCount.incrementAndGet();
            return Future.succeededFuture();
        });

        Future.all(List.of(
                        domesticSubscription,
                        internationalSubscription,
                        expressSubscription,
                        routerSubscription))
                .compose(v -> sendOrders(inputProducer))
                .compose(v -> awaitCompletedCount(vertx, inputTopic, 6, 100))
                .compose(v -> awaitCompletedCount(vertx, domesticTopic, 1, 100))
                .compose(v -> awaitCompletedCount(vertx, internationalTopic, 1, 100))
                .compose(v -> awaitCompletedCount(vertx, expressTopic, 4, 100))
                .onSuccess(v -> testContext.verify(() -> {
                    assertEquals(1, domesticCount.intValue(), "Should route 1 domestic order");
                    assertEquals(1, internationalCount.intValue(), "Should route 1 international order");
                    assertEquals(4, expressCount.intValue(), "Should route 4 express orders");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    private Future<Void> sendRequests(MessageProducer<IntegrationMessage> producer) {
        List<Future<?>> sends = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String correlationId = "order-" + i;
            IntegrationMessage request = new IntegrationMessage(
                    "req-" + i,
                    "ORDER_REQUEST",
                    "client-service",
                    "order-service",
                    correlationId,
                    "{\"customerId\": \"cust-" + i + "\", \"items\": [\"item1\", \"item2\"]}",
                    "2025-01-01T00:00:00Z",
                    Map.of("replyTo", "client-service"));
            sends.add(producer.send(request));
        }
        return Future.all(sends).mapEmpty();
    }

    private Future<Void> publishEvents(
            MessageProducer<IntegrationMessage> emailProducer,
            MessageProducer<IntegrationMessage> analyticsProducer,
            MessageProducer<IntegrationMessage> auditProducer) {
        String[] eventTypes = {"CUSTOMER_CREATED", "CUSTOMER_UPDATED", "CUSTOMER_DELETED"};
        List<Future<?>> sends = new ArrayList<>();
        for (int i = 0; i < eventTypes.length; i++) {
            IntegrationMessage event = new IntegrationMessage(
                    "event-" + (i + 1),
                    eventTypes[i],
                    "customer-service",
                    "all-subscribers",
                    "customer-123",
                    "{\"customerId\": \"customer-123\", \"action\": \"" + eventTypes[i] + "\"}",
                    "2025-01-01T00:00:00Z",
                    Map.of("eventType", eventTypes[i]));
            sends.add(emailProducer.send(event));
            sends.add(analyticsProducer.send(event));
            sends.add(auditProducer.send(event));
        }
        return Future.all(sends).mapEmpty();
    }

    private Future<Void> sendOrders(MessageProducer<IntegrationMessage> producer) {
        List<Future<?>> sends = new ArrayList<>();
        sends.add(producer.send(new IntegrationMessage(
                "order-1", "ORDER", "order-service", "router", "corr-1",
                "{\"orderId\": \"order-1\", \"customerId\": \"cust-1\"}", "2025-01-01T00:00:00Z",
                Map.of("country", "US", "priority", "normal"))));
        sends.add(producer.send(new IntegrationMessage(
                "order-2", "ORDER", "order-service", "router", "corr-2",
                "{\"orderId\": \"order-2\", \"customerId\": \"cust-2\"}", "2025-01-01T00:00:00Z",
                Map.of("country", "CA", "priority", "normal"))));
        for (int i = 3; i <= 6; i++) {
            sends.add(producer.send(new IntegrationMessage(
                    "order-" + i, "ORDER", "order-service", "router", "corr-" + i,
                    "{\"orderId\": \"order-" + i + "\", \"customerId\": \"cust-" + i + "\"}",
                    "2025-01-01T00:00:00Z",
                    Map.of("country", i % 2 == 0 ? "US" : "UK", "priority", "express"))));
        }
        return Future.all(sends).mapEmpty();
    }

    private Future<Integer> awaitCompletedCount(
            Vertx vertx,
            String topic,
            int expectedCount,
            int remainingAttempts) {
        return statusCount(topic, "COMPLETED").compose(count -> {
            if (count == expectedCount) {
                return Future.succeededFuture(count);
            }
            if (remainingAttempts == 0) {
                return Future.failedFuture(
                        "Expected " + expectedCount + " completed messages for " + topic + " but found " + count);
            }
            return vertx.timer(100)
                    .compose(v -> awaitCompletedCount(vertx, topic, expectedCount, remainingAttempts - 1));
        });
    }

    private Future<Integer> statusCount(String topic, String status) {
        return manager.getDatabaseService().getConnectionProvider()
                .getReactivePool("peegeeq-main")
                .compose(pool -> pool.withConnection(connection -> connection.preparedQuery("""
                        SELECT CAST(COUNT(*) AS INTEGER) AS message_count
                        FROM outbox
                        WHERE topic = $1 AND status = $2
                        """).execute(Tuple.of(topic, status))))
                .map(rows -> rows.iterator().next().getInteger("message_count"));
    }

    /**
     * Integration message class for testing
     */
    public static class IntegrationMessage {
        private final String messageId;
        private final String messageType;
        private final String source;
        private final String destination;
        private final String correlationId;
        private final String payload;
        private final String timestamp; // Use String instead of Instant to avoid serialization issues
        private final Map<String, String> headers;

        @JsonCreator
        public IntegrationMessage(
                @JsonProperty("messageId") String messageId,
                @JsonProperty("messageType") String messageType,
                @JsonProperty("source") String source,
                @JsonProperty("destination") String destination,
                @JsonProperty("correlationId") String correlationId,
                @JsonProperty("payload") String payload,
                @JsonProperty("timestamp") String timestamp,
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
        public String getTimestamp() { return timestamp; }
        public Map<String, String> getHeaders() { return headers; }

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
}
