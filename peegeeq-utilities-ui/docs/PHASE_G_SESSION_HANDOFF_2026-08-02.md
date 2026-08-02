# Phase G — Session Handoff (2026-08-02)

**Start-here note for the next session.** Phase G is the "generation tool suite" — the additional
generation-side tools built on the existing `publicationEngine`. This session shipped **three of the
five client-only tools**: G.4 (saved scenarios), G.3 (traffic profile), G.1a (ramp / breaking point).
What remains client-side is G.5-send and G.6; G.1b and G.2 stay blocked on Phase T telemetry.

The load-bearing structural decision: **a ramp is a profile whose phases are computed, and a profile
is a sequence of flat runs.** One sequencer (`profileRunner`) drives all of them over the unchanged
engine. Build any future stepped mode the same way rather than adding a second runner.

Authoritative spec: [`PEEGEEQ_DEVOPS_UTILITIES_DESIGN.md`](PEEGEEQ_DEVOPS_UTILITIES_DESIGN.md) §19.1, §19.3, §19.4.
Phase tracking: [`PEEGEEQ_DEVOPS_UTILITIES_IMPLEMENTATION_PLAN.md`](PEEGEEQ_DEVOPS_UTILITIES_IMPLEMENTATION_PLAN.md) → "Phase G".

---

## 1. Done this session (green)

| Step | What | Where |
|---|---|---|
| G.4 | Saved scenarios: model, service, store, `/tools` manager, generator scenario bar | `types/scenario.ts`, `services/scenarioService.ts`, `stores/scenarioStore.ts`, `pages/tools/ToolsPage.tsx`, `components/ScenarioBar.tsx` |
| G.4 | `TargetSelector.initialTarget` — a scenario restores its own target | `components/TargetSelector.tsx` |
| G.3a | Traffic-profile sequencer (+ the G.1a early-halt hook) | `engine/profileRunner.ts`, `types/profile.ts` |
| G.3b | Phases editor (Zone B for Profile mode) | `pages/generator/ProfilePhasesEditor.tsx` |
| G.3c | Mode selector, page wiring, achieved-vs-requested panel | `pages/generator/MessageGeneratorPage.tsx`, `ProfileResultsPanel.tsx`, `GeneratorActions.tsx` |
| G.3d | `mode`/`phases` on `Scenario`, Tools Mode column, profile e2e | `types/scenario.ts`, `services/scenarioService.ts`, `pages/tools/ToolsPage.tsx` |
| G.1a | Ramp planning + knee detection | `engine/rampPlan.ts`, `types/ramp.ts` |
| G.1a | Ramp mode UI + page wiring + e2e | `pages/generator/RampControls.tsx`, `MessageGeneratorPage.tsx` |
| — | ESLint config repair + dead-code sweep (`npm run lint` was failing at HEAD) | `.eslintrc.json`, 4 test/e2e files |
| — | Screenshot gallery regenerated (37 → 40 PNGs) + Appendix A §A.4 | `docs/screenshots/`, `PEEGEEQ_DEVOPS_UTILITIES_DESIGN.md` |

**Verified 2026-08-02 (commands in §5):** unit **41 files / 638 tests**, e2e **82/82 across 12
projects**, `npm run lint` **0 errors** (4 warnings), `tsc --noEmit` **0 errors**.

Committed in `68ae1abb` and `4316c0a0`; the working tree is clean.

---

## 2. Critical context / gotchas (read before touching this code)

1. **An IDLE phase must never be driven through a run.** `publicationEngine.tick()` floors the
   per-second quota at `Math.max(1, …)` and `runConfigSchema` requires `rate ≥ 1`, so a `rate: 0`
   phase run as a `RunConfig` would **publish traffic the profile says should not exist**.
   `profileRunner` waits it out with a timer and starts no run. Three tests pin this.
2. **`Scenario.mode` is optional-with-default at the storage boundary, deliberately.** A
   discriminated union was rejected: scenarios saved by G.4 carry no `mode`, and per-entry validation
   DISCARDS invalid entries — a strict union would have destroyed the user's saved scenarios on load.
   `mode` defaults to `'flat'` in the schema *and* in `applyScenario`. Do not "tighten" this.
3. **A ramp cannot be saved as a scenario** — `Scenario` has no ramp kind, so saving would store it
   as a *flat* scenario that replays as a completely different run. `ScenarioBar.handleSave` checks
   `mode === 'ramp'` directly, which also narrows the type so the object cannot be built wrongly.
   Adding ramp scenarios means a third mode kind + `RampSettings` on the model + Load restoring it.
4. **Schedule is blocked in both Profile and Ramp modes.** A `ScheduledRun` stores one `RunConfig`;
   scheduling a sequence would silently schedule only the base rate and duration.
5. **Every non-flat mode routes through `handleStartProfile`** (`mode !== 'flat'`). Testing for
   `'profile'` alone silently ran a ramp as a single flat run — a real bug this session, caught only
   by the e2e. Any new stepped mode must be added to that condition.
6. **Derived values are never stored.** Requested totals, profile duration, the ramp's steps, the
   knee, and the Tools "Run" description are all computed at render/run time from their source. This
   is why an achieved figure can never sit beside a stale requested one.
7. **`sustainedRate` returns `null` when no step sustained its rate.** Reporting the lowest attempted
   rate as the breaking point would fabricate a finding. The UI says so in words.
