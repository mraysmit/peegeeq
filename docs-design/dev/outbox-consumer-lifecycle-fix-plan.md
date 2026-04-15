# Outbox Consumer Lifecycle Fix Plan

## Task Tracker

| # | Task | Status | Files | Depends On |
|--:|------|--------|-------|------------|
| 1 | OutboxFactory close hook — wire `closeTrackedResourcesAsync()` | DONE | `OutboxFactory.java`, `OutboxFactoryCloseHookTest.java` | — |
| 1b | OutboxConsumer in-flight tracking — `closeAsync()` waits for in-flight processing | DONE | `OutboxConsumer.java`, `OutboxConsumerEdgeCasesCoverageTest.java` | 1 |
| 2 | Clean up `isShutdownRelatedError` — remove string-matching and side-effects | DONE | `OutboxConsumer.java` | 1 |
| 3 | Remove `getConnectionProvider() == null` hack from `processAvailableMessages` | DONE | `OutboxConsumer.java` | 1 |
| 4 | Replace `ScheduledExecutorService` with `Vertx.setPeriodic()` in OutboxConsumer | DONE | `OutboxConsumer.java`, `PgConnectionManager.java` | — |
| 5a | Migrate `FilterRetryManager` — accept `Vertx`, use `vertx.setTimer()` | DONE | `FilterRetryManager.java` | — |
| 5b | Migrate `AsyncFilterRetryManager` — replace both executors with Vert.x | DONE | `AsyncFilterRetryManager.java` | 5a |
| 5c | Migrate `OutboxConsumerGroupMember` — remove `filterScheduler`/`ownsScheduler`, pass `Vertx` | DONE | `OutboxConsumerGroupMember.java` | 5a |
| 5d | Migrate `OutboxConsumerGroup` — remove `sharedFilterScheduler`, pass `Vertx` to members | DONE | `OutboxConsumerGroup.java` | 5c |
| 6 | Compile `peegeeq-outbox` (`mvn -pl peegeeq-outbox test-compile`) | DONE | — | 1–5d |
| 7 | Run full outbox integration tests, count "Client not found" — target: zero | TODO | — | 6 |

### Status Key

- **TODO** — not started
- **IN PROGRESS** — actively being worked
- **DONE** — complete and verified
- **BLOCKED** — waiting on dependency

---

## The Problem

3,975 `Client not found: null` error log lines during a full outbox integration test run
(410 tests, 1 failure, 1 skipped). The errors are silently swallowed and do not cause test
failures — but they indicate consumers polling against destroyed DB pools.

### Error Distribution by Test Class (36 affected classes)

| Errors | Test Class |
|-------:|------------|
| 420 | OutboxErrorHandlingTest |
| 396 | OutboxConsumerGroupTest |
| 363 | OutboxConsumerGroupSubscriptionEdgeCasesTest$ConcurrentOperationsTests |
| 318 | OutboxResourceLeakDetectionTest |
| 186 | OutboxRetryLogicTest |
| 174 | OutboxParallelProcessingTest |
| 150 | OutboxRetryResilienceTest |
| 132 | OutboxRetryConcurrencyTest |
| 129 | OutboxDeadLetterQueueTest |
| 120 | OutboxFactoryIntegrationTest |
| 120 | OutboxDirectExceptionHandlingTest |
| 93 | OutboxConsumerGroupSubscriptionEdgeCasesTest$FromMessageIdTests |
| 90 | OutboxFutureExceptionTest |
| 90 | OutboxConsumerSurgicalCoverageTest |
| 87 | OutboxConsumerGroupSubscriptionTest$SetMessageHandlerTests |
| 84 | OutboxProducerAdditionalCoverageTest |
| 78 | OutboxExceptionHandlingDemonstrationTest |
| 78 | OutboxConsumerIntegrationTest |
| 78 | OutboxProducerTransactionTest |
| 78 | OutboxProducerIntegrationTest |
| 72 | OutboxQueueBrowserIntegrationTest |
| 72 | OutboxMetricsTest |
| 69 | OutboxEdgeCasesTest |
| 60 | OutboxConsumerGroupSubscriptionTest$StartWithOptionsTests |
| 60 | OutboxConsumerNullHandlerTest |
| 54 | OutboxIdempotencyKeyTest |
| 51 | OutboxConsumerGroupSubscriptionEdgeCasesTest$EmptyTopicTests |
| 48 | ReactiveOutboxProducerTest |
| 48 | OutboxProducerCoreTest |
| 45 | OutboxConsumerGroupSubscriptionEdgeCasesTest$TimestampBoundaryTests |
| 42 | OutboxSchemaQuotingTest |
| 30 | OutboxQueueTest |
| 27 | OutboxConsumerGroupSubscriptionTest |
| 15 | OutboxConsumerGroupSubscriptionEdgeCasesTest$BuilderEdgeCasesTests |
| 12 | OutboxConsumerGroupSubscriptionEdgeCasesTest$HeartbeatConfigTests |
| 6 | OutboxConsumerFailureHandlingTest |

