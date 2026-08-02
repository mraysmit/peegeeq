# peegeeq-utilities-ui — Implementation Plan

**Author**: Mark Andrew Ray-Smith Cityline Ltd  
**Created**: 2026-07-05  
**Version**: 1.0  

This plan sequences the **remaining** work for `peegeeq-utilities-ui`. It is derived from two
documents and should be read alongside them:

- [PEEGEEQ_DEVOPS_UTILITIES_DESIGN.md](PEEGEEQ_DEVOPS_UTILITIES_DESIGN.md) — the design document:
  **Part I** is the functional/feature design ("§6.1" references point into it); **Part II** is the
  technical design / as-built state ("TD §12" references point into it); **Part III** is the
  scheduled-runs feature design (Phase SCH references point into it).

The feature design's own §18 plan is written as build-from-scratch (Phases 1, 1B, 2, 3, 4, 5, 6).
Phases 1, 1B, and 2 are **already implemented** in the codebase. This plan therefore starts from
the current baseline and covers only what is left, plus the divergences recorded in TD §12.

---

## Baseline — what is already built

| Area | Status | Reference |
|---|---|---|
| Types (`generator.ts`, `queue.ts`, `setup.ts`) | ✅ done | TD §3.1 |
| Template resolver + `findMissingLists` | ✅ done | TD §3.1, §6 |
| Publication engine (concurrent fan-out, auto-stop, caller-supplied identity) | ✅ done and UI-wired (B.0/B.5) | TD §3.1, §7 |
| Services (setup, queue, publish, template, valueList, config) | ✅ done | TD §3.1, §5 |
| Stores (generator, template, valueList, utilities) | ✅ done | TD §3.1 |
| Create Setup / Create Queue pages | ✅ **removed** (Phase S / S.6, 2026-07-17 — provisioning is admin-tool-only; replaced by ConnectSetupPage) | TD §3.2; S.6 |
| Setups list + Setup detail (queue CRUD, badges) + per-row Detach | ✅ done | TD §3.2 |
| TargetSelector (Zone A) | ✅ done | TD §3.2 |
| Connect-to-existing-setup UI (ConnectSetupPage + service) | ✅ done (Phase S / S.5, 2026-07-17) | setup-db §12 |
| Generator Zones B–E | ✅ done (Phase B, 2026-07-18) | feature §6.1 |
| Template Manager page | ✅ done (Phase C, 2026-07-18) | feature §6.2 |
| Value List Manager page | ✅ done (Phase D, 2026-07-19) | feature §6.3 |
| Scheduled runs (screen, scheduler, history, templates, import) | ✅ done (2026-07-19, SCH.0–SCH.8; hardened SCH.9 2026-07-21) | Phase SCH; design Part III |
| Overview redesign (per-setup, no global aggregates) | ✅ done (per-setup table + detail card; connect CTA) | feature §6.6, TD §12.2 |
| Saved scenarios + Tools launcher (`/tools` no longer duplicates Overview) | ✅ done (Phase G.4, 2026-08-01) | design §19.0, §19.4 |

---

## Cross-cutting rules (apply to every phase)

These are mandatory and come from the project standards — not optional style notes.

1. **Mandatory pre-work before writing code** (per `CLAUDE.md`): read
   `docs-design/dev/pgq-coding-principles.md` and
   `docs-design/testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md` in full; read every file you
   will modify; read the existing tests in the same area and mirror their pattern exactly.
2. **One phase at a time.** Implement a phase, then stop and report. **Do not run tests** —
   the user runs tests and verifies before the next phase begins.
3. **No error swallowing.** Every catch surfaces the error (`message.error(...)` /
   `<Alert type="error">` in UI). Silent catches are defects (see TD §10, §12.6).
4. **No mocking.** No Mockito, no mocked DB, no Playwright `page.route` HTTP stubbing.
   Testcontainers for anything touching the backend.
5. **Mirror existing patterns.** Reuse the idioms already in the built pages/services/stores;
   do not invent new ones.
6. **Banned patterns stay banned** (relevant to any Java touched in a backend phase, and to TS):
   no `.recover`/`.otherwise`/`.await` on Futures, no `CompletableFuture`/blocking bridges, no
   `Thread.sleep`. Grep touched files before and after.
7. **Never assert runtime behaviour from static reading.** Where a phase depends on backend
   behaviour (e.g. the delete-queue endpoint), verify by running, not by asserting.

Per-phase verification is always: banned-pattern grep on touched files → `npm run build`
(zero TS errors) → targeted Vitest for changed units → targeted Playwright for changed flows.
Provide the commands; the user runs them.

---

## Dependency graph

```
Phase A (divergence fixes & hardening)     ── ✅ DONE 2026-07-10 (TD §12 items 3–6 resolved)
Phase S (Setup connect: manual attach)     ── ✅ DONE 2026-07-18 (S.0–S.6 + detach + guarded drop W-DD)
      │                                       (DECIDED: S lands BEFORE B — setups are provisioned by
      │                                        the admin tool, NOT by the generator; connecting to an
      │                                        existing setup is the generator's only path to a target)
      ├── Phase B (Generator UI: Zones B–E)  ── ✅ DONE 2026-07-18 (B.0–B.6; engine wired via runStarter)
      │     ├── Phase C (Template Manager)    ── ✅ DONE 2026-07-18
      │     └── Phase D (Value List Manager)  ── ✅ DONE 2026-07-19
      └── Phase R (Durable registry + auto-reload) ── after S; persists bindings, reconnects on boot
             └── Phase M (Management DB: estate control plane) ── after R; org-wide + single-owner leases
Phase E (Overview redesign)                ── independent (done)
Phase F (Integration + E2E + screenshots)  ── ✅ DONE 2026-07-19 (F.1–F.4; F.5 full gate = user's call)
Phase SCH (Scheduled generator runs)       ── ✅ DONE 2026-07-19 (SCH.0–SCH.8; graduated from Part I §3
      │                                        non-goals) + SCH.9 hardening 2026-07-21; design Part III
Phase G (Generation tool suite, §19)       ── after B; most tools client-only, no backend
Phase T (Backend telemetry, peegeeq-db/rest) ── gates only the two telemetry-heavy G tools:
      └─ required by  G.2 (native-vs-outbox)  and  G.1b (rich breaking-point)

Cross-track edges & newly-surfaced prerequisites (details in the next section):
  M enables ─► T.7 estate telemetry fan-out · G estate target routing · E/A.2 server-aware setup UI
  Pre-1  Backend connection settings (utilities-ui)     ── reach a chosen backend
  Pre-2  API-layer re-architecture (copy management-ui) ── gates A, B, C, D, S.5
  Pre-3  S.2 reconstitution enumeration spike           ── gates S
  Pre-4  Credential key provisioning (env/KMS)          ── gates R, M
```

---

## Cross-track dependencies & newly-surfaced prerequisites

Design work on the setup lifecycle, the management database, and telemetry created dependency edges
and prerequisites beyond the per-phase lists. Captured here until each is worked into a phase.

### Cross-track edges

- **T ↔ M (telemetry × estate).** The telemetry design assumes a **single backend** (`/sse/metrics`
  per process, `dbPool` per process). Under the estate model, setups live on **different servers**,
  so DB-level telemetry (**T.7**, `pg_stat_*` per setup DB) and per-setup stats must **fan out to
  each setup's own server** — the connections **M** manages. → **T.7 at estate scale depends on M**,
  and cross-backend telemetry aggregation becomes a new concern.
- **G ↔ M (generation × estate).** Publishing to a **single-owned** setup routes through its owning
  backend. Phase **G** target selection assumes one backend → **G at estate scale depends on M** for
  target/ownership routing.
- **E/A.2 ↔ M (setup UI × estate).** Overview and `TargetSelector` have no notion of *which
  server/backend* a setup lives on. Phase **E** (done) and **A.2** predate the estate model →
  **M implies revisiting** the utilities-ui setup listing to be server-aware.

### Prerequisites (promote to phases when scheduled)

