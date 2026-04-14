# Partitioned Consumption — Design & TDD Plan

**Purpose**: Design, schema, TDD test plan, and concurrency invariants for OFFSET_WATERMARK partitioned consumption mode.  
**Created**: 2026-04-09  
**Source Documents**: Consolidated from `CONSUMER_GROUP_IMPLEMENTATION_TRACKER.md`, `CONSUMER_GROUP_SOURCE_VERIFICATION_FINDINGS.md`, `PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md`, `PEEGEEQ_CONSUMER_GROUP_FANOUT_GUIDE.md`, `PEEGEEQ_CONSUMER_GROUPS_BACKFILL_PERFORMANCE_VALIDATION.md` (all now archived).  
**Design Reference**: [PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md](../archived/PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md) (archived)

---

## Summary

2 areas of work remain. Everything else (core fan-out, subscription management, dead consumer detection/cleanup/resurrection, flapping protection, backfill with rate limiting, graceful shutdown, admin force-remove, tracing instrumentation, fan-out trace propagation, fanout retry/DLQ automation, Prometheus metrics, alerting endpoints, monitoring endpoints) is fully implemented and tested.

| Area | Priority | Status |
|------|----------|--------|
| Fan-Out Trace Branching | LOW | **COMPLETED** (2026-04-11) |
| Fanout Retry & DLQ Automation | MEDIUM | **COMPLETED** (2026-04-11) |
| Consumer Group Lifecycle Alignment | HIGH | **COMPLETED** (2026-04-13) |
| Offset/Watermark Mode + Partitioned Consumption | LOW | Full design complete, no implementation |

---

## Task 1: Fan-Out Trace Propagation Implementation — COMPLETED

**Priority**: LOW  
**Previous Task ID**: L9 (from Implementation Tracker)  
**Design Document**: [CONSUMER_GROUP_FANOUT_TRACE_PROPAGATION.md](../tracing-observability/CONSUMER_GROUP_FANOUT_TRACE_PROPAGATION.md)  
**Completed**: 2026-04-11

### Implementation Summary

Child spans are now created per consumer group from each message's `traceparent` header, producing a proper span tree visible in Jaeger/Zipkin.

**Changes:**
- `OutboxConsumer.processRow()` — extracts `traceparent` from message headers, creates child span via `traceCtx.childSpan("consumer-group:" + groupName + "/process")` when `consumerGroupName` is set
- `ConsumerGroupFetcher.fetchMessages(topic, groupName, batchSize, parentTrace)` — new overload accepts parent trace, creates child span for fetch operations
- `CompletionTracker.markCompleted(messageId, groupName, topic, parentTrace)` — new overload creates child span for completion tracking
- `CompletionTracker.markFailed(messageId, groupName, topic, errorMessage, parentTrace)` — new overload creates child span for failure tracking
- `ConsumerTracingTest` — 15 tests covering child span creation, parent linkage, MDC attributes, fan-out parallel spans, backward compatibility
- `DistributedTracingTest` — 2 end-to-end tracing tests
- `TraceCtxTest` — child span unit tests

### Acceptance Criteria Verification

- ✅ Each consumer group delivery creates a child span from the message's root trace
- ✅ Span attributes include `topic`, `group_name`, `message_id` (via MDC)
- ✅ Fan-out is visible as parallel branches in a trace visualiser (distinct spanIds per group, same traceId, same parentSpanId)
- ✅ `ConsumerTracingTest` extended with 15 tests verifying child span creation and propagation

---

## Task 2: Fanout Retry & DLQ Automation — COMPLETED

**Priority**: MEDIUM  
**Source**: Verification Findings, Design Doc §12  
**Completed**: 2026-04-11

### Implementation Summary

Consumer group retry and dead-letter-queue automation is now fully wired into the PeeGeeQManager runtime lifecycle.

**Pre-existing components (already on branch):**
- `ConsumerGroupRetryService` — resets FAILED→PENDING rows where `retry_count < max_retries`, moves exhausted rows to dead_letter_queue table
- `ConsumerGroupRetryJob` — Vert.x periodic timer wrapper around ConsumerGroupRetryService
- `V016__Add_Consumer_Group_Dead_Letter_Status.sql` — migration adding DEAD_LETTER status to outbox_consumer_groups CHECK constraint
- `ConsumerGroupRetryServiceIntegrationTest` — 14 comprehensive integration tests

**New changes (wiring into runtime):**
- `PeeGeeQConfiguration.QueueConfig` — added `consumerGroupRetryEnabled` (default: true) and `consumerGroupRetryInterval` (default: PT30S, minimum: 10s) configuration properties
- `PeeGeeQManager.startBackgroundTasks()` — creates and starts ConsumerGroupRetryJob when enabled, following the DeadConsumerDetectionJob pattern
- `PeeGeeQManager.stopBackgroundTasks()` — stops ConsumerGroupRetryJob on shutdown
- `ConsumerGroupRetryJobLifecycleTest` — 3 lifecycle integration tests verifying start/stop/disabled behavior

