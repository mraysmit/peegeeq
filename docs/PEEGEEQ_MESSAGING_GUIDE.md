# PeeGeeQ Messaging Guide
© Mark Andrew Ray-Smith Cityline Ltd 2025  
Version 1.0

This guide describes how to send and consume queue messages with the Java API: producer send modes and
transaction ownership, consumers, consumer groups and subscriptions, filtering, retry and dead letter, and
idempotent handlers.

Related documents:

- [Getting Started](PEEGEEQ_GETTING_STARTED.md) owns the `Future` contract, local startup, and the first
  producer and consumer.
- [Architecture](PEEGEEQ_ARCHITECTURE.md) explains the native, outbox and bi-temporal models and the
  at-least-once guarantee.
- [Ordering Guide](PEEGEEQ_ORDERING_GUIDE.md) owns per-key ordering and `OFFSET_WATERMARK` topics.
- [Configuration Guide](PEEGEEQ_CONFIGURATION_GUIDE.md) owns every runtime property named indirectly here
  (retry limit, visibility timeout, polling interval, background jobs).
- [REST API Guide](PEEGEEQ_REST_API_GUIDE.md) owns the HTTP form of these operations.

Statements marked "(read in code, not run)" come from reading the source. They have not been confirmed by a
test run for this guide.

## 1. Delivery contract

Delivery is at-least-once. A handler can receive the same message more than once, and the queue does not
make the handler's side effects exactly-once. The [Architecture](PEEGEEQ_ARCHITECTURE.md) guide lists the
paths that cause a redelivery. Every handler must be idempotent (section 7).

Each message has three identifying values:

| Value | Where it comes from | How a handler reads it |
|---|---|---|
| Message ID | The `id` of the database row | `Message.getId()` |
| Correlation ID | The `correlationId` argument of `send`, or a random UUID when the argument is null | `message.getHeaders().get("correlationId")` from the native and outbox consumers. On an `OFFSET_WATERMARK` topic the engine does not add this header; read `SimpleMessage.getCorrelationId()` |
| Idempotency key | The `idempotencyKey` header supplied by the producer | `message.getHeaders()` |

`Message<T>` has four methods: `getId()`, `getPayload()`, `getCreatedAt()` and `getHeaders()`. There is no
priority or correlation accessor on the interface. `SimpleMessage<T>` adds `getTopic()`,
`getCorrelationId()` and `getMessageGroup()`.

Queue messages carry a correlation ID only. A causation ID belongs to the bi-temporal event store; see the
[Event Store Guide](PEEGEEQ_EVENT_STORE_GUIDE.md).

## 2. Producers

### 2.1 The producer interface

`MessageProducer<T>` (in `peegeeq-api`) has four `send` overloads. Each returns `Future<Void>`, which
completes when the row is committed.

| Overload | Use |
|---|---|
| `send(payload)` | Payload only |
| `send(payload, headers)` | Adds string headers |
| `send(payload, headers, correlationId)` | Adds a correlation ID |
| `send(payload, headers, correlationId, messageGroup)` | Adds a message group, the partition key of the [Ordering Guide](PEEGEEQ_ORDERING_GUIDE.md) |

A factory creates a producer with `createProducer(topic, payloadType)`. The factory type decides the table:
`native` writes `queue_messages`, `outbox` writes `outbox`.

The native producer reads three headers in the four-argument overload only (read in code, not run):

- `priority`: an integer, clamped to 1 to 10. Higher values are claimed first. The default is 5.
- `delaySeconds`: the message becomes visible after this many seconds.
- `idempotencyKey`: see section 2.4.

The shorter native overloads store these headers as plain headers and do not act on them (register D44).

### 2.2 Outbox send modes and transaction ownership

`OutboxProducer<T>` adds two families of methods to `send`. The difference between the three is who owns the
transaction.

| Method | Transaction owner | Atomic with the caller's business writes |
|---|---|---|
| `send(...)` | None. The insert runs on a pooled connection and commits by itself | No |
| `sendInOwnTransaction(...)` | The producer. Each call opens and commits its own transaction | No |
| `sendInExistingTransaction(..., connection)` | The caller | Yes |

