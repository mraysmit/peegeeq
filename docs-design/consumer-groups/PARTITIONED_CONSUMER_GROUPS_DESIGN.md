# Partitioned Consumer Groups with Offset/Watermark Tracking

## Status: DRAFT
## Date: 2026-04-05
## Author: Design collaboration

---

## 1. Problem Statement

PeeGeeQ's current consumer group model (REFERENCE_COUNTING) provides fan-out delivery with per-message acknowledgement. It does NOT provide:

1. **Partition affinity** — messages with the same logical key can be processed by any consumer instance in the group, in any order
2. **Strict per-key ordering** — message N+1 for key K can begin processing before message N for key K is committed
3. **Offset-based progress** — there is no "where am I in the stream" cursor; progress is tracked per-message via `outbox_consumer_groups` rows

These are required for use cases like:
- Financial transaction processing where events for the same account must be applied in order
- State machine projections where out-of-order events corrupt the projection
- Event sourcing read models that must process events sequentially per aggregate

---

## 2. Design Goals

| Goal | Constraint |
|------|------------|
| **Guaranteed per-partition ordering** | Message N+1 for partition P MUST NOT be delivered until message N for partition P is committed |
| **Concurrent cross-partition processing** | Different partitions MUST be processable in parallel by different consumer instances |
| **Offset-based progress tracking** | Each (group, partition) tracks a monotonic cursor, not per-message ack rows |
| **No write amplification blow-up** | Offset commit is O(1) per batch, not O(messages × groups) |
| **Backward compatible** | REFERENCE_COUNTING mode continues to work unchanged; OFFSET_WATERMARK is opt-in per topic |
| **PostgreSQL only** | No external dependencies (Kafka, Redis, etc.); all coordination via SQL + LISTEN/NOTIFY |
| **Vert.x 5.x reactive** | All new APIs return `Future<T>`; no blocking |

---

## 3. Concepts

### 3.1 Partition Key

A **partition key** is an application-provided string that determines which logical partition a message belongs to. Messages with the same partition key within a topic are guaranteed to be processed in order by the same consumer instance (within a consumer group).

The partition key maps to the existing `message_group` column on the `outbox` table. This column already exists, is already passed through `ConsumerGroupFetcher`, and is already available on `OutboxMessage`. The rename is semantic only — `message_group` becomes the partition key for OFFSET_WATERMARK topics.

**Decision: Re-use `message_group` as the partition key. No schema change on the outbox table.**

Messages with NULL `message_group` go to a synthetic partition `__default__`. This means every message has a partition, even if the producer doesn't set one.

### 3.2 Partition Assignment

Partitions are assigned to consumer instances within a group. The assignment is:

- **Sticky** — a partition stays assigned to the same instance unless rebalance is triggered
- **Exclusive** — each partition is assigned to exactly one instance at a time
- **Rebalanced** on: instance join, instance leave (heartbeat timeout), instance crash (lock expiry)

Assignment is stored in the database so it survives process restarts and is visible to all instances.

### 3.3 Offset

An **offset** is the `outbox.id` (BIGSERIAL) of the last message successfully committed by a consumer group for a partition. Since `outbox.id` is monotonically increasing and gapless within a single INSERT, it serves as a natural offset.

Offset semantics:
- `committed_offset = 47` means "all messages with id ≤ 47 in this partition are done"
- Next fetch returns messages with `id > 47` in this partition
- Offset is committed atomically with the consumer's business transaction (exactly-once via DB transaction)

### 3.4 Watermark

The **watermark** for a topic is `MIN(committed_offset)` across all active consumer groups and all partitions. Messages with `id ≤ watermark` are eligible for cleanup (deletion or partition drop). The watermark is calculated periodically by a background job, not on every commit.

---

## 4. Schema Design

### 4.1 New Tables