The single test failure is `OutboxConsumerGroupSubscriptionEdgeCasesTest$ConcurrentOperationsTests.testConcurrent_SetHandlerDuringProcessing` (30.73s timeout).

### Observations

- Every integration test that creates an `OutboxConsumer` or `OutboxConsumerGroup` is affected.
- The heaviest producers are error handling and consumer group tests — these create the most
  consumers with the longest polling windows.
- Producer-only tests still show errors because `OutboxFactory` creates internal consumers
  for health checks and stats that are never closed.

## Root Cause

A shutdown ordering bug: consumers keep polling after their DB pool infrastructure is destroyed.

### Current Shutdown Sequence (PeeGeeQManager.closeReactive)

```
Step 0: Await in-flight start()
Step 1: stop() — background tasks, health checks
Step 2: Run registered close hooks — OutboxFactory's hook is a NO-OP
Step 3: Close worker executor
Step 4: Close client factory — clears connectionConfigs, poolConfigs, clients maps
Step 5: Close meter registry
Step 6: Close Vert.x
```

After step 4, `PgClientFactory.getConnectionConfig(null)` returns `null` because the maps
were cleared in `closeAsync()`. Then `PgConnectionProvider.getReactivePool()` (line 75)
hits this:

```java
if (connectionConfig == null || poolConfig == null) {
    if (clientFactory.getAvailableClients().isEmpty()) {
        return Future.failedFuture(new IllegalStateException(
                "Connection provider is closed or shutting down"));
    }
    return Future.failedFuture(new IllegalArgumentException("Client not found: " + resolvedClientId));
}
```

The consumers are never told to stop, so their `ScheduledExecutorService` keeps firing
`scheduledProcessMessages()` every poll interval, each invocation generating another error.

### Why Consumers Are Never Stopped

`OutboxFactory` registers a close hook with the manager, but it does **nothing**:

```java
@Override
public io.vertx.core.Future<Void> closeReactive() {
    return io.vertx.core.Future.succeededFuture();  // no-op
}
```

`OutboxFactory.close()` DOES close all tracked resources (consumers, producers, groups),
but nobody calls it during manager shutdown. The close hook was the intended integration
point and it was left as a no-op.

### Why The Polling Loop Survives Vert.x Shutdown

`OutboxConsumer` uses a plain `java.util.concurrent.ScheduledExecutorService` for its
polling loop:

```java
this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "outbox-consumer-" + topic);
    t.setDaemon(true);
    return t;
});
```

PeeGeeQ is a pure Vert.x system. This should be `Vertx.setPeriodic()`. A Vert.x timer is
automatically canceled when `vertx.close()` runs in step 6 of the shutdown sequence. The
`ScheduledExecutorService` runs independently of Vert.x — it keeps firing after both the
pools and the Vert.x instance are destroyed.

The same problem exists for the `messageProcessingExecutor` field (a plain
`java.util.concurrent.ExecutorService`). Both executors are legacy artifacts from before
the Vert.x migration and were never converted.

`OutboxConsumer` does not even hold a `Vertx` reference. Converting to Vert.x timers
requires threading the `Vertx` instance through, either via the constructor (from
`DatabaseService.getVertx()` or `PgClientFactory`) or from the `PeeGeeQConfiguration`.

With Vert.x timers:
- The close hook fix (Change 1) is still the primary fix — it stops consumers in the
  correct shutdown step.
- Vert.x timer cancellation in step 6 provides a safety net — even if the close hook
  fails, Vert.x shutdown kills the timers.
- No daemon threads running outside Vert.x lifecycle control.

### Why Errors Are Swallowed

