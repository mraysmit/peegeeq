# PostgreSQL Connection Management and HAProxy Failover Test Design

**Author**: Mark A Ray-Smith Cityline Ltd.
**Date**: Aug 26, 2025  
**Status**: COMPLETE  
**Version**: 1.1 (updated May 6, 2026 — incorporates architecture review)

---

## Executive Summary

PeeGeeQ connects to PostgreSQL exclusively via the Vert.x 5.x reactive client (`io.vertx.pgclient`).
No JDBC, no HikariCP, no connection URL strings.
Connection options — host, port, database, credentials, and `search_path` — are set individually on
`PgConnectOptions`; the pool is built once per `serviceId` and cached in a `ConcurrentHashMap`.

The pool itself has **no built-in failover**.  When a backend goes away, Vert.x discards broken
connections on the next acquisition attempt and opens new ones.  Whether those new connections
succeed depends entirely on what the pool's configured host/port resolves to at that moment —
which is precisely the problem an external TCP proxy solves.

**Design principle confirmed by architecture review**: PeeGeeQ deliberately delegates
database-tier failover to infrastructure.  The application tier handles its own node-level
failover (via `ConnectionRouter` + Consul) independently of the database tier.  These two
planes do not need to know about each other.

This document records:

1. The full connection management architecture as found in the codebase.
2. The full resilience stack active during a failover window.
3. Application-tier vs database-tier failover — how the two planes relate.
4. Why the Vert.x pool requires an external proxy for database failover.
5. The design decisions behind the HAProxy failover integration test.
6. The local developer docker-compose topology (HAProxy + PgBouncer).
7. Known gaps and future work.

`HealthCheckManager` updates a health status map and optionally short-circuits its own
periodic checks via the circuit breaker — it does not stop the `Pool` or gate business
operations.  The pool continues serving requests throughout the failover window; requests that
arrive before HAProxy switches fail, and those after succeed.  The circuit breaker in this
codebase guards health-check queries, not business queries.

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

`PeeGeeQConfiguration` is the single entry point for all configuration.  It resolves values
through a fixed four-layer priority chain (lowest to highest):

```
1. peegeeq-default.properties        (classpath, always loaded)
2. peegeeq-<profile>.properties      (classpath, profile-specific overrides)
3. Environment variables             (PEEGEEQ_* prefix, converted to property keys)
4. System properties                 (-Dpeegeeq.* JVM args — highest priority)
```

**Profile selection** — evaluated once at construction time:

```
System.getProperty("peegeeq.profile")   →  env PEEGEEQ_PROFILE   →  fallback "default"
```

**Named profiles** (files in `peegeeq-db/src/main/resources/`):

| Profile | File | Tuned for |
|---|---|---|
| `default` | `peegeeq-default.properties` | local development |
| `development` | `peegeeq-development.properties` | local dev variant |
| `production` | `peegeeq-production.properties` | production workload |
| `reliable` | `peegeeq-reliable.properties` | durability-first |
| `high-throughput` | `peegeeq-high-throughput.properties` | throughput-first |
| `high-performance` | `peegeeq-high-performance.properties` | low-latency |
| `low-latency` | `peegeeq-low-latency.properties` | low-latency |
| `extreme-performance` | `peegeeq-extreme-performance.properties` | benchmarks |
| `vertx5-optimized` | `peegeeq-vertx5-optimized.properties` | Vert.x 5 tuning |
| `bitemporal-optimized` | `peegeeq-bitemporal-optimized.properties` | bitemporal workload |
| `parallel-test` | `peegeeq-parallel-test.properties` | parallel test isolation |

**Default property values** (from `peegeeq-default.properties`):

```properties
# Connection
peegeeq.database.host=localhost
peegeeq.database.port=5432
peegeeq.database.name=peegeeq
peegeeq.database.username=peegeeq
peegeeq.database.password=peegeeq
peegeeq.database.schema=public
peegeeq.database.ssl.enabled=false

# Pool
peegeeq.database.pool.min-size=8
peegeeq.database.pool.max-size=32
peegeeq.database.pool.shared=true
peegeeq.database.pool.connection-timeout-ms=30000
peegeeq.database.pool.idle-timeout-ms=600000
peegeeq.database.pool.max-lifetime-ms=1800000
peegeeq.database.pool.auto-commit=true
```

**Constructor variants** of `PeeGeeQConfiguration`:

