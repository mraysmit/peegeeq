# Bi-Temporal Event Store: Advanced Patterns

**Purpose**: Advanced patterns for bi-temporal event stores including reactive integration, multi-store transactions, domain-specific queries, and financial services use cases

**Audience**: Developers implementing complex enterprise workflows, financial services applications, and reactive microservices

**Examples**:
- `springboot2bitemporal` - Reactive settlement processing
- `springboot-bitemporal-tx` - Multi-store trade lifecycle
- `CloudEventsJsonbQueryTest` - Domain-specific query patterns

---

## What This Guide Covers

This guide demonstrates advanced bi-temporal patterns organized into 4 parts:

**Part 1: Reactive Integration Patterns**
- Reactive adapter (CompletableFuture → Mono/Flux)
- Settlement processing with Spring WebFlux
- Event corrections with audit trail
- Error handling and backpressure control

**Part 2: Multi-Store Transaction Patterns**
- Multiple event stores in single transaction
- Transaction coordination using `withTransaction()`
- Saga pattern for compensating actions
- Cross-store queries with correlation IDs

**Part 3: Domain-Specific Query Patterns**
- Wrapping EventQuery with business language
- Async/non-blocking query methods
- Aggregate ID prefixing for efficiency
- Query composition patterns

**Part 4: Funds & Custody Use Cases**
- Late trade confirmations and NAV corrections
- Corporate actions (dividends, splits)
- Trade lifecycle management
- Reconciliation and regulatory reporting

**What This Guide Does NOT Cover**:
- Basic bi-temporal concepts (see BITEMPORAL_BASICS_GUIDE.md)
- Outbox pattern (see INTEGRATED_PATTERN_GUIDE.md)
- Consumer groups (see CONSUMER_GROUP_GUIDE.md)

---

# Part 1: Reactive Integration Patterns

## Overview

PeeGeeQ's bi-temporal event store returns `CompletableFuture` from its public API. For reactive applications using Project Reactor (Spring WebFlux), you need to convert to `Mono`/`Flux`.

### Key Concepts

- **Reactive Adapter** - Convert CompletableFuture to Mono/Flux
- **Non-Blocking** - Maintain reactive streams throughout
- **Backpressure** - Control flow of large event histories
- **Error Handling** - Reactive error recovery patterns

---

## Reactive Adapter Pattern

### Converting CompletableFuture to Mono/Flux

**Adapter Implementation**:
```java
@Component
public class ReactiveBiTemporalAdapter {

    public <T> Mono<BiTemporalEvent<T>> toMono(
            CompletableFuture<BiTemporalEvent<T>> future) {
        return Mono.fromFuture(future)
            .doOnError(error -> log.error("Error in bi-temporal operation", error));
    }

    public <T> Flux<BiTemporalEvent<T>> toFlux(
            CompletableFuture<List<BiTemporalEvent<T>>> future) {
        return Mono.fromFuture(future)
            .flatMapMany(Flux::fromIterable)
            .doOnError(error -> log.error("Error in bi-temporal query", error));
    }
}
```

### Using the Adapter

**Service Implementation**:
```java
@Service
public class SettlementService {

    private final EventStore<SettlementEvent> eventStore;
    private final ReactiveBiTemporalAdapter adapter;

    public Mono<BiTemporalEvent<SettlementEvent>> recordSettlement(
            String eventType, SettlementEvent event) {

        CompletableFuture<BiTemporalEvent<SettlementEvent>> future =
            eventStore.append(eventType, event, event.getEventTime());

        return adapter.toMono(future);
    }

    public Flux<BiTemporalEvent<SettlementEvent>> getHistory(String instructionId) {
        CompletableFuture<List<BiTemporalEvent<SettlementEvent>>> future =
            eventStore.query(EventQuery.all());

        return adapter.toFlux(future)
            .filter(e -> instructionId.equals(e.getPayload().getInstructionId()));
    }
}
```

---

## Reactive Query Patterns

### Get Current State

Get the most recent event for a settlement instruction:

```java
public Mono<SettlementEvent> getCurrentState(String instructionId) {
    CompletableFuture<List<BiTemporalEvent<SettlementEvent>>> future =
        eventStore.query(EventQuery.all());

    return adapter.toFlux(future)
        .filter(e -> instructionId.equals(e.getPayload().getInstructionId()))
        .sort((a, b) -> b.getValidTime().compareTo(a.getValidTime()))
        .next()
        .map(BiTemporalEvent::getPayload);
}
```

**Use Case**: Display current settlement status in operations dashboard

**Example**:
```
Events in database:
1. Submitted  - valid: 2025-10-07 09:00, transaction: 2025-10-07 09:01
2. Matched    - valid: 2025-10-07 10:30, transaction: 2025-10-07 10:31
3. Confirmed  - valid: 2025-10-08 14:00, transaction: 2025-10-08 14:05

Query: getCurrentState("SSI-12345")
Result: Confirmed (event 3)
```

### Get State As-Of Point in Time

Reconstruct what we knew about a settlement at a specific moment:

```java
public Mono<SettlementEvent> getStateAsOf(String instructionId, Instant asOfTime) {
    CompletableFuture<List<BiTemporalEvent<SettlementEvent>>> future =
        eventStore.query(EventQuery.all());

    return adapter.toFlux(future)
        .filter(e -> instructionId.equals(e.getPayload().getInstructionId()))
        .filter(e -> !e.getValidTime().isAfter(asOfTime))
        .filter(e -> !e.getTransactionTime().isAfter(asOfTime))
        .sort((a, b) -> b.getValidTime().compareTo(a.getValidTime()))
        .next()
        .map(BiTemporalEvent::getPayload);
}
```

**Use Case**: Regulatory inquiry - "What did you know about this settlement on 2025-10-08 at 15:00?"

**Example**:
```
Events in database:
1. Submitted  - valid: 2025-10-07 09:00, transaction: 2025-10-07 09:01
2. Matched    - valid: 2025-10-07 10:30, transaction: 2025-10-07 10:31
3. Confirmed  - valid: 2025-10-08 14:00, transaction: 2025-10-08 14:05
4. Corrected  - valid: 2025-10-07 09:00, transaction: 2025-10-09 10:00

Query: getStateAsOf("SSI-12345", "2025-10-08T15:00:00Z")
Result: Confirmed (event 3)
  - We knew about submission, matching, and confirmation
  - We did NOT know about correction yet (transaction time is 2025-10-09)

Query: getStateAsOf("SSI-12345", "2025-10-09T11:00:00Z")
Result: Corrected (event 4)
  - Now we know about the correction
  - Valid time shows it applies to original submission time
```

### Get Complete History

Get all events for a settlement instruction in chronological order:

```java
public Flux<BiTemporalEvent<SettlementEvent>> getCompleteHistory(String instructionId) {
    CompletableFuture<List<BiTemporalEvent<SettlementEvent>>> future =
        eventStore.query(EventQuery.all());

    return adapter.toFlux(future)
        .filter(e -> instructionId.equals(e.getPayload().getInstructionId()))
        .sort(Comparator.comparing(BiTemporalEvent::getValidTime));
}
```

**Use Case**: Audit trail report showing complete settlement lifecycle

**Example**:
```
Events in database:
1. Submitted  - valid: 2025-10-07 09:00, transaction: 2025-10-07 09:01
2. Matched    - valid: 2025-10-07 10:30, transaction: 2025-10-07 10:31
3. Confirmed  - valid: 2025-10-08 14:00, transaction: 2025-10-08 14:05
4. Corrected  - valid: 2025-10-07 09:00, transaction: 2025-10-09 10:00

Query: getCompleteHistory("SSI-12345")
Result (ordered by valid time):
- Submitted (09:00)
- Corrected (09:00)
- Matched (10:30)
- Confirmed (14:00)
```

### Find All Corrections

Find all correction events for audit purposes:

```java
public Flux<BiTemporalEvent<SettlementEvent>> getCorrections(String instructionId) {
    CompletableFuture<List<BiTemporalEvent<SettlementEvent>>> future =
        eventStore.query(EventQuery.all());

    return adapter.toFlux(future)
        .filter(e -> instructionId.equals(e.getPayload().getInstructionId()))
        .filter(e -> "instruction.settlement.corrected".equals(e.getEventType()))
        .sort(Comparator.comparing(BiTemporalEvent::getTransactionTime));
}
```

**Use Case**: Compliance report showing all data corrections made

**Example**:
```
Events in database:
1. Submitted  - valid: 2025-10-07 09:00, transaction: 2025-10-07 09:01
2. Corrected  - valid: 2025-10-07 09:00, transaction: 2025-10-09 10:00
3. Corrected  - valid: 2025-10-07 09:00, transaction: 2025-10-10 08:15

Query: getCorrections("SSI-12345")
Result (corrections ordered by transaction time):
- Corrected (2025-10-09 10:00)
- Corrected (2025-10-10 08:15)
```

---

## Event Correction Patterns

### When to Correct vs Append New Event

**Append New Event** - When business state changes:
- Settlement matched → confirmed
- Settlement confirmed → failed
- New information about current state

**Correct Existing Event** - When original data was wrong:
- Wrong counterparty recorded
- Wrong amount recorded
- Wrong settlement date
- Late-arriving information that changes past state

### Correction with Audit Trail

**Scenario**: Wrong counterparty recorded on submission

```java
// Original submission (2025-10-07 09:00)
SettlementEvent original = new SettlementEvent(
    "SSI-12345",                                    // instructionId
    "TRD-67890",                                    // tradeId
    "CUSTODIAN-XYZ",                                // counterparty - WRONG!
    new BigDecimal("1000000.00"),                   // amount
    "USD",                                          // currency
    LocalDate.of(2025, 10, 10),                     // settlementDate
    SettlementStatus.SUBMITTED,                     // status
    null,                                           // failureReason
    Instant.parse("2025-10-07T09:00:00Z")          // eventTime (valid time)
);

settlementService.recordSettlement("instruction.settlement.submitted", original)
    .subscribe();

// Discovered error on 2025-10-09 10:00
// Record correction with ORIGINAL valid time but CURRENT transaction time
SettlementEvent corrected = new SettlementEvent(
    "SSI-12345",                                    // instructionId
    "TRD-67890",                                    // tradeId
    "CUSTODIAN-ABC",                                // counterparty - CORRECT!
    new BigDecimal("1000000.00"),                   // amount
    "USD",                                          // currency
    LocalDate.of(2025, 10, 10),                     // settlementDate
    SettlementStatus.CORRECTED,                     // status
    null,                                           // failureReason
    Instant.parse("2025-10-07T09:00:00Z")          // eventTime - Original valid time!
);

settlementService.correctSettlement("SSI-12345", corrected)
    .subscribe();
```

**Result**:
- Original event preserved (transaction time: 2025-10-07 09:01)
- Correction event recorded (transaction time: 2025-10-09 10:00)
- Both events have same valid time (2025-10-07 09:00)
- Complete audit trail of what changed and when

---

## Reactive Error Handling

### Error Recovery

```java
public Mono<SettlementEvent> getCurrentStateWithFallback(String instructionId) {
    return getCurrentState(instructionId)
        .onErrorResume(error -> {
            log.error("Failed to get current state for {}", instructionId, error);
            return Mono.empty();
        })
        .doOnSuccess(state -> log.info("Retrieved state for {}: {}",
            instructionId, state.getStatus()));
}
```

### Backpressure Control

When processing large event histories, use reactive operators to control backpressure:

```java
public Flux<SettlementEvent> processLargeHistory(String instructionId) {
    return getCompleteHistory(instructionId)
        .map(BiTemporalEvent::getPayload)
        .buffer(100)  // Process in batches of 100
        .flatMap(batch -> processBatch(batch), 4);  // Max 4 concurrent batches
}
```

---

# Part 2: Multi-Store Transaction Patterns

## Overview

Complex business workflows often span multiple domains, each with its own event store. PeeGeeQ supports coordinating multiple event stores in a single database transaction using `withTransaction()`.

