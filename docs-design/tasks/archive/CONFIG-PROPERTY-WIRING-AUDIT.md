> **ARCHIVED 2026-08-29:** Historical evidence only. Current tasks and status are maintained exclusively in [the consolidated register](../tasks.md).

# Configuration Property Wiring Audit

**Date:** 2026-05-28
**Scope:** All core modules — `peegeeq-native`, `peegeeq-db`, `peegeeq-outbox`, `peegeeq-rest`, `peegeeq-runtime`
**Status:** ACTIVE — tables reconciled against revision `32ab0371` and the current worktree on 2026-08-29

### Reconciled findings

- Native queue polling, batch size, consumer threads, visibility timeout, and retry limits
  are wired into `PgNativeQueueConsumer`; existing non-default tests cover the principal paths.
- The canonical global consumer-thread key is `peegeeq.consumer.threads`. The older
  `peegeeq.queue.consumer-threads` spelling is not parsed; one outbox test and one bitemporal
  test resource still contain that ineffective key and must be corrected in a code phase.
- Outbox polling, batch size, retry limits, and consumer threads are wired. Consumer threads
  atomically bound claimed handler/terminal-persistence pipelines; same-group rows are serialized
  within one consumer instance while different groups can execute concurrently.
- Outbox uses the canonical `peegeeq.queue.*` settings; there is no separate
  `peegeeq.outbox.*` property namespace. Outbox has no native-style visibility lock, so an
  `outbox.visibility-timeout` setting is not applicable. Stale PROCESSING recovery is governed
  separately by `peegeeq.queue.recovery.processing-timeout`.
- Pool maximum size, connection timeout, and idle timeout are carried into `PoolOptions`.
- The unsupported pool minimum-size property was removed from configuration validation,
  shipped profiles, adapters, examples, and live documentation. Focused contracts assert that
  it is not forwarded or exposed.
- Shipped resource profiles now use the canonical `connection-timeout-ms` and
  `idle-timeout-ms` keys, with parameterized binding coverage for every bundled profile.
- `SystemInfoCollector` reads diagnostic values only from an explicitly supplied configuration.
  Its no-configuration path reports that configuration is not supplied instead of consulting
  process-wide PeeGeeQ properties. A focused two-instance plus ambient-poison contract passed
  8/8 with the rest of `SystemInfoCollectorTest` on 2026-08-29.
- Core metrics enablement is parsed and gates registry binding and periodic metrics collection.
  Source wiring is confirmed; a focused behavior test with `peegeeq.metrics.enabled=false`
  remains to be added. The `metrics.jvm.enabled` and `metrics.database.enabled` flags are parsed
  into `MetricsConfig` but have no production consumer.
- Circuit-breaker enablement, failure-rate threshold, wait duration, and ring-buffer size are
  wired. However, six additional properties advertised in shipped profiles or the public guide
  are not parsed: `minimum-number-of-calls`, `sliding-window-size`,
  `wait-duration-in-open-state`, both `slow-call-*` properties, and
  `permitted-calls-in-half-open-state`. The runtime instead maps `failure-threshold` to
  Resilience4j's minimum-number-of-calls and hardcodes half-open permitted calls to `3`.
- Health configuration is split across incompatible namespaces. `PeeGeeQManager` constructs and
  starts `HealthCheckManager` from `peegeeq.health-check.*`; its parsed `enabled` flag is not
  consulted. `PgQueueConfiguration` separately exposes `peegeeq.health.enabled` and
  `peegeeq.health.check-interval`. Shipped profiles primarily set the latter namespace, so their
  interval/timeout values do not configure the manager health-check timer.
- `BackpressureManager` was deleted on 2026-08-09 because it guarded no operation, but shipped
  profiles and the public guide still advertise `peegeeq.backpressure.*` settings. Those keys
  currently have no production effect.
- A literal-key scan of shipped profiles against production Java found additional profile-only
  settings with no direct consumer, including migration, maintenance, performance-tuning,
  bitemporal-feature, queue prefetch/buffer, and extended metrics keys. This scan is a discovery
  signal, not proof against every dynamically constructed key; each family still requires a
  deliberate source/test classification before removal or implementation.