`sendInOwnTransaction` does not join a transaction that the caller has opened. Its Javadoc states that it
"does NOT participate in any caller-initiated transaction". Two `sendInOwnTransaction` calls chained with
`.compose(...)` commit two separate transactions. A business write chained between them is in neither.

The overloads of `sendInOwnTransaction` that take a `TransactionPropagation` argument fail with
`IllegalStateException` when they are called off a Vert.x context. There is no fallback. Call them from a
Vert.x context, or use an overload without the argument.

### 2.3 Writing a business change and a message atomically

Open the transaction in the caller. Run the business statements on its connection. Pass the same connection to
`sendInExistingTransaction`. The outbox row commits or rolls back with the business rows.

```java
Future<String> createOrder(Pool pool, OutboxProducer<JsonObject> producer, JsonObject order) {
    return pool.withTransaction(connection ->
        connection.preparedQuery("INSERT INTO orders (id, amount) VALUES ($1, $2)")
            .execute(Tuple.of(order.getString("id"), order.getDouble("amount")))
            .compose(rows -> producer.sendInExistingTransaction(
                new JsonObject().put("orderId", order.getString("id")), connection))
            .map(sent -> order.getString("id")));
}
```

Compiled examples of this pattern:

- [`VertxTransactionParticipationExample`](../peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/outbox/VertxTransactionParticipationExample.java)
  uses `Pool.withTransaction`.
- [`springboot/service/OrderService`](../peegeeq-examples-spring/src/main/java/dev/mars/peegeeq/examples/springboot/service/OrderService.java)
  uses `DatabaseService.getConnectionProvider().withTransaction(clientId, ...)` and sends three events in one
  transaction.

Rules for this mode:

- The insert names the table `outbox` without a schema. The connection must resolve it through `search_path`
  to the setup's schema. Pools created by `PgConnectionManager`, including `PeeGeeQManager.getPool()`, set
  `search_path` from the configured schema (read in code, not run).
- The Future of `sendInExistingTransaction` completes when the insert has run. The row is visible to consumers
  only after the caller's transaction commits.
- The producer does not check that a transaction is open. On a connection with no open transaction the
  insert commits by itself.
- The business statements and the outbox insert must use the same connection. A second connection is a
  second transaction.

The native producer has no caller-transaction variant. Every native send commits its own transaction, which
also sends the `NOTIFY`.

### 2.4 Producer idempotency key

Put the key in the `idempotencyKey` header to make a send idempotent.

```java
Future<Void> publishOrderPaid(OutboxProducer<OrderPaid> producer, OrderPaid event) {
    return producer.send(event, Map.of("idempotencyKey", "order-paid-" + event.orderId()));
}
```

- The outbox insert uses `ON CONFLICT (topic, idempotency_key) ... DO NOTHING`. A second send with the same
  key on the same topic succeeds and writes no row. A blank key is treated as no key. This applies to every
  outbox send overload and mode.
- The native producer honours the key in the four-argument `send` only. The shorter overloads, including
  `send(payload, headers)`, use an insert without the `idempotency_key` column, so the key is stored as a
  plain header and does not deduplicate (register D44). On a native factory, use
  `send(payload, headers, correlationId, messageGroup)` with the header.
- The native four-argument `send` detects a duplicate from the unique-index violation and returns success.
  This path has no dedicated test (read in code, not run).
- The key is unique per topic for as long as the row exists. After the row is deleted, the same key can be
  written again.
- A dead-letter reprocess re-inserts the message without the `idempotency_key` column. The `idempotencyKey`
  header is kept, but the reprocessed row does not block a later send with the same key (register D44). On a
  native queue the original row was deleted when it was dead-lettered, so nothing blocks that send. On an
  outbox queue the original row stays as `DEAD_LETTER` with its key and still blocks it (read in code, not
  run).

The REST API maps the `Idempotency-Key` HTTP header to this header; see the
[REST API Guide](PEEGEEQ_REST_API_GUIDE.md).

## 3. Consumers

### 3.1 Subscribing

