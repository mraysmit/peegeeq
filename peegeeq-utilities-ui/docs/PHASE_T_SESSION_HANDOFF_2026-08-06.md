# Phase T — Session Handoff (2026-08-06)

**Start-here note for the next session.** Phase T closes the backend telemetry gaps that gate the
two heavy Phase G tools. This session shipped **T.3 (telemetry gap G6)** and fixed **two
pre-existing test defects** found by running the whole `peegeeq-rest` suite. The full
`-Pall-tests` gate is **green** (user-run, 2026-08-06).

**The single most useful thing in this document:** T.3 turned out to be *nothing like* what its
plan row described, and only an empirical probe revealed that. Read §2.1 before touching any
remaining Phase T row — the same "the gap is not where the doc says" risk applies to T.4–T.7.

Authoritative spec: [`PEEGEEQ_ADMIN_DEVOPS_TELEMETRY_REQUIREMENTS.md`](PEEGEEQ_ADMIN_DEVOPS_TELEMETRY_REQUIREMENTS.md) §4, §4A, §5.
Phase tracking: [`PEEGEEQ_DEVOPS_UTILITIES_IMPLEMENTATION_PLAN.md`](PEEGEEQ_DEVOPS_UTILITIES_IMPLEMENTATION_PLAN.md) → "Phase T".
Prior handoff: [`PHASE_G_SESSION_HANDOFF_2026-08-02.md`](PHASE_G_SESSION_HANDOFF_2026-08-02.md) (Phase G tool suite).

---

## 1. Done this session (green)

| Step | What | Where |
|---|---|---|
| T.3 | `enqueuedAt` + `headers` on the live SSE message frame (telemetry G6) | `peegeeq-rest` `ServerSentEventsHandler` |
| T.3 | Integration test: enqueue timestamp + echoed `x-send-ts` on the stream | `peegeeq-rest` `SseMessageStreamDemoIntegrationTest` |
| fix | Two independent pre-existing defects (see §3) | `peegeeq-rest` `SubscriptionPersistenceAcrossRestartIntegrationTest` |
| docs | Test-command gate rule rewritten (see §5) | `docs-design/testing/PEEGEEQ-TEST-COMMANDS.md` |

All committed in **`6cac7938`**; the working tree is clean.

**Verification:** `peegeeq-rest` `-Pall-tests` 504 passed / 0 failures / 0 errors; default
profile 172 passed. Whole-repo `mvn clean test -Pall-tests` **green (user-run)**.

---

## 2. Critical context / gotchas (read before touching this code)

### 2.1 The T.3 gap was not where the plan row said — verify before building

The row read *"per-message enqueue timestamp + echoed client `x-send-ts` header on consume"*, which
implies both are missing. Probing a running backend first showed:

- **The browse endpoint already satisfied G6** and had for a long time. `GET
  /api/v1/queues/{setup}/{queue}/messages` returns `createdAt` and the full `headers` map, and the
  publish path passes client headers straight through (`headers.putAll(request.getHeaders())`).
  A client `x-send-ts` round-trips intact.
- **The live SSE stream did not.** Its frame carried only
  `{type, connectionId, messageId, payload, timestamp}`.

Had the row been implemented as written, the work would have duplicated an existing mechanism on
one path and missed the actual gap on the other. **Probe the running backend before implementing
any remaining Phase T row.**

### 2.2 The SSE frame carries TWO clocks — do not confuse them

| Field | Meaning |
|---|---|
| `enqueuedAt` | When the message was ENQUEUED (`created_at`). **This is the one to measure latency against.** Absent if unknown — never zeroed. |
| `headers` | The message's own headers, carrying any client correlation/send-time header. Absent when the message has none. |
| `timestamp` | **Emit time** — `System.currentTimeMillis()` when the frame was written. Predates T.3, kept for compatibility. |

`timestamp` is the trap. In the probe it landed **45 ms after** the client's send, so a consumer
treating it as delivery latency gets a small, entirely plausible number that measures only the
server's own emit delay. It was left in place deliberately (additive change, no consumer breaks)
and its meaning is now documented on the handler. **Any UI consuming this stream must use
`enqueuedAt`.**

### 2.3 Absence, not zero

Both new fields are **omitted** when unknown rather than defaulted. This follows the T.1 precedent
recorded in `a08b7c74`: *"'No data' is the ABSENCE of the object (null), never zeroed values — a
zeroed percentile would claim a 0 ms tail."* Keep it.

### 2.4 Build traps that cost real time this session

