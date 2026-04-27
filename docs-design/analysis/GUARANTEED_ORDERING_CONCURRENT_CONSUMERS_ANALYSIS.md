# Guaranteed Ordering for Concurrent Consumers - Architecture Design

**Author**: GitHub Copilot AI Analysis  
**Date**: April 27, 2026  
**Status**: REVIEWED — corrections applied April 27, 2026  
**Version**: 1.1  
**Priority**: P1 (Architectural Documentation & Pattern Guidance)

---

## Executive Summary

**Problem**: PeeGeeQ achieves high throughput via `FOR UPDATE SKIP LOCKED`, enabling concurrent message processing. However, this breaks ordering - message N+1 can complete before message N, causing failures in order-sensitive workloads (event sourcing, state machines, financial transactions).

**Current State**: PeeGeeQ **already implements** the solution (OFFSET_WATERMARK mode with `PartitionedConsumerEngine`), but:
- Not demonstrated in examples
- Not documented in user guides  
- EventSourcingCQRSDemoTest uses wrong pattern (simple consumer → intermittent failures)

**Proposed Solution**:
1. Document **two consumer modes** with clear use-case guidance
2. Demonstrate partition-based ordering in EventSourcingCQRSDemoTest
3. Add comprehensive ordering patterns guide
4. Provide migration path for existing applications

**Impact**: Enables guaranteed per-partition ordering while maintaining concurrent throughput across partitions

---

## Problem Analysis

### The Core Trade-Off: Design Choice vs. Technical Limitation

PeeGeeQ is designed for **throughput over ordering**. While a single simple `MessageConsumer` could theoretically be designed to fetch and process messages strictly sequentially, omitting this feature for simple consumers is a deliberate **architectural design choice**, not a technical limitation.

If simple consumers enforced global strict ordering:
- Scaling to multiple instances would cause severe **head-of-line blocking** (e.g., Consumer A processing Message 1 would block Consumer B from accessing Message 2).
- The `FOR UPDATE SKIP LOCKED` mechanism would lose its primary benefit of concurrent, lock-free throughput.

Therefore, PeeGeeQ intentionally divides consumer responsibilities:
- **Simple Consumer (`MessageConsumer`)**: Optimized entirely for raw, concurrent throughput. No coordination, no ordering guarantees, even when running as a single instance.
- **Consumer Group (`ConsumerGroup`)**: Optimized for coordinated state. Introduces the required overhead (partition assignment, exclusivity, fencing) to mathematically guarantee order per-partition.

| Optimization | Benefit | Cost |
|--------------|---------|------|
| `FOR UPDATE SKIP LOCKED` | Multiple consumers grab different messages | Storage order ≠ processing order |
| Vert.x Reactive Futures | Concurrent async processing | Future completion order unpredictable |
| At-least-once delivery | No message loss | Application must handle duplicates + out-of-order |

**This is intentional and correct** for most use cases. But some workloads need **strict ordering**.

### When Ordering Matters

**Requires Ordering**:
- Event sourcing (version-based projections)
- State machines (transitions must be sequential)
- Financial transactions (per-account ordering)
- Aggregate rehydration (events applied in sequence)

**Tolerates Out-of-Order**:
- Metrics aggregation  
- Log collection
- Notification delivery
- Cache invalidation

### EventSourcingCQRSDemoTest Failure Analysis

**Affected test**: `testCQRS` (Order=2), **not** `testEventSourcing` (Order=1).

`testEventSourcing` stores raw events in a `List` and sorts by version before asserting — it does not apply events to a read model and is therefore not fragile. `testCQRS` is the failing test.

**Failing test code** (current `testCQRS`):
```java
// Creates simple MessageConsumer (no ordering guarantee)
MessageConsumer<DomainEvent> eventConsumer = queueFactory.createConsumer(eventQueue, DomainEvent.class);

// READ SIDE — updates read model from events
eventConsumer.subscribe(message -> {
    DomainEvent event = message.getPayload();
    readModelAggregate.applyEvent(event);  // Version-based guard fails on out-of-order
    return Future.succeededFuture();
});
```