A factory creates a consumer with `createConsumer(topic, payloadType)`, or with
`createConsumer(topic, payloadType, config)` for the settings of section 3.3. `subscribe(handler)` returns
`Future<Void>` (read in code, not run):

- native consumer: the Future completes when the `LISTEN` command is acknowledged, or when the polling timer
  is scheduled in `POLLING_ONLY` mode. Compose on it before sending, so the first notification is not missed.
- outbox consumer: the Future completes when polling is scheduled.

A `MessageHandler<T>` returns `Future<Void>`. The outcome of that Future decides what happens to the message
(read in code, not run):

| Handler outcome | Native consumer | Outbox consumer |
|---|---|---|
| Succeeded Future | Deletes the row | Sets status `COMPLETED` |
| Failed Future, or `null` | Failed attempt (section 6) | Failed attempt (section 6) |
| Thrown exception | Failed attempt for an `Exception`; the consumer catches `Exception` only | Failed attempt for any `Throwable`, including an `Error` |

A succeeded Future is the acknowledgement. Return it only after the work is durable. A handler that returns
success for a message it could not process has acknowledged that message, and it will not be delivered again.

`unsubscribe()` stops new deliveries. `closeAsync()` completes when the consumer has released its resources;
the outbox consumer waits for poll cycles already in progress.

### 3.2 How consumers find new messages

The outbox consumer polls. It claims rows with `FOR UPDATE SKIP LOCKED` in `created_at, id` order. Within
one consumer instance, it runs messages of the same message group one after another (read in code, not run).

The native consumer has three modes, set in `ConsumerConfig` (behaviour read in code, not run):

| `ConsumerMode` | Behaviour |
|---|---|
| `LISTEN_NOTIFY_ONLY` | Wakes on `NOTIFY` only. No polling. A 10-second job releases expired locks, and a one-shot timer wakes the consumer when a delayed message becomes visible |
| `POLLING_ONLY` | Polls on a timer. No `LISTEN` |
| `HYBRID` | `LISTEN` plus polling. The default |

The native producer sends `pg_notify(channel, id)` in the same transaction as the insert. The channel is
`<schema>_queue_<topic>`, truncated with a hash suffix above 63 bytes. The payload is the row id, so each
insert sends a distinct notification. PostgreSQL folds only notifications with the same channel and payload in
one transaction; that rule is PostgreSQL behaviour and was not checked for this guide. A notification triggers
a claim, and the consumer claims again after each message it settles, so it drains the backlog instead of
fetching one message per notification (read in code, not run). A notification sent while the `LISTEN`
connection is down is lost; `HYBRID` polling covers that gap.

The native consumer claims in `priority DESC, created_at ASC` order. It sets the row to `LOCKED` with a
`lock_until` derived from the visibility timeout (read in code, not run).

### 3.3 Consumer settings

| Class | Factory | Settings |
|---|---|---|
| `ConsumerConfig` (`peegeeq-native`) | native | mode, polling interval, `enableNotifications`, batch size, consumer threads, server-side filter. No consumer code reads `enableNotifications`; the mode decides whether `LISTEN` runs (read in code, not run) |
| `OutboxConsumerConfig` (`peegeeq-outbox`) | outbox | polling interval, batch size, consumer threads, retry limit, server-side filter |

A factory rejects a configuration object of the other type. Without a configuration object the consumer
uses the queue settings of the [Configuration Guide](PEEGEEQ_CONFIGURATION_GUIDE.md).

### 3.4 Reading without consuming

`QueueFactory.createBrowser(topic, payloadType)` returns a `Future<QueueBrowser<T>>`. A browser reads messages without
claiming or acknowledging them. Admin and monitoring code uses a browser, not a consumer.

## 4. Consumer groups

### 4.1 What a group does

`createConsumerGroup(groupName, topic, payloadType)` returns a `ConsumerGroup<T>`. A group owns one underlying
consumer (or, on an `OFFSET_WATERMARK` topic, the partitioned engine) and hands each claimed message to one
member.

