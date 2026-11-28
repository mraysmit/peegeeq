# PeeGeeQ Durable Subscriptions Design Record

**Status:** the durable subscription service for the bi-temporal event store is built (Task 4, complete
2026-09-05). The outbox parts, the delivery log, REST endpoints, metrics, handler retry and the automatic move to
`DEAD` are not built. Task status lives in `docs-design/tasks/tasks.md`.
**Rewritten:** 2026-11-28. The current contract of the built service is owned by
`docs/PEEGEEQ_EVENT_STORE_GUIDE.md` section 8. This record keeps the design reasoning, the decisions and the parts
that are not built. The earlier full text, with the API proposals, the runtime flow, the historical test inventory
and the phase plan, is in git history at commit `0b644af1`.

## 1. Purpose

A live event-store subscription (LISTEN/NOTIFY) loses every event committed while its connection is down or its
process is stopped. A durable subscription exists so that a consumer that must not miss an event can stop, restart
or lose its connection, and then continue from the last event it acknowledged, with no gap. It stores its
definition and a cursor in the database and replays committed events in a fixed order.

Success means: after a restart the application subscribes again with the same identity and a handler, and the
handler receives every event after the cursor, at least once, in `id` order.

## 2. What is built and what is not

Built for the bi-temporal store (read in code on 2026-11-28):

- `BiTemporalSubscriptionService` in `peegeeq-api`, created by
  `EventStoreFactory.createBiTemporalSubscriptionService()` and implemented by
  `DurableBiTemporalSubscriptionCoordinator` and `DurableBiTemporalDelivery` in `peegeeq-bitemporal`.
- The table `bitemporal_subscriptions`: migration `V012`, lease columns `V020`, and the base template
  `08f-bitemporal-subscriptions.sql`.
- The interface `DurableSubscriptionCoordinator<K>` in `peegeeq-api`. Only the bi-temporal coordinator implements
  it.

Not built:

- the outbox durable coordinator (section 4.1);
- the delivery log `bitemporal_subscription_delivery_log` (section 4.2);
- manual acknowledgement (section 4.3);
- handler retry with backoff, and an automatic move to `DEAD` (section 4.4);
- REST endpoints for durable bi-temporal subscriptions (section 4.5);
- metrics (section 4.6).

No production code calls `loadActiveSubscriptionDefinitions()` (search of the main sources). It checks the stored
cursor of every `ACTIVE` definition in the schema against the table's highest `id`, and starts nothing.

## 3. Design reasoning

### 3.1 The cursor is the numeric `id`

The cursor is the event table's `BIGSERIAL id`. The other ordered columns cannot serve:

- `transaction_time` can be equal for concurrent inserts and is taken before the commit.
- `valid_time` is business time and can be in the past.
- `event_id` is a random UUID and has no order.

`id` order is the order in which the sequence handed out values, not commit order. A transaction that took a lower
`id` can commit after one that took a higher `id`. A cursor that had moved past the higher `id` would then skip the
lower one. Each replay pass therefore fixes a boundary behind a short writer barrier. In a READ COMMITTED
transaction with `lock_timeout` set to 5 seconds, it takes a `SHARE` lock on the table, which waits for inserts in
progress to finish, reads the highest `id`, and commits. The lock is released before any event is read or handled.
Every event at or below that boundary is committed, so the pass has no gap.

This rests on a contract: the table is append-only, every `id` comes from the column's sequence during the INSERT,
and the sequence is ascending, non-cycling and `CACHE 1`. Each pass checks the sequence settings. It cannot detect an
`id` written explicitly or a reset sequence, so both are outside the contract. A cursor above the table's highest
`id` fails the pass.

The barrier has three costs. Appends to the table wait behind the lock for up to 5 seconds. A lock timeout stops
delivery. The database user needs the privilege to take a `SHARE` lock on the table.

### 3.2 Notifications are hints

An earlier proposal compared the `event_id` of a notification with the cursor to decide between catch-up and live
delivery. It was rejected for two reasons: `event_id` is a UUID and cannot be compared with a numeric cursor, and
`id` allocation alone is not commit order.

Catch-up and live delivery use one path instead (`DurableBiTemporalDelivery`):

1. The delivery registers an all-events live subscription on the store, so LISTEN is in place before the first
   pass.
2. It then runs the first replay pass.
3. A notification only requests another pass. The event in the notification is never delivered from it.
4. A request that arrives while a pass runs sets a flag, and one more pass runs when the current one ends.
5. A one-second timer also requests a pass. This covers notifications lost while the LISTEN connection was down.

