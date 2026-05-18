# Guard Tiers 4, 5, 7 — Audit and Remediation Plan

**Date:** 2026-05-17  
**Last updated:** 2026-05-18  
**Scope:** All files flagged by `OnSuccessExceptionSwallowingGuardTest` tiers 4, 5, and 7.

---

## Current Build Status (as of 2026-05-18)

`mvn clean install` fails with **3 guard test failures** in `OnSuccessExceptionSwallowingGuardTest`:

| Tier | Guard test | Violation count | Primary modules |
|------|-----------|-----------------|----------------|
| T7 | `noDiscardedFuturesFromStopOrCloseInTestSources` | 66 (was 77; 11 fixed in OutboxConsumerGroupCoreTest 2026-05-18) | `peegeeq-outbox` |
| T4 | `noFutureAwaitInTestSources` | 100+ | `peegeeq-native`, `peegeeq-outbox` |
| T5 | `noBlockingThreadDelaysInTestSources` | ~11 | `peegeeq-outbox`, `peegeeq-examples`, `peegeeq-test-support` |

---

## Master Remediation Checklist

**This is the single source of truth for all file-level work.** Status is tracked here only — nowhere else in this document.

**Tier legend:** T4 = `.await()` violations · T5 = `Thread.sleep`/`parkNanos` · T7 = discarded `close()`/`stop()` future

**T5 action legend:** Tag-restore = add back `@Tag("demonstration")` · Cat-A = delete the line · Cat-B = DEFERRED (caller-graph audit needed) · Cat-C = replace spin-loop with Promise + VertxTestContext · Cat-D = replace with `vertx.timer().compose(...)`

### `peegeeq-outbox`

