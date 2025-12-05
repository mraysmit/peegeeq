# Server-Side Filtering Technical Plan

## Executive Summary

This document outlines the technical plan for adding **server-side (database-level) message filtering** to PeeGeeQ. Currently, PeeGeeQ filters messages at the client level after fetching all messages from the database. This enhancement will push filtering to PostgreSQL, reducing network traffic and client CPU usage for high-volume scenarios.

## Problem Statement

### Current Architecture: Client-Side Filtering

```
┌─────────────────┐    SELECT * WHERE topic='orders'    ┌──────────────┐
│   PostgreSQL    │ ─────────────────────────────────▶  │   Consumer   │
│  queue_messages │     (fetches ALL messages)          │   (client)   │
└─────────────────┘                                     └──────┬───────┘
                                                               │
                                                    Filter by headers
                                                    (type='created')
                                                               │
                                                               ▼
                                                     ┌─────────────────┐
                                                     │ Process matching│
                                                     │    messages     │
                                                     └─────────────────┘
```

**Problems with client-side filtering:**
1. **Network overhead**: All messages fetched regardless of filter criteria
2. **CPU overhead**: Client processes messages only to discard them
3. **Scalability**: Doesn't scale well with high message volumes
4. **Resource waste**: Database locks messages that may never be processed

### Proposed Architecture: Server-Side Filtering

```
┌─────────────────┐   SELECT * WHERE topic='orders'     ┌──────────────┐
│   PostgreSQL    │   AND headers->>'type'='created'    │   Consumer   │
│  queue_messages │ ─────────────────────────────────▶  │   (client)   │
└─────────────────┘   (fetches ONLY matching msgs)      └──────┬───────┘
                                                               │
                                                     Process all messages
                                                     (already filtered)
                                                               │
                                                               ▼
                                                     ┌─────────────────┐
                                                     │ Process matching│
                                                     │    messages     │
                                                     └─────────────────┘
```

## Comparison with Industry Standards

### How Kafka Handles Filtering

| Aspect | Kafka | PeeGeeQ (Current) | PeeGeeQ (Proposed) |
|--------|-------|-------------------|---------------------|
| **Primary filter mechanism** | Topic subscription | Client-side predicates | Server-side SQL + client predicates |
| **Where filtering happens** | Broker (server) | Consumer (client) | Database (server) |
| **Network efficiency** | High - only subscribed topics delivered | Low - all messages fetched | High - only matching messages fetched |
| **Sub-topic filtering** | Not built-in (use headers + client filter) | Headers + client filter | Headers + SQL JSONB filter |

### How Other Systems Handle Filtering

| System | Filtering Approach |
|--------|-------------------|
| **AWS SQS** | No content filtering (use separate queues) |
| **AWS SNS** | Server-side filter policies on subscriptions |
| **RabbitMQ** | Routing keys + exchange bindings (server-side) |
| **Redis Streams** | Consumer groups per stream, no content filtering |
| **Google Pub/Sub** | Server-side attribute filtering |

## Current Implementation Analysis

### Queue Name vs Topic in PeeGeeQ

In PeeGeeQ, **queue name and topic are the same concept**. When you create a producer:

```java
MessageProducer<String> producer = factory.createProducer("my-queue", String.class);
```

The string `"my-queue"` becomes the `topic` column value in the database. There's no separate "topic" parameter in the send API.

### Current Filtering Mechanism

**Producer** - categorizes messages via headers:
```java
producer.send(payload, Map.of("type", "order-created"));
```

**Consumer** - filters using `MessageFilter` predicates:
```java
group.addConsumer("consumer-1", handler, MessageFilter.byHeader("type", "order-created"));
```

### Code Flow Analysis

1. **PgNativeQueueConsumer.processAvailableMessages()** (lines 395-408):
```sql
WITH c AS (
    SELECT id FROM queue_messages
    WHERE topic = $1 AND status = 'AVAILABLE' AND visible_at <= now()
    ORDER BY priority DESC, created_at ASC
    LIMIT $2
    FOR UPDATE SKIP LOCKED
)
UPDATE queue_messages q
SET status = 'LOCKED', lock_until = now() + make_interval(secs => $3)
FROM c WHERE q.id = c.id
RETURNING q.id, q.payload, q.headers, ...
```

2. **PgNativeConsumerGroup.distributeMessage()** (lines 323-341):
   - Applies `groupFilter` predicate (client-side)
   - Finds eligible consumers whose filters accept the message
   - Routes to selected consumer

3. **Same pattern in OutboxConsumer** (lines 258-269)

## Impact Assessment

### Module-by-Module Analysis

| Module | Impact Level | Changes Required |
|--------|--------------|------------------|
| **peegeeq-api** | Low | Add `ServerSideFilter` class |
| **peegeeq-native** | Medium | Extend `ConsumerConfig`, modify SQL in `PgNativeQueueConsumer` |
| **peegeeq-outbox** | Medium | Modify SQL in `OutboxConsumer` |
| **peegeeq-db** | None | No changes needed |
| **peegeeq-bitemporal** | None | No changes needed |
| **peegeeq-migrations** | Optional | Add JSONB index for performance |

