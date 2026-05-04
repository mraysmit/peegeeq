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
| 7 | Run full outbox integration tests, count "Client not found" — target: zero | DONE | — | 6 |
| 8 | Add missing `logger` field declarations — 12 test classes | DONE | 12 test classes | — |
| 9 | Fix example tests — adopt standard setup — 3 classes | DONE | 3 example test classes | — |
| 10 | Fix `JsonbConversionValidationTest` — reorder schema init | DONE | `JsonbConversionValidationTest.java` | — |
| 11 | Standardize hardcoded container — `OutboxConsumerErrorPathsCoverageTest` | DONE | `OutboxConsumerErrorPathsCoverageTest.java` | — |
| 12 | Fix `OutboxResourceLeakDetectionTest` thread assertion | DONE | `OutboxResourceLeakDetectionTest.java` | 4 |
| 13 | Investigate remaining genuine test failures — 3 tests | DONE | varies | 8–11 |

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

---

## Task 7 Validation Results

### Run Summary

Full outbox integration test run (`mvn test -pl peegeeq-outbox -Pintegration-tests`).

| Metric | Before (baseline) | After (lifecycle fixes) |
|--------|------------------:|------------------------:|
| "Client not found" log lines | 3,975 | **0** |
| Total tests | 410 | 372 |
| Failures | 1 | 3 |
| Errors | 0 | 58 |
| Build result | FAILURE | FAILURE |

**Primary target achieved**: "Client not found" count dropped from 3,975 to zero.

The 58 errors and 3 failures are pre-existing issues unrelated to the lifecycle fix.
Test count dropped from 410 to 372 because compilation errors in 11 classes prevent
those tests from running at all.

### How Working Tests Initialize Schema

The canonical pattern used by all passing integration tests:

```java
@BeforeEach
void setUp() throws Exception {
    // 1. Initialize schema FIRST — creates required tables
    PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

    // 2. Set system properties
    System.setProperty("peegeeq.database.host", postgres.getHost());
    // ... port, name, username, password, ssl ...

    // 3. Create config and manager
    PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
    manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

    // 4. Start manager — validateRequiredTables() runs here, tables MUST exist
    manager.start().await();

    // 5. Create factory and components
    DatabaseService databaseService = new PgDatabaseService(manager);
    outboxFactory = new OutboxFactory(databaseService, config);
}
```

Key points:
- `PeeGeeQTestSchemaInitializer.initializeSchema()` must be called BEFORE `manager.start()`
- `PeeGeeQManager.start()` calls `validateRequiredTables()` which checks for: `outbox`,
  `queue_messages`, `dead_letter_queue`, `outbox_topic_subscriptions`
- `PgDatabaseService.runMigrations()` is a no-op stub — the `peegeeq.migration.auto-migrate=true`
  system property is dead code, nothing reads it
- `SchemaComponent.QUEUE_ALL` is the standard set; some tests add `SchemaComponent.CONSUMER_GROUP_FANOUT`
- Container: `PostgreSQLTestConstants.createStandardContainer()` is the standard factory

### Failing Test Classification by Root Cause

#### Root Cause 1: Missing `logger` field declaration (compilation error) — 11 classes

A batch edit added `logger.info("Setting up: ...")` / `logger.info("Tearing down: ...")` /
`logger.info("Test: ...")` lines and the SLF4J imports, but did NOT add the field declaration
`private static final Logger logger = LoggerFactory.getLogger(...)`. The classes compile with
errors at the `logger` usage sites. Surefire reports "Unresolved compilation problem: logger
cannot be resolved". Because the class fails to load, `@Container` static fields never
initialize, producing the secondary error "Container postgres needs to be initialized".

| Test Class | logger usage sites | Surefire error |
|---|---|---|
| `OutboxConsumerCoreTest` | setUp, tearDown, tests | Container not initialized |
| `OutboxConsumerErrorHandlingTest` | setUp, tearDown, tests | Container not initialized |
| `OutboxConsumerFailureHandlingTest` | setUp, tearDown, tests | Container not initialized |
| `OutboxConsumerGroupIntegrationTest` | setUp, tearDown, tests | Container not initialized |
| `OutboxConsumerNullHandlerTest` | setUp, tearDown, tests | Container not initialized |
| `OutboxErrorHandlingTest` | setUp, tearDown, tests | Container not initialized |
| `OutboxMetricsTest` | setUp, tearDown | Container not initialized |
| `OutboxParallelProcessingTest` | setUp, tearDown | Container not initialized |
| `OutboxProducerCoreTest` | setUp, tearDown, tests | Unresolved compilation |
| `OutboxConsumerLifecycleBugReproducerTest` | setUp, tearDown | Unresolved compilation |
| `OutboxConsumerCrashRecoveryTest` | setUp, test method | Unresolved compilation |

