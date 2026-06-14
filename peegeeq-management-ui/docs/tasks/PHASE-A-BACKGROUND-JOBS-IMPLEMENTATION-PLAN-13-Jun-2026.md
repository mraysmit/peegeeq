# Background Jobs Infrastructure + Aggregate Summary UI Integration

## Status
- **Part 1 (Design & Roadmap)** — designed 12 Jun 2026; covers all phases A–D.
- **Part 2 (Phase A Implementation Plan)** — detailed 13 Jun 2026 from a two-pass cross-layer code investigation; authoritative for Phase A.
- Phase A: **planned, not yet implemented.** Phases B–D: designed only.

## Document structure
This single document merges the original design (Part 1 — the "what & why" across all phases) and the definitive Phase A implementation plan (Part 2 — the "how", grounded in the actual codebase). **Where they differ on Phase A specifics, Part 2 supersedes Part 1**; the divergences are tabulated in "Reconciliation" at the end of Part 1. Related: `AGGREGATE-STREAM-IMPROVEMENTS-PLAN-7-Jun-2026.md` (I4 — the first job consumer), `SCHEMA-PROCESSING-GAPS-CRITICAL-12-Jun-2026.md` (the schema-anchor foundation).

---

## Introduction — the feature and its functional coverage

### What this is

PeeGeeQ background jobs is a **durable, per-setup infrastructure for tracking long-running server-side operations as first-class, queryable records.** Each job is a row in a `peegeeq_jobs` table that lives in a PeeGeeQ setup's own schema. It moves through an explicit lifecycle — `QUEUED → RUNNING → COMPLETED | FAILED | CANCELLED` — and records per-run facts: queued/started/finished timestamps, rows affected, a result document, and any error. Jobs are submitted and inspected over the REST API, and every state change is streamed over the existing `/ws/monitoring` WebSocket channel.

The first operation it covers is the **aggregate-summary REBUILD** (the I4 feature). Rebuilding an event store's materialized summary is a single long transaction that takes an `EXCLUSIVE` lock and scans the full event log; run synchronously over HTTP it makes the caller wait — potentially minutes — and risks request timeouts. As a job, the caller submits it, receives `202 Accepted` with a `jobId` immediately, and observes progress and outcome asynchronously. The infrastructure is deliberately generic so the same model serves the long-running operations already on the roadmap (PostgreSQL `VACUUM` maintenance, scheduled drift verification, jobs-table retention).

### Where it sits in PeeGeeQ

