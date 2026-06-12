# Background Jobs Infrastructure + Aggregate Summary UI Integration

## Status: OPEN — designed 12 Jun 2026, NOT yet implemented

End-to-end wiring of the I4 aggregate-summary features (and future long-running operations)
into the REST API and Management UI, built on a durable, per-setup background-jobs
infrastructure. Supersedes the earlier in-memory job-registry sketch per owner feedback:

> Owner feedback (12 Jun 2026): (1) the UI needs a Jobs screen that really connects to backend
> jobs; (2) jobs must be durable with per-run stats (time, rows, duration) used to guesstimate
> future runs — more background jobs are coming (e.g. PostgreSQL VACUUM for high-churn
> queue-like tables); (3) a notification history screen is needed alongside the existing
> bell/toast; (4) the jobs table is per-PeeGeeQ-setup, created automatically at setup creation
> with the rest of the schema.

Related: `AGGREGATE-STREAM-IMPROVEMENTS-PLAN-7-Jun-2026.md` (I4 — the first job consumer),
`SCHEMA-PROCESSING-GAPS-CRITICAL-12-Jun-2026.md` (the schema-anchor foundation this design
must honor).

---

## Design

### D1 — Per-setup durable jobs table (created with the setup, in the setup's schema)

A new base template file (e.g. `db/templates/base/10-jobs-table.sql` + `.manifest` entry) so
**every setup gets the table automatically at creation**, in the setup's schema — the same
mechanism as `outbox`, `queue_messages`, etc. All runtime access goes through the setup's
pools (search_path), unqualified — per the schema-anchor foundation.

```sql
CREATE TABLE IF NOT EXISTS {schema}.peegeeq_jobs (
    id              BIGSERIAL PRIMARY KEY,
    job_id          VARCHAR(255) NOT NULL UNIQUE,      -- external UUID
    job_type        VARCHAR(100) NOT NULL,             -- e.g. AGGREGATE_SUMMARY_REBUILD
    resource        VARCHAR(255),                      -- e.g. event store table name
    status          VARCHAR(20)  NOT NULL CHECK (status IN
                        ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED')),
    phase           VARCHAR(100),                      -- coarse progress for single-statement jobs
    parameters      JSONB DEFAULT '{}',
    requested_by    VARCHAR(255),
    rows_affected   BIGINT,
    error_message   TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    started_at      TIMESTAMP WITH TIME ZONE,
    completed_at    TIMESTAMP WITH TIME ZONE
);
-- One active job per (type, resource): DB-enforced concurrency guard → REST 409
CREATE UNIQUE INDEX idx_peegeeq_jobs_active
    ON {schema}.peegeeq_jobs (job_type, resource)
    WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX idx_peegeeq_jobs_listing
    ON {schema}.peegeeq_jobs (created_at DESC);
```

**Run-history stats and ETA guesstimate**: the completed rows ARE the stats — no separate
stats table. For a new submission, the most recent COMPLETED row of the same
`(job_type, resource)` yields `lastRun { durationMs, rowsAffected, completedAt }`; the UI
shows "estimated ~42s based on last run (12,431 rows)". Honest semantics: a guesstimate
label, never a progress percentage we cannot compute.

**Progress for single-statement jobs**: rebuild is one `INSERT...SELECT` + one `DELETE`
under a lock — no real percent exists. The `phase` column reports coarse phases
(`LOCKING` → `REBUILDING` → `DELETING_ORPHANS`), and the ETA comes from history (above).

**Retention**: unbounded job history bloats — prune by age/count. Fittingly, pruning is
itself a future job type (see D6); not needed for phase A.

### D2 — Backend job framework (peegeeq-db)

- `JobRepository` — reactive CRUD over `peegeeq_jobs` (unqualified SQL, per-setup pool).
- `BackgroundJobService` (per setup / via the manager): `submit(jobType, resource, params,
  executor)` → INSERT QUEUED (the partial unique index rejects a duplicate active job →
  surfaces as 409 at REST) → transition RUNNING + phases → executor `Future` →
  COMPLETED (`rows_affected`) or FAILED (`error_message`). Every transition emits a job
  event (D4). Errors propagate into the row and the event — never swallowed.
- **Non-transactional capability**: `VACUUM` cannot run inside a transaction; the framework
  must support executors that need a raw, non-transactional connection. Designed in from the
  start (it constrains the executor interface), implemented with the VACUUM job in phase D.
- First job type: `AGGREGATE_SUMMARY_REBUILD` wrapping
  `EventStore.reconcileAggregateSummary(REBUILD)`. `AGGREGATE_SUMMARY_VERIFY` runs
  synchronously by default (read-only, fast at current scale) with async as an option for
  very large stores.

### D3 — REST surface

| Endpoint | Behavior |
|---|---|
| `POST /api/v1/eventstores/:setupId/:name/aggregate-summary/reconcile?mode=rebuild&async=true` | `202 { jobId, lastRun? }`; the existing synchronous form (no `async`) stays for small stores/scripting |
| `GET /api/v1/setups/:setupId/jobs` | List with filters (`type`, `status`, `resource`) + pagination; newest first |
| `GET /api/v1/setups/:setupId/jobs/:jobId` | Job detail incl. `lastRun` stats of the same (type, resource) |
| Duplicate active job | `409` naming the running jobId (DB-enforced via the partial unique index) |
| Cancellation | Out of scope for phase A: only QUEUED jobs could be cancelled cheaply; cancelling a lock-holding rebuild is not safely interruptible. Recorded as a decision, revisit per job type. |

### D4 — Push events and notifications

