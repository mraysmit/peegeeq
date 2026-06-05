# REST Handler Error-Path Tests — TDD Implementation Plan

*Last updated: 2026-06-05*
*Status: **COMPLETE** — All cycles (A1–A9, B1–B3, C1–C8, D1–D4, E1–E3) implemented and GREEN. 458 tests, 0 failures.*

## Source-Verified Facts (Read Before Editing This Plan)

These were verified by reading source directly. Do not assume — re-read the source if a detail below
contradicts what you observe.

| Fact | Verified value |
|---|---|
| `PeeGeeQRestServer` constructor | `(RestServerConfig config, DatabaseSetupService setupService)` |
| `RestServerConfig` constructor | Java record: `(int port, MonitoringConfig monitoring, List<String> allowedOrigins)` |
| `RestServerConfig.MonitoringConfig.defaults()` | Exists ✓ |
| `isSetupNotFoundError` | Checks `.getClass().getSimpleName().equals("SetupNotFoundException")` |
| `isDatabaseCreationConflictError` | Checks `.getClass().getSimpleName().equals("DatabaseCreationConflictException")` — **used only for log-level suppression**, NOT for the 409 status decision |
| `createSetup` 409 trigger | `cause.getMessage().contains("already exists")` — message content, not exception class |
| `createSetup` 400 trigger | `cause.getMessage().contains("invalid")` — message content |
| `SetupNotFoundException` production location | Inner static class of `PeeGeeQDatabaseSetupService` (peegeeq-db module) |
| `DatabaseCreationConflictException` production location | Same inner class location as above |
| `GET /api/v1/health` | **Static inline lambda** in `PeeGeeQRestServer` — never calls service, always returns 200 with hardcoded JSON |
| `HealthHandler` failure status | 500 (not 503) on async failure |
| `HealthHandler` null-check status | 404 when `getHealthServiceForSetup` returns null |
| `ManagementApiHandler.createQueue` | Calls `addQueue()` directly; no active-set membership check; all failures → 503 |
| `QueueHandlerUnitTest` | **Already fully implemented** — Cycle B is COMPLETE, no tests to write |
| `sendError` response body | JSON with `"error"` field (not `"message"`) |

---

## Problem Statement

`peegeeq-rest` has 50+ integration tests (each booting a real PostgreSQL container) that cover
happy-path flows. They cannot exercise error paths reliably because:
- You cannot make a real database return "setup not found" on demand
- You cannot make the service throw at a specific point without contriving DB state
- Result: HTTP status-code contracts and error response shapes are unspecified and untested

The management UI depends on these contracts. A handler that silently returns 200 on failure,
or crashes with an unhandled exception, breaks the UI in ways the current tests do not catch.

## TDD Stance

These tests are written against existing handler code. In TDD terms:
- Each test is a **specification** of what the handler must do
- A **RED** test means the handler's actual behaviour does not match the specification — this is a handler bug to fix, not a test to disable
- A **GREEN** test confirms the handler already satisfies the specification
- Tests are written and run **one at a time**, smallest/simplest scenario first
- The test double is the seam that makes isolated handler testing possible

**The goal is not to write all tests and then run them.** The goal is to let each failing test
reveal a handler defect early, fix it, then move to the next scenario. The tests drive the work.

## Approach

Deploy `PeeGeeQRestServer` with a hand-written test double in place of `DatabaseSetupService`.
No database. No Testcontainers. The HTTP server is real; only the service is controlled.

This is directly supported by the constructor:
```
PeeGeeQRestServer(RestServerConfig config, DatabaseSetupService setupService)
```

Test class attributes:
- `@Tag(TestCategories.CORE)` — no DB dependency
- `@ExtendWith(VertxExtension.class)` — standard Vert.x test extension
- `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` — single server deployment per class
- No `@Testcontainers`

---

## Antipatterns: Mandatory Gates for These Tests

Source: `docs-design/testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md`.
Read the relevant sections **before writing any test**, not after. These are hard gates — a
violation is either present or absent. Severity labels do not create a schedule.

The antipatterns listed below are the ones that apply directly to the REST handler test code
being written. They are not an exhaustive list; the source document is the authoritative reference.

### CRITICAL: Exception thrown in `onSuccess` is silently swallowed (antipatterns doc §"Exception Thrown in `onSuccess`")

This is the most common cause of silent 30-second test hangs.

Any `RuntimeException` thrown synchronously inside a bare `onSuccess(v -> ...)` callback is
caught by the Vert.x event-loop context, routed to `vertx.exceptionHandler`, and logged as
`ContextImpl - Unhandled exception`. Neither `completeNow()` nor `failNow()` is called. The
test hangs for the full timeout and reports only "Timeout" with no root-cause information.

