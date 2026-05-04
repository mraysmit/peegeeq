# Plan: Fix `CircuitBreakerManager` — Delete Wrong Code, Rewrite Test Honestly

## Background

### What `CircuitBreakerManager` actually is

A registry and factory for Resilience4j `CircuitBreaker` objects. Its job is to create, cache, configure, and expose `CircuitBreaker` instances by name. That is the entire contract. Callers retrieve a `CircuitBreaker` and drive it themselves.

The sole production caller is `HealthCheckManager.executeWithCircuitBreaker()`:

```java
CircuitBreaker cb = circuitBreakerManager.getCircuitBreaker(cbName);
if (!cb.tryAcquirePermission()) {
    return Future.succeededFuture(HealthStatus.unhealthy(...));
}
long start = System.nanoTime();
return operation.get()
    .onSuccess(status -> cb.onSuccess(duration, TimeUnit.NANOSECONDS))
    .onFailure(t   -> cb.onError(duration, TimeUnit.NANOSECONDS, t));
```

This is the correct and established pattern: the caller owns the permission check, the timing, and the result recording. `CircuitBreakerManager` stays as a pure factory.

---

### What is wrong and why it is a serious design deviation

`CircuitBreakerManager` contains four methods that contradict this design at every level:

```java
public <T> T executeSupplier(String name, Supplier<T> supplier)
public void    executeRunnable(String name, Runnable runnable)
public <T> T executeDatabaseOperation(String operation, Supplier<T> supplier)
public <T> T executeQueueOperation(String queueType, String operation, Supplier<T> supplier)
```

**1. They block the Vert.x event loop.**
PeeGeeQ is built entirely on Vert.x reactive futures. All I/O and queue operations return `Future<T>`. These four methods accept synchronous `Supplier<T>` and return raw `T` by executing the supplier inline on the calling thread. If any production code ever passed a database or queue operation through these methods, the event loop thread would block until the operation completed. This is one of the most fundamental prohibited patterns in the entire project. The copilot instructions list it as forbidden without exception.

**2. They duplicate and corrupt the `HealthCheckManager` pattern.**
`HealthCheckManager` already shows the correct way to use `CircuitBreakerManager`: get the `CircuitBreaker`, acquire permission, run the async operation, report the outcome. These four methods introduce a second, incompatible usage pattern that wraps synchronous work inside a circuit breaker. A future developer reading the class now sees two patterns and must guess which one to follow. The wrong one would be chosen for any I/O operation.

**3. They are dead code with no legitimate future use.**
Every real operation in PeeGeeQ is asynchronous. There is no class in the system that has a synchronous result to protect with a circuit breaker. The only path that would ever call these methods is test code. They were written to make the class testable in a synchronous test harness, not because any production caller needed them.

**4. They smuggle JDBC-era thinking into a reactive codebase.**
The pattern of `executeSupplier(name, () -> doSomethingBlocking())` is the Resilience4j idiom designed for synchronous, blocking applications — JDBC, RestTemplate, blocking HTTP clients. PeeGeeQ uses the reactive Vert.x PostgreSQL client throughout. Introducing these methods here is the same category of mistake as calling `DriverManager.getConnection()` in a test — it is the wrong abstraction for the wrong runtime model.

**5. The test class fabricates Vert.x infrastructure to hide the mismatch.**
`CircuitBreakerManagerTest` carries `@ExtendWith(VertxExtension.class)`, `VertxTestContext`, `CountDownLatch`, and `AtomicInteger` purely to make one test (`testConcurrentCircuitBreakerAccess`) look reactive. The rest of the tests make no use of Vert.x at all. The Vert.x machinery is present not because the class under test is reactive, but because a synchronous blocking operation was dressed up in a worker thread to avoid the appearance of event loop blocking. This is exactly the kind of camouflage that the project's coding principles prohibit.

