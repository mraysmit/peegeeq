# Transactional Multi-Store Bi-Temporal Guide

**Example**: `springboot-bitemporal-tx`  
**Use Case**: Multi-asset trade lifecycle management with coordinated transactions  
**Focus**: Multiple event stores, transaction coordination, saga pattern

---

## What This Guide Covers

This guide demonstrates advanced bi-temporal patterns using multiple event stores coordinated in a single transaction. It shows how to manage complex business workflows that span multiple domains (orders, inventory, payments, audit) with complete ACID guarantees.

**Key Patterns**:
- Multiple event stores in single transaction
- Transaction coordination using `withTransaction()`
- Saga pattern for complex workflows
- Cross-store temporal queries
- Correlation IDs for event linking
- Complete audit trail across all stores

**What This Guide Does NOT Cover**:
- Basic bi-temporal concepts (see BITEMPORAL_BASICS_GUIDE.md)
- Reactive integration (see REACTIVE_BITEMPORAL_GUIDE.md)
- Outbox pattern (see INTEGRATED_PATTERN_GUIDE.md)
- Consumer groups (see CONSUMER_GROUP_GUIDE.md)

---

## Core Concepts

### Multiple Event Stores

**Why Multiple Stores?**

**Problem**: Different business domains need separate event stores.

**Example - Order Processing**:
- **Order Events** - Order lifecycle (created, confirmed, cancelled)
- **Inventory Events** - Stock movements (reserved, allocated, released)
- **Payment Events** - Payment processing (authorized, captured, refunded)
- **Audit Events** - Regulatory compliance (transaction start, complete, failed)

**Benefits**:
- Domain separation (clear boundaries)
- Independent querying (each domain has own history)
- Scalability (different retention policies per domain)
- Compliance (separate audit trail)

### Transaction Coordination

**Challenge**: How to ensure consistency across multiple event stores?

**Solution**: Single database transaction using `withTransaction()`.

**Pattern**:
```java
connectionProvider.withTransaction("client-id", connection -> {
    // Append to Order Event Store (connection)
    // Append to Inventory Event Store (connection)
    // Append to Payment Event Store (connection)
    // Append to Audit Event Store (connection)
    // All use SAME connection = SAME transaction
});
```

**Guarantees**:
- All events committed together OR all rolled back
- Consistent transaction time across all stores
- No partial updates
- Complete ACID compliance

### Correlation IDs

**Purpose**: Link related events across different event stores.

**Pattern**:
```java
String correlationId = UUID.randomUUID().toString();

// Order event
orderEventStore.appendInTransaction("OrderCreated", orderEvent, validTime, 
    Map.of("correlationId", correlationId), correlationId, orderId, connection);

// Inventory event
inventoryEventStore.appendInTransaction("InventoryReserved", inventoryEvent, validTime,
    Map.of("correlationId", correlationId), correlationId, orderId, connection);

// Payment event
paymentEventStore.appendInTransaction("PaymentAuthorized", paymentEvent, validTime,
    Map.of("correlationId", correlationId), correlationId, orderId, connection);
```

**Benefits**:
- Query all events for a business transaction
- Trace workflow across stores
- Debug complex scenarios
- Audit trail reconstruction

---

## Implementation

### Configuration

**Multiple Event Store Beans**:
```java
@Configuration
public class BiTemporalTxConfig {
    
    @Bean
    public EventStore<OrderEvent> orderEventStore(DatabaseService databaseService) {
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory();
        return factory.createEventStore("order_events", OrderEvent.class, databaseService);
    }
    
    @Bean
    public EventStore<InventoryEvent> inventoryEventStore(DatabaseService databaseService) {
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory();
        return factory.createEventStore("inventory_events", InventoryEvent.class, databaseService);
    }
    
    @Bean
    public EventStore<PaymentEvent> paymentEventStore(DatabaseService databaseService) {
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory();
        return factory.createEventStore("payment_events", PaymentEvent.class, databaseService);
    }
    
    @Bean
    public EventStore<AuditEvent> auditEventStore(DatabaseService databaseService) {
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory();
        return factory.createEventStore("audit_events", AuditEvent.class, databaseService);
    }
}
```

