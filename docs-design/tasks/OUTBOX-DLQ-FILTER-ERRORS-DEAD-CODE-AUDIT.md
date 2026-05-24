# Outbox DLQ / Filter-Error Dead Code Audit

**Date:** 2026-05-24  
**Scope:** `peegeeq-outbox` dead letter queue abstraction, filter-error handling, and test-contamination in production classes  
**Status:** Steps 1–7 complete. All tests passing.

---

## Background

### How peegeeq-native handles dead letters (reference implementation)

`PgNativeQueueConsumer` is the clearest working example of the correct DLQ pattern across the whole codebase.

When a message handler throws, `handleProcessingFailure()` checks `retryCount >= maxRetries`. If exhausted it calls `moveToDeadLetterQueue()`, which runs a **single atomic transaction**:

```
pool.withTransaction(conn ->
    SELECT payload, headers, ... FROM queue_messages WHERE id = $1
    → INSERT INTO dead_letter_queue (original_table='queue_messages', original_id, topic, payload, ...)
    → DELETE FROM queue_messages WHERE id = $1
)
```

The message is physically removed from `queue_messages` when it moves to the DLQ. On DLQ write failure the fallback is to delete the message (documented). The same `dead_letter_queue` table is used by all three modules (`peegeeq-native`, `peegeeq-outbox`, `peegeeq-db`).

`OutboxConsumer` follows the same pattern, with one structural difference: the outbox row is not deleted but marked `status = 'DEAD_LETTER'` in place.

### The three DLQ write paths compared

| Module | Source table | DLQ operation | On DLQ write failure |
|--------|-------------|---------------|----------------------|
| `peegeeq-native` | `queue_messages` | SELECT + INSERT + DELETE (atomic) | Fallback: delete the message |
| `peegeeq-outbox` (delivery) | `outbox` | INSERT + UPDATE `status='DEAD_LETTER'` | Logs error |
| `peegeeq-outbox` (filter error) | — | Filter exception caught → returns `false` → `resetFilteredMessageToPending()` — **no `retry_count` increment** | Message loops in PENDING forever |

The native and outbox delivery paths are structurally consistent. The filter-error path catches exceptions, returns `false`, and resets to PENDING without incrementing `retry_count`, creating an infinite retry loop for messages with persistent filter failures.

---

### The three DLQ abstraction layers in the codebase

There are two entirely separate dead letter queue mechanisms in the outbox module. These must not be confused:

### Path 1 — Outbox delivery failures (works correctly)
`OutboxConsumer` → `pool.withTransaction(...)` → `INSERT INTO dead_letter_queue` + `UPDATE outbox SET status = 'DEAD_LETTER'`

This path is correct. When an outbox message exhausts its delivery retries, `OutboxConsumer` writes the message to the PostgreSQL `dead_letter_queue` table and marks the outbox row as `DEAD_LETTER`. The `peegeeq-db DeadLetterQueueManager` implements the same table via `DeadLetterService` for read/reprocess/delete operations.

### Path 2 — Filter-error DLQ path (broken / dead code)
`AsyncFilterRetryManager` → outbox `DeadLetterQueueManager` → `LoggingDeadLetterQueue`

This path logs 6 ERROR lines and returns `Future.succeededFuture()`. Nothing is persisted. Additionally, as documented in Issue 1 below, this path is **never called by production code** — it is dead code.

---

## What is a "filter error"? — Requirements from design documents

A consumer group member can hold an optional `Predicate<Message<T>> messageFilter`. When a message arrives:

- Predicate returns `false` → message is silently skipped (not an error, by design).
- Predicate **throws an exception** → this is a "filter error."

Filters are the mechanism for content-based routing. A filter might call a downstream service to validate a field, parse a complex payload, or check a header. Any of those can fail transiently (e.g. a database lookup times out) or permanently (e.g. invalid data format). The distinction matters because transient failures should trigger retries while permanent failures should not.

### Documented functional requirements (`docs/PEEGEEQ_ARCHITECTURE_API_GUIDE.md`, `docs/PEEGEEQ_COMPLETE_GUIDE.md`)