### Acceptance Criteria Verification

- ✅ After `markFailed()` with `retry_count < maxRetries`, the consumer-group row is automatically re-queued for processing
- ✅ After `markFailed()` with `retry_count >= maxRetries`, the consumer-group row is moved to the dead letter queue
- ✅ DLQ entries include the original message, consumer group name, failure reason, and retry history
- ✅ Integration tests verify retry → eventual DLQ flow end-to-end (14 tests)
- ✅ Runtime lifecycle wired into PeeGeeQManager (3 lifecycle tests)

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

### TDD Implementation Plan

Each phase follows strict TDD: write the test first, run it to see it fail, then write the minimum implementation to pass. Tests are integration tests using TestContainers against real PostgreSQL — no mocks, no fakes.

#### Open Question Decisions (required before Phase 1)

| # | Question | Decision |
|---|----------|----------|
| 1 | Partition revocation callback? | **Defer.** Phase 1-4 use database-level fencing (generation check). Callbacks are a consumer-API concern for Phase 6. |
| 2 | Co-located exactly-once only? | **Yes, acceptable.** External DBs get at-least-once with idempotency. Document this as a known constraint. |
| 3 | `partition_count` hard or soft? | **Soft hint.** Partitions are discovered dynamically from `message_group` values. Count is advisory for pre-allocation only. |
| 4 | Remove empty partition assignments? | **Keep.** Avoids rebalance churn when a key temporarily goes idle. Clean up via configurable TTL in watermark sweep. |
| 5 | Mode migration path? | **Defer to Phase 6.** Initial implementation requires topic creation with OFFSET_WATERMARK. Migration from REFERENCE_COUNTING is a separate task. |

---

#### Phase 1: Schema + Offset Tracking

**Goal**: Migration creates tables. `PartitionedOffsetManager` can commit/fetch offsets with CAS and generation fencing.

**New files**:
- `V017__Create_Offset_Watermark_Tables.sql`
- `PartitionedOffsetManager.java`
- `PartitionOffset.java` (record)
- `PartitionedOffsetManagerIntegrationTest.java`

**Test class**: `PartitionedOffsetManagerIntegrationTest` (extends `BaseIntegrationTest`)

| # | Test | Proves | Red → Green |
|---|------|--------|-------------|
| 1.1 | `testInitializeOffset_createsRowWithZeroOffset` | First offset init creates row with `committed_offset=0`, correct generation | Red: no table/class. Green: migration + `initializeOffset()` |
| 1.2 | `testInitializeOffset_idempotent` | Calling init twice for same (topic, group, partition) returns existing row, no error | Red: ON CONFLICT not handled. Green: UPSERT semantics |
| 1.3 | `testCommitOffset_advancesForward` | Commit offset 10 → read back 10. Commit 20 → read back 20. `committed_at` updated. | Red: no `commitOffset()`. Green: UPDATE with CAS |
| 1.4 | `testCommitOffset_rejectsBackwardMove` | Commit 20, then commit 10 → returns false, offset stays 20 | Red: no CAS guard. Green: `WHERE committed_offset < $4` |
| 1.5 | `testCommitOffset_rejectsStaleGeneration` | Init at gen=1. Externally bump gen to 2. Commit with gen=1 → returns false | Red: no generation check. Green: `WHERE generation = $5` |
| 1.6 | `testGetOffset_returnsCurrentState` | After commit, `getOffset()` returns correct committed_offset, generation, no pending | Red: no `getOffset()`. Green: SELECT query |
| 1.7 | `testGetOffset_notFound_returnsEmpty` | `getOffset()` for non-existent (topic, group, partition) → empty Optional | Red: NPE or wrong return. Green: handle empty result set |
| 1.8 | `testSetPendingOffset_tracksInFlightBatch` | After fetch, `setPendingOffset(50)` sets pending_offset=50, pending_since=now | Red: no `setPendingOffset()`. Green: UPDATE pending columns |
| 1.9 | `testCommitOffset_clearsPending` | Set pending=50, commit=50 → pending_offset=NULL, pending_since=NULL | Red: pending not cleared. Green: SET pending_offset=NULL in commit |
| 1.10 | `testSetPendingOffset_rejectsStaleGeneration` | Set pending with wrong generation → returns false | Red: no generation check on pending. Green: WHERE generation=$N |
| 1.11 | `testMultiplePartitions_independentOffsets` | Partition A at offset 100, partition B at offset 200 — each independent | Red: partition isolation not proven. Green: correct WHERE clause |
| 1.12 | `testMultipleGroups_independentOffsets` | group-1 at offset 100, group-2 at offset 50 for same partition — independent | Red: group isolation not proven. Green: correct WHERE clause |
| 1.13 | `testBumpGeneration_incrementsAndReturns` | `bumpGeneration()` increments gen 1→2, returns new generation | Red: no method. Green: UPDATE generation = generation + 1 RETURNING |
| 1.14 | `testBumpGeneration_invalidatesStalePending` | Bump gen → pending_offset set to NULL for old-gen rows | Red: stale pending survives. Green: clear pending on gen bump |

