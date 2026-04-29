# PeeGeeQ Ordering Patterns Guide
#### © Mark Andrew Ray-Smith Cityline Ltd 2026
#### Version 1.0 (draft — finalised after Phase 2 VERIFY)

**Date**: April 29, 2026

> **Draft status**: examples in this guide reflect the planned Phase 2 API. They will be
> verified against the shipped implementation before this document is marked final.

---

## Table of Contents

1. [Understanding PeeGeeQ's Ordering Guarantees](#1-understanding-peeqeeqs-ordering-guarantees)
   - [Storage order vs. processing order](#storage-order-vs-processing-order)
   - [Why FOR UPDATE SKIP LOCKED breaks ordering](#why-for-update-skip-locked-breaks-ordering)
   - [When ordering matters](#when-ordering-matters)
2. [Consumer Mode Comparison](#2-consumer-mode-comparison)
3. [Pattern 1: Simple Consumer + Idempotency](#3-pattern-1-simple-consumer--idempotency)
4. [Pattern 2: Partitioned Consumption (OFFSET_WATERMARK)](#4-pattern-2-partitioned-consumption-offset_watermark)
   - [Topic configuration](#topic-configuration)
   - [Producer: setting a partition key](#producer-setting-a-partition-key)
   - [Consumer: ConsumerGroup with OFFSET_WATERMARK](#consumer-consumergroup-with-offset_watermark)
   - [The `__default__` partition — most common misconfiguration](#the-__default__-partition--most-common-misconfiguration)
   - [Partition discovery and rebalancing](#partition-discovery-and-rebalancing)
5. [Migration Guide: Simple → Partitioned](#5-migration-guide-simple--partitioned)
6. [Choosing Partition Keys](#6-choosing-partition-keys)
7. [Testing Strategies](#7-testing-strategies)
8. [Operational Caveats](#8-operational-caveats)

---

## 1. Understanding PeeGeeQ's Ordering Guarantees

### Storage order vs. processing order

Messages are stored in the `outbox` table with a monotonically increasing `id`. Storage order
is therefore well-defined: the row with the lower `id` was inserted first.

Processing order is a different question. A `MessageConsumer` fetches batches using
`FOR UPDATE SKIP LOCKED`, which allows multiple concurrent consumers to grab different
messages at the same time. Once a batch is in flight, each message is dispatched as a
separate Vert.x reactive `Future`. Those futures resolve in completion order, not insertion
order. A fast message N+1 will finish before a slow message N. Storage order does **not**
imply processing order.

### Why FOR UPDATE SKIP LOCKED breaks ordering

`FOR UPDATE SKIP LOCKED` gives PeeGeeQ its high throughput:

- Consumer A fetches `[msg-1, msg-2]` and skips `[msg-3]` (already locked by Consumer B).
- Consumer B fetches `[msg-3, msg-4]` concurrently.
- Consumer B finishes first: `msg-3` and `msg-4` are processed before `msg-1` and `msg-2`.

This is correct behaviour for most workloads. It only becomes a problem when the application
assumes that processing order matches insertion order.

### When ordering matters

**Requires per-entity ordering**:
- Event sourcing — version-based projections must see events in version order.
- State machines — transitions must be sequential per aggregate.
- Financial transactions — debits and credits must be applied in the order they were recorded.
- CQRS read-model updates — `EntityCreated` must be applied before `EntityUpdated`.

**Tolerates out-of-order delivery** (use the simple consumer):
- Metrics aggregation
- Log collection
- Notification delivery (email, push)
- Cache invalidation
- Audit log fan-out

---

## 2. Consumer Mode Comparison

| Feature | Simple Consumer (`MessageConsumer`) | Partitioned Consumer (`ConsumerGroup` + OFFSET_WATERMARK) |
|---|---|---|
| **Setup** | 2 lines | Topic config + partition key on every `send(...)` |
| **Ordering guarantee** | None | Strict per-partition ordering |
| **Throughput** | High — fully concurrent | High — concurrent across partitions, serial within each |
| **Scalability** | Add more consumer instances | Rebalances partitions across instances automatically |
| **Idempotency requirement** | Must handle out-of-order arrival and duplicates | Must handle duplicates only (ordering guaranteed) |
| **State** | Stateless | Persistent offset cursors per `(topic, group, partition)` |
| **Failure recovery** | Re-fetch from DB; no cursor | Resume from last committed offset |
| **Best for** | Metrics, logs, notifications, cache invalidation | Event sourcing, state machines, financial per-entity ordering |

**Decision rule**: if your handler's correctness depends on the order in which events for the
same entity arrive, use a partitioned consumer group. Otherwise, use the simple consumer.

---

## 3. Pattern 1: Simple Consumer + Idempotency

Use this pattern when messages are independent or when out-of-order arrival is acceptable.

### When to use

- Workload is embarrassingly parallel.
- Handler is idempotent by construction (e.g. upsert, set-flag).
- Deduplication or out-of-order handling is already built into the downstream system.
- Maximum throughput is the priority.

### Implementation

```java
// Create the consumer
MessageConsumer<OrderEvent> consumer = queueFactory.createConsumer("order-events", OrderEvent.class);

// Subscribe with a handler — subscribe() returns Future<Void>
consumer.subscribe(message -> {
    OrderEvent event = message.getPayload();
    // Handler must tolerate duplicate delivery and out-of-order arrival
    return orderService.upsert(event);   // idempotent upsert
})
.onFailure(err -> logger.error("Failed to subscribe: {}", err.getMessage()));

// Shutdown
consumer.close();
```

### Idempotency patterns that work with the simple consumer

**Version guard** (reject stale events):
```java
consumer.subscribe(message -> {
    OrderEvent event = message.getPayload();
    if (event.getVersion() <= readModel.getLastAppliedVersion(event.getOrderId())) {
        return Future.succeededFuture(); // already processed or out-of-order — skip
    }
    return readModel.apply(event);
});
```

> Note: a version guard alone does not fix ordering — it skips out-of-order events rather
> than processing them. If your read model must reflect every event, this guard silently drops
> data. Use the partitioned consumer instead.

**Idempotency key** (deduplicate on event ID):
```java
consumer.subscribe(message -> {
    String eventId = message.getPayload().getEventId();
    return processedEventStore.markIfAbsent(eventId)
        .compose(isNew -> isNew
            ? processEvent(message.getPayload())
            : Future.succeededFuture());
});
```

### Trade-offs

| Pro | Con |
|---|---|
| Simple setup | No ordering guarantee |
| Maximum throughput | Application must handle duplicates and out-of-order arrival |
| Horizontally scalable without coordination | Read models require idempotent handlers |

---

## 4. Pattern 2: Partitioned Consumption (OFFSET_WATERMARK)

Use this pattern when events for the same entity must be processed in the order they were
produced.

### How the guarantee works

1. The producer tags each message with a `messageGroup` (the partition key, e.g. `account-123`).
2. PeeGeeQ assigns each `messageGroup` to exactly one consumer instance in the group — no two
   instances ever overlap on the same partition.
3. Within that instance, the `PartitionedConsumerEngine` processes messages for each partition
   strictly one at a time: message N+1 is not dispatched until message N's `Future` resolves.
4. The engine does not fetch the next batch for a partition until the current batch's offset
   is committed.

The result: all events for `account-123` are processed in `id` (insertion) order, regardless
of how many consumer instances are running.

### Topic configuration

OFFSET_WATERMARK must be enabled per topic. Insert or update the topic row before
any producers or consumers start:

```sql
INSERT INTO outbox_topics (topic, completion_tracking_mode)
VALUES ('account-events', 'OFFSET_WATERMARK')
ON CONFLICT (topic) DO UPDATE
    SET completion_tracking_mode = 'OFFSET_WATERMARK';
```

Existing topics default to `REFERENCE_COUNTING`. Changing a live topic's mode without
restarting consumers is unsupported; do this before bringing up producers and consumers.

### Producer: setting a partition key

Pass the aggregate ID (or equivalent business key) as the fourth argument to `send(...)`.
Always return or compose the `Future<Void>` — never discard it.

```java
MessageProducer<AccountEvent> producer = queueFactory.createProducer("account-events", AccountEvent.class);

// The partition key is the aggregate ID — all events for this account land on one partition
String partitionKey = event.getAccountId();  // e.g. "account-123"

return producer.send(event, headers, correlationId, partitionKey)
        .onFailure(err -> logger.error("Failed to publish event {}: {}", event.getEventId(), err.getMessage()));
```

> **Rule**: every `send(...)` to an OFFSET_WATERMARK topic must include a non-null
> `messageGroup`. Omitting it routes the message to the `__default__` partition —
> see [The `__default__` partition](#the-__default__-partition--most-common-misconfiguration).

### Consumer: ConsumerGroup with OFFSET_WATERMARK

```java
// 1. Create the group
ConsumerGroup<AccountEvent> group = queueFactory.createConsumerGroup(
    "account-event-processors",   // group name — unique per logical consumer
    "account-events",             // topic
    AccountEvent.class
);

// 2. Set the handler before starting
group.setMessageHandler(message -> {
    AccountEvent event = message.getPayload();
    // message.getMessageGroup() == event.getAccountId() — same partition key as producer

    AccountReadModel model = readModels.get(event.getAccountId());
    model.apply(event);  // guaranteed in-order per account
    return Future.succeededFuture();
});

// 3. Start — returns Future<Void>; always chain onFailure
SubscriptionOptions options = SubscriptionOptions.builder()
    .startPosition(StartPosition.FROM_BEGINNING)
    .build();

group.start(options)
     .onFailure(err -> logger.error("Consumer group failed to start: {}", err.getMessage()));

// 4. Shutdown
group.stopGracefully()
     .onFailure(err -> logger.warn("Shutdown error: {}", err.getMessage()));
```

**Why this is correct**:

- All events for `account-123` go to the same partition (same `messageGroup`).
- That partition is owned by exactly one consumer instance — no concurrent processing.
- Processing within the partition is sequential — events applied in `id` order.
- Multiple accounts (`account-123`, `account-456`, `account-789`) run fully in parallel across
  partitions, preserving throughput.
- Adding a second instance triggers a rebalance; partitions are redistributed automatically.

### The `__default__` partition — most common misconfiguration

When a producer calls `send(payload)` or `send(payload, headers, correlationId)` without a
`messageGroup`, the message is stored with `message_group = NULL`. PeeGeeQ maps all such
messages to a single synthetic partition key: `__default__`.

**Consequence**: all ungrouped messages on the topic share one serial partition. There is no
concurrency. Throughput collapses to single-threaded processing.

| Producer call | Partition key | Effect |
|---|---|---|
| `send(event, headers, correlationId, "account-123")` | `account-123` | Ordered per account; parallel with other accounts |
| `send(event, headers, correlationId, null)` | `__default__` | Joins the serial bottleneck |
| `send(event)` | `__default__` | Joins the serial bottleneck |

**Rule**: on an OFFSET_WATERMARK topic, every `send(...)` must include a non-null, meaningful
`messageGroup`. The `__default__` partition is a safety net, not a feature to design around.

### Partition discovery and rebalancing

Partition discovery runs once when a consumer instance calls `joinGroup` (at `start(...)` time).
The engine queries for distinct `message_group` values among `PENDING` and `PROCESSING` rows
at that moment.

**Implication**: if no messages exist at join time, the engine starts with an empty partition
list and sits idle. To avoid this:

- Ensure at least one `PENDING` message exists for each intended partition key **before**
  calling `group.start(options)`, OR
- Sequence `start(...)` to happen only after the first batch of `send(...)` futures has
  resolved.

A rebalance is triggered when any consumer instance joins or leaves the group. After a
rebalance, newly discovered partition keys are assigned and the engine begins fetching for them.

---

## 5. Migration Guide: Simple → Partitioned

Follow these steps to migrate an existing `MessageConsumer` to a partitioned `ConsumerGroup`.

### Step 1 — Determine the partition key

Identify the entity whose events must be strictly ordered. That entity's ID is your partition
key. Typical choices:

| Workload | Partition key |
|---|---|
| Bank account events | `accountId` |
| Order lifecycle events | `orderId` |
| User workflow | `userId` |
| Per-tenant processing | `tenantId` |

The partition key must be something your producer already carries — usually the aggregate ID.

### Step 2 — Configure the topic

```sql
INSERT INTO outbox_topics (topic, completion_tracking_mode)
VALUES ('your-topic', 'OFFSET_WATERMARK')
ON CONFLICT (topic) DO UPDATE
    SET completion_tracking_mode = 'OFFSET_WATERMARK';
```

Do this before starting any producers or consumers.

### Step 3 — Update the producer

Add the partition key as the `messageGroup` argument:

```java
// Before
producer.send(event, headers, correlationId);

// After — always include the partition key
producer.send(event, headers, correlationId, event.getAggregateId());
```

Apply to every `send(...)` call for this topic. Any call that omits `messageGroup` routes to
`__default__`.

### Step 4 — Replace the consumer

```java
// Before — simple consumer
MessageConsumer<MyEvent> consumer = queueFactory.createConsumer("my-topic", MyEvent.class);
consumer.subscribe(message -> handle(message));

// After — partitioned consumer group
ConsumerGroup<MyEvent> group = queueFactory.createConsumerGroup(
    "my-processor-group", "my-topic", MyEvent.class);

group.setMessageHandler(message -> handle(message));

SubscriptionOptions options = SubscriptionOptions.builder()
    .startPosition(StartPosition.FROM_BEGINNING)
    .build();
group.start(options).onFailure(err -> logger.error("Start failed: {}", err.getMessage()));
```

### Step 5 — Review idempotency

Under OFFSET_WATERMARK, ordering is guaranteed. Duplicates are still possible (at-least-once
delivery). Your handler must tolerate duplicate delivery but no longer needs to handle
out-of-order arrival.

If you had a version guard that silently skipped out-of-order events, **keep it** — it now
serves as an idempotency fence against duplicate redelivery:

```java
group.setMessageHandler(message -> {
    MyEvent event = message.getPayload();
    // Idempotency fence — guards against duplicate redelivery, not out-of-order arrival
    if (event.getVersion() <= model.getLastAppliedVersion()) {
        return Future.succeededFuture();
    }
    return model.apply(event);
});
```

### Step 6 — Test

See [Testing Strategies](#7-testing-strategies) below.

---

## 6. Choosing Partition Keys

The partition key defines the unit of ordering. Events sharing the same key are processed
strictly sequentially; events with different keys run in parallel.

**Guidelines**:

- **Use the aggregate ID** for event-sourcing patterns. All events for one aggregate share a
  key; projections see them in order.
- **Keep keys stable**. Changing the key for an existing entity mid-stream loses ordering
  continuity. The old and new key become independent partitions.
- **Cardinality matters**. With 1 partition key for 100,000 entities, throughput is limited to
  one-at-a-time processing. With 100,000 distinct keys, you get 100,000-way parallelism.
- **Avoid keys that concentrate load**. If one aggregate generates 99% of events, all that
  load flows through a single partition.
- **`null` is always wrong** on an OFFSET_WATERMARK topic. It routes to `__default__`, serialising
  all ungrouped messages.

**Do not use as partition keys**:

- Timestamps — too many distinct values, and time-based ordering does not align with
  business-entity ordering.
- Random UUIDs generated per-message — defeats partitioning; every message gets its own
  partition and the ordering infrastructure adds overhead with no benefit.
- Global constants (e.g. `"all"`) — creates one serial partition for the entire topic.

---

## 7. Testing Strategies

### Verify per-partition ordering

Assert that event versions arrive at the consumer in strictly ascending order per partition
key:

```java
Map<String, Integer> lastVersionByPartition = new ConcurrentHashMap<>();

group.setMessageHandler(message -> {
    String partitionKey = message.getMessageGroup();
    int version = message.getPayload().getVersion();

    lastVersionByPartition.compute(partitionKey, (k, prev) -> {
        if (prev != null) {
            assertTrue(version > prev,
                "Ordering violation on partition " + k + ": " + prev + " → " + version);
        }
        return version;
    });
    return Future.succeededFuture();
});
```

### Verify cross-partition concurrency

Publish messages for two partitions with a slow handler (e.g. 100 ms delay). Assert the
total elapsed time is less than the time for both partitions processed serially:

```java
long start = System.currentTimeMillis();
// ... wait for all messages to complete ...
long elapsed = System.currentTimeMillis() - start;

long singlePartitionTime = eventCount * HANDLER_DELAY_MS;
assertTrue(elapsed < singlePartitionTime * 2,
    "Partitions should run concurrently: elapsed=" + elapsed + "ms");
```

### Lock in the discovery contract

If you are testing the empty-partition scenario (consumer starts before any messages exist),
assert that the partition list is empty at start time and becomes populated only after a
rebalance trigger:

```java
// Start consumer before any messages
group.start(options);
// Verify no partitions assigned yet
assertTrue(engine.getAssignedPartitions().isEmpty());

// Publish a message
producer.send(event, headers, correlationId, "acct-new");
// Trigger rebalance by bringing in a second instance, then assert discovery
```

### Integration test setup checklist

- [ ] Insert the `outbox_topics` row with `completion_tracking_mode = 'OFFSET_WATERMARK'` in `@BeforeEach`.
- [ ] Publish at least one message per intended partition key **before** calling `group.start(options)`.
- [ ] Use `@Tag(TestCategories.INTEGRATION)` and `@Testcontainers` for any test touching the database.
- [ ] Use `VertxTestContext` and `Checkpoint` for async completion — never `Thread.sleep`.
- [ ] Call `group.stopGracefully()` in `@AfterEach`.

---

## 8. Operational Caveats

OFFSET_WATERMARK is implemented and tested, but has lifecycle constraints that must be
designed around.

### Partition discovery is point-in-time at join

Discovery queries for distinct `message_group` values among `PENDING`/`PROCESSING` rows at
the moment `joinGroup` runs. A partition key that does not exist at join time is not assigned
until the next rebalance.

This also affects partition keys whose rows have all completed: once all rows for
`account-123` reach `COMPLETED` status, that key becomes invisible to discovery. New messages
for `account-123` arriving after the next join will not be picked up until a rebalance
redistributes them.

**Workaround**: sequence producer activity before `group.start(...)`, or accept the
rebalance latency.

### The engine does not emit heartbeats

`PartitionAssignmentService.heartbeat(...)` exists but is not called by the engine.
`last_heartbeat_at` reflects assignment time, not consumer liveness. Do not rely on
this column to detect zombie instances.

### New message groups after join require a rebalance

A `message_group` value first seen in `outbox` after `joinGroup` completes is not in any
instance's partition list. Messages accumulate in `PENDING` until a rebalance is triggered by
another join or leave event.

### At-least-once delivery still applies

OFFSET_WATERMARK guarantees per-partition order. It does **not** guarantee exactly-once.
Duplicates can occur on consumer restart before offset commit, on stale-generation rejection
followed by retry, and on rebalance windows. Keep your handler idempotent.

---

## Related Documents

- [PEEGEEQ_COMPLETE_GUIDE.md](PEEGEEQ_COMPLETE_GUIDE.md) — full system overview
- [PEEGEEQ_EXAMPLES_GUIDE.md](PEEGEEQ_EXAMPLES_GUIDE.md) — runnable examples
- [PEEGEEQ_ARCHITECTURE_API_GUIDE.md](PEEGEEQ_ARCHITECTURE_API_GUIDE.md) — API reference
- [PEEGEEQ_CONSUMER_GROUP_GETTING_STARTED.md](PEEGEEQ_CONSUMER_GROUP_GETTING_STARTED.md) — consumer group basics
- Design analysis: `docs-design/analysis/GUARANTEED_ORDERING_CONCURRENT_CONSUMERS_ANALYSIS.md`