`scheduledProcessMessages()` calls `processAvailableMessages()` and handles failures in
`.onFailure()`. If `isShutdownRelatedError()` returns true, it logs at DEBUG. If false, it
logs at ERROR. Either way, it just returns — the scheduler fires again next interval.

`isShutdownRelatedError()` currently has string-matching hacks (added in a previous session)
that try to recognize "Client not found" by inspecting exception message text. This is
the wrong approach entirely.

## What Is Wrong With isShutdownRelatedError

### Current code (lines 410-443)

The method has two layers:

1. **Legitimate type checks** — `closed.get()`, `instanceof RejectedExecutionException`,
   `instanceof ClosedChannelException`. These are fine; they check structural types that
   reliably indicate executor/channel shutdown.

2. **String-matching garbage** — checking `IllegalArgumentException.getMessage().startsWith("Client not found")`
   and `IllegalStateException.getMessage().contains("closed or shutting down")`. These are:
   - **Fragile**: any change to the message text in `PgConnectionProvider` silently breaks detection.
   - **Coupling to internals**: the consumer now knows the exact wording of errors from a different module.
   - **Side-effecting in a predicate**: `isShutdownRelatedError` mutates state (`closed.set(true)`,
     `unsubscribe()`, `scheduler.shutdown()`). A method named `is...` should return a boolean,
     not perform shutdown. This makes the code impossible to reason about.
   - **Treating symptoms**: the consumer should never see these errors in the first place.
     If it does, something upstream failed to close it properly.

### What to keep

- `closed.get()` — correct
- `instanceof RejectedExecutionException` — correct (executor shut down)
- `instanceof ClosedChannelException` — correct (network channel closed)

### What to remove

- All string-matching blocks (the `IllegalArgumentException` "Client not found" check and the
  `IllegalStateException` "closed or shutting down" / "not available" check)
- All side-effects (the `closed.set(true)`, `unsubscribe()`, `scheduler.shutdown()` calls
  inside the predicate)

## What Is Wrong With processAvailableMessages (lines 295-308)

The hack that checks `databaseService.getConnectionProvider() == null` and self-closes is
also symptom treatment. Same problem: if the factory close hook worked, the consumer would
already be closed before the connection provider becomes null.

Remove this hack too.

## The Actual Fix

### Change 1: OutboxFactory close hook — the real fix (Task 1)

**File:** `OutboxFactory.java`

Make the close hook actually close all tracked resources. This ensures consumers are stopped
in step 2 of the shutdown sequence, BEFORE step 5 destroys the pools.

#### Current State

The constructor (lines 103-113) registers a no-op close hook:

```java
registrar.registerCloseHook(new PeeGeeQCloseHook() {
    public String name() { return "outbox"; }
    public io.vertx.core.Future<Void> closeReactive() {
        return io.vertx.core.Future.succeededFuture();  // no-op
    }
});
```

The sync `close()` (line 538) does the actual work — iterates `createdResources`
(`Set<AutoCloseable>`) calling `resource.close()` — but nobody calls it during shutdown.

`PeeGeeQManager.closeReactive()` invokes hooks in step 2 via
`chain.compose(ignored -> hook.closeReactive().recover(...))`, sequenced BEFORE step 5
(client factory pool teardown).

#### Resource Types in `createdResources`

5 call sites add resources via `createdResources.add(...)`:

| Type | `closeAsync()` → `Future<Void>` | `close()` sync | Notes |
|------|:-------------------------------:|:--------------:|-------|
| `OutboxConsumer` | Yes (public) | Yes | Cancels Vert.x timer, closes reactive pool |
| `OutboxConsumerGroup` | Yes (package-private) | Yes (delegates to `closeAsync().await()`) | Stops members, closes underlying consumer |
| `OutboxProducer` | No | Yes | Sets flag only, lightweight |
| `OutboxQueueBrowser` | No | Yes | Lightweight |

#### Implementation Steps

**Step 1: Add `closeTrackedResourcesAsync()` method**

New method returning `Future<Void>`:

```java
private Future<Void> closeTrackedResourcesAsync() {
    if (!closed.compareAndSet(false, true)) {
        return Future.succeededFuture();
    }

    logger.info("Closing {} tracked resources", createdResources.size());

    // Partition into async-closeable and sync-only
    List<Future<Void>> asyncCloses = new ArrayList<>();
    for (AutoCloseable resource : createdResources) {
        if (resource instanceof OutboxConsumer<?> consumer) {
            asyncCloses.add(consumer.closeAsync()
                .onFailure(e -> logger.warn("Error closing consumer: {}", e.getMessage()))
                .recover(e -> Future.succeededFuture()));
        } else if (resource instanceof OutboxConsumerGroup<?> group) {
            asyncCloses.add(group.closeAsync()
                .onFailure(e -> logger.warn("Error closing consumer group: {}", e.getMessage()))
                .recover(e -> Future.succeededFuture()));
        } else {
            // Producers, browsers — sync close is lightweight
            try {
                resource.close();
                logger.debug("Closed resource: {}", resource.getClass().getSimpleName());
            } catch (Exception e) {
                logger.warn("Error closing resource {}: {}",
                    resource.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
    createdResources.clear();

    return Future.all(asyncCloses)
        .mapEmpty()
        .onSuccess(v -> logger.info("OutboxFactory closed successfully"));
}
```

Key design decisions:
- **`instanceof` for type discrimination** — simpler than introducing a new `AsyncCloseable`
  interface across all resource types.
- **`AtomicBoolean closed`** — replaces the current `volatile boolean closed` + `synchronized`
  pattern on `close()`. The method returns a Future so it cannot be `synchronized`.
- **`Future.all()`** — closes consumers/groups in parallel (no ordering dependency between
  them). Each future is individually recovered so one failure doesn't block others.
- **`OutboxConsumerGroup.closeAsync()` is package-private** — works because
  `closeTrackedResourcesAsync()` is in the same package (`dev.mars.peegeeq.outbox`).

**Step 2: Wire the close hook**

Replace the no-op in the constructor:

```java
registrar.registerCloseHook(new PeeGeeQCloseHook() {
    @Override
    public String name() { return "outbox"; }

    @Override
    public io.vertx.core.Future<Void> closeReactive() {
        return closeTrackedResourcesAsync();
    }
});
```

**Step 3: Delegate `close()` to the async method**

Replace the current sync `close()`:

```java
@Override
public void close() throws Exception {
    assertNotEventLoopForBlocking("close()", "close this factory from a worker thread");
    closeTrackedResourcesAsync().await();
}
```

This eliminates the duplicated resource-closing loop. Both the close hook (async path)
and explicit `close()` (sync path) share the same idempotent implementation.
`closeLegacy()` continues to delegate to `close()`, unchanged.

**Step 4: Change `closed` field from `volatile boolean` to `AtomicBoolean`**

```java
// Before:
private volatile boolean closed = false;

// After:
private final AtomicBoolean closed = new AtomicBoolean(false);
```

Update `checkNotClosed()` to use `closed.get()`.

#### Checklist

- [ ] `closed` field: `volatile boolean` → `AtomicBoolean`
- [ ] Add `closeTrackedResourcesAsync()` method
- [ ] Wire close hook `closeReactive()` → `closeTrackedResourcesAsync()`
- [ ] `close()` delegates to `closeTrackedResourcesAsync().await()`
- [ ] `checkNotClosed()` updated to `closed.get()`
- [ ] `isHealthy()`/`getStats()`/`getPoolBlocking()` — verify they use `checkNotClosed()` (no direct `closed` reads to update)
- [ ] Log message updated: `"Registered outbox close hook (no-op)"` → `"Registered outbox close hook"`
- [ ] Compile: `mvn -pl peegeeq-outbox test-compile`
- [ ] Grep: zero uses of `volatile boolean closed` in `OutboxFactory.java`

#### TDD Test Design

Pure TDD: write each RED test first, see it fail, then write the minimum implementation
to make it GREEN. Task 1 does not touch the database — `closeTrackedResourcesAsync()` closes
in-memory resource objects. All tests are CORE (no TestContainers).

The existing `OutboxFactoryUnitTest` uses a `TestDatabaseService` that does NOT implement
`LifecycleHookRegistrar`, so the close hook registration path is skipped. The new tests
need a `TestDatabaseService` variant that implements `LifecycleHookRegistrar` to capture
the registered hook and invoke it.

**Test file:** `OutboxFactoryCloseHookTest.java` (new, CORE)

##### Test Infrastructure

