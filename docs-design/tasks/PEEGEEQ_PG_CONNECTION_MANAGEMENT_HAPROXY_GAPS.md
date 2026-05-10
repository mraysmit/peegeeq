# PostgreSQL Connection Management and HAProxy Failover — Gaps and Implementation Plan

**Extracted from**: `PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md`  
**Author**: Mark A Ray-Smith Cityline Ltd.  
**Date**: May 10, 2026  
**Status**: PLANNED

This document contains the detailed gap analysis and phased implementation plan for
improving the HAProxy failover stack.  The architectural context, conceptual overview,
and sidecar usage guide are in
[PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md).

---

## 1. Gap Analysis

### 1.1 No pool-level reconnect configuration

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

### 1.2 LISTEN/NOTIFY reconnects are pool-independent

`ReactiveNotificationHandler` in `peegeeq-bitemporal` manages its own long-lived connection
separate from the pool.  It has its own exponential backoff.  It points directly at PostgreSQL
(primary host/port), not at HAProxy.

**Gap**: if the primary fails and the application uses bitemporal subscriptions, the
LISTEN/NOTIFY connection will exhaust its 5 reconnect attempts and go silent.  It will not
automatically route to the secondary via HAProxy because it was not pointed at HAProxy.

**Recommendation**: `ReactiveNotificationHandler` should also connect through HAProxy (or
another TCP proxy) so its long-lived connection benefits from the same automatic failover.

### 1.3 Streaming replication not covered by the test

The current test uses two independent PostgreSQL instances.  A real HA setup requires
streaming replication so the secondary has the same data as the primary.  After a failover,
queries to the secondary that reference data written to the primary but not yet replicated
would return stale results or fail.

**Recommendation**: a future integration test should configure pg_primary with
`wal_level=replica`, set up a Testcontainers secondary with `recovery.conf` / `standby.signal`,
and verify data consistency across the failover boundary.

### 1.4 PgBouncer `server_reset_query` in transaction pool mode

The local docker-compose uses `PGBOUNCER_POOL_MODE=session`.  In session mode, a server
connection follows a client connection for its lifetime, making reset queries straightforward.
Transaction pool mode (higher throughput) requires careful `server_reset_query` configuration
because PostgreSQL `search_path` (and other session parameters) must be reset between
transactions when connections are multiplexed.  Using transaction mode with PeeGeeQ's
`search_path` tenant isolation has not been tested.

**Recommendation**: if transaction pool mode is required for performance, validate that
`server_reset_query = DISCARD ALL` or an explicit `SET search_path` reset correctly restores
the configured schema between transactions.

### 1.5 Split-brain consideration with streaming replication and automatic promotion

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
`httpchk GET /primary` against Patroni's REST port as shown in §4 of the main document.
For environments that use manual promotion or a managed service endpoint, HAProxy alone is
sufficient.

---

## 2. Implementation Plan

The four gaps above are graded by risk and dependency order.  Gaps 1.1 and 1.2 require
production code changes and must follow the standard change process (read existing code →
focused file-by-file change → compile → integration test → verify no forbidden patterns).
Gaps 1.3 and 1.4 are test/config-only changes.

---

### Phase 1 — LISTEN/NOTIFY via HAProxy (Gap 1.2)
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

### Phase 2 — Circuit Breaker on Pool Operations (Gap 1.1)
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

### Phase 3 — Streaming Replication Test (Gap 1.3)
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

### Phase 4 — PgBouncer Transaction Pool Validation (Gap 1.4)
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
| 1.2 | LISTEN/NOTIFY bypasses HAProxy | Phase 1 | LOW | Proxy config properties; call-site change | `HaProxyConnectionFailoverTest` Phase 3 |
| 1.1 | No circuit breaker on pool ops | Phase 2 | MEDIUM | `PgConnectionManager` + `PeeGeeQManager` wiring | New `CircuitBreakerPoolIntegrationTest` |
| 1.3 | No streaming replication test | Phase 3 | LOW | None | New `HaProxyReplicationFailoverTest` |
| 1.4 | PgBouncer transaction mode untested | Phase 4 | LOW | None | New `PgBouncerTransactionModeTest` |
| 1.5 | Split-brain with replication + auto-promotion | Ops/infra | LOW–MEDIUM (specific conditions only) | Add Patroni if using auto-promotion with replication | N/A |

Phases 1 and 3 can be worked in parallel (different modules, no shared files).  Phase 2
depends on Phase 1 being stable (pool changes affect the same `PgConnectionManager`).
Phase 4 is fully independent.
