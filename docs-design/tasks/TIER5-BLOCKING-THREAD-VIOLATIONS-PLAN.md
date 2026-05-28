# Guard Tiers 4, 5, 7 — Audit and Remediation Plan

**Date:** 2026-05-17  
**Last updated:** 2026-05-28  
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

**T5 action legend:** Tag-restore = add back `@Tag("blocking-exempt")` · Cat-A = delete the line · Cat-B = DEFERRED (caller-graph audit needed) · Cat-C = replace spin-loop with Promise + VertxTestContext · Cat-D = replace with `vertx.timer().compose(...)`

### `peegeeq-outbox`

| Done | File | Tiers | T5 Action | Notes |
|------|------|-------|-----------|-------|
| [x] | [OutboxMetricsTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxMetricsTest.java) | T5 | Tag-restore | No blocking patterns present in file — tag not needed; file already uses reactive patterns. No action required. |
| [x] | [OutboxPerformanceTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxPerformanceTest.java) | T5 | Tag-restore | No blocking patterns present in file — blocking loop already replaced with `vertx.timer()` / composed futures. No action required. |
| [x] | [AsyncRetryMechanismTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/AsyncRetryMechanismTest.java) | T4 | — | No `.await()` violations found — T4 already fixed. |
| [x] | [FilterErrorHandlingIntegrationTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/FilterErrorHandlingIntegrationTest.java) | T5 | Cat-C | No T5 or T4 violations found — both already fixed. |
| [x] | [JsonbConversionValidationTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/JsonbConversionValidationTest.java) | T4, T7 | — | Fixed 2026-05-27: added `@ExtendWith(VertxExtension.class)`; `setUp(Vertx,VertxTestContext)` reactive; `tearDown(VertxTestContext)` chains `connectionManager.close()` via `.eventually()`; all 3 tests converted to `VertxTestContext` compose chains. |
| [x] | [MessageReliabilityTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/MessageReliabilityTest.java) | T5 | Cat-C | No T5 or T4 violations found — already fixed. |
| [x] | [OutboxBlockingSafetyTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxBlockingSafetyTest.java) | T4 | — | Fixed 2026-05-27: added `@ExtendWith(VertxExtension.class)`; all 3 tests inject `(Vertx, VertxTestContext)`; `invokeOnEventLoop` returns `Future<Throwable>` instead of blocking; try/finally+`vertx.close().await()` removed. |
| [x] | [OutboxConfigurationIntegrationTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConfigurationIntegrationTest.java) | T4 | — | Fixed 2026-05-27: tearDown reactive via `VertxTestContext`; all 3 tests use `manager.start().compose(...subscribe).compose(...send).onFailure(failNow)`; `vertx.timer().await()` replaced with `new CountDownLatch(1).await(2s)`. |
| [x] | [OutboxConsumerCoreTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerCoreTest.java) | T7 | — | T7 fixed (2026-05-18): `groupConsumer` local replaced by `this.consumer` reassignment; tearDown closes it. No `.await()` found — T4 tag was incorrect. |
| [x] | [OutboxConsumerCoverageTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerCoverageTest.java) | T4 | — | Fixed 2026-05-27: reactive setUp; all subscribe Futures chained; testMessageDeletedDuringProcessing and testCompletionPersistenceBlocksNextMessageProcessing rewritten as compose chains; vertx.timer(800) used in-chain (not awaited). |
| [x] | [OutboxConsumerCrashRecoveryTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerCrashRecoveryTest.java) | T4, T7 | — | Fixed 2026-05-27: added VertxExtension; reactive setUp/tearDown; helpers converted to Future<T>; test rewritten as compose chain; vertx.timer() used in-chain. |
| [x] | [OutboxConsumerEdgeCasesCoverageTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerEdgeCasesCoverageTest.java) | T4 | — | |
| [x] | [OutboxConsumerFailureHandlingTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerFailureHandlingTest.java) | T4, T7 | — | No T4 or T7 violations found — both already fixed. |
| [x] | [OutboxConsumerGroupClientIdPropagationTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupClientIdPropagationTest.java) | T7 | — | T7 fixed: L162 now `group.close().onFailure(...)` — Future is observed. |
| [x] | [OutboxConsumerGroupCoreTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupCoreTest.java) | T7 | — | 11 discarded calls fixed (2026-05-18). See T7 note: `closeAsync()`/`stopGracefully()` required — `close()`/`stop()` return `void`. |
| [x] | [OutboxConsumerGroupFaultToleranceTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupFaultToleranceTest.java) | T7 | — | L135, 136, 148 |
| [x] | [OutboxConsumerGroupFilteredMessageStatusTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupFilteredMessageStatusTest.java) | T7 | — | T7 fixed: `consumerGroup.stop().compose(v -> consumerGroup.close())` at L106-107; `.onFailure()` at L289. |
| [x] | [OutboxConsumerGroupGracefulShutdownTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupGracefulShutdownTest.java) | T4, T7 | — | Fixed 2026-05-28: plan notes were stale — no `.await()` or bare `close()` remained. Violations fixed: 6× banned `.onSuccess(completeNow).onFailure(failNow)` replaced with `.onComplete(ctx.succeeding(...))`, 6× intermediate assertions in `.map()` wrapped in `ctx.verify()`, `@Timeout(5)` added to synchronous test. |
| [x] | [OutboxConsumerGroupIntegrationTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupIntegrationTest.java) | T7 | — | T7 fixed: `consumerGroup.stop().compose(v -> consumerGroup.close())` — both chained, not discarded. |
| [x] | [OutboxConsumerGroupReviewFixesTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupReviewFixesTest.java) | T7 | — | Fixed 2026-05-28: plan T7 notes (L95, 432, 492, 582) were stale — no bare discarded `close()` calls found. Only fix applied: removed trivial constant assertion `defaultMaxConcurrencyIsOne()` (tested `DEFAULT_MAX_CONCURRENCY == 1`, covered behaviourally by `groupMemberUsesDefaultConcurrency`). |
| [x] | [OutboxConsumerGroupSubscriptionEdgeCasesTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupSubscriptionEdgeCasesTest.java) | T7 | — | Fixed 2026-05-28: 14 T7 violations fixed (12 `group.close().succeeded()` inside reactive callbacks moved to `.compose()` steps; 2 `groupHolder[0].close().succeeded()` after `awaitCompletion` replaced with `.onFailure()` observation); 4 banned `.onSuccess(completeNow).onFailure(failNow)` terminals replaced with `.onComplete(ctx.succeeding(...))`. Tests passed exit code 0. |
| [x] | [OutboxConsumerGroupSubscriptionTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupSubscriptionTest.java) | T7 | — | T7 fixed: all 20 violations replaced with `group.close().onFailure(...)` or `.compose()` — Futures observed. |
| [x] | [OutboxConsumerGroupTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupTest.java) | T7 | — | T7 fixed: L99 now `consumerGroup.close().onFailure(...)` — Future is observed. |
| [x] | [OutboxConsumerNullHandlerTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerNullHandlerTest.java) | T7 | — | Plan note stale — no violations found: `consumer.close()` and `producer.close()` return `void` (AutoCloseable), not `Future<Void>`; tearDown uses standard `.onSuccess(completeNow).onFailure(failNow)` form. No changes needed. |
| [x] | [OutboxRetryConcurrencyTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxRetryConcurrencyTest.java) | T4, T7 | — | Fixed 2026-05-28: T4 plan notes stale (no `.await()` found). T7: one violation fixed — `outboxFactory.close()` Future was discarded in tearDown try-catch; now chained via `.eventually()` before manager/connectionManager close. |
| [x] | [OutboxRetryResilienceTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxRetryResilienceTest.java) | T4, T7 | — | Fixed 2026-05-28: T4 plan notes stale (no `.await()` found in test methods). T7: one violation fixed — `queueFactory.close()` (`QueueFactory.close()` returns `Future<Void>`) discarded in tearDown try-catch; chained via reactive `.eventually()` steps with manager/connectionManager. |
| [x] | [ReactiveOutboxProducerTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/ReactiveOutboxProducerTest.java) | T4, T7 | — | Plan notes stale — `connectionManager.close()` is already inside `.eventually()` at L107 (properly observed). `producer.close()` returns `void` (AutoCloseable). No T4 or T7 violations found. No changes needed. |
| [x] | [deadletter/DeadLetterQueueManagerCoverageTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/deadletter/DeadLetterQueueManagerCoverageTest.java) | T7 | — | T7 fixed: no bare `manager.close()` or `disabledManager.close()` calls found. |
| [x] | [resilience/FilterRetryManagerTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/resilience/FilterRetryManagerTest.java) | T4, T5 | Cat-D | No T4 or T5 violations found — both already fixed. |
| [x] | [examples/AutomaticTransactionManagementExampleTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/examples/AutomaticTransactionManagementExampleTest.java) | T5 | Cat-A | T5 fixed: no parkNanos. Only `CountDownLatch.await(30, TimeUnit.SECONDS)` remains — whitelisted form. |
| [x] | [examples/JdbcIntegrationHybridExampleTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/examples/JdbcIntegrationHybridExampleTest.java) | T5 | Cat-A | T5 fixed: no parkNanos. Only `CountDownLatch.await(30, TimeUnit.SECONDS)` remains — whitelisted form. |
| [x] | [examples/SystemPropertiesConfigurationExampleTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/examples/SystemPropertiesConfigurationExampleTest.java) | T5 | Cat-C | T5 fixed: no spin-loop. Only `CountDownLatch.await(30, TimeUnit.SECONDS)` remains — whitelisted form. |
| [x] | [examples/TransactionParticipationAdvancedExampleTest.java](../../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/examples/TransactionParticipationAdvancedExampleTest.java) | T4 | — | Plan notes stale — no `.await()` violations found anywhere in the file. `outboxFactory.close()` observed via `.onFailure()`; `vertxPool.close()` and `vertx.close()` observed post-`awaitCompletion` (no event loop needed). `orderProducer.close()` returns `void`. No changes needed. |

