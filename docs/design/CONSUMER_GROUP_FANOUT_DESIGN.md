# Consumer Group Fan-Out Design

**Status**: Design Proposal  
**Author**: PeeGeeQ Team  
**Date**: 2025-11-11  
**Version**: 1.0

## Table of Contents
1. [Overview](#overview)
2. [Problem Statement](#problem-statement)
3. [Current Architecture Limitations](#current-architecture-limitations)
4. [Proposed Solutions](#proposed-solutions)
5. [Recommended Approach: Hybrid Queue/Pub-Sub](#recommended-approach-hybrid-queuepub-sub)
6. [Critical Design Questions & Solutions](#critical-design-questions--solutions)
7. [Database Schema Changes](#database-schema-changes)
8. [API Design](#api-design)
9. [Implementation Details](#implementation-details)
10. [Migration Path](#migration-path)
11. [Comparison with Other Systems](#comparison-with-other-systems)

---

## Overview

This document describes the design for implementing **reliable fan-out with at-least-once delivery** to multiple consumers in PeeGeeQ. The goal is to support **pub/sub semantics** (message replication) while maintaining backward compatibility with existing **queue semantics** (message distribution).

### Key Requirements
- ✅ At-least-once delivery guarantee to multiple independent consumer groups
- ✅ Backward compatibility with existing queue-based consumers
- ✅ Support for late-joining consumers with configurable catch-up behavior
- ✅ Automatic cleanup of dead/inactive consumer groups
- ✅ Efficient message retention and cleanup
- ✅ Clear, explicit configuration per topic

---

## Problem Statement

### Use Case: Event Broadcasting
A typical scenario requiring fan-out:

```
Order Created Event
    ├─> Email Service (send confirmation email)
    ├─> Analytics Service (track metrics)
    ├─> Inventory Service (update stock)
    └─> Shipping Service (prepare shipment)
```

**Requirements:**
- Each service must receive and process the event independently
- Failure in one service should not affect others
- Services can be added/removed dynamically
- Late-joining services may need historical events

---

## Current Architecture Limitations

### Queue Semantics (Current Implementation)

PeeGeeQ currently implements **queue semantics** where messages are **distributed** (not replicated):

```java
// Current OutboxConsumer query
String sql = """
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
    """;
```

**Behavior:**
- Each message is consumed by **exactly ONE consumer**
- `FOR UPDATE SKIP LOCKED` ensures no duplicate processing
- Message marked as `PROCESSING` immediately
- Perfect for work queue patterns, **NOT for fan-out**

### OutboxConsumerGroup Limitation

Even with `OutboxConsumerGroup`, messages are **distributed** among group members:

```java
// Round-robin distribution to ONE consumer
OutboxConsumerGroupMember<T> selectedConsumer = selectConsumer(eligibleConsumers, message);
return selectedConsumer.processMessage(message);
```

**This does NOT provide fan-out to multiple independent services.**

---

## Proposed Solutions

### Option 1: Multiple Independent Consumer Groups ⭐ (Current Workaround)

Create separate consumer groups for each logical subscriber:

```java
ConsumerGroup<OrderEvent> emailGroup = 
    queueFactory.createConsumerGroup("email-service-group", "orders", OrderEvent.class);
ConsumerGroup<OrderEvent> analyticsGroup = 
    queueFactory.createConsumerGroup("analytics-service-group", "orders", OrderEvent.class);
```

**Pros:**
- Works with current implementation
- Each group tracks processing independently in `outbox_consumer_groups` table

**Cons:**
- No coordination between groups
- No way to know when ALL groups have processed a message
- **Message cleanup is problematic** - The current `cleanup_completed_outbox_messages()` function only deletes messages when:
  1. The message status is `COMPLETED` (which happens when ANY consumer marks it complete)
  2. AND all entries in `outbox_consumer_groups` for that message are `COMPLETED`

  **The Problem**: The `outbox_consumer_groups` table is only populated when consumer groups are explicitly registered via `register_consumer_group_for_existing_messages()`. If you create multiple independent consumer groups without this registration, the cleanup function will delete messages as soon as the FIRST group completes them, preventing other groups from processing them.

  **Example Failure Scenario**:
  ```java
  // Group 1 processes message and marks it COMPLETED
  ConsumerGroup<OrderEvent> emailGroup =
      queueFactory.createConsumerGroup("email-service", "orders", OrderEvent.class);

  // Group 2 never gets a chance because message is deleted after Group 1 completes
  ConsumerGroup<OrderEvent> analyticsGroup =
      queueFactory.createConsumerGroup("analytics-service", "orders", OrderEvent.class);
  ```

  Without explicit registration and coordination, there's no way to know that `analytics-service` also needs to process the message before it's deleted.

- No support for late-joining consumers

#### Deep Dive: Why Message Cleanup Fails

The current cleanup function logic:

```sql
-- Current cleanup_completed_outbox_messages() function
DELETE FROM outbox
WHERE id IN (
    SELECT o.id
    FROM outbox o
    WHERE o.status = 'COMPLETED'
    AND NOT EXISTS (
        SELECT 1 FROM outbox_consumer_groups ocg
        WHERE ocg.outbox_message_id = o.id
        AND ocg.status != 'COMPLETED'
    )
    AND o.created_at < NOW() - INTERVAL '1 hour'
);
```

**The Race Condition:**

1. **Message marked COMPLETED too early**: When ANY consumer processes a message, it marks the outbox message status as `COMPLETED`:
   ```java
   // In OutboxConsumer.markMessageCompleted()
   String sql = "UPDATE outbox SET status = 'COMPLETED', processed_at = $1 WHERE id = $2";
   ```

2. **outbox_consumer_groups not auto-populated**: The `outbox_consumer_groups` table is only populated when:
   - Consumer groups are explicitly registered via `register_consumer_group_for_existing_messages()`
   - OR via the trigger `create_consumer_group_entries_for_new_message()` which only creates entries for ALREADY KNOWN consumer groups:
   ```sql
   -- Trigger only creates entries for groups that already exist in the table
   INSERT INTO outbox_consumer_groups (outbox_message_id, consumer_group_name, status)
   SELECT DISTINCT NEW.id, consumer_group_name, 'PENDING'
   FROM outbox_consumer_groups  -- Only knows about groups already in this table!
   WHERE consumer_group_name IS NOT NULL
   ```

3. **Cleanup deletes prematurely**: If you create multiple independent consumer groups without explicit registration:
   - Group 1 processes message → marks outbox status as `COMPLETED`
   - Cleanup job runs → sees status is `COMPLETED`
   - Cleanup checks `outbox_consumer_groups` → finds NO entries (because Group 2 was never registered)
   - NOT EXISTS clause returns TRUE (no incomplete entries found)
   - Message gets deleted ❌
   - Group 2 never sees the message

**Timeline of Failure:**

```
T0: Message inserted into outbox (status = 'PENDING')
    - No entries in outbox_consumer_groups (no groups registered yet)

T1: email-service consumer group starts and processes message
    - Marks outbox.status = 'COMPLETED'
    - No entry created in outbox_consumer_groups (group not registered)

T2: Cleanup job runs (1 hour later)
    - Finds message with status = 'COMPLETED'
    - Checks outbox_consumer_groups → no entries found
    - NOT EXISTS returns TRUE → message deleted

T3: analytics-service consumer group starts
    - Tries to fetch pending messages
    - Message already deleted → never processed ❌
```

**Why Explicit Registration Doesn't Fully Solve It:**

Even if you call `register_consumer_group_for_existing_messages()`, you still have coordination problems:

```java
// You must register ALL groups BEFORE any messages are sent
register_consumer_group_for_existing_messages("email-service");
register_consumer_group_for_existing_messages("analytics-service");
register_consumer_group_for_existing_messages("inventory-service");

// Now send messages
producer.send(orderEvent);
```

**Problems:**
- ❌ Must know all consumer groups upfront
- ❌ Can't add new consumer groups dynamically
- ❌ No way to handle late-joining consumers
- ❌ Manual coordination required across services
- ❌ Fragile - forgetting to register a group causes data loss

### Option 2: Application-Level Fan-Out

Single consumer fans out to multiple handlers:

```java
public class FanOutMessageHandler<T> implements MessageHandler<T> {
    private final List<MessageHandler<T>> handlers;
    
    @Override
    public CompletableFuture<Void> handle(Message<T> message) {
        List<CompletableFuture<Void>> futures = handlers.stream()
            .map(handler -> handler.handle(message))
            .toList();
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
}
```

**Pros:**
- Simple to implement
- Single consumer manages fan-out

**Cons:**
- No independent tracking per handler
- Difficult to scale handlers independently
- Error handling complexity

### Option 3: Outbox-to-Outbox Pattern

Consumer republishes to topic-specific outboxes:

```java
emailProducer.send(message.getPayload());
analyticsProducer.send(message.getPayload());
inventoryProducer.send(message.getPayload());
```

**Pros:**
- Complete isolation between consumers
- Each topic can have different policies

**Cons:**
- More database writes
- Additional latency
- Complex topology

### Option 4: Hybrid Queue/Pub-Sub Support ⭐⭐⭐ (RECOMMENDED)

Add pub/sub semantics as an **opt-in feature** configured per topic.

**This is the recommended approach detailed in the rest of this document.**

---

## Recommended Approach: Hybrid Queue/Pub-Sub

### Design Principles

1. **Backward Compatibility**: Existing queue consumers work unchanged
2. **Opt-In**: Only topics configured as `PUB_SUB` use new behavior
3. **Explicit Configuration**: Topic semantics are clearly defined
4. **Gradual Migration**: Topics can be migrated one at a time
5. **Performance**: Queue topics keep efficient `FOR UPDATE SKIP LOCKED`

### Topic Semantics

```java
public enum TopicSemantics {
    /**
     * Queue semantics: Messages are DISTRIBUTED to consumers.
     * Each message processed by exactly ONE consumer (or consumer group).
     * Existing behavior - no changes.
     */
    QUEUE,
    
    /**
     * Pub/Sub semantics: Messages are REPLICATED to consumer groups.
     * Each message processed by ALL registered consumer groups.
     * New behavior - opt-in.
     */
    PUB_SUB
}
```

### Configuration API

```java
public class TopicConfiguration {
    private final String topic;
    private final TopicSemantics semantics;
    private final Duration messageRetention;
    
    public static TopicConfiguration queue(String topic) {
        return new TopicConfiguration(topic, TopicSemantics.QUEUE, null);
    }
    
    public static TopicConfiguration pubSub(String topic, Duration retention) {
        return new TopicConfiguration(topic, TopicSemantics.PUB_SUB, retention);
    }
}

// Usage
queueFactory.configureTopic(
    TopicConfiguration.pubSub("orders.events", Duration.ofHours(24))
);
```

---

## Critical Design Questions & Solutions

### ⚠️ Question 1: Consumer Group Registration

**Problem**: How do we know which consumer groups should receive messages?

**Solution**: Explicit consumer group registration with subscription tracking.

#### Database Schema
```sql
CREATE TABLE IF NOT EXISTS outbox_topic_subscriptions (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    subscribed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_active_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    subscription_status VARCHAR(20) DEFAULT 'ACTIVE' 
        CHECK (subscription_status IN ('ACTIVE', 'PAUSED', 'CANCELLED', 'DEAD')),
    
    -- Offset tracking for late-joining consumers
    start_from_message_id BIGINT,
    start_from_timestamp TIMESTAMP WITH TIME ZONE,
    
    -- Heartbeat tracking
    heartbeat_interval_seconds INT DEFAULT 60,
    heartbeat_timeout_seconds INT DEFAULT 300,
    last_heartbeat_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    UNIQUE(topic, group_name)
);

CREATE INDEX idx_topic_subscriptions_topic 
    ON outbox_topic_subscriptions(topic) 
    WHERE subscription_status = 'ACTIVE';
```

#### API Design
```java
public interface ConsumerGroup<T> {
    /**
     * Starts the consumer group and registers subscription for pub/sub topics.
     */
    void start(SubscriptionOptions options);
}

public enum SubscriptionPosition {
    EARLIEST,      // Process all existing messages
    LATEST,        // Only process new messages from now
    TIMESTAMP,     // Start from specific timestamp
    MESSAGE_ID     // Start from specific message ID
}

public class SubscriptionOptions {
    private final SubscriptionPosition position;
    private final Instant startTimestamp;
    private final Long startMessageId;
    private final boolean backfillExisting;

    public static SubscriptionOptions fromBeginning(boolean backfill) {
        return new SubscriptionOptions(SubscriptionPosition.EARLIEST, null, null, backfill);
    }

    public static SubscriptionOptions fromNow() {
        return new SubscriptionOptions(SubscriptionPosition.LATEST, Instant.now(), null, false);
    }
}
```

#### Implementation
```java
public class OutboxConsumerGroup<T> {

    @Override
    public void start(SubscriptionOptions options) {
        TopicSemantics semantics = getTopicSemantics(topic);

        if (semantics == TopicSemantics.PUB_SUB) {
            registerSubscription(options)
                .compose(v -> startPolling())
                .onSuccess(v -> logger.info("Consumer group '{}' registered for pub/sub topic '{}'",
                    groupName, topic));
        } else {
            // Queue semantics - no registration needed
            startPolling();
        }
    }

    private Future<Void> registerSubscription(SubscriptionOptions options) {
        String sql = """
            INSERT INTO outbox_topic_subscriptions
                (topic, group_name, start_from_message_id, start_from_timestamp, subscription_status)
            VALUES ($1, $2, $3, $4, 'ACTIVE')
            ON CONFLICT (topic, group_name)
            DO UPDATE SET
                subscription_status = 'ACTIVE',
                last_active_at = NOW()
            """;

        return pool.preparedQuery(sql)
            .execute(Tuple.of(topic, groupName, options.getStartMessageId(), options.getStartTimestamp()))
            .mapEmpty();
    }
}
```

---

### ⚠️ Question 2: Message Retention

**Problem**: Pub/sub messages can't be deleted until ALL consumer groups process them.

**Solution**: Reference counting with automatic cleanup.

#### Database Schema Changes
```sql
-- Add columns to outbox table for pub/sub tracking
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS
    required_consumer_groups INT DEFAULT 0;

ALTER TABLE outbox ADD COLUMN IF NOT EXISTS
    completed_consumer_groups INT DEFAULT 0;

-- Trigger to set required_consumer_groups when message is inserted
CREATE OR REPLACE FUNCTION set_required_consumer_groups()
RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM outbox_topics WHERE topic = NEW.topic AND semantics = 'PUB_SUB') THEN
        -- Count active subscriptions for pub/sub topics
        NEW.required_consumer_groups := (
            SELECT COUNT(*)
            FROM outbox_topic_subscriptions
            WHERE topic = NEW.topic
              AND subscription_status = 'ACTIVE'
        );
    ELSE
        -- Queue semantics: only needs 1 consumer
        NEW.required_consumer_groups := 1;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_set_required_consumer_groups
    BEFORE INSERT ON outbox
    FOR EACH ROW
    EXECUTE FUNCTION set_required_consumer_groups();
```

#### Message Completion Logic
```java
private Future<Void> markMessageProcessedByGroup(Long messageId, String groupName) {
    String sql = """
        WITH group_processing AS (
            INSERT INTO outbox_consumer_groups (group_name, message_id, processed_at)
            VALUES ($1, $2, NOW())
            ON CONFLICT (group_name, message_id) DO NOTHING
            RETURNING message_id
        ),
        update_count AS (
            UPDATE outbox
            SET completed_consumer_groups = completed_consumer_groups + 1
            WHERE id = $2
              AND EXISTS (SELECT 1 FROM group_processing)
            RETURNING id, completed_consumer_groups, required_consumer_groups
        )
        UPDATE outbox
        SET status = 'COMPLETED', processed_at = NOW()
        WHERE id IN (
            SELECT id FROM update_count
            WHERE completed_consumer_groups >= required_consumer_groups
        )
        RETURNING id
        """;

    return pool.preparedQuery(sql)
        .execute(Tuple.of(groupName, messageId))
        .mapEmpty();
}
```

#### Cleanup Job
```java
public class OutboxCleanupJob {

    public Future<Integer> cleanupCompletedMessages() {
        String sql = """
            DELETE FROM outbox
            WHERE status = 'COMPLETED'
              AND processed_at < NOW() - INTERVAL '1 hour' * (
                  SELECT COALESCE(message_retention_hours, 24)
                  FROM outbox_topics
                  WHERE outbox_topics.topic = outbox.topic
              )
              AND (
                  required_consumer_groups = 1  -- Queue: delete immediately
                  OR
                  completed_consumer_groups >= required_consumer_groups  -- Pub/Sub: all groups done
              )
            RETURNING id
            """;

        return pool.preparedQuery(sql)
            .execute()
            .map(rows -> rows.size());
    }
}
```

---

### ⚠️ Question 3: Late-Joining Consumers

**Problem**: What happens if a new consumer group subscribes after messages already exist?

**Solution**: Configurable catch-up strategy with optional backfill.

#### Catch-Up Strategies
```java
public enum CatchUpStrategy {
    /**
     * Process all messages from the beginning of the topic.
     * Use for critical consumers that must process all historical data.
     */
    FROM_BEGINNING,

    /**
     * Process messages from subscription time onwards.
     * Use for non-critical consumers or when historical data isn't needed.
     */
    FROM_NOW,

    /**
     * Process messages from a specific timestamp.
     */
    FROM_TIMESTAMP,

    /**
     * Process messages from a specific message ID.
     */
    FROM_MESSAGE_ID
}
```

#### Backfill Implementation
```java
public class OutboxConsumerGroup<T> {

    /**
     * For late-joining consumers that need to process existing messages,
     * increment required_consumer_groups for all unprocessed messages.
     */
    private Future<Void> backfillRequiredConsumerGroups() {
        String sql = """
            UPDATE outbox
            SET required_consumer_groups = required_consumer_groups + 1
            WHERE topic = $1
              AND status IN ('PENDING', 'PROCESSING')
              AND id >= $2  -- From start position
              AND NOT EXISTS (
                  SELECT 1 FROM outbox_consumer_groups cg
                  WHERE cg.message_id = outbox.id
                    AND cg.group_name = $3
              )
            """;

        return pool.preparedQuery(sql)
            .execute(Tuple.of(topic, startMessageId, groupName))
            .onSuccess(rows ->
                logger.info("Backfilled {} messages for late-joining group '{}'",
                    rows.rowCount(), groupName))
            .mapEmpty();
    }
}
```

#### Query with Start Position
```java
private Future<Void> processPubSubSemantics() {
    String sql = """
        SELECT o.id, o.payload, o.headers, o.correlation_id, o.message_group, o.created_at
        FROM outbox o
        INNER JOIN outbox_topic_subscriptions s
            ON s.topic = o.topic AND s.group_name = $2
        WHERE o.topic = $1
          AND o.status = 'PENDING'
          -- Respect subscription start position
          AND (s.start_from_message_id IS NULL OR o.id >= s.start_from_message_id)
          AND (s.start_from_timestamp IS NULL OR o.created_at >= s.start_from_timestamp)
          AND NOT EXISTS (
              SELECT 1 FROM outbox_consumer_groups cg
              WHERE cg.message_id = o.id AND cg.group_name = $2
          )
        ORDER BY o.created_at ASC
        LIMIT $3
        FOR UPDATE SKIP LOCKED
        """;

    return pool.preparedQuery(sql).execute(Tuple.of(topic, groupName, batchSize))
        .compose(this::processMessages);
}
```

#### Usage Example
```java
// Existing consumer - only new messages
ConsumerGroup<OrderEvent> emailGroup =
    queueFactory.createConsumerGroup("email-service", "orders.events", OrderEvent.class);
emailGroup.start(SubscriptionOptions.fromNow());

// Late-joining consumer - process ALL historical data
ConsumerGroup<OrderEvent> analyticsGroup =
    queueFactory.createConsumerGroup("analytics-service", "orders.events", OrderEvent.class);
analyticsGroup.start(SubscriptionOptions.fromBeginning(true));

// Late-joining consumer - only new messages (no backfill)
ConsumerGroup<OrderEvent> reportingGroup =
    queueFactory.createConsumerGroup("reporting-service", "orders.events", OrderEvent.class);
analyticsGroup.start(SubscriptionOptions.fromNow());
```

---

### ⚠️ Question 4: Dead Consumer Groups

**Problem**: Consumer groups that never process messages block cleanup.

**Solution**: Heartbeat tracking with automatic detection and cleanup.

#### Heartbeat Implementation
```java
public class OutboxConsumerGroup<T> {

    private ScheduledExecutorService heartbeatScheduler;

    @Override
    public void start(SubscriptionOptions options) {
        // ... existing start logic

        if (getTopicSemantics(topic) == TopicSemantics.PUB_SUB) {
            registerSubscription(options)
                .compose(v -> startHeartbeat())
                .compose(v -> startPolling());
        }
    }

    private Future<Void> startHeartbeat() {
        int heartbeatInterval = configuration.getHeartbeatIntervalSeconds();

        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-" + groupName);
            t.setDaemon(true);
            return t;
        });

        heartbeatScheduler.scheduleAtFixedRate(
            this::sendHeartbeat,
            heartbeatInterval,
            heartbeatInterval,
            TimeUnit.SECONDS
        );

        logger.info("Started heartbeat for consumer group '{}' (interval: {}s)",
            groupName, heartbeatInterval);

        return Future.succeededFuture();
    }

    private void sendHeartbeat() {
        String sql = """
            UPDATE outbox_topic_subscriptions
            SET last_heartbeat_at = NOW(),
                last_active_at = NOW()
            WHERE topic = $1
              AND group_name = $2
              AND subscription_status = 'ACTIVE'
            """;

        pool.preparedQuery(sql)
            .execute(Tuple.of(topic, groupName))
            .onFailure(ex ->
                logger.error("Failed to send heartbeat for group '{}'", groupName, ex));
    }

    @Override
    public void close() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdown();
        }

        // Mark subscription as CANCELLED
        String sql = """
            UPDATE outbox_topic_subscriptions
            SET subscription_status = 'CANCELLED',
                last_active_at = NOW()
            WHERE topic = $1 AND group_name = $2
            """;

        pool.preparedQuery(sql)
            .execute(Tuple.of(topic, groupName))
            .await();
    }
}
```

#### Dead Consumer Group Detection
```java
public class DeadConsumerGroupCleanup {

    /**
     * Detects consumer groups that haven't sent heartbeat within timeout period.
     * Marks them as DEAD and decrements required_consumer_groups for pending messages.
     */
    public Future<List<String>> detectAndCleanupDeadConsumerGroups() {
        String detectSql = """
            UPDATE outbox_topic_subscriptions
            SET subscription_status = 'DEAD'
            WHERE subscription_status = 'ACTIVE'
              AND last_heartbeat_at < NOW() - (heartbeat_timeout_seconds || ' seconds')::INTERVAL
            RETURNING topic, group_name
            """;

        return pool.preparedQuery(detectSql)
            .execute()
            .compose(rows -> {
                List<String> deadGroups = new ArrayList<>();
                List<Future<Void>> cleanupFutures = new ArrayList<>();

                for (Row row : rows) {
                    String topic = row.getString("topic");
                    String groupName = row.getString("group_name");
                    deadGroups.add(groupName);

                    logger.warn("Detected dead consumer group '{}' for topic '{}'",
                        groupName, topic);

                    cleanupFutures.add(cleanupDeadGroupMessages(topic, groupName));
                }

                return Future.all(cleanupFutures).map(deadGroups);
            });
    }

    private Future<Void> cleanupDeadGroupMessages(String topic, String groupName) {
        String sql = """
            UPDATE outbox
            SET required_consumer_groups = required_consumer_groups - 1
            WHERE topic = $1
              AND status IN ('PENDING', 'PROCESSING')
              AND NOT EXISTS (
                  SELECT 1 FROM outbox_consumer_groups cg
                  WHERE cg.message_id = outbox.id AND cg.group_name = $2
              )
              AND required_consumer_groups > 0
            """;

        return pool.preparedQuery(sql)
            .execute(Tuple.of(topic, groupName))
            .onSuccess(rows ->
                logger.info("Decremented required_consumer_groups for {} messages from dead group '{}'",
                    rows.rowCount(), groupName))
            .mapEmpty();
    }

    /**
     * Scheduled cleanup job - runs every 5 minutes
     */
    public void startDeadConsumerGroupCleanup(Duration interval) {
        scheduler.scheduleAtFixedRate(
            () -> detectAndCleanupDeadConsumerGroups()
                .onFailure(ex -> logger.error("Dead consumer group cleanup failed", ex)),
            interval.toMillis(),
            interval.toMillis(),
            TimeUnit.MILLISECONDS
        );
    }
}
```

#### Manual Admin Operations
```java
public interface ConsumerGroupAdmin {

    /**
     * Manually mark a consumer group as dead and cleanup its pending messages.
     */
    Future<Void> removeConsumerGroup(String topic, String groupName);

    /**
     * List all consumer groups and their health status.
     */
    Future<List<ConsumerGroupHealth>> listConsumerGroups(String topic);

    /**
     * Force cleanup of messages stuck waiting for dead consumer groups.
     */
    Future<Integer> forceCompleteOrphanedMessages(String topic, Duration olderThan);
}

public class ConsumerGroupHealth {
    private final String groupName;
    private final String topic;
    private final String status;  // ACTIVE, DEAD, CANCELLED
    private final Instant lastHeartbeat;
    private final long pendingMessages;
    private final long processedMessages;
}
```

---

## Database Schema Changes

### Summary of All Schema Changes

```sql
-- 1. Topic configuration table
CREATE TABLE IF NOT EXISTS outbox_topics (
    topic VARCHAR(255) PRIMARY KEY,
    semantics VARCHAR(20) NOT NULL DEFAULT 'QUEUE'
        CHECK (semantics IN ('QUEUE', 'PUB_SUB')),
    message_retention_hours INT DEFAULT 24,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 2. Consumer group subscriptions table
CREATE TABLE IF NOT EXISTS outbox_topic_subscriptions (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    subscribed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_active_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    subscription_status VARCHAR(20) DEFAULT 'ACTIVE'
        CHECK (subscription_status IN ('ACTIVE', 'PAUSED', 'CANCELLED', 'DEAD')),

    -- Offset tracking
    start_from_message_id BIGINT,
    start_from_timestamp TIMESTAMP WITH TIME ZONE,

    -- Heartbeat tracking
    heartbeat_interval_seconds INT DEFAULT 60,
    heartbeat_timeout_seconds INT DEFAULT 300,
    last_heartbeat_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

    UNIQUE(topic, group_name),
    FOREIGN KEY (topic) REFERENCES outbox_topics(topic) ON DELETE CASCADE
);

CREATE INDEX idx_topic_subscriptions_topic
    ON outbox_topic_subscriptions(topic)
    WHERE subscription_status = 'ACTIVE';

CREATE INDEX idx_topic_subscriptions_heartbeat
    ON outbox_topic_subscriptions(last_heartbeat_at)
    WHERE subscription_status = 'ACTIVE';

-- 3. Modify outbox table (add reference counting columns)
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS
    required_consumer_groups INT DEFAULT 0;

ALTER TABLE outbox ADD COLUMN IF NOT EXISTS
    completed_consumer_groups INT DEFAULT 0;

CREATE INDEX idx_outbox_completion_tracking
    ON outbox(topic, status, completed_consumer_groups, required_consumer_groups)
    WHERE status IN ('PENDING', 'PROCESSING');

-- 4. Trigger to set required_consumer_groups
CREATE OR REPLACE FUNCTION set_required_consumer_groups()
RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM outbox_topics WHERE topic = NEW.topic AND semantics = 'PUB_SUB') THEN
        NEW.required_consumer_groups := (
            SELECT COUNT(*)
            FROM outbox_topic_subscriptions
            WHERE topic = NEW.topic
              AND subscription_status = 'ACTIVE'
        );
    ELSE
        NEW.required_consumer_groups := 1;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_set_required_consumer_groups
    BEFORE INSERT ON outbox
    FOR EACH ROW
    EXECUTE FUNCTION set_required_consumer_groups();

-- 5. outbox_consumer_groups table (already exists, no changes needed)
-- This table tracks which consumer groups have processed which messages
```

---

## API Design

### Topic Configuration

```java
// Configure a topic as pub/sub
queueFactory.configureTopic(
    TopicConfiguration.pubSub("orders.events", Duration.ofHours(24))
);

// Configure a topic as queue (default)
queueFactory.configureTopic(
    TopicConfiguration.queue("orders.tasks")
);
```

### Consumer Group Creation and Subscription

```java
// Create consumer group
ConsumerGroup<OrderEvent> emailGroup = queueFactory.createConsumerGroup(
    "email-service",           // group name
    "orders.events",           // topic
    OrderEvent.class           // payload type
);

// Add consumers to the group
emailGroup.addConsumer("email-worker-1", emailHandler);
emailGroup.addConsumer("email-worker-2", emailHandler);

// Start with different subscription options
emailGroup.start(SubscriptionOptions.fromNow());                    // Only new messages
emailGroup.start(SubscriptionOptions.fromBeginning(true));          // All messages + backfill
emailGroup.start(SubscriptionOptions.fromTimestamp(timestamp));     // From specific time
```

### Complete Example

```java
// 1. Configure topic as pub/sub
queueFactory.configureTopic(
    TopicConfiguration.pubSub("orders.events", Duration.ofHours(24))
);

// 2. Create multiple independent consumer groups
ConsumerGroup<OrderEvent> emailGroup =
    queueFactory.createConsumerGroup("email-service", "orders.events", OrderEvent.class);
ConsumerGroup<OrderEvent> analyticsGroup =
    queueFactory.createConsumerGroup("analytics-service", "orders.events", OrderEvent.class);
ConsumerGroup<OrderEvent> inventoryGroup =
    queueFactory.createConsumerGroup("inventory-service", "orders.events", OrderEvent.class);

// 3. Add handlers
emailGroup.addConsumer("email-1", orderEvent -> sendEmail(orderEvent));
analyticsGroup.addConsumer("analytics-1", orderEvent -> trackMetrics(orderEvent));
inventoryGroup.addConsumer("inventory-1", orderEvent -> updateStock(orderEvent));

// 4. Start all groups
emailGroup.start(SubscriptionOptions.fromNow());
analyticsGroup.start(SubscriptionOptions.fromNow());
inventoryGroup.start(SubscriptionOptions.fromNow());

// 5. Publish message - will be delivered to ALL three groups
producer.send(new OrderCreatedEvent(...));
```

---

## Implementation Details

### Consumer Query Logic (Conditional)

```java
public class OutboxConsumer<T> {

    private Future<Void> processAvailableMessagesReactive() {
        TopicSemantics semantics = getTopicSemantics(topic);

        if (semantics == TopicSemantics.QUEUE) {
            return processQueueSemantics();
        } else {
            return processPubSubSemantics();
        }
    }

    /**
     * Existing queue behavior - UNCHANGED
     */
    private Future<Void> processQueueSemantics() {
        String sql = """
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
            """;

        return pool.preparedQuery(sql)
            .execute(Tuple.of(OffsetDateTime.now(), topic, batchSize))
            .compose(this::processMessages);
    }

    /**
     * New pub/sub behavior - only fetch messages not yet processed by this group
     */
    private Future<Void> processPubSubSemantics() {
        String sql = """
            SELECT o.id, o.payload, o.headers, o.correlation_id, o.message_group, o.created_at
            FROM outbox o
            INNER JOIN outbox_topic_subscriptions s
                ON s.topic = o.topic AND s.group_name = $2
            WHERE o.topic = $1
              AND o.status = 'PENDING'
              AND (s.start_from_message_id IS NULL OR o.id >= s.start_from_message_id)
              AND (s.start_from_timestamp IS NULL OR o.created_at >= s.start_from_timestamp)
              AND NOT EXISTS (
                  SELECT 1 FROM outbox_consumer_groups cg
                  WHERE cg.message_id = o.id
                    AND cg.group_name = $2
              )
            ORDER BY o.created_at ASC
            LIMIT $3
            FOR UPDATE SKIP LOCKED
            """;

        return pool.preparedQuery(sql)
            .execute(Tuple.of(topic, consumerGroupName, batchSize))
            .compose(this::processMessages);
    }

    /**
     * Message completion logic - different for queue vs pub/sub
     */
    private Future<Void> markMessageCompleted(Long messageId, TopicSemantics semantics) {
        if (semantics == TopicSemantics.QUEUE) {
            // Existing: Mark as COMPLETED immediately
            return updateMessageStatus(messageId, "COMPLETED");
        } else {
            // New: Track in consumer_groups table and check if all groups done
            return markMessageProcessedByGroup(messageId, consumerGroupName);
        }
    }
}
```

### Message Processing Flow

#### Queue Semantics (Existing)
```
1. SELECT messages WHERE status = 'PENDING'
2. UPDATE status = 'PROCESSING' (atomic with SELECT via FOR UPDATE SKIP LOCKED)
3. Process message
4. UPDATE status = 'COMPLETED'
5. Message can be deleted after retention period
```

#### Pub/Sub Semantics (New)
```
1. SELECT messages WHERE status = 'PENDING'
   AND NOT IN outbox_consumer_groups for this group
   AND respects subscription start position
2. Process message (status stays 'PENDING')
3. INSERT into outbox_consumer_groups (group_name, message_id)
4. UPDATE completed_consumer_groups = completed_consumer_groups + 1
5. IF completed_consumer_groups >= required_consumer_groups:
   UPDATE status = 'COMPLETED'
6. Message deleted after retention period AND all groups processed
```

### Performance Considerations

#### Index Strategy
```sql
-- Critical indexes for pub/sub performance
CREATE INDEX idx_outbox_pubsub_pending
    ON outbox(topic, status, created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_consumer_groups_lookup
    ON outbox_consumer_groups(message_id, group_name);

CREATE INDEX idx_topic_subscriptions_active
    ON outbox_topic_subscriptions(topic, subscription_status)
    WHERE subscription_status = 'ACTIVE';
```

#### Query Performance
- **Queue semantics**: No change - same efficient `FOR UPDATE SKIP LOCKED`
- **Pub/Sub semantics**:
  - Additional JOIN with `outbox_topic_subscriptions`
  - NOT EXISTS check against `outbox_consumer_groups`
  - Should still be efficient with proper indexes
  - Consider partitioning for very high-volume topics

---

## Migration Path

### Phase 1: Schema Changes (Backward Compatible)
1. Add `outbox_topics` table
2. Add `outbox_topic_subscriptions` table
3. Add `required_consumer_groups` and `completed_consumer_groups` columns to `outbox`
4. Add trigger `set_required_consumer_groups`
5. Create indexes

**Impact**: None - existing code continues to work

### Phase 2: API Extensions
1. Add `TopicSemantics` enum
2. Add `TopicConfiguration` class
3. Add `SubscriptionOptions` class
4. Extend `ConsumerGroup` interface with `start(SubscriptionOptions)`

**Impact**: None - backward compatible API additions

### Phase 3: Consumer Logic Updates
1. Add `getTopicSemantics()` method
2. Implement conditional query logic in `OutboxConsumer`
3. Implement `processPubSubSemantics()` method
4. Update message completion logic

**Impact**: Existing consumers work unchanged (default to QUEUE semantics)

### Phase 4: Heartbeat & Cleanup
1. Implement heartbeat mechanism
2. Implement dead consumer group detection
3. Add cleanup jobs
4. Add admin operations

**Impact**: None - opt-in for pub/sub topics only

### Phase 5: Testing & Documentation
1. Integration tests for pub/sub scenarios
2. Performance tests
3. Update documentation
4. Migration guide for existing applications

### Migration Example

```java
// Before: Queue semantics (implicit)
MessageConsumer<OrderEvent> consumer =
    queueFactory.createConsumer("orders", OrderEvent.class);
consumer.subscribe(handler);

// After: Explicit queue semantics (same behavior)
queueFactory.configureTopic(TopicConfiguration.queue("orders"));
MessageConsumer<OrderEvent> consumer =
    queueFactory.createConsumer("orders", OrderEvent.class);
consumer.subscribe(handler);

// New: Pub/Sub semantics
queueFactory.configureTopic(
    TopicConfiguration.pubSub("orders.events", Duration.ofHours(24))
);
ConsumerGroup<OrderEvent> group1 =
    queueFactory.createConsumerGroup("service-1", "orders.events", OrderEvent.class);
ConsumerGroup<OrderEvent> group2 =
    queueFactory.createConsumerGroup("service-2", "orders.events", OrderEvent.class);
group1.start(SubscriptionOptions.fromNow());
group2.start(SubscriptionOptions.fromNow());
```

---

## Comparison with Other Systems

### Kafka
| Feature | Kafka | PeeGeeQ Pub/Sub |
|---------|-------|-----------------|
| **Semantics** | Pub/Sub (log-based) | Pub/Sub (database-based) |
| **Consumer Groups** | Automatic partition assignment | Manual consumer registration |
| **Offset Management** | Consumer commits offsets | Database tracks per-group processing |
| **Late Joiners** | Can read from beginning | Configurable with backfill option |
| **Message Retention** | Time-based | Reference counting + time-based |
| **Dead Consumers** | Rebalancing protocol | Heartbeat + cleanup job |
| **Ordering** | Per-partition | Per message_group (if implemented) |

### RabbitMQ
| Feature | RabbitMQ | PeeGeeQ Pub/Sub |
|---------|----------|-----------------|
| **Semantics** | Pub/Sub (exchange/queue) | Pub/Sub (database-based) |
| **Fan-Out** | Exchange routing | Consumer group registration |
| **Acknowledgment** | Per-message ack | Database transaction |
| **Dead Letter** | DLX/DLQ | Status tracking + retry |
| **Persistence** | Optional (durable queues) | Always (PostgreSQL) |
| **Ordering** | Per-queue | Per message_group |

### Pulsar
| Feature | Pulsar | PeeGeeQ Pub/Sub |
|---------|--------|-----------------|
| **Semantics** | Pub/Sub + Queue | Hybrid (configurable) |
| **Subscriptions** | Exclusive/Shared/Failover/Key_Shared | Consumer groups |
| **Message Retention** | Acknowledgment-based | Reference counting |
| **Late Joiners** | Reader API | Subscription start position |
| **Multi-tenancy** | Built-in | Topic-based |

### PeeGeeQ Advantages
✅ **Transactional Guarantees**: PostgreSQL ACID properties
✅ **No Additional Infrastructure**: Uses existing database
✅ **Simple Operations**: Standard SQL queries and monitoring
✅ **Flexible Semantics**: Queue or Pub/Sub per topic
✅ **Strong Consistency**: No eventual consistency issues

### PeeGeeQ Limitations
⚠️ **Scalability**: Limited by PostgreSQL performance
⚠️ **Latency**: Higher than dedicated message brokers
⚠️ **Throughput**: Lower than Kafka/Pulsar for high-volume scenarios
⚠️ **Partitioning**: Manual implementation via message_group

---

## Summary

### Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Hybrid Queue/Pub-Sub** | Backward compatibility + new capabilities |
| **Explicit Topic Configuration** | Clear intent, no surprises |
| **Reference Counting** | Ensures messages not deleted until all groups process |
| **Heartbeat Tracking** | Automatic detection of dead consumer groups |
| **Configurable Catch-Up** | Flexibility for late-joining consumers |
| **Database-Driven** | Leverages PostgreSQL strengths (ACID, consistency) |

### Critical Questions Answered

| Question | Solution |
|----------|----------|
| ⚠️ **Consumer Group Registration** | Explicit subscription via `outbox_topic_subscriptions` table |
| ⚠️ **Message Retention** | Reference counting with `required_consumer_groups` / `completed_consumer_groups` |
| ⚠️ **Late-Joining Consumers** | Configurable start position with optional backfill |
| ⚠️ **Dead Consumer Groups** | Heartbeat tracking + automatic cleanup job |

### Next Steps

1. **Review and Approve Design** - Stakeholder sign-off
2. **Create Implementation Tasks** - Break down into phases
3. **Prototype Core Logic** - Validate pub/sub query performance
4. **Write Integration Tests** - Cover all scenarios
5. **Implement Phase 1** - Schema changes
6. **Implement Phase 2-4** - API and logic updates
7. **Performance Testing** - Validate scalability
8. **Documentation** - Update guides and examples
9. **Migration Support** - Help existing applications adopt pub/sub

---

## Appendix: Complete Schema DDL

```sql
-- Complete schema for pub/sub support

-- 1. Topic configuration
CREATE TABLE IF NOT EXISTS outbox_topics (
    topic VARCHAR(255) PRIMARY KEY,
    semantics VARCHAR(20) NOT NULL DEFAULT 'QUEUE'
        CHECK (semantics IN ('QUEUE', 'PUB_SUB')),
    message_retention_hours INT DEFAULT 24,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 2. Consumer group subscriptions
CREATE TABLE IF NOT EXISTS outbox_topic_subscriptions (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    subscribed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_active_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    subscription_status VARCHAR(20) DEFAULT 'ACTIVE'
        CHECK (subscription_status IN ('ACTIVE', 'PAUSED', 'CANCELLED', 'DEAD')),
    start_from_message_id BIGINT,
    start_from_timestamp TIMESTAMP WITH TIME ZONE,
    heartbeat_interval_seconds INT DEFAULT 60,
    heartbeat_timeout_seconds INT DEFAULT 300,
    last_heartbeat_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(topic, group_name),
    FOREIGN KEY (topic) REFERENCES outbox_topics(topic) ON DELETE CASCADE
);

CREATE INDEX idx_topic_subscriptions_topic
    ON outbox_topic_subscriptions(topic)
    WHERE subscription_status = 'ACTIVE';

CREATE INDEX idx_topic_subscriptions_heartbeat
    ON outbox_topic_subscriptions(last_heartbeat_at)
    WHERE subscription_status = 'ACTIVE';

-- 3. Modify outbox table
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS required_consumer_groups INT DEFAULT 0;
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS completed_consumer_groups INT DEFAULT 0;

CREATE INDEX idx_outbox_completion_tracking
    ON outbox(topic, status, completed_consumer_groups, required_consumer_groups)
    WHERE status IN ('PENDING', 'PROCESSING');

CREATE INDEX idx_outbox_pubsub_pending
    ON outbox(topic, status, created_at)
    WHERE status = 'PENDING';

-- 4. Trigger function
CREATE OR REPLACE FUNCTION set_required_consumer_groups()
RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM outbox_topics WHERE topic = NEW.topic AND semantics = 'PUB_SUB') THEN
        NEW.required_consumer_groups := (
            SELECT COUNT(*)
            FROM outbox_topic_subscriptions
            WHERE topic = NEW.topic
              AND subscription_status = 'ACTIVE'
        );
    ELSE
        NEW.required_consumer_groups := 1;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_set_required_consumer_groups
    BEFORE INSERT ON outbox
    FOR EACH ROW
    EXECUTE FUNCTION set_required_consumer_groups();

-- 5. Consumer groups tracking (already exists)
CREATE INDEX IF NOT EXISTS idx_consumer_groups_lookup
    ON outbox_consumer_groups(message_id, group_name);
```

---

**End of Document**


