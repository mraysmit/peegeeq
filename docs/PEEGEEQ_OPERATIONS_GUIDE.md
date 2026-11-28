# PeeGeeQ Operations Guide
© Mark Andrew Ray-Smith Cityline Ltd 2025  
Version 2.0

This guide is for the people who run PeeGeeQ. It covers the manager lifecycle and shutdown, the
background jobs that recover work, health checks, the PostgreSQL connectivity and failover
boundary, metrics, and tracing and logging. "(read in code, not run)" marks runtime behaviour that
was read in the source and not observed.

Other documents own related facts:

- Every runtime property, its default and its validation: `PEEGEEQ_CONFIGURATION_GUIDE.md`.
  This guide names a setting by its subject and does not repeat keys.
- Health, metrics and streaming endpoints of the REST server: `PEEGEEQ_REST_API_GUIDE.md`.
- Schemas, migrations and setups: `PEEGEEQ_DATABASE_GUIDE.md`.
- Consumer groups, retry and dead letters from the application side: `PEEGEEQ_MESSAGING_GUIDE.md`.
- Federation and discovery across PeeGeeQ instances: `peegeeq-service-manager/README.md`.

## Contents

1. Manager lifecycle and shutdown
2. Background jobs and recovery
3. Health checks
4. PostgreSQL connectivity and failover
5. Metrics
6. Tracing and logging
7. Performance evidence

---

## 1. Manager lifecycle and shutdown

`PeeGeeQManager` (`peegeeq-db`) owns the connection pool, the health checks, the metrics samplers
and the background jobs of one setup.

### Construction

| Constructor | Vert.x | Meter registry |
|---|---|---|
| `PeeGeeQManager(configuration)` | Creates its own and closes it on close. | Creates a `SimpleMeterRegistry`. It is never closed (D43). |
| `PeeGeeQManager(configuration, meterRegistry)` | Creates its own and closes it on close. | Caller's; not closed. |
| `PeeGeeQManager(configuration, meterRegistry, vertx)` | Caller's; not closed. | Caller's; not closed. |

In a Vert.x application pass the application's `Vertx`. The constructor creates the default pool,
a shared worker executor named `peegeeq-worker-pool` with 20 threads, the circuit breakers, the
health check manager, the dead letter queue manager and the stuck-message recovery manager. It
binds the PeeGeeQ meters to the registry when metrics are enabled. A construction failure throws
`RuntimeException`.

### Start

`start()` returns `Future<Void>`. In order, it:

1. runs `SELECT 1` on the pool;
2. checks that `outbox`, `queue_messages`, `dead_letter_queue` and `outbox_topic_subscriptions`
   exist in the configured schema, and fails with a message that lists the missing tables;
3. starts the health checks, the metrics samplers and the background jobs (section 2).

When any step fails, `start()` stops the background jobs and health checks it started and fails
with `RuntimeException("Failed to start PeeGeeQ Manager", cause)`. A second `start()` on a started
manager succeeds without doing anything. `start()` on a closing manager fails (read in code, not
run).

The manager publishes lifecycle events on the Vert.x event bus address `peegeeq.lifecycle`:
`database.validating`, `database.ready`, `components.started`, `manager.ready`, and
`manager.failed` when start fails.

```java
PeeGeeQManager manager = new PeeGeeQManager(configuration, meterRegistry, vertx);

manager.start()
    .onSuccess(v -> logger.info("PeeGeeQ manager started"))
    .onFailure(error -> logger.error("PeeGeeQ manager failed to start", error));
```

### Stop and close

| Method | Effect |
|---|---|
| `stop()` | Cancels the timers, stops the dead-consumer and consumer-group retry jobs, and stops the health checks. The pool stays open. |
| `closeReactive()` | The full shutdown. The returned Future fails with the first step that failed; later failures are added to it as suppressed exceptions. A second call returns the same Future. |
| `close()` | Deprecated. Calls `closeReactive()` and returns before it finishes. The manager is not closed when `close()` returns. Do not use the manager in try-with-resources. |

`closeReactive()` runs these steps in order, and every step runs even when an earlier one failed
(read in code, not run):

1. Marks the metrics, dead letter and recovery components as closing, so running timer callbacks
   stop before they query the database.