```java
@Tag(TestCategories.CORE)
@DisplayName("OutboxFactory close hook lifecycle")
class OutboxFactoryCloseHookTest {

    /**
     * DatabaseService that also implements LifecycleHookRegistrar,
     * so OutboxFactory's constructor registers the close hook.
     * Captures the hook for test invocation.
     */
    private static class HookCapturingDatabaseService implements DatabaseService, LifecycleHookRegistrar {
        private PeeGeeQCloseHook capturedHook;

        @Override
        public void registerCloseHook(PeeGeeQCloseHook hook) {
            this.capturedHook = hook;
        }

        PeeGeeQCloseHook getCapturedHook() { return capturedHook; }

        // ... minimal DatabaseService stubs (same as existing TestDatabaseService)
    }
}
```

##### RED/GREEN Test Sequence

Each test below is written and run BEFORE the implementation it exercises. The expected
failure mode is noted — this is what we expect to see when the test is RED.

**Test 1: Close hook is registered (not no-op)**

```java
@Test
@DisplayName("Close hook should be registered when DatabaseService is a LifecycleHookRegistrar")
void closeHookIsRegistered() {
    var dbService = new HookCapturingDatabaseService();
    new OutboxFactory(dbService);

    assertNotNull(dbService.getCapturedHook());
    assertEquals("outbox", dbService.getCapturedHook().name());
}
```

- **RED**: Already passes (hook is registered today, just as a no-op). This is a precondition
  test, not a behaviour change. Include it for regression safety.

**Test 2: Close hook closes tracked consumers**

```java
@Test
@DisplayName("Close hook closeReactive() should close tracked consumers")
void closeHookClosesConsumers() {
    var dbService = new HookCapturingDatabaseService();
    OutboxFactory factory = new OutboxFactory(dbService);

    // Create a consumer — adds to createdResources
    MessageConsumer<String> consumer = factory.createConsumer("test-topic", String.class);
    OutboxConsumer<String> outboxConsumer = (OutboxConsumer<String>) consumer;
    assertFalse(outboxConsumer.isClosed());

    // Invoke the close hook (simulates PeeGeeQManager shutdown step 2)
    Future<Void> result = dbService.getCapturedHook().closeReactive();
    result.await();

    assertTrue(outboxConsumer.isClosed());
}
```

- **RED**: Fails because the current hook is a no-op — `closeReactive()` returns
  `succeededFuture()` without closing anything. Consumer remains open.
- **GREEN**: Wire `closeReactive()` → `closeTrackedResourcesAsync()`.

**Test 3: Close hook closes tracked consumer groups**

```java
@Test
@DisplayName("Close hook closeReactive() should close tracked consumer groups")
void closeHookClosesConsumerGroups() {
    var dbService = new HookCapturingDatabaseService();
    OutboxFactory factory = new OutboxFactory(dbService);

    ConsumerGroup<String> group = factory.createConsumerGroup("test-topic", "test-group", String.class);
    OutboxConsumerGroup<String> outboxGroup = (OutboxConsumerGroup<String>) group;
    assertNotEquals(OutboxConsumerGroup.State.CLOSED, outboxGroup.getState());

    Future<Void> result = dbService.getCapturedHook().closeReactive();
    result.await();

    assertEquals(OutboxConsumerGroup.State.CLOSED, outboxGroup.getState());
}
```

- **RED**: Same — no-op hook doesn't close anything.
- **GREEN**: Already satisfied by `closeTrackedResourcesAsync()` from Test 2.

**Test 4: Close hook is idempotent**

```java
@Test
@DisplayName("Close hook closeReactive() should be idempotent")
void closeHookIsIdempotent() {
    var dbService = new HookCapturingDatabaseService();
    OutboxFactory factory = new OutboxFactory(dbService);

    factory.createConsumer("test-topic", String.class);

    // Call twice
    dbService.getCapturedHook().closeReactive().await();
    Future<Void> second = dbService.getCapturedHook().closeReactive();
    second.await();

    // Second call succeeds without error
    assertTrue(second.succeeded());
}
```

- **RED**: Fails because current `closed` is `volatile boolean` with `synchronized close()`.
  The hook was a no-op and never set `closed`. After wiring, the first call sets
  `closed` via `compareAndSet`, but the field type must be `AtomicBoolean` for
  `compareAndSet` to exist.
- **GREEN**: Change `closed` to `AtomicBoolean`, `closeTrackedResourcesAsync()` uses
  `compareAndSet(false, true)`.

**Test 5: Factory rejects operations after close hook fires**

