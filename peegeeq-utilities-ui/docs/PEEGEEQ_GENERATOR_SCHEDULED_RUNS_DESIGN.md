# PeeGeeQ Utilities UI — Scheduled Generator Runs: Design

**Author**: Mark Andrew Ray-Smith Cityline Ltd
**Created**: 2026-07-19
**Status**: IMPLEMENTED (v1, 2026-07-19 — SCH.1–SCH.7 complete, plus the two later phases
shipped the same day as SCH.8: schedule import and manual-run history, both graduated from
§4; see the implementation plan for per-step evidence). One post-review model correction is
recorded in §5 (schedules carry
scheduling state only); one design correction from end-to-end testing is recorded in §7.4/§7.5
(the missed sweep runs at first lock acquisition with app start as the cutoff — the
start-time-only sweep would have auto-fired after a reload; §7.5 records the 2026-07-21
replacement of the localStorage lease with the Web Locks API).
**Version**: 1.0

Companion implementation plan:
[PEEGEEQ_GENERATOR_SCHEDULED_RUNS_IMPLEMENTATION_PLAN.md](PEEGEEQ_GENERATOR_SCHEDULED_RUNS_IMPLEMENTATION_PLAN.md).
This feature graduates "scheduled runs" out of the main design's Non-Goals list
([PEEGEEQ_DEVOPS_UTILITIES_DESIGN.md](PEEGEEQ_DEVOPS_UTILITIES_DESIGN.md) §3).

---

## 1. Feature statement

A user configures a generator run (target, rate, duration, template) and, instead of starting
it immediately, schedules it: run once at a chosen time, or repeat at a fixed interval. A new
**Scheduled Runs** screen lists every schedule with its next fire time, status, and last
outcome, and provides enable/disable, run-now, and delete. The schedule action lives on the
Message Generator screen, next to Start.

Success end-state: a user sets up a nightly 60-second load run before leaving the generator
open on a test bench; each firing publishes real messages and records its outcome; the
Scheduled Runs screen shows what fired, what it sent, and when the next firing is due; the
run history lists every past firing — completed or missed — filterable by result; and any
past run can be saved as a schedule template, so re-running it later is a two-click
prefill instead of a rebuild.

## 2. The central decision: execution context

**DECIDED (recommendation, confirm at review): schedules execute CLIENT-SIDE. A schedule
fires only while the Utilities UI is open in a browser tab.**

Reasons:

1. The entire generator is client-side by design: the publication engine, template resolver,
   templates, and value lists live in the browser (main design §5, §7, §8). A schedule is a
   deferred call to the same engine the Start button uses — same code, same fidelity, zero
   backend change.
2. The alternative — backend scheduling — requires reimplementing the generator in Java
   (engine, resolver, template semantics) and moving templates + value lists to server-side
   storage. That is a different project, not a feature of this one. If reliable unattended
   scheduling is later required, that project builds on this design's schedule model but
   replaces §7 (execution) wholesale.
3. The tool is a dev/test load generator, typically driven from an open workstation or test
   bench. "The tab must be open" is an acceptable v1 constraint for that audience **provided
   the UI states it** — see §7.4 (missed runs) and §8 (the banner).

Consequences, stated plainly:

- A schedule does NOT fire if the app is closed, the machine is asleep, or the tab is
  discarded by the browser. The UI must never imply otherwise.
- Firing happens in whichever open tab holds the scheduler lock (§7.5). Two open tabs must
  not double-fire.
- Times are the browser's local timezone.

## 3. Requirements

