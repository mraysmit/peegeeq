package dev.mars.peegeeq.examples.springboot;

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

import dev.mars.peegeeq.examples.springboot.events.OrderEvent;
import dev.mars.peegeeq.examples.springboot.model.CreateOrderRequest;
import dev.mars.peegeeq.examples.springboot.model.OrderItem;
import dev.mars.peegeeq.examples.springboot.service.OrderService;
import dev.mars.peegeeq.outbox.OutboxProducer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the Order Service using Spring Boot Test framework.
 * 
 * This test verifies the service layer functionality and its integration with
 * the PeeGeeQ transactional outbox pattern.
 * 
 * Key Features Tested:
 * - Order creation with transactional consistency
 * - Event publishing through outbox pattern
 * - Transactional rollback scenarios
 * - Business validation logic
 * - Reactive operations with CompletableFuture
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-09
 * @version 1.0
 */
@SpringBootTest(
    classes = SpringBootOutboxApplication.class,
    properties = {
        "spring.profiles.active=test",
        "logging.level.dev.mars.peegeeq=DEBUG"
    }
)
@Testcontainers
class OrderServiceTest {
    
    private static final Logger logger = LoggerFactory.getLogger(OrderServiceTest.class);
    
    @Autowired
    private OrderService orderService;
    
    @Autowired
    private OutboxProducer<OrderEvent> orderEventProducer;
    
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_test")
            .withUsername("test_user")
            .withPassword("test_password")
            .withSharedMemorySize(256 * 1024 * 1024L);
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        logger.info("Configuring properties for OrderService test");
        
