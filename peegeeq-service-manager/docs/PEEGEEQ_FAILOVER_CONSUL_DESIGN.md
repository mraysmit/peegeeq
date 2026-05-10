# PostgreSQL Automatic Failover Using Consul — Design

**Author**: Mark A Ray-Smith Cityline Ltd.  
**Date**: May 10, 2026  
**Status**: PROPOSED  
**Module**: `peegeeq-service-manager`

---

## 1. Context and Motivation

PeeGeeQ connects to PostgreSQL exclusively via the Vert.x 5.x reactive client
(`io.vertx.pgclient`).  The connection pool is built once per `serviceId` from a fixed
`PgConnectOptions` (host + port baked in at construction time).  Failover at the database
tier is therefore entirely the responsibility of whatever sits at that host:port.

The current stack delegates this to **HAProxy with `pgsql-check`**: HAProxy calls
`pg_is_in_recovery()` on each backend; it routes writes only to the node that returns `false`
(the primary).  When the primary dies HAProxy stops routing to it immediately.  The problem
is that HAProxy itself does not promote the standby — it merely stops forwarding to the dead
node.  Without automatic promotion the standby remains read-only and all writes fail until an
operator intervenes.

`peegeeq-service-manager` already contains a full Consul integration (`ConsulServiceDiscovery`,
`ConnectionRouter`, `LoadBalancer`).  Consul provides:

- **Sessions with TTL** — a session expires automatically when its holder stops renewing it.
- **KV store** — a session can hold a lock on a KV key; when the session expires the lock
  is released atomically.
- **Distributed consensus** — Consul's Raft consensus means the KV store is consistent even
  across multiple service-manager nodes.

This is precisely the coordination layer that tools like Patroni use (Patroni targets etcd,
Consul, or ZooKeeper).  We can implement the same behaviour using the existing
`io.vertx.ext.consul.ConsulClient` already present in the project.

---

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                   peegeeq-service-manager               │
│                                                         │
│  ┌──────────────────────┐   ┌──────────────────────┐   │
│  │  ConsulServiceDiscovery│  │  PgFailoverMonitor   │   │
│  │  (existing — app tier)│  │  (NEW — db tier)     │   │
│  └──────────────────────┘   └──────────┬───────────┘   │
│                                         │               │
│                              ┌──────────▼───────────┐   │
│                              │   PgPrimaryElector   │   │
│                              │   (NEW — Consul lock)│   │
│                              └──────────────────────┘   │
└─────────────────────────────────────────────────────────┘
          │ direct pool                   │ direct pool
          ▼                               ▼
  ┌──────────────┐               ┌──────────────┐
  │  pg_primary  │               │  pg_standby  │
  │ (port 5432)  │               │ (port 5433)  │
  └──────────────┘               └──────────────┘
          ▲                               ▲
          └───────────┬───────────────────┘
                      │
               ┌──────┴──────┐
               │   HAProxy   │  ← pgsql-check still does routing
               │  port 5000  │    (pg_is_in_recovery() → primary only)
               └─────────────┘
                      ▲
               PeeGeeQ pools
               (PgConnectionManager)
```

Two planes operate independently:

| Plane | Tooling | Purpose |
|-------|---------|---------|
| **Application tier** | `ConsulServiceDiscovery` + `ConnectionRouter` | Discover and route to healthy PeeGeeQ application instances |
| **Database tier** | `PgFailoverMonitor` + `PgPrimaryElector` + HAProxy | Detect primary failure, promote standby, HAProxy re-routes |

The two planes share the same Consul cluster but register under different service names and KV
key prefixes.  They do not need to know about each other.

---

## 3. New Components

### 3.1 `PgNodeConfig`

Value object. Holds the **direct** host:port of one PostgreSQL node (not the HAProxy address).
Required because `PgFailoverMonitor` must reach a node even when HAProxy considers it dead.

```java
// peegeeq-service-manager/.../failover/PgNodeConfig.java
public record PgNodeConfig(
    String nodeId,        // e.g. "pg-primary", "pg-standby-1"
    String host,          // direct host (not HAProxy)
    int    port,          // direct port
    String database,
    String user,
    String password
) {}
```

### 3.2 `PgPrimaryElector`

Manages a **Consul session + KV lock** that identifies the current primary.

**KV key**: `peegeeq/pg/primary-lock`  
**Session TTL**: configurable, default 15 s  
**Renewal interval**: TTL / 3 (5 s)

Lifecycle:

```
start()
  → create Consul session (TTL=15s, LockDelay=10s)
  → attempt to acquire lock on KV key
  → if acquired: this node is the designated primary manager
  → schedule periodic renewal (every 5 s)

