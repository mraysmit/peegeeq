# Last 5 Check-Ins Change Log

**Date**: March 15, 2026  
**Status**: Complete  
**Scope**: EventStore async migration fallout, native queue shutdown correctness, setup teardown ordering, smoke-test shutdown hygiene, full-reactor validation

## Note On Terminology

No new git commits were created during this workstream. In this document, "last 5 check-ins" means the last five completed engineering increments delivered during the session.

---

## Executive Summary

The last five check-ins completed a full closure cycle on two related themes:

1. Finish the wider fallout cleanup from the `EventStore` migration from `CompletableFuture` to Vert.x `Future`.
2. Turn teardown-time LISTEN/reconnect warnings into explicit regression tests, then fix the lifecycle bugs at the root.

The result is:

- broader async migration validation restored to green,
- native queue consumers no longer reconnect after unsubscribe or factory-driven shutdown,
- setup teardown now closes owned resources before manager shutdown,
- smoke coverage now asserts shutdown cleanliness under real setup destruction,
- full reactor validation completed successfully.

Residual follow-up remains around health-check noise during teardown, but the targeted shutdown regressions are now covered and passing.

---

## Check-In 1: Wider Async Migration Fallout Cleanup

### Objective

Validate modules downstream of the `EventStore` async API migration and fix the first round of broader-suite fallout.

### Changes Made

#### 1. Runtime durable-subscription migration made tolerant of missing default bitemporal table

**File**: `peegeeq-migrations/src/main/resources/db/migration/V012__Create_Bitemporal_Durable_Subscriptions.sql`

Changed the migration to guard index creation on `bitemporal_event_log` using `to_regclass(...)` before attempting to create the index. This prevents runtime setup failures when durable subscriptions are initialized in environments where the default bitemporal table does not yet exist.

#### 2. Example test suite updated to the new Vert.x `Future` contract

**Files**:

- `peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/bitemporal/BiTemporalEventStoreExampleTest.java`
- `peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/bitemporal/CloudEventsJsonbQueryTest.java`

Added local `await(...)` helpers and replaced stale blocking calls that assumed `CompletableFuture`-returning `EventStore` methods.

### Enhancements

- Example tests now reflect the actual public contract of the migrated `EventStore` API.
- The runtime setup path is more robust in partially provisioned environments.

### Validation

- `mvn -pl peegeeq-runtime -am test "-Dtest=RuntimeDatabaseSetupServiceIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
- `mvn -pl peegeeq-examples -am test "-Dtest=BiTemporalEventStoreExampleTest,CloudEventsJsonbQueryTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
- `mvn -pl peegeeq-rest,peegeeq-examples,peegeeq-examples-spring -am test`

All passed after the fixes.

---

## Check-In 2: Native Queue LISTEN Reconnect And Factory Ownership TDD

### Objective

Create the missing regression coverage for native queue teardown edge cases and fix the production code only after the failures were reproduced.

### Changes Made

#### 1. Added unsubscribe/reconnect regression coverage