The architecture guide devotes a full section to filter error handling and specifies it as a first-class documented feature.

**1. Error classification** — filter exceptions must be automatically classified:

| Class | Characteristics | Examples |
|-------|----------------|---------|
| **Transient** | Temporary; may succeed on retry | Network timeout, connection failure, temporary resource unavailability |
| **Permanent** | Persistent; won't succeed on retry | Invalid data format, authorization failure, malformed message |
| **Unknown** | Unclassified | Falls through to configurable default strategy |

**2. Four recovery strategies** — each `OutboxConsumerGroupMember` can be configured with any of:

| Strategy | Behaviour |
|----------|----------|
| `REJECT_IMMEDIATELY` | Reject without retry — for permanent errors or high-performance scenarios |
| `RETRY_THEN_REJECT` | Exponential backoff retries, then reject — transient errors with graceful degradation |
| `RETRY_THEN_DEAD_LETTER` | Exponential backoff retries, then write to DLQ — critical messages that must not be lost |
| `DEAD_LETTER_IMMEDIATELY` | Skip retries, write to DLQ immediately — permanent errors requiring manual intervention |

**3. DLQ integration requirements** (from docs):
- Messages must be **persisted**, not lost.
- DLQ entries must include metadata enrichment: error classification, attempt count, stack traces.
- Topic routing: different DLQ topics per error type or consumer.
- Monitoring: success rates and failure tracking.
- "**No Message Loss: Critical messages are never lost due to filter errors**" — stated explicitly in `PEEGEEQ_COMPLETE_GUIDE.md` Key Benefits section.

**4. Circuit breaker** — three-state machine (CLOSED → OPEN → HALF_OPEN):
- OPEN state fast-fails without calling the filter — protects against cascading failures.
- Configurable: failure threshold, timeout duration, minimum request count.

**5. Retries** — configurable via `maxRetries`, `initialRetryDelay`, `retryBackoffMultiplier`, `maxRetryDelay`.

### Documented non-functional requirements (performance targets)

| Scenario | Required throughput |
|----------|-------------------|
| Normal operation | >1,000 msg/sec |
| Filter exceptions (20% of messages) | >500 msg/sec |
| Circuit breaker open (fast-fail) | >2,000 msg/sec |
| Async retries active | >300 msg/sec |
| DLQ operations | >100 msg/sec |

### What the docs show about wiring

The architecture guide's component diagram shows `OutboxConsumerGroupMember` → `AsyncFilterRetryManager` → `DeadLetterQueueManager` as a connected, operational pipeline. The `OutboxConsumerGroupMember` constructor is documented as accepting a `FilterErrorHandlingConfig` parameter for per-consumer configuration. The docs contain no mention of any TODOs or unimplemented paths.

### The gap — and what actually happens

The `OutboxConsumerGroupMember.acceptsMessage()` method catches filter exceptions and returns `false`. In `OutboxConsumerGroup`, a `false` from all members produces `MessageFilteredException`, which `OutboxConsumer` handles by calling `resetFilteredMessageToPending()` — resetting the outbox row to PENDING **without incrementing `retry_count`**.

This means a message whose filter consistently throws will loop indefinitely: pick up → filter exception → `return false` → `MessageFilteredException` → reset to PENDING → next poll → repeat. It never reaches `maxRetries`. It never reaches DLQ. The message is not lost in the sense of being dropped, but it is permanently stuck and will never be processed or surfaced for manual intervention.

The `AsyncFilterRetryManager` abstraction was built to solve this but was never wired in. Per `docs-design/dev/pgq-coding-principles.md`: *"DLQ routing is a framework responsibility. The message processing pipeline should handle failures through its own error channel."* The existing outbox retry + DLQ infrastructure (`handleMessageFailureWithRetry()`) already does this correctly for delivery failures. The correct fix is to make filter exceptions flow through the same path, not to add a parallel inline abstraction.

The entire `AsyncFilterRetryManager` / `DeadLetterQueue` / outbox `DeadLetterQueueManager` / `LoggingDeadLetterQueue` layer is the wrong architecture, not merely an incomplete one.