1. **Stale `.m2` for changed upstream modules.** `peegeeq-rest` tests failed to compile against
   `QueueStats.getProcessingTimePercentiles()` (added to `peegeeq-api` in `a08b7c74`) because the
   local repo held the old jar. Fix:
   `mvn install -pl :peegeeq-api,:peegeeq-db,:peegeeq-native,:peegeeq-outbox -am -DskipTests`.
   (Same class of trap the Phase S handoff recorded for `peegeeq-db`.) Not needed for a full
   reactor build — `-Pall-tests` without `-pl` builds in dependency order.
2. **IDE-compiled `target/classes` with baked-in errors.** A plain `mvn test` reused Eclipse-JDT
   output containing `java.lang.Error: Unresolved compilation problems`. That exact error text is
   the giveaway; `mvn clean` is the fix. This survived a `.m2` refresh and looked like a real test
   failure.
3. **`peegeeq-utilities-ui/testcontainers-db.json` is stale** — points at a dead port with no
   container behind it, so anything trusting it gets a 503. Git-ignored, local-environment only.

### 2.5 Reading Maven results

- **The background-task exit code is the SHELL's, not Maven's.** A run reported "exit code 0" while
  the log said `BUILD FAILURE`. Capture Maven's own status explicitly.
- **`Tests run: 0` on an outer class is normal** when its tests live in `@Nested` inner classes —
  surefire reports the outer shell separately. `ConfigRetrieverIntegrationTest` and
  `RestServerConfigTest` do this legitimately; their 50 tests run under both profiles.
- **`-Pall-tests` genuinely runs everything.** It is the single guarantee; if a test exists and
  `-Pall-tests` does not execute it, that is a bug worth filing.

---

## 3. The two pre-existing defects fixed (both in one test class)

`SubscriptionPersistenceAcrossRestartIntegrationTest` had been red since **2026-07-17** — roughly
seven weeks — which means the full `peegeeq-rest` suite had not been run in that window. Two
independent causes, neither a cascade of the other:

| Defect | Introduced by | Symptom |
|---|---|---|
| Provisioned into the container's **shared** database (`postgres.getDatabaseName()`, `templateDatabase: null`) | **S.0**, 2026-07-17 — `create` made non-destructive | `409 Setup already exists` |
| Schema hardcoded `"peegeeq"` while the setup provisions `peegeeq_test` (`TEST_SCHEMA`) | **`0a86ed54`**, 2026-06-12 | `relation "peegeeq.outbox_topic_subscriptions" does not exist` |

The second would have failed *even after* the 409 was fixed. The dead `databaseName` field —
declared but never assigned — was the tell that the class once followed the convention.

**The fix was determined by the module's own convention, not invented.** A sweep of ~50
`"databaseName"` call sites across `peegeeq-rest` tests shows the long-standing pattern: a unique
per-run name (`"<prefix>_" + System.currentTimeMillis()`), `schema` from
`PostgreSQLTestConstants.TEST_SCHEMA`, `templateDatabase: "template0"`, and **no** manual
`PeeGeeQTestSchemaInitializer` call — `create` provisions the schema.

Only two other sites use `postgres.getDatabaseName()`, and both are legitimate:
`DatabaseSetupConnectIntegrationTest` uses it for **connect** (attaching to an existing database is
what connect is for) and in a **negative test** asserting 400 when the schema is absent;
`RestApiExampleTest` only builds the request object and never POSTs it.

> **If you hit a similar failure: sweep the call sites first.** Establishing the convention turned
> what looked like a design choice into a single obvious repair.

---

## 4. Pick up here — Phase T remaining (recommended order)

### T.7 — database-level queue-table telemetry (do this next; it unblocks G.2)

**Why first:** G.2 (native-vs-outbox comparison) needed G1 + G2 + G6 + G7. T.1, T.2 and now T.3
have closed the first three. **T.7 is the last dependency** — finishing it unblocks the one Phase G
tool that genuinely cannot be built client-side.

- Spec: telemetry §4A. Expose `pg_stat_user_tables` churn / dead-tuple / vacuum / scan / size for
  the setup's `queue_messages`, `outbox`, `dead_letter_queue` and per-queue tables, plus cluster
  signals (long-txn/`xmin`, locks, WAL, checkpoints, xid-age).
- §4A notes: sample the DB queries at **~5 s, not 1 Hz**; **baseline-and-delta** the cumulative
  `pg_stat_*` counters over the run window; `pgstattuple` is optional (else use the
  `n_dead_tup`/`n_live_tup` estimate).
- Rationale worth keeping in view: for a Postgres-backed queue the real bottleneck is
  INSERT/DELETE churn → dead-tuple bloat → autovacuum lag → scan degradation — all invisible to
  app-level counters.

