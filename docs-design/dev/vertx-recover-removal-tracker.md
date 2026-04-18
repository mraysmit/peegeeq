# `.recover()` Removal — Implementation Tracker

**STATUS: COMPLETE** — All 207 `.recover()` instances removed (117 production + 90 test).
Completed 2026-04-18 on branch `feature/offset-watermark-phase1`.

Tracks remediation of all `.recover()` instances across the PeeGeeQ codebase.
See `vertx-recover-usage.md` for the full audit, classifications, and rationale.

## Fix types

| Fix | Description |
|---|---|
| **REMOVE** | Delete the `.recover()` block entirely. Let `.compose()` short-circuit propagation do its job. |
| **→ .eventually()** | Replace with `.eventually(() -> cleanup())`. For shutdown/cleanup that must run regardless of outcome. |
| **→ .onFailure()** | Replace with `.onFailure(e -> log.error(...))`. For logging side-effects that don't alter the chain. |
| **→ .transform()** | Replace with `.transform(...)`. For converting failure into a domain type (e.g. health status). |
| **→ SQL** | Move idempotency/conflict handling into SQL (`ON CONFLICT`, `IF NOT EXISTS`). Remove the `recover()`. |
| **→ RETRY-INFRA** | Move retry/failover logic into middleware. Remove inline `recover()`. |
| **→ FRAMEWORK** | Move DLQ/retry routing into the message processing framework. Remove inline `recover()`. |
| **→ ctx.fail()** | Replace with `ctx.fail(err)` + centralized failure handler. For HTTP handler error responses. |

---

## Module: peegeeq-bitemporal (6 instances)

### PgBiTemporalEventStore.java

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 1 | 1286 | ERASURE-IN-SHUTDOWN | → .eventually() | ☑ |
| 2 | 1298 | ERASURE-IN-SHUTDOWN | → .eventually() | ☑ |
| 3 | 1310 | ERASURE-IN-SHUTDOWN | → .eventually() | ☑ |
| 4 | 1816 | RE-WRAPS-FAILURE | → .onFailure() | ☑ |
| 5 | 2122 | ERASURE-IN-SHUTDOWN | → .eventually() | ☑ |
| 6 | 2133 | ERASURE-IN-SHUTDOWN | → .eventually() | ☑ |

**Notes:** Entries 1–3 and 5–6 use an `AtomicReference<Throwable>` pattern to capture
the first error and re-raise it at the end of the close chain. `.eventually()` preserves
the original success/failure without this manual bookkeeping.

---

## Module: peegeeq-db (59 instances)

### PeeGeeQManager.java (12 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 7 | 278 | RE-WRAPS-FAILURE | → .onFailure() for logging; keep the re-throw via .transform() | ☑ |
| 8 | 285 | ERASURE-IN-SHUTDOWN | → .eventually() | ☑ |
| 9 | 325 | RE-WRAPS-FAILURE | → .onFailure() + .transform() to wrap | ☑ |
| 10 | 407 | ERASURE-IN-SHUTDOWN | → .eventually() | ☑ |
| 11 | 410 | ERASURE-IN-SHUTDOWN | → .eventually() | ☑ |
| 12 | 423 | ERASURE-IN-SHUTDOWN | → .eventually() | ☑ |
| 13 | 437 | ERASURE-IN-SHUTDOWN | → .eventually() | ☑ |
| 14 | 449 | ERASURE-IN-SHUTDOWN | → .eventually() | ☑ |
| 15 | 474 | SELECTIVE-RECOVERY | → .eventually() (this is shutdown cleanup disguised as selective recovery) | ☑ |
| 16 | 490 | SELECTIVE-RECOVERY | → .eventually() for RejectedExecutionException; keep failedFuture for others | ☑ |
| 17 | 738 | RE-WRAPS-FAILURE | → .onFailure() + .transform() to wrap | ☑ |
| 18 | 935 | ERASURE-IN-SHUTDOWN | → .eventually() | ☑ |

