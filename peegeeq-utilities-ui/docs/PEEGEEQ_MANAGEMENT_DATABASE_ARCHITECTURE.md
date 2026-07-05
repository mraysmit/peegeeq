# PeeGeeQ — Management Database Architecture (estate control plane)

A standalone, centralised **`peegeeq-management` database** that lets a PeeGeeQ control plane know
about, reconnect to, and coordinate **setups distributed across many PostgreSQL servers** (different
machines / IPs). This is the estate-scale realisation of the durable registry in
[PEEGEEQ_CONNECT_EXISTING_SETUP_SPEC.md §6](PEEGEEQ_CONNECT_EXISTING_SETUP_SPEC.md); it builds on the
setup definition and the non-destructive `connectToExistingSetup` primitive defined there.

> "Verified" = read from source on 2026-07-05. "*(to confirm)*" = still needs a runtime check.

> **Status — decisions locked (see §7):** single-owner (lease-based); **one org-wide** shared
> `peegeeq-management` database; credentials **encrypted at rest** initially, with a planned move to
> secret references / vault later.

---

## 1. Motivation

Setups in a real estate live on **different PostgreSQL servers** (per-machine, per-tenant,
per-region). Today the binding — *which* setups exist, *where* they live, *how* to reach them — is
held **in-memory per REST-backend process** (`activeSetups`, verified) and lost on shutdown. There is
no shared, durable record of the estate. To manage a distributed estate you need a persistent,
central place that describes it and from which any control-plane backend can (re)establish setups.

---

## 2. Control plane vs data plane

A browser cannot connect to PostgreSQL — management-ui talks to a REST backend, and the backend
connects to PostgreSQL. So "connect to many PG servers" is a *control-plane* capability:

```
management-ui (browser)
      │  HTTP
      ▼
peegeeq-rest (control plane) ──reads/writes──►  peegeeq-management DB  (central registry + leases)
      │  per-setup connections, fanned out to wherever each setup lives
      ├───────────────► PG server A (10.0.0.1)  → setup "orders-prod"
      ├───────────────► PG server B (10.0.0.2)  → setup "events-eu"
      └───────────────► PG server C (10.0.0.3)  → setups "x", "y"
```

- **Data plane** — the distributed setup databases (queues, messages, events). Source of truth for
  message data; already concurrency-safe (SKIP LOCKED, transactions).
- **Control plane** — the REST backend(s) + the **`peegeeq-management` database**: the estate registry
  and coordination store.

**Already true (so this is smaller than it looks):** a setup's `databaseConfig` already carries its
own `host`/`port` — `createCompleteSetup` → `createDatabaseFromTemplate(host, port, …)` connects to
*that* server ([DatabaseTemplateManager.java:41](../../peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/DatabaseTemplateManager.java)).
So one backend can **already** provision/run setups on different servers. The missing piece is purely
the durable, central **control-plane registry** — this document.

---

## 3. The `peegeeq-management` database