**Resolution:** delete the four blocking methods and the import they require. Do not replace them with reactive equivalents — there is no caller that needs one. The `HealthCheckManager` pattern is the reference implementation for any future caller.

---

## Phase 1 — CircuitBreakerManager.java (production)

**Step 1.** Delete `executeSupplier(String, Supplier<T>)`.

**Step 2.** Delete `executeRunnable(String, Runnable)`.

**Step 3.** Delete `executeDatabaseOperation(String, Supplier<T>)`.

**Step 4.** Delete `executeQueueOperation(String, String, Supplier<T>)`.

**Step 5.** Delete `import java.util.function.Supplier` — no longer used after Step 1–4.

Everything else stays: constructor, `getCircuitBreaker()`, `getMetrics()`, `getCircuitBreakerNames()`, `reset()`, `forceOpen()`, `CircuitBreakerMetrics` inner class.

---

## Phase 2 — CircuitBreakerManagerTest.java

The tests must honestly reflect what `CircuitBreakerManager` actually does: create, configure, cache, inspect, and reset circuit breakers. State transitions are verified by calling `cb.onSuccess()` / `cb.onError()` / `cb.tryAcquirePermission()` directly on the `CircuitBreaker` returned by `getCircuitBreaker()` — exactly how production callers use it.

### Step 6 — Delete all tests that call removed methods

These tests are wrong, not broken. They tested an API that should never have existed:

| Test | Removed method called |
|---|---|
| `testDisabledCircuitBreaker` | `executeSupplier` |
| `testSuccessfulExecution` | `executeSupplier` |
| `testFailedExecution` | `executeSupplier` |
| `testCircuitBreakerOpening` | `executeSupplier` |
| `testCircuitBreakerCallNotPermitted` | `executeSupplier` |
| `testCircuitBreakerReset` | `executeSupplier` |
| `testDatabaseOperationCircuitBreaker` | `executeDatabaseOperation` |
| `testQueueOperationCircuitBreaker` | `executeQueueOperation` |
| `testRunnableExecution` | `executeRunnable` |
| `testConcurrentCircuitBreakerAccess` | `executeSupplier` |
| `testCircuitBreakerStateTransitions` | `executeSupplier` |
| `testCircuitBreakerWithNullMeterRegistry` | `executeSupplier` |
| `testCircuitBreakerConfiguration` | `executeSupplier` |
| `testCircuitBreakerMetricsToString` | `executeSupplier` (seeds state) |
| `testCircuitBreakerWithCustomConfiguration` | `executeSupplier` |
| `testMultipleCircuitBreakers` | `executeSupplier` |

Also delete `IntentionalTestFailureException` inner class — only used in deleted tests.

### Step 7 — Keep these tests unchanged

They test the API that remains:

- `testCircuitBreakerManagerInitialization` — tests constructor + `getCircuitBreakerNames()`
- `testCircuitBreakerMetricsForNonExistentBreaker` — tests `getMetrics()` for unknown name
- `testDisabledCircuitBreakerMetrics` — tests `getMetrics()` when disabled

### Step 8 — Add new tests for the remaining API

All new tests call `CircuitBreaker` methods directly — `tryAcquirePermission()`, `onSuccess()`, `onError()` — which is the correct production pattern.

> **Note on metrics recording:** Resilience4j only records metrics when a call is properly bracketed.
> The required sequence is: `tryAcquirePermission()` → execute → `onSuccess()` or `onError()`.
> Calling `onSuccess()` / `onError()` without a prior successful `tryAcquirePermission()` does **not**
> increment call counts. All new tests that assert on call counts must follow this sequence.

> **Note on `notPermittedCalls`:** this counter is incremented by the Resilience4j execute-path
> (e.g. `CircuitBreaker.decorateSupplier`), not by `tryAcquirePermission()` directly.
> Tests using `tryAcquirePermission()` should not assert on `notPermittedCalls`.

New tests to add:

1. **`testGetCircuitBreaker_createsAndCaches`** — call `getCircuitBreaker("x")` twice, verify same instance returned, name appears in `getCircuitBreakerNames()`.

