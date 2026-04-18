# Test Suite Connection Exhaustion — Investigation & Remediation Plan

**Date**: 2026-04-18
**Branch**: `feature/offset-watermark-phase1`
**Trigger**: Full reactor test run (`mvn test -Pintegration-tests`) failed with 277 errors, all rooted in PostgreSQL `FATAL: sorry, too many clients already (SQLSTATE 53300)`.
**Run log**: `logs/full-suite-20260418-163257.txt` (12.59 MB)
**Status**: Investigation complete. Remediation **not yet applied** — awaiting instruction.

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
| `PgConnectionManager.close()` invoked before first exhaustion | 595 |
| `PgConnectionManager.close()` succeeded before first exhaustion | 551 |
| First exhaustion timestamp | `16:34:29` (~92 s into the run) |
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

---

## 3. Root cause — primary

**`BaseIntegrationTest.setupTestConfiguration()` sets pool tuning properties using keys that the configuration loader does not read. The conservative test config is silently ignored, and tests fall through to production defaults.**

### What the test sets

[`BaseIntegrationTest.java` lines 182-188](peegeeq-db/src/test/java/dev/mars/peegeeq/db/BaseIntegrationTest.java#L182-L188):

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

| Test sets | Loader reads | Effective value |
|---|---|---|
| `pool.connection-timeout` (Duration string `PT10S`) | `pool.connection-timeout-ms` (long ms) | **30 000 ms** (default) |
| `pool.idle-timeout` (Duration string `PT10S`) | `pool.idle-timeout-ms` (long ms) | **600 000 ms = 10 min** (default) |
| `pool.max-lifetime` (Duration string `PT2M`) | *(no such key in loader)* | **N/A — not applied** |
| `pool.max-size` ✓ | `pool.max-size` ✓ | 3 (works) |
| `pool.min-size` | *(loader ignores min-size; PoolOptions doesn't expose it)* | unused |

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

(`max-lifetime-ms` is in the file but not consumed by `getPoolConfig()`.)

### Consequence

Every `BaseIntegrationTest`-derived test creates a pool that holds idle connections on the PG server for **up to 10 minutes** after the test ends, even when `closeReactive()` runs cleanly. With ~9 manager lifecycles per second across forks, the 200-connection ceiling is consumed in ~22 s — far inside the 600 s idle window.

### Pool semantics implication

`Pool.close()` returning successfully does not guarantee the underlying TCP sockets have FIN-ed by the time the next test starts. With shared pools (`setShared(true)`) and idle-timeout=600 s, the Vert.x pool may retain connections via reference counting until idle eviction runs.

---

## 4. Root cause — secondary

**Each `PeeGeeQManager` owns its own `Vertx` instance. The asynchronous `vertx.close()` step in `closeReactive()` does not synchronously join Netty channel teardown.**

[`PeeGeeQManager.closeReactive()` step #7, lines 469-489](peegeeq-db/src/main/java/dev/mars/peegeeq/db/PeeGeeQManager.java#L469-L489):

```java
.eventually(() -> {
    if (vertx != null && vertxOwnedByManager) {
        return vertx.close()...
    }
    ...
})
```

`vertx.close()` returns a `Future<Void>` that completes when the event loops shut down, but PgClient's underlying Netty `EventLoopGroup` shutdown is asynchronous and the resolved `Future` does not wait for socket-close FIN exchange to complete on the wire. PostgreSQL still counts those connections as live until its TCP timeout / FIN handling completes, which can lag by hundreds of ms under load.

This compounds the primary cause: even if idle-timeout were 2 s, the per-test Vertx churn alone leaves a backlog of half-closed sockets in the PG `pg_stat_activity` view.

---

## 5. Root cause — tertiary

**`PgConnectionManager` calls `setShared(true)` on `PoolOptions` but never calls `setName(...)`.** Within any one Vertx, every shared pool aliases the default-name slot, so multiple service IDs created on the same Vertx instance fight over a single shared pool. Reference counting then governs when connections actually close.

[`PgConnectionManager.java` line 305](peegeeq-db/src/main/java/dev/mars/peegeeq/db/connection/PgConnectionManager.java#L305):

```java
PoolOptions poolOptions = new PoolOptions()
    .setMaxSize(poolConfig.getMaxSize())
    ...
    .setShared(poolConfig.isShared());     // shared=true
// no .setName(serviceId) call anywhere
```

This is mostly inert in the test scenario (each test gets its own Vertx) but is a foot-gun for any production caller that runs more than one logical PgConnectionManager on a single Vertx.

---

## 6. What is **not** broken

- `PeeGeeQManager.closeReactive()` cleanup chain — verified: every `.eventually()` step runs.
- `BaseIntegrationTest.@AfterEach` — verified: calls `awaitFuture(manager.closeReactive())` with try/finally, then nulls `manager`, then `System.gc()`.
- `PgConnectionManager.close()` — verified: snapshots service IDs, closes every pool via `Future.all`, completes when all per-pool futures complete.
- `SharedPostgresTestExtension` — verified: single container per JVM, `max_connections=200`, `withReuse(true)`.
- The `.recover()` → `.transform()` / `.onFailure().mapEmpty()` swap — verified semantically equivalent for the cleanup paths involved.

---

## 7. Math sanity check

- PG `max_connections` = 200
- Reserved by PG for superuser + autovacuum ≈ 5
- Effective ceiling ≈ 195
- BaseIntegrationTest pool `max-size` = 3
- Surefire parallelism: configurable, but at observed rate ≈ 9 manager lifecycles/sec aggregated across forks
- Idle-timeout (effective) = 600 s
- Steady-state connection accumulation: 9 × 3 × 600 = **16 200 connection-seconds in flight** in worst case

195 ÷ (9 × 3) ≈ 7.2 s of headroom before saturation. Observed time-to-first-exhaustion: ~92 s (better than worst case because some pools idle-evict early via internal heuristics, and many tests don't hold all 3 connections concurrently). Numbers reconcile.

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
| C8 | **`PgConnectionDiagnostics` location is hedged.** Currently says "(`peegeeq-test-support` or `peegeeq-db` test scope)". Principles say "Follow Established Conventions." | Pin to `peegeeq-test-support`, mindful of the existing reactor-ordering issue with `TestCategories` (see §9). If reactor ordering blocks, place in `peegeeq-db/src/test/java/dev/mars/peegeeq/db/infrastructure/` instead and document the choice. |
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
| T2 | `BaseIntegrationTest.setupTestConfiguration()` produces the `PgPoolConfig` it claims | Property-key typo in test setup (the actual current bug) | No |
| T3 | `PgConnectionManager` builds a `Pool` whose runtime parameters match the supplied `PgPoolConfig` | Builder/PoolOptions wiring drift | No |
| T4 | `PeeGeeQManager.closeReactive()` releases every PG backend it opened | Leaked sockets after clean shutdown | No |
| T5 | Sequential `BaseIntegrationTest` lifecycles do not accumulate PG backends | Cumulative connection leak across tests (the production symptom) | No |
| T6 | `PgConnectionManager.close()` is idempotent and Future completes only after every pool is fully closed | Premature completion masking incomplete teardown | No |
| T7 | `PgPoolConfig.idleTimeout` shorter than X actually evicts idle connections in ≤ X+grace | `setShared(true)` reference-counting holds connections past their idle timeout | No |
| T8 | `SqlConnection.close()` returns connections to the pool, doesn't open new backends | Pool leak per borrow/release cycle | No |
| T9 | `Pool.close()` releases every backend that pool owns within bounded grace | `Pool.close()` future resolves before sockets actually FIN (esp. shared pools) | No |
| T10 | `PgConnectionManager.closePool(serviceId)` closes only that service's backends | Cross-service close affecting siblings | No |
| T11 | `PgConnectionManager.close()` future does not resolve until backends are released server-side | "Future resolved too early" — caller can't trust completion | No |
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
     .compose(v -> Vertx.vertx().setTimer(500).mapEmpty())   // grace for FIN
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
            assertThat(maxBackends).as("connection backends must not accumulate per lifecycle").isLessThan(30);
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

A small reusable utility (`peegeeq-test-support` or `peegeeq-db` test scope) that wraps `pg_stat_activity` queries against the shared container, returning `Future<Integer>` for backend counts by application_name or by datname. Used by T4, T5, T7. Must be reactive — no JDBC.

#### TDD execution order

1. **Add T1 only**, run, confirm green on master. Locks the loader contract.
2. **Add T2**, run, confirm 3/4 fail with the documented messages. **Do not edit production yet.**
3. **Apply Tier 1 edits** (BaseIntegrationTest property keys + container `max_connections`). Re-run T2 — must go green. Re-run T1 — must stay green.
4. **Add T8** (borrow/release cycle). Must pass on master — locks pool checkout contract before further edits.
5. **Add T9** (Pool.close releases all backends). Run on master config (`shared=true`) — must fail. Re-apply Tier 1 (`shared=false`) — must pass. Hard evidence for the `shared=false` decision.
6. **Add T7** (idle eviction). Same red→green pattern as T9; corroborates T9 on the eviction path.
7. **Add T10** (closePool isolation). Must pass; regression lock for any future `setName(serviceId)` work.
8. **Add T5** (sequential lifecycle no leak). On master-equivalent config — must fail (linear leak). After Tier 1 — must pass. This is the test that mirrors the production symptom.
9. **Add T11** (close future doesn't resolve early). Likely fails — drives an explicit decision: extend `close()` to verify zero backends, OR document and codify the grace. **No silent grace.**
10. **Add T13** (PeeGeeQManager.closeReactive end-to-end). Likely fails on master. After Tier 2 step 1 (eager `awaitFuture(vertx.close())`) — must pass.
11. **Add T4** (PeeGeeQManager closeReactive releases backends, with grace). Confirms the public-API contract. Must pass after T13's fix.
12. **Add T12** (close idempotency) and **T6** (Future.all semantics). Regression locks before any Tier 4 work.
13. Only then proceed to the existing Tier 1/2/3/4 plan below.

This sequence guarantees every code change is preceded by a red test and followed by that test going green, with no test added merely to confirm code already written. T8–T13 specifically prove the **close lifecycle observable on the PostgreSQL server**, not just in JVM-internal Future state.

---

### Tier 1 — fix the broken test config (minutes, low risk, high reward)

**Edits in `peegeeq-db/src/test/java/dev/mars/peegeeq/db/BaseIntegrationTest.java` `setupTestConfiguration()`:**

```java
// Use the keys the loader actually reads (millisecond longs, not Duration strings)
System.setProperty("peegeeq.database.pool.min-size", "1");
System.setProperty("peegeeq.database.pool.max-size", "3");
System.setProperty("peegeeq.database.pool.connection-timeout-ms", "5000");
System.setProperty("peegeeq.database.pool.idle-timeout-ms", "2000");
// remove the bogus PT2M max-lifetime line — there is no such config key

// Force non-shared pools in tests so pool.close() drops the underlying connections
System.setProperty("peegeeq.database.pool.shared", "false");
```

**Edit in `peegeeq-db/src/test/java/dev/mars/peegeeq/db/SharedPostgresTestExtension.java` container command:**

```java
"-c", "max_connections=400",   // bumped from 200 to absorb worst-case race window
```

**Expected effect**: idle test connections released in ~2 s instead of ~10 min; non-shared pool means `pool.close()` is deterministic, not reference-counted. Full suite should finish without exhaustion.

### Tier 2 — defense in depth (small risk)

1. **Eagerly close Vert.x in tests**: in `BaseIntegrationTest.@AfterEach`, after `closeReactive()`, also `awaitFuture(vertx.close())` if the manager owned its Vertx. (`PeeGeeQManager` already does this in step #7, but adding a hard await guarantees socket release before the next test starts.)
2. **Property-key consistency cleanup**: rename `pool.connection-timeout-ms` / `pool.idle-timeout-ms` to `pool.connection-timeout` / `pool.idle-timeout` consuming `Duration` strings everywhere (loader + default properties + tests). Eliminates the foot-gun once and for all. Single coherent rename across one PR.
3. **Add a startup assertion** in `PeeGeeQConfiguration` that warns when both the millisecond and the Duration form of any pool key are present in `System.getProperties()`, so future authors get a loud signal.

### Tier 3 — only if Tier 1+2 are insufficient (medium risk)

**Surefire isolation tweak** in modules with the heaviest test density (`peegeeq-db`, `peegeeq-outbox`, `peegeeq-native`):

```xml
<configuration>
    <forkCount>1C</forkCount>
    <reuseForks>false</reuseForks>
</configuration>
```

Fresh JVM per fork batch → fresh Vertx population → fresh socket budget. Costs build time.

### Tier 4 — code-quality follow-up (separate PR, not blocking)

Set an explicit `setName(serviceId)` in `PgConnectionManager.createReactivePool()` so shared pools are scoped per logical service. Eliminates the accidental aliasing at the default name. Belongs in a dedicated cleanup PR with its own tests.

---

## 9. Independent compilation errors found in the same run

Not connection-related, must be fixed separately:

### `PeeGeeQDatabaseSetupServiceEnhancedTest`
6 compile errors at lines 81, 133, 202, 262, 300, 635: `Type mismatch: cannot convert from Future<Object> to Future<Void>`. Fix by adding explicit type witness (`Future.<Void>succeededFuture()`) or `.mapEmpty()` to satisfy the `Future<Void>` lambda return type.

### `ResurrectionReBackfillIntegrationTest`
6 errors: `import dev.mars.peegeeq.test.categories.TestCategories cannot be resolved`. Caused by reactor ordering — `peegeeq-test-support` builds **after** `peegeeq-db` in the reactor. Two options:

1. Bootstrap step: run `mvn install -pl peegeeq-test-support -am -DskipTests` once before `mvn test`.
2. Move `TestCategories` into a module that builds earlier (`peegeeq-api` or `peegeeq-db` itself).

### Other failures noted (not yet investigated)
- `DeadConsumerDetectorComprehensiveTest` — 1 fail
- `PeeGeeQMetricsCoreTest` — 3 errors
- `DeadConsumerDetectorCoreTest` — 2 errors

These may resolve once Tier 1 is applied (if they are downstream of pool exhaustion) or may be genuine logic issues. Re-evaluate after Tier 1.

---

## 10. Recommended execution order

1. Apply Tier 1 — three small file edits.
2. Re-run `mvn test -Pintegration-tests` with logging to `logs/full-suite-<ts>.txt`.
3. If green: address the compile errors in §9 in a separate commit.
4. If still failing: layer Tier 2.
5. If still failing: Tier 3.
6. Schedule Tier 4 cleanup as separate PR.

---

## 11. Verification queries used

For reproducibility, the PowerShell commands used to extract the metrics in §2:

```powershell
$log = "logs\full-suite-20260418-163257.txt"
$first = (Select-String -Path $log -Pattern "too many clients" | Select-Object -First 1).LineNumber
$head = Get-Content $log | Select-Object -First $first

# pool create/close counts before first exhaustion
($head | Select-String -Pattern "created reactive pool").Count
($head | Select-String -Pattern "Closing reactive pool for service").Count
($head | Select-String -Pattern "Closing all \d+ pool").Count
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