**Affected scenarios in these tests:**
- `@BeforeAll`: assertions inside `onSuccess` after `deployVerticle` — e.g., if `WebClient.create` threw
- Every test method: assertions like `assertEquals(200, response.statusCode())` inside `onSuccess`

**Canonical fix — use `testContext.succeeding(v -> testContext.verify(...))` for ALL `onComplete` callbacks with assertions:**

```java
// WRONG — any assertion failure causes a 30-second hang
.onSuccess(response -> {
    assertEquals(200, response.statusCode());  // throws AssertionError → silently swallowed
    testContext.completeNow();                 // never reached
})
.onFailure(testContext::failNow);

// CORRECT — testContext.succeeding routes failures to failNow; verify routes assertion
// errors to failNow; test fails immediately with the real cause
.onComplete(testContext.succeeding(response -> testContext.verify(() -> {
    assertEquals(200, response.statusCode());
    testContext.completeNow();
})));
```

`testContext.succeeding(handler)` handles both outcomes:
- Success: runs `handler` with the result value
- Failure: calls `testContext.failNow(cause)` immediately

No separate `.onFailure(testContext::failNow)` is needed after `.onComplete(testContext.succeeding(...))`.

### CRITICAL: Placeholder tests that always pass (antipatterns doc §"Placeholder Tests")

Never write `assertTrue(true, "...")`. Every test must assert at least:
1. The HTTP response status code (`assertEquals(expectedStatus, response.statusCode())`)
2. At least one meaningful response body field where the spec defines one

### SERIOUS: `.onComplete(ar -> latch.countDown())` swallows failures (antipatterns doc §"onComplete swallows failures")

`CountDownLatch` as a `Future`-completion bridge is banned. Use `VertxTestContext` + `Checkpoint` only.

```java
// BANNED
producer.send(msg).onComplete(ar -> latch.countDown());
assertTrue(latch.await(5, TimeUnit.SECONDS));

// REQUIRED
webClient.get(...).send()
    .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
        assertEquals(200, response.statusCode());
        testContext.completeNow();
    })));
```

### HIGH: `setTimer` as a readiness guard (antipatterns doc §"`setTimer` as a Readiness Guard")

`deployVerticle` completes only after `Verticle.start()` finishes. If `start()` awaits `HttpServer.listen()`,
the server is ready the instant `onComplete` fires. **Never** add a `setTimer` delay after deploy:

```java
// WRONG — timer is pure waste
vertx.deployVerticle(server)
    .onSuccess(id -> {
        deploymentId = id;
        vertx.setTimer(1000, t -> testContext.completeNow()); // "give server time to start"
    });

// CORRECT — trust the deploy future
vertx.deployVerticle(server)
    .onComplete(testContext.succeeding(id -> testContext.verify(() -> {
        deploymentId = id;
        webClient = WebClient.create(vertx);
        testContext.completeNow();
    })));
```

### CRITICAL: `setTimer` timeout handler calling `completeNow()` (antipatterns doc §"`setTimer` Variant: timeout calls completeNow")

If a timer is used as a fallback for an event that never arrives, it MUST call
`testContext.failNow(new AssertionError("Expected event did not arrive"))` — never `completeNow()`.
A timer that calls `completeNow()` is a test that passes unconditionally when the event is missing.

### MEDIUM: Empty catch blocks (antipatterns doc §"Empty Catch Blocks in Test Teardown")

- In `@AfterAll` / `@AfterEach`: replace `catch (Exception ignored) {}` with `catch (Exception e) { logger.warn("Close failed", e); }`
- In test body: replace with `catch (Exception e) { testContext.failNow(e); }`

### HIGH: Discarded `Future<Void>` from stop/close methods (antipatterns doc §"Discarded Future<Void> From Stop/Close")

Any `close()` or `undeploy()` call that returns `Future<Void>` must be composed on, never called
and ignored. This applies in `@AfterAll`:

```java
// WRONG — undeploy discarded, test may complete while server is still shutting down
manager.close();
testContext.completeNow();

// CORRECT — compose on the close future
vertx.undeploy(deploymentId)
    .onComplete(testContext.succeeding(v -> testContext.completeNow()));
```

### LOW: Unused method parameters (antipatterns doc §"Unused Method Parameters")

Do not declare `Vertx vertx` in a test method that never uses `vertx`. `VertxExtension` injects it
eagerly regardless; the parameter misleads readers.

### LOW: `@TestMethodOrder` / `@Order` on independent tests (antipatterns doc §"Unnecessary Test Ordering")

Each test in these classes is fully independent (its own `webClient` request, no shared mutable state
between tests). Do not add `@TestMethodOrder` or `@Order`.

---

## Step 1: Build the Test Double First

**New files (written before any test class):**
```
peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/support/ControllableSetupService.java
peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/support/SetupNotFoundException.java
```

