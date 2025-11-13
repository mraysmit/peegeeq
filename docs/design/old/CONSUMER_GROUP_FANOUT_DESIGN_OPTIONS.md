# Consumer Group Fan-Out Design Options

**Status**: Design Exploration  
**Author**: Mark Andrew Ray-Smith Cityline Ltd 
**Date**: 2025-11-11  
**Version**: 1.0

## Table of Contents
1. [Overview](#overview)
2. [Problem Statement](#problem-statement)
3. [Current Architecture Limitations](#current-architecture-limitations)
4. [Proposed Solutions](#proposed-solutions)
5. [Summary](#summary)

---

## Overview

This document explores various design options for implementing **reliable fan-out with at-least-once delivery** to multiple consumers in PeeGeeQ. The goal is to support **pub/sub semantics** (message replication) while maintaining backward compatibility with existing **queue semantics** (message distribution).

### Key Requirements
- ✅ At-least-once delivery guarantee to multiple independent consumer groups
- ✅ Backward compatibility with existing queue-based consumers
- ✅ Support for late-joining consumers with configurable catch-up behavior
- ✅ Automatic cleanup of dead/inactive consumer groups
- ✅ Efficient message retention and cleanup
- ✅ Clear, explicit configuration per topic

---

## Problem Statement

### Use Case: Event Broadcasting
A typical scenario requiring fan-out:

```
Order Created Event
    ├─> Email Service (send confirmation email)
    ├─> Analytics Service (track metrics)
    ├─> Inventory Service (update stock)
    └─> Shipping Service (prepare shipment)
```

**Requirements:**
- Each service must receive and process the event independently
- Failure in one service should not affect others
- Services can be added/removed dynamically
- Late-joining services may need historical events

---

## Current Architecture Limitations

### Queue Semantics (Current Implementation)

PeeGeeQ currently implements **queue semantics** where messages are **distributed** (not replicated):

```java
// Current OutboxConsumer query
String sql = """
    UPDATE outbox
    SET status = 'PROCESSING', processed_at = $1
    WHERE id IN (
        SELECT id FROM outbox
        WHERE topic = $2 AND status = 'PENDING'
        ORDER BY created_at ASC
        LIMIT $3
        FOR UPDATE SKIP LOCKED
    )
    RETURNING id, payload, headers, correlation_id, message_group, created_at
    """;
```

**Behavior:**
- Each message is consumed by **exactly ONE consumer**
- `FOR UPDATE SKIP LOCKED` ensures no duplicate processing
- Message marked as `PROCESSING` immediately
- Perfect for work queue patterns, **NOT for fan-out**

### OutboxConsumerGroup Limitation

Even with `OutboxConsumerGroup`, messages are **distributed** among group members:

```java
// Round-robin distribution to ONE consumer
OutboxConsumerGroupMember<T> selectedConsumer = selectConsumer(eligibleConsumers, message);
return selectedConsumer.processMessage(message);
```

**This does NOT provide fan-out to multiple independent services.**

---

## Proposed Solutions

### Option 1: Multiple Independent Consumer Groups ⭐ (Current Workaround)

Create separate consumer groups for each logical subscriber:

```java
ConsumerGroup<OrderEvent> emailGroup = 
    queueFactory.createConsumerGroup("email-service-group", "orders", OrderEvent.class);
ConsumerGroup<OrderEvent> analyticsGroup = 
    queueFactory.createConsumerGroup("analytics-service-group", "orders", OrderEvent.class);
```

**Pros:**
- Works with current implementation
- Each group tracks processing independently in `outbox_consumer_groups` table

**Cons:**
- No coordination between groups
- No way to know when ALL groups have processed a message
- **Message cleanup is problematic** - The current `cleanup_completed_outbox_messages()` function only deletes messages when:
  1. The message status is `COMPLETED` (which happens when ANY consumer marks it complete)
  2. AND all entries in `outbox_consumer_groups` for that message are `COMPLETED`

  **The Problem**: The `outbox_consumer_groups` table is only populated when consumer groups are explicitly registered via `register_consumer_group_for_existing_messages()`. If you create multiple independent consumer groups without this registration, the cleanup function will delete messages as soon as the FIRST group completes them, preventing other groups from processing them.

  **Example Failure Scenario**:
  ```java
  // Group 1 processes message and marks it COMPLETED
  ConsumerGroup<OrderEvent> emailGroup =
      queueFactory.createConsumerGroup("email-service", "orders", OrderEvent.class);

  // Group 2 never gets a chance because message is deleted after Group 1 completes
  ConsumerGroup<OrderEvent> analyticsGroup =
      queueFactory.createConsumerGroup("analytics-service", "orders", OrderEvent.class);
  ```

  Without explicit registration and coordination, there's no way to know that `analytics-service` also needs to process the message before it's deleted.

- No support for late-joining consumers

#### Deep Dive: Why Message Cleanup Fails

The current cleanup function logic:

```sql
-- Current cleanup_completed_outbox_messages() function
DELETE FROM outbox
WHERE id IN (
    SELECT o.id
    FROM outbox o
    WHERE o.status = 'COMPLETED'
    AND NOT EXISTS (
        SELECT 1 FROM outbox_consumer_groups ocg
        WHERE ocg.outbox_message_id = o.id
        AND ocg.status != 'COMPLETED'
    )
    AND o.created_at < NOW() - INTERVAL '1 hour'
);
```

**The Race Condition:**

1. **Message marked COMPLETED too early**: When ANY consumer processes a message, it marks the outbox message status as `COMPLETED`:
   ```java
   // In OutboxConsumer.markMessageCompleted()
   String sql = "UPDATE outbox SET status = 'COMPLETED', processed_at = $1 WHERE id = $2";
   ```

2. **outbox_consumer_groups not auto-populated**: The `outbox_consumer_groups` table is only populated when:
   - Consumer groups are explicitly registered via `register_consumer_group_for_existing_messages()`
   - OR via the trigger `create_consumer_group_entries_for_new_message()` which only creates entries for ALREADY KNOWN consumer groups:
   ```sql
   -- Trigger only creates entries for groups that already exist in the table
   INSERT INTO outbox_consumer_groups (outbox_message_id, consumer_group_name, status)
   SELECT DISTINCT NEW.id, consumer_group_name, 'PENDING'
   FROM outbox_consumer_groups  -- Only knows about groups already in this table!
   WHERE consumer_group_name IS NOT NULL
   ```

3. **Cleanup deletes prematurely**: If you create multiple independent consumer groups without explicit registration:
   - Group 1 processes message → marks outbox status as `COMPLETED`
   - Cleanup job runs → sees status is `COMPLETED`
   - Cleanup checks `outbox_consumer_groups` → finds NO entries (because Group 2 was never registered)
   - NOT EXISTS clause returns TRUE (no incomplete entries found)
   - Message gets deleted ❌
   - Group 2 never sees the message

**Timeline of Failure:**

```
T0: Message inserted into outbox (status = 'PENDING')
    - No entries in outbox_consumer_groups (no groups registered yet)

T1: email-service consumer group starts and processes message
    - Marks outbox.status = 'COMPLETED'
    - No entry created in outbox_consumer_groups (group not registered)

T2: Cleanup job runs (1 hour later)
    - Finds message with status = 'COMPLETED'
    - Checks outbox_consumer_groups → no entries found
    - NOT EXISTS returns TRUE → message deleted

T3: analytics-service consumer group starts
    - Tries to fetch pending messages
    - Message already deleted → never processed ❌
```

**Why Explicit Registration Doesn't Fully Solve It:**

Even if you call `register_consumer_group_for_existing_messages()`, you still have coordination problems:

```java
// You must register ALL groups BEFORE any messages are sent
register_consumer_group_for_existing_messages("email-service");
register_consumer_group_for_existing_messages("analytics-service");
register_consumer_group_for_existing_messages("inventory-service");

// Now send messages
producer.send(orderEvent);
```

**Problems:**
- ❌ Must know all consumer groups upfront
- ❌ Can't add new consumer groups dynamically
- ❌ No way to handle late-joining consumers
- ❌ Manual coordination required across services
- ❌ Fragile - forgetting to register a group causes data loss

### Option 2: Application-Level Fan-Out

Single consumer fans out to multiple handlers:

```java
public class FanOutMessageHandler<T> implements MessageHandler<T> {
    private final List<MessageHandler<T>> handlers;
    
    @Override
    public CompletableFuture<Void> handle(Message<T> message) {
        List<CompletableFuture<Void>> futures = handlers.stream()
            .map(handler -> handler.handle(message))
            .toList();
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
}
```

**Pros:**
- Simple to implement
- Single consumer manages fan-out

**Cons:**
- No independent tracking per handler
- Difficult to scale handlers independently
- Error handling complexity

### Option 3: Outbox-to-Outbox Pattern

Consumer republishes to topic-specific outboxes:

```java
emailProducer.send(message.getPayload());
analyticsProducer.send(message.getPayload());
inventoryProducer.send(message.getPayload());
```

**Pros:**
- Complete isolation between consumers
- Each topic can have different policies

**Cons:**
- More database writes
- Additional latency
- Complex topology

### Option 4: Hybrid Queue/Pub-Sub Support ⭐⭐⭐ (RECOMMENDED)

Add pub/sub semantics as an **opt-in feature** configured per topic.

**This is the recommended approach detailed in [CONSUMER_GROUP_FANOUT_DESIGN.md](CONSUMER_GROUP_FANOUT_DESIGN.md).**

---

## Summary

This document explores four alternative design approaches for implementing consumer group fan-out in PeeGeeQ. Each option is evaluated based on its ability to meet the key requirements: at-least-once delivery, backward compatibility, dynamic consumer registration, late-joining consumer support, and scalability.

### Options Comparison

| Option | Pros | Cons | Verdict |
|--------|------|------|---------|
| **Option 1: Multiple Independent Consumer Groups** | Works with current implementation, simple | No coordination, premature message deletion, no late-joining support | ❌ Not suitable for production |
| **Option 2: Application-Level Fan-Out** | Simple to implement, single consumer | No independent tracking, difficult to scale handlers | ❌ Limited scalability |
| **Option 3: Outbox-to-Outbox Pattern** | Complete isolation, flexible policies | More database writes, additional latency, complex topology | ⚠️ Viable but inefficient |
| **Option 4: Hybrid Queue/Pub-Sub** | True pub/sub semantics, backward compatible, dynamic registration, scalable | Requires schema changes, more complex implementation | ✅ **RECOMMENDED** |

### Key Problems Identified

1. **Message Cleanup Race Condition** (Option 1)
   - Messages deleted after first consumer group completes
   - No coordination between independent consumer groups
   - Late-joining consumers miss messages

2. **Lack of Independent Tracking** (Option 2)
   - All handlers share single consumer instance
   - Cannot scale handlers independently
   - Complex error handling

3. **Write Amplification** (Option 3)
   - Each fan-out requires multiple outbox writes
   - Increased latency and storage overhead
   - Complex message topology

### Recommended Approach

After evaluating all options, **Option 4: Hybrid Queue/Pub-Sub Support** was selected as the recommended approach because it:

- ✅ Provides true pub/sub semantics with proper fan-out
- ✅ Maintains backward compatibility with existing queue-based consumers
- ✅ Supports dynamic consumer group registration
- ✅ Handles late-joining consumers gracefully with configurable catch-up
- ✅ Provides automatic cleanup of dead consumer groups via heartbeat tracking
- ✅ Scales efficiently to 30,000+ messages/second with bitmap tracking
- ✅ Addresses all critical design questions (registration, retention, cleanup, scalability)

### Next Steps

See [CONSUMER_GROUP_FANOUT_DESIGN.md](CONSUMER_GROUP_FANOUT_DESIGN.md) for the complete design specification of the chosen approach, including:

- Detailed design principles and topic semantics
- Database schema changes and migration path
- API design and implementation details
- Dead consumer group cleanup with 9 critical pitfalls addressed
- Concurrency and scalability analysis with quantified performance limits
- Operational guidance and monitoring recommendations

