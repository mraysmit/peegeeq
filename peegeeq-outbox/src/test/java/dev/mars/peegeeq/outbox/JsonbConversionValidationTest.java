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
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Test to validate that JSONB conversion is working correctly.
 * This test verifies that data is stored as proper JSONB objects rather than JSON strings.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JsonbConversionValidationTest {

    private static final Logger logger = LoggerFactory.getLogger(JsonbConversionValidationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private PeeGeeQManager manager;
    private OutboxFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        // Set system properties for test configuration
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");

        // Initialize PeeGeeQ Manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("jsonb-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Initialize schema
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        // Create factory
        factory = new OutboxFactory(manager.getDatabaseService());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (factory != null) {
            factory.close();
        }
        if (manager != null) {
            manager.close();
        }
    }

    /**
     * Test that simple string payloads are stored as proper JSONB objects.
     */
    @Test
    @Order(1)
    void testSimpleStringPayloadStoredAsJsonb() throws Exception {
        logger.info("=== Testing Simple String Payload JSONB Storage ===");

        String testMessage = "Hello, JSONB World!";
        String topic = "jsonb-test-simple";

        // Send message
        MessageProducer<String> producer = factory.createProducer(topic, String.class);
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Verify JSONB storage directly in database
        String dbUrl = String.format("jdbc:postgresql://%s:%d/%s", 
                postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());
        
        try (Connection conn = DriverManager.getConnection(dbUrl, postgres.getUsername(), postgres.getPassword())) {
            String sql = """
                SELECT payload, jsonb_typeof(payload) as payload_type, 
                       payload->>'value' as extracted_value
                FROM outbox 
                WHERE topic = ? 
                ORDER BY id DESC 
                LIMIT 1
                """;
            
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, topic);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertTrue(rs.next(), "Should find the inserted message");
                    
                    // Verify it's stored as JSONB object, not string
                    String payloadType = rs.getString("payload_type");
                    assertEquals("object", payloadType, "Payload should be stored as JSONB object, not string");
                    
                    // Verify we can extract the value using JSON operators
                    String extractedValue = rs.getString("extracted_value");
                    assertEquals(testMessage, extractedValue, "Should be able to extract value using JSON operators");
                    
                    logger.info("✅ Simple string payload correctly stored as JSONB object");
                    logger.info("   Payload type: {}", payloadType);
                    logger.info("   Extracted value: {}", extractedValue);
                }
            }
        }

        producer.close();
    }

    /**
     * Test that complex object payloads are stored as proper JSONB objects.
     */
    @Test
    @Order(2)
    void testComplexObjectPayloadStoredAsJsonb() throws Exception {
        logger.info("=== Testing Complex Object Payload JSONB Storage ===");

        // Create complex test object
        OrderEvent testOrder = new OrderEvent("ORD-JSONB-001", "customer-123", new BigDecimal("299.99"), "PROCESSING");
        String topic = "jsonb-test-complex";

        // Send message with headers
        Map<String, String> headers = new HashMap<>();
        headers.put("source", "jsonb-test");
        headers.put("version", "1.0");
        headers.put("correlationId", "test-correlation-123");

        MessageProducer<OrderEvent> producer = factory.createProducer(topic, OrderEvent.class);
        producer.send(testOrder, headers).get(5, TimeUnit.SECONDS);

        // Verify JSONB storage directly in database
        String dbUrl = String.format("jdbc:postgresql://%s:%d/%s", 
                postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());
        
        try (Connection conn = DriverManager.getConnection(dbUrl, postgres.getUsername(), postgres.getPassword())) {
            String sql = """
                SELECT payload, headers,
                       jsonb_typeof(payload) as payload_type,
                       jsonb_typeof(headers) as headers_type,
                       payload->>'orderId' as order_id,
                       payload->>'amount' as amount,
                       headers->>'correlationId' as correlation_id
                FROM outbox 
                WHERE topic = ? 
                ORDER BY id DESC 
                LIMIT 1
                """;
            
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, topic);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertTrue(rs.next(), "Should find the inserted message");
                    
                    // Verify both payload and headers are stored as JSONB objects
                    String payloadType = rs.getString("payload_type");
                    String headersType = rs.getString("headers_type");
                    assertEquals("object", payloadType, "Payload should be stored as JSONB object");
                    assertEquals("object", headersType, "Headers should be stored as JSONB object");
                    
                    // Verify we can extract nested values using JSON operators
                    String orderId = rs.getString("order_id");
                    String amount = rs.getString("amount");
                    String correlationId = rs.getString("correlation_id");
                    
                    assertEquals("ORD-JSONB-001", orderId, "Should extract orderId from payload");
                    assertEquals("299.99", amount, "Should extract amount from payload");
                    assertEquals("test-correlation-123", correlationId, "Should extract correlationId from headers");
                    
                    logger.info("✅ Complex object payload correctly stored as JSONB object");
                    logger.info("   Payload type: {}, Headers type: {}", payloadType, headersType);
                    logger.info("   Extracted orderId: {}, amount: {}, correlationId: {}", orderId, amount, correlationId);
                }
            }
        }

        producer.close();
    }

    /**
     * Test that consumers can properly read and parse JSONB objects.
     */
    @Test
    @Order(3)
    void testConsumerCanReadJsonbObjects() throws Exception {
        logger.info("=== Testing Consumer JSONB Object Reading ===");

        String topic = "jsonb-test-consumer";
        OrderEvent testOrder = new OrderEvent("ORD-CONSUMER-001", "customer-456", new BigDecimal("199.99"), "COMPLETED");
        
        Map<String, String> headers = new HashMap<>();
        headers.put("source", "consumer-test");
        headers.put("priority", "HIGH");

        // Send message
        MessageProducer<OrderEvent> producer = factory.createProducer(topic, OrderEvent.class);
        producer.send(testOrder, headers).get(5, TimeUnit.SECONDS);

        // Consume message
        MessageConsumer<OrderEvent> consumer = factory.createConsumer(topic, OrderEvent.class);
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger processedCount = new AtomicInteger(0);
        
        consumer.subscribe(message -> {
            try {
                // Verify the payload was correctly deserialized from JSONB
                OrderEvent receivedOrder = message.getPayload();
                assertNotNull(receivedOrder, "Payload should not be null");
                assertEquals("ORD-CONSUMER-001", receivedOrder.getOrderId(), "OrderId should match");
                assertEquals("customer-456", receivedOrder.getCustomerId(), "CustomerId should match");
                assertEquals(new BigDecimal("199.99"), receivedOrder.getAmount(), "Amount should match");
                assertEquals("COMPLETED", receivedOrder.getStatus(), "Status should match");
                
                // Verify headers were correctly deserialized from JSONB
                Map<String, String> receivedHeaders = message.getHeaders();
                assertNotNull(receivedHeaders, "Headers should not be null");
                assertEquals("consumer-test", receivedHeaders.get("source"), "Source header should match");
                assertEquals("HIGH", receivedHeaders.get("priority"), "Priority header should match");
                
                processedCount.incrementAndGet();
                latch.countDown();
                
                logger.info("✅ Consumer successfully read JSONB objects");
                logger.info("   Received order: {}", receivedOrder.getOrderId());
                logger.info("   Received headers: {}", receivedHeaders.size());
                
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            } catch (Exception e) {
                logger.error("Error processing message", e);
                throw new RuntimeException(e);
            }
        });

        // Wait for message processing
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Message should be processed within 10 seconds");
        assertEquals(1, processedCount.get(), "Should have processed exactly 1 message");

        consumer.close();
        producer.close();
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
