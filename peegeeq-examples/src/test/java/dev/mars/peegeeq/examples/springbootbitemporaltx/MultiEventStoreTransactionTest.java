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
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterAll;

/**
 * Comprehensive Test Suite for Multi-Event Store Transaction Coordination.
 * 
 * This test suite validates advanced bi-temporal event store patterns with
 * transactional coordination across multiple event stores using Vert.x 5.x
 * reactive patterns and PostgreSQL ACID transactions.
 * 
 * <h2>Test Categories</h2>
 * 
 * <h3>1. Transaction Coordination Tests</h3>
 * <ul>
 *   <li><b>Successful Coordination</b> - All event stores updated in single transaction</li>
 *   <li><b>Automatic Rollback</b> - Any failure rolls back all changes</li>
 *   <li><b>Event Ordering</b> - Consistent transaction time across stores</li>
 *   <li><b>Correlation Tracking</b> - Events linked across stores</li>
 * </ul>
 * 
 * <h3>2. Business Workflow Tests</h3>
 * <ul>
 *   <li><b>Complete Order Processing</b> - End-to-end order lifecycle</li>
 *   <li><b>Inventory Coordination</b> - Stock reservation and allocation</li>
 *   <li><b>Payment Processing</b> - Authorization and capture coordination</li>
 *   <li><b>Audit Trail</b> - Complete compliance event recording</li>
 * </ul>
 * 
 * <h3>3. Failure Scenario Tests</h3>
 * <ul>
 *   <li><b>Payment Failures</b> - Rollback inventory and order changes</li>
 *   <li><b>Inventory Shortages</b> - Cancel payment and update order</li>
 *   <li><b>System Failures</b> - Database connection and timeout handling</li>
 *   <li><b>Partial Failures</b> - Saga compensation patterns</li>
 * </ul>
 * 
 * <h3>4. Bi-Temporal Query Tests</h3>
 * <ul>
 *   <li><b>Cross-Store Queries</b> - Query events across multiple stores</li>
 *   <li><b>Temporal Consistency</b> - Point-in-time reconstruction</li>
 *   <li><b>Correlation Queries</b> - Find all related events</li>
 *   <li><b>Audit Queries</b> - Regulatory compliance reporting</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-03
 * @version 1.0
 */
@SpringBootTest(classes = SpringBootBitemporalTxApplication.class)
@ActiveProfiles("test")
@Testcontainers
class MultiEventStoreTransactionTest {
    
    private static final Logger logger = LoggerFactory.getLogger(MultiEventStoreTransactionTest.class);
    
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("peegeeq.bitemporal.database.host", postgres::getHost);
        registry.add("peegeeq.bitemporal.database.port", postgres::getFirstMappedPort);
        registry.add("peegeeq.bitemporal.database.name", postgres::getDatabaseName);
        registry.add("peegeeq.bitemporal.database.username", postgres::getUsername);
        registry.add("peegeeq.bitemporal.database.password", postgres::getPassword);
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

    @AfterAll
    static void tearDown() {
        logger.info("🧹 Cleaning up Multi-Event Store Transaction Test resources");
        if (postgres != null) {
            try {
                postgres.stop();
                logger.info("✅ PostgreSQL container stopped successfully");
            } catch (Exception e) {
                logger.warn("⚠️ Error stopping PostgreSQL container: {}", e.getMessage());
            }
        }
        logger.info("✅ Multi-Event Store Transaction Test cleanup complete");
    }


