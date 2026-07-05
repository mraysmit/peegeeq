# peegeeq-utilities-ui — Technical Design (As-Built)

This document describes the actual technical design and current implementation state of the
`peegeeq-utilities-ui` module, derived from a static reading of the source. It complements the
feature-level [PEEGEEQ_QUEUE_MESSAGE_GENERATOR_DESIGN.md](PEEGEEQ_QUEUE_MESSAGE_GENERATOR_DESIGN.md), which
describes the intended feature; this document records what is present in the codebase today and
where the two diverge.

> **Reading note.** Everything below is from static code reading. Runtime behaviour (e.g. which
> REST endpoints the live backend actually serves) has not been verified by running the app.
> Items that need runtime confirmation are called out explicitly.

> **What is a "setup"?** The canonical definition of a PeeGeeQ setup — its logical/active facets and
> the Artifacts / Binding / Runtime durability tiers — is in
> [PEEGEEQ_CONNECT_EXISTING_SETUP_SPEC.md §1](PEEGEEQ_CONNECT_EXISTING_SETUP_SPEC.md). In short: a
> setup is a named, isolated PeeGeeQ instance backed by a dedicated database+schema; only an *active*
> (backend-registered, live-pool) setup is usable, and that active binding is in-memory only today.

---

## 1. Purpose and scope

`peegeeq-utilities-ui` is a standalone browser front-end whose purpose is to **drive controlled
streams of test messages into PeeGeeQ queues**. It is deliberately a lightweight *test tool*,
separate from and narrower than `peegeeq-management-ui`:

- It assumes a setup and at least one queue may need to be created, and provides the minimum
  create/list/delete surface to get there.
- Lifecycle management, monitoring, consumer groups, message browsing, statistics dashboards,
  purge, and pause/resume are **out of scope** — those belong to `peegeeq-management-ui`.

The centrepiece is the **Queue Message Generator**: publish templated messages to a queue at a
configurable rate and duration, with placeholder tokens, named value lists, a dry-run preview,
live progress, and consecutive-error auto-stop.

---

## 2. Technology stack

| Concern            | Choice                                                        |
|--------------------|---------------------------------------------------------------|
| Framework          | React 18                                                      |
| Language           | TypeScript 5                                                  |
| Build tool         | Vite 5                                                        |
| UI component lib   | Ant Design 5                                                  |
| State management   | Zustand 5 (primary); Redux Toolkit present but a no-op stub   |
| HTTP client        | axios                                                         |
| Charts             | recharts 3                                                    |
| Validation         | Zod 4 (file-import validation)                                |
| Routing            | react-router-dom 7                                            |
| Unit/component test| Vitest 3 + Testing Library + jsdom                            |
| E2E test           | Playwright + Testcontainers (PostgreSQL)                      |
| Maven integration  | `frontend-maven-plugin` (module packaging is `pom`)          |

The Maven POM installs Node/npm and runs `npm install` at `initialize`; `npm run build` is wired
to `package` but skipped by default. Test profiles (`smoke-tests`, `core-tests`,
`integration-tests`, `e2e-tests`, `performance-tests`, `slow-tests`, `all-tests`) each shell out
to the corresponding npm script.

Backend base URL defaults to `http://127.0.0.1:8088`, overridable via localStorage key
`peegeeq_utilities_backend_config` (see [configService.ts](../src/services/configService.ts)).

---

## 3. Layered architecture

The module is organised in clear layers, with a fully-built foundation and a partially-built UI.

