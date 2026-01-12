# PeeGeeQ Bi-Temporal Event Subscriptions

## Overview

This document describes the subscription model for reactive bi-temporal event notifications using PostgreSQL LISTEN/NOTIFY.

## Subscription Patterns

### Exact Match

Subscribe to a specific event type.

- Pattern: `order.created`
- PostgreSQL channel: `bitemporal_events_order_created` (dots converted to underscores)
- Filtering: None required - only matching events are received

### Wildcard Match

Subscribe to events matching a pattern. Wildcards use `*` to match one or more complete segments (separated by dots).

| Pattern | Matches | Does Not Match |
|---------|---------|----------------|
| `order.*` | `order.created`, `order.shipped` | `orders.created`, `order`, `payment.received` |
| `*.created` | `order.created`, `payment.created` | `order.shipped`, `created` |
| `order.*.completed` | `order.payment.completed`, `order.shipping.completed` | `order.created`, `order.completed` |
| `*.order.*` | `foo.order.bar`, `abc.order.xyz` | `order.created`, `order` |

- PostgreSQL channel: `bitemporal_events` (general channel)
- Filtering: Application-side pattern matching against event type

### All Events

Subscribe to all events by passing null as the event type.

- Pattern: `null`
- PostgreSQL channel: `bitemporal_events`
- Filtering: None - all events are received

## Channel Strategy

| Subscription Type | Channel | Rationale |
|-------------------|---------|-----------|
| Exact match | Type-specific (`bitemporal_events_<type>`) | Reduces message traffic - subscriber only receives relevant events |
| Wildcard | General (`bitemporal_events`) | PostgreSQL channels don't support wildcards - must receive all and filter |
| All events | General (`bitemporal_events`) | Need all events by definition |

## Dot-to-Underscore Conversion

Event types may contain dots (e.g., `order.created`), but PostgreSQL channel names cannot. The system converts dots to underscores when constructing channel names:

- Event type: `order.created`
- Channel name: `bitemporal_events_order_created`

This conversion happens in both:
1. Java code when setting up LISTEN
2. PostgreSQL trigger when sending NOTIFY

### Collision Handling

Event types `my.channel` and `my_channel` both map to channel `bitemporal_events_my_channel`. The system handles this by:
- Including the original event type in the notification payload
- Matching subscriptions against the original event type, not the channel name

## PostgreSQL Trigger

The trigger sends notifications to two channels for each event:

1. `bitemporal_events` - General channel for wildcard and all-events subscriptions
2. `bitemporal_events_<event_type>` - Type-specific channel for exact match subscriptions

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

5. **No duplicate events**
   - Subscribe to `test.event`
   - Append: 1 event of type `test.event`
   - Expect: 1 event received (not 2)

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

The subscription system correctly routes events based on subscription type:

- **Exact match subscriptions**: Listen ONLY on the type-specific channel (e.g., `bitemporal_events_order_created`)
- **Wildcard subscriptions**: Listen ONLY on the general channel (`bitemporal_events`) with application-side filtering
- **All events subscriptions**: Listen ONLY on the general channel (`bitemporal_events`)

