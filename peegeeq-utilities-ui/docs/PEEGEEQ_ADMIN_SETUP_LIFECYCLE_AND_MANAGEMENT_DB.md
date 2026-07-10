# PeeGeeQ — Setup Lifecycle & Management Database (admin control plane)

Defines what a *setup* is, how to **re-establish** one against a database whose artifacts already
exist (both **manual** and **automatic**), and the standalone, centralised **`peegeeq-management`
database** that coordinates setups distributed across many PostgreSQL servers. This is a long-standing
**backend gap** surfaced by work on `peegeeq-utilities-ui`.

> "Verified" claims were read from source on 2026-07-05. Items marked *(to confirm)* still need a
> runtime check before being relied on.

> **Status — decisions locked (see §13):** single-owner (lease-based); **one org-wide** shared
> `peegeeq-management` database; credentials **encrypted at rest** initially, with a planned move to
> secret references / vault later.

---

## 1. What a setup is (canonical definition)

**A *setup* is a named, isolated PeeGeeQ instance** — the top-level entity, identified by a unique
`setupId`, backed by a dedicated PostgreSQL **database + schema**, containing its own queues and
event stores. The code states this directly
([PeeGeeQDatabaseSetupService.java:179](../../peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/PeeGeeQDatabaseSetupService.java)):
*"A PeeGeeQ setup is an independent instance — its own database, schema, manager and LISTEN/NOTIFY
connections."* Its isolation boundary is that dedicated database/schema **and** its own
Vert.x/event-loop — never shared across setups.

A setup has **two facets**:

- **Logical (at-rest):** a database+schema provisioned with the PeeGeeQ object model — `queue_messages`,
  `outbox`, `dead_letter_queue`, event-store and consumer-group/subscription tables — plus its identity
  and connection config. Can sit in a database with **no process attached**.
- **Active (registered/running):** a logical setup a specific backend process has connected to — an
  in-memory registry entry (`activeSetups`,
  [:53](../../peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/PeeGeeQDatabaseSetupService.java))
  bound to a live `PeeGeeQManager` with its own Vert.x + connection pool. **Only an active setup is
  usable** (list queues, publish, consume).

A setup **contains** zero or more **queues** (each with its own `native`/`outbox` implementation type)
and **event stores**; a freshly provisioned setup starts with **zero queues**.

### Durability tiers

