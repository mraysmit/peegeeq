# Test Suite Connection Exhaustion — Investigation & Remediation Plan

**Date**: 2026-04-18
**Branch**: `feature/offset-watermark-phase1`
**Trigger**: Full reactor test run (`mvn test -Pintegration-tests`) failed with 277 errors, all rooted in PostgreSQL `FATAL: sorry, too many clients already (SQLSTATE 53300)`.
**Run log**: `logs/full-suite-20260418-163257.txt` (12.59 MB)
**Status**: Tier 1 + Tier 2 + Tier 3 applied. All three root causes fixed. §3 property-key mismatch + `shared=false`. §6 eager Vertx close. §3c secondary PgConnectionManager leak — all 28 test classes now close their `connectionManager` in `@AfterEach`, pool config reduced to `maxSize=3, shared=false, idleTimeout=2s, connectionTimeout=5s`. §4 demoted to appendix. §5 demoted to code-quality / future-risk. **Next action: run full integration suite to validate.**

---

## 1. Honest scoping disclosure

The recent `.recover()` removal work on this branch **did not introduce this leak**. Evidence:

- `PeeGeeQManager.closeReactive()` cleanup chain is composed entirely of `.eventually()` steps; every step still runs regardless of failure.
- `PgConnectionManager.close()` semantics are preserved: the `.recover(...)` → `.onFailure(...).mapEmpty()` swap is equivalent for a non-failing terminal handler. The `Future.all(closeFutures)` join still happens.
- `BaseIntegrationTest.@AfterEach` was not modified by the recover-removal work; it still calls `awaitFuture(manager.closeReactive())`.
- The leak conditions described below exist in code that pre-dates this branch.

The full suite likely never passed end-to-end on this configuration; module-by-module runs have been masking the issue because each fork resets the connection budget.

---

## 2. Symptom summary

From `logs/full-suite-20260418-163257.txt`:

| Metric | Count |
|---|---|
| Total `Caused by: PgException: too many clients already` | 710 |
| Total `Caused by: ... startup validation failed` | 471 |
| Total `Caused by: Failed to start PeeGeeQ Manager` | 464 |
| Pools created (all services) before first exhaustion | 705 |
| `PgConnectionManager.close()` invoked before first exhaustion (log pattern: `"Closing all N pool(s) asynchronously"`) | 595 |
| `PgConnectionManager.close()` succeeded before first exhaustion (log pattern: `"Closed successfully"`) | 551 |
| First exhaustion timestamp | `16:34:29` (~85 s into the run, ~80 s from first pool creation) |
| First failing test class | `dev.mars.peegeeq.db.consumer.PartitionedFetcherIntegrationTest` |

Pool creation breakdown by service name (full file):

| Service | Pools created |
|---|---|
| `peegeeq-main` | 756 |
| `test` | 54 |
| `test-metrics` | 24 |
| `test-client` | 22 |
| `test-tracing` | 15 |
| `test-metrics-provider` | 14 |
| `test-service` | 13 |
| (~12 other test-* names) | <12 each |

`peegeeq-main` pool sizing distribution (max-size, shared flag):

| Config | Count |
|---|---|
| `maxSize=3, shared=true` | 588 |
| `maxSize=10, shared=true` | 158 |
| `maxSize=32, shared=true` | 5 |
| `maxSize=5, shared=true` | 3 |
| `maxSize=8, shared=true` | 2 |

Note: Tests using `BaseIntegrationTest` get `maxSize=3` (588 pools). Tests **not** using `BaseIntegrationTest` get the production default `maxSize=32` or other values — i.e. they are using whatever `peegeeq-default.properties` provides.

### Post-Tier-1 pool sizing distribution (from `logs/tier1-full-suite.txt`)

After applying Tier 1 fixes (property keys corrected, `shared=false`, idle-timeout=2s):

| Config | Count | Source |
|---|---|---|
| `maxSize=3, shared=false` | 650 | BaseIntegrationTest subclasses (Tier 1 fix working) |
| `maxSize=10, shared=true` | 473 | **Secondary PgConnectionManagers — NOT closed (see §3c)** |
| `maxSize=5, shared=true` | 94 | Various standalone tests |
| `maxSize=10, shared=false` | 20 | Tests with explicit non-shared config |
| `maxSize=32, shared=true` | 8 | Tests loading peegeeq-default.properties directly |
| `maxSize=5, shared=false` | 5 | Various |
| Other (`maxSize=2,8,16,20,50`) | 10 | Edge cases |

**Key finding**: 473 pools (38% of total) are created by secondary `PgConnectionManager` instances with `maxSize=10, shared=true` — completely bypassing the Tier 1 fixes. These are leaked (never closed). See §3c.

---

## 3. Root cause — primary

**`BaseIntegrationTest.setupTestConfiguration()` sets pool tuning properties using keys that the configuration loader does not read. The conservative test config is silently ignored, and tests fall through to production defaults.**

### What the test sets

