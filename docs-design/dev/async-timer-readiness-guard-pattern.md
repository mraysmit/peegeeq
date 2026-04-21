# Developer Advice: Replacing `setTimer` Readiness Guards with Reactive Chains

## The Anti-Pattern: `setTimer` as a Readiness Guard

A common mistake in Vert.x tests is using a fixed-delay timer to wait for an async setup operation to complete before acting on it.

```java
// WRONG — races against actual readiness
consumer.subscribe(handler);
vertx.setTimer(1000, id -> {
    producer.send("test message");
});
```

This is a **readiness guard disguised as a delay**. The timer fires after an arbitrary wall-clock duration, not when the setup is actually complete. It causes:

- Flaky tests on slow CI or under load (timer too short)
- Wasted time on fast machines (timer too long)
- Hidden coupling between test duration and infrastructure latency

> **Rule:** Never use `vertx.setTimer` to wait for an async operation to complete. If the operation produces a `Future`, chain `.onSuccess()` instead.

---

## The Specific Case: `MessageConsumer.subscribe()`

`MessageConsumer.subscribe(MessageHandler<T>)` returns `Future<Void>`. The future completes when the underlying PostgreSQL `LISTEN` acknowledgement is received (for `LISTEN_NOTIFY_ONLY` and `HYBRID` modes) or immediately (for `POLLING_ONLY`).

Tests must chain work off that future rather than guessing when LISTEN is ready.

---

## Correct Patterns

### Single consumer

```java
// BEFORE
consumer.subscribe(handler);
vertx.setTimer(1000, id -> {
    producer.send("message").onFailure(testContext::failNow);
});

// AFTER
consumer.subscribe(handler)
    .onSuccess(ignored -> producer.send("message").onFailure(testContext::failNow))
    .onFailure(testContext::failNow);
```

### Multiple consumers

```java
// AFTER — wait for all LISTEN acknowledgements before sending
Future.all(
    consumer1.subscribe(handler1),
    consumer2.subscribe(handler2)
)
.onSuccess(ignored -> {
    producer.send("message").onFailure(testContext::failNow);
})
.onFailure(testContext::failNow);
```

### Loop of consumers

```java
// AFTER — collect subscribe futures, then send
List<Future<?>> subscribeFutures = new ArrayList<>();
for (MessageConsumer<String> c : consumers) {
    subscribeFutures.add(c.subscribe(handler));
}
Future.all(subscribeFutures)
    .onSuccess(ignored -> {
        // send messages
    })
    .onFailure(testContext::failNow);
```

### Preserving inner phase timers

Not every `setTimer` is a readiness guard. Timers used to simulate processing delays or introduce deliberate phase gaps **inside** an already-started flow must be kept.

```java
// Only the outer subscribe-readiness timer is removed.
// The inner 500ms phase gap is a deliberate test design choice — keep it.
consumer.subscribe(handler)
    .onSuccess(ignored -> {
        producer.send("phase 1 message");
        vertx.setTimer(500, id -> {          // PRESERVED — phase delay, not readiness guard
            producer.send("phase 2 message");
        });
    })
    .onFailure(testContext::failNow);
```

**Heuristic:** If the timer fires to wait for an operation to *become ready*, replace it. If it fires to simulate *time passing after readiness is confirmed*, keep it.

---

---

## Broader `setTimer` Anti-Patterns Found in This Codebase

The readiness-guard is the most common misuse, but there are several other forms. The following are real examples found in this project.

---

### Anti-pattern 1: Arbitrary drain delay before a destructive operation (production code)

**File:** `peegeeq-db/src/main/java/dev/mars/peegeeq/db/setup/DatabaseTemplateManager.java`

```java
// WRONG — assumes 100 ms is long enough for terminated connections to drain
return connection.preparedQuery(terminateConnectionsSql)
    .execute(Tuple.of(databaseName))
    .compose(rowSet -> {
        // Wait a brief moment for connections to fully terminate using Promise
        return Future.<Void>future(promise -> {
            vertx.setTimer(100, id -> promise.complete());  // ← timing assumption, not a guarantee
        })
        .compose(v -> {
            String dropSql = "DROP DATABASE IF EXISTS \"" + databaseName + "\"";
            return connection.query(dropSql).execute();
        });
    });
```