### Key Concepts

- **Multiple Event Stores** - Separate stores for different domains
- **Transaction Coordination** - Single database transaction across all stores
- **Correlation IDs** - Link related events across stores
- **ACID Guarantees** - All succeed or all fail together
- **Saga Pattern** - Compensating actions for complex workflows

---

## Multiple Event Stores

### Why Multiple Stores?

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

---

## Transaction Coordination

### The Pattern

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

## Complete Order Processing Example

**Service Implementation**:
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

## Best Practices

### 1. Reactive Patterns

**Use Mono for Single Results**:
```java
// ✅ CORRECT
public Mono<SettlementEvent> getCurrentState(String id) {
    return adapter.toFlux(eventStore.query(EventQuery.all()))
        .filter(e -> id.equals(e.getPayload().getId()))
        .next()
        .map(BiTemporalEvent::getPayload);
}

// ❌ WRONG - blocking
public SettlementEvent getCurrentState(String id) {
    return eventStore.query(EventQuery.all()).join()
        .stream()
        .filter(e -> id.equals(e.getPayload().getId()))
        .findFirst()
        .map(BiTemporalEvent::getPayload)
        .orElse(null);
}
```

**Handle Errors Reactively**:
```java
// ✅ CORRECT
return getCurrentState(id)
    .onErrorResume(error -> {
        log.error("Failed to get state", error);
        return Mono.empty();
    });

// ❌ WRONG - exception propagates
return getCurrentState(id);
```

### 2. Multi-Store Transaction Patterns

**Use Same Connection**:
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

**Use Correlation IDs**:
```java
// ✅ CORRECT
String correlationId = UUID.randomUUID().toString();
Map<String, String> metadata = Map.of("correlationId", correlationId);

// ❌ WRONG - no correlation
Map<String, String> metadata = Map.of();
```

**Compose Operations**:
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

**Use Same Valid Time**:
```java
// ✅ CORRECT
Instant validTime = Instant.now();
appendToStore1(event1, validTime, connection);
appendToStore2(event2, validTime, connection);

// ❌ WRONG - different times
appendToStore1(event1, Instant.now(), connection);
appendToStore2(event2, Instant.now(), connection);
```

### 3. Event Correction Patterns

**Valid Time Selection**:
```java
// ✅ CORRECT - use original valid time for corrections
SettlementEvent corrected = new SettlementEvent(
    original.getInstructionId(),
    original.getTradeId(),
    "CUSTODIAN-ABC",  // Corrected value
    original.getAmount(),
    original.getCurrency(),
    original.getSettlementDate(),
    SettlementStatus.CORRECTED,
    null,
    original.getEventTime()  // Original valid time!
);

// ❌ WRONG - new valid time
corrected.setEventTime(Instant.now());
```

**Never Modify Existing Events**:
```java
// ✅ CORRECT - append correction event
settlementService.recordSettlement("instruction.settlement.corrected", corrected);

// ❌ WRONG - modify existing event
existingEvent.setCounterparty("CUSTODIAN-ABC");
```

### 4. Query Optimization

**Use Specific Queries**:
```java
// ✅ CORRECT - filter by aggregate ID
eventStore.query(EventQuery.forAggregate(instructionId))

// ❌ WRONG - query all then filter
eventStore.query(EventQuery.all())
    .thenApply(events -> events.stream()
        .filter(e -> instructionId.equals(e.getPayload().getInstructionId()))
        .collect(Collectors.toList())
    );
```

**Control Backpressure**:
```java
// ✅ CORRECT - buffer and limit concurrency
return getCompleteHistory(id)
    .buffer(100)
    .flatMap(batch -> processBatch(batch), 4);

// ❌ WRONG - unbounded processing
return getCompleteHistory(id)
    .flatMap(event -> processEvent(event));
```

---

## Summary

### Part 1: Reactive Integration