The hint subscription is an all-events subscription. Each append to the table therefore costs one event read for
the hint, as for any all-events subscription (`docs/PEEGEEQ_EVENT_STORE_GUIDE.md` section 7). The pass then reads
each matching event again.

### 3.3 Ownership: leases and fencing

Several service instances can register the same identity. One pass at a time owns it, so that two instances do not
deliver and acknowledge the same events in parallel.

- A pass takes a lease: a new UUID as owner, an expiry on the database clock (`heartbeat_timeout_seconds` from
  now), and `lease_generation` incremented by one.
- The pass renews the lease every `heartbeat_interval_seconds` and releases it when it ends.
- A live delivery that finds the lease held stays a standby and tries again on its next wake-up. `catchUp` fails
  instead.
- Before each event and in each cursor update, the pass checks that its owner and generation still hold and that
  the lease has not expired. An owner whose lease expired or was revoked cannot move the cursor.
- Pause, cancel and cursor reset revoke the lease: they clear the owner and increment the generation.

Fencing cannot undo a side effect that a stale owner's handler has already started.

### 3.4 At least once

The cursor advances in its own transaction after the handler's Future succeeds. The handler's side effects are not
in that transaction. A crash between the two delivers the event again, so handlers must be idempotent. Effectively
once per consumer would need the delivery log of section 4.2.

### 3.5 Filters

`eventType` and `aggregateId` are filters, not part of the identity. They use the same segment wildcard rule as the
live subscriptions, so durable and live delivery select the same events. A pass reads a bounded `id` range and
applies the filters in Java. The cursor also advances past events that do not match, so a narrow filter does not
read the same excluded rows again on every pass.

A wildcard or null filter with `FROM_BEGINNING` reads the whole table. The index on `(id, event_type,
aggregate_id)` exists only on the Flyway table `bitemporal_event_log` (`V012`); a table created from the event
store template reads its range through the `id` primary key. The effect of the index on replay cost has not been
measured.

### 3.6 Identity and API shape

The identity is `(tableName, subscriptionName, consumerGroup)`.

- A separate `BiTemporalSubscriptionService` was chosen over extending the outbox `SubscriptionService`. Outbox
  subscriptions are keyed by `(topic, groupName)`, and one interface for both would carry parameters that one side
  never uses.
- The service is created by the event-store factory, not by `PeeGeeQManager`, because `peegeeq-db` cannot depend
  on `peegeeq-bitemporal` without a dependency cycle.
- The typed `subscribe` and `catchUp` take a `Class<T>`, so that the store deserialises the payload without an
  unchecked generic cast. The untyped `subscribe` overload fails with `UnsupportedOperationException`.
- No separate configuration class was introduced. Delivery settings come from `SubscriptionOptions`, the lease
  timing from the stored heartbeat interval and timeout, and the reconcile interval is fixed at one second.

### 3.7 Restart

Definitions and cursors survive in the table. Handlers are application code and cannot be stored. After a restart
the application calls `subscribe` again with the same identity and a handler, and the stored cursor is kept.
Nothing restores a subscription automatically (section 2).

## 4. Proposals not built

These are design ideas, not approved work. The task register lists outbox durable subscriptions and their REST
endpoints as backlog that needs an owner decision.

### 4.1 Outbox durable coordinator

Outbox consumer-group subscriptions already persist in `outbox_topic_subscriptions` with their status, heartbeat
and backfill state. The proposal is a `DurableOutboxSubscriptionCoordinator` that implements
`DurableSubscriptionCoordinator<K>` with the key `(topic, groupName)`, so that both modules offer one cursor
interface.

The earlier text named `consumer_group_index.last_processed_id` as the outbox cursor. No runtime class reads or
writes `consumer_group_index`; only the test-support schema initializer uses it (search of the main sources). That
column therefore records no progress, and the outbox cursor has to be chosen again before this proposal can be
built.

`V012` adds `outbox_topic_subscriptions.durable_enabled BOOLEAN DEFAULT TRUE`, and the base template
`08b-consumer-table-subscriptions.sql` has the same column. No code reads or writes it (search of the main
sources). It was meant as a flag for parity with the bi-temporal service.

### 4.2 Delivery log

A bounded table, `bitemporal_subscription_delivery_log`, that records delivered events per consumer for
deduplication and audit. It would give effectively-once processing per consumer. Its schema is not designed.