**Version guard in `AccountReadModel.applyEvent()`**:
```java
public void applyEvent(DomainEvent event) {
    if (event.getVersion() <= lastProcessedVersion) {
        return; // silently skipped when out-of-order
    }
    // ... update fields ...
    this.lastProcessedVersion = event.getVersion();
}
```

**What Happens**:
1. Consumer fetches batch: [v1, v2, v3, v4]
2. All 4 events process concurrently (multiple Futures in-flight)
3. v4 completes first → `lastProcessedVersion = 4`
4. v1, v2, v3 arrive later → all rejected by `if (version <= 4)`
5. `totalTransactions` stays at 1; balance reflects only the first event
6. Test fails: `assertEquals(2050.0, readAggregate.currentBalance)` — actual value is wrong

**Why the version guard cannot fix this**:
- The guard is intended as a duplicate-detection fence, not an ordering mechanism
- It assumes strictly increasing arrival order, which `FOR UPDATE SKIP LOCKED` does not guarantee
- Sorting or resequencing at the consumer is too complex and defeats the purpose of the queue

---

## Existing Solution: OFFSET_WATERMARK Mode

PeeGeeQ **already has** infrastructure for guaranteed ordering via **partitioned consumption**.

### Architecture Overview

**Key Components** (all implemented):

1. **`PartitionedConsumerEngine`** (`peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PartitionedConsumerEngine.java`)
   - Coordinates partition assignment, fetching, offset commits
   - **Sequential processing per partition** (prevents concurrent Futures for same partition)
   
2. **`PartitionAssignmentService`** (`peegeeq-db/src/main/java/dev/mars/peegeeq/db/consumer/PartitionAssignmentService.java`)
   - Assigns partitions to consumer instances
   - Handles rebalancing when consumers join/leave
   
3. **`PartitionedOffsetManager`** (`peegeeq-db/src/main/java/dev/mars/peegeeq/db/consumer/PartitionedOffsetManager.java`)
   - Tracks per-(group, partition) offset cursors
   - Enables resumable processing from last committed offset

4. **`message_group` Column** (database schema)
   - Producer sets partition key via `messageGroup` parameter
   - Messages with same `message_group` guaranteed sequential processing

### How It Works: The Mechanics of Guaranteed Ordering

PeeGeeQ guarantees strict message ordering through a precise four-step mechanism that securely isolates partitions:

1. **Routing**: When producers send a message, they specify a `messageGroup` (the partition key, e.g., `account-123`). All messages for `account-123` are routed to the same partition.
2. **Exclusive Ownership**: The `PartitionAssignmentService` ensures that a specific partition is assigned to **exactly one consumer instance** within the consumer group. No two consumers will ever overlap on `account-123`.
3. **Sequential Processing Loop**: Inside the assigned consumer, `PartitionedConsumerEngine` fetches messages for the partition in batches but processes them strictly one-by-one. It waits for Message 1's `Future` to complete successfully *before* dispatching Message 2.
4. **Guarded Fetching**: The engine will not fetch the next batch for that partition until the offset for the entire current batch is successfully committed to the database, enforced by an atomic `fetchInProgress` guard.

Below is how these mechanics translate into the codebase.

#### 1. Partition Assignment

```java
// Consumer joins partitioned group via QueueFactory
ConsumerGroup<DomainEvent> group = queueFactory.createConsumerGroup(
    "event-processors", "events", DomainEvent.class);

// Set handler before starting
group.setMessageHandler(message -> Future.succeededFuture());

// start(SubscriptionOptions) returns Future<Void> — chain failure handling
SubscriptionOptions options = SubscriptionOptions.builder()
    .startPosition(StartPosition.FROM_BEGINNING)
    .build();
group.start(options)
    .onFailure(err -> logger.error("Failed to start group: {}", err.getMessage()));

// Internally, PgNativeConsumerGroup.startInternal() detects OFFSET_WATERMARK,
// then calls PartitionedConsumerEngine.start() which:
//   → assignmentService.joinGroup(topic, groupName, instanceId)
//   → returns: [partition-acc-123, partition-acc-456, partition-acc-789]
```

