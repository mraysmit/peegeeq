# PeeGeeQ Copilot Instructions

These instructions are mandatory for all code and test changes in this repository.

## Read Before Changing Code
- Read `docs-design/dev/pgq-coding-principles.md` before making any change.
- Read existing code in the same module before implementing new logic.
- Read existing tests in the same module before changing or adding tests.
- Do not guess and do not default to generic Java patterns.

## Vert.x 5.x Reactive Only
Use Vert.x composable futures everywhere.

### Required
- Use `io.vertx.core.Future<T>` for asynchronous APIs and flows.
- Use `.compose(...)` for sequencing.
- Use `.map(...)` for transformation.
- Use `.recover(...)` for fallback/recovery.
- Use `.onSuccess(...)` and `.onFailure(...)` for terminal handlers.
- Use `Promise<Void>` for async coordination in tests.
- Use `VertxTestContext` and `Checkpoint` for asynchronous test completion.
- Use `Vertx.setTimer(...)` for delays in tests when needed.

### Forbidden
- `CompletableFuture`
- `CompletionStage`
- `.toCompletionStage()` / `.toCompletableFuture()` bridge chains
- `.get()` / `.join()` blocking waits
- `Thread.sleep(...)`
- Spin-loop polling helpers (for example `waitForCondition` loops)
- Callback-style `Handler<AsyncResult<T>>` patterns where composable futures can be used
- `.onComplete(ar -> { if (ar.succeeded()) ... })` in place of `.onSuccess/.onFailure`

## Database Access Rules
PeeGeeQ uses Vert.x PostgreSQL reactive client patterns.

### Forbidden
- `java.sql.Connection`
- `DriverManager.getConnection(...)`
- `PreparedStatement`
- `ResultSet`
- Any raw JDBC in production/tests (except migration code)

### Required
- Use `DatabaseService.getPool()` or `PgConnectionManager` reactive pool helpers.
- Use reactive query patterns (`withConnection`, `preparedQuery`, `Tuple`, `RowSet`).

Reference examples:
- `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerCrashRecoveryTest.java`
- `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/ReactiveOutboxProducerTest.java`

## Testing Rules
- If a test touches the database, use `@Tag(TestCategories.INTEGRATION)` and `@Testcontainers`.
- Use the latest Testcontainers version defined by the repository; do not introduce older or ad-hoc versions.
- Use non-deprecated Testcontainers patterns only.
- Use the PostgreSQL version constant pattern used by the project (do not hardcode PostgreSQL image tags in test classes).
- Do not use mocks/stubs/fakes for database integration behavior.
- Do not skip failing tests with `@Disabled`, `-DskipTests`, or exclusion tricks.
- Work incrementally: one small change, then run tests.
- Read test logs in detail; do not rely on process exit code only.

### Maven Profile Rules
- `mvn test` runs core tests only by default.
- Use `-Pintegration-tests` for integration tests.
- `Tests run: 0` means the test likely did not execute under the active profile.
- Install dependent modules locally first when needed.

## Change Process Rules
- Never do bulk regex replacements across many files.
- Make focused file-by-file changes.
- Verify each change against existing project patterns.
- If scope expands significantly, stop and confirm approach before continuing.
- Compilation success alone is not completion; behavior and patterns must be correct.

## Multi-Tenant Schema Isolation Rules
- Tenant separation is absolute: one isolated schema per tenant.
- No hardcoded global schema names.
- Use `{schema}` placeholders in SQL templates.
- LISTEN/NOTIFY channels must be tenant schema-aware.
- `peegeeq.database.schema` is the single source of truth.
- Any schema logic change must include multi-tenant isolation tests.

## Environment
- Development environment is Windows 11.
- Use PowerShell-compatible commands.
- For Maven/test commands, use `Tee-Object` so output is visible and saved.
