# peegeeq-db review (Vert.x 5.x patterns compliance)

Scope: Review of peegeeq-db against Vertx-5x-Patterns-Guide. Verified current code in:
- src/main/java/dev/mars/peegeeq/db/health/HealthCheckManager.java
- src/main/java/dev/mars/peegeeq/db/PeeGeeQManager.java
- src/main/java/dev/mars/peegeeq/db/config/PgConnectionConfig.java
- src/main/java/dev/mars/peegeeq/db/config/PgPoolConfig.java
- src/main/java/dev/mars/peegeeq/db/connection/PgConnectionManager.java
- src/main/java/dev/mars/peegeeq/db/client/PgClientFactory.java
- src/main/java/dev/mars/peegeeq/db/setup/PeeGeeQDatabaseSetupService.java

Date: current workspace state (post Vertx-5x-Patterns-Guide updates)

---

## Executive summary

Strengths (keep):
- PgConnectionManager and PgClientFactory follow Vert.x 5.x reactive patterns (PgBuilder.pool, idempotent creation, async close with Future.all, input validation).
- PeeGeeQManager uses Vert.x timers for background tasks and provides reactive lifecycle (startReactive/stopReactive/closeReactive), with event-loop blocking guards.
- PgPoolConfig uses Duration-based timeouts and explicit maxWaitQueueSize; no JDBC-era fields.
- Health checks use real SQL via pool.withConnection (SELECT 1 etc.).

Issues to address:
1) HealthCheckManager uses ScheduledExecutorService and blocking get(); switch to Vert.x timers and keep the flow reactive. Add stopReactive().
2) PeeGeeQDatabaseSetupService creates its own cached thread pool and blocks on futures; prefer vertx.executeBlocking or fully reactive composition. It also creates a new PeeGeeQManager without passing the existing Vertx.
3) PeeGeeQManager creates a new Vert.x when vertx arg is null; prefer reusing Vertx.currentContext().owner() when available. Once HealthCheckManager exposes stopReactive(), use it from PeeGeeQManager.
4) PgConnectionConfig exposes getJdbcUrl(); deprecate (project is Vert.x reactive only). Add sanitized toString without password.

---

## Findings by file

### HealthCheckManager.java
- Current:
  - Uses ScheduledExecutorService for periodic execution (`Executors.newScheduledThreadPool`), and schedules checks with `scheduleAtFixedRate`.
  - Inside each check it blocks: `toCompletableFuture().get(...)` and future.get(timeout...).
  - Stop is synchronous via `scheduler.shutdown()`/`awaitTermination`.
- Why it matters:
  - Violates the guide’s “Replace ScheduledExecutorService with Vert.x timers” and “avoid blocking on event loops” patterns; harder lifecycle orchestration; extra unmanaged threads.
- Recommendation:
  - Accept `Vertx` in ctor or a `startReactive(Vertx vertx)` parameter, replace scheduler with `vertx.setPeriodic(checkInterval.toMillis(), ...)`.
  - Build checks as a Future chain (no blocking `get()`), aggregate with `Future.all` if needed.
  - Provide `stopReactive(): Future<Void>` that cancels timer(s) and completes when canceled; make existing `stop()` a thin wrapper.
  - Keep debug logs; retain detailed error classification (connection vs schema errors) but inside reactive flow.

Example (sketch):
```java
private long timerId;
public Future<Void> startReactive(Vertx vertx) {
  if (running) return Future.succeededFuture();
  running = true;
  timerId = vertx.setPeriodic(checkInterval.toMillis(), id -> runChecksReactive());
  return Future.succeededFuture();
}
public Future<Void> stopReactive(Vertx vertx) {
  if (!running) return Future.succeededFuture();
  running = false;
  vertx.cancelTimer(timerId);
  return Future.succeededFuture();
}
```

### PeeGeeQManager.java
- Current:
  - Uses Vert.x timers correctly for metrics, DLQ cleanup, and recovery; guards synchronous start/stop on event loop.
  - Constructs Vertx with `Objects.requireNonNullElseGet(vertx, Vertx::vertx)`.
  - Calls `healthCheckManager.startReactive()` but stops via `healthCheckManager.stop()` (synchronous).
- Recommendations:
  - Default Vert.x selection: if `vertx == null` and running inside a Vert.x context, prefer `Vertx.currentContext().owner()`; else `Vertx.vertx()`.
  - After HealthCheckManager exposes stopReactive(), switch stop flow to compose that future.