```
  ┌─────────────────────────────────────────────────────────────┐
  │  Pages / Components (React + Ant Design)                     │
  │  Overview, Setups, SetupDetail, CreateSetup, CreateQueue,   │
  │  TargetSelector  — plus stubbed Generator/Templates/Lists   │
  └───────────────┬─────────────────────────────────────────────┘
                  │ read/write
  ┌───────────────▼─────────────────────────────────────────────┐
  │  Stores (Zustand)                                            │
  │  generatorStore · templateStore · valueListStore ·          │
  │  utilitiesStore                                             │
  └───────────────┬─────────────────────────────────────────────┘
                  │ call
  ┌───────────────▼──────────────────┐   ┌────────────────────────┐
  │  Engine                          │   │  Services              │
  │  publicationEngine               │   │  setupService          │
  │  templateResolver                │   │  queueService          │
  │                                  │   │  publishService        │
  │                                  │   │  templateService       │
  │                                  │   │  valueListService      │
  │                                  │   │  configService         │
  └───────────────┬──────────────────┘   └───────────┬────────────┘
                  │                                   │
                  ▼                                   ▼
            localStorage                        REST API (axios)
```

### 3.1 Foundation layer — implemented and unit-tested

- **Types** — [generator.ts](../src/types/generator.ts) (`MessageTemplate`, `ValueList`,
  `RunConfig`, `RunState`, `RunSummary`, `PublishError`, `RunStatus`),
  [queue.ts](../src/types/queue.ts) (`QueueImplementationType`, `QueueSummary`,
  `CreateQueueRequest`, `MessageRequest`, `BatchMessageRequest`, `DEFAULT_QUEUE_CONFIG`),
  [setup.ts](../src/types/setup.ts) (`DatabaseConfig`, `CreateSetupRequest`, `SetupDetails`,
  `DEFAULT_DATABASE_CONFIG`).

- **Template resolver** — [templateResolver.ts](../src/engine/templateResolver.ts). Pure,
  side-effect-free. `resolveTemplate(json, ctx)` substitutes all `{{...}}` tokens then
  `JSON.parse`s the result (parse errors are intentionally surfaced to the caller).
  `findMissingLists(json, lists)` reports `{{list:name}}` references that are missing or empty.

- **Publication engine** — [publicationEngine.ts](../src/engine/publicationEngine.ts).
  A single-use factory `createPublicationEngine()` returning `{ start, stop }`. Internally:
  - `batchSize = min(maxBatchSize, max(1, floor(rate)))`, `tickMs = (batchSize / rate) * 1000`.
  - A `setInterval` tick guards against re-entrancy (`inFlight`) and completion (`finished`).
  - Each tick builds one batch, calls `publishBatch`, and either increments `sent` and resets
    the consecutive-error counter, or records a `PublishError` and increments it.
  - Auto-stop: when `maxConsecErrors > 0` and the streak reaches it, the engine clears the
    timer and calls `onError` with an auto-stop reason.
  - Value lists are snapshotted once at `start()` from `useValueListStore` — mid-run changes are
    not reflected, matching the design.

- **Services** —
  - [setupService.ts](../src/services/setupService.ts): `createSetup` (120 s timeout),
    `getSetups`, `getQueues`, `getSetupDetails`, `deleteSetup`.
  - [queueService.ts](../src/services/queueService.ts): `listQueueDetails` (uses additive
    `queueDetails[]`, falls back to names-only), `createQueue`, `deleteQueue`.
  - [publishService.ts](../src/services/publishService.ts): `publishSingle`, `publishBatch`
    (prefers batch endpoint; on HTTP 404 falls back to per-message single publish).
  - [templateService.ts](../src/services/templateService.ts) /
    [valueListService.ts](../src/services/valueListService.ts): localStorage CRUD plus
    Zod-validated file import and browser-download export.
  - [configService.ts](../src/services/configService.ts): backend URL resolution + versioned
    URL builder.

- **Stores (Zustand)** —
  - [generatorStore.ts](../src/stores/generatorStore.ts): owns `RunConfig` + live `RunState`;
    the engine calls back into `tickUpdate`/`transitionTo`.
  - [templateStore.ts](../src/stores/templateStore.ts): template CRUD with write-through to
    localStorage; import with duplicate-ID skip.
  - [valueListStore.ts](../src/stores/valueListStore.ts): value-list CRUD, `snapshot()` for the
    resolver, `importList()` with overwrite/merge semantics.
  - [utilitiesStore.ts](../src/stores/utilitiesStore.ts): the older Overview-page store — fetches
    `management/overview`, `management/queues`, `management/consumer-groups`, and derives global
    aggregates and chart series.