| Constructor | Use case |
|---|---|
| `PeeGeeQConfiguration()` | Production — auto-detects profile |
| `PeeGeeQConfiguration(profile)` | Explicit profile |
| `PeeGeeQConfiguration(profile, Properties overrides)` | Tests — isolated override map, never writes to `System.getProperties()` |
| `PeeGeeQConfiguration(profile, host, port, name, user, password, schema)` | Testcontainers setup — avoids `System.setProperty` race conditions between parallel tests |

After loading, `PeeGeeQConfiguration` passes the resolved `Properties` map to
`PgConnectionConfig.Builder` (connection details) and `PgPoolConfig.Builder` (pool sizing),
both of which read their specific keys and apply safe defaults for any missing values.

---

### 1.3 How a pool is created

`PgConnectionManager.createReactivePool()` (called once per `serviceId` via `computeIfAbsent`):

```java
PgConnectOptions connectOptions = new PgConnectOptions()
    .setHost(connectionConfig.getHost())
    .setPort(connectionConfig.getPort())
    .setDatabase(connectionConfig.getDatabase())
    .setUser(connectionConfig.getUsername())
    .setPassword(connectionConfig.getPassword())
    .setSslMode(sslEnabled ? SslMode.REQUIRE : SslMode.DISABLE)
    .setProperties(Map.of("search_path", schema));  // per-tenant schema isolation

PoolOptions poolOptions = new PoolOptions()
    .setMaxSize(poolConfig.getMaxSize())
    .setMaxWaitQueueSize(poolConfig.getMaxWaitQueueSize())
    .setConnectionTimeout(...)
    .setIdleTimeout(...)
    .setShared(poolConfig.isShared());

Pool pool = PgBuilder.pool()
    .with(poolOptions)
    .connectingTo(connectOptions)
    .using(vertx)
    .build();
```

`search_path` is injected at the `PgConnectOptions` level so every connection from the pool
inherits the correct schema automatically.  This is the project's single source of truth for
schema isolation; it must never be duplicated in SQL strings.

### 1.4 Pool configuration defaults

| Property | Default (properties file) | Integration-test override |
|---|---|---|
| `peegeeq.database.pool.max-size` | 32 | 3 |
| `peegeeq.database.pool.shared` | `true` | `false` |
| `peegeeq.database.pool.connection-timeout-ms` | 30 000 ms | 30 000 ms |
| `peegeeq.database.pool.idle-timeout-ms` | 600 000 ms (10 min) | 5 000 ms |

`PgPoolConfig.Builder` code defaults (before properties are applied): `maxSize=16`,
`maxWaitQueueSize=128`, `connectionTimeout=30 s`, `idleTimeout=10 min`, `shared=true`.

### 1.5 Reconnection behaviour (main pool)

The Vert.x reactive pool does **not** proactively reconnect.  When a connection is
acquired (`pool.getConnection()`, `pool.withConnection()`, `pool.withTransaction()`) and the
underlying TCP socket is dead, Vert.x discards the broken connection and opens a new one to the
configured host/port.  There is no backoff, no retry loop, and no awareness of primary/replica
topology.

**Exception — LISTEN/NOTIFY connections** (`ReactiveNotificationHandler` in
`peegeeq-bitemporal`): these hold a single long-lived connection.  When it drops,
`ReactiveNotificationHandler` reconnects with exponential backoff:

- `MAX_RECONNECT_ATTEMPTS = 5`
- Base delay: 1 000 ms; formula: `1000 * (1 << min(attempt−1, 5))` → caps at 32 s
- Resets attempt counter to 0 on success
- Guarded by `shutdown` flag — no reconnect during intentional close

### 1.6 Schema isolation

`peegeeq.database.schema` is the single source of truth.  `PgConnectionConfig.getSchema()` reads
it; `PgConnectionManager.normalizeSearchPath()` validates and normalises the value (letters,
digits, underscore, comma only — no quoted identifiers, no `$user`).  The normalised value is
then written into `PgConnectOptions.setProperties("search_path", ...)`, making it a
per-connection startup parameter rather than a `SET search_path` statement that can be overridden
by application SQL.

### 1.7 Connection string / URL patterns

| Usage | Pattern |
|---|---|
| Vert.x reactive pool | `PgConnectOptions` fields — no URL |
| Flyway migrations | `jdbc:postgresql://<host>:<port>/<db>?currentSchema=<schema>` via `PgConnectionConfig.getJdbcUrl()` |

`getJdbcUrl()` is used **only** by migration tooling.  It is never called on the reactive pool
path and must not be introduced there.

---

## 2. Resilience Stack Active During a Failover Window