`pg_terminate_backend` is asynchronous at the PostgreSQL level — backends may not have disconnected
by the time the timer fires. The correct approach is to retry the `DROP DATABASE` or poll
`pg_stat_activity` until the connection count is zero.

```java
// BETTER — retry DROP until connections have actually gone away
private Future<Void> dropWhenEmpty(SqlConnection connection, String databaseName) {
    String checkSql = "SELECT COUNT(*) AS n FROM pg_stat_activity WHERE datname = $1 AND pid <> pg_backend_pid()";
    return connection.preparedQuery(checkSql)
        .execute(Tuple.of(databaseName))
        .compose(rows -> {
            int remaining = rows.iterator().next().getInteger("n");
            if (remaining == 0) {
                return connection.query("DROP DATABASE IF EXISTS \"" + databaseName + "\"").execute().mapEmpty();
            }
            // recurse after a short back-off
            return Future.future(p -> vertx.setTimer(50, id -> p.complete()))
                .compose(v -> dropWhenEmpty(connection, databaseName));
        });
}
```

---

### Anti-pattern 2: Post-deploy readiness guard in test setUp (test code)

**File:** `peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/EndToEndValidationTest.java`

```java
// WRONG — assumes the server needs an extra second after deployVerticle() completes
vertx.deployVerticle(server)
    .onSuccess(id -> {
        deploymentId = id;
        vertx.setTimer(1000, timerId -> testContext.completeNow());  // ← unnecessary guard
    })
    .onFailure(testContext::failNow);
```

`deployVerticle()` only completes after `Verticle.start()` finishes. If `start()` returns a
`Future`, deployment waits for it. Any readiness that must hold before tests run should be
awaited inside `start()`. The timer here is wasted time on every test run.

```java
// CORRECT — trust the deploy future
vertx.deployVerticle(server)
    .onSuccess(id -> {
        deploymentId = id;
        testContext.completeNow();
    })
    .onFailure(testContext::failNow);
```

---

### Anti-pattern 3: Unconditional pass disguised as an integration test (test code)

**File:** `peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/EndToEndValidationTest.java`

```java
// WRONG — waits 1 second and passes regardless of what the server did
vertx.setTimer(1000, id -> {
    logger.info("All endpoints integration test completed");
    testContext.completeNow();  // no assertions made
});
```

This test always passes. It exercises no behaviour. Either delete it or replace it with real
endpoint assertions using `HttpClient` requests and `testContext.verify(...)`.

---

### Anti-pattern 4: Log-flush padding after a synchronous appender (test code)

**File:** `peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/tracing/TracePropagationTest.java`

```java
// WRONG — the comment admits the appender is synchronous, so the delay is dead code
vertx.setTimer(100, id -> { // Give appender a moment to flush if async (it's not, but safety)
    // ... assertions on captured log lines
});
```

If the appender is synchronous, its output is visible immediately when control returns from
the logging call. The 100 ms timer introduces false confidence and hides the real test flow.
Remove the timer and assert inline.

---

### Anti-pattern 5: Negative assertion backed only by elapsed time (test code)

**File:** `peegeeq-integration-tests/src/test/java/dev/mars/peegeeq/integration/outbox/TransactionalIntegrityTest.java`

```java
// WEAK — "we waited 3 seconds and nothing arrived" is not a proof of absence
vertx.setTimer(3000, id -> {
    testContext.verify(() -> {
        assertTrue(receivedMessages.isEmpty(), "Message should not have been delivered!");
        testContext.completeNow();
    });
});
```

The assertion is correct if the system is deterministically drained, but a 3-second wall-clock
wait is a maintenance burden: it slows every test run and may still be too short under load.
Prefer driving the consumer to a known terminal state (e.g., confirmed rollback, confirmed
PENDING status in the DB) and then asserting absence synchronously.

