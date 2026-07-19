# Scheduled Generator Runs — Implementation Plan

**Author**: Mark Andrew Ray-Smith Cityline Ltd
**Created**: 2026-07-19
**Status**: ✅ COMPLETE 2026-07-19 — all steps SCH.0–SCH.7 done; per-step evidence below.
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
434/434, build clean).
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
key. Route + nav entry + schedulerRuntime lifecycle added to App.tsx. Full unit suite
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
De-flaked `--repeat-each=2 --retries=0`. Screenshots 13–16 added (modal, schedules tab,
history after a real firing, templates tab) and Appendix A extended; main design §3
non-goal graduated. Unit 471/471.
Coverage extension (user request, 2026-07-19): two further e2e tests — (a) the two-tab
lease with two REAL tabs sharing real storage: a due schedule fires exactly once, and the
count stays 1 through two further check cycles in both tabs; (b) an interval schedule
fires twice at its one-minute slots with the schedule staying enabled between firings.
Project re-de-flaked `--repeat-each=2 --retries=0` (33/33). Remaining out of scope by
decision: schedule import (later phase, D7).

Design: [PEEGEEQ_GENERATOR_SCHEDULED_RUNS_DESIGN.md](PEEGEEQ_GENERATOR_SCHEDULED_RUNS_DESIGN.md).
Section references (§n) point into it.

Ground rules, unchanged from the main plan: strict TDD (failing tests first, including every
failure mode of every called dependency), no mocks beyond the sanctioned engine publish
boundary, no error swallowing, no fire-and-forget async, mirror existing module patterns
(templateStore/templateService are the model for the store/service pair), banned-pattern grep
per step, one phase at a time with tests green before the next.

---

## Dependency order

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

---

## Steps