> **Note**: The no-arg `void start()` also exists but does not return a `Future<Void>` and
> cannot be chained. Use `start(SubscriptionOptions)` when you need to observe the join result
> or handle startup failure. Both paths detect the topic mode automatically.

#### 2. Sequential Fetch Per Partition

```java
// PartitionedConsumerEngine.fetchPartition():
private void fetchPartition(String partitionKey, int generation) {
    // computeIfAbsent — creates the guard lazily on first encounter
    AtomicBoolean inProgress = fetchInProgress.computeIfAbsent(partitionKey, k -> new AtomicBoolean(false));
    if (!inProgress.compareAndSet(false, true)) {
        return;  // Previous batch still processing - skip this cycle
    }

    fetcher.fetch(topic, groupName, partitionKey, batchSize, generation)
        .compose(messages -> processAndCommit(messages, partitionKey, generation))
        .eventually(() -> {
            inProgress.set(false);  // Release lock for next cycle
            return Future.succeededFuture();
        });
}
```

**Key Point**: `inProgress` guard prevents overlapping fetch cycles for same partition

#### 3. Sequential Processing Within Batch

```java
// PartitionedConsumerEngine.processAndCommit():
private Future<Void> processAndCommit(List<OutboxMessage> messages, String partitionKey, int generation) {
    // Process messages **sequentially** in order (NOT concurrently)
    Future<Void> chain = Future.succeededFuture();
    for (OutboxMessage msg : messages) {
        chain = chain.compose(v -> dispatchMessage(msg));  // Sequential chaining
    }
    
    // Commit offset after ALL messages processed
    return chain.compose(v -> offsetManager.commitOffset(...));
}
```

**Key Point**: `.compose()` chains enforce sequential execution - message N+1 waits for message N

---

### The `__default__` Partition — Critical Behaviour

When a producer calls `send(payload)` or `send(payload, headers, correlationId)` without a `messageGroup` argument, the message is stored with `message_group = NULL`.

`PartitionAssignmentService.discoverPartitionsInternal()` maps this to the synthetic key `__default__`:

```sql
SELECT DISTINCT COALESCE(message_group, '__default__') AS partition_key
FROM outbox WHERE topic = $1 AND status IN ('PENDING', 'PROCESSING')
```

`PartitionedFetcher` then routes `__default__` to a separate query branch that filters `WHERE message_group IS NULL`.

**Implications**:

| Producer call | Partition key | Behaviour |
|---|---|---|
| `send(event, headers, corrId, "account-123")` | `account-123` | Ordered per account; concurrent with other accounts |
| `send(event)` | `__default__` | All ungrouped messages share **one** serial partition — no concurrency |

**This is the single most common misconfiguration.** If you configure a topic as `OFFSET_WATERMARK` but do not pass a `messageGroup` on every `send()`, all messages are funnelled through `__default__` and processed serially, eliminating any throughput benefit from partitioning.

**Rule**: Always set `messageGroup = aggregateId` (or equivalent business key) when using OFFSET_WATERMARK. The `__default__` partition exists as a safety net, not a recommended usage.

### Option 1: Use OFFSET_WATERMARK Mode (Recommended) ⭐

**Approach**: Migrate EventSourcingCQRSDemoTest to use partitioned consumer group

**Changes Required**:

1. **Set up OFFSET_WATERMARK topic**:
```sql
-- Configure topic for offset/watermark mode
INSERT INTO outbox_topics (topic, completion_tracking_mode)
VALUES ('account-events', 'OFFSET_WATERMARK')
ON CONFLICT (topic) DO UPDATE
SET completion_tracking_mode = 'OFFSET_WATERMARK';
```

2. **Producer: Set partition key**:
```java
// Use aggregate ID as partition key
String partitionKey = command.getAggregateId();  // e.g., "account-123"
eventProducer.send(event, headers, correlationId, partitionKey);
```