The test double is the seam that makes all subsequent tests possible. Build it completely before
writing the first test so each test can be written, compiled, and run immediately.

### `ControllableSetupService` design

A concrete class implementing `DatabaseSetupService` (and therefore `ServiceProvider`).
Each method delegates to a configurable field. Builder-style factory methods:

```
ControllableSetupService.defaults()
    All async methods return Future.succeededFuture() with minimal valid responses.
    All ServiceProvider methods return null (simulates "no active setup").

ControllableSetupService.alwaysFailing(String reason)
    All async methods return Future.failedFuture(new RuntimeException(reason)).
    All ServiceProvider methods return null.

ControllableSetupService builder:
    .withCreateCompleteSetup(Function<DatabaseSetupRequest, Future<DatabaseSetupResult>>)
    .withDestroySetup(Function<String, Future<Void>>)
    .withGetSetupStatus(Function<String, Future<DatabaseSetupStatus>>)
    .withGetSetupResult(Function<String, Future<DatabaseSetupResult>>)
    .withAddQueue(BiFunction<String, QueueConfig, Future<Void>>)
    .withAddEventStore(BiFunction<String, EventStoreConfig, Future<Void>>)
    .withGetAllActiveSetupIds(Supplier<Future<Set<String>>>)
    .withHealthServiceForSetup(Function<String, HealthService>)
    .withSubscriptionServiceForSetup(Function<String, SubscriptionService>)
    .withDeadLetterServiceForSetup(Function<String, DeadLetterService>)
```

**Why a builder, not anonymous classes?**
Each test needs to configure 1-2 methods while defaulting the rest. Anonymous classes require
implementing all 11 interface methods in every test. The builder eliminates the boilerplate
and makes each test's intent visible.

**Not Mockito.** This is a hand-written concrete class. No mocking framework involved.

### `SetupNotFoundException` design

The `isSetupNotFoundError` helper in `DatabaseSetupHandler` checks:
```java
throwable.getClass().getSimpleName().equals("SetupNotFoundException")
```
A plain `RuntimeException` with "not found" in the message will **not** trigger the 404 path —
it will produce 503. Provide a dedicated class in `support/` whose simple class name is exactly
`SetupNotFoundException`.

**Note:** `SetupNotFoundException` already exists as an inner static class of
`PeeGeeQDatabaseSetupService` in the `peegeeq-db` module. The test support class is a separate
class in `peegeeq-rest`'s test classpath — same simple name, different fully-qualified name.
The handler only checks the simple name, so both trigger the same 404 path. The test support
class must NOT extend or import the production one (to keep the test module independent of
`peegeeq-db` internals).

### Verification after Step 1
Run `mvn test-compile -pl :peegeeq-rest` — the test double must compile cleanly before any
test is written. Fix any compile errors before continuing.

---

## Step 2: TDD Cycles — One Test at a Time

**The rule:** Write one test. Run it. If RED, read the handler and decide whether the specification
is correct or the handler has a bug. Fix the handler if it has a bug. Confirm GREEN. Move to
the next test.

**Do not write multiple tests before running.** The value of TDD is that each failure tells you
something specific. A batch of failures tells you much less.

### Test class `@BeforeAll` / `@AfterAll` template (applies to all 4 classes)

```java
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SomeHandlerErrorTest {

    private static final int TEST_PORT = 1811X;  // see port assignments below

    private String deploymentId;
    private WebClient webClient;

    @BeforeAll
    void deployServer(Vertx vertx, VertxTestContext testContext) {
        DatabaseSetupService service = ControllableSetupService.defaults();
        RestServerConfig config = new RestServerConfig(
            TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));

        // testContext.succeeding routes HTTP/deploy failures to failNow immediately.
        // testContext.verify routes any AssertionError or RuntimeException thrown inside
        // the callback to failNow — preventing the silent-swallow antipattern.
        vertx.deployVerticle(new PeeGeeQRestServer(config, service))
            .onComplete(testContext.succeeding(id -> testContext.verify(() -> {
                deploymentId = id;
                webClient = WebClient.create(vertx);
                testContext.completeNow();
            })));
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        (deploymentId != null
            ? vertx.undeploy(deploymentId)
            : Future.<Void>succeededFuture())
            .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }
}
```

Tests that require a different service configuration deploy a second server on an offset port.
Decide at implementation time; keep per-class server overhead to one deployment where possible.

### Canonical test method shape

Every test method MUST follow this shape. No exceptions.

```java
@Test
void getSetupStatus_unknownSetup_returns404(VertxTestContext testContext) {
    webClient.get(TEST_PORT, "localhost", "/api/v1/setups/bad-id/status")
        .send()
        .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
            assertEquals(404, response.statusCode());
            assertNotNull(response.bodyAsJsonObject().getString("message"));
            testContext.completeNow();
        })));
}
```