---

## Legitimate `setTimer` Uses in Production Code

These patterns are correct and should **not** be changed.

### Exponential-backoff reconnection

```java
// ReactiveNotificationHandler.java — reconnect after LISTEN/NOTIFY connection loss
long delay = BASE_RECONNECT_DELAY * (1L << Math.min(attempt - 1, 5)); // capped exponential
vertx.setTimer(delay, timerId -> {
    if (!shutdown && !active) {
        start().onSuccess(result -> reconnectAttempts.set(0))
               .onFailure(error -> logger.error("Reconnect failed", error));
    }
});
```

The timer fires after a calculated back-off. No future is available to chain on because the
event being waited for is an *external* reconnection, not an in-process future.

### Shutdown timeout guard (race-free close)

```java
// OutboxConsumer.java — prevents closeAsync() from hanging indefinitely
long timeoutTimerId = vertx.setTimer(timeoutMs, id -> {
    logger.warn("Timed out waiting for in-flight processing during close — proceeding");
    timeoutSignal.tryComplete();
});

Future<Void> awaitInflight = Future.any(normalCompletion, timeoutSignal.future())
    .<Void>mapEmpty()
    .eventually(() -> {
        vertx.cancelTimer(timeoutTimerId);   // ← always cancelled
        return Future.succeededFuture();
    });
```

The timer ID is always cancelled after the race resolves. This is a correct use of `setTimer`
as a safety net around an uncertain completion time.

### Batch flush timeout

```java
// ServerSentEventsHandler.java — flush SSE batch if it hasn't filled up
long timerId = vertx.setTimer(connection.getMaxWaitTime(), id -> {
    sendBatchEvent(connection);
});
connection.setBatchTimerId(timerId);   // stored so it can be cancelled when the batch fills
```

The timer ID is stored and cancelled when the batch fills before the deadline. Clean pattern.

### Retry with configurable back-off

```java
// PeeGeeQRestClient.java — retry HTTP requests with configured delay
vertx.setTimer(delayMillis, timerId ->
    executeWithRetry(attemptSupplier, attempt + 1).onComplete(retryPromise));
```

The delay is driven by external configuration, not a guessed readiness interval.

---

## Blocking Test Helpers (also forbidden)

The same principle applies to blocking coordination. Do not write:

```java
// WRONG — blocks the Vert.x event loop thread
consumer.subscribe(handler);
Thread.sleep(1000);
producer.send("message");
```

Use `VertxTestContext`, `Checkpoint`, and `awaitCompletion()` for test synchronisation.

---

## Summary

| Situation | Verdict | Correct approach |
|---|---|---|
| Single consumer, LISTEN mode | ❌ anti-pattern | `.onSuccess(ignored -> sends).onFailure(tc::failNow)` |
| Multiple consumers | ❌ anti-pattern | `Future.all(sub1, sub2, ...).onSuccess(...).onFailure(...)` |
| Consumers in a loop | ❌ anti-pattern | Collect into `List<Future<?>>`, then `Future.all(list)` |
| Inner phase / processing delay | ✅ legitimate | Keep the `setTimer` — it is not a readiness guard |
| Blocking wait (`Thread.sleep`) | ❌ forbidden | Never. Use `VertxTestContext` + `awaitCompletion()` |
| Arbitrary drain delay before DROP DATABASE | ❌ anti-pattern | Retry / poll `pg_stat_activity` until connections are gone |
| Post-`deployVerticle` readiness guard | ❌ anti-pattern | Trust the deploy future; await readiness inside `start()` |
| Unconditional pass with no assertions | ❌ anti-pattern | Write real assertions or delete the test |
| Log-flush padding for a synchronous appender | ❌ anti-pattern | Assert immediately; remove the timer |
| Negative assertion backed only by elapsed time | ⚠️ weak | Drive to a known terminal state, then assert synchronously |
| Exponential-backoff reconnection | ✅ legitimate | No future available; timer IS the scheduling mechanism |
| Shutdown timeout guard (cancelled on completion) | ✅ legitimate | Race `Future.any()` and always `cancelTimer` in `.eventually()` |
| Batch flush timeout (ID stored and cancellable) | ✅ legitimate | Store timer ID; cancel when batch fills before deadline |
| HTTP/DB retry with configured back-off | ✅ legitimate | Delay driven by config, not by a readiness assumption |

