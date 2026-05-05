# peegeeq-db Full Test Suite — Failure Analysis
**Date**: 2026-05-05  
**Branch**: `feature/offset-watermark-phase1`  
**Command**: `mvn test -Pall-tests -pl :peegeeq-db`  
**Result**: 1,075 tests run — **2 Failures, 5 Errors, 6 Skipped**

---

## Summary Table

| # | Test | Type | Category | Root Cause |
|---|---|---|---|---|
| 1 | `MultiConfigurationExampleTest.testMultipleConfigurationRegistration` | Error (Timeout) | Race + Bug | Parallel test race on system properties + exception swallowed in `onSuccess` |
| 2 | `FanoutPerformanceValidationTest.testBasicThroughputValidation` | Error (Timeout) | Performance | Throughput target not met within timeout |
| 3 | `PerformanceTuningExampleTest.testLatencyOptimization` | Error (Runtime) | Config | `PeeGeeQManager` start fails — no DB connection available |
| 4 | `DatabaseTemplateManagerDropDrainTest.dropDatabase_mustSucceedWhenBackendsAreSlowToDisconnect` | Error (PgException) | Timing | Database still in use when drop is attempted |
| 5 | `PeeGeeQDatabaseSetupServiceEnhancedTest.testSetupWithEventStores` | Error (Runtime) | Config | Pool min-size > max-size validation fails |
| 6 | `BackfillTeardownDeadlockProofTest.testProbeBeta_BackfillWhileTeardownRaces` | Failure (Assertion) | Race probe | `expected: <500> but was: <100>` — probe counting wrong under race |
| 7 | `StuckMessageRecoveryManagerCoreTest.testGetRecoveryStats` | Failure (Assertion) | Logic | `expected: <0> but was: <1>` — recovery stats counter off by one |

---

## Detailed Analysis

---

### 1. `MultiConfigurationExampleTest.testMultipleConfigurationRegistration` — TIMEOUT

**Log evidence**:
```
09:57:17.653  ERROR PgConnectionManager  - Invalid schema config (allowed: letters, digits, underscore, comma, space): test-schema
09:57:17.655  ERROR MultiConfigurationManager - Failed to register configuration: development
09:57:17.656  ERROR ContextImpl - Unhandled exception
[ERROR] Time elapsed: 30.33 s <<< ERROR!
╗ Timeout The test execution timed out.
```

#### Root Cause — Three compounding issues

**Issue A: Parallel test race clears system properties**

- `junit-platform.properties` enables parallel execution at 4 threads.
- `MultiConfigurationExampleTest` holds `@ResourceLock("system-properties")`, but most other tests that also set `peegeeq.*` system properties do **not** declare this lock.
- The test chains three `vertx.timer(100)` calls (300ms total) before registering the fourth configuration `"development"`.
- During this 300ms window, a concurrent test's `tearDown` removes `peegeeq.database.schema=public` from system properties.

**Issue B: `peegeeq-test.properties` has an invalid schema name**

`PeeGeeQConfiguration.loadProperties("test")` loads `peegeeq-test.properties` first:
```properties
peegeeq.database.schema=test-schema
```
System properties are applied on top as the final override. When system properties are absent (because a concurrent test cleared them), the value falls back to `test-schema`. `PgConnectionManager` validates schema names and rejects hyphens — the `registerConfiguration("development", "test")` call throws `RuntimeException`.

**Issue C: Exception thrown inside `.onSuccess()` is swallowed**

```java
.onSuccess(v -> {
    configManager.registerConfiguration("development", "test");  // throws RuntimeException
    testContext.verify(() -> {
        ...
        testContext.completeNow();  // never reached
    });
})
.onFailure(testContext::failNow);  // not triggered — exception is in onSuccess, not Future failure
```

Vert.x catches the exception as an "unhandled exception" on the event loop. Neither `completeNow()` nor `failNow()` is ever called. The test context hangs until the 30s timeout fires.

#### Fix Required

