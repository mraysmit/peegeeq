# PeeGeeQ Test Guards

**Status:** CURRENT
**Last reconciled:** 2026-10-06 against commit f1c5d25d

PeeGeeQ enforces its async-test and test-hygiene rules with five static-analysis guard
tests in `peegeeq-test-support/src/test/java/dev/mars/peegeeq/test/quality/`. Each guard
is `@Tag(TestCategories.CORE)`, uses no database and no Testcontainers, and runs under
plain `mvn test`. A violation fails the build with a `file:line  snippet` report.

| Guard class | What it enforces |
|---|---|
| `OnSuccessExceptionSwallowingGuardTest` | The nine async-test tiers described in this document, scanning every `peegeeq-*/src/test/java/**/*.java` file. |
| `VertxAsyncForbiddenPatternsGuardTest` | Ratchets `new VertxTestContext(`, `.toCompletionStage(`, `.toCompletableFuture(`, `CompletableFuture`, `.recover(`, `.otherwise(`, and `Handler<AsyncResult` in production and test sources against `quality/vertx-async-forbidden-baseline.csv`. |
| `DisabledTestsGuardTest` | Rejects any `@Disabled` annotation not recorded with a rationale in `quality/disabled-tests-allowlist.csv`, and rejects stale allowlist rows. |
| `InvalidDurationLiteralGuardTest` | Rejects ISO-8601 duration literals written with a millisecond suffix (`PT`, digits, `MS`) in Java sources, configuration resources, `pom.xml`, and every Markdown file under `docs` and `docs-design`, because `Duration.parse` rejects them and `PeeGeeQConfiguration` would fall back to its default. The literal must not appear in documentation examples. |
| `SchemaInitializerTestInfrastructureGuardTest` | Rejects hand-built `new PostgreSQLContainer(` and raw `java.sql` imports in schema-initializer tests under `peegeeq-test-support/src/test/java/dev/mars/peegeeq/test/schema`. |

The rest of this document covers `OnSuccessExceptionSwallowingGuardTest`.

Source: [peegeeq-test-support/src/test/java/dev/mars/peegeeq/test/quality/OnSuccessExceptionSwallowingGuardTest.java](../../peegeeq-test-support/src/test/java/dev/mars/peegeeq/test/quality/OnSuccessExceptionSwallowingGuardTest.java)

Design record: [docs-design/tasks/archive/PEEGEEQ_ONSUCCESS_AUDIT_DEFINITIVE_2026_05_14.md](../tasks/archive/PEEGEEQ_ONSUCCESS_AUDIT_DEFINITIVE_2026_05_14.md)

## What it checks

The guard runs eight independent `@Test` methods. Each method walks the workspace, masks
comments and string literals, then applies tier-specific regex checks. The exemption check
scans raw source so an annotation cannot be hidden by string-literal masking. The walk
covers `peegeeq-*/src/test/java` directories only, so `.history/` and `target/` are never
visited.

| Tier | Test method | Pattern flagged |
|------|-------------|-----------------|
| 2/3  | `noTier3OnSuccessExceptionSwallowingInTestSources` | Bare `assertX(...)` / `fail(...)` (Tier 3) or top-level `.close(...)` (Tier 2) inside `.onSuccess(v -> { ... })` outside `testContext.verify(...)`. |
| 4    | `noFutureAwaitInTestSources` | `.await(...)` in test code. A zero-argument `.await()` is always flagged. An `.await(...)` with arguments is allowed only when the argument list contains a `TimeUnit.` reference, which is the bounded `CountDownLatch.await(timeout, TimeUnit.X)` form. `testContext.awaitCompletion(...)` is a different method name and never matches. Ratcheted (see below). |
| 5    | `noBlockingThreadDelaysInTestSources` | `Thread.sleep(...)` or `LockSupport.parkNanos(...)`. No baseline. |
| Policy | `noBlockingExemptionsInTestSources` | Any source-level `@Tag("blocking-exempt")` annotation. No baseline. |
| 6    | `noOnCompleteSwallowingInTestSources` | `.onComplete(ar -> singleCountdown())` lambdas containing only a countdown/signal op (`countDown`, `getAndIncrement`, `getAndAdd`, `release`, `tryComplete`) with no failure-awareness token (`failed`, `cause`, `failNow`, `onFailure`, `succeeded`, `if (ar`). |
| 7    | `noDiscardedFuturesFromStopOrCloseInTestSources` | `<receiver>.stop();` / `<receiver>.close();` where the receiver name contains `job`, `manager`, or `group` (case-insensitive), the call is followed immediately by `;`, and no `.compose`/`.onSuccess`/`.onFailure`/`.onComplete`/`.eventually`/`.map`/`.transform`/`.andThen` chain consumes the result. Ratcheted (see below). |
| 8    | `noAsyncOperationsAssertedOnlyAsNonNullInTestSources` | `assertNotNull(...)` around `send`, `subscribe`, or `close`, which proves only that a Future object was allocated. |
| 9    | `noDiscardedFuturesFromSubscribeInTestSources` | A bare `consumer.subscribe(handler);` whose returned Future is not observed or composed. Ratcheted (see below). |

