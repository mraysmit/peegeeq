# Consumer Group Fan-Out Guide

**Author**: Mark Andrew Ray-Smith, Cityline Ltd  
**Date**: December 2025  
**Version**: 1.0

---

## Overview

PeeGeeQ supports **hybrid queue/pub-sub semantics** that allow you to configure topics to either distribute messages to one consumer (QUEUE) or replicate messages to multiple independent consumer groups (PUB_SUB).

### Key Features

- ✅ **At-least-once delivery** to multiple independent consumer groups
- ✅ **Backward compatible** with existing queue-based consumers
- ✅ **Configurable catch-up** behavior for late-joining consumers
- ✅ **Automatic cleanup** when all consumer groups complete processing
- ✅ **Zero-subscription protection** to prevent data loss
- ✅ **Concurrent-safe** message fetching with PostgreSQL row-level locking

### When to Use Fan-Out

**Use PUB_SUB semantics when:**
- Multiple services need to process the same event independently
- You need event broadcasting (e.g., order created → email, analytics, inventory)
- Consumer groups can join/leave dynamically
- Each consumer group maintains its own processing state

**Use QUEUE semantics when:**
- Only one consumer should process each message
- You need work distribution across multiple workers
- Traditional task queue behavior is sufficient

---

## Quick Start

### Step 1: Configure a Topic as PUB_SUB

```java
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;

// Create topic configuration service
TopicConfigService topicConfigService = new TopicConfigService(connectionManager);

// Configure topic as PUB_SUB
TopicConfig config = TopicConfig.builder()
    .topic("orders.events")
    .semantics(TopicSemantics.PUB_SUB)
    .messageRetentionHours(24)
    .zeroSubscriptionRetentionHours(24)
    .blockWritesOnZeroSubscriptions(false)
    .build();

topicConfigService.createTopic(config)
    .toCompletionStage()
    .toCompletableFuture()
    .get();
```

### Step 2: Create Consumer Groups

```java
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;

// Create multiple consumer groups for the same topic
ConsumerGroup<OrderEvent> emailService = queueFactory.createConsumerGroup(
    "email-service",
    "orders.events",
    OrderEvent.class
);

ConsumerGroup<OrderEvent> analyticsService = queueFactory.createConsumerGroup(
    "analytics-service",
    "orders.events",
    OrderEvent.class
);

ConsumerGroup<OrderEvent> inventoryService = queueFactory.createConsumerGroup(
    "inventory-service",
    "orders.events",
    OrderEvent.class
);
```

### Step 3: Set Message Handlers

```java
// Email service handler
emailService.setMessageHandler(message -> {
    OrderEvent order = message.getPayload();
    sendOrderConfirmationEmail(order);
    return CompletableFuture.completedFuture(null);
});

// Analytics service handler
analyticsService.setMessageHandler(message -> {
    OrderEvent order = message.getPayload();
    trackOrderMetrics(order);
    return CompletableFuture.completedFuture(null);
});

// Inventory service handler
inventoryService.setMessageHandler(message -> {
    OrderEvent order = message.getPayload();
    updateInventory(order);
    return CompletableFuture.completedFuture(null);
});
```

### Step 4: Start Consumer Groups

```java
// Start all consumer groups
emailService.start();
analyticsService.start();
inventoryService.start();
```

### Step 5: Publish Messages

```java
// Publish a message - it will be delivered to ALL three consumer groups
OrderEvent orderEvent = new OrderEvent(orderId, customerId, amount);
producer.send("orders.events", orderEvent)
    .toCompletionStage()
    .toCompletableFuture()
    .get();
```

---

## Configuration

### Topic Configuration Options

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `topic` | String | Required | Topic name |
| `semantics` | TopicSemantics | QUEUE | QUEUE or PUB_SUB |
| `messageRetentionHours` | int | 24 | How long to keep completed messages |
| `zeroSubscriptionRetentionHours` | int | 24 | Retention when no subscriptions exist |
| `blockWritesOnZeroSubscriptions` | boolean | false | Block writes when no active subscriptions |
| `completionTrackingMode` | String | REFERENCE_COUNTING | Tracking mode (only REFERENCE_COUNTING implemented) |

### Subscription Options