| Step | Files | What | Verification |
|---|---|---|---|
| SCH.0 | design doc | User reviews §12 decisions D1–D6. **No code before sign-off** — D1 (client-side execution) shapes every later step. | User review. |
| SCH.1 | `src/types/schedule.ts`, `src/services/scheduleService.ts`, `src/stores/scheduleStore.ts` | ALL §5 types: `ScheduledRun`/`ScheduleOutcome`/`ScheduleRunRecord`/`ScheduleTemplate`; service = localStorage CRUD for the three keys (`peegeeq_generator_schedules`, `peegeeq_schedule_run_history`, `peegeeq_schedule_templates`) with per-entry Zod validation (mirror templateService, incl. the dropped-entry warning path), the **200-entry FIFO cap + 20-error cap** on history writes (D8), and `exportAllSchedules()` → `schedules.json` (R11); store = zustand CRUD for schedules/history/templates + `recordOutcome` (appends the history record — the run record — and advances SCHEDULING state only; corrected 2026-07-19: the schedule carries no outcome fields, the table derives its outcome column via `latestOutcomeFor`) + `saveAsTemplate` + `computeNextRunAt` (§7.3 — pure function, exported for direct testing). | Unit: CRUD round-trips for all three; corrupt-entry drop visible not silent; history cap drops oldest; history entry survives schedule deletion; export blob parses back; §7.3 cases — one-shot consumption, interval advance past missed slots, no catch-up. |
| SCH.2 | `src/engine/runStarter.ts`, `src/pages/generator/MessageGeneratorPage.tsx` | Extract the engine wiring from `handleStart` into `startGeneratorRun(config, hooks?)` (§9): active-run guard returns null; store-generated run id; callbacks to `tickUpdate`/`transitionTo`/`setSummary`; terminal hook; returned `stop()` handle. Page delegates to it; unmount-stop preserved. Pure relocation — no contract change. | Unit: new runStarter tests (refusal while active; terminal hook fires; callbacks wired — publish boundary mocked as in engine tests). ALL existing page + engine tests stay green unmodified. |
| SCH.3 | `src/engine/schedulerRuntime.ts` | §7 in full: 15 s check; due-schedule firing via runStarter; skip-and-record while active (D4); §7.4 on start — every overdue schedule records `missed` and advances, **app open never auto-fires** (D3); §7.5 localStorage lease with heartbeat; §10 check-escape → surfaced message + `error` outcome. `start()`/`stop()` for App mount/unmount and tests. | Unit (fake timers): fire-on-due while running; app start with overdue schedules marks missed and fires NOTHING; skip records outcome and advances; every outcome kind (fired/skipped/missed) appends its history record (R12, via SCH.1's `recordOutcome`); lease non-holder never fires, expired lease taken over; escape surfaces. |
| SCH.4 | `src/pages/generator/ScheduleRunModal.tsx`, `GeneratorActions.tsx` | Zone D **Schedule…** button (enabled as Start: idle + target) opening the §8.1 modal: name (prefilled), once/interval picker, past-time rejection, capture summary line, client-side-execution note, missing-list warning (same scan surface as Start), save = full-config snapshot into the store (D5). | Unit: validation paths; snapshot equals the current working state; missing-list warning; disabled without target. Existing Zone D tests green. |
| SCH.5 | `src/pages/schedules/ScheduledRunsPage.tsx` | §8.2 in full — three tabs. **Schedules**: table + enable toggle, run-now (via runStarter; visible refusal while active; records history), edit-timing modal (D6), save-as-template, delete confirm, **Export all** (R11; disabled when empty), empty state. **Run history**: filterable table (result filter + name search), download on fired entries, save-as-template, re-schedule (prefilled modal), 200-cap caption. **Templates**: table with Schedule… (prefilled modal), run-now (records history under the template name), delete confirm, empty state. Page-level execution-constraint banner. | Unit: §11 test groups 6–8 — schedules tab behaviours; every-outcome-kind history append, filters, cap, survival after schedule deletion, download-only-on-fired; template save/prefill (frozen config, not working state)/run-now/delete. |
| SCH.6 | `src/App.tsx` | Route `/generator/schedules`, sidebar entry, `schedulerRuntime.start()` on App mount (stop on unmount). | Unit: nav + route render. Full unit suite green; build clean. |
| SCH.7 | e2e spec + project, `screenshots.spec.ts`, both scheduled-runs docs, main design §3 | E2E (real backend): schedule ~10 s ahead via the UI → fires while on the Scheduled Runs screen → **history row appears with real acknowledged counts and passes the result filter** → save the entry as a template → schedule-from-template (prefilled modal) → run-now from the template → delete schedule and template, history rows survive. Screenshot addendum (all three tabs + modal). Graduate "scheduled runs" from main design §3 Non-Goals with a pointer here; mark plan rows done. | Full e2e suite green incl. new project, de-flaked `--repeat-each=2 --retries=0`; screenshots regenerated. |

---

## Risks and their controls

| Risk | Control |
|---|---|
| Unbounded localStorage growth from history | D8: 200-entry FIFO cap + 20-error cap per stored summary, enforced in the service write path and tested in SCH.1. |
| Double-firing with two open tabs | §7.5 lease; a dedicated SCH.3 test proves a non-holder never fires. |
| Firing collides with a manual run | D4 skip-and-record; §7.2 rule 1 test. |
| Timer-driven code is flake-prone to test | All SCH.3 tests use fake timers; `computeNextRunAt` is a pure exported function tested without timers. |
| The refactor (SCH.2) silently changes Start behaviour | Zero modifications to existing page/engine tests allowed in SCH.2 — they are the regression harness. |
| User misreads schedules as server-side | The §8.1 modal note and §8.2 banner state the constraint; the design forbids UI copy implying otherwise. |
| localStorage schedule corrupt/stale after upgrades | Per-entry Zod validation with visible dropped-entry warnings (SCH.1). |

## Out of scope (recorded, not planned)

Backend/unattended execution, cron grammar, config editing in place, full per-schedule run
history, cross-tab live list sync — all per design §4. Backend execution, if ever wanted, is
a separate project that reuses the §5 schedule model and replaces §7.

**SCH.8 ✅ DONE 2026-07-19 — the later phases, implemented on user request:**
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
cron (D2), in-place config editing (D6), cross-tab live list sync (§4).