### Service Implementation

**Complete Order Processing**:
```java
@Service
public class OrderProcessingService {
    
    private final EventStore<OrderEvent> orderEventStore;
    private final EventStore<InventoryEvent> inventoryEventStore;
    private final EventStore<PaymentEvent> paymentEventStore;
    private final EventStore<AuditEvent> auditEventStore;
    private final DatabaseService databaseService;
    
    public CompletableFuture<OrderProcessingResult> processCompleteOrder(
            OrderProcessingRequest request) {
        
        String correlationId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        Instant validTime = Instant.now();
        
        // Get connection provider
        var connectionProvider = databaseService.getConnectionProvider();
        
        // Execute all operations in SINGLE transaction
        return connectionProvider.withTransaction("peegeeq-main", connection -> {
            
            // Step 1: Record audit event (transaction start)
            AuditEvent auditStart = new AuditEvent(
                transactionId,
                "TransactionStarted",
                "Order processing started",
                correlationId
            );
            
            CompletableFuture<BiTemporalEvent<AuditEvent>> auditFuture =
                ((PgBiTemporalEventStore<AuditEvent>) auditEventStore)
                    .appendInTransaction("TransactionStarted", auditStart, validTime,
                        Map.of("correlationId", correlationId),
                        correlationId, transactionId, connection);
            
            // Step 2: Create order event
            return auditFuture.thenCompose(auditResult -> {
                OrderEvent orderEvent = new OrderEvent(
                    request.getOrderId(),
                    request.getCustomerId(),
                    request.getTotalAmount(),
                    OrderStatus.CREATED
                );
                
                return ((PgBiTemporalEventStore<OrderEvent>) orderEventStore)
                    .appendInTransaction("OrderCreated", orderEvent, validTime,
                        Map.of("correlationId", correlationId),
                        correlationId, request.getOrderId(), connection);
            })
            
            // Step 3: Reserve inventory
            .thenCompose(orderResult -> {
                List<CompletableFuture<BiTemporalEvent<InventoryEvent>>> inventoryFutures =
                    request.getItems().stream()
                        .map(item -> {
                            InventoryEvent inventoryEvent = new InventoryEvent(
                                item.getProductId(),
                                item.getQuantity(),
                                InventoryAction.RESERVED,
                                request.getOrderId()
                            );
                            
                            return ((PgBiTemporalEventStore<InventoryEvent>) inventoryEventStore)
                                .appendInTransaction("InventoryReserved", inventoryEvent, validTime,
                                    Map.of("correlationId", correlationId),
                                    correlationId, request.getOrderId(), connection);
                        })
                        .toList();
                
                return CompletableFuture.allOf(inventoryFutures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> inventoryFutures.stream()
                        .map(CompletableFuture::join)
                        .toList());
            })
            
            // Step 4: Authorize payment
            .thenCompose(inventoryResults -> {
                PaymentEvent paymentEvent = new PaymentEvent(
                    request.getOrderId(),
                    request.getTotalAmount(),
                    PaymentAction.AUTHORIZED,
                    request.getPaymentMethod()
                );
                
                return ((PgBiTemporalEventStore<PaymentEvent>) paymentEventStore)
                    .appendInTransaction("PaymentAuthorized", paymentEvent, validTime,
                        Map.of("correlationId", correlationId),
                        correlationId, request.getOrderId(), connection);
            })
            
            // Step 5: Record audit event (transaction complete)
            .thenCompose(paymentResult -> {
                AuditEvent auditComplete = new AuditEvent(
                    transactionId,
                    "TransactionCompleted",
                    "Order processing completed successfully",
                    correlationId
                );
                
                return ((PgBiTemporalEventStore<AuditEvent>) auditEventStore)
                    .appendInTransaction("TransactionCompleted", auditComplete, validTime,
                        Map.of("correlationId", correlationId),
                        correlationId, transactionId, connection);
            })
            
            // Return result
            .thenApply(finalAuditResult -> {
                return new OrderProcessingResult(
                    request.getOrderId(),
                    "SUCCESS",
                    "Order processed successfully",
                    correlationId,
                    transactionId,
                    validTime
                );
            });
            
        }).toCompletionStage().toCompletableFuture();
    }
}
```