---

## Remediation Tracker

This section lists every known anti-pattern instance in the codebase for prioritised remediation.
Severity rating:
- **P1 — Production code**: correctness risk, fix immediately
- **P2 — Suppressed failure**: test always passes regardless of behaviour, high deception risk
- **P3 — Readiness guard**: flakiness/speed risk, moderate
- **P4 — Wall-clock data wait**: flakiness risk, moderate
- **P5 — Cosmetic**: low risk, tidy up opportunistically

---

### P1 — Production code

| File | Line | Description |
|---|---|---|
| `peegeeq-db/.../DatabaseTemplateManager.java` | 140 | 100 ms drain delay after `pg_terminate_backend` before `DROP DATABASE`. Not a guarantee. Replace with a retry loop that polls `pg_stat_activity`. |

---

### P2 — Suppressed test failure (pass-on-timeout)

These timers call `testContext.completeNow()` when the expected event does **not** arrive.
The test passes regardless of whether the system behaves correctly.

| File | Line | Method / description |
|---|---|---|
| `peegeeq-rest/.../EndToEndValidationTest.java` | 227 | `testAllEndpointsIntegration` — waits 1 s then calls `completeNow()` with zero assertions |
| `peegeeq-rest/.../WebSocketHandlerTest.java` | 301 | "Set a timeout to complete if no subscription confirmation" → `completeNow()` |
| `peegeeq-rest/.../WebSocketHandlerTest.java` | 356 | "Set a timeout to complete if no configuration confirmation" → `completeNow()` |
| `peegeeq-rest/.../SystemMonitoringHandlerTest.java` | 294 | "Set timeout in case metrics don't arrive" → `ws.close(); completeNow()` |
| `peegeeq-rest/.../SystemMonitoringHandlerTest.java` | 443 | Same pattern — WS metrics timeout → `completeNow()` |
| `peegeeq-rest/.../SystemMonitoringHandlerTest.java` | 553 | SSE metrics timeout → `completeNow()` |
| `peegeeq-rest/.../ConsumerGroupSubscriptionIntegrationTest.java` | 1010 | "SSE stream may not have sent configured event" → `completeNow()` |

**Fix:** Replace `completeNow()` in timeout handlers with `testContext.failNow(new AssertionError("..."))`.
For tests where the feature is genuinely optional/experimental, mark them `@Disabled` with a
note rather than silently passing.

---

### P3 — Post-`deployVerticle` readiness guard

All of these follow the same pattern: `"// Give server time to fully start"` inside a 1 s timer
placed immediately after `deployVerticle().onSuccess(id -> ...)`.  Since `deployVerticle`
waits for `Verticle.start()` to complete, this timer is redundant if the verticle correctly
awaits its own HTTP binding inside `start()`.

**Fix:** Remove the timer and chain the next setup step directly off the `onSuccess` callback.
If the server is not ready when `start()` completes, fix `start()` to return a `Future` that
resolves only after the `HttpServer.listen()` future completes.

| File | Line | Description |
|---|---|---|
| `peegeeq-rest/.../EndToEndValidationTest.java` | 70 | `setUp` — "Give the server a moment to fully start" |
| `peegeeq-rest/.../EventStoreIntegrationTest.java` | 83 | `setUp` — "Give server time to fully start" |
| `peegeeq-rest/.../ConsumerGroupSubscriptionIntegrationTest.java` | 96 | `setUp` — "Give server time to fully start" |
| `peegeeq-rest/.../SSEBasicStreamingIntegrationTest.java` | 113 | `setUp` — "Give server time to fully start" |
| `peegeeq-rest/.../SSEBatchingIntegrationTest.java` | 101 | `setUp` — "Give server time to fully start" |
| `peegeeq-rest/.../SSEReconnectionIntegrationTest.java` | 103 | `setUp` — "Give server time to fully start" |

