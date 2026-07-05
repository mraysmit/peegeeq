# PeeGeeQ — Setup Definition, Connect & Reconnect (Spec)

Defines what a *setup* is in PeeGeeQ, and specifies how to **re-establish** a setup against a
database whose artifacts already exist — both **manually** (operator-supplied) and **automatically**
(durable registry + reload on startup). This is a long-standing **backend gap** surfaced by work on
`peegeeq-utilities-ui`.

> "Verified" claims were read from source on 2026-07-05. Items marked *(to confirm)* still need a
> runtime check before being relied on.

---

## 1. Definition of a setup (canonical)

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

## 2. Problem (verified)

Shutting down PeeGeeQ services (leaving Postgres running) correctly leaves the **Artifacts**
untouched, but loses the **Binding** — the record that "this database *is* setup X, reachable with
these credentials." Today that binding is in-memory only:

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
  startup with no manual step.

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

Both §5 and §6 call this same operation — they differ only in the **trigger** and whether the binding
is persisted.

---

## 5. Manual attach

- **API:** `POST /api/v1/database-setup/connect` with the connection tuple + `setupId`.
- **Errors:** schema-absent / not-a-PeeGeeQ-database → `400` with a clear message; connection/auth
  failure → surfaced, never a silent success.
- **UI:** see §7 (reference) and §8 (utilities-ui port).

---

## 6. Durable registry + auto-reload

> At estate scale (setups spread across many PostgreSQL servers) this registry becomes a standalone,
> centralised control-plane database — see
> [PEEGEEQ_MANAGEMENT_DATABASE_ARCHITECTURE.md](PEEGEEQ_MANAGEMENT_DATABASE_ARCHITECTURE.md), which
> also defines the single-owner lease model for coordinating multiple backends.

- **Registry store:** a persisted table of **bindings** — `setupId → { host, port, databaseName,
  username, schema, sslEnabled, credentialRef }` — kept in the backend's **system/bootstrap database**
  (the one the backend already connects to at startup; a central registry avoids the chicken-and-egg of
  "you must know the DB to read the DB").
- **Write:** on `createCompleteSetup` **and** manual `connect`, optionally persist the binding
  (opt-in flag per request) so it survives the next restart.
- **Reload:** on startup the backend reads the registry and calls `connectToExistingSetup` for each
  entry — so auto-reload is just the core operation (§4) driven from persistence instead of an API call.
- **Idempotent & resilient:** a failed reconnect for one setup (DB unreachable, schema gone) must be
  logged and skipped without aborting startup or the other setups.

---

## 7. Reference UI — peegeeq-management-ui

Mirror the create flow in
[DatabaseSetups.tsx](../../peegeeq-management-ui/src/pages/DatabaseSetups.tsx) with the minimum delta:
a **"Connect to Existing"** button + modal reusing the same form fields and `setupRequest` body
builder, posting to `database-setup/connect`, with reworded copy (*"Connects to an existing database
— it will not be created or modified; the PeeGeeQ schema must already exist"*) and a **"remember this
setup"** checkbox that sets the persist flag (§6). List/details/delete unchanged.

---

## 8. Port — peegeeq-utilities-ui

Per the copy-management-ui directive, mirror the reference UI once it exists:
`setupService.connectExisting(req)` → `POST /api/v1/database-setup/connect`, and a **"Connect to
existing setup"** form alongside the current (destructive) Create Setup page (§6.4).

---

## 9. Credential security (decision required)

Auto-reload (§6) means persisting credentials, which is sensitive. The registry must **not store
plaintext passwords in production**. Support (and choose per environment):
- a **secret reference** (`credentialRef` → env var / external secret manager), not the secret itself; or
- **encryption at rest** with a key supplied at backend startup.
Dev/test may allow plaintext behind an explicit opt-in. The exact scheme is a decision to make before
implementing §6; §5 (manual) needs no persisted credentials and can ship first.

---

## 10. Out of scope

- Rotating/managing external secrets themselves (the registry references them; it does not manage them).
- Auto-**discovery** of PeeGeeQ databases the backend was never told about (auto-reload works from the
  registry, not by scanning a PostgreSQL server for PeeGeeQ schemas).

---

## 11. Verification

- Multi-module Java change → mandatory pre-work (`pgq-coding-principles.md` +
  `PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md`), reactive-only, no banned patterns, TestContainers
  integration tests, `mvn clean test -Pall-tests`.
- **Non-destructiveness (the crux):** create a setup, publish rows, then `connect` from a fresh service
  instance and assert the rows **survive** and the reconstituted queues are usable.
- **Reconstitution:** attach to a database with pre-existing queues/event-stores and assert they are
  enumerated (not lost) without being re-supplied.
- **Auto-reload:** persist a binding, restart the backend, assert the setup comes back active with no
  manual step; a bad entry is skipped without aborting startup.
- **Schema-absent:** `connect` to a bare database → clear `400`, no partial registration.
- Confirm each new endpoint field against a running backend before the UI consumes it.
