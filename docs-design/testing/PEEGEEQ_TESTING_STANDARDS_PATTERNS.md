# PeeGeeQ Testing Standards

## Overview

This document establishes comprehensive testing standards for all PeeGeeQ development increments, ensuring thorough validation at every stage of implementation.

## Core Testing Principles

### 1. **Test at Every Stage**
- Compile after each code change
- Run unit tests after each logical unit of work
- Run integration tests after each increment completion
- Validate database state after each transaction-related change

### 2. **Comprehensive Coverage Requirements**

#### **Unit Tests**
- Parameter validation logic
- Method signature verification
- Edge case handling
- Error condition testing

#### **Integration Tests**
- End-to-end functionality with TestContainers
- Database state validation
- Transaction consistency verification
- Cross-table data integrity checks

#### **Database State Validation**
- **CRITICAL**: Always verify actual database state, not just API responses
- Query all relevant tables to confirm data persistence
- Validate referential integrity between related tables
- Check temporal data (valid_time vs transaction_time)

### 3. **Transaction Testing Standards**

For any increment involving database transactions:

#### **Required Test Scenarios**
1. **Happy Path Transaction Commit**
   - Business operation + event operation in same transaction
   - Verify both operations committed successfully
   - Database state validation for all affected tables

2. **Database Consistency Verification**
   - Cross-reference data between business and event tables
   - Use correlation IDs to link related records
   - Validate headers, metadata, and payload serialization

3. **Transaction Timing Validation**
   - Verify transaction_time is within expected bounds
   - Validate valid_time vs transaction_time relationships
   - Check temporal consistency

4. **Rollback Scenarios** (when applicable)
   - Simulate failures after partial operations
   - Verify no partial data exists after rollback
   - Confirm database state is clean after failed transactions

### 4. **Test Structure Standards**

#### **Test Class Naming**
- Unit Tests: `[Feature]Test.java`
- Integration Tests: `[Feature]IntegrationTest.java`
- Follow plan specifications for test class names

#### **Test Method Naming**
- Use descriptive names: `testSimpleTransactionParticipation()`
- Include validation type: `testBusinessTableEventLogConsistency()`
- Use `@DisplayName` for human-readable descriptions

#### **Test Organization**
```java
@Test
@DisplayName("Clear description of what is being tested")
void testMethodName() throws Exception {
    // 1. Setup test data
    // 2. Execute operation
    // 3. Verify API response
    // 4. CRITICAL: Verify database state
    // 5. Validate cross-table consistency
    // 6. Check timing/temporal aspects
}
```

## Increment-Specific Testing Requirements

### **Phase 1: Foundation and Core Infrastructure**

#### **Increment 1.1: Method Signatures**
- ✅ Unit tests for method signature existence
- ✅ Compilation verification
- ✅ Parameter validation logic

#### **Increment 1.2: Core Implementation**
- ✅ Unit tests for parameter validation
- ✅ Integration tests with TestContainers
- ✅ Database state validation

#### **Increment 1.3: Error Handling**
- ✅ Edge case testing
- ✅ Error condition validation
- ✅ Exception handling verification

### **Phase 2: Integration and Transaction Scenarios**

#### **Increment 2.1: Simple Transaction Integration Tests** ✅ COMPLETED
- ✅ TestContainers-based integration tests
- ✅ Simple business operation + bitemporal event in same transaction
- ✅ Transaction commit verification with database state validation
- ✅ Business table + bitemporal_event_log consistency verification
- ✅ Data integrity validation queries
- ✅ Transaction timing validation

**Test Coverage Achieved:**
- `testSimpleTransactionParticipation()` - Basic transaction participation with comprehensive database validation
- `testBusinessTableEventLogConsistency()` - Cross-table consistency verification with correlation IDs
- `testTransactionCommitVerification()` - Transaction timing and commit validation

#### **Increment 2.2: Transaction Rollback Scenarios** ✅ COMPLETED
**Required Tests:**
- ✅ Transaction rollback after business operation failure
- ✅ Transaction rollback after bitemporal append failure
- ✅ Database state verification after rollback (no orphaned data)
- ✅ Partial operation cleanup validation
- ✅ Transaction boundary integrity tests

**Test Coverage Achieved:**
- `testBusinessOperationFailureAfterBiTemporalAppend()` - Rollback after business operation failure with comprehensive database validation
- `testBiTemporalAppendFailureAfterBusinessOperation()` - Rollback after bitemporal append failure with clean state verification
- `testTransactionBoundaryIntegrity()` - Complex multi-operation transaction rollback with no partial commits

#### **Increment 2.3: Multiple Operations in Single Transaction** (FUTURE)
**Required Tests:**
- Multiple `appendInTransaction` calls in same transaction
- Cross-operation consistency validation
- Performance impact assessment
- Resource cleanup verification

## Database Validation Patterns

### **Standard Validation Queries**

#### **Business Data Validation**
```sql
SELECT COUNT(*) FROM business_data WHERE [conditions]
```

#### **Event Data Validation**
```sql
SELECT COUNT(*) FROM bitemporal_event_log WHERE event_id = ? AND event_type = ?
```

#### **Cross-Table Consistency**
```sql
SELECT bd.*, bel.* 
FROM business_data bd 
CROSS JOIN bitemporal_event_log bel 
WHERE [correlation_conditions]
```

#### **Temporal Validation**
```sql
SELECT transaction_time, valid_time 
FROM bitemporal_event_log 
WHERE event_id = ?
```

### **Payload Integrity Validation**
```sql
SELECT payload FROM bitemporal_event_log WHERE event_id = ?
```
- Verify JSON serialization correctness
- Check for expected data fields
- Validate data types and values

## TestContainers Standards

### 🚨 **Critical Rules**

#### **1. ALWAYS Use PostgreSQLTestConstants**

```java
// ✅ CORRECT - Use the centralized constant
import dev.mars.peegeeq.test.PostgreSQLTestConstants;

@Container
@SuppressWarnings("resource")
static PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer();

// OR with custom settings
@Container
@SuppressWarnings("resource")
static PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createContainer(
    "custom_db_name",
    "custom_user",
    "custom_password");
```

```java
// ❌ WRONG - Never hardcode PostgreSQL versions
@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");
```

#### **2. Standard Container Patterns**

**Pattern A: Static Container (Recommended for Most Tests)**
```java
@Testcontainers
class MyTest {
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer();

    private final Map<String, String> originalProperties = new HashMap<>();

    @BeforeEach
    void setUp() {
        saveOriginalProperties();
        configureSystemPropertiesForContainer();
    }

    @AfterEach
    void tearDown() {
        restoreOriginalProperties();
    }
}
```

**Pattern B: Spring Boot Tests with @DynamicPropertySource**
```java
@SpringBootTest
@Testcontainers
class SpringBootTest {
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("peegeeq.database.host", postgres::getHost);
        registry.add("peegeeq.database.port", () -> postgres.getFirstMappedPort().toString());
        registry.add("peegeeq.database.name", postgres::getDatabaseName);
        registry.add("peegeeq.database.username", postgres::getUsername);
        registry.add("peegeeq.database.password", postgres::getPassword);
        registry.add("peegeeq.database.schema", () -> "public");
        registry.add("peegeeq.migration.enabled", () -> "true");
        registry.add("peegeeq.migration.auto-migrate", () -> "true");
    }
}
```

#### **3. System Property Configuration**

> ⚠️ **Preferred: Use `PeeGeeQTestConfig.builder()` instead of `System.setProperty`**
>
> `System.setProperty` writes to the global JVM property map. In a parallel test run, any
> other test constructing a `PeeGeeQConfiguration` while properties are only partially written
> (e.g. `pool.max-size` set but `pool.min-size` not yet set) will read an inconsistent view
> and can fail with `"Maximum pool size must be greater than or equal to minimum pool size"`.
> The `PeeGeeQTestConfig.builder()` helper produces a `Properties` object that is passed
> directly to `new PeeGeeQConfiguration("default", props)` — it never touches
> `System.getProperties()`, so there is no race window.

**Required pattern for all new tests:**
```java
Properties testProps = PeeGeeQTestConfig.builder()
    .from(postgres)          // extracts host, port, db, user, password
    .schema("public")        // optional; defaults to "public"
    .property("peegeeq.database.pool.min-size", "1")
    .property("peegeeq.database.pool.max-size", "3")
    .property("peegeeq.migration.enabled", "false")
    .build();

PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
```

No `@BeforeEach`/`@AfterEach` property save/restore boilerplate is needed — isolation is
built in. Each test instance holds its own `Properties` object; nothing leaks to siblings.

**Legacy pattern (only for tests that cannot use the builder — must include cleanup):**
```java
private void configureSystemPropertiesForContainer() {
    // Standard PeeGeeQ properties
    System.setProperty("peegeeq.database.host", postgres.getHost());
    System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
    System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
    System.setProperty("peegeeq.database.username", postgres.getUsername());
    System.setProperty("peegeeq.database.password", postgres.getPassword());
    System.setProperty("peegeeq.database.schema", "public");
    System.setProperty("peegeeq.database.ssl.enabled", "false");
    System.setProperty("peegeeq.migration.enabled", "true");
    System.setProperty("peegeeq.migration.auto-migrate", "true");
}
```