2. Waits for an in-flight `start()`.
3. Calls `stop()`.
4. Runs the registered close hooks. The native queue factory, the outbox factory and the durable
   bi-temporal subscription service register hooks (the database service only forwards them to the
   manager), so their consumers and producers close before the pool.
5. Closes the worker executor.
6. Closes the client factory and its pools.
7. Closes the meter registry only if the manager created it and it implements `AutoCloseable`.
   Micrometer's `MeterRegistry` does not, so this step never runs (D43).

After the returned Future settles, the manager closes its Vert.x if it created it (read in code, not
run).

Event stores are not closed by the manager. Each event store has its own pool and LISTEN
connection (`PEEGEEQ_EVENT_STORE_GUIDE.md`). Close them first. `CloseSteps`
(`peegeeq-api`, package `dev.mars.peegeeq.api.lifecycle`) runs every step and reports the first
failure:

```java
CloseSteps.after(Future.succeededFuture(), logger)
    .then("order event store", orderEventStore::close)
    .then("peegeeq manager", manager::closeReactive)
    .result()
    .onSuccess(v -> logger.info("PeeGeeQ shut down"))
    .onFailure(error -> logger.error("PeeGeeQ shutdown failed", error));
```

Do not use `.eventually(...)` for these steps: it discards the failure of the step it runs.

## 2. Background jobs and recovery

### Jobs the manager runs

| Job | Interval | What it does | Runs when |
|---|---|---|---|
| Queue-depth cache | Configured depth-cache interval | Counts outbox `PENDING` and `PROCESSING` rows, native `AVAILABLE` rows and dead letter rows for the depth gauges. | Metrics enabled |
| Event-loop lag sampler | 500 ms | Measures how late a periodic timer runs. | Metrics enabled |
| Pool acquisition canary | 5 s | Acquires and releases one connection and records the wait. | Metrics enabled |
| Dead letter purge | 24 hours; the first run is 24 hours after start | Deletes `dead_letter_queue` rows whose `failed_at` is older than 30 days. The interval and the retention are fixed in code (D43). | Always |
| Stuck-message recovery | Configured recovery check interval | See below. | Recovery enabled; otherwise each run does nothing |
| Dead consumer detection | Configured interval | Adds one to `consecutive_misses` of each consumer-group subscription whose heartbeat timeout has passed, and marks it `DEAD` when `consecutive_misses` reaches its `dead_after_misses`. It then runs `DeadConsumerGroupCleanup` for the dead groups. | Detection enabled |
| Consumer-group retry | Configured interval | Resets `FAILED` consumer-group rows to `PENDING`, or moves them to the dead letter queue when the retry limit is reached. | Retry enabled |

The jobs do not stop on failure. They log failures differently (read in code, not run):

| Job | Failure logging |
|---|---|
| Queue-depth cache, dead consumer detection, consumer-group retry | `BackgroundTaskFailureTracker`: WARN on the first consecutive failure, nothing on the second, ERROR on the third and on every third failure after that. A success logs a recovery line at INFO and resets the count. |
| Dead letter purge | ERROR on every failure from `DeadLetterQueueManager`, plus a manager line at WARN, or at ERROR from the third consecutive failure. |
| Stuck-message recovery | WARN, then ERROR from the third consecutive failure. A success resets the count. |
| Pool acquisition canary | WARN on every failure. |

### Stuck-message recovery

An outbox consumer claims a message by setting `status = 'PROCESSING'` and `processed_at` to the
claim time. If the consumer process stops before it records the result, the row stays in
`PROCESSING`. `StuckMessageRecoveryManager` resets such rows. On each run it first counts the
matching rows on a plain connection. Only when the count is above 0 does it run, in one transaction:

```sql
UPDATE outbox
SET status = 'PENDING', processed_at = NULL
WHERE status = 'PROCESSING' AND processed_at < $1   -- now minus the processing timeout
RETURNING id, topic, retry_count, error_message
```

It logs one INFO line per recovered row. What it does not change:

The cutoff is computed from the JVM clock, not the database clock, so clock skew between the
application host and the database moves it.

- `retry_count` and `error_message` stay as they were.
- It reads only the `outbox` table. Native `queue_messages`, the per-group rows of
  `outbox_consumer_groups` and event store tables are not touched.

A handler that finished its work but did not record completion before the process stopped runs
again after recovery (read in code, not run). Handlers must be idempotent.