---

## Issues

### Issue 1 — `AsyncFilterRetryManager` is dead production code (Critical)

`AsyncFilterRetryManager` has **zero production call sites**. It is only ever instantiated in `AsyncRetryMechanismTest.java`. The entire class — including its `executeBlocking` usage and `.otherwise(...)` invocations — is untriggerable from production.

The intended wire-up point was `OutboxConsumerGroupMember.acceptsMessage()`, which contains:
```java
// TODO: Implement async retry logic for transient errors
return false;
```

This TODO was never implemented.

**Impact:** The `AsyncFilterRetryManager`, outbox `DeadLetterQueueManager`, `LoggingDeadLetterQueue`, `DeadLetterQueue` interface, `DeadLetterMetrics`, and `FilterErrorHandlingConfig` together form a complete feature that was built, tested in isolation, but never wired into the actual message processing path.

---

### Issue 2 — Filter exceptions cause an infinite PENDING loop (Critical)

When a filter exception is caught in `acceptsMessage()` and `false` is returned:

1. `OutboxConsumerGroup` sees no eligible consumers → throws `MessageFilteredException`
2. `OutboxConsumer` catches `MessageFilteredException` and calls `resetFilteredMessageToPending(messageId)`
3. `resetFilteredMessageToPending()` issues `UPDATE outbox SET status='PENDING'` — **`retry_count` is not incremented**
4. The message is picked up again on the next poll and the cycle repeats

A message whose filter consistently throws (permanent error) will never escape this loop. It will never reach `maxRetries`, never call `handleMessageFailureWithRetry()`, and never be written to `dead_letter_queue`. It is permanently stuck in PENDING, invisible to operators.

This is the actual failure mode. The `AsyncFilterRetryManager` abstraction (Issue 1) was built to solve it but was never wired in, and per the coding principles doc is the wrong architecture regardless.

---

### Issue 3 — `.otherwise(...)` banned pattern in `AsyncFilterRetryManager` (High)

In `AsyncFilterRetryManager.sendToDeadLetterQueue()`:

```java
.otherwise(throwable -> {
    logger.error("Failed to send message {} to dead letter queue: {}",
        message.getId(), throwable.getMessage());
    return FilterResult.deadLetter("DLQ failed: " + throwable.getMessage(), attempts, totalTime);
});
```

`.otherwise(...)` is banned (same error-silencing as `.recover()`). DLQ send failures are converted to a synthetic success result, making them invisible to callers. Must be replaced with `.transform(...)` and proper failure propagation.

---

### Issue 4 — `executeBlocking` banned pattern in `AsyncFilterRetryManager` (High)

In `AsyncFilterRetryManager.executeFilterWithRetry()`:

```java
filterFuture = vertx.executeBlocking(() -> {
    boolean result = filter.test(message);
    ...
});
```

`executeBlocking` is banned by project rules. Filter execution must use composable futures.

Additionally, the same method contains a second execution path specifically for "tests without Vert.x context":
```java
} else {
    // No Vertx execute inline (for tests without Vert.x context)
    try {
        boolean result = filter.test(message);
```
This is test-awareness embedded in production code — production behaviour differs from test behaviour.

---

### Issue 5 — Test-awareness code embedded in three production classes (High)

**a) `LoggingDeadLetterQueue.sendToDeadLetter()`** (lines 44–47):
```java
boolean isIntentionalTest = reason != null && reason.contains("INTENTIONAL TEST FAILURE");
String logPrefix = isIntentionalTest ? "🧪 INTENTIONAL TEST FAILURE - " : "";
String logSuffix = isIntentionalTest ? " (THIS IS EXPECTED IN TESTS)" : "";
```
Applied to all 6 `logger.error()` calls. Production code branches on test identity.

**b) Outbox `DeadLetterQueueManager.sendToDeadLetter()`** (lines 76–79): identical `isIntentionalTest` / `logPrefix` / `logSuffix` pattern applied to `logger.warn()`.

