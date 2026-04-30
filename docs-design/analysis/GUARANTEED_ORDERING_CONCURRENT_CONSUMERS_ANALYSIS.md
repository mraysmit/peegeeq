# Guaranteed Ordering for Concurrent Consumers - Architecture Design

**Author**: Mark A Ray-Smith 
**Date**: April 29, 2026  
**Status**: DECIDED — v1.4 (Decision 1 adopted; Decision 2 deferred to a future phase)  
**Version**: 1.4  
**Priority**: P1 (Architectural Documentation & Pattern Guidance)

---

## Executive Summary

**Problem**: PeeGeeQ achieves high throughput via `FOR UPDATE SKIP LOCKED`, which enables concurrent message processing but breaks ordering — message N+1 can complete before message N. This causes intermittent failures in order-sensitive workloads such as event sourcing, state machines, and financial transactions.

**Current State**: PeeGeeQ **already implements** the core solution (OFFSET_WATERMARK mode with `PartitionedConsumerEngine`). The infrastructure provides per-partition ordering today, but it ships with documented operational caveats (see *Operational Caveats & Lifecycle* below) and it is:

- not demonstrated in any example,
- not documented in user guides, and
- not used by `EventSourcingCQRSDemoTest`, which consequently fails intermittently.

Known caveats (do **not** treat OFFSET_WATERMARK as a turnkey "production-ready" subsystem until these are accounted for):

- **Partition discovery is point-in-time at join.** `PartitionAssignmentService.joinGroup` discovers partitions from existing `PENDING`/`PROCESSING` rows; new `message_group` values created after join are not assigned until the next rebalance event.
- **The engine does not currently emit heartbeats.** `PartitionedConsumerEngine` never calls `PartitionAssignmentService.heartbeat(...)`, so `last_heartbeat_at` reflects assignment time, not consumer liveness.
- **OFFSET_WATERMARK guarantees ordering, not deduplication.** At-least-once delivery still applies; consumers must remain idempotent.

**Proposed Solution**:

1. Document the **two consumer modes** with clear use-case guidance.
2. Demonstrate partition-based ordering in `EventSourcingCQRSDemoTest`.
3. Add a comprehensive ordering patterns guide.
4. Provide a migration path for existing applications.

**Impact**: Guaranteed per-partition ordering while preserving concurrent throughput across partitions.

---

## Problem Analysis

### The Core Trade-Off: Design Choice, Not Technical Limitation

PeeGeeQ is designed for **throughput over ordering**. A simple `MessageConsumer` could in principle fetch and process messages strictly sequentially, but omitting that behaviour is a deliberate **architectural choice**, not a missing feature.

If simple consumers enforced global strict ordering:

- Scaling to multiple instances would cause severe **head-of-line blocking** — Consumer A processing Message 1 would block Consumer B from accessing Message 2.
- `FOR UPDATE SKIP LOCKED` would lose its primary benefit: concurrent, lock-free throughput.

PeeGeeQ therefore divides consumer responsibilities cleanly:

- **Simple Consumer (`MessageConsumer`)** — optimised for raw concurrent throughput. No coordination, no ordering guarantees, even when running as a single instance.
- **Consumer Group (`ConsumerGroup`)** — optimised for coordinated state. Accepts the overhead of partition assignment, exclusivity, and fencing in exchange for a mathematical guarantee of order per partition.

| Optimisation | Benefit | Cost |
|---|---|---|
| `FOR UPDATE SKIP LOCKED` | Multiple consumers grab different messages | Storage order ≠ processing order |
| Vert.x reactive futures | Concurrent async processing | Future completion order is unpredictable |
| At-least-once delivery | No message loss | Application must handle duplicates and out-of-order arrival |

This split is intentional and correct for most use cases. Some workloads, however, need **strict ordering**.

### When Ordering Matters

**Requires ordering**:

- Event sourcing (version-based projections)
- State machines (transitions must be sequential)
- Financial transactions (per-account ordering)
- Aggregate rehydration (events applied in sequence)

**Tolerates out-of-order**:

- Metrics aggregation
- Log collection
- Notification delivery
- Cache invalidation

### EventSourcingCQRSDemoTest — Failure Analysis

The intermittent failure is in **`testCQRS` (Order=2)**, not `testEventSourcing` (Order=1). `testEventSourcing` stores raw events in a `List`, sorts by version before asserting, and never applies events to a read model — it is therefore not order-sensitive.

**Failing test code** (current `testCQRS`):

```java
// Creates a simple MessageConsumer (no ordering guarantee)
MessageConsumer<DomainEvent> eventConsumer = queueFactory.createConsumer(eventQueue, DomainEvent.class);

// READ SIDE — updates read model from events
eventConsumer.subscribe(message -> {
    DomainEvent event = message.getPayload();
    readModelAggregate.applyEvent(event);  // Version-based guard fails on out-of-order arrival
    return Future.succeededFuture();
});
```

**Version guard** in `AccountReadModel.applyEvent()`:

```java
public void applyEvent(DomainEvent event) {
    if (event.getVersion() <= lastProcessedVersion) {
        return; // silently skipped when out-of-order
    }
    // ... update fields ...
    this.lastProcessedVersion = event.getVersion();
}
```

**What happens**:

1. The consumer fetches a batch: `[v1, v2, v3, v4]`.
2. All four events process concurrently — multiple futures in flight.
3. `v4` completes first, so `lastProcessedVersion = 4`.
4. `v1`, `v2`, `v3` arrive later and are all rejected by `if (version <= 4)`.
5. `totalTransactions` stays at 1; the balance reflects only the first event applied.
6. The test fails: `assertEquals(2050.0, readAggregate.currentBalance)` — the actual value is wrong.

**Why the version guard cannot fix this**:

- The guard is a duplicate-detection fence, not an ordering mechanism.
- It assumes strictly increasing arrival order, which `FOR UPDATE SKIP LOCKED` does not provide.
- Sorting or resequencing inside the consumer is too complex and defeats the purpose of the queue.

---

## Existing Solution: OFFSET_WATERMARK Mode

PeeGeeQ already has the infrastructure for guaranteed ordering via **partitioned consumption**.

### Architecture Overview

All four components are already implemented:

1. **`PartitionedConsumerEngine`** — `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PartitionedConsumerEngine.java`
   Coordinates partition assignment, fetching, and offset commits. Processes each partition **sequentially** (no concurrent futures for the same partition).

2. **`PartitionAssignmentService`** — `peegeeq-db/src/main/java/dev/mars/peegeeq/db/consumer/PartitionAssignmentService.java`
   Assigns partitions to consumer instances and rebalances when consumers join or leave.

3. **`PartitionedOffsetManager`** — `peegeeq-db/src/main/java/dev/mars/peegeeq/db/consumer/PartitionedOffsetManager.java`
   Tracks per-(group, partition) offset cursors so consumption is resumable from the last committed offset.

4. **`message_group` column** (database schema)
   Producers set the partition key via the `messageGroup` parameter. Messages sharing a `message_group` are guaranteed to be processed sequentially.

### How Ordering Is Guaranteed

The guarantee comes from a precise four-step mechanism that isolates partitions:

1. **Routing** — Producers send each message with a `messageGroup` (the partition key, e.g. `account-123`). All messages for `account-123` route to the same partition.
2. **Exclusive ownership** — `PartitionAssignmentService` assigns each partition to **exactly one consumer instance** in the group. No two consumers ever overlap on `account-123`. (See *How exclusive ownership is enforced* below for the four database-level mechanisms that combine to make this safe.)
3. **Sequential processing loop** — Inside the assigned consumer, `PartitionedConsumerEngine` fetches messages for the partition in batches but processes them strictly one at a time, waiting for message N's `Future` to complete before dispatching message N+1.
4. **Guarded fetching** — The engine will not fetch the next batch for a partition until the current batch's offset is committed, enforced by an atomic `fetchInProgress` guard.

The code below shows how each step is realised.

#### How exclusive ownership is enforced

The "exactly one consumer instance per partition" claim is **not** a convention or in-memory belief. It is enforced by four cooperating mechanisms — three at the database level, one in the engine's read path. Each layer addresses a different failure mode.

##### Layer 1 — Schema uniqueness on the assignment row

The assignment table has a UNIQUE constraint on `(topic, group_name, partition_key)`:

```sql
-- peegeeq-migrations/src/main/resources/db/migration/V017__Create_Offset_Watermark_Tables.sql
CREATE TABLE IF NOT EXISTS outbox_partition_assignments (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    partition_key VARCHAR(255) NOT NULL,
    assigned_instance_id VARCHAR(255) NOT NULL,
    assigned_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_heartbeat_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    generation INT NOT NULL DEFAULT 1,
    UNIQUE(topic, group_name, partition_key)
);
```

Postgres physically prevents two rows from claiming the same `(topic, group, partition)` triple. Even in the presence of a coding bug that tried to insert two assignments, the second `INSERT` would fail with a unique-constraint violation. Ownership is a database invariant, not an application invariant.

##### Layer 2 — Serialized rebalance via `FOR UPDATE` on the subscription row

All `joinGroup` / `leaveGroup` calls funnel through `PartitionAssignmentService.lockAndBumpGeneration(...)`, which executes inside the same transaction as the rest of the rebalance:

```sql
-- PartitionAssignmentService.lockAndBumpGeneration
UPDATE outbox_topic_subscriptions
SET rebalance_generation = rebalance_generation + 1
WHERE topic = $1 AND group_name = $2
RETURNING rebalance_generation
```

The implicit row lock acquired by `UPDATE` is held until commit. Two simultaneous joins for the same `(topic, group)` queue behind this row lock and execute strictly one at a time. The entire rebalance — `DELETE FROM outbox_partition_assignments`, `INSERT` the new assignments, `UPDATE outbox_partition_offsets SET generation = ...` — runs inside a single transaction (`connectionManager.withTransaction`), so a partially rebalanced state is never visible to readers.

##### Layer 3 — Generation fencing on every offset write

A rebalance increments `rebalance_generation` and also bumps every offset row's `generation`:

```sql
-- PartitionAssignmentService.bumpOffsetGenerations
UPDATE outbox_partition_offsets
SET generation = $3,
    pending_offset = NULL,
    pending_since = NULL
WHERE topic = $1 AND group_name = $2
```

Both `setPendingOffset` and `commitOffset` (in `PartitionedFetcher` and `PartitionedOffsetManager` respectively) include `AND generation = $X` in their `WHERE` clause. The generation an instance carries was captured at its last `joinGroup`. After a rebalance, that value is stale, and every subsequent CAS update by the displaced instance affects zero rows:

```sql
-- PartitionedOffsetManager.commitOffset
UPDATE outbox_partition_offsets
SET committed_offset = $4, committed_at = NOW(),
    pending_offset = NULL, pending_since = NULL
WHERE topic = $1 AND group_name = $2 AND partition_key = $3
  AND generation = $5
  AND committed_offset < $4
```

The engine observes `rows.rowCount() == 0` and logs `"Offset commit rejected (stale generation)"`. A displaced consumer cannot poison the offset cursor of the new owner, even if it processes messages fetched just before the rebalance.

##### Layer 4 — Cooperative read isolation via the engine + `SKIP LOCKED`

`PartitionedConsumerEngine.fetchAllPartitions()` iterates only over its in-memory `assignedPartitions` map, which was populated from the engine's own `joinGroup` result. So an instance never *attempts* to fetch a partition that is not assigned to it.

For the brief overlap window during a rebalance — where the previous owner has not yet noticed it has been displaced — the fetch query itself adds a second guard:

```sql
-- PartitionedFetcher.fetchMessages
SELECT o.id, o.topic, o.payload, ...
FROM outbox o
WHERE o.topic = $1
  AND o.id > $2
  AND o.status IN ('PENDING', 'PROCESSING')
  AND o.message_group = $3
ORDER BY o.id ASC
LIMIT $4
FOR UPDATE OF o SKIP LOCKED
```

`FOR UPDATE OF o SKIP LOCKED` ensures that even if both the old and new owner momentarily issue a fetch for `account-123`, they cannot both lock the same `outbox` row. One acquires the row lock; the other skips it. Combined with Layer 3, any messages the displaced instance still manages to process produce a stale-generation offset commit that is silently rejected, so the new owner re-fetches them and processes them in order under the new generation. **Per-partition order is preserved end-to-end across rebalance windows.**

##### What this combination guarantees — and what it does not

- **Guaranteed**: at any committed point in time, exactly one assignment row exists per `(topic, group, partition)`, and exactly one instance can advance the offset cursor for that partition.
- **Guaranteed**: per-partition message order is preserved across rebalance windows, because `SKIP LOCKED` prevents double row-locking and generation fencing prevents stale commits.
- **Not guaranteed**: that during a rebalance window, only one instance is *executing handler code* for the partition. A handler invocation that was already in flight when ownership transferred will run to completion. This is why consumer handlers must be idempotent (see Phase 2 step 7 and the Appendix row "Idempotency requirement").
- **Not guaranteed**: that the assignment row reflects current liveness. `last_heartbeat_at` is updated on assignment but is not currently refreshed by the engine (see *Operational Caveats & Lifecycle §2*).

The four layers together turn "exclusive ownership" from a hopeful invariant into one enforced by database constraints (Layer 1), transactional serialization (Layer 2), CAS fencing (Layer 3), and row-level locking (Layer 4).

##### Walkthrough: what happens when two consumers claim the same partition at runtime

Two consumers ending up nominally "owning" the same partition is not an exotic edge case. It can be triggered by:

- **A natural rebalance window** — instance B has just joined, instance A has not yet noticed it has been displaced.
- **A consumer-side bug** — application code holds a stale `assignedPartitions` snapshot, or two threads inside the same JVM construct two engines for the same `(topic, group, instanceId)`.
- **A misconfigured deployment** — the same `instanceId` is reused across two pods, or a pod's old container has not yet exited when the new one starts.
- **A network partition** that briefly hides instance A from the database; A continues fetching while B was promoted.

This subsection traces what actually happens in the implementation under each scenario. It is not theoretical — every step references a specific SQL statement or method already shown above.

###### Scenario A — Two consumers join simultaneously (race at join time)

Both call `assignmentService.joinGroup(topic, group, instanceId)` at the same moment. Each call runs inside its own `connectionManager.withTransaction(...)`.

1. **Both transactions race for the subscription row lock.** The first statement in each transaction is the `UPDATE outbox_topic_subscriptions SET rebalance_generation = rebalance_generation + 1 ... RETURNING rebalance_generation` shown in Layer 2. Postgres takes a row-level lock as part of `UPDATE`. **Only one transaction acquires it.** The other blocks on that row lock.
2. **Winner runs to completion.** It reads the partition list, computes consistent-hash assignments, deletes prior assignment rows, inserts new ones, bumps offset generations, commits. Generation is now `N+1`.
3. **Loser unblocks against the post-commit state.** Its `UPDATE ... RETURNING` returns generation `N+2`. It re-reads partitions and current instances (now including the winner), recomputes assignments — itself included — deletes the just-committed assignment rows, inserts a fresh set at generation `N+2`, and bumps offsets again.
4. **Net effect**: simultaneous joins are serialized by Postgres. There is no in-doubt state and no double-claim. The winner's view of the world is now stale (generation `N+1`); its next offset commit will be rejected (see Scenario B). The schema's `UNIQUE(topic, group_name, partition_key)` is the safety net if a coding bug ever bypassed Layer 2 — the second `INSERT` would fail with `23505` and the transaction would roll back cleanly.

###### Scenario B — Two consumers believe they own the same partition during runtime

A held `account-123` at generation `N`. B has just joined and now also believes it owns `account-123` at generation `N+1`. A's in-memory `assignedPartitions` map still has `account-123 → N`. For ~one fetch tick, both engines try to fetch.

**Step 1 — Both call `PartitionedFetcher.fetch(...)`.** Each issues the partition fetch query shown in Layer 4 — `SELECT ... FROM outbox WHERE topic=$1 AND id>$2 AND status IN ('PENDING','PROCESSING') AND message_group=$3 ORDER BY id ASC LIMIT $4 FOR UPDATE OF o SKIP LOCKED`.

The query is `SKIP LOCKED`, **not** `NOWAIT`. Both queries run concurrently. For each candidate `outbox` row:

- Whichever transaction reaches the row first acquires the row lock and returns it in its result set.
- The other transaction's query does not wait, does not error, and does not return that row. It silently skips.

So in the worst case A and B receive **disjoint** subsets of `[v1, v2, v3, v4]`. They do not double-process the same row inside the same fetch tick.

**Step 2 — Divergence at offset commit.** Suppose A locked `[v1, v2]` (generation `N`) and B locked `[v3, v4]` (generation `N+1`). They both process in id order and call `PartitionedOffsetManager.commitOffset(...)`, which is the CAS update shown in Layer 3.

The offset row's `generation` was just bumped to `N+1` by the rebalance. So:

- **A's commit (`generation = N`)** matches zero rows. `rowCount == 0`. `commitOffset` returns `false`. The engine logs `"Offset commit rejected (stale generation): topic=..., partition=account-123, gen=N"` (`PartitionedConsumerEngine.processAndCommit`). **`committed_offset` does not advance for A.**
- **B's commit (`generation = N+1`)** matches and advances `committed_offset` to `v4`'s id.

**Step 3 — What happens to v1 and v2 — A's "successfully processed" messages?** A's handler did run for v1 and v2. Their side effects (read-model updates, downstream publishes, etc.) are real — that is why handlers must be idempotent. Because A's offset commit was rejected, the cursor stays at its pre-rebalance value. On the next fetch tick under generation `N+1`, B fetches `[v1, v2, v3, v4]` again starting from that pre-rebalance offset, processes them all in `ORDER BY id ASC`, and commits at generation `N+1`.

**Step 4 — End result**:

| Property | Outcome | Enforced by |
|---|---|---|
| Row-level safety | Never double-locked in the same instant | Layer 4 — `SKIP LOCKED` |
| Cursor safety | Cursor only advances under the current generation | Layer 3 — generation fencing on CAS |
| Order safety | B re-reads from the cursor's pre-rebalance value and processes in `id` order | Layer 4 fetch + Layer 3 fence combined |
| **Idempotency cost** | A's handler may have applied v1/v2 once; B will apply them again | **Application's responsibility** |

The application sees those messages **at-least-once with duplicates**, but in order. This is exactly the "ordering guaranteed; deduplication is the consumer's job" contract.

###### Scenario C — Consumer-side bug: stale or duplicated engine in the same process

If application code (or a test harness) accidentally constructs two `PartitionedConsumerEngine` instances for the same `(topic, group, instanceId)`, or holds a stale assignment snapshot longer than expected, the behaviour reduces to Scenario B. Both engines independently call `joinGroup`; the second join produces a new generation; the first engine's subsequent commits are rejected. No data is lost, no row is double-locked, but duplicate handler invocations occur and the application must remain idempotent.

If the duplication is across processes (e.g., two pods sharing an `instanceId`), the consistent-hash assignment treats them as a single instance (same `instanceId` collapses in the `LinkedHashSet`). They will *both* receive the same partition list, neither knows about the other, and both fetch concurrently. Layer 4 still prevents double row-locks; Layer 3 still prevents double commits — but only one set of commits will succeed at any given time, and which side "wins" is non-deterministic. **Reusing `instanceId` across processes is unsupported and should be considered an operational incident**, not a configuration option. A future enhancement could add a uniqueness check via `last_heartbeat_at` once the engine emits heartbeats (see *Operational Caveats & Lifecycle §2*).

###### Pathological sub-case: stale instance keeps fetching forever

Today's engine does not detect that its generation is stale. It will keep:

- fetching successfully (stealing some rows via `SKIP LOCKED`),
- processing them (causing duplicate downstream effects),
- failing every offset commit with a `"stale generation"` log line.

It will not recover until the process restarts or another join/leave triggers a fresh rebalance that this instance participates in. **This is a real, documented gap.** It is the runtime sibling of the missing-heartbeat issue noted in *Operational Caveats & Lifecycle §2*: there is no "I have been fenced, refresh my assignment" path. Mitigations operators can apply today:

- Alert on the `"Offset commit rejected (stale generation)"` log line — sustained occurrence indicates a fenced instance.
- Restart the affected consumer process; on restart its fresh `joinGroup` produces a current generation.
- Avoid configurations that produce repeated rebalances (flapping pods, frequent scale-up/scale-down).

A follow-up design (Open Question 6) should add either (a) a periodic generation-refresh inside the engine that re-runs `joinGroup` when commits are rejected, or (b) an explicit `engine.refreshAssignments()` API that operators or supervisors can call on detecting fence events.

---

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
        return;  // Previous batch still processing — skip this cycle
    }

    fetcher.fetch(topic, groupName, partitionKey, batchSize, generation)
        .compose(messages -> processAndCommit(messages, partitionKey, generation))
        .eventually(() -> {
            inProgress.set(false);  // Release lock for next cycle
            return Future.succeededFuture();
        });
}
```

The `inProgress` guard prevents overlapping fetch cycles for the same partition.

#### 3. Sequential Processing Within a Batch

```java
// PartitionedConsumerEngine.processAndCommit():
private Future<Void> processAndCommit(List<OutboxMessage> messages, String partitionKey, int generation) {
    // Process messages **sequentially** in order (NOT concurrently)
    Future<Void> chain = Future.succeededFuture();
    for (OutboxMessage msg : messages) {
        chain = chain.compose(v -> dispatchMessage(msg));  // Sequential chaining
    }
    
    // Commit offset only after every message is processed
    return chain.compose(v -> offsetManager.commitOffset(...));
}
```

The `.compose()` chain forces sequential execution: message N+1 cannot start until message N has completed.

---

### The `__default__` Partition — Critical Behaviour

When a producer calls `send(payload)` or `send(payload, headers, correlationId)` without a `messageGroup`, the message is stored with `message_group = NULL`.

`PartitionAssignmentService.discoverPartitionsInternal()` maps `NULL` to the synthetic key `__default__`:

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

**This is the single most common misconfiguration.** Configuring a topic as OFFSET_WATERMARK without passing a `messageGroup` on every `send()` funnels all messages through `__default__` and processes them serially, eliminating the throughput benefit of partitioning.

**Rule**: Always set `messageGroup = aggregateId` (or the equivalent business key) when using OFFSET_WATERMARK. The `__default__` partition is a safety net, not a recommended usage.

> **Caveat**: The `__default__` partition is also subject to the discovery gap described below — it is only assigned to an instance after at least one row with `message_group IS NULL` exists for the topic at the time `joinGroup` runs.

---

### Operational Caveats & Lifecycle

The partitioned engine is correct for the cases it covers, but its lifecycle has constraints that must be designed around. These are not bugs; they are the current contract of `PartitionAssignmentService` + `PartitionedConsumerEngine`.

#### 1. Partition discovery is point-in-time at join

`PartitionAssignmentService.discoverPartitionsInternal` runs this query inside the join transaction:

```sql
SELECT DISTINCT COALESCE(message_group, '__default__') AS partition_key
FROM outbox
WHERE topic = $1
  AND status IN ('PENDING', 'PROCESSING')
ORDER BY partition_key
```

If no rows match at the moment of join, `joinGroup` returns an empty assignment list and `PartitionedConsumerEngine.assignedPartitions` stays empty. The engine's fetch loop iterates only over `assignedPartitions`, so a consumer that started before any producers will sit idle until a rebalance event re-runs discovery.

A rebalance is currently triggered only by another `joinGroup` or `leaveGroup`; there is no periodic rediscovery.

**Implications for application code**:

- Ensure at least one `PENDING` message exists for each intended partition key **before** the first consumer joins, OR
- Defer `consumerGroup.start(...)` until after the first event for each partition has been published, OR
- Trigger a rebalance after publishing (e.g., bring up a second instance, then drain it).

#### 2. The engine does not heartbeat

`PartitionAssignmentService.heartbeat(topic, group, instance)` exists and updates `outbox_partition_assignments.last_heartbeat_at`, but `PartitionedConsumerEngine` never calls it. Operators relying on `last_heartbeat_at` to detect zombie instances or to reap stale assignments cannot do so today.

#### 3. New partition keys after join require a rebalance

A `message_group` value that first appears in `outbox` *after* the consumer instance has joined the group is not in `assignedPartitions`. The engine will not fetch for it until a rebalance redistributes partitions. Until that happens, those rows accumulate in `PENDING`.

#### 4. At-least-once delivery still applies

OFFSET_WATERMARK guarantees per-partition order. It does **not** guarantee exactly-once. Duplicates can occur on consumer restart before offset commit, on stale-generation rejection followed by retry, and on rebalance windows. Consumer logic must remain idempotent — see *Phase 2, step 6* below for the recommended idempotency-fence pattern.

#### 5. Partitions with all-COMPLETED rows are invisible at join time

`PartitionAssignmentService.discoverPartitionsInternal` filters `status IN ('PENDING', 'PROCESSING')`. A `message_group` whose rows have all transitioned to `COMPLETED` (or `FAILED`, `DEAD_LETTER`) by the time a consumer joins will not appear in the partition-discovery query. The engine will not assign or fetch for it.

When a new message with that same `message_group` is later published, it creates a `PENDING` row. If no rebalance has occurred since join, no instance has `account-123` in its `assignedPartitions` map, and the row accumulates in `PENDING` indefinitely — until another join or leave event triggers rediscovery.

**This is the same class of problem as caveats §1 and §3.** All three share the same root cause: discovery is point-in-time, not continuous.

**Implications for application code**:

- Do **not** assume that a previously active partition remains assigned after all its messages drain. The assignment may be dropped on the next rebalance if discovery sees no `PENDING`/`PROCESSING` rows at that moment.
- For long-lived aggregates that may go quiet and resume later, the safest model is to accept the rebalance latency and rely on a subsequent join/leave to re-surface the partition. Alternatively, keep at least one `PROCESSING`-status sentinel row alive during quiet periods (not recommended in most designs — document the trade-off if used).
- If/when periodic rediscovery (Open Question 6) is implemented, this caveat and caveats §1/§3 are addressed together. Any change to that behaviour must update all three entries here.

---

### Decision 1: Adopt OFFSET_WATERMARK Mode ⭐

**Status**: **✅ adopted — implement in Phases 1–5.**

**Approach**: Migrate `EventSourcingCQRSDemoTest` to use a partitioned consumer group.

**Required changes**:

1. **Configure the topic for OFFSET_WATERMARK**:

```sql
INSERT INTO outbox_topics (topic, completion_tracking_mode)
VALUES ('account-events', 'OFFSET_WATERMARK')
ON CONFLICT (topic) DO UPDATE
SET completion_tracking_mode = 'OFFSET_WATERMARK';
```

2. **Producer — set partition key (and compose the send future)**:

```java
// Use the aggregate ID as partition key
String partitionKey = command.getAggregateId();  // e.g., "account-123"

// send(...) returns Future<Void>. Return it: the command handler's own future
// must not complete until the event is durably persisted. .onFailure is a
// side-effect terminal handler; use .compose(...) if further chaining is needed.
return eventProducer.send(event, headers, correlationId, partitionKey)
        .onFailure(err -> logger.error("Failed to publish event {}: {}",
                event.getEventId(), err.getMessage()));
```

> **Reactive-only rule**: Never discard the `Future<Void>` returned by `send(...)`. Without composition, the command handler can complete before the event is persisted, breaking the CQRS contract.

3. **Consumer — use a partitioned group**:

```java
// Create a consumer group via QueueFactory (NOT a simple MessageConsumer)
ConsumerGroup<DomainEvent> eventGroup = queueFactory.createConsumerGroup(
    "read-model-updaters",
    eventQueue,
    DomainEvent.class
);

// Set message handler (required before start)
eventGroup.setMessageHandler(message -> {
    DomainEvent event = message.getPayload();
    // message.getMessageGroup() == event.getAggregateId() — same partition key as producer

    AccountReadModel readModel = readModels.get(event.getAggregateId());
    readModel.applyEvent(event);  // ✅ Guaranteed in-order per account

    eventsProcessed.incrementAndGet();
    eventCheckpoint.flag();
    return Future.succeededFuture();
});

// start(SubscriptionOptions) returns Future<Void>; always chain onFailure
SubscriptionOptions options = SubscriptionOptions.builder()
    .startPosition(StartPosition.FROM_BEGINNING)
    .build();
eventGroup.start(options)
    .onFailure(err -> testContext.failNow(err));