3. **Consumer: Use partitioned group**:
```java
// Create consumer group via QueueFactory (NOT simple MessageConsumer)
ConsumerGroup<DomainEvent> eventGroup = queueFactory.createConsumerGroup(
    "read-model-updaters",
    eventQueue,
    DomainEvent.class
);

// Set message handler (required before start)
eventGroup.setMessageHandler(message -> {
    DomainEvent event = message.getPayload();
    // message.getMessageGroup() == event.getAggregateId() — same partition key used by producer

    AccountReadModel readModel = readModels.get(event.getAggregateId());
    readModel.applyEvent(event);  // ✅ Guaranteed in-order per account

    eventsProcessed.incrementAndGet();
    eventCheckpoint.flag();
    return Future.succeededFuture();
});

// start(SubscriptionOptions) — returns Future<Void>; always chain onFailure
SubscriptionOptions options = SubscriptionOptions.builder()
    .startPosition(StartPosition.FROM_BEGINNING)
    .build();
eventGroup.start(options)
    .onFailure(err -> testContext.failNow(err));
```

**Why This Works**:
- ✅ All events for same account go to same partition
- ✅ Partition processed sequentially (no concurrent Futures)
- ✅ Ordering guaranteed within partition
- ✅ Concurrency across partitions (account-123 and account-456 parallel)
- ✅ Scalability: Add more consumers → rebalance partitions

**Trade-offs**:
- More complex setup (topic configuration, partition keys)
- Requires understanding of partitioning concepts
- Test setup overhead (schema initialization)

---

### Option 2: Use Event ID for Idempotency (Fallback)

**Approach**: Keep simple consumer, fix idempotency logic

**Changes Required**:

```java
// AccountReadModel
private final Set<String> processedEventIds = new HashSet<>();

public void applyEvent(DomainEvent event) {
    // ✅ Use event ID (unique identifier) not version (sequence number)
    if (processedEventIds.contains(event.getEventId())) {
        return;  // True duplicate
    }
    
    // Process event regardless of arrival order
    switch (event.getEventType()) {
        case ACCOUNT_OPENED:
            this.currentBalance = ...;
            break;
        // ... more cases
    }
    
    processedEventIds.add(event.getEventId());
    this.lastProcessedVersion = Math.max(this.lastProcessedVersion, event.getVersion());
}
```

**Why This Works**:
- ✅ Handles out-of-order delivery (v4 before v3)
- ✅ Handles true duplicates (same event twice)
- ✅ Simpler than partitioned consumption
- ✅ Matches documented idempotency patterns