**c) `FilterErrorHandlingConfig.testingConfig()`** — a factory method in a production configuration class that encodes test-string patterns as error classification rules:
```java
public static FilterErrorHandlingConfig testingConfig() {
    return builder()
        .addTransientErrorPattern("INTENTIONAL TEST FAILURE")
        .addTransientErrorPattern("Simulated")
        ...
}
```

**d) `DatabaseSetupHandler.isTestScenario()`** (peegeeq-rest, lines 554–567) — a private method in a production REST handler that sniffs `setupId.startsWith("test-")`, `setupId.equals("non-existent-setup")`, and `message.contains("INTENTIONAL TEST FAILURE")` to downgrade HTTP 503 responses to 404.

---

### Issue 6 — Fire-and-forget failure in outbox `DeadLetterQueueManager` (Medium)

In outbox `DeadLetterQueueManager.sendToDeadLetter()`:
```java
return deadLetterQueues.get(topic).sendToDeadLetter(...)
    .onFailure(throwable -> logger.error(...));
```

The `.onFailure(...)` handler logs the failure but does not propagate it. The returned `Future<Void>` always succeeds from the caller's perspective. The failure is observed but then discarded.

---

### Issue 7 — Two classes named `DeadLetterQueueManager` with opposite responsibilities (Medium)

| Class | Module | Writes to DB? |
|-------|--------|---------------|
| `dev.mars.peegeeq.db.deadletter.DeadLetterQueueManager` | `peegeeq-db` | Yes — `INSERT INTO dead_letter_queue` via reactive pool |
| `dev.mars.peegeeq.outbox.deadletter.DeadLetterQueueManager` | `peegeeq-outbox` | No — routes to `LoggingDeadLetterQueue` |

The names are identical. Code that appears to be using a `DeadLetterQueueManager` may or may not be persisting anything, depending on which import is in scope.

---

### Issue 8 — `AsyncFilterRetryManager` violates the naming rules (Low)

The coding principles state: *do not use `Async` or `Reactive` suffixes to indicate asynchrony in a fully Future-based API; use them only when a synchronous variant with the same base name also exists.*

There is no `FilterRetryManager` (sync variant). The `Async` prefix is redundant — `Future<FilterResult>` already conveys async behaviour. The class should be renamed `FilterRetryManager`.

This is a secondary concern given Issue 1 (the class is dead code), but it must be corrected when the class is either wired in or replaced.

---

### Issue 9 — Unit tests generate hundreds of unmarked ERROR log lines (Medium)

`LoggingDeadLetterQueueCoreTest`, `DeadLetterQueueManagerCoreTest`, and `LoggingDeadLetterQueueCoverageTest` call `sendToDeadLetter` with placeholder reasons (`"reason"`, `"Reason 0"`, `"test"`, `"Test failure"`, `"Processing failed"`, `"Failure 1"` through `"Failure 5"`). Each call produces 6 ERROR lines from `LoggingDeadLetterQueue`. None have log banners.

The outbox test run produces 200+ `DEAD LETTER QUEUE` ERROR entries that are indistinguishable from real failures in the log output.

The production code attempted to suppress these with the `isIntentionalTest` string-sniff (Issue 5a/5b), but that check only fires when the reason contains `"INTENTIONAL TEST FAILURE"`. The majority of test calls use none of those strings, so most errors appear unmarked regardless.

`LoggingDeadLetterQueueCoverageTest` also contains a test called `testIntentionalTestFailureMarking` that asserts the bad production behaviour described in Issue 5a. That test should be deleted when the production code is fixed.

---

## Root Cause

All of these issues trace to a single agent session in September 2025 (commits `3beb10b8`, `9c5966ba`, `2347d4b3`) that introduced a complete filter-error handling abstraction without:

1. Reading `docs-design/dev/pgq-coding-principles.md`, which states DLQ routing is a framework responsibility — not inline application logic.
2. Understanding that `OutboxConsumer` already had a working retry + DLQ mechanism (`handleMessageFailureWithRetry()`), and that the correct fix was to route filter exceptions through it.
3. Ever wiring `AsyncFilterRetryManager` into `OutboxConsumerGroupMember.acceptsMessage()`.
4. Writing a database-backed `DeadLetterQueue` implementation.