### Files to Modify

**peegeeq-api:**
- `NEW: api/messaging/ServerSideFilter.java` - Filter expression API

**peegeeq-native:**
- `pgqueue/ConsumerConfig.java` - Add serverSideFilter field
- `pgqueue/PgNativeQueueConsumer.java` - Modify SQL query
- `pgqueue/PgNativeQueueFactory.java` - Pass filter through factory

**peegeeq-outbox:**
- `outbox/OutboxConsumer.java` - Modify SQL query
- `outbox/OutboxFactory.java` - Pass filter through factory

**peegeeq-migrations (optional):**
- `NEW: V00X__Add_Headers_Index.sql` - GIN index for JSONB

## Technical Design

### 1. ServerSideFilter API (peegeeq-api)

```java
package dev.mars.peegeeq.api.messaging;

/**
 * Represents a filter that can be translated to SQL WHERE clauses.
 * Used for server-side (database-level) message filtering.
 */
public class ServerSideFilter {

    public enum Operator {
        EQUALS,
        IN,
        NOT_EQUALS,
        LIKE,
        AND,
        OR
    }

    private final Operator operator;
    private final String headerKey;
    private final Object value;  // String, Set<String>, or List<ServerSideFilter>

    // Factory methods
    public static ServerSideFilter headerEquals(String key, String value);
    public static ServerSideFilter headerIn(String key, Set<String> values);
    public static ServerSideFilter headerNotEquals(String key, String value);
    public static ServerSideFilter headerLike(String key, String pattern);
    public static ServerSideFilter and(ServerSideFilter... filters);
    public static ServerSideFilter or(ServerSideFilter... filters);

    /**
     * Generates SQL condition fragment.
     * @param paramOffset Starting parameter index (e.g., 4 for $4)
     * @return SQL fragment like "headers->>'type' = $4"
     */
    public String toSqlCondition(int paramOffset);

    /**
     * Returns parameters to bind to the SQL query.
     */
    public List<Object> getParameters();

    /**
     * Returns the number of parameters this filter uses.
     */
    public int getParameterCount();
}
```

### 2. Extended ConsumerConfig (peegeeq-native)

```java
public class ConsumerConfig {
    // Existing fields
    private final ConsumerMode mode;
    private final Duration pollingInterval;
    private final boolean enableNotifications;
    private final int batchSize;
    private final int consumerThreads;

    // NEW: Server-side filter
    private final ServerSideFilter serverSideFilter;

    public static class Builder {
        // Existing builder methods...

        // NEW
        public Builder serverSideFilter(ServerSideFilter filter) {
            this.serverSideFilter = filter;
            return this;
        }
    }

    public ServerSideFilter getServerSideFilter() {
        return serverSideFilter;
    }

    public boolean hasServerSideFilter() {
        return serverSideFilter != null;
    }
}
```

### 3. Modified SQL Query (PgNativeQueueConsumer)

**Before:**
```sql
WITH c AS (
    SELECT id FROM queue_messages
    WHERE topic = $1 AND status = 'AVAILABLE' AND visible_at <= now()
    ORDER BY priority DESC, created_at ASC
    LIMIT $2
    FOR UPDATE SKIP LOCKED
)
...
```

**After (with server-side filter):**
```sql
WITH c AS (
    SELECT id FROM queue_messages
    WHERE topic = $1 AND status = 'AVAILABLE' AND visible_at <= now()
      AND headers->>'type' = $4  -- Dynamic filter condition
    ORDER BY priority DESC, created_at ASC
    LIMIT $2
    FOR UPDATE SKIP LOCKED
)
...
```

### 4. Database Index (Optional Migration)

```sql
-- V00X__Add_Headers_Index.sql

-- Option 1: GIN index for general JSONB queries
CREATE INDEX CONCURRENTLY idx_queue_messages_headers_gin
ON queue_messages USING GIN (headers);

-- Option 2: B-tree index for specific header key (more efficient for single key)
CREATE INDEX CONCURRENTLY idx_queue_messages_headers_type
ON queue_messages ((headers->>'type'));

-- Same for outbox table
CREATE INDEX CONCURRENTLY idx_outbox_headers_gin
ON outbox USING GIN (headers);
```

## Usage Examples

### Example 1: Simple Header Filter

```java
// Create a consumer that only receives "order-created" messages at the database level
ConsumerConfig config = ConsumerConfig.builder()
    .mode(ConsumerMode.HYBRID)
    .serverSideFilter(ServerSideFilter.headerEquals("type", "order-created"))
    .build();

MessageConsumer<OrderEvent> consumer = factory.createConsumer(
    "order-events", OrderEvent.class, config);

consumer.subscribe(message -> {
    // Only receives messages where headers->>'type' = 'order-created'
    processOrder(message.getPayload());
    return CompletableFuture.completedFuture(null);
});
```

### Example 2: Multiple Values (IN clause)

```java
// Consumer receives messages for multiple regions
ConsumerConfig config = ConsumerConfig.builder()
    .serverSideFilter(ServerSideFilter.headerIn("region", Set.of("US", "EU", "APAC")))
    .build();

MessageConsumer<Event> consumer = factory.createConsumer("events", Event.class, config);
```

