# Test Integrity Defect Remediation Plan

Opened: 2026-08-11
Last reconciled: 2026-08-20 against commit
`a5625fa5` plus the completed P4, P6/D11, and D10/D12 worktree described below
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

Status values: `FIXED` (change made with targeted verification), `PARTIALLY FIXED` (some
registered instances fixed, with enumerated work still open), `EXPOSED` (now failing
honestly, not yet fixed), `OPEN` (not started), `NOT REPRODUCED` (diagnosed from artifacts
only), and `MASKING FIXED, CAUSE EXPOSED` (the misleading failure was removed, revealing
an unresolved cause).

| ID | Defect | Status | Evidence |
|---|---|---|---|
| D1 | `HealthCheckManager` interpolates schema unquoted; reserved-word schema breaks every queue health check | FIXED | contract tests plus `HealthCheckManagerCoreTest#testQueueHealthChecksWithReservedWordSchema` 1/1 against schema `select` |
| D2 | `sendInOwnTransaction(..., TransactionPropagation)` throws NPE inside `Pool.withTransaction` | FIXED | `OutboxProducerTransactionTest` 20/20 |
| D3 | A hand-written outbox test fixture stopped at the V001-era `outbox_consumer_groups` shape; current background-service queries then failed with `42703` | FIXED | V001 reproduction fails before V010; V010/V015 diagnosis succeeds; shared-initializer background-service contract 1/1 |
| D4 | Pass-on-failure handlers | FIXED | tracked-source expression scan returns zero; static async guards 8/8 green on 2026-08-19 |
| D4-A | 4 tests that passed when the subject failed | FIXED | REST contracts rewritten in `bbfa370e`; DB cleanup failure fixed in `bdd72071` |
| D5 | `-Pperformance-tests` never activates gated tests — redundant `@EnabledIfSystemProperty` gate on top of the tag | FIXED | 8 tests now execute (db 5, outbox 3); previously all skipped |
| D6 | `OutboxPerformanceTest` NPE masked the send failure that caused it | FIXED | `2b35bbdc` pins pool size and bounds sends to 20 sequential lanes instead of a 1000-send burst |
| D7 | Background timer failures had no common bounded escalation or health signal | FIXED | tracker core contracts 3/3, job core contracts 5/5, depth-cache integration 5/5, job lifecycle integration 6/6 |
| D8 | Antipatterns doc gave two opposite rules for `@AfterEach` failure handling | FIXED | 3 blocks corrected to `failNow` |
| D9 | `CLAUDE.md` Step 5 guard list could not detect pass-on-failure | FIXED | entry added |
| D10 | `PeeGeeQManager.closeReactive()` can return a Future that never settles | FIXED | red settlement proof; final settlement contract 2/2; public Future now settles before manager-owned Vert.x termination starts |
| D11 | Unasserted ERROR logs do not fail the build | FIXED | normal Maven profiles enable the packaged gate; real canary failed 1/1 unclaimed and passed 1/1 with an exact expectation; final gate contracts 30/30 |
| D12 | `PostgreSQLErrorHandlingTest` teardown exceeded a **60 s** budget — third occurrence of D10 | FIXED | D10 root cause fixed without raising the budget; original native class passes 5/5 |
| D13 | 6 `@Disabled` tests in `ConsumerGroupSubscriptionTest`, one hiding a known race condition | FIXED | production start-position/race fixes and six restored tests in `20537c83`; `DisabledTestsGuardTest` added in `2b35bbdc` |

### 2026-08-20 P4 worktree and verification

The nine Java implementation/test files not yet committed are:

- `OutboxSchemaQuotingTest` — replaces its hand-written V001-era schema with the shared
  Flyway initializer and directly exercises both background services against a reserved-word
  schema.
- `BackgroundTaskFailureTracker` and its core test — shared first-failure, persistent-summary,
  health-transition, and recovery policy.
- `PeeGeeQManager` and `PeeGeeQManagerTimerGuardTest` — depth-cache timer wiring, health-check
  registration, and real migrated-schema integration coverage.
- `ConsumerGroupRetryJob`, `ConsumerGroupRetryJobShutdownCoreTest`,
  `DeadConsumerDetectionJob`, and `DeadConsumerDetectionJobFailureHealthCoreTest` — shared
  policy wiring and shutdown/failure contracts.