`LoggingDeadLetterQueue` was left as a placeholder. Everything that followed — the test-awareness string-sniffing, the `.otherwise(...)` suppression, the false log markers, the `testingConfig()` test strings — was patchwork applied to paper over the consequences.

---

## Remediation Plan

Order is mandatory. Do not start a later step before completing the earlier one.

| # | Action | Scope |
|---|--------|-------|
| 1 | Delete the entire wrong-architecture abstraction layer: `DeadLetterQueue` interface, `LoggingDeadLetterQueue`, outbox `DeadLetterQueueManager`, `DeadLetterMetrics`, `AsyncFilterRetryManager`, and all their test classes | `peegeeq-outbox` |
| 2 | Fix `acceptsMessage()` in `OutboxConsumerGroupMember`: remove the try-catch that swallows filter exceptions. Circuit breaker OPEN → still return `false`. Filter throws → record the circuit breaker failure and **re-throw** so the exception propagates up through `OutboxConsumerGroup.dispatchMessage()` to `OutboxConsumer`, where it falls through to `handleMessageFailureWithRetry()` — incrementing `retry_count` and eventually writing to `dead_letter_queue` via the existing mechanism. | `peegeeq-outbox` |
| 3 | Review `FilterErrorHandlingConfig` and `FilterCircuitBreaker`: remove DLQ strategy config and anything that depended on `AsyncFilterRetryManager`. Retain circuit breaker logic if it remains useful; delete `FilterErrorHandlingConfig` entirely if nothing remains. | `peegeeq-outbox` |
| 4 | Remove `isIntentionalTest` / `logPrefix` / `logSuffix` from any remaining production files | `peegeeq-outbox` |
| 5 | Remove `isTestScenario()` from `DatabaseSetupHandler` and fix affected tests | `peegeeq-rest` |
| 6 | Rewrite filter-related tests to assert the corrected behaviour: filter exception → message eventually reaches `dead_letter_queue` (requires integration test with Testcontainers, following the pattern in `OutboxConsumerCrashRecoveryTest`) | `peegeeq-outbox` |
| 7 | Fix DLQ SQL in `OutboxConsumer.handleMessageFailureWithRetry()` to use schema-qualified table names, then add a multi-tenant integration test proving DLQ writes go to the correct tenant schema and are isolated from other tenants | `peegeeq-outbox` |

---

## Files to Change

| File | Action |
|------|--------|
| `peegeeq-outbox/.../deadletter/DeadLetterQueue.java` | Delete (interface) |
| `peegeeq-outbox/.../deadletter/LoggingDeadLetterQueue.java` | Delete |
| `peegeeq-outbox/.../deadletter/DeadLetterQueueManager.java` | Delete |
| `peegeeq-outbox/.../resilience/AsyncFilterRetryManager.java` | Delete |
| `peegeeq-outbox/.../config/FilterErrorHandlingConfig.java` | Remove `testingConfig()` and DLQ strategy config; or delete if the whole class is no longer needed |
| `peegeeq-rest/.../handlers/DatabaseSetupHandler.java` | Remove `isTestScenario()` private method and its call site |
| `peegeeq-outbox/src/test/.../AsyncRetryMechanismTest.java` | Delete |
| `peegeeq-outbox/src/test/.../deadletter/LoggingDeadLetterQueueCoreTest.java` | Delete |
| `peegeeq-outbox/src/test/.../deadletter/LoggingDeadLetterQueueCoverageTest.java` | Delete |
| `peegeeq-outbox/src/test/.../deadletter/DeadLetterQueueManagerCoreTest.java` | Delete |
| `peegeeq-outbox/src/test/.../deadletter/DeadLetterQueueManagerCoverageTest.java` | Delete |

---

## Required Test Cases

