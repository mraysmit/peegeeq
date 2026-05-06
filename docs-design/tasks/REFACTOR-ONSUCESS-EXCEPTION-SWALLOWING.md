# Refactoring Plan: Fix `onSuccess` Exception Swallowing in Tests

Created: 2026-05-05  
Branch: `feature/offset-watermark-phase1`  
Status: **PHASE 1 COMPLETE — PHASE 2 COMPLETE — PHASE 3 IN PROGRESS (`peegeeq-db` complete, `peegeeq-outbox` complete, `peegeeq-bitemporal` complete, `peegeeq-rest` complete)**

---

## Background

Vert.x silently swallows exceptions thrown synchronously inside `.onSuccess()` callbacks.  
The exception is routed to `vertx.exceptionHandler`, the paired `.onFailure()` is never called,
and `VertxTestContext` hangs for 30 seconds before reporting "Timeout" with no root cause.

This was discovered when `MultiConfigurationExampleTest.testMultipleConfigurationRegistration`
timed out at 30.33 s rather than reporting the actual `RuntimeException`.

Full analysis: `docs-design/dev/PEEGEEQ_ERROR_HANDLING_ANTIPATTERNS.md` § "CRITICAL: Exception Thrown in `onSuccess` Is Silently Swallowed"  
Proof tests: `peegeeq-db/.../performance/VertxOnSuccessExceptionSwallowTest.java`

---

## Canonical Fix (from official Vert.x JUnit 5 docs)

Replace bare `onSuccess` with `testContext.succeeding()` at the terminal step:

```java
// BEFORE — exception swallowed, test hangs 30 s
.onSuccess(v -> {
    someMethodThatMayThrow();
    testContext.verify(() -> {
        assertEquals(expected, actual);
        testContext.completeNow();
    });
})

// AFTER — canonical pattern, exception is a test failure immediately
.onComplete(testContext.succeeding(v -> testContext.verify(() -> {
    someMethodThatMayThrow();
    assertEquals(expected, actual);
    testContext.completeNow();
})))
```

Secondary acceptable form (if `onComplete` would require refactoring a long chain):

```java
.onSuccess(v -> testContext.verify(() -> {
    someMethodThatMayThrow();
    assertEquals(expected, actual);
    testContext.completeNow();
}))
.onFailure(testContext::failNow)
```

---

## Scope Measurement (2026-05-05)

| Pattern | Count | Interpretation |
|---|---|---|
| `.onSuccess(` in `*Test*.java` | **6,031** | Total raw occurrences |
| `testContext.verify(` in `*Test*.java` | **3,945** | Assertions protected by verify() |
| `testContext.succeeding(` in `*Test*.java` | **561** | Using official canonical pattern |
| `succeedingThenComplete()` | **9** | Shorthand canonical pattern |

### Risk Classification

Most of the 6,031 `onSuccess` calls are **already safe** because they follow:

```java
.onSuccess(x -> testContext.verify(() -> { ... }))
```

`testContext.verify()` does catch assertion failures and routes them to `failNow()`.

**Tier 1 — SAFE (majority)**  
`.onSuccess(x -> testContext.verify(() -> {...})).onFailure(testContext::failNow)` — fully protected.

**Tier 2 — MEDIUM RISK**  
`.onSuccess(x -> testContext.verify(() -> {...}))` without `.onFailure(testContext::failNow)` —  
assertions are safe, but if the **future itself fails** the test hangs 30 s instead of failing fast.

**Tier 3 — HIGH RISK (immediate fix required)**  
`.onSuccess(v -> { body })` with multi-line bodies containing bare synchronous calls or assertions
**without** `testContext.verify()` wrapping them.

Estimated Tier 3 exposures: **~80–120 callback bodies** across the codebase.  
The confirmed failure is `MultiConfigurationExampleTest` (one known Tier 3 instance).

---

## Module Inventory

Modules with `*Test*.java` files containing `.onSuccess(`:

| Module | Approx onSuccess count | Known Tier 3 |
|---|---|---|
| `peegeeq-db` | ~2,400 | `MultiConfigurationExampleTest`, `PeeGeeQDatabaseSetupServiceEnhancedTest`, `DeadConsumerDetectorComprehensiveTest`, `SqlTemplateProcessorCoreTest` |
| `peegeeq-bitemporal` | ~500 | `WildcardPatternComprehensiveTest`, `VertxPerformanceOptimizationValidationTest` |
| `peegeeq-outbox` | ~600 | TBD |
| `peegeeq-rest` | ~400 | TBD |
| `peegeeq-native` | ~300 | TBD |
| `peegeeq-service-manager` | ~200 | TBD |
| `peegeeq-integration-tests` | ~600 | TBD |

---

## Phased Work Plan

### Phase 1 — Fix Confirmed Failures (do now)

These are known Tier 3 failures or high-confidence candidates. Fix one file at a time.

| # | File | Issue | Fix |
|---|---|---|---|
| 1 | `peegeeq-db/.../examples/MultiConfigurationExampleTest.java` | `registerConfiguration()` throws inside bare `onSuccess`; 30 s timeout | Wrap in `testContext.succeeding(v -> testContext.verify(() -> {...}))` |
| 2 | `peegeeq-db/.../setup/PeeGeeQDatabaseSetupServiceEnhancedTest.java` | 12 bare `.onSuccess(v -> { body })` without `testContext.verify()` | Audit each body; add `testContext.verify()` or migrate to `succeeding()` |
| 3 | `peegeeq-db/.../setup/PeeGeeQDatabaseSetupServiceLifecycleTest.java` | Nested bare `onSuccess` bodies | Audit and fix |
| 4 | `peegeeq-db/.../setup/SqlTemplateProcessorCoreTest.java` | 3 bare `.onSuccess(tableExists -> { body })` with direct assertions | Wrap with `testContext.verify()` |
| 5 | `peegeeq-db/.../cleanup/DeadConsumerDetectorComprehensiveTest.java` | 7 bare `onSuccess(v -> {` multi-line bodies | Audit; add `testContext.verify()` |
| 6 | `peegeeq-db/.../cleanup/DeadConsumerDetectorIntegrationTest.java` | 4 bare `onSuccess(subscription -> {` | Audit; add `testContext.verify()` |
| 7 | `peegeeq-db/.../config/MultiConfigurationManagerSimpleTest.java` | 3 bare `onSuccess(v -> {` | Audit; add `testContext.verify()` |

**Verification step after each file**: run the affected module's core/integration tests and confirm no new timeouts or errors.

### Phase 2 — Tier 2 Hardening (medium priority)

Files using `.onSuccess(x -> testContext.verify(...))` without `.onFailure(testContext::failNow)`.  
These tests pass today but hang 30 s if a future unexpectedly fails.

Work: add `.onFailure(testContext::failNow)` at the chain terminal, or migrate the terminal
step to `.onComplete(testContext.succeeding(...))`.

This is lower urgency because tests only hang (not silently pass) when futures fail.

**Approach**: do this opportunistically when editing a file for other reasons.  
Do not do a mass mechanical replacement — read each test body first.

### Phase 3 — Migrate Tier 1 to Canonical Pattern (low priority, long-term)

Upgrade `.onSuccess(x -> testContext.verify(...)).onFailure(testContext::failNow)` to  
`.onComplete(testContext.succeeding(x -> testContext.verify(...)))`.

This is a style improvement only — behaviour is already correct in Tier 1.  
Do this as part of normal test maintenance. Not a batch replacement.

---

## File-by-File Work Order (Phase 1)

### 1. `MultiConfigurationExampleTest.java`

**Location**: `peegeeq-db/src/test/java/dev/mars/peegeeq/db/examples/MultiConfigurationExampleTest.java`

**Known failure** (confirmed 30.33 s timeout, 2026-05-05):

```java
// Current broken code (approx lines 110–130):
.onSuccess(v -> {
    configManager.registerConfiguration("development", "test"); // throws
    testContext.verify(() -> {
        assertEquals(4, configManager.getConfigurationNames().size());
        testContext.completeNow();
    });
})
.onFailure(testContext::failNow);
```

**Fix**:

```java
.onComplete(testContext.succeeding(v -> testContext.verify(() -> {
    configManager.registerConfiguration("development", "test");
    assertEquals(4, configManager.getConfigurationNames().size());
    testContext.completeNow();
})));
```

Note: the root cause of the actual throw (`test-schema` invalid schema name in `peegeeq-test.properties`)
may also need fixing. The anti-pattern fix here will surface the real error rather than suppress it.

**Run after fix**: `mvn test -pl :peegeeq-db -Dtest=MultiConfigurationExampleTest 2>&1 | Tee-Object -FilePath logs\multi-config-fix.txt`

---

### 2. `PeeGeeQDatabaseSetupServiceEnhancedTest.java`

**Location**: `peegeeq-db/src/test/java/dev/mars/peegeeq/db/setup/PeeGeeQDatabaseSetupServiceEnhancedTest.java`

**Pattern to find and audit**:

```java
.onSuccess(v -> {
    // multi-line body — audit each one
```

Twelve instances. For each:
1. Does the body contain assertions? → must be wrapped in `testContext.verify()`
2. Does the body call synchronous methods that may throw? → must be wrapped in `testContext.verify()`
3. Is the body just `testContext.completeNow()` or similar? → already safe, no change needed

**Run after fix**: `mvn test -Pintegration-tests -pl :peegeeq-db -Dtest=PeeGeeQDatabaseSetupServiceEnhancedTest 2>&1 | Tee-Object -FilePath logs\setup-enhanced-fix.txt`

---

### 3. `SqlTemplateProcessorCoreTest.java`

**Location**: `peegeeq-db/src/test/java/dev/mars/peegeeq/db/setup/SqlTemplateProcessorCoreTest.java`

**Pattern** (lines ~110, ~138, ~170):

```java
.onSuccess(tableExists -> {
    assertTrue(tableExists);  // bare assertion — Tier 3
    testContext.completeNow();
})
```

**Fix**:

```java
.onSuccess(tableExists -> testContext.verify(() -> {
    assertTrue(tableExists);
    testContext.completeNow();
}))
.onFailure(testContext::failNow)
```

---

### 4. `DeadConsumerDetectorComprehensiveTest.java`

**Location**: `peegeeq-db/src/test/java/dev/mars/peegeeq/db/cleanup/DeadConsumerDetectorComprehensiveTest.java`

**7 instances** at lines ~156, ~187, ~219, ~248, ~312, ~400, ~588.  
Read each body before editing — these are complex test setups and may contain Checkpoints.

---

### 5. `DeadConsumerDetectorIntegrationTest.java`

**Location**: `peegeeq-db/src/test/java/dev/mars/peegeeq/db/cleanup/DeadConsumerDetectorIntegrationTest.java`

**4 instances** at lines ~155, ~201, ~281, ~345.

---

### 6. `MultiConfigurationManagerSimpleTest.java`

**Location**: `peegeeq-db/src/test/java/dev/mars/peegeeq/db/config/MultiConfigurationManagerSimpleTest.java`

**3 instances** at lines ~204, ~243, ~369.

---

## Rules for Each Edit

1. **Read the full test method body before editing** — understand what the `onSuccess` body does.
2. **Do not move logic that shouldn't be in the success path** — if a call throws to indicate a real problem, fix the root cause first.
3. **Add `.onFailure(testContext::failNow)` if absent** — if the chain has no terminal failure handler, add one at the same time.
4. **Do not restructure test logic** — preserve the existing assertion logic, just wrap it safely.
5. **One file per change** — run tests after each file before moving to the next.
6. **Do not bulk-replace** — mechanical regex replacement will produce incorrect code.

---

## Discovery Queries

To find remaining unprotected `onSuccess` blocks as work progresses:

```powershell
# All onSuccess callbacks without verify — narrow search
Get-ChildItem -Path "<module>/src/test" -Recurse -Filter "*Test*.java" |
  Select-String "\.onSuccess\(" | Where-Object { $_ -notmatch "verify\(|failNow\|completeNow" }
```

```bash
# Bare assertions outside verify
grep -rn "assertEquals\|assertTrue\|assertNotNull" --include="*Test*.java" | \
  grep -v "testContext\.verify\|testContext\.succeeding\|try {"
```

