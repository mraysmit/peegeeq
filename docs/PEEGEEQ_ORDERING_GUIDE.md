# PeeGeeQ Ordering Guide
© Mark Andrew Ray-Smith Cityline Ltd 2025  
Version 1.0

This guide explains how to choose and apply an ordering model: simple consumption with idempotent handlers, or
partitioned consumption with per-key order (`OFFSET_WATERMARK`).

Related documents: [Messaging Guide](PEEGEEQ_MESSAGING_GUIDE.md) (producers, consumers, consumer groups,
consumer modes), [Architecture](PEEGEEQ_ARCHITECTURE.md) (storage models and the at-least-once guarantee),
[Partitioned Consumption Design](../docs-design/consumer-groups/PEEGEEQ_PARTITIONED_CONSUMPTION_DESIGN.md)
(internals and design invariants).

Statements marked "(read in code, not run)" come from reading the source. They have not been confirmed by a
test run for this guide.

## 1. PeeGeeQ's ordering model

### Storage order is not processing order

PeeGeeQ gives each message an `id` from a sequence when the row is inserted. The `id` order is the insert order,
not the commit order: a transaction that inserts first can commit after one that inserts later. Processing the
rows concurrently is a further problem.

Consumers claim rows with `FOR UPDATE SKIP LOCKED`. When several consumer instances or threads run the claim
query at once, each locks different rows and skips rows locked by the others. Nobody blocks, but the order in
which the claimed rows are processed is not fixed.

Example: a consumer claims `[v1, v2, v3, v4]` and runs four handlers concurrently. `v4` finishes first. A state
machine or read model that applies events in completion order is now wrong.

The order matters only when the processing order of messages carries meaning.

### When ordering matters

Requires strict ordering:

| Workload | Why |
|---|---|
| Event sourcing and CQRS projections | Version-sequenced projections become inconsistent if events arrive out of order |
| State machines | Invalid transitions if later events are applied before earlier ones |
| Per-account financial transactions | Balance calculations depend on the order of postings |
| Aggregate rehydration | Events must replay in their original order to rebuild state |

Tolerates out-of-order delivery:

| Workload | Why |
|---|---|
| Metrics aggregation | The totals are the same in any order |
| Log collection | Each entry is independent |
| Notification delivery | Each notification is self-contained |
| Cache invalidation | The latest invalidation wins |

### Why sorting inside the consumer is the wrong fix

Buffering messages and sorting them by version before applying them fails in three ways:

- Incomplete batches. A consumer cannot know whether the next message was claimed by another instance. It cannot
  tell "not yet delivered" from "not yet sent".
- At-least-once redelivery. A message can arrive twice across restarts. Sorting does not help if the version was
  already applied in an earlier delivery.
- Complexity. A resequencing buffer turns a stateless handler into a stateful component with its own failure
  modes.

The correct fix is to deliver the messages of one entity in order at the infrastructure level.

## 2. Choosing a consumption mode

| | Simple (`MessageConsumer`, or a `ConsumerGroup` on a `REFERENCE_COUNTING` topic) | Partitioned (`ConsumerGroup` on an `OFFSET_WATERMARK` topic) |
|---|---|---|
| Per-key ordering | Not guaranteed across instances | Guaranteed per message group |
| Global ordering | Not provided | Not provided (section 6) |
| Multiple instances | Fully concurrent | Partitions distributed across the instances of a group |
| Producer | Native or outbox | Outbox only |
| Retry limit and dead letter | Yes | No; a failed batch is fetched again |
| Filter refusal | Message stays available to other consumers (Messaging Guide) | Outbox group: the batch fails and the partition stops. Native group: a group-filter refusal moves the offset past the message (section 4) |
| Schema | Either provisioning path | Built by the Flyway migrations (register D42) |
| Setup | Minimal | Topic configuration and partition key design |
| Idempotent handlers | Required | Required |
| Fits | Metrics, logs, notifications, cache invalidation | Event sourcing, state machines, per-account operations |