When the primary PostgreSQL goes down, three independent mechanisms activate in PeeGeeQ.
Understanding them separately is important because they operate on different timescales and
are not coordinated with each other.

### 2.1 Vert.x pool — silent discard and reconnect

Timescale: **per-request** (no polling, no background thread).

When `pool.getConnection()` or `pool.withConnection()` is called and the pooled TCP socket is
dead, Vert.x discards the broken connection and immediately attempts a new TCP connection to the
configured host/port (which, if HAProxy is used, is the proxy address).  There is no explicit
delay, no backoff, and no counter — it either succeeds or returns a failed `Future`.

This means the Vert.x pool **does not suspend itself** during a failover.  It tries every
request.  Requests that arrive in the first ~1 s after primary failure (before HAProxy has
detected and switched) will fail.  Requests after the switch will succeed.

### 2.2 HealthCheckManager + CircuitBreakerManager — detection and status

Timescale: **periodic** (configured interval, default every 30 s in production).

`HealthCheckManager` runs `SELECT 1` against the pool on a `Vertx.setPeriodic()` timer.
Each check is optionally wrapped with a `Resilience4j CircuitBreaker` via
`CircuitBreakerManager.getCircuitBreaker(name)`:

```java
// In HealthCheckManager.executeWithCircuitBreaker():
if (circuitBreakerManager != null) {
    CircuitBreaker cb = circuitBreakerManager.getCircuitBreaker(cbName);
    if (cb.getState() == CircuitBreaker.State.OPEN) {
        return Future.succeededFuture(HealthStatus.unhealthy(cbName, "Circuit breaker open"));
    }
}
```

When health checks start failing after a primary outage:

1. `HealthCheckManager` marks the database component `UNHEALTHY` in its `lastResults` map.
2. The `OverallHealthInfo` returned by `getHealth()` reflects `DOWN`.
3. The circuit breaker (if configured) opens after its threshold is reached, causing subsequent
   checks to short-circuit to `UNHEALTHY` without hitting the pool.

**Important nuance**: `HealthCheckManager` marks status; it does **not** stop the pool or
prevent application code from calling `pool.withConnection()`.  Business operations and health
checks share the same pool independently.

### 2.3 Application-tier failover — ConnectionRouter + Consul

Timescale: **per-request on the application routing layer** (independent of database tier).

This mechanism is entirely separate from database failover.  It handles failover between
**PeeGeeQ application instances** in a multi-node deployment, not between PostgreSQL nodes.

`ConnectionRouter` routes incoming client requests (from microservices or the Management UI)
to a healthy PeeGeeQ instance discovered via `ConsulServiceDiscovery`:

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

`LoadBalancer` supports `ROUND_ROBIN` and other strategies.  `routeRequestWithRetry` attempts
up to `maxRetries` (default 3) additional instances before failing.

When a PeeGeeQ instance loses its database connection and its `/health` endpoint returns `DOWN`,
Consul deregisters it.  `ConsulServiceDiscovery.discoverInstances()` then stops returning that
instance, and `ConnectionRouter` automatically routes to the remaining healthy instances.

**Key distinction**: Consul-based application failover makes no assumptions about which
PostgreSQL node is primary.  Each surviving PeeGeeQ instance manages its own pool independently.

### 2.4 Combined failover sequence (HAProxy + Consul cluster)

```
t=0 s    Primary PostgreSQL goes down.

t≈0 s    Vert.x pools on all instances: in-flight requests fail immediately.
          Business operations receive Future failures.

t≈1 s    HAProxy detects primary down (fall=2 × inter=500ms).
          HAProxy starts routing new TCP connections to secondary (backup).
          Vert.x pools: new connection attempts now succeed to secondary.
          Business operations resume successfully.

t≈30 s   HealthCheckManager periodic check fires on each instance.
          SELECT 1 succeeds (now reaching secondary via HAProxy).
          Health status returns to UP.
          Circuit breaker (if configured) resets.

Consul:   /health endpoint on each instance reflects UP again.
          No Consul deregistration occurs if instances recovered within the
          Consul TTL window.

t=N      Primary returns. HAProxy detects recovery (rise=1, ~500 ms).
          Traffic automatically fails back to primary.
          Vert.x pool opens new connections to primary.
```

---

## 3. Application-Tier Failover vs Database-Tier Failover

These are orthogonal concerns.  The table below maps the two planes:

| Concern | Mechanism | Scope |
|---|---|---|
| PostgreSQL primary → secondary | HAProxy / VIP / RDS endpoint | Database tier, infrastructure |
| Pool reconnection | Vert.x pool implicit per-request | Database client tier |
| Health status reporting | `HealthCheckManager` + `CircuitBreakerManager` | Application tier, observability |
| PeeGeeQ instance A → instance B | `ConnectionRouter` + Consul | Application tier, routing |

A production deployment must address **both** planes:

1. Place an HAProxy, Patroni VIP, or managed service writer endpoint between PeeGeeQ and
   PostgreSQL.  Set `peegeeq.database.host` to that proxy/VIP address.
2. Run multiple PeeGeeQ instances registered with Consul.  `ConnectionRouter` handles
   application-level failover automatically.

Neither plane knows about the other, and they do not need to.

---

## 4. Why the Vert.x Pool Needs an External Proxy for Failover

`PgBuilder.pool().connectingTo(connectOptions)` bakes a single host/port into the pool at
construction time.  Changing where that pool connects requires rebuilding it — which means
restarting the `PeeGeeQManager`.

An external TCP proxy (`HAProxy`) solves this at the network layer:

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
3. The Vert.x pool's existing connections to the dead primary fail on next use; Vert.x
   discards them and opens new connections — which HAProxy now routes to the secondary.
4. **No application restart.  No pool rebuild.  No code change.**

Auto-recovery: when primary returns, HAProxy detects recovery after 1 successful check
(`rise=1`) and resumes routing to it; the backup reverts to standby.

**Why the same port on both backends is not a problem**: HAProxy distinguishes backends by
**hostname or IP address**, not by port number.  Port 5432 is the PostgreSQL wire-protocol
port on each server; the servers themselves are separate hosts (containers, VMs, or physical
machines) that resolve to different IP addresses on the Docker network.  HAProxy maintains
independent TCP connections to each backend's `host:port` pair and health-checks them
separately.  The client connecting to `haproxy:5400` only ever sees the single proxy address;
the fact that both backends share port 5432 is irrelevant.  This is identical to how an
HTTP load balancer works with two web servers both listening on port 443 — the LB routes by
upstream IP, not by upstream port.

**HAProxy does not require Patroni**: the two are independent tools.  HAProxy alone, using
plain TCP health checks (`check inter 500ms`), is sufficient to detect a dead primary and
promote the backup — no leader-election daemon needed.  This is exactly how the test and
local docker-compose stacks in this project work.

Patroni is an optional enhancement.  When Patroni manages the PostgreSQL cluster, it exposes
a REST API on each node (default port 8008).  HAProxy can use this as a more semantically
precise health check:

```haproxy
# Patroni-aware health check — only routes to the Patroni-elected write primary
server pg1 pg1:5432 check port 8008 httpchk GET /primary
server pg2 pg2:5432 check port 8008 httpchk GET /primary backup
```

With this config, HAProxy only routes to the node Patroni has promoted as primary, rather
than any node that accepts a TCP connection.  This prevents accidentally routing writes to a
replica that is technically up but not the write primary.  For the current project setup
(test + local dev), plain TCP checks are sufficient.

---

## 5. HAProxy Failover Integration Test

### 5.1 Files created

| File | Purpose |
|---|---|
| `peegeeq-db/src/test/resources/haproxy-failover.cfg` | HAProxy config embedded in the JAR, mounted into the container at test startup |
| `peegeeq-db/src/test/java/.../resilience/HaProxyConnectionFailoverTest.java` | The integration test |

### 5.2 Container topology (Testcontainers)

```
Docker bridge network (created per test class run)
  │
  ├── pg_primary   (PostgreSQLContainer, alias "pg_primary")
  ├── pg_secondary (PostgreSQLContainer, alias "pg_secondary")
  └── haproxy      (GenericContainer "haproxy:2.8-alpine")
        │  mounts haproxy-failover.cfg from classpath
        └── exposes HAPROXY_PG_PORT:5400 → random host port
```

`PgConnectionManager` is pointed at `haproxy.getHost() : haproxy.getMappedPort(5400)`.
It never knows the actual PostgreSQL host/port.

### 5.3 Test phases

**Phase 1 — Normal operation** (`@Order(1)`)

- Executes `SELECT 1` through the pool.
- Verifies HAProxy routes to the primary and the pool functions normally.

**Phase 2 — HAProxy TCP failover** (`@Order(2)`)

1. Confirms pre-failover query succeeds.
2. Calls `primary.stop()` to kill the primary container.
3. Waits 4 s (HAProxy detection time ~1 s + 3 s buffer).
4. Retries `SELECT 1` up to 8 times at 1 s intervals.
5. Asserts the query eventually returns 1, routed through HAProxy to the secondary.

