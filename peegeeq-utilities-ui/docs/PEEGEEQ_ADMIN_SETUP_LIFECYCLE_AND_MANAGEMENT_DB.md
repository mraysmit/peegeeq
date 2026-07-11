# PeeGeeQ — Setup Lifecycle & Management Database (admin control plane)

**Author**: Mark Andrew Ray-Smith Cityline Ltd  
**Created**: 2026-07-10  
**Version**: 1.0  

Defines what a *setup* is, how to **re-establish** one against a database whose artifacts already
exist (both **manual** and **automatic**), and the standalone, centralised **`peegeeq-management`
database** that coordinates setups distributed across many PostgreSQL servers. This is a long-standing
**backend gap** surfaced by work on `peegeeq-utilities-ui`.

> "Verified" claims were read from source. Items marked *(to confirm)* still need a runtime check
> before being relied on.

> **Status — decisions locked (see §14):** single-owner (lease-based); **one org-wide** shared
> `peegeeq-management` database; the registry stores connection **coordinates only, never a password** —
> credential resolution is a pluggable `CredentialProvider` seam (§11).

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
credentials." Across many setups this is worse: they live on **different PostgreSQL servers**
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

> **⚠ This is not merely an inconvenience — it is a critical data-loss hazard.** In a system of record
> (bitemporal event history = source of truth; undelivered queue rows = committed business work),
> `create` destroying an existing database **on a name collision, silently, with no confirmation and only
> an INFO log** is a safe-by-default violation. See **§13** for the full danger analysis and the fix. The
> non-destructive split (§13/§4) is a **correctness/safety fix that should ship ahead of the estate
> features**, not after them.

**Key enabler — reconstitution gap, verified from code:** queue **names** are enumerable from the schema — each queue is a per-queue table created
`LIKE queue_template INCLUDING ALL` by the `db/templates/queue/*` templates — **but that per-queue
table appears to be an inert marker**: created at provisioning and **not read or written in any DML found
by a whole-repo source search** *(static evidence only — a runtime publish-and-inspect probe would
confirm it definitively; not yet run)*.
Native and outbox both route messages through **shared, fixed-name tables** keyed by the `topic`
column — native writes `queue_messages` and pushes via `LISTEN/NOTIFY` (channel `{schema}_queue_{topic}`),
outbox writes `outbox` and is poll-driven — so a native and an outbox queue produce **byte-identical
DDL** and there is **no naming convention** from which the implementation type can be derived. The type
exists only as the in-memory choice of `QueueFactory`
([PeeGeeQDatabaseSetupService.java:827–854](../../peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/PeeGeeQDatabaseSetupService.java)).

The gap is **broader than the type**: the entire `QueueConfig` — visibility timeout, max retries, batch
size, polling interval, FIFO, dead-letter settings
([QueueConfig.java:8–16](../../peegeeq-api/src/main/java/dev/mars/peegeeq/api/database/QueueConfig.java)) —
is unpersisted, and `setupId` itself is **in-memory only** (four `ConcurrentHashMap`s keyed by it,
[:53–56](../../peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/PeeGeeQDatabaseSetupService.java);
lost on restart). (**Bitemporal** event stores *are* distinguishable — a different
`event_store_template` column set plus an optional `{tableName}_aggregate_summary` companion — but that
tells a queue apart from an event store, never native from outbox.)

**So reconstitution requires the setup to record its own identity and contents** — a per-schema
**object registry** + a **`peegeeq_setup_metadata`** row (the reconstitution data model, §4). After that,
the only thing **not** recoverable from the setup's own database is the **password** (by design — §11);
connection **coordinates** live in the estate registry (§8), not in the setup's schema. (S.2 therefore
gains a sub-step: add these tables to the provisioning + queue-creation paths before or with
`connectToExistingSetup`.)

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

### Reconstitution data model — per-schema registry tables

Because implementation type and full config are **not** otherwise recoverable (§2), a setup must record
its own contents and identity **inside its own schema**, written **transactionally at the creation
point** so the record can never drift from the artifacts:

- **`peegeeq_object_registry`** — one row per queue / event-store, written in the same transaction that
  creates the object (the `addQueue` path,
  [:601–605](../../peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/PeeGeeQDatabaseSetupService.java);
  the bulk provisioning loop, [:338–350](../../peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/PeeGeeQDatabaseSetupService.java);
  and the `addEventStore` path, [:640](../../peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/PeeGeeQDatabaseSetupService.java)):

  | Column | Purpose |
  |---|---|
  | `object_name` (pk) | queue or event-store name |
  | `kind` | `native` \| `outbox` \| `bitemporal` |
  | `config` (jsonb) | the full `QueueConfig` / `EventStoreConfig` |
  | `created_at` | |

  It lives in the setup's schema, so it is implicitly scoped to that setup — **no `setup_id` column** is
  needed unless these rows are ever consolidated into the estate registry.