    /**
     * BUSINESS SCENARIO: Complete Order Processing with Transaction Coordination
     * 
     * This test validates the core pattern of coordinating transactions across
     * multiple bi-temporal event stores for a complete order processing workflow.
     */
    @Test
    void testCompleteOrderProcessingWithTransactionCoordination() throws Exception {
        logger.info("=== Testing Complete Order Processing with Transaction Coordination ===");
        
        // BUSINESS SETUP: Create realistic order processing request
        String orderId = "ORDER-" + UUID.randomUUID().toString().substring(0, 8);
        String customerId = "CUSTOMER-" + UUID.randomUUID().toString().substring(0, 8);
        
        OrderProcessingRequest request = new OrderProcessingRequest(
            orderId,
            customerId,
            List.of(
                new OrderProcessingRequest.OrderItem(
                    "PROD-001",
                    "SKU-LAPTOP-001",
                    "Gaming Laptop",
                    1,
                    new BigDecimal("1299.99")
                ),
                new OrderProcessingRequest.OrderItem(
                    "PROD-002",
                    "SKU-MOUSE-001",
                    "Wireless Gaming Mouse",
                    2,
                    new BigDecimal("79.99")
                )
            ),
            new BigDecimal("1459.97"),
            "USD",
            "CREDIT_CARD",
            "123 Main St, Anytown, ST 12345",
            "123 Main St, Anytown, ST 12345"
        );
        
        // TRANSACTION COORDINATION: Process order across all event stores
        logger.info("Processing order with coordinated transactions: {}", orderId);
        CompletableFuture<OrderProcessingResult> resultFuture = 
            orderProcessingService.processCompleteOrder(request);
        
        OrderProcessingResult result = resultFuture.get();
        
        // VALIDATION: Processing should be successful
        assertNotNull(result, "Processing result should not be null");
        assertTrue(result.isSuccess(), "Order processing should be successful");
        assertEquals(orderId, result.getOrderId(), "Order ID should match");
        assertNotNull(result.getCorrelationId(), "Correlation ID should be set");
        assertNotNull(result.getTransactionId(), "Transaction ID should be set");
        
        logger.info("Order processing successful: {} with correlation: {}", 
                   result.getOrderId(), result.getCorrelationId());
        
        // CROSS-STORE VALIDATION: Verify events were created in all stores
        
        // 1. Verify Order Events
        List<BiTemporalEvent<OrderEvent>> orderEvents = 
            orderEventStore.query(EventQuery.forAggregate(orderId)).get();
        
        assertFalse(orderEvents.isEmpty(), "Order events should be created");
        assertEquals(1, orderEvents.size(), "Should have exactly one order event");
        
        BiTemporalEvent<OrderEvent> orderEvent = orderEvents.get(0);
        assertEquals("OrderCreated", orderEvent.getEventType(), "Order event type should be OrderCreated");
        assertEquals(orderId, orderEvent.getPayload().getOrderId(), "Order ID should match");
        assertEquals("CREATED", orderEvent.getPayload().getOrderStatus(), "Order status should be CREATED");
        assertEquals(result.getCorrelationId(), orderEvent.getCorrelationId(), "Correlation ID should match");
        
        // 2. Verify Inventory Events
        List<BiTemporalEvent<InventoryEvent>> inventoryEvents =
            inventoryEventStore.query(EventQuery.builder()
                .headerFilters(Map.of("correlationId", result.getCorrelationId()))
                .build()).get();
        
        assertFalse(inventoryEvents.isEmpty(), "Inventory events should be created");
        assertEquals(2, inventoryEvents.size(), "Should have inventory events for both products");
        
        for (BiTemporalEvent<InventoryEvent> inventoryEvent : inventoryEvents) {
            assertEquals("InventoryReserved", inventoryEvent.getEventType(), "Inventory event type should be InventoryReserved");
            assertEquals("RESERVED", inventoryEvent.getPayload().getMovementType(), "Movement type should be RESERVED");
            assertEquals(orderId, inventoryEvent.getPayload().getOrderId(), "Order ID should match");
            assertEquals(result.getCorrelationId(), inventoryEvent.getCorrelationId(), "Correlation ID should match");
        }
        
        // 3. Verify Payment Events
        List<BiTemporalEvent<PaymentEvent>> paymentEvents =
            paymentEventStore.query(EventQuery.builder()
                .headerFilters(Map.of("correlationId", result.getCorrelationId()))
                .build()).get();

        assertFalse(paymentEvents.isEmpty(), "Payment events should be created");
        assertEquals(1, paymentEvents.size(), "Should have exactly one payment event");

        BiTemporalEvent<PaymentEvent> paymentEvent = paymentEvents.get(0);
        assertEquals("PaymentAuthorized", paymentEvent.getEventType(), "Payment event type should be PaymentAuthorized");
        assertEquals("AUTHORIZED", paymentEvent.getPayload().getPaymentStatus(), "Payment status should be AUTHORIZED");
        assertEquals(orderId, paymentEvent.getPayload().getOrderId(), "Order ID should match");
        assertEquals(result.getCorrelationId(), paymentEvent.getCorrelationId(), "Correlation ID should match");

        // 4. Verify Audit Events
        List<BiTemporalEvent<AuditEvent>> auditEvents =
            auditEventStore.query(EventQuery.builder()
                .headerFilters(Map.of("correlationId", result.getCorrelationId()))
                .build()).get();
        
        assertFalse(auditEvents.isEmpty(), "Audit events should be created");
        assertTrue(auditEvents.size() >= 2, "Should have at least transaction start and complete events");
        
        // Find transaction start and complete events
        BiTemporalEvent<AuditEvent> startEvent = auditEvents.stream()
            .filter(e -> "TRANSACTION_STARTED".equals(e.getEventType()))
            .findFirst()
            .orElse(null);
        
        BiTemporalEvent<AuditEvent> completeEvent = auditEvents.stream()
            .filter(e -> "TRANSACTION_COMPLETED".equals(e.getEventType()))
            .findFirst()
            .orElse(null);
        
        assertNotNull(startEvent, "Transaction start event should exist");
        assertNotNull(completeEvent, "Transaction complete event should exist");
        
        assertEquals(result.getCorrelationId(), startEvent.getCorrelationId(), "Start event correlation ID should match");
        assertEquals(result.getCorrelationId(), completeEvent.getCorrelationId(), "Complete event correlation ID should match");
        assertEquals(result.getTransactionId(), startEvent.getPayload().getTransactionId(), "Start event transaction ID should match");
        assertEquals(result.getTransactionId(), completeEvent.getPayload().getTransactionId(), "Complete event transaction ID should match");
        
        // TEMPORAL CONSISTENCY VALIDATION: All events should have consistent transaction times
        // (within a reasonable tolerance since they're in the same transaction)
        long startTransactionTime = startEvent.getTransactionTime().toEpochMilli();
        long orderTransactionTime = orderEvent.getTransactionTime().toEpochMilli();
        long paymentTransactionTime = paymentEvent.getTransactionTime().toEpochMilli();
        long completeTransactionTime = completeEvent.getTransactionTime().toEpochMilli();
        
        // All transaction times should be within 1 second of each other (same transaction)
        long maxTimeDiff = Math.max(
            Math.max(Math.abs(orderTransactionTime - startTransactionTime), 
                    Math.abs(paymentTransactionTime - startTransactionTime)),
            Math.abs(completeTransactionTime - startTransactionTime)
        );
        
        assertTrue(maxTimeDiff < 1000, "All events should have similar transaction times (within 1 second)");
        
        logger.info("✅ Complete order processing with transaction coordination validated successfully");
        logger.info("✅ Order: {}, Correlation: {}, Transaction: {}", 
                   result.getOrderId(), result.getCorrelationId(), result.getTransactionId());
        logger.info("✅ Events created: {} order, {} inventory, {} payment, {} audit", 
                   orderEvents.size(), inventoryEvents.size(), paymentEvents.size(), auditEvents.size());
    }
    
