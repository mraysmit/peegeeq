# PeeGeeQ Error Handling Antipatterns

Audit date: 2026-04-13  
Branch: `feature/fanout-trace-propagation`

> **This is a mandatory standards document.**  
> Every pattern listed here must be eliminated from all test code. There are no exceptions.
> Severity labels (CRITICAL, SERIOUS, HIGH, MEDIUM, LOW) describe the *blast radius* of
> each violation — how quickly it causes test failures, masks bugs, or leaks resources.
> They do not create a schedule or a class of acceptable violations. A LOW-severity
> violation is still a violation and must be fixed. A violation is either present or absent.

This document catalogues error-swallowing and test-integrity patterns found across the PeeGeeQ codebase
that prevent runtime failures from surfacing in tests. Organised by severity of impact.

---

## CRITICAL: Placeholder Tests That Always Pass

27 test methods across the codebase end with `assertTrue(true, "...")` followed by
`testContext.completeNow()`. These tests perform real operations but assert nothing
about the outcome. Any runtime error exception, failed future, data corruption —
is invisible. The test passes unconditionally.

### Pattern

```java
// WRONG: tautological assertion test always passes
consumer.close();
blockGate.countDown();
consumer.close();

assertTrue(true, "Close should be idempotent and handle concurrent processing");
testContext.completeNow();
```

### Affected Files

| File | Line(s) | Assertion text |
|---|---|---|
| `peegeeq-outbox/.../OutboxConsumerFailureHandlingTest.java` | 208 | `"Consumer should handle closure during processing without errors"` |
| `peegeeq-outbox/.../OutboxConsumerFailureHandlingTest.java` | 297 | `"Close should be idempotent and handle concurrent processing"` |
| `peegeeq-outbox/.../examples/IntegrationPatternsExampleTest.java` | 483 | `"Content-Based Router pattern concept validated"` |
| `peegeeq-outbox/.../examples/IntegrationPatternsExampleTest.java` | 499 | `"Aggregator pattern concept validated"` |
| `peegeeq-outbox/.../examples/IntegrationPatternsExampleTest.java` | 515 | `"Scatter-Gather pattern concept validated"` |
| `peegeeq-outbox/.../examples/IntegrationPatternsExampleTest.java` | 531 | `"Saga pattern concept validated"` |
| `peegeeq-outbox/.../examples/IntegrationPatternsExampleTest.java` | 547 | `"CQRS pattern concept validated"` |
| `peegeeq-native/.../examples/NativeVsOutboxComparisonExampleTest.java` | 171 | `"Architectural differences demonstration completed"` |
| `peegeeq-native/.../examples/NativeVsOutboxComparisonExampleTest.java` | 182 | `"Performance characteristics demonstration completed"` |
| `peegeeq-native/.../examples/NativeVsOutboxComparisonExampleTest.java` | 193 | `"Reliability features demonstration completed"` |
| `peegeeq-native/.../examples/NativeVsOutboxComparisonExampleTest.java` | 204 | `"Scalability patterns demonstration completed"` |
| `peegeeq-native/.../examples/NativeVsOutboxComparisonExampleTest.java` | 215 | `"Failure scenarios demonstration completed"` |
| `peegeeq-native/.../examples/NativeVsOutboxComparisonExampleTest.java` | 226 | `"Technical guidance demonstration completed"` |
| `peegeeq-native/.../examples/PeeGeeQExampleTest.java` | 558 | `"System monitoring demonstration completed"` |
| `peegeeq-db/.../examples/NativeVsOutboxComparisonExampleTest.java` | 56 | `"Architectural differences demonstration should complete successfully"` |
| `peegeeq-db/.../examples/NativeVsOutboxComparisonExampleTest.java` | 73 | `"Performance characteristics demonstration should complete successfully"` |
| `peegeeq-db/.../examples/NativeVsOutboxComparisonExampleTest.java` | 90 | `"Reliability features demonstration should complete successfully"` |
| `peegeeq-db/.../examples/NativeVsOutboxComparisonExampleTest.java` | 109 | `"Technical guidance demonstration should complete successfully"` |
| `peegeeq-api/.../MessageTest.java` | 63 | `"Placeholder test"` |
| `peegeeq-rest/.../MessageTypeDetectionAndValidationTest.java` | 326 | `"Batch API usage documented"` |
| `peegeeq-rest/.../MessageSendingIntegrationTest.java` | 148 | `"REST API usage documented"` |
| `peegeeq-rest/.../MessageResponseValidationTest.java` | 236 | `"Phase 3 consumption API usage documented"` |
| `peegeeq-rest/.../MessageResponseValidationTest.java` | 272 | `"Phase 3 consumption workflow documented"` |
| `peegeeq-rest/.../MessageConsumptionWorkflowTest.java` | 258 | `"Phase 3 API documentation complete"` |
| `peegeeq-rest/.../ConsumerGroupHandlerTest.java` | 343 | `"Consumer Group API documentation complete"` |
| `peegeeq-rest/.../EndToEndValidationTest.java` | 275 | `"Documentation and validation complete"` |
| `peegeeq-bitemporal/.../VertxPerformanceOptimizationValidationTest.java` | 277 | `"Performance monitoring integration validated"` |

### Variant: Tautological Comparisons That Always Pass

A related pattern uses comparisons that are always true instead of `assertTrue(true)`:

```java
// WRONG: list size is always >= 0
assertTrue(receivedMessages.size() >= 0, "Should process messages");

// WRONG: AtomicInteger(0).get() is always >= 0
assertTrue(count.get() >= 0);
```

Found in: `OutboxConsumerGroupSubscriptionEdgeCasesTest` (2 occurrences:
`testStartFromMessageId_Valid` `size() >= 0`, `testTimestamp_Future` `count.get() >= 0`).

### Required Fix

Each test must either:
1. Assert a meaningful postcondition (state change, received message content, metric value), or
2. Be deleted if it tests nothing.

---

## SERIOUS: `.onComplete(ar -> latch.countDown())` Swallows Send Failures

50+ test locations use this pattern for producer sends:

```java
// WRONG: counts down on BOTH success AND failure
producer.send(msg).onComplete(ar -> sendLatch.countDown());
assertTrue(sendLatch.await(5, TimeUnit.SECONDS), "Send should complete");
```

`onComplete` fires regardless of outcome. If the send fails (pool closed, connection
refused, serialisation error), the latch still counts down and the test proceeds with
its preconditions unmet. Downstream assertions then fail with confusing timeout errors
instead of the real cause or worse, pass vacuously because no messages arrived and
the test doesn't check for that.

### Correct Pattern

```java
// Only correct pattern: VertxTestContext Checkpoint, completion driven by .onSuccess,
// failure routed to testContext::failNow. No CountDownLatch, no .await(...).
producer.send(msg)
    .onSuccess(v -> checkpoint.flag())
    .onFailure(testContext::failNow);
```

`CountDownLatch.countDown()` + `latch.await(...)` is **not** an acceptable variant:
`.await(...)` on a latch as a Future-completion bridge is banned in test code (see
"CRITICAL: Future.await() in Test Code"). Use `VertxTestContext` + `Checkpoint`
exclusively.

### Affected Files (non-exhaustive)

| File | Approx. occurrences |
|---|---|
| `peegeeq-outbox/.../OutboxBasicTest.java` | 5 |
| `peegeeq-outbox/.../OutboxConsumerCoreTest.java` | 8 |
| `peegeeq-outbox/.../OutboxConsumerEdgeCasesCoverageTest.java` | 8 |
| `peegeeq-outbox/.../OutboxConsumerFailureHandlingTest.java` | 4 |
| `peegeeq-outbox/.../OutboxConsumerCrashRecoveryTest.java` | 3 |
| `peegeeq-outbox/.../FanOutTracePropagationTest.java` | 4 |
| `peegeeq-outbox/.../ReactiveOutboxProducerTest.java` | 3 |
| `peegeeq-outbox/.../DistributedTracingTest.java` | 2 |
| `peegeeq-outbox/.../PerformanceBenchmarkTest.java` | 2 |
| `peegeeq-outbox/.../JsonbConversionValidationTest.java` | 3 |
| `peegeeq-outbox/.../examples/MessagePriorityExampleTest.java` | 2 |
| `peegeeq-native/.../NativeQueueIntegrationTest.java` | 10+ |
| `peegeeq-native/.../PollingOnlyEdgeCaseTest.java` | 8 |
| `peegeeq-native/.../ListenNotifyOnlyEdgeCaseTest.java` | 3 |
| `peegeeq-native/.../MemoryAndResourceLeakTest.java` | 3 |
| `peegeeq-native/.../PgNativeQueueShutdownTest.java` | 2 |
| `peegeeq-native/.../RetryableErrorIT.java` | 1 |

### Same Pattern in Teardown

Multiple `@AfterEach` methods use the same swallowing pattern for `manager.closeReactive()`:

```java
// WRONG: close failure is invisible; uses CountDownLatch.await as a Future bridge
manager.closeReactive().onComplete(ar -> closeLatch.countDown());
closeLatch.await(10, TimeUnit.SECONDS);

// CORRECT: drive teardown via the injected VertxTestContext.
// Real close failures are propagated to the test (failNow), not hidden behind
// completeNow(). A failed close leaks database connections and the test must fail
// loudly so the leak is visible at the offending test, not at some later "too many
// clients" cascade.
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

Files: `OutboxConsumerEdgeCasesCoverageTest`, `OutboxConsumerCrashRecoveryTest`,
`OutboxConsumerCoreTest`, `OutboxBasicTest`, `FanOutTracePropagationTest`,
`OutboxConsumerSurgicalCoverageTest`, `OutboxBlockingSafetyTest`.

While teardown errors are less critical than test-body errors, a failed close can leak
database connections and cause subsequent tests to fail with misleading "too many clients"
errors.

---

## MEDIUM: Empty Catch Blocks in Test Teardown

27 instances of `catch (Exception ignored) {}` most in `@AfterEach` cleanup:

```java
try { factory.close(); } catch (Exception ignore) {}
try { connectionManager.close(); } catch (Exception ignored) {}
try { vertx.close(); } catch (Exception ignored) {}
```

### Assessment

These are concentrated in test teardown (`@AfterEach`, `@AfterAll`). Empty catch blocks
are never acceptable — even in teardown, silent swallowing hides resource leaks and
causes subsequent tests to fail with unrelated errors. At minimum every catch block must
log the error at WARN level so teardown failures are visible in test output. The two
occurrences in test bodies are a harder violation and must either propagate the exception
or call `testContext.failNow(e)` if inside a `VertxTestContext` scope:

| File | Line | Context |
|---|---|---|
| `PartitionedNativeConsumerIntegrationTest.java` | 122 | Test body — must log or fail |
| `ResilienceSmokeTest.java` | 118, 200 | Test body — must log or fail |

**Fix for teardown:** Replace `catch (Exception ignored) {}` with `catch (Exception e) { logger.warn("Close failed", e); }`  
**Fix for test body:** Replace with `catch (Exception e) { testContext.failNow(e); }`

---

## SERIOUS: Production `.recover()` Patterns All Wrong

A full module-by-module audit (documented in `vertx-recover-usage.md`) found **117
instances** of `.recover()` across production code. The previous version of this section
assessed 16 of these as falling into two acceptable categories. Both assessments were
wrong. Every `.recover()` in this codebase is the wrong API. The correct count and
correct classification are in `vertx-recover-usage.md`.

### 1. Shutdown/Cleanup Chains (WRONG use `.eventually()`)

In `PeeGeeQManager.closeReactive()`, `DeadConsumerDetectionJob.stop()`,
`PeeGeeQDatabaseSetupService.close()`, `OutboxConsumerGroup.stopGracefully()`,
`PgNativeConsumerGroup.startPartitioned()` (abort path), `PgNativeQueueConsumer.close()`.

The previous assessment labelled these "Acceptable." This is wrong.

`.recover()` converts a failed Future into a succeeded one. Using it for shutdown cleanup
forces every callsite to explicitly return `Future.succeededFuture()` to avoid propagating
the error which is error erasure, even in shutdown context. The correct API is
`.eventually()`, which runs a side-effect (close a pool, cancel a timer, leave a group)
without altering whether the overall Future succeeds or fails:

```java
// WRONG: .recover() erases the error
resource.close()
    .recover(e -> { logger.warn("Close failed", e); return Future.succeededFuture(); })

// CORRECT: .eventually() runs cleanup without touching the outcome
operation()
    .eventually(() -> resource.close())
```

These 27 ERASURE-IN-SHUTDOWN instances across the codebase should be converted to
`.eventually()` chains.

### 2. Background Timer Callbacks Not a `.recover()` Problem

In `PeeGeeQManager.startMetricsCollection()` and `startBackgroundTasks()`:

```java
metrics.persistMetrics(meterRegistry)
    .onFailure(e -> logger.warn("Failed to persist metrics", e));

metrics.refreshDepthCache()
    .onFailure(e -> logger.warn("Failed to refresh depth cache", e));

deadLetterQueueManager.purgeOldDeadLetterMessages(...)
    .onFailure(e -> logger.warn("Failed to cleanup old dead letter messages", e));

stuckMessageRecoveryManager.recoverStuckMessages()
    .onFailure(e -> logger.warn("Failed to recover stuck messages", e));
```

These timer callbacks correctly use `.onFailure()` not `.recover()`. `.onFailure()` is
a terminal observer that does not alter the Future chain. This is the correct API for
fire-and-forget periodic tasks.

The remaining issue is escalation: unlike `DeadConsumerDetectionJob` which tracks
`consecutiveFailures` and escalates to `logger.error` after a threshold, these four
timers log at `warn` level indefinitely with no escalation. Persistent failures are
never surfaced.

### Recommendation

Convert all ERASURE-IN-SHUTDOWN `.recover()` calls to `.eventually()`. See
`vertx-recover-usage.md` for the full classification of all 117 instances.

Add consecutive failure tracking (matching `DeadConsumerDetectionJob`'s pattern) to:
- `metrics.persistMetrics` timer
- `metrics.refreshDepthCache` timer
- `deadLetterQueueManager.purgeOldDeadLetterMessages` timer
- `stuckMessageRecoveryManager.recoverStuckMessages` timer

---

## NOT AN ANTIPATTERN: Production `.onFailure(log)` Terminal Handlers

These are in `PgConnectionProvider.java` (lines 98, 106, 114):

```java
.onFailure(error -> logger.error("Failed to get reactive connection for client: {}: {}",
    clientId, error.getMessage()));