        registry.add("peegeeq.database.host", postgres::getHost);
        registry.add("peegeeq.database.port", () -> postgres.getFirstMappedPort().toString());
        registry.add("peegeeq.database.name", postgres::getDatabaseName);
        registry.add("peegeeq.database.username", postgres::getUsername);
        registry.add("peegeeq.database.password", postgres::getPassword);
        registry.add("peegeeq.database.schema", () -> "public");
        registry.add("peegeeq.profile", () -> "test");
        registry.add("peegeeq.migration.enabled", () -> "true");
        registry.add("peegeeq.migration.auto-migrate", () -> "true");
    }
    
    /**
     * Test successful order creation.
     */
    @Test
    void testCreateOrderSuccess() throws Exception {
        logger.info("=== Testing Successful Order Creation ===");
        
        CreateOrderRequest request = createValidOrderRequest();
        
        CompletableFuture<String> future = orderService.createOrder(request);
        String orderId = future.get(10, TimeUnit.SECONDS);
        
        assertNotNull(orderId, "Order ID should not be null");
        assertFalse(orderId.isEmpty(), "Order ID should not be empty");
        
        logger.info("✅ Order created successfully with ID: {}", orderId);
        logger.info("✅ Successful order creation test passed");
    }
    
    /**
     * Test order validation functionality.
     */
    @Test
    void testValidateOrder() throws Exception {
        logger.info("=== Testing Order Validation ===");
        
        String orderId = "test-order-validation-123";
        
        CompletableFuture<Void> future = orderService.validateOrder(orderId);
        
        // Should complete without throwing an exception
        assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS));
        
        logger.info("✅ Order validation completed successfully");
        logger.info("✅ Order validation test passed");
    }
    
    /**
     * Test order creation with business validation that triggers rollback.
     */
    @Test
    void testCreateOrderWithBusinessValidationRollback() {
        logger.info("=== Testing Business Validation Rollback ===");

        // Create request that exceeds $10,000 limit
        CreateOrderRequest request = new CreateOrderRequest(
            "test-customer-" + System.currentTimeMillis(),
            new BigDecimal("15000"), // Exceeds $10,000 limit
            Arrays.asList(
                new OrderItem("item-1", "Expensive Item", 1, new BigDecimal("15000"))
            )
        );
        
        CompletableFuture<String> future = orderService.createOrderWithBusinessValidation(request);
        
        // Should complete exceptionally due to business validation failure
        Exception exception = assertThrows(Exception.class, () -> {
            future.get(10, TimeUnit.SECONDS);
        });
        
        assertTrue(exception.getMessage().contains("Order amount exceeds maximum limit") ||
                  exception.getCause().getMessage().contains("Order amount exceeds maximum limit"),
                  "Exception should mention amount limit");
        
        logger.info("✅ Business validation rollback triggered correctly");
        logger.info("✅ Business validation rollback test passed");
    }
    
    /**
     * Test order creation with invalid customer that triggers rollback.
     */
    @Test
    void testCreateOrderWithInvalidCustomerRollback() {
        logger.info("=== Testing Invalid Customer Rollback ===");

        // Create request with invalid customer ID
        CreateOrderRequest request = new CreateOrderRequest(
            "INVALID_CUSTOMER", // Triggers validation failure
            new BigDecimal("99.99"),
            Arrays.asList(
                new OrderItem("item-1", "Test Item 1", 2, new BigDecimal("29.99")),
                new OrderItem("item-2", "Test Item 2", 1, new BigDecimal("39.99"))
            )
        );
        
        CompletableFuture<String> future = orderService.createOrderWithBusinessValidation(request);
        
        // Should complete exceptionally due to invalid customer
        Exception exception = assertThrows(Exception.class, () -> {
            future.get(10, TimeUnit.SECONDS);
        });
        
        assertTrue(exception.getMessage().contains("Invalid customer ID") ||
                  exception.getCause().getMessage().contains("Invalid customer ID"),
                  "Exception should mention invalid customer");
        
        logger.info("✅ Invalid customer rollback triggered correctly");
        logger.info("✅ Invalid customer rollback test passed");
    }
    
    /**
     * Test order creation with database constraints that trigger rollback.
     */
    @Test
    void testCreateOrderWithDatabaseConstraintsRollback() {
        logger.info("=== Testing Database Constraints Rollback ===");

        // Create request with customer ID that triggers database constraint violation
        CreateOrderRequest request = new CreateOrderRequest(
            "DUPLICATE_ORDER", // Triggers database constraint violation
            new BigDecimal("99.99"),
            Arrays.asList(
                new OrderItem("item-1", "Test Item 1", 2, new BigDecimal("29.99")),
                new OrderItem("item-2", "Test Item 2", 1, new BigDecimal("39.99"))
            )
        );
        
        CompletableFuture<String> future = orderService.createOrderWithDatabaseConstraints(request);
        
        // Should complete exceptionally due to database constraint violation
        Exception exception = assertThrows(Exception.class, () -> {
            future.get(10, TimeUnit.SECONDS);
        });
        
        assertTrue(exception.getMessage().contains("Database constraint violation") ||
                  exception.getCause().getMessage().contains("Database constraint violation"),
                  "Exception should mention database constraint violation");
        
        logger.info("✅ Database constraints rollback triggered correctly");
        logger.info("✅ Database constraints rollback test passed");
    }
    
    /**
     * Test successful order creation with multiple events.
     */
    @Test
    void testCreateOrderWithMultipleEventsSuccess() throws Exception {
        logger.info("=== Testing Order Creation with Multiple Events ===");
        
        CreateOrderRequest request = createValidOrderRequest();
        
        CompletableFuture<String> future = orderService.createOrderWithMultipleEvents(request);
        String orderId = future.get(10, TimeUnit.SECONDS);
        
        assertNotNull(orderId, "Order ID should not be null");
        assertFalse(orderId.isEmpty(), "Order ID should not be empty");
        
        logger.info("✅ Order with multiple events created successfully with ID: {}", orderId);
        logger.info("✅ Multiple events creation test passed");
    }
    
    /**
     * Test that the OutboxProducer bean is properly injected.
     */
    @Test
    void testOutboxProducerInjection() {
        logger.info("=== Testing OutboxProducer Bean Injection ===");
        
        assertNotNull(orderEventProducer, "OutboxProducer should be injected");
        
        logger.info("✅ OutboxProducer bean injected successfully");
        logger.info("✅ Bean injection test passed");
    }
    
    /**
     * Test that the OrderService bean is properly injected.
     */
    @Test
    void testOrderServiceInjection() {
        logger.info("=== Testing OrderService Bean Injection ===");
        
        assertNotNull(orderService, "OrderService should be injected");
        
        logger.info("✅ OrderService bean injected successfully");
        logger.info("✅ Service injection test passed");
    }
    
    /**
     * Creates a valid order request for testing.
     */
    private CreateOrderRequest createValidOrderRequest() {
        return new CreateOrderRequest(
            "test-customer-" + System.currentTimeMillis(),
            new BigDecimal("99.99"),
            Arrays.asList(
                new OrderItem("item-1", "Test Item 1", 2, new BigDecimal("29.99")),
                new OrderItem("item-2", "Test Item 2", 1, new BigDecimal("39.99"))
            )
        );
    }
}