```java
@Test
@DisplayName("Factory should reject createProducer after close hook fires")
void factoryRejectsOperationsAfterCloseHook() {
    var dbService = new HookCapturingDatabaseService();
    OutboxFactory factory = new OutboxFactory(dbService);

    dbService.getCapturedHook().closeReactive().await();

    assertThrows(IllegalStateException.class, () ->
        factory.createProducer("test-topic", String.class));
}
```

- **RED**: Fails because the no-op hook never sets `closed = true`, so `checkNotClosed()`
  passes and the factory accepts the call.
- **GREEN**: `closeTrackedResourcesAsync()` sets `closed` via `compareAndSet`, and
  `checkNotClosed()` reads `closed.get()`.

**Test 6: Sync `close()` delegates to async path**

```java
@Test
@DisplayName("Sync close() should close tracked consumers (same as hook)")
void syncCloseClosesConsumers() throws Exception {
    var dbService = new HookCapturingDatabaseService();
    OutboxFactory factory = new OutboxFactory(dbService);

    MessageConsumer<String> consumer = factory.createConsumer("test-topic", String.class);
    OutboxConsumer<String> outboxConsumer = (OutboxConsumer<String>) consumer;

    factory.close();

    assertTrue(outboxConsumer.isClosed());
}
```

- **RED**: Already passes today (existing `close()` loop calls `resource.close()`). Include
  as regression test to verify delegation doesn't break the sync path.

**Test 7: Close hook and sync close() are safe to call in either order**

```java
@Test
@DisplayName("Calling close hook then close() should not error")
void closeHookThenSyncClose() throws Exception {
    var dbService = new HookCapturingDatabaseService();
    OutboxFactory factory = new OutboxFactory(dbService);

    factory.createConsumer("test-topic", String.class);

    // Hook fires first (manager shutdown), then explicit close() later
    dbService.getCapturedHook().closeReactive().await();
    assertDoesNotThrow(factory::close);
}

@Test
@DisplayName("Calling close() then close hook should not error")
void syncCloseThenCloseHook() throws Exception {
    var dbService = new HookCapturingDatabaseService();
    OutboxFactory factory = new OutboxFactory(dbService);

    factory.createConsumer("test-topic", String.class);

    // Explicit close() first, then hook fires during manager shutdown
    factory.close();
    Future<Void> hookResult = dbService.getCapturedHook().closeReactive();
    hookResult.await();
    assertTrue(hookResult.succeeded());
}
```

- **RED**: Fails because current `close()` is `synchronized` with `volatile boolean closed`,
  but the hook path doesn't touch `closed` at all. After both are wired to
  `closeTrackedResourcesAsync()`, the `AtomicBoolean` guard makes both idempotent.
- **GREEN**: Both paths delegate to `closeTrackedResourcesAsync()` with `compareAndSet`.

##### RED/GREEN Execution Order

| Step | Action | Expected |
|-----:|--------|----------|
| 1 | Write Tests 1–7 in `OutboxFactoryCloseHookTest.java` | — |
| 2 | Run tests | Tests 2, 3, 4, 5, 7 RED; Tests 1, 6 GREEN |
| 3 | Change `closed` to `AtomicBoolean`, update `checkNotClosed()` | Tests still RED (hook is still no-op) |
| 4 | Add `closeTrackedResourcesAsync()` method | Tests still RED (not wired yet) |
| 5 | Wire close hook → `closeTrackedResourcesAsync()` | Tests 2, 3, 4, 5 GREEN |
| 6 | Delegate `close()` → `closeTrackedResourcesAsync().await()` | Test 7 GREEN |
| 7 | All 7 tests GREEN | Done |

### Change 2: Clean up isShutdownRelatedError — remove the hacks

**File:** `OutboxConsumer.java`, lines 410-443

Remove the two string-matching blocks and their side-effects. Keep only:

```java
private boolean isShutdownRelatedError(Throwable error) {
    if (closed.get()) {
        return true;
    }
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
        if (cause instanceof RejectedExecutionException
                || cause instanceof ClosedChannelException) {
            return true;
        }
    }
    return false;
}
```

This is a pure predicate. No side-effects. No string coupling. If the factory close hook
works correctly, `closed.get()` will already be true before any pool errors can occur.

### Change 3: Remove processAvailableMessages hack

**File:** `OutboxConsumer.java`, lines 295-308

Remove the `databaseService.getConnectionProvider() == null` early-close block. With the
factory close hook working, the consumer's `closed` flag is already set before the
connection provider is torn down, so the existing `closed.get()` check at the top of
the method handles it.

