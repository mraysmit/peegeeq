# Integrated Outbox + Bi-Temporal Pattern Guide

**Example**: `springboot-integrated`  
**Use Case**: Trade capture with immediate processing and complete audit trail  
**Focus**: Single transaction coordination, dual-purpose events, consistency guarantees

---

## What This Guide Covers

This guide demonstrates how to combine PeeGeeQ's outbox pattern and bi-temporal event store in a single transaction. This enables both immediate real-time processing (via outbox consumers) and complete historical audit trail (via bi-temporal queries).

**Key Patterns**:
- Single transaction coordination across three operations
- Database save + outbox send + event store append
- Dual-purpose events (real-time + historical)
- Complete ACID guarantees
- Consistency across all storage layers

**What This Guide Does NOT Cover**:
- Basic outbox pattern (see PRIORITY_FILTERING_GUIDE.md)
- Basic bi-temporal pattern (see REACTIVE_BITEMPORAL_GUIDE.md)
- Consumer groups (see CONSUMER_GROUP_GUIDE.md)
- Retry and DLQ (see DLQ_RETRY_GUIDE.md)

---

## Core Concepts

### The Integration Challenge

**Problem**: How to ensure consistency across three operations?

1. **Database Save** - Store order in `orders` table
2. **Outbox Send** - Send event to `outbox` table for immediate processing
3. **Event Store Append** - Append event to bi-temporal event store for audit trail

**Requirements**:
- All three succeed together OR all three fail together
- No partial updates
- No lost events
- Complete consistency

**Solution**: Single database transaction using `ConnectionProvider.withTransaction()`.

### Single Transaction Pattern

**Key Principle**: Pass the SAME `SqlConnection` to all three operations.

```java
connectionProvider.withTransaction("client-id", connection -> {
    // 1. Save to database (connection)
    // 2. Send to outbox (connection)
    // 3. Append to event store (connection)
    // All use SAME connection = SAME transaction
});
```

**How It Works**:
1. `withTransaction()` begins database transaction
2. Creates `SqlConnection` for this transaction
3. Passes connection to your lambda
4. You pass connection to all operations
5. All operations use same transaction
6. If any fails → rollback ALL
7. If all succeed → commit ALL

### Dual-Purpose Events

**Same Event, Two Purposes**:

**Purpose 1: Real-Time Processing** (Outbox)
- Immediate downstream processing
- Settlement instructions sent to custodian
- Position updates sent to risk system
- Confirmations sent to trading desk

**Purpose 2: Historical Queries** (Bi-Temporal)
- Complete audit trail
- Point-in-time reconstruction
- Regulatory reporting
- Historical analysis

**Benefits**:
- Single event definition
- Consistent data across systems
- No event duplication
- Simplified architecture

---

## Implementation

### Configuration

**Bean Setup**:
```java
@Configuration
public class IntegratedConfig {
    
    @Bean
    public DatabaseService databaseService(PeeGeeQManager manager) {
        return new PgDatabaseService(manager);
    }
    
    @Bean
    public QueueFactory queueFactory(DatabaseService databaseService) {
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        return provider.createFactory("outbox", databaseService);
    }
    
    @Bean
    public OutboxProducer<OrderEvent> orderEventProducer(QueueFactory factory) {
        return (OutboxProducer<OrderEvent>) factory.createProducer("order-events");
    }
    
    @Bean
    public EventStore<OrderEvent> orderEventStore(DatabaseService databaseService) {
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory();
        return factory.createEventStore("order_events", OrderEvent.class, databaseService);
    }
}
```

### Service Implementation