**Key Points**:
1. **Single Transaction** - All event stores use same connection
2. **Composition** - Chain operations with `.thenCompose()`
3. **Correlation ID** - Links events across stores
4. **Transaction ID** - Identifies this business transaction
5. **Valid Time** - Same for all events in workflow
6. **ACID Guarantees** - All succeed or all fail

---

## Saga Pattern

### Order Cancellation Saga

**Scenario**: Cancel order and compensate all related events.

**Implementation**:
```java
public CompletableFuture<OrderProcessingResult> cancelOrder(String orderId) {
    String correlationId = UUID.randomUUID().toString();
    Instant validTime = Instant.now();
    
    return connectionProvider.withTransaction("peegeeq-main", connection -> {
        
        // Step 1: Cancel order
        return findOrder(orderId)
            .thenCompose(order -> {
                OrderEvent cancelEvent = new OrderEvent(
                    orderId,
                    order.getCustomerId(),
                    order.getTotalAmount(),
                    OrderStatus.CANCELLED
                );
                
                return ((PgBiTemporalEventStore<OrderEvent>) orderEventStore)
                    .appendInTransaction("OrderCancelled", cancelEvent, validTime,
                        Map.of("correlationId", correlationId),
                        correlationId, orderId, connection);
            })
            
            // Step 2: Release inventory
            .thenCompose(orderResult -> {
                return findInventoryReservations(orderId)
                    .thenCompose(reservations -> {
                        List<CompletableFuture<BiTemporalEvent<InventoryEvent>>> releaseFutures =
                            reservations.stream()
                                .map(reservation -> {
                                    InventoryEvent releaseEvent = new InventoryEvent(
                                        reservation.getProductId(),
                                        reservation.getQuantity(),
                                        InventoryAction.RELEASED,
                                        orderId
                                    );
                                    
                                    return ((PgBiTemporalEventStore<InventoryEvent>) inventoryEventStore)
                                        .appendInTransaction("InventoryReleased", releaseEvent, validTime,
                                            Map.of("correlationId", correlationId),
                                            correlationId, orderId, connection);
                                })
                                .toList();
                        
                        return CompletableFuture.allOf(releaseFutures.toArray(new CompletableFuture[0]));
                    });
            })
            
            // Step 3: Refund payment
            .thenCompose(inventoryResult -> {
                return findPaymentAuthorization(orderId)
                    .thenCompose(payment -> {
                        PaymentEvent refundEvent = new PaymentEvent(
                            orderId,
                            payment.getAmount(),
                            PaymentAction.REFUNDED,
                            payment.getPaymentMethod()
                        );
                        
                        return ((PgBiTemporalEventStore<PaymentEvent>) paymentEventStore)
                            .appendInTransaction("PaymentRefunded", refundEvent, validTime,
                                Map.of("correlationId", correlationId),
                                correlationId, orderId, connection);
                    });
            })
            
            // Step 4: Audit trail
            .thenApply(paymentResult -> {
                return new OrderProcessingResult(
                    orderId,
                    "CANCELLED",
                    "Order cancelled successfully",
                    correlationId,
                    UUID.randomUUID().toString(),
                    validTime
                );
            });
            
    }).toCompletionStage().toCompletableFuture();
}
```

**Saga Steps**:
1. Cancel order → `OrderCancelled` event
2. Release inventory → `InventoryReleased` events
3. Refund payment → `PaymentRefunded` event
4. All in single transaction (compensating actions)

---

## Cross-Store Queries

### Query by Correlation ID

**Pattern**: Find all events for a business transaction.