### Change 4: Replace ScheduledExecutorService with Vert.x timers

**File:** `OutboxConsumer.java`

Replace the `ScheduledExecutorService scheduler` and `ExecutorService messageProcessingExecutor`
with Vert.x primitives:

- Replace `scheduler.scheduleWithFixedDelay(...)` with `vertx.setPeriodic(intervalMs, id -> ...)`
- Remove `messageProcessingExecutor` — message processing already uses `Future` composition
  which runs on the Vert.x event loop; no separate thread pool is needed.
- Add a `Vertx` field, obtained from `DatabaseService` or passed through the constructor.
- `closeAsync()` cancels the timer via `vertx.cancelTimer(timerId)` instead of
  `scheduler.shutdown()`.

This eliminates the entire class of "polling loop survives Vert.x shutdown" bugs. Even
if the close hook somehow fails, `vertx.close()` in step 6 cancels all timers.

## Change 5: Replace ScheduledExecutorService in filter-retry chain with Vert.x timers

### Codebase-Wide Executor Audit Results

A full-codebase audit found 6 production files using `java.util.concurrent` executors
outside Vert.x lifecycle control. Two are acceptable; four must migrate.

### MUST MIGRATE — Same lifecycle vulnerability as OutboxConsumer

**1. OutboxConsumerGroup.java** (line 87)
- `ScheduledExecutorService sharedFilterScheduler`
- Created: `Executors.newScheduledThreadPool(1)`, daemon thread `"filter-retry-{groupName}"`
- Shared with all members via `getSharedFilterScheduler()`
- Shutdown: `closeAsync()` lines 627-634
- **Risk**: Executor runs independently of Vert.x lifecycle. If `closeAsync()` is never
  called (the same close-hook problem), this scheduler survives Vert.x shutdown and keeps
  scheduling retries against destroyed pools.

**2. OutboxConsumerGroupMember.java** (line 77)
- `ScheduledExecutorService filterScheduler` + `boolean ownsScheduler`
- Either receives shared scheduler from parent group, or creates own fallback via
  `Executors.newScheduledThreadPool(1)`, daemon thread `"filter-retry-{consumerId}"`
- Passed to `FilterRetryManager` constructor
- Shutdown: `close()` lines 301-311, only if `ownsScheduler` is true
- **Risk**: Fallback-created schedulers (when no parent group) have no external lifecycle
  management.

**3. FilterRetryManager.java** (line 52, resilience package)
- Injected `ScheduledExecutorService scheduler`
- Usage: `scheduler.schedule(...)` for retry delays with exponential backoff
- **No shutdown method** — lifecycle owned by caller
- Javadoc (lines 22-47) claims "intentional" use for "framework agnosticism" and
  "testability" — this rationale is wrong for a pure Vert.x system. PeeGeeQ is not
  framework-agnostic. Testability is already provided by `VertxTestContext`.
