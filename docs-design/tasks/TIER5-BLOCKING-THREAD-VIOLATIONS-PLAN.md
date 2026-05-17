# Tier 5 (Blocking Thread Delays) — Audit and Replacement Plan

**Date:** 2026-05-17
**Scope:** Tier 5 of `OnSuccessExceptionSwallowingGuardTest` (`scanSleep` — `Thread.sleep` / `LockSupport.parkNanos`).
**Out of scope:** Tier 4 / Tier 7 violations (`.await()`, `.toCompletionStage()`, etc.). Several files audited here also contain Tier 4/7 violations; those are flagged in notes but not addressed in this plan.

---

## Background

The Tier 5 guard runs a regex over every test source and flags `Thread.sleep(...)` and `LockSupport.parkNanos(...)` calls. The check is bypassed for files containing the literal string `@Tag("demonstration")` anywhere in the raw file content (including comments).

An earlier session bulk-applied `@Tag("demonstration")` to 16 test classes to make the guard pass. That was wrong: the tag must be reserved for files where the blocking call is genuinely justified, not used as a blanket exemption. This document is the per-violation audit and replacement plan that should have been done first.

## Standard for "legit demonstration" tag

The `@Tag("demonstration")` exemption is valid only when one of the following applies:

1. **Test subject IS the blocking pattern** (e.g. proving event-loop blocking, JDK `ExecutorService` shutdown semantics).
2. **Test subject IS time-based state transition** in a non-reactive component (e.g. `CircuitBreaker` timeout window).
3. **JVM-thread diagnostics post-Vert.x shutdown** — `vertx.close()` has returned, so `vertx.timer(...)` is no longer available.
4. **Inside `vertx.executeBlocking(...)`** — the call runs on a Vert.x worker thread, not the event loop. The guard's regex flags these falsely.

The tag is **invalid** when:

- The blocking call is a "simulate processing time" no-op with no semantic value.
- The blocking call is a spin-loop polling pattern (`while (!done && deadline) { sleep(short) }`).

---

## Audit results

### Files KEEPING `@Tag("demonstration")` (justified)

| File | Justification | Category |
|---|---|---|
| [VertxEventLoopBlockingJoinTest.java](../../peegeeq-db/src/test/java/dev/mars/peegeeq/db/performance/VertxEventLoopBlockingJoinTest.java) | Proves event-loop blocking — Thread.sleep IS the SUT | 1 |
| [CircuitBreakerRecoveryTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/CircuitBreakerRecoveryTest.java) | `parkNanos > timeout` validates CircuitBreaker state transition | 2 |
| [VertxAsyncTestPitfallsDemo.java](../../peegeeq-db/src/test/java/dev/mars/peegeeq/db/testpatterns/VertxAsyncTestPitfallsDemo.java) | Pedagogical anti-pattern demo (pre-existing tag) | 1 |
| [ResourceLeakDetectionTest.java](../../peegeeq-db/src/test/java/dev/mars/peegeeq/db/ResourceLeakDetectionTest.java) | All sleeps occur after `closeReactive()`; Vert.x runtime gone. Comment: *"Thread.sleep (the only option for JVM-level thread diagnostics without a reactive runtime)"* | 3 |
| [MemoryAndResourceLeakTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/MemoryAndResourceLeakTest.java) | All sleeps inside `vertx.executeBlocking(() -> { System.gc(); Thread.sleep(...); })`. Comment: *"sleeps run on Vert.x worker thread, not event loop"* | 4 |
| [ShutdownTest.java](../../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/patterns/ShutdownTest.java) | Sleeps inside `executor.submit(Runnable)` on a JDK `ExecutorService`; test subject is `shutdownExecutorGracefully(ExecutorService)` interrupt semantics | 1 |
| [SharedTestContainers.java](../../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/shared/SharedTestContainers.java) | Comment marker only — Testcontainers port-mapping retry infra | 1 |
| [SharedTestContainers.java](../../peegeeq-examples-spring/src/test/java/dev/mars/peegeeq/examples/shared/SharedTestContainers.java) | Same as above | 1 |

### Files that need `@Tag("demonstration")` RESTORED (initial revert was wrong)

| File | Justification |
|---|---|
| [OutboxMetricsTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxMetricsTest.java) | All 4 `parkNanos` calls (lines 157, 198, 258, 312) inside `vertx.executeBlocking(() -> { while(deadline) { ... parkNanos(100ms) } })`. Code comment: *"Poll on a worker thread so the event loop is never blocked."* Category 4. |
| [OutboxPerformanceTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxPerformanceTest.java) | `parkNanos(10ms)` at line 236 inside `vertx.executeBlocking(() -> { for { producer.send(...).await(); parkNanos(10ms); } })`. Category 4. **Note:** also contains `.await()` (Tier 4/7 violation, separate scope). |

### Files with violations that must be FIXED (no exemption)