**Complete Pattern**:
```java
@Service
public class OrderService {
    
    private final DatabaseService databaseService;
    private final OrderRepository orderRepository;
    private final OutboxProducer<OrderEvent> orderEventProducer;
    private final EventStore<OrderEvent> orderEventStore;
    
    public CompletableFuture<String> createOrder(CreateOrderRequest request) {
        String orderId = UUID.randomUUID().toString();
        Instant validTime = Instant.now();
        
        // Create domain objects
        Order order = new Order(
            orderId,
            request.getCustomerId(),
            request.getAmount(),
            OrderStatus.CREATED,
            request.getDescription(),
            Instant.now()
        );
        
        OrderEvent event = new OrderEvent(
            orderId,
            request.getCustomerId(),
            request.getAmount(),
            OrderStatus.CREATED,
            request.getDescription(),
            validTime
        );
        
        // Get connection provider
        ConnectionProvider cp = databaseService.getConnectionProvider();
        
        // Execute all operations in SINGLE transaction
        return cp.withTransaction("peegeeq-main", connection -> {
            
            // Step 1: Save order to database
            return orderRepository.save(order, connection)
                
                // Step 2: Send to outbox (for immediate processing)
                .compose(v -> Future.fromCompletionStage(
                    orderEventProducer.sendInTransaction(event, connection)
                ))
                
                // Step 3: Append to bi-temporal event store (for historical queries)
                .compose(v -> Future.fromCompletionStage(
                    orderEventStore.appendInTransaction(
                        "OrderCreated",        // Event type
                        event,                 // Event payload
                        validTime,             // Valid time
                        connection             // SAME connection
                    )
                ))
                
                // Return order ID
                .map(v -> orderId);
                
        }).toCompletionStage().toCompletableFuture();
    }
}
```

**Key Points**:
1. **Single Transaction** - `withTransaction()` creates one transaction
2. **Same Connection** - Pass `connection` to all three operations
3. **Composition** - Use `.compose()` to chain operations
4. **ACID Guarantees** - All succeed or all fail together

### Repository Implementation

**Database Save**:
```java
@Repository
public class OrderRepository {
    
    public Future<Void> save(Order order, SqlConnection connection) {
        String sql = "INSERT INTO orders (id, customer_id, amount, status, description, created_at) " +
                    "VALUES ($1, $2, $3, $4, $5, $6)";
        
        return connection.preparedQuery(sql)
            .execute(Tuple.of(
                order.getId(),
                order.getCustomerId(),
                order.getAmount(),
                order.getStatus().name(),
                order.getDescription(),
                order.getCreatedAt()
            ))
            .map(result -> null);
    }
}
```

**Important**: Accept `SqlConnection` parameter, don't create your own connection.

### Outbox Producer

**Send in Transaction**:
```java
// ✅ CORRECT - use sendInTransaction()
orderEventProducer.sendInTransaction(event, connection)

// ❌ WRONG - creates separate transaction
orderEventProducer.send(event)
```

**API**:
```java
public interface OutboxProducer<T> {
    // Separate transaction (don't use for integrated pattern)
    CompletableFuture<Void> send(T payload);
    
    // Same transaction (use this!)
    CompletableFuture<Void> sendInTransaction(T payload, SqlConnection connection);
}
```

### Event Store

**Append in Transaction**:
```java
// ✅ CORRECT - use appendInTransaction()
orderEventStore.appendInTransaction(
    "OrderCreated",
    event,
    validTime,
    connection  // SAME connection
)

// ❌ WRONG - creates separate transaction
orderEventStore.append("OrderCreated", event, validTime)
```

**API**:
```java
public interface EventStore<T> {
    // Separate transaction (don't use for integrated pattern)
    CompletableFuture<BiTemporalEvent<T>> append(
        String eventType, T payload, Instant validTime);
    
    // Same transaction (use this!)
    CompletableFuture<BiTemporalEvent<T>> appendInTransaction(
        String eventType, T payload, Instant validTime, SqlConnection connection);
}
```

---

## Transaction Flow

### Success Path

**Sequence**:
```
1. BEGIN TRANSACTION
2. INSERT INTO orders (...)
3. INSERT INTO outbox (...)
4. INSERT INTO order_events (...)
5. COMMIT TRANSACTION
```

