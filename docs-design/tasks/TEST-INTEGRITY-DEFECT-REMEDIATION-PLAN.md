# Test Integrity Defect Remediation Plan

Opened: 2026-08-11
Origin: reading `logs/peegeeq-outbox-integration-20260811.txt` — a run reporting
`Tests run: 537, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS` while logging
46 ERROR-level exceptions.

---

## The single root cause

Every defect in this register is one of two things: a test that reports success without
verifying anything, or a failure that is only ever written to a log. None of them is new
breakage. They became visible because the detection method changed — reading logs instead
of exit codes, and making failure handlers honest.

Three forms of the same defect:

| Form | Effect |
|---|---|
| `.onFailure(err -> { log; completeNow(); })` | Test passes whether the operation succeeded or failed |
| Gated test that never activates | `Tests run: N, Skipped: N` reported as `BUILD SUCCESS` |
| Production failure handled by `logger.error` only | No test, health signal, or caller ever sees it |

**D11 is the systemic fix.** Everything above it is remediation of instances.

---

## Defect register

Status values: `FIXED` (change made and verified this session), `EXPOSED` (now failing
honestly, not yet fixed), `OPEN` (not started), `NOT REPRODUCED` (diagnosed from artifacts
only).

| ID | Defect | Status | Evidence |
|---|---|---|---|
| D1 | `HealthCheckManager` interpolates schema unquoted; reserved-word schema breaks every queue health check | FIXED | `PostgreSqlIdentifierValidatorTest` 20/20; `HealthCheckManagerTest` 16/16 |
| D2 | `sendInOwnTransaction(..., TransactionPropagation)` throws NPE inside `Pool.withTransaction` | FIXED | `OutboxProducerTransactionTest` 20/20 |
| D3 | `outbox_consumer_groups` has two incompatible DDL definitions; both background jobs fail every run | NOT REPRODUCED | `42703` in outbox integration log; template vs `V001` diff |
| D4 | Pass-on-failure teardown/setup swallows — 53 sites | FIXED | 53 fixed across 6 modules; outbox, native, bitemporal, db Group B re-run green |
| D4-A | 4 tests that pass when the subject fails — needs rewrite, not conversion | OPEN | see below; expect red once honest |
| D5 | `-Pperformance-tests` never activates gated tests — redundant `@EnabledIfSystemProperty` gate on top of the tag | FIXED | 8 tests now execute (db 5, outbox 3); previously all skipped |
| D6 | `OutboxPerformanceTest` NPE masked the send failure that caused it | MASKING FIXED, CAUSE EXPOSED | now `ConnectionPoolTooBusyException: max wait queue size of 128` |
| D7 | Background timer failures log unbounded ERROR, no escalation, no health signal | OPEN | `totalRuns=1, totalFailures=1` both jobs; commit e79030da known gaps |
| D8 | Antipatterns doc gave two opposite rules for `@AfterEach` failure handling | FIXED | 3 blocks corrected to `failNow` |
| D9 | `CLAUDE.md` Step 5 guard list could not detect pass-on-failure | FIXED | entry added |
| D10 | `PeeGeeQManager.closeReactive()` can return a Future that never settles | OPEN | commit e79030da known gaps; not re-observed |
| D11 | Unasserted ERROR logs do not fail the build | OPEN | 46 exceptions in a green run |
| D12 | `PostgreSQLErrorHandlingTest` teardown exceeded a **60 s** budget — third occurrence of D10 | OPEN | `tearDown:121 expected: <true> but was: <false>`, class elapsed 65.5 s |
| D13 | 6 `@Disabled` tests in `ConsumerGroupSubscriptionTest`, one hiding a known race condition | OPEN | 5× "requires SubscriptionManager integration", 1× "setMessageHandler() has a race condition" |

### D12 detail

The budget was raised from 30 s to 60 s on 2026-08-09 after timeouts on 2026-05-23 and
2026-08-09. The in-code comment states plainly: "The slow-close mechanism under load is NOT
diagnosed." It has now failed at 60 s.

Raising the budget is the threshold-masking anti-fix the standards doc bans under
"CRITICAL: 'Strategic Delay' and Threshold-Masking Anti-Fixes". Raising it again is not the
fix. This is the same defect as D10 (`closeReactive()` may never settle), now with a third
observation and a harder bound.

Compounding it: that same teardown is a D4 pass-on-failure site — `.onFailure(err -> { warn;
completeNow(); })`. Teardown *failure* is swallowed; only teardown *timeout* fails the test.
The P1b sweep fixes the swallow, which will make the real failure mode visible.

### D13 detail

These are `@Disabled` with reasons, not the commented-out-`@Test` antipattern, so they are
visible in reports. But `pgq-coding-principles.md` states: "NEVER skip failing tests — it is
entirely non-professional and unacceptable. All test failures must be fixed, never skipped
with `@Disabled`." One reason names an undiagnosed production defect:
"Native queue `setMessageHandler()` has a race condition - thread safety needs implementation fix."