### Example 3: Combined Filters (AND)

```java
// Consumer receives high-priority order events from US region
ServerSideFilter filter = ServerSideFilter.and(
    ServerSideFilter.headerEquals("type", "order"),
    ServerSideFilter.headerEquals("priority", "HIGH"),
    ServerSideFilter.headerEquals("region", "US")
);

ConsumerConfig config = ConsumerConfig.builder()
    .serverSideFilter(filter)
    .build();
```

### Example 4: Combined with Client-Side Filter

```java
// Server-side: coarse filter (type = order)
// Client-side: fine-grained filter (amount > 1000)
ConsumerConfig config = ConsumerConfig.builder()
    .serverSideFilter(ServerSideFilter.headerEquals("type", "order"))
    .build();

ConsumerGroup<OrderEvent> group = factory.createConsumerGroup(
    "HighValueOrders", "order-events", OrderEvent.class);

// Client-side filter for complex logic not expressible in SQL
group.addConsumer("high-value-processor", handler,
    message -> message.getPayload().getAmount() > 1000);

group.start();
```

## Performance Considerations

### When to Use Server-Side Filtering

| Scenario | Recommendation |
|----------|----------------|
| High message volume, low match rate | **Use server-side** - reduces network/CPU |
| Low message volume | Either works - client-side is simpler |
| Complex filter logic (regex, payload inspection) | **Use client-side** - more flexible |
| Simple header equality checks | **Use server-side** - most efficient |
| Multiple consumers with different filters | **Use server-side** - each gets only their messages |

### Index Strategy

| Filter Pattern | Recommended Index |
|----------------|-------------------|
| Single header key equality | B-tree on `(headers->>'key')` |
| Multiple header keys | GIN on `headers` |
| Header key existence check | GIN on `headers` |
| Pattern matching (LIKE) | GIN with `gin_trgm_ops` |

### Benchmark Expectations

| Metric | Client-Side | Server-Side (no index) | Server-Side (with index) |
|--------|-------------|------------------------|--------------------------|
| Messages fetched | All | Matching only | Matching only |
| Network transfer | High | Low | Low |
| DB CPU | Low | Medium | Low |
| Client CPU | High | Low | Low |
| Query latency | Fast | Medium | Fast |

## Backward Compatibility

### Guarantees

1. **100% backward compatible** - `serverSideFilter` is optional (null by default)
2. **Existing code unchanged** - current client-side filtering continues to work
3. **Combinable** - can use both server-side and client-side filters together
4. **No migration required** - works without the optional index (just slower)

### Migration Path

```java
// Before (client-side only)
group.addConsumer("id", handler, MessageFilter.byHeader("type", "order"));

// After (server-side + optional client-side)
ConsumerConfig config = ConsumerConfig.builder()
    .serverSideFilter(ServerSideFilter.headerEquals("type", "order"))
    .build();

MessageConsumer<T> consumer = factory.createConsumer("topic", Type.class, config);
consumer.subscribe(handler);
```

## Implementation Checklist

### Phase 1: Core API
- [ ] Create `ServerSideFilter` class in peegeeq-api
- [ ] Add unit tests for SQL generation
- [ ] Document the API with Javadoc

### Phase 2: Native Queue Integration
- [ ] Extend `ConsumerConfig` with `serverSideFilter` field
- [ ] Modify `PgNativeQueueConsumer.processAvailableMessages()` SQL
- [ ] Update `PgNativeQueueFactory.createConsumer()` to pass config
- [ ] Add integration tests

### Phase 3: Outbox Integration
- [ ] Modify `OutboxConsumer.processAvailableMessagesReactive()` SQL
- [ ] Update `OutboxFactory.createConsumer()` to pass config
- [ ] Add integration tests

### Phase 4: Performance Optimization (Optional)
- [ ] Create migration for JSONB index
- [ ] Benchmark with and without index
- [ ] Document performance characteristics

### Phase 5: Documentation
- [ ] Update README with server-side filtering examples
- [ ] Add to API documentation
- [ ] Create migration guide for existing users

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| SQL injection via header keys | Validate header keys against allowlist pattern |
| Performance regression without index | Document index recommendations, log warnings |
| Complex filter expressions | Limit nesting depth, validate at build time |
| JSONB null handling | Use COALESCE or explicit null checks in SQL |

## Future Enhancements

1. **Subscription-based filtering** - Define filters at subscription creation time
2. **Filter caching** - Cache compiled SQL for repeated filter patterns
3. **Filter statistics** - Track filter hit rates for optimization
4. **Dynamic filter updates** - Change filters without consumer restart
5. **Filter expressions in headers** - Allow producers to suggest routing

## Conclusion

Adding server-side filtering to PeeGeeQ is a **low-risk, high-value enhancement** that:

- Aligns with industry best practices (Kafka topics, SNS filter policies)
- Significantly improves performance for filtered consumption patterns
- Maintains full backward compatibility
- Requires changes to only 2 modules (native + outbox)
- Can be implemented incrementally

The change is **isolated** and does not affect the database schema, bitemporal functionality, or existing client-side filtering mechanisms.


