# Phase G.2 — Session Handoff (2026-08-07)

**Start-here note for the next session.** This session shipped **G.2 (Native-vs-Outbox comparison)**
end to end — the last of the two telemetry-gated Phase G tools — plus a document-status correction
pass and a screenshot regeneration. Everything is committed and green.

**The single most useful thing in this document is §3.** The Compare panel currently
**under-reports database churn**, and the screenshot capture found it, not the test suite. It is
diagnosed with evidence, it is *not* an error-handling defect, and the fix is not a sleep. Read §3
before touching `comparisonRunner` or `CompareResultsPanel`.

**The second most useful thing is §2.1.** Compare is the first mode with two runs live at once, and
it deliberately bypasses `runStarter`/`generatorStore`. Anything that "tidies" it back onto the
shared run wiring will silently report a run that never happened.

Authoritative spec: [`PEEGEEQ_DEVOPS_UTILITIES_DESIGN.md`](PEEGEEQ_DEVOPS_UTILITIES_DESIGN.md) §19.2.
Telemetry: [`PEEGEEQ_ADMIN_DEVOPS_TELEMETRY_REQUIREMENTS.md`](PEEGEEQ_ADMIN_DEVOPS_TELEMETRY_REQUIREMENTS.md) §4 G1/G2/G7, §4A, §6, §7.
Phase tracking: [`PEEGEEQ_DEVOPS_UTILITIES_IMPLEMENTATION_PLAN.md`](PEEGEEQ_DEVOPS_UTILITIES_IMPLEMENTATION_PLAN.md) → "Phase G" and the "G.2" record.
Prior handoff: [`PHASE_T_SESSION_HANDOFF_2026-08-06.md`](PHASE_T_SESSION_HANDOFF_2026-08-06.md) (backend telemetry).

---

## 1. Done this session (green)

| Step | What | Where |
|---|---|---|
| G.2a | Data model, pure comparison logic, telemetry reads, concurrent runner | `types/compare.ts`, `engine/comparePlan.ts`, `services/telemetryService.ts`, `engine/comparisonRunner.ts` |
| G.2b | Zone A two-target selector (native + outbox rows) | `pages/generator/CompareTargets.tsx` |
| G.2c | Zone E side-by-side results + page/ScenarioBar wiring | `pages/generator/CompareResultsPanel.tsx`, `MessageGeneratorPage.tsx`, `components/ScenarioBar.tsx` |
| G.2d | E2E project `14-compare` (4 cases) | `tests/e2e/specs/compare.spec.ts`, `playwright.config.ts` |
| docs | Six stale status claims corrected (§5) | plan + telemetry requirements docs |
| shots | Captures 47/48 added; 33 existing PNGs regenerated | `tests/e2e/specs/screenshots.spec.ts`, `docs/screenshots/` |

Commits: **`da210a52`** (code + tests), **`371799fb`** (docs + screenshots). Working tree clean.

**Verification (all run this session):**

- Unit: **52 files, 811 tests, 0 failures** (`npm run test:run`)
- E2E: **91/91 across 14 projects**, 8.1 min (`npx playwright test`)
- `14-compare` de-flaked: **8/8** at `--repeat-each=2 --retries=0`
- `tsc --noEmit`: 0 errors. `eslint`: **0 errors, 0 warnings**
- Screenshots: 1 passed, 1.7 min
- **10 mutation probes** across G.2a–G.2c, each applied, observed red, and reverted

---

## 2. Critical context / gotchas (read before touching this code)

### 2.1 Compare is the first mode with TWO runs live at once — it cannot use the shared run wiring

`startGeneratorRun` returns `null` while a run is active, and `generatorStore` holds **one**
`RunState`. That is correct for every other mode: flat/exerciser/trace are a single run, and
profile/ramp are a *sequence* with one live at a time. A comparison is neither.

`comparisonRunner` therefore drives two `createPublicationEngine()` instances **directly**, with
caller-supplied identities (the engine already takes identity from the caller — §7 / B.0), and owns
its own per-side state. **It never writes `generatorStore`.** A unit test pins that the store stays
`idle` throughout a comparison.

Consequences that look like oversights but are not:

- `MessageGeneratorPage` keeps a separate `comparing` flag, folded into `running` and into Zone D's
  `actionStatus`. Without it the mode selector and Zones B/C stay editable mid-comparison and both
  buttons are armed, because the store is idle by design.
- `handleStop` checks `compareHandleRef` **before** `stopActiveRun()`. `stopActiveRun` only reaches
  the single store-backed run, which a comparison never uses.