Example (selection fallback):
```java
this.vertx = (vertx != null) ? vertx
  : (Vertx.currentContext() != null ? Vertx.currentContext().owner() : Vertx.vertx());
```

### PgConnectionConfig.java
- Current:
  - Provides getters and a builder aligned to Vert.x needs, but also includes `getJdbcUrl()`.
- Recommendations:
  - Mark `getJdbcUrl()` as `@Deprecated` with javadoc pointing to using `PgConnectOptions` in Vert.x reactive clients.
  - Add `toString()` that redacts password and prints host/db/schema/ssl flags only.

Example:
```java
@Deprecated // Use Vert.x PgConnectOptions instead
public String getJdbcUrl() { ... }
@Override public String toString() {
  return "PgConnectionConfig{host="+host+", port="+port+", database="+database+", schema="+schema+", ssl="+sslEnabled+"}";
}
```

### PgPoolConfig.java
- Status: Compliant with Vert.x 5.x semantics (Duration timeouts, explicit maxWaitQueueSize, shared pools). Keep as-is.

### PgConnectionManager.java
- Status: Compliant. Idempotent pool creation via `computeIfAbsent`, `PgBuilder.pool()`, early validation, async close with `Future.all`, and real health check using `SELECT 1`.

### PgClientFactory.java
- Status: Compliant. Proper DI of Vertx via PgConnectionManager, idempotent client creation, event-loop guard in blocking `close()`, async `removeClientAsync` and `closeAsync`.

### PeeGeeQDatabaseSetupService.java
- Current:
  - Reuses `Vertx.currentContext().owner()` when in-context; otherwise `Vertx.vertx()` (good).
  - Uses its own `ExecutorService` and blocking `get()` on futures for setup steps.
  - Creates `PeeGeeQManager` via the 1-arg constructor, which creates a new Vertx internally.
- Recommendations:
  - Reuse the existing Vertx by calling the 3-arg constructor: `new PeeGeeQManager(config, new SimpleMeterRegistry(), this.vertx)` (or an injected registry). This avoids a second Vertx.
  - Prefer `vertx.executeBlocking` for offloading blocking parts, or—better—convert the whole setup flow to a reactive `Future` chain to avoid bespoke thread pools.
  - Keep the existing fallback to minimal core schema for extension permission failures; that is consistent with “fail fast with clear errors and recover when expected.”

---

## Implementation plan (aligned with Vertx-5x-Patterns-Guide and pgq-coding-principles)

### Goals and constraints
- Apply Vert.x 5.x patterns end-to-end: DI of Vertx, composable Futures, timers over executors, reactive lifecycle.
- Keep debug logging; fail fast with clear errors; no JDBC in main code.
- Reuse `Vertx.currentContext().owner()` before creating a new Vertx.
- Validate incrementally using TestContainers (postgres:15.13-alpine3.20); read logs thoroughly after each run.

### Change set overview
- HealthCheckManager: replace ScheduledExecutorService with Vert.x timers; remove blocking waits; add startReactive/stopReactive.
- PeeGeeQManager: prefer current context Vertx when none provided; compose stop with healthCheckManager.stopReactive().
- PeeGeeQDatabaseSetupService: pass existing Vertx into PeeGeeQManager; move away from bespoke cached thread pool to executeBlocking or fully reactive flow.
- PgConnectionConfig: deprecate getJdbcUrl(); add sanitized toString().

### Work breakdown (incremental, validate after each step)

Step 1 — HealthCheckManager (highest priority)
- Changes:
  - Accept Vertx in `startReactive(Vertx)` (or store via ctor) and replace `scheduleAtFixedRate` with `vertx.setPeriodic(checkInterval.toMillis(), ...)`.
  - Implement a non-blocking `runChecksReactive()` that composes Futures; aggregate via `Future.all` as needed.
  - Add `stopReactive(): Future<Void>` that cancels timer(s); keep `start()/stop()` as thin wrappers that avoid event-loop blocking.
- Acceptance:
  - No ScheduledExecutorService remains; no blocking `.get(...)` calls; periodic checks update results; stopReactive cancels timers.
- Validation:
  - Unit: tiny interval verifies at least one cycle; stopReactive prevents further executions.
  - Integration (TestContainers): DB check passes with SELECT 1; schema errors surface clearly without stack-trace spam.

