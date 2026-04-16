# `.recover()` Removal — Implementation Tracker

Tracks remediation of all 117 `.recover()` instances across the PeeGeeQ codebase.
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
| 1 | 1286 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |
| 2 | 1298 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |
| 3 | 1310 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |
| 4 | 1816 | RE-WRAPS-FAILURE | → .onFailure() | ☐ |
| 5 | 2122 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |
| 6 | 2133 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |

**Notes:** Entries 1–3 and 5–6 use an `AtomicReference<Throwable>` pattern to capture
the first error and re-raise it at the end of the close chain. `.eventually()` preserves
the original success/failure without this manual bookkeeping.

---

## Module: peegeeq-db (59 instances)

### PeeGeeQManager.java (12 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 7 | 278 | RE-WRAPS-FAILURE | → .onFailure() for logging; keep the re-throw via .transform() | ☐ |
| 8 | 285 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |
| 9 | 325 | RE-WRAPS-FAILURE | → .onFailure() + .transform() to wrap | ☐ |
| 10 | 407 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |
| 11 | 410 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |
| 12 | 423 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |
| 13 | 437 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |
| 14 | 449 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |
| 15 | 474 | SELECTIVE-RECOVERY | → .eventually() (this is shutdown cleanup disguised as selective recovery) | ☐ |
| 16 | 490 | SELECTIVE-RECOVERY | → .eventually() for RejectedExecutionException; keep failedFuture for others | ☐ |
| 17 | 738 | RE-WRAPS-FAILURE | → .onFailure() + .transform() to wrap | ☐ |
| 18 | 935 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |

### PeeGeeQMetrics.java (3 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 19 | 672 | ERASURE | REMOVE — let metric query failure propagate | ☐ |
| 20 | 705 | ERASURE | REMOVE — let metric persistence failure propagate | ☐ |
| 21 | 745 | ERASURE | → .transform() — convert to health status at caller level | ☐ |

### HealthCheckManager.java (10 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 22 | 235 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |
| 23 | 252 | RE-WRAPS-FAILURE | → .onFailure() + .transform() | ☐ |
| 24 | 352 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |
| 25 | 640 | PROPER-FALLBACK | → .transform() — caller should render failed Future as unhealthy | ☐ |
| 26 | 655 | PROPER-FALLBACK | → .transform() | ☐ |
| 27 | 666 | PROPER-FALLBACK | → .transform() | ☐ |
| 28 | 689 | PROPER-FALLBACK | → .transform() | ☐ |
| 29 | 705 | PROPER-FALLBACK | → .transform() | ☐ |
| 30 | 724 | PROPER-FALLBACK | → .transform() | ☐ |
| 31 | 740 | PROPER-FALLBACK | → .transform() | ☐ |
| 32 | 764 | PROPER-FALLBACK | → .transform() | ☐ |

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
| 41 | 368 | PROPER-FALLBACK | → .transform() — convert to boolean at caller level | ☐ |
| 42 | 477 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |

### PgConnectionProvider.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 43 | 152 | PROPER-FALLBACK | → .transform() | ☐ |

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
| 53 | 65 | RE-WRAPS-FAILURE | → .onFailure() + .transform() to wrap exception type | ☐ |

### PeeGeeQDatabaseSetupService.java (6 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 54 | 195 | RE-WRAPS-FAILURE | → .onFailure() + .transform() | ☐ |
| 55 | 252 | SELECTIVE-RECOVERY | → SQL (`IF NOT EXISTS` / `ON CONFLICT`) | ☐ |
| 56 | 266 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |
| 57 | 566 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |
| 58 | 774 | RE-WRAPS-FAILURE | → .onFailure() + .transform() | ☐ |
| 59 | 1111 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |

### DatabaseTemplateManager.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 60 | 90 | SELECTIVE-RECOVERY | → SQL (`CREATE DATABASE IF NOT EXISTS`) | ☐ |

### DeadConsumerGroupCleanup.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 61 | 217 | TYPED-ERASURE | REMOVE — let individual cleanup failure propagate into batch results | ☐ |

### DeadConsumerDetectionJob.java (2 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 62 | 199 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |
| 63 | 363 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |

### MultiConfigurationManager.java (2 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 64 | 172 | RE-WRAPS-FAILURE | → .onFailure() + .transform() | ☐ |
| 65 | 333 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |

---

## Module: peegeeq-native (9 instances)

### PgNativeQueueProducer.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 66 | 312 | SELECTIVE-RECOVERY | → SQL (`INSERT ... ON CONFLICT DO NOTHING`) | ☐ |

### PgNativeQueueConsumer.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 67 | 280 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |

### PgNativeConsumerGroup.java (5 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 68 | 246 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |
| 69 | 256 | RE-WRAPS-FAILURE | → .onFailure() + .transform() (state reset + re-throw) | ☐ |
| 70 | 276 | PROPER-FALLBACK | → .transform() — let caller decide fallback mode | ☐ |
| 71 | 371 | RE-WRAPS-FAILURE | → .onFailure() + state reset via .transform() | ☐ |
| 72 | 405 | ERASURE | REMOVE — let subscription cancel failure propagate | ☐ |

### PartitionedConsumerEngine.java (2 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 73 | 163 | RE-WRAPS-FAILURE | → .onFailure() + .transform() (state reset + re-throw) | ☐ |
| 74 | 200 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |

---

## Module: peegeeq-outbox (9 instances)

### OutboxFactory.java (4 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 75 | 325 | PROPER-FALLBACK | → .transform() — convert to health boolean at caller level | ☐ |
| 76 | 462 | TYPED-ERASURE | REMOVE — let stats failure propagate | ☐ |
| 77 | 555 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |
| 78 | 559 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |

### OutboxConsumerGroup.java (2 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 79 | 389 | RE-WRAPS-FAILURE | → .onFailure() + .transform() (state reset + re-throw) | ☐ |
| 80 | 448 | ERASURE | REMOVE — let subscription cancel failure propagate | ☐ |

### OutboxConsumer.java (3 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 81 | 436 | PROPER-FALLBACK | → FRAMEWORK — DLQ routing belongs in message pipeline | ☐ |
| 82 | 510 | SELECTIVE-RECOVERY | → FRAMEWORK — retry/DLQ routing belongs in message pipeline | ☐ |
| 83 | 871 | ERASURE-IN-SHUTDOWN | → .eventually() | ☐ |

---

## Module: peegeeq-performance-test-harness (2 instances)

### PerformanceTestHarness.java

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 84 | 86 | ERASURE | REMOVE — let test failure propagate to harness caller | ☐ |
| 85 | 103 | ERASURE | REMOVE — let harness failure propagate | ☐ |

---

## Module: peegeeq-rest (22 instances)

### SystemMonitoringHandler.java (4 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 86 | 409 | TYPED-ERASURE | → ctx.fail() — let failure handler return error response | ☐ |
| 87 | 507 | TYPED-ERASURE | → ctx.fail() | ☐ |
| 88 | 567 | TYPED-ERASURE | → ctx.fail() or REMOVE — let partial metric failure propagate | ☐ |
| 89 | 623 | TYPED-ERASURE | → ctx.fail() or REMOVE | ☐ |

### QueueHandler.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 90 | 239 | TYPED-ERASURE | → ctx.fail() — let batch error propagate to HTTP response | ☐ |

### ManagementApiHandler.java (16 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 91 | 158 | RE-WRAPS-FAILURE | → .onFailure() | ☐ |
| 92 | 209 | TYPED-ERASURE | → ctx.fail() — let failure handler respond with error | ☐ |
| 93 | 427 | TYPED-ERASURE | → ctx.fail() | ☐ |
| 94 | 446 | TYPED-ERASURE | → ctx.fail() | ☐ |
| 95 | 465 | RE-WRAPS-FAILURE | → .onFailure() | ☐ |
| 96 | 589 | TYPED-ERASURE | → ctx.fail() | ☐ |
| 97 | 627 | TYPED-ERASURE | → ctx.fail() | ☐ |
| 98 | 643 | TYPED-ERASURE | → ctx.fail() | ☐ |
| 99 | 750 | RE-WRAPS-FAILURE | → .onFailure() | ☐ |
| 100 | 795 | TYPED-ERASURE | → ctx.fail() | ☐ |
| 101 | 813 | TYPED-ERASURE | → ctx.fail() | ☐ |
| 102 | 856 | TYPED-ERASURE | → ctx.fail() | ☐ |
| 103 | 900 | TYPED-ERASURE | → ctx.fail() | ☐ |
| 104 | 951 | TYPED-ERASURE | → ctx.fail() | ☐ |
| 105 | 1618 | TYPED-ERASURE | → ctx.fail() | ☐ |
| 106 | 1733 | TYPED-ERASURE | → ctx.fail() | ☐ |

**Notes:** This class needs a centralized failure handler on the management API
router. All 13 TYPED-ERASURE instances can be replaced with `ctx.fail(err)` once
that failure handler exists.

### ConsumerGroupHandler.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 107 | 912 | TYPED-ERASURE | → ctx.fail() or REMOVE | ☐ |

---

## Module: peegeeq-rest-client (2 instances)

### PeeGeeQRestClient.java

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 108 | 792 | RE-WRAPS-FAILURE | → .onFailure() + .transform() — convert to PeeGeeQNetworkException | ☐ |
| 109 | 798 | SELECTIVE-RECOVERY | → RETRY-INFRA — move retry logic to middleware | ☐ |

---

## Module: peegeeq-service-manager (8 instances)

### ConnectionRouter.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 110 | 105 | PROPER-FALLBACK | → RETRY-INFRA — failover belongs in retry middleware | ☐ |

### PeeGeeQServiceManager.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 111 | 93 | ERASURE | REMOVE — let Consul registration failure propagate | ☐ |

### HealthMonitor.java (1 instance)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 112 | 165 | PROPER-FALLBACK | → .transform() — convert to HealthCheckResult at caller level | ☐ |

### FederatedManagementHandler.java (5 instances)

| # | Line | Classification | Fix | Status |
|---|---|---|---|---|
| 113 | 388 | TYPED-ERASURE | → ctx.fail() — return proper HTTP error status | ☐ |
| 114 | 397 | TYPED-ERASURE | → ctx.fail() | ☐ |
| 115 | 406 | TYPED-ERASURE | → ctx.fail() | ☐ |
| 116 | 415 | TYPED-ERASURE | → ctx.fail() | ☐ |
| 117 | 424 | TYPED-ERASURE | → ctx.fail() | ☐ |

---

## Progress summary

| Module | Total | Done | Remaining |
|---|---|---|---|
| peegeeq-bitemporal | 6 | 0 | 6 |
| peegeeq-db | 59 | 17 | 42 |
| peegeeq-native | 9 | 0 | 9 |
| peegeeq-outbox | 9 | 0 | 9 |
| peegeeq-performance-test-harness | 2 | 0 | 2 |
| peegeeq-rest | 22 | 0 | 22 |
| peegeeq-rest-client | 2 | 0 | 2 |
| peegeeq-service-manager | 8 | 0 | 8 |
| **Total** | **117** | **17** | **100** |

## Fix type summary

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

## Suggested implementation order

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