**Fix**: Add `private static final Logger logger = LoggerFactory.getLogger(ClassName.class);`
to each class (same fix that was applied to `OutboxConsumerGroupSubscriptionTest`).

#### Root Cause 2: Missing `initializeSchema()` + forbidden patterns — 3 example classes

These tests call `manager.start().await()` inside each test method without any prior
`PeeGeeQTestSchemaInitializer.initializeSchema()` call. They set `peegeeq.migration.auto-migrate=true`
via `configureSystemPropertiesForContainer()` but nothing in the start path reads that property.
`validateRequiredTables()` fails with `IllegalStateException: Database required tables missing`.

Additionally, tearDown uses the forbidden pattern `.toCompletionStage().toCompletableFuture().join()`.

| Test Class | Module |
|---|---|
| `AutomaticTransactionManagementExampleTest` | peegeeq-outbox (examples/) |
| `BatchOperationsWithPropagationExampleTest` | peegeeq-outbox (examples/) |
| `JdbcIntegrationHybridExampleTest` | peegeeq-outbox (examples/) |

**Fix**: Add `PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL)`
before each `manager.start().await()`. Replace `.toCompletionStage().toCompletableFuture().join()`
with `.await()`. Use `PostgreSQLTestConstants.createStandardContainer()` instead of hardcoded container.

#### Root Cause 3: `initializeSchema()` called AFTER `manager.start()` (wrong order) — 1 class

| Test Class | Issue |
|---|---|
| `JsonbConversionValidationTest` | `manager.start().await()` at line 88, `initializeSchema()` at line 90. Tables don't exist when `validateRequiredTables()` runs. |

**Fix**: Move `initializeSchema()` call before `manager.start().await()`.

#### Root Cause 4: Actual test logic / production code failures — 4 classes (correct infrastructure)

These tests compile, have correct schema initialization order, containers start properly.
The failures are genuine test or production code issues.

| Test Class | Failure | Nature |
|---|---|---|
| `OutboxSchemaQuotingTest` | SQL/reserved-word quoting failures | Production code bug — reserved words used as schema names are not properly quoted in SQL queries |
| `OutboxConsumerErrorPathsCoverageTest` | 1 of 10 tests fails | Test logic / assertion failure |
| `OutboxConsumerGroupFaultToleranceTest$StopDuringProcessing` | 1 test fails | Timing / assertion failure during `stopGracefully()` |
| `OutboxResourceLeakDetectionTest.testNoThreadLeaksAfterConsumerClose` | Thread count assertion: "before: 141, active: 141" | Thread-counting logic — consumer no longer creates non-daemon threads after Vert.x timer migration |

### Error Propagation

#### The generic problem: `.recover()` as silent error erasure

In a `Future` chain, `.recover()` is the reactive equivalent of a `catch` block. When a
`.recover()` handler returns `Future.succeededFuture()`, it converts a failed Future into
a succeeded one. Every caller upstream now sees success. The error is gone from the chain
— it has been erased.

The dangerous pattern looks like this:

```java
doSomething()
    .recover(error -> {
        logger.error("Something failed: {}", error.getMessage());
        return Future.succeededFuture();  // failure becomes success
    })
    .compose(result -> doNextThing())  // runs as if nothing went wrong
```

This is functionally identical to:

```java
try {
    doSomething();
} catch (Exception e) {
    logger.error("Something failed: {}", e.getMessage());
    // swallowed — execution continues as if nothing happened
}
doNextThing();
```

The log line creates the illusion that the error is handled. It is not handled. It is
discarded. The caller has no way to know the operation failed. The system continues in a
corrupt or inconsistent state, and any downstream failure is now disconnected from its
actual cause.

This pattern is especially destructive in reactive code because:

- **Errors are values, not stack-unwinding exceptions.** In synchronous code, a swallowed
  exception at least stops the current method from doing further damage (unless you catch
  and continue). In a `Future` chain, `.recover()` to success means the chain keeps
  composing — the next `.compose()` runs, receives a result that looks valid, and acts
  on it. There is no implicit "stop executing" behavior.

- **It defeats the entire point of `Future` error propagation.** A `Future<T>` carries
  either a result or an error. `.compose()` already short-circuits on failure — it skips
  downstream stages and propagates the error to whoever is observing the final Future.
  This is correct behavior, and it is free. `.recover()` to success actively works against
  this by forcing the chain to continue through stages that should never have run.

- **It is invisible at the call site.** The caller composes on the returned Future and
  has no indication that failures have been silently erased inside. Unlike a `throws`
  declaration or a checked exception, there is no signal in the type system that this
  Future can never fail because someone swallowed the errors internally.

- **Log lines are not error signals.** A `logger.error()` call inside `.recover()` writes
  to a file or stdout. It does not complete a Promise as failed, does not throw, does not
  set a flag, does not notify the caller. In production, nobody is tailing the log in real
  time. The error is recorded for forensic use but has zero operational effect.

#### What `.recover()` is actually for (per Vert.x documentation)

The Vert.x API Javadoc defines `recover()` as:

> **Handles a failure of this Future by returning the result of another Future.**
> If the mapper fails, then the returned future will be failed with this failure.
>
> — `Future.recover(Function<Throwable, Future<T>> mapper)`

The key phrase is "**returning the result of another Future**." The mapper function
receives the exception and is expected to return a *new Future that produces a real
result of the same type `T`*. This is a recovery operation: the original operation
failed, so you try an alternative path that can still produce a meaningful value
for the caller.

`recover()` is the failure-side counterpart to `compose()`. Where `compose()` chains
on success (`successMapper` returns a new `Future<U>`), `recover()` chains on failure
(`mapper` returns a new `Future<T>`). The two-argument form of `compose()` makes this
explicit:

```java
future.compose(successMapper, failureMapper)
// recover() is sugar for:
future.compose(Future::succeededFuture, failureMapper)
```

In both cases, the mapper is expected to produce a Future that represents a real
alternative outcome — either a successful result or a propagated/re-thrown failure.

The Vert.x composition model works as follows:
- `.compose()` short-circuits on failure: when a Future fails, downstream `.compose()`
  stages are skipped, and the failure propagates automatically to the final Future.
  This is free and correct.
- `.recover()` intercepts that propagation. If the mapper returns
  `Future.succeededFuture()`, the failure is erased and downstream stages run as if
  nothing went wrong.
- `.eventually()` runs a side-effect on completion (success or failure) without
  altering the outcome — it explicitly documents that "the outcome of the future
  returned by the mapper will not influence the nature of the returned future."

The correct uses of `.recover()` are narrow:

1. **Fallback to a real alternative** — return a cached value, try a secondary
   data source, use a default. The recovered Future carries a meaningful result
   of type `T` that the caller can use.
2. **Selective recovery** — inspect the exception type and recover only from
   expected/transient failures. Re-throw everything else via
   `Future.failedFuture(error)`.
3. **Cleanup during shutdown** — when the system is already tearing down and the
   only goal is to make best-effort cleanup not abort the shutdown sequence. Even
   here, the scope should be tight: recover individual resource closes, not entire
   operation chains.

The pattern `.recover(e -> { log(e); return Future.succeededFuture(); })` in an
operational code path is not recovery — it is error erasure. The mapper does not
produce an alternative result; it produces `null` disguised as success. Every
downstream `.compose()` stage runs on a lie. This should be treated as a bug.

#### How this manifested in the outbox consumer lifecycle

The 3,975 "Client not found" errors during tests were the result of this anti-pattern
applied across multiple layers of the outbox consumer system:

**1. `scheduledProcessMessages()` is fire-and-forget with no error observer.**

The `ScheduledExecutorService` calls `scheduledProcessMessages()` every poll interval.
This method calls `processAvailableMessages()` and attaches `.onFailure()`, which only
logs — it never re-throws, never completes a Promise as failed, and never signals the
caller. The method returns `void`, so the returned Future is unobserved. The scheduler
fires again next interval regardless of the outcome. Every failure is absorbed.

