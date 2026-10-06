# PeeGeeQ Connection Management and HAProxy Failover

**Author**: Mark A Ray-Smith Cityline Ltd.
**Status**: CURRENT OPERATING REFERENCE
**Last reconciled**: 2026-10-06 against commit `f1c5d25d`

The 2026-10-06 revision retired the gap list closed by Task 3 of the consolidated task
register (tasks.md §3, completed 2026-09-02) and removed the duplicated sidecar appendix.
Git history retains both.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Resiliency Options at a Glance](#resiliency-options-at-a-glance)
3. [1. Connection Management Architecture](#1-connection-management-architecture)
4. [2. Resilience Stack Active During a Failover Window](#2-resilience-stack-active-during-a-failover-window)
5. [3. Application-Tier Failover vs Database-Tier Failover](#3-application-tier-failover-vs-database-tier-failover)
6. [4. Why the Vert.x Pool Needs an External Proxy for Failover](#4-why-the-vertx-pool-needs-an-external-proxy-for-failover)
7. [5. HAProxy Failover Integration Test](#5-haproxy-failover-integration-test)
8. [6. Local Developer Environment (HAProxy + PgBouncer)](#6-local-developer-environment-haproxy--pgbouncer)
9. [7. Verified Coverage](#7-verified-coverage)
10. [8. Using the pg-sidecar Service](#8-using-the-pg-sidecar-service-peegeeq-pg-sidecar)
11. [Appendix A: HAProxy Primary Detection Options](#appendix-a-haproxy-primary-detection-options)
12. [Appendix B: The JDBC Multi-Host Failover Pattern](#appendix-b-the-jdbc-multi-host-failover-pattern-historical-reference)

---

## Executive Summary

PeeGeeQ connects to PostgreSQL exclusively via the Vert.x 5.x reactive client (`io.vertx.pgclient`).
No JDBC, no HikariCP, no connection URL strings.
Connection options (host, port, database, credentials, and `search_path`) are set individually on
`PgConnectOptions`. The pool is built once per `serviceId` and cached in a `ConcurrentHashMap`.

The pool itself has no built-in failover. When a backend goes away, Vert.x discards broken
connections on the next acquisition attempt and opens new ones. Whether those new connections
succeed depends on what the pool's configured host/port resolves to at that moment. An external
TCP proxy solves this at the network layer.

**Design principle**: PeeGeeQ delegates database-tier failover to infrastructure. The application
tier handles its own node-level failover (via `ConnectionRouter` + Consul) independently of the
database tier. The two planes do not need to know about each other.

This document records:

1. The connection management architecture as found in the codebase.
2. The resilience stack active during a failover window.
3. Application-tier vs database-tier failover.
4. Why the Vert.x pool requires an external proxy for database failover.
5. The HAProxy failover integration test.
6. The local developer docker-compose topology (HAProxy + PgBouncer).
7. The tests that verify the resilience contracts.
8. How to build and use the pg-sidecar service.

---

## Resiliency Options at a Glance

The table below lists the PostgreSQL database-tier resiliency options available with PeeGeeQ,
ordered from simplest to most complete.

| Option | Failover behaviour | Promotion mechanism | Write downtime | Extra infrastructure |
|---|---|---|---|---|
| **Single managed FQDN (DBA/DevOps-controlled)** | No automatic failover. DBA/DevOps updates the DNS record or virtual IP to point at the new primary; the pool reconnects once the FQDN resolves to the live node | Manual DBA / DevOps (DNS or VIP flip) | Until FQDN is re-pointed | DNS TTL management or virtual IP |
| **HAProxy + `pgsql-check`** | Stops routing to dead node; standby stays read-only; no auto-promotion | Manual DBA | Until DBA promotes | HAProxy |
| **HAProxy + `httpchk` + `peegeeq-pg-sidecar`** | Semantic primary detection via `pg_is_in_recovery()`; no auto-promotion | Manual DBA | Until DBA promotes | HAProxy + sidecar per node |
| **HAProxy + Patroni** | Automatic failover; DCS leader election; fencing + `pg_rewind` | Patroni (`pg_promote()` + `pg_ctl`) | ~10–30 s | HAProxy + Patroni + DCS (etcd / Consul / ZK) |
| **HAProxy + Consul monitor (`peegeeq-service-manager`)** | Automatic failover; Consul TTL + LockDelay fencing; partial (`pg_terminate_backend`) | `pg_promote()` via reactive pgclient | ~25–30 s (tunable) | Consul cluster (already required by service-manager) |

### Reading the table

- **Option 1 (single managed FQDN)**: no proxy process. The application points at a single FQDN or virtual IP that DBA/DevOps re-points after failover. Suitable for development and non-critical workloads.
- **Option 2 (HAProxy + `pgsql-check`)**: adds TCP-level health checking and automatic re-routing away from a dead node. It cannot distinguish primary from replica. The standby remains read-only; a DBA must still promote manually.
- **Option 3 (HAProxy + sidecar)**: adds semantic primary detection (`pg_is_in_recovery()`) without Patroni. HAProxy routes only to the true write primary. Correct for production with manual failover.
- **Option 4 (HAProxy + Patroni)**: the full Patroni stack. Requires an external DCS. Provides automatic promotion, fencing, and `pg_rewind` for safe re-join.
- **Option 5 (Consul monitor)**: re-uses the Consul cluster that `peegeeq-service-manager` already requires. The `PgFailoverMonitor` and `PgPrimaryElector` components are **proposed** and do not exist in the codebase (see [PEEGEEQ_FAILOVER_CONSUL_DESIGN.md](../../peegeeq-service-manager/docs/PEEGEEQ_FAILOVER_CONSUL_DESIGN.md)).

In all cases, set `peegeeq.database.proxy.host` and `peegeeq.database.proxy.port` to the proxy or
load-balancer endpoint (see §1.2). Never point the application at a PostgreSQL node directly when
a proxy is in use.

> **And JDBC?** The PostgreSQL JDBC driver supports a multi-host URL
> (`jdbc:postgresql://host1,host2/db?targetServerType=primary`) that provides client-side
> failover without a proxy. This pattern is not applicable to PeeGeeQ. JDBC is a blocking
> synchronous protocol incompatible with the Vert.x reactive model. See
> [Appendix B](#appendix-b-the-jdbc-multi-host-failover-pattern-historical-reference).

---

## 1. Connection Management Architecture

### 1.1 Class hierarchy

```
PeeGeeQManager (public facade)
  └── PgClientFactory
        └── PgConnectionManager            peegeeq-db/.../connection/PgConnectionManager.java
              └── PgBuilder.pool()  ──► io.vertx.pgclient  (Vert.x 5.x)
```

### 1.2 Configuration loading

`PeeGeeQConfiguration` (`peegeeq-db/src/main/java/dev/mars/peegeeq/db/config/PeeGeeQConfiguration.java`)
is the single entry point for all configuration. The profile is mandatory explicit configuration.
There is no ambient resolution from a `peegeeq.profile` system property or a `PEEGEEQ_PROFILE`
environment variable (`PeeGeeQConfiguration.java` L57–L60).

**Constructors** (the only two that exist):

| Constructor | Use case |
|---|---|
| `PeeGeeQConfiguration(String profile, Properties overrides)` (L74) | Production and tests. Loads the profile defaults, then applies every entry in `overrides`. Never writes to `System.getProperties()`. |
| `PeeGeeQConfiguration(String profile, String dbHost, int dbPort, String dbName, String dbUsername, String dbPassword, String dbSchema)` (L110) | Used by `PeeGeeQDatabaseSetupService`. `dbSchema` must be non-null and non-blank. |

**Named profiles** (files in `peegeeq-db/src/main/resources/`):

| Profile | File |
|---|---|
| `default` | `peegeeq-default.properties` |
| `development` | `peegeeq-development.properties` |
| `production` | `peegeeq-production.properties` |
| `reliable` | `peegeeq-reliable.properties` |
| `high-throughput` | `peegeeq-high-throughput.properties` |
| `high-performance` | `peegeeq-high-performance.properties` |
| `low-latency` | `peegeeq-low-latency.properties` |
| `extreme-performance` | `peegeeq-extreme-performance.properties` |
| `vertx5-optimized` | `peegeeq-vertx5-optimized.properties` |
| `bitemporal-optimized` | `peegeeq-bitemporal-optimized.properties` |
| `parallel-test` | `peegeeq-parallel-test.properties` |

**Connection and pool keys** (from `peegeeq-default.properties`):

```properties
# Connection
peegeeq.database.host=localhost
peegeeq.database.port=5432
peegeeq.database.proxy.host=
peegeeq.database.proxy.port=
peegeeq.database.name=peegeeq
peegeeq.database.username=peegeeq
peegeeq.database.password=peegeeq
peegeeq.database.schema=myschema
peegeeq.database.ssl.enabled=false

# Pool
peegeeq.database.pool.max-size=32
peegeeq.database.pool.max-wait-queue-size=128
peegeeq.database.pool.connection-timeout-ms=30000
peegeeq.database.pool.idle-timeout-ms=600000
peegeeq.database.pool.shared=true
peegeeq.database.pool.wait-queue-multiplier=10
```

**Proxy endpoint.** `peegeeq.database.proxy.host` and `peegeeq.database.proxy.port` are the
proxy endpoint. When they are non-blank, `PeeGeeQConfiguration.getDatabaseConfig()` builds the
effective `PgConnectionConfig` from them instead of from `peegeeq.database.host` / `.port`
(`getEffectiveDatabaseHost()` / `getEffectiveDatabasePort()`, L512–L523). A non-blank proxy port
is range-validated (L282–L295). Both the pooled connections and the dedicated LISTEN/NOTIFY
connection resolve through this one effective configuration: `PgConnectionManager` builds the
pool from it, and `PgBiTemporalEventStore.createConnectOptionsFromPeeGeeQManager()` builds the
`ReactiveNotificationHandler` connect options from
`PgClientFactory.getConnectionConfig(clientId)`. The environment-variable form is
`PEEGEEQ_DATABASE_PROXY_HOST` / `PEEGEEQ_DATABASE_PROXY_PORT`.

**Schema.** Every deployment supplies its own schema. The shipped value `myschema` is a
deliberate placeholder (`peegeeq-default.properties` L13–L14). `PgConnectionConfig` rejects a
null or blank schema at build time (`PgConnectionConfig.java` L49–L53) and
`PeeGeeQConfiguration.validateConfiguration()` rejects a blank `peegeeq.database.schema`
(L302–L303).

After loading, `PeeGeeQConfiguration` passes the resolved `Properties` to
`PgConnectionConfig.Builder` (connection details) and `PgPoolConfig.Builder` (pool sizing).

### 1.3 How a pool is created

`PgConnectionManager.createReactivePool()` (L330–L386, called once per `serviceId` via `computeIfAbsent`):

```java
PgConnectOptions connectOptions = new PgConnectOptions()
    .setHost(connectionConfig.getHost())
    .setPort(connectionConfig.getPort())
    .setDatabase(connectionConfig.getDatabase())
    .setUser(connectionConfig.getUsername())
    .setPassword(connectionConfig.getPassword());
connectOptions.setSslMode(connectionConfig.isSslEnabled() ? SslMode.REQUIRE : SslMode.DISABLE);

String normalized = normalizeSearchPath(connectionConfig.getSchema());
Map<String, String> properties = new HashMap<>();
properties.put("search_path", normalized);
connectOptions.setProperties(properties);

PoolOptions poolOptions = new PoolOptions()
    .setMaxSize(poolConfig.getMaxSize())
    .setMaxWaitQueueSize(poolConfig.getMaxWaitQueueSize())
    .setConnectionTimeout(...)
    .setIdleTimeout(...)
    .setShared(poolConfig.isShared())
    .setName("peegeeq-pool-" + host + ":" + port + "/" + database + "?search_path=" + normalized);

Pool pool = PgBuilder.pool()
    .with(poolOptions)
    .connectingTo(connectOptions)
    .using(vertx)
    .build();
```

The pool name includes the full connection identity. Vert.x shared pools are keyed by (Vert.x
instance, pool name); an unnamed shared pool would collapse every pool on one Vert.x onto the
first `connectOptions` it saw.

### 1.4 Pool configuration defaults

| Property | Default (`peegeeq-default.properties`) |
|---|---|
| `peegeeq.database.pool.max-size` | 32 |
| `peegeeq.database.pool.max-wait-queue-size` | 128 |
| `peegeeq.database.pool.shared` | `true` |
| `peegeeq.database.pool.connection-timeout-ms` | 30 000 ms |
| `peegeeq.database.pool.idle-timeout-ms` | 600 000 ms (10 min) |
| `peegeeq.database.pool.wait-queue-multiplier` | 10 |

`PgPoolConfig.Builder` code defaults (before properties are applied, `PgPoolConfig.java`
L127–L131): `maxSize=16`, `maxWaitQueueSize=128`, `connectionTimeout=30 s`, `idleTimeout=10 min`,
`shared=true`.

### 1.5 Reconnection behaviour (main pool)

The Vert.x reactive pool does not proactively reconnect. When a connection is acquired
(`pool.getConnection()`, `pool.withConnection()`, `pool.withTransaction()`) and the underlying
TCP socket is dead, Vert.x discards the broken connection and opens a new one to the configured
host/port. There is no backoff, no retry loop, and no awareness of primary/replica topology.

**LISTEN/NOTIFY connections** (`ReactiveNotificationHandler` in `peegeeq-bitemporal`) hold a
single long-lived connection. When it drops, the handler reconnects with exponential backoff
(`ReactiveNotificationHandler.java` L87–L88, L440–L473):

- `MAX_RECONNECT_ATTEMPTS = 5`
- `BASE_RECONNECT_DELAY = 1000` ms; delay = `BASE_RECONNECT_DELAY * (1L << Math.min(attempt - 1, 5))`, so the cap is 32 s
- Resets the attempt counter to 0 on success
- A failed attempt schedules the next one until the limit is reached
- Guarded by the `shutdown` flag; no reconnect during intentional close

### 1.6 Schema isolation

`peegeeq.database.schema` is the configured schema. Three mechanisms apply it:

1. **Connect time.** `PgConnectionManager.createReactivePool()` writes the normalised value into
   `PgConnectOptions.setProperties("search_path", ...)`, so every connection in the pool starts
   with that `search_path` (L354–L357).
2. **Transaction-local.** `PgConnectionManager.withTransaction()` calls
   `applyTransactionSearchPath()`, which executes
   `SELECT set_config('search_path', $1, true)` on the transaction's connection before the
   caller's work runs (L251–L269). This covers transaction-pooling proxies that can route
   consecutive transactions to different backend sessions.
3. **Provisioning.** The base template `peegeeq-db/src/main/resources/db/templates/base/04-search-path.sql`
   executes `SET search_path TO {schema};` during setup creation.

`PgConnectionManager.normalizeSearchPath()` (L400–L418) accepts only the characters matched by
`[A-Za-z0-9_,\s]+` (letters, digits, underscore, comma, whitespace). Quoted identifiers and
`$user` are rejected. Comma-separated entries are trimmed and re-joined with `", "`.

The schema-per-setup contract is specified in
[PEEGEEQ_SCHEMA_CONFIGURATION_DESIGN.md](../schema-tenants-support/PEEGEEQ_SCHEMA_CONFIGURATION_DESIGN.md).

### 1.7 Connection string / URL patterns

| Usage | Pattern |
|---|---|
| Vert.x reactive pool | `PgConnectOptions` fields; no URL |
| Flyway migrations | `jdbc:postgresql://<host>:<port>/<db>?currentSchema=<schema>` via `PgConnectionConfig.getJdbcUrl()` (L91–L105) |

`getJdbcUrl()` is used only by migration tooling. It is never called on the reactive pool path.

---

## 2. Resilience Stack Active During a Failover Window

When the primary PostgreSQL goes down, three independent mechanisms activate in PeeGeeQ. They
operate on different timescales and are not coordinated with each other.

### 2.1 Vert.x pool: discard and reconnect

Timescale: **per request** (no polling, no background thread).

When `pool.getConnection()` or `pool.withConnection()` is called and the pooled TCP socket is
dead, Vert.x discards the broken connection and attempts a new TCP connection to the configured
host/port (the proxy address when a proxy is configured). There is no delay, no backoff, and no
counter; it either succeeds or returns a failed `Future`.

The pool does not suspend itself during a failover. Requests that arrive in the first ~1 s after
primary failure (before HAProxy has detected and switched) fail. Requests after the switch
succeed.

### 2.2 Pool-operation circuit breaker

`PgConnectionManager.executeWithPoolCircuitBreaker()` (L276–L305) brackets every
`withConnection()` and `withTransaction()` call with a Resilience4j `CircuitBreaker` named
`db.pool.<serviceId>`, obtained from `CircuitBreakerManager.getCircuitBreaker(...)`. Permission is
acquired before the pool call. Both acquisition failures and caller-operation failures are
recorded against the breaker. The original failure `Future` is returned unchanged. When the
breaker is open the call fails fast with `CallNotPermittedException`. One failed pool does not
block another, because each logical pool has its own breaker name. `getReactiveConnection()` is
deliberately unwrapped for callers that own a connection explicitly.

When `PgConnectionManager` is constructed without a `CircuitBreakerManager` (standalone use), the
breaker is absent and the pooled operation runs directly (L278–L283).

### 2.3 HealthCheckManager: detection and status

Timescale: **periodic** (`peegeeq.health.check-interval`, default `PT30S`).

`HealthCheckManager` runs `SELECT 1` against the pool on a `Vertx.setPeriodic()` timer
(`HealthCheckManager.java` L214, L266). Each check is wrapped by
`executeWithCircuitBreaker("database", ...)` (L506–L534), which uses its own breaker named
`database`, separate from the pool-operation breakers in §2.2. When that breaker is open,
subsequent checks short-circuit to `UNHEALTHY` without hitting the pool, and
`getOverallHealthAsync()` returns the cached state (L470–L475).

`HealthCheckManager` marks status. It does not stop the pool or prevent application code from
calling `pool.withConnection()`.

### 2.4 Application-tier failover: ConnectionRouter + Consul

Timescale: **per request on the application routing layer** (independent of the database tier).

This mechanism handles failover between PeeGeeQ application instances in a multi-node
deployment, not between PostgreSQL nodes. `ConnectionRouter`
(`peegeeq-service-manager/.../routing/ConnectionRouter.java`) routes incoming client requests to
a healthy PeeGeeQ instance discovered via `ConsulServiceDiscovery.discoverInstances()`:

```java
// In ConnectionRouter.routeGetRequest():
serviceDiscovery.discoverInstances()
    .compose(instances -> {
        PeeGeeQInstance selected = loadBalancer.selectInstance(instances, environment, region);
        if (selected == null) {
            return Future.failedFuture("No healthy instances available");
        }
        return routeRequestWithRetry(selected, path, instances, environment, region, 0);
    });
```

`LoadBalancer` supports `ROUND_ROBIN` and other strategies. `routeRequestWithRetry` attempts up
to `maxRetries` (default 3) additional instances before failing.

When a PeeGeeQ instance loses its database connection and its `/health` endpoint returns `DOWN`,
Consul deregisters it. `discoverInstances()` stops returning that instance, and `ConnectionRouter`
routes to the remaining healthy instances. Each surviving instance manages its own pool
independently.

### 2.5 Combined failover sequence (HAProxy + Consul cluster)

```
t=0 s    Primary PostgreSQL goes down.

t≈0 s    Vert.x pools on all instances: in-flight requests fail immediately.
          Business operations receive Future failures. The db.pool.<serviceId>
          breaker records them and opens after its configured threshold.

t≈1 s    HAProxy detects primary down (fall=2 × inter=500ms).
          HAProxy starts routing new TCP connections to secondary (backup).
          Vert.x pools: new connection attempts now succeed to secondary.
          Pool breakers move HALF_OPEN then CLOSED as probes succeed.

t≈30 s   HealthCheckManager periodic check fires on each instance.
          SELECT 1 succeeds (now reaching secondary via HAProxy).
          Health status returns to UP. The "database" breaker resets.

Consul:   /health endpoint on each instance reflects UP again.

t=N      Primary returns. HAProxy detects recovery (rise=1, ~500 ms).
          Traffic fails back to primary. Vert.x pool opens new connections to primary.
```

---

## 3. Application-Tier Failover vs Database-Tier Failover

These are orthogonal concerns.

| Concern | Mechanism | Scope |
|---|---|---|
| PostgreSQL primary → secondary | HAProxy / VIP / RDS endpoint | Database tier, infrastructure |
| Pool reconnection | Vert.x pool implicit per-request, guarded by `db.pool.<serviceId>` breaker | Database client tier |
| Health status reporting | `HealthCheckManager` + `database` breaker | Application tier, observability |
| PeeGeeQ instance A → instance B | `ConnectionRouter` + Consul | Application tier, routing |

A production deployment addresses both planes:

1. Place an HAProxy, Patroni VIP, or managed-service writer endpoint between PeeGeeQ and
   PostgreSQL. Set `peegeeq.database.proxy.host` / `peegeeq.database.proxy.port` to that address.
2. Run multiple PeeGeeQ instances registered with Consul. `ConnectionRouter` handles
   application-level failover.

---

## 4. Why the Vert.x Pool Needs an External Proxy for Failover

`PgBuilder.pool().connectingTo(connectOptions)` fixes a single host/port in the pool at
construction time. Changing where that pool connects requires rebuilding it, which means
restarting the `PeeGeeQManager`.

An external TCP proxy (HAProxy) solves this at the network layer:

```
Vert.x pool
  │  host=haproxy, port=5400   ← fixed, never changes
  ▼
HAProxy
  ├── pg_primary:5432   (active, health-checked every 500 ms)
  └── pg_secondary:5432 (backup — activated when primary is DOWN)
```

When the primary fails:

1. HAProxy detects failure after 2 consecutive failed checks (~1 s with `fall=2 inter=500ms`).
2. HAProxy promotes the secondary backup and starts routing new TCP connections to it.
3. The Vert.x pool's existing connections to the dead primary fail on next use; Vert.x discards
   them and opens new connections, which HAProxy now routes to the secondary.
4. No application restart. No pool rebuild. No code change.

Auto-recovery: when the primary returns, HAProxy detects recovery after 1 successful check
(`rise=1`) and resumes routing to it; the backup reverts to standby.

**Why the same port on both backends is not a problem**: HAProxy distinguishes backends by
hostname or IP address, not by port number. The servers are separate hosts that resolve to
different IP addresses. The client only ever sees the single proxy address.

**HAProxy does not require Patroni**: HAProxy alone, using `pgsql-check`, is sufficient to
detect a dead primary and route to the backup. Patroni is an optional enhancement. When Patroni
manages the cluster, it exposes a REST API on each node (default port 8008) that HAProxy can use
as a semantically precise health check:

```haproxy
# Patroni-aware health check — only routes to the Patroni-elected write primary
server pg1 pg1:5432 check port 8008 httpchk GET /primary
server pg2 pg2:5432 check port 8008 httpchk GET /primary backup
```

`peegeeq-pg-sidecar` (§8) provides the same `/primary` endpoint without Patroni.

---

## 5. HAProxy Failover Integration Test

### 5.1 Files

| File | Purpose |
|---|---|
| `peegeeq-db/src/test/resources/haproxy-failover.cfg` | HAProxy config mounted into the container at test startup |
| `peegeeq-db/src/test/resources/haproxy-check-init.sql` | Creates the `haproxy_check` user on each PostgreSQL node (`withInitScript`) |
| `peegeeq-db/src/test/java/dev/mars/peegeeq/db/resilience/HaProxyConnectionFailoverTest.java` | The integration test (`@Tag(TestCategories.INTEGRATION)`) |

### 5.2 Container topology (Testcontainers)

```
Docker bridge network (created per test class run)
  │
  ├── pg_primary   (PostgreSQLContainer, alias "pg_primary")
  ├── pg_secondary (PostgreSQLContainer, alias "pg_secondary")
  ├── primary2     (PostgreSQLContainer, alias "pg_primary"; prepared, started in Phase 5)
  └── haproxy      (GenericContainer "haproxy:2.8-alpine")
        │  mounts haproxy-failover.cfg from classpath
        └── exposes 5400 → random host port
```

`PgConnectionManager` is pointed at `haproxy.getHost() : haproxy.getMappedPort(5400)`. It never
knows the actual PostgreSQL host/port.

### 5.3 Test phases

The class runs six ordered phases (`@TestMethodOrder(OrderAnnotation.class)`,
`HaProxyConnectionFailoverTest.java` L317–L581):

| Phase | What it verifies |
|---|---|
| 1 | `SELECT 1` succeeds through HAProxy while the primary is healthy. |
| 2 | `withTransaction` DDL + DML round-trip via HAProxy (temp table). |
| 3 | `withTransaction` rollback leaves no row. |
| 4 | `PgConnectionManager.checkHealth()` returns true via HAProxy. |
| 5 | Failback. Stops the primary, waits `HAPROXY_FAILOVER_WAIT_MS` (4 000 ms), confirms the secondary answers, starts `primary2` with the same alias `pg_primary`, waits `HAPROXY_FAILBACK_WAIT_MS` (8 000 ms), and confirms the pool serves queries again. |
| 6 | Failover (destructive). Stops the active primary, waits 4 000 ms, and asserts `SELECT 1` succeeds through HAProxy on the secondary. |

Phases 5 and 6 use `queryWithRetry()` with `MAX_RETRY_ATTEMPTS = 8` at `RETRY_INTERVAL_MS = 1 000`.

> **Production note**: in production, primary and secondary share data via PostgreSQL streaming
> replication. This test uses two independent nodes because it targets connection-level
> resilience, not data consistency. `HaProxyStreamingReplicationFailoverTest` (§7) covers the
> streaming-replication case.

### 5.4 Retry design (no `.recover()`)

`.recover()` is banned in this codebase (see
[pgq-coding-principles.md §5](../dev/pgq-coding-principles.md#5-the-recover-ban)). The retry loop
uses `Promise<Integer>` + `vertx.setTimer()` + `.onSuccess()` / `.onFailure()`
(`HaProxyConnectionFailoverTest.java` L648–L675):

```java
private void scheduleAttempt(Vertx vertx, Pool pool, int maxAttempts, int attempt,
                             long delayMs, Promise<Integer> result) {
    pool.query("SELECT 1 AS health").execute()
        .onSuccess(rows -> result.complete(rows.iterator().next().getInteger("health")))
        .onFailure(err -> {
            if (attempt >= maxAttempts) {
                result.fail(err);
            } else {
                vertx.setTimer(delayMs, id ->
                    scheduleAttempt(vertx, pool, maxAttempts, attempt + 1, delayMs, result));
            }
        });
}
```

State is carried in a `Promise`, control flow in `.onSuccess()`/`.onFailure()`, delays via
`vertx.setTimer()`.

### 5.5 Running the test

```powershell
mvn test -Pintegration-tests -pl :peegeeq-db "-Dtest=HaProxyConnectionFailoverTest" 2>&1 | Tee-Object -FilePath logs\peegeeq-db-haproxy-failover-20261006.log
```

Requirements: Docker Desktop running. The test pulls `haproxy:2.8-alpine` on first run.

### 5.6 HAProxy config (annotated)

`peegeeq-db/src/test/resources/haproxy-failover.cfg`:

```haproxy
global
    maxconn 200
    log     stdout format raw local0 info

defaults
    mode              tcp          # PostgreSQL is a raw TCP protocol
    timeout connect   5s
    timeout client    60s
    timeout server    60s
    option            tcplog

frontend pg_frontend
    bind *:5400
    default_backend pg_backends

backend pg_backends
    balance           leastconn   # good for long-lived PG connections

    # pgsql-check sends a PostgreSQL startup packet to each backend.
    # Any protocol-level response (including "wrong password") proves PostgreSQL
    # is serving the wire protocol, not just accepting a TCP connection.
    # Requires a 'haproxy_check' user to exist in PostgreSQL (no password needed).
    option            pgsql-check user haproxy_check

    # fall=2: mark DOWN after 2 consecutive failed checks (~1 s at inter=500ms)
    # rise=1: recover after 1 successful check
    server pg_primary   pg_primary:5432   check inter 500ms fall 2 rise 1
    server pg_secondary pg_secondary:5432 check inter 500ms fall 2 rise 1 backup
```

**`haproxy_check` user**: HAProxy sends a PostgreSQL startup message with this username.
PostgreSQL responds with an authentication challenge. The user needs no password, no schema
access, and no database privileges. It is created by `haproxy-check-init.sql` via
`withInitScript()` on each Testcontainers node; in the local docker-compose stack it is mounted
as `/docker-entrypoint-initdb.d/init-haproxy-check.sql`.

---

## 6. Local Developer Environment (HAProxy + PgBouncer)

For manual failover testing without running a JVM test, a docker-compose stack is provided.

**Files:**

| File | Purpose |
|---|---|
| `scripts/local-infra/docker-compose-failover-local.yml` | Full stack definition |
| `scripts/local-infra/haproxy-failover-local.cfg` | HAProxy config with stats page |
| `scripts/local-infra/init-haproxy-check.sql` | Creates the `haproxy_check` PostgreSQL user on first start |

**Topology:**

```
Application (PeeGeeQ) or psql client
  │  host=localhost, port=6432 (session pooling) or 6433 (transaction pooling, optional profile)
  ▼
PgBouncer         (edoburu/pgbouncer:v1.25.2-p0)
  │  session pool on :6432: up to 200 client connections, 20 server connections
  │  transaction pool on :6433: compose profile "transaction-pool", DISCARD ALL after every release
  │  both connect to haproxy:5400 (not directly to PostgreSQL)
  ▼
HAProxy:5400     (haproxy:2.8-alpine, stats on :8404)
  ├──► pg-primary:5432    (postgres:15.13-alpine3.20, exposed :5433)
  └──► pg-secondary:5432  (postgres:15.13-alpine3.20, exposed :5434)
```

Both PgBouncer instances set `IGNORE_STARTUP_PARAMETERS: extra_float_digits,search_path`. The
Vert.x client sends `search_path` as a startup parameter; PgBouncer ignores it, and PeeGeeQ
reapplies the schema as transaction-local state (§1.6). The transaction-mode instance
(`pgbouncer-transaction`, compose profile `transaction-pool`) adds `POOL_MODE: transaction`,
`MAX_PREPARED_STATEMENTS: 32`, `SERVER_RESET_QUERY: DISCARD ALL`, and
`SERVER_RESET_QUERY_ALWAYS: "1"` (`docker-compose-failover-local.yml` L164–L188).

**Stats page**: `http://localhost:8404/stats` shows live health of both backends.

**Start:**
```powershell
docker compose -f scripts/local-infra/docker-compose-failover-local.yml up -d
# With the transaction-mode PgBouncer as well:
docker compose -f scripts/local-infra/docker-compose-failover-local.yml --profile transaction-pool up -d
```

**Simulate failover:**
```powershell
# Stop primary — HAProxy detects failure in ~1 s, routes to secondary
docker compose -f scripts/local-infra/docker-compose-failover-local.yml stop pg-primary

# Connect through PgBouncer (or HAProxy direct on :5400)
psql -h localhost -p 6432 -U peegeeq_dev -d peegeeq_dev -c "SELECT 1"

# Restore primary — HAProxy auto-recovers, traffic returns to primary
docker compose -f scripts/local-infra/docker-compose-failover-local.yml start pg-primary
```

---

## 7. Verified Coverage

The resilience gaps recorded in earlier revisions of this document were closed by Task 3 of the
[consolidated task register](../tasks/tasks.md#3-postgresqlhaproxy-resilience-gaps) on
2026-09-02. The following tests are the evidence. Run them with the `-Pintegration-tests`
profile and a `-Dtest=` scope as in §5.5.

| Test class | Module path | Contract |
|---|---|---|
| `PgPoolCircuitBreakerIntegrationTest` | `peegeeq-db/src/test/java/dev/mars/peegeeq/db/resilience/` | Per-pool `db.pool.<serviceId>` breaker opens after terminal failures behind a stopped node, rejects with `CallNotPermittedException`, leaves another pool usable, and recovers HALF_OPEN then CLOSED against a replacement node through a fixed HAProxy endpoint |
| `HaProxyNotificationFailoverIntegrationTest` | `peegeeq-bitemporal/src/test/java/dev/mars/peegeeq/bitemporal/` | Pooled and dedicated LISTEN connections share the configured proxy endpoint; after primary loss the LISTEN channel is replayed and delivery resumes through HAProxy |
| `HaProxyStreamingReplicationFailoverTest` | `peegeeq-db/src/test/java/dev/mars/peegeeq/db/resilience/` | A physical standby created with `pg_basebackup -R` retains a committed marker across explicit primary fencing and promotion, and accepts a post-promotion write through HAProxy |
| `PgBouncerTransactionModeTest` | `peegeeq-db/src/test/java/dev/mars/peegeeq/db/resilience/` | Two logical clients with distinct tenant schemas multiplex one PgBouncer backend connection across alternating transactions with correct schema and payload isolation |

Promotion and fencing remain explicit operational decisions. Neither the fixtures nor the
runtime implement automatic promotion; automatic promotion without an external fencing authority
can create split brain. See the Consul-monitor design linked in the options table for the
proposed automatic path.

---

## 8. Using the pg-sidecar Service (`peegeeq-pg-sidecar`)

`peegeeq-pg-sidecar` is the project's HTTP health-check sidecar. It exposes a single endpoint:

```
GET /primary
  → HTTP 200   when pg_is_in_recovery() = false  (this node is the write primary)
  → HTTP 503   when pg_is_in_recovery() = true   (replica) or PostgreSQL is unreachable
```

Any other path returns HTTP 404. Deploy one sidecar process alongside each PostgreSQL node.
HAProxy calls the endpoint on the local sidecar to decide whether to route writes to that node.
The module's own guide is
[peegeeq-pg-sidecar/docs/PEEGEEQ_PG_SIDECAR.md](../../peegeeq-pg-sidecar/docs/PEEGEEQ_PG_SIDECAR.md).

### 8.1 Where it fits in the stack

```
PeeGeeQ application
  │  peegeeq.database.proxy.host=haproxy, peegeeq.database.proxy.port=5400
  ▼
HAProxy:5400
  ├──► pg-node-1:5432   check port 8008  httpchk GET /primary
  │       sidecar:8008 ──► pg-node-1:5432  SELECT pg_is_in_recovery()
  └──► pg-node-2:5432   check port 8008  httpchk GET /primary backup
          sidecar:8008 ──► pg-node-2:5432  SELECT pg_is_in_recovery()
```

### 8.2 Configuration

The sidecar reads JVM system properties only (`PgPrimaryCheckMain.java` L32–L38). No
configuration file and no environment-variable layer exist.

| Property | Default | Description |
|---|---|---|
| `pg.host` | `localhost` | PostgreSQL host (the local node address) |
| `pg.port` | `5432` | PostgreSQL port |
| `pg.database` | `postgres` | Database to connect to |
| `pg.user` | `haproxy_check` | PostgreSQL user |
| `pg.password` | *(empty)* | Password (empty for the no-password user) |
| `http.port` | `8008` | Port the sidecar HTTP server listens on |

The sidecar uses a pool of 2 connections to the local node.

### 8.3 PostgreSQL user setup (one-time, per node)

The `haproxy_check` user requires only the ability to connect and call `pg_is_in_recovery()`.

```sql
CREATE USER haproxy_check WITH PASSWORD '' CONNECTION LIMIT 3;
-- pg_is_in_recovery() is a built-in function; no GRANT is needed.
-- Optionally restrict to the postgres database only:
REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
GRANT  CONNECT ON DATABASE postgres TO haproxy_check;
```

In Testcontainers, supply this as an init script via `.withInitScript("haproxy-check-init.sql")`.
In docker-compose, mount it as `/docker-entrypoint-initdb.d/init-haproxy-check.sql`.

### 8.4 Build and run

Module: `peegeeq-pg-sidecar` (artifact `peegeeq-pg-sidecar`, version `1.0-SNAPSHOT`, Maven
profile `native` for the GraalVM build). Commands run from the repository root.

#### Fat-jar (JVM, JDK 21+)

```powershell
mvn package -pl :peegeeq-pg-sidecar -DskipTests 2>&1 | Tee-Object -FilePath logs\pg-sidecar-build-20261006.log

java `
  -Dpg.host=localhost `
  -Dpg.port=5432 `
  -Dpg.database=postgres `
  -Dpg.user=haproxy_check `
  -Dpg.password= `
  -Dhttp.port=8008 `
  -jar peegeeq-pg-sidecar/target/peegeeq-pg-sidecar-1.0-SNAPSHOT.jar
```

#### Native binary (GraalVM JDK 21+ with `native-image`)

```powershell
mvn package -Pnative -pl :peegeeq-pg-sidecar -DskipTests 2>&1 | Tee-Object -FilePath logs\pg-sidecar-native-20261006.log
# Output: peegeeq-pg-sidecar\target\peegeeq-pg-sidecar.exe  (Windows)
#         peegeeq-pg-sidecar/target/peegeeq-pg-sidecar       (Linux/macOS)
```

Run (Windows):
```powershell
.\peegeeq-pg-sidecar\target\peegeeq-pg-sidecar.exe `
  -Dpg.host=localhost `
  -Dpg.port=5432 `
  -Dpg.user=haproxy_check `
  -Dpg.password= `
  -Dhttp.port=8008
```

Run (Linux/macOS):
```bash
./peegeeq-pg-sidecar/target/peegeeq-pg-sidecar \
  -Dpg.host=localhost \
  -Dpg.port=5432 \
  -Dpg.user=haproxy_check \
  -Dpg.password= \
  -Dhttp.port=8008
```

#### Container image (native binary, distroless)

```dockerfile
# Stage 1: build the native binary
FROM ghcr.io/graalvm/native-image-community:21 AS builder
WORKDIR /build
COPY . .
RUN mvn package -Pnative -pl :peegeeq-pg-sidecar -DskipTests

# Stage 2: distroless runtime — no JVM, no shell
FROM gcr.io/distroless/base-debian12
COPY --from=builder /build/peegeeq-pg-sidecar/target/peegeeq-pg-sidecar /app/peegeeq-pg-sidecar
EXPOSE 8008
ENTRYPOINT ["/app/peegeeq-pg-sidecar"]
```

The binary reads `-D` system properties from its command line, exactly as in the native run
above. Pass them as container arguments after the image name; they are appended to the
`ENTRYPOINT`. Environment variables such as `-e pg.host=...` do not reach the process.

```bash
docker build -t peegeeq-pg-sidecar:latest .

docker run --rm -p 8008:8008 peegeeq-pg-sidecar:latest \
  -Dpg.host=db-node-1 -Dpg.port=5432 -Dpg.user=haproxy_check -Dpg.password= -Dhttp.port=8008
```

### 8.5 HAProxy configuration for HTTP-based primary detection

Replace the `option pgsql-check` line with `option httpchk` pointing at the sidecar:

```haproxy
backend pg_write
    balance           leastconn
    option            httpchk GET /primary
    http-check        expect status 200

    # One sidecar per PostgreSQL node, listening on port 8008.
    server pg1 pg1:5432 check port 8008 inter 500ms fall 2 rise 1
    server pg2 pg2:5432 check port 8008 inter 500ms fall 2 rise 1 backup
```

HAProxy contacts `pg1:8008/primary` and `pg2:8008/primary` every 500 ms. Only the node whose
sidecar returns HTTP 200 receives write traffic. The backup becomes active when the primary's
sidecar returns HTTP 503 twice in a row (`fall=2`).

### 8.6 PeeGeeQ application configuration

Point PeeGeeQ at HAProxy through the proxy keys:

```properties
# profile properties file or Properties overrides
peegeeq.database.proxy.host=haproxy-host   # HAProxy address, not the PostgreSQL node
peegeeq.database.proxy.port=5400           # HAProxy frontend port
peegeeq.database.name=peegeeq
peegeeq.database.username=peegeeq
peegeeq.database.password=peegeeq
peegeeq.database.schema=<your-schema>      # every deployment supplies its own schema
```

The `PgConnectionManager` pool is built once from the effective configuration. Reconnects and
failovers happen through the HAProxy address; no code change or pool restart is required when
PostgreSQL failover occurs.

### 8.7 Verifying the sidecar

```powershell
Invoke-WebRequest -Uri http://localhost:8008/primary -Method GET | Format-List StatusCode, StatusDescription
# Primary node  → StatusCode 200
# Replica node  → StatusCode 503
```

```bash
curl -o /dev/null -s -w "%{http_code}\n" http://localhost:8008/primary
```

Unknown paths return HTTP 404 (`http://localhost:8008/health` → 404).

### 8.8 Running the sidecar tests

`PgPrimaryCheckIntegrationTest` (`peegeeq-pg-sidecar/src/test/java/dev/mars/peegeeq/sidecar/`,
`@Tag(TestCategories.INTEGRATION)`) starts a real PostgreSQL container, deploys the verticle, and
verifies the 200, 404, and 503 responses. `PgPrimaryCheckLifecycleTest` is in the same package.

```powershell
mvn test -Pintegration-tests -pl :peegeeq-pg-sidecar 2>&1 | Tee-Object -FilePath logs\pg-sidecar-integration-20261006.log
```

Requirements: Docker Desktop running.

---

## Appendix A: HAProxy Primary Detection Options

The long-form comparison, including the Patroni internals and the shell `agent-check` script, is
archived at
[PG_HAPROXY_PRIMARY_DETECTION_OPTIONS.md](../_archived/superseded-guides/PG_HAPROXY_PRIMARY_DETECTION_OPTIONS.md).

`option pgsql-check` and plain TCP checks verify that PostgreSQL is alive. Both the primary and
any replica pass them. Only something that queries `SELECT pg_is_in_recovery()` can tell HAProxy
which node is the write primary.

| Approach | Complexity | Extra process required | Automatic promotion | Split-brain safe |
|----------|------------|------------------------|--------------------|--------------------|
| Custom HTTP sidecar (`peegeeq-pg-sidecar`, §8) | Very low | Yes: one Vert.x process per node | No | No |
| HAProxy `agent-check` | Low | Yes: shell + socat/xinetd | No | No |
| repmgr + repmgrd | Medium | Yes: repmgrd daemon | Yes (with witness) | Partial |
| Consul-based monitor ([PEEGEEQ_FAILOVER_CONSUL_DESIGN.md](../../peegeeq-service-manager/docs/PEEGEEQ_FAILOVER_CONSUL_DESIGN.md), proposed) | Medium | No (uses existing Consul) | Yes | Yes (with fencing) |
| Patroni | High | Yes: Patroni + DCS | Yes | Yes |

**Custom HTTP sidecar.** Replicates Patroni's `/primary` endpoint: `SELECT pg_is_in_recovery()`
→ 200 for primary, 503 for replica. HAProxy routes writes only to the current primary. It does
not promote a standby and does not prevent a recovered old primary from accepting writes.
Appropriate when promotion is manual and downtime until operator action is acceptable.

**HAProxy `agent-check`.** An agent process on each node answers `up` or `down` over a plain TCP
port; HAProxy combines it with the regular health check. Same guarantees and limits as the HTTP
sidecar, implemented with a shell script instead of a Java process.

**repmgr + repmgrd.** A traditional replication manager that promotes automatically without an
external DCS. A witness node is needed for quorum. It exposes no `httpchk`-compatible endpoint, so
HAProxy integration still needs a sidecar.

**Consul-based monitor.** The proposed PeeGeeQ-native path. It would re-use the Consul cluster
that `peegeeq-service-manager` already requires and run fully reactive on
`io.vertx.ext.consul.ConsulClient`. No code exists for it yet.

**Patroni.** The industry-standard stack: DCS leader election, automatic promotion, fencing, and
`pg_rewind`. Highest operational complexity.

**Recommendation for PeeGeeQ:**

| Scenario | Approach |
|----------|----------|
| Development / single-node | `option pgsql-check` (already in the HAProxy test config) |
| Production, manual failover acceptable | `peegeeq-pg-sidecar` + HAProxy `httpchk` (§8) |
| Production, automatic failover required | Consul-based monitor (proposed) or Patroni |
| Production, no Consul, 3 nodes available | repmgr + witness node |

---

## Appendix B: The JDBC Multi-Host Failover Pattern (Historical Reference)

The PostgreSQL JDBC driver (42.2+) supports a multi-host connection URL:

```
jdbc:postgresql://host1:5432,host2:5432/database?targetServerType=primary
```

The driver iterates the host list on each connection attempt, queries `pg_is_in_recovery()`
internally, and connects to the first node matching `targetServerType` (`primary`,
`preferPrimary`, `secondary`, `any`).

**Why it is not used in PeeGeeQ.** PeeGeeQ is a reactive system built on Vert.x. JDBC is a
blocking protocol; every query occupies a thread until a response arrives. PeeGeeQ forbids JDBC
on the runtime path (`DriverManager`, `PreparedStatement`, `ResultSet`, and JDBC URL strings).
The Vert.x reactive pgclient configures hosts via `PgConnectOptions` and has no equivalent of
`targetServerType`. The only JDBC URL in the codebase is `PgConnectionConfig.getJdbcUrl()`, used
by Flyway migration tooling.

**Why it is not a sound pattern for distributed systems.**

1. Uncoordinated failover: each service instance iterates the host list independently, so
   different instances may reach different nodes during the promotion window.
2. No fencing: a recovered old primary can accept writes concurrently with the new primary.
3. Per-process host-list management: adding or removing a node requires reconfiguring and
   restarting every service instance.
4. Promotion race: all instances exhaust their retry loops at the same time and reconnect to the
   new primary in a burst.
5. No ongoing primary check: a long-lived pool keeps connections to a demoted node and receives
   `ERROR: cannot execute ... in a read-only transaction`.

An external proxy (HAProxy) or a monitor with a distributed lock (Consul) is the correct
abstraction boundary for database-tier failover in a multi-service architecture. The proxy is the
single shared point of truth about which node is the primary.
