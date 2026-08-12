# Session Handover — 2026-08-12

Companion to `TEST-INTEGRITY-DEFECT-REMEDIATION-PLAN.md`, which is the defect register.
This document covers what happened in this session, what state the working tree is in,
and what the next person needs to know before touching it.

### Repository snapshot

Verified after Phase 3 remediation on 2026-08-12:

* Branch `master` is at commit `7ee3148dc26cff25a58d33c222163352460ad7a2`
  (`fix(tests,db,outbox): make failing tests fail — 53 swallowed failures, 8 skipped
  tests, 2 production defects`). The implementation and supporting documentation
  described below are committed there; the commit changes 82 files.
* The working tree contains four modified tracked files — `AGENTS.md`, `CLAUDE.md`,
  `TEST-INTEGRITY-DEFECT-REMEDIATION-PLAN.md`, and `PEEGEEQ-TEST-COMMANDS.md` — plus
  one untracked file: this handover document. These are the Phase 1–3 documentation
  remediations and are not committed.
* Commit `7ee3148d` also contains 20 modified and 4 added PNGs under
  `docs-design/peegeeq-management-ui/screenshots/`. The session attributed these files
  to a `mvn clean install` rebuilding the UI modules; that attribution has not been
  independently reproduced.

**Owner decision required for the 24 PNGs:** either retain them and record their inclusion
as intentional, or restore the 20 modified files and remove the 4 added files in a new,
path-scoped corrective commit. Do not rewrite the existing commit history. Until that
decision is made, do not alter the screenshots as part of the defect remediation work.

---

## 1. What started this

Reading `logs/peegeeq-outbox-integration-20260811.txt`, which reported:

```
Tests run: 537, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

while logging **46 ERROR-level exceptions**.

Everything in this session came from pulling that thread. None of it is new breakage —
the swallowing handlers date from 2026-03-20, the schema-quoting gap and the divergent
DDL are older. What changed is that we read logs instead of exit codes.

---

## 2. The single root cause

Three forms of one defect: something fails, and nothing that reports test results ever
learns about it.

| Form | Effect |
|---|---|
| `.onFailure(err -> { log; completeNow(); })` | Test passes whether the operation worked or not |
| Gated test that never activates | `Tests run: N, Skipped: N` printed as `BUILD SUCCESS` |
| Production failure handled by `logger.error` only | No test, health signal or caller sees it |

**D11 is the systemic fix** — make unasserted ERROR logs fail the build. Everything else
in the register is remediation of instances.

---

## 3. What was fixed and verified

### Production code (4 files)

**`PostgreSqlIdentifierValidator`** — added `quote(String)` and `qualify(String,String)`.
The class already owned identifier validation and had no quoting operation; the quoter
existed twice in `peegeeq-outbox` (`OutboxFactory`, `OutboxConsumer`), in a module
`peegeeq-db` cannot depend on. Adding a third copy would have violated "never keep both".

**`HealthCheckManager`** — `qualifiedTable()` now quotes the schema. Its `SQL_IDENTIFIER`
guard (`^[A-Za-z_][A-Za-z0-9_]*$`) blocks injection but matches every reserved word, so a
schema named `select` passed validation and produced `FROM select.outbox` — error 42601,
and every queue health check reported a permanently failing queue. Observed live in
`OutboxSchemaQuotingTest`, which exists to fix this exact defect (it calls it M1) but
asserts only on three outbox query methods.

**`PeeGeeQDatabaseSetupService`** — two `DROP TABLE IF EXISTS` statements built the same
way. **No test covers this change.**

**`OutboxProducer`** — guard added: `TransactionPropagation` off a Vert.x context now
returns a failed Future naming the requirement, instead of letting `Pool.withTransaction`
dereference the null context and surface a Vert.x-internal NPE.

### Test code

- **53 swallowed-failure handlers** converted to `failNow`, across 6 modules
  (native 18, outbox 13, examples 11, bitemporal 5, db 5, service-manager 1).
- **3 propagation tests** rewritten to run inside `vertx.getOrCreateContext().runOnContext`
  and assert success, plus a new test pinning the off-context contract.
- **2 tests** that accepted any failure now assert the contract read from source:
  `IllegalArgumentException("Message payload cannot be null")` and
  `IllegalStateException("Producer is closed")`.
- **3 performance classes** — removed a dead `@EnabledIfSystemProperty` gate.
- **`OutboxPerformanceTest`** — `failIfContextFailed` helper at three `awaitCompletion`
  sites, so a failed send surfaces its cause instead of an NPE on derived state.

### Verification runs (this session)

| Suite | Result |
|---|---|
| peegeeq-native integration | 189/189 green |
| peegeeq-bitemporal integration | 352/352 green |
| peegeeq-db (3 changed classes) | 45/45 green |
| `PostgreSqlIdentifierValidatorTest` | 20/20 green |
| `OutboxProducerTransactionTest` | 20/20 green |
| peegeeq-outbox (changed classes) | 66 tests green |
| performance, all 3 classes | 8 tests now execute (were 0) |

---

## 4. What is NOT verified

* **`PeeGeeQDatabaseSetupService`** — production change with no test behind it.
* **peegeeq-examples** (11 sites changed) — not re-run.
* **peegeeq-service-manager** (1 site changed) — not re-run.
* **peegeeq-db and peegeeq-outbox full integration** — not run since the production
  changes to `HealthCheckManager`, `PeeGeeQDatabaseSetupService` and `OutboxProducer`.
* **`-Pall-tests`** — not run.

---

## 5. Open work, ranked

### 5.1 — 24 remaining swallow sites

Found late in the session in a lambda form never scanned for:

```java
.onFailure(err -> testContext.completeNow())     // expression lambda, no braces
```

| Kind | Count | Notes |
|---|---|---|
| Teardown / setup | 10 | Same mechanical edit as the 53 |
| Test body | 10 | 8 of them in `PgBiTemporalEventStoreComplexTest` |
| Needs rewrite (D4-A) | 4 | 3 in peegeeq-rest, 1 in peegeeq-db |

The 10 test-body sites need reading, not converting. Two carry comments admitting what
they do: `// Either null or exception acceptable` and `// Expected in some implementations`.
A test where either outcome is acceptable asserts nothing about the event store.