**Property Cleanup Pattern**
```java
private final Map<String, String> originalProperties = new HashMap<>();

private void saveOriginalProperties() {
    String[] propertiesToSave = {
        "peegeeq.database.host", "peegeeq.database.port", "peegeeq.database.name",
        "peegeeq.database.username", "peegeeq.database.password", "peegeeq.database.schema"
    };

    for (String property : propertiesToSave) {
        String value = System.getProperty(property);
        if (value != null) {
            originalProperties.put(property, value);
        }
    }
}

private void restoreOriginalProperties() {
    // Clear test properties
    System.clearProperty("peegeeq.database.host");
    System.clearProperty("peegeeq.database.port");
    System.clearProperty("peegeeq.database.name");
    System.clearProperty("peegeeq.database.username");
    System.clearProperty("peegeeq.database.password");
    System.clearProperty("peegeeq.database.schema");

    // Restore original properties
    originalProperties.forEach(System::setProperty);
}
```

#### **4. Container Configuration Options**

**Standard Container**
```java
PostgreSQLTestConstants.createStandardContainer()
// Uses: postgres:15.13-alpine3.20, 256MB shared memory, no reuse
```

**Custom Container**
```java
PostgreSQLTestConstants.createContainer("db_name", "username", "password")
// Uses: postgres:15.13-alpine3.20, 256MB shared memory, no reuse
```

**High Performance Container**
```java
PostgreSQLTestConstants.createHighPerformanceContainer("db_name", "username", "password")
// Uses: postgres:15.13-alpine3.20, 512MB shared memory, performance tuning
```

#### **5. Common Mistakes to Avoid**

❌ **Don't Do This:**
```java
// Hardcoded version
new PostgreSQLContainer<>("postgres:15")

// Missing @SuppressWarnings("resource")
@Container
static PostgreSQLContainer<?> postgres = ...

// No property cleanup
@BeforeEach
void setUp() {
    System.setProperty("db.host", postgres.getHost());
    // No cleanup in @AfterEach
}

// Setting pool properties via System.setProperty in parallel tests
// (race window: another thread reads an inconsistent min/max state)
System.setProperty("peegeeq.database.pool.min-size", "1");
System.setProperty("peegeeq.database.pool.max-size", "3");  // NOT atomic

// Inconsistent property names
System.setProperty("db.host", postgres.getHost());        // Wrong
System.setProperty("database.host", postgres.getHost());  // Wrong
```

✅ **Do This Instead:**
```java
// Use centralized constants
PostgreSQLTestConstants.createStandardContainer()

// Always suppress resource warnings for static containers
@Container
@SuppressWarnings("resource")
static PostgreSQLContainer<?> postgres = ...

// Always clean up properties
@BeforeEach void setUp() { saveOriginalProperties(); configureSystemPropertiesForContainer(); }
@AfterEach void tearDown() { restoreOriginalProperties(); }

// Use PeeGeeQTestConfig.builder() for pool/connection config — zero System.setProperty needed
Properties testProps = PeeGeeQTestConfig.builder()
    .from(postgres)
    .property("peegeeq.database.pool.min-size", "1")
    .property("peegeeq.database.pool.max-size", "3")
    .build();
PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);

// Use standard property names
System.setProperty("peegeeq.database.host", postgres.getHost());
```

### **TestContainers Migration Tools**

**Check for Violations**
```bash
# Run the pre-commit check
./scripts/git-hooks/pre-commit-postgresql-check

# Search for hardcoded versions
findstr /R /S /C:"new PostgreSQLContainer.*postgres:" *.java
```

---

## Vert.x Async Test Safety Patterns

> **Companion fixture.**
> [peegeeq-db/src/test/java/dev/mars/peegeeq/db/testpatterns/VertxAsyncTestPitfallsDemo.java](../../peegeeq-db/src/test/java/dev/mars/peegeeq/db/testpatterns/VertxAsyncTestPitfallsDemo.java)
> contains executable counter-examples for every anti-pattern listed here.

### Why this section exists

PeeGeeQ's test suite is large, fully reactive, and runs against real PostgreSQL
via Testcontainers. The combination of Vert.x 5 reactive futures, JUnit5's
`VertxExtension`, and `VertxTestContext` is powerful but unforgiving: the
framework's default behaviour on a mis-wired test is **silence**, not failure.

During the Tier 1 async-test sweep across `peegeeq-db`, `peegeeq-outbox`,
`peegeeq-native`, `peegeeq-examples`, and `peegeeq-bitemporal`, we repeatedly
found tests where:

- Assertions threw `AssertionError`, but Vert.x caught and logged the error
  at WARN — the test reported a generic timeout with no diagnostic.
- Futures failed, but no `.onFailure` was wired — the failure vanished.
- `awaitCompletion(...)` returned `false` on timeout, but the return value was
  discarded and a trivial post-await assertion let the test pass green.
- `ctx.completeNow()` was called too early in a `.compose` chain — later
  assertions were delivered to a closed context and silently dropped.
- `.toCompletionStage().get()` wrapped `AssertionError` in `ExecutionException`,
  which a broad `catch (Exception)` silently discarded.

In every case, the build was green. **That is the failure mode this section exists to eliminate.**

### Canonical safe forms

Every async PeeGeeQ test must use one of the two forms below as the terminal
node of every chain that the test depends on.

#### Success expected

```java
@Test
void someOperationSucceeds(VertxTestContext ctx) {
    service.doSomething()
        .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
            assertEquals(expected, result);
            ctx.completeNow();
        })));
}
```

#### Failure expected

```java
@Test
void someOperationFailsWithIllegalArgument(VertxTestContext ctx) {
    service.doSomething(-1)
        .onComplete(ctx.failing(e -> ctx.verify(() -> {
            assertTrue(e instanceof IllegalArgumentException);
            ctx.completeNow();
        })));
}
```

#### Why exactly this form

There are **three obligations** on every async test branch. The canonical form
satisfies all three by construction; alternative forms satisfy them only if
hand-written correctly every time.

| Obligation | How the canonical form satisfies it |
|---|---|
| Route both success and failure to the test context | `ctx.succeeding` / `ctx.failing` route the *unexpected* branch to `failNow` automatically. |
| Catch `AssertionError` thrown in the lambda and report it to the test context | `ctx.verify(() -> ...)` wraps the lambda and reroutes any `AssertionError` to `ctx.failNow`. |
| Reach `ctx.completeNow()` exactly once on the intended terminal branch | The verify body is the only branch — `completeNow()` is its last statement. |

Drop **any one** of those three pieces and the test will lie to you on at least
one failure path.

### Anti-patterns (all demonstrated in `VertxAsyncTestPitfallsDemo`)

#### Category A: failures masked as timeouts

These are bad UX (the real diagnostic is hidden), but at least the test *does* fail.

##### A1. Assertion in raw `.onSuccess` with no `ctx.verify`

```java
// WRONG
future.onSuccess(v -> {
    assertEquals(expected, v);   // AssertionError swallowed by Vert.x
    ctx.completeNow();           // never reached
}).onFailure(ctx::failNow);
```

`AssertionError` propagates out of the lambda, Vert.x logs it at WARN, and
`completeNow()` is never reached. The test hangs to `awaitCompletion` timeout
with no mention of the failed assertion.

**Fix:** wrap the assertion in `ctx.verify(...)`.

##### A2. `.onSuccess` with no `.onFailure`

```java
// WRONG
future.onSuccess(v -> ctx.verify(() -> { ...; ctx.completeNow(); }));
// no .onFailure — future failures go to Vert.x's unhandled-exception logger
```

If the future fails, nothing reaches `ctx`. Test times out with no cause.

**Fix:** use `.onComplete(ctx.succeeding(...))` so the failure path is routed automatically.

#### Category B: failures that produce GREEN BUILDS (catastrophic)

These are the patterns that have produced real outages of trust in the test suite.

##### B1. Ignored `awaitCompletion` return value

```java
// WRONG
VertxTestContext ctx = new VertxTestContext();
service.doWork(ctx);
ctx.awaitCompletion(100, TimeUnit.MILLISECONDS);  // return value discarded
assertTrue(counter.get() > 0);                    // runs against partial state
```

`awaitCompletion` returns `false` on timeout. If the caller doesn't check the
return value, the test continues into post-await assertions that have nothing
to do with the actual work, and passes green.

**Fix:** always inject `VertxTestContext` as a method parameter (let the
`VertxExtension` manage the await), or — if a manual context is required —
`assertTrue(ctx.awaitCompletion(...))` as the first post-await line.

##### B2. `completeNow()` on the wrong `.compose` branch

```java
// WRONG
future
    .compose(a -> {
        ctx.completeNow();              // PREMATURE — test is done as far as JUnit knows
        return doStepTwo();
    })
    .compose(b -> {
        assertEquals(expected, b);      // failure has nowhere to go
        return Future.succeededFuture();
    })
    .onFailure(ctx::failNow);           // failNow on completed context is ignored
```

Once `ctx.completeNow()` has been called, the context is closed and subsequent
`failNow` / `verify` calls are no-ops. Any downstream assertion failure is silently dropped.

**Fix:** call `ctx.completeNow()` exactly once, in the *last* terminal handler.

##### B3. Sync bridge + broad `catch`

```java
// WRONG
try {
    future.toCompletionStage().toCompletableFuture().get();
} catch (Exception e) {
    // anti-pattern: catch Exception silently
}
```

`.get()` wraps any failure (including `AssertionError`) in `ExecutionException`.
A broad `catch (Exception)` swallows it. The PeeGeeQ coding rules already ban
sync bridges (`.toCompletionStage()`, `.toCompletableFuture()`, `.get()`,
`.join()`); this is *why*.

**Fix:** remove the bridge entirely. Use composable futures only.

##### B4. `ctx.verify(...)` after `ctx.completeNow()`

