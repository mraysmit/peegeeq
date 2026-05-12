# peegeeq-db Integration Test Failure Analysis — 2026-05-12

## Overview

Two integration tests in `peegeeq-db` failed intermittently when the full module test suite
was run concurrently. Both tests passed in isolation. Root cause was confirmed to be shared
database state mutated by a background job started in an unrelated test class.

---

## Failing Tests

| Test class | Test method | Symptom |
|---|---|---|
| `CompletionTrackerIntegrationTest` | `testMarkFailedRepeatedlyIncrementsRetryCount` | `expected: <error 2> but was: <null>` — `error_message` was `null` after second `markFailed` call |
| `DeadConsumerDetectionJobIntegrationTest` | `testEndToEndDetectCleanupPipeline` | `expected: <COMPLETED> but was: <PENDING>` — outbox message status not yet written by the time the assertion ran |

---

## Shared Infrastructure

All `peegeeq-db` integration tests share a single `PostgreSQLContainer` via
`SharedPostgresTestExtension`. The schema is created once and data from all tests coexists
in the same database for the lifetime of the test run. Isolation between tests relies on:

- unique topic names per test
- `@ResourceLock` annotations where needed
- background jobs being disabled in `BaseIntegrationTest` via explicit config properties

---

## Root Cause 1 — `testMarkFailedRepeatedlyIncrementsRetryCount`

### What happened

`DeadConsumerDetectionJobLifecycleTest.setUp()` created a `PeeGeeQManager` with
`peegeeq.queue.dead-consumer-detection.enabled=true`. It did **not** set
`peegeeq.queue.consumer-group-retry.enabled=false`. The default for that property in
`PeeGeeQConfiguration` is `true` (line 352):

```java
boolean consumerGroupRetryEnabled = getBoolean("peegeeq.queue.consumer-group-retry.enabled", true);
```

This caused `PeeGeeQManager` to start a `ConsumerGroupRetryJob` that runs
`ConsumerGroupRetryService` on a periodic timer. That service executes:

```sql
UPDATE outbox_consumer_groups
SET status = 'PENDING', error_message = NULL
WHERE status = 'FAILED'
  AND retry_count < max_retries
  AND ...
```

This SQL clears `error_message` on ALL `FAILED` rows in the shared database — regardless
of which test created them. When `testMarkFailedRepeatedlyIncrementsRetryCount` called
`markFailed("error 2")`, then immediately read back the row, the retry job had already
reset `error_message` to `NULL`.

### Why `retry_count` assertion still passed

`ConsumerGroupRetryService` does **not** reset `retry_count`. It only resets
`status = 'PENDING'` and `error_message = NULL`. The test checked both fields:

- `retry_count = 1` → PASS (not modified by retry service)
- `error_message = "error 2"` → FAIL (cleared to `null` by retry service)

### Timeline

```
[lifecycle test setUp]  PeeGeeQManager starts → ConsumerGroupRetryJob timer starts
[completion tracker test]  markFailed("error 1") → row: status=FAILED, error_message="error 1", retry_count=1
[completion tracker test]  markFailed("error 2") → row: status=FAILED, error_message="error 2", retry_count=1
[ConsumerGroupRetryJob fires]  → error_message=NULL, status=PENDING (retry_count unchanged)
[completion tracker test]  getTrackingRowStatus → error_message=null  ← FAIL
```

### Fix

Added to `DeadConsumerDetectionJobLifecycleTest.setUp()`:

```java
// Disable retry job — this test does not need it and it mutates outbox_consumer_groups globally
testProps.setProperty("peegeeq.queue.consumer-group-retry.enabled", "false");
```

This follows the same pattern already used in `BaseIntegrationTest` which sets both
`dead-consumer-detection.enabled=false` and `consumer-group-retry.enabled=false`.

---

## Root Cause 2 — `testEndToEndDetectCleanupPipeline`

### What happened

`testEndToEndDetectCleanupPipeline` calls `job.stop()` to halt the
`DeadConsumerDetectionJob` before asserting that messages have been marked `COMPLETED`.
`job.stop()` returns `Future<Void>` that resolves only after any in-flight detection
cycle completes — including the `cleanupAllDeadGroups` call which issues the
`UPDATE outbox SET status='COMPLETED' ...` SQL.

The original code discarded the returned `Future`:

```java
.compose(messageIds -> {
    job.stop();                        // Future<Void> discarded — fire and forget
    assertTrue(job.getTotalDeadDetected() >= 1, ...);
    ...
    return getSubscriptionStatus(topic, "group-b")
            .map(v -> messageIds);
})
```

Because the Future was discarded, the compose chain continued immediately while the
in-flight detection cycle was still executing. The message status was still `PENDING`
when `verifyCompletedMessagesAndReturn` queried it.

### Fix

Chained on the `job.stop()` Future so the assertions and status verification only run
after the stop (and any in-flight cleanup) completes:

```java
.compose(messageIds -> job.stop().compose(v -> {
    assertTrue(job.getTotalDeadDetected() >= 1,
            "Job should have detected at least 1 dead consumer");
    assertTrue(job.getTotalRunCount() >= 1,
            "Job should have completed at least 1 run");
    assertEquals(0, job.getTotalFailures(),
            "Job should have no failures");
    return getSubscriptionStatus(topic, "group-b")
            .map(ignored -> messageIds);
}))
```

---

## Pattern: Background Jobs and Shared Database State

Both failures share a common class of problem:

> A background job that mutates shared database rows was allowed to run during tests that
> do not expect or need it.

### Prevention rules

1. **Any test that starts a `PeeGeeQManager` must explicitly disable all background jobs
   it does not need.** Do not rely on defaults. Properties to consider:
   - `peegeeq.queue.dead-consumer-detection.enabled`
   - `peegeeq.queue.consumer-group-retry.enabled`

2. **Never discard a `Future<Void>` returned by a stop/close method in a test compose
   chain.** If stopping a job is a prerequisite for an assertion, compose on the stop
   Future before running that assertion.

3. **`BaseIntegrationTest` correctly sets both job-disable flags.** Any test that extends
   it is safe. Tests that create their own `PeeGeeQManager` directly (as
   `DeadConsumerDetectionJobLifecycleTest` does) must replicate the safe subset of that
   config.

---

## Files Changed

| File | Change |
|---|---|
| `peegeeq-db/src/test/java/dev/mars/peegeeq/db/DeadConsumerDetectionJobLifecycleTest.java` | Added `peegeeq.queue.consumer-group-retry.enabled=false` in `setUp()` |
| `peegeeq-db/src/test/java/dev/mars/peegeeq/db/fanout/DeadConsumerDetectionJobIntegrationTest.java` | Composed on `job.stop()` Future in `testEndToEndDetectCleanupPipeline` instead of fire-and-forget |

---

## Targeted Verification Command

To verify the fixes without running the full module suite:

```powershell
mvn test -Pintegration-tests -pl :peegeeq-db "-Dtest=CompletionTrackerIntegrationTest#testMarkFailedRepeatedlyIncrementsRetryCount+DeadConsumerDetectionJobIntegrationTest#testEndToEndDetectCleanupPipeline+DeadConsumerDetectionJobLifecycleTest" 2>&1 | Tee-Object -FilePath logs\peegeeq-db-targeted-fix-20260512.txt
```

Running all three together is essential for verifying Fix 1: `DeadConsumerDetectionJobLifecycleTest`
must be present to exercise the race condition that Fix 1 eliminates.