**D4-A, the four that need rewriting:**

| Site | Why it always passes |
|---|---|
| `RealTimeStreamingIntegrationTest:210` | Three pass paths: swallowed connection failure; a 5 s `setTimer → completeNow` that fires *because* no message arrived; an assertion guarded by `if ("welcome".equals(type))` |
| `WebSocketHandlerTest:395` | `assertTrue(status >= 200 \|\| status >= 400)` — every status ≥ 200 satisfies clause one; clause two is unreachable |
| `CrossLayerPropagationIntegrationTest:383` | DB verification declared optional by comment |
| `HealthCheckManagerTest:468` | Swallows a close it calls "expected" without asserting it |

Expect red once honest. If the WebSocket layer is incomplete, that is what the tests will
say — which is the point, but it needs its own budget.

### 5.2 — `closeReactive()` returns a Future that never settles (D10/D12)

**The most serious open item. Production code.**

Third observation. `PostgreSQLErrorHandlingTest` teardown exceeded a **60 second** budget
while the log showed the close completing in 36 ms:

```
PeeGeeQManager.closeReactive() cleanup completed
Closing Vert.x instance (manager-owned)
Vert.x instance closed successfully
```

Neither `onSuccess` nor `onFailure` ran. This is not a swallowed failure — it is silence.

The budget had already been raised 30 s → 60 s on 2026-08-09, with the in-code comment
admitting "the slow-close mechanism under load is NOT diagnosed". It then failed at 60 s.
Raising it again is the threshold-masking anti-fix the standards doc bans by name.

**Untested hypothesis:** `closeReactive()` closes the manager-owned Vert.x as its final
step. If the continuation that completes the caller's Future is scheduled on that same
event loop, closing the loop first would drop the callback permanently. This fits every
observation — clean logs, passes in isolation, fails under load, elapsed time exactly the
budget. It also matches commit `e79030da`'s own note that "any caller chaining on
closeReactive() can wait forever."

It did **not** reproduce in two subsequent full-native runs, so it is load-dependent.

### 5.3 — `outbox_consumer_groups` has two incompatible definitions (D3)

Error code is `42703` (undefined_column), not `42P01` — the tables exist, the columns
differ.

| | Template `08c-consumer-table-groups.sql` | Migration `V001__Create_Base_Tables.sql` |
|---|---|---|
| message ref | `message_id` | `outbox_message_id` |
| group | `group_name` | `consumer_group_name` |
| timing | `claimed_at`, `completed_at` | `processing_started_at`, `processed_at` |
| locking | `lock_id`, `lock_until` | absent |

`V010` reconciles them with conditional `RENAME`/`ADD`/`DROP`. `ConsumerGroupRetryService`
queries the template shape, so any database between V001 and V010 fails every job tick.

**Status: NOT REPRODUCED.** Step one is reproduction — capture which relation the query
resolved to and what its columns are — not a fix.

### 5.4 — Remaining

* **D7** — background timer failures log unbounded ERROR with no escalation or health
  signal. One shared mechanism for `ConsumerGroupRetryJob`, `DeadConsumerDetectionJob`
  and the depth-cache timer.
* **D13** — 6 `@Disabled` tests in `ConsumerGroupSubscriptionTest`, one parked on
  "Native queue `setMessageHandler()` has a race condition - thread safety needs
  implementation fix". A known production defect behind a disabled test.
* **P3 remainder** — one test: `HealthCheckManager` against a schema named `select`.
  D1's fix is proven at contract level, not end to end.
* **D11** — the systemic fix. Needs its own data-model pass first: what counts as an
  expectation, where it is declared, how a fault-injection test registers one.

---

## 6. Awaiting the owner's decision

**D6 — `OutboxPerformanceTest.testThroughputPerformance`.** It fires 1000 concurrent sends
at a pool with a 128 wait queue and now fails with `ConnectionPoolTooBusyException`. Size
the pool, or bound in-flight sends? Those measure different things. **Not a production
defect** — the pool rejected work it was configured to reject.