**Result**:
- Order saved in database ✅
- Event in outbox (consumers will process) ✅
- Event in bi-temporal store (audit trail) ✅
- All three committed together ✅

### Failure Path

**Scenario**: Event store append fails.

**Sequence**:
```
1. BEGIN TRANSACTION
2. INSERT INTO orders (...)        ✅ Success
3. INSERT INTO outbox (...)        ✅ Success
4. INSERT INTO order_events (...)  ❌ FAILS
5. ROLLBACK TRANSACTION
```

**Result**:
- Order NOT saved (rolled back) ✅
- Event NOT in outbox (rolled back) ✅
- Event NOT in event store (failed) ✅
- Complete consistency maintained ✅

**No Partial Updates**: Either all three succeed or none succeed.

---

## Querying Patterns

### Database Queries

**Get Order by ID**:
```java
public CompletableFuture<Order> getOrder(String orderId) {
    return databaseService.getConnectionProvider()
        .withTransaction("peegeeq-main", connection -> {
            String sql = "SELECT * FROM orders WHERE id = $1";
            
            return connection.preparedQuery(sql)
                .execute(Tuple.of(orderId))
                .map(rows -> {
                    if (rows.size() == 0) {
                        throw new RuntimeException("Order not found");
                    }
                    Row row = rows.iterator().next();
                    return new Order(
                        row.getString("id"),
                        row.getString("customer_id"),
                        row.getBigDecimal("amount"),
                        OrderStatus.valueOf(row.getString("status")),
                        row.getString("description"),
                        row.getOffsetDateTime("created_at").toInstant()
                    );
                });
        })
        .toCompletionStage()
        .toCompletableFuture();
}
```

### Bi-Temporal Queries

**Get Order History**:
```java
public CompletableFuture<List<BiTemporalEvent<OrderEvent>>> getOrderHistory(String orderId) {
    return orderEventStore.query(EventQuery.all())
        .thenApply(events -> events.stream()
            .filter(event -> orderId.equals(event.getPayload().getOrderId()))
            .collect(Collectors.toList())
        );
}
```

**Point-in-Time Query**:
```java
public CompletableFuture<List<BiTemporalEvent<OrderEvent>>> getOrdersAsOfTime(Instant validTime) {
    return orderEventStore.query(EventQuery.asOfValidTime(validTime));
}
```

**Get Customer Orders**:
```java
public CompletableFuture<List<BiTemporalEvent<OrderEvent>>> getCustomerOrders(String customerId) {
    return orderEventStore.query(EventQuery.all())
        .thenApply(events -> events.stream()
            .filter(event -> customerId.equals(event.getPayload().getCustomerId()))
            .collect(Collectors.toList())
        );
}
```

---

## Event Definition

### Shared Event Class

**Single Event for Both Purposes**:
```java
public class OrderEvent {
    
    private final String orderId;
    private final String customerId;
    private final BigDecimal amount;
    private final OrderStatus status;
    private final String description;
    private final Instant validTime;
    
    @JsonCreator
    public OrderEvent(
            @JsonProperty("orderId") String orderId,
            @JsonProperty("customerId") String customerId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("status") OrderStatus status,
            @JsonProperty("description") String description,
            @JsonProperty("validTime") Instant validTime) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount;
        this.status = status;
        this.description = description;
        this.validTime = validTime;
    }
    
    // Getters...
    
    public enum OrderStatus {
        CREATED, CONFIRMED, CANCELLED, COMPLETED
    }
}
```

**Used By**:
- Outbox producer (sends to consumers)
- Event store (stores for audit trail)
- Consumers (process from outbox)
- Query services (retrieve from event store)

---

## Database Schema

### Orders Table

```sql
CREATE TABLE IF NOT EXISTS orders (
    id VARCHAR(255) PRIMARY KEY,
    customer_id VARCHAR(255) NOT NULL,
    amount DECIMAL(19, 4) NOT NULL,
    status VARCHAR(50) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_orders_customer_id ON orders(customer_id);
CREATE INDEX idx_orders_created_at ON orders(created_at);
```