All integration tests must use `@Tag(TestCategories.INTEGRATION)`, `@Testcontainers`, `PostgreSQLTestConstants.createStandardContainer()`, `VertxTestContext`, and `@ExtendWith(VertxExtension.class)`. Follow the pattern in `OutboxConsumerCrashRecoveryTest`.

### TC-1 — Filter exception increments `retry_count` (not infinite PENDING loop)

**Precondition:** Consumer group member has a filter that always throws.  
**Action:** Insert one message, run one poll cycle.  
**Assert:** `SELECT retry_count FROM outbox WHERE id = ?` returns `1`. Status remains `PENDING` or `PROCESSING`. It does **not** reset to `retry_count = 0`.

This is the regression test for Issue 2. If `resetFilteredMessageToPending()` is still being called for filter exceptions, `retry_count` stays at 0.

---

### TC-2 — Filter exception exhausting `maxRetries` writes message to `dead_letter_queue`

**Precondition:** Consumer group member with a filter that always throws. `maxRetries = 2`.  
**Action:** Insert one message, run enough poll cycles to exhaust retries.  
**Assert:**
- `SELECT COUNT(*) FROM dead_letter_queue WHERE original_id = ?` returns `1`
- `SELECT status FROM outbox WHERE id = ?` returns `DEAD_LETTER`
- `failure_reason` column in `dead_letter_queue` contains the filter exception class and message

This is the primary correctness test — the message must reach `dead_letter_queue` via the existing `handleMessageFailureWithRetry()` path, not via any custom filter-error abstraction.

---

### TC-3 — Transient filter exception followed by success processes the message normally

**Precondition:** Consumer group member with a filter that throws on the first call then returns `true`.  
**Action:** Insert one message, run two poll cycles.  
**Assert:**
- After poll 1: `retry_count = 1`, status `PENDING`
- After poll 2: message processed, status `COMPLETED`
- `dead_letter_queue` is empty

---

### TC-4 — Circuit breaker OPEN resets message to PENDING without incrementing `retry_count`

**Precondition:** Consumer group member whose circuit breaker has been tripped to OPEN (enough prior failures).  
**Action:** Insert one message, run one poll cycle while circuit breaker is OPEN.  
**Assert:**
- `SELECT retry_count FROM outbox WHERE id = ?` returns `0` — not incremented
- `SELECT status FROM outbox WHERE id = ?` returns `PENDING`
- `dead_letter_queue` is empty

This verifies that circuit breaker OPEN is deliberately not a retry-count failure — it is infrastructure protection, not message failure. The message waits for the circuit breaker to recover.

---

### TC-5 — Filter returning `false` (not throwing) leaves message for other consumers

**Precondition:** Consumer group with two members. Member A filter returns `false`. Member B has no filter.  
**Action:** Insert one message, run one poll cycle.  
**Assert:**
- Message processed by Member B, status `COMPLETED`
- `retry_count = 0`
- `dead_letter_queue` is empty

Confirms that predicate `false` (legitimate filter rejection) is unaffected by the fix — it still produces `MessageFilteredException` → `resetFilteredMessageToPending()` when no eligible consumer exists, or is processed by another eligible consumer.

---

### TC-6 — Single-member group with filter returning `false` resets to PENDING (existing behaviour preserved)

**Precondition:** Single-member consumer group. Filter always returns `false`.  
**Action:** Insert one message, run one poll cycle.  
**Assert:**
- `SELECT retry_count FROM outbox WHERE id = ?` returns `0` — not incremented (this is correct: the message is not for this consumer)
- Status `PENDING`

Distinguishes deliberate rejection (`false`) from error (throws). Rejection does not consume retry budget.

---

## Verification (after all steps complete)

1. `grep_search` for `LoggingDeadLetterQueue|AsyncFilterRetryManager|isIntentionalTest|isTestScenario|testingConfig|\.otherwise\(|executeBlocking` across all touched files — must return zero matches.
2. `grep_search` for `INSERT INTO dead_letter_queue|UPDATE outbox|INTO outbox` (unqualified) in `OutboxConsumer.java` — must return zero matches after Step 7 fix.
3. All TC-1 through TC-7c test cases pass.
4. `mvn clean test -Pall-tests 2>&1 | Tee-Object -FilePath logs\all-tests-YYYYMMDD.txt`
5. Confirm `Tests run: 0` does not appear for any test class that should have executed.