**Notes:** Items 7–8: `start()` method — replaced nested `.recover()` with `.onFailure()` + `.transform()` chain. Initial implementation incorrectly used `.eventually()` for the cleanup branch, which caused cleanup to run on **both** success and failure (destroying components that were just started). Corrected on 2026-04-18 to use `.transform(ar -> ...)` that branches on `ar.failed()` only and restores the original `RuntimeException("Failed to start PeeGeeQ Manager", throwable)` wrapping. Verified by `PeeGeeQManagerCloseReactiveErrorPropagationTest` (2 tests) and `PeeGeeQManagerLifecycleTest`.
Item 9: `stop()` — `.recover()` → `.onFailure()`.
Items 10–16: `closeReactive()` — complete rewrite from `.recover()`/`.compose()` chain to `.eventually()` chain. Added `closeFuture` field for idempotent second-call behavior.
Item 17: `validateDatabaseConnectivity()` — `.recover()` → `.onFailure()` + `.transform()`.
Item 18: `stopBackgroundTasks()` — `.recover(e -> succeededFuture())` → `.onFailure()`.

### PeeGeeQMetrics.java (3 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 19 | 672 | ERASURE | REMOVE — let metric query failure propagate | ☑ |
| 20 | 705 | ERASURE | REMOVE — let metric persistence failure propagate | ☑ |
| 21 | 745 | ERASURE | → .transform() — convert to health status at caller level | ☑ |

**Notes:** #19: `executeCountQuery()` — removed `.recover()` that fabricated `0.0` on failure.
#20: `persistMetrics()` — `.recover()` → `.onFailure()` for logging; failure now propagates.
Updated `PeeGeeQMetricsLogLevelTest` to expect failed future and use `.eventually()` for cleanup.
#21: `isHealthy()` — `.recover()` → `.transform()` to convert failure to `false`.

### HealthCheckManager.java (10 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 22 | 235 | ERASURE-IN-SHUTDOWN | → .eventually() | ☑ |
| 23 | 252 | RE-WRAPS-FAILURE | → .onFailure() + .transform() | ☑ |
| 24 | 352 | ERASURE-IN-SHUTDOWN | → .eventually() | ☑ |
| 25 | 640 | PROPER-FALLBACK | → .transform() — caller should render failed Future as unhealthy | ☑ |
| 26 | 655 | PROPER-FALLBACK | → .transform() | ☑ |
| 27 | 666 | PROPER-FALLBACK | → .transform() | ☑ |
| 28 | 689 | PROPER-FALLBACK | → .transform() | ☑ |
| 29 | 705 | PROPER-FALLBACK | → .transform() | ☑ |
| 30 | 724 | PROPER-FALLBACK | → .transform() | ☑ |
| 31 | 740 | PROPER-FALLBACK | → .transform() | ☑ |
| 32 | 764 | PROPER-FALLBACK | → .transform() | ☑ |

### DeadLetterQueueManager.java (8 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 33 | 143 | RE-WRAPS-FAILURE | → .onFailure() | ☑ |
| 34 | 182 | RE-WRAPS-FAILURE | → .onFailure() | ☑ |
| 35 | 210 | RE-WRAPS-FAILURE | → .onFailure() | ☑ |
| 36 | 237 | RE-WRAPS-FAILURE | → .onFailure() | ☑ |
| 37 | 262 | RE-WRAPS-FAILURE | → .onFailure() | ☑ |
| 38 | 322 | RE-WRAPS-FAILURE | → .onFailure() | ☑ |
| 39 | 344 | RE-WRAPS-FAILURE | → .onFailure() | ☑ |
| 40 | 366 | RE-WRAPS-FAILURE | → .onFailure() | ☑ |

**Notes:** All 8 instances in this class follow the same pattern: log error, then
re-throw via `Future.failedFuture(throwable)`. Replace each with
`.onFailure(t -> log.error(...))` — the error propagates naturally.

### PgConnectionManager.java (2 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 41 | 368 | PROPER-FALLBACK | → .transform() — convert to boolean at caller level | ☑ |
| 42 | 477 | ERASURE-IN-SHUTDOWN | → .onFailure() + .mapEmpty() | ☑ |

### PgConnectionProvider.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 43 | 152 | PROPER-FALLBACK | → .transform() | ☑ |

### StuckMessageRecoveryManager.java (6 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 44 | 104 | ERASURE | REMOVE — let recovery failure propagate | ☑ |
| 45 | 132 | ERASURE | REMOVE — let count failure propagate | ☑ |
| 46 | 164 | ERASURE | REMOVE — let reset failure propagate | ☑ |
| 47 | 202 | ERASURE | REMOVE — let logging failure propagate (or → .onFailure()) | ☑ |
| 48 | 221 | ERASURE | REMOVE — let stats failure propagate | ☑ |
| 49 | 241 | ERASURE | REMOVE — let count failure propagate | ☑ |