**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ListenReconnectFaultInjectionIT.java`

Added `testUnsubscribeDoesNotReestablishListenConnection()` to prove that:

- `unsubscribe()` must not recreate the LISTEN connection,
- `listenReconnectTimerId` must be reset,
- the consumer must remain unsubscribed.

#### 2. Added factory-owned consumer shutdown regression coverage

**File**: `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PgNativeQueueShutdownTest.java`

Added `testFactoryCloseClosesCreatedConsumers()` to prove that factory shutdown must close the consumers it created and must not leave reconnect timers behind.

#### 3. Fixed reconnect gating inside the consumer

**File**: `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueConsumer.java`

Introduced `shouldMaintainListenSubscription()` and routed all reconnect-sensitive code paths through it:

- initial LISTEN setup,
- notification handling,
- close handler,
- exception handler,
- connect-failure handler,
- reconnect timer scheduling.

This ensures reconnect only happens when the consumer is both subscribed and not closed.

#### 4. Fixed resource ownership inside the native queue factory

**File**: `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueFactory.java`

Added explicit tracking of created closeable resources and ensured the factory closes them before closing the pool adapter.

Tracked resources now include:

- consumers,
- consumer groups,
- queue browsers.

### Enhancements

- Native queue teardown is now ownership-correct instead of relying on the caller to close every created resource manually.
- LISTEN reconnect behavior is now lifecycle-safe rather than only shutdown-safe.

### Validation

- New tests initially failed red in the expected places.
- After the fixes:
  - targeted native shutdown/reconnect tests passed,
  - adjacent native cleanup/resource-management suites passed.

---

## Check-In 3: Setup Teardown Ordering TDD

### Objective

Prove and fix the teardown ordering bug in `destroySetup()` where the manager was stopped before setup-owned factories and event stores were closed.

### Changes Made

#### 1. Added teardown ordering regression coverage

**File**: `peegeeq-db/src/test/java/dev/mars/peegeeq/db/setup/PeeGeeQDatabaseSetupServiceLifecycleTest.java`

Added `destroySetupShouldCloseResourcesBeforeStoppingManager()` using recording doubles injected into the internal setup maps. The test proved the old incorrect order:

- manager stop first,
- queue factory close second,
- event store close third.

#### 2. Reworked setup destruction to close resources first

**File**: `peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/PeeGeeQDatabaseSetupService.java`

Updated `destroySetup()` to:

1. close setup-owned queue factories and event stores first,
2. then call `manager.closeReactive()` instead of `manager.stopReactive()`,
3. then perform test-database cleanup when applicable.

This is the correct terminal lifecycle for a destroyed setup.

### Enhancements

- Setup teardown now preserves resource cleanup access to the manager's lifecycle and connection infrastructure while owned resources are being closed.
- The service now uses the manager's full close path, including registered close hooks.

### Validation

- The new lifecycle test failed red before the patch and passed green after it.
- An adjacent setup-service sweep also passed.

---

## Check-In 4: Smoke-Test Shutdown Hygiene And Integration Coverage

### Objective

Promote the shutdown guarantees from focused native/db tests into a real smoke-test setup/teardown path.

### Changes Made

#### 1. Strengthened smoke cleanup behavior

**File**: `peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/nativequeue/NativeConcurrencySmokeTest.java`

Changed smoke cleanup from `consumer.unsubscribe()` to `consumer.close()` so integration cleanup matches the intended terminal lifecycle.

#### 2. Added real setup-destruction shutdown regression

**File**: `peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/nativequeue/NativeConcurrencySmokeTest.java`

Added `testDestroySetupStopsNativeListenersCleanly()` which:

1. creates a real setup,
2. creates a real native consumer,
3. subscribes it,
4. destroys the setup through `DatabaseSetupService`,
5. asserts post-shutdown internal state using reflection,
6. captures logs and asserts no reconnect/error shutdown noise.

The test verifies:

- `subscriber == null`,
- `listenReconnectTimerId == -1`,
- `closed == true`,
- `subscribed == false`,
- no shutdown-period log messages containing:
  - `LISTEN connection closed unexpectedly`
  - `Reconnecting LISTEN`
  - `Failed to start LISTEN`
  - `Pool closed`

### Enhancements

- Shutdown guarantees are now tested through the actual setup service and REST/smoke infrastructure, not just isolated module tests.
- Smoke cleanup is more faithful to production shutdown semantics.

### Validation

- `NativeConcurrencySmokeTest` passed with the new regression.
- A combined smoke/native/db lifecycle sweep passed.

---

## Check-In 5: Wider Validation And Final Review Of Residual Warnings

### Objective

Rerun broader validation after all lifecycle changes and review what remains beyond the targeted fixes.

### Changes Made

#### 1. Full reactor validation rerun

Executed a full root-level `mvn test` after the teardown fixes and coverage additions.

#### 2. Reactor log review for non-failing lifecycle quality issues

Reviewed the tail of the full reactor output to identify any remaining warnings that still appear during teardown.

### Validation Outcome

- Full reactor `mvn test`: passed
- Exit code: `0`
- Broader regression confidence: restored

### Residual Findings

The build is green, but the logs still show teardown-time health-check warnings, including:

- `Reactive outbox queue health check failed: null`
- `Failed to check native queue: Pool closed`

These warnings originate from reactive health checks continuing briefly during teardown. They no longer indicate the original LISTEN reconnect bug, but they do indicate a remaining shutdown-observability race that can be hardened further.

---

## Files Changed Across The Last 5 Check-Ins

### Production Code

- `peegeeq-migrations/src/main/resources/db/migration/V012__Create_Bitemporal_Durable_Subscriptions.sql`
- `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueConsumer.java`
- `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueFactory.java`
- `peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/PeeGeeQDatabaseSetupService.java`

### Tests

- `peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/bitemporal/BiTemporalEventStoreExampleTest.java`
- `peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/bitemporal/CloudEventsJsonbQueryTest.java`
- `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/ListenReconnectFaultInjectionIT.java`
- `peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PgNativeQueueShutdownTest.java`
- `peegeeq-db/src/test/java/dev/mars/peegeeq/db/setup/PeeGeeQDatabaseSetupServiceLifecycleTest.java`
- `peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/nativequeue/NativeConcurrencySmokeTest.java`

### Repository Memory / Engineering Notes

- `/memories/repo/build-validation.md`
- `/memories/repo/queue-management.md`

---

## Coverage Gaps Closed During This Work

The following previously missing shutdown regressions are now covered:

1. `unsubscribe()` must not trigger LISTEN reconnect.
2. `factory.close()` must close created consumers.
3. `destroySetup()` must close resources before manager shutdown and must not leave active LISTEN state behind.
4. Teardown logs must remain free of reconnect/error events once shutdown begins.

---

## Commands And Validation Highlights

### Targeted Validation

- `mvn -pl peegeeq-runtime -am test "-Dtest=RuntimeDatabaseSetupServiceIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
- `mvn -pl peegeeq-examples -am test "-Dtest=BiTemporalEventStoreExampleTest,CloudEventsJsonbQueryTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
- `mvn -pl peegeeq-rest,peegeeq-examples,peegeeq-examples-spring -am test`

### Module-Level Lifecycle Validation

- native reconnect and shutdown regression suites: passed
- db setup lifecycle regression suite: passed
- smoke/native/db combined lifecycle sweep: passed

### Full Validation

- `mvn test`: passed with exit code `0`

---

## Remaining Follow-Up

No known failing tests remain from this workstream.

The main remaining hardening item is teardown-time health-check noise. A future improvement should stop or suppress reactive health checks cleanly before pool shutdown so teardown logs remain entirely clean even under rapid smoke-test setup/create/delete cycles.

---

## Final Outcome

The last five check-ins moved the codebase from:

- broader async migration fallout still being resolved,
- teardown reconnect warnings not covered by tests,
- factory and setup teardown ownership gaps,
- smoke cleanup not asserting shutdown cleanliness,

to:

- wider async migration compatibility restored,
- targeted and smoke-level shutdown regressions in place,
- native queue and setup teardown logic corrected,
- full reactor validation restored to green.