- `addConsumer(consumerId, handler)` and `addConsumer(consumerId, handler, filter)` add members.
  `setMessageHandler(handler)` adds one default member.
- The group selects a member by `hash(message id) mod eligible members`. The `LoadBalancingStrategy` enum
  (`ROUND_ROBIN`, `RANGE`, `STICKY`, `RANDOM`) is not applied: `getLoadBalancingStrategy()` returns the
  interface default, `ROUND_ROBIN`, in both implementations (read in code, not run; register D44).
- The member's handler outcome is the message outcome of section 3.1.

Two groups on the same topic, in the default `REFERENCE_COUNTING` mode, compete for its messages: each message
goes to one group. This holds for `QUEUE` and `PUB_SUB` topics alike, because the group claims through the
row status that all groups share (read in code, not run; register D44). The test
[`AdvancedProducerConsumerGroupTest`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/AdvancedProducerConsumerGroupTest.java)
`testConcurrentConsumerGroups` asserts this for outbox groups on a `QUEUE` topic only. The per-group completion
path for `PUB_SUB` (`ConsumerGroupFetcher`, and the `CompletionTracker` test fixture) has no production caller.

On an `OFFSET_WATERMARK` topic each group has its own committed offsets, so groups do not take messages from
each other through a shared status. A group can still miss rows in three ways (register D44; read in code, not
run):

- Another group's concurrent fetch holds a row locked. The fetch skips it with `SKIP LOCKED`, and the group then
  commits the last id of its batch, which moves its offset past the skipped row.
- The group subscribes after the watermark has already completed the rows.
- The group is not `ACTIVE`. The watermark counts `ACTIVE` groups only, so rows the group has not read can be
  completed.

That mode requires the outbox producer and a schema built by the Flyway migrations: the partition tables exist
only in `V017` and `V018`, and the setup templates do not create them (register D42; see the
[Database Guide](PEEGEEQ_DATABASE_GUIDE.md)). The [Ordering Guide](PEEGEEQ_ORDERING_GUIDE.md) describes the
mode.

### 4.2 Topic configuration

`TopicConfigService` (`peegeeq-db`) stores a `TopicConfig` in the `outbox_topics` table. `createTopic(config)`
inserts or replaces the row; `updateTopic(config)` fails when the row does not exist.

| `TopicConfig` field | Effect |
|---|---|
| `semantics` | `QUEUE` (default) or `PUB_SUB`. Changes the zero-subscription check, the deletion rule of `CleanupService`, and (on a schema built by the Flyway migrations) the `required_consumer_groups` count that a trigger sets on insert. It does not change which group receives a message (section 4.1) |
| `completionTrackingMode` | `REFERENCE_COUNTING` (default) or `OFFSET_WATERMARK` |
| `messageRetentionHours`, `zeroSubscriptionRetentionHours` | Retention inputs of `CleanupService`. No production code schedules that service (see [Architecture](PEEGEEQ_ARCHITECTURE.md)) |
| `blockWritesOnZeroSubscriptions` | Input of `ZeroSubscriptionValidator` (section 4.7) |

A topic with no row behaves as `QUEUE` with `REFERENCE_COUNTING`.

### 4.3 Starting a group

| Call | Subscription row | Start position | Heartbeat and `DEAD` tracking |
|---|---|---|---|
| `start()` | Not created | None. The group consumes every message still pending on the topic | None |
| `start(SubscriptionOptions)` | Created, or reactivated (section 4.6) | From the stored subscription | Yes |

Only `start(SubscriptionOptions)` applies a start position, and only on a `REFERENCE_COUNTING` topic. On an
`OFFSET_WATERMARK` topic the start position has no effect: each partition offset starts at 0 (register D44).
`start()` is not equivalent to a `FROM_NOW` subscription.

`start(SubscriptionOptions)` on a group instance that is already active succeeds and does nothing in the native
implementation. In the outbox implementation it fails with `IllegalStateException` (read in code, not run).