```java
// WRONG
future.onSuccess(v -> {
    ctx.completeNow();
    ctx.verify(() -> assertEquals(expected, v));   // ctx is already closed
});
```

`VertxTestContext` ignores failures delivered after completion. The verify body
runs, the `AssertionError` is caught by `verify`, but the call to `failNow`
inside `verify` is a no-op because the context is already complete.

**Fix:** put `ctx.completeNow()` at the *end* of the `verify` body, never before or alongside it.

### Hard rules for new tests

These are mandatory and enforceable by reviewer judgment until automated rules are added.

1. **Every terminal future handler in a test must be `.onComplete(ctx.succeeding(...))` or `.onComplete(ctx.failing(...))`.** Bare `.onSuccess(...).onFailure(...)` pairs are banned in test code.
2. **Every assertion inside an async callback must be wrapped in `ctx.verify(...)`.** No exceptions.
3. **`ctx.completeNow()` must be the last statement of the `verify` body on the intended terminal branch.** Never call it earlier; never call it from multiple branches.
4. **Never construct a manual `VertxTestContext` unless you need an unusual lifecycle.** Inject it as a method parameter and let `VertxExtension` handle the await. If you must construct one, assert the boolean return of `awaitCompletion`.
5. **Never bridge a `Future` to a `CompletableFuture` in tests.** Already banned by `pgq-coding-principles.md`; this is the most common source of swallowed failures.
6. **Post-`awaitCompletion` assertions are forbidden.** All verification happens inside the future chain. The only thing allowed after `awaitCompletion` is the JUnit-managed test exit.

### Proposed future hardening

Not yet implemented; tracked here as the natural next step once the Tier 1 sweep finishes across all modules.

- **ArchUnit rule**: forbid `.onSuccess(...)` / `.onFailure(...)` calls in any
  `src/test` class file under any PeeGeeQ module. Force `.onComplete(...)` as
  the only legal terminal handler in tests.
- **ArchUnit rule**: forbid `.toCompletionStage()`, `.toCompletableFuture()`,
  `.get()`, `.join()`, `Thread.sleep` in `src/test`.
- **Checkstyle rule** (if a parser can handle it): require any `assertEquals` /
  `assertTrue` / `assertNotNull` call lexically inside a `.onComplete` lambda
  to be inside a `ctx.verify(...)` call.
- **CI-side default timeout**: shorten the default `awaitCompletion` timeout
  to 10s in CI so accidental pass-by-timeout failures surface fast instead of
  silently consuming the full default.
- **Helper consolidation**: a tiny `TestAssertions.succeeding(ctx, body)` and
  `TestAssertions.failing(ctx, type, body)` that bundle verify + completeNow,
  so the surface area where a test author can get the plumbing wrong shrinks to zero.

---

## Vert.x `Future.recover()` Usage and Anti-Patterns

### The anti-pattern: `.recover()` as silent error erasure

In a `Future` chain, `.recover()` is the reactive equivalent of a `catch` block. When a
`.recover()` handler returns `Future.succeededFuture()`, it converts a failed Future into
a succeeded one. Every caller upstream now sees success. The error is gone from the chain —
it has been erased.

The dangerous pattern looks like this:

```java
doSomething()
    .recover(error -> {
        logger.error("Something failed: {}", error.getMessage());
        return Future.succeededFuture();  // failure becomes success
    })
    .compose(result -> doNextThing())  // runs as if nothing went wrong
```

This is functionally identical to:

```java
try {
    doSomething();
} catch (Exception e) {
    logger.error("Something failed: {}", e.getMessage());
    // swallowed — execution continues as if nothing happened
}
doNextThing();
```

The log line creates the illusion that the error is handled. It is not handled. It is
discarded. The caller has no way to know the operation failed. The system continues in a
corrupt or inconsistent state, and any downstream failure is now disconnected from its
actual cause.

This pattern is especially destructive in reactive code because:

- **Errors are values, not stack-unwinding exceptions.** In synchronous code, a swallowed
  exception at least stops the current method from doing further damage (unless you catch
  and continue). In a `Future` chain, `.recover()` to success means the chain keeps
  composing — the next `.compose()` runs, receives a result that looks valid, and acts
  on it. There is no implicit "stop executing" behavior.

- **It defeats the entire point of `Future` error propagation.** A `Future<T>` carries
  either a result or an error. `.compose()` already short-circuits on failure — it skips
  downstream stages and propagates the error to whoever is observing the final Future.
  This is correct behavior, and it is free. `.recover()` to success actively works against
  this by forcing the chain to continue through stages that should never have run.

- **It is invisible at the call site.** The caller composes on the returned Future and
  has no indication that failures have been silently erased inside. Unlike a `throws`
  declaration or a checked exception, there is no signal in the type system that this
  Future can never fail because someone swallowed the errors internally.

- **Log lines are not error signals.** A `logger.error()` call inside `.recover()` writes
  to a file or stdout. It does not complete a Promise as failed, does not throw, does not
  set a flag, does not notify the caller. In production, nobody is tailing the log in real
  time. The error is recorded for forensic use but has zero operational effect.

### What `.recover()` is actually for (per Vert.x documentation)

The Vert.x API Javadoc defines `recover()` as:

> **Handles a failure of this Future by returning the result of another Future.**
> If the mapper fails, then the returned future will be failed with this failure.
>
> `Future.recover(Function<Throwable, Future<T>> mapper)`

The key phrase is "**returning the result of another Future.**" The mapper function
receives the exception and is expected to return a *new Future that produces a real
result of the same type `T`*. This is a recovery operation: the original operation
failed, so you try an alternative path that can still produce a meaningful value
for the caller.

`recover()` is the failure-side counterpart to `compose()`. Where `compose()` chains
on success (`successMapper` returns a new `Future<U>`), `recover()` chains on failure
(`mapper` returns a new `Future<T>`). The two-argument form of `compose()` makes this
explicit:

```java
future.compose(successMapper, failureMapper)
// recover() is sugar for:
future.compose(Future::succeededFuture, failureMapper)
```

In both cases, the mapper is expected to produce a Future that represents a real
alternative outcome — either a successful result or a propagated/re-thrown failure.

The Vert.x composition model works as follows:
- `.compose()` short-circuits on failure: when a Future fails, downstream `.compose()`
  stages are skipped, and the failure propagates automatically to the final Future.
  This is free and correct.
- `.recover()` intercepts that propagation. If the mapper returns
  `Future.succeededFuture()`, the failure is erased and downstream stages run as if
  nothing went wrong.
- `.eventually()` runs a side-effect on completion (success or failure) without
  altering the outcome — it explicitly documents that "the outcome of the future
  returned by the mapper will not influence the nature of the returned future."

### There are zero legitimate uses of `.recover()` in this project

The Vert.x API defines two narrow use cases for `recover()`:

1. **Fallback to a real alternative** — try a secondary data source, use a cached value.
2. **Selective recovery** — inspect the exception type, recover from expected failures,
   re-throw everything else.

Neither applies to any `.recover()` call in this codebase. Every instance is either
using the wrong API entirely, or implementing logic that belongs at a different
architectural layer.

**Why the "health check" pattern is wrong:** The previous version of this analysis
classified 12 health check instances as legitimate — converting a connection error into
`HealthStatus.unhealthy("...")` or `Future.succeededFuture(false)` via `recover()`.
This is wrong. The health check should let the Future fail. The health endpoint caller
should handle the failed Future and render it as an unhealthy response. Pushing
error→domain-status conversion into a `.recover()` inside the health check implementation
conflates error handling with domain mapping. Use `.transform()` if the return type needs
converting, or let the caller decide what a failure means.

**Why the "idempotency / conflict detection" pattern is wrong:** Idempotency should be
handled in SQL (`INSERT ... ON CONFLICT DO NOTHING`, `CREATE DATABASE IF NOT EXISTS`) not
by throwing an exception and then catching it with `recover()` to convert it back to
success. The exception-and-recover path is a round-trip through failure for something that
is not a failure. It is a control flow abuse.

**Why the "retry / failover" pattern is wrong:** Retry and failover are infrastructure
concerns. They should be implemented in retry middleware or a circuit breaker, not inline
via `recover()` at each call site. `recover()` makes the retry logic invisible to the
caller and couples it to the specific call chain.

**Why the "DLQ routing" pattern is wrong:** Message processing failure → DLQ routing is a
framework responsibility. The message processing pipeline should handle failures through
its own error channel, not by using `recover()` to convert a processing failure into a
succeeded Future that happens to have routed the message to DLQ. The caller of the
processing chain should see the failure.

**What about cleanup during shutdown?** The correct API for cleanup-regardless-of-outcome
is `.eventually()`:

> **Invokes the given function upon completion.** The outcome of the
> future returned by the mapper will **not influence the nature of the
> returned future.**

`.eventually()` runs a side-effect (close a pool, cancel a timer, leave a group) without
altering whether the overall Future succeeds or fails. This is exactly what shutdown
cleanup needs. Using `.recover()` for shutdown cleanup forces the developer to explicitly
return `Future.succeededFuture()` to prevent errors from propagating — and that is error
erasure, even if the context is "just cleanup."

**What about log-and-re-throw?** A common pattern in this codebase is:

```java
.recover(error -> {
    logger.error("Operation failed: {}", error.getMessage(), error);
    return Future.failedFuture(new RuntimeException("Wrapped: " + error.getMessage(), error));
})
```

