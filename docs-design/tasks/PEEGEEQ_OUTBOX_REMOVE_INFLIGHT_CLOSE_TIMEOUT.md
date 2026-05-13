# Task: Remove `inflightProcessing` / `inflightFutures` Timeout Machinery and Audit `*Async` Naming

Created: 2026-05-13  
Updated: 2026-05-13 (expanded after deeper audit)  
Branch: `master`  
Status: **PENDING**

---

## Background

`OutboxConsumer.closeAsync()` has a 30-second `vertx.setTimer()` callback (old non-composable API) that waits for an `inflightProcessing` future before closing `reactivePool`. Initial investigation pointed to this as the cause of 30-second hangs in fault-tolerance tests. **Deeper investigation shows the antipattern exists at TWO layers, and only the upper layer matters for the hang.**

### The antipattern

Both `OutboxConsumer` and `OutboxConsumerGroupMember` track futures returned by the user's `MessageHandler` and try to await them on shutdown. The consumer has no control over when the user's handler completes. A `Promise<Void>` that never completes (the F3 hung-handler test scenario) makes shutdown wait indefinitely (or, in the consumer's case, exactly 30 seconds because of the timer escape hatch).

This is not graceful shutdown — it is two classes incorrectly trying to supervise user code they did not call.

---

## Root Cause Analysis (Two Layers)

### Layer 1: `OutboxConsumer.inflightProcessing` (the timer-guarded one)

```
scheduledProcessMessages()
  └─ processing = processAvailableMessages()   // Future chains into user's MessageHandler
       inflightProcessing = processing          // stored here
closeAsync()
  └─ waits for inflightProcessing              // 30s wall-clock wait for user code
       └─ reactivePool.close()                 // reactivePool is always null → dead code
```

Three problems compounded:

1. **`reactivePool` is never assigned in production.** Connections are obtained on demand via `getReactivePoolFuture()` from the shared `PgConnectionManager` and are never stored to the `reactivePool` field. The `if (reactivePool != null)` pool-close branch in both `closeAsync()` and `close()` never executes. Verified by grep — zero assignment sites in production code.

2. **Waiting for the user's `MessageHandler` is wrong.** `inflightProcessing` holds the result of `processAvailableMessages()`, which chains all the way through DB query → deserialise → user's `MessageHandler` → DB ack.

3. **`vertx.setTimer()` is the old callback-style API.** The composable `vertx.timer(long, TimeUnit)` returning `Timer extends Future<Long>` should be used.

### Layer 2: `OutboxConsumerGroupMember.inflightFutures` (the real source of the 30-second hang)

```
processMessage(message)
  └─ processingFuture = messageHandler.handle(message)   // user's Future
       inflightFutures.add(tracked)                       // tracked here
stopAsync()
  └─ Future.all(inflightFutures).mapEmpty()              // no timeout — hangs forever
```

`OutboxConsumerGroup.stopInternal()` ([OutboxConsumerGroup.java#L580-L582](peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxConsumerGroup.java#L580)) calls `m.stopAsync()` on every member and awaits via `Future.all(memberStops)` — propagating the wait up to the group level.

**Net effect:** when `OutboxConsumer.closeAsync()`'s 30-second timer is removed but `OutboxConsumerGroupMember.stopAsync()` keeps its untimed `Future.all(inflightFutures)`, the test will hang forever instead of for 30 seconds. **Removing only layer 1 makes things worse.** Both layers must be removed in the same change.

### Impact

`OutboxConsumerGroupFaultToleranceTest` F3 "hung handler" scenario intentionally uses a never-completing `Promise<Void>`. Its `@AfterEach` teardown calls `consumerGroup.stop()` → `OutboxConsumerGroup.stopInternal()` → `m.stopAsync()` per member → `Future.all(inflightFutures)`. Today the teardown goes through the consumer's `closeAsync()` path with the 30 s timer, masking the group-level problem. Once the consumer field is removed, the group-level hang becomes visible — which is why both layers must be cut together.

---

## What Must Change

### `OutboxConsumer.java`

**Remove these fields (lines 92–105):**
- `private volatile Future<Void> inflightProcessing` — dead coordination state
- `long closeInflightTimeoutMs = 30_000L` — package-private for test override; both field and concept disappear
- `private volatile Pool reactivePool` — never assigned; dead pool reference

**Remove this assignment (line 253 in `scheduledProcessMessages()`):**
```java
inflightProcessing = processing;
```

**Remove `closeAsync()` method entirely (lines 864–925).** No async work is needed. Callers that require a `Future<Void>` should call `close()` and return `Future.succeededFuture()`.

**Simplify `void close()` (lines 927–943):** Remove the `reactivePool` block. The body becomes:
```java
if (closed.compareAndSet(false, true)) {
    unsubscribe();
    if (pollingTimerId != -1) {
        vertx.cancelTimer(pollingTimerId);
        pollingTimerId = -1;
    }
    logger.info("Closed outbox consumer for topic: {}", topic);
}
```

### `OutboxConsumerGroupMember.java`

**Remove field** (line 75):
```java
private final Set<Future<Void>> inflightFutures = ConcurrentHashMap.newKeySet();
```

**Remove `inflightFutures.add(...)`** registration logic in `processMessage()` (around lines 432–434 — the comment `// Register this in-flight future so stopAsync() can await it` and the surrounding tracking code).

**Remove `stopAsync()` method entirely** (lines 218–232). Callers should call `stop()` (the existing void method) and treat the result as `Future.succeededFuture()`.

The `inFlightCount` `AtomicInteger` concurrency gate (line 72) is **kept** — it serves a different, legitimate purpose (max-concurrency rate limiting on accepting new messages). It does not block shutdown.

### `OutboxConsumerGroup.java`

**`stopInternal()`** (lines 575–600): remove the `m.stopAsync()` await loop. Members are stopped synchronously via `stop()` (void). No await on user handler futures.

```java
members.values().forEach(OutboxConsumerGroupMember::stop);
return closeUnderlyingConsumerAsync(...)
    .compose(v -> stopPartitionedEngineAsync())
    .eventually(...);
```

**`closeUnderlyingConsumerAsync()` (lines 606–625):** The `instanceof OutboxConsumer<?> oc` branch calls `oc.closeAsync()`. Change to call `oc.close()` and return `Future.succeededFuture()`. No async work is needed.

```java
if (underlyingConsumer instanceof OutboxConsumer<?> oc) {
    oc.close();
    consumerClose = Future.succeededFuture();
} else {
    underlyingConsumer.close();
    consumerClose = Future.succeededFuture();
}
```

**Naming note:** `OutboxConsumerGroup.closeAsync()` keeps its `Async` suffix because `ConsumerGroup<T> extends AutoCloseable` already provides `void close()`. Java does not allow `Future<Void> close()` as a separate overload — same name, same parameter list, different return type is a compile error. The `closeAsync()` suffix is the correct per-project-conventions name (synchronous variant exists, disambiguation required).

### `OutboxFactory.java`

**`closeTrackedResourcesAsync()` lines 587–591:** Change:
```java
asyncCloses.add(consumer.closeAsync()...);
```
to:
```java
consumer.close();
asyncCloses.add(Future.succeededFuture());
```
Leave the `group.closeAsync()` call on line 591 unchanged — `OutboxConsumerGroup.closeAsync()` is not being removed.

---

## Test Changes

### Delete these 4 test methods from `OutboxConsumerEdgeCasesCoverageTest.java`

All four test the removed `inflightProcessing` / `closeInflightTimeoutMs` machinery and will not compile once those fields and `closeAsync()` are removed.

| Line | Method | Why deleted |
|------|--------|-------------|
| 320 | `closeAsyncWaitsForInflightProcessing` | Uses reflection to set `inflightProcessing` field; calls `closeAsync()` |
| 357 | `closeAsyncCompletesWhenInflightProcessingFails` | Tests that `closeAsync()` tolerates a failed inflight future |
| 388 | `managerShutdownDoesNotHangWithInflightProcessing` | End-to-end scenario for the exact bug being removed |
| 422 | `closeAsyncTimesOutWhenInflightProcessingStalls` | Sets `typedConsumer.closeInflightTimeoutMs = 1_000L`; calls `closeAsync()` |

### Do not delete `closeAsyncIsIdempotent` (line 458)

This test calls `closeAsync()` and verifies idempotency. After `closeAsync()` is removed, convert to test `close()` idempotency instead:
```java
consumer.close();
consumer.close(); // second call must be a no-op
testContext.completeNow();
```

### Search for tests calling `OutboxConsumerGroupMember.stopAsync()`

Remove or convert any such tests:
```powershell
Select-String -Path "peegeeq-outbox\src\test\**\*.java" -Pattern "stopAsync"
```

### `OutboxConsumerGroupReviewFixesTest.java` line 446

`Future<Void> result = group.closeAsync();` — `group` here is an `OutboxConsumerGroup`. This call is valid and unchanged because `OutboxConsumerGroup.closeAsync()` is not being removed.

---

## Review of the Hanging Tests That First Exposed the Bug

The 30-second hangs were originally observed in [OutboxConsumerGroupFaultToleranceTest.java](peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupFaultToleranceTest.java). The shared `@AfterEach` at [L131-L150](peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupFaultToleranceTest.java#L131) calls `consumerGroup.stop()` then `consumerGroup.close()`. Whenever a test left a never-completing user-handler future at teardown time, `stop()` blocked inside `Future.all(inflightFutures)` (no timeout in `OutboxConsumerGroupMember.stopAsync()`), then `close()` blocked inside `OutboxConsumer.closeAsync()`'s 30-second timer escape hatch.

After the redesign, neither path waits for user-handler futures. Per-test analysis:

| Test | Method | Before | After | Action |
|------|--------|--------|-------|--------|
| F2 `ConnectionLossRecovery` | [groupRecoversAfterConnectionKill](peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupFaultToleranceTest.java#L160) | Passed | Passes (faster teardown) | None |
| F3 `HungHandlerBlocking` | [hungHandlerBlocksPollLoop](peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupFaultToleranceTest.java#L361) | **30-second @AfterEach hang** | Passes cleanly; hung handler future is abandoned (correct — not the consumer's lifetime to manage) | **None.** Keep as the canonical regression guard. Assertions on `processingCount > 0`, `processing + pending == total`, `terminalCount == 0`, and `handlerCalls == 1` describe documented intended poll-loop behaviour |
| F4 `MidBatchFailure` | [batchContinuesAfterHandlerFailure](peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupFaultToleranceTest.java#L230) | Passed | Passes | None |
| F5 `StopDuringProcessing` | [stopDuringActiveHandlerCompletesCleanly](peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupFaultToleranceTest.java#L282) | Passed (only because the test contained a workaround that released the handler gate concurrently with `stopGracefully()`) | Passes | **Update comment** at L296-L298 — the explanation "closeAsync() waits for in-flight processing... otherwise stopGracefully() would block forever" is no longer true. Optionally simplify by removing the redundant `vertx.timer(500).onSuccess(...handlerGate.complete())` at L301-L304 — `stopGracefully()` now returns regardless of the gate |
| F6 `RetryAndDlqPipeline` | [alwaysFailingHandlerExhaustsRetries](peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupFaultToleranceTest.java#L444) | Passed | Passes | None |

The only `OutboxConsumerGroupFaultToleranceTest` edit required by this redesign is updating the F5 comment block (and optionally pruning the gate-release timer); no assertions or test semantics change.

### F5 — exact edit

Replace the block at [L294-L308](peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupFaultToleranceTest.java#L294):

```java
.compose(v -> {
    // Now stop the group gracefully while handler is in-flight.
    // closeAsync() waits for in-flight processing to finish, so we must
    // release the handler gate concurrently — otherwise stopGracefully()
    // would block forever waiting for the handler that is waiting on the gate.
    logger.info("Calling stopGracefully() while handler is in-flight");

    // Schedule gate release after a short delay so closeAsync() can proceed
    vertx.timer(500).onSuccess(id -> {
        logger.info("Releasing handler gate to allow in-flight processing to complete");
        handlerGate.complete();
    });

    return consumerGroup.stopGracefully();
})
```

with:

```java
.compose(v -> {
    // Stop the group while the user handler is still in-flight.
    // stopGracefully() no longer waits for user-handler futures, so the hung
    // gate is simply abandoned — that is the documented contract: handler
    // lifetime is the caller's concern, not the consumer's.
    logger.info("Calling stopGracefully() while handler is in-flight");
    return consumerGroup.stopGracefully();
})
```

---

## Related Findings (Documented, Out of Scope for THIS Task)

The audit surfaced three further issues. Recorded here for visibility; address in separate tasks to keep this change focused.

### A. Private helper methods violating the `*Async` naming rule

Project rule (`copilot-instructions.md`): only use `Async`/`Reactive` suffix when a synchronous variant with the same base name exists for disambiguation. The following private helpers violate it (no sync variant):

| File | Method | Suggested rename |
|---|---|---|
| [OutboxConsumerGroup.java#L606](peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxConsumerGroup.java#L606) | `private Future<Void> closeUnderlyingConsumerAsync(...)` | `closeUnderlyingConsumer(...)` |
| [OutboxConsumerGroup.java#L631](peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxConsumerGroup.java#L631) | `private Future<Void> stopPartitionedEngineAsync()` | `stopPartitionedEngine()` |
| [OutboxFactory.java#L577](peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxFactory.java#L577) | `private Future<Void> closeTrackedResourcesAsync()` | debatable — sync `close()` exists but with different base name |

### B. Public API interface violating the `*Async` naming rule

[peegeeq-api/.../health/HealthService.java](peegeeq-api/src/main/java/dev/mars/peegeeq/api/health/HealthService.java) lines 46 and 62:

```java
Future<OverallHealthInfo> getOverallHealthAsync();
Future<HealthStatusInfo> getComponentHealthAsync(String componentName);
```

No sync variant exists. Should be `getOverallHealth()` / `getComponentHealth(name)`. Public API change — touches `HealthCheckManager`, `HealthHandler`, and all consumers. Separate task.

### C. `vertx.setTimer(...)` callback-style violations (production)

Composable `vertx.timer(long, TimeUnit)` should be preferred. 14 production occurrences:

| File | Line(s) | Purpose |
|---|---|---|
| [OutboxConsumer.java](peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxConsumer.java#L891) | 891 | The close timeout being removed (handled by this task) |
| [FilterRetryManager.java](peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/resilience/FilterRetryManager.java#L169) | 169 | Retry delay |
| [AsyncFilterRetryManager.java](peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/resilience/AsyncFilterRetryManager.java#L244) | 244 | Retry delay |
| [PgNativeQueueConsumer.java](peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueConsumer.java#L296) | 296, 1228, 1238 | Reconnect + retry |
| [ReactiveNotificationHandler.java](peegeeq-bitemporal/src/main/java/dev/mars/peegeeq/bitemporal/ReactiveNotificationHandler.java#L386) | 386 | Reconnect delay |
| [HealthCheckManager.java](peegeeq-db/src/main/java/dev/mars/peegeeq/db/health/HealthCheckManager.java#L259) | 259, 325 | Initial delay + check timeout |
| [DatabaseTemplateManager.java](peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/DatabaseTemplateManager.java#L184) | 184 | Hard-coded 100 ms wait |
| [ServerSentEventsHandler.java](peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/ServerSentEventsHandler.java#L307) | 307 | SSE keepalive timeout |
| [PeeGeeQRestClient.java](peegeeq-rest-client/src/main/java/dev/mars/peegeeq/client/PeeGeeQRestClient.java#L814) | 814 | Retry delay |
| `peegeeq-examples` (SSEErrorHandlingExample, SSEConnectionManagementExample) | — | Demo code, low priority |

### D. Class-level naming oddity

[AsyncFilterRetryManager.java](peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/resilience/AsyncFilterRetryManager.java) and [FilterRetryManager.java](peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/resilience/FilterRetryManager.java) coexist with overlapping responsibility. `Async` as a class-name prefix is a code smell in this fully reactive codebase. Investigate whether one is dead code and consolidate. Spot-check: [OutboxConsumerGroupMember.java#L154](peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupMember.java#L154) wires `FilterRetryManager` (not the `Async` variant), suggesting `AsyncFilterRetryManager` may be dead code.

### E. Forbidden blocking bridges in `OutboxFactory`

Project rule (`copilot-instructions.md` "Reactive-Only / Forbidden"): `.toCompletionStage()` / `.toCompletableFuture()` bridge chains and `.get(...)` blocking waits are forbidden in production. **Three sites in [OutboxFactory.java](peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxFactory.java) violate this:**

| Lines | Method | Bridge usage |
|---|---|---|
| 327–346 | `isHealthy()` | `connectionProvider.isHealthy().toCompletionStage().toCompletableFuture().get(2, SECONDS)` |
| 371–435 | `getStats(String topic)` | `pool.preparedQuery(...).execute(...).toCompletionStage().toCompletableFuture().get(5, SECONDS)` |
| 554–569 | `getPoolBlocking()` | `connectionProvider.getReactivePool(...).toCompletionStage().toCompletableFuture().get(5, SECONDS)` |

All three are protected by `assertNotEventLoopForBlocking(...)`, but the bridge pattern itself is still banned. The async variants (`isHealthyAsync()`, `getStatsAsync()`) already exist and are reactive-clean. The sync versions exist only because `QueueFactory` declares them. **Real fix:** make `QueueFactory` reactive-only (remove sync `isHealthy()` / `getStats()` / `getPoolBlocking()`), then callers use the `*Async` variants directly and the bridges disappear. Public API change — separate task.

### F. Sync wrappers via `.await()` on `Future`

Project rule: "Remove sync wrappers before converting tests." Three production sync wrappers block on futures:

| File | Line | Method | Why it exists |
|---|---|---|---|
| [OutboxConsumerGroup.java#L539](peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxConsumerGroup.java#L539) | 539 | `stop()` → `stopInternal().await()` | `ConsumerGroup.stop()` is `void` in the API |
| [OutboxConsumerGroup.java#L730](peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxConsumerGroup.java#L730) | 730 | `close()` (AutoCloseable) → `closeAsync().await()` | `AutoCloseable.close()` is `void` |
| [OutboxFactory.java#L619](peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxFactory.java#L619) | 619 | `close()` (AutoCloseable) → `closeTrackedResourcesAsync().await()` | `AutoCloseable.close()` is `void` |

Cannot be removed without changing public interfaces (`ConsumerGroup`, `QueueFactory`, `AutoCloseable`). Separate task; ties in with E.

### G. Callback-style `.onComplete(ar -> if(ar.succeeded()))`

Project rule: forbidden — use `.onSuccess(...)` / `.onFailure(...)`. **One violation** in production:

| File | Lines | Code |
|---|---|---|
| [OutboxQueue.java#L122-L130](peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxQueue.java#L122) | 122–130 | `pool.close().onComplete(ar -> { if (ar.succeeded()) ... else ... })` |

Note: [OutboxConsumer.java#L517](peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxConsumer.java#L517) uses `if (ar.succeeded())` but inside `.transform(ar -> ...)`, which is the correct reactive API — not a violation.

### H. `OutboxQueue.java` likely dead placeholder code

[OutboxQueue.java](peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxQueue.java) has comments "Placeholder for actual implementation" and "In a real implementation, this would...". Its `send()`/`receive()`/`acknowledge()` methods don't actually persist anything; they just complete a promise. It also contains the `Promise.promise() + runOnContext + complete()` anti-pattern (lines 86–101 and 107–119) and the Finding G callback. Verify zero usages and delete, or finish the implementation. Separate task.

---

## Files Changed (This Task)

| File | Change type |
|------|-------------|
| `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxConsumer.java` | Remove 3 fields, remove `closeAsync()`, simplify `close()`, remove `inflightProcessing` assignment |
| `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupMember.java` | Remove `inflightFutures` field, remove `stopAsync()`, remove tracking code in `processMessage()` |
| `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxConsumerGroup.java` | `stopInternal()` no longer awaits `m.stopAsync()`; simplify `closeUnderlyingConsumerAsync()` |
| `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxFactory.java` | Change `consumer.closeAsync()` to `consumer.close()` |
| `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerEdgeCasesCoverageTest.java` | Delete 4 test methods; convert 1 test to use `close()` |
| `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerGroupFaultToleranceTest.java` | F5 only: update stale comment block at L294-L308 and remove the now-redundant `vertx.timer(500).onSuccess(... handlerGate.complete())` workaround |
| any test calling `member.stopAsync()` | Convert to `member.stop()` |

---

## Verification

After changes:

1. Absence check — run from workspace root:
   ```powershell
   Select-String -Path "peegeeq-outbox\src\main\**\*.java" `
     -Pattern "inflightProcessing|closeInflightTimeoutMs|reactivePool|inflightFutures|stopAsync"
   Select-String -Path "peegeeq-outbox\src\main\**\*.java" `
     -Pattern "closeAsync" |
     Where-Object { $_.Line -notmatch "OutboxConsumerGroup" }
   ```
   First command must return zero results. Second must return only the `OutboxConsumerGroup.closeAsync()` definition and its internal callers.

2. Compile and test:
   ```powershell
   mvn test -Pall-tests -pl peegeeq-outbox --no-transfer-progress 2>&1 | Tee-Object -FilePath logs\outbox-redesign-20260513.txt
   ```
   All tests pass. `OutboxConsumerGroupFaultToleranceTest` must complete all iterations in well under 5 minutes total (previously each hung-handler iteration burned 30 seconds).

---

## Out of Scope (Explicit)

- `peegeeq-api` interfaces (`MessageConsumer`, `ConsumerGroup`, `HealthService`) — not changed.
- `peegeeq-native` (`PgNativeQueueConsumer`) — not changed.
- Any other module — not changed.
- Renaming any `*Async` method (Related Finding A and B) — separate tasks.
- Replacing `vertx.setTimer(...)` with `vertx.timer(...)` across the codebase (Related Finding C) — separate task.
- Consolidating `AsyncFilterRetryManager` and `FilterRetryManager` (Related Finding D) — separate task.
- Removing `.toCompletionStage().toCompletableFuture().get(...)` bridges from `OutboxFactory` (Related Finding E) — blocked on `QueueFactory` API decision; separate task.
- Removing `.await()` sync wrappers (Related Finding F) — coupled with E; separate task.
- Fixing `.onComplete(ar -> if(succeeded))` callback in `OutboxQueue` (Related Finding G) — separate task.
- Investigating / deleting `OutboxQueue` placeholder (Related Finding H) — separate task.
- Adding new inflight-tracking or graceful drain logic — explicitly excluded. The pool manages its own draining. User handler lifetime is the caller's concern, not the consumer's.

---