| Done | File | Tiers | T5 Action | Notes |
|------|------|-------|-----------|-------|
| [ ] | [OutboxMetricsTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxMetricsTest.java) | T5 | Tag-restore | 4 parkNanos (lines 157, 198, 258, 312) inside `executeBlocking` — justified (category 4) |
| [ ] | [OutboxPerformanceTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxPerformanceTest.java) | T4, T5 | Tag-restore | parkNanos L236 inside `executeBlocking` — justified; `.await()` fixed as part of T4 pass |
| [ ] | [AsyncRetryMechanismTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/AsyncRetryMechanismTest.java) | T4 | — | |
| [ ] | [FilterErrorHandlingIntegrationTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/FilterErrorHandlingIntegrationTest.java) | T5 | Cat-C | L154 spin-loop must be fixed; L268 tag-retain is justified (category 2) — tag stays |
| [ ] | [JsonbConversionValidationTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/JsonbConversionValidationTest.java) | T4, T7 | — | T7: L104 `connectionManager.close()` |
| [ ] | [MessageReliabilityTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/MessageReliabilityTest.java) | T5 | Cat-C | L425 spin-loop; no VertxTestContext currently injected — method signature change required |
| [ ] | [OutboxBlockingSafetyTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxBlockingSafetyTest.java) | T4 | — | |
| [ ] | [OutboxConfigurationIntegrationTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConfigurationIntegrationTest.java) | T4 | — | |
| [x] | [OutboxConsumerCoreTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerCoreTest.java) | T7 | — | T7 fixed (2026-05-18): `groupConsumer` local replaced by `this.consumer` reassignment; tearDown closes it. No `.await()` found — T4 tag was incorrect. |
| [ ] | [OutboxConsumerCoverageTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerCoverageTest.java) | T4 | — | |
| [ ] | [OutboxConsumerCrashRecoveryTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerCrashRecoveryTest.java) | T4, T7 | — | T7: L132 `connectionManager.close()` discarded |
| [ ] | [OutboxConsumerEdgeCasesCoverageTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerEdgeCasesCoverageTest.java) | T4 | — | |
| [ ] | [OutboxConsumerFailureHandlingTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerFailureHandlingTest.java) | T4, T7 | — | T7: L139, 419, 490 — `connectionManager`, `dlqManager`, `retryManager` |
| [ ] | [OutboxConsumerGroupClientIdPropagationTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupClientIdPropagationTest.java) | T7 | — | L162 `group.close()` discarded |
| [x] | [OutboxConsumerGroupCoreTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupCoreTest.java) | T7 | — | 11 discarded calls fixed (2026-05-18). See T7 note: `closeAsync()`/`stopGracefully()` required — `close()`/`stop()` return `void`. |
| [ ] | [OutboxConsumerGroupFaultToleranceTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupFaultToleranceTest.java) | T7 | — | L135, 136, 148 |
| [ ] | [OutboxConsumerGroupFilteredMessageStatusTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupFilteredMessageStatusTest.java) | T7 | — | L106, 107, 288, 303, 304 |
| [ ] | [OutboxConsumerGroupGracefulShutdownTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupGracefulShutdownTest.java) | T7 | — | L90, 118, 199 |
| [ ] | [OutboxConsumerGroupIntegrationTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupIntegrationTest.java) | T7 | — | L83, 84 |
| [ ] | [OutboxConsumerGroupReviewFixesTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupReviewFixesTest.java) | T7 | — | L95, 432, 492, 509, 582 |
| [ ] | [OutboxConsumerGroupSubscriptionEdgeCasesTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupSubscriptionEdgeCasesTest.java) | T7 | — | L176, 210, 298, 363, 449, 508, 536, 605, 731, 746, 747, 748, 763 (guard-verified 2026-05-18: 13 violations, not 7) |
| [ ] | [OutboxConsumerGroupSubscriptionTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupSubscriptionTest.java) | T7 | — | L156, 185, 226, 238, 256, 270, 309, 324, 335, 345, 375, 393, 418, 432, 445, 454, 481, 511, 545, 592 (20 violations) |
| [ ] | [OutboxConsumerGroupTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupTest.java) | T7 | — | L99 `consumerGroup.close()` |
| [ ] | [OutboxConsumerNullHandlerTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerNullHandlerTest.java) | T7 | — | L109 `connectionManager.close()` |
| [ ] | [OutboxRetryConcurrencyTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxRetryConcurrencyTest.java) | T7 | — | L202 `connectionManager.close()` |
| [ ] | [OutboxRetryResilienceTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxRetryResilienceTest.java) | T7 | — | L172 `connectionManager.close()` |
| [ ] | [ReactiveOutboxProducerTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/ReactiveOutboxProducerTest.java) | T4, T7 | — | T7: L114 `connectionManager.close()` (guard-verified 2026-05-18; T7 missing from original audit) |
| [ ] | [deadletter/DeadLetterQueueManagerCoverageTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/deadletter/DeadLetterQueueManagerCoverageTest.java) | T7 | — | L58, 82, 130 — `manager.close()`, `disabledManager.close()` |
| [ ] | [resilience/FilterRetryManagerTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/resilience/FilterRetryManagerTest.java) | T4, T5 | Cat-D | L500 parkNanos + `vertx.close().await()` — both fixed in one Cat-D rewrite |
| [ ] | [examples/AutomaticTransactionManagementExampleTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/examples/AutomaticTransactionManagementExampleTest.java) | T5 | Cat-A | L347: delete parkNanos "Simulate processing time" |
| [ ] | [examples/JdbcIntegrationHybridExampleTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/examples/JdbcIntegrationHybridExampleTest.java) | T5 | Cat-A | L284, L294: delete both parkNanos |
| [ ] | [examples/SystemPropertiesConfigurationExampleTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/examples/SystemPropertiesConfigurationExampleTest.java) | T5 | Cat-C | L266 spin-loop; method signature change required |
| [ ] | [examples/TransactionParticipationAdvancedExampleTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/examples/TransactionParticipationAdvancedExampleTest.java) | T4 | — | |

### `peegeeq-native`