onSessionExpired()    ← Consul session TTL elapsed without renewal
  → lock released automatically by Consul
  → competing monitor nodes race to re-acquire
  → winner calls promote()
```

`LockDelay = 10 s` prevents a flapping node from immediately re-acquiring a lock it just
lost.  This is the same mechanism Patroni uses.

### 3.3 `PgFailoverMonitor`

A Vert.x `AbstractVerticle` that:

1. Holds one direct `PgPool` per configured PostgreSQL node (bypassing HAProxy).
2. Every 500 ms executes `SELECT pg_is_in_recovery()` on the node it currently considers the
   primary.
3. On consecutive failures (configurable, default 3 within 3 s):
   a. Stops renewing the Consul session → session expires → lock releases.
   b. The standby node's monitor (on another service-manager instance, or the same one after
      re-acquiring the lock) calls `SELECT pg_promote()` on the standby.
4. After promotion: HAProxy's next `pgsql-check` cycle (every 500 ms, configured in HAProxy
   `inter=500ms`) detects the new primary and reroutes automatically.  No HAProxy
   reconfiguration is needed.

**Why `pg_promote()` and not `pg_ctl`?**  
`pg_promote()` is a pure SQL call available since PostgreSQL 12.  It does not require shell
access to the database host.  PeeGeeQ already uses the reactive pgclient; invoking
`pg_promote()` through it is consistent with the project's no-JDBC rule.

---

## 4. Failover Sequence

```
T+0     Primary crash detected (3 consecutive health poll failures within 3 s)
T+3     PgFailoverMonitor stops renewing the Consul session for pg-primary
T+13    Consul session TTL expires (15 s TTL − 2 s already elapsed = ~13 s)
T+23    LockDelay expires (10 s) — standby monitor acquires lock
T+23    pg_promote() issued on standby via direct reactive pgclient
T+24    Standby completes promotion, pg_is_in_recovery() → false
T+24.5  HAProxy pgsql-check detects new primary (fall=2, inter=500ms → ~1 s to reroute)
T+25    PeeGeeQ pools retry connections → HAProxy routes to new primary → success
```

Approximate RTO with default settings: **25–30 seconds** from crash to traffic restored.

This is tunable:
- Reduce session TTL + LockDelay for faster failover (higher risk of false positive).
- Increase poll failure threshold for more stability (slower detection).

---

## 5. Fencing (Split-Brain Prevention)

The most dangerous scenario is a **network partition**: the primary is alive but isolated.
Both nodes believe they should be primary; both accept writes; data diverges.

**Mandatory fencing rule**: When `PgPrimaryElector` loses the Consul lock (session expires),
it must immediately execute one of:

- `SELECT pg_ctl_stop_fast()` — not available as plain SQL in all PostgreSQL versions.
- Issue a `PgPool.close()` on the direct primary pool + reject all in-flight operations.
- Execute `pg_terminate_backend(pid) FROM pg_stat_activity` to kill all active backends
  before the standby is promoted.

The simplest safe option for PeeGeeQ: when the lock is lost, the old primary's
`PgFailoverMonitor` calls `pg_terminate_backend` on all sessions via the direct pool (if the
node is still reachable).  If the node is unreachable this is a no-op — and the reason the
session expired was likely because the node was unreachable, so this is the correct outcome.

**`pg_rewind` for re-joining**: After the old primary recovers, it must re-join as a standby.
This requires:

- `wal_log_hints = on` in `postgresql.conf` (or data checksums enabled).
- Running `pg_rewind` to align the WAL timeline before starting replication.

Without `pg_rewind`, the old primary's WAL diverges from the new primary and streaming
replication cannot re-establish automatically.

---

## 6. Consul Dependency Topology

```
Production (recommended):
  3-node Consul cluster (Raft quorum = 2)
  → tolerates 1 Consul node failure
  → pg failover continues correctly

Development / single-node test:
  consul agent -dev
  → no HA, Consul is a single point of failure
  → acceptable for local testing only
```

PeeGeeQ service-manager already starts a `ConsulClient` pointing to `consul.host` /
`consul.port` system properties.  The `PgFailoverMonitor` reuses the same client.

---

## 7. Configuration

All settings read from `peegeeq-service-manager` configuration (same pattern as
`ServiceManagerConfig`):

| Property | Default | Description |
|----------|---------|-------------|
| `peegeeq.pg.failover.enabled` | `false` | Enable/disable the monitor entirely |
| `peegeeq.pg.failover.session.ttl` | `15s` | Consul session TTL |
| `peegeeq.pg.failover.session.lock-delay` | `10s` | Consul lock delay after session loss |
| `peegeeq.pg.failover.poll.interval` | `500ms` | How often to poll `pg_is_in_recovery()` |
| `peegeeq.pg.failover.poll.failure-threshold` | `3` | Consecutive failures before acting |
| `peegeeq.pg.nodes.<id>.host` | — | Direct host for each PostgreSQL node |
| `peegeeq.pg.nodes.<id>.port` | — | Direct port for each PostgreSQL node |

---

## 8. Relationship to HAProxy

HAProxy continues to serve its existing role:

- All PeeGeeQ application pools connect to the HAProxy VIP address only.
- HAProxy's `pgsql-check` health checks re-route automatically when `pg_is_in_recovery()`
  changes on promotion.
- `PgFailoverMonitor` does **not** reconfigure HAProxy; it only promotes the standby.
  HAProxy's built-in checks handle the rest.

The two components are therefore independent: HAProxy handles routing; Consul handles
promotion election.

---

## 9. Proposed New Classes

```
peegeeq-service-manager/src/main/java/dev/mars/peegeeq/servicemanager/
├── discovery/
│   └── ConsulServiceDiscovery.java          (existing — app tier, unchanged)
├── failover/
│   ├── PgNodeConfig.java                    NEW: direct node host/port config
│   ├── PgPrimaryElector.java                NEW: Consul session + KV lock management
│   └── PgFailoverMonitor.java               NEW: health poll + pg_promote() trigger
└── config/
    └── ServiceManagerConfig.java            EXTEND: add pg failover config fields
```

---

## 10. What This Is Not

- **Not a replacement for Patroni** in large deployments. Patroni is battle-tested, widely
  operated, and supports more complex topologies (cascading standbys, synchronous replication
  mode management, REST API for operators).
- **Not safe without fencing** in a network-partition scenario. The fencing approach in
  Section 5 must be implemented before this is used in production.
- **Not a solution for the LISTEN/NOTIFY gap**. LISTEN/NOTIFY connections bypass HAProxy
  (they are long-lived). That gap is tracked separately in the connection management document
  as Phase 1 work.

---

## 11. Acceptance Criteria

A new integration test `PgConsulFailoverIntegrationTest` in `peegeeq-service-manager` must
demonstrate:

1. Two PostgreSQL containers + Consul container + HAProxy container started via Testcontainers.
2. `PgFailoverMonitor` started against both nodes.
3. Primary container stopped (`dockerClient.stopContainerCmd(...)`).
4. Within 45 seconds: writes succeed through HAProxy (confirming promotion completed and
   HAProxy re-routed).
5. Old primary container restarted: it re-joins as standby (manual `pg_rewind` step or
   verified via `pg_is_in_recovery()` returning `true`).

---

*End of document.*