The outbox group routes to the `OFFSET_WATERMARK` engine only from `start(SubscriptionOptions)`. The native
group checks the topic mode in both start methods. If the outbox group cannot read the topic mode, it logs a
warning and starts in `REFERENCE_COUNTING` mode (read in code, not run; register D44). The engine needs the
Flyway-built schema of register D42; see the [Database Guide](PEEGEEQ_DATABASE_GUIDE.md).

```java
Future<Void> startBilling(QueueFactory factory, MessageHandler<OrderEvent> handler) {
    ConsumerGroup<OrderEvent> group = factory.createConsumerGroup("billing", "orders", OrderEvent.class);
    group.setMessageHandler(handler);
    return group.start(SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_BEGINNING)
            .backfillScope(BackfillScope.PENDING_ONLY)
            .build());
}
```

`stop()` stops the instance. `stopGracefully()` also sets the subscription to `STOPPED` when the group was
started with options. `close()` releases the group; a factory closes the groups it created when the factory
closes.

### 4.4 Subscriptions and start positions

A subscription is a row in `outbox_topic_subscriptions`, keyed by topic and group name. `SubscriptionService`
(from `DatabaseService.getSubscriptionService()`) manages it. `subscribe(topic, group, store, options)` takes a
`SubscriptionStore`: `OUTBOX` or `NATIVE`, the table that the stored message ids refer to. A factory returns its
store from `getSubscriptionStore()`. There is no default store.

| `StartPosition` | Stored start bound |
|---|---|
| `FROM_NOW` (default) | `MAX(id) + 1` of the store's table for the topic, read in the transaction that creates the row |
| `FROM_BEGINNING` | Message id 1 |
| `FROM_MESSAGE_ID` | `startFromMessageId` |
| `FROM_TIMESTAMP` | `startFromTimestamp` |

The bound is stored once, when the row is created. A later subscribe or start keeps the stored bound and
ignores the start position of its options. The bound applies to `REFERENCE_COUNTING` topics only; the
`OFFSET_WATERMARK` engine ignores it and starts each partition offset at 0 (register D44).

The bound limits what the group claims. It does not bring back rows that are no longer pending: the outbox
group claims `PENDING` rows only, and the native consumer deletes rows it completes. `FROM_BEGINNING` therefore
delivers the messages from id 1 that are still pending, not the topic's full history (read in code, not run).

### 4.5 Backfill

When `subscribe` is called with `FROM_BEGINNING` and the stored bound is the beginning, it also runs
`BackfillService`. The `subscribe` Future completes after the backfill, and a failed backfill fails it.

| `BackfillScope` | Outbox rows included |
|---|---|
| `PENDING_ONLY` (default) | `PENDING` and `PROCESSING` |
| `ALL_RETAINED` | `PENDING`, `PROCESSING` and `COMPLETED` |

The backfill raises `required_consumer_groups` and writes `outbox_consumer_groups` tracking rows. It reads the
`outbox` table whatever the subscription store (register D44). The production consumer-group path does not read the tracking
rows, so the backfill changes bookkeeping, not what the group receives (read in code, not run). The
[Architecture](PEEGEEQ_ARCHITECTURE.md) guide has the two backfill sequence diagrams.

### 4.6 Subscription lifecycle

States (`SubscriptionState`): `ACTIVE`, `PAUSED`, `DEAD`, `CANCELLED`, `STOPPED`.

| Operation | Accepted from | Result | Otherwise |
|---|---|---|---|
| `subscribe` / `start(options)` with no row | (no row) | `ACTIVE` | |
| `subscribe` / `start(options)` on an existing row | `ACTIVE`, `STOPPED`, `DEAD` | `ACTIVE` | `PAUSED` stays `PAUSED`; `CANCELLED` fails |
| `pause` | `ACTIVE`, `STOPPED`, `PAUSED` | `PAUSED` | Fails on `DEAD`, `CANCELLED` |
| `resume` | `ACTIVE`, `PAUSED`, `DEAD` | `ACTIVE` | Fails on `STOPPED`, `CANCELLED` |
| `stopGroup` / `stopGracefully()` | `ACTIVE` | `STOPPED` | Other states unchanged |
| `cancel` | any | `CANCELLED` | |
| Dead-consumer detection | `ACTIVE`, `PAUSED` | `DEAD` | |
| `updateHeartbeat` | `ACTIVE`, `PAUSED` | State unchanged | Fails on `STOPPED`, `DEAD`, `CANCELLED` |