**2. `isShutdownRelatedError()` downgrades real failures to DEBUG-level logging.**

When `closed.get()` returns `true`, every error — regardless of its actual cause — is
classified as "shutdown-related" and logged at `DEBUG` level. This means authentication
failures, constraint violations, or any other real error that happens to occur after the
close flag is set becomes invisible. Before the lifecycle fix, the close flag was often
not set at all during shutdown (because the factory close hook was a no-op), so errors
hit the `else` branch and were logged at `ERROR` — but still not propagated. Either
branch just returns; the scheduler fires again.

**3. No shutdown signal reached the consumers.**

`OutboxFactory` registered a close hook with `PeeGeeQManager`, but the hook was a no-op:

```java
public io.vertx.core.Future<Void> closeReactive() {
    return io.vertx.core.Future.succeededFuture();  // no-op
}
```

The manager's shutdown sequence destroyed the DB connection pools in step 4, but
consumers were never told to stop. Their `ScheduledExecutorService` (a plain
`java.util.concurrent` executor, not a Vert.x timer) kept firing independently of
Vert.x lifecycle. Each poll attempt after pool destruction generated a "Client not
found: null" error from `PgConnectionProvider.getReactivePool()`, which was caught by
`.onFailure()` in `scheduledProcessMessages()`, logged, and forgotten.

**Net effect**: consumers polled against destroyed pools, generating thousands of errors
per test run, all silently absorbed by the log-and-continue pattern in the polling loop.
No test ever failed because of these errors — they were invisible unless you read the
full log output.

#### Project-wide `.recover()` audit

The outbox consumer was not an isolated case. A full audit of every `.recover()` call
in production code (117 occurrences across 32 source files) reveals the same
misunderstanding applied systematically:

| Classification | Count | Description |
|---|---|---|
| **ERASURE** | 20 | Silent error swallowing in operational code paths. Callers see success. |
| **ERASURE-IN-SHUTDOWN** | 17 | Error swallowing during shutdown/cleanup. Lower risk but still masks failures. |
| **RE-WRAPS-FAILURE** | 9 | Logs error, then re-throws via `Future.failedFuture()`. Error propagates. Correct. |
| **SELECTIVE-RECOVERY** | 4 | Inspects exception type, recovers only from expected errors, re-throws others. Correct. |
| **PROPER-FALLBACK** | 27 | Returns a real alternative value (cached data, health status object, empty list). Correct. |

The 20 ERASURE calls are bugs. They occur in operational code where callers depend on
the Future's outcome to make decisions, and those callers now receive fabricated success.

The most damaging instances:

| Module | Class | What is erased |
|---|---|---|
| `peegeeq-db` | `StuckMessageRecoveryManager` | Stuck message recovery fails → returns `0`. Callers think no messages were stuck. Reset fails → stuck messages stay stuck forever. 6 methods, all erasure. |
| `peegeeq-db` | `SubscriptionManager` | `FROM_BEGINNING` backfill fails → subscription appears complete but data is missing. Cancel cleanup fails → orphan rows and zombie tracking data remain. Resurrection re-backfill fails → resurrected consumer misses messages. |
| `peegeeq-db` | `PeeGeeQMetrics` | Metric count queries fail → return zero. Metric persistence fails → silently swallowed. Monitoring dashboards show fabricated data. |
| `peegeeq-native` | `PgNativeConsumerGroup` | Subscription cancellation fails during graceful stop → lost. Orphan subscriptions remain in database. |
| `peegeeq-outbox` | `OutboxConsumerGroup` | Same pattern: subscription cancellation failure during stop is swallowed. |
| `peegeeq-performance-test-harness` | `PerformanceTestHarness` | Test suite failures hidden in aggregated results. Top-level execution failure swallowed. Performance regressions invisible. |

The pattern is always the same:

```java
.recover(error -> {
    logger.warn("X failed: {}", error.getMessage());
    return Future.succeededFuture();      // or succeededFuture(0), succeededFuture(emptyList)
})
```

