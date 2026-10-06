# PeeGeeQ Testing Standards and Antipatterns

**Status:** CURRENT MANDATORY STANDARD

**Last reconciled:** 2026-10-06 against commit f1c5d25d

> **This is a mandatory standards document.**
> Every pattern listed here must be absent from all test code. There are no exceptions.
> Severity labels (CRITICAL, SERIOUS, HIGH, MEDIUM, LOW) describe how quickly a violation
> causes test failures, masks bugs, or leaks resources. They do not create a class of
> acceptable violations. A LOW-severity violation is still a violation and must be fixed.

This document states the rules. It does not carry audit counts or remediation history.
Remediation status lives in code, in the guard tests, and in the
[consolidated task register](../tasks/tasks.md). Approved test structure is shown in
[PeeGeeQ Testing Patterns](PEEGEEQ_TESTING_STANDARDS_PATTERNS.md). Maven commands are in
[PeeGeeQ Test Commands](PEEGEEQ-TEST-COMMANDS.md).

---

## Table of contents

1. [Banned patterns](#banned-patterns)
2. [Enforcement: guard tests](#enforcement-guard-tests)
3. [CRITICAL: Placeholder tests that always pass](#critical-placeholder-tests-that-always-pass)
4. [CRITICAL: Exception thrown in `onSuccess` is silently swallowed](#critical-exception-thrown-in-onsuccess-is-silently-swallowed)
5. [CRITICAL: `Future.await()` in test code](#critical-futureawait-in-test-code)
6. [CRITICAL: Delays and thresholds added to make a build pass](#critical-delays-and-thresholds-added-to-make-a-build-pass)
7. [SERIOUS: `.onComplete(ar -> ...)` swallows failures](#serious-oncompletear----swallows-failures)
8. [SERIOUS: Fire-and-forget Futures](#serious-fire-and-forget-futures)
9. [SERIOUS: `.recover()` and `.otherwise()` in production code](#serious-recover-and-otherwise-in-production-code)
10. [SERIOUS: Blocking wrapper methods in production code](#serious-blocking-wrapper-methods-in-production-code)
11. [SERIOUS: Teardown](#serious-teardown)
12. [HIGH: Discarded `Future<Void>` from `stop()`/`close()` in compose chains](#high-discarded-futurevoid-from-stopclose-in-compose-chains)
13. [HIGH: Background jobs left enabled when creating `PeeGeeQManager` directly](#high-background-jobs-left-enabled-when-creating-peegeeqmanager-directly)
14. [HIGH: Fixed delays and timers in tests](#high-fixed-delays-and-timers-in-tests)
15. [HIGH: Hand-rolled schema DDL and raw JDBC in tests](#high-hand-rolled-schema-ddl-and-raw-jdbc-in-tests)
16. [HIGH: Test infrastructure needs contract tests](#high-test-infrastructure-needs-contract-tests)
17. [MEDIUM: Empty catch blocks](#medium-empty-catch-blocks)
18. [MEDIUM: Integration test hygiene](#medium-integration-test-hygiene)
19. [MEDIUM: Commented-out `@Test`](#medium-commented-out-test)
20. [MEDIUM: Asserting on log message strings instead of exception type](#medium-asserting-on-log-message-strings-instead-of-exception-type)
21. [MEDIUM: `withConnection()` for write operations](#medium-withconnection-for-write-operations)
22. [MEDIUM: Tests placed in the wrong class](#medium-tests-placed-in-the-wrong-class)
23. [MEDIUM: `VertxTestContext` orphan](#medium-vertxtestcontext-orphan)
24. [MEDIUM: Health checks that count the wrong thing](#medium-health-checks-that-count-the-wrong-thing)
25. [LOW: Copy-paste contamination of test logger messages](#low-copy-paste-contamination-of-test-logger-messages)
26. [NOT AN ANTIPATTERN: `.onFailure(log)` on a returned Future](#not-an-antipattern-onfailurelog-on-a-returned-future)

---

## Banned patterns

This list matches `.claude/CLAUDE.md` Step 5. Grep every file you intend to touch for each
pattern before editing. All must return zero results, and your edits must not introduce any.

| Pattern | Scope | Rule |
|---|---|---|
| `\.recover\(` | production and tests | Banned everywhere. No exceptions. See [`.recover()` and `.otherwise()`](#serious-recover-and-otherwise-in-production-code). |
| `\.otherwise\(` | production and tests | Banned everywhere. No exceptions. |
| `\.await\(` on a Future | production and tests | Banned. See [`Future.await()`](#critical-futureawait-in-test-code). |
| `CompletableFuture`, `toCompletionStage`, `toCompletableFuture`, `\.join\(\)`, `\.get\(\)` on a future | production and tests | Blocking bridges to JDK futures. Banned. |
| `Thread\.sleep`, `LockSupport\.parkNanos` | production and tests | Banned. See [Fixed delays and timers](#high-fixed-delays-and-timers-in-tests). |
| `Handler<AsyncResult` | production and tests | Callback style. Banned. |
| `\.onComplete\(ar -> .*succeeded` | production and tests | Use `.onSuccess`/`.onFailure`, or `testContext.succeeding(...)`. See [`.onComplete(ar -> ...)`](#serious-oncompletear----swallows-failures). |
| Fire-and-forget Future | production and tests | Every Future must be observed via `.onFailure(...)` or chained. See [Fire-and-forget Futures](#serious-fire-and-forget-futures). |
| Pass-on-failure handler | tests, including `@AfterEach` | An `.onFailure(err -> { ... })` body that reaches `testContext.completeNow()` without an assertion. The handler must call `testContext.failNow(err)` or assert the expected failure inside `testContext.verify(...)`. See [Teardown](#serious-teardown). |

Detection of the pass-on-failure form needs a body-scoped scan. A flat regex runs past the
handler's closing brace and reports the next `completeNow()` in an `else` branch as a hit.

---

## Enforcement: guard tests

Five static-analysis guard tests in
`peegeeq-test-support/src/test/java/dev/mars/peegeeq/test/quality/` run under
`@Tag(TestCategories.CORE)` on every `mvn test`. They need no database and no Testcontainers.

| Guard class | What it rejects |
|---|---|
| `OnSuccessExceptionSwallowingGuardTest` | Async test antipatterns that swallow failures, deadlock tests, block event loops, or discard futures, in every `peegeeq-*/src/test/java/**/*.java` file. |
| `VertxAsyncForbiddenPatternsGuardTest` | Vert.x async forbidden patterns in production and test sources (`new VertxTestContext(`, `.toCompletionStage(`, `.toCompletableFuture(`, `CompletableFuture`, `.recover(`, `.otherwise(`, `Handler<AsyncResult`) ratcheted against a checked-in baseline. |
| `DisabledTestsGuardTest` | Unapproved JUnit `@Disabled` annotations and stale allowlist rows. |
| `InvalidDurationLiteralGuardTest` | Millisecond-suffixed ISO-8601 duration literals that `Duration.parse` rejects, in Java sources, configuration resources, and documentation examples. |
| `SchemaInitializerTestInfrastructureGuardTest` | Schema-initializer tests that regress to hand-built PostgreSQL containers or raw JDBC verification. |

`OnSuccessExceptionSwallowingGuardTest` checks nine tiers:

| Tier | Test method | Pattern |
|---|---|---|
| 2/3 | `noTier3OnSuccessExceptionSwallowingInTestSources` | Bare `assertX(...)`/`fail(...)` (Tier 3) or top-level `.close(...)` (Tier 2) inside `.onSuccess(v -> { ... })` outside `testContext.verify(...)`. |
| 4 | `noFutureAwaitInTestSources` | Any zero-argument `.await()`, and any `.await(...)` whose argument list has no `TimeUnit.` token. `testContext.awaitCompletion(...)` is a different method name and never matches. A zero-argument `CyclicBarrier.await()` is flagged; use the bounded `await(timeout, TimeUnit)` overload. |
| 5 | `noBlockingThreadDelaysInTestSources` | `Thread.sleep(...)` or `LockSupport.parkNanos(...)`. |
| Policy | `noBlockingExemptionsInTestSources` | Any `@Tag("blocking-exempt")` annotation. |
| 6 | `noOnCompleteSwallowingInTestSources` | `.onComplete(ar -> singleCountdown())` with no failure branch. |
| 7 | `noDiscardedFuturesFromStopOrCloseInTestSources` | `<receiver>.stop();` / `<receiver>.close();` whose Future is neither chained, assigned, nor returned. |
| 8 | `noAsyncOperationsAssertedOnlyAsNonNullInTestSources` | `assertNotNull(...)` around `send`, `subscribe`, or `close`. |
| 9 | `noDiscardedFuturesFromSubscribeInTestSources` | A bare `consumer.subscribe(handler);` whose Future is not observed. |

Tiers 4, 7, and 9 ratchet against CSV baselines in
`peegeeq-test-support/src/test/resources/quality/`. All three baselines currently hold zero
rows, so any new hit fails the build. A `try { ... } catch (Throwable|Exception e) { /* no
re-throw */ }` block around a synchronous call is treated as a containment shield and is not
flagged by Tiers 2 and 3.

Do not relax, exclude, or weaken a guard to make a test pass. Fix the test. Details, failure
report format, and maintenance procedure are in [PeeGeeQ Async Test Guard](PEEGEEQ_TEST_GUARD.md).

---

## CRITICAL: Placeholder tests that always pass

A test that performs real operations and asserts nothing about the outcome passes
unconditionally. Runtime errors, failed futures, and data corruption are invisible.

```java
// WRONG: tautological assertion, test always passes
consumer.close();
assertTrue(true, "Close should be idempotent");
testContext.completeNow();

// WRONG: comparisons that are always true
assertTrue(receivedMessages.size() >= 0, "Should process messages");
assertTrue(count.get() >= 0);
```

**Rule:** Each test asserts a meaningful postcondition (state change, received message
content, metric value) or is deleted.

Variants of the same defect:

- A timeout handler that calls `completeNow()` when the expected event did not arrive. See
  [Fixed delays and timers](#high-fixed-delays-and-timers-in-tests).
- A leak detector with a tolerance budget. See
  [Delays and thresholds](#critical-delays-and-thresholds-added-to-make-a-build-pass).
- `assertNotNull(producer.send(...))`. This proves only that a Future object was allocated.
  Compose or observe the Future and assert the resulting behaviour (guard Tier 8).

---

## CRITICAL: Exception thrown in `onSuccess` is silently swallowed

When a `RuntimeException` is thrown synchronously inside a `Future.onSuccess(v -> ...)`
callback:

1. The Vert.x context catches the exception internally and routes it to
   `vertx.exceptionHandler`, logged as `ContextImpl - Unhandled exception` at ERROR level.
2. The paired `.onFailure(...)` handler is never called. It fires only for Future pipeline
   failures, not for exceptions thrown inside callbacks.
3. `VertxTestContext` receives neither `completeNow()` nor `failNow()`. The test hangs for
   the full timeout and reports only "Timeout".

The same mechanism applies to any synchronous call inside `onSuccess`: assertions, and
resource cleanup such as `.close()`, `.stop()`, `.commit()`, `.rollback()`.

```java
// WRONG: assertion and close() outside verify(); an exception here hangs the test
.onSuccess(pool -> {
    assertNotNull(pool);
    pool.close();
    testContext.completeNow();
})
.onFailure(testContext::failNow);
```

### Correct patterns

**Preferred: `testContext.succeeding(...)`.** Any exception thrown inside the wrapped
callback is a test failure. Source: [Vert.x JUnit 5 docs](https://vertx.io/docs/vertx-junit5/java/).

```java
// CORRECT
.onComplete(testContext.succeeding(pool -> testContext.verify(() -> {
    assertNotNull(pool);
    pool.close();
    testContext.completeNow();
})));
```

**Also correct: `testContext.verify(...)` as the outermost wrapper inside `onSuccess`.**

```java
// CORRECT: everything synchronous lives inside verify()
.onSuccess(pool -> testContext.verify(() -> {
    assertNotNull(pool);
    pool.close();
    testContext.completeNow();
}))
.onFailure(testContext::failNow);
```

**Also correct: move the throwing call into the pipeline with `compose()`.**

```java
// CORRECT: a throw inside compose() becomes a failed Future and reaches onFailure
.compose(v -> {
    configManager.registerConfiguration("development", testConfig());
    return Future.succeededFuture();
})
.onComplete(testContext.succeeding(v -> testContext.verify(() -> {
    assertEquals(4, configManager.getConfigurationNames().size());
    testContext.completeNow();
})));
```

The `compose()` form applies only to synchronous calls. `Future`-returning close methods
belong in the teardown chain. See [Teardown](#serious-teardown).

### Containment shield for a synchronous, void-returning close

When a synchronous, void-returning `close()` is allowed to fail and the test must still
complete, wrap only that call in a `try`/`catch` that logs and does not re-throw. The shield
is not valid for a `Future`-returning close: `try { resource.closeReactive(); } catch` would
discard the returned Future, and the resource would not be closed when `completeNow()` runs.

```java
.onSuccess(server -> testContext.verify(() -> {
    assertTrue(server.isRunning());
    try {
        server.close();   // synchronous, void-returning only
    } catch (Exception e) {
        logger.warn("Test server close failed: {}", e.getMessage());
    }
    testContext.completeNow();
}))
```

### Callback ordering

The Vert.x Core manual states: "Terminal operations like `onSuccess`, `onFailure` and
`onComplete` provide no guarantee whatsoever regarding the invocation order of callbacks."
Do not stack several `onSuccess` handlers on one future and rely on their order. Sequence
with `compose()` or `andThen()`.

### Runtime demonstration

`peegeeq-db/src/test/java/dev/mars/peegeeq/db/performance/VertxOnSuccessExceptionSwallowTest.java`
holds three antipattern proofs and three safe-pattern counterparts
(`antiPattern_exceptionInOnSuccess_bypassesOnFailure`,
`antiPattern_syncCallAfterTimerChain_causesSilentHang`,
`safePattern_tryCatchInOnSuccess_preventsSwallow`,
`safePattern_syncCallAfterTimerChain_preventsHang`,
`safePattern_compose_keepFailuresInPipeline`,
`safePattern_testContextVerify_autoRoutesExceptions`).

---

## CRITICAL: `Future.await()` in test code

`io.vertx.core.Future#await()` is a Vert.x 5 helper for virtual threads. Called from a
JUnit platform thread it blocks that thread until the future settles. If settling needs the
Vert.x event loop to dispatch work, the future never completes and the test hangs with no
timeout, no error, and no thread dump. The failure is observable in logs as
`WARN ... Cannot be called on a Vert.x event-loop thread` immediately before the hang.

### Banned forms

All of these are banned in test code. The same patterns are banned in production code by
[the coding principles](../dev/pgq-coding-principles.md).

```java
manager.start().await();
manager.closeReactive().await();
producer.send("msg").await();
consumer.close().await();
group.start(options).await();
vertx.timer(100).await();
vertx.close().await();
someFuture.await();
```

### Permitted `await*` calls

```java
testContext.awaitCompletion(10, TimeUnit.SECONDS);   // drains a VertxTestContext
latch.await(10, TimeUnit.SECONDS);                    // CountDownLatch, bounded form only
startBarrier.await(10, TimeUnit.SECONDS);             // CyclicBarrier, bounded form only
```

`CountDownLatch.await(timeout, unit)` and `CyclicBarrier.await(timeout, unit)` are permitted
only with a `TimeUnit` argument. The guard whitelists that bounded form and flags every
zero-argument `.await()`, whatever the receiver. The bounded latch form is discouraged: in
`@ExtendWith(VertxExtension.class)` tests, drive Future completion through the injected
`VertxTestContext` and its checkpoints. A helper that wraps a latch around a Future (`awaitFuture`,
`blockOnFuture`, `syncFuture`) keeps the synchronous test shape that `VertxTestContext`
replaces; do not add one.

### Correct pattern

Inject `VertxTestContext` into `@BeforeEach`, `@Test`, and `@AfterEach`, and drive completion
from terminal handlers.

```java
@BeforeEach
void setUp(VertxTestContext testContext) {
    PeeGeeQTestSchemaInitializer.initializeSchema(postgres, "peegeeq_test", SchemaComponent.QUEUE_ALL);
    manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
    manager.start()
        .onSuccess(v -> {
            factory = new OutboxFactory(new PgDatabaseService(manager), config);
            producer = factory.createProducer(topic, String.class);
            testContext.completeNow();
        })
        .onFailure(testContext::failNow);
}
```

For in-test sequencing, compose:

```java
// WRONG
group1.start(opts1).await();
assertTrue(group1.isActive());
group2.start(opts2).await();

// CORRECT
group1.start(opts1)
    .compose(v -> {
        assertTrue(group1.isActive());
        return group2.start(opts2);
    })
    .onSuccess(v -> testContext.verify(() -> {
        assertTrue(group2.isActive());
        testContext.completeNow();
    }))
    .onFailure(testContext::failNow);
```

The teardown form is in [Teardown](#serious-teardown).

### Verification

```powershell
grep -nE '\.await\(' path/to/Test.java
```

Every match that is not `testContext.awaitCompletion(...)` or a bounded
`await(timeout, TimeUnit.X)` on a `CountDownLatch` or `CyclicBarrier` is a violation. "Compilation passes" is not evidence
of a hang-free test.

---

## CRITICAL: Delays and thresholds added to make a build pass

A change made to achieve a green build rather than to fix the underlying problem is the test
equivalent of `.recover(e -> Future.succeededFuture())`. It makes the failure invisible
without removing its cause.

### Pattern 1: `Thread.sleep()` labelled "strategic delay"

```java
// WRONG: a fixed-duration block inserted to cover a race condition
Thread.sleep(2000);   // "after concurrent operations that need time to complete"
```

If two operations race, sequence them with futures. The label does not change what the code
does.

### Pattern 2: Threshold-based leak detection

```java
// WRONG: treats up to 5 real leaks as acceptable
int maxAllowedVertxThreads = 5;
if (allVertxThreads.size() > maxAllowedVertxThreads) {
    fail("Excessive Vert.x event loop threads detected");
}
```

A threshold of 5 means "I allow 5 leaks before I notice". The fix for threads appearing from
other tests is lifecycle management, not a tolerance budget. The threshold is zero.

### Pattern 3: Filtering "old" threads by creation timestamp

A thread that survives past the end of its owning test is a leak regardless of when it was
created. Filtering by timestamp hides the leak. Stop the thread during shutdown through a
close hook or Vert.x lifecycle integration.

### Why these are dangerous

A real problem is detected and the detector is adjusted until the problem is no longer
visible. The build goes green. The problem remains in production. A "100% test success rate"
achieved this way is evidence that the failure detectors were tuned to stop detecting.

---

## SERIOUS: `.onComplete(ar -> ...)` swallows failures

`onComplete` fires on success and on failure. A handler that only signals completion loses
the failure.

```java
// WRONG: counts down on BOTH success AND failure
producer.send(msg).onComplete(ar -> sendLatch.countDown());

// WRONG: close failure is invisible
manager.closeReactive().onComplete(ar -> closeLatch.countDown());

// WRONG: manual branch on succeeded() (banned regex form)
future.onComplete(ar -> { if (ar.succeeded()) { ... } });
```

If the send fails (pool closed, connection refused, serialisation error), the test proceeds
with its preconditions unmet. Downstream assertions then fail with a timeout, or pass
vacuously because no message arrived and the test does not check for that.

```java
// CORRECT: checkpoint on success, failure routed to the test context
producer.send(msg)
    .onSuccess(v -> checkpoint.flag())
    .onFailure(testContext::failNow);

// CORRECT: Vert.x JUnit 5 form
producer.send(msg).onComplete(testContext.succeeding(v -> checkpoint.flag()));
```

Both forms are permitted. The banned form is `.onComplete(ar -> ... succeeded())`.

---

## SERIOUS: Fire-and-forget Futures

A `Future` whose result is never observed loses its failure.

```java
// WRONG: send failure is silently lost
producer.send("test-message");

// WRONG: subscription failure is silently lost; a following send races the subscribe
consumer.subscribe(handler);
```

```java
// CORRECT: consumer-side checkpoint completes the test; send failure fails it
producer.send("test-message")
    .onFailure(testContext::failNow);

// CORRECT: when the next step depends on the send
producer.send("test-message")
    .compose(v -> nextStep())
    .onSuccess(v -> testContext.completeNow())
    .onFailure(testContext::failNow);

// CORRECT: send only after the LISTEN acknowledgement
consumer.subscribe(handler)
    .compose(v -> producer.send("message"))
    .onFailure(testContext::failNow);
```

**Rule:** Every Future is chained or observed with `.onFailure(...)`. This applies in test
code exactly as in production code.

---

## SERIOUS: `.recover()` and `.otherwise()` in production code

`.recover()` and `.otherwise()` convert a failed Future into a succeeded one. Every use in
this codebase was found to be error erasure. Both are banned everywhere. The full
classification is in [the coding principles](../dev/pgq-coding-principles.md).

The shutdown/cleanup case is the common temptation. The correct API is `.eventually()`, which
runs a side-effect (close a pool, cancel a timer, leave a group) without altering whether the
overall Future succeeds or fails.

```java
// WRONG: .recover() erases the error
resource.close()
    .recover(e -> { logger.warn("Close failed", e); return Future.succeededFuture(); })

// CORRECT: .eventually() runs cleanup without touching the outcome
operation()
    .eventually(() -> resource.close())
```

Background timer callbacks that cannot propagate anywhere use `.onFailure(...)` as a terminal
observer and track consecutive failures so a persistent fault escalates. `PeeGeeQManager`
does this for its depth-cache, dead-letter cleanup, and stuck-message timers.

---

## SERIOUS: Blocking wrapper methods in production code

A registry or factory for an async component must not expose synchronous `Supplier<T>`
wrappers that execute inline. Such methods block the calling thread, block the Vert.x event
loop when invoked there, and introduce a second, incompatible usage pattern that future
callers may copy.

`HealthCheckManager` is the reference for circuit-breaker use: the caller retrieves the
`CircuitBreaker`, calls `tryAcquirePermission()`, runs the reactive operation, and records
`onSuccess`/`onError` from terminal handlers. Do not add `executeSupplier`-style methods to
`CircuitBreakerManager` or any other reactive factory.

---

## SERIOUS: Teardown

This section is the single statement of the teardown rules. Other sections cross-reference it.

### Rule 1: Close in reverse construction order

Resources form a dependency chain: consumers use producers, which use the pool, which uses the
connection manager, which uses the Vert.x event loop. Closing an outer resource before an
inner one produces `"Client not found"` errors, `NullPointerException` on in-flight futures,
and leaked event-loop threads.

```
1. Message consumers        (stop receiving; let in-flight messages drain)
2. Message producers        (stop sending)
3. QueueFactory / consumer group
4. Secondary PgConnectionManager instances
5. PeeGeeQManager           (closeReactive())
6. Vertx                    (only if self-managed, not VertxExtension)
```

Each step completes before the next starts. Independent `try`/`catch` blocks do not guarantee
ordering for `Future`-returning closes.

### Rule 2: Drive teardown from the injected `VertxTestContext`, and fail on close failure

A failed close leaks pool connections. Routing the failure to `completeNow()` hides the leak
until a later test fails with "too many clients". The failure must surface at the offending
test.

Signatures (verified against `peegeeq-api` and `peegeeq-outbox`): `MessageProducer.close()`
and `MessageConsumer.close()` are `void`; `QueueFactory.close()`, `ConsumerGroup.close()`,
`PgConnectionManager.close()`, `MultiConfigurationManager.close()`, and
`PeeGeeQManager.closeReactive()` return `Future<Void>`.

```java
// WRONG: try/catch around a Future-returning close discards the Future;
//        failure swallowed to completeNow() hides the leak
@AfterEach
void tearDown(VertxTestContext testContext) {
    try { factory.close(); } catch (Exception e) { logger.warn("close failed", e); }  // Future<Void> discarded
    manager.closeReactive()
        .onSuccess(v -> testContext.completeNow())
        .onFailure(err -> { logger.warn("close failed", err); testContext.completeNow(); });  // pass-on-failure
}
```

```java
// CORRECT: void closes shielded, Future-returning closes composed in order, failure fails the test
@AfterEach
void tearDown(VertxTestContext testContext) {
    if (consumer != null) {
        try { consumer.close(); } catch (Exception e) { logger.warn("Error closing consumer", e); }
    }
    if (producer != null) {
        try { producer.close(); } catch (Exception e) { logger.warn("Error closing producer", e); }
    }
    Future.<Void>succeededFuture()
        .compose(v -> factory != null ? factory.close() : Future.succeededFuture())
        .compose(v -> connectionManager != null ? connectionManager.close() : Future.succeededFuture())
        .compose(v -> manager != null ? manager.closeReactive() : Future.succeededFuture())
        .onSuccess(v -> {
            manager = null;
            testContext.completeNow();
        })
        .onFailure(err -> {
            logger.error("Error during reactive teardown", err);
            manager = null;
            testContext.failNow(err);
        });
    // Do NOT close vertx here if @ExtendWith(VertxExtension.class) is present
}
```

The `null` guards keep the chain intact when `@BeforeEach` failed part-way. Do not add a
grace timer before the close: `vertx.timer(...)` throws `RejectedExecutionException` when the
event loop is already dead, and the close is then never reached.

### Rule 3: Close every resource the subclass creates

`BaseIntegrationTest` closes only the primary `manager`. A subclass that creates a secondary
`PgConnectionManager`, factory, or consumer group in `@BeforeEach` must close it in its own
`@AfterEach`. The base class cannot close what it does not know about.

### Rule 4: Cleanup SQL runs before the pool closes

```java
// WRONG: pool already closed when the cleanup query fires
manager.closeReactive()
    .eventually(() -> cleanupSql(pool))

// CORRECT: cleanup first, close last
cleanupSql(pool)
    .eventually(() -> manager.closeReactive())
```

The close is the terminal `.eventually(...)` step, not the first one.

### Rule 5: The container outlives the manager

A `PeeGeeQManager` with a short metrics interval (`PT1S`, `PT5S`) keeps firing its periodic
timer after the Testcontainers PostgreSQL container stops. The escalating ERROR log
("consecutive failures") is correct production behaviour and must never appear in tests.
`closeReactive()` must complete, through the chain above, before the container stops.

### Rule 6: One owner for `Vertx`

`VertxExtension` creates one `Vertx` per test, injects it into method parameters, and closes
it. A class that also calls `Vertx.vertx()` in `@BeforeEach` has two instances; the manual one
leaks its event-loop thread and pool connections when the test's own `@AfterEach` does not
run to completion.

```java
// CORRECT, Option A: let VertxExtension own the instance
@ExtendWith(VertxExtension.class)
class MyTest {
    private WebClient client;

    @BeforeEach
    void setUp(Vertx vertx) {
        client = WebClient.create(vertx);   // injected; do NOT create a second one
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
        // Do NOT call vertx.close(); VertxExtension owns this lifecycle
    }
}

// CORRECT, Option B: self-managed Vertx, no VertxExtension
class MyTest {
    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (vertx == null) { testContext.completeNow(); return; }
        vertx.close()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(err -> { logger.error("vertx close failed", err); testContext.failNow(err); });
    }
}
```

Pick one owner. Never mix `@ExtendWith(VertxExtension.class)` with a `Vertx` field created by
the test.

### Rule 7: `.eventually(() -> resource.close())` inside a test body

`.eventually(...)` on the test's Future chain is a valid place to close a resource whose
`close()` returns a Future or is event-loop-safe (`MessageProducer.close()`,
`MessageConsumer.close()`, `Pool.close()`). It is not a substitute for `@AfterEach`: a
resource stored in a field is closed in `@AfterEach` through the chain in Rule 2, so that it
is also closed when the test body fails before reaching `.eventually(...)`.

`try`/`finally` does not bracket an async Future chain. The `finally` block runs the moment
the `try` body returns control, before the chain has settled, and can close a resource the
chain is still using. Never wrap a Future chain in `try`/`finally` expecting `finally` to run
after the async work.

### Rule 8: No `System.gc()` in teardown

GC does not release database connections. `PgConnectionManager` holds `Pool` references with
explicit lifecycles. Only `close()` releases them. Remove `System.gc()`.

---

## HIGH: Discarded `Future<Void>` from `stop()`/`close()` in compose chains

`DeadConsumerDetectionJob.stop()` returns a `Future<Void>` that resolves only after any
in-flight detection cycle, including the SQL it writes, has completed. A test that calls
`job.stop()` and continues runs its next query while the cleanup is still in flight. Such a
test passes in isolation and fails under concurrent load.

```java
// WRONG: Future<Void> discarded; stop() returns immediately
.compose(messageIds -> {
    job.stop();
    assertTrue(job.getTotalDeadDetected() >= 1);
    return verifyState().map(v -> messageIds);
})

// CORRECT: compose on stop(); assertions run only after stop completes
.compose(messageIds -> job.stop().compose(v -> {
    assertTrue(job.getTotalDeadDetected() >= 1);
    return verifyState().map(ignored -> messageIds);
}))
```

**Rule:** Any `stop()`, `close()`, or `shutdown()` that returns `Future<Void>` and is a
prerequisite to an assertion is composed on, never called and ignored. Guard Tier 7 flags the
discarded form.

---

## HIGH: Background jobs left enabled when creating `PeeGeeQManager` directly

A test that constructs a `PeeGeeQManager` from its own `Properties` inherits the production
defaults in `peegeeq-db/src/main/resources/peegeeq-default.properties`:

| Property | Default | Effect if left enabled in a shared test database |
|---|---|---|
| `peegeeq.queue.dead-consumer-detection.enabled` | `true` | Marks subscriptions DEAD across every concurrent test. |
| `peegeeq.queue.consumer-group-retry.enabled` | `true` | `ConsumerGroupRetryJob` resets `status='PENDING', error_message=NULL` on every FAILED row, including rows owned by other tests. |

The pollution is invisible in isolation runs. It shows only under concurrent execution,
as an assertion on another test's row failing with `expected: <error 2> but was: <null>`.

```java
// WRONG: sets only the job under test; the retry job inherits its default of true
//        and pollutes the shared database
testProps.setProperty("peegeeq.queue.dead-consumer-detection.enabled", "true");

// CORRECT: every background job is set explicitly; only the job under test is enabled
testProps.setProperty("peegeeq.queue.dead-consumer-detection.enabled", "true");
testProps.setProperty("peegeeq.queue.consumer-group-retry.enabled", "false");

// CORRECT: a test that exercises neither job disables both
testProps.setProperty("peegeeq.queue.dead-consumer-detection.enabled", "false");
testProps.setProperty("peegeeq.queue.consumer-group-retry.enabled", "false");
```

**Rule:** A test that bypasses `BaseIntegrationTest` sets every background-job property to a
known value. `BaseIntegrationTest` disables both (`peegeeq-db/src/test/java/dev/mars/peegeeq/db/BaseIntegrationTest.java`).

---

## HIGH: Fixed delays and timers in tests

This section is the single statement of the timing rule.

**Rule:** No fixed delay is used as a readiness guard or as a substitute for an assertion.
Every async operation produces a `Future`; chain off it. Every side-effect has an observable
consequence; observe it. A bounded `vertx.timer` poll that re-checks a condition and fails
on its deadline is permitted. `Thread.sleep`, `LockSupport.parkNanos`, `CountDownLatch`
spin-loops, and `vertx.executeBlocking(...)` wrapping any of them are banned.

### Form 1: `Thread.sleep` / `LockSupport.parkNanos`

```java
// WRONG: blocks the thread for a fixed duration; flaky on slow CI, wasteful otherwise
LockSupport.parkNanos(1_000_000_000L);
```

On a Vert.x event-loop thread this blocks the entire event loop. Guard Tier 5 flags both
forms.

### Form 2: `setTimer` as a readiness guard

```java
// WRONG: races against actual readiness
consumer.subscribe(handler);
vertx.setTimer(1000, id -> producer.send("test message"));

// CORRECT: subscribe() completes on the LISTEN acknowledgement
consumer.subscribe(handler)
    .compose(v -> producer.send("message"))
    .onFailure(testContext::failNow);

// CORRECT: multiple consumers
Future.all(consumer1.subscribe(handler1), consumer2.subscribe(handler2))
    .compose(v -> producer.send("message"))
    .onFailure(testContext::failNow);
```

The same applies after `deployVerticle()`: it completes only after `Verticle.start()`
finishes. If `start()` awaits `HttpServer.listen()`, the server is listening when `onSuccess`
fires. A timer after deploy is a guess. If the server is not ready when `start()` completes,
fix `start()` to return a Future that resolves after `listen()`.

### Form 3: Timeout handler that calls `completeNow()`

```java
// WRONG: the test passes if the event never arrives
vertx.setTimer(1000, id -> {
    testContext.completeNow();  // no assertions made
});

// CORRECT: a timeout is a failure
vertx.setTimer(10000, id -> {
    if (!metricsReceived.get()) {
        testContext.failNow(new AssertionError("No system_stats message received within 10 s"));
    }
});
```

### Form 4: Blocking poll on the test thread under `VertxExtension`

```java
// WRONG: parks the JUnit thread; races VertxTestContext timeout handling
while (System.currentTimeMillis() < deadline) {
    var status = healthCheckManager.getOverallHealth();
    if (status != null && status.isHealthy()) break;
    LockSupport.parkNanos(200_000_000L);
}

// CORRECT: bounded recursive vertx.timer poll; fails on its deadline
pollHealth(vertx, healthCheckManager, System.currentTimeMillis() + 10_000)
    .onSuccess(status -> testContext.verify(() -> {
        assertTrue(status.isHealthy());
        testContext.completeNow();
    }))
    .onFailure(testContext::failNow);

private Future<HealthStatus> pollHealth(Vertx vertx, HealthCheckManager hcm, long deadline) {
    HealthStatus status = hcm.getOverallHealth();
    if (status != null && status.isHealthy()) {
        return Future.succeededFuture(status);
    }
    if (System.currentTimeMillis() >= deadline) {
        return Future.failedFuture(new AssertionError("Health check did not become healthy within 10 s"));
    }
    return vertx.timer(200).compose(t -> pollHealth(vertx, hcm, deadline));
}
```

Each retry is a Future continuation on the event loop. The deadline is carried in the chain.
The test thread never blocks. If the polled method itself returns a `Future`, chain it
inside the helper; the recursive shape is the same.

### Form 5: Production timing assumptions

Production code that waits a fixed interval for an asynchronous PostgreSQL effect (for
example `pg_terminate_backend()`, which signals backends and returns before they exit) is the
same defect. Poll the observable state (`pg_stat_activity`) with a bounded retry instead.
`peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/DatabaseTemplateManager.java` shows the
correct form.

---

## HIGH: Hand-rolled schema DDL and raw JDBC in tests

Inline `CREATE TABLE` statements duplicating the production schema, executed through raw JDBC
(`java.sql.Connection`, `DriverManager`, `PreparedStatement`, `ResultSet`), create schema
drift and ignore the shared initializer. JDBC-based verification queries in assertions are the
same violation.

```java
// CORRECT: shared schema initializer (signature: container, schema, components...)
PeeGeeQTestSchemaInitializer.initializeSchema(postgres, "peegeeq_test",
        SchemaComponent.NATIVE_QUEUE,
        SchemaComponent.OUTBOX,
        SchemaComponent.DEAD_LETTER_QUEUE);

// CORRECT: reactive verification query through the service pool
pool.preparedQuery("SELECT jsonb_typeof(payload) FROM queue_messages WHERE topic = $1")
    .execute(Tuple.of(topic))
    .compose(rows -> { ... });
```

`SchemaComponent.QUEUE_ALL` expands to `OUTBOX`, `NATIVE_QUEUE`, and `DEAD_LETTER_QUEUE`.
The pool comes from `PgDatabaseService.getPool()`. `SchemaInitializerTestInfrastructureGuardTest`
rejects regressions in the initializer's own tests.

---

## HIGH: Test infrastructure needs contract tests

Test infrastructure that configures shared resources must have a `@Tag(TestCategories.CORE)`
contract test proving the configuration reaches the loader. A property-key typo in a base
class silently degrades every integration test that inherits it, and nothing fails at review
time.

`peegeeq-db/src/test/java/dev/mars/peegeeq/db/infrastructure/BaseIntegrationTestPoolConfigContractCoreTest.java`
is the reference. It asserts the pool values `BaseIntegrationTest` sets through
`PeeGeeQTestConfig`:

- `poolConfigUsesShortIdleTimeoutForFastTeardown`
- `poolConfigUsesShortConnectionTimeoutForFastFailure`
- `poolConfigUsesNonSharedPoolsForDeterministicCleanup`
- `poolConfigUsesSmallMaxSizeForParallelExecutionHeadroom`

Integration tests use `peegeeq.database.pool.shared=false`. With `shared=true` (the
production default in `peegeeq-default.properties`), `Pool.close()` defers socket release to
reference counting and idle eviction, so teardown is not deterministic. Pool tuning keys are
the `-ms` forms the loader reads (`peegeeq.database.pool.connection-timeout-ms`,
`peegeeq.database.pool.idle-timeout-ms`).

**Rule:** "It compiles" is not evidence that test configuration works.

---

## MEDIUM: Empty catch blocks

```java
// WRONG
try { factory.close(); } catch (Exception ignored) {}
```

Empty catch blocks are never acceptable. In teardown they hide resource leaks and cause later
tests to fail with unrelated errors. In a test body they hide the failure under test.

- Teardown, synchronous void close: `catch (Exception e) { logger.warn("Close failed", e); }`
- Test body inside a `VertxTestContext` scope: `catch (Exception e) { testContext.failNow(e); }`
- `Future`-returning close: do not catch; compose it. See [Teardown](#serious-teardown).

---

## MEDIUM: Integration test hygiene

### 1. System property pollution

`System.setProperty(...)` in `@BeforeEach` without `System.clearProperty(...)` in
`@AfterEach` leaks host, port, and credentials to later tests in the same JVM fork. Prefer an
isolated `Properties` object built with `PeeGeeQTestConfig.builder()` (see the
[patterns guide](PEEGEEQ_TESTING_STANDARDS_PATTERNS.md)). If a legacy boundary requires system
properties, track every key in a constant array and clear them all in `@AfterEach`.

### 2. Custom container factory

Use `PostgreSQLTestConstants.createStandardContainer()`. A hand-rolled
`PostgreSQLContainer` with manual `.withDatabaseName`/`.withUsername`/`.withPassword` misses
shared memory size, reuse policy, and future defaults.

### 3. Narration logging

`logger.info("Testing connection pool usage...")` adds nothing the test name, `@DisplayName`,
and assertion messages do not already say. Remove it. Keep `logger.debug` only for genuine
diagnostics.

### 4. Trivial assertions that test language mechanics

`assertEquals(5, consumers.size())` after five `add` calls asserts that `ArrayList.add()`
works. Delete it, or assert the behaviour the test claims to verify.

### 5. Producer leak in timer callbacks

```java
// WRONG: producers leak if any send fails
producer.send("msg1")
    .compose(v -> producer2.send("msg2"))
    .onSuccess(v -> { producer.close(); producer2.close(); })
    .onFailure(testContext::failNow);

// CORRECT: close regardless of outcome
producer.send("msg1")
    .compose(v -> producer2.send("msg2"))
    .eventually(() -> {
        producer.close();
        producer2.close();
        return Future.succeededFuture();
    })
    .onFailure(testContext::failNow);
```

### 6. Missing `hashCode()` when `equals()` is overridden

Add a matching `hashCode()` (`java.util.Objects.hash(...)`). The object breaks silently in a
`HashSet` or as a `HashMap` key, and the class is copied as a template.

### 7. Unused method parameters

Remove injected parameters (`Vertx vertx`) that the body never references.

### 8. Dead branches in shared helpers

A branch no caller exercises must be kept compilable, suggests coverage that does not exist,
and obscures what the helper does. Remove it. Test the case explicitly where it is exercised.

### 9. Unnecessary test ordering

`@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` with `@Order(n)` on tests that use
their own topic names implies a dependence that does not exist and discourages parallel
execution. Remove it. If tests share state, make them independent.

### 10. Dead code after `testContext.failNow()`

```java
// WRONG: the throw is never observed by the framework and may trigger secondary handling
} catch (Exception e) {
    testContext.failNow(e);
    throw new RuntimeException(e);
}

// CORRECT
} catch (Exception e) {
    testContext.failNow(e);
    return Future.failedFuture(e);
}
```

### 11. Weak assertion idioms

Replace `assertTrue(x instanceof Y)` with `assertInstanceOf(Y.class, x)` and
`assertTrue(x == null)` with `assertNull(x)`. The dedicated assertions report the actual
value on failure.

### 12. Asserting handler invocation instead of outcome

A test that checks `invoked.get()` after a handler returned `null` proves the handler ran. It
does not prove the production path (`OutboxConsumer.processMessageWithCompletion` wraps a
null return as `IllegalStateException("Message handler returned null Future")` and routes it
through retry/failure handling). Verify the message status through a reactive query or a
checkpoint on the retry path.

### 13. Stale Javadoc

Javadoc that references a banned type (`CompletableFuture`) or hard-coded production line
numbers is wrong on the day it is written. Describe the behaviour, not the line.

---

## MEDIUM: Commented-out `@Test`

```java
// WRONG: invisible to runners, reports, IDE views, and grep for @Disabled
//@Test
void testRetryLogicWithFailingMessages(Vertx vertx, VertxTestContext testContext) {
```

Either fix and re-enable the test, or use `@Disabled("reason")` so it appears in reports.
`DisabledTestsGuardTest` requires every `@Disabled` to be recorded in
`peegeeq-test-support/src/test/resources/quality/disabled-tests-allowlist.csv` with a
rationale.

---

## MEDIUM: Asserting on log message strings instead of exception type

A test that stops a database container and asserts the text `"Connection refused"` appeared
in a log message also passes if the timer failed for an unrelated reason and the substring
came from an earlier event. It also passes if production code logged `e.getMessage()` and
dropped the Throwable.

```java
// WRONG
boolean ok = warns.stream().allMatch(e -> e.getFormattedMessage().contains("Connection refused"));
```

Assert on the exception type through the Logback `IThrowableProxy`, walking the cause chain:

```java
private boolean hasCauseOfAnyType(IThrowableProxy proxy, String... classNames) {
    for (; proxy != null; proxy = proxy.getCause()) {
        for (String name : classNames) {
            if (name.equals(proxy.getClassName())) return true;
        }
    }
    return false;
}

assertFalse(warns.isEmpty(), "Expected WARN events from timer failures; none captured");
assertTrue(warns.stream().allMatch(e -> hasCauseOfAnyType(e.getThrowableProxy(),
        "java.io.IOException", "java.net.SocketException", "java.net.ConnectException")),
        "Every WARN must carry a network I/O exception in its cause chain");
```

Put the exception-type assertions first. Escalation assertions (WARN versus ERROR, counts in
the message) are meaningful only once the cause is proven.
`peegeeq-db/src/test/java/dev/mars/peegeeq/db/PeeGeeQManagerTimerGuardTest.java` is the
reference.

---

## MEDIUM: `withConnection()` for write operations

`pool.withConnection()` borrows a connection and does not begin a transaction. A failure
mid-sequence leaves partial state with no rollback.

```java
// WRONG: no transaction
return pool.withConnection(conn ->
    conn.preparedQuery("DELETE FROM dead_letter_queue WHERE id = $1")
        .execute(Tuple.of(messageId)));

// CORRECT: commit on success, rollback on failure
return pool.withTransaction(conn ->
    conn.preparedQuery("DELETE FROM dead_letter_queue WHERE id = $1")
        .execute(Tuple.of(messageId)));
```

**Rule:** `withTransaction()` for all writes. `withConnection()` only for reads.

---

## MEDIUM: Tests placed in the wrong class

A test for subsystem B inside the test class for subsystem A, because A's setup is available,
makes the class name untrustworthy, misattributes regressions, and breaks when B's API
changes.

**Rule:** A test class owns exactly one subject. If a test needs setup from class A to test
class B, it belongs in B's test suite with its own setup.

---

## MEDIUM: `VertxTestContext` orphan

A `new VertxTestContext()` created in a test body is drained by `awaitCompletion(timeout,
unit)`. If an assertion throws between creation and the drain, the context is abandoned:
later `completeNow()`/`failNow()` calls from background callbacks have no effect, unflagged
checkpoints expire silently, and a second callback throws
`IllegalStateException: Test context already completed` with no clear attribution.
`VertxAsyncForbiddenPatternsGuardTest` ratchets `new VertxTestContext(` against its baseline.

```java
// CORRECT: use the injected context and its checkpoints
void testOutboxTransactionParticipation(VertxTestContext testContext) {
    var msgCheckpoint = testContext.checkpoint();
    consumer.subscribe(message -> {
        msgCheckpoint.flag();
        return Future.succeededFuture();
    }).onFailure(testContext::failNow);
}
```

**Rule:** Do not create a `new VertxTestContext()`. Use the context injected by
`VertxExtension`. The baseline for this pattern holds zero rows, so any new instance fails
the build.

---

## MEDIUM: Health checks that count the wrong thing

A health check that counts every row matching a state across a shared table measures total
write throughput across all producers, not the health of one queue. In production and in a
parallel test suite, other producers are legitimately writing to the same table.

```sql
-- WRONG: counts all PENDING rows created in the last hour across all topics
SELECT COUNT(*) FROM outbox
WHERE status = 'PENDING' AND created_at > NOW() - INTERVAL '1 hour'

-- CORRECT: counts messages stuck pending beyond the expected processing window
SELECT COUNT(*) FROM outbox
WHERE status = 'PENDING' AND created_at < NOW() - INTERVAL '5 minutes'
```

**Rule:** A health check is scoped to what it owns. The fix for a false positive under
concurrent load is never `@ResourceLock` to serialize the tests; it is a health check that is
correct by design.

---

## LOW: Copy-paste contamination of test logger messages

A `logger.info("Test: circuit breaker integration")` left inside a test that was copied from
another directs a failure investigation to the wrong test and the wrong subsystem.

**Rule:** A logger call inside a test identifies the test it is in. The safest approach is to
omit per-test trace logs and rely on the runner's own reporting.

---

## NOT AN ANTIPATTERN: `.onFailure(log)` on a returned Future

```java
return pool.getConnection()
    .onFailure(error -> logger.error("Failed to get reactive connection for client: {}: {}",
        clientId, error.getMessage()));
```

`.onFailure` here is a terminal side-effect on a future that is also returned to the caller.
The error propagates through the chain; the log is supplementary. This is correct
(`peegeeq-db/src/main/java/dev/mars/peegeeq/db/provider/PgConnectionProvider.java`). It
becomes a violation only when the future is not returned or chained, which is the
[fire-and-forget](#serious-fire-and-forget-futures) case.
