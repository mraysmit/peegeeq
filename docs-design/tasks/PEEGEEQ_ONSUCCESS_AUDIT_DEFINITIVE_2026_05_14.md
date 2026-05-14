# PeeGeeQ `.onSuccess` Exception-Swallowing — Definitive Audit (2026-05-14)

> **Status: Tier 3 anti-pattern eliminated workspace-wide.**
> CI guard test `OnSuccessExceptionSwallowingGuardTest` (in `peegeeq-test-support`)
> scans every `peegeeq-*/src/test/java/**/*.java` and currently reports **0 violations**.
> Any future Tier 3 regression will fail the `peegeeq-test-support` build.

This document supersedes all earlier "Phase 3 complete" claims for individual modules.

---

## 1. The anti-pattern (recap)

```java
producer.send(payload)
    .onSuccess(v -> {
        assertEquals(1, count);   // ← if this throws, Vert.x swallows it
        testContext.completeNow();
    })
    .onFailure(testContext::failNow);
```

When the bare assertion throws synchronously inside `.onSuccess`, Vert.x routes the
throwable to `vertx.exceptionHandler` (not to `.onFailure`). `VertxTestContext` never
completes and the test hangs for the full timeout (typically 30s) with **no root cause
in the log**.

### Tiers
| Tier  | Pattern                                                                 | Risk   | Action                                                                  |
|-------|-------------------------------------------------------------------------|--------|-------------------------------------------------------------------------|
| 3     | `.onSuccess(x -> { assertX(...); ... })` — bare assertion in block      | **High** — silent timeout | **Must fix**: wrap body in `testContext.verify(() -> { ... })`            |
| 2     | `.onSuccess(x -> { ... someSync.close(); ... })` — sync op that may throw | Medium | Review: wrap in verify, or move into chained `.onComplete`             |
| 1     | `.onSuccess(x -> testContext.verify(() -> { ... })).onFailure(::failNow)` | Style only | Optional: convert to `.onComplete(testContext.succeeding(x -> testContext.verify(...)))` |

---

## 2. CI guard (the new regression boundary)

`peegeeq-test-support/src/test/java/dev/mars/peegeeq/test/quality/OnSuccessExceptionSwallowingGuardTest.java`

- Scans every `.onSuccess(... -> { body })` **block** lambda in test sources across all `peegeeq-*` modules.
- For each, strips any `<ident>.verify(...)` calls (handles `testContext.verify`, `ctx.verify`, etc.) **and** strips any `try { ... } catch (Throwable | Exception ...) { ... failNow ... }` shields.
- If the stripped body still contains `assertX(...)` or `fail(...)`, the site is reported as a Tier 3 violation and the test fails.

Expression-lambda Tier 1 form (`.onSuccess(v -> testContext.verify(() -> ...))`) is intentionally **not** flagged.

**Run command:**
```powershell
mvn test -pl :peegeeq-test-support -Dtest=OnSuccessExceptionSwallowingGuardTest 2>&1 | Tee-Object -FilePath logs\guard.txt
```

Current result (2026-05-14): **PASS, 0 violations.**

---

## 3. Fixes applied in this audit pass (2026-05-14)

### 3.1 `peegeeq-outbox` — second-pass Tier 2/3 (3 sites)
- `OutboxQueueUnitTest.testClose_MultipleInvocations` — Tier 3, bare `assertNotNull` + sync `queue.close()` in `onSuccess`.
- `OutboxIdempotencyKeyTest.testSendWithIdempotencyKey_ConsumerReceivesOnlyOnce` — Tier 2, sync `consumer.close()` after `verify`.
- `OutboxConsumerGroupSubscriptionEdgeCasesTest.testCleanup_PreventOperationsAfterClose` — Tier 2, sync `group.close()` in bare `onSuccess`.

### 3.2 `peegeeq-db` — Tier 3 (24 sites)
| File                                          | Sites | Lines                               |
|-----------------------------------------------|-------|-------------------------------------|
| `PgClientCoreTest`                            | 1     | 126                                 |
| `PgDatabaseServiceCoreTest`                   | 1     | 105                                 |
| `PgConnectionProviderCoreTest`                | 4     | 93, 122, 142, 174                   |
| `DeadConsumerDetectionJobIntegrationTest`     | 8     | 137, 167, 208, 229, 282, 299, 305, 433 |
| `DatabaseTemplateManagerCoreTest`             | 10    | 97, 110, 134, 180, 189, 217, 230, 257, 302, 393 |

### 3.3 `peegeeq-rest` — Tier 3 (23 sites)
| File                                         | Sites | Lines                                                |
|----------------------------------------------|-------|------------------------------------------------------|
| `ModernVertxCompositionExampleTest`          | 1     | 135                                                  |
| `EndToEndValidationTest`                     | 4     | 105, 132, 166, 195                                   |
| `EventStoreIntegrationTest`                  | 8     | 1466, 1493, 1521, 1565, 1590, 1616, 1737, 1762       |
| `ConsumerGroupSubscriptionIntegrationTest`   | 10    | 199, 230, 342, 406, 485, 523, 617, 709, 853, 1129    |

### 3.4 `peegeeq-outbox` — Tier 1 partial (7 sites)
- `OutboxIdempotencyKeyTest`: 7 of 8 tail `.onSuccess(...).onFailure(testContext::failNow)` patterns converted to canonical `.onComplete(testContext.succeeding(...))`. 1 site (L255) intentionally left because it is nested inside a `.compose(...)` chain where the outer chain owns the failure handler.

**Total fixes this pass: 57 sites (3 outbox + 24 db + 23 rest + 7 outbox-Tier-1).**

---

## 4. Workspace-wide status (per module)

Generated 2026-05-14. Columns: test files in module; total `.onSuccess(... -> {` block lambdas; tail-chain Tier 1 conversion candidates remaining; `.onSuccess` blocks already containing `testContext.verify`; `.onSuccess` blocks containing a `.close()` call (Tier 2 candidates — manual review needed).

| Module                              | Files | `onSuccess` blocks | Tier 1 tails | verify-wrapped | `.close()` in block |
|-------------------------------------|------:|-------------------:|-------------:|---------------:|--------------------:|
| `peegeeq-api`                       | 31    | 0                  | 1            | 0              | 0                   |
| `peegeeq-bitemporal`                | 40    | 53                 | 0            | 9              | 0                   |
| `peegeeq-db`                        | 133   | 92                 | 14           | 6              | 0                   |
| `peegeeq-examples`                  | 39    | 17                 | 17           | 0              | 0                   |
| `peegeeq-examples-spring`           | 31    | 0                  | 0            | 0              | 0                   |
| `peegeeq-integration-tests`         | 28    | 23                 | 0            | 5              | 1                   |
| `peegeeq-migrations`                | 5     | 0                  | 0            | 0              | 0                   |
| `peegeeq-native`                    | 56    | 22                 | 0            | 3              | 1                   |
| `peegeeq-openapi`                   | —     | (no tests)         | —            | —              | —                   |
| `peegeeq-outbox`                    | 90    | 57                 | 0            | 3              | 4                   |
| `peegeeq-performance-test-harness`  | 2     | 0                  | 0            | 0              | 0                   |
| `peegeeq-pg-sidecar`                | 3     | 2                  | 0            | 0              | 0                   |
| `peegeeq-rest`                      | 57    | 245                | 22           | 145            | 6                   |
| `peegeeq-rest-client`               | 5     | 21                 | 0            | 15             | 1                   |
| `peegeeq-runtime`                   | 6     | 4                  | 0            | 0              | 0                   |
| `peegeeq-service-manager`           | 11    | 10                 | 0            | 0              | 0                   |
| `peegeeq-test-support`              | 12    | 0                  | 0            | 0              | 0                   |

Notes:
- "Tier 1 tails" counts the **safe expression-lambda Tier 1 form** `.onSuccess(x -> testContext.verify(...)).onFailure(testContext::failNow)`. These are safe and only stylistic.
- "verify-wrapped" counts block-form `.onSuccess(x -> { ... testContext.verify(...) ... })`. The guard test has already proven these contain no bare assertions outside the verify wrapper.
- "`.close()` in block" counts blocks where a `.close()` call appears in the first ~400 chars of body. This is a coarse heuristic; **must be reviewed per-site** before declaring Tier 2.

---

## 5. Tier 2 sites — triaged and resolved

### 5.1 First pass — 13 candidates from manual regex sweep (2026-05-14)

13 sites were flagged where a `.close()` call appeared inside an `.onSuccess(... -> { ... })` block. After per-site code review:

- **9 genuine Tier 2 sites — FIXED 2026-05-14** by wrapping the `onSuccess` body in `testContext.verify(() -> { ... })`. Compilation verified clean for all 7 affected files.
- **4 false positives** — the `.close()` call is inside a nested `vertx.setTimer(...)` or `Runnable` callback, **not** in the `onSuccess` body itself. Exceptions in those nested callbacks are routed independently and are not affected by `onSuccess` semantics.

| File                                                                                                       | Line  | Status            |
|------------------------------------------------------------------------------------------------------------|------:|-------------------|
| `peegeeq-integration-tests/.../TransactionalIntegrityTest.java`                                            | 281   | False positive — `close()` is inside `setTimer` callback |
| `peegeeq-native/.../ListenNotifyOnlyEdgeCaseTest.java`                                                     | 311   | False positive — `close()` is inside `setTimer` callback |
| `peegeeq-outbox/.../OutboxProducerAdditionalCoverageTest.java`                                             | 288   | **Fixed** — `producer2`/`producer3.close()` wrapped in `testContext.verify` |
| `peegeeq-outbox/.../OutboxProducerAdditionalCoverageTest.java`                                             | 308   | **Fixed** — idempotent `testProducer.close()` x3 wrapped |
| `peegeeq-outbox/.../OutboxProducerAdditionalCoverageTest.java`                                             | 348   | **Fixed** — `complexProducer.close()` wrapped |
| `peegeeq-outbox/.../OutboxProducerTransactionTest.java`                                                    | 467   | **Fixed** — `producer2.close()` wrapped |
| `peegeeq-rest/.../CallPropagationIntegrationTest.java`                                                     | 458   | **Fixed** — `eventPoolHolder[0].close()` merged into existing `testContext.verify` block |
| `peegeeq-rest/.../handlers/MessageOperationsE2ETest.java`                                                  | 122   | **Fixed** — teardown `webClient.close()` wrapped |
| `peegeeq-rest/.../handlers/QueueManagementAdvancedE2ETest.java`                                            | 127   | **Fixed** — teardown `webClient.close()` wrapped |
| `peegeeq-rest/.../handlers/SSEBasicStreamingIntegrationTest.java`                                          | 527   | False positive — `close()` is inside `setTimer` callback |
| `peegeeq-rest/.../handlers/SSEBatchingIntegrationTest.java`                                                | 154   | **Fixed** — teardown `httpClient`/`webClient.close()` wrapped |
| `peegeeq-rest/.../handlers/SSEReconnectionIntegrationTest.java`                                            | 155   | **Fixed** — teardown `httpClient`/`webClient.close()` wrapped |
| `peegeeq-rest-client/.../sse/SSEReadStreamTest.java`                                                       | 54    | False positive — `.close()` is inside a nested `Runnable` constructor arg |

Recommended remediation pattern for genuine Tier 2:

```java
// BEFORE — sync close() can throw and be swallowed
.onSuccess(v -> {
    producer2.close();
    producer3.close();
    testContext.completeNow();
})

// AFTER — close() exceptions surface to testContext
.onSuccess(v -> testContext.verify(() -> {
    producer2.close();
    producer3.close();
    testContext.completeNow();
}))
```

Or, if `close()` returns `Future<Void>`, chain it:

```java
.compose(v -> Future.all(producer2.close(), producer3.close()))
.onComplete(testContext.succeeding(v -> testContext.completeNow()));
```

### 5.2 Second pass — guard extended to detect Tier 2 (2026-05-14)

After Section 5.1 was complete, the CI guard (`OnSuccessExceptionSwallowingGuardTest`) was extended to detect Tier 2 sites with the same precise tokeniser already used for Tier 3 (full lambda body, string/comment-aware, balanced-brace nested-block stripping, `try/catch` shield stripping). Running the extended guard exposed **10 additional Tier 2 sites the manual regex sweep had missed** — all in different files than Section 5.1 (no overlap):

| File                                                                                            | Line  | Status                                              |
|-------------------------------------------------------------------------------------------------|------:|-----------------------------------------------------|
| `peegeeq-bitemporal/.../PgBiTemporalEventStoreComplexTest.java`                                 | 724   | **Fixed** — `objectStore.close()` + `completeNow()` moved inside verify |
| `peegeeq-bitemporal/.../PgBiTemporalEventStoreComplexTest.java`                                 | 763   | **Fixed** — same pattern (`boolFetched`)            |
| `peegeeq-bitemporal/.../PgBiTemporalEventStoreComplexTest.java`                                 | 814   | **Fixed** — same pattern (legacy-scalar)            |
| `peegeeq-bitemporal/.../PgBiTemporalEventStoreComplexTest.java`                                 | 864   | **Fixed** — same pattern (legacy-array)             |
| `peegeeq-bitemporal/.../PgBiTemporalEventStoreComplexTest.java`                                 | 2285  | **Fixed** — same pattern (null headers)             |
| `peegeeq-bitemporal/.../PgBiTemporalEventStoreComplexTest.java`                                 | 2330  | **Fixed** — same pattern (null-value headers)       |
| `peegeeq-bitemporal/.../PgBiTemporalEventStoreComplexTest.java`                                 | 2374  | **Fixed** — same pattern (legacy numeric)           |
| `peegeeq-bitemporal/.../PgBiTemporalEventStoreComplexTest.java`                                 | 2419  | **Fixed** — same pattern (legacy boolean)           |
| `peegeeq-integration-tests/.../TransactionalIntegrityTest.java`                                 | 157   | **Fixed** — `webhookServer.close()` + `cleanupSetup()` moved inside verify |
| `peegeeq-rest/.../CrossLayerPropagationIntegrationTest.java`                                    | 436   | **Fixed** — both `response*Ref.get().request().connection().close()` calls moved inside verify |