P4 verification is green. The fixture rebuild passed; `OutboxSchemaQuotingTest` passed 6/6,
including the direct retry/detection service contract. The D7 reactor-slice rebuild passed.
The D7 core scope passed 8/8 (`DeadConsumerDetectionJobFailureHealthCoreTest` 1/1,
`ConsumerGroupRetryJobShutdownCoreTest` 4/4, `BackgroundTaskFailureTrackerTest` 3/3), the
depth-cache integration scope passed 5/5, and the two background-job lifecycle classes passed
6/6. Authoritative logs are under `logs/`:

- `p4a-v001-v010-relation-diagnosis-20260820.txt`
- `p4a-subscription-v001-v010-v015-diagnosis-20260820.txt`
- `p4b-outbox-fixture-rebuild-20260820.txt`
- `p4b-outbox-schema-quoting-targeted-20260820.txt`
- `p4-d7-background-failure-rebuild-20260820.txt`
- `p4-d7-background-failure-core-targeted-20260820.txt`
- `p4-d7-depth-cache-health-integration-targeted-20260820.txt`
- `p4-d7-background-job-lifecycle-targeted-20260820.txt`

### 2026-08-20 D11-A worktree and verification

The eight D11-A Maven/Java source/test files not yet committed are:

- `peegeeq-test-support/pom.xml` — promotes Logback to the main dependency set and adds the
  JUnit Platform TestKit for executable nested-engine proofs.
- `ExpectedErrorLog`, `ErrorLogExpectation`, `ErrorLogExpectations`, `ErrorLogLedger`, and
  `UnexpectedErrorLogExtension` — the repeatable annotation, immutable contract, runtime
  registration facade, concurrent ledger, and explicit JUnit callback integration.
- `ErrorLogLedgerTest` and `UnexpectedErrorLogExtensionBootstrapTest` — CORE contracts and
  nested EngineTestKit fixtures.

The clean `peegeeq-test-support` reactor-slice rebuild passed. Targeted CORE verification
passed 20/20: `ErrorLogLedgerTest` 12/12 and
`UnexpectedErrorLogExtensionBootstrapTest` 8/8. The bootstrap proves unexpected,
missing-expected, teardown, and after-all ERROR events change JUnit method/container results
to failed; structured exact, prefix, cause-chain, runtime, and parallel expectations pass.
The ERROR records visible in the test log are deliberately emitted inside those nested proof
fixtures and are asserted by the outer green tests. Authoritative logs are:

- `d11a-error-log-contract-rebuild-20260820.txt`
- `d11a-error-log-contract-core-targeted-20260820.txt`

### 2026-08-20 D11-B worktree and verification

D11-B extends the D11 worktree to eleven Maven/Java/resource files. It adds
`UnexpectedErrorLogCaptureCoordinator`, the JUnit service-provider descriptor, and
`UnexpectedErrorLogPackagedIntegrationTest`, and updates the ledger, extension, and ledger
contracts from D11-A.

The packaged extension now acquires one root-store lease per JUnit engine. A synchronized,
reference-counted coordinator shares exactly one root Logback appender across concurrent
engine runs and removes the exact instance when the last engine root closes. The ledger keeps
method scopes separate from class-container lifecycle ownership and uses sorted owner maps for
stable ambiguity diagnostics. Service discovery is packaged but normal Maven profiles do not
enable JUnit extension auto-detection yet; that remains the D11-D gate after migration.

The clean `peegeeq-test-support` reactor-slice rebuild passed and copied the service resource
into the packaged JAR. Targeted CORE verification passed 29/29:
`ErrorLogLedgerTest` 14/14, `UnexpectedErrorLogExtensionBootstrapTest` 8/8, and
`UnexpectedErrorLogPackagedIntegrationTest` 7/7. The packaged contracts prove automatic
discovery, explicit-plus-automatic idempotence, appender removal, parallel class event capture,
method isolation, stable owner/logger/event diagnostics, and compatibility with a test-owned
Logback appender. Authoritative logs are:

- `d11b-packaged-error-log-integration-rebuild-20260820.txt`
- `d11b-packaged-error-log-integration-core-targeted-20260820.txt`