- **Redux** — [store/index.ts](../src/store/index.ts) is a placeholder single-reducer store kept
  only to satisfy the Provider in `main.tsx`. All real state is Zustand.

### 3.2 UI layer — partially implemented

Working pages/components:
- [SetupsPage.tsx](../src/pages/SetupsPage.tsx) — table of setups with create/delete/details.
- [SetupDetailPage.tsx](../src/pages/SetupDetailPage.tsx) — setup detail, per-queue type badges
  (green = native, orange = outbox), create-queue button, per-row delete with `Popconfirm`.
- [CreateSetupPage.tsx](../src/pages/CreateSetupPage.tsx) — full-page create-setup form.
- [CreateQueuePage.tsx](../src/pages/CreateQueuePage.tsx) — full-page create-queue form with
  implementation-type select and a collapsed Advanced panel.
- [TargetSelector.tsx](../src/components/TargetSelector.tsx) — Zone A of the generator (setup +
  queue dropdowns), with empty/no-queues/error states.
- [Overview.tsx](../src/pages/Overview.tsx) — dashboard (see §6 for the redesign gap).

Stubbed (not yet built), in [App.tsx](../src/App.tsx):
- **Message Generator page** renders only Zone A plus a `"Zone B — Message composer — Phase 2"`
  placeholder.
- **Template Manager** and **Value List Manager** pages render `"Coming soon — Phase 3"`.

---

## 4. Routing

Routes are declared in [App.tsx](../src/App.tsx):

| Route                              | Component            | Notes                               |
|------------------------------------|----------------------|-------------------------------------|
| `/`                                | `Overview`           | Dashboard                           |
| `/tools`                           | `Overview`           | Aliased to the same component       |
| `/generator`                       | `MessageGeneratorPage` (inline) | Zone A only + Phase-2 stub |
| `/setups`                          | `SetupsPage`         |                                     |
| `/setups/:setupId`                 | `SetupDetailPage`    | Queue list / create / delete        |
| `/setups/:setupId/queues/new`      | `CreateQueuePage`    |                                     |
| `/generator/setup/new`             | `CreateSetupPage`    |                                     |
| `/generator/templates`             | `TemplateManagerPage` (inline) | Phase-3 stub             |
| `/generator/value-lists`           | `ValueListManagerPage` (inline) | Phase-3 stub            |

Sidebar entries: Overview, Tools, Setups, Message Generator, Templates (indented), Value Lists
(indented).

---

## 5. API integration

All calls go through `getVersionedApiUrl(endpoint)` → `{base}/api/v1/{endpoint}`.

| Operation              | Method | Path (as coded)                                              |
|------------------------|--------|--------------------------------------------------------------|
| List setups            | GET    | `/api/v1/setups`                                             |
| Setup details          | GET    | `/api/v1/setups/{setupId}`                                   |
| List queues (names)    | GET    | `/api/v1/setups/{setupId}/queues`                            |
| List queues (details)  | GET    | `/api/v1/setups/{setupId}/queues` (reads `queueDetails[]`)   |
| Create setup           | POST   | `/api/v1/database-setup/create`                             |
| Delete setup           | DELETE | `/api/v1/database-setup/{setupId}`                          |
| Create queue           | POST   | `/api/v1/setups/{setupId}/queues`                           |
| Delete queue           | DELETE | `/api/v1/management/queues/{setupId}/{queueName}`           |
| Publish single         | POST   | `/api/v1/queues/{setupId}/{queueName}/messages`            |
| Publish batch          | POST   | `/api/v1/queues/{setupId}/{queueName}/messages/batch`      |
| Overview aggregates    | GET    | `/api/v1/management/overview`                               |
| Management queues      | GET    | `/api/v1/management/queues`                                 |
| Consumer groups        | GET    | `/api/v1/management/consumer-groups`                        |

---

## 6. Placeholder token reference

