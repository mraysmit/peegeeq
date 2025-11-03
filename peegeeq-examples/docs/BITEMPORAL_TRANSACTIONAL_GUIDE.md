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

## Advanced Transaction Patterns

### Complex Multi-Asset Trade Processing

**Scenario**: Execute a complex trade involving multiple asset classes, currencies, and regulatory jurisdictions.

**Trade Components**:
- **Equity Trade**: Buy 1000 AAPL shares at $150.50
- **FX Hedge**: Sell USD 150,500 / Buy EUR 135,450 (hedge currency exposure)
- **Cash Settlement**: Debit USD account, credit EUR account
- **Regulatory Reporting**: MiFID II (EU), SEC (US), CFTC (derivatives)

```java
@Service
public class ComplexTradeProcessingService {

    private final EventStore<TradeEvent> tradeEventStore;
    private final EventStore<FxTradeEvent> fxEventStore;
    private final EventStore<CashMovementEvent> cashEventStore;
    private final EventStore<RegulatoryEvent> regulatoryEventStore;
    private final EventStore<AuditEvent> auditEventStore;

    public CompletableFuture<ComplexTradeResult> executeComplexTrade(
            ComplexTradeRequest request) {

        String correlationId = UUID.randomUUID().toString();
        String transactionId = "CTX-" + UUID.randomUUID().toString().substring(0, 8);
        Instant tradeTime = request.getTradeTime();

        return connectionProvider.withTransaction("peegeeq-main", connection -> {

            // Step 1: Audit - Transaction Started
            return recordTransactionStart(transactionId, correlationId, tradeTime, connection)

                // Step 2: Execute Equity Trade
                .thenCompose(auditResult -> {
                    TradeEvent equityTrade = new TradeEvent(
                        request.getEquityTradeId(),
                        "AAPL",
                        TradeType.EQUITY,
                        TradeSide.BUY,
                        new BigDecimal("1000"),        // quantity
                        new BigDecimal("150.50"),      // price
                        "USD",
                        request.getAccountId(),
                        TradeStatus.EXECUTED,
                        tradeTime
                    );

                    return ((PgBiTemporalEventStore<TradeEvent>) tradeEventStore)
                        .appendInTransaction("EquityTradeExecuted", equityTrade, tradeTime,
                            Map.of("correlationId", correlationId,
                                   "transactionId", transactionId,
                                   "tradeType", "EQUITY",
                                   "hedgeRequired", "true"),
                            correlationId, request.getEquityTradeId(), connection);
                })

                // Step 3: Execute FX Hedge
                .thenCompose(equityResult -> {
                    FxTradeEvent fxHedge = new FxTradeEvent(
                        request.getFxTradeId(),
                        "USDEUR",
                        TradeSide.SELL,                // Sell USD
                        new BigDecimal("150500.00"),   // USD amount
                        new BigDecimal("1.1111"),      // FX rate
                        new BigDecimal("135450.00"),   // EUR amount
                        request.getAccountId(),
                        TradeStatus.EXECUTED,
                        tradeTime
                    );

                    return ((PgBiTemporalEventStore<FxTradeEvent>) fxEventStore)
                        .appendInTransaction("FxHedgeExecuted", fxHedge, tradeTime,
                            Map.of("correlationId", correlationId,
                                   "transactionId", transactionId,
                                   "hedgeFor", request.getEquityTradeId(),
                                   "hedgeType", "CURRENCY_HEDGE"),
                            correlationId, request.getFxTradeId(), connection);
                })

                // Step 4: Record Cash Movements
                .thenCompose(fxResult -> {
                    // USD Debit
                    CashMovementEvent usdDebit = new CashMovementEvent(
                        UUID.randomUUID().toString(),
                        request.getAccountId(),
                        "USD",
                        new BigDecimal("-150500.00"),  // Debit
                        CashMovementType.TRADE_SETTLEMENT,
                        request.getEquityTradeId(),
                        tradeTime
                    );

                    CompletableFuture<BiTemporalEvent<CashMovementEvent>> usdFuture =
                        ((PgBiTemporalEventStore<CashMovementEvent>) cashEventStore)
                            .appendInTransaction("CashMovementUSD", usdDebit, tradeTime,
                                Map.of("correlationId", correlationId,
                                       "transactionId", transactionId,
                                       "movementType", "TRADE_SETTLEMENT"),
                                correlationId, usdDebit.getMovementId(), connection);

                    // EUR Credit
                    CashMovementEvent eurCredit = new CashMovementEvent(
                        UUID.randomUUID().toString(),
                        request.getAccountId(),
                        "EUR",
                        new BigDecimal("135450.00"),   // Credit
                        CashMovementType.FX_SETTLEMENT,
                        request.getFxTradeId(),
                        tradeTime
                    );

                    CompletableFuture<BiTemporalEvent<CashMovementEvent>> eurFuture =
                        ((PgBiTemporalEventStore<CashMovementEvent>) cashEventStore)
                            .appendInTransaction("CashMovementEUR", eurCredit, tradeTime,
                                Map.of("correlationId", correlationId,
                                       "transactionId", transactionId,
                                       "movementType", "FX_SETTLEMENT"),
                                correlationId, eurCredit.getMovementId(), connection);

                    return CompletableFuture.allOf(usdFuture, eurFuture)
                        .thenApply(v -> List.of(usdFuture.join(), eurFuture.join()));
                })

                // Step 5: Regulatory Reporting
                .thenCompose(cashResults -> {
                    // MiFID II Reporting (EU)
                    RegulatoryEvent mifidReport = new RegulatoryEvent(
                        UUID.randomUUID().toString(),
                        "MIFID_II",
                        "EU",
                        request.getEquityTradeId(),
                        "EQUITY_TRADE_REPORT",
                        Map.of("instrument", "AAPL",
                               "quantity", "1000",
                               "price", "150.50",
                               "venue", "XNAS"),
                        tradeTime
                    );

                    CompletableFuture<BiTemporalEvent<RegulatoryEvent>> mifidFuture =
                        ((PgBiTemporalEventStore<RegulatoryEvent>) regulatoryEventStore)
                            .appendInTransaction("MiFIDIIReport", mifidReport, tradeTime,
                                Map.of("correlationId", correlationId,
                                       "transactionId", transactionId,
                                       "regulation", "MIFID_II"),
                                correlationId, mifidReport.getReportId(), connection);

                    // CFTC Reporting (FX)
                    RegulatoryEvent cftcReport = new RegulatoryEvent(
                        UUID.randomUUID().toString(),
                        "CFTC",
                        "US",
                        request.getFxTradeId(),
                        "FX_TRADE_REPORT",
                        Map.of("currencyPair", "USDEUR",
                               "notional", "150500.00",
                               "rate", "1.1111"),
                        tradeTime
                    );

                    CompletableFuture<BiTemporalEvent<RegulatoryEvent>> cftcFuture =
                        ((PgBiTemporalEventStore<RegulatoryEvent>) regulatoryEventStore)
                            .appendInTransaction("CFTCReport", cftcReport, tradeTime,
                                Map.of("correlationId", correlationId,
                                       "transactionId", transactionId,
                                       "regulation", "CFTC"),
                                correlationId, cftcReport.getReportId(), connection);

                    return CompletableFuture.allOf(mifidFuture, cftcFuture)
                        .thenApply(v -> List.of(mifidFuture.join(), cftcFuture.join()));
                })

                // Step 6: Audit - Transaction Completed
                .thenCompose(regulatoryResults -> {
                    AuditEvent auditComplete = new AuditEvent(
                        transactionId,
                        "ComplexTradeCompleted",
                        "Complex multi-asset trade executed successfully",
                        correlationId,
                        Map.of("equityTradeId", request.getEquityTradeId(),
                               "fxTradeId", request.getFxTradeId(),
                               "totalUSDAmount", "150500.00",
                               "totalEURAmount", "135450.00")
                    );

                    return ((PgBiTemporalEventStore<AuditEvent>) auditEventStore)
                        .appendInTransaction("ComplexTradeCompleted", auditComplete, tradeTime,
                            Map.of("correlationId", correlationId,
                                   "transactionId", transactionId),
                            correlationId, transactionId, connection);
                })

                // Return final result
                .thenApply(finalAuditResult -> {
                    return new ComplexTradeResult(
                        transactionId,
                        correlationId,
                        request.getEquityTradeId(),
                        request.getFxTradeId(),
                        "SUCCESS",
                        "Complex trade executed successfully",
                        tradeTime,
                        Map.of("usdAmount", "150500.00",
                               "eurAmount", "135450.00",
                               "fxRate", "1.1111")
                    );
                });

        }).toCompletionStage().toCompletableFuture();
    }
}
```

