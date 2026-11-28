# PeeGeeQ Bi-Temporal Event Subscriptions

**Corrected:** 2026-11-21 against `18c27376`: schema-qualified channel names throughout; `*` matches exactly one segment; every notified event is fetched before matching; duplicate delivery is open defect D4; one LISTEN connection per store; 50-character subscription event-type limit; unrecorded performance figures removed.

## Overview

This document describes the subscription model for reactive bi-temporal event notifications using PostgreSQL LISTEN/NOTIFY.

The code is `ReactiveNotificationHandler` in `peegeeq-bitemporal`. Each `PgBiTemporalEventStore` instance owns one handler. That handler holds one LISTEN connection, and every subscription made on that store shares it.

### Channel names

All channel names are schema-qualified and table-qualified:

- General channel: `{schema}_bitemporal_events_{table}`
- Type-specific channel: `{schema}_bitemporal_events_{table}_{event_type}`, with dots in the event type replaced by underscores

For the default store these are `public_bitemporal_events_bitemporal_event_log` and, for example, `public_bitemporal_events_bitemporal_event_log_order_created`. Hyphens in the table name are replaced by underscores. A name longer than 63 characters is truncated and given an `_` plus the first 8 hex characters of its MD5 hash. Both the trigger and `ReactiveNotificationHandler.createSafeChannelName` apply this rule.

### Event type validation

`subscribe(eventType, ...)` validates the event type or pattern before it opens any channel:

- Maximum length is 50 characters (`MAX_EVENT_TYPE_LENGTH`).
- It must be dot-separated segments. Each segment contains only `a-z`, `A-Z`, `0-9` and `_`, or is `*` alone.
- A blank value is rejected. `null` means all events.

An appended event type may be up to 255 characters (`PgBiTemporalEventStore`, `MAX_METADATA_LENGTH`). An event type longer than 50 characters can therefore be stored, but no exact subscription can name it.

## Subscription Patterns

### Exact Match

Subscribe to a specific event type.