Tokens use `{{name}}` syntax and are resolved by
[templateResolver.ts](../src/engine/templateResolver.ts). Resolution is a single pass of ordered
`String.replace` calls; per-message tokens and `{{list:...}}` lookups happen in the same pass,
then the substituted string is `JSON.parse`d.

| Token                 | Scope       | Resolves to                                                    |
|-----------------------|-------------|---------------------------------------------------------------|
| `{{messageId}}`       | per-message | 1-based counter, zero-padded to 8 digits (`00000001`)         |
| `{{sequenceId}}`      | per-message | Alias for `{{messageId}}`                                      |
| `{{uuid}}`            | per-message | `crypto.randomUUID()` — a distinct value per token occurrence  |
| `{{timestamp}}`       | per-message | `ctx.now.toISOString()`                                        |
| `{{unixMs}}`          | per-message | `String(ctx.now.getTime())`                                   |
| `{{index}}`           | per-message | 0-based position (`messageId - 1`)                            |
| `{{random:N}}`        | per-message | `Math.floor(Math.random() * N)` as a string                  |
| `{{randomAlpha:N}}`   | per-message | Random alphanumeric string of length N                        |
| `{{list:name}}`       | per-message | Uniform random element of the named value list; `""` if missing/empty |
| `{{correlationId}}`   | per-run     | Single UUID generated once per run                            |
| `{{runId}}`           | per-run     | Single UUID generated once per run                            |

Implementation notes:
- `ctx.now` is a single `Date` captured **once per batch** in the engine's `buildBatch`, so every
  message in a batch shares the same `{{timestamp}}` / `{{unixMs}}` value.
- `{{uuid}}` is evaluated by the replacer, so each `{{uuid}}` occurrence in the payload gets a
  fresh UUID; `{{runId}}`/`{{correlationId}}` are fixed strings substituted verbatim.
- `{{list:name}}` accepts names matching `[A-Za-z0-9_-]+`. A missing or empty list resolves to
  the empty string; `findMissingLists` is the pre-flight check that surfaces these before a run.
- The final `JSON.parse` is intentional: it fails loudly on templates that are not valid JSON
  after substitution, which is how authoring errors are meant to surface in Preview.

---

## 7. Run lifecycle and state machine

`RunStatus` (from [generator.ts](../src/types/generator.ts)) is one of
`idle | running | completed | stopped | error`.

```
  idle ──start──▶ running ──duration elapsed──▶ completed
                    │
                    ├──stop()──────────────────▶ stopped
                    │
                    └──N consecutive errors────▶ error   (maxConsecErrors > 0)
```

Ownership split (design §7, §13):
- **[generatorStore](../src/stores/generatorStore.ts)** owns all observable state (`config`,
  `runState`). `startRun` initialises `runState` (status `running`, its own `runId`/`startedAt`),
  `tickUpdate` applies per-tick counters, `transitionTo` applies terminal status, `resetRun`
  returns to `IDLE_STATE`.
- **[publicationEngine](../src/engine/publicationEngine.ts)** owns timing only. It is created
  fresh per run, holds no persistent state, and reports outcomes through `EngineCallbacks`
  (`onTick`, `onComplete`, `onStop`, `onError`). The engine keeps its **own** `runId`,
  `correlationId`, counters, and value-list snapshot for the duration of the run.

Timing model:
- `batchSize = min(maxBatchSize, max(1, floor(rate)))`.
- `tickMs = (batchSize / rate) * 1000` — one `setInterval` tick publishes one batch.
- Re-entrancy guard `inFlight` ensures a slow request does not overlap the next tick; the tick
  is a no-op while a batch is in flight or after `finished`.
- Completion is checked at the **start** of each tick against `durationSecs * 1000`.

> **Not yet wired.** No component currently constructs the engine or subscribes the store to its
> callbacks. The generator page renders Zone A only. See §12.1.

---

## 8. Client-side persistence

The app persists three things in `localStorage`. There is no backend persistence for templates
or value lists in v1.