Rules that follow from the table:

- `CANCELLED` is terminal. A subscribe to a cancelled subscription fails with `SubscriptionStateException`.
- A heartbeat never changes the state. It does not make a `DEAD` subscription `ACTIVE`. Use `resume`, or start
  the group again with options.
- `STOPPED` keeps the start bound and the committed offsets. A later start makes it `ACTIVE`.

Heartbeats are not sent automatically. No production consumer group calls `updateHeartbeat`; the only caller is
the REST heartbeat endpoint. An application that starts a group with options must send heartbeats itself, at
the `heartbeatIntervalSeconds` of its options:

```java
long startHeartbeats(Vertx vertx, SubscriptionService subscriptions, SubscriptionOptions options) {
    return vertx.setPeriodic(options.getHeartbeatIntervalSeconds() * 1000L, timerId ->
        subscriptions.updateHeartbeat("orders", "billing")
            .onFailure(error -> logger.error("Heartbeat failed for group billing on orders", error)));
}
```

The dead-consumer detection job runs on a timer unless it is disabled (see the
[Configuration Guide](PEEGEEQ_CONFIGURATION_GUIDE.md)). On each run it counts a miss for every `ACTIVE` or
`PAUSED` subscription whose last heartbeat is older than `heartbeatTimeoutSeconds`. It marks the subscription
`DEAD` when the count reaches `deadAfterMisses` (default 3). A heartbeat on an `ACTIVE` subscription clears the
count.

Two consequences of the code, both read in code, not run:

- The claim path of a running group does not read the subscription state. Pausing, cancelling or marking a
  subscription `DEAD` does not stop a running group instance; stop the instance (register D44).
- `DeadConsumerGroupCleanup` can mark undelivered outbox messages `COMPLETED` (register D1). It runs on
  `cancel`, on `forceRemoveConsumerGroup`, and on every detection run that finds a new `DEAD` subscription; that
  run cleans up every `DEAD` group, not only the new one. Each run first lowers `required_consumer_groups` on
  the topic's `PENDING` and `PROCESSING` outbox rows created after the group subscribed, so a later run lowers
  the count of newer rows again. It then sets every `PENDING` or `PROCESSING` row of the topic whose required
  count no longer exceeds its completed count to `COMPLETED`. The production path never records a per-group
  completion, so the completed count stays 0.

Send heartbeats for every subscription you create. A group on an `OFFSET_WATERMARK` topic sends no
subscription heartbeat: its engine refreshes its partition assignments only. Its subscription therefore becomes
`DEAD` while detection runs, unless the application sends heartbeats for it.

### 4.7 Zero-subscription protection

`ZeroSubscriptionValidator.isWriteAllowed(topic)` returns `false` for a `PUB_SUB` topic with
`blockWritesOnZeroSubscriptions` set and no `ACTIVE` subscription, and `true` otherwise.
`validateWriteAllowed(topic)` fails with `NoActiveSubscriptionsException` in the same case. The producers do not
call the validator. A producer that needs the protection calls it before `send`.

## 5. Filtering

### 5.1 Client-side filters (consumer groups)

A client-side filter is a `Predicate<Message<T>>` evaluated in the JVM after the message is claimed.
`MessageFilter` provides `byHeader`, `byHeaderIn`, `byHeaderPattern`, `byHeaders`, `byAnyHeader`, `byPriority`,
`byRegion`, `byType`, `bySource`, `acceptAll`, `rejectAll`, `and`, `or` and `not`.

```java
group.addConsumer("eu-worker", euHandler, MessageFilter.byHeader("region", "EU"));
group.setGroupFilter(MessageFilter.byHeaderIn("type", Set.of("ORDER_CREATED", "ORDER_PAID")));
```

A member filter chooses which members may take a message. A group filter decides whether the group takes it at
all. The outcomes differ by implementation (read in code, not run):