**Rules enforced by this shape:**

| Rule | Why |
|---|---|
| `.onComplete(testContext.succeeding(...))` — never bare `.onSuccess(...)` | Bare `onSuccess` silently swallows `AssertionError`; test hangs 30 s instead of failing immediately |
| All assertions and `completeNow()` inside `testContext.verify(...)` | `verify()` routes any exception to `failNow()`; nothing outside `verify()` is protected |
| No separate `.onFailure(testContext::failNow)` needed | `testContext.succeeding(handler)` already calls `failNow(cause)` when the future fails |
| `assertEquals(exactCode, response.statusCode())` — not `assertTrue(response.statusCode() < 500)` | Exact code is the specification; a range assertion lets wrong-but-close codes pass |
| No `assertTrue(true, ...)` anywhere | Tautological assertions prove nothing; delete or replace |
| No `CountDownLatch`, no `.await()` on a `Future` | Banned; use `VertxTestContext` + `Checkpoint` exclusively |

**For tests that use per-test service configuration** (tests A3, A7, C3, D4 etc. — where the shared
`defaults()` service is wrong for the scenario), deploy a dedicated server in `@BeforeEach`
and undeploy in `@AfterEach`, or accept the per-test deploy overhead if only 1–2 tests need it.
Keep the same `testContext.succeeding(...)` shape.

---

## TDD Cycle A: `DatabaseSetupHandlerErrorTest` (port 18110)

**File:** `peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/DatabaseSetupHandlerErrorTest.java`
**Tag:** `@Tag(TestCategories.CORE)`

Write and run each test in sequence. Do not proceed to the next until the current one is GREEN.

| # | Test method | Route | Service behaviour | Expected HTTP | Expected body | RED means… |
|---|---|---|---|---|---|---|
| A1 | `listSetups_emptyService_returns200` | `GET /api/v1/setups` | `getAllActiveSetupIds()` → `Set.of()` | 200 | JSON with `setupIds: []` | Handler crashes or returns wrong status on empty result |
| A2 | `destroySetup_serviceSucceeds_returns204` | `DELETE /api/v1/setups/any-id` | `destroySetup` → `Future.succeededFuture()` | 204 | empty body | Handler does not return 204 on success |
| A3 | `getSetupStatus_unknownSetup_returns404` | `GET /api/v1/setups/bad-id/status` | `getSetupStatus` → `failedFuture(new SetupNotFoundException(...))` | 404 | JSON `error` field | Handler returns 503 — test double exception simple name doesn't match, or `isSetupNotFoundError` not reached |
| A4 | `getSetupStatus_serviceFails_returns503` | `GET /api/v1/setups/any-id/status` | `getSetupStatus` → `failedFuture(new RuntimeException(...))` | 503 | JSON `error` field | Handler does not complete response on failure (hangs) |
| A5 | `getSetupDetails_unknownSetup_returns404` | `GET /api/v1/setups/bad-id` | `getSetupResult` → `failedFuture(new SetupNotFoundException(...))` | 404 | JSON `error` field | Same as A3 |
| A6 | `addQueue_unknownSetup_returns404` | `POST /api/v1/setups/bad-id/queues` | `addQueue` → `failedFuture(new SetupNotFoundException(...))` | 404 | JSON `error` field | Handler returns 503 or crashes |
| A7 | `createSetup_missingBody_returns400` | `POST /api/v1/setups` | service not called (body null/empty) | 400 | JSON `error` field | Handler throws NPE and returns 500/503, or no response |
| A8 | `createSetup_serviceFails_returns503` | `POST /api/v1/setups` | `createCompleteSetup` → `failedFuture(new RuntimeException("unexpected failure"))` | 503 | JSON `error` field | Handler does not handle failure or returns 500 |
| A9 | `createSetup_conflictingSetup_returns409` | `POST /api/v1/setups` | `createCompleteSetup` → `failedFuture(new RuntimeException("setup X already exists"))` | 409 | JSON `error` field | Handler returns 503 — the 409 path is triggered by **message content** `.contains("already exists")`, not by exception class name |

**A9 note:** `isDatabaseCreationConflictError` in the handler checks the exception class name, but only uses it to suppress the stack-trace log. The 409 status is set by `cause.getMessage().contains("already exists")`. To trigger 409, the test double must throw with a message containing that substring. A plain `RuntimeException("setup X already exists")` is sufficient — `DatabaseCreationConflictException` is not needed in the test support package for this test.

---

## TDD Cycle B: `QueueHandlerUnitTest` — ALREADY COMPLETE

**File:** `peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/QueueHandlerUnitTest.java`

This file is fully implemented. All three tests exist, compile, and follow the correct
`testContext.succeeding(...)` shape. No tests to write for Cycle B.

