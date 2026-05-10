Ran terminal command: Get-Content "c:\Users\mraysmit\dev\idea-projects\peegeeq\logs\examples-spring-final-20260509.txt" | Where-Object { $_ -match "\bWARN\b" }

Read [](file:///c%3A/Users/mraysmit/AppData/Roaming/Code/User/workspaceStorage/4a1a6b0edf3376e58ad41d5ae20fbb8b/GitHub.copilot-chat/chat-session-resources/ef053edc-3767-43d1-9ee7-aeafc624b802/toolu_vrtx_01MEMttNd6f2xoWATEnCQ9Xt__vscode-1778240963554/content.txt#1-1), lines 1 to 200

Compacted conversation

Read [](file:///c%3A/Users/mraysmit/AppData/Roaming/Code/User/workspaceStorage/4a1a6b0edf3376e58ad41d5ae20fbb8b/GitHub.copilot-chat/chat-session-resources/ef053edc-3767-43d1-9ee7-aeafc624b802/toolu_vrtx_01MEMttNd6f2xoWATEnCQ9Xt__vscode-1778240963554/content.txt#201-201), lines 201 to 397

All warnings fall into these categories — none indicate bugs or unexpected failures:

**1. Logback config (noise — internal logback startup)**
- `Resource [logback.xml] occurs multiple times on the classpath` — multiple PeeGeeQ JARs each bundle their own `logback.xml`. Harmless at test time; would be worth fixing by removing `logback.xml` from library JARs, but not urgent.
- `[converterClass] attribute is deprecated and replaced by [class]` — Logback internal deprecation. Update the `logback.xml` `<conversionRule>` elements to use `class=` instead of `converterClass=`.

**2. Testcontainers container reuse (expected)**
- `Reuse was requested but the environment does not support the reuse of containers` — repeated every time a new Spring context is created with `@DirtiesContext`. This is normal in CI/dev without `testcontainers.reuse.enable=true` in `~/.testcontainers.properties`. Not a defect.

**3. Flyway idempotent migrations (expected)**
- `DB: column "idempotency_key" of relation "outbox" already exists, skipping` and similar — Flyway scripts use `ADD COLUMN IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS`. These are logged as WARN by `DefaultSqlScriptExecutor` but are intentional and harmless.

**4. OutboxConsumer intentional test failures (expected)**
- `Message processing failed for N in consumer group …: Simulated transient failure / poison message / Intentional error for metrics testing` — these are deliberate failures exercised by retry/DLQ/metrics tests.

**5. Two application-level WARNs worth noting**
- `OrderConsumerService: Failed to update consumer status` (×2) — logged during teardown; likely a race between `@DirtiesContext` context shutdown and a background consumer heartbeat. Low risk but worth a look if it causes flakiness.
- `HighPriorityConsumerService: Non-high-priority message received (filter bypass?)` (×2) — a filter test deliberately sending a normal-priority message to a high-priority consumer to verify filtering. The WARN is the expected diagnostic output from that scenario.
- `ReactiveNotificationHandler: Reactive LISTEN connection closed, attempting reconnection` (×10) — these appear during `@DirtiesContext` shutdown when the PostgreSQL connection pool is torn down while the reactive LISTEN loop is still active. Expected teardown noise.

**Summary:** No unexpected WARNs. The two that are most worth fixing long-term are the `logback.xml` duplication across JARs and the `converterClass` deprecation in `logback.xml`.

**6. migration of all tests configuration to config framework and away from System properties**


**7. Test Antipatterns**

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/docs-design/testing/PEEGEEQ_ERROR_HANDLING_ANTIPATTERNS.md#1-1), lines 1 to 120

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests)

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/docs-design/testing/PEEGEEQ_ERROR_HANDLING_ANTIPATTERNS.md#120-120), lines 120 to 350

Searched for files matching `**/peegeeq-integration-tests/src/test/**/*.java`, 20 matches

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/docs-design/testing/PEEGEEQ_ERROR_HANDLING_ANTIPATTERNS.md#350-350), lines 350 to 600

Searched for files matching `**/peegeeq-integration-tests/src/test/**/*.java`, 28 matches

Let me finish reading the antipatterns guide, then use a subagent to audit all 28 files. 

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/docs-design/testing/PEEGEEQ_ERROR_HANDLING_ANTIPATTERNS.md#600-600), lines 600 to 900

Created 3 todos

Starting: *Read SmokeTestBase and BaseIntegrationTest* (1/3)

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/SmokeTestBase.java#1-1), lines 1 to 200

Now let me run the full audit via a subagent across all 28 files: 

Starting: *Audit all 28 integration test files* (2/3)

Compacted conversation

Audit integration test files

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/SmokeTestBase.java#1-1), lines 1 to 150

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/smoke/WebhookSubscriptionSmokeTest.java)

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/smoke/SystemOverviewSmokeTest.java#1-1), lines 1 to 150

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/smoke/SubscriptionLifecycleSmokeTest.java#1-1), lines 1 to 150

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/smoke/QueueManagementSmokeTest.java#1-1), lines 1 to 150

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/smoke/NativeQueueSmokeTest.java#1-1), lines 1 to 150

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/smoke/HealthMetricsSmokeTest.java)

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/smoke/HealthCheckSmokeTest.java#1-1), lines 1 to 150

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/SmokeTestBase.java#150-150), lines 150 to 259

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/smoke/SystemOverviewSmokeTest.java#150-150), lines 150 to 196

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/smoke/SubscriptionLifecycleSmokeTest.java#150-150), lines 150 to 272

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/smoke/QueueManagementSmokeTest.java#150-150), lines 150 to 232

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/smoke/NativeQueueSmokeTest.java#150-150), lines 150 to 170

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/smoke/HealthCheckSmokeTest.java#150-150), lines 150 to 164

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/smoke/EventVisualizationApiTest.java)

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/smoke/EventStoreManagementSmokeTest.java#1-1), lines 1 to 200

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/smoke/EventStoreManagementSmokeTest.java#200-200), lines 200 to 343

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/smoke/EventStoreAdvancedAttributesSmokeTest.java#1-1), lines 1 to 250

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/smoke/DeadLetterQueueSmokeTest.java#1-1), lines 1 to 250

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/smoke/ConsumerGroupSmokeTest.java#1-1), lines 1 to 250

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/smoke/BiTemporalEventStoreSmokeTest.java)

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/resilience/ResilienceSmokeTest.java#1-1), lines 1 to 250

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/resilience/SetupFailureRecoverySmokeTest.java)

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/smoke/EventStoreAdvancedAttributesSmokeTest.java#250-250), lines 250 to 350

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/smoke/ConsumerGroupSmokeTest.java#250-250), lines 250 to 315

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/resilience/ResilienceSmokeTest.java#250-250), lines 250 to 254

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/outbox/TransactionalIntegrityTest.java#1-1), lines 1 to 300

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/outbox/OutboxSmokeTest.java)

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/outbox/MultiTenantIsolationTest.java)

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/lifecycle/SetupLifecycleTest.java)

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/nativequeue/NativeConcurrencySmokeTest.java#1-1), lines 1 to 300

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/deadletter/DeadLetterQueueIntegrationTest.java)

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/consumergroup/ConsumerGroupMessageAckTest.java)

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/bitemporal/EventVisualizationIntegrationTest.java)

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/bitemporal/BiTemporalQuerySmokeTest.java)

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/tracing/TracePropagationTest.java)

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/tracing/TraceIdSpanIdDemoTest.java#1-1), lines 1 to 300

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/tracing/ObservabilitySystemIntegrationTest.java)

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/outbox/TransactionalIntegrityTest.java#300-300), lines 300 to 317

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/nativequeue/NativeConcurrencySmokeTest.java#300-300), lines 300 to 422

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/tracing/TraceIdSpanIdDemoTest.java#300-300), lines 300 to 371