### Expired locks in the native queue

The native queue does not use the recovery manager. A native consumer claims a row by setting
`status = 'LOCKED'` and `lock_until` to now plus the visibility timeout. Every native consumer runs
its own timer every 10 seconds. It moves rows of its topic whose `lock_until` has passed back to
`AVAILABLE` and adds one to `retry_count`; a row that would exceed the retry limit goes to the dead
letter queue (read in code, not run). The release runs only while at least one consumer of that
topic is running.

## 3. Health checks

When health checks are enabled, `HealthCheckManager` runs its checks on the configured health
interval with the configured timeout.

| Component | Check | Unhealthy when |
|---|---|---|
| `database` | `SELECT 1` | The query fails |
| `outbox-queue` | Count of outbox `PENDING` rows older than 5 minutes | More than 10000, or the query fails |
| `native-queue` | Count of native `AVAILABLE` rows | The query fails |
| `dead-letter-queue` | Count of dead letter rows from the last hour | More than 100, or the query fails |
| `memory` | JVM heap use against the maximum | Above 90 % (DEGRADED above 80 %) |
| `disk-space` | Use of the root file system | Above 95 % (DEGRADED above 85 %) |
| `background-depth-cache` | Consecutive failures of the queue-depth cache refresh | DEGRADED after one failure, UNHEALTHY from the third |
| `background-dead-consumer-detection`, `background-consumer-group-retry` | Consecutive failures of those jobs | Same rule |

The database and the three queue checks all run through the circuit breaker named `database`. The
three queue checks run only when queue checks are enabled. `background-depth-cache` is registered
when metrics are enabled; the two job components are registered when their jobs are enabled. The
overall state is `UP` only when there is at least one result and every component is `HEALTHY`. A
`DEGRADED` component, including one failed depth-cache refresh, makes it `DOWN`; so does an empty
result set before the first check has run. `PeeGeeQManager.isHealthy()` returns the same result as a
boolean (read in code, not run).

The REST server's `/health` and `/api/v1/health` return a fixed `UP` body; they do not read the
database. `PEEGEEQ_REST_API_GUIDE.md` describes these and the per-setup health
endpoints.

## 4. PostgreSQL connectivity and failover

The Vert.x PostgreSQL pool manages connections. It does not elect or discover a new database
primary. A deployment component owns routing and primary detection: HAProxy, with the role check
served by `peegeeq-pg-sidecar`. An optional PgBouncer sits between PeeGeeQ and HAProxy.

The sidecar serves `GET /primary`. It runs `SELECT pg_is_in_recovery()` and returns 200 on the
write primary, 503 on a replica, and 503 when the query fails. Every other path returns 404. Its
`logback.xml` prints no trace fields.

PeeGeeQ does not use the Patroni product. `peegeeq-pg-failover` holds writer-ownership components,
but no module depends on it, so PeeGeeQ performs no automatic failover. The work is Task 8 in the
task register (`docs-design/tasks/tasks.md`).

The service manager is not part of database failover. It discovers and federates PeeGeeQ REST
instances through Consul (`peegeeq-service-manager/README.md`).

Design records:

- PostgreSQL connection management and HAProxy:
  `docs-design/failover and resilience/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md`.
- `peegeeq-pg-sidecar` design and current state: `docs-design/failover and resilience/PEEGEEQ_PG_SIDECAR.md`.

## 5. Metrics

PeeGeeQ records Micrometer meters into the registry its manager was given. An embedding
application passes its own registry to the constructor and exports it. When metrics are disabled
by configuration, the manager binds none of the meters below and starts no samplers.

### Core meters (`PeeGeeQMetrics`)

Every core meter has the tag `instance` (the configured metrics instance ID).

