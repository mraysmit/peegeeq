# PeeGeeQ Durable Subscriptions Option Plan (Outbox + Bi-Temporal)

**Status:** Task 4 bitemporal runtime implemented and locally verified; broader outbox/operations proposals are not implemented
**Reconciled:** 2026-09-05 against `19e3cbdb` plus the Tasks 4.2–4.6 working-tree implementation

The only live execution order and verification record is [the consolidated task register](../tasks/tasks.md).
This document is a design reference, not a second task list. Its proposed runtime flows and
test inventory are targets, not claims that every behavior exists.

Migration V012 and the base-template table exist. Tasks 4.1 and 4.2 add the public contracts,
`EventStoreFactory.createBiTemporalSubscriptionService()`, and the bitemporal coordinator.
`registerDefinition(...)` persists metadata only. Reads, lifecycle changes, heartbeats, and
transactional cursor updates use the manager's tenant-local schema and pool. Recreating a
service preserves committed definitions/cursors; loading active definitions validates stored
cursor bounds but does not restore handlers or start delivery automatically.

The typed `subscribe(..., Class<T>, handler, options)` overload starts durable delivery;
the untyped overload fails explicitly because it cannot safely deserialize an erased payload type.
`catchUp(..., Class<T>, handler, batchSize)` replays one finite committed boundary without live delivery.
Successful handler completion permits a generation-fenced cursor acknowledgement. V020 adds
expiring owner leases; each finite scan renews and releases its lease, and live contenders
remain standbys while another scan owns it. Observe `deliveryCompletion(...)` for terminal
errors after startup; `close()` drains work and reports resource-cleanup failures separately.

Focused local evidence: `DurableBiTemporalReplayIntegrationTest` 15/15,
`DurableBiTemporalSubscriptionIntegrationTest` 34/34, `MigrationConventionsTest` 11/11, and
`OnSuccessExceptionSwallowingGuardTest` 8/8. These results do not claim a fresh Jenkins/full-suite gate.
Existing non-durable subscriptions remain unchanged. The historical test inventory in section 13
includes superseded ideas and unapproved extensions; it is not a list of additional current tasks.

## 1. Objective

Add a durable subscription option to:
- peegeeq-bitemporal typed durable service (implemented separately from non-durable subscriptions)
- peegeeq-native outbox flow (extend existing infrastructure)

The option must preserve current behavior by default (non-durable, in-memory), while enabling durable restart-safe consumer progress when explicitly configured.

## 2. Scope and Non-Goals

In scope:
- Optional durable mode for subscription registration and progress tracking
- Restart-safe catch-up and resume semantics
- Extend outbox infrastructure; build equivalent for bitemporal
- Backward-compatible API and configuration changes
- Migration scripts and end-to-end tests with TestContainers

Out of scope (phase 1):
- Full Kafka-style partition rebalancing protocol
- Exactly-once across arbitrary external side effects
- Cross-region active/active subscription consensus

## 3. Existing Infrastructure Inventory

Before designing new components, the following existing infrastructure must be understood and reused where applicable.

### 3.1 Outbox Subscription Tables (V010)

The outbox module already has durable subscription infrastructure:

| Table | Purpose | Key Columns |
|-------|---------|-------------|
| `outbox_topic_subscriptions` | Subscription lifecycle per (topic, group) | `subscription_status` (ACTIVE/PAUSED/CANCELLED/DEAD), `start_from_message_id`, `last_heartbeat_at`, `backfill_status`, `backfill_checkpoint_id` |
| `consumer_group_index` | Denormalized consumer group progress | `last_processed_id`, `last_processed_at`, `pending_count`, `processing_count`, `completed_count`, `failed_count` |
| `outbox_consumer_groups` | Per-message consumer group status | `status` (PENDING/PROCESSING/COMPLETED/FAILED), `retry_count`, `error_message` |
| `processed_ledger` | Audit trail | `message_id`, `group_name`, `processing_duration_ms`, `status` |
| `outbox_topics` | Topic registry | Topic metadata |

### 3.2 Existing SubscriptionService API (peegeeq-api)

`dev.mars.peegeeq.api.subscription.SubscriptionService` already provides:
- `subscribe(topic, groupName)` / `subscribe(topic, groupName, SubscriptionOptions)`
- `pause(topic, groupName)` / `resume(topic, groupName)` / `cancel(topic, groupName)`
- `updateHeartbeat(topic, groupName)`
- `getSubscription(topic, groupName)` → `SubscriptionInfo`
- `listSubscriptions(topic)` → `List<SubscriptionInfo>`
- `startBackfill(topic, groupName)` / `startBackfill(topic, groupName, BackfillScope)`
- `cancelBackfill(topic, groupName)`

Factory: `PeeGeeQManager.createSubscriptionService()`

### 3.3 Existing SubscriptionOptions (peegeeq-api)

`dev.mars.peegeeq.api.messaging.SubscriptionOptions` already has:
- `startPosition` (FROM_NOW, FROM_BEGINNING, FROM_MESSAGE_ID, FROM_TIMESTAMP)
- `startFromMessageId`, `startFromTimestamp`
- `heartbeatIntervalSeconds`, `heartbeatTimeoutSeconds`
- `backfillScope` (PENDING_ONLY, etc.)

### 3.4 Existing Non-Durable Bitemporal Subscription State

`ReactiveNotificationHandler` has:
- `SubscriptionKey` private inner class with `eventType` (nullable, supports `*` wildcards) and `aggregateId` (nullable)
- Subscription storage: `ConcurrentHashMap<SubscriptionKey, CopyOnWriteArrayList<MessageHandler>>`
- LISTEN/NOTIFY via PostgreSQL trigger `notify_bitemporal_event()` (V011)
- This non-durable path remains in-memory. The durable service owns a typed event-store listener
  and treats its notifications only as hints to reconcile committed history.

### 3.5 Bitemporal Event Table Ordering

`bitemporal_event_log` has:
- `id BIGSERIAL PRIMARY KEY` sequence-allocated identifier (the cursor column); allocation order is not transaction commit order
- `transaction_time TIMESTAMPTZ` when recorded, but can have ties across concurrent inserts
- `valid_time TIMESTAMPTZ` business time, not monotonic (can be backdated)
- `event_type`, `aggregate_id`, `correlation_id`, `causation_id`

Existing index: `idx_bitemporal_valid_time ON bitemporal_event_log(valid_time)`

### 3.6 Schema-Tenancy

The project supports per-schema table isolation (see PEEGEEQ_SCHEMA_CONFIGURATION_DESIGN.md). Infrastructure tables like `outbox_topic_subscriptions` are created per tenant schema via template scripts (e.g., `08b-consumer-table-subscriptions.sql`). Any new subscription tables must follow the same isolation model and be included in the template manifest.

## 4. Current State Summary

Bitemporal non-durable reactive subscriptions (unchanged by Task 4.2):
- Subscription keys are in memory only (ConcurrentHashMap in ReactiveNotificationHandler)
- Clients must resubscribe after process restart
- LISTEN/NOTIFY events while disconnected are not replayed
- `SubscriptionKey` is a private inner class not directly persistable without extraction

Outbox subscriptions:
- Already durable via `outbox_topic_subscriptions` with status lifecycle, heartbeats, and backfill
- Consumer progress tracked in `consumer_group_index.last_processed_id`
- `SubscriptionService` API provides full lifecycle management
- The shared cursor interface exists from Task 4.1; adapting the outbox implementation to it remains a design proposal, not a completed integration.

## 5. Target Functional Requirements

1. Durable mode is opt-in per subscription.
2. On server restart, durable subscription definitions and cursors are reloaded automatically; delivery resumes when the application re-registers the same durable subscription and handler.
3. On reconnect, consumers catch up from last acknowledged cursor before live notifications.
4. At-least-once delivery guarantee in durable mode.
5. Existing non-durable API behavior remains unchanged.
6. Multiple non-durable handlers per filter remain supported. A durable identity has one local
   handler per service; multiple services coordinate ownership rather than broadcasting a shared cursor.
7. Clear operational controls: pause/resume/reset/replay-from-cursor via a dedicated bitemporal service plus the existing outbox `SubscriptionService`.

## 6. High-Level Design

### 6.1 Strategy: Extend, Don't Replace

