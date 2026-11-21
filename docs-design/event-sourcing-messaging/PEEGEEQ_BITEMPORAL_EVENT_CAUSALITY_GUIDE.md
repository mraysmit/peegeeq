# PeeGeeQ Event Causality Guide

**Version**: 1.1.0  
**Last Updated**: 2026-01-08  
**Author**: Mark Andrew Ray-Smith Cityline Ltd  
**Corrected:** 2026-11-21 against `0ffaafb6`: SQL examples use the real table names; `appendBuilder()` added as the preferred API; corrections do not carry a causation ID.

---

## Overview

PeeGeeQ supports **event causality tracking** through the `causationId` field. This enables you to build chains of related events, showing which event caused which.

### Key Concepts

| Field | Purpose | Example |
|-------|---------|---------|
| `correlationId` | Groups events in the same workflow/process | `workflow-order-123` |
| `causationId` | Identifies which event caused this event | `evt-order-created-456` |
| `aggregateId` | Groups events for the same entity | `ORDER-789` |

### Why Use Causation Tracking?

- **Debug event chains** - Trace back from an error to its root cause
- **Audit trails** - See the complete history of why something happened
- **Event replay** - Reconstruct the order of operations
- **Saga patterns** - Track multi-step distributed transactions

---

## API Reference

### Append Builder (preferred)

`EventStore.appendBuilder()` (in `peegeeq-api`) is the preferred append API. It returns an `EventStoreAppendBuilder<T>`:

```java
Future<BiTemporalEvent<Payment>> paymentEventFuture = eventStore.appendBuilder()
    .eventType("PaymentProcessed")        // required
    .payload(paymentPayload)              // required
    .validTime(Instant.now())             // required
    .correlationId("workflow-123")        // optional
    .causationId(orderEvent.getEventId()) // optional: parent event ID
    .aggregateId("ORDER-456")             // optional
    .header("source", "payment-gateway")  // optional
    .execute();

paymentEventFuture
    .onSuccess(paymentEvent -> useEventId(paymentEvent.getEventId()))
    .onFailure(error -> logger.error("Failed to append payment event", error));
```

- `execute()` returns a failed Future with `IllegalStateException` if `eventType`, `payload` or `validTime` is missing.
- `.inTransaction(connection)` appends on an existing `SqlConnection` and keeps `causationId`.
- Without `.inTransaction(...)` or `.correction(...)`, `execute()` calls the seven-argument `append(...)` below.
- `.correction(originalEventId, reason)` calls `appendCorrection(...)`. No `appendCorrection` overload takes a causation ID, so a `causationId(...)` set on the builder is not stored for a correction.

The overloads `append(eventType, payload, validTime)`, `append(eventType, payload, validTime, headers)` and the shorter `appendInTransaction(...)` overloads are `@Deprecated(since = "2.0")`. The seven-argument `append(...)` below is not deprecated. The builder calls it.

### Event Store Append (seven arguments)

```java
eventStore.append(
    String eventType,           // 1. Event type identifier
    T payload,                  // 2. Event payload
    Instant validTime,          // 3. Valid time (business time)
    Map<String,String> headers, // 4. Event metadata
    String correlationId,       // 5. Workflow/process ID
    String causationId,         // 6. Parent event ID (null for root events)
    String aggregateId          // 7. Entity/aggregate ID
);
```

### BiTemporalEvent Interface

```java
public interface BiTemporalEvent<T> {
    String getEventId();
    String getCorrelationId();
    String getCausationId();      // Returns the parent event ID
    String getAggregateId();
    // ... other methods
}
```

---

## Usage Patterns

### Pattern 1: Root Event (No Causation)

The first event in a chain has no parent, so `causationId` is `null`:

```java
Future<BiTemporalEvent<Order>> orderEventFuture = eventStore.append(
    "OrderCreated",
    orderPayload,
    Instant.now(),
    Map.of("source", "web"),
    "workflow-123",    // correlationId - same for entire workflow
    null,              // causationId - null for root event
    "ORDER-456"        // aggregateId
);

orderEventFuture
    .onSuccess(orderEvent -> useEventId(orderEvent.getEventId()))
    .onFailure(error -> logger.error("Failed to append root event", error));
```

### Pattern 2: Child Event (Caused by Parent)

Subsequent events reference their parent's event ID:

```java
Future<BiTemporalEvent<Payment>> paymentEventFuture = orderEventFuture.compose(orderEvent ->
    eventStore.append(
        "PaymentProcessed",
        paymentPayload,
        Instant.now(),
        Map.of("source", "payment-gateway"),
        "workflow-123",           // Same workflow
        orderEvent.getEventId(),  // causationId - this event was caused by the order
        "ORDER-456"));             // Same aggregate
```

### Pattern 3: Event Chain (Grandchild Event)

Build deeper chains by continuing the pattern:

```java
Future<BiTemporalEvent<Shipment>> shipmentEventFuture = paymentEventFuture.compose(paymentEvent ->
    eventStore.append(
        "OrderShipped",
        shipmentPayload,
        Instant.now(),
        Map.of("source", "warehouse"),
        "workflow-123",             // Same workflow
        paymentEvent.getEventId(),  // Caused by payment confirmation
        "ORDER-456"));               // Same aggregate
```

### Resulting Event Chain

```
OrderCreated (root)
    │
    └─► PaymentProcessed (caused by OrderCreated)
            │
            └─► OrderShipped (caused by PaymentProcessed)
```

---

## REST API Usage

### Append Event with Causation

```bash
curl -X POST http://localhost:8080/api/v1/eventstores/{setupId}/{storeName}/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventType": "PaymentProcessed",
    "aggregateId": "ORDER-456",
    "correlationId": "workflow-123",
    "causationId": "evt-order-created-789",
    "payload": {
      "amount": 99.99,
      "currency": "USD"
    }
  }'
```