2. **`testGetCircuitBreaker_disabledReturnsNull`** — verify `getCircuitBreaker()` returns `null` when disabled.

3. **`testForceOpen_circuitBreakerRefusesPermission`** — call `forceOpen("x")`, then verify `getCircuitBreaker("x").tryAcquirePermission()` returns `false` and `getMetrics("x").getState()` is `"OPEN"`.

4. **`testReset_returnsCircuitBreakerToClosed`** — `forceOpen`, then `reset`, verify `tryAcquirePermission()` returns `true` and state is `"CLOSED"`.

5. **`testMetrics_recordedViaDirectCircuitBreakerCalls`** — get CB; call the bracket sequence (`tryAcquirePermission` + `onSuccess`) twice for successes and once (`tryAcquirePermission` + `onError`) for a failure; verify `getMetrics()` reports correct counts. Keep `import java.util.concurrent.TimeUnit` since `onSuccess(long, TimeUnit)` requires it.

6. **`testMultipleCircuitBreakers_independentState`** — create two named CBs, force-open one, verify the other is still `"CLOSED"`.

### Step 9 — Remove all Vert.x machinery and now-unused imports

Remove these imports — none of the remaining or new tests need them:

- `import org.junit.jupiter.api.extension.ExtendWith`
- `@ExtendWith(VertxExtension.class)` class annotation
- `import io.vertx.junit5.VertxExtension`
- `import io.vertx.junit5.VertxTestContext`
- `import io.vertx.core.Vertx`
- `import java.util.concurrent.CountDownLatch`
- `import java.util.concurrent.atomic.AtomicInteger`
- `import io.github.resilience4j.circuitbreaker.CallNotPermittedException`

Keep `import java.util.concurrent.TimeUnit` — required by the new metrics test (Step 8, item 5).

---

## Relevant Files

| File | Change |
|---|---|
| `peegeeq-db/.../resilience/CircuitBreakerManager.java` | Delete 4 methods + 1 import |
| `peegeeq-db/.../resilience/CircuitBreakerManagerTest.java` | Delete 16 tests + inner class, keep 3, add 6 |
| `peegeeq-db/.../resilience/HealthCheckManager.java` | **No change** — already uses `getCircuitBreaker()` correctly |

---

## Verification

1. `grep_search` on `*.java` for `executeSupplier|executeRunnable|executeDatabaseOperation|executeQueueOperation` → zero matches.
2. `mvn clean test-compile -pl peegeeq-db` → BUILD SUCCESS.
3. `mvn test -pl peegeeq-db -Dtest=CircuitBreakerManagerTest --no-transfer-progress` → all tests pass.

---

## Decisions

- No reactive `execute()` method is added. That decision stands unless a second production caller appears that needs one.
- `CircuitBreakerMetrics` inner class is kept — it is the return type of `getMetrics()` which remains.

---

## Secondary Contamination: `OutboxMetricsTest` — Anti-Pattern Catalogue

The bad design in `CircuitBreakerManager` radiated outward and produced three distinct anti-patterns in `OutboxMetricsTest`. These are documented here as generic patterns because all three recur independently of the circuit-breaker subject matter.

---

### Anti-pattern 1: Wrong test placed in the wrong class

**What was there:**  
`testCircuitBreakerIntegration` was a test that called `executeDatabaseOperation` — the removed blocking API — placed inside `OutboxMetricsTest`. The class name, its `@BeforeEach`/`@AfterEach` setup, and every other test in it are about the outbox metrics system. The circuit-breaker test needed a full Testcontainers PostgreSQL database to run and used none of the outbox infrastructure.

