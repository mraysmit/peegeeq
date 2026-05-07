# Configuration Architecture Remediation: Replace Process-Wide Globals with Per-Instance Isolation

Created: 2026-05-07  
Branch: `feature/offset-watermark-phase1`  
Status: **PENDING**

---

## Executive Summary

### Root defect

`PeeGeeQConfiguration` sources its values from process-wide globals — JVM System properties and OS
environment variables. There is one copy of each per JVM. PeeGeeQ is a multi-tenant system where
multiple isolated instances with different databases, schemas, and credentials are required to
coexist in one JVM. **Process-wide globals cannot express per-instance configuration. The system
as it stands is unusable for multi-tenant scenarios.**

This is not a test-isolation problem that happens to also affect production. It is a fundamental
architectural correctness failure. The test failures are the most visible symptom, but the same
defect means that any production deployment with more than one tenant or Spring context in a
single JVM is silently misconfigured with no exception, no log warning, and no compile-time
detection.

### Immediate symptom

The 2026-05-07 full test run produced a flaky failure:

```
CleanupServiceIntegrationTest>BaseIntegrationTest.setUpBaseIntegration:95
IllegalStateException: Configuration validation failed:
  Maximum pool size must be greater than or equal to minimum pool size
```

This is one manifestation. The defect can produce silent misconfiguration or failures anywhere
in the suite on any run, and in production under concurrent tenant initialisation.

### Four problems, one root cause

| Problem | What | Scope | Execution order |
|---|---|---|---|
| **A** | Production code reads directly from System, bypassing `PeeGeeQConfiguration` | 3 classes | Phase 0 — fix first |
| **B** | Test code writes to System to pass config into `PeeGeeQConfiguration` | ~188 files, 8 modules | Phases 1–9 — fix after A |
| **C** | `loadProperties()` sweeps process-wide globals on every construction — **root architectural defect** | `PeeGeeQConfiguration` itself | Phases 11–12 — fix last |
| **D** | Spring bridge writes `peegeeq.*` to System at runtime — production race | `BiTemporalTxConfig` | Phase 10 — fix with B |

Note on Problem C ordering: it is the root cause and the most architecturally important fix, but it must be executed last — after all call sites are migrated — because removing the System sweep breaks any caller still using the zero-arg constructor to pick up System-set values. Phases 1–10 eliminate every such caller first.

Problems A, B, and D are symptoms of Problem C. Fixing only the write side (test code) while
leaving the System sweep in `loadProperties()` means the architectural defect remains and any
future caller can reintroduce contamination without warning. The order in the implementation
plan below is mandatory.

---

## Problem A — Production code reads from System, bypassing PeeGeeQConfiguration

Three production classes read `peegeeq.*` properties directly from `System.getProperty`, bypassing
the `PeeGeeQConfiguration` instance entirely. Each must be fixed before test-side migration
(Problem B) can be done safely; otherwise removing the matching `System.setProperty` calls from
tests silently discards the test values and the production code falls through to hardcoded defaults.

---

### A1 — `PgBiTemporalEventStore` (peegeeq-bitemporal)

`PgBiTemporalEventStore` holds a `peeGeeQManager` field with a fully populated
`PeeGeeQConfiguration`. It bypasses it for five tuning properties:

| Line  | Property key | Default |
|-------|---|---|
| 463   | `peegeeq.database.use.event.bus.distribution` | `"false"` |
| 1530  | `peegeeq.database.pipelining.limit`           | `"1024"` |
| 1562  | `peegeeq.database.pool.wait-queue-multiplier` | `"10"` |
| 1576  | `peegeeq.database.event.loop.size`            | `"0"` |
| 1644  | `peegeeq.database.pool.max-size`              | `null` (falls through to PeeGeeQManager config) |

**Fix:** Replace each `System.getProperty(...)` call with
`peeGeeQManager.getConfiguration().getInt(key, default)` / `.getBoolean(key, default)` /
`.getString(key, default)`. The generic getters already exist on `PeeGeeQConfiguration`.
Add the five property keys to `peegeeq-default.properties` with the same defaults currently
hardcoded in the `System.getProperty(key, defaultString)` calls.