1. Wrap synchronous calls inside `.onSuccess()` in try-catch calling `testContext.failNow(e)`.
2. Add `@ResourceLock("system-properties")` to all tests that set/clear `peegeeq.*` system properties in `peegeeq-db`, or eliminate the timer delays and register all four configurations synchronously before the Future chain.
3. Consider changing the `"test"` profile used in `MultiConfigurationExampleTest` to one that does not have an invalid schema name, or add an explicit `peegeeq.database.schema` override before calling `registerConfiguration`.

---

### 2. `FanoutPerformanceValidationTest.testBasicThroughputValidation` — TIMEOUT

**Log evidence**:
```
[ERROR] Time elapsed: 30.12 s <<< ERROR!
╗ Timeout The test execution timed out.
```

#### Root Cause

The test validates that a throughput target (likely messages/second) is achieved within a fixed wall-clock time. Under the 4-thread parallel test runner, competing tests consume CPU and I/O, causing the fanout producer or consumer to fall below the threshold. The test times out waiting for its `VertxTestContext` checkpoint to be flagged.

This is a **flaky performance test** — it is environment-sensitive (dependent on available CPU/IO during CI/parallel execution). It passed in isolation but fails when the full suite runs concurrently.

#### Fix Required

- Either mark with `@Tag("performance")` and exclude from `all-tests` parallel runs, or
- Raise the timeout and/or lower the throughput assertion threshold to be resilient to parallel execution overhead.

---

### 3. `PerformanceTuningExampleTest.testLatencyOptimization` — RUNTIME FAILURE

**Log evidence**:
```
09:57:16.825  ERROR PeeGeeQManager - Database connectivity validation failed: Connection refused: localhost/127.0.0.1:5432
09:57:16.825  ERROR PeeGeeQManager - Failed to start PeeGeeQ Manager reactively
[ERROR] Time elapsed: 0.192 s <<< ERROR!
╗ Runtime Failed to start PeeGeeQ Manager
```

#### Root Cause

The test attempts to start a `PeeGeeQManager` using `localhost:5432` — the default host/port, **not** the Testcontainers-mapped port. This means the system properties for host/port were not set when `PeeGeeQConfiguration` was loaded, and it fell back to the default values from `peegeeq-default.properties`.

Same race as issue 1A: `setUp` of this test ran after another test's `tearDown` cleared the `peegeeq.*` system properties, so the DB coordinates pointed at the default non-existent local PostgreSQL.

#### Fix Required

- Ensure `setUp` always sets all required system properties before `PeeGeeQConfiguration` is instantiated.
- Either acquire `@ResourceLock("system-properties")` or (better) pass configuration directly via `Properties` overrides rather than relying on global system properties.

---

### 4. `DatabaseTemplateManagerDropDrainTest.dropDatabase_mustSucceedWhenBackendsAreSlowToDisconnect` — PG ERROR

**Log evidence**:
```
[ERROR] Time elapsed: 5.310 s <<< ERROR!
io.vertx.pgclient.PgException: ERROR: database "test_drain_1777946365614" is being accessed by other users (55006)
```

#### Root Cause

The test creates a temporary database, opens connections into it, then attempts to `DROP DATABASE` after draining backends. PostgreSQL raises `55006` if any connection remains open when `DROP DATABASE` is executed.

The test relies on all backends disconnecting within a timing window. Under parallel test load, other JVM threads or connection pool idle threads may hold a connection open longer than expected, causing the drop to fail with `55006`.

#### Fix Required

- Use `FORCE` option (`DROP DATABASE ... WITH (FORCE)`) available in PostgreSQL 13+ to forcibly terminate remaining connections, or
- Increase the drain wait time/retry loop to be more resilient to parallel load, or
- Ensure the test acquires an exclusive resource lock to prevent other tests from opening connections to the same host during the drain window.

---

### 5. `PeeGeeQDatabaseSetupServiceEnhancedTest.testSetupWithEventStores` — RUNTIME FAILURE