- **Risk**: Low standalone (doesn't own executor), but part of the chain. Must migrate
  together with callers.

**4. AsyncFilterRetryManager.java** (lines 27-28, resilience package)
- `ScheduledExecutorService retryScheduler` — 2 threads, daemon `"async-retry-scheduler-{filterId}"`
- `ExecutorService filterExecutor` — cached (unbounded) thread pool, daemon `"async-filter-executor-{filterId}"`
- Usage: `retryScheduler.schedule()` for retry delays, `filterExecutor.submit()` to run
  filter predicates off event loop
- Shutdown: proper `shutdown`/`awaitTermination`/`shutdownNow` in `shutdown()` method
- **Risk**: Highest-risk item. Two independent executors per instance, both surviving Vert.x
  shutdown. The cached thread pool is unbounded — under load, unlimited threads outside
  Vert.x control.

### ACCEPTABLE — No migration needed

**5. HealthCheckManager.java** (peegeeq-db, line 62)
- `ExecutorService healthCheckExecutor` — bounded `ThreadPoolExecutor`
- Already uses Vert.x for scheduling (`vertx.setPeriodic` and `vertx.setTimer`)
- The executor runs blocking `HealthCheck.check()` implementations off the event loop —
  correct pattern for blocking I/O
- Proper lifecycle in `stop()` with `shutdownNow()` + `awaitTermination(5s)`
- **Verdict**: Legitimate use. No change needed.

**6. OutboxFactory.java** (lines 304, 362, 528)
- `toCompletionStage().toCompletableFuture().get()` blocking bridges
- All methods call `assertNotEventLoopForBlocking()` — intentional blocking API for
  worker-thread callers
- **Verdict**: Acceptable. Not an executor lifecycle issue.

### Filter-Retry Dependency Graph

```
OutboxConsumerGroup
  └── sharedFilterScheduler (ScheduledExecutorService)
       └── passed to each OutboxConsumerGroupMember
            └── passed to FilterRetryManager (injected)

AsyncFilterRetryManager (independent)
  ├── retryScheduler (ScheduledExecutorService, 2 threads)
  └── filterExecutor (ExecutorService, unbounded cached)
```

### Migration Approach

All 4 "must migrate" classes need coordinated change:

- **FilterRetryManager**: Change constructor to accept `Vertx` instead of
  `ScheduledExecutorService`. Replace `scheduler.schedule(runnable, delay, unit)` with
  `vertx.setTimer(delayMs, id -> runnable.run())`.

- **AsyncFilterRetryManager**: Replace `retryScheduler` with `vertx.setTimer()`. Replace
  `filterExecutor.submit()` with `vertx.executeBlocking()` or direct Future composition
  on the event loop. Remove both executor fields and `shutdown()` method.

- **OutboxConsumerGroupMember**: Remove `filterScheduler` and `ownsScheduler` fields.
  Pass `Vertx` to `FilterRetryManager` instead. Remove executor shutdown in `close()`.

- **OutboxConsumerGroup**: Remove `sharedFilterScheduler` field and
  `getSharedFilterScheduler()` accessor. Remove executor shutdown in `closeAsync()`.
  Pass `Vertx` (from `DatabaseService.getVertx()`) through to members.

With this migration, Vert.x timer cancellation in step 6 of the shutdown sequence
guarantees all retry timers stop — even if the close hook fails.

## Execution Order

See **Task Tracker** at the top of this document. Tasks are numbered and dependency-ordered.

**Critical path**: Task 1 (close hook) → Tasks 2 + 3 (cleanup) → Task 6 (compile) → Task 7 (verify)

**Parallel track**: Tasks 5a → 5b/5c → 5d (filter-retry chain migration) can proceed
independently of Tasks 1–3, but all must be complete before Task 6.

## Expected Outcome

- Consumer polling stops cleanly during step 2 of manager shutdown
- No "Client not found" errors because consumers are already closed before pools are destroyed
- `isShutdownRelatedError()` is a clean predicate with no string-matching or side-effects
- The race window from the original plan (poll fires between close hook start and
  `closed.set(true)`) is eliminated: Vert.x timer cancellation is synchronous within
  the event loop, so no poll can fire after `vertx.cancelTimer()` returns

## Files Changed

| File | What |
|---|---|
| `OutboxFactory.java` | Close hook calls `closeTrackedResourcesAsync()`, new method extracted, `close()` delegates |
| `OutboxConsumer.java` | `isShutdownRelatedError()` cleaned up, `processAvailableMessages()` hack removed, `ScheduledExecutorService` replaced with `Vertx.setPeriodic()`, `messageProcessingExecutor` removed |
| `OutboxConsumerGroup.java` | Remove `sharedFilterScheduler` field + accessor + shutdown code, pass `Vertx` to members |
| `OutboxConsumerGroupMember.java` | Remove `filterScheduler` + `ownsScheduler` fields + shutdown code, pass `Vertx` to `FilterRetryManager` |
| `FilterRetryManager.java` | Accept `Vertx` instead of `ScheduledExecutorService`, use `vertx.setTimer()` for retry delays, remove framework-agnostic Javadoc |
| `AsyncFilterRetryManager.java` | Replace `retryScheduler` + `filterExecutor` with Vert.x timers + `executeBlocking()`, remove `shutdown()` |

## What This Does NOT Change

- `scheduledProcessMessages()` error handling — logging + continuing is correct for
  transient operational errors. The fix ensures shutdown errors don't reach this code path.
- `OutboxConsumer` does not register itself as a close hook — the factory is the right
  level to manage consumer lifecycle.
- `PgConnectionProvider.getReactivePool()` — the error it throws is legitimate for the
  "someone asks for a pool that doesn't exist" case. The fix ensures consumers don't ask
  after pools are gone.