---

### A2 — `VertxPerformanceOptimizer` (peegeeq-db)

`VertxPerformanceOptimizer` is a static utility class with no `PeeGeeQConfiguration` field.
Five properties are read from System in four private static methods:

| Line | Method | Property key | Default |
|------|---|---|---|
| 121  | `getOptimalEventLoopSize()`     | `peegeeq.database.event.loop.size`    | `processors * 2` |
| 135  | `getOptimalWorkerPoolSize()`    | `peegeeq.database.worker.pool.size`   | `processors * 4` |
| 149  | `getOptimalVerticleInstances()` | `peegeeq.verticle.instances`          | `processors` |
| 163  | `getPipeliningLimit()`          | `peegeeq.database.pipelining.enabled` | `"true"` |
| 169  | `getPipeliningLimit()`          | `peegeeq.database.pipelining.limit`   | `"32"` |

The public methods that call these (`createOptimizedVertx()`, `createOptimizedPool()`,
`createOptimizedDeploymentOptions()`) already receive `PgConnectionConfig` and `PgPoolConfig`
parameters but not a `PeeGeeQConfiguration`.

**Fix:** Add an optional `PeeGeeQConfiguration` parameter (or a thin overload) to the three
public methods. Thread the configuration down to the private helpers. When a configuration is
supplied, read from it; when null, fall back to the current `Runtime.getRuntime().availableProcessors()`
defaults. This preserves backward compatibility for callers that do not have a configuration
instance (e.g. performance test setup code that runs before the manager starts).

All five property keys must also be added to `peegeeq-default.properties`.

---

### A3 — `SystemInfoCollector` (peegeeq-db)

`SystemInfoCollector` is a static diagnostics/reporting utility that collects a system snapshot
for performance reports. It reads three `peegeeq.*` properties from System and also sweeps
`System.getProperties()` for all `peegeeq.*` keys in `collectPeeGeeQConfiguration()`:

| Line | Property key | Context |
|------|---|---|
| 190  | `peegeeq.database.url`                         | DB connection reporting |
| 216  | `peegeeq.database.pool.max-size`               | Pool reporting |
| 217  | `peegeeq.database.pool.wait-queue-multiplier`  | Pool reporting |
| 218  | `peegeeq.database.pipelining.limit`            | Pool reporting |

The `collectPeeGeeQConfiguration()` method exists specifically to show the operator what peegeeq
properties are currently in effect — this is a diagnostic purpose, not a configuration-reading
purpose. However the values it reports are wrong after test migration: they will show System
defaults rather than the actual values in the `PeeGeeQConfiguration` instance.

**Fix:** Add an optional `PeeGeeQConfiguration` parameter to `collectSystemInfo()` and the
private helpers. When supplied, read `peegeeq.*` values from the configuration object. When null
(e.g. outside a manager context), fall back to the current System property sweep. This is a
low-priority change that affects report accuracy but not correctness of the system under test.

---

### A3 — `SystemInfoCollector` (peegeeq-db) — low priority

`SystemInfoCollector` is a static diagnostics utility. Fix scheduled in Phase 0c below.

---

### Not a bypass — `PerformanceTestConfig` (peegeeq-performance-test-harness)

`PerformanceTestConfig` reads six `peegeeq.performance.*` properties from System (lines 220–235).
These are purpose-built CLI overrides for the performance test runner (`-Dpeegeeq.performance.suite=outbox`).
System properties are the correct mechanism for command-line parameterisation of a test runner.
This class does not need to be changed.

---

## Problem B — Test code writes to System.setProperty to configure PeeGeeQConfiguration

**Scope: ~188 files, 8 modules**