### Outbox Table

```sql
CREATE TABLE IF NOT EXISTS outbox (
    id BIGSERIAL PRIMARY KEY,
    queue_name VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB,
    retry_count INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_outbox_queue_name ON outbox(queue_name);
CREATE INDEX idx_outbox_created_at ON outbox(created_at);
```

### Bi-Temporal Event Store Table

```sql
CREATE TABLE IF NOT EXISTS order_events (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    valid_time TIMESTAMPTZ NOT NULL,
    transaction_time TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_order_events_event_type ON order_events(event_type);
CREATE INDEX idx_order_events_valid_time ON order_events(valid_time);
CREATE INDEX idx_order_events_transaction_time ON order_events(transaction_time);
```

---

## Trade Capture Use Case

### Scenario

**Front Office Trade Capture**:
- Trader executes trade
- Trade captured in system
- Need immediate downstream processing:
  - Send settlement instruction to custodian
  - Update position in risk system
  - Send confirmation to trading desk
- Need complete audit trail:
  - Regulatory reporting (MiFID II, EMIR)
  - Historical position reconstruction
  - Trade lifecycle tracking

### Implementation

**Trade Capture**:
```java
public CompletableFuture<String> captureTradeOrder(TradeRequest request) {
    String tradeId = UUID.randomUUID().toString();
    Instant tradeTime = request.getTradeTime();
    
    // Create trade order
    TradeOrder trade = new TradeOrder(
        tradeId,
        request.getInstrument(),
        request.getQuantity(),
        request.getPrice(),
        TradeStatus.CAPTURED,
        tradeTime
    );
    
    // Create trade event
    TradeEvent event = new TradeEvent(
        tradeId,
        request.getInstrument(),
        request.getQuantity(),
        request.getPrice(),
        TradeStatus.CAPTURED,
        tradeTime
    );
    
    // Single transaction: database + outbox + event store
    return connectionProvider.withTransaction("peegeeq-main", connection -> {
        
        // 1. Save trade to database
        return tradeRepository.save(trade, connection)
            
            // 2. Send to outbox (immediate processing)
            .compose(v -> Future.fromCompletionStage(
                tradeEventProducer.sendInTransaction(event, connection)
            ))
            
            // 3. Append to event store (audit trail)
            .compose(v -> Future.fromCompletionStage(
                tradeEventStore.appendInTransaction(
                    "TradeCaptured",
                    event,
                    tradeTime,
                    connection
                )
            ))
            
            .map(v -> tradeId);
    }).toCompletionStage().toCompletableFuture();
}
```

**Downstream Processing** (Outbox Consumers):
```java
// Consumer 1: Settlement Instruction
@Service
public class SettlementInstructionConsumer {
    
    @EventListener(ApplicationReadyEvent.class)
    public void startConsuming() {
        tradeEventConsumer.subscribe(this::sendSettlementInstruction);
    }
    
    private CompletableFuture<Void> sendSettlementInstruction(Message<TradeEvent> message) {
        TradeEvent trade = message.getPayload();
        
        // Send to custodian
        return custodianClient.sendSettlementInstruction(trade)
            .thenApply(v -> null);
    }
}

// Consumer 2: Position Update
@Service
public class PositionUpdateConsumer {
    
    @EventListener(ApplicationReadyEvent.class)
    public void startConsuming() {
        tradeEventConsumer.subscribe(this::updatePosition);
    }
    
    private CompletableFuture<Void> updatePosition(Message<TradeEvent> message) {
        TradeEvent trade = message.getPayload();
        
        // Update risk system
        return riskSystem.updatePosition(trade)
            .thenApply(v -> null);
    }
}
```