| Done | File | Tiers | T5 Action | Notes |
|------|------|-------|-----------|-------|
| [ ] | [ConsumerModeMetricsTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ConsumerModeMetricsTest.java) | T4 | — | |
| [ ] | [ConsumerModePerformanceTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ConsumerModePerformanceTest.java) | T4 | — | |
| [ ] | [ConsumerModeTypeSafetyTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ConsumerModeTypeSafetyTest.java) | T4 | — | |
| [ ] | [HybridModeEdgeCaseTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/HybridModeEdgeCaseTest.java) | T4 | — | |
| [ ] | [JsonbConversionValidationTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/JsonbConversionValidationTest.java) | T4 | — | |
| [ ] | [ListenNotifyOnlyEdgeCaseTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ListenNotifyOnlyEdgeCaseTest.java) | T4 | — | |
| [ ] | [MemoryAndResourceLeakTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/MemoryAndResourceLeakTest.java) | T4 | T5 exempt | T5 sleeps inside `executeBlocking` — tag kept (category 4); T4 `.await()` still needs fixing |
| [ ] | [MultiConsumerModeTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/MultiConsumerModeTest.java) | T4 | — | |
| [ ] | [MultiTenantSchemaIsolationTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/MultiTenantSchemaIsolationTest.java) | T4 | — | |
| [ ] | [NativeQueueIntegrationTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/NativeQueueIntegrationTest.java) | T4 | — | |
| [ ] | [PgNativeQueueConcurrentClaimIT.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PgNativeQueueConcurrentClaimIT.java) | T4 | — | |
| [ ] | [PgNativeQueueConsumerClaimIT.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PgNativeQueueConsumerClaimIT.java) | T4 | — | |
| [ ] | [PgNativeQueueConsumerCleanupIT.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PgNativeQueueConsumerCleanupIT.java) | T4 | — | |
| [ ] | [PgNativeQueueFactoryIntegrationTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PgNativeQueueFactoryIntegrationTest.java) | T4 | — | |
| [ ] | [PgNativeQueueShutdownTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PgNativeQueueShutdownTest.java) | T4 | — | |
| [ ] | [PollingOnlyEdgeCaseTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PollingOnlyEdgeCaseTest.java) | T4 | — | |
| [ ] | [PostgreSQLErrorHandlingTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PostgreSQLErrorHandlingTest.java) | T4 | — | |
| [ ] | [QueueFactoryConsumerModeTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/QueueFactoryConsumerModeTest.java) | T4 | — | |
| [ ] | [RetryableErrorIT.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/RetryableErrorIT.java) | T4 | — | |
| [ ] | [examples/MessagePriorityExampleTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/examples/MessagePriorityExampleTest.java) | T4 | — | |
| [ ] | [examples/NativeVsOutboxComparisonExampleTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/examples/NativeVsOutboxComparisonExampleTest.java) | T4 | — | |

### `peegeeq-test-support`

| Done | File | Tiers | T5 Action | Notes |
|------|------|-------|-----------|-------|
| [ ] | [test/demo/ParameterizedPerformanceDemoTest.java](../../peegeeq-test-support/src/test/java/dev/mars/peegeeq/test/demo/ParameterizedPerformanceDemoTest.java) | T5 | Cat-A | L153: delete `Thread.sleep` "Simulate variable work" |
| [ ] | [test/consumer/ConsumerModePerformanceTestBaseTest.java](../../peegeeq-test-support/src/test/java/dev/mars/peegeeq/test/consumer/ConsumerModePerformanceTestBaseTest.java) | T5 | Cat-A | L212: delete `Thread.sleep` in `simulateWork()`; `generateRealisticMetrics()` is formula-based |

### `peegeeq-examples`

| Done | File | Tiers | T5 Action | Notes |
|------|------|-------|-----------|-------|
| [ ] | [nativequeue/DistributedSystemResilienceDemoTest.java](../../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/nativequeue/DistributedSystemResilienceDemoTest.java) | T5 | Cat-B (DEFERRED) | L254, L326 — caller-graph audit required before acting; do not touch until authorized |
| [ ] | [bitemporal/BiTemporalEventStoreExampleTest.java](../../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/bitemporal/BiTemporalEventStoreExampleTest.java) | T4 | — | |

---

## T5 Execution Order

Complete T5 items in this order before running the guard. T4 and T7 are a separate, larger pass.