8. **Unit tests must not depend on an ambient backend.** `configService` defaults to the ABSOLUTE
   URL `http://127.0.0.1:8088`, so a real fetch in jsdom reaches whatever is listening — and
   `npm run test:e2e` leaves a backend running. `MessageGeneratorPage.test.tsx` seeds a closed port
   through the app's own config seam, AFTER `localStorage.clear()`. Without it, `test:e2e` →
   `test:run` fails in that order.
9. **`profileRunner` takes `startRun` and its timer as INJECTED dependencies** so its tests drive
   real fakes instead of `vi.mock`. It is the only module here with that seam; it exists specifically
   to avoid a mock.

---

## 3. Pick up here — Phase G remaining (recommended order)

### G.5-send — Delay / Priority / FIFO exerciser (client-only; do this next)
- Design §19.5. Drives `delaySeconds`, `priority` and `messageGroup` deliberately; **no backend
  change** — `MessageRequest` already carries all three.
- Group strategies: single group, round-robin N groups, per-key from a template token.
- Output is a **manifest** of what was sent (id → group/priority/delay) so ordering can be verified
  downstream in management-ui's MessageBrowser. Auto-verification needs telemetry G6 — out of scope.
- Fits as a fourth mode. Zone B becomes the ordering/scheduling controls; Zone E gains the manifest.

### G.6 — Correlation / trace seed generator (client-only)
- Design §19.6. Correlation strategy (one id per run / per batch / every N messages) plus optional
  parent→child causation chains. Reuses the `{{correlationId}}`/`{{runId}}` tokens — template and
  header population only.
- Output is the list of emitted ids, ready to paste into management-ui's CausationTree/Events.

### Blocked on Phase T (do NOT start these first)
- **G.1b** rich saturation attribution — needs T telemetry G3 (resource saturation), G4 (≥1 Hz
  stream), G7 (DB bottleneck signals).
- **G.2** native-vs-outbox comparison — needs G1 (percentiles), G2 (delivery latency), G6
  (correlation join), G7 (DB churn profile).
- G.1a's client-detected knee is the *basic* version and is done; G.1b is the attribution layer on
  top, not a rewrite.

### Carried debt (not blocking, but real)
1. **Screenshots are stale again.** The gallery was regenerated mid-session, then Ramp mode landed —
   so the generator shots predate the third mode and **Ramp mode has no capture at all**. Add a ramp
   shot to `screenshots.spec.ts` and regenerate. This rewrites committed PNGs, so it is the user's
   call.
2. **A ramp is not saveable as a scenario** (gotcha 3). Deliberate boundary, not an oversight.
3. **4 lint warnings**, all `react-refresh/only-export-components`: `RATE_DEFAULTS`,
   `blankTemplate`, `makeDefaultPhase`, `RAMP_DEFAULTS` exported beside their components. Warnings do
   not fail the gate; clearing them means splitting constants into separate files purely for
   hot-reload ergonomics.
4. **`npm run test:integration` finds no test files** and passes via `--passWithNoTests`. That leg
   proves nothing; do not read it as coverage.
5. **The duplicate saved-RunConfig store stands** (recorded decision, 2026-08-01):
   `peegeeq_scenarios` and `peegeeq_schedule_templates` hold the same shape. Do not "fix" one in
   isolation without revisiting the decision.

---

## 4. Test-integrity notes (this session's method)

Every step was written **test-first**, and each new test file was then **mutation-probed**: break one
load-bearing behaviour, confirm the matching test goes red, revert. This is worth continuing, because
it repeatedly found things a green suite could not:

- Two page-level tests asserting Start/Schedule disabled in Profile mode were **vacuous** — with no
  backend there is no target, so both buttons were disabled anyway. The rules moved to
  `GeneratorActions.test.tsx`, which can set `targetSelected: true`.
- A `perl`-based probe **silently failed to match** and reported "passed" for a mutation that never
  applied. **Always verify the probe landed in the file before trusting its result.**
- A probe found **redundant code**: the `index === 0` guard in the plateau rule changed nothing,
  because the `!previousPhase` check already covered it. Deleted; the remaining guard re-probed.

Two pre-existing defects were also found by running the full sweep and fixed:

- `generator-schedule.spec.ts` had a **1-in-60 time-dependent failure** — `localDatetime()` always
  emitted seconds, but the `datetime-local` inputs carry no `step`, so Chrome normalises a trailing
  `:00` away and Playwright's `fill()` throws `Malformed value`. Seconds are now emitted only when
  non-zero.
- `npm run lint` was **failing at HEAD** with 40 problems: the config never extended
  `plugin:@typescript-eslint/eslint-recommended`, so base rules ran alongside their TS equivalents
  (double-reporting, `React` false positives, `_`-prefixed args flagged).

---

## 5. Test commands (this module is npm, not Maven)

```powershell
# From peegeeq-utilities-ui/

# Unit suite (fast — the inner TDD loop)
npm run test:run

# One file while iterating
npx vitest run src/tests/unit/rampPlan.test.ts

# E2E: auto-starts a REST backend + TestContainers Postgres, ~5 min for all 12 projects
npm run test:e2e

# A single e2e project
npm run test:e2e -- --project=11-ramp

# Gate checks
npx tsc --noEmit
npm run lint

# Screenshot gallery (rewrites committed PNGs — user's call)
npx playwright test --config=playwright.screenshots.config.ts
```

**Run from the module directory.** `npx vitest` invoked from the repo root misses
`peegeeq-utilities-ui/vitest.config.ts` and fails with `document is not defined` — a wasted
debugging cycle this session.

**`test:e2e` leaves a backend running on 127.0.0.1:8088.** That is why gotcha 8 exists; it also means
a stray backend can linger between runs.