| Case | Native group | Outbox group |
|---|---|---|
| Group filter refuses the message | Recorded in `queue_message_group_exclusions`; this group never claims it again; the row is released for other consumers | Row reset to `PENDING`; not completed, retried or dead-lettered |
| No member filter accepts it | Row released and made visible again after a delay | Row reset to `PENDING` |
| A filter throws | Failed attempt (a member filter only when no other member accepts the message) | Group filter: treated as a refusal. Member filter: failed attempt |

A message that no consumer on the topic accepts stays in the table.

### 5.2 Server-side filters (single consumers)

A server-side filter is a `ServerSideFilter` translated into the SQL `WHERE` clause of the claim. Rows that do
not match are not claimed and stay available for other consumers. Set it with
`ConsumerConfig.Builder.serverSideFilter(...)` or `OutboxConsumerConfig.Builder.serverSideFilter(...)` and pass
the configuration to `createConsumer(topic, payloadType, config)`. `createConsumerGroup` takes no consumer
configuration, so server-side filters apply to single consumers only.

| Factory method | SQL condition on the `headers` JSONB column |
|---|---|
| `headerEquals(key, value)` | key present and equal |
| `headerIn(key, values)` | key present and in the set; the set must not be empty |
| `headerNotEquals(key, value)` | key absent, or present and not equal |
| `headerLike(key, pattern)` | key present and `LIKE` the pattern |
| `and(filters...)`, `or(filters...)` | Combination; each needs at least two filters and fails with `IllegalArgumentException` otherwise |

Header keys must match `^[a-zA-Z][a-zA-Z0-9_-]*$`; any other key fails when the filter is built. Values are
bound as statement parameters.

```java
ConsumerConfig config = ConsumerConfig.builder()
        .mode(ConsumerMode.HYBRID)
        .serverSideFilter(ServerSideFilter.and(
                ServerSideFilter.headerEquals("region", "EU"),
                ServerSideFilter.headerIn("priority", Set.of("HIGH", "URGENT"))))
        .build();
MessageConsumer<OrderEvent> consumer = nativeFactory.createConsumer("orders", OrderEvent.class, config);
```

## 6. Retry and dead letter

A failed attempt is a failed handler Future, a thrown exception, a `null` return, or (native only) a handler
that does not settle within the visibility timeout. The retry limit is a queue setting in the
[Configuration Guide](PEEGEEQ_CONFIGURATION_GUIDE.md); `OutboxConsumerConfig` can override it for one outbox
consumer. The table is read in code, not run.

| | Native consumer | Outbox consumer |
|---|---|---|
| After a failed attempt | Row set to `AVAILABLE`, `retry_count` raised | Row set to `PENDING`, `retry_count` raised |
| Delay before the next attempt | None | None |
| Retry limit reached | Row copied to `dead_letter_queue` and deleted, in one transaction | Row copied to `dead_letter_queue` and set to `DEAD_LETTER`, in one transaction |
| Handler does not settle in time | Counted as a failed attempt; expired locks are released every 10 seconds | Stuck-message recovery resets `PROCESSING` rows older than the recovery processing timeout to `PENDING`, without raising `retry_count` |

With a retry limit of N, a message is dead-lettered on its N + 1th failed attempt (read in code, not run). A
failed attempt is the only event that raises `retry_count`. The outbox consumer also redelivers without
charging `retry_count` in three cases (register D44): stuck-message recovery, a failed completion update, and a
payload that cannot be parsed. An outbox row whose payload cannot be parsed is therefore never dead-lettered;
it stays `PROCESSING` until stuck-message recovery resets it, and the cycle repeats. The native consumer counts
a parse failure as a failed attempt.

There is no backoff between attempts; a handler that needs one fails fast and lets the retry limit decide, or
schedules its own work.

No handler outcome dead-letters a message at once. A failed Future is the handler's only path to the dead
letter queue, and it uses the retry budget. The native consumer also dead-letters a message whose attempts
exceed the limit when its visibility timeout expires, and in the 10-second expired-lock job.
`RejectedMessageException` does not dead-letter: the outbox consumer resets the message to `PENDING` (register
D44; the class Javadoc says the message is dead-lettered).