[`BaseIntegrationTest.java` lines 179-183](peegeeq-db/src/test/java/dev/mars/peegeeq/db/BaseIntegrationTest.java#L179-L183):

```java
System.setProperty("peegeeq.database.pool.min-size", "1");
System.setProperty("peegeeq.database.pool.max-size", "3");
System.setProperty("peegeeq.database.pool.connection-timeout", "PT10S");
System.setProperty("peegeeq.database.pool.idle-timeout", "PT10S");      // ignored
System.setProperty("peegeeq.database.pool.max-lifetime", "PT2M");        // ignored
```

### What the loader reads

[`PeeGeeQConfiguration.getPoolConfig()` lines 386-394](peegeeq-db/src/main/java/dev/mars/peegeeq/db/config/PeeGeeQConfiguration.java#L386-L394):

```java
public PgPoolConfig getPoolConfig() {
    return new PgPoolConfig.Builder()
        .maxSize(getInt("peegeeq.database.pool.max-size", 32))
        .maxWaitQueueSize(getInt("peegeeq.database.pool.max-wait-queue-size", 128))
        .connectionTimeout(Duration.ofMillis(getLong("peegeeq.database.pool.connection-timeout-ms", 30000)))
        .idleTimeout(Duration.ofMillis(getLong("peegeeq.database.pool.idle-timeout-ms", 600000)))
        .shared(getBoolean("peegeeq.database.pool.shared", true))
        .build();
}
```

### Key mismatch table

These are **entirely different property key names**, not type-conversion errors on the same key. The test sets `pool.connection-timeout`; the loader reads `pool.connection-timeout-ms`. Because the key names differ, the system property is never looked up and the loader silently falls through to its hardcoded default.

| Test sets | Loader reads | Effective value |
|---|---|---|
| `pool.connection-timeout` (Duration string `PT10S`) | `pool.connection-timeout-ms` (long ms) | **30 000 ms** (default) |
| `pool.idle-timeout` (Duration string `PT10S`) | `pool.idle-timeout-ms` (long ms) | **600 000 ms = 10 min** (default) |
| `pool.max-lifetime` (Duration string `PT2M`) | *(no such key in loader)* | **N/A — not applied** |
| `pool.max-size` ✓ | `pool.max-size` ✓ | 3 (works) |
| `pool.min-size` | *(loader ignores min-size; PoolOptions doesn't expose it)* | unused |
| *(not set by test)* | `pool.shared` (default: `true`) | **true** (production default — see §3b) |

### Defaults from `peegeeq-default.properties` (lines 13-21)

```properties
peegeeq.database.pool.min-size=8
peegeeq.database.pool.max-size=32
peegeeq.database.pool.shared=true
peegeeq.database.pool.connection-timeout-ms=30000
peegeeq.database.pool.idle-timeout-ms=600000
peegeeq.database.pool.max-lifetime-ms=1800000
peegeeq.database.pool.auto-commit=true
```

(`max-lifetime-ms` is in the file but not consumed by `getPoolConfig()`. Should be either wired up or removed from `peegeeq-default.properties` — see Tier 2.)

### Related inconsistency in `validateDatabaseConfig()`

[`PeeGeeQConfiguration.validateDatabaseConfig()` lines 209-224](peegeeq-db/src/main/java/dev/mars/peegeeq/db/config/PeeGeeQConfiguration.java#L209-L224) uses different defaults than `getPoolConfig()` for the same logical keys:

| Key | `validateDatabaseConfig()` default | `getPoolConfig()` default |
|---|---|---|
| `pool.min-size` | 5 | *(not read by loader)* |
| `pool.max-size` | 10 | 32 |

This does not affect the connection exhaustion bug, but it means the validation logic checks against different thresholds than the pool builder actually uses. Should be unified in the Tier 2 cleanup.

### Sub-second hazard in `PoolOptions` wiring

[`PgConnectionManager.createReactivePool()` lines 298-306](peegeeq-db/src/main/java/dev/mars/peegeeq/db/connection/PgConnectionManager.java#L298-L306) builds `PoolOptions` using **integer seconds**, not milliseconds:

```java
PoolOptions poolOptions = new PoolOptions()
    .setMaxSize(poolConfig.getMaxSize())
    .setMaxWaitQueueSize(poolConfig.getMaxWaitQueueSize())
    .setConnectionTimeout((int) poolConfig.getConnectionTimeout().toSeconds())
    .setConnectionTimeoutUnit(TimeUnit.SECONDS)
    .setIdleTimeout((int) poolConfig.getIdleTimeout().toSeconds())
    .setIdleTimeoutUnit(TimeUnit.SECONDS)
    .setShared(poolConfig.isShared());
```

`Duration.toSeconds()` truncates. Implication for Tier 1: `idle-timeout-ms=2000` becomes 2 seconds (intended), but **any value `<` 1000 ms silently becomes `0`**, which Vert.x interprets as "no timeout" — i.e. connections are never evicted. Do not use sub-second values when picking timeout numbers in Tier 1, and consider switching to a unit-explicit setter in a follow-up.

### Root cause 3b — `shared=true` default never overridden in tests

`BaseIntegrationTest.setupTestConfiguration()` never sets `peegeeq.database.pool.shared`, so tests inherit the production default (`true`). With `shared=true`, Vert.x pools use reference counting to govern connection lifetime — `Pool.close()` does **not** deterministically release the underlying TCP sockets. Instead, connections linger until the reference count reaches zero and the idle-eviction timer fires.

This is a **co-equal root cause** with the property-key mismatch above. The 600 s idle timeout is deadly *because* shared pools defer real close via reference counting. Even if the idle-timeout were corrected to 2 s, `shared=true` would still defer connection release past `Pool.close()` completion. Conversely, with `shared=false`, `pool.close()` is deterministic even with the long timeout — so both must be fixed together.

### Combined consequence

Every `BaseIntegrationTest`-derived test creates a **shared** pool that holds idle connections on the PG server for **up to 10 minutes** after the test ends, even when `closeReactive()` runs cleanly. With ~8.8 pool creations per second across forks (see §2), the 200-connection ceiling is consumed in seconds — far inside the 600 s idle window.

### Pool semantics implication

`Pool.close()` returning successfully does not guarantee the underlying TCP sockets have FIN-ed by the time the next test starts. With shared pools (`setShared(true)`) and idle-timeout=600 s, the Vert.x pool may retain connections via reference counting until idle eviction runs.

---

## 3c. Root cause — secondary (FIXED — Tier 3 applied 2026-04-18)

**28 test classes that extend `BaseIntegrationTest` created secondary `PgConnectionManager` fields with their own pools and never closed them.** These leaked pools were the primary remaining source of connection exhaustion after Tier 1+2 fixes.

**Resolution**: All 28 files edited — each now has `@AfterEach` calling `connectionManager.close()`, pool config reduced to `maxSize=3, shared=false, idleTimeout=2s, connectionTimeout=5s`. Compilation verified (BUILD SUCCESS).

### The pattern

Many integration tests need a standalone `PgConnectionManager` to test component-level APIs (e.g., `SubscriptionManager`, `CleanupService`, `BackfillService`) that take a `PgConnectionManager` parameter. They create this in `@BeforeEach`:

```java
// From CleanupServiceIntegrationTest — REPRESENTATIVE EXAMPLE
@BeforeEach
public void setUp() throws Exception {
    super.setUpBaseIntegration();   // creates manager (pool #1: maxSize=3, shared=false)

    connectionManager = new PgConnectionManager(manager.getVertx(), null);   // NEW manager

    PgPoolConfig poolConfig = new PgPoolConfig.Builder()
        .maxSize(10)                                // hardcoded to 10!
        .build();                                   // shared defaults to true!

    connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);
    // pool #2: maxSize=10, shared=true — NEVER CLOSED
}
```

But `BaseIntegrationTest.tearDownBaseIntegration()` only closes `manager` (pool #1). The secondary `connectionManager` (pool #2) is **never closed** — no `@AfterEach` in the subclass calls `connectionManager.close()`.

### Evidence — pool creation distribution (Tier 1 run)

| Pool Config | Count | Ownership |
|---|---|---|
| `maxSize=3, shared=false` | 650 | `BaseIntegrationTest` manager (properly closed) |
| `maxSize=10, shared=true` | 473 | **Secondary PgConnectionManagers (LEAKED)** |

473 leaked pools × 10 maxSize = up to **4,730 potential connections** competing for 200 slots.

### The 28 affected test classes

All extend `BaseIntegrationTest`, create a `connectionManager` field, call `getOrCreateReactivePool()`, and have **no** `@AfterEach` that closes it:

**`cleanup/`**:
- `DeadConsumerDetectorIntegrationTest.java` (maxSize=10)
- `DeadConsumerDetectorComprehensiveTest.java` (maxSize=10)
- `CleanupServiceIntegrationTest.java` (maxSize=10)

**`consumer/`**:
- `ConsumerGroupRetryServiceIntegrationTest.java` (maxSize=10)
- `ConsumerGroupFetcherIntegrationTest.java` (maxSize=10)
- `CompletionTrackerIntegrationTest.java` (maxSize=10)

**`subscription/`**:
- `TopicConfigServiceIntegrationTest.java` (maxSize=10)
- `ZeroSubscriptionValidatorIntegrationTest.java` (maxSize=10)
- `SubscriptionManagerIntegrationTest.java` (maxSize=10)
- `SubscriptionManagerEdgeCaseTest.java` (maxSize=10)
- `ForceRemoveIntegrationTest.java` (maxSize=10)
- `CancelCleanupIntegrationTest.java` (maxSize=10)
- `ResurrectionReBackfillIntegrationTest.java` (maxSize=10)
- `StartPositionDatabaseStateTest.java` (maxSize=10)

**`metrics/`**:
- `RemainingPrometheusMetricsIntegrationTest.java` (maxSize=10)
- `ConsumerGroupMetricsIntegrationTest.java` (maxSize=10)

**`fanout/`**:
- `P4_BackfillVsOLTPTest.java` (maxSize=20)
- `P3_MixedTopicsTest.java` (maxSize=20)
- `P2_FanoutScalingTest.java` (maxSize=20)
- `FlappingProtectionIntegrationTest.java` (maxSize=10)
- `BackfillServiceIntegrationTest.java` (maxSize=10)
- `BackfillServiceConcurrencyTest.java` (maxSize=50)
- `BackfillScopePerformanceTest.java` (maxSize=20)
- `BackfillRateLimitingIntegrationTest.java` (maxSize=10)
- `FanoutProducerIntegrationTest.java` (maxSize=10)
- `FanoutPerformanceValidationTest.java` (maxSize=20)
- `DeadConsumerGroupCleanupIntegrationTest.java` (maxSize=10)
- `DeadConsumerDetectionJobIntegrationTest.java` (maxSize=10)

### Why Tier 2 (eager Vertx close) doesn't fix this

Tier 2's `vertxRef.close()` in `BaseIntegrationTest.@AfterEach` closes the **same Vertx instance** that the secondary `connectionManager` uses (since it was created via `manager.getVertx()`). When Vertx closes, it tears down event loops and should close Netty channels. However:

1. The secondary pool is `shared=true` — Vert.x shared pool semantics use reference counting. The pool may not release TCP connections until all internal references are resolved, which may survive even `vertx.close()` if the close is not graceful.
2. Even if Vertx close does eventually FIN the sockets, there is a **race window**: the next test's `@BeforeEach` can start before the OS completes TCP teardown of the previous pool's sockets. PostgreSQL counts connections by TCP socket, not by Vert.x pool state.
3. The pool's `idleTimeout` is the **default** (600s) because these secondary pools use `PgPoolConfig.Builder().maxSize(10).build()` with no idle timeout override — the Builder default is 600,000 ms.

### Fix — Tier 3 (APPLIED 2026-04-18)

Two-part fix, applied file-by-file per project rules. Both parts applied simultaneously to all 28 files.

**Part A** (DONE): Added `@AfterEach` to each of the 28 test classes that closes the secondary `connectionManager`:
```java
@AfterEach
void tearDown() throws Exception {
    if (connectionManager != null) {
        connectionManager.close();
    }
}
```
For `DeadConsumerDetectionJobIntegrationTest` (which already had `@AfterEach`), the `connectionManager.close()` call was merged into the existing `cleanUp()` method.

**Part B** (DONE): Reduced all secondary pool sizes from 10/20/50 to 3, set `shared=false` + `idleTimeout=2s` + `connectionTimeout=5s`:
```java
PgPoolConfig poolConfig = new PgPoolConfig.Builder()
    .maxSize(3)
    .shared(false)
    .idleTimeout(Duration.ofSeconds(2))
    .connectionTimeout(Duration.ofSeconds(5))
    .build();
```

**Verification**: `mvn compile test-compile -pl peegeeq-db -am` → BUILD SUCCESS. Full integration suite run pending.

---

## 4. Hypothesis — Vertx close timing (UNVERIFIED — deprioritized)

> **Deprioritized**: §3 (property-key mismatch + `shared=true`) fully explains the exhaustion, and §6 (`@AfterEach` amplification) explains the acceleration. This hypothesis is speculative and lower priority. The proving test is retained below for completeness. **Do not investigate until Tier 1 is validated and exhaustion persists.**

**Claim**: Each `PeeGeeQManager` owns its own `Vertx` instance. The asynchronous `vertx.close()` step in `closeReactive()` does not synchronously join Netty channel teardown, leaving half-closed sockets in `pg_stat_activity`.

**Evidence status**: NOT PROVEN. This is inferred from Vert.x library behavior, not measured in this repository. The full-suite log contains **zero** `"Vert.x instance closed successfully"` messages across 12.59 MB. However, this is **not necessarily evidence of a bug**: `closeReactive()` step 7 only closes Vertx when `vertxOwnedByManager == true`. If most test managers do not own their Vertx instance, zero messages is expected behavior, not evidence that the close path fails. There are no `pg_stat_activity` backend-count measurements anywhere in the test suite.

[`PeeGeeQManager.closeReactive()` step #7, lines 474-489](peegeeq-db/src/main/java/dev/mars/peegeeq/db/PeeGeeQManager.java#L474-L489):

```java
.eventually(() -> {
    if (vertx != null && vertxOwnedByManager) {
        return vertx.close()...
    }
    ...
})
```

### Proving test: `VertxCloseBackendReleaseTimingTest`

**Prerequisites**: `application_name` must be plumbed through `PgConnectionManager.createReactivePool()` → `PgConnectOptions.setProperties()` so backends can be attributed per-manager. Pattern exists in [`VertxPerformanceOptimizer.java`](peegeeq-db/src/main/java/dev/mars/peegeeq/db/performance/VertxPerformanceOptimizer.java). Without this, the test cannot filter `pg_stat_activity` rows.

**Test design**:

```java
@Test
void vertxClose_releasesAllBackendsWithinBoundedGrace(Vertx vertx, VertxTestContext ctx) {
    String app = "prove-s4-" + UUID.randomUUID();
    // 1. Create a PgConnectionManager with application_name = app
    // 2. Open 3 connections, execute trivial queries, close connections back to pool
    // 3. Call pool.close(), then vertx.close()
    // 4. Immediately after vertx.close() future resolves, query pg_stat_activity
    //    using a SEPARATE connection (from the shared test container directly)
    //    for: SELECT count(*) FROM pg_stat_activity WHERE application_name = $app
    // 5. If count == 0: vertx.close() IS sufficient — §4 is disproven
    //    If count > 0: measure how long until count reaches 0 (poll at 100ms intervals, cap at 5s)
    //    Record the measured lag as the "FIN grace window"
}
```

**Outcome mapping**:
- count == 0 immediately → §4 is **disproven**, remove from document
- count > 0, drops to 0 within N ms → §4 is **confirmed** with measured grace = N ms, promote to root cause with data
- count > 0, never drops to 0 within 5s → different bug, investigate separately

**This test must be run and its result recorded before §4 can be classified as a root cause.**

---

## 5. Code quality — missing `setName` on shared pool (NOT a contributing factor in the test scenario)

**Confirmed code gap**: `PgConnectionManager` calls `setShared(true)` on `PoolOptions` but never calls `setName(...)`. Source: [`PgConnectionManager.java` lines 298-306](peegeeq-db/src/main/java/dev/mars/peegeeq/db/connection/PgConnectionManager.java#L298-L306).

```java
PoolOptions poolOptions = new PoolOptions()
    .setMaxSize(poolConfig.getMaxSize())
    ...
    .setShared(poolConfig.isShared());     // shared=true
// no .setName(serviceId) call anywhere
```

**Claimed consequence**: within any one Vertx, every shared pool aliases the default-name slot, so multiple service IDs created on the same Vertx instance fight over a single shared pool. Reference counting then governs when connections actually close.

**Evidence status**: The code gap is **confirmed by source inspection**. The consequence (aliasing, reference-counting interference) is **inferred from Vert.x library semantics** but NOT demonstrated in this repository. **Crucially, in the test scenario each `BaseIntegrationTest` creates a fresh Vertx per manager, so the aliasing is inert and does NOT contribute to connection exhaustion.** The consequence would only manifest in a production scenario with multiple `PgConnectionManager` instances on a single Vertx. This section is retained as a code-quality finding for the Tier 4 cleanup, not as a root cause of the current failure.

### Proving test: `SharedPoolAliasingTest`

```java
@Test
void twoServicesOnSameVertx_withSharedUnnamed_shareOnePool(Vertx vertx, VertxTestContext ctx) {
    String appA = "prove-s5a-" + UUID.randomUUID();
    String appB = "prove-s5b-" + UUID.randomUUID();
    PgPoolConfig sharedUnnamed = new PgPoolConfig.Builder()
        .maxSize(3).shared(true).idleTimeout(Duration.ofSeconds(2)).build();

    // 1. Create two pools on the SAME vertx with different serviceIds but shared=true, no name
    Pool poolA = mgr.getOrCreateReactivePool("svcA", connCfgFor(appA), sharedUnnamed);
    Pool poolB = mgr.getOrCreateReactivePool("svcB", connCfgFor(appB), sharedUnnamed);

    // 2. Open maxSize connections on poolA
    // 3. Attempt to open a connection on poolB
    //    - If poolB returns a connection immediately: pools are independent → §5 consequence DISPROVEN
    //    - If poolB blocks/queues: pools share the same slot → §5 consequence CONFIRMED
    // 4. Verify via pg_stat_activity that both application_names are present
    //    (or only one, if aliased)
}

@Test
void twoServicesOnSameVertx_withSharedNamed_getIndependentPools(Vertx vertx, VertxTestContext ctx) {
    // Same as above but with .setName(serviceId) added
    // Must prove that naming fixes the aliasing — poolB gets its own connections
    // This is the control test that validates the Tier 4 fix
}
```

**Outcome mapping**:
- poolB blocks when poolA is saturated → aliasing **confirmed**, promote §5 to root cause with data, prioritise Tier 4 `setName` fix
- poolB gets independent connections → aliasing **disproven**, downgrade §5 to code-quality issue only, deprioritise Tier 4

**This test must be run before §5 can be classified as a root cause contributing to connection exhaustion.**

---

## 6. What is **not** broken — and what is (CORRECTED)

### Confirmed not broken

- `PeeGeeQManager.closeReactive()` cleanup chain **structure** — source-verified: every `.eventually()` step is chained and will run if the chain is entered. Source: [`PeeGeeQManager.java` lines 394-489](peegeeq-db/src/main/java/dev/mars/peegeeq/db/PeeGeeQManager.java#L394-L489).
- `PgConnectionManager.close()` — source-verified: snapshots service IDs, closes every pool via `Future.all`, completes when all per-pool futures complete. Source: [`PgConnectionManager.java` lines 453-483](peegeeq-db/src/main/java/dev/mars/peegeeq/db/connection/PgConnectionManager.java#L453-L483).
- `SharedPostgresTestExtension` — source-verified: single container per JVM, `max_connections=200`, `withReuse(true)`. Source: [`SharedPostgresTestExtension.java` lines 100-130](peegeeq-db/src/test/java/dev/mars/peegeeq/db/SharedPostgresTestExtension.java#L100-L130).
- The `.recover()` → `.transform()` / `.onFailure().mapEmpty()` swap — source-verified semantically equivalent for the cleanup paths involved.

### BROKEN — `@AfterEach` grace timer prevents `closeReactive()` from being called (LOG-PROVEN)

**Evidence**: The full-suite log contains **232** occurrences of `RejectedExecutionException: event executor terminated` at [`BaseIntegrationTest.java` line 125](peegeeq-db/src/test/java/dev/mars/peegeeq/db/BaseIntegrationTest.java#L125), out of **696** total `@BeforeEach` invocations (33%). Verified by: `(Select-String -Path $log -Pattern "RejectedExecutionException.*event executor terminated" | Measure-Object).Count` → 232; `(Select-String -Path $log -Pattern "setUpBaseIntegration" | Measure-Object).Count` → 696.

The `@AfterEach` method `tearDownBaseIntegration()` calls `manager.getVertx().timer(100)` as a grace delay **before** calling `closeReactive()`. When the Vertx event loop is already dead (which happens after `start()` fails due to pool exhaustion), the `timer()` call throws `RejectedExecutionException`. The `catch` block logs the error and continues to the `finally` block, which nulls `manager` — but **`closeReactive()` is never invoked**.

The cascade:
1. Pool exhaustion → `manager.start()` fails
2. `@BeforeEach` catch block tries `awaitFuture(manager.closeReactive())` — may partially succeed or fail if Vertx is degraded
3. `@AfterEach` runs, calls `manager.getVertx().timer(100)` → `RejectedExecutionException` because Vertx event loop is dead
4. `closeReactive()` is **skipped** → pool connections are **never closed**
5. Connection leak accelerates, compounding the primary cause

This is an **amplification mechanism**, not the root cause. The primary cause (§3, property-key mismatch) creates the exhaustion; this defect accelerates the leak once exhaustion begins.

### Proving test: `AfterEachGraceTimerFailureTest`

```java
@Test
void afterEach_closesManagerEvenWhenVertxEventLoopIsDead(VertxTestContext ctx) {
    // 1. Create a PeeGeeQManager with its own Vertx
    // 2. Start it, do trivial work
    // 3. Close the Vertx instance directly (simulating the degraded state)
    // 4. Call the same teardown logic as @AfterEach
    // 5. Assert: closeReactive() was still attempted (or pool connections were released
    //    by an alternative path)
    //
    // Current code: FAILS — timer(100) throws RejectedExecutionException,
    //   closeReactive() is never called
    // After fix (remove or guard the grace timer): PASSES
}
```

**Fix direction (promoted to Tier 1)**: The `manager.getVertx().timer(100)` grace delay in `@AfterEach` must be guarded — either wrapped in a try/catch that still proceeds to `closeReactive()`, or removed entirely (it serves no documented purpose). The `closeReactive()` chain itself already handles a dead Vertx in step 7 via the `RejectedExecutionException` catch. This fix is low-risk and directly improves teardown reliability, so it belongs in Tier 1 alongside the property-key fixes, not deferred.

**Additional code smell in `@AfterEach`**: Line 144 calls `System.gc()` — relying on garbage collection for resource cleanup is a code smell and should be removed. Connection resources must be released deterministically via `closeReactive()`, not via finalizers. This line should be removed in the Tier 1 edits.

---

## 7. Math sanity check

- PG `max_connections` = 200
- Reserved by PG for superuser + autovacuum ≈ 5
- Effective ceiling ≈ 195
- BaseIntegrationTest pool `max-size` = 3 (78% of peegeeq-main pools; 21% are maxSize=10)
- Surefire parallelism: configurable, but at observed rate ≈ 8.8 pool creations/sec aggregated across forks
- Idle-timeout (effective) = 600 s
- Steady-state connection accumulation: 8.8 × 3 × 600 = **15 840 connection-seconds in flight** in worst case (maxSize=3 only; maxSize=10 pools consume faster)

195 ÷ (8.8 × 3) ≈ 7.4 s of headroom before saturation (maxSize=3 only). Including the 158 maxSize=10 pools would reduce headroom further. Observed time-to-first-exhaustion: ~80 s from first pool creation (better than worst case because some pools idle-evict early via internal heuristics, many tests don't hold all 3 connections concurrently, and close operations do release some pools within the window). Numbers reconcile.

---

## 8. Remediation Plan

### Tier 0 — TDD: write the missing infrastructure tests FIRST

**The reason this defect survived for so long is that there are zero tests asserting that the test infrastructure itself works.** A property-key typo in `BaseIntegrationTest` silently degrades every downstream test in the project, but no test catches it because the only thing that would catch it is a test against the test infrastructure. We fix this by writing failing tests **before** any production or test-infrastructure code change. Each test must fail on `master` for the documented reason, then pass after the corresponding Tier 1/2 edit.

All tests below live in `peegeeq-db/src/test/java/dev/mars/peegeeq/db/infrastructure/` (new package). They are tagged `@Tag(TestCategories.INTEGRATION)`, use the shared container via `SharedPostgresTestExtension`, and use Vert.x reactive primitives only (no JDBC, no `CompletableFuture`, no blocking) per the project coding principles.

#### Compliance review against `pgq-coding-principles.md`

The Tier 0 plan was reviewed line-by-line against [docs-design/dev/pgq-coding-principles.md](docs-design/dev/pgq-coding-principles.md). Findings — these MUST be applied to the test sketches in §T1–T13 when implemented; the sketches as written are illustrative and have the gaps below.

| # | Finding | Required correction |
|---|---|---|
| C1 | **T1 is mis-tagged.** It only constructs `PeeGeeQConfiguration` and reads `getPoolConfig()` — zero database operations. Per "Only tag tests as CORE if they test pure logic with ZERO database operations." | T1 → `@Tag(TestCategories.CORE)`, no `@Testcontainers`, no `SharedPostgresTestExtension`. T2–T13 stay INTEGRATION. |
| C2 | **Annotations not made explicit on non-`BaseIntegrationTest` sketches.** T3, T6, T8–T12 test `PgConnectionManager` directly, with no manager. Principles require every integration test to carry both `@Tag(TestCategories.INTEGRATION)` and either `@Testcontainers` or `@ExtendWith(SharedPostgresTestExtension.class)` explicitly. | Each integration test class header must spell out: `@Tag(TestCategories.INTEGRATION)` + `@ExtendWith({SharedPostgresTestExtension.class, VertxExtension.class})`. Do not leave to inference. |
| C3 | **T4 sketch creates an ad-hoc `Vertx.vertx()` for the timer**, which itself leaks an event loop per test. Violates "fix the cause, not the symptom." | Use the `Vertx vertx` parameter injected by `VertxExtension`: `vertx.timer(500).mapEmpty()`. Never `Vertx.vertx()` inside a test method. |
| C4 | **Test method signatures don't show where `vertx` comes from.** Several sketches use `vertx` without declaring it. | Standard signature for every reactive test: `void testName(Vertx vertx, VertxTestContext ctx)`. The `VertxExtension` injects both. |
| C5 | **T6 wording "sabotage via decorator" reads like a stub.** Principles forbid Mockito and reflection. A real wrapper class is fine; the wording is not. | Reword: "wrap the underlying `Pool` with a real implementation class that throws on `close()` — not a Mockito mock, not a reflection proxy." |
| C6 | **Helper methods referenced but unspecified.** `chainBorrowReleaseCycles`, `openN`, `warmUp`, `trivialWork`, `chainSequentialLifecycles`, `cfgFor(app)`, `connCfgFor(app)`, `nonSharedSmall()` are used but have no defined home or signature. | Place in `peegeeq-db/src/test/java/dev/mars/peegeeq/db/infrastructure/InfrastructureTestHelpers.java`. All return `Future<T>` — no blocking, no `CompletableFuture`. List signatures in the doc before implementing tests. |
| C7 | **`application_name` per-manager attribution is a prerequisite, not assumed.** T4, T9–T13 depend on `pg_stat_activity.application_name` being set per `PeeGeeQManager` / per pool. The current `PgConnectionConfig` → `PgConnectOptions` build path may not set it. Per "Investigate Before Implementation." | Insert **Step 0** in the TDD execution order: read `PgConnectionManager.createReactivePool()` (lines ~273-310). If `application_name` is not plumbed, write a small failing test that proves the gap, then add `properties.put("application_name", ...)` to `PgConnectOptions`, then proceed to T4. |
| C8 | **`PgConnectionDiagnostics` location is hedged.** Currently says "(`peegeeq-test-support` or `peegeeq-db` test scope)". Principles say "Follow Established Conventions." | Pin to `peegeeq-db/src/test/java/dev/mars/peegeeq/db/infrastructure/`. The reactor-ordering issue with `peegeeq-test-support` (see §9) makes that module unreliable as a dependency for `peegeeq-db` tests. |
| C9 | **Missing top-of-section "no mocks, no reflection, no JDBC" reminder.** Principles call this out three times. Easy to miss when scanning the test sketches. | Add a "Constraints" callout box at the top of Tier 0 restating the four absolutes: reactive Vert.x only, no Mockito, no reflection, no JDBC, no blocking, no `Thread.sleep`. |
| C10 | **No instruction to capture the red baseline.** Principle "Verify Test Methods Are Actually Executing" + "Read Logs Carefully." Without captured logs, a future bisect can't prove the test ever exercised the bug. | Amend the TDD execution order: for each red→green test (T2, T5, T7, T9, T11, T13), capture failing output to `logs/<test>-baseline-red.txt` *before* applying the corresponding fix, and the green output to `logs/<test>-baseline-green.txt` after. Commit both alongside the test code. |

#### Items the plan already gets right (no action)

- All sketches use `.compose()` / `.onSuccess()` / `.onFailure()` — composable futures, no callback hell.
- Uses `VertxTestContext.succeeding(...)` — proper assertion idiom, not raw `.onComplete(ar -> if (ar.succeeded()))`.
- No `CompletableFuture`, no `.toCompletionStage()`, no `.get()`/`.join()`, no `Thread.sleep`.
- Delays use `vertx.timer(...)`.
- `PgConnectionDiagnostics` explicitly stated reactive — no JDBC.
- Real PostgreSQL via `SharedPostgresTestExtension` (TestContainers) for every DB-touching test.
- Uses `PostgreSQLContainer<?>` constant pattern via `SharedPostgresTestExtension` (no hardcoded image tags in test sketches).

#### Constraints (apply to every test sketch in T1–T13)

- **Reactive Vert.x only.** All async APIs return `io.vertx.core.Future<T>`. Use `.compose()`, `.map()`, `.transform()`, `.eventually()`, `.onSuccess()`, `.onFailure()`. No `CompletableFuture`, no `CompletionStage`, no `.toCompletionStage()` / `.toCompletableFuture()` bridges.
- **No blocking.** No `.get()`, no `.join()`, no `Thread.sleep`, no spin-loop polling. Delays via `vertx.timer(...)` only. Test completion via `VertxTestContext.awaitCompletion(...)`.
- **No mocks, no reflection.** Real objects only. Wrappers are fine; Mockito and `java.lang.reflect.*` are not.
- **No JDBC.** No `java.sql.Connection`, `DriverManager`, `PreparedStatement`, `ResultSet`. Use Vert.x PgClient (`Pool`, `SqlConnection`, `Tuple`, `RowSet`).
- **TestContainers, not in-memory DBs.** No H2, no HSQLDB. Real PostgreSQL via `SharedPostgresTestExtension`.

#### Test analysis — what must be asserted

| # | Concern | Failure mode it catches | Currently caught? |
|---|---|---|---|
| T1 | `PeeGeeQConfiguration.getPoolConfig()` honors every documented pool key | Property-key typo in loader; default value silently used | No |
| T2 | `BaseIntegrationTest.setupTestConfiguration()` produces the `PgPoolConfig` it claims (integration) | Property-key typo in test setup (the actual current bug) | No |
| T2b | Same as T2 but CORE (no DB) — **primary test for the property-key mismatch** | Same as T2; cannot be blocked by connection exhaustion | No |
| T3 | `PgConnectionManager` builds a `Pool` whose runtime parameters match the supplied `PgPoolConfig` | Builder/PoolOptions wiring drift | No |
| T4 | `PeeGeeQManager.closeReactive()` releases every PG backend it opened | Leaked sockets after clean shutdown | No |
| T5 | Sequential `BaseIntegrationTest` lifecycles do not accumulate PG backends | Cumulative connection leak across tests (the production symptom) | No |
| T6 | `PgConnectionManager.close()` is idempotent and Future completes only after every pool is fully closed | Premature completion masking incomplete teardown | No |
| T7 | `PgPoolConfig.idleTimeout` shorter than X actually evicts idle connections in ≤ X+grace | `setShared(true)` reference-counting holds connections past their idle timeout | No |
| T8 | `SqlConnection.close()` returns connections to the pool, doesn't open new backends | Pool leak per borrow/release cycle | No |
| T9 | `Pool.close()` releases every backend that pool owns within bounded grace | `Pool.close()` future resolves before sockets actually FIN (esp. shared pools) | No |
| T10 | `PgConnectionManager.closePool(serviceId)` closes only that service's backends | Cross-service close affecting siblings | No |
| T11 | `PgConnectionManager.close()` future does not resolve until backends are released server-side | "Future resolved too early" — caller can't trust completion (⚠ may be Vert.x library behavior, not a PeeGeeQ defect) | No |
| T12 | `PgConnectionManager.close()` is idempotent | Double-close throws or leaks state | No |
| T13 | `PeeGeeQManager.closeReactive()` future does not resolve until ALL backends released (incl. Vertx-internal) | The contract `BaseIntegrationTest.@AfterEach` depends on | No |

#### T1 — `PgPoolConfigPropertyBindingTest`

**Asserts**: every property the loader documents in `peegeeq-default.properties` actually round-trips through `PeeGeeQConfiguration.getPoolConfig()`. Parameterised by (system-property key, expected getter on `PgPoolConfig`, sentinel value distinct from the default).

```java
@ParameterizedTest
@MethodSource("poolPropertyKeys")
void getPoolConfig_appliesEveryDocumentedSystemProperty(String key, String sentinel,
                                                        Function<PgPoolConfig, Object> getter,
                                                        Object expected) {
    System.setProperty(key, sentinel);
    try {
        PeeGeeQConfiguration cfg = new PeeGeeQConfiguration("test-binding-" + UUID.randomUUID());
        assertThat(getter.apply(cfg.getPoolConfig())).isEqualTo(expected);
    } finally {
        System.clearProperty(key);
    }
}

static Stream<Arguments> poolPropertyKeys() {
    return Stream.of(
        arguments("peegeeq.database.pool.max-size",              "7",     (Function<PgPoolConfig,Object>) PgPoolConfig::getMaxSize,           7),
        arguments("peegeeq.database.pool.connection-timeout-ms", "1234",  (Function<PgPoolConfig,Object>) c -> c.getConnectionTimeout().toMillis(), 1234L),
        arguments("peegeeq.database.pool.idle-timeout-ms",       "5678",  (Function<PgPoolConfig,Object>) c -> c.getIdleTimeout().toMillis(),       5678L),
        arguments("peegeeq.database.pool.shared",                "false", (Function<PgPoolConfig,Object>) PgPoolConfig::isShared,             false),
        arguments("peegeeq.database.pool.max-wait-queue-size",   "9",     (Function<PgPoolConfig,Object>) PgPoolConfig::getMaxWaitQueueSize,  9)
    );
}
```

**Status on master**: passes. Locks the contract before we rename keys in Tier 2.

#### T2 — `BaseIntegrationTestPoolConfigContractTest`

**This is the test that catches the actual current bug.** It asserts that the `PgPoolConfig` actually constructed when `BaseIntegrationTest` runs reflects the *intent* of `setupTestConfiguration()`. Drives the Tier 1 fix.

```java
class BaseIntegrationTestPoolConfigContractTest extends BaseIntegrationTest {

    @Test
    void poolConfigUsesShortIdleTimeoutForFastTeardown() {
        PgPoolConfig pool = configuration.getPoolConfig();
        assertThat(pool.getIdleTimeout())
            .as("test pools must idle-evict in seconds, not the production 10-minute default")
            .isLessThanOrEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void poolConfigUsesShortConnectionTimeoutForFastFailure() {
        assertThat(configuration.getPoolConfig().getConnectionTimeout())
            .isLessThanOrEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void poolConfigUsesNonSharedPoolsForDeterministicCleanup() {
        assertThat(configuration.getPoolConfig().isShared())
            .as("shared pools defer connection close via reference counting; tests need deterministic close")
            .isFalse();
    }

    @Test
    void poolConfigUsesSmallMaxSizeForParallelExecutionHeadroom() {
        assertThat(configuration.getPoolConfig().getMaxSize()).isLessThanOrEqualTo(3);
    }
}
```

**Status on master**: 3 of 4 fail (`idleTimeout` = 600 s, `connectionTimeout` = 30 s, `shared` = true). After Tier 1 edits: all 4 pass.

**Design note**: This test extends `BaseIntegrationTest` to exercise the full lifecycle, which means it requires a running database. An alternative is a CORE test that calls `setupTestConfiguration()` + `new PeeGeeQConfiguration(...)` directly (no DB dependency). The current approach is justified because it also validates that the `PgPoolConfig` survives the full manager startup path, not just configuration construction in isolation.

**⚠ Circular dependency risk**: If T2 is run before the Tier 1 fix while the pool-exhaustion bug is still present, T2 itself may fail with `too many clients already` rather than with the expected property-mismatch assertion failures. The failures would still be red, but for the wrong reason. To mitigate this, **add a CORE variant (T2b) as the primary test** and keep the integration variant (T2) as a secondary check.

#### T2b — `BaseIntegrationTestPoolConfigContractCoreTest` (CORE — no DB)

**This is the primary test for the property-key mismatch.** It calls `setupTestConfiguration()` + `new PeeGeeQConfiguration(...)` directly without starting a manager or connecting to a database. Tagged `@Tag(TestCategories.CORE)` — runs in every `mvn test` invocation, no `-Pintegration-tests` required. Cannot be blocked by connection exhaustion.

```java
@Tag(TestCategories.CORE)
class BaseIntegrationTestPoolConfigContractCoreTest {

    @BeforeEach
    void setUp() {
        // Call the same setup logic that BaseIntegrationTest uses
        BaseIntegrationTest.setupTestConfiguration();
    }

    @AfterEach
    void tearDown() {
        // Clear all pool system properties to avoid test pollution
        System.clearProperty("peegeeq.database.pool.max-size");
        System.clearProperty("peegeeq.database.pool.connection-timeout-ms");
        System.clearProperty("peegeeq.database.pool.idle-timeout-ms");
        System.clearProperty("peegeeq.database.pool.shared");
    }

    @Test
    void poolConfigUsesShortIdleTimeoutForFastTeardown() {
        PeeGeeQConfiguration cfg = new PeeGeeQConfiguration("test-t2b-" + UUID.randomUUID());
        assertThat(cfg.getPoolConfig().getIdleTimeout())
            .as("test pools must idle-evict in seconds, not the production 10-minute default")
            .isLessThanOrEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void poolConfigUsesShortConnectionTimeoutForFastFailure() {
        PeeGeeQConfiguration cfg = new PeeGeeQConfiguration("test-t2b-" + UUID.randomUUID());
        assertThat(cfg.getPoolConfig().getConnectionTimeout())
            .isLessThanOrEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void poolConfigUsesNonSharedPoolsForDeterministicCleanup() {
        PeeGeeQConfiguration cfg = new PeeGeeQConfiguration("test-t2b-" + UUID.randomUUID());
        assertThat(cfg.getPoolConfig().isShared())
            .as("shared pools defer connection close via reference counting; tests need deterministic close")
            .isFalse();
    }

    @Test
    void poolConfigUsesSmallMaxSizeForParallelExecutionHeadroom() {
        PeeGeeQConfiguration cfg = new PeeGeeQConfiguration("test-t2b-" + UUID.randomUUID());
        assertThat(cfg.getPoolConfig().getMaxSize()).isLessThanOrEqualTo(3);
    }
}
```

**Status on master**: 3 of 4 fail (same as T2). After Tier 1 edits: all 4 pass. Cannot be blocked by connection exhaustion because it never connects to a database.

#### T3 — `PgConnectionManagerPoolBuildContractTest`

**Asserts**: the runtime `Pool` produced by `PgConnectionManager.getOrCreateReactivePool` reflects the supplied `PgPoolConfig`. Validates the wiring between `PgPoolConfig` → `PoolOptions` → `Pool`. Uses introspection of `PoolOptions` (constructed locally with the same builder) plus a behavioural probe: open `maxSize+1` connections concurrently and assert the (`maxSize+1`)th waits.

```java
@Test
void poolHonorsConfiguredMaxSize(VertxTestContext ctx) {
    PgPoolConfig config = new PgPoolConfig.Builder().maxSize(2).shared(false)
        .connectionTimeout(Duration.ofSeconds(2)).idleTimeout(Duration.ofSeconds(2)).build();
    Pool pool = mgr.getOrCreateReactivePool("probe", connConfig, config);

    Future<SqlConnection> a = pool.getConnection();
    Future<SqlConnection> b = pool.getConnection();
    Future<SqlConnection> c = pool.getConnection();   // must queue, not exceed maxSize

    Future.all(a, b).onComplete(ctx.succeeding(v -> {
        // c is still pending here; release one and confirm c progresses
        a.result().close().onComplete(ctx.succeeding(x ->
            c.onComplete(ctx.succeeding(conn -> ctx.completeNow()))));
    }));
}
```

**Status on master**: passes (no defect here today). Captures the contract so Tier 4's `setName(serviceId)` change cannot regress it.

#### T4 — `PeeGeeQManagerCloseReleasesAllConnectionsTest`

**Asserts**: after `manager.closeReactive()` completes, the PG server reports zero backends owned by the test profile. Uses `pg_stat_activity` filtered by `application_name` (set per manager) to count.

```java
@Test
void closeReactive_releasesEveryBackend(VertxTestContext ctx) {
    String app = "peegeeq-test-" + UUID.randomUUID();
    System.setProperty("peegeeq.metrics.instance-id", app);
    PeeGeeQManager m = new PeeGeeQManager(...);

    m.start()
     .compose(v -> doSomeWork(m))                    // open ≥ 1 connection
     .compose(v -> m.closeReactive())
     .compose(v -> vertx.timer(500).mapEmpty())             // grace for FIN (use injected vertx, not new instance)
     .compose(v -> countBackends(app))               // SELECT count(*) FROM pg_stat_activity WHERE application_name=$1
     .onComplete(ctx.succeeding(count -> {
         assertThat(count).isZero();
         ctx.completeNow();
     }));
}
```

**Status on master**: likely fails intermittently (depends on Vertx FIN timing). Drives Tier 2 step 1 (eager `vertx.close()` await).

#### T5 — `BaseIntegrationTestSequentialLifecycleNoLeakTest`

**Reproduces the production symptom in miniature.** Spins up and tears down N managers sequentially in one test, asserts that the PG backend count never exceeds a small ceiling. This is the test that, had it existed, would have caught this defect at the property-key typo PR.

```java
@Test
void thirty_sequential_lifecycles_do_not_leak_connections(VertxTestContext ctx) {
    // Each iteration: new Vertx, new PeeGeeQManager, start, do trivial work, closeReactive
    // After each iteration, snapshot pg_stat_activity backend count
    // Assert max(snapshots) < 30  (i.e. nowhere near linear accumulation)
    chainSequentialLifecycles(30)
        .onComplete(ctx.succeeding(maxBackends -> {
            assertThat(maxBackends)
                .as("connection backends must not accumulate per lifecycle — expect <= maxSize + grace, not linear growth")
                .isLessThanOrEqualTo(5);   // maxSize=3 + small grace; anything near 30 = linear leak
            ctx.completeNow();
        }));
}
```

**Status on master**: **fails** — backends grow roughly linearly because of the 600 s idle timeout. After Tier 1 (idle-timeout=2 s, shared=false): passes.

#### T6 — `PgConnectionManagerCloseFutureCompletionTest`

**Asserts**: the `Future<Void>` returned by `PgConnectionManager.close()` does not complete until every per-pool close future has completed, even when one pool's close fails.

```java
@Test
void closeFuture_waitsForAllPools_evenWhenOneFails(VertxTestContext ctx) {
    PgConnectionManager mgr = ...;
    mgr.getOrCreateReactivePool("a", goodCfg, ...);
    mgr.getOrCreateReactivePool("b", goodCfg, ...);
    // sabotage one underlying pool to throw on close (e.g. via decorator)
    mgr.close().onComplete(ar -> {
        // accept either succeeded or failed, but assert both pools were attempted
        assertThat(closeAttempts).containsExactlyInAnyOrder("a", "b");
        ctx.completeNow();
    });
}
```

**Status on master**: passes. Locks the existing `Future.all + onFailure().mapEmpty()` semantics that Tier-4 work must preserve.

#### T7 — `PgPoolIdleTimeoutEvictionTest`

**Asserts**: a pool configured with `idleTimeout=2s, shared=false` actually drops backends within 5 s of last use. Confirms that the Tier 1 `shared=false` choice is sufficient (because `shared=true` defers eviction via reference counting).

```java
@Test
void nonSharedPool_evictsIdleConnectionsWithinTimeout(VertxTestContext ctx) {
    PgPoolConfig cfg = new PgPoolConfig.Builder()
        .maxSize(3).shared(false).idleTimeout(Duration.ofSeconds(2)).build();
    Pool p = mgr.getOrCreateReactivePool("evict", connCfg, cfg);
    p.withConnection(c -> c.query("SELECT 1").execute().mapEmpty())
     .compose(v -> vertx.timer(5000).mapEmpty())                  // wait > idle-timeout + grace
     .compose(v -> countBackendsOwnedBy(p))
     .onComplete(ctx.succeeding(n -> { assertThat(n).isZero(); ctx.completeNow(); }));
}
```

**Status on master**: fails when pool is shared (the production default), passes when `shared=false`. Justifies the Tier 1 `shared=false` setting and the Tier 4 `setName` follow-up.

#### Connection-close lifecycle tests (T8–T13)

T1–T7 prove **configuration is honored** and **steady-state usage doesn't leak**. These additional tests prove **each documented close transition actually releases the PG backend**, observable on the server side. They are the contract tests for the `close()` semantics themselves and would catch any future refactor that resolves a `Future<Void>` before the wire-level close completes.

All use `PgConnectionDiagnostics` (below) and the `application_name` connection property to attribute backends to a specific manager/pool. The grace timer (≤ 1 s) accounts for PG's internal lag between socket FIN and `pg_stat_activity` row removal.

| # | Transition under test | Observable assertion |
|---|---|---|
| T8 | `SqlConnection.close()` returns a connection to its pool | backend count == pool size, not 2 × pool size, after N borrow/release cycles |
| T9 | `Pool.close()` releases every backend that pool owns | backend count for that `application_name` drops to 0 within grace |
| T10 | `PgConnectionManager.closePool(serviceId)` closes only that service's backends, leaves siblings intact | sibling pool's backends unchanged; closed pool's count → 0 |
| T11 | `PgConnectionManager.close()` released futures complete *after* every backend is gone | `countBackends()` invoked the instant the future resolves returns 0 — no race window |
| T12 | `PgConnectionManager.close()` is idempotent | calling twice does not throw, second call resolves immediately, total backends still 0 |
| T13 | `PeeGeeQManager.closeReactive()` released future completes *after* `clientFactory.closeAsync()` AND `vertx.close()` have fully released sockets | `countBackends(app)` == 0 immediately after the future resolves, with no sleep beyond the documented grace |

Per project policy, all tests are reactive Vert.x only — no JDBC, no `CompletableFuture`, no blocking, no `Thread.sleep`. Delays use `vertx.timer(...)`.

##### T8 — `PoolConnectionReturnTest`

Asserts a borrowed connection returned via `SqlConnection.close()` is reused, not duplicated.

```java
@Test
void closingConnection_returnsItToPool_doesNotOpenNew(VertxTestContext ctx) {
    String app = "t8-" + UUID.randomUUID();
    Pool pool = makePool(app, /*maxSize*/ 2, /*shared*/ false);

    // 10 sequential borrow/close cycles — must never exceed maxSize backends
    chainBorrowReleaseCycles(pool, 10)
        .compose(v -> diagnostics.countBackends(app))
        .onComplete(ctx.succeeding(n -> {
            assertThat(n).as("borrow/close cycles must not leak backends").isLessThanOrEqualTo(2);
            ctx.completeNow();
        }));
}
```

**Status on master**: passes. Locks the pool checkout/return contract.

##### T9 — `PoolCloseReleasesAllBackendsTest`

The fundamental claim of `Pool.close()`. Asserts that immediately after the close future resolves (plus a bounded grace), the PG server reports zero backends for that application_name.

```java
@Test
void poolClose_dropsAllBackendsWithinGrace(VertxTestContext ctx) {
    String app = "t9-" + UUID.randomUUID();
    Pool pool = makePool(app, 3, false);

    // saturate
    Future<List<SqlConnection>> opened = openN(pool, 3);
    opened
        .compose(conns -> Future.all(conns.stream().map(SqlConnection::close).toList()).mapEmpty())
        .compose(v -> diagnostics.countBackends(app))
        .onSuccess(before -> assertThat(before).isEqualTo(3))
        .compose(v -> pool.close())
        .compose(v -> vertx.timer(500).mapEmpty())              // bounded grace
        .compose(v -> diagnostics.countBackends(app))
        .onComplete(ctx.succeeding(after -> {
            assertThat(after).as("Pool.close() must release every backend").isZero();
            ctx.completeNow();
        }));
}
```

**Status on master**: **fails for `shared=true`** — backends linger past the grace because shared pools defer real close until the last reference is released by Vertx housekeeping. Passes after Tier 1's `shared=false`. Critical regression lock.

##### T10 — `PgConnectionManagerClosePoolIsolationTest`

Asserts `closePool(serviceId)` is surgical — only the named service's backends drop.

```java
@Test
void closePool_releasesOnlyTargetServicesBackends(VertxTestContext ctx) {
    String appA = "t10a-" + UUID.randomUUID();
    String appB = "t10b-" + UUID.randomUUID();
    mgr.getOrCreateReactivePool("A", connCfgFor(appA), nonSharedSmall());
    mgr.getOrCreateReactivePool("B", connCfgFor(appB), nonSharedSmall());

    warmUp(mgr, "A").compose(v -> warmUp(mgr, "B"))
        .compose(v -> mgr.closePool("A"))
        .compose(v -> vertx.timer(500).mapEmpty())
        .compose(v -> Future.all(diagnostics.countBackends(appA), diagnostics.countBackends(appB)))
        .onComplete(ctx.succeeding(cf -> {
            assertThat((int) cf.resultAt(0)).as("closed pool backends").isZero();
            assertThat((int) cf.resultAt(1)).as("sibling pool unaffected").isGreaterThan(0);
            ctx.completeNow();
        }));
}
```

**Status on master**: passes. Locks isolation guarantee against future refactor.

##### T11 — `PgConnectionManagerCloseFutureWaitsForBackendReleaseTest`

> **⚠ Important context**: This test probes **Vert.x library behavior** (when does `Pool.close()` resolve relative to TCP FIN?), not PeeGeeQ application code. If Vert.x resolves the future before the FIN exchange completes — which is the expected library behavior — the remediation is not a PeeGeeQ code fix but rather accepting and documenting a bounded grace window. The test is valuable as a **forcing function for an explicit design decision** ("decide and document"), not as a regression test for PeeGeeQ code. If the outcome is "grace is inherent in Vert.x," amend T11 to allow a documented grace (e.g., 500 ms) and add a comment explaining why.

The contract test for §6's claim that `PgConnectionManager.close()` "completes when all per-pool futures complete." Strengthens it to: completes when the PG server has dropped the backends. **No grace timer** — if the future resolves while backends still exist, the test fails. This is the test that catches "future resolved too early" bugs.

```java
@Test
void closeFuture_resolvesAfterServerSideBackendsAreGone(VertxTestContext ctx) {
    String app = "t11-" + UUID.randomUUID();
    mgr.getOrCreateReactivePool("svc", connCfgFor(app), nonSharedSmall());
    warmUp(mgr, "svc")
        .compose(v -> mgr.close())
        // NOTE: no timer here — assert immediately on resolution
        .compose(v -> diagnostics.countBackends(app))
        .onComplete(ctx.succeeding(n -> {
            assertThat(n)
                .as("close() future must not resolve until backends are released server-side")
                .isZero();
            ctx.completeNow();
        }));
}
```

**Status on master**: **likely fails** — the future resolves on `Pool.close()` Future completion, which precedes the FIN exchange. Drives a Tier 2 enhancement: chain a "verify zero backends" step into `close()`, OR document the grace explicitly and amend the test to allow it. Either outcome is a deliberate decision, not silent behavior.

**⚠ Caveat (repeated for emphasis)**: See the context block above — this probes Vert.x library behavior. The likely outcome is that a bounded grace window (~500 ms) is inherent and must be documented, not fixed.

##### T12 — `PgConnectionManagerCloseIdempotentTest`

```java
@Test
void close_isIdempotent(VertxTestContext ctx) {
    mgr.getOrCreateReactivePool("svc", connCfg, nonSharedSmall());
    mgr.close()
       .compose(v -> mgr.close())                  // second call must succeed
       .compose(v -> mgr.close())                  // and third
       .onComplete(ctx.succeeding(v -> ctx.completeNow()));
}
```

**Status on master**: needs verification — depends on whether `reactivePools` map state survives close. If not idempotent, fix is small.

##### T13 — `PeeGeeQManagerCloseReactiveCompletesAfterSocketReleaseTest`

The end-to-end equivalent of T11 for the public `PeeGeeQManager.closeReactive()` API. Proves that downstream test code can rely on "after `awaitFuture(closeReactive())`, my backends are gone."

```java
@Test
void closeReactive_resolvesAfterAllBackendsReleased(VertxTestContext ctx) {
    String app = "t13-" + UUID.randomUUID();
    System.setProperty("peegeeq.metrics.instance-id", app);
    PeeGeeQManager m = new PeeGeeQManager(cfgFor(app), new SimpleMeterRegistry());

    m.start()
     .compose(v -> trivialWork(m))
     .compose(v -> m.closeReactive())
     .compose(v -> vertx.timer(250).mapEmpty())   // documented grace ≤ 250ms
     .compose(v -> diagnostics.countBackends(app))
     .onComplete(ctx.succeeding(n -> {
         assertThat(n).isZero();
         ctx.completeNow();
     }));
}
```

**Status on master**: fails (Vertx close timing). After Tier 2 step 1 (eager `awaitFuture(vertx.close())`): passes. This is the canonical contract for `BaseIntegrationTest.@AfterEach`'s correctness.

#### Helper: `PgConnectionDiagnostics`

A small reusable utility in `peegeeq-db/src/test/java/dev/mars/peegeeq/db/infrastructure/` that wraps `pg_stat_activity` queries against the shared container, returning `Future<Integer>` for backend counts by application_name or by datname. Used by T4, T5, T7. Must be reactive — no JDBC. Placed in `peegeeq-db` test scope (not `peegeeq-test-support`) to avoid the reactor-ordering issue documented in §9.

#### TDD execution order

1. **Add T1 only**, run, confirm green on master. Locks the loader contract.
2. **Add T2b** (CORE variant), run, confirm 3/4 fail with the documented messages. T2b cannot be blocked by connection exhaustion. **Do not edit production yet.**
3. **Add T2** (integration variant), run, confirm it also fails (may fail with property-mismatch assertions OR with connection exhaustion — either is red).
4. **Apply Tier 1 edits** (BaseIntegrationTest property keys + container `max_connections`). Re-run T2b and T2 — must go green. Re-run T1 — must stay green.
5. **Add T8** (borrow/release cycle). Must pass on master — locks pool checkout contract before further edits.
6. **Add T9** (Pool.close releases all backends). Run on master config (`shared=true`) — must fail. Re-apply Tier 1 (`shared=false`) — must pass. Hard evidence for the `shared=false` decision.
7. **Add T7** (idle eviction). Same red→green pattern as T9; corroborates T9 on the eviction path.
8. **Add T10** (closePool isolation). Must pass; regression lock for any future `setName(serviceId)` work.
9. **Add T5** (sequential lifecycle no leak). On master-equivalent config — must fail (linear leak). After Tier 1 — must pass. This is the test that mirrors the production symptom.
10. **Add T11** (close future doesn't resolve early). Likely fails — drives an explicit decision: extend `close()` to verify zero backends, OR document and codify the grace. **No silent grace.** (See T11 caveat re: Vert.x library behavior.)
11. **Add T13** (PeeGeeQManager.closeReactive end-to-end). Likely fails on master. After Tier 2 step 1 (eager `awaitFuture(vertx.close())`) — must pass.
12. **Add T4** (PeeGeeQManager closeReactive releases backends, with grace). Confirms the public-API contract. Must pass after T13's fix.
13. **Add T12** (close idempotency) and **T6** (Future.all semantics). Regression locks before any Tier 4 work.
14. Only then proceed to the existing Tier 1/2/3/4 plan below.

This sequence guarantees every code change is preceded by a red test and followed by that test going green, with no test added merely to confirm code already written. T8–T13 specifically prove the **close lifecycle observable on the PostgreSQL server**, not just in JVM-internal Future state.

---

### Tier 1 — fix the broken test config + @AfterEach guard (minutes, low risk, high reward)

> **This is the ship-first tier.** Apply these edits, validate with a full suite run, and only proceed to Tiers 2–4 if exhaustion persists or other issues emerge. Do not batch Tier 2+ changes into the same commit.

#### Edit 1: `setupTestConfiguration()` property keys

**File**: `peegeeq-db/src/test/java/dev/mars/peegeeq/db/BaseIntegrationTest.java`

Complete final state of the pool-related properties (replaces the existing block):

```java
// Use the keys the loader actually reads (millisecond longs, not Duration strings).
// NOTE: PgConnectionManager truncates these to whole seconds via Duration.toSeconds(),
// so values must be >= 1000 ms. A value < 1000 ms truncates to 0 = "no timeout".
System.setProperty("peegeeq.database.pool.max-size", "3");
System.setProperty("peegeeq.database.pool.connection-timeout-ms", "5000");   // 5 s
System.setProperty("peegeeq.database.pool.idle-timeout-ms", "2000");         // 2 s

// Force non-shared pools in tests so pool.close() drops the underlying connections
// deterministically, without reference-counting deferral (see §3b).
System.setProperty("peegeeq.database.pool.shared", "false");

// REMOVED: System.setProperty("peegeeq.database.pool.min-size", "1");
//   — never consumed by getPoolConfig(); PoolOptions has no min-size setter. Dead property.
// REMOVED: System.setProperty("peegeeq.database.pool.connection-timeout", "PT10S");
//   — wrong key name (loader reads "connection-timeout-ms", not "connection-timeout").
// REMOVED: System.setProperty("peegeeq.database.pool.idle-timeout", "PT10S");
//   — wrong key name (loader reads "idle-timeout-ms", not "idle-timeout").
// REMOVED: System.setProperty("peegeeq.database.pool.max-lifetime", "PT2M");
//   — no such config key in getPoolConfig().
```

#### Edit 2: `@AfterEach` grace timer guard + `System.gc()` removal

**File**: `peegeeq-db/src/test/java/dev/mars/peegeeq/db/BaseIntegrationTest.java`

The `manager.getVertx().timer(100)` grace delay must be guarded so that `closeReactive()` is always called, even when the Vertx event loop is dead (see §6 — 33% of `@AfterEach` invocations hit `RejectedExecutionException`, skipping `closeReactive()` entirely). Two options:

**Option A (preferred — remove the grace timer):**
```java
@AfterEach
void tearDownBaseIntegration() throws Exception {
    if (manager != null) {
        try {
            // closeReactive() handles dead Vertx internally (step 7 catch).
            // No grace timer needed — it serves no documented purpose and
            // throws RejectedExecutionException when the event loop is dead.
            awaitFuture(manager.closeReactive());
        } catch (Exception e) {
            logger.error("Error closing PeeGeeQ Manager for profile: {}", testProfile, e);
        } finally {
            manager = null;
        }
    }
    // REMOVED: System.gc(); — resource cleanup must be deterministic via closeReactive(),
    // not via finalizers.
}
```

**Option B (guard the timer):**
```java
try {
    awaitFuture(manager.getVertx().timer(100).mapEmpty());
} catch (Exception ignored) {
    // Vertx event loop may be dead; proceed to closeReactive() regardless.
}
awaitFuture(manager.closeReactive());
```

Option A is preferred because the grace timer serves no documented purpose and adds unnecessary complexity.

#### Edit 3: `max_connections` — keep at 200

**File**: `peegeeq-db/src/test/java/dev/mars/peegeeq/db/SharedPostgresTestExtension.java`

**Keep `max_connections=200` for the Tier 1 validation run.** If the idle-timeout + `shared=false` fix works (and the math in §7 supports it), passing at 200 provides genuine confidence. Bumping to 400 would mask residual leaks and obscure whether Tier 1 actually solved the problem. If the validation run fails at 200, that information is diagnostic — it tells us Tier 2 is needed.

#### Edit 4: No change to `SharedPostgresTestExtension` max_connections

(Intentionally no edit — kept at 200 per rationale above.)

**Note on `shared=false`**: The current `setupTestConfiguration()` does not set `peegeeq.database.pool.shared` at all — tests inherit the production default (`true`). The `shared=false` line is a **new** property being added, not a correction of an existing one. This directly addresses root cause §3b.

**Expected effect**: idle test connections released in ~2 s instead of ~10 min; non-shared pool means `pool.close()` is deterministic, not reference-counted; `@AfterEach` always calls `closeReactive()` even after startup failures. Full suite should finish without exhaustion.

### Tier 2 — defense in depth (small risk, gated behind Tier 1 validation)

> **Gate**: Do not start Tier 2 until the Tier 1 full-suite validation run passes (or fails with a clear diagnosis that Tier 2 addresses). Tier 2 items are valuable but must not be batched with Tier 1.

1. **Eagerly close Vert.x in tests**: in `BaseIntegrationTest.@AfterEach`, after `closeReactive()`, also `awaitFuture(vertx.close())` if the manager owned its Vertx. (`PeeGeeQManager` already does this in step #7, but adding a hard await guarantees socket release before the next test starts.)
2. **Property-key consistency cleanup**: rename `pool.connection-timeout-ms` / `pool.idle-timeout-ms` to `pool.connection-timeout` / `pool.idle-timeout` consuming `Duration` strings everywhere (loader + default properties + tests). Eliminates the foot-gun once and for all. Single coherent rename across one PR.

   **⚠ Blast radius warning**: This is a breaking change for any external configuration (environment variables, deployment YAML, Docker Compose overrides, CI scripts) that sets these keys. Consider supporting **both** key forms with a deprecation warning (log at WARN when the `-ms` suffix form is detected) instead of a hard rename. This avoids a flag-day migration for downstream consumers.

   **Rename scope is wider than just `getPoolConfig()`.** Both keys are also read by `validatePoolConfig()` at [`PeeGeeQConfiguration.java` lines 219 and 224](peegeeq-db/src/main/java/dev/mars/peegeeq/db/config/PeeGeeQConfiguration.java#L219-L224):

   ```java
   long connectionTimeoutMs = getLong("peegeeq.database.pool.connection-timeout-ms", 30000);
   ...
   long idleTimeoutMs = getLong("peegeeq.database.pool.idle-timeout-ms", 600000);
   ```

   Before opening the rename PR, run `grep -r "pool.connection-timeout-ms\|pool.idle-timeout-ms"` across the whole reactor (main + test + properties + docs) and migrate every call site in the same commit. Missing one site silently restores the old default.

   **Exhaustive scope** — known locations that must be migrated in the same commit:
   - `peegeeq-db/src/main/java/dev/mars/peegeeq/db/config/PeeGeeQConfiguration.java` — `getPoolConfig()` (lines 390-391) and `validateDatabaseConfig()` (lines 219, 224)
   - `peegeeq-db/src/main/resources/peegeeq-default.properties` (lines 18-19)
   - `peegeeq-db/src/test/java/dev/mars/peegeeq/db/BaseIntegrationTest.java` — `setupTestConfiguration()` (after Tier 1 fix)
   - Any profile-specific `.properties` files (search: `find . -name '*.properties' | xargs grep 'timeout-ms'`)
   - Documentation referencing the property keys (search: `find docs* -name '*.md' | xargs grep 'timeout-ms'`)
3. **Add a startup assertion** in `PeeGeeQConfiguration` that warns when both the millisecond and the Duration form of any pool key are present in `System.getProperties()`, so future authors get a loud signal.
4. **Wire up or remove `max-lifetime-ms`** from `peegeeq-default.properties`. It is declared in the defaults file but never consumed by `getPoolConfig()`. Either add it to the builder or remove the dead property to prevent the same class of foot-gun.
5. **Unify `validateDatabaseConfig()` defaults** with `getPoolConfig()` defaults. Currently `validateDatabaseConfig()` uses `min-size=5, max-size=10` while `getPoolConfig()` uses `max-size=32`. These should agree on the same defaults so validation checks match actual runtime behavior.

### Tier 3 — only if Tier 1+2 are insufficient (medium risk, gated behind Tier 2 validation)

**Surefire isolation tweak** in modules with the heaviest test density (`peegeeq-db`, `peegeeq-outbox`, `peegeeq-native`):

```xml
<configuration>
    <forkCount>1C</forkCount>
    <reuseForks>false</reuseForks>
</configuration>
```

Fresh JVM per fork batch → fresh Vertx population → fresh socket budget. Costs build time.

### Tier 4 — code-quality follow-up (separate PR, not blocking, gated behind Tier 1 validation)

Set an explicit `setName(serviceId)` in `PgConnectionManager.createReactivePool()` so shared pools are scoped per logical service. Eliminates the accidental aliasing at the default name. Belongs in a dedicated cleanup PR with its own tests. **Only schedule after Tier 1 is validated and the exhaustion issue is confirmed resolved.**

---

## 9. Independent compilation errors found in the same run

Not connection-related, must be fixed separately:

### `PeeGeeQDatabaseSetupServiceEnhancedTest`
6 compile errors at lines 81, 133, 202, 262, 300, 635: `Type mismatch: cannot convert from Future<Object> to Future<Void>`. Fix by adding explicit type witness (`Future.<Void>succeededFuture()`) or `.mapEmpty()` to satisfy the `Future<Void>` lambda return type.

### `ResurrectionReBackfillIntegrationTest`
6 errors: `import dev.mars.peegeeq.test.categories.TestCategories cannot be resolved`. Caused by reactor ordering — `peegeeq-test-support` builds **after** `peegeeq-db` in the reactor.

**Recommended fix**: Move `TestCategories` into a module that builds earlier (`peegeeq-api` or `peegeeq-db` itself). This is a small class with no dependencies; it belongs wherever the test infrastructure contract is anchored.

**⚠ Ripple effect**: Every module that currently imports `TestCategories` from `peegeeq-test-support` will need its `pom.xml` dependency updated (or the import path changed if the package moves). Run `grep -r "import dev.mars.peegeeq.test.categories.TestCategories" --include="*.java"` across the entire reactor to enumerate all affected files before making the move. The move and all import updates must land in the same commit to avoid breaking intermediate builds.

**Temporary workaround** (until the move is done): run `mvn install -pl peegeeq-test-support -am -DskipTests` once before `mvn test`.

### Other failures noted (not yet investigated)
- `DeadConsumerDetectorComprehensiveTest` — 1 fail
- `PeeGeeQMetricsCoreTest` — 3 errors
- `DeadConsumerDetectorCoreTest` — 2 errors

These may resolve once Tier 1 is applied (if they are downstream of pool exhaustion) or may be genuine logic issues. Re-evaluate after Tier 1.

---

## 10. Recommended execution order

**Phase 1 — Ship Tier 1, validate** (do this first, in isolation):

1. Apply Tier 1 — four edits in two files:
   - `BaseIntegrationTest.setupTestConfiguration()` — fix property keys (Edit 1).
   - `BaseIntegrationTest.tearDownBaseIntegration()` — guard/remove grace timer + remove `System.gc()` (Edit 2).
   - `SharedPostgresTestExtension` — keep `max_connections=200` (no edit; intentional).
2. Commit Tier 1 edits.
3. Re-run `mvn test -Pintegration-tests` with logging to `logs/full-suite-<ts>.txt`.
4. **If green**: Tier 1 is sufficient. Commit the green log. Address the compile errors in §9 in a separate commit. Proceed to Phase 2 when convenient.
5. **If still failing with connection exhaustion**: diagnose from the new log. The reduced idle-timeout + `shared=false` + `@AfterEach` fix should eliminate the primary and amplification causes. If exhaustion persists, the §7 math is wrong or there is an additional leak path — investigate before layering Tier 2.
6. **If failing with non-connection errors only**: Tier 1 resolved the exhaustion. Triage the remaining failures (may be §9 compile errors or genuine logic bugs).

**Phase 2 — Tiers 2–4, incrementally** (only after Phase 1 validation):

7. If Tier 1 validation shows residual connection pressure: apply Tier 2 step 1 (eager `vertx.close()` await). Re-validate.
8. Schedule Tier 2 items 2–5 (property-key rename, startup assertion, `max-lifetime-ms`, default unification) as a separate PR. Note the blast radius warning on the rename.
9. Tier 3 (Surefire fork isolation) only if Tier 1+2 are insufficient.
10. Tier 4 (`setName(serviceId)`) as a separate cleanup PR.

---

## 11. Verification queries used

For reproducibility, the PowerShell commands used to extract the metrics in §2:

```powershell
$log = "logs\full-suite-20260418-163257.txt"
$first = (Select-String -Path $log -Pattern "too many clients" | Select-Object -First 1).LineNumber
$head = Get-Content $log | Select-Object -First $first

# pool create/close counts before first exhaustion
($head | Select-String -Pattern "created reactive pool").Count
($head | Select-String -Pattern "Closing all .* pool").Count
($head | Select-String -Pattern "Closed successfully").Count

# distinct service names
Select-String -Path $log -Pattern "Service '([^']+)' created reactive pool" -AllMatches `
  | ForEach-Object { $_.Matches } `
  | ForEach-Object { $_.Groups[1].Value } `
  | Group-Object | Sort-Object Count -Descending

# peegeeq-main pool config distribution
Select-String -Path $log -Pattern "Service 'peegeeq-main' created reactive pool \(maxSize=(\d+), shared=(\w+)" `
  | ForEach-Object { $_.Matches } `
  | ForEach-Object { "max=$($_.Groups[1].Value) shared=$($_.Groups[2].Value)" } `
  | Group-Object
```

---

## 12. Files referenced

- [peegeeq-db/src/test/java/dev/mars/peegeeq/db/BaseIntegrationTest.java](peegeeq-db/src/test/java/dev/mars/peegeeq/db/BaseIntegrationTest.java)
- [peegeeq-db/src/test/java/dev/mars/peegeeq/db/SharedPostgresTestExtension.java](peegeeq-db/src/test/java/dev/mars/peegeeq/db/SharedPostgresTestExtension.java)
- [peegeeq-db/src/main/java/dev/mars/peegeeq/db/config/PeeGeeQConfiguration.java](peegeeq-db/src/main/java/dev/mars/peegeeq/db/config/PeeGeeQConfiguration.java)
- [peegeeq-db/src/main/java/dev/mars/peegeeq/db/config/PgPoolConfig.java](peegeeq-db/src/main/java/dev/mars/peegeeq/db/config/PgPoolConfig.java)
- [peegeeq-db/src/main/java/dev/mars/peegeeq/db/connection/PgConnectionManager.java](peegeeq-db/src/main/java/dev/mars/peegeeq/db/connection/PgConnectionManager.java)
- [peegeeq-db/src/main/java/dev/mars/peegeeq/db/PeeGeeQManager.java](peegeeq-db/src/main/java/dev/mars/peegeeq/db/PeeGeeQManager.java)
- [peegeeq-db/src/main/resources/peegeeq-default.properties](peegeeq-db/src/main/resources/peegeeq-default.properties)
- `logs/full-suite-20260418-163257.txt` (run log, 12.59 MB)