---

## Step 7 — Multi-tenant DLQ schema isolation

### Problem

`OutboxConsumer.handleMessageFailureWithRetry()` writes to the `dead_letter_queue` table using unqualified SQL:

```java
String insertSql = """
    INSERT INTO dead_letter_queue (original_table, original_id, topic, payload, ...)
    VALUES ('outbox', $1, $2, ...)
    """;
...
String updateSql = "UPDATE outbox SET status = 'DEAD_LETTER', error_message = $1 WHERE id = $2";
```

No schema prefix. The connection's `search_path` determines which schema is used, which may not be the correct tenant schema. For a tenant configured with `peegeeq.database.schema = tenant_abc`, the DLQ write goes to whatever schema is first on the connection's `search_path` — potentially `public` or a different tenant's schema.

The same `resetFilteredMessageToPending()` SQL (`UPDATE outbox SET status = 'PENDING' ...`) and the `SELECT` that precedes the DLQ insert also lack schema qualification.

By contrast, `OutboxFactory` already uses `configuration.getDatabaseConfig().getSchema()` with `quoteIdentifier()` and `.formatted()` for its schema-qualified queries — the correct pattern is established.

### Required fix in `OutboxConsumer`

`OutboxConsumer` must receive the schema name at construction time (via `PeeGeeQConfiguration` or a dedicated `String schemaName` field) and use it in all SQL that references `outbox` or `dead_letter_queue`:

```java
// Pattern already used in OutboxFactory:
String schema = configuration.getDatabaseConfig().getSchema();  // e.g. "tenant_abc"
String insertSql = """
    INSERT INTO %s.dead_letter_queue (original_table, original_id, topic, payload, ...)
    VALUES ('outbox', $1, $2, ...)
    """.formatted(quoteIdentifier(schema));
String updateSql = "UPDATE %s.outbox SET status = 'DEAD_LETTER', error_message = $1 WHERE id = $2"
    .formatted(quoteIdentifier(schema));
```

All SQL strings in `OutboxConsumer` that reference `outbox` or `dead_letter_queue` must be audited and schema-qualified.

### Required test cases for Step 7

#### TC-7a — DLQ write goes to the correct tenant schema (not `public`)

**Precondition:** Two tenants configured: `tenant_a` (schema `tenant_a`) and `tenant_b` (schema `tenant_b`). Each has its own `OutboxConsumer` with a filter that always throws. `maxRetries = 1`.  
**Action:** Insert one message per tenant, run enough poll cycles to exhaust retries.  
**Assert:**
- `SELECT COUNT(*) FROM tenant_a.dead_letter_queue WHERE topic = 'tenant-a-topic'` returns `1`
- `SELECT COUNT(*) FROM tenant_b.dead_letter_queue WHERE topic = 'tenant-b-topic'` returns `1`
- `SELECT COUNT(*) FROM tenant_a.dead_letter_queue WHERE topic = 'tenant-b-topic'` returns `0` (no cross-tenant bleed)
- `SELECT COUNT(*) FROM public.dead_letter_queue` returns `0` (no writes to `public`)

#### TC-7b — `outbox` status update goes to the correct tenant schema

**Precondition:** Same two-tenant setup as TC-7a.  
**Assert:** After retries exhausted, `SELECT status FROM tenant_a.outbox WHERE ...` returns `DEAD_LETTER`. The `tenant_b.outbox` row is unaffected and `tenant_a.dead_letter_queue` contains only tenant_a messages.

#### TC-7c — `resetFilteredMessageToPending` (circuit breaker path) also uses correct schema

**Precondition:** Tenant with schema `tenant_c`. Circuit breaker OPEN. One message in `tenant_c.outbox`.  
**Assert:** After poll cycle, `SELECT status FROM tenant_c.outbox WHERE id = ?` returns `PENDING` (not from `public.outbox`).
