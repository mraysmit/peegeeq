# PeeGeeQ Error Handling Antipatterns

Audit date: 2026-04-13  
Branch: `feature/fanout-trace-propagation`

This document catalogues error-swallowing patterns found across the PeeGeeQ codebase
that prevent runtime failures from surfacing in tests. Organised by severity.

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
producer.send(msg)
    .onSuccess(v -> sendLatch.countDown())
    .onFailure(testContext::failNow);
```

Or with Checkpoint:
```java
producer.send(msg)
    .onSuccess(v -> checkpoint.flag())
    .onFailure(testContext::failNow);
```

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
// WRONG: close failure is invisible
manager.closeReactive().onComplete(ar -> closeLatch.countDown());
closeLatch.await(10, TimeUnit.SECONDS);
```

Files: `OutboxConsumerEdgeCasesCoverageTest`, `OutboxConsumerCrashRecoveryTest`,
`OutboxConsumerCoreTest`, `OutboxBasicTest`, `FanOutTracePropagationTest`,
`OutboxConsumerSurgicalCoverageTest`, `OutboxBlockingSafetyTest`.

While teardown errors are less critical than test-body errors, a failed close can leak
database connections and cause subsequent tests to fail with misleading "too many clients"
errors.

---

## MODERATE: Empty Catch Blocks in Test Teardown

27 instances of `catch (Exception ignored) {}` most in `@AfterEach` cleanup:

```java
try { factory.close(); } catch (Exception ignore) {}
try { connectionManager.close(); } catch (Exception ignored) {}
try { vertx.close(); } catch (Exception ignored) {}
```

### Assessment

These are concentrated in test teardown (`@AfterEach`, `@AfterAll`). In teardown code,
swallowing close errors is generally acceptable best-effort cleanup. However, some
are in test bodies:

| File | Line | Context |
|---|---|---|
| `PartitionedNativeConsumerIntegrationTest.java` | 122 | Test body not teardown |
| `ResilienceSmokeTest.java` | 118, 200 | Test body not teardown |

These should log or fail the test instead of silently continuing.

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

## LOW: Production `.onFailure(log)` Terminal Handlers

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

### 6. Resource Allocation Outside try/finally (Low)

Producers and consumers created before the `try` block. If `awaitCompletion` or an
assertion fails, the `finally` block is never reached and resources leak.

Found in: `ConsumerModeResourceManagementTest`, `JsonbConversionValidationTest`.

**Fix:** Move resource creation inside `try`, or restructure so `finally` always runs:

```java
MessageConsumer<String> consumer = factory.createConsumer(...);
MessageProducer<String> producer = factory.createProducer(...);
try {
    // test body
} finally {
    consumer.close();
    producer.close();
}
```

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
// WRONG: asserts invocation but not the null-return consequence
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

---

## Summary of Required Actions

| Priority | Action | Scope |
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
| LOW | Remove dead code branches in shared test helpers | Helpers with never-exercised paths |
| HIGH | Replace hand-rolled schema DDL / raw JDBC with shared initializer + reactive pool | Tests with inline CREATE TABLE or JDBC verification |
| LOW | Remove unnecessary `@TestMethodOrder` / `@Order` on independent tests | Tests with ordering annotations but no shared state |
| LOW | Remove dead code after `testContext.failNow()` | Subscribe handlers with redundant `throw` after failNow |
| LOW | Replace `assertTrue(x instanceof Y)` with `assertInstanceOf` / `assertNull` | Tests using weak assertion idioms |
| MEDIUM | Replace commented-out `//@Test` with `@Disabled("reason")` or fix and re-enable | 1 test in OutboxConsumerFailureHandlingTest |
| HIGH | Replace `LockSupport.parkNanos()` / `Thread.sleep` with event-driven waits (future chains, checkpoints, DB state observation) | 18 occurrences across 10 files |
| CRITICAL | Replace `setTimer → completeNow()` timeout handlers with `failNow()` (P2 pattern) | 7 tests in `peegeeq-rest` |
| HIGH | Replace `setTimer` readiness guards with future chains off `subscribe()` / `deployVerticle()` | P3: 6 setUp methods in `peegeeq-rest`; P4: 9 data-wait timers |
| P1 | Replace 100 ms drain delay in `DatabaseTemplateManager` with `pg_stat_activity` poll loop | `peegeeq-db/.../DatabaseTemplateManager.java:140` |
| SERIOUS | Add `.await()` or `.onFailure(testContext::failNow)` to fire-and-forget `producer.send()` | 7 occurrences in OutboxConsumerErrorHandlingTest |
| MEDIUM | Replace near-tautological handler-invocation assertions with outcome verification | OutboxConsumerNullHandlerTest |
| LOW | Fix stale Javadoc referencing banned `CompletableFuture` type | OutboxConsumerNullHandlerTest |
| MEDIUM | Replace `withConnection()` with `withTransaction()` for all write operations | `DeadLetterQueueManager` and any other class using `withConnection` for DML |
| CRITICAL | Revert `Thread.sleep` "strategic delays" and threshold-based leak masking introduced as concurrency "fixes" | `peegeeq-db` test classes |
| SERIOUS | Delete blocking `executeSupplier`/`executeRunnable`/`executeDatabaseOperation`/`executeQueueOperation` methods from `CircuitBreakerManager` | `peegeeq-db/.../resilience/CircuitBreakerManager.java` |
| MEDIUM | Move misplaced tests to their correct subject class | `testCircuitBreakerIntegration` in `OutboxMetricsTest` |
| MEDIUM | Move blocking waits inside `vertx.executeBlocking()` in `@ExtendWith(VertxExtension.class)` tests | `testHealthCheckIntegration` spin-poll in `OutboxMetricsTest` |
| LOW | Fix logger messages that identify the wrong test (copy-paste contamination) | `OutboxMetricsTest` 3 mismatched log strings |
| SERIOUS | Fix property-key mismatch in `BaseIntegrationTest.setupTestConfiguration()` use `*-ms` suffixed keys the loader actually reads | `BaseIntegrationTest.java` lines 179-183 |
| SERIOUS | Add `peegeeq.database.pool.shared=false` in `BaseIntegrationTest.setupTestConfiguration()` | `BaseIntegrationTest.java` currently absent, tests inherit `shared=true` production default |
| SERIOUS | Add `@AfterEach` calling `connectionManager.close()` to 28 integration test classes that create secondary `PgConnectionManager` instances | All files listed in §3c of `test-suite-connection-exhaustion-investigation.md` |
| SERIOUS | Guard `@AfterEach` grace timer so `closeReactive()` is always called even when the Vertx event loop is dead | `BaseIntegrationTest.tearDownBaseIntegration()` 33% of teardowns silently skip `closeReactive()` |
| LOW | Remove `System.gc()` from `BaseIntegrationTest.@AfterEach` | `BaseIntegrationTest.tearDownBaseIntegration()` GC-based resource cleanup is not deterministic |
| HIGH | Add contract tests for `BaseIntegrationTest` pool configuration so property-key regressions are caught automatically | New test class `BaseIntegrationTestPoolConfigContractCoreTest` (`@Tag(CORE)`) |

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