This is not recovery either. The mapper always returns a failed Future. Nothing is
recovered. The correct approach is:
- **If you just want to log:** use `.onFailure(error -> logger.error(...))`.
- **If you need to wrap the exception type:** use `.transform()` or `.recover()` with the
  understanding that you are selectively converting one failure type to another. But if
  every error path returns `failedFuture()`, you are paying the semantic cost of
  `recover()` for zero benefit over `.onFailure()`.

### Summary: when to use each API

| Intent | Correct API | Wrong API |
|---|---|---|
| Log an error without affecting the chain | `.onFailure(e -> log(e))` | `.recover(e -> { log(e); return failedFuture(e); })` |
| Convert error to domain status (e.g. unhealthy) | `.transform()` or caller handles failure | `.recover(e -> succeededFuture(unhealthy(e)))` |
| Handle expected error (e.g. duplicate key) | SQL-level (`ON CONFLICT`, `IF NOT EXISTS`) | `.recover(e -> { if (duplicate) return succeededFuture(); })` |
| Retry or failover | Retry middleware / circuit breaker | `.recover(e -> { if (retryable) return retry(); })` |
| Run cleanup regardless of outcome | `.eventually(() -> resource.close())` (only if `close()` is event-loop-safe — see caveat below) | `.recover(e -> { cleanup(); return succeededFuture(); })` |
| Erase errors so the chain continues | **Do not do this.** Let `.compose()` short-circuit — propagate the error. | `.recover(e -> { log(e); return succeededFuture(); })` |
| Return fabricated data on failure | **Do not do this.** Propagate the error. | `.recover(e -> succeededFuture(0L))` |

---

### Project-wide `.recover()` audit — systematic module-by-module review

A full audit of every `.recover()` call across the entire PeeGeeQ codebase. Every
module is listed. Within each module, every class containing `.recover()` is listed.
Within each class, every instance is listed with its line number, classification,
and description.

#### Classification key

| Classification | Meaning |
|---|---|
| **ERASURE** | Silent error swallowing in operational code. Callers see success. This is a bug. |
| **ERASURE-IN-SHUTDOWN** | Error swallowing during shutdown/cleanup. Should use `.eventually()`. |
| **RE-WRAPS-FAILURE** | Logs error, then re-throws via `Future.failedFuture()`. Should use `.onFailure()` or `.transform()`. |
| **SELECTIVE-RECOVERY** | Inspects exception type, recovers from expected errors. Should be handled in SQL or retry infrastructure. |
| **PROPER-FALLBACK** | Returns a domain-meaningful alternative (health status, failover). Should use `.transform()` or let caller handle failure. |
| **TYPED-ERASURE** | Returns fabricated data (`0`, `0L`, empty `JsonArray`, `null`, empty list) of the correct return type. Caller cannot distinguish "no data exists" from "query failed." Error erasure in a type-correct wrapper. This is a bug. |

#### Summary totals (production code only)

| Classification | Count |
|---|---|
| **ERASURE** | 17 |
| **ERASURE-IN-SHUTDOWN** | 27 |
| **RE-WRAPS-FAILURE** | 25 |
| **SELECTIVE-RECOVERY** | 7 |
| **PROPER-FALLBACK** | 15 |
| **TYPED-ERASURE** | 26 |
| **Total** | **117 — all wrong** |

---

#### Module 1: `peegeeq-api`

**Production code:** No `.recover()` calls.

**Test code:** No `.recover()` calls.

This module defines interfaces and data types only. No reactive implementation code.

---

#### Module 2: `peegeeq-bitemporal`

**Production code: 1 class, 6 instances.**

##### PgBiTemporalEventStore.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 1 | 1286 | **ERASURE-IN-SHUTDOWN** | `close()`: notification handler stop → logs warning, captures first error in `AtomicReference<Throwable>`, returns `Future.succeededFuture()`. Error is captured and re-raised at end of close chain. | Partially captured and re-raised |
| 2 | 1298 | **ERASURE-IN-SHUTDOWN** | `close()`: reactive pool close → same `firstError` capture pattern. | Partially captured and re-raised |
| 3 | 1310 | **ERASURE-IN-SHUTDOWN** | `close()`: pipelined client close → same `firstError` capture pattern. | Partially captured and re-raised |
| 4 | 1816 | **RE-WRAPS-FAILURE** | Event bus operation fails → logs error, re-throws via `Future.failedFuture(error)`. | Yes |
| 5 | 2122 | **ERASURE-IN-SHUTDOWN** | `clearInstancePools()`: reactive pool close → captures first error. | Partially captured and re-raised |
| 6 | 2133 | **ERASURE-IN-SHUTDOWN** | `clearInstancePools()`: pipelined client close → captures first error. | Partially captured and re-raised |

**Test code: 27 instances across 19 test classes.** Most are teardown `manager.closeReactive().recover(err -> Future.succeededFuture())` in `@AfterEach` methods — the same ERASURE-IN-SHUTDOWN pattern replicated in test cleanup.

---

#### Module 3: `peegeeq-coverage-report`

**Production code:** No Java source files. POM-only aggregation module.

---

#### Module 4: `peegeeq-db`

**Production code: 14 classes, 59 instances.** This is the worst-affected module.

##### PeeGeeQManager.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 7 | 278 | **RE-WRAPS-FAILURE** | `start()` fails → logs error, stops background tasks and health checks (with nested ERASURE), publishes "manager.failed" event, then re-throws via `Future.failedFuture(new RuntimeException(...))`. | Yes (eventually) |
| 8 | 285 | **ERASURE-IN-SHUTDOWN** | Inside start() failure handler: `healthCheckManager.stop().recover(e -> Future.succeededFuture())` ignores errors during cleanup-after-startup-failure. | No — erased |
| 9 | 325 | **RE-WRAPS-FAILURE** | `stop()` fails → logs error, marks as stopped anyway, returns `Future.failedFuture(throwable)`. | Yes |
| 10 | 407 | **ERASURE-IN-SHUTDOWN** | `closeReactive()`: `awaitStart.recover(startError -> Future.succeededFuture())` absorbs start failure so cleanup chain proceeds. | No — erased |
| 11 | 410 | **ERASURE-IN-SHUTDOWN** | `stop()` fails during close → warns, returns `Future.succeededFuture()`. | No — erased |
| 12 | 423 | **ERASURE-IN-SHUTDOWN** | Each close hook failure → warns, returns `Future.succeededFuture()` to continue chain. | No — erased |
| 13 | 437 | **ERASURE-IN-SHUTDOWN** | Worker executor close failure → warns, returns `Future.succeededFuture()`. | No — erased |
| 14 | 449 | **ERASURE-IN-SHUTDOWN** | Client factory close failure → warns, returns `Future.succeededFuture()`. | No — erased |
| 15 | 474 | **SELECTIVE-RECOVERY** | Vert.x close: if `RejectedExecutionException`, treats as expected and succeeds; other errors → warn + succeed. `RejectedExecutionException` branch should use `.eventually()` since this is shutdown. | Partially |
| 16 | 490 | **SELECTIVE-RECOVERY** | Final catch-all: `RejectedExecutionException` → succeed; other errors → re-throw via `Future.failedFuture(e)`. | Yes (for non-RejectedExecution) |
| 17 | 738 | **RE-WRAPS-FAILURE** | `validateDatabaseConnectivity()` fails → logs, re-throws as `Future.failedFuture(new RuntimeException("Database startup validation failed", throwable))`. | Yes |
| 18 | 935 | **ERASURE-IN-SHUTDOWN** | `deadConsumerDetectionJob.stop().recover(e -> Future.succeededFuture())` inside `stopBackgroundTasks()`. | No — erased |

##### PeeGeeQMetrics.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 19 | 672 | **ERASURE** | `executeReactiveCountQuery()` fails → logs warning, returns `Future.succeededFuture(0.0)`. Metric query failure silently returns zero. | No — erased |
| 20 | 705 | **ERASURE** | `persistMetrics()` fails → logs error (or debug for connection errors), returns `Future.succeededFuture()`. Metric persistence failure silently swallowed. | No — erased |
| 21 | 745 | **ERASURE** | `isHealthy()` fails → logs warning, returns `Future.succeededFuture(false)`. Health check failure returns false instead of propagating. | No — erased |

##### HealthCheckManager.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 22 | 235 | **ERASURE-IN-SHUTDOWN** | `stop()`: awaits in-flight check cycle, `.recover(e -> Future.succeededFuture())`. | No — erased (shutdown) |
| 23 | 252 | **RE-WRAPS-FAILURE** | `validateConnectionPool()` fails → logs error, re-throws as `Future.failedFuture(new RuntimeException(...))`. | Yes |
| 24 | 352 | **ERASURE-IN-SHUTDOWN** | `inFlightCheckCycle.recover(e -> Future.succeededFuture())` tracks in-flight cycle, erases errors for stop() await. | No — erased |
| 25 | 640 | **PROPER-FALLBACK** | `DatabaseHealthCheck.checkReactive()` outer recover → returns `HealthStatus.unhealthy(...)`. Should use `.transform()` or let caller handle failure. | No — wrong layer |
| 26 | 655 | **PROPER-FALLBACK** | `DatabaseHealthCheck.checkDatabase()` inner recover → returns unhealthy status with error message. Should use `.transform()`. | No — wrong layer |
| 27 | 666 | **PROPER-FALLBACK** | `OutboxQueueHealthCheck.checkReactive()` outer recover. Should use `.transform()`. | No — wrong layer |
| 28 | 689 | **PROPER-FALLBACK** | `OutboxQueueHealthCheck.checkOutboxQueue()` inner recover detects missing tables as FATAL. Should use `.transform()`. | No — wrong layer |
| 29 | 705 | **PROPER-FALLBACK** | `NativeQueueHealthCheck.checkReactive()` outer recover. Should use `.transform()`. | No — wrong layer |
| 30 | 724 | **PROPER-FALLBACK** | `NativeQueueHealthCheck.checkNativeQueue()` inner recover detects missing tables as FATAL. Should use `.transform()`. | No — wrong layer |
| 31 | 740 | **PROPER-FALLBACK** | `DeadLetterQueueHealthCheck.checkReactive()` outer recover. Should use `.transform()`. | No — wrong layer |
| 32 | 764 | **PROPER-FALLBACK** | `DeadLetterQueueHealthCheck.checkDeadLetterQueue()` inner recover detects missing tables as FATAL. Should use `.transform()`. | No — wrong layer |