The audit remains open for the shipped-profile/runtime reconciliation above, the circuit-breaker
and health contracts, the metrics behavior/sub-flags, the native cleanup-interval decision, and
the two stale consumer-thread test keys. Static source inspection establishes candidate wiring
and gaps but is not represented here as runtime verification.

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
| `peegeeq.queue.visibility-timeout` | `PeeGeeQConfiguration` | lock SQL and handler-settlement timeout | **OK — fixed 2026-05-28; non-default regression exists** |
| `peegeeq.queue.polling-interval` | `PeeGeeQConfiguration` | `startPolling()` timer | **OK — source path and non-default coverage confirmed** |
| `peegeeq.queue.batch-size` | `PeeGeeQConfiguration` | claim limit/capacity | **OK — source path and non-default coverage confirmed** |
| `peegeeq.consumer.threads` | `PeeGeeQConfiguration` | atomic processing-capacity admission | **OK — canonical key; non-default capacity coverage exists** |
| `peegeeq.queue.max-retries` | `PeeGeeQConfiguration` | processing-failure retry check | **OK — source path and retry coverage confirmed** |
| Cleanup timer interval | N/A | `startPolling()` — hardcoded `10_000` ms | **DESIGN DECISION — fixed cadence is not currently a configuration bug** |

### `peegeeq-native` / `PgNativeQueueFactory` / `ConsumerConfig`

| Property | Where parsed | Where used | Status |
|----------|-------------|------------|--------|
| `ConsumerConfig.pollingInterval` | `ConsumerConfig` | `startPolling()` override | **OK — per-consumer > global > default** |
| `ConsumerConfig.batchSize` | `ConsumerConfig` | claim limit/capacity override | **OK — per-consumer > global > default** |
| `ConsumerConfig.consumerThreads` | `ConsumerConfig` | processing-capacity override | **OK — per-consumer > global > default** |

### `peegeeq-native` / `PgNativeConsumerGroup`

| Property | Where parsed | Where used | Status |
|----------|-------------|------------|--------|
| Group-level visibility/polling config | N/A | Consumer groups do not define a separate property layer | **NOT APPLICABLE — member consumers use their supplied/global configuration** |

### `peegeeq-outbox` / `OutboxConsumer`

| Property | Where parsed | Where used | Status |
|----------|-------------|------------|--------|
| `peegeeq.queue.polling-interval` / `OutboxConsumerConfig.pollingInterval` | global queue config / per-consumer builder | polling timer | **OK — per-consumer > global > local fallback** |
| `peegeeq.queue.batch-size` / `OutboxConsumerConfig.batchSize` | global queue config / per-consumer builder | claim limit/capacity | **OK — per-consumer > global > local fallback** |
| `peegeeq.queue.max-retries` / `OutboxConsumerConfig.maxRetries` | global queue config / per-consumer builder | retry exhaustion | **OK — per-consumer > global > database/local fallback** |
| `peegeeq.consumer.threads` / `OutboxConsumerConfig.consumerThreads` | global queue config / per-consumer builder | atomic claim admission and handler pipeline capacity | **FIXED — 2026-08-29; real-PostgreSQL contracts 3/3** |
| Outbox visibility timeout / lock duration | N/A | Outbox claims use PROCESSING state, not native `lock_until` visibility locks | **NOT APPLICABLE — stale recovery is a separate queue recovery concern** |

### `peegeeq-db` / Pool configuration

| Property | Where parsed | Where used | Status |
|----------|-------------|------------|--------|
| `peegeeq.database.pool.max-size` | `PeeGeeQConfiguration` | `PgPoolAdapter` pool options | **OK — focused binding coverage** |
| `peegeeq.database.pool.connection-timeout-ms` | `PeeGeeQConfiguration` | `PgPoolAdapter` pool options | **OK — canonicalized profiles; 28/28 binding contract** |
| `peegeeq.database.pool.idle-timeout-ms` | `PeeGeeQConfiguration` | `PgPoolAdapter` pool options | **OK — canonicalized profiles; 28/28 binding contract** |

### `peegeeq-db` / Circuit breaker / backpressure