1. **Tag-restore** — `OutboxMetricsTest.java`, `OutboxPerformanceTest.java` (2 one-line edits, zero risk)
2. **Cat-A deletions** — 4 files, 5 lines: `AutomaticTransactionManagementExampleTest`, `JdbcIntegrationHybridExampleTest` (×2), `ParameterizedPerformanceDemoTest`, `ConsumerModePerformanceTestBaseTest` (zero risk)
3. **Cat-D rewrite** — `FilterRetryManagerTest.java:500` (also removes the T4 `.await()` in that method)
4. **Cat-C rewrites** — `FilterErrorHandlingIntegrationTest`, `MessageReliabilityTest`, `SystemPropertiesConfigurationExampleTest` (invasive but mechanical — method signature change required in each)
5. **Cat-B** — `DistributedSystemResilienceDemoTest` remains deferred until caller-graph audit is explicitly authorized

**Guard verification command (run after steps 1–4):**
```powershell
mvn test -pl :peegeeq-test-support -Pcore-tests -Dtest=OnSuccessExceptionSwallowingGuardTest#noBlockingThreadDelaysInTestSources 2>&1 | Tee-Object -FilePath logs\guard-tier5-YYYYMMDD.txt
```

Expected result after steps 1–4: 2 remaining violations (the deferred Cat-B lines in `DistributedSystemResilienceDemoTest`) — or 0 if Cat-B is also authorized.

---

## T7 Execution Note

Because every T7-affected file also has T4 violations, **do not fix T7 in isolation**. Fixing T7 teardown with `.await()` is a Tier 4 violation and nets zero improvement. Fix T7 and T4 together in the same file pass using the `VertxTestContext` teardown pattern below.

### `OutboxConsumerGroup` — `close()` / `closeAsync()` naming constraint

`ConsumerGroup<T>` extends `AutoCloseable`, which mandates `void close()`. Java does not allow overloading by return type, so `Future<Void> close()` is not legal alongside `void close()`. This is why `closeAsync()` and `stopGracefully()` exist with the `Async`/`Gracefully` suffixes — the naming rules' exception for coexisting sync variants applies here.

**Impact on T7 fixes in `OutboxConsumerGroup`-typed variables:** replace `group.close()` with `group.closeAsync()` and `group.stop()` with `group.stopGracefully()` — not `group.close().onFailure(...)` (compile error: void return). The T7 guard regex `\.(?:stop|close)\s*\(` does not match `closeAsync` or `stopGracefully`, so these calls are not re-flagged.

**Future clean-up task (out of scope for this plan):** Remove `AutoCloseable` from the `ConsumerGroup<T>` interface, change `close()` to return `Future<Void>`, and delete `closeAsync()`. This would also remove the `void close()` / `void stop()` blocking `.await()` wrappers (T4 violations in production code at `OutboxConsumerGroup` lines 724 and 543).

---

## Implementation Patterns (reference)

### T5 Category A — Delete the line

No-op "simulate work" delays with no semantic value. Delete the offending line and its `// Simulate ...` comment. Check for unused `LockSupport` / `TimeUnit` imports afterward (`get_errors`).

### T5 Category C — Replace spin-loop with handler-completed Promise

**Pattern being replaced:**
```java
long deadline = System.currentTimeMillis() + 5_000;
while (processedCount.get() < N && System.currentTimeMillis() < deadline) {
    LockSupport.parkNanos(50_000_000L);
}
assertEquals(N, processedCount.get(), "...");
```

**Replacement:**
```java
@Test
@Timeout(5)
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
        .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
            assertEquals(N, processedCount.get(), "...");
            ctx.completeNow();
        })));
}
// JUnit @Timeout replaces the manual deadline.
// BANNED: bare .onSuccess(...).onFailure(ctx::failNow) — use .onComplete(ctx.succeeding(...)) only.
```

### T5 Category D — Small delay before discrete event

**Current pattern:**
```java
retryManager.executeWithRetry(message, filter, circuitBreaker);
// Give it a moment to start, then close Vertx while retries are in progress.
LockSupport.parkNanos(20_000_000L);  // 20 ms
vertx.close().await();
vertx = Vertx.vertx();
```

