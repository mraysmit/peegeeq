# Getting Started with PeeGeeQ
© Mark Andrew Ray-Smith Cityline Ltd 2025  
Version 2.0

This guide is the entry point for a developer who adopts PeeGeeQ. It states what PeeGeeQ provides, how to
choose a delivery model, how to start the local stack, the rules for asynchronous code and transactions, and
where the runnable examples are. Each section links to the guide that owns the detail.

## 1. What PeeGeeQ provides

PeeGeeQ uses PostgreSQL as the message and event store. It is built on Vert.x 5; the root `pom.xml` sets
`vertx.version` to 5.1.10. It provides:

- a native queue (`peegeeq-native`);
- a transactional outbox (`peegeeq-outbox`);
- a bi-temporal event store (`peegeeq-bitemporal`);
- consumer groups, server-side filtering, per-key ordered consumption, retry and a dead-letter queue;
- database setup through `DatabaseSetupService`, which creates the schema from the SQL scripts under
  `peegeeq-db/src/main/resources/db/templates`; the Flyway migrations in `peegeeq-migrations` are a
  separate path, and the [Database Guide](PEEGEEQ_DATABASE_GUIDE.md) states the difference;
- a REST server (`peegeeq-rest`) and a React management UI (`peegeeq-management-ui`).

The module list and the layers are in the [Architecture Guide](PEEGEEQ_ARCHITECTURE.md).

## 2. Choose a delivery model

| Requirement | Model | What the producer does |
|---|---|---|
| Hand work to other services; the send does not have to commit with a business write | Native queue | Inserts a row into `queue_messages` and calls `pg_notify`. Consumers use LISTEN/NOTIFY, polling, or both (`ConsumerMode`: `LISTEN_NOTIFY_ONLY`, `POLLING_ONLY`, `HYBRID`) |
| The message must commit or roll back with a business write | Outbox | `OutboxProducer.sendInExistingTransaction(...)` inserts into the `outbox` table on the caller's connection |
| Record facts with a valid time and a transaction time, correct them, and query history | Bi-temporal event store | `EventStore.appendBuilder()...execute()` appends an event; `.inTransaction(connection)` joins the caller's transaction, except for a correction built with `.correction(...)`, which ignores the connection (read in code, not run; D41) |

These rules apply to every model:

- Delivery is at-least-once. A message can be delivered again after a handler failure, a handler timeout,
  or a consumer crash (read in code, not run). A handler must be idempotent: a second run for the same
  message must leave the same result.
- A queue message carries a correlation ID (`MessageProducer.send(payload, headers, correlationId)`). It
  carries no causation ID. Causation IDs belong to bi-temporal events only.
- A simple consumer does not preserve order between messages (read in code, not run). Per-key order requires the `OFFSET_WATERMARK`
  completion mode and a message group. The [Ordering Guide](PEEGEEQ_ORDERING_GUIDE.md) owns that contract.

Producer send modes, consumer groups, filtering, retry and the dead-letter queue are in the
[Messaging Guide](PEEGEEQ_MESSAGING_GUIDE.md). The event store is in the
[Event Store Guide](PEEGEEQ_EVENT_STORE_GUIDE.md).

## 3. Prerequisites

- JDK 27 registered in `~/.m2/toolchains.xml`. The root `pom.xml` sets `maven.compiler.release` to 27, and
  `maven-toolchains-plugin` compiles and runs tests on that JDK. Maven itself may run on another JDK. The
  required `toolchains.xml` entry is in section 6 of
  [PeeGeeQ Test Commands](../docs-design/testing/PEEGEEQ-TEST-COMMANDS.md).
- Maven 3.8.0 or later. The enforcer rule in the root `pom.xml` rejects older versions.
- Docker. Every database test and the management UI end-to-end tests start PostgreSQL through
  Testcontainers.
- Node v22.12.0 and npm 10.2.4 for the management UI. `peegeeq-management-ui/pom.xml` pins both, and the
  `frontend-maven-plugin` installs them into `peegeeq-management-ui/node` during a Maven build.
- Windows and PowerShell. The command forms below are PowerShell.

Contributor setup and CI are in the [Contributor Guide](PEEGEEQ_CONTRIBUTOR_GUIDE.md).