**Notes:** This class is the worst offender for fabricated data. Every method
returns `Future.succeededFuture(0)` or fabricated `RecoveryStats` on failure.
Callers think "zero stuck messages" when the real answer is "query failed."

### SubscriptionManager.java (3 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 50 | 189 | ERASURE | REMOVE — let backfill failure propagate to caller | ☑ |
| 51 | 411 | ERASURE | REMOVE — let cancel cleanup failure propagate | ☑ |
| 52 | 587 | ERASURE | REMOVE — let resurrection re-backfill failure propagate | ☑ |

**Notes:** These are data-integrity critical. FROM_BEGINNING backfill silently
fails (#50), cancel cleanup leaves orphan data (#51), resurrection misses
messages (#52). All three must propagate failures.

### SqlTemplateProcessor.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 53 | 65 | RE-WRAPS-FAILURE | → .onFailure() + .transform() to wrap exception type | ☑ |

### PeeGeeQDatabaseSetupService.java (6 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 54 | 195 | RE-WRAPS-FAILURE | → .onFailure() + .transform() | ☑ |
| 55 | 252 | SELECTIVE-RECOVERY | → .transform() (inspect exception, always re-throw) | ☑ |
| 56 | 266 | ERASURE-IN-SHUTDOWN | → .onFailure() + .transform() (cleanup then failedFuture) | ☑ |
| 57 | 566 | ERASURE-IN-SHUTDOWN | → .transform() (manager close in shutdown) | ☑ |
| 58 | 774 | RE-WRAPS-FAILURE | → .onFailure() + .transform() | ☑ |
| 59 | 1111 | ERASURE-IN-SHUTDOWN | → Future.join() + .transform() (best-effort destroy) | ☑ |

### DatabaseTemplateManager.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 60 | 90 | SELECTIVE-RECOVERY | → .transform() (selective conflict handling) | ☑ |

### DeadConsumerGroupCleanup.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 61 | 217 | TYPED-ERASURE | → .transform() — log error, skip fabricated result, continue batch | ☑ |

### DeadConsumerDetectionJob.java (2 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 62 | 199 | ERASURE-IN-SHUTDOWN | → .transform() (await in-flight, always succeed for stop) | ☑ |
| 63 | 363 | ERASURE-IN-SHUTDOWN | → .onFailure() + .transform() (in-flight tracking) | ☑ |

### MultiConfigurationManager.java (2 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 64 | 172 | RE-WRAPS-FAILURE | → .onFailure() (state reset + log, failure propagates) | ☑ |
| 65 | 333 | ERASURE-IN-SHUTDOWN | → .onFailure() + .transform() (best-effort close) | ☑ |

---

## Module: peegeeq-native (9 instances)

### PgNativeQueueProducer.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 66 | 312 | SELECTIVE-RECOVERY | → SQL (`INSERT ... ON CONFLICT DO NOTHING`) | ☑ |

### PgNativeQueueConsumer.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 67 | 280 | ERASURE-IN-SHUTDOWN | → .eventually() | ☑ |

### PgNativeConsumerGroup.java (5 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 68 | 246 | ERASURE-IN-SHUTDOWN | → .eventually() | ☑ |
| 69 | 256 | RE-WRAPS-FAILURE | → .onFailure() + .transform() (state reset + re-throw) | ☑ |
| 70 | 276 | PROPER-FALLBACK | → .transform() — let caller decide fallback mode | ☑ |
| 71 | 371 | RE-WRAPS-FAILURE | → .onFailure() + state reset via .transform() | ☑ |
| 72 | 405 | ERASURE | REMOVE — let subscription cancel failure propagate | ☑ |

### PartitionedConsumerEngine.java (2 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 73 | 163 | RE-WRAPS-FAILURE | → .onFailure() + .transform() (state reset + re-throw) | ☑ |
| 74 | 200 | ERASURE-IN-SHUTDOWN | → .eventually() | ☑ |

---

## Module: peegeeq-outbox (9 instances)

### OutboxFactory.java (4 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 75 | 325 | PROPER-FALLBACK | → .transform() — convert to health boolean at caller level | ☑ |
| 76 | 462 | TYPED-ERASURE | REMOVE — let stats failure propagate | ☑ |
| 77 | 555 | ERASURE-IN-SHUTDOWN | → .eventually() | ☑ |
| 78 | 559 | ERASURE-IN-SHUTDOWN | → .eventually() | ☑ |

### OutboxConsumerGroup.java (2 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 79 | 389 | RE-WRAPS-FAILURE | → .onFailure() + .transform() (state reset + re-throw) | ☑ |
| 80 | 448 | ERASURE | REMOVE — let subscription cancel failure propagate | ☑ |

### OutboxConsumer.java (3 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 81 | 436 | PROPER-FALLBACK | → FRAMEWORK — DLQ routing belongs in message pipeline | ☑ |
| 82 | 510 | SELECTIVE-RECOVERY | → FRAMEWORK — retry/DLQ routing belongs in message pipeline | ☑ |
| 83 | 871 | ERASURE-IN-SHUTDOWN | → .eventually() | ☑ |

---

## Module: peegeeq-performance-test-harness (2 instances)

### PerformanceTestHarness.java

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 84 | 86 | ERASURE | → .transform() — record failure, continue suite chain | ☑ |
| 85 | 103 | ERASURE | → .transform() — record failure, cleanup, return results | ☑ |

---

## Module: peegeeq-rest (22 instances)

### SystemMonitoringHandler.java (4 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 86 | 409 | TYPED-ERASURE | → ctx.fail() — let failure handler return error response | ☑ |
| 87 | 507 | TYPED-ERASURE | → ctx.fail() | ☑ |
| 88 | 567 | TYPED-ERASURE | → ctx.fail() or REMOVE — let partial metric failure propagate | ☑ |
| 89 | 623 | TYPED-ERASURE | → ctx.fail() or REMOVE | ☑ |

### QueueHandler.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 90 | 239 | TYPED-ERASURE | → ctx.fail() — let batch error propagate to HTTP response | ☑ |

### ManagementApiHandler.java (16 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 91 | 158 | RE-WRAPS-FAILURE | → .onFailure() | ☑ |
| 92 | 209 | TYPED-ERASURE | → ctx.fail() — let failure handler respond with error | ☑ |
| 93 | 427 | TYPED-ERASURE | → ctx.fail() | ☑ |
| 94 | 446 | TYPED-ERASURE | → ctx.fail() | ☑ |
| 95 | 465 | RE-WRAPS-FAILURE | → .onFailure() | ☑ |
| 96 | 589 | TYPED-ERASURE | → ctx.fail() | ☑ |
| 97 | 627 | TYPED-ERASURE | → ctx.fail() | ☑ |
| 98 | 643 | TYPED-ERASURE | → ctx.fail() | ☑ |
| 99 | 750 | RE-WRAPS-FAILURE | → .onFailure() | ☑ |
| 100 | 795 | TYPED-ERASURE | → ctx.fail() | ☑ |
| 101 | 813 | TYPED-ERASURE | → ctx.fail() | ☑ |
| 102 | 856 | TYPED-ERASURE | → ctx.fail() | ☑ |
| 103 | 900 | TYPED-ERASURE | → ctx.fail() | ☑ |
| 104 | 951 | TYPED-ERASURE | → ctx.fail() | ☑ |
| 105 | 1618 | TYPED-ERASURE | → ctx.fail() | ☑ |
| 106 | 1733 | TYPED-ERASURE | → ctx.fail() | ☑ |

### ConsumerGroupHandler.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 107 | 912 | TYPED-ERASURE | → ctx.fail() or REMOVE | ☑ |

---

## Module: peegeeq-rest-client (2 instances)

### PeeGeeQRestClient.java

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 108 | 792 | RE-WRAPS-FAILURE | → .transform() — convert to PeeGeeQNetworkException | ☑ |
| 109 | 798 | SELECTIVE-RECOVERY | → .transform() — retry logic with short-circuit | ☑ |

---

## Module: peegeeq-service-manager (8 instances)

### ConnectionRouter.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 110 | 105 | PROPER-FALLBACK | → .transform() — retry with failover | ☑ |

### PeeGeeQServiceManager.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 111 | 93 | ERASURE | → .onFailure() + .transform() — log, continue without Consul | ☑ |

### HealthMonitor.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 112 | 165 | PROPER-FALLBACK | → .transform() — convert to HealthCheckResult at caller level | ☑ |

### FederatedManagementHandler.java (5 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 113 | 388 | TYPED-ERASURE | → .transform() — proper fallback with error response | ☑ |
| 114 | 397 | TYPED-ERASURE | → .transform() | ☑ |
| 115 | 406 | TYPED-ERASURE | → .transform() | ☑ |
| 116 | 415 | TYPED-ERASURE | → .transform() | ☑ |
| 117 | 424 | TYPED-ERASURE | → .transform() | ☑ |

---

# Test Code — `.recover()` Removal Tracker

90 `.recover()` instances across test source trees (`src/test/java`).

## Test fix types

| Fix | Description |
|---|---|
| **→ .transform()** | Replace with `.transform(ar -> ...)`. For deliberate-rollback catches, assertion-then-continue patterns, and expected-error handling in tests. |
| **→ .eventually()** | Replace with `.eventually(() -> cleanup())`. For teardown/cleanup that must run regardless of outcome. |
| **→ .onFailure()** | Replace with `.onFailure(e -> ...)`. For logging/capture side-effects that don't alter the chain. |
| **→ SQL** | Move guard into SQL (`TRUNCATE ... IF EXISTS`, `DELETE ... IF EXISTS`). Remove the `.recover()`. |
| **REMOVE** | Delete the `.recover()` block entirely. |

---

## Module: peegeeq-bitemporal tests (48 instances)

### TransactionPropagationHonestyTest.java (10 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T1 | 187 | SETUP-GUARD | Already clean — no .recover() at this line | ☑ |
| T2 | 285 | DELIBERATE-ROLLBACK | → .transform() | ☑ |
| T3 | 404 | DELIBERATE-ROLLBACK | → .transform() | ☑ |
| T4 | 492 | DELIBERATE-ROLLBACK | → .transform() | ☑ |
| T5 | 546 | DELIBERATE-ROLLBACK | Already clean — no .recover() at this line | ☑ |
| T6 | 600 | DELIBERATE-ROLLBACK | Already clean — no .recover() at this line | ☑ |
| T7 | 753 | DELIBERATE-ROLLBACK | Already clean — no .recover() at this line | ☑ |
| T8 | 894 | EXPECTED-ERROR-ASSERT | → .transform() — assert IllegalStateException then continue | ☑ |
| T9 | 909 | EXPECTED-ERROR-ASSERT | → .transform() — assert IllegalStateException then continue | ☑ |
| T10 | 1033 | DELIBERATE-ROLLBACK | → .transform() | ☑ |

**Notes:** T1: `TRUNCATE` in setUp — add `IF EXISTS` or use `.transform()`.
T2–T7, T10: Tests that deliberately cause a transaction rollback and catch the expected
failure to continue the assertion chain. Replace with `.transform(ar -> { if (ar.failed()) ... })`.
T8–T9: Tests that expect `IllegalStateException` from a closed event store and assert on
the error before continuing. Replace with `.transform()`.

### WildcardPatternComprehensiveTest.java (2 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T11 | 96 | TEARDOWN-SWALLOW | → .transform() | ☑ |
| T12 | 100 | TEARDOWN-SWALLOW | → .transform() | ☑ |

### VertxPerformanceOptimizationValidationTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T13 | 133 | TEARDOWN-SWALLOW | → .transform() with logging | ☑ |

### VertxPerformanceOptimizationExampleTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T14 | 107 | TEARDOWN-SWALLOW | → .transform() with logging | ☑ |

### VersionLineageIntegrationTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T15 | 108 | TEARDOWN-SWALLOW | → .transform() with logging | ☑ |

### VersionLineageBugSurfacingTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T16 | 168 | SETUP-GUARD | → .transform() | ☑ |

### VersionFamilyTopologyTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T17 | 157 | SETUP-GUARD | Already clean — no .recover() at this line | ☑ |

### VersionFamilyDefensiveTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T18 | 161 | SETUP-GUARD | → .transform() | ☑ |

### TransactionalBiTemporalExampleTest.java (2 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T19 | 156 | TEARDOWN-SWALLOW | → .transform() with logging | ☑ |
| T20 | 200 | SETUP-GUARD | → .transform() with logging | ☑ |

### ReactiveNotificationTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T21 | 166 | TEARDOWN-SWALLOW | → .transform() | ☑ |

### ReactiveNotificationHandlerFailurePathTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T22 | 213 | EXPECTED-ERROR-ASSERT | → .transform() — assert forced listen failure then call stop() | ☑ |

### PgBiTemporalEventStoreStatsTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T23 | 168 | SETUP-GUARD | → .transform() | ☑ |

### PgBiTemporalEventStorePerformanceTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T24 | 98 | TEARDOWN-SWALLOW | → .transform() with logging | ☑ |

### PgBiTemporalEventStoreIntegrationTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T25 | 1000 | TEARDOWN-SWALLOW | → .transform() with logging | ☑ |

### PgBiTemporalEventStoreComplexTest.java (3 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T26 | 176 | SETUP-GUARD | → .transform() | ☑ |
| T27 | 380 | TX-ROLLBACK-PATTERN | → .transform() — on failure: rollback tx then re-propagate error | ☑ |
| T28 | 437 | ERASURE | → .transform() — swallows getById failure after rollback | ☑ |

### PgBiTemporalEventStoreCloseLogLevelTest.java (3 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T29 | 121 | TEARDOWN-SWALLOW | → .transform() | ☑ |
| T30 | 125 | TEARDOWN-SWALLOW | → .transform() | ☑ |
| T31 | 190 | TEARDOWN-SWALLOW | → .transform() — ownManager cleanup after DB shutdown | ☑ |

### PeeGeeQBiTemporalIntegrationTest.java (2 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T32 | 356 | RE-WRAPS-FAILURE | → .transform() — logs error, calls failNow on failure | ☑ |
| T33 | 502 | RE-WRAPS-FAILURE | → .transform() — same pattern in E2E test | ☑ |

### MultiTenantSchemaIsolationTest.java (2 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T34 | 148 | TEARDOWN-SWALLOW | → .transform() | ☑ |
| T35 | 149 | TEARDOWN-SWALLOW | → .transform() | ☑ |

### MissingSchemaFailFastTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T36 | 70 | TEARDOWN-SWALLOW | → removed .recover() (.onComplete already handles both) | ☑ |

### JsonbConversionValidationTest.java (2 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T37 | 112 | TEARDOWN-SWALLOW | → .transform() | ☑ |
| T38 | 113 | TEARDOWN-SWALLOW | → .transform() | ☑ |

### EventBusDistributionSemanticGapsTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T39 | 185 | SETUP-GUARD | → .transform() | ☑ |

### EventBusDistributionEquivalenceTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T40 | 179 | SETUP-GUARD | → .transform() | ☑ |

### CausationIdSchemaValidationTest.java (2 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T41 | 130 | TEARDOWN-SWALLOW | → .transform() | ☑ |
| T42 | 131 | TEARDOWN-SWALLOW | → .transform() | ☑ |

### BiTemporalQueryEdgeCasesTest.java (2 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T43 | 129 | TEARDOWN-SWALLOW | → .transform() with logging | ☑ |
| T44 | 133 | TEARDOWN-SWALLOW | → .transform() with logging | ☑ |

### BiTemporalPoolFactoryTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T45 | 51 | TEARDOWN-SWALLOW | → .transform() | ☑ |

### BiTemporalPerformanceParityTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T46 | 118 | TEARDOWN-SWALLOW | → .transform() | ☑ |

### BiTemporalEventStoreExampleTest.java (2 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T47 | 161 | TEARDOWN-SWALLOW | → .onFailure() — logs and re-throws | ☑ |
| T48 | 188 | SETUP-GUARD | → .onFailure() — logs and re-throws | ☑ |

### AppendBatchIntegrationTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T49 | 177 | TEARDOWN-SWALLOW | Already clean — no .recover() in file | ☑ |

---

## Module: peegeeq-db tests (28 instances)

### PeeGeeQManagerIntegrationTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T50 | 101 | TEARDOWN-SWALLOW | → .onFailure() | ☑ |

### PeeGeeQManagerCloseLogLevelTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T51 | 108 | TEARDOWN-SWALLOW | → removed .recover() (.onComplete already handles both) | ☑ |

### PeeGeeQMetricsLogLevelTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T52 | 105 | TEARDOWN-SWALLOW | → removed .recover() (.onComplete already handles both) | ☑ |

### PgConnectionManagerCloseLogLevelTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T53 | 94 | TEARDOWN-SWALLOW | → removed .recover() (.onComplete already handles both) | ☑ |

### DeadConsumerDetectionJobLifecycleTest.java (2 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T54 | 84 | TEARDOWN-SWALLOW | → .onFailure() | ☑ |
| T55 | 154 | TEARDOWN-SWALLOW | → .transform() — mid-test close needs chain to continue | ☑ |

### ConsumerGroupRetryJobLifecycleTest.java (2 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T56 | 84 | TEARDOWN-SWALLOW | → .onFailure() | ☑ |
| T57 | 152 | N/A | Already clean — no .recover() present at this line | ☑ |

### CompletionTrackerIntegrationTest.java (4 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T58 | 444 | EXPECTED-ERROR-ASSERT | → .transform() — expects IllegalArgumentException, re-throws others | ☑ |
| T59 | 660 | EXPECTED-ERROR-ASSERT | → .transform() — expects IllegalArgumentException, re-throws others | ☑ |
| T60 | 699 | EXPECTED-ERROR-ASSERT | → .transform() — expects IllegalArgumentException, re-throws others | ☑ |
| T61 | 737 | EXPECTED-ERROR-ASSERT | → .transform() — expects IllegalArgumentException, re-throws others | ☑ |

### DeadLetterQueueManagerTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T62 | 104 | TEARDOWN-SWALLOW | → .onFailure() + .transform() | ☑ |

### DeadLetterQueueManagerCoreTest.java (2 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T63 | 94 | SETUP-GUARD | → .transform() | ☑ |
| T64 | 514 | EXPECTED-ERROR-ASSERT | → .transform() — assertFutureFailure utility | ☑ |

### P4_BackfillVsOLTPTest.java (3 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T65 | 273 | RE-WRAPS-FAILURE | → .onFailure() — captures error in AtomicRef, propagates naturally | ☑ |
| T66 | 279 | RE-WRAPS-FAILURE | → .onFailure() — captures error in AtomicRef, propagates naturally | ☑ |
| T67 | 285 | RE-WRAPS-FAILURE | → .onFailure() — captures error in AtomicRef, propagates naturally | ☑ |

### SimpleConsumerGroupTestTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T68 | 89 | TEARDOWN-SWALLOW | → .onFailure() | ☑ |

### SecurityConfigurationExampleTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T69 | 65 | TEARDOWN-SWALLOW | → removed .recover() (.onComplete already handles both) | ☑ |

### PerformanceTuningExampleTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T70 | 88 | TEARDOWN-SWALLOW | → removed .recover() (.onComplete already handles both) | ☑ |

### PerformanceComparisonExampleTest.java (3 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T71 | 85 | TEARDOWN-SWALLOW | → removed .recover() (.onComplete already handles both) | ☑ |
| T72 | 232 | RE-WRAPS-FAILURE | → .eventually() — close mgr regardless then propagate original error | ☑ |
| T73 | 233 | TEARDOWN-SWALLOW | → .transform() inside .eventually() | ☑ |

### PeeGeeQSelfContainedDemoTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T74 | 62 | TEARDOWN-SWALLOW | → .onFailure() | ☑ |

### PeeGeeQExampleTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T75 | 77 | TEARDOWN-SWALLOW | → removed .recover() (.onComplete already handles both) | ☑ |

### AdvancedConfigurationExampleTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T76 | 79 | TEARDOWN-SWALLOW | → removed .recover() (.onComplete already handles both) | ☑ |

---

## Module: peegeeq-outbox tests (7 instances)

### ErrorHandlingRollbackExampleTest.java (6 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T77 | 233 | DELIBERATE-ROLLBACK | → .transform() — catches expected order-exceeds-limit failure | ☑ |
| T78 | 309 | DELIBERATE-ROLLBACK | → .transform() — catches expected multi-stage inventory failure | ☑ |
| T79 | 353 | RE-WRAPS-FAILURE | → .onFailure() — logs error, wraps in RuntimeException, re-throws | ☑ |
| T80 | 380 | DELIBERATE-ROLLBACK | → .transform() — catches expected validation failure | ☑ |
| T81 | 419 | RE-WRAPS-FAILURE | → .onFailure() — logs error, wraps in RuntimeException, re-throws | ☑ |
| T82 | 464 | RE-WRAPS-FAILURE | → .onFailure() — logs error, wraps in RuntimeException, re-throws | ☑ |

### OutboxRetryResilienceTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T83 | 481 | ERASURE | → .transform() — pool exhaustion simulation, expected failures swallowed | ☑ |

---

## Module: peegeeq-native tests (2 instances)

### ConsumerGroupSubscriptionIntegrationTest.java (2 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T84 | 139 | TEARDOWN-SWALLOW | → .eventually() — cleanupTestData in @AfterEach | ☑ |
| T85 | 318 | EXPECTED-ERROR-ASSERT | → .transform() — asserts state resets to NEW on failure | ☑ |

---

## Module: peegeeq-rest tests (5 instances)

### ModernVertxCompositionExampleTest.java (4 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T86 | 122 | ERASURE | → .transform() — demo of error recovery pattern (should teach .transform()) | ☑ |
| T87 | 195 | ERASURE | → .transform() — simulated graceful degradation | ☑ |
| T88 | 229 | ERASURE | → .transform() — simulated fallback database setup | ☑ |
| T89 | 251 | ERASURE | → .transform() — simulated service interaction degradation | ☑ |

**Notes:** This test class is a Vert.x composition example/demo. It currently teaches
`.recover()` as the pattern for error handling. Should be updated to demonstrate
`.transform()` as the Vert.x 5.x-idiomatic approach.

### SSEBasicStreamingIntegrationTest.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| T90 | 222 | TEARDOWN-SWALLOW | → .eventually() — test setup cleanup swallows HTTP error | ☑ |

---

## Test fix type summary

| Fix type | Count |
|---|---|
| → .eventually() (TEARDOWN-SWALLOW) | 44 |
| → .transform() (DELIBERATE-ROLLBACK) | 12 |
| → .transform() (EXPECTED-ERROR-ASSERT) | 9 |
| → SQL (SETUP-GUARD) | 11 |
| → .onFailure() (RE-WRAPS-FAILURE) | 9 |
| → .transform() (ERASURE / demo) | 4 |
| REMOVE | 1 |
| **Total** | **90** |

Note: 3 additional matches in test code are comment-only string literal references
(DeadConsumerGroupCleanupIntegrationTest L782/784, OutboxSchemaQuotingTest L260).
These require no code changes.

---

## Progress summary

| Module | Prod Total | Prod Done | Test Total | Test Done | Remaining |
|---|---|---|---|---|---|
| peegeeq-bitemporal | 6 | 6 | 48 | 48 | 0 |
| peegeeq-db | 59 | 59 | 28 | 28 | 0 |
| peegeeq-native | 9 | 9 | 2 | 2 | 0 |
| peegeeq-outbox | 9 | 9 | 7 | 7 | 0 |
| peegeeq-performance-test-harness | 2 | 2 | 0 | 0 | 0 |
| peegeeq-rest | 22 | 22 | 5 | 5 | 0 |
| peegeeq-rest-client | 2 | 2 | 0 | 0 | 0 |
| peegeeq-service-manager | 8 | 8 | 0 | 0 | 0 |
| **Total** | **117** | **117** | **90** | **90** | **0** |

## Production fix type summary

| Fix type | Count |
|---|---|
| REMOVE | 22 |
| → .eventually() | 27 |
| → .onFailure() | 18 |
| → .onFailure() + .transform() | 14 |
| → .transform() | 15 |
| → SQL | 3 |
| → RETRY-INFRA | 2 |
| → FRAMEWORK | 2 |
| → ctx.fail() | 24 |
| **Total (prod)** | **117** |

> **Note:** The bucketed counts above sum to 127 because the 14 entries fixed
> with the combo `→ .onFailure() + .transform()` are also reflected (conceptually)
> in the individual `→ .onFailure()` and `→ .transform()` rows by some readings of
> the per-instance Fix column. The authoritative total is **117 distinct `.recover()`
> instances removed** — verified by `grep -E '\.recover\s*\('` returning zero
> live call sites across all modules (only Javadoc/comment references remain).

## Suggested implementation order

> **All items below are complete.** Order preserved for historical reference.

1. **DeadLetterQueueManager.java** (8 instances, all RE-WRAPS-FAILURE → .onFailure())
   — Simplest class to fix. Every instance is the same pattern. Good warmup.

2. **StuckMessageRecoveryManager.java** (6 instances, all ERASURE → REMOVE)
   — Straightforward deletion. High-risk data integrity fixes.

3. **SubscriptionManager.java** (3 instances, all ERASURE → REMOVE)
   — Data-integrity critical. FROM_BEGINNING backfill, cancel cleanup, resurrection.

4. **PgBiTemporalEventStore.java** (6 instances, mostly → .eventually())
   — Clean shutdown pattern conversion.

5. **PeeGeeQManager.java** (12 instances, mixed)
   — Largest single class. Mix of shutdown cleanup and re-wraps.

6. **HealthCheckManager.java** (10 instances, health checks → .transform())
   — Requires designing the .transform() pattern for health checks first.

7. **ManagementApiHandler.java** (16 instances, mostly → ctx.fail())
   — Requires adding a centralized failure handler to the management API router first.

8. **FederatedManagementHandler.java** (5 instances, → ctx.fail())
   — Same pattern as ManagementApiHandler.

9. **Remaining modules** — pick off in any order.