### 2026-08-20 D11-C worktree and verification

D11-C migrated the observed green-path fault-injection ERRORs in `peegeeq-db`,
`peegeeq-outbox`, and `peegeeq-examples` to 30 structured method expectations. Test-authored
ERROR narration was removed; the 20 retained `INTENTIONAL ERROR TEST` narration calls are
all INFO. Fault handlers that already fail their test remain ordinary handlers and were not
converted into allowlists.

The first realistic parallel DB run exposed a cold SLF4J initialization race in the packaged
extension. The coordinator now initializes and caches the Logback context when the global
extension class is loaded, before parallel test classes acquire the capture appender. The same
run also exposed two mechanically misplaced contracts and a timer-test capture-order race;
those contracts were moved to the actual faulting methods, and the timer appender is now
cleared before PostgreSQL is stopped so the first WARN cannot occur outside the observation
window.

Required reactor-slice rebuilds passed. Targeted verification with JUnit extension
auto-detection enabled passed:

- test-support ledger/bootstrap/packaging: 29/29 (14 + 8 + 7);
- parallel DB contract scope: 52/52 (36 + 4 + 9 + 3), plus the timer class alone 5/5;
- outbox schema isolation: 14/14, and the narration-only null-handler class 1/1;
- examples database setup: 8/8;
- CORE async-antipattern narration fixture: 6/6.

The approximately 90-minute release gate was not run. Authoritative final logs include:

- `d11c-module-migration-rebuild-rerun-20260820.txt`
- `d11c-logback-bootstrap-race-rebuild-20260820.txt`
- `d11c-logback-bootstrap-race-targeted-20260820.txt`
- `d11c-db-parallel-contracts-final-20260820.txt`
- `d11c-timer-capture-order-targeted-20260820.txt`
- `d11c-outbox-contract-correction-rebuild-20260820.txt`
- `d11c-outbox-contract-correction-targeted-20260820.txt`
- `d11c-examples-migration-targeted-20260820.txt`
- `d11c-marker-level-rebuild-20260820.txt`
- `d11c-marker-level-targeted-20260820.txt`

### 2026-08-20 D11-D worktree and verification

D11-D enables JUnit extension auto-detection through the root Surefire configuration, so
the default CORE invocation and the integration, performance, smoke, and all-tests profiles
inherit the same ERROR gate. `UnexpectedErrorLogDefaultGateCanaryTest` is the permanent CORE
contract. Before its expectation was added, the real default-profile run reported 1 test,
1 failure, and `BUILD FAILURE` for its unclaimed ERROR. The same test then passed 1/1 after
adding its exact logger/message/no-throwable expectation. The source now contains 41
annotation declarations: 30 migrated application expectations and 11 test-support
infrastructure contracts, including this gate canary.

Enabling the outer Maven gate exposed shared-ledger contamination between the outer run and
the nested EngineTestKit proof runs. The coordinator now keeps one process-wide Logback
appender but gives every JUnit engine run its own ledger. A per-thread stack routes nested
method and lifecycle events to the innermost run; unbound asynchronous events retain the
fail-safe behavior of being delivered to every active candidate run. The nested run closes
without removing the still-active outer Maven appender.

The final clean `peegeeq-test-support` reactor-slice rebuild passed. Targeted verification,
with no explicit auto-detection property on any test command, passed:

- final gate contracts: 30/30 (ledger 14, default canary 1, bootstrap 8, packaged 7);
- DB integration profile: `PgConnectionManagerSchemaIntegrationTest` 9/9;
- performance profile: one targeted matrix-generation method 1/1;
- smoke profile: `MessageTest` 1/1;
- scoped all-tests profile canary: 1/1 (not the 90-minute release gate);
- outbox structured expectations: schema isolation 14/14 and quoting 6/6, with the final
  asynchronous expected-ERROR method rerun 1/1 after the last infrastructure rebuild.

Authoritative logs are:

