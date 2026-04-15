package dev.mars.peegeeq.outbox;

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

import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test to validate that JSONB conversion is working correctly.
 * This test verifies that data is stored as proper JSONB objects rather than JSON strings.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class JsonbConversionValidationTest {
    private static final Logger logger = LoggerFactory.getLogger(JsonbConversionValidationTest.class);


    private static final String[] SYSTEM_PROPERTIES = {
        "peegeeq.database.host", "peegeeq.database.port", "peegeeq.database.name",
        "peegeeq.database.username", "peegeeq.database.password", "peegeeq.database.ssl.enabled"
    };

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory factory;
    private Vertx testVertx;
    private PgConnectionManager connectionManager;
    private Pool reactivePool;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("jsonb-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        factory = new OutboxFactory(manager.getDatabaseService());

        testVertx = Vertx.vertx();
        connectionManager = new PgConnectionManager(testVertx);
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();
        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).build();
        reactivePool = connectionManager.getOrCreateReactivePool("test-verification", connectionConfig, poolConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("Tearing down: closing resources and manager");
        if (factory != null) {
            factory.close();
        }
        if (connectionManager != null) {
            connectionManager.close();
        }
        if (testVertx != null) {
            testVertx.close().await();
        }
        if (manager != null) {
            manager.closeReactive().await();
        }
        for (String prop : SYSTEM_PROPERTIES) {
            System.clearProperty(prop);
        }
    }

    /**
     * Test that simple string payloads are stored as proper JSONB objects.
     */
    @Test
    void testSimpleStringPayloadStoredAsJsonb() throws Exception {
        logger.info("Test: simple string payload stored as jsonb");
        String testMessage = "Hello, JSONB World!";
        String topic = "jsonb-test-simple";

        MessageProducer<String> producer = factory.createProducer(topic, String.class);
        try {
            producer.send(testMessage).await();

            Row row = reactivePool.withConnection(conn ->
                conn.preparedQuery("""
                    SELECT jsonb_typeof(payload) as payload_type,
                           payload->>'value' as extracted_value
                    FROM outbox
                    WHERE topic = $1
                    ORDER BY id DESC
                    LIMIT 1
                    """)
                    .execute(Tuple.of(topic))
                    .map(rows -> {
                        assertTrue(rows.size() > 0, "Should find the inserted message");
                        return rows.iterator().next();
                    })
            ).await();

            assertEquals("object", row.getString("payload_type"),
                    "Payload should be stored as JSONB object, not string");
            assertEquals(testMessage, row.getString("extracted_value"),
                    "Should be able to extract value using JSON operators");
        } finally {
            producer.close();
        }
    }

    /**
     * Test that complex object payloads are stored as proper JSONB objects.
     */
    @Test
    void testComplexObjectPayloadStoredAsJsonb() throws Exception {
        logger.info("Test: complex object payload stored as jsonb");
        OrderEvent testOrder = new OrderEvent("ORD-JSONB-001", "customer-123", new BigDecimal("299.99"), "PROCESSING");
        String topic = "jsonb-test-complex";

        Map<String, String> headers = new HashMap<>();
        headers.put("source", "jsonb-test");
        headers.put("version", "1.0");
        headers.put("correlationId", "test-correlation-123");

        MessageProducer<OrderEvent> producer = factory.createProducer(topic, OrderEvent.class);
        try {
            producer.send(testOrder, headers).await();

            Row row = reactivePool.withConnection(conn ->
                conn.preparedQuery("""
                    SELECT jsonb_typeof(payload) as payload_type,
                           jsonb_typeof(headers) as headers_type,
                           payload->>'orderId' as order_id,
                           payload->>'amount' as amount,
                           headers->>'correlationId' as correlation_id
                    FROM outbox
                    WHERE topic = $1
                    ORDER BY id DESC
                    LIMIT 1
                    """)
                    .execute(Tuple.of(topic))
                    .map(rows -> {
                        assertTrue(rows.size() > 0, "Should find the inserted message");
                        return rows.iterator().next();
                    })
            ).await();

            assertEquals("object", row.getString("payload_type"),
                    "Payload should be stored as JSONB object");
            assertEquals("object", row.getString("headers_type"),
                    "Headers should be stored as JSONB object");
            assertEquals("ORD-JSONB-001", row.getString("order_id"),
                    "Should extract orderId from payload");
            assertEquals("299.99", row.getString("amount"),
                    "Should extract amount from payload");
            assertEquals("test-correlation-123", row.getString("correlation_id"),
                    "Should extract correlationId from headers");
        } finally {
            producer.close();
        }
    }

    /**
     * Test that consumers can properly read and parse JSONB objects.
     */
    @Test
    void testConsumerCanReadJsonbObjects() throws Exception {
        logger.info("Test: consumer can read jsonb objects");
        String topic = "jsonb-test-consumer";
        OrderEvent testOrder = new OrderEvent("ORD-CONSUMER-001", "customer-456", new BigDecimal("199.99"), "COMPLETED");

        Map<String, String> headers = new HashMap<>();
        headers.put("source", "consumer-test");
        headers.put("priority", "HIGH");

        MessageProducer<OrderEvent> producer = factory.createProducer(topic, OrderEvent.class);
        MessageConsumer<OrderEvent> consumer = factory.createConsumer(topic, OrderEvent.class);
        try {
            producer.send(testOrder, headers).await();

            Promise<Void> done = Promise.promise();
            AtomicInteger processedCount = new AtomicInteger(0);

            consumer.subscribe(message -> {
                try {
                    OrderEvent receivedOrder = message.getPayload();
                    assertNotNull(receivedOrder, "Payload should not be null");
                    assertEquals("ORD-CONSUMER-001", receivedOrder.getOrderId(), "OrderId should match");
                    assertEquals("customer-456", receivedOrder.getCustomerId(), "CustomerId should match");
                    assertEquals(new BigDecimal("199.99"), receivedOrder.getAmount(), "Amount should match");
                    assertEquals("COMPLETED", receivedOrder.getStatus(), "Status should match");

                    Map<String, String> receivedHeaders = message.getHeaders();
                    assertNotNull(receivedHeaders, "Headers should not be null");
                    assertEquals("consumer-test", receivedHeaders.get("source"), "Source header should match");
                    assertEquals("HIGH", receivedHeaders.get("priority"), "Priority header should match");

                    processedCount.incrementAndGet();
                    done.complete();
                    return Future.succeededFuture();
                } catch (Exception e) {
                    done.tryFail(e);
                    return Future.failedFuture(e);
                }
            });

            done.future().await();
            assertEquals(1, processedCount.get(), "Should have processed exactly 1 message");
        } finally {
            consumer.close();
            producer.close();
        }
    }

    /**
     * Simple test data class for complex object testing.
     */
    public static class OrderEvent {
        private String orderId;
        private String customerId;
        private BigDecimal amount;
        private String status;

        public OrderEvent() {} // Required for Jackson

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
    }
}