```

These are **not** error swallowing the `.onFailure` is a terminal side-effect on
a future that is also returned to the caller. The error propagates through the future
chain; the log is supplementary. No action needed.

---

## MEDIUM: Integration Test Hygiene Antipatterns

First catalogued in `ConsumerModeResourceManagementTest` (peegeeq-native). These
patterns recur across integration tests and cause flaky cross-test interference,
resource leaks, and misleading results.

### 1. System Property Pollution (High)

`@BeforeEach` sets `System.setProperty(...)` calls but `@AfterEach` never clears
them. Stale connection details (host, port, credentials) leak to subsequent tests in
the same JVM fork, causing silent misbinding or confusing "connection refused" errors
when a different Testcontainer starts on a different port.

Found in: `ConsumerModeResourceManagementTest`, `JsonbConversionValidationTest`,
`OutboxConsumerCoreTest`, `OutboxConsumerEdgeCasesCoverageTest`,
`OutboxConsumerFailureHandlingTest`, `OutboxConsumerCrashRecoveryTest`,
`OutboxConsumerNullHandlerTest` (also missing `ssl.enabled`),
`OutboxConsumerGroupSubscriptionEdgeCasesTest` (missing `ssl.enabled` and `polling-interval`).

**Fix:** Track all property keys in a constant array and clear them in `@AfterEach`:

```java
private static final String[] SYSTEM_PROPERTIES = {
    "peegeeq.database.host", "peegeeq.database.port", ...
};

@AfterEach
void tearDown() {
    for (String prop : SYSTEM_PROPERTIES) {
        System.clearProperty(prop);
    }
}
```

### 2. Custom Container Factory Instead of Standard Helper (Medium)

Hand-rolled `createPostgresContainer()` with manual `.withDatabaseName`/`.withUsername`/
`.withPassword` instead of `PostgreSQLTestConstants.createStandardContainer()`. Misses
shared memory size, reuse policy, and any future defaults added to the standard factory.

Found in: `ConsumerModeResourceManagementTest`, `JsonbConversionValidationTest`,
`OutboxConsumerCoreTest`, `OutboxConsumerEdgeCasesCoverageTest`,
`OutboxConsumerFailureHandlingTest`, `OutboxConsumerCrashRecoveryTest`,
`OutboxConsumerErrorHandlingTest`, `OutboxConsumerNullHandlerTest`,
`OutboxConsumerGroupSubscriptionEdgeCasesTest`.

**Fix:** Replace with `PostgreSQLTestConstants.createStandardContainer()`.

### 3. Excessive Narration Logging (Medium)

~20 `logger.info` calls per test class narrating steps ("Testing connection pool
usage…", "Connection pool usage verified…", "test completed successfully"). Test names,
`@DisplayName`, and assertion messages already communicate intent. The logging clutters
output and adds no diagnostic value.

Found in: `ConsumerModeResourceManagementTest`, `JsonbConversionValidationTest`,
`OutboxConsumerCoreTest` (20× `System.err.println`),
`OutboxConsumerCrashRecoveryTest` (25+ emoji-heavy `logger.info`).

**Fix:** Remove all narration logging. Keep only `logger.debug` for genuinely useful
diagnostics if needed.

### 4. Trivial Assertions That Test Language Mechanics (Medium)

```java
assertEquals(5, consumers.size(), "All consumers should be set up successfully");
```

This asserts that `ArrayList.add()` works five times. It does not verify Vert.x instance
sharing, connection pool reuse, or any meaningful resource behaviour.

**Fix:** Delete the test, or replace with an assertion that actually verifies the
claimed behaviour (e.g., inspecting a shared Vert.x instance reference).

### 5. Producer Leak in Timer Callbacks (Medium)

Producers created inside `vertx.setTimer(...)` callbacks and closed only in `.onSuccess`:

```java
// WRONG: producers leak if any send fails
producer.send("msg1")
    .compose(v -> producer2.send("msg2"))
    .onSuccess(v -> { producer.close(); producer2.close(); })
    .onFailure(testContext::failNow);
```

**Fix:** Use `.eventually()` to close regardless of outcome:

```java
producer.send("msg1")
    .compose(v -> producer2.send("msg2"))
    .eventually(() -> {
        producer.close();
        producer2.close();
        return Future.succeededFuture();
    })
    .onFailure(testContext::failNow);
```

### 6. Resource Cleanup via `try/finally` Around a Future Chain (Low)

`try`/`finally` does **not** bracket an async Future chain. The `finally` block runs
the moment the `try` body returns control \u2014 typically *before* the chain has settled
\u2014 so it does not guarantee cleanup, and it can close a resource while the chain is
still using it.

Found in: `ConsumerModeResourceManagementTest`, `JsonbConversionValidationTest`.

**Fix:** Track resources as instance fields and close them in `@AfterEach` (driven
by the injected `VertxTestContext`), or chain cleanup via `.eventually(...)` on the
Future chain itself \u2014 see "Tier 1: Resource & Lifecycle Discipline" above and the
`QueueFactory` section below. Never wrap an async Future chain in `try`/`finally`
expecting the `finally` to bracket the async work.

### 7. Missing `hashCode()` When `equals()` Is Overridden (Medium)

Test data classes override `equals()` but not `hashCode()`, violating the
`Object.equals`/`hashCode` contract. Works by accident when only used with
`assertEquals`, but breaks silently if the object is ever placed in a `HashSet` or
used as a `HashMap` key. Also sets a bad example for copy-paste reuse.

Found in: `ConsumerModeTypeSafetyTest.TestPerson`.

**Fix:** Add a matching `hashCode()` implementation, or use `java.util.Objects.hash()`:

```java
@Override
public int hashCode() {
    return java.util.Objects.hash(name, age, email);
}
```

### 8. Unused Method Parameters (Low)

Test methods declare parameters (e.g., `Vertx vertx`) that are never referenced in
the method body. VertxExtension injects these eagerly, so there is no functional harm,
but unused parameters mislead readers into thinking the parameter is needed and add
noise.

Found in: `ConsumerModeTypeSafetyTest.testNullValueHandlingAcrossConsumerModes(Vertx vertx, ...)`,
`OutboxConsumerCoreTest` (unused `Vertx vertx` in multiple tests),
`OutboxConsumerErrorHandlingTest` (unused `Vertx vertx` in multiple tests).

**Fix:** Remove the unused parameter from the method signature.

### 9. Dead Code Branches in Shared Helpers (Low)

Helper methods contain branches that no caller ever exercises. For example, a
`if (expectedMessage == null)` path in a helper that is only ever called with non-null
values. The null case is tested via a completely separate code path in a different test
method.

Dead branches increase maintenance cost (must be kept compilable), give false
confidence that a scenario is tested, and obscure what the helper actually does.

Found in: `ConsumerModeTypeSafetyTest.testTypeSafetyForMode()` null branch never
reached because no caller passes null.

**Fix:** Remove the dead branch. If null handling needs testing, test it explicitly
where it is actually exercised.

### 10. Hand-Rolled Schema DDL / Raw JDBC in Tests (High)

Inline `CREATE TABLE` statements duplicating the production schema, executed via
raw JDBC (`java.sql.Connection`, `DriverManager`, `PreparedStatement`, `ResultSet`).
This violates the project's "no raw JDBC in tests" rule, creates schema drift risk
(inline DDL silently diverges from the real schema), and ignores the shared
`PeeGeeQTestSchemaInitializer` that other tests use.

The same problem applies to JDBC-based verification queries in test assertions —
use the reactive `PgPool.preparedQuery(...)` instead.

Found in: `JsonbConversionValidationTest.initializeSchema()` and JDBC verification
blocks in `testSimpleStringPayloadStoredAsJsonb` / `testHeadersStoredAsJsonb`;
`OutboxConsumerFailureHandlingTest` (3 JDBC blocks: lines 151, 361, 433).

**Fix (schema init):** Replace inline DDL with the shared initializer:

```java
PeeGeeQTestSchemaInitializer.initializeSchema(postgres,
        SchemaComponent.NATIVE_QUEUE,
        SchemaComponent.OUTBOX,
        SchemaComponent.DEAD_LETTER_QUEUE);
```

**Fix (verification queries):** Use the reactive pool from `DatabaseService.getPool()`:

```java
pool.preparedQuery("SELECT jsonb_typeof(payload) FROM queue_messages WHERE topic = $1")
    .execute(Tuple.of(topic))
    .compose(rows -> { ... });
```

### 11. Unnecessary Test Ordering (Low)

`@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` with `@Order(1)`, `@Order(2)`
etc. when each test uses its own topic name and is fully independent. The ordering
implies test interdependence that does not exist, discourages parallel execution, and
misleads readers into thinking execution order matters.

Found in: `JsonbConversionValidationTest`.

**Fix:** Remove `@TestMethodOrder` and all `@Order` annotations. If tests truly share
state, refactor them to be independent instead.

### 12. Dead Code After `testContext.failNow()` (Low)

```java
consumer.subscribe(message -> {
    try {
        // assertions...
        testContext.completeNow();
        return Future.succeededFuture();
    } catch (Exception e) {
        testContext.failNow(e);
        throw new RuntimeException(e); // dead code failNow already signals failure
    }
});
```

`testContext.failNow(e)` immediately fails the test. The subsequent `throw` is never
observed by the test framework and may trigger secondary error handling in the
subscribe machinery, masking the original assertion failure.

Found in: `JsonbConversionValidationTest.testConsumerCanReadJsonbObjects`.

**Fix:** Remove the `throw`. Let `failNow` handle the failure signal:

```java
} catch (Exception e) {
    testContext.failNow(e);
    return Future.failedFuture(e);
}
```

### 13. Weak Assertion Idioms (Low)

```java
// WRONG: loses type information on failure message is just "expected true"
assertTrue(member instanceof ConsumerGroupMember);
assertTrue(thrown == null, "Expected no exception");
```

JUnit 5 provides dedicated assertions that produce better failure messages and are
more readable:

```java
// CORRECT: failure message includes actual type vs expected type
assertInstanceOf(ConsumerGroupMember.class, member);
assertNull(thrown, "Expected no exception");
```

Found in: `OutboxConsumerGroupSubscriptionTest`, `OutboxBlockingSafetyTest`.

**Fix:** Replace `assertTrue(x instanceof Y)` with `assertInstanceOf(Y.class, x)`.
Replace `assertTrue(x == null, ...)` with `assertNull(x, ...)`.

### 13b. Near-Tautological Assertion Tests Handler Invocation Not Outcome (Medium)

Test asserts that a handler was called but does not verify the consequence of the
handler's return value. When the handler returns `null` (triggering a specific error
path in `processMessageWithCompletion`), the test should verify that the message
ends up retried or in a failure state not just that the handler ran.

```java
// WRONG: asserts invocation but not the null-return consequence.
// ALSO WRONG: .await() on Futures in test code — see Future.await() antipattern.
consumer.subscribe(message -> {
    invoked.set(true);
    handlerCalled.tryComplete();
    return null; // triggers IllegalStateException path
});
producer.send("Test null return").await();
handlerCalled.future().await();
assertTrue(invoked.get(), "Handler should have been invoked");
```

The production code wraps a null return as
`Future.failedFuture(new IllegalStateException("Message handler returned null Future"))`
and routes through retry/failure handling. The test should verify that outcome.

Found in: `OutboxConsumerNullHandlerTest.testNullHandlerReturn`.

**Fix:** After the handler fires, verify the message status (retried or failed) via
a reactive pool query, or use a `VertxTestContext` checkpoint on the retry path.

### 13c. Stale Javadoc Referencing Banned Types (Low)

Javadoc references `CompletableFuture` a banned type in this project and cites
stale line numbers:

```java
// WRONG: references banned type and stale line numbers
/**
 * Targets the branch at line 426-430 where handler returns null CompletableFuture.
 */
```

Found in: `OutboxConsumerNullHandlerTest` (class-level Javadoc).

**Fix:** Update to reference `Future<Void>` and remove stale line numbers:

```java
/**
 * Tests the null handler return path in OutboxConsumer.processMessageWithCompletion.
 */
```

---

## CRITICAL: Exception Thrown in `onSuccess` Is Silently Swallowed

Discovered: 2026-05-05 during full `peegeeq-db` test suite run (`feature/offset-watermark-phase1`).  
Demonstrated by: `VertxOnSuccessExceptionSwallowTest` (`peegeeq-db/.../performance/`).

### What Happens

When a `RuntimeException` is thrown synchronously inside a `Future.onSuccess(v -> ...)` callback:

1. The Vert.x event-loop context **catches the exception internally**.
2. It is routed to `vertx.exceptionHandler` (logged as `ContextImpl - Unhandled exception` at ERROR level).
3. The paired `.onFailure(...)` handler is **never called** — it only fires for Future pipeline failures, not for exceptions thrown inside callbacks.
4. `VertxTestContext` receives neither `completeNow()` nor `failNow()` — the test **hangs silently** for the full timeout (default 30 seconds) then reports only "Timeout" with no root-cause information.

The real exception is visible only as a buried ERROR log line, not as a test failure.

### Real-World Instance

`MultiConfigurationExampleTest.testMultipleConfigurationRegistration` (2026-05-05):

```java
vertx.timer(100).mapEmpty()
    .compose(v -> vertx.timer(100).mapEmpty())
    .compose(v -> vertx.timer(100).mapEmpty())
    .onSuccess(v -> {
        // ANTI-PATTERN: throws RuntimeException("Failed to register configuration: development")
        // because peegeeq-test.properties has peegeeq.database.schema=test-schema (hyphens invalid).
        // The exception is swallowed; neither completeNow() nor failNow() is ever called.
        configManager.registerConfiguration("development", "test");
        testContext.verify(() -> {
            assertEquals(4, configManager.getConfigurationNames().size()); // unreachable
            testContext.completeNow();                                      // unreachable
        });
    })
    .onFailure(testContext::failNow); // never triggered — exception is not a Future failure
