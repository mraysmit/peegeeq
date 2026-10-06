# PeeGeeQ Coding Principles and Standards

**Status:** CURRENT CODING STANDARD
**Last reconciled:** 2026-10-06 against commit f1c5d25d

This document is the coding standard for PeeGeeQ. `.claude/CLAUDE.md` requires every
implementation task to read it in full before any code is written. Every rule here is
mandatory. Where this document and `.claude/CLAUDE.md` overlap, both say the same thing;
if they ever diverge, treat the divergence as a defect and report it.

## Table of contents

1. [TestContainers mandatory policy](#1-testcontainers-mandatory-policy)
2. [Working principles](#2-working-principles)
3. [Vert.x 5 composable Future patterns](#3-vertx-5-composable-future-patterns)
4. [Forbidden reactive patterns](#4-forbidden-reactive-patterns)
5. [The `.recover()` ban](#5-the-recover-ban)
6. [Database write operations](#6-database-write-operations)
7. [Test infrastructure integrity](#7-test-infrastructure-integrity)
8. [Test execution validation](#8-test-execution-validation)
9. [Failure-handling reference](#9-failure-handling-reference)
10. [Multi-tenant schema isolation](#10-multi-tenant-schema-isolation)

---

## 1. TestContainers mandatory policy

### Principle: database-centric systems require real databases

PeeGeeQ is a PostgreSQL queue and event-store system. Almost every operation touches the
database.

- MANDATORY: use TestContainers for every test that performs a database operation.
- FORBIDDEN: mocking database connections, repositories, or SQL operations.
- FORBIDDEN: H2, HSQLDB, or any in-memory database as a PostgreSQL substitute.
- FORBIDDEN: skipping database tests because TestContainers is slow.
- FORBIDDEN: Mockito or reflection in any test.

There are no exceptions to this policy.

### Container declaration

Declare containers through the test-support factory. Do not construct a
`PostgreSQLContainer` with an inline image string. The image name lives in one place:
`PostgreSQLTestConstants.POSTGRES_IMAGE` in
`peegeeq-test-support/src/main/java/dev/mars/peegeeq/test/PostgreSQLTestConstants.java`.

```java
// CORRECT: integration test with a standard container
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class OutboxProducerCoreTest {
    @Container
    private static final PostgreSQLContainer postgres =
        PostgreSQLTestConstants.createStandardContainer();

    @Test
    void testSendMessage(VertxTestContext testContext) {
        // real database writes through OutboxProducer
    }
}

// CORRECT: pure logic test with no database
@Tag(TestCategories.CORE)
class CircuitBreakerRecoveryTest {
    // No @Testcontainers. Tests the state machine only.
    @Test
    void testStateTransitions() {
        // CLOSED -> OPEN -> HALF_OPEN
    }
}

// WRONG: database test tagged CORE and run without a container
@Tag(TestCategories.CORE)
class OutboxFactoryTest {
    @Test
    void testCreateProducer() {
        // creates a producer that accesses the database
        // MUST be @Tag(TestCategories.INTEGRATION) with a container
    }
}
```

Tests in `peegeeq-db` extend `BaseIntegrationTest`, which obtains a module-wide shared
container from `SharedPostgresTestExtension`
(`peegeeq-db/src/test/java/dev/mars/peegeeq/db/SharedPostgresTestExtension.java`). Use the
base class in that module. Use `PostgreSQLTestConstants.createStandardContainer()` elsewhere.

### Tag selection

- `@Tag(TestCategories.CORE)`: pure logic with zero database operations. Examples: circuit
  breaker transitions, filter predicates, configuration parsing. Runs under the default
  Maven settings.
- `@Tag(TestCategories.INTEGRATION)`: any test that touches the database. Examples:
  `Producer.send()`, `Consumer.subscribe()`, factory creation, message persistence.
  Requires a container. Runs under `-Pintegration-tests`.

Ask three questions. If any answer is yes, the test is INTEGRATION and needs a container.

1. Does the test create an `OutboxFactory`, `OutboxProducer`, or `OutboxConsumer`?
2. Does the test send, receive, or query messages?
3. Does the test involve `PeeGeeQManager` or `DatabaseSetupService`?

Objections and their answers:

- "It is too slow": run CORE for quick feedback and INTEGRATION before committing.
- "TestContainers is complex": copy an existing pattern such as `OutboxBasicTest`.
- "Docker is not available": fix the development environment.
- "It only tests configuration": if the configuration affects database behaviour, it needs
  a container.

---

## 2. Working principles

These principles come from defects found in this project. Each one names the mistake it
prevents.

1. **Investigate first.** Understand the root cause before changing anything. Adding
   "graceful" error handling to a failing test without understanding the failure hides the
   problem.
2. **Follow established patterns.** Read how existing tests and classes in the same module
   are structured before writing new ones. Do not invent a new pattern.
3. **Verify assumptions.** Read the test logs. Do not assume a test is doing what its name
   says.
4. **Fix root causes.** A database connection failure in a test is a missing-container
   problem, not "expected behaviour in the test environment".
5. **Document honestly.** Comments describe what the code does and requires. A comment that
   calls a configuration defect "expected behaviour" is a defect.
6. **Validate incrementally.** Make one change, rebuild, run the targeted test, read the
   log, then make the next change.
7. **Classify tests clearly.** CORE tests have no database. INTEGRATION tests have a real
   PostgreSQL container.
8. **Fail honestly.** A test that catches an exception, logs a warning, and returns has
   not tested anything. Let it fail and fix the cause.
9. **Read logs carefully.** `UnknownHostException: test-host` means the test needs a real
   host from a container. It does not mean the environment is wrong.
10. **Use Vert.x 5 composable Futures.** `.compose()`, `.onSuccess()`, `.onFailure()`,
    `.eventually()`, `.transform()`. Never callbacks.

Additional mandatory rules:

- Do not rely on the Maven exit code. Read the per-class `Tests run:` lines.
- Dependent PeeGeeQ modules must be installed to the local Maven repository before a
  downstream module is tested. The rebuild command is in
  `docs-design/testing/PEEGEEQ-TEST-COMMANDS.md`.
- `-DskipTests` is permitted only for the `mvn clean install -DskipTests -pl :<module> -am`
  rebuild step, as `.claude/CLAUDE.md` and `PEEGEEQ-TEST-COMMANDS.md` require. It is never
  permitted as a way to avoid a failing test.
- Never skip a failing test. `@Disabled` requires a row in
  `peegeeq-test-support/src/test/resources/quality/disabled-tests-allowlist.csv` with a
  rationale; `DisabledTestsGuardTest` fails the build otherwise.
- Do not continue to the next step until the targeted tests pass.
- Development happens on Windows 11 with PowerShell. Pipe every Maven run through
  `2>&1 | Tee-Object -FilePath logs\<name>-<date>.log`.

---

## 3. Vert.x 5 composable Future patterns

### Principle: use composable Futures, not callbacks

Every asynchronous operation uses the Vert.x 5 `Future<T>` API. Chains compose with
`.compose()`. Terminal observation uses `.onSuccess()` and `.onFailure()`.

```java
// WRONG: nested callbacks
server.listen(8080, ar -> {
    if (ar.succeeded()) {
        doWarmup(warmupResult -> {
            if (warmupResult.succeeded()) {
                registerWithRegistry(registryResult -> { /* ... */ });
            }
        });
    }
});

// CORRECT: composable chain
server.listen(8080)
    .compose(s -> doWarmup())              // returns Future<Void>
    .compose(v -> registerWithRegistry())  // returns Future<Void>
    .onSuccess(v -> logger.info("Server is ready"))
    .onFailure(e -> logger.error("Startup failed", e));
```

### Pattern: optional step that must not stop the chain

Use `.onFailure()` to log and `.transform()` to continue. This is the production pattern
in `peegeeq-service-manager/src/main/java/dev/mars/peegeeq/servicemanager/PeeGeeQServiceManager.java`
(Consul registration):

```java
return registerSelfWithConsul()
    .onFailure(throwable ->
        logger.warn("Failed to register with Consul (continuing without Consul): {}",
                throwable.getMessage()))
    .transform(ar -> Future.<Void>succeededFuture());
```

### Pattern: cleanup that must run regardless of outcome

`.eventually(Supplier<Future<U>>)` runs the supplier on success and on failure. The
supplier's outcome does not change the outer Future's outcome. `.compose()` skips
downstream stages after a failure, so it is wrong for teardown.

```java
// CORRECT: vertx.close() runs even if manager.closeReactive() fails,
// and a failure in either step fails the test.
manager.closeReactive()
    .eventually(() -> vertx.close())
    .onComplete(testContext.succeedingThenComplete());

// WRONG: vertx.close() is skipped when closeReactive() fails
manager.closeReactive()
    .compose(v -> vertx.close())
    .onComplete(testContext.succeedingThenComplete());
```

`BaseIntegrationTest.tearDownBaseIntegration` in `peegeeq-db` is the reference
implementation of this pattern. Its chain ends with `.onFailure(testContext::failNow)`.

### Pattern: test assertions on asynchronous results

```java
// WRONG: onComplete with manual success check
queue.send(message)
    .onComplete(ar -> {
        if (ar.succeeded()) {
            latch.countDown();
        } else {
            fail("Failed: " + ar.cause().getMessage());
        }
    });

// CORRECT: explicit success and failure handlers
queue.send(message)
    .onSuccess(v -> testContext.verify(() -> {
        assertNotNull(v);
        testContext.completeNow();
    }))
    .onFailure(testContext::failNow);
```

Assertions inside an `.onSuccess` body must be wrapped in `testContext.verify(...)`.
A bare assertion that throws inside the handler is routed to the Vert.x exception handler
and the test times out instead of failing with the assertion message.

### Explicit type parameters

```java
return Future.<Void>succeededFuture();
return Future.<Void>failedFuture("Error message");
```

---

## 4. Forbidden reactive patterns

These patterns are banned in production code, tests, and examples. The list is the same
list as `.claude/CLAUDE.md` Step 5.

| Pattern | Rule | Replacement |
|---|---|---|
| `.recover(` | Banned everywhere, no exceptions. | `.transform()` for optional steps; `.compose()` with explicit failure mapping; `.eventually()` for cleanup. See section 5. |
| `.otherwise(` | Banned everywhere, no exceptions. | Same as `.recover(`. |
| `.await(` on a `Future` | Banned in production and tests. | `.onSuccess(...).onFailure(testContext::failNow)` and `testContext.awaitCompletion(timeout, unit)` at the test boundary. A bounded `CountDownLatch.await(timeout, TimeUnit.X)` is allowed. |
| `CompletableFuture`, `toCompletionStage`, `toCompletableFuture`, `.join()`, `.get()` | Blocking bridges. Banned. | Stay on `Future<T>`. |
| `Thread.sleep`, `LockSupport.parkNanos` | Banned. | `vertx.timer(ms).compose(v -> nextStep())` for a delay; chain off the real Future for synchronisation. |
| `Handler<AsyncResult<...>>` | Callback style. Banned. | Return `Future<T>`. |
| `.onComplete(ar -> { if (ar.succeeded()) ... })` | Banned. | `.onSuccess(...)` and `.onFailure(...)`, or `.onComplete(testContext.succeedingThenComplete())`. |
| Fire-and-forget `Future` | Banned. | Every `Future` is chained or observed with `.onFailure(...)`. |
| Pass-on-failure test | Banned. | An `.onFailure(err -> { ... })` body must call `testContext.failNow(err)` or assert the expected failure inside `testContext.verify(...)`. This applies to `@AfterEach`. |
| `vertx.setTimer` as a readiness guard | Banned. | Chain directly off the asynchronous operation. |
| Raw JDBC in tests (`DriverManager`, `java.sql.Connection`) | Banned. | Vert.x `Pool` and `PgConnection`. |

```java
// WRONG: all of these are banned
future.recover(e -> Future.succeededFuture());
future.otherwise(fallbackValue);
future.await();
future.toCompletionStage().toCompletableFuture().get();
Thread.sleep(500);
LockSupport.parkNanos(200_000_000L);
vertx.setTimer(100, id -> testContext.completeNow());
producer.send("message");                       // result discarded
Connection conn = DriverManager.getConnection(url, user, pass);

// CORRECT
vertx.timer(500).compose(v -> nextStep());
producer.send("message").onFailure(testContext::failNow);
deployVerticle().compose(v -> doWork()).onSuccess(v -> testContext.completeNow());
pool.withTransaction(conn -> conn.preparedQuery("SELECT ...").execute(Tuple.of(id)));
```

### Enforcement

Five guard tests in
`peegeeq-test-support/src/test/java/dev/mars/peegeeq/test/quality/` enforce these rules at
build time. All five are `@Tag(TestCategories.CORE)` and run under plain `mvn test`.

| Guard | Enforces |
|---|---|
| `VertxAsyncForbiddenPatternsGuardTest` | `.recover(`, `.otherwise(`, `CompletableFuture`, `.toCompletionStage(`, `.toCompletableFuture(`, `Handler<AsyncResult`, and manual `new VertxTestContext(` in production and test sources, ratcheted against `quality/vertx-async-forbidden-baseline.csv` (zero rows). |
| `OnSuccessExceptionSwallowingGuardTest` | Bare assertions and `.close()` inside `.onSuccess`, `Future.await()`, `Thread.sleep`/`parkNanos`, `@Tag("blocking-exempt")`, `.onComplete` latch swallowing, discarded `stop()`/`close()`/`subscribe()` Futures, and `assertNotNull` on an async operation in test sources. |
| `DisabledTestsGuardTest` | `@Disabled` annotations not recorded in `quality/disabled-tests-allowlist.csv`. |
| `InvalidDurationLiteralGuardTest` | ISO-8601 duration literals written with a millisecond suffix (`PT`, digits, `MS`), which `Duration.parse` rejects. It scans Java, properties, YAML, JSON, XML, and every Markdown file under `docs` and `docs-design`, so the literal must not appear in documentation either. |
| `SchemaInitializerTestInfrastructureGuardTest` | Hand-built `new PostgreSQLContainer(` and raw JDBC imports in schema-initializer tests. |

`docs-design/testing/PEEGEEQ_TEST_GUARD.md` describes the guards and their baselines.

---

## 5. The `.recover()` ban

`.recover()` is banned everywhere. `.otherwise()` is banned for the same reason.

The reason: a `.recover()` handler that returns `Future.succeededFuture()` converts a
failed Future into a succeeded one. Every caller upstream sees success. The error is
discarded, not handled. A log line inside the handler has no operational effect. The
chain continues through stages that should never have run, and downstream failures are
disconnected from their cause.

Every use that was found in this codebase fell into one of these shapes, and each has a
correct replacement:

| Intent | Correct API |
|---|---|
| Log an error without changing the outcome | `.onFailure(e -> logger.warn(...))` |
| Cleanup that must run regardless of outcome | `.eventually(() -> resource.close())` |
| Optional step; warn on failure and continue | `.onFailure(e -> log).transform(ar -> Future.succeededFuture())` |
| Convert an error to a domain status (health check) | Let the Future fail; the caller renders the failure. |
| Idempotent insert or create | SQL-level `ON CONFLICT DO NOTHING` / `IF NOT EXISTS`. |
| Retry or failover | `CircuitBreakerManager` in `peegeeq-db` (Resilience4j), not inline recovery. |
| DLQ routing | The message-processing pipeline's own failure channel. |
| Return fabricated data (`0L`, empty `JsonArray`, `null`) on failure | Never. Propagate the error. |

Production and test code contain zero `.recover()` and zero `.otherwise()` calls
(verified 2026-10-06; the only `.recover` token in `src/main` is a Javadoc comment in
`peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/QueueHandler.java`). `VertxAsyncForbiddenPatternsGuardTest`
fails the build if either pattern is introduced. The 2025 file-by-file audit that
established the ban was removed from this document on 2026-10-06 because every instance it
listed has been remediated; it remains in git history.

---

## 6. Database write operations

### Principle: writes require transactions

All DML (`INSERT`, `UPDATE`, `DELETE`) runs inside `pool.withTransaction()`, never
`pool.withConnection()`.

`pool.withConnection()` borrows a connection but begins no transaction. A failure leaves
partial state with no rollback. `PgConnectionManager.withTransaction` also sets the
configured schema as transaction-local state, which transaction-pooling proxies require
(see `docs-design/schema-tenants-support/PEEGEEQ_SCHEMA_CONFIGURATION_DESIGN.md`).

```java
// WRONG: no transaction; no rollback on failure
return pool.withConnection(conn ->
    conn.preparedQuery("DELETE FROM outbox_messages WHERE id = $1")
        .execute(Tuple.of(id)));

// CORRECT: commit on success, rollback on failure
return pool.withTransaction(conn ->
    conn.preparedQuery("DELETE FROM outbox_messages WHERE id = $1")
        .execute(Tuple.of(id)));
```

Use `pool.withConnection()` only for read-only queries where auto-commit semantics are
acceptable.

---

## 7. Test infrastructure integrity

### Principle: test setup must be verified by tests

A misconfigured test base class degrades every downstream test without any single test
failing for the right reason. Test infrastructure therefore has contract tests.

### Rule 1: build configuration as an isolated `Properties` object

Do not write `System.setProperty`. Build a per-test `Properties` through
`PeeGeeQTestConfig.builder()` and pass it to `PeeGeeQConfiguration`. This is the pattern
in `peegeeq-db/src/test/java/dev/mars/peegeeq/db/BaseIntegrationTest.java` and in
`docs-design/testing/PEEGEEQ_TESTING_STANDARDS_PATTERNS.md`.

```java
Properties props = PeeGeeQTestConfig.builder()
        .from(postgres)
        .schema(PostgreSQLTestConstants.TEST_SCHEMA)
        .property("peegeeq.database.pool.max-size", "3")
        .property("peegeeq.database.pool.connection-timeout-ms", "30000")
        .property("peegeeq.database.pool.idle-timeout-ms", "5000")
        .property("peegeeq.database.pool.shared", "false")
        .build();
PeeGeeQConfiguration configuration = new PeeGeeQConfiguration(testProfile, props);
PeeGeeQManager manager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());
```

`from(postgres)` reads host, port, database, username, and password from the live
container. Never hard-code those values.

### Rule 2: property keys must exactly match what the loader reads

`PeeGeeQConfiguration.getPoolConfig()` reads `peegeeq.database.pool.idle-timeout-ms` as a
millisecond long. A Duration string under a different key is silently ignored and the
600000 ms default applies.

```java
// WRONG: key does not exist; loader falls back to the default
.property("peegeeq.database.pool.idle-timeout", "PT10S")

// CORRECT
.property("peegeeq.database.pool.idle-timeout-ms", "2000")
```

### Rule 3: test pools use `shared=false`

`peegeeq.database.pool.shared` defaults to `true`. With `shared=true`, `Pool.close()` uses
reference counting and does not deterministically release TCP sockets. Integration tests
need deterministic teardown, so every test configuration sets
`peegeeq.database.pool.shared` to `false`.

### Rule 4: secondary `PgConnectionManager` instances are closed in `@AfterEach`

A base class closes only its own manager. A test that creates a second
`PgConnectionManager` for verification queries closes it, and the close result is observed.

```java
@AfterEach
void tearDown(VertxTestContext testContext) {
    if (verificationConnectionManager == null) {
        testContext.completeNow();
        return;
    }
    verificationConnectionManager.close()
        .onComplete(testContext.succeedingThenComplete());
}
```

### Rule 5: test infrastructure has `@Tag(CORE)` contract tests

`BaseIntegrationTestPoolConfigContractCoreTest`
(`peegeeq-db/src/test/java/dev/mars/peegeeq/db/infrastructure/`) builds the same pool
properties the base class uses and asserts, with JUnit assertions, that
`PeeGeeQConfiguration.getPoolConfig()` reflects them:

```java
@Tag(TestCategories.CORE)
@Execution(ExecutionMode.SAME_THREAD)
class BaseIntegrationTestPoolConfigContractCoreTest {
    private Properties testProps;

    @BeforeEach
    void setUp() {
        testProps = new Properties();
        testProps.setProperty("peegeeq.database.pool.idle-timeout-ms", "2000");
        testProps.setProperty("peegeeq.database.pool.shared", "false");
    }

    @Test
    void poolConfigUsesShortIdleTimeoutForFastTeardown() {
        PeeGeeQConfiguration cfg = new PeeGeeQConfiguration("test-" + UUID.randomUUID(), testProps);
        assertEquals(Duration.ofMillis(2000), cfg.getPoolConfig().getIdleTimeout());
    }
}
```

This test runs under plain `mvn test` and catches property-key regressions before they
exhaust the connection pool. The project uses JUnit Jupiter assertions; AssertJ is not a
dependency of the Java modules.

---

## 8. Test execution validation

### Principle: verify that test methods are actually executing

Maven can report `Tests run: 0, Failures: 0, Errors: 0` with `BUILD SUCCESS`. That is not a
pass. Read the per-class `Tests run:` lines in the saved log.

Common causes of silent non-execution:

1. An INTEGRATION-tagged test run without `-Pintegration-tests`. The default settings run
   `core` only and exclude `integration`.
2. A TestContainers start-up failure that prevents the test method from running.
3. A test with no `@Tag`. Run `-Puntagged-tests` to find them.

```text
# Looks like success; nothing ran
mvn test -pl :peegeeq-outbox -Dtest=OutboxProducerCoreTest 2>&1 | Tee-Object -FilePath logs\outbox-core-20261006.log
[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0

# Runs the test
mvn test -pl :peegeeq-outbox -Dtest=OutboxProducerCoreTest -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\outbox-it-20261006.log
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
```

The profile set (`integration-tests`, `performance-tests`, `smoke-tests`, `all-tests`,
`untagged-tests`, `coverage`), the default tag filters, and the copy-paste command forms are
defined once in `docs-design/testing/PEEGEEQ-TEST-COMMANDS.md`. Do not compose Maven
commands from memory; copy them from that document. `-Pall-tests` is an owner-run release
gate, not part of the edit-test loop.

### Diagnostics when a test does not run

- Add `System.err.println("=== TEST METHOD STARTED ===")` at the top of the method and
  check the log for it.
- Run with `-X` to see Surefire's tag filtering and the forked command line.
- Log `postgres.isRunning()` and `postgres.getJdbcUrl()` in `@BeforeEach` when the
  container is suspected.

---

## 9. Failure-handling reference

### The rule

Default to propagation. A Vert.x `Future` propagates failure unless something intercepts
it. `.compose()` runs only on success. `.onFailure()`, `.onSuccess()`, and `.onComplete()`
are terminal observers.

### 1. Normal path

```java
return step1()
  .compose(this::step2)
  .compose(this::step3);
```

`step2` runs only if `step1` succeeded. Any failure propagates to the caller.

### 2. Log without changing the outcome

```java
return step1()
  .compose(this::step2)
  .onFailure(err -> log.error("Pipeline failed", err));
```

Use `.onFailure()` for logging, metrics, and alerts. It is terminal; it is not control
flow.

### 3. Cleanup that must run regardless of outcome

```java
return doWork()
  .eventually(() -> resource.close());
```

`.eventually()` is `finally`. The supplier runs on success and on failure and does not
change the outer outcome.

### 4. Optional step

```java
return doWork()
  .onFailure(err -> log.warn("doWork failed (optional step, continuing): {}", err.getMessage()))
  .transform(ar -> Future.succeededFuture());
```

Use `.transform()` only when the step is genuinely optional and the caller does not need to
know it failed.

### 5. Explicit failure

```java
return validate(request)
  .compose(valid -> {
    if (!valid) {
      return Future.failedFuture(new IllegalArgumentException("Invalid request"));
    }
    return process(request);
  });
```

Fail explicitly for expected business failures. Never return a fake default.

### 6. Expected database conflicts

Handle idempotency in SQL (`INSERT ... ON CONFLICT DO NOTHING`, `CREATE ... IF NOT EXISTS`).
Do not throw and then catch in the Future chain.

### 7. HTTP handlers

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

Call `ctx.fail(err)` and centralise HTTP error mapping in failure handlers. Do not return
HTTP 200 with an error payload.

### 8. Composite futures

- `Future.all(f1, f2, f3)`: succeeds when all succeed; fails as soon as one fails.
- `Future.join(f1, f2, f3)`: waits for all to complete before reporting failure.
- `Future.any(f1, f2, f3)`: succeeds as soon as one succeeds.

### 9. Blocking code

```java
return vertx.executeBlocking(() -> legacyBlockingCall());
```

Thrown exceptions fail the returned Future. Handle them in the normal chain.

### 10. The rules

1. Use `compose(...)` for the normal path.
2. Let failures propagate by default.
3. Use `onFailure(...)` for side effects only.
4. Use `eventually(...)` for cleanup.
5. Use `transform(...)` only for a genuinely optional step.
6. In Vert.x Web, convert async failure to `ctx.fail(...)`.
7. Use `executeBlocking(...)` for blocking code.
8. Never fake success when the system has failed.
9. Never use `recover(...)` or `otherwise(...)`.

---

## 10. Multi-tenant schema isolation

**Status: implemented.** The design record is
`docs-design/schema-tenants-support/PEEGEEQ_SCHEMA_CONFIGURATION_DESIGN.md`.

### Principle: one isolated schema per setup

Every table, function, trigger, template, and subscription record for a setup lives in
that setup's configured schema. The names `peegeeq` and `bitemporal` have no special
runtime meaning.

- MANDATORY: `peegeeq.database.schema` is the single configuration key for the schema.
  `PeeGeeQConfiguration` rejects an empty value. There is no implicit fallback.
- MANDATORY: SQL templates under `peegeeq-db/src/main/resources/db/templates/` use the
  `{schema}` placeholder for table qualification and schema creation.
- MANDATORY: Java code constructs fully qualified table names from the configured schema.
- MANDATORY: `ReactiveNotificationHandler` (`peegeeq-bitemporal`) requires a non-null
  schema in its constructor and prefixes LISTEN/NOTIFY channel names with it
  (`<schema>_bitemporal_events_<eventType>`).
- FORBIDDEN: hard-coded global schema names in SQL or Java.
- FORBIDDEN: unqualified table names in utility queries unless the connection's schema has
  been set through `PgConnectionManager`.
- FORBIDDEN: global notification channels that broadcast to every tenant in a shared
  database.
- FORBIDDEN: module-specific schema defaults.

### Verification

Every change that touches schema logic includes a multi-tenant isolation test. The test
uses a TestContainers PostgreSQL instance, creates two schemas (for example `tenant_a` and
`tenant_b`), and proves that `tenant_a` cannot `LISTEN` to `tenant_b`'s events or `SELECT`
from its tables.
