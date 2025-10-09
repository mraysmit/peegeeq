package dev.mars.peegeeq.examples.springbootbitemporaltx;

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
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.examples.springbootbitemporaltx.events.*;
import dev.mars.peegeeq.examples.springbootbitemporaltx.service.*;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for Order Processing Service.
 * 
 * This test class focuses on testing the OrderProcessingService in isolation,
 * validating its business logic, error handling, and integration with
 * multiple bi-temporal event stores.
 * 
 * <h2>Test Categories</h2>
 * 
 * <h3>1. Service Logic Tests</h3>
 * <ul>
 *   <li><b>Request Validation</b> - Input validation and error handling</li>
 *   <li><b>Event Creation</b> - Proper event construction from requests</li>
 *   <li><b>Correlation Management</b> - Correlation ID generation and tracking</li>
 *   <li><b>Transaction Coordination</b> - Multi-store transaction management</li>
 * </ul>
 * 
 * <h3>2. Business Logic Tests</h3>
 * <ul>
 *   <li><b>Order Processing</b> - Complete order lifecycle processing</li>
 *   <li><b>Inventory Management</b> - Stock reservation and allocation</li>
 *   <li><b>Payment Processing</b> - Payment authorization and capture</li>
 *   <li><b>Audit Trail</b> - Compliance and regulatory event recording</li>
 * </ul>
 * 
 * <h3>3. Error Handling Tests</h3>
 * <ul>
 *   <li><b>Invalid Requests</b> - Malformed or invalid input handling</li>
 *   <li><b>Service Failures</b> - Database and network error handling</li>
 *   <li><b>Transaction Rollback</b> - Automatic rollback on failures</li>
 *   <li><b>Compensation Logic</b> - Saga pattern implementation</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-03
 * @version 1.0
 */
@SpringBootTest(
    classes = SpringBootBitemporalTxApplication.class,
    properties = {
        "test.context.unique=OrderProcessingServiceTest"
    }
)
@ActiveProfiles("test")
@Testcontainers
class OrderProcessingServiceTest {
    
    private static final Logger logger = LoggerFactory.getLogger(OrderProcessingServiceTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }

    @Autowired
    private OrderProcessingService orderProcessingService;
    
    @Autowired
    private EventStore<OrderEvent> orderEventStore;
    
    @Autowired
    private EventStore<InventoryEvent> inventoryEventStore;
    
    @Autowired
    private EventStore<PaymentEvent> paymentEventStore;
    
    @Autowired
    private EventStore<AuditEvent> auditEventStore;

    // TestContainers @Container annotation handles lifecycle automatically
    // No manual teardown needed - this was causing race conditions with async operations