```java
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.messaging.StartPosition;

// Start from now (only new messages)
SubscriptionOptions fromNow = SubscriptionOptions.builder()
    .startPosition(StartPosition.FROM_NOW)
    .build();

// Start from beginning (all historical messages)
SubscriptionOptions fromBeginning = SubscriptionOptions.builder()
    .startPosition(StartPosition.FROM_BEGINNING)
    .build();

// Start from specific timestamp
SubscriptionOptions fromTimestamp = SubscriptionOptions.builder()
    .startPosition(StartPosition.FROM_TIMESTAMP)
    .startFromTimestamp(Instant.parse("2025-01-01T00:00:00Z"))
    .build();

// Start from specific message ID
SubscriptionOptions fromMessageId = SubscriptionOptions.builder()
    .startPosition(StartPosition.FROM_MESSAGE_ID)
    .startFromMessageId(12345L)
    .build();
```

---

## How It Works

### Reference Counting Mode

PeeGeeQ uses **Reference Counting** to track message completion across multiple consumer groups:

1. **Message Insertion**: When a message is inserted, a trigger automatically sets `required_consumer_groups` to the count of ACTIVE subscriptions
2. **Message Processing**: Each consumer group fetches and processes messages independently
3. **Completion Tracking**: When a group completes a message, `completed_consumer_groups` is incremented
4. **Message Cleanup**: When `completed_consumer_groups >= required_consumer_groups`, the message is marked COMPLETED and eligible for cleanup

### Database Schema

**outbox table** (fanout columns):
```sql
ALTER TABLE outbox ADD COLUMN required_consumer_groups INT DEFAULT 1;
ALTER TABLE outbox ADD COLUMN completed_consumer_groups INT DEFAULT 0;
```

**outbox_consumer_groups table** (tracking):
```sql
CREATE TABLE outbox_consumer_groups (
    id BIGSERIAL PRIMARY KEY,
    message_id BIGINT NOT NULL REFERENCES outbox(id),
    group_name VARCHAR(255) NOT NULL,
    status VARCHAR(50) DEFAULT 'PENDING',
    processed_at TIMESTAMP WITH TIME ZONE,
    error_message TEXT,
    retry_count INT DEFAULT 0,
    UNIQUE(message_id, group_name)
);
```

**outbox_topics table** (configuration):
```sql
CREATE TABLE outbox_topics (
    topic VARCHAR(255) PRIMARY KEY,
    semantics VARCHAR(20) DEFAULT 'QUEUE',
    message_retention_hours INT DEFAULT 24,
    zero_subscription_retention_hours INT DEFAULT 24,
    block_writes_on_zero_subscriptions BOOLEAN DEFAULT FALSE,
    completion_tracking_mode VARCHAR(20) DEFAULT 'REFERENCE_COUNTING'
);
```

**outbox_topic_subscriptions table** (subscriptions):
```sql
CREATE TABLE outbox_topic_subscriptions (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    subscription_status VARCHAR(20) DEFAULT 'ACTIVE',
    start_from_message_id BIGINT,
    start_from_timestamp TIMESTAMP WITH TIME ZONE,
    last_heartbeat_at TIMESTAMP WITH TIME ZONE,
    UNIQUE(topic, group_name)
);
```

---

## Subscription Lifecycle

### Subscription States

```
ACTIVE ──────────────────────────────────────────────────┐
  │                                                       │
  │ (manual pause)                                        │
  ├──────────────> PAUSED                                 │
  │                  │                                    │
  │                  │ (manual resume)                    │
  │                  └──────────────> ACTIVE              │
  │                                                       │
  │ (heartbeat timeout)                                   │
  ├──────────────> DEAD ──────────────────────────────────┤
  │                  │                                    │
  │                  │ (heartbeat received)               │
  │                  └──> ACTIVE (resurrection)           │
  │                                                       │
  │ (manual cancel / graceful shutdown)                   │
  └──────────────> CANCELLED (terminal state)            │
```

### Managing Subscriptions