```

Log evidence:
```
09:57:17.653 ERROR PgConnectionManager  - Invalid schema config: test-schema
09:57:17.655 ERROR MultiConfigurationManager - Failed to register configuration: development
09:57:17.656 ERROR ContextImpl - Unhandled exception
[ERROR] Time elapsed: 30.33 s — Timeout
```

### The Four Fixes (ranked by idiomaticness)

**Fix 1 — `testContext.succeeding()` — canonical pattern from official Vert.x docs**

The Vert.x JUnit 5 documentation shows this as the standard pattern. `testContext.succeeding(handler)`
wraps the callback such that **any exception thrown inside it is automatically considered a test failure**,
without any extra try-catch:

```java
.onComplete(testContext.succeeding(v -> testContext.verify(() -> {
    // Both succeeding() and verify() route exceptions to failNow() automatically.
    configManager.registerConfiguration("development", "test");
    assertEquals(4, configManager.getConfigurationNames().size());
    testContext.completeNow();
})));
```

Source: [Vert.x JUnit 5 docs](https://vertx.io/docs/vertx-junit5/java/) — "any exception thrown from the
[succeeding] callback is considered as a test failure."

This is the **preferred idiom** for all new test code. Do not use bare `onSuccess` when calling
synchronous methods or making assertions.

**Fix 2 — `testContext.verify()` wrapping inside `onSuccess` (ergonomic)**

`testContext.verify(Executable)` automatically catches any exception thrown inside it
and calls `failNow(e)`. Use it as the outermost wrapper around all assertion and
sync-call logic inside `onSuccess`:

```java
.onSuccess(v -> testContext.verify(() -> {
    // Any exception thrown here is automatically routed to failNow() — no silent swallow.
    configManager.registerConfiguration("development", "test");
    assertEquals(4, configManager.getConfigurationNames().size());
    testContext.completeNow();
}))
```

**Fix 3 — `try-catch` calling `testContext.failNow()` (minimum fix for existing code)**

```java
.onSuccess(v -> {
    try {
        configManager.registerConfiguration("development", "test");
        testContext.verify(() -> {
            assertEquals(4, configManager.getConfigurationNames().size());
            testContext.completeNow();
        });
    } catch (Exception e) {
        testContext.failNow(e); // surfaces the real cause immediately — no 30 s hang
    }
})
```

**Fix 4 — Move the synchronous call into the Future pipeline via `compose()`**

Wrap the throwing call as a `Future` step so failures propagate through the pipeline
and reach `onFailure` in the normal way:

```java
.compose(v -> {
    try {
        configManager.registerConfiguration("development", "test");
        return Future.succeededFuture();
    } catch (Exception e) {
        return Future.failedFuture(e); // now .onFailure(testContext::failNow) fires correctly
    }
})
.onComplete(testContext.succeeding(v -> testContext.verify(() -> {
    assertEquals(4, configManager.getConfigurationNames().size());
    testContext.completeNow();
})));
```

### Additional Warning from Official Vert.x Docs

The Vert.x Core manual explicitly states:

> **"Terminal operations like `onSuccess`, `onFailure` and `onComplete` provide no guarantee
> whatsoever regarding the invocation order of callbacks."**

This means registering multiple `onSuccess` handlers on the same future (or mixing
`onSuccess` with `onFailure`) does not guarantee which fires first. The recommended
workaround for ordered sequencing is `andThen()`, not stacked `onSuccess` calls.

### Scope — Where This Pattern Exists

Any `onSuccess` (or `onComplete(ar -> { if (ar.succeeded()) ...})`) callback that:
- calls a synchronous method that may throw, or
- contains `assertEquals` / `assertTrue` / `assertNotNull` outside a `testContext.verify()` block

is silently swallowed if the exception fires. Search the test codebase for:

```
# Find onSuccess callbacks without verify() or try-catch
grep -rn "\.onSuccess(" --include="*Test.java" | grep -v "testContext\.verify\|try {" | grep -v "::failNow\|testContext\.failing"

# Find bare assertions outside testContext wrappers
grep -rn "assertEquals\|assertTrue\|assertNotNull" --include="*Test.java" | grep -v "testContext\.verify\|testContext\.succeeding"
```

Every hit that contains assertions or sync calls without `testContext.succeeding()`, `testContext.verify()`,
or `try-catch → failNow` is vulnerable.

### Companion Test

`VertxOnSuccessExceptionSwallowTest` in `peegeeq-db/.../performance/` provides
six executable examples (three anti-pattern proofs, three safe-pattern counterparts):

| Test | What it demonstrates |
|---|---|
| `antiPattern_exceptionInOnSuccess_bypassesOnFailure` | Exception bypasses `onFailure`; reaches `vertx.exceptionHandler` instead |
| `safePattern_tryCatchInOnSuccess_preventsSwallow` | `try-catch` captures the exception |
| `antiPattern_syncCallAfterTimerChain_causesSilentHang` | Exact mirror of the real-world failure — context never completes |
| `safePattern_syncCallAfterTimerChain_preventsHang` | `try-catch` prevents the 30 s hang |
| `safePattern_compose_keepFailuresInPipeline` | `Future.failedFuture()` in `compose()` properly reaches `onFailure` |
| `safePattern_testContextVerify_autoRoutesExceptions` | `testContext.verify()` auto-routes exceptions to `failNow()` |

### Same Root Cause, Second Blast Radius: Resource Cleanup in `onSuccess`

The same swallowing mechanism applies to **any synchronous call** placed inside an
`onSuccess` lambda outside `testContext.verify()` — not just assertions. The most
common second form is **resource cleanup** (`.close()`, `.stop()`, `.shutdown()`,
`.commit()`, `.rollback()`) placed *after* the verify block:

```java
// WRONG: .close() and completeNow() are outside verify()
.onSuccess(pool -> {
    testContext.verify(() -> {
        assertNotNull(pool);
        assertTrue(pool.isOpen());
    });
    pool.close();              // if this throws, exception is swallowed by the event loop
    testContext.completeNow(); // never reached on throw — test hangs for 30 s
})
```

Failure mode is different from the assertion form but equally invisible:

- If `.close()` throws (pool already closed, connection dropped, native handle error)
  the exception is routed to `vertx.exceptionHandler`, logged as `Unhandled exception`,
  and **`completeNow()` is never reached**. The test hangs to its timeout.
- If `.close()` succeeds but a prior step has already leaked state, the leak persists
  to the next test (`PgPool`, `HttpServer`, `WebClient`, container-bound resources).

#### Correct Pattern

Move every synchronous call — assertions, resource cleanup, and `completeNow()` —
**inside** the single `testContext.verify()` block:

```java
// CORRECT: everything synchronous lives inside verify()
.onSuccess(pool -> testContext.verify(() -> {
    assertNotNull(pool);
    assertTrue(pool.isOpen());
    pool.close();
    testContext.completeNow();
}))
.onFailure(testContext::failNow);
```

This is the canonical shape for the entire codebase. `testContext.verify(Executable)`
catches any exception thrown inside it and routes it to `failNow(e)` — assertion
failures, cleanup failures, and arithmetic bugs all surface as ordinary test failures
with full stack traces instead of 30-second timeouts.

#### Acceptable Alternative: Explicit Containment Shield

When the cleanup call is allowed to fail and the test must still complete (for
example, closing a transient mock server during teardown), the explicit form is a
`try { ... } catch (Exception e) { /* log */ }` block. The catch must not re-throw:

```java
.onSuccess(server -> testContext.verify(() -> {
    assertTrue(server.isRunning());
    try {
        server.close();   // synchronous, void-returning close only
    } catch (Exception e) {
        logger.warn("Test mock server close failed (non-fatal): {}", e.getMessage());
    }
    testContext.completeNow();
}))
```

**Scope of this shield.** The shield is only valid for **synchronous, void-returning**
`close()` calls inside `testContext.verify(...)`. It is **not** an acceptable wrapper
for `Future`-returning close methods (e.g. `closeReactive()`, `closeAsync()`, anything
returning `Future<Void>`):

- `try { resource.closeReactive(); } catch (...) { ... }` would swallow only the
  synchronous part of the call and fire-and-forget the returned `Future` \u2014 the
  resource is not actually closed when `completeNow()` runs, and any async failure
  is invisible.
- For `Future`-returning closes, drive teardown via `.eventually(...)` on the test's
  outer Future chain (outside `verify(...)`), or close in `@AfterEach` driven by the
  injected `VertxTestContext`. See the `QueueFactory` and `BaseIntegrationTest`
  sections later in this document.

A bare `try { ... } catch (Exception e) {}` (empty catch) is an empty-catch
antipattern (see "MODERATE: Empty Catch Blocks in Test Teardown" above). Always log.

### Permanent Regression Boundary: CI Guard Test

The repository enforces both forms of this antipattern through a single static-analysis
test that runs in the default Maven profile:

```
peegeeq-test-support/src/test/java/dev/mars/peegeeq/test/quality/
    OnSuccessExceptionSwallowingGuardTest.java
```

It scans every `peegeeq-*/src/test/java/**.java` file with a precise tokeniser
(balanced parens/braces, string/text-block/char/comment aware) and fails the build on:

| Tier | Pattern | Detection |
|---|---|---|
| **Tier 3** | `assertX(...)` / `fail(...)` inside `onSuccess(...)` but **outside** `testContext.verify(...)` | `ASSERT_OR_FAIL` regex applied after `stripVerifyCalls` |
| **Tier 2** | `.close(`  inside `onSuccess(...)` but outside both `verify(...)` and an `onSuccess`-nested-lambda block | `CLOSE_CALL` regex applied after `stripVerifyCalls` + `stripTryCatchFailNow` + `stripNestedBraceBlocks` |

A `try { ... } catch (Throwable|Exception ...) { /* no re-throw */ }` block around
the sync call is treated as a containment shield and stripped before detection
(this is the explicit acceptable-alternative form documented above).

Properties:

- Runs in default profile (`@Tag(TestCategories.CORE)`, no `-Pintegration-tests` needed).
- ≈ 0.3 s total; no database or Testcontainers required.
- Failure report is a list of `[Tier N] absolute/path/File.java:line — snippet` entries.
- The companion `VertxOnSuccessExceptionSwallowTest` (above) provides six runtime
  proofs; the guard provides static enforcement. Both layers run on every PR.

If a future change reintroduces either Tier 2 or Tier 3 in any module's tests, the
build fails before the offending code can land. **Do not relax, exclude, or weaken
the guard to make a test pass — fix the test by moving the sync call inside
`testContext.verify(...)`.**

---

## HIGH: Discarded `Future<Void>` From Stop/Close Methods in Test Compose Chains

Discovered: 2026-05-12 from full `peegeeq-db` test suite run.  
Root cause of: `DeadConsumerDetectionJobIntegrationTest.testEndToEndDetectCleanupPipeline` — `expected: <COMPLETED> but was: <PENDING>`.

### What Happens

`DeadConsumerDetectionJob.stop()` returns a `Future<Void>` that resolves only after
any in-flight detection cycle — including `cleanupAllDeadGroups` which writes
`status='COMPLETED'` to the database — has fully completed.

The original test code discarded this Future (fire-and-forget):

```java
.compose(messageIds -> {
    job.stop();                        // Future<Void> discarded — returns immediately
    assertTrue(job.getTotalDeadDetected() >= 1, ...);
    return getSubscriptionStatus(topic, "group-b")
            .map(v -> messageIds);
})
```

The compose chain continued while the in-flight cleanup was still executing. The
subsequent `verifyCompletedMessagesAndReturn` query ran before the `UPDATE outbox
SET status='COMPLETED'` SQL had been written, finding `PENDING` instead.

The same test passed in isolation because the single-test run had no concurrent
activity making the timing window observable.

### Pattern

```java
// WRONG: Future<Void> discarded — stop() not awaited
.compose(messageIds -> {
    job.stop();
    // assertions run immediately — cleanup may still be in-flight
    assertTrue(job.getTotalDeadDetected() >= 1, ...);
    return verifyState().map(v -> messageIds);
})

// CORRECT: compose on stop() — assertions run only after stop completes
.compose(messageIds -> job.stop().compose(v -> {
    assertTrue(job.getTotalDeadDetected() >= 1, ...);
    return verifyState().map(ignored -> messageIds);
}))
```

### Rule

Any `stop()`, `close()`, or `shutdown()` method that returns `Future<Void>` and is
called as a prerequisite to an assertion **must** be composed on — not called and
ignored. This applies in test code exactly as in production code.

### Fixed In

`peegeeq-db/src/test/java/dev/mars/peegeeq/db/fanout/DeadConsumerDetectionJobIntegrationTest.java`
— `testEndToEndDetectCleanupPipeline`, 2026-05-12.

---

## HIGH: Background Jobs Left Enabled When Creating `PeeGeeQManager` Directly in Tests

Discovered: 2026-05-12 from full `peegeeq-db` test suite run.  
Root cause of: `CompletionTrackerIntegrationTest.testMarkFailedRepeatedlyIncrementsRetryCount` — `expected: <error 2> but was: <null>`.

### What Happens

Any test that constructs a `PeeGeeQManager` with a custom `Properties` object without
explicitly disabling background jobs inherits the production defaults, which enable both:

- `peegeeq.queue.dead-consumer-detection.enabled` — default `true`
- `peegeeq.queue.consumer-group-retry.enabled` — default `true`

`ConsumerGroupRetryService` (started by the retry job) executes on a periodic timer:

```sql
UPDATE outbox_consumer_groups
SET status = 'PENDING', error_message = NULL
WHERE status = 'FAILED'
  AND retry_count < max_retries
  AND ...
```

This SQL clears `error_message` on **all** `FAILED` rows in the shared database —
across every test running concurrently. `DeadConsumerDetectionJobLifecycleTest` started
a manager with `dead-consumer-detection.enabled=true` but did not set
`consumer-group-retry.enabled=false`, so `ConsumerGroupRetryJob` started and polluted
rows owned by `CompletionTrackerIntegrationTest` running in parallel.

The pollution was invisible in isolation runs — it only manifested under concurrent
execution because the race window between `markFailed` and `getTrackingRowStatus` is
too small to be hit when no other timer is firing.

### Race Timeline

```
[lifecycle test setUp]       PeeGeeQManager starts → ConsumerGroupRetryJob timer starts
[completion tracker test]    markFailed("error 1") → error_message="error 1", retry_count=1
[completion tracker test]    markFailed("error 2") → error_message="error 2", retry_count=1
[ConsumerGroupRetryJob fires] → error_message=NULL, status=PENDING  (retry_count unchanged)
[completion tracker test]    getTrackingRowStatus → error_message=null  ← FAIL
```

Note: `retry_count` was NOT reset (the retry service only resets `status` and
`error_message`), which is why the `retry_count=1` assertion passed while
`error_message=null` failed.

### Pattern

```java
// WRONG: relies on defaults — ConsumerGroupRetryJob starts globally
testProps.setProperty("peegeeq.queue.dead-consumer-detection.enabled", "true");
// no consumer-group-retry.enabled → defaults to true → retry job pollutes shared DB