##### DeadLetterQueueManager.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 33 | 143 | **RE-WRAPS-FAILURE** | `moveToDeadLetterQueue()` fails → logs error, re-throws via `Future.failedFuture(throwable)`. | Yes |
| 34 | 182 | **RE-WRAPS-FAILURE** | `fetchStatistics()` → logs error, re-throws. | Yes |
| 35 | 210 | **RE-WRAPS-FAILURE** | `fetchDeadLetterMessagesByTopic()` → logs error, re-throws. | Yes |
| 36 | 237 | **RE-WRAPS-FAILURE** | `fetchAllDeadLetterMessages()` → logs error, re-throws. | Yes |
| 37 | 262 | **RE-WRAPS-FAILURE** | `fetchDeadLetterMessage()` → logs error, re-throws. | Yes |
| 38 | 322 | **RE-WRAPS-FAILURE** | `reprocessDeadLetterMessageRecord()` → logs error, re-throws. | Yes |
| 39 | 344 | **RE-WRAPS-FAILURE** | `removeDeadLetterMessage()` → logs error, re-throws. | Yes |
| 40 | 366 | **RE-WRAPS-FAILURE** | `purgeOldDeadLetterMessages()` → logs error, re-throws. | Yes |

##### PgConnectionManager.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 41 | 368 | **PROPER-FALLBACK** | `checkHealth()` fails → returns `Future.succeededFuture(false)`. Should use `.transform()` or let caller handle failure. | No — wrong layer |
| 42 | 477 | **ERASURE-IN-SHUTDOWN** | `close()` all pools — if some fail, logs error and returns `Future.succeededFuture()`. Close never propagates failure. | No — erased |

##### PgConnectionProvider.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 43 | 152 | **PROPER-FALLBACK** | `isHealthy()` fails → returns `Future.succeededFuture(false)`. Should use `.transform()`. | No — wrong layer |

##### StuckMessageRecoveryManager.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 44 | 104 | **ERASURE** | `recoverStuckMessages()` fails → logs error, returns `Future.succeededFuture(0)`. Recovery failure silently swallowed; callers think zero messages were recovered. | No — erased |
| 45 | 132 | **ERASURE** | `countStuckMessages()` fails → logs error, returns `Future.succeededFuture(0)`. | No — erased |
| 46 | 164 | **ERASURE** | `resetStuckMessages()` fails → logs error, returns `Future.succeededFuture(0)`. | No — erased |
| 47 | 202 | **ERASURE** | `logRecoveredMessages()` fails → logs warning, returns `Future.succeededFuture()`. Benign: this is just logging, not business logic. | No — erased (low risk) |
| 48 | 221 | **ERASURE** | `getRecoveryStats()` fails → logs warning, returns `Future.succeededFuture(new RecoveryStats(0, 0, true))`. | No — erased |
| 49 | 241 | **ERASURE** | `countTotalProcessingMessages()` fails → logs error, returns `Future.succeededFuture(0)`. | No — erased |

##### SubscriptionManager.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 50 | 189 | **ERASURE** | Auto-backfill after subscribe with FROM_BEGINNING fails → logs warning, returns `Future.succeededFuture()`. Subscription was created but backfill silently failed. | No — erased |
| 51 | 411 | **ERASURE** | Cancel cleanup after subscription cancellation fails → logs warning, returns `Future.succeededFuture()`. Cancel succeeded but cleanup was lost. | No — erased |
| 52 | 587 | **ERASURE** | Resurrection re-backfill fails → logs warning, returns `Future.succeededFuture()`. Heartbeat succeeded but re-backfill silently failed. | No — erased |

##### SqlTemplateProcessor.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 53 | 65 | **RE-WRAPS-FAILURE** | SQL file execution fails → logs error, re-throws as `Future.failedFuture(new RuntimeException(...))`. | Yes |

##### PeeGeeQDatabaseSetupService.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 54 | 195 | **RE-WRAPS-FAILURE** | Database creation step fails → logs error, re-throws as `Future.failedFuture(new RuntimeException("Database creation failed", ex))`. | Yes |
| 55 | 252 | **SELECTIVE-RECOVERY** | `createCompleteSetup()` outer recover → checks if conflict error → wraps as `DatabaseCreationConflictException`; otherwise cleans up and re-throws. Should use SQL-level `IF NOT EXISTS` / `ON CONFLICT`. | Partially |
| 56 | 266 | **ERASURE-IN-SHUTDOWN** | Cleanup after setup failure → logs error, returns `Future.succeededFuture()`. Cleanup failure during error recovery is swallowed. | No — erased |
| 57 | 566 | **ERASURE-IN-SHUTDOWN** | `destroySetup()` → `manager.closeReactive().recover(error -> Future.succeededFuture())` — manager close failure during teardown is swallowed. | No — erased |
| 58 | 774 | **RE-WRAPS-FAILURE** | `addEventStore()` fails → re-throws as `Future.failedFuture(new RuntimeException(...))`. | Yes |
| 59 | 1111 | **ERASURE-IN-SHUTDOWN** | `close()` → each setup destroy failure → warn, returns `Future.succeededFuture()`. | No — erased |

##### DatabaseTemplateManager.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 60 | 90 | **SELECTIVE-RECOVERY** | Database creation: if conflict error (`already exists`), succeeds (idempotent); otherwise re-throws. Should use SQL-level `IF NOT EXISTS`. | Yes (for non-conflict errors) |

##### DeadConsumerGroupCleanup.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 61 | 217 | **TYPED-ERASURE** | `cleanupAllDeadGroups()` individual group cleanup fails → logs error, adds zero-result to list, continues batch. Caller sees fabricated zero-result per failed group. | No — fabricated |

##### DeadConsumerDetectionJob.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 62 | 199 | **ERASURE-IN-SHUTDOWN** | `stop()` → awaits in-flight detection `.recover(e -> Future.succeededFuture())`. | No — erased (shutdown) |
| 63 | 363 | **ERASURE-IN-SHUTDOWN** | `inFlightDetection` tracking: `.recover(e -> Future.succeededFuture())` for fire-and-forget reference. | No — erased |

##### MultiConfigurationManager.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 64 | 172 | **RE-WRAPS-FAILURE** | `start()` fails → sets state to STOPPED, logs error, re-throws via `Future.failedFuture(e)`. | Yes |
| 65 | 333 | **ERASURE-IN-SHUTDOWN** | `close()` → some configs fail to close → warns, returns `Future.succeededFuture()`. | No — erased |

**Test code: 30+ instances across 15 test classes.** Predominantly teardown cleanup and test-specific error handling.

---

#### Module 5: `peegeeq-examples`

**Production code:** No `.recover()` calls.

---

#### Module 6: `peegeeq-examples-spring`

**Production code:** No `.recover()` calls. One comment in `ReactiveBiTemporalAdapter.java` (line 141) mentions `.recover()` in documentation but does not call it.

---

#### Module 7: `peegeeq-integration-tests`

No `.recover()` calls.

---

#### Module 8: `peegeeq-management-ui`

No Java source files. Frontend module.

---

#### Module 9: `peegeeq-migrations`

No `.recover()` calls. SQL migration files only.

---

#### Module 10: `peegeeq-native`

**Production code: 4 classes, 9 instances.**

##### PgNativeQueueProducer.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 66 | 312 | **SELECTIVE-RECOVERY** | Checks if the error is a duplicate idempotency key violation (`idx_queue_messages_idempotency_key`). If so, returns success (message already exists). All other errors re-thrown. Should use `INSERT ... ON CONFLICT DO NOTHING`. | Yes (non-idempotency errors propagate) |

##### PgNativeQueueConsumer.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 67 | 280 | **ERASURE-IN-SHUTDOWN** | During `connection.close()` after UNLISTEN during shutdown: `.recover(ignore -> Future.succeededFuture())`. Ignores connection close errors. | No — erased (narrow scope, single resource) |

##### PgNativeConsumerGroup.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 68 | 246 | **ERASURE-IN-SHUTDOWN** | During startup abort: `partitionedEngine.stop().recover(stopErr -> Future.succeededFuture())` — if stop fails during startup abort, error is swallowed so the "closed during startup" failure can propagate. | No — erased (cleanup during abort) |
| 69 | 256 | **RE-WRAPS-FAILURE** | Logs error and resets state from STARTING to NEW, then re-throws via `Future.failedFuture(err)`. | Yes |
| 70 | 276 | **PROPER-FALLBACK** | `isOffsetWatermarkTopic()` fails → falls back to reference counting mode with `Future.succeededFuture(false)`. Should use `.transform()` or let caller handle. | No — wrong layer |
| 71 | 371 | **RE-WRAPS-FAILURE** | `subscriptionService.subscribe()` fails → resets state to NEW and re-throws via `Future.failedFuture(err)`. | Yes |
| 72 | 405 | **ERASURE** | `stopGracefully()` → cancel subscription fails → logs warning and returns `Future.succeededFuture()`, then continues to `stopInternal()`. Subscription cancellation error is silently erased. | No — erased |

##### PartitionedConsumerEngine.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 73 | 163 | **RE-WRAPS-FAILURE** | `resetAfterFailedStart(err)` sets running=false, tears down, and re-throws the original error via `Future.failedFuture(err)`. | Yes |
| 74 | 200 | **ERASURE-IN-SHUTDOWN** | `assignmentService.leaveGroup()` fails during teardown → logs warning, returns `Future.succeededFuture()`. | No — erased (shutdown cleanup) |

**Test code: 5 instances across 3 test classes.** Cleanup and test assertions about `.recover()` behavior.

---

#### Module 11: `peegeeq-openapi`

**Production code:** No `.recover()` calls. OpenAPI specification module.

---

#### Module 12: `peegeeq-outbox`

**Production code: 3 classes, 9 instances.**

##### OutboxFactory.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 75 | 325 | **PROPER-FALLBACK** | `isHealthyAsync()` fails → returns `Future.succeededFuture(false)`. Should use `.transform()`. | No — wrong layer |
| 76 | 462 | **TYPED-ERASURE** | `getStatsAsync()` fails → returns fallback basic stats. Caller sees fabricated numbers. | No — fabricated |
| 77 | 555 | **ERASURE-IN-SHUTDOWN** | `closeTrackedResourcesAsync()`: consumer close fails → logs warning, returns `Future.succeededFuture()`. | No — erased |
| 78 | 559 | **ERASURE-IN-SHUTDOWN** | `closeTrackedResourcesAsync()`: consumer group close fails → same pattern. | No — erased |

##### OutboxConsumerGroup.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 79 | 389 | **RE-WRAPS-FAILURE** | `startWithSubscription()`: subscription fails → resets state to NEW, re-throws. | Yes |
| 80 | 448 | **ERASURE** | `stopGracefully()`: cancel subscription fails → logs warning, returns `Future.succeededFuture()`, continues to `stopInternal()`. | No — erased |

##### OutboxConsumer.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 81 | 436 | **PROPER-FALLBACK** | Message processing fails → calls `markMessageFailed()` to update status in database. DLQ routing should be a framework responsibility, not inline `recover()`. | No — wrong layer |
| 82 | 510 | **SELECTIVE-RECOVERY** | Post-completion recover: `RejectedMessageException` → resets to PENDING; `MessageFilteredException` → resets to PENDING; retry logic for other failures → either retry or DLQ. Retry/DLQ routing should be framework infrastructure. | Varies — wrong layer |
| 83 | 871 | **ERASURE-IN-SHUTDOWN** | `closeAsync()`: waits for in-flight processing, `.recover(e -> Future.succeededFuture())` to proceed to pool close regardless. | No — erased (shutdown) |

**Test code: 8 instances across 3 test classes.** Example tests and retry resilience tests.

---

#### Module 13: `peegeeq-performance-test-harness`

**Production code: 1 class, 2 instances.**

##### PerformanceTestHarness.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 84 | 86 | **ERASURE** | Logs error on test suite failure, then adds failure to results and returns `Future.succeededFuture()`. The overall harness chain continues silently. | No — erased |
| 85 | 103 | **ERASURE** | Top-level harness execution error: logs, adds failure to aggregated results, returns `Future.succeededFuture(aggregatedResults)`. Error is swallowed. | No — erased |

---

#### Module 14: `peegeeq-rest`

**Production code: 4 classes, 22 instances.**

##### SystemMonitoringHandler.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 86 | 409 | **TYPED-ERASURE** | Metrics collection fails → returns cached/minimal runtime metrics as fallback. Caller sees stale data with no indication it is stale. | No — fabricated |
| 87 | 507 | **TYPED-ERASURE** | `collectMetricsFromServices()` fails → returns minimal metrics with error message embedded in JSON. Error is in the data, not in the Future. | No — fabricated |
| 88 | 567 | **TYPED-ERASURE** | `collectSetupMetrics()` fails → returns accumulator unchanged (skips this setup). Aggregate metrics are silently incomplete. | No — fabricated |
| 89 | 623 | **TYPED-ERASURE** | `collectTopicSubscriptionMetrics()` fails → returns accumulator unchanged. Aggregate silently incomplete. | No — fabricated |

##### QueueHandler.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 90 | 239 | **TYPED-ERASURE** | Batch message send: individual message fails → if `failOnError=false`, returns "FAILED:" marker string. Caller sees a string that looks like a result. | No — fabricated marker |

##### ManagementApiHandler.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 91 | 158 | **RE-WRAPS-FAILURE** | `getRealQueues()` fails → re-throws as `RuntimeException`. | Yes |
| 92 | 209 | **TYPED-ERASURE** | `getQueuesForSetup()` fails → returns empty `JsonArray()`. Caller sees "no queues" instead of "error loading queues." | No — fabricated |
| 93 | 427 | **TYPED-ERASURE** | `getConsumerGroupsForSetup()` topic subscription list fails → returns empty `JsonArray()`. | No — fabricated |
| 94 | 446 | **TYPED-ERASURE** | Same setup-level recover for consumer groups. | No — fabricated |
| 95 | 465 | **RE-WRAPS-FAILURE** | `getRealConsumerGroups()` fails → re-throws. | Yes |
| 96 | 589 | **TYPED-ERASURE** | `getRealEventCount()` fails → returns `0L`. Caller displays "0 events" — fabricated. | No — fabricated |
| 97 | 627 | **TYPED-ERASURE** | `getRealAggregateCount()` fails → returns `0L`. Caller displays "0 aggregates" — fabricated. | No — fabricated |
| 98 | 643 | **TYPED-ERASURE** | `getRealCorrectionCount()` fails → returns `0L`. Caller displays "0 corrections" — fabricated. | No — fabricated |
| 99 | 750 | **RE-WRAPS-FAILURE** | `getRealEventStores()` fails → re-throws. | Yes |
| 100 | 795 | **TYPED-ERASURE** | `getEventStoresForSetup()` fails → returns empty `JsonArray()`. Caller sees "no event stores" — fabricated. | No — fabricated |
| 101 | 813 | **TYPED-ERASURE** | `getRealMessages()` fails → returns empty `JsonArray()`. Caller sees "no messages" — fabricated. | No — fabricated |
| 102 | 856 | **TYPED-ERASURE** | `getRecentActivity()` fails → returns empty `JsonArray()`. Caller sees "no activity" — fabricated. | No — fabricated |
| 103 | 900 | **TYPED-ERASURE** | `getRecentActivityForSetup()` → store query fails → returns empty list. | No — fabricated |
| 104 | 951 | **TYPED-ERASURE** | `getRecentActivityForSetup()` → setup not found → returns empty list. | No — fabricated |
| 105 | 1618 | **TYPED-ERASURE** | `getRealConsumerCount()` fails → returns `0`. Caller displays "0 consumers" — fabricated. | No — fabricated |
| 106 | 1733 | **TYPED-ERASURE** | Subscription listing for queue details fails → returns empty list. Caller sees "no subscriptions" — fabricated. | No — fabricated |

##### ConsumerGroupHandler.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 107 | 912 | **TYPED-ERASURE** | Subscription lookup for consumer group options fails → returns `null` (caller uses defaults). Caller cannot distinguish "no subscription" from "lookup failed." | No — fabricated |

**Test code: 5 instances across 2 test classes.** SSE streaming test and example tests.

---

#### Module 15: `peegeeq-rest-client`

**Production code: 1 class, 2 instances.**

##### PeeGeeQRestClient.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 108 | 792 | **RE-WRAPS-FAILURE** | `.recover(this::handleNetworkError)` converts network errors into `PeeGeeQNetworkException` and re-throws via `Future.failedFuture()`. | Yes |
| 109 | 798 | **SELECTIVE-RECOVERY** | Retry logic: if the error is retryable and within retry limit, schedules a retry; otherwise re-throws the original error. Retry should be middleware, not inline `recover()`. | Yes (non-retryable errors propagate) |

---

#### Module 16: `peegeeq-runtime`

**Production code:** No `.recover()` calls.

---

#### Module 17: `peegeeq-service-manager`

**Production code: 4 classes, 8 instances.**

##### ConnectionRouter.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 110 | 105 | **PROPER-FALLBACK** | Retry with failover: request fails → if retries remain, selects another instance and retries; if max retries exceeded, propagates failure. Failover belongs in retry middleware. | Yes (at retry exhaustion) |

##### PeeGeeQServiceManager.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 111 | 93 | **ERASURE** | Consul registration fails → logs warning, returns `Future.succeededFuture()`. Service starts even if Consul registration fails. | No — erased (arguably acceptable: Consul is optional) |

##### HealthMonitor.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 112 | 165 | **PROPER-FALLBACK** | Health check fails → increments failure counter, marks as UNHEALTHY/UNKNOWN, returns `HealthCheckResult` with error info. Should use `.transform()`. | No — wrong layer |

##### FederatedManagementHandler.java

| # | Line | Classification | Description | Error propagated? |
|---|---|---|---|---|
| 113 | 388 | **TYPED-ERASURE** | `fetchInstanceOverview()` fails → returns error response JSON as succeeded Future. HTTP 200 with error payload instead of proper error status. | No — fabricated |
| 114 | 397 | **TYPED-ERASURE** | `fetchInstanceQueues()` fails → returns error response JSON as succeeded Future. | No — fabricated |
| 115 | 406 | **TYPED-ERASURE** | `fetchInstanceConsumerGroups()` fails → returns error response JSON as succeeded Future. | No — fabricated |
| 116 | 415 | **TYPED-ERASURE** | `fetchInstanceEventStores()` fails → returns error response JSON as succeeded Future. | No — fabricated |
| 117 | 424 | **TYPED-ERASURE** | `fetchInstanceMetrics()` fails → returns error response JSON as succeeded Future. | No — fabricated |

---

#### Module 18: `peegeeq-test-support`

**Production code:** No `.recover()` calls.

---

### Per-module summary

| Module | Production instances | ERASURE | ERASURE-IN-SHUTDOWN | RE-WRAPS | SELECTIVE | PROPER-FALLBACK | TYPED-ERASURE |
|---|---|---|---|---|---|---|---|
| `peegeeq-api` | 0 | | | | | | |
| `peegeeq-bitemporal` | 6 | 0 | 5 | 1 | 0 | 0 | 0 |
| `peegeeq-coverage-report` | 0 | | | | | | |
| `peegeeq-db` | 59 | 12 | 16 | 16 | 4 | 10 | 1 |
| `peegeeq-examples` | 0 | | | | | | |
| `peegeeq-examples-spring` | 0 | | | | | | |
| `peegeeq-integration-tests` | 0 | | | | | | |
| `peegeeq-management-ui` | 0 | | | | | | |
| `peegeeq-migrations` | 0 | | | | | | |
| `peegeeq-native` | 9 | 1 | 3 | 3 | 1 | 1 | 0 |
| `peegeeq-openapi` | 0 | | | | | | |
| `peegeeq-outbox` | 9 | 1 | 3 | 1 | 1 | 2 | 1 |
| `peegeeq-performance-test-harness` | 2 | 2 | 0 | 0 | 0 | 0 | 0 |
| `peegeeq-rest` | 22 | 0 | 0 | 3 | 0 | 0 | 19 |
| `peegeeq-rest-client` | 2 | 0 | 0 | 1 | 1 | 0 | 0 |
| `peegeeq-runtime` | 0 | | | | | | |
| `peegeeq-service-manager` | 8 | 1 | 0 | 0 | 0 | 2 | 5 |
| `peegeeq-test-support` | 0 | | | | | | |
| **Totals** | **117** | **17** | **27** | **25** | **7** | **15** | **26** |

**100% of `.recover()` uses in this codebase are wrong.** Every instance is either
using the wrong Vert.x API or implementing logic that belongs at a different
architectural layer. There are 0 legitimate uses out of 117 production instances.

---

### Critical ERASURE findings (non-shutdown operational code)

These are the most dangerous errors silently swallowed in code paths where callers
depend on the Future's outcome:

| # | Module | Class | Line | Impact |
|---|---|---|---|---|
| 19 | peegeeq-db | PeeGeeQMetrics | 672 | Metric count query failures return zero silently |
| 20 | peegeeq-db | PeeGeeQMetrics | 705 | Metric persistence failures swallowed |
| 44 | peegeeq-db | StuckMessageRecoveryManager | 104 | **Stuck message recovery failures return zero** — caller thinks nothing was stuck |
| 45 | peegeeq-db | StuckMessageRecoveryManager | 132 | Count query failure hidden |
| 46 | peegeeq-db | StuckMessageRecoveryManager | 164 | Reset failure hidden — stuck messages stay stuck forever |
| 48 | peegeeq-db | StuckMessageRecoveryManager | 221 | Recovery stats fabricated |
| 49 | peegeeq-db | StuckMessageRecoveryManager | 241 | Count fabricated |
| 50 | peegeeq-db | SubscriptionManager | 189 | **FROM_BEGINNING backfill silently fails** — subscription appears complete but data is missing |
| 51 | peegeeq-db | SubscriptionManager | 411 | **Cancel cleanup silently fails** — orphan rows and zombie tracking data remain |
| 52 | peegeeq-db | SubscriptionManager | 587 | **Resurrection re-backfill silently fails** — resurrected consumer misses messages |
| 72 | peegeeq-native | PgNativeConsumerGroup | 405 | Subscription cancellation failure during graceful stop lost |
| 80 | peegeeq-outbox | OutboxConsumerGroup | 448 | Subscription cancellation failure during graceful stop lost |
| 84 | peegeeq-performance-test-harness | PerformanceTestHarness | 86 | Test suite failures hidden in aggregated results |
| 85 | peegeeq-performance-test-harness | PerformanceTestHarness | 103 | Top-level execution failure swallowed |
| 111 | peegeeq-service-manager | PeeGeeQServiceManager | 93 | Consul registration failure hidden (lower risk — Consul is optional) |

---

### TYPED-ERASURE findings (fabricated data disguised as real results)

These are the instances previously misclassified as "PROPER-FALLBACK" that are
actually returning fabricated data. The caller receives a succeeded Future with
a type-correct result and has no way to know the data is fake.

#### ManagementApiHandler — 13 instances (peegeeq-rest)

The management UI dashboard calls these endpoints. Every failure returns empty
JSON or zero counts. The dashboard shows "0 events", "0 queues", "no messages",
"no activity" — indistinguishable from a system with no data. An operator looking
at this dashboard during an outage sees a clean, empty system instead of errors.

| # | Line | Returns | What the caller sees |
|---|---|---|---|
| 92 | 209 | `new JsonArray()` | "No queues" |
| 93 | 427 | `new JsonArray()` | "No topic subscriptions" |
| 94 | 446 | `new JsonArray()` | "No consumer groups" |
| 96 | 589 | `0L` | "0 events" |
| 97 | 627 | `0L` | "0 aggregates" |
| 98 | 643 | `0L` | "0 corrections" |
| 100 | 795 | `new JsonArray()` | "No event stores" |
| 101 | 813 | `new JsonArray()` | "No messages" |
| 102 | 856 | `new JsonArray()` | "No recent activity" |
| 103 | 900 | `empty list` | "No activity for this setup" |
| 104 | 951 | `empty list` | "Setup not found" (actually: query failed) |
| 105 | 1618 | `0` | "0 consumers" |
| 106 | 1733 | `empty list` | "No subscriptions" |

#### SystemMonitoringHandler — 4 instances (peegeeq-rest)

| # | Line | Returns | What the caller sees |
|---|---|---|---|
| 86 | 409 | cached/minimal metrics | Stale metrics with no staleness indicator |
| 87 | 507 | minimal metrics + error msg in JSON | Error buried inside JSON data, not in HTTP status |
| 88 | 567 | accumulator unchanged | Aggregate metrics missing a setup silently |
| 89 | 623 | accumulator unchanged | Aggregate metrics missing subscriptions silently |

#### FederatedManagementHandler — 5 instances (peegeeq-service-manager)

HTTP 200 with error JSON payload instead of proper HTTP error status code.

| # | Line | Returns |
|---|---|---|
| 113 | 388 | Error response JSON |
| 114 | 397 | Error response JSON |
| 115 | 406 | Error response JSON |
| 116 | 415 | Error response JSON |
| 117 | 424 | Error response JSON |

#### Other TYPED-ERASURE instances

| # | Module | Class | Line | Returns | Impact |
|---|---|---|---|---|---|
| 61 | peegeeq-db | DeadConsumerGroupCleanup | 217 | zero-result per failed group | Batch result undercounts cleaned groups |
| 76 | peegeeq-outbox | OutboxFactory | 462 | fallback basic stats | Stats numbers fabricated |
| 90 | peegeeq-rest | QueueHandler | 239 | "FAILED:" marker string | Caller gets string that is not a real message ID |
| 107 | peegeeq-rest | ConsumerGroupHandler | 912 | `null` | Caller falls through to defaults; cannot distinguish "no subscription" from "lookup failed" |

---

## Appendix: Vert.x 5.x failure-handling cheat sheet

### The rule

**Default to propagation, not recovery.**

A Vert.x `Future` already propagates failure unless you intercept it. `compose(...)` only runs on success; if the upstream future fails, the failure is propagated. `recover(...)` handles failure by switching to another `Future`. `onFailure(...)`, `onSuccess(...)`, and `onComplete(...)` are terminal operations.

---

### 1. Normal async pipeline

```java
return step1()
  .compose(this::step2)
  .compose(this::step3);
```

Use this for the normal path. `step2` runs only if `step1` succeeded. `step3` runs only if `step2` succeeded. Any failure automatically propagates downstream.

---

### 2. Log the error without changing the outcome

```java
return step1()
  .compose(this::step2)
  .onFailure(err -> log.error("Pipeline failed", err));
```

Use `onFailure(...)` for logging, metrics, tracing, alerts, and similar side effects.

Do **not** use it as business control flow. It is terminal, and Vert.x warns there is no guarantee multiple terminal handlers run in registration order.

**Meaning:** log it, then let the failure stay failed.

---

### 3. Real fallback only: `recover(...)`

```java
return cacheGet(key)
  .recover(err -> {
    log.warn("Cache failed, falling back to DB", err);
    return dbGet(key);
  });
```

Use `recover(...)` only when you have a **genuine alternate source or business fallback**. Vert.x defines it as handling a failure by returning another future.

Good examples: cache → database, primary endpoint → secondary endpoint, optional enrichment fails → load reduced but still valid response.

Bad example:

```java
return doCriticalWork()
  .recover(err -> Future.succeededFuture(null));
```

That converts failure into fake success.

---

### 4. Dangerous methods: `otherwise(...)` and `otherwiseEmpty()`

```java
return doWork().otherwise("default-value");
return doWork().otherwiseEmpty();
```

These explicitly map failure into a value, including `null`. Use them only when a default value is genuinely correct. Most of the time they hide real faults, destroy signal, create downstream ambiguity, and lead to "why is this null?" debugging hell.

---

### 5. Async `finally`: `eventually(...)`

```java
return doWork()
  .eventually(() -> {
    timer.close();
    return Future.succeededFuture();
  });
```

Use `eventually(...)` for cleanup, timing, and "always run this" work. The mapper's outcome does **not** change the original success/failure nature of the returned future. This is the closest reactive equivalent to Java `finally`.

Good uses: stop timer, release non-critical resource, emit metrics, audit completion. Not for hiding the original error or replacing actual failure handling.

> **Caveat — thread-affinity-guarded `close()`:** `.eventually(...)` callbacks run on
> the event-loop that completed the upstream future. Resources whose `close()` refuses
> to run on an event-loop thread (notably every `QueueFactory` implementation in this
> project — `OutboxFactory`, `PgNativeQueueFactory`) must **not** be closed inside
> `.eventually(...)`. Doing so logs `Error closing queue factory: Do not call blocking
> close() on event-loop thread` and leaves the resource open. Close such resources
> from `@AfterEach` (JUnit worker thread) instead. Full antipattern entry:
> `PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md` →
> *"`.eventually(factory::close)` for `QueueFactory` Logs Cleanup Errors and Skips Cleanup"*.

---

### 6. Handle both success and failure in one place: `transform(...)`

```java
return callRemote()
  .transform((result, err) -> {
    if (err != null) {
      return Future.failedFuture(
        new ServiceUnavailableException("Remote call failed", err)
      );
    }
    return Future.succeededFuture(convert(result));
  });
```

Use `transform(...)` when you need one branch for either outcome and must return a new future. Typical use: wrap low-level exceptions in domain exceptions, normalize success and failure into another async type, adapt one API boundary to another.

---

### 7. The try/catch mapping

| Java | Vert.x |
|---|---|
| `try { ... }` | `.compose(this::step2).compose(this::step3)` |
| `catch (Throwable t) { return fallback(t); }` | `.recover(this::fallback)` |
| `finally { cleanup(); }` | `.eventually(this::cleanup)` |

---

### 8. HTTP handlers: fail the routing context

```java
router.get("/users/:id").handler(ctx -> {
  loadUser(ctx.pathParam("id"))
    .onSuccess(user -> ctx.json(user))
    .onFailure(ctx::fail);
});

router.route().failureHandler(ctx -> {
  Throwable err = ctx.failure();
  int status = ctx.statusCode() > 0 ? ctx.statusCode() : 500;
  log.error("Request failed", err);
  ctx.response().setStatusCode(status).end("Request failed");
});
```

This is the proper web pattern: async operation fails → call `ctx.fail(err)` → centralize HTTP error mapping in failure handlers. Do **not** scatter ad hoc `setStatusCode(...).end(...)` error handling all over every route.

---

### 9. Validation / domain failure

For expected business failures, fail explicitly.

```java
return validate(request)
  .compose(valid -> {
    if (!valid) {
      return Future.failedFuture(new IllegalArgumentException("Invalid request"));
    }
    return process(request);
  });
```

This is better than returning fake defaults.

---

### 10. Composite failures

```java
return Future.all(f1, f2, f3);   // fail fast when any fails
return Future.join(f1, f2, f3);  // wait for all, then decide
return Future.any(f1, f2, f3);   // succeed when any one succeeds
```

Use `all` for strict dependency sets, `join` when you need all outcomes collected before deciding, `any` for race / first-success patterns.

---

### 11. The blunt rules

1. **Use `compose(...)` for the happy path.**
2. **Let failures propagate by default.**
3. **Use `onFailure(...)` for side effects only.**
4. **Use `recover(...)` only for a real alternate path.**
5. **Treat `otherwise(...)` and `otherwiseEmpty()` as hazardous.**
6. **Use `eventually(...)` for cleanup.**
7. **In Vert.x Web, convert async failure to `ctx.fail(...)`.**
8. **Centralize HTTP failure mapping in failure handlers.**
9. **Use `executeBlocking(...)` for blocking code, not the event loop.**
10. **Do not fake success when the system has actually failed.**

---

### 12. Default production pattern

```java
return doSomething(request)
  .compose(this::enrich)
  .compose(this::persist)
  .onFailure(err -> log.error("Operation failed for request {}", request.id(), err));
```

Then only add one of these when truly needed:

* `recover(...)` for fallback
* `eventually(...)` for cleanup
* `transform(...)` for full success/failure remapping
* `ctx.fail(...)` at the HTTP boundary

---

### References

[1]: https://vertx.io/docs/apidocs/io/vertx/core/Future.html "Future (Vert.x Stack - Docs 5.0.10 API)"
[2]: https://vertx.io/docs/vertx-web/java/ "Vert.x Web | Eclipse Vert.x"
[3]: https://vertx.io/docs/apidocs/io/vertx/core/Context.html "Context (Vert.x Stack - Docs 5.0.10 API)"
[4]: https://vertx.io/docs/apidocs/io/vertx/core/Vertx.html "Vertx (Vert.x Stack - Docs 5.0.10 API)"

**Fix Violations**
```bash
# Run migration script (Linux/Mac)
./scripts/postgres-migration/migrate-postgresql-versions.sh

# Run migration script (Windows)
powershell -ExecutionPolicy Bypass -File ./scripts/postgres-migration/migrate-postgresql-versions.ps1
```

## Testing Checklist for Each Increment

### **Before Implementation**
- [ ] Review plan requirements for testing specifications
- [ ] Identify all database tables that will be affected
- [ ] Plan test scenarios covering happy path and edge cases
- [ ] Design correlation mechanisms for cross-table validation
- [ ] Import `dev.mars.peegeeq.test.PostgreSQLTestConstants`
- [ ] Plan TestContainers setup using standard patterns

### **During Implementation**
- [ ] Compile after each code change
- [ ] Run unit tests after each logical unit
- [ ] Test parameter validation thoroughly
- [ ] Verify error handling paths
- [ ] Use `PostgreSQLTestConstants.createStandardContainer()` or variants
- [ ] Add `@SuppressWarnings("resource")` to static containers

### **After Implementation**
- [ ] Run comprehensive integration tests
- [ ] Validate database state for all affected tables
- [ ] Check cross-table data consistency
- [ ] Verify temporal aspects (timing, valid_time, transaction_time)
- [ ] Test rollback scenarios (if applicable)
- [ ] Performance validation (if specified in plan)
- [ ] Implement property save/restore pattern in `@BeforeEach`/`@AfterEach`
- [ ] Prefer `PeeGeeQTestConfig.builder()` over `System.setProperty` for pool/connection config in concurrent tests
- [ ] Use standard `peegeeq.database.*` property names
- [ ] No hardcoded PostgreSQL versions in code

### **Increment Completion Criteria**
- [ ] All unit tests pass
- [ ] All integration tests pass
- [ ] Database state validation confirms expected behavior
- [ ] Cross-table consistency verified
- [ ] Temporal data correctly stored and retrievable
- [ ] No resource leaks or cleanup issues
- [ ] Performance meets requirements (if specified)
- [ ] TestContainers standards compliance verified

## TestContainers Benefits

1. **Single PostgreSQL Version**: Only `postgres:15.13-alpine3.20` across entire project
2. **Consistent Configuration**: Standard property names and patterns
3. **Clean Test Isolation**: Proper property cleanup between tests
4. **Maintainable**: Easy to update PostgreSQL version project-wide
5. **No Docker Pollution**: Prevents multiple PostgreSQL images
6. **Standardized Setup**: All tests use the same container configuration
7. **Performance Optimized**: High-performance containers available for performance tests

## New Test Checklist (Complete)

### **TestContainers Standards Compliance**
- [ ] Import `dev.mars.peegeeq.test.PostgreSQLTestConstants`
- [ ] Use `PostgreSQLTestConstants.createStandardContainer()` or variants
- [ ] Add `@SuppressWarnings("resource")` to static containers
- [ ] Implement property save/restore pattern in `@BeforeEach`/`@AfterEach`
- [ ] Prefer `PeeGeeQTestConfig.builder()` over `System.setProperty` for pool/connection config in concurrent tests
- [ ] Use standard `peegeeq.database.*` property names
- [ ] Test compiles and runs successfully
- [ ] No hardcoded PostgreSQL versions in code

### **Core Testing Requirements**
- [ ] Unit tests for all public methods
- [ ] Integration tests with TestContainers
- [ ] Database state validation for all affected tables
- [ ] Cross-table consistency verification
- [ ] Temporal data validation (valid_time, transaction_time)
- [ ] Transaction rollback scenarios (if applicable)
- [ ] Error handling and edge cases
- [ ] Performance validation (if specified)

## Commitment to Quality

**Every increment must meet these comprehensive testing standards before being considered complete.** This ensures:
- Robust, production-ready code
- Comprehensive validation of all functionality
- Early detection of integration issues
- Confidence in database consistency and transaction behavior
- Maintainable test suite for regression testing
- Consistent TestContainers usage across all modules
- Clean Docker environment without image pollution
- Standardized test patterns for all developers

**Testing is not optional - it is integral to the development process. TestContainers standards are mandatory for all database-related tests.**