For the outbox module, extend the existing `outbox_topic_subscriptions` and `consumer_group_index` infrastructure rather than creating parallel tables. The outbox path already has durable subscriptions the work is to formalize cursor semantics and expose a unified coordinator interface.

For the bitemporal module, build new durable infrastructure that follows the same patterns established by the outbox tables but is scoped to bitemporal event replay.

### 6.2 Cursor Type Decisions

- **Outbox cursor**: `outbox.id` (BIGSERIAL) already stored as `consumer_group_index.last_processed_id`
- **Bitemporal cursor**: `bitemporal_event_log.id` (BIGSERIAL) provides numeric order, not commit order.
  A short READ COMMITTED SHARE-lock barrier waits for current writers before reading the maximum.
  Tests prove a delayed lower-ID commit is included. The lock is released before fetching/handling
  the backlog. The supported table is append-only, with IDs allocated inside INSERT from an
  ascending, non-cycling CACHE 1 sequence. Explicit/preallocated IDs, sequence resets, and
  deleting retained history are outside this contract. Sequence settings are checked on each scan.
  Lock acquisition is bounded to five seconds and requires PostgreSQL SHARE-lock privileges.
  See [PostgreSQL LOCK](https://www.postgresql.org/docs/current/sql-lock.html) and
  [sequence caching](https://www.postgresql.org/docs/current/sql-createsequence.html).

### 6.3 Core Pattern

1. Persist subscription definition (outbox: existing table; bitemporal: new table)
2. Persist consumer progress as a cursor pointing to `id` column
3. On startup/reconnect, recover subscriptions and replay from cursor
4. Switch to live notifications after catch-up completes

## 7. Data Model Changes

### 7.1 Bitemporal Subscription Tables (New)

These tables follow the existing naming convention (no `pgq_` prefix) and must be created per tenant schema via the template script mechanism.

**Table: `bitemporal_subscriptions`**

| Column | Type | Notes |
|--------|------|-------|
| `id` | `BIGSERIAL PRIMARY KEY` | |
| `table_name` | `VARCHAR(255) NOT NULL` | Target bitemporal table (e.g., `order_events`) scopes the subscription to a specific PgBiTemporalEventStore instance |
| `subscription_name` | `VARCHAR(255) NOT NULL` | Human-readable name for the subscription |
| `consumer_group` | `VARCHAR(255) NOT NULL` | Durable subscription identity component. Required for uniqueness and future coordination |
| `event_type` | `VARCHAR(255)` | Nullable null means all event types. Supports segment-based `*` wildcard matching using the same rules as `ReactiveNotificationHandler` |
| `aggregate_id` | `VARCHAR(255)` | Nullable null means all aggregates |
| `subscription_status` | `VARCHAR(20) DEFAULT 'ACTIVE'` | CHECK IN ('ACTIVE', 'PAUSED', 'CANCELLED', 'DEAD') matches outbox convention |
| `start_from_event_id` | `BIGINT` | Starting cursor position (bitemporal_event_log.id) |
| `last_processed_id` | `BIGINT` | Last acknowledged cursor (bitemporal_event_log.id) |
| `last_processed_at` | `TIMESTAMPTZ` | When cursor was last advanced |
| `heartbeat_interval_seconds` | `INT DEFAULT 60` | |
| `heartbeat_timeout_seconds` | `INT DEFAULT 300` | |
| `last_heartbeat_at` | `TIMESTAMPTZ DEFAULT NOW()` | |
| `lease_owner` | `UUID` | Unique token for this scan, not a reusable configured consumer name |
| `lease_until` | `TIMESTAMPTZ` | Database-clock expiry; renewal cannot revive an expired lease |
| `lease_generation` | `BIGINT NOT NULL DEFAULT 0` | Incremented on acquisition/reset/revocation; fences stale acknowledgements |
| `subscribed_at` | `TIMESTAMPTZ DEFAULT NOW()` | |
| `last_active_at` | `TIMESTAMPTZ DEFAULT NOW()` | |
| `UNIQUE` | | `(table_name, subscription_name, consumer_group)` |

Design notes:
- `event_type` and `aggregate_id` are explicit columns (not JSONB) matching SubscriptionKey's two fields. This enables direct SQL filtering during replay queries and supports indexing.
- `table_name` is required because PgBiTemporalEventStore instances target different tables.
- `consumer_group` is required and forms part of the durable business key. Lifecycle operations address subscriptions by `(table_name, subscription_name, consumer_group)`.
- Wildcard matching follows the current runtime semantics in `ReactiveNotificationHandler`: `*` matches a single dot-delimited segment, so `order.*` matches `order.created` but not `order.created.v2`.
- Status enum values match `outbox_topic_subscriptions` for consistency.
- Lease columns ship in V020 and `08f-bitemporal-subscriptions.sql`; existing definitions start unowned.

**Table: `bitemporal_subscription_delivery_log` (Phase 3)**

Bounded dedupe and audit diagnostics table. Schema deferred until Phase 3 to avoid premature design.

### 7.2 Outbox Schema Extensions

The outbox already stores `last_processed_id` in `consumer_group_index`. No new outbox tables are required. The integration work is:

1. Ensure `consumer_group_index.last_processed_id` is treated as the canonical cursor.
2. Add a `durable_enabled BOOLEAN DEFAULT TRUE` column to `outbox_topic_subscriptions` (outbox subscriptions are already effectively durable; this flag formalizes it for API parity with bitemporal).
3. No migration of existing data existing outbox subscriptions continue to work exactly as today.

### 7.3 Schema-Tenancy Integration

The base manifest includes `08f-bitemporal-subscriptions.sql`, which now contains the V020
lease fields for fresh tenant schemas. The migration path upgrades existing definitions.

### 7.4 Required Indexes

```sql
-- Bitemporal cursor replay query (the primary catch-up query)
CREATE INDEX idx_bitemporal_event_log_id_type_agg
    ON bitemporal_event_log(id, event_type, aggregate_id);

-- Subscription lookup by table and status
CREATE INDEX idx_bitemporal_subs_table_status
    ON bitemporal_subscriptions(table_name, subscription_status);
```

## 8. API and Config Changes

### 8.1 Extend Existing SubscriptionOptions

Add durable-subscription fields to the existing `SubscriptionOptions.Builder` rather than creating a separate options object:

New builder methods:
- `durableEnabled(boolean)` default false for bitemporal, default true for outbox
- `subscriptionName(String)` required when durableEnabled=true
- `consumerId(String)` remains optional application metadata; it is not the lease/fencing token
- `replayBatchSize(int)` number of records fetched per batch (default 500); runtime accepts 1–10000

Phase 1 delivery mode:
- Automatic cursor advancement only. Cursor advances after the handler completes successfully.
- Manual ack is deferred until a concrete ack token/API is introduced in a later phase.

### 8.2 Extend Existing SubscriptionService

Add bitemporal-aware methods to `SubscriptionService` or create a parallel `BiTemporalSubscriptionService` interface:

```java
// Option A: Extend SubscriptionService with module-typed methods
Future<Void> subscribeBitemporal(String tableName, String subscriptionName,
    String eventType, String aggregateId, SubscriptionOptions options);

// Option B: Separate interface (preferred cleaner separation)
public interface BiTemporalSubscriptionService {
    <T> Future<Void> subscribe(String tableName, String subscriptionName, String consumerGroup,
        String eventType, String aggregateId, Class<T> payloadType,
        MessageHandler<BiTemporalEvent<T>> handler, SubscriptionOptions options);
    Future<Void> deliveryCompletion(String tableName, String subscriptionName, String consumerGroup);
    Future<Void> pause(String tableName, String subscriptionName, String consumerGroup);
    Future<Void> resume(String tableName, String subscriptionName, String consumerGroup);
    Future<Void> cancel(String tableName, String subscriptionName, String consumerGroup);
    Future<Void> resetCursor(String tableName, String subscriptionName, String consumerGroup, long fromEventId);
}
```

Decision: **Option B** a separate `BiTemporalSubscriptionService` interface. Outbox subscriptions are keyed by `(topic, groupName)`. Bitemporal durable subscriptions are keyed by `(tableName, subscriptionName, consumerGroup)`, with `eventType` and `aggregateId` defining the delivery filter. Forcing both into one interface creates awkward unused parameters.

Factory access:
- Implemented through `EventStoreFactory.createBiTemporalSubscriptionService()` and `BiTemporalEventStoreFactory`, using a started manager's tenant schema/pool and a manager close hook.
- This supersedes direct construction from `PeeGeeQManager`: the database module cannot depend on its downstream bitemporal module without a dependency cycle. No manager/database-provider factory method is claimed as implemented.
- `registerDefinition(...)` is metadata-only. Typed subscribe waits for LISTEN readiness and
  its initial finite reconciliation; it may be a standby if another instance currently owns delivery.
  The untyped overload remains unsupported. Applications explicitly re-register handlers after restart.

### 8.3 Separate Typed Delivery

The proposed notification-handler overload was superseded. Applications obtain the durable
service from the event-store factory and supply an explicit payload class. The service uses
the existing typed event store for deserialization and LISTEN readiness. Non-durable callers
continue using `EventStore.subscribe(...)`; the durable service rejects non-durable options.

### 8.4 SubscriptionKey Extraction

The durable coordinator uses its own validated public key record
`(tableName, subscriptionName, consumerGroup)`. The existing notification-filter key is unchanged.

### 8.5 Configuration via PeeGeeQConfiguration

The proposed `DurableSubscriptionConfig` was not introduced. Delivery settings come from
`SubscriptionOptions`. Reconciliation uses a fixed one-second cadence; lease renewal/expiry
use the persisted heartbeat interval/timeout. No new global property namespace is advertised.

## 9. Runtime Flow (Durable Mode)

### 9.1 Subscribe Call (durableEnabled=true)

1. Validate inputs `subscriptionName` and `consumerGroup` are required, and `tableName` must match the store's configured table
2. UPSERT `bitemporal_subscriptions` row keyed by `(table_name, subscription_name, consumer_group)` with status=ACTIVE
3. If `startPosition == FROM_BEGINNING`, set `start_from_event_id = 0`
4. If `startPosition == FROM_NOW`, capture the initial maximum behind the writer barrier (§6.2)
5. If `startPosition == FROM_MESSAGE_ID`, set `start_from_event_id` from options
6. Register one typed local handler, establish LISTEN readiness, then begin finite replay (§9.3)
7. Re-registration preserves the existing cursor and rejects changed filters. A failed local
   registration releases its key/resources so a corrected call can retry.

### 9.2 Startup Recovery (Metadata + Cursor State)

1. Query `bitemporal_subscriptions WHERE subscription_status = 'ACTIVE' AND table_name = ?`
2. Validate cursor integrity for each row (`last_processed_id <= MAX(id)` of the configured table) and load the durable subscription definition into coordinator state
3. Handler functions are still application code and are **not** reconstructed from the database
4. On startup, the application re-calls `subscribe()` with the same `(tableName, subscriptionName, consumerGroup)` and handler. The coordinator detects the existing ACTIVE subscription and resumes from the stored cursor instead of starting fresh.

### 9.3 Catch-Up Replay

```sql
SELECT id, event_id, event_type, aggregate_id FROM <validated_tenant_and_table>
WHERE id > :last_processed_id
  AND id <= :stable_boundary
ORDER BY id ASC
LIMIT :replay_batch_size
```

Query rules:
- Apply exact/wildcard/aggregate matching after fetching the bounded ID range. Advance past
  nonmatching records so filtered subscriptions do not scan the same excluded tail forever.
- Both schema and table are validated and quoted; cursor bounds and limits are bound parameters.

Processing loop:
1. Acquire an expiring lease, capture the finite writer-barrier boundary, and fetch a batch
2. For each event that matches the subscription filter, invoke handler
3. After the handler's Future succeeds, commit the numeric ID cursor in a separate row-locked,
   generation-fenced transaction. Handler side effects are not made atomic with that commit.
4. If batch size equals `replay_batch_size`, fetch next batch
5. Stop at the fixed boundary, release the lease, and reconcile again when live work is requested

### 9.4 Catch-Up to Live Handoff Protocol

This is the critical transition that must avoid gaps or duplicates:

1. Establish LISTEN before the initial replay.
2. Use notifications only to request a reconciliation; never dispatch notification payloads directly.
3. Coalesce requests while one finite scan runs; serialize handler invocations and acknowledgements.
4. Reconcile from the committed numeric cursor again when a request arrived during the scan.
5. Reconcile every second as a fallback for lost notifications or reconnect gaps.

The original proposal to compare notification `event_id` with numeric cursor `id` was unsafe
and is not implemented: event UUIDs are not numeric cursor values, and sequence allocation
alone is not commit order. Both initial and live delivery use the same writer-barrier scan.

### 9.5 Wildcard Subscription Replay Cost

Subscriptions with wildcard event types such as `order.*` or null event type/aggregate ID will replay broader cursor ranges and apply the final filter in application code. For large backlogs this can be expensive.

Mitigations:
- Bounded batch sizes (§8.1 `replayBatchSize`)
- The composite index `(id, event_type, aggregate_id)` enables efficient range scans even with filters
- Document that wildcard subscriptions with `FROM_BEGINNING` on large tables will trigger significant I/O, especially when SQL cannot pre-filter by exact event type
- Consider adding `EXPLAIN ANALYZE` logging for replay queries in development mode

### 9.6 Failure Handling

- Handler failure: do not acknowledge that event; fail catch-up/startup or the live
  `deliveryCompletion` Future and log the terminal live error. No implicit retry/backoff or
  automatic DEAD transition is claimed. Recreate the service/handler to retry from committed progress.
- Competing finite catch-up fails explicitly while owned. Live contenders remain standbys and
  reconcile later. Expiry permits takeover; pre-dispatch checks and acknowledgement generation
  checks fence stale owners. An already-running external side effect cannot be cancelled by fencing.
- Pause/cancel quiesce the local scan before changing state. Cross-instance pause/reset/cancel
  revoke ownership; affected workers surface the resulting lease/state failure. Reset preserves
  numeric bounds and requires re-registration if it invalidates an active local worker.
- Process crash during catch-up: cursor was last committed transactionally, so replay resumes from last acknowledged position. At-least-once semantics the last batch may be partially re-delivered.
- Process crash during live mode: on restart, application re-calls `subscribe()` with same `subscriptionName`, catch-up resumes from stored cursor, handoff protocol re-executes.

## 10. Module-Specific Plan

### 10.1 Bitemporal

Implemented: public service/factory API, coordinator-local durable identity, tenant-qualified
metadata/cursors/leases, bounded replay, and `DurableBiTemporalDelivery` for typed live delivery.
The manager retains pool ownership and registers service close hooks. Handler restoration
remains application-owned; no manager-to-bitemporal dependency or notification-handler API
extension was introduced.

### 10.2 Outbox (peegeeq-native)

Changes required:
- Add `durable_enabled` column to `outbox_topic_subscriptions` (defaults to TRUE for backward compatibility)
- Formalize `consumer_group_index.last_processed_id` as the canonical cursor in documentation and in the coordinator interface
- Implement `DurableOutboxSubscriptionCoordinator` wrapping existing `OutboxConsumerGroup` and `SubscriptionService` logic
- No data migration needed existing subscriptions are already durable

### 10.3 Shared Coordinator Interface

Define a common internal interface that both modules implement:

```java
public interface DurableSubscriptionCoordinator<K> {
    Future<Void> loadActiveSubscriptionDefinitions();
    Future<Void> advanceCursor(K subscriptionKey, long cursorValue);
    Future<Long> getCurrentCursor(K subscriptionKey);
    Future<Void> pauseSubscription(K subscriptionKey);
    Future<Void> resumeSubscription(K subscriptionKey);
    Future<Void> resetCursor(K subscriptionKey, long fromPosition);
}
```

Key types:
- Bitemporal key: `(tableName, subscriptionName, consumerGroup)`
- Outbox key: `(topic, groupName)`

## 11. Delivery Semantics

Durable mode guarantee:
- At-least-once

Operational implications:
- Handlers must be idempotent
- Duplicate delivery can occur after retries/crash recovery
- Catch-up and live use one numeric cursor and lease-fenced scans; notification UUIDs are never compared to that cursor

Future enhancement:
- Add bounded dedupe key support via `bitemporal_subscription_delivery_log` for effectively-once processing per consumer (Phase 3).
- Add manual ack only after introducing an explicit ack API/token tied to a delivered event (Phase 3+).

## 12. Migration and Rollout

### 12.1 Database Migration

V012 creates subscription metadata/indexes and the outbox durability column. V020 adds owner,
expiry, and generation fields to existing bitemporal definitions. Fresh tenants use the
manifest's `08f-bitemporal-subscriptions.sql`. Apply V020 before enabling the new runtime.
Do not drop subscription state to roll back application code: stop durable consumers first
and retain committed definitions/cursors. The additive lease columns may remain dormant.

### 12.2 Code Rollout

1. Ship code with durable mode disabled by default.
2. Canary enable durable mode for selected bitemporal subscriptions using `SubscriptionOptions.builder().durableEnabled(true)`.
3. Monitor replay lag, cursor commit latency, and handler error rates.
4. Expand rollout per subscription.

### 12.3 Rollback

- Stop/close the durable service, then explicitly use the existing non-durable event-store API
  if that weaker delivery contract is acceptable. Setting false on the durable service is rejected.
- No destructive rollback needed tables can remain dormant.

## 13. Historical Proposed Test Inventory (Not a Current Task List)

The named classes/cases below are retained design ideas, not the current executable suite or
completion counts. Sections 6–10 above supersede contradictory proposals (UUID watermarks,
automatic retry/DEAD transitions, extracted keys, global configuration, and outbox adaptation).
Current evidence is recorded at the top and in the consolidated task register.

All tests follow pgq-coding-principles.md. Database-touching tests use TestContainers with real PostgreSQL. Pure logic tests are @Tag(CORE). All others are @Tag(INTEGRATION). Every functional behavior has both positive proof (it works) and negative proof (it rejects or handles the wrong case correctly).

### 13.1 SubscriptionOptions Builder Extensions

**Test class**: `SubscriptionOptionsDurableTest` (@Tag(CORE))

| # | Test Name | Type | Proof |
|---|-----------|------|-------|
| 1 | `shouldBuildWithDurableEnabledAndSubscriptionName` | Positive | Builder accepts `durableEnabled(true).subscriptionName("my-sub")` and getters return correct values |
| 2 | `shouldDefaultDurableEnabledToFalse` | Positive | `SubscriptionOptions.defaults()` returns `durableEnabled == false` |
| 3 | `shouldRejectDurableEnabledWithoutSubscriptionName` | Negative | `builder().durableEnabled(true).build()` throws `IllegalArgumentException` with message mentioning subscriptionName |
| 4 | `shouldAcceptNonDurableWithoutSubscriptionName` | Positive | `builder().durableEnabled(false).build()` succeeds subscriptionName not required when non-durable |
| 5 | `shouldRejectNullSubscriptionName` | Negative | `builder().durableEnabled(true).subscriptionName(null).build()` throws `IllegalArgumentException` |
| 6 | `shouldRejectEmptySubscriptionName` | Negative | `builder().durableEnabled(true).subscriptionName("").build()` throws `IllegalArgumentException` |
| 7 | `shouldRejectBlankSubscriptionName` | Negative | `builder().durableEnabled(true).subscriptionName("   ").build()` throws `IllegalArgumentException` |
| 8 | `shouldDefaultReplayBatchSizeTo500` | Positive | Default builder produces `replayBatchSize == 500` |
| 9 | `shouldAcceptCustomReplayBatchSize` | Positive | `builder().replayBatchSize(100)` returns 100 |
| 10 | `shouldRejectZeroReplayBatchSize` | Negative | `builder().replayBatchSize(0)` throws `IllegalArgumentException` |
| 11 | `shouldRejectNegativeReplayBatchSize` | Negative | `builder().replayBatchSize(-1)` throws `IllegalArgumentException` |
| 12 | `shouldAcceptConsumerId` | Positive | `builder().consumerId("instance-1")` returns "instance-1" |
| 13 | `shouldDefaultConsumerIdToNull` | Positive | Default builder leaves `consumerId == null` |
| 14 | `shouldIncludeDurableFieldsInEqualsAndHashCode` | Positive | Two builders with same durable fields produce equal options; different subscriptionName produces unequal |
| 15 | `shouldIncludeDurableFieldsInToString` | Positive | `toString()` contains subscriptionName and durableEnabled |

### 13.2 BiTemporal Subscription Identity

**Test class**: `BiTemporalSubscriptionIdentityTest` (@Tag(CORE))

| # | Test Name | Type | Proof |
|---|-----------|------|-------|
| 1 | `shouldRequireConsumerGroupForDurableSubscribe` | Negative | Durable subscribe without consumerGroup fails validation |
| 2 | `shouldRejectBlankConsumerGroupForDurableSubscribe` | Negative | Durable subscribe with blank consumerGroup fails validation |
| 3 | `shouldAllowSameSubscriptionNameAcrossDifferentConsumerGroups` | Positive | Same `(table_name, subscription_name)` with different `consumer_group` values is allowed |

### 13.3 SubscriptionKey Extraction

**Test class**: `SubscriptionKeyTest` (@Tag(CORE))

| # | Test Name | Type | Proof |
|---|-----------|------|-------|
| 1 | `shouldCreateWithEventTypeAndAggregateId` | Positive | `SubscriptionKey.of("OrderCreated", "order-123")` stores both fields |
| 2 | `shouldCreateAllEventsKey` | Positive | `SubscriptionKey.allEvents()` has null eventType and null aggregateId |
| 3 | `shouldCreateWithNullEventType` | Positive | `SubscriptionKey.of(null, "order-123")` succeeds matches all event types for that aggregate |
| 4 | `shouldCreateWithNullAggregateId` | Positive | `SubscriptionKey.of("OrderCreated", null)` succeeds matches all aggregates for that type |
| 5 | `shouldDetectWildcardEventType` | Positive | `SubscriptionKey.of("order.*", null).hasWildcardEventType()` returns true |
| 6 | `shouldDetectNonWildcardEventType` | Negative | `SubscriptionKey.of("order.created", null).hasWildcardEventType()` returns false |
| 7 | `shouldMatchAnyAggregateWhenAggregateIdIsNull` | Positive | Key with null aggregateId matches any incoming aggregateId |
| 8 | `shouldMatchSpecificAggregate` | Positive | Key with "order-123" matches incoming "order-123" |
| 9 | `shouldRejectNonMatchingAggregate` | Negative | Key with "order-123" does not match incoming "order-456" |
| 10 | `shouldBeEqualWhenFieldsMatch` | Positive | Two keys with same eventType and aggregateId are equal and have same hashCode |
| 11 | `shouldNotBeEqualWhenFieldsDiffer` | Negative | Keys with different eventType or aggregateId are not equal |
| 12 | `shouldProduceReadableToString` | Positive | `toString()` includes eventType and aggregateId values |

### 13.4 DurableSubscriptionConfig (PeeGeeQConfiguration)

**Test class**: `DurableSubscriptionConfigTest` (@Tag(CORE))

| # | Test Name | Type | Proof |
|---|-----------|------|-------|
| 1 | `shouldReturnDefaultReplayBatchSize500` | Positive | Default config returns 500 |
| 2 | `shouldReturnDefaultRecoveryPollInterval1000` | Positive | Default config returns 1000ms |
| 3 | `shouldReturnDefaultDurableEnabledFalse` | Positive | Default config returns false |
| 4 | `shouldAcceptCustomReplayBatchSize` | Positive | Config with override returns custom value |
| 5 | `shouldAcceptCustomRecoveryPollInterval` | Positive | Config with override returns custom value |
| 6 | `shouldRejectZeroReplayBatchSize` | Negative | Config validation rejects 0 |
| 7 | `shouldRejectNegativeRecoveryPollInterval` | Negative | Config validation rejects -1 |

### 13.5 Schema DDL Bitemporal Subscriptions Table

**Test class**: `BiTemporalSubscriptionSchemaTest` (@Tag(INTEGRATION), @Testcontainers)

| # | Test Name | Type | Proof |
|---|-----------|------|-------|
| 1 | `shouldCreateBiTemporalSubscriptionsTable` | Positive | After migration, `bitemporal_subscriptions` table exists with all expected columns |
| 2 | `shouldEnforceUniqueConstraintOnBusinessKey` | Negative | INSERT two rows with same (table_name, subscription_name, consumer_group) → unique violation |
| 3 | `shouldAllowSameSubscriptionNameForDifferentTables` | Positive | INSERT same subscription_name for different table_name values succeeds |
| 4 | `shouldRequireConsumerGroup` | Negative | INSERT with NULL consumer_group fails with not-null violation |
| 5 | `shouldAllowNullEventTypeAndAggregateId` | Positive | INSERT with NULL event_type and NULL aggregate_id succeeds (wildcard subscription) |
| 6 | `shouldEnforceStatusCheckConstraint` | Negative | INSERT with subscription_status='INVALID' → check constraint violation |
| 7 | `shouldDefaultStatusToActive` | Positive | INSERT without specifying status → row has status='ACTIVE' |
| 8 | `shouldDefaultSubscribedAtToNow` | Positive | INSERT without specifying subscribed_at → row has subscribed_at close to NOW() |
| 9 | `shouldCreateCompositeIndexOnIdTypeAgg` | Positive | Index `idx_bitemporal_event_log_id_type_agg` exists on `bitemporal_event_log` |
| 10 | `shouldCreateIndexOnTableAndStatus` | Positive | Index `idx_bitemporal_subs_table_status` exists on `bitemporal_subscriptions` |

### 13.6 Schema DDL Outbox Extensions

**Test class**: `OutboxDurableColumnSchemaTest` (@Tag(INTEGRATION), @Testcontainers)

| # | Test Name | Type | Proof |
|---|-----------|------|-------|
| 1 | `shouldAddDurableEnabledColumnToOutboxTopicSubscriptions` | Positive | After migration, `durable_enabled` column exists on `outbox_topic_subscriptions` |
| 2 | `shouldDefaultDurableEnabledToTrue` | Positive | INSERT without specifying durable_enabled → row has `durable_enabled = true` |
| 3 | `shouldAllowSettingDurableEnabledToFalse` | Positive | INSERT with `durable_enabled = false` succeeds and value is stored |
| 4 | `shouldNotBreakExistingOutboxSubscriptionInserts` | Positive | Existing INSERT statements (without durable_enabled) continue to work backward compatible |

### 13.7 Schema-Tenancy

**Test class**: `BiTemporalSubscriptionTenancyTest` (@Tag(INTEGRATION), @Testcontainers)

| # | Test Name | Type | Proof |
|---|-----------|------|-------|
| 1 | `shouldCreateBiTemporalSubscriptionsInTenantSchema` | Positive | DatabaseSetupService creates `bitemporal_subscriptions` in the configured tenant schema |
| 2 | `shouldIsolateSubscriptionsBetweenTenantSchemas` | Positive | Subscription inserted in schema_a is not visible from schema_b |
| 3 | `shouldIncludeTemplateInManifest` | Positive | The `.manifest` file includes the new `09a-bitemporal-subscriptions.sql` template |

### 13.8 Durable Subscribe Persistence

**Test class**: `DurableBiTemporalSubscribeTest` (@Tag(INTEGRATION), @Testcontainers)

| # | Test Name | Type | Proof |
|---|-----------|------|-------|
| 1 | `shouldPersistSubscriptionOnDurableSubscribe` | Positive | `subscribe(tableName, subscriptionName, consumerGroup, eventType, aggregateId, handler, durableOptions)` inserts row into `bitemporal_subscriptions` with correct table_name, consumer_group, event_type, aggregate_id, subscription_name, status=ACTIVE |
| 2 | `shouldSetCursorToMaxIdWhenStartPositionIsFromNow` | Positive | Subscribe with FROM_NOW → `start_from_event_id` equals current MAX(id) of the configured bitemporal table |
| 3 | `shouldSetCursorToZeroWhenStartPositionIsFromBeginning` | Positive | Subscribe with FROM_BEGINNING → `start_from_event_id` is 0 |
| 4 | `shouldSetCursorToSpecifiedIdWhenStartPositionIsFromMessageId` | Positive | Subscribe with FROM_MESSAGE_ID(50) → `start_from_event_id` is 50 |
| 5 | `shouldRegisterHandlerInMemoryMap` | Positive | After durable subscribe, the in-memory handler map contains the handler for the subscription key (verified by publishing a NOTIFY and seeing handler invoked) |
| 6 | `shouldRejectDurableSubscribeWithoutSubscriptionName` | Negative | `subscribe(eventType, aggId, handler, optionsWithDurableButNoName)` fails with clear error |
| 7 | `shouldUpsertOnResubscribeWithSameBusinessKey` | Positive | Call subscribe twice with same `(tableName, subscriptionName, consumerGroup)` → only one row in bitemporal_subscriptions, cursor not reset |
| 8 | `shouldResumeFromStoredCursorOnResubscribe` | Positive | Subscribe durable → process events to cursor=50 → unsubscribe → resubscribe with same business key → cursor starts at 50, not 0 |
| 9 | `shouldReactivatePausedSubscriptionOnResubscribe` | Positive | Pause a subscription → resubscribe with same business key → status changes from PAUSED back to ACTIVE |
| 10 | `shouldNotPersistWhenDurableEnabledIsFalse` | Negative | Subscribe with durableEnabled=false → no row in `bitemporal_subscriptions` (in-memory only, as today) |
| 11 | `shouldStillWorkNonDurablyWithNullOptions` | Positive | `subscribe(eventType, aggId, handler)` (existing API, no options) continues to work with no database row backward compatible |
| 12 | `shouldRejectSubscribeAfterCancelled` | Negative | Cancel a subscription → resubscribe with same business key → fails (CANCELLED is terminal) or creates a new subscription (decide and test the chosen behavior) |

### 13.9 Startup Recovery

**Test class**: `DurableBiTemporalStartupRecoveryTest` (@Tag(INTEGRATION), @Testcontainers)

| # | Test Name | Type | Proof |
|---|-----------|------|-------|
| 1 | `shouldLoadActiveSubscriptionDefinitionsOnStartup` | Positive | Insert ACTIVE subscription rows directly → call `loadActiveSubscriptionDefinitions()` → coordinator reports them as loaded |
| 2 | `shouldNotLoadPausedSubscriptionsOnStartup` | Negative | Insert PAUSED subscription row → `loadActiveSubscriptionDefinitions()` → subscription is not loaded as resumable metadata |
| 3 | `shouldNotLoadCancelledSubscriptionsOnStartup` | Negative | Insert CANCELLED subscription row → `loadActiveSubscriptionDefinitions()` → subscription is not loaded |
| 4 | `shouldNotLoadDeadSubscriptionsOnStartup` | Negative | Insert DEAD subscription row → `loadActiveSubscriptionDefinitions()` → subscription is not loaded |
| 5 | `shouldFilterByTableNameOnStartup` | Positive | Insert ACTIVE subscriptions for tables A and B → coordinator for table A loads only A's definitions |
| 6 | `shouldHandleEmptySubscriptionTableOnStartup` | Positive | Empty table → `loadActiveSubscriptionDefinitions()` completes successfully with no subscriptions loaded |
| 7 | `shouldResumeFromStoredCursorOnStartupResubscribe` | Positive | Pre-existing subscription with `last_processed_id=100` → application calls subscribe with same business key → catch-up starts from id > 100 |

### 13.10 Catch-Up Replay

**Test class**: `DurableBiTemporalCatchUpReplayTest` (@Tag(INTEGRATION), @Testcontainers)

| # | Test Name | Type | Proof |
|---|-----------|------|-------|
| 1 | `shouldReplayEventsFromCursorInIdOrder` | Positive | Insert 10 events → subscribe durable with FROM_BEGINNING → handler receives all 10 events in ascending id order |
| 2 | `shouldReplayOnlyEventsAfterCursor` | Positive | Insert 10 events → subscribe with `start_from_event_id=5` → handler receives only events with id > 5 |
| 3 | `shouldReplayInBoundedBatches` | Positive | Insert 20 events → subscribe with `replayBatchSize=5` → events still all delivered, but verify multiple batches were executed (via cursor advancement pattern or metrics) |
| 4 | `shouldAdvanceCursorAfterEachSuccessfulHandlerCompletion` | Positive | Insert 5 events → subscribe durable → after completing, `last_processed_id` in DB equals id of last event |
| 5 | `shouldApplySegmentWildcardMatchingDuringReplay` | Positive | Insert `order.created`, `order.updated`, `payment.received` → subscribe with `eventType="order.*"` → handler receives only the two `order.*` events |
| 6 | `shouldFilterReplayByEventType` | Positive | Insert events of types A, B, C → subscribe for type B only → handler receives only type B events |
| 7 | `shouldFilterReplayByAggregateId` | Positive | Insert events with agg-1, agg-2 → subscribe for agg-1 → handler receives only agg-1 events |
| 8 | `shouldReplayAllEventsWhenEventTypeIsNull` | Positive | Subscribe with null eventType → handler receives all event types past cursor |
| 9 | `shouldReplayAllEventsWhenAggregateIdIsNull` | Positive | Subscribe with null aggregateId → handler receives events for all aggregates past cursor |
| 10 | `shouldReplayNoEventsWhenCursorIsAtHead` | Positive | Insert 5 events → subscribe with FROM_NOW (cursor at MAX(id)) → handler receives zero replay events, transitions directly to live |
| 11 | `shouldReplayNoEventsWhenTableIsEmpty` | Positive | Empty bitemporal_event_log → subscribe durable with FROM_BEGINNING → handler receives no events, transitions to live |
| 12 | `shouldNotReplayEventsForDifferentTable` | Negative | Events in table_a, subscription for table_b → replay returns no events for table_b |
| 13 | `shouldReplayWildcardEventTypeMatchingAllTypes` | Positive | Insert events of types `order.created`, `order.updated`, `payment.received` → subscribe with eventType="order.*" → handler receives `order.created` and `order.updated` only |
| 14 | `shouldHandleLargeBacklogReplayWithMultipleBatches` | Positive | Insert 2000 events → subscribe with replayBatchSize=500 → all 2000 events delivered, cursor at last event |

### 13.11 Catch-Up to Live Handoff

**Test class**: `DurableBiTemporalHandoffTest` (@Tag(INTEGRATION), @Testcontainers)

| # | Test Name | Type | Proof |
|---|-----------|------|-------|
| 1 | `shouldTransitionToLiveAfterCatchUpComplete` | Positive | Subscribe durable → catch-up completes → insert new event → handler receives it via NOTIFY (live mode) |
| 2 | `shouldNotDuplicateEventsDeliveredDuringCatchUp` | Negative | Insert events before subscribe → subscribe durable → events delivered during catch-up are NOT re-delivered when NOTIFY fires for same event_id |
| 3 | `shouldNotMissEventInsertedDuringCatchUp` | Positive | Subscribe durable (catch-up running) → insert new event while catch-up is in progress → event is delivered exactly once (either via catch-up or via live, not lost) |
| 4 | `shouldSkipNotifyEventsWithIdBelowHighWaterMark` | Positive | After catch-up reaches high_water_id=50 → NOTIFY for event_id=45 → handler NOT invoked again for that event |
| 5 | `shouldProcessNotifyEventsWithIdAboveHighWaterMark` | Positive | After catch-up reaches high_water_id=50 → NOTIFY for event_id=51 → handler invoked, cursor advanced to 51 |
| 6 | `shouldAdvanceCursorInLiveMode` | Positive | In live mode, each processed NOTIFY event advances `last_processed_id` in the database |
| 7 | `shouldHandleRapidEventInsertionDuringHandoff` | Positive | Insert 100 events rapidly during the catch-up → live transition window → all events delivered, none lost, none duplicated |

### 13.12 Failure Handling

**Test class**: `DurableBiTemporalFailureHandlingTest` (@Tag(INTEGRATION), @Testcontainers)

| # | Test Name | Type | Proof |
|---|-----------|------|-------|
| 1 | `shouldNotAdvanceCursorWhenHandlerThrows` | Positive | Handler throws RuntimeException → `last_processed_id` unchanged in DB |
| 2 | `shouldRetryAfterHandlerFailure` | Positive | Handler throws on first call, succeeds on second → event is delivered, cursor advances |
| 3 | `shouldApplyExponentialBackoffOnRetry` | Positive | Handler fails multiple times → verify increasing delay between retries (via timestamps or metrics) |
| 4 | `shouldMarkSubscriptionDeadAfterMaxRetries` | Positive | Handler throws on every attempt → after max retries, subscription_status changes to DEAD in DB |
| 5 | `shouldNotReplayToDeadSubscription` | Negative | Subscription marked DEAD → no further events delivered to its handler |
| 6 | `shouldResumeFromCursorAfterProcessCrash` | Positive | Subscribe durable → process 5 of 10 events → simulate crash (destroy coordinator) → recreate and resubscribe → remaining 5 events delivered starting from cursor |
| 7 | `shouldRedeliverPartialBatchAfterCrash` | Positive | Process 3 events of a batch of 5 (cursor committed after event 3) → crash → resume → events 4 and 5 re-delivered (at-least-once) |
| 8 | `shouldNotAdvanceCursorBeyondActualProcessedEvent` | Negative | During batch processing, if event 3 of 5 fails, cursor does NOT jump to event 5 stays at event 2 (last successfully processed) |
| 9 | `shouldLogHandlerFailureWithException` | Positive | Handler throws → log output contains exception message and subscription_name |
| 10 | `shouldLogSubscriptionMarkedDead` | Positive | Subscription transitions to DEAD → log output contains ERROR level entry with subscription_name |

### 13.13 Subscription Lifecycle Operations

**Test class**: `DurableBiTemporalLifecycleTest` (@Tag(INTEGRATION), @Testcontainers)

| # | Test Name | Type | Proof |
|---|-----------|------|-------|
| 1 | `shouldPauseActiveSubscription` | Positive | Pause ACTIVE subscription → status changes to PAUSED in DB |
| 2 | `shouldStopDeliveryWhenPaused` | Positive | Pause subscription → insert new event → handler NOT invoked |
| 3 | `shouldResumeFromCursorAfterPause` | Positive | Process events to cursor=20 → pause → insert more events → resume → catch-up replays from id > 20 |
| 4 | `shouldRejectPauseOnNonExistentSubscription` | Negative | Pause with non-existent subscription_name → Future fails with appropriate error |
| 5 | `shouldRejectPauseOnAlreadyPausedSubscription` | Negative | Pause already-PAUSED subscription → Future fails or is idempotent (decide and test chosen behavior) |
| 6 | `shouldResumeOnlyPausedSubscription` | Negative | Resume ACTIVE subscription → Future fails or is idempotent (cannot resume what is already running) |
| 7 | `shouldCancelSubscription` | Positive | Cancel subscription → status changes to CANCELLED in DB, handler removed |
| 8 | `shouldNotDeliverEventsAfterCancel` | Negative | Cancel subscription → insert event → handler NOT invoked |
| 9 | `shouldRejectResumeOnCancelledSubscription` | Negative | Cancel then resume → Future fails (CANCELLED is terminal) |
| 10 | `shouldResetCursorToSpecifiedPosition` | Positive | Subscription at cursor=100 → `resetCursor(subscriptionName, 50)` → cursor in DB is 50 → catch-up replays from id > 50 |
| 11 | `shouldRejectResetCursorToNegativeValue` | Negative | `resetCursor(subscriptionName, -1)` → Future fails with validation error |
| 12 | `shouldRejectResetCursorBeyondMaxId` | Negative | `resetCursor(subscriptionName, MAX(id)+1000)` → Future fails or is clamped (decide and test) |
| 13 | `shouldRejectOperationsOnNonExistentSubscription` | Negative | `cancel("does-not-exist")` → Future fails with clear error message |

### 13.14 DurableSubscriptionCoordinator Interface

**Test class**: `DurableBiTemporalCoordinatorTest` (@Tag(INTEGRATION), @Testcontainers)

| # | Test Name | Type | Proof |
|---|-----------|------|-------|
| 1 | `shouldLoadActiveSubscriptionDefinitionsOnStartup` | Positive | Pre-populate ACTIVE subscriptions → `loadActiveSubscriptionDefinitions()` completes successfully |
| 2 | `shouldAdvanceCursorTransactionally` | Positive | `advanceCursor(subscriptionKey, 42)` → DB row has `last_processed_id=42`, `last_processed_at` updated |
| 3 | `shouldGetCurrentCursorValue` | Positive | Set cursor to 42 → `getCurrentCursor(subscriptionKey)` returns 42 |
| 4 | `shouldReturnZeroCursorForNewSubscription` | Positive | New subscription with FROM_BEGINNING → `getCurrentCursor(subscriptionKey)` returns 0 |
| 5 | `shouldRejectAdvanceCursorBackward` | Negative | Current cursor=50 → `advanceCursor(subscriptionKey, 30)` → fails (cursor must be monotonically increasing) |
| 6 | `shouldRejectAdvanceCursorForNonExistentSubscription` | Negative | `advanceCursor(missingSubscriptionKey, 42)` → Future fails |
| 7 | `shouldGetCurrentCursorForNonExistentSubscription` | Negative | `getCurrentCursor(missingSubscriptionKey)` → Future fails with clear error |

### 13.15 Multi-Subscriber Behavior

**Test class**: `DurableBiTemporalMultiSubscriberTest` (@Tag(INTEGRATION), @Testcontainers)

| # | Test Name | Type | Proof |
|---|-----------|------|-------|
| 1 | `shouldSupportMultipleSubscribersForSameEventTypeWithDifferentNames` | Positive | Subscribe "sub-A" and "sub-B" both for eventType="OrderCreated" → both handlers receive the event |
| 2 | `shouldTrackIndependentCursorsPerSubscriptionName` | Positive | sub-A processes to cursor=50, sub-B processes to cursor=30 → DB shows different `last_processed_id` per row |
| 3 | `shouldAllowDifferentStartPositionsPerSubscriber` | Positive | sub-A starts FROM_BEGINNING, sub-B starts FROM_NOW → sub-A replays full history, sub-B receives only new events |
| 4 | `shouldNotInterfereWhenOneSubscriberPauses` | Positive | Pause sub-A → sub-B continues receiving events |
| 5 | `shouldSupportMixOfDurableAndNonDurableForSameKey` | Positive | sub-A is durable, a second handler is non-durable (no options) → both receive events, only sub-A has a DB row |
| 6 | `shouldRejectDuplicateSubscriptionNameForSameTable` | Negative | Subscribe "sub-A" twice for same table → second call is upsert (no duplicate row), not an error, but cursor is not reset |

### 13.16 Backward Compatibility

**Test class**: `DurableBiTemporalBackwardCompatibilityTest` (@Tag(INTEGRATION), @Testcontainers)

| # | Test Name | Type | Proof |
|---|-----------|------|-------|
| 1 | `shouldPreserveExistingSubscribeApiWithoutOptions` | Positive | `subscribe(eventType, aggId, handler)` works exactly as before no DB row, in-memory only |
| 2 | `shouldPreserveExistingNonDurableDelivery` | Positive | Non-durable subscriber receives NOTIFY events as before |
| 3 | `shouldNotRequireBiTemporalSubscriptionsTableForNonDurableUse` | Positive | If migration hasn't run, non-durable subscribe still works (no table access) |
| 4 | `shouldPreserveMultiHandlerPerKeyBehavior` | Positive | Multiple non-durable handlers for same key all receive events (CopyOnWriteArrayList behavior preserved) |
| 5 | `shouldNotAffectExistingReactiveNotificationHandlerStartStop` | Positive | `start()` and `stop()` lifecycle unaffected by durable subscription feature |

### 13.17 Outbox Coordinator Integration

**Test class**: `DurableOutboxCoordinatorTest` (@Tag(INTEGRATION), @Testcontainers)

| # | Test Name | Type | Proof |
|---|-----------|------|-------|
| 1 | `shouldWrapExistingConsumerGroupIndexAsCanonicalCursor` | Positive | `getCurrentCursor(groupName)` returns `consumer_group_index.last_processed_id` value |
| 2 | `shouldAdvanceOutboxCursorViaCoordinator` | Positive | `advanceCursor(groupName, 42)` updates `consumer_group_index.last_processed_id` to 42 |
| 3 | `shouldNotBreakExistingOutboxSubscriptionService` | Positive | Existing `SubscriptionService.subscribe(topic, group)` continues to work with coordinator in place |
| 4 | `shouldPauseOutboxSubscriptionViaCoordinator` | Positive | `pauseSubscription(groupName)` updates `outbox_topic_subscriptions.subscription_status` to PAUSED |
| 5 | `shouldResumeOutboxSubscriptionViaCoordinator` | Positive | `resumeSubscription(groupName)` updates status back to ACTIVE |
| 6 | `shouldResetOutboxCursorViaCoordinator` | Positive | `resetCursor(groupName, 0)` sets `last_processed_id` to 0 in `consumer_group_index` |
| 7 | `shouldNotBreakExistingOutboxTests` | Positive | All existing OutboxConsumerGroup and SubscriptionService tests pass unchanged |
| 8 | `shouldReadDurableEnabledFlag` | Positive | Query `outbox_topic_subscriptions.durable_enabled` → returns TRUE for existing subscriptions (default) |

### 13.18 Cursor Integrity

**Test class**: `DurableBiTemporalCursorIntegrityTest` (@Tag(INTEGRATION), @Testcontainers)

| # | Test Name | Type | Proof |
|---|-----------|------|-------|
| 1 | `shouldPersistCursorTransactionally` | Positive | Advance cursor within a transaction → cursor visible after commit |
| 2 | `shouldNotPersistCursorOnRollback` | Negative | Advance cursor → force rollback → cursor unchanged |
| 3 | `shouldEnforceMonotonicCursorInvariant` | Negative | Attempt to set `last_processed_id` to a value less than current → rejected |
| 4 | `shouldDetectCursorCorruptionOnStartup` | Negative | Manually set `last_processed_id` > MAX(id) of bitemporal_event_log → startup integrity check detects and reports error |
| 5 | `shouldUpdateLastProcessedAtOnCursorAdvance` | Positive | Advance cursor → `last_processed_at` timestamp in DB updated to approximately NOW() |
| 6 | `shouldHandleConcurrentCursorUpdates` | Positive | Two threads both try to advance cursor → no data corruption, final value is the higher of the two |

### 13.19 Migration Safety

**Test class**: `DurableSubscriptionMigrationTest` (@Tag(INTEGRATION), @Testcontainers)

| # | Test Name | Type | Proof |
|---|-----------|------|-------|
| 1 | `shouldApplyMigrationWithoutErrors` | Positive | V012 migration runs cleanly on a database that has V010 and V011 already applied |
| 2 | `shouldBeAdditiveOnly` | Positive | No existing columns or tables are modified or dropped by V012 |
| 3 | `shouldBeIdempotentForColumnAddition` | Positive | `ADD COLUMN IF NOT EXISTS durable_enabled` runs successfully even if column already exists |
| 4 | `shouldRollbackCleanly` | Positive | Drop `bitemporal_subscriptions` table and `durable_enabled` column → system returns to pre-migration state |
| 5 | `shouldNotBreakExistingQueriesAgainstOutboxTopicSubscriptions` | Positive | Existing queries against `outbox_topic_subscriptions` (without selecting `durable_enabled`) still work |

### 13.20 End-to-End Scenarios

**Test class**: `DurableBiTemporalEndToEndTest` (@Tag(INTEGRATION), @Testcontainers)

| # | Test Name | Type | Proof |
|---|-----------|------|-------|
| 1 | `shouldSurviveFullRestartCycle` | Positive | Subscribe durable → insert 5 events → handler processes them → stop coordinator → insert 5 more events → start new coordinator → resubscribe with same name → handler receives the 5 missed events |
| 2 | `shouldHandleLargeBacklogReplayOnRestart` | Positive | Subscribe durable → stop immediately → insert 2000 events → restart → resubscribe → all 2000 events delivered in order |
| 3 | `shouldSupportConcurrentSubscribersWithIndependentProgress` | Positive | sub-A and sub-B both durable for same event type → sub-A is slower → sub-B's cursor advances independently |
| 4 | `shouldTransitionFromCatchUpToLiveSeamlessly` | Positive | Insert 50 events → subscribe durable FROM_BEGINNING → while catch-up runs, insert 10 more → handler receives all 60, no gaps, no duplicates |
| 5 | `shouldRecoverAfterHandlerFailuresAndContinue` | Positive | Handler fails on event 3 → retries → succeeds on retry → continues processing events 4, 5, 6... → cursor at end reflects all events processed |
| 6 | `shouldPauseResumeAndContinueFromCorrectPosition` | Positive | Process to cursor=30 → pause → insert 20 more events → resume → catch-up replays 20 events from cursor=30 |
| 7 | `shouldResetCursorAndReplayFromNewPosition` | Positive | Process to cursor=100 → resetCursor to 50 → catch-up replays events from id > 50 |
| 8 | `shouldDeliverAtLeastOnceUnderCrashConditions` | Positive | Process events → crash mid-batch → restart → verify all events delivered at least once (some may be duplicated, none lost) |
| 9 | `shouldNotDeliverEventsToNonDurableSubscriberAfterRestart` | Negative | Non-durable subscriber does NOT receive missed events after restart only durable subscribers catch up |
| 10 | `shouldMaintainEventOrderAcrossCatchUpAndLive` | Positive | All events received by handler are in strictly ascending id order, even across the catch-up → live boundary |

## 14. Proposed Observability Extensions (Not Shipped by Task 4)

### 14.1 Metrics

- `bitemporal_subscription_replay_lag` difference between `MAX(id)` and `last_processed_id`
- `bitemporal_subscription_cursor_commit_latency` time to persist cursor advancement
- `bitemporal_subscription_replay_batch_duration` time per catch-up batch
- `bitemporal_subscription_handler_failures_total` handler exception count
- `bitemporal_subscription_handoff_count` catch-up to live transitions

### 14.2 Logs

- Subscription registration/upsert (INFO)
- Cursor commit with event_id (DEBUG)
- Replay start (subscription_name, from_cursor, table_name) (INFO)
- Replay complete + handoff point event_id (INFO)
- Handler failure with exception (WARN)
- Subscription marked DEAD after max retries (ERROR)

## 15. Risks and Mitigations

1. **Risk**: Replay query load spikes on large tables with wildcard subscriptions
   - Mitigation: bounded batch sizes, composite index `(id, event_type, aggregate_id)`, document cost of `FROM_BEGINNING` on large tables

2. **Risk**: Cursor corruption or out-of-order replay
   - Mitigation: writer-barrier boundaries plus sequence constraints, bounded numeric validation,
     row-locked cursor updates, and lease-generation fencing (§6.2 and §9)

3. **Risk**: Handler side effects duplicated
   - Mitigation: at-least-once semantics documented, handlers must be idempotent, optional dedupe log in Phase 3

4. **Risk**: Gap during catch-up to live handoff
   - Mitigation: LISTEN readiness, coalesced ordered scans, and periodic reconciliation (§9.4)

5. **Risk**: Schema-tenancy not supported for new tables
   - Mitigation: new tables included in template scripts from Phase 0, tested with multi-schema setup

6. **Risk**: SubscriptionKey extraction breaks encapsulation
   - Mitigation: package-private visibility only, no public API exposure, same package as ReactiveNotificationHandler

## 16. Phased Implementation Plan

Historical design decomposition only. Use Task 4 in the consolidated register for current
phase boundaries, completion status, and approved execution order.

### Phase 0: Schema and Interfaces
- Create `V012` migration: `bitemporal_subscriptions` table + indexes
- Add template script `09a-bitemporal-subscriptions.sql` + manifest update
- Add `durable_enabled` column to `outbox_topic_subscriptions`
- Extract `SubscriptionKey` to package-visible class
- Define `BiTemporalSubscriptionService` interface in peegeeq-api
- Expose `createBiTemporalSubscriptionService()` through the event-store factory (implemented in Task 4.2; see section 8.2)
- Define `DurableSubscriptionCoordinator` interface
- Add `durableEnabled`, `subscriptionName`, and `replayBatchSize` to `SubscriptionOptions.Builder`
- Add durable config properties to `PeeGeeQConfiguration`

### Phase 1: Bitemporal Durable MVP
- Implement `DurableBiTemporalSubscriptionCoordinator`
- Add `subscribe(eventType, aggregateId, handler, options)` overload to ReactiveNotificationHandler
- Implement catch-up replay query path
- Implement catch-up → live handoff protocol (§9.4)
- Implement cursor persistence after successful handler completion
- Integration tests: persist, resume, replay, handoff, failure

### Phase 2: Outbox Integration
- Implement `DurableOutboxSubscriptionCoordinator` wrapping existing OutboxConsumerGroup
- Formalize `consumer_group_index.last_processed_id` as cursor
- Ensure SubscriptionService operations route through coordinator when durable
- Integration tests: existing outbox tests must not regress

### Phase 3: Ops Hardening
- Add `lease_owner`, `lease_until` columns to `bitemporal_subscriptions` via ALTER TABLE
- Implement lease coordination for multi-instance takeover
- Add `bitemporal_subscription_delivery_log` for dedupe
- Introduce explicit manual-ack API/token if operationally justified
- Add pause/resume/reset/replay-from-cursor REST endpoints
- Metrics, dashboards, alert thresholds

### Phase 4: Scale and Resilience
- Performance tuning and `EXPLAIN ANALYZE` for replay queries
- Chaos tests (kill during catch-up, kill during handoff, kill during live)
- Large backlog replay validation (millions of events)
- Adaptive backoff tuning

## 17. Historical Broader Proposal Acceptance Criteria

These include outbox and full-suite work beyond the completed bitemporal Task 4 scope; they
are not all claimed as satisfied by focused local verification.

1. Non-durable default path remains backward-compatible all existing tests pass unchanged.
2. Durable bitemporal subscription survives simulated restart without client data loss once the application re-registers the same durable subscription and handler.
3. Replay from persisted cursor delivers all events in `id` order with no gaps.
4. Catch-up to live handoff produces no duplicates and no missed events.
5. Outbox coordinator wraps existing infrastructure without data migration.
6. Schema-tenancy: new tables created correctly in tenant schemas.
7. Full integration suite passes with TestContainers.
8. No Mockito, no reflection, no in-memory databases in any test.

## 18. Immediate Next Steps

Task 4 is locally verified. Follow [the consolidated register](../tasks/tasks.md) for CI reporting
verification and any subsequently approved work.
Do not treat the broader proposed phases above as authorization to implement extra features.

## Appendix A: Decisions Log

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Bitemporal cursor is `bitemporal_event_log.id` (BIGSERIAL) | Numeric ordering only; delayed lower-ID commits need an explicit replay safety contract. |
| D2 | No `pgq_` table prefix | Existing tables use unprefixed names. Follow convention. |
| D3 | Explicit `event_type` + `aggregate_id` columns, not JSONB filter | Matches SubscriptionKey's two fields. Enables SQL filtering and indexing. |
| D4 | `table_name` column in `bitemporal_subscriptions` | PgBiTemporalEventStore instances target different tables. |
| D5 | Separate `BiTemporalSubscriptionService` interface | Outbox keyed by (topic, group). Bitemporal keyed by (table, subscription, eventType, aggregateId). Different shapes. |
| D6 | Lease columns shipped in Task 4.5 / V020 | Expiry and generation fencing protect concurrent replay acknowledgements. |
| D7 | Extend `SubscriptionOptions` not new options class | Reuse existing builder pattern and StartPosition enum. |
| D8 | Task 4.2 uses a public coordinator-local durable key record | Stable `(tableName, subscriptionName, consumerGroup)` identity; the existing private notification-filter key is not extracted or reused. |
| D9 | LISTEN active before catch-up starts | Ensures no NOTIFY gap during handoff. See §9.4. |
| D10 | Configuration via PeeGeeQConfiguration, not system properties | Follow existing config pattern. |
| D11 | Phase 1 uses automatic cursor advancement only | Manual ack needs an explicit ack API/token and is deferred. |
| D12 | Wildcard replay uses current segment-based `ReactiveNotificationHandler` semantics | Prevent durable replay and live delivery from diverging. |
| D13 | Public bitemporal subscription factory shipped in Task 4.2 | EventStoreFactory is the supported entry point; avoid a database-to-bitemporal dependency cycle. |
| D14 | Typed subscribe/catch-up require `Class<T>` | Reuse event-store deserialization without unsafe erased generic casts. |
| D15 | Short writer barrier and CACHE 1 sequence contract | Include delayed lower-ID commits before advancing a numeric cursor. |
| D16 | Notifications are hints, not direct durable deliveries | One ordered cursor path plus periodic reconciliation covers handoff/reconnect gaps. |
| D17 | Terminal delivery errors and resource cleanup are separate Futures | Failure remains observable while resources can close cleanly before retry. |