Step 2 — PeeGeeQManager
- Changes:
  - Choose Vertx as: provided Vertx, else `Vertx.currentContext().owner()`, else `Vertx.vertx()`.
  - In shutdown, compose with `healthCheckManager.stopReactive()` and ensure background timers are canceled before completing.
- Acceptance:
  - Provided Vertx is reused; no extra Vertx instances appear in logs; stopReactive completes after timers canceled.
- Validation:
  - Unit: identity check on provided Vertx; stopReactive completes promptly.
  - Integration: startReactive → stopReactive has no stray timers/threads.

Step 3 — PeeGeeQDatabaseSetupService
- Changes:
  - Construct `PeeGeeQManager` with existing Vertx (and a registry): `new PeeGeeQManager(config, new SimpleMeterRegistry(), this.vertx)`.
  - Transition off bespoke cached thread pool: use `vertx.executeBlocking` for temporary blocking portions or refactor to a fully reactive Future chain.
- Acceptance:
  - Single Vertx instance in use; setup path avoids indefinite blocking; logs confirm reuse of Vertx.
- Validation:
  - Integration: end-to-end setup runs and tears down cleanly with TestContainers.

Step 4 — PgConnectionConfig
- Changes:
  - Annotate `getJdbcUrl()` with `@Deprecated` (document Vert.x PgConnectOptions usage) and add `toString()` with password redaction.
- Acceptance:
  - No password leakage in logs; calls to getJdbcUrl() produce deprecation warnings during compilation.
- Validation:
  - Unit: `toString()` excludes credentials; contains host/db/schema/ssl flags.

### Test plan (smallest scope first)
- Unit tests:
  - HealthCheckManagerReactiveTest: verify periodic run and stopReactive cancellation; no blocking usage.
  - PeeGeeQManagerVertxReuseTest: verify provided Vertx reuse and clean stopReactive.
  - PgConnectionConfigTest: verify redacted toString and presence of @Deprecated on getJdbcUrl.
- Integration tests (TestContainers postgres:15.13-alpine3.20):
  - Health checks against real DB: success path and schema-error path; no stack trace spam; proper logging.
  - Setup service: create/destroy cycle using single Vertx; clean shutdown with no stray timers/threads.
- Run guidance: prefer targeted runs and detailed logs
  - `mvn -q -Dtest=HealthCheckManagerReactiveTest test -X`
  - `mvn -q -Dtest=PeeGeeQManagerVertxReuseTest test -X`
  - `mvn -q -Dtest='*Integration*' test -X`

### Logging, error handling, principles alignment
- Keep debug logs; do not remove.
- Fail fast; no masking of real problems; recover only where explicitly intended.
- Use composable Future patterns (`.compose()`, `.onSuccess()`, `.onFailure()`, `.recover()`).
- Prefer `Pool.withConnection/withTransaction` and TransactionPropagation.CONTEXT in layered services.

### Risks and mitigations
- Timer semantics change in HealthCheckManager → mitigate with short-interval tests and explicit assertions.
- API surface change (startReactive/stopReactive) → keep thin synchronous wrappers for compatibility during transition.
- Removing bespoke thread pools → phase in executeBlocking, then fully reactive composition; validate after each step.

### Definition of done (DoD)
- HealthCheckManager uses Vert.x timers; exposes startReactive/stopReactive; no blocking waits; tests green.
- PeeGeeQManager reuses provided/current-context Vertx; shutdown composes reactive stops; no stray timers/threads.
- PeeGeeQDatabaseSetupService passes existing Vertx to PeeGeeQManager; no unnecessary Vertx instances; minimal blocking.
- PgConnectionConfig has `@Deprecated getJdbcUrl()` and a sanitized `toString()`; no sensitive data in logs.
- All new and existing unit/integration tests pass with TestContainers; logs confirm test methods executed and clean shutdown.
---

## Notes on configuration defaults
- PgPoolConfig chooses a bounded wait queue (128). Vert.x 5 default is -1 (unbounded). Keeping an explicit bound is fine; monitor and tune per workload.
- Timeout distinctions per guide: PoolOptions.connectionTimeout (time to get a pooled connection) vs connectOptions.connectTimeout (TCP/DB connect). Ensure both are set where appropriate.

---

## References (internal)
- docs/vertx5-migration-code-reviews/Vertx-5x-Patterns-Guide.md
- docs/vertx5-migration-code-reviews/vertx5-migration-general-guide.md