**Replacement:**
```java
@Test
void testSchedulerShutdownGracefully(VertxTestContext ctx) {
    // ...build message and filter...
    retryManager.executeWithRetry(message, filter, circuitBreaker);
    vertx.timer(20)
        .compose(v -> vertx.close())
        .onComplete(ctx.succeeding(v -> ctx.completeNow()));
}
// BANNED: bare .onSuccess(...).onFailure(ctx::failNow) — use .onComplete(ctx.succeeding(...)) only.
// vertx field re-init moves into @BeforeEach or stays in tearDown.
```

### T7 — Correct teardown pattern

**Wrong (Tier 7 + Tier 4):**
```java
@AfterEach
void tearDown() {
    connectionManager.close();          // T7: Future discarded
    manager.closeReactive().await();    // T4: banned .await()
}
```

**Correct:**
```java
@AfterEach
void tearDown(VertxTestContext testContext) {
    if (manager == null) { testContext.completeNow(); return; }
    manager.closeReactive()
        .onSuccess(v -> { manager = null; testContext.completeNow(); })
        .onFailure(err -> {
            logger.error("close failed", err);
            manager = null;
            testContext.failNow(err);   // do NOT swallow to completeNow()
        });
}
```

A failed close leaks database connections — the offending test appears green and a later unrelated test fails with "too many clients".

### T4 — Canonical async test form

```java
// Success expected:
@Test
void someOperationSucceeds(VertxTestContext ctx) {
    service.doSomething()
        .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
            assertEquals(expected, result);
            ctx.completeNow();
        })));
}

// Failure expected:
@Test
void someOperationFails(VertxTestContext ctx) {
    service.doSomething(-1)
        .onComplete(ctx.failing(e -> ctx.verify(() -> {
            assertTrue(e instanceof IllegalArgumentException);
            ctx.completeNow();
        })));
}
```

`ctx.succeeding`/`ctx.failing` route the unexpected branch to `failNow` automatically. `ctx.verify` catches `AssertionError` and reports it to the context. Every method must reach `ctx.completeNow()` exactly once on the intended terminal branch.

---

## Exempt Files (T5 — no action required)

Files that legitimately keep `@Tag("demonstration")` under the criteria below. **Do not modify these for T5.**

`@Tag("demonstration")` is valid only when: (1) test subject IS the blocking pattern, (2) test subject IS time-based state transition in a non-reactive component, (3) JVM-thread diagnostics post-`vertx.close()` with no reactive runtime, or (4) sleep is inside `vertx.executeBlocking(...)` on a worker thread.

| File | Criterion | Reason |
|------|-----------|--------|
| [VertxEventLoopBlockingJoinTest.java](../../peegeeq-db/src/test/java/dev/mars/peegeeq/db/performance/VertxEventLoopBlockingJoinTest.java) | 1 | `Thread.sleep` IS the test subject |
| [CircuitBreakerRecoveryTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/CircuitBreakerRecoveryTest.java) | 2 | `parkNanos > timeout` validates CircuitBreaker state transition |
| [VertxAsyncTestPitfallsDemo.java](../../peegeeq-db/src/test/java/dev/mars/peegeeq/db/testpatterns/VertxAsyncTestPitfallsDemo.java) | 1 | Pedagogical anti-pattern demo |
| [ResourceLeakDetectionTest.java](../../peegeeq-db/src/test/java/dev/mars/peegeeq/db/ResourceLeakDetectionTest.java) | 3 | All sleeps after `closeReactive()`; no reactive runtime |
| [MemoryAndResourceLeakTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/MemoryAndResourceLeakTest.java) | 4 | All sleeps inside `vertx.executeBlocking(...)` on worker thread |
| [ShutdownTest.java](../../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/patterns/ShutdownTest.java) | 1 | Tests `ExecutorService` interrupt semantics; sleep is inside `executor.submit(Runnable)` |
| [FilterErrorHandlingIntegrationTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/FilterErrorHandlingIntegrationTest.java) | 2 | L268 timeout-elapse pattern (tag retained); L154 spin-loop is still fixed separately |
| [SharedTestContainers.java](../../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/shared/SharedTestContainers.java) | 1 | Testcontainers port-mapping retry infrastructure |
| [SharedTestContainers.java](../../peegeeq-examples-spring/src/test/java/dev/mars/peegeeq/examples/shared/SharedTestContainers.java) | 1 | Same as above |