> **Production note**: in production, primary and secondary would share data via
> PostgreSQL streaming replication.  This test uses two independent nodes because
> it targets *connection-level* resilience, not data consistency.

### 5.4 Retry design (no `.recover()`)

`.recover()` is banned in this codebase (see `docs-design/code reviews/vertx-recover-usage.md`).
The retry loop uses `Promise<Integer>` + `vertx.setTimer()` + `.onSuccess()` / `.onFailure()`:

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

This is correct Vert.x 5.x style: state is carried in a `Promise`, control flow in
`.onSuccess()`/`.onFailure()`, delays via `vertx.setTimer()`.

### 5.5 Running the test

```powershell
mvn test -pl peegeeq-db -Pintegration-tests '-Dtest=HaProxyConnectionFailoverTest'
```

Requirements: Docker Desktop running.  The test pulls `haproxy:2.8-alpine` on first run.

### 5.6 HAProxy config (annotated)

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
    # The server responds with an authentication challenge; any response
    # (including "wrong password") proves PostgreSQL is alive and serving
    # the wire protocol — not just accepting a TCP connection.
    # tcp-check would pass even if PostgreSQL is still in crash recovery or
    # max_connections is exhausted.
    # Requires a 'haproxy_check' user to exist in PostgreSQL (no password needed).
    option            pgsql-check user haproxy_check

    # pg_primary / pg_secondary are Docker network aliases on the test network.
    # fall=2: mark DOWN after 2 consecutive failed checks (~1 s at inter=500ms)
    # rise=1: recover after 1 successful check
    server pg_primary   pg_primary:5432   check inter 500ms fall 2 rise 1
    server pg_secondary pg_secondary:5432 check inter 500ms fall 2 rise 1 backup
```

**`haproxy_check` user**: HAProxy sends a PostgreSQL startup message with this username.
PostgreSQL responds with an authentication challenge (even "role does not exist" is a valid
protocol-level response).  The user needs no password, no schema access, and no database
privileges.  It is created by `haproxy-check-init.sql` via `withInitScript()` on each
Testcontainers node; in the local docker-compose stack it is mounted as
`/docker-entrypoint-initdb.d/init-haproxy-check.sql`.

---

## 6. Local Developer Environment (HAProxy + PgBouncer)

For manual failover testing without running a JVM test, a docker-compose stack is provided:

**Files:**

| File | Purpose |
|---|---|
| `scripts/docker-compose-failover-local.yml` | Full stack definition |
| `scripts/haproxy-failover-local.cfg` | HAProxy config with stats page |
| `scripts/init-haproxy-check.sql` | Creates `haproxy_check` PostgreSQL user on first start |

**Topology:**

```
Application (PeeGeeQ) or psql client
  │  host=localhost, port=6432
  ▼
PgBouncer:6432   (bitnami/pgbouncer:1.22.0)
  │  session pool, up to 200 client connections, 20 server connections
  │  connects to haproxy:5400 (not directly to PostgreSQL)
  ▼
HAProxy:5400     (haproxy:2.8-alpine, stats on :8404)
  ├──► pg-primary:5432    (postgres:15.13-alpine3.20, exposed :5433)
  └──► pg-secondary:5432  (postgres:15.13-alpine3.20, exposed :5434)
```

**PgBouncer** sits in front of HAProxy to handle the connection reset burst that occurs
when HAProxy fails over: clients waiting for a connection receive a server-side disconnect;
PgBouncer absorbs the reset and retries the server connection transparently to the client.

**Stats page**: `http://localhost:8404/stats` — shows live health of both backends.

**Start:**
```powershell
docker compose -f scripts/docker-compose-failover-local.yml up -d
```

**Simulate failover:**
```powershell
# Stop primary — HAProxy detects failure in ~1 s, routes to secondary
docker compose -f scripts/docker-compose-failover-local.yml stop pg-primary

# Connect through PgBouncer (or HAProxy direct on :5400)
psql -h localhost -p 6432 -U peegeeq_dev -d peegeeq_dev -c "SELECT 1"

# Restore primary — HAProxy auto-recovers, traffic returns to primary
docker compose -f scripts/docker-compose-failover-local.yml start pg-primary
```

---

## 7. Gaps and Future Work

### 7.1 No pool-level reconnect configuration

`PgBuilder.pool()` has no `reconnectAttempts` / `reconnectInterval` setting analogous to
Vert.x EventBus or HttpClient reconnect options.  Reconnect happens implicitly on the next
`pool.getConnection()` call.  Under a slow HAProxy failover window, in-flight operations
on broken connections will fail and must be retried by the caller.