**Transaction Guarantees**:
- All 6 steps succeed together OR all fail together
- Consistent transaction time across all event stores
- Complete audit trail with correlation ID linking
- Regulatory compliance across multiple jurisdictions
- No partial trade executions

### Transaction Correction and Amendment Patterns

**Scenario**: Correct a complex trade after execution due to data errors or late-arriving information.

```java
public CompletableFuture<ComplexTradeResult> correctComplexTrade(
        String originalTransactionId, ComplexTradeCorrection correction) {

    String correctionCorrelationId = UUID.randomUUID().toString();
    String correctionTransactionId = "CTX-CORR-" + UUID.randomUUID().toString().substring(0, 8);
    Instant correctionTime = Instant.now();
    Instant originalValidTime = correction.getOriginalValidTime(); // Keep original valid time

    return connectionProvider.withTransaction("peegeeq-main", connection -> {

        // Step 1: Audit - Correction Started
        return recordCorrectionStart(originalTransactionId, correctionTransactionId,
                correctionCorrelationId, correctionTime, connection)

            // Step 2: Correct Equity Trade (if needed)
            .thenCompose(auditResult -> {
                if (correction.hasEquityCorrection()) {
                    TradeEvent correctedEquityTrade = new TradeEvent(
                        correction.getEquityTradeId(),
                        correction.getCorrectedSymbol(),      // e.g., "AAPL" -> "MSFT"
                        TradeType.EQUITY,
                        TradeSide.BUY,
                        correction.getCorrectedQuantity(),    // e.g., 1000 -> 1500
                        correction.getCorrectedPrice(),       // e.g., 150.50 -> 151.00
                        "USD",
                        correction.getAccountId(),
                        TradeStatus.CORRECTED,
                        originalValidTime                     // Original valid time!
                    );

                    return ((PgBiTemporalEventStore<TradeEvent>) tradeEventStore)
                        .appendInTransaction("EquityTradeCorrected", correctedEquityTrade,
                            originalValidTime,  // Valid time = original trade time
                            Map.of("correlationId", correctionCorrelationId,
                                   "transactionId", correctionTransactionId,
                                   "originalTransactionId", originalTransactionId,
                                   "correctionReason", correction.getReason()),
                            correctionCorrelationId, correction.getEquityTradeId(), connection);
                } else {
                    return CompletableFuture.completedFuture(null);
                }
            })

            // Step 3: Correct FX Hedge (if needed)
            .thenCompose(equityResult -> {
                if (correction.hasFxCorrection()) {
                    FxTradeEvent correctedFxTrade = new FxTradeEvent(
                        correction.getFxTradeId(),
                        "USDEUR",
                        TradeSide.SELL,
                        correction.getCorrectedUsdAmount(),   // Adjusted for equity correction
                        correction.getCorrectedFxRate(),      // Updated rate
                        correction.getCorrectedEurAmount(),   // Recalculated
                        correction.getAccountId(),
                        TradeStatus.CORRECTED,
                        originalValidTime
                    );

                    return ((PgBiTemporalEventStore<FxTradeEvent>) fxEventStore)
                        .appendInTransaction("FxHedgeCorrected", correctedFxTrade,
                            originalValidTime,
                            Map.of("correlationId", correctionCorrelationId,
                                   "transactionId", correctionTransactionId,
                                   "originalTransactionId", originalTransactionId,
                                   "correctionReason", correction.getReason()),
                            correctionCorrelationId, correction.getFxTradeId(), connection);
                } else {
                    return CompletableFuture.completedFuture(null);
                }
            })

            // Step 4: Correct Cash Movements
            .thenCompose(fxResult -> {
                List<CompletableFuture<BiTemporalEvent<CashMovementEvent>>> cashCorrections =
                    correction.getCashCorrections().stream()
                        .map(cashCorrection -> {
                            CashMovementEvent correctedCash = new CashMovementEvent(
                                cashCorrection.getMovementId(),
                                correction.getAccountId(),
                                cashCorrection.getCurrency(),
                                cashCorrection.getCorrectedAmount(),
                                CashMovementType.TRADE_CORRECTION,
                                cashCorrection.getRelatedTradeId(),
                                originalValidTime
                            );

                            return ((PgBiTemporalEventStore<CashMovementEvent>) cashEventStore)
                                .appendInTransaction("CashMovementCorrected", correctedCash,
                                    originalValidTime,
                                    Map.of("correlationId", correctionCorrelationId,
                                           "transactionId", correctionTransactionId,
                                           "originalTransactionId", originalTransactionId,
                                           "correctionReason", correction.getReason()),
                                    correctionCorrelationId, cashCorrection.getMovementId(), connection);
                        })
                        .toList();

                return CompletableFuture.allOf(cashCorrections.toArray(new CompletableFuture[0]))
                    .thenApply(v -> cashCorrections.stream()
                        .map(CompletableFuture::join)
                        .toList());
            })

            // Step 5: Update Regulatory Reports
            .thenCompose(cashResults -> {
                List<CompletableFuture<BiTemporalEvent<RegulatoryEvent>>> regulatoryCorrections =
                    correction.getRegulatoryCorrections().stream()
                        .map(regCorrection -> {
                            RegulatoryEvent correctedReport = new RegulatoryEvent(
                                regCorrection.getReportId(),
                                regCorrection.getRegulation(),
                                regCorrection.getJurisdiction(),
                                regCorrection.getTradeId(),
                                "TRADE_CORRECTION_REPORT",
                                regCorrection.getCorrectedData(),
                                originalValidTime
                            );

                            return ((PgBiTemporalEventStore<RegulatoryEvent>) regulatoryEventStore)
                                .appendInTransaction("RegulatoryReportCorrected", correctedReport,
                                    originalValidTime,
                                    Map.of("correlationId", correctionCorrelationId,
                                           "transactionId", correctionTransactionId,
                                           "originalTransactionId", originalTransactionId,
                                           "correctionReason", correction.getReason()),
                                    correctionCorrelationId, regCorrection.getReportId(), connection);
                        })
                        .toList();

                return CompletableFuture.allOf(regulatoryCorrections.toArray(new CompletableFuture[0]))
                    .thenApply(v -> regulatoryCorrections.stream()
                        .map(CompletableFuture::join)
                        .toList());
            })

            // Step 6: Audit - Correction Completed
            .thenCompose(regulatoryResults -> {
                AuditEvent auditComplete = new AuditEvent(
                    correctionTransactionId,
                    "ComplexTradeCorrected",
                    "Complex trade correction completed: " + correction.getReason(),
                    correctionCorrelationId,
                    Map.of("originalTransactionId", originalTransactionId,
                           "correctionReason", correction.getReason(),
                           "fieldsChanged", String.join(",", correction.getChangedFields()))
                );

                return ((PgBiTemporalEventStore<AuditEvent>) auditEventStore)
                    .appendInTransaction("ComplexTradeCorrected", auditComplete, correctionTime,
                        Map.of("correlationId", correctionCorrelationId,
                               "transactionId", correctionTransactionId,
                               "originalTransactionId", originalTransactionId),
                        correctionCorrelationId, correctionTransactionId, connection);
            })

            .thenApply(finalAuditResult -> {
                return new ComplexTradeResult(
                    correctionTransactionId,
                    correctionCorrelationId,
                    correction.getEquityTradeId(),
                    correction.getFxTradeId(),
                    "CORRECTED",
                    "Complex trade corrected: " + correction.getReason(),
                    originalValidTime,  // Valid time preserved
                    correction.getCorrectedValues()
                );
            });

    }).toCompletionStage().toCompletableFuture();
}
```