- `d11d-default-gate-canary-unexpected-fails-20260820.txt`
- `d11d-default-gate-canary-expected-passes-20260820.txt`
- `d11d-final-clean-rebuild-20260820.txt`
- `d11d-final-clean-targeted-20260820.txt`
- `d11d-integration-profile-schema-targeted-20260820.txt`
- `d11d-performance-profile-targeted-20260820.txt`
- `d11d-smoke-profile-targeted-20260820.txt`
- `d11d-all-tests-profile-canary-targeted-20260820.txt`
- `d11d-outbox-structured-expectations-targeted-20260820.txt`
- `d11d-outbox-expected-error-final-20260820.txt`

### 2026-08-20 D10/D12 worktree and verification

The load-dependent symptom was made deterministic with a close-settlement contract. Cleanup
could finish and manager-owned Vert.x could report successful termination, yet a caller that
had composed `closeReactive()` from that same context could lose the public Future's terminal
signal. The regression failed before the fix rather than relying on another long timeout.

`PeeGeeQManager.closeReactive()` now caches one context-free public close Future. It settles
that Future after all managed-component cleanup has completed and before manager-owned Vert.x
termination is initiated. The termination Future is still observed for success or failure.
This preserves failure propagation and repeated-close identity without making callers wait for
a terminal signal scheduled through an executor that has already stopped.

The examples regression subsequently exposed an equivalent repeated-close defect in
`OutboxConsumerGroup`: a second call returned a new succeeded Future while the original close
was still waiting for an in-flight message handler. `OutboxConsumerGroup.close()` now caches
and returns the actual in-progress settlement Future. Its focused regression failed before the
fix and passed afterward.

The D11 gate also correctly rejected two simultaneously active, identical expected-ERROR
signatures in `PeeGeeQManagerCloseReactiveErrorPropagationTest`. The second method now uses a
distinct authentication fault and declares both exact ERROR events from that path; the first
retains its missing-schema fault. Parallel execution was not disabled or serialized.

Required clean reactor-slice rebuilds passed. Targeted final verification passed:

- `PeeGeeQManagerCloseSettlementTest` 2/2;
- `PostgreSQLErrorHandlingTest` 5/5, covering the original D12 class;
- `PeeGeeQManagerCloseLogLevelTest` 4/4;
- the targeted resource-leak method 1/1;
- the outbox repeated-close settlement method 1/1;
- `ConsumerGroupResilienceTest` 3/3;
- parallel `PeeGeeQManagerCloseReactiveErrorPropagationTest` 2/2;
- repository async integrity guards 8/8.

The 60-second budget was not raised. The approximately 90-minute release gate was not run.
Authoritative logs include:

- `d10d12-red-close-settlement-20260820.txt`
- `d10d12-final-clean-rebuild-20260820.txt`
- `d10d12-close-settlement-targeted-20260820.txt`
- `d10d12-native-error-handling-targeted-20260820.txt`
- `d10d12-close-log-level-targeted-20260820.txt`
- `d10d12-resource-leak-method-20260820.txt`
- `outbox-close-settlement-red-targeted-20260820.txt`
- `outbox-close-settlement-green-rebuild-20260820.txt`
- `outbox-close-settlement-green-targeted-20260820.txt`
- `outbox-close-settlement-examples-regression-20260820.txt`
- `d11-parallel-expectation-final-targeted-20260820.txt`
- `close-phases-static-integrity-guards-20260820.txt`

### D12 detail

The budget was raised from 30 s to 60 s on 2026-08-09 after timeouts on 2026-05-23 and
2026-08-09. The in-code comment states plainly: "The slow-close mechanism under load is NOT
diagnosed." It has now failed at 60 s.

Raising the budget is the threshold-masking anti-fix the standards doc bans under
"CRITICAL: 'Strategic Delay' and Threshold-Masking Anti-Fixes". The budget was not raised.
The deterministic settlement proof confirmed this as the same defect as D10: the caller's
terminal signal could be lost when manager-owned Vert.x termination preceded delivery of that
signal on the caller's composition context.

Compounding it: that same teardown was a D4 pass-on-failure site —
`.onFailure(err -> { warn; completeNow(); })`. The 53-site sweep committed in `7ee3148d`
changed this specific handler to fail the test. That closes the error-swallowing instance;
the unsettled-Future behavior is now closed by the D10/D12 settlement fix. The original
`PostgreSQLErrorHandlingTest` passes 5/5 after the clean rebuild.

### D13 detail

Resolved in `20537c83`: native queue start-position handling was implemented, concurrent
handler registration was made atomic, and all six tests were restored as behavioral tests.
`2b35bbdc` added `DisabledTestsGuardTest` and a ratcheted allowlist. The only approved
disabled fixture is the intentional async-antipattern demonstration.

### D1 detail

`SQL_IDENTIFIER` (`^[A-Za-z_][A-Za-z0-9_]*$`) blocks injection but matches every reserved
word — `select`, `order`, `user` all pass. Unquoted, `select.outbox` is a syntax error.
Fixed by adding `quote`/`qualify` to the existing `PostgreSqlIdentifierValidator` rather
than writing a third copy of the quoter.

The prior end-to-end gap is now closed by
`HealthCheckManagerCoreTest#testQueueHealthChecksWithReservedWordSchema`. It provisions and
cleans schema `select` through `PeeGeeQTestSchemaInitializer`, starts real queue health
checks, and asserts outbox, native, and DLQ status are healthy. This also exposed and fixed
two unquoted schema references in the shared test initializer.

### D2 detail

```
NullPointerException: ... "context" is null
  at io.vertx.sqlclient.Pool.withTransaction(Pool.java:161)
  at OutboxProducer.lambda$sendInOwnTransactionInternal$0(OutboxProducer.java:330)
```

Three tests reported green for five months while failing on every run. P2 established the
context contract: the propagation path is covered from a Vert.x context, while an
off-context call now returns a failed Future naming the context requirement. The updated
`OutboxProducerTransactionTest` reports 20/20 green.

### D3 detail

Error code is `42703` (undefined_column), not `42P01` (undefined_table) — the tables exist
and the columns are missing.

| | Template `08c-consumer-table-groups.sql` | Migration `V001__Create_Base_Tables.sql` |
|---|---|---|
| message ref | `message_id` | `outbox_message_id` |
| group | `group_name` | `consumer_group_name` |
| timing | `claimed_at`, `completed_at` | `processing_started_at`, `processed_at` |
| locking | `lock_id`, `lock_until` | absent |

`V010` reconciles them with conditional `RENAME`/`ADD`/`DROP`; `V015` adds the flapping
columns used by dead-consumer detection. The 2026-08-20 diagnosis applied the real scripts
incrementally and proved both boundaries:

- after V001, the current retry query fails with SQLSTATE `42703` on `message_id`;
- after V010, that retry query plans successfully;
- after V010 but before V015, the current detector query fails with SQLSTATE `42703` on
  `consecutive_misses`;
- after V015, the detector query plans successfully.

The green outbox run that originally exposed D3 had bypassed that chain with hand-written
DDL mirroring only the V001 shape. `OutboxSchemaQuotingTest` now uses
`PeeGeeQTestSchemaInitializer` and therefore runs the supported migration chain to completion.
Its new direct service contract passed 1/1 against reserved-word schema `table`; the existing
retry-service and dead-detector integration contracts also passed 1/1 each against current
schema. The remediation does not claim an intermediate V001-only database supports current
runtime code; it removes the unsupported, drift-prone test provisioning path.

### D7 detail

`BackgroundTaskFailureTracker` is the single failure policy for the depth-cache timer,
`ConsumerGroupRetryJob`, and `DeadConsumerDetectionJob`:

- the first consecutive failure emits WARN with the full Throwable;
- persistent failures emit stack-free ERROR summaries at counts 3, 6, 9, and so on;
- health transitions from HEALTHY to DEGRADED on the first failure and to UNHEALTHY at the
  persistent threshold;
- the next success resets the consecutive count and restores HEALTHY.

Each task registers a named health check, so failure is visible without scraping logs. The
tests prove the policy state machine, job wiring, recovery, shutdown behavior, and that timer
callbacks stop after manager close. They do not claim behavior outside the exercised paths.

### D4-A — the 4 contract rewrites (resolved)

These are the CRITICAL placeholder-test class, not the teardown class. Converting the
handler is not enough; each asserts nothing meaningful today.

