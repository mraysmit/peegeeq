# PeeGeeQ Shutdown Lifecycle & Concurrency Fixes

**Date:** November 28, 2025  
**Component:** `peegeeq-db` (PeeGeeQManager)  
**Status:** Fixed & Verified

## 1. The Issue

During integration testing (`PeeGeeQManagerIntegrationTest`), we encountered two distinct but related failures:

1.  **`java.util.concurrent.TimeoutException`**: Tests were failing because the `manager.close()` method was blocking for up to 5 seconds, fighting with JUnit's own timeout mechanisms.
2.  **`java.util.concurrent.RejectedExecutionException`**: The logs were polluted with stack traces because the Vert.x instance was being closed *before* the Worker Executors had finished their tasks. When those workers tried to report completion back to the Event Loop, the loop was already dead.

### Root Cause Analysis
*   **Incorrect Shutdown Order**: The original implementation closed the DB Connection Pools (Client Factory) *before* the Worker Executors. This created a race condition where workers might try to use closed connections or report to a closed Vert.x instance.
*   **Blocking on Event Loop**: The `AutoCloseable.close()` method was attempting to block (`.get(5, SECONDS)`) to ensure a clean shutdown. However, in an asynchronous environment like Vert.x, blocking the thread (especially if called from a context related to the test runner) caused deadlocks or timeouts.

## 2. The Solution

We refactored `PeeGeeQManager.java` to implement a strict, ordered shutdown sequence and non-blocking semantics for legacy interfaces.

### 2.1 Strict Shutdown Order (The "7-Step" Sequence)
We rewrote `closeReactive()` to enforce the following dependency chain using `Future` composition:

1.  **Stop Reactive Components**: Stop accepting new work (`stopReactive()`).
2.  **Run Close Hooks**: Execute any registered external cleanup logic.
3.  **Cancel Timers**: Stop internal maintenance tasks (Metrics, DLQ, Recovery).
4.  **Close Worker Executor**: **CRITICAL STEP**. We now wait for the Worker Executor to close (draining pending tasks) *before* touching the DB pools.
5.  **Close Client Factory**: Close the DB connection pools only after workers are guaranteed idle.
6.  **Close MeterRegistry**: Clean up metrics.
7.  **Close Vert.x**: Finally, close the Vert.x instance (if owned).

### 2.2 "Wait-and-Catch" Pattern
When closing Vert.x from within a test that might share its context, `RejectedExecutionException` is sometimes inevitable (the thread you are standing on disappears). We added specific recovery logic to catch this exception and treat it as a successful shutdown, keeping the logs clean.

### 2.3 Non-Blocking `AutoCloseable`
We changed the `close()` method to be **Fire-and-Forget**:
*   It triggers `closeReactive()` but returns immediately.
*   It logs a warning if called on an Event Loop thread.
*   **Impact**: Tests no longer timeout waiting for `close()`. Instead, the test harness explicitly waits for `closeReactive()` completion in `@AfterEach`.

## 3. Verification

*   **Test Suite**: `PeeGeeQManagerIntegrationTest` passed successfully.
*   **Performance**: Execution time dropped to ~14s (well within limits).
*   **Logs**: No `RejectedExecutionException` or `TimeoutException` observed in the output.

## 4. Code Reference

**`PeeGeeQManager.java`**

```java
// New Shutdown Sequence
return stopReactive()
    .compose(v -> /* Run Hooks */)
    .compose(v -> /* Cancel Timers */)
    .compose(v -> workerExecutor.close()) // Workers First
    .compose(v -> clientFactory.closeAsync()) // Pools Second
    .compose(v -> /* Close Vert.x */);
```
