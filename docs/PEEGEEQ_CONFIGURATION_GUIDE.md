# PeeGeeQ Configuration Guide

**Authoritative runtime-property reference**
**Reconciled:** August 29, 2026

## Scope and contract

This guide covers the `peegeeq.*` properties loaded by
`dev.mars.peegeeq.db.config.PeeGeeQConfiguration` and the small set of identically named
properties with an explicit production owner outside that loader.

The executable inventory is
`ShippedConfigurationPropertyContractTest`. The 11 profiles in
`peegeeq-db/src/main/resources` may contain only keys in that inventory. A profile entry is
therefore evidence that a key has an owner, but behavioral support is established by the
production call site and a non-default test—not by the file alone.

Statuses in this guide mean:

- **OK** — parsed, consumed by production code, and covered with a non-default contract.
- **EXTERNAL** — deliberately owned by a named production component outside the core
  `PeeGeeQConfiguration` adapter path.
- **COMPATIBILITY ALIAS** — still readable but never shipped. Source-layer precedence is
  applied first; when both spellings occur in one layer, the canonical spelling wins.
- **UNSUPPORTED** — no effective production behavior; not shipped and not a supported control.

## Loading and precedence

A profile is always explicit. The supported constructors are:

```java
new PeeGeeQConfiguration(profile, overrides);
new PeeGeeQConfiguration(
    profile, host, port, database, username, password, schema);
```

There is no no-argument or profile-only constructor and no ambient
`peegeeq.profile`/`PEEGEEQ_PROFILE` selection.

Values merge in this order, from lowest to highest precedence:

1. `peegeeq-default.properties`
2. `peegeeq-<profile>.properties` when the profile is not `default`
3. `PEEGEEQ_*` environment variables
4. the explicit `Properties overrides` object, or the explicit database arguments

JVM `-Dpeegeeq.*` system properties are not swept by `PeeGeeQConfiguration`. If an
application intentionally wants JVM arguments, copy them into an isolated `Properties`
object at its bootstrap boundary and pass that object to the two-argument constructor.

Known environment-variable names map underscores to the existing canonical key, including
hyphenated segments:

```text
PEEGEEQ_DATABASE_HOST                 -> peegeeq.database.host
PEEGEEQ_DATABASE_PROXY_HOST           -> peegeeq.database.proxy.host
PEEGEEQ_DATABASE_POOL_MAX_SIZE        -> peegeeq.database.pool.max-size
PEEGEEQ_CIRCUIT_BREAKER_ENABLED       -> peegeeq.circuit-breaker.enabled
```

### Placeholder resolution

After merging, values may contain `${VAR}` or `${VAR:default}`:

- `${VAR}` uses the environment value and fails construction when `VAR` is absent.
- `${VAR:default}` uses the environment value or the literal default.
- `${VAR:}` resolves to an empty string when the variable is absent.