- **`peegeeq_setup_metadata`** — a **single row**, written once at provisioning, that self-declares the
  setup's identity:

  | Column | Purpose |
  |---|---|
  | `setup_id` (pk) | the arbitrary label, now durable |
  | `schema_name` | |
  | `schema_version` | for future migrations |
  | `created_at` | |

  This makes the schema **self-identifying** ("this schema *is* setup X"). It also lets the estate
  registry (§8) be cross-checked or rebuilt from the databases themselves — only the password can't be
  recovered that way (§11).

`connectToExistingSetup` therefore **reconstitutes from these tables** — reading `peegeeq_setup_metadata`
to recover the `setupId` and `peegeeq_object_registry` to rebuild the queue/event-store factories with
their exact type + config — rather than inferring anything from table shapes, and repopulating the
in-memory maps from the database instead of losing them on restart.

---

## 5. Manual attach

- **API:** `POST /api/v1/database-setup/connect` with the connection tuple + `setupId`.
- **Errors:** schema-absent / not-a-PeeGeeQ-database → `400` with a clear message; connection/auth
  failure → surfaced, never a silent success.
- **UI:** see §12.

---

## 6. Durable registry & auto-reload

- **Registry store:** a persisted table of **bindings** — `setupId → { host, port, databaseName,
  username, schema, sslEnabled, credential_ref }` (coordinates + an opaque, nullable secret pointer —
  **never a password**, §11). For a single backend this can be a table in its
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
| `servers` | `id`, `name`, `host`, `port`, `environment`, `username`, `credential_ref` (nullable), `created_at`, `updated_at` | The PostgreSQL servers in the estate |
| `setups` | `setup_id` (pk), `server_id` (fk), `database_name`, `schema`, `username`, `credential_ref` (nullable), `status`, `tags`/`tenant`, `created_at`, `updated_at` | The estate-wide **binding** (which setup, where, how to reach it) — the §6 binding, promoted to a standalone, cross-server table |
| `setup_ownership` | `setup_id` (pk/fk), `owner_backend_id`, `lease_expires_at`, `heartbeat_at` | Single-owner lease (§10) |
| `backends` | `id`, `name`, `host`, `started_at`, `last_heartbeat` | Control-plane instances (for ownership/observability) |

The registry stores connection **coordinates only** — host/port/database/schema/username plus an opaque,
nullable `credential_ref`. It **never stores a password** — see §11.

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

The estate registry (§8) and auto-reload (§6) need to *reach* many servers, which concentrates risk — so
the control plane must **never hold a password**. The registry stores connection **coordinates**
(host/port/database/schema/username) and, optionally, an **opaque `credential_ref`**; the password itself
is **resolved at connect time and never persisted** by PeeGeeQ.

**Decision (locked — revises the earlier "encrypted at rest" scheme): PeeGeeQ stores no credential
material at all.** Because PeeGeeQ is **open-source and not built for any one organisation**, it cannot
host, bundle, or assume a particular secret store (Vault, cloud secret managers, K8s secrets, an HSM…).
It owns only a **`CredentialProvider` seam**:

- **Core ships one default — supplied-at-connect (zero dependencies):** the caller hands the password to
  the connect call (exactly what `PgConnectOptions.setPassword(...)` already does). Nothing external is
  contacted, nothing is stored. This is option A of §5.
- **Adopters bring their own provider** for whatever secret store they run, keyed by the opaque
  `credential_ref` — which PeeGeeQ passes through **verbatim and never interprets**. Authentication to
  that store uses the adopter's runtime **ambient identity** (K8s ServiceAccount, cloud IAM role, Vault
  AppRole…), never a secret PeeGeeQ holds — otherwise the bootstrap is turtles-all-the-way-down.
  Reference providers may be published later as **separate opt-in modules** so core stays
  dependency-free.

The `credential_ref` column is **opaque and nullable**: `null` → core's supplied-at-connect default; a
value → the adopter's provider resolves it. Provider selection is **explicit, instance-scoped config**
(constructor/config-injected like everything else) — **not** an env/system-property sweep, keeping the
no-system-properties rule intact.

**Manual connect (§5) needs no persisted credential at all**, so it can ship first; the estate registry +
provider seam follow.

---

## 12. UI — reference (management-ui) & port (utilities-ui)

This whole feature is net-new — it is built here, not somewhere else. Build order: **(a)** backend core
op + non-destructive split (§4, §13) → **(b)** connect flow in the *reference* management-ui → **(c)**
port to utilities-ui. The reference create flow (§12.1) is only the **UI baseline** the connect form is a
delta against; the connect *behaviour* it drives does not exist until (a) lands.

### 12.1 The reference create flow being mirrored (verified from source)

"The minimum delta" only means something against a known baseline, so here is the baseline —
[DatabaseSetups.tsx](../../peegeeq-management-ui/src/pages/DatabaseSetups.tsx), a single page with a
**Create Setup modal** and a **View Details modal**:

| Piece | What it is (verified) |
|---|---|
| **List** | `GET /api/v1/setups` → `{ setupIds }`, then `GET /api/v1/setups/{id}` per row for queue/event-store counts + status ([:57–101](../../peegeeq-management-ui/src/pages/DatabaseSetups.tsx)) |
| **Create modal fields** | `setupId`, `host` (default `localhost`), `port` (default `5432`), `databaseName`, `username` (default `peegeeq`), `password`, `schema`, `sslEnabled` ([:328–412](../../peegeeq-management-ui/src/pages/DatabaseSetups.tsx)) |
| **Body builder** | `{ setupId, databaseConfig: { host, port, databaseName, username, password, schema, sslEnabled, templateDatabase: 'template0', encoding: 'UTF8' }, queues: [], eventStores: [] }` ([:162–177](../../peegeeq-management-ui/src/pages/DatabaseSetups.tsx)) |
| **Submit** | `POST /api/v1/database-setup/create`, **timeout 120 000 ms** ([:179–181](../../peegeeq-management-ui/src/pages/DatabaseSetups.tsx)) |
| **Warning banner** | `Alert` *"Creating a setup will create a new PostgreSQL database. Ensure your database user has CREATEDB permission."* ([:320–326](../../peegeeq-management-ui/src/pages/DatabaseSetups.tsx)) |
| **Delete** | `Modal.confirm` → `DELETE /api/v1/database-setup/{setupId}` — copy: *"Delete the database … This action cannot be undone"* ([:112–143](../../peegeeq-management-ui/src/pages/DatabaseSetups.tsx)) |
| **Notifications / errors** | success + `managementStore.addNotification`; every catch surfaces via `message.error(...)` (no swallowing) ([:136–139, 183–191](../../peegeeq-management-ui/src/pages/DatabaseSetups.tsx)) |
| **Details modal** | `Descriptions` over `GET /api/v1/setups/{id}` ([:415–443](../../peegeeq-management-ui/src/pages/DatabaseSetups.tsx)) |

### 12.2 The connect delta (what actually changes vs create)

A **"Connect to Existing"** button + modal that **reuses the create modal's fields and `databaseConfig`
body builder verbatim**, changing only:

| Aspect | Create (today) | Connect (new) |
|---|---|---|
| **Endpoint** | `POST database-setup/create` | `POST database-setup/connect` (§4/§5) |
| **`setupId` meaning** | a new arbitrary label | must name an **existing** provisioned setup; on connect it is recovered from `peegeeq_setup_metadata` (§4) — treat the field as "expected id", validate against the reconstituted value |
| **`queues` / `eventStores` in body** | authored by the user | **omit / ignore** — reconstituted from the schema, never from the request (§4). Do not let the form imply the user defines contents on connect |
| **Warning banner** | CREATEDB permission warning | **replace** with *"Connects to an existing database — it will not be created or modified; the PeeGeeQ schema must already exist."* |
| **`password` field** | stored in the created setup's config | still **required at connect time** (supplied-at-connect, §11) — but see the "remember" note: it is used to connect and is **not persisted** |
| **"Remember this setup" checkbox (new)** | n/a | sets the **opt-in persist flag** (§6). When checked, the backend persists the **binding coordinates** (+ optional `credential_ref`) to the registry for auto-reload — **never the password** (§11). Word the checkbox so it does not imply the password is saved |
| **Error mapping** | 409-on-exists (concurrent-create race, §13) | schema-absent / not-a-PeeGeeQ-database → **400 with a clear message** (§5), surfaced via `message.error` — never a silent success |

List / Details modal are unchanged. A **server-inventory / per-server view** (§8) is a separate, later
addition: a read-only view over the `peegeeq-management` DB showing `servers`, the `setups` bound to
each, and ownership/lease status from `setup_ownership` (§10). Not part of the first connect slice.

### 12.3 ⚠ Delete semantics — detach vs destroy (a §13 hazard surfaced through the UI)

The reference's delete calls `DELETE /api/v1/database-setup/{setupId}`, which **drops the database**, and
its confirm copy says so. Offering that same action on a setup the operator merely **connected** to —
data this tool did **not** create and does not own — is exactly the §13 destructive-by-default hazard,
now one click away in the UI. **Severity: high — irreversible loss of a system of record on data you
don't own.**

The connect UI must therefore separate two actions:

- **Detach / deregister (default for a connected setup):** drop only the in-memory binding (+ the
  registry row if remembered) and stop the manager. **Artifacts untouched.** This is the natural inverse
  of connect. *(Backend op not yet present — no detach/deregister endpoint exists in source; it is part
  of the §4/§9 "release ownership" path and must be built.)*
- **Destroy (drop database):** the §13 explicit-overwrite/destroy path only — behind distinct copy, a
  danger-styled confirm, and never the default for a setup that was connected rather than created.