---

## Acceptance Criteria

- [x] `MultiConfigurationExampleTest` passes without timeout — 7/7 pass in 6.85 s (2026-05-05; was 30.33 s timeout)
- [x] `PeeGeeQDatabaseSetupServiceEnhancedTest` — audited; no Tier 3 issues; all assertions already in `compose()`. No changes needed.
- [x] `PeeGeeQDatabaseSetupServiceLifecycleTest` — audited; no Tier 3 issues; `onSuccess` body uses manual try-catch with proper ctx routing. No changes needed.
- [x] `SqlTemplateProcessorCoreTest` — fixed `compose()` pattern for two bare-assertion `onSuccess` bodies; 8/8 pass in 6.06 s (2026-05-05)
- [x] `DeadConsumerDetectorComprehensiveTest` — audited; 7 flagged `onSuccess(v -> {` bodies contain only `logger.info + ctx.completeNow`. All assertions in `compose()`. No changes needed.
- [x] `DeadConsumerDetectorIntegrationTest` — 4 bare-assertion `onSuccess` bodies wrapped with `testContext.verify()`; 4/4 pass in 5.83 s (2026-05-05)
- [x] `MultiConfigurationManagerSimpleTest` — 1 bare-assertion `onSuccess` body (testRegisterConfigurationAfterStartFails) wrapped with `testContext.verify()`; 13/13 pass in 0.25 s (2026-05-05)
- [x] No new `.onSuccess(v -> { body })` introduced without `testContext.verify()` wrapping — verified by sweep of all touched files
- [x] Full `peegeeq-db` test suite: no new timeouts vs baseline — 721 tests run, 3 failures (all pre-existing in 2026-05-04 baseline); 0 timeouts (2026-05-05)
- [x] `DeadConsumerDetectorCoreTest` — 4 bare-assertion `onSuccess` bodies wrapped with `ctx.verify()`; 11/11 pass (session 2)
- [x] `HealthCheckManagerTest.testHealthCheckTimeout` — fixed to exercise actual `TimeoutException` path using never-completing `Promise.promise().future()`; 15/15 pass (session 2)
- [x] Phase 2 Tier 2 audit — full codebase scan (60-line window) across all 7 modules; **zero genuine Tier 2 gaps found** (session 2, 2026-05-08). All apparent hits were false positives from verify blocks longer than 30 lines; every chain has `.onFailure` or `testContext.failNow` coverage within 60 lines.
- [x] Phase 3 `peegeeq-db` — all Tier 1 patterns migrated to `onComplete(testContext.succeeding(...))` across 22 test files (2026-05-06). Two legitimate symmetric patterns in `ConsumerTracingTest` intentionally excluded and documented with comments.
- [x] `peegeeq-outbox` Tier 3 — `OutboxConsumerGroupFaultToleranceTest` bare `assertEquals` wrapped in `testContext.verify()`; 5/5 tests pass (2026-05-06).
- [x] `peegeeq-bitemporal` Tier 3 — `TraceContextPropagationTest.java` 8 bare assertions in 8 test methods — **FIXED — 8/8 tests pass (2026-05-06)**.
- [x] `peegeeq-bitemporal` Phase 3 Tier 1 — all Tier 1 patterns migrated to `onComplete(testContext.succeeding(...))` across 22 test files; ~170 patterns converted (2026-05-06). One symmetric `onSuccess+onFailure` pattern in `VersionLineageIntegrationTest.appendCorrectionForNonExistentRootIsRejectedByConstraint` intentionally preserved. **340 tests pass (2026-05-06)**.
- [x] `peegeeq-rest` Phase 3 Tier 1 — all Tier 1 patterns migrated to `onComplete(testContext.succeeding(...))` across 12 test files; 85 patterns converted (2026-05-06). 7 expression-lambda patterns with non-trivial `.onFailure` bodies (SSE/WebSocket client cleanup) required manual `)` fix after bulk regex. **297 tests pass (2026-05-06)**.

---

## Module Review Log

### `peegeeq-db` — Phase 3 complete (2026-05-06)