```java
import dev.mars.peegeeq.db.subscription.SubscriptionManager;

SubscriptionManager subscriptionManager = new SubscriptionManager(connectionManager);

// Subscribe a consumer group
subscriptionManager.subscribe("orders.events", "email-service", SubscriptionOptions.defaults())
    .toCompletionStage()
    .toCompletableFuture()
    .get();

// Pause a subscription
subscriptionManager.pause("orders.events", "email-service")
    .toCompletionStage()
    .toCompletableFuture()
    .get();

// Resume a subscription
subscriptionManager.resume("orders.events", "email-service")
    .toCompletionStage()
    .toCompletableFuture()
    .get();

// Cancel a subscription (terminal - cannot be resumed)
subscriptionManager.cancel("orders.events", "email-service")
    .toCompletionStage()
    .toCompletableFuture()
    .get();

// Update heartbeat (prevents DEAD status)
subscriptionManager.updateHeartbeat("orders.events", "email-service")
    .toCompletionStage()
    .toCompletableFuture()
    .get();
```

---

## Zero-Subscription Protection

### The Problem

If a PUB_SUB topic has zero active subscriptions when a message is published, the message could be immediately eligible for cleanup, causing data loss.

### The Solution

PeeGeeQ provides **two complementary strategies**:

#### 1. Minimum Retention Period (Default)

Messages with `required_consumer_groups = 0` are retained for a configurable period before cleanup:

```java
TopicConfig config = TopicConfig.builder()
    .topic("orders.events")
    .semantics(TopicSemantics.PUB_SUB)
    .zeroSubscriptionRetentionHours(24)  // Keep for 24 hours even with no subscriptions
    .build();
```

**Recommended retention periods:**
- **Critical topics** (orders, payments, audit): 168 hours (7 days)
- **Standard topics**: 24 hours (default)
- **Ephemeral topics** (metrics, logs): 1 hour

#### 2. Write Blocking (Optional, Strict Mode)

Optionally block writes when zero ACTIVE subscriptions exist:

```java
TopicConfig config = TopicConfig.builder()
    .topic("orders.events")
    .semantics(TopicSemantics.PUB_SUB)
    .blockWritesOnZeroSubscriptions(true)  // Fail-fast if no subscriptions
    .build();
```

**Validation before writing:**

```java
import dev.mars.peegeeq.db.subscription.ZeroSubscriptionValidator;

ZeroSubscriptionValidator validator = new ZeroSubscriptionValidator(connectionManager, "my-service");

// Check if write is allowed
validator.isWriteAllowed("orders.events")
    .compose(allowed -> {
        if (!allowed) {
            return Future.failedFuture(new IllegalStateException("No active subscriptions"));
        }
        return producer.send("orders.events", orderEvent);
    });

// Or use validateWriteAllowed (throws exception if blocked)
validator.validateWriteAllowed("orders.events")
    .compose(v -> producer.send("orders.events", orderEvent));
```

---

## Best Practices

### 1. Topic Configuration

**✅ DO:**
- Configure topics explicitly before first use
- Use descriptive topic names (e.g., `orders.created`, `user.updated`)
- Set appropriate retention periods based on business requirements
- Use PUB_SUB for event broadcasting, QUEUE for task distribution

**❌ DON'T:**
- Mix QUEUE and PUB_SUB semantics on the same topic
- Set retention periods too short (risk data loss)
- Use `blockWritesOnZeroSubscriptions=true` without monitoring

### 2. Consumer Group Naming

**✅ DO:**
- Use service/component names (e.g., `email-service`, `analytics-service`)
- Keep names stable across deployments
- Use consistent naming conventions

**❌ DON'T:**
- Use instance-specific names (e.g., `email-service-pod-123`)
- Change group names between deployments (creates duplicate subscriptions)
- Use generic names (e.g., `consumer1`, `consumer2`)

### 3. Subscription Management

**✅ DO:**
- Subscribe consumer groups at application startup
- Update heartbeats regularly (every 30-60 seconds)
- Use `FROM_NOW` for new consumer groups unless catch-up is required
- Gracefully cancel subscriptions on shutdown

**❌ DON'T:**
- Create subscriptions dynamically per message
- Forget to update heartbeats (risk DEAD status)
- Use `FROM_BEGINNING` without understanding backfill implications

### 4. Error Handling

**✅ DO:**
- Implement retry logic with exponential backoff
- Log failures with context (message ID, group name, error)
- Monitor failed message counts
- Set up alerts for persistent failures