```

**Why this works**:

- ✅ All events for the same account go to the same partition.
- ✅ Each partition is processed sequentially — no concurrent futures for that partition.
- ✅ Ordering is guaranteed within a partition.
- ✅ Concurrency is preserved across partitions (`account-123` and `account-456` run in parallel).
- ✅ Adding more consumers triggers rebalancing.

**Trade-offs**:

- More setup (topic configuration, partition keys).
- Requires understanding of partitioning concepts.
- Slightly larger test setup overhead (schema initialisation).

---

### Decision 2 (Future Phase): Exclusive Consumer for Total Global Ordering

**Status**: **deferred to a future phase.** Design captured below; not in scope for the current implementation plan. See *Decision* and *Phase 6 (Deferred)* below for sequencing.

**Approach**: Use PostgreSQL session-level advisory locks to guarantee that **at most one** consumer instance can dequeue messages from a given topic at a time, enforcing strict sequential execution across **all** messages on that topic. Other instances of the same consumer remain hot-standby, ready to take over when the active session ends.

#### Motivation

OFFSET_WATERMARK provides *per-key* ordering: messages with the same `messageGroup` are processed sequentially, but different keys run concurrently. Some workloads need **total global ordering** across an entire topic — every message processed strictly in `id` order, with no concurrent dispatch — and cannot be partitioned at all:

- Append-only ledger streams where causality is global and not bound to a single aggregate.
- Migration / replay pipelines that must reproduce historical order exactly.
- Single-writer projection builders (one materialised view, one writer).
- Sequential workflow engines where step N+1 is meaningful only after step N has been observed.

A single `MessageConsumer` running today **cannot** guarantee this. `FOR UPDATE SKIP LOCKED` is by design cooperative: any second connection that runs the same dequeue query will steal rows the first consumer has not yet locked, instantly breaking global ordering. There is no application-level safeguard preventing a misconfigured second instance from connecting.

The proposal is to add an opt-in **Exclusive Consumer Model** that uses `pg_try_advisory_lock(...)` to make exclusivity a database-enforced invariant rather than a deployment convention.

#### Semantics

When a consumer is configured as exclusive on topic `T`:

1. **At-most-one-active**: only one consumer session in the cluster holds the topic's advisory lock and is permitted to dequeue. Other sessions enter standby.
2. **Strict sequential dispatch**: the active consumer processes messages with `consumerThreads = 1` and chained futures. Message N+1 is not dispatched until message N's handler `Future` resolves.
3. **Ordered fetch**: dequeue queries use `ORDER BY id ASC` (no `SKIP LOCKED` is needed for ordering, since exclusivity is already enforced).
4. **Active/passive HA**: standby instances poll for the lock at a configurable cadence. When the active session dies (process crash, network drop, graceful close), Postgres releases the session-level lock automatically, and one of the standbys acquires it on its next attempt.
5. **No silent fallback**: if exclusivity cannot be established and the configuration forbids standby (see `onLockUnavailable` below), `start()` fails with a typed exception rather than running concurrently.

Exclusivity is enforced **per topic**, not per consumer process. Multiple exclusive consumers on different topics in the same JVM coexist without contention; they take different lock keys.

#### Configuration API

The proposal extends the existing `ConsumerConfig` builder in `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/ConsumerConfig.java` with a new nested `ExclusiveConfig` value object. Keeping it nested (rather than as five flat fields) avoids polluting `ConsumerConfig` and keeps the default — non-exclusive — single-line opt-out.

```java
ConsumerConfig config = ConsumerConfig.builder()
    .mode(ConsumerMode.HYBRID)
    .batchSize(50)
    .exclusive(ExclusiveConfig.builder()
        .enabled(true)
        .lockKey(LockKey.fromTopic())                       // default: stable hash of topic name
        .lockAcquisitionInterval(Duration.ofSeconds(5))     // standby poll cadence
        .onLockUnavailable(OnLockUnavailable.STANDBY)       // STANDBY | FAIL_FAST
        .lockAcquisitionTimeout(Duration.ofMinutes(2))      // optional bounded wait
        .heartbeatInterval(Duration.ofSeconds(10))          // optional liveness ping
        .build())
    .build();
```

**`ExclusiveConfig` fields**:

| Field | Type | Default | Description |
|---|---|---|---|
| `enabled` | `boolean` | `false` | Master switch. When false, all other fields are ignored. |
| `lockKey` | `LockKey` | `LockKey.fromTopic()` | Strategy for deriving the 64-bit advisory lock key. See *Lock keying* below. |
| `lockAcquisitionInterval` | `Duration` | `5s` | How often standby instances retry `pg_try_advisory_lock`. |
| `onLockUnavailable` | enum | `STANDBY` | `STANDBY` = enter hot standby; `FAIL_FAST` = fail `start()` immediately. |
| `lockAcquisitionTimeout` | `Duration` | `null` (unbounded) | If non-null, a standby that fails to acquire within this window fails its `start()` future. |
| `heartbeatInterval` | `Duration` | `10s` | Active consumer issues a lightweight `SELECT 1` on the lock-holding connection at this cadence to keep the connection alive through NAT idle timers and proxy idle-connection reaping. |

**Validation rules** (added to `ConsumerConfig.Builder.build()`):

- `enabled = true` ⇒ `consumerThreads` **must be 1**. Multi-threaded exclusive consumption is a contradiction; throw `IllegalArgumentException` if violated.
- `enabled = true` is incompatible with topics configured as `OFFSET_WATERMARK`. The two ordering models must not be combined; throw `IllegalStateException` at `start()` after looking up the topic mode.
- `lockAcquisitionInterval` must be positive and ≤ 5 minutes.
- `heartbeatInterval` (if set) must be less than the connection idle timeout configured on the pool.

**`ConsumerConfig.Builder.exclusive(...)`** convenience overloads:

```java
// Shorthand for the most common case
.exclusive(true)
// equivalent to:
.exclusive(ExclusiveConfig.builder().enabled(true).build())
```

#### Lock keying

`pg_try_advisory_lock` takes a 64-bit `bigint`. Three keying strategies are proposed:

```java
public sealed interface LockKey {
    long resolve(String topic, String schema);

    /** Default. Hash of (schema, topic). Tenant-safe, stable across restarts. */
    static LockKey fromTopic() { ... }

    /** Explicit literal key. Use when coordinating exclusivity across multiple topics. */
    static LockKey ofLiteral(long key) { ... }

    /** Hash of an arbitrary user-supplied string. */
    static LockKey ofString(String namespace) { ... }
}
```

The default `fromTopic()` strategy hashes `schema + ":" + topic` so that:

- Tenants in different schemas never collide (matches the multi-tenant schema isolation rules).
- The same topic across consumer restarts always resolves to the same lock key.
- A 64-bit Murmur3 hash (the same hash already used by `PartitionAssignmentService`) is reused for consistency.

The literal-key strategy lets advanced users couple exclusivity across multiple topics — for example, "only one of `orders` or `order-cancellations` may run at a time" — by configuring both consumers with the same literal key.

#### Lifecycle and state machine

A new internal class, `ExclusiveConsumerCoordinator`, owns one dedicated `SqlConnection` for the lifetime of the consumer. The lock must be held on the same session that issues the dequeue queries; releasing the connection releases the lock, so this connection cannot be returned to the pool between batches.

State machine:

```
                    ┌────────────────┐
                    │   STARTING     │
                    └───────┬────────┘
                            │ acquire dedicated connection
                            ▼
                    ┌────────────────┐
        success     │  ACQUIRING     │   pg_try_advisory_lock(key) → false
       ◄────────────┤   (LOCK)       ├────────────────────────────►┐
                    └───────┬────────┘                              │
                            │ true                                  │
                            ▼                                       │
                    ┌────────────────┐                              │
                    │    ACTIVE      │                              │
                    │  (dispatch)    │                              │
                    └───────┬────────┘                              │
                            │ stop() / connection lost              │
                            ▼                                       │
                    ┌────────────────┐                              │
                    │   RELEASING    │                              │
                    └───────┬────────┘                              │
                            │                                       │
                            ▼                                       │
                    ┌────────────────┐    onLockUnavailable=        │
                    │    STOPPED     │    STANDBY: poll every       │
                    └────────────────┘    lockAcquisitionInterval ◄─┘
                                          FAIL_FAST: fail start()
```

Transitions:

1. **STARTING → ACQUIRING**: dedicated connection obtained from `PgConnectionManager`.
2. **ACQUIRING → ACTIVE**: `pg_try_advisory_lock(key)` returned `true`. Begin fetch loop with `consumerThreads = 1` and `ORDER BY id ASC`.
3. **ACQUIRING → ACQUIRING (retry)**: `pg_try_advisory_lock` returned `false`. Schedule a `vertx.setTimer(lockAcquisitionInterval, ...)` and retry. Emit a `consumer.exclusive.standby` metric.
4. **ACQUIRING → STOPPED (failure)**: `onLockUnavailable = FAIL_FAST` or `lockAcquisitionTimeout` exceeded. `start()` future fails with `ExclusiveConsumerLockUnavailableException`.
5. **ACTIVE → RELEASING**: `stop()` called, or heartbeat detected the connection is dead.
6. **RELEASING → STOPPED**: `pg_advisory_unlock(key)` issued (best effort), then connection closed. If the connection is already gone, Postgres releases the lock automatically.

All transitions return `Future<Void>` and use `.compose()` chaining — no callbacks, no blocking waits — to comply with the project's reactive-only rules.

#### Failure modes and fencing

The exclusive consumer must remain safe under the following failure scenarios:

| Scenario | Detection | Behaviour |
|---|---|---|
| Active consumer process crashes | TCP session ends; Postgres releases session-level lock automatically | A standby acquires the lock on its next retry. Maximum failover latency = `lockAcquisitionInterval` + RTT. |
| Network partition (active consumer cut off from DB) | Heartbeat `SELECT 1` fails, OR Postgres terminates the session via `tcp_keepalives_idle` | Active consumer transitions to STOPPED. Standby takes over once Postgres releases the lock. |
| Connection pool or proxy that does not preserve session lifetime | Advisory locks are session-scoped; returning the connection to a pool releases the lock silently | **Operator requirement**: the dedicated connection used by `ExclusiveConsumerCoordinator` must persist for the consumer's lifetime. Use a direct connection or a pool configured for session-level persistence. This is an operator responsibility — the engine cannot reliably detect whether a connection will be returned to a pool mid-session. |
| Two instances both believe they hold the lock | Cannot happen at Postgres level — `pg_try_advisory_lock` is atomic | Application code never branches on a cached lock-state belief; it issues queries on the lock-holding connection and trusts Postgres. |
| Handler hangs indefinitely | No automatic fence today | Recommend: combine with the existing `ConsumerConfig.handlerTimeout` (separate proposal). Out of scope for this design. |

There is intentionally **no zombie-fencing token mechanism** in this proposal. The advisory lock + dedicated session is the fence; if the session is alive, the lock is held; if the session dies, the lock is gone. This is simpler and more reliable than application-level epoch fencing.

#### Observability

The coordinator emits the following metrics (using the existing tracing/metrics conventions in `peegeeq-db`):

- `peegeeq.consumer.exclusive.state` — gauge, one of `ACQUIRING / ACTIVE / RELEASING / STOPPED`.
- `peegeeq.consumer.exclusive.lock_acquired_total` — counter; incremented on each successful acquisition.
- `peegeeq.consumer.exclusive.lock_failover_seconds` — histogram; time from previous holder's release to acquisition by this instance.
- `peegeeq.consumer.exclusive.standby_duration_seconds` — histogram; time spent waiting for the lock per acquisition.
- Structured logs at `INFO` for state transitions, at `WARN` for failed acquisitions or heartbeat failures.

#### Trade-offs vs. partitioned consumer groups

| Feature | Exclusive Consumer (`ExclusiveConfig.enabled = true`) | Partitioned Consumer Group (`OFFSET_WATERMARK`) |
|---|---|---|
| **Scope of ordering** | Total global ordering across the entire topic | Per-key ordering (within `messageGroup`) |
| **Maximum throughput** | One thread, one instance — bounded by handler latency | Scales horizontally across instances and partitions |
| **Fault tolerance** | Active/passive; failover latency ≈ `lockAcquisitionInterval` | Active/active; rebalance on join/leave |
| **Complexity (production code)** | Low: one coordinator class, one extra connection | High: offset table, generations, watermark sweeper |
| **Complexity (operator burden)** | Must use session-pooling or direct connections | Must choose partition keys correctly |
| **Multi-tenant safe** | Yes, when `LockKey.fromTopic()` includes schema | Yes (existing) |
| **Combinable with other modes** | No — mutually exclusive with OFFSET_WATERMARK | N/A |
| **Observable failover** | Yes (failover metric) | N/A (always active) |

#### TDD ordering for implementation

If/when this proposal is approved, implementation must follow:

```
RED:
 1. ExclusiveConsumerConfigValidationTest — builder + validation rules
 2. ExclusiveConsumerLockAcquisitionIntegrationTest — single instance acquires, dequeues in id order
 3. ExclusiveConsumerFailoverIntegrationTest — kill active, standby takes over within 2× interval
 4. ExclusiveConsumerStrictOrderingIntegrationTest — 1000 messages, slow handler, asserts strict id order
 5. ExclusiveConsumerIncompatibleWithOffsetWatermarkTest — start() fails on OFFSET_WATERMARK topic

GREEN:
 6. ExclusiveConfig + LockKey + builder validation
 7. ExclusiveConsumerCoordinator with state machine on a dedicated SqlConnection
 8. Wire into PgNativeQueueConsumer.start() so existing simple consumers opt in via config
 9. Topic-mode incompatibility check
10. Metrics + structured logging

VERIFY:
13. All RED tests pass deterministically (10/10 runs)
14. testCQRS in EventSourcingCQRSDemoTest still passes (no regression on partitioned path)
15. Existing ConsumerConfigValidationTest still passes
```

#### What this proposal explicitly does **not** include

- **Cross-topic transaction coordination**: an exclusive consumer on topic `A` does not order messages on topic `B`. Use a literal `LockKey` if cross-topic exclusivity is required, but ordering between topics still depends on the producer's send order and is out of scope here.
- **Outbox / bitemporal API surface**: the cross-module API alignment for `peegeeq-outbox` and `peegeeq-bitemporal` is tracked in the TODO section below. The exclusive-consumer feature must land in `peegeeq-native` first and be exposed upward only after its semantics are stable.
- **Auto-promotion of partitioned consumers to exclusive**: there is no implicit upgrade path. A topic is either OFFSET_WATERMARK, or a candidate for exclusive consumption, or a regular SKIP-LOCKED topic. Choice is explicit.

**Conclusion**: A focused, opt-in extension that uses Postgres-native primitives (advisory locks + session lifetime) to provide total global ordering with active/passive HA. Complexity is concentrated in one new class (`ExclusiveConsumerCoordinator`) and one new value object (`ExclusiveConfig`); no schema changes; no impact on existing consumers that do not opt in.

---

### TODO: Cross-Module API Alignment (Outbox & Bitemporal)

**Status**: pending deep analysis.

The ordering guarantees designed in `peegeeq-native` must be exposed through the configuration APIs of higher-level modules. A strict-ordered consumption feature is only useful if it offers feature parity across the ecosystem.

**1. `peegeeq-outbox` impact**:

- **Current state**: `OutboxConsumerConfig.Builder` supports `pollingInterval`, `batchSize`, `consumerThreads`, `maxRetries`, and `serverSideFilter`. It cannot configure OFFSET_WATERMARK at the API surface.
- **Required analysis (in scope for Decision 1 follow-up)**: design how `OutboxConsumer` and `OutboxConsumerGroup` expose OFFSET_WATERMARK so downstream systems do not receive "Entity Updated" before "Entity Created". Exclusive-consumer exposure (`exclusive(true)`) is held until Phase 6.

**2. `peegeeq-bitemporal` impact**:

- **Current state**: `PgBiTemporalEventStore` exposes a simple `subscribe(eventType, handler)` API backed by `ReactiveNotificationHandler`. There is no configuration object to request durable, ordered timeline subscription.
- **Required analysis (in scope for Decision 1 follow-up)**: design a `BiTemporalSubscriptionConfig` that lets subscribers opt into OFFSET_WATERMARK with a partition key derived from the bitemporal aggregate. Exclusive (totally-ordered) timeline subscription is held until Phase 6.

This TODO is intentionally scoped to OFFSET_WATERMARK. The exclusive-consumer surface across these modules is deferred to Phase 6.

---

## Decision

**Decided April 28, 2026.** The project will move forward with two concrete decisions, recorded below. (Idempotency is enforced by the existing version guard in `AccountReadModel.applyEvent()`; no in-memory tracking structure is used.)

| Decision | Status | Rationale |
|---|---|---|
| **Decision 1 — OFFSET_WATERMARK + partitioned consumer group** | **✅ ADOPTED — implement now** | The infrastructure already exists; this decision makes the per-key ordering pattern visible and supported. It fixes the intermittent `testCQRS` failure with the architecturally correct pattern. |
| **Decision 2 — Exclusive Consumer (advisory locks, total global ordering)** | **🕒 DEFERRED — future phase** | Genuinely useful for total-ordering workloads (ledgers, replays, single-writer projections), but distinct from per-key ordering. Implementing it after Decision 1 ships avoids conflating two design surfaces and lets us validate Decision 1's operational caveats before adding a second mode. |

**Implications of this decision**:

- The current implementation plan covers Decision 1 only.
- Decision 2's full design (configuration API, lock keying, lifecycle, observability, TDD ordering) remains in this document as a *deferred future phase* — see Phase 6 below — so that no design work is lost.
- The TODO under *Cross-Module API Alignment* is reduced in scope: the immediate concern is ensuring `peegeeq-outbox` and `peegeeq-bitemporal` can opt into OFFSET_WATERMARK. Their exclusive-consumer story is held until Decision 2's phase begins.

---

## Recommendation

**For `EventSourcingCQRSDemoTest`** — use **Decision 1 (OFFSET_WATERMARK)** because it:

1. demonstrates the **correct architectural pattern** for event sourcing,
2. shows users **how to achieve ordering** in production,
3. uses **existing infrastructure** (no new code required), and
4. validates that **partitioned consumption** works correctly.

**For general guidance** — document **both patterns**:

- Simple consumer + idempotency → use when order does not matter.
- Partitioned consumer → use when per-key ordering is required.
- Exclusive consumer (Decision 2) → deferred; revisit when total global ordering is requested by a concrete workload.

---

## Implementation Plan

### TDD Ordering (mandatory — do not reverse)

This section defines the **execution order** across Phases 1–5. The phases below describe **what** is delivered in each work package; this section defines **when** each piece is written relative to the others. Read it as a cross-cutting checklist that interleaves the per-phase work — not as a sixth phase.

**Mapping to phases**:

| TDD step | Belongs to | Outcome |
|---|---|---|
| RED 1 | Phase 2 | New failing test `testCQRS_multipleAccounts_perAccountOrdering` (Order=3) added to `EventSourcingCQRSDemoTest`. |
| RED 2 | Phase 3 | New failing file `PartitionedOrderingDemoTest` (tests 2a–2d). |
| RED 3 | Phase 2 | Confirm the existing `testCQRS` (Order=2) is already intermittently RED — baseline before any production change. |
| RED 3a | Phase 4 | Add the safety tests (Test A `…newMessageGroupAfterJoin_notAssignedUntilRebalance` and Test B handler-failure-mid-batch) as failing tests against current behaviour. |
| GREEN 4 | Phase 2 | Insert `outbox_topics` OFFSET_WATERMARK row in test setup. |
| GREEN 5 | Phase 2 | Producer change: `send(event, headers, correlationId, event.getAggregateId())` and compose returned `Future<Void>`. |
| GREEN 6 | Phase 2 | Consumer change: `ConsumerGroup` + `start(SubscriptionOptions)`; retain version guard as idempotency fence (Phase 2 step 7). |
| GREEN 7 | Phase 3 | Implement `PartitionedOrderingDemoTest` scaffolding (container, helpers). |
| GREEN 8 | Phase 3 + Phase 4 | Implement Phase 3 test bodies 2a–2d **and** Phase 4 safety-test bodies. |
| VERIFY 9 | Phase 2 | `testCQRS` passes 10/10 consecutive runs. |
| VERIFY 10 | Phase 2 | `testCQRS_multipleAccounts_perAccountOrdering` passes 10/10. |
| VERIFY 11 | Phase 3 + Phase 4 | All `PartitionedOrderingDemoTest` and Phase 4 safety tests pass deterministically. |
| VERIFY 12 | Regression gate | `testEventSourcing` (Order=1) still passes — no regression. |
| Documentation | Phase 1 + Phase 5 | Phase 1 (`PEEGEEQ_ORDERING_PATTERNS_GUIDE.md`) may be drafted at any point but is **finalised after VERIFY** so examples reflect the shipped APIs; Phase 5 (user-guide cross-references) lands last. |

**Phase 6** (Deferred — Exclusive Consumer) and **Phase 7** (Deferred — Native-Table OFFSET_WATERMARK over `queue_messages`) each have their own, independent TDD ordering and are not part of the table above. They begin only when the triggers documented in *Phase 6 (Deferred)* and *Phase 7 (Deferred)* respectively are satisfied.

**Execution sequence in flat form** (the original list, retained for quick reference):

```
RED PHASE — write failing tests first:
  1. Write testCQRS_multipleAccounts_perAccountOrdering (Order=3) in EventSourcingCQRSDemoTest    [Phase 2]
     → RED: no OFFSET_WATERMARK topic setup, no messageGroup on producers
  2. Write PartitionedOrderingDemoTest (new file) — tests 2a–2d                                    [Phase 3]
     → RED: class does not exist
  3. The existing testCQRS (Order=2) is already intermittently RED.                                [Phase 2]
     Confirm by running 10 times before any code change.
  3a. Write Phase 4 safety tests A (newMessageGroupAfterJoin) and B (handler-failure-mid-batch)    [Phase 4]
     → RED against current behaviour.

GREEN PHASE — implement minimum change to pass:
  4. Add outbox_topics OFFSET_WATERMARK row in @BeforeEach (or per-test setup)                     [Phase 2]
  5. Change testCQRS event producer: send with messageGroup = event.getAggregateId()               [Phase 2]
  6. Change testCQRS event consumer: ConsumerGroup + start(SubscriptionOptions)                    [Phase 2]
  7. Implement PartitionedOrderingDemoTest scaffolding (container, helpers)                        [Phase 3]
  8. Implement test bodies 2a–2d and Phase 4 safety-test bodies                                    [Phase 3 + Phase 4]

VERIFY:
  9.  testCQRS passes 10/10 consecutive runs                                                       [Phase 2]
  10. testCQRS_multipleAccounts_perAccountOrdering passes 10/10                                    [Phase 2]
  11. All PartitionedOrderingDemoTest and Phase 4 safety tests pass deterministically              [Phase 3 + Phase 4]
  12. testEventSourcing (Order=1) still passes — no regression                                     [Regression gate]

DOCUMENTATION (lands after VERIFY so examples reflect shipped APIs):
  13. Finalise PEEGEEQ_ORDERING_PATTERNS_GUIDE.md                                                  [Phase 1]
  14. Cross-reference from PEEGEEQ_COMPLETE_GUIDE / EXAMPLES_GUIDE / ARCHITECTURE_API_GUIDE        [Phase 5]
```

---

### Phase 1: Documentation

**New guide**: `docs/PEEGEEQ_ORDERING_PATTERNS_GUIDE.md`

**Sections**:

1. **Understanding PeeGeeQ's ordering guarantees**
   - Storage order vs. processing order
   - Why `FOR UPDATE SKIP LOCKED` breaks ordering
   - When ordering matters
2. **Consumer mode comparison**
   - Simple consumer (`MessageConsumer`) — use cases
   - Partitioned consumer (`ConsumerGroup` + OFFSET_WATERMARK) — use cases
   - Decision matrix
3. **Pattern 1: Simple consumer + idempotency**
   - When to use (metrics, logs, notifications)
   - Implementation examples
   - Trade-offs
4. **Pattern 2: Partitioned consumption**
   - When to use (event sourcing, state machines, financial)
   - Setup steps (topic configuration, partition keys, `messageGroup` on every send)
   - Implementation examples
   - The `__default__` partition and why to avoid it
   - Rebalancing behaviour
5. **Migration guide**
   - Simple → partitioned
   - Choosing partition keys
   - Testing strategies

---

### Phase 2: Fix `testCQRS` in `EventSourcingCQRSDemoTest`

**File**: `peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/EventSourcingCQRSDemoTest.java`

**Changes** (all inside `testCQRS`, Order=2):

1. Before creating producers/consumers, insert the event queue topic into `outbox_topics` as OFFSET_WATERMARK.
2. Change every `eventProducer.send(event)` call to `eventProducer.send(event, headers, correlationId, event.getAggregateId())` and **compose the returned `Future<Void>`** — never discard it. The command handler's returned future must complete only after every generated event's send future has resolved.
3. **Prime partitions before consumer-group start.** Because `joinGroup` discovers partitions from existing `PENDING`/`PROCESSING` rows (see *Operational Caveats & Lifecycle*), the test must publish at least one event per `aggregateId` it intends to use before calling `eventGroup.start(options)`, OR sequence start strictly after the first batch of `send(...)` futures resolves. This avoids the empty-assignment trap where the consumer sits idle.
4. Replace `MessageConsumer<DomainEvent> eventConsumer` with `ConsumerGroup<DomainEvent> eventGroup`.
5. Replace `eventConsumer.subscribe(...)` with `eventGroup.setMessageHandler(...)` followed by `eventGroup.start(options).onFailure(...)`.
6. Replace `eventConsumer.close()` with `eventGroup.stopGracefully()` (returns `Future<Void>`).
7. **Repurpose the version guard as an idempotency fence — do NOT remove it.** OFFSET_WATERMARK guarantees per-partition ordering; it does not deduplicate. Keep the existing check in `AccountReadModel.applyEvent()`:

   ```java
   public void applyEvent(DomainEvent event) {
       // Idempotency fence: protects against at-least-once redelivery
       // (rebalance, stale-generation retry, consumer restart before commit).
       // Ordering is guaranteed by OFFSET_WATERMARK; deduplication is the consumer's job.
       if (event.getVersion() <= lastProcessedVersion) {
           return;
       }
       // ... apply ...
       this.lastProcessedVersion = event.getVersion();
   }
   ```

   Update the comment to describe the guard's purpose under OFFSET_WATERMARK (idempotency, not ordering). Removing the guard would cause `totalTransactions`, `totalDeposits`, and `totalWithdrawals` to double-count on any redelivery — see Appendix row "Idempotency requirement".

Add `testCQRS_multipleAccounts_perAccountOrdering` (Order=3) to verify that the pattern generalises across multiple aggregates processed concurrently.

---

### Phase 3: New Example — `PartitionedOrderingDemoTest`

**File**: `peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/nativequeue/PartitionedOrderingDemoTest.java`

**Tag**: `@Tag(TestCategories.INTEGRATION)` + `@Testcontainers`  
**Container**: `SharedTestContainers.getSharedPostgreSQLContainer()`

| Test | Method | What it proves |
|---|---|---|
| 2a | `testPartitionedOrdering_eventsPerAggregateInOrder` | 3 accounts × 5 events = 15 messages. Per-account version order is strictly ascending at the consumer. |
| 2b | `testPartitionedOrdering_differentAggregatesProcessedConcurrently` | 2 accounts × 3 slow events each (100 ms delay in handler). Total elapsed < 2 × single-account time. |
| 2c | `testPartitionedOrdering_defaultPartition_noMessageGroup` | Producer sends without `messageGroup`. Topic is OFFSET_WATERMARK. Asserts `outbox_partition_assignments` contains exactly one row with `partition_key = '__default__'`; all 5 messages received in order. |
| 2d | `testPartitionedOrdering_consumerRestart_resumesFromCommittedOffset` | Consumer group processes a batch, then is restarted without an offset reset. Verifies that the engine resumes from the committed offset (does not restart from zero) and does not re-deliver already-committed messages in the normal restart path. (OFFSET_WATERMARK is at-least-once; this test asserts cursor position, not the absence of all possible re-delivery.) |

---

### Phase 4: Safety Tests — Handler Failure & Discovery Gap

**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PartitionedConsumerSafetyIntegrationTest.java`

#### Test A: `testPartitionedConsumer_newMessageGroupAfterJoin_notAssignedUntilRebalance`

Locks in the current discovery contract documented in *Operational Caveats & Lifecycle §1*.

**Setup**: Configure topic as OFFSET_WATERMARK with no `PENDING` rows. Start a consumer group instance and observe `getAssignedPartitions()` is empty.

**Action**: Producer publishes a message with `messageGroup = "acct-new"`.

**Assertions**:

- Without a rebalance trigger, `getAssignedPartitions()` remains empty for at least 2 fetch ticks.
- After a second instance joins (forcing a rebalance), `acct-new` becomes assigned to one of the instances and the message is processed.

This test exists to make the limitation explicit. If/when periodic rediscovery is added, this test must be updated rather than silently passing under new behaviour.

#### Test B: `testHandlerFailureMidBatch_offsetNotAdvanced_inProgressReleased`

The existing safety tests cover close-during-startup, stop-failure logging, and the overlapping-fetch guard. None tests handler failure mid-batch.

**Assertions**:

- Insert 5 messages into one partition.
- Handler returns `Future.succeededFuture()` for messages 1–2 and `Future.failedFuture(...)` for message 3.
- After the fetch cycle completes, `committed_offset` must remain at its pre-batch value — the offset must not advance past the failure.
- `fetchInProgress` for that partition must be `false` (released by `.eventually()`) so the next cycle can run.
- On the **next fetch cycle**, messages 1–5 are re-fetched from the pre-batch offset and the handler is invoked again for messages 1–2 (at-least-once redelivery after failure). This assertion is required to prove the at-least-once + re-delivery contract, not merely the offset non-advancement.

This validates the `processAndCommit` → `.eventually(() -> inProgress.set(false))` contract under failure, including the full redelivery loop.

---

### Phase 5: User Guide Updates

**Files to update**:

- `docs/PEEGEEQ_COMPLETE_GUIDE.md` — add an "Ordering Patterns" section.
- `docs/PEEGEEQ_EXAMPLES_GUIDE.md` — reference the new examples.
- `docs/PEEGEEQ_ARCHITECTURE_API_GUIDE.md` — document `PartitionedConsumerEngine`.

---

### Phase 6 (Deferred): Exclusive Consumer — Total Global Ordering

**Status**: **deferred future phase.** Not in scope for the current implementation cycle.

This phase implements Decision 2 (see *Decision 2 (Future Phase): Exclusive Consumer for Total Global Ordering* above). It is held back deliberately so that:

- Decision 1 ships, is exercised in production, and its operational caveats (Open Question 6 — periodic rediscovery and engine heartbeats) are addressed first.
- The exclusive-consumer mode is implemented against a stable, documented per-key-ordering baseline rather than concurrently with it.
- Demand can be validated against a real workload (ledger streams, replay pipelines, single-writer projections) before committing to a second ordering mode.

**Trigger to start Phase 6**:

1. Decision 1 (Phases 1–5) is signed off and merged.
2. A concrete workload is identified that requires total global ordering and cannot be partitioned.
3. Open Question 6 has been answered (engine heartbeats / periodic rediscovery), so that lifecycle assumptions for the exclusive-consumer coordinator are stable.

**Scope when Phase 6 begins** (already designed in *Decision 2 (Future Phase): Exclusive Consumer for Total Global Ordering* above; not duplicated here):

- `ExclusiveConfig` value object on `ConsumerConfig`.
- `LockKey` strategy interface with `fromTopic()` / `ofLiteral(long)` / `ofString(...)` implementations.
- `ExclusiveConsumerCoordinator` with a dedicated `SqlConnection` and the documented state machine.
- Validation rules (mutually exclusive with OFFSET_WATERMARK).
- Six RED tests, six GREEN steps, three VERIFY checks per the documented TDD ordering.
- Cross-module API alignment for `peegeeq-outbox` and `peegeeq-bitemporal` follows after the native implementation lands.

**Out of scope for Phase 6** (still): cross-topic transaction coordination; auto-promotion from partitioned to exclusive consumption.

---

### Phase 7 (Deferred): Native-Table OFFSET_WATERMARK — Partitioned Engine over `queue_messages`

**Status**: **deferred future phase.** Not in scope for the current implementation cycle.

#### Background

`PartitionedConsumerEngine` and `PartitionedFetcher` were designed for the outbox subsystem; their fetch SQL reads from the `outbox` table:

```sql
-- PartitionedFetcher.fetchMessages (peegeeq-db)
SELECT o.id, o.topic, o.payload, ...
FROM outbox o
WHERE o.topic = $1
  AND o.id > $2
  AND o.status IN ('PENDING','PROCESSING')
  AND o.message_group = $3
ORDER BY o.id ASC
LIMIT $4
FOR UPDATE OF o SKIP LOCKED;
```

The **native** subsystem (`PgNativeQueueProducer` + `PgNativeQueueConsumer`) writes to a different table — `queue_messages` — using PostgreSQL `LISTEN/NOTIFY` for low-latency delivery. `PgNativeConsumerGroup.startPartitioned()` *does* register partition assignments when the topic is configured `OFFSET_WATERMARK`, but the partitioned engine then finds zero rows because the producer's writes never reach `outbox`. As a result, today the `EventSourcingCQRSDemoTest` Phase 2 GREEN demo runs over the **outbox factory** (see [`peegeeq-examples/.../EventSourcingCQRSDemoTest.java`](../../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/EventSourcingCQRSDemoTest.java)). This is correct end-to-end behaviour but means OFFSET_WATERMARK currently has **no native-subsystem equivalent**.

#### Goal

Make `PgNativeConsumerGroup` + OFFSET_WATERMARK fully functional against the native producer — so a single demo or production application can use the native subsystem (LISTEN/NOTIFY-driven low-latency delivery) **and** still get per-`messageGroup` ordering.

#### Design Options

1. **Sibling fetcher** (recommended): introduce `PartitionedNativeFetcher` (in `peegeeq-db` or `peegeeq-native`) that mirrors `PartitionedFetcher` but reads from `queue_messages`. `PartitionedConsumerEngine` selects the fetcher implementation based on the calling factory (native vs outbox) at construction time. Lowest-risk change; no shared SQL surface.
2. **Parameterised fetcher**: extend `PartitionedFetcher` with a "source table" parameter (`outbox` | `queue_messages`) and column mapping. Higher coupling, requires care that locking semantics (`FOR UPDATE SKIP LOCKED`) remain correct against `queue_messages`'s indexes and status enum.
3. **Cross-table view/MV**: a database view that unions outbox and native rows. Rejected — adds replication latency and complicates `FOR UPDATE`.

#### Required Production Changes (option 1)

- New `PartitionedNativeFetcher` reading `queue_messages` with the same contract as `PartitionedFetcher` (`fetchMessages → setPendingOffset → getCommittedOffset`).
- `PgNativeQueueFactory` (or `PgNativeConsumerGroup`) wires the native fetcher into `PartitionedConsumerEngine` instead of the outbox fetcher.
- Verify `queue_messages` has the columns required: `id`, `topic`, `payload`, `headers`, `correlation_id`, `message_group`, `created_at`, plus a status column suitable for `FOR UPDATE SKIP LOCKED`. Add a partial index `(topic, message_group, id) WHERE status IN (...)` if missing.
- Confirm offset persistence (`outbox_partition_offsets`) is table-agnostic and can be reused; if not, generalise it.
- LISTEN/NOTIFY: the partitioned engine is poll-driven; native messages still notify, but the engine should not double-deliver. Either disable LISTEN-based dispatch on OFFSET_WATERMARK topics for `PgNativeConsumerGroup`, or use NOTIFY purely as a fetch-trigger.

#### Trigger to Start Phase 7

1. Phases 1–5 are signed off and merged (per-partition ordering on outbox is stable).
2. A concrete workload requires native-subsystem latency **and** per-key ordering (e.g. low-latency notification stream where each user must see strictly ordered events).
3. Operational story for offset table sharing between subsystems is settled.

#### Scope when Phase 7 begins

##### TDD ordering for Phase 7 (mandatory — do not reverse)

```
RED:
 1. PartitionedNativeConsumerOrderingIntegrationTest
    — topic configured OFFSET_WATERMARK; messages produced via PgNativeQueueProducer.send(..., messageGroup);
      asserts per-partition strict ordering at the consumer (version order ascending per aggregateId).
 2. PartitionedNativeConsumerOffsetAdvancementIntegrationTest
    — asserts outbox_partition_offsets.committed_offset advances after successful processing
      when messages arrive via the native producer path.
 3. PartitionedNativeConsumerRestartResumesFromOffsetIntegrationTest
    — consumer group stopped and restarted without offset reset; asserts engine resumes
      from committed_offset and does not re-deliver already-committed messages in normal path.
 4. PartitionedNativeConsumerConcurrentPartitionsIntegrationTest
    — 2 accounts × 3 slow-handler events each; asserts total elapsed < 2 × single-partition time
      (cross-partition concurrency preserved on native path).

GREEN:
 5. PartitionedNativeFetcher — reads from queue_messages with the same contract as
    PartitionedFetcher (fetchMessages → setPendingOffset → getCommittedOffset).
    Confirm queue_messages has (topic, message_group, id) index; add partial index if absent.
 6. PgNativeQueueFactory (or PgNativeConsumerGroup) wires PartitionedNativeFetcher into
    PartitionedConsumerEngine instead of the outbox fetcher when topic mode is OFFSET_WATERMARK.
 7. Confirm outbox_partition_offsets is table-agnostic and can be shared; generalise if not.
 8. Disable or convert LISTEN/NOTIFY-based dispatch for OFFSET_WATERMARK topics on
    PgNativeConsumerGroup — use NOTIFY as a fetch-trigger only, not for direct delivery.

VERIFY:
 9. All four RED tests pass deterministically (10/10 runs each).
10. testCQRS and testCQRS_multipleAccounts_perAccountOrdering in EventSourcingCQRSDemoTest
    still pass — no regression on the outbox path.
11. Existing PartitionedNativeConsumerIntegrationTest (outbox-path tests 6.1–6.6) still pass.
```

**Additional scope**:

- Documentation: update `PEEGEEQ_ORDERING_PATTERNS_GUIDE.md` (Phase 1 deliverable) to describe both subsystems' OFFSET_WATERMARK support.
- Once Phase 7 lands, the `EventSourcingCQRSDemoTest` may be migrated back to the native factory if desired (it currently runs on outbox).

#### Out of scope for Phase 7

- Cross-subsystem topic interoperability (a topic that mixes native and outbox producers).
- Changes to LISTEN/NOTIFY semantics for non-OFFSET_WATERMARK native topics.
- Performance benchmarks comparing native vs outbox under OFFSET_WATERMARK (separate performance-module task).

---

## Success Criteria

### Functional

- ✅ `testCQRS` (Order=2) in `EventSourcingCQRSDemoTest` passes 10/10 consecutive runs.
- ✅ `testEventSourcing` (Order=1) still passes — no regression.
- ✅ `testCQRS_multipleAccounts_perAccountOrdering` (Order=3) passes 10/10 consecutive runs.
- ✅ `PartitionedOrderingDemoTest` tests 2a–2d all pass deterministically.
- ✅ `testHandlerFailureMidBatch_offsetNotAdvanced_inProgressReleased` passes.
- ✅ `AccountReadModel.applyEvent()` retains its version guard, now documented as an **idempotency fence** (not an ordering mechanism).
- ✅ Test logs show partition assignments and per-partition ordered delivery.
- ✅ `testPartitionedConsumer_newMessageGroupAfterJoin_notAssignedUntilRebalance` passes, locking in the documented discovery contract.

### Educational

- ✅ Users understand when to use each consumer mode.
- ✅ The `messageGroup` requirement for OFFSET_WATERMARK is clearly documented.
- ✅ The `__default__` partition behaviour and its throughput implications are documented.
- ✅ Clear guidance on partition-key selection.
- ✅ `PartitionedOrderingDemoTest` demonstrates per-key ordering and cross-partition concurrency.
- ✅ Migration path documented.

### Technical

- ✅ No new production code required (uses existing `PartitionedConsumerEngine`).
- ✅ Backward compatible — existing simple consumers still work; REFERENCE_COUNTING topics unchanged.
- ✅ Performance characteristics documented (concurrency across partitions, serial per partition).
- ✅ Rebalancing behaviour tested (existing tests 6.5/6.6 in `PartitionedNativeConsumerIntegrationTest`).

---

## Open Questions for Review

1. **Should OFFSET_WATERMARK become the default for new topics?**
   - Pro: better guarantees out of the box.
   - Con: requires partition-key discipline on every `send()` call; a single `send()` without `messageGroup` creates an unintended `__default__` serial partition.
   - **Recommendation**: keep REFERENCE_COUNTING as the default. Make OFFSET_WATERMARK an explicit opt-in via `outbox_topics` configuration. This forces a deliberate choice and prevents accidental serial bottlenecks.