- Job lifecycle events broadcast over the existing `/ws/monitoring` channel as
  `{ type: "job_event", setupId, jobId, jobType, resource, status, phase, rowsAffected,
  durationMs }` via `SystemMonitoringHandler` — generic, so **backfill completion** (today
  discoverable only by 30s polling) and all future job types ride the same channel.
- UI: the monitoring hook feeds the existing Zustand notification store → bell badge + toast
  ("Summary rebuild for 'orders' completed: 12,431 rows, 41s").

### D5 — Management UI screens

**Jobs screen** (`/jobs`, new sidebar entry):
- Setup scope selector (jobs are per setup — the schema anchor).
- Table: type, resource, status tag, phase, requested by, created/started, duration, rows.
- Live: WS job events update rows in place; fallback periodic refresh.
- Detail drawer: parameters, error message, and the run history of the same (type, resource)
  with the stats — this is where the guesstimate data is visible.
- Actions: Re-run (submits a new job of the same type/resource); respects the 409 guard.

**Notification history screen** (`/notifications`, linked from the bell's "View all"):
- Lists notifications with filters (type, read/unread), mark-read/mark-all, clear.
- v1 persistence: client-side (raise the current 50-item cap; persist the store to
  localStorage so refresh does not wipe history). **Decision recorded**: a server-side
  notification log table would make history durable and cross-browser; jobs are already
  durable via `peegeeq_jobs`, so v1 defers the server-side log — revisit if non-job
  notifications need durability.

**Event Stores page** (the I4 features become operable):
- Create modal: "Aggregate summary" toggle → `aggregateSummaryEnabled`.
- Listing/details: show the flag (backend prerequisite: `management/event-stores` listing
  must return `aggregateSummaryEnabled` — it does not today).
- Per summary-enabled store: **Verify** (drawer with the reconcile report: checked / missing
  / stale / orphaned + sample pairs; "Rebuild now" button when drift > 0) and **Rebuild**
  (submits the async job; toast links to the Jobs screen).

**Aggregate Stream page**: optional "Source: Auto / Event log / Summary" selector exposing
the `source=` parameter — the UI counterpart of the dual-path guarantee.

### D6 — Declared future job types (why the table is durable and generic)

| Job type | Purpose |
|---|---|
| `VACUUM_MAINTENANCE` | `VACUUM (ANALYZE)` on high-churn queue-like tables (`outbox`, `queue_messages`, `dead_letter_queue`) — heavy insert/delete tables are the classic bloat profile; needs the non-transactional executor capability |
| `AGGREGATE_SUMMARY_VERIFY` (scheduled) | Periodic drift detection with a warning notification when drift found (DeadConsumerDetectionJob timer pattern + consecutive-failure escalation idiom) |
| `JOBS_RETENTION` | Prunes old `peegeeq_jobs` rows by age/count |
| (later) backfill as a job | Migrate backfill status reporting onto the same job events channel |

---

## Phased tasks

### Phase A — foundation (backend, TDD)
| # | Task | Notes |
|---|------|-------|
| A1 | `10-jobs-table.sql` base template + manifest; created at setup creation in the setup's schema | RED: setup integration test asserting the table + indexes exist (pattern: `testAggregateSummaryTableCreatedAndMaintained`) |
| A2 | `JobRepository` + `BackgroundJobService` with lifecycle transitions, stats lookup, duplicate-active rejection | RED: unit/integration tests per transition; duplicate submit rejected |
| A3 | `AGGREGATE_SUMMARY_REBUILD` job type; `async=true` on the reconcile endpoint → 202 + jobId | RED: REST test — submit async, poll job to COMPLETED, assert rows_affected and lastRun on second submit |
| A4 | Jobs REST endpoints (list/detail/filters) + 409 path | RED: REST tests |
| A5 | Job events over `/ws/monitoring` | RED: WS test asserting job_event frames for a full lifecycle (pattern: SystemMonitoringHandler tests) |

### Phase B — UI core
| # | Task | Notes |
|---|------|-------|
| B1 | `management/event-stores` listing returns `aggregateSummaryEnabled` | Backend prerequisite for all store-page UI |
| B2 | Jobs screen with live WS updates + detail drawer + re-run | e2e: submit rebuild via UI, watch status transition live, assert stats display |
| B3 | Event Stores: create-modal toggle, flag display, Verify drawer, Rebuild action → Jobs screen link | e2e per action |
| B4 | Bell/toast wiring for job events | e2e: rebuild completion produces a notification |

### Phase C — UI completion
| # | Task | Notes |
|---|------|-------|
| C1 | Notification history screen (client-persisted v1) | e2e: history survives refresh, mark-read works |
| C2 | Enable-on-existing-store endpoint (install template → async rebuild, the documented two-txn sequence) + UI action | Productizes the manual procedure |
| C3 | Aggregate Stream source selector | Small |

### Phase D — future job types
| # | Task | Notes |
|---|------|-------|
| D1 | `VACUUM_MAINTENANCE` job (non-transactional executor capability) | The framework constraint is designed in phase A |
| D2 | Scheduled `AGGREGATE_SUMMARY_VERIFY` with drift notifications | Timer + escalation idioms |
| D3 | `JOBS_RETENTION` pruning job | Keeps the jobs table bounded |

## Open decisions (recorded, owner to confirm during implementation)
1. Job cancellation semantics per job type (phase A ships without it).
2. Server-side notification log vs client-persisted history (v1 = client-persisted).
3. Whether `requested_by` carries a real principal once authentication exists (currently no
   auth in the management API — store the caller IP/UA or leave null until then).
