# Consumer Group Fan-Out — Implementation Tracker

**Purpose**: Honest, verified tracking of what is actually implemented vs what the design specifies.  
**Author**: Mark Andrew Ray-Smith, Cityline Ltd  
**Created**: 2026-03-01  
**Last Verified**: 2026-03-01 (updated after comprehensive test coverage pass)  
**Design Reference**: [PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md](PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md)  
**Tracing/Observability References**:  
- [PEEGEEQ_TRACING_ARCHITECTURE_GUIDE.md](../tracing-observability/PEEGEEQ_TRACING_ARCHITECTURE_GUIDE.md) — All async code must use `AsyncTraceUtils` wrappers  
- [PEEGEEQ_TRACING_USER_GUIDE.md](../tracing-observability/PEEGEEQ_TRACING_USER_GUIDE.md) — Defines `consumer_group` as a Prometheus metric label  
- [MONITORING_ENDPOINTS_IMPLEMENTATION_PLAN.md](../tracing-observability/MONITORING_ENDPOINTS_IMPLEMENTATION_PLAN.md) — `/ws/monitoring` already counts consumer groups at summary level

> This document was created because the previous Implementation Plan incorrectly claimed
> "Phases 1-6 COMPLETE" when multiple critical features were missing or non-functional. Every
> status in this document is verified against actual source code, not design intent.

---

## Overall Status Summary

| Area | Status | Detail |
|------|--------|--------|
| Core Fan-Out (Reference Counting) | ✅ DONE | Schema, trigger, fetch, complete, cleanup all functional |
| Topic Configuration | ✅ DONE | CRUD + QUEUE/PUB_SUB semantics |
| Subscription Management | ✅ DONE | Subscribe, pause, resume, cancel, heartbeat, REST API |
| Consumer Group Fetching | ✅ DONE | FOR UPDATE SKIP LOCKED, group-aware |
| Completion Tracking | ✅ DONE | Atomic per-group completion, auto-message-complete on all groups done |
| Zero-Subscription Protection | ✅ DONE | Blocks writes when configured and no active subscriptions |
| Cleanup (fan-out aware) | ✅ DONE | Respects required vs completed counts |
| Dead Consumer Detection | ✅ DONE | Detects, marks DEAD, cleans up messages, runs on schedule via service manager |
| Dead Consumer Operational Logging | ✅ DONE | Structured results, blocked message stats, subscription landscape, critical alerts |
| Dead Consumer Message Cleanup | ✅ DONE | `DeadConsumerGroupCleanup` decrements `required_consumer_groups`, removes orphans, auto-completes |
| Dead Consumer Resurrection | ✅ DONE | `updateHeartbeat()` auto-resurrects DEAD→ACTIVE via conditional SQL |
| Flapping Protection | ❌ MISSING | No `consecutive_failures` — marks DEAD on first timeout |
| Dead Consumer Scheduled Job | ✅ DONE | Wired into `PeeGeeQManager` lifecycle, configurable interval, auto-start/stop |
| Backfill Service | ✅ DONE | Full batch processing with auto-trigger on FROM_BEGINNING subscribe, REST endpoints for trigger/monitor/cancel |
| Backfill Lifecycle Integration | ✅ DONE | Auto-triggers backfill on FROM_BEGINNING subscription via `setBackfillService()` |
| Subscribe REST Endpoint | ✅ DONE | POST creates subscriptions via REST with validation |
| Backfill REST Endpoints | ✅ DONE | POST start, GET progress, DELETE cancel — all via REST |
| Offset/Watermark Mode | ❌ NOT STARTED | Design only |
| Tracing Instrumentation | ❌ NOT STARTED | Zero `TraceCtx`/`AsyncTraceUtils` usage in cleanup/, consumer/, subscription/ packages |
| Prometheus Metrics (consumer groups) | ❌ NOT STARTED | User Guide promises `peegeeq_messages_received_total{consumer_group}` but not implemented |
| Consumer Group Count in Monitoring WS/SSE | ✅ DONE (summary) | Total count exposed via `/ws/monitoring` — no per-group detail |
| Dead Consumer Alerting Endpoint | ❌ NOT STARTED | `DetectionJob` logs alerts but no API surface for programmatic consumption |
| Subscription Health Monitoring Endpoint | ❌ NOT STARTED | `getSubscriptionSummary()` exists but not exposed through any endpoint |
| Backfill Progress Monitoring Endpoint | ❌ NOT STARTED | `getBackfillProgress()` exists but not exposed through any endpoint |
| Fan-Out Trace Propagation Design | ❌ NOT STARTED | No design for how traces branch across N consumer groups |

---

## Table of Contents

