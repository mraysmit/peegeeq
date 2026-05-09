# PeeGeeQ Configuration Guide

**Complete reference for operators, integrators, and developers**

Version: 1.0  
Date: May 9, 2026  
Author: Mark Andrew Ray-Smith Cityline Ltd

---

## Table of Contents

- [Overview](#overview)
- [Priority Chain](#priority-chain)
- [Constructors and When to Use Each](#constructors-and-when-to-use-each)
- [Named Profiles](#named-profiles)
- [Environment Variable Mapping](#environment-variable-mapping)
- [Property Reference](#property-reference)
  - [Database Connection](#database-connection)
  - [Connection Pool](#connection-pool)
  - [Database Tuning](#database-tuning)
  - [Queue Behaviour](#queue-behaviour)
  - [Stuck Message Recovery](#stuck-message-recovery)
  - [Dead Consumer Detection](#dead-consumer-detection)
  - [Consumer Group Retry](#consumer-group-retry)
  - [Metrics](#metrics)
  - [Circuit Breaker](#circuit-breaker)
  - [Health Check](#health-check)
  - [Backpressure](#backpressure)
  - [Bitemporal Features](#bitemporal-features)
  - [Migration](#migration)
  - [Performance Tuning](#performance-tuning)
  - [PostgreSQL Notice Handling](#postgresql-notice-handling)
  - [Maintenance](#maintenance)
- [Configuration Validation Rules](#configuration-validation-rules)
- [Operator Deployment Patterns](#operator-deployment-patterns)
  - [Single-Tenant Production](#single-tenant-production)
  - [Multi-Tenant / Multi-Instance in One JVM](#multi-tenant--multi-instance-in-one-jvm)
- [Vert.x 5.x Integration](#vertx-5x-integration)
- [Spring Boot Integration](#spring-boot-integration)
- [Developer and Test Patterns](#developer-and-test-patterns)
  - [PeeGeeQTestConfig Builder](#peegeeeqtestconfig-builder)
  - [Permitted System.setProperty Callers](#permitted-systemsetproperty-callers)
- [Troubleshooting](#troubleshooting)

---

## Overview

`PeeGeeQConfiguration` is the single source of truth for all PeeGeeQ runtime settings. It is
constructed once per manager instance and holds an isolated, immutable snapshot of the resolved
property values. No shared global state is consulted after construction — every instance carries
its own private copy.

**Class:** `dev.mars.peegeeq.db.config.PeeGeeQConfiguration`  
**Module:** `peegeeq-db`

`PeeGeeQManager` accepts a fully-constructed `PeeGeeQConfiguration` and delegates all
configuration reads through it. `new PeeGeeQManager(config)` is the standard production entry
point.

---

## Priority Chain

Properties are resolved from lowest to highest priority. A later source overrides an earlier one
for the same key.

```
1. peegeeq-default.properties          (classpath, always loaded)
2. peegeeq-<profile>.properties        (classpath, loaded when profile != "default")
3. PEEGEEQ_* environment variables     (converted to peegeeq.* key format)
4. System properties (peegeeq.*)       (JVM -D args — single-tenant convenience only)
5. Programmatic overrides              (Properties passed to the 2-arg constructor — highest)
```

**Profile selection** is resolved once at construction:

```
System.getProperty("peegeeq.profile")
  → env PEEGEEQ_PROFILE
  → fallback: "default"
```

> **Multi-tenancy warning:** System properties (step 4) are process-wide. In a JVM hosting
> multiple tenant instances, relying on `System.setProperty` creates silent misconfiguration
> races — the last writer wins, with no exception, no log warning, and no compile-time
> detection. **Always use the 2-arg constructor with an explicit `Properties` object** for
> multi-tenant and test scenarios. See
> [Multi-Tenant / Multi-Instance in One JVM](#multi-tenant--multi-instance-in-one-jvm).
>
> **Single-tenant `-Dpeegeeq.*` JVM args:** If you need `-Dpeegeeq.database.host=...` style
> JVM argument support for local development convenience, handle the sweep once at your
> application entry point — not inside `PeeGeeQConfiguration`:
> ```java
> // In main() / application bootstrap only
> Properties jvmOverrides = new Properties();
> System.getProperties().forEach((k, v) -> {
>     if (k.toString().startsWith("peegeeq."))
>         jvmOverrides.setProperty(k.toString(), v.toString());
> });
> PeeGeeQConfiguration config = new PeeGeeQConfiguration("production", jvmOverrides);
> ```
> This keeps System access to one intentional call at the application boundary.

---

## Constructors and When to Use Each

### `new PeeGeeQConfiguration()`

Loads the `"default"` profile (or the profile named by `peegeeq.profile` / `PEEGEEQ_PROFILE`).
Suitable for **single-tenant applications** that configure the JVM once at startup.

```java
PeeGeeQConfiguration config = new PeeGeeQConfiguration();
PeeGeeQManager manager = new PeeGeeQManager(config);
```

---

### `new PeeGeeQConfiguration(String profile)`

Loads a named profile explicitly.

```java
PeeGeeQConfiguration config = new PeeGeeQConfiguration("production");
```

---

### `new PeeGeeQConfiguration(String profile, Properties overrides)` — recommended

Loads profile defaults, applies env vars, then applies `overrides` on top without touching
`System.getProperties()`. This is the **correct pattern for multi-tenant deployments and all
tests**.

```java
Properties props = new Properties();
props.setProperty("peegeeq.database.host",     tenant.getDbHost());
props.setProperty("peegeeq.database.schema",   tenant.getSchema());
props.setProperty("peegeeq.database.username", tenant.getDbUser());
props.setProperty("peegeeq.database.password", tenant.getDbPassword());
PeeGeeQConfiguration config = new PeeGeeQConfiguration("production", props);
```

Any property set in `overrides` dominates all earlier sources including environment variables.

---

### `new PeeGeeQConfiguration(String profile, String dbHost, int dbPort, String dbName, String dbUsername, String dbPassword, String dbSchema)`

Convenience overload that accepts database coordinates directly. Equivalent to using the
2-arg constructor with those six properties pre-populated.

---

## Named Profiles

Profile files live in `peegeeq-db/src/main/resources/`. Each file overrides only the properties
it lists; everything else falls through to `peegeeq-default.properties`.

| Profile | File | Intended use |
|---|---|---|
| `default` | `peegeeq-default.properties` | Local development, fallback baseline |
| `development` | `peegeeq-development.properties` | Dev: small pool, verbose logging, auto-migrate on |
| `production` | `peegeeq-production.properties` | Production: SSL, env-var placeholders, no auto-migrate |
| `reliable` | `peegeeq-reliable.properties` | Guaranteed delivery: high retries, long visibility timeout |
| `low-latency` | `peegeeq-low-latency.properties` | Real-time: 10 ms polling, batch-size=1, DLQ off |
| `high-performance` | `peegeeq-high-performance.properties` | Throughput: large batches, 8 consumer threads, pipelining=32 |
| `high-throughput` | `peegeeq-high-throughput.properties` | Batch processing: large pool, prefetch=50 |
| `vertx5-optimized` | `peegeeq-vertx5-optimized.properties` | Vert.x 5 best practices: pipelining=1024, 8 verticle instances |
| `extreme-performance` | `peegeeq-extreme-performance.properties` | Benchmarks only: pool=200, pipelining=2048, 16 verticles |
| `bitemporal-optimized` | `peegeeq-bitemporal-optimized.properties` | Bitemporal event-sourcing workloads |
| `parallel-test` | `peegeeq-parallel-test.properties` | Parallel test isolation: smaller pool, 4 consumer threads |

### Profile comparison — key settings

| Setting | `default` | `development` | `production` | `reliable` | `low-latency` | `high-performance` |
|---|---|---|---|---|---|---|
| pool min / max | 8 / 32 | 2 / 5 | 10 / 50 | 10 / 50 | 5 / 20 | 8 / 32 |
| batch-size | 10 | 5 | 50 | 10 | 1 | 100 |
| polling-interval | PT5S | PT2S | PT0.5S | PT1S | PT0.01S | PT0.1S |
| max-retries | 3 | 2 | 5 | 10 | 1 | 3 |
| visibility-timeout | PT30S | PT15S | PT60S | PT300S | PT10S | PT30S |
| consumer.threads | 1 | 1 | 1 | 1 | 1 | 8 |
| circuit-breaker | enabled | **disabled** | enabled | enabled | enabled | enabled |
| auto-migrate | false | **true** | false | — | — | — |
| SSL | false | false | **true** | — | — | — |

---

## Environment Variable Mapping

Environment variables with the `PEEGEEQ_` prefix are automatically mapped to property keys.
Underscore separators are converted to dots; hyphenated property key names are also matched
(e.g., `PEEGEEQ_DATABASE_POOL_MAX_SIZE` → `peegeeq.database.pool.max-size`).

| Environment variable | Property key |
|---|---|
| `PEEGEEQ_PROFILE` | profile selection |
| `PEEGEEQ_DATABASE_HOST` | `peegeeq.database.host` |
| `PEEGEEQ_DATABASE_PORT` | `peegeeq.database.port` |
| `PEEGEEQ_DATABASE_NAME` | `peegeeq.database.name` |
| `PEEGEEQ_DATABASE_USERNAME` | `peegeeq.database.username` |
| `PEEGEEQ_DATABASE_PASSWORD` | `peegeeq.database.password` |
| `PEEGEEQ_DATABASE_SCHEMA` | `peegeeq.database.schema` |
| `PEEGEEQ_DATABASE_SSL_ENABLED` | `peegeeq.database.ssl.enabled` |
| `PEEGEEQ_DATABASE_POOL_MAX_SIZE` | `peegeeq.database.pool.max-size` |
| `INSTANCE_ID` | `peegeeq.metrics.instance-id` (via `${INSTANCE_ID:peegeeq-prod}` in `production` profile) |

The `production` profile uses `${ENV_VAR:default}` placeholder syntax for the six core database
settings. These are resolved by `PeeGeeQConfiguration` at load time.

---

## Property Reference

Duration values use ISO-8601 notation: `PT30S` = 30 seconds, `PT5M` = 5 minutes, `P30D` = 30 days.

### Database Connection

| Property | Default | Description |
|---|---|---|
| `peegeeq.database.host` | `localhost` | PostgreSQL host. Required. |
| `peegeeq.database.port` | `5432` | PostgreSQL port. Must be 1–65535. |
| `peegeeq.database.name` | `peegeeq` | Database name. Required. |
| `peegeeq.database.username` | `peegeeq` | Database username. Required. |
| `peegeeq.database.password` | `peegeeq` | Database password. Empty is permitted (trust/peer auth); a WARN is logged. |
| `peegeeq.database.schema` | `public` | PostgreSQL `search_path` schema. Set per-tenant for full multi-tenant isolation. |
| `peegeeq.database.ssl.enabled` | `false` | Enable TLS. Set `true` in production. |

### Connection Pool

Timeout properties use the `-ms` suffix and are in milliseconds.

| Property | Default | Description |
|---|---|---|
| `peegeeq.database.pool.min-size` | `8` | Minimum idle connections. Must be ≥ 1. |
| `peegeeq.database.pool.max-size` | `32` | Maximum connections. Must be ≥ `min-size`. |
| `peegeeq.database.pool.shared` | `true` | Use a shared Vert.x pool keyed by pool name. |
| `peegeeq.database.pool.name` | — | Named-pool identifier (metrics and shared pool lookup). |
| `peegeeq.database.pool.connection-timeout-ms` | `30000` | Maximum wait (ms) for a connection. Must be > 0. |
| `peegeeq.database.pool.idle-timeout-ms` | `600000` | Idle eviction timeout (ms). `0` disables. |
| `peegeeq.database.pool.max-lifetime-ms` | `1800000` | Maximum connection lifetime (ms). |
| `peegeeq.database.pool.auto-commit` | `true` | Default auto-commit. Set `false` for explicit transaction control. |
| `peegeeq.database.pool.wait-queue-multiplier` | `10` | `max-wait-queue-size = max-size × multiplier`. |
| `peegeeq.database.pool.max-wait-queue-size` | — | Explicit override for wait queue size (overrides multiplier). |

### Database Tuning

| Property | Default | Description |
|---|---|---|
| `peegeeq.database.pipelining.enabled` | `true` | Enable Vert.x PostgreSQL pipelining. Disable if behind a proxy that does not support it (e.g. PgBouncer in transaction mode). |
| `peegeeq.database.pipelining.limit` | `1024` | Maximum pipelined requests per connection. Use 8–32 for latency-sensitive; 1024–2048 for throughput. |
| `peegeeq.database.event.loop.size` | `0` | Vert.x event-loop thread count. `0` = Vert.x default (`availableProcessors × 2`). |
| `peegeeq.database.worker.pool.size` | `0` | Vert.x worker-thread count. `0` = Vert.x default (`availableProcessors × 4`). |
| `peegeeq.database.use.event.bus.distribution` | `false` | Distribute messages across event loops via the Vert.x event bus. Enable for `extreme-performance` only. |
| `peegeeq.verticle.instances` | `0` | PeeGeeQ verticle instance count. `0` = 1. Set to `availableProcessors` for multi-core throughput. |
| `peegeeq.database.batch.size` | — | Internal SQL batch size (extreme-performance: 2000). |

### Queue Behaviour

| Property | Default | Description |
|---|---|---|
| `peegeeq.queue.max-retries` | `3` | Maximum delivery attempts before a message is dead-lettered. `0` = dead-letter immediately. Must be ≥ 0. |
| `peegeeq.queue.visibility-timeout` | `PT30S` | How long a dequeued message is invisible to other consumers. Must be ≥ 1 s. Set longer than worst-case processing time. |
| `peegeeq.queue.batch-size` | `10` | Messages fetched per poll cycle. Must be 1–1000. Use 1 for low-latency; 50–500 for throughput. |
| `peegeeq.queue.polling-interval` | `PT5S` | How often the consumer polls when the queue is empty. Reduce to `PT0.01S`–`PT0.5S` for near-real-time. |
| `peegeeq.queue.dead-letter.enabled` | `true` | Move exhausted messages to the dead-letter queue. |
| `peegeeq.queue.priority.default` | `5` | Default priority assigned to new messages (1 = lowest, 10 = highest). |
| `peegeeq.consumer.threads` | `1` | Processing threads per manager instance. Increase for CPU-bound workloads. |
| `peegeeq.queue.prefetch-count` | — | Messages pre-fetched per consumer (profile-specific). |
| `peegeeq.queue.concurrent-consumers` | — | Concurrent consumer goroutines (profile-specific). |
| `peegeeq.queue.buffer-size` | — | In-memory buffer depth (profile-specific). |
| `peegeeq.queue.retention-period` | — | How long processed messages are retained before pruning (e.g. `P30D`). |

### Stuck Message Recovery

Requeues messages that have been invisible too long — worker crash recovery.

| Property | Default | Description |
|---|---|---|
| `peegeeq.queue.recovery.enabled` | `true` | Enable automatic stuck message recovery. |
| `peegeeq.queue.recovery.processing-timeout` | `PT5M` | A message invisible longer than this is considered stuck. Must be ≥ PT1M. |
| `peegeeq.queue.recovery.check-interval` | `PT10M` | Scan frequency. Must be ≥ PT1M **and strictly greater than** `processing-timeout`. |

### Dead Consumer Detection

Detects consumers that have stopped heartbeating and releases their claimed messages.

| Property | Default | Description |
|---|---|---|
| `peegeeq.queue.dead-consumer-detection.enabled` | `true` | Enable dead consumer detection. |
| `peegeeq.queue.dead-consumer-detection.interval` | `PT1M` | Scan interval. Must be ≥ 10 s. |

### Consumer Group Retry

Controls retry scheduling for consumer groups that have failed.

| Property | Default | Description |
|---|---|---|
| `peegeeq.queue.consumer-group-retry.enabled` | `true` | Enable consumer group retry scheduling. |
| `peegeeq.queue.consumer-group-retry.interval` | `PT30S` | Re-attempt interval. Must be ≥ 10 s. |

### Metrics

| Property | Default | Description |
|---|---|---|
| `peegeeq.metrics.enabled` | `true` | Enable metrics collection. |
| `peegeeq.metrics.reporting-interval` | `PT1M` | How often metrics snapshots are published. Must be ≥ 1 s. |
| `peegeeq.metrics.depth-cache-interval` | `PT5S` | Queue-depth cache refresh rate. Must be ≥ 1 s. |
| `peegeeq.metrics.jvm.enabled` | `true` | Include JVM metrics (heap, GC, threads). Disable in `high-performance` to reduce overhead. |
| `peegeeq.metrics.database.enabled` | `true` | Include database pool metrics. |
| `peegeeq.metrics.instance-id` | `peegeeq-<random-8>` | Unique identifier for this manager in metrics output. Set a stable value in production. |
| `peegeeq.metrics.collection.enabled` | — | Enable internal metrics collection (profile-specific). |
| `peegeeq.metrics.collection.async-save` | — | Persist metrics snapshots asynchronously. |
| `peegeeq.metrics.collection.sampling-rate` | — | Fraction of operations sampled (0.0–1.0). |
| `peegeeq.metrics.bitemporal.enabled` | `false` | Include bitemporal-specific metrics. |
| `peegeeq.metrics.detailed.enabled` | `false` | Include extended per-queue detail metrics. |

### Circuit Breaker

Guards health-check queries against a degraded database. Does not gate business operations.

| Property | Default | Description |
|---|---|---|
| `peegeeq.circuit-breaker.enabled` | `true` | Enable the circuit breaker. Set `false` in `development` to simplify debugging. |
| `peegeeq.circuit-breaker.failure-threshold` | `5` | Consecutive failures before the breaker opens. Must be ≥ 1. |
| `peegeeq.circuit-breaker.wait-duration` | `PT1M` | How long the breaker stays open before probing (half-open). Must be ≥ 1 s. |
| `peegeeq.circuit-breaker.ring-buffer-size` | `100` | Sliding-window size for failure-rate calculation. |
| `peegeeq.circuit-breaker.failure-rate-threshold` | `50.0` | Failure percentage that opens the breaker. |
| `peegeeq.circuit-breaker.slow-call-rate-threshold` | — | Percentage of slow calls that opens the breaker. |
| `peegeeq.circuit-breaker.slow-call-duration-threshold` | — | Duration above which a call is considered slow. |
| `peegeeq.circuit-breaker.permitted-calls-in-half-open-state` | — | Probe calls allowed while half-open. |
| `peegeeq.circuit-breaker.sliding-window-size` | — | Explicit sliding-window size (parallel-test profile). |
| `peegeeq.circuit-breaker.minimum-number-of-calls` | — | Minimum calls before failure rate is evaluated. |
| `peegeeq.circuit-breaker.wait-duration-in-open-state` | — | Alias for `wait-duration` (some profiles). |

### Health Check

| Property | Default | Description |
|---|---|---|
| `peegeeq.health.enabled` | `true` | Enable periodic health checks. |
| `peegeeq.health.check-interval` | `PT30S` | Frequency of health-check queries. |
| `peegeeq.health.timeout` | `PT5S` | Maximum time allowed for a health-check query. |
| `peegeeq.health.failure-threshold` | — | Consecutive failures before status is `DOWN`. |
| `peegeeq.health.recovery-threshold` | — | Consecutive successes needed to return to `UP`. |
| `peegeeq.health-check.queue-checks-enabled` | `false` | Include per-queue depth checks in health output. |

> The `development` profile uses `peegeeq.health-check.*` key variants. Both forms are recognised. Prefer `peegeeq.health.*` in new deployments.

### Backpressure

| Property | Default | Description |
|---|---|---|
| `peegeeq.backpressure.enabled` | — | Enable backpressure control. |
| `peegeeq.backpressure.max-queue-size` | — | Maximum in-flight messages before backpressure activates. |
| `peegeeq.backpressure.high-watermark` | — | Queue depth that triggers slow-down. |
| `peegeeq.backpressure.low-watermark` | — | Queue depth at which normal pace resumes. |
| `peegeeq.backpressure.check-interval` | — | How often watermarks are evaluated. |
| `peegeeq.backpressure.max-concurrent-operations` | `50` | Maximum simultaneous in-flight operations (parallel-test profile). |
| `peegeeq.backpressure.timeout` | `PT30S` | Maximum wait for an available slot. |

### Bitemporal Features

| Property | Default | Description |
|---|---|---|
| `peegeeq.bitemporal.notification.enabled` | `false` | Enable LISTEN/NOTIFY for bitemporal change events. |
| `peegeeq.bitemporal.correction.enabled` | `false` | Enable bitemporal correction tracking. |
| `peegeeq.bitemporal.versioning.enabled` | `false` | Enable bitemporal version-chain management. |
| `peegeeq.bitemporal.bulk.operations.enabled` | `false` | Enable bulk bitemporal write optimisations. |

Enable these via the `bitemporal-optimized` or `extreme-performance` profile, or via overrides.

### Migration

| Property | Default | Description |
|---|---|---|
| `peegeeq.migration.enabled` | `true` | Enable Flyway migration checks at startup. |
| `peegeeq.migration.validate-checksums` | `true` | Validate applied migration checksums. Set `false` in `development` to allow local script edits. |
| `peegeeq.migration.auto-migrate` | `false` | Apply pending migrations automatically at startup. Set `true` in `development`. **Never `true` in production.** |
| `peegeeq.migration.validate-on-migrate` | — | Run schema validation after migration (profile-specific). |

### Performance Tuning

| Property | Default | Description |
|---|---|---|
| `peegeeq.performance.async.enabled` | `true` | Enable async internal operations. |
| `peegeeq.performance.async.thread-pool-size` | `10` | Async thread-pool size. |
| `peegeeq.performance.batch.enabled` | `true` | Enable internal SQL batching. |
| `peegeeq.performance.batch.max-size` | `100` | Maximum SQL batch size. |
| `peegeeq.performance.batch.timeout` | `PT5S` | Flush timeout for an accumulating batch. |
| `peegeeq.performance.monitoring.enabled` | — | Enable performance diagnostic monitoring. |
| `peegeeq.performance.monitoring.interval` | — | Sample interval (ms). |
| `peegeeq.performance.thresholds.query.warning` | — | Query duration (ms) above which a WARN is logged. |
| `peegeeq.performance.thresholds.query.critical` | — | Query duration (ms) above which an ERROR is logged. |
| `peegeeq.performance.thresholds.connection.warning` | — | Connection-wait (ms) WARN threshold. |
| `peegeeq.performance.thresholds.connection.critical` | — | Connection-wait (ms) ERROR threshold. |
| `peegeeq.performance.suite` | — | **CLI override only** — selects the performance test suite to run (`-Dpeegeeq.performance.suite=outbox`). Belongs in `System.setProperty` / `-D` JVM args for the performance test runner; not relevant to production configuration. |
| `peegeeq.performance.tests` | — | **CLI override only** — comma-separated test names for the performance runner. Same scope as `peegeeq.performance.suite`. |

### PostgreSQL Notice Handling

Controls how PostgreSQL NOTICE messages (e.g. from idempotent `IF NOT EXISTS` DDL) are surfaced in logs.

| Property | Default | Description |
|---|---|---|
| `peegeeq.notices.info.enabled` | `true` | Log INFO-level PostgreSQL notices. |
| `peegeeq.notices.info.level` | `INFO` | Log level for INFO notices. |
| `peegeeq.notices.other.enabled` | `false` | Log non-INFO PostgreSQL notices. |
| `peegeeq.notices.other.level` | `DEBUG` | Log level for other notices. |
| `peegeeq.notices.metrics.enabled` | `true` | Track notice counts in metrics. |

### Maintenance

| Property | Default | Description |
|---|---|---|
| `peegeeq.maintenance.cleanup-interval` | `PT1H` | How often old completed messages are pruned. |
| `peegeeq.maintenance.retention-period` | `P7D` | How long completed messages are retained before pruning. |

---

## Configuration Validation Rules

`PeeGeeQConfiguration` validates all settings at construction time and throws
`IllegalStateException` with the full list of violations if any rule fails.

**Database**
- `host` must not be empty
- `port` must be 1–65535
- `name` must not be empty
- `username` must not be empty
- `pool.min-size` ≥ 1
- `pool.max-size` ≥ `pool.min-size`
- `pool.connection-timeout-ms` > 0
- `pool.idle-timeout-ms` ≥ 0

**Queue**
- `max-retries` ≥ 0
- `visibility-timeout` ≥ 1000 ms (1 s)
- `batch-size` must be 1–1000
- If `recovery.enabled`: `processing-timeout` ≥ 60 s; `check-interval` ≥ 60 s; `check-interval` > `processing-timeout`
- If `dead-consumer-detection.enabled`: `interval` ≥ 10 s
- If `consumer-group-retry.enabled`: `interval` ≥ 10 s

**Metrics** (when `metrics.enabled = true`)
- `reporting-interval` ≥ 1000 ms
- `depth-cache-interval` ≥ 1000 ms

**Circuit breaker** (when `circuit-breaker.enabled = true`)
- `failure-threshold` ≥ 1
- `wait-duration` ≥ 1000 ms

---

## Operator Deployment Patterns

### Single-Tenant Production

Use the `production` profile, supply credentials via environment variables:

```bash
export PEEGEEQ_DATABASE_HOST=db.example.com
export PEEGEEQ_DATABASE_PORT=5432
export PEEGEEQ_DATABASE_NAME=peegeeq_prod
export PEEGEEQ_DATABASE_USERNAME=peegeeq_prod
export PEEGEEQ_DATABASE_PASSWORD=<secret>
export PEEGEEQ_DATABASE_SCHEMA=public
export INSTANCE_ID=peegeeq-prod-1
```

```java
PeeGeeQConfiguration config = new PeeGeeQConfiguration("production");
PeeGeeQManager manager = new PeeGeeQManager(config, meterRegistry);
manager.start()
    .onSuccess(v -> logger.info("PeeGeeQ started"))
    .onFailure(err -> logger.error("PeeGeeQ failed to start", err));
```

For any setting not sourced from the environment, pass explicit overrides:

```java
Properties overrides = new Properties();
overrides.setProperty("peegeeq.database.pool.max-size", "80");
overrides.setProperty("peegeeq.metrics.instance-id", System.getenv("HOSTNAME"));
PeeGeeQConfiguration config = new PeeGeeQConfiguration("production", overrides);
```

### Multi-Tenant / Multi-Instance in One JVM

Each tenant gets its own `PeeGeeQConfiguration` and `PeeGeeQManager`. **Never use
`System.setProperty` for multi-tenant configuration** — System properties are process-wide and
cause silent misconfiguration when multiple instances initialise concurrently.

```java
Map<String, PeeGeeQManager> tenantManagers = new ConcurrentHashMap<>();

for (Tenant tenant : tenants) {
    Properties props = new Properties();
    props.setProperty("peegeeq.database.host",      tenant.dbHost());
    props.setProperty("peegeeq.database.port",      String.valueOf(tenant.dbPort()));
    props.setProperty("peegeeq.database.name",      tenant.dbName());
    props.setProperty("peegeeq.database.username",  tenant.dbUser());
    props.setProperty("peegeeq.database.password",  tenant.dbPassword());
    props.setProperty("peegeeq.database.schema",    tenant.schema());
    props.setProperty("peegeeq.metrics.instance-id", "peegeeq-" + tenant.id());

    PeeGeeQConfiguration config = new PeeGeeQConfiguration("production", props);
    PeeGeeQManager manager = new PeeGeeQManager(config, meterRegistry);
    manager.start();
    tenantManagers.put(tenant.id(), manager);
}
```

Each manager holds a completely isolated configuration snapshot. Schema isolation is absolute:
LISTEN/NOTIFY channels and all SQL templates are scoped to the tenant's `peegeeq.database.schema`.

---

## Vert.x 5.x Integration

PeeGeeQ is built exclusively on Vert.x 5.x reactive APIs. All I/O — including every database
operation — is non-blocking and returns `Future<T>`. Understanding how the configuration maps to
Vert.x internals is important for sizing and tuning deployments.

### Vertx instance ownership

`PeeGeeQManager` can accept an externally-owned `Vertx` instance or create its own:

```java
// Use an existing Vertx instance (recommended in Vert.x applications)
PeeGeeQManager manager = new PeeGeeQManager(config, registry, vertx);

// Let PeeGeeQManager create and own its own Vertx instance
PeeGeeQManager manager = new PeeGeeQManager(config);
```

When sharing a `Vertx` instance, `PeeGeeQManager.close()` does **not** close the shared
instance. When `PeeGeeQManager` owns its instance, `close()` shuts it down.

### Reactive pool (`PgPool`)

`PeeGeeQConfiguration` drives the `PgPool` created internally by `PeeGeeQManager`. The
relevant properties are:

| Property | Effect on PgPool |
|---|---|
| `peegeeq.database.pool.min-size` | `PgPoolOptions.setMinSize` |
| `peegeeq.database.pool.max-size` | `PgPoolOptions.setMaxSize` |
| `peegeeq.database.pool.connection-timeout` | `PgPoolOptions.setConnectionTimeout` (ms) |
| `peegeeq.database.pool.idle-timeout` | `PgPoolOptions.setIdleTimeout` (ms) |
| `peegeeq.database.pool.shared` | `PgPoolOptions.setShared` — pool is keyed by name |
| `peegeeq.database.pool.name` | Pool name when `shared=true` |
| `peegeeq.database.pipelining.enabled` | `PgConnectOptions.setPipeliningLimit > 1` |
| `peegeeq.database.pipelining.max-commands` | Maximum pipelined commands per connection |

Pipelining is enabled by default. Disable it (`peegeeq.database.pipelining.enabled=false`) when
connecting through a proxy that does not support the PostgreSQL extended query protocol (e.g.
PgBouncer in transaction mode).

### Event-loop and worker thread sizing

| Property | Default | Notes |
|---|---|---|
| `peegeeq.database.event.loop.size` | `0` (Vert.x default) | `0` → `availableProcessors × 2` |
| `peegeeq.database.worker.pool.size` | `0` (Vert.x default) | `0` → `availableProcessors × 4` |
| `peegeeq.database.use.event.bus.distribution` | `false` | Spread message dispatch across event loops via the event bus; only meaningful at extreme throughput |

For most deployments the Vert.x defaults are correct. Override only when profiling shows
specific event-loop saturation.

### Lifecycle — `Future<Void>` everywhere

Every `PeeGeeQManager` lifecycle method returns a `Future<Void>`:

```java
manager.start()
    .compose(v -> manager.send(envelope))
    .compose(v -> manager.close())
    .onFailure(err -> log.error("lifecycle error", err));
```

Do **not** block on these futures with `.get()`, `.join()`, or
`.toCompletionStage().toCompletableFuture().get()`. All three are forbidden in PeeGeeQ code —
they block the event-loop thread and cause pool starvation under load.

### Choosing the right profile for your Vert.x deployment

| Scenario | Recommended profile |
|---|---|
| Standard Vert.x application | `default` or `production` |
| Low-latency event processing | `low-latency` |
| High-throughput batch pipelines | `high-performance` or `high-throughput` |
| Maximising Vert.x pipelining/verticle density | `vertx5-optimized` |
| Benchmarks and load tests | `extreme-performance` |
| Bitemporal event-sourcing | `bitemporal-optimized` |

### `io.vertx:vertx-config` and `ConfigRetriever`

PeeGeeQ uses `io.vertx:vertx-config` (`ConfigRetriever`) in two modules:

| Module | Usage |
|---|---|
| `peegeeq-rest` | `StartRestServer` loads REST server settings (`port`, monitoring limits, etc.) from `conf/rest-server.json` + env vars + system properties into `RestServerConfig` |
| `peegeeq-test-support` | `BaseConfigurableTest` loads test fixture config from a JSON file on the classpath |

**`ConfigRetriever` and `PeeGeeQConfiguration` serve different domains and do not replace each other:**

- `ConfigRetriever` / `RestServerConfig` — HTTP server settings, monitoring thresholds, REST API behaviour.
- `PeeGeeQConfiguration` — database connection coordinates, pool sizing, queue behaviour, schema isolation.

`StartRestServer` bootstraps `PeeGeeQRuntime` (which constructs `PeeGeeQConfiguration` via the
standard profile/properties mechanism) independently of the `ConfigRetriever` pipeline. The
`JsonObject` returned by `ConfigRetriever` is never fed directly into `PeeGeeQConfiguration`.

If you need to source `peegeeq.*` values from a `ConfigRetriever` store (e.g. from a
`conf/peegeeq.json` file or a Consul store), extract them from the `JsonObject` and pass them
to the 2-arg constructor:

```java
retriever.getConfig()
    .compose(json -> {
        Properties overrides = new Properties();
        json.forEach(entry -> {
            if (entry.getKey().startsWith("peegeeq."))
                overrides.setProperty(entry.getKey(), entry.getValue().toString());
        });
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("production", overrides);
        return Future.succeededFuture(new PeeGeeQManager(config));
    });
```

This keeps the `ConfigRetriever`-sourced values isolated to the `overrides` `Properties` object
and avoids touching `System.setProperty`.

---

## Spring Boot Integration

Use `@ConfigurationProperties` to bind Spring Boot `application.yml` values into a `Properties`
object, then pass it to the 2-arg constructor. **Do not call `System.setProperty` inside a Spring `@Bean` method.** The old
`BiTemporalTxConfig` pattern of writing `peegeeq.database.*` keys to System during Spring
context initialisation creates a real production race: if two tenant contexts (or two Spring
test slices) initialise concurrently, the last `System.setProperty` call wins and one context
silently receives the other tenant's database coordinates — no exception, no warning.

```yaml
# application.yml
peegeeq:
  profile: production
  database:
    host: ${DB_HOST:localhost}
    port: ${DB_PORT:5432}
    name: ${DB_NAME:peegeeq}
    username: ${DB_USERNAME:peegeeq}
    password: ${DB_PASSWORD:}
    schema: ${DB_SCHEMA:public}
  pool:
    min-size: 10
    max-size: 50
```

```java
@Configuration
@EnableConfigurationProperties(PeeGeeQProperties.class)
public class PeeGeeQConfig {

    @Bean
    public PeeGeeQManager peeGeeQManager(PeeGeeQProperties props, MeterRegistry registry,
                                         Vertx vertx) {
        Properties overrides = new Properties();
        overrides.setProperty("peegeeq.database.host",     props.getDatabase().getHost());
        overrides.setProperty("peegeeq.database.port",     String.valueOf(props.getDatabase().getPort()));
        overrides.setProperty("peegeeq.database.name",     props.getDatabase().getName());
        overrides.setProperty("peegeeq.database.username", props.getDatabase().getUsername());
        overrides.setProperty("peegeeq.database.password", props.getDatabase().getPassword());
        overrides.setProperty("peegeeq.database.schema",   props.getDatabase().getSchema());

        PeeGeeQConfiguration config = new PeeGeeQConfiguration(props.getProfile(), overrides);
        PeeGeeQManager manager = new PeeGeeQManager(config, registry, vertx);
        // start() returns Future<Void> — integrate with your Vert.x lifecycle
        return manager;
    }
}
```

`PeeGeeQProperties` is provided in `peegeeq-examples-spring` as a reference implementation.

---

## Developer and Test Patterns

### PeeGeeQTestConfig Builder

`PeeGeeQTestConfig` (module `peegeeq-test-support`) is the standard way to build test
configuration from a running Testcontainers `PostgreSQLContainer`. It eliminates the
`System.setProperty` / `System.clearProperty` boilerplate entirely and is safe for parallel
test execution.

```java
// @BeforeEach or @BeforeAll
Properties props = PeeGeeQTestConfig.builder()
    .from(postgres)                                      // host, port, db, user, password from container
    .schema("test_schema")                               // optional schema override
    .property("peegeeq.database.pool.min-size", "1")    // any additional overrides
    .property("peegeeq.database.pool.max-size", "3")
    .property("peegeeq.migration.enabled", "false")
    .build();

PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", props);
```

`from(container)` extracts `host`, `port`, `database name`, `username`, and `password` directly
from the live container, so dynamic port assignment is always reflected correctly.

**Reference implementation:** `ResourceLeakDetectionTest` in `peegeeq-db`.

> **Supply all test-relevant properties in overrides.** The 2-arg constructor currently still
> sweeps `System.getProperties()` for `peegeeq.*` keys before applying overrides (Phase 11 of
> the config remediation will remove this sweep). Until then, any property you do *not* supply
> in the `overrides` object can be silently contaminated by another test thread that has set it
> via System. Pass every property your test depends on explicitly through the builder.

> **`test.database.*` properties are safe.** `PeeGeeQTestBase` and
> `ParameterizedPerformanceTestBase` write to the `test.database.*` namespace for JDBC/Spring
> integration. Because `loadProperties()` only sweeps keys starting with `peegeeq.`, this
> namespace is never swept and creates no `PeeGeeQConfiguration` races. Leave those helpers
> as-is.

### Permitted System.setProperty Callers

The following test files deliberately exercise the System-property reading path in
`PeeGeeQConfiguration` and **must not** be migrated to `PeeGeeQTestConfig.builder()`.
All carry `@ResourceLock("system-properties")` to serialise against each other:

- `PgPoolConfigPropertyBindingTest.java` — parameterised verification of System property reading
- `PeeGeeQConfigurationTest.java` — unit tests for `PeeGeeQConfiguration` itself
- `SystemPropertiesConfigurationDemoTest.java` — demonstration test
- `SystemPropertiesConfigurationExampleTest.java` — demonstration test
- `ConfigurationValidationTest.java` (`peegeeq-examples`) — validates configuration wiring; some assertions call `System.getProperty` to verify what was written. Review individually before migrating.

Any other test that touches `System.setProperty("peegeeq.*", ...)` is a violation.

---

## Troubleshooting

### `IllegalStateException: Maximum pool size must be greater than or equal to minimum pool size`

`pool.min-size` resolved to a larger value than `pool.max-size`. Most commonly caused by two
configurations being mixed via System property races. Fix: use the 2-arg constructor with
explicit pool properties — see [Multi-Tenant pattern](#multi-tenant--multi-instance-in-one-jvm).

---

### `IllegalStateException: Recovery check interval should be longer than processing timeout`

`peegeeq.queue.recovery.check-interval` ≤ `peegeeq.queue.recovery.processing-timeout`.
Increase `check-interval` so it strictly exceeds `processing-timeout`, otherwise the scanner
marks messages as stuck before the worker holding them has had time to finish.

---

### `IllegalStateException: Database host is required`

`peegeeq.database.host` resolved to an empty string. Check that `PEEGEEQ_DATABASE_HOST` is set
in the environment, or that the `overrides` `Properties` object contains `peegeeq.database.host`.

---

### Tests hang for 30 seconds then report `Timeout` with no root cause

An exception was thrown inside a bare `.onSuccess()` callback. Vert.x routes synchronous
exceptions thrown inside `onSuccess` to the vertex exception handler silently; `onFailure` is
never called and `VertxTestContext` times out. Fix:

```java
// Replace:
.onSuccess(v -> {
    someMethodThatMayThrow();
    testContext.completeNow();
})

// With:
.onComplete(testContext.succeeding(v -> testContext.verify(() -> {
    someMethodThatMayThrow();
    testContext.completeNow();
})))
```

---

### `WARN: Reuse was requested but environment does not support container reuse`

Expected noise when `testcontainers.reuse.enable=true` is absent from
`~/.testcontainers.properties`. Not a defect. Add it for faster local test iteration:

```properties
# ~/.testcontainers.properties
testcontainers.reuse.enable=true
```

---

### `WARN: Resource [logback.xml] occurs multiple times on the classpath`

Multiple PeeGeeQ JARs bundle their own `logback.xml`. Harmless at test time. Long-term fix:
remove `logback.xml` from library module JARs and keep it only in executable entry-point modules.

---

### Metrics instance-id shows a random UUID suffix

`peegeeq.metrics.instance-id` was not set explicitly. Set it to a stable, deployment-specific
value for meaningful dashboards:

```properties
peegeeq.metrics.instance-id=peegeeq-prod-us-east-1-pod-7
```

Or via override:

```java
overrides.setProperty("peegeeq.metrics.instance-id", System.getenv("HOSTNAME"));
```