**Key Takeaways**:
1. **Reactive Adapter** - Convert CompletableFuture to Mono/Flux
2. **Non-Blocking** - Maintain reactive streams throughout
3. **Error Handling** - Use `.onErrorResume()` for recovery
4. **Backpressure** - Control flow with `.buffer()` and `.flatMap()`
5. **Point-in-Time Queries** - Reconstruct state at any moment

**When to Use**:
- ✅ Spring WebFlux applications
- ✅ Reactive microservices
- ✅ High-throughput event processing
- ✅ Non-blocking I/O requirements

### Part 2: Multi-Store Transactions

**Key Takeaways**:
1. **Multiple Event Stores** - Separate stores for different domains
2. **Single Transaction** - Use `withTransaction()` for coordination
3. **Same Connection** - Pass connection to all operations
4. **Correlation IDs** - Link events across stores
5. **Saga Pattern** - Compensating actions for complex workflows
6. **Cross-Store Queries** - Query by correlation ID or entity ID
7. **ACID Guarantees** - All succeed or all fail together

**When to Use**:
- ✅ Complex workflows spanning multiple domains
- ✅ Need ACID guarantees across event stores
- ✅ Saga pattern for compensating transactions
- ✅ Complete audit trail across all domains

### When NOT to Use Advanced Patterns

- ❌ Simple single-domain workflows (use basic patterns)
- ❌ No need for reactive streams (use CompletableFuture directly)
- ❌ Single event store sufficient (no multi-store coordination needed)
- ❌ No compensating actions required (simple append-only)

---

---

# Part 3: Domain-Specific Query Patterns

## Overview

The domain-specific query pattern wraps PeeGeeQ's `EventQuery` API with methods that use business language and maintain async/non-blocking behavior.

**Benefits**:
1. **Domain Language** - Methods named after business concepts
2. **Type Safety** - Strongly typed return values
3. **Async/Non-Blocking** - Returns `CompletableFuture`
4. **Encapsulation** - Hides query complexity
5. **Testability** - Easy to mock for unit testing

---

## The Pattern

### ❌ Anti-Pattern: Blocking the Event Loop

```java
// DON'T DO THIS - blocks the event loop!
public List<BiTemporalEvent<TransactionEvent>> queryTransactionsByAccount(String accountId) {
    return eventStore.query(EventQuery.forAggregate(accountId)).join();  // ❌ BLOCKS!
}
```

### ✅ Correct Pattern: Async/Non-Blocking

```java
// DO THIS - maintains async chain
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> queryTransactionsByAccount(String accountId) {
    return eventStore.query(EventQuery.forAggregate(accountId));  // ✅ Non-blocking
}
```

---

## Example: TransactionService

### Basic Query Methods

```java
@Service
public class TransactionService {

    private final EventStore<TransactionEvent> eventStore;

    /**
     * Domain-specific query: Get all transactions for a specific account.
     * Wraps EventQuery.forAggregate() with domain language.
     */
    public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>>
            queryTransactionsByAccount(String accountId) {
        return eventStore.query(EventQuery.forAggregate(accountId));
    }

    /**
     * Domain-specific query: Get transactions by account and type.
     */
    public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>>
            queryTransactionsByAccountAndType(String accountId, String eventType) {
        return eventStore.query(EventQuery.forAggregateAndType(accountId, eventType));
    }
}
```

### Convenience Methods

Build higher-level methods on top of the basic queries:

```java
/**
 * Get only recorded (non-corrected) transactions for an account.
 */
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>>
        queryRecordedTransactions(String accountId) {
    return queryTransactionsByAccountAndType(accountId, "TransactionRecorded");
}

/**
 * Get only corrected transactions for an account.
 */
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>>
        queryCorrectedTransactions(String accountId) {
    return queryTransactionsByAccountAndType(accountId, "TransactionCorrected");
}
```

### Composing Async Operations

Use `.thenApply()` and `.thenCompose()` to chain operations:

```java
/**
 * Calculate account balance at a specific point in time.
 */
public CompletableFuture<BigDecimal> getAccountBalance(String accountId, Instant asOf) {
    return queryTransactionsByAccount(accountId)
        .thenApply(transactions -> {
            return transactions.stream()
                .filter(event -> !event.getValidTime().isAfter(asOf))
                .map(event -> {
                    TransactionEvent txn = event.getPayload();
                    return txn.getType() == TransactionType.CREDIT
                        ? txn.getAmount()
                        : txn.getAmount().negate();
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        });
}
```

---

## Query Efficiency: Aggregate ID Prefixing

### The Problem

PeeGeeQ uses a **shared table with JSONB serialization** for all event types. Querying without proper filtering causes:
- Cross-type deserialization attempts
- Performance degradation
- Warning log spam
- Scalability problems

### The Solution

**Use aggregate ID prefixing** to separate event streams:

```java
// ✅ CORRECT - Prefix aggregate IDs by domain
String accountAggregateId = "account:" + accountId;
String tradeAggregateId = "trade:" + tradeId;
String fundAggregateId = "fund:" + fundId;

// Query by aggregate ID (efficient)
eventStore.query(EventQuery.forAggregate(accountAggregateId))
```

**Benefits**:
- Separate event streams by domain
- Efficient querying (no cross-type deserialization)
- Clear domain boundaries
- Scalable as data grows

### Anti-Pattern: EventQuery.all()

```java
// ❌ WRONG - queries ALL events, attempts to deserialize everything
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> getAllTransactions() {
    return eventStore.query(EventQuery.all());
}

// ✅ CORRECT - query by aggregate or event type
public CompletableFuture<List<BiTemporalEvent<TransactionEvent>>> getAccountTransactions(String accountId) {
    return eventStore.query(EventQuery.forAggregate("account:" + accountId));
}
```

---

# Part 4: Funds & Custody Use Cases

## Domain Context

### Middle Office / Back Office Operations

**Middle Office** responsibilities:
- Trade confirmation and matching
- Position reconciliation
- Corporate actions processing
- NAV (Net Asset Value) calculation
- Performance attribution
- Risk management

**Back Office** responsibilities:
- Settlement processing
- Cash management
- Custody reconciliation
- Fee calculation and billing
- Regulatory reporting (AIFMD, MiFID II, EMIR)
- Tax lot accounting

### Why Bi-Temporal for Funds & Custody?

1. **Trade Date vs Settlement Date** - Natural bi-temporal dimension
2. **Late Trade Confirmations** - Trades confirmed after cut-off time
3. **Corporate Actions** - Backdated adjustments (dividends, splits, mergers)
4. **NAV Corrections** - Recalculate historical NAV with corrected prices
5. **Regulatory Reporting** - Prove what was reported and when
6. **Reconciliation Breaks** - Track when discrepancies were discovered vs when they occurred
7. **Audit Trail** - Complete history required by regulators

---

## Use Case 1: Late Trade Confirmation

### Scenario

**Timeline:**
- 10:30 - Trade executed (Valid Time)
- 18:00 - NAV cut-off
- 20:00 - Trade confirmed (Transaction Time) ⚠️ LATE
- 22:00 - NAV calculated (without trade)
- Next day 09:00 - NAV corrected

### Implementation

```java
public CompletableFuture<List<BiTemporalEvent<TradeEvent>>> getLateTradeConfirmations(
        String fundId, LocalDate tradingDay) {

    Instant dayStart = tradingDay.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant navCutoff = tradingDay.atTime(18, 0).toInstant(ZoneOffset.UTC);
    Instant dayEnd = tradingDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

    return eventStore.query(EventQuery.forAggregate("fund:" + fundId))
        .thenApply(events -> events.stream()
            // Valid time = during trading day
            .filter(e -> !e.getValidTime().isBefore(dayStart))
            .filter(e -> e.getValidTime().isBefore(dayEnd))
            // Transaction time = after NAV cutoff
            .filter(e -> e.getTransactionTime().isAfter(navCutoff))
            .collect(Collectors.toList())
        );
}
```

### Query Patterns

