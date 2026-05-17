# Wave 2 Uncommitted Changes — Testing-Standards Audit

**Date**: 2026-05-16
**Scope**: 28 files modified but not yet committed in branch `master`.
**Rules applied**:
- `docs-design/dev/pgq-coding-principles.md` (banned-pattern list)
- `.github/copilot-instructions.md`
- `docs-design/testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md`
- `docs-design/testing/PEEGEEQ_TESTING_STANDARDS.md`
- `docs-design/testing/PEEGEEQ_VERTX_TEST_SAFETY_PATTERNS.md`

**Severity scale**: CRITICAL > SERIOUS > MODERATE > MINOR > CLEAN.

**Note on pre-existing violations**: Several `static PostgreSQLContainer postgres = ...` declarations (raw type) and `CountDownLatch`-as-Future-bridge patterns in setUp/tearDown predate this work but persist in files I touched. They are flagged here because the file was modified this session — I am responsible for not fixing them when I had the file open. They are NOT introduced by my edits unless explicitly noted.

---

## Fix progress (2026-05-16, follow-up session)

**Completed (compile-clean, banned-pattern grep clean on touched scope):**
- ✅ `fundscustody/FundsCustodyTestBase.java` — full reactive rewrite (CRITICAL → CLEAN)
- ✅ `fundscustody/TradeAuditServiceTest.java` — `delay()` helper rewritten to `vertx.timer(ms).<Void>mapEmpty()`
- ✅ `fundscustody/PositionServiceTest.java` — inherits fixed base, no further changes needed
- ✅ `fundscustody/TradeServiceTest.java` — inherits fixed base, no further changes needed
- ✅ `patterns/ServiceDiscoveryExampleTest.java` — full reactive rewrite, removed System.setProperty (CRITICAL → CLEAN)
- ✅ `peegeeq-service-manager/.../PeeGeeQServiceManager.java` — added instance-field constructor; removed `static final System.getProperty` antipattern. Back-compat constructors preserved.
- ✅ `patterns/NativeVsOutboxComparisonTest.java` — converted `CountDownLatch`/`AtomicReference` Future-bridges to `VertxTestContext` + `Checkpoint` chains.
- ✅ `outbox/AdvancedProducerConsumerGroupTest.java` — 4× `setTimer(ms, cb)` → `vertx.timer(ms).<Void>map(...)` ; setUp/tearDown converted to `VertxTestContext`.
- ✅ `outbox/ConsumerGroupResilienceTest.java` — `setTimer` polling + setUp/tearDown latches removed; reactive chain.
- ✅ `nativequeue/ConsumerGroupLoadBalancingDemoTest.java` — 4× `setTimer(ms, cb)` → `vertx.timer(ms).<Void>map(...)`.
- ✅ `outbox/HighFrequencyProducerConsumerTest.java` — setUp/tearDown + 2× watchdog `setTimer` converted; `t` lambda var renamed to avoid clash with `Throwable t`.
- ✅ `peegeeq-outbox/.../OutboxConsumerErrorHandlingTest.java` — 3× handler `Promise + setTimer + complete` collapsed to `vertx.timer(ms).<Void>map(...)`.
- ✅ `outbox/OutboxServerSideFilteringTest.java` — fixed prior session (B1 awaitCompletion guard, settle window, succeeding).
- ✅ `patterns/RestApiExampleTest.java` — setUp/tearDown latches removed; reactive chain with `vertx.timer(500).<Void>mapEmpty()` for verticle warm-up.
- ✅ `outbox/MultiConfigurationIntegrationTest.java` — `testMultipleQueueConfigurationsInSameApplication` and 3 helpers (`testBatchProcessing`, `testRealTimeProcessing`, `testTransactionalProcessing`) rewritten as `Future<Void>` chains using `AtomicInteger remaining` + `Promise<Void> done`; `.eventually(closes)`; setUp/tearDown converted to `VertxTestContext`. Pre-existing `testConcurrentMultiConfigurationUsage` retains the allowed `testContext.awaitCompletion(...)` JUnit idiom.
- ✅ `outbox/EventSourcingCQRSDemoTest.java` — full rewrite: setUp/tearDown blocking bridges removed; 4-level `vertx.setTimer` pyramid unrolled to composed `vertx.timer().compose(...)` chain; both `testCQRS` and `testCQRS_multipleAccounts_perAccountOrdering` extracted into `runCqrsScenario(...)` / `runMultiAccountScenario(...)` helpers returning `Future<Void>`. **All 6 in-test-body `CountDownLatch` bridges** (config × 2, 500 ms settle × 2, `stopGracefully` × 2) and 2 `assertTrue(testContext.awaitCompletion(30s))` replaced with `Promise<Void> allDone` driven by `commandsProcessed`/`eventsProcessed` counters → `.compose(vertx.timer(500).<Void>mapEmpty()) → .compose(testContext.verify + close + stopGracefully) → .onSuccess(completeNow)/.onFailure(failNow)`. `.otherwiseEmpty()` (banned) replaced with `.transform(ar -> Future.succeededFuture())`. `CountDownLatch`/`AtomicReference` imports removed.
- ⚠️ **Correction**: "Raw `PostgreSQLContainer`" was MISCLASSIFIED as a violation throughout this audit. The project depends on testcontainers `2.0.2` where `PostgreSQLContainer` is **non-generic** (the `<SELF>` type parameter was removed). The raw-looking syntax `static PostgreSQLContainer postgres = ...` is the **only valid form** — parameterizing to `PostgreSQLContainer<?>` or `new PostgreSQLContainer<>(...)` causes `error: type PostgreSQLContainer does not take parameters`. An attempt to "fix" raw types across 14 files was reverted. Files whose ONLY listed issue was "raw type" should be reclassified CLEAN: `nativequeue/NativeQueueFeatureTest`, `nativequeue/ServerSideFilteringTest`, `nativequeue/SimpleNativeQueueTest`, `outbox/PartitionedOrderingDemoTest`, `outbox/TransactionalOutboxAnalysisTest`, `outbox/ZeroSubscriptionProtectionDemoTest`, `patterns/PeeGeeQExampleTest`, `patterns/ShutdownTest`.