## 4. Start the local stack

The stack has three parts: PostgreSQL, the REST server, and the management UI. Start them in that order.
Run every command from the repository root unless the step says otherwise.

### 4.1 PostgreSQL

`scripts/local-infra/docker-compose-local-dev.yml` defines one `postgres:15.13-alpine3.20` container named
`peegeeq-postgres-local`. It listens on port 5432. The database, user and password are all `peegeeq_dev`.

```powershell
docker compose -f scripts/local-infra/docker-compose-local-dev.yml up -d
docker ps --filter "name=peegeeq-postgres-local"
```

The container has a `pg_isready` health check. Wait until `docker ps` shows it as healthy.

### 4.2 REST server

Rebuild the REST module and its upstream modules first. Then start the server with the `exec:java`
execution that `peegeeq-rest/pom.xml` binds to `dev.mars.peegeeq.rest.StartRestServer`. `Tee-Object` writes
into `logs\`, which is ignored by git and absent in a fresh clone; create it first.

```powershell
New-Item -ItemType Directory -Force logs

mvn clean install -DskipTests -pl ":peegeeq-rest" -am 2>&1 |
    Tee-Object -FilePath logs\rebuild-peegeeq-rest-<YYYYMMDD>.log

mvn process-resources exec:java -pl ":peegeeq-rest" 2>&1 |
    Tee-Object -FilePath logs\rest-server-<YYYYMMDD>.log
```

The statements in this list were read in code, not run.

- `process-resources` copies `conf/rest-server.json` into `target/classes`. That file sets port 8088 and the
  CORS origins. `exec:java` does not copy resources itself. Without the file, `RestServerConfig.from` fails
  with "allowedOrigins must be provided and non-empty" and the server does not start.
- `StartRestServer.main` does not read its arguments. `-Dexec.args` has no effect.
- The startup banner prints `http://localhost:8080/health` and `http://localhost:8080/api/v1`. Both
  lines are wrong (defect D31 in [tasks.md](../docs-design/tasks/tasks.md)). The server listens on 8088.
- The server opens no database at startup. It reaches a database only through a setup (section 4.4).

Check the server:

```powershell
Invoke-RestMethod http://localhost:8088/api/v1/health
```

The handler answers with `status` set to `UP` (read in code, not run).

### 4.3 Management UI

```powershell
cd peegeeq-management-ui
npm run dev
```

The npm dependencies must be installed first. A Maven build that includes `peegeeq-management-ui` runs
`npm install` in the `initialize` phase with the pinned Node; `npm install` with Node v22.12.0 does the same.

The Vite development server listens on port 3000 and forwards `/api` and `/ws` to `localhost:8088`
(`vite.config.ts`). Open `http://localhost:3000`.

### 4.4 Create a setup

A setup names a database, a schema, and the queues and event stores in it. Create one from the management
UI with the coordinates of the database in section 4.1, or through the REST API. The request body and the
create and connect behaviour are in the [REST API Guide](PEEGEEQ_REST_API_GUIDE.md) and the
[Database Guide](PEEGEEQ_DATABASE_GUIDE.md).

### 4.5 Management UI end-to-end tests

The Playwright tests need the rebuild of section 4.2 and the npm dependencies of section 4.3; the rebuild
of section 4.2 does not run `npm install` for the UI. They need no other step above. The rest of this
paragraph was read in code, not run. `npm run test:e2e`, run in `peegeeq-management-ui`, starts its own
PostgreSQL container and starts the REST server with `mvn process-resources exec:java -pl peegeeq-rest`.
It stops a server on 8088 when it holds no record of that server's database port, when the recorded port
differs from the new container's port, or when the server's CORS settings are stale, and then starts its
own. A server started by hand in section 4.2 has no such record, so it is stopped and replaced.
It also stops any process on port 3000. Section 7 of [PeeGeeQ Test Commands](../docs-design/testing/PEEGEEQ-TEST-COMMANDS.md)
describes the procedure.

## 5. The Future contract

Every asynchronous PeeGeeQ operation returns `io.vertx.core.Future`. The caller must do one of three things
with each returned Future:

1. return it to its own caller;
2. compose it into a larger operation with `.compose(...)`; or
3. observe its failure with `.onFailure(...)`, and add `.onSuccess(...)` when the success needs handling.

A Future that is neither returned, composed nor observed loses its failure. The banned patterns, such as
converting a failure into a success, blocking bridges to JDK futures, fixed sleeps and callback style, are
listed in section 4 of the [coding principles](../docs-design/dev/pgq-coding-principles.md).

Return the operation:

```java
Future<Void> publish(MessageProducer<JsonObject> producer, JsonObject order, String correlationId) {
    return producer.send(order, Map.of(), correlationId);
}
```

Compose dependent steps. A failure in any step fails the returned Future, and later steps do not run:

```java
Future<String> recordThenPublish(Pool pool, MessageProducer<JsonObject> producer, JsonObject order) {
    return pool.withTransaction(connection -> connection
            .preparedQuery("INSERT INTO orders (id, amount) VALUES ($1, $2)")
            .execute(Tuple.of(order.getString("id"), order.getDouble("amount"))))
        .compose(rows -> producer.send(order))
        .map(ignored -> order.getString("id"));
}
```

In this sample the insert commits before the send starts. A failed send leaves the order row without a
message. Section 6 shows the atomic form.

Observe a terminal operation, for example at an HTTP boundary:

```java
void createOrder(RoutingContext context) {
    JsonObject order = context.body().asJsonObject();
    recordThenPublish(pool, producer, order)
        .onSuccess(orderId -> context.response().setStatusCode(201)
            .end(new JsonObject().put("orderId", orderId).encode()))
        .onFailure(context::fail);
}
```

A delay uses `vertx.timer(ms).compose(...)`. Retry and failover of database access use `CircuitBreakerManager`
in `peegeeq-db`, not an inline retry loop.

### Consumer handlers

A `MessageHandler<T>` returns `Future<Void>`. The handler must return the whole processing chain. The consumer
treats the message as processed only when that Future succeeds. A failed Future, a `null` return, or a
handler that does not settle within the visibility timeout counts as a failed attempt.
`peegeeq.queue.max-retries` counts retries after the first delivery: with a value of N, the message moves to
the dead-letter queue after N + 1 failed attempts (read in code for the native consumer, not run).

`MessageConsumer.subscribe(handler)` returns a Future that completes when the subscription is established
(for LISTEN/NOTIFY modes, when PostgreSQL acknowledges `LISTEN`; read in code, not run). Send only after it
completes:

```java
consumer.subscribe(message -> handle(message.getPayload()))
    .compose(ready -> producer.send(order))
    .onFailure(error -> logger.error("Subscribe or send failed", error));
```

## 6. Caller-owned transactions

When a business write and an outgoing message must commit together, the caller owns the transaction and
passes its `SqlConnection` to `OutboxProducer.sendInExistingTransaction(...)`. A failure in either operation
fails the chain, and `withTransaction` rolls back both.

```java
Future<String> recordWithOutbox(Pool pool, OutboxProducer<JsonObject> producer, JsonObject order) {
    return pool.withTransaction(connection -> connection
        .preparedQuery("INSERT INTO orders (id, amount) VALUES ($1, $2)")
        .execute(Tuple.of(order.getString("id"), order.getDouble("amount")))
        .compose(rows -> producer.sendInExistingTransaction(order, connection))
        .map(ignored -> order.getString("id")));
}
```

`VertxTransactionParticipationExample` in
`peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/outbox/` is a compiled example of the same
pattern. `OutboxProducerTransactionTest` in
`peegeeq-outbox` tests `sendInExistingTransaction` against PostgreSQL.

These rules hold:

- `sendInExistingTransaction` does not begin a transaction. If the connection has no active transaction,
  the insert commits on its own (read in code, not run).
- `sendInOwnTransaction(...)` runs its insert through `Pool.withTransaction`. Its Javadoc states that it
  does not participate in a caller-initiated transaction. Use `sendInExistingTransaction` when the message
  must commit with a business write.
- The native producer has no caller-transaction send. Use the outbox when the message must commit with a
  business write.
- All writes run inside `withTransaction`, never `withConnection`.