### `peegeeq-native`

| Done | File | Tiers | T5 Action | Notes |
|------|------|-------|-----------|-------|
| [x] | [ConsumerModeMetricsTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ConsumerModeMetricsTest.java) | T4 | — | No `.await()` violations found — T4 already fixed. |
| [x] | [ConsumerModePerformanceTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ConsumerModePerformanceTest.java) | T4 | — | No `.await()` violations found — T4 already fixed. |
| [x] | [ConsumerModeTypeSafetyTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ConsumerModeTypeSafetyTest.java) | T4 | — | Plan notes stale — no `.await()` violations found. `consumer.close()` and `producer.close()` return `void` (AutoCloseable). Only `CountDownLatch.await(timeout, unit)` present — whitelisted. No changes needed. |
| [x] | [HybridModeEdgeCaseTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/HybridModeEdgeCaseTest.java) | T4 | — | Plan note stale — no `.await()` violations found. No changes needed. |
| [x] | [JsonbConversionValidationTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/JsonbConversionValidationTest.java) | T4 | — | Plan note stale — no `.await()` violations found. No changes needed. |
| [x] | [ListenNotifyOnlyEdgeCaseTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ListenNotifyOnlyEdgeCaseTest.java) | T4 | — | Plan note stale — no `.await()` violations found. No changes needed. |
| [x] | [MemoryAndResourceLeakTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/MemoryAndResourceLeakTest.java) | T5 | T5 exempt | T5 sleeps inside `executeBlocking` — tag `@Tag("blocking-exempt")` kept (category 4). No T4 `.await()` violations found — T4 already fixed. |
| [x] | [MultiConsumerModeTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/MultiConsumerModeTest.java) | T4 | — | No `.await()` violations found — T4 already fixed. |
| [x] | [MultiTenantSchemaIsolationTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/MultiTenantSchemaIsolationTest.java) | T4 | — | Plan note stale — no `.await()` violations found. No changes needed. |
| [x] | [NativeQueueIntegrationTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/NativeQueueIntegrationTest.java) | T4 | — | Fixed 2026-05-28 (prior session): T4 violations resolved; all 12 tests pass. |
| [x] | [PgNativeQueueConcurrentClaimIT.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PgNativeQueueConcurrentClaimIT.java) | T4 | — | Plan note stale — no `.await()` violations found. No changes needed. |
| [x] | [PgNativeQueueConsumerClaimIT.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PgNativeQueueConsumerClaimIT.java) | T4 | — | Plan note stale — no `.await()` violations found. No changes needed. |
| [x] | [PgNativeQueueConsumerCleanupIT.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PgNativeQueueConsumerCleanupIT.java) | T4 | — | Plan note stale — no `.await()` violations found. No changes needed. |
| [x] | [PgNativeQueueFactoryIntegrationTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PgNativeQueueFactoryIntegrationTest.java) | T4 | — | Plan note stale — no `.await()` violations found. No changes needed. |
| [x] | [PgNativeQueueShutdownTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PgNativeQueueShutdownTest.java) | T4 | — | Plan note stale — no `.await()` violations found. No changes needed. |
| [x] | [PollingOnlyEdgeCaseTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PollingOnlyEdgeCaseTest.java) | T4 | — | Plan note stale — no `.await()` violations found. No changes needed. |
| [x] | [PostgreSQLErrorHandlingTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PostgreSQLErrorHandlingTest.java) | T4 | — | No `.await()` violations found — T4 already fixed. |
| [x] | [QueueFactoryConsumerModeTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/QueueFactoryConsumerModeTest.java) | T4 | — | Plan note stale — no `.await()` violations found. No changes needed. |
| [x] | [RetryableErrorIT.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/RetryableErrorIT.java) | T4 | — | Plan note stale — no `.await()` violations found. No changes needed. |
| [x] | [examples/MessagePriorityExampleTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/examples/MessagePriorityExampleTest.java) | T4 | — | Plan note stale — no `.await()` violations found. No changes needed. |
| [x] | [examples/NativeVsOutboxComparisonExampleTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/examples/NativeVsOutboxComparisonExampleTest.java) | T4 | — | Plan note stale — no `.await()` violations found. No changes needed. |
| [ ] | [examples/PerformanceComparisonExampleTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/examples/PerformanceComparisonExampleTest.java) | T4 | — | **Newly discovered** — not in original audit. T4: L371 `allProcessed.future().await()`. |
| [x] | [PeeGeeQConfigurationConsumerModeTest.java](../../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PeeGeeQConfigurationConsumerModeTest.java) | T4 | — | Fixed 2026-05-28: T4 — L258-259 `producer.send(...).await()` in `testConfigurationVisibilityTimeoutIntegrationWithConsumerModes` only; removed `.await()` from both sends. All other tests use `CountDownLatch.await()` correctly. |