**What the existing tests cover:**

| # | Test method | Route | Expected HTTP | Notes |
|---|---|---|---|---|
| B1 | `sendMessage_unknownQueue_returns404` | `POST /api/v1/queues/setup1/test-q/messages` | 404 | defaults() service returns ACTIVE status with empty factory map; `getQueueFactory()` returns `failedFuture(ResponseException(404))` because queue name is absent from map |
| B2 | `getQueueStats_unknownQueue_returns404` | `GET /api/v1/queues/setup1/test-q/stats` | 404 | Same mechanism as B1 |
| B3 | `sendMessage_missingBody_returns400` | `POST /api/v1/queues/setup1/test-q/messages` | 400 | `parseAndValidateRequest` throws when body is absent |

**Key distinction from the original plan description:** These tests assert "unknown queue" (queue name
not in the factory map), not "unknown setup" (`getQueueFactoryProviderForSetup` returning null).
The `defaults()` service returns a non-null factory provider with an empty map; the 404 originates
from inside `getQueueFactory()` when the queue name is absent from that map.

---

## TDD Cycle C: `ManagementApiHandlerErrorTest` (port 18112)

**File:** `peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/ManagementApiHandlerErrorTest.java`
**Tag:** `@Tag(TestCategories.CORE)`

These are the endpoints the management UI calls most heavily. The handler's failure path
returns **503**, not 500. Tests must assert exactly 503.

| # | Test method | Route | Service behaviour | Expected HTTP | RED means… |
|---|---|---|---|---|---|
| C1 | `getOverview_noSetups_returns200` | `GET /api/v1/management/overview` | `getAllActiveSetupIds()` → empty set | 200 | Handler crashes on empty result or returns wrong status |
| C2 | `getQueues_noSetups_returns200` | `GET /api/v1/management/queues` | `getAllActiveSetupIds()` → empty set | 200 | Handler not robust to empty result set |
| C3 | `getOverview_serviceFails_returns503` | `GET /api/v1/management/overview` | `getAllActiveSetupIds()` → `failedFuture(...)` | 503 | Handler returns 500, or hangs, or crashes without sending a response |
| C4 | `createQueue_missingBody_returns400` | `POST /api/v1/management/queues` | body missing | 400 | Handler throws and returns 503 on bad input instead of 400 |
| C5 | `createQueue_serviceFailsWithSetupNotFound_returns404` | `POST /api/v1/management/queues` | valid body; `addQueue` → `failedFuture(RuntimeException("Setup not found: x"))` | 404 | Handler returns 503 — message-content check not triggering |
| C6 | `createQueue_serviceFails_returns503` | `POST /api/v1/management/queues` | valid body; `addQueue` → `failedFuture(RuntimeException("db error"))` | 503 | Handler maps all failures to 503 for unrecognised errors |
| C7 | `deleteQueue_queueNotFound_returns404` | `DELETE /api/v1/management/queues/:setupId/:queueName` | defaults() — empty factory map | 404 | Handler does not check that the queue exists before deleting |
| C8 | `updateQueue_queueNotFound_returns404` | `PUT /api/v1/management/queues/:setupId/:queueName` | defaults() — empty factory map | 404 | Handler does not check that the queue exists before updating |

**C4-C8 are the observable error paths.** The `getQueues`, `getConsumerGroups`, and `getEventStores` GET routes call internal helpers (`getRealQueues()`, `getRealConsumerGroups()`, `getRealEventStores()`) that each use `.transform(ar -> succeededFuture(emptyArray))` to absorb `getAllActiveSetupIds()` failures. Service failures on those routes silently produce 200 with an empty list — there is no 503 path to test. Only `getSystemOverview` propagates failures to `sendError(ctx, 503)`.

---

## TDD Cycle D: `HealthHandlerErrorTest` (port 18113)

**File:** `peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/HealthHandlerErrorTest.java`
**Tag:** `@Tag(TestCategories.CORE)`

Two distinct surfaces:
- Top-level `GET /api/v1/health` — a **static inline lambda** in `PeeGeeQRestServer` that returns hardcoded JSON without calling the service. Always 200.
- Per-setup `GET /api/v1/setups/:setupId/health` and `/health/components` and `/health/components/:name` — `HealthHandler` methods that call the synchronous `getHealthServiceForSetup(setupId)`, returning 404 immediately when null