| Site | Why it always passes |
|---|---|
| `RealTimeStreamingIntegrationTest:210` (rest) | Three independent pass paths: connection failure swallowed, a 5 s `setTimer → completeNow` that fires *because* no message arrived, and an assertion guarded by `if ("welcome".equals(type))` so any other frame asserts nothing |
| `WebSocketHandlerTest:395` (rest) | `assertTrue(status >= 200 \|\| status >= 400)` — every status ≥ 200 satisfies the first clause; second is unreachable. Plus the failure swallow |
| `CrossLayerPropagationIntegrationTest:383` (rest) | DB verification declared optional by comment: "might fail if table structure is different — that's OK" |
| `HealthCheckManagerTest:468` (db) | "expected after pool close" — if genuinely expected it must be asserted as expected, not swallowed |

The three REST tests now assert concrete WebSocket/HTTP/database contracts and fail on
connection, timeout, or query failure (`bbfa370e`). The DB test now propagates health-manager
shutdown failure after the deliberately closed pool (`bdd72071`).

### D4-B — setup precondition (done)

Three `@BeforeEach` pre-cleanups swallowed their failure, so a class documented as needing
"a clean slate" would run against another class's leftovers — the same mechanism as the
cross-test pollution recorded at lines 847-922. Converted to `failNow`; 45 tests green.

### D4 progress by module

Committed in `7ee3148d`:

| Module | Occurrences |
|---|---|
| peegeeq-native | 18 |
| peegeeq-outbox | 13 |
| peegeeq-examples | 11 |
| peegeeq-bitemporal | 5 |
| peegeeq-db | 5 |
| peegeeq-service-manager | 1 |
| **Total** | **53 across 6 modules** |

Closed since the original sweep:

| Kind | Original count | Resolution |
|---|---:|---|
| Setup / teardown expression lambdas | 10 | remediated across lifecycle-hardening commits |
| Test-body expression lambdas | 10 | remediated; tracked-source expression scan is zero |
| D4-A contract rewrites | 4 | `bbfa370e` and `bdd72071` |
| **Total** | **24** | **closed** |

Ruling (2026-08-11): `failNow` everywhere, including `@AfterEach`. A failed close leaks
pool connections; the doc's own evidence is 232 silent teardown skips that cascaded into
710 "too many clients" errors.

Detection requires a body-scoped scan that also recognizes expression lambdas. A flat
regex runs past the handler's closing brace and reports the next `completeNow()` in an
`else` branch — that error produced an initial overcount of 75/52 before correction to
66/47. Later scans found the 20 expression-lambda sites listed above; the current detector
has not been hand-validated.

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

### D6 detail — resolved

`awaitCompletion` returns true for a context completed by `failNow`, so a failed send left
`sendCompleteTimeRef` null and the test died in `Duration.between` with
`NullPointerException: temporal`. A new `failIfContextFailed` helper, applied at all three
`awaitCompletion` sites, now rethrows `causeOfFailure()`.

The cause it revealed:

```
ConnectionPoolTooBusyException: Connection pool reached max wait queue size of 128
```

`testThroughputPerformance` formerly fired 1000 sends concurrently at a pool that could not
queue them. `2b35bbdc` made the measurement decision explicit: it pins the database pool at
20 and distributes the workload across 20 sequential send lanes. The test now measures
sustained bounded throughput rather than rejection under an unbounded client burst.

---

## Phases

One phase at a time. Each has an entry condition, an exit condition, and a scoped
verification command. The agent runs the scoped verification and reports per-class
`Tests run:` lines; `-Pall-tests` stays with the user.

### P0 — Investigation and guard correction — COMPLETE

Fixed D8 and D9. Without these, any sweep would have been reintroducing the pattern from
the standards doc's own "Correct Pattern" examples.

### P1 — Pass-on-failure sweep (D4) — COMPLETE

Module by module, not one sweep. Each module's conversions are verified before the next.

- **P1a peegeeq-outbox — COMPLETE.** 13 sites converted. After D2 was fixed, the changed
  outbox classes reported 66 tests green; `OutboxQueueUnitTest` reported 26/26.
- **P1b peegeeq-native — COMPLETE.** 18 sites converted; full module integration reported
  189/189 green.