**Correction Audit Trail**:
- Original events preserved (never modified)
- Correction events use original valid time
- Complete reason tracking for compliance
- Links to original transaction via metadata
- All corrections in single atomic transaction

---

## Advanced Query Patterns

### Cross-Store Transaction History

**Pattern**: Reconstruct complete transaction history across all event stores.

```java
public CompletableFuture<CompleteTransactionHistory> getCompleteTransactionHistory(
        String correlationId) {

    // Query all stores in parallel
    CompletableFuture<List<BiTemporalEvent<TradeEvent>>> tradeFuture =
        tradeEventStore.query(EventQuery.all())
            .thenApply(events -> events.stream()
                .filter(e -> correlationId.equals(e.getMetadata().get("correlationId")))
                .sorted(Comparator.comparing(BiTemporalEvent::getValidTime))
                .collect(Collectors.toList())
            );

    CompletableFuture<List<BiTemporalEvent<FxTradeEvent>>> fxFuture =
        fxEventStore.query(EventQuery.all())
            .thenApply(events -> events.stream()
                .filter(e -> correlationId.equals(e.getMetadata().get("correlationId")))
                .sorted(Comparator.comparing(BiTemporalEvent::getValidTime))
                .collect(Collectors.toList())
            );

    CompletableFuture<List<BiTemporalEvent<CashMovementEvent>>> cashFuture =
        cashEventStore.query(EventQuery.all())
            .thenApply(events -> events.stream()
                .filter(e -> correlationId.equals(e.getMetadata().get("correlationId")))
                .sorted(Comparator.comparing(BiTemporalEvent::getValidTime))
                .collect(Collectors.toList())
            );

    CompletableFuture<List<BiTemporalEvent<RegulatoryEvent>>> regulatoryFuture =
        regulatoryEventStore.query(EventQuery.all())
            .thenApply(events -> events.stream()
                .filter(e -> correlationId.equals(e.getMetadata().get("correlationId")))
                .sorted(Comparator.comparing(BiTemporalEvent::getValidTime))
                .collect(Collectors.toList())
            );

    CompletableFuture<List<BiTemporalEvent<AuditEvent>>> auditFuture =
        auditEventStore.query(EventQuery.all())
            .thenApply(events -> events.stream()
                .filter(e -> correlationId.equals(e.getMetadata().get("correlationId")))
                .sorted(Comparator.comparing(BiTemporalEvent::getTransactionTime))
                .collect(Collectors.toList())
            );

    // Combine all results
    return CompletableFuture.allOf(tradeFuture, fxFuture, cashFuture, regulatoryFuture, auditFuture)
        .thenApply(v -> new CompleteTransactionHistory(
            correlationId,
            tradeFuture.join(),
            fxFuture.join(),
            cashFuture.join(),
            regulatoryFuture.join(),
            auditFuture.join()
        ));
}
```

### Point-in-Time Cross-Store Query

**Pattern**: "What did we know across all stores at a specific moment?"

```java
public CompletableFuture<CrossStoreSnapshot> getSnapshotAsOf(
        String correlationId, Instant asOfTime) {

    // Query each store for state as-of specific time
    CompletableFuture<Optional<TradeEvent>> tradeSnapshot =
        tradeEventStore.query(EventQuery.all())
            .thenApply(events -> events.stream()
                .filter(e -> correlationId.equals(e.getMetadata().get("correlationId")))
                .filter(e -> !e.getValidTime().isAfter(asOfTime))
                .filter(e -> !e.getTransactionTime().isAfter(asOfTime))
                .max(Comparator.comparing(BiTemporalEvent::getValidTime))
                .map(BiTemporalEvent::getPayload)
            );

    CompletableFuture<Optional<FxTradeEvent>> fxSnapshot =
        fxEventStore.query(EventQuery.all())
            .thenApply(events -> events.stream()
                .filter(e -> correlationId.equals(e.getMetadata().get("correlationId")))
                .filter(e -> !e.getValidTime().isAfter(asOfTime))
                .filter(e -> !e.getTransactionTime().isAfter(asOfTime))
                .max(Comparator.comparing(BiTemporalEvent::getValidTime))
                .map(BiTemporalEvent::getPayload)
            );

    CompletableFuture<List<CashMovementEvent>> cashSnapshot =
        cashEventStore.query(EventQuery.all())
            .thenApply(events -> events.stream()
                .filter(e -> correlationId.equals(e.getMetadata().get("correlationId")))
                .filter(e -> !e.getValidTime().isAfter(asOfTime))
                .filter(e -> !e.getTransactionTime().isAfter(asOfTime))
                .map(BiTemporalEvent::getPayload)
                .collect(Collectors.toList())
            );

    return CompletableFuture.allOf(tradeSnapshot, fxSnapshot, cashSnapshot)
        .thenApply(v -> new CrossStoreSnapshot(
            correlationId,
            asOfTime,
            tradeSnapshot.join(),
            fxSnapshot.join(),
            cashSnapshot.join()
        ));
}
```

### Find All Corrections Across Stores

**Pattern**: Compliance audit - show all corrections made to a transaction.