---

#### Phase 2: Partition Assignment + Rebalance

**Goal**: `PartitionAssignmentService` assigns partitions to consumer instances using consistent hashing, serialized by `FOR UPDATE`, fenced by generation.

**New files**:
- `PartitionAssignmentService.java`
- `PartitionAssignment.java` (record)
- `PartitionAssignmentIntegrationTest.java`

**Test class**: `PartitionAssignmentIntegrationTest` (extends `BaseIntegrationTest`)

| # | Test | Proves | Red → Green |
|---|------|--------|-------------|
| 2.1 | `testJoinGroup_firstInstance_getsAllPartitions` | Single instance joining gets all discovered partitions assigned | Red: no class. Green: join + discover + assign |
| 2.2 | `testJoinGroup_secondInstance_triggersRebalance` | Two instances → partitions split. Neither gets all. Each gets at least one. | Red: no rebalance. Green: consistent-hash redistribution |
| 2.3 | `testJoinGroup_incrementsGeneration` | Each join bumps `rebalance_generation` on subscription row | Red: gen stays 1. Green: UPDATE rebalance_generation |
| 2.4 | `testLeaveGroup_triggersRebalance` | Instance leaves → its partitions reassigned to remaining instances | Red: no `leaveGroup()`. Green: delete assignments + rebalance |
| 2.5 | `testLeaveGroup_lastInstance_cleansUp` | Last instance leaves → no assignments remain, offsets preserved | Red: orphan rows. Green: cleanup logic |
| 2.6 | `testRebalance_minimizesMovement` | 3 instances, add 4th → most partitions stay with original owner | Red: full reshuffle. Green: consistent-hash stability |
| 2.7 | `testRebalance_serializedUnderConcurrency` | 5 concurrent joinGroup calls → exactly one winner per rebalance epoch | Red: race condition. Green: FOR UPDATE serialization |
| 2.8 | `testGetAssignments_returnsCorrectPartitions` | After join, `getAssignments(instanceId)` lists only that instance's partitions | Red: no method. Green: SELECT WHERE assigned_instance_id |
| 2.9 | `testHeartbeat_updatesTimestamp` | `heartbeat()` updates `last_heartbeat_at` on assignments | Red: no heartbeat update. Green: UPDATE last_heartbeat_at |
| 2.10 | `testStaleAssignment_cleanedOnRebalance` | Old-gen assignments deleted during rebalance | Red: stale rows persist. Green: DELETE WHERE generation < new_gen |
| 2.11 | `testDiscoverPartitions_includesDefaultForNullGroup` | Messages with NULL message_group → `__default__` partition discovered | Red: NULL skipped. Green: COALESCE(message_group, '__default__') |
| 2.12 | `testDiscoverPartitions_dynamicAsMessagesArrive` | Insert messages with new message_group → next rebalance includes it | Red: static partition list. Green: dynamic discovery query |

**Concurrency invariant tests** (prove correctness under contention):

| # | Test | Proves | Red → Green |
|---|------|--------|-------------|
| 2.13 | `testRebalanceStorm_rapidJoinLeave_generationsMonotonic` | 5 instances rapidly join/leave in parallel (20 operations total) → generations strictly monotonic, every assignment row has valid generation, no orphan assignments with stale generation | Red: lost updates or non-monotonic gen. Green: FOR UPDATE + single-writer serialization |
| 2.14 | `testConcurrentJoinAndLeave_noOrphanAssignments` | Instance A joins while instance B leaves simultaneously → final assignment state is consistent: all partitions assigned, no duplicates, no unassigned gaps | Red: race leaves orphaned or double-assigned partitions. Green: serialized rebalance within transaction |
| 2.15 | `testRebalance_whileOffsetCommitInFlight` | Consumer commits offset with gen=1, concurrent rebalance bumps gen to 2 → commit returns false (fenced), new owner starts from last committed offset (not the rejected one) | Red: stale commit accepted or new owner starts from wrong offset. Green: generation CAS on commit + correct offset read after rebalance |

---

#### Phase 3: Partitioned Fetch + Consumption

**Goal**: `PartitionedFetcher` fetches messages per-partition in offset order, respecting generation fencing. Integrates with LISTEN/NOTIFY.

**New files**:
- `PartitionedFetcher.java`
- `PartitionedFetcherIntegrationTest.java`

**Test class**: `PartitionedFetcherIntegrationTest` (extends `BaseIntegrationTest`)