**❌ DON'T:**
- Silently swallow exceptions
- Retry indefinitely without backoff
- Mark messages as completed on failure

### 5. Performance Optimization

**✅ DO:**
- Use batch fetching when possible
- Process messages asynchronously
- Monitor consumer lag
- Scale consumer groups horizontally

**❌ DON'T:**
- Fetch one message at a time in tight loops
- Block message processing with synchronous I/O
- Ignore consumer lag warnings

---

## Troubleshooting

### Messages Not Being Delivered to Consumer Group

**Symptoms:**
- Consumer group is running but not receiving messages
- Other consumer groups on the same topic are receiving messages

**Possible Causes:**

1. **Subscription is PAUSED or CANCELLED**
   ```sql
   SELECT subscription_status
   FROM outbox_topic_subscriptions
   WHERE topic = 'orders.events' AND group_name = 'email-service';
   ```
   **Solution:** Resume or re-subscribe the consumer group

2. **Subscription start position is in the future**
   ```sql
   SELECT start_from_message_id, start_from_timestamp
   FROM outbox_topic_subscriptions
   WHERE topic = 'orders.events' AND group_name = 'email-service';
   ```
   **Solution:** Update start position or wait for new messages

3. **Messages already processed**
   ```sql
   SELECT COUNT(*)
   FROM outbox_consumer_groups
   WHERE group_name = 'email-service' AND status = 'COMPLETED';
   ```
   **Solution:** Check if messages were already processed

### Messages Not Being Cleaned Up

**Symptoms:**
- `outbox` table growing indefinitely
- Old COMPLETED messages not being deleted

**Possible Causes:**

1. **Consumer group not completing messages**
   ```sql
   SELECT message_id, group_name, status
   FROM outbox_consumer_groups
   WHERE status != 'COMPLETED'
   ORDER BY message_id DESC
   LIMIT 100;
   ```
   **Solution:** Investigate why consumer groups are not completing messages

2. **Cleanup job not running**
   ```sql
   SELECT cleanup_completed_outbox_messages();
   ```
   **Solution:** Verify cleanup job is scheduled and running

3. **Retention period too long**
   ```sql
   SELECT topic, message_retention_hours
   FROM outbox_topics
   WHERE topic = 'orders.events';
   ```
   **Solution:** Adjust retention period if appropriate

### Consumer Group Marked as DEAD

**Symptoms:**
- Subscription status changed to DEAD
- Consumer group stopped receiving messages

**Possible Causes:**

1. **Heartbeat timeout**
   ```sql
   SELECT last_heartbeat_at, NOW() - last_heartbeat_at AS time_since_heartbeat
   FROM outbox_topic_subscriptions
   WHERE topic = 'orders.events' AND group_name = 'email-service';
   ```
   **Solution:** Ensure heartbeat updates are running (default timeout: 5 minutes)

2. **Consumer group crashed**
   **Solution:** Restart consumer group, heartbeat will resurrect subscription

### Write Blocked Due to Zero Subscriptions

**Symptoms:**
- `NoActiveSubscriptionsException` thrown on write
- Topic has `block_writes_on_zero_subscriptions = true`

**Possible Causes:**

1. **No active subscriptions**
   ```sql
   SELECT COUNT(*)
   FROM outbox_topic_subscriptions
   WHERE topic = 'orders.events' AND subscription_status = 'ACTIVE';
   ```
   **Solution:** Subscribe at least one consumer group before writing

2. **All subscriptions PAUSED or CANCELLED**
   ```sql
   SELECT group_name, subscription_status
   FROM outbox_topic_subscriptions
   WHERE topic = 'orders.events';
   ```
   **Solution:** Resume or create active subscriptions

---

## Monitoring

### Key Metrics to Track

1. **Consumer Lag**
   ```sql
   SELECT
       s.topic,
       s.group_name,
       COUNT(CASE WHEN cg.status IS NULL THEN 1 END) AS pending_messages,
       COUNT(CASE WHEN cg.status = 'COMPLETED' THEN 1 END) AS completed_messages,
       COUNT(CASE WHEN cg.status = 'FAILED' THEN 1 END) AS failed_messages
   FROM outbox_topic_subscriptions s
   CROSS JOIN outbox o
   LEFT JOIN outbox_consumer_groups cg
       ON cg.message_id = o.id AND cg.group_name = s.group_name
   WHERE s.subscription_status = 'ACTIVE'
     AND o.topic = s.topic
     AND o.id >= COALESCE(s.start_from_message_id, 0)
   GROUP BY s.topic, s.group_name;
   ```