This ensures:
- No duplicate event delivery
- Efficient message routing (exact match subscribers don't receive unrelated events)
- Proper wildcard pattern matching

## How It Works

### Channel Architecture

The PostgreSQL trigger on the bi-temporal events table sends notifications to TWO channels for every event:

1. **General channel**: `{schema}_bitemporal_events_{table}` - receives ALL events for that table
2. **Type-specific channel**: `{schema}_bitemporal_events_{table}_{event_type}` - receives only that type

> **Note**: Channel names are schema-qualified to support multi-tenant deployments. For example,
> `public_bitemporal_events_bitemporal_event_log` for the default schema.

### Subscription Routing

| Subscription | Listens On | Filtering |
|--------------|------------|-----------||
| `order.created` (exact) | `public_bitemporal_events_bitemporal_event_log_order_created` only | None |
| `order.*` (wildcard) | `public_bitemporal_events_bitemporal_event_log` only | In-memory pattern match |
| `null` (all) | `public_bitemporal_events_bitemporal_event_log` only | None |

## Scalability

### Why Scalability Is Not Impacted

**1. Database-side: Minimal overhead**

The trigger does two `pg_notify()` calls per insert:

```sql
-- Channel names are schema-qualified: {schema}_bitemporal_events_{table}
pg_notify('public_bitemporal_events_bitemporal_event_log', payload);
pg_notify('public_bitemporal_events_bitemporal_event_log_order_created', payload);
```

This is negligible - PostgreSQL NOTIFY is extremely lightweight (in-memory pub/sub).

**2. Network: Traffic reduction for exact match**

Exact match subscribers only receive notifications for their specific event type. If you have 1000 event types and subscribe to `order.created`, you receive 1/1000th of the traffic compared to listening on the general channel.

**3. Wildcard filtering: In-memory, not network**

For wildcard subscriptions like `order.*`:
- The notification payload is tiny (~200 bytes): just event ID and type
- Filtering happens in the JVM BEFORE fetching the full event
- Only matching events trigger a database fetch

```
[PostgreSQL] ──notification──> [JVM Filter] ──fetch if match──> [Database]
                                    │
                                    └── discard if no match (no DB call)
```

**4. No wasted database queries**

The notification contains the event type. Wildcard matching happens on this string:
- Match: fetch full event from database
- No match: discard immediately (zero database overhead)

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
│ Pattern match:          │         │ No filtering needed             │
│ order.* vs order.created│         │                                 │
│ ✓ Match - fetch event   │         │ Fetch event                     │
└─────────────────────────┘         └─────────────────────────────────┘

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
│ Pattern match:          │
│ order.* vs payment.recv │
│ ✗ No match - discard    │
│ (NO database fetch)     │
└─────────────────────────┘
```

### Scalability Summary

| Concern | How It's Addressed |
|---------|-------------------|
| High event volume | Exact match subscribers only receive their events via type-specific channel |
| Many event types | Each type has its own channel - no cross-talk |
| Wildcard overhead | Filtering is string comparison in memory (~nanoseconds), not database queries |
| Network bandwidth | Notifications are tiny (~200 bytes); full events only fetched when needed |
| Database load | No wasted queries - only matching events trigger fetches |

### Trade-off

The only cost is the trigger sending two notifications per event. This is acceptable because:
- `pg_notify` is in-memory, not disk I/O
- It enables exact-match subscribers to avoid all filtering overhead
- The alternative (only general channel) would require ALL subscribers to filter ALL events

## Test Approach

### Performance Tests

The performance test suite (`PgBiTemporalEventStorePerformanceTest.java`) validates throughput and latency characteristics:

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

### Throughput Benchmarks

Based on test results with PostgreSQL in Testcontainers:

| Operation | Expected Throughput | Notes |
|-----------|---------------------|-------|
| Single append | ~100-200 events/sec | Individual transactions, includes commit overhead |
| Batch append (100 events) | ~1,000-2,000 events/sec | Single transaction, 5-10x faster than single |
| Concurrent append (10 threads) | ~500-800 events/sec | Connection pool contention, still efficient |
| Subscription delivery | <50ms latency | From append to subscriber callback |

### Latency Characteristics

| Component | Typical Latency | Description |
|-----------|-----------------|-------------|
| `pg_notify` | <1ms | In-memory pub/sub, extremely fast |
| Notification delivery | 1-5ms | PostgreSQL to JVM via LISTEN |
| Wildcard pattern match | <1μs | Simple string comparison in memory |
| Event fetch (on match) | 5-20ms | Database round-trip for full event |

### Scaling Factors

| Factor | Impact | Mitigation |
|--------|--------|------------|
| Number of event types | Minimal | Each type has dedicated channel |
| Number of subscribers | Linear | Each subscriber is independent connection |
| Event payload size | Affects fetch time | Notification payload is always small (~200 bytes) |
| Wildcard complexity | Negligible | Pattern matching is O(n) where n = segments |

### Resource Usage

| Resource | Typical Usage | Notes |
|----------|---------------|-------|
| PostgreSQL connections | 1 per subscriber + pool | LISTEN requires dedicated connection |
| Memory per subscriber | ~10KB | Vert.x reactive connection overhead |
| CPU for filtering | <0.1% | String comparison is trivial |

### Production Recommendations

1. **Connection pool sizing**: 10-20 connections for typical workloads
2. **Batch size**: 50-100 events per batch for optimal throughput
3. **Subscriber count**: Hundreds of subscribers are feasible per JVM
4. **Event type count**: Thousands of distinct types are supported efficiently