Do **not** reuse the reference's "Delete the database … cannot be undone" confirm for the connect flow's
primary action; that wording belongs only to the explicit destroy path.

### 12.4 Port — peegeeq-utilities-ui (current state → target)

Per the copy-management-ui directive the port mirrors the reference **once (b) exists**, but the
utilities-ui baseline differs from the reference in ways that change the work:

- **Pages, not a modal.** utilities-ui uses routed pages —
  [CreateSetupPage.tsx](../src/pages/CreateSetupPage.tsx) (`/generator/setup/new`) and
  [CreateQueuePage.tsx](../src/pages/CreateQueuePage.tsx) (`/setups/:setupId/queues/new`), plus
  [SetupsPage.tsx](../src/pages/SetupsPage.tsx) / [SetupDetailPage.tsx](../src/pages/SetupDetailPage.tsx)
  ([App.tsx:115–120](../src/App.tsx)) — whereas the reference uses a modal on one page. **Decide up
  front:** the port follows the utilities-ui **page** idiom (a `ConnectSetupPage`), not the reference's
  modal — "mirror the reference" means mirror its **fields, body builder, endpoints, and copy**, not its
  modal-vs-page shell. (Open decision — confirm with the user before building.)
- **Service layer gap.** [setupService.ts](../src/services/setupService.ts) has `createSetup` →
  `POST database-setup/create`, `getSetups`, `getQueues`, `getSetupDetails`, `deleteSetup` — but **no
  `connectExisting`**. Add `connectExisting(req): Promise<void>` → `POST /api/v1/database-setup/connect`
  (same 120 s timeout, same `CreateSetupRequest` DTO shape), mirroring the existing `createSetup`
  exactly.
- **Replace the Create pages.** Provisioning is **admin-tool-only** (LOCKED, §14); the utilities UI only
  **targets** setups. So `CreateSetupPage` / `CreateQueuePage` and their routes
  ([App.tsx:117–118](../src/App.tsx)) are to be **replaced** by the connect form and retired.
- **Everything else per §12.1–12.3** — reuse the `databaseConfig` body builder, the "will not
  create/modify" copy, the remember checkbox, the §5 error mapping, and the detach-vs-destroy split;
  preserve `data-testid` conventions and the surface-every-error rule already in the utilities-ui pages.

### 12.5 Post-connect repopulation — the UI must rehydrate the setup's whole object graph

A successful `connect` is not "close the form and show a toast." It makes a setup that the UI **could not
previously see** active, so the UI must then **load and display that setup's full object graph** — the
same objects it shows for any active setup. The connect POST returning `200` is only step one; the UI is
not done until those objects are on screen.

**What "all the setup objects" means concretely in utilities-ui (verified from source):**

| Object | Source call | Where it renders |
|---|---|---|
| The setup itself (now selectable) | `GET /api/v1/setups` → `getSetups()` | [TargetSelector](../src/components/TargetSelector.tsx) setup `Select`; [SetupsPage](../src/pages/SetupsPage.tsx) list |
| **Queues + `implementationType`** (native/outbox) | `GET /api/v1/setups/{id}/queues` → `listQueueDetails()` | [TargetSelector](../src/components/TargetSelector.tsx) queue `Select` (shows `name (type)`); [SetupDetailPage](../src/pages/SetupDetailPage.tsx) queue list + type tag |
| Status + **event stores** | `GET /api/v1/setups/{id}` → `getSetupDetails()` | [SetupDetailPage](../src/pages/SetupDetailPage.tsx) descriptions + event-store list |

So the post-connect sequence is: **connect succeeds → re-run `getSetups()` → select the just-connected
`setupId` → its selection re-fires the existing effects that call `listQueueDetails()` +
`getSetupDetails()`** ([TargetSelector.tsx:66–74](../src/components/TargetSelector.tsx) already does exactly
this on setup change). The connect flow does **not** need a bespoke repopulation path — it needs to feed
the new `setupId` into the **same load effects** the UI already runs, and land the operator on that setup
(selected in TargetSelector, or navigated to its SetupDetailPage) so the rehydration is visible, not
silent.