The production profile uses required placeholders for all database coordinates. Missing
`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, or `DB_SCHEMA`
therefore fails fast unless an explicit override supplies the property first.

## Bundled profiles

Each non-default profile overrides the default baseline.

| Profile | Intended use |
|---|---|
| `default` | Complete local baseline and the authoritative active-key inventory |
| `development` | Small development pool; circuit breaker disabled |
| `production` | Required environment-backed database coordinates and TLS |
| `reliable` | Conservative retry, visibility, and recovery timings |
| `low-latency` | Batch size 1 and 10 ms empty-queue polling |
| `high-performance` | Large batches, eight consumer threads, pipelining 32 |
| `high-throughput` | Large pool and batches |
| `vertx5-optimized` | Vert.x tuning metadata and pipelining 1024 |
| `extreme-performance` | High-resource benchmark profile |
| `bitemporal-optimized` | Bitemporal production-owner tuning |
| `parallel-test` | Parallel-consumer test profile |

Bundled profiles contain no migration, backpressure, JVM-metrics, generic performance-toggle,
queue-prefetch, or invented outbox property namespace.

## Authoritative active inventory

### Database and pool

| Property | Status | Parser / production owner | Non-default evidence |
|---|---|---|---|
| `peegeeq.database.host` | OK | `PeeGeeQConfiguration -> PgConnectionConfig -> PgConnectionManager` | configuration/database integration tests |
| `peegeeq.database.port` | OK | same connection path | configuration/database integration tests |
| `peegeeq.database.proxy.host` | OK | optional effective endpoint for pooled and dedicated LISTEN/NOTIFY connections | configuration contract and HAProxy notification failover test |
| `peegeeq.database.proxy.port` | OK | optional effective endpoint for pooled and dedicated LISTEN/NOTIFY connections | configuration contract and HAProxy notification failover test |
| `peegeeq.database.name` | OK | same connection path | configuration/database integration tests |
| `peegeeq.database.username` | OK | same connection path | configuration/database integration tests |
| `peegeeq.database.password` | OK | same connection path | configuration/database integration tests |
| `peegeeq.database.schema` | OK | connection `search_path` and schema-scoped SQL | schema-isolation integration tests |
| `peegeeq.database.ssl.enabled` | OK | `PgConnectionConfig -> PgConnectOptions` | connection-config tests |
| `peegeeq.database.pool.max-size` | OK | `PgPoolConfig -> PgPoolAdapter` | `PgPoolConfigPropertyBindingTest` |
| `peegeeq.database.pool.max-wait-queue-size` | OK | `PgPoolConfig -> PgPoolAdapter` | `PgPoolConfigPropertyBindingTest` |
| `peegeeq.database.pool.connection-timeout-ms` | OK | `PgPoolConfig -> PgPoolAdapter` | exact binding across all 11 profiles |
| `peegeeq.database.pool.idle-timeout-ms` | OK | `PgPoolConfig -> PgPoolAdapter` | exact binding across all 11 profiles |
| `peegeeq.database.pool.shared` | OK | `PgPoolConfig -> PgPoolAdapter` | `PgPoolConfigPropertyBindingTest` |
| `peegeeq.database.pool.wait-queue-multiplier` | EXTERNAL | bitemporal optimized-pool construction | bitemporal pool tests |
| `peegeeq.database.pipelining.enabled` | EXTERNAL | `VertxPerformanceOptimizer` | optimizer tests |
| `peegeeq.database.pipelining.limit` | EXTERNAL | optimizer and bitemporal pool construction | optimizer/bitemporal tests |
| `peegeeq.database.event.loop.size` | EXTERNAL | `VertxPerformanceOptimizer` and bitemporal path | optimizer tests |
| `peegeeq.database.worker.pool.size` | EXTERNAL | `VertxPerformanceOptimizer` | optimizer tests |
| `peegeeq.database.use.event.bus.distribution` | EXTERNAL | `PgBiTemporalEventStore` | bitemporal tests |
| `peegeeq.verticle.instances` | EXTERNAL | `VertxPerformanceOptimizer` | optimizer tests |

The EXTERNAL keys are not applied by `PeeGeeQManager` merely because they appear in a
profile. Their named owners must receive the corresponding configuration object.

The proxy host and port are optional and resolve independently. A blank proxy host inherits
`peegeeq.database.host`; a blank proxy port inherits `peegeeq.database.port`. When either proxy
value is nonblank, the resulting effective `PgConnectionConfig` is shared by the Vert.x pool
and `PgDatabaseService.getConnectOptions()`, so long-lived LISTEN/NOTIFY connections use the
same endpoint as ordinary database operations.

### Queue and background work

| Property | Status | Production use | Non-default evidence |
|---|---|---|---|
| `peegeeq.queue.max-retries` | OK | native and outbox retry exhaustion | retry integration tests |
| `peegeeq.queue.visibility-timeout` | OK | native lock duration and settlement timeout | native non-default visibility test |
| `peegeeq.queue.batch-size` | OK | native/outbox claim capacity | consumer capacity tests |
| `peegeeq.queue.polling-interval` | OK | native/outbox polling timer | polling contract tests |
| `peegeeq.consumer.threads` | OK | native/outbox atomic admission capacity | non-default capacity tests |
| `peegeeq.queue.recovery.enabled` | OK | stuck-message recovery manager and timer | manager lifecycle tests |
| `peegeeq.queue.recovery.processing-timeout` | OK | stale PROCESSING recovery cutoff | recovery integration tests |
| `peegeeq.queue.recovery.check-interval` | OK | manager recovery timer | timer/lifecycle tests |
| `peegeeq.queue.dead-consumer-detection.enabled` | OK | manager job startup | lifecycle tests |
| `peegeeq.queue.dead-consumer-detection.interval` | OK | dead-consumer timer | lifecycle tests |
| `peegeeq.queue.consumer-group-retry.enabled` | OK | manager job startup | lifecycle tests |
| `peegeeq.queue.consumer-group-retry.interval` | OK | consumer-group retry timer | lifecycle tests |

Consumer-specific configuration has deliberate precedence:

```text
per-consumer ConsumerConfig / OutboxConsumerConfig
    > global PeeGeeQConfiguration queue value
    > component-local default