- Compare renders `CompareResultsPanel` instead of `ProgressPanel`. `ProgressPanel` reads the store,
  so in Compare mode it would show a stale idle run beside live results.

> **If you are tempted to unify this back onto `runStarter`:** merging two sides into one
> `RunState` reports a run neither side performed. That is the defect the separation exists to
> prevent.

### 2.2 An errored side does NOT stop the healthy side (differs from `profileRunner` on purpose)

`profileRunner` aborts the whole profile when a phase errors, because the phases are **sequential**
and continuing would report a shape that never happened. In a comparison the sides are
**concurrent**: cutting the healthy side short turns its real figures into a partial run, which is
worse for a comparison than letting it finish. The report carries each side's real terminal status
and `compareVerdict` refuses to name a winner unless **both** completed.

### 2.3 Latency percentiles are NOT scoped to the run — never present them as if they were

`PeeGeeQMetrics.percentilesFor` reads a per-instance, per-topic Micrometer timer that resets on
backend restart. Per-run scoping is telemetry gap **G5**, still open. So:

- The panel reports `latencySampleDelta` — how many samples **this run** contributed — and states
  the scope in words (`compare-scope-note`).
- A run-scoped p95 would be fabricated. Do not compute one.

### 2.4 Delivery latency is usually ABSENT, and that is correct

The utilities-ui **publishes and does not consume**. Delivery latency (telemetry G2) is recorded at
*claim* time by a consumer, so a queue nothing consumes produces no distribution at all. The panel
renders `not measured`, never `0.0 ms` — a zero would read as instant delivery. The verdict cites
delivery latency **only when both sides measured it**; one side's number is not a comparison, and
two absences are not "equal".

This is visible in capture `48-compare-results.png`: both sides read `not measured`.

### 2.5 G6 is deliberately NOT used by G.2 — the plan's dependency list predates T.2

Both the plan and the telemetry doc listed **G6** (per-message enqueue timestamp + echoed
`x-send-ts`) as a G.2 dependency. That was written when the only route to end-to-end latency was a
client-side join on `correlationId`. **T.2 now measures delivery latency inside the claim statement
on the DATABASE clock**, which is strictly more accurate and needs no message read-back — and
reading messages back to join them sits on management-ui's side of the §8 boundary.

Both documents now carry this as a dated correction rather than a deletion, so a reader of the
original list does not conclude G.2 shipped with a missing dependency. G6 remains delivered and
still useful for §19.5 auto-verification.

### 2.6 Absence is a value; zero is not (inherited from T.1/T.3/T.7, preserved at the client)

- `telemetryService` folds the flat `/stats` percentile fields into one optional object per
  distribution, and treats a **partially** reported block as absent — reporting it would mean
  inventing the missing percentiles.
- `TelemetryCapture<T>` is a discriminated union: a failed read carries its reason and the caller
  **cannot** reach the data without branching. It is not `.recover()`-style erasure.
- `churnDeltaFor` returns `null` — never a zero delta — when either read failed or the table is
  absent from either snapshot.

### 2.7 The two roles are validated, and unknown ≠ wrong

`checkCompareTargets` refuses two queues of the same implementation type, and refuses one queue on
both sides. A type the backend did **not** report is *unknown*, not wrong: it does not block, and it
is named in a non-blocking warning. `CompareTargets` mirrors this — each row auto-selects the first
queue of its own type, falls back to an **unknown** type, and never takes a known-wrong one. With
exact-match-only, a backend sending the names-only payload would leave Compare mode dead.

### 2.8 Per-queue tables are inert — churn lives in `queue_messages` / `outbox`

Confirmed empirically this session (see §3): `demo_events`, `cmp_native` and `cmp_outbox` all show
`n_tup_ins = 0`, while `queue_messages` and `outbox` carry the real inserts. This matches the
setup-db spike's finding that the per-queue table is a marker and both implementations route through
the shared tables by topic. `CHURN_TABLE` in `CompareResultsPanel` encodes exactly this. **Had it
been mapped to the queue-named table, every churn figure would have been zero.**

---

## 3. OPEN DEFECT — the Compare panel under-reports database churn

**Status: diagnosed, not fixed. No code was changed for it.**

### Symptom

In `48-compare-results.png`, the outbox side reads:

| Row | native | outbox |
|---|---|---|
| Acknowledged | 10 | **10** |
| Rows inserted | 10 | **0** |

Ten messages acknowledged and zero rows inserted cannot both be true.

### What it is NOT

Not an error-handling defect. `churnDeltaFor` renders `—` when it cannot know, and the cell showed
`0` — so the `outbox` table was found in **both** snapshots and the delta genuinely computed as
zero. The code behaved correctly on stale inputs.

