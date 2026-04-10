# Consumer Group Fan-Out — Outstanding Tasks

**Purpose**: Consolidated list of all remaining unimplemented work for consumer group fan-out.  
**Created**: 2026-04-09  
**Source Documents**: Consolidated from `CONSUMER_GROUP_IMPLEMENTATION_TRACKER.md`, `CONSUMER_GROUP_SOURCE_VERIFICATION_FINDINGS.md`, `PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md`, `PEEGEEQ_CONSUMER_GROUP_FANOUT_GUIDE.md`, `PEEGEEQ_CONSUMER_GROUPS_BACKFILL_PERFORMANCE_VALIDATION.md` (all now archived).  
**Design Reference**: [PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md](../archived/PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md) (archived)

---

## Summary

3 areas of work remain. Everything else (core fan-out, subscription management, dead consumer detection/cleanup/resurrection, flapping protection, backfill with rate limiting, graceful shutdown, admin force-remove, tracing instrumentation, Prometheus metrics, alerting endpoints, monitoring endpoints) is fully implemented and tested.

| Area | Priority | Status |
|------|----------|--------|
| Fan-Out Trace Branching | LOW | Design complete, implementation not started |
| Fanout Retry & DLQ Automation | MEDIUM | Infrastructure exists elsewhere, not wired to fanout |
| Offset/Watermark Mode + Partitioned Consumption | LOW | Full design complete, no implementation |

---

## Task 1: Fan-Out Trace Propagation Implementation

**Priority**: LOW  
**Previous Task ID**: L9 (from Implementation Tracker)  
**Design Document**: [CONSUMER_GROUP_FANOUT_TRACE_PROPAGATION.md](../tracing-observability/CONSUMER_GROUP_FANOUT_TRACE_PROPAGATION.md)

### What

When one message is delivered to N consumer groups, create a child span per group so fan-out is visible as a span tree in Jaeger/Zipkin. Currently, per-class tracing (`TraceCtx`/`mdcScope()`) is implemented across all consumer group operational code, but each class creates standalone `TraceCtx.createNew()` traces with no parent linkage. `AsyncTraceUtils` (which provides `traceAsyncAction()`, `publishWithTrace()`, `requestWithTrace()`, `tracedConsumer()`) exists and is tested but is **not used** in the consumer group fan-out code path. The result is that fan-out delivery produces independent traces per class rather than a connected span tree.

### Where

There is no single "delivery loop that dispatches to N groups." Each consumer group independently fetches the same message via its own `OutboxConsumer` → `ConsumerGroupFetcher.fetchMessages()` → `OutboxConsumerGroup.distributeMessage()` chain. The child span creation therefore belongs at one of:

- **`ConsumerGroupFetcher.fetchMessages()`** — replace `TraceCtx.createNew()` with a child span derived from the message's `traceparent` header (connects fetch to publish trace)
- **`OutboxConsumerGroup.distributeMessage()`** — create a child span per-group when routing to a member (connects group delivery to fetch trace)
- **`CompletionTracker.markCompleted()`/`markFailed()`** — replace `TraceCtx.createNew()` with a child span from the processing trace (connects completion to delivery trace)

### Prerequisite

`TraceCtx.childSpan()` infrastructure already exists and is tested.

### Acceptance Criteria

- Each consumer group delivery creates a child span from the message's root trace
- Span attributes include `topic`, `group_name`, `message_id`
- Fan-out is visible as parallel branches in a trace visualiser
- Existing `ConsumerTracingTest` updated or extended to verify child span creation

### Current Tracing State (for context)

All consumer group code already has per-class `TraceCtx.createNew()` + `TraceContextUtil.mdcScope()` instrumentation:

| Class | Tracing |
|-------|---------|
| `DeadConsumerDetectionJob` | Trace per detection run, propagated through entire compose chain |
| `ConsumerGroupFetcher` | Trace at `fetchMessages()` entry and result |
| `CompletionTracker` | Trace at `markCompleted()`/`markFailed()` entry, all internal log points |
| `BackfillService` | Comprehensive trace through entire recursive batch chain |

The missing piece is the **span hierarchy** connecting a message's publish trace to per-group delivery traces. `AsyncTraceUtils` should be adopted in the fan-out path to replace the standalone `TraceCtx.createNew()` calls with proper parent→child span propagation.

---

## Task 2: Fanout Retry & DLQ Automation

**Priority**: MEDIUM  
**Source**: Verification Findings, Design Doc §12

### What

Wire fanout-specific retry and dead-letter-queue automation into the consumer group path. Currently:

- `CompletionTracker.markFailed(...)` exists and updates failed status plus `retry_count`
- No production fanout workflow automatically retries failed consumer-group rows
- No production fanout workflow moves failed fanout rows into the database dead letter queue

DLQ and retry infrastructure exists elsewhere in the repository (`DeadLetterQueueManager`, `FilterRetryManager`, `PgNativeQueueConsumer` retry logic) — the missing piece is consumer-group fanout integration with that infrastructure.