---

## 7. Traps found the hard way

**Stale `.m2` after changing an upstream module.** A `-pl` scoped run resolves dependencies
from the local repository, not the reactor. Changing `peegeeq-api` or `peegeeq-db` and then
running a downstream module's tests produces `NoSuchMethodError` at runtime. Fix:

```powershell
mvn clean install "-DskipTests" "-pl" ":<changed-module>" "-am" 2>&1 |
    Tee-Object -FilePath logs\rebuild-<module>-<date>.txt
```

This rebuilds and installs the changed module plus its upstream reactor dependencies.
Use `-DskipTests` for this prerequisite only (it compiles tests but skips execution), then
run the targeted tests. Never use `-Dmaven.test.skip=true`.

**`-Dtest=` with the wrong profile reports `Tests run: 0` and `BUILD SUCCESS`.** Match the
profile to the class's tag. `PostgreSqlIdentifierValidatorTest` is CORE — adding
`-Pintegration-tests` runs nothing.

**Maven properties are not system properties.** `-Pperformance-tests` sets
`peegeeq.performance.tests` as a Maven property, but surefire is declared version-only in
`<pluginManagement>` with no `<systemPropertyVariables>`, so it never reaches the test JVM.
Any `@EnabledIfSystemProperty` gate on it can never be satisfied. Three classes had never
executed. Fixed by deleting the redundant gate — the `@Tag` already selects them.

**The standards doc contradicted itself.** Two passages required
`testContext.failNow(err); // do NOT swallow to completeNow()`; three blocks headed
"Correct Pattern" showed the opposite. Corrected, but check for the same pattern elsewhere
before trusting a doc example.

---

## 8. Reliability warning on the counts

**Treat every site count in this session as an estimate.**

The detector was wrong four times, and each correction came from stumbling into a new form
while editing rather than from testing the detector:

1. **75/52** — flat regex, 300-char window ran past the handler's closing brace and caught
   `completeNow()` in the following `else`.
2. **66/47** — body-scoped, but counted asserted negative tests as violations.
3. **53/38** — excluded handlers containing `assert`, but missed the capture idiom where
   the check lives outside the handler.
4. **35 → 53 fixed, 24 open** — blind to the single-line block form, then to the
   expression lambda form with no braces at all.

**Before quoting another count:** open one representative file, enumerate every
`.onFailure(` by hand, confirm the scanner's output matches, and state which file was used
to validate. `PgBiTemporalEventStoreComplexTest` is the right file — it has eight sites in
the form that was missed longest.

The current scanner is at `/tmp/scan5.awk` (not persisted). It covers three forms:
expression lambda, single-line block, multi-line block; and excludes handlers containing
`assert`, `.set(` (throwable capture) or `failNow`. It has **not** been hand-validated.

---

## 9. Resuming

```powershell
# 1. Rebuild and install the changed reactor slices before downstream tests
mvn clean install "-DskipTests" "-pl" ":peegeeq-db,:peegeeq-outbox" "-am" 2>&1 |
    Tee-Object -FilePath logs\rebuild-db-outbox-20260812.txt

# 2. Close the unverified gap
mvn test "-Pintegration-tests" "-pl" ":peegeeq-examples" 2>&1 | Tee-Object -FilePath logs\examples-20260812.txt
mvn test "-Pintegration-tests" "-pl" ":peegeeq-service-manager" 2>&1 | Tee-Object -FilePath logs\svcmgr-20260812.txt

# 3. Full regression on the modules with production changes
mvn test "-Pintegration-tests" "-pl" ":peegeeq-db" 2>&1 | Tee-Object -FilePath logs\db-20260812.txt
mvn test "-Pintegration-tests" "-pl" ":peegeeq-outbox" 2>&1 | Tee-Object -FilePath logs\outbox-20260812.txt
```

Read the per-class `Tests run:` lines in each log. `BUILD SUCCESS` alone means nothing —
that is what started this session.

---

## 10. Working agreement notes

* After every Java or Maven implementation change, the agent rebuilds and installs the
  affected reactor slice with `clean install -DskipTests -pl :<changed-module> -am`, then
  runs the smallest relevant test scope (`-Dtest=` method/class or a single module) with
  the correct profile. Both commands are piped through `Tee-Object`; the agent reads the
  saved logs and reports the exact scope and per-class counts. The approximately 90-minute
  `-Pall-tests` run is owner-run or executed only when explicitly requested as a release
  gate. `AGENTS.md`, `CLAUDE.md`, and `PEEGEEQ-TEST-COMMANDS.md` now state the same rule.
* `CLAUDE.md` Step 5 gained a pass-on-failure entry. Its previous closest rule required a
  Future be "observed via `.onFailure(...)`", which a swallowing handler satisfies — the
  list checked that a handler exists, never what it does.
* Status lives in the register, not in chat. Anything diagnosed from artifacts alone is
  marked `NOT REPRODUCED` rather than described as understood.
