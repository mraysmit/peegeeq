# PeeGeeQ Bi-Temporal Event Store

The PeeGeeQ Bi-Temporal Event Store provides append-only, bi-temporal event storage with real-time processing capabilities. This module extends PeeGeeQ's PostgreSQL-based message queue system with sophisticated temporal event sourcing features.

## Features

### Core Capabilities
- **Append-only**: Events are never deleted, only new versions added
- **Bi-temporal**: Track both when events happened (valid time) and when they were recorded (transaction time)
- **Real-time**: Immediate processing via PeeGeeQ's LISTEN/NOTIFY mechanism
- **Historical**: Query any point-in-time view of your data
- **Type safety**: Strongly typed events with JSON storage flexibility

### Advanced Features
- **Event Corrections**: Support for correcting historical events while maintaining audit trail
- **Versioning**: Automatic version tracking for event evolution
- **Temporal Queries**: Rich query capabilities across both time dimensions
- **Real-time Subscriptions**: Subscribe to event streams with wildcard pattern matching
- **Transaction Participation**: Events can participate in existing database transactions
- **Reactive API**: Full Vert.x 5.x Future-based reactive methods
- **Batch Operations**: High-throughput batch append for maximum performance
- **Statistics**: Comprehensive metrics and monitoring

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>dev.mars</groupId>
    <artifactId>peegeeq-bitemporal</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### 2. Create Event Store

```java
// Initialize PeeGeeQ with configuration
PeeGeeQManager manager = new PeeGeeQManager(
    new PeeGeeQConfiguration("development"),
    new SimpleMeterRegistry()
);
manager.start();

// Create event store factory
BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager);

// Create event store for your event type with explicit table name
EventStore<OrderEvent> eventStore = factory.createEventStore(OrderEvent.class, "order_events");

// Or use Map for flexible JSON payloads
@SuppressWarnings("unchecked")
Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
EventStore<Map<String, Object>> flexibleStore = factory.createEventStore(mapClass, "flexible_events");
```

### 3. Append Events

```java
// Create an event payload
OrderEvent order = new OrderEvent("ORDER-001", "CUST-123", new BigDecimal("99.99"), "CREATED");

// Append with valid time (when it actually happened)
Instant validTime = Instant.now().minus(1, ChronoUnit.HOURS);
BiTemporalEvent<OrderEvent> event = eventStore.append(
    "OrderCreated",
    order,
    validTime,
    Map.of("source", "web", "region", "US"),
    "correlation-123",
    "ORDER-001"
).join();

// Or use the reactive API with Vert.x Future
Future<BiTemporalEvent<OrderEvent>> futureEvent = eventStore.appendReactive(
    "OrderCreated", order, validTime
);
```

### 4. Query Events

```java
// Query all events
List<BiTemporalEvent<OrderEvent>> allEvents = eventStore.query(EventQuery.all()).join();

// Query by event type
List<BiTemporalEvent<OrderEvent>> orderEvents = eventStore.query(
    EventQuery.forEventType("OrderCreated")
).join();

// Query by aggregate
List<BiTemporalEvent<OrderEvent>> order1Events = eventStore.query(
    EventQuery.forAggregate("ORDER-001")
).join();

// Query by aggregate and type (common pattern)
List<BiTemporalEvent<OrderEvent>> specificEvents = eventStore.query(
    EventQuery.forAggregateAndType("ORDER-001", "OrderCreated")
).join();

// Temporal range query
List<BiTemporalEvent<OrderEvent>> recentEvents = eventStore.query(
    EventQuery.builder()
        .validTimeRange(TemporalRange.from(Instant.now().minus(24, ChronoUnit.HOURS)))
        .sortOrder(EventQuery.SortOrder.VALID_TIME_DESC)
        .limit(100)
        .build()
).join();

// Reactive query with Vert.x Future
Future<List<BiTemporalEvent<OrderEvent>>> futureEvents = eventStore.queryReactive(
    EventQuery.forEventType("OrderCreated")
);
```

### 5. Event Corrections

```java
// Append a correction for a previous event
OrderEvent correctedOrder = new OrderEvent("ORDER-001", "CUST-123", new BigDecimal("89.99"), "CREATED");

BiTemporalEvent<OrderEvent> correction = eventStore.appendCorrection(
    originalEvent.getEventId(),
    "OrderCreated",
    correctedOrder,
    validTime,
    "Price correction due to discount"
).join();

// Get all versions of an event
List<BiTemporalEvent<OrderEvent>> versions = eventStore.getAllVersions(originalEvent.getEventId()).join();
```

### 6. Point-in-Time Queries