**⚠ The repopulation is only as complete as the backend's §4 reconstitution — this is the load-bearing
coupling.** The UI **cannot** derive a queue's type or config from the schema — that is the exact §2 gap
(native and outbox produce byte-identical DDL). [listQueueDetails](../src/services/queueService.ts#L20)
already encodes the failure mode: any `implementationType` that is not `'native'`/`'outbox'` is mapped to
**`null`**, and the UI then renders the queue with **no type tag** (SetupDetailPage) and **no `(type)`
suffix** (TargetSelector). Therefore:

- If `connectToExistingSetup` reconstitutes `peegeeq_object_registry` (§4), `GET .../queues` returns real
  `queueDetails[]` types and the UI repopulates **fully** — types, tags, selectable targets.
- If it does not (e.g. a setup provisioned before the registry tables existed, or reconstitution partial),
  the same endpoint returns `implementationType: null` and the UI repopulates **degraded but honest** —
  queues present and selectable, types blank. It must **not** fabricate a type. Whether a blank-type queue
  is even usable as a **publish target** (the whole point of utilities-ui) depends on whether the backend
  can route without the reconstituted type — *(to confirm at build time; do not assume)*.

This is why the reconstitution data model (§4) is a **prerequisite** of the connect UI, not a parallel
nicety: without it the UI can list a reconnected setup's queues by name but cannot present them as typed,
targetable objects.

**Stale generator state.** A run target in the generator is a `{ setupId, queueName }` pair
([App.tsx:16](../src/App.tsx)); the [generatorStore](../src/stores/generatorStore.ts) holds run config/state
but **not** the target list. On (re)connect nothing in the store needs migrating — but any target the UI
had selected against a setup that was **absent and is now present** should be re-resolved through the
reloaded `listQueueDetails()` result, not trusted from stale component state, so a target can't point at a
queue the reconnected setup no longer has.

---

## 13. Non-destructive provisioning — `create` / `connect` / `recreate`

`createCompleteSetup` is today the **only** way to make a setup active, and it is **unconditionally
destructive** — provisioning and activation are conflated, so the act of *reaching* a setup is also the
act that wipes it. This section specifies the split that removes that hazard. (The reconstitution half —
`connectToExistingSetup` — is §4; this section adds the guard on the *provisioning* path itself.)

### Design

**The hazard (verified from source):**

- `createCompleteSetup` calls `createDatabaseFromTemplate` at the head of its pipeline
  ([PeeGeeQDatabaseSetupService.java:162](../../peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/PeeGeeQDatabaseSetupService.java)),
  before anything else runs.
- `createDatabaseFromTemplate` does check `databaseExists`, but on **true** it **force-evicts every live
  connection** (`pg_terminate_backend` over `pg_stat_activity`) and issues `DROP DATABASE … WITH (FORCE)`,
  logging only at **INFO** — *"already exists, dropping and recreating"* *(read from source; the DROP and
  force-eviction are unambiguous in the SQL/control flow, but not observed on a live run)*
  ([DatabaseTemplateManager.java:72–82, 150–174](../../peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/DatabaseTemplateManager.java)).
- **No layer refuses or warns before the drop.** The REST `"already exists"` → **409** branch
  ([DatabaseSetupHandler.java:120–122](../../peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/DatabaseSetupHandler.java))
  is fed by `isDatabaseCreationConflict` (matches `duplicate key … pg_database_datname_index`), i.e. a
  **concurrent-CREATE race** handled *downstream* of the drop — it never protects existing data. It is a
  guard-shaped path that guards nothing, and is easy to mistake for real protection.

**⚠ Why this is dangerous (not just "destructive") — severity: critical.** PeeGeeQ is a **system of
record**: a bitemporal event store's history is the source of truth (often the audit trail), and
undelivered queue rows are committed-but-unprocessed business work (orders, payments). `DROP DATABASE`
is **irreversible** — no soft-delete, tombstone, or backup hook anywhere in the path.

- **The trigger is a name collision, not intent.** Destruction fires whenever a `create` names an
  existing database — the exact shape of an *accident*: a retried/replayed request, an IaC/automation
  re-apply, a copy-pasted `setupId`, a reused example config (`demo_setup_db`). The normal API intuition
  ("re-POSTing `create` is a safe no-op or a `409`") is silently inverted into a wipe.
- **Irreversible loss of the source of truth.** Event-sourced history cannot be rebuilt; for the
  financial-services use this project targets, that is a compliance/audit breach, not just downtime.
  In-flight messages vanish with **no error to the producer** — downstream believes work was emitted that
  never arrives.
- **Blast radius beyond the target.** `WITH (FORCE)` + `pg_terminate_backend` kills **every** live
  connection to that database — other setups, running producers/consumers, external tooling — so
  "recreate one setup" can take down unrelated live workloads.
- **Automation amplifies it.** The control plane in this document runs create/reconnect/failover
  programmatically across many servers; a destructive-by-default primitive in an auto-reload or CI
  re-apply can wipe **many** setups unattended, with no human "are you sure?" in the loop.
- **Undetectable until too late.** Logged at **INFO**, with no WARN/alert/audit event — the loss reads as
  a routine log line, and nothing ties it back to the `create` call in forensics.
- **No safety net.** No confirmation, dry-run, force flag, backup hook, or rename-aside at any layer.

Safe-by-default is the cardinal rule of a persistence system; here destruction is the default *and only*
path, and the safe operation (`connect`, §4) does not yet exist. **The split below is therefore a
correctness/safety fix, not an enhancement, and should ship ahead of the estate features (§6–§11).**

**Target model — three distinct operations, destruction only ever explicit:**

| Operation | Existing database | Behaviour |
|---|---|---|
| **`create`** (provision) | present | **Refuse** — fail loudly (`409`, WARN) **before any drop**; data untouched |
| **`create`** (provision) | absent | Provision as today |
| **`connect`** (§4) | present | Non-destructive attach + reconstitute |
| **`recreate`** (explicit overwrite) | present | The **only** path allowed to drop — opt-in (`overwrite: true` / dedicated op), WARN before dropping |

Together with §4 this yields: **connect** never destroys, **create** refuses if the setup already
exists, **recreate** is the single deliberate destructive path. Destruction stops being the silent
default.

### Implementation

| # | Change | Where |
|---|---|---|
| 1 | Add `overwrite` (boolean, default `false`) to `DatabaseSetupRequest`. | peegeeq-api |
| 2 | Thread `overwrite` into `createDatabaseFromTemplate`; when `databaseExists && !overwrite`, return a failed `Future` with a typed, actionable message **before** `dropDatabase` runs. | `DatabaseTemplateManager` |
| 3 | Only under `overwrite` may the force-drop run; change its drop log from INFO to **WARN** with a `DESTRUCTIVE` marker. | `DatabaseTemplateManager` |
| 4 | `createCompleteSetup` passes `request.isOverwrite()` through; no other behavioural change. | `PeeGeeQDatabaseSetupService` |
| 5 | REST: map the new "exists, no overwrite" failure to **`409`** with an actionable message — *"Setup already exists; use `POST …/database-setup/connect` to attach, or resubmit with `overwrite=true` to recreate (DESTRUCTIVE, drops all data)."* Keep it **distinct** from the concurrency-race 409 (do not fold into `isDatabaseCreationConflict`). | `DatabaseSetupHandler` |
| 6 | Tests (TestContainers, no mocking): (a) existing DB + `overwrite=false` → `409`, rows **survive**, assert no DROP/eviction occurred; (b) existing DB + `overwrite=true` → recreated; (c) absent DB → created; (d) guard fires **before** any connection eviction. | peegeeq-db / peegeeq-rest ITs |

Production Java change → mandatory pre-work (`pgq-coding-principles.md` +
`PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md`), reactive-only, no banned patterns. Tracked as workstream
**G** in Appendix A.

---

## 14. Decisions & non-goals

**Decided (locked):**
- **Ownership** — single-owner, lease-based (§10).
- **Registry scope** — **one org-wide** `peegeeq-management` database shared by all control-plane
  backends, which coordinate via ownership leases (not a per-backend registry).
- **Credentials** — PeeGeeQ stores **no password**, only connection coordinates + an opaque
  `credential_ref`; resolution is a pluggable **`CredentialProvider`** seam (core default =
  supplied-at-connect; adopters bring their own store — §11). Open-source: no vault is bundled or assumed.
- **Self-describing setups** — each schema carries a `peegeeq_object_registry` (queue/event-store `kind`
  + `config`) and a single `peegeeq_setup_metadata` row (`setup_id`), written transactionally at creation
  (§4), so `connectToExistingSetup` reconstitutes from the database rather than inferring type.

**Deferred / non-goals:** true multi-owner consumer scale-out (needs the §10 manager decomposition);
HA / replication of the management DB itself; estate-wide authn/authz/RBAC; rotating/managing the
external secrets themselves (the registry references them); auto-**discovery** of PeeGeeQ databases the
registry was never told about (reconnect works from the registry, not by scanning servers).

---

## 15. Verification (when built)

- Multi-module Java change → mandatory pre-work (`pgq-coding-principles.md` +
  `PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md`), reactive-only, no banned patterns, TestContainers
  integration tests, `mvn clean test -Pall-tests`.
- **Non-destructiveness (the crux):** create a setup, publish rows, then `connect` from a fresh service
  instance and assert the rows **survive** and the reconstituted queues are usable.
- **Reconstitution:** attach to a database with pre-existing queues/event-stores and assert they are
  enumerated (not lost) without being re-supplied — including that each object's **`kind`** (native /
  outbox / bitemporal) and **full config** are restored from `peegeeq_object_registry`, and the
  `setupId` from `peegeeq_setup_metadata` — since neither is otherwise derivable from the schema (§2).