Failures emit a precise report:

```
Found 1 .onSuccess(...) block(s) with exception-swallowing risk (Tier 3 = 1, Tier 2 = 0).
  [Tier 3] .../MultiConfigurationIntegrationTest.java:215 — .onSuccess(v -> { assertEquals(...)...
```

## Ratchet baselines (Tiers 4, 7, 9)

Tiers 4, 7 and 9 compare the per-file violation count against a checked-in CSV under
`peegeeq-test-support/src/test/resources/quality/`:

| Tier | Baseline resource | Data rows (2026-10-06) |
|---|---|---|
| 4 | `future-await-baseline.csv` | 0 |
| 7 | `discarded-future-baseline.csv` | 0 (header records "All violations remediated 2026-05-27") |
| 9 | `discarded-subscribe-future-baseline.csv` | 0 |

Each row is `path,count`. The guard fails on any mismatch in either direction:

- **REGRESSION**: a file has more violations than its baselined count, or a file with
  violations is absent from the baseline. Not permitted.
- **STALE**: a baselined file now has fewer violations than recorded. The file was fixed
  but the CSV row was not updated in the same commit.

A remediator who fixes a file reduces or deletes its row in the same commit. Nobody adds a
row. With every baseline at zero rows, the three ratcheted tiers behave as zero-tolerance
checks. The `future-await-baseline.csv` header still states "Total allowed violations:
468" from its 2026-05-18 capture; the data rows, not the header, are what the guard reads.

Tiers 2/3, 5, 6, 8 and the exemption policy have no baseline and no opt-out.

## Run commands

Follow [PEEGEEQ-TEST-COMMANDS.md](PEEGEEQ-TEST-COMMANDS.md): use the `:module` selector,
pipe through `Tee-Object`, run from the workspace root. That document also defines when
`clean` is used (the `mvn clean install -DskipTests -pl :<module> -am` rebuild step and
regression-safety runs); a scoped guard run does not need it.

### Run all eight checks (whole codebase)

```powershell
mvn test -pl :peegeeq-test-support -Dtest=OnSuccessExceptionSwallowingGuardTest 2>&1 | Tee-Object -FilePath logs\guard-tests-20261006.log
```

### Run a single tier

```powershell
# Tier 2/3
mvn test -pl :peegeeq-test-support -Dtest=OnSuccessExceptionSwallowingGuardTest#noTier3OnSuccessExceptionSwallowingInTestSources 2>&1 | Tee-Object -FilePath logs\guard-tier3-20261006.log

# Tier 4 - Future.await()
mvn test -pl :peegeeq-test-support -Dtest=OnSuccessExceptionSwallowingGuardTest#noFutureAwaitInTestSources 2>&1 | Tee-Object -FilePath logs\guard-tier4-20261006.log

# Tier 5 - Thread.sleep / parkNanos
mvn test -pl :peegeeq-test-support -Dtest=OnSuccessExceptionSwallowingGuardTest#noBlockingThreadDelaysInTestSources 2>&1 | Tee-Object -FilePath logs\guard-tier5-20261006.log

# Blocking-exemption policy
mvn test -pl :peegeeq-test-support -Dtest=OnSuccessExceptionSwallowingGuardTest#noBlockingExemptionsInTestSources 2>&1 | Tee-Object -FilePath logs\guard-blocking-exemptions-20261006.log

# Tier 6 - onComplete swallow
mvn test -pl :peegeeq-test-support -Dtest=OnSuccessExceptionSwallowingGuardTest#noOnCompleteSwallowingInTestSources 2>&1 | Tee-Object -FilePath logs\guard-tier6-20261006.log

# Tier 7 - discarded stop()/close() Future
mvn test -pl :peegeeq-test-support -Dtest=OnSuccessExceptionSwallowingGuardTest#noDiscardedFuturesFromStopOrCloseInTestSources 2>&1 | Tee-Object -FilePath logs\guard-tier7-20261006.log

# Tier 8 - async operation asserted only as non-null
mvn test -pl :peegeeq-test-support -Dtest=OnSuccessExceptionSwallowingGuardTest#noAsyncOperationsAssertedOnlyAsNonNullInTestSources 2>&1 | Tee-Object -FilePath logs\guard-tier8-20261006.log

# Tier 9 - discarded subscribe Future
mvn test -pl :peegeeq-test-support -Dtest=OnSuccessExceptionSwallowingGuardTest#noDiscardedFuturesFromSubscribeInTestSources 2>&1 | Tee-Object -FilePath logs\guard-tier9-20261006.log
```

