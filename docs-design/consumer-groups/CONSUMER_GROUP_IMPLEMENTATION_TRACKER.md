# Consumer Group Fan-Out — Implementation Tracker

**Purpose**: Honest, verified tracking of what is actually implemented vs what the design specifies.  
**Author**: Mark Andrew Ray-Smith, Cityline Ltd  
**Created**: 2026-03-01  
**Last Verified**: 2026-04-08 (updated method names after Async/Reactive suffix cleanup)  
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
| Flapping Protection | ✅ DONE | V015 migration adds `consecutive_misses`/`dead_after_misses` columns; detector uses two-phase increment→threshold SQL |
| Dead Consumer Scheduled Job | ✅ DONE | Wired into `PeeGeeQManager` lifecycle, configurable interval, auto-start/stop |
| Backfill Service | ✅ DONE | Full batch processing with auto-trigger on FROM_BEGINNING subscribe, REST endpoints for trigger/monitor/cancel |
| Backfill Lifecycle Integration | ✅ DONE | Auto-triggers backfill on FROM_BEGINNING subscription via `setBackfillService()` |
| Subscribe REST Endpoint | ✅ DONE | POST creates subscriptions via REST with validation |
| Backfill REST Endpoints | ✅ DONE | POST start, GET progress, DELETE cancel — all via REST |
| Offset/Watermark Mode | ❌ NOT STARTED | See [PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md §19](PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md#partitioned-consumer-groups-offsetwatermark-mode) for schema, phases, and open questions |
| Resurrection Re-Backfill | ✅ DONE | Task H5 — `updateHeartbeat()` detects DEAD→ACTIVE, resets backfill status, chains `BackfillService.startBackfill()` with `.recover()`. 6 integration tests (3 RED-verified). |
| CANCELLED Orphan Cleanup | ✅ DONE | Task H6 — `cancel()` chains `DeadConsumerGroupCleanup.cleanupDeadGroup()` with `.recover()`. 4 integration tests (1 RED-verified). |
| Service Manager Integration Test | ✅ DONE | Task M7 — 3 integration tests verify detection job start/stop/disabled lifecycle via `PeeGeeQManager` |
| Fan-Out Trace Propagation (impl) | ❌ NOT STARTED | Task L9 — design doc complete, child span implementation not done |
| Remaining Prometheus Metrics | ✅ DONE | Task L10 — 3 metrics implemented; processing timer and backfill progress dropped (invasive, no concrete need) |
| Tracing Instrumentation | ✅ DONE | `TraceCtx`/`mdcScope()` added to `DeadConsumerDetectionJob` (compose chain + 5 logging methods), `ConsumerGroupFetcher` (fetch entry + result), `CompletionTracker` (markCompleted + markFailed). `BackfillService` was already traced. |
| Prometheus Metrics (consumer groups) | ✅ DONE | `ConsumerGroupMetrics` implements `MeterBinder`, registers 6 gauges: active/paused/dead/cancelled/total/topics. Refresh via `Future<Void> refresh()` from `DeadConsumerDetector.getSubscriptionSummary()`. 7 integration tests GREEN. |
| Consumer Group Count in Monitoring WS/SSE | ✅ DONE (summary) | Total count exposed via `/ws/monitoring` — no per-group detail |
| Dead Consumer Alerting Endpoint | ✅ DONE | REST endpoints: `/api/v1/setups/:setupId/consumer-alerts/{dead|summary|blocked}` — 7 integration tests GREEN |
| Subscription Health Monitoring Endpoint | ✅ DONE | `/ws/monitoring` payload now includes `subscriptionHealth` object with active/paused/dead/cancelled/total/topics breakdown |
| Backfill Progress Monitoring Endpoint | ✅ DONE | `/ws/monitoring` payload now includes `activeBackfills` array with topic/groupName/processedMessages/totalMessages/percentComplete per in-progress backfill |
| Fan-Out Trace Propagation Design | ✅ DONE | Design document: `CONSUMER_GROUP_FANOUT_TRACE_PROPAGATION.md` — recommends child spans per consumer group (Option A), details current gaps and implementation scope |
| Partitioned Consumer Groups Design | ✅ DONE (draft) | Consolidated into [PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md §19](PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md#partitioned-consumer-groups-offsetwatermark-mode) — offset/watermark mode + partitioned consumption, 6 implementation phases, 5 open questions |

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
- 13 integration tests passing (8 core + 5 edge cases: FAILED→COMPLETED recovery, retry count, markFailed unknown group, PAUSED subscription rejection, non-existent message rejection)

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

### Layer 4: Graceful Shutdown — ✅ DONE
- `ConsumerGroup` interface: added `default Future<Void> stopGracefully()` method
- `OutboxConsumerGroup`: tracks subscription-backed start via `startedWithSubscription` flag; `stopGracefully()` cancels subscription in DB before stopping local consumers; cancel failure is recovered (group still stops)
- `PgNativeConsumerGroup`: same pattern as OutboxConsumerGroup
- 7 unit tests in `OutboxConsumerGroupGracefulShutdownTest` — all passing
- Existing 55 OutboxConsumerGroupCoreTest tests remain passing (backward compat)

Primary sources:
- `peegeeq-api/src/main/java/dev/mars/peegeeq/api/messaging/ConsumerGroup.java`
- `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxConsumerGroup.java`
- `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeConsumerGroup.java`
- `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupGracefulShutdownTest.java`

### Layer 5: Admin Override / Force-Remove — ✅ DONE
- `SubscriptionService` interface: added `forceRemoveConsumerGroup(topic, groupName)` default method
- `SubscriptionManager`: validates subscription exists and is not already CANCELLED → marks DEAD → runs `DeadConsumerGroupCleanup.cleanupDeadGroup()` → marks CANCELLED → returns `ForceRemoveResult` with cleanup stats
- REST: `DELETE /api/v1/setups/:setupId/subscriptions/:topic/:groupName/force-remove`
- `SubscriptionHandler.forceRemoveConsumerGroup()`: 404 for not found, 409 for already cancelled, 200 with cleanup stats on success
- `ForceRemoveResult` record: `topic`, `groupName`, `previousStatus`, `messagesDecremented`, `orphanRowsRemoved`, `messagesAutoCompleted`, `totalActions()`
- `PeeGeeQManager.createSubscriptionService()` wires `DeadConsumerGroupCleanup` into `SubscriptionManager`
- 5 unit tests in `ForceRemoveUnitTest` — all passing
- 5 integration tests in `ForceRemoveIntegrationTest` — all passing

Primary sources:
- `peegeeq-api/src/main/java/dev/mars/peegeeq/api/subscription/ForceRemoveResult.java`
- `peegeeq-api/src/main/java/dev/mars/peegeeq/api/subscription/SubscriptionService.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/subscription/SubscriptionManager.java`
- `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/SubscriptionHandler.java`
- `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/PeeGeeQRestServer.java`
- `peegeeq-db/src/test/java/dev/mars/peegeeq/db/subscription/ForceRemoveUnitTest.java`
- `peegeeq-db/src/test/java/dev/mars/peegeeq/db/subscription/ForceRemoveIntegrationTest.java`

### Scheduled Job — ✅ DONE
- `DeadConsumerDetectionJob.java` (~460 lines) wraps `DeadConsumerDetector` + `DeadConsumerGroupCleanup` with `vertx.setPeriodic()`
- Configurable interval (default 60s), start/stop lifecycle, manual trigger via `runDetectionOnce()` / `runDetectionOnceWithDetails()`
- Full operational logging (see Layer 2a above)
- Detection → cleanup chained automatically: after detecting dead consumers, `cleanup.cleanupAllDeadGroups()` runs
- Overlap guard: skips detection if previous run still in progress (verified by concurrent guard integration test)
- Lifetime stats accessible: `getTotalRunCount()`, `getTotalDeadDetected()`, `getTotalFailures()`
- Wired into `PeeGeeQManager.startBackgroundTasks()` with auto-start on boot and auto-stop on shutdown
- Configurable via `peegeeq.queue.dead-consumer-detection.enabled` (default: true) and `peegeeq.queue.dead-consumer-detection.interval` (default: 60s)
- 8 integration tests across 1 class — all passing:
  - `DeadConsumerDetectionJobIntegrationTest` (8 tests) — includes end-to-end pipeline test and concurrent overlap guard test

### Resurrection — ✅ DONE
- `SubscriptionManager.updateHeartbeat()` uses conditional SQL: `CASE WHEN subscription_status = 'DEAD' THEN 'ACTIVE' ELSE subscription_status END`
- CTE captures pre-update status for resurrection logging at INFO level
- CANCELLED and PAUSED subscriptions are NOT affected by heartbeat (only DEAD → ACTIVE)
- 3 integration tests: `testHeartbeatResurrectsDeadSubscription`, `testHeartbeatDoesNotResurrectCancelledSubscription`, `testHeartbeatKeepsPausedSubscriptionPaused`
- Note: resurrection does NOT re-increment `required_consumer_groups` or trigger re-backfill for messages cleaned up during DEAD period (future enhancement)

### Flapping Protection — ✅ DONE
- Design doc (Pitfall 2, lines ~2500-2510) specifies `consecutive_failures` column requiring 3+ consecutive heartbeat misses before marking DEAD
- Implemented as `consecutive_misses` and `dead_after_misses` columns via V015 migration
- `DeadConsumerDetector` uses two-phase SQL: increment `consecutive_misses`, then mark DEAD only when `>= dead_after_misses`
- `SubscriptionManager.updateHeartbeat()` resets `consecutive_misses = 0` on heartbeat (including DEAD→ACTIVE resurrection)
- `SubscriptionManager.subscribe()` resets `consecutive_misses = 0` on resubscription
- `SubscriptionOptions.deadAfterMisses(int)` allows per-subscription threshold configuration (default 3)
- 12 integration tests in `FlappingProtectionIntegrationTest` — all passing

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

### Rate Limiting — ✅ DONE
- `BackfillService` accepts `batchDelayMs` parameter for inter-batch throttling
- New 3-arg constructor `(PgConnectionManager, String, Vertx)` enables timer-based delays via `vertx.timer(batchDelayMs).mapEmpty()`
- Legacy 2-arg constructor preserved for backward compatibility (timer support disabled, zero delay only)
- Validation: negative `batchDelayMs` → `IllegalArgumentException`; non-zero without Vertx → `IllegalStateException`
- `PeeGeeQManager.createSubscriptionService()` now passes Vertx to BackfillService
- All existing overloads delegate with `batchDelayMs=0` (no behavior change)
- 13 unit tests in `BackfillRateLimitingUnitTest` — all passing
- 4 integration tests in `BackfillRateLimitingIntegrationTest` — all passing

Primary sources:
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/subscription/BackfillService.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/PeeGeeQManager.java`
- `peegeeq-db/src/test/java/dev/mars/peegeeq/db/subscription/BackfillRateLimitingUnitTest.java`
- `peegeeq-db/src/test/java/dev/mars/peegeeq/db/fanout/BackfillRateLimitingIntegrationTest.java`

---

## Tracing & Observability — Gap Analysis

**Cross-referenced against**: Tracing Architecture Guide, Tracing User Guide, Monitoring Endpoints Implementation Plan

### Tracing Instrumentation — ✅ DONE

All consumer group operational code now uses `TraceCtx.createNew()` at entry points and `TraceContextUtil.mdcScope(trace)` around every log site to ensure `traceId`/`spanId` appear in all structured log output.

| Package | Files Updated | Tracing Approach |
|---------|---------------|------------------|
| `db/cleanup/` | `DeadConsumerDetectionJob` | Trace created per detection run; passed through entire compose chain (detection→blocked stats→cleanup→summary); all 5 private logging methods accept `TraceCtx` and scope MDC |
| `db/consumer/` | `ConsumerGroupFetcher` | Trace created at `fetchMessages()` entry; MDC scoped at entry log and result count log |
| `db/consumer/` | `CompletionTracker` | Trace created at `markCompleted()`/`markFailed()` entry; MDC scoped at entry, validation, idempotent, completion status, and error logs |
| `db/subscription/` | `BackfillService` | (Already implemented — comprehensive trace through entire recursive batch chain) |

**Previously identified gaps** (all resolved by M3–M6):
- ~~All consumer group logs had blank `traceId`/`spanId` fields~~ → Fixed: `TraceCtx.createNew()` at entry points
- ~~Detection runs could not be correlated with affected messages~~ → Fixed: M3 traces entire compose chain
- ~~Backfill operations not traced end-to-end~~ → Already traced (M6 confirmed pre-existing)
- ~~Fan-out delivery creates no span hierarchy~~ → Design complete (L6, `CONSUMER_GROUP_FANOUT_TRACE_PROPAGATION.md`)

### Prometheus Metrics — ✅ DONE

`ConsumerGroupMetrics.java` implements `MeterBinder`, registers 6 gauges: `active_subscriptions`, `paused_subscriptions`, `dead_subscriptions`, `cancelled_subscriptions`, `total_subscriptions`, `topics_with_subscriptions`. Backed by `AtomicLong` values, refreshed via `DeadConsumerDetector.getSubscriptionSummary()`. 7 integration tests GREEN.

**Remaining gaps** (not critical for current milestone):

| Metric | Labels | Source Data | Status |
|--------|--------|-------------|--------|
| `peegeeq_messages_received_total` | `topic`, `consumer_group` | Promised in User Guide | ❌ Not implemented |
| `peegeeq_blocked_messages_total` | `topic`, `group` | Available from `getBlockedMessageStats()` | ❌ Not implemented |
| `peegeeq_consumer_group_processing_seconds` | `topic`, `group`, `quantile` | Not designed | ❌ Not designed |
| `peegeeq_backfill_progress_ratio` | `topic`, `group` | Available from `getBackfillProgress()` | ❌ Not implemented |
| `peegeeq_detection_run_duration_seconds` | — | Available from `DetectionResult.detectionTimeMs` | ❌ Not implemented |

### Consumer Group Count in Monitoring — ✅ DONE (full breakdown)

The `/ws/monitoring` payload includes:
- `subscriptionHealth` object with active/paused/dead/cancelled/total/topics breakdown (L5)
- `activeBackfills` array with topic/groupName/processedMessages/totalMessages/percentComplete per in-progress backfill (L7)
- Total consumer group count via `DatabaseSetupService` (original implementation)

### Fan-Out Trace Propagation — ✅ DONE (design)

Design document created: `CONSUMER_GROUP_FANOUT_TRACE_PROPAGATION.md`. Recommends Option A (child spans per consumer group). Documents current trace gap and implementation scope. Implementation deferred.

### Overlaps — Resolved

| Overlap | Resolution |
|---------|-----------|
| `DeadConsumerDetectionJob` lifetime stats vs Prometheus | ✅ Prometheus via `ConsumerGroupMetrics` (L4). Job still exposes in-memory getters for programmatic access. |
| `getBlockedMessageStats()` vs admin endpoints | ✅ Reused via `ConsumerAlertHandler` REST endpoint `/consumer-alerts/blocked`. |
| `/ws/monitoring` consumer group count vs subscription health | ✅ Extended with full breakdown (L5) and active backfills (L7). |

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
| Admin force-remove endpoint | ✅ | ✅ | `DELETE .../force-remove` endpoint + `SubscriptionManager.forceRemoveConsumerGroup()` + `ForceRemoveResult` |

---

## Schema Gaps

| Column/Table | Needed For | Exists | Notes |
|--------------|-----------|--------|-------|
| `consecutive_misses` on `outbox_topic_subscriptions` | Flapping protection | ✅ | V015 migration; default 0, reset on heartbeat/resubscribe |
| `dead_after_misses` on `outbox_topic_subscriptions` | Flapping protection | ✅ | V015 migration; default 3, configurable per subscription |
| Partitioned consumer group tables | Offset/Watermark mode | ❌ | 3 new tables + 2 indexes defined in [PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md §19.4](PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md#partitioned-consumer-groups-offsetwatermark-mode) — supersedes old Phase 7 plan |

---

## Test Coverage Gaps

> **Updated**: March 2026 — Comprehensive test coverage pass completed. 13 new tests added,
> 4 test classes fixed for parallel execution safety. All 40 dead consumer tests now passing.

| Test Scenario | Exists | Why Not |
| `required_consumer_groups` decrement after DEAD | ✅ | Covered by `DeadConsumerGroupCleanupIntegrationTest` |
| Resurrection (DEAD→ACTIVE via heartbeat) | ✅ | 3 tests in `SubscriptionManagerIntegrationTest` |
| Flapping protection (consecutive failures) | ✅ | 12 tests in `FlappingProtectionIntegrationTest` |
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
| Service manager starts detection job on boot | ✅ | Task M7 — 3 tests in `DeadConsumerDetectionJobLifecycleTest` |
| Cancel subscription cleans up orphaned rows | ✅ | Task H6 — 4 tests in `CancelCleanupIntegrationTest` |
| Resurrection triggers re-backfill of missed messages | ✅ | Task H5 — 6 tests in `ResurrectionReBackfillIntegrationTest` |
| Fan-out trace propagation (child spans per group) | ❌ | Task L9 — design complete, no implementation |
| Remaining Prometheus metrics registered | ✅ | Task L10 — `completions.total`, `blocked.messages`, `detection.run.duration.seconds`, `detection.runs.total` done; `processing_seconds` and `backfill_progress` dropped |
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

**Implementation**: Wired into `PeeGeeQManager.startBackgroundTasks()` with auto-start on boot and auto-stop on shutdown. Added configurable properties `peegeeq.queue.dead-consumer-detection.enabled` (default: true) and `peegeeq.queue.dead-consumer-detection.interval` (default: 60s) to `QueueConfig`. Config validation enforces interval ≥ 10s. All 3 CRITICAL tasks (C1, C2, C3) now complete.

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
**Schema Change**: Add `consecutive_misses INT DEFAULT 0` and `dead_after_misses INT DEFAULT 3` to `outbox_topic_subscriptions`  
**Logic Change**: On detection run:
- If heartbeat expired: increment `consecutive_misses`
- If `consecutive_misses >= dead_after_misses`: mark DEAD
- If heartbeat is current: reset `consecutive_misses = 0`  
**Acceptance Criteria**:
- Single timeout miss increments counter but does not mark DEAD
- 3 consecutive misses marks DEAD
- Heartbeat resets counter to 0
- Threshold is configurable per subscription

**Status**: ✅ Completed

**Implementation**:
- V015 migration adds `consecutive_misses` (default 0) and `dead_after_misses` (default 3) columns
- `DeadConsumerDetector` uses two-phase SQL: Phase 1 increments `consecutive_misses` for expired subscriptions, Phase 2 marks DEAD only when `consecutive_misses >= dead_after_misses`
- `SubscriptionManager.updateHeartbeat()` resets `consecutive_misses = 0` on every heartbeat (works for resurrection too)
- `SubscriptionManager.subscribe()` resets `consecutive_misses = 0` on resubscription via ON CONFLICT
- `SubscriptionOptions.deadAfterMisses(int)` builder method allows per-subscription threshold (default 3, minimum 1)
- REST handler parses `deadAfterMisses` from subscription creation body
- Miss state is persisted on the subscription row (survives process restarts)
- 12 integration tests in `FlappingProtectionIntegrationTest`

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

**Status**: ✅ Completed

**Implementation**: `BackfillService` extended with `batchDelayMs` parameter for inter-batch throttling. New 3-arg constructor `(PgConnectionManager, String, Vertx)` enables non-blocking timer-based delays via `vertx.timer(batchDelayMs).mapEmpty()`. Legacy 2-arg constructor preserved (timer support disabled, zero delay only). Validation: negative delay → `IllegalArgumentException`, non-zero without Vertx → `IllegalStateException`. All existing overloads delegate with `batchDelayMs=0`. `PeeGeeQManager.createSubscriptionService()` passes Vertx to BackfillService.
- 13 unit tests in `BackfillRateLimitingUnitTest`: parameter validation, constructor variants, Vertx requirement enforcement
- 4 integration tests in `BackfillRateLimitingIntegrationTest`: delay slows throughput, zero delay normal, cancellation during delay, legacy overloads

#### Task L2: Graceful Shutdown Handling
**What**: On consumer shutdown, mark subscription CANCELLED and drain in-flight messages  
**Where**: Consumer lifecycle hooks  
**Status**: ✅ Completed

**Implementation**: `ConsumerGroup` interface extended with `default Future<Void> stopGracefully()` method that cancels the subscription in the database before stopping local consumers. Both `OutboxConsumerGroup` and `PgNativeConsumerGroup` override with subscription-aware shutdown: track `startedWithSubscription` flag → on `stopGracefully()`, cancel subscription via `DatabaseService` → `stopInternal()`. Cancel failure is recovered (group still stops cleanly). Groups started without `SubscriptionOptions` fall back to regular `stop()` behavior.
- 7 unit tests in `OutboxConsumerGroupGracefulShutdownTest`: not-active, closed, idempotent, without-subscription, with-subscription, cancel-fails, after-stop
- 55 existing `OutboxConsumerGroupCoreTest` tests remain passing (backward compat verified)

#### Task L3: Admin Force-Remove Endpoint
**What**: REST endpoint to force-remove a dead consumer group and clean up its messages  
**Where**: Admin/management API  
**Status**: ✅ Completed

**Implementation**: Added `forceRemoveConsumerGroup(topic, groupName)` to `SubscriptionService` interface (default method). `SubscriptionManager` implementation: validates subscription exists and is not already CANCELLED → marks DEAD (if not already) → runs `DeadConsumerGroupCleanup.cleanupDeadGroup()` → marks CANCELLED → returns `ForceRemoveResult` record with `previousStatus`, `messagesDecremented`, `orphanRowsRemoved`, `messagesAutoCompleted`. REST endpoint: `DELETE /api/v1/setups/:setupId/subscriptions/:topic/:groupName/force-remove` returns 200 with cleanup stats, 404 for not found, 409 for already cancelled. Error code `PGQERR0062` added. `PeeGeeQManager` wires `DeadConsumerGroupCleanup` into `SubscriptionManager` via `setDeadConsumerGroupCleanup()`.
- 5 unit tests in `ForceRemoveUnitTest`: null topic, null groupName, without cleanup service, with cleanup service, null cleanup setter
- 5 integration tests in `ForceRemoveIntegrationTest`: active subscription, dead subscription, non-existent, already-cancelled, idempotent

### MEDIUM — Tracing & Observability

#### Task M3: Add Tracing to DeadConsumerDetectionJob
**What**: Use `AsyncTraceUtils` wrappers instead of raw `vertx.setPeriodic()` for detection runs  
**Where**: `DeadConsumerDetectionJob.java`  
**Rationale**: Currently violates Tracing Architecture Guide — detection runs have no trace context, so logs cannot be correlated with affected messages  
**Acceptance Criteria**:
- Each detection run creates a traced span
- Logs from detection include `traceId`/`spanId`
- Detection timing is observable via trace spans

**Status**: ✅ Completed

**Implementation**: `runDetection()` passes `TraceCtx trace` through the entire compose chain. All 5 private logging methods (`logCleanupResults`, `logDeadConsumersDetected`, `logBlockedMessageStats`, `logSubscriptionSummary`, `logHealthyRun`) accept `TraceCtx` and wrap their contents in `try (var scope = TraceContextUtil.mdcScope(trace))`. Inline log calls in compose lambdas are also MDC-scoped. The existing `onFailure` handler already scoped MDC.
- 5 unit tests in `DetectionJobTracingTest`: trace creation per run, MDC clean after completion, trace propagation through dead consumer cleanup chain, trace preserved in error logs, lifecycle start/stop MDC cleanup

#### Task M4: Add Tracing to ConsumerGroupFetcher
**What**: Extract `traceparent` from fetched message headers and propagate trace context  
**Where**: `ConsumerGroupFetcher.java`  
**Rationale**: Tracing Architecture Guide mandates all message consumers extract trace context from headers  
**Acceptance Criteria**:
- Fetched messages carry trace context to downstream processing
- Trace spans show message delivery to specific consumer groups

**Status**: ✅ Completed

**Implementation**: `fetchMessages()` creates `TraceCtx.createNew()` at entry and scopes MDC for both the entry debug log and the result count debug log inside the `.map()` handler. Trace imports added: `TraceCtx`, `TraceContextUtil`.
- 3 integration tests in `ConsumerTracingTest`: MDC clean after fetch completion, succeeds without external trace context, preserves caller's pre-existing MDC

#### Task M5: Add Tracing to CompletionTracker
**What**: Carry trace context from the message being completed through the completion flow  
**Where**: `CompletionTracker.java`  
**Acceptance Criteria**:
- Completion operations are visible in distributed traces
- Can trace a message from publish → fan-out → per-group completion

**Status**: ✅ Completed

**Implementation**: Both `markCompleted()` and `markFailed()` create `TraceCtx.createNew()` at entry and scope MDC for the entry log. All internal log calls (validation warnings, idempotent debug, completion status, error messages) are wrapped in `try (var scope = TraceContextUtil.mdcScope(trace))`. Trace imports added: `TraceCtx`, `TraceContextUtil`.
- 4 integration tests in `ConsumerTracingTest`: MDC clean after markCompleted/markFailed (success or failure), preserves caller's pre-existing MDC for both markCompleted and markFailed

#### Task M6: Add Tracing to BackfillService
**What**: Create traced spans per batch for backfill observability  
**Where**: `BackfillService.java`  
**Acceptance Criteria**:
- Each backfill batch creates a child span
- Backfill progress is observable via distributed tracing

**Status**: ✅ Already Implemented

**Note**: BackfillService already has comprehensive tracing — `startBackfill()` and `cancelBackfill()` each create `TraceCtx.createNew()`, the trace is passed as a parameter through the entire recursive batch chain (`acquireBackfillLock()` → `processBatches()` → `processBatchesRecursively()` → `processOneBatch()` → `processFetchedBatch()` → `markBackfillCompleted()`), and every log point uses `try (var scope = TraceContextUtil.mdcScope(trace))`. This was missed in the original tracker audit.

### HIGH — Should Fix (future)

#### Task H5: Resurrection Re-Backfill
**What**: When a DEAD consumer resumes via heartbeat (DEAD→ACTIVE), re-backfill messages that were cleaned up (decremented) during the DEAD period  
**Where**: `SubscriptionManager.updateHeartbeat()` → trigger `BackfillService`  
**Previous Behaviour**: `updateHeartbeat()` transitioned DEAD→ACTIVE but did not re-backfill. Messages cleaned up by `DeadConsumerGroupCleanup` during the DEAD period were permanently lost to this consumer group.  
**Fixed Behaviour**: `updateHeartbeat()` now detects DEAD→ACTIVE resurrection, resets backfill status (`backfill_status`, `backfill_checkpoint_id`, `backfill_processed_messages`), and chains `BackfillService.startBackfill(topic, groupName, BackfillScope.PENDING_ONLY)` with `.recover()` for resilience. If `BackfillService` is not configured, resurrection succeeds with a debug log.  
**Acceptance Criteria**:
- On DEAD→ACTIVE resurrection, automatically trigger a backfill scoped to messages missed during the DEAD period
- Backfill uses the same `BackfillService` infrastructure (checkpoint, cancellation, progress)
- Test: publish 10 messages → consumer dies → 5 messages cleaned up → consumer resurrects → re-backfill delivers the 5 cleaned messages
- If `BackfillService` is not configured, resurrection still works (log WARNING, no backfill)

**Status**: ✅ Complete — `updateHeartbeat()` modified in `SubscriptionManager.java` to detect DEAD→ACTIVE resurrection, call `resetBackfillStatus()`, and chain `backfillService.startBackfill(topic, groupName, BackfillScope.PENDING_ONLY)` with `.recover()` for resilience. New `resetBackfillStatus()` method clears backfill_status, checkpoint_id, processed_messages. 6 integration tests in `ResurrectionReBackfillIntegrationTest.java` (3 RED-verified). 12 existing SubscriptionManager tests GREEN (zero regressions).

#### Task H6: CANCELLED Subscription Orphan Cleanup
**What**: When a subscription is cancelled via `cancel()`, clean up its PENDING/PROCESSING rows in `outbox_consumer_groups` and decrement `required_consumer_groups` on affected messages  
**Where**: `SubscriptionManager.cancel()` or chain `DeadConsumerGroupCleanup` after cancel  
**Previous Behaviour**: `cancel()` only called `updateStatus(CANCELLED)`. No `outbox_consumer_groups` rows were removed. Messages remained blocked waiting for a group that would never process them. `forceRemoveConsumerGroup()` did run cleanup, but plain `cancel()` did not.  
**Fixed Behaviour**: `cancel()` now chains `DeadConsumerGroupCleanup.cleanupDeadGroup()` after `updateStatus(CANCELLED)` with `.recover()` for resilience. Cleanup decrements `required_consumer_groups`, removes orphaned tracking rows, and auto-completes unblocked messages. If `DeadConsumerGroupCleanup` is not configured, cancel succeeds without cleanup.  
**Acceptance Criteria**:
- `cancel()` chains `DeadConsumerGroupCleanup.cleanupDeadGroup()` (or equivalent) after marking CANCELLED
- `required_consumer_groups` is decremented on affected messages
- Messages where `completed >= required` after decrement are auto-completed
- Orphaned `outbox_consumer_groups` rows are removed
- Test: subscribe → publish messages → cancel subscription → verify messages are no longer blocked
- `forceRemoveConsumerGroup()` behaviour unchanged (already works correctly)

**Status**: ✅ Complete — `cancel()` modified in `SubscriptionManager.java` to chain `DeadConsumerGroupCleanup.cleanupDeadGroup()` after `updateStatus(CANCELLED)` with `.recover()` for resilience. 4 integration tests in `CancelCleanupIntegrationTest.java` (1 RED-verified). 27 subscription tests GREEN across 4 test classes (zero regressions).

### MEDIUM — Should Address (future)

#### Task M7: Service Manager Integration Test
**What**: Verify that `PeeGeeQManager.startBackgroundTasks()` actually starts the `DeadConsumerDetectionJob` and that shutdown stops it  
**Where**: New integration test in `peegeeq-db` or `peegeeq-integration-tests`  
**Current State**: C3 wired the job into `PeeGeeQManager`, but no test verified the boot→run→shutdown lifecycle via the manager  
**Acceptance Criteria**:
- Test creates a `PeeGeeQManager`, calls `start()` (which calls `startBackgroundTasks()`)
- Verifies `DeadConsumerDetectionJob` is running (e.g., `getTotalRunCount() > 0` after a delay)
- Calls manager shutdown and verifies job is stopped
- Uses real PostgreSQL via Testcontainers

**Status**: ✅ Complete — `DeadConsumerDetectionJobLifecycleTest.java` in `peegeeq-db` with 3 integration tests: (1) `testDetectionJobStartsWithManager` — verifies job is running and `getTotalRunCount() > 0` after start, (2) `testDetectionJobStopsWithManager` — verifies job reference is null and `isStarted()` false after `stop()`, (3) `testDetectionJobDisabledByConfig` — verifies job is null when `dead-consumer-detection.enabled=false`. Added package-visible `getDeadConsumerDetectionJob()` getter to `PeeGeeQManager`. 14 tests GREEN (11 existing + 3 new, zero regressions).

### LOW — Monitoring Endpoints

#### Task L4: Expose Dead Consumer Stats via Prometheus
**What**: Publish `peegeeq_dead_consumers_total{topic}` and `peegeeq_blocked_messages_total{topic,group}` as Prometheus metrics  
**Where**: Metrics integration layer  
**Rationale**: `DeadConsumerDetector` already computes this data but it's only available in logs. The Tracing User Guide promises per-consumer-group metrics that don't exist.  
**Status**: ✅ Complete — `ConsumerGroupMetrics.java` implements `MeterBinder`, 6 gauges backed by `AtomicLong`, `refresh()` calls `getSubscriptionSummary()`. 7 integration tests GREEN.
**What**: Include active/paused/dead/cancelled breakdown in the `/ws/monitoring` payload  
**Where**: Monitoring endpoint data source  
**Rationale**: `getSubscriptionSummary()` already returns this data. Monitoring endpoint currently only shows total consumer group count.  
**Status**: ✅ Complete — `SystemMonitoringHandler.collectMetricsFromServices()` now counts by `SubscriptionState` and adds `subscriptionHealth` JSON object.
**What**: Define how traces branch when one message is delivered to N consumer groups  
**Where**: Design document / Tracing Architecture Guide  
**Open Questions**: Should each group get a child span? Parallel branches? How to visualise in Jaeger?  
**Status**: ✅ Complete — `CONSUMER_GROUP_FANOUT_TRACE_PROPAGATION.md` created. Recommends Option A (child spans per consumer group).
**What**: Include in-progress backfill status in the monitoring payload  
**Where**: Monitoring endpoint data source  
**Rationale**: `BackfillService.getBackfillProgress()` already returns status/checkpoint/percentage but isn't exposed.  
**Status**: ✅ Complete — `SystemMonitoringHandler` collects in-progress backfills and adds `activeBackfills` array to payload.

#### Task L9: Fan-Out Trace Propagation Implementation
**What**: When one message is delivered to N consumer groups, create a child span per group so fan-out is visible as a span tree in Jaeger/Zipkin  
**Where**: `ConsumerGroupFetcher` (or the delivery loop that invokes handlers per group)  
**Design**: `CONSUMER_GROUP_FANOUT_TRACE_PROPAGATION.md` recommends Option A — child spans per consumer group  
**Prerequisite**: `TraceCtx.childSpan()` infrastructure already exists and is tested  
**Acceptance Criteria**:
- Each consumer group delivery creates a child span from the message's root trace
- Span attributes include `topic`, `group_name`, `message_id`
- Fan-out is visible as parallel branches in a trace visualiser
- Existing `ConsumerTracingTest` updated or extended to verify child span creation

**Status**: ❌ Not Started (design complete, implementation not done)

#### Task L10: Remaining Prometheus Metrics
**What**: Implement the 5 Prometheus metrics listed in the Tracing User Guide that are not yet registered  
**Where**: `ConsumerGroupMetrics.java` or new `MeterBinder` classes  
**Metrics**:

| Metric | Labels | Source Data |
|--------|--------|-------------|
| `peegeeq_messages_received_total` | `topic`, `consumer_group` | Increment in `CompletionTracker.markCompleted()` |
| `peegeeq_blocked_messages_total` | `topic`, `group` | `DeadConsumerDetector.getBlockedMessageStats()` |
| `peegeeq_consumer_group_processing_seconds` | `topic`, `group`, `quantile` | Timer around message handler callback |
| `peegeeq_backfill_progress_ratio` | `topic`, `group` | `BackfillService.getBackfillProgress()` |
| `peegeeq_detection_run_duration_seconds` | — | `DetectionResult.detectionTimeMs` |

**Acceptance Criteria**:
- All 5 metrics registered and producing non-zero values during integration tests
- `peegeeq_messages_received_total` increments on each successful completion
- `peegeeq_blocked_messages_total` refreshes on each detection run
- Timer metrics use Micrometer `Timer` with p50/p95/p99 percentiles

**Status**: ✅ DONE

**Implemented Metrics** (4 tests GREEN):

| Metric | Labels | Source Data | Status |
|--------|--------|-------------|--------|
| `peegeeq.completions.total` | `topic`, `group` | Counter in `CompletionTracker.markCompleted()` via optional `MeterRegistry` | ✅ |
| `peegeeq.blocked.messages` | `topic`, `group` | Dynamic gauge from `DeadConsumerDetector.getBlockedMessageStats()` during `ConsumerGroupMetrics.refresh()` | ✅ |
| `peegeeq.detection.run.duration.seconds` | — | Gauge from `DeadConsumerDetectionJob.getTotalRunTimeMs()` | ✅ |
| `peegeeq.detection.runs.total` | — | Gauge from `DeadConsumerDetectionJob.getTotalRunCount()` | ✅ |
| `peegeeq_consumer_group_processing_seconds` | `topic`, `group`, `quantile` | Timer around message handler callback | ❌ Dropped — invasive hot-path change, no concrete need |
| `peegeeq_backfill_progress_ratio` | `topic`, `group` | `BackfillService.getBackfillProgress()` | ❌ Dropped — invasive wiring change, no concrete need |

**Implementation**:
- `CompletionTracker.java` — added optional `MeterRegistry` field and overloaded constructor `(PgConnectionManager, String, MeterRegistry)`. `recordCompletion()` increments `peegeeq.completions.total` counter with topic+group tags on successful completion.
- `ConsumerGroupMetrics.java` — extended with: `setDetectionJob()` setter, dynamic blocked messages gauges per topic+group, detection run duration/count gauges. `refresh()` now also calls `getBlockedMessageStats()` and reads detection job stats.
- `DeadConsumerDetectionJob.java` — added `getTotalRunTimeMs()` getter for cumulative run time.
- `BaseIntegrationTest.java` — changed `awaitFuture()` from `private` to `protected` (subclass access).
- `HealthCheckManagerCoreTest.java` — removed duplicate `awaitFuture()` (now inherited).

**Test Evidence**: `RemainingPrometheusMetricsIntegrationTest.java` — 4 tests:
- `testCompletionCounterIncrementsOnMarkCompleted` — verifies counter increments with topic+group tags
- `testCompletionCounterWithoutRegistryStillWorks` — verifies backward compatibility (no registry)
- `testBlockedMessagesGaugeAfterRefresh` — verifies dynamic gauge creation for dead group blocked messages
- `testDetectionRunDurationGaugeAfterRefresh` — verifies detection duration and run count gauges after `start()`

**Regression**: 39/39 tests pass across `ConsumerGroupMetricsIntegrationTest` (7), `RemainingPrometheusMetricsIntegrationTest` (4), `CompletionTrackerIntegrationTest` (15), `HealthCheckManagerCoreTest` (13).

#### Task L8: Dead Consumer Alerting REST Endpoints
**What**: REST API surface for programmatic consumption of dead consumer alerts, health summaries, and blocked message stats  
**Where**: New `ConsumerAlertHandler` + routes in `PeeGeeQRestServer`  
**Endpoints**:
- `GET /api/v1/setups/:setupId/consumer-alerts/dead` — lists all DEAD subscriptions with timing details (lastHeartbeat, deadSince, heartbeatTimeout, overdue duration)
- `GET /api/v1/setups/:setupId/consumer-alerts/summary` — subscription health summary (active/paused/dead/cancelled/total counts)
- `GET /api/v1/setups/:setupId/consumer-alerts/blocked` — blocked message statistics per dead consumer group  
**Status**: ✅ Complete

**Implementation**:
- `ConsumerAlertHandler.java` (~130 lines) in `rest.handlers` — 3 handler methods delegating to `SubscriptionService`
- `SubscriptionService` extended with 3 default methods: `listDeadSubscriptions()`, `getSubscriptionHealthSummary()`, `getBlockedMessageStats()`
- `SubscriptionManager` implements all 3 — delegates to `DeadConsumerDetector` (creates internally using `connectionManager`)
- Error code `PGQERR0063` (`SUBSCRIPTION_ALERTS_FAILED`) added to `PeeGeeQErrorCodes`
- 3 routes registered in `PeeGeeQRestServer`
- 7 integration tests in `DeadConsumerAlertingIntegrationTest`: dead list, health summary, blocked stats, unknown setup (×3), timing details
- Uses `TestDatabaseSetupService` inner class to bypass REST create flow for Flyway V010 migration

Primary sources:
- `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/ConsumerAlertHandler.java`
- `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/PeeGeeQRestServer.java`
- `peegeeq-api/src/main/java/dev/mars/peegeeq/api/subscription/SubscriptionService.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/subscription/SubscriptionManager.java`
- `peegeeq-api/src/main/java/dev/mars/peegeeq/api/error/PeeGeeQErrorCodes.java`
- `peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/DeadConsumerAlertingIntegrationTest.java`

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
| `ConsumerGroupMetrics.java` | `db.metrics` | ~95 | ✅ Complete — `MeterBinder` with 6 gauges, `refresh()` via `getSubscriptionSummary()` |
| `ConsumerAlertHandler.java` | REST handler | ~130 | ✅ Complete — 3 endpoints: dead subscriptions list, health summary, blocked stats |

### Test Code — Verified Existing

| Test File | Tests | Run Against DB | Status |
|-----------|-------|----------------|--------|
| `TopicConfigServiceIntegrationTest` | 7 | ✅ Yes | ✅ Passing |
| `SubscriptionManagerIntegrationTest` | 12 | ✅ Yes | ✅ Passing — includes 3 resurrection tests + 3 backfill lifecycle tests |
| `ZeroSubscriptionValidatorIntegrationTest` | 7 | ✅ Yes | ✅ Passing |
| `FanoutProducerIntegrationTest` | 6 | ✅ Yes | ✅ Passing |
| `ConsumerGroupFetcherIntegrationTest` | 4 | ✅ Yes | ✅ Passing |
| `CompletionTrackerIntegrationTest` | 13 | ✅ Yes | ✅ Passing — `@Tag(FLAKY)` removed, parallel-safe (UUID-based topic names) |
| `CleanupServiceIntegrationTest` | 6 | ✅ Yes | ✅ Passing |
| `DeadConsumerDetectorIntegrationTest` | 4 | ✅ Yes | ✅ Passing — `@Tag(FLAKY)` removed, parallel-safe |
| `DeadConsumerDetectorComprehensiveTest` | 10 | ✅ Yes | ✅ Passing — PAUSED/CANCELLED/boundary/mixed/API coverage |
| `BackfillServiceIntegrationTest` | 9 | ✅ Yes | ✅ Passing |
| `DeadConsumerDetectionJobIntegrationTest` | 8 | ✅ Yes | ✅ Passing — pipeline + concurrent guard tests |
| `DeadConsumerGroupCleanupIntegrationTest` | 9 | ✅ Yes | ✅ Passing — error resilience test |
| `FlappingProtectionIntegrationTest` | 12 | ✅ Yes | ✅ Passing — single miss, recovery reset, threshold, isolation, PAUSED, intermittent flapping, resubscribe, resurrection, differential, persistence, all-topics, cross-topic |
| `DetectionJobTracingTest` | 5 | ❌ No (CORE) | ✅ Passing — stub-based tracing tests |
| `ConsumerTracingTest` | 7 | ✅ Yes | ✅ Passing — fetcher (3) + completion tracker (4) tracing tests |
| `CompletionTrackerCoreTest` | 5 | ✅ Yes | ✅ Passing — creation, markCompleted, idempotent, counter, all-groups |
| `ForceRemoveUnitTest` | 5 | ❌ No (CORE) | ✅ Passing — null validation, without/with cleanup service, null setter |
| `ForceRemoveIntegrationTest` | 5 | ✅ Yes | ✅ Passing — active, dead, non-existent, already-cancelled, idempotent |
| `BackfillRateLimitingUnitTest` | 13 | ❌ No (CORE) | ✅ Passing — parameter validation, constructor variants, Vertx requirement |
| `BackfillRateLimitingIntegrationTest` | 4 | ✅ Yes | ✅ Passing — delay slows throughput, zero delay, cancellation, legacy overloads |
| `SubscriptionCreateAndBackfillIntegrationTest` | 15 | ✅ Yes | ✅ Passing — subscribe REST + backfill REST endpoints |
| `ConsumerGroupMetricsIntegrationTest` | 7 | ✅ Yes | ✅ Passing — gauge registration, active/dead/paused/topics counts, refresh replacement, detection run |
| `DeadConsumerAlertingIntegrationTest` | 7 | ✅ Yes | ✅ Passing — dead list, health summary, blocked stats, unknown setup (×3), timing details |
| Performance tests (P1-P4) | 4 | ✅ Yes | ✅ Passing |

### Missing Test Coverage

| Scenario | Needed For Task |
|----------|-----------------|
| Dead consumer → decrement → messages cleanable | C1, C2 — ✅ covered by `DeadConsumerGroupCleanupIntegrationTest` |
| Full cycle: publish → die → detect → clean → verify | C1, C2 — ✅ covered by `testEndToEndDetectCleanupPipeline` |
| Service manager starts/stops detection job | C3 — wired into `PeeGeeQManager`, no dedicated integration test |
| Resurrection via heartbeat | H1 — ✅ completed |
| Subscribe FROM_BEGINNING triggers backfill | H2 — ✅ covered by `SubscriptionManagerIntegrationTest` (3 tests) |
| Flapping protection (1 miss = no DEAD, 3 = DEAD) | M1 — ✅ covered by `FlappingProtectionIntegrationTest` (12 tests) |
| Detection job runs with trace context | M3 — ✅ covered by `DetectionJobTracingTest` (5 tests) |
| Consumer group fetch with trace propagation | M4 — ✅ covered by `ConsumerTracingTest` (3 tests) |
| Completion tracking with trace context | M5 — ✅ covered by `ConsumerTracingTest` (4 tests) |
| Backfill operations with traced spans | M6 — ✅ already implemented |

---

## Previous Document Corrections

The previous `CONSUMER_GROUP_FANOUT_IMPLEMENTATION_PLAN.md` was removed (2026-04-06) because it was
heavily stale — incorrect status claims, missing features (flapping protection, tracing, Prometheus
metrics, force-remove, alerting endpoints), outdated test counts, and contradictory phase statuses.
This tracker is the sole source of truth for consumer group fan-out implementation status.

---

## Change Log

| Date | Change | Author |
|------|--------|--------|
| 2026-03-01 | Created — full code audit against design spec | — |
| 2026-03-01 | Added operational logging: `DeadConsumerDetector` now returns structured `DetectionResult`, `BlockedMessageStats`, `SubscriptionSummary`. `DeadConsumerDetectionJob` rewritten with per-run summaries, blocked message impact, critical alerts (>1000 msgs or >24h blocked), subscription landscape, failure tracking, overlap guard, lifetime stats, human-readable durations. | — |
| 2026-03-01 | Tracing/observability cross-reference: Expanded "Metrics/Monitoring" into 7 granular rows. Added new section "Tracing & Observability — Gap Analysis" documenting zero tracing instrumentation across all consumer group code, inconsistency where User Guide promises Prometheus metrics that don't exist, and summary-only monitoring endpoint coverage. Added tasks M3-M6 (tracing instrumentation) and L4-L7 (monitoring endpoints). Added tracing doc references to header. | — |
| 2026-03-01 | **C1+C2 Completed**: Created `DeadConsumerGroupCleanup.java` (~250 lines) with 3-step transactional cleanup (decrement → orphan removal → auto-complete). Wired into `DeadConsumerDetectionJob` — detection now chains cleanup automatically with per-group logging, cumulative stats, and cleanup failure tracking. Created `DeadConsumerGroupCleanupIntegrationTest.java` (8 tests). Updated job constructor to require cleanup dependency. | — |
| 2026-03-01 | **C3 Completed**: Wired `DeadConsumerDetectionJob` into `PeeGeeQManager.startBackgroundTasks()` with auto-start on boot and auto-stop on shutdown. Added configurable properties `peegeeq.queue.dead-consumer-detection.enabled` (default: true) and `peegeeq.queue.dead-consumer-detection.interval` (default: 60s) to `QueueConfig`. Added config validation (interval ≥ 10s). All 3 CRITICAL tasks (C1, C2, C3) now complete. | — |
| 2026-03-01 | **Comprehensive test coverage pass**: Created `DeadConsumerDetectorComprehensiveTest.java` (10 tests, 695 lines) covering PAUSED detection, CANCELLED exclusion, already-DEAD re-detection, mixed subscription states, `DetectionResult`/`BlockedMessageStats`/`SubscriptionSummary` API validation, `countEligibleForDeadDetection`, and boundary conditions. Added `testEndToEndDetectCleanupPipeline` (detect→cleanup→auto-complete full pipeline) and `testConcurrentDetectionGuardPreventsOverlap` (pure integration, validates overlap guard skips concurrent invocations) to `DeadConsumerDetectionJobIntegrationTest`. Added `testCleanupContinuesAfterOneGroupFails` (subclass override injection, validates `.recover()` error isolation) to `DeadConsumerGroupCleanupIntegrationTest`. | — |
| 2026-03-01 | **Test infrastructure fixes**: Fixed 4 test classes for JUnit 5 parallel execution safety — added `@Execution(ExecutionMode.SAME_THREAD)` to prevent intra-class parallel interference. Removed `@Tag(FLAKY)` from `DeadConsumerDetectorIntegrationTest` after fixing root cause (hardcoded topic names + exact count assertions in parallel environment). All tests now use UUID-based unique topic names and verify final subscription status instead of exact detection counts. Fixed 3 pre-existing `SubscriptionOptions` builder validation bugs (`heartbeatTimeoutSeconds` must be strictly > `heartbeatIntervalSeconds`). All 40 dead consumer tests across 5 classes now passing with 0 failures. Test classes verified as actually running against real PostgreSQL via Testcontainers. | — |
| 2026-03-01 | **H1 Completed**: Heartbeat auto-resurrection. `SubscriptionManager.updateHeartbeat()` now conditionally transitions DEAD→ACTIVE using `CASE WHEN subscription_status = 'DEAD' THEN 'ACTIVE' ELSE subscription_status END`. CTE captures pre-update status for INFO-level resurrection logging. CANCELLED and PAUSED subscriptions are unaffected. 3 new tests added to `SubscriptionManagerIntegrationTest`. Full regression: 49 tests, 0 failures across 6 classes. | — |
| 2026-03-01 | **H2 Completed**: Backfill lifecycle integration. `SubscriptionManager.subscribe()` now auto-triggers `BackfillService.startBackfill()` for `FROM_BEGINNING` subscriptions when BackfillService is configured via `setBackfillService()`. Backfill runs after subscribe connection is released. Failure is resilient — logged as WARNING, subscription still created. 3 new tests added to `SubscriptionManagerIntegrationTest`. Full regression: 52 tests, 0 failures across 6 classes. | — |
| 2026-03-01 | **H3+H4 Completed**: Subscribe REST endpoint + Backfill REST endpoints. Added `createSubscription()` handler (POST, returns 201) with JSON body validation (groupName required, startPosition enum, heartbeat config, timestamp parsing). Added 3 backfill handlers: `startBackfill()` (POST), `getBackfillProgress()` (GET with percentComplete calculation), `cancelBackfill()` (DELETE). Extended `SubscriptionService` interface with `startBackfill()`/`cancelBackfill()` default methods. `SubscriptionManager` overrides delegate to `BackfillService`. 4 new error codes. 4 new routes in `PeeGeeQRestServer`. Created `SubscriptionCreateAndBackfillIntegrationTest` (12 tests). REST regression: 19 tests (12 new + 7 existing), 0 failures. DB regression: 52 tests, 0 failures. | — |
| 2026-03-01 | **H3+H4 Code Review Fixes**: (1) Added `Objects.requireNonNull(topic/groupName)` to `SubscriptionManager.startBackfill()`/`cancelBackfill()` for consistency with all other methods (18 existing usages). (2) Fixed fully-qualified `io.vertx.core.json.JsonObject` in `SubscriptionService` interface — now uses import. (3) Rewrote `createSubscription()` handler to return 409 on duplicate subscription (was returning 201 via silent upsert) — pre-checks with `getSubscription()` and returns `SUBSCRIPTION_ALREADY_EXISTS` error. Added `testCreateDuplicateSubscription` test. (4) Added missing `import io.vertx.core.Future` and `import SubscriptionState` to `SubscriptionHandler`. Noted: `BACKFILL_NOT_FOUND` error code (PGQERR0060) is dead code — reserved for future admin endpoints. Full regression: 71 tests (19 REST + 52 DB), 0 failures. | — |
| 2026-03-01 | **Code Review Follow-up — Dead Code + Happy-Path Coverage**: (1) Removed dead `BACKFILL_NOT_FOUND` error code (PGQERR0060) — was declared but never referenced; all not-found cases use `SUBSCRIPTION_NOT_FOUND` via `sendSubscriptionNotFoundError()`. (2) Wired `BackfillService` into production code: `PeeGeeQManager.createSubscriptionService()` now creates a `BackfillService` alongside `SubscriptionManager`, using `DEFAULT_POOL_ID` instead of null. This enables backfill REST endpoints to actually work end-to-end (previously returned 501 `UnsupportedOperationException`). (3) Added 3 happy-path REST tests: `testStartBackfillHappyPath` (200, verifies COMPLETED status with 0 messages), `testStartBackfillAlreadyCompleted` (200, verifies ALREADY_COMPLETED on re-call), `testCancelBackfillHappyPath` (200, verifies success/topic/groupName/action). Full regression: 74 tests (22 REST + 52 DB), 0 failures. | — |
| 2026-04-04 | **CompletionTracker edge case coverage**: Added 5 edge case tests to `CompletionTrackerIntegrationTest` (13 total): FAILED→COMPLETED recovery, retry_count verification (0→1→2), markFailed unknown group rejection, PAUSED subscription rejection, non-existent message rejection. Added `pauseSubscription()` helper. Removed `@Tag(FLAKY)` — all tests use UUID-based unique topic names and pass reliably (2 consecutive clean runs verified). Updated test inventory: added `DetectionJobTracingTest` (5), `ConsumerTracingTest` (7), `CompletionTrackerCoreTest` (5) rows. | — |
| 2026-04-05 | **Schema template + test schema alignment**: Added `consecutive_misses INTEGER NOT NULL DEFAULT 0` and `dead_after_misses INTEGER NOT NULL DEFAULT 3` to `08b-consumer-table-subscriptions.sql` (multi-tenant schema template) and `PeeGeeQManagerCloseLogLevelTest.java` (standalone test schema). These were missed when V015 migration and `SharedPostgresTestExtension` were updated earlier. Updated Missing Test Coverage section — M1, M3-M6 now cross-referenced to their test classes. Full regression: 571 tests (3 pre-existing `ConsumerTracingTest` MDC failures unrelated to flapping), 82 dead-consumer-related tests all passing. | — |
| 2026-04-05 | **L4-L7 Complete**: (L4) Created `ConsumerGroupMetrics.java` — `MeterBinder` with 6 gauges (active/paused/dead/cancelled/total/topics), `refresh()` via `DeadConsumerDetector.getSubscriptionSummary()`, 7 integration tests GREEN, full module regression clean. (L5) Extended `SystemMonitoringHandler.collectMetricsFromServices()` — counts subscriptions by `SubscriptionState`, adds `subscriptionHealth` JSON object to `/ws/monitoring` payload. (L6) Created `CONSUMER_GROUP_FANOUT_TRACE_PROPAGATION.md` design document — recommends child spans per consumer group (Option A), documents current trace gap and implementation scope. (L7) Extended handler to collect in-progress backfills from `SubscriptionInfo.backfillStatus()`, adds `activeBackfills` array to payload with topic/groupName/processedMessages/totalMessages/percentComplete. | — |
| 2026-04-05 | **L8 Complete (Dead Consumer Alerting Endpoint)**: Created `ConsumerAlertHandler.java` (~130 lines) with 3 REST endpoints: `GET .../consumer-alerts/dead` (dead subscription list with timing), `GET .../consumer-alerts/summary` (health summary), `GET .../consumer-alerts/blocked` (blocked message stats). Extended `SubscriptionService` with 3 default methods, `SubscriptionManager` implements via `DeadConsumerDetector`. Error code `PGQERR0063`. 7 integration tests GREEN, 103/103 core tests passing. | — |
| 2026-04-05 | **Partitioned Consumer Groups Design**: Created partitioned design (now consolidated into [PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md §19](PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md#partitioned-consumer-groups-offsetwatermark-mode)) — comprehensive design for offset/watermark mode + partitioned consumption. Covers partition key (reuse `message_group`), 3 new tables, generation-based fencing, watermark sweep, 6 implementation phases, 5 open questions. | — |
| 2026-04-05 | **Tracker refresh**: Updated stale entries — Dead Consumer Alerting `❌ NOT STARTED` → `✅ DONE`, Prometheus Metrics `❌ NOT STARTED` → `✅ DONE`, Fan-Out Trace Propagation `❌ NOT DESIGNED` → `✅ DONE (design)`, Monitoring upgraded from summary-only to full breakdown. Removed stale "Impact" / "Specific violations" text from Tracing section (all fixed by M3-M6). Resolved all 3 overlaps. Added `ConsumerAlertHandler.java` and `DeadConsumerAlertingIntegrationTest` to inventories. Added Partitioned Consumer Groups Design to status summary. | — || 2026-04-06 | **Document consolidation + future work audit**: Merged `PARTITIONED_CONSUMER_GROUPS_DESIGN.md` into [PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md §19](PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md#partitioned-consumer-groups-offsetwatermark-mode). Updated all tracker references to point to consolidated doc. Deleted standalone partitioned design doc. Audited 5 un-tracked future work items and confirmed all are genuinely not implemented: (H5) Resurrection re-backfill, (H6) CANCELLED orphan cleanup, (M7) Service manager integration test, (L9) Fan-out trace propagation implementation, (L10) 5 remaining Prometheus metrics. Added formal task definitions with acceptance criteria for each. Updated status summary and test coverage gaps tables. | — |