```

Outbox uses these canonical `peegeeq.queue.*` settings. There is no
`peegeeq.outbox.*` runtime-property namespace. Outbox PROCESSING recovery is governed by
`peegeeq.queue.recovery.processing-timeout`; it does not use the native queue's visibility
lock.

The native expired-lock cleanup cadence remains an intentional internal constant of 10 seconds.
It is not a public property because it is housekeeping for expired locks, not a workload tuning
control. The polling, lock duration, capacity, and retry controls remain configurable.

### Metrics

| Property | Status | Production use | Non-default evidence |
|---|---|---|---|
| `peegeeq.metrics.enabled` | OK | gates PeeGeeQ/notice/Resilience4j registry binding and all periodic metric samplers | `PeeGeeQManagerIntegrationTest.disabledMetricsPreventRegistryBindingAndPeriodicCollection` |
| `peegeeq.metrics.depth-cache-interval` | OK | queue-depth refresh timer | timer guard tests |
| `peegeeq.metrics.instance-id` | OK | metric tags in `PeeGeeQMetrics` | metrics tests |

When `peegeeq.metrics.enabled=false`, PeeGeeQ binds no PeeGeeQ notice meters, no core
PeeGeeQ meters, and no Resilience4j circuit-breaker meters to the supplied registry; it also
starts no depth, event-loop-lag, or pool-acquisition sampler.

`peegeeq.metrics.jvm.enabled` and `peegeeq.metrics.database.enabled` were removed from
`MetricsConfig`: neither had a consumer. Extended persistence/export/detail flags are also
unsupported; applications should bind their chosen Micrometer JVM/database binders explicitly.

### Circuit breaker

The canonical namespace is:

| Property | Default | Resilience4j setting |
|---|---:|---|
| `peegeeq.circuit-breaker.enabled` | `true` | disabled path returns direct futures |
| `peegeeq.circuit-breaker.minimum-number-of-calls` | `5` | `minimumNumberOfCalls` |
| `peegeeq.circuit-breaker.wait-duration-in-open-state` | `PT1M` | `waitDurationInOpenState` |
| `peegeeq.circuit-breaker.sliding-window-size` | `100` | `slidingWindowSize` |
| `peegeeq.circuit-breaker.failure-rate-threshold` | `50.0` | `failureRateThreshold` |
| `peegeeq.circuit-breaker.slow-call-rate-threshold` | `100.0` | `slowCallRateThreshold` |
| `peegeeq.circuit-breaker.slow-call-duration-threshold` | `PT60S` | `slowCallDurationThreshold` |
| `peegeeq.circuit-breaker.permitted-calls-in-half-open-state` | `3` | `permittedNumberOfCallsInHalfOpenState` |

All eight settings are OK and covered by
`PeeGeeQConfigurationTest` and `CircuitBreakerManagerTest`.

Historical aliases remain readable as compatibility spellings:

| Compatibility alias | Canonical key |
|---|---|
| `peegeeq.circuit-breaker.failure-threshold` | `minimum-number-of-calls` |
| `peegeeq.circuit-breaker.wait-duration` | `wait-duration-in-open-state` |
| `peegeeq.circuit-breaker.ring-buffer-size` | `sliding-window-size` |

Source-layer precedence is applied before alias normalization, so an alias in a higher layer
overrides a canonical value in a lower layer. If both spellings are present in the same layer,
the canonical key wins. Bundled profiles use only canonical spellings. Historical `timeout` and
`reset-timeout` keys are unsupported and are not aliases.

### Health

The canonical namespace is:

| Property | Default | Production use |
|---|---:|---|
| `peegeeq.health.enabled` | `true` | gates `HealthCheckManager.start()` |
| `peegeeq.health.queue-checks-enabled` | `true` | gates queue-health registration |
| `peegeeq.health.check-interval` | `PT30S` | health timer interval |
| `peegeeq.health.timeout` | `PT5S` | health query timeout |

These values are shared by `PeeGeeQManager` and `PgQueueConfiguration`.
`PeeGeeQManagerIntegrationTest.disabledHealthConfigurationDoesNotStartHealthChecks` proves
the disabled path, and configuration tests cover non-default interval and timeout values.

The four historical `peegeeq.health-check.*` spellings remain compatibility aliases. An alias in
a higher source layer overrides a canonical value from a lower layer; canonical
`peegeeq.health.*` values win when both spellings occur in the same layer. The old
`health.failure-threshold` and `health.recovery-threshold` keys are unsupported.

### PostgreSQL notices

| Property | Default | Production owner |
|---|---:|---|
| `peegeeq.notices.info.enabled` | `true` | `NoticeHandlerConfig` |
| `peegeeq.notices.info.level` | `INFO` | `NoticeHandlerConfig` |
| `peegeeq.notices.other.enabled` | `false` | `NoticeHandlerConfig` |
| `peegeeq.notices.other.level` | `DEBUG` | `NoticeHandlerConfig` |
| `peegeeq.notices.metrics.enabled` | `true` | `PgClientFactory` notice metrics, also gated by core metrics enablement |

## Unsupported and removed profile keys

The following keys were inventoried and deliberately removed from bundled profiles. They must
not be presented as live deployment controls.

| Classification | Exact keys |
|---|---|
| UNSUPPORTED pool/database aliases | `peegeeq.database.database`, `database.user`, `database.ssl-mode`, `database.batch.size`, `database.pool.name`, `pool.auto-commit`, `pool.min-idle`, `pool.max-lifetime`, `pool.max-lifetime-ms` |
| EXTERNAL/TEST metadata, not a core runtime control | `peegeeq.database.url` |
| UNSUPPORTED queue parser-only values | `peegeeq.queue.dead-letter.enabled`, `queue.priority.default`, `queue.prefetch-count`, `queue.concurrent-consumers`, `queue.buffer-size`, `queue.retention-period` |
| UNSUPPORTED stale spelling | `peegeeq.queue.consumer-threads` (use `peegeeq.consumer.threads`) |
| UNSUPPORTED dead-letter family | `peegeeq.dead-letter.max-retries`, `dead-letter.retry-delay`, `dead-letter.retention-period` |
| UNSUPPORTED metrics | `peegeeq.metrics.reporting-interval`, `metrics.jvm.enabled`, `metrics.database.enabled`, `metrics.collection.enabled`, `metrics.collection.async-save`, `metrics.collection.sampling-rate`, `metrics.collection-interval`, `metrics.retention-period`, `metrics.bitemporal.enabled`, `metrics.detailed.enabled` |
| UNSUPPORTED health | `peegeeq.health.failure-threshold`, `health.recovery-threshold` |
| UNSUPPORTED circuit breaker | `peegeeq.circuit-breaker.timeout`, `circuit-breaker.reset-timeout` |
| UNSUPPORTED backpressure | `peegeeq.backpressure.enabled`, `max-queue-size`, `high-watermark`, `low-watermark`, `check-interval`, `max-concurrent-operations`, `timeout` |
| UNSUPPORTED migration | `peegeeq.migration.enabled`, `auto-migrate`, `validate-checksums`, `validate-on-migrate` |
| UNSUPPORTED maintenance | `peegeeq.maintenance.cleanup-interval`, `maintenance.retention-period` |
| UNSUPPORTED generic performance toggles | `peegeeq.performance.async.enabled`, `async.thread-pool-size`, `batch.enabled`, `batch.max-size`, `batch.timeout`, `monitoring.enabled`, `monitoring.interval`, `thresholds.query.warning`, `thresholds.query.critical`, `thresholds.connection.warning`, `thresholds.connection.critical` |
| UNSUPPORTED bitemporal feature toggles | `peegeeq.bitemporal.notification.enabled`, `correction.enabled`, `versioning.enabled`, `bulk.operations.enabled` |
| UNSUPPORTED logging wrapper | `peegeeq.logging.level.root`, `logging.level.peegeeq`, `logging.pattern` |

CLI-only test-runner selectors such as `peegeeq.performance.suite` and
`peegeeq.performance.tests` are test metadata, not `PeeGeeQConfiguration` runtime
properties.

## Validation

Construction aggregates validation errors and throws `IllegalStateException`.

- Database host, name, username, password, and schema are required; an empty password is
  permitted with a warning. Database and nonblank proxy ports must be 1–65535.
- Pool connection timeout must be positive; idle timeout must be non-negative.
- Queue retries must be non-negative; visibility must be at least one second; batch size must
  be 1–1000.
- Enabled recovery requires processing/check intervals of at least one minute and the check
  interval must be greater than the processing timeout.
- Enabled dead-consumer detection and consumer-group retry require intervals of at least ten
  seconds.
- Enabled metrics require a depth-cache interval of at least one second.
- Enabled circuit breakers require positive/range-valid settings, and the sliding window must
  be at least the minimum number of calls.

## Recommended construction

```java
Properties overrides = new Properties();
overrides.setProperty("peegeeq.database.host", host);
overrides.setProperty("peegeeq.database.port", Integer.toString(port));
// Optional: route both pooled and dedicated LISTEN/NOTIFY connections through a TCP proxy.
overrides.setProperty("peegeeq.database.proxy.host", proxyHost);
overrides.setProperty("peegeeq.database.proxy.port", Integer.toString(proxyPort));
overrides.setProperty("peegeeq.database.name", database);
overrides.setProperty("peegeeq.database.username", username);
overrides.setProperty("peegeeq.database.password", password);
overrides.setProperty("peegeeq.database.schema", schema);

PeeGeeQConfiguration configuration =
    new PeeGeeQConfiguration("production", overrides);
PeeGeeQManager manager = new PeeGeeQManager(configuration, meterRegistry, vertx);

manager.start()
    .onSuccess(ignored -> logger.info("PeeGeeQ started"))
    .onFailure(error -> logger.error("PeeGeeQ startup failed", error));
```

For tests, build isolated overrides with `PeeGeeQTestConfig` and a real PostgreSQL
Testcontainer. Never use process-global system properties for concurrent test configuration.