| Meter | Type | Extra tags | Recorded by |
|---|---|---|---|
| `peegeeq.messages.sent` | Counter | | Native and outbox producers, event store appends |
| `peegeeq.messages.sent.by.topic` | Counter | `topic` | Same |
| `peegeeq.message.send.time` | Timer | `topic` | Same, when a duration is known |
| `peegeeq.messages.duplicates`, `peegeeq.messages.duplicates.by.topic` | Counter | `topic` on the second | Outbox producer, idempotency-key conflicts |
| `peegeeq.messages.received`, `peegeeq.messages.received.by.topic` | Counter | `topic` on the second | Native and outbox consumers, only after the handler succeeded; a failed delivery is not counted |
| `peegeeq.messages.processed`, `peegeeq.messages.processed.by.topic` | Counter | `topic` on the second | Native and outbox consumers |
| `peegeeq.message.processing.time` | Timer | | Same |
| `peegeeq.message.processing.time.by.topic` | Timer, p50/p95/p99 | `topic` | Same |
| `peegeeq.message.delivery.latency.by.topic` | Timer, p50/p95/p99 | `topic`, `implementation` | Native and outbox consumers; enqueue to claim, on the database clock |
| `peegeeq.messages.failed`, `peegeeq.messages.failed.by.topic` | Counter | `topic`, `error_type` on the second | Outbox consumer. The native consumer does not record it (D43). |
| `peegeeq.messages.dead_lettered`, `peegeeq.messages.dead_lettered.by.topic` | Counter | `topic`, `reason` on the second | Native consumer |
| `peegeeq.connection.acquisition.time` | Timer | | Pool acquisition canary |
| `peegeeq.queue.depth.outbox` | Gauge | | Depth cache: outbox `PENDING` and `PROCESSING` |
| `peegeeq.queue.depth.native` | Gauge | | Depth cache: native `AVAILABLE` |
| `peegeeq.queue.depth.dead_letter` | Gauge | | Depth cache: all dead letter rows |

The "recorded by" column was read in code; the counts were not observed.

### Other meters

| Meter | Tags | Source |
|---|---|---|
| `peegeeq.db.client.created`, `peegeeq.db.client.create.failed`, `peegeeq.db.client.removed` | `client` | `PgClientFactory` |
| `peegeeq.db.pool.created`, `peegeeq.db.pool.create.failed`, `peegeeq.db.pool.closed`, `peegeeq.db.pool.close.failed` | `service` | `PgConnectionManager` |
| `peegeeq.notice.peegeeq_info`, `peegeeq.notices.info.total` | `code` on the second | PostgreSQL notices with PeeGeeQ info codes |
| `peegeeq.notice.postgres_warning`, `peegeeq.notices.warnings.total` | `sql_state` on the second | PostgreSQL warnings |
| `peegeeq.notice.other`, `peegeeq.notices.other.total` | `severity` on the second | Other notices |
| `peegeeq.notices.handler.duration` | | Notice handling time |
| Resilience4j circuit-breaker meters | Defined by the library | `CircuitBreakerManager` binds `TaggedCircuitBreakerMetrics` |

The REST server has its own Prometheus registry, scraped at `/metrics`. It records
`peegeeq.http.requests.duration` and `peegeeq.http.requests.total` (tags `method`, `path`,
`status`), `peegeeq.server.status`, `peegeeq.monitoring.connections` (tag `type`: `ws` or `sse`),
`peegeeq.monitoring.connections.total`, `peegeeq.monitoring.collection.duration` and
`peegeeq.monitoring.errors` (tag `operation`).

The setups that the REST server creates or connects build their managers with a new
`SimpleMeterRegistry` each. Their core meters are therefore not in the `/metrics` scrape (read in
code, not run; D43).

## 6. Tracing and logging

### Model

PeeGeeQ uses the W3C `traceparent` format, `00-<32 hex trace ID>-<16 hex span ID>-<2 hex flags>`, and
the SLF4J MDC. It does not export spans. There is no OpenTelemetry exporter, no sampling decision
and no tracing on/off switch. Trace IDs appear in log lines and in message headers only.

Trace context travels with a queue message as the `traceparent` key of the message's `headers`
JSONB column (`queue_messages` for the native queue, `outbox` for the outbox). There is no separate
table for trace data. Inspect one message with:

```sql
SELECT headers->>'traceparent' FROM queue_messages WHERE id = $1;
```

### Sending a message with trace context

The producers do not add a `traceparent`. The application puts it in the headers map. The
`MessageProducer` overloads are `send(payload)`, `send(payload, headers)`,
`send(payload, headers, correlationId)` and `send(payload, headers, correlationId, messageGroup)`.

```java
Future<Void> sendTraced(MessageProducer<String> producer, String payload, String incomingTraceparent) {
    TraceCtx span = TraceCtx.parseOrCreate(incomingTraceparent).childSpan("send-order");
    Map<String, String> headers = Map.of("traceparent", span.traceparent());
    return producer.send(payload, headers, "order-123")
        .onFailure(error -> logger.error("Traced send failed", error));
}
```

