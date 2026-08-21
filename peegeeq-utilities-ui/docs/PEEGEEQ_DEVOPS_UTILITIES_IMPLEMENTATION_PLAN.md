# peegeeq-utilities-ui — Implementation Plan

**Author**: Mark Andrew Ray-Smith Cityline Ltd  
**Created**: 2026-07-05  
**Version**: 1.0  
**Last reconciled**: 2026-08-19

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
| Backend telemetry needed by rich ramp attribution | ✅ done (T.4/T.5 remediation, 2026-08-09) | Phase T; telemetry §4A, §5 |
| Rich breaking-point attribution | ✅ done (G.1b, 2026-08-19) | Phase G.1b |

---

## Cross-cutting rules (apply to every phase)

These are mandatory and come from the project standards — not optional style notes.

1. **Mandatory pre-work before writing code** (per repository `AGENTS.md`): read
   `docs-design/dev/pgq-coding-principles.md` and
   `docs-design/testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md` in full; read every file you
   will modify; read the existing tests in the same area and mirror their pattern exactly.
2. **One phase at a time.** Implement a phase, rebuild, run the smallest relevant targeted
   tests, inspect their per-class counts, then stop and report. The approximately 90-minute
   `-Pall-tests` suite is an owner-run or explicitly requested release gate.
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

For utilities UI changes, per-phase verification is: banned-pattern grep on touched files →
`npm run build` (zero TS errors) → targeted Vitest for changed units → targeted Playwright for
changed flows. For Java/Maven changes, first run the mandated clean reactor-slice rebuild through
`Tee-Object`, then targeted tests through `Tee-Object` using the documented test profile and tags.

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
Phase T (Backend telemetry, peegeeq-db/rest) ── T.1–T.5/T.7 DONE; T.6 DEFERRED
      └─ G.2 (native-vs-outbox) SHIPPED 2026-08-07 on T.1+T.2+T.7
      └─ G.1b (rich breaking-point) SHIPPED 2026-08-19 on T.4 (G3) + T.5 (G4) + T.7 (G7)

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
| G.1b | ✅ **DONE 2026-08-19** — Ramp rich saturation *attribution*; see the G.1b record below | **Consumed:** G3 resource saturation + G4 fast per-queue stream + G7 DB bottleneck signals | telemetry §4A, §7 |
| G.2 | ✅ **DONE 2026-08-07** — Native-vs-Outbox comparison run (G.2a runner/plan/telemetry service · G.2b two-target Zone A · G.2c results panel + page wiring · G.2d e2e); see the G.2 record below | **Consumed:** G1 (T.1 percentiles) + G2 (T.2 delivery latency) + G7 (T.7 DB churn profile). **G6 not used** — T.2 measures delivery latency on the DATABASE clock server-side, so a client-side correlation join would be a second, less accurate path to the same number, and reading messages back sits on management-ui's side of the §8 boundary | telemetry §7; design §19.2 |
| G.3 | ✅ **DONE 2026-08-02** — Traffic-profile / scenario runner (G.3a sequencer · G.3b phases editor · G.3c mode selector + results panel · G.3d profile scenarios + e2e) | **Client-only** — achieved-rate timeline (finer with G4) | design §19.3; telemetry §6 |
| G.4 | ✅ **DONE 2026-08-01** — saved scenarios (localStorage, templateService-shaped); see the G.4 record below | **None** | design §19.4 |
| G.5 | ✅ **DONE (send) 2026-08-04** — Delay / Priority / FIFO exerciser: deterministic assignment plan, engine ordering seam, Exerciser mode UI + derived manifest, e2e; see the G.5 record below | **Client-only** to send; *auto-verify* needs G6 (else defer to management-ui browser) | design §19.5; telemetry §6 |
| G.6 | ✅ **DONE 2026-08-04** — Correlation / trace seed generator: deterministic id plan, engine per-message correlation seam, Trace-seed mode UI + derived emitted-ids report, e2e; see the G.6 record below | **None** — emits ids; verify in management-ui | design §19.6 |

Surface as **modes of the Message Generator** (Flat rate · Ramp · Compare · Profile), or repurpose
the dead `/tools` route as the suite launcher.

**Build order within Phase G:** all Phase G tools are shipped. G.1b completed the phase on
2026-08-19 using the telemetry supplied by T.4, T.5 and T.7. T.6 is deliberately deferred;
its scope, effort, restart criteria, and dedicated-queue workaround are recorded in Phase T.
It did not block G.1b and is not silently inferred by the UI.

### G.2 — Native-vs-Outbox comparison, 2026-08-07 — **G.2 COMPLETE**

**Purpose.** Fires identical load at one native and one outbox queue AT THE SAME TIME and reports
each side's outcome next to the other's, with backend telemetry sampled either side of the run.
The last of the two telemetry-gated Phase G tools.

**The load-bearing constraint: this is the first mode with TWO runs live at once.** `runStarter`
returns null while a run is active and `generatorStore` holds ONE `RunState`. That is correct for
every other mode — flat/exerciser/trace are one run, profile/ramp are a SEQUENCE with one live at a
time — but a comparison cannot use either: writing both sides into one `RunState` would report a
merged run neither side performed. [comparisonRunner](../src/engine/comparisonRunner.ts) therefore
drives two `createPublicationEngine()` instances directly with caller-supplied identities (the §7 /
B.0 contract the engine already has) and owns its own per-side state. The page folds a separate
`comparing` flag into its gating, or Zone B/C and the mode selector would stay editable mid-run and
both buttons would be armed. A test pins that the store stays idle throughout.

**Three refusals, each with its reason rather than a dead control:**

1. **No verdict unless BOTH sides completed.** A completed run and a stopped or errored one did not
   carry the same load, so "which sustained it better" has no answer. Only the side and its status
   are reported.
2. **Delivery latency is cited only when both sides measured it.** The utilities-ui publishes and
   does not consume, so a queue nothing consumed produces no delivery latency at all. Showing one
   side's number, or reading two absences as "equal", would both invent a result.
3. **An errored side does NOT stop the healthy one.** This deliberately differs from
   `profileRunner`, which aborts: there the phases are sequential, so continuing would report a
   shape that never happened; here the sides are concurrent, and cutting the healthy one short
   would turn its real figures into a partial run.

**Percentiles are never claimed as the run's.** `PeeGeeQMetrics` reads a per-instance, per-topic
Micrometer timer that resets on restart; per-run scoping is gap **G5**, still open. So
`latencySampleDelta` reports how many samples the run contributed and the panel states the scope in
words — a run-scoped p95 would be fabricated.

**Telemetry is sampled twice, not streamed** — baseline before either side publishes, final after
both settle. §4A says these queries are heavier than a counter read and should not be polled at
1 Hz, and that the consumer baselines and deltas the cumulative counters itself. A baseline taken
after the run would understate every churn delta. A FAILED read is a `TelemetryCapture` carrying its
reason and never a zeroed snapshot; `churnDeltaFor` returns null rather than a zero delta.