Dead-letter records:

- `DeadLetterService` (`peegeeq-api`; implemented by `DeadLetterQueueManager` in `peegeeq-db`) lists, reads,
  reprocesses and deletes records, and reports statistics. Reprocessing inserts a new row into the original
  table, so the message gets a new id.
- `PeeGeeQManager` purges dead-letter records older than 30 days once every 24 hours. Both values are fixed in
  code (read in code, not run).
- The REST form is in the [REST API Guide](PEEGEEQ_REST_API_GUIDE.md).

A topic in `OFFSET_WATERMARK` mode has no retry count and no dead letter; see the
[Ordering Guide](PEEGEEQ_ORDERING_GUIDE.md).

## 7. Idempotent handlers

At-least-once delivery means a handler must produce the same result when it sees a message twice. Record what
was applied in the same transaction as the effect, and let a unique constraint reject the second application.

```java
MessageHandler<OrderEvent> handler = message ->
    pool.withTransaction(connection ->
        connection.preparedQuery(
                "INSERT INTO processed_orders (order_id) VALUES ($1) ON CONFLICT DO NOTHING")
            .execute(Tuple.of(message.getPayload().orderId()))
            .compose(rows -> rows.rowCount() == 0
                ? Future.<Void>succeededFuture()
                : applyOrder(connection, message.getPayload())));
```

`applyOrder` is application code that returns `Future<Void>` and uses the same connection. When the insert
writes no row, the order was already applied and the handler acknowledges the duplicate.

Choosing the key:

- Use a business key or the producer's idempotency key. Both survive a dead-letter reprocess.
- `Message.getId()` is stable across redeliveries of one row. It changes when a dead-lettered message is
  reprocessed, because reprocessing inserts a new row. The reprocessed row also has no `idempotency_key` value
  (section 2.4).
- An in-memory set of seen IDs is lost on restart. Use it only in tests.

## 8. Examples

| Class | Shows |
|---|---|
| [`VertxTransactionParticipationExample`](../peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/outbox/VertxTransactionParticipationExample.java) | Caller-owned transaction with `sendInExistingTransaction` |
| [`springboot/service/OrderService`](../peegeeq-examples-spring/src/main/java/dev/mars/peegeeq/examples/springboot/service/OrderService.java) | The same pattern from a Spring service; `peegeeq-examples-spring` is built outside the main reactor |
| [`OutboxProducerTransactionTest`](../peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxProducerTransactionTest.java) | Send modes, the off-context `IllegalStateException`, idempotency keys |
| [`ConsumerGroupLoadBalancingDemoTest`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/nativequeue/ConsumerGroupLoadBalancingDemoTest.java) | Members of one native group sharing a topic |
| [`AdvancedProducerConsumerGroupTest`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/AdvancedProducerConsumerGroupTest.java) | Member filters; competing groups on one topic |
| [`DeadConsumerDetectionDemoTest`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/DeadConsumerDetectionDemoTest.java) | Heartbeat timeout and `DEAD` detection |
| [`ZeroSubscriptionProtectionDemoTest`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/ZeroSubscriptionProtectionDemoTest.java) | `ZeroSubscriptionValidator` |
| [`ConsumerGroupResilienceTest`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/ConsumerGroupResilienceTest.java) | Handler failures in a group |
| [`ServerSideFilteringTest`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/nativequeue/ServerSideFilteringTest.java), [`OutboxServerSideFilteringTest`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/OutboxServerSideFilteringTest.java) | Server-side filters |

`LateJoiningConsumerDemoTest` is not listed: it reads through `ConsumerGroupFetcher`, which has no production
caller, so its Javadoc statement that every `PUB_SUB` group receives every message does not describe
production consumer groups.

Every test class in the table is tagged `@Tag(TestCategories.INTEGRATION)`. The command forms and profiles are in
`docs-design/testing/PEEGEEQ-TEST-COMMANDS.md`; see the [Contributor Guide](PEEGEEQ_CONTRIBUTOR_GUIDE.md).