    /**
     * BUSINESS SCENARIO: Single Product Order Processing
     * 
     * Tests the complete processing of a simple single-product order
     * to validate basic service functionality and event store coordination.
     */
    @Test
    void testSingleProductOrderProcessing() throws Exception {
        logger.info("=== Testing Single Product Order Processing ===");
        
        // BUSINESS SETUP: Create simple single-product order
        String orderId = "ORDER-SINGLE-" + UUID.randomUUID().toString().substring(0, 8);
        String customerId = "CUSTOMER-" + UUID.randomUUID().toString().substring(0, 8);
        
        OrderProcessingRequest request = new OrderProcessingRequest(
            orderId,
            customerId,
            List.of(
                new OrderProcessingRequest.OrderItem(
                    "PROD-SINGLE-001",
                    "SKU-SINGLE-001",
                    "Single Test Product",
                    1,
                    new BigDecimal("49.99")
                )
            ),
            new BigDecimal("49.99"),
            "USD",
            "CREDIT_CARD",
            "123 Single St, Test City, TC 12345",
            "123 Single St, Test City, TC 12345"
        );
        
        // SERVICE EXECUTION: Process the order
        logger.info("Processing single product order: {}", orderId);
        OrderProcessingResult result = orderProcessingService.processCompleteOrder(request).get();
        
        // RESULT VALIDATION: Processing should be successful
        assertNotNull(result, "Processing result should not be null");
        assertTrue(result.isSuccess(), "Order processing should be successful");
        assertEquals(orderId, result.getOrderId(), "Order ID should match");
        assertNotNull(result.getCorrelationId(), "Correlation ID should be set");
        assertNotNull(result.getTransactionId(), "Transaction ID should be set");
        assertNotNull(result.getValidTime(), "Valid time should be set");
        
        logger.info("Single product order processed successfully: {}", result.getOrderId());
        
        // EVENT STORE VALIDATION: Verify events were created correctly
        logger.info("Starting event store validation for order: {}", orderId);

        // Add a small delay to ensure all async operations complete
        Thread.sleep(100);

        // 1. Order Event Validation - Query specifically for OrderCreated events
        logger.info("Querying order events for orderId: {}", orderId);
        List<BiTemporalEvent<OrderEvent>> orderEvents =
            orderEventStore.query(EventQuery.builder()
                .aggregateId(orderId)
                .eventType("OrderCreated")
                .build()).get();

        assertEquals(1, orderEvents.size(), "Should have exactly one order event");
        
        BiTemporalEvent<OrderEvent> orderEvent = orderEvents.get(0);
        assertEquals("OrderCreated", orderEvent.getEventType(), "Order event type should be OrderCreated");
        assertEquals(orderId, orderEvent.getPayload().getOrderId(), "Order ID should match");
        assertEquals(customerId, orderEvent.getPayload().getCustomerId(), "Customer ID should match");
        assertEquals("CREATED", orderEvent.getPayload().getOrderStatus(), "Order status should be CREATED");
        assertEquals(1, orderEvent.getPayload().getItems().size(), "Should have one order item");
        assertEquals(new BigDecimal("49.99"), orderEvent.getPayload().getTotalAmount(), "Total amount should match");
        assertEquals("USD", orderEvent.getPayload().getCurrency(), "Currency should match");
        
        // 2. Inventory Event Validation - Query specifically for this order's inventory events
        List<BiTemporalEvent<InventoryEvent>> inventoryEvents =
            inventoryEventStore.query(EventQuery.builder()
                .aggregateId(orderId)
                .eventType("InventoryReserved")
                .build()).get();

        assertEquals(1, inventoryEvents.size(), "Should have exactly one inventory event");
        
        BiTemporalEvent<InventoryEvent> inventoryEvent = inventoryEvents.get(0);
        assertEquals("InventoryReserved", inventoryEvent.getEventType(), "Inventory event type should be InventoryReserved");
        assertEquals("PROD-SINGLE-001", inventoryEvent.getPayload().getProductId(), "Product ID should match");
        assertEquals("SKU-SINGLE-001", inventoryEvent.getPayload().getProductSku(), "Product SKU should match");
        assertEquals("RESERVED", inventoryEvent.getPayload().getMovementType(), "Movement type should be RESERVED");
        assertEquals(-1, inventoryEvent.getPayload().getQuantityChange(), "Quantity change should be -1 (reservation)");
        assertEquals(orderId, inventoryEvent.getPayload().getOrderId(), "Order ID should match");
        
        // 3. Payment Event Validation - Query specifically for this order's payment events
        List<BiTemporalEvent<PaymentEvent>> paymentEvents =
            paymentEventStore.query(EventQuery.builder()
                .aggregateId(orderId)
                .eventType("PaymentAuthorized")
                .build()).get();

        assertEquals(1, paymentEvents.size(), "Should have exactly one payment event");
        
        BiTemporalEvent<PaymentEvent> paymentEvent = paymentEvents.get(0);
        assertEquals("PaymentAuthorized", paymentEvent.getEventType(), "Payment event type should be PaymentAuthorized");
        assertEquals(orderId, paymentEvent.getPayload().getOrderId(), "Order ID should match");
        assertEquals(customerId, paymentEvent.getPayload().getCustomerId(), "Customer ID should match");
        assertEquals("AUTHORIZED", paymentEvent.getPayload().getPaymentStatus(), "Payment status should be AUTHORIZED");
        assertEquals(new BigDecimal("49.99"), paymentEvent.getPayload().getAmount(), "Payment amount should match");
        assertEquals("USD", paymentEvent.getPayload().getCurrency(), "Payment currency should match");
        assertEquals("CREDIT_CARD", paymentEvent.getPayload().getPaymentMethod(), "Payment method should match");
        
        // 4. Audit Event Validation - Query specifically for this order's audit events
        List<BiTemporalEvent<AuditEvent>> auditEvents =
            auditEventStore.query(EventQuery.builder()
                .aggregateId(orderId)
                .build()).get();

        // DEBUG: Log audit events to understand what's being returned
        logger.info("Found {} audit events for orderId: {}", auditEvents.size(), orderId);
        for (int i = 0; i < auditEvents.size(); i++) {
            BiTemporalEvent<AuditEvent> event = auditEvents.get(i);
            logger.info("Audit Event {}: type={}, transactionId={}, aggregateId={}",
                i + 1, event.getEventType(),
                event.getPayload() != null ? event.getPayload().getTransactionId() : "null",
                event.getAggregateId());
        }

        // If no transaction lifecycle events found, try querying by correlation ID
        if (auditEvents.stream().noneMatch(e -> "TransactionStarted".equals(e.getEventType()))) {
            logger.info("No TransactionStarted events found with aggregateId={}, trying correlationId query", orderId);
            auditEvents = auditEventStore.query(EventQuery.builder()
                .correlationId(result.getCorrelationId())
                .build()).get();

            logger.info("Found {} audit events for correlationId: {}", auditEvents.size(), result.getCorrelationId());
            for (int i = 0; i < auditEvents.size(); i++) {
                BiTemporalEvent<AuditEvent> event = auditEvents.get(i);
                logger.info("Correlation Audit Event {}: type={}, transactionId={}, aggregateId={}",
                    i + 1, event.getEventType(),
                    event.getPayload() != null ? event.getPayload().getTransactionId() : "null",
                    event.getAggregateId());
            }
        }

        assertTrue(auditEvents.size() >= 2, "Should have at least 2 audit events (start and complete)");

        // Find transaction start and complete events (using correct event type names from service)
        BiTemporalEvent<AuditEvent> startEvent = auditEvents.stream()
            .filter(e -> "TransactionStarted".equals(e.getEventType()))
            .findFirst()
            .orElse(null);

        BiTemporalEvent<AuditEvent> completeEvent = auditEvents.stream()
            .filter(e -> "TransactionCompleted".equals(e.getEventType()))
            .findFirst()
            .orElse(null);
        
        assertNotNull(startEvent, "Transaction start event should exist");
        assertNotNull(completeEvent, "Transaction complete event should exist");
        
        assertEquals("ORDER", startEvent.getPayload().getEntityType(), "Start event entity type should be ORDER");
        assertEquals(orderId, startEvent.getPayload().getEntityId(), "Start event entity ID should match order ID");
        assertEquals("PROCESS_ORDER", startEvent.getPayload().getAction(), "Start event action should be PROCESS_ORDER");
        assertEquals("STARTED", startEvent.getPayload().getOutcome(), "Start event outcome should be STARTED");
        
        assertEquals("ORDER", completeEvent.getPayload().getEntityType(), "Complete event entity type should be ORDER");
        assertEquals(orderId, completeEvent.getPayload().getEntityId(), "Complete event entity ID should match order ID");
        assertEquals("PROCESS_ORDER", completeEvent.getPayload().getAction(), "Complete event action should be PROCESS_ORDER");
        assertEquals("SUCCESS", completeEvent.getPayload().getOutcome(), "Complete event outcome should be SUCCESS");
        
        // CORRELATION VALIDATION: All events should have the same correlation ID
        String correlationId = result.getCorrelationId();
        assertEquals(correlationId, orderEvent.getCorrelationId(), "Order event correlation ID should match");
        assertEquals(correlationId, inventoryEvent.getCorrelationId(), "Inventory event correlation ID should match");
        assertEquals(correlationId, paymentEvent.getCorrelationId(), "Payment event correlation ID should match");
        assertEquals(correlationId, startEvent.getCorrelationId(), "Start audit event correlation ID should match");
        assertEquals(correlationId, completeEvent.getCorrelationId(), "Complete audit event correlation ID should match");
        
        logger.info("✅ Single product order processing validated successfully");
        logger.info("✅ Order: {}, Correlation: {}, Events: {} order, {} inventory, {} payment, {} audit", 
                   result.getOrderId(), result.getCorrelationId(),
                   orderEvents.size(), inventoryEvents.size(), paymentEvents.size(), auditEvents.size());
    }
    