The guard's first run on the extended detector flagged 12 candidates; 2 were correctly identified as false positives once `stripTryCatchFailNow` was generalised to `stripTryCatchShields` (any `catch (Throwable|Exception ...)` whose body does not re-`throw` is a containment shield):

| File                                                                                            | Line  | Status                                              |
|-------------------------------------------------------------------------------------------------|------:|-----------------------------------------------------|
| `peegeeq-outbox/.../examples/RetryAndFailureHandlingExampleTest.java`                           | 271   | False positive — `try { producer.close(); } catch (Exception e) { logger.warn(...); }` |
| `peegeeq-rest/.../handlers/ConsumerGroupSubscriptionIntegrationTest.java`                       | 946   | False positive — all `.close()` calls already inside `try/catch(Exception)` with `logger.warn` |

**Verification after fixes (2026-05-14):**
- Guard: `mvn test -pl :peegeeq-test-support -Dtest=OnSuccessExceptionSwallowingGuardTest` → `Tests run: 1, Failures: 0` (0 Tier 2, 0 Tier 3).
- `peegeeq-bitemporal/PgBiTemporalEventStoreComplexTest` (default profile) → `Tests run: 73, Failures: 0, Errors: 0, Skipped: 0`.
- `peegeeq-integration-tests/TransactionalIntegrityTest` (`integration-tests` profile) → `Tests run: 2, Failures: 0`.
- `peegeeq-rest/CrossLayerPropagationIntegrationTest` (`integration-tests` profile) → `Tests run: 10, Failures: 0`.

The CI guard is now the authoritative regression boundary for both Tier 2 and Tier 3. Any future Tier 2 introduction will fail the build at `peegeeq-test-support` test time.

---

## 6. Tier 1 stylistic conversion — remaining

Tier 1 conversion is **stylistic** (uniformity with the canonical `.onComplete(testContext.succeeding(...))` form). It is **not a safety issue** because the existing pattern wraps assertions in `testContext.verify(...)` and any failure is routed to `testContext::failNow`.

Remaining candidate counts (tail-chain `.onSuccess(x -> testContext.verify(...)).onFailure(testContext::failNow)`):

- `peegeeq-api` — 1
- `peegeeq-db` — 14
- `peegeeq-examples` — 17
- `peegeeq-rest` — 22

Tier 1 work is **deferred indefinitely** and not required for safety. Defer further work until a future style sweep.

---

## 7. Recommendations / next concrete actions

1. **Adopt the guard test as a permanent CI gate.** It is already wired into the `peegeeq-test-support` `CORE` test category and will fail any future PR that introduces a Tier 3 site.

2. **Triage the 13 Tier 2 candidates** in Section 5 (file/line listed). Apply the verify-wrapping pattern where the `close()` is in the actual `onSuccess` body. Expect approximately 10–12 real fixes after removing false positives.

3. **Do not bulk-convert Tier 1.** The 54+ Tier 1 candidates are safe; per repo discipline rules (no bulk regex), defer or address one file at a time.

4. **Run module integration suites** after Tier 2 triage to confirm no behavioural regressions:
   ```powershell
   mvn test -pl :peegeeq-db -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\db.txt
   mvn test -pl :peegeeq-rest -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\rest.txt
   mvn test -pl :peegeeq-outbox -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\outbox.txt
   mvn test -pl :peegeeq-bitemporal -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\bitemporal.txt
   mvn test -pl :peegeeq-native -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\native.txt
   mvn test -pl :peegeeq-integration-tests -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\integration.txt
   mvn test -pl :peegeeq-rest-client -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\rest-client.txt
   ```

5. **Retire the old "Phase 3 complete" claims** in `PEEGEEQ_REFACTOR_ONSUCESS_EXCEPTION_SWALLOWING.md` per-module sections. The guard test is now the source of truth.

---

## 8. Lessons learned

- "Phase complete" claims based on grep-driven manual sweeps are unreliable. The 2026-05-06 sweep declared `peegeeq-db`, `peegeeq-rest`, `peegeeq-bitemporal`, `peegeeq-native`, `peegeeq-service-manager`, and `peegeeq-integration-tests` complete — yet a stricter audit on 2026-05-14 found **47 missed Tier 3 sites** in just `peegeeq-db` + `peegeeq-rest`.
- The fix: a **programmatic guard test** that runs in CI is the only durable defense. Sweeps cannot be trusted; the regex must be the contract.
- Variable naming inconsistency (`testContext` vs `ctx`) and inline `try/catch(Throwable)→failNow` shields broke the first iteration of the guard; the final regex normalises both.

---

*Generated 2026-05-14. Authoritative source: `OnSuccessExceptionSwallowingGuardTest` in `peegeeq-test-support`.*