```java
public CompletableFuture<TransactionHistory> getTransactionHistory(String correlationId) {
    // Query all event stores in parallel
    CompletableFuture<List<BiTemporalEvent<OrderEvent>>> orderFuture =
        orderEventStore.query(EventQuery.all())
            .thenApply(events -> events.stream()
                .filter(e -> correlationId.equals(e.getMetadata().get("correlationId")))
                .collect(Collectors.toList())
            );
    
    CompletableFuture<List<BiTemporalEvent<InventoryEvent>>> inventoryFuture =
        inventoryEventStore.query(EventQuery.all())
            .thenApply(events -> events.stream()
                .filter(e -> correlationId.equals(e.getMetadata().get("correlationId")))
                .collect(Collectors.toList())
            );
    
    CompletableFuture<List<BiTemporalEvent<PaymentEvent>>> paymentFuture =
        paymentEventStore.query(EventQuery.all())
            .thenApply(events -> events.stream()
                .filter(e -> correlationId.equals(e.getMetadata().get("correlationId")))
                .collect(Collectors.toList())
            );
    
    CompletableFuture<List<BiTemporalEvent<AuditEvent>>> auditFuture =
        auditEventStore.query(EventQuery.all())
            .thenApply(events -> events.stream()
                .filter(e -> correlationId.equals(e.getMetadata().get("correlationId")))
                .collect(Collectors.toList())
            );
    
    // Combine results
    return CompletableFuture.allOf(orderFuture, inventoryFuture, paymentFuture, auditFuture)
        .thenApply(v -> new TransactionHistory(
            correlationId,
            orderFuture.join(),
            inventoryFuture.join(),
            paymentFuture.join(),
            auditFuture.join()
        ));
}
```

### Query by Order ID

**Pattern**: Find all events related to an order.

```java
public CompletableFuture<OrderHistory> getOrderHistory(String orderId) {
    CompletableFuture<List<BiTemporalEvent<OrderEvent>>> orderFuture =
        orderEventStore.query(EventQuery.all())
            .thenApply(events -> events.stream()
                .filter(e -> orderId.equals(e.getPayload().getOrderId()))
                .collect(Collectors.toList())
            );
    
    CompletableFuture<List<BiTemporalEvent<InventoryEvent>>> inventoryFuture =
        inventoryEventStore.query(EventQuery.all())
            .thenApply(events -> events.stream()
                .filter(e -> orderId.equals(e.getPayload().getOrderId()))
                .collect(Collectors.toList())
            );
    
    CompletableFuture<List<BiTemporalEvent<PaymentEvent>>> paymentFuture =
        paymentEventStore.query(EventQuery.all())
            .thenApply(events -> events.stream()
                .filter(e -> orderId.equals(e.getPayload().getOrderId()))
                .collect(Collectors.toList())
            );
    
    return CompletableFuture.allOf(orderFuture, inventoryFuture, paymentFuture)
        .thenApply(v -> new OrderHistory(
            orderId,
            orderFuture.join(),
            inventoryFuture.join(),
            paymentFuture.join()
        ));
}
```

---

## Trade Lifecycle Use Case

### Scenario

**Multi-Asset Trade Lifecycle Management**:
- Trade execution across multiple asset classes (equities, FX, bonds)
- Position updates in multiple accounts
- Cash movements in multiple currencies
- Regulatory reporting across all domains
- Complete ACID guarantees for complex workflows

### Implementation

**Trade Execution**:
```java
public CompletableFuture<TradeProcessingResult> executeTrade(TradeRequest request) {
    String correlationId = UUID.randomUUID().toString();
    Instant tradeTime = request.getTradeTime();
    
    return connectionProvider.withTransaction("peegeeq-main", connection -> {
        
        // Step 1: Record trade event
        return recordTradeEvent(request, correlationId, tradeTime, connection)
            
            // Step 2: Update position
            .thenCompose(tradeResult -> 
                updatePosition(request, correlationId, tradeTime, connection))
            
            // Step 3: Record cash movement
            .thenCompose(positionResult -> 
                recordCashMovement(request, correlationId, tradeTime, connection))
            
            // Step 4: Regulatory reporting
            .thenCompose(cashResult -> 
                recordRegulatoryEvent(request, correlationId, tradeTime, connection))
            
            .thenApply(regulatoryResult -> new TradeProcessingResult(
                request.getTradeId(),
                "SUCCESS",
                correlationId,
                tradeTime
            ));
            
    }).toCompletionStage().toCompletableFuture();
}
```