| # | Test | Proves | Red → Green |
|---|------|--------|-------------|
| 3.1 | `testFetch_returnsMessagesInIdOrder` | 10 messages → fetch returns ids in ascending order | Red: no class. Green: ORDER BY o.id ASC |
| 3.2 | `testFetch_startsAfterCommittedOffset` | Offset at 5 → fetch returns only messages with id > 5 | Red: starts from 0. Green: WHERE o.id > committed_offset |
| 3.3 | `testFetch_respectsBatchSize` | 20 messages, batchSize=5 → returns exactly 5 | Red: returns all. Green: LIMIT $4 |
| 3.4 | `testFetch_onlyMatchingPartition` | Messages in partition A and B → fetch(A) returns only A's messages | Red: cross-partition leak. Green: WHERE message_group = $2 |
| 3.5 | `testFetch_nullMessageGroup_usesDefault` | Messages with NULL message_group → fetchable via `__default__` partition | Red: NULL not matched. Green: COALESCE handling |
| 3.6 | `testFetch_setsPendingOffset` | After fetch, `outbox_partition_offsets.pending_offset` = last fetched id | Red: pending not set. Green: UPDATE pending_offset |
| 3.7 | `testFetch_rejectsStaleGeneration` | Fetch with old generation → empty result or error | Red: stale fetch succeeds. Green: generation check |
| 3.8 | `testFetch_skipLockedPreventsDoubleDelivery` | Two concurrent fetches on same partition → no overlap (FOR UPDATE SKIP LOCKED) | Red: duplicate messages. Green: SKIP LOCKED |
| 3.9 | `testFetch_emptyPartition_returnsEmptyList` | No pending messages → returns empty list, no error | Red: NPE or exception. Green: handle empty result set |
| 3.10 | `testFetch_onlyPendingAndProcessingStatus` | COMPLETED/FAILED messages skipped | Red: includes completed. Green: WHERE status IN ('PENDING', 'PROCESSING') |
| 3.11 | `testSequentialDelivery_withinPartition` | Fetch batch, commit, fetch next → no gaps, no reordering | Red: gaps. Green: correct offset advancement |

**Concurrency invariant tests** (prove delivery guarantees under failure and contention):

| # | Test | Proves | Red → Green |
|---|------|--------|-------------|
| 3.12 | `testCrashRecovery_atLeastOnceDelivery` | Consumer A fetches batch (ids 1-10, pending_offset=10), crashes (no commit). New consumer B takes partition (rebalance bumps generation). B fetches → receives ids 1-10 again (starts from committed_offset=0). Zero messages lost. | Red: B starts from pending_offset, skipping uncommitted messages. Green: fetch uses committed_offset (not pending), generation bump clears pending |
| 3.13 | `testRebalanceDuringInflightBatch_oldOwnerFenced` | Consumer A fetches batch with gen=1, sets pending. Rebalance bumps gen to 2, partition reassigned to B. A tries to commit with gen=1 → rejected (false). B fetches from committed_offset → gets the same messages A had in-flight. No messages lost, no duplicates for committed work. | Red: A's stale commit accepted, or B skips A's uncommitted batch. Green: generation fencing on commit + fetch from committed_offset |
| 3.14 | `testNoGapNoSkip_acrossMultipleFetchCommitCycles` | Insert 100 messages (ids 1-100). Fetch in batches of 10, commit after each. After 10 cycles, verify: every message id 1-100 was delivered exactly once, in strictly ascending order within each batch, with no gaps between batches (batch N+1 starts at exactly last_committed+1). | Red: gap between batches or message skipped. Green: committed_offset = last id in batch, next fetch WHERE id > committed_offset |
| 3.15 | `testConcurrentFetchOnSamePartition_noOverlap` | Two consumers assigned same partition (simulates pre-rebalance race). Both fetch concurrently → returned message sets have zero intersection (SKIP LOCKED). Combined set covers the full range. Neither consumer sees a gap in its own batch. | Red: overlapping message ids. Green: FOR UPDATE SKIP LOCKED ensures mutual exclusion |
| 3.16 | `testFetchAfterGenerationBump_ignoresStaleInFlightBatch` | Consumer A fetches with gen=1, pending_offset set to 50. Generation bumped to 2 (pending cleared). Consumer B fetches with gen=2 → starts from committed_offset (not 50), proving the stale pending from gen=1 is fully discarded. | Red: B starts from stale pending_offset=50. Green: bumpGeneration clears pending, fetch reads committed_offset |

---

#### Phase 4: Watermark Sweep + Cleanup

**Goal**: `WatermarkCalculator` computes MIN(committed_offset) across all active groups, marks messages below watermark as COMPLETED.

**New files**:
- `WatermarkCalculator.java`
- `WatermarkJob.java` (periodic timer, same pattern as `ConsumerGroupRetryJob`)
- `WatermarkCalculatorIntegrationTest.java`

**Test class**: `WatermarkCalculatorIntegrationTest` (extends `BaseIntegrationTest`)