- **P1c — COMPLETE for the enumerated sites.** peegeeq-db 5, peegeeq-examples 11,
  peegeeq-bitemporal 5, peegeeq-service-manager 1. The changed db group reported 45/45
  and bitemporal integration reported 352/352. Examples and service-manager were not
  re-run during the recorded session.
- **P1d — COMPLETE.** The tracked-source expression scan returns zero. Lifecycle-hardening
  commits also replaced broader discarded/weakly observed async cleanup patterns.
- **P1e / D4-A — COMPLETE.** The four tests now assert concrete success or expected-failure
  contracts (`bbfa370e`, `bdd72071`).

Exit: body-scoped scan returns zero for git-tracked test sources; each module's scoped run
reported with per-class counts.

### P2 — `TransactionPropagation` contract (D2) — COMPLETE

Three propagation tests now execute from a Vert.x context and assert success. A separate
test pins the off-context contract, and `OutboxProducer` returns a failed Future naming
the context requirement instead of surfacing a Vert.x-internal NPE.

Exit evidence: `OutboxProducerTransactionTest` 20/20 green.

### P3 — Schema identifier quoting (D1) — COMPLETE

The reserved-word integration test is green 1/1 on 2026-08-19. The shared schema initializer
now quotes its validated identifier in both compatibility SQL statements.

### P4 — Background jobs (D3, D7) — COMPLETE

1. **D3 COMPLETE.** The `42703` was reproduced at the exact V001/V010 and V010/V015
   boundaries. The failing outbox fixture was a hand-written intermediate schema, not the
   supported final migration state. It now provisions through the shared Flyway initializer,
   and both services execute successfully against that schema.
2. **D7 COMPLETE.** One shared tracker provides first-failure stack logging, bounded persistent
   summaries, health degradation, unhealthy escalation, and recovery for
   `ConsumerGroupRetryJob`, `DeadConsumerDetectionJob`, and the depth-cache timer.

Exit evidence: fixture contract 6/6, D7 core contracts 8/8, depth-cache integration 5/5,
background-job lifecycle integration 6/6, with clean reactor-slice rebuilds before testing.

### P5 — Performance profile and exposed throughput question (D5, D6) — COMPLETE

1. **D5 COMPLETE.** Removed the redundant `@EnabledIfSystemProperty` gates. The existing
   performance tag is now the single selection mechanism, and all three classes execute
   under `-Pperformance-tests` (8 tests total).
2. **D6 COMPLETE.** `2b35bbdc` chose bounded in-flight concurrency: 20 sequential lanes
   aligned with an explicitly asserted 20-connection pool.

Profile activation and the measurement decision are both satisfied.

### P6 — Make unasserted ERROR logs fail the build (D11) — COMPLETE

The systemic fix, and the only item that prevents the next round of this.

The original “Surefire listener” wording describes the outcome, not a proven integration
mechanism. Surefire's reporting listener observes completed results and does not itself prove
that it can change a successful JUnit 5 result. D11 therefore starts with an executable
bootstrap proof before repository-wide wiring. The gate must fail a test container or the
fork for every unclaimed ERROR; merely printing a report is not acceptance.

#### D11 expectation data model — DECIDED 2026-08-20

An expectation is owned by exactly one test method and has these required fields:

| Field | Rule |
|---|---|
| logger | exact logger name; wildcards and root-logger expectations are forbidden |
| message | exact text or stable prefix; unrestricted substring and unanchored regex matches are forbidden |
| throwable | explicit `NONE` or `CAUSE_CHAIN_CONTAINS(type)`; omission is forbidden |
| occurrences | exact count by default; an explicit finite minimum and maximum may be used when concurrency makes an exact count invalid |
| owner | JUnit unique test ID plus display name, recorded by the framework rather than supplied by the test |

An event at ERROR is consumed only when it matches exactly one active expectation. Zero
matches is unexpected. More than one match is ambiguous and fails every owning scope; the
framework must never choose by registration order. An expectation that consumes fewer than
its minimum or more than its maximum also fails. Thus an expectation cannot make a test pass
when the intended fault did not occur, and it cannot hide extra repetitions.