**Residual work:**
- Run full `mvn test -Pintegration-tests -pl :peegeeq-examples` (and `-pl :peegeeq-outbox`) to confirm behavioural parity. Compile-clean verified across both modules.
- All SERIOUS/CRITICAL files in the original audit are now compile-clean and banned-pattern-clean on touched scope.

---

## Summary table (post-fix)

Legend: **CLEAN** = no issues remaining. **FIXED** = had violations in original audit; now compile-clean and banned-pattern grep clean on touched scope. **RECLASSIFIED CLEAN** = only flagged for raw `PostgreSQLContainer` — that flag was incorrect for testcontainers 2.0.2 (see Fix progress section).

| # | File | Original | Current |
|---|------|----------|---------|
| 1 | `docs-design/testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md` | CLEAN | CLEAN |
| 2 | `peegeeq-examples/.../CloudEventsExample.java` (main) | CLEAN | CLEAN |
| 3 | `peegeeq-examples/.../FullDistributedTracingExample.java` (main) | CLEAN | CLEAN |
| 4 | `peegeeq-examples/.../SSEFilteringExample.java` (main) | CLEAN | CLEAN |
| 5 | `.../fundscustody/FundsCustodyTestBase.java` | **CRITICAL** | **FIXED** |
| 6 | `.../fundscustody/PositionServiceTest.java` | INHERITED CRITICAL | **FIXED** (via base) |
| 7 | `.../fundscustody/TradeServiceTest.java` | INHERITED CRITICAL | **FIXED** (via base) |
| 8 | `.../nativequeue/ConsumerGroupLoadBalancingDemoTest.java` | SERIOUS | **FIXED** |
| 9 | `.../nativequeue/NativeQueueFeatureTest.java` | MINOR (raw type) | RECLASSIFIED CLEAN |
| 10 | `.../nativequeue/ServerSideFilteringTest.java` | MINOR (raw type) | RECLASSIFIED CLEAN |
| 11 | `.../nativequeue/SimpleNativeQueueTest.java` | MINOR (raw type) | RECLASSIFIED CLEAN |
| 12 | `.../outbox/AdvancedProducerConsumerGroupTest.java` | SERIOUS | **FIXED** |
| 13 | `.../outbox/ConsumerGroupResilienceTest.java` | SERIOUS | **FIXED** |
| 14 | `.../outbox/EventSourcingCQRSDemoTest.java` | SERIOUS | **FIXED** |
| 15 | `.../outbox/HighFrequencyProducerConsumerTest.java` | SERIOUS | **FIXED** |
| 16 | `.../outbox/MultiConfigurationIntegrationTest.java` | MODERATE | **FIXED** |
| 17 | `.../outbox/OutboxServerSideFilteringTest.java` | CLEAN | CLEAN |
| 18 | `.../outbox/PartitionedOrderingDemoTest.java` | MINOR (raw type) | RECLASSIFIED CLEAN |
| 19 | `.../outbox/TransactionalOutboxAnalysisTest.java` | MINOR (raw type) | RECLASSIFIED CLEAN |
| 20 | `.../outbox/ZeroSubscriptionProtectionDemoTest.java` | MINOR (raw type) | RECLASSIFIED CLEAN |
| 21 | `.../patterns/NativeVsOutboxComparisonTest.java` | SERIOUS | **FIXED** |
| 22 | `.../patterns/PeeGeeQExampleTest.java` | MINOR (raw type) | RECLASSIFIED CLEAN |
| 23 | `.../patterns/RestApiExampleTest.java` | MODERATE | **FIXED** |
| 24 | `.../patterns/ServiceDiscoveryExampleTest.java` | **CRITICAL** | **FIXED** |
| 25 | `.../patterns/ShutdownTest.java` | MINOR (raw type) | RECLASSIFIED CLEAN |
| 26 | `peegeeq-native/.../ConsumerGroupSubscriptionTest.java` | CLEAN | CLEAN |
| 27 | `peegeeq-outbox/.../OutboxConsumerErrorHandlingTest.java` | SERIOUS | **FIXED** |
| 28 | `peegeeq-rest/.../SubscriptionPersistenceAcrossRestartIntegrationTest.java` | CLEAN | CLEAN |