// CORRECT: explicitly disable every background job the test does not need
testProps.setProperty("peegeeq.queue.dead-consumer-detection.enabled", "true");
testProps.setProperty("peegeeq.queue.consumer-group-retry.enabled", "false");
```

### Rule

Any test that creates a `PeeGeeQManager` directly (not via `BaseIntegrationTest`) must
explicitly set all background-job-enabled properties to a known safe value. Do not
rely on defaults. Properties to consider:

| Property | Default | Danger if left enabled |
|---|---|---|
| `peegeeq.queue.dead-consumer-detection.enabled` | `true` | Marks subscriptions DEAD globally |
| `peegeeq.queue.consumer-group-retry.enabled` | `true` | Resets `error_message=NULL` globally on all FAILED rows |

`BaseIntegrationTest` disables both. Every test that bypasses it must replicate that.

### Fixed In

`peegeeq-db/src/test/java/dev/mars/peegeeq/db/DeadConsumerDetectionJobLifecycleTest.java`
— added `peegeeq.queue.consumer-group-retry.enabled=false` in `setUp()`, 2026-05-12.

---

## MEDIUM: Test Teardown Cleanup SQL Running After Pool Is Closed

Discovered: 2026-05-12 from full `peegeeq-db` test suite run.  
Observed in: `HealthCheckManagerTest` — `WARN Failed to cleanup test data: Pool closed`.

### What Happens

When a test closes its `PeeGeeQManager` (or pool) before running cleanup SQL in
`@AfterEach`, the pool is already closed by the time the cleanup query fires:

```
09:38:39.264  PgConnectionManager  — Closed reactive pool for service: peegeeq-main
09:38:39.273  HealthCheckManagerTest — Failed to cleanup test data: Pool closed   ← WARN
09:38:39.273  HealthCheckManagerTest — Failed to cleanup test data in tearDown     ← WARN
```

This does not fail the test itself, but it:
1. Leaves dirty data in the shared database that can affect subsequent tests
2. Produces unintentional WARN-level log noise that obscures genuine warnings
3. Masks the real teardown bug when future tests fail due to leftover state

### Pattern

```java
// WRONG: manager (and pool) closed before cleanup SQL runs
manager.closeReactive()
    .eventually(() -> cleanupSql(pool))    // pool already closed — SQL fails
    .onComplete(...)

// CORRECT: run cleanup SQL first, close pool last
cleanupSql(pool)
    .eventually(() -> manager.closeReactive())
    .onComplete(...)
```

### Rule

In `@AfterEach`, reactive cleanup SQL must run **before** the pool or manager is closed.
The close must be the terminal `.eventually(...)` step, not the first one.

---

## MEDIUM: Metrics Timer Left Running After Container Stops

Discovered: 2026-05-12 from full `peegeeq-db` test suite run.  
Observed in: `PeeGeeQManager` — recurring `WARN/ERROR Failed to persist metrics: Connection refused: localhost/127.0.0.1:54878`.

### What Happens

A `PeeGeeQManager` configured with a short metrics interval (`PT1S`) continues firing
its periodic timer after the Testcontainers PostgreSQL container it was pointed at has
stopped. The container stops when its owning test ends, but the manager's Vert.x timer
is still alive:

```
09:38:58.999  PeeGeeQManager — WARN  Failed to persist metrics: Connection refused: localhost/127.0.0.1:54878
09:39:01.000  PeeGeeQManager — ERROR Failed to persist metrics (3 consecutive failures): Connection refused
09:39:04.010  PeeGeeQManager — ERROR Failed to persist metrics (6 consecutive failures): Connection refused
```

This happens when the manager's reactive close is not properly awaited before the
container stops — either because the close was discarded (see "Discarded Future" entry
above) or because the test's `@AfterEach` does not compose on the close Future.

The escalating ERROR log pattern (consecutive failure counter) is correct production
behaviour, but it should never fire in tests because the manager must be fully closed
before the container stops.

### Rule

Any test that uses a short metrics interval (`PT1S`, `PT5S`) must ensure
`manager.closeReactive()` (or `manager.close()`) is fully awaited — via compose or
`awaitCompletion` — before the Testcontainers container stops. The container must
outlive the manager, not the reverse.

```java
// WRONG: manager.closeReactive() Future not awaited before container stops
@AfterEach
void tearDown() {
    manager.closeReactive(); // Future discarded — timer may still fire when container stops
}

// CORRECT: await close before test ends
@AfterEach
void tearDown(VertxTestContext ctx) {
    manager.closeReactive()
        .onSuccess(v -> ctx.completeNow())
        .onFailure(ctx::failNow);
    assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS));
}
```

---

## MEDIUM: Asserting on Log Message Strings Instead of Exception Type

**Severity**: MEDIUM

### Problem

Tests that intentionally trigger a known failure scenario (e.g. stopping a DB container to
produce connection errors) assert on substrings of the logged message rather than on the
exception type. This means:

- The assertion passes if any failure occurs, not just the expected one.
- If `PeeGeeQManager` swallows the exception and logs only `e.getMessage()`, the test still
  passes even though the Throwable was never attached to the log event.
- The test does not prove that the scenario under test is actually what caused the failure.

```java
// WRONG: string-match assertions do not verify the exception was propagated
boolean allWarnsHaveConnectCause = warns.stream()
        .allMatch(e -> e.getFormattedMessage().contains("Connection refused"));
assertTrue(allWarnsHaveConnectCause, ...);
```

### Why It Matters

A test that stops a DB container and asserts the text `"Connection refused"` appeared in a
log message would also pass if the timer failed for an unrelated reason (NPE, misconfiguration)
and the log happened to contain that substring from a previous event. The test scenario is
not actually being verified.

### Fix

Assert on the **exception type** via `IThrowableProxy`, using a helper that walks the cause
chain:

```java
private boolean hasCauseOfType(IThrowableProxy proxy, String className) {
    while (proxy != null) {
        if (className.equals(proxy.getClassName())) return true;
        proxy = proxy.getCause();
    }
    return false;
}
```

Then assert:

```java
// PRIMARY: every captured failure must carry ConnectException in the cause chain.
// If the container-stop scenario did not produce connection failures, or if
// PeeGeeQManager swallowed the cause, this fails immediately.
assertFalse(warns.isEmpty(), "Expected WARN events from timer failures; none captured");
assertFalse(errors.isEmpty(), "Expected ERROR events from timer escalation; none captured");

boolean allWarnsHaveConnectException = warns.stream()
        .allMatch(e -> hasCauseOfType(e.getThrowableProxy(), "java.net.ConnectException"));
assertTrue(allWarnsHaveConnectException,
        "Every WARN must carry ConnectException — proves DB-stopped scenario. " +
        "WARN count: " + warns.size());

boolean allErrorsHaveConnectException = errors.stream()
        .allMatch(e -> hasCauseOfType(e.getThrowableProxy(), "java.net.ConnectException"));
assertTrue(allErrorsHaveConnectException,
        "Every ERROR must carry ConnectException — proves DB-stopped scenario. " +
        "ERROR count: " + errors.size());
```

`java.net.ConnectException` is a stable JDK type meaning "could not reach the host".
`IThrowableProxy.getClassName()` and `getCause()` are part of the Logback SPI — no new
imports required beyond `ch.qos.logback.classic.spi.IThrowableProxy` (same package as
`ILoggingEvent`, which is already imported).

**The escalation-behaviour assertions (WARN vs ERROR, count in message) are secondary**.
They are only meaningful once the primary assertion has proven the cause is what the test
intends. Put the exception-type assertions first.

### Scope

- `PeeGeeQManagerTimerGuardTest.testTimerFailuresEscalateWarnToError` — primary fix site
- Any other test that captures `ILoggingEvent` events from an intentional-failure scenario
  and asserts on message text instead of the attached `Throwable`

---

## Summary of Required Actions

> **All items below are mandatory.** Severity describes the impact of the violation — the higher the severity, the faster it causes test failures or masks production bugs. Every item must be addressed regardless of severity level.

| Severity | Required Action | Scope |
|---|---|---|
| CRITICAL | Replace bare `onSuccess` containing assertions or throwing calls with `testContext.succeeding(v -> testContext.verify(...))` — the canonical pattern from Vert.x JUnit 5 docs | All test classes with bare `onSuccess` callbacks |
| CRITICAL | Replace `assertTrue(true, ...)` with real assertions or delete test | 27 tests |
| SERIOUS | Replace `.onComplete(ar -> latch.countDown())` with `.onSuccess`/`.onFailure` | 50+ sends |
| HIGH | Clear system properties in `@AfterEach` | Integration tests using `System.setProperty` |
| MEDIUM | Use `PostgreSQLTestConstants.createStandardContainer()` | Tests with hand-rolled container setup |
| MEDIUM | Remove narration logging from tests | Tests with excessive `logger.info` |
| MEDIUM | Delete or replace trivial assertions that test language mechanics | Tests asserting collection size after add |
| MEDIUM | Close producers in `.eventually()` not `.onSuccess()` | Timer callbacks creating producers |
| MEDIUM | Add `hashCode()` where `equals()` is overridden | Test data classes with `equals()` only |
| MODERATE | Add logging to catch blocks in test bodies (not teardown) | 2-3 locations |
| LOW | Move resource allocation inside try/finally | Tests with resources before try block |
| LOW | Remove unused method parameters (`Vertx vertx`, etc.) | Test methods with injected but unused params |
| HIGH | Compose on `Future<Void>` from `stop()`/`close()` — never discard | Test compose chains calling job/service stop |
| HIGH | Explicitly disable all background jobs not under test when constructing `PeeGeeQManager` directly | Tests not extending `BaseIntegrationTest` |
| MEDIUM | Run cleanup SQL before closing pool in `@AfterEach` | Tests with reactive teardown cleanup queries |
| MEDIUM | Await `manager.closeReactive()` before Testcontainers container stops | Tests with short metrics intervals (`PT1S`, `PT5S`) |
| LOW | Remove dead code branches in shared test helpers | Helpers with never-exercised paths |
| HIGH | Replace hand-rolled schema DDL / raw JDBC with shared initializer + reactive pool | Tests with inline CREATE TABLE or JDBC verification |
| LOW | Remove unnecessary `@TestMethodOrder` / `@Order` on independent tests | Tests with ordering annotations but no shared state |
| LOW | Remove dead code after `testContext.failNow()` | Subscribe handlers with redundant `throw` after failNow |
| LOW | Replace `assertTrue(x instanceof Y)` with `assertInstanceOf` / `assertNull` | Tests using weak assertion idioms |
| MEDIUM | Replace commented-out `//@Test` with `@Disabled("reason")` or fix and re-enable | 1 test in OutboxConsumerFailureHandlingTest |
| HIGH | Replace `LockSupport.parkNanos()` / `Thread.sleep` with event-driven waits (future chains, checkpoints, DB state observation) | 18 occurrences across 10 files |
| CRITICAL | Replace `setTimer → completeNow()` timeout handlers with `failNow()` | 7 tests in `peegeeq-rest` |
| HIGH | Replace `setTimer` readiness guards with future chains off `subscribe()` / `deployVerticle()` | 6 setUp methods in `peegeeq-rest`; 9 data-wait timers |
| HIGH | Replace 100 ms drain delay in `DatabaseTemplateManager` with `pg_stat_activity` poll loop | `peegeeq-db/.../DatabaseTemplateManager.java:140` |
| SERIOUS | Attach `.onFailure(testContext::failNow)` to fire-and-forget `producer.send()` (do NOT use `.await()` — see CRITICAL `Future.await()` antipattern) | 7 occurrences in OutboxConsumerErrorHandlingTest |
| MEDIUM | Replace near-tautological handler-invocation assertions with outcome verification | OutboxConsumerNullHandlerTest |
| LOW | Fix stale Javadoc referencing banned `CompletableFuture` type | OutboxConsumerNullHandlerTest |
| MEDIUM | Replace `withConnection()` with `withTransaction()` for all write operations | `DeadLetterQueueManager` and any other class using `withConnection` for DML |
| CRITICAL | Revert `Thread.sleep` "strategic delays" and threshold-based leak masking introduced as concurrency "fixes" | `peegeeq-db` test classes |
| SERIOUS | Delete blocking `executeSupplier`/`executeRunnable`/`executeDatabaseOperation`/`executeQueueOperation` methods from `CircuitBreakerManager` | `peegeeq-db/.../resilience/CircuitBreakerManager.java` |
| MEDIUM | Move misplaced tests to their correct subject class | `testCircuitBreakerIntegration` in `OutboxMetricsTest` |
| MEDIUM | Replace `LockSupport.parkNanos`/`while`-loop polling with recursive `vertx.timer` chain driven by `VertxTestContext`; do NOT use `vertx.executeBlocking(...)` as an escape hatch | `testHealthCheckIntegration` spin-poll in `OutboxMetricsTest` |
| LOW | Fix logger messages that identify the wrong test (copy-paste contamination) | `OutboxMetricsTest` 3 mismatched log strings |
| SERIOUS | Fix property-key mismatch in `BaseIntegrationTest.setupTestConfiguration()` use `*-ms` suffixed keys the loader actually reads | `BaseIntegrationTest.java` lines 179-183 |
| SERIOUS | Add `peegeeq.database.pool.shared=false` in `BaseIntegrationTest.setupTestConfiguration()` | `BaseIntegrationTest.java` currently absent, tests inherit `shared=true` production default |
| SERIOUS | Add `@AfterEach` calling `connectionManager.close()` to 28 integration test classes that create secondary `PgConnectionManager` instances | All files listed in §3c of `test-suite-connection-exhaustion-investigation.md` |
| SERIOUS | Remove `@AfterEach` grace timer and `awaitFuture` bridge helper; drive teardown via injected `VertxTestContext` calling `closeReactive()` directly | `BaseIntegrationTest.tearDownBaseIntegration()` 33% of teardowns silently skip `closeReactive()` |
| LOW | Remove `System.gc()` from `BaseIntegrationTest.@AfterEach` | `BaseIntegrationTest.tearDownBaseIntegration()` GC-based resource cleanup is not deterministic |
| HIGH | Add contract tests for `BaseIntegrationTest` pool configuration so property-key regressions are caught automatically | New test class `BaseIntegrationTestPoolConfigContractCoreTest` (`@Tag(CORE)`) |
| SERIOUS | Remove manual `Vertx.vertx()` creation in classes annotated with `@ExtendWith(VertxExtension.class)` — either remove the extension or remove the manual instance | `ServiceDiscoveryExampleTest` and any class with both |
| SERIOUS | Fix `@AfterEach` teardown to close resources in strict reverse-construction order using nested `finally` blocks | All integration tests with multi-resource teardown |
| MEDIUM | Wrap code between `new VertxTestContext()` creation and `awaitCompletion` in `try/finally` to prevent orphaned contexts | Any test creating inline `VertxTestContext` instances |
| MEDIUM | Assert on exception type via `IThrowableProxy.getClassName()` cause-chain walk, not on log message substrings | `PeeGeeQManagerTimerGuardTest` and any test capturing `ILoggingEvent` from intentional failure scenarios |