### D1 detail

`SQL_IDENTIFIER` (`^[A-Za-z_][A-Za-z0-9_]*$`) blocks injection but matches every reserved
word — `select`, `order`, `user` all pass. Unquoted, `select.outbox` is a syntax error.
Fixed by adding `quote`/`qualify` to the existing `PostgreSqlIdentifierValidator` rather
than writing a third copy of the quoter.

**Open gap:** no test runs `HealthCheckManager` against a reserved-word schema. The unit
tests pin the quoting contract; they do not prove the health check passes end to end.
`OutboxSchemaQuotingTest` asserts only `getStatsAsync`, `countMessagesAsync`,
`purgeMessagesAsync` — which is why this survived.

### D2 detail

```
NullPointerException: ... "context" is null
  at io.vertx.sqlclient.Pool.withTransaction(Pool.java:161)
  at OutboxProducer.lambda$sendInOwnTransactionInternal$0(OutboxProducer.java:330)
```

Three tests reported green for five months while failing on every run. Unknown: whether a
caller already on an event loop behaves differently. That is the first thing P2 must
establish, and it may split the phase.

### D3 detail

Error code is `42703` (undefined_column), not `42P01` (undefined_table) — the tables exist
and the columns are missing.

| | Template `08c-consumer-table-groups.sql` | Migration `V001__Create_Base_Tables.sql` |
|---|---|---|
| message ref | `message_id` | `outbox_message_id` |
| group | `group_name` | `consumer_group_name` |
| timing | `claimed_at`, `completed_at` | `processing_started_at`, `processed_at` |
| locking | `lock_id`, `lock_until` | absent |

`V010` reconciles them with conditional `RENAME`/`ADD`/`DROP`. `ConsumerGroupRetryService`
queries the template shape. Any database between `V001` and `V010`, or provisioned by a
path running neither to completion, fails every job tick.

### D4-A — the 4 that need rewriting, not converting

These are the CRITICAL placeholder-test class, not the teardown class. Converting the
handler is not enough; each asserts nothing meaningful today.

| Site | Why it always passes |
|---|---|
| `RealTimeStreamingIntegrationTest:210` (rest) | Three independent pass paths: connection failure swallowed, a 5 s `setTimer → completeNow` that fires *because* no message arrived, and an assertion guarded by `if ("welcome".equals(type))` so any other frame asserts nothing |
| `WebSocketHandlerTest:395` (rest) | `assertTrue(status >= 200 \|\| status >= 400)` — every status ≥ 200 satisfies the first clause; second is unreachable. Plus the failure swallow |
| `CrossLayerPropagationIntegrationTest:383` (rest) | DB verification declared optional by comment: "might fail if table structure is different — that's OK" |
| `HealthCheckManagerTest:468` (db) | "expected after pool close" — if genuinely expected it must be asserted as expected, not swallowed |

Fix requires stating what each endpoint must do — a `welcome` frame carrying a
`connectionId` within a bounded wait, a specific upgrade status code — and failing
otherwise. **Expect red.** If the WebSocket layer is incomplete, honest tests will say so.
That is a scope decision with its own budget, not a mechanical edit.

### D4-B — setup precondition (done)

Three `@BeforeEach` pre-cleanups swallowed their failure, so a class documented as needing
"a clean slate" would run against another class's leftovers — the same mechanism as the
cross-test pollution recorded at lines 847-922. Converted to `failNow`; 45 tests green.

### D4 fixed, by module

| Module | Occurrences |
|---|---|
| peegeeq-native | 27 |
| peegeeq-db | 10 |
| peegeeq-examples | 9 |
| peegeeq-rest | 3 |
| peegeeq-bitemporal | 3 |
| peegeeq-service-manager | 1 |
| **Total** | **53 across 38 files** |

Ruling (2026-08-11): `failNow` everywhere, including `@AfterEach`. A failed close leaks
pool connections; the doc's own evidence is 232 silent teardown skips that cascaded into
710 "too many clients" errors.

Detection requires a body-scoped scan. A flat regex runs past the handler's closing brace
and reports the next `completeNow()` in an `else` branch — that error produced an initial
overcount of 75/52 before correction to 66/47.

### D5 detail — resolved

`peegeeq.performance.tests` is a Maven property (`pom.xml:129`, set true at `607` and
`640`). The root pom has no `<systemPropertyVariables>` block and surefire is declared
version-only inside `<pluginManagement>`, so the property was never forwarded to the test
JVM and `@EnabledIfSystemProperty(named = "peegeeq.performance.tests")` could never be
satisfied by the profile.

**Fix: removed the gate, not added a forwarder.** `@Tag(TestCategories.PERFORMANCE)` was
already present on all three classes and the profile architecture already selects on it.
The system-property gate was a second, redundant mechanism — the "never keep both" case.
Deleting it means one selection mechanism, which is what the profile doc claims exists.

