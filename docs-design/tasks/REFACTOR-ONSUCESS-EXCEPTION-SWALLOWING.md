# Refactoring Plan: Fix `onSuccess` Exception Swallowing in Tests

Created: 2026-05-05  
Branch: `feature/offset-watermark-phase1`  
Status: **PHASE 1 COMPLETE**

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

---

## Related Artefacts

| File | Purpose |
|---|---|
| `docs-design/dev/PEEGEEQ_ERROR_HANDLING_ANTIPATTERNS.md` | Full anti-pattern catalogue; the `onSuccess` section is the canonical reference |
| `peegeeq-db/.../performance/VertxOnSuccessExceptionSwallowTest.java` | 6 executable proof tests demonstrating the failure mode and safe patterns |
| `docs-design/testing/PEEGEEQ-DB-TEST-FAILURE-ANALYSIS-20260505.md` | Full analysis of the 7 failures discovered in the 2026-05-05 test run |
| `logs/peegeeq-db-all-tests-20260505.txt` | Baseline test run (1,075 tests, 7 failures) |