```java
// NAV as originally reported (for regulatory filing)
public CompletableFuture<BigDecimal> getNAVAsReported(String fundId, LocalDate asOfDate) {
    Instant reportTime = asOfDate.atTime(22, 0).toInstant(ZoneOffset.UTC);

    return eventStore.query(
        EventQuery.builder()
            .aggregateId("fund:" + fundId)
            .asOfTransactionTime(reportTime)
            .build()
    ).thenApply(events -> calculateNAV(events));
}

// NAV with late trades included (corrected)
public CompletableFuture<BigDecimal> getNAVCorrected(String fundId, LocalDate asOfDate) {
    Instant endOfDay = asOfDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

    return eventStore.query(
        EventQuery.builder()
            .aggregateId("fund:" + fundId)
            .asOfValidTime(endOfDay)
            .build()
    ).thenApply(events -> calculateNAV(events));
}
```

---

## Use Case 2: Corporate Actions

### Scenario: Dividend Payment

**Timeline:**
- 2025-10-01 - Ex-dividend date (Valid Time)
- 2025-10-15 - Payment date
- 2025-10-20 - We learn about dividend (Transaction Time)

### Implementation

```java
public CompletableFuture<BiTemporalEvent<CorporateActionEvent>> recordDividend(
        String securityId, LocalDate exDate, BigDecimal dividendPerShare) {

    CorporateActionEvent dividend = new CorporateActionEvent(
        securityId,
        CorporateActionType.DIVIDEND,
        dividendPerShare,
        exDate
    );

    // Valid time = ex-dividend date (when it actually happened)
    Instant validTime = exDate.atStartOfDay(ZoneOffset.UTC).toInstant();

    return eventStore.append("CorporateActionRecorded", dividend, validTime);
}
```

### Backdated NAV Recalculation

```java
public CompletableFuture<List<NAVCorrection>> recalculateNAVWithCorporateAction(
        String fundId, LocalDate fromDate, LocalDate toDate) {

    Instant start = fromDate.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant end = toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

    return eventStore.query(EventQuery.forAggregate("fund:" + fundId))
        .thenApply(events -> {
            List<NAVCorrection> corrections = new ArrayList<>();

            for (LocalDate date = fromDate; !date.isAfter(toDate); date = date.plusDays(1)) {
                Instant dayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

                // Calculate NAV as of this date
                BigDecimal originalNAV = calculateNAVAsOf(events, dayEnd);
                BigDecimal correctedNAV = calculateNAVWithCorporateActions(events, dayEnd);

                if (!originalNAV.equals(correctedNAV)) {
                    corrections.add(new NAVCorrection(date, originalNAV, correctedNAV));
                }
            }

            return corrections;
        });
}
```

---

## Use Case 3: Trade Lifecycle Management

### Trade Lifecycle Stages

**Typical Trade Lifecycle**:
1. **Execution** - Trade executed on exchange/OTC
2. **Confirmation** - Counterparty confirms trade details
3. **Affirmation** - Both parties agree on terms
4. **Allocation** - Allocate to client accounts (for block trades)
5. **Settlement** - Exchange of securities and cash
6. **Reconciliation** - Verify with custodian

### Implementation

```java
@Service
public class TradeLifecycleService {

    private final EventStore<TradeEvent> eventStore;

    // Step 1: Record trade execution
    public CompletableFuture<BiTemporalEvent<TradeEvent>> recordExecution(
            String tradeId, TradeDetails details, Instant tradeTime) {

        TradeEvent execution = new TradeEvent(
            tradeId,
            details.getSecurityId(),
            details.getQuantity(),
            details.getPrice(),
            TradeStatus.EXECUTED,
            tradeTime
        );

        return eventStore.append("TradeExecuted", execution, tradeTime);
    }

    // Step 2: Record confirmation
    public CompletableFuture<BiTemporalEvent<TradeEvent>> recordConfirmation(
            String tradeId, Instant tradeTime) {

        return getLatestTradeState(tradeId)
            .thenCompose(trade -> {
                TradeEvent confirmed = trade.toBuilder()
                    .status(TradeStatus.CONFIRMED)
                    .build();

                return eventStore.append("TradeConfirmed", confirmed, tradeTime);
            });
    }

    // Step 3: Record settlement
    public CompletableFuture<BiTemporalEvent<TradeEvent>> recordSettlement(
            String tradeId, Instant settlementDate) {

        return getLatestTradeState(tradeId)
            .thenCompose(trade -> {
                TradeEvent settled = trade.toBuilder()
                    .status(TradeStatus.SETTLED)
                    .build();

                return eventStore.append("TradeSettled", settled, settlementDate);
            });
    }
}
```

