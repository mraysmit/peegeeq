# PeeGeeQ Event Causality Guide

**Version**: 1.1.0  
**Last Updated**: 2026-01-08  
**Author**: Mark Andrew Ray-Smith Cityline Ltd

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

### Event Store Append

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
BiTemporalEvent<Order> orderEvent = eventStore.append(
    "OrderCreated",
    orderPayload,
    Instant.now(),
    Map.of("source", "web"),
    "workflow-123",    // correlationId - same for entire workflow
    null,              // causationId - null for root event
    "ORDER-456"        // aggregateId
).join();

// Store the event ID for child events
String orderId = orderEvent.getEventId();
```

### Pattern 2: Child Event (Caused by Parent)

Subsequent events reference their parent's event ID:

```java
BiTemporalEvent<Payment> paymentEvent = eventStore.append(
    "PaymentProcessed",
    paymentPayload,
    Instant.now(),
    Map.of("source", "payment-gateway"),
    "workflow-123",           // Same workflow
    orderEvent.getEventId(),  // causationId - this event was caused by the order
    "ORDER-456"               // Same aggregate
).join();
```

### Pattern 3: Event Chain (Grandchild Event)

Build deeper chains by continuing the pattern:

```java
BiTemporalEvent<Shipment> shipmentEvent = eventStore.append(
    "OrderShipped",
    shipmentPayload,
    Instant.now(),
    Map.of("source", "warehouse"),
    "workflow-123",             // Same workflow
    paymentEvent.getEventId(),  // Caused by payment confirmation
    "ORDER-456"                 // Same aggregate
).join();
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
// 1. Order Created (root)
var orderEvent = eventStore.append("OrderCreated", order, now, headers, 
    "saga-" + orderId, null, orderId).join();

// 2. Inventory Reserved (caused by order)
var inventoryEvent = eventStore.append("InventoryReserved", inventory, now, headers,
    "saga-" + orderId, orderEvent.getEventId(), orderId).join();

// 3. Payment Processed (caused by inventory reservation)
var paymentEvent = eventStore.append("PaymentProcessed", payment, now, headers,
    "saga-" + orderId, inventoryEvent.getEventId(), orderId).join();

// 4. Order Confirmed (caused by payment)
var confirmEvent = eventStore.append("OrderConfirmed", confirm, now, headers,
    "saga-" + orderId, paymentEvent.getEventId(), orderId).join();
```

### Correction Events

When correcting a previous event, reference the original as the cause:

```java
// Original event
var originalEvent = eventStore.append("PaymentProcessed", originalPayment, 
    originalTime, headers, correlationId, null, aggregateId).join();

// Correction references the original
var correctionEvent = eventStore.append("PaymentProcessed", correctedPayment,
    correctedTime, 
    Map.of("correction", "true", "reason", "Wrong amount"),
    correlationId,
    originalEvent.getEventId(),  // Caused by the event being corrected
    aggregateId).join();
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

### Find Children of an Event

```sql
SELECT * FROM events 
WHERE causation_id = 'evt-parent-123'
ORDER BY transaction_time;
```

### Build Full Chain (Recursive)

```sql
WITH RECURSIVE event_chain AS (
    -- Start with root event
    SELECT event_id, event_type, causation_id, 0 as depth
    FROM events 
    WHERE event_id = 'evt-root-456'
    
    UNION ALL
    
    -- Find children recursively
    SELECT e.event_id, e.event_type, e.causation_id, ec.depth + 1
    FROM events e
    JOIN event_chain ec ON e.causation_id = ec.event_id
)
SELECT * FROM event_chain ORDER BY depth;
```

### Find Root Cause

```sql
WITH RECURSIVE cause_chain AS (
    -- Start with the event in question
    SELECT event_id, event_type, causation_id
    FROM events 
    WHERE event_id = 'evt-problem-789'
    
    UNION ALL
    
    -- Walk up the chain
    SELECT e.event_id, e.event_type, e.causation_id
    FROM events e
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