Not a wrong table mapping. See the evidence below.

### Evidence (read-only diagnostic, long after the runs)

Querying the surviving container databases directly:

| Database | `outbox` | `queue_messages` | Panel had shown |
|---|---|---|---|
| `peegeeq_screenshot_demo_…` | `ins=10`, 10 actual rows | `ins=60` | outbox **0**, native 10 |
| `e2e_compare_db_…` (×3) | `ins=15` | `ins=15` | — |

The inserts are there. The mapping is right. The e2e databases also confirm **both implementations
received identical load** (15 each), which is what makes a churn comparison meaningful at all.

### Root cause

**The final telemetry sample races PostgreSQL's statistics reporting.** PostgreSQL 15 accumulates
`pg_stat_*` per backend and flushes on a rate limit (~1 s), not synchronously at commit. The native
and outbox publish paths commit on **different connections**, so their statistics become visible at
different moments. The final sample landed after the native backend had flushed and before the
outbox one had — which is why one column was right and the other was zero *in the same query*.

`queue_messages` reading correctly at 60 fits: its inserts were spread across the whole screenshot
session, so earlier flushes had long since landed.

### Why the test suite does not catch it

`compare.spec.ts` asserts acknowledged counts and terminal statuses. **It never asserts a churn
figure**, so it passes with this wrong. Any fix must add that assertion.

### Proposed fix (G.2e)

- **Do not add a fixed delay before the final sample.** That is the "strategic delay" antipattern
  the standards doc names.
- **Poll `db-telemetry` until two consecutive samples agree**, bounded by a timeout. That observes a
  condition rather than guessing an interval.
- If it never settles within the bound, report the churn as **unavailable** (`—`), not as zero —
  keeping the absence-not-zero contract.
- Add an e2e assertion on the churn figure so this cannot regress silently.

`pg_stat_force_next_flush()` does **not** help: it flushes only the calling backend, and the
backends that matter are the publisher connections, not the telemetry reader.

The diagnostic script used is in the session scratchpad
(`churn-diagnostic.mjs`); it takes its coordinates from `testcontainers-db.json` and is read-only.
It is not committed — recreate it if needed.

---

## 4. Defects found and fixed this session

Three were in my own tests; one was a real design flaw. All were found by mutation probing or by
the screenshot capture, not by the tests passing.

| # | Defect | How it surfaced |
|---|---|---|
| 1 | A baseline-ordering test passed with the ordering **reversed** — it asserted both the telemetry reads and the engine starts happened, never which came first | Probe stayed green |
| 2 | A stale-selection test **could not fail**: it mocked the *first* queue load as failing, so nothing was ever cached and "clears the stale selection" was never exercised | Probe stayed green |
| 3 | **Real flaw behind (2):** `loadQueues` used `Promise.all`, which fails fast, so one unreachable setup blanked **both** rows including the one whose setup answered fine — contradicting the row-independence rule the same file already tested | Rewriting (2) exposed it |
| 4 | **Latent infinite render:** `CompareTargets` rebuilt its target objects every render, so once the page stored them in state the `onChange` effect would loop forever | Found while wiring the page |

Notes worth keeping:

- (1) was fixed with **one sequence log shared by the engine and telemetry fakes**. Asserting that
  two things happened proves nothing about their order.
- (3) is now `Promise.allSettled` with **per-setup** errors. A new test covers a setup that
  succeeded and then failed on a later load — the genuinely reachable stale case, which had no
  coverage.
- (4) is structurally invisible to `CompareTargets`' own tests: their `onChange` is a spy that
  triggers no render. Only a parent that stores the value can see it.
- One **existing** test was correctly stale: it asserted Compare was *absent* from the mode list, on
  the rule that a dead control promises behaviour the app lacks. The rule stands; the mode is now
  real, so the assertion flipped.

### E2E harness trap (cost a run)

`CompareTargets` auto-selects the first setup, which **may already be the spec's own**. Clicking an
already-selected antd option races the dropdown closing itself (`element is not stable` → `not
visible`). Both `compare.spec.ts` and `screenshots.spec.ts` now open the dropdown **only when a
change is needed**, then assert the end state either way.

---

## 5. Document status corrected (docs are not state)

Six claims were stale or wrong and are now fixed against the code, each dated rather than silently
overwritten:

| Where | Was | Now |
|---|---|---|
| Plan, dependency graph | T "gates the two telemetry-heavy G tools" | G.2 shipped; G.1b alone blocked, on T.4+T.5 |
| Plan, Phase G table | G.2 "Needs Phase T: G1+G2+G6+G7" | DONE, with what was actually consumed |
| Plan, build order | "G.1b and G.2 land only after Phase T" | G.1b is the only Phase G step still open |
| Plan, Phase T gates | "Gates: G.1b **and G.2**" | G.1b only |
| Plan, T.7 record | "No UI consumes `db-telemetry` yet" | **Factually wrong** — struck through, with what consumes it |
| Plan, scope boundaries | "gates *only* two Phase G tools" | Only one since 2026-08-07 |

Plus the telemetry doc's §6 matrix (which called §19.2 "the one tool that genuinely can't be built
well client-side") and its §7 prioritisation line listing G6.

---

## 6. Pick up here

1. **G.2e — fix the churn sampling race (§3).** Highest value: it is the §4A comparison's whole
   point and it is currently wrong on screen.
2. **Design doc Appendix A** — captures `47-compare-mode.png` and `48-compare-results.png` exist and
   are committed, but **Appendix A has no entries for them**. Every prior capture set was recorded
   there. This is the one loose end from the screenshot run.
3. **G.1b** — the only remaining Phase G step. Still blocked on **T.4** (G3, resource saturation)
   and **T.5** (G4, ≥1 Hz stream).
4. **T.4 / T.5 / T.6** — the remaining Phase T rows. Per the T.3 handoff's §2.1 lesson: **probe the
   running backend before implementing any row**; the gap may not be where the row says.

---

## 7. Standing debt (unchanged, not blocking)

1. **The duplicate saved-`RunConfig` store stands** (recorded decision 2026-08-01):
   `peegeeq_scenarios` and `peegeeq_schedule_templates` hold the same shape. Do not "fix" one in
   isolation.
2. **A comparison cannot be saved as a scenario, nor scheduled.** `Scenario` and `ScheduledRun` hold
   one target; a comparison has two. Both are refused with a stated reason, asserted in the e2e with
   targets selected. Deliberate boundary, same precedent as ramp/exerciser/trace.
3. **The whole-repo Java gate** (`mvn clean test -Pall-tests`) was **not** run this session — no
   Java changed. The T.7 handoff recorded it red in `peegeeq-db` (8 errors, 6 of them
   `SetupBindingPersistenceIntegrationTest`); nothing here addresses that.

---

## 8. Test commands (utilities-ui)

```powershell
cd peegeeq-utilities-ui

# Unit — the fast loop
npx vitest run src/tests/unit/comparePlan.test.ts     # one file
npm run test:run -- --reporter=dot                    # all 52 files (~7-11 min)

# Static gates
npm run type-check
npm run lint

# E2E — one project, then the gate
npx playwright test --project=14-compare --reporter=list
npx playwright test --project=14-compare --repeat-each=2 --retries=0   # de-flake a new project
npx playwright test --reporter=list                                     # all 14 projects (~8 min)

# Screenshots — REWRITES COMMITTED PNGs, so this is the user's call
npx playwright test --config=playwright.screenshots.config.ts
```

> **Set the working directory explicitly.** The agent shell's cwd reset between calls more than
> once this session, and a vitest run from the repo root silently loses the module's jsdom
> environment — the tell is `environment 0ms` and `document is not defined` on every test.

---

## 9. What was NOT done / not checked

- **The churn sampling race is diagnosed but NOT fixed** (§3). The panel currently under-reports
  database churn, and the e2e does not assert churn at all.
- **Appendix A was not updated** for captures 47/48 (§6 item 2).
- **No Java was touched**, so no Java suite was run — including the known-red `peegeeq-db` gate.
- **`processingTime` percentiles were never observed non-absent.** Both the e2e and the screenshot
  ran with nothing consuming the queues, so every latency figure this session read `not measured`.
  The rendering path for a *present* distribution is covered by unit tests with synthetic data only.
- **The `db-telemetry` cluster block is fetched and typed but not displayed.** `DbClusterStats`
  (locks, WAL, checkpoints, xid age, long-txn) is carried in the report and unused by the panel;
  §4A ties those signals to breaking-point attribution (G.1b) rather than to the comparison.
- **No performance measurement** of running two engines concurrently in one browser tab. §7.1's
  ceiling note (`6 × maxBatchSize / avgLatency`, browser connection limit) applies **per origin**,
  so two concurrent fan-outs share it. Not measured; the tested loads were tiny (5–10 msg/s).
- **`comparisonRunner`'s stop-before-baseline path** is unit-tested with fakes but never exercised
  against a real backend — the e2e stops a comparison that is already publishing.