- Pattern: `order.created`
- PostgreSQL channel: `{schema}_bitemporal_events_{table}_order_created` (dots converted to underscores)
- Filtering: the handler only LISTENs on the type-specific channel for this subscription. If the same store also has an all-events or wildcard subscription, see [Duplicate delivery (open defect D4)](#duplicate-delivery-open-defect-d4).

### Wildcard Match

Subscribe to events matching a pattern. Each `*` matches exactly one complete segment (segments are separated by dots). The pattern and the event type must have the same number of segments (`ReactiveNotificationHandler.matchesWildcardPattern`). `order.*` does not match `order.payment.completed`.

| Pattern | Matches | Does Not Match |
|---------|---------|----------------|
| `order.*` | `order.created`, `order.shipped` | `orders.created`, `order`, `payment.received` |
| `*.created` | `order.created`, `payment.created` | `order.shipped`, `created` |
| `order.*.completed` | `order.payment.completed`, `order.shipping.completed` | `order.created`, `order.completed` |
| `*.order.*` | `foo.order.bar`, `abc.order.xyz` | `order.created`, `order` |
| `order.*` | `order.created` | `order.payment.completed` (three segments, pattern has two) |

- PostgreSQL channel: `{schema}_bitemporal_events_{table}` (general channel)
- Filtering: Application-side pattern matching against event type, after the event is fetched

### All Events

Subscribe to all events by passing null as the event type.

- Pattern: `null`
- PostgreSQL channel: `{schema}_bitemporal_events_{table}`
- Filtering: None - all events are received

## Channel Strategy

| Subscription Type | Channel | Rationale |
|-------------------|---------|-----------|
| Exact match | Type-specific (`{schema}_bitemporal_events_{table}_{type}`) | The type channel carries only events of that type |
| Wildcard | General (`{schema}_bitemporal_events_{table}`) | PostgreSQL channels don't support wildcards - must receive all and filter |
| All events | General (`{schema}_bitemporal_events_{table}`) | Need all events by definition |

The channels are opened on the store's single LISTEN connection. If one store has both an exact subscription and a wildcard or all-events subscription, that connection LISTENs on both channels and receives both notifications for a matching event.

## Dot-to-Underscore Conversion

Event types may contain dots (e.g., `order.created`), but PostgreSQL channel names cannot. The system converts dots to underscores when constructing channel names:

- Event type: `order.created`
- Channel name: `{schema}_bitemporal_events_{table}_order_created`

This conversion happens in both:
1. Java code when setting up LISTEN
2. PostgreSQL trigger when sending NOTIFY

### Collision Handling

Event types `my.channel` and `my_channel` both map to channel `{schema}_bitemporal_events_{table}_my_channel`. The system handles this by:
- Including the original event type in the notification payload
- Matching subscriptions against the original event type, not the channel name

## PostgreSQL Trigger

The trigger sends notifications to two channels for each event:

1. `{schema}_bitemporal_events_{table}` - General channel for wildcard and all-events subscriptions
2. `{schema}_bitemporal_events_{table}_{event_type}` - Type-specific channel for exact match subscriptions

For a per-store table the trigger function comes from `peegeeq-db/src/main/resources/db/templates/eventstore/03-function.sql`. For the Flyway table `bitemporal_event_log` it is `notify_bitemporal_event()`, last replaced in `V011__Post_Fanout_Consolidation.sql`. V001 created that function with the older unqualified names `bitemporal_events` and `bitemporal_events_{type}`; V011 replaced them.

## Test Cases

### Basic Subscription Behavior

1. **Type-specific subscription only receives matching events**
   - Subscribe to `order.created`
   - Append: `order.created`, `order.shipped`, `payment.received`
   - Expect: 1 event received

2. **General subscription receives all events**
   - Subscribe with null event type
   - Append: `order.created`, `order.shipped`, `payment.received`
   - Expect: 3 events received

3. **Multiple type-specific subscriptions receive only their types**
   - Subscribe to `order.created` and `payment.received`
   - Append: `order.created`, `order.shipped`, `payment.received`
   - Expect: Each subscriber receives 1 event

4. **Dot-underscore channel collision isolation**
   - Subscribe to `my.channel` and `my_channel`
   - Append: 1 event of each type
   - Expect: Each subscriber receives exactly 1 matching event

5. **No duplicate events (single exact subscription)**
   - Subscribe to `test.event`
   - Append: 1 event of type `test.event`
   - Expect: 1 event received (not 2)
   - This test (`PgBiTemporalEventStoreIntegrationTest.testNoDuplicateEvents`) has one exact subscription only. No test covers an exact subscription combined with a wildcard or all-events subscription on the same store. See open defect D4.

### Wildcard Subscriptions

6. **Trailing wildcard `order.*`**
   - Subscribe to `order.*`
   - Append: `order.created`, `order.shipped`, `payment.received`
   - Expect: 2 events received

7. **Leading wildcard `*.created`**
   - Subscribe to `*.created`
   - Append: `order.created`, `payment.created`, `order.shipped`
   - Expect: 2 events received

8. **Middle wildcard `order.*.completed`**
   - Subscribe to `order.*.completed`
   - Append: `order.payment.completed`, `order.shipping.completed`, `order.created`
   - Expect: 2 events received

9. **Wildcard matches whole segments only**
   - Subscribe to `order.*`
   - Append: `order.created`, `orders.created`, `order`
   - Expect: 1 event received (`order.created` only)

10. **Multiple wildcards `*.order.*`**
    - Subscribe to `*.order.*`
    - Append: `foo.order.bar`, `abc.order.xyz`, `order.created`
    - Expect: 2 events received

## Implementation Status

The handler chooses the channel to LISTEN on by subscription type (`setupListenChannels`):

- **Exact match subscriptions**: LISTEN on the type-specific channel (e.g., `public_bitemporal_events_bitemporal_event_log_order_created`)
- **Wildcard subscriptions**: LISTEN on the general channel (`public_bitemporal_events_bitemporal_event_log`) with application-side filtering
- **All events subscriptions**: LISTEN on the general channel

The channel choice is per subscription. The LISTEN connection is per store. The handler adds each channel to the same connection.

### Duplicate delivery (open defect D4)

`handleNotification` ignores the channel a notification arrived on. For every notification it fetches the event and then calls `notifySubscriptions`, which delivers to every matching all-events, exact and wildcard subscription on the store.

Consider one store with an all-events subscription and an exact `order.created` subscription. The connection LISTENs on both the general and the `order_created` channel. One `order.created` insert sends a notification on each channel. Each notification is dispatched to both subscriptions. From the code, each subscription can receive the event twice.

The Javadoc on `handleNotification` says it deduplicates events that arrive on more than one channel. No deduplication code exists in the class. The same applies to an exact subscription combined with a wildcard subscription that matches it.

This behaviour comes from reading the code. It has not been confirmed at runtime. It is recorded as open defect D4 in `docs-design/tasks/tasks.md`.

## How It Works

### Channel Architecture

The PostgreSQL trigger on the bi-temporal events table sends notifications to TWO channels for every event:

1. **General channel**: `{schema}_bitemporal_events_{table}` - receives ALL events for that table
2. **Type-specific channel**: `{schema}_bitemporal_events_{table}_{event_type}` - receives only that type

> **Note**: Channel names are schema-qualified to support multi-tenant deployments. For example,
> `public_bitemporal_events_bitemporal_event_log` for the default schema.

### Subscription Routing

| Subscription | Listens On | Filtering |
|--------------|------------|-----------|
| `order.created` (exact) | `public_bitemporal_events_bitemporal_event_log_order_created` only | None |
| `order.*` (wildcard) | `public_bitemporal_events_bitemporal_event_log` only | In-memory pattern match |
| `null` (all) | `public_bitemporal_events_bitemporal_event_log` only | None |

## Scalability

No load or latency measurement of the subscription path is recorded. This section describes what the code does.

**1. Database side**

The trigger does two `pg_notify()` calls per insert:

```sql
-- Channel names are schema-qualified: {schema}_bitemporal_events_{table}
pg_notify('public_bitemporal_events_bitemporal_event_log', payload);
pg_notify('public_bitemporal_events_bitemporal_event_log_order_created', payload);
```

The cost of the two calls has not been measured in this project.

**2. Network: exact match uses the type channel**

An exact subscription makes the store's connection LISTEN on the type channel only. If the store has no wildcard or all-events subscription, the connection receives only notifications for the subscribed types. If the store also has a wildcard or all-events subscription, the same connection also LISTENs on the general channel and receives every notification for the table.

**3. Every notified event is fetched before matching**

`handleNotification` reads `event_id`, `event_type` and `aggregate_id` from the notification payload. It then calls the event retriever, which fetches the full event from the database. Only after the fetch completes does `notifySubscriptions` match the event against the subscriptions.

```
[PostgreSQL] ──notification──> [fetch full event] ──> [match subscriptions] ──> handlers
                                                            │
                                                            └── no match: event discarded after the fetch
```

A wildcard subscription such as `order.*` therefore causes one database fetch for every event inserted into the table, including events it does not match. The payload already contains the event type, so matching before the fetch is possible, but the code does not do it. This is part of open defect D4.

For a per-store table the payload is a JSON object with `action`, `id`, `event_id`, `event_type`, `aggregate_id`, `correlation_id`, `causation_id`, `is_correction`, `transaction_time`, `channel_name` and `original_channel_name`, plus `general_channel` on the type channel. The V011 function for `bitemporal_event_log` sends the same fields without `action`, `id` and `original_channel_name`. The payload size has not been measured.

### Notification Flow

> **Channel naming**: `{schema}_bitemporal_events_{table}` format. Examples below use
> `public` schema and `bitemporal_event_log` table, abbreviated for readability.

```
INSERT event (type: order.created)
    │
    ▼
┌─────────────────────────────────────────────────────────────────────┐
│ PostgreSQL Trigger                                                  │
│   pg_notify('public_bitemporal_events_...', payload)                │
│   pg_notify('public_bitemporal_events_..._order_created', payload)  │
└─────────────────────────────────────────────────────────────────────┘
    │                                           │
    ▼                                           ▼
┌─────────────────────────┐         ┌─────────────────────────────────┐
│ General Channel         │         │ Type-Specific Channel           │
│ {schema}_bitemporal_... │         │ {schema}_bitemporal_..._order   │
└─────────────────────────┘         └─────────────────────────────────┘
    │                                           │
    ▼                                           ▼
┌─────────────────────────┐         ┌─────────────────────────────────┐
│ Wildcard Subscriber     │         │ Exact Match Subscriber          │
│ (order.*)               │         │ (order.created)                 │
│                         │         │                                 │
│ Fetch event, then       │         │ Fetch event, then deliver       │
│ pattern match:          │         │                                 │
│ order.* vs order.created│         │                                 │
│ ✓ Match - deliver       │         │                                 │
└─────────────────────────┘         └─────────────────────────────────┘

The diagram shows the two subscribers on separate store instances. If
both subscriptions are on one store instance, they share one connection and
both notifications reach both subscriptions (open defect D4).

INSERT event (type: payment.received)
    │
    ▼
┌─────────────────────────────────────────────────────────────────────┐
│ PostgreSQL Trigger                                                  │
│   pg_notify('public_bitemporal_events_...', payload)                │
│   pg_notify('public_bitemporal_events_..._payment_recv', payload)   │
└─────────────────────────────────────────────────────────────────────┘
    │                                           │
    ▼                                           ▼
┌─────────────────────────┐         ┌─────────────────────────────────┐
│ General Channel         │         │ Type-Specific Channel           │
│ {schema}_bitemporal_... │         │ {schema}_bitemporal_..._payment │
└─────────────────────────┘         └─────────────────────────────────┘
    │                                           │
    ▼                                           X (not listening)
┌─────────────────────────┐
│ Wildcard Subscriber     │         Exact Match Subscriber (order.created)
│ (order.*)               │         does NOT receive this notification
│                         │
│ Fetch event (DB query)  │
│ Pattern match:          │
│ order.* vs payment.recv │
│ ✗ No match - discard    │
└─────────────────────────┘
```

### Scalability Summary

| Concern | What the code does |
|---------|-------------------|
| High event volume | An exact subscription LISTENs on the type channel only. A wildcard or all-events subscription on the same store adds the general channel to the same connection. |
| Many event types | Each type has its own channel. Types that differ only by `.` and `_` share a channel; dispatch uses the original type from the payload. |
| Wildcard overhead | Every notification on the general channel causes a database fetch before the pattern is matched. |
| Network bandwidth | Not measured. |
| Database load | One fetch per notification received, matched or not. |

### Trade-off

The trigger sends two notifications per event. The type channel lets a store that has only exact subscriptions avoid receiving notifications for other types. The cost of the second notification has not been measured.

## Test Approach

### Performance Tests

The performance test suite (`peegeeq-benchmarking/src/test/java/dev/mars/peegeeq/bitemporal/PgBiTemporalEventStorePerformanceTest.java`) logs timings. Its only timing assertion is that batch append is faster than single appends. It records no throughput or latency figures:

| Test | Description | Approach |
|------|-------------|----------|
| Batch vs Single Append | Compare `appendBatch()` vs individual `append()` calls | Append 100 events each way, measure throughput |
| Subscription Throughput | Validate notification system under load | Rapidly append 50 events, measure time for subscriber to receive all |
| Concurrent Append | Validate connection pool handles concurrent writes | 10 threads × 10 events = 100 concurrent appends |
| Wildcard vs Exact Match | Compare subscription delivery latency | Append 50 events, compare delivery time for both subscription types |

### Wildcard Pattern Tests

The wildcard pattern test suite (`WildcardPatternComprehensiveTest.java`) validates 100 different patterns:

| Category | Count | Examples |
|----------|-------|----------|
| Trailing wildcards | 12 | `order.*`, `user.profile.*`, `a.b.c.d.*` |
| Leading wildcards | 12 | `*.created`, `*.user.deleted`, `*.a.b.c` |
| Middle wildcards | 12 | `order.*.completed`, `user.*.profile.updated` |
| Multiple wildcards | 15 | `*.*`, `*.order.*`, `user.*.*.action`, `*.*.*.*` |
| Deep nesting (5+ segments) | 10 | `a.b.c.d.e.*`, `*.a.b.c.d.e`, `a.*.b.*.c.*.d` |
| Dot-underscore collision | 10 | `my.channel` vs `my_channel`, `order.item.created` vs `order_item.created` |
| Numeric segments | 8 | `v1.*`, `*.v2.created`, `api.v3.*.response` |
| Underscore segments | 8 | `user_service.*`, `*.order_created`, `payment.*.card_declined` |
| Non-matching verification | 13 | Verify `order.*` does NOT match `orders.created`, `user.profile` |

Each test verifies:
1. Pattern matches expected event types (positive cases)
2. Pattern does NOT match non-matching event types (negative cases)
3. Subscriber receives correct events only (no duplicates, no missed events)

## Performance Expectations

No throughput, latency, memory or CPU figures are recorded for this subscription path. The figures that this section listed earlier had no recorded source and are removed. They are unmeasured.

### Resource Usage

| Resource | Usage | Notes |
|----------|-------|-------|
| LISTEN connections | One per `PgBiTemporalEventStore` instance | The store's `ReactiveNotificationHandler` opens one `PgConnection` for LISTEN. All subscriptions on that store share it. It is separate from the pool. |
| Pool connections | One fetch per received notification | The event retriever reads the full event for every notification before matching. |
| Wildcard matching | Split on `.` and compare segments | Runs once per wildcard subscription per received notification, after the fetch. |