| # | Requirement |
|---|---|
| R1 | Create a schedule from the Message Generator screen, capturing the CURRENT run configuration (target, rate settings, template working copy). |
| R2 | Schedule types: **one-shot** (fire once at a datetime) and **interval** (first firing at a datetime, then every N minutes). |
| R3 | A new **Scheduled Runs** screen lists all schedules: name, target, rate × duration, schedule description, next fire time, enabled state, last outcome. |
| R4 | Actions per schedule: enable/disable, run now, delete (confirmed). Editing of the timing (next fire time, interval) in place; the run configuration itself is not editable — delete and re-schedule from the generator. |
| R5 | A due schedule fires through the SAME engine path as the Start button: store-generated run id, acknowledged counts, terminal summary. |
| R6 | Each firing records an outcome — when, final status, total sent, total errors — **in the run history** (corrected at the §5 model review: the schedule itself stores no outcome; the table's outcome column is derived from the newest history record). The last summary is downloadable from the history. |
| R7 | Only one run executes at a time (the engine and run state are singletons). A schedule that comes due while any run is active is **skipped** and records a skipped outcome; an interval schedule advances to its next slot. |
| R8 | Missed schedules (due while the app was closed) NEVER auto-start on app open: they record a `missed` outcome and advance/disable (§7.4). |
| R9 | Schedules persist in localStorage, like templates and value lists. Per-browser, per-origin — not shared between machines or users, and the UI says so. |
| R10 | Zero backend changes. |
| R11 | **Export all schedules as JSON** from the Scheduled Runs screen: one file containing the full `ScheduledRun[]` array (§5 shape — configs, timing, outcomes), downloadable as `schedules.json`. This is the escape hatch for R9's per-browser storage. Import of that file was planned as a later phase and **shipped 2026-07-19** (§4). |
| R12 | **Run history.** EVERY scheduled firing outcome — `completed`, `stopped`, `error`, `skipped`, `missed` — appends an entry to a run-history list, shown on the Scheduled Runs screen with a **filter** (by result, and by schedule name). History is bounded (§5), survives deletion of its schedule, and each fired entry keeps a downloadable summary. |
| R13 | **Schedule templates.** A history entry or an existing schedule can be **saved as a named schedule template** (the frozen `RunConfig`). A new schedule (or an immediate run) can be created FROM a template — the schedule modal opens prefilled, only the timing needs choosing. Templates are listed on the Scheduled Runs screen with schedule-from / run-now / delete actions. |

## 4. Non-goals (v1)

- Backend/unattended execution (§2). No firing with the app closed.
- ~~Schedule import~~ — **shipped 2026-07-19** (the later phase was implemented on request):
  an Import button on the Schedules tab consumes the R11 `schedules.json` format with
  per-entry Zod validation (named rejects, including range rules), duplicate-id skip with a
  named warning (no overwrites), and `nextRunAt` recomputed at import — a past one-shot
  arrives consumed (disabled) and a past interval advances to its next future slot, so an
  imported backlog never fires (verified end-to-end across two check cycles).
- Cron expressions. One-shot + fixed interval only; a cron-lite grammar can extend §5 later.
- Editing a schedule's run configuration in place (R4: timing only; templates cover the
  rebuild case — R13).
- ~~History of MANUAL (Start-button) runs~~ — **shipped 2026-07-19**: every manual Start
  terminal (completed, stopped, error) records a history entry under the fixed scheduleId
  `manual`, named "Manual run — {template} @ {queue}", with the frozen config and full
  summary — filterable and template-savable like any scheduled firing. Zone E behaviour is
  unchanged; the history entry is additional.
- Cross-tab live sync of the schedules list (the lock prevents double-firing; the list
  refreshes on navigation).

## 5. Data model

Stored in localStorage under `peegeeq_generator_schedules` as a JSON array, validated per
entry with Zod on load (per-entry `safeParse` — one corrupt entry never blanks the list).