| # | Test | Proves | Red → Green |
|---|------|--------|-------------|
| 4.1 | `testCalculateWatermark_singleGroup_returnsMinOffset` | One group, 3 partitions at offsets 10/20/30 → watermark=10 | Red: no class. Green: MIN(committed_offset) query |
| 4.2 | `testCalculateWatermark_multipleGroups_returnsGlobalMin` | Group A min=100, group B min=50 → watermark=50 | Red: per-group only. Green: MIN across groups |
| 4.3 | `testCalculateWatermark_excludesDeadGroups` | Active group at 100, DEAD group at 10 → watermark=100 (not pinned by dead) | Red: dead group pins watermark. Green: WHERE subscription_status='ACTIVE' |
| 4.4 | `testCalculateWatermark_noActiveGroups_returnsZero` | No active subscriptions → watermark=0 | Red: NPE. Green: COALESCE(MIN(...), 0) |
| 4.5 | `testAdvanceWatermark_onlyMovesForward` | Watermark at 100, calculate returns 50 → stays 100 | Red: watermark retreats. Green: WHERE watermark_id < $2 |
| 4.6 | `testAdvanceWatermark_updatesTimestamp` | After advance, `watermark_updated_at` is recent | Red: stale timestamp. Green: SET watermark_updated_at=NOW() |
| 4.7 | `testSweep_marksMessagesBelowWatermarkCompleted` | 20 messages, watermark=10 → messages 1-10 become COMPLETED, 11-20 unchanged | Red: no sweep. Green: UPDATE SET status='COMPLETED' WHERE id <= $2 |
| 4.8 | `testSweep_idempotent` | Run sweep twice → same result, no errors | Red: double-update conflict. Green: WHERE status IN ('PENDING', 'PROCESSING') |
| 4.9 | `testSweep_doesNotTouchOtherTopics` | Topic A watermark=100, topic B watermark=10 → only topic A's messages swept | Red: cross-topic sweep. Green: WHERE topic=$1 |
| 4.10 | `testSweep_preservesCompletedMessages` | Already-COMPLETED messages not re-updated | Red: unnecessary writes. Green: WHERE status IN ('PENDING', 'PROCESSING') |
| 4.11 | `testWatermarkJob_runsPeriodicSweep` | Start job → after interval, watermark advances | Red: no periodic execution. Green: Vert.x setPeriodic timer |
| 4.12 | `testWatermarkJob_stopCancelsTimer` | Start then stop → no more sweeps fire | Red: timer leaks. Green: cancelTimer on stop |

---

#### Phase 5: API + REST Surface

**Goal**: `SubscriptionService` gets 5 new methods for partitioned consumption. REST endpoints expose them.

**Modified files**:
- `SubscriptionService.java` (peegeeq-api, 5 new default methods)
- `SubscriptionManager.java` (peegeeq-db, implements the 5 methods)

**New files**:
- `PartitionedSubscriptionIntegrationTest.java` (peegeeq-db)
- REST controller additions + REST integration tests (peegeeq-rest)

**Test class**: `PartitionedSubscriptionIntegrationTest` (extends `BaseIntegrationTest`)

| # | Test | Proves | Red → Green |
|---|------|--------|-------------|
| 5.1 | `testJoinPartitionedGroup_returnsAssignments` | `joinPartitionedGroup()` returns assignment list with correct generation | Red: method not on interface. Green: delegate to PartitionAssignmentService |
| 5.2 | `testJoinPartitionedGroup_rejectsReferenceCountingTopic` | REFERENCE_COUNTING topic → IllegalArgumentException | Red: no mode check. Green: validate completionTrackingMode |
| 5.3 | `testLeavePartitionedGroup_triggersRebalance` | Leave → remaining instance gets all partitions | Red: no method. Green: delegate to PartitionAssignmentService |
| 5.4 | `testFetchPartitioned_returnsOrderedMessages` | `fetchPartitioned()` returns messages in id order for partition | Red: no method. Green: delegate to PartitionedFetcher |
| 5.5 | `testFetchPartitioned_rejectsUnassignedPartition` | Fetch partition not assigned to caller → error | Red: no assignment check. Green: verify assignment before fetch |
| 5.6 | `testCommitOffset_advancesAndReturnsTrue` | `commitOffset()` succeeds and returns true | Red: no method. Green: delegate to PartitionedOffsetManager |
| 5.7 | `testCommitOffset_staleGeneration_returnsFalse` | Commit after rebalance with old gen → false | Red: no fencing. Green: CAS + generation check |
| 5.8 | `testGetAssignments_returnsInstancePartitions` | Returns only partitions assigned to the specified instance | Red: no method. Green: SELECT WHERE instance_id |
| 5.9 | `testEndToEnd_joinFetchCommitLeave` | Full lifecycle: join → fetch → commit → leave → watermark advances | Red: integration gaps. Green: all components wired |

**REST tests** (peegeeq-rest, `PartitionedConsumptionRestIntegrationTest`):

