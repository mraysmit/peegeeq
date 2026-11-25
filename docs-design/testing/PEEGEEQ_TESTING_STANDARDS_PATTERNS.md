# PeeGeeQ Testing Patterns

**Status:** CURRENT COMPANION GUIDE

**Last reconciled:** 2026-10-06

**Corrected:** 2026-11-21 against `0ffaafb6`: the container example uses the raw `org.testcontainers.postgresql.PostgreSQLContainer` type of Testcontainers 2.x.

**Corrected:** 2026-11-25: added "Schema setup for integration tests", checked against `PeeGeeQTestSchemaInitializer`, `PeeGeeQDatabaseSetupService` and the setup templates. It replaces the test-setup material formerly in `docs/PEEGEEQ_DATABASE_SETUP_GUIDE.md`.

## Authority

The mandatory rules are defined in
[PeeGeeQ Testing Standards and Antipatterns](PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md).
This shorter document shows approved test structure and repository conventions. If the two
documents differ, the mandatory standards document wins.

## Core principles

- Test externally observable behaviour, not private implementation detail.
- Use real implementations at meaningful boundaries.
- Use Testcontainers for PostgreSQL behaviour and compatibility.
- Do not use Mockito or another mocking framework.
- Do not use mocked database connections or repositories.
- Keep every asynchronous result observed and route both outcomes to the test context.
- Verify persisted database state for transaction, ordering, migration, and isolation behaviour.
- Give every test a supported category so the intended Maven profile runs it.
- Keep cleanup deterministic and surface cleanup failures.
- Report exact test scope and per-class counts; a successful build banner alone is insufficient.

## TDD sequence

For each behavioural increment:

1. Write the smallest test that expresses the missing behaviour.
2. Run it with the profile that includes its tag and confirm the expected failure.
3. Implement only enough production behaviour to satisfy that test.
4. Rebuild the affected reactor slice before verification.
5. Run the focused method, class, or module test scope.
6. Inspect the saved Maven log and test reports.
7. Add failure, rollback, cleanup, concurrency, or restart coverage required by the boundary.
8. Re-run the mandatory banned-pattern scan on every touched file.

The complete `all-tests` profile is an owner-run or explicitly requested release gate, not the
normal edit-test loop.

## Test naming and organization

- Name focused tests `<Feature>Test`.
- Name real-boundary tests `<Feature>IntegrationTest`.
- Use a method name that states the trigger and observable result.
- Use `@DisplayName` when it materially improves the report.
- Keep arrange, act, and assertions close enough that a failure is easy to diagnose.
- Prefer one behavioural reason for failure per test.

Example structure:

```java
@Test
@DisplayName("commits the domain row and event in one transaction")
void commitsDomainRowAndEvent(VertxTestContext context) {
    executeTransaction()
        .onComplete(context.succeeding(result -> context.verify(() -> {
            assertEquals(expectedId, result.id());
            assertPersistedState(result.id());
            context.completeNow();
        })));
}
```

When failure is expected:

```java
@Test
@DisplayName("rejects an invalid partition key")
void rejectsInvalidPartitionKey(VertxTestContext context) {
    createSubscription(invalidRequest())
        .onComplete(context.failing(error -> context.verify(() -> {
            assertInstanceOf(IllegalArgumentException.class, error);
            context.completeNow();
        })));
}
```

The terminal handler routes the unexpected outcome to the test context. Assertions remain inside
`context.verify`, and completion is the last action on the intended branch.

## Asynchronous-test checklist

- Inject `VertxTestContext` through the Vert.x JUnit extension.
- Route both success and failure through its `succeeding` or `failing` handler.
- Wrap callback assertions in `context.verify`.
- Complete the context exactly once, after the last required assertion.
- Compose every operation on which the assertion depends.
- Never discard a returned future.
- Never bridge a Vert.x future to a blocking Java future.
- Never use fixed-duration blocking delays as synchronization.
- Use deterministic checkpoints, latches designed for the test framework, database observations,
  or protocol acknowledgements.
- Keep cleanup asynchronous where the resource supports it and observe its result.

## PostgreSQL and Testcontainers pattern

Use the repository test-support factory rather than constructing an image ad hoc:

```java
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class AccountRepositoryIntegrationTest {
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer postgres =
        PostgreSQLTestConstants.createStandardContainer();
}
```

The project uses Testcontainers 2.x. Its `org.testcontainers.postgresql.PostgreSQLContainer` is
not generic, and `PostgreSQLTestConstants.createStandardContainer()` returns that raw type. Do not
write `PostgreSQLContainer<?>` and do not import `org.testcontainers.containers.PostgreSQLContainer`.

For PeeGeeQ configuration, prefer an isolated property object:

```java
Properties properties = PeeGeeQTestConfig.builder()
    .from(postgres)
    .schema("tenant_alpha")
    .property("peegeeq.database.pool.max-size", "3")
    .build();

PeeGeeQConfiguration configuration =
    new PeeGeeQConfiguration("test", properties);
```

Avoid process-wide system properties. If a legacy boundary truly requires them, serialize the test
and restore every original value in cleanup.

## Schema setup for integration tests

Create the test schema the way the production path under test creates it. PeeGeeQ has two
provisioning paths, Flyway migrations and setup templates, and they create different objects. The
[PeeGeeQ Database Guide](../../docs/PEEGEEQ_DATABASE_GUIDE.md) lists the differences.

| Production path under test | Test schema | Reference test |
|---|---|---|
| `PeeGeeQManager` built from `PeeGeeQConfiguration` on a Flyway schema | Flyway schema in the container database, from `PeeGeeQTestSchemaInitializer` | `peegeeq-examples` `nativequeue/ServerSideFilteringTest`, `setUp` and `tearDown` only. Copy the lifecycle, not the logging: its `logger.info` narration lines break hygiene rule 3 of the [standards](PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md) |
| `DatabaseSetupService` (`PeeGeeQRuntime`, REST server) | A new database provisioned by `createCompleteSetup` | `peegeeq-db` `setup/SetupBindingPersistenceIntegrationTest`: one service per test method, verification through `PgConnectionManager`, no ordering, no narration. Deviation: it runs `destroySetup` through `compose`, so a failed earlier step skips it; `close()` still runs |

Tests in `peegeeq-db` extend `BaseIntegrationTest`, as the
[coding principles](../dev/pgq-coding-principles.md) require. Do not apply both paths to one schema: the
tables that both create have different definitions.

### Flyway schema: `PeeGeeQTestSchemaInitializer`

```java
PeeGeeQTestSchemaInitializer.initializeSchema(PostgreSQLContainer postgres, String schema,
        SchemaComponent... components);
PeeGeeQTestSchemaInitializer.initializeSchema(String jdbcUrl, String username, String password,
        String schema, SchemaComponent... components);
```

- The schema argument is required and must be a plain identifier. There is no overload without it.
- The initializer runs Flyway over `peegeeq-migrations/src/main/resources/db/migration`, which it finds
  from the working directory (the repository root or a module directory). It creates the schema if it is
  absent. It then adds `causation_id` to `bitemporal_event_log` if that column is absent.
- Every call migrates the schema to the latest version of the full migration set. The component
  arguments do not limit what is created; they matter only to `cleanupTestData`.
- The call is synchronous JDBC and throws `RuntimeException` on failure. Call it directly in the
  lifecycle method, before the first `Future` chain starts. Do not call it inside a `Future` callback,
  because it blocks the thread that runs the callback.
- `SchemaComponent` is nested in `PeeGeeQTestSchemaInitializer`. Its values are `SCHEMA_VERSION`,
  `OUTBOX`, `NATIVE_QUEUE`, `DEAD_LETTER_QUEUE`, `BITEMPORAL`, `CONSUMER_GROUP_FANOUT`, `QUEUE_ALL` and
  `ALL`.
- The resulting schema has no `queue_template`, `event_store_template`, `peegeeq_object_registry` or
  `peegeeq_setup_metadata`. `DatabaseSetupService.connectToExistingSetup` cannot attach to it.

```java
@BeforeEach
void setUp(VertxTestContext testContext) {
    PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA,
            SchemaComponent.ALL);
    Properties properties = PeeGeeQTestConfig.builder()
            .from(postgres)
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .build();
    manager = new PeeGeeQManager(new PeeGeeQConfiguration("test", properties), new SimpleMeterRegistry());
    manager.start()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
}

@AfterEach
void tearDown(VertxTestContext testContext) {
    TestCleanup.run(() -> manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
}
```

`cleanupTestData(postgres, schema, components...)` truncates the tables of each named component through
raw JDBC. With `ALL`, or with no component, it truncates nothing. Prefer a unique topic and group name
per test to truncation: in a container shared by several test classes, a truncation also removes rows
that other running tests own.

### Template-provisioned database: `DatabaseSetupService`

- Obtain the service with `PeeGeeQRuntime.createDatabaseSetupService()` in `@BeforeEach`. In
  `@AfterEach`, run `destroySetup(setupId)` and then `close()` through `TestCleanup.run`. `close()`
  closes every active setup manager but not the setup's queue factories or event stores;
  `destroySetup` closes those. Neither drops a database. The databases a test creates remain until the
  container stops.
- Each `createCompleteSetup` creates a new database and refuses a name that already exists. Generate a
  unique name that is a valid identifier, for example `"setup_db_" + System.nanoTime()`. A name built
  from `UUID.randomUUID()` contains hyphens and is rejected.