### T.4 / T.5 — then G.1b becomes possible

G.1b (rich ramp attribution) needs G3 (T.4, resource saturation), G4 (T.5, ≥1 Hz stream) and G7
(T.7). With T.7 done, T.4 and T.5 are what remain for it.

### T.6 — per-run / correlation scoping

The plan already records an accepted tool-side workaround (dedicated queue per run), so this is the
lowest-value row unless per-run scoping is wanted for its own sake.

### Phase G status for context

Done: G.1a, G.3, G.4, G.5-send, G.6. Blocked: **G.2** (needs T.7 only), **G.1b** (needs T.4, T.5,
T.7).

---

## 5. Standing debt (not blocking, but real)

1. **utilities-ui screenshots — RESOLVED, no action needed.** An earlier draft of this document
   claimed Ramp mode had no capture. That was wrong: `42-ramp-mode.png` exists, was added in
   `887425eb`, and is referenced from the design doc's Appendix A. The gallery now holds 45 PNGs
   (up from the 40 left mid-session). Regenerate only if the UI changes again:
   `npx playwright test --config=playwright.screenshots.config.ts`.
2. **A ramp cannot be saved as a scenario.** `Scenario` has no ramp kind; saving is blocked with a
   reason rather than silently storing it as flat. Deliberate boundary — see the Phase G handoff.
3. **The duplicate saved-RunConfig store stands** (recorded decision 2026-08-01):
   `peegeeq_scenarios` and `peegeeq_schedule_templates` hold the same shape. Do not "fix" one in
   isolation.
4. **4 lint warnings** in utilities-ui, all `react-refresh/only-export-components`. Warnings do not
   fail the gate.
5. **`npm run test:integration` finds no test files** and passes via `--passWithNoTests`. That leg
   proves nothing.

---

## 6. Test commands — the gate rule was rewritten this session

`docs-design/testing/PEEGEEQ-TEST-COMMANDS.md` previously read *"After ANY code change, the only
acceptable validation command is `-Pall-tests`."* That is a ~75-minute whole-repo run, so as an
absolute rule it made the edit-test loop — and therefore TDD — impossible. It now reads **"scoped
runs to iterate, `-Pall-tests` to gate"**.

The original rule was reacting to real damage (months of silently skipped tests), but it
misdiagnosed the cause: the failure was **partial results being reported as whole-repo
validation**, not people running fast tests. That ban is preserved and sharpened:

- Never describe a scoped run as "the suite passes" — say what ran:
  *"`peegeeq-rest` integration: 504 passed"*.
- `mvn test -pl :module` (no profile) runs `@Tag("core")` **only** and silently skips every
  integration test in that module. Always name the profile when reporting.
- A scoped green does not clear a change for commit. Only the gate does.

```powershell
# THE GATE — every tag, every module (~75m). Before commit / push / release.
mvn clean test -Pall-tests 2>&1 | Tee-Object -FilePath logs\all-tests-20260806.txt

# Iterating on one class (the TDD loop)
mvn test -Pintegration-tests -pl :peegeeq-rest -Dtest=SseMessageStreamDemoIntegrationTest 2>&1 | Tee-Object -FilePath logs\sse-stream-20260806.txt

# One module, both profiles (pre-change baseline)
mvn test -pl :peegeeq-rest 2>&1 | Tee-Object -FilePath logs\peegeeq-rest-core-20260806.txt
mvn test -Pintegration-tests -pl :peegeeq-rest 2>&1 | Tee-Object -FilePath logs\peegeeq-rest-integration-20260806.txt

# After any run
Get-Content logs\<name>.txt -Tail 30
```

> The doc also states: run Maven commands manually in the terminal rather than through the agent
> (~60 KB output cap, unreliable timeouts). Honour that unless explicitly asked otherwise.

---

## 7. What was NOT done / not checked

- **T.4, T.5, T.6, T.7 untouched.**
- **No UI consumes `enqueuedAt` or `headers` yet.** T.3 exposes the data; nothing reads it. The
  delay/FIFO auto-verification and the latency join that G6 exists to enable are still to build.
- **Only the `peegeeq-rest` module was exercised by me.** The whole-repo gate was run by the user,
  not by me — I did not independently verify other modules against the `ServerSentEventsHandler`
  change.
- **The `x-send-ts` echo was verified on the SSE stream and the browse path by probe**, but no
  automated test covers the *browse* path's header echo — that behaviour predates T.3 and remains
  untested by anything I added.
- **No performance measurement** of the additional per-frame JSON work (two extra fields per
  observed message). Expected negligible; not measured.