```java
// Get event as it existed at a specific transaction time
Instant pointInTime = Instant.now().minus(1, ChronoUnit.HOURS);
BiTemporalEvent<OrderEvent> historicalEvent = eventStore.getAsOfTransactionTime(
    eventId,
    pointInTime
).join();

// Query events as they were valid at a specific time
List<BiTemporalEvent<OrderEvent>> historicalView = eventStore.query(
    EventQuery.asOfValidTime(pointInTime)
).join();

// Query events as of a specific transaction time
List<BiTemporalEvent<OrderEvent>> transactionView = eventStore.query(
    EventQuery.asOfTransactionTime(pointInTime)
).join();
```

### 7. Real-time Subscriptions

```java
// Subscribe to all events
eventStore.subscribe(null, event -> {
    System.out.println("New event: " + event.getEventType());
    return CompletableFuture.completedFuture(null);
}).join();

// Subscribe to specific event type
eventStore.subscribe("OrderCreated", event -> {
    System.out.println("New order: " + event.getPayload());
    return CompletableFuture.completedFuture(null);
}).join();

// Subscribe to events for specific aggregate
eventStore.subscribe("OrderCreated", "ORDER-001", event -> {
    System.out.println("Order 001 updated: " + event.getPayload());
    return CompletableFuture.completedFuture(null);
}).join();

// Wildcard subscriptions (pattern matching)
eventStore.subscribe("order.*", event -> {  // Matches order.created, order.shipped, etc.
    System.out.println("Order event: " + event.getEventType());
    return CompletableFuture.completedFuture(null);
}).join();

eventStore.subscribe("*.created", event -> {  // Matches order.created, payment.created, etc.
    System.out.println("Created event: " + event.getEventType());
    return CompletableFuture.completedFuture(null);
}).join();

// Reactive subscription with Vert.x Future
Future<Void> subscription = eventStore.subscribeReactive("OrderCreated", event -> {
    System.out.println("Reactive: " + event.getEventType());
    return CompletableFuture.completedFuture(null);
});
```

### 8. Transaction Participation

Events can participate in existing database transactions for ACID guarantees:

```java
// Get a connection from the pool and start a transaction
pool.getConnection().compose(conn ->
    conn.begin().compose(tx -> {
        // Business operation
        return conn.preparedQuery("UPDATE orders SET status = $1 WHERE id = $2")
            .execute(Tuple.of("CONFIRMED", orderId))
            .compose(v -> {
                // Append event in the same transaction
                return eventStore.appendInTransaction(
                    "OrderConfirmed",
                    new OrderEvent(orderId, "CONFIRMED"),
                    Instant.now(),
                    Map.of("source", "api"),
                    correlationId,
                    orderId,
                    conn  // Pass the connection with active transaction
                );
            })
            .compose(event -> tx.commit().map(event))
            .eventually(() -> conn.close());
    })
);
```

### 9. Batch Operations

For high-throughput scenarios, use batch append:

```java
import dev.mars.peegeeq.bitemporal.PgBiTemporalEventStore.BatchEventData;

// Prepare batch data
List<BatchEventData<OrderEvent>> events = List.of(
    new BatchEventData<>("OrderCreated", order1, Instant.now(), Map.of(), null, "ORDER-001"),
    new BatchEventData<>("OrderCreated", order2, Instant.now(), Map.of(), null, "ORDER-002"),
    new BatchEventData<>("OrderCreated", order3, Instant.now(), Map.of(), null, "ORDER-003")
);

// Batch append for maximum throughput (PgBiTemporalEventStore specific)
PgBiTemporalEventStore<OrderEvent> pgStore = (PgBiTemporalEventStore<OrderEvent>) eventStore;
List<BiTemporalEvent<OrderEvent>> results = pgStore.appendBatch(events).join();
```

## Database Schema

The bi-temporal event store uses a dedicated table with the following structure:

```sql
CREATE TABLE bitemporal_event_log (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL,

    -- Bi-temporal dimensions
    valid_time TIMESTAMP WITH TIME ZONE NOT NULL,
    transaction_time TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,

    -- Event data
    payload JSONB NOT NULL,
    headers JSONB DEFAULT '{}',

    -- Versioning and corrections
    version BIGINT DEFAULT 1 NOT NULL,
    previous_version_id VARCHAR(255),
    is_correction BOOLEAN DEFAULT FALSE NOT NULL,
    correction_reason TEXT,

    -- Grouping and correlation
    correlation_id VARCHAR(255),
    aggregate_id VARCHAR(255),

    -- Metadata
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL
);

-- Recommended indexes for optimal query performance
CREATE INDEX idx_event_type ON bitemporal_event_log(event_type);
CREATE INDEX idx_aggregate_id ON bitemporal_event_log(aggregate_id);
CREATE INDEX idx_correlation_id ON bitemporal_event_log(correlation_id);
CREATE INDEX idx_valid_time ON bitemporal_event_log(valid_time);
CREATE INDEX idx_transaction_time ON bitemporal_event_log(transaction_time);

-- Trigger for real-time notifications
CREATE OR REPLACE FUNCTION notify_bitemporal_events() RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('bitemporal_events', json_build_object(
        'event_id', NEW.event_id,
        'event_type', NEW.event_type,
        'aggregate_id', NEW.aggregate_id,
        'correlation_id', NEW.correlation_id,
        'causation_id', NEW.causation_id,
        'is_correction', NEW.is_correction,
        'transaction_time', extract(epoch from NEW.transaction_time))::text);
    -- Also notify on event-type-specific channel (dots replaced with underscores)
    PERFORM pg_notify('bitemporal_events_' || replace(NEW.event_type, '.', '_'),
        json_build_object('event_id', NEW.event_id, 'event_type', NEW.event_type,
        'causation_id', NEW.causation_id)::text);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER bitemporal_event_notify
    AFTER INSERT ON bitemporal_event_log
    FOR EACH ROW EXECUTE FUNCTION notify_bitemporal_events();
```