**Trade Amendment**:
```java
public CompletableFuture<TradeProcessingResult> amendTrade(
        String tradeId, TradeAmendmentRequest amendment) {
    
    String correlationId = UUID.randomUUID().toString();
    Instant amendmentTime = Instant.now();
    
    return connectionProvider.withTransaction("peegeeq-main", connection -> {
        
        // Find original trade
        return findTrade(tradeId)
            .thenCompose(originalTrade -> {
                
                // Step 1: Record trade amendment
                return recordTradeAmendment(originalTrade, amendment, correlationId, 
                    amendmentTime, connection)
                    
                    // Step 2: Adjust position
                    .thenCompose(tradeResult -> 
                        adjustPosition(originalTrade, amendment, correlationId, 
                            amendmentTime, connection))
                    
                    // Step 3: Adjust cash
                    .thenCompose(positionResult -> 
                        adjustCash(originalTrade, amendment, correlationId, 
                            amendmentTime, connection))
                    
                    // Step 4: Regulatory reporting
                    .thenCompose(cashResult -> 
                        recordAmendmentRegulatory(originalTrade, amendment, correlationId, 
                            amendmentTime, connection))
                    
                    .thenApply(regulatoryResult -> new TradeProcessingResult(
                        tradeId,
                        "AMENDED",
                        correlationId,
                        amendmentTime
                    ));
            });
            
    }).toCompletionStage().toCompletableFuture();
}
```

---

## Best Practices

### 1. Use Same Connection

**Why**: Ensures all operations in same transaction.

```java
// ✅ CORRECT
connectionProvider.withTransaction("client-id", connection -> {
    return appendToStore1(event1, connection)
        .thenCompose(r -> appendToStore2(event2, connection))
        .thenCompose(r -> appendToStore3(event3, connection));
});

// ❌ WRONG - separate transactions
appendToStore1(event1);
appendToStore2(event2);
appendToStore3(event3);
```

### 2. Use Correlation IDs

**Why**: Links related events across stores.

```java
// ✅ CORRECT
String correlationId = UUID.randomUUID().toString();
Map<String, String> metadata = Map.of("correlationId", correlationId);

// ❌ WRONG - no correlation
Map<String, String> metadata = Map.of();
```

### 3. Compose Operations

**Why**: Chains operations in same transaction.

```java
// ✅ CORRECT - composed
return step1(connection)
    .thenCompose(r -> step2(connection))
    .thenCompose(r -> step3(connection));

// ❌ WRONG - parallel (race conditions)
CompletableFuture.allOf(
    step1(connection),
    step2(connection),
    step3(connection)
);
```

### 4. Use Same Valid Time

**Why**: Represents single business event.

```java
// ✅ CORRECT
Instant validTime = Instant.now();
appendToStore1(event1, validTime, connection);
appendToStore2(event2, validTime, connection);

// ❌ WRONG - different times
appendToStore1(event1, Instant.now(), connection);
appendToStore2(event2, Instant.now(), connection);
```

### 5. Handle Errors Properly

**Why**: Ensures rollback on any failure.

```java
// ✅ CORRECT
return connectionProvider.withTransaction("client-id", connection -> {
    return step1(connection)
        .thenCompose(r -> step2(connection));
})
.toCompletionStage()
.toCompletableFuture()
.exceptionally(ex -> {
    log.error("Transaction failed - ALL rolled back", ex);
    return failureResult;
});
```

---

## Summary

**Key Takeaways**:

1. **Multiple Event Stores** - Separate stores for different domains
2. **Single Transaction** - Use `withTransaction()` for coordination
3. **Same Connection** - Pass connection to all operations
4. **Correlation IDs** - Link events across stores
5. **Saga Pattern** - Compensating actions for complex workflows
6. **Cross-Store Queries** - Query by correlation ID or entity ID
7. **ACID Guarantees** - All succeed or all fail together

**Related Guides**:
- **BITEMPORAL_BASICS_GUIDE.md** - Basic bi-temporal concepts
- **REACTIVE_BITEMPORAL_GUIDE.md** - Reactive integration
- **INTEGRATED_PATTERN_GUIDE.md** - Outbox + bi-temporal integration
- **CONSUMER_GROUP_GUIDE.md** - Message consumption patterns