| # | Test | Proves |
|---|------|--------|
| 5.10 | `testPostJoin_returnsAssignments` | POST .../join with instanceId → 200 + assignments JSON |
| 5.11 | `testDeleteLeave_triggersRebalance` | DELETE .../leave → 200 + rebalance |
| 5.12 | `testGetPartitions_returnsAssignments` | GET .../partitions → current assignment list |
| 5.13 | `testPostFetch_returnsMessages` | POST .../fetch with batchSize → messages in order |
| 5.14 | `testPostCommit_returnsResult` | POST .../commit with offset → committed:true/false |

---

#### Phase 6: Native Consumer Integration

**Goal**: `PgNativeQueueConsumer` and `OutboxConsumerGroup` automatically detect OFFSET_WATERMARK topics and use partitioned consumption internally.

**Modified files**:
- `PgNativeQueueConsumer.java` (peegeeq-native)
- `OutboxConsumerGroup.java` (peegeeq-outbox)
- `PgNativeConsumerGroup.java` (peegeeq-native)

**Test classes**: 
- `PartitionedNativeConsumerIntegrationTest.java` (peegeeq-native)
- `PartitionedOutboxConsumerGroupIntegrationTest.java` (peegeeq-outbox)

| # | Test | Proves |
|---|------|--------|
| 6.1 | `testStart_offsetWatermarkTopic_autoJoins` | Consumer start → automatically calls joinPartitionedGroup |
| 6.2 | `testClose_autoLeaves` | Consumer close → calls leavePartitionedGroup, rebalance fires |
| 6.3 | `testMessageHandler_receivesInOrder` | Handler receives messages strictly in id order within partition |
| 6.4 | `testAutoCommit_afterHandlerReturns` | After handler completes, offset auto-committed |
| 6.5 | `testRebalance_stopsProcessingRevokedPartitions` | Generation bump → old consumer stops fetching revoked partitions |
| 6.6 | `testMultipleInstances_parallelPartitions` | 2 consumers → each processes different partitions concurrently |
| 6.7 | `testReferenceCountingTopic_unchanged` | Existing REFERENCE_COUNTING topics work exactly as before |
| 6.8 | `testWatermarkSweep_completesProcessedMessages` | After all groups commit past message → watermark advances → message COMPLETED |

**Concurrency invariant tests** (prove end-to-end production correctness with real consumers):

| # | Test | Proves |
|---|------|--------|
| 6.9 | `testMultiConsumer_partitionIsolation_noContamination` | 3 consumers, 6 partitions (2 each). Each consumer receives messages ONLY from its assigned partitions. Messages within each partition arrive in strictly ascending id order. Each consumer's offset advances independently — committing in one consumer has zero effect on another's offset or fetch position. |
| 6.10 | `testConsumerCrash_newConsumerResumes_zeroMessageLoss` | Consumer A processes partitions P1, P2. A crashes after committing P1 offset=50 but before committing P2 (P2 pending=30, committed=0). Rebalance assigns P1, P2 to consumer B. B resumes P1 from offset 50 (no re-delivery of committed work). B resumes P2 from offset 0 (re-delivers the uncommitted batch). Total messages delivered = total messages produced. Zero loss. |
| 6.11 | `testRebalanceUnderLoad_noMessageLoss_noInfiniteRedelivery` | 2 consumers processing 1000 messages at steady rate. Third consumer joins mid-stream (triggers rebalance). Verify: all 1000 messages eventually committed across all partitions, no message permanently stuck in re-delivery loop, total unique committed messages = total produced. Acceptable duplicates during rebalance window, but duplicates must be bounded (< 2x batch size). |
| 6.12 | `testColocatedTransaction_exactlyOnceCommit` | Consumer fetches batch, writes business result to same PostgreSQL database, commits offset — all in a single PG transaction. Transaction commits → offset advanced AND business row visible. Transaction rolls back → offset unchanged AND business row absent. No partial state. |
| 6.13 | `testGenerationFence_preventsZombieConsumer` | Consumer A holds partition P1 at gen=1. Network partition simulated (A stops heartbeating). Rebalance assigns P1 to B at gen=2. A "wakes up" and tries to fetch/commit with gen=1 → all operations rejected. B is the sole owner. No split-brain delivery. |

---

#### Execution Order Summary

```
Phase 1 (Schema + Offset)      → 14 tests → V017 migration + PartitionedOffsetManager
Phase 2 (Assignment + Rebalance)→ 15 tests → PartitionAssignmentService (12 core + 3 concurrency invariants)
Phase 3 (Fetch)                 → 16 tests → PartitionedFetcher (11 core + 5 concurrency invariants)
Phase 4 (Watermark)             → 12 tests → WatermarkCalculator + WatermarkJob
Phase 5 (API + REST)            → 14 tests → SubscriptionService + REST endpoints
Phase 6 (Consumer Integration)  → 13 tests → PgNativeQueueConsumer + OutboxConsumerGroup (8 core + 5 concurrency invariants)
                                  --------
                                  84 tests total
```

Each phase is a separate feature branch merged to master before starting the next. No phase depends on unmerged work. Every test is written and run RED before any implementation code for that test is written.

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