Three classes were affected and none had been executing:
`OutboxPerformanceTest` (3), `PeeGeeQPerformanceTest` (4),
`PeeGeeQReactiveConnectionPoolPerformanceTest` (1).

### D6 detail — masking fixed, cause open

`awaitCompletion` returns true for a context completed by `failNow`, so a failed send left
`sendCompleteTimeRef` null and the test died in `Duration.between` with
`NullPointerException: temporal`. A new `failIfContextFailed` helper, applied at all three
`awaitCompletion` sites, now rethrows `causeOfFailure()`.

The cause it revealed:

```
ConnectionPoolTooBusyException: Connection pool reached max wait queue size of 128
```

`testThroughputPerformance` fires 1000 sends concurrently at a pool that cannot queue them.
**Open decision, not a defect to fix blind:** either size the pool for the load or bound the
in-flight sends. Those measure different things, and which one this test is meant to measure
is a call for the owner. It is not a production defect — the pool rejected work it was
configured to reject.

---

## Phases

One phase at a time. Each has an entry condition, an exit condition, and a scoped
verification command. The agent runs the scoped verification and reports per-class
`Tests run:` lines; `-Pall-tests` stays with the user.

### P0 — Investigation and guard correction — COMPLETE

Fixed D8 and D9. Without these, any sweep would have been reintroducing the pattern from
the standards doc's own "Correct Pattern" examples.

### P1 — Pass-on-failure sweep (D4)

Module by module, not one sweep. Each module's conversions are verified before the next.

- **P1a peegeeq-outbox — COMPLETE.** 13 sites, 9 files. Integration set 66 tests / 3 errors
  (the 3 are D2). `OutboxQueueUnitTest` 26/26. All 8 teardown conversions pass, so no
  teardown was silently failing.
- **P1b peegeeq-native** — 27 sites. Largest remaining.
- **P1c** — peegeeq-db, examples, rest, bitemporal, service-manager. 26 sites.

Exit: body-scoped scan returns zero for git-tracked test sources; each module's scoped run
reported with per-class counts.

### P2 — `TransactionPropagation` contract (D2)

Entry: P1a complete (done) — the three tests now fail honestly.

1. Probe first. Call `sendInOwnTransaction(..., CONTEXT)` from inside `vertx.runOnContext`
   and assert the row lands. This establishes whether the path works at all. No test
   currently proves it does.
2. Depending on the probe: either guard the missing context and return a failed Future
   naming the requirement, or fix the deeper defect the probe reveals.

Exit: `OutboxProducerTransactionTest` green with both the on-context and off-context cases
asserted.

### P3 — Schema identifier quoting (D1) — COMPLETE except one test

Remaining: `HealthCheckManager` against a schema named `select`, asserting all three queue
checks report healthy. Requires reading `HealthCheckManagerTest` (665 lines) first, per
Step 4.

### P4 — Background jobs (D3, D7)

1. **Diagnose, do not fix.** Reproduce the `42703` in isolation and capture which relation
   resolved and what its columns are. The DDL divergence is the likely cause but is not
   proven to be the one that fired.
2. Then either converge the two definitions onto one source of truth, or add a precondition
   check so the jobs do not start against a schema they cannot query.
3. Independent of cause: one escalation mechanism — full stack on first failure, then a
   rate-limited summary carrying a consecutive-failure count, surfaced as a health signal.
   Apply to `ConsumerGroupRetryJob`, `DeadConsumerDetectionJob`, and the depth-cache timer.

### P5 — Performance profile (D5, D6)

1. Forward the flag: add `<systemPropertyVariables>` to the surefire config, or change the
   three gates to use the tag rather than a system property. Prefer one mechanism, not both.
2. Then fix what the newly-running tests reveal, starting with D6.

Exit: `-Pperformance-tests` reports a non-zero executed count for all three classes.

### P6 — Make unasserted ERROR logs fail the build (D11)

The systemic fix, and the only item that prevents the next round of this.

A surefire listener that fails a test class when it logged at ERROR without a registered
expectation. Fault-injection tests declare what they expect; everything else becomes red.

This needs its own data-model pass before any code: what counts as an expectation, where it
is declared, how a deliberate fault registers one. Scope it separately — do not bolt it
onto P4 or P5.

---

## Out of scope

- **D10** (`closeReactive()` may never settle) — recorded in commit e79030da, observed once
  in eleven runs, not re-observed here. Needs its own investigation.
- **Converging the duplicate quoters** in `OutboxFactory` and `OutboxConsumer` onto
  `PostgreSqlIdentifierValidator`. Correct to do, but a pure refactor of working code; it
  waits until P3's tests are green.
- Anything requiring `-Pall-tests`. That is the release gate, not a step in this work.

---

## Status discipline

This file is the register. Chat is not. Any status here must be traceable to a command
output, and anything diagnosed from artifacts alone is marked `NOT REPRODUCED` rather than
described as understood.