**Log evidence**:
```
09:57:49.934  ERROR PeeGeeQDatabaseSetupService - Failed to create database setup: enhanced-test-setup-1777946268819 - 
  Configuration validation failed: Maximum pool size must be greater than or equal to minimum pool size
```

#### Root Cause

The test constructs a `DatabaseSetup` with pool configuration where `min-size > max-size`. This is either:

- A deliberate negative test that should assert the error but instead reaches an unexpected code path, or
- A regression in pool size validation logic on this branch that changed the threshold comparison direction, or
- System properties from another test (`peegeeq.database.pool.min-size=2`, `peegeeq.database.pool.max-size=3`) were modified by a concurrent test, leaving the pool config in an invalid state when this test ran.

#### Fix Required

Needs deeper investigation. Read `testSetupWithEventStores` to confirm whether the pool sizes are intentionally or accidentally invalid, and whether this is a new regression on this branch.

---

### 6. `BackfillTeardownDeadlockProofTest.testProbeBeta_BackfillWhileTeardownRaces` — ASSERTION FAILURE

**Log evidence**:
```
java.lang.AssertionError: org.opentest4j.AssertionFailedError: expected: <500> but was: <100>
```

#### Root Cause

This is a **probe test** that validates a race condition between backfill and teardown. It expects 500 messages to be processed but only 100 were. This suggests:

- The backfill was interrupted prematurely during the teardown race, or
- A change on this branch (`feature/offset-watermark-phase1`) altered the message/offset accounting logic, causing the backfill to terminate early at 100 (a round number suggesting a batch or window boundary), or
- The test's expected count (500) was not updated to match a changed batch size or watermark boundary in the new code.

**This is the most likely genuine regression** introduced by the watermark/offset changes on this branch.

#### Fix Required

Investigate `BackfillTeardownDeadlockProofTest.testProbeBeta_BackfillWhileTeardownRaces` and the backfill message counting logic for changes on this branch. The `100 vs 500` discrepancy is a strong signal that a watermark or batch window constant changed.

---

### 7. `StuckMessageRecoveryManagerCoreTest.testGetRecoveryStats` — ASSERTION FAILURE

**Log evidence**:
```
java.lang.AssertionError: org.opentest4j.AssertionFailedError: expected: <0> but was: <1>
```

#### Root Cause

The test expects `getRecoveryStats()` to return `0` for a particular counter, but gets `1`. This is likely:

- A new field was added to `RecoveryStats` on this branch (e.g., a watermark-related scan counter), and the test's assertion was not updated to account for it, or
- An initialization change in `StuckMessageRecoveryManager` now pre-increments a counter during construction or on the first stats query.

**Also a likely regression from the offset-watermark-phase1 changes.**

#### Fix Required

Read `StuckMessageRecoveryManagerCoreTest.testGetRecoveryStats` and `StuckMessageRecoveryManager.getRecoveryStats()` to identify which counter changed from 0 to 1 on this branch.

---

## Priority Classification

| Priority | Tests | Action |
|---|---|---|
| **High — likely regressions from this branch** | `BackfillTeardownDeadlockProofTest`, `StuckMessageRecoveryManagerCoreTest` | Investigate offset-watermark changes |
| **Medium — pre-existing issues / environment** | `MultiConfigurationExampleTest`, `PerformanceTuningExampleTest`, `PeeGeeQDatabaseSetupServiceEnhancedTest` | Fix exception handling + system property race |
| **Low — flaky / timing-sensitive** | `FanoutPerformanceValidationTest`, `DatabaseTemplateManagerDropDrainTest` | Address test resilience |

---

## Environment Notes

- Parallel execution: 4 threads (`junit.jupiter.execution.parallel.config.fixed.parallelism=4`)
- System properties race: multiple tests modify `peegeeq.*` system properties without consistent `@ResourceLock("system-properties")` coverage
- `peegeeq-test.properties` contains `peegeeq.database.schema=test-schema` which is invalid (hyphens not allowed)
- Log file: `logs/peegeeq-db-all-tests-20260505.txt`