### Where

Integration between `CompletionTracker` failure handling and existing retry/DLQ infrastructure.

### Relevant Source Files

- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/consumer/CompletionTracker.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/deadletter/DeadLetterQueueManager.java`
- `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueConsumer.java`
- `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/resilience/FilterRetryManager.java`

### Acceptance Criteria

- After `markFailed()` with `retry_count < maxRetries`, the consumer-group row is automatically re-queued for processing
- After `markFailed()` with `retry_count >= maxRetries`, the consumer-group row is moved to the dead letter queue
- DLQ entries include the original message, consumer group name, failure reason, and retry history
- Integration tests verify retry → eventual DLQ flow end-to-end

---

## Task 3: Offset/Watermark Mode + Partitioned Consumption

**Priority**: LOW (full design complete, large implementation scope)  
**Design Reference**: [PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md §19](../archived/PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md) (archived)

### Problem Statement

PeeGeeQ's current consumer group model (REFERENCE_COUNTING) does NOT provide:

1. **Partition affinity** — messages with the same logical key can be processed by any consumer instance, in any order
2. **Strict per-key ordering** — message N+1 for key K can begin processing before message N is committed
3. **Offset-based progress** — no "where am I in the stream" cursor; progress is per-message via `outbox_consumer_groups` rows

These are required for: financial transaction processing (per-account ordering), state machine projections, event sourcing read models.

### Design Summary

OFFSET_WATERMARK is a second completion tracking mode alongside the existing REFERENCE_COUNTING mode. Key differences:

| Aspect | Reference Counting (current) | Offset/Watermark (planned) |
|--------|------------------------------|---------------------------|
| **Scale** | ≤8-16 consumer groups | 16-100+ consumer groups |
| **Write amplification** | O(messages × groups) | O(1) per batch (offset commit) |
| **Ordering** | No per-key ordering | Guaranteed per-partition ordering |
| **Progress tracking** | Per-message `outbox_consumer_groups` rows | Per-(group, partition) offset cursor |
| **Cleanup** | Counter-based auto-complete | Watermark sweep + optional partition DROP |
| **Max groups** | 64 (bitmap limit) | Unlimited |

### Schema (3 new tables)

```sql
-- Partition assignments: which consumer instance owns which partitions
CREATE TABLE IF NOT EXISTS {schema}.outbox_partition_assignments (
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

-- Per-group, per-partition offset tracking (replaces outbox_consumer_groups for this mode)
CREATE TABLE IF NOT EXISTS {schema}.outbox_partition_offsets (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    partition_key VARCHAR(255) NOT NULL,
    committed_offset BIGINT NOT NULL DEFAULT 0,
    committed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    pending_offset BIGINT,
    pending_since TIMESTAMP WITH TIME ZONE,
    generation INT NOT NULL DEFAULT 1,
    UNIQUE(topic, group_name, partition_key)
);

-- Per-topic watermark — the safe cleanup boundary
CREATE TABLE IF NOT EXISTS {schema}.outbox_topic_watermarks (
    topic VARCHAR(255) PRIMARY KEY,
    watermark_id BIGINT NOT NULL DEFAULT 0,
    watermark_updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
```

### Additional Index

```sql
-- Critical index for offset-based consumption
CREATE INDEX idx_outbox_topic_msggroup_id
    ON {schema}.outbox(topic, message_group, id)
    WHERE status IN ('PENDING', 'PROCESSING');
```

### Key Design Decisions

| Decision | Rationale |
|----------|----------|
| Re-use `message_group` as partition key | Already exists on outbox table, no schema change needed |
| Database-coordinated assignment | PostgreSQL `FOR UPDATE` provides distributed locking without external infrastructure |
| Generation-based fencing | Monotonic integers avoid clock skew risk; simpler than leases |
| Watermark-based cleanup | O(1) per cleanup cycle; partition DROP is instant |
| NULL message_group → `__default__` | Backward compatible |

### Implementation Phases

| Phase | Scope | Key Deliverables |
|-------|-------|------------------|
| **1** | Schema + Offset Tracking | Migration, `PartitionedOffsetManager`, offset commit/fetch, CAS semantics, generation fencing tests |
| **2** | Partition Assignment + Rebalance | `PartitionAssignmentService`, consistent-hash assignment, generation-based fencing, rebalance serialization tests |
| **3** | Partitioned Fetch + Consumption | `PartitionedFetcher`, offset-based fetch per partition, LISTEN/NOTIFY integration, ordering guarantee tests |
| **4** | Watermark Sweep + Cleanup | `WatermarkCalculator` background job, mark below watermark as COMPLETED, optional partition DROP |
| **5** | API + REST Surface | `SubscriptionService` additions (join/leave/fetch/commit), REST endpoints, end-to-end REST tests |
| **6** | Native Consumer Integration | OFFSET_WATERMARK mode in `PgNativeQueueConsumer` and `OutboxConsumerGroup`, auto-join/leave, auto-commit, rebalance callbacks |

### API Surface (planned)

New methods on `SubscriptionService`:
- `joinPartitionedGroup(topic, groupName, instanceId)` → `Future<PartitionAssignment>`
- `leavePartitionedGroup(topic, groupName, instanceId)` → `Future<Void>`
- `fetchPartitioned(topic, groupName, partitionKey, batchSize)` → `Future<List<OutboxMessage>>`
- `commitOffset(topic, groupName, partitionKey, offset)` → `Future<Boolean>`
- `getAssignments(topic, groupName, instanceId)` → `Future<List<PartitionAssignment>>`

REST endpoints:
- `POST .../partitions/join` — register consumer instance
- `DELETE .../partitions/leave` — deregister consumer instance
- `GET .../partitions` — list current assignments
- `POST .../partitions/:partitionKey/fetch` — fetch next batch
- `POST .../partitions/:partitionKey/commit` — commit offset

### Open Questions

1. Should consumers get a callback before partitions are revoked (Kafka-style `onPartitionsRevoked`/`onPartitionsAssigned`)?
2. Co-located transaction commit (exactly-once) only works when business DB = same PostgreSQL. Acceptable?
3. Is `partition_count` on subscriptions a hard limit or soft hint?
4. When a `message_group` has no more pending messages, remove its assignment or keep?
5. Can an existing REFERENCE_COUNTING topic be migrated to OFFSET_WATERMARK? Migration path for in-flight `outbox_consumer_groups` rows?

### Related: Bitmap Tracking Optimization

The current REFERENCE_COUNTING mode uses a simple integer counter (`completed_consumer_groups`). The design includes a bitmap optimization (`completed_groups_bitmap BIGINT`) that uses bitwise operations for more efficient tracking (up to 64 groups). This is documented in the design but not implemented. If REFERENCE_COUNTING needs to scale beyond its current limit, bitmap tracking should be implemented before moving to the full OFFSET_WATERMARK mode.

---

## Task 4: Pre-GA Decision Checkpoints

**Priority**: Must complete before declaring fan-out production-ready  
**Design Reference**: [PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md §16](../archived/PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md) (archived)

These are mandatory load tests and chaos engineering scenarios, not code features.

### 4.1 Load Test: Fanout Write Amplification

Prove the chosen completion tracking mode sustains target throughput without table bloat, WAL saturation, or latency degradation.

| Test ID | Mode | Consumer Groups | Msg/Sec | Duration |
|---------|------|-----------------|---------|----------|
| LT-1 | Reference Counting | 4 | 10,000 | 1 hour |
| LT-2 | Reference Counting | 8 | 10,000 | 1 hour |
| LT-3 | Reference Counting | 16 | 10,000 | 1 hour |
| LT-4 | Reference Counting | 32 | 10,000 | 1 hour (expected to FAIL — prove limit) |

**Success thresholds**: Table bloat < 20%, WAL rate < 50% of I/O capacity, cleanup rate > production rate, p95 consumer fetch latency < 10ms.

### 4.2 Kill-and-Resurrect Chaos Test

Verify dead consumer detection, cleanup, and resurrection keeps database growth stable and doesn't stall other groups.

```
T0:  Start 3 consumer groups at 1,000 msg/sec
T5:  SIGKILL one group (no graceful shutdown)
T10: Verify marked DEAD, messages decremented, other groups unaffected
T15: Verify database size stable
T20: Resurrect with bounded backfill (maxBackfill=100k)
T25: Verify all groups processing, database stable, no stuck PENDING messages
```

**Success criteria**: Dead detection within 5min, database growth < 10% during dead period, bounded backfill (≤100k not all 900k), no stalls in other groups, steady state within 30min.

### 4.3 Backfill vs OLTP Contention Test

Prove backfill with rate limiting does not degrade OLTP performance.

```
Setup: 5M pending messages, 2 active groups at 1k msg/sec, 500 TPS OLTP workload
T0:   Baseline OLTP p95 (target < 50ms)
T1:   Start aggressive backfill (batch 50k, max 200k msg/sec)
T2-30: Monitor OLTP p95 every 60 seconds
```

**Success criteria**: OLTP p95 < 100ms (2x baseline) throughout, backfill auto-throttles when OLTP degrades.

### 4.4 Multi-Tenant Isolation Test

Verify high-volume topics don't starve low-volume topics.

```
Setup: Topic A at 10k msg/sec with 10 groups, Topic B at 10 msg/sec with 2 groups
Run:   1 hour concurrent
```

**Success criteria**: Topic B p95 fetch latency < 50ms, processing lag < 10 seconds.

---

## Dropped Items (intentional, recorded for completeness)

These were evaluated and intentionally not implemented:

| Item | Reason |
|------|--------|
| `peegeeq_consumer_group_processing_seconds` Prometheus metric | Invasive hot-path change (timer around message handler), no concrete need |
| `peegeeq_backfill_progress_ratio` Prometheus metric | Invasive wiring change, no concrete need |