- The schema is required. Use `PostgreSQLTestConstants.TEST_SCHEMA` or a per-test literal.
- The template schema lacks the objects that only Flyway creates, such as the `OFFSET_WATERMARK` tables
  and `bitemporal_event_log`. A test of those features uses the Flyway schema, as
  `peegeeq-rest` `PartitionedConsumptionRestIntegrationTest` does.
- A subscription record is created through `setupService.getSubscriptionServiceForSetup(setupId)`, which
  returns null for an unknown setup, and its `subscribe(topic, groupName, store)` or
  `subscribe(topic, groupName, store, options)`. The `SubscriptionStore` value `OUTBOX` or `NATIVE`
  names the message table of the queue.
- Verification queries against the provisioned database go through a secondary `PgConnectionManager`
  that the test closes. The helper `queryBinding` in `SetupBindingPersistenceIntegrationTest` shows the
  form.

```java
@Test
void createsSetupWithQueue(VertxTestContext testContext) {
    DatabaseConfig database = new DatabaseConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .databaseName("setup_db_" + System.nanoTime())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .build();
    DatabaseSetupRequest request = new DatabaseSetupRequest(setupId, database,
            List.of(new QueueConfig.Builder().queueName("orders").build()), List.of(), Map.of());

    setupService.createCompleteSetup(request)
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                assertEquals(DatabaseSetupStatus.ACTIVE, result.getStatus());
                assertTrue(result.getQueueFactories().containsKey("orders"));
                testContext.completeNow();
            })));
}

@AfterEach
void tearDown(VertxTestContext testContext) {
    if (setupService == null) {
        testContext.completeNow();
        return;
    }
    TestCleanup.run(() -> setupService.destroySetup(setupId), () -> setupService.close())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
}
```

## Database verification pattern

For a transactional change, assert both the API result and the database facts that define the
contract:

- expected rows exist in every participating table;
- unexpected or partially committed rows do not exist;
- correlation and idempotency identifiers match;
- tenant schema qualification is correct;
- temporal values have the required valid-time and transaction-time relationship;
- rollback restores the pre-operation state; and
- retry or restart does not violate uniqueness, ordering, or cursor rules.

Use parameterized SQL and query the tenant-qualified table through the same supported schema
contract as production code.

## External-system adapters

Adapter tests should exercise:

- actual request serialization;
- real transport or a protocol-level fixture;
- status and error mapping;
- timeout and cancellation;
- response parsing;
- retry and idempotency behaviour; and
- resource cleanup.

A hand-built protocol server can be appropriate when the real service cannot be embedded, but it
must behave at the protocol boundary rather than impersonating internal repositories or clients.

## Concurrency and lifecycle tests

For code that starts background work or owns resources, cover:

- close before start completes;
- repeated start or stop requests;
- partial-start failure;
- work in flight during shutdown;
- handler failure in the middle of a batch;
- cancellation of timers and subscriptions;
- recovery or restart from the last committed state; and
- concurrent callers contending for the same database invariant.

Prefer state or protocol checkpoints over timing assumptions.

## Categorization and execution

Use the constants in `TestCategories` and select the Maven profile that includes the category.
Typical scopes are:

| Intent | Scope |
|---|---|
| Fast logic feedback | Focused test method or class under the core profile |
| Database or container boundary | Focused class with the integration profile |
| Minimal end-to-end health | Smoke profile |
| Performance investigation | Explicit performance profile and controlled host |
| Missing-tag audit | Untagged profile |
| Release acceptance | Complete profile from the beginning |

The current commands and profile semantics are documented in
[PeeGeeQ Test Commands](PEEGEEQ-TEST-COMMANDS.md).

## Evidence to report

Every verification report should include:

- changed module and rebuilt reactor slice;
- exact focused test method, class, or module;
- Maven profile;
- saved log path;
- per-class `Tests run`, failure, error, and skipped counts;
- any expected containers or browser suites that did not run; and
- whether the result is focused evidence, a resumed reactor tail, or a complete release gate.

## Pre-completion checklist

- [ ] The expected failure was observed before the implementation change.
- [ ] Touched production and test files comply with the banned-pattern scan.
- [ ] No mocking dependency, import, extension, agent, or configuration was added.
- [ ] Database tests use a real PostgreSQL container.
- [ ] Both asynchronous outcomes reach the test context.
- [ ] Assertions execute before test completion.
- [ ] Failure, rollback, cleanup, and restart paths appropriate to the change are covered.
- [ ] The affected reactor slice was rebuilt before verification.
- [ ] The smallest relevant tagged scope passed.
- [ ] Saved logs and per-class counts were inspected and reported.

## References

- [Mandatory testing standards](PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md)
- [Test commands](PEEGEEQ-TEST-COMMANDS.md)
- [Coding principles](../dev/pgq-coding-principles.md)
- [Consolidated task register](../tasks/tasks.md)