---

## Summary

### Part 1: Reactive Integration

**Key Takeaways**:
1. **Reactive Adapter** - Convert CompletableFuture to Mono/Flux
2. **Non-Blocking** - Maintain reactive streams throughout
3. **Error Handling** - Use `.onErrorResume()` for recovery
4. **Backpressure** - Control flow with `.buffer()` and `.flatMap()`
5. **Point-in-Time Queries** - Reconstruct state at any moment

**When to Use**:
- Spring WebFlux applications
- Reactive microservices
- High-throughput event processing
- Non-blocking I/O requirements

### Part 2: Multi-Store Transactions

**Key Takeaways**:
1. **Multiple Event Stores** - Separate stores for different domains
2. **Single Transaction** - Use `withTransaction()` for coordination
3. **Same Connection** - Pass connection to all operations
4. **Correlation IDs** - Link events across stores
5. **Saga Pattern** - Compensating actions for complex workflows
6. **ACID Guarantees** - All succeed or all fail together

**When to Use**:
- Complex workflows spanning multiple domains
- Need ACID guarantees across event stores
- Saga pattern for compensating transactions
- Complete audit trail across all domains

### Part 3: Domain-Specific Query Patterns

**Key Takeaways**:
1. **Domain Language** - Wrap EventQuery with business methods
2. **Async/Non-Blocking** - Maintain CompletableFuture chains
3. **Aggregate ID Prefixing** - Use `"domain:"` prefixes for efficiency
4. **Query Specificity** - Avoid `EventQuery.all()`
5. **Composition** - Chain operations with `.thenApply()` and `.thenCompose()`

**When to Use**:
- Building domain services
- Need business-language APIs
- Multiple event types in same store
- Performance-critical queries

### Part 4: Funds & Custody Use Cases

**Key Takeaways**:
1. **Late Trade Confirmations** - Valid Time ≠ Transaction Time
2. **Corporate Actions** - Backdated adjustments (dividends, splits)
3. **NAV Corrections** - Recalculate with new information
4. **Trade Lifecycle** - Execution → Confirmation → Settlement
5. **Regulatory Reporting** - Prove what was reported and when

**When to Use**:
- Middle office / back office operations
- Fund administration
- Custody reconciliation
- Regulatory compliance (AIFMD, MiFID II, EMIR)

---

## Related Guides

- **BITEMPORAL_BASICS_GUIDE.md** - Core bi-temporal concepts and basic patterns
- **INTEGRATED_PATTERN_GUIDE.md** - Combining outbox + bi-temporal patterns
- **CONSUMER_GROUP_GUIDE.md** - Message consumption patterns
- **DLQ_RETRY_GUIDE.md** - Error handling and retry patterns

---

## Reference Implementations

- **springboot2bitemporal** - Reactive settlement processing example
  - Location: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/springboot2bitemporal/`
  - Demonstrates: Reactive adapter, settlement lifecycle, corrections

- **springboot-bitemporal-tx** - Multi-store trade lifecycle example
  - Location: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/springbootbitemporaltx/`
  - Demonstrates: Multi-store coordination, saga pattern, correlation IDs

- **TransactionServiceTest** - Domain-specific query pattern examples
  - Location: `peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/bitemporal/`
  - Demonstrates: Query methods, async composition, aggregate ID prefixing

- **CloudEventsJsonbQueryTest** - JSONB query patterns with CloudEvents
  - Location: `peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/bitemporal/`
  - Demonstrates: Trade lifecycle, JSONB queries, bi-temporal + JSONB combination

---

## License

Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd

Licensed under the Apache License, Version 2.0

