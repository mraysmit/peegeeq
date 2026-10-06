# Consumer Group Fan-Out Design: Hybrid Queue/Pub-Sub

**Status:** IMPLEMENTED CONTRACT WITH RETAINED DESIGN RATIONALE

**Last reconciled:** 2026-10-06 against commit `f1c5d25d`

**Author:** Mark Andrew Ray-Smith Cityline Ltd

**Originally created:** 2025-11-11

The 2026-10-06 revision removed unimplemented proposals, pre-implementation summaries,
duplicated schema copies, and completed-work plans; all removed text is retained in git
history. Every class, table, column, index, function, route, and file named below was
confirmed to exist in the repository at the reconciliation commit. Statements about
runtime behaviour come from static reading of the source unless a test or Jenkins record
is cited.

User guide: [Consumer Groups Guide](../../docs/PEEGEEQ_CONSUMER_GROUP_GETTING_STARTED.md).

---

## Table of Contents

1. [Overview](#overview)
2. [Design Principles](#design-principles)
3. [Implemented Contract](#implemented-contract)
   1. [Completion Tracking Modes](#completion-tracking-modes)
   2. [Schema](#schema)
   3. [Java API](#java-api)
   4. [Consumer Group Start Path](#consumer-group-start-path)
   5. [Subscription Lifecycle](#subscription-lifecycle)
   6. [Retention and Cleanup Defaults](#retention-and-cleanup-defaults)
   7. [REST Routes](#rest-routes)
   8. [Names From Earlier Revisions That Do Not Exist](#names-from-earlier-revisions-that-do-not-exist)
4. [Design Rationale](#design-rationale)
   1. [Design Alternatives Considered](#design-alternatives-considered)
   2. [Resolved Design Gaps](#resolved-design-gaps)
   3. [Scalability Conclusions](#scalability-conclusions)
   4. [Comparison With Other Systems](#comparison-with-other-systems)
5. [Partitioned Consumption](#partitioned-consumption)
6. [Release Gates](#release-gates)
7. [References](#references)

---

## Overview

PeeGeeQ's original consumer model implements queue semantics. The `OutboxConsumer` claim
query marks rows `PROCESSING` under `FOR UPDATE SKIP LOCKED`, so each message is processed
by exactly one consumer. Multiple consumer groups on the same topic compete for messages.

This document specifies the hybrid queue/pub-sub design that adds opt-in fan-out per topic.
A topic configured as `PUB_SUB` keeps a subscription row per consumer group, snapshots the
number of active groups on every message insert, and retains each message until the
configured completion rule is met.

Two completion tracking modes exist in the schema and the service layer:

- `REFERENCE_COUNTING`: per-message counters on `outbox` plus per-group rows in
  `outbox_consumer_groups`.
- `OFFSET_WATERMARK`: per-group, per-partition committed offsets with a per-topic watermark.

The implementation status of each mode is stated in
[Completion Tracking Modes](#completion-tracking-modes).

---

## Design Principles

1. Backward compatibility. Existing queue consumers work unchanged.
2. Opt-in. Only topics configured as `PUB_SUB` use fan-out behaviour.
3. Explicit configuration. Topic semantics and completion mode are stored per topic.
4. Gradual migration. Topics can be migrated one at a time.
5. Performance. Queue topics keep the `FOR UPDATE SKIP LOCKED` claim path.
6. PostgreSQL only. All coordination is SQL plus `LISTEN/NOTIFY`; no external broker.
7. Vert.x 5 reactive. Every public API returns `Future<T>`; no blocking calls.

---

## Implemented Contract

### Completion Tracking Modes

| Mode | Schema | Service layer | Production consumer path | Release evidence |
|---|---|---|---|---|
| `OFFSET_WATERMARK` | V017, V018 | `PartitionedOffsetManager`, `PartitionAssignmentService`, `PartitionedFetcher`, `WatermarkCalculator`, `WatermarkJob`, `PartitionedConsumerEngine` | `OutboxConsumerGroup.start(SubscriptionOptions)` and `PgNativeConsumerGroup` route `OFFSET_WATERMARK` topics to `PartitionedConsumerEngine` | Task 6 release gate complete (`docs-design/tasks/tasks.md` §6, Jenkins build #11, 2026-09-17) |
| `REFERENCE_COUNTING` | V010 | `ConsumerGroupFetcher` (peegeeq-db main), `CompletionTracker` (peegeeq-db **test** scope) | None. See below. | None recorded |
| Bitmap tracking | `outbox.completed_groups_bitmap` column only (V010) | None | None | None |

`OFFSET_WATERMARK` is implemented and gated. Its design, lifecycle contract, and release
evidence are maintained in
[PEEGEEQ_PARTITIONED_CONSUMPTION_DESIGN.md](PEEGEEQ_PARTITIONED_CONSUMPTION_DESIGN.md).

`REFERENCE_COUNTING` has no production completion writer. Commit `b81be29b` (2026-08-10)
moved `CompletionTracker` to `peegeeq-db/src/test/java/dev/mars/peegeeq/db/consumer/` with
the commit message "production has no REFERENCE_COUNTING completion writer, so
peegeeq.completions.total could never fire". No class under any `src/main` tree
instantiates `ConsumerGroupFetcher` or `CompletionTracker`, and no `src/main` code inserts
per-group completion rows into `outbox_consumer_groups` except `BackfillService`. For a
`REFERENCE_COUNTING` topic, `OutboxConsumerGroup.start(SubscriptionOptions)` creates the
subscription row and then subscribes an `OutboxConsumer`, whose claim query is the queue
claim with the subscription's start bounds applied. The mode therefore provides the
subscription lifecycle, heartbeat, dead-group detection and cleanup, backfill, and
retention rules below, but not per-group message replication. The reason this path was
not completed is recorded in [Scalability Conclusions](#scalability-conclusions).

Bitmap tracking was designed and never implemented. The `completed_groups_bitmap` column
exists and is set to `0` by the insert trigger; no code reads or sets bits.

### Schema

Sources: `peegeeq-migrations/src/main/resources/db/migration/` (Flyway) and
`peegeeq-db/src/main/resources/db/templates/base/` (runtime schema templates). The
templates provision the V010, V012, and V015 columns. The V017 partition tables and the
V018 `rebalance_generation` column appear only in the Flyway migrations.

#### `outbox` fan-out columns (V010 §3; template `04a-core-table-outbox.sql`)

| Column | Type | Default | Purpose |
|---|---|---|---|
| `required_consumer_groups` | INT | 1 | Snapshot of active `PUB_SUB` subscriptions at insert time; 1 for `QUEUE` topics |
| `completed_consumer_groups` | INT | 0 | Count of groups that have completed the message |
| `completed_groups_bitmap` | BIGINT | 0 | Reserved; never read or set by code |

The `outbox` table also carries `message_group VARCHAR(255)` (V001, V011), which
`OFFSET_WATERMARK` uses as the partition key, and `status` with CHECK set
`('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD_LETTER')`.

Index (V017): `idx_outbox_topic_msggroup_id ON outbox(topic, message_group, id)
WHERE status IN ('PENDING', 'PROCESSING')`.

Indexes (V010): `idx_outbox_fanout_completion ON outbox(topic, status,
completed_consumer_groups, required_consumer_groups) WHERE status IN ('PENDING',
'PROCESSING')` and `idx_outbox_fanout_cleanup ON outbox(status, processed_at,
completed_consumer_groups, required_consumer_groups) WHERE status = 'COMPLETED'`.

#### `outbox_topics` (V010 §1; template `08a-consumer-table-topics.sql`)

| Column | Type | Default / constraint |
|---|---|---|
| `topic` | VARCHAR(255) | PRIMARY KEY in V010; `NOT NULL UNIQUE` with a separate `id BIGSERIAL PRIMARY KEY` in the template |
| `semantics` | VARCHAR(20) | `'QUEUE'`; CHECK `('QUEUE', 'PUB_SUB')` |
| `message_retention_hours` | INT | 24 |
| `zero_subscription_retention_hours` | INT | 24 |
| `block_writes_on_zero_subscriptions` | BOOLEAN | FALSE |
| `completion_tracking_mode` | VARCHAR(20) | `'REFERENCE_COUNTING'`; CHECK `('REFERENCE_COUNTING', 'OFFSET_WATERMARK')` |
| `created_at`, `updated_at` | TIMESTAMPTZ | `NOW()` |

#### `outbox_topic_subscriptions` (V010 §2, V012, V015, V018; template `08b-consumer-table-subscriptions.sql`)

| Column | Type | Default / constraint | Added by |
|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | V010 |
| `topic` | VARCHAR(255) | NOT NULL | V010 |
| `group_name` | VARCHAR(255) | NOT NULL | V010 |
| `subscription_status` | VARCHAR(20) | `'ACTIVE'`; CHECK `('ACTIVE', 'PAUSED', 'CANCELLED', 'DEAD')` | V010 |
| `subscribed_at`, `last_active_at` | TIMESTAMPTZ | `NOW()` | V010 |
| `start_from_message_id` | BIGINT | NULL | V010 |
| `start_from_timestamp` | TIMESTAMPTZ | NULL | V010 |
| `heartbeat_interval_seconds` | INT | 60 | V010 |
| `heartbeat_timeout_seconds` | INT | 300 | V010 |
| `last_heartbeat_at` | TIMESTAMPTZ | `NOW()` | V010 |
| `backfill_status` | VARCHAR(20) | `'NONE'`; CHECK `('NONE', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'FAILED')` | V010 |
| `backfill_checkpoint_id` | BIGINT | NULL | V010 |
| `backfill_processed_messages` | BIGINT | 0 | V010 |
| `backfill_total_messages` | BIGINT | NULL | V010 |
| `backfill_started_at`, `backfill_completed_at` | TIMESTAMPTZ | NULL | V010 |
| `durable_enabled` | BOOLEAN | TRUE | V012 |
| `consecutive_misses` | INTEGER | NOT NULL 0 | V015__Add_Flapping_Protection_Columns.sql |
| `dead_after_misses` | INTEGER | NOT NULL 3 | V015__Add_Flapping_Protection_Columns.sql |
| `rebalance_generation` | INT | NOT NULL 0 | V018 |

Constraint: `UNIQUE(topic, group_name)`. There is no foreign key to `outbox_topics`.

Indexes (V010; templates `09a`, `09b`):
`idx_topic_subscriptions_active ON outbox_topic_subscriptions(topic, subscription_status)
WHERE subscription_status = 'ACTIVE'` and
`idx_topic_subscriptions_heartbeat ON outbox_topic_subscriptions(subscription_status,
last_heartbeat_at) WHERE subscription_status = 'ACTIVE'`.

#### `outbox_consumer_groups` (V001, renamed in V010 §4, V016; template `08c-consumer-table-groups.sql`)

| Column | Type | Default / constraint |
|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY |
| `message_id` | BIGINT | NOT NULL; V001 declares `REFERENCES outbox(id) ON DELETE CASCADE` under the pre-rename name `outbox_message_id` |
| `group_name` | VARCHAR(255) | NOT NULL |
| `status` | VARCHAR | `'PENDING'`; CHECK `('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD_LETTER')` after V016 |
| `processed_at` | TIMESTAMPTZ | V001 |
| `retry_count` | INT | 0 |
| `error_message` | TEXT | NULL |

The template adds `claimed_at`, `completed_at`, `lock_id`, and `lock_until`.
Constraint: `UNIQUE(message_id, group_name)` (V010 names it
`outbox_consumer_groups_message_id_group_name_key`).
Index (V010; template `09c`): `idx_outbox_consumer_groups_group_status
ON outbox_consumer_groups(group_name, status, message_id)`.

#### Partition tables (V017)

`outbox_partition_assignments`:

| Column | Type | Default / constraint |
|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY |
| `topic`, `group_name`, `partition_key` | VARCHAR(255) | NOT NULL; `UNIQUE(topic, group_name, partition_key)` |
| `assigned_instance_id` | VARCHAR(255) | NOT NULL |
| `assigned_at`, `last_heartbeat_at` | TIMESTAMPTZ | `NOW()` |
| `generation` | INT | NOT NULL 1 |

Index: `idx_partition_assignments_instance ON outbox_partition_assignments(topic,
group_name, assigned_instance_id)`.

`outbox_partition_offsets`:

| Column | Type | Default / constraint |
|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY |
| `topic`, `group_name`, `partition_key` | VARCHAR(255) | NOT NULL; `UNIQUE(topic, group_name, partition_key)` |
| `committed_offset` | BIGINT | NOT NULL 0 |
| `committed_at` | TIMESTAMPTZ | `NOW()` |
| `pending_offset` | BIGINT | NULL |
| `pending_since` | TIMESTAMPTZ | NULL |
| `generation` | INT | NOT NULL 1 |

`outbox_topic_watermarks`:

| Column | Type | Default / constraint |
|---|---|---|
| `topic` | VARCHAR(255) | PRIMARY KEY |
| `watermark_id` | BIGINT | NOT NULL 0 |
| `watermark_updated_at` | TIMESTAMPTZ | `NOW()` |

#### Functions and trigger (V010 §7-§9)

- `set_required_consumer_groups()` (trigger function). Reads `outbox_topics.semantics`
  for `NEW.topic`, treating a missing row as `QUEUE`. For `PUB_SUB` it sets
  `NEW.required_consumer_groups` to the count of `ACTIVE` rows in
  `outbox_topic_subscriptions` for the topic; otherwise it sets 1. It sets
  `completed_consumer_groups` and `completed_groups_bitmap` to 0.
- `trigger_set_required_consumer_groups`: `BEFORE INSERT ON outbox FOR EACH ROW`.
- `cleanup_completed_outbox_messages()` returns INTEGER. Deletes up to 10,000 `COMPLETED`
  rows per call where the row is older than `COALESCE(message_retention_hours, 24)` hours
  and one of: `required_consumer_groups = 1 AND completed_consumer_groups >= 1`;
  `completed_consumer_groups >= required_consumer_groups AND required_consumer_groups > 1`;
  or `required_consumer_groups = 0 AND created_at` older than
  `COALESCE(zero_subscription_retention_hours, 24)` hours.
- `mark_dead_consumer_groups()` returns INTEGER. Sets `subscription_status = 'DEAD'` where
  status is `ACTIVE` and `last_heartbeat_at` is older than `heartbeat_timeout_seconds`.
  The Java `DeadConsumerDetector` applies the stricter `consecutive_misses >=
  dead_after_misses` rule; the SQL function does not.

V010 also creates `processed_ledger`, `partition_drop_audit`, and `consumer_group_index`
with `update_consumer_group_index()`. They are outside this contract.

### Java API

#### API layer (`peegeeq-api`)

`dev.mars.peegeeq.api.messaging.ConsumerGroup<T>`
(`peegeeq-api/src/main/java/dev/mars/peegeeq/api/messaging/ConsumerGroup.java`):

```java
String getGroupName();
String getTopic();
ConsumerGroupMember<T> addConsumer(String consumerId, MessageHandler<T> handler);
ConsumerGroupMember<T> addConsumer(String consumerId, MessageHandler<T> handler, Predicate<Message<T>> messageFilter);
boolean removeConsumer(String consumerId);
Set<String> getConsumerIds();
int getActiveConsumerCount();
Future<Void> start();
Future<Void> start(SubscriptionOptions subscriptionOptions);
Future<Void> stop();
boolean isActive();
ConsumerGroupStats getStats();
ConsumerGroupMember<T> setMessageHandler(MessageHandler<T> handler);
void setGroupFilter(Predicate<Message<T>> groupFilter);
Predicate<Message<T>> getGroupFilter();
Future<Void> close();
```

`QueueFactory.createConsumerGroup(String groupName, String topic, Class<T> payloadType)`
creates a group. There is no topic-configuration method on `QueueFactory`; topics are
configured through `TopicConfigService` in the database layer.

`dev.mars.peegeeq.api.messaging.SubscriptionOptions`
(`peegeeq-api/src/main/java/dev/mars/peegeeq/api/messaging/SubscriptionOptions.java`):

| Factory or builder field | Default |
|---|---|
| `SubscriptionOptions.defaults()` | `FROM_NOW` with the defaults below |
| `SubscriptionOptions.fromBeginning()` | `FROM_BEGINNING`, `backfillScope = PENDING_ONLY` |
| `SubscriptionOptions.fromBeginning(BackfillScope)` | `FROM_BEGINNING` with the given scope |
| `SubscriptionOptions.builder()` | |
| `startPosition(StartPosition)` | `FROM_NOW` |
| `startFromMessageId(long)` | null |
| `startFromTimestamp(Instant)` | null |
| `heartbeatIntervalSeconds(int)` | 60 |
| `heartbeatTimeoutSeconds(int)` | 300 |
| `deadAfterMisses(int)` | 3 |
| `backfillScope(BackfillScope)` | `PENDING_ONLY` |
| `durableEnabled(boolean)` | false |
| `subscriptionName(String)` | null |
| `consumerId(String)` | null |
| `replayBatchSize(int)` | 500 |

`dev.mars.peegeeq.api.messaging.StartPosition`: `FROM_NOW`, `FROM_BEGINNING`,
`FROM_MESSAGE_ID`, `FROM_TIMESTAMP`.

`dev.mars.peegeeq.api.messaging.BackfillScope`: `PENDING_ONLY`, `ALL_RETAINED`.

`dev.mars.peegeeq.api.subscription.SubscriptionService`
(`peegeeq-api/src/main/java/dev/mars/peegeeq/api/subscription/SubscriptionService.java`).
Abstract methods:

```java
Future<Void> subscribe(String topic, String groupName);
Future<Void> subscribe(String topic, String groupName, SubscriptionOptions options);
Future<Void> pause(String topic, String groupName);
Future<Void> resume(String topic, String groupName);
Future<Void> cancel(String topic, String groupName);
Future<Void> updateHeartbeat(String topic, String groupName);
Future<SubscriptionInfo> getSubscription(String topic, String groupName);
Future<List<SubscriptionInfo>> listSubscriptions(String topic);
```

Default methods, each failing with `UnsupportedOperationException` unless overridden:

```java
Future<JsonObject> startBackfill(String topic, String groupName);
Future<JsonObject> startBackfill(String topic, String groupName, BackfillScope messageScope);
Future<Void> cancelBackfill(String topic, String groupName);
Future<ForceRemoveResult> forceRemoveConsumerGroup(String topic, String groupName);
Future<List<SubscriptionInfo>> listDeadSubscriptions();
Future<JsonObject> getSubscriptionHealthSummary();
Future<JsonObject> getBlockedMessageStats();
Future<List<PartitionAssignmentInfo>> joinPartitionedGroup(String topic, String groupName, String instanceId);
Future<Void> leavePartitionedGroup(String topic, String groupName, String instanceId);
Future<List<JsonObject>> fetchPartitioned(String topic, String groupName, String partitionKey, int batchSize, int generation);
Future<Boolean> commitOffset(String topic, String groupName, String partitionKey, long offset, int generation);
Future<List<PartitionAssignmentInfo>> getPartitionAssignments(String topic, String groupName, String instanceId);
```

Supporting records in `dev.mars.peegeeq.api.subscription`: `SubscriptionInfo`,
`ForceRemoveResult`, `PartitionAssignmentInfo`.

#### Database layer (`peegeeq-db`)

`dev.mars.peegeeq.db.subscription.TopicConfig` and `TopicConfigService`
(`peegeeq-db/src/main/java/dev/mars/peegeeq/db/subscription/`). `TopicConfig.builder()`
has `topic`, `semantics(TopicSemantics)`, `messageRetentionHours`,
`zeroSubscriptionRetentionHours`, `blockWritesOnZeroSubscriptions`,
`completionTrackingMode(String)`, `createdAt`, `updatedAt`, `build()`.
`TopicSemantics` is `QUEUE` or `PUB_SUB`. `TopicConfigService` methods:

```java
Future<Void> createTopic(TopicConfig config);
Future<Void> updateTopic(TopicConfig config);
Future<TopicConfig> getTopic(String topic);
Future<List<TopicConfig>> listTopics();
Future<Void> deleteTopic(String topic);
Future<Boolean> topicExists(String topic);
```

`dev.mars.peegeeq.db.subscription.SubscriptionManager` implements `SubscriptionService`,
including every default method above. Wiring setters: `setBackfillService(BackfillService)`,
`setDeadConsumerGroupCleanup(DeadConsumerGroupCleanup)`, and
`setPartitionedConsumptionServices(PartitionAssignmentService, PartitionedFetcher,
PartitionedOffsetManager)`. `subscribe` upserts on `(topic, group_name)`; the upsert resets
`consecutive_misses` to 0 and leaves a `CANCELLED` row `CANCELLED`. `updateHeartbeat` sets
`last_heartbeat_at`, resets `consecutive_misses` to 0, and changes `DEAD` to `ACTIVE`.

`dev.mars.peegeeq.db.subscription.BackfillService`
(`peegeeq-db/src/main/java/dev/mars/peegeeq/db/subscription/BackfillService.java`):

```java
Future<BackfillResult> startBackfill(String topic, String groupName);
Future<BackfillResult> startBackfill(String topic, String groupName, BackfillScope messageScope);
Future<BackfillResult> startBackfill(String topic, String groupName, int batchSize, long maxMessages);
Future<BackfillResult> startBackfill(String topic, String groupName, int batchSize, long maxMessages, long batchDelayMs);
Future<Void> cancelBackfill(String topic, String groupName);
Future<Optional<BackfillProgress>> getBackfillProgress(String topic, String groupName);
```

Backfill is batched, checkpointed in `backfill_checkpoint_id`, resumable from
`IN_PROGRESS`, and cancellable. Inter-batch throttling is a fixed `batchDelayMs` applied
with a Vert.x timer. There is no latency-adaptive throttling.

`dev.mars.peegeeq.db.subscription.ZeroSubscriptionValidator`:
`Future<Boolean> isWriteAllowed(String topic)` and
`Future<Void> validateWriteAllowed(String topic)`, which fails with the nested
`NoActiveSubscriptionsException` when `block_writes_on_zero_subscriptions` is true and the
topic has no `ACTIVE` subscription.

`dev.mars.peegeeq.db.cleanup.DeadConsumerDetector`
(`peegeeq-db/src/main/java/dev/mars/peegeeq/db/cleanup/`): `detectDeadSubscriptions(String
topic)`, `detectAllDeadSubscriptions()`, `detectAllDeadSubscriptionsWithDetails()`,
`countDeadSubscriptions(String)`, `countEligibleForDeadDetection(String)`,
`getBlockedMessageStats()`, `getSubscriptionSummary()`. Detection increments
`consecutive_misses` for expired heartbeats and marks `DEAD` only when
`consecutive_misses >= dead_after_misses`.

`dev.mars.peegeeq.db.cleanup.DeadConsumerGroupCleanup`:
`Future<CleanupResult> cleanupDeadGroup(String topic, String groupName)` and
`Future<List<CleanupResult>> cleanupAllDeadGroups()`. Cleanup decrements
`required_consumer_groups` on messages the dead group has not completed, guarded by
`required_consumer_groups > 0`, removes orphaned tracking rows, and auto-completes
messages where `completed_consumer_groups >= required_consumer_groups`.

`dev.mars.peegeeq.db.cleanup.DeadConsumerDetectionJob`: constructed with `Vertx` and a
`DeadConsumerDetector`; `start()` schedules detection with `vertx.setPeriodic`; `stop()`
returns `Future<Void>`; `runDetectionOnce()` and `runDetectionOnceWithDetails()` run a
single cycle; `checkHealth()` returns `Future<HealthStatus>`.

`dev.mars.peegeeq.db.consumer.ConsumerGroupRetryService` and `ConsumerGroupRetryJob`:
retry exhaustion moves a tracking row to `DEAD_LETTER` (V016) and the message to
`dead_letter_queue`.

Partitioned consumption classes in `dev.mars.peegeeq.db.consumer`
(`peegeeq-db/src/main/java/dev/mars/peegeeq/db/consumer/`):

```java
// PartitionedOffsetManager
Future<PartitionOffset> initializeOffset(String topic, String groupName, String partitionKey, int generation);
Future<Boolean> commitOffset(String topic, String groupName, String partitionKey, long newOffset, int generation);
Future<Optional<PartitionOffset>> getOffset(String topic, String groupName, String partitionKey);
Future<Boolean> setPendingOffset(String topic, String groupName, String partitionKey, long pendingOffset, int generation);
Future<Optional<Integer>> bumpGeneration(String topic, String groupName, String partitionKey);

// PartitionAssignmentService
Future<List<PartitionAssignment>> joinGroup(String topic, String groupName, String instanceId);
Future<Void> leaveGroup(String topic, String groupName, String instanceId);
Future<List<PartitionAssignment>> getAssignments(String topic, String groupName, String instanceId);
Future<Void> heartbeat(String topic, String groupName, String instanceId);
Future<List<String>> discoverPartitions(String topic);

// PartitionedFetcher
Future<List<OutboxMessage>> fetch(String topic, String groupName, String partitionKey, int batchSize, int generation);

// WatermarkCalculator
Future<Long> calculateWatermark(String topic);
Future<Long> advanceWatermark(String topic, long newWatermark);
Future<Integer> sweep(String topic, long watermark);
Future<Integer> calculateAndSweep(String topic);

// WatermarkJob (vertx.setPeriodic)
void start(); void stop(); Future<Void> stopAsync(); Future<Integer> runOnce();

// PartitionedConsumerEngine<T>
Future<Void> start(MessageHandler<T> handler); Future<Void> stop(); Future<Void> close();
static Future<Boolean> isOffsetWatermarkTopic(PgConnectionManager connectionManager, String serviceId, String topic);
```

Records: `PartitionAssignment`, `PartitionOffset`, `OutboxMessage` (all in
`dev.mars.peegeeq.db.consumer`).

### Consumer Group Start Path

`OutboxConsumerGroup` (`peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxConsumerGroup.java`)
and `PgNativeConsumerGroup` (`peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeConsumerGroup.java`)
implement `ConsumerGroup<T>`.

`start()` without options subscribes the underlying consumer and starts the members. It
creates no subscription row.

`start(SubscriptionOptions)` in `OutboxConsumerGroup`:

1. Rejects a null options argument with `IllegalArgumentException`.
2. Moves state `NEW` to `STARTING`; any other state fails with `IllegalStateException`.
3. Calls `SubscriptionService.subscribe(topic, groupName, options)`.
4. Calls `PartitionedConsumerEngine.isOffsetWatermarkTopic(...)`. If the topic mode is
   `OFFSET_WATERMARK`, it starts a `PartitionedConsumerEngine` with a generated instance id
   and routes every delivered message through `distributeMessage`. Otherwise, or if mode
   detection fails, it subscribes an `OutboxConsumer` with the group name set.
5. On failure it returns the state to `NEW`.

`distributeMessage` applies the group filter, selects the active members whose filters
accept the message, and routes with
`Math.floorMod(message.getId().hashCode(), eligibleConsumers.size())`. A message the
group filter rejects fails with `RejectedMessageException`. A message that no active
member accepts fails with `MessageFilteredException`, which the underlying consumer
treats as a reset to `PENDING`; it is not counted as processed.

There is no zero-member check in `start()` and no last-member guard in `removeConsumer()`.

### Subscription Lifecycle

Statuses are the CHECK set `ACTIVE`, `PAUSED`, `CANCELLED`, `DEAD`.

| Transition | Caused by |
|---|---|
| `ACTIVE -> PAUSED` | `SubscriptionService.pause` |
| `PAUSED -> ACTIVE` | `SubscriptionService.resume` |
| `ACTIVE -> DEAD` | `DeadConsumerDetector` after `dead_after_misses` consecutive expired heartbeats, or `mark_dead_consumer_groups()` on a single expiry |
| `DEAD -> ACTIVE` | `SubscriptionService.updateHeartbeat` |
| `ACTIVE`, `PAUSED`, `DEAD` `-> CANCELLED` | `SubscriptionService.cancel` or `forceRemoveConsumerGroup` |
| `CANCELLED -> ACTIVE` | Rejected. `resume` fails with `IllegalStateException`; `subscribe` leaves the row `CANCELLED`; `forceRemoveConsumerGroup` rejects an already-cancelled row |

`CANCELLED` is terminal. A new subscription requires a new `(topic, group_name)` pair or
manual deletion of the row.

### Retention and Cleanup Defaults

| Setting | Default | Source |
|---|---|---|
| Message retention | 24 hours | `outbox_topics.message_retention_hours` |
| Zero-subscription retention | 24 hours | `outbox_topics.zero_subscription_retention_hours` |
| Block writes on zero subscriptions | off | `outbox_topics.block_writes_on_zero_subscriptions` |
| Heartbeat interval | 60 s | `outbox_topic_subscriptions.heartbeat_interval_seconds`, `SubscriptionOptions` |
| Heartbeat timeout | 300 s | `outbox_topic_subscriptions.heartbeat_timeout_seconds`, `SubscriptionOptions` |
| Dead after misses | 3 | `outbox_topic_subscriptions.dead_after_misses`, `SubscriptionOptions` |
| Cleanup batch | 10,000 rows per call | `cleanup_completed_outbox_messages()` |

Messages inserted while a `PUB_SUB` topic has zero `ACTIVE` subscriptions get
`required_consumer_groups = 0`. The cleanup function retains them for
`zero_subscription_retention_hours` (default 24) so that a late-registering group can
still be backfilled. Operators who need a hard guarantee set
`block_writes_on_zero_subscriptions = TRUE` and call
`ZeroSubscriptionValidator.validateWriteAllowed` on the producer path.

### REST Routes

Registered in `peegeeq-rest/src/main/java/dev/mars/peegeeq/rest/PeeGeeQRestServer.java`.

Management routes (`ManagementApiHandler`):

```
GET    /api/v1/management/consumer-groups
POST   /api/v1/management/consumer-groups
DELETE /api/v1/management/consumer-groups/:setupId/:queueName/:groupName
POST   /api/v1/management/consumer-groups/:setupId/:queueName/:groupName/pause
POST   /api/v1/management/consumer-groups/:setupId/:queueName/:groupName/resume
POST   /api/v1/management/consumer-groups/:setupId/:queueName/:groupName/backfill   (handler backfillConsumerGroup)
```

Subscription lifecycle routes (`SubscriptionHandler`):

```
GET    /api/v1/setups/:setupId/subscriptions/:topic
POST   /api/v1/setups/:setupId/subscriptions/:topic
GET    /api/v1/setups/:setupId/subscriptions/:topic/:groupName
POST   /api/v1/setups/:setupId/subscriptions/:topic/:groupName/pause
POST   /api/v1/setups/:setupId/subscriptions/:topic/:groupName/resume
POST   /api/v1/setups/:setupId/subscriptions/:topic/:groupName/heartbeat
DELETE /api/v1/setups/:setupId/subscriptions/:topic/:groupName
DELETE /api/v1/setups/:setupId/subscriptions/:topic/:groupName/force-remove
```

Backfill routes:

```
POST   /api/v1/setups/:setupId/subscriptions/:topic/:groupName/backfill
GET    /api/v1/setups/:setupId/subscriptions/:topic/:groupName/backfill
DELETE /api/v1/setups/:setupId/subscriptions/:topic/:groupName/backfill
```

Partitioned consumption routes (`OFFSET_WATERMARK` topics only; the partition key is a
body field, not a path segment):

```
POST   /api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions/join
DELETE /api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions/leave
GET    /api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions
POST   /api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions/fetch
POST   /api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions/commit
```

Queue-scoped consumer-group routes (`/api/v1/queues/:setupId/:queueName/consumer-groups`
and `/api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription`) manage in-process
groups and subscription options and are documented in
[docs/PEEGEEQ_REST_API_REFERENCE.md](../../docs/PEEGEEQ_REST_API_REFERENCE.md).

### Names From Earlier Revisions That Do Not Exist

Earlier revisions of this document used the following names. None exists in the
repository at the reconciliation commit. They are listed once here so that readers of
older copies can map them to the implemented names above.

| Name in earlier revisions | Implemented equivalent |
|---|---|
| `queueFactory.configureTopic(...)`, `TopicConfiguration.pubSub(...)`, `TopicConfiguration.queue(...)` | `TopicConfigService.createTopic(TopicConfig.builder()...build())` |
| `TopicConfiguration.withHeartbeatInterval`, `withPartitioning`, `withDedicatedTable` | none |
| `SubscriptionOptions.fromNow()`, `fromBeginning(boolean)`, `fromBeginning(boolean, long)`, `fromBeginningUnlimited()`, `fromTimestamp(...)` | `defaults()`, `fromBeginning()`, `fromBeginning(BackfillScope)`, `builder()` |
| `SubscriptionPosition`, `CatchUpStrategy` | `StartPosition` |
| `ConsumerGroup.pause()`, `resume()`, `cancel()` | `SubscriptionService.pause/resume/cancel` |
| Zero-member check in `start()`; last-member guard in `removeConsumer()` | none |
| `OutboxConsumer.processPubSubSemantics()`, `processQueueSemantics()`, `getTopicSemantics()` | none; the claim query is queue semantics with subscription bounds |
| `ConsumerGroupMetrics` | deleted in commit `b81be29b`; never constructed in production |
| `OutboxMessageCleanupJob`, `SubscriptionCleanupJob`, `CleanupJobMetrics`, `CleanupConfiguration`, `OutboxCleanupJob` | `cleanup_completed_outbox_messages()` SQL function; `DeadConsumerDetectionJob` |
| `ResumableBackfillJob`, `BackfillConfiguration` with adaptive p95 throttling | `BackfillService` with fixed `batchDelayMs` |
| `WatermarkCleanupJob`, partition `DROP` cleanup, `outbox_subscription_offsets`, `outbox_topic_watermarks.oldest_partition` | `WatermarkCalculator.sweep` sets `status = 'COMPLETED'` at or below the watermark; `outbox_partition_offsets` |
| `ConsumerGroupAdmin`, `ZeroSubscriptionMonitor`, `NoSubscriptionsException` | `SubscriptionService.forceRemoveConsumerGroup`, `DeadConsumerDetector`, `ZeroSubscriptionValidator.NoActiveSubscriptionsException` |
| `backfill_status DEFAULT 'NOT_STARTED'`, `backfill_error_message` | `'NONE'`; no error column |
| `consecutive_failures` | `consecutive_misses`, `dead_after_misses` |
| `outbox_consumer_group_registry`, bitmap completion SQL | none |
| `outbox_topic_subscriptions.instance_id`, `partition_count`; `idx_outbox_topic_msggroup_distinct` | none; `assigned_instance_id` lives on `outbox_partition_assignments` |
| Trigger `trg_set_required_consumer_groups`; indexes `idx_outbox_completion_tracking`, `idx_topic_subscriptions_topic`, `idx_outbox_pubsub_pending`, `idx_consumer_groups_lookup` | `trigger_set_required_consumer_groups`; `idx_outbox_fanout_completion`, `idx_topic_subscriptions_active`, `idx_outbox_fanout_cleanup`, `idx_outbox_consumer_groups_group_status` |
| Foreign key from `outbox_topic_subscriptions.topic` to `outbox_topics` | none |

---

## Design Rationale

### Design Alternatives Considered

Four approaches were evaluated for delivering each message to several independent
services (for example email, analytics, inventory, and shipping on an order-created
event).

| Option | Description | Verdict | Reason |
|---|---|---|---|
| 1. Multiple independent consumer groups | One group per subscriber on the same topic with no coordination | Rejected | `cleanup_completed_outbox_messages()` could delete a message as soon as the first group completed it; no late-joining support |
| 2. Application-level fan-out | One consumer invokes a list of handlers | Rejected | No independent tracking per handler; handlers cannot scale or fail independently |
| 3. Outbox-to-outbox replication | A consumer republishes to one outbox per subscriber | Viable, not chosen | Write amplification, extra latency, complex topology |
| 4. Hybrid queue/pub-sub | Opt-in `PUB_SUB` semantics per topic with subscription registration | Chosen | Backward compatible, dynamic group membership, late joiners supported |

### Resolved Design Gaps

Each gap below records the decision and its reason. Pseudo-code from the original
resolutions is removed; the implemented behaviour is in
[Implemented Contract](#implemented-contract).

**Gap 1: completion arithmetic when the group count changes.**
Decision: `required_consumer_groups` is a snapshot taken by the insert trigger.
Dead-group cleanup decrements it for messages the dead group never completed; backfill
increments it for messages a late joiner will process. `COMPLETED` messages are never
modified. Reason: snapshot semantics make completion idempotent and avoid a race between
subscription changes and in-flight messages.

**Gap 2: zero-member consumer groups.**
Original decision: fail fast in `start()` and refuse to remove the last member.
Implemented behaviour: neither check exists; `OutboxConsumerGroup.start()` accepts an
empty member set, and `removeConsumer()` removes any member. A message that no active
member accepts is reset to `PENDING` through `MessageFilteredException`. The fail-fast
rule is retained here only as history; the implementation treats member management as
dynamic.

**Gap 3: registration timing.**
Decision: messages inserted while a `PUB_SUB` topic has zero `ACTIVE` subscriptions get
`required_consumer_groups = 0` and are retained for `zero_subscription_retention_hours`
(default 24 hours) before cleanup. Optional producer blocking exists through
`block_writes_on_zero_subscriptions` and `ZeroSubscriptionValidator`. Reason: a 24-hour
window covers slow provisioning and weekend deployments; blocking is opt-in because
`QUEUE` topics and intermittent consumers must not be rejected.

**Gap 4: hash-based consumer selection.**
Decision: `Math.abs(hashCode())` was replaced because `Math.abs(Integer.MIN_VALUE)` is
negative. Implemented as `Math.floorMod(hashCode, size)`.

**Gap 5: subscription state machine.**
Decision: `CANCELLED` is terminal; `DEAD` is recoverable by heartbeat; `PAUSED` is
recoverable by `resume`. Reason: a cancelled group must not silently resume and pin
retention; a dead group is usually a crashed process that will restart.

**Gap 6: backfill limits.**
Decision: backfill runs in batches with a configurable maximum message count, a
persistent checkpoint, cancellation, and a fixed inter-batch delay. Reason: a single
`UPDATE` across millions of rows caused lock contention and timeouts. Adaptive throttling
on database latency was designed and not implemented; `P4_BackfillVsOLTPTest` in
`peegeeq-benchmarking` is the contention check.

**Gap 7: dead consumer detection.**
Decision: heartbeat rows with a configurable timeout, a scheduled detector that requires
`dead_after_misses` consecutive expired cycles before marking `DEAD` (flapping
protection), a cleanup that decrements `required_consumer_groups`, and heartbeat-driven
resurrection. All timestamps use database `NOW()` to avoid clock skew. Reason: without
automatic detection a crashed group blocks cleanup indefinitely; a single-miss rule marks
restarting pods dead.

**Gap 8: heartbeat write contention and insert-trigger cost.**
Decision: accepted as-is for the current scale. Heartbeats are one row update per group
per interval; the trigger performs two indexed lookups per insert. Mitigations that were
designed and not adopted: heartbeat jitter, batched `UNNEST` heartbeats, a cached
`outbox_topic_cache`, application-side `required_consumer_groups` without a trigger, and
list partitioning of `outbox_topic_subscriptions`.

### Scalability Conclusions

The original analysis modelled the `REFERENCE_COUNTING` path (per-message rows in
`outbox_consumer_groups`, `NOT EXISTS` fetch, counter update per completion) on a 32-core,
128 GB, NVMe PostgreSQL host with these assumptions: 50 consumer groups, 1,000 messages
per second, 24-hour retention, 10,000-row cleanup batches.

| Design | Sustained msg/s | Groups per topic | Storage per 1M messages | Status |
|---|---|---|---|---|
| Reference counting with `outbox_consumer_groups` rows | ~200 | ~100 | ~6 GB | Schema and services exist; no production writer |
| Reference counting plus time-partitioned tracking table | ~400 | ~300 | ~2 GB | Not implemented |
| Reference counting plus per-group Bloom filter | ~800 | ~500 (120 MB RAM each) | ~300 MB | Not implemented |
| Bitmap tracking (`completed_groups_bitmap`) | ~30,000 | 64 | ~8 MB | Column only; not implemented |
| `OFFSET_WATERMARK` | Gated at 200 msg/s total over one hour (see [Release Gates](#release-gates)); one offset update per batch per group | Unbounded (string partition keys) | No per-group rows | Implemented |

The 200 msg/s ceiling for reference counting comes from the completion write path: one
`INSERT` into `outbox_consumer_groups` plus one `UPDATE` on `outbox` per message per group,
against a tracking table that grows to billions of rows at 50 groups. Cleanup with
`ON DELETE CASCADE` over 50 tracking rows per message falls below the production rate.
These figures are modelled estimates, not measurements. The conclusion that drove the
implementation was to invest in `OFFSET_WATERMARK`, which removes per-message writes
entirely, instead of completing the reference-counting consumer path or the bitmap
optimisation.

Short performance tests for the reference-counting harness exist in
`peegeeq-benchmarking/src/test/java/dev/mars/peegeeq/db/fanout/`
(`FanoutPerformanceValidationTest`, `P2_FanoutScalingTest`, `P3_MixedTopicsTest`,
`P4_BackfillVsOLTPTest`, tag `PERFORMANCE`). They drive the test-scoped
`CompletionTracker` and `ConsumerGroupFetcher` directly; they do not exercise a production
consumer.

### Comparison With Other Systems

| Feature | Kafka | RabbitMQ | Pulsar | PeeGeeQ |
|---|---|---|---|---|
| Fan-out model | Consumer groups on a log | Exchange to bound queues | Subscriptions | Subscription rows per group on a `PUB_SUB` topic |
| Progress tracking | Committed offsets per partition | Per-message ack | Acknowledgement cursor | `OFFSET_WATERMARK`: committed offsets per partition; `REFERENCE_COUNTING`: per-message counters |
| Late joiners | Read from earliest offset | Not supported after delivery | Reader API | `StartPosition` plus `BackfillService` |
| Dead consumers | Rebalance protocol | Connection drop | Rebalance | Heartbeat rows, `DeadConsumerDetector`, generation-fenced rebalance in `OFFSET_WATERMARK` |
| Per-key ordering | Per partition | Per queue | Key_Shared | `OFFSET_WATERMARK` per `message_group` partition; none under queue semantics |
| Infrastructure | Broker cluster | Broker | Broker plus BookKeeper | PostgreSQL only |

Under queue semantics `FOR UPDATE SKIP LOCKED` lets different consumers process messages
from the same `message_group` concurrently, so order is not guaranteed. Applications that
need per-key order use an `OFFSET_WATERMARK` topic and set `messageGroup` on every send.
Patterns for choosing a key and handling ungrouped messages are in
[docs/PEEGEEQ_ORDERING_PATTERNS_GUIDE.md](../../docs/PEEGEEQ_ORDERING_PATTERNS_GUIDE.md).

PeeGeeQ advantages: ACID transactions, no extra infrastructure, standard SQL for operations
and monitoring, strong consistency. Limitations: throughput and latency are bounded by
PostgreSQL; the reference-counting fan-out path is not production-complete.

---

## Partitioned Consumption

The `OFFSET_WATERMARK` mode, including partition keys, assignment and rebalance with
generation fencing, ordered per-partition fetch, offset commit, watermark calculation and
cleanup, the lifecycle contract, trace shape, test surfaces, and release-gate evidence, is
specified in
[PEEGEEQ_PARTITIONED_CONSUMPTION_DESIGN.md](PEEGEEQ_PARTITIONED_CONSUMPTION_DESIGN.md).
That document supersedes the partitioned design section that earlier revisions of this
document carried; the superseded text is in git history. Its schema is the V017 and V018
listing in [Schema](#schema); its API is the partitioned subset of
[Java API](#java-api) and [REST Routes](#rest-routes). The archived ordering guide at
`docs-design/_archived/superseded-guides/PEEGEEQ_OUTBOX_PARTITIONED_ORDERING_COMPLETE_GUIDE.md`
records the pre-implementation ordering guidance.

---

## Release Gates

Task 6 (`docs-design/tasks/tasks.md` §6, status COMPLETE, Jenkins build #11, 2026-09-17)
executed the `OFFSET_WATERMARK` release gate with
`peegeeq-benchmarking/src/test/java/dev/mars/peegeeq/db/fanout/PartitionedConsumptionReleaseGate.java`:
a 3,600-second run at 200 messages per second total across two isolated tenant schemas,
two consumer groups per tenant, 16 initial partitions expanded live to 17 with a rebalance
at the midpoint, concurrent OLTP probes, and a full drain. Accepted results: every group
received every message, zero within-partition ordering violations, zero cross-tenant
deliveries, watermark `360017` in both schemas, OLTP probe p95 of 10 ms, and all
assignments removed on shutdown. The class name omits the `Test` suffix so the one-hour
gate is excluded from routine profiles. Build #10 qualified the harness; build #9 is
diagnostic only.

The reference-counting load matrix from earlier revisions (LT-1 to LT-4: 4, 8, 16, and 32
groups at 10,000 messages per second for one hour) has never been run. `tasks.md` has no
entry for it. The mode has no production completion writer, so there is no production
path for that matrix to measure. The `PERFORMANCE`-tagged benchmark tests listed in
[Scalability Conclusions](#scalability-conclusions) are the only reference-counting
measurements and are short, harness-driven runs.

No release gate for the fan-out feature is marked pending in this document.

---

## References

- [Partitioned consumption design and implementation record](PEEGEEQ_PARTITIONED_CONSUMPTION_DESIGN.md)
- [Consolidated task register](../tasks/tasks.md)
- [Consumer groups user guide](../../docs/PEEGEEQ_CONSUMER_GROUP_GETTING_STARTED.md)
- [Ordering patterns guide](../../docs/PEEGEEQ_ORDERING_PATTERNS_GUIDE.md)
- [Fan-out trace propagation record](../_archived/completed-records/CONSUMER_GROUP_FANOUT_TRACE_PROPAGATION.md)
- [Management UI consumer-groups implementation record](../tasks/archive/CONSUMER-GROUPS-UI-REDESIGN-PLAN.md)
- [Testing standards](../testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md)
- Migrations: `peegeeq-migrations/src/main/resources/db/migration/V001__Create_Base_Tables.sql`,
  `V010__Create_Consumer_Group_Fanout_Tables.sql`, `V011__Post_Fanout_Consolidation.sql`,
  `V012__Create_Bitemporal_Durable_Subscriptions.sql`,
  `V015__Add_Flapping_Protection_Columns.sql`,
  `V016__Add_Consumer_Group_Dead_Letter_Status.sql`,
  `V017__Create_Offset_Watermark_Tables.sql`,
  `V018__Add_Rebalance_Generation_To_Subscriptions.sql`
- Runtime templates: `peegeeq-db/src/main/resources/db/templates/base/04a-core-table-outbox.sql`,
  `08a-consumer-table-topics.sql`, `08b-consumer-table-subscriptions.sql`,
  `08c-consumer-table-groups.sql`, `09a-consumer-index-active.sql`,
  `09b-consumer-index-heartbeat.sql`, `09c-consumer-index-groupstatus.sql`

**End of Document**