---

### P4 — Wall-clock wait for data to accumulate in buffer

These timers fire after connecting to an SSE or WebSocket stream, wait N seconds,
then inspect the accumulated receive buffer. They race against actual message delivery.

**Fix:** Use an event-driven handler that acts as soon as the expected event appears in the
buffer, then call `testContext.completeNow()`. For ordering (e.g., send after SSE ready),
wait for the initial `connection` or `configured` event before triggering the next step.

| File | Line | Description |
|---|---|---|
| `peegeeq-rest/.../ConsumerGroupSubscriptionIntegrationTest.java` | 245 | Wait 2 s then read SSE buffer for `connection`/`configured` events |
| `peegeeq-rest/.../ConsumerGroupSubscriptionIntegrationTest.java` | 354 | Wait 2 s then read SSE buffer |
| `peegeeq-rest/.../ConsumerGroupSubscriptionIntegrationTest.java` | 529 | Wait 2 s then read SSE buffer |
| `peegeeq-rest/.../ConsumerGroupSubscriptionIntegrationTest.java` | 785 | Wait 1 s, update subscription, wait 1 s more (nested timers) |
| `peegeeq-rest/.../ConsumerGroupSubscriptionIntegrationTest.java` | 799 | Inner nested timer — second leg of 785 |
| `peegeeq-rest/.../ConsumerGroupSubscriptionIntegrationTest.java` | 1136 | Wait 2 s then read SSE buffer for `configured` event |
| `peegeeq-rest/.../CrossLayerPropagationIntegrationTest.java` | 262 | Wait 1 s after SSE connected before sending REST message |
| `peegeeq-rest/.../SystemMonitoringIntegrationTest.java` | 138 | Wait 1.5 s to "receive the first stats update" after WS connect |
| `peegeeq-rest/.../QueueManagementE2ETest.java` | 223 | "Wait a bit for messages to be available" before querying queue |

---

### P5 — Log-flush padding / cosmetic delays

| File | Line | Description |
|---|---|---|
| `peegeeq-integration-tests/.../TracePropagationTest.java` | 110 | 100 ms "Give appender a moment to flush if async (it's not)" |
| `peegeeq-integration-tests/.../TraceIdSpanIdDemoTest.java` | 134 | Same pattern — 100 ms guard around synchronous appender assertions |

**Fix:** Remove the timers and assert inline immediately after the logging call returns.

---

### Instances requiring individual review

These have less clear-cut fixes and need case-by-case assessment before remediation:

| File | Line | Notes |
|---|---|---|
| `peegeeq-integration-tests/.../TransactionalIntegrityTest.java` | 111 | 3 s wait then assert `receivedMessages.isEmpty()`. Negative assertion — consider polling DB for confirmed PENDING status instead. |
| `peegeeq-rest/.../CrossLayerPropagationIntegrationTest.java` | 417 | 2 s delay before second phase — investigate whether this is a legitimate phase gap or a readiness guard for the SSE stream. |

---

## Detailed Solution Outlines

### P1 — `DatabaseTemplateManager.java:140`

**Root cause:** `pg_terminate_backend()` signals backends to terminate but returns before they
have disconnected. A 100 ms timer assumes the OS scheduler has had enough time to clean them
up — a false assumption under load.

**Solution:** Replace the timer with a polling loop that retries `DROP DATABASE` until
`pg_stat_activity` reports no remaining connections. Introduce a max-attempt guard to prevent
an infinite loop on stuck backends.