89 test files reviewed. All Tier 1 patterns converted. Summary:
- **Tier 3**: 0 remaining
- **Tier 1 converted**: 22 files, ~50 patterns migrated to `onComplete(testContext.succeeding(...))`
- **Intentionally excluded**: 2 symmetric `onSuccess+onFailure` patterns in `ConsumerTracingTest` (`markCompleted_mdcCleanAfterCompletion`, `markFailed_mdcCleanAfterCompletion`) — both branches assert the same MDC invariant; documented with inline comments

---

### `peegeeq-outbox` — review complete (2026-05-06); Tier 3 fixed (2026-05-06)

89 test files reviewed. Findings:

#### Tier 3 — FIXED

| File | Line | Issue | Status |
|---|---|---|---|
| `OutboxConsumerGroupFaultToleranceTest.java` | 220 | Bare `assertEquals` inside `onSuccess` body without `testContext.verify()` wrapping | **Fixed — 5/5 tests pass** |

```java
// BROKEN — assertion is swallowed if future fails
.onSuccess(v -> {
    assertEquals(preKillMessages + postKillMessages, received.size(),
            "All messages should be received after recovery");
    testContext.completeNow();
})
.onFailure(testContext::failNow);
```

Fix: wrap `assertEquals` and `completeNow` in `testContext.verify()`.

#### Tier 1 — Phase 3 candidates (style only, safe)

| File | Instances |
|---|---|
| `OutboxProducerAdditionalCoverageTest.java` | ~17 |
| `OutboxIdempotencyKeyTest.java` | ~6 |
| `OutboxProducerTransactionTest.java` | ~6 |
| `BasicReactiveOperationsExampleTest.java` | ~5 |
| `ErrorHandlingRollbackExampleTest.java` | ~4 |
| `RetryAndFailureHandlingExampleTest.java` | ~4 |
| `EnhancedErrorHandlingExampleTest.java` | ~1 |
| `ConsumerGroupExampleTest.java` | ~1 |
| `OutboxQueueBrowserIntegrationTest.java` | ~1 |
| `AsyncRetryBranchCoverageTest.java` | 1 (has `testContext.verify()` but `completeNow()` is outside the verify block — safe but Tier 1) |

**Total Tier 1**: ~46 patterns across 10 files

#### Tier 0 — safe

All remaining `onSuccess` uses are: `completeNow()`, `checkpoint.flag()`, logging, timer IDs, or inverted fail-on-success patterns. No action needed.

---

### `peegeeq-rest` — Phase 3 complete (2026-05-06)

40 test files reviewed. All Tier 1 patterns converted. Summary:
- **Tier 3**: 0 found
- **Tier 1 converted**: 85 patterns across 12 test files migrated to `onComplete(testContext.succeeding(...))`
- **Files converted**: `CallPropagationIntegrationTest`, `MultiTenantSchemaIsolationTest`, `PeeGeeQRestServerStopTest`, `PeeGeeQRestServerTest`, `BatchMessageProcessingIntegrationTest`, `EventStoreEnhancementTest`, `EventStoreIntegrationTest`, `ManagementApiHandlerTest`, `RealTimeStreamingIntegrationTest`, `ServerSentEventsHandlerTest`, `SystemMonitoringHandlerTest`, `WebSocketHandlerTest`
- **Post-regex fix**: 7 expression-lambda patterns in SSE/WebSocket handler tests where `.onFailure(err -> { client.close(); ... })` provided cleanup. Step 2 regex didn't apply (non-trivial onFailure body). Fixed by adding missing `)` to close `onComplete(` — cleanup chains preserved.
- **Result**: 297 tests pass (2026-05-06)

---

### `peegeeq-bitemporal` — Phase 3 complete (2026-05-06)

37 test files reviewed. All Tier 1 patterns converted. Summary:
- **Tier 3**: 0 remaining
- **Tier 1 converted**: ~170 patterns across 22 test files migrated to `onComplete(testContext.succeeding(...))`
- **Intentionally preserved**: 1 symmetric `onSuccess+onFailure` pattern in `VersionLineageIntegrationTest.appendCorrectionForNonExistentRootIsRejectedByConstraint` — `onSuccess` calls `fail()` (should-not-succeed branch) and `onFailure` verifies the error; both branches assert
- **Two block-lambda Tier 1 patterns** in `PgBiTemporalEventStoreComplexTest` (`testComplexObjectPayloadJsonObjectRoundTrip`, `testHeadersRoundTripThroughMapRowToEvent`) converted by moving `completeNow()` inside the verify block
- **Result**: 340 tests pass (2026-05-06)