- **Pre-1 — Backend connection settings (utilities-ui).** A backend-URL Settings control (copied from
  management-ui's `configService.testRestConnection` + Settings page + `ConnectionStatus`) so the UI
  can target a chosen backend. Needed by connect-to-existing and by the multi-server estate.
  Independent utilities-ui work; prerequisite for reaching any non-default backend.
- **Pre-2 — API-layer re-architecture (copy management-ui).** The "Backend integration architecture"
  section below is a *principle*, not a sequenced phase; A.1 does one slice (createQueue), but the
  full `endpoints.ts` + `PeeGeeQClient` + RTK adoption is unscheduled — yet **A, B, C, D, and S.5 all
  sit on that layer**. Decide prerequisite-once vs incremental, and sequence it early.
- **Pre-3 — S.2 reconstitution spike. DONE — findings (see setup-db §2, §4):** a queue's artifact is a
  per-queue table `"{queueName}" (LIKE queue_template INCLUDING ALL)`, but that table is an **inert
  marker** — native/outbox route through the shared `queue_messages` / `outbox` tables by `topic`, so a
  native and an outbox queue produce **byte-identical DDL** and **neither `implementationType` nor the
  full `QueueConfig` is recoverable** from the schema; `setupId` is likewise in-memory only.
  **Consequence for S.2:** two per-schema tables written transactionally at creation —
  `peegeeq_object_registry` (`object_name`, `kind`, `config`, `created_at`) and single-row
  `peegeeq_setup_metadata` (`setup_id`, `schema_name`, `schema_version`, `created_at`) — after which
  `connectToExistingSetup` reconstitutes from the tables (exact `kind` + config), not by inferring from
  schema shapes. *(Supersedes the earlier single `peegeeq_queue_registry` sketch.)*
- **Pre-4 — Credential handling (R/M).** PeeGeeQ stores **no password** — the registry holds connection
  coordinates + an opaque `credential_ref`; resolution is a pluggable `CredentialProvider` (core default =
  supplied-at-connect; adopters bring their own store). Open-source: no vault bundled or assumed. No
  encryption key to provision. Spec: setup-db §11.
- **Pre-5 — Correctness/safety bugs (prerequisites).** Defects found during the spike, each static-only
  and to be **runtime-reproduced before fixing** (see setup-db Appendix A, W-P):
  ✅ **destructive `create` on name collision (§13/W-G) — FIXED 2026-07-17:** `create` now *refuses* an
  existing database with a `DatabaseCreationConflictException` → REST **409**; it never drops. Dropping is
  a separate guarded operation (W-DD, §13.1: type-to-confirm `dropSetupDatabase` +
  `POST /setups/{id}/database/drop`), shipped and integration-tested.
  Still open: silent partial setup (`createQueueFactories` continues on a factory failure → setup ACTIVE
  with queues missing); `pg_notify` failure swallowed; no polling fallback in `LISTEN_NOTIFY_ONLY`.

---

## Prerequisite — Backend service control (reuse from peegeeq-management-ui)

Several steps in this plan can only be exercised against a **live PeeGeeQ REST backend**:
the runtime verifications in A.1 (delete-queue endpoint) and E.2 (overview payload), the manual
smoke test of the Phase B generator run, and every Playwright E2E step in Phase F. When the UI was
run standalone with no backend, every backend-dependent page correctly showed an error state — so
this control is a hard prerequisite for that work, not an optional extra.

**Do not build a new backend launcher.** A comprehensive, fully-tested backend service control
already exists in `peegeeq-management-ui` and is proven in that module's CI. It can be copied
into `peegeeq-utilities-ui` essentially verbatim — the only edits are the module name in paths and
the dev-server origin/port (utilities-ui runs on `3001`; see [vite.config.ts](../vite.config.ts)).

### What already exists in utilities-ui (do not re-copy)

utilities-ui already carries the **E2E-time** half of this control, itself derived from
management-ui: [src/tests/global-setup-testcontainers.ts](../src/tests/global-setup-testcontainers.ts)
starts a Testcontainers PostgreSQL, creates the `peegeeq` superuser, writes `testcontainers-db.json`,
**auto-starts the REST server** (`mvn exec:java -pl peegeeq-rest` with the DB system properties),
waits up to 120 s for `/health`, verifies CORS for `http://localhost:3001`, kills stale backends,
cleans up setups, and tears the backend down in
[global-teardown.ts](../src/tests/global-teardown.ts). This runs automatically inside Playwright and
needs no further work.

### What to copy from management-ui (the missing manual/dev half)

For **on-demand backend control outside a Playwright run** — which is what the A.1/E.2/Phase B
manual verifications need — copy these proven assets:

| Source (peegeeq-management-ui) | Destination (peegeeq-utilities-ui) | Purpose |
|---|---|---|
| `scripts/start-backend-with-testcontainers.ps1` / `.sh` | `scripts/` | Read `testcontainers-db.json`, set DB props, start `peegeeq-rest` via `mvn exec:java` |
| `scripts/stop-backend-env.ps1` / `.sh` | `scripts/` | Stop the backend/container environment |
| `scripts/create-test-setup.ps1`, `setup-test-data.ps1`, `cleanup-test-data.ps1`, `cleanup-test-setup.ps1`, `verify-api.js` | `scripts/` (optional) | Seed / verify / clean test data against the running backend |
| `src/components/common/ConnectionStatus.tsx` | `src/components/common/` (optional) | Live REST/WS/SSE reachability badge in the app header — makes backend state visible in-UI instead of only in the console/Overview alert |

Notes:
- The start script expects `testcontainers-db.json` to exist. It is produced by the existing
  utilities-ui global-setup, so the flow is: run the Playwright setup once (or the container-only
  path) to create the container + JSON, then `start-backend-with-testcontainers` to run the REST
  server against it for manual work.
- `ConnectionStatus.tsx` depends only on `configService.getBackendConfig` /
  `getVersionedApiUrl`, both of which exist in utilities-ui — so it drops in unchanged. It polls
  `health`, `ws/health`, `sse/health`; utilities-ui has no Settings page firing
  `peegeeq-config-changed`, but the component works fine without it (initial check + interval).
- These scripts use system properties / env vars to pass DB connection details to the backend
  **process**. That is the backend's own startup contract and is unrelated to the utilities-ui
  no-system-properties-for-config rule, which governs the app's own configuration — do not
  "fix" the scripts to remove them.

### Typical manual bring-up (for A.1 / E.2 / Phase B verification)

```powershell
# 1. Create the Testcontainers PostgreSQL + testcontainers-db.json (one-time per session)
cd peegeeq-utilities-ui
npx playwright test --config=playwright.screenshots.config.ts --grep "@setup"   # or any e2e run

# 2. Start the REST backend against that container (copied script)
./scripts/start-backend-with-testcontainers.ps1

# 3. Run the UI and verify against a real backend
npm run dev            # http://localhost:3001, proxies /api -> :8088

# 4. When done
./scripts/stop-backend-env.ps1
```

---

## Backend integration architecture — copy from peegeeq-management-ui (authoritative)

**Directive:** utilities-ui's REST integration must **precisely copy the architecture of
`peegeeq-management-ui`**, which has a sophisticated, fully-functional, fully-tested backend
integration. Do **not** invent or guess endpoints, request bodies, or response shapes. When a
utilities-ui service contradicts management-ui, management-ui wins.

The bespoke `src/services/*.ts` in utilities-ui were hand-written and some invented their own
contracts (see the verified create-queue mismatch below). The remediation is to replace them with
management-ui's proven layer, copied file-for-file with only path/origin edits:

| management-ui source | Role to replicate in utilities-ui |
|---|---|
| `src/api/endpoints.ts` | Route constants that match `peegeeq-rest/PeeGeeQRestServer.java` — the single source of every path |
| `src/api/PeeGeeQClient.ts` | Typed fetch client: timeout, exponential-backoff retry (5xx only), `PeeGeeQApiError`/`PeeGeeQNetworkError`, 204 handling |
| `src/api/types.ts` | Request/response DTOs matching the backend |
| `src/store/api/*.ts` (RTK Query) | `dynamicBaseQuery` + `transformResponse` (maps backend field names → UI shape) + response validation |

### Verified backend contracts (probed against the live backend)

| Operation | Method + path | Body / notes |
|---|---|---|
| Create queue | `POST /api/v1/management/queues` | `{ setup, name, type, ...config }` → **201**; response echoes `implementationType`. This is management-ui's `useCreateQueueMutation` contract. |
| Delete queue | `DELETE /api/v1/management/queues/{setupId}/{queueName}` | → **200**. utilities-ui's `deleteQueue` matched this contract. *(Removed 2026-07-21, user decision: the queue list here is read-only; this UI no longer calls the endpoint. The endpoint itself is unchanged and management-ui owns it.)* |
| List queues | `GET /api/v1/setups/{setupId}/queues` | Returns `count`, `queues[]`, and `queueDetails:[{name,implementationType}]`. |
| Create setup | `POST /api/v1/database-setup/create` | Verified working end-to-end from the UI (→ 201, provisions a DB). |
| Queue name rule | — | Must match `[A-Za-z_][A-Za-z0-9_]*` — **no hyphens** (backend returns 400). |

> The earlier "delete-queue endpoint mismatch" (TD §12.3) is now **resolved by verification**:
> utilities-ui's code path is correct; the design doc §16 path (`/setups/{id}/queues/{name}`) is the
> one that is wrong (404). Fix the doc, not the code.
> *(Moot since 2026-07-21 — utilities-ui calls no delete-queue endpoint at all.)*

---

## Phase A — Divergence fixes and hardening — ✅ DONE 2026-07-10

**Goal (met):** close the small, high-confidence gaps in TD §12 before building new pages. All
four items are recorded as RESOLVED in TD §12 (items 3–6).

| Step | File | Outcome | Reference |
|---|---|---|---|
| A.1 | docs only | ✅ done — feature §16's delete-queue path corrected in place (verified note at the §16 endpoint table: `DELETE /management/queues/{setupId}/{queueName}` is correct; the old `/setups/...` path 404s). The createQueue contract mismatch became **moot** when S.6 removed `createQueue` entirely. | TD §12.3; S.6 |
| A.2 | [TargetSelector.tsx](../src/components/TargetSelector.tsx) | ✅ done — Queue dropdown loads via `listQueueDetails`; implementation type shown as **plain text** in the option label (`orders (native)`) per the recorded **no-badges decision** (TD §12.4) — the original badge idea was dropped deliberately. | feature §6.1; TD §12.4 |
| A.3 | [TargetSelector.tsx](../src/components/TargetSelector.tsx) | ✅ done — queue-fetch failure surfaces as an error `Alert` with Retry (`data-testid="queue-load-error"`), distinct from the no-queues empty state; covered by unit tests. | TD §12.6 |
| A.4 | [generatorStore.ts](../src/stores/generatorStore.ts) | ✅ done — `currentRate` is a true rolling 1-second window in the store (per-run samples in the store closure, cumulative fallback on first tick); the engine computes no rate of its own (`buildSummary.avgRate` is cumulative **by design** — it is the summary average). | TD §12.5 |

**Acceptance (met):** delete-queue verified against the live backend *(the delete feature was
later removed — 2026-07-21, user decision)*; the Queue dropdown shows each queue's type; a
queue-load failure is visible with retry; `currentRate` semantics match the docs (rolling
window, documented in types + TD §12.5).

---

## Phase B — Generator page UI (Zones B–E)

**Goal:** assemble the full generator so a user can configure a run, preview a message, start and
stop it, and watch live progress. This is the phase that finally **wired the engine into the
UI** (closing what TD §7 recorded as "Not yet wired" — since resolved there). Corresponds to
feature §18 Phase 3.

*Prerequisite: **Phase S** (decided). Setup provisioning belongs to the **admin tool**, not the
generator — the generator only *targets* setups. Connecting to an existing setup
(`connectToExistingSetup`) is therefore the generator's only path to a target, so S must land
before B for Zone A's target list to be real.*

Build in the order below (each is its own component under `src/pages/generator/`), then assemble.

| Step | File | Zone / responsibility | Reference |
|---|---|---|---|
| B.0 | ✅ **DONE 2026-07-18** — [publicationEngine.ts](../src/engine/publicationEngine.ts) | Engine upgraded to §7.1 as respecified: 1 s ticks (first fan-out fires immediately — §7.1 note; without it a duration-N run sends only N−1 quotas), full per-second quota split into ≤ `maxBatchSize` groups fired concurrently (`Promise.allSettled`), per-batch consec-error processing in batch order, whole-fan-out in-flight guard, stop-during-fan-out race guarded; `start(config, identity, callbacks)` — engine generates none of the ids. TDD red→green, 11 engine tests. | feature §7.1, §13 |
| B.1 | ✅ **DONE 2026-07-18** — `src/pages/generator/RateControls.tsx` | Zone B — rate, duration, max batch size, warn threshold, auto-stop; live "Total = rate × duration"; non-blocking rate-warning `Alert`. 15 unit tests, no mocks. | feature §6.1 Zone B |
| B.2 | ✅ **DONE 2026-07-18** — `src/pages/generator/TemplateEditor.tsx` | Zone C — working-copy editor (value/onChange, page owns state): template `Select` backed by the real templateStore, New/Save/Export ("Edit" dropped — always editable under the working-copy contract), payload validated on blur by resolve-then-parse (§8 semantics), name/type/priority/delay/group fields, headers add/remove (cap 20), §5.3 placeholder reference `Collapse`, dirty-switch confirm. 15 unit tests, real store + localStorage, no service mocks. | feature §6.1 Zone C, §5 |
| B.3 | ✅ **DONE 2026-07-18** — `src/pages/generator/GeneratorActions.tsx` | Zone D — preview index input, **Preview** (resolve at index with display-only fresh identity + modal, no HTTP, missing-lists warning banner, inline error on parse failure), **Start** (idle + target only; missing-lists Proceed/Cancel pre-flight per §5.5), **Stop** (running only). 13 unit tests, real resolver + real valueListStore, no mocks. **Header-resolution finding: FIXED 2026-07-18 (user decision — fix the engine):** `resolveString` extracted in the resolver; the engine resolves header values per message; Preview shows resolved headers; the missing-list scan covers payload + header values. 7 new tests across resolver/engine/Zone D. | feature §6.1 Zone D, §5.5, §8 |
| B.4 | ✅ **DONE 2026-07-18** — `src/pages/generator/ProgressPanel.tsx` | Zone E — store-driven (reads generatorStore directly): progress bar, Sent/Elapsed/Rate/Errors counters (Elapsed on the panel's own 500 ms interval from `startedAt`), 20-most-recent error list (hidden at zero), ERROR status shows `autoStopReason`, terminal summary card replaces the bar and renders from the stored `RunSummary` alone, **Download results** + **New run** (clears summary + `resetRun`). Store gained `summary`/`setSummary` (cleared on start/reset). 8 panel tests + 3 store tests, real store, no mocks. | feature §6.1 Zone E, §7.2, §11 |
| B.5 | ✅ **DONE 2026-07-18** — `src/pages/generator/MessageGeneratorPage.tsx` | Zones A–E assembled. Page owns working template / rate settings / preview index / target; Start builds `RunConfig` → `setConfig` + `startRun()` (generates runId) → `engine.start(config, {runId, correlationId}, callbacks)`; onTick → `tickUpdate`; terminal callbacks → `transitionTo` + `setSummary`, engine discarded; Stop delegates to `engine.stop()` (no double-write); unmount stops a live run; Zones B/C disabled while running. 5 page unit tests (zone assembly + state wiring; run flow deferred to the e2e — needs a real backend). | feature §6.1; §7, §13; TD §7 |
| B.6 | ✅ **DONE 2026-07-18** — [App.tsx](../src/App.tsx) | Stub replaced with the real page (stub + its now-unused imports removed). New e2e project `4-generator-run` (own throwaway setup+queue): Zone B total + threshold advisory (the B.1 e2e obligation), template editing, Preview, full run with real publishing (server-acknowledged counters → COMPLETED summary → New run), and Stop → STOPPED. Fallout fix: TargetSelector now keeps the **setup dropdown visible in the no-queues and queue-error states** — previously a queue-less first setup stranded the user with no way to switch setups (found because a concurrent spec's queue-less setup was auto-selected). Full e2e suite 60/60; `4-generator-run` de-flaked at `--repeat-each=2 --retries=0`. | — |

**Design decisions locked in before B.2 (coherence review 2026-07-18 — see design §7.1, §7.2,
§6.1 Zone C/E, §11, §13 for the full statements):**

1. **Run identity (B.0):** the **store owns `runId`** — `startRun()` generates it; the page passes the
   store's `runId`/`correlationId` into `engine.start(config, identity, callbacks)`. The engine's
   own UUID generation is removed in B.0 (it currently generates two identities for one run).
2. **Concurrent fan-out in v1 (DECIDED 2026-07-18, user call — implemented as step B.0):**
   design §7.1 respecified to 1-second ticks carrying the full per-second quota, split into
   `ceil(rate / maxBatchSize)` batches fired concurrently via `Promise.allSettled`; per-batch
   consecutive-error counting in batch order; ceiling ≈ `6 × maxBatchSize / avgLatency` (browser
   connection limit). `sent` counts server-**acknowledged** messages. (The original §7.1 sketch
   was internally inconsistent — its group-split branch was unreachable; §7.1 now records the
   coherent form.)
3. **Summary home (B.4/B.5):** `generatorStore` gains `summary: RunSummary | null` +
   `setSummary` (set by terminal callbacks, cleared on start/reset); the Zone E summary card and
   Download render from the summary alone.
4. **No dead-end (B.4):** the summary card carries a **New run** button (clears summary +
   `resetRun()`) — the only exit from terminal states; Start stays idle-only.
5. **Elapsed cadence (B.4):** ProgressPanel runs its own 500 ms interval deriving Elapsed from
   `startedAt`; sent/rate/errors update at engine tick cadence.
6. **Zone C working copy (B.2):** Preview and Start use the editor's current working copy, saved
   or not; Save is pure persistence; switching templates confirms before discarding edits.

**E2E obligation (B.5/B.6):** the generator Playwright spec must exercise Zone B through the
run flow (set rate/duration → total updates; cross the warn threshold → advisory appears) —
RateControls has unit coverage only until the page is assembled.

**Acceptance:** with a live backend, a user can select target → set rate/duration → edit/select a
template → Preview (valid JSON in a modal, missing-list warning shown) → Start (Zone E counters
climb, progress bar advances) → Stop or let it complete → summary card + download. Auto-stop
triggers `error` state after N consecutive failures.

**Verification:** banned-pattern grep → `npm run build` → Vitest for the engine wiring and any
component tests → Playwright: start a short run, observe counters increment, stop.

---

## Phase C — Template Manager page

**Goal:** full CRUD over templates (feature §6.2, §18 Phase 4). Parallelisable with Phase B.

| Step | File | What | Reference |
|---|---|---|---|
| C.1 | ✅ **DONE 2026-07-18** — `src/pages/templates/TemplateManagerPage.tsx` | `Table` over the real templateStore: Name link + Edit action → `select(id)` + navigate `/generator` (MessageGeneratorPage now consumes `selected` as its initial working copy, cleared after mount); Message Type; Description truncated at 80 + tooltip; relative Updated (dayjs); Duplicate / Delete (Popconfirm) / Export per row; toolbar New Template + Import (per-entry Zod validation via `importFromFile`, duplicate IDs skipped with a **named** warning, invalid entries surface named errors). 11 unit tests + 1 generator-handoff test, real store/localStorage/FileReader, no mocks. | feature §6.2; TD §8 |
| C.2 | ✅ **DONE 2026-07-18** — [App.tsx](../src/App.tsx) | Stub replaced. e2e: "Coming soon" test replaced with real assertions (table + toolbar; New Template → blank editor; save-in-generator → listed in manager → reopens via Name link, full localStorage round-trip). Full e2e suite 62/62. | — |

Reuse [templateStore](../src/stores/templateStore.ts) and
[templateService](../src/services/templateService.ts) as-is (already built).

**Acceptance:** create, edit, duplicate, delete, export, and import templates; imports with an
existing ID are skipped with a visible warning; localStorage round-trips.

**Verification:** banned-pattern grep → `npm run build` → Vitest for `templateStore` /
`templateService` (existing) plus component test → Playwright template CRUD path.

---

## Phase D — Value List Manager page

**Goal:** manage the named value lists behind `{{list:name}}` (feature §6.3, §18 Phase 5).
Parallelisable with Phases B and C.

| Step | File | What | Reference |
|---|---|---|---|
| D.1 | ✅ **DONE 2026-07-19** — `src/pages/value-lists/ValueListManagerPage.tsx` | `Table` (Name, preview, count, Edit/Export/Delete) + edit panel (rename = remove-old+add-new with collision rejection; one-value-per-line, trimmed, blanks dropped; live count; Save/Cancel); New List (empty-name rejected); Import via `importFromFile` with Overwrite/Merge/Cancel collision modal (merge de-dupes), named errors, coercion warning; Delete confirm names referencing templates (payload AND header values). 13 unit tests, real stores/localStorage/FileReader, no mocks. | feature §6.3; TD §8 |
| D.2 | ✅ **DONE 2026-07-19** — [App.tsx](../src/App.tsx) | Last stub replaced. e2e: "Coming soon" test replaced with real assertions incl. a cross-page round-trip (create list → generator preview resolves it → delete). **The e2e caught a real bug:** on a fresh page load the generator never called `valueListStore.loadFromStorage()`, so every `{{list:...}}` resolved to `""` in previews and runs — fixed in MessageGeneratorPage (unit regression test added). Full e2e 63/63, unit 217/217. | — |

Reuse [valueListStore](../src/stores/valueListStore.ts) (`importList` already implements
overwrite/merge) and [valueListService](../src/services/valueListService.ts).

**Acceptance:** create/edit/rename/delete/import/export lists; merge de-duplicates; deleting a
referenced list warns; the resolver's `snapshot()` reflects the current lists at run start.

**Verification:** banned-pattern grep → `npm run build` → Vitest for `valueListStore` /
`valueListService` (existing) plus component test → Playwright value-list path.

---

## Phase E — Overview redesign — ✅ DONE

**Goal (met):** [Overview.tsx](../src/pages/Overview.tsx) is in line with feature §6.6 — a per-setup
table with a per-setup detail card (queues + event stores), and **no global/system-wide aggregates**
(TD §12.2). Post-S.6 the header CTA and empty state point at **Connect setup** (provisioning is
admin-tool-only), not create.

| Step | File | What | Reference |
|---|---|---|---|
| E.1 | [Overview.tsx](../src/pages/Overview.tsx) | ✅ done — global Statistic cards/system totals removed; per-setup table + selected-setup detail card; empty-state and header CTA navigate to `/setups/connect`. | feature §6.6 |
| E.2 | [utilitiesStore.ts](../src/stores/utilitiesStore.ts) | ✅ done — per-setup/per-queue data shape, no `systemStats` global aggregates. | feature §6.6; TD §5 |

**Note on charts:** per the recorded recharts constraint, keep charts **non-stacked** (the current
`AreaChart`/`LineChart` are single-series and safe). Do not introduce a stacked `stackId`.

**Acceptance (met):** Overview shows no cross-setup aggregates; every metric is per-setup or
per-queue; empty state shows the connect-setup CTA. Covered by the converted real-backend
overview e2e spec.

**Verification:** banned-pattern grep → `npm run build` → Vitest for any Overview test →
Playwright overview render with and without setups.

---

## Phase F — Integration, E2E, and screenshots

**Goal:** lock in the above with tests and refreshed docs (feature §18 Phase 6).

*Prerequisite: the backend service control above. E2E already auto-starts the backend via the
existing global-setup; no extra work is needed for the Playwright steps themselves.*

| Step | What | Reference |
|---|---|---|
| F.1 | ✅ **DONE** (delivered through B/C/D TDD) — unit suite 217 tests: resolver incl. `resolveString`/header resolution, engine incl. fan-out/identity/run-time-failure/stop-settle, stores incl. `summary`, all five zone components, both manager pages. | feature §18 6.1–6.3 |
| F.2 | ✅ **DONE** — `4-generator-run` e2e: full run (start → acknowledged counters → COMPLETED summary → New run) + stop flow, real backend. Download is unit-tested (blob content); browser download event not e2e-asserted. | feature §18 6.5 |
| F.3 | ✅ **DONE** — e2e: template save → manager list → reopen round-trip; value-list create → generator resolve → delete round-trip. Duplicate/delete/import paths covered at unit level with real localStorage. | feature §6.2, §6.3 |
| F.4 | ✅ **DONE 2026-07-19** — screenshots spec rewritten for the connect-only, fully-built UI (old spec drove the removed Create Setup/Queue pages); 12 fresh PNGs incl. the assembled generator, preview modal, real-run summary, detach/connect flows; Appendix A rewritten; 15 stale PNGs removed. *Extended same day to 16 shots (13–16: schedule modal, Scheduled Runs tabs) with the scheduled-runs feature — Appendix A §A.3.* | feature Appendix A |
| F.5 | Full module gate (final check, **user's call**): `mvn test -pl :peegeeq-utilities-ui -Pall-tests`. | reference test commands |

**Acceptance:** targeted suites green; screenshots reflect the built pages; the module gate passes.

---

## Phase G — Generation tool suite (post-core)

**Goal:** the additional generation-side tools defined in design §19 — all built on the
`publicationEngine` from Phase B, none duplicating management-ui.

*Prerequisite: Phase B. Telemetry-heavy steps additionally require **Phase T** (below).* The
telemetry each tool needs, and which side measures it, is specified in
[PEEGEEQ_ADMIN_DEVOPS_TELEMETRY_REQUIREMENTS.md](PEEGEEQ_ADMIN_DEVOPS_TELEMETRY_REQUIREMENTS.md); the "Telemetry" column
tracks that dependency.

| Step | Tool | Telemetry | Reference |
|---|---|---|---|
| G.1a | ✅ **DONE 2026-08-02** — Ramp load test, basic knee (client-detected): planning + knee detection, Ramp mode UI, e2e | **Client-only** — accept rate/latency + `/stats` `pendingMessages` | design §19.1; telemetry §6 |
| G.1b | Ramp — rich saturation *attribution* | **Needs Phase T:** G3 (resource saturation) + G4 (≥1 Hz stream) + G7 (DB bottleneck signals) | telemetry §4A, §7 |
| G.2 | Native-vs-Outbox comparison run | **Needs Phase T:** G1 (percentiles) + G2 (delivery latency) + G6 (correlation join) + G7 (DB churn profile) | telemetry §7 |
| G.3 | ✅ **DONE 2026-08-02** — Traffic-profile / scenario runner (G.3a sequencer · G.3b phases editor · G.3c mode selector + results panel · G.3d profile scenarios + e2e) | **Client-only** — achieved-rate timeline (finer with G4) | design §19.3; telemetry §6 |
| G.4 | ✅ **DONE 2026-08-01** — saved scenarios (localStorage, templateService-shaped); see the G.4 record below | **None** | design §19.4 |
| G.5 | Delay / Priority / FIFO exerciser | **Client-only** to send; *auto-verify* needs G6 (else defer to management-ui browser) | design §19.5; telemetry §6 |
| G.6 | Correlation / trace seed generator | **None** — emits ids; verify in management-ui | design §19.6 |

Surface as **modes of the Message Generator** (Flat rate · Ramp · Compare · Profile), or repurpose
the dead `/tools` route as the suite launcher.

**Build order within Phase G:** ship the client-only tools first (G.1a, G.3, G.4, G.5-send, G.6) —
they need no backend change. G.1b and G.2 land only after Phase T delivers their telemetry.

### G.1a (part 1) — ramp planning and knee detection, 2026-08-02

**A ramp is a profile whose phases are computed, plus a stop rule.** Building Profile mode first
paid off exactly here: the sequencing, per-phase results and Stop semantics are reused unchanged.
Only two things were new.

**1. An early-halt hook on the sequencer.** `profileRunner` gained
`shouldHaltAfterPhase(result, results) → reason | null` and a matching `onProfileHalted`. Halting at
the knee is a **normal, successful** outcome — the ramp found what it was looking for — so it is
neither a completion nor an error, and it gets its own terminal signal. The hook receives EVERY
result so far, because a plateau check needs the previous step. It runs on completed phases only: a
stopped or errored phase never measured the rate it was asked to.

**2. [engine/rampPlan.ts](../src/engine/rampPlan.ts) — the pure decisions**, so sequencing stays in
`profileRunner`:

- `buildRampPhases` — steps ascend from `startRate` by `stepRate`. A step overshooting `maxRate` is
  **clamped to the cap, not dropped**: the cap is a rate the operator asked to reach. A start above
  the cap yields **no** steps rather than one silent step. An uncapped ramp is still bounded
  (`MAX_STEPS`), because a stop rule that never trips must not produce an unbounded plan.
- `rampHaltReason` — the error-rate rule measures errors against what that step **requested**; the
  plateau rule halts when achieved throughput fails to rise despite a higher request.
- `sustainedRate` — the highest step that delivered ≥99% of its request, or **null when none did**.
  Null is deliberate: reporting the lowest attempted rate as the breaking point when even that step
  could not keep up would fabricate a finding.

`RampSettings` stores the controls only; the steps and the knee are derived, so the plan can never
disagree with the settings that produced it.

**TDD.** 3 sequencer tests + 14 plan tests, written before the code. Probes, each verified applied
then reverted: fabricated knee when nothing sustained → 1 red; overshoot dropped instead of clamped
→ 1 red. **A third probe found redundant code:** removing the `index === 0` plateau guard changed
nothing, because the `!previousPhase` check below already covers the first step. The redundant guard
was deleted and the remaining one re-probed to confirm it is load-bearing (1 red). Unit suite:
**40 files, 625 tests**; lint 0 errors; `tsc --noEmit` 0 errors.

### G.1a (part 2) — Ramp mode UI, 2026-08-02 — **G.1a COMPLETE**

[RampControls](../src/pages/generator/RampControls.tsx) is Zone B for Ramp mode. Its plan preview
calls the **same `buildRampPhases` the run uses** — a preview computed a second way could describe a
ramp that never happens. The error-rate threshold is **disabled under the plateau rule**, so a
number with no effect cannot look like it has one. Clearing "Max rate" is meaningful (it means *no
cap*), so unlike the other numeric fields a null is stored rather than ignored.

Ramp is the third mode on the generator. Its steps are `useMemo`-derived from the settings and never
stored, and the results table is the **profile panel reused** — a ramp's steps ARE phases. The knee
readout states the outcome plainly: the halt reason plus the max sustained rate, or *"No step
sustained its requested rate"* when none did.

**Two refusals a ramp carries**, both surfaced with reasons rather than dead buttons: Schedule is
blocked (a `ScheduledRun` holds one `RunConfig`), and **a ramp cannot be saved as a scenario** —
`Scenario` has no ramp kind, so saving would store it as a *flat* scenario that replays as a
completely different run. `handleSave` checks the mode directly, which also narrows the type so the
object cannot be built with a mode the model does not support.

**A page-wiring bug the e2e caught.** `handleStart` routed to the sequencer only when
`mode === 'profile'`, so **Ramp mode silently fell through to a single flat run**. No unit test could
see it: without a backend there is no target, so Start can never be clicked. Fixed to `mode !== 'flat'`
— every non-flat mode is a sequence driven by the same runner.

**A second regression, caught by the unit suite:** `applyScenario` set `mode` from a scenario that
predates the field, leaving it `undefined` and rendering **no Zone B at all**. The page now defaults
to flat exactly as the storage schema does, and the stale test data was corrected.

**E2E — new project `11-ramp`** ([ramp.spec.ts](../src/tests/e2e/specs/ramp.spec.ts)): planned steps
listed as *pending* before anything runs (with the preview and the table agreeing); a real climb
publishing 60 acknowledged messages across three steps with the knee reported; an empty ramp
(start above cap) surfaced and unstartable. Both refusals are asserted **with a target selected**,
which the unit tests cannot do.

Probes, each verified applied then reverted: threshold no longer disabled under plateau → 1 red;
preview computed independently of the real builder → 3 red.

**Verified 2026-08-02:** unit **41 files, 638 tests**; e2e **82/82 across 12 projects**; lint 0
errors (4 warnings); `tsc --noEmit` 0 errors.

### G.3a — traffic-profile sequencer, 2026-08-02

**Purpose.** Profile mode reproduces a realistic traffic shape — burst → steady → spike → idle —
against one target, and reports what each phase actually achieved against what it asked for.

**Data model.** `ProfilePhase { id, label, rate, durationSecs }` and
`ProfilePhaseResult { phaseId, label, sent, errors, status, durationMs }`
([types/profile.ts](../src/types/profile.ts)). `sent` is the server-acknowledged count from the
phase's `RunSummary`. The **requested** total (`rate × durationSecs`) is deliberately NOT a field on
the result: it is derivable from the phase, and storing it beside the achieved figure is exactly
where the two drift apart.

**Load-bearing finding — idle cannot be a run.** `publicationEngine.tick()` floors the per-second
quota at `Math.max(1, …)` and `runConfigSchema` requires `rate ≥ 1`, so a phase with `rate: 0`
driven through a run would **publish traffic the profile says should not exist**. The sequencer
([engine/profileRunner.ts](../src/engine/profileRunner.ts)) waits out an idle phase instead and
starts no run at all. Three tests pin this.

**Decisions.** A phase ending in ERROR **aborts** the profile (continuing would report an achieved
shape that never happened, measured against a backend already known to be failing). `stop()` stops
the active phase and starts no later phase; during an idle phase it cancels the wait. An empty
profile is refused rather than "completing" vacuously. The engine, run identity and store wiring are
untouched — the runner drives the existing `startGeneratorRun` once per phase and owns only
sequencing and aggregation.

**No mocking:** `startRun` and the timer are injected, so the tests drive real fakes instead of
`vi.mock`. This is the first module here to take that seam; it exists specifically to avoid a mock.

**TDD (strict, this time).** The 12-test contract was written and run BEFORE the runner existed —
red at import resolution. Because import-level red exercises no assertion, three mutation probes
followed, each reverted: idle delegated to a run → 3 tests red; error no longer aborting → 1 red;
the phase-sequencing boundary broken → 3 red. `tsc` then caught a dead `phase` parameter in
`record()`, removed. Module unit suite after G.3a: **37 files, 569 tests, all passing**; lint 0
errors; `tsc --noEmit` 0 errors.

### G.3b — phases editor, 2026-08-02

[pages/generator/ProfilePhasesEditor.tsx](../src/pages/generator/ProfilePhasesEditor.tsx) — Zone B
for Profile mode. Presentational and fully controlled exactly like `RateControls`: one row per phase
(label, rate, duration, remove), "Add phase", and the derived `Total messages = Σ(rate × duration)`
and `Profile duration` readouts. Both totals are computed on render and never stored, so an achieved
figure can never sit beside a stale requested one.

A rate-0 row carries an explicit **idle** tag: 0 is a deliberate shape, not an unset field, and
without the tag it reads as a mistake. Its duration counts toward the profile length while
contributing no messages. An empty list surfaces a non-blocking advisory rather than leaving an
unrunnable profile looking normal.

**TDD.** 10-test contract written and run before the component — red at import. Three mutation
probes, each reverted: ids no longer fresh → 2 red; idle counted as traffic in the derived total →
1 red; an edit mutating the caller's array in place → 1 red. Module unit suite: **38 files, 579
tests, all passing**; lint 0 errors; `tsc --noEmit` 0 errors.

*Note:* this file adds a third `react-refresh/only-export-components` **warning** by exporting
`makeDefaultPhase` beside the component — the identical shape `RateControls` (`RATE_DEFAULTS`) and
`TemplateEditor` (`blankTemplate`) already have. Consistent with the established Zone pattern;
warnings do not fail the gate.

### G.3c — mode selector, page wiring, results panel, 2026-08-02

Mode selector on the generator (**Flat rate · Profile**) — only the two BUILT modes are offered. A
greyed-out "Ramp"/"Compare" would promise behaviour the app does not have. Profile mode swaps Zone B
for the phases editor and adds
[ProfileResultsPanel](../src/pages/generator/ProfileResultsPanel.tsx): per phase the **requested**
(derived `rate × durationSecs`), the acknowledged **sent**, errors, status, and a `short by N` tag
when a phase under-delivered. A phase that has not run reports **PENDING**, never "0 sent" — "did
not run" and "ran and delivered nothing" are different facts.

**Schedule is BLOCKED in Profile mode.** A `ScheduledRun` stores one `RunConfig`, so scheduling a
profile would silently schedule only the base rate and duration. Zone D gained
`startBlockedReason` / `scheduleBlockedReason` — carried as REASONS, not booleans, so the disabled
button explains itself in a tooltip instead of refusing silently. Stop routes to the SEQUENCER when
a profile is live; stopping only the live phase would let the next phase start immediately.

**A vacuous test, caught by probing.** Two page-level tests asserted Start/Schedule were disabled in
Profile mode. `MessageGeneratorPage.test.tsx` has no backend, so `targetSelected` is false and both
buttons are disabled ANYWAY — a probe that removed the Schedule rule entirely left all 15 tests
green. The rules moved to `GeneratorActions.test.tsx`, which sets `targetSelected: true` and
therefore tests the blocked reason itself; re-probing there failed correctly. The page test now
asserts only what is observable without a target (the mode swap, the results panel, the empty
advisory). The end-to-end wiring with a real target is G.3d's e2e.

Probes (each applied, verified in the file, then reverted): schedule reason ignored → 1 red;
pending rendered as `0` sent → 1 red. `tsc` then rejected `onRow`'s `data-testid` (antd types it as
`HTMLAttributes`, which does not admit `data-*` in an object position) — fixed with a typed cast,
not a behaviour change. Module unit suite: **39 files, 596 tests, all passing**; lint 0 errors;
`tsc --noEmit` 0 errors.

### G.3d — profile scenarios + e2e, 2026-08-02 — **G.3 COMPLETE**

`Scenario` gained `mode: 'flat' | 'profile'` and an optional `phases` — the fields G.4 deliberately
withheld until a producer existed.

**Back-compat is the load-bearing decision.** A discriminated union on `mode` was the cleaner model,
and it was rejected: scenarios saved by G.4 carry **no** `mode`, and a strict union would have
DROPPED the user's saved data on load (per-entry validation discards invalid entries). `mode` is
therefore `z.enum(...).default('flat')` — legacy scenarios load as flat — with a `superRefine`
rejecting a profile scenario that has no phases, since the runner refuses an empty profile and
storing one would be a scenario that silently does nothing. `ScenarioBar` refuses to save that case
at the point of saving, too.

The Tools table gained a **Mode** column, and a profile row is described **by its phases**
(`3 phases · 85 s · 11000 messages`), never by the base config's flat rate — that rate is never used
when a profile runs, so showing it would describe a run that never happens.

**TDD.** Each surface written test-first with behavioural (not import-level) red: service 5 red →
green, ScenarioBar 3 red → green, page 1 red → green, Tools 2 red → green. Probes, each verified
applied in the file then reverted: back-compat default removed → 1 red; empty-profile guard removed
→ 1 red; phases dropped on save → 1 red.

**E2E — new project `10-profile`** ([profile.spec.ts](../src/tests/e2e/specs/profile.spec.ts)), the
only automated coverage of the PAGE driving the sequencer against a live backend:

| Case | What it proves |
|---|---|
| two-phase real run | mode switch → phases editor → real publishing → per-phase achieved-vs-requested totals (30 requested / 30 acknowledged), no shortfall. **Also asserts Schedule is disabled WITH a target selected** — the thing the unit tests could not prove |
| save + reload | a profile saves as a scenario, the Tools row reads "Profile · 2 phases", and Load restores mode, both phases and the idle badge |
| stop mid-profile | the stopped phase settles and the next phase stays **pending** — it did not run, and does not report zero sent |

Full suite **79/79 passed across 11 projects** (2026-08-02). Unit **39 files, 608 tests**; lint 0
errors; `tsc --noEmit` 0 errors.

*One e2e defect found and fixed while writing it:* `getByLabel('Profile')` matched two elements and
failed only in the first of the three tests — ambiguity that presents as flakiness. Replaced with a
locator scoped to `generator-mode`.

### G.4 — saved scenarios, 2026-08-01

**Data model.** `Scenario { id, name, config: RunConfig, createdAt, updatedAt }`
([types/scenario.ts](../src/types/scenario.ts)), persisted under `peegeeq_scenarios`. `config` is the
full snapshot at save time — the same working-copy contract a schedule uses. Everything else the
§19.4 table shows is **derived at render time and deliberately not stored**: the target string, the
`rate × duration` total, and the template name all come from `config`.

**Deliberate omission — no `mode`/`phases` field yet.** §19.4 says a scenario is "a RunConfig
(+ profile for §19.3)". Nothing produces a profile until G.3 builds Profile mode, and a stored field
with no producer cannot be trusted, so the field lands with G.3 and the §19.4 Mode column is absent
until then.

**Recorded decision — the duplicate saved-RunConfig store (user's call, 2026-08-01).**
`ScheduleTemplate` (`peegeeq_schedule_templates`, [types/schedule.ts](../src/types/schedule.ts) §R13,
Scheduled Runs → Templates tab) is already "a reusable run configuration": the same
`{ id, name, config: RunConfig, timestamps }` shape this step adds. Generalising it into `Scenario`
was offered as the alternative. **The user chose to build G.4 exactly as this plan specifies**, so
the module now has two parallel stores of a saved `RunConfig`. This is a known, accepted duplication,
not an oversight — record it here so a later reader does not "fix" one of them in isolation.

| Step | What |
|---|---|
| G.4a | [types/scenario.ts](../src/types/scenario.ts), [services/scenarioService.ts](../src/services/scenarioService.ts), [stores/scenarioStore.ts](../src/stores/scenarioStore.ts) — load with **per-entry** Zod validation (one corrupt entry is dropped and named, never a blanked list), save through `persistJson`, export one/all, import with one named error per rejected entry, duplicate ids skipped without overwrite. `runConfigSchema` and `loadValidated` are now **exported from scheduleService** and `triggerDownload` from templateService rather than re-implemented. |
| G.4b | [pages/tools/ToolsPage.tsx](../src/pages/tools/ToolsPage.tsx) — the dead `/tools` route (it rendered a **second copy of Overview**) becomes the generation-tool suite launcher; its first panel is the scenario table (Name · Target · Run · Updated · Load/Export/Delete) plus Import and an empty state pointing at the generator. Ramp/Profile/etc. join this page as they are built. |
| G.4c | [components/ScenarioBar.tsx](../src/components/ScenarioBar.tsx) on the generator — scenario select, Load, "Save as…" (named, blank name refused inline), Export. **Import is on the Tools page only**, not on the bar as the §19.4 mockup draws it: templates, value lists and schedules each have exactly one import code path, and mirroring that beats duplicating the dialog wiring. |
| G.4d | [components/TargetSelector.tsx](../src/components/TargetSelector.tsx) gained `initialTarget` — without it a loaded scenario could not restore its own target, because the selector always auto-selected the first setup + first queue. Consumed once per mount (the generator remounts it with a new `key` per Load) so it never fights a later manual change. **A requested target that no longer exists is NOT silently replaced:** the substitution is named in a `target-unavailable` warning, because silently retargeting means publishing load at a queue the user never chose. |
| G.4e | Handoff: Tools "Load" selects the scenario and navigates to `/generator`, which consumes the selection into rate settings, working template, preview index and target — the same shape as the Template Manager handoff. The template is deep-copied on apply, so editing the working copy cannot mutate the stored scenario. |

**Tests.** `scenarioService.test.ts`, `scenarioStore.test.ts`, `ScenarioBar.test.tsx`,
`ToolsPage.test.tsx`, plus scenario-handoff and no-mutation cases in `MessageGeneratorPage.test.tsx`
and five `initialTarget` cases in `TargetSelector.test.tsx`. All use the real stores, real
localStorage and the real FileReader import path; only `URL.createObjectURL` / anchor click are
stubbed (jsdom implements neither).

**Process defect, and how the evidence was repaired.** This step was built implementation-first and
the tests were written afterwards — a departure from the red→green discipline every other step in
this plan records, and it was not declared at the time. The tests were therefore never observed
failing, so nothing established that they *could* fail. Repaired by reconstructing both directions:

- **Red (implementation removed, tests kept):** 6 files failed. The five `initialTarget` cases failed
  on assertions against the original `TargetSelector` (`expected "spy" to be called with
  [ 'setup-b', 'events' ]`); the four new files failed at import resolution. The 23 pre-existing
  `TargetSelector` cases still passed, confirming the new behaviour was additive.
- **Mutation probes**, because import-time red proves nothing about assertions that never executed.
  Each load-bearing behaviour was broken one at a time and the matching test confirmed red, then
  reverted: per-entry validation bypassed → 3 service cases; duplicate-id skipping removed → 2 store
  cases + 1 ToolsPage case; blank-name refusal removed → 1 bar case; Tools handoff not consumed → 1
  generator case; Load navigating without selecting → 1 ToolsPage case.
- **Green:** the 6 files pass (75 cases). Full module unit suite: **36 files, 557 tests, all passing**
  (2026-08-01). `tsc --noEmit` 0 errors; ESLint clean on every changed file.

**E2E — new project `9-scenarios` ([scenarios.spec.ts](../src/tests/e2e/specs/scenarios.spec.ts)),
3 tests, own throwaway setups.** Full suite 2026-08-02 (user-authorised), real backend +
TestContainers: **76/76 passed in 4.9 min across all 10 projects.**

| Case | What it proves |
|---|---|
| round trip | generator → Save as… → Tools row with both DERIVED columns → **real export download** (file contents asserted) → Delete → **re-import of that exported file** → Load → generator repopulated, **target included**, Start armed |
| scenario bar | Load restores drifted values in place, without leaving `/generator` |
| destroyed target | a scenario whose setup was deleted surfaces `target-unavailable` naming it, instead of silently retargeting |

Mutation probe on the round trip: with `initialTarget` ignored in TargetSelector, the test failed with
`Expected "e2e-scenario-…" / Received "e2e-test-setup"` — it catches the exact silent-retargeting
failure the feature exists to prevent.

`generator.spec.ts` asserted the Tools route renders the "System Overview" heading; corrected to
assert the Generation Tools page, and the rewritten case passed against the live app.

**Two pre-existing defects found by running the full sweep, and fixed here:**

1. *`generator-schedule.spec.ts` had a 1-in-60 time-dependent failure.* `localDatetime()` always
   emitted seconds; the `datetime-local` inputs carry no `step`, so Chrome normalises a trailing
   `:00` away and Playwright's `fill()` throws `Malformed value` when the retained value differs.
   Observed at `fill("2026-08-02T00:22:00")`. Seconds are now emitted only when non-zero.
2. *Two `MessageGeneratorPage` unit tests depended on no backend being reachable.*
   `configService` defaults to the **absolute** URL `http://127.0.0.1:8088`, so Zone A's real fetch
   reached whatever was listening — and `npm run test:e2e` leaves a backend running, making
   `test:e2e` → `test:run` fail in that order. Confirmed pre-existing by reproducing on stashed,
   pristine code. Fixed through the app's own config seam (a closed port seeded in `beforeEach`,
   after `localStorage.clear()`): no mocks, no request interception. Verified green **with the
   leftover backend still listening**, the exact condition that broke it.

*Integration leg is a non-result:* `npm run test:integration` finds **no test files** and passes via
`--passWithNoTests`. The module has no integration suite; that leg proves nothing.

**Screenshots:** the gallery spec now captures the G.4 surfaces — `39-scenario-save-dialog.png` and
`40-tools-scenarios.png` — and clears `peegeeq_scenarios` in its teardown. `02-tools.png` picks up
the new Generation Tools page automatically. Regenerating the PNGs rewrites committed assets, so it
is deliberately left as the user's action:
`npx playwright test --config=playwright.screenshots.config.ts`, then Appendix A's Tools entry.

*Repo-wide lint (`npm run lint`) fails with 40 problems across 10 files — **identical at HEAD**, none
in any file this step created or changed. Pre-existing; not addressed here to keep G.4 reviewable.*

---

## Phase T — Backend telemetry (peegeeq-db / peegeeq-rest)

**Goal:** close the telemetry gaps the two heavy generation tools depend on. This is a **multi-module
Java change** (like Phase 1B), so validate with `mvn clean test -Pall-tests`. Read
`docs-design/dev/pgq-coding-principles.md` and the testing-antipatterns doc first; reactive-only, no
banned patterns; TestContainers integration tests. Full rationale and verified baseline in
[PEEGEEQ_ADMIN_DEVOPS_TELEMETRY_REQUIREMENTS.md](PEEGEEQ_ADMIN_DEVOPS_TELEMETRY_REQUIREMENTS.md).

*Gates:* G.1b (rich breaking-point) and G.2 (native-vs-outbox). **No other phase depends on it** —
everything in Phases A–G except those two is client-side or uses telemetry that already exists.

| Step | Gap | What to add | Reference |
|---|---|---|---|
| T.1 | G1 | Latency **percentiles** (p50/p95/p99) per queue (histogram) — expose on `/stats` alongside the existing `avgProcessingTimeMs` | telemetry §4 G1 |
| T.2 | G2 | **End-to-end delivery latency** (enqueue → available), tagged by implementation type — the native-vs-outbox differentiator | telemetry §4 G2 |
| T.3 | G6 | Per-message **enqueue timestamp** + echoed client `x-send-ts` header on consume (for latency join + ordering checks) | telemetry §4 G6 |
| T.4 | G3 | Resource-saturation metrics **beyond** the `dbPool` already in `/sse/metrics`: DB write latency, event-loop lag, NOTIFY backlog, pool acquire-wait | telemetry §4 G3 |
| T.5 | G4 | Raise `/sse/metrics` cadence to **≥ 1 Hz**, or add a fast per-run/per-queue stream | telemetry §5 |
| T.6 | G5 | **Per-run / correlation scoping** of metrics (or accept dedicated-queue-per-run as the tool-side workaround) | telemetry §4 G5 |
| T.7 | G7 | **Database-level queue-table telemetry** endpoint/stream: `pg_stat_user_tables` churn / dead-tuple / vacuum / scan / size for the setup's `queue_messages` · `outbox` · `dead_letter_queue` · per-queue tables, plus cluster signals (long-txn/`xmin`, locks, WAL, checkpoints, xid-age) | telemetry §4A |

Notes (from telemetry §4A): the DB queries (T.7) sample at ~5 s, **not** 1 Hz; baseline-and-delta the
cumulative `pg_stat_*` counters over the run window; `pgstattuple` is optional (enable for exact
bloat, else use the `n_dead_tup`/`n_live_tup` estimate).

**Verification:** banned-pattern grep (Java **and** TS); `mvn clean test -Pall-tests`; confirm each
new field against the running backend **before** the UI consumes it (verify-by-running, not asserting).

---

# Setup connect / reconnect track (backend-led)

A backend-led track that closes the "connect to an existing setup" gap and builds toward the estate
control plane. **Phase S is a prerequisite for Phase B** (decided): setup provisioning belongs to
the admin tool, not the generator — the generator only *targets* setups, so connect-to-existing is
its only path to a target and S sits on the generator track's critical path. R and M follow S but
do not block B. Spec:
[PEEGEEQ_ADMIN_SETUP_LIFECYCLE_AND_MANAGEMENT_DB.md](PEEGEEQ_ADMIN_SETUP_LIFECYCLE_AND_MANAGEMENT_DB.md). All three
phases are multi-module Java changes → the same pre-work + `mvn clean test -Pall-tests` gate as Phase T.
Ship in order; each is independently useful.

## Phase S — Setup connect (manual attach)

**Goal:** a non-destructive `connectToExistingSetup` primitive so an operator can attach a backend to a
setup whose database already exists, plus the reference + port UI. No persisted credentials.

*Prerequisite: none (independent backend work).* Spec: setup-db §4, §5, §12, §13.

| Step | Layer | Change | Reference |
|---|---|---|---|
| S.0 | peegeeq-api/db/rest | ✅ **DONE 2026-07-17** — stronger than originally sketched: **no `overwrite` flag at all.** `create` **never drops** — an existing DB fails with `DatabaseCreationConflictException` → REST **409** with an actionable message (connect, or drop first). The destructive path is a *separate* guarded op (**W-DD**, setup-db §13.1): `dropSetupDatabase(setupId, confirmDatabaseName)` type-to-confirm + `POST /setups/{id}/database/drop` + management-ui danger modal. | setup-db §13/§13.1 |
| S.1 | peegeeq-api | ✅ **DONE** — `DatabaseSetupService.connectToExistingSetup(request)` added as a non-breaking `default` (throws `UnsupportedOperationException`); real impl in S.2, delegators in S.3. | setup-db §4 |
| S.2a | peegeeq-db | ✅ **DONE** — `peegeeq_object_registry` + `peegeeq_setup_metadata` created via the base schema-template (`10a`/`10b`); rows written on bulk create (metadata + event-store `bitemporal` + queue resolved `native`/`outbox` kind), on dynamic `addQueue` / `addEventStore`, and removed on `removeEventStore` delete-sync. Event-store add/remove are atomic with their DDL (`withTransaction`); the queue write is ordered-safe on a separate connection (resolved kind is only known post-factory). Registry rows upsert. All three code-review follow-ups closed (below). | setup-db §4 |
| S.2 | peegeeq-db | ✅ **DONE** — `connectToExistingSetup` implemented as a **fully separate, parallel non-destructive path** (decision: do **not** refactor the load-bearing `createCompleteSetup` — keep create untouched as a failsafe). Skips steps 1–2, runs `validateDatabaseInfrastructure` **first** (fails clearly if the schema/registry tables are absent; never creates), reconstitutes queues/event-stores from `peegeeq_setup_metadata` + `peegeeq_object_registry` (exact `kind` + config; queue `implementationType` forced to the recorded kind), then starts the manager + registers the reconstituted factories. `RuntimeDatabaseSetupService` delegates. | setup-db §4 |
| S.3 | peegeeq-rest | ✅ **DONE 2026-07-17** — `POST /api/v1/database-setup/connect` → `connectToExistingSetup` (200 + reconstituted contents; schema-absent → 400); `RuntimeDatabaseSetupService` delegates. The dead `RestDatabaseSetupService` was **deleted** (no production callers, no back-compat required). Also added: `POST /setups/{id}/detach` (non-destructive, 204) and `POST /setups/{id}/database/drop` (W-DD, guarded). | setup-db §4/§5 |
| S.4 | peegeeq-management-ui (reference) | ✅ **DONE 2026-07-17** — "Connect to Existing" button + modal posting to `database-setup/connect`; "Delete Setup" reworded to non-destructive **Detach Setup**; danger **Drop Database…** type-to-confirm modal (W-DD). E2E: 44/44 (`4-database-setup` chain). | setup-db §12 |
| S.5 | peegeeq-utilities-ui (port) | ✅ **DONE 2026-07-17** — `setupService.connectExisting` + `ConnectSetupPage` (form, **no modal**; connect-only — replaced the Create Setup page) + per-row **Detach** on SetupsPage. E2E real-backend: 52/52 (`connect` + `5-setups`); unit 118/118. | setup-db §12 |
| S.6 | peegeeq-utilities-ui (removal) | ✅ **DONE 2026-07-17** — `CreateSetupPage`, `CreateQueuePage`, their routes, `setupService.createSetup`/`deleteSetup` and mock-based unit tests removed; SetupDetailPage destructive delete-setup removed; create CTAs/empty states (TargetSelector, Overview, SetupsPage) repointed at "Connect setup" + admin-tool pointer; e2e specs converted to real-backend style. Screenshots doc-gen conversion deferred. | design §6.4/§6.5 scope decision |

**Verification (all run green, 2026-07-17/18):** non-destructiveness (publish rows, connect from a fresh
instance, assert rows survive); reconstitution (pre-existing queues enumerated with correct `kind` +
config, not re-supplied); schema-absent → clear `400`; **`create` on an existing DB → `409`, data always
intact — there is no overwrite path; dropping is the separate guarded W-DD op**; after S.6, no
create-setup/create-queue route or service function remains in utilities-ui. Backend: runtime IT 12/12
(incl. duplicate-attach refusal, create-conflict 409, guarded drop, dead-DB teardown contract), REST
connect IT 2/2, REST setup-lifecycle IT 15/15 (incl. detach 204, drop 400/404/200). UI e2e:
management-ui 44/44, utilities-ui 52/52 + unit 118/118.

### S.2a — implemented (writes foundation), 2026-07-16

The self-describing registry is populated end-to-end, so S.2 reconstitution has real data to read:

- **DDL via the base schema-template mechanism** (not ad-hoc): `10a-setup-object-registry.sql`
  (`peegeeq_object_registry`: `object_name` PK, `kind` CHECK `native`/`outbox`/`bitemporal`, `config`
  JSONB) and `10b-setup-metadata.sql` (`peegeeq_setup_metadata`: one self-identifying `setup_id` row).
  Both are appended to the base `.manifest`, so `resolveRequiredTables` auto-includes them in
  `validateDatabaseInfrastructure`.
- **Writes:** bulk `createCompleteSetup` (metadata row; one event-store row per store, kind `bitemporal`;
  one queue row per queue carrying the **resolved** `native`/`outbox` kind captured from factory
  creation); dynamic `addQueue` and `addEventStore`; delete-sync on `removeEventStore`. Config is
  serialised with a JavaTimeModule `ObjectMapper` (`QueueConfig`/`EventStoreConfig` → JSONB).
- **Queue delete-sync is moot today:** there is no `removeQueue` in the service/interface and REST
  `deleteQueue` never touches the DB registry, so no queue-row drift is possible. Add a
  `deleteObjectRegistry` call if/when a `removeQueue` is introduced.
- **Tests** (TDD red→green, real TestContainers, `PgConnectionManager` verification): `peegeeq-runtime`
  `RuntimeDatabaseSetupServiceIntegrationTest` (create + `addQueue` + `addEventStore` + `removeEventStore`
  registry rows) **7/7**; `peegeeq-db` `PeeGeeQDatabaseSetupServiceEnhancedTest` (bulk metadata +
  event-store row) **13/13**. Also added the missing `peegeeq-runtime/src/test/resources/logback-test.xml`.

**Code-review follow-ups — all resolved 2026-07-16 (green: runtime 7/7, db 13/13):**
1. ✅ `addQueue` now writes the registry row and mutates in-memory setup state **only on success**, so a
   registry-write failure no longer leaves a queue live in memory but absent from the registry.
2. ✅ `addEventStore` and `removeEventStore` run their DDL + registry write in one `withTransaction`
   (table and registry row commit or roll back together). `addQueue` and the bulk create path write the
   queue row on a separate connection **by design** — the resolved `native`/`outbox` kind is only known
   after factory creation, which needs the started manager — so those are ordered-safe (registry before
   in-memory mutation; the bulk case is additionally covered by create-failure teardown) rather than
   single-transaction, and the code comments now state this accurately.
3. ✅ `insertObjectRegistry` upserts (`ON CONFLICT (object_name) DO UPDATE SET kind, config`), so
   re-provisioning refreshes the recorded kind/config instead of keeping a stale row.

### S.1 + S.2 — implemented (connect / reconstitution), 2026-07-16

`connectToExistingSetup` is a **fully separate, parallel path** — `createCompleteSetup` is untouched
(decision: keep create as a failsafe; no shared-tail refactor):

- **S.1** — `DatabaseSetupService.connectToExistingSetup(request)` added as a non-breaking `default`
  (throws `UnsupportedOperationException`); implemented in `PeeGeeQDatabaseSetupService`;
  `RuntimeDatabaseSetupService` delegates. Rest delegation is **S.3** (still open).
- **S.2** — flow: validate schema identifier → `validateDatabaseInfrastructure` **first** (fails clearly
  if the registry/schema tables are absent — never creates) → **reconstitute** from
  `peegeeq_setup_metadata` (recover + validate `setupId`) and `peegeeq_object_registry` (each row →
  `QueueConfig` for `native`/`outbox` with `implementationType` forced to the recorded kind, or
  `EventStoreConfig` for `bitemporal`) → start manager (`start()` is non-destructive) → register the
  reconstituted factories + repopulate in-memory maps. No registry writes on connect. Failure path uses
  the non-destructive `destroySetup` (closes the manager; never drops the database).
- **Latent bug fixed:** object-registry `config` was being stored **double-encoded** — binding a `String`
  to a `CAST($n AS JSONB)` param makes the Vert.x pg client JSON-encode (quote) it, so it landed as a
  JSONB *string* not an object. The S.2a write tests only asserted `config` non-null, so it slipped
  through; reconnect (the first reader to deserialize it) caught it. Now bound as a `JsonObject`.
- **Test** (TDD red→green): `RuntimeDatabaseSetupServiceIntegrationTest.connectToExistingSetup_reconstitutesFromRegistry`
  — instance A provisions; a fresh instance B attaches to the same DB with an **empty** request body and
  rebuilds the queue (correct `native`/`outbox` kind) + event store from the registry. Green:
  runtime **8/8**, db **13/13**.

### Phase S — COMPLETE 2026-07-18

All steps S.0–S.6 shipped and verified (see the step table above for per-step detail). Delivered
beyond the original scope, all spec'd in setup-db §13.1 / Appendix W:

- **Detach vs destroy made explicit:** `detachSetup` on the service interface (delegates to the
  already-non-destructive `destroySetup`), REST `POST /setups/{id}/detach` (204), management-ui
  "Detach Setup" action (replacing misleading "Delete" copy), utilities-ui per-row Detach.
- **Guarded drop (W-DD):** the *single* destructive path — `dropSetupDatabase(setupId,
  confirmDatabaseName)` with a type-to-confirm guard, REST `POST /setups/{id}/database/drop`
  (400 mismatch / 404 unknown / 200 confirmed), management-ui danger modal whose Drop button stays
  disabled until the exact database name is typed.
- **Teardown honesty (setup-db §13 Note B):** `destroySetup`/`close()` now surface resource-close
  failures (DELETE/detach can return 503 on a genuine close failure) instead of erasing them; a
  dead-database probe verified closes are local and never spuriously fail (pinned by
  `DestroySetupDeadDatabaseIntegrationTest`).

## Phase R — Durable registry + auto-reload (single backend)

**Goal:** persist the binding so setups survive a restart and re-establish automatically.

*Prerequisite: Phase S (auto-reload drives `connectToExistingSetup`).* Spec: setup-db §6, §8, §11.

| Step | Layer | Change | Reference |
|---|---|---|---|
| R.1 | peegeeq-db/rest | Registry store: bindings table (`setupId → server/db/schema/username/credential_ref`); **no password stored** — resolution via `CredentialProvider` (Pre-4) | setup-db §6, §11 |
| R.2 | provision/connect | On `create` **and** `connect`, opt-in persist the binding | setup-db §6 |
| R.3 | startup | Read registry → `connectToExistingSetup` per entry; **skip-and-log** failures, never abort startup | setup-db §6 |
| R.4 | UI (reference + port) | "Remember this setup" checkbox (sets the persist flag) in the connect modal/form | setup-db §12 |

**Verification:** persist a binding → restart → setup comes back active with no manual step; a bad entry
is skipped; **no password is stored** — the registry holds coordinates + `credential_ref` only.

## Phase M — Management database (estate control plane)

**Goal:** one **org-wide** `peegeeq-management` database coordinating setups across many PostgreSQL
servers, with **single-owner** leases and failover.

*Prerequisite: Phase R (generalises the registry to the standalone central DB).* Spec:
[PEEGEEQ_ADMIN_SETUP_LIFECYCLE_AND_MANAGEMENT_DB.md](PEEGEEQ_ADMIN_SETUP_LIFECYCLE_AND_MANAGEMENT_DB.md).

| Step | Layer | Change | Reference |
|---|---|---|---|
| M.1 | schema | Standalone management DB: `servers`, `setups`, `setup_ownership`, `backends` tables | setup-db §8 |
| M.2 | bootstrap | Backend connects to the management DB at startup (well-known config) and discovers the estate | setup-db §7, §9 |
| M.3 | ownership | **Single-owner** lease: atomic claim / renew / heartbeat; takeover on TTL expiry | setup-db §10 |
| M.4 | reconnect fan-out | Auto-reload reads the central registry and connects out to **each setup's own server** | setup-db §9 |
| M.5 | peegeeq-management-ui | Server inventory + per-server setup listing; each setup shows its server/host | setup-db §7 |

**Verification:** cross-server reconnect (two separate PG containers, restart, both return active);
lease takeover on owner death with **no duplicate maintenance jobs** running; `mvn clean test -Pall-tests`.

---

## Phase SCH — Scheduled generator runs

**Merged into this plan 2026-07-22** (previously
`PEEGEEQ_GENERATOR_SCHEDULED_RUNS_IMPLEMENTATION_PLAN.md`, now deleted).

**Design:** **Part III** of
[PEEGEEQ_DEVOPS_UTILITIES_DESIGN.md](PEEGEEQ_DEVOPS_UTILITIES_DESIGN.md). Every `§n`, `Rn`
and `Dn` reference in this phase points into Part III.

**Status**: ✅ COMPLETE 2026-07-19 — all steps SCH.0–SCH.7 done, plus SCH.8 (the later
phases: schedule import + manual-run history, implemented on user request the same day).
**Post-review hardening 2026-07-21 — SCH.9 at the end of this phase.** The per-step evidence
below is a dated record of what was built on 2026-07-19; where the review changed that code
the row carries a *superseded* note. Three mechanisms below no longer describe the shipped
UI: the `localStorage` lease (replaced by the Web Locks API), advancing `nextRunAt` at the
terminal callback (moved to fire time), and the page-local stop handle (moved into
`runStarter`).

Ground rules are the **Cross-cutting rules** above, unchanged: strict TDD (failing tests
first, including every failure mode of every called dependency), no mocks beyond the
sanctioned engine publish boundary, no error swallowing, no fire-and-forget async, mirror
existing module patterns (templateStore/templateService are the model for the store/service
pair), banned-pattern grep per step, one phase at a time with tests green before the next.

### Per-step evidence

SCH.1 ✅ DONE 2026-07-19 (24 tests red→green; three storage keys with per-entry validation +
D8 bounds in the service write path; `computeNextRunAt` pure and directly tested; model
corrected at user review: schedules carry scheduling state only, outcomes live in history,
table derives via `latestOutcomeFor`).

SCH.2 ✅ DONE 2026-07-19 (7 runStarter tests red→green; wiring relocated from
MessageGeneratorPage without contract change; existing page/engine tests untouched and green;
full suite 248/248, build clean, generator e2e 28/28 re-verified).

SCH.3 ✅ DONE 2026-07-19 (14 scheduler tests red→green: no-auto-start-on-open with missed
records, firing/skip/interval-advance, fire-time missing-list detail, lease non-holder +
expired-lease takeover, two-layer corrupt-schedule defence — storage validation drops on
load, the runtime catch records error + disables for in-memory corruption; full suite
434/434, build clean). *(Superseded 2026-07-21: the two lease tests are now Web Locks tests —
a non-holder never fires, and a waiting runtime takes over when the holder releases, as on
tab death. jsdom has no Web Locks API, so `vitest.setup.ts` installs a process-wide polyfill.)*

SCH.4 ✅ DONE 2026-07-19 (12 modal tests + 3 Zone D tests red→green: capture summary,
execution-constraint note, prefilled name, name/time/past-time validation, frozen deep-copy
config on save with correct nextRunAt, interval spec, non-blocking missing-list warning,
cancel stores nothing; interval-below-1 proven unreachable via the input's min bound with
storage-level Zod as the non-UI guard; Zone D Schedule… button enabled as Start; page
assembles the config and owns the modal; full suite 449/449, build clean).

SCH.5 ✅ DONE 2026-07-19 (17 page tests red→green covering all three tabs: schedule rows +
derived outcome column + enable toggle + Export-all + run-now (fires without consuming the
slot; refused with a message while active) + edit-timing (revives a consumed one-shot) +
save-as-template + delete-with-history-surviving; history filters (result + name), download
only on fired entries, re-schedule prefilled from the frozen record config, 200-bound
caption; template schedule-from/run-now-under-template-name/delete. Shared helpers
(fireTimeMissingListsNote, outcomeFromRun) exported from schedulerRuntime, its 14 tests
unchanged and green. Full suite 466/466, build clean).

SCH.6 ✅ DONE 2026-07-19 (2 App tests red→green: Scheduled Runs sidebar entry navigates to
the screen; scheduler starts on App mount and stops on unmount, observed through its lease
key *(superseded 2026-07-21: the test observes the Web Locks lock via `navigator.locks.query`,
and the axios boundary is stubbed so the App-wiring test stops emitting ECONNREFUSED noise)*.
Route + nav entry + schedulerRuntime lifecycle added to App.tsx. Full unit suite
468/468, build clean, full browser suite 63/63 re-verified against the changed sidebar and
the now-mounted scheduler).

SCH.7 ✅ DONE 2026-07-19. E2E project `6-generator-schedules` (real backend, own throwaway
setup): the full journey — schedule 10 s ahead via the UI, fires while on the Scheduled Runs
screen, history row with server-acknowledged 10/0, one-shot consumed, result filter,
save-as-template, re-schedule prefilled from the frozen config, template run-now recorded
under the template name, deletes with history surviving — plus the D3 test: a schedule due
while the app is closed (about:blank across the due time) is recorded MISSED with 0/0 and
never fires. **The D3 e2e caught a real defect**: the missed sweep ran only at start, so a
reload within the lease TTL skipped it and the post-takeover check auto-fired the overdue
schedule. Fixed test-first (3 new scheduler unit tests): sweep at first lease acquisition
with app start as the cutoff, lease released on pagehide; design §7.4/§7.5 corrected.
*(Superseded 2026-07-21: the lease is deleted. The sweep now runs at first WEB LOCK
acquisition, still with app start as the cutoff; the browser releases the lock on tab death,
so there is no `pagehide` handling.)*
De-flaked `--repeat-each=2 --retries=0`. Screenshots 13–16 added (modal, schedules tab,
history after a real firing, templates tab) and Appendix A extended; Part I §3 non-goal
graduated. Unit 471/471.

Coverage extension (user request, 2026-07-19): two further e2e tests — (a) the two-tab
executor election *(the lease then; the Web Locks lock since 2026-07-21)* with two REAL tabs
sharing real storage: a due schedule fires exactly once, and the
count stays 1 through two further check cycles in both tabs; (b) an interval schedule
fires twice at its one-minute slots with the schedule staying enabled between firings.
Project re-de-flaked `--repeat-each=2 --retries=0` (33/33). Schedule import, deferred here
as the D7 later phase, shipped the same day as SCH.8 (below).

### Dependency order

```
SCH.0  Design review (user)            ── gates everything; D1–D6 sign-off
SCH.1  Types + service + store          ── pure data layer, no UI
SCH.2  runStarter extraction            ── refactor, no behaviour change
SCH.3  schedulerRuntime                 ── depends on SCH.1 + SCH.2
SCH.4  Schedule… modal (Zone D)         ── depends on SCH.1
SCH.5  Scheduled Runs screen + route    ── depends on SCH.1; run-now uses SCH.2
SCH.6  App wiring                       ── mounts SCH.3; adds nav + route for SCH.5
SCH.7  E2E + screenshots + doc close-out
```

SCH.4 and SCH.5 are parallelisable after SCH.1; everything else is sequential.

### Steps

| Step | Files | What | Verification |
|---|---|---|---|
| SCH.0 | design doc | User reviews Part III §12 decisions D1–D6. **No code before sign-off** — D1 (client-side execution) shapes every later step. | User review. |
| SCH.1 | `src/types/schedule.ts`, `src/services/scheduleService.ts`, `src/stores/scheduleStore.ts` | ALL §5 types: `ScheduledRun`/`ScheduleOutcome`/`ScheduleRunRecord`/`ScheduleTemplate`; service = localStorage CRUD for the three keys (`peegeeq_generator_schedules`, `peegeeq_schedule_run_history`, `peegeeq_schedule_templates`) with per-entry Zod validation (mirror templateService, incl. the dropped-entry warning path), the **200-entry FIFO cap + 20-error cap** on history writes (D8), and `exportAllSchedules()` → `schedules.json` (R11); store = zustand CRUD for schedules/history/templates + `recordOutcome` (appends the history record — the run record — and advances SCHEDULING state only; corrected 2026-07-19: the schedule carries no outcome fields, the table derives its outcome column via `latestOutcomeFor`) + `saveAsTemplate` + `computeNextRunAt` (§7.3 — pure function, exported for direct testing). | Unit: CRUD round-trips for all three; corrupt-entry drop visible not silent; history cap drops oldest; history entry survives schedule deletion; export blob parses back; §7.3 cases — one-shot consumption, interval advance past missed slots, no catch-up. |
| SCH.2 | `src/engine/runStarter.ts`, `src/pages/generator/MessageGeneratorPage.tsx` | Extract the engine wiring from `handleStart` into `startGeneratorRun(config, hooks?)` (§9): active-run guard returns null; store-generated run id; callbacks to `tickUpdate`/`transitionTo`/`setSummary`; terminal hook; returned `stop()` handle. Page delegates to it; unmount-stop preserved. Pure relocation — no contract change. *(Superseded 2026-07-21: the active handle moved from the page into the module, with `stopActiveRun()` exported — a page-local ref left Stop a no-op for scheduler-started and run-now runs. Page unmount still stops only page-started runs, so a scheduled run survives navigation. `engine.start` is wrapped: a synchronous throw settles the store to ERROR instead of leaving it stuck RUNNING.)* | Unit: new runStarter tests (refusal while active; terminal hook fires; callbacks wired — publish boundary mocked as in engine tests). ALL existing page + engine tests stay green unmodified. |
| SCH.3 | `src/engine/schedulerRuntime.ts` | §7 in full: 15 s check; due-schedule firing via runStarter; skip-and-record while active (D4); §7.4 on start — every overdue schedule records `missed` and advances, **app open never auto-fires** (D3); §7.5 localStorage lease with heartbeat *(superseded 2026-07-21 — Web Locks exclusive lock `peegeeq_scheduler`, held for the tab's lifetime; no Web Locks support means no firing, said loudly)*; §10 check-escape → surfaced message + `error` outcome. `start()`/`stop()` for App mount/unmount and tests. *(Superseded 2026-07-21: `nextRunAt` advances AT FIRE TIME via the new `advanceSchedule` action — advancing only at the terminal callback left the fired slot due during the run, so any run outlasting one 15 s check recorded a false self-skip and consumed a one-shot mid-run. `recordOutcome` also takes a fire-time `{scheduleName, config}` snapshot: a schedule deleted mid-run previously produced a record with an empty config stub that failed per-entry validation on re-read, silently losing the outcome.)* | Unit (fake timers): fire-on-due while running; app start with overdue schedules marks missed and fires NOTHING; skip records outcome and advances; every outcome kind (fired/skipped/missed) appends its history record (R12, via SCH.1's `recordOutcome`); lock non-holder never fires, waiting tab takes over on release; escape surfaces. |
| SCH.4 | `src/pages/generator/ScheduleRunModal.tsx`, `GeneratorActions.tsx` | Zone D **Schedule…** button (enabled as Start: idle + target) opening the §8.1 modal: name (prefilled), once/interval picker, past-time rejection, capture summary line, client-side-execution note, missing-list warning (same scan surface as Start), save = full-config snapshot into the store (D5). | Unit: validation paths; snapshot equals the current working state; missing-list warning; disabled without target. Existing Zone D tests green. |
| SCH.5 | `src/pages/schedules/ScheduledRunsPage.tsx` | §8.2 in full — three tabs. **Schedules**: table + enable toggle, run-now (via runStarter; visible refusal while active; records history), edit-timing modal (D6), save-as-template, delete confirm, **Export all** (R11; disabled when empty), empty state. **Run history**: filterable table (result filter + name search), download on fired entries, save-as-template, re-schedule (prefilled modal), 200-cap caption. **Templates**: table with Schedule… (prefilled modal), run-now (records history under the template name), delete confirm, empty state. Page-level execution-constraint banner. | Unit: §11 test groups 6–8 — schedules tab behaviours; every-outcome-kind history append, filters, cap, survival after schedule deletion, download-only-on-fired; template save/prefill (frozen config, not working state)/run-now/delete. |
| SCH.6 | `src/App.tsx` | Route `/generator/schedules`, sidebar entry, `schedulerRuntime.start()` on App mount (stop on unmount). | Unit: nav + route render. Full unit suite green; build clean. |
| SCH.7 | e2e spec + project, `screenshots.spec.ts`, the design doc | E2E (real backend): schedule ~10 s ahead via the UI → fires while on the Scheduled Runs screen → **history row appears with real acknowledged counts and passes the result filter** → save the entry as a template → schedule-from-template (prefilled modal) → run-now from the template → delete schedule and template, history rows survive. Screenshot addendum (all three tabs + modal). Graduate "scheduled runs" from Part I §3 Non-Goals; mark plan rows done. | Full e2e suite green incl. new project, de-flaked `--repeat-each=2 --retries=0`; screenshots regenerated. |

### Risks and their controls

| Risk | Control |
|---|---|
| Unbounded localStorage growth from history | D8: 200-entry FIFO cap + 20-error cap per stored summary, enforced in the service write path and tested in SCH.1. |
| Double-firing with two open tabs | §7.5 Web Locks executor election *(the lease until 2026-07-21, whose acquire was not atomic)*; a dedicated SCH.3 test proves a non-holder never fires. |
| Firing collides with a manual run | D4 skip-and-record; §7.2 rule 1 test. |
| Timer-driven code is flake-prone to test | All SCH.3 tests use fake timers; `computeNextRunAt` is a pure exported function tested without timers. |
| The refactor (SCH.2) silently changes Start behaviour | Zero modifications to existing page/engine tests allowed in SCH.2 — they are the regression harness. |
| User misreads schedules as server-side | The §8.1 modal note and §8.2 banner state the constraint; the design forbids UI copy implying otherwise. |
| localStorage schedule corrupt/stale after upgrades | Per-entry Zod validation with visible dropped-entry warnings (SCH.1). |

### Out of scope (recorded, not planned)

Backend/unattended execution, cron grammar, config editing in place, full per-schedule run
history, cross-tab live list sync — all per Part III §4. Backend execution, if ever wanted,
is a separate project that reuses the Part III §5 schedule model and replaces its §7.

### SCH.8 — the later phases (graduated from Out of scope)

**SCH.8 ✅ DONE 2026-07-19 — implemented on user request:**
1. *Schedule import*: `importSchedulesFromFile` (per-entry Zod incl. range rules, named
   rejects, array-or-single) + `scheduleStore.importSchedules` (duplicate-id skip against
   storage AND within the batch, named; `nextRunAt` recomputed — past one-shot arrives
   consumed, past interval advances, an imported backlog never fires) + the Import button on
   the Schedules tab (fire-and-forget-guarded). 10 unit tests red→green; e2e: import via the
   real file input, future schedule live, past one-shot consumed, zero firings across two
   check cycles.
2. *Manual-run history*: `scheduleStore.recordManualRun` — every Start-button terminal
   records a history entry (`scheduleId: "manual"`, frozen config copy, full summary);
   wired in MessageGeneratorPage's terminal hook. 2 unit tests red→green; e2e: the
   generator-run journey asserts the manual record with acknowledged counts (15).
Screenshots regenerated (Schedules tab now shows Import; history includes manual runs).
Unit 483/483. Remaining exclusions are DECISIONS, not deferrals: backend execution (D1),
cron (D2), in-place config editing (D6), cross-tab live list sync (Part III §4).

### SCH.9 — post-review hardening (2026-07-21)

**SCH.9 ✅ DONE 2026-07-21** — a full review of the shipped feature produced the corrections
below. Each was written test-first. They change mechanisms SCH.2/SCH.3/SCH.7 built, so the
rows above carry *superseded* notes pointing here.

**Scheduler correctness**

1. *Fire-time advance.* `nextRunAt` advances when the schedule fires, through the new
   `scheduleStore.advanceSchedule`; the terminal callback records history only. Advancing at
   the terminal left the fired slot due at every check during the run: a run outlasting one
   15 s check recorded a false self-skip, and a one-shot was consumed mid-run by that skip.
   Part III §7.2 corrected.
2. *Outcome of a schedule deleted mid-run.* `recordOutcome` takes a fire-time snapshot
   parameter (`{scheduleName, config}`). Reading the schedule at terminal time produced an
   empty config stub for a deleted schedule, which then failed the per-entry history
   validation on re-read — the run outcome was silently lost. The scheduler passes its
   closure-held snapshot.
3. *Stop reaches every run.* `runStarter` holds the active handle at module level and exports
   `stopActiveRun()`. The page-local ref meant scheduler-started and run-now runs showed
   RUNNING with a Stop that did nothing. Page unmount still stops only page-started runs, so
   a scheduled run survives navigation.
4. *Synchronous start failure.* `engine.start` is wrapped; a throw settles the store to ERROR
   instead of leaving it RUNNING with no engine until a reload.
5. *Terminal-callback exceptions.* `publicationEngine`'s `runTick` catch no longer swallows
   exceptions thrown by TERMINAL callbacks (a storage-quota error while recording the outcome,
   for one). They surface via `console.error` + `message.error`.
6. *Publish timeout.* `PUBLISH_TIMEOUT_MS` (30 s) on both publish endpoints — a hung socket
   kept the in-flight fan-out that Stop awaits unsettled until the OS timeout.
7. *Per-message `{{uuid}}`.* Memoised per context, so a message's payload and headers share
   one uuid. They previously differed, breaking correlation.

**Cross-tab executor election — REPLACED (user decision)**

The `localStorage` lease (TTL + heartbeat + `pagehide` release) is **deleted, not kept
alongside**: its acquire was not atomic, so two tabs could both pass the write-then-read-back
check and double-fire. The Web Locks API now elects one executor per origin — a hold-forever
exclusive lock named `peegeeq_scheduler`, released by the browser on tab death. The D3 missed
sweep still runs at first acquisition with app start as the cutoff. A browser without Web
Locks gets no firing tab and is told so. Part III §7.5 rewritten.

*Known limit, documented:* the run-active guard is per-tab, so a scheduled firing can overlap
a MANUAL run started in another tab. The lock serialises scheduled firings only.

**Error surfacing**

`services/storagePersist.persistJson` is the shared `localStorage` write path (5 call sites
across the schedule, template and value-list services): quota and disabled-storage failures
are reported instead of throwing uncaught into a click handler or the scheduler terminal
path. `scheduleService.loadValidated` surfaces dropped invalid entries and load failures to
the user — they are user data. `ScheduledRunsPage`: `saveTiming` on a vanished schedule
reports and closes the modal; a blank template name shows an inline error instead of a dead
Save button.

**Test infrastructure**

`vitest.setup.ts` installs a Web Locks polyfill for jsdom (exclusive FIFO, `ifAvailable`,
pending-abort, `query`) shared process-wide the way real same-origin tabs share the browser's
lock manager, plus `__resetWebLocks` between tests. New UI-free
[schedulerConstants.ts](../src/engine/schedulerConstants.ts) lets the Playwright specs derive
their negative-assertion waits from the real check cycle — the two 35 s waits became
`2 × CHECK_INTERVAL_MS + 5 s`, and the app-closed wait polls the actual overdue condition.
`AppScheduledRuns.test` stubs the axios boundary (App wiring only), removing ECONNREFUSED
noise from every unit run.

Test counts are deliberately not recorded here — run the suites for current numbers.

---

## Notes on scope boundaries

- **Backend changes are not expected** in Phases A–F: the Phase 1B backend work (per-queue
  `implementationType`) is already reflected in the services. The only backend touch-point is
  *verification* (A.1 delete-queue path, E.2 overview payload) — confirm, and only change docs or
  code once the runtime behaviour is known. Use the Backend service control prerequisite (copied
  from management-ui) to stand up the REST backend for that verification.
- **Backend-led work is quarantined into named tracks:** Phase T (telemetry) gates *only* two Phase G
  tools; the **Setup connect / reconnect track (Phases S → R → M)** is a separate backend-led effort
  spec'd in the connect and management-DB docs. Everything else — all of Phases A–F and most of G —
  runs on client-side metering plus the telemetry/endpoints PeeGeeQ already exposes, so the utilities-ui
  UI work never blocks on backend changes.
- Anything in the feature design's "Non-Goals (v1)" (Part I §3) and "Future Work" (Part I
  §17) — consumer panel, Monaco editor, auth, Web Worker — stays out of this plan.
  **Scheduled runs graduated from Part I §3 on 2026-07-19** and shipped as **Phase SCH**
  above, against design Part III.