| File:Line | Pattern | Replacement category |
|---|---|---|
| [AutomaticTransactionManagementExampleTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/examples/AutomaticTransactionManagementExampleTest.java#L347) | `parkNanos(1ms)` "Simulate processing time" in metrics-collection loop | A — delete |
| [JdbcIntegrationHybridExampleTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/examples/JdbcIntegrationHybridExampleTest.java#L284) | `parkNanos(1ms)` "Simulate JDBC processing time" | A — delete |
| [JdbcIntegrationHybridExampleTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/examples/JdbcIntegrationHybridExampleTest.java#L294) | `parkNanos(1ms)` "Simulate reactive processing time" | A — delete |
| [ParameterizedPerformanceDemoTest.java](../../peegeeq-test-support/src/test/java/dev/mars/peegeeq/test/demo/ParameterizedPerformanceDemoTest.java#L153) | `Thread.sleep(50 + random*100)` "Simulate variable work"; metrics map below is hand-rolled | A — delete |
| [ConsumerModePerformanceTestBaseTest.java](../../peegeeq-test-support/src/test/java/dev/mars/peegeeq/test/consumer/ConsumerModePerformanceTestBaseTest.java#L212) | `Thread.sleep(workTime)` inside `simulateWork()`; `generateRealisticMetrics()` is formula-based | A — delete |
| [DistributedSystemResilienceDemoTest.java](../../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/nativequeue/DistributedSystemResilienceDemoTest.java#L254) | `Thread.sleep(processingDelayMs)` inside `ResilientService.processRequest()` synthetic stub | B — caller-graph audit needed |
| [DistributedSystemResilienceDemoTest.java](../../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/nativequeue/DistributedSystemResilienceDemoTest.java#L326) | `Thread.sleep(delay)` inside `RetryHandler.executeWithRetry()` exponential backoff | B — caller-graph audit needed |
| [FilterErrorHandlingIntegrationTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/FilterErrorHandlingIntegrationTest.java#L154) | `while (count < 50 && deadline) { parkNanos(50ms) }` spin-loop polling | C — Promise + VertxTestContext |
| [MessageReliabilityTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/MessageReliabilityTest.java#L425) | `while (count < 5 && deadline) { parkNanos(50ms) }` spin-loop polling | C — Promise + VertxTestContext |
| [SystemPropertiesConfigurationExampleTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/examples/SystemPropertiesConfigurationExampleTest.java#L266) | `while (count < 5 && deadline) { parkNanos(50ms) }` spin-loop polling | C — Promise + VertxTestContext |
| [FilterRetryManagerTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/resilience/FilterRetryManagerTest.java#L500) | `parkNanos(20ms)` "Give it a moment to start, then close Vertx" | D — `vertx.timer().compose(close)` |

**Note on FilterErrorHandlingIntegrationTest:** This file is staying tagged for the line 268 timeout-elapse pattern (analogous to `CircuitBreakerRecoveryTest`). However the line 154 spin-loop is unambiguously banned and must still be fixed — otherwise the tag is silently exempting a real violation.

---

## Replacement plans by category

### Category A — Delete the line entirely (5 violations)

**Rationale.** These are synthetic "simulate work" delays in test methods that collect metrics for display purposes only. No `@Test` method asserts an elapsed-time bound that depends on these sleeps; the surrounding metrics are either hand-rolled formulas or unused.

**Action.** Delete the offending line and its `// Simulate ...` comment. No imports of `LockSupport` / `TimeUnit` may need pruning (use `get_errors` after).

**Files.**

1. `AutomaticTransactionManagementExampleTest.java:347`
2. `JdbcIntegrationHybridExampleTest.java:284`
3. `JdbcIntegrationHybridExampleTest.java:294`
4. `ParameterizedPerformanceDemoTest.java:153`
5. `ConsumerModePerformanceTestBaseTest.java:212`

**Risk.** Zero — these files do not depend on wall-clock timing for assertions.

### Category B — Caller-graph audit required (2 violations, 1 file)

**File.** `DistributedSystemResilienceDemoTest.java` lines 254, 326.

**Concern.** Both sleeps live inside test-fixture inner classes (`ResilientService` and `RetryHandler`) called from `@Test` methods that exercise circuit-breaker and retry semantics. If a test asserts:

- Circuit breaker opens **after** N failures within a time window, or
- Total retry duration is bounded, or
- Timeout exceptions fire because processing exceeds a configured deadline,

— then the sleep is load-bearing and cannot be deleted without breaking the assertion.

**Required pre-work.** Read each `@Test` method in the file and tabulate which assertions depend on `processingDelayMs` or `delay`. Only then choose between:

- Delete (if no timing assertion depends on it).
- Replace with `vertx.timer(delayMs).await()` — **banned in tests**.
- Replace with a chained reactive flow returning `Future<ServiceResponse>` — **changes the contract** of `processRequest` / `executeWithRetry` and ripples into every caller.

**Recommendation.** Defer this file. It is the most semantically risky of the set and warrants its own dedicated change.

### Category C — Replace spin-loop with handler-completed `Promise` (3 violations, 3 files)

**Pattern being replaced.**

```java
long deadline = System.currentTimeMillis() + 5_000;
while (processedCount.get() < N && System.currentTimeMillis() < deadline) {
    LockSupport.parkNanos(50_000_000L);
}
assertEquals(N, processedCount.get(), "...");
```

**Replacement pattern.**

```java
@Test
void someTest(VertxTestContext ctx) {
    Promise<Void> allProcessed = Promise.promise();
    consumer.subscribe(msg -> {
        // ...existing logic...
        if (processedCount.incrementAndGet() >= N) {
            allProcessed.tryComplete();
        }
        return Future.succeededFuture();
    });
    // ...send messages...
    allProcessed.future()
        .onSuccess(v -> ctx.verify(() -> {
            assertEquals(N, processedCount.get(), "...");
            ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
}
// JUnit @Timeout(seconds = 5) enforces the deadline, replacing the manual one.
```

**Files.**

1. `MessageReliabilityTest.java:425` — class is plain `@Tag(CORE)`, no `VertxTestContext` currently injected. Method signature changes required.
2. `SystemPropertiesConfigurationExampleTest.java:266` — Testcontainers integration test, no `VertxExtension`. Method signature changes required.
3. `FilterErrorHandlingIntegrationTest.java:154` — same situation; method signature changes required.

**Risk.** Medium. The method-signature change is invasive but mechanical. The completion semantics differ: the new code completes as soon as the threshold is hit (instead of polling every 50ms), which can only affect timing-sensitive downstream code that runs after the wait. Need to audit each test for code between the spin-loop end and `// Get final metrics` / assertions.

### Category D — Single-shot small delay before discrete event (1 violation)

**File.** `FilterRetryManagerTest.java:500`.

**Current code.**

```java
retryManager.executeWithRetry(message, filter, circuitBreaker);
// Give it a moment to start, then close Vertx while retries are in progress.
LockSupport.parkNanos(20_000_000L);  // 20 ms
vertx.close().await();
vertx = Vertx.vertx();
```

**Replacement.**

```java
@Test
void testSchedulerShutdownGracefully(VertxTestContext ctx) {
    // ...build message and filter...
    retryManager.executeWithRetry(message, filter, circuitBreaker);
    vertx.timer(20)
        .compose(v -> vertx.close())
        .onSuccess(v -> ctx.completeNow())
        .onFailure(ctx::failNow);
}
// vertx field re-init moves into a @BeforeEach or remains in tearDown unchanged.
```

**Risk.** Low — the test only checks for graceful completion (no thrown exception, no hang). `vertx.close().await()` is also a `.await()` Tier 4/7 violation; the rewrite removes it in passing.

---

## Execution order

1. **Restore tag** on `OutboxMetricsTest.java` and `OutboxPerformanceTest.java` (2 one-line edits).
2. **Category A deletions** (5 violations, 4 files — atomic, zero risk).
3. **Category D rewrite** (`FilterRetryManagerTest:500` — single method signature change).
4. **Category C rewrites** (3 files — invasive but mechanical).
5. **Defer Category B** (`DistributedSystemResilienceDemoTest`) until caller-graph audit is requested.
6. **Run guard** (user-side):

   ```powershell
   mvn test -pl :peegeeq-test-support -Pcore-tests -Dtest=OnSuccessExceptionSwallowingGuardTest#noBlockingThreadDelaysInTestSources 2>&1 | Tee-Object -FilePath logs\guard-tier5-20260517.txt
   ```

   Expected Tier 5 count: 2 (the deferred lines in `DistributedSystemResilienceDemoTest`) — or 0 if the user authorizes deletion in Category B as well.

---

## Open questions for the user

1. Confirm tag-restore on `OutboxMetricsTest` and `OutboxPerformanceTest`.
2. Confirm Category A deletions (5 violations).
3. Defer or address Category B (`DistributedSystemResilienceDemoTest`)?
4. Confirm Category C invasive method-signature changes (`VertxTestContext` injection in 3 files).
5. Confirm Category D rewrite (`FilterRetryManagerTest:500`).

---

## Out-of-scope notes (Tier 4 / Tier 7 violations found in passing)

These are documented for future work; **do not address them in this plan**.

- `ResourceLeakDetectionTest.java` — contains `testManager.closeReactive().await()`. Banned `.await()`.
- `MemoryAndResourceLeakTest.java` — multiple `.await()` calls on `Promise.future()` and `executeBlocking(...)`.
- `OutboxPerformanceTest.java` — `producer.send(...).await()` inside `executeBlocking`.
- `FilterRetryManagerTest.java` — `vertx.close().await()` (removed in Category D fix above).

These represent a separate banned-pattern class (reactive→blocking bridge) that requires its own authorized pass.