### `peegeeq-test-support`

| Done | File | Tiers | T5 Action | Notes |
|------|------|-------|-----------|-------|
| [x] | [test/demo/ParameterizedPerformanceDemoTest.java](../../peegeeq-test-support/src/test/java/dev/mars/peegeeq/test/demo/ParameterizedPerformanceDemoTest.java) | T5 | Cat-A | No `Thread.sleep` found — T5 already fixed. |
| [x] | [test/consumer/ConsumerModePerformanceTestBaseTest.java](../../peegeeq-test-support/src/test/java/dev/mars/peegeeq/test/consumer/ConsumerModePerformanceTestBaseTest.java) | T5 | Cat-A | No `Thread.sleep` found — T5 already fixed. |

### `peegeeq-examples`

| Done | File | Tiers | T5 Action | Notes |
|------|------|-------|-----------|-------|
| [x] | [nativequeue/DistributedSystemResilienceDemoTest.java](../../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/nativequeue/DistributedSystemResilienceDemoTest.java) | T5 | Cat-B | No `Thread.sleep` or `parkNanos` found — T5 already fixed. Cat-B deferred was unnecessary. |
| [ ] | [bitemporal/BiTemporalEventStoreExampleTest.java](../../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/bitemporal/BiTemporalEventStoreExampleTest.java) | T4, T7 | — | T4: L146 `future.await()` in helper, L185 `manager.start().await()` in setUp, L217 `manager.closeReactive().await()` in tearDown. T7: L207 `eventStore.close()` — `EventStore.close()` returns `Future<Void>` (api/EventStore.java:336), Future discarded. |

