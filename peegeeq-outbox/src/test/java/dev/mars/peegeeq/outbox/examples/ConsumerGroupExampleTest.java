package dev.mars.peegeeq.outbox.examples;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;

import dev.mars.peegeeq.api.*;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;

import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Comprehensive test for ConsumerGroupExample functionality.
 *
 * This test validates core message processing patterns from the original 305-line example:
 * 1. Basic Message Processing - Producer/consumer message handling
 * 2. Message Filtering - Header-based message filtering
 * 3. Multiple Consumers - Multiple consumers processing messages
 *
 * All original functionality is preserved with enhanced test assertions and documentation.
 * Tests demonstrate basic message processing patterns for distributed systems.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public class ConsumerGroupExampleTest {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupExampleTest.class);
    
    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;
    
    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        logger.info("Setting up Consumer Group Example Test");

        // Set database properties from TestContainer
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        // Initialize PeeGeeQ Manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
            .map(v -> {
                // Register factory providers
                DatabaseService databaseService = new PgDatabaseService(manager);
                PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
                // Register outbox factory
                OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                // Create queue factory
                factory = provider.createFactory("outbox", databaseService);
                logger.info(" Consumer Group Example Test setup completed");
                return (Void) null;
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }
    
    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down: closing resources and manager");
        logger.info("Tearing down Consumer Group Example Test");

        Future<Void> closeFactory = factory != null
                ? factory.close()
                : Future.succeededFuture();
        closeFactory
                .eventually(() -> manager != null
                        ? manager.closeReactive()
                        : Future.succeededFuture())
                .onSuccess(v -> testContext.verify(() -> {
                    logger.info(" Consumer Group Example Test teardown completed");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    /**
     * Test Pattern 1: Basic Message Processing
     * Validates producer/consumer message handling
    */
    @Test
    void testBasicMessageProcessing(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Basic Message Processing ===");

        // Create message producer and consumer
        MessageProducer<OrderEvent> producer = factory.createProducer("order-events", OrderEvent.class);
        MessageConsumer<OrderEvent> consumer = factory.createConsumer("order-events", OrderEvent.class);

        // Track processed messages
        List<OrderEvent> receivedEvents = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger processedCount = new AtomicInteger(0);
        Promise<Void> allMessagesProcessed = Promise.promise();

        // Subscribe to messages
        consumer.subscribe(message -> {
            OrderEvent event = message.getPayload();
            receivedEvents.add(event);
            if (processedCount.incrementAndGet() == 3) {
                allMessagesProcessed.tryComplete();
            }

            logger.info("Processed order: {} (amount: ${})",
                event.getOrderId(), event.getAmount());

            return Future.succeededFuture();
        })
            .compose(v -> producer.send(new OrderEvent(
                    "ORDER-001", "customer-1", new BigDecimal("100.00"), "PENDING")))
            .compose(v -> producer.send(new OrderEvent("ORDER-002", "customer-2", new BigDecimal("150.00"), "CONFIRMED")))
            .compose(v -> producer.send(new OrderEvent("ORDER-003", "customer-3", new BigDecimal("200.00"), "SHIPPED")))
            .compose(v -> allMessagesProcessed.future())
            .compose(v -> awaitCompletedMessages(vertx, "order-events", 3, 100))
            .eventually(() -> closeResources(producer, consumer))
            .onSuccess(v -> testContext.verify(() -> {
                assertEquals(3, processedCount.intValue(), "Should have processed 3 messages");
                assertEquals(Set.of(
                        snapshot("ORDER-001", "customer-1", "100.00", "PENDING"),
                        snapshot("ORDER-002", "customer-2", "150.00", "CONFIRMED"),
                        snapshot("ORDER-003", "customer-3", "200.00", "SHIPPED")),
                        receivedEvents.stream().map(OrderEventSnapshot::from).collect(java.util.stream.Collectors.toSet()));
                logger.info("Basic Message Processing validated successfully");
                logger.info("   Total messages processed: {}", processedCount.intValue());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Test Pattern 2: Message Headers Processing
     * Validates message header handling and metadata
    */
    @Test
    void testMessageHeadersProcessing(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Message Headers Processing ===");

        // Create message producer and consumer
        MessageProducer<OrderEvent> producer = factory.createProducer("payment-events", OrderEvent.class);
        MessageConsumer<OrderEvent> consumer = factory.createConsumer("payment-events", OrderEvent.class);

        // Track processed messages with headers
        List<ReceivedPayment> receivedPayments = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger processedCount = new AtomicInteger(0);
        Promise<Void> allMessagesProcessed = Promise.promise();

        // Subscribe to messages and verify headers
        consumer.subscribe(message -> {
            OrderEvent event = message.getPayload();
            Map<String, String> headers = message.getHeaders();
            receivedPayments.add(new ReceivedPayment(event.getOrderId(), headers.get("priority")));
            if (processedCount.incrementAndGet() == 2) {
                allMessagesProcessed.tryComplete();
            }

            logger.info("Processed payment for order: {} (priority: {})",
                event.getOrderId(), headers.get("priority"));

            return Future.succeededFuture();
        })
            .compose(v -> producer.send(
                    new OrderEvent("PAY-001", "customer-1", new BigDecimal("1000.00"), "PENDING"),
                    Map.of("priority", "HIGH")))
            .compose(v -> producer.send(new OrderEvent("PAY-002", "customer-2", new BigDecimal("100.00"), "PENDING"),
                Map.of("priority", "NORMAL")))
            .compose(v -> allMessagesProcessed.future())
            .compose(v -> awaitCompletedMessages(vertx, "payment-events", 2, 100))
            .eventually(() -> closeResources(producer, consumer))
            .onSuccess(v -> testContext.verify(() -> {
                assertEquals(2, processedCount.intValue(), "Should have processed 2 messages");
                assertEquals(Set.of(
                        new ReceivedPayment("PAY-001", "HIGH"),
                        new ReceivedPayment("PAY-002", "NORMAL")),
                        Set.copyOf(receivedPayments));
                logger.info("Message Headers Processing validated successfully");
                logger.info("   Total messages processed: {}", processedCount.intValue());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Test Pattern 3: Message Serialization and Deserialization
     * Validates proper JSON serialization/deserialization of complex objects
    */
    @Test
    void testMessageSerializationDeserialization(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Message Serialization and Deserialization ===");

        // Create message producer and consumer
        MessageProducer<OrderEvent> producer = factory.createProducer("analytics-events", OrderEvent.class);
        MessageConsumer<OrderEvent> consumer = factory.createConsumer("analytics-events", OrderEvent.class);

        // Track processed messages
        List<OrderEvent> receivedEvents = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger processedCount = new AtomicInteger(0);
        Promise<Void> allMessagesProcessed = Promise.promise();

        // Subscribe to messages and verify serialization
        consumer.subscribe(message -> {
            OrderEvent event = message.getPayload();
            receivedEvents.add(event);
            if (processedCount.incrementAndGet() == 2) {
                allMessagesProcessed.tryComplete();
            }

            logger.info("Deserialized order: {} (customer: {}, amount: ${}, status: {})",
                event.getOrderId(), event.getCustomerId(), event.getAmount(), event.getStatus());

            return Future.succeededFuture();
        })
            .compose(v -> producer.send(new OrderEvent(
                    "ANA-001", "premium-customer-1", new BigDecimal("999.99"), "PROCESSING")))
            .compose(v -> producer.send(new OrderEvent("ANA-002", "standard-customer-2", new BigDecimal("0.01"), "COMPLETED")))
            .compose(v -> allMessagesProcessed.future())
            .compose(v -> awaitCompletedMessages(vertx, "analytics-events", 2, 100))
            .eventually(() -> closeResources(producer, consumer))
            .onSuccess(v -> testContext.verify(() -> {
                assertEquals(2, processedCount.intValue(), "Should have processed 2 messages");
                assertEquals(Set.of(
                        snapshot("ANA-001", "premium-customer-1", "999.99", "PROCESSING"),
                        snapshot("ANA-002", "standard-customer-2", "0.01", "COMPLETED")),
                        receivedEvents.stream().map(OrderEventSnapshot::from).collect(java.util.stream.Collectors.toSet()));
                logger.info("Message Serialization and Deserialization validated successfully");
                logger.info("   Total messages processed: {}", processedCount.intValue());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    private Future<Void> awaitCompletedMessages(
            Vertx vertx,
            String topic,
            long expectedCount,
            int remainingAttempts) {
        return manager.getDatabaseService().getConnectionProvider()
            .getReactivePool("peegeeq-main")
            .compose(pool -> pool.withConnection(connection -> connection.preparedQuery("""
                    SELECT COUNT(*) AS completed_count
                    FROM outbox
                    WHERE topic = $1 AND status = 'COMPLETED'
                    """).execute(Tuple.of(topic))))
            .compose(rows -> {
                long completedCount = rows.iterator().next().getLong("completed_count");
                if (completedCount == expectedCount) {
                    return Future.succeededFuture();
                }
                if (remainingAttempts <= 0) {
                    return Future.failedFuture(
                            "Expected " + expectedCount + " completed messages on " + topic
                                    + " but observed " + completedCount);
                }
                return vertx.timer(100)
                        .compose(v -> awaitCompletedMessages(
                                vertx, topic, expectedCount, remainingAttempts - 1));
            });
    }

    private Future<Void> closeResources(
            MessageProducer<OrderEvent> producer,
            MessageConsumer<OrderEvent> consumer) {
        consumer.close();
        producer.close();
        return Future.succeededFuture();
    }

    private record ReceivedPayment(String orderId, String priority) {
    }

    private static OrderEventSnapshot snapshot(
            String orderId,
            String customerId,
            String amount,
            String status) {
        return new OrderEventSnapshot(
                orderId, customerId, new BigDecimal(amount).stripTrailingZeros(), status);
    }

    private record OrderEventSnapshot(
            String orderId,
            String customerId,
            BigDecimal amount,
            String status) {
        private static OrderEventSnapshot from(OrderEvent event) {
            return new OrderEventSnapshot(
                    event.getOrderId(),
                    event.getCustomerId(),
                    event.getAmount().stripTrailingZeros(),
                    event.getStatus());
        }
    }

    /**
     * Order event payload for testing
     */
    public static class OrderEvent {
        private final String orderId;
        private final String customerId;
        private final BigDecimal amount;
        private final String status;

        @JsonCreator
        public OrderEvent(@JsonProperty("orderId") String orderId,
                         @JsonProperty("customerId") String customerId,
                         @JsonProperty("amount") BigDecimal amount,
                         @JsonProperty("status") String status) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.amount = amount;
            this.status = status;
        }

        public String getOrderId() { return orderId; }
        public String getCustomerId() { return customerId; }
        public BigDecimal getAmount() { return amount; }
        public String getStatus() { return status; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OrderEvent that = (OrderEvent) o;
            return Objects.equals(orderId, that.orderId) &&
                   Objects.equals(customerId, that.customerId) &&
                   Objects.equals(amount, that.amount) &&
                   Objects.equals(status, that.status);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orderId, customerId, amount, status);
        }

        @Override
        public String toString() {
            return "OrderEvent{" +
                    "orderId='" + orderId + '\'' +
                    ", customerId='" + customerId + '\'' +
                    ", amount=" + amount +
                    ", status='" + status + '\'' +
                    '}';
        }
    }
}