The native consumer modes (`LISTEN_NOTIFY_ONLY`, `POLLING_ONLY`, `HYBRID`) decide how a consumer wakes up. They
do not change ordering. The [Messaging Guide](PEEGEEQ_MESSAGING_GUIDE.md) describes them.

### The partition key

The partition key is the message group: the `messageGroup` argument of
`send(payload, headers, correlationId, messageGroup)`. On an `OFFSET_WATERMARK` topic all messages with the same
key are processed one after another, and messages with different keys run concurrently, subject to the member
concurrency limit of section 4 (read in code, not run).

Choose the key to match the unit of consistency:

| Domain | Partition key |
|---|---|
| Banking | Account ID |
| E-commerce | Order ID |
| Event sourcing | Aggregate ID |
| IoT telemetry | Device ID |
| Workflow | Workflow instance ID |

Set the key on every send to an `OFFSET_WATERMARK` topic. A message without a key goes to the `__default__`
partition, which is processed serially (section 4).

## 3. Pattern 1: simple consumption with idempotent handlers

Use a `MessageConsumer` when processing order does not affect correctness: throughput matters more than order,
each message is self-contained, and the outcome does not depend on delivery order.

```java
Future<Void> startMetrics(QueueFactory queueFactory) {
    MessageConsumer<MetricEvent> consumer = queueFactory.createConsumer("metrics-queue", MetricEvent.class);
    return consumer.subscribe(message -> metricsStore.recordOnce(message.getPayload()));
}
```

`metricsStore.recordOnce` is application code that returns `Future<Void>` and ignores an event ID it has already
recorded. At-least-once delivery applies in every mode, so every handler needs this guard. Persist the guard in
the database, in the same transaction as the effect; the [Messaging Guide](PEEGEEQ_MESSAGING_GUIDE.md) shows the
pattern. An in-memory set of seen IDs is lost on restart and is suitable for tests only.

The outbox consumer runs the messages of one message group one after another within one consumer instance. This
does not hold across instances, and the native consumer does not do it (read in code, not run). Do not rely on it
for correctness.

## 4. Pattern 2: partitioned consumption (OFFSET_WATERMARK)

Use a `ConsumerGroup` on an `OFFSET_WATERMARK` topic when the messages of one entity must be processed in the
order they were published.

### Requirements

All read in code, not run:

- The partitioned engine reads the `outbox` table only. Produce with the outbox producer (`OutboxProducer`, or a
  factory of type `outbox`). A native producer writes `queue_messages`, which the engine never reads. A consumer
  group from either factory routes to the engine on an `OFFSET_WATERMARK` topic; a native-factory group then
  reads the `outbox` table too.
- The schema must be built by the Flyway migrations. The partition tables exist only in `V017` and `V018`; the
  setup templates do not create them (register D42; see the [Database Guide](PEEGEEQ_DATABASE_GUIDE.md)).
- Start the group with `start(SubscriptionOptions)`. Joining a partitioned group needs the subscription row; the
  outbox group routes to the engine only from this method.
- The start position of the options is ignored. Each partition offset starts at 0, so the group reads every
  `PENDING` or `PROCESSING` row of the partition (register D44).