### Query Events by Causation

Find all events caused by a specific event:

```bash
curl "http://localhost:8080/api/v1/eventstores/{setupId}/{storeName}/events?causationId=evt-order-created-789"
```

### Query Event Response

```json
{
  "events": [
    {
      "eventId": "evt-payment-456",
      "eventType": "PaymentProcessed",
      "aggregateId": "ORDER-456",
      "correlationId": "workflow-123",
      "causationId": "evt-order-created-789",
      "validTime": "2026-01-08T10:30:00Z",
      "transactionTime": "2026-01-08T10:30:01Z",
      "payload": { "amount": 99.99, "currency": "USD" }
    }
  ]
}
```

---

## Common Patterns

### Order Processing Saga

```java
Future<BiTemporalEvent<OrderConfirmation>> sagaFuture =
    eventStore.append("OrderCreated", order, now, headers,
        "saga-" + orderId, null, orderId)
    .compose(orderEvent ->
        eventStore.append("InventoryReserved", inventory, now, headers,
            "saga-" + orderId, orderEvent.getEventId(), orderId))
    .compose(inventoryEvent ->
        eventStore.append("PaymentProcessed", payment, now, headers,
            "saga-" + orderId, inventoryEvent.getEventId(), orderId))
    .compose(paymentEvent ->
        eventStore.append("OrderConfirmed", confirm, now, headers,
            "saga-" + orderId, paymentEvent.getEventId(), orderId));

sagaFuture
    .onSuccess(confirmEvent -> logger.info("Order saga completed: {}", confirmEvent.getEventId()))
    .onFailure(error -> logger.error("Order saga failed", error));
```

### Correction Events

The example below appends a second, ordinary event that names the first as its cause. It is not a bi-temporal correction: it does not set `is_correction` or `previous_version_id`. A real correction uses `appendCorrection(...)` or `appendBuilder().correction(...)`. Neither stores a causation ID, as stated in the API Reference.

When recording a follow-up event that references the original as the cause:

```java
Future<BiTemporalEvent<Payment>> correctionFuture =
    eventStore.append("PaymentProcessed", originalPayment,
        originalTime, headers, correlationId, null, aggregateId)
    .compose(originalEvent ->
        eventStore.append("PaymentProcessed", correctedPayment,
            correctedTime,
            Map.of("correction", "true", "reason", "Wrong amount"),
            correlationId,
            originalEvent.getEventId(),  // Caused by the event being corrected
            aggregateId));

correctionFuture
    .onSuccess(correctionEvent -> logger.info("Correction appended: {}", correctionEvent.getEventId()))
    .onFailure(error -> logger.error("Failed to append correction", error));
```

### Command-Event Pattern (CQRS)

```java
// Command received
String commandId = UUID.randomUUID().toString();

// Event caused by command execution
eventStore.append("AccountCreated", accountEvent, Instant.now(),
    Map.of("commandId", commandId, "commandType", "CreateAccount"),
    commandId,  // correlationId = command ID
    null,       // causationId = null (command is external cause)
    accountId);
```

---

## Best Practices

1. **Root events have null causationId** - Don't invent a fake parent

2. **Use event IDs, not correlation IDs** - `causationId` should be the actual `eventId` of the parent event

3. **Keep correlation ID consistent** - All events in a workflow should share the same `correlationId`

4. **Store causation chains, not just immediate parent** - If you need the full chain, query recursively or denormalize

5. **Use meaningful aggregate IDs** - Group related events by business entity (order, customer, account)

---

## Querying Causation Chains

PeeGeeQ creates no table named `events`. The examples below use `bitemporal_event_log`, the table that Flyway migration V001 creates. A store created through the setup templates has its own table, `{schema}."{tableName}"`, built from `peegeeq-db/src/main/resources/db/templates/eventstore/01-table.sql`. Replace the table name in the queries with that table for such a store. Both kinds of table have a partial index on `causation_id` (`V011__Post_Fanout_Consolidation.sql` and `eventstore/02f1-index-causation.sql`).

### Find Children of an Event

```sql
SELECT * FROM bitemporal_event_log
WHERE causation_id = 'evt-parent-123'
ORDER BY transaction_time;
```

### Build Full Chain (Recursive)

```sql
WITH RECURSIVE event_chain AS (
    -- Start with root event
    SELECT event_id, event_type, causation_id, 0 as depth
    FROM bitemporal_event_log
    WHERE event_id = 'evt-root-456'
    
    UNION ALL
    
    -- Find children recursively
    SELECT e.event_id, e.event_type, e.causation_id, ec.depth + 1
    FROM bitemporal_event_log e
    JOIN event_chain ec ON e.causation_id = ec.event_id
)
SELECT * FROM event_chain ORDER BY depth;
```

### Find Root Cause

```sql
WITH RECURSIVE cause_chain AS (
    -- Start with the event in question
    SELECT event_id, event_type, causation_id
    FROM bitemporal_event_log
    WHERE event_id = 'evt-problem-789'
    
    UNION ALL
    
    -- Walk up the chain
    SELECT e.event_id, e.event_type, e.causation_id
    FROM bitemporal_event_log e
    JOIN cause_chain cc ON e.event_id = cc.causation_id
)
SELECT * FROM cause_chain 
WHERE causation_id IS NULL;  -- Root has no parent
```

---

## Summary

| Scenario | correlationId | causationId |
|----------|---------------|-------------|
| First event in workflow | New workflow ID | `null` |
| Event caused by another | Same workflow ID | Parent's `eventId` |
| Command triggers event | Command ID | `null` |
| Correction of event | Original or new | Original's `eventId` |
| Unrelated event | New workflow ID | `null` |