| # | Test method | Route | Service behaviour | Expected HTTP | RED means… |
|---|---|---|---|---|---|
| D1 | `getHealth_serverUp_returns200` | `GET /api/v1/health` | service not called (static lambda) | 200 | Scaffold smoke test — always GREEN; confirms server deployed and responds to health probe |
| D2 | `getSetupHealth_unknownSetup_returns404` | `GET /api/v1/setups/bad-id/health` | `getHealthServiceForSetup("bad-id")` → null | 404 | `HealthHandler` does not null-check; NullPointerException |
| D3 | `listComponentHealth_unknownSetup_returns404` | `GET /api/v1/setups/bad-id/health/components` | `getHealthServiceForSetup("bad-id")` → null | 404 | Same as D2 |
| D4 | `getComponentHealth_unknownSetup_returns404` | `GET /api/v1/setups/bad-id/health/components/db` | `getHealthServiceForSetup("bad-id")` → null | 404 | `HealthHandler.getComponentHealth` does not null-check before accessing component by name |

**D1 note:** `GET /api/v1/health` is an inline lambda registered directly in `PeeGeeQRestServer`
that builds a static JSON object (`status: UP`, `version`, `uptime`) and returns 200. It never
calls the service. D1 is a scaffold smoke test — it will be GREEN regardless of service
configuration and is included only to confirm the server deploys correctly.

**D4 rationale:** Replaces the previously-planned test that claimed the top-level health lambda
calls `getAllActiveSetupIds()` and could fail with 500 — that was wrong. The static lambda has
no service dependency and no failure path to test. D4 covers the `getComponentHealth` null-check
path (same `HealthHandler` pattern as D2/D3 but for the per-component route).

**Async failure path (deferred):** Testing `getOverallHealthAsync()` failure (500) requires a
`HealthService` test double — a second interface to implement. Deferred until after Cycle D
confirms the null-check paths work. Add a Cycle E if the async path becomes a priority.

---

---

## Complete Interface Surface for `ControllableSetupService`

`DatabaseSetupService` extends `ServiceProvider`. Both interfaces must be implemented.

### From `ServiceProvider` (4 synchronous methods):
| Method | Default return in `ControllableSetupService.defaults()` | Notes |
|---|---|---|
| `getSubscriptionServiceForSetup(String setupId)` | `null` | null = "not found" |
| `getDeadLetterServiceForSetup(String setupId)` | `null` | null = "not found" |
| `getHealthServiceForSetup(String setupId)` | `null` | null = "not found" |
| `getQueueFactoryProviderForSetup(String setupId)` | `null` | null = "not found" |

### From `DatabaseSetupService` (7 async methods + 3 defaults):
| Method | Default return in `ControllableSetupService.defaults()` |
|---|---|
| `createCompleteSetup(DatabaseSetupRequest)` | `Future.succeededFuture(minimalResult)` |
| `destroySetup(String setupId)` | `Future.succeededFuture()` |
| `getSetupStatus(String setupId)` | `Future.succeededFuture(DatabaseSetupStatus.ACTIVE)` |
| `getSetupResult(String setupId)` | `Future.succeededFuture(minimalResult)` |
| `addQueue(String setupId, QueueConfig)` | `Future.succeededFuture()` |
| `addEventStore(String setupId, EventStoreConfig)` | `Future.succeededFuture()` |
| `getAllActiveSetupIds()` | `Future.succeededFuture(Set.of())` |
| `close()` *(default)* | `Future.succeededFuture()` — keep inherited default |
| `addFactoryRegistration(...)` *(default)* | no-op — keep inherited default |
| `getDatabaseConfig(String setupId)` *(default)* | keep inherited default (returns `Future.failedFuture(...)` or similar) — check interface source before deciding |

### How handlers map failures to HTTP status codes
Verified by reading source. Not assumed.

| Handler method | Failure type | HTTP status |
|---|---|---|
| `createSetup` | message contains `"already exists"` (e.g. from `DatabaseCreationConflictException`) | 409 |
| `createSetup` | message contains `"invalid"` | 400 |
| `createSetup` | other | 503 |
| `getSetupStatus` | `SetupNotFoundException` (by class name) | 404 |
| `getSetupStatus` | other | 503 |
| `getSetupDetails` | `SetupNotFoundException` (by class name) | 404 |
| `getSetupDetails` | other | 503 |
| `addQueue` | `SetupNotFoundException` | 404 |
| `addQueue` | `IllegalArgumentException` | 400 |
| `addQueue` | other | 503 |
| `getSystemOverview` | any | 503 |
| `getQueues` | any | 503 |
| `getConsumerGroups` | any | 503 |
| `getEventStores` | any | 503 |
| `getMessages` | any | 503 |
| `createQueue` (management) | any | 503 |
| `deleteQueue` (management) | `SetupNotFoundException` | 404 |
| `getOverallHealth` (:setupId) | `getHealthServiceForSetup` returns null | 404 |
| `getOverallHealth` (:setupId) | async failure | **500** |
| `listComponentHealth` | `getHealthServiceForSetup` returns null | 404 |
| `getComponentHealth` | `getHealthServiceForSetup` returns null | 404 |