2. **Subscription Health**
   ```sql
   SELECT
       topic,
       group_name,
       subscription_status,
       last_heartbeat_at,
       NOW() - last_heartbeat_at AS heartbeat_age
   FROM outbox_topic_subscriptions
   ORDER BY topic, group_name;
   ```

3. **Message Completion Rate**
   ```sql
   SELECT
       o.topic,
       COUNT(*) AS total_messages,
       AVG(o.completed_consumer_groups::FLOAT / NULLIF(o.required_consumer_groups, 0)) AS avg_completion_rate,
       COUNT(CASE WHEN o.status = 'COMPLETED' THEN 1 END) AS fully_completed
   FROM outbox o
   WHERE o.created_at > NOW() - INTERVAL '1 hour'
   GROUP BY o.topic;
   ```

4. **Zero-Subscription Messages**
   ```sql
   SELECT
       topic,
       COUNT(*) AS zero_subscription_messages,
       MIN(created_at) AS oldest_message
   FROM outbox
   WHERE required_consumer_groups = 0
     AND status != 'COMPLETED'
   GROUP BY topic;
   ```

### Alerts to Configure

- **Consumer lag > threshold** (e.g., 1000 messages)
- **Consumer group DEAD** for > 5 minutes
- **Failed message count increasing**
- **Zero-subscription messages accumulating**
- **Cleanup job failures**

---

## Migration Guide

### Migrating from QUEUE to PUB_SUB

If you have an existing QUEUE topic and want to add fan-out:

1. **Configure topic as PUB_SUB**
   ```java
   topicConfigService.updateTopic("orders.events",
       TopicConfig.builder()
           .topic("orders.events")
           .semantics(TopicSemantics.PUB_SUB)
           .build());
   ```

2. **Subscribe existing consumer as a group**
   ```java
   subscriptionManager.subscribe("orders.events", "legacy-consumer",
       SubscriptionOptions.builder()
           .startPosition(StartPosition.FROM_NOW)
           .build());
   ```

3. **Add new consumer groups**
   ```java
   subscriptionManager.subscribe("orders.events", "new-consumer-1",
       SubscriptionOptions.builder()
           .startPosition(StartPosition.FROM_NOW)
           .build());

   subscriptionManager.subscribe("orders.events", "new-consumer-2",
       SubscriptionOptions.builder()
           .startPosition(StartPosition.FROM_NOW)
           .build());
   ```

4. **Verify all consumers receiving messages**
   ```sql
   SELECT group_name, COUNT(*) AS completed_count
   FROM outbox_consumer_groups
   WHERE message_id IN (
       SELECT id FROM outbox WHERE topic = 'orders.events' AND created_at > NOW() - INTERVAL '1 hour'
   )
   GROUP BY group_name;
   ```

---

## Advanced Topics

### Late-Joining Consumer Groups

When a new consumer group subscribes to a PUB_SUB topic, you can control where it starts:

**Option 1: FROM_NOW (Recommended for most cases)**
```java
SubscriptionOptions.builder()
    .startPosition(StartPosition.FROM_NOW)
    .build();
```
- Only receives new messages published after subscription
- No backfill required
- Fastest startup

**Option 2: FROM_BEGINNING (Catch-up mode)**
```java
SubscriptionOptions.builder()
    .startPosition(StartPosition.FROM_BEGINNING)
    .build();
```
- Receives all historical messages still in the outbox
- Requires backfill processing
- May take time to catch up

**Option 3: FROM_TIMESTAMP (Specific point-in-time)**
```java
SubscriptionOptions.builder()
    .startPosition(StartPosition.FROM_TIMESTAMP)
    .startFromTimestamp(Instant.parse("2025-01-01T00:00:00Z"))
    .build();
```
- Receives messages from specific timestamp forward
- Useful for disaster recovery or testing

**Option 4: FROM_MESSAGE_ID (Precise control)**
```java
SubscriptionOptions.builder()
    .startPosition(StartPosition.FROM_MESSAGE_ID)
    .startFromMessageId(12345L)
    .build();
```
- Receives messages from specific ID forward
- Most precise control

### Handling Consumer Group Failures

**Scenario: Consumer group crashes and restarts**

1. Consumer group crashes (no graceful shutdown)
2. Heartbeat stops updating
3. After 5 minutes, subscription marked as DEAD
4. Consumer group restarts
5. First heartbeat update resurrects subscription to ACTIVE
6. Consumer group resumes processing from last completed message

**No manual intervention required** - the system is self-healing.

### Idempotent Message Processing

Since PeeGeeQ guarantees **at-least-once delivery**, consumer groups must handle duplicate messages:

```java
consumerGroup.setMessageHandler(message -> {
    OrderEvent order = message.getPayload();

    // Check if already processed (application-level deduplication)
    if (orderRepository.isProcessed(order.getOrderId())) {
        logger.info("Order {} already processed, skipping", order.getOrderId());
        return CompletableFuture.completedFuture(null);
    }

    // Process message
    processOrder(order);

    // Mark as processed
    orderRepository.markProcessed(order.getOrderId());

    return CompletableFuture.completedFuture(null);
});
```

---

## Limitations & Future Work

### Current Limitations

1. **No partition-based cleanup** - Cleanup is based on reference counting only
2. **No automatic backfill rate limiting** - Late-joining consumers may overwhelm the system
3. **No built-in metrics** - Monitoring requires custom SQL queries
4. **No dead letter queue** - Failed messages remain in tracking table

### Future Enhancements

1. **Offset/Watermark Mode** - Alternative cleanup strategy using consumer group offsets
2. **Adaptive Backfill** - Rate-limited catch-up for late-joining consumers
3. **Metrics & Observability** - Built-in Prometheus/Micrometer metrics
4. **Dead Letter Queue** - Automatic routing of permanently failed messages
5. **Partition Management** - Automatic partition creation and cleanup

---

## FAQ

**Q: Can I mix QUEUE and PUB_SUB semantics on the same topic?**
A: No. Each topic must be configured as either QUEUE or PUB_SUB. Mixing semantics will cause unpredictable behavior.

**Q: What happens if I delete a consumer group subscription?**
A: The subscription is marked as CANCELLED (terminal state). Messages already in the tracking table remain, but no new messages will be delivered to that group.

**Q: Can a consumer group rejoin after being CANCELLED?**
A: Yes, but it will be treated as a new subscription. You must call `subscribe()` again with appropriate start position.

**Q: How do I handle schema evolution?**
A: Use versioned message formats (e.g., Avro, Protobuf) or include version fields in your payload. Consumer groups can handle different versions independently.

**Q: What's the performance impact of fan-out?**
A: Minimal. The trigger that sets `required_consumer_groups` is fast. Message fetching uses efficient indexes. Cleanup is slightly slower due to reference counting checks.

**Q: Can I have hundreds of consumer groups on one topic?**
A: Technically yes, but not recommended. Each consumer group adds overhead. Consider using fewer groups with internal routing if you need many consumers.

**Q: What happens if a message fails processing in one consumer group?**
A: Only that consumer group's tracking row is marked as FAILED. Other consumer groups continue processing independently. The message remains in the outbox until all groups complete or the retention period expires.

---

## Summary

PeeGeeQ's Consumer Group Fan-Out provides:

- ✅ **Flexible semantics** - QUEUE or PUB_SUB per topic
- ✅ **Independent processing** - Each consumer group maintains its own state
- ✅ **Automatic cleanup** - Messages deleted when all groups complete
- ✅ **Zero-subscription protection** - Configurable retention and write blocking
- ✅ **Self-healing** - DEAD subscriptions automatically resurrect
- ✅ **Concurrent-safe** - PostgreSQL row-level locking prevents conflicts

For detailed design information, see [PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md](../docs-design/design/PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md).

For alternative design options, see [CONSUMER_GROUP_FANOUT_DESIGN_OPTIONS.md](../docs-design/design/CONSUMER_GROUP_FANOUT_DESIGN_OPTIONS.md).