- Send subscription heartbeats. The engine refreshes its partition assignments, not the subscription heartbeat,
  so without application heartbeats the subscription becomes `DEAD` while dead-consumer detection runs. A `DEAD`
  subscription triggers the cleanup of register D1 and stops holding back the watermark (see "Operational
  boundaries").
- Each group on the topic keeps its own committed offsets. Groups do not take messages from each other through a
  shared status, but a group can still miss rows; see "Operational boundaries".

### How the ordering guarantee works

For each assigned partition the implementation applies this sequence (read in code, not run):

1. The assignment service gives each discovered partition to one instance of the group for the current
   generation.
2. An in-memory guard allows at most one fetch-and-handle chain per partition in an engine.
3. The engine fetches rows after the committed offset in ascending message id order.
4. Row locking (`FOR UPDATE OF o SKIP LOCKED`) stops two concurrent fetches from claiming the same row. The lock
   lasts only for the fetch transaction, which commits before the handlers run.
5. Handler calls within a partition are composed one after another.
6. The committed offset advances only after the whole batch succeeds.
7. A rebalance raises the generation, which stops a displaced owner from advancing the offset.
8. A periodic heartbeat and assignment reconciliation refresh liveness and drop partitions the instance no longer
   owns.

| Mechanism | Purpose |
|---|---|
| Unique assignment row | No two committed assignments for the same topic, group and partition |
| Rebalance in one serialized transaction | No partly visible assignment change |
| Generation fencing | Offset commits from an earlier rebalance are rejected |
| Row locking during fetch | Two concurrent fetches cannot lock the same row; the lock ends with the fetch transaction |
| Per-partition in-progress guard | No overlapping batches in one engine |
| Offset commit after successful handling | A failed or interrupted batch replays from the last committed offset |
| Periodic heartbeat and reconciliation | Refreshes liveness and the engine's in-memory ownership |

These controls protect ordering within a partition and the offset. They do not stop a group from missing rows
(see "Operational boundaries"), and they do not make side effects exactly-once. A handler that is
still running during a rebalance may finish before its stale offset commit is rejected. Handlers must stay
idempotent and safe to replay.

### Step 1: configure the topic

```java
Future<Void> configureAccountEvents(PeeGeeQManager manager) {
    TopicConfigService topics = new TopicConfigService(
            manager.getClientFactory().getConnectionManager(), PeeGeeQDefaults.DEFAULT_POOL_ID);
    return topics.createTopic(TopicConfig.builder()
            .topic("account-events")
            .completionTrackingMode("OFFSET_WATERMARK")
            .build());
}
```

`createTopic` inserts the `outbox_topics` row, or replaces it when it exists. A replace writes every field,
including `semantics` and the retention fields, so fields not set on the builder are reset to the builder
defaults. To change only the mode of an existing topic, read it with `getTopic`, copy its fields into the
builder, and call `updateTopic`.

### Step 2: producer, always set the message group

```java
Future<Void> publish(OutboxProducer<DomainEvent> producer, DomainEvent event,
                     Map<String, String> headers, String correlationId) {
    return producer.send(event, headers, correlationId, event.getAggregateId());
}
```

Return or compose the `Future`. Discarding it lets the command handler finish before the event is durable.

### Step 3: consumer, a ConsumerGroup started with options

```java
Future<Void> startProjection(QueueFactory outboxFactory, MessageHandler<DomainEvent> projection) {
    ConsumerGroup<DomainEvent> group = outboxFactory.createConsumerGroup(
            "account-event-processors", "account-events", DomainEvent.class);
    group.setMessageHandler(projection);
    return group.start(SubscriptionOptions.fromBeginning());
}
```

The engine calls the handler one message after another for each partition. The handler receives a
`SimpleMessage`; `getMessageGroup()` is on `SimpleMessage`, not on `Message`. A handler that needs the key reads it
from its payload (here `event.getAggregateId()`) or casts to `SimpleMessage`. The engine does not add the
`correlationId` header; read `SimpleMessage.getCorrelationId()` (read in code, not run).

In an outbox group the member concurrency limit is set once, at start, to the number of partitions assigned then;
the default is 1. A partition gained later, for example after a rebalance, can find the member at its limit. The
dispatch then fails with "at max concurrency", the batch fails, and it is fetched again (read in code, not run).

### Concurrency across partitions

```
account-001 events -> partition account-001 -> instance A (one after another)
account-002 events -> partition account-002 -> instance B (one after another)
account-003 events -> partition account-003 -> instance A (one after another)
```

Different partitions can run in parallel, within the member concurrency limit above. Adding an instance triggers
a rebalance that redistributes partitions (read in code, not run).
Throughput depends on the number of keys, key skew, handler cost and database capacity. Measure it for the target
deployment; do not assume it scales linearly.

### The `__default__` partition

A message sent without a message group is stored with `message_group = NULL`. `PartitionAssignmentService` maps
`NULL` to the key `__default__`. All such messages share one serial partition: they block each other, and the
throughput benefit of partitioning is lost. The `__default__` partition is a safety net, not a usage pattern.

### Operational boundaries

Everything in this section is read in code, not run.

Partition discovery happens during a rebalance. `joinGroup` discovers partitions from the `PENDING` and
`PROCESSING` outbox rows that exist at that moment. With no rows, the first assignment can be empty. A key that
first appears after the rebalance is not assigned by the heartbeat cycle; it needs a later join or leave. Where the
key set is known, publish one message for each key before the first join. Otherwise trigger a rebalance by
operational means when new keys appear.

Heartbeat and reconciliation are periodic. `PartitionedConsumerEngine` updates its assignment heartbeat and
reconciles its assignments on a timer. Reconciliation initialises missing offset state, drops partitions the
instance no longer owns, and picks up ownership changes from a rebalance. It does not discover new keys. Monitor
assignment-heartbeat age, rebalance churn, pending-offset age, watermark lag and repeated stale-generation commit
rejections.

A failed handler is not retried to a limit. The batch fails, the offset does not move, and the batch is fetched
again on the next cycle. There is no retry count and no dead letter on this path, so a message that always fails
stops its partition until the handler or the message is fixed (register D44).

Filter outcomes differ by factory. In an outbox group, a group-filter refusal or a message that no member accepts
fails the batch, so the partition stops at that message. In a native group, a group-filter refusal (`REJECTED`)
moves the offset past the message; a message that no member accepts (`UNAVAILABLE`) fails the batch.

A group can miss rows (register D44):

- Offsets use `id > committed offset` with no gap check. A row with a lower id that commits after a higher id of
  the same partition has been committed is never fetched; the watermark later completes it.
- The fetch lock lasts only for the fetch transaction. When another group's fetch holds a row, this group skips
  it with `SKIP LOCKED` and then commits the last id of its batch, which moves its offset past the skipped row.
- A group that subscribes after the watermark has completed rows never reads them.

The watermark completes rows. `WatermarkJob` takes the minimum committed offset over every partition offset row
of the topic's `ACTIVE` subscriptions and sets every `PENDING` or `PROCESSING` row at or below it to `COMPLETED`.
Consequences (register D44):

- An idle partition with a low offset holds the watermark back for the whole topic.
- A group whose subscription is not `ACTIVE` (for example `STOPPED` or `DEAD`) does not hold the watermark back.
  The engine fetches `PENDING` and `PROCESSING` rows only, so rows completed while that group was not `ACTIVE` are
  not delivered to it later.
- Rows of a key that no instance has been assigned are completed when the watermark passes them.

At-least-once delivery still applies. `OFFSET_WATERMARK` guarantees order within a partition. A failed or
interrupted batch replays from the last committed offset, and replays can also follow a restart, a generation
rejection or a rebalance. Handlers must stay idempotent.

## 5. Migration: simple to partitioned

### Before starting

- Identify the partition key: the field whose value groups messages that must be ordered relative to each other,
  for example an aggregate or entity ID.
- Confirm that every send to the topic sets that key as the message group, and that every producer is an outbox
  producer. One send without a key routes to `__default__`.
- Confirm that the schema was built by the Flyway migrations (register D42), and plan subscription heartbeats
  (section 4, "Requirements").
- Make the handlers idempotent first. A rebalance can replay a batch, and the first joins after the migration
  cause rebalances (read in code, not run).

### Steps

1. Configure the topic as `OFFSET_WATERMARK` with `TopicConfigService` (section 4, step 1).
2. Add the message group to every send, and keep returning the `Future`:

   ```java
   // Before
   return producer.send(event, headers, correlationId);
   // After
   return producer.send(event, headers, correlationId, event.getAggregateId());
   ```

3. Replace the `MessageConsumer` with a `ConsumerGroup` started with `start(SubscriptionOptions)` (section 4,
   step 3).
4. Add idempotency to the handler if it is not there (section 3).
5. Validate ordering and assert the per-key sequence.
   [`PartitionedOrderingDemoTest`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/PartitionedOrderingDemoTest.java)
   is the reference for one instance at a time. For two instances of one group,
   [`PartitionedNativeConsumerIntegrationTest`](../peegeeq-native/src/test/java/dev/mars/peegeeq/pgqueue/PartitionedNativeConsumerIntegrationTest.java)
   `testMultipleInstances_parallelPartitions` is the reference.

### Partition key selection

| Constraint | Guidance |
|---|---|
| Few distinct values (for example a `status` field with 3 values) | Only 3 partitions exist and most instances sit idle. Use a key with more values |
| Very many distinct values (for example a UUID per message) | Every message is its own partition; partition discovery scans more rows. Consider a hash bucket |
| The key is needed in the handler | Carry it in the payload. `getMessageGroup()` is on `SimpleMessage` only |
| The key contains personal data | The key is stored in `outbox_partition_assignments` and `outbox_partition_offsets`. Hash or pseudonymise it |

### Tests to write

The demo tests show each check:

| Check | Test |
|---|---|
| Each key's events arrive in strictly ascending version order | `PartitionedOrderingDemoTest.testPartitionedOrdering_eventsPerAggregateInOrder` |
| Two keys are processed with overlapping time windows | `PartitionedOrderingDemoTest.testPartitionedOrdering_differentAggregatesProcessedConcurrently` |
| Messages without a key form one `__default__` partition and arrive in send order | `PartitionedOrderingDemoTest.testPartitionedOrdering_defaultPartition_noMessageGroup` |
| After a graceful stop and restart, the committed offset prevents redelivery of the first batch | `PartitionedOrderingDemoTest.testPartitionedOrdering_idempotentRedelivery` |
| Each read model reaches the correct final state with several aggregates in parallel | [`EventSourcingCQRSDemoTest`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/EventSourcingCQRSDemoTest.java)`.testCQRS_multipleAccounts_perAccountOrdering` |

Other tests of this mode: `PartitionedConsumerSafetyIntegrationTest` (discovery and failed-batch redelivery),
`PartitionAssignmentIntegrationTest` (assignment, rebalance, generation, heartbeat),
`PartitionedFetcherIntegrationTest` and `PartitionedOffsetManagerIntegrationTest` (fetch and offsets), and
`PartitionedNativeConsumerIntegrationTest` (lifecycle and failure paths).

The release evidence for this mode, including the one-hour release gate, is recorded in the
[archived task register, section 6](../docs-design/tasks/archive/TASKS_REGISTER_TO_2026-11-21.md#6-partitioned-consumption-pre-ga-gates).

## 6. Decisions and boundaries

- `REFERENCE_COUNTING` is the default topic mode.
- `OFFSET_WATERMARK` is an explicit opt-in per topic.
- Producers supply the partition key explicitly. There is no reflection or annotation-based key extraction.
- A consumer-side version guard is an idempotency fence, not an ordering mechanism.
- Per-partition ordering does not imply exactly-once side effects.
- PeeGeeQ provides neither total global ordering nor a partitioned fetch path for the native table.

What those two designs would involve:

- Total global ordering. One active consumer guarded by a PostgreSQL session advisory lock could give total order
  for a topic. It trades horizontal throughput for active-passive failover and needs a dedicated session, fencing
  rules, lifecycle tests and operator guidance.
- Native-table partition fetch. A fetcher for `queue_messages` could combine the native storage path with the
  watermark contract. It needs a concrete latency requirement, offset ownership, notification and fetch
  interaction, schema indexes and compatibility tests.