**Result**: 0 CRITICAL, 0 SERIOUS, 0 MODERATE, 0 MINOR remaining. All 28 files are compile-clean. Behavioural parity pending integration-test run (see Residual work).

---

## Per-file findings (post-fix)

Each entry: original findings → applied fix → current status.

### 1. `docs-design/testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md`
**Status**: CLEAN. Documentation file; content consistent with other standards docs. No changes required.

### 2. `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/CloudEventsExample.java`
**Status**: CLEAN. `CountDownLatch` used to keep the demo main thread alive — legitimate for an executable example, not a test.

### 3. `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/FullDistributedTracingExample.java`
**Status**: CLEAN. Same as #2.

### 4. `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/SSEFilteringExample.java`
**Status**: CLEAN. Same as #2.

### 5. `peegeeq-examples/.../fundscustody/FundsCustodyTestBase.java`
**Original**: CRITICAL — 5× `System.setProperty(db.*)` (JVM-global side-effects forcing `SAME_THREAD`); 3× `CountDownLatch.await(30s)` Future-to-sync bridges in `cleanupDatabase()` / `setUp()` / `tearDown()`; tearDown copy-paste log message; `.onFailure` cleanup paths that warn-and-continue silently.
**Fix**: Full reactive rewrite. `setUp(Vertx, VertxTestContext)` / `tearDown(VertxTestContext)` JUnit5+Vert.x lifecycle; config built via `PeeGeeQTestConfig.builder().from(postgres)` (System.setProperty block removed); `cleanupDatabase()` returns `Future<Void>`; cleanup failures propagated to `ctx.failNow(...)`; tearDown log corrected.
**Status**: **FIXED**.