```typescript
/** One scheduled generator run. */
export interface ScheduledRun {
  id: string                     // crypto.randomUUID()
  name: string                   // user-supplied, non-empty
  config: RunConfig              // FULL SNAPSHOT at scheduling time (see §6)
  schedule:
    | { kind: 'once'; runAt: string }                          // ISO 8601, local intent
    | { kind: 'interval'; firstRunAt: string; everyMinutes: number }  // everyMinutes ≥ 1
  enabled: boolean
  nextRunAt: string | null       // null once a one-shot has fired/missed
  createdAt: string
  updatedAt: string
}
// The schedule carries SCHEDULING state only (corrected 2026-07-19, user review).
// Run outcomes live exclusively in the run history; the schedules table derives
// its "last outcome" column from the newest ScheduleRunRecord for the schedule.
// Duplicating an outcome onto the schedule would be redundant state that drifts.

export interface ScheduleOutcome {
  at: string                                        // firing (or skip/miss) time
  result: 'completed' | 'stopped' | 'error' | 'skipped' | 'missed'
  totalSent: number              // 0 for skipped/missed
  totalErrors: number
  detail?: string                // autoStopReason / skip reason / miss reason
}

/**
 * One row of the run history (R12). Written for EVERY outcome, including
 * skipped and missed. Deliberately denormalised: scheduleName and target are
 * copied in so the entry stays meaningful after its schedule is deleted.
 */
export interface ScheduleRunRecord {
  id: string
  scheduleId: string
  scheduleName: string
  target: { setupId: string; queueName: string }
  outcome: ScheduleOutcome
  /** Present for fired runs; errors capped at the 20 most recent to bound entry size. */
  summary: RunSummary | null
  /** Frozen config of the firing — what "Save as template" captures (R13). */
  config: RunConfig
}

/** A reusable run configuration (R13). No timing — that is chosen per schedule. */
export interface ScheduleTemplate {
  id: string
  name: string
  config: RunConfig
  createdAt: string
  updatedAt: string
}
```

Storage keys and bounds:

| Key | Content | Bound |
|---|---|---|
| `peegeeq_generator_schedules` | `ScheduledRun[]` | none needed (user-managed) |
| `peegeeq_schedule_run_history` | `ScheduleRunRecord[]`, newest first | **capped at 200 entries, oldest dropped** — localStorage must not grow unbounded |
| `peegeeq_schedule_templates` | `ScheduleTemplate[]` | none needed (user-managed) |

All three load with per-entry Zod validation; invalid entries are dropped with a visible
warning naming them, never silently.

## 6. Snapshot semantics

**The schedule embeds the full `RunConfig`, including the template working copy, at
scheduling time.** What the user sees when clicking Schedule is what runs — the same
working-copy contract as Start (main design §6.1 Zone C). Later edits to the saved template
do not change an existing schedule.

