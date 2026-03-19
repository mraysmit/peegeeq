# PeeGeeQ Copilot Instructions

These instructions are mandatory for all code and test changes in this repository.

## Read Before Changing Code
- Read `docs-design/dev/pgq-coding-principles.md` before making any change.
- Read existing code in the same module before implementing new logic.
- Read existing tests in the same module before changing or adding tests.
- Do not guess and do not default to generic Java patterns.

## Vert.x 5.x Reactive Only
Use Vert.x composable futures everywhere.

### Naming Rules (Reactive-Only APIs)
- In APIs/modules that are fully Future-based, do not use `Async` or `Reactive` method suffixes just to indicate asynchrony.
- Prefer plain domain names (for example `close`, `send`, `createSetup`) and let the return type (`Future<T>`) define async behavior.
- Use `Async`/`Reactive` suffixes only when a synchronous variant with the same base name also exists and disambiguation is required.
- During migrations, remove temporary `Async`/`Reactive` suffixes after sync variants are removed.

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

### Mandatory: Remove Sync Wrappers Before Converting Tests
- Before converting any test file to Vert.x test primitives, check whether the production class under test has synchronous wrapper methods (methods that internally block on futures via `.join()`, `.get()`, `.toCompletionStage()` etc.).
- If synchronous wrappers exist in the production class, **remove them first** before touching any tests.
- Then rewrite the tests to call the async `Future<T>` APIs directly using `VertxTestContext`, `.compose()`, `.onSuccess()`, `.onFailure()`, and `awaitCompletion()`.
- Never write tests that call synchronous wrapper methods. Never write new synchronous wrapper helper methods in tests.
- Order is mandatory: production sync wrappers removed → tests rewritten to async APIs. Never the reverse.

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

## Instruction Compliance Contract
- Follow explicit user instructions exactly. Do not replace requested architecture/style changes with temporary compatibility shims unless the user explicitly asks for a temporary shim.
- Do not optimize for short-term compile continuity when that conflicts with explicit user instructions.
- When user asks for reactive-only style, remove all bridge/blocking patterns from touched files; do not keep `toCompletionStage`, `toCompletableFuture`, `.get`, `.join`, or equivalent blocking bridges.
- If the implementation deviates from user instructions for any reason, stop and state the exact reason before making further edits.

## Mandatory Verification Before Claiming Done
- Provide evidence for touched files:
	- Search proof that forbidden patterns are absent in touched files.
	- Build/test proof for affected modules.
	- Exact list of changed files.
- Do not claim completion until verification passes.

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