| Key                                | Shape                        | Written by                          |
|------------------------------------|------------------------------|-------------------------------------|
| `peegeeq_msg_templates`            | `MessageTemplate[]`          | [templateService.saveAll](../src/services/templateService.ts) |
| `peegeeq_value_lists`              | `Record<string, string[]>`   | [valueListService.saveAll](../src/services/valueListService.ts) |
| `peegeeq_utilities_backend_config` | `{ apiUrl: string }`         | [configService.saveBackendConfig](../src/services/configService.ts) |

Conventions:
- Loaders are defensive: corrupt or absent data returns `[]` / `{}` / the default config rather
  than throwing, logging via `console.error`.
- The stores are the single source of truth for the UI; every mutating store action writes
  through to storage immediately (`add`/`update`/`remove`/`duplicate`/`importX`).
- The `valueListStore` holds richer `ValueList` records (with timestamps) in memory but persists
  the flat `name → values` map; timestamps are regenerated on load and are not durable.

File import/export (browser-only, no server):
- Export builds a `Blob`, creates an object URL, clicks a synthetic `<a download>`, then revokes
  the URL.
- Import reads text via `FileReader`, `JSON.parse`s, and validates:
  - Templates are validated with a Zod schema (`messageTemplateSchema`); per-entry failures are
    collected as messages, valid entries returned.
  - Value lists accept a non-empty array of strings or numbers (numbers coerced to strings with a
    non-fatal warning); objects/nested arrays/null/empty are rejected.

---

## 9. Key data-flow sequences

**Load target (TargetSelector):**
```
mount ─▶ getSetups() ─▶ setups[]; auto-select first
        selectedSetup change ─▶ getQueues(setupId) ─▶ queues[]; auto-select first
        both selected ─▶ onTargetSelected(setupId, queueName)
```

**Preview (design §6.1 Zone D — not yet built):**
```
resolveTemplate(payloadSchema, { messageId: previewIndex, runId, correlationId, now, valueLists })
  ─▶ JSON shown in modal; zero HTTP calls; parse errors shown inline
```

**Run tick (engine):**
```
tick ─▶ if elapsed ≥ duration ─▶ onComplete(summary)
     ─▶ buildBatch(batchSize) via resolveTemplate
     ─▶ publishBatch(setupId, queueName, batch)
          success ─▶ sent += messagesSent; consecErrors = 0
          failure ─▶ errors.push(PublishError); consecErrors++
                     if consecErrors ≥ maxConsecErrors (>0) ─▶ onError(summary, reason)
     ─▶ onTick(sent, errors, consecErrors, elapsedMs)
```

**Publish fallback (publishService):**
```
POST .../messages/batch
   └─ 404 ─▶ for each message: POST .../messages   (single), messagesSent = count
```

**Create setup / queue:**
```
form.validateFields() ─▶ createSetup | createQueue (axios POST)
   success ─▶ navigate back (/setups or /setups/{setupId})
   error   ─▶ inline <Alert type="error"> via extractErrorMessage; stay for retry
```

---

## 10. Error-handling conventions

- **Services** are thin and stateless: they issue the axios call and let non-2xx responses throw.
  Error presentation is always the caller's responsibility.
- **Form pages** (`CreateSetupPage`, `CreateQueuePage`) use a shared `extractErrorMessage` helper
  that prefers `response.data.error`, then `message`, then a generic fallback, and render it in a
  closable `<Alert type="error">`. `errorFields` (Ant form validation) is swallowed deliberately
  because the form already shows field-level messages.
- **Table/detail pages** (`SetupsPage`, `SetupDetailPage`) surface load failures via `<Alert>`
  and action failures via `message.error(...)`; `SetupDetailPage` maps a 404 to a specific
  "not found" message.
- **Overview** shows a top-level status `<Alert>` that flips to `error` when `utilitiesStore`
  records a fetch failure.
- Per the project's no-error-swallowing rule, every catch should surface the error. One current
  exception is noted in §12.6.

---

## 11. Build, dev, and deployment

- **Dev:** `npm run dev` (Vite dev server). Requires the PeeGeeQ REST backend reachable at the
  configured base URL (default `http://127.0.0.1:8088`).