- **Auto-reload:** persist a binding, restart the backend, assert the setup comes back active with no
  manual step; a bad entry is skipped without aborting startup.
- **Cross-server reconnect:** register setups on two separate PG containers; restart the backend; assert
  both come back active against their own servers.
- **Lease takeover:** kill the owning backend; assert another claims the lease and re-activates, with
  **no duplicate maintenance jobs** running meanwhile.
- **Schema-absent:** `connect` to a bare database → clear `400`, no partial registration.
- Confirm each new endpoint / registry field against a running backend before any UI consumes it.

---

## Appendix A — Task tracking

> Granular, checkable tasks for the whole effort in this document. **Status:** ☐ not started · ◐ in
> progress · ☑ done · ⊘ blocked/deferred. The `Ref` column points at the driving section. Update status
> **in place** — keep completed rows for history; do not delete them.
>
> **Dependency order:** **B** (self-describing schema) → **A** (connect core), which consume it; **G**
> (provisioning guard) is independent and should ship early as a safety fix; **C/D/E** (durable registry,
> estate DB, leases) build the control plane; **F** (credential seam) can land alongside; **U** (UI) is
> last; **V** (verification) is cross-cutting. Nothing below is started yet.

### W-A — Non-destructive connect core (§4)

| ID | Task | Ref | Status |
|---|---|---|---|
| A1 | Add `Future<DatabaseSetupResult> connectToExistingSetup(DatabaseSetupRequest)` to `DatabaseSetupService` | §4 | ☐ |
| A2 | Refactor `createCompleteSetup` steps 3–5 into a shared private tail (manager start → validate → register) | §4 | ☐ |
| A3 | Implement `connectToExistingSetup`: skip provisioning, run `validateDatabaseInfrastructure` **first** (fail if schema absent, never create), reconstitute (W-B), then the shared tail | §4 | ☐ |
| A4 | REST route `POST /api/v1/database-setup/connect` → `connectToExistingSetup` | §4/§5 | ☐ |
| A5 | `RestDatabaseSetupService` / `RuntimeDatabaseSetupService` delegate the new method | §4 | ☐ |
| A6 | Add non-destructive **detach**: `Future<Void> detachSetup(setupId)` — deregister the in-memory binding + stop the manager, **never drops the database** (the inverse of connect; distinct from the DB-dropping delete). Release the ownership lease (W-E) if held | §12.3/§9 | ☐ |
| A7 | REST route for detach, **distinct** from the destructive `DELETE /api/v1/database-setup/{setupId}` (which drops the DB) → `detachSetup` | §12.3/§9 | ☐ |