| Step | What |
|---|---|
| G.2a | [types/compare.ts](../src/types/compare.ts), [comparePlan.ts](../src/engine/comparePlan.ts) (target validation, churn deltas, sample deltas, verdict), [telemetryService.ts](../src/services/telemetryService.ts) (`/stats` G1+G2, `/setups/{id}/db-telemetry` G7; flat percentile fields folded into one optional object per distribution so the backend's absence contract survives), [comparisonRunner.ts](../src/engine/comparisonRunner.ts) |
| G.2b | [CompareTargets.tsx](../src/pages/generator/CompareTargets.tsx) — §19.2 puts BOTH targets in Zone A, so Compare REPLACES `TargetSelector` rather than adding a control beside it. Not two `TargetSelector`s: it reports no implementation type (which `comparePlan` needs) and auto-selects the first queue, so two instances would collide on one queue before the user touched anything. Each row auto-selects the first queue of its OWN type, falling back to an UNKNOWN type but never a known-wrong one — with exact-match-only, a backend sending the names-only payload would leave Compare mode dead. |
| G.2c | [CompareResultsPanel.tsx](../src/pages/generator/CompareResultsPanel.tsx) — the §19.2 card plus the §4A churn profile read from each implementation's own table (`queue_messages` vs `outbox`). Page wiring: mode selector, Zone A swap, shared `RateControls` as Zone B, Zone E swap, Start/Stop routing, unmount stop. `ScenarioBar` refuses to save a comparison — a `Scenario` holds one target and a comparison has two. |
| G.2d | [compare.spec.ts](../src/tests/e2e/specs/compare.spec.ts) + project `14-compare`. Provisions a setup with one native AND one outbox queue in a single `database-setup/create` (`ConfigParser.parseQueueConfig` accepts `implementationType` per queue), then **re-reads the queue list and fails if the two types did not come back native+outbox** — a comparison of two same-type queues measures nothing it claims to and would still go green on counts alone. |

**Four defects found by probing — three in the tests, one a real design flaw:**

1. *A baseline-ordering test that passed with the ordering REVERSED.* It asserted both the telemetry
   reads and the engine starts happened, never which came first. Fixed with one sequence log shared
   by the engine and telemetry fakes; re-probed red.
2. *A stale-selection test that could not fail*, because it mocked the FIRST queue load as failing —
   nothing was ever cached, so "clears the stale selection" was never exercised.
3. *Behind (2), a real flaw:* `loadQueues` used `Promise.all`, which fails fast, so one unreachable
   setup blanked BOTH rows including the one whose setup answered fine — contradicting the
   row-independence rule the same file already tested. Now `Promise.allSettled` with per-setup
   errors, and a new test covers a setup that succeeded then failed on a later load.
4. *A latent infinite-render bug:* `CompareTargets` rebuilt its target objects every render, so once
   the page stored them in state the `onChange` effect would loop forever. Its own tests could never
   catch it — their `onChange` is a spy that triggers no render. Fixed by memoising.

**Verified 2026-08-07:** unit **52 files, 811 tests**; e2e **91/91 across 14 projects** (8.1 min),
`14-compare` de-flaked at `--repeat-each=2 --retries=0` (**8/8**); `tsc --noEmit` 0 errors; lint
**0 errors, 0 warnings**. 10 mutation probes across G.2a–G.2c, each applied, verified, and reverted.
~~**Not done:** screenshots not regenerated (no Compare capture exists — regenerating rewrites
committed PNGs, so it stays the user's action).~~ *(Superseded 2026-08-07: `47-compare-mode.png`
and `48-compare-results.png` were captured and committed. **Design-doc Appendix A still has no
entries for either** — verified 2026-08-08, and the only loose end from that capture run.)*

### G.2e — the churn figures were under-reported, 2026-08-08

**The defect capture 48 shows.** The outbox column read **10 acknowledged and 0 rows inserted**,
which cannot both be true. It was found by the screenshot run, not by the suite: `compare.spec.ts`
asserted acknowledged counts and terminal statuses and **never asserted a churn figure**, so it
passed with this wrong.

**Not an error-handling defect.** `churnDeltaFor` renders `—` when it cannot know, and the cell
showed `0` — so the `outbox` table was found in BOTH snapshots and the delta genuinely computed as
zero. The code behaved correctly on stale inputs. Nor was it the table mapping: querying the
surviving databases directly showed `outbox` `ins=10` with ten real rows.

**Cause.** PostgreSQL does not make a committed INSERT visible in `pg_stat_user_tables` at commit —
each backend accumulates its counters and flushes them on a rate limit — and the native and outbox
publish paths commit on **different connections**. One final sample can therefore catch one side and
miss the other, in the same query.

**The originally proposed fix was unsound and was rejected.** The G.2 handoff proposed polling until
*two consecutive samples agree*. Two reads of a backend that has not flushed **agree with each
other**, so it terminates early and reports the same zero. This was not argued — it was implemented
as a mutation and the suite failed it. Stability is not completeness.

**What shipped instead** mirrors the condition the backend's own churn poll already uses
([DatabaseTelemetryHandlerIntegrationTest](../../peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/DatabaseTelemetryHandlerIntegrationTest.java)):
re-read the final sample until each side's insert delta reaches the rows that side **acknowledged**,
bounded by a deadline. The acknowledged count is a LOWER BOUND, not an expectation — the churn
tables are shared by topic. On deadline the final capture becomes a **failure capture**, so
`churnDeltaFor` yields null and the panel renders `—` with the reason: no new type, no new render
path, and never a zero.

**Three guards, each covering a case where waiting cannot help:** a run that acknowledged nothing is
owed no rows; a failed BASELINE read can never produce a delta; and a table absent from the snapshot
cannot appear by waiting — that is an absent table, not a stale counter.

**The deadline is measured, not inherited.** Instrumented against a real backend, a 10-message-per-
side comparison settled after **17 attempts spanning 9.0 s**. The backend's 15 s would have left a
1.66x margin on an idle machine with a trivial load, and the failure mode is silently rendering `—`,
so the bound is **60 s**.

**A consequence that had to be fixed with it.** Holding the report for the settle window left Zone E
saying *"Both sides are running"* for seconds after both had stopped. The panel now takes
`settledSides` (required, not defaulted) and states three truthful states. `onSideSettled` was
declared on `CompareHooks` and **never consumed by the page** until now.

| Step | What |
|---|---|
| G.2e | [comparePlan.ts](../src/engine/comparePlan.ts) — `CHURN_TABLE` moved here from the panel (one definition, two readers) and new pure `churnReconciled`; [comparisonRunner.ts](../src/engine/comparisonRunner.ts) — `settleChurn` recursive poll + `CHURN_SETTLE_INTERVAL_MS`/`CHURN_SETTLE_DEADLINE_MS`; [CompareResultsPanel.tsx](../src/pages/generator/CompareResultsPanel.tsx) — `settledSides`, three live notes; [MessageGeneratorPage.tsx](../src/pages/generator/MessageGeneratorPage.tsx) — count wiring; [compare.spec.ts](../src/tests/e2e/specs/compare.spec.ts) — asserts both churn figures are exactly `10`, and asserts the waiting note |

**Two of the new tests were toothless and the probes caught it** — both `does not poll…` cases passed
with their guards deleted, the absent-table guard covering for them. The scenarios were changed (a
FAILING final read is the only way a run owed no rows could end up waiting), not the guards. That is
the third time in this feature's history a Compare test passed with the thing it named removed.

**One seam is unreachable from unit tests.** Deleting the page's `onSideSettled` wiring passes
`tsc` **and all 27 page tests** — the page cannot start a comparison without a backend, and the
panel's own tests are handed the count directly. Only the e2e assertion bites it; verified by
applying that mutation and watching `14-compare` fail on `compare-live-note`.

**Verified 2026-08-08:** unit **52 files, 826 tests**; e2e **91/91 across 14 projects** (5.7 min);
`14-compare` de-flaked at `--repeat-each=3 --retries=0` (**12/12**); `tsc --noEmit` and lint clean.
**10 mutation probes**, each applied, observed red on the intended test, and reverted.
**Not done:** the BASELINE carries the same lag in the opposite direction — unflushed statistics
from earlier activity make it too low and INFLATE the delta. Untouched. A quiescence check (two
baselines agreeing) is sound *there* precisely because nothing is owed yet at baseline, which is
what makes it unsound at the final sample; it costs ~1 s on every comparison start and was left as
a decision rather than assumed.

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

### G.1b — rich breaking-point saturation attribution, 2026-08-19 — **G.1 COMPLETE**

**Purpose.** Ramp mode now records the backend conditions observed while each load step runs,
so the basic client-side knee can be reviewed beside queue pressure, resource saturation and
database churn. This is attribution evidence, not a causal claim.

**Collection boundaries.** The collector takes a T.7 database snapshot before load starts,
then phase-tags samples from the T.5 target queue stats stream and the T.4 system metrics
stream. It closes both streams and takes the final database snapshot when the ramp completes,
is stopped, or halts at its knee. Queue SSE and REST parsing share one mapper, preserving
missing values as absent rather than manufacturing zeroes. Start and finish failures are
surfaced in the UI; streams are always observed and closed.

**Report.** The ramp result shows per-step maxima for pending messages, backend message rate,
event-loop lag, pool acquire wait and database active/pending sessions. The database window
shows implementation-specific table churn and baseline-to-final cluster deltas. Findings state
which pressure signals were observed and retain an explicit scope warning: queue and lifetime
database statistics are not per-run without T.6, so a dedicated queue is recommended when
isolation matters.

**Lifecycle correction.** The targeted browser test exposed a React StrictMode rehearsal bug:
the page's mounted guard was cleared by the development-only setup/cleanup/setup cycle and was
not re-armed, leaving ramp preparation locked after its baseline snapshot. The effect now
re-arms the guard on every setup; the real-backend ramp test pins the completed report path.

**Targeted verification 2026-08-19:** production build passed (`tsc` + Vite, 3,209 modules);
focused G.1b/mapper/page unit tests **52/52 across 5 files** after the lifecycle correction; ramp
Playwright project **3/3** against TestContainers PostgreSQL and the real REST backend. The
approximately 90-minute all-tests gate was not run.

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

### G.5 — Delay / Priority / FIFO exerciser (send), 2026-08-04 — **G.5-send COMPLETE**

**Purpose.** Deliberately drives `delaySeconds`, `priority` and `messageGroup` per message to
exercise scheduling and FIFO ordering, then reports a manifest (id → group · priority · delay) so
ordering can be verified downstream in management-ui's Message Browser. Send-side only — auto-
verification needs telemetry G6 and stays out of scope.

**Data model.** `ExerciserSettings { delay, priority, group }`
([types/exerciser.ts](../src/types/exerciser.ts)) is the single source of truth. Every per-message
assignment and the post-run manifest are DERIVED by one pure function,
`assignmentFor(settings, runId, index, valueLists)`
([engine/exerciserPlan.ts](../src/engine/exerciserPlan.ts)) — deterministic via an FNV-1a hash of
`runId:index`. The engine applies it per message at build time; `buildManifest` recomputes it after
the run. Nothing per-message is stored, so the manifest cannot drift from what was sent. "Random"
delay being deterministic per (runId, index) is a documented property, not a shortcut: true
randomness would force the engine to record every assignment — stored derived data.

**Two recorded deviations from the §19.5 mock.** (1) Per-key groups name a **value list**, not a
`{{customerId}}` token — a value list is the only per-message key source this app has, and the
deterministic pick keeps the manifest exact. (2) The manifest lists **attempted** assignments:
per-id delivery attribution does not exist client-side (errors are batch-level), so
`RunSummary.totalAttempted` was added (optional — absent in pre-G.5 stored summaries), a run with
errors carries an explicit caveat, and the display caps at 100 rows while SAYING so; the download
carries all rows.

**Engine seam.** Optional `RunConfig.ordering` — the engine overrides the template's scalar
priority/delay/group per message when present. Build errors are labelled at their SOURCE: template
resolution keeps its original "Template failed to resolve" wording (a mid-step generic rewording
broke two existing tests that correctly assert the reason names the cause — reverted), and a
per-key strategy with a missing/empty list throws naming the list, terminating through `onError`.
The Zod `runConfigSchema`/`runSummarySchema` gained `ordering`/`totalAttempted`: without them the
default Zod strip would silently reload a manual exerciser run's history record as a flat run.

**The exerciser is NOT a sequence.** It is one flat run whose messages carry assignments, so
`handleStart` routes it through the flat wiring with `ordering` attached — the sequencer condition
changed from `mode !== 'flat'` to explicit `profile || ramp` (the exact spot the G.1a fall-through
bug lived; pinned by tests). Zone B is
[ExerciserControls](../src/pages/generator/ExerciserControls.tsx) PLUS the rate controls; its plan
preview calls the same `assignmentFor` the run uses, flags itself as illustrative when values
depend on the not-yet-existing run id, and states that the template's scalar fields are overridden
(the Preview modal still shows template values — recorded gap). Zone E is
[ManifestPanel](../src/pages/generator/ManifestPanel.tsx).

**Three refusals, each with its reason:** Schedule is blocked (the schedule surfaces would display
an exerciser run as a plain flat run), an exerciser run cannot be saved as a scenario (`Scenario`
has no exerciser kind — the ramp precedent, same type-narrowing guard), and per-key with no usable
value list blocks Start (the engine throw is the defence in depth behind it).

**E2E — new project `12-exerciser`** ([exerciser.spec.ts](../src/tests/e2e/specs/exerciser.spec.ts)):
ordering controls + plan preview with a target selected, both refusal assertions, the per-key
block, and a real 10-message run whose manifest rows match the deterministic assignments, with a
real browser download. **A locator collision only the e2e could catch:** the TemplateEditor's
`delaySeconds` field carries the identical "Delay (seconds)" label (it is the field the strategies
override), so the fill is scoped to the exerciser controls — unit tests render the control in
isolation and cannot see the page-level ambiguity.

**Verified 2026-08-03/04:** unit **44 files, 684 tests** (`logs/utilities-ui-unit-20260803-2.txt`);
e2e **85/85 across all projects including 12-exerciser**
(`logs/utilities-ui-e2e-exerciser-20260804.txt`); lint 0 errors (5 warnings — the accepted
`react-refresh/only-export-components` class, +1 for `EXERCISER_DEFAULTS` beside its component);
`tsc --noEmit` 0 errors. **Not done:** mutation probes were not run this step; screenshots not
regenerated (no exerciser capture exists — rewrites committed PNGs, user's call).
*(Both items resolved 2026-08-04: see the carried-debt tidy-up record below — warnings cleared,
gallery regenerated with captures 42–44.)*

### Carried-debt tidy-up — 2026-08-04

All four items from the G.5-era carried-debt list were assessed; three fixed, one deliberately
left:

1. **Lint warnings cleared (5 → 0).** The five constants/factories exported beside components
   (`RATE_DEFAULTS`, `blankTemplate`, `makeDefaultPhase`, `RAMP_DEFAULTS`, `EXERCISER_DEFAULTS`)
   moved to [generatorDefaults.ts](../src/pages/generator/generatorDefaults.ts); component files
   now export components only, satisfying `react-refresh/only-export-components`. All importers
   (page + five test files) updated; no re-exports, which would have kept the warning.
2. **The vacuous `test:integration` leg removed.** `src/tests/integration` does not exist, so the
   script always passed via `--passWithNoTests` and proved nothing. Removed from `package.json`
   including its `test:all`/`test:ci` uses — a green `test:all` no longer implies a leg that ran
   nothing. Nothing else invoked it.
3. **Screenshot gallery regenerated** with the missing mode captures: `42-ramp-mode.png`,
   `43-exerciser-mode.png`, `44-exerciser-manifest.png` (the manifest shot follows a tiny real
   run); Appendix A §A.4 extended.
4. **NOT fixed: the duplicate saved-RunConfig store** (`peegeeq_scenarios` vs
   `peegeeq_schedule_templates`). This is a recorded decision (2026-08-01) marked "do not fix in
   isolation without revisiting the decision" — it stays until that decision is revisited.

**Verified 2026-08-04:** screenshots 1/1 (2.3 m); unit **44 files, 684 tests**; e2e **85/85**
(`logs/utilities-ui-screenshots-20260804.txt`, `utilities-ui-unit-20260804.txt`,
`utilities-ui-e2e-20260804.txt`); lint **0 errors, 0 warnings**; `tsc --noEmit` 0 errors.

### G.6 — Correlation / trace seed generator, 2026-08-04 — **G.6 COMPLETE**

**Purpose.** Emits messages whose correlation ids follow a structured scheme so a run can be
traced afterwards; the emitted ids are reported for downstream use in management-ui's
CausationTree / Events. Template and id population only — no backend change.

**Data model.** `TraceSettings { correlation, causation }`
([types/trace.ts](../src/types/trace.ts)): correlation one-per-run / one-per-batch / every-N;
causation `{ enabled, childrenPerParent }`. Every message's id and the post-run report derive
from `traceFor(settings, runId, index, rate, maxBatchSize)`
([engine/tracePlan.ts](../src/engine/tracePlan.ts)) — UUID-shaped ids minted deterministically
(FNV-1a of runId:group), the exerciserPlan reasoning. Nothing per-message is stored.

**Two load-bearing decisions.**

1. **Causation chains are an ID SCHEME, reported not sent.** `causationId` is by design a
   bi-temporal event-store attribute; queue messages carry `correlationId` only (correction
   recorded 2026-08-04 — an early draft proposed a `causationId` header on queue messages, which
   would fake a store concept). Chains organize the MINTED IDS (1 root + N children per chain) in
   the emitted-ids report; a test pins that enabling chains changes nothing a message carries.
2. **Per-batch grouping follows the ENGINE'S real batch boundaries.** Per tick the quota
   (max(1, floor(rate))) splits into groups of ≤ maxBatchSize, so the remainder batch ends at the
   tick edge; `floor(index / maxBatchSize)` would merge a remainder batch with the next tick's
   first whenever rate is not a multiple of maxBatchSize. A test pins the boundary (rate 25,
   batch 10: …24 | 25…).

**Engine seam.** Optional `RunConfig.trace`: the engine derives each message's correlationId via
`traceFor` — in the MessageRequest field AND the `{{correlationId}}` token context, so a
template-embedded id can never disagree with the field (test-pinned). `runConfigSchema` gained
`trace` so a manual run's history record survives reload un-stripped.

**UI.** [TraceControls](../src/pages/generator/TraceControls.tsx) (Zone B + rate controls; a
derived scheme summary and NO example ids — every id derives from the run id, which does not
exist until Start, and the caveat says so) and
[TraceSeedPanel](../src/pages/generator/TraceSeedPanel.tsx) (Zone E: totals in the §19.6 mock's
shape, the chain figure omitted when chains were not seeded, 100-entry display cap stated, Copy
ids with surfaced clipboard failures, full-report download, delivery caveat on errors).
`TRACE_DEFAULTS` reproduces the mock arithmetic: every 100 messages, chains of 1+3 → 1,200
messages = 12 ids / 3 chains (test-pinned). Trace seed is the fifth mode; like the exerciser it
routes through the FLAT run path (not the sequencer). Schedule and scenario-save are refused
with reasons.

**E2E — new project `13-trace`** ([trace.spec.ts](../src/tests/e2e/specs/trace.spec.ts)):
controls + scheme summary with a target, both refusals, and a real 10-message run (id every 5,
1 child per parent → "10 messages under 2 correlation ids / 1 causation chain") with root/child
rows and a real report download.

**Verified 2026-08-04:** unit **47 files, 721 tests** (`logs/utilities-ui-unit-g6-20260804.txt`);
e2e **87/87 across all projects including 13-trace**
(`logs/utilities-ui-e2e-trace-20260804.txt`); lint **0 errors, 0 warnings**; `tsc --noEmit`
0 errors. **Not done:** mutation probes were not run; emitted ids are verified on the wire in the
engine unit test, not read back from the server. *(Screenshots resolved 2026-08-04: gallery
regenerated — `45-trace-mode.png` / `46-trace-ids.png` captured, spec 1/1 green,
`logs/utilities-ui-screenshots-20260804-2.txt`.)*

---

## Phase T — Backend telemetry (peegeeq-db / peegeeq-rest)

**Goal:** close the telemetry gaps the two heavy generation tools depend on. This is a **multi-module
Java change** (like Phase 1B), so use the required clean reactor-slice rebuild followed by targeted
tests. `-Pall-tests` is the owner-run release gate, not the normal edit-test loop. Read
`docs-design/dev/pgq-coding-principles.md` and the testing-antipatterns doc first; reactive-only, no
banned patterns; TestContainers integration tests. Full rationale and verified baseline in
[PEEGEEQ_ADMIN_DEVOPS_TELEMETRY_REQUIREMENTS.md](PEEGEEQ_ADMIN_DEVOPS_TELEMETRY_REQUIREMENTS.md).

*Gate status:* G.2 shipped on T.1/T.2/T.7. T.4 (G3) and T.5 (G4) were completed on
2026-08-09; G.1b consumed those signals plus T.7 and shipped on 2026-08-19. T.6 was explicitly
deferred on 2026-08-21; the decision record below preserves its scope and estimated effort.

| Step | Gap | What to add | Reference |
|---|---|---|---|
| T.1 | G1 | ✅ **DONE 2026-08-04** — Latency percentiles (p50/p95/p99 + mean + sampleCount) per queue via the per-topic Micrometer histogram both consumers feed at ack; exposed on `/stats` (`processingTime*Ms` fields, ABSENT when unmeasured); replaces the hardcoded 0.0 `avgProcessingTimeMs` with the histogram mean. App-side scope recorded on `DurationPercentiles`: per-instance, resets on restart (decided over SQL percentiles — native deletes processed rows). Evidence: db 48/48, native 13/13, outbox 6/6 (`logs/t2-*-20260804.txt`) | telemetry §4 G1 |
| T.2 | G2 | ✅ **DONE 2026-08-04** — Delivery latency (enqueue → claim) computed INSIDE the claim statement on the DATABASE clock (`now() - created_at` in the claim RETURNING, both consumers, all four SQL variants), recorded to `peegeeq.message.delivery.latency.by.topic` tagged by implementation type; `deliveryLatency*Ms` on `/stats`, same absent-when-unmeasured contract. Same evidence runs as T.1 | telemetry §4 G2 |
| T.3 | G6 | ✅ **DONE 2026-08-05** — enqueue timestamp + client headers on the LIVE SSE message frame (`enqueuedAt`, `headers`); see the T.3 record below. The browse endpoint already satisfied G6 — confirmed against a running backend BEFORE any code was written — so the real gap was the stream. Additive: the pre-existing emit-time `timestamp` is kept and documented, so no SSE consumer breaks | telemetry §4 G6 |
| T.4 | G3 | ✅ **DONE 2026-08-09** — typed core saturation snapshot: event-loop lag and pool acquire-wait; producer send timing; NOTIFY queue usage in DB telemetry. See the superseding metrics-stack remediation record below | telemetry §4 G3 |
| T.5 | G4 | ✅ **DONE 2026-08-09** — periodic-stream jitter fixed and fast per-queue stats SSE added (`/stats/stream`, 200–10000 ms) | telemetry §5 |
| T.6 | G5 | ⏸ **DEFERRED 2026-08-21, NOT A G.1b BLOCKER** — true per-run scoping is substantial cross-module work; retain dedicated-queue-per-run as the current tool-side isolation contract. Full scope, effort, and restart criteria are recorded below. | telemetry §4 G5 |
| T.7 | G7 | ✅ **DONE 2026-08-06** (module-verified; the `peegeeq-db` blocker noted here was ~~unresolved~~ **fixed in `ed2e3d00`, the same commit — confirmed 2026-08-08, see the T.7 record below**) — `GET /api/v1/setups/{setupId}/db-telemetry` returns one snapshot: per-table `pg_stat_user_tables` + `pg_statio_user_tables` + size stats for every table in the setup's schema, plus the cluster signals (long-txn/`xmin`, locks, WAL, checkpoints, xid-age, commit/rollback/deadlock). Errors surface as 404/503 — never fabricated zeroes. Evidence: `peegeeq-rest` core 172/172, integration 332/332 (`logs/peegeeq-rest-*-20260806.txt`) | telemetry §4A |

### T.6 — per-run telemetry scoping — DEFERRED 2026-08-21

**Decision.** Do not implement T.6 until concurrent generator runs sharing one queue are a
demonstrated operating requirement. G.1b and the comparison tool already report their actual
scope honestly. For isolated attribution today, use a dedicated queue per run. This is an
explicit workaround, not a claim that queue-wide or lifetime metrics are run-scoped.

**Why this is not a small UI change.** The generator owns a `runId`, but the current publish
contract sends `correlationId`, not a dedicated run identity. Correlation IDs also become
per-message in trace-seed mode, so they cannot safely double as the run key. Core metrics are
per-instance/per-topic Micrometer meters, REST exposes queue snapshots and streams, and PostgreSQL
`pg_stat_*` counters are table-wide. Filtering the UI after collection cannot separate two runs
whose activity was aggregated before it reached the browser.

**Effort estimate** (one engineer, targeted edit-test loops, excluding the approximately
90-minute owner-run release gate):

| Option | Estimate | Delivered scope |
|---|---:|---|
| Preserve and make the dedicated-queue contract more explicit in the UI | 0.5–1 day | Guidance/validation only; no backend telemetry change |
| True run-scoped application telemetry | 6–9 engineering days | Run-scoped send/claim/process/failure/DLQ counts and latency distributions through API, DB metrics, native, outbox, REST/SSE, and utilities UI |
| Application telemetry plus genuinely run-scoped PostgreSQL churn | 10–15 engineering days | The above plus new application-maintained database accounting or schema/index work; `pg_stat_*` cannot provide this partition by itself |

These are planning estimates, not commitments. Re-estimate after the first design/reproduction
phase if the existing message metadata or storage paths have changed.

**Required design constraints if resumed:**

1. Add an explicit run identifier to the publish/message contract or a formally reserved header;
   do not overload `correlationId`.
2. Keep run state bounded and expiring. Do not add arbitrary run IDs as permanent Micrometer tags:
   that creates unbounded meter cardinality and retains completed runs indefinitely.
3. Define lifecycle semantics before implementation: registration, first accepted message,
   completion, expiry, late messages, retries, DLQ transitions, and process restart.
4. Instrument both native and outbox paths at equivalent send, claim, process, failure, and DLQ
   boundaries so a comparison does not measure two different contracts.
5. Extend typed API statistics and REST/SSE contracts with explicit run selection and the existing
   absent-means-unmeasured rule. Unknown, expired, or unavailable run telemetry must not become
   fabricated zeroes.
6. Pass the run identity through the generator, ramp, comparison, and scheduled-run paths, then
   consume the scoped stream/snapshots in the attribution reports.
7. Treat database churn separately. Queue-table `pg_stat_*` deltas remain run-window observations
   unless the larger database-accounting option is deliberately selected.

**Likely module scope:** `peegeeq-api`, `peegeeq-db`, `peegeeq-native`, `peegeeq-outbox`,
`peegeeq-rest`, and `peegeeq-utilities-ui`, plus their existing same-area tests and telemetry
documentation.

**Minimum acceptance criteria if resumed:**

- two concurrent runs on the same queue report separate counts and latency sample populations,
  with no cross-run leakage;
- native and outbox expose the same run-scoped fields and lifecycle semantics;
- retries and DLQ transitions remain attributed to the originating run;
- expired/unknown runs are explicit absence or a typed not-found response, never zero-filled data;
- retention is bounded and a contract test proves completed runs are evicted;
- queue-wide endpoints remain backward compatible and clearly labelled as queue/lifetime scope;
- the utilities UI names the observed scope in every report and continues to surface telemetry
  failures rather than dropping them;
- each changed Java reactor slice receives its mandatory clean rebuild followed by targeted core
  and Testcontainers integration tests; the UI receives a production build, focused Vitest, and
  a real-backend Playwright scenario with two overlapping runs on one queue.

**Restart trigger.** Reopen T.6 only when users need concurrent same-queue runs, an automated
environment cannot allocate a dedicated queue, or a downstream consumer requires run-isolated
telemetry through the API. Otherwise the dedicated-queue contract remains the lower-risk choice.

### T.4 — historical first increment, 2026-08-08

> **Superseded 2026-08-09 by the metrics stack review below.** Both fields shipped under this
> record are collector-produced, which violates the architecture rule the owner stated on
> 2026-08-09 (core produces; REST only collects). `eventLoopLagMs` additionally samples the WRONG
> Vert.x — the REST server's, not the per-setup manager loops that carry queue work. The code is
> still in place; re-homing it is remediation step 1.

**Shipped:** `eventLoopLagMs` on the `/sse/metrics` + `/ws/monitoring` payload
([SystemMonitoringHandler](../../peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/SystemMonitoringHandler.java)).
A periodic timer measures how late it actually fires; that drift is the loop's unavailability.
This is the G3 signal no other field can stand in for — a blocked event loop reports normal memory,
normal threads and a normal pool while everything queues behind it.

**MAX since the previous read, not a mean.** Saturation is spikes; a mean over the collection window
averages them away, so the number would read healthy at the moment it matters. Read-and-reset, not a
running maximum — a running maximum would pin the payload to the worst moment since startup for
ever, so one transient stall would make the stream read saturated permanently.

**Absent until sampled — and that is what makes the test real.** The first version emitted `0.0`
by default and its test asserted presence and non-negativity. A mutation probe deleting the sampler
entirely **passed**: `0.0 >= 0` holds whether or not anything ever measures. The field is now
OMITTED until a measurement exists, matching the T.1/T.3/T.7 absence-not-zero contract, and the same
probe now fails on `eventLoopLagMs must be present`. A zero would have asserted a perfectly
responsive event loop on the evidence of nothing.

**Verified:** `SystemMonitoringHandlerTest` **19/19** (`-Pall-tests -pl :peegeeq-rest`); mutation
probe applied, observed red, reverted. Sampler timer cancelled first in `close()`.

**Also shipped: NOTIFY backlog (2 of 4).** `notifyQueueUsage` — `pg_notification_queue_usage()`
riding on the existing per-setup `pg_stat_activity` read rather than a second round trip. The native
queue signals consumers with `NOTIFY`; when that fixed-size instance-wide buffer fills, `NOTIFY`
blocks committing transactions, which presents as write latency with no cause visible in any
app-level counter. **MAX across setups, not a sum** — the figure is instance-wide, so setups sharing
a server report the same value and summing would multiply it. **Omitted when no setup could be
read**: the surrounding failure path fabricates zeros for the pool counts (the TYPED-ERASURE the
`.recover()` audit catalogues) and extending that here would report an empty NOTIFY queue for a
server nobody could reach. `SystemMonitoringHandlerTest` **20/20**; probe (merge removed) detected,
though as a 34 s timeout rather than a clean assertion failure — detection works, diagnostics are
poorer than the lag test's, and that is not yet understood.

**BLOCKED — the remaining 2 of 4 need a decision, not just code.** DB write latency and pool
acquire-wait can only be measured inside `peegeeq-db`, and **the registries are not shared**:
`PeeGeeQRestServer` builds its own `PrometheusMeterRegistry`, while `PeeGeeQDatabaseSetupService`
constructs a **fresh `SimpleMeterRegistry` per manager** (lines 238, 373). Anything recorded in the
DB layer is therefore invisible to the monitoring payload the other two G3 metrics landed in. This
is why T.1/T.2 surfaced their percentiles through `/stats` on `QueueHandler` and not through the SSE
stream. Three routes, none free:

1. **Thread the REST registry into the managers** the setup service creates — changes a core class
   every module depends on.
2. **Surface via `/stats`**, following the proven T.1/T.2 path — consistent, but splits the four G3
   signals across two endpoints, so a saturation view has to join them.
3. **Expose a metrics accessor per setup** and have the REST layer pull at collection time.

Route 2 matches precedent; route 1 is the only one that puts all four G3 signals in one payload.
~~**Not chosen — recorded for the next session rather than decided unilaterally.**~~ *(Decided
2026-08-09 by the owner's architecture rule — see the review below. Route 1 is dead: injecting the
REST registry into the managers makes metric production depend on the collector. The compliant
shape is route 3 restated: core produces into manager-owned registries and exposes typed
snapshots; REST pulls at collection time, exactly as `/stats` already does.)*

### Metrics stack review — 2026-08-09

**Trigger.** T.4 was being built collector-first: two G3 metrics were added inside the REST layer.
The owner then stated the rule: **the core produces all metrics unconditionally; the REST layer is
an optional, stateless, idempotent collector.** Reading state PostgreSQL itself maintains
(`pg_stat_*`, `pg_notification_queue_usage()`) is collection, not production.

**Method.** 10-agent audit: 6 parallel mappers (registry topology, peegeeq-db production,
messaging-module production, REST exposition field-by-field, persistence/lifecycle, consumers),
3 adversarial verifiers instructed to refute each load-bearing claim with file:line, 1 coverage
critic. Static reading only — no runtime probe was executed, and no behavioural claim below should
be treated as runtime-verified.

**The one correct seam, and the template for everything else.** T.1/T.2, verified end to end:
consumers record delivery latency at claim and processing time at ack into the manager-owned
registry → `PeeGeeQMetrics.percentilesFor` snapshots the identical timer → `QueueFactory.getStats`
returns typed `QueueStats`/`DurationPercentiles` (absence-not-zero) → REST `QueueHandler` flattens
and adds nothing. Production does not depend on REST. Every finding below is a deviation from this
shape.

**Findings (adversarially confirmed unless marked):**

| # | Finding | Evidence |
|---|---|---|
| 1 | **Two disjoint metric worlds.** `GET /metrics` scrapes only REST-process meters (JVM binders, HTTP timers, monitoring self-metrics). Every core meter lands in a per-setup `SimpleMeterRegistry` no exporter or production caller ever reads. The accessors exist — `PeeGeeQManager.getMeterRegistry()`, `PeeGeeQMetrics.getRegistry()` — with **zero production callers**. The Spring examples prove the injectable bridge works (`PeeGeeQConfig.java:80-83`) | `PeeGeeQRestServer.java:120,539-542`; `PeeGeeQDatabaseSetupService.java:238,373`; `PeeGeeQManager.java:543` |
| 2 | **Dead instrumentation inside core.** The connection-acquisition Timer is registered and **never fed**; the pool active/idle/pending gauges permanently report 0; nothing times DML client-side; `peegeeq.messages.retried` is never incremented; `ConsumerGroupMetrics` is test-only; `CompletionTracker` is never constructed; `recordDatabaseOperation` has zero callers. Two of T.4's four rows are therefore "feed the existing meter", not "add a metric" | `PeeGeeQMetrics.java:148` + verifier confirmations |
| 3 | **`persistMetrics` fails every interval, forever, in setup-service-provisioned schemas** — `queue_metrics` is not in the provisioned DDL. Where the table does exist it is write-only (no production reader; the retention function is defined and never invoked, so it also grows unboundedly) | `PeeGeeQMetrics.java:668-706`; `PeeGeeQManager.java:855-874` |
| 4 | **`messagesPerSecond` is three different quantities under one name.** SSE/WS: REST-held delta of the summed PENDING counts — backlog growth, clamped to ≥ 0 (reads 0 during steady consumption), reset on REST restart, divergent per connected client. Management endpoints: lifetime enqueue average. Neither is throughput. Stream `totalMessages` is the summed pending backlog, misnamed | `SystemMonitoringHandler.java:97-98,633-638,725-742,1043-1052` |
| 5 | **The two 2026-08-08 G3 fields are collector-produced.** `eventLoopLagMs` samples the REST server's Vert.x; the per-setup loops that actually carry queue work are never measured, and the metric ceases to exist when REST is down — wrong producer AND wrong loop. `notifyQueueUsage` is a compliant SQL read, but its natural home is db-telemetry's cluster block beside the other Postgres cluster signals. **Zero consumers exist for either field** | `SystemMonitoringHandler.java`; consumers map |
| 6 | **Vert.x built-in Micrometer support is unused at every `Vertx` construction site.** It provides pool and event-loop metrics natively | no `MicrometerMetricsOptions` in any src/main |
| 7 | **Fabrications.** Per-queue `errorRate` hardcoded `0.0` (`ManagementApiHandler.java:378`). The performance-test-harness returns hardcoded constants after `Thread.sleep` as if measured (`NativeQueuePerformanceTestSuite.java:107-157`) — fabricated performance results | critic, file-verified |
| 8 | **Production gaps and orphaned families.** peegeeq-bitemporal produces zero meters (log-only `SimplePerformanceMonitor`). Notice metrics, resilience4j circuit-breaker metrics, client/pool lifecycle counters all exist — and all land in the same unexported registries | `PgBiTemporalEventStore.java:111`; `CircuitBreakerManager.java:68-70` |
| 9 | **Collector-on-collector.** service-manager federates `/api/v1/management/metrics`, itself a REST-held TTL cache. And management-ui's `managementStore` reads `monitoringSessions`/`dbPool` from `/management/overview`, an endpoint that does not emit them | `FederatedManagementHandler`; `ManagementApiHandler.java:482-563` |

**Claims the verifiers corrected (kept per working agreement #6):** "no scrape route exists" —
REFUTED, `GET /metrics` exists and serves the REST registry; "the manager registry is unreachable,
no getter exists" — PARTLY, the getters exist with no callers; "nothing in the entire repository
reads queue_metrics" — PARTLY, one unit test reads a row count.

**Target architecture (the rule applied):**

1. **Production in core, unconditionally.** Every measurement lives in the module that owns the
   measured thing: per-setup event-loop lag sampled by `PeeGeeQManager` on its own Vert.x;
   acquire-wait by feeding the already-registered timer at the acquisition site in
   `PgConnectionManager`; write latency at the client DML seam. Registries stay manager-owned and
   injectable (the Spring examples are the proof it works).
2. **Typed snapshot hand-off.** `QueueStats` is the precedent. A saturation snapshot accessor
   beside it carries the G3 signals. Absence-not-zero throughout.
3. **REST holds no metric state.** Rates are derived by the consumer from two samples (the §4A
   baseline-and-delta discipline the db-telemetry tools already follow), never from REST-held
   `prev*` fields.
4. **`GET /metrics` stays honestly scoped** to REST-process health unless bridging core registries
   into it becomes a decided requirement.

**Remediation order (replaces the T.4/T.5 plan of record):**
1. ✅ **DONE 2026-08-09** — Re-home event-loop lag (record below).
2. ✅ **DONE 2026-08-09** — `notifyQueueUsage` moved into db-telemetry's cluster block (record below).
3. ✅ **DONE 2026-08-09** — acquisition canary + fabricated gauges deleted (record below).
4. ✅ **DONE 2026-08-09** — producer send timing at every send site (record below).
5. ✅ **DONE 2026-08-09** — persistMetrics deleted; rate semantics corrected (record below).
6. ✅ **DONE 2026-08-09** — jitter phase fix + fast per-queue stats stream (record below).

### Remediation steps 3–6, 2026-08-09

**Step 3 — pool acquire-wait is now measured; the fabricated gauges are gone.**
`PeeGeeQManager` runs a 5 s CANARY: one real, timed `getConnection()` against its own pool, with
an overlap guard so a queued canary never stacks a second behind it. Under exhaustion the canary
queues behind real work, so it measures exactly the wait a caller experiences. It feeds both the
already-registered `peegeeq.connection.acquisition.time` timer and a second rolling window on the
saturation snapshot — `SetupSaturationSnapshot` now carries `Window eventLoopLag` and
`Window poolAcquireWait` (shared nested `Window` record; each absent independently, since the
samplers run on different cadences). REST's `mergeSaturation` surfaces both per setup plus
worst-across-setups headlines (`eventLoopLagMs`, `poolAcquireWaitMs`). A failed canary acquisition
is logged with the time it waited and NEVER recorded as a sample — the wait ended in refusal, not
acquisition. The three `peegeeq.connection.pool.*` gauges, `updateConnectionPoolMetrics`, and the
`MetricsSummary` connection fields were **deleted**: nothing ever fed them, so they permanently
reported 0 — a fabricated healthy signal. Their ABSENCE is now pinned by
`connectionPoolGaugesAreNotFabricated` in both metrics test classes, so re-registering them
without a real feed fails the build.

**Step 4 — every producer send is timed.** Native (2 sites) and outbox (3 sites, via
`logSendOutcome`) time submission-to-completed-INSERT — the write latency the caller experiences —
and feed `peegeeq.message.send.time` through `recordMessageSent(topic, durationMs)`. That overload
existed since T-era with ZERO production callers because it was never on the `MetricsProvider`
interface; it is now on the interface as an **abstract** method (the ServiceProvider precedent: a
default delegating to the untimed overload would let an implementation silently drop the duration
by forgetting to override). `ConsumerModeMetricsTest` pins that every send contributes a timed
sample.

**Step 5 — the fail-forever loop and the dead rate state are gone.**
- `persistMetrics`/`persistCounter` and their manager timer are **deleted**: queue_metrics was
  write-only (no production reader in the repo) and absent from setup-provisioned schemas, so the
  timer failed every interval forever at ERROR level. `PeeGeeQMetricsLogLevelTest` (whose whole
  subject was those log levels) deleted with it; the timer-guard race test retargeted to the
  depth-cache task. The `queue_metrics`/`connection_pool_metrics` DDL still exists in migrations —
  recorded as debt, removable when migrations are next touched.
- The handler-level `prevTotalMessages` delta in `SystemMonitoringHandler` is **deleted**: every
  streaming path overwrote its output via `withPerConnectionRate` before any client saw it — dead
  state. The PER-CONNECTION delta **stays, reclassified**: it is the §4A consumer-side
  baseline-and-delta held for one client session, and the §8.2 regression test deliberately pins
  its semantics ("0 when nothing changed") — replacing it with core's lifetime average would
  regress exactly what §8.2 was written to prevent. Two honest corrections: the clamp is gone
  (a draining backlog now reads NEGATIVE instead of a fabricated 0), and the javadoc names the
  quantity — a backlog change rate, not throughput. `totalMessages` (really the summed pending
  backlog) keeps its name this pass: renaming breaks management-ui's pinned payload shape, so it
  is recorded as a consumer-coordinated rename, not smuggled in.

**Step 6 — the cadence blockers found by the T.5 probe are fixed, and G4 has its stream.**
- Jitter at all three `/sse/metrics`/WS sites is now a ONE-OFF PHASE offset
  (`setPeriodic(initialDelay, period)`), never an addition to the period — the probe measured the
  old form delivering 0.52 Hz against a 1 Hz request.
- New `GET /api/v1/queues/{setupId}/{queueName}/stats/stream?intervalMs=1000` (clamp 200–10000,
  default 1000): per-queue SSE built on the typed core seam. Each tick is a fresh idempotent read
  (`isHealthy` + `getStats`) flattened by `QueueHandler.queueStatsJson` — extracted as the ONE
  owner of the flat shape, so the stream and `GET .../stats` cannot drift and utilities-ui's
  parser holds for both. In-flight tick guard (a slow DB must not turn the observer into load); a
  failed read ENDS the stream with a stated `error` event — a stream that silently stopped
  sampling would read as a healthy queue with frozen numbers. `QueueStatsStreamIntegrationTest`
  pins cadence (≥ 8 frames in 3 s at 250 ms), the shared shape, freshness (mid-stream sends appear
  in later frames), and 404 for unknown queue/setup.

**Evidence (scoped runs; the whole-repo gate follows separately):** core
`SaturationSnapshotIntegrationTest` 4/4; `PeeGeeQMetricsCoreTest`+`PeeGeeQMetricsTest` 49/49;
native `ConsumerModeMetricsTest` 3/3; REST `QueueStatsStreamIntegrationTest` 4/4 +
`SystemMonitoringHandlerTest` 19/19 + `DatabaseTelemetryHandlerIntegrationTest` 4/4;
banned-pattern grep clean across all touched files. Mutation probes recorded beside this entry.

**Known gaps, stated:** (1) no automated pin for the jitter phase fix — the discriminating test
is flaky by construction (it races real timers), so the live probe measurement stands as the
evidence and the fix is three mechanically-identical lines; (2) outbox send timing has no
outbox-side e2e pin (the native pin + the interface-level `testRecordMessageSentWithDuration`
cover the mechanism; an outbox equivalent of the native pin is the cheap follow-up); (3) the
signed rate is a semantic change management-ui will now display during drain (negative values) —
its Overview consumes the field, and that display decision belongs to management-ui.

### The `-Pall-tests` gate — GREEN, 2026-08-09

**Every module passed under `-Pall-tests`.** Run as `mvn clean test -Pall-tests` plus the doc's own
`-rf` resume form after each failure was fixed — five resumes, logs
`logs/all-tests-20260809.txt` + `all-tests-resume{2..6}-20260809.txt`. Every module's pass
postdates every change that touches it. Full-suite figures where the log states them:
`peegeeq-native` 374 (6 skipped), `peegeeq-rest` 3:34, management-ui e2e **482** (E5 rewritten),
utilities-ui unit **826** + e2e **91/91**. Stated honestly: this is a resume CHAIN, not one
uninterrupted invocation; a ceremonial single pass is the user's call.

**Five test defects surfaced by the gate and fixed en route — none in the remediation code:**

1. `PostgreSQLErrorHandlingTest` (native): teardown budget of 30 s EQUAL to closeReactive's
   internal worst case — failed identically under `-Pall-tests` on **2026-05-23** and 2026-08-09,
   passes in isolation both times. Budget aligned to 60 s; the slow-close mechanism under load is
   recorded as undiagnosed.
2. `ListenNotifyOnlyEdgeCaseTest` (native): a STRICT single-flag checkpoint flagged on every
   delivery with three messages in flight and an "at least 1" assertion — it errored precisely
   when delivery worked. Guarded on the first delivery (its sibling test's own pattern).
3. `ManualHealthCheckTest` (service-manager): hardcoded ports 8090–8092 collide with Docker
   Desktop's backend — and Docker must run for TestContainers, so the collision was structural.
   Rewritten on ephemeral ports (`listen(0)` + `actualPort()`); the test that held a fixed port
   for 10 s of wall-clock per run now asserts the start/verify/close lifecycle.
4. `api-error-paths.spec.ts` E5 (management-ui): asserted a "Delete" dropdown flow the guarded
   detach/drop rework (`5a1755de`, 2026-07-19) removed — SEVEN WEEKS of asserting a dead flow.
   Rewritten against the real destructive path: Drop Database… → type-to-confirm guard (button
   disabled until the exact name) → stubbed 503 → server error text surfaces, modal stays open.
   Note: the 2026-08-06 "whole-repo gate green" record cannot be reconciled with this spec's
   state; either that run skipped the management-ui e2e leg or the record was wrong.
5. `compare.spec.ts` + `CompareTargets.tsx` (utilities-ui): the compare setup Select had no
   `showSearch`, and antd VIRTUALIZES long option lists — under `-Pall-tests`, thirteen prior
   projects' accumulated setups pushed the spec's own setup out of the rendered DOM entirely.
   The documented fix for this exact class (the management-ui `SetupScopeBar` precedent):
   `showSearch` + `optionFilterProp` on the component, type-to-filter in the spec.

### The five recorded gaps — closed, 2026-08-09

1. **Jitter cadence pin (was "flaky by construction" — that claim was wrong).**
   `SseMetricsCadencePinTest` (CORE, no DB: `ControllableSetupService.defaults()` reports zero
   setups): the handler is constructed with `jitterMs = 5000` against a 1 s interval, and the
   assertion covers only PURE-PERIOD gaps (the immediate initial send and the phase-offset first
   gap are excluded). Fixed world ~1 s, period-bug world ~6 s, threshold 3.5 s — wide of both.
   Mutation probe (SSE site reverted to period-added jitter) failed with "Periodic gap of
   3935 ms … jitter is inflating the PERIOD again". Deterministic where the naive test races
   real timers.
2. **Outbox send-time pin.** `OutboxMetricsTest` now asserts every outbox send contributes a
   timed `peegeeq.message.send.time` sample, mirroring the native pin. Probe (timing reverted in
   `logSendOutcome`) red on "must be fed by the outbox producer send path". 4/4.
3. **Stream field renamed: `totalMessages` → `totalPendingMessages`.** The value was always the
   summed PENDING backlog; the old name read as a lifetime total. Renamed through the handler,
   `withPerConnectionRate`, the per-connection seeding, and the §8.2 regression test. The
   backfill entries' own `totalMessages` (a genuine total) keeps its name. management-ui's
   store field `totalMessages` was DELETED — nothing rendered it, and its two sources carried
   two different quantities.
4. **The rate is single-sourced and the tile names its quantity.** The Overview tile (was
   "Messages/sec") is now **"Backlog change"**: it shows the stream's signed per-session backlog
   change rate, and the HTTP overview's lifetime enqueue average is NO longer mapped into the
   same store field — one source, one meaning; the tile reads 0 until the first stream frame.
   Both overview e2e title pins updated. mgmt-ui tsc clean, store tests 23/23.
5. **The dead DDL is gone.** `V019__Drop_Unused_Metrics_Tables.sql` drops `queue_metrics`,
   `connection_pool_metrics` and the never-invoked `cleanup_old_metrics` function (Flyway
   discipline: a new migration, never an edit to applied V001). Both migration tests now assert
   their ABSENCE (the sweep initially missed the FUNCTIONS list — the migrations suite caught
   it; 53/53 after). The dead `SchemaComponent.METRICS` (zero external users) and three inline
   `CREATE TABLE queue_metrics` test scaffolds in peegeeq-db are removed. peegeeq-db affected
   classes 9/9.

   Still true and recorded: the undiagnosed slow-close-under-load mechanism behind the
   `PostgreSQLErrorHandlingTest` teardown boundary remains open — it is a production-lifecycle
   diagnosis, not a metrics gap.

### Review backlog, item 1: peegeeq-performance-test-harness DELETED, 2026-08-09

The metrics-stack review's critic found that **every figure the module reported was a hardcoded
constant returned after `Thread.sleep`** — 10000.0 notifications/sec, 2.5 ms latency, 7936.51
queries/sec, all invented, piped through a report generator as measurements. `Thread.sleep` is
itself a banned pattern. Verified before deleting: no suite touched a database, queue, or pool;
the only consumers were the root reactor and the coverage-report aggregation; real load tests
already exist in the modules under `-Pperformance-tests` (the peegeeq-db fanout suites among
them). Per the delete-dead-mechanisms rule the module is gone — reactor entry, coverage-report
dependency, directory (38 files), and the commands doc's reference (dated correction). Reversible
via git if a real harness is ever built; it returns only measuring something.

### Review backlog, item 2: errorRate is now a real figure, 2026-08-09

`ManagementApiHandler`'s queue listing put a hardcoded `errorRate: 0.0` beside real statistics —
two management-ui views rendered "0.00%" regardless of failures. It is now
`errorRateFrom(stats)` = the **dead-letter fraction** (terminally failed / seen), deliberately
NOT `1 - successRatePercent`: that formula counts PENDING messages against success, so a healthy
queue with a backlog would read as erroring — `ManagementApiErrorRateTest` pins exactly that
case (80 pending, 0 dead-lettered → 0.0). TDD: compile-level red (seam absent) → green 3/3;
integration 38/38; probe (fabrication reintroduced) red. The listing block also consolidated
three per-queue stats queries into one `getStats` read.

*(Correction 2026-08-09, later the same day: statements in items below that called
peegeeq-rest-client a "zero-test module" were WRONG. It has 5 test files / 48 tests (33
core + 15 integration). A stale `target/test-classes` made incremental scoped runs report
"Tests run: 0" — "Nothing to compile - all classes are up to date" over stale class files;
`mvn clean test` runs all 33 core tests. The true statement: none of its tests cover the
management getters, which is why those survived broken. The stale-target trap the
test-commands doc documents for the gate applies to scoped runs too.)*

### Review backlog, item 3: aggregate messagesPerSecond deleted, store clobber fixed, 2026-08-09

Four defects in one family — a lifetime average dressed as a rate, and clients reading fields
endpoints do not emit.

1. **Aggregate `messagesPerSecond` DELETED at all three REST emit sites** (overview
   `systemStats`, per-setup summaries, `/management/metrics` cache). The value summed per-queue
   `total/(last−first)` lifetime averages (PgNativeQueueFactory) — not a rate of anything. The
   live system rate is the monitoring stream's backlog change rate; one mechanism. Per-queue
   `messageRate`/`statistics.messagesPerSecond` KEPT — a real per-queue quantity with a pinned
   contract. Absence pins added to both overview tests and the metrics test.
2. **FederatedManagementHandler stopped re-aggregating it** (overview + metrics paths,
   per-instance entries + `totalMessagesPerSecond`). Summing the `getDouble` defaults after
   step 1 would have fabricated zeros.
3. **managementStore clobber fixed.** Every HTTP overview poll replaced the whole `systemStats`
   object, zeroing four stream-owned fields (`messagesPerSecond`, `monitoringSessions`,
   `activeSubscriptions`, `dbPool`) until the next frame — gap D's own fix had this bug (it
   wrote a literal 0). It also pushed a chart point built from the clobbered data. New pure
   `mergeOverviewSystemStats(current, overviewStats)` maps only what the endpoint emits and
   preserves stream-owned fields; the chart call in `fetchSystemData` is removed (stream frames
   are the only chart source). Three new vitest tests.
4. **rest-client dead read fixed.** `getQueues()` read flat `messagesPerSecond`, which the
   endpoint has never emitted — every `QueueInfo.messagesPerSecond` was the 0.0 default. Now
   reads `messageRate`.

TDD: TS red (missing export, 3 failed) → green 26/26. Java red (both overview absence pins,
38 run / 2 failures) → green 38/38. Probes: all three Java pins red under reintroduced
fabrication — the metrics pin was TOOTHLESS on first probe (single re-read raced the async
cache fill) and was strengthened to poll until the fill lands before asserting absence; store
probe (clobber reintroduced) 2 red. All restored, final greens: rest 38/38, service-manager
core 56/56 + integration 32 run 0 failures (15 pre-existing conditional skips), rest-client
compiles (module has zero tests), vitest 26/26, tsc clean.

**Not checked**: federation aggregation output shape has no test anywhere — the
FederatedManagementHandler deletions are compile-verified only. The rest-client `messageRate`
fix is unverified by any test (zero-test module). management-ui e2e specs not run this pass.
**Found, not fixed** (recorded for the backlog): rest-client `getSystemOverview()` parses into
a `SystemOverview` dto whose fields are disjoint from the real payload (`totalEvents`,
`uptimeSeconds` — never emitted) using a default `ObjectMapper`, which rejects unknown
properties — by static reading it should fail on every call; not verified by execution.

### Review backlog, item 4: dead/unfed meters deleted (mechanical tier), 2026-08-09

A 6-agent verification workflow enumerated every definition, caller, and payload reference
before any deletion (verdicts with file:line evidence; ~400k tokens). Deleted — each confirmed
with zero production callers:

1. **`peegeeq.messages.retried` chain**: PeeGeeQMetrics field + registration +
   `recordMessageRetried`, the `MetricsProvider` abstract method, NoOp + PgMetricsProvider
   overrides, three test methods. The counter permanently scraped as 0; native retry
   accounting lives in the `retry_count` column and never called this seam.
2. **`ConsumerGroupMetrics`** — entire class + its integration test file + two dependent test
   methods in RemainingPrometheusMetricsIntegrationTest (+ orphaned helper/imports). Never
   constructed in production; its eight `peegeeq.subscriptions.*`/`peegeeq.detection.*`/
   `peegeeq.blocked.messages` meters existed only inside its own tests. Follow-on orphan
   `DeadConsumerDetectionJob.getTotalRunTimeMs()` deleted (field kept — feeds the run-summary log).
3. **`recordDatabaseOperation`** + `databaseOperationTime` timer + registration + two tests.
4. **`getQueueDepth(String topic)`** from MetricsProvider + all three implementors + its test —
   every implementation ignored the topic argument; `cachedNativeDepth` machinery KEPT (feeds
   the live `peegeeq.queue.depth.*` gauges).
5. **Sweep extras the audit missed** (completeness agent): `recordMessageReceived(String,long)`
   overload and `recordMessageAcknowledged` (zero callers anywhere, including tests — their
   timers were never even registered), `recordMessageSendError`/`ReceiveError`/`AckError`
   wrappers (test-only), `getRegistry()` (zero callers).

New regression lock `deadMetersAreNotFabricated` pins the ABSENCE of the retried counter and
database-operation timer (same pattern as `connectionPoolGaugesAreNotFabricated`). Probe:
re-registering the retried counter → red on exactly that pin → restored. Greens: api + db
install, all seven dependent modules compile, four touched suites 59/59 (surefire's per-class
counts scramble under parallel methods — the XML method lists were verified instead: zero
deleted methods executed). Superseded handoff docs PHASE_G2/PHASE_T deleted.

### Review backlog, item 5: the three deferred deletions executed, 2026-08-09

User ruling: none of the three are part of the metrics design — delete.

1. **Generic `MetricsProvider` quartet DELETED** (`incrementCounter`, `recordTimer` both
   overloads, `recordGauge` + the DynamicGaugeEntry/GaugeKey machinery, `getAllMetrics`) from
   the interface and all three implementors, plus their tests. The interface now carries
   exactly the message-lifecycle signals producers and consumers feed. `TestSupportMetrics`
   (peegeeq-test-support) is independent and untouched.
2. **`SystemStatus` + `getSystemStatus()` + `BackpressureManager` DELETED** (class, field,
   construction, accessor, BackpressureManagerTest). Seven test files across four modules
   rewired to assert the same live facts through direct accessors (`isStarted()`,
   `getHealthCheckManager().getOverallHealthAsync()`, `getMetrics().getSummary()`,
   `getDeadLetterQueueManager().getStatistics()`); backpressure-demo test methods deleted with
   dated comments. An outbox test comment that blamed a teardown hang on "the
   BackpressureManager permit" was corrected — nothing ever passed through that manager, so
   the attribution was disproven, not just stale. `getSummary()`/`MetricsSummary` KEPT: it
   reads real counters and many tests verify alive meters through it.
3. **`CompletionTracker` RELOCATED to test scope** (peegeeq-db/src/test, same package), its
   metrics constructor and `peegeeq.completions.total` counter deleted with the move.
   Production now honestly has no REFERENCE_COUNTING completion writer (it never had a wired
   one); the six suites that use the tracker as a harness to drive the alive readers
   (fetcher, retry, cleanup) compile unchanged against the fixture. The fixture's javadoc
   forbids silent re-promotion. RemainingPrometheusMetricsIntegrationTest deleted entirely
   (both surviving tests tested the deleted meter).

Verification: repo-wide sweep zero references to any deleted API outside dated comments; all
modules test-compile; suites green — peegeeq-db 89/89 (manager integration, metrics core ×2,
examples ×2, CompletionTracker fixture ×2, tracing, fetcher harness), peegeeq-native 18/18,
peegeeq-examples 2/2.

*(Correction 2026-08-10: "all modules test-compile" was FALSE for peegeeq-runtime — the
check ran without `clean` and incremental compilation skipped a stale test class. The user's
`mvn clean test` caught it: RuntimeDatabaseSetupServiceTest's anonymous DatabaseSetupService
was missing getSaturationSnapshotForSetup — an implementor missed by the remediation because
the sweep matched "implements ServiceProvider", not DatabaseSetupService. Fixed (null
override, fake precedent); corrected implementor sweep balances across all nine files; the
resumed clean core run is green across all 12 remaining modules. Third stale-target incident
this session — compile checks and scoped runs both require `clean` to be evidence.)* Three example-file rewires were done by parallel subagents with
signature-verified accessor mappings and banned-pattern sweeps; their reports flagged
pre-existing antipatterns (timer-as-readiness-guard, narration logging, fire-and-forget DLQ
moves in example tests) which were left in place — recorded here as known debt.

**Not run** (performance-tagged, compile-verified only): PeeGeeQPerformanceTest, the P2–P4
fanout suites, FanoutPerformanceValidationTest. **Not checked**: external consumers of the
published peegeeq-api/peegeeq-db artifacts; out-of-repo dashboards scraping the removed series.

### Review backlog, item 6: bitemporal appends now produce metrics, 2026-08-09

The bitemporal module recorded ZERO metrics — a system doing only event-store writes scraped
as completely idle. Every successful append now records through the manager's
`PeeGeeQMetrics` via the existing `recordMessageSent(topic, durationMs)` seam — the same
meters the native and outbox producers feed (`peegeeq.messages.sent`, `.by.topic`,
`peegeeq.message.send.time`), topic = the store's table name. Zero new meter names;
core-produces, no REST involvement.

Instrumented at the four funnels (each covers its overloads and, for single appends, both
the direct and event-bus-distribution branches): `appendOwnTransaction`, `appendCorrection`
(8-arg), `appendInTransaction` (8-arg, records the INSERT's completion — the caller-owned
transaction may still roll back afterwards, same accepted semantics as the outbox in-tx
send), `appendBatch`. Batch semantics: counter counts EVENTS (N), timer gets ONE sample —
the single measured write; N identical samples would fabricate distribution mass. Failed
appends record nothing (producer precedent). `MetricsProvider` field is null-safe to
`NoOpMetricsProvider.INSTANCE`.

TDD: `BiTemporalAppendMetricsIntegrationTest` (4 tests: absence-before/presence-after,
batch N-counter/1-timer, correction records, closed-store failure records nothing) written
first → red 3/4 ("must feed ... expected: not null") → impl → green 4/4 → probes: recording
bypassed (2 reds) + timed-per-event (timer pin red "expected 1 but was 5") → restored →
green 4/4 → full module regression `-Pintegration-tests -pl :peegeeq-bitemporal` 352/352.
Banned-pattern sweep clean.

**Not checked**: the subscribe/consume path still records nothing (recordMessageReceived/
Processed for bitemporal subscriptions — follow-up decision); query-path metrics deliberately
not added (no consumer; accretion).

### Review backlog, item 7: rest-client management getters fixed against the real contracts, 2026-08-09

The zero-test module's four management getters were written against an imagined contract —
every call failed against a real server: `getSystemOverview()` strict-Jackson-parsed the
whole body into a dto with fields the endpoint never emits (`totalEvents`,
`deadLetterMessages`, `systemStatus`, `uptimeSeconds`); `getQueues()`/
`getConsumerGroups()`/`getEventStores()` called `bodyAsJsonArray()` on endpoints that wrap
their arrays in objects (`{queues: [...]}` etc.).

Fixes: `SystemOverview` record reshaped to mirror the `systemStats` block one-to-one
(totalSetups/Queues/ConsumerGroups/EventStores, totalMessages, activeConnections, uptime;
dead `empty()` deleted — zero callers); `getSystemOverview` maps by hand from
`systemStats`; the three list getters unwrap their named arrays.

TDD: new `RestClientManagementContractSmokeTest` (peegeeq-integration-tests, which depends
on both server and client — the module's first client-vs-real-server coverage): overview
cross-checked field-by-field against the raw payload, getQueues verified to return a queue
created through the API with name+setupId mapped, both other getters pinned to unwrap.
Red: compile-level on the two accessors the old dto lacked. Green 4/4. Probes: overview
mapping pointed at the root (red "expected 1 but was 0" on a real setup count) + getQueues
reverted to `bodyAsJsonArray` (errored) → restored → full smoke suite 79/79.

**Not checked**: the ~26 other `parseResponse` call sites (setup/queue-operation dtos) were
NOT audited against their endpoints — same defect class possible; recorded for the backlog.
The unused `mockito-core` dependency in the rest-client pom remains (no-mocking rule makes
it dead weight).

### Review backlog, item 8: full rest-client dto audit — 26 of 44 typed methods MISMATCH, 2026-08-09

A 6-agent workflow audited every typed client method field-by-field against its owning
server handler (~445k tokens; verdicts with file:line evidence). Result: **26 MISMATCH, 18
MATCH** — beyond the four management getters already fixed, most of the client's typed
surface was written against imagined contracts. Defect families:

1. **Non-deserializable core dtos**: `createSetup`/`listSetups`/`getSetup` parse into
   `dev.mars.peegeeq.api.setup.DatabaseSetupResult`, which has NO Jackson creator (parse
   fails at instantiation on every call) and carries live-object fields
   (`Map<String,QueueFactory>`) a wire payload can never hold.
2. **Object-vs-array wrapping** (same family as the fixed management getters):
   `listSetups`, `sendBatch`, `listConsumerGroups`, `listDeadLetters`, `listSubscriptions`,
   and the event-store list shapes.
3. **Enum-vs-object**: `getSetupStatus` Jackson-parses `{setupId, status}` into a bare enum.
4. **Fields never emitted / strict-parse rejections** across consumer-group, subscription-
   options, webhook, and event-store dtos (`appendEvent`, `queryEvents`, `getEvent`,
   `getEventVersions`, `appendCorrection`, `getEventAsOf`, `getEventStoreStats`,
   `streamEvents` all MISMATCH).

MATCH (18): deleteSetup, addQueue, addEventStore, sendMessage, getQueueBindings,
purgeQueue, deleteConsumerGroup, leaveConsumerGroup, deleteSubscriptionOptions,
getDeadLetter, reprocessDeadLetter, deleteDeadLetter, getDeadLetterStats, getSubscription,
pause/resume/cancelSubscription, updateHeartbeat, deleteWebhookSubscription,
deleteEventStore, getGlobalHealth, getMetrics — mostly void/raw-JSON methods that never
typed-parse a response.

**Load-bearing fact**: the module has ZERO in-repo consumers — no production module,
example, or UI uses `PeeGeeQClient`; its only consumer is the contract smoke test added
today. Its 48 existing tests are green but none exercise the typed response parsing of the
26 broken methods. Also done this pass: dead mockito dependencies removed from its pom
(no test referenced them; no-mocking rule).

**DECISION PENDING (user)**: the client is the outward-facing typed library for the REST
API, and fixing the 26 methods requires reshaping public dtos and several `PeeGeeQClient`
interface signatures (e.g. `listSetups` can only honestly return setup IDs; `createSetup`
must return a wire-shaped record, not `DatabaseSetupResult`). Options: (a) fix all 26
against the real contracts with per-group contract smoke tests — the audit's fixScopes
enumerate each change; (b) strip the broken typed surface down to the working 18 + raw
JsonObject accessors; (c) delete the module as unconsumed fiction. Recommendation: (a) —
it is the only programmatic client of the REST API and the repair path is fully enumerated.

### Review backlog, item 9: all 26 rest-client mismatches fixed against the real contracts, 2026-08-10

User ruling: option (a). Executed as five sequential TDD phases (every fix touches
PeeGeeQRestClient.java), each by a subagent with the audit fixScope as spec, server payloads
re-verified in the handlers, red captured where honestly producible, and a new contract
smoke test per group in peegeeq-integration-tests (real server via SmokeTestBase — the
module's first end-to-end typed coverage):

- **A setups**: createSetup→`SetupResultInfo` (old parse failed at instantiation — dto had
  no Jackson creator), listSetups→`List<String>` (endpoint emits ids only), getSetup→new
  `SetupDetailsInfo`, getSetupStatus reads the status field. Red: compile-level.
- **B messages-stats**: sendBatch sends the `{messages:[...]}` wrapper the server requires
  and builds results from `messageIds` (failures fail the future); getQueueStats/
  getQueueDetails manual lenient mapping (`QueueDetailsInfo` reshaped — four fields had no
  source); getQueueConsumers extracts `groupName` from the object items. Three runtime reds
  captured (400 on the bare array; two strict-parse rejections).
- **C consumer-groups + FIX 0**: cross-cutting defect FOUND BEYOND THE AUDIT — Vert.x
  `JsonObject` request bodies serialized through plain Jackson as `{"map":...,"empty":...}`,
  corrupting the request side of ~8 methods including audit-MATCH ones; fixed at the
  request helper (encode() for JsonObject/JsonArray), red captured (400 "Consumer group
  name is required"). Five methods remapped; `ConsumerGroupInfo`/`ConsumerGroupMemberInfo`/
  `SubscriptionOptionsRequest`/`SubscriptionOptionsInfo` reshaped to real contracts,
  fabricating factory methods deleted. Sweep catch: management `getConsumerGroups()` read
  keys the payload never carries and fabricated 0/0/now() per row — remapped honestly.
  Server-truth deviations recorded (FROM_NOW resolves server-side and does not round-trip).
- **D dead-letters/webhooks**: listDeadLetters sends limit/offset (not page/pageSize) and
  parses the bare array (`DeadLetterListResponse` DELETED — server emits no total; returns
  `List<DeadLetterMessageInfo>`); cleanupDeadLetters sends retentionDays as query param and
  FAILS on a missing `messagesDeleted` (the old default-0 read masked the defect);
  listSubscriptions reads the bare array; webhook dtos reshaped (imagined fields deleted;
  `consecutiveFailures` nullable — create omits it). Three runtime reds captured; notable:
  the old webhook create had NO red — strict Jackson silently default-filled the imagined
  fields, the quietest form of the fabrication class.
- **E eventstores**: new concrete `EventInfo` (+`EventAppendResult`, `EventCorrectionResult`)
  replaces parsing into the BiTemporalEvent interface across all eight methods; envelope
  unwraps (`event`/`events`/`versions`/`stats`); query param `asOf`→`transactionTime`;
  request dtos serialize the server's keys (`metadata`, `eventData`, `validFrom` — the
  fixScope's claimed `validTime` alias was DISPROVEN server-side and not used); queryEvents
  now transmits every handler-accepted filter param and fails explicitly on the unsupported
  `headerFilters`; `EventStoreStats` reshaped (four never-emitted fields deleted); SSE
  stream typed to `EventInfo`, control frames skipped. Red: 400 on the old request keys.

Verification: five new contract smoke tests + all regressions green after every phase;
final consolidated runs — **smoke suite 84/84** (79 pre-existing + 5 new), client **33/33
core + 15/15 integration** (clean builds; the stale-target trap avoided with `clean`).

**Not checked** (accumulated from phase reports): getQueueConsumers/DLQ-item/subscription-
item mappings never exercised against non-empty payloads (no REST route populates them in
the smoke flow); streamEvents not runtime-verified (control-frame readiness unobservable
through the typed stream); SSE error frames skipped silently; page>0 offset translation;
webhook delivery timestamps never non-null; sub-second Instant precision from decimal-epoch
payloads (~microsecond, double-parse limit). Stale docs: PEEGEEQ_REST_CLIENT_GUIDE.md still
documents old shapes and shows a banned toCompletionStage example; management-ui/utilities-ui
TS types and peegeeq-openapi yaml mention old dto names (they do not compile against Java).
Pre-existing test debt flagged: PeeGeeQRestClientTest `.onComplete(ar->...)` idiom,
RestClientIntegrationTest hand-rolled container setup.

### SubscriptionOptionsCoreTest rewritten on the real integration pattern, 2026-08-10

Found while classifying the exceptions in the user's core-run log: all nine tests in
peegeeq-rest's SubscriptionOptionsCoreTest hit a hardcoded port with NO server started,
caught the connection failure, logged "skipping assertions" and returned green — permanently
vacuous ("Tests run: 9, Failures: 0" with zero assertions executed). Repo-wide sweep: the
pattern exists nowhere else. Rewritten on the ManagementApiIntegrationTest pattern (real
server + TestContainers + real consumer groups created via REST), INTEGRATION-tagged, all
skip-guards deleted. Two of the old expectations were fiction against the real handler and
are now pinned to reality: update without an existing consumer group is 404 (new test 10),
and delete of a missing subscription is idempotent 204, never 404. 10/10 green; probe
(fictional 404 reinstated) red "expected 404 but was 204" → restored → 10/10.

**Review backlog still open**: rest-client guide/openapi doc refresh; bitemporal
subscribe-path metrics; Vert.x built-in Micrometer unused; the federated
collector-on-collector tier; example-test antipattern debt.

### Timer-guard findings from the user's integration run, 2026-08-10

The changed-module integration run failed on ONE error in 723 peegeeq-db tests:
`PeeGeeQManagerTimerGuardTest.testTimerFailuresEscalateWarnToError` threw
ConcurrentModificationException — the test streamed its `synchronizedList` of captured log
events while the manager's depth-cache timer (production code, still legitimately firing
against the stopped DB) appended concurrently. Fixed: `eventsAtLevel` streams over the
`snapshot()` copy; assertions are threshold-based so a still-growing capture is sound.
5/5 green. The remaining six modules were reactor-SKIPPED, not failed.

### Changed-module integration run, 2026-08-10 (rerun after the timer-guard fix)

1801 tests across 7 modules, **one error**. Six modules fully green: peegeeq-db 723,
peegeeq-native 189 (6 conditional skips), peegeeq-bitemporal 352, peegeeq-rest 347
(includes the rewritten SubscriptionOptionsCoreTest), peegeeq-rest-client 15,
peegeeq-service-manager 32 (15 conditional skips). peegeeq-examples: 143 run, 1 error.

**The error is a teardown hang, not a test-logic failure.**
`ConsumerGroupResilienceTest.testConsumerFailureRecovery`: the test body PASSED and logged
its success assertions; the `@AfterEach` then logged "Tearing down" and produced NONE of its
three continuation lines ("PeeGeeQ manager closed" / "Error closing manager" / "teardown
completed") for 30 s, until VertxExtension's default timeout failed it. The
`manager.closeReactive()` Future never settled. Distinguishing detail: that teardown alone
carried an in-flight un-ACKed message ("Shutdown during completion of message 2461 - message
may be stuck in PROCESSING"); the class's other two teardowns had none and completed in
~14 ms. Symptom class matches the long-open "slow-close-under-load" item and the recorded
closeReactive/per-setup-Vert.x continuation-drop pattern.

**Load-dependent, not deterministic**: the SAME binary passes the class in isolation
(3/3, 18.8 s, verified today) and hung only under the full-module concurrent run. It also
ran green in the 2026-08-09 gate (16.3 s) and 2026-05-31 (16.2 s) — most other gate logs
never reached it, so "no prior error marker" is largely absence-of-execution, not evidence
of health.

**NOT established** (stated rather than guessed): whether this session's changes affect its
probability — no causal evidence either way, and the same-binary isolation pass means the
trigger is timing. The mechanism inside `closeReactive()` could NOT be read from this log:
peegeeq-examples' logback-test.xml sets `dev.mars.peegeeq` to WARN, so the manager's own
close-sequence INFO lines are suppressed. Diagnosing which close step stalls requires a
rerun of that module with the manager logger at INFO/DEBUG.

**Production concern surfaced by the diagnosis (open, user's call)**: once escalated past
`TIMER_FAILURE_ESCALATION_THRESHOLD`, PeeGeeQManager logs a FULL ERROR with stack trace on
EVERY tick (~1/s) indefinitely while the DB is down — no rate limit, no once-per-transition
logging (PeeGeeQManager.java:824-834). During a sustained outage that is ~1 ERROR/sec per
manager instance (one manager per setup), flooding logs exactly when they matter. The
retry-forever is the deliberate auto-recovery design; the unbounded repeat-ERROR logging is
the questionable part. An initial "not in production code" classification of the CME was
wrong and retracted — the concurrent writer WAS production; only the unsafe iteration was
test code.

### Remediation steps 1–2 — event-loop lag re-homed, notifyQueueUsage moved, 2026-08-09

**Step 2 (smaller, shipped first).** `pg_notification_queue_usage()` now rides on db-telemetry's
`CLUSTER_STATS_SQL` and lands in the cluster block as `notifyQueueUsage` (always present, 0..1 —
PostgreSQL is the producer, the endpoint is a collector, exactly like every other cluster signal).
Removed from system_stats entirely: the SQL column, the per-setup merge, the top-level field and
its REST test. `DbClusterStats` in utilities-ui gained the required field; both unit-test fixtures
updated. TDD: assertion added to `DatabaseTelemetryHandlerIntegrationTest#testClusterSignalsPresent`,
run red (`cluster block must carry notifyQueueUsage`), then green **4/4**.

**Step 1 — the measurement now lives where the measured thing lives.**

- **peegeeq-api:** new `SetupSaturationSnapshot` record (`metrics` package) with nested
  `EventLoopLag(maxMs, latestMs, sampleCount, windowSeconds)`; `ServiceProvider` gained
  `getSaturationSnapshotForSetup(setupId)` — **deliberately abstract, not a default**: the audit
  found `reloadPersistedSetups` as a default that `RuntimeDatabaseSetupService` silently fails to
  forward, and a null-returning default here would have let the delegator swallow the real
  snapshot the same way. The compiler then surfaced **eight** implementors, including four
  anonymous classes two `implements DatabaseSetupService` greps had missed — the abstract choice
  doing exactly its job.
- **peegeeq-db:** `EventLoopLagTracker` — a rolling 60 s window of samples, **read-idempotent**:
  snapshot() computes max/latest over the window without mutating it, because the owner's rule is
  that REST is idempotent, and a read-and-reset accessor lets the first collector steal the window
  from every other. `PeeGeeQManager` schedules the 500 ms sampler beside its other metrics timers
  (same `closing` guard family, cancelled in `stopBackgroundTasks`), measuring drift on **its own
  Vert.x** — the loop that carries the setup's queue work. `getSaturationSnapshot()` is the typed
  accessor; the setup service resolves it per setup like every other `getXxxForSetup`.
- **peegeeq-rest:** the 2026-08-08 REST-local sampler is **deleted** (fields, timer, close-path
  cancel). `mergeSaturation` reads each setup's snapshot at collection time; the payload carries
  per-setup attribution (`saturation[]`: setupId, max, latest, sampleCount, windowSeconds) and a
  top-level `eventLoopLagMs` = worst window-max across setups. Both omitted until a setup has
  sampled — and early absence is CORRECT (a real run showed a frame 670 ms after setup creation,
  before the first 500 ms tick), so the REST test waits for presence-eventually rather than
  asserting on the first eligible frame.
- **Verified:** metrics default ON (`peegeeq.metrics.enabled` → `true`) and the setup-provisioning
  path does not override it, so provisioned managers sample — checked before trusting the test.
  Core: `SaturationSnapshotIntegrationTest` **3/3** (sampled-after-start via recursive-timer poll;
  idempotent double-read; unstarted manager reports ABSENCE, not zero). REST:
  `SystemMonitoringHandlerTest` **19/19**, `DatabaseTelemetryHandlerIntegrationTest` **4/4**.
  utilities-ui: tsc clean, comparePlan+comparisonRunner **57/57**. Banned-pattern grep across all
  twelve touched files: clean (three hits are prose in comments).
- **TDD deviation, stated:** the sampler was implemented before its core test existed. Red was
  reconstructed per the standing rule: the test was written after, run green, then each behaviour
  mutation-probed red (sampler recording deleted; snapshot made read-and-reset; REST merge
  deleted) — probe results recorded with this entry.

**Not checked:** nothing was executed — all findings are code-structure facts, and runtime claims
(scrape contents, the fail-forever loop firing) need probes; src/test trees; UI derivation code;
pom dependency declarations. **No code was changed by this review.**

### T.5 — historical probe, 2026-08-08 (superseded by remediation step 6)

**The cadence knob is already capable of 1 Hz; two other things stop it being delivered.** Probed
against a live backend (`GET /sse/metrics?interval=1`, 12 s, zero setups — field presence and timing
do not need a provisioned database):

| Measured | Value |
|---|---|
| Requested interval | `1` s (`minIntervalSeconds` is already **1**, so the row's "raise the cadence" is a no-op) |
| Actual frame gaps | **1932, 1934, 1936, 1933, 1933, 1935 ms** — a steady **0.52 Hz** |
| Metric frames byte-identical to the previous frame | **4 of 7** |

**Cause 1 — jitter inflates the PERIOD, not the phase.**
`intervalMs = interval * 1000L + jitter`, where `jitter = random.nextInt(config.jitterMs())` is drawn
**once at connection setup** and then reused for every tick of `setPeriodic`. Its stated purpose is
to spread load across connections, which wants a one-off *phase* offset; as written it permanently
slows every connection by up to **2x**. The constant ~933 ms added to all six gaps is that single
draw, visible.

**Cause 2 — a 5 s metrics cache sits behind the stream.** `cacheTtlMs` defaults to **5000**, so
`collectMetricsFromServices()` runs at most every 5 s and faster polling re-sends the same object.
That is what the 4 identical frames are. Raising the cadence alone would deliver more copies of the
same numbers.

**So implementing the row as written would change a knob that is already capable and leave both
actual blockers in place** — the exact T.3 failure mode. The work is: make the jitter a one-off
phase offset rather than a per-tick period addition, and stop the cache TTL exceeding the
connection's own interval. **Neither is written yet**, and both change behaviour for existing SSE
consumers (management-ui reads this stream), so the shape is a decision, not a detail.

### T.3 — enqueue timestamp + client headers on the live stream, 2026-08-05

**The gap was not where the row said it was, and finding that out first was the whole job.** Probing
a running backend before writing any code:

- **Browse (`GET /queues/{setup}/{queue}/messages`) already satisfied G6.** It returns
  `createdAt: "2026-08-05T12:22:09.609318Z"` and the full `headers` map, and a client `x-send-ts`
  round-trips intact — mechanisms that predate Phase T entirely.
- **The live SSE stream did not.** Its frame was
  `{type, connectionId, messageId, payload, timestamp}` — no enqueue time, no headers, so the
  client's `x-send-ts` was dropped.

**Why that mattered more than a missing field.** The frame's `timestamp` is
`System.currentTimeMillis()` at the moment the frame is WRITTEN. In the probe it landed 45 ms after
the client's send, so a consumer treating it as delivery latency would get "45 ms" — believable, and
measuring nothing but the server's own emit delay. A wrong number wearing a convincing name is worse
than an absent one.

**The fix is additive** ([ServerSentEventsHandler](../../peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/ServerSentEventsHandler.java)):
`enqueuedAt` (the message's `created_at`) and `headers` are added to the message frame; both are
**omitted rather than zeroed** when unknown. `timestamp` is untouched so no existing SSE consumer
breaks, and the handler javadoc now spells out that the frame carries two different clocks and which
one to measure against.

**TDD.** Test written first in `SseMessageStreamDemoIntegrationTest` (the class that owns this
stream), run red for the right reason — the assertion printed the real frame,
`… "payload":{"probe":"g6"},"timestamp":1785933271994} ==> expected: not <null>` — then green.
Mutation probe (verified applied, then reverted): headers dropped from the frame → red. Whole class
**4/4 green**.

*Two environment traps hit on the way, both worth knowing:* `peegeeq-rest` tests fail to compile
against a stale `.m2` after `peegeeq-api` changes (`mvn install -pl :peegeeq-api,… -am -DskipTests`
first), and an IDE-compiled `target/classes` can carry baked-in `Unresolved compilation problems`
that survive a plain `mvn test` — the giveaway is that error text, and the fix is `mvn clean`.

Notes (from telemetry §4A): the DB queries (T.7) sample at ~5 s, **not** 1 Hz; baseline-and-delta the
cumulative `pg_stat_*` counters over the run window; `pgstattuple` is optional (enable for exact
bloat, else use the `n_dead_tup`/`n_live_tup` estimate).

### T.7 — database-level queue-table telemetry, 2026-08-06

**Probed before implementing** (the §2.1 discipline the T.3 record established). Unlike T.3, the gap
was where the row said: the only `pg_stat_*` exposure in `peegeeq-rest` is `pg_stat_activity`
connection counting (`ManagementApiHandler`, `SystemMonitoringHandler`). No table-churn, vacuum,
lock, WAL or xid telemetry existed anywhere. Every §4A query was run against live PostgreSQL 15.13
**before** any code was written, and the handler's two final statements were run verbatim afterwards.

**Shape: a snapshot endpoint, not a stream.** §4A says sample at ~5 s and have the tool
baseline-and-delta the cumulative counters itself, so a GET the tool polls is the minimal truthful
mechanism; a stream at that cadence adds nothing. New
[DatabaseTelemetryHandler](../../peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/DatabaseTelemetryHandler.java)
uses the module's established temp-pool pattern (`PgBuilder.pool()` maxSize 1, `.eventually(close)`).

**Errors are errors.** The existing temp-pool call sites convert failure into zeroed data — the
TYPED-ERASURE pattern the coding-principles audit already catalogues. That was flagged, not copied:
this endpoint returns **404** for an unknown setup and **503** on query failure. The **absence, not
zero** contract from T.1/T.3 is kept: `lastVacuum`/`lastAutovacuum`/`lastAutoanalyze`, `idxScan` and
`longestTxnSeconds` are omitted when NULL. Two clocks are documented on the handler, per the T.3
lesson: `sampledAt` is the REST server's clock for plotting only; every counter is the database's own.

**Evidence.** `peegeeq-rest` core **172/172**, integration **332/332** (the new class 4/4 inside the
full suite, no `Tests run: 0`). Mutation probe (applied, then reverted): `nTupIns` dropped from the
frame → **2 of 4 red for the right reason**, each printing the real frame; reverted → 4/4 green.
The consuming-read guard ran and passed (`AdminConsumingReadGuardTest` 2/2) — the endpoint is
non-destructive `pg_stat` SELECTs only.

**The whole-repo gate is RED, for a reason unrelated to this work.** `mvn clean test -Pall-tests`
stopped in `peegeeq-db`: 1122 run, **8 errors**, and every later module — including `peegeeq-rest` —
was SKIPPED. `peegeeq-db` builds *before* `peegeeq-rest` and does not depend on it, so T.7 cannot be
the cause. Six of the eight are `SetupBindingPersistenceIntegrationTest` (Phase R, added
2026-07-31 in `659e5624`), **reproducible in isolation** (8 run, 6 errors). Root cause: the bare
`TimeoutException` hides an uncaught `RejectedExecutionException: event executor terminated` —
`PeeGeeQDatabaseSetupService` sets `ownsVertx = (Vertx.currentContext() == null)` at construction on
the JUnit thread, then `close()` closes that self-owned Vertx *from inside its own event loop* when
called via `.eventually(() -> service.close())`, killing the continuation that would have called
`completeNow()`. **Fix location is a design decision and was deliberately left open:** the
antipatterns doc lists `Future`-returning closes as event-loop-safe, which makes the *service* the
defect rather than the test — but that changes a core teardown contract every module depends on.
The remaining two errors (`BackfillServiceConcurrencyTest.testBackfillHeavyLoad_100kMessages`,
`PeeGeeQManagerTimerGuardTest.testTimerFailuresEscalateWarnToError`) were **not** investigated.

> **RESOLVED — verified 2026-08-08. The paragraph above is history, not current state.** The fix
> landed in **`ed2e3d00` itself**, the same commit as T.7, and this record was never updated to say
> so. The teardown was changed from
> `.eventually(() -> ownsVertx ? vertx.close() : Future.succeededFuture())` to
> `.transform(this::releaseOwnedVertx)`, which completes the caller-visible promise **first** and
> only then queues `vertx.close()` via `runOnContext` — exactly the "continuation killed by closing
> the Vert.x from inside its own event loop" cause diagnosed above. The design decision recorded as
> "deliberately left open" was in fact taken: the **service** was fixed, not the test.
>
> Evidence (this session): `SetupBindingPersistenceIntegrationTest` in isolation
> (`-Pintegration-tests -Dtest=…`) **8 run, 0 failures, 0 errors**, where the record above says
> 6 errors reproduced. Both other named errors also pass in a full module run:
> `PeeGeeQManagerTimerGuardTest` 1/1, `BackfillServiceConcurrencyTest` 5/5.
> `mvn test -Pall-tests -pl :peegeeq-db` → **1123 run, 0 failures, 0 errors, BUILD SUCCESS**
> (`logs/peegeeq-db-alltests-20260808.txt`).
>
> **One anomaly is unexplained and is NOT being reported as green.** In that full-module run
> surefire recorded `tests="1"` for `SetupBindingPersistenceIntegrationTest` — a class with eight
> `@Test` methods, no `@Nested`, no `@Disabled` — while the log shows a *different* method
> (`reloadReconnectsPersistedSetupsAndSkipsBadEntries`) executing inside it. Seven tests are
> unaccounted for in the module run's own accounting. In isolation all eight are reported. Until
> that is understood, the module total is not solid evidence for this class, and the whole-repo
> gate has NOT been re-run — only `peegeeq-db` was.

**Unblocks G.2.** With G1 (T.1), G2 (T.2), G6 (T.3) and now G7, the native-vs-outbox comparison has
its full telemetry dependency set. ~~No UI consumes `db-telemetry` yet.~~ *(Superseded 2026-08-07:
G.2 consumes it — `telemetryService.getDbTelemetry` samples this endpoint either side of a
comparison run and `comparePlan.churnDeltaFor` reports the run-window delta per table. G.2 did NOT
use G6/T.3: T.2 already measures delivery latency on the database clock, which a client-side
correlation join could only approximate.)*

**Verification:** banned-pattern grep (Java **and** TS); required clean reactor-slice rebuild;
targeted tests for each changed module; confirm each new field against the running backend
**before** the UI consumes it (verify-by-running, not asserting). The full suite remains the
owner-run release gate.

---

# Setup connect / reconnect track (backend-led)

A backend-led track that closes the "connect to an existing setup" gap and builds toward the estate
control plane. **Phase S is a prerequisite for Phase B** (decided): setup provisioning belongs to
the admin tool, not the generator — the generator only *targets* setups, so connect-to-existing is
its only path to a target and S sits on the generator track's critical path. R and M follow S but
do not block B. Spec:
[PEEGEEQ_ADMIN_SETUP_LIFECYCLE_AND_MANAGEMENT_DB.md](PEEGEEQ_ADMIN_SETUP_LIFECYCLE_AND_MANAGEMENT_DB.md). All three
phases are multi-module Java changes → the same pre-work, clean reactor-slice rebuild, and targeted
verification discipline as Phase T. The full `-Pall-tests` suite is the owner-run release gate.
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
- **Backend-led work is quarantined into named tracks:** Phase T's required telemetry for G.1b
  was delivered on 2026-08-09, and rich breaking-point attribution shipped on 2026-08-19. T.6 is
  deliberately deferred with its scope, effort, and restart criteria recorded above. The **Setup connect / reconnect track (Phases S → R → M)** is a separate backend-led effort
  spec'd in the connect and management-DB docs. Everything else — all of Phases A–F and most of G —
  runs on client-side metering plus the telemetry/endpoints PeeGeeQ already exposes, so the utilities-ui
  UI work never blocks on backend changes.
- Anything in the feature design's "Non-Goals (v1)" (Part I §3) and "Future Work" (Part I
  §17) — consumer panel, Monaco editor, auth, Web Worker — stays out of this plan.
  **Scheduled runs graduated from Part I §3 on 2026-07-19** and shipped as **Phase SCH**
  above, against design Part III.