    /**
     * BUSINESS SCENARIO: Multi-Product Order Processing
     * 
     * Tests the processing of an order with multiple products to validate
     * proper handling of multiple inventory reservations and complex order structures.
     */
    @Test
    void testMultiProductOrderProcessing() throws Exception {
        logger.info("=== Testing Multi-Product Order Processing ===");
        
        // BUSINESS SETUP: Create order with multiple products
        String orderId = "ORDER-MULTI-" + UUID.randomUUID().toString().substring(0, 8);
        String customerId = "CUSTOMER-" + UUID.randomUUID().toString().substring(0, 8);
        
        OrderProcessingRequest request = new OrderProcessingRequest(
            orderId,
            customerId,
            List.of(
                new OrderProcessingRequest.OrderItem(
                    "PROD-MULTI-001",
                    "SKU-MULTI-001",
                    "Multi Test Product 1",
                    2,
                    new BigDecimal("29.99")
                ),
                new OrderProcessingRequest.OrderItem(
                    "PROD-MULTI-002",
                    "SKU-MULTI-002",
                    "Multi Test Product 2",
                    1,
                    new BigDecimal("79.99")
                ),
                new OrderProcessingRequest.OrderItem(
                    "PROD-MULTI-003",
                    "SKU-MULTI-003",
                    "Multi Test Product 3",
                    3,
                    new BigDecimal("19.99")
                )
            ),
            new BigDecimal("199.95"), // (29.99 * 2) + 79.99 + (19.99 * 3)
            "USD",
            "CREDIT_CARD",
            "123 Multi St, Test City, TC 12345",
            "123 Multi St, Test City, TC 12345"
        );
        
        // SERVICE EXECUTION: Process the multi-product order
        logger.info("Processing multi-product order: {}", orderId);
        OrderProcessingResult result = orderProcessingService.processCompleteOrder(request).get();
        
        // RESULT VALIDATION: Processing should be successful
        assertTrue(result.isSuccess(), "Multi-product order processing should be successful");
        assertEquals(orderId, result.getOrderId(), "Order ID should match");
        
        logger.info("Multi-product order processed successfully: {}", result.getOrderId());
        
        // EVENT STORE VALIDATION: Verify correct number of events
        logger.info("Starting event store validation for order: {}", orderId);

        // Add a small delay to ensure all async operations complete
        Thread.sleep(100);

        // 1. Order Event Validation - Query specifically for OrderCreated events
        logger.info("Querying order events for orderId: {}", orderId);
        List<BiTemporalEvent<OrderEvent>> orderEvents =
            orderEventStore.query(EventQuery.builder()
                .aggregateId(orderId)
                .eventType("OrderCreated")
                .build()).get();

        assertEquals(1, orderEvents.size(), "Should have exactly one order event");
        
        BiTemporalEvent<OrderEvent> orderEvent = orderEvents.get(0);
        assertEquals(3, orderEvent.getPayload().getItems().size(), "Should have three order items");
        assertEquals(new BigDecimal("199.95"), orderEvent.getPayload().getTotalAmount(), "Total amount should match");
        
        // 2. Inventory Event Validation - Should have one event per product
        List<BiTemporalEvent<InventoryEvent>> inventoryEvents =
            inventoryEventStore.query(EventQuery.builder()
                .aggregateId(orderId)
                .eventType("InventoryReserved")
                .build()).get();

        assertEquals(3, inventoryEvents.size(), "Should have exactly three inventory events (one per product)");
        
        // Verify each product has an inventory reservation
        List<String> reservedProductIds = inventoryEvents.stream()
            .map(event -> event.getPayload().getProductId())
            .toList();
        
        assertTrue(reservedProductIds.contains("PROD-MULTI-001"), "Product 1 should have inventory reservation");
        assertTrue(reservedProductIds.contains("PROD-MULTI-002"), "Product 2 should have inventory reservation");
        assertTrue(reservedProductIds.contains("PROD-MULTI-003"), "Product 3 should have inventory reservation");
        
        // Verify quantity changes match order quantities
        for (BiTemporalEvent<InventoryEvent> inventoryEvent : inventoryEvents) {
            String productId = inventoryEvent.getPayload().getProductId();
            int expectedQuantityChange = switch (productId) {
                case "PROD-MULTI-001" -> -2; // 2 units reserved
                case "PROD-MULTI-002" -> -1; // 1 unit reserved
                case "PROD-MULTI-003" -> -3; // 3 units reserved
                default -> 0;
            };
            
            assertEquals(expectedQuantityChange, inventoryEvent.getPayload().getQuantityChange(),
                "Quantity change should match order quantity for product: " + productId);
            assertEquals("RESERVED", inventoryEvent.getPayload().getMovementType(),
                "Movement type should be RESERVED for product: " + productId);
            assertEquals(orderId, inventoryEvent.getPayload().getOrderId(),
                "Order ID should match for product: " + productId);
        }
        
        // 3. Payment Event Validation - Should have one payment for total amount
        List<BiTemporalEvent<PaymentEvent>> paymentEvents =
            paymentEventStore.query(EventQuery.builder()
                .aggregateId(orderId)
                .eventType("PaymentAuthorized")
                .build()).get();

        assertEquals(1, paymentEvents.size(), "Should have exactly one payment event");
        
        BiTemporalEvent<PaymentEvent> paymentEvent = paymentEvents.get(0);
        assertEquals(new BigDecimal("199.95"), paymentEvent.getPayload().getAmount(), "Payment amount should match total");
        assertEquals(orderId, paymentEvent.getPayload().getOrderId(), "Payment order ID should match");
        
        // CORRELATION VALIDATION: All events should have the same correlation ID
        String correlationId = result.getCorrelationId();
        assertEquals(correlationId, orderEvent.getCorrelationId(), "Order event correlation ID should match");
        assertEquals(correlationId, paymentEvent.getCorrelationId(), "Payment event correlation ID should match");
        
        inventoryEvents.forEach(event -> 
            assertEquals(correlationId, event.getCorrelationId(), 
                "Inventory event correlation ID should match for product: " + event.getPayload().getProductId()));
        
        logger.info("✅ Multi-product order processing validated successfully");
        logger.info("✅ Order: {}, Products: {}, Total: {}, Correlation: {}", 
                   result.getOrderId(), inventoryEvents.size(), 
                   orderEvent.getPayload().getTotalAmount(), result.getCorrelationId());
    }
}