```java
// Replace this:
return Future.<Void>future(promise -> {
    vertx.setTimer(100, id -> promise.complete());
})
.compose(v -> connection.query("DROP DATABASE IF EXISTS \"" + databaseName + "\"").execute());

// With this:
.compose(rowSet -> dropWhenDrained(connection, databaseName, 0));

private Future<RowSet<Row>> dropWhenDrained(SqlConnection conn, String dbName, int attempt) {
    if (attempt > 20) {  // ~2 s total
        return Future.failedFuture("Timed out waiting for connections to drain from database: " + dbName);
    }
    String checkSql =
        "SELECT COUNT(*) AS n FROM pg_stat_activity WHERE datname = $1 AND pid <> pg_backend_pid()";
    return conn.preparedQuery(checkSql)
        .execute(Tuple.of(dbName))
        .compose(rows -> {
            int remaining = rows.iterator().next().getInteger("n");
            if (remaining == 0) {
                String dropSql = "DROP DATABASE IF EXISTS \"" + dbName + "\"";
                return conn.query(dropSql).execute();
            }
            return Future.<Void>future(p -> vertx.setTimer(100, id -> p.complete()))
                .compose(ignored -> dropWhenDrained(conn, dbName, attempt + 1));
        });
}
```

---

### P2 — Suppressed test failures

**Root cause:** Timeout handlers call `testContext.completeNow()` instead of
`testContext.failNow(...)`. This makes the test pass regardless of whether the expected event
arrived.

**General solution for all P2 instances:** Replace `completeNow()` in timeout handlers with
`testContext.failNow(new AssertionError("..."))`. For features that are genuinely not yet
implemented, annotate with `@Disabled("feature not yet implemented: ...")` rather than
hiding the gap behind a silent pass.

#### `EndToEndValidationTest.java:227` — `testAllEndpointsIntegration`

This test has no real assertions at all. Delete it or rewrite as a proper sequence of HTTP
endpoint calls with `testContext.verify(...)`.

```java
// DELETE this test entirely, or replace body with:
Future.all(
    client.get(TEST_PORT, "localhost", "/api/v1/health").send(),
    client.get(TEST_PORT, "localhost", "/api/v1/management/overview").send()
).onSuccess(results -> {
    testContext.verify(() -> {
        assertEquals(200, results.<HttpClientResponse>resultAt(0).statusCode());
        assertEquals(200, results.<HttpClientResponse>resultAt(1).statusCode());
    });
    testContext.completeNow();
}).onFailure(testContext::failNow);
```

#### `WebSocketHandlerTest.java:301` and `:356` — WS subscription / configuration timeouts

```java
// BEFORE
vertx.setTimer(5000, id -> {
    ws.close();
    testContext.completeNow();   // silent pass
});

// AFTER
vertx.setTimer(5000, id -> {
    ws.close();
    testContext.failNow(new AssertionError(
        "WebSocket did not send 'subscribed'/'configured' confirmation within 5 s"));
});
```

Note: if the WS feature is not yet implemented, the outer `.onFailure` already calls
`testContext.completeNow()` — those should also become `testContext.failNow(...)` or the
test should be `@Disabled`.

#### `SystemMonitoringHandlerTest.java:294` and `:443` — WS metrics timeout

```java
// BEFORE
vertx.setTimer(10000, id -> {
    if (!metricsReceived.get()) {
        logger.warn("Metrics not received within timeout");
        ws.close();
        testContext.completeNow();   // silent pass
    }
});

// AFTER
vertx.setTimer(10000, id -> {
    if (!metricsReceived.get()) {
        ws.close();
        testContext.failNow(new AssertionError("WS metrics not received within 10 s"));
    }
});
```

#### `SystemMonitoringHandlerTest.java:553` — SSE metrics timeout

Same pattern as above. The `completeNow()` in the timeout handler becomes:
```java
testContext.failNow(new AssertionError("SSE metrics event not received within 10 s"));
```

#### `ConsumerGroupSubscriptionIntegrationTest.java:1010` — SSE configured event timeout

```java
// BEFORE — test logs a warning and passes
testContext.completeNow();

// AFTER — fail clearly so the gap is visible
testContext.failNow(new AssertionError(
    "SSE stream did not send 'configured' event within 10 s. Data: " +
    events.substring(0, Math.min(500, events.length()))));
```

---

### P3 — Post-`deployVerticle` readiness guards (6 test files)