---

## MEDIUM: Commented-Out `@Test` Hidden Test Disabling

Tests disabled by commenting out the `@Test` annotation instead of using `@Disabled`
with a reason:

```java
// WRONG: invisible to test runners and reports
//@Test
void testRetryLogicWithFailingMessages(Vertx vertx, VertxTestContext testContext) {
```

This is worse than `@Disabled` because:
1. The test is invisible to test runners, reports, and IDE test views.
2. There is no structured reason string that tools can surface.
3. `grep` for disabled tests will miss it.
4. It silently rots no one notices it exists.

Found in: `OutboxConsumerFailureHandlingTest` (line 128, `testRetryLogicWithFailingMessages`).

**Fix:** Either re-enable the test and fix the timing sensitivity, or use `@Disabled`
with a reason so it appears in test reports:

```java
@Test
@Disabled("Timing sensitive requires investigation (see #NNN)")
void testRetryLogicWithFailingMessages(...) { ... }
```

---

## HIGH: `LockSupport.parkNanos()` Blocking Thread Delay in Tests

Tests using `LockSupport.parkNanos()` to introduce fixed delays. This is semantically
equivalent to `Thread.sleep()` it blocks the current thread for a fixed duration.
Both are forbidden in a reactive codebase.

```java
// WRONG: blocks the thread for 1 second
LockSupport.parkNanos(1_000_000_000L);
```

Problems:
1. Blocks the thread, defeating the purpose of a reactive/non-blocking architecture.
2. Fixed delays are inherently flaky too short on slow CI, wastefully long otherwise.
3. On Vert.x event-loop threads, blocks the entire event loop.

### Affected Files

| File | Occurrences | Delay |
|---|---|---|
| `OutboxConsumerCrashRecoveryTest` | 2 | 1s, 2s |
| `CircuitBreakerRecoveryTest` | 3 | 250ms each |
| `FilterErrorHandlingIntegrationTest` | 2 | 50ms, 600ms |
| `MessageReliabilityTest` | 1 | 50ms |
| `OutboxMetricsTest` | 4 | 100–200ms |
| `OutboxPerformanceTest` | 1 | 10ms |
| `FilterRetryManagerTest` | 1 | 20ms |
| `SystemPropertiesConfigurationExampleTest` | 1 | 50ms |
| `JdbcIntegrationHybridExampleTest` | 2 | 1ms each |
| `AutomaticTransactionManagementExampleTest` | 1 | 1ms |

**Fix:** Redesign the test to react to an observable condition: future completion,
checkpoint flag, or a row appearing in the database. In a reactive Vert.x system there
is no legitimate test use for a fixed-delay timer if an operation produces a `Future`,
chain off it; if it produces a side-effect, observe the side-effect directly.
See the `setTimer` readiness guard section below for the full pattern inventory.

---

## HIGH: `setTimer` as a Readiness Guard

Using `vertx.setTimer()` with an arbitrary delay to wait for an async setup operation
to complete before acting on it. The timer fires after a wall-clock interval, not when
the operation actually finishes.

```java
// WRONG races against actual readiness
consumer.subscribe(handler);
vertx.setTimer(1000, id -> {
    producer.send("test message");
});
```

`MessageConsumer.subscribe()` returns `Future<Void>` that completes when the PostgreSQL
`LISTEN` acknowledgement is received. The timer is a guess at when that completes. The
correct pattern chains directly off the returned future:

```java
// CORRECT
consumer.subscribe(handler)
    .onSuccess(ignored -> producer.send("message").onFailure(testContext::failNow))
    .onFailure(testContext::failNow);

// CORRECT multiple consumers
Future.all(consumer1.subscribe(handler1), consumer2.subscribe(handler2))
    .onSuccess(ignored -> producer.send("message").onFailure(testContext::failNow))
    .onFailure(testContext::failNow);
```

**Rule:** In a reactive Vert.x system, timers have no place in tests. Every async
operation produces a `Future` chain off it. Every side-effect has an observable
consequence observe it directly. A timer is always a guess at when something will be
ready; the correct answer is always to know when it is ready.

### Variant: Timeout handler calls `completeNow()` instead of `failNow()`

The most deceptive form. The timer fires when the expected event *has not arrived*, and
instead of failing the test it marks it as passed:

```java
// WRONG test passes if the event never arrives
vertx.setTimer(1000, id -> {
    logger.info("All endpoints integration test completed");
    testContext.completeNow();  // no assertions made
});
```

This is a variant of the CRITICAL placeholder-test pattern, implemented via a timer
rather than `assertTrue(true)`. The test is behaviorally identical to a no-op.

**Fix:** Replace `completeNow()` in timeout handlers with
`testContext.failNow(new AssertionError("Expected event did not arrive within timeout"))`.

Affected files in `peegeeq-rest`:

| File | Line | Description |
|---|---|---|
| `EndToEndValidationTest.java` | 227 | `testAllEndpointsIntegration` waits 1 s, `completeNow()`, zero assertions |
| `WebSocketHandlerTest.java` | 301 | "No subscription confirmation" timeout → `completeNow()` |
| `WebSocketHandlerTest.java` | 356 | "No configuration confirmation" timeout → `completeNow()` |
| `SystemMonitoringHandlerTest.java` | 294 | "Metrics don't arrive" timeout → `completeNow()` |
| `SystemMonitoringHandlerTest.java` | 443 | WS metrics timeout → `completeNow()` |
| `SystemMonitoringHandlerTest.java` | 553 | SSE metrics timeout → `completeNow()` |
| `ConsumerGroupSubscriptionIntegrationTest.java` | 1010 | SSE configured event timeout → `completeNow()` |

### Variant: Post-`deployVerticle` readiness guard

`deployVerticle()` only completes after `Verticle.start()` finishes. If `start()`
awaits the `HttpServer.listen()` future, the server is listening the moment `onSuccess`
fires. A subsequent `setTimer(1000, ...)` inside `onSuccess` is pure waste.

```java
// WRONG timer after deploy is redundant if start() awaits listen()
vertx.deployVerticle(server)
    .onSuccess(id -> {
        deploymentId = id;
        vertx.setTimer(1000, timerId -> testContext.completeNow());
    });

// CORRECT trust the deploy future
vertx.deployVerticle(server)
    .onSuccess(id -> { deploymentId = id; testContext.completeNow(); })
    .onFailure(testContext::failNow);
```

Affected files (all in `peegeeq-rest`, all with `"Give server time to fully start"` comments):
`EndToEndValidationTest.java:70`, `EventStoreIntegrationTest.java:83`,
`ConsumerGroupSubscriptionIntegrationTest.java:96`, `SSEBasicStreamingIntegrationTest.java:113`,
`SSEBatchingIntegrationTest.java:101`, `SSEReconnectionIntegrationTest.java:103`.

**Fix:** Remove the timer and chain the next setup step directly off `onSuccess`. If the
server is not ready when `start()` completes, fix `start()` to return a `Future` that
resolves only after `HttpServer.listen()` completes.

### Variant: Wall-clock wait for SSE/WebSocket buffer to accumulate

Timers that fire after connecting to an SSE or WebSocket stream, wait N seconds, then
inspect the accumulated receive buffer. They race against actual message delivery.

Affected: 9 locations in `peegeeq-rest`, including `ConsumerGroupSubscriptionIntegrationTest`
(lines 245, 354, 529, 785, 799, 1136), `CrossLayerPropagationIntegrationTest:262`,
`SystemMonitoringIntegrationTest:138`, `QueueManagementE2ETest:223`.

**Fix:** Use an event-driven handler that acts as soon as the expected event appears, then
call `testContext.completeNow()`.

### Variant: Production code timing assumption (correctness risk)

**File:** `peegeeq-db/.../DatabaseTemplateManager.java:140`

```java
// WRONG assumes 100 ms is long enough for terminated connections to drain
return connection.preparedQuery(terminateConnectionsSql)
    .execute(Tuple.of(databaseName))
    .compose(rowSet -> Future.<Void>future(promise -> {
        vertx.setTimer(100, id -> promise.complete());
    }))
    .compose(v -> connection.query("DROP DATABASE IF EXISTS ...").execute());
```

`pg_terminate_backend()` is asynchronous at the PostgreSQL level backends may not
have disconnected when the timer fires. Under load this causes `DROP DATABASE` to fail
with "there are other sessions using the database."

**Fix:** Replace with a polling loop that retries `DROP DATABASE` until `pg_stat_activity`
reports zero connections for that database.

---

## SERIOUS: Fire-and-Forget `producer.send()` Without Await or Failure Handler

Calls to `producer.send(...)` whose returned `Future` is completely ignored not
awaited, not chained, and no `.onFailure()` handler attached:

```java
// WRONG: send failure is silently lost
producer.send("test-message");
```

If the send fails (serialisation error, pool closed, connection refused), the test
proceeds as if the precondition was met. Downstream consumer assertions then fail
with a timeout or pass vacuously because no message was delivered.

Found in: `OutboxConsumerErrorHandlingTest` (lines 142, 161, 183, 207, 256, 305, 329
7 occurrences across `testRetryMechanism`, `testMessageFailureHandling`,
`testMessageWithNullPayload`, `testInterruptedExceptionHandling`,
`testConcurrentMessageProcessing`).

**Fix:** Attach a failure handler so a send failure surfaces as a test failure. Do NOT
use `Future.await()` — see the CRITICAL `Future.await()` antipattern further below; it
deadlocks the test thread.

```java
// CORRECT — consumer-side checkpoint completes testContext on success
producer.send("test-message")
    .onFailure(testContext::failNow);

// If the test must observe the send completing before continuing, chain explicitly:
producer.send("test-message")
    .compose(v -> nextStep())
    .onSuccess(v -> testContext.completeNow())
    .onFailure(testContext::failNow);
```
| SERIOUS | Convert all ERASURE-IN-SHUTDOWN `.recover()` calls to `.eventually()` | 27 production instances see `vertx-recover-usage.md` |
| LOW | Add consecutive failure tracking to background timer callbacks | 4 timers |

---

## SERIOUS: Blocking Wrapper Methods on a Reactive Factory (CircuitBreakerManager)

`CircuitBreakerManager` is a registry and factory for Resilience4j `CircuitBreaker`
objects. The established production pattern used correctly in `HealthCheckManager` —
is for callers to retrieve a `CircuitBreaker` and drive it themselves:

```java
// CORRECT caller owns the permission check, timing, and result recording
CircuitBreaker cb = circuitBreakerManager.getCircuitBreaker(cbName);
if (!cb.tryAcquirePermission()) {
    return Future.succeededFuture(HealthStatus.unhealthy(...));
}
long start = System.nanoTime();
return operation.get()
    .onSuccess(status -> cb.onSuccess(duration, TimeUnit.NANOSECONDS))
    .onFailure(t   -> cb.onError(duration, TimeUnit.NANOSECONDS, t));
```

`CircuitBreakerManager` also contained four methods that violate this design:

```java
public <T> T executeSupplier(String name, Supplier<T> supplier)
public void   executeRunnable(String name, Runnable runnable)
public <T> T executeDatabaseOperation(String operation, Supplier<T> supplier)
public <T> T executeQueueOperation(String queueType, String operation, Supplier<T> supplier)
```

These are wrong on multiple counts:

1. **They block the Vert.x event loop.** All four accept synchronous `Supplier<T>` and
   return raw `T` by executing the supplier inline. Any database or queue operation
   passed through these methods blocks the calling thread.

2. **They are dead code with no legitimate future use.** Every real operation in PeeGeeQ
   is asynchronous. These methods exist only to make the class testable via a synchronous
   test harness. They were written for test convenience, not for any production caller.

3. **They smuggle the JDBC-era Resilience4j idiom into a reactive codebase.** The
   `executeSupplier(name, () -> doSomethingBlocking())` pattern is designed for blocking
   applications JDBC, RestTemplate, blocking HTTP clients. PeeGeeQ uses the reactive
   Vert.x PostgreSQL client. Introducing these methods here is the same category of
   mistake as calling `DriverManager.getConnection()` in a test.

4. **They introduced a second, incompatible usage pattern.** `HealthCheckManager` shows
   the correct way to use circuit breakers. These four methods show a different, wrong
   way. A future developer reading the class must now guess which pattern to follow.

**Fix:** Delete all four methods. No reactive equivalent is needed `HealthCheckManager`
is the reference implementation for any future caller.

---

## MEDIUM: Scope Leakage Tests Placed in the Wrong Class

A test for subsystem B deposited inside the test class for subsystem A because A's setup
happens to be available or convenient. Found in `OutboxMetricsTest`, which contained
`testCircuitBreakerIntegration` a test that called `CircuitBreakerManager` methods,
needed a full Testcontainers PostgreSQL container, and used none of the outbox metrics
infrastructure.

**Three harms:**

1. **The class's stated scope cannot be trusted.** If circuit-breaker tests are in
   `OutboxMetricsTest`, anything else might be too. Readers cannot rely on the class name.

2. **Failure attribution is wrong.** A `CircuitBreakerManager` regression surfaces as a
   failure in the outbox metrics test run, misleading diagnosis.

3. **The test becomes collateral damage.** When the wrong API was deleted from
   `CircuitBreakerManager`, this test would have failed with a compile error in a class
   that has nothing to do with the deletion.

**Rule:** A test class owns exactly one subject. If a test requires setup from class A
to test behaviour in class B, it belongs to class B's test suite. Never deposit tests in
a host class because setup already exists there.

---

## MEDIUM: Blocking Wait on the Test Thread Under `VertxExtension`

A polling loop executing directly on the test thread inside a class annotated with
`@ExtendWith(VertxExtension.class)`. Found in `OutboxMetricsTest.testHealthCheckIntegration`:

```java
// WRONG parks the test thread under VertxExtension
while (System.currentTimeMillis() < deadline) {
    var status = healthCheckManager.getOverallHealth();
    if (status != null && status.isHealthy()) break;
    LockSupport.parkNanos(200_000_000L);
}
```

Under `VertxExtension`, the test thread shares lifecycle state with `VertxTestContext`.
Parking it is indistinguishable from an unresponsive test, races with `VertxTestContext`
timeout handling, and hides the wait from the Vert.x scheduler entirely.

