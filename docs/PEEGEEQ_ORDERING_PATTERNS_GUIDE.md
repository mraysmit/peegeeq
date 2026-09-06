# PeeGeeQ Ordering Patterns Guide
*© Mark Andrew Ray-Smith Cityline Ltd 2025*  
*Version 1.1*

**Status:** IMPLEMENTED BASELINE — PRE-GA VALIDATION OPEN

**Last reconciled:** 2026-09-06

**Repository baseline:** `7db748b8e77f3aba850be7b73547d192dac5b83f`

**How to choose and apply the right ordering model for your workload.**

> **Related reading**: [PeeGeeQ Architecture & API Reference](PEEGEEQ_ARCHITECTURE_API_GUIDE.md) · [Complete Guide](PEEGEEQ_COMPLETE_GUIDE.md) · [Examples Guide](PEEGEEQ_EXAMPLES_GUIDE.md)

---

## Table of Contents

1. [Understanding PeeGeeQ's Ordering Model](#1-understanding-peegeeqs-ordering-model)
2. [Choosing a Consumer Mode](#2-choosing-a-consumer-mode)
3. [Pattern 1 Simple Consumer with Idempotent Handlers](#3-pattern-1--simple-consumer-with-idempotent-handlers)
4. [Pattern 2 Partitioned Consumption (OFFSET_WATERMARK)](#4-pattern-2--partitioned-consumption-offset_watermark)
5. [Migration Guide: Simple → Partitioned](#5-migration-guide-simple--partitioned)
6. [Verification and Release Status](#6-verification-and-release-status)
7. [Decisions and Future Boundaries](#7-decisions-and-future-boundaries)

---

## 1. Understanding PeeGeeQ's Ordering Model

### Storage order is not processing order

PeeGeeQ stores messages in insertion order (`id ASC` in the `outbox` table). Reading that order back is straightforward. **Processing** that order concurrently is a different problem.

PeeGeeQ uses `FOR UPDATE SKIP LOCKED` to dispatch messages to concurrent consumers. This is a deliberate architectural choice: when multiple consumer instances (or threads) run the same dequeue query simultaneously, each one locks a different row and skips rows already locked by others. The result is high throughput with no blocking but the processing order of those rows is unpredictable.

**Example**: A consumer fetches `[v1, v2, v3, v4]` as four concurrent async futures. `v4` completes first because its I/O path happened to be fastest. Any state machine or read model that applies events in arrival order will produce incorrect results.

This behaviour is correct and desirable for most workloads. It becomes a problem only when the *processing* order of messages carries semantic meaning.

### When ordering matters

**Requires strict ordering:**

| Workload | Why |
|---|---|
| Event sourcing / CQRS projections | Version-sequenced projections become inconsistent if events arrive out of order |
| State machines | Invalid transitions if later events are applied before earlier ones |
| Per-account financial transactions | Balance calculations depend on chronological sequence |
| Aggregate rehydration | Events must replay in original order to reconstruct state |

**Tolerates out-of-order delivery:**

| Workload | Why |
|---|---|
| Metrics aggregation | Eventual totals are the same regardless of arrival order |
| Log collection | Each log entry is independent |
| Notification delivery | Notifications are self-contained |
| Cache invalidation | The latest invalidation wins; order of earlier entries is irrelevant |

### Why sorting inside the consumer is the wrong fix

It is tempting to buffer messages and sort them by version before applying. This approach has several failure modes:

- **Incomplete batches**: a consumer can never know whether the next message has already been fetched by a different instance. It cannot distinguish "not yet delivered" from "not yet sent".
- **At-least-once redelivery**: a message may arrive twice across restarts. Sorting does not help if the version was already applied in a previous delivery cycle.
- **Complexity grows**: adding a resequence buffer turns a stateless handler into a stateful component with its own failure modes.

The correct fix is to guarantee that messages for the same logical entity are delivered in order at the infrastructure level, not to reconstruct order inside the handler.

---

## 2. Choosing a Consumer Mode

### Decision matrix

| | `MessageConsumer` (Simple) | `ConsumerGroup` (Partitioned / OFFSET_WATERMARK) |
|---|---|---|
| **Throughput** | Highest no coordination overhead | High parallel across partitions, serial within each |
| **Per-key ordering** | Not guaranteed | ✅ Guaranteed per `messageGroup` |
| **Global ordering** | Not guaranteed | Not provided (see [Section 7](#7-decisions-and-future-boundaries)) |
| **Multiple instances** | Fully concurrent | Partitions distributed across instances |
| **Setup complexity** | Minimal | Requires topic configuration + partition key design |
| **Idempotency requirement** | Always recommended | Required at-least-once still applies |
| **Recommended for** | Metrics, logs, notifications, cache invalidation | Event sourcing, state machines, financial per-account ops |

### PostgreSQL NOTIFY Coalescing and Consumer Mode Selection

PostgreSQL deduplicates pending `NOTIFY` notifications on a per-channel-per-payload basis. Because all inserts to the same PeeGeeQ topic send the same channel name as the payload, **rapid burst inserts will coalesce** — the server may deliver fewer `NOTIFY` events than messages inserted.

The impact depends on the consumer mode you choose:

| Consumer Mode | Coalescing Impact | Mitigation |
|---|---|---|
| `LISTEN_NOTIFY_ONLY` | Each NOTIFY triggers a drain loop that fetches all available messages — safe for normal rates | Use for steady, low-to-moderate message rates only |
| `HYBRID` | Polling fallback catches any messages missed by coalesced NOTIFYs | Preferred for burst-heavy workloads; polling interval bounds worst-case delivery latency |
| `STANDARD` (polling only) | Completely unaffected — no NOTIFY dependency | Acceptable for latency-tolerant workloads |

**Recommendation**: prefer `HYBRID` mode when your producers emit short bursts of messages to the same topic. The polling fallback provides a bounded-latency safety net regardless of NOTIFY delivery frequency.

### The partition key is everything

The partition key (`messageGroup`) is the unit of ordering. All messages sharing the same key are guaranteed to be processed sequentially. Messages with different keys run concurrently.

Choose the partition key to match your unit of consistency:

| Domain | Partition key |
|---|---|
| Banking | Account ID |
| E-commerce | Order ID |
| Event sourcing | Aggregate ID |
| IoT telemetry | Device ID |
| Workflow | Workflow instance ID |

**Never omit the partition key** when using OFFSET_WATERMARK. Messages without a `messageGroup` fall into the `__default__` partition and are processed serially eliminating all throughput benefit.

---

## 3. Pattern 1 Simple Consumer with Idempotent Handlers

Use a `MessageConsumer` when processing order does not affect correctness.

### When to use this pattern

- Throughput matters more than ordering.
- Each message is self-contained (no dependency on earlier messages).
- The handler outcome is the same regardless of delivery order.

### Implementation

```java
// Create a simple consumer (no partition coordination)
MessageConsumer<MetricEvent> consumer = queueFactory.createConsumer(
    "metrics-queue",
    MetricEvent.class
);

consumer.subscribe(message -> {
    MetricEvent event = message.getPayload();

    // Use an idempotency key to guard against at-least-once redelivery
    if (seenEventIds.add(event.getEventId())) {
        metricsStore.record(event);
    }

    return Future.succeededFuture();
});
```

### Idempotency fence required for all consumer types

At-least-once delivery applies regardless of consumer mode. Handlers should always guard against duplicate delivery:

```java
// ConcurrentHashMap-backed seen-ID set for in-process deduplication
private final Set<String> seenEventIds = ConcurrentHashMap.newKeySet();

private Future<Void> handleEvent(Message<DomainEvent> message) {
    String eventId = message.getPayload().getEventId();

    if (!seenEventIds.add(eventId)) {
        // Already processed skip silently
        return Future.succeededFuture();
    }

    // Normal processing path
    return applyToReadModel(message.getPayload());
}
```

> **Note**: In-process deduplication via `ConcurrentHashMap` is sufficient for tests and short-lived consumers. Production deployments that restart frequently should persist seen IDs to the database and clear them on a TTL schedule.

---

## 4. Pattern 2 Partitioned Consumption (OFFSET_WATERMARK)

Use a `ConsumerGroup` with OFFSET_WATERMARK mode when messages for the same logical entity must be processed in the order they were published.

### How the ordering guarantee works

For a topic configured as `OFFSET_WATERMARK`, the implementation applies this sequence to every
assigned partition:

1. The assignment service gives each discovered partition to one consumer-group instance for the
   current generation.
2. An in-memory guard allows at most one fetch-and-handle chain for that partition in an engine.
3. The engine fetches rows after the committed cursor in ascending message identifier order.
4. Row locking prevents simultaneous fetches from claiming the same message row.
5. Handler invocations within the partition are composed sequentially.
6. The committed offset advances only after the complete batch succeeds.
7. A rebalance increments the generation, fencing a displaced owner from advancing the current
   cursor.
8. Periodic heartbeat and assignment reconciliation refresh liveness and remove ownership that
   the instance no longer holds.

The supporting safety mechanisms are:

| Mechanism | Purpose |
|---|---|
| Unique assignment row | Prevents two committed assignment records for the same topic, group, and partition |
| Transactionally serialized rebalance | Prevents partially visible assignment changes |
| Generation fencing | Rejects offset changes made by an owner from an earlier rebalance |
| Row locking during fetch | Prevents simultaneous fetches from locking the same message row |
| Per-partition in-progress guard | Prevents overlapping batches in one engine |
| Offset commit after successful handling | Replays a failed or interrupted batch from the last committed point |
| Periodic heartbeat and assignment reconciliation | Refreshes liveness and replaces the engine's in-memory ownership snapshot |

These controls protect ordering and the queue cursor. They do not make application side effects
exactly once. A handler already running during a rebalance may finish before its stale cursor update
is rejected, so handlers must remain idempotent and replay-safe.

### Step 1 Configure the topic

```sql
INSERT INTO outbox_topics (topic, completion_tracking_mode)
VALUES ('account-events', 'OFFSET_WATERMARK')
ON CONFLICT (topic) DO UPDATE
    SET completion_tracking_mode = 'OFFSET_WATERMARK';
```

Create a consumer group subscription:

```sql
INSERT INTO outbox_topic_subscriptions (topic, group_name)
VALUES ('account-events', 'account-event-processors')
ON CONFLICT DO NOTHING;
```

### Step 2 Producer: always set messageGroup

```java
// The aggregate ID is the partition key all events for this account
// are routed to the same partition and processed in order.
String partitionKey = command.getAggregateId();  // e.g. "account-123"

// send() returns Future<Void> always compose it; never discard it.
return eventProducer.send(event, headers, correlationId, partitionKey)
    .onFailure(err -> logger.error("Failed to publish event {}: {}", event.getEventId(), err));
```

> **Reactive rule**: The `Future<Void>` returned by `send(...)` must be composed into the caller's chain. Discarding it allows the command handler to complete before the event is durably persisted, breaking the CQRS guarantee.

### Step 3 Consumer: use ConsumerGroup

```java
ConsumerGroup<DomainEvent> eventGroup = queueFactory.createConsumerGroup(
    "account-event-processors",
    "account-events",
    DomainEvent.class
);

// Handler called sequentially for each partition
// message.getMessageGroup() == partitionKey set on the producer side
eventGroup.setMessageHandler(message -> {
    DomainEvent event = message.getPayload();
    String accountId = event.getAggregateId();

    // Idempotency fence
    if (!seenEventIds.add(event.getEventId())) {
        return Future.succeededFuture();
    }

    AccountReadModel account = readModels.computeIfAbsent(
        accountId, id -> new AccountReadModel());
    account.applyEvent(event);  // ✅ Guaranteed in-order per account

    return Future.succeededFuture();
});

// start(SubscriptionOptions) returns Future<Void> chain onFailure
SubscriptionOptions options = SubscriptionOptions.builder()
    .startPosition(StartPosition.FROM_BEGINNING)
    .build();

eventGroup.start(options)
    .onFailure(err -> logger.error("Consumer group start failed: {}", err.getMessage()));
```

### Concurrency across partitions

Messages with *different* partition keys run concurrently:

```
account-001 events → partition-0 → Consumer instance A (sequential)
account-002 events → partition-1 → Consumer instance B (sequential)
account-003 events → partition-2 → Consumer instance A (sequential)
```

Each partition is processed sequentially; different partitions can be processed in parallel. Adding
consumer instances triggers a rebalance that redistributes partitions. The resulting throughput
depends on partition cardinality, key skew, handler cost, and database capacity; it must be measured
for the target deployment rather than assumed to scale linearly.

### The `__default__` partition critical behaviour

When a producer calls `send(payload)` without a `messageGroup`, the message is stored with `message_group = NULL`. Internally, `PartitionAssignmentService` maps `NULL` to the synthetic key `__default__`.

**All ungrouped messages share one serial partition.** This means:

- No concurrent processing of ungrouped messages.
- All messages for the topic block on each other.
- The throughput advantage of OFFSET_WATERMARK is eliminated.

**Rule**: Always set `messageGroup` on every `send()` call when using OFFSET_WATERMARK. The `__default__` partition exists as a safety net, not a recommended usage pattern.

### Operational boundaries

These are documented contracts of the current implementation.

#### Partition discovery occurs during rebalance

`joinGroup` discovers partitions from the active outbox rows visible during the rebalance. If no rows
exist at that point, the initial assignment can be empty. A `message_group` value that first appears
after the rebalance is not made assignable by the heartbeat cycle alone; discovery requires a later
join or leave to trigger another rebalance.

Deployments with unbounded or late-appearing partition keys must account for this lifecycle. Where
the key set is known, publish at least one pending message for each intended key before the initial
group join. Otherwise, provide an operational rebalance strategy until a separately approved
discovery mechanism exists.

#### Heartbeat and assignment reconciliation are periodic

`PartitionedConsumerEngine` periodically updates its heartbeat and reconciles its current database
assignments. Reconciliation initializes missing cursor state, drops partitions no longer owned by
the instance, and picks up ownership changes produced by a rebalance. It does not discover new
partition keys independently.

Operators should monitor assignment-heartbeat age, rebalance churn, pending-offset age, watermark
lag, and sustained stale-generation commit rejection.

#### At-least-once delivery still applies

`OFFSET_WATERMARK` guarantees per-partition order; it does not guarantee exactly-once application
effects. A failed or interrupted batch replays from the last committed cursor. Replays can also
follow consumer restart, generation rejection, or a rebalance window. Consumer handlers must remain
idempotent.

---

## 5. Migration Guide: Simple → Partitioned

### Before starting

- Identify the partition key: the field whose value groups messages that must be ordered relative to each other. This is almost always an aggregate ID or entity ID.
- Confirm that every producer `send()` call sets that field as `messageGroup`. A single ungrouped send silently routes to `__default__` and breaks ordering for all other ungrouped messages.
- Ensure handlers are idempotent before migration. At-least-once delivery means duplicates are more likely during rebalance windows immediately after the migration.

### Migration steps

**Step 1 Update the topic mode** (one-time database change):

```sql
UPDATE outbox_topics
   SET completion_tracking_mode = 'OFFSET_WATERMARK'
 WHERE topic = 'your-topic';

-- Create the consumer group subscription if not already present
INSERT INTO outbox_topic_subscriptions (topic, group_name)
VALUES ('your-topic', 'your-group-name')
ON CONFLICT DO NOTHING;
```

**Step 2 Update the producer**: add `messageGroup` to every `send()` call.

```java
// Before
producer.send(event, headers, correlationId);

// After
producer.send(event, headers, correlationId, event.getAggregateId());
```

**Step 3 Replace `MessageConsumer` with `ConsumerGroup`**:

```java
// Before
MessageConsumer<MyEvent> consumer = queueFactory.createConsumer(topic, MyEvent.class);
consumer.subscribe(message -> handleEvent(message));

// After
ConsumerGroup<MyEvent> group = queueFactory.createConsumerGroup(groupName, topic, MyEvent.class);
group.setMessageHandler(message -> handleEvent(message));
group.start(SubscriptionOptions.builder().startPosition(StartPosition.FROM_BEGINNING).build())
     .onFailure(err -> logger.error("Group start failed", err));
```

**Step 4 Add idempotency** to the handler if not already present (see Section 3).

**Step 5 Validate ordering** by running with two consumer instances and asserting per-key sequence. Reference: `PartitionedOrderingDemoTest` in `peegeeq-examples`.

### Partition key selection

| Constraint | Guidance |
|---|---|
| Key cardinality is too low (e.g. `status` field with 3 values) | Only 3 partitions exist; most consumers sit idle. Use a higher-cardinality key. |
| Key cardinality is very high (e.g. UUID per message) | Every message gets its own partition; no rebalancing, but discovery scan is slow at large scale. Consider a hash bucket if needed. |
| Key is unknown at produce time | Store the key as part of the event payload and pass it through to the consumer handler via `message.getMessageGroup()`. |
| Key contains PII | The partition key is stored in `outbox_partition_assignments` and `outbox_partition_offsets`. Hash or pseudonymise before use. |

### Testing strategies

1. **Verify per-key ordering**: send N events with version numbers for K different keys; assert that the handler receives each key's events in strictly ascending version order. See `testPartitionedOrdering_eventsPerAggregateInOrder` in `PartitionedOrderingDemoTest`.

2. **Verify concurrent throughput**: send events for 2+ keys with a slow handler; assert that total elapsed time is less than twice the single-key time. See `testPartitionedOrdering_differentAggregatesProcessedConcurrently` in `PartitionedOrderingDemoTest`.

3. **Verify idempotency**: restart the consumer without resetting the committed offset; assert that no message is applied twice. See `testPartitionedOrdering_idempotentRedelivery` in `PartitionedOrderingDemoTest`.

4. **Verify default partition behaviour**: send messages without `messageGroup`; assert they arrive in order and that `partition_key = '__default__'` in `outbox_partition_assignments`. See `testPartitionedOrdering_defaultPartition_noMessageGroup` in `PartitionedOrderingDemoTest`.

5. **Verify CQRS correctness under concurrency**: send multi-event sequences for multiple aggregates concurrently; assert that each read model reaches the correct final state. See `testCQRS_multipleAccounts_perAccountOrdering` in `EventSourcingCQRSDemoTest`.

---

## 6. Verification and Release Status

The ordered-consumption implementation baseline is complete. Maintained verification surfaces
cover:

- `EventSourcingCQRSDemoTest` for ordered CQRS projection updates;
- `PartitionedOrderingDemoTest` for per-key order, cross-partition concurrency, the default
  partition, and restart behaviour;
- `PartitionedConsumerSafetyIntegrationTest` for discovery and failed-batch redelivery boundaries;
- `PartitionAssignmentIntegrationTest` for assignment, rebalance, generation, and heartbeat
  behaviour;
- `PartitionedFetcherIntegrationTest` and `PartitionedOffsetManagerIntegrationTest` for fetch and
  cursor correctness; and
- `PartitionedNativeConsumerIntegrationTest` plus consumer-group fault tests for lifecycle and
  failure paths.

Historical test counts describe only the revisions on which they were recorded. The
[consolidated task register](../docs-design/tasks/tasks.md) is authoritative for accepted current
verification.

### Remaining pre-GA gates

The only approved outstanding ordering work is the evidence required by
[consolidated Task 6](../docs-design/tasks/tasks.md#6-partitioned-consumption-pre-ga-gates):

- long-duration partition and fan-out stability;
- consumer death, lease expiry, rebalance, and recovery chaos;
- database contention and connection-pool pressure;
- tenant and schema isolation under concurrent activity;
- cleanup correctness after interrupted processing; and
- a documented performance envelope from a controlled environment.

The implementation is not declared generally available from static inspection alone. Those gates
require dated, reproducible runtime evidence.

---

## 7. Decisions and Future Boundaries

The current ordering decisions are:

- `REFERENCE_COUNTING` remains the default topic mode.
- `OFFSET_WATERMARK` remains an explicit opt-in.
- Producers supply an explicit partition key; hidden reflection or annotation-based extraction is
  not part of the contract.
- A consumer-side version guard is an idempotency fence, not an ordering mechanism.
- Per-partition ordering does not imply exactly-once application effects.
- Total global ordering and a dedicated native-table partition fetch path require separate product
  decisions.

### Evaluated but unapproved designs

**Total global ordering.** A single active consumer guarded by a PostgreSQL session advisory lock
could provide total order for an entire topic. It would trade horizontal throughput for
active/passive failover and require a dedicated session, explicit fencing semantics, lifecycle
tests, and operator guidance. It is not implemented or scheduled.

**Native-table partition fetch parity.** A dedicated fetcher for `queue_messages` could combine the
native storage path with the watermark contract. Before such work is approved, the project must
establish a concrete latency requirement, cursor ownership, notification/fetch interaction, schema
indexes, and compatibility tests. It is not an approved implementation task.

No unchecked item from the superseded guaranteed-ordering analysis is a separate live task.

---

## Related Documentation

- [Getting Started](PEEGEEQ_GETTING_STARTED.md) maintained entry path and guide selection
- [PeeGeeQ Architecture & API Reference](PEEGEEQ_ARCHITECTURE_API_GUIDE.md) `PartitionedConsumerEngine`, `PartitionAssignmentService`, `PartitionedOffsetManager` internals
- [PeeGeeQ Examples Guide](PEEGEEQ_EXAMPLES_GUIDE.md) `PartitionedOrderingDemoTest`, `EventSourcingCQRSDemoTest`, and other runnable examples
- [Transactional Outbox Patterns Guide](PEEGEEQ_TRANSACTIONAL_OUTBOX_PATTERNS_GUIDE.md) foundational outbox patterns that OFFSET_WATERMARK builds on
- [Partitioned Consumption Design](../docs-design/consumer-groups/PEEGEEQ_PARTITIONED_CONSUMPTION_DESIGN.md) implemented internals and design invariants
- [Consolidated Task Register](../docs-design/tasks/tasks.md) authoritative remaining implementation and release work