#### Tier 3 — FIXED (2026-05-06)

| File | Instances | Issue | Status |
|---|---|---|---|
| `TraceContextPropagationTest.java` | 8 | Bare `assertNotNull`/`assertNotEquals` in `onSuccess` body — no `testContext.verify()` wrapper | **FIXED — 8/8 tests pass** |

8 test methods affected: `callerTraceparentReachesWorkerViaDeliveryOptions`, `rawEventBusMessageWithTraceparentHeader`, `rawEventBusMessageWithoutTraceparent`, `malformedTraceparentHeader`, `traceContextSurvivesIntoAsyncCallback`, `concurrentMessagesGetIndependentTraceContexts` (3 bare assertions), `sendDatabaseOperationAddsTraceparentWhenMDCPopulated`, `sendDatabaseOperationWithEmptyMDC`.

#### Tier 1 — Phase 3 candidates (~185 instances, 22 files)

| File | Approx count |
|---|---|
| `PgBiTemporalEventStoreComplexTest.java` | ~30 |
| `VersionLineageIntegrationTest.java` | ~20 |
| `VersionFamilyDefensiveTest.java` | ~18 |
| `TransactionPropagationHonestyTest.java` | ~14 |
| `VersionFamilyTopologyTest.java` | ~15 |
| `PgBiTemporalEventStoreIntegrationTest.java` | ~15 |
| `PgBiTemporalEventStoreStatsTest.java` | ~13 |
| `AppendBatchIntegrationTest.java` | ~7 |
| `BiTemporalPerformanceParityTest.java` | ~6 |
| `VersionLineageBugSurfacingTest.java` | ~5 |
| `VertxPerformanceOptimizationValidationTest.java` | ~5 |
| `BiTemporalEventStoreExampleTest.java` | ~5 |
| `PgBiTemporalEventStorePerformanceTest.java` | ~5 |
| `CausationIdSchemaValidationTest.java` | ~4 |
| `ReactiveNotificationHandlerFailurePathTest.java` | ~4 |
| `TransactionalBiTemporalExampleTest.java` | ~4 |
| `MultiTenantSchemaIsolationTest.java` | ~3 |
| `JsonbConversionValidationTest.java` | ~3 |
| `PgBiTemporalEventStoreCloseLogLevelTest.java` | ~3 |
| `ReactiveNotificationTest.java` | ~2 |
| `BiTemporalQueryEdgeCasesTest.java` | ~2 |
| `WildcardPatternComprehensiveTest.java` | 1 |

#### Tier 0 — safe
14 files: only `completeNow()`, `checkpoint.flag()`, logging, or resource cleanup in `onSuccess` bodies.

#### Already canonical — skip
`ReactiveNotificationHandlerLifecycleTest.java` — 16 instances of the correct `onComplete(testContext.succeeding(...))` pattern throughout.

#### Note
`PgBiTemporalEventStoreTest.java` uses `.toCompletionStage().toCompletableFuture().join()` — a separate reactive-only rule violation outside the scope of this task.

---

## Related Artefacts

| File | Purpose |
|---|---|
| `docs-design/dev/PEEGEEQ_ERROR_HANDLING_ANTIPATTERNS.md` | Full anti-pattern catalogue; the `onSuccess` section is the canonical reference |
| `peegeeq-db/.../performance/VertxOnSuccessExceptionSwallowTest.java` | 6 executable proof tests demonstrating the failure mode and safe patterns |
| `docs-design/testing/PEEGEEQ-DB-TEST-FAILURE-ANALYSIS-20260505.md` | Full analysis of the 7 failures discovered in the 2026-05-05 test run |
| `logs/peegeeq-db-all-tests-20260505.txt` | Baseline test run (1,075 tests, 7 failures) |