**Trade-offs**:
- ❌ Doesn't teach ordering patterns
- ❌ Memory overhead (O(N) event IDs)
- ❌ Not production-ready (in-memory Set doesn't persist)
- ❌ Doesn't solve general ordering problem

---

### Option 3: Future Proposal — Exclusive Consumer (Total Global Ordering)

**Approach**: Use PostgreSQL Advisory Locks to guarantee that only one simple consumer can ever process a queue, enforcing strict sequential execution across all messages.

While `OFFSET_WATERMARK` mode provides *per-key* ordering, some use cases require **total global ordering** across the entire queue. Currently, a single `MessageConsumer` cannot guarantee this safely, as a rogue second consumer could connect and steal rows via `SKIP LOCKED`, immediately destroying the ordering guarantee.

To solve this mathematically without forming a consumer group, an **Exclusive Consumer Model** can be implemented using PostgreSQL Advisory Locks (`pg_try_advisory_lock`).

**Proposed Implementation Details**:
1. **Configuration**: Add an `exclusive(true)` flag to `ConsumerConfig`.
2. **Advisory Lock Acquisition**: On startup, the consumer attempts to acquire a session-level advisory lock using a unique hash of the topic/queue name.
3. **Active/Passive HA**: If the lock is held by another instance, the consumer does not fail; instead, it enters a hot-standby polling loop, waiting passively for the active consumer to crash or gracefully disconnect.
4. **Sequential Processing Loop**: Once locked, the active exclusive consumer fetches batches and iterates through them strictly sequentially (chaining futures), safe in the knowledge that no other consumer can concurrently read from this queue.

**Trade-offs vs. Partitioned Consumer Groups**:

| Feature | Exclusive Consumer (`exclusive=true`) | Partitioned Consumer Group (`OFFSET_WATERMARK`) |
|---------|---------------------------------------|-------------------------------------------------|
| **Scope of Ordering** | Total Global Ordering (all messages) | Per-Key Ordering (e.g., per account) |
| **Max Throughput** | Limited to exactly 1 thread on 1 instance | Scales infinitely across multiple instances |
| **Fault Tolerance** | Active/Passive (HA failover latency based on session limits) | Active/Active (Dynamic partition rebalancing) |
| **Complexity** | Low (simple session lock) | High (offset tracking, generations) |

*Conclusion*: This is a highly valid future extension for domains requiring absolute sequential consistency at the expense of horizontal scalability.

---

### TODO: Cross-Module API Alignment Analysis (Outbox & Bitemporal)

**Status: Pending Deep Analysis**

The ordering guarantees designed in the `peegeeq-native` layer must be explicitly exposed through the configuration APIs of the higher-level modules. A strict ordered consumption feature is only useful if it maintains feature parity across the entire ecosystem.

**1. `peegeeq-outbox` Impact:**
- **Current State:** `OutboxConsumerConfig.Builder` currently only supports `pollingInterval`, `batchSize`, `consumerThreads`, `maxRetries`, and `serverSideFilter`. It completely lacks the ability to configure `exclusive(true)` or safely enforce `OFFSET_WATERMARK` modes.
- **Required Analysis:** Outbox pattern implementations are typically used for transactional log relays (e.g., Change Data Capture). Chronological integrity is critical. We must design how `OutboxConsumer` and `OutboxConsumerGroup` explicitly expose strict ordering configurations so downstream systems do not receive "Entity Updated" events before "Entity Created" events.

**2. `peegeeq-bitemporal` Impact:**
- **Current State:** `PgBiTemporalEventStore` uses a simplistic `subscribe(eventType, handler)` API backed by `ReactiveNotificationHandler`. It has no configuration object to request durable, exclusive timeline ordering.
- **Required Analysis:** Bitemporal state is projected using precise `valid_time` and `transaction_time` coordinates. Consuming these streams concurrently via `SKIP LOCKED` risks replaying timelines out-of-sequence, breaking the mathematical projection of state. We must design a `BiTemporalSubscriptionConfig` object to expose the `exclusive` and partitioned ordering features securely to bitemporal subscribers.

This TODO serves as a placeholder to expand the architectural design of module consistency before implementation begins.

---

## Recommendation

**For EventSourcingCQRSDemoTest**: Use **Option 1 (OFFSET_WATERMARK)** because:
1. Demonstrates **correct architectural pattern** for event sourcing
2. Shows users **how to achieve ordering** in production
3. Uses **existing infrastructure** (no new code needed)
4. Validates **partitioned consumption** works correctly

**For General Guidance**: Document **both patterns**:
- Simple consumer + idempotency → Use when order doesn't matter
- Partitioned consumer → Use when per-key ordering required

---

## Implementation Plan

### TDD Ordering (mandatory — do not reverse)

```
RED PHASE — write failing tests first:
  1. Write testCQRS_multipleAccounts_perAccountOrdering (Order=3) in EventSourcingCQRSDemoTest
     → RED: no OFFSET_WATERMARK topic setup, no messageGroup on producers
  2. Write PartitionedOrderingDemoTest (new file) — tests 2a–2d (see Phase 3)
     → RED: class does not exist

  3. The existing testCQRS (Order=2) is already intermittently RED.
     Confirm by running 10 times before any code change.

GREEN PHASE — implement minimum change to pass:
  4. Add outbox_topics OFFSET_WATERMARK row in @BeforeEach (or per-test setup)
  5. Change testCQRS event producer: send with messageGroup = event.getAggregateId()
  6. Change testCQRS event consumer: ConsumerGroup + start(SubscriptionOptions)
  7. Implement PartitionedOrderingDemoTest scaffolding (container, helpers)
  8. Implement test bodies 2a–2d

VERIFY:
  9. testCQRS passes 10/10 consecutive runs
  10. testCQRS_multipleAccounts_perAccountOrdering passes 10/10
  11. All PartitionedOrderingDemoTest tests pass deterministically
  12. testEventSourcing (Order=1) still passes — no regression
```

---

### Phase 1: Documentation

**New Guide**: `docs/PEEGEEQ_ORDERING_PATTERNS_GUIDE.md`

**Sections**:
1. **Understanding PeeGeeQ's Ordering Guarantees**
   - Storage order vs processing order
   - Why `FOR UPDATE SKIP LOCKED` breaks ordering
   - When ordering matters

2. **Consumer Mode Comparison**
   - Simple Consumer (`MessageConsumer`) — use cases
   - Partitioned Consumer (`ConsumerGroup` + OFFSET_WATERMARK) — use cases
   - Decision matrix

3. **Pattern 1: Simple Consumer + Idempotency**
   - When to use (metrics, logs, notifications)
   - Implementation examples
   - Trade-offs

4. **Pattern 2: Partitioned Consumption**
   - When to use (event sourcing, state machines, financial)
   - Setup steps (topic configuration, partition keys, `messageGroup` on every send)
   - Implementation examples
   - The `__default__` partition and why to avoid it
   - Rebalancing behaviour

5. **Migration Guide**
   - Simple → Partitioned
   - Choosing partition keys
   - Testing strategies

---

### Phase 2: Fix `testCQRS` in `EventSourcingCQRSDemoTest`

**File**: `peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/nativequeue/EventSourcingCQRSDemoTest.java`

**Changes** (all inside `testCQRS`, Order=2):

1. Before creating producers/consumers, insert the event queue topic into `outbox_topics` as `OFFSET_WATERMARK`
2. Change all `eventProducer.send(event)` calls to `eventProducer.send(event, headers, correlationId, event.getAggregateId())`
3. Replace `MessageConsumer<DomainEvent> eventConsumer` with `ConsumerGroup<DomainEvent> eventGroup`
4. Replace `eventConsumer.subscribe(...)` with `eventGroup.setMessageHandler(...)` + `eventGroup.start(options).onFailure(...)`
5. Replace `eventConsumer.close()` with `eventGroup.stopGracefully()` (returns `Future<Void>`)
6. Remove the version guard from `AccountReadModel.applyEvent()` — it is no longer needed; ordering is guaranteed

Also add `testCQRS_multipleAccounts_perAccountOrdering` (Order=3) to verify the pattern generalises across multiple aggregates processed concurrently.

---

### Phase 3: New Example — `PartitionedOrderingDemoTest`

**File**: `peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/nativequeue/PartitionedOrderingDemoTest.java`

**Tag**: `@Tag(TestCategories.INTEGRATION)` + `@Testcontainers`  
**Container**: `SharedTestContainers.getSharedPostgreSQLContainer()`

| Test | Method | What it proves |
|---|---|---|
| 2a | `testPartitionedOrdering_eventsPerAggregateInOrder` | 3 accounts × 5 events = 15 messages. Per-account version order is strictly ascending at the consumer. |
| 2b | `testPartitionedOrdering_differentAggregatesProcessedConcurrently` | 2 accounts × 3 slow events each (100ms delay in handler). Total elapsed < 2 × single-account time. |
| 2c | `testPartitionedOrdering_defaultPartition_noMessageGroup` | Producer sends without `messageGroup`. Topic is OFFSET_WATERMARK. Assert `outbox_partition_assignments` contains exactly one row with `partition_key = '__default__'`; all 5 messages received in order. |
| 2d | `testPartitionedOrdering_idempotentRedelivery` | Consumer group processes batch; same group restarted without offset reset. Verifies committed offset prevents re-delivery. |

---

### Phase 4: Safety Test — Handler Failure Mid-Batch

**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PartitionedConsumerSafetyIntegrationTest.java`

**Add test**: `testHandlerFailureMidBatch_offsetNotAdvanced_inProgressReleased`

The existing safety tests cover close-during-startup, stop-failure-logging, and the overlapping-fetch guard. None test handler failure mid-batch.

**What to assert**:
- Insert 5 messages in one partition
- Handler returns `Future.succeededFuture()` for messages 1–2, `Future.failedFuture(...)` for message 3
- After the fetch cycle completes, `committed_offset` must remain at its pre-batch value (offset did not advance past the failure)
- `fetchInProgress` for that partition must be `false` (released by `.eventually()`) so the next cycle runs

This validates the `processAndCommit` → `.eventually(() -> inProgress.set(false))` contract under failure.

---

### Phase 5: User Guide Updates

**Files to Update**:
- `docs/PEEGEEQ_COMPLETE_GUIDE.md` — Add "Ordering Patterns" section
- `docs/PEEGEEQ_EXAMPLES_GUIDE.md` — Reference new examples
- `docs/PEEGEEQ_ARCHITECTURE_API_GUIDE.md` — Document `PartitionedConsumerEngine`

---

## Success Criteria

### Functional

- ✅ `testCQRS` (Order=2) in `EventSourcingCQRSDemoTest` passes 10/10 consecutive runs
- ✅ `testEventSourcing` (Order=1) still passes — no regression
- ✅ `testCQRS_multipleAccounts_perAccountOrdering` (Order=3) passes 10/10 consecutive runs
- ✅ `PartitionedOrderingDemoTest` 2a–2d all pass deterministically
- ✅ `testHandlerFailureMidBatch_offsetNotAdvanced_inProgressReleased` passes
- ✅ No version-based event rejections — `AccountReadModel.applyEvent()` version guard removed
- ✅ Test logs show partition assignments and per-partition ordered delivery

### Educational

- ✅ Users understand when to use each consumer mode
- ✅ `messageGroup` requirement for OFFSET_WATERMARK is clearly documented
- ✅ `__default__` partition behaviour and its throughput implications are documented
- ✅ Clear guidance on partition key selection
- ✅ `PartitionedOrderingDemoTest` demonstrates per-key ordering + cross-partition concurrency
- ✅ Migration path documented

### Technical

- ✅ No new production code required (uses existing `PartitionedConsumerEngine`)
- ✅ Backward compatible (existing simple consumers still work; REFERENCE_COUNTING topics unchanged)
- ✅ Performance characteristics documented (concurrency across partitions, serial per partition)
- ✅ Rebalancing behaviour tested (existing 6.5/6.6 in `PartitionedNativeConsumerIntegrationTest`)

---

## Open Questions for Review

1. **Should OFFSET_WATERMARK become the default for new topics?**
   - Pro: Better guarantees out of the box
   - Con: Requires partition key discipline on every `send()` call; a single `send()` without `messageGroup` creates an unintended `__default__` serial partition
   - **Recommendation**: Keep REFERENCE_COUNTING as default. Make OFFSET_WATERMARK an explicit opt-in via `outbox_topics` configuration. This forces deliberate choice and prevents accidental serial bottlenecks.

2. **How should partition keys be chosen?** (answered)
   - Aggregate ID — recommended for event sourcing and CQRS
   - User ID — for per-user workflows
   - Tenant ID — for per-tenant isolation (complements multi-tenant schema isolation)
   - Custom business key — any key that defines the ordering boundary
   - **Rule**: the partition key must be the entity whose events must be strictly ordered relative to each other

3. **Should we provide automatic partition key extraction?** (deferred)
   ```java
   // Proposed annotation approach — deferred to future iteration:
   producer.send(event, headers, correlationId, event.getAggregateId());
   // is the correct pattern now; annotation extraction adds complexity for marginal gain
   ```

4. **What about cross-partition transactions?** (out of scope for this phase)
   - Currently: each partition processes independently
   - Future consideration: distributed transaction coordination across partitions
   - Not required for event sourcing or CQRS patterns addressed here

5. **Performance impact of partitioned consumption?** (partially answered by existing tests)
   - `PartitionedNativeConsumerIntegrationTest` test 6.6 confirms parallel partitions across 2+ instances
   - `PartitionedOrderingDemoTest` test 2b adds a concurrency timing assertion
   - Detailed benchmark with multiple partition counts is deferred to the performance module

---

## References

### Existing Implementation

- **`PartitionedConsumerEngine.java`** - Main coordination logic
- **`PartitionAssignmentService.java`** - Partition assignment & rebalancing
- **`PartitionedOffsetManager.java`** - Offset tracking & commits
- **`PartitionedFetcher.java`** - Per-partition message fetching
- **`WatermarkCalculator.java`** - Cleanup coordination

### Design Documents

- **`PEEGEEQ_OUTBOX_PARTITIONED_ORDERING_COMPLETE_GUIDE.md`** - Original ordering design
- **`PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md`** - Consumer group architecture
- **`CONSUMER_GROUP_OUTSTANDING_TASKS.md`** - OFFSET_WATERMARK implementation status

### Database Schema

- **`V010__Create_Consumer_Group_Fanout_Tables.sql`** - Subscription tracking
- **`outbox_partition_assignments`** table - Partition ownership
- **`outbox_partition_offsets`** table - Per-partition offset cursors
- **`outbox_topic_watermarks`** table - Cleanup boundaries

---

## Appendix: Comparison Table

| Aspect | Simple Consumer | Partitioned Consumer (OFFSET_WATERMARK) |
|--------|----------------|----------------------------------------|
| **Setup Complexity** | Low (2 lines) | Medium (topic config + partition keys) |
| **Ordering Guarantee** | None | Per-partition strict ordering |
| **Throughput** | High (fully concurrent) | High (concurrent across partitions) |
| **Latency** | Low (immediate processing) | Low (batch processing per partition) |
| **Scalability** | Horizontal (add consumers) | Horizontal (rebalance partitions) |
| **Idempotency Requirement** | Must handle out-of-order + duplicates | Must handle duplicates only |
| **State Management** | Stateless consumer | Offset cursors persisted |
| **Failure Recovery** | Re-fetch from DB | Resume from last committed offset |
| **Use Cases** | Metrics, logs, notifications | Event sourcing, state machines, financial |
| **Production Readiness** | ✅ Fully production-ready | ✅ Fully implemented & tested |

---

## Approval & Sign-Off

**Review completed**: April 27, 2026 — corrections applied in v1.1

**Corrections applied in this revision**:
- Retargeted failure analysis from `testEventSourcing` to `testCQRS` (the actual failing test)
- Fixed `fetchPartition()` code snippet: `computeIfAbsent` not `get()` (would NPE in original)
- Fixed Partition Assignment pseudocode: `start(SubscriptionOptions)` returns `Future<Void>`; no-arg `start()` returns void
- Added `__default__` partition section — critical for correct OFFSET_WATERMARK usage
- Fixed Option 1 consumer code: `start(options).onFailure(...)` chaining
- Replaced flat Implementation Plan with TDD-ordered phases
- Removed `IdempotentConsumerDemoTest` (wrong pattern for event sourcing)
- Added `PartitionedConsumerSafetyIntegrationTest` handler failure mid-batch test
- Updated Success Criteria and Open Questions

**Awaiting Sign-Off**: Mark Andrew Ray-Smith

**Key Decisions Required**:
1. Approve Option 1 (OFFSET_WATERMARK + `messageGroup` on producer) for `testCQRS`?
2. Approve removal of version guard from `AccountReadModel.applyEvent()`?
3. Confirm REFERENCE_COUNTING stays as the default topic mode?
4. Approve TDD phase ordering before implementation begins?