**Gap**: `CircuitBreakerManager` exists and is used by `HealthCheckManager` to guard
health-check queries.  It is **not** currently wired into `PgConnectionManager` or into
business operation paths.  During the ~1 s HAProxy failover window, callers receive raw
`VertxException: Connection refused` failures rather than a structured
`CircuitBreakerOpenException`.

**Recommendation**: evaluate wiring the circuit breaker to the pool acquisition path
(`withConnection`, `withTransaction`) so that a burst of connection failures during failover
opens the breaker and surfaces a single, observable circuit-open event rather than a flood of
raw TCP errors.

### 7.2 LISTEN/NOTIFY reconnects are pool-independent

`ReactiveNotificationHandler` in `peegeeq-bitemporal` manages its own long-lived connection
separate from the pool.  It has its own exponential backoff.  It points directly at PostgreSQL
(primary host/port), not at HAProxy.

**Gap**: if the primary fails and the application uses bitemporal subscriptions, the
LISTEN/NOTIFY connection will exhaust its 5 reconnect attempts and go silent.  It will not
automatically route to the secondary via HAProxy because it was not pointed at HAProxy.

**Recommendation**: `ReactiveNotificationHandler` should also connect through HAProxy (or
another TCP proxy) so its long-lived connection benefits from the same automatic failover.

### 7.3 Streaming replication not covered by the test

The current test uses two independent PostgreSQL instances.  A real HA setup requires
streaming replication so the secondary has the same data as the primary.  After a failover,
queries to the secondary that reference data written to the primary but not yet replicated
would return stale results or fail.

**Recommendation**: a future integration test should configure pg_primary with
`wal_level=replica`, set up a Testcontainers secondary with `recovery.conf` / `standby.signal`,
and verify data consistency across the failover boundary.

### 7.4 PgBouncer `server_reset_query` in transaction pool mode

The local docker-compose uses `PGBOUNCER_POOL_MODE=session`.  In session mode, a server
connection follows a client connection for its lifetime, making reset queries straightforward.
Transaction pool mode (higher throughput) requires careful `server_reset_query` configuration
because PostgreSQL `search_path` (and other session parameters) must be reset between
transactions when connections are multiplexed.  Using transaction mode with PeeGeeQ's
`search_path` tenant isolation has not been tested.

**Recommendation**: if transaction pool mode is required for performance, validate that
`server_reset_query = DISCARD ALL` or an explicit `SET search_path` reset correctly restores
the configured schema between transactions.

### 7.5 Split-brain consideration with streaming replication and automatic promotion

HAProxy is production-ready and widely deployed in front of PostgreSQL.  It does not require
Patroni.  The consideration below applies only to a **specific combination** of conditions:
streaming replication is active **and** an automatic promotion mechanism (script, cloud
automation, etc.) promotes the secondary without fencing the old primary first.

**The scenario that requires care**:

1. Primary goes down.  HAProxy routes writes to the secondary.
2. Automated tooling promotes the secondary to accept writes.
3. The original primary recovers and passes HAProxy's `pgsql-check` health check (`rise=1`).
4. HAProxy automatically routes writes back to the original primary — which was never
   demoted and has no knowledge of the promotion.
5. Both nodes now accept writes simultaneously.  Data diverges.

**This scenario does not arise when**:
- Promotion is manual (a DBA promotes explicitly, then updates HAProxy or the DNS entry).
- Patroni manages the cluster — it fences the old primary before promoting the secondary.
- A cloud managed service is used (RDS Multi-AZ, Cloud SQL, etc.) — the managed failover
  endpoint handles the switch atomically.
- The `backup` keyword in HAProxy config is left as-is and no automatic promotion script
  runs — HAProxy routes to the secondary for reads/connections but the secondary remains
  a replica and never becomes a conflicting primary.

**Why this does not affect the current project**: the integration test and local dev
docker-compose use two independent PostgreSQL instances with no streaming replication.  No
automatic promotion occurs.  The setup is correct for its purpose (connection-level failover
testing).

**Recommendation for production with streaming replication and automatic promotion**: add
Patroni (or repmgr) to manage promotion with fencing, and replace the plain TCP check with
`httpchk GET /primary` against Patroni's REST port as shown in §4.  For environments that
use manual promotion or a managed service endpoint, HAProxy alone is sufficient.

---

## 8. Implementation Plan