2. **How should partition keys be chosen?** *(answered)*
   - Aggregate ID — recommended for event sourcing and CQRS.
   - User ID — for per-user workflows.
   - Tenant ID — for per-tenant isolation (complements multi-tenant schema isolation).
   - Custom business key — any key that defines the ordering boundary.
   - **Rule**: the partition key must be the entity whose events must be strictly ordered relative to each other.

3. **Should we provide automatic partition-key extraction?** *(rejected)*

   ```java
   // Annotation extraction was considered and rejected — complexity for marginal gain.
   // The correct pattern is explicit:
   producer.send(event, headers, correlationId, event.getAggregateId());
   ```

   **Rationale**: explicit `messageGroup` on every `send(...)` call makes the partition boundary visible at the call site, which is essential for correctness review. An annotation approach would hide that contract and add a non-trivial annotation-processing or reflection layer. Rejected; not deferred.

4. **What about cross-partition transactions?** *(out of scope for this phase)*
   - Currently each partition processes independently.
   - Future consideration: distributed transaction coordination across partitions.
   - Not required for the event-sourcing or CQRS patterns addressed here.

5. **Performance impact of partitioned consumption?** *(partially answered by existing tests)*
   - `PartitionedNativeConsumerIntegrationTest` test 6.6 confirms parallel partitions across 2+ instances.
   - `PartitionedOrderingDemoTest` test 2b adds a concurrency timing assertion.
   - A detailed benchmark across multiple partition counts is deferred to the performance module.

6. **Should `PartitionedConsumerEngine` perform periodic partition rediscovery and emit heartbeats?** *(deferred — separate design)*
   - Today, discovery runs only inside `joinGroup`/`leaveGroup`, and the engine does not call `PartitionAssignmentService.heartbeat(...)`.
   - Options for a follow-up design: (A) periodic `refreshAssignments()` timer in the engine; (B) explicit public API `engine.refreshAssignments()` for application-driven triggers; (C) database-side notify on new `message_group` first-sighting.
   - Out of scope for v1.2; documented here so the limitation is not forgotten.

---

## References

### Existing Implementation

- **`PartitionedConsumerEngine.java`** — main coordination logic.
- **`PartitionAssignmentService.java`** — partition assignment and rebalancing.
- **`PartitionedOffsetManager.java`** — offset tracking and commits.
- **`PartitionedFetcher.java`** — per-partition message fetching.
- **`WatermarkCalculator.java`** — cleanup coordination.

### Design Documents

- **`PEEGEEQ_OUTBOX_PARTITIONED_ORDERING_COMPLETE_GUIDE.md`** — original ordering design.
- **`PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md`** — consumer group architecture.
- **`CONSUMER_GROUP_OUTSTANDING_TASKS.md`** — OFFSET_WATERMARK implementation status.

### Database Schema

- **`V010__Create_Consumer_Group_Fanout_Tables.sql`** — subscription tracking.
- **`outbox_partition_assignments`** — partition ownership.
- **`outbox_partition_offsets`** — per-partition offset cursors.
- **`outbox_topic_watermarks`** — cleanup boundaries.

---

## Appendix: Comparison Table

| Aspect | Simple Consumer | Partitioned Consumer (OFFSET_WATERMARK) |
|---|---|---|
| **Setup complexity** | Low (2 lines) | Medium (topic config + partition keys) |
| **Ordering guarantee** | None | Per-partition strict ordering |
| **Throughput** | High (fully concurrent) | High (concurrent across partitions) |
| **Latency** | Low (immediate processing) | Low (batch processing per partition) |
| **Scalability** | Horizontal (add consumers) | Horizontal (rebalance partitions) |
| **Idempotency requirement** | Must handle out-of-order and duplicates | Must handle duplicates only |
| **State management** | Stateless consumer | Persistent offset cursors |
| **Failure recovery** | Re-fetch from DB | Resume from last committed offset |
| **Use cases** | Metrics, logs, notifications | Event sourcing, state machines, financial |
| **Production readiness** | ✅ Fully production-ready | ⚠️ Implemented and tested, with operational caveats: point-in-time partition discovery at join; engine does not emit heartbeats; new `message_group` values require a rebalance to be assigned (see *Operational Caveats & Lifecycle*) |

---

## Approval & Sign-Off

**Decision recorded**: April 28, 2026 — v1.3.

**Recorded decisions** (the previous "Key decisions required" list, now resolved):

1. **✅ Decision 1 (OFFSET_WATERMARK + `messageGroup` on producer) is adopted** for `testCQRS` and as the recommended pattern for event sourcing / per-key ordering.
2. **✅ The version guard in `AccountReadModel.applyEvent()` is retained** as an idempotency fence (reversal from v1.1 stands).
3. **✅ REFERENCE_COUNTING stays as the default topic mode**; OFFSET_WATERMARK is explicit opt-in.
4. **✅ TDD phase ordering is approved** before implementation begins (RED tests first, then GREEN, then VERIFY).
5. **✅ Engine lifecycle caveats remain documented as-is**; periodic rediscovery / heartbeat emission is tracked under Open Question 6.
6. **✅ Decision 2 (Exclusive Consumer) is deferred to Phase 6** (a future phase). Its full design is preserved in this document; no implementation work in the current cycle.

**Implementation scope for the current cycle**: Phases 1–5. Phase 6 begins only after the triggers listed in *Phase 6 (Deferred)* are satisfied. Phase 7 (native-table OFFSET_WATERMARK) begins only after the triggers listed in *Phase 7 (Deferred)* are satisfied.

**Changelog v1.4** (April 29, 2026):

- Corrected `EventSourcingCQRSDemoTest` package path in Phase 2 and Phase 7: `nativequeue/` → `outbox/` (the test uses `OutboxFactoryRegistrar` and lives in the `outbox` package).
- Added **Operational Caveats & Lifecycle §5**: COMPLETED-row discovery gap — partitions whose rows have all drained to COMPLETED are invisible to `discoverPartitionsInternal` at join time; cross-referenced with §1 and §3 as the same root cause.
- Renamed Phase 3 test 2d to `testPartitionedOrdering_consumerRestart_resumesFromCommittedOffset`; corrected description to assert cursor position rather than overstating "prevents re-delivery".
- Added missing re-delivery assertion to Phase 4 Test B: next fetch cycle must re-invoke the handler for messages 1–2, proving the at-least-once + redelivery contract.
- Fixed misleading producer snippet comment: "Compose it" replaced with accurate wording distinguishing `.onFailure` (terminal side-effect) from `.compose` (chaining).
- Removed all pgBouncer-specific references from the Phase 6 deferred design. The underlying constraint (advisory locks require a dedicated persistent connection) is retained as a general operator requirement; the removed items were: the pgBouncer failure-mode row, `ExclusiveConsumerTransactionPooledRejectionTest` (RED 6), and the `Pool-mode probe` GREEN step.
- Changed Open Question 3 status from `*(deferred)*` to `*(rejected)*` with explicit rationale.
- Added formal RED/GREEN/VERIFY TDD table to Phase 7 (*Scope when Phase 7 begins*), matching the structure of Phase 6.

**Changelog v1.3**:

- Added a new **Decision** section recording two concrete design decisions: Decision 1 (OFFSET_WATERMARK) adopted now, Decision 2 (Exclusive Consumer) deferred to a future phase.
- **Removed the prior "options" framing**: Section headings and cross-references now read as recorded decisions ("Decision 1", "Decision 2 (Future Phase)") rather than open options.
- Marked Decision 2 as a *Future Phase* (deferred), preserving its full design.
- Added **Phase 6 (Deferred): Exclusive Consumer** to the Implementation Plan with explicit triggers for when work may start.
- Reduced the *Cross-Module API Alignment* TODO to OFFSET_WATERMARK exposure for `peegeeq-outbox` and `peegeeq-bitemporal`; exclusive-consumer surface is deferred to Phase 6.
- Updated the Recommendation section to call out exclusive-consumer as deferred.
- Converted the "Key decisions required" block into recorded decisions.

**Changelog v1.2**:

- Scoped the "production-ready" claim: added explicit operational caveats in the Executive Summary and Appendix.
- Added a new **Operational Caveats & Lifecycle** section documenting (1) point-in-time partition discovery at join, (2) the engine not calling `PartitionAssignmentService.heartbeat(...)`, (3) new `message_group` values requiring rebalance, and (4) at-least-once delivery semantics.
- **Reversed the dangerous Phase 2 step 6**: the version guard in `AccountReadModel.applyEvent()` is now retained and reframed as an idempotency fence. Removing it would cause double-counting under at-least-once redelivery.
- Fixed the Decision 1 / Phase 2 producer snippets to compose the `Future<Void>` returned by `send(...)` rather than discard it.
- Added Phase 2 step 3: prime partitions (publish at least one event per `aggregateId`) before `eventGroup.start(...)` to avoid the empty-assignment trap.
- Added Phase 4 Test A: `testPartitionedConsumer_newMessageGroupAfterJoin_notAssignedUntilRebalance` to lock in the discovery contract.
- Added a `__default__` partition caveat noting that it too is subject to the discovery gap.
- Added Open Question 6 covering periodic rediscovery and engine-driven heartbeats as a deferred follow-up.

**Changelog v1.1**:

- Retargeted failure analysis from `testEventSourcing` to `testCQRS` (the actual failing test).
- Fixed `fetchPartition()` code snippet: `computeIfAbsent` instead of `get()` (the original would NPE).
- Fixed partition-assignment pseudocode: `start(SubscriptionOptions)` returns `Future<Void>`; the no-arg `start()` returns `void`.
- Added the `__default__` partition section — critical for correct OFFSET_WATERMARK usage.
- Fixed the Decision 1 consumer code: `start(options).onFailure(...)` chaining.
- Replaced the flat implementation plan with TDD-ordered phases.
- Removed `IdempotentConsumerDemoTest` (wrong pattern for event sourcing).
- Added the `PartitionedConsumerSafetyIntegrationTest` handler-failure-mid-batch test.
- Updated success criteria and open questions.

**Sign-off**: Mark Andrew Ray-Smith — decision pending signature; document content reflects the agreed direction.
