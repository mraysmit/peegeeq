# Configuration Property Wiring Audit

**Date:** 2026-05-28
**Scope:** All core modules — `peegeeq-native`, `peegeeq-db`, `peegeeq-outbox`, `peegeeq-rest`, `peegeeq-runtime`
**Status:** Pending

---

## Background

During investigation of `testNativeQueueVisibilityTimeout`, `PgNativeQueueConsumer.processAvailableMessages()` was found to be hardcoding `30` seconds as the lock duration in the SQL `make_interval(secs => $3)` parameter, completely ignoring the configured `peegeeq.queue.visibility-timeout` property. The configuration was being read correctly by `PeeGeeQConfiguration` (line 313) and stored in `QueueConfig.visibilityTimeout`, but the consumer never called `getVisibilityTimeout()` when building the lock SQL.

This was discovered only because a test explicitly configured `PT5S` and expected behaviour that depended on the value being honoured. Tests using the coincidental default of `PT30S` masked the bug entirely.

This pattern — configuration parsed and stored, but silently ignored in favour of a hardcoded constant — is likely present elsewhere. The full extent is unknown.

---

## Known Instance (Fixed)

| Module | Class | Property | Hardcoded value | Fix applied |
|--------|-------|----------|-----------------|-------------|
| `peegeeq-native` | `PgNativeQueueConsumer.processAvailableMessages()` | `peegeeq.queue.visibility-timeout` | `30` (seconds) | Yes — 2026-05-28 |

---

## Objective

Systematically audit every `peegeeq.queue.*`, `peegeeq.database.*`, `peegeeq.outbox.*`, and `peegeeq.metrics.*` property across all core modules to verify:

1. The property is parsed from `PeeGeeQConfiguration` (or equivalent).
2. The parsed value is actually used at the call site where it matters (SQL, timer interval, pool size, retry count, etc.).
3. No hardcoded fallback silently overrides the configured value in normal (non-null) paths.
4. Test coverage exercises a non-default value and would catch regression if the wiring broke.

---

## Scope — Properties to Audit

### `peegeeq-native` / `PgNativeQueueConsumer`

| Property | Where parsed | Where used | Status |
|----------|-------------|------------|--------|
| `peegeeq.queue.visibility-timeout` | `PeeGeeQConfiguration` L313 | `processAvailableMessages()` lock SQL `$3` | Fixed |
| `peegeeq.queue.polling-interval` | `PeeGeeQConfiguration` | `startPolling()` `setPeriodic` interval | Verify |
| `peegeeq.queue.batch-size` | `PeeGeeQConfiguration` | `processAvailableMessages()` `effectiveBatch` | Verify |
| `peegeeq.queue.consumer-threads` | `PeeGeeQConfiguration` | `maxThreads` in capacity check | Verify |
| `peegeeq.queue.max-retries` | `PeeGeeQConfiguration` | `handleProcessingFailure()` retry check | Verify |
| Cleanup timer interval | N/A | `startPolling()` — hardcoded `10_000` ms | Investigate: should this be configurable? |

### `peegeeq-native` / `PgNativeQueueFactory` / `ConsumerConfig`

| Property | Where parsed | Where used | Status |
|----------|-------------|------------|--------|
| `ConsumerConfig.pollingInterval` | `ConsumerConfig` | `startPolling()` override | Verify precedence over global config |
| `ConsumerConfig.batchSize` | `ConsumerConfig` | `processAvailableMessages()` override | Verify precedence |
| `ConsumerConfig.consumerThreads` | `ConsumerConfig` | `maxThreads` override | Verify precedence |

### `peegeeq-native` / `PgNativeConsumerGroup`

| Property | Where parsed | Where used | Status |
|----------|-------------|------------|--------|
| Group-level visibility/polling config | Unknown | Unknown | Investigate |

### `peegeeq-outbox` / `OutboxConsumer`

| Property | Where parsed | Where used | Status |
|----------|-------------|------------|--------|
| `peegeeq.outbox.polling-interval` | Unknown | `setPeriodic` interval | Verify |
| `peegeeq.outbox.batch-size` | Unknown | batch claim SQL `LIMIT` | Verify |
| `peegeeq.outbox.max-retries` | Unknown | retry exhaustion check | Verify |
| `peegeeq.outbox.visibility-timeout` or lock duration | Unknown | lock SQL parameter | **High priority — same class of bug as native** |

### `peegeeq-db` / Pool configuration

| Property | Where parsed | Where used | Status |
|----------|-------------|------------|--------|
| `peegeeq.database.pool.min-size` | `PeeGeeQConfiguration` | `PgPoolAdapter` pool options | Verify |
| `peegeeq.database.pool.max-size` | `PeeGeeQConfiguration` | `PgPoolAdapter` pool options | Verify |
| `peegeeq.database.pool.connection-timeout-ms` | `PeeGeeQConfiguration` | `PgPoolAdapter` pool options | Verify |
| `peegeeq.database.pool.idle-timeout-ms` | `PeeGeeQConfiguration` | `PgPoolAdapter` pool options | Verify |

### `peegeeq-db` / Circuit breaker / backpressure

| Property | Where parsed | Where used | Status |
|----------|-------------|------------|--------|
| `peegeeq.circuit-breaker.enabled` | `PeeGeeQConfiguration` | `CircuitBreakerManager` | Verify |
| `peegeeq.circuit-breaker.*` thresholds | `PeeGeeQConfiguration` | `CircuitBreakerManager` logic | Verify |

### `peegeeq-db` / Metrics

| Property | Where parsed | Where used | Status |
|----------|-------------|------------|--------|
| `peegeeq.metrics.enabled` | `PeeGeeQConfiguration` | metrics recording guards | Verify |

---

## Investigation Method

For each property in scope:

1. Find where it is parsed in `PeeGeeQConfiguration` (or equivalent config class). Note line number.
2. Grep the production class that is supposed to consume it for the getter call.
3. If the getter is not called — or called but the result is not passed to the relevant SQL/timer/pool — flag as **broken wiring**.
4. If a hardcoded literal exists at the same call site — flag as **hardcoded override**.
5. Check whether any test sets a non-default value for the property AND asserts behaviour that would fail if the value were ignored.

---

## Deliverables

1. Updated version of the table above with confirmed **OK**, **broken wiring**, or **hardcoded override** for each row.
2. Fix each broken wiring or hardcoded override found, one class at a time.
3. For each fix, add or update a test that sets a non-default value and would catch future regression.
4. Run `mvn clean test -pl <affected-module> -Dtest=<affected-test> -Pintegration-tests` after each fix.

---

## Notes

- The cleanup timer in `PgNativeQueueConsumer.startPolling()` is hardcoded to `10_000` ms. Whether this should be driven by config is a design question, not a bug. Decide and document.
- The `ConsumerConfig` per-consumer overrides are intentional. The audit should confirm the override precedence (ConsumerConfig > PeeGeeQConfiguration > hardcoded default) is applied consistently.
- Do not fix anything in bulk. One class, one property, one test at a time.