The four gaps above are graded by risk and dependency order.  Gaps 7.1 and 7.2 require
production code changes and must follow the standard change process (read existing code →
focused file-by-file change → compile → integration test → verify no forbidden patterns).
Gaps 7.3 and 7.4 are test/config-only changes.

---

### Phase 1 — LISTEN/NOTIFY via HAProxy (Gap 7.2)
**Risk: LOW.  No new abstractions.  Single call-site change.**

`ReactiveNotificationHandler` accepts a `PgConnectOptions` as a constructor argument.  The
host/port baked into that options object is what determines whether the long-lived LISTEN
connection survives a primary failover.  Currently that options object is built from
`PgConnectionConfig.getHost()` / `getPort()` — the raw PostgreSQL address.

**Change required**: wherever `ReactiveNotificationHandler` is constructed (find all call sites
in `peegeeq-bitemporal`), use the HAProxy host/port from configuration rather than the direct
PostgreSQL host/port.

**New configuration property** (add to `peegeeq-default.properties` and
`PeeGeeQConfiguration`):
```
peegeeq.database.proxy.host=   (default: same as peegeeq.database.host)
peegeeq.database.proxy.port=   (default: same as peegeeq.database.port)
```

When these properties are set, both `PgConnectionManager.createReactivePool()` and the
`PgConnectOptions` passed to `ReactiveNotificationHandler` should use the proxy address.
When unset, behaviour is unchanged (direct PostgreSQL, no proxy).

**Test**: extend `HaProxyConnectionFailoverTest` with a third phase that:
1. Creates a `ReactiveNotificationHandler` pointed at HAProxy.
2. Subscribes to a NOTIFY channel.
3. Stops the primary; waits for HAProxy to switch.
4. Issues `NOTIFY` on the secondary; asserts the handler receives the notification.

**Files to change**:
- `peegeeq-db/src/main/resources/peegeeq-default.properties` — add proxy properties
- `peegeeq-db/src/main/java/.../config/PeeGeeQConfiguration.java` — read proxy properties
- `peegeeq-bitemporal/src/main/.../bitemporal/PeeGeeQBiTemporalManager.java` (or wherever
  `ReactiveNotificationHandler` is constructed) — use proxy options
- `peegeeq-db/src/test/.../resilience/HaProxyConnectionFailoverTest.java` — Phase 3 test

---

### Phase 2 — Circuit Breaker on Pool Operations (Gap 7.1)
**Risk: MEDIUM.  Changes `PgConnectionManager` public API surface.**

The goal is to make `withConnection()` and `withTransaction()` aware of an optional
`CircuitBreakerManager` so that a burst of connection failures during a failover window opens
the breaker and surfaces a structured failure instead of a flood of raw TCP errors.

**Design constraints**:
- `CircuitBreakerManager` is already optional (nullable) in `HealthCheckManager`.  Apply the
  same pattern in `PgConnectionManager`: accept it via constructor, null means no circuit
  breaking.
- The circuit breaker must be checked **before** `pool.withConnection()` is called; if open,
  return `Future.failedFuture(new CallNotPermittedException(...))` immediately.
- Record success/failure on the Resilience4j `CircuitBreaker` after the pool call resolves.
- Use a **per-pool** circuit breaker named `"db.pool.<serviceId>"` so different pools can
  fail independently.

**Sketch** (inside `PgConnectionManager.withConnection()`):
```java
// New optional field: private final CircuitBreakerManager circuitBreakerManager;

public <T> Future<T> withConnection(String serviceId, Function<SqlConnection, Future<T>> op) {
    String resolvedId = resolveServiceId(serviceId);
    Pool pool = reactivePools.get(resolvedId);
    if (pool == null) {
        return Future.failedFuture(new IllegalStateException("No pool: " + resolvedId));
    }
    if (circuitBreakerManager != null) {
        CircuitBreaker cb = circuitBreakerManager.getCircuitBreaker("db.pool." + resolvedId);
        if (cb != null && cb.getState() == CircuitBreaker.State.OPEN) {
            return Future.failedFuture(cb.createCallNotPermittedException());
        }
        return pool.withConnection(conn -> {
            setupNoticeHandler(conn);
            return op.apply(conn);
        }).onSuccess(v -> { if (cb != null) cb.onSuccess(0, TimeUnit.NANOSECONDS); })
          .onFailure(e -> { if (cb != null) cb.onError(0, TimeUnit.NANOSECONDS, e); });
    }
    return pool.withConnection(conn -> {
        setupNoticeHandler(conn);
        return op.apply(conn);
    });
}
```