**Fix:** Express the poll as a recursive `vertx.timer` chain. Each retry is a fresh
Future continuation scheduled on the event loop; the deadline is carried in the chain;
the test thread never blocks. `vertx.executeBlocking(...)` is **not** an acceptable
escape hatch — it smuggles `Thread.sleep`/`LockSupport.park`/spin-loop polling back
into a reactive system, all of which are independently banned (see the HIGH
`LockSupport.parkNanos()` section above and the project coding principles).

```java
// CORRECT — recursive timer poll, no blocking, no executeBlocking
long deadline = System.currentTimeMillis() + 10_000;
pollHealth(vertx, healthCheckManager, deadline)
    .onSuccess(status -> testContext.verify(() -> {
        assertTrue(status.isHealthy());
        testContext.completeNow();
    }))
    .onFailure(testContext::failNow);

// Helper — recurses via Future composition, not via a loop:
private Future<HealthStatus> pollHealth(Vertx vertx, HealthCheckManager hcm, long deadline) {
    HealthStatus status = hcm.getOverallHealth();
    if (status != null && status.isHealthy()) {
        return Future.succeededFuture(status);
    }
    if (System.currentTimeMillis() >= deadline) {
        return Future.failedFuture(new AssertionError(
            "Health check did not become healthy within 10 seconds"));
    }
    return vertx.timer(200).compose(t -> pollHealth(vertx, hcm, deadline));
}
```

If `getOverallHealth()` itself returns a `Future`, chain it inside the helper rather
than calling it synchronously — the recursive shape is the same.

**Rule:** In `@ExtendWith(VertxExtension.class)` tests, blocking waits, polling,
`Thread.sleep`, `LockSupport.park`, and `CountDownLatch.await` on the JUnit thread
are not allowed. Express the wait as a composed Future continuation driven by
`VertxTestContext`. Time-based delays use `vertx.timer(ms)` chained off the
relevant async op, never `Thread.sleep`.

---

## LOW: Copy-Paste Contamination of Test Logger Messages

Logger calls inside test methods that identify the wrong test produced by copying
a test's structure and updating the body without updating the log strings. Found in
`OutboxMetricsTest`:

```java
// inside testMetricsIntegration copied from circuit-breaker test, label not updated
logger.info("Test: circuit breaker integration");

// at start of testMultipleMessageMetrics same stale string
logger.info("Test: circuit breaker integration");
```

During a failure investigation, these messages direct a developer to the wrong test and
the wrong subsystem. The longer the search takes, the more expensive the contamination.

**Rule:** Logger calls inside tests must identify the test they are in. If per-test trace
logging is kept at all, the message must match the actual test name. The safest approach
is to omit per-test trace logs entirely and rely on the test runner's own reporting.

---

## MEDIUM: `withConnection()` for Write Operations Missing Transaction

Using `pool.withConnection()` for DML operations (`INSERT`, `UPDATE`, `DELETE`). The
reactive PostgreSQL client's `withConnection()` borrows a connection from the pool but
does **not** begin a transaction. Each statement executes in auto-commit mode only if
the underlying driver defaults to it otherwise the writes may not be committed at all,
and any failure leaves partial state with no rollback.

```java
// WRONG no transaction, write may not commit
return pool.withConnection(conn ->
    conn.preparedQuery("DELETE FROM dead_letter_queue WHERE id = $1")
        .execute(Tuple.of(messageId)));

// CORRECT automatic commit on success, rollback on failure
return pool.withTransaction(conn ->
    conn.preparedQuery("DELETE FROM dead_letter_queue WHERE id = $1")
        .execute(Tuple.of(messageId)));
```

This was the root cause of silent data loss in `DeadLetterQueueManager`: delete and
re-process operations appeared to succeed (the Future completed) but the rows were
never removed from the table.

**Rule:** `withTransaction()` for all writes. `withConnection()` only for reads.

---

## CRITICAL: "Strategic Delay" and Threshold-Masking Anti-Fixes

A category of changes made specifically to achieve a passing build rather than to fix
the underlying problem. These are the test equivalent of `.recover(→ succeededFuture())`:
they make failures invisible without removing their cause.

### Pattern 1: `Thread.sleep()` labelled as "strategic delay"

```java
// WRONG labelled "strategic" but is still the forbidden blocking pattern
Thread.sleep(2000);   // "after concurrent operations that need time to complete"
Thread.sleep(3000);   // "after manager close operations"
Thread.sleep(500);    // "between retry attempts"
```

This is identical to `LockSupport.parkNanos()` a fixed-duration block inserted to
paper over a race condition instead of fixing the race condition. The word "strategic"
does not change what the code does. If there is a race condition between two operations,
the fix is to sequence them correctly using futures, not to hope the delay is long enough.

These are present in `peegeeq-db` test classes and must be replaced with future chains
that wait for the actual operation to complete.

### Pattern 2: Threshold-based thread leak detection

```java
// WRONG treats up to 5 real leaks as acceptable
int maxAllowedVertxThreads = 5;
if (allVertxThreads.size() > maxAllowedVertxThreads) {
    fail("Excessive Vert.x event loop threads detected");
}
```

This is a tautological assertion (the CRITICAL pattern) applied to leak detection. A
threshold of 5 does not mean "no leak" it means "I allow 5 leaks before I notice."
The correct fix for threads appearing from other parallel tests is proper lifecycle
management, not a tolerance budget.

The outbox consumer lifecycle fix (see case study) eliminated the actual thread leaks.
With proper lifecycle management, `ResourceLeakDetectionTest` should find zero leaked
threads and the threshold should be zero.

### Pattern 3: Timestamp extraction to filter "old" threads

```java
// WRONG complex workaround for a lifecycle bug
private long extractTimestampFromThreadName(String threadName) {
    Pattern pattern = Pattern.compile("PeeGeeQ-Migration-(\\d+)");
    Matcher matcher = pattern.matcher(threadName);
    if (matcher.find()) return Long.parseLong(matcher.group(1));
    return 0;
}
if (threadTimestamp < testStartTime) continue; // filter out "old" threads
```

If a thread survives past the end of its owning test, that is a leak regardless of
when it was created. Filtering by creation timestamp hides the leak rather than fixing it.
The correct fix is to ensure the thread is stopped during shutdown (e.g., via a
properly wired close hook or Vert.x lifecycle integration).

### Why these patterns are dangerous

All three share the same failure mode as the error-swallowing patterns documented
throughout this file: a real problem is detected, and instead of being fixed, the
detection is adjusted until the problem is no longer visible. The build goes green.
The problem remains in production.

The "100% test success rate" claim in the document that introduced these patterns is
not evidence of correctness it is evidence that the failure detectors were tuned to
stop detecting failures.

---

## SERIOUS: Property-Key Mismatch Between Test Setup and Config Loader (Silent Misconfiguration)

`BaseIntegrationTest.setupTestConfiguration()` set conservative pool tuning via
`System.setProperty`, but the keys it used did not match the keys the configuration
loader reads. The loader silently fell through to its hardcoded production defaults.

```java
// WRONG keys the loader does NOT read
System.setProperty("peegeeq.database.pool.connection-timeout", "PT10S");  // Duration string form
System.setProperty("peegeeq.database.pool.idle-timeout",       "PT10S");  // Duration string form
System.setProperty("peegeeq.database.pool.max-lifetime",       "PT2M");   // key not in loader at all

// CORRECT keys the loader actually reads (millisecond long form)
System.setProperty("peegeeq.database.pool.connection-timeout-ms", "5000");
System.setProperty("peegeeq.database.pool.idle-timeout-ms",       "2000");
// max-lifetime-ms in peegeeq-default.properties but also not consumed; remove the dead property
```

**Consequence in the full test suite:** Idle timeout was effectively 600 seconds (10
minutes) rather than 10 seconds as intended. At ~8.8 pool creations/second across forks,
the 200-connection ceiling was consumed in seconds. The full suite produced 710 `FATAL:
sorry, too many clients already` errors across 277 failing tests all traceable to this
single key-name typo in test infrastructure.

**Why it survived:** No test asserted that `setupTestConfiguration()` produces the pool
config it claims. The misconfiguration was invisible until the full multi-module suite
was run on a single PostgreSQL container.

**Rule:** Test-infrastructure property writes must be covered by a `@Tag(CORE)` contract
test that verifies the loader reads what the test sets. A misconfigured baseline silently
degrades every downstream integration test in the project.

---

## SERIOUS: `shared=true` in Test Pools Non-Deterministic Connection Cleanup

`BaseIntegrationTest.setupTestConfiguration()` never set `peegeeq.database.pool.shared`,
so tests inherited the production default (`true`). With `shared=true`, Vert.x pools use
reference counting to govern connection lifetime: `Pool.close()` does **not**
deterministically release the underlying TCP sockets. Connections linger until the
reference count reaches zero and the idle-eviction timer fires.

```java
// WRONG shared=true, close() defers to reference-count + idle-eviction
// (never set → inherits peegeeq-default.properties default of true)

// CORRECT add to setupTestConfiguration()
System.setProperty("peegeeq.database.pool.shared", "false");
```

**Consequence:** Even if the idle-timeout were corrected to 2 seconds, `shared=true`
still defers socket release past `Pool.close()` completion. Both the key-name fix and
the `shared=false` fix are required together fixing only one leaves the other root
cause active.

**Rule:** Integration tests must use `shared=false` pools. With `shared=false`, `pool.close()`
is deterministic the future resolves only after the underlying connections are released.
`shared=true` is a production optimization for long-lived shared infrastructure, not for
test isolation.

---

## SERIOUS: Secondary `PgConnectionManager` Instances Never Closed in `@AfterEach`

28 integration test classes created a secondary `PgConnectionManager` field in
`@BeforeEach` to supply component-level tests with a standalone pool. None of them
closed it. `BaseIntegrationTest.tearDownBaseIntegration()` only closes the primary
`manager` it has no knowledge of secondary fields declared in subclasses.

```java
// WRONG pool created, never closed
@BeforeEach
void setUp() throws Exception {
    super.setUpBaseIntegration();

    connectionManager = new PgConnectionManager(manager.getVertx(), null);
    PgPoolConfig poolConfig = new PgPoolConfig.Builder()
        .maxSize(10)          // hardcoded to 10 no idle-timeout, shared=true by default
        .build();
    connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);
    // nothing calls connectionManager.close() ever
}

// CORRECT add to every subclass that creates a secondary PgConnectionManager
@AfterEach
void tearDown() throws Exception {
    if (connectionManager != null) {
        connectionManager.close();
    }
}
```

**Consequence:** 28 test classes × up to 10/20/50 maxSize = up to 4,730 connections
competing for 200 slots, all shared=true, none ever closed. After Tier-1 fixes corrected
the primary manager's config, a second run revealed 473 secondary pools (38% of total)
from this pattern still fully leaked.

**Rule:** Every field of type `PgConnectionManager` (or any resource-holding object)
declared in a test subclass must be closed in an `@AfterEach` in that subclass, even
when the base class already handles its own resources. The base class cannot close what
it does not know about.

---

## SERIOUS: `@AfterEach` Grace Timer + `awaitFuture` Bridge Skips `closeReactive()` When Vertx Event Loop Is Dead

`BaseIntegrationTest.tearDownBaseIntegration()` called `manager.getVertx().timer(100)`
as a grace delay *before* calling `closeReactive()`. When the Vertx event loop is dead
(which happens whenever `manager.start()` fails due to pool exhaustion or any other
startup error), `timer()` throws `RejectedExecutionException`. The `catch` block logged
the error and continued to `finally`, which nulled `manager` but `closeReactive()` was
never called.

```java
// WRONG: grace timer + awaitFuture bridge helper. Two violations:
//   1. vertx.timer(100) as a "grace delay" is a setTimer-as-readiness-guard antipattern
//      (see "HIGH: setTimer as a Readiness Guard"). If the event loop is dead, this
//      throws RejectedExecutionException, and closeReactive() is never reached.
//   2. awaitFuture(...) is a CountDownLatch-based bridge helper around a Future
//      (see "CRITICAL: Future.await() in Test Code") \u2014 banned in test code.
try {
    awaitFuture(manager.getVertx().timer(100).mapEmpty());   // \u2190 grace timer; throws if loop is dead
    awaitFuture(manager.closeReactive());                    // \u2190 banned bridge helper
} catch (Exception e) {
    logger.error("...", e);
} finally {
    manager = null;   // pool connections never released
}

// CORRECT: remove the grace timer entirely; drive teardown reactively via VertxTestContext.
//   - No timer \u2014 closeReactive() is the only thing that should run, and it handles
//     a dead Vertx internally.
//   - No awaitFuture / CountDownLatch / .await() bridge \u2014 the injected VertxTestContext
//     is the only permitted completion mechanism (see ANTIPATTERN doc rules above).
//   - Close failures are propagated via failNow(err), NOT swallowed to completeNow().
//     A failed closeReactive() leaks pool connections; that is the exact failure mode
//     this section exists to surface, and 232 silent skips proved the cost of hiding it.
@AfterEach
void tearDownBaseIntegration(VertxTestContext testContext) {
    if (manager == null) {
        testContext.completeNow();
        return;
    }
    manager.closeReactive()
        .onSuccess(v -> {
            manager = null;
            testContext.completeNow();
        })
        .onFailure(err -> {
            logger.error("Error closing PeeGeeQ Manager for profile: {}",
                testProfile, err);
            manager = null;
            testContext.failNow(err);   // do NOT swallow \u2014 leaked pool is the real failure
        });
}
```

**Evidence:** The full-suite log contained 232 occurrences of
`RejectedExecutionException: event executor terminated` at the `BaseIntegrationTest`
teardown line 33% of all 696 `@BeforeEach` invocations. Each of those 232 teardowns
left its pool connections open, compounding the connection exhaustion.

**Rule:** Teardown logic must never place pool cleanup behind a conditional gate that
can be skipped. Every code path through `@AfterEach` must reach `closeReactive()`.
The grace timer served no documented purpose and must be removed. The `awaitFuture`
bridge helper (or any equivalent `CountDownLatch.await`/`Future.await()`-based wrapper)
must be removed at the same time: drive teardown completion via the injected
`VertxTestContext`, not via a blocking bridge.

---

## LOW: `System.gc()` in `@AfterEach` as Resource Cleanup

`BaseIntegrationTest.tearDownBaseIntegration()` called `System.gc()` after teardown.
GC does not deterministically release database connections. Connections are held by
`PgConnectionManager`, which holds `Pool` references with explicit lifecycles. The GC
cannot release these only `close()` can.