A **standalone** database (its own host; not any setup's data DB). The backend is given its
connection at startup (one well-known bootstrap config); from there it discovers the whole estate. It
holds **inventory + bindings + coordination**, never queue data.

### Proposed schema (starting point)

| Table | Columns (essential) | Purpose |
|---|---|---|
| `servers` | `id`, `name`, `host`, `port`, `environment`, `credential`, `created_at`, `updated_at` | The PostgreSQL servers in the estate |
| `setups` | `setup_id` (pk), `server_id` (fk), `database_name`, `schema`, `credential`, `status`, `tags`/`tenant`, `created_at`, `updated_at` | The estate-wide **binding** (which setup, where, how to reach it) |
| `setup_ownership` | `setup_id` (pk/fk), `owner_backend_id`, `lease_expires_at`, `heartbeat_at` | Single-owner lease (see §5) |
| `backends` | `id`, `name`, `host`, `started_at`, `last_heartbeat` | Control-plane instances (for ownership/observability) |

Notes:
- **Credentials are encrypted at rest** in the `credential` column (key supplied to the backend at
  startup), with a planned move to **secret references** later — the column is defined to hold either
  form without a schema change. Never plaintext in production. Same requirement as connect spec §9,
  now estate-wide and therefore non-negotiable — see §6.
- `setups` is the connect-spec §6 binding, promoted to a standalone, cross-server table.

---

## 4. Lifecycle flows (all via the central registry)

| Operation | Data plane | Control plane (management DB) |
|---|---|---|
| **Provision** (`create`, destructive) | DROP+CREATE database + schema on the target server | insert `servers` (if new) + `setups` row; acquire ownership |
| **Connect / attach** (non-destructive, spec §4) | validate schema, reconstitute queues/event-stores from the schema, start manager | upsert `setups` row; acquire ownership |
| **Auto-reload on startup** | for each owned/claimable setup → `connectToExistingSetup`, fanning out to each setup's own server | read `setups` + claim `setup_ownership` leases |
| **Destroy** | drop database | remove `setups` row; release ownership |
| **Owner failover** | new owner runs `connectToExistingSetup` | lease TTL expires → another backend claims `setup_ownership` |

The single primitive underneath all reconnect paths is `connectToExistingSetup` (spec §4); this
document only adds *where the bindings live* and *who owns an active setup*.

---

## 5. Ownership model — single-owner (decided)

**Decision (locked): each active setup is owned by exactly one backend at a time, via a lease in
`setup_ownership`.**

### Why single-owner
A setup's `PeeGeeQManager` bundles **singleton maintenance jobs** — dead-consumer-detection,
consumer-group-retry, metrics-persistence, stuck-message-recovery. These are **not safe to run
twice**: the testing-antipatterns doc already documents the consumer-group-retry job clearing
`error_message` on shared rows when two managers run against the same database concurrently. Activating
one setup in *N* backends would duplicate every one of those jobs → contention, duplicate writes, and
the exact pollution the codebase already fights. The data plane is concurrency-safe (SKIP LOCKED), but
the manager's control loop is not meant to be multi-instanced.

### How
- `setup_ownership` holds `owner_backend_id` + `lease_expires_at` + `heartbeat_at`.
- A backend **claims** a setup by atomically taking/renewing the lease (row lock / conditional update).
- The owner **heartbeats**; if it dies, the lease expires and another backend **takes over** and
  re-activates via `connectToExistingSetup`. → HA without multi-owning the manager.

### Evolution path (not now)
If consumer *throughput* ever needs to scale beyond one backend, the mature move is to **decompose the
manager**: keep maintenance/coordination single-owner (a per-setup leader) while adding stateless
**competing-consumer workers** that safely share the load via SKIP LOCKED. That is a separate, later
effort — it is explicitly *not* "activate the whole manager in many backends."

---

## 6. Security — credentials (decided)

Centralised connectivity to many servers concentrates risk, so the management DB must **never** store
plaintext credentials in production.

**Decision (locked): credentials are encrypted at rest** in the `credential` column, with the
encryption key supplied to the backend at startup (from env / a KMS-provided key — never stored beside
the data). This is the initial scheme, chosen for a simple, self-contained control plane.

**Planned migration:** move to **secret references** (`credential` → external secret manager / vault)
once available. The column is defined to hold either an encrypted blob (now) or a reference (later),
so the migration does **not** change the schema shape.

Dev/test may allow plaintext behind an explicit opt-in. Manual connect (spec §5) needs no persisted
credentials, so it can ship before the encryption work lands.

---

## 7. Decisions & non-goals

**Decided (locked):**
- **Ownership** — single-owner, lease-based (§5).
- **Registry scope** — **one org-wide** `peegeeq-management` database shared by all control-plane
  backends, which coordinate via ownership leases (not a per-backend registry).
- **Credentials** — **encryption at rest** now, with a planned migration to secret-reference / vault
  later (§6).

**Deferred / non-goals:** true multi-owner consumer scale-out (needs the §5 manager decomposition);
HA / replication of the management DB itself; estate-wide authn/authz/RBAC; auto-**discovery** of
PeeGeeQ databases the registry was never told about (reconnect works from the registry, not by
scanning servers).

---

## 8. Verification (when built)

- Multi-module backend change → mandatory pre-work (`pgq-coding-principles.md` +
  `PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md`), reactive-only, no banned patterns, TestContainers,
  `mvn clean test -Pall-tests`.
- **Cross-server reconnect:** register setups on two separate PG containers; restart the backend;
  assert both come back active against their own servers.
- **Lease takeover:** kill the owning backend; assert another claims the lease and re-activates, with
  **no duplicate maintenance jobs** running meanwhile.
- **Non-destructiveness & reconstitution:** as in connect-spec §11.
- Confirm each registry field / endpoint against a running backend before any UI consumes it.