**Critical:** `createSetup` 409 is driven by **message content**, not by exception class name.
`isDatabaseCreationConflictError` is used only for log-level control (suppresses stack trace
in debug log). A `RuntimeException("setup X already exists")` produces 409. A
`DatabaseCreationConflictException` without that substring would produce 503.

---

## Port Assignments

Verified against all existing test classes in `peegeeq-rest/src/test/`:

| Port | Existing user |
|---|---|
| 18080 | `SSEBasicStreamingIntegrationTest` |
| 18081 | `ConsumerGroupSubscriptionIntegrationTest`, `SSEReconnectionIntegrationTest` |
| 18082 | `SSEBatchingIntegrationTest` |
| 18085 | `SubscriptionPersistenceAcrossRestartIntegrationTest` |
| 18090 | `PeeGeeQRestServerTest`, `EventStoreIntegrationTest` |
| 18091 | `EndToEndValidationTest` |
| 18092 | `CallPropagationIntegrationTest` |
| 18093 | `CrossLayerPropagationIntegrationTest`, `MultiTenantSchemaIsolationTest` |
| 18094 | `DatabaseInfrastructureDiagnosticTest` |
| 18095 | `ManagementApiHandlerTest` |
| 18096 | `EventStoreEnhancementTest` |
| 18097 | `SystemMonitoringHandlerTest`, `ManagementApiIntegrationTest` |
| 18098 | `WebSocketHandlerTest`, `SubscriptionLifecycleIntegrationTest`, `HealthHandlerIntegrationTest` |
| 18099 | `DeadConsumerAlertingIntegrationTest`, `QueuePauseResumeIT`, `DeadLetterRequeueIntegrationTest`, `ServerSentEventsHandlerTest`, `QueuePauseResumeManualTest` |
| 18100 | `BatchMessageProcessingIntegrationTest`, `QueueDeleteManualTest`, `SetupManagementIntegrationTest` |
| 18101 | `QueueManagementE2ETest`, `RealTimeStreamingIntegrationTest` |
| 18102 | `QueueManagementAdvancedE2ETest` |
| 18103 | `MessageOperationsE2ETest`, `SubscriptionCreateAndBackfillIntegrationTest` |
| 18105 | `PartitionedConsumptionRestIntegrationTest` |

**New test ports:**
| Port | New test class |
|---|---|
| 18110 | `DatabaseSetupHandlerErrorTest` |
| 18111 | `QueueHandlerUnitTest` |
| 18112 | `ManagementApiHandlerErrorTest` |
| 18113 | `HealthHandlerErrorTest` |

---

## Files to Create

