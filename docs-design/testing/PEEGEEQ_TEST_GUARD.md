# PeeGeeQ Async Test Guard

`OnSuccessExceptionSwallowingGuardTest` is a workspace-wide static-analysis test
that fails the build when banned async-test patterns appear in any
`peegeeq-*/src/test/java/**/*.java` file. It is tagged `@Tag(TestCategories.CORE)`,
uses no database and no Testcontainers, and runs in well under a second.

Source: [peegeeq-test-support/src/test/java/dev/mars/peegeeq/test/quality/OnSuccessExceptionSwallowingGuardTest.java](../../peegeeq-test-support/src/test/java/dev/mars/peegeeq/test/quality/OnSuccessExceptionSwallowingGuardTest.java)

## What it checks

The guard runs five independent `@Test` methods, one per tier. Each method
walks the workspace, masks comments and string literals, then applies tier-specific
regex checks.

| Tier | Test method | Pattern flagged |
|------|-------------|-----------------|
| 2/3  | `noTier3OnSuccessExceptionSwallowingInTestSources` | Bare `assertX(...)` / `fail(...)` (Tier 3) or top-level `.close(...)` (Tier 2) inside `.onSuccess(v -> { ... })` outside `testContext.verify(...)`. |
| 4    | `noFutureAwaitInTestSources` | `.await()` on a `Future` in test code. Whitelisted: `testContext.awaitCompletion(...)`, `CountDownLatch.await(timeout, TimeUnit.X)`. |
| 5    | `noBlockingThreadDelaysInTestSources` | `Thread.sleep(...)` or `LockSupport.parkNanos(...)`. |
| 6    | `noOnCompleteSwallowingInTestSources` | `.onComplete(ar -> singleCountdown())` lambdas containing only a countdown/signal op (`countDown`, `getAndIncrement`, `release`, `tryComplete`) with no failure branch (`ar.failed`, `ar.cause`, `failNow`, `onFailure`). |
| 7    | `noDiscardedFuturesFromStopOrCloseInTestSources` | `<receiver>.stop();` / `<receiver>.close();` where the receiver name contains `job`/`manager`/`group` (case-insensitive), the call is followed immediately by `;`, and the result is neither assigned nor returned. |

Failures emit a precise report:

```
Found 1 .onSuccess(...) block(s) with exception-swallowing risk (Tier 3 = 1, Tier 2 = 0).
  [Tier 3] .../MultiConfigurationIntegrationTest.java:215 — .onSuccess(v -> { assertEquals(...)...
```

## Run commands

All commands follow [PEEGEEQ-TEST-COMMANDS.md](PEEGEEQ-TEST-COMMANDS.md) conventions:
no `clean`, `Tee-Object` for logs, run from the workspace root.

### Run all five tiers (whole codebase)

```powershell
mvn test -pl :peegeeq-test-support -Dtest=OnSuccessExceptionSwallowingGuardTest 2>&1 | Tee-Object -FilePath logs\guard-tests-20260517.txt
```

### Run a single tier

```powershell
# Tier 2/3
mvn test -pl :peegeeq-test-support -Dtest=OnSuccessExceptionSwallowingGuardTest#noTier3OnSuccessExceptionSwallowingInTestSources 2>&1 | Tee-Object -FilePath logs\guard-tier3-20260517.txt

# Tier 4 — Future.await()
mvn test -pl :peegeeq-test-support -Dtest=OnSuccessExceptionSwallowingGuardTest#noFutureAwaitInTestSources 2>&1 | Tee-Object -FilePath logs\guard-tier4-20260517.txt

# Tier 5 — Thread.sleep / parkNanos
mvn test -pl :peegeeq-test-support -Dtest=OnSuccessExceptionSwallowingGuardTest#noBlockingThreadDelaysInTestSources 2>&1 | Tee-Object -FilePath logs\guard-tier5-20260517.txt

# Tier 6 — onComplete swallow
mvn test -pl :peegeeq-test-support -Dtest=OnSuccessExceptionSwallowingGuardTest#noOnCompleteSwallowingInTestSources 2>&1 | Tee-Object -FilePath logs\guard-tier6-20260517.txt

# Tier 7 — discarded stop()/close() Future
mvn test -pl :peegeeq-test-support -Dtest=OnSuccessExceptionSwallowingGuardTest#noDiscardedFuturesFromStopOrCloseInTestSources 2>&1 | Tee-Object -FilePath logs\guard-tier7-20260517.txt
```

### Read the summary

```powershell
Get-Content logs\guard-tests-20260517.txt | Where-Object { $_ -match '^Found |Tests run:|BUILD ' }
```

## Scoping to specific files

The scan itself is workspace-wide by design — it is meant to catch regressions
anywhere. To focus on a subset of files or modules, filter the report output
rather than the scan:

```powershell
# All violations in one file
Get-Content logs\guard-tests-20260517.txt | Where-Object { $_ -match 'MultiConfigurationIntegrationTest' }

# Tier 7 hits in one module
Get-Content logs\guard-tests-20260517.txt | Where-Object { $_ -match '\[Tier 7\].*peegeeq-db' }

# All violations in one module across all tiers
Get-Content logs\guard-tests-20260517.txt | Where-Object { $_ -match 'peegeeq-outbox' }
```

To verify a single file is clean after a fix, run the guard and grep for that
file — absence of matches means no violations in that file.

## Fix recipes

| Tier | Fix |
|------|-----|
| 3 | Wrap the `.onSuccess` body in `testContext.verify(() -> { ... })` so assertion exceptions route to `failNow`. |
| 2 | Either wrap the synchronous `.close()` in `testContext.verify(...)` or replace it with the async `.close()` chained via `.compose`/`.onSuccess`/`.onFailure`. |
| 4 | Replace `future.await()` with `.onSuccess(...).onFailure(testContext::failNow)` + `testContext.awaitCompletion(timeout, unit)` at the test boundary. Bounded `CountDownLatch.await(timeout, TimeUnit.SECONDS)` is allowed. |
| 5 | Chain off the `Future` returned by the operation, or observe the side-effect (database row, checkpoint flag) directly. Never use `Thread.sleep` to wait for async work. |
| 6 | Replace `.onComplete(ar -> latch.countDown())` with `.onSuccess(v -> latch.countDown()).onFailure(testContext::failNow)`, or use a `VertxTestContext.Checkpoint` instead of a latch. |
| 7 | Compose on the returned `Future`: `job.stop().onComplete(testContext.succeedingThenComplete())`, or chain into the next step: `.compose(v -> job.stop()).onSuccess(...)`. In `@AfterEach`, accept the `VertxTestContext` parameter and complete it from `manager.close().onComplete(...)`. |

Full guidance:
- [PEEGEEQ_TESTING_STANDARDS_PATTERNS.md](PEEGEEQ_TESTING_STANDARDS_PATTERNS.md)
- [PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md](PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md)
- [PEEGEEQ_VERTX_TEST_SAFETY_PATTERNS.md](PEEGEEQ_VERTX_TEST_SAFETY_PATTERNS.md)

## Opting out

The guard skips any file annotated with `@Tag("blocking-exempt")` at class
level — reserved for tests where the blocking pattern is the subject under
test, or where blocking occurs on a non-reactive thread (worker pool, raw
ExecutorService, post-shutdown quiescence). Each use must carry a
`// BLOCKING-EXEMPT:` comment with a detailed rationale directly above the
tag. Do not use this tag to silence real test code.

Ranking by actual damage, not by tier number:

## Catastrophic JUnit Tests By Accident — silent data/logic corruption

**Tier 3 — bare assertions inside `.onSuccess(v -> { ... })`.** The disaster you named. Mechanically:

```java
producer.send(msg).onSuccess(v -> {
    assertEquals(expected, actual);   // ← throws
    testContext.completeNow();
});
```

When `assertEquals` throws, Vert.x catches the `AssertionError` on the event-loop thread and routes it to `Vertx.exceptionHandler`, which by default just logs at WARN. The lambda never returns to `completeNow`, the `VertxTestContext` never completes, and the test sits idle until JUnit's 30-second timeout fires with the message `Timeout of 30000 ms exceeded` — no stack trace pointing at the actual failed assertion. **A broken assertion looks identical to a hung test.** You can lose hours diagnosing the wrong thing. Worse: a flaky test of this shape passing on rerun gives false confidence that the assertion is "fine".

The Vert.x team's defense is "we don't want the event loop to die from a thrown exception" — which is correct for production. But Vert.x JUnit5 should have made `verify(() -> {...})` the *default* shape in their docs, not an opt-in. Instead, the natural-looking code is the wrong code. Hence the guard.

**Tier 6 — `.onComplete(ar -> latch.countDown())`.** Same category, different mechanism:

```java
producer.send(msg).onComplete(ar -> latch.countDown());
latch.await(5, TimeUnit.SECONDS);
assertEquals(expected, store.size());   // ← runs even though send failed
```

`onComplete` fires on *both* outcomes. A failed send still counts the latch down, the await returns successfully, and the next assertion runs against state that was never built. The test then fails with a misleading message ("expected 10 messages, got 0") that points at the consumer instead of the failed producer. This is the "preconditions unmet" antipattern — the test reports a bug in code that isn't broken.

## Severe — guaranteed hang or guaranteed flake

**Tier 4 — `Future.await()` in test code.** Vert.x 5 added `Future.await()` for virtual threads. On a JUnit *platform* thread (which is what JUnit Jupiter uses unless you opt in to virtual threads), `Future.await()` blocks the calling thread until the future settles. If settling needs the Vert.x event loop to dispatch a callback — which it almost always does — and that event loop happens to call back into your awaiting thread, you have a deadlock. **No timeout. No exception. The test hangs until CI kills the JVM, with no stack trace identifying the await site as the cause.** This is worse than Tier 3 in some ways because there's nothing in the logs at all.

**Tier 5 — `Thread.sleep` / `parkNanos`.** Two failure modes:
1. *On an event-loop thread*: blocks the entire event loop for the duration. Every other handler, every other test sharing that Vertx, every Netty timer, all frozen. If anything you're waiting for needs that event loop to run, you wait the full sleep duration and then time out. This is a self-inflicted deadlock.
2. *On a non-event-loop thread*: works, but is flaky. `Thread.sleep(100)` is enough on a dev laptop and not enough on a CI runner under load. Pick any duration and it's wrong somewhere. The cure is to chain off the actual Future, never to guess at timing.

## Real but lower-impact

**Tier 2 — synchronous `.close()` at top level of `.onSuccess`.** If `close()` throws synchronously, exception goes to `Vertx.exceptionHandler` and is swallowed the same way as Tier 3, but `close()` rarely throws synchronously in this codebase, so the practical hit rate is low. Worth fixing but not the disaster.

**Tier 7 — discarded `Future` from `stop()`/`close()`.** Race condition: shutdown is fire-and-forget while the next test step assumes it's done. In `@AfterEach` cleanup this often appears benign because Surefire is moving on anyway — but it leaks state across tests (a not-yet-closed pool holds connections; the next test gets a different one and fails non-deterministically). Most of the 209 hits are this latent-leak shape, not in-flow races. Fixable, but lower urgency than 3/4/5/6.

---

## The meta-issue raised

Yes — it should not be this easy to fuck up. The shape `.onSuccess(v -> { assertX(...); })` is the most natural thing to write, and it's wrong. Three design choices conspired:

1. **JUnit assertions throw on failure.** Necessary for the JUnit model, but means any context that catches `Throwable` swallows them.
2. **Vert.x catches all throwables on the event loop.** Necessary for production (don't let one bad handler kill the loop), but means tests inherit that swallowing.
3. **`VertxTestContext.verify(...)` is opt-in.** It exists, it works, it correctly routes assertion failures to `failNow`. But it's not the default — you have to know to wrap.

The Vert.x JUnit5 docs do show `verify(...)` in the canonical examples, but they don't *enforce* it, and the naked `.onSuccess(v -> { assertX(...); })` shape compiles, runs, and looks plausible. That's the trap. The guard exists because the language and framework won't enforce what they should.

A cleaner world: `VertxTestContext` would refuse to accept a lambda body that contains assertion calls outside `verify`, at compile time, via an annotation processor. That's not how Vert.x JUnit5 is built, so we have a regex guard instead. Which is exactly what it is — a workaround for a framework gap.