- **Build:** `npm run build` = `tsc && vite build`; output in `dist/`.
- **Lint / types:** `npm run lint`, `npm run type-check`.
- **Maven:** the module is `packaging: pom`; `frontend-maven-plugin` installs Node/npm and runs
  `npm install` at `initialize`. `npm run build` is bound to `package` but skipped by default.
  Test profiles map to npm scripts (see §2).
- **Backend dependency for E2E:** the Playwright global setup spins up a Testcontainers
  PostgreSQL and a PeeGeeQ REST backend; the screenshots spec additionally creates and tears down
  a throwaway `screenshot-demo` setup and `demo_orders` queue.

---

## 12. Known gaps and divergences from the feature design

These are the material points where the code diverges from
[PEEGEEQ_QUEUE_MESSAGE_GENERATOR_DESIGN.md](PEEGEEQ_QUEUE_MESSAGE_GENERATOR_DESIGN.md). None have been fixed here;
this section records them so they are not lost.

1. **Engine is not wired into the UI.** The generator UI does not instantiate
   `publicationEngine` or drive `generatorStore`. Zones B–E (rate controls, template editor,
   actions, progress) and the Template/Value-List manager pages (design §6.1–6.3) are not built.
   State: *foundation complete, generator UI not assembled.*

2. **Overview page contradicts its own redesign.** [Overview.tsx](../src/pages/Overview.tsx)
   still renders **global aggregates** (total setups/queues/messages, msg/s cards, system-wide
   stats via `utilitiesStore`) — precisely what design §6.6 says to remove in favour of
   setups-as-top-level cards with per-setup/per-queue metrics only. The redesign is documented
   but not applied to this page.

3. **Delete-queue endpoint — RESOLVED (2026-07-05).** [queueService.deleteQueue](../src/services/queueService.ts)
   calls `DELETE /api/v1/management/queues/{setupId}/{queueName}`. Verified against the live backend:
   this path returns **200** and matches `peegeeq-management-ui`'s proven contract. The design §16
   path `/api/v1/setups/{setupId}/queues/{queueName}` is the incorrect one (404) — **fix the doc,
   not the code.** Separately, [queueService.createQueue](../src/services/queueService.ts) uses a
   *bespoke* contract (`POST /setups/{id}/queues {queueName, implementationType}`) that diverges
   from management-ui's verified `POST /management/queues {setup, name, type}` (→ 201). Per the
   directive to copy management-ui precisely, createQueue should be switched to the management-ui
   contract (see IMPLEMENTATION_PLAN "Backend integration architecture").

4. **Queue dropdown lacks a type badge.** Design §6.1 wants the generator's Queue dropdown to
   show each queue's `native`/`outbox` badge. [TargetSelector](../src/components/TargetSelector.tsx)
   uses `getQueues` (names only) and renders plain labels. The richer `listQueueDetails`
   (with `queueDetails[]`) exists but is only consumed by `SetupDetailPage`.

5. **`currentRate` is a cumulative average, not a rolling window.** Design §6.1/§10 call it a
   "rolling 1-second window"; both [generatorStore.tickUpdate](../src/stores/generatorStore.ts)
   and the engine's `buildSummary` compute `sent / elapsedSeconds`.

6. **Silent error path in queue loading.** [TargetSelector.loadQueues](../src/components/TargetSelector.tsx)
   catches a fetch failure and resets to an empty queue list with no surfaced message, so a real
   failure is indistinguishable from a legitimately empty setup. Given the project's
   no-error-swallowing rule this is a smell worth revisiting.

---

## 13. Testing

Unit/component tests (Vitest) cover the resolver, engine, stores, services, and the built pages
(`src/tests/unit/*`). E2E tests (Playwright, `src/tests/e2e/*`) use a page-object pattern and a
Testcontainers-backed PostgreSQL global setup/teardown. npm scripts: `test:run`,
`test:integration`, `test:e2e`, and the aggregate `test:all` / `test:ci`.

Per the project standards, tests use no Mockito-style mocking; database interaction is via
Testcontainers, and HTTP stubbing is likewise avoided.