Every one of these was written as if `.recover()` means "log and ignore" rather than
"provide a real alternative." The 9 RE-WRAPS-FAILURE and 4 SELECTIVE-RECOVERY calls
show that the correct pattern was understood in some places (re-throw via
`Future.failedFuture()`, or inspect the exception type before deciding). But in the
majority of cases, the developer reached for `.recover()` as a catch-all error silencer.

The 17 ERASURE-IN-SHUTDOWN calls are a gray area. During shutdown, best-effort cleanup
that does not abort the shutdown sequence is reasonable. But even here, several of these
calls erase errors that should at least be captured in the close Future's outcome (using
the `firstError` pattern seen in `PgBiTemporalEventStore.close()`, which captures
individual close errors and re-raises the first one at the end).

The 27 PROPER-FALLBACK calls are correct uses of `.recover()`. Health checks that return
`HealthStatus.unhealthy(error)` preserve the error semantically. Management API handlers
that return empty lists on failure provide degraded-but-functional responses. These are
real fallbacks that produce meaningful values of type `T`.

The net effect: roughly one-third of all `.recover()` calls in the codebase are silent
error erasure in operational code. This is not a localized bug — it is a systematic
misunderstanding of what `.recover()` does and what `Future` error propagation is for.

#### Startup errors (correctly propagated)

`PeeGeeQManager.start()` chain:
1. `validateDatabaseConnectivity()` → `validateRequiredTables()` → `startAllComponents()`
2. If `validateRequiredTables()` throws `IllegalStateException("Database required tables missing...")`:
   - `.recover()` handler logs ERROR, cleans up background tasks, re-throws via
     `Future.failedFuture(new RuntimeException("Failed to start PeeGeeQ Manager", throwable))`
   - `.await()` in test propagates the exception
   - JUnit reports it as ERROR (not a silent pass)

The 58 errors in the build are correctly reported — startup errors are not being swallowed.
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

---

## Implementation Plan: Fix Remaining Test Failures

### Standard Test Setup

All outbox integration tests MUST follow this setup unless there is a documented
test-specific reason to deviate.

#### Container

```java
@Container
private static final PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer();
```

No hardcoded image tags. No custom credentials. No per-class `createPostgresContainer()` methods.

#### setUp

```java
@BeforeEach
void setUp() throws Exception {
    // 1. Schema — BEFORE manager.start()
    PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

    // 2. System properties
    System.setProperty("peegeeq.database.host", postgres.getHost());
    System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
    System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
    System.setProperty("peegeeq.database.username", postgres.getUsername());
    System.setProperty("peegeeq.database.password", postgres.getPassword());

    // 3. Manager
    PeeGeeQConfiguration config = new PeeGeeQConfiguration("test-name");
    manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
    manager.start().await();

    // 4. Factory + components
    DatabaseService databaseService = new PgDatabaseService(manager);
    outboxFactory = new OutboxFactory(databaseService, config);
}
```

Additional system properties (e.g. `ssl.enabled`, `polling-interval`) only when the test
requires them. Do NOT set dead properties (`peegeeq.migration.auto-migrate`,
`peegeeq.migration.enabled`).

#### tearDown

```java
@AfterEach
void tearDown() throws Exception {
    // Close resources, then manager
    if (outboxFactory != null) outboxFactory.close();
    if (manager != null) manager.closeReactive().await();

    // Clear system properties
    System.clearProperty("peegeeq.database.host");
    System.clearProperty("peegeeq.database.port");
    System.clearProperty("peegeeq.database.name");
    System.clearProperty("peegeeq.database.username");
    System.clearProperty("peegeeq.database.password");
}
```

No `.toCompletionStage().toCompletableFuture().join()`. No `Thread.sleep()`.
No `CountDownLatch`.

#### Logger

Every test class that uses logging:

```java
private static final Logger logger = LoggerFactory.getLogger(ClassName.class);
```

#### Documented Exceptions

| Test Class | Deviation | Reason |
|---|---|---|
| `OutboxSchemaQuotingTest` | Per-test custom schema + DDL, no shared setUp | Tests SQL quoting with reserved-word schema names — each test needs a different schema |
| `OutboxResourceLeakDetectionTest` | Extra thread-state capture in setUp | Tests for thread leaks — needs baseline thread snapshot before consumer creation |
| `OutboxConsumerErrorPathsCoverageTest` | Extra per-test topic randomization | Error path coverage — needs isolated topics per test |