**Root cause:** `PeeGeeQRestServer.start(Promise<Void>)` correctly chains
`httpServer.listen(port)` and only calls `startPromise.complete()` after the listen future
resolves. This means `deployVerticle()` already waits for the HTTP port to be bound before
its future completes. The 1 s timer is dead time on every setUp invocation.

**Solution:** Remove the timer entirely and chain the next setup step (`createDatabaseSetupViaRestApi` or equivalent) directly off the `onSuccess` callback.

All 6 affected setUp methods follow the same template:

```java
// BEFORE — all 6 files
vertx.deployVerticle(server)
    .onSuccess(id -> {
        deploymentId = id;
        webClient = WebClient.create(vertx);  // or equivalent init

        // Give server time to fully start   ← remove this comment and the timer
        vertx.setTimer(1000, timerId -> {
            createDatabaseSetupViaRestApi(vertx, testContext);
        });
    })
    .onFailure(testContext::failNow);

// AFTER — in all 6 files
vertx.deployVerticle(server)
    .onSuccess(id -> {
        deploymentId = id;
        webClient = WebClient.create(vertx);
        createDatabaseSetupViaRestApi(vertx, testContext);  // call directly
    })
    .onFailure(testContext::failNow);
```

Files to change (same mechanical edit in each):
- `EndToEndValidationTest.java:70`
- `EventStoreIntegrationTest.java:83`
- `ConsumerGroupSubscriptionIntegrationTest.java:96`
- `SSEBasicStreamingIntegrationTest.java:113`
- `SSEBatchingIntegrationTest.java:101`
- `SSEReconnectionIntegrationTest.java:103`

---

### P4 — Wall-clock wait for SSE/WS data to accumulate

**Root cause:** These tests connect to a streaming endpoint, collect data in a `StringBuilder`,
wait a fixed interval, then inspect the buffer. They race against message delivery — too slow
on the server, and the buffer is empty when the timer fires.

**General solution:** Move assertions into the `response.handler(buffer -> ...)` callback.
Detect the target event inside the handler and call `testContext.completeNow()` there. If
multiple events are needed, use a `Checkpoint` or `AtomicInteger` counter. Retain a
`setTimer` only as a **failure** guard — it should call `testContext.failNow(...)` if the
expected events have not arrived by the deadline.

#### `ConsumerGroupSubscriptionIntegrationTest.java:245`, `:354`, `:529`, `:1136` — SSE buffer wait

```java
// BEFORE — collect into buffer, then timer fires and reads it
StringBuilder sseData = new StringBuilder();
response.handler(buffer -> sseData.append(buffer.toString()));
vertx.setTimer(2000, id -> {
    String events = sseData.toString();
    assertTrue(events.contains("event: connection"));
    assertTrue(events.contains("event: configured"));
    testContext.completeNow();
});

// AFTER — act on each incoming SSE frame, fail if deadline passes
AtomicBoolean done = new AtomicBoolean(false);
StringBuilder sseData = new StringBuilder();
response.handler(chunk -> {
    sseData.append(chunk.toString());
    String events = sseData.toString();
    if (events.contains("event: connection") && events.contains("event: configured")) {
        if (done.compareAndSet(false, true)) {
            testContext.verify(() -> {
                assertTrue(events.contains("\"startPosition\":\"FROM_NOW\""));
            });
            testContext.completeNow();
        }
    }
});
vertx.setTimer(5000, id -> {   // deadline — must failNow, not completeNow
    if (done.compareAndSet(false, true)) {
        testContext.failNow(new AssertionError(
            "SSE did not deliver expected events within 5 s. Received: " + sseData));
    }
});
```

#### `ConsumerGroupSubscriptionIntegrationTest.java:785/799` — Nested timers for subscription update

The inner pattern is: wait 1 s → update subscription → wait 1 s more → assert SSE still live.
The second `setTimer` is gating on the subscription update `Future`, not on a real event.