### 6. `peegeeq-examples/.../fundscustody/PositionServiceTest.java`
**Original**: INHERITED CRITICAL via base class.
**Fix**: Inherits the rewritten `FundsCustodyTestBase`; no further changes required. Test bodies already used `VertxTestContext` correctly.
**Status**: **FIXED**.

### 7. `peegeeq-examples/.../fundscustody/TradeServiceTest.java`
**Original**: INHERITED CRITICAL via base class.
**Fix**: Same as #6 — inherits fixed base. Local `delay()` helper rewritten to `vertx.timer(ms).<Void>mapEmpty()` (the only file-local change was to `TradeAuditServiceTest`'s `delay()`; this file inherits cleanly).
**Status**: **FIXED**.

### 8. `peegeeq-examples/.../nativequeue/ConsumerGroupLoadBalancingDemoTest.java`
**Original**: SERIOUS — 4× `vertx.setTimer(N, timerId -> {...})` callback form (Vert.x 4.x API) producing un-observed side-effect Futures.
**Fix**: All 4 sites converted to `vertx.timer(ms).<Void>map(...)` chained into the surrounding `Future` composition.
**Status**: **FIXED**.

### 9. `peegeeq-examples/.../nativequeue/NativeQueueFeatureTest.java`
**Original**: MINOR — raw `PostgreSQLContainer` at lines 81, 84.
**Fix**: None required. The "raw type" flag was incorrect: testcontainers 2.0.2 made `PostgreSQLContainer` non-generic, so the unparameterized form is the only valid form.
**Status**: RECLASSIFIED CLEAN.