**Value lists are the one deliberate exception**: they are snapshotted at FIRE time, exactly
as a manual Start snapshots them (§8 of the main design: "loaded once at the start of each
run"). A nightly schedule therefore picks up value-list edits made during the day. The
missing-list rule also applies at fire time: missing lists resolve to `""`; unlike the
interactive pre-flight there is no one to confirm, so the firing proceeds and the outcome
`detail` records the missing list names.

The target is validated at fire time only by the publish path itself: a detached setup makes
every batch fail, the auto-stop guard ends the run, and the outcome records `error`. The
schedule stays enabled (an interval schedule retries at its next slot).

## 7. Scheduler mechanics

### 7.1 Placement

A single scheduler instance runs app-wide, started once from `App` mount — NOT from the
generator page. Schedules fire regardless of which screen is open. It is a plain module
(`schedulerRuntime`) driven by a 15-second `setInterval` check; schedules have minute
granularity, so a 15 s check bounds firing lag at ~15 s.

### 7.2 Firing

On each check, for every enabled schedule with `nextRunAt <= now`, in `nextRunAt` order:

1. If a run is already active (`generatorStore.runState.status === 'running'`): record a
   `skipped` outcome (R7), advance `nextRunAt` (§7.3), continue.
2. Otherwise fire through the SHARED run-starter (§9): `setConfig(schedule.config)` →
   `startRun()` → engine with store-generated run id — identical to the Start button.
   `nextRunAt` advances AT FIRE TIME (§7.3) — corrected 2026-07-21: advancing only at the
   terminal callback left the fired slot "due" at every check during the run, so any run
   outlasting one 15-second check recorded a false self-skip and a one-shot was consumed
   by that skip mid-run.
3. On the terminal callback, append the outcome and summary to the run history. The
   terminal callback does NOT advance — re-advancing there would silently discard a slot
   that came due during the run, which must record a skip instead (R7).

**Every outcome — fired (completed/stopped/error), skipped, and missed (§7.4) — also
appends a `ScheduleRunRecord` to the run history (R12).** The history is the run record —
the schedule itself is only touched for scheduling state (`nextRunAt` advance, one-shot
consumption); the schedules table's outcome column reads the newest history record.

At most one schedule fires per check; runs never overlap. The check itself is re-entrant-safe
(a firing in progress blocks further firings via rule 1).

### 7.3 Advancing

- `once`: `nextRunAt = null`, `enabled = false` after any terminal outcome (fired, skipped,
  or missed — a one-shot consumes its single slot either way; run-now remains available).
- `interval`: next slot strictly in the future: `firstRunAt + k · everyMinutes` for the
  smallest k with slot > now. Slots that would have landed while a long run was executing are
  not replayed — no catch-up bursts.

### 7.4 Missed runs (app was closed)

**Opening the app never auto-starts a run** (decided 2026-07-19, user — supersedes the
earlier grace-window draft). Every enabled schedule overdue at APP START (`nextRunAt` before
the start time) records a `missed` outcome with the overdue duration in `detail`, then
advances per §7.3. A one-shot is consumed (disabled); an interval schedule moves to its next
future slot. The user resumes deliberately: Run now, or wait for the next slot.

*Corrected during end-to-end testing (2026-07-19):* the sweep runs at the scheduler's
**first successful lock acquisition**, with the app start time as the cutoff — not only at
start. Running it only at start was a defect: after a reload, the previous page can still
hold the lock, the start-time sweep is skipped, and the first post-takeover check would
have FIRED the overdue schedule. Schedules becoming due after start (while waiting for the
lock) fire normally.

While the app is RUNNING, a schedule firing up to ~15 s after its due time is normal firing
lag (§7.1 check interval), not a missed run — this rule concerns app start only.

### 7.5 Two open tabs

The **Web Locks API** elects one firing tab: each tab's scheduler requests the exclusive
lock `peegeeq_scheduler` and holds it for as long as the tab lives; only the holder fires.
The browser releases the lock automatically when the holding page goes away (close, reload,
crash), so a waiting tab takes over immediately — no TTL, no heartbeat, no `pagehide`
handling. The schedules LIST is readable from any tab; mutations go through the store and
re-read storage on navigation (§4 non-goal: no live sync).

*Corrected 2026-07-21 (post-review, user decision):* v1 used a `localStorage` lease
(tab id + heartbeat, 30 s TTL, released on `pagehide`). Its acquire was not atomic —
`localStorage` has no compare-and-set, and two tabs passing the write-then-read-back check
in the same window could both fire a due schedule. The lease mechanism is deleted, not kept
alongside. A browser without the Web Locks API gets no firing tab and says so
(`message.error`) — firing without mutual exclusion risks double-publishing.

*Known limit (unchanged by the lock):* the run-active guard is per-tab state. The executor
tab cannot see a MANUAL run active in another tab, so a scheduled firing can overlap a
manual run started elsewhere. The lock serialises scheduled firings only.

### 7.6 Interaction with a user mid-flow

A schedule firing writes to the shared `generatorStore`. If the user has the generator page
open: Zone E shows the scheduled run live, Zones B/C disable (already keyed off
`status === 'running'`), and Start is unavailable — identical to watching a manual run. If
the user is mid-edit of an unsaved working copy, nothing is lost: the firing uses the
schedule's embedded config and never touches the page's local state.

## 8. UI

### 8.1 Message Generator screen — the Schedule action

Zone D gains a **Schedule…** button next to Start, enabled under the same conditions as
Start (status `idle`, target selected). It opens a modal:

- Schedule name (required, prefilled `"{template name} @ {target queue}"`).
- Run: **Once** at [datetime picker] | **Repeat every** [N] **minutes starting** [datetime].
  Times in the past are rejected.
- A summary line of what is being captured: target, rate × duration = total, template name.
- A plainly-worded note: *"Scheduled runs fire only while this app is open in a browser tab,
  and are stored in this browser only."*
- The missing-value-lists pre-flight runs on save exactly as on Start (warn, non-blocking).
- Save creates the schedule (full config snapshot) and confirms with a link to the
  Scheduled Runs screen.

The same modal serves the **prefilled paths** (R13): opened from a template ("Schedule…")
or a history entry ("Re-schedule"), the config summary line shows the frozen config being
reused and only the name and timing need entering. The prefilled config is the template's,
never the generator page's current working state.

### 8.2 Scheduled Runs screen — `/generator/schedules`

New sidebar entry **Scheduled Runs** under Message Generator (with Templates and Value
Lists). A page-level info banner restates the client-side execution constraint (§2).
Three tabs:

**Tab 1 — Schedules.** Toolbar: **Export all** — downloads the complete `ScheduledRun[]`
array as `schedules.json` (R11; disabled when there are no schedules). Table:

| Column | Content |
|---|---|
| Name | Schedule name |
| Target | `setupId / queueName` |
| Run | `rate msg/s × duration s = total`, template name |
| Schedule | "Once at {t}" or "Every {N} min from {t}" |
| Next run | Absolute + relative time; "—" when consumed/disabled |
| Enabled | Toggle (Switch) |
| Last outcome | Status tag (COMPLETED green / ERROR red / SKIPPED orange / MISSED grey) + `sent/total` + relative time |
| Actions | Run now · Edit timing · Save as template · Delete (confirmed) |

- **Run now**: fires immediately through §7.2 rule 2 (refused with a message while a run is
  active). Does not consume a one-shot's scheduled slot and does not advance an interval.
  The firing appends to the history like any other (R12).
- **Edit timing**: modal editing only the §5 `schedule` field and recomputing `nextRunAt`.
- **Save as template**: prompts for a template name, captures the schedule's frozen config
  (R13).
- Empty state: explains the feature and points at the generator's Schedule… button.

**Tab 2 — Run history (R12).** Every `ScheduleRunRecord`, newest first, with **filters**:
a result filter (All / Completed / Stopped / Error / Skipped / Missed) and a schedule-name
search. Table: time (absolute + relative) · schedule name · target · result tag ·
`sent/errors` · detail (auto-stop reason / skip / miss). Row actions: **Download summary**
(fired entries) · **Save as template** (R13) · **Re-schedule** (opens the §8.1 modal
prefilled from the entry's frozen config). A caption states the 200-entry bound (§5).

**Tab 3 — Templates (R13).** Table: name · target · `rate × duration = total` · template
name · updated. Row actions: **Schedule…** (opens the §8.1 modal prefilled from the
template, timing blank) · **Run now** (immediate firing via the run-starter, recorded in
history with the template name as the schedule name) · **Delete** (confirmed). Empty state
points at the Save-as-template actions.

## 9. Required refactor: the shared run-starter

`MessageGeneratorPage.handleStart` currently owns the engine wiring (construct engine, generate
identity via `startRun()`, connect callbacks to `tickUpdate`/`transitionTo`/`setSummary`,
discard on terminal). The scheduler needs the identical wiring without the page mounted.

Extraction: `src/engine/runStarter.ts` exporting
`startGeneratorRun(config: RunConfig, hooks?: { onTerminal?(summary, status) }): { stop(): void } | null`
— returns null (refusing) when a run is already active. The page and the scheduler both call
it; the page keeps its unmount-stop behaviour by holding the returned handle. The engine, the
stores, and the callback contract do not change. This is a pure relocation of existing wiring
plus an active-run guard, covered by the existing page tests plus new runStarter tests.

## 10. Failure honesty

- A firing whose template no longer resolves (run-time resolve failure) ends through the
  engine's ERROR path (B-review fix) and records an `error` outcome with the parse message.
- Skipped and missed firings are recorded outcomes, never silent (R7, R8).
- Storage read uses per-entry validation; invalid entries are dropped with a visible warning
  naming them, never silently.
- The scheduler check never throws into the interval: any escape is caught and surfaced as a
  message and a recorded `error` outcome (fire-and-forget is banned).

## 11. Testing

Unit (Vitest, fake timers, real stores/localStorage, no mocks beyond the sanctioned engine
publish boundary):

1. scheduleStore/service CRUD, per-entry validation on load, persistence round-trip.
2. `nextRunAt` computation: one-shot consumption; interval advance skips past slots; no
   catch-up bursts.
3. schedulerRuntime: fires a due schedule through the run flow (publish boundary mocked as in
   engine tests); skips + records when a run is active; app start marks every overdue
   schedule missed and NEVER auto-fires (§7.4); the lock prevents a non-holder from firing;
   check-escape surfaces an error outcome.
4. runStarter: refuses while active; wires callbacks; page behaviour unchanged.
5. Schedule modal: validation (name, past times), snapshot capture, missing-list warning.
6. Scheduled Runs screen: schedules table render, toggle, run-now refusal while active,
   edit-timing, delete confirm, empty state, Export-all (blob content parses back to the
   stored `ScheduledRun[]`; disabled when empty).
7. Run history: every outcome kind appends a record (incl. skipped and missed); the 200
   cap drops oldest; entries survive schedule deletion; result filter and name search
   narrow the table; download present only on fired entries.
8. Templates: save-as-template from a schedule row and a history row; Schedule…/Re-schedule
   open the modal prefilled from the FROZEN config (not the generator's working state);
   template run-now fires and records history; delete confirm.

E2E (real backend, `4-generator-run`-style project): create a schedule ~10 s ahead from the
generator UI → it fires while the user is on the Scheduled Runs screen → the outcome appears
with real acknowledged counts → run-now fires again → delete. Screenshot addendum for the new
screen and modal.

## 12. Decisions requiring review sign-off

| # | Decision | Recommendation |
|---|---|---|
| D1 | Execution context | Client-side; app must be open (§2). The whole design depends on this. |
| D2 | Recurrence scope v1 | One-shot + fixed interval in minutes. No cron. |
| D3 | Missed-run policy | No auto-start on app open — record missed and advance/disable (§7.4). **Decided 2026-07-19 (user).** |
| D4 | Concurrency | Skip-and-record; no queueing of due runs (§7.2). |
| D5 | Snapshot semantics | Config+template frozen at scheduling; value lists at fire time (§6). |
| D6 | Edit scope | Timing editable in the list; config changes = delete + re-schedule (R4). |
| D7 | Export/import | Export-all as `schedules.json` shipped in v1 (R11); import shipped as the follow-on phase using that file as its format (§4). **Decided and completed 2026-07-19 (user).** |
| D8 | Run history scope | Every scheduled-firing outcome recorded (R12), 200-entry FIFO cap, entries survive schedule deletion, per-entry stored errors capped at 20. ~~Manual runs excluded in v1~~ — manual-run history shipped 2026-07-19 (§4): every Start-button terminal records under scheduleId `manual`. **Requested 2026-07-19 (user); bounds are my proposal — confirm.** |
| D9 | Template model | `ScheduleTemplate` = named frozen `RunConfig`, no timing (§5). Created from schedule rows and history rows; consumed by prefil­led Schedule modal and template Run now (R13). **Requested 2026-07-19 (user); model is my proposal — confirm.** |