```java
// AFTER — chain off the update future directly
sseResponse.handler(buffer -> sseData.append(buffer.toString()));
// First: send the update when SSE is ready (detected via initial 'configured' event)
sseResponse.handler(chunk -> {
    sseData.append(chunk.toString());
    if (sseData.toString().contains("event: configured") && !updateSent.getAndSet(true)) {
        webClient.post(TEST_PORT, "localhost", setOptionsPath)
            .sendJsonObject(updatedOptions)
            .onSuccess(updateResponse -> {
                // Chain assertions directly off the update future — no timer needed
                testContext.verify(() -> {
                    assertTrue(sseData.toString().contains("\"heartbeatIntervalSeconds\":30"));
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }
});
```

#### `CrossLayerPropagationIntegrationTest.java:262` — Send REST message after SSE ready

The timer guards the SSE connection becoming "ready to receive". Replace it with a trigger
on the first SSE `connection` event:

```java
// AFTER — send message only after receiving the SSE connection event
AtomicBoolean messageSent = new AtomicBoolean(false);
response.handler(chunk -> {
    String data = chunk.toString();
    buffer.append(data);

    // Trigger message send as soon as SSE confirms it is connected
    if (!messageSent.getAndSet(true) && data.contains("event: connection")) {
        sendTestMessageViaRestApi();   // extracted method
    }

    // Detect our test message
    if (data.contains("testField") && data.contains("testValue")) {
        receivedPayload.set(data);
        messageLatch.countDown();
    }
});
```

#### `SystemMonitoringIntegrationTest.java:138` — Wait for first WS stats update

The WS server sends a metrics push shortly after connection. Replace the 1.5 s timer with a
message handler that proceeds as soon as the first message arrives:

```java
// AFTER — wait for the first WS message, then close and scrape metrics
.compose(ws -> {
    Promise<Void> firstMessageReceived = Promise.promise();
    ws.textMessageHandler(msg -> {
        if (firstMessageReceived.future().isComplete()) return;
        ws.close();
        firstMessageReceived.complete();
    });
    // Safety timeout — fail if no message arrives within 5 s
    vertx.setTimer(5000, id ->
        firstMessageReceived.tryFail(new AssertionError("No WS stats update within 5 s")));
    return firstMessageReceived.future();
})
.compose(v -> webClient.get(REST_PORT, "localhost", "/metrics").send())
```

#### `QueueManagementE2ETest.java:223` — Wait for messages to appear in queue

Test 2 publishes messages via the REST API and its future completes after the `202 Accepted`
response. The queue is populated synchronously within the request handler before responding,
so by the time the REST call completes the messages are in the DB. The timer is not needed.

```java
// AFTER — query immediately; if the queue API is eventually-consistent,
//         use retry with testContext.failNow on the deadline instead of a flat sleep.
webClient.get(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName)
    .send()
    .onSuccess(response -> {
        testContext.verify(() -> {
            assertEquals(200, response.statusCode());
            long messageCount = response.bodyAsJsonObject().getLong("messages", 0L);
            assertTrue(messageCount > 0, "Queue should have messages, found: " + messageCount);
        });
        testContext.completeNow();
    })
    .onFailure(testContext::failNow);
```

---

### P5 — Log-flush padding (`TracePropagationTest.java:110`, `TraceIdSpanIdDemoTest.java:134`)

**Root cause:** A 100 ms timer was added "in case the appender is async". The comments in
both files acknowledge the appender is synchronous. The timer is dead code that adds 100 ms
to every run of these tests.

**Solution:** Remove the `setTimer` wrapper entirely. Pull the assertion block up so it runs
inline after the operation that does the logging.

```java
// BEFORE
vertx.setTimer(100, id -> {  // Give appender a moment to flush if async (it's not, but safety)
    List<String> logs = traceAppender.getCapturedLogs();
    // ... assertions
    testContext.completeNow();
});

// AFTER — inline, no timer
List<String> logs = traceAppender.getCapturedLogs();
// ... same assertions
testContext.completeNow();
```

The same mechanical change applies to `TraceIdSpanIdDemoTest.java:134`.