### Variant: P2 Timeout handler calls `completeNow()` instead of `failNow()`

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

### Variant: P3 Post-`deployVerticle` readiness guard

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

### Variant: P4 Wall-clock wait for SSE/WebSocket buffer to accumulate

Timers that fire after connecting to an SSE or WebSocket stream, wait N seconds, then
inspect the accumulated receive buffer. They race against actual message delivery.

Affected: 9 locations in `peegeeq-rest`, including `ConsumerGroupSubscriptionIntegrationTest`
(lines 245, 354, 529, 785, 799, 1136), `CrossLayerPropagationIntegrationTest:262`,
`SystemMonitoringIntegrationTest:138`, `QueueManagementE2ETest:223`.

**Fix:** Use an event-driven handler that acts as soon as the expected event appears, then
call `testContext.completeNow()`.

### Variant: P1 Production code timing assumption (correctness risk)

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

**Fix:** Await the send or attach a failure handler:

```java
// Option 1: await (preferred when send must complete before assertions)
producer.send("test-message").await();

// Option 2: chain with failure handler
producer.send("test-message")
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

**Fix:** Move any blocking wait inside `vertx.executeBlocking()`, then chain the result
back into the `VertxTestContext`:

```java
// CORRECT
vertx.executeBlocking(() -> {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
        var status = healthCheckManager.getOverallHealth();
        if (status != null && status.isHealthy()) return status;
        LockSupport.parkNanos(200_000_000L);
    }
    throw new AssertionError("Health check did not become healthy within 10 seconds");
})
.onSuccess(status -> testContext.verify(() -> {
    assertTrue(status.isHealthy());
    testContext.completeNow();
}))
.onFailure(testContext::failNow);
```

**Rule:** In `@ExtendWith(VertxExtension.class)` tests, any blocking wait polling,
`Thread.sleep`, `LockSupport.park`, `CountDownLatch.await` belongs inside
`vertx.executeBlocking()`, not on the test thread directly.

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

## SERIOUS: `@AfterEach` Grace Timer Skips `closeReactive()` When Vertx Event Loop Is Dead

`BaseIntegrationTest.tearDownBaseIntegration()` called `manager.getVertx().timer(100)`
as a grace delay *before* calling `closeReactive()`. When the Vertx event loop is dead
(which happens whenever `manager.start()` fails due to pool exhaustion or any other
startup error), `timer()` throws `RejectedExecutionException`. The `catch` block logged
the error and continued to `finally`, which nulled `manager` but `closeReactive()` was
never called.

```java
// WRONG timer throws if event loop is dead; closeReactive() is then skipped
try {
    awaitFuture(manager.getVertx().timer(100).mapEmpty());   // ← throws RejectedExecutionException
    awaitFuture(manager.closeReactive());
} catch (Exception e) {
    logger.error("...", e);
} finally {
    manager = null;   // pool connections never released
}

// CORRECT remove the grace timer; closeReactive() handles dead Vertx internally
@AfterEach
void tearDownBaseIntegration() throws Exception {
    if (manager != null) {
        try {
            awaitFuture(manager.closeReactive());
        } catch (Exception e) {
            logger.error("Error closing PeeGeeQ Manager for profile: {}", testProfile, e);
        } finally {
            manager = null;
        }
    }
}
```

**Evidence:** The full-suite log contained 232 occurrences of
`RejectedExecutionException: event executor terminated` at the `BaseIntegrationTest`
teardown line 33% of all 696 `@BeforeEach` invocations. Each of those 232 teardowns
left its pool connections open, compounding the connection exhaustion.

**Rule:** Teardown logic must never place pool cleanup behind a conditional gate that
can be skipped. Every code path through `@AfterEach` must reach `closeReactive()`.
The grace timer served no documented purpose and must be removed.

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