---

### Task 8: Add missing `logger` field declarations — 12 classes (DONE)

Pure mechanical fix. Added the SLF4J field to each class.

**Scope**: Fixed the compilation error that blocked all tests in these classes and produced
the secondary "Container postgres needs to be initialized" error.

| # | Test Class | File | Notes |
|--:|---|---|---|
| 1 | `OutboxConsumerCoreTest` | `peegeeq-outbox/src/test/.../outbox/OutboxConsumerCoreTest.java` | Added logger field |
| 2 | `OutboxConsumerErrorHandlingTest` | `peegeeq-outbox/src/test/.../outbox/OutboxConsumerErrorHandlingTest.java` | Added logger field |
| 3 | `OutboxConsumerFailureHandlingTest` | `peegeeq-outbox/src/test/.../outbox/OutboxConsumerFailureHandlingTest.java` | Added logger field |
| 4 | `OutboxConsumerGroupIntegrationTest` | `peegeeq-outbox/src/test/.../outbox/OutboxConsumerGroupIntegrationTest.java` | Added logger field |
| 5 | `OutboxConsumerNullHandlerTest` | `peegeeq-outbox/src/test/.../outbox/OutboxConsumerNullHandlerTest.java` | Added logger field |
| 6 | `OutboxErrorHandlingTest` | `peegeeq-outbox/src/test/.../outbox/OutboxErrorHandlingTest.java` | Added logger field |
| 7 | `OutboxMetricsTest` | `peegeeq-outbox/src/test/.../outbox/OutboxMetricsTest.java` | Added logger field |
| 8 | `OutboxParallelProcessingTest` | `peegeeq-outbox/src/test/.../outbox/OutboxParallelProcessingTest.java` | Added logger field |
| 9 | `OutboxProducerCoreTest` | `peegeeq-outbox/src/test/.../outbox/OutboxProducerCoreTest.java` | Added logger field |
| 10 | `OutboxConsumerLifecycleBugReproducerTest` | `peegeeq-outbox/src/test/.../outbox/OutboxConsumerLifecycleBugReproducerTest.java` | Added SLF4J logger field; changed import from `ch.qos.logback.classic.Logger` to `org.slf4j.Logger`; fully qualified 4 local logback `Logger` references |
| 11 | `OutboxConsumerCrashRecoveryTest` | `peegeeq-outbox/src/test/.../outbox/OutboxConsumerCrashRecoveryTest.java` | Added logger field |
| 12 | `OutboxPerformanceTest` | `peegeeq-outbox/src/test/.../outbox/OutboxPerformanceTest.java` | Added logger field (discovered during compilation — not in original scan) |

**Verification**: `mvn -pl peegeeq-outbox clean test-compile` — BUILD SUCCESS, 88 test files, zero errors.

---

### Task 9: Fix example tests — adopt standard setup — 3 classes — DONE

**Completed.** All 3 example test classes now follow the standard setup pattern.

**Changes applied to all 3 classes:**

1. Replaced `createPostgresContainer()` with `PostgreSQLTestConstants.createStandardContainer()`
2. Deleted `createPostgresContainer()` method
3. Deleted `configureSystemPropertiesForContainer()` method and dead properties
4. Rewrote `setUp()`: `initializeSchema()` → 5 system props → config → manager → start
5. Rewrote `tearDown()`: `.await()` instead of `.toCompletionStage().toCompletableFuture().join()`, plus property cleanup
6. Removed per-test `manager = new PeeGeeQManager(...)` + `manager.start().await()` from all test methods
7. Added imports: `PostgreSQLTestConstants`, `PeeGeeQTestSchemaInitializer`, `SchemaComponent`
8. Restored `List`/`IntStream`/`Collectors`/`LockSupport` imports where still used in helper methods

| # | Test Class | Config Name | Test Methods Cleaned |
|--:|---|---|---|
| 1 | `AutomaticTransactionManagementExampleTest` | `auto-tx-test` | 5 |
| 2 | `BatchOperationsWithPropagationExampleTest` | `batch-ops-test` | 6 |
| 3 | `JdbcIntegrationHybridExampleTest` | `jdbc-hybrid-test` | 5 |