Static expectations are declared with a repeatable method annotation. Values known only at
runtime use a programmatic registration API called before the fault is triggered. Both forms
compile to the same immutable expectation record and remain active through user teardown;
plain log markers such as `INTENTIONAL ERROR TEST` have no semantic effect and must be removed
or retained only at INFO. There is no file-wide, classpath-wide, logger-wide, or message-only
allowlist.

The first gate covers Logback events whose level is exactly ERROR. WARN escalation is outside
D11. Events emitted during setup, teardown, or after a test scope has closed are not silently
discarded: they are unexpected lifecycle/unowned events and must fail the class container or
fork. Parallel execution remains enabled. If an unexpected event cannot be attributed because
multiple classes are active, all candidate classes may be reported, but the build must fail
and the diagnostic must state that attribution was ambiguous.

#### D11 implementation phases

1. **D11-A — model and bootstrap proof — COMPLETE.** Added the immutable matcher/ledger in
   `peegeeq-test-support` with CORE tests for exact/prefix matching, throwable cause-chain
   matching, cardinality, overlap, parallel scopes, lifecycle events, and reset. Nested
   JUnit-platform fixtures prove an unexpected ERROR changes the execution summary to failed,
   an expected ERROR passes, a missing expected ERROR fails, and teardown/late ERRORs cannot
   escape. Select JUnit extension, platform listener, or Surefire integration only from this
   proof; reporting-only hooks are rejected.
2. **D11-B — packaged test integration — COMPLETE.** Installed one process-wide Logback
   appender and the proven JUnit 5 integration from `peegeeq-test-support`. CORE fixtures prove
   idempotent installation and removal, no event loss under parallel classes, no leakage
   between methods, deterministic diagnostics, and compatibility with tests that attach their
   own appenders. The service descriptor is packaged; profile activation remains D11-D.
3. **D11-C — module migration — COMPLETE.** Inventoried green-run ERRORs, then migrated intentional
   fault-injection tests module by module to structured expectations. Application modules have
   30 structured method expectations. The remaining narration calls are INFO-only; source
   marker and ERROR-call counts remain triage inputs, not expectation counts. A production/test
   error handler that already fails its test needs no expectation unless ERROR is actually
   emitted on the green path.
4. **D11-D — default gate — COMPLETE.** Root Surefire configuration enables the integration
   in the normal CORE, integration, performance, smoke, and all-tests profiles. The real
   unclaimed canary failed the Maven build 1/1; the same canary passed 1/1 with its structured
   expectation. Engine-run isolation keeps nested proof runs independent while sharing one
   process appender. Targeted profile/module scopes are green; the owner retains the
   approximately 90-minute all-tests release gate.

Each phase was rebuilt and verified before the next. D11 is fixed.

### P7 — Close settlement (D10, D12) — COMPLETE

1. Added a deterministic manager settlement contract that failed before the production fix
   and passed afterward; no timeout threshold was increased.
2. Made the public manager close Future context-free, cached, and settled after managed cleanup
   but before manager-owned Vert.x termination starts. Vert.x termination remains observed.
3. Applied the same repeated-close settlement contract to `OutboxConsumerGroup` after the
   downstream examples scope exposed an immediate-success second close.
4. Removed overlapping active D11 expectations from the parallel manager failure class by
   using two distinct real startup faults with exact structured ERROR contracts.

Exit evidence: manager settlement 2/2, original native D12 class 5/5, close log levels 4/4,
resource-leak method 1/1, outbox settlement 1/1, examples resilience 3/3, parallel manager
failure propagation 2/2, and static async guards 8/8. Required clean reactor-slice rebuilds
passed before downstream testing.

All registered defects D1-D13 are fixed.

---

## Out of scope

- **Converging duplicate quoters** in `OutboxFactory` and `OutboxConsumer` onto
  `PostgreSqlIdentifierValidator`. Correct to do, but a pure refactor of working code; P3 no
  longer blocks it.
- **`peegeeq-utilities-ui` T.6** — an optional per-run telemetry precision/scoping
  enhancement. Utilities UI Phase G.1b is complete and T.6 is not a defect in this register.
- Anything requiring `-Pall-tests`. That is the release gate, not a step in this work.

---

## Status discipline

This file is the register. Chat is not. Any status here must be traceable to a command
output, and anything diagnosed from artifacts alone is marked `NOT REPRODUCED` rather than
described as understood.