### Run the other four guards

```powershell
mvn test -pl :peegeeq-test-support -Dtest="VertxAsyncForbiddenPatternsGuardTest,DisabledTestsGuardTest,InvalidDurationLiteralGuardTest,SchemaInitializerTestInfrastructureGuardTest" 2>&1 | Tee-Object -FilePath logs\guard-other-20261006.log
```

### Read the summary

```powershell
Get-Content logs\guard-tests-20261006.log | Where-Object { $_ -match '^Found |REGRESSION|STALE|Tests run:|BUILD ' }
```

## Scoping to specific files

The scan is workspace-wide by design. To focus on a subset of files or modules, filter the
report output rather than the scan:

```powershell
# All violations in one file
Get-Content logs\guard-tests-20261006.log | Where-Object { $_ -match 'MultiConfigurationIntegrationTest' }

# Tier 7 hits in one module
Get-Content logs\guard-tests-20261006.log | Where-Object { $_ -match '\[Tier 7\].*peegeeq-db' }

# All violations in one module across all tiers
Get-Content logs\guard-tests-20261006.log | Where-Object { $_ -match 'peegeeq-outbox' }
```

To verify a single file is clean after a fix, run the guard and filter for that file.
Absence of matches means no violations in that file.

## Fix recipes

| Tier | Fix |
|------|-----|
| 3 | Wrap the `.onSuccess` body in `testContext.verify(() -> { ... })` so assertion exceptions route to `failNow`. |
| 2 | Either wrap the synchronous `.close()` in `testContext.verify(...)` or replace it with the async `.close()` chained via `.compose`/`.onSuccess`/`.onFailure`. |
| 4 | Replace `future.await()` with `.onSuccess(...).onFailure(testContext::failNow)` and `testContext.awaitCompletion(timeout, unit)` at the test boundary. A bounded `CountDownLatch.await(timeout, TimeUnit.SECONDS)` is allowed. |
| 5 | Chain off the `Future` returned by the operation, or observe the side-effect (database row, checkpoint flag) directly. Never use `Thread.sleep` to wait for async work. |
| Policy | Remove the exemption annotation and rewrite the test around observable async completion. |
| 6 | Replace `.onComplete(ar -> latch.countDown())` with `.onSuccess(v -> latch.countDown()).onFailure(testContext::failNow)`, or use a `VertxTestContext.Checkpoint` instead of a latch. |
| 7 | Compose on the returned `Future`: `job.stop().onComplete(testContext.succeedingThenComplete())`, or chain into the next step: `.compose(v -> job.stop()).onSuccess(...)`. In `@AfterEach`, accept the `VertxTestContext` parameter and complete it from `manager.close().onComplete(testContext.succeedingThenComplete())`. |
| 8 | Observe the Future's terminal result and assert behaviour inside `testContext.verify(...)`; do not assert only that the Future reference is non-null. |
| 9 | Compose from `subscribe(...)`, or attach success and failure handlers before any dependent send or assertion. |

Full guidance:
- [PEEGEEQ_TESTING_STANDARDS_PATTERNS.md](PEEGEEQ_TESTING_STANDARDS_PATTERNS.md)
- [PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md](PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md)
- [../dev/pgq-coding-principles.md](../dev/pgq-coding-principles.md), section 4

## No opt-outs

The guard permits no source-level exemption tag. Tests that need to demonstrate a
prohibited pattern must assert its observable scheduling or failure contract without
executing that pattern. Counter-examples belong in documentation, not in compilable test
sources.

## Why the guard exists

Vert.x catches every throwable raised on the event loop and routes it to
`Vertx.exceptionHandler`, which logs at WARN by default. A JUnit assertion that throws
inside `.onSuccess(v -> { ... })` therefore never reaches `VertxTestContext`; the test
times out with no stack trace pointing at the assertion. `.onComplete(ar -> latch.countDown())`
fires on failure as well as success, so a failed operation releases the latch and later
assertions run against state that was never built. `Future.await()` on a JUnit platform
thread blocks until the event loop dispatches, which can never happen if the loop is
waiting on that thread. `testContext.verify(...)` and composed Futures are the correct
shapes; the guard enforces them because the compiler and the framework do not.