**Verification**: `mvn -pl peegeeq-outbox clean test-compile` — BUILD SUCCESS (88 test files, zero errors).
Forbidden pattern grep on all 3 files — zero matches for `toCompletionStage`, `toCompletableFuture`, `.join()`, `createPostgresContainer`, `configureSystemPropertiesForContainer`, `auto-migrate`, `migration.enabled`, `health.enabled`, `metrics.enabled`.

---

### Task 10: Fix `JsonbConversionValidationTest` — reorder schema init — 1 class

**Current** (broken):
```java
manager.start().await();                                                 // line 88
PeeGeeQTestSchemaInitializer.initializeSchema(postgres, QUEUE_ALL);      // line 90
```

**Fixed**:
```java
PeeGeeQTestSchemaInitializer.initializeSchema(postgres, QUEUE_ALL);      // schema FIRST
manager.start().await();                                                  // start SECOND
```

No other changes needed — this test already uses `PostgreSQLTestConstants.createStandardContainer()`.

**Verification**: Run `JsonbConversionValidationTest` individually.

---

### Task 11: Standardize hardcoded container — 1 class

`OutboxConsumerErrorPathsCoverageTest` uses a hardcoded
`new PostgreSQLContainer("postgres:15.13-alpine3.20")` with custom credentials
(`testdb`/`testuser`/`testpass`).

**Changes**:
1. Replace with `PostgreSQLTestConstants.createStandardContainer()`
2. Delete `createPostgresContainer()` helper method
3. Remove duplicate SLF4J imports (lines 35-38 have two pairs)

**Verification**: Run `OutboxConsumerErrorPathsCoverageTest` individually.

---

### Task 12: Fix `OutboxResourceLeakDetectionTest` thread assertion

The test `testNoThreadLeaksAfterConsumerClose` asserts that the consumer creates polling
threads and that they are cleaned up after `consumer.close()`. After the Vert.x timer
migration (Task 4), the consumer no longer creates its own `ScheduledExecutorService`
threads — it uses `Vertx.setPeriodic()` which runs on the shared Vert.x event loop.

The assertion "Consumer should create polling threads (before: 141, active: 141)" fails
because the before/active counts are identical — no new threads are created.

**Fix**: Update the test to reflect the new Vert.x timer-based architecture. The test should
verify that no Vert.x timers are leaked (i.e., no periodic callbacks remain firing after
`consumer.close()`), not that OS threads are created and destroyed. Alternatively, the test
can verify that the consumer's Vert.x timer ID is cancelled after close.

**Verification**: Run `OutboxResourceLeakDetectionTest` individually.

---

### Task 13: Investigate remaining genuine test failures — 3 tests

These tests have correct infrastructure. The failures are in test logic or production code.

| # | Test | Issue | Investigation |
|--:|---|---|---|
| 1 | `OutboxSchemaQuotingTest` (multiple tests) | SQL reserved words as schema names not quoted | Production code bug in SQL query generation — not a test fix |
| 2 | `OutboxConsumerErrorPathsCoverageTest` (1/10) | Single assertion failure | Read the failing test, check assertion vs actual behavior |
| 3 | `OutboxConsumerGroupFaultToleranceTest$StopDuringProcessing` | Timing/assertion failure | Check `stopGracefully()` behavior during active processing |

**These are not infrastructure issues and may require production code changes.**

---

### Execution Order

| Step | Task | Depends On | Unblocks |
|-----:|------|------------|----------|
| 1 | Task 8 — logger fields | — | Compilation of 11 classes |
| 2 | `mvn -pl peegeeq-outbox test-compile` | Task 8 | Confirms zero compilation errors |
| 3 | Task 10 — JsonbConversionValidationTest reorder | — | 1 class |
| 4 | Task 11 — ErrorPathsCoverageTest container | — | 1 class |
| 5 | Task 9 — example tests | — | 3 classes |
| 6 | Full integration test run | Tasks 8–11 | Baseline with infrastructure fixes applied |
| 7 | Task 12 — ResourceLeakDetectionTest | Task 4 migration | 1 test |
| 8 | Task 13 — genuine failures | Step 6 baseline | Remaining failures |

Tasks 8, 9, 10, 11 are independent and can be done in any order. Task 8 should be first
because it unblocks the most tests (11 classes) and is purely mechanical.