```java
public CompletableFuture<TransactionCorrections> getAllCorrections(String originalTransactionId) {

    // Find all correction events across stores
    CompletableFuture<List<BiTemporalEvent<TradeEvent>>> tradeCorrections =
        tradeEventStore.query(EventQuery.all())
            .thenApply(events -> events.stream()
                .filter(e -> originalTransactionId.equals(e.getMetadata().get("originalTransactionId")))
                .filter(e -> e.getEventType().contains("Corrected"))
                .sorted(Comparator.comparing(BiTemporalEvent::getTransactionTime))
                .collect(Collectors.toList())
            );

    CompletableFuture<List<BiTemporalEvent<FxTradeEvent>>> fxCorrections =
        fxEventStore.query(EventQuery.all())
            .thenApply(events -> events.stream()
                .filter(e -> originalTransactionId.equals(e.getMetadata().get("originalTransactionId")))
                .filter(e -> e.getEventType().contains("Corrected"))
                .sorted(Comparator.comparing(BiTemporalEvent::getTransactionTime))
                .collect(Collectors.toList())
            );

    CompletableFuture<List<BiTemporalEvent<CashMovementEvent>>> cashCorrections =
        cashEventStore.query(EventQuery.all())
            .thenApply(events -> events.stream()
                .filter(e -> originalTransactionId.equals(e.getMetadata().get("originalTransactionId")))
                .filter(e -> e.getEventType().contains("Corrected"))
                .sorted(Comparator.comparing(BiTemporalEvent::getTransactionTime))
                .collect(Collectors.toList())
            );

    return CompletableFuture.allOf(tradeCorrections, fxCorrections, cashCorrections)
        .thenApply(v -> new TransactionCorrections(
            originalTransactionId,
            tradeCorrections.join(),
            fxCorrections.join(),
            cashCorrections.join()
        ));
}
```

### Transaction Consistency Validation

**Pattern**: Verify transaction consistency across all stores.

```java
public CompletableFuture<TransactionConsistencyReport> validateTransactionConsistency(
        String correlationId) {

    return getCompleteTransactionHistory(correlationId)
        .thenApply(history -> {
            List<String> inconsistencies = new ArrayList<>();

            // Check 1: All events have same correlation ID
            boolean correlationConsistent = Stream.of(
                history.getTradeEvents().stream(),
                history.getFxEvents().stream(),
                history.getCashEvents().stream(),
                history.getRegulatoryEvents().stream(),
                history.getAuditEvents().stream()
            )
            .flatMap(stream -> stream)
            .allMatch(event -> correlationId.equals(event.getMetadata().get("correlationId")));

            if (!correlationConsistent) {
                inconsistencies.add("Correlation ID mismatch across stores");
            }

            // Check 2: Transaction times are close (within 1 second)
            List<Instant> transactionTimes = Stream.of(
                history.getTradeEvents().stream(),
                history.getFxEvents().stream(),
                history.getCashEvents().stream(),
                history.getRegulatoryEvents().stream()
            )
            .flatMap(stream -> stream)
            .map(BiTemporalEvent::getTransactionTime)
            .collect(Collectors.toList());

            if (!transactionTimes.isEmpty()) {
                Instant minTime = transactionTimes.stream().min(Instant::compareTo).get();
                Instant maxTime = transactionTimes.stream().max(Instant::compareTo).get();

                if (Duration.between(minTime, maxTime).getSeconds() > 1) {
                    inconsistencies.add("Transaction times spread over " +
                        Duration.between(minTime, maxTime).getSeconds() + " seconds");
                }
            }

            // Check 3: Cash movements balance
            BigDecimal totalCashMovements = history.getCashEvents().stream()
                .map(event -> event.getPayload().getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (totalCashMovements.abs().compareTo(new BigDecimal("0.01")) > 0) {
                inconsistencies.add("Cash movements don't balance: " + totalCashMovements);
            }

            return new TransactionConsistencyReport(
                correlationId,
                inconsistencies.isEmpty(),
                inconsistencies,
                history
            );
        });
}
```

---

## Regulatory Compliance and Audit Patterns

### Complete Audit Trail Reconstruction

**Scenario**: Regulatory inquiry requiring complete transaction history.

**Question**: "Show complete audit trail for transaction CTX-12345 including all corrections"

```java
public CompletableFuture<RegulatoryAuditReport> generateRegulatoryAuditReport(
        String transactionId) {

    return getCompleteTransactionHistory(transactionId)
        .thenCompose(originalHistory -> {
            // Also get all corrections
            return getAllCorrections(transactionId)
                .thenApply(corrections -> {

                    // Build timeline
                    List<AuditTrailEntry> timeline = new ArrayList<>();

                    // Add original events
                    originalHistory.getAllEvents().forEach(event -> {
                        timeline.add(new AuditTrailEntry(
                            event.getEventType(),
                            "ORIGINAL",
                            event.getValidTime(),
                            event.getTransactionTime(),
                            event.getPayload(),
                            event.getMetadata()
                        ));
                    });

                    // Add corrections
                    corrections.getAllCorrections().forEach(correction -> {
                        timeline.add(new AuditTrailEntry(
                            correction.getEventType(),
                            "CORRECTION",
                            correction.getValidTime(),
                            correction.getTransactionTime(),
                            correction.getPayload(),
                            correction.getMetadata()
                        ));
                    });

                    // Sort by transaction time (when we learned about it)
                    timeline.sort(Comparator.comparing(AuditTrailEntry::getTransactionTime));

                    return new RegulatoryAuditReport(
                        transactionId,
                        timeline,
                        originalHistory,
                        corrections,
                        generateComplianceSummary(originalHistory, corrections)
                    );
                });
        });
}
```

### MiFID II Transaction Reporting

**Pattern**: Generate MiFID II compliant transaction report.

```java
public CompletableFuture<MiFIDIIReport> generateMiFIDIIReport(String correlationId) {

    return getCompleteTransactionHistory(correlationId)
        .thenApply(history -> {

            // Extract trade details
            TradeEvent equityTrade = history.getTradeEvents().stream()
                .filter(e -> e.getPayload().getTradeType() == TradeType.EQUITY)
                .map(BiTemporalEvent::getPayload)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No equity trade found"));

            // Extract FX hedge details
            Optional<FxTradeEvent> fxHedge = history.getFxEvents().stream()
                .map(BiTemporalEvent::getPayload)
                .findFirst();

            // Build MiFID II report
            return new MiFIDIIReport(
                correlationId,
                equityTrade.getTradeId(),
                equityTrade.getSymbol(),
                equityTrade.getQuantity(),
                equityTrade.getPrice(),
                equityTrade.getTradeTime(),
                "XNAS",  // Trading venue
                fxHedge.map(fx -> new MiFIDIIReport.FxHedgeDetails(
                    fx.getCurrencyPair(),
                    fx.getRate(),
                    fx.getUsdAmount()
                )),
                history.getRegulatoryEvents().stream()
                    .filter(e -> "MIFID_II".equals(e.getPayload().getRegulation()))
                    .map(BiTemporalEvent::getPayload)
                    .findFirst()
                    .map(RegulatoryEvent::getReportingTime)
                    .orElse(null)
            );
        });
}
```

### CFTC Swap Data Repository Reporting

**Pattern**: Generate CFTC SDR report for FX trades.