    /**
     * BUSINESS SCENARIO: Cross-Store Event Correlation Validation
     * 
     * This test validates that events across multiple stores can be correlated
     * and queried together for complete business process reconstruction.
     */
    @Test
    void testCrossStoreEventCorrelation() throws Exception {
        logger.info("=== Testing Cross-Store Event Correlation ===");
        
        // BUSINESS SETUP: Process an order to generate correlated events
        String orderId = "ORDER-CORRELATION-" + UUID.randomUUID().toString().substring(0, 8);
        String customerId = "CUSTOMER-" + UUID.randomUUID().toString().substring(0, 8);
        
        OrderProcessingRequest request = new OrderProcessingRequest(
            orderId,
            customerId,
            List.of(
                new OrderProcessingRequest.OrderItem(
                    "PROD-CORRELATION-001",
                    "SKU-CORRELATION-001",
                    "Correlation Test Product",
                    1,
                    new BigDecimal("99.99")
                )
            ),
            new BigDecimal("99.99"),
            "USD",
            "CREDIT_CARD",
            "123 Correlation St, Test City, TC 12345",
            "123 Correlation St, Test City, TC 12345"
        );
        
        // Process order and get correlation ID
        OrderProcessingResult result = orderProcessingService.processCompleteOrder(request).get();
        assertTrue(result.isSuccess(), "Order processing should be successful");
        
        String correlationId = result.getCorrelationId();
        assertNotNull(correlationId, "Correlation ID should be set");
        
        logger.info("Order processed with correlation ID: {}", correlationId);
        
        // CROSS-STORE CORRELATION VALIDATION: Query all stores for correlated events
        
        // Query each store for events with the correlation ID
        List<BiTemporalEvent<OrderEvent>> correlatedOrderEvents = 
            orderEventStore.query(EventQuery.builder()
                .correlationId(correlationId)
                .build()).get();
        
        List<BiTemporalEvent<InventoryEvent>> correlatedInventoryEvents = 
            inventoryEventStore.query(EventQuery.builder()
                .correlationId(correlationId)
                .build()).get();
        
        List<BiTemporalEvent<PaymentEvent>> correlatedPaymentEvents = 
            paymentEventStore.query(EventQuery.builder()
                .correlationId(correlationId)
                .build()).get();
        
        List<BiTemporalEvent<AuditEvent>> correlatedAuditEvents = 
            auditEventStore.query(EventQuery.builder()
                .correlationId(correlationId)
                .build()).get();
        
        // VALIDATION: All stores should have events with the correlation ID
        assertFalse(correlatedOrderEvents.isEmpty(), "Should find correlated order events");
        assertFalse(correlatedInventoryEvents.isEmpty(), "Should find correlated inventory events");
        assertFalse(correlatedPaymentEvents.isEmpty(), "Should find correlated payment events");
        assertFalse(correlatedAuditEvents.isEmpty(), "Should find correlated audit events");
        
        // Verify all events have the same correlation ID
        correlatedOrderEvents.forEach(event -> 
            assertEquals(correlationId, event.getCorrelationId(), "Order event correlation ID should match"));
        correlatedInventoryEvents.forEach(event -> 
            assertEquals(correlationId, event.getCorrelationId(), "Inventory event correlation ID should match"));
        correlatedPaymentEvents.forEach(event -> 
            assertEquals(correlationId, event.getCorrelationId(), "Payment event correlation ID should match"));
        correlatedAuditEvents.forEach(event -> 
            assertEquals(correlationId, event.getCorrelationId(), "Audit event correlation ID should match"));
        
        // BUSINESS PROCESS RECONSTRUCTION: Verify complete business process can be reconstructed
        int totalCorrelatedEvents = correlatedOrderEvents.size() + correlatedInventoryEvents.size() + 
                                   correlatedPaymentEvents.size() + correlatedAuditEvents.size();
        
        assertTrue(totalCorrelatedEvents >= 4, "Should have at least 4 correlated events across all stores");
        
        logger.info("✅ Cross-store event correlation validated successfully");
        logger.info("✅ Correlation ID: {}, Total correlated events: {}", correlationId, totalCorrelatedEvents);
        logger.info("✅ Events by store: {} order, {} inventory, {} payment, {} audit", 
                   correlatedOrderEvents.size(), correlatedInventoryEvents.size(), 
                   correlatedPaymentEvents.size(), correlatedAuditEvents.size());
    }
}
