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
- **Real-time Subscriptions**: Subscribe to event streams with filtering
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
// Initialize PeeGeeQ
PeeGeeQManager manager = new PeeGeeQManager();
manager.start();

// Create event store factory
BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager);

// Create event store for your event type
EventStore<OrderEvent> eventStore = factory.createEventStore(OrderEvent.class);
```

### 3. Append Events

```java
// Create an event
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

// Temporal range query
List<BiTemporalEvent<OrderEvent>> recentEvents = eventStore.query(
    EventQuery.builder()
        .validTimeRange(TemporalRange.from(Instant.now().minus(24, ChronoUnit.HOURS)))
        .sortOrder(EventQuery.SortOrder.VALID_TIME_DESC)
        .limit(100)
        .build()
).join();
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

## Examples

See the comprehensive example in `peegeeq-examples`:
- `BiTemporalEventStoreExample.java` - Complete demonstration of all features

## Integration with PeeGeeQ

The bi-temporal event store integrates seamlessly with PeeGeeQ's infrastructure:
- Uses PeeGeeQ's connection pooling and database management
- Leverages PostgreSQL LISTEN/NOTIFY for real-time events
- Integrates with PeeGeeQ's metrics and monitoring
- Benefits from PeeGeeQ's resilience patterns (circuit breakers, backpressure)

## Performance Considerations

- Optimized indexes for temporal queries
- Efficient JSON storage with PostgreSQL JSONB
- Connection pooling for high throughput
- Configurable query limits and pagination
- Real-time notifications without polling overhead

## Testing

Run the tests with:

```bash
mvn test -pl peegeeq-bitemporal
```

The tests use TestContainers to provide a real PostgreSQL environment for comprehensive integration testing.
