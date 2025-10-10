package dev.mars.peegeeq.examples.springbootintegrated;

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

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.examples.springbootintegrated.events.OrderEvent;
import dev.mars.peegeeq.examples.springbootintegrated.model.CreateOrderRequest;
import dev.mars.peegeeq.examples.springbootintegrated.model.OrderResponse;
import dev.mars.peegeeq.examples.springbootintegrated.service.OrderService;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for Spring Boot Integrated Outbox + Bi-Temporal Example.
 * 
 * <p>This test validates the complete integration pattern where:
 * <ul>
 *   <li>Order is saved to database</li>
 *   <li>Event is sent to outbox (for immediate processing)</li>
 *   <li>Event is appended to bi-temporal store (for historical queries)</li>
 *   <li>All three operations are in a SINGLE transaction</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@SpringBootTest
@Testcontainers
class SpringBootIntegratedApplicationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(SpringBootIntegratedApplicationTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        logger.info("Configuring properties for SpringBootIntegrated test");
        SharedTestContainers.configureSharedProperties(registry);
    }

    @BeforeAll
    static void initializeSchema() {
        logger.info("Initializing database schema for Spring Boot integrated application test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer (ALL components)");
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private DatabaseService databaseService;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("=== Setting up application-specific tables ===");

        // Create orders table for this specific test
        String createOrdersTable = """
            CREATE TABLE IF NOT EXISTS orders (
                id VARCHAR(255) PRIMARY KEY,
                customer_id VARCHAR(255) NOT NULL,
                amount DECIMAL(19, 2) NOT NULL,
                status VARCHAR(50) NOT NULL,
                description TEXT,
                created_at TIMESTAMPTZ NOT NULL
            )
            """;

        // Execute application-specific schema creation
        databaseService.getConnectionProvider()
            .withTransaction("peegeeq-main", connection -> {
                return connection.query(createOrdersTable).execute()
                    .map(v -> {
                        logger.info("Application-specific schema created successfully");
                        return (Void) null;
                    });
            }).toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);

        logger.info("=== Application-specific schema setup complete ===");
    }

    @Test
    void testIntegratedTransactionSuccess() throws Exception {
        logger.info("=== Testing Integrated Transaction Success ===");
        
        // Create order request
        CreateOrderRequest request = new CreateOrderRequest();
        request.setCustomerId("CUST-001");
        request.setAmount(new BigDecimal("1500.00"));
        request.setDescription("Laptop purchase");
        request.setValidTime(Instant.now());
        
        // Create order (integrated transaction)
        String orderId = orderService.createOrder(request).get(10, TimeUnit.SECONDS);
        assertNotNull(orderId);
        logger.info("Order created: {}", orderId);
        
        // Wait a bit for async operations
        Thread.sleep(500);
        
        // Verify 1: Order saved to database
        boolean orderExists = verifyOrderInDatabase(orderId);
        assertTrue(orderExists, "Order should exist in database");
        logger.info("✅ Order found in database");
        
        // Verify 2: Event sent to outbox
        boolean eventInOutbox = verifyEventInOutbox(orderId);
        assertTrue(eventInOutbox, "Event should exist in outbox");
        logger.info("✅ Event found in outbox");
        
        // Verify 3: Event appended to event store
        OrderResponse history = orderService.getOrderHistory(orderId).get(10, TimeUnit.SECONDS);
        assertNotNull(history);
        assertTrue(history.getHistory().size() >= 1, "Event should exist in event store");
        logger.info("✅ Event found in event store ({} events)", history.getHistory().size());
        
        // Verify event details
        BiTemporalEvent<OrderEvent> event = history.getHistory().get(0);
        assertEquals("OrderCreated", event.getEventType());
        assertEquals(orderId, event.getPayload().getOrderId());
        assertEquals("CUST-001", event.getPayload().getCustomerId());
        assertEquals(0, new BigDecimal("1500.00").compareTo(event.getPayload().getAmount()));
        
        logger.info("=== Test Passed: All three operations committed together ===");
    }
    
    @Test
    void testQueryOrderHistory() throws Exception {
        logger.info("=== Testing Query Order History ===");
        
        // Create order
        CreateOrderRequest request = new CreateOrderRequest();
        request.setCustomerId("CUST-002");
        request.setAmount(new BigDecimal("2500.00"));
        request.setDescription("Desktop purchase");
        
        String orderId = orderService.createOrder(request).get(10, TimeUnit.SECONDS);
        logger.info("Order created: {}", orderId);
        
        // Query order history
        OrderResponse history = orderService.getOrderHistory(orderId).get(10, TimeUnit.SECONDS);
        assertNotNull(history);
        assertEquals(orderId, history.getOrderId());
        assertTrue(history.getHistory().size() >= 1);
        
        logger.info("Order history retrieved: {} events", history.getHistory().size());
        logger.info("=== Test Passed ===");
    }
    
    @Test
    void testQueryCustomerOrders() throws Exception {
        logger.info("=== Testing Query Customer Orders ===");
        
        String customerId = "CUST-003";
        
        // Create multiple orders for same customer
        CreateOrderRequest request1 = new CreateOrderRequest();
        request1.setCustomerId(customerId);
        request1.setAmount(new BigDecimal("1000.00"));
        request1.setDescription("Order 1");
        
        CreateOrderRequest request2 = new CreateOrderRequest();
        request2.setCustomerId(customerId);
        request2.setAmount(new BigDecimal("2000.00"));
        request2.setDescription("Order 2");
        
        String orderId1 = orderService.createOrder(request1).get(10, TimeUnit.SECONDS);
        String orderId2 = orderService.createOrder(request2).get(10, TimeUnit.SECONDS);
        
        logger.info("Orders created: {}, {}", orderId1, orderId2);
        
        // Query customer orders
        List<BiTemporalEvent<OrderEvent>> orders = orderService.getCustomerOrders(customerId)
            .get(10, TimeUnit.SECONDS);
        
        assertNotNull(orders);
        assertTrue(orders.size() >= 2, "Should have at least 2 orders for customer");
        
        logger.info("Found {} orders for customer {}", orders.size(), customerId);
        logger.info("=== Test Passed ===");
    }
    
    @Test
    void testPointInTimeQuery() throws Exception {
        logger.info("=== Testing Point-in-Time Query ===");
        
        Instant beforeOrders = Instant.now();
        Thread.sleep(100);
        
        // Create order
        CreateOrderRequest request = new CreateOrderRequest();
        request.setCustomerId("CUST-004");
        request.setAmount(new BigDecimal("3000.00"));
        request.setDescription("Server purchase");
        
        String orderId = orderService.createOrder(request).get(10, TimeUnit.SECONDS);
        logger.info("Order created: {}", orderId);
        
        Instant afterOrders = Instant.now();
        
        // Query as of before orders
        List<BiTemporalEvent<OrderEvent>> beforeResults = orderService.getOrdersAsOfTime(beforeOrders)
            .get(10, TimeUnit.SECONDS);
        
        // Query as of after orders
        List<BiTemporalEvent<OrderEvent>> afterResults = orderService.getOrdersAsOfTime(afterOrders)
            .get(10, TimeUnit.SECONDS);
        
        // After should have more orders than before
        assertTrue(afterResults.size() >= beforeResults.size(), 
            "After timestamp should have more or equal orders");
        
        logger.info("Orders before: {}, after: {}", beforeResults.size(), afterResults.size());
        logger.info("=== Test Passed ===");
    }
    
    /**
     * Verifies that an order exists in the database.
     */
    private boolean verifyOrderInDatabase(String orderId) throws Exception {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        databaseService.getConnectionProvider().withConnection("peegeeq-main", connection -> {
            String sql = "SELECT COUNT(*) as count FROM orders WHERE id = $1";
            return connection.preparedQuery(sql)
                .execute(Tuple.of(orderId))
                .map(rows -> {
                    int count = rows.iterator().next().getInteger("count");
                    future.complete(count > 0);
                    return null;
                });
        });
        
        return future.get(5, TimeUnit.SECONDS);
    }
    
    /**
     * Verifies that an event exists in the outbox.
     */
    private boolean verifyEventInOutbox(String orderId) throws Exception {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        databaseService.getConnectionProvider().withConnection("peegeeq-main", connection -> {
            String sql = "SELECT COUNT(*) as count FROM outbox WHERE payload::text LIKE $1";
            return connection.preparedQuery(sql)
                .execute(Tuple.of("%" + orderId + "%"))
                .map(rows -> {
                    int count = rows.iterator().next().getInteger("count");
                    future.complete(count > 0);
                    return null;
                });
        });
        
        return future.get(5, TimeUnit.SECONDS);
    }
}