The REST send handler copies the `headers` object of the request body into the message headers,
then copies the HTTP request's `traceparent`, `tracestate` and `baggage` headers over them. When
neither source has a `traceparent`, the message has none.

### What PeeGeeQ sets in the MDC

| Component | MDC behaviour (read in code, not run) |
|---|---|
| REST router | Parses the request `traceparent`, or creates a new trace. Stores it in the Vert.x context and sets `traceId` and `spanId`. When the response body ends it clears the MDC keys; the Vert.x context entry stays. The single and batch send handlers also set `setupId` and `queueName`. |
| Native consumer (`PgNativeQueueConsumer`) | Sets `traceId` and `spanId` from the message's `traceparent`, which are the producer's IDs, or creates a new trace when there is none. Sets `messageId`, `topic` and `correlationId`. Clears them after the handler's Future settles. |
| Outbox consumer (`OutboxConsumer`) | Parses `traceparent` or creates a new trace. When the consumer belongs to a consumer group it creates a child span. Sets `messageId`, `topic`, `consumerGroup` and `correlationId`. Clears them after processing. |
| Consumer groups (`PgNativeConsumerGroup`, `OutboxConsumerGroup`) | Create a child span per group delivery and set `consumerGroup`, `topic` and `messageId`. |
| Event store live subscription | Sets `traceId` and `spanId` from the event's `traceparent` header, or a new trace, and sets `correlationId` and `messageId` (the event ID). |

The MDC is thread-local. A value set by PeeGeeQ is visible to log calls on the same thread during
the handler call. A continuation that runs later on another thread does not see it unless the code
sets it again.

MDC keys, defined as constants in `TraceContextUtil`:

| Key | Constant |
|---|---|
| `traceId` | `MDC_TRACE_ID` |
| `spanId` | `MDC_SPAN_ID` |
| `correlationId` | `MDC_CORRELATION_ID` |
| `topic` | `MDC_TOPIC` |
| `messageId` | `MDC_MESSAGE_ID` |
| `setupId` | `MDC_SETUP_ID` |
| `queueName` | `MDC_QUEUE_NAME` |
| `consumerGroup` | `MDC_CONSUMER_GROUP` |

### Tracing classes (`peegeeq-api`, package `dev.mars.peegeeq.api.tracing`)

| Class and method | Behaviour |
|---|---|
| `TraceCtx` | Record with `traceId`, `spanId`, `parentSpanId` and `traceparent`. |
| `TraceCtx.createNew()` | New root. The IDs come from `ThreadLocalRandom`, which is not a cryptographic generator. Flags `01`. |
| `TraceCtx.parseOrCreate(header)` | Accepts a header with version `00`, at least four parts, a 32-character trace ID and a 16-character span ID. It does not check that the characters are hexadecimal. Any other value gives a new root. |
| `traceCtx.childSpan(name)` | Same trace ID, new span ID, `parentSpanId` set to the current span. Flags `01`. The name is not stored. |
| `TraceContextUtil.mdcScope(trace)` | Puts `traceId` and `spanId` in the MDC and returns an `MDCScope`; `close()` restores the previous values. A null trace gives a scope that does nothing. |
| `TraceContextUtil.captureTraceContext()` | Builds a `TraceCtx` from the MDC, or returns null. |
| `TraceContextUtil.setMDCFromMessageHeaders(headers)`, `clearTraceMDC()` | What the consumers above use. |
| `TraceContextUtil.CONTEXT_TRACE_KEY` | `trace.ctx`, the Vert.x context key that holds the current `TraceCtx`. |
| `AsyncTraceUtils.executeBlockingTraced(vertx, worker, ordered, callable)` | Runs a `Callable` on a `WorkerExecutor` with a child span in the worker's MDC. |
| `AsyncTraceUtils.traceAsyncAction(vertx, name, supplier)` | Runs a Future-returning action under a child span. `PeeGeeQManager.start()` uses it. |
| `AsyncTraceUtils.publishWithTrace`, `requestWithTrace`, `tracedConsumer`, `tracedAsyncConsumer` | Vert.x event bus helpers. `tracedConsumer` and `tracedAsyncConsumer` register event bus consumers; they do not consume PeeGeeQ queue messages. Both store the trace in the Vert.x context and do not remove it afterwards (D43). |