## Task 5: Consumer Group Lifecycle Alignment — COMPLETED

**Priority**: HIGH (pre-requisite for Phase 6 integration)  
**Source**: Cross-review of `PgNativeConsumerGroup` and `OutboxConsumerGroup` (2026-04-12)  
**Completed**: 2026-04-13  
**Goal**: Align both implementations to the best pattern from each before Phase 6 wires partitioned consumption into both.

### Rationale

The two `ConsumerGroup` implementations share the same API contract but diverge in lifecycle management quality. Fixing these before Phase 6 avoids compounding the issues when partitioned engine integration adds more async complexity.

### 5.1 — Adopt state machine in PgNativeConsumerGroup — DONE

**Adopt from**: `OutboxConsumerGroup` `AtomicReference<State>` with `{NEW, STARTING, ACTIVE, STOPPING, CLOSED}`  
**Replace**: Dual `AtomicBoolean` (`active`, `closed`) in `PgNativeConsumerGroup`  
**Why**: The dual-boolean scheme has no STARTING or STOPPING state, which causes:  
- `start()` sets `active=true` before async work begins (callers observe "active" during setup)  
- `close()` can race with `stop()` because both check/set different flags independently  
- No way to distinguish "never started" from "stopped and restartable"

**Files**: `PgNativeConsumerGroup.java`  
**Scope**: Replace `AtomicBoolean active`, `AtomicBoolean closed` with `AtomicReference<State>`. Update all CAS transitions in `start()`, `stop()`, `stopGracefully()`, `close()`, `isActive()`, `addConsumer()`.

### 5.2 — Fix TOCTOU race in PgNativeConsumerGroup.addConsumer — DONE

**Adopt from**: `OutboxConsumerGroup.addConsumer` uses `putIfAbsent` (line 265)  
**Fix**: `PgNativeConsumerGroup.addConsumer` uses `containsKey` + `put` (lines 156-163), which has a time-of-check/time-of-use race under concurrent `addConsumer` calls  
**Files**: `PgNativeConsumerGroup.java`  
**Change**: Replace `containsKey` check + `put` with `putIfAbsent`, throw if existing returned.

### 5.3 — Compose partitioned engine stop future in stopInternal — DONE

**Fix in**: `PgNativeConsumerGroup.stopInternal()` (lines 338-344)  
**Problem**: `partitionedEngine.stop()` returns `Future<Void>` performing async `leaveGroup`, but the future is discarded and the engine reference nulled immediately. Both `stop()` and `stopGracefully()` report completion before the leave-group RPC finishes.  
**Change**: `stopInternal` must return `Future<Void>`. Compose `partitionedEngine.stop()` into the returned future. `stop()` becomes `Future<Void>` (or composes internally). `stopGracefully()` composes subscription cancel → `stopInternal()` sequentially and returns the composed future.

### 5.4 — Compose partitioned engine start future in start(SubscriptionOptions) — DONE

**Fix in**: `PgNativeConsumerGroup.start(SubscriptionOptions)` (lines 286-294) and `start()` (line 192)  
**Problem**: `start()` is `void` and performs async mode detection + async engine startup via fire-and-forget `.onSuccess()`/`.onFailure()`. The `start(SubscriptionOptions)` future completes after subscription creation but before the consumer engine is ready.  
**Change**:  
- `startPartitioned()` must return `Future<Void>` that completes when `partitionedEngine.start(handler)` completes.  
- `start(SubscriptionOptions)` must compose mode detection → `startPartitioned()`/`startReferenceCountingInternal()` into its returned future.  
- `start()` (void) should set state to STARTING, then compose the same async chain, setting ACTIVE on success and resetting to NEW on failure (matching OutboxConsumerGroup's try/catch pattern).

### 5.5 — Stop members on partitioned engine start failure — DONE

**Fix in**: `PgNativeConsumerGroup.startPartitioned()` (lines 236-244)  
**Problem**: Members are started at line 236 before `partitionedEngine.start(handler)` at line 238. If engine start fails, the `.onFailure` handler sets `active=false` but never stops the members. Members remain in started state with no engine feeding them.  
**Change**: Move `members.values().forEach(start)` into the `.onSuccess` handler of `partitionedEngine.start()`, or add `members.values().forEach(stop)` to the `.onFailure` handler.

### 5.6 — Compose closeAsync future in OutboxConsumerGroup stop/close paths — DONE

**Fix in**: `OutboxConsumerGroup.stopInternal()` (lines 471-479) and `close()` (lines 588-592)  
**Problem**: `oc.closeAsync()` returns `Future<Void>` (closes reactive pool), but the future is discarded. `stopGracefully()` returns `Future.succeededFuture()` before the pool close finishes.  
**Change**: `stopInternal()` must return `Future<Void>` composing `closeAsync()`. `stopGracefully()` must compose subscription cancel → `stopInternal()` and return the result.

### 5.7 — Replace ScheduledExecutorService with Vert.x timers in OutboxConsumerGroup

**Status**: SKIPPED — intentional design decision documented below.

**Original proposal**: Replace `sharedFilterScheduler` (`ScheduledExecutorService`) with `Vertx.setTimer()`/`Vertx.setPeriodic()` and remove the blocking `awaitTermination(5, SECONDS)` from `close()`.

**Investigation findings**: `FilterRetryManager` (in `peegeeq-api/.../resilience/`) contains an explicit design note (lines 22-48) documenting why `ScheduledExecutorService` is intentionally used over `vertx.setTimer()`:

1. **Framework agnostic** — `FilterRetryManager` is a core API class that must work outside Vert.x contexts.
2. **API boundary isolation** — The retry mechanism is self-contained; coupling it to Vert.x internals adds unnecessary dependency.
3. **Testability** — Pure JDK scheduling is testable without Vert.x context or event-loop setup.
4. **Not a Verticle** — `OutboxConsumerGroup` and `FilterRetryManager` are not Verticles; they have no event-loop affinity that Vert.x timers would respect.

The `sharedFilterScheduler` is created in `OutboxConsumerGroup`, exposed via `getSharedFilterScheduler()`, and consumed by `OutboxConsumerGroupMember` → `FilterRetryManager`. The per-5.7 note says: "If the outbox module intentionally avoids Vert.x threading for isolation, document that decision and skip this task." This is exactly that case.

**Remaining risk**: `close()` calls `awaitTermination(5, SECONDS)` which blocks the calling thread. If `close()` is ever called from a Vert.x event-loop thread, this would block. Currently `close()` is called from application shutdown paths (not event-loop), so this is acceptable. If this changes in the future, `close()` should be reworked to use `executeBlocking()` or an async shutdown handshake.

### 5.8 — Add fan-out trace propagation to OutboxConsumerGroup.distributeMessage — DONE

**Adopt from**: `PgNativeConsumerGroup.distributeMessage()` (lines 469-522) creates a child span per consumer group from the message's `traceparent` header  
**Implemented in**: `OutboxConsumerGroup.distributeMessage()` — now has `TraceCtx.parseOrCreate(traceparent)` → `childSpan()` → `TraceContextUtil.mdcScope()` with cleanup in `.eventually()`  
**Change**: Add the same `TraceCtx.parseOrCreate(traceparent)` → `childSpan("consumer-group:" + groupName + "/process")` → `TraceContextUtil.mdcScope()` pattern, with `TraceContextUtil.clearTraceMDC()` in `.eventually()`.  
**Files**: `OutboxConsumerGroup.java`, plus test coverage in outbox module.

### 5.9 — Wire connectionManager/serviceId in PgNativeQueueFactory.createConsumerGroup — DONE

**Fix in**: `PgNativeQueueFactory.createConsumerGroup()` (lines 228-230)  
**Implemented**: Factory now passes `connectionManager` and `connectionServiceId` to the full constructor (line 244).

### 5.10 — Adopt try/finally state reset in PgNativeConsumerGroup stop paths — DONE

**Adopt from**: `OutboxConsumerGroup.stopInternal()` uses `try { ... } finally { state.set(State.NEW); }` (lines 463-484)  
**Implemented**: `stopInternal()` has try/catch with `state.compareAndSet(State.STOPPING, State.NEW)` in both the `eventually()` callback and the catch block.

### Execution Order

Tasks have dependencies:

```
5.1 (state machine)  ──→ 5.4 (compose start future)  ──→ 5.5 (members after engine)
       │                                                         
       └──→ 5.10 (try/finally stop)  ──→ 5.3 (compose stop future)
       
5.2 (putIfAbsent)     — independent
5.6 (outbox closeAsync)— independent  
5.7 (vert.x timers)   — independent, needs design decision
5.8 (outbox tracing)   — independent
5.9 (factory wiring)   — independent, Phase 6 prerequisite
```

Recommended order: 5.1 → 5.2 → 5.10 → 5.3 → 5.4 → 5.5 → 5.6 → 5.8 → 5.9 → 5.7

### Verification Criteria

For each sub-task:
- Grep proof that old patterns are absent in touched files
- Compile success for affected modules
- All existing tests pass (core + integration)
- No new test gaps: each behavioral change has test coverage

---

## Dropped Items (intentional, recorded for completeness)

These were evaluated and intentionally not implemented:

| Item | Reason |
|------|--------|
| 5.7 — Replace `ScheduledExecutorService` with Vert.x timers | `FilterRetryManager` intentionally uses JDK scheduling for framework-agnostic design, API boundary isolation, and testability. See 5.7 section above for full rationale. |
| `peegeeq_consumer_group_processing_seconds` Prometheus metric | Invasive hot-path change (timer around message handler), no concrete need |
| `peegeeq_backfill_progress_ratio` Prometheus metric | Invasive wiring change, no concrete need |