```java
public CompletableFuture<CFTCSDRReport> generateCFTCSDRReport(String correlationId) {

    return getCompleteTransactionHistory(correlationId)
        .thenApply(history -> {

            // Extract FX trade details
            FxTradeEvent fxTrade = history.getFxEvents().stream()
                .map(BiTemporalEvent::getPayload)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No FX trade found"));

            // Extract related equity trade for context
            Optional<TradeEvent> relatedEquityTrade = history.getTradeEvents().stream()
                .map(BiTemporalEvent::getPayload)
                .findFirst();

            return new CFTCSDRReport(
                correlationId,
                fxTrade.getTradeId(),
                fxTrade.getCurrencyPair(),
                fxTrade.getUsdAmount(),
                fxTrade.getRate(),
                fxTrade.getTradeTime(),
                "HEDGE",  // Transaction type
                relatedEquityTrade.map(TradeEvent::getTradeId),  // Underlying reference
                history.getRegulatoryEvents().stream()
                    .filter(e -> "CFTC".equals(e.getPayload().getRegulation()))
                    .map(BiTemporalEvent::getPayload)
                    .findFirst()
                    .map(RegulatoryEvent::getReportingTime)
                    .orElse(null)
            );
        });
}
```

### SOX Compliance - Internal Controls Audit

**Pattern**: Demonstrate internal controls and segregation of duties.

```java
public CompletableFuture<SOXComplianceReport> generateSOXComplianceReport(
        String transactionId) {

    return getCompleteTransactionHistory(transactionId)
        .thenCompose(history -> {
            return getAllCorrections(transactionId)
                .thenApply(corrections -> {

                    List<SOXControlPoint> controlPoints = new ArrayList<>();

                    // Control Point 1: Transaction Authorization
                    history.getAuditEvents().stream()
                        .filter(e -> e.getEventType().contains("Started"))
                        .forEach(event -> {
                            controlPoints.add(new SOXControlPoint(
                                "TRANSACTION_AUTHORIZATION",
                                "Transaction properly authorized",
                                event.getTransactionTime(),
                                event.getMetadata().get("authorizedBy"),
                                "PASS"
                            ));
                        });

                    // Control Point 2: Segregation of Duties
                    Set<String> systemSources = history.getAllEvents().stream()
                        .map(e -> e.getMetadata().get("systemSource"))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

                    controlPoints.add(new SOXControlPoint(
                        "SEGREGATION_OF_DUTIES",
                        "Multiple systems involved in transaction processing",
                        null,
                        String.join(", ", systemSources),
                        systemSources.size() > 1 ? "PASS" : "FAIL"
                    ));

                    // Control Point 3: Change Management
                    if (!corrections.getAllCorrections().isEmpty()) {
                        corrections.getAllCorrections().forEach(correction -> {
                            controlPoints.add(new SOXControlPoint(
                                "CHANGE_MANAGEMENT",
                                "Correction properly documented and authorized",
                                correction.getTransactionTime(),
                                correction.getMetadata().get("correctionReason"),
                                correction.getMetadata().containsKey("correctionReason") ? "PASS" : "FAIL"
                            ));
                        });
                    }

                    // Control Point 4: Data Integrity
                    return validateTransactionConsistency(transactionId)
                        .thenApply(consistencyReport -> {
                            controlPoints.add(new SOXControlPoint(
                                "DATA_INTEGRITY",
                                "Transaction data consistent across all systems",
                                null,
                                consistencyReport.getInconsistencies().toString(),
                                consistencyReport.isConsistent() ? "PASS" : "FAIL"
                            ));

                            return new SOXComplianceReport(
                                transactionId,
                                controlPoints,
                                history,
                                corrections,
                                consistencyReport
                            );
                        });
                });
        }).thenCompose(Function.identity());
}
```

---

## Exception Handling in Multi-Store Transactions

### Middle Office Exception Processing

**Scenario**: Handle exceptions during complex multi-store transactions with proper rollback and recovery.

**Middle Office Responsibilities**:
- Trade confirmation and matching across multiple systems
- Position reconciliation between trading and settlement systems
- Exception detection and resolution workflows
- STP (Straight Through Processing) break management
- Cross-system data consistency validation

### Exception Types and Handling Patterns

```java
// Exception event data model
public class TransactionExceptionEvent {
    private String exceptionId;
    private String transactionId;
    private String correlationId;
    private String exceptionType;     // MATCHING_FAILED, DATA_INCONSISTENCY, SYSTEM_TIMEOUT, etc.
    private String severity;          // LOW, MEDIUM, HIGH, CRITICAL
    private String description;
    private String affectedStore;     // Which event store had the issue
    private String originalValue;
    private String expectedValue;
    private String systemSource;
    private String assignedTo;
    private String status;            // DETECTED, IN_PROGRESS, RESOLVED, ESCALATED
    private String resolutionAction;
    private Instant detectedTime;
    private Instant resolvedTime;
    private Map<String, String> additionalData;
}

@Service
public class TransactionExceptionHandler {

    private final EventStore<TransactionExceptionEvent> exceptionEventStore;
    private final EventStore<AuditEvent> auditEventStore;
    private final List<EventStore<?>> allEventStores;

    public CompletableFuture<TransactionExceptionResult> handleTransactionException(
            String originalTransactionId, TransactionException exception) {

        String exceptionCorrelationId = "EXC-" + UUID.randomUUID().toString().substring(0, 8);
        String exceptionTransactionId = "EXC-TX-" + UUID.randomUUID().toString().substring(0, 8);
        Instant exceptionTime = Instant.now();

        return connectionProvider.withTransaction("peegeeq-main", connection -> {

            // Step 1: Record exception detection
            return recordExceptionDetection(originalTransactionId, exception,
                    exceptionCorrelationId, exceptionTransactionId, exceptionTime, connection)

                // Step 2: Attempt automated resolution
                .thenCompose(detectionResult -> {
                    if (exception.isAutoResolvable()) {
                        return attemptAutomatedResolution(originalTransactionId, exception,
                            exceptionCorrelationId, exceptionTransactionId, exceptionTime, connection);
                    } else {
                        return CompletableFuture.completedFuture(null);
                    }
                })

                // Step 3: If automated resolution fails, escalate
                .thenCompose(resolutionResult -> {
                    if (resolutionResult == null || !resolutionResult.isSuccessful()) {
                        return escalateException(originalTransactionId, exception,
                            exceptionCorrelationId, exceptionTransactionId, exceptionTime, connection);
                    } else {
                        return recordExceptionResolved(originalTransactionId, exception,
                            exceptionCorrelationId, exceptionTransactionId, exceptionTime, connection);
                    }
                })

                .thenApply(finalResult -> new TransactionExceptionResult(
                    exceptionTransactionId,
                    exceptionCorrelationId,
                    originalTransactionId,
                    exception.getExceptionType(),
                    finalResult.getStatus(),
                    finalResult.getDescription(),
                    exceptionTime
                ));

        }).toCompletionStage().toCompletableFuture();
    }
}
```

### Complex Transaction Rollback with Exception Tracking

**Scenario**: Multi-store transaction fails partway through - need to rollback with complete audit trail.