| # | File | Type |
|---|---|---|
| 1 | `peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/support/ControllableSetupService.java` | New — shared test double |
| 2 | `peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/support/SetupNotFoundException.java` | New — test exception (or inner class of #1) |
| 3 | `peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/DatabaseSetupHandlerErrorTest.java` | New — 9 tests (A1–A9) |
| 4 | `peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/ManagementApiHandlerErrorTest.java` | New — 8 tests (C1–C8) |
| 5 | `peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/HealthHandlerErrorTest.java` | New — 4 tests (D1–D4) |

**Note:** `QueueHandlerUnitTest` (Cycle B) is already fully implemented and must NOT be recreated.
No production files are modified.

---

## Files to Read Before Starting

**Already read during plan validation** — re-read only if the source has changed:
- [peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/PeeGeeQRestServer.java](peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/PeeGeeQRestServer.java) — constructor, route wiring, static health lambda
- [peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/DatabaseSetupHandler.java](peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/DatabaseSetupHandler.java) — `isSetupNotFoundError`, `isDatabaseCreationConflictError`, body-parsing, status codes
- [peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/HealthHandler.java](peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/HealthHandler.java) — null-check pattern, 404/500 paths
- [peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/ManagementApiHandler.java](peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/ManagementApiHandler.java) — `createQueue` failure path, 503 mapping
- [peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/QueueHandler.java](peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/handlers/QueueHandler.java) — already implemented in Cycle B
- [peegeeq-api/src/main/java/dev/mars/peegeeq/api/setup/DatabaseSetupService.java](peegeeq-api/src/main/java/dev/mars/peegeeq/api/setup/DatabaseSetupService.java) — all method signatures
- [peegeeq-api/src/main/java/dev/mars/peegeeq/api/setup/ServiceProvider.java](peegeeq-api/src/main/java/dev/mars/peegeeq/api/setup/ServiceProvider.java) — 4 synchronous methods

**Read before writing `ControllableSetupService.defaults()` — need exact types:**
- [peegeeq-api/src/main/java/dev/mars/peegeeq/api/setup/DatabaseSetupResult.java](peegeeq-api/src/main/java/dev/mars/peegeeq/api/setup/DatabaseSetupResult.java) — what constitutes a "minimal valid result"
- [peegeeq-api/src/main/java/dev/mars/peegeeq/api/setup/DatabaseSetupStatus.java](peegeeq-api/src/main/java/dev/mars/peegeeq/api/setup/DatabaseSetupStatus.java) — confirm `ACTIVE` is a valid enum constant

**Read as pattern reference:**
- [peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/QueueHandlerUnitTest.java](peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/QueueHandlerUnitTest.java) — canonical `@BeforeAll`/`@AfterAll`/test shape already in use
- [peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/SetupManagementIntegrationTest.java](peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/SetupManagementIntegrationTest.java) — additional `@BeforeAll`/`@AfterAll` reference

---

## Scope Exclusions

| Handler | Reason excluded |
|---|---|
| `EventStoreHandler` | Error paths require real event store state; integration tests cover the happy path |
| `ServerSentEventsHandler` | Streaming; error-path behaviour without a real message source is not meaningful |
| `WebSocketHandler` | Same as SSE |
| `WebhookHandler` / `SubscriptionHandler` | Lower priority; no known UI gap |
| `ConsumerGroupHandler` | `ConsumerGroupHandlerTest` already exists with JSON structure tests |

---

## After Every RED Test: Decision Tree

```
Test is RED
    │
    ├─ Test TIMES OUT (30 s) instead of failing immediately
    │       → "Exception thrown in onSuccess is silently swallowed" antipattern.
    │         The assertion threw AssertionError but it was caught by the Vert.x event-loop.
    │         Fix: change bare .onSuccess(v -> { assert...; completeNow(); })
    │              to .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
    │                     assert...;
    │                     testContext.completeNow();
    │                 })));
    │         The real exception is in the test output as "ContextImpl - Unhandled exception".
    │
    ├─ Response has wrong HTTP status (e.g., 503 instead of 404)
    │       → Check exception class name. Is the test double throwing exactly
    │         SetupNotFoundException (simple name)? If not, fix the test double.
    │         If yes, the handler is not calling isSetupNotFoundError — fix the handler.
    │
    ├─ Response body is not JSON / malformed
    │       → Handler is not calling sendError(ctx, ...) — sending raw text or nothing.
    │         Fix the handler.
    │
    ├─ Connection reset / no response / test times out with no log evidence
    │       → Handler is not completing the response on the failure path.
    │         Likely a fire-and-forget Future or unhandled exception. Fix the handler.
    │
    └─ Test assertion is wrong (spec error)
            → Re-read the handler source. If the handler is intentionally correct and the
              test expectation was based on a misread of the source, fix the test assertion.
              Document the reason in a comment.
```

---

## Final Validation (after all cycles complete and GREEN)

**Single-module run** (do not escalate to full suite for a `peegeeq-rest`-only change):
```powershell
mvn test -pl :peegeeq-rest -Pall-tests 2>&1 | Tee-Object -FilePath logs\peegeeq-rest-YYYYMMDD.txt
```

Confirm `Tests run: N` for each new class (not `Tests run: 0`).

**Banned-pattern grep on every file written or modified:**
```powershell
Get-Content `
  peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/support/ControllableSetupService.java, `
  peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/DatabaseSetupHandlerErrorTest.java, `
  peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/ManagementApiHandlerErrorTest.java, `
  peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/HealthHandlerErrorTest.java |
  Where-Object { $_ -match '\.recover\(|\.otherwise\(|CountDownLatch.*await|\.await\(\)|toCompletionStage|Thread\.sleep|assertTrue\(true\)|onComplete\(ar ->' }
```
Expected result: zero matches. Any match is a banned-pattern violation to fix before claiming done.

**Antipattern-specific grep — catches the most common new-code mistakes:**
```powershell
# Find bare onSuccess callbacks that contain assertions or completeNow outside verify()
# (the silent-swallow antipattern — causes 30s hangs, not immediate RED failures)
Get-Content `
  peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/DatabaseSetupHandlerErrorTest.java, `
  peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/ManagementApiHandlerErrorTest.java, `
  peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/handlers/HealthHandlerErrorTest.java |
  Where-Object { $_ -match '\.onSuccess\(' }
```
Expected result: zero matches. All `onSuccess` must have been converted to
`.onComplete(testContext.succeeding(...))`. Any remaining `onSuccess` call is suspicious and
must be reviewed against the canonical shape.

Note: `OnSuccessExceptionSwallowingGuardTest` in `peegeeq-test-support` will also catch Tier 2
and Tier 3 violations (assertions or close calls outside `testContext.verify`) as a static-analysis
gate when the full suite runs. Fix violations before that gate fires.