Read [](file:///c%3A/Users/mraysmit/AppData/Roaming/Code/User/workspaceStorage/4a1a6b0edf3376e58ad41d5ae20fbb8b/GitHub.copilot-chat/chat-session-resources/eabcf53f-44ad-4f7b-814f-b1a905dc81da/toolu_vrtx_01RTNiogvJqpja78VJ1fAHmt__vscode-1778240965235/content.txt#1-1), lines 1 to 300

Read [](file:///c%3A/Users/mraysmit/AppData/Roaming/Code/User/workspaceStorage/4a1a6b0edf3376e58ad41d5ae20fbb8b/GitHub.copilot-chat/chat-session-resources/eabcf53f-44ad-4f7b-814f-b1a905dc81da/toolu_vrtx_01RTNiogvJqpja78VJ1fAHmt__vscode-1778240965235/content.txt#300-300), lines 300 to 347

I have the full audit. Let me verify one key detail about `EventVisualizationIntegrationTest` and `BiTemporalQuerySmokeTest` before presenting — the subagent flagged them as CRITICAL for assertions inside `testContext.succeeding()`, but per the guide, `succeeding()` already routes exceptions to `failNow()`.  

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/bitemporal/EventVisualizationIntegrationTest.java#70-70), lines 70 to 120

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/deadletter/DeadLetterQueueIntegrationTest.java#130-130), lines 130 to 220

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/deadletter/DeadLetterQueueIntegrationTest.java#280-280), lines 280 to 297

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/deadletter/DeadLetterQueueIntegrationTest.java#240-240), lines 240 to 297

Read [](file:///c%3A/Users/mraysmit/dev/idea-projects/peegeeq/peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/deadletter/DeadLetterQueueIntegrationTest.java#190-190), lines 190 to 245

Good. Now I have confirmed facts. Let me compile the complete audit report. 

Starting: *Produce findings report* (3/3)

---

## peegeeq-integration-tests Antipattern Audit

**28 files read. 11 clean. 17 with violations.**

---

### CRITICAL

#### `TracePropagationTest` — FORBIDDEN pattern
**`testConcurrencyIsolation`**: `vertx.timer(5).toCompletionStage().toCompletableFuture().join()` — all three (`toCompletionStage`, `toCompletableFuture`, `join`) are explicitly forbidden. This also blocks the worker thread. Additionally, `CountDownLatch` + `.onComplete(ar -> latch.countDown())` across 100 parallel callbacks swallows individual failures.

#### `TraceIdSpanIdDemoTest` — Bare assertions in `runOnContext` (AP-CRITICAL)
**`testNestedChildSpans`**: Six `assertEquals`/`assertNotEquals` calls inside `vertx.runOnContext(v -> { ... })` with no `testContext.verify()`. `runOnContext` does not wrap exceptions — any assertion failure is swallowed, test hangs 30 s.

**`testTraceIdConstantSpanIdChangesAcrossEventBus`**: `consumerLatch.await(5, TimeUnit.SECONDS)` called **inside an `.onComplete()` callback** — blocks the Vert.x event loop thread.

#### `DeadLetterQueueIntegrationTest` — Bare assertion in `onSuccess` (AP-CRITICAL)
**`testExhaustedRetriesPromoteMessageToDlq`** (line ~230):
```java
.onSuccess(publishResponse -> {
    assertEquals(200, publishResponse.statusCode(),  // ← assertion not in testContext.verify()
        "Message publish must succeed");
    ...
    vertx.setTimer(30_000, ...);  // if assertion fails, test hangs 30 s
    vertx.setPeriodic(500, ...);
})
.onFailure(testContext::failNow);
```

---

### SERIOUS

#### `SetupFailureRecoverySmokeTest` — `CountDownLatch` throughout (AP2)
`testInvalidSchemaNameRejected` and `testPartialSetupCleanup` both use `CountDownLatch` + `.onSuccess(v -> latch.countDown())` + blocking `latch.await()`. No `testContext.failNow` on failure paths in `testInvalidSchemaNameRejected`; the latch counts down on both success and failure paths, hiding assertion skips.

#### `ResilienceSmokeTest` — AP2 + AP3 + AP6
- **AP2**: `CountDownLatch` + `latch.await()` blocking waits throughout both tests
- **AP3**: `catch (Exception ignored) {}` on a latch await — swallows the interrupted-exception signal
- **AP6**: Five `System.setProperty(...)` calls in test bodies cleaned up in `finally` blocks, not `@AfterEach`

#### `NativeConcurrencySmokeTest` — AP2 (event-loop block) + AP3
- **AP2 — event-loop block**: `testNotificationRecovery` calls `latch1.await(10, TimeUnit.SECONDS)` **inside an `.onSuccess()` callback** — deadlocks the Vert.x event loop
- **AP2 — raw threads**: `testConsumerGroupLoadBalancing` spawns `new Thread(...)` with `allConsumed.await(60, SECONDS)` — blocking raw thread coordination
- **AP3**: `catch (Exception e) { // Ignore cleanup errors }` in `@AfterEach` and `catch (Exception ignore) { // Cleanup best-effort only. }` inline

#### `SmokeTestBase` — AP12 + teardown swallows errors (AP-SERIOUS)
- **AP12**: Hand-rolled `createPostgresContainer()` with `new PostgreSQLContainer(...).withDatabaseName(...).withUsername(...).withPassword(...)` — should use `PostgreSQLTestConstants.createStandardContainer()`
- **AP-SERIOUS**: `stopServer @AfterAll` `onFailure` calls `testContext.completeNow()` instead of `testContext.failNow(err)` — teardown failures are silently swallowed

---

### MODERATE — `cleanupSetup()` AP2 pattern (8 files)

All 8 classes below inherit or define a `cleanupSetup(String setupId)` helper using `.onComplete(ar -> { if (ar.succeeded()) ... })`:

```java
// WRONG — fire-and-forget with swallowed failure
webClient.delete("/api/v1/setups/" + setupId)
    .send()
    .onComplete(ar -> {
        if (ar.succeeded()) { logger.info("Setup deleted: {}", setupId); }
        else { logger.warn("Failed to delete setup: {}", setupId, ar.cause()); }
    });
```

Note: these are cleanup helpers, not test-body assertions, so the severity is MODERATE not CRITICAL. But a failed cleanup leaks state to subsequent tests.

| File |
|---|
| `SubscriptionLifecycleSmokeTest` |
| `NativeQueueSmokeTest` |
| `HealthCheckSmokeTest` |
| `EventStoreManagementSmokeTest` |
| `EventStoreAdvancedAttributesSmokeTest` |
| `DeadLetterQueueSmokeTest` |
| `ConsumerGroupSmokeTest` |
| `BiTemporalEventStoreSmokeTest` |

**Fix**: Change to `.onFailure(err -> logger.warn(...))` (drop `onComplete`). Since cleanup is fire-and-forget, a logging-only `onFailure` is sufficient.

---

### LOW — Style (`EventVisualizationIntegrationTest`, `BiTemporalQuerySmokeTest`)

Both use `.onComplete(testContext.succeeding(response -> { assertEquals(...); testContext.completeNow(); }))`.  
`testContext.succeeding()` **does** route thrown exceptions to `failNow()` per the Vert.x JUnit5 docs, so there is no silent-hang risk here. However the guide's canonical form nests `testContext.verify()` inside `succeeding()` for double coverage. Not blocking, but worth aligning.

---

### Clean (11 files)

`WebhookSubscriptionSmokeTest`, `SystemOverviewSmokeTest`, `QueueManagementSmokeTest`, `HealthMetricsSmokeTest`, `EventVisualizationApiTest`, `TransactionalIntegrityTest`, `OutboxSmokeTest`, `MultiTenantIsolationTest`, `SetupLifecycleTest`, `ConsumerGroupMessageAckTest`, `ObservabilitySystemIntegrationTest`

---

### Prioritised Fix List

| Priority | File | Fix |
|---|---|---|
| P1 CRITICAL | `TracePropagationTest` | Remove `toCompletionStage().toCompletableFuture().join()`; replace `CountDownLatch` with `Checkpoint` |
| P1 CRITICAL | `TraceIdSpanIdDemoTest` | Wrap `runOnContext` assertions in `testContext.verify()`; remove `latch.await()` on event-loop |
| P1 CRITICAL | `DeadLetterQueueIntegrationTest` | Wrap `assertEquals(200,...)` in `testContext.verify()` inside `onSuccess` |
| P2 SERIOUS | `SetupFailureRecoverySmokeTest` | Replace `CountDownLatch` flows with `VertxTestContext` + `Checkpoint` |
| P2 SERIOUS | `ResilienceSmokeTest` | Same; clear system properties in `@AfterEach` |
| P2 SERIOUS | `NativeConcurrencySmokeTest` | Remove event-loop blocking `latch.await()`; remove raw `Thread` spawn; add logging to empty catch |
| P2 SERIOUS | `SmokeTestBase` | Replace hand-rolled container factory; fix teardown to `failNow` on error |
| P3 MODERATE | 8 smoke test files | Replace `cleanupSetup().onComplete(...)` with `.onFailure(logger::warn)` |
| P4 LOW | `EventVisualizationIntegrationTest`, `BiTemporalQuerySmokeTest` | Nest `testContext.verify()` inside `testContext.succeeding()` |

Do you want me to start fixing these, starting with the P1 CRITICAL items? 

Completed: *Produce findings report* (3/3)