```java
public CompletableFuture<ComplexTradeResult> executeComplexTradeWithExceptionHandling(
        ComplexTradeRequest request) {

    String correlationId = UUID.randomUUID().toString();
    String transactionId = "CTX-" + UUID.randomUUID().toString().substring(0, 8);
    Instant tradeTime = request.getTradeTime();

    return connectionProvider.withTransaction("peegeeq-main", connection -> {

        // Step 1: Audit - Transaction Started
        return recordTransactionStart(transactionId, correlationId, tradeTime, connection)

            // Step 2: Execute Equity Trade
            .thenCompose(auditResult -> {
                return executeEquityTradeWithValidation(request, correlationId,
                    transactionId, tradeTime, connection)
                    .exceptionally(throwable -> {
                        // Record exception but don't fail transaction yet
                        recordTradeException("EQUITY_TRADE_FAILED", throwable,
                            correlationId, transactionId, connection);
                        throw new CompletionException(throwable);
                    });
            })

            // Step 3: Execute FX Hedge
            .thenCompose(equityResult -> {
                return executeFxHedgeWithValidation(request, correlationId,
                    transactionId, tradeTime, connection)
                    .exceptionally(throwable -> {
                        recordTradeException("FX_HEDGE_FAILED", throwable,
                            correlationId, transactionId, connection);
                        throw new CompletionException(throwable);
                    });
            })

            // Step 4: Record Cash Movements
            .thenCompose(fxResult -> {
                return recordCashMovementsWithValidation(request, correlationId,
                    transactionId, tradeTime, connection)
                    .exceptionally(throwable -> {
                        recordTradeException("CASH_MOVEMENT_FAILED", throwable,
                            correlationId, transactionId, connection);
                        throw new CompletionException(throwable);
                    });
            })

            // Step 5: Regulatory Reporting
            .thenCompose(cashResult -> {
                return recordRegulatoryReportsWithValidation(request, correlationId,
                    transactionId, tradeTime, connection)
                    .exceptionally(throwable -> {
                        recordTradeException("REGULATORY_REPORTING_FAILED", throwable,
                            correlationId, transactionId, connection);
                        throw new CompletionException(throwable);
                    });
            })

            // Step 6: Final Success Audit
            .thenCompose(regulatoryResult -> {
                return recordTransactionSuccess(transactionId, correlationId, tradeTime, connection);
            })

            .thenApply(finalResult -> new ComplexTradeResult(
                transactionId, correlationId, request.getEquityTradeId(),
                request.getFxTradeId(), "SUCCESS",
                "Complex trade executed successfully", tradeTime,
                Map.of("totalSteps", "6", "allStepsCompleted", "true")
            ));

    })
    .toCompletionStage()
    .toCompletableFuture()
    .exceptionally(throwable -> {
        // Transaction failed - all changes automatically rolled back
        // Record the failure for audit purposes
        recordTransactionFailure(transactionId, correlationId, throwable, tradeTime);

        return new ComplexTradeResult(
            transactionId, correlationId, request.getEquityTradeId(),
            request.getFxTradeId(), "FAILED",
            "Transaction failed and rolled back: " + throwable.getMessage(),
            tradeTime,
            Map.of("rollbackCompleted", "true",
                   "errorType", throwable.getClass().getSimpleName())
        );
    });
}
```

### Data Consistency Exception Handling

**Scenario**: Detect and resolve data inconsistencies across multiple event stores.

```java
public CompletableFuture<ConsistencyCheckResult> performConsistencyCheckWithCorrection(
        String correlationId) {

    return getCompleteTransactionHistory(correlationId)
        .thenCompose(history -> {
            return validateTransactionConsistency(correlationId)
                .thenCompose(consistencyReport -> {

                    if (!consistencyReport.isConsistent()) {
                        // Found inconsistencies - need to correct them
                        return correctDataInconsistencies(correlationId, consistencyReport);
                    } else {
                        return CompletableFuture.completedFuture(
                            new ConsistencyCheckResult(correlationId, true,
                                "No inconsistencies found", Collections.emptyList())
                        );
                    }
                });
        });
}

private CompletableFuture<ConsistencyCheckResult> correctDataInconsistencies(
        String correlationId, TransactionConsistencyReport consistencyReport) {

    String correctionCorrelationId = "CORR-" + UUID.randomUUID().toString().substring(0, 8);
    String correctionTransactionId = "CORR-TX-" + UUID.randomUUID().toString().substring(0, 8);
    Instant correctionTime = Instant.now();

    return connectionProvider.withTransaction("peegeeq-main", connection -> {

        List<CompletableFuture<Void>> correctionFutures = new ArrayList<>();

        // Step 1: Record consistency exception
        TransactionExceptionEvent consistencyException = new TransactionExceptionEvent(
            UUID.randomUUID().toString(),
            correctionTransactionId,
            correctionCorrelationId,
            "DATA_INCONSISTENCY",
            "HIGH",
            "Data inconsistency detected: " + String.join(", ", consistencyReport.getInconsistencies()),
            "MULTIPLE_STORES",
            null, null,
            "CONSISTENCY_CHECKER",
            "middle.office.team",
            "IN_PROGRESS",
            null,
            correctionTime, null,
            Map.of("originalCorrelationId", correlationId,
                   "inconsistencyCount", String.valueOf(consistencyReport.getInconsistencies().size()))
        );

        CompletableFuture<Void> exceptionFuture =
            ((PgBiTemporalEventStore<TransactionExceptionEvent>) exceptionEventStore)
                .appendInTransaction("DataInconsistencyDetected", consistencyException, correctionTime,
                    Map.of("correlationId", correctionCorrelationId,
                           "transactionId", correctionTransactionId,
                           "originalCorrelationId", correlationId),
                    correctionCorrelationId, consistencyException.getExceptionId(), connection)
                .thenApply(result -> null);

        correctionFutures.add(exceptionFuture);

        // Step 2: Apply corrections based on inconsistency type
        for (String inconsistency : consistencyReport.getInconsistencies()) {
            if (inconsistency.contains("Cash movements don't balance")) {
                // Add balancing cash movement
                correctionFutures.add(addBalancingCashMovement(correlationId,
                    correctionCorrelationId, correctionTransactionId, correctionTime, connection));
            }

            if (inconsistency.contains("Transaction times spread")) {
                // Record timing inconsistency for audit
                correctionFutures.add(recordTimingInconsistency(correlationId,
                    correctionCorrelationId, correctionTransactionId, correctionTime, connection));
            }

            if (inconsistency.contains("Correlation ID mismatch")) {
                // Fix correlation ID references
                correctionFutures.add(fixCorrelationIdReferences(correlationId,
                    correctionCorrelationId, correctionTransactionId, correctionTime, connection));
            }
        }

        // Step 3: Record correction completion
        return CompletableFuture.allOf(correctionFutures.toArray(new CompletableFuture[0]))
            .thenCompose(v -> {
                TransactionExceptionEvent correctionComplete = new TransactionExceptionEvent(
                    consistencyException.getExceptionId(),
                    correctionTransactionId,
                    correctionCorrelationId,
                    "DATA_INCONSISTENCY",
                    "HIGH",
                    "Data inconsistency corrected successfully",
                    "MULTIPLE_STORES",
                    null, null,
                    "CONSISTENCY_CHECKER",
                    "middle.office.team",
                    "RESOLVED",
                    "AUTOMATED_CORRECTION",
                    correctionTime, correctionTime,
                    Map.of("originalCorrelationId", correlationId,
                           "correctionsApplied", String.valueOf(correctionFutures.size() - 1))
                );

                return ((PgBiTemporalEventStore<TransactionExceptionEvent>) exceptionEventStore)
                    .appendInTransaction("DataInconsistencyResolved", correctionComplete, correctionTime,
                        Map.of("correlationId", correctionCorrelationId,
                               "transactionId", correctionTransactionId,
                               "originalCorrelationId", correlationId),
                        correctionCorrelationId, correctionComplete.getExceptionId(), connection);
            })
            .thenApply(result -> new ConsistencyCheckResult(
                correlationId, true,
                "Inconsistencies detected and corrected",
                consistencyReport.getInconsistencies()
            ));

    }).toCompletionStage().toCompletableFuture();
}
```

### Exception Recovery and Retry Patterns

**Scenario**: Implement sophisticated retry logic with exponential backoff for transient failures.

```java
@Service
public class TransactionRetryHandler {

    private final ScheduledExecutorService retryExecutor =
        Executors.newScheduledThreadPool(5, r -> {
            Thread t = new Thread(r, "transaction-retry");
            t.setDaemon(true);
            return t;
        });

    public CompletableFuture<ComplexTradeResult> executeWithRetry(
            ComplexTradeRequest request, int maxRetries) {

        return executeWithRetryInternal(request, maxRetries, 1, null);
    }

    private CompletableFuture<ComplexTradeResult> executeWithRetryInternal(
            ComplexTradeRequest request, int maxRetries, int attempt, Throwable lastError) {

        if (attempt > maxRetries) {
            return recordFinalFailure(request, lastError, attempt - 1);
        }

        String retryCorrelationId = "RETRY-" + UUID.randomUUID().toString().substring(0, 8);

        return executeComplexTradeWithExceptionHandling(request)
            .thenCompose(result -> {
                if ("SUCCESS".equals(result.getStatus())) {
                    // Success - record retry success if this wasn't first attempt
                    if (attempt > 1) {
                        return recordRetrySuccess(request, retryCorrelationId, attempt)
                            .thenApply(v -> result);
                    } else {
                        return CompletableFuture.completedFuture(result);
                    }
                } else {
                    // Failed - schedule retry
                    return scheduleRetry(request, maxRetries, attempt,
                        new RuntimeException(result.getDescription()));
                }
            })
            .exceptionally(throwable -> {
                // Exception occurred - schedule retry
                return scheduleRetry(request, maxRetries, attempt, throwable).join();
            });
    }

    private CompletableFuture<ComplexTradeResult> scheduleRetry(
            ComplexTradeRequest request, int maxRetries, int attempt, Throwable error) {

        // Calculate exponential backoff delay
        long delayMs = Math.min(1000 * (long) Math.pow(2, attempt - 1), 30000); // Max 30 seconds

        String retryCorrelationId = "RETRY-" + UUID.randomUUID().toString().substring(0, 8);

        // Record retry attempt
        recordRetryAttempt(request, retryCorrelationId, attempt, error, delayMs);

        CompletableFuture<ComplexTradeResult> retryFuture = new CompletableFuture<>();

        retryExecutor.schedule(() -> {
            executeWithRetryInternal(request, maxRetries, attempt + 1, error)
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        retryFuture.completeExceptionally(throwable);
                    } else {
                        retryFuture.complete(result);
                    }
                });
        }, delayMs, TimeUnit.MILLISECONDS);

        return retryFuture;
    }

    private CompletableFuture<Void> recordRetryAttempt(
            ComplexTradeRequest request, String retryCorrelationId,
            int attempt, Throwable error, long delayMs) {

        String retryTransactionId = "RETRY-TX-" + UUID.randomUUID().toString().substring(0, 8);
        Instant retryTime = Instant.now();

        return connectionProvider.withTransaction("peegeeq-main", connection -> {

            TransactionExceptionEvent retryEvent = new TransactionExceptionEvent(
                UUID.randomUUID().toString(),
                retryTransactionId,
                retryCorrelationId,
                "TRANSACTION_RETRY",
                "MEDIUM",
                "Retry attempt " + attempt + " scheduled due to: " + error.getMessage(),
                "TRANSACTION_PROCESSOR",
                null, null,
                "RETRY_HANDLER",
                "system.auto.retry",
                "IN_PROGRESS",
                "AUTOMATED_RETRY",
                retryTime, null,
                Map.of("originalTradeId", request.getEquityTradeId(),
                       "retryAttempt", String.valueOf(attempt),
                       "delayMs", String.valueOf(delayMs),
                       "errorType", error.getClass().getSimpleName())
            );

            return ((PgBiTemporalEventStore<TransactionExceptionEvent>) exceptionEventStore)
                .appendInTransaction("TransactionRetryScheduled", retryEvent, retryTime,
                    Map.of("correlationId", retryCorrelationId,
                           "transactionId", retryTransactionId),
                    retryCorrelationId, retryEvent.getExceptionId(), connection)
                .thenApply(result -> null);

        }).toCompletionStage().toCompletableFuture();
    }
}
```

### Exception Monitoring and Alerting

**Scenario**: Monitor exception patterns and trigger alerts for operational teams.

```java
@Service
public class TransactionExceptionMonitor {

    public CompletableFuture<ExceptionMonitoringReport> generateExceptionReport(
            Instant fromTime, Instant toTime) {

        return exceptionEventStore.query(EventQuery.all())
            .thenApply(events -> {

                List<BiTemporalEvent<TransactionExceptionEvent>> relevantExceptions = events.stream()
                    .filter(e -> !e.getTransactionTime().isBefore(fromTime))
                    .filter(e -> !e.getTransactionTime().isAfter(toTime))
                    .sorted(Comparator.comparing(BiTemporalEvent::getTransactionTime))
                    .collect(Collectors.toList());

                // Group by exception type
                Map<String, List<BiTemporalEvent<TransactionExceptionEvent>>> byType =
                    relevantExceptions.stream()
                        .collect(Collectors.groupingBy(e -> e.getPayload().getExceptionType()));

                // Group by severity
                Map<String, List<BiTemporalEvent<TransactionExceptionEvent>>> bySeverity =
                    relevantExceptions.stream()
                        .collect(Collectors.groupingBy(e -> e.getPayload().getSeverity()));

                // Calculate resolution times
                Map<String, Duration> avgResolutionTimes = calculateAverageResolutionTimes(relevantExceptions);

                // Identify trending issues
                List<String> trendingIssues = identifyTrendingIssues(byType, fromTime, toTime);

                // Check SLA breaches
                List<ExceptionSLABreach> slaBreaches = checkSLABreaches(relevantExceptions);

                return new ExceptionMonitoringReport(
                    fromTime, toTime,
                    relevantExceptions.size(),
                    byType,
                    bySeverity,
                    avgResolutionTimes,
                    trendingIssues,
                    slaBreaches
                );
            });
    }

    private List<ExceptionSLABreach> checkSLABreaches(
            List<BiTemporalEvent<TransactionExceptionEvent>> exceptions) {

        Map<String, Duration> slaThresholds = Map.of(
            "CRITICAL", Duration.ofMinutes(15),
            "HIGH", Duration.ofHours(2),
            "MEDIUM", Duration.ofHours(8),
            "LOW", Duration.ofHours(24)
        );

        return exceptions.stream()
            .filter(e -> "RESOLVED".equals(e.getPayload().getStatus()))
            .filter(e -> e.getPayload().getResolvedTime() != null)
            .map(e -> {
                Duration resolutionTime = Duration.between(
                    e.getPayload().getDetectedTime(),
                    e.getPayload().getResolvedTime()
                );

                Duration slaThreshold = slaThresholds.get(e.getPayload().getSeverity());

                if (resolutionTime.compareTo(slaThreshold) > 0) {
                    return new ExceptionSLABreach(
                        e.getPayload().getExceptionId(),
                        e.getPayload().getExceptionType(),
                        e.getPayload().getSeverity(),
                        resolutionTime,
                        slaThreshold,
                        Duration.between(slaThreshold, resolutionTime)
                    );
                } else {
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    public CompletableFuture<Void> triggerAlertsIfNeeded(ExceptionMonitoringReport report) {

        List<CompletableFuture<Void>> alertFutures = new ArrayList<>();

        // Alert on high exception volume
        if (report.getTotalExceptions() > 100) {
            alertFutures.add(sendAlert("HIGH_EXCEPTION_VOLUME",
                "Exception volume exceeded threshold: " + report.getTotalExceptions()));
        }

        // Alert on critical exceptions
        int criticalCount = report.getBySeverity().getOrDefault("CRITICAL", Collections.emptyList()).size();
        if (criticalCount > 0) {
            alertFutures.add(sendAlert("CRITICAL_EXCEPTIONS",
                "Critical exceptions detected: " + criticalCount));
        }

        // Alert on SLA breaches
        if (!report.getSlaBreaches().isEmpty()) {
            alertFutures.add(sendAlert("SLA_BREACHES",
                "SLA breaches detected: " + report.getSlaBreaches().size()));
        }

        // Alert on trending issues
        if (!report.getTrendingIssues().isEmpty()) {
            alertFutures.add(sendAlert("TRENDING_ISSUES",
                "Trending issues: " + String.join(", ", report.getTrendingIssues())));
        }

        return CompletableFuture.allOf(alertFutures.toArray(new CompletableFuture[0]));
    }

    private CompletableFuture<Void> sendAlert(String alertType, String message) {
        // Implementation would integrate with alerting system (PagerDuty, Slack, etc.)
        System.out.println("ALERT [" + alertType + "]: " + message);
        return CompletableFuture.completedFuture(null);
    }
}
```

