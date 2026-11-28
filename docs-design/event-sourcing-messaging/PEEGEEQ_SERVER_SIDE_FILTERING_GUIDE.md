# Server-Side Filtering: Technical Design & Implementation Guide

**Version:** 1.0
**Last Updated:** 2025-12-23
**Corrected:** 2026-11-21 against `18c27376`: NOT_EQUALS includes messages without the header (open defect D3); header key regex; consumer groups have no filter support; tenant-schema `outbox` and `queue_messages` have no headers index; filter parameter numbering; "Production Ready" label and unbacked benchmark numbers removed; no REST exposure.

## Executive Summary

This document provides a comprehensive technical design and implementation guide for **server-side (database-level) message filtering** in PeeGeeQ. This feature enables PostgreSQL-level filtering of messages based on JSONB headers, significantly reducing network traffic and client CPU usage for high-volume scenarios.

### Key Features

- **Database-level filtering** using PostgreSQL JSONB operators
- **Flexible filter expressions** supporting EQUALS, IN, NOT_EQUALS, LIKE, AND, OR
- **Key existence checks**: EQUALS, IN and LIKE exclude messages without the header. NOT_EQUALS includes them (see [NOT_EQUALS and missing headers](#not_equals-and-missing-headers-open-defect-d3)).
- **100% backward compatible** - optional enhancement to existing API
- **GIN indexes on `headers`** exist for the Flyway tables only; tenant-schema `outbox` and `queue_messages` have none (see [Database Indexes](#4-database-indexes))
- **Test coverage** - 29 tests across unit and integration levels
- **Java API only**: `ServerSideFilter` is set through `ConsumerConfig` or `OutboxConsumerConfig`. The REST API (`peegeeq-rest`) does not expose it.
- **Plain consumers only**: consumer groups have no server-side filter support

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Technical Design](#technical-design)
3. [Implementation Guide](#implementation-guide)
4. [Usage Examples](#usage-examples)
5. [Performance Optimization](#performance-optimization)
6. [Testing Strategy](#testing-strategy)
7. [Best Practices](#best-practices)
8. [Troubleshooting](#troubleshooting)

---

## Architecture Overview

### Filtering Approaches: Client-Side vs Server-Side

#### Client-Side Filtering (Original Approach)

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

**Limitations of client-side filtering:**
1. **Network overhead**: All messages fetched regardless of filter criteria
2. **CPU overhead**: Client processes messages only to discard them
3. **Scalability**: Doesn't scale well with high message volumes
4. **Resource waste**: Database locks messages that may never be processed

#### Server-Side Filtering (Enhanced Approach)

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

**Benefits of server-side filtering:**
1. **Reduced network traffic**: Only matching messages transferred
2. **Lower client CPU**: No need to process and discard messages
3. **Better scalability**: Database handles filtering efficiently
4. **Optimized locking**: Only relevant messages are locked

### Industry Comparison

#### Kafka vs PeeGeeQ Filtering

| Aspect | Kafka | PeeGeeQ (Client-Side) | PeeGeeQ (Server-Side) |
|--------|-------|-------------------|---------------------|
| **Primary filter mechanism** | Topic subscription | Client-side predicates | Server-side SQL + client predicates |
| **Where filtering happens** | Broker (server) | Consumer (client) | Database (server) |
| **Network efficiency** | High - only subscribed topics delivered | Low - all messages fetched | High - only matching messages fetched |
| **Sub-topic filtering** | Not built-in (use headers + client filter) | Headers + client filter | Headers + SQL JSONB filter |
| **Filter complexity** | Simple (topic only) | High (any Java predicate) | Medium (SQL-expressible conditions) |

#### Other Message Queue Systems

| System | Filtering Approach | Comparison to PeeGeeQ |
|--------|-------------------|----------------------|
| **AWS SQS** | No content filtering (use separate queues) | PeeGeeQ more flexible |
| **AWS SNS** | Server-side filter policies on subscriptions | Similar to PeeGeeQ server-side |
| **RabbitMQ** | Routing keys + exchange bindings (server-side) | PeeGeeQ more dynamic |
| **Redis Streams** | Consumer groups per stream, no content filtering | PeeGeeQ more powerful |
| **Google Pub/Sub** | Server-side attribute filtering | Similar to PeeGeeQ server-side |
| **Azure Service Bus** | SQL-like filter expressions on subscriptions | Very similar to PeeGeeQ |

---

## Technical Design

### Core Components

The server-side filtering implementation consists of three main components:

1. **ServerSideFilter API** (`peegeeq-api`) - Filter expression builder and SQL generator
2. **ConsumerConfig Extensions** (`peegeeq-native`, `peegeeq-outbox`) - Configuration support
3. **SQL Query Modifications** - Dynamic query building with filter conditions
4. **Database Indexes** - GIN indexes for JSONB performance optimization

### Component Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      peegeeq-api                            │
│  ┌───────────────────────────────────────────────────────┐  │
│  │              ServerSideFilter                         │  │
│  │  - Operator enum (EQUALS, IN, NOT_EQUALS, LIKE, ...)│  │
│  │  - Factory methods (headerEquals, headerIn, ...)     │  │
│  │  - toSqlCondition(paramOffset) → SQL string          │  │
│  │  - getParameters() → List<Object>                    │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                            ▲
                            │ uses
                            │
        ┌───────────────────┴────────────────────┐
        │                                        │
┌───────┴──────────────┐              ┌─────────┴────────────┐
│   peegeeq-native     │              │   peegeeq-outbox     │
│  ┌────────────────┐  │              │  ┌─────────────────┐ │
│  │ConsumerConfig  │  │              │  │OutboxConsumer   │ │
│  │+ serverSide    │  │              │  │Config           │ │
│  │  Filter        │  │              │  │+ serverSide     │ │
│  └────────────────┘  │              │  │  Filter         │ │
│  ┌────────────────┐  │              │  └─────────────────┘ │
│  │PgNativeQueue   │  │              │  ┌─────────────────┐ │
│  │Consumer        │  │              │  │OutboxConsumer   │ │
│  │- builds SQL    │  │              │  │- builds SQL     │ │
│  └────────────────┘  │              │  └─────────────────┘ │
└──────────────────────┘              └──────────────────────┘
```

### 1. ServerSideFilter API (peegeeq-api)

The `ServerSideFilter` class provides a fluent API for building SQL filter expressions.

#### Class Structure

```java
package dev.mars.peegeeq.api.messaging;

/**
 * Server-side filter for database-level message filtering using JSONB headers.
 *
 * <p>Generates SQL WHERE clauses that filter messages at the PostgreSQL level,
 * reducing network traffic and client CPU usage.
 *
 * <p><b>Example:</b>
 * <pre>
 * // Simple equality filter
 * ServerSideFilter filter = ServerSideFilter.headerEquals("type", "order-created");
 *
 * // Complex compound filter
 * ServerSideFilter filter = ServerSideFilter.and(
 *     ServerSideFilter.headerEquals("type", "order"),
 *     ServerSideFilter.headerIn("region", Set.of("US", "EU"))
 * );
 * </pre>
 */
public class ServerSideFilter {

    public enum Operator {
        EQUALS,        // headers->>'key' = value
        IN,            // headers->>'key' IN (value1, value2, ...)
        NOT_EQUALS,    // NOT headers ? 'key' OR headers->>'key' != value
        LIKE,          // headers->>'key' LIKE pattern
        AND,           // (filter1) AND (filter2) AND ...
        OR             // (filter1) OR (filter2) OR ...
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
     * Generates SQL condition fragment with NULL-safe key existence checks.
     * @param paramOffset Starting parameter index (e.g., 4 for $4)
     * @return SQL fragment like "(headers ? 'type' AND headers->>'type' = $4)"
     */
    public String toSqlCondition(int paramOffset);

    /**
     * Returns parameters to bind to the SQL query in order.
     */
    public List<Object> getParameters();

    /**
     * Returns the number of parameters this filter uses.
     */
    public int getParameterCount();
}
```

#### Key Design Decisions

1. **Key Existence Checks**: EQUALS, IN and LIKE require the key to exist:
   ```sql
   -- Instead of: headers->>'type' = $n
   -- Generates:   (headers ? 'type' AND headers->>'type' = $n)
   ```
   Messages without the header key are excluded by these three operators.

   NOT_EQUALS uses the opposite form (`ServerSideFilter.java:191`):
   ```sql
   (NOT headers ? 'type' OR headers->>'type' != $n)
   ```
   Messages without the header key are **included** by NOT_EQUALS.

   #### NOT_EQUALS and missing headers (open defect D3)

   The code includes messages that lack the header when the filter is NOT_EQUALS. Earlier versions of this guide said they were excluded. Which behaviour is intended has not been decided. It is recorded as open defect D3 in `docs-design/tasks/tasks.md`. Until D3 is decided, a consumer that must exclude messages without the header must combine the filter with an EQUALS, IN or LIKE condition on the same key, or check in its handler.

2. **Header Key Validation**: Header keys must match pattern `^[a-zA-Z][a-zA-Z0-9_-]*$` (`ServerSideFilter.VALID_HEADER_KEY`). The key must start with a letter. The remaining characters may be letters, digits, `_` or `-`. The key is written into the SQL text, so this check prevents SQL injection. Values are always bound parameters.

3. **Immutable Design**: All filter objects are immutable and thread-safe.

4. **Composable**: Filters can be nested arbitrarily using AND/OR operators.

### 2. ConsumerConfig Extensions

Both `ConsumerConfig` (native) and `OutboxConsumerConfig` (outbox) have been extended with server-side filter support.

#### Native Queue ConsumerConfig

```java
public class ConsumerConfig {
    // Existing fields
    private final ConsumerMode mode;
    private final Duration pollingInterval;
    private final boolean enableNotifications;
    private final int batchSize;
    private final int consumerThreads;

    // Server-side filter (optional)
    private final ServerSideFilter serverSideFilter;

    public static class Builder {
        // Existing builder methods...

        /**
         * Sets the server-side filter for database-level message filtering.
         * @param filter The filter to apply, or null for no filtering
         */
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

#### Outbox ConsumerConfig

```java
public class OutboxConsumerConfig {
    // Existing fields
    private final Duration pollingInterval;
    private final int batchSize;
    private final int consumerThreads;
    private final int maxRetries;

    // Server-side filter (optional)
    private final ServerSideFilter serverSideFilter;

    public static class Builder {
        // Existing builder methods...

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

### 3. SQL Query Modifications

The SQL queries in both `PgNativeQueueConsumer` and `OutboxConsumer` are dynamically built when a server-side filter is present.

**Parameter numbering.** The filter's first parameter is not always `$4`. Each consumer passes the next free parameter number to `toSqlCondition(...)`:

- `PgNativeQueueConsumer`: `$1` topic, `$2` batch size, `$3` lock seconds. Then an optional start-position value (`id >= $n` or `created_at >= $n`) and an optional exclusion group name (`queue_message_group_exclusions`). The filter starts at `4 + (number of those optional parameters)`, so at `$4`, `$5` or `$6`.
- `OutboxConsumer`: `$1` processed time, `$2` topic, `$3` batch size. Then the consumer group name when subscription bounds are enforced. The filter starts at `$4` or `$5`. A scan-position parameter (`outbox_message.id > $n`) may follow the filter parameters.

The SQL examples in this guide show `$4`, which is the case with no optional parameters. The SQL blocks below are simplified; the current statements also select `delivery_latency_ms`, and the outbox statement uses the alias `outbox_message` and the schema-qualified table name.

#### Native Queue SQL (PgNativeQueueConsumer)

**Without Filter:**
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
FROM c
WHERE q.id = c.id
RETURNING q.id, q.payload, q.headers, q.correlation_id, q.message_group, q.retry_count, q.created_at
```

**With Filter (e.g., `headerEquals("type", "order")`):**
```sql
WITH c AS (
    SELECT id FROM queue_messages
    WHERE topic = $1 AND status = 'AVAILABLE' AND visible_at <= now()
      AND (headers ? 'type' AND headers->>'type' = $4)  -- Dynamic filter condition
    ORDER BY priority DESC, created_at ASC
    LIMIT $2
    FOR UPDATE SKIP LOCKED
)
UPDATE queue_messages q
SET status = 'LOCKED', lock_until = now() + make_interval(secs => $3)
FROM c
WHERE q.id = c.id
RETURNING q.id, q.payload, q.headers, q.correlation_id, q.message_group, q.retry_count, q.created_at
```

#### Outbox SQL (OutboxConsumer)

**Without Filter:**
```sql
UPDATE outbox
SET status = 'PROCESSING', processed_at = $1
WHERE id IN (
    SELECT id FROM outbox
    WHERE topic = $2 AND status = 'PENDING'
    ORDER BY created_at ASC
    LIMIT $3
    FOR UPDATE SKIP LOCKED
)
RETURNING id, payload, headers, correlation_id, message_group, created_at
```

**With Filter (e.g., `headerEquals("priority", "HIGH")`):**
```sql
UPDATE outbox
SET status = 'PROCESSING', processed_at = $1
WHERE id IN (
    SELECT id FROM outbox
    WHERE topic = $2 AND status = 'PENDING'
      AND (headers ? 'priority' AND headers->>'priority' = $4)  -- Dynamic filter condition
    ORDER BY created_at ASC
    LIMIT $3
    FOR UPDATE SKIP LOCKED
)
RETURNING id, payload, headers, correlation_id, message_group, created_at
```

#### Implementation Pattern

```java
// Simplified from PgNativeQueueConsumer (claim query construction)
int filterParameterOffset = 4 + startPositionParameters.size(); // not always 4
String filterCondition = filter.toSqlCondition(filterParameterOffset);
// ... the SQL text inserts startPositionCondition, then "AND " + filterCondition ...

List<Object> allParameters = new ArrayList<>();
allParameters.add(topic);
allParameters.add(admittedCapacity);
allParameters.add(lockDurationSeconds);
allParameters.addAll(startPositionParameters); // optional start position and exclusion group
allParameters.addAll(filter.getParameters());
Tuple params = Tuple.from(allParameters);
```

### 4. Database Indexes

GIN (Generalized Inverted Index) indexes on `headers` exist for some tables only.

| Table | Headers GIN index |
|-------|-------------------|
| Flyway `outbox` | Yes (`idx_outbox_headers_gin`, V001) |
| Flyway `queue_messages` | Yes (`idx_queue_messages_headers_gin`, V001) |
| Flyway `bitemporal_event_log` | Yes (`idx_bitemporal_headers_gin`, V001) |
| Tenant-schema `{schema}.outbox` (template `base/04a-core-table-outbox.sql`) | **No** |
| Tenant-schema `{schema}.queue_messages` (template `base/04b-core-table-queue-messages.sql`) | **No** |
| Tenant-schema `queue_template` and `event_store_template` | Yes (`base/07p`, `base/07o`) |
| Per-queue tables and per-store event tables | Yes (`queue/02f`, `eventstore/02j`) |

Both consumers filter `outbox` or `queue_messages`. In a tenant schema created from the templates, those two tables have no headers index, so a filtered claim query cannot use one. Add the index by hand if it is needed there.

#### Index Definitions

**Baseline Migration (V001__Create_Base_Tables.sql):**
```sql
-- GIN index for JSONB headers queries (server-side filtering)
CREATE INDEX idx_outbox_headers_gin ON outbox USING GIN(headers);

-- GIN index for JSONB headers queries (server-side filtering)
CREATE INDEX idx_queue_messages_headers_gin ON queue_messages USING GIN(headers);

-- GIN index for JSONB headers queries
CREATE INDEX idx_bitemporal_headers_gin ON bitemporal_event_log USING GIN(headers);
```

**Template Files:**
```sql
-- peegeeq-db/src/main/resources/db/templates/base/07p-queue-index-headers.sql
CREATE INDEX idx_queue_template_headers_gin ON {schema}.queue_template USING GIN(headers);

-- peegeeq-db/src/main/resources/db/templates/queue/02f-index-headers.sql
CREATE INDEX IF NOT EXISTS "idx_{queueName}_headers_gin"
    ON {schema}."{queueName}" USING GIN(headers);

-- peegeeq-db/src/main/resources/db/templates/eventstore/02j-index-headers.sql
CREATE INDEX IF NOT EXISTS "idx_{tableName}_headers_gin"
    ON {schema}."{tableName}" USING GIN(headers);

-- No template creates a headers index on {schema}.outbox or {schema}.queue_messages.
```

#### Index Performance Characteristics

| Index Type | Use Case | Performance | Storage Overhead |
|------------|----------|-------------|------------------|
| **GIN on headers** | Multiple header keys, existence checks | Excellent for `?` and `->>` operators | Medium (2-3x column size) |
| **B-tree on (headers->>'key')** | Single specific header key | Excellent for equality/range | Low (1x extracted values) |
| **No index** | Low volume or infrequent queries | Poor for filtering | None |

#### When to Add Additional Indexes

Consider adding B-tree indexes for specific header keys if:
- You frequently filter on a single header key (e.g., `type`, `region`)
- The header key has high cardinality (many distinct values)
- Query performance with GIN index is insufficient

```sql
-- Example: B-tree index for frequently queried 'type' header
CREATE INDEX idx_queue_messages_headers_type
ON queue_messages ((headers->>'type'));
```

---

## Implementation Guide

### Quick Start

1. **Add server-side filter to consumer configuration:**
   ```java
   ConsumerConfig config = ConsumerConfig.builder()
       .serverSideFilter(ServerSideFilter.headerEquals("type", "order-created"))
       .build();
   ```

2. **Create consumer with the configuration:**
   ```java
   MessageConsumer<OrderEvent> consumer = factory.createConsumer(
       "order-events", OrderEvent.class, config);
   ```

3. **Subscribe to messages (already filtered at database level):**
   ```java
   consumer.subscribe(message -> {
       // Only receives messages where headers->>'type' = 'order-created'
       processOrder(message.getPayload());
       return Future.succeededFuture();
   });
   ```

### Migration from Client-Side Filtering

**Before (client-side only):**
```java
ConsumerGroup<OrderEvent> group = factory.createConsumerGroup(
    "OrderProcessors", "order-events", OrderEvent.class);

group.addConsumer("order-created-processor", handler,
    MessageFilter.byHeader("type", "order-created"));

group.start().onFailure(error -> logger.error("Consumer group start failed", error));
```

**After (server-side + optional client-side):**
```java
// Option 1: Server-side only (recommended for simple filters)
ConsumerConfig config = ConsumerConfig.builder()
    .serverSideFilter(ServerSideFilter.headerEquals("type", "order-created"))
    .build();

MessageConsumer<OrderEvent> consumer = factory.createConsumer(
    "order-events", OrderEvent.class, config);
consumer.subscribe(handler).onFailure(error -> logger.error("Subscription failed", error));
```

**Consumer groups are not supported.** `QueueFactory.createConsumerGroup` has one signature, `createConsumerGroup(groupName, topic, payloadType)`. No overload takes a `ConsumerConfig`, and the consumer group code does not read `ServerSideFilter`. A consumer group can use only client-side `MessageFilter` predicates. Earlier versions of this guide showed a `createConsumerGroup(name, topic, type, config)` overload; it does not exist.

---

## Usage Examples

### Example 1: Simple Equality Filter

**Use Case:** Filter messages by a single header value.

```java
// Producer sends messages with different types
producer.send(orderCreatedEvent, Map.of("type", "order-created"))
    .onFailure(error -> logger.error("Order-created send failed", error));
producer.send(orderUpdatedEvent, Map.of("type", "order-updated"))
    .onFailure(error -> logger.error("Order-updated send failed", error));

// Consumer only receives "order-created" messages
ConsumerConfig config = ConsumerConfig.builder()
    .mode(ConsumerMode.HYBRID)
    .serverSideFilter(ServerSideFilter.headerEquals("type", "order-created"))
    .build();

MessageConsumer<OrderEvent> consumer = factory.createConsumer(
    "order-events", OrderEvent.class, config);

consumer.subscribe(message -> {
    // Only receives messages where headers->>'type' = 'order-created'
    processOrderCreated(message.getPayload());
    return Future.succeededFuture();
});
```

**Generated SQL:**
```sql
WHERE topic = $1 AND status = 'AVAILABLE' AND visible_at <= now()
  AND (headers ? 'type' AND headers->>'type' = $4)
```

### Example 2: IN Operator (Multiple Values)

**Use Case:** Filter messages matching any value in a set.

```java
// Consumer receives messages for multiple regions
ConsumerConfig config = ConsumerConfig.builder()
    .serverSideFilter(ServerSideFilter.headerIn("region", Set.of("US", "EU", "APAC")))
    .build();

MessageConsumer<Event> consumer = factory.createConsumer("events", Event.class, config);
```

**Generated SQL:**
```sql
WHERE topic = $1 AND status = 'AVAILABLE' AND visible_at <= now()
  AND (headers ? 'region' AND headers->>'region' IN ($4, $5, $6))
```

### Example 3: AND Operator (Multiple Conditions)

**Use Case:** Filter messages matching all conditions.

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

MessageConsumer<OrderEvent> consumer = factory.createConsumer(
    "order-events", OrderEvent.class, config);
```

**Generated SQL:**
```sql
WHERE topic = $1 AND status = 'AVAILABLE' AND visible_at <= now()
  AND ((headers ? 'type' AND headers->>'type' = $4)
   AND (headers ? 'priority' AND headers->>'priority' = $5)
   AND (headers ? 'region' AND headers->>'region' = $6))
```

### Example 4: OR Operator (Alternative Conditions)

**Use Case:** Filter messages matching any condition.

```java
// Consumer receives either high-priority OR urgent messages
ServerSideFilter filter = ServerSideFilter.or(
    ServerSideFilter.headerEquals("priority", "HIGH"),
    ServerSideFilter.headerEquals("urgent", "true")
);

ConsumerConfig config = ConsumerConfig.builder()
    .serverSideFilter(filter)
    .build();
```

**Generated SQL:**
```sql
WHERE topic = $1 AND status = 'AVAILABLE' AND visible_at <= now()
  AND ((headers ? 'priority' AND headers->>'priority' = $4)
    OR (headers ? 'urgent' AND headers->>'urgent' = $5))
```

### Example 5: LIKE Operator (Pattern Matching)

**Use Case:** Filter messages with header values matching a pattern.

```java
// Consumer receives messages for all US regions (US-EAST, US-WEST, etc.)
ConsumerConfig config = ConsumerConfig.builder()
    .serverSideFilter(ServerSideFilter.headerLike("region", "US-%"))
    .build();
```

**Generated SQL:**
```sql
WHERE topic = $1 AND status = 'AVAILABLE' AND visible_at <= now()
  AND (headers ? 'region' AND headers->>'region' LIKE $4)
```

### Example 6: NOT_EQUALS Operator (Exclusion)

**Use Case:** Filter out messages with specific header values.

```java
// Consumer receives all messages except test messages
ConsumerConfig config = ConsumerConfig.builder()
    .serverSideFilter(ServerSideFilter.headerNotEquals("environment", "test"))
    .build();
```

**Generated SQL:**
```sql
WHERE topic = $1 AND status = 'AVAILABLE' AND visible_at <= now()
  AND (NOT headers ? 'environment' OR headers->>'environment' != $4)
```

Messages that have no `environment` header are **included**. This is open defect D3; see [NOT_EQUALS and missing headers](#not_equals-and-missing-headers-open-defect-d3).

### Example 7: Hybrid Filtering (Server + Client)

**Not supported with consumer groups.** Earlier versions of this example passed a `ConsumerConfig` to `createConsumerGroup(name, topic, type, config)`. That overload does not exist, and consumer groups do not apply `ServerSideFilter`. A plain `MessageConsumer` with a server-side filter can apply further checks inside its own handler.

### Example 8: Outbox Pattern with Filtering

**Use Case:** Filter outbox messages by header.

```java
// Outbox consumer configuration
OutboxConsumerConfig config = OutboxConsumerConfig.builder()
    .pollingInterval(Duration.ofSeconds(5))
    .batchSize(100)
    .serverSideFilter(ServerSideFilter.headerEquals("destination", "email-service"))
    .build();

OutboxConsumer consumer = outboxFactory.createConsumer(
    "notifications", NotificationEvent.class, config);

consumer.subscribe(message -> {
    // Only receives messages destined for email service
    sendEmail(message.getPayload());
    return Future.succeededFuture();
});
```

---

## Performance Optimization

### When to Use Server-Side Filtering

| Scenario | Recommendation | Rationale |
|----------|----------------|-----------|
| **High message volume, low match rate** | ✅ **Use server-side** | Fewer rows are claimed and transferred |
| **Low message volume** | ⚖️ Either works | No measurement in this project shows a difference |
| **Complex filter logic** (regex, payload inspection) | ❌ **Use client-side** | More flexible, SQL can't express all logic |
| **Simple header equality checks** | ✅ **Use server-side** | Most efficient, leverages database indexes |
| **Multiple consumers, different filters** | ✅ **Use server-side** | Each plain consumer gets only relevant messages (not available for consumer groups) |
| **Filtering on payload content** | ❌ **Use client-side** | Requires deserialization, not SQL-friendly |
| **Dynamic filter criteria** | ⚖️ Hybrid approach | Server-side for static, client-side for dynamic |

### Index Strategy

| Filter Pattern | Recommended Index | Performance | When to Use |
|----------------|-------------------|-------------|-------------|
| **Single header key equality** | B-tree on `(headers->>'key')` | ⭐⭐⭐⭐⭐ Excellent | Frequent queries on one specific key |
| **Multiple header keys** | GIN on `headers` | ⭐⭐⭐⭐ Very Good | Queries on various header keys |
| **Header key existence check** | GIN on `headers` | ⭐⭐⭐⭐ Very Good | Checking if header exists |
| **Pattern matching (LIKE)** | GIN with `gin_trgm_ops` | ⭐⭐⭐ Good | Wildcard searches |
| **No index** | None | ⭐ Poor | Low volume or infrequent queries |

**Current Implementation:** GIN indexes on `headers` exist on the Flyway tables `outbox`, `queue_messages` and `bitemporal_event_log`, and on per-queue and per-store tables. Tenant-schema `outbox` and `queue_messages` have no headers index (see [Database Indexes](#4-database-indexes)).

### Performance Benchmarks

No benchmark of server-side filtering exists in the code base. Earlier versions of this guide listed throughput, latency and transfer figures for 10,000-message scenarios. No test or benchmark produced them, so they are removed.

### Optimization Best Practices

1. **Use server-side filtering when the match rate is low**
   - Significant reduction in network traffic
   - Lower client CPU usage
   - Better scalability

2. **Combine server-side and client-side filtering**
   - Server-side: coarse filter (e.g., message type)
   - Client-side: fine-grained filter (e.g., business logic)
   - Example: Server filters `type=order`, client filters `amount > 1000`

3. **Monitor query performance**
   - Use `EXPLAIN ANALYZE` to verify index usage
   - Check for sequential scans on large tables
   - Add specific B-tree indexes for hot paths

4. **Consider cardinality**
   - High cardinality (many distinct values): B-tree index on specific key
   - Low cardinality (few distinct values): GIN index sufficient
   - Example: `region` (low cardinality) vs `user_id` (high cardinality)

5. **Avoid over-filtering**
   - When most messages match, the filter removes few rows
   - Profile your specific use case

### Query Plan Analysis

Use `EXPLAIN ANALYZE` on your own data to see whether an index is used. No query plans or timings are recorded for this feature.

```sql
EXPLAIN ANALYZE
SELECT id FROM queue_messages
WHERE topic = 'orders' AND status = 'AVAILABLE'
  AND (headers ? 'type' AND headers->>'type' = 'order-created');
```

---

## Testing Strategy

### Test Coverage Summary

**Total: 29 tests** across unit and integration levels.

#### Unit Tests (peegeeq-api) - 18 tests

Located in: `ServerSideFilterTest.java`

| Category | Test Count | Coverage |
|----------|------------|----------|
| **Factory Method Validation** | 7 | Null/empty/invalid input rejection |
| **SQL Generation (Simple)** | 4 | EQUALS, NOT_EQUALS, LIKE, IN operators |
| **SQL Generation (Compound)** | 4 | AND, OR, nested filters, parameter offsets |
| **Equals & HashCode** | 2 | Object equality and hash code contracts |
| **Header Key Validation** | 1 | Valid header key pattern enforcement |

**Key Test Cases:**
- ✅ NULL-safe SQL generation (explicit key existence checks)
- ✅ Parameter offset handling for compound filters
- ✅ SQL injection prevention (invalid header keys rejected)
- ✅ Immutability and thread-safety

#### Integration Tests - Native Queue (peegeeq-examples) - 7 tests

Located in: `ServerSideFilteringTest.java`

| Test | Operator | Verification |
|------|----------|--------------|
| `testServerSideFilterEquals` | EQUALS | Exact header value match |
| `testServerSideFilterIn` | IN | Set membership |
| `testServerSideFilterAnd` | AND | Multiple conditions (all must match) |
| `testServerSideFilterNotEquals` | NOT_EQUALS | Value exclusion |
| `testServerSideFilterOr` | OR | Alternative conditions (any must match) |
| `testServerSideFilterLike` | LIKE | Pattern matching with wildcards |
| `testServerSideFilterMissingHeader` | EQUALS | NULL-safe filtering (missing headers excluded) |

**Test Infrastructure:**
- Uses TestContainers with PostgreSQL 16
- Real database queries (not mocked)
- Verifies actual message filtering behavior

#### Integration Tests - Outbox (peegeeq-examples) - 4 tests

Located in: `OutboxServerSideFilteringTest.java`

| Test | Operator | Verification |
|------|----------|--------------|
| `testOutboxServerSideFilterEquals` | EQUALS | Exact header value match |
| `testOutboxServerSideFilterIn` | IN | Set membership |
| `testOutboxServerSideFilterAnd` | AND | Multiple conditions |
| `testOutboxServerSideFilterMissingHeader` | EQUALS | NULL-safe filtering |

### Testing Best Practices

1. **Always test with missing headers**
   - Verify messages without the header key are excluded by EQUALS, IN and LIKE
   - NOT_EQUALS includes them (open defect D3); no integration test covers a missing header with NOT_EQUALS

2. **Test compound filters**
   - Verify AND/OR logic works as expected
   - Test nested filters (e.g., `AND(OR(...), EQUALS(...))`)

3. **Verify SQL injection prevention**
   - Test with invalid header keys (e.g., `'; DROP TABLE--`)
   - Ensure validation rejects malicious input

4. **Performance testing**
   - Measure query latency with and without filters
   - Verify index usage with `EXPLAIN ANALYZE`
   - Test with realistic message volumes

### Example Test Case

```java
@Test
void testServerSideFilterEquals(VertxTestContext testContext) {
    // Create consumer with server-side filter
    ConsumerConfig config = ConsumerConfig.builder()
        .serverSideFilter(ServerSideFilter.headerEquals("type", "order-created"))
        .build();

    MessageConsumer<String> consumer = factory.createConsumer(
        "orders", String.class, config);

    List<String> received = new CopyOnWriteArrayList<>();
    Checkpoint expectedDeliveries = testContext.checkpoint(2);

    consumer.subscribe(msg -> {
        testContext.verify(() -> {
            received.add(msg.getPayload());
            assertThat(msg.getHeaders().get("type")).isEqualTo("order-created");
            assertThat(received).doesNotContain("order-2");
            expectedDeliveries.flag();
        });
        return Future.succeededFuture();
    }).compose(ignored -> Future.all(List.of(
        producer.send("order-1", Map.of("type", "order-created")),
        producer.send("order-2", Map.of("type", "order-updated")),
        producer.send("order-3", Map.of("type", "order-created")))))
      .onFailure(testContext::failNow);
}
```

---

## Best Practices

### 1. Choose the Right Filtering Approach

**Use Server-Side Filtering When:**
- ✅ Filtering on message headers (metadata)
- ✅ Simple equality, IN, or pattern matching
- ✅ High message volume with low match rate
- ✅ Multiple plain consumers with different filter criteria (not consumer groups)
- ✅ Network bandwidth is a concern

**Use Client-Side Filtering When:**
- ✅ Filtering on payload content (requires deserialization)
- ✅ Complex business logic (e.g., `amount > 1000 && customer.isPremium()`)
- ✅ Dynamic filter criteria (changes frequently)
- ✅ Low message volume
- ✅ Filter logic not expressible in SQL
- ✅ Consumer groups (server-side filtering is not available for them)

**Use Hybrid Approach When:**
- ✅ Coarse server-side filter + fine-grained client-side filter
- ✅ Example: Server filters `type=order`, client filters `amount > 1000`
- ✅ Balances performance and flexibility

### 2. Design Effective Header Schemas

**Good Header Design:**
```java
// Clear, consistent header keys
Map.of(
    "type", "order-created",           // Message type
    "region", "US-EAST",                // Geographic region
    "priority", "HIGH",                 // Priority level
    "source", "web-app"                 // Source system
)
```

**Poor Header Design:**
```java
// Inconsistent, hard to filter
Map.of(
    "messageType", "OrderCreated",      // Inconsistent casing
    "reg", "us-east",                   // Abbreviated key
    "p", "1",                           // Unclear key
    "metadata", "{\"source\":\"web\"}"  // Nested JSON string
)
```

**Best Practices:**
- Use lowercase, hyphen-separated keys (`order-type`, not `OrderType`)
- Keep header values simple (strings, not nested JSON)
- Use consistent vocabulary across all messages
- Document header schema in a central location

### 3. Optimize for Common Queries

**Identify Hot Paths:**
```java
// If you frequently filter on "type" header, consider a specific index
CREATE INDEX idx_queue_messages_type ON queue_messages ((headers->>'type'));
```

**Monitor Query Performance:**
```sql
-- Check if index is being used
EXPLAIN ANALYZE
SELECT id FROM queue_messages
WHERE topic = 'orders'
  AND (headers ? 'type' AND headers->>'type' = 'order-created');
```

### 4. Handle Missing Headers Gracefully

**EQUALS, IN and LIKE exclude messages without the header:**
```java
// This filter will NOT match messages without "type" header
ServerSideFilter.headerEquals("type", "order-created")

// Generated SQL includes key existence check:
// (headers ? 'type' AND headers->>'type' = $n)
```

**NOT_EQUALS includes messages without the header (open defect D3):**
```java
// This filter DOES match messages without "environment" header
ServerSideFilter.headerNotEquals("environment", "test")

// Generated SQL:
// (NOT headers ? 'environment' OR headers->>'environment' != $n)
```

**If you want to include messages without the header, use client-side filter:**
```java
// Client-side filter can handle missing headers
message -> {
    String type = message.getHeaders().get("type");
    return type == null || type.equals("order-created");
}
```

### 5. Validate Filter Expressions

**Server-side filters validate header keys at build time:**
```java
// Valid header keys: start with a letter, then letters, digits, underscore, hyphen
ServerSideFilter.headerEquals("order-type", "created");  // ✅ Valid
ServerSideFilter.headerEquals("order_type", "created");  // ✅ Valid

// Invalid header keys (throws IllegalArgumentException)
ServerSideFilter.headerEquals("1type", "created");       // ❌ Invalid (starts with a digit)
ServerSideFilter.headerEquals("_type", "created");       // ❌ Invalid (starts with underscore)
ServerSideFilter.headerEquals("order.type", "created");  // ❌ Invalid (dot)
ServerSideFilter.headerEquals("'; DROP TABLE--", "x");   // ❌ Invalid (SQL injection)
```

### 6. Combine with Other PeeGeeQ Features

**Priority + Filtering:**
```java
// High-priority messages processed first, filtered by type
ConsumerConfig config = ConsumerConfig.builder()
    .serverSideFilter(ServerSideFilter.headerEquals("type", "urgent"))
    .build();

// Messages are ordered by priority DESC, created_at ASC
// Only "urgent" messages are fetched
```

**Consumer Groups + Filtering: not supported.** `createConsumerGroup` takes only `(groupName, topic, payloadType)`. There is no overload that takes a `ConsumerConfig`, and consumer groups do not apply `ServerSideFilter`. Use client-side `MessageFilter` predicates on group members.

---

## Troubleshooting

### Issue: Messages Not Being Filtered

**Symptom:** Consumer receives all messages, ignoring server-side filter.

**Possible Causes:**
1. Filter not passed to consumer configuration
2. Filter is null
3. SQL syntax error in generated query

**Solution:**
```java
// Verify filter is set
ConsumerConfig config = ConsumerConfig.builder()
    .serverSideFilter(ServerSideFilter.headerEquals("type", "order"))
    .build();

System.out.println("Has filter: " + config.hasServerSideFilter());  // Should be true

// Enable SQL logging to see generated queries
// In application.properties:
// logging.level.io.vertx.sqlclient=DEBUG
```

### Issue: Slow Query Performance

**Symptom:** Queries with server-side filter are slower than without.

**Possible Causes:**
1. Missing GIN index on headers column (tenant-schema `outbox` and `queue_messages` have none)
2. Sequential scan instead of index scan
3. High match rate

**Solution:**
```sql
-- 1. Verify index exists
SELECT indexname, indexdef
FROM pg_indexes
WHERE tablename = 'queue_messages'
  AND indexname LIKE '%headers%';

-- 2. Check query plan
EXPLAIN ANALYZE
SELECT id FROM queue_messages
WHERE topic = 'orders'
  AND (headers ? 'type' AND headers->>'type' = 'order-created');

-- Look for "Bitmap Index Scan" or "Index Scan"
-- If you see "Seq Scan", index is not being used

-- 3. Create index if missing
CREATE INDEX CONCURRENTLY idx_queue_messages_headers_gin
ON queue_messages USING GIN(headers);
```

### Issue: Messages with Missing Headers Not Excluded

**Symptom:** Consumer receives messages without the filtered header.

**Possible Causes:**
1. The filter is NOT_EQUALS. In the current code NOT_EQUALS includes messages without the header (open defect D3).
2. Using old version without NULL-safe SQL generation
3. Custom SQL query not using `headers ?` check

**Solution:**
```java
// Verify SQL includes key existence check
ServerSideFilter filter = ServerSideFilter.headerEquals("type", "order");
String sql = filter.toSqlCondition(4);

// Should output: (headers ? 'type' AND headers->>'type' = $4)
System.out.println(sql);

// If not, update to latest version with NULL-safe filtering
```

### Issue: IllegalArgumentException on Filter Creation

**Symptom:** `IllegalArgumentException: Invalid header key` when creating filter.

**Possible Causes:**
1. Header key contains invalid characters
2. Header key is null or empty

**Solution:**
```java
// Valid header keys: start with a letter, then letters, digits, underscore, hyphen
ServerSideFilter.headerEquals("order-type", "created");  // ✅ Valid
ServerSideFilter.headerEquals("order_type", "created");  // ✅ Valid

// Invalid header keys
ServerSideFilter.headerEquals("1type", "created");       // ❌ Must start with a letter
ServerSideFilter.headerEquals("order.type", "created");  // ❌ Dot not allowed
ServerSideFilter.headerEquals("order type", "created");  // ❌ Space not allowed
ServerSideFilter.headerEquals("", "created");            // ❌ Empty not allowed
ServerSideFilter.headerEquals(null, "created");          // ❌ Null not allowed
```

### Issue: Filter Not Working with Consumer Groups

**Symptom:** Consumer group ignores server-side filter.

**Cause:** Consumer groups do not support server-side filtering. `createConsumerGroup(groupName, topic, payloadType)` is the only signature. It takes no `ConsumerConfig`, and the consumer group code does not read `ServerSideFilter`.

**Solution:** Use a plain consumer created with `createConsumer(topic, payloadType, config)` when a server-side filter is needed, or use client-side `MessageFilter` predicates on group members.

### Debugging Tips

1. **Enable SQL logging:**
   ```properties
   # application.properties
   logging.level.io.vertx.sqlclient=DEBUG
   ```

2. **Verify filter SQL generation:**
   ```java
   ServerSideFilter filter = ServerSideFilter.headerEquals("type", "order");
   System.out.println("SQL: " + filter.toSqlCondition(4));
   System.out.println("Params: " + filter.getParameters());
   ```

3. **Test filter in PostgreSQL directly:**
   ```sql
   SELECT id, headers
   FROM queue_messages
   WHERE topic = 'orders'
     AND (headers ? 'type' AND headers->>'type' = 'order-created')
   LIMIT 10;
   ```

4. **Check message headers:**
   ```java
   consumer.subscribe(message -> {
       System.out.println("Headers: " + message.getHeaders());
       return Future.succeededFuture();
   });
   ```

---

## Implementation Summary

### Completed Components

✅ **Phase 1: Core API (peegeeq-api)**
- `ServerSideFilter` class with full operator support
- NULL-safe SQL generation
- Header key validation
- Comprehensive unit tests (18 tests)

✅ **Phase 2: Native Queue Integration (peegeeq-native)**
- Extended `ConsumerConfig` with `serverSideFilter` field
- Modified `PgNativeQueueConsumer` for dynamic SQL building
- Integration tests with TestContainers (7 tests)

✅ **Phase 3: Outbox Integration (peegeeq-outbox)**
- Extended `OutboxConsumerConfig` with `serverSideFilter` field
- Modified `OutboxConsumer` for dynamic SQL building
- Integration tests (4 tests)

✅ **Phase 4: Indexes**
- GIN indexes on `headers` for the Flyway tables and for per-queue and per-store template tables
- Not for tenant-schema `outbox` and `queue_messages` (templates `base/04a` and `base/04b` create none)
- Baseline migration updated

**Not provided:** REST exposure of server-side filters, and server-side filtering for consumer groups.

### Files Modified/Created

**peegeeq-api:**
- ✅ `api/messaging/ServerSideFilter.java` - Filter expression API
- ✅ `api/messaging/ServerSideFilterTest.java` - Unit tests

**peegeeq-native:**
- ✅ `pgqueue/ConsumerConfig.java` - Added serverSideFilter field
- ✅ `pgqueue/PgNativeQueueConsumer.java` - Dynamic SQL building
- ✅ `pgqueue/PgNativeQueueFactory.java` - Pass filter through factory

**peegeeq-outbox:**
- ✅ `outbox/OutboxConsumerConfig.java` - Added serverSideFilter field
- ✅ `outbox/OutboxConsumer.java` - Dynamic SQL building
- ✅ `outbox/OutboxFactory.java` - Pass filter through factory

**peegeeq-examples:**
- ✅ `ServerSideFilteringTest.java` - Native queue integration tests
- ✅ `OutboxServerSideFilteringTest.java` - Outbox integration tests

**peegeeq-migrations:**
- ✅ `V001__Create_Base_Tables.sql` - Added GIN indexes

**peegeeq-db:**
- ✅ `db/templates/base/07p-queue-index-headers.sql` - Queue template index
- ✅ `db/templates/queue/02f-index-headers.sql` - Individual queue index
- ✅ `db/templates/eventstore/02j-index-headers.sql` - Event store index

### Backward Compatibility

✅ **100% backward compatible**
- `serverSideFilter` is optional (null by default)
- Existing code continues to work unchanged
- Can combine server-side and client-side filters
- No database migration required (indexes are additive)

### Known Gaps

- NOT_EQUALS includes messages without the header; the intent is undecided (open defect D3).
- Tenant-schema `outbox` and `queue_messages` have no headers index.
- No benchmark of filtered claim queries exists.
- No REST exposure and no consumer-group support.

---

## Appendix

### SQL Operator Reference

| Operator | SQL Template | Example | Use Case |
|----------|--------------|---------|----------|
| **EQUALS** | `(headers ? 'key' AND headers->>'key' = $n)` | `type = 'order'` | Exact match |
| **NOT_EQUALS** | `(NOT headers ? 'key' OR headers->>'key' != $n)` | `env != 'test'` | Exclusion; messages without the key are included (open defect D3) |
| **IN** | `(headers ? 'key' AND headers->>'key' IN ($n, $n+1, ...))` | `region IN ('US', 'EU')` | Set membership |
| **LIKE** | `(headers ? 'key' AND headers->>'key' LIKE $n)` | `region LIKE 'US-%'` | Pattern matching |
| **AND** | `((filter1) AND (filter2) AND ...)` | `type='order' AND region='US'` | All conditions |
| **OR** | `((filter1) OR (filter2) OR ...)` | `priority='HIGH' OR urgent='true'` | Any condition |

### Header Key Validation Pattern

```regex
^[a-zA-Z][a-zA-Z0-9_-]*$
```

The first character must be a letter.

**Valid Examples:**
- `type`
- `order-type`
- `order_type`
- `region123`

**Invalid Examples:**
- `1type` (must start with a letter)
- `_type` or `-type` (must start with a letter)
- `order.type` (dot not allowed)
- `order type` (space not allowed)
- `order/type` (slash not allowed)
- `'; DROP TABLE--` (SQL injection attempt)

### Related Documentation

- [PeeGeeQ Main README](../../README.md)
- [Native Queue Module Guide](../../peegeeq-native/docs/PEEGEEQ_NATIVE_MODULE_GUIDE.md)
- [Messaging Guide](../../docs/PEEGEEQ_MESSAGING_GUIDE.md)
- [Database Schema Documentation](../../peegeeq-migrations/README.md)

### Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2025-12-23 | Transformed from plan to technical design & guide |
| 0.2 | 2025-12-18 | Implementation complete, all tests passing |
| 0.1 | 2025-12-18 | Initial technical plan |

---

**Last Reviewed:** 2026-11-21 (corrected against `18c27376`)
**Next Review:** As needed for feature enhancements
