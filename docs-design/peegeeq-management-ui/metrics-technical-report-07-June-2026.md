# PeeGeeQ Metrics Technical Report

**Project:** peegeeq  
**Date:** 2026-06-07  
**Authors:** Claude Code analysis (8-agent workflow scan)  
**Scope:** Native queue · Outbox queue · BiTemporal event store — metrics inventory, implementation quality, gaps and recommendations

---

## Table of Contents

1. [Current Metrics Inventory](#1-current-metrics-inventory)
   - [1.1 Native Queue](#11-native-queue-pgnativequeuefactory--pgnativequeue)
   - [1.2 Outbox Queue](#12-outbox-queue-outboxfactory--outboxqueue)
   - [1.3 BiTemporal Event Store](#13-bitemporal-event-store-pgbitemporaleventstore)
2. [Implementation Quality Assessment](#2-implementation-quality-assessment)
   - [2.1 Native Queue](#21-native-queue)
   - [2.2 Outbox Queue](#22-outbox-queue)
   - [2.3 BiTemporal Event Store](#23-bitemporal-event-store)
3. [Recommended Metric Additions (Prioritised)](#3-recommended-metric-additions-prioritised)
   - [3.1 HIGH — Broken or Misleading, Fix Now](#31-high--broken-or-misleading-fix-now)
   - [3.2 MEDIUM — Genuinely Useful, Data Already in DB](#32-medium--genuinely-useful-data-already-in-db)
   - [3.3 LOW — Nice to Have, Requires New Queries or Schema Changes](#33-low--nice-to-have-requires-new-queries-or-schema-changes)
4. [Consistency Gaps Between Queue Types](#4-consistency-gaps-between-queue-types)
5. [Quick Wins](#5-quick-wins)

---

## 1. Current Metrics Inventory

### 1.1 Native Queue (`PgNativeQueueFactory` / `PgNativeQueue`)

REST endpoint: `ManagementApiHandler` — queue detail and queue list handlers.

| API Field | Source Method | Source Query / Computation | Status |
|---|---|---|---|
| `messagesPerSecond` | `getRealMessageRate()` → `getStats().getMessagesPerSecond()` | `total / (MAX(created_at) − MIN(created_at)).seconds` over all-time rows | ⚠️ Misleading — lifetime average, not rolling rate |
| `avgProcessingTimeMs` | `getRealAvgProcessingTime()` → `getStats().getAvgProcessingTimeMs()` | Hardcoded `0.0` in `PgNativeQueueFactory.getStats()` | ❌ Always 0 |
| `messageCount` | `countMessages()` | `COUNT(*) FROM queue_messages WHERE topic = $1` | ✅ Yes |
| `consumerCount` | `getRealConsumerCount()` → `SubscriptionService.listSubscriptions()` | Counts `SubscriptionState.ACTIVE` subscriptions | ✅ Yes |
| `pendingMessages` | `QueueStats.getPendingMessages()` from `getStats()` | `COUNT(*) FILTER (WHERE status = 'AVAILABLE')` | ⚠️ Computed correctly but result unused in `ManagementApiHandler` — `countMessages()` used instead |
| `processedMessages` | `QueueStats.getProcessedMessages()` from `getStats()` | `COUNT(*) FILTER (WHERE status = 'PROCESSED')` | ⚠️ Computed correctly but never surfaced in REST response |
| `inFlightMessages` | `QueueStats.getInFlightMessages()` from `getStats()` | `COUNT(*) FILTER (WHERE status = 'LOCKED')` | ⚠️ Computed correctly but never surfaced in REST response |
| `deadLetteredMessages` | `QueueStats.getDeadLetteredMessages()` from `getStats()` | `COUNT(*) FILTER (WHERE status = 'DEAD_LETTER')` in `queue_messages` | ❌ Structurally wrong — `moveToDeadLetterQueue()` deletes the row after insert; count will always be 0 |
| `createdAt` | `QueueStats.getCreatedAt()` | `MIN(created_at)` — oldest message, not queue creation time | ⚠️ Misleading label; not surfaced in REST |
| `lastMessageAt` | `QueueStats.getLastMessageAt()` | `MAX(created_at)` | ⚠️ Computed but not surfaced in REST |

#### Metrics tracked in-memory but not surfaced anywhere

- `inFlightOperations` (`AtomicInteger` in `PgNativeQueueConsumer`) — in-flight DB operations
- `processingInFlight` (`AtomicInteger` in `PgNativeQueueConsumer`) — concurrent handler executions
- `MetricsProvider.recordMessageProcessed(topic, Duration)` — wall-clock processing time measured but routed to `MetricsProvider` only, never to `getStats()`

#### `MetricsProvider` methods declared but never called from native queue path

- `recordMessageRetried(topic, retryCount)` — `handleProcessingFailure()` increments `retry_count` in DB but never calls this
- `recordMessageSent(topic)` — never called from `PgNativeQueue.send()` or any producer
- `recordMessageFailed(topic, reason)` — never called from consumer failure path

---

### 1.2 Outbox Queue (`OutboxFactory` / `OutboxQueue`)

| API Field | Source Method | Source Query / Computation | Status |
|---|---|---|---|
| `messagesPerSecond` | `getStats().getMessagesPerSecond()` | `total / (MAX(created_at) − MIN(created_at)).seconds` | ⚠️ Misleading — same lifetime-average flaw as native |
| `avgProcessingTimeMs` | `getStats().getAvgProcessingTimeMs()` | Hardcoded `0.0` in `OutboxFactory.getStats()` | ❌ Always 0 despite `processing_started_at` and `processed_at` columns existing |
| `messageCount` | `countMessages()` | `COUNT(*) FROM <schema.>outbox WHERE topic = $1` | ✅ Yes |
| `consumerCount` | `getRealConsumerCount()` | Same `SubscriptionService.listSubscriptions()` path as native | ✅ Yes |
| `pendingMessages` | `QueueStats.getPendingMessages()` | `COUNT(*) FILTER (WHERE status = 'PENDING')` | ⚠️ Correct but not surfaced in REST |
| `processedMessages` | `QueueStats.getProcessedMessages()` | `COUNT(*) FILTER (WHERE status = 'COMPLETED')` | ⚠️ Correct but not surfaced in REST |
| `inFlightMessages` | `QueueStats.getInFlightMessages()` | `COUNT(*) FILTER (WHERE status = 'PROCESSING')` | ⚠️ Correct but not surfaced in REST |
| `deadLetteredMessages` | `QueueStats.getDeadLetteredMessages()` | `COUNT(*) FILTER (WHERE status = 'DEAD_LETTER')` | ✅ Correct — outbox retains the row in-place on DLQ |

#### Per-member stats via `OutboxConsumerGroupMember.getStats()`

Correct but only exposed through `ConsumerGroup.getStats()` — not surfaced via the management REST API:

- `messagesProcessed` — `AtomicLong`, correct
- `messagesFailed` — `AtomicLong`, correct
- `messagesFiltered` — `AtomicLong`, correct
- `avgProcessingTime` — computed from `totalProcessingTimeMs / processed`, correct
- `messagesPerSecond` — computed from `elapsed / processed` since `createdAt`, correct
- `lastError` — `AtomicReference`, correct
- `inFlightCount` — `AtomicLong`, tracked internally but **not included in `ConsumerMemberStats`** returned by `getStats()`

---

### 1.3 BiTemporal Event Store (`PgBiTemporalEventStore`)

REST endpoint: `EventStoreHandler`.

| API Field | Source | Status |
|---|---|---|
| `totalEvents` | `getStats()` SQL: `COUNT(*)` | ✅ Surfaced in REST DTO |
| `totalCorrections` | `getStats()` SQL: `COUNT(*) FILTER (WHERE is_correction = TRUE)` | ✅ Surfaced in REST DTO |
| `eventCountsByType` | `getStats()` SQL: `COUNT(*) GROUP BY event_type` | ✅ Surfaced as `Map<String, Long>` |
| `oldestEventTime` | `getStats()` SQL: `MIN(valid_time)` | ❌ Computed in `EventStoreStatsImpl` but **explicitly dropped** by `EventStoreHandler` |
| `newestEventTime` | `getStats()` SQL: `MAX(valid_time)` | ❌ Computed — explicitly dropped by `EventStoreHandler` |
| `storageSizeBytes` | `getStats()` SQL: `pg_total_relation_size()` | ❌ Computed — explicitly dropped by `EventStoreHandler` |
| `uniqueAggregateCount` | `getStats()` SQL: `COUNT(DISTINCT aggregate_id)` | ❌ Computed — explicitly dropped by `EventStoreHandler` |

#### `SimplePerformanceMonitor` — computed every 10 seconds, logged, never served via REST

- `getAverageQueryTime()` / `getMaxQueryTime()`
- `getAverageConnectionTime()` / `getMaxConnectionTime()`
- `getQueryCount()`
- `getConnectionFailures()` / `getConnectionFailureRate()`

---

## 2. Implementation Quality Assessment

### 2.1 Native Queue

#### Dead-letter count is structurally wrong

`PgNativeQueueFactory.getStats()` counts `status = 'DEAD_LETTER'` in `queue_messages`. However, `PgNativeQueueConsumer.moveToDeadLetterQueue()` performs a SELECT from `queue_messages`, inserts into `dead_letter_queue`, and then **deletes the original row**. After a successful DLQ move, the row is gone from `queue_messages`, so the `DEAD_LETTER` filter will always return 0. The real DLQ depth lives in `dead_letter_queue` and is never queried.

#### `avgProcessingTimeMs` is hardcoded to 0.0

`PgNativeQueueConsumer` measures `System.currentTimeMillis()` before and after `handler.handle(message)` and calls `metrics.recordMessageProcessed(topic, duration)`. This timing data is captured but is routed only to `MetricsProvider` (e.g., Micrometer). There is no `processed_at` or `processing_duration_ms` column in `queue_messages` to query back. The `getStats()` method unconditionally passes `0.0` as `avgProcessingTimeMs` with no SQL computation.

#### Three `MetricsProvider` methods are dead code on the native path

`recordMessageRetried`, `recordMessageSent`, and `recordMessageFailed` are defined on the interface and implemented by `MicrometerNoticeMetrics`, but are never called from `PgNativeQueueConsumer`, `PgNativeQueue`, or any producer class.

#### `getStats()` result is almost entirely wasted by `ManagementApiHandler`

`ManagementApiHandler.getRealMessageRate()` (line 715) and `getRealAvgProcessingTime()` (line 729) each independently call `queueFactory.getStats(queueName)` to extract a single field. This means the DB is queried twice, and the six other fields in `QueueStats` — pending, processed, inFlight, deadLettered, createdAt, lastMessageAt — are discarded. The handler then calls `countMessages()` a third time for the total count. **Three separate DB round-trips are used where one would suffice.**

#### `messagesPerSecond` rate calculation is misleading for stable queues

The formula `total / (MAX(created_at) − MIN(created_at))` approaches zero for any queue that has been running for a long time with a moderate message volume. A queue with 1,000 messages written over two days shows a rate of ~0.006/s regardless of whether it processed 900 of them in the last minute.

#### `isHealthy()` on `PgQueueFactory` (abstract base) does no DB check

`PgQueueFactory.isHealthy()` returns `!closed && databaseService != null` synchronously without any connection attempt. Both concrete subclasses (`PgNativeQueueFactory`, `OutboxFactory`) correctly override this with a real async connection check, but any future subclass that forgets to override inherits a broken health check.

---

### 2.2 Outbox Queue

#### `avgProcessingTimeMs` is hardcoded to 0.0 despite the data existing

Unlike the native queue, the outbox table has both `processing_started_at` and `processed_at` columns. The SQL in `OutboxFactory.getStats()` does not query them. The fix is a single additional expression in the existing SELECT:

```sql
AVG(EXTRACT(EPOCH FROM (processed_at - created_at)) * 1000)
    FILTER (WHERE status = 'COMPLETED' AND processed_at IS NOT NULL) AS avg_processing_ms
```

#### `FAILED` status is silently excluded from all counts

The outbox table CHECK constraint explicitly allows `'FAILED'` as a status, and the implementation can set it. However, `getStats()` only counts `PENDING`, `PROCESSING`, `COMPLETED`, and `DEAD_LETTER`. A message in `FAILED` status is invisible to all four counters; it contributes to `total` but to none of the named buckets.

#### Fan-out columns are completely ignored

`required_consumer_groups`, `completed_consumer_groups`, and `completed_groups_bitmap` represent the outbox pattern's fan-out state and are the outbox's primary differentiator from the native queue. None of these are surfaced in any stat.

#### `inFlightCount` on `OutboxConsumerGroupMember` is not included in `ConsumerMemberStats`

The member tracks in-flight processing count via `AtomicLong inFlightCount`, which is incremented before processing and decremented after. `getStats()` in the member class does not include this field in the returned `ConsumerMemberStats` object.

---

### 2.3 BiTemporal Event Store

#### Four fields are computed by SQL but dropped by the REST handler

`EventStoreHandler` constructs the REST DTO from `EventStore.EventStoreStats` using only `totalEvents`, `totalCorrections`, and `eventCountsByType`. The `getOldestEventTime()`, `getNewestEventTime()`, `getStorageSizeBytes()`, and `getUniqueAggregateCount()` methods are called in the SQL query — incurring the cost of `pg_total_relation_size()` and `COUNT(DISTINCT aggregate_id)` — but the results are thrown away before serialization.

#### `sortOrder` in `EventQuery` is parsed but not applied

`PgBiTemporalEventStore.query()` hard-codes `ORDER BY transaction_time DESC, valid_time DESC` regardless of the `sortOrder` field set on the `EventQuery` builder. The `SortOrder` enum defines six values (`VALID_TIME_ASC/DESC`, `TRANSACTION_TIME_ASC/DESC`, `VERSION_ASC/DESC`) but none are injected into the SQL string.

#### `offset` pagination field is silently ignored

`EventQuery` has an `offset` field but `query()` never appends `OFFSET $N` to the SQL. Combined with the hard-coded `LIMIT 1000` default, cursor-based pagination is impossible.

#### Five filter fields parsed but not applied

`correlationId`, `headerFilters`, `minVersion`, `maxVersion`, and `includeCorrections` are defined on `EventQuery` but are never added to the `WHERE` clause in `query()`. Only `eventType`, `aggregateId`, `causationId`, and the two temporal ranges are applied.

#### `SimplePerformanceMonitor` logs but never serves

The monitor computes average/max query time, connection time, and failure rate, logged every 10 seconds. There is no REST endpoint, no Micrometer integration, and no `getStats()` inclusion for any of these values.

#### Read paths have no timing instrumentation

`PgBiTemporalEventStore.append()` calls `timing.recordAsQuery()` wrapping the insert. `query()`, `getById()`, `getAllVersions()`, `getAsOfTransactionTime()`, and `getUniqueAggregates()` have no equivalent timing call.

---

## 3. Recommended Metric Additions (Prioritised)

### 3.1 HIGH — Broken or Misleading, Fix Now

| # | Metric | Queue Type | Data Source | API Field | Effort |
|---|---|---|---|---|---|
| H1 | Fix dead-letter count | Native | `COUNT(*) FROM dead_letter_queue WHERE topic = $1` | `deadLetteredMessages` (same field name, correct source) | Small |
| H2 | Fix `avgProcessingTimeMs` | Outbox | `AVG(EXTRACT(EPOCH FROM (processed_at - created_at)) * 1000) FILTER (WHERE status='COMPLETED')` | `avgProcessingTimeMs` (replace stub `0.0`) | Small |
| H3 | Consolidate triple `getStats()` calls | Both | Call `getStats()` once per queue; derive all fields from single `QueueStats` result | Eliminates 2 DB round-trips per queue per request | Small |
| H4 | Surface 4 dropped EventStore fields | BiTemporal | Already computed in `EventStoreStatsImpl`: `getOldestEventTime()`, `getNewestEventTime()`, `getStorageSizeBytes()`, `getUniqueAggregateCount()` | `oldestEventTime`, `newestEventTime`, `storageSizeBytes`, `uniqueAggregateCount` | Small |
| H5 | Count `FAILED` status in outbox | Outbox | `COUNT(*) FILTER (WHERE status = 'FAILED')` added to existing SELECT | `failedMessages` (new field) | Small |

### 3.2 MEDIUM — Genuinely Useful, Data Already in DB

| # | Metric | Queue Type | Data Source | API Field | Effort |
|---|---|---|---|---|---|
| M1 | Rolling 60-second message rate | Both | `COUNT(*) WHERE created_at >= NOW() - INTERVAL '60 seconds'` | Replace `messagesPerSecond` computation in both factories | Small |
| M2 | Oldest pending message age | Both | `EXTRACT(EPOCH FROM (NOW() - MIN(visible_at))) * 1000 FILTER (WHERE status='AVAILABLE')` — outbox uses `created_at` where `status='PENDING'` | `oldestPendingMessageAgeMs` (new field) | Small |
| M3 | Surface `pendingMessages`, `processedMessages`, `inFlightMessages` in REST | Both | Already in `QueueStats` — just not emitted in the REST response object | Add to `statistics` sub-object in `getQueueDetails` and `getQueuesForSetup` | Small |
| M4 | Per-member `inFlightCount` | Outbox | `AtomicLong inFlightCount` already tracked in `OutboxConsumerGroupMember` | Add `inFlightCount` to `ConsumerMemberStats` | Small |
| M5 | EventStore `SimplePerformanceMonitor` data | BiTemporal | `getAverageQueryTime()`, `getMaxQueryTime()`, `getConnectionFailureRate()`, `getQueryCount()` already computed | `avgQueryTimeMs`, `maxQueryTimeMs`, `connectionFailureRate`, `totalQueryCount` | Medium |
| M6 | Retry distribution | Outbox | `AVG(retry_count)`, `MAX(retry_count)`, `COUNT(*) WHERE next_retry_at IS NOT NULL AND next_retry_at <= NOW()` | `avgRetryCount`, `maxRetryCount`, `retriableMessageCount` | Small/Med |
| M7 | Wire missing `MetricsProvider` call sites | Native | Three existing call sites in `PgNativeQueueConsumer` and `PgNativeQueue`; implementations exist in `MicrometerNoticeMetrics` | `recordMessageRetried`, `recordMessageSent`, `recordMessageFailed` | Small |
| M8 | Correction rate per event type | BiTemporal | `COUNT(*) FILTER (WHERE is_correction) / COUNT(*)::float GROUP BY event_type` alongside existing `eventCountsByType` query | `correctionRateByType: Map<String, Double>` | Medium |

### 3.3 LOW — Nice to Have, Requires New Queries or Schema Changes

| # | Metric | Queue Type | Notes | Effort |
|---|---|---|---|---|
| L1 | Real `avgProcessingTimeMs` for native | Native | Requires new `processed_at` or `processing_duration_ms` column; schema migration needed | Large |
| L2 | Per-priority message distribution | Both | `priority` column exists in both tables; `COUNT(*) FILTER (...) GROUP BY priority` → `pendingByPriority: Map<Integer, Long>` | Medium |
| L3 | Fan-out completion metrics | Outbox | `AVG(completed_consumer_groups::float / NULLIF(required_consumer_groups, 0))` → `avgFanOutCompletionRatio`; `COUNT(*) WHERE completed < required AND status='COMPLETED'` → `partiallyDeliveredCount` | Small query / Med plumbing |
| L4 | Timing on EventStore read paths | BiTemporal | Wrap `query()`, `getById()`, `getAllVersions()`, `getAsOfTransactionTime()`, `getUniqueAggregates()` with `timing.recordAsQuery()` | Medium |
| L5 | Bi-temporal ingestion lag | BiTemporal | `AVG(EXTRACT(EPOCH FROM (transaction_time - valid_time)) * 1000)` per event type — measures how late events are recorded relative to when they happened → `avgIngestionLagMsByType: Map<String, Double>` | Small query / Med plumbing |
| L6 | Idempotency rejection counter | Outbox | Not in DB; requires new `AtomicLong` counter in `OutboxProducer` incremented when unique-index violation is caught → `idempotencyRejectedCount` | Medium |
| L7 | `getAsOfTransactionTime()` performance tracking | BiTemporal | Most expensive read path (multi-step recursive CTE); expose `asOfQueryCount`, `avgAsOfQueryTimeMs` | Medium |

---

## 4. Consistency Gaps Between Queue Types

| Dimension | Native Queue | Outbox Queue |
|---|---|---|
| `avgProcessingTimeMs` | ❌ Always `0.0` — no timestamp column in schema | ❌ Always `0.0` — **but `processing_started_at` and `processed_at` exist and are populated**; a data access problem, not a schema problem |
| Dead-letter tracking | ❌ Wrong — queries deleted rows in `queue_messages`; real data in `dead_letter_queue` table | ✅ Correct — in-place `status = 'DEAD_LETTER'`; row retained |
| `FAILED` status tracking | Not counted in `getStats()` | Not counted in `getStats()` — same gap |
| Per-member in-flight | `processingInFlight` (`AtomicInteger`) tracked in `PgNativeQueueConsumer` but not in `getStats()` | `inFlightCount` (`AtomicLong`) tracked in `OutboxConsumerGroupMember` but not in `ConsumerMemberStats` |
| Retry count exposure | `retry_count` column exists in `queue_messages`; not queried; `recordMessageRetried()` never called | `retry_count` and `next_retry_at` columns exist; neither queried by `getStats()` |
| Fan-out state | Not applicable — native queue has no fan-out concept | `required_consumer_groups`, `completed_consumer_groups`, `completed_groups_bitmap` all exist; none surfaced |
| `processing_started_at` / `processed_at` | ❌ Columns do not exist in `queue_messages` schema | ✅ Exist in outbox table, populated by implementation, unused by `getStats()` |
| DLQ mechanism | Separate `dead_letter_queue` table; written by `moveToDeadLetterQueue()`; never queried by `getStats()` | In-place status flag; outbox retains the original row |
| `MetricsProvider` integration | Partial — `recordMessageReceived`, `recordMessageProcessed`, `recordMessageDeadLettered` called; `recordMessageRetried`, `recordMessageSent`, `recordMessageFailed` never called | None — uses in-memory `AtomicLong` counters only; no `MetricsProvider` calls at all |
| Schema qualification | ❌ Hardcoded table name `queue_messages` — no schema prefix | ✅ Correctly uses `qualifyTable("outbox")` with schema prefix |

---

## 5. Quick Wins

The following five changes collectively fix the most visible correctness issues and eliminate three unnecessary DB round-trips per request, requiring no schema migrations.

### QW1 — Fix the native queue dead-letter count (`PgNativeQueueFactory.getStats()`)

Change the SQL in `PgNativeQueueFactory.getStats()` to query `dead_letter_queue` instead of relying on the `DEAD_LETTER` status in `queue_messages`. Add a second query or a correlated subquery:

```sql
SELECT COUNT(*) AS dlq_count FROM dead_letter_queue WHERE topic = $1
```

Pass the result as the `deadLetteredMessages` argument to the `QueueStats` constructor. This turns a metric that is structurally always 0 into one that accurately reflects the real DLQ depth.

---

### QW2 — Fix outbox `avgProcessingTimeMs` (`OutboxFactory.getStats()`)

In the existing SELECT in `OutboxFactory.getStats()`, add one expression:

```sql
AVG(EXTRACT(EPOCH FROM (processed_at - created_at)) * 1000)
    FILTER (WHERE status = 'COMPLETED' AND processed_at IS NOT NULL) AS avg_processing_ms
```

Replace the hardcoded `0.0` in the `QueueStats` constructor call with `row.getDouble("avg_processing_ms")`. This is a single-line SQL addition and a one-line Java change. The outbox is the only queue type with the required timestamp columns, so this fix has no equivalent in the native queue without a schema migration.

---

### QW3 — Surface four dropped EventStore fields (`EventStoreHandler` + `EventStoreStats` DTO)

The SQL queries for `oldestEventTime`, `newestEventTime`, `storageSizeBytes`, and `uniqueAggregateCount` already execute on every `getStats()` call — the cost of `pg_total_relation_size()` and `COUNT(DISTINCT aggregate_id)` is already being paid. `EventStoreHandler` currently throws the results away before serialisation. Add four fields to the `EventStoreStats` DTO and populate them in the handler's mapping code. **Zero additional DB work** — the data is already fetched.

Fields to add to the DTO:

- `oldestEventTime` (`Instant`)
- `newestEventTime` (`Instant`)
- `storageSizeBytes` (`long`)
- `uniqueAggregateCount` (`long`)

---

### QW4 — Consolidate three DB calls into one in `ManagementApiHandler`

`ManagementApiHandler` currently calls the database three times per queue per request:

1. `getRealMessageRate()` at line 715 calls `queueFactory.getStats(queueName)` → discards 6 of 8 fields
2. `getRealAvgProcessingTime()` at line 729 calls `queueFactory.getStats(queueName)` again → discards 6 of 8 fields
3. `countMessages()` is called separately for the total count

Refactor to call `queueFactory.getStats(queueName)` once per queue and derive `messagesPerSecond`, `avgProcessingTimeMs`, and `messageCount` (via `QueueStats.getTotalMessages()`) from the single returned `QueueStats` object. This change also enables surfacing `pendingMessages`, `processedMessages`, `inFlightMessages`, and `deadLetteredMessages` in the REST response without any additional DB cost.

---

### QW5 — Wire three missing `MetricsProvider` call sites in `PgNativeQueueConsumer`

Three `MetricsProvider` methods are fully implemented in `MicrometerNoticeMetrics` and `NoOpNoticeMetrics` but are never invoked from the native queue path:

**1. In `PgNativeQueueConsumer.handleProcessingFailure()`, after incrementing `retry_count` in the DB:**

```java
metrics.recordMessageRetried(topic, retryCount);
```

**2. In `PgNativeQueue.send()` (or its producer class), after a successful insert:**

```java
metrics.recordMessageSent(topic);
```

**3. In the consumer branch that exhausts retries and sends to DLQ:**

```java
metrics.recordMessageFailed(topic, reason);
```

These are three one-line additions at already-identified decision points. No interface changes, no new classes — the implementations already exist.