| Property | Where parsed | Where used | Status |
|----------|-------------|------------|--------|
| `peegeeq.circuit-breaker.enabled` | `PeeGeeQConfiguration` | manager construction/disabled path | **OK — source wiring confirmed** |
| `peegeeq.circuit-breaker.failure-rate-threshold` | `PeeGeeQConfiguration` | Resilience4j `failureRateThreshold` | **OK — source wiring confirmed** |
| `peegeeq.circuit-breaker.wait-duration` | `PeeGeeQConfiguration` | Resilience4j `waitDurationInOpenState` | **OK — source wiring confirmed** |
| `peegeeq.circuit-breaker.ring-buffer-size` | `PeeGeeQConfiguration` | Resilience4j `slidingWindowSize` | **OK — source wiring confirmed** |
| `peegeeq.circuit-breaker.failure-threshold` | `PeeGeeQConfiguration` | Resilience4j `minimumNumberOfCalls` | **AMBIGUOUS — wired, but name/documentation imply consecutive failures** |
| `minimum-number-of-calls`, `sliding-window-size`, `wait-duration-in-open-state`, `slow-call-*`, `permitted-calls-in-half-open-state` | Shipped profiles, a base integration fixture, and/or public guide | Not consumed; half-open calls hardcoded to `3` | **BROKEN WIRING — decide canonical names/aliases and precedence** |

### `peegeeq-db` / Metrics

| Property | Where parsed | Where used | Status |
|----------|-------------|------------|--------|
| `peegeeq.metrics.enabled` | `PeeGeeQConfiguration` | registry binding and periodic collection guards | **WIRED — focused `false` behavior contract still required** |
| `peegeeq.metrics.jvm.enabled` | `PeeGeeQConfiguration` | No getter consumer found | **BROKEN WIRING — parsed but ineffective** |
| `peegeeq.metrics.database.enabled` | `PeeGeeQConfiguration` | No getter consumer found | **BROKEN WIRING — parsed but ineffective** |

### `peegeeq-db` / Health checks

| Property | Where parsed | Where used | Status |
|----------|-------------|------------|--------|
| `peegeeq.health-check.interval` | `PeeGeeQConfiguration.getHealthCheckConfig()` | `HealthCheckManager` timer | **WIRED** |
| `peegeeq.health-check.timeout` | `PeeGeeQConfiguration.getHealthCheckConfig()` | health query timeout | **WIRED** |
| `peegeeq.health-check.queue-checks-enabled` | `PeeGeeQConfiguration.getHealthCheckConfig()` | queue-health registration | **WIRED** |
| `peegeeq.health-check.enabled` | `PeeGeeQConfiguration.getHealthCheckConfig()` | Not consulted by manager startup | **BROKEN WIRING — manager always starts health checks** |
| `peegeeq.health.enabled` / `peegeeq.health.check-interval` | direct reads in `PgQueueConfiguration` | API adapter only, not manager health timer | **SPLIT CONTRACT — reconcile with `health-check.*`** |
| `peegeeq.health.timeout`, `failure-threshold`, `recovery-threshold` | Shipped profiles/public guide | No production consumer found | **BROKEN/UNSUPPORTED** |

### Shipped-profile families requiring classification

| Family | Current source finding | Required decision |
|---|---|---|
| `peegeeq.backpressure.*` | Manager implementation deleted; no production consumer | Remove profiles/docs or implement a real guarded operation |
| `peegeeq.migration.*` | No production Java consumer found | Identify external consumer or remove unsupported claims |
| `peegeeq.maintenance.*` | No production Java consumer found | Identify scheduler/consumer or remove unsupported claims |
| `peegeeq.performance.async.*`, `performance.batch.*`, `performance.monitoring.*` | No production Java consumer found | Treat as test/profile metadata or implement explicitly |
| `peegeeq.bitemporal.*.enabled` | No production Java consumer found | Remove feature-toggle claims or wire them |
| Queue prefetch/concurrent-consumer/buffer/retention keys | No production Java consumer found | Remove profile metadata or define runtime semantics |
| Extended metrics collection/export/detail keys | No production Java consumer found | Separate monitoring-process settings from core runtime settings |

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
- Do not document an `peegeeq.outbox.*` namespace unless one is deliberately added; current
  outbox consumers use the global `peegeeq.queue.*` keys plus `OutboxConsumerConfig` overrides.
- Do not treat use of `peegeeq.queue.consumer-threads` as evidence. The parsed global key is
  `peegeeq.consumer.threads`.
- Do not fix anything in bulk. One class, one property, one test at a time.