### 10. `peegeeq-examples/.../nativequeue/ServerSideFilteringTest.java`
**Original**: MINOR — raw type at line 71.
**Fix**: None required (see #9).
**Status**: RECLASSIFIED CLEAN.

### 11. `peegeeq-examples/.../nativequeue/SimpleNativeQueueTest.java`
**Original**: MINOR — raw type at line 68.
**Fix**: None required (see #9).
**Status**: RECLASSIFIED CLEAN.

### 12. `peegeeq-examples/.../outbox/AdvancedProducerConsumerGroupTest.java`
**Original**: SERIOUS — 4× `vertx.setTimer(N, id -> future.complete())` callback form; 2× `CountDownLatch started/closed` + `.await(timeout)` Future-to-sync bridge in setUp/tearDown.
**Fix**: All 4 `setTimer` sites collapsed to `vertx.timer(ms).<Void>map(...)`. setUp/tearDown converted to `VertxTestContext` lifecycle; CountDownLatch removed.
**Status**: **FIXED**.

### 13. `peegeeq-examples/.../outbox/ConsumerGroupResilienceTest.java`
**Original**: SERIOUS — `vertx.setTimer(5000, ...)` polling callback form; 2× `CountDownLatch` Future-bridge in setUp/tearDown.
**Fix**: `setTimer` polling replaced with reactive `vertx.timer(...).compose(...)` chain; setUp/tearDown converted to `VertxTestContext`; CountDownLatch removed.
**Status**: **FIXED**.

### 14. `peegeeq-examples/.../outbox/EventSourcingCQRSDemoTest.java`
**Original**: SERIOUS — nested 4-level `vertx.setTimer(...)` callback pyramid (lines 748–754); 7+ `CountDownLatch` Future-to-sync bridges in test bodies; misleading "Thread.sleep" javadoc.
**Fix**: setUp/tearDown blocking bridges removed; the 4-level pyramid unrolled to a composed `vertx.timer().compose(...)` chain. Both test methods (`testCQRS`, `testCQRS_multipleAccounts_perAccountOrdering`) extracted into `runCqrsScenario(...)` / `runMultiAccountScenario(...)` helpers returning `Future<Void>`. All 6 in-test-body `CountDownLatch` bridges (config × 2, 500ms settle × 2, `stopGracefully` × 2) and 2× `awaitCompletion` calls replaced with `Promise<Void> allDone` driven by `commandsProcessed`/`eventsProcessed` counters → `.compose(vertx.timer(500).<Void>mapEmpty()) → .compose(testContext.verify + close + stopGracefully) → .onSuccess(completeNow)/.onFailure(failNow)`. `.otherwiseEmpty()` (banned) replaced with `.transform(ar -> Future.succeededFuture())`. `CountDownLatch` / `AtomicReference` imports removed.
**Status**: **FIXED**.

### 15. `peegeeq-examples/.../outbox/HighFrequencyProducerConsumerTest.java`
**Original**: SERIOUS — 2× watchdog `vertx.setTimer(20000/30000, ...)` callback form.
**Fix**: setUp/tearDown converted to `VertxTestContext`; both watchdog timers converted to `vertx.timer(ms).<Void>map(...)`. Lambda variable `t` renamed to avoid clash with `Throwable t`.
**Status**: **FIXED**.

### 16. `peegeeq-examples/.../outbox/MultiConfigurationIntegrationTest.java`
**Original**: MODERATE — container started in `@BeforeEach` without try/finally; `.stop()` only in `@AfterEach` (container leak on setUp failure).
**Fix**: `testMultipleQueueConfigurationsInSameApplication` and 3 helpers (`testBatchProcessing`, `testRealTimeProcessing`, `testTransactionalProcessing`) rewritten as `Future<Void>` chains using `AtomicInteger remaining` + `Promise<Void> done`; resource cleanup via `.eventually(closes)`; setUp/tearDown converted to `VertxTestContext`. Pre-existing `testConcurrentMultiConfigurationUsage` retains the allowed `testContext.awaitCompletion(...)` JUnit idiom. A Tier 3 regression introduced during the rewrite (assertions inside `.onSuccess` without `testContext.verify(...)`) was fixed in a follow-up session.
**Status**: **FIXED**.

### 17. `peegeeq-examples/.../outbox/OutboxServerSideFilteringTest.java`
**Original**: CLEAN. Fixed in a prior session: B1 awaitCompletion guard, settle-window for over-delivery detection, `testContext.succeeding(...)` for onSuccess exception safety, try/finally for resource cleanup.
**Status**: CLEAN.

### 18. `peegeeq-examples/.../outbox/PartitionedOrderingDemoTest.java`
**Original**: MINOR — raw type at line 109.
**Fix**: None required (see #9).
**Status**: RECLASSIFIED CLEAN.

### 19. `peegeeq-examples/.../outbox/TransactionalOutboxAnalysisTest.java`
**Original**: MINOR — raw type at line 90.
**Fix**: None required (see #9).
**Status**: RECLASSIFIED CLEAN.

### 20. `peegeeq-examples/.../outbox/ZeroSubscriptionProtectionDemoTest.java`
**Original**: MINOR — raw type at line 93.
**Fix**: None required (see #9).
**Status**: RECLASSIFIED CLEAN.

### 21. `peegeeq-examples/.../patterns/NativeVsOutboxComparisonTest.java`
**Original**: SERIOUS — `CountDownLatch.await(10s)` + `AtomicReference` for async coordination instead of `VertxTestContext` + `Checkpoint`; failures inside async callbacks silently timed out.
**Fix**: All `CountDownLatch` / `AtomicReference` Future-bridges replaced with `VertxTestContext` + `Checkpoint` chains. Async failures now surface via `ctx.failNow(...)`.
**Status**: **FIXED**.

### 22. `peegeeq-examples/.../patterns/PeeGeeQExampleTest.java`
**Original**: MINOR — raw type at lines 108, 143.
**Fix**: None required (see #9).
**Status**: RECLASSIFIED CLEAN.

### 23. `peegeeq-examples/.../patterns/RestApiExampleTest.java`
**Original**: MODERATE — container lifecycle without try/finally guard.
**Fix**: setUp/tearDown latches removed; reactive chain with `vertx.timer(500).<Void>mapEmpty()` for verticle warm-up. Container lifecycle now driven through the `VertxTestContext` chain.
**Status**: **FIXED**.

### 24. `peegeeq-examples/.../patterns/ServiceDiscoveryExampleTest.java`
**Original**: CRITICAL — 2× `System.setProperty("consul.host"/"consul.port", ...)` JVM-global side-effects with no try/finally guard.
**Fix**: Full reactive rewrite. `System.setProperty` block removed; config plumbed via the existing `PeeGeeQTestConfig`-style builder. Companion change in `peegeeq-service-manager/.../PeeGeeQServiceManager.java`: added instance-field constructor; removed `static final System.getProperty` antipattern. Back-compat constructors preserved.
**Status**: **FIXED**.

### 25. `peegeeq-examples/.../patterns/ShutdownTest.java`
**Original**: MINOR — raw type at line 145; `Thread.sleep` calls at lines 77, 115 (inside `Runnable` worker bodies; `Thread.sleep` is the test subject, not a coordination mechanism — legitimate use).
**Fix**: None required (see #9 for the raw-type flag; the `Thread.sleep` usage was legitimate).
**Status**: RECLASSIFIED CLEAN.

### 26. `peegeeq-native/.../pgqueue/ConsumerGroupSubscriptionTest.java`
**Status**: CLEAN. Proper async patterns; disabled tests are documented.

### 27. `peegeeq-outbox/.../OutboxConsumerErrorHandlingTest.java`
**Original**: SERIOUS — 2× `vertx.setTimer(N, id -> { ... promise.complete(); ... })` handler callback form.
**Fix**: 3 handler sites of `Promise + setTimer + complete` collapsed to `vertx.timer(ms).<Void>map(...)`.
**Status**: **FIXED**.

### 28. `peegeeq-rest/.../SubscriptionPersistenceAcrossRestartIntegrationTest.java`
**Status**: CLEAN.

---

## Residual work

All files in this audit are compile-clean and banned-pattern-clean on touched scope. The remaining item is behavioural verification:

```powershell
mvn test -Pintegration-tests -pl :peegeeq-examples 2>&1 | Tee-Object -FilePath logs\wave2-examples-YYYYMMDD.txt
mvn test -Pintegration-tests -pl :peegeeq-outbox   2>&1 | Tee-Object -FilePath logs\wave2-outbox-YYYYMMDD.txt
```

Both runs must show `BUILD SUCCESS` with no new failures vs. the pre-WAVE2 baseline before the changes are committed.

---

## Notes on what was and wasn't my mess this session (historical)

- The "raw `PostgreSQLContainer`" pattern was **misclassified** as a violation throughout the original audit. Testcontainers 2.0.2 removed the `<SELF>` type parameter, so the unparameterized form is the only valid form. The flag has been retracted; files whose only listed issue was raw type are reclassified CLEAN above.
- The `CountDownLatch`-as-Future-bridge pattern in setUp/tearDown of `AdvancedProducerConsumerGroupTest`, `ConsumerGroupResilienceTest`, `EventSourcingCQRSDemoTest`, and `FundsCustodyTestBase` was a pre-existing module idiom from before JUnit5+Vert.x integration. Fixed by moving to `@BeforeEach setUp(VertxTestContext)` / `@AfterEach tearDown(VertxTestContext)` with `ctx.completeNow()`.
- The `vertx.setTimer(N, handler)` callback form is the Vert.x 4.x API; Vert.x 5.x provides `vertx.timer(N)` returning `Future<Long>`. All callback-form sites in WAVE2 files have been converted.
- `System.setProperty(...)` in `FundsCustodyTestBase` and `ServiceDiscoveryExampleTest` has been removed; configuration now flows through `PeeGeeQTestConfig.builder()` / instance-field constructors.

---

## Second-pass audit (2026-05-16, after WAVE2 fix work) — vs `PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md`

Performed a fresh full grep across all 31 uncommitted files against the antipattern doc's key categories (`Future.await`, `CountDownLatch`, `Thread.sleep`, `LockSupport.parkNanos`, `.recover/.otherwise`, `setTimer(...,handler)` callback form, manual `Vertx.vertx()`, fire-and-forget `producer.send`, empty teardown catches, `System.gc`, `System.setProperty/getProperty`, placeholder asserts, `@Disabled`, `shared=true`, `withConnection` for writes).

### NEW SERIOUS findings — files previously marked CLEAN/MINOR that are NOT clean

These all share the identical pattern: `CountDownLatch started = new CountDownLatch(1); consumer.subscribe(...).onSuccess(v -> started.countDown()).onFailure(err -> started.countDown()); started.await(30, TimeUnit.SECONDS);` plus a mirror `CountDownLatch closed` in cleanup. Both `.await(N, SECONDS)` calls are Future-bridge blocking waits.

| File | Latch sites | Notes |
|------|------------:|-------|
| `outbox/OutboxServerSideFilteringTest.java` | 2 (started+closed) | **Previously claimed CLEAN — incorrect.** |
| `nativequeue/SimpleNativeQueueTest.java` | 2 (started+closed) | Was marked MINOR (raw type only). |
| `nativequeue/ServerSideFilteringTest.java` | 2 (started+closed) | Was marked MINOR (raw type only). |
| `outbox/ZeroSubscriptionProtectionDemoTest.java` | 2 (started+closed) | Was marked MINOR (raw type only). |
| `outbox/TransactionalOutboxAnalysisTest.java` | 2 (started+closed) | Was marked MINOR (raw type only). |
| `outbox/PartitionedOrderingDemoTest.java` | **9** (started, closed, stopLatch, stopLatch2b, countLatch, stopLatch2c, stopLatchG1, reactivateLatch, stopLatchG2, latch2) | Heaviest residual antipattern offender. Multiple test bodies use `CountDownLatch(n)` for both Future-bridge and arrival counting. |

### NEW SERIOUS — Manual `Vertx.vertx()` under `VertxExtension` (antipattern §"Manual `Vertx.vertx()` Creation in Tests That Already Have `@ExtendWith(VertxExtension.class)`")

| File | Line | Issue |
|------|-----:|-------|
| `patterns/RestApiExampleTest.java` | 97 | `vertx = Vertx.vertx();` inside `setUp(VertxTestContext)` despite `@ExtendWith(VertxExtension.class)`. Creates an orphan `Vertx` instance the extension doesn't track or shut down. Should accept `Vertx vertx` parameter on lifecycle methods. |

### NEW SERIOUS — Fire-and-forget `producer.send()` (antipattern §"Fire-and-Forget `producer.send()` Without Await or Failure Handler")

| File | Lines |
|------|-------|
| `outbox/EventSourcingCQRSDemoTest.java` | 648 (`eventProducer.send(event)`), 704, 979, 990, 991, 992 (`commandProducer.send(...)`) — no `.onSuccess`/`.onFailure`; if a send fails the corresponding checkpoint never fires and the test times out instead of failing with a real cause. |
| `nativequeue/ConsumerGroupLoadBalancingDemoTest.java` | 274, 367, 503, 606 (`producer.send(work[, headers])`) — same. |

### MODERATE — Empty catch in teardown (antipattern §"Empty Catch Blocks in Test Teardown")

| File | Lines |
|------|-------|
| `outbox/TransactionalOutboxAnalysisTest.java` | 234–235 — `try { consumer.close(); } catch (Exception ignored) { }` repeated. |

### MODERATE — Residual `System.getProperty` in production service-manager constructor

| File | Lines |
|------|-------|
| `peegeeq-service-manager/.../PeeGeeQServiceManager.java` | 60–61 — `PeeGeeQServiceManager(int port)` back-compat constructor still reads `System.getProperty("consul.host"/"consul.port")`. The `static final` form was removed earlier; this fallback constructor preserves it on opt-in. Should be replaced by an explicit no-defaults constructor or a `ConsulConfig`-typed argument when callers are updated. |

### CLEAN false positives (in javadoc only — no code impact)

- `EventSourcingCQRSDemoTest` lines 556, 560 — `Thread.sleep` / `CountDownLatch` mentions inside a javadoc block describing prior implementation.
- `ConsumerGroupResilienceTest`, `HighFrequencyProducerConsumerTest`, `NativeVsOutboxComparisonTest`, `MultiConfigurationIntegrationTest` — `CountDownLatch` mentions in class-level javadoc only; no actual usage in code.
- `ShutdownTest` lines 77, 115 — `Thread.sleep` is the **test subject** (verifying executor interrupt behaviour). Legitimate.

### Updated severity table

| File | New severity | Reason |
|------|--------------|--------|
| `outbox/OutboxServerSideFilteringTest.java` | SERIOUS | Started/closed `CountDownLatch` Future-bridge in setUp/tearDown. Prior CLEAN classification was wrong. |
| `outbox/PartitionedOrderingDemoTest.java` | **CRITICAL** | 9 `CountDownLatch` Future-bridges across multiple test bodies + cleanup. |
| `outbox/TransactionalOutboxAnalysisTest.java` | SERIOUS | started/closed latches + empty catch in teardown. |
| `outbox/ZeroSubscriptionProtectionDemoTest.java` | SERIOUS | started/closed latches. |
| `nativequeue/SimpleNativeQueueTest.java` | SERIOUS | started/closed latches. |
| `nativequeue/ServerSideFilteringTest.java` | SERIOUS | started/closed latches. |
| `outbox/EventSourcingCQRSDemoTest.java` | MODERATE (down from SERIOUS) | 6 latch bridges + setTimer pyramid removed this session. Remaining: 6 fire-and-forget `producer.send(...)` calls. |
| `nativequeue/ConsumerGroupLoadBalancingDemoTest.java` | MODERATE (down from SERIOUS) | setTimer/latches removed. Remaining: 4 fire-and-forget `producer.send(...)`. |
| `patterns/RestApiExampleTest.java` | SERIOUS | Manual `Vertx.vertx()` under `VertxExtension`. setUp/tearDown latches removed but extension isolation broken. |
| `peegeeq-service-manager/.../PeeGeeQServiceManager.java` | MODERATE | Back-compat constructor still reads `System.getProperty(consul.*)`. |

### Recommended priority for residual work

1. **`outbox/PartitionedOrderingDemoTest.java`** (CRITICAL — 9 latch sites). Convert each `CountDownLatch(n)` to `Checkpoint(n)` and each `latch.await(...)` to a `Promise<Void>` driven by checkpoint completion or to `testContext.awaitCompletion(...)`.
2. **6 started/closed-latch files** (`OutboxServerSideFilteringTest`, `SimpleNativeQueueTest`, `ServerSideFilteringTest`, `ZeroSubscriptionProtectionDemoTest`, `TransactionalOutboxAnalysisTest`, plus the `TransactionalOutboxAnalysisTest` empty catch). Identical mechanical fix in each: convert lifecycle methods to `void setUp(Vertx vertx, VertxTestContext ctx)` / `void tearDown(VertxTestContext ctx)` and chain `.onSuccess(v -> ctx.completeNow()).onFailure(ctx::failNow)`.
3. **`patterns/RestApiExampleTest.java`** — remove `Vertx.vertx()` and accept the injected `Vertx`.
4. **Fire-and-forget sends in `EventSourcingCQRSDemoTest` and `ConsumerGroupLoadBalancingDemoTest`** — wrap each `producer.send(x)` in `.onFailure(testContext::failNow)` at minimum.
5. **`TransactionalOutboxAnalysisTest`** empty catches — replace with `.onFailure(err -> logger.warn(...))` or propagate.
6. **`PeeGeeQServiceManager`** — drop the `System.getProperty` fallback constructor or migrate callers.

### Note on classification confidence

The original first-pass audit's "raw type only → MINOR" classifications were derived by greppping for `static PostgreSQLContainer postgres = ...` and treating files with no other hits as CLEAN. That heuristic missed the `CountDownLatch started/closed` pattern in setUp / `.close()` blocks because those latches are short-lived locals inside method bodies rather than fields. This second pass uses `\bCountDownLatch\b` plus `\.await\(\s*\d` to catch all sites including local-variable usage.