**Why it matters:**  
A test class with a clear domain responsibility (`OutboxMetricsTest` → outbox metrics) that secretly also tests an unrelated subsystem (`CircuitBreakerManager`) is a scope violation. It creates three problems:
- The reader cannot trust the class's stated scope: if circuit-breaker tests are here, anything else might be too.
- The failure surface is wrong: a circuit-breaker regression surfaces as a failure in the outbox metrics test run, which misleads diagnosis.
- The test becomes load-bearing for the wrong class: when the bad API was deleted, this test would have failed with a compile error in a class that has nothing to do with the deletion.

**Generic rule:** A test class owns exactly one subject. If a test requires setup from class A to test behaviour in class B, it belongs to the test suite for class B. Never deposit tests in a convenient host class because setup already exists there.

---

### Anti-pattern 2: Blocking the test thread outside `executeBlocking`

**What was there:**  
`testHealthCheckIntegration` contained a polling loop directly on the test thread:

```java
while (System.currentTimeMillis() < deadline) {
    var status = healthCheckManager.getOverallHealth();
    if (status != null && status.isHealthy()) break;
    LockSupport.parkNanos(200_000_000L);
}
```

The test class carries `@ExtendWith(VertxExtension.class)`. In that context, the test thread is managed by Vert.x's JUnit 5 extension and shares lifecycle state with `VertxTestContext`. Parking the test thread here blocks it in a way that is indistinguishable from an unresponsive test, races with `VertxTestContext` timeout handling, and hides the wait from the Vert.x scheduler entirely.

**Why it matters:**  
In a Vert.x test context, any polling or waiting that takes longer than a trivial duration must happen off the test thread using `vertx.executeBlocking()`. The off-thread block is controlled, observable, and composable with the standard `onSuccess`/`onFailure`/`testContext.failNow()` teardown. A raw `park` or `Thread.sleep` on the test thread is the same category of mistake as blocking the event loop in production code — it works in isolation but breaks under load or test-runner parallelism.

**Fixed form:**
```java
void testHealthCheckIntegration(Vertx vertx, VertxTestContext testContext) {
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

    assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
}
```

**Generic rule:** In `@ExtendWith(VertxExtension.class)` tests, any blocking wait — polling, `Thread.sleep`, `LockSupport.park`, `CountDownLatch.await` — belongs inside `vertx.executeBlocking()`, not on the test thread directly.

---

### Anti-pattern 3: Logger calls with mismatched identity context

**What was there:**  
Three tests contained `logger.info(...)` calls with messages that identified the wrong test:

```java
// inside testMetricsIntegration — consumer subscribe lambda
logger.info("Test: metrics integration");   // ← misidentified; was "Test: circuit breaker integration"

// at start of testMultipleMessageMetrics
logger.info("Test: circuit breaker integration");   // ← clearly from a different test

// inside testErrorMetrics — consumer subscribe lambda
logger.info("Test: error metrics");   // ← body from error metrics test, label from circuit breaker test
```

These were produced by copying the circuit-breaker test's structure into other tests and updating the body without updating the logger strings.

**Why it matters:**  
Mismatched log context corrupts the diagnostic signal exactly when it is needed most — during a failure investigation. A developer reading test output that says `Test: circuit breaker integration` while the failure is in `testMultipleMessageMetrics` will look in the wrong place. The longer the search takes, the more expensive the mismatch becomes.

**Generic rule:** Logger calls inside tests must identify the test they are in. The safest approach is to use JUnit 5's `TestInfo` parameter or to omit per-test trace logs entirely, relying on the test runner's own reporting. If manual logging is kept, the message must match the actual test name, not a name copied from a template test.

---

### Summary

| Anti-pattern | Generic name | Root cause |
|---|---|---|
| Circuit-breaker test in metrics class | **Scope leakage** | Test authored against the wrong registration criterion (convenient setup vs. correct subject) |
| Spin-poll on test thread under VertxExtension | **Event-loop threading discipline violation in tests** | Poll not moved to `executeBlocking` when test class adopted Vert.x extension |
| Logger messages mismatched to test identity | **Copy-paste contamination of diagnostic context** | Log string not updated when test body was templated from another test |