## Temporal Concepts

### Valid Time vs Transaction Time

- **Valid Time**: When the event actually happened in the real world (business time)
- **Transaction Time**: When the event was recorded in the system (system time)

This bi-temporal approach allows you to:
- Correct historical data while maintaining audit trails
- Query data as it was known at any point in time
- Handle late-arriving events correctly
- Support regulatory compliance and auditing requirements

### Event Versioning

Events can be corrected by creating new versions:
- Original event: version 1, is_correction = false
- Correction: version 2, is_correction = true, previous_version_id = original_event_id
- Each correction creates a new event with incremented version

## Wildcard Pattern Matching

The subscription system supports wildcard patterns for flexible event filtering:

| Pattern | Matches | Does Not Match |
|---------|---------|----------------|
| `order.*` | `order.created`, `order.shipped` | `orders.created`, `order` |
| `*.created` | `order.created`, `payment.created` | `order.shipped` |
| `order.*.completed` | `order.payment.completed`, `order.shipping.completed` | `order.created` |
| `*.order.*` | `foo.order.bar`, `abc.order.xyz` | `order.created` |

Wildcards match whole segments only (segments are separated by dots).

## Examples

See the comprehensive examples and guides in `peegeeq-examples/docs`:
- `BITEMPORAL_GUIDE.md` - Complete bi-temporal event store guide (1800+ lines)
- `BITEMPORAL_REACTIVE_GUIDE.md` - Reactive API patterns with Vert.x
- `BITEMPORAL_TRANSACTIONAL_GUIDE.md` - Transaction participation patterns

Integration tests in `peegeeq-bitemporal/src/test/java/dev/mars/peegeeq/bitemporal`:
- `PgBiTemporalEventStoreIntegrationTest.java` - Core integration tests
- `TransactionParticipationIntegrationTest.java` - Transaction participation tests
- `WildcardPatternComprehensiveTest.java` - Wildcard subscription tests
- `PgBiTemporalEventStorePerformanceTest.java` - Performance benchmarks
- `BiTemporalEventStoreExampleTest.java` - Example usage patterns
- `ReactiveNotificationHandlerIntegrationTest.java` - Real-time notification tests

## Integration with PeeGeeQ

The bi-temporal event store integrates seamlessly with PeeGeeQ's infrastructure:
- Uses PeeGeeQ's Vert.x 5.x reactive connection pooling
- Leverages PostgreSQL LISTEN/NOTIFY for real-time events
- Integrates with PeeGeeQ's metrics and monitoring (Micrometer)
- Benefits from PeeGeeQ's resilience patterns (circuit breakers, backpressure)
- Supports transaction propagation with `TransactionPropagation.CONTEXT`

## Performance Considerations

- **Batch Operations**: Use `appendBatch()` for high-throughput scenarios
- **Pipelined Client**: Automatic pipelining for maximum database throughput
- **Optimized Indexes**: Recommended indexes for temporal queries
- **Efficient JSON Storage**: PostgreSQL JSONB with proper serialization
- **Connection Pooling**: Vert.x reactive pool with configurable sizing
- **Configurable Query Limits**: Default limit of 1000 with pagination support
- **Real-time Notifications**: LISTEN/NOTIFY without polling overhead

## Testing

Run core tests (fast, no database required):
```bash
mvn test -pl peegeeq-bitemporal
```

Run integration tests (requires Docker for TestContainers):
```bash
mvn test -pl peegeeq-bitemporal -Pintegration-tests
```

Run performance tests:
```bash
mvn test -pl peegeeq-bitemporal -Pperformance-tests
```

Run all tests:
```bash
mvn test -pl peegeeq-bitemporal -Pall-tests
```

The tests use TestContainers with PostgreSQL 15 for comprehensive integration testing.