A PeeGeeQ instance is a PostgreSQL schema with the PeeGeeQ objects in it. Background jobs are **per-instance / per-schema**: the `peegeeq_jobs` table is created with the rest of a setup's schema at setup creation — a base DDL template, exactly like `outbox` and `queue_messages` — and every job operation runs on that setup's connection pool (unqualified SQL on the schema's `search_path`). A `jobId` therefore belongs to exactly one setup, and the REST surface is setup-scoped: `/api/v1/setups/:setupId/jobs`.

The feature spans these layers (each grounded in the code in Part 2):

| Layer | Module | Role |
|---|---|---|
| Schema | `peegeeq-db` | `10-jobs-table.sql` base template creates `peegeeq_jobs` + indexes in the setup's schema |
| Domain | `peegeeq-api` / `peegeeq-db` | job value types, a reactive `JobRepository`, a per-setup `BackgroundJobService`, reached via `DatabaseSetupService.getJobService(setupId)` |
| Lifecycle | `peegeeq-db` | a `StuckJobRecoveryManager` + a periodic timer in `PeeGeeQManager` — the same shape as the existing `StuckMessageRecoveryManager` — reclaims orphaned jobs |
| API + events | `peegeeq-rest` | `JobHandler` (list/detail/cancel) + the async branch of the reconcile endpoint; transitions broadcast as `job_event` over `/ws/monitoring` |
| UI (forward) | `peegeeq-management-ui` | the WS frames and REST shape are designed to slot into the existing RTK Query / Zustand-notification patterns (Phase B/C) |

### Functional capabilities

Within the PeeGeeQ system the feature provides:

1. **Asynchronous submission** of a long-running operation → `202 { jobId }`, decoupling the HTTP request from the work's duration.
2. **Durable lifecycle tracking** — each job's state and timestamps persist as a DB row; state survives within a running instance and across a non-destructive reconnect-restart.
3. **Per-run statistics and ETA** — the completed rows *are* the stats; the most recent completed run of a job type yields an "estimated ~Ns based on last run (N rows)" guesstimate (an honest label, never a fabricated progress percentage).
4. **Concurrency control** — at most one active job per `(job_type, resource)`, enforced by a partial unique index → `409` on a duplicate submit; for REBUILD this hard-prevents two concurrent `EXCLUSIVE`-lock rebuilds of the same store.
5. **Querying** — list jobs (filter by type/status, newest first, paginated), fetch a job's detail, and read last-run stats.
6. **Cancellation** of a not-yet-terminal job.
7. **Live events** — every transition is pushed over `/ws/monitoring` as a `job_event` frame, feeding live UI table updates and the notification bell/toast.
8. **Self-healing** — running jobs heartbeat; a periodic in-process sweep fails any job whose heartbeat goes stale (a crashed or hung execution) and frees its concurrency slot, and an indefinite lock wait in REBUILD is bounded by a `lock_timeout`.

### Functional scope (this document)

This document specifies **Phase A — the backend foundation**: the table, the repository and per-setup service, the `AGGREGATE_SUMMARY_REBUILD` job type, the REST endpoints (async submit / list / detail / cancel), WebSocket `job_event` streaming, heartbeat-based recovery, and the REBUILD `lock_timeout`. The complete SQL is in Appendix A; it has been validated by execution against PostgreSQL.

On the roadmap, out of Phase A scope (Part 1 D5/D6):
- **Phase B/C (UI):** the Jobs screen, the notification-history screen, and the Event Stores Verify/Rebuild actions.
- **Phase D (more job types):** `VACUUM_MAINTENANCE`, scheduled `AGGREGATE_SUMMARY_VERIFY`, `JOBS_RETENTION`.

**One known boundary, not jobs-specific:** after a process restart a REST-managed setup is unreachable until reconnected, because the setup service has no non-destructive reconnect entry — a pre-existing PeeGeeQ-level gap (Part 2 §13 risk 1). Job *rows* persist regardless; they become operable again the moment the setup is reconnected, at which point the recovery sweep reclaims anything the dead process left running.

---

# Part 1 — Design & Roadmap

## Overview

End-to-end wiring of the I4 aggregate-summary features (and future long-running operations) into the REST API and Management UI, built on a durable, per-setup background-jobs infrastructure. Supersedes an earlier in-memory job-registry sketch per owner feedback:

> Owner feedback (12 Jun 2026): (1) the UI needs a Jobs screen that really connects to backend jobs; (2) jobs must be durable with per-run stats (time, rows, duration) used to guesstimate future runs — more background jobs are coming (e.g. PostgreSQL VACUUM for high-churn queue-like tables); (3) a notification history screen is needed alongside the existing bell/toast; (4) the jobs table is per-PeeGeeQ-setup, created automatically at setup creation with the rest of the schema.

> **Durability note (refined during Part 2 planning):** jobs ARE durable across a restart. A PeeGeeQ instance is a schema with its objects; `PeeGeeQManager.start()` is non-destructive, so a restart reconnects to the schema and the `peegeeq_jobs` rows persist, and the recovery sweep (in the manager lifecycle) reclaims any job the dead process left `RUNNING`. The one caveat is REST-specific: the setup service's only re-entry today is the destructive `createCompleteSetup` (provisioning), so after a REST process restart a setup is `404` until reconnected — a known PeeGeeQ-level gap (`test04`), independent of jobs, with a small fix (lazy reconnect in the setup service). See Part 2 §5 and §13 risk 1.

## Design

### D1 — Per-setup durable jobs table (created with the setup, in the setup's schema)

A base template (`db/templates/base/10-jobs-table.sql` + `.manifest`) so **every setup gets the table automatically at creation**, in the setup's schema — the same mechanism as `outbox`, `queue_messages`. Runtime access is **unqualified** via the setup pool's `search_path` (schema-anchor foundation).

→ **As-planned schema: Part 2 §3.** The original sketch was refined — `job_id UUID` PK, `dedup_key`, `heartbeat_at` (one combined `10-jobs-table.sql`, table + indexes); the `phase` column was dropped (see Reconciliation).

**Run-history stats & ETA guesstimate** (retained): the completed rows ARE the stats — no separate stats table. The most recent COMPLETED row of the same `(job_type, resource)` yields `lastRun { durationMs, rowsAffected, completedAt }`; the UI shows "estimated ~42s based on last run (12,431 rows)" — a guesstimate label, never a fake percentage. (As-planned: `last_run_at` + `findLatestByType`, Part 2 §4.)

**Retention**: unbounded history bloats — prune by age/count, itself a future job type (D6); not needed for Phase A.

### D2 — Backend job framework (peegeeq-db)

- `JobRepository` — reactive CRUD over `peegeeq_jobs` (unqualified SQL, per-setup pool).
- `BackgroundJobService` (per setup): submit → INSERT QUEUED (the partial unique index rejects a duplicate active job → 409 at REST) → RUNNING → COMPLETED(`rows_affected`)/FAILED(`error_message`). Every transition emits a job event (D4); errors propagate into the row and the event — never swallowed.
- **Non-transactional capability** (`VACUUM` cannot run inside a transaction): the framework should support executors needing a raw connection. → **As-planned: deferred.** Phase A executes its single job type *inline* in the REST handler and does **not** build a generic executor interface; the non-transactional capability lands with the VACUUM job (Phase D). See Reconciliation.
- First job type: `AGGREGATE_SUMMARY_REBUILD` wrapping `EventStore.reconcileAggregateSummary(REBUILD)`; `AGGREGATE_SUMMARY_VERIFY` stays synchronous by default. → As-planned: Part 2 §7.

→ **As-planned service shape, recovery, and lifecycle: Part 2 §4–§5.** Phase A adds heartbeating + a periodic `StuckJobRecoveryManager` + `lock_timeout` (recovery for in-process orphans) that the sketch did not anticipate.

### D3 — REST surface

| Endpoint | Behavior |
|---|---|
| `POST …/aggregate-summary/reconcile?mode=rebuild&async=true` | `202 { jobId, … }`; the synchronous form (no `async`) stays for small stores/scripting |
| `GET /api/v1/setups/:setupId/jobs` | List with filters (`type`, `status`) + pagination; newest first |
| `GET /api/v1/setups/:setupId/jobs/:jobId` | Job detail incl. `lastRun` stats of the same (type, resource) |
| Duplicate active job | `409` (DB-enforced via the partial unique index) |
| Cancellation | Design deferred it. → **As-planned: Phase A INCLUDES cancel** (`POST …/jobs/:jobId/cancel`, `409` if already terminal) — Part 2 §8. Cancelling a lock-holding rebuild is still not offered. |

→ As-planned routes, 202 shape, and error mapping: Part 2 §7–§8.

### D4 — Push events and notifications

- Job lifecycle events broadcast over the existing `/ws/monitoring` channel via `SystemMonitoringHandler` — generic, so backfill completion and all future job types ride the same channel. → **As-planned envelope: Part 2 §9** — `{ type:"job_event", data:{…}, timestamp }`, dictated by the UI's `websocketService.ts` parser; the flat shape originally sketched would not be parsed, and `phase`/`durationMs` are not carried.
- UI: the monitoring hook feeds the existing Zustand notification store → bell badge + toast ("Summary rebuild for 'orders' completed: 12,431 rows, 41s").

### D5 — Management UI screens (Phase B/C; Part 2 §10 states the contract Phase A exposes)

**Jobs screen** (`/jobs`, new sidebar entry): setup scope selector; table (type, resource, status, requested-by, created/started, duration, rows); live WS row updates with periodic-refresh fallback; detail drawer with parameters, error, and the same-(type,resource) run history (the guesstimate data); Re-run action (respects the 409 guard).

**Notification history screen** (`/notifications`, from the bell's "View all"): list with filters (type, read/unread), mark-read/mark-all, clear. v1 persistence: client-side (raise the 50-item cap; persist the store to localStorage). Decision recorded: a server-side notification-log table would make history durable/cross-browser; deferred since jobs are already durable as rows.

**Event Stores page**: create-modal "Aggregate summary" toggle → `aggregateSummaryEnabled` (backend prerequisite B1: the `management/event-stores` listing must return the flag — it does not today); per summary-enabled store, **Verify** (drawer with the reconcile report + "Rebuild now" when drift > 0) and **Rebuild** (submits the async job; toast links to Jobs).

**Aggregate Stream page**: optional "Source: Auto / Event log / Summary" selector exposing the `source=` parameter.

### D6 — Declared future job types (why the table is durable and generic)

| Job type | Purpose |
|---|---|
| `VACUUM_MAINTENANCE` | `VACUUM (ANALYZE)` on high-churn tables (`outbox`, `queue_messages`, `dead_letter_queue`) — needs the non-transactional executor capability |
| `AGGREGATE_SUMMARY_VERIFY` (scheduled) | Periodic drift detection + warning notification (DeadConsumerDetectionJob timer + consecutive-failure escalation idioms) |
| `JOBS_RETENTION` | Prunes old `peegeeq_jobs` rows by age/count |
| (later) backfill as a job | Migrate backfill status reporting onto the same job-events channel |

## Phased roadmap

> Phase A is detailed in **Part 2**; several items were refined or extended during planning (see Reconciliation). Phases B–D are design-level only.

### Phase A — foundation (backend, TDD) — see Part 2
| # | Task |
|---|------|
| A1 | `10-jobs-table.sql` base template + manifest; table created at setup creation in the setup's schema |
| A2 | `JobRepository` + `BackgroundJobService` (lifecycle transitions, stats lookup, duplicate-active rejection) + heartbeat + `StuckJobRecoveryManager` |
| A3 | `AGGREGATE_SUMMARY_REBUILD` job type; `async=true` reconcile → 202 + jobId; per-job heartbeat; `lock_timeout` |
| A4 | Jobs REST endpoints (list/detail/cancel/filters) + 409 path |
| A5 | Job events over `/ws/monitoring` |

### Phase B — UI core
| # | Task |
|---|------|
| B1 | `management/event-stores` listing returns `aggregateSummaryEnabled` (backend prerequisite for the store-page UI) |
| B2 | Jobs screen with live WS updates + detail drawer + re-run |
| B3 | Event Stores: create-modal toggle, flag display, Verify drawer, Rebuild → Jobs link |
| B4 | Bell/toast wiring for job events |

### Phase C — UI completion
| # | Task |
|---|------|
| C1 | Notification history screen (client-persisted v1) |
| C2 | Enable-on-existing-store endpoint (install template → async rebuild) + UI action |
| C3 | Aggregate Stream source selector |

### Phase D — future job types
| # | Task |
|---|------|
| D1 | `VACUUM_MAINTENANCE` job (introduces the non-transactional executor capability) |
| D2 | Scheduled `AGGREGATE_SUMMARY_VERIFY` with drift notifications |
| D3 | `JOBS_RETENTION` pruning job |

## Open decisions

1. Job cancellation — **Phase A now ships cancel for non-terminal jobs** (Part 2 §8); cancelling a lock-holding rebuild remains unsafe and is not offered. Partially resolved.
2. Server-side notification log vs client-persisted history (v1 = client-persisted; Phase C).
3. Whether `requested_by` carries a real principal once auth exists (no auth today — store caller IP/UA or leave null until then).
4. The `phase` column / coarse-progress tracking (D1) was **dropped** for Phase A — re-add only if a future multi-step job type needs it (see Reconciliation).

## Reconciliation — design sketch (Part 1) vs as-planned Phase A (Part 2)

| Topic | Part 1 sketch | Part 2 as-planned | Why |
|---|---|---|---|
| Table PK / columns | `id BIGSERIAL` + `job_id VARCHAR` unique; `resource`; `phase`; `completed_at` | `job_id UUID` PK; `dedup_key`; `heartbeat_at`; `finished_at` + `last_run_at`; no `id`/`resource`/`phase` | refined to the columns the code actually needs (§3/§4) |
| WS event shape | flat `{type,setupId,…,phase,durationMs}` | `{type:"job_event", data:{…}, timestamp}` | the UI parser requires the envelope (§9) |
| Cancellation | out of Phase A | in Phase A (`409` on terminal) | cheap to add (§8) |
| `phase` column / coarse progress | included | **omitted** | single-statement rebuild has no real phases — open item 4 |
| Executor interface / non-transactional | "designed in from the start" | **deferred to Phase D** | YAGNI — one inline job type in Phase A; the abstraction lands with VACUUM |
| Recovery | not addressed | heartbeat + `StuckJobRecoveryManager` + `lock_timeout` | in-process orphan handling (§4–§7, §13) |
| Cross-restart durability | implied by "durable" | **out of scope** (destructive recreate) | §13 risk 1 |

---

# Part 2 — Phase A: Definitive Implementation Plan

**Source:** Two-pass cross-layer investigation (11-agent + 12-agent workflows), 13 Jun 2026.
**Every decision cites the investigation. Corrections to earlier drafts are called out inline.**
**Prerequisite reading:** `SCHEMA-PROCESSING-GAPS-CRITICAL-12-Jun-2026.md`

---

## Confirmed owner decisions

1. `getJobService(setupId)` added to `DatabaseSetupService` interface; one `BackgroundJobService` per setup.
2. `transitionJob` on the public `BackgroundJobService` interface.
3. Broadcasting is a REST-layer concern — `BackgroundJobServiceImpl` does DB only; REST handlers broadcast after transitions.
4. Single combined `10-jobs-table.sql` (table + both indexes in one file) — honored. Verified by runtime probe that a multi-statement template file creates all its indexes (`04a` is the precedent); see §3.
5. Routes: `/api/v1/setups/:setupId/jobs`.
6. **Job recovery is a heartbeat-driven periodic sweep** modeled 1:1 on `StuckMessageRecoveryManager` (new `StuckJobRecoveryManager` + a timer in `PeeGeeQManager`). Running jobs **heartbeat** (`heartbeat_at` bumped by a per-job timer in the executor); the sweep fails only jobs whose heartbeat has gone stale — so a legitimately long job is never falsely failed. The sweep lives in `PeeGeeQManager.startBackgroundTasks()`, which runs on **every** instance start including a restart, so it reclaims both in-process orphans and a dead previous process's orphans (§5). It is NOT in `createCompleteSetup`, which is a one-time provisioning operation, not the restart path.
7. **Heartbeating is in main scope** (added per owner request): a `heartbeat_at` column, a `heartbeat(jobId)` service op, a per-job heartbeat timer in the executor, and a stale-heartbeat sweep predicate. This replaces the earlier "generous 30m timeout" with tight liveness detection and removes the false-positive entirely.

> **Lifecycle fact behind decision 6:** a PeeGeeQ instance is a schema with its objects. `PeeGeeQManager.start()` is **non-destructive** — it validates the existing tables and connects, dropping nothing — so a restart reconnects to the schema and the `peegeeq_jobs` rows survive (verified). The destructive `DROP DATABASE` is only in `createCompleteSetup`'s provisioning path (the drop-if-exists branch at [`DatabaseTemplateManager.java:75-77`](peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/DatabaseTemplateManager.java:75) calls `dropDatabase`, whose literal `DROP DATABASE` SQL is at `:174`), which is run once, not on restart. The recovery sweep therefore sits in the manager lifecycle (runs on every reconnect) and covers orphans left by a dead process. The one limitation is REST-specific: the setup service has no non-destructive reconnect entry, so after a REST restart a setup is `404` until reconnected — a known PeeGeeQ gap (test04), not jobs scope (§5, §13).

---

## Schema model — the foundation this design honors

The jobs feature operates entirely within the schema-anchor foundation established by `SCHEMA-PROCESSING-GAPS-CRITICAL-12-Jun-2026.md` (tasks D2.1–D2.5, G1/G2). The binding rules:

1. **Schema is mandatory and per-setup.** `DatabaseConfig.getSchema()` has no default — not `public`, not anything. A missing/blank schema fails at construction (the `PeeGeeQConfiguration` 7-arg constructor throws; `PgConnectionConfig` requires it). Each setup carries exactly one schema.
2. **Schema selection is connection-level.** `PgConnectionManager.createReactivePool()` sets `search_path` on every managed pool; `04-search-path.sql` is `SET search_path TO {schema};` with no `, public` fallback (G1).
3. **Runtime SQL is unqualified; DDL is `{schema}`-parameterized.** No schema-qualified table names in runtime Java SQL; all DDL lives in validated `{schema}` templates, never built in Java.
4. **No ambient configuration.** No schema — or any config — from `System.getProperty`/`System.getenv`; configuration has a single source (`PeeGeeQConfiguration`/`DatabaseConfig`).
5. **Identifier validation.** `PostgreSqlIdentifierValidator` (`^[a-zA-Z_][a-zA-Z0-9_]*$`) validates every schema/table name.

**Setup and schema are the same boundary for jobs.** `setupId` is the in-memory handle; the schema is the database namespace holding the setup's tables. The two are 1:1 (one mandatory schema per setup), and since `createCompleteSetup` operates at the database level, 1:1 with the database in practice. The jobs feature is therefore per-schema throughout: `peegeeq_jobs` is created in the setup's schema and every job operation runs within it.

**Compliance of each part:**
| Rule | How the jobs feature complies |
|---|---|
| Mandatory per-setup schema | `peegeeq_jobs` created by `applySchemaTemplates` in the setup's schema; `getJobService(setupId)` returns the service bound to that setup's schema-configured pool (§5) |
| Connection-level `search_path` | `PgJobRepository`, `StuckJobRecoveryManager`, and the heartbeat run unqualified SQL on `manager.getPool()` (`search_path` = schema) — no schema param, no qualification (§3/§4) |
| `{schema}` DDL | `10-jobs-table.sql` uses `{schema}.peegeeq_jobs`; runtime SQL never qualifies (§3) |
| No ambient config | the new `peegeeq.jobs.recovery.*` / `peegeeq.jobs.heartbeat-interval` keys are read through `PeeGeeQConfiguration` (profile properties), the same path as `peegeeq.queue.recovery.*` — never `System.getProperty`/`getenv` (§5). Defaults are permitted for intervals/timeouts; the no-default rule applies to **schema only** |
| Identifier validation | the repo interpolates no identifiers (nothing built in Java), so there is no injection surface; the `{schema}` in DDL templates is validated by the existing template path |

**Tests** use `PostgreSQLTestConstants.TEST_SCHEMA = "peegeeq_test"` (never `public`) and thread the schema through all three places per `BiTemporalAggregateSummaryIntegrationTest` (§11). No job test reads a schema system property.

---

## 1. Architecture trace — a job submission, front to back

```
┌─ UI (Phase B — forward context only) ──────────────────────────────────────┐
│ React → jobsApi (RTK Query, dynamicBaseQuery → /api/v1)                     │
│   POST /api/v1/setups/{setupId}/jobs  (or async reconcile endpoint)        │
│ websocketService.ts → createSystemMonitoringService() → ws://…/ws/monitoring│
└────────────────────────────────────────────────────────────────────────────┘
                  │ HTTP                              ▲ WS frame
                  ▼                                   │
┌─ peegeeq-rest ─────────────────────────────────────────────────────────────┐
│ PeeGeeQRestServer.createRouter()  — routes + handler wiring                 │
│   EventStoreHandler.reconcileAggregateSummary(ctx)   [A3 async path]        │
│   JobHandler.list/detail/cancel(ctx)                 [A4]                   │
│     ↳ setupService.getJobService(setupId) → BackgroundJobService            │
│     ↳ setupService.getSetupResult(setupId).getEventStores().get(name)       │
│   SystemMonitoringHandler.broadcastJobEvent(...)     [A5] — REST broadcasts │
└────────────────────────────────────────────────────────────────────────────┘
                  │ getJobService(setupId)
                  ▼
┌─ peegeeq-api (interfaces only) ────────────────────────────────────────────┐
│ DatabaseSetupService.getJobService(String) : BackgroundJobService          │
│ BackgroundJobService.submit / transitionJob / get / list / latestByType    │
│ EventStore.reconcileAggregateSummary(ReconcileMode)                         │
│     : Future<EventStore.AggregateSummaryReconcileResult>                    │
└────────────────────────────────────────────────────────────────────────────┘
                  │ resolves through facades
                  ▼
┌─ peegeeq-runtime ──────────────────────────────────────────────────────────┐
│ RuntimeDatabaseSetupService.getJobService(id) → delegate.getJobService(id)  │
└────────────────────────────────────────────────────────────────────────────┘
                  ▼
┌─ peegeeq-db (real impl) ───────────────────────────────────────────────────┐
│ PeeGeeQDatabaseSetupService                                                 │
│   jobServices: Map<String, BackgroundJobService>   (NEW field)             │
│   getJobService(id) → jobServices.get(id)                                   │
│   BackgroundJobServiceImpl  (DB-only; NO broadcasting)                      │
│     ↳ PgJobRepository(Pool)  — unqualified SQL, relies on search_path        │
│         manager.getPool()  — shared default pool, search_path = schema      │
│         (PgConnectionManager sets search_path at connection level)          │
│ PeeGeeQManager (per setup)                                                   │
│   StuckJobRecoveryManager  + vertx.setPeriodic timer  [decision 6/7]        │
│     ↳ periodic UPDATE: jobs with stale heartbeat_at → FAILED (in-proc orphan)│
│   (running jobs bump heartbeat_at via a per-job timer in EventStoreHandler)  │
└────────────────────────────────────────────────────────────────────────────┘
                  ▼
┌─ PostgreSQL ───────────────────────────────────────────────────────────────┐
│ {schema}.peegeeq_jobs  (created at setup via 10-jobs-table.sql template)    │
│ {schema}.bitemporal_event_log_aggregate_summary (REBUILD target for A3)     │
└────────────────────────────────────────────────────────────────────────────┘
```

**Return path (job event):** `BackgroundJobServiceImpl.transitionJob` writes the row → REST handler (the caller) invokes `SystemMonitoringHandler.broadcastJobEvent` → WS frame `{type:"job_event", data:{…}, timestamp}` → `websocketService.ts onmessage` → `JSON.parse` → consumer. The DB layer never touches the WS (owner decision 3).

---

## 2. Cross-layer impact table

| Module | File | Create / Modify | Why |
|---|---|---|---|
| **peegeeq-api** | `…/api/jobs/BackgroundJobService.java` | Create | Public per-setup service interface (`transitionJob` here) |
| peegeeq-api | `…/api/jobs/JobRepository.java` | Create | Repo contract |
| peegeeq-api | `…/api/jobs/JobRecord.java` | Create | Value type returned to REST |
| peegeeq-api | `…/api/jobs/JobSubmitRequest.java` | Create | Submission value type |
| peegeeq-api | `…/api/jobs/JobStatus.java` | Create | Enum `QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED` |
| peegeeq-api | `…/api/jobs/JobTypes.java` | Create | `AGGREGATE_SUMMARY_REBUILD` constant |
| peegeeq-api | `…/api/jobs/DuplicateActiveJobException.java` | Create | Duplicate-active rejection (A2) |
| peegeeq-api | `…/api/jobs/JobNotFoundException.java` | Create | Detail 404 |
| peegeeq-api | `…/api/setup/DatabaseSetupService.java` | Modify | Add abstract `getJobService(String)` |
| **peegeeq-db** | `…/db/jobs/PgJobRepository.java` | Create | Real repo, `(Pool, schema)` ctor |
| peegeeq-db | `…/db/jobs/BackgroundJobServiceImpl.java` | Create | DB-only service impl |
| peegeeq-db | `…/db/recovery/StuckJobRecoveryManager.java` | Create | Periodic in-process stuck-job sweep (mirrors `StuckMessageRecoveryManager`) — decision 6 |
| peegeeq-db | `…/db/PeeGeeQManager.java` | Modify | Construct + timer + teardown for `StuckJobRecoveryManager` (beside `StuckMessageRecoveryManager`) |
| peegeeq-db | `…/db/config/PeeGeeQConfiguration.java` | Modify | `QueueConfig`: 3 `jobs.recovery.*` fields + ctor params + accessors + parse block |
| peegeeq-db | `…/db/setup/PeeGeeQDatabaseSetupService.java` | Modify | `jobServices` field, populate in `createCompleteSetup`, `getJobService`, `destroySetup` removal |
| peegeeq-db | `…/resources/db/templates/base/10-jobs-table.sql` | Create | DDL template (table + both indexes, one file — like `04a`) |
| peegeeq-db | `…/resources/db/templates/base/.manifest` | Modify | Append `10-jobs-table.sql` |
| **peegeeq-bitemporal** | `…/bitemporal/PgBiTemporalEventStore.java` | Modify | `SET LOCAL lock_timeout` on the REBUILD transaction (hung-on-lock guard — §7) |
| **peegeeq-runtime** | `…/runtime/RuntimeDatabaseSetupService.java` | Modify | Delegating override — **PREVIOUS PLAN MISSED** |
| **peegeeq-rest** | `…/rest/handlers/JobHandler.java` | Create | A4 list/detail/cancel |
| peegeeq-rest | `…/rest/handlers/EventStoreHandler.java` | Modify | A3 async reconcile branch + per-job heartbeat timer |
| peegeeq-rest | `…/rest/handlers/SystemMonitoringHandler.java` | Modify | A5 `broadcastJobEvent` |
| peegeeq-rest | `…/rest/PeeGeeQRestServer.java` | Modify | Register `JobHandler` + routes |
| peegeeq-rest | `…/rest/setup/RestDatabaseSetupService.java` | Modify | Deprecated delegating override — **PREVIOUS PLAN MISSED** |
| **peegeeq-service-manager** | — | No change | HTTP-federation only; holds no `DatabaseSetupService`. Federated job visibility is a later optional phase. |

**Test doubles that MUST gain a stub** (the interface method is abstract — no default, so each fails to compile otherwise):

| Module | File | Change |
|---|---|---|
| peegeeq-rest (test) | `support/ControllableSetupService.java` | field + with-method + override |
| peegeeq-rest (test) | `BasicUnitTest.java` (`StubDatabaseSetupService`) | stub returning null |
| peegeeq-rest (test) | `handlers/DeadConsumerAlertingIntegrationTest.java` | stub returning null |
| peegeeq-rest (test) | `handlers/PartitionedConsumptionRestIntegrationTest.java` | stub returning null |
| peegeeq-rest (test) | `PeeGeeQRestServerStopTest.java` (anonymous) | stub returning null |
| peegeeq-runtime (test) | `RuntimeDatabaseSetupServiceTest.java` (anonymous `spyDelegate`) | stub returning null |
| peegeeq-api (test) | `setup/DatabaseSetupServiceTest.java` (TWO anonymous classes) | stub in both |
| peegeeq-db (test) | `setup/TestPeeGeeQDatabaseSetupService.java` | **No change** — subclass inherits |

---

## 3. A1 — SQL template + manifest

**Runtime schema-qualification — unqualified, mandated by house style.** `SCHEMA-PROCESSING-GAPS-CRITICAL-12-Jun-2026.md`: schema selection is connection-level (`search_path` set by `PgConnectionManager.createReactivePool()`), runtime SQL is unqualified, DDL is `{schema}`-parameterized. Confirmed by `PgBiTemporalEventStore` (`quotedTableName` has no schema prefix) and `StuckMessageRecoveryManager` (queries bare `outbox`). **Correction to earlier drafts:** `PgJobRepository` and `StuckJobRecoveryManager` therefore use **unqualified** runtime SQL against `peegeeq_jobs` on the manager's schema-bound pool — no `schema + "."` prefix, no schema constructor param. The earlier "belt-and-suspenders qualified" approach was a deviation from house style guarding a scenario that does not occur (all components share the manager's single schema-bound pool) and is dropped.

**Owner decision 4 (single combined file) — honored; verified by runtime probe.** A single file with the `CREATE TABLE` followed by its `CREATE INDEX` statements **works**: `SqlTemplateProcessor` applies each manifest file with `connection.query(processedSql).execute()` (`SqlTemplateProcessor.java:59`), and PostgreSQL's simple query protocol (which `query()` uses) executes **all** semicolon-separated statements. The processor's JavaDoc (`:22-24`) claims only the first statement runs — that is **stale/wrong**, proven by an integration probe: applying the real `base` template into a fresh schema then querying `pg_indexes` returns both outbox idempotency indexes from `04a-core-table-outbox.sql` (`idx_outbox_idempotency_key`, `idx_outbox_idempotency_key_lookup`), which are the 2nd and 3rd statements of that one file. `04a` is the precedent: table + two inline indexes in one file, and they are all created. So `peegeeq_jobs` ships as **one** `10-jobs-table.sql` (table + both indexes), matching `04a` and owner decision 4. (Earlier drafts split into `10`/`10a`/`10b` on the strength of the stale JavaDoc; that split is unnecessary and is dropped.)

**`10-jobs-table.sql`** (create) — table + both indexes in one file, mirroring `04a-core-table-outbox.sql`:
The exact DDL is in **[Appendix A.1](#appendix-a--complete-sql-every-statement-precise)** (table + both indexes, one file). It adds a `status` CHECK constraint and a `dedup_key IS NOT NULL` predicate on the unique index — both mirroring `04a-core-table-outbox.sql`.

**Manifest edit** — append one line after `09e-consumer-index-topic.sql`:
```
10-jobs-table.sql
```

**Required-tables validation: no code change.** `resolveRequiredTables("base")` scans manifest files with `CREATE_TABLE_PATTERN`; its `(?:\{schema\}\.|\w+\.)?` group consumes the `{schema}.` prefix and `group(1)` captures `peegeeq_jobs`, compared against unqualified `information_schema.tables.table_name`. Auto-picked-up.

---

## 4. A2 — API types + DB impl

### New API files (`peegeeq-api`, package `dev.mars.peegeeq.api.jobs`)

```java
public enum JobStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }   // active = QUEUED|RUNNING
```
```java
public final class JobTypes {
    public static final String AGGREGATE_SUMMARY_REBUILD = "AGGREGATE_SUMMARY_REBUILD";
    private JobTypes() {}
}
```

**`JobRecord`** — immutable; **camelCase fields, ISO-string timestamps** so the WS `data` slots into the TS type with no `transformResponse` (UI finding):
```java
public final class JobRecord {
    String jobId;          // UUID string
    String jobType;
    JobStatus status;
    String params;         // JSON string
    String result;         // JSON string, nullable
    Long rowsAffected;     // nullable
    String errorMessage;   // nullable
    String dedupKey;       // nullable
    Instant createdAt;
    Instant startedAt;     // nullable
    Instant heartbeatAt;   // nullable — last liveness bump while RUNNING
    Instant finishedAt;    // nullable
    Instant lastRunAt;     // nullable
    // all-args ctor + getters
}
```
```java
public final class JobSubmitRequest {
    String jobType;
    String params;     // JSON string
    String dedupKey;   // nullable; duplicate-active key
    // ctor + getters
}
public class DuplicateActiveJobException extends RuntimeException { /* → REST 409 */ }
public class JobNotFoundException extends RuntimeException { /* → REST 404 */ }
```

**`JobRepository`** — DB-only contract, all `Future`-returning (Vert.x house style):
```java
public interface JobRepository {
    Future<JobRecord> insert(JobSubmitRequest request);                       // uq violation → failed Future(DuplicateActiveJobException)
    Future<JobRecord> updateStatus(String jobId, JobStatus to, Long rowsAffected,
                                   String result, String errorMessage);       // transition + timestamps
    Future<JobRecord> findById(String jobId);                                 // null result → caller maps to JobNotFoundException
    Future<List<JobRecord>> list(String jobType, JobStatus status, int limit, int offset);
    Future<JobRecord> findLatestByType(String jobType);                       // A3 "lastRun on second submit"
    Future<Void>      heartbeat(String jobId);                                // UPDATE heartbeat_at=now() WHERE job_id=$1 AND status='RUNNING'
}
```

**`BackgroundJobService`** — public per-setup interface (`transitionJob` here, owner decision 2):
```java
public interface BackgroundJobService {
    Future<JobRecord> submit(JobSubmitRequest request);
    Future<JobRecord> transitionJob(String jobId, JobStatus to, Long rowsAffected,
                                    String result, String errorMessage);
    Future<Void>      heartbeat(String jobId);                               // liveness bump while RUNNING (decision 6/7)
    Future<JobRecord> get(String jobId);
    Future<List<JobRecord>> list(String jobType, JobStatus status, int limit, int offset);
    Future<JobRecord> latestByType(String jobType);
}
```
> The recovery sweep is NOT on this interface. It is a separate `StuckJobRecoveryManager` driven by a `PeeGeeQManager` timer (decision 6) — exactly as message recovery is `StuckMessageRecoveryManager`, not a method on any queue-producer interface.

### `PgJobRepository` (`peegeeq-db`, package `dev.mars.peegeeq.db.jobs`)

Constructed with **`(Pool pool)`** — unqualified runtime SQL, mirroring `StuckMessageRecoveryManager` (`outbox`) and `PgBiTemporalEventStore`:
```java
public class PgJobRepository implements JobRepository {
    private final Pool pool;
    public PgJobRepository(Pool pool) {
        this.pool = Objects.requireNonNull(pool, "pool cannot be null");
    }
    // all SQL targets bare `peegeeq_jobs`; schema is the pool's search_path
}
```
- The `pool` is **`manager.getPool()`** — the single shared default pool, already `search_path`-configured for the setup's schema by `PgConnectionManager`. No schema param, no qualification, no `PostgreSqlIdentifierValidator` (nothing is interpolated). The repo does **not** build or close the pool (the manager owns it).
- `insert` maps a PG unique-violation (SQLSTATE `23505` on `uq_peegeeq_jobs_active`) to a failed `Future` carrying `DuplicateActiveJobException` — the constraint surfaces, never swallowed.
- `updateStatus` sets timestamps per transition: → `RUNNING` sets `started_at = now()` **and `heartbeat_at = now()`** (so a job is "alive" the instant it starts); → terminal (`COMPLETED`/`FAILED`/`CANCELLED`) sets `finished_at = now()` and `last_run_at = now()`; persists `rows_affected`, `result`, `error_message`.
- `heartbeat(jobId)` → `UPDATE peegeeq_jobs SET heartbeat_at = now() WHERE job_id = $1 AND status = 'RUNNING'` (the `status='RUNNING'` guard makes a heartbeat against an already-terminal job a harmless no-op — avoids resurrecting a cancelled/failed job).

### `BackgroundJobServiceImpl` (`peegeeq-db`, package `dev.mars.peegeeq.db.jobs`) — DB-ONLY
```java
public class BackgroundJobServiceImpl implements BackgroundJobService {
    private final JobRepository repository;
    public BackgroundJobServiceImpl(JobRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
    }
    @Override public Future<JobRecord> submit(JobSubmitRequest r) { return repository.insert(r); }
    @Override public Future<JobRecord> transitionJob(String id, JobStatus to, Long rows, String res, String err) {
        return repository.updateStatus(id, to, rows, res, err);
    }
    @Override public Future<Void> heartbeat(String id) { return repository.heartbeat(id); }
    @Override public Future<JobRecord> get(String id) {
        return repository.findById(id).compose(rec -> rec == null
            ? Future.failedFuture(new JobNotFoundException("Job not found: " + id))
            : Future.succeededFuture(rec));
    }
    @Override public Future<List<JobRecord>> list(String type, JobStatus status, int limit, int offset) {
        return repository.list(type, status, limit, offset);
    }
    @Override public Future<JobRecord> latestByType(String type) { return repository.findLatestByType(type); }
}
```
No `Vertx`, no broadcasting, no WS, no recovery.

### `StuckJobRecoveryManager` (`peegeeq-db`, package `dev.mars.peegeeq.db.recovery`) — mirrors `StuckMessageRecoveryManager` 1:1

Stateless recovery service. **No internal timer** (the timer lives in `PeeGeeQManager`, exactly as for `StuckMessageRecoveryManager`). Constructor and shape copied from `StuckMessageRecoveryManager`:
```java
public class StuckJobRecoveryManager {
    private final Pool reactivePool;
    private final Duration staleTimeout;   // no heartbeat / no progress for this long ⇒ "stuck"
    private final boolean enabled;
    private volatile boolean closing = false;

    public StuckJobRecoveryManager(Pool reactivePool, Duration staleTimeout, boolean enabled) { ... }

    public Future<Integer> recoverStuckJobs() {            // called by the PeeGeeQManager timer each tick
        if (closing || !enabled) return Future.succeededFuture(0);
        OffsetDateTime cutoff = Instant.now().minus(staleTimeout).atOffset(ZoneOffset.UTC);
        // Runs the heartbeat-stale sweep UPDATE (exact SQL: Appendix A.8) bound with `cutoff`,
        // returning the affected row count. Unqualified SQL; search_path = setup schema.
    }

    public void markClosing() { this.closing = true; }    // called by PeeGeeQManager.closeReactive()
}
```
- **`COALESCE(heartbeat_at, started_at, created_at) < cutoff` is the heartbeat-driven predicate.** A healthy `RUNNING` job bumps `heartbeat_at` every `heartbeat-interval` (≪ `staleTimeout`), so its `heartbeat_at` is always fresh and it is **never** swept — no matter how long it legitimately runs (this removes the §13 false-positive). A `RUNNING` job whose owner died/hung stops heartbeating → `heartbeat_at` ages past `cutoff` → swept. A `QUEUED` job (no heartbeat, `started_at` null) falls back to `created_at`; in the inline model `QUEUED` is transient, so a `QUEUED` row older than `staleTimeout` is a stuck submission → swept. Both cases also free the `uq_peegeeq_jobs_active` dedup index.
- Because detection is now liveness-based, `staleTimeout` is **tight** (default `2m`), not the old generous `30m`. The invariant `staleTimeout > heartbeat-interval` (by several ×) must hold, else a healthy job could be swept between beats — enforced by config defaults (`2m` vs `30s`).
- Errors in the tick propagate up the `Future` to the `PeeGeeQManager` timer callback (consecutive-failure escalation there — §5), never swallowed; `markClosing()` fail-fasts in-flight ticks during shutdown.

### Reconcile rows-affected field — CONFIRMED

The A3 job records `rows_affected` from `EventStore.AggregateSummaryReconcileResult.getRepaired()` — a `long`, confirmed live at `EventStoreHandler.java:470`. The result type is the **nested interface** `EventStore.AggregateSummaryReconcileResult` (not a top-level class). Its getters: `getAggregatesChecked / getMissingInSummary / getStaleInSummary / getOrphanedInSummary / getRepaired / getSampleMismatches / getMode`. There is no `getRowsAffected()` — do not invent one.

---

## 5. A2 wiring — `getJobService` end to end

**Field** (`PeeGeeQDatabaseSetupService.java`, alongside the other per-setup maps):
```java
private final Map<String, BackgroundJobService> jobServices = new ConcurrentHashMap<>();
```

**Population — inside `createCompleteSetup`, in the existing `.map(arr -> {...})` block (lines 191–228), after `PeeGeeQManager manager = (PeeGeeQManager) arr[1];` (line 196) and alongside `activeSetups.put(...)` (line 218).** `manager` is in scope; by this point `applySchemaTemplates` (step 2, line 172) has created `peegeeq_jobs` and `manager.getPool()` is live:
```java
PgJobRepository jobRepo = new PgJobRepository(manager.getPool());
jobServices.put(req.getSetupId(), new BackgroundJobServiceImpl(jobRepo));
```
> Correction: there is **no standalone `setupPool` local** in `createCompleteSetup`. The pool is `manager.getPool()` (the schema-bound shared pool). Eager construction (not lazy) is chosen for lifecycle symmetry with the other per-setup resources; the repo is a single Pool ref, so eager costs nothing. **No `.compose` recovery step is appended here** — recovery lives in `PeeGeeQManager` (the per-instance lifecycle that runs on every restart), not in this one-time provisioning path; see "Recovery" below.

**`getJobService` body** (near the other per-setup getters, mirroring `getSubscriptionServiceForSetup`):
```java
@Override
public BackgroundJobService getJobService(String setupId) {
    BackgroundJobService service = jobServices.get(setupId);
    if (service == null) {
        logger.debug("Job service not found for setupId: {}", setupId);
    }
    return service;
}
```

**`destroySetup` cleanup** — add to the existing removals:
```java
jobServices.remove(setupId);
```
Do **not** close `manager.getPool()` from the job service — it is shared and closed via `manager.closeReactive()`.

### Recovery — `StuckJobRecoveryManager` + a `PeeGeeQManager` timer (decision 6)

**Where the sweep lives, and why it is not in `createCompleteSetup`.** The sweep belongs in `PeeGeeQManager.startBackgroundTasks()`, beside `StuckMessageRecoveryManager` — because that is the per-instance lifecycle that runs on **every start**, including a restart. It is NOT in `createCompleteSetup`, because `createCompleteSetup` is a one-time **provisioning** operation that `DROP`s and recreates the database (drop-if-exists branch [`DatabaseTemplateManager.java:75-77`](peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/DatabaseTemplateManager.java:75) → `dropDatabase`, `DROP DATABASE` SQL at `:174`); provisioning is not restart, and running recovery there would only ever see a freshly empty table.

**Restart is a non-destructive reconnect, and the sweep covers it.** A PeeGeeQ instance is a schema with its objects; `PeeGeeQManager.start()` is non-destructive — it runs `validateRequiredTables()` against the existing schema and connects, dropping nothing (verified). So a restarted manager on an existing schema reaches the surviving `peegeeq_jobs` rows, and its recovery timer reclaims **two** orphan classes with one mechanism:
- **In-process orphan** — the process stayed up but a job's inline `executeReconcile` chain died/hung, leaving a `RUNNING` row no executor will terminate.
- **Cross-restart orphan** — the process that owned a `RUNNING` job died; the new process's manager, reconnected to the same schema, sees the stale heartbeat and fails the row.

Both are stale-heartbeat rows; the same predicate (§4) handles them. This is exactly how `StuckMessageRecoveryManager` reclaims stuck `PROCESSING` outbox rows across a restart.

**REST-deployment caveat (a pre-existing PeeGeeQ gap, not jobs scope).** The reconnect path exists at the manager level (library/embedded usage constructs a manager on the existing schema and starts it). The **REST setup service has no non-destructive reconnect entry**: `activeManagers` is populated only by `createCompleteSetup` (destructive) and `registerSetupForTesting` (test-only), so after a REST process restart `getJobService(setupId)` returns `null` — and the recovery timer for that setup is not running — until the setup is reconnected. This is the known limitation documented by `SubscriptionPersistenceAcrossRestartIntegrationTest.test04_DemonstrateSetupCacheLimitationAfterRestart` (its own note: the fix is "setup cache persistence or lazy loading"), and it affects **all** per-setup REST access — subscriptions, queues, event stores — not just jobs. Closing it (a lazy reconnect in the setup service: if a requested `setupId`'s schema exists but is not in `activeManagers`, construct a manager on it without dropping) makes cross-restart job recovery automatic for REST too. Recommended as a PeeGeeQ-level follow-up; see §13.

**Wiring in `PeeGeeQManager`** (mirrors the `StuckMessageRecoveryManager` wiring verbatim):
- **Field + construct** (in the constructor, beside `stuckMessageRecoveryManager`):
  ```java
  private final StuckJobRecoveryManager stuckJobRecoveryManager;
  private long jobRecoveryTimerId = 0;
  // ...
  this.stuckJobRecoveryManager = new StuckJobRecoveryManager(
      pool,
      configuration.getQueueConfig().getJobStaleTimeout(),
      configuration.getQueueConfig().isJobRecoveryEnabled());
  ```
- **Timer** (in `startBackgroundTasks()`, beside the stuck-message timer; mirrors its error handling incl. consecutive-failure escalation via a `stuckJobFailures` atomic):
  ```java
  if (stuckJobRecoveryManager != null && configuration.getQueueConfig().isJobRecoveryEnabled()) {
      long intervalMs = configuration.getQueueConfig().getJobRecoveryCheckInterval().toMillis();
      jobRecoveryTimerId = vertx.setPeriodic(intervalMs, id -> {
          if (closing) return;
          stuckJobRecoveryManager.recoverStuckJobs()
              .onSuccess(n -> { stuckJobFailures.set(0);
                  if (n > 0) logger.info("Recovered {} stuck job(s) → FAILED", n); })
              .onFailure(e -> {
                  long f = stuckJobFailures.incrementAndGet();
                  if (f >= TIMER_FAILURE_ESCALATION_THRESHOLD)
                      logger.error("Stuck-job recovery failed ({} consecutive): {}", f, e.getMessage(), e);
                  else logger.warn("Stuck-job recovery failed: {}", e.getMessage(), e);
              });
      });
  }
  ```
  Like the stuck-message timer, the first tick fires after `intervalMs` (not immediately), so the table is guaranteed to exist by then.
- **Teardown:** `stuckJobRecoveryManager.markClosing()` in `closeReactive()`; `vertx.cancelTimer(jobRecoveryTimerId)` + zero it in `stopBackgroundTasks()` — both beside the stuck-message equivalents.

**Config** (new keys in `PeeGeeQConfiguration.QueueConfig`, mirroring `recovery.*`; instance-scoped profile config, no system properties):
| Key | Default | Notes |
|---|---|---|
| `peegeeq.jobs.recovery.enabled` | `true` | parallels `peegeeq.queue.recovery.enabled` |
| `peegeeq.jobs.recovery.check-interval` | `1m` | sweep timer period |
| `peegeeq.jobs.recovery.stale-timeout` | `2m` | no heartbeat for this long ⇒ stuck; **tight**, enabled by heartbeating |
| `peegeeq.jobs.heartbeat-interval` | `30s` | running-job heartbeat cadence; **must be ≪ stale-timeout** |

Touches: `QueueConfig` (4 fields + constructor params + 4 accessors `isJobRecoveryEnabled` / `getJobRecoveryCheckInterval` / `getJobStaleTimeout` / `getJobHeartbeatInterval`), the config-parse block (read the 4 keys; validate `stale-timeout > heartbeat-interval`, mirroring the existing `recovery.processing-timeout` ≥ 1m validation), and every `new QueueConfig(...)` call site (a compile gate — find them all). `StuckJobRecoveryManager` is per-setup (one per `PeeGeeQManager`), sweeping only its own schema's `peegeeq_jobs` via the schema-bound pool — no cross-setup reach. `getJobHeartbeatInterval()` is read by the **executor** (§7), not the recovery manager.

---

## 6. A2 facade change — complete checklist

Return type `dev.mars.peegeeq.api.jobs.BackgroundJobService`. The method is **synchronous, abstract, no default** (mirrors the `ServiceProvider` getters; returns `null` when the setup is unknown). Because it is abstract, **every** `implements DatabaseSetupService` must gain an override.

1. **`peegeeq-api/…/setup/DatabaseSetupService.java`** — add import + `BackgroundJobService getJobService(String setupId);` (next to `getAllActiveSetupIds()`, before the `close()` default).
2. **`peegeeq-runtime/…/RuntimeDatabaseSetupService.java`** (PREVIOUS PLAN MISSED) — import + delegate:
   ```java
   @Override public BackgroundJobService getJobService(String setupId) { return delegate.getJobService(setupId); }
   ```
3. **`peegeeq-db/…/PeeGeeQDatabaseSetupService.java`** — real override + field + population (§5).
4. **`peegeeq-rest/…/setup/RestDatabaseSetupService.java`** (PREVIOUS PLAN MISSED) — deprecated delegate:
   ```java
   @Override @Deprecated(since = "1.0", forRemoval = true)
   public BackgroundJobService getJobService(String setupId) { return delegate.getJobService(setupId); }
   ```
5. **`TestPeeGeeQDatabaseSetupService.java`** — no change (subclass inherits).
6. **Test doubles** (each fails to compile without a stub):
   - `ControllableSetupService.java` — `Function<String,BackgroundJobService> jobServiceForSetup` field; `id -> null` in `defaults()`/`alwaysFailing()`; `withJobServiceForSetup(...)`; override returns `jobServiceForSetup.apply(setupId)`.
   - `BasicUnitTest.java` (`StubDatabaseSetupService`) — return null.
   - `DeadConsumerAlertingIntegrationTest.java` (`TestDatabaseSetupService`) — return null.
   - `PartitionedConsumptionRestIntegrationTest.java` (`TestDatabaseSetupService`) — return null.
   - `PeeGeeQRestServerStopTest.java` (anonymous) — return null.
   - `RuntimeDatabaseSetupServiceTest.java` (anonymous `spyDelegate`) — return null.
   - `DatabaseSetupServiceTest.java` — **two** anonymous classes, stub in both.

   Anonymous doubles use FQN in the return type (no import); named classes add the import.

---

## 7. A3 — async reconcile

### Blocking decision — reactive-non-blocking, NOT `executeBlocking`

`PgBiTemporalEventStore.verify/rebuildAggregateSummary` runs entirely inside `pool.withTransaction(...)` — fully reactive Vert.x SQL, no JDBC/CPU blocking. It must **not** be wrapped in `executeBlocking` (that would move reactive futures onto a worker thread for nothing). The long-running concern is a *database duration* concern (REBUILD holds `LOCK TABLE …_aggregate_summary IN EXCLUSIVE MODE` and scans the event log), not an event-loop concern. The async-job wrapper exists so the HTTP client does not wait on that DB duration — it returns 202 and the reconcile future completes later.

### `EventStoreHandler.reconcileAggregateSummary` (line 434) — add an `async` branch

When `async=true`: submit a job, return `202 + jobId`, run the reconcile in the background transitioning + broadcasting at each step. When absent/false: the existing synchronous 200 path (lines 452–490, extracted to `reconcileSync`) is unchanged.

```java
public void reconcileAggregateSummary(RoutingContext ctx) {
    String setupId = ctx.pathParam("setupId");
    String eventStoreName = ctx.pathParam("eventStoreName");
    String modeParam = ctx.request().getParam("mode");
    boolean async = "true".equalsIgnoreCase(ctx.request().getParam("async"));

    EventStore.ReconcileMode mode;
    if ("verify".equalsIgnoreCase(modeParam != null ? modeParam.trim() : "")) {
        mode = EventStore.ReconcileMode.VERIFY;
    } else if ("rebuild".equalsIgnoreCase(modeParam != null ? modeParam.trim() : "")) {
        mode = EventStore.ReconcileMode.REBUILD;
    } else {
        sendError(ctx, 400, "Invalid or missing mode. Valid values: verify, rebuild");
        return;
    }

    if (!async) { reconcileSync(ctx, setupId, eventStoreName, mode); return; }

    BackgroundJobService jobService = setupService.getJobService(setupId);
    if (jobService == null) { sendError(ctx, 404, "Setup not found or not active: " + setupId); return; }

    JobSubmitRequest req = new JobSubmitRequest(
        JobTypes.AGGREGATE_SUMMARY_REBUILD,
        new JsonObject().put("eventStoreName", eventStoreName).put("mode", mode.name()).encode(),
        eventStoreName + ":" + mode.name());   // dedupKey → one active job per (store, mode)

    jobService.submit(req)
        .onSuccess(job -> {
            broadcastJobEvent(setupId, job);                                   // QUEUED frame
            sendResponse(ctx, 202, new JsonObject()
                .put("jobId", job.getJobId()).put("status", job.getStatus().name()));
            executeReconcile(setupId, eventStoreName, mode, job.getJobId(), jobService);
        })
        .onFailure(t -> {
            if (t instanceof DuplicateActiveJobException) sendError(ctx, 409, t.getMessage());
            else { logger.error("Failed to submit reconcile job: {}", t.getMessage(), t);
                   sendError(ctx, 503, "Failed to submit reconcile job: " + t.getMessage()); }
        });
}
```

### `executeReconcile` helper (runs after the 202 is sent) — with heartbeat timer
The executor starts a per-job heartbeat timer when the job goes `RUNNING` and cancels it in `.eventually` (fires on success *and* failure), so the heartbeat stops the instant the job reaches a terminal state. `EventStoreHandler` already holds `vertx` (confirmed constructor). The interval is `config.getJobHeartbeatInterval()` — but the handler does not hold a `PeeGeeQConfiguration`; pass the interval ms in via the existing setup wiring (simplest: a constant default in the handler overridable from `RestServerConfig`, or read once from the setup's manager). The timer id is captured per invocation:
```java
private void executeReconcile(String setupId, String eventStoreName,
                              EventStore.ReconcileMode mode, String jobId,
                              BackgroundJobService jobService) {
    jobService.transitionJob(jobId, JobStatus.RUNNING, null, null, null)
        .compose(running -> {
            broadcastJobEvent(setupId, running);                              // RUNNING frame
            // Per-job heartbeat: bump heartbeat_at every heartbeatIntervalMs while this job runs.
            long hbTimer = vertx.setPeriodic(heartbeatIntervalMs, id ->
                jobService.heartbeat(jobId)
                    .onFailure(e -> logger.warn("Heartbeat failed for job {}: {}", jobId, e.getMessage())));
            return runReconcile(setupId, eventStoreName, mode)                // the getSetupResult→store→reconcile chain
                .eventually(() -> { vertx.cancelTimer(hbTimer); return Future.succeededFuture(); });
        })
        .compose(result -> jobService.transitionJob(
            jobId, JobStatus.COMPLETED, result.getRepaired(),                 // CORRECT field
            reconcileResultJson(result).encode(), null))
        .onSuccess(completed -> broadcastJobEvent(setupId, completed))        // COMPLETED frame
        .onFailure(t ->
            jobService.transitionJob(jobId, JobStatus.FAILED, null, null, t.getMessage())
                .onSuccess(failed -> broadcastJobEvent(setupId, failed))      // FAILED frame
                .onFailure(t2 -> logger.error("Failed to record job failure {}: {}", jobId, t2.getMessage(), t2)));
}
```
`runReconcile(...)` is the extracted `getSetupResult → ACTIVE check → getEventStores().get(name) → store.reconcileAggregateSummary(mode)` chain (reactive — no `executeBlocking`). The `.eventually` guarantees the heartbeat timer is cancelled whether the reconcile completes or throws, so a finished job never keeps heartbeating (which would otherwise hide it from the sweep). A heartbeat-UPDATE failure is logged, not fatal — the next beat retries, and if beats truly stop the sweep reclaims the job. `reconcileResultJson(result)` builds the same JSON the sync path returns (mode, aggregatesChecked, missingInSummary, staleInSummary, orphanedInSummary, repaired, sampleMismatches). `AggregateSummaryNotEnabledException` surfaces through `.onFailure` → job `FAILED` (the 202 was already sent). Every transition broadcasts (A5) — REST-layer, not the DB service.

### Bounding the lock wait — `lock_timeout` on REBUILD (closes the §13 hung-on-lock residual)

Heartbeating catches a job whose *owner* died; it cannot catch a REBUILD whose owner is alive and heartbeating but whose `LOCK TABLE … IN EXCLUSIVE MODE` is blocked indefinitely behind a long/stuck append transaction. The clean, DB-enforced bound is **`lock_timeout`** — NOT `statement_timeout`. `statement_timeout` would also abort a legitimately long *running* rebuild (re-creating the false-positive); `lock_timeout` aborts only the *wait to acquire* a lock, leaving a rebuild that already holds its lock and is doing real work untouched.

**File (modify):** `peegeeq-bitemporal/…/bitemporal/PgBiTemporalEventStore.java`, `rebuildAggregateSummary()` ([lines 1412–1422](peegeeq-bitemporal/src/main/java/dev/mars/peegeeq/bitemporal/PgBiTemporalEventStore.java:1412)). Add `SET LOCAL lock_timeout` as the first statement in the existing `withTransaction`, before the `LOCK TABLE`:
```java
private static final String RECONCILE_LOCK_TIMEOUT = "30s";   // class constant, beside MAX_METADATA_LENGTH etc.

return getOrCreateReactivePool().withTransaction(conn ->
        conn.query("SET LOCAL lock_timeout = '" + RECONCILE_LOCK_TIMEOUT + "'").execute()   // NEW
            .compose(v -> conn.query("LOCK TABLE " + summary + " IN EXCLUSIVE MODE").execute())
            .compose(v -> conn.query(upsertSql).execute())
            .compose(upserted -> conn.query(deleteOrphansSql).execute()
                    .map(deleted -> ... /* unchanged */)));
```
- `SET LOCAL` scopes the timeout to this transaction only (auto-reset on commit/rollback) — no leak to other users of the shared pool. The value is a constant literal, not a bind parameter (`SET` takes no parameters), and not user input — no injection.
- If the EXCLUSIVE lock can't be acquired within 30s, PostgreSQL aborts with SQLSTATE `55P03` ("canceling statement due to lock timeout"); `withTransaction` rolls back, the reconcile `Future` fails, and `executeReconcile`'s `.onFailure` records the job `FAILED` with that message — a clean terminal state instead of an indefinitely-`RUNNING` row.
- **REBUILD only.** `verifyAggregateSummary()` ([line 1356](peegeeq-bitemporal/src/main/java/dev/mars/peegeeq/bitemporal/PgBiTemporalEventStore.java:1356)) is read-only and takes only `ACCESS SHARE`, which `EXCLUSIVE` does not conflict with — VERIFY cannot hang on a concurrent rebuild, so it needs no `lock_timeout`.
- The 30s constant can later be promoted to instance config via the manager (the class already reads tunables from `peeGeeQManager` configuration with a default fallback — see `getConfiguredPoolSize`, lines 1997–2019) if per-instance tuning is wanted; the constant is the complete default.

---

## 8. A4 — `JobHandler`

**File (create):** `peegeeq-rest/…/handlers/JobHandler.java`, constructed `(DatabaseSetupService setupService, ObjectMapper objectMapper)` mirroring `QueueHandler`/`EventStoreHandler`, plus access to the shared `SystemMonitoringHandler` so cancel can broadcast.

Routes registered in `PeeGeeQRestServer.createRouter` (owner decision 5):
- `GET  /api/v1/setups/:setupId/jobs` → `list`
- `GET  /api/v1/setups/:setupId/jobs/:jobId` → `detail`
- `POST /api/v1/setups/:setupId/jobs/:jobId/cancel` → `cancel`

```java
public void list(RoutingContext ctx) {
    String setupId = ctx.pathParam("setupId");
    BackgroundJobService svc = setupService.getJobService(setupId);
    if (svc == null) { sendError(ctx, 404, "Setup not found or not active: " + setupId); return; }
    String jobType = ctx.request().getParam("type");
    JobStatus status = parseStatusOrNull(ctx.request().getParam("status"));   // invalid → 400
    int limit  = parseIntOrDefault(ctx.request().getParam("limit"), 50);
    int offset = parseIntOrDefault(ctx.request().getParam("offset"), 0);
    svc.list(jobType, status, limit, offset)
        .onSuccess(jobs -> sendResponse(ctx, 200, toJsonArrayEnvelope(jobs)))
        .onFailure(t -> { logger.error("List jobs failed: {}", t.getMessage(), t);
                          sendError(ctx, 503, "Failed to list jobs: " + t.getMessage()); });
}

public void detail(RoutingContext ctx) {
    String setupId = ctx.pathParam("setupId"); String jobId = ctx.pathParam("jobId");
    BackgroundJobService svc = setupService.getJobService(setupId);
    if (svc == null) { sendError(ctx, 404, "Setup not found or not active: " + setupId); return; }
    svc.get(jobId)
        .onSuccess(job -> sendResponse(ctx, 200, toJson(job)))
        .onFailure(t -> {
            if (t instanceof JobNotFoundException) sendError(ctx, 404, t.getMessage());
            else { logger.error("Get job failed: {}", t.getMessage(), t);
                   sendError(ctx, 503, "Failed to get job: " + t.getMessage()); }
        });
}

public void cancel(RoutingContext ctx) {
    String setupId = ctx.pathParam("setupId"); String jobId = ctx.pathParam("jobId");
    BackgroundJobService svc = setupService.getJobService(setupId);
    if (svc == null) { sendError(ctx, 404, "Setup not found or not active: " + setupId); return; }
    svc.get(jobId)
        .compose(job -> {
            if (job.getStatus() == JobStatus.COMPLETED || job.getStatus() == JobStatus.FAILED
                    || job.getStatus() == JobStatus.CANCELLED)
                return Future.failedFuture(new ResponseException(409, "Job not cancellable in state " + job.getStatus()));
            return svc.transitionJob(jobId, JobStatus.CANCELLED, null, null, "Cancelled by request");
        })
        .onSuccess(cancelled -> { broadcastJobEvent(setupId, cancelled); sendResponse(ctx, 200, toJson(cancelled)); })
        .onFailure(t -> {
            if (t instanceof JobNotFoundException) sendError(ctx, 404, t.getMessage());
            else if (t instanceof ResponseException re) sendError(ctx, re.statusCode, re.getMessage());
            else { logger.error("Cancel job failed: {}", t.getMessage(), t);
                   sendError(ctx, 503, "Failed to cancel job: " + t.getMessage()); }
        });
}
```
**Error→HTTP:** unknown setup/null service → 404; `JobNotFoundException` → 404; non-cancellable (`ResponseException(409)`) → 409; `DuplicateActiveJobException` (submit path) → 409; else → 503. Mirrors `EventStoreHandler`'s mapping. Cancel is the only A4 broadcast (list/detail are reads).

---

## 9. A5 — `SystemMonitoringHandler.broadcastJobEvent` + WS envelope

**File (modify):** `peegeeq-rest/…/handlers/SystemMonitoringHandler.java`:
```java
public void broadcastJobEvent(String setupId, JobRecord job) {
    JsonObject data = new JsonObject()
        .put("setupId", setupId)
        .put("jobId", job.getJobId())
        .put("jobType", job.getJobType())
        .put("status", job.getStatus().name())              // UPPERCASE — UI convention
        .put("rowsAffected", job.getRowsAffected())         // nullable
        .put("createdAt", job.getCreatedAt() != null ? job.getCreatedAt().toString() : null)   // ISO-8601
        .put("startedAt", job.getStartedAt() != null ? job.getStartedAt().toString() : null)
        .put("finishedAt", job.getFinishedAt() != null ? job.getFinishedAt().toString() : null)
        .put("lastRunAt", job.getLastRunAt() != null ? job.getLastRunAt().toString() : null)
        .put("errorMessage", job.getErrorMessage());

    JsonObject envelope = new JsonObject()
        .put("type", "job_event")
        .put("data", data)
        .put("timestamp", System.currentTimeMillis());

    // No fan-out method exists today — add this iteration. wsConnections is
    // Map<String, WebSocketConnection> (SystemMonitoringHandler.java:79); each holds a
    // ServerWebSocket `webSocket` (:860). Metrics today are pushed PER-CONNECTION via
    // per-connection vertx.setPeriodic timers (:242-244), not broadcast — so this loop is new.
    String text = envelope.encode();
    wsConnections.values().forEach(conn -> conn.webSocket.writeTextMessage(text));
}
```

**Correction (verification):** there is **no existing `broadcastToWebSocketClients`/fan-out method** — earlier drafts assumed one. Metrics are streamed per-connection by per-connection timers; the only existing iteration over `wsConnections.values()` is in `close()` (`:161`). `broadcastJobEvent` therefore introduces the first fan-out, iterating `wsConnections.values()` and calling `webSocket.writeTextMessage(...)` on each — mirroring the per-connection `WebSocketConnection.sendMetrics` write (`:873-878`).

**Envelope shape is dictated by the UI parser, and matches the `system_stats` frame.** `websocketService.ts onmessage` does `JSON.parse(event.data)` into `WebSocketMessage { type; data; timestamp }` with no per-type listeners — so the frame MUST be `{type, data, timestamp}` at top level; `type:"job_event"` routes it. This is the exact shape `WebSocketConnection.sendMetrics` already emits for `system_stats` (`:874-878`). Note: the `welcome` frame is **flat** (`type/connectionId/timestamp/message`, no nested `data`, `:228-233`) — only the metrics/`system_stats` frame is the `{type,data,timestamp}` envelope, so `job_event` follows `system_stats`, not `welcome`.

**SSE — WS only in Phase A.** The browser `EventSource.onmessage` fires only for the **default (unnamed)** event, and this UI registers no `addEventListener('job_event', …)`. A named-`job_event` SSE frame would be silently dropped. Phase A broadcasts job events over **WS only**. If SSE is added later it must use the default channel or the UI must add a listener — do not emit a dead named SSE frame now.

`JobHandler` and `EventStoreHandler` obtain the shared `SystemMonitoringHandler` reference from `PeeGeeQRestServer` and call `broadcastJobEvent` after each transition.

---

## 10. UI-readiness note (forward context — NOT Phase A work)

So Phase B slots in without rework, Phase A output already matches the UI conventions found:
- **WS envelope** exactly `{ type:"job_event", data:{…}, timestamp:<ms number> }`. No named SSE event for jobs.
- **`data` camelCase, ISO-string timestamps** (`jobId, jobType, status, rowsAffected, createdAt, startedAt, finishedAt, lastRunAt`) — slots into a TS `JobRecord` with no `transformResponse` (unlike `getQueueDetails`).
- **Status UPPERCASE** (`QUEUED|RUNNING|COMPLETED|FAILED|CANCELLED`).
- **`setupId`-scoped routes** `/api/v1/setups/:setupId/jobs[/:jobId][/cancel]` — a future `jobsApi` threads `setupId` as a query-arg field interpolated into the path, as `queuesApi` does; must be registered in `store/index.ts` (reducer + middleware).
- **Notifications:** a job notification needs only `{ resource, action }`; the Zustand store generates `id/timestamp/read`. No backend change.

No production UI scaffolding for jobs exists yet; Phase A creates none.

---

## 11. Test plan

**Conventions (all tests):** no shared base class — each declares its own `@Container static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer()`, `@Testcontainers`, `@ExtendWith(VertxExtension.class)`, `@Tag(TestCategories.CORE)` (db/bitemporal) or `@Tag(TestCategories.INTEGRATION)` (rest). Schema = **`PostgreSQLTestConstants.TEST_SCHEMA`** (`"peegeeq_test"`) — never `public`, never from system props. Schema threaded to all three places, copied verbatim from `BiTemporalAggregateSummaryIntegrationTest.@BeforeEach`:
1. `PeeGeeQTestConfig.builder().from(postgres).schema(schema)…build()` (`build()` throws without `.schema()`).
2. `PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema, SchemaComponent.…)`.
3. Raw `setupPool` (no `search_path`) → statements schema-qualified; `manager.getPool()` (search_path=schema) → unqualified.

- **A1** (`peegeeq-db`): run `createCompleteSetup` (schema=`TEST_SCHEMA`); assert via `setupPool` (qualified) that `information_schema.tables` contains `peegeeq_jobs` in `TEST_SCHEMA`, and `pg_indexes` contains `idx_peegeeq_jobs_type_status` + `uq_peegeeq_jobs_active`. RED first.
- **A2** (`peegeeq-db`): `new PgJobRepository(manager.getPool())`, `new BackgroundJobServiceImpl(repo)`. One test per transition (QUEUED→RUNNING→COMPLETED; →FAILED; QUEUED→CANCELLED) asserting status + `started_at`/`finished_at`/`last_run_at` + `rows_affected`/`error_message`. Duplicate-active: submit with a `dedupKey`, leave QUEUED, submit same `(jobType, dedupKey)` → failed Future carries `DuplicateActiveJobException` (driven by the `uq_peegeeq_jobs_active` partial unique index, SQLSTATE 23505).
- **A2 heartbeat** (`peegeeq-db`): transition a job to `RUNNING` → assert `heartbeat_at` set. Capture it, then call `repository.heartbeat(jobId)` → assert `heartbeat_at` **advanced**. Move the job to terminal, call `heartbeat(jobId)` again → assert **no-op** (the `status='RUNNING'` guard; row not resurrected).
- **A2 stuck-job recovery** (`peegeeq-db`): `new StuckJobRecoveryManager(manager.getPool(), Duration.ofMinutes(2), true)`. Insert directly:
  - `RUNNING`, `heartbeat_at = now() - 1h` (dead owner) → **swept**.
  - `RUNNING`, `started_at = now() - 1h` but `heartbeat_at = now()` (legitimately long, still alive) → **NOT swept** — the heartbeating win; the old age-based design would have wrongly failed this.
  - `RUNNING`, `heartbeat_at = now()` (fresh) → NOT swept.
  - `QUEUED`, `created_at = now() - 1h`, `heartbeat_at` null → **swept** (stuck submission).
  Call `recoverStuckJobs()` → assert returns **2**, the two stale rows `FAILED` with `finished_at`/`last_run_at` set, both alive rows **untouched**. Then a `submit` of the same `(jobType, dedupKey)` as a swept row now **succeeds** (proves the `uq_peegeeq_jobs_active` index unblocked). Assert `enabled=false` → 0, touches nothing; empty table → 0.
- **A3** (`peegeeq-rest`): deploy `PeeGeeQRestServer`; create an aggregate-summary-enabled event store; `POST …/reconcile?mode=rebuild&async=true` → `202` + `jobId`; poll `GET …/setups/{setupId}/jobs/{jobId}` to `COMPLETED`; assert `rowsAffected` == `getRepaired()`; submit again → assert `lastRunAt` reflects the prior run.
- **A3 lock_timeout** (`peegeeq-bitemporal`): from a separate connection, `BEGIN; LOCK TABLE <summary> IN EXCLUSIVE MODE;` and hold it; concurrently call `rebuildAggregateSummary()` → assert the returned `Future` **fails within ~30s** with a lock-timeout error (SQLSTATE `55P03`), not hangs. Release the holder → a subsequent rebuild succeeds. (Temporarily lowering `RECONCILE_LOCK_TIMEOUT` for the test keeps it fast — or assert the failure mode with the default and a generous test timeout.)
- **A4** (`peegeeq-rest`): `GET …/jobs` (+ `type`/`status` filters); `GET …/jobs/{jobId}` (unknown → 404); `POST …/jobs/{jobId}/cancel` on a terminal job → 409; unknown setup → 404.
- **A5** (`peegeeq-rest`): WS client → `/ws/monitoring`; trigger async reconcile; assert `job_event` frames for `QUEUED → RUNNING → COMPLETED`, each top-level `{type:"job_event", data, timestamp}`, `data.status` UPPERCASE, `data.setupId`/`data.jobId` present.

---

## 12. Implementation order

1. **A2 API types** (`peegeeq-api/jobs/*`) — leaves everything imports.
2. **A2 facade thread** — add abstract `getJobService` to the interface, then fix all 3 facades + 7 test doubles **in the same commit** (compile gate). Keeps the tree green.
3. **A1 SQL template + manifest** — independent of Java; table must exist before repo integration tests. Land with its RED setup test.
4. **A2 DB impl** — `PgJobRepository` + `BackgroundJobServiceImpl`, then wire `jobServices` population / `getJobService` / `destroySetup` in `PeeGeeQDatabaseSetupService`. Land with per-transition + duplicate-active tests.
5. **A2 recovery + heartbeat** — `heartbeat` on the repo/service (needs the `heartbeat_at` column from A1), `StuckJobRecoveryManager` (stale-heartbeat predicate), the `jobs.recovery.*` + `jobs.heartbeat-interval` config in `QueueConfig` (+ fix every `new QueueConfig(...)` call site — a compile gate) + the timer/teardown wiring in `PeeGeeQManager`. Land with the heartbeat + stuck-job recovery tests. Independent of the REST layer; can land any time after 4.
6. **A5 broadcast method** — `SystemMonitoringHandler.broadcastJobEvent`; needed by A3 and A4; small and standalone.
7. **A3 async reconcile** — `EventStoreHandler` branch + `executeReconcile` (incl. the per-job heartbeat timer calling `jobService.heartbeat`) + the `SET LOCAL lock_timeout` line on REBUILD in `PgBiTemporalEventStore` (§7; the `lock_timeout` change is independent and can land first/standalone). Depends on 4 + 5 (heartbeat op) + 6.
8. **A4 JobHandler** — list/detail/cancel + route registration. Depends on 4 + 6. A3's poll test needs A4's `detail` route, so 7/8 may interleave; `getJobService` (4) and broadcast (6) precede both.

Order: types → interface/facade (compile gate) → DDL → DB impl → recovery → broadcast → REST handlers. Each step is RED-first testable; nothing higher compiles until the layer beneath exists.

---

## 13. Remaining real risks

1. **REST setup-service reconnect — a pre-existing PeeGeeQ gap, not jobs scope.** Jobs survive a restart at the manager level: `PeeGeeQManager.start()` is non-destructive (validates existing tables, drops nothing — verified), so a reconnected manager reaches the surviving `peegeeq_jobs` rows and its recovery sweep reclaims a dead process's orphans (§5). The destructive `DROP DATABASE` is only in `createCompleteSetup`'s one-time provisioning path, not restart. The gap is REST-only: `PeeGeeQDatabaseSetupService` populates `activeManagers` solely via `createCompleteSetup` (destructive) and `registerSetupForTesting` (test-only) — no non-destructive reconnect entry — so after a REST process restart, `getJobService(setupId)` (and every other per-setup REST accessor: subscriptions, queues, event stores) returns `404` and the setup's recovery timer is not running until it is reconnected. `SubscriptionPersistenceAcrossRestartIntegrationTest.test04` documents this as a known limitation; its own note prescribes the fix: lazy reconnect / setup-cache loading. The fix (when `getJobService`/setup access finds a `setupId` whose schema exists but is absent from `activeManagers`, construct a `PeeGeeQManager` on the existing schema without dropping, and populate the maps) is a **PeeGeeQ-level setup-lifecycle item affecting all setup data**, recommended as a follow-up. It is modest (a non-destructive variant of the existing start path), not the "persistence layer" earlier framing implied. **Phase A scope:** the recovery sweep is in place and correct; cross-restart job recovery becomes automatic for REST once the reconnect gap is closed.

2. **False-positive (sweep fails a legitimately long job) — RESOLVED by heartbeating (decision 6/7, in scope).** A running job bumps `heartbeat_at` every `heartbeat-interval` (default `30s`); the sweep fails only `RUNNING` rows whose `heartbeat_at` is older than `stale-timeout` (default `2m`). A legitimately long REBUILD keeps a fresh heartbeat and is **never** swept, no matter how long it runs. The earlier "generous 30m timeout" mitigation is gone — detection is now tight and precise.
   **Hung-but-alive job (blocked on the lock) — RESOLVED in scope (§7).** The one case heartbeating cannot catch — a REBUILD whose owner is alive and heartbeating but whose `LOCK TABLE … EXCLUSIVE` is blocked indefinitely behind a stuck append transaction — is now bounded by `SET LOCAL lock_timeout = '30s'` on the REBUILD transaction in `PgBiTemporalEventStore`. After 30s waiting for the lock, PostgreSQL aborts (SQLSTATE 55P03), the future fails, and the job lands `FAILED` cleanly. `lock_timeout` (not `statement_timeout`) is used deliberately so a rebuild that *holds* its lock and is doing real work is never aborted — no false-positive reintroduced. VERIFY is read-only (`ACCESS SHARE`), so it cannot hang on a rebuild and needs no bound. No residual remains here.

3. **REBUILD's `EXCLUSIVE` lock — accepted, bounded both ways.** `rebuildAggregateSummary` holds `LOCK TABLE …_aggregate_summary IN EXCLUSIVE MODE` for its transaction. Two directions: (a) two concurrent REBUILDs for the same store+mode are **hard-prevented** by `dedupKey = eventStoreName + ":" + mode` enforced by the `uq_peegeeq_jobs_active` partial unique index (a DB constraint, not a best-effort) — the second submit gets 409 before any lock is taken; (b) a REBUILD waiting on the lock behind a stuck append is **bounded to 30s** by the new `lock_timeout` (risk 2 / §7). What remains is inherent and accepted: while a *legitimate* REBUILD runs, concurrent appends block briefly on their summary-trigger write — that is the cost of a consistent rebuild and is not something the job layer introduces or should mask. No open decision.

4. **Event-loop safety — RESOLVED, not a risk.** The reconcile is fully reactive `pool.withTransaction` (no JDBC/CPU blocking), so no `executeBlocking` (§7). Recorded here only so no one adds a needless worker-thread wrapper.

No other risks survive: pool retrieval (`manager.getPool()`), schema qualification (`{schema}` DDL + connection-level `search_path`), rows-affected (`getRepaired()`), required-tables auto-pickup, the facade checklist, and the WS envelope are all grounded above.

---

## Appendix A — Complete SQL (every statement, precise)

This is the single authoritative copy of all SQL the implementation runs. **DDL** (A.1) is `{schema}`-parameterized for template substitution. **All runtime SQL** (A.2–A.8) is **unqualified** — bare `peegeeq_jobs` on the manager's `search_path`-bound pool — with PostgreSQL positional `$n` parameters. `job_id` path values are parsed to `java.util.UUID` in Java before binding (malformed → `JobNotFoundException` → 404), so the SQL binds a `UUID`, never an in-SQL cast. Status names bind as text. `RETURNING *` rows are mapped to `JobRecord` by a private `mapRow(Row)`.

### A.1 — DDL: `10-jobs-table.sql` (one file — table + both indexes; §3)
```sql
-- PeeGeeQ background jobs table. Schema is connection-level (search_path);
-- DDL is {schema}-parameterized per the schema-anchor foundation.
CREATE TABLE IF NOT EXISTS {schema}.peegeeq_jobs (
    job_id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    job_type        VARCHAR(255) NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'QUEUED'
                        CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED')),
    params          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    result          JSONB,
    rows_affected   BIGINT,
    error_message   TEXT,
    dedup_key       VARCHAR(512),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    heartbeat_at    TIMESTAMPTZ,                 -- bumped by the executor while RUNNING; drives stuck detection
    finished_at     TIMESTAMPTZ,
    last_run_at     TIMESTAMPTZ
);

-- List/filter index
CREATE INDEX IF NOT EXISTS idx_peegeeq_jobs_type_status
    ON {schema}.peegeeq_jobs (job_type, status, created_at DESC);

-- Duplicate-active guard: at most one QUEUED|RUNNING job per (job_type, dedup_key).
-- dedup_key IS NULL ⇒ not guarded (a job with no resource key is not deduplicated).
CREATE UNIQUE INDEX IF NOT EXISTS uq_peegeeq_jobs_active
    ON {schema}.peegeeq_jobs (job_type, dedup_key)
    WHERE status IN ('QUEUED','RUNNING') AND dedup_key IS NOT NULL;
```
The `status` CHECK and the `dedup_key IS NOT NULL` index predicate mirror `04a-core-table-outbox.sql` (its status CHECK; its idempotency index `WHERE idempotency_key IS NOT NULL`). One file with three statements is created in full — verified by runtime probe (§3).

### A.2 — `insert(JobSubmitRequest)` → new QUEUED row
```sql
INSERT INTO peegeeq_jobs (job_type, params, dedup_key)
VALUES ($1, COALESCE($2::jsonb, '{}'::jsonb), $3)
RETURNING *
```
`$1` jobType · `$2` params (JSON text, nullable → `{}`) · `$3` dedupKey (nullable). `job_id`, `status='QUEUED'`, `created_at` come from column defaults. A `23505` violation on `uq_peegeeq_jobs_active` → failed `Future` carrying `DuplicateActiveJobException` (→ REST 409); the constraint surfaces, never swallowed.

### A.3 — `updateStatus(jobId, to, rowsAffected, result, errorMessage)` → lifecycle transition
```sql
UPDATE peegeeq_jobs
SET status        = $2,
    rows_affected = COALESCE($3, rows_affected),
    result        = COALESCE($4::jsonb, result),
    error_message = COALESCE($5, error_message),
    started_at    = CASE WHEN $2 = 'RUNNING' THEN now() ELSE started_at END,
    heartbeat_at  = CASE WHEN $2 = 'RUNNING' THEN now() ELSE heartbeat_at END,
    finished_at   = CASE WHEN $2 IN ('COMPLETED','FAILED','CANCELLED') THEN now() ELSE finished_at END,
    last_run_at   = CASE WHEN $2 IN ('COMPLETED','FAILED','CANCELLED') THEN now() ELSE last_run_at END
WHERE job_id = $1
  AND status NOT IN ('COMPLETED','FAILED','CANCELLED')
RETURNING *
```
`$1` jobId (UUID) · `$2` target status name · `$3` rowsAffected (nullable; `0` is preserved — `COALESCE(0, …)=0`, not treated as null) · `$4` result JSON (nullable) · `$5` errorMessage (nullable). The `WHERE … status NOT IN (terminal)` guard blocks retrograde/double transitions: **0 rows updated ⇒ already terminal ⇒ fail with `ResponseException(409)`**; non-zero ⇒ return the updated row. Setting `started_at`+`heartbeat_at` on `RUNNING` makes a job "alive" the instant it starts.

### A.4 — `heartbeat(jobId)` → liveness bump
```sql
UPDATE peegeeq_jobs SET heartbeat_at = now()
WHERE job_id = $1 AND status = 'RUNNING'
```
`$1` jobId (UUID). Returns `Future<Void>`. 0 rows = harmless no-op (job already terminal — never resurrected).

### A.5 — `findById(jobId)`
```sql
SELECT * FROM peegeeq_jobs WHERE job_id = $1
```
`$1` jobId (UUID). Empty result ⇒ `JobNotFoundException` (→ 404).

### A.6 — `list(jobType, status, limit, offset)` — optional filters, newest first
```sql
SELECT * FROM peegeeq_jobs
WHERE ($1::varchar IS NULL OR job_type = $1)
  AND ($2::varchar IS NULL OR status = $2)
ORDER BY created_at DESC
LIMIT $3 OFFSET $4
```
`$1` jobType (nullable) · `$2` status name (nullable) · `$3` limit · `$4` offset. The `$n::varchar IS NULL OR …` idiom makes each filter optional within one prepared statement (no dynamic SQL).

### A.7 — `findLatestByType(jobType)` → last-run stats source
```sql
SELECT * FROM peegeeq_jobs
WHERE job_type = $1 AND status = 'COMPLETED'
ORDER BY finished_at DESC NULLS LAST
LIMIT 1
```
`$1` jobType. Returns the most recent COMPLETED row of that type; `finished_at - started_at` (duration) and `rows_affected` feed the "estimated ~Ns based on last run" guesstimate. Per-resource ETA would add `AND dedup_key = $2`; the Phase-A interface is by type only.

### A.8 — `StuckJobRecoveryManager.recoverStuckJobs()` → heartbeat-stale sweep (§4/§5)
```sql
UPDATE peegeeq_jobs
SET status        = 'FAILED',
    error_message = 'Recovered: no heartbeat within stale timeout',
    finished_at   = now(),
    last_run_at   = now()
WHERE status IN ('QUEUED','RUNNING')
  AND COALESCE(heartbeat_at, started_at, created_at) < $1
RETURNING job_id
```
`$1` cutoff `= now() − staleTimeout` (an `OffsetDateTime`). Returned row count = number recovered. A healthy RUNNING job (fresh `heartbeat_at`) is never matched; an orphan (stale/absent heartbeat) is failed and its `uq_peegeeq_jobs_active` slot freed.

### A.9 — A3 `lock_timeout` (in `PgBiTemporalEventStore.rebuildAggregateSummary`; §7)
First statement inside the existing `pool.withTransaction(...)`, before `LOCK TABLE … IN EXCLUSIVE MODE`:
```sql
SET LOCAL lock_timeout = '30s'
```
`SET LOCAL` scopes the timeout to the transaction (auto-reset on commit/rollback). On lock-wait timeout PostgreSQL aborts with `55P03`; the future fails → job `FAILED`. REBUILD only — VERIFY takes no `EXCLUSIVE` lock (§7).