**Rule:** Resource cleanup must be explicit and deterministic via `close()` / `closeReactive()`.
`System.gc()` is not a cleanup mechanism. Remove it.

---

## HIGH: No Contract Tests for Test Infrastructure (Zero Regression Coverage on Test Setup)

The property-key mismatch described above survived because there were no tests asserting
that `setupTestConfiguration()` produces the pool configuration it claims. A typo in
test infrastructure silently degraded every integration test in the project.

The fix is a `@Tag(CORE)` test class (no database dependency) that calls
`setupTestConfiguration()` + `new PeeGeeQConfiguration(...)` directly and asserts on
every pool tuning value:

```java
@Tag(TestCategories.CORE)
class BaseIntegrationTestPoolConfigContractCoreTest {

    @BeforeEach void setUp() { BaseIntegrationTest.setupTestConfiguration(); }

    @AfterEach
    void tearDown() {
        System.clearProperty("peegeeq.database.pool.max-size");
        System.clearProperty("peegeeq.database.pool.connection-timeout-ms");
        System.clearProperty("peegeeq.database.pool.idle-timeout-ms");
        System.clearProperty("peegeeq.database.pool.shared");
    }

    @Test void poolIdleTimeoutIsShortForFastTeardown() {
        assertThat(cfg().getPoolConfig().getIdleTimeout()).isLessThanOrEqualTo(Duration.ofSeconds(5));
    }

    @Test void poolConnectionTimeoutIsShortForFastFailure() {
        assertThat(cfg().getPoolConfig().getConnectionTimeout()).isLessThanOrEqualTo(Duration.ofSeconds(10));
    }

    @Test void poolIsNonSharedForDeterministicClose() {
        assertThat(cfg().getPoolConfig().isShared()).isFalse();
    }

    @Test void poolMaxSizeIsSmallForParallelHeadroom() {
        assertThat(cfg().getPoolConfig().getMaxSize()).isLessThanOrEqualTo(3);
    }

    private PeeGeeQConfiguration cfg() {
        return new PeeGeeQConfiguration("test-t2b-" + UUID.randomUUID());
    }
}
```

This test is `@Tag(CORE)` it runs on every `mvn test`, requires no database, and
cannot be blocked by connection exhaustion. It is the regression lock that would have
caught the property-key typo at PR review time.

**Rule:** Test infrastructure classes that set system properties or configure shared
resources must themselves have `@Tag(CORE)` contract tests verifying the configuration
survives through to the loader. "It compiles" is not evidence it works.

---

## Case Study: Outbox Consumer Lifecycle Bug (Resolved)

### The Symptom

A full outbox integration test run (410 tests) produced **3,975 `"Client not found: null"`
error log lines** spread across 36 test classes. The errors were silently absorbed no
test failed because of them, and no assertion checked for their presence. They were only
visible by reading the raw log output.

### The Root Cause

A shutdown ordering bug: consumers kept polling against DB connection pools that had
already been destroyed.

**`OutboxFactory`'s close hook was a no-op:**

```java
// WRONG registered but does nothing
registrar.registerCloseHook(new PeeGeeQCloseHook() {
    public io.vertx.core.Future<Void> closeReactive() {
        return io.vertx.core.Future.succeededFuture();  // no-op
    }
});
```

`PeeGeeQManager.closeReactive()` destroys DB pools in step 4 of its shutdown sequence,
but consumers were never told to stop. Their polling loop used a plain
`java.util.concurrent.ScheduledExecutorService` not a Vert.x timer so it ran
independently of Vert.x lifecycle and kept firing after both the pools and the Vert.x
instance were destroyed.

Each poll attempt generated `"Client not found: null"` from
`PgConnectionProvider.getReactivePool()`. The polling loop's `.onFailure()` handler
logged the error and returned the scheduler fired again next interval.

**Three layers of error erasure combined to make this invisible:**

1. **No-op close hook** manager shutdown never reached the consumers.
2. **`ScheduledExecutorService` outside Vert.x lifecycle** even if the hook had worked,
   `vertx.close()` could not cancel a plain executor.
3. **Log-and-continue `.onFailure()`** in the polling loop every error was absorbed
   without propagating, without failing a test, and without stopping the scheduler.

Additionally, `isShutdownRelatedError()` contained string-matching hacks that classified
`"Client not found"` and `"closed or shutting down"` messages as expected and silenced
them at DEBUG level treating the symptom rather than the cause, and introducing
fragile coupling to error message text from a different module.

### The Fix (all tasks completed)

| Task | Change | File |
|---|---|---|
| 1 | Wire `OutboxFactory` close hook → `closeTrackedResourcesAsync()` | `OutboxFactory.java` |
| 1b | `OutboxConsumer.closeAsync()` waits for in-flight processing | `OutboxConsumer.java` |
| 2 | Remove string-matching from `isShutdownRelatedError()`, remove its side-effects | `OutboxConsumer.java` |
| 3 | Remove `getConnectionProvider() == null` null-check hack from `processAvailableMessages` | `OutboxConsumer.java` |
| 4 | Replace `ScheduledExecutorService` with `Vertx.setPeriodic()` | `OutboxConsumer.java`, `PgConnectionManager.java` |
| 5a–5d | Migrate `FilterRetryManager`, `AsyncFilterRetryManager`, `OutboxConsumerGroupMember`, `OutboxConsumerGroup` to Vert.x timers | 4 files |

The close hook fix (task 1) is the primary fix it stops consumers in the correct
shutdown step, before pools are destroyed. The Vert.x timer migration (tasks 4–5d)
provides a safety net: even if a close hook fails, `vertx.close()` automatically cancels
all registered timers.

### Validation Result

| Metric | Before | After |
|--------|-------:|------:|
| `"Client not found"` log lines | **3,975** | **0** |
| Tests run | 410 | 372 |
| Failures | 1 | 3 |

The 372 vs 410 difference and the 3 failures are pre-existing issues unrelated to the
lifecycle fix: missing `logger` field declarations in 11 test classes (compilation errors
preventing those tests from loading), and schema-initialisation order errors in 3 example
test classes (`initializeSchema()` called after `manager.start().await()`).

### Connection to Antipatterns in This Document

This case study is a concrete example of how the patterns documented above compound:

- **CRITICAL placeholder tests** (section 1) tests that always passed regardless of
  outcome masked the 3,975 errors for the entire lifetime of the bug.
- **SERIOUS `.onComplete` swallowing** the polling loop used `.onFailure()` correctly
  for logging but returned without propagating; the fire-and-forget scheduler ensured
  failures never reached any test assertion.
- **SERIOUS production `.recover()` patterns** the no-op close hook itself returns
  `Future.succeededFuture()`, the canonical ERASURE pattern, at the exact integration
  point that should have stopped the consumers.

The bug was not introduced in a single commit. It accumulated incrementally: the no-op
hook was left as a stub, the executor was never converted to a Vert.x timer, and each
new error was handled with a log-and-continue block. At no point did a test fail. The
only way to see the problem was to read thousands of log lines.

---

## ANTIPATTERN: Health Checks That Count the Wrong Thing

### Problem

A health check that counts all rows matching a state across an entire shared table is
meaningless. In any multi-producer system in production or in a parallel test suite —
other producers are legitimately writing messages to the same table at the same time.
Counting their rows as evidence of a problem in *your* queue is wrong.

The canonical mistake:

```sql
-- WRONG: counts all PENDING rows created in the last hour across all topics
SELECT COUNT(*) FROM outbox
WHERE status = 'PENDING'
AND created_at > NOW() - INTERVAL '1 hour'
```

This triggers false-positive "too many pending messages" failures whenever any concurrent
workload a performance test, another service instance, a backfill job inserts messages
into the same table. The health check is not measuring queue health; it is measuring
total write throughput across all producers.

### Rule

**A health check must be scoped to what it owns.** Count only messages that indicate
a real problem in the specific queue instance being checked:

- Messages that have been **stuck** pending for longer than an expected processing
  window (e.g. `created_at < NOW() - INTERVAL '5 minutes'`), not freshly-created ones.
- Optionally further scoped to topics owned by this instance.

```sql
-- CORRECT: counts messages stuck pending beyond the expected processing window
SELECT COUNT(*) FROM outbox
WHERE status = 'PENDING'
AND created_at < NOW() - INTERVAL '5 minutes'
```

### Why This Matters in Tests

Tests that insert large volumes of data for performance validation will make health
checks of co-running tests fail if the health check counts indiscriminately. The fix
is never to serialize tests with `@ResourceLock` that treats the symptom by removing
concurrency. The fix is to make the health check correct so it is immune to concurrent
producers by design, exactly as it must be in production.

---

## SERIOUS: Manual `Vertx.vertx()` Creation in Tests That Already Have `@ExtendWith(VertxExtension.class)`

Added: 2026-05-09

### What Happens

`VertxExtension` creates and manages one `Vertx` instance per test. It injects it into
method parameters (`Vertx vertx`, `VertxTestContext testContext`) and closes it after
each test in `@AfterEach` during extension cleanup. When a test class has
`@ExtendWith(VertxExtension.class)` but also calls `vertx = Vertx.vertx()` in its own
`@BeforeEach`, the result is **two separate Vertx instances**:

1. The one managed by `VertxExtension` — injected into method parameters, closed by the extension.
2. The one created manually in `@BeforeEach` — stored in a field, only closed if the test's own `@AfterEach` runs to completion.

If `@AfterEach` throws or if the extension's close fires before the test's own teardown,
the manual instance leaks: its event-loop thread survives past the test, its `Pool`
connections are not released, and timers it owns continue firing into the next test.

### Real-World Instance

`ServiceDiscoveryExampleTest` (`peegeeq-examples`):

```java
@ExtendWith(VertxExtension.class)   // VertxExtension creates + manages a Vertx instance
@Testcontainers
public class ServiceDiscoveryExampleTest {

    private Vertx vertx;            // WRONG: field shadows VertxExtension's managed instance

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();      // WRONG: creates a second, unmanaged Vertx instance
        client = WebClient.create(vertx);
        ...
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        ...
        // WRONG: .await() in a JUnit thread deadlocks (see Future.await() antipattern).
        // Also closes the manual instance, but VertxExtension also closes its own
        // instance (different object), so this whole pattern is double-wrong.
        vertx.close().await();
    }
}
```

`RestApiExampleTest` has the same pattern without `@ExtendWith(VertxExtension.class)`,
so the manual creation is correct there — but for any class that already has the extension,
every `Vertx.vertx()` in `@BeforeEach` is a duplicate instance.

### Correct Patterns

**Option A — Use VertxExtension fully (preferred for integration tests using PeeGeeQ APIs)**

Let `VertxExtension` manage the instance. Declare it as a method parameter in `@BeforeEach`:

```java
@ExtendWith(VertxExtension.class)
public class MyTest {

    private WebClient client;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) throws Exception {
        // vertx is injected and managed by VertxExtension — do NOT create a second one
        client = WebClient.create(vertx);
        // setup complete; call testContext.completeNow() if setUp is async via Future
        testContext.completeNow();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) client.close();
        // Do NOT call vertx.close() — VertxExtension owns this lifecycle
    }
}
```

**Option B — Self-managed Vertx (for tests without VertxExtension)**

Remove `@ExtendWith(VertxExtension.class)` when you need full control over the Vertx
lifecycle (e.g., tests that deploy verticles, need custom Vertx options, or manage
their own teardown order):

```java
// No @ExtendWith(VertxExtension.class)
public class MyTest {

    private Vertx vertx;
    private WebClient client;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        client = WebClient.create(vertx);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (client != null) client.close();
        if (vertx == null) { testContext.completeNow(); return; }
        vertx.close()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(err -> { logger.error("vertx close failed", err); testContext.failNow(err); });
    }
}
```

Do NOT use `vertx.close().await()` — see the CRITICAL `Future.await()` antipattern.

**Rule:** Never mix `@ExtendWith(VertxExtension.class)` with a manually-created `Vertx`
instance stored in a field. Pick one owner. `VertxExtension` is the correct choice when
tests receive `VertxTestContext` as a method parameter; self-managed is the correct
choice when the test controls its own `Vertx` options and teardown sequence.

---

## SERIOUS: Wrong Resource Close Order in `@AfterEach`

Added: 2026-05-09

### What Happens

Resources in PeeGeeQ form a dependency chain: consumers use producers use the pool use
the connection manager use the Vertx event loop. Closing an outer resource before an
inner one causes the inner resource to fail mid-operation — pooled connections are torn
out from under active consumers, producing `"Client not found"` errors, `NullPointerException`
on in-flight futures, or leaked event-loop threads.

### Correct Teardown Order

Close in strict reverse-construction order, innermost consumers first, outermost
infrastructure last:

```
1. Message consumers      (stop receiving; let in-flight messages drain)
2. Message producers      (stop sending)
3. OutboxFactory / consumer group   (close managed lifecycle objects)
4. PgConnectionManager / secondary pools  (release pool connections)
5. PeeGeeQManager / primary pool    (closeReactive())
6. Vertx                  (vertx.close() — only if self-managed, not VertxExtension)
```

Each step must complete before the next starts. Chain with `.compose()` and drive the
`VertxTestContext` from terminal handlers. **Do not use `Future.await()`** — see the
CRITICAL `Future.await()` antipattern further below.

### Anti-Pattern: Independent `try/catch` Blocks With No Guaranteed Ordering

```java
// WRONG on TWO counts:
//   1. independent try/catch blocks don't guarantee ordering
//   2. .await() in a JUnit thread deadlocks the test (see Future.await() antipattern)
@AfterEach
void tearDown() throws Exception {
    try {
        consumer.close();           // step 1
    } catch (Exception e) { ... }

    try {
        outboxFactory.close();      // step 3 before pool — WRONG ORDER if pool closes first
    } catch (Exception e) { ... }

    try {
        manager.closeReactive().await();    // step 5 — BANNED .await()
    } catch (Exception e) { ... }
}
```

### Correct Pattern: Reactive Teardown Chain Driven By `VertxTestContext`

```java
@AfterEach
void tearDown(VertxTestContext testContext) {
    // sync close() calls (those that don't return a Future) inside try/catch + WARN log
    if (consumer != null) {
        try { consumer.close(); } catch (Exception e) { logger.warn("Error closing consumer", e); }
    }
    if (producer != null) {
        try { producer.close(); } catch (Exception e) { logger.warn("Error closing producer", e); }
    }
    if (outboxFactory != null) {
        try { outboxFactory.close(); } catch (Exception e) { logger.warn("Error closing factory", e); }
    }
    if (manager == null) { testContext.completeNow(); return; }
    manager.closeReactive()
        .onSuccess(v -> testContext.completeNow())
        .onFailure(err -> {
            logger.warn("Error during manager cleanup: {}", err.getMessage());
            testContext.completeNow();
        });
    // Do NOT close vertx here if @ExtendWith(VertxExtension.class) is present
}
```