The event store joins a caller's transaction through `appendBuilder()...inTransaction(connection).execute()`.
A correction built with `.correction(...)` ignores the connection and does not join the transaction (read
in code, not run; D41). The [Event Store Guide](PEEGEEQ_EVENT_STORE_GUIDE.md) owns its rules.

## 7. First producer and consumer

The runnable material is the test classes in `peegeeq-examples`. Each class starts PostgreSQL through
Testcontainers, so Docker must be running. Every class below carries `@Tag(TestCategories.INTEGRATION)`, so
it runs only under `-Pintegration-tests` or `-Pall-tests`; without one of them, Maven reports
`Tests run: 0` (read in code, not run).

Start with `SimpleNativeQueueTest`
(`peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/nativequeue/`). It starts a `PeeGeeQManager`,
creates a native `QueueFactory` through `PgQueueFactoryProvider`, subscribes a consumer, sends after the
subscription completes, and completes the test from the consumer handler. A message that never arrives fails
the test on its timeout (read in code, not run). Create `logs\` first if it does not exist.

```powershell
New-Item -ItemType Directory -Force logs

mvn clean install -DskipTests -pl ":peegeeq-examples" -am 2>&1 |
    Tee-Object -FilePath logs\rebuild-peegeeq-examples-<YYYYMMDD>.log

mvn test "-Pintegration-tests" -pl ":peegeeq-examples" "-Dtest=SimpleNativeQueueTest" 2>&1 |
    Tee-Object -FilePath logs\simple-native-queue-<YYYYMMDD>.log
```

Read the `Tests run:` line for the class in the saved log. `BUILD SUCCESS` alone does not show that the test
ran.

Further examples, run with the same command and a different `-Dtest=` value:

| Subject | Class | Package under `peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/` |
|---|---|---|
| Native consumer groups sharing work | `ConsumerGroupLoadBalancingDemoTest` | `nativequeue` |
| Server-side filtering, native queue | `ServerSideFilteringTest` | `nativequeue` |
| Server-side filtering, outbox | `OutboxServerSideFilteringTest` | `outbox` |
| Per-key ordered consumption | `PartitionedOrderingDemoTest` | `outbox` |
| CloudEvents in the bi-temporal store, JSONB queries | `CloudEventsJsonbQueryTest` | `bitemporal` |
| Database setup service | `DatabaseSetupServiceIntegrationTest` | `integration` |

For `OutboxProducerTransactionTest`, use `-pl ":peegeeq-outbox"`.

The `exec` executions in `peegeeq-examples/pom.xml` are not a supported entry point: most of them name main
classes that do not exist (defect D30 in [tasks.md](../docs-design/tasks/tasks.md)).

`peegeeq-examples-spring` is a separate Maven project, not a module of the root reactor. It holds Spring Boot
applications such as `SpringBootOutboxApplication` and `SpringBootConsumerApplication`, and consumes PeeGeeQ
as artifacts installed into the local Maven repository by the root build.

## 8. Where to go next

| Subject | Guide |
|---|---|
| Modules, layers, delivery semantics | [Architecture](PEEGEEQ_ARCHITECTURE.md) |
| Producers, consumers, consumer groups, filtering, retry, dead letters | [Messaging](PEEGEEQ_MESSAGING_GUIDE.md) |
| Ordered consumption | [Ordering](PEEGEEQ_ORDERING_GUIDE.md) |
| Bi-temporal events, corrections, subscriptions, pagination | [Event Store](PEEGEEQ_EVENT_STORE_GUIDE.md) |
| Runtime properties and profiles | [Configuration](PEEGEEQ_CONFIGURATION_GUIDE.md) |
| Setups, migrations, templates, schemas | [Database](PEEGEEQ_DATABASE_GUIDE.md) |
| HTTP, SSE and WebSocket interfaces | [REST API](PEEGEEQ_REST_API_GUIDE.md) |
| Lifecycle, recovery, metrics, tracing | [Operations](PEEGEEQ_OPERATIONS_GUIDE.md) |
| Build, tests and CI for contributors | [Contributor Guide](PEEGEEQ_CONTRIBUTOR_GUIDE.md) |