---

## T5 Execution Order

T5 is **fully fixed** — all previously flagged violations have been resolved (verified 2026-05-27 by grep).
T4 and T7 are the remaining open work.

**T5 summary (all done):**
- Tag-restore: `OutboxMetricsTest.java`, `OutboxPerformanceTest.java` — already clean, no tag needed
- Cat-A: `AutomaticTransactionManagementExampleTest`, `JdbcIntegrationHybridExampleTest`, `ParameterizedPerformanceDemoTest`, `ConsumerModePerformanceTestBaseTest` — fixed
- Cat-B: `DistributedSystemResilienceDemoTest` — no violations found, Cat-B was unnecessary
- Cat-C: `FilterErrorHandlingIntegrationTest`, `MessageReliabilityTest`, `SystemPropertiesConfigurationExampleTest` — fixed
- Cat-D: `FilterRetryManagerTest.java` — fixed

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

Files that legitimately keep `@Tag("blocking-exempt")` under the criteria below. **Do not modify these for T5.**

`@Tag("blocking-exempt")` is valid only when: (1) test subject IS the blocking pattern, (2) test subject IS time-based state transition in a non-reactive component, (3) JVM-thread diagnostics post-`vertx.close()` with no reactive runtime, or (4) sleep is inside `vertx.executeBlocking(...)` on a worker thread.

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
