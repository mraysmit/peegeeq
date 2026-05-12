# peegeeq-bitemporal Test Failure Analysis — 2026-05-12

**Run command:** `mvn test -Pall-tests -pl :peegeeq-bitemporal`  
**Log file:** `logs/peegeeq-bitemporal-all-20260512.txt`  
**Result:** Tests run: 360, Failures: 16, Errors: 35, Skipped: 0 — BUILD FAILURE

---

## Summary

All 51 failures fall into two independent root causes.

---

## Root Cause 1 — Deprecated `PeeGeeQConfiguration` constructor ignores system properties (35 errors, 16 failures)

### What happens

The failing tests follow this pattern:

```java
// Step 1: write Testcontainers coords into JVM system properties
setTestProperty("peegeeq.database.host", postgres.getHost());
setTestProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
setTestProperty("peegeeq.database.name",  postgres.getDatabaseName());
setTestProperty("peegeeq.database.username", postgres.getUsername());
setTestProperty("peegeeq.database.password", postgres.getPassword());

// Step 2: construct configuration — but the deprecated constructors no longer sweep
//         system properties, so the container coords are silently ignored
manager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
```

`PeeGeeQConfiguration.loadProperties()` loads the resource file defaults and then applies
environment variables. It **no longer reads system properties** (the sweep was removed; both
single-arg and no-arg constructors are `@Deprecated(since="2.0", forRemoval=true)`).

The resource file default for `peegeeq.database.host` is `localhost` and for
`peegeeq.database.port` is `5432`. No local PostgreSQL is running during the test run, so every
manager start fails with:

```
Database connectivity validation failed: Connection refused: no further information: localhost/127.0.0.1:5432
java.lang.RuntimeException: Database startup validation failed
→ java.lang.RuntimeException: Failed to start PeeGeeQ Manager
```

### Fix required

Replace the deprecated constructor pattern with `PeeGeeQConfiguration(String profile, Properties overrides)`
using `PeeGeeQTestConfig.builder().from(postgres).build()` — the same pattern already used by the
passing `VertxPerformanceOptimizationValidationTest`:

```java
// Correct pattern
Properties testProps = PeeGeeQTestConfig.builder()
        .from(postgres)
        .property("peegeeq.health-check.queue-checks-enabled", "false")
        .build();
PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
```

The `setTestProperty` / `restoreTestProperties` bookkeeping can be removed from each test once
the migration is complete.

### Affected test classes

| Test class | Deprecated constructor call(s) | Notes |
|---|---|---|
| `AppendBatchIntegrationTest` | line 150: `new PeeGeeQConfiguration()` | Also sets system props beforehand |
| `BiTemporalPerformanceParityTest` | line 86: `new PeeGeeQConfiguration()` | |
| `DatabaseWorkerVerticleTest` | line 78: `new PeeGeeQConfiguration()` | |
| `ReactiveNotificationTest` | line 141: `new PeeGeeQConfiguration()` | |
| `JsonbConversionValidationTest` | line 92: `new PeeGeeQConfiguration("jsonb-bitemporal-test")` | Profile `jsonb-bitemporal-test` resource file probably doesn't set a port either |
| `PgBiTemporalEventStoreCloseLogLevelTest` | lines 91, 171: `new PeeGeeQConfiguration("test")` | Called in `@BeforeEach` and in a second manager created inside a test |
| `PgBiTemporalEventStoreIntegrationTest` | line 228: `new PeeGeeQConfiguration("development")` | Per-test instantiation inside each `@Test` method |
| `PgBiTemporalEventStorePerformanceTest` | line 82–83: `new PeeGeeQConfiguration("development")` | |
| `MissingSchemaFailFastTest` | line 92: `new PeeGeeQConfiguration("development")` | Intentional negative-path test; still needs correct host/port so it can reach the empty container |
| `TransactionalBiTemporalExampleTest` | line 115: `new PeeGeeQConfiguration("development")` | `@BeforeAll` shared setup |
| `WildcardPatternComprehensiveTest` | line 82: `new PeeGeeQConfiguration("development")` | `@BeforeAll` shared setup |

---

## Root Cause 2 — `shouldValidateConfigurationProfiles` reads system properties that were never set (1 failure)

### What happens

`VertxPerformanceOptimizationValidationTest.shouldValidateConfigurationProfiles` (line 274) reads:

```java
String poolSize = System.getProperty("peegeeq.database.pool.max-size");  // → null
assertEquals("100", poolSize);  // → FAILURE: expected <100> but was <null>
```

The `setUp()` method correctly uses `PeeGeeQConfiguration("default", testProps)` and never
calls `System.setProperty()`. The properties exist only inside the `PeeGeeQConfiguration`
instance — they are never in the JVM system property table. `System.getProperty()` therefore
returns `null`.

### Fix required

The test is asserting that system properties were set as a side-effect of creating the
configuration object — an assumption that is both incorrect and incompatible with the
"no global system property pollution" design goal.

The assertion should be rewritten to inspect the `PeeGeeQConfiguration` instance directly
(via a getter or a helper that exposes the property), or removed if the configuration profile
validation is already covered by a unit test of `PeeGeeQConfiguration` itself.

---

## Passing tests confirmation

`VertxPerformanceOptimizationValidationTest` (all tests except `shouldValidateConfigurationProfiles`)
passes cleanly, confirming the correct constructor + `PeeGeeQTestConfig.builder()` pattern works.

---

## Action plan

1. Fix all 11 test classes listed under Root Cause 1: replace deprecated constructor calls
   with `PeeGeeQConfiguration("default", testProps)` + `PeeGeeQTestConfig.builder().from(postgres)`.
   Remove the `setTestProperty` / `restoreTestProperties` bookkeeping from each test.
2. Fix `VertxPerformanceOptimizationValidationTest.shouldValidateConfigurationProfiles` (Root Cause 2):
   remove or rewrite the `System.getProperty` assertions.
3. Re-run `mvn test -Pall-tests -pl :peegeeq-bitemporal` to verify clean pass.