### W-B — Self-describing schema (reconstitution data model, §4)

| ID | Task | Ref | Status |
|---|---|---|---|
| B1 | DDL template for per-schema `peegeeq_object_registry` (`object_name` pk, `kind`, `config` jsonb, `created_at`) | §4 | ☐ |
| B2 | DDL template for single-row `peegeeq_setup_metadata` (`setup_id` pk, `schema_name`, `schema_version`, `created_at`) | §4 | ☐ |
| B3 | Write the object-registry row **transactionally** in the `addQueue` path (:601–605) | §4 | ☐ |
| B4 | Write it in the bulk provisioning loop (:338–350) and the `addEventStore` path (:640) | §4 | ☐ |
| B5 | Write the `setup_metadata` row once at provisioning | §4 | ☐ |
| B6 | `connectToExistingSetup` reads both tables to rebuild queue/event-store factories (exact `kind` + config) and recover `setupId` | §4 | ☐ |
| B7 | Back-compat for setups provisioned before these tables exist (graceful absence / backfill) | §4 | ☐ |

### W-G — Non-destructive provisioning guard (§13) — *the create/connect/recreate fix*

| ID | Task | Ref | Status |
|---|---|---|---|
| G1 | Add `overwrite` (boolean, default `false`) to `DatabaseSetupRequest` | §13 | ☐ |
| G2 | `createDatabaseFromTemplate`: when `databaseExists && !overwrite`, return a failed `Future` with a typed, actionable message **before** `dropDatabase` | §13 | ☐ |
| G3 | Force-drop runs **only** under `overwrite`; change the drop log INFO → **WARN** with a `DESTRUCTIVE` marker | §13 | ☐ |
| G4 | `createCompleteSetup` passes `request.isOverwrite()` through | §13 | ☐ |
| G5 | REST: distinct **409** + actionable message (connect vs `overwrite=true`); keep separate from the `isDatabaseCreationConflict` race handler | §13 | ☐ |
| G6 | ITs: (a) exists + no-overwrite → 409, data survives, no drop/eviction; (b) exists + overwrite → recreated; (c) absent → created; (d) guard fires before any eviction | §13/§15 | ☐ |

### W-C — Durable registry & auto-reload (§6)

| ID | Task | Ref | Status |
|---|---|---|---|
| C1 | Binding table schema (`setupId` → coordinates + `credential_ref`; **no password**) | §6 | ☐ |
| C2 | Persist binding on `create` **and** `connect` behind an opt-in "remember" flag | §6 | ☐ |
| C3 | Startup reload: iterate registry → `connectToExistingSetup` per entry | §6 | ☐ |
| C4 | Resilient reload: a bad entry is logged and skipped without aborting startup or other setups | §6 | ☐ |

### W-D — Estate management DB (§8–§9)