### 4.3 Manual acknowledgement

The cursor advances only when the handler's Future succeeds. A manual acknowledgement needs an acknowledgement
token tied to a delivered event. It is not designed.

### 4.4 Handler retry and `DEAD`

Today a handler failure stops delivery, and redelivery needs a new service instance
(`docs/PEEGEEQ_EVENT_STORE_GUIDE.md` section 8; register D43). The proposal is a retry with backoff and a move to
`DEAD` after a limit, logged at ERROR.

### 4.5 REST endpoints

Pause, resume, cancel, cursor reset and replay from a cursor, for durable bi-temporal subscriptions.

### 4.6 Observability

Metrics:

- replay lag: the table's highest `id` minus `last_processed_id`;
- cursor commit latency;
- replay page duration;
- handler failure count;
- wake-up count by source (notification or timer).

Logs: registration (INFO); replay start with subscription, cursor and table (INFO); replay end (INFO); a handler
failure that stops delivery (ERROR, as `DurableBiTemporalDelivery` logs it today); the move to `DEAD` (ERROR).

### 4.7 Scale and resilience work

`EXPLAIN ANALYZE` of the replay query on large tables; tests that stop the process during catch-up and during live
delivery; replay of a backlog of millions of events.

## 5. Risks

| Risk | Mitigation in the built service |
|---|---|
| Replay load on a large table with a wildcard or null filter | Bounded pages; the composite index on the Flyway table only (section 3.5) |
| Cursor skips an event or replays out of order | Writer barrier, sequence check, cursor bounds check, row-locked cursor update, lease fencing (sections 3.1 and 3.3) |
| Side effects run twice | At-least-once is documented; handlers must be idempotent; the delivery log is a proposal (section 4.2) |
| Gap between catch-up and live delivery | LISTEN before the first pass, one more pass after a request during a pass, one-second timer (section 3.2) |
| Writers blocked by the barrier | The lock waits at most 5 seconds; a timeout stops delivery and fails `deliveryCompletion` |

## 6. Decisions

| # | Decision | Reason |
|---|---|---|
| D1 | The cursor is the table's `BIGSERIAL id` | Numeric order; section 3.1 states why commit order needs the writer barrier |
| D2 | No `pgq_` table prefix | Existing tables use unprefixed names |
| D3 | Explicit `event_type` and `aggregate_id` columns, not a JSONB filter | They match the two filter fields and can be read by SQL |
| D4 | A `table_name` column in `bitemporal_subscriptions` | Event store instances use different tables |
| D5 | A separate `BiTemporalSubscriptionService` | Outbox subscriptions are keyed by `(topic, groupName)`; bi-temporal ones by `(tableName, subscriptionName, consumerGroup)`, with `eventType` and `aggregateId` as filters. Corrected 2026-11-28: the earlier text listed the filters as part of the key |
| D6 | Lease columns added in `V020` | Expiry and generation fencing protect concurrent passes |
| D7 | Extend `SubscriptionOptions` rather than add an options class | Reuse the existing builder and the `StartPosition` enum |
| D8 | The coordinator has its own key record `(tableName, subscriptionName, consumerGroup)` | The live notification-filter key `SubscriptionKey` is package-private and holds filters, not an identity |
| D9 | LISTEN is in place before the first pass | No notification is lost between catch-up and live delivery |
| D10 | No new configuration; no system properties | The earlier decision named configuration through `PeeGeeQConfiguration`. No configuration class was added; settings come from `SubscriptionOptions` and the stored heartbeat values |
| D11 | The cursor advances automatically after the handler succeeds | Manual acknowledgement needs a token API (section 4.3) |
| D12 | Durable and live delivery share the segment wildcard rule | Both select the same events |
| D13 | The service is created by `EventStoreFactory` | `peegeeq-db` cannot depend on `peegeeq-bitemporal` |
| D14 | Typed `subscribe` and `catchUp` require `Class<T>` | Deserialisation without an unchecked generic cast |
| D15 | Short writer barrier and a `CACHE 1` sequence contract | A delayed commit of a lower `id` is included before the cursor passes it |
| D16 | Notifications are hints, not deliveries | One ordered cursor path, with a timer for lost notifications |
| D17 | Terminal delivery failure (`deliveryCompletion`) and resource cleanup (`close`) are separate Futures | A failure stays visible while resources still close before a retry |