Use `mdcScope` with try-with-resources around synchronous logging only:

```java
try (var scope = TraceContextUtil.mdcScope(trace)) {
    logger.info("Order accepted");
}
```

### Logback configuration

PeeGeeQ ships no `logback.xml` in its library modules. The application owns its logging
configuration. Register the two converters from `peegeeq-api`:

| Conversion word | Class | Output |
|---|---|---|
| `%vxTrace` | `dev.mars.peegeeq.api.logging.VertxTraceIdConverter` | The MDC `traceId`; otherwise, on a thread whose name starts with `vert.x-`, the trace ID of the `TraceCtx` in the current Vert.x context, or a plain `traceId` value in that context; otherwise `-`. `error` when the Vert.x context could not be read. |
| `%vxSpan` | `dev.mars.peegeeq.api.logging.VertxSpanIdConverter` | The same for the span ID and the plain `spanId` context value. |

The module test configurations use this form:

```xml
<configuration>
    <conversionRule conversionWord="vxTrace"
                    class="dev.mars.peegeeq.api.logging.VertxTraceIdConverter"/>
    <conversionRule conversionWord="vxSpan"
                    class="dev.mars.peegeeq.api.logging.VertxSpanIdConverter"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [trace=%vxTrace span=%vxSpan] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

The correlation ID is not in that pattern. To search by it, add an MDC field, for example
`corr=%X{correlationId:-}` inside the brackets; `:-` gives an empty value when the key is absent
(logback syntax, not run).

### Searching logs

A search must match the pattern that wrote the log. With the pattern above, every line contains
`[trace=<id> span=<id>]`, and a line for which no trace context is found contains
`[trace=- span=-]` (read in code, not run).

```powershell
# All lines of one trace
Select-String -Path logs\application.log -Pattern 'trace=4bf92f3577b34da6a3ce929d0e0e4736'

# Lines that had no trace context
Select-String -Path logs\application.log -Pattern 'trace=- span=-'

# By correlation ID, only when the pattern contains corr=%X{correlationId:-}
Select-String -Path logs\application.log -Pattern 'corr=order-123\]'
```

On Linux, `grep 'trace=4bf92f3577b34da6a3ce929d0e0e4736' application.log` is the same search. A log
shipper that parses the pattern above can use the regular expression
`\[trace=(?<traceId>\S+) span=(?<spanId>\S+)\]`.

A line written outside a request does not reliably show `-`. It can carry a stale trace ID
(read in code, not run; D43):

- The `PeeGeeQManager` constructor puts a startup trace in the MDC of the thread that constructs it
  and does not remove it.
- `publishLifecycleEvent` and `AsyncTraceUtils.traceAsyncAction` put a trace in the Vert.x context
  when none is there and leave it, so later lines on that context report it through the converters.
- `tracedConsumer`, `tracedAsyncConsumer` and the REST router leave their trace in the Vert.x
  context (see above).

Search by trace ID only for lines inside a request or a message handler. When a consumer's log line
shows a different trace ID from the producer's, the message was sent without a `traceparent`, or the
consumer line carries a stale trace.

### Demonstration test

`TraceIdSpanIdDemoTest` in `peegeeq-integration-tests` shows one trace ID across a worker thread,
the event bus and nested spans. It is tagged `@Tag("smoke")`, so it runs only under the
`smoke-tests` profile:

```powershell
mvn test "-Psmoke-tests" -pl :peegeeq-integration-tests "-Dtest=TraceIdSpanIdDemoTest" 2>&1 | Tee-Object -FilePath logs\trace-demo.log
```

Without `-Psmoke-tests` the default tag filter excludes the test, and Maven selects zero tests (read
in the root `pom.xml`, not run). The command forms are defined in
`docs-design/testing/PEEGEEQ-TEST-COMMANDS.md`.

## 7. Performance evidence

Benchmark scenarios live in the `peegeeq-benchmarking` module. A performance figure is valid only
for the workload, hardware, database, pool size, concurrency and revision it was recorded with.
Dated results are release evidence, recorded against their task in the
task register (`docs-design/tasks/tasks.md`) with the saved logs they came from. This guide states
none.