| ID | Task | Ref | Status |
|---|---|---|---|
| D1 | `peegeeq-management` bootstrap connection config (one well-known entry) | §8 | ☐ |
| D2 | `servers` table | §8 | ☐ |
| D3 | `setups` table (coordinates + `credential_ref`, no password) | §8 | ☐ |
| D4 | `backends` table | §8 | ☐ |
| D5 | Wire lifecycle flows (provision / connect / reload / destroy / failover) to upsert the right rows | §9 | ☐ |

### W-E — Ownership / leases (§10)

| ID | Task | Ref | Status |
|---|---|---|---|
| E1 | `setup_ownership` table (`owner_backend_id`, `lease_expires_at`, `heartbeat_at`) | §10 | ☐ |
| E2 | Atomic claim/renew (row lock / conditional update) | §10 | ☐ |
| E3 | Heartbeat loop + lease-TTL expiry | §10 | ☐ |
| E4 | Takeover: expired lease → another backend claims + `connectToExistingSetup` | §10 | ☐ |
| E5 | Maintenance jobs run **only** while the lease is held (assert no duplicate jobs) | §10 | ☐ |

### W-F — Credential seam (§11)

| ID | Task | Ref | Status |
|---|---|---|---|
| F1 | `CredentialProvider` interface | §11 | ☐ |
| F2 | Default `SuppliedCredentialProvider` (uses `setPassword`; zero deps) | §11 | ☐ |
| F3 | Opaque `credential_ref` pass-through — core never interprets it | §11 | ☐ |
| F4 | Explicit instance-scoped provider selection (no env/system-property sweep) | §11 | ☐ |
| F5 | Reference provider modules (Vault/cloud/K8s) as separate opt-in artifacts | §11 | ⊘ deferred |

### W-U — UI (§12)

| ID | Task | Ref | Status |
|---|---|---|---|
| U1 | management-ui: "Connect to Existing" button + modal (reuse form + `setupRequest` builder) | §12 | ☐ |
| U2 | management-ui: "remember this setup" checkbox → persist flag (W-C) | §12 | ☐ |
| U3 | management-ui: server-inventory / per-server setup view | §12 | ☐ |
| U4 | utilities-ui: `setupService.connectExisting(req)` + connect form replacing the Create pages | §12 | ☐ |
| U5 | utilities-ui: **post-connect repopulation** — on connect success, reload the setup's object graph (`getSetups` → select the connected `setupId` → its effects re-run `listQueueDetails` + `getSetupDetails`) and land the operator on that setup so rehydration is visible; degrade honestly (blank type) when W-B reconstitution is absent, never fabricate a type | §12.5 | ☐ |
| U6 | Both UIs: **detach-vs-destroy** — make the connected-setup removal default to non-destructive **detach** (W-A A6/A7); gate destroy (drop DB) behind distinct copy + a danger-styled confirm; do not reuse the "drops the database … cannot be undone" wording for the detach action | §12.3 | ☐ |

### W-V — Verification (cross-cutting, §15)

| ID | Task | Ref | Status |
|---|---|---|---|
| V1 | Non-destructiveness IT: create → publish → `connect` from a fresh instance → rows survive | §15 | ☐ |
| V2 | Reconstitution IT: `kind` + full config + `setupId` restored from the registry tables | §15 | ☐ |
| V3 | Auto-reload IT: persist binding → restart → active with no manual step; bad entry skipped | §15 | ☐ |
| V4 | Cross-server reconnect IT: setups on two PG containers both return active | §15 | ☐ |
| V5 | Lease-takeover IT: kill owner → another claims + re-activates, no duplicate maintenance jobs | §15 | ☐ |
| V6 | Schema-absent → clear `400`, no partial registration | §15 | ☐ |

### W-P — Correctness/safety bugs (prerequisites)

Found during analysis; **static reads — each must be runtime-reproduced before fixing**, then fixed
under mandatory pre-work (`pgq-coding-principles.md` + `PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md`).
Prerequisite in the sense they are correctness/safety defects that should clear ahead of feature work.

| ID | Bug | Location | Severity | Status |
|---|---|---|---|---|
| P0 | Destructive `create` wipes an existing database on name collision (silent, INFO-only, no guard) | §13; `DatabaseTemplateManager.java:72–82` | critical | ☐ (= W-G) |
| P1 | Silent partial setup — `createQueueFactories` continues on a per-queue factory failure → setup reports **ACTIVE with queues missing** (violates no-error-swallowing) | `PeeGeeQDatabaseSetupService.java:863–864` | high | ☐ |
| P2 | `pg_notify` failure swallowed (warn-only) while the insert succeeds | `PgNativeQueueProducer.java:196–199` | high | ☐ |
| P3 | No polling fallback in `LISTEN_NOTIFY_ONLY` — a missed/failed NOTIFY leaves a message stuck invisibly (compounds P2 into a delivery-loss path) | `PgNativeQueueConsumer.java:179` | high | ☐ |