1. [Overall Status Summary](#overall-status-summary)
2. [What Is Actually Complete](#what-is-actually-complete)
3. [Dead Consumer Detection — Gap Analysis](#dead-consumer-detection--gap-analysis)
4. [Backfill Support — Gap Analysis](#backfill-support--gap-analysis)
5. [Tracing & Observability — Gap Analysis](#tracing--observability--gap-analysis)
6. [Integration Gaps](#integration-gaps)
7. [Schema Gaps](#schema-gaps)
8. [Test Coverage Gaps](#test-coverage-gaps)
9. [Implementation Tasks](#implementation-tasks)
10. [File Inventory](#file-inventory)

---

## What Is Actually Complete

These components have been verified to work end-to-end with tests passing:

### Schema & Migrations
- [V010__Create_Consumer_Group_Fanout_Tables.sql](../../../peegeeq-migrations/src/main/resources/db/migration/V010__Create_Consumer_Group_Fanout_Tables.sql) — ~450 lines
- Tables: `outbox_topics`, `outbox_topic_subscriptions`, `outbox_consumer_groups`, `processed_ledger`, `partition_drop_audit`, `consumer_group_index`
- Columns added to `outbox`: `required_consumer_groups`, `completed_consumer_groups`, `completed_groups_bitmap`
- Trigger: `set_required_consumer_groups()` — fires BEFORE INSERT, sets count based on topic type
- Functions: `cleanup_completed_outbox_messages()`, `mark_dead_consumer_groups()`, `update_consumer_group_index()`
- 8 indexes for performance
- Rollback script verified

### Topic Configuration
- `TopicConfigService` (264 lines) — full CRUD, QUEUE/PUB_SUB, retention config
- 7 integration tests passing

### Subscription Management
- `SubscriptionManager` (503 lines) — subscribe, pause, resume, cancel, heartbeat, get, list
- Supports FROM_NOW, FROM_BEGINNING, FROM_MESSAGE_ID, FROM_TIMESTAMP start positions
- Reactivates PAUSED/DEAD subscriptions on re-subscribe (but see resurrection gap below)
- REST endpoints: list, get, pause, resume, heartbeat, cancel
- 6+ integration tests passing

### Consumer Group Fetching
- `ConsumerGroupFetcher` (127 lines) — `FOR UPDATE SKIP LOCKED`, group-aware LEFT JOIN
- 4 integration tests passing

### Completion Tracking
- `CompletionTracker` (161 lines) — atomic per-group completion, idempotent, auto-complete on all groups done
- 4 integration tests passing

### Cleanup
- `CleanupService` (199 lines) — fan-out aware, respects completed vs required counts
- 6 integration tests passing

### Zero-Subscription Protection
- `ZeroSubscriptionValidator` (140 lines) — blocks writes when configured
- 7 integration tests passing

### Performance Validation
- 4 performance tests: scaling to 16 groups, mixed topics, backfill vs OLTP concurrency
- Throughput ≥ 30,000 msg/sec validated

---

## Dead Consumer Detection — Gap Analysis

**Design Reference**: Design doc lines 2250-2900 (Question 4: "Dead Consumer Groups")

The design specifies a **5-layer approach**. Here's what actually exists:

### Layer 1: Heartbeat Protocol — ✅ DONE
- `SubscriptionManager.updateHeartbeat()` updates `last_heartbeat_at` and `last_active_at`
- REST endpoint: `POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/heartbeat`
- Schema: `last_heartbeat_at`, `heartbeat_timeout_seconds` columns exist

### Layer 2: Timeout Detection — ✅ DONE (detection + diagnostics)
- `DeadConsumerDetector.java` (~400 lines) correctly identifies timed-out subscriptions with full structured results
- SQL: `UPDATE SET subscription_status = 'DEAD' WHERE last_heartbeat_at + heartbeat_timeout_seconds < NOW()`
- Detection methods: `detectDeadSubscriptions(topic)`, `detectAllDeadSubscriptions()`, `detectAllDeadSubscriptionsWithDetails()`
- Monitoring methods: `countDeadSubscriptions()`, `countEligibleForDeadDetection()`, `getBlockedMessageStats()`, `getSubscriptionSummary()`
- Structured result types: `DeadSubscriptionInfo`, `DetectionResult`, `BlockedMessageStats`, `SubscriptionSummary`
- 14 integration tests across 2 classes — all passing:
  - `DeadConsumerDetectorIntegrationTest` (4 tests) — parallel execution fixed, `@Tag(FLAKY)` removed
  - `DeadConsumerDetectorComprehensiveTest` (10 tests) — covers PAUSED detection, CANCELLED exclusion, already-DEAD re-detection, mixed states, `DetectionResult`/`BlockedMessageStats`/`SubscriptionSummary` APIs, `countEligibleForDeadDetection`, boundary conditions

### Layer 2a: Operational Logging & Diagnostics — ✅ DONE
- `DeadConsumerDetectionJob.java` (~340 lines) provides comprehensive operational logging:
  - **Per-run summary**: run number, detection time, subscriptions checked, topics affected
  - **Per-dead-consumer detail**: group name, last heartbeat, timeout, how long overdue, how long silent
  - **Blocked message impact**: per dead group — PENDING/PROCESSING counts, age of oldest blocked message
  - **Critical alerts**: `ERROR`-level when >1000 messages blocked or messages blocked >24 hours
  - **Healthy run logging**: concise `INFO` line, distinguishes "no dead" vs "pre-existing dead, no new"
  - **Subscription landscape**: active/paused/dead/cancelled counts across all topics on every run
  - **Failure tracking**: consecutive failure counter, escalates after 3+ consecutive failures
  - **Overlap guard**: skips detection if previous run still in progress
  - **Lifetime stats**: `totalRunCount`, `totalDeadDetected`, `totalFailures`, `totalRunTimeMs` — logged on stop, accessible via getters
  - **Human-readable durations**: formats as "2d 3h 15m 30s"

### Layer 3: Automatic Message Cleanup — ✅ DONE
- `DeadConsumerGroupCleanup.java` (~250 lines) — 3-step transactional cleanup per dead group:
  1. Decrement `required_consumer_groups` on PENDING/PROCESSING messages (idempotent, NOT EXISTS guard)
  2. Remove orphaned `outbox_consumer_groups` rows for the dead group
  3. Auto-complete messages where `completed >= required` after decrement
- `cleanupDeadGroup(topic, groupName)` — single-group cleanup within a transaction
- `cleanupAllDeadGroups()` — discovers all DEAD subscriptions and cleans each with `.recover()` error isolation
- Structured `CleanupResult` record with `messagesDecremented`, `orphanRowsRemoved`, `messagesAutoCompleted`
- 9 integration tests across 1 class — all passing:
  - `DeadConsumerGroupCleanupIntegrationTest` (9 tests) — includes error resilience test validating `.recover()` block

### Layer 4: Graceful Shutdown — ❌ NOT IMPLEMENTED
- Design specifies graceful shutdown should mark subscription CANCELLED and drain in-flight messages
- No shutdown hook exists in any consumer code

### Layer 5: Admin Override / Force-Remove — ❌ NOT IMPLEMENTED
- Design specifies admin API to force-remove dead consumer groups
- No such endpoint exists

### Scheduled Job — ✅ DONE
- `DeadConsumerDetectionJob.java` (~460 lines) wraps `DeadConsumerDetector` + `DeadConsumerGroupCleanup` with `vertx.setPeriodic()`
- Configurable interval (default 60s), start/stop lifecycle, manual trigger via `runDetectionOnce()` / `runDetectionOnceWithDetails()`
- Full operational logging (see Layer 2a above)
- Detection → cleanup chained automatically: after detecting dead consumers, `cleanup.cleanupAllDeadGroups()` runs
- Overlap guard: skips detection if previous run still in progress (verified by concurrent guard integration test)
- Lifetime stats accessible: `getTotalRunCount()`, `getTotalDeadDetected()`, `getTotalFailures()`
- Wired into `PeeGeeQManager.startBackgroundTasksReactive()` with auto-start on boot and auto-stop on shutdown
- Configurable via `peegeeq.queue.dead-consumer-detection.enabled` (default: true) and `peegeeq.queue.dead-consumer-detection.interval` (default: 60s)
- 8 integration tests across 1 class — all passing:
  - `DeadConsumerDetectionJobIntegrationTest` (8 tests) — includes end-to-end pipeline test and concurrent overlap guard test

### Resurrection — ✅ DONE
- `SubscriptionManager.updateHeartbeat()` uses conditional SQL: `CASE WHEN subscription_status = 'DEAD' THEN 'ACTIVE' ELSE subscription_status END`
- CTE captures pre-update status for resurrection logging at INFO level
- CANCELLED and PAUSED subscriptions are NOT affected by heartbeat (only DEAD → ACTIVE)
- 3 integration tests: `testHeartbeatResurrectsDeadSubscription`, `testHeartbeatDoesNotResurrectCancelledSubscription`, `testHeartbeatKeepsPausedSubscriptionPaused`
- Note: resurrection does NOT re-increment `required_consumer_groups` or trigger re-backfill for messages cleaned up during DEAD period (future enhancement)

### Flapping Protection — ❌ NOT IMPLEMENTED
- Design doc (Pitfall 2, lines ~2500-2510) specifies `consecutive_failures` column requiring 3+ consecutive heartbeat misses before marking DEAD
- Column does not exist in V010 migration schema
- Detector marks DEAD on first timeout miss — vulnerable to transient network issues

### `outbox_consumer_groups` Orphan Cleanup — ✅ DONE
- When a consumer is marked DEAD, its PENDING rows in `outbox_consumer_groups` are removed by `DeadConsumerGroupCleanup` step 2 (orphan removal)
- Handled automatically as part of the detection → cleanup pipeline

---

## Backfill Support — Gap Analysis

### Backfill Service — ✅ DONE (isolated)
- `BackfillService.java` (545 lines) — full batch-based backfill with:
  - Checkpoint-based resumability (`backfill_checkpoint_id`, `backfill_processed_messages`)
  - Status lifecycle: NONE → IN_PROGRESS → COMPLETED/CANCELLED/FAILED
  - Per-message: increments `required_consumer_groups`, creates PENDING `outbox_consumer_groups` row
  - Cancellation via `cancelBackfill()`
  - Progress tracking via `getBackfillProgress()`
- 9 integration tests verified passing against actual DB via Testcontainers

### Lifecycle Integration — ✅ DONE
- `SubscriptionManager.subscribe()` auto-triggers `BackfillService.startBackfill()` when `startPosition = FROM_BEGINNING`
- Enabled via `SubscriptionManager.setBackfillService(backfillService)` — optional dependency
- Backfill runs AFTER subscribe connection is released (avoids holding 2 connections)
- Backfill failure does NOT fail the subscribe — logged as WARNING, subscription still created
- REST endpoints available: POST start, GET progress, DELETE cancel backfill (Task H4 ✅)
- `ManagementApiHandler` reads `backfillStatus` for display; `SubscriptionHandler` provides full backfill management
- 3 integration tests: auto-trigger, FROM_NOW no-trigger, no-BackfillService backward compatibility

### Rate Limiting — ❌ MISSING
- Design specifies adaptive rate limiting to protect OLTP workloads during backfill
- Not implemented — batches run as fast as the database can process them

---

## Tracing & Observability — Gap Analysis

**Cross-referenced against**: Tracing Architecture Guide, Tracing User Guide, Monitoring Endpoints Implementation Plan

### Tracing Instrumentation — ❌ NOT STARTED

The Tracing Architecture Guide mandates that **all** async operations use `AsyncTraceUtils` wrappers and that message consumers extract `traceparent` from headers. A codebase search for `TraceCtx`, `TraceContext`, `AsyncTraceUtils`, `MDC`, `traceparent`, `spanId`, `traceId`, or `mdcScope` across all consumer group packages returns **zero matches**:

| Package | Files Checked | Tracing References |
|---------|---------------|--------------------|
| `db/cleanup/` | `CleanupService`, `DeadConsumerDetector`, `DeadConsumerDetectionJob` | **None** |
| `db/consumer/` | `ConsumerGroupFetcher`, `CompletionTracker` | **None** |
| `db/subscription/` | `SubscriptionManager`, `TopicConfigService`, `ZeroSubscriptionValidator`, `BackfillService` | **None** |

**Impact**:
- All consumer group logs have **blank** `traceId`/`spanId` fields
- Cannot correlate detection runs with the messages they affect
- Backfill operations cannot be traced end-to-end
- Fan-out delivery to multiple consumer groups creates no span hierarchy

**Specific violations of Tracing Architecture Guide**:
- `DeadConsumerDetectionJob` uses raw `vertx.setPeriodic()` — should use `AsyncTraceUtils` wrappers
- `ConsumerGroupFetcher` does not extract `traceparent` from fetched messages
- `CompletionTracker` does not carry trace context from the message being completed
- `BackfillService` batch operations create no traced spans

### Prometheus Metrics — ❌ NOT STARTED

**Inconsistency identified**: The Tracing User Guide (line ~377) shows `peegeeq_messages_received_total{consumer_group="cg1"}` as an available Prometheus metric. The Monitoring Endpoints doc (Bug #4) revealed `/metrics` was returning hardcoded zeros. Even after the fix, **no per-consumer-group metrics are implemented in code**.

Metrics that should exist but don't:

| Metric | Labels | Source Data |
|--------|--------|-------------|
| `peegeeq_messages_received_total` | `topic`, `consumer_group` | Promised in User Guide, not implemented |
| `peegeeq_dead_consumers_total` | `topic` | Available from `DeadConsumerDetector` |
| `peegeeq_blocked_messages_total` | `topic`, `group` | Available from `getBlockedMessageStats()` |
| `peegeeq_consumer_group_processing_seconds` | `topic`, `group`, `quantile` | Not even designed |
| `peegeeq_backfill_progress_ratio` | `topic`, `group` | Available from `getBackfillProgress()` |
| `peegeeq_detection_run_duration_seconds` | — | Available from `DetectionResult.detectionTimeMs` |

### Consumer Group Count in Monitoring — ✅ DONE (summary only)

The Monitoring Endpoints Implementation Plan's `/ws/monitoring` payload includes total consumer group count via `DatabaseSetupService`. This is **summary level only** — no per-group detail, no status breakdown, no dead consumer visibility.

### Fan-Out Trace Propagation — ❌ NOT DESIGNED

The Tracing Architecture Guide covers producer→consumer trace propagation but never addresses the fan-out case where one message is delivered to N consumer groups. Open questions:
- Should each consumer group get a **child span** of the original message span?
- Should all groups share the **parent span** with parallel branches?
- How should fan-out be visualised in Jaeger/Zipkin?

### Overlaps to Resolve

| Overlap | Details | Decision Needed |
|---------|---------|------------------|
| `DeadConsumerDetectionJob` lifetime stats vs Prometheus | Job exposes `getTotalRunCount()`, `getTotalDeadDetected()`, etc. as in-memory getters. These overlap with what Prometheus gauges would provide. | Expose as Prometheus metrics, WS/SSE, or both? |
| `getBlockedMessageStats()` vs admin endpoints | Detector already computes blocked counts. Tracker Task L3 (Admin Force-Remove) needs the same data. | Reuse `DeadConsumerDetector` methods in admin endpoint. |
| `/ws/monitoring` consumer group count vs subscription health | Monitoring endpoint has total count. `getSubscriptionSummary()` has active/paused/dead/cancelled breakdown. | Extend monitoring payload with breakdown. |

---

## Integration Gaps

These are cases where components exist but are not connected to the application lifecycle:

| Component | Code Exists | Wired Into App | Gap |
|-----------|-------------|----------------|-----|
| `DeadConsumerDetectionJob` | ✅ | ✅ | Wired into `PeeGeeQManager` with auto-start/stop |
| `BackfillService` | ✅ | ✅ | Auto-triggers on FROM_BEGINNING subscribe; REST endpoints for trigger/monitor/cancel |
| Dead consumer → message cleanup | ✅ | ✅ | `DeadConsumerGroupCleanup` chained from detection job |
| Resurrection (DEAD→ACTIVE) | ✅ | ✅ | `updateHeartbeat()` auto-resurrects DEAD→ACTIVE |
| Subscribe REST endpoint | ✅ | ✅ | `POST /subscriptions/:topic` with groupName, startPosition, heartbeat config |
| Backfill REST endpoints | ✅ | ✅ | POST start, GET progress, DELETE cancel backfill |
| Admin force-remove endpoint | ❌ | ❌ | Cannot force-remove dead consumers via API |

---

## Schema Gaps

| Column/Table | Needed For | Exists | Notes |
|--------------|-----------|--------|-------|
| `consecutive_failures` on `outbox_topic_subscriptions` | Flapping protection | ❌ | Design specifies 3-strike logic |
| `outbox_subscription_offsets` table | Offset/Watermark mode | ❌ | Phase 7, explicitly deferred |
| `outbox_topic_watermarks` table | Offset/Watermark mode | ❌ | Phase 7, explicitly deferred |

---

## Test Coverage Gaps

> **Updated**: March 2026 — Comprehensive test coverage pass completed. 13 new tests added,
> 4 test classes fixed for parallel execution safety. All 40 dead consumer tests now passing.

| Test Scenario | Exists | Why Not |
| `required_consumer_groups` decrement after DEAD | ✅ | Covered by `DeadConsumerGroupCleanupIntegrationTest` |
| Resurrection (DEAD→ACTIVE via heartbeat) | ✅ | 3 tests in `SubscriptionManagerIntegrationTest` |
| Flapping protection (consecutive failures) | ❌ | Feature not implemented |
| End-to-end: dead detection → decrement → cleanup | ✅ | `testEndToEndDetectCleanupPipeline` in JobIntegrationTest |
| PAUSED consumer with expired heartbeat detected | ✅ | `testPausedConsumerWithExpiredHeartbeatDetected` in ComprehensiveTest |
| CANCELLED consumer excluded from detection | ✅ | `testCancelledConsumerNotDetected` in ComprehensiveTest |
| Already-DEAD consumer not re-detected | ✅ | `testAlreadyDeadNotReDetected` in ComprehensiveTest |
| Mixed subscription states (only eligible detected) | ✅ | `testMixedStatesDetectsOnlyEligible` in ComprehensiveTest |
| Boundary: within timeout = not detected | ✅ | `testBoundaryWithinTimeoutNotDetected` in ComprehensiveTest |
| Concurrent detection overlap guard | ✅ | `testConcurrentDetectionGuardPreventsOverlap` in JobIntegrationTest |
| Cleanup error resilience (continues after failure) | ✅ | `testCleanupContinuesAfterOneGroupFails` in CleanupIntegrationTest |
| BackfillService integration tests | ✅ | 9 tests verified against actual DB |
| Backfill lifecycle (auto-trigger on subscribe) | ✅ | 3 tests in `SubscriptionManagerIntegrationTest` |
| DeadConsumerDetectionJob integration tests | ✅ | 8 tests verified against actual DB |
| DeadConsumerGroupCleanup integration tests | ✅ | 9 tests verified against actual DB |
| Service manager starts detection job on boot | ❌ | Integration not wired (C3 wires job, but no service manager integration test) |
| REST-triggered backfill | ✅ | 15 tests in `SubscriptionCreateAndBackfillIntegrationTest` |

---

## Implementation Tasks

Prioritised by severity. Each task has a clear acceptance criteria.

### CRITICAL — Must Fix (messages blocked indefinitely without these)

#### Task C1: Dead Consumer Message Cleanup
**What**: After marking subscriptions DEAD, decrement `required_consumer_groups` on affected messages  
**Where**: New method in `DeadConsumerDetector` or new `DeadConsumerGroupCleanup` class  
**SQL**:
```sql
-- Decrement required count for messages the dead group hasn't processed
UPDATE outbox SET required_consumer_groups = required_consumer_groups - 1
WHERE topic = $1 AND status IN ('PENDING', 'PROCESSING')
AND NOT EXISTS (
    SELECT 1 FROM outbox_consumer_groups cg
    WHERE cg.message_id = outbox.id AND cg.group_name = $2 AND cg.status = 'COMPLETED'
)
AND required_consumer_groups > 0;

-- Remove orphaned tracking rows for the dead group
DELETE FROM outbox_consumer_groups
WHERE group_name = $2 AND message_id IN (
    SELECT id FROM outbox WHERE topic = $1 AND status IN ('PENDING', 'PROCESSING')
)
AND status != 'COMPLETED';

-- Auto-complete messages where completed now >= required
UPDATE outbox SET status = 'COMPLETED'
WHERE topic = $1 AND status IN ('PENDING', 'PROCESSING')
AND completed_consumer_groups >= required_consumer_groups
AND required_consumer_groups > 0;
```
**Acceptance Criteria**:
- After a consumer is marked DEAD, `required_consumer_groups` is decremented on all non-completed messages for that topic
- Messages where `completed >= required` after decrement are auto-completed
- Orphaned `outbox_consumer_groups` rows are cleaned up
- Idempotent — running twice doesn't double-decrement
- Integration test proves messages can be cleaned up after consumer death

**Status**: ✅ Completed

**Implementation**: `DeadConsumerGroupCleanup.java` in `db.cleanup` package (~250 lines)
- `cleanupDeadGroup(topic, groupName)` — single-group cleanup within a transaction
- `cleanupAllDeadGroups()` — discovers all DEAD subscriptions and cleans each sequentially
- 3-step transactional cleanup: decrement → remove orphans → auto-complete
- Idempotent — NOT EXISTS guard prevents double-decrement
- `required_consumer_groups > 0` guard prevents going negative
- Structured `CleanupResult` record with `messagesDecremented`, `orphanRowsRemoved`, `messagesAutoCompleted`
- Integration test: `DeadConsumerGroupCleanupIntegrationTest.java` (8 tests)

#### Task C2: Wire Detection Job into DeadConsumerDetectionJob
**What**: `DeadConsumerDetectionJob.processDetectionResults()` must chain the cleanup from C1 after detection  
**Where**: `DeadConsumerDetectionJob.java`  
**Acceptance Criteria**:
- Detection → mark DEAD → cleanup messages is a single atomic flow
- Job logs the number of messages cleaned up per dead group
- Test proves full cycle: publish messages → consumer dies → job detects → messages become cleanable

**Status**: ✅ Completed

**Implementation**: `DeadConsumerDetectionJob.java` updated
- Constructor now requires `DeadConsumerGroupCleanup` (3rd parameter)
- After detection finds dead consumers, `cleanup.cleanupAllDeadGroups()` is chained
- New `logCleanupResults()` method logs per-group and aggregate cleanup stats
- Cumulative stats tracked: `totalMessagesDecremented`, `totalOrphanRowsRemoved`, `totalMessagesAutoCompleted`, `totalCleanupFailures`
- `stop()` now logs cleanup stats alongside detection stats

#### Task C3: Wire DeadConsumerDetectionJob Into Service Manager
**What**: The detection job must actually start when the application starts  
**Where**: Service manager or REST server startup  
**Acceptance Criteria**:
- `DeadConsumerDetectionJob.start()` is called during application bootstrap
- `DeadConsumerDetectionJob.stop()` is called during shutdown
- Configurable interval via application config (default 60s)

**Status**: ✅ Completed

**Implementation**: Wired into `PeeGeeQManager.startBackgroundTasksReactive()` with auto-start on boot and auto-stop on shutdown. Added configurable properties `peegeeq.queue.dead-consumer-detection.enabled` (default: true) and `peegeeq.queue.dead-consumer-detection.interval` (default: 60s) to `QueueConfig`. Config validation enforces interval ≥ 10s. All 3 CRITICAL tasks (C1, C2, C3) now complete.

### HIGH — Should Fix (functional gaps that affect correctness)

#### Task H1: Heartbeat Auto-Resurrection
**What**: When a DEAD consumer sends a heartbeat, auto-transition from DEAD → ACTIVE  
**Where**: `SubscriptionManager.updateHeartbeat()`  
**Acceptance Criteria**:
- `updateHeartbeat()` sets `subscription_status = 'ACTIVE'` if current status is DEAD
- Test: mark subscription DEAD → send heartbeat → status is ACTIVE
- Consider: should resurrection trigger re-backfill of messages missed during DEAD period?

**Status**: ✅ Completed

**Implementation**: `SubscriptionManager.updateHeartbeat()` updated with conditional SQL: `CASE WHEN subscription_status = 'DEAD' THEN 'ACTIVE' ELSE subscription_status END`. Uses CTE to capture pre-update status for INFO-level resurrection logging. Only DEAD subscriptions are resurrected — PAUSED and CANCELLED are unaffected. Re-backfill of messages missed during DEAD period is NOT implemented (future enhancement).
- 3 tests added to `SubscriptionManagerIntegrationTest`: `testHeartbeatResurrectsDeadSubscription`, `testHeartbeatDoesNotResurrectCancelledSubscription`, `testHeartbeatKeepsPausedSubscriptionPaused`

#### Task H2: Backfill Lifecycle Integration
**What**: Auto-trigger backfill when a subscription with `FROM_BEGINNING` is created  
**Where**: `SubscriptionManager.subscribe()` → `BackfillService.startBackfill()`  
**Acceptance Criteria**:
- `subscribe()` with `FROM_BEGINNING` automatically starts backfill
- `subscribe()` with other start positions does not trigger backfill
- Test: subscribe FROM_BEGINNING to topic with existing messages → backfill runs → messages available

**Status**: ✅ Completed

**Implementation**: `SubscriptionManager.subscribe()` now auto-triggers `BackfillService.startBackfill()` when `startPosition = FROM_BEGINNING` and a `BackfillService` is configured via `setBackfillService()`. Backfill runs after the subscribe connection is released to avoid holding 2 connections simultaneously. Backfill failure is caught and logged as WARNING — the subscription is still created successfully. Tests verify: (1) FROM_BEGINNING auto-triggers and completes backfill, (2) FROM_NOW does not trigger backfill, (3) subscribe works without BackfillService configured.
- 3 tests added to `SubscriptionManagerIntegrationTest`: `testSubscribeFromBeginningAutoTriggersBackfill`, `testSubscribeFromNowDoesNotTriggerBackfill`, `testSubscribeFromBeginningWithoutBackfillServiceStillWorks`

#### Task H3: Subscribe REST Endpoint
**What**: Add POST endpoint for creating subscriptions via REST  
**Where**: `SubscriptionHandler` + route registration  
**Acceptance Criteria**:
- `POST /api/v1/setups/:setupId/subscriptions/:topic` creates a new subscription
- Request body includes `groupName`, `startPosition`, `heartbeatTimeoutSeconds`
- Returns 201 on success, 409 on conflict (already exists)

**Status**: ✅ Completed

**Implementation**: Added `createSubscription()` handler to `SubscriptionHandler` with full request validation (groupName required, startPosition enum validation, ISO-8601 timestamp parsing). Added `buildSubscriptionOptions()` helper to parse JSON body into `SubscriptionOptions`. Route registered as `POST /api/v1/setups/:setupId/subscriptions/:topic`. Returns 201 with subscription details including state, heartbeat config, and backfill status. Added `startBackfill()`/`cancelBackfill()` default methods to `SubscriptionService` interface for backward-compatible extension.
- 6 tests in `SubscriptionCreateAndBackfillIntegrationTest`: create success (201), verify in list, missing groupName (400), invalid startPosition (400), non-existent setup (404), FROM_BEGINNING start position

#### Task H4: Backfill REST Endpoints
**What**: REST endpoints to trigger and monitor backfill  
**Where**: New handler or extend `SubscriptionHandler`  
**Acceptance Criteria**:
- `POST .../subscriptions/:topic/:groupName/backfill` — starts backfill
- `GET .../subscriptions/:topic/:groupName/backfill` — returns progress
- `DELETE .../subscriptions/:topic/:groupName/backfill` — cancels backfill

**Status**: ✅ Completed

**Implementation**: Extended `SubscriptionHandler` with 3 backfill endpoints. `startBackfill()` delegates to `SubscriptionService.startBackfill()` (implemented in `SubscriptionManager` via `BackfillService`), returns result JSON with status/processedMessages/message. Handles UnsupportedOperationException (501), IllegalStateException (409). `getBackfillProgress()` reads backfill fields from `SubscriptionInfo`, calculates percentComplete. `cancelBackfill()` delegates to `SubscriptionService.cancelBackfill()`. Added 4 error codes: BACKFILL_START_FAILED, BACKFILL_CANCEL_FAILED, BACKFILL_NOT_FOUND, BACKFILL_INVALID_STATE.
- 5 tests in `SubscriptionCreateAndBackfillIntegrationTest`: get progress (200), non-existent subscription (404), non-existent setup (404) for GET/DELETE/POST backfill

### MEDIUM — Should Address (robustness improvements)

#### Task M1: Flapping Protection
**What**: Require N consecutive heartbeat misses before marking DEAD  
**Where**: Schema migration + `DeadConsumerDetector`  
**Schema Change**: Add `consecutive_failures INT DEFAULT 0` to `outbox_topic_subscriptions`  
**Logic Change**: On detection run:
- If heartbeat expired: increment `consecutive_failures`
- If `consecutive_failures >= threshold` (default 3): mark DEAD
- If heartbeat is current: reset `consecutive_failures = 0`  
**Acceptance Criteria**:
- Single timeout miss increments counter but does not mark DEAD
- 3 consecutive misses marks DEAD
- Heartbeat resets counter to 0
- Threshold is configurable per subscription

**Status**: ❌ Not started

#### Task M2: Orphaned Consumer Group Row Cleanup
**What**: Clean up `outbox_consumer_groups` rows for dead/cancelled subscriptions  
**Where**: Dead consumer cleanup flow or separate cleanup job  
**Acceptance Criteria**:
- When subscription is DEAD or CANCELLED, PENDING rows in `outbox_consumer_groups` for that group are removed
- COMPLETED rows may be retained for audit

**Status**: ✅ Completed (for DEAD subscriptions)

**Implementation**: Handled by `DeadConsumerGroupCleanup` step 2 — orphaned `outbox_consumer_groups` rows with `status != 'COMPLETED'` are removed for each dead group during the detection→cleanup pipeline. CANCELLED subscription cleanup is not yet addressed.

### LOW — Nice to Have

#### Task L1: Adaptive Rate Limiting for Backfill
**What**: Throttle backfill to protect OLTP workloads  
**Where**: `BackfillService`  
**Acceptance Criteria**:
- Configurable pause between batches
- Backfill throughput adapts based on DB load signals

**Status**: ❌ Not started

#### Task L2: Graceful Shutdown Handling
**What**: On consumer shutdown, mark subscription CANCELLED and drain in-flight messages  
**Where**: Consumer lifecycle hooks  
**Status**: ❌ Not started

#### Task L3: Admin Force-Remove Endpoint
**What**: REST endpoint to force-remove a dead consumer group and clean up its messages  
**Where**: Admin/management API  
**Status**: ❌ Not started

### MEDIUM — Tracing & Observability

#### Task M3: Add Tracing to DeadConsumerDetectionJob
**What**: Use `AsyncTraceUtils` wrappers instead of raw `vertx.setPeriodic()` for detection runs  
**Where**: `DeadConsumerDetectionJob.java`  
**Rationale**: Currently violates Tracing Architecture Guide — detection runs have no trace context, so logs cannot be correlated with affected messages  
**Acceptance Criteria**:
- Each detection run creates a traced span
- Logs from detection include `traceId`/`spanId`
- Detection timing is observable via trace spans

**Status**: ❌ Not started

#### Task M4: Add Tracing to ConsumerGroupFetcher
**What**: Extract `traceparent` from fetched message headers and propagate trace context  
**Where**: `ConsumerGroupFetcher.java`  
**Rationale**: Tracing Architecture Guide mandates all message consumers extract trace context from headers  
**Acceptance Criteria**:
- Fetched messages carry trace context to downstream processing
- Trace spans show message delivery to specific consumer groups

**Status**: ❌ Not started

#### Task M5: Add Tracing to CompletionTracker
**What**: Carry trace context from the message being completed through the completion flow  
**Where**: `CompletionTracker.java`  
**Acceptance Criteria**:
- Completion operations are visible in distributed traces
- Can trace a message from publish → fan-out → per-group completion

**Status**: ❌ Not started

#### Task M6: Add Tracing to BackfillService
**What**: Create traced spans per batch for backfill observability  
**Where**: `BackfillService.java`  
**Acceptance Criteria**:
- Each backfill batch creates a child span
- Backfill progress is observable via distributed tracing

**Status**: ❌ Not started

### LOW — Monitoring Endpoints

#### Task L4: Expose Dead Consumer Stats via Prometheus
**What**: Publish `peegeeq_dead_consumers_total{topic}` and `peegeeq_blocked_messages_total{topic,group}` as Prometheus metrics  
**Where**: Metrics integration layer  
**Rationale**: `DeadConsumerDetector` already computes this data but it's only available in logs. The Tracing User Guide promises per-consumer-group metrics that don't exist.  
**Status**: ❌ Not started

#### Task L5: Add Subscription Health to /ws/monitoring
**What**: Include active/paused/dead/cancelled breakdown in the `/ws/monitoring` payload  
**Where**: Monitoring endpoint data source  
**Rationale**: `getSubscriptionSummary()` already returns this data. Monitoring endpoint currently only shows total consumer group count.  
**Status**: ❌ Not started

#### Task L6: Design Fan-Out Trace Propagation
**What**: Define how traces branch when one message is delivered to N consumer groups  
**Where**: Design document / Tracing Architecture Guide  
**Open Questions**: Should each group get a child span? Parallel branches? How to visualise in Jaeger?  
**Status**: ❌ Not started

#### Task L7: Add Backfill Progress to /ws/monitoring
**What**: Include in-progress backfill status in the monitoring payload  
**Where**: Monitoring endpoint data source  
**Rationale**: `BackfillService.getBackfillProgress()` already returns status/checkpoint/percentage but isn't exposed.  
**Status**: ❌ Not started

---

## File Inventory

### Production Code — Verified Existing

| File | Package | Lines | Status |
|------|---------|-------|--------|
| `TopicConfigService.java` | `db.subscription` | 264 | ✅ Complete |
| `SubscriptionManager.java` | `db.subscription` | 503 | ✅ Complete (resurrection via heartbeat implemented) |
| `ZeroSubscriptionValidator.java` | `db.subscription` | 140 | ✅ Complete |
| `ConsumerGroupFetcher.java` | `db.consumer` | 127 | ✅ Complete |
| `CompletionTracker.java` | `db.consumer` | 161 | ✅ Complete |
| `CleanupService.java` | `db.cleanup` | 199 | ✅ Complete |
| `DeadConsumerDetector.java` | `db.cleanup` | ~400 | ✅ Detection + diagnostics (structured results, blocked stats, subscription summary) |
| `DeadConsumerGroupCleanup.java` | `db.cleanup` | ~250 | ✅ Complete — decrement, orphan removal, auto-complete |
| `DeadConsumerDetectionJob.java` | `db.cleanup` | ~460 | ✅ Full operational logging + cleanup wired, started by `PeeGeeQManager` |
| `BackfillService.java` | `db.subscription` | 545 | ✅ Complete — wired into lifecycle, REST endpoints available |
| `SubscriptionHandler.java` | REST handler | ~650 | ✅ Complete — subscribe, backfill endpoints implemented |
| `ManagementApiHandler.java` | REST handler | — | ✅ Read-only backfill status |

### Test Code — Verified Existing

| Test File | Tests | Run Against DB | Status |
|-----------|-------|----------------|--------|
| `TopicConfigServiceIntegrationTest` | 7 | ✅ Yes | ✅ Passing |
| `SubscriptionManagerIntegrationTest` | 12 | ✅ Yes | ✅ Passing — includes 3 resurrection tests + 3 backfill lifecycle tests |
| `ZeroSubscriptionValidatorIntegrationTest` | 7 | ✅ Yes | ✅ Passing |
| `FanoutProducerIntegrationTest` | 6 | ✅ Yes | ✅ Passing |
| `ConsumerGroupFetcherIntegrationTest` | 4 | ✅ Yes | ✅ Passing |
| `CompletionTrackerIntegrationTest` | 4 | ✅ Yes | ✅ Passing |
| `CleanupServiceIntegrationTest` | 6 | ✅ Yes | ✅ Passing |
| `DeadConsumerDetectorIntegrationTest` | 4 | ✅ Yes | ✅ Passing — `@Tag(FLAKY)` removed, parallel-safe |
| `DeadConsumerDetectorComprehensiveTest` | 10 | ✅ Yes | ✅ Passing — PAUSED/CANCELLED/boundary/mixed/API coverage |
| `BackfillServiceIntegrationTest` | 9 | ✅ Yes | ✅ Passing |
| `DeadConsumerDetectionJobIntegrationTest` | 8 | ✅ Yes | ✅ Passing — pipeline + concurrent guard tests |
| `DeadConsumerGroupCleanupIntegrationTest` | 9 | ✅ Yes | ✅ Passing — error resilience test |
| Performance tests (P1-P4) | 4 | ✅ Yes | ✅ Passing |

### Missing Test Coverage

| Scenario | Needed For Task |
|----------|-----------------|
| Dead consumer → decrement → messages cleanable | C1, C2 — ✅ covered by `DeadConsumerGroupCleanupIntegrationTest` |
| Full cycle: publish → die → detect → clean → verify | C1, C2 — ✅ covered by `testEndToEndDetectCleanupPipeline` |
| Service manager starts/stops detection job | C3 |
| Resurrection via heartbeat | H1 — ✅ completed |
| Subscribe FROM_BEGINNING triggers backfill | H2 |
| Flapping protection (1 miss = no DEAD, 3 = DEAD) | M1 |
| Detection job runs with trace context | M3 |
| Consumer group fetch with trace propagation | M4 |
| Completion tracking with trace context | M5 |
| Backfill operations with traced spans | M6 |

---

## Previous Document Corrections

The existing [Implementation Plan](CONSUMER_GROUP_FANOUT_IMPLEMENTATION_PLAN.md) contains these inaccuracies:

| Claim | Reality |
|-------|---------|
| "Phase 5: ✅ COMPLETE — Dead Consumer Detection" | Phase 5 is now COMPLETE — detection, cleanup, and scheduling all implemented |
| "Dead subscriptions automatically resurrect when heartbeat resumes" (line ~238) | NOW TRUE — `updateHeartbeat()` conditionally sets `subscription_status = 'ACTIVE'` when current status is `DEAD` |
| "Phase 8: ⏸️ NOT STARTED — Resumable Backfill" | OUTDATED — `BackfillService` now exists with full batch processing AND is wired into subscription lifecycle via `setBackfillService()` |
| "DeadConsumerDetector… ⚠️ No scheduled job" (Component Status table) | OUTDATED — `DeadConsumerDetectionJob` exists and is wired into `PeeGeeQManager` |
| "Backfill Support… ❌ Missing" (Component Status table) | OUTDATED — `BackfillService` now exists |
| "Manual Workaround: call SQL function periodically" | OUTDATED — detection now runs automatically via `PeeGeeQManager` on configurable schedule |

---

## Change Log

| Date | Change | Author |
|------|--------|--------|
| 2026-03-01 | Created — full code audit against design spec | — |
| 2026-03-01 | Added operational logging: `DeadConsumerDetector` now returns structured `DetectionResult`, `BlockedMessageStats`, `SubscriptionSummary`. `DeadConsumerDetectionJob` rewritten with per-run summaries, blocked message impact, critical alerts (>1000 msgs or >24h blocked), subscription landscape, failure tracking, overlap guard, lifetime stats, human-readable durations. | — |
| 2026-03-01 | Tracing/observability cross-reference: Expanded "Metrics/Monitoring" into 7 granular rows. Added new section "Tracing & Observability — Gap Analysis" documenting zero tracing instrumentation across all consumer group code, inconsistency where User Guide promises Prometheus metrics that don't exist, and summary-only monitoring endpoint coverage. Added tasks M3-M6 (tracing instrumentation) and L4-L7 (monitoring endpoints). Added tracing doc references to header. | — |
| 2026-03-01 | **C1+C2 Completed**: Created `DeadConsumerGroupCleanup.java` (~250 lines) with 3-step transactional cleanup (decrement → orphan removal → auto-complete). Wired into `DeadConsumerDetectionJob` — detection now chains cleanup automatically with per-group logging, cumulative stats, and cleanup failure tracking. Created `DeadConsumerGroupCleanupIntegrationTest.java` (8 tests). Updated job constructor to require cleanup dependency. | — |
| 2026-03-01 | **C3 Completed**: Wired `DeadConsumerDetectionJob` into `PeeGeeQManager.startBackgroundTasksReactive()` with auto-start on boot and auto-stop on shutdown. Added configurable properties `peegeeq.queue.dead-consumer-detection.enabled` (default: true) and `peegeeq.queue.dead-consumer-detection.interval` (default: 60s) to `QueueConfig`. Added config validation (interval ≥ 10s). All 3 CRITICAL tasks (C1, C2, C3) now complete. | — |
| 2026-03-01 | **Comprehensive test coverage pass**: Created `DeadConsumerDetectorComprehensiveTest.java` (10 tests, 695 lines) covering PAUSED detection, CANCELLED exclusion, already-DEAD re-detection, mixed subscription states, `DetectionResult`/`BlockedMessageStats`/`SubscriptionSummary` API validation, `countEligibleForDeadDetection`, and boundary conditions. Added `testEndToEndDetectCleanupPipeline` (detect→cleanup→auto-complete full pipeline) and `testConcurrentDetectionGuardPreventsOverlap` (pure integration, validates overlap guard skips concurrent invocations) to `DeadConsumerDetectionJobIntegrationTest`. Added `testCleanupContinuesAfterOneGroupFails` (subclass override injection, validates `.recover()` error isolation) to `DeadConsumerGroupCleanupIntegrationTest`. | — |
| 2026-03-01 | **Test infrastructure fixes**: Fixed 4 test classes for JUnit 5 parallel execution safety — added `@Execution(ExecutionMode.SAME_THREAD)` to prevent intra-class parallel interference. Removed `@Tag(FLAKY)` from `DeadConsumerDetectorIntegrationTest` after fixing root cause (hardcoded topic names + exact count assertions in parallel environment). All tests now use UUID-based unique topic names and verify final subscription status instead of exact detection counts. Fixed 3 pre-existing `SubscriptionOptions` builder validation bugs (`heartbeatTimeoutSeconds` must be strictly > `heartbeatIntervalSeconds`). All 40 dead consumer tests across 5 classes now passing with 0 failures. Test classes verified as actually running against real PostgreSQL via Testcontainers. | — |
| 2026-03-01 | **H1 Completed**: Heartbeat auto-resurrection. `SubscriptionManager.updateHeartbeat()` now conditionally transitions DEAD→ACTIVE using `CASE WHEN subscription_status = 'DEAD' THEN 'ACTIVE' ELSE subscription_status END`. CTE captures pre-update status for INFO-level resurrection logging. CANCELLED and PAUSED subscriptions are unaffected. 3 new tests added to `SubscriptionManagerIntegrationTest`. Full regression: 49 tests, 0 failures across 6 classes. | — |
| 2026-03-01 | **H2 Completed**: Backfill lifecycle integration. `SubscriptionManager.subscribe()` now auto-triggers `BackfillService.startBackfill()` for `FROM_BEGINNING` subscriptions when BackfillService is configured via `setBackfillService()`. Backfill runs after subscribe connection is released. Failure is resilient — logged as WARNING, subscription still created. 3 new tests added to `SubscriptionManagerIntegrationTest`. Full regression: 52 tests, 0 failures across 6 classes. | — |
| 2026-03-01 | **H3+H4 Completed**: Subscribe REST endpoint + Backfill REST endpoints. Added `createSubscription()` handler (POST, returns 201) with JSON body validation (groupName required, startPosition enum, heartbeat config, timestamp parsing). Added 3 backfill handlers: `startBackfill()` (POST), `getBackfillProgress()` (GET with percentComplete calculation), `cancelBackfill()` (DELETE). Extended `SubscriptionService` interface with `startBackfill()`/`cancelBackfill()` default methods. `SubscriptionManager` overrides delegate to `BackfillService`. 4 new error codes. 4 new routes in `PeeGeeQRestServer`. Created `SubscriptionCreateAndBackfillIntegrationTest` (12 tests). REST regression: 19 tests (12 new + 7 existing), 0 failures. DB regression: 52 tests, 0 failures. | — |
| 2026-03-01 | **H3+H4 Code Review Fixes**: (1) Added `Objects.requireNonNull(topic/groupName)` to `SubscriptionManager.startBackfill()`/`cancelBackfill()` for consistency with all other methods (18 existing usages). (2) Fixed fully-qualified `io.vertx.core.json.JsonObject` in `SubscriptionService` interface — now uses import. (3) Rewrote `createSubscription()` handler to return 409 on duplicate subscription (was returning 201 via silent upsert) — pre-checks with `getSubscription()` and returns `SUBSCRIPTION_ALREADY_EXISTS` error. Added `testCreateDuplicateSubscription` test. (4) Added missing `import io.vertx.core.Future` and `import SubscriptionState` to `SubscriptionHandler`. Noted: `BACKFILL_NOT_FOUND` error code (PGQERR0060) is dead code — reserved for future admin endpoints. Full regression: 71 tests (19 REST + 52 DB), 0 failures. | — |
| 2026-03-01 | **Code Review Follow-up — Dead Code + Happy-Path Coverage**: (1) Removed dead `BACKFILL_NOT_FOUND` error code (PGQERR0060) — was declared but never referenced; all not-found cases use `SUBSCRIPTION_NOT_FOUND` via `sendSubscriptionNotFoundError()`. (2) Wired `BackfillService` into production code: `PeeGeeQManager.createSubscriptionService()` now creates a `BackfillService` alongside `SubscriptionManager`, using `DEFAULT_POOL_ID` instead of null. This enables backfill REST endpoints to actually work end-to-end (previously returned 501 `UnsupportedOperationException`). (3) Added 3 happy-path REST tests: `testStartBackfillHappyPath` (200, verifies COMPLETED status with 0 messages), `testStartBackfillAlreadyCompleted` (200, verifies ALREADY_COMPLETED on re-call), `testCancelBackfillHappyPath` (200, verifies success/topic/groupName/action). Full regression: 74 tests (22 REST + 52 DB), 0 failures. | — |