| Module | Affected files |
|---|---|
| `peegeeq-outbox` | 60 |
| `peegeeq-bitemporal` | 30 |
| `peegeeq-examples` | 29 |
| `peegeeq-native` | 39 |
| `peegeeq-db` | 23 |
| `peegeeq-rest` | 3 |
| `peegeeq-test-support` (test helpers) | 3 |
| `peegeeq-performance-test-harness` | 1 |

The pattern across all modules is the same: a `@BeforeEach` method calls
`System.setProperty("peegeeq.database.host", ...)` (and 5–15 other properties), constructs a
`PeeGeeQConfiguration` using the zero-arg or single-profile constructor, then a `@AfterEach`
method calls `System.clearProperty(...)` for each key. Under parallel execution any other thread
can observe partially-written or partially-cleared state during this window.

**The standard fix** (already mandated in `PEEGEEQ_TESTING_STANDARDS.md` § "System Property
Configuration"):

```java
Properties testProps = PeeGeeQTestConfig.builder()
    .from(postgres)                                             // host, port, db, user, password
    .schema("public")                                           // optional
    .property("peegeeq.database.pool.min-size", "1")
    .property("peegeeq.database.pool.max-size", "3")
    .property("peegeeq.migration.enabled", "false")
    .build();
PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
```

The two-arg `PeeGeeQConfiguration(String profile, Properties overrides)` constructor loads the
profile defaults first, then applies `overrides` on top. Any property present in `overrides`
dominates regardless of what `System.getProperties()` contains — eliminating the race window for
that property. No `@BeforeEach`/`@AfterEach` save/restore boilerplate is needed.

The reference implementation is `ResourceLeakDetectionTest` in `peegeeq-db`.

**Important (migration window — Phases 1–10):** The two-arg constructor's internal
`loadProperties()` still sweeps `System.getProperties()` for `peegeeq.*` keys as a first pass
during this period (see Problem C). Because `overrides` are applied after, any property
explicitly supplied in `overrides` wins. Tests must supply all test-relevant properties in
`overrides` — not rely on System for any of them. Phase 11 removes the sweep entirely,
eliminating this residual contamination path.

### Highest-leverage changes in this module group

#### peegeeq-db: BaseIntegrationTest

`BaseIntegrationTest` is the abstract base for **all** `peegeeq-db` integration tests. A single
change here covers every subclass.

Current `setUpBaseIntegration()` calls `setupTestConfiguration()` which calls ~15
`System.setProperty` calls and then constructs `PeeGeeQConfiguration` via the 7-arg explicit
constructor (host, port, db, user, pass, schema only — **pool properties omitted**). Pool
properties therefore still come from System, which is the direct cause of the observed race.

Replace the private `setupTestConfiguration()` and the dead `setupDatabaseProperties()` methods
with a single `PeeGeeQTestConfig.builder()` call in `setUpBaseIntegration()`, passing all
required properties — including pool settings — to the 2-arg constructor.

#### peegeeq-test-support: shared base helpers

`PeeGeeQTestBase.java` and `ParameterizedPerformanceTestBase.java` write `test.database.*`
properties (not `peegeeq.*`) to System for JDBC/Spring integration. These are in a separate
namespace that `PeeGeeQConfiguration.loadProperties()` does not sweep, so they do not create
`PeeGeeQConfiguration` races. Leave them as-is unless a separate migration is planned.

### Tests that legitimately test System property reading

The following must keep their `System.setProperty` calls because they are specifically testing that
`PeeGeeQConfiguration` correctly reads from System properties. They must not be migrated:

- `PgPoolConfigPropertyBindingTest.java` — parameterised test verifying `PeeGeeQConfiguration`
  picks up `peegeeq.*` System properties. Already has `finally { System.clearProperty(key) }`.
  **Only fix needed:** add `@ResourceLock("system-properties")` to serialise against concurrent
  holders of that lock.
- `PeeGeeQConfigurationTest.java` — unit test for `PeeGeeQConfiguration` itself; exercises the
  System property reading path intentionally.
- `SystemPropertiesConfigurationDemoTest.java` / `SystemPropertiesConfigurationExampleTest.java`
  — demonstration tests for the System property configuration path. Review individually; most
  likely require `@ResourceLock("system-properties")` rather than migration.
- `ConfigurationValidationTest.java` (in `peegeeq-examples`) — validates configuration wiring;
  some assertions call `System.getProperty` to verify what was written. Review individually.

---

## Problem C — loadProperties() uses process-wide globals: wrong design for multi-tenancy

`PeeGeeQConfiguration.loadProperties()` (called from all constructors, including the 2-arg
override constructor) ends with:

```java
System.getProperties().forEach((key, value) -> {
    String keyStr = key.toString();
    if (keyStr.startsWith("peegeeq.")) {
        props.setProperty(keyStr, value.toString());
    }
});
```

And earlier it sweeps env vars:

```java
System.getenv().forEach((key, value) -> {
    if (key.startsWith("PEEGEEQ_")) { ... }
});
```

### Why this is an architectural design defect for PeeGeeQ

Both System properties and env vars are **process-wide globals**. There is one copy per JVM.
PeeGeeQ is a multi-tenant system where the architecture explicitly requires multiple isolated
instances to coexist in one JVM — different schemas, different databases, different credentials.
Process-wide globals cannot express that.

| Source | Mutable at runtime | Per-instance | Correct for multi-tenant |
|---|---|---|---|
| `System.setProperty` | Yes | No | No — race condition |
| `System.getenv` | No | No | No — single value per process |
| `Properties overrides` (2-arg constructor) | No (caller-owned) | Yes | **Yes** |

The zero-arg and single-arg `PeeGeeQConfiguration()` constructors are **only correct for
single-tenant, single-instance deployments**. Any caller that constructs `PeeGeeQConfiguration`
using those constructors in a multi-tenant JVM will silently receive shared process globals
instead of tenant-specific values. There is no exception, no log warning, no compile-time
detection.

This is not a test-isolation issue. It is a fundamental correctness issue: the configuration
mechanism cannot support the isolation guarantees the system is designed to provide.

### What the correct model looks like

Each tenant/instance constructs `PeeGeeQConfiguration` using the 2-arg override constructor,
passing all required properties in an isolated `Properties` object sourced from the tenant's
own configuration (database, secrets manager, per-tenant config store):

```java
Properties tenantProps = new Properties();
tenantProps.setProperty("peegeeq.database.host", tenant.getDbHost());
tenantProps.setProperty("peegeeq.database.schema", tenant.getSchema());
// ... other tenant-specific properties
PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", tenantProps);
```

This is exactly what `PeeGeeQTestConfig.builder()` does. It is also the only correct pattern
for production multi-tenant use.

### Fix

1. **Remove the System property sweep from `loadProperties()`** entirely. The priority chain
   becomes: `programmatic overrides` → `PEEGEEQ_*` env vars → profile `.properties` → defaults.
   Env vars serve single-tenant deployments where one set of credentials applies to the whole
   process. Multi-tenant callers must use the 2-arg constructor.

2. **Deprecate and eventually remove the zero-arg and single-arg constructors.** They are
   meaningless in a multi-tenant context and misleading in a single-tenant context (they appear
   to work but silently rely on the process state being correct).

3. If `-Dpeegeeq.*` JVM arg support is required for local development convenience, handle it
   explicitly at the application entry point (`main()`), not inside `PeeGeeQConfiguration`:

   ```java
   Properties jvmOverrides = new Properties();
   System.getProperties().forEach((k, v) -> {
       if (k.toString().startsWith("peegeeq."))
           jvmOverrides.setProperty(k.toString(), v.toString());
   });
   new PeeGeeQConfiguration("prod", jvmOverrides);
   ```

   This restricts the System access to one intentional call at startup, at the application
   boundary, rather than inside the library on every construction.

**This is the highest-priority structural fix in the entire remediation.** Problems B and D are
symptoms. Problem C is the root architectural defect that makes them possible.

---

## Problem D — Spring integration bridge writes peegeeq.* to System

**File:** `peegeeq-examples-spring/src/main/java/.../BiTemporalTxConfig.java` (lines 216–220)

This Spring `@Configuration` class bridges Spring Boot properties into `PeeGeeQConfiguration` by
writing `peegeeq.database.*` keys to `System.setProperty`. This is production code, not test code.
It creates the same race window in any environment where multiple Spring contexts initialise
concurrently (e.g. Spring Boot test slices run in parallel).

**Root cause:** `PeeGeeQConfiguration` has no Spring-injectable factory or constructor that
accepts Spring `Environment` or `@ConfigurationProperties`. The bridge to System properties was
the only way to get Spring-sourced values in.

**Fix:** Use the 2-arg `PeeGeeQConfiguration(String profile, Properties overrides)` constructor.
Build the `Properties` object from the Spring `@ConfigurationProperties`-bound bean and pass it
directly. Remove the `System.setProperty` calls and all subsequent `System.clearProperty` cleanup.
This is a single-file change in `peegeeq-examples-spring`.

### Production contamination risk (not test-only)

The `loadProperties()` System sweep was originally added so operators could pass
`-Dpeegeeq.database.host=...` JVM args at startup. In a single-tenant deployment where those
values are set once before the JVM starts and never change, there is no race. But this is the
wrong design for a multi-tenant library (see Problem C): it only works by accident in the
single-tenant case, and breaks silently in the multi-tenant case.

`BiTemporalTxConfig` exposes this defect in a Spring context by writing `peegeeq.database.*` to
System at **runtime**, during Spring context initialisation. This creates a real production race
in two scenarios:

1. **Multi-tenant same JVM**: if two tenants' Spring contexts initialise concurrently, each
   running `BiTemporalTxConfig` with different database coordinates, the last writer to
   `System.setProperty` wins. A `PeeGeeQConfiguration` being constructed by the other tenant
   during that window inherits the wrong values silently — no exception, no log warning.

2. **Multi-context Spring application**: any Spring Boot application that loads two or more
   `ApplicationContext` instances in parallel (test slices, composite contexts, modular apps)
   faces the same race on the five shared System property keys.

`peegeeq-outbox` and `peegeeq-native` are not themselves contaminated at runtime once their
`PeeGeeQConfiguration` is constructed — each holds a private `Properties` object that is
never re-read from System after construction. The vulnerability is confined entirely to the
construction window. The fix (Problem D above) eliminates that window.

---

## Implementation Order (mandatory — do not reverse)

Each phase has a **change**, a **positive test** (must pass — confirms behaviour is preserved),
and a **negative test** (must return nothing — confirms the anti-pattern is gone).

---

### Phase 0a — Fix `PgBiTemporalEventStore` System.getProperty bypass [Problem A1]

**Change:** Replace all five `System.getProperty("peegeeq.*")` calls with
`peeGeeQManager.getConfiguration()` getter calls. Add the five keys to
`peegeeq-default.properties`.

**Positive test** — bitemporal and db modules behaviour unchanged (peegeeq-default.properties is in peegeeq-db):
```powershell
mvn test -pl peegeeq-db,peegeeq-bitemporal -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\phase0a.txt
Select-String "BUILD" logs\phase0a.txt   # must show SUCCESS
```

**Negative test** — no remaining bypass in production class:
```powershell
Select-String 'System\.getProperty' peegeeq-bitemporal\src\main\java\dev\mars\peegeeq\bitemporal\PgBiTemporalEventStore.java
# must return nothing
```

---

### Phase 0b — Fix `VertxPerformanceOptimizer` System.getProperty bypass [Problem A2]

**Change:** Add a `PeeGeeQConfiguration` parameter (or overload) to the three public static
methods. Thread it to the private helpers. Fall back to CPU-count defaults when null.

**Positive test** — peegeeq-db module behaviour unchanged:
```powershell
mvn test -pl peegeeq-db -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\phase0b.txt
Select-String "BUILD" logs\phase0b.txt   # must show SUCCESS
```

**Negative test** — no remaining bypass in production class:
```powershell
Select-String 'System\.getProperty' peegeeq-db\src\main\java\dev\mars\peegeeq\db\performance\VertxPerformanceOptimizer.java
# must return nothing
```

---

### Phase 0c — Fix `SystemInfoCollector` System.getProperty bypass [Problem A3]

**Change:** Add an optional `PeeGeeQConfiguration` parameter to `collectSystemInfo()` and the
private helpers `collectDatabaseConfiguration()` and `collectPeeGeeQConfiguration()`. When
supplied, read `peegeeq.*` values from the configuration object. When null, fall back to the
current System property sweep (acceptable for diagnostics invoked outside a manager context).

**Positive test** — peegeeq-db module behaviour unchanged:
```powershell
mvn test -pl peegeeq-db -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\phase0c.txt
Select-String "BUILD" logs\phase0c.txt   # must show SUCCESS
```

**Negative test** — primary `collectSystemInfo()` entry point no longer reads from System directly:
```powershell
Select-String 'System\.getProperty' peegeeq-db\src\main\java\dev\mars\peegeeq\db\performance\SystemInfoCollector.java
# must return nothing when a PeeGeeQConfiguration is supplied; fallback path may remain
```

---

### Phase 1 — `peegeeq-db` BaseIntegrationTest [Problem B]

**Change:** Replace `setupTestConfiguration()` / `setupDatabaseProperties()` in
`BaseIntegrationTest` with a single `PeeGeeQTestConfig.builder().from(container)...build()`
call. Pass result to the 2-arg `PeeGeeQConfiguration` constructor. Include all pool properties.

**Positive test** — all peegeeq-db integration tests pass:
```powershell
mvn test -pl peegeeq-db -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\phase1.txt
Select-String "BUILD" logs\phase1.txt   # must show SUCCESS
Select-String "Maximum pool size" logs\phase1.txt   # must return nothing
```

**Negative test** — no System.setProperty for `peegeeq.*` in BaseIntegrationTest:
```powershell
Select-String 'System\.(set|clear)Property' peegeeq-db\src\test\java\dev\mars\peegeeq\db\base\BaseIntegrationTest.java
# must return nothing
```

---

### Phase 2 — `peegeeq-db` remaining integration tests [Problem B]

**Change:** Migrate all other `peegeeq-db` integration test classes that are NOT
`PgPoolConfigPropertyBindingTest` or `PeeGeeQConfigurationTest`.

**Positive test:**
```powershell
mvn test -pl peegeeq-db -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\phase2.txt
Select-String "BUILD" logs\phase2.txt   # must show SUCCESS
```

**Negative test** — no System.setProperty for `peegeeq.*` in peegeeq-db test sources except
the permitted list:
```powershell
$permitted = @('PgPoolConfigPropertyBindingTest.java','PeeGeeQConfigurationTest.java',
               'SystemPropertiesConfigurationDemoTest.java','SystemPropertiesConfigurationExampleTest.java')
Get-ChildItem -Recurse -Filter *.java peegeeq-db\src\test |
    Select-String 'System\.(set|clear)Property.*"peegeeq\.' |
    Where-Object { $_.Filename -notin $permitted }
# must return nothing
```

---

### Phase 3 — `peegeeq-db` CORE (non-container) tests [Problem B]

**Change:** Migrate CORE unit tests that set `peegeeq.*` System properties. Most will simply
construct `PeeGeeQConfiguration` with explicit `Properties` rather than relying on System state.

**Positive test:**
```powershell
mvn test -pl peegeeq-db 2>&1 | Tee-Object -FilePath logs\phase3.txt
Select-String "BUILD" logs\phase3.txt   # must show SUCCESS
```

**Negative test:** same grep as Phase 2 — must still return nothing.

---

### Phase 4 — `peegeeq-db` System-property tests: add `@ResourceLock` [Problem B]

**Change:** Add `@ResourceLock("system-properties")` to `PgPoolConfigPropertyBindingTest` and
any other test that legitimately tests the System property reading path. Do NOT migrate these
tests — they are testing the current `loadProperties()` behaviour intentionally.

Note: after Phase 11 removes the System sweep, these tests will be testing behaviour that no
longer exists. They must be updated in Phase 11: either inverted to assert the sweep is absent,
or removed if they no longer test live behaviour.

**Positive test:**
```powershell
mvn test -pl peegeeq-db -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\phase4.txt
Select-String "PgPoolConfigPropertyBindingTest" logs\phase4.txt   # must show PASSED
```

**Negative test** — `@ResourceLock` annotation is present:
```powershell
Select-String 'ResourceLock' peegeeq-db\src\test\java\dev\mars\peegeeq\db\config\PgPoolConfigPropertyBindingTest.java
# must return a match
```

---

### Phase 5 — `peegeeq-bitemporal` tests [Problem B]

**Change:** Migrate all `peegeeq-bitemporal` test classes from System.setProperty to
`PeeGeeQTestConfig.builder()` + 2-arg constructor.

**Positive test:**
```powershell
mvn test -pl peegeeq-bitemporal -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\phase5.txt
Select-String "BUILD" logs\phase5.txt   # must show SUCCESS
```

**Negative test:**
```powershell
Get-ChildItem -Recurse -Filter *.java peegeeq-bitemporal\src\test |
    Select-String 'System\.(set|clear)Property.*"peegeeq\.'
# must return nothing
```

---

### Phase 6 — `peegeeq-outbox` tests [Problem B]

**Change:** Migrate all 60 affected test files in `peegeeq-outbox`.

**Positive test:**
```powershell
mvn test -pl peegeeq-outbox -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\phase6.txt
Select-String "BUILD" logs\phase6.txt   # must show SUCCESS
```

**Negative test:**
```powershell
Get-ChildItem -Recurse -Filter *.java peegeeq-outbox\src\test |
    Select-String 'System\.(set|clear)Property.*"peegeeq\.'
# must return nothing
```

---

### Phase 7 — `peegeeq-native` tests [Problem B]

**Change:** Migrate all 39 affected test files in `peegeeq-native`.

**Positive test:**
```powershell
mvn test -pl peegeeq-native -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\phase7.txt
Select-String "BUILD" logs\phase7.txt   # must show SUCCESS
```

**Negative test:**
```powershell
Get-ChildItem -Recurse -Filter *.java peegeeq-native\src\test |
    Select-String 'System\.(set|clear)Property.*"peegeeq\.'
# must return nothing
```

---

### Phase 8 — `peegeeq-examples` tests [Problem B]

**Change:** Migrate all 29 affected test files in `peegeeq-examples`.

**Positive test:**
```powershell
mvn test -pl peegeeq-examples -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\phase8.txt
Select-String "BUILD" logs\phase8.txt   # must show SUCCESS
```

**Negative test:**
```powershell
$permitted = @('ConfigurationValidationTest.java')   # review individually
Get-ChildItem -Recurse -Filter *.java peegeeq-examples\src\test |
    Select-String 'System\.(set|clear)Property.*"peegeeq\.' |
    Where-Object { $_.Filename -notin $permitted }
# must return nothing
```

---

### Phase 9 — `peegeeq-rest` tests [Problem B]

**Change:** Migrate the 3 affected test files in `peegeeq-rest`.

**Positive test:**
```powershell
mvn test -pl peegeeq-rest -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\phase9.txt
Select-String "BUILD" logs\phase9.txt   # must show SUCCESS
```

**Negative test:**
```powershell
Get-ChildItem -Recurse -Filter *.java peegeeq-rest\src\test |
    Select-String 'System\.(set|clear)Property.*"peegeeq\.'
# must return nothing
```

---

### Phase 10 — `peegeeq-examples-spring` BiTemporalTxConfig [Problem D]

**Change:** Replace the five `System.setProperty` calls in `BiTemporalTxConfig` with a
`Properties` object passed to the 2-arg `PeeGeeQConfiguration` constructor. Remove all
`System.clearProperty` cleanup.

**Positive test:**
```powershell
mvn test -pl peegeeq-examples-spring -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\phase10.txt
Select-String "BUILD" logs\phase10.txt   # must show SUCCESS
```

**Negative test** — no System.setProperty for `peegeeq.*` in production source:
```powershell
Get-ChildItem -Recurse -Filter *.java peegeeq-examples-spring\src\main |
    Select-String 'System\.(set|clear)Property.*"peegeeq\.'
# must return nothing
```

---

### Phase 11 — Remove System sweep from `loadProperties()` [Problem C] — MANDATORY

**Change:** Delete the `System.getProperties().forEach(...)` block from `loadProperties()`.
The priority chain becomes: programmatic overrides → `PEEGEEQ_*` env vars → profile
`.properties` → defaults. Update `PeeGeeQConfigurationTest` to assert that System properties
are **not** picked up (this test will need inverting for that assertion).

**Positive test** — full suite still passes; all tenant isolation is correct:
```powershell
mvn test -Pall-tests 2>&1 | Tee-Object -FilePath logs\phase11.txt
Select-String "BUILD" logs\phase11.txt   # must show SUCCESS
Select-String "Maximum pool size" logs\phase11.txt   # must return nothing
```

**Negative test** — no System sweep remains in loadProperties():
```powershell
Select-String 'System\.getProperties' peegeeq-db\src\main\java\dev\mars\peegeeq\db\config\PeeGeeQConfiguration.java
# must return nothing
```

**New test required:** Add a test to `PeeGeeQConfigurationTest` that sets a `peegeeq.*`
System property, constructs `PeeGeeQConfiguration()`, and asserts the System property value
is **not** reflected — confirming the sweep is gone and isolation is enforced.

---

### Phase 12 — Deprecate zero-arg and single-arg constructors [Problem C]

**Change:** Annotate `PeeGeeQConfiguration()` and `PeeGeeQConfiguration(String profile)` with
`@Deprecated(since = "2.0", forRemoval = true)`. Add Javadoc directing callers to the 2-arg
constructor with explicit `Properties`.

**Positive test** — suite compiles and passes (deprecation warnings are acceptable):
```powershell
mvn test -Pall-tests 2>&1 | Tee-Object -FilePath logs\phase12.txt
Select-String "BUILD" logs\phase12.txt   # must show SUCCESS
```

**Negative test** — no call sites in production code use the deprecated constructors:
```powershell
Get-ChildItem -Recurse -Filter *.java -Path *\src\main\* |
    Select-String -Pattern 'new PeeGeeQConfiguration\(\s*\)|new PeeGeeQConfiguration\(\s*"[^,"]+"\s*\)'
# must return nothing (all production callers use 2-arg or 7-arg constructor)
```

---

### Final gate — full suite clean

```powershell
mvn test -Pall-tests 2>&1 | Tee-Object -FilePath logs\sysprop-fix-final.txt
Select-String "Maximum pool size" logs\sysprop-fix-final.txt   # must return nothing
Select-String "BUILD" logs\sysprop-fix-final.txt               # must show SUCCESS
```

---

## Permitted System.setProperty callers (do not migrate)

These files legitimately test the System property reading path and must keep their
`System.setProperty` calls. All others are violations after the corresponding phase completes.

```
PgPoolConfigPropertyBindingTest.java         — tests PeeGeeQConfiguration reads System props
PeeGeeQConfigurationTest.java                — unit tests for PeeGeeQConfiguration itself
SystemPropertiesConfigurationDemoTest.java   — demonstration test for System prop path
SystemPropertiesConfigurationExampleTest.java — demonstration test for System prop path
```

After Phase 11 (System sweep removed), these tests must be updated to either:
- Assert that System properties are **not** picked up (inverting the current assertions), or
- Be reclassified as historical/removed if they no longer test live behaviour.