Apply the same pattern to `withTransaction()`.  `getReactiveConnection()` is for callers that
manage their own connection lifecycle; leave it unwrapped for now.

**Wiring**: `PeeGeeQManager` already constructs both `PgConnectionManager` and
`CircuitBreakerManager`; pass `circuitBreakerManager` into the `PgConnectionManager`
constructor there.

**Test**: add an integration test (`CircuitBreakerPoolIntegrationTest`) that:
1. Creates a pool pointed at a stopped PostgreSQL container.
2. Calls `withConnection()` enough times to trip the breaker threshold.
3. Asserts subsequent calls fail with a `CallNotPermittedException` (not a TCP error).
4. Starts the container; asserts the breaker transitions to HALF_OPEN then CLOSED.

**Files to change**:
- `peegeeq-db/src/main/java/.../connection/PgConnectionManager.java` — constructor +
  `withConnection()` + `withTransaction()`
- `peegeeq-db/src/main/java/.../PeeGeeQManager.java` — pass `circuitBreakerManager` to
  `PgConnectionManager`
- `peegeeq-db/src/test/.../resilience/CircuitBreakerPoolIntegrationTest.java` — new test

---

### Phase 3 — Streaming Replication Test (Gap 7.3)
**Risk: LOW.  Test-only.  No production code change.**

Replace the two-independent-nodes topology in `HaProxyConnectionFailoverTest` with a real
streaming replication setup.  This verifies not just connection-level resilience but also that
data written before the failover is visible on the secondary after promotion.

**PostgreSQL configuration for primary** (via `withCommand()` on `PostgreSQLContainer`):
```
-c wal_level=replica
-c max_wal_senders=3
-c wal_keep_size=64
```

**Secondary setup** (via `withCommand()` on `GenericContainer` running the same Postgres
image): use `pg_basebackup` in a `@BeforeAll` init script, then add `standby.signal` to the
data directory.  Testcontainers `ExecInContainerPattern` can run the `pg_basebackup` command
after both containers are started.

**Test phase** (extends Phase 2 of the existing test):
1. Write a known row to primary via the pool.
2. Stop primary; wait for HAProxy failover.
3. Promote secondary with `pg_ctl promote`.
4. Query that row via the pool — now reaching the promoted secondary.
5. Assert the row exists (data survived the failover).

**New test class**: `HaProxyReplicationFailoverTest` in the same `resilience` package.
Keep `HaProxyConnectionFailoverTest` as-is (connection-level test, no replication, faster).

---

### Phase 4 — PgBouncer Transaction Pool Validation (Gap 7.4)
**Risk: LOW.  Config and test only.**

Add a second PgBouncer profile to the local docker-compose that uses
`PGBOUNCER_POOL_MODE=transaction` with `server_reset_query=DISCARD ALL`.

Write a test (`PgBouncerTransactionModeTest`) in `peegeeq-db` that:
1. Starts PostgreSQL + PgBouncer (transaction mode) via Testcontainers.
2. Runs `peegeeq-migrations` Flyway migrations against it.
3. Creates a `PeeGeeQManager` pointed at PgBouncer.
4. Sends and consumes messages across multiple transactions.
5. Verifies `search_path` is correctly applied for each connection (queries the right schema).

This test can be tagged `@Tag(TestCategories.INTEGRATION)` and run in CI alongside the
existing integration suite.

---

### Summary table

| # | Gap | Phase | Risk | Production change | Test change |
|---|---|---|---|---|---|
| 7.2 | LISTEN/NOTIFY bypasses HAProxy | Phase 1 | LOW | Proxy config properties; call-site change | `HaProxyConnectionFailoverTest` Phase 3 |
| 7.1 | No circuit breaker on pool ops | Phase 2 | MEDIUM | `PgConnectionManager` + `PeeGeeQManager` wiring | New `CircuitBreakerPoolIntegrationTest` |
| 7.3 | No streaming replication test | Phase 3 | LOW | None | New `HaProxyReplicationFailoverTest` |
| 7.4 | PgBouncer transaction mode untested | Phase 4 | LOW | None | New `PgBouncerTransactionModeTest` |
| 7.5 | Split-brain with replication + auto-promotion | Ops/infra | LOW–MEDIUM (specific conditions only) | Add Patroni if using auto-promotion with replication | N/A |

Phases 1 and 3 can be worked in parallel (different modules, no shared files).  Phase 2
depends on Phase 1 being stable (pool changes affect the same `PgConnectionManager`).
Phase 4 is fully independent.