---

## Performance and Scalability Patterns
```

### Batch Transaction Processing

**Pattern**: Process multiple related transactions efficiently.

```java
public CompletableFuture<List<ComplexTradeResult>> processBatchTrades(
        List<ComplexTradeRequest> requests) {

    // Group requests by correlation strategy
    Map<String, List<ComplexTradeRequest>> groupedRequests = requests.stream()
        .collect(Collectors.groupingBy(this::determineCorrelationStrategy));

    List<CompletableFuture<List<ComplexTradeResult>>> batchFutures =
        groupedRequests.entrySet().stream()
            .map(entry -> processBatchGroup(entry.getKey(), entry.getValue()))
            .toList();

    return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
        .thenApply(v -> batchFutures.stream()
            .flatMap(future -> future.join().stream())
            .collect(Collectors.toList())
        );
}

private CompletableFuture<List<ComplexTradeResult>> processBatchGroup(
        String strategy, List<ComplexTradeRequest> requests) {

    String batchCorrelationId = "BATCH-" + UUID.randomUUID().toString().substring(0, 8);
    Instant batchTime = Instant.now();

    return connectionProvider.withTransaction("peegeeq-main", connection -> {

        // Process all requests in single transaction
        List<CompletableFuture<ComplexTradeResult>> tradeFutures = requests.stream()
            .map(request -> processSingleTradeInBatch(request, batchCorrelationId,
                batchTime, connection))
            .toList();

        return CompletableFuture.allOf(tradeFutures.toArray(new CompletableFuture[0]))
            .thenApply(v -> tradeFutures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList())
            );

    }).toCompletionStage().toCompletableFuture();
}
```

### Connection Pool Optimization

**Pattern**: Optimize connection usage for high-throughput scenarios.

```java
@Configuration
public class HighThroughputBiTemporalConfig {

    @Bean
    public DatabaseService optimizedDatabaseService() {
        return DatabaseService.builder()
            .connectionString("jdbc:postgresql://localhost:5432/peegeeq")
            .username("peegeeq")
            .password("peegeeq")
            .maxPoolSize(50)           // Increased for high throughput
            .minPoolSize(10)           // Keep connections warm
            .connectionTimeout(5000)   // 5 second timeout
            .idleTimeout(300000)       // 5 minute idle timeout
            .maxLifetime(1800000)      // 30 minute max lifetime
            .leakDetectionThreshold(60000)  // 1 minute leak detection
            .build();
    }

    @Bean
    public EventStore<TradeEvent> optimizedTradeEventStore(DatabaseService databaseService) {
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory();
        return factory.createEventStore("trade_events", TradeEvent.class, databaseService);
    }
}
```

### Asynchronous Processing Patterns

**Pattern**: Non-blocking transaction processing with proper error handling.

```java
@Service
public class AsyncTransactionProcessor {

    private final Executor transactionExecutor =
        Executors.newFixedThreadPool(20, r -> {
            Thread t = new Thread(r, "transaction-processor");
            t.setDaemon(true);
            return t;
        });

    public CompletableFuture<ComplexTradeResult> processTradeAsync(
            ComplexTradeRequest request) {

        return CompletableFuture
            .supplyAsync(() -> executeComplexTrade(request), transactionExecutor)
            .thenCompose(Function.identity())  // Flatten nested CompletableFuture
            .exceptionally(throwable -> {
                log.error("Trade processing failed for {}", request.getEquityTradeId(), throwable);
                return new ComplexTradeResult(
                    "ERROR-" + UUID.randomUUID().toString().substring(0, 8),
                    request.getEquityTradeId(),
                    null,
                    null,
                    "FAILED",
                    "Processing failed: " + throwable.getMessage(),
                    Instant.now(),
                    Map.of("error", throwable.getClass().getSimpleName())
                );
            });
    }

    public CompletableFuture<List<ComplexTradeResult>> processTradesInParallel(
            List<ComplexTradeRequest> requests) {

        List<CompletableFuture<ComplexTradeResult>> futures = requests.stream()
            .map(this::processTradeAsync)
            .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList())
            );
    }
}
```

---

## Summary

**Key Takeaways**:

1. **Multiple Event Stores** - Separate stores for different domains with coordinated transactions
2. **Single Transaction** - Use `withTransaction()` for ACID guarantees across all stores
3. **Same Connection** - Pass connection to all operations for consistency
4. **Correlation IDs** - Link events across stores for complete audit trails
5. **Saga Pattern** - Compensating actions for complex workflows
6. **Cross-Store Queries** - Query by correlation ID or entity ID across all stores
7. **Event Correction** - Fix historical data while preserving complete audit trail
8. **Regulatory Compliance** - Generate compliant reports (MiFID II, CFTC, SOX)
9. **Performance Optimization** - Batch processing and connection pool tuning
10. **Audit Trail Reconstruction** - Complete transaction history for compliance

### When to Use Multi-Store Bi-Temporal Transactions

- ✅ Complex business workflows spanning multiple domains
- ✅ ACID guarantees required across different event types
- ✅ Regulatory compliance requiring complete audit trails
- ✅ Financial transactions with multiple asset classes
- ✅ Cross-system coordination with consistency requirements

### When NOT to Use Multi-Store Bi-Temporal Transactions

- ❌ Simple single-domain operations
- ❌ No need for cross-store consistency
- ❌ Performance is more important than consistency
- ❌ No regulatory audit requirements

### Reference Implementation

See `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/springboot-bitemporal-tx/` for complete working example.

---

## License

Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd

Licensed under the Apache License, Version 2.0
```
```