If multiple resources expose `Future`-returning close methods, compose them:

```java
@AfterEach
void tearDown(VertxTestContext testContext) {
    Future.<Void>succeededFuture()
        .compose(v -> consumer != null ? consumer.closeAsync() : Future.succeededFuture())
        .compose(v -> producer != null ? producer.closeAsync() : Future.succeededFuture())
        .compose(v -> outboxFactory != null ? outboxFactory.closeAsync() : Future.succeededFuture())
        .compose(v -> manager != null ? manager.closeReactive() : Future.succeededFuture())
        .onSuccess(v -> testContext.completeNow())
        .onFailure(err -> {
            logger.warn("Error during reactive teardown: {}", err.getMessage());
            testContext.completeNow();
        });
}
```

**Rule:** Every resource that depends on another must be closed before it. In `@AfterEach`,
an exception closing a resource at level N must not prevent the close of the resource at
level N+1. Use nested `finally` blocks or a reactive chain to guarantee this.

---

## MEDIUM: `VertxTestContext` Orphan — Context Created But `awaitCompletion` Not Reachable

Added: 2026-05-09

### What Happens

`VertxTestContext` is designed to be created, used to register async checkpoints, and
then drained by `awaitCompletion(timeout, unit)`. If an exception is thrown, or a
synchronous assertion fails, between context creation and the `awaitCompletion` call,
the context is abandoned:

- `completeNow()` or `failNow()` called from background callbacks after the method
  returns have no effect on the test — the result has already been determined.
- Any checkpoints that were never flagged silently expire without surfacing in the test
  report.
- If the same background future later attempts a second callback on the abandoned
  context, it throws `IllegalStateException: Test context already completed`.

### Real-World Instance

`TransactionalOutboxAnalysisTest.testOutboxTransactionParticipation()` (simplified):

```java
VertxTestContext msgContext = new VertxTestContext();    // created
var msgCheckpoint = msgContext.checkpoint();

consumer.subscribe(message -> {
    msgCheckpoint.flag();                                // background: may fire after throw below
    return Future.succeededFuture();
});

someAssertion();    // WRONG: if this throws, the line below is never reached
                    // msgContext is now orphaned

assertTrue(msgContext.awaitCompletion(10, TimeUnit.SECONDS), "...");   // unreachable
```

If `someAssertion()` throws `AssertionError`, JUnit marks the test failed at that line,
but `msgCheckpoint.flag()` may still fire from the subscribe callback after the test
exits. This causes a secondary `IllegalStateException` in the log with no clear
attribution.

### Correct Pattern: Guard the Context Lifetime With `try/finally`

```java
VertxTestContext msgContext = new VertxTestContext();
var msgCheckpoint = msgContext.checkpoint();

consumer.subscribe(message -> {
    msgCheckpoint.flag();
    return Future.succeededFuture();
});

try {
    someAssertion();    // if this throws, awaitCompletion still runs in finally
} finally {
    assertTrue(msgContext.awaitCompletion(10, TimeUnit.SECONDS),
        "Subscriber should have received the message");
    if (msgContext.failed()) throw new AssertionError(msgContext.causeOfFailure());
}
```

Or, avoid inline `VertxTestContext` creation entirely: pass the enclosing test's
`VertxTestContext` (the one injected by `VertxExtension`) down to the subscribe handler
and use its checkpoints:

```java
// Prefer: use the injected testContext directly
void testOutboxTransactionParticipation(VertxTestContext testContext) throws Exception {
    var msgCheckpoint = testContext.checkpoint();   // flagged in subscribe → test completes
    consumer.subscribe(message -> {
        msgCheckpoint.flag();
        return Future.succeededFuture();
    });
    ...
}
```

**Rule:** Never create a `new VertxTestContext()` unless the injected `VertxTestContext`
cannot be used (e.g., a second round of async work mid-test after the first has already
been drained). When a standalone context is unavoidable, always wrap the code between
creation and `awaitCompletion` in `try/finally` so the drain always runs.

---

## CRITICAL: `Future.await()` in Test Code Causes Indefinite Teardown Hangs

Added: 2026-05-16

### What Happens

`io.vertx.core.Future#await()` is a Vert.x 5 helper designed for **virtual threads only**.
Calling it from a JUnit/main platform thread blocks that thread until the future settles.
If settling the future requires the Vert.x event loop to dispatch work, and the event
loop is already busy (or the close operation itself routes through the same thread the
test is sitting on), the future never completes and the test hangs **indefinitely** —
no timeout, no error, no thread dump unless the user kills the JVM.

This is especially common in `@AfterEach` teardown:

```java
// WRONG: blocks platform test thread until closeReactive() future completes.
// If the close path needs event-loop dispatch, deadlocks forever.
@AfterEach
void tearDown() throws Exception {
    if (producer != null) producer.close();
    if (factory != null) factory.close();
    if (manager != null) {
        manager.closeReactive().await();   // ← hang point
    }
}
```

The failure mode is observable in logs as `WARN ... Cannot be called on a Vert.x
event-loop thread` immediately before the hang — the close handler detected wrong-thread
dispatch but the test thread is already pinned waiting on a future that will never
resolve.

A bounded earlier version of the same pattern (`closeLatch.await(10, TimeUnit.SECONDS)`)
would have timed out and surfaced the bug. Removing the timeout to "clean up" the code
is what turns a slow test into a hung CI job.

### Real-World Instances

- `peegeeq-native/.../ConsumerGroupSubscriptionTest` — `manager.closeReactive().await()`
  in `tearDown()` introduced in commit `a6b9cc8d` (replaced a bounded `CountDownLatch.await(10s)`).
  Hung the entire `peegeeq-native` test run on a build server.
- `peegeeq-outbox/.../OutboxConsumerErrorHandlingTest` — same pattern in `setUp()` and
  `tearDown()`, plus `producer.send(...).await()` inside every `@Test` body.
  Hung when triggered transitively via `mvn -am`.

### Banned Forms

All of these are banned in **test code** (the same patterns are also banned in
production code per the project coding principles):

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

The **only** `await*` calls allowed in test code are JUnit / VertxTestContext idioms:

```java
testContext.awaitCompletion(10, TimeUnit.SECONDS);   // OK — drains VertxTestContext
startBarrier.await();                                 // OK — CyclicBarrier coordinates worker threads, not Futures
```

**`CountDownLatch.await(...)` is not on this list.** In `@ExtendWith(VertxExtension.class)`
tests, Future completion is driven by `VertxTestContext` exclusively. There is no
"temporary bridge" allowance — a permanent helper that wraps `CountDownLatch.await`
around a Future (often named `awaitFuture`, `blockOnFuture`, `syncFuture`) is itself an
antipattern: it preserves the structurally-synchronous test shape that
`VertxTestContext` was designed to replace, and it makes every test method look like
it cannot fail by timeout when it can. If you find yourself reaching for such a
helper, the test method's signature needs to take `VertxTestContext` and the body
needs to be a composed Future chain. Sleeps and delays inside that chain are
`vertx.timer(ms)`, not `Thread.sleep`.

### Correct Pattern: `VertxTestContext` Lifecycle Hooks

Use the Vert.x JUnit5 extension to inject a `VertxTestContext` into `setUp` and
`tearDown` and drive completion via terminal handlers:

```java
@BeforeEach
void setUp(VertxTestContext testContext) {
    PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);
    manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
    manager.start()
        .onSuccess(v -> {
            // wire factory/producer/consumer *inside* onSuccess so they're ready
            // before any @Test runs
            factory = new OutboxFactory(new PgDatabaseService(manager), config);
            producer = factory.createProducer(topic, String.class);
            testContext.completeNow();
        })
        .onFailure(testContext::failNow);
}

@AfterEach
void tearDown(VertxTestContext testContext) {
    if (producer != null) {
        try { producer.close(); } catch (Exception e) { logger.warn("Error closing producer", e); }
    }
    if (factory != null) {
        try { factory.close(); } catch (Exception e) { logger.warn("Error closing factory", e); }
    }
    if (manager == null) { testContext.completeNow(); return; }
    manager.closeReactive()
        .onSuccess(v -> testContext.completeNow())
        .onFailure(err -> {
            logger.warn("Error during manager cleanup: {}", err.getMessage());
            testContext.completeNow();
        });
}
```

For in-test sequencing, replace `.await()` chains with composed futures:

```java
// WRONG
group1.start(opts1).await();
assertTrue(group1.isActive());
group1.close();
group2.start(opts2).await();
assertTrue(group2.isActive());
group2.close();

// CORRECT
group1.start(opts1)
    .compose(v -> {
        assertTrue(group1.isActive());
        group1.close();
        return group2.start(opts2);
    })
    .onSuccess(v -> testContext.verify(() -> {
        assertTrue(group2.isActive());
        group2.close();
        testContext.completeNow();
    }))
    .onFailure(testContext::failNow);
```

For fire-and-forget sends where the test waits via consumer checkpoints, attach a
failure handler instead of awaiting:

```java
// WRONG
producer.send("msg").await();

// CORRECT — consumer-side checkpoint will drive testContext completion
producer.send("msg").onFailure(testContext::failNow);
```

### Why This Antipattern Slipped Through

Earlier revisions of this very document recommended `.await()` as a "preferred" fix for
fire-and-forget `producer.send()` calls and as the canonical teardown pattern. Those
recommendations were wrong and have been corrected. If you find any code that still
follows them, fix the code — do not propagate the pattern by appeal to existing usage.

### Verification

Audit every file you touch with:

```powershell
grep -nE '\.await\(\)' path/to/Test.java
```

Matches that are NOT `testContext.awaitCompletion(...)` or `CyclicBarrier.await()` are
violations. Fix them before claiming the file is done — "compilation passes" is not
evidence of a hang-free test. `CountDownLatch.await(timeout, unit)` is **not** on the
allow-list: in `@ExtendWith(VertxExtension.class)` tests, Future completion must be
driven by `VertxTestContext` and time delays by `vertx.timer(ms)`.

---

## CRITICAL: `.eventually(factory::close)` for `QueueFactory` Logs Cleanup Errors and Skips Cleanup

Added: 2026-05-17

### What Happens

The general advice elsewhere in these docs — "use `.eventually(() -> resource.close())`
as the reactive equivalent of `finally`" — is **not** safe for every resource. It is
safe only when the resource's `close()` is event-loop-safe.

`QueueFactory.close()` (and its `OutboxFactory` / `PgNativeQueueFactory` implementations)
is **blocking** and **thread-affinity guarded**: it refuses to run on a Vert.x event-loop
thread, throwing/logging:

```
WARN  Error closing queue factory: Do not call blocking close() on event-loop thread
      - close this factory from a worker thread
```

When the close is chained off the test body via `.eventually(...)`, the callback runs on
the event-loop that just executed the test's reactive chain. The guard trips, the factory
is **not** closed, and the WARN is the only signal — the test still passes.

```java
// WRONG: runs factory.close() on the event-loop, hits the thread-affinity guard,
// emits a cleanup WARN, and leaves the factory open.
@Test
void testSomething(Vertx vertx, VertxTestContext testContext) {
    QueueFactory factory = configManager.createFactory("x", "outbox");
    doWork(factory)
        .eventually(() -> {
            factory.close();                      // ← WARN, factory NOT closed
            return Future.<Void>succeededFuture();
        })
        .onSuccess(v -> testContext.completeNow())
        .onFailure(testContext::failNow);
}
```

`vertx.executeBlocking(...)` is **not** an acceptable escape hatch for thread-affinity
guarded close methods: it smuggles a blocking primitive into a reactive system, and the
project has no other test that does it. The established pattern below — close the
factory in `@AfterEach` on the JUnit worker thread — is the only correct fix.

### Correct Pattern

Track factories as instance fields and close them in `@AfterEach`. JUnit invokes
`@AfterEach` on the platform test thread (a worker thread), so the thread-affinity
guard does not trip.

```java
private MultiConfigurationManager configManager;
private final List<QueueFactory> openFactories = new ArrayList<>();

private <F extends QueueFactory> F track(F factory) {
    openFactories.add(factory);
    return factory;
}

@Test
void testSomething(Vertx vertx, VertxTestContext testContext) {
    QueueFactory factory = track(configManager.createFactory("x", "outbox"));
    doWork(factory)
        .onSuccess(v -> testContext.completeNow())
        .onFailure(testContext::failNow);
}

@AfterEach
void tearDown(VertxTestContext testContext) {
    for (QueueFactory f : openFactories) {
        try { f.close(); }
        catch (Exception e) { logger.warn("Error closing queue factory: {}", e.getMessage()); }
    }
    openFactories.clear();

    if (configManager == null) { testContext.completeNow(); return; }
    configManager.close()
        .onSuccess(v -> testContext.completeNow())
        .onFailure(err -> {
            logger.warn("Error during configManager cleanup: {}", err.getMessage());
            testContext.completeNow();
        });
}
```

This is the established project pattern, used by
`NativeVsOutboxComparisonTest`, `TransactionalOutboxAnalysisTest`,
`OutboxServerSideFilteringTest`, `PeeGeeQBiTemporalIntegrationTest`,
`PeeGeeQBiTemporalWorkingIntegrationTest`, and others.

### Rule

`.eventually(() -> resource.close())` is correct only when `resource.close()` is
event-loop-safe. For any resource whose `close()` has thread-affinity requirements —
including every `QueueFactory` implementation — close it from `@AfterEach` on the
JUnit worker thread instead.

Resources known to require worker-thread `close()` in this project:

- `QueueFactory` (all implementations: `OutboxFactory`, `PgNativeQueueFactory`)

Resources known to be event-loop-safe and therefore valid inside `.eventually(...)`:

- `MessageProducer.close()`
- `MessageConsumer.close()`
- Any `Future`-returning close (`PeeGeeQManager.closeReactive()`,
  `MultiConfigurationManager.close()`, `Pool.close()`).

### Verification

After touching any test that constructs a `QueueFactory`, audit:

```powershell
grep -nE '\.eventually\(.*close|QueueFactory.*close' path\to\Test.java
```

A hit on `.eventually(... factory.close() ...)` is a violation. Run the test and grep
the log for `Error closing queue factory: Do not call blocking close()` — its presence
confirms the antipattern.

---