```sql
-- Tracks which consumer instance owns which partitions within a group.
-- Partition assignment is the source of truth for "who processes what".
CREATE TABLE IF NOT EXISTS {schema}.outbox_partition_assignments (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    partition_key VARCHAR(255) NOT NULL,         -- value of message_group (or '__default__')
    assigned_instance_id VARCHAR(255) NOT NULL,  -- consumer instance identifier
    assigned_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_heartbeat_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    generation INT NOT NULL DEFAULT 1,           -- rebalance generation; prevents stale instances
    UNIQUE(topic, group_name, partition_key)
);

CREATE INDEX idx_partition_assignments_instance
    ON {schema}.outbox_partition_assignments(topic, group_name, assigned_instance_id);
```

```sql
-- Per-group, per-partition offset tracking.
-- This replaces outbox_consumer_groups rows for OFFSET_WATERMARK topics.
CREATE TABLE IF NOT EXISTS {schema}.outbox_partition_offsets (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    partition_key VARCHAR(255) NOT NULL,        -- value of message_group (or '__default__')
    committed_offset BIGINT NOT NULL DEFAULT 0, -- outbox.id of last committed message
    committed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    pending_offset BIGINT,                      -- outbox.id of last fetched-but-uncommitted message
    pending_since TIMESTAMP WITH TIME ZONE,
    generation INT NOT NULL DEFAULT 1,          -- must match assignment generation
    UNIQUE(topic, group_name, partition_key)
);
```

```sql
-- Per-topic watermark — the safe cleanup boundary.
-- Updated periodically by background job.
CREATE TABLE IF NOT EXISTS {schema}.outbox_topic_watermarks (
    topic VARCHAR(255) PRIMARY KEY,
    watermark_id BIGINT NOT NULL DEFAULT 0,     -- MIN(committed_offset) across all active groups/partitions
    watermark_updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
```

### 4.2 Modified Tables

**outbox_topic_subscriptions** — add columns for partitioned mode:

```sql
ALTER TABLE {schema}.outbox_topic_subscriptions
    ADD COLUMN instance_id VARCHAR(255),             -- consumer instance within group
    ADD COLUMN partition_count INT,                   -- desired number of partitions (hint, not enforced)
    ADD COLUMN rebalance_generation INT DEFAULT 1;    -- current rebalance epoch
```

**outbox_topics** — no changes needed. The existing `completion_tracking_mode` column already supports `'OFFSET_WATERMARK'`.

**outbox** — no changes needed. The existing `message_group` column serves as the partition key.

### 4.3 Index Support

```sql
-- Fast fetch for a specific partition within a topic
-- This is THE critical index for offset-based consumption
CREATE INDEX idx_outbox_topic_msggroup_id
    ON {schema}.outbox(topic, message_group, id)
    WHERE status IN ('PENDING', 'PROCESSING');

-- Fast partition discovery (distinct message_group values for a topic)
CREATE INDEX idx_outbox_topic_msggroup_distinct
    ON {schema}.outbox(topic, message_group);
```

---

## 5. Consumption Flow (OFFSET_WATERMARK Mode)

### 5.1 Message Publish

No change. Producer inserts into `outbox` with `message_group` set to the partition key.

The existing trigger `set_required_consumer_groups()` continues to work. For OFFSET_WATERMARK topics, `required_consumer_groups` is still set but is informational — completion tracking uses offsets, not the counter.

### 5.2 Partition Discovery

When a topic uses OFFSET_WATERMARK mode, the system needs to know what partitions exist. Partitions are discovered dynamically from the data:

```sql
-- Discover active partitions for a topic
SELECT DISTINCT COALESCE(message_group, '__default__') AS partition_key
FROM {schema}.outbox
WHERE topic = $1
  AND status IN ('PENDING', 'PROCESSING')
ORDER BY partition_key;
```

New partitions appear automatically when a producer sends a message with a new `message_group` value. No pre-registration required.

### 5.3 Partition Assignment (Rebalance Protocol)

Rebalance runs when:
- A new consumer instance registers (calls `joinGroup`)
- An existing instance's heartbeat expires
- An instance explicitly leaves the group

The rebalance algorithm:

```
1. LOCK: SELECT ... FOR UPDATE on outbox_topic_subscriptions WHERE topic AND group_name
   (serializes rebalance — only one runs at a time)

2. INCREMENT rebalance_generation on the subscription row

3. DISCOVER: Get all current partition keys from outbox
   (SELECT DISTINCT COALESCE(message_group, '__default__') ...)

4. GET LIVE INSTANCES: All instances with recent heartbeat
   (WHERE last_heartbeat_at > NOW() - heartbeat_timeout)

5. ASSIGN: Round-robin or consistent-hash partitions across live instances
   - INSERT/UPDATE outbox_partition_assignments
   - Set generation = new generation on each assignment

6. STALE CLEANUP: DELETE assignments WHERE generation < new_generation

7. FENCE: Any instance with generation < new_generation MUST stop processing
   before the new assignee starts. Enforced by generation check on offset commit.
```

**Consistent hashing** is preferred over round-robin because it minimizes partition movement when instances join/leave. Partitions hash to a ring; each instance owns a segment.

### 5.4 Message Fetch (Partitioned, Ordered)

For each assigned partition, the consumer fetches the NEXT batch of unprocessed messages:

```sql
-- Fetch next batch for a specific partition
SELECT o.id, o.topic, o.payload, o.headers, o.correlation_id, o.message_group,
       o.created_at, o.required_consumer_groups, o.completed_consumer_groups
FROM {schema}.outbox o
WHERE o.topic = $1
  AND COALESCE(o.message_group, '__default__') = $2
  AND o.id > $3                              -- $3 = committed_offset for this partition
  AND o.status IN ('PENDING', 'PROCESSING')
ORDER BY o.id ASC
LIMIT $4                                     -- batch size
FOR UPDATE OF o SKIP LOCKED;
```

**Critical ordering property**: `ORDER BY o.id ASC` within a single partition guarantees sequential delivery. `FOR UPDATE SKIP LOCKED` is used to prevent duplicate delivery if multiple instances are racing during a rebalance (belt-and-suspenders; the assignment should prevent this).

**No NOT EXISTS subquery**: Unlike REFERENCE_COUNTING, there is no join to `outbox_consumer_groups`. The offset IS the state — "give me everything after my last committed position."

After fetching, update the pending offset:

```sql
UPDATE {schema}.outbox_partition_offsets
SET pending_offset = $4,           -- id of last message in the fetched batch
    pending_since = NOW()
WHERE topic = $1
  AND group_name = $2
  AND partition_key = $3
  AND generation = $5;             -- fencing: must match current generation
```

### 5.5 Message Processing

The consumer processes messages **one at a time within a partition**, in order. This is the fundamental guarantee.

Processing model options:

**Option A — Sequential within partition, parallel across partitions**
```
Partition "account-123":  msg 50 → msg 51 → msg 52  (sequential)
Partition "account-456":  msg 48 → msg 53            (sequential, parallel with above)
Partition "account-789":  msg 49 → msg 54 → msg 55   (sequential, parallel with above)
```

**Option B — Micro-batch commit**
Fetch N messages for a partition, process them all in order, commit the batch offset atomically. This is more efficient than committing after every message but the consumer MUST process them sequentially within the batch.

### 5.6 Offset Commit

After processing a message (or batch), the consumer commits the offset:

```sql
UPDATE {schema}.outbox_partition_offsets
SET committed_offset = $4,          -- id of last successfully processed message
    committed_at = NOW(),
    pending_offset = NULL,
    pending_since = NULL
WHERE topic = $1
  AND group_name = $2
  AND partition_key = $3
  AND generation = $5               -- FENCING: reject commits from stale generation
  AND committed_offset < $4;        -- CAS: only move forward, never backward
```

If `generation` doesn't match (stale instance after rebalance), the UPDATE affects 0 rows. The instance detects this and stops processing.

**Exactly-once via co-located transaction**: If the consumer's business logic uses the same PostgreSQL database, the offset commit can be in the same transaction as the business write:

```java
// Consumer processing loop
pool.withTransaction(conn -> {
    // 1. Business logic write
    conn.preparedQuery("INSERT INTO projections ...").execute(...)
    // 2. Offset commit (same transaction)
    .compose(v -> conn.preparedQuery(COMMIT_OFFSET_SQL).execute(offsetParams))
});
// If either fails, both roll back. Exactly-once.
```

### 5.7 Message Status Transition (OFFSET_WATERMARK)

In REFERENCE_COUNTING mode, message `status` transitions from PENDING → PROCESSING → COMPLETED based on the counter.

In OFFSET_WATERMARK mode, message `status` transitions are simpler:

- **PENDING → COMPLETED**: When `outbox.id ≤ MIN(committed_offset)` across ALL active groups for this topic. This is the watermark.
- The per-message `status` field is updated by the **watermark sweep job**, not by individual consumers.
- Individual consumers do NOT write to the `outbox` row or to `outbox_consumer_groups`. They only write to `outbox_partition_offsets`.

This eliminates per-message write amplification entirely.

### 5.8 Watermark Calculation and Cleanup

A periodic background job (similar to `DeadConsumerDetectionJob`) calculates the watermark:

```sql
-- Calculate watermark for a topic
-- = the lowest committed offset across all active groups and all partitions
WITH active_groups AS (
    SELECT DISTINCT group_name
    FROM {schema}.outbox_topic_subscriptions
    WHERE topic = $1
      AND subscription_status = 'ACTIVE'
),
min_offsets AS (
    SELECT po.group_name, MIN(po.committed_offset) AS group_min_offset
    FROM {schema}.outbox_partition_offsets po
    INNER JOIN active_groups ag ON ag.group_name = po.group_name
    WHERE po.topic = $1
    GROUP BY po.group_name
)
SELECT COALESCE(MIN(group_min_offset), 0) AS watermark
FROM min_offsets;
```

Then update the watermark and mark messages as completed:

```sql
-- Update watermark
INSERT INTO {schema}.outbox_topic_watermarks (topic, watermark_id, watermark_updated_at)
VALUES ($1, $2, NOW())
ON CONFLICT (topic)
DO UPDATE SET watermark_id = $2, watermark_updated_at = NOW()
WHERE outbox_topic_watermarks.watermark_id < $2;  -- only advance, never retreat

-- Mark messages below watermark as COMPLETED (batch)
UPDATE {schema}.outbox
SET status = 'COMPLETED', processed_at = NOW()
WHERE topic = $1
  AND id <= $2
  AND status IN ('PENDING', 'PROCESSING');
```

---

## 6. Rebalance Protocol Detail

### 6.1 Consumer Instance Lifecycle

```
                        ┌──────────┐
           joinGroup()  │          │  leaveGroup()
          ┌────────────►│ ASSIGNED │──────────────┐
          │             │          │               │
          │             └────┬─────┘               │
          │                  │                     │
     ┌────┴────┐        heartbeat             ┌────▼─────┐
     │  IDLE   │        timeout               │  LEAVING │
     │(no part)│          │                    └────┬─────┘
     └─────────┘     ┌────▼─────┐                   │
                     │ REVOKED  │            cleanup offsets
                     │(fence)   │             release partitions
                     └────┬─────┘                   │
                          │                    ┌────▼─────┐
                    rebalance               │  LEFT    │
                    triggers                └──────────┘
                    new assignment
```

### 6.2 Fencing via Generation

The `generation` counter on assignments and offsets is the key to preventing split-brain:

1. Rebalance increments generation to G+1
2. Old instance (generation G) tries to commit offset → UPDATE matches 0 rows (generation mismatch) → instance knows it's been revoked
3. New instance (generation G+1) starts fetching from the last committed offset

**Gap between revocation and new assignment**: Messages are NOT lost because the offset hasn't advanced. The new assignee picks up from exactly where the old one left off.

### 6.3 Rebalance Triggers

| Event | Detection | Action |
|-------|-----------|--------|
| Instance joins | `registerInstance()` API call | Trigger full rebalance |
| Instance leaves | `deregisterInstance()` API call | Trigger full rebalance |
| Instance dies | Heartbeat timeout (existing `DeadConsumerDetector`) | Trigger full rebalance |
| New partition appears | Periodic partition discovery job | Assign to least-loaded instance |
| Manual rebalance | Admin API call | Trigger full rebalance |

---

## 7. API Surface

### 7.1 SubscriptionService Additions

```java
// New methods on SubscriptionService (default methods, opt-in)

/** Register a consumer instance within a group for partitioned consumption. */
Future<PartitionAssignment> joinPartitionedGroup(String topic, String groupName, String instanceId);

/** Deregister a consumer instance, triggering rebalance. */
Future<Void> leavePartitionedGroup(String topic, String groupName, String instanceId);

/** Fetch next batch for a specific partition (offset-based). */
Future<List<OutboxMessage>> fetchPartitioned(String topic, String groupName, 
                                              String partitionKey, int batchSize);

/** Commit offset for a partition. Returns false if generation is stale (fenced). */
Future<Boolean> commitOffset(String topic, String groupName, 
                              String partitionKey, long offset);

/** Get current assignments for an instance. */
Future<List<PartitionAssignment>> getAssignments(String topic, String groupName, String instanceId);
```

### 7.2 New Record Types

```java
public record PartitionAssignment(
    String topic,
    String groupName,
    String partitionKey,
    String instanceId,
    int generation,
    Instant assignedAt
) {}

public record PartitionOffset(
    String topic,
    String groupName,
    String partitionKey,
    long committedOffset,
    Long pendingOffset,
    int generation
) {}
```

### 7.3 REST Endpoints

```
POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions/join
  Body: { "instanceId": "instance-1" }
  Response: { "assignments": [...], "generation": 3 }

DELETE /api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions/leave
  Body: { "instanceId": "instance-1" }

GET /api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions
  Response: { "assignments": [...], "generation": 3 }

POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions/:partitionKey/fetch
  Body: { "batchSize": 10 }
  Response: { "messages": [...], "partitionKey": "account-123" }

POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions/:partitionKey/commit
  Body: { "offset": 1547 }
  Response: { "committed": true, "generation": 3 }
```

---

## 8. Interaction with Existing Infrastructure

### 8.1 LISTEN/NOTIFY

The existing notification channel is per-topic (`{schema}_queue_{topic}`). For partitioned consumption this is still correct — the notification wakes up all consumer instances, each of which then checks their assigned partitions. No change needed.

### 8.2 DeadConsumerDetector

Dead consumer detection continues to work on `outbox_topic_subscriptions` heartbeats. When an instance dies:

1. `DeadConsumerDetector` marks subscription as `DEAD`
2. Rebalance is triggered (either by detection job or by another instance noticing assignments for a dead peer)
3. Dead instance's partitions are reassigned
4. New assignee resumes from committed offset

### 8.3 Backfill

For OFFSET_WATERMARK topics, backfill is simply "set committed_offset = 0 for all partitions in this group" and let the consumer process from the beginning. The existing `BackfillService` needs a new code path for offset-based topics.

### 8.4 CompletionTracker

For OFFSET_WATERMARK topics, `CompletionTracker.markCompleted()` is NOT called. Individual messages are not marked complete by consumers. Completion is determined by the watermark sweep job. The `CompletionTracker` continues to work unchanged for REFERENCE_COUNTING topics.

### 8.5 ConsumerGroupFetcher

For OFFSET_WATERMARK topics, `ConsumerGroupFetcher.fetchMessages()` is NOT used. A new `PartitionedFetcher` handles offset-based fetch with partition affinity. The existing fetcher continues to work unchanged for REFERENCE_COUNTING topics.

---

## 9. Key Design Decisions

### 9.1 Why `message_group` and not a new column?

- Already exists on the outbox table
- Already carried through ConsumerGroupFetcher and OutboxMessage
- Already understood by producers (it's the application-level grouping key)
- Adding a new column would require a migration on the hot outbox table

### 9.2 Why database-coordinated assignment, not client-side?

- PostgreSQL `FOR UPDATE` provides distributed locking without external infrastructure
- Assignment is durable — survives process restarts
- Visible to all consumers without gossip protocol
- Consistent with PeeGeeQ's "PostgreSQL is the only infrastructure" principle

### 9.3 Why generation-based fencing, not lease-based?

- Leases require wall-clock agreement across machines
- Generations are monotonic integers — no clock skew risk
- PostgreSQL sequence guarantees monotonicity
- Simpler to reason about in failure scenarios

### 9.4 Why watermark-based cleanup, not per-message?

- No writes to `outbox` or `outbox_consumer_groups` per message per group
- Cleanup is a single batch UPDATE (or partition DROP for time-partitioned tables)
- Write amplification drops from O(messages × groups) to O(1) per cleanup cycle
- Trade-off: slightly delayed cleanup (watermark advances periodically, not per-message)

### 9.5 What about messages with NULL message_group?

All routed to `__default__` partition. This means:
- Topics that don't care about ordering get a single partition (same as today's behaviour)
- Producers must explicitly set `message_group` to get partitioned ordering
- Backward compatible: existing producers that don't set message_group get identical behaviour

### 9.6 Maximum partition count?

No hard limit. Partitions are strings, not fixed slots. However:
- Very high cardinality (millions of distinct `message_group` values) will make the assignment table large
- Recommend: use coarse partition keys (account ID, tenant ID) not fine-grained (transaction ID)
- The assignment table has one row per (topic, group, partition) — this is the scaling factor

---

## 10. Implementation Phases

### Phase 1: Schema + Offset Tracking (Foundation)

- Migration V015: Create `outbox_partition_offsets`, `outbox_partition_assignments`, `outbox_topic_watermarks`
- Add index `idx_outbox_topic_msggroup_id` on outbox
- Implement `PartitionedOffsetManager` in peegeeq-db: commit/fetch offset, CAS semantics
- Tests: offset commit idempotency, generation fencing, CAS rejection

### Phase 2: Partition Assignment + Rebalance

- Implement `PartitionAssignmentService` in peegeeq-db: join, leave, rebalance, heartbeat
- Consistent-hash assignment algorithm
- Generation-based fencing
- Tests: rebalance on join/leave, stale generation rejection, concurrent rebalance serialization

### Phase 3: Partitioned Fetch + Consumption

- Implement `PartitionedFetcher` in peegeeq-db: offset-based fetch per partition
- Integrate with LISTEN/NOTIFY: notification wakes up assigned partitions only
- Consumer loop: fetch → process sequentially → commit offset
- Tests: ordering guarantee within partition, parallel processing across partitions

### Phase 4: Watermark Sweep + Cleanup

- Implement `WatermarkCalculator` background job
- Mark messages below watermark as COMPLETED
- Optional: partition-based DROP for time-partitioned outbox tables
- Tests: watermark advances correctly, cleanup honours watermark

### Phase 5: API + REST Surface

- Add methods to `SubscriptionService`
- Implement REST endpoints for join/leave/fetch/commit
- Integrate with existing `PeeGeeQRestServer` route registration
- Tests: full end-to-end REST round-trip with partitioned ordering

### Phase 6: Native Consumer Integration

- Add OFFSET_WATERMARK mode to `PgNativeQueueConsumer`
- Auto-join partitioned group on subscribe
- Auto-commit offsets after handler callback
- Rebalance listener callback for application coordination
- Tests: multi-instance consumption with guaranteed ordering

---

## 11. Open Questions

1. **Partition rebalance notification**: Should consumers get a callback before partitions are revoked? (Kafka-style `onPartitionsRevoked` / `onPartitionsAssigned`). This enables the consumer to flush in-progress work before losing a partition.

2. **Co-located transaction commit**: The exactly-once pattern (business write + offset commit in same transaction) only works when the consumer's business database is the same PostgreSQL instance. For external databases, at-least-once is the guarantee. Is this acceptable?

3. **Partition count hint**: The `partition_count` column on subscriptions — is this a hard limit (reject messages that would create a new partition) or a soft hint (for pre-creating assignment slots)?

4. **Dead partition cleanup**: When a `message_group` value has no more pending messages, should its assignment be removed? Or kept for future messages with the same key?

5. **Consumer group migration**: Can an existing REFERENCE_COUNTING topic be migrated to OFFSET_WATERMARK? What happens to in-flight `outbox_consumer_groups` rows during migration?