| Tier | What | Survives a full PeeGeeQ shutdown (Postgres stays up)? |
|---|---|---|
| **Artifacts** | Database, schema, PeeGeeQ tables, messages, event history | ✅ yes — must never be touched by shutdown |
| **Binding** | `setupId` ↔ `databaseConfig` (host/port/db/**credentials**) + the enumerated queues/event-stores | ❌ **no — in-memory only** today (`activeSetups` / `setupDatabaseConfigs`, :234) |
| **Runtime** | Live `PeeGeeQManager` + Vert.x + pool + LISTEN/NOTIFY | ❌ no (expected — it is a live process) |

**"Re-establish a setup" = rebuild the Binding and Runtime from the surviving Artifacts.**

---

## 2. The problem (verified)

Shutting down PeeGeeQ services (leaving Postgres running) correctly leaves the **Artifacts** untouched,
but loses the **Binding** — the record that "this database *is* setup X, reachable with these
credentials." In a real estate this bites twice: setups live on **different PostgreSQL servers**
(per-machine, per-tenant, per-region), and there is no shared, durable record of what exists or how to
reach it. Today the binding is in-memory, per REST-backend process:

- `createCompleteSetup` is the **only** way to make a setup active (:137), and it is **destructive**:
  `createDatabaseFromTemplate` runs `DROP DATABASE IF EXISTS "<db>" WITH (FORCE)`
  ([DatabaseTemplateManager.java:174](../../peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/DatabaseTemplateManager.java))
  then `CREATE DATABASE … TEMPLATE` (:223).
- There is **no** reconnect/attach/load method on `DatabaseSetupService`
  ([:40–53](../../peegeeq-api/src/main/java/dev/mars/peegeeq/api/setup/DatabaseSetupService.java)) and
  no registry persistence or reload-on-startup. `peegeeq-management-ui` has no connect concept either.

So a setup whose artifacts exist (created elsewhere, or after a restart) cannot be reached, and
`create` cannot be reused to attach — it would wipe the data.

**Key enabler:** the Postgres side is largely **self-describing** — queues, event stores, consumer
groups and subscriptions are persisted as tables/rows. So on reconnect the backend can **enumerate
what exists and rebuild the factories** *(exact enumeration path to confirm in implementation)*. The
only things not recoverable from the database itself are the arbitrary `setupId` label and the
**connection credentials**.

---

## 3. Requirement

Re-establish a logical setup as active **without provisioning, dropping, or modifying** the database —
via **both** paths, which share one core operation (§4):

- **Manual (§5):** an operator supplies connection details + credentials (+ `setupId`); the backend
  attaches on demand.
- **Automatic (§6):** the binding is persisted to a durable registry and re-established on backend
  startup with no manual step. At estate scale that registry is the `peegeeq-management` database (§8).

---

## 4. Core operation — `connectToExistingSetup` (non-destructive)

`createCompleteSetup` is already a clean pipeline
([PeeGeeQDatabaseSetupService.java:159–238](../../peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/PeeGeeQDatabaseSetupService.java)):
1. `createDatabaseFromTemplate` — **destructive**; 2. `applySchemaTemplates`; 3. start `PeeGeeQManager`
(own Vert.x + pool); 4. `validateDatabaseInfrastructure`; 5. register factories → `activeSetups.put`.

**Attach = the same pipeline minus destructive provisioning, plus reconstitution:**

| Layer | Change |
|---|---|
| **peegeeq-api** `DatabaseSetupService` | Add `Future<DatabaseSetupResult> connectToExistingSetup(DatabaseSetupRequest request)` (same DTO). |
| **peegeeq-db** `PeeGeeQDatabaseSetupService` | Refactor steps 3–5 into a shared private tail. `connectToExistingSetup` = **skip 1 & 2**, run `validateDatabaseInfrastructure` **first** (fail clearly if the PeeGeeQ schema is absent — must not create it), **reconstitute queues/event-stores by reading the existing schema** (not from the request), then the tail. `PeeGeeQManager.start()` is non-destructive, so no data is touched. |
| **peegeeq-rest** `DatabaseSetupHandler` | New route `POST /api/v1/database-setup/connect` → `connectToExistingSetup`. `RestDatabaseSetupService` / `RuntimeDatabaseSetupService` delegate. |

Both the manual (§5) and automatic (§6) paths call this same operation — they differ only in the
**trigger** and whether the binding is persisted.

---

## 5. Manual attach

- **API:** `POST /api/v1/database-setup/connect` with the connection tuple + `setupId`.
- **Errors:** schema-absent / not-a-PeeGeeQ-database → `400` with a clear message; connection/auth
  failure → surfaced, never a silent success.
- **UI:** see §12.

---

## 6. Durable registry & auto-reload

- **Registry store:** a persisted table of **bindings** — `setupId → { host, port, databaseName,
  username, schema, sslEnabled, credential }`. For a single backend this can be a table in its
  bootstrap database; at estate scale it is the standalone `peegeeq-management` database (§8). A central
  registry avoids the chicken-and-egg of "you must know the DB to read the DB."
- **Write:** on `createCompleteSetup` **and** manual `connect`, optionally persist the binding
  (opt-in flag per request) so it survives the next restart.
- **Reload:** on startup the backend reads the registry and calls `connectToExistingSetup` for each
  entry — so auto-reload is just the core operation (§4) driven from persistence instead of an API call.
- **Idempotent & resilient:** a failed reconnect for one setup (DB unreachable, schema gone) must be
  logged and skipped without aborting startup or the other setups.

---

## 7. Estate control plane vs data plane

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
the durable, central **control-plane registry** — §8.

---

## 8. The `peegeeq-management` database

A **standalone** database (its own host; not any setup's data DB). The backend is given its connection
at startup (one well-known bootstrap config); from there it discovers the whole estate. It holds
**inventory + bindings + coordination**, never queue data.

### Proposed schema (starting point)

| Table | Columns (essential) | Purpose |
|---|---|---|
| `servers` | `id`, `name`, `host`, `port`, `environment`, `credential`, `created_at`, `updated_at` | The PostgreSQL servers in the estate |
| `setups` | `setup_id` (pk), `server_id` (fk), `database_name`, `schema`, `credential`, `status`, `tags`/`tenant`, `created_at`, `updated_at` | The estate-wide **binding** (which setup, where, how to reach it) — the §6 binding, promoted to a standalone, cross-server table |
| `setup_ownership` | `setup_id` (pk/fk), `owner_backend_id`, `lease_expires_at`, `heartbeat_at` | Single-owner lease (§10) |
| `backends` | `id`, `name`, `host`, `started_at`, `last_heartbeat` | Control-plane instances (for ownership/observability) |

Credentials in the `credential` column are **encrypted at rest** — see §11.

---

## 9. Lifecycle flows (all via the central registry)

| Operation | Data plane | Control plane (management DB) |
|---|---|---|
| **Provision** (`create`, destructive) | DROP+CREATE database + schema on the target server | insert `servers` (if new) + `setups` row; acquire ownership |
| **Connect / attach** (non-destructive, §4) | validate schema, reconstitute queues/event-stores from the schema, start manager | upsert `setups` row; acquire ownership |
| **Auto-reload on startup** | for each owned/claimable setup → `connectToExistingSetup`, fanning out to each setup's own server | read `setups` + claim `setup_ownership` leases |
| **Destroy** | drop database | remove `setups` row; release ownership |
| **Owner failover** | new owner runs `connectToExistingSetup` | lease TTL expires → another backend claims `setup_ownership` |

The single primitive underneath all reconnect paths is `connectToExistingSetup` (§4); the estate layer
only adds *where the bindings live* and *who owns an active setup*.

---

## 10. Ownership model — single-owner (decided)

**Decision (locked): each active setup is owned by exactly one backend at a time, via a lease in
`setup_ownership`.**

### Why single-owner
A setup's `PeeGeeQManager` bundles **singleton maintenance jobs** — dead-consumer-detection,
consumer-group-retry, metrics-persistence, stuck-message-recovery. These are **not safe to run twice**:
the testing-antipatterns doc already documents the consumer-group-retry job clearing `error_message` on
shared rows when two managers run against the same database concurrently. Activating one setup in *N*
backends would duplicate every one of those jobs → contention, duplicate writes, and the exact
pollution the codebase already fights. The data plane is concurrency-safe (SKIP LOCKED), but the
manager's control loop is not meant to be multi-instanced.

### How
- `setup_ownership` holds `owner_backend_id` + `lease_expires_at` + `heartbeat_at`.
- A backend **claims** a setup by atomically taking/renewing the lease (row lock / conditional update).
- The owner **heartbeats**; if it dies, the lease expires and another backend **takes over** and
  re-activates via `connectToExistingSetup`. → HA without multi-owning the manager.

### Evolution path (not now)
If consumer *throughput* ever needs to scale beyond one backend, the mature move is to **decompose the
manager**: keep maintenance/coordination single-owner (a per-setup leader) while adding stateless
**competing-consumer workers** that safely share the load via SKIP LOCKED. That is a separate, later
effort — explicitly *not* "activate the whole manager in many backends."

---

## 11. Credentials & security (decided)

Both auto-reload (§6) and the estate registry (§8) persist credentials, and centralised connectivity to
many servers concentrates risk — so the store must **never** hold plaintext credentials in production.

**Decision (locked): credentials are encrypted at rest** in the `credential` column, with the
encryption key supplied to the backend at startup (from env / a KMS-provided key — never stored beside
the data). This is the initial scheme, chosen for a simple, self-contained control plane.

**Planned migration:** move to **secret references** (`credential` → external secret manager / vault)
once available. The column is defined to hold either an encrypted blob (now) or a reference (later), so
the migration does **not** change the schema shape.

Dev/test may allow plaintext behind an explicit opt-in. **Manual connect (§5) needs no persisted
credentials, so it can ship before the encryption work lands.**

---

## 12. UI — reference (management-ui) & port (utilities-ui)

- **Reference — peegeeq-management-ui:** mirror the create flow in
  [DatabaseSetups.tsx](../../peegeeq-management-ui/src/pages/DatabaseSetups.tsx) with the minimum delta:
  a **"Connect to Existing"** button + modal reusing the same form fields and `setupRequest` body
  builder, posting to `database-setup/connect`, with reworded copy (*"Connects to an existing database
  — it will not be created or modified; the PeeGeeQ schema must already exist"*) and a **"remember this
  setup"** checkbox that sets the persist flag (§6). List/details/delete unchanged. A server-inventory /
  per-server setup view surfaces the estate (§8).
- **Port — peegeeq-utilities-ui:** per the copy-management-ui directive, mirror the reference UI once it
  exists — `setupService.connectExisting(req)` → `POST /api/v1/database-setup/connect`, and a
  **"Connect to existing setup"** form **replacing** the Create Setup / Create Queue pages —
  provisioning is **admin-tool-only** (decided 2026-07-10); the utilities UI only targets setups.

---

## 13. Decisions & non-goals

**Decided (locked):**
- **Ownership** — single-owner, lease-based (§10).
- **Registry scope** — **one org-wide** `peegeeq-management` database shared by all control-plane
  backends, which coordinate via ownership leases (not a per-backend registry).
- **Credentials** — **encryption at rest** now, with a planned migration to secret-reference / vault
  later (§11).

**Deferred / non-goals:** true multi-owner consumer scale-out (needs the §10 manager decomposition);
HA / replication of the management DB itself; estate-wide authn/authz/RBAC; rotating/managing the
external secrets themselves (the registry references them); auto-**discovery** of PeeGeeQ databases the
registry was never told about (reconnect works from the registry, not by scanning servers).

---

## 14. Verification (when built)

- Multi-module Java change → mandatory pre-work (`pgq-coding-principles.md` +
  `PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md`), reactive-only, no banned patterns, TestContainers
  integration tests, `mvn clean test -Pall-tests`.
- **Non-destructiveness (the crux):** create a setup, publish rows, then `connect` from a fresh service
  instance and assert the rows **survive** and the reconstituted queues are usable.
- **Reconstitution:** attach to a database with pre-existing queues/event-stores and assert they are
  enumerated (not lost) without being re-supplied.
- **Auto-reload:** persist a binding, restart the backend, assert the setup comes back active with no
  manual step; a bad entry is skipped without aborting startup.
- **Cross-server reconnect:** register setups on two separate PG containers; restart the backend; assert
  both come back active against their own servers.
- **Lease takeover:** kill the owning backend; assert another claims the lease and re-activates, with
  **no duplicate maintenance jobs** running meanwhile.
- **Schema-absent:** `connect` to a bare database → clear `400`, no partial registration.
- Confirm each new endpoint / registry field against a running backend before any UI consumes it.