**Historical Queries** (Bi-Temporal):
```java
// Regulatory reporting
public CompletableFuture<List<BiTemporalEvent<TradeEvent>>> getTradesForRegulatory(
        LocalDate reportDate) {
    Instant startOfDay = reportDate.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant endOfDay = reportDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    
    return tradeEventStore.query(EventQuery.all())
        .thenApply(events -> events.stream()
            .filter(e -> e.getValidTime().isAfter(startOfDay) && 
                        e.getValidTime().isBefore(endOfDay))
            .collect(Collectors.toList())
        );
}

// Position reconstruction
public CompletableFuture<BigDecimal> getPositionAsOf(String instrument, Instant pointInTime) {
    return tradeEventStore.query(EventQuery.asOfValidTime(pointInTime))
        .thenApply(events -> events.stream()
            .filter(e -> instrument.equals(e.getPayload().getInstrument()))
            .map(e -> e.getPayload().getQuantity())
            .reduce(BigDecimal.ZERO, BigDecimal::add)
        );
}
```

---

## Best Practices

### 1. Always Use Same Connection

**Why**: Ensures all operations in same transaction.

```java
// ✅ CORRECT
connectionProvider.withTransaction("client-id", connection -> {
    return save(connection)
        .compose(v -> sendInTransaction(event, connection))
        .compose(v -> appendInTransaction(event, validTime, connection));
});

// ❌ WRONG - separate transactions
save();
send(event);
append(event, validTime);
```

### 2. Use InTransaction Methods

**Why**: Participates in existing transaction.

```java
// ✅ CORRECT
producer.sendInTransaction(event, connection)
eventStore.appendInTransaction(eventType, event, validTime, connection)

// ❌ WRONG - creates new transaction
producer.send(event)
eventStore.append(eventType, event, validTime)
```

### 3. Compose Operations

**Why**: Chains operations in same transaction.

```java
// ✅ CORRECT - composed
return save(connection)
    .compose(v -> send(event, connection))
    .compose(v -> append(event, validTime, connection));

// ❌ WRONG - parallel (race conditions)
CompletableFuture.allOf(
    save(connection),
    send(event, connection),
    append(event, validTime, connection)
);
```

### 4. Handle Errors Properly

**Why**: Ensures rollback on any failure.

```java
return connectionProvider.withTransaction("client-id", connection -> {
    return save(connection)
        .compose(v -> send(event, connection))
        .compose(v -> append(event, validTime, connection));
})
.toCompletionStage()
.toCompletableFuture()
.whenComplete((result, error) -> {
    if (error != null) {
        log.error("Transaction failed - ALL operations rolled back", error);
    } else {
        log.info("Transaction succeeded - ALL operations committed");
    }
});
```

### 5. Use Shared Event Definition

**Why**: Consistency across outbox and event store.

```java
// ✅ CORRECT - same event class
OrderEvent event = new OrderEvent(...);
producer.sendInTransaction(event, connection);
eventStore.appendInTransaction("OrderCreated", event, validTime, connection);

// ❌ WRONG - different event classes
OutboxEvent outboxEvent = new OutboxEvent(...);
EventStoreEvent storeEvent = new EventStoreEvent(...);
```

---

## Summary

**Key Takeaways**:

1. **Single Transaction** - Use `withTransaction()` for all three operations
2. **Same Connection** - Pass `SqlConnection` to all operations
3. **InTransaction Methods** - Use `sendInTransaction()` and `appendInTransaction()`
4. **Composition** - Chain operations with `.compose()`
5. **ACID Guarantees** - All succeed or all fail together
6. **Dual-Purpose Events** - Same event for real-time and historical
7. **Complete Consistency** - No partial updates across storage layers

**Related Guides**:
- **CONSUMER_GROUP_GUIDE.md** - Processing outbox events
- **REACTIVE_BITEMPORAL_GUIDE.md** - Bi-temporal queries and corrections
- **DLQ_RETRY_GUIDE.md** - Handling failures in consumers
- **PRIORITY_FILTERING_GUIDE.md** - Priority-based message routing

