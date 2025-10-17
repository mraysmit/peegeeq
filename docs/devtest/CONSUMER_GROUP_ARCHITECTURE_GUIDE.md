# PeeGeeQ Consumer Group Architecture Guide

## Section 1: Quick Reference

### Three Key Findings

#### 1️⃣ Message Distribution Model

**PeeGeeQ = Queue Semantics (NOT Pub/Sub)**

```
Multiple Consumer Groups on Same Topic
↓
Messages are DISTRIBUTED (not replicated)
↓
Each message processed by exactly ONE consumer
↓
Consumers COMPETE using FOR UPDATE SKIP LOCKED
```

**Comparison:**
- **Kafka**: Each group gets full copy of all messages
- **RabbitMQ**: One group per queue; messages distributed
- **PeeGeeQ**: Multiple groups compete for same messages

#### 2️⃣ Thread Configuration Limitation

**peegeeq.consumer.threads ONLY works without ConsumerGroup**

```java
// ✅ Works - Standalone consumer
MessageConsumer<Event> consumer = factory.createConsumer(topic, Event.class);
// Uses peegeeq.consumer.threads configuration

// ❌ Doesn't work - ConsumerGroup
ConsumerGroup<Event> group = factory.createConsumerGroup(name, topic, Event.class);
group.addConsumer("c1", handler);  // Ignores peegeeq.consumer.threads
```

#### 3️⃣ Manual Scaling Required

**No automatic instance discovery or scaling**

```java
// To scale from 1 to 4 consumers:
ConsumerGroup<Event> group = factory.createConsumerGroup(name, topic, Event.class);

group.addConsumer("consumer-1", handler);
group.addConsumer("consumer-2", handler);
group.addConsumer("consumer-3", handler);
group.addConsumer("consumer-4", handler);

group.start();
```

---

## Section 2: Detailed Analysis

### Finding 1: Consumer Group Message Distribution Model

#### The Actual Architecture

PeeGeeQ uses **QUEUE SEMANTICS** model, NOT a pub/sub model:

```
Topic: "orders"
├─ Message 1 → Processed by ONE consumer (from any group)
├─ Message 2 → Processed by ONE consumer (from any group)
└─ Message 3 → Processed by ONE consumer (from any group)
```

#### Multiple Groups Share the Same Queue

From `AdvancedProducerConsumerGroupTest.java` (lines 217-280):

```java
// Create consumer group for order processing
ConsumerGroup<OrderEvent> orderGroup = queueFactory.createConsumerGroup(
    orderGroupName, testQueueName, OrderEvent.class);

// Add region-specific consumers
orderGroup.addConsumer("US-Consumer", handler, MessageFilter.byRegion(Set.of("US")));
orderGroup.addConsumer("EU-Consumer", handler, MessageFilter.byRegion(Set.of("EU")));
orderGroup.addConsumer("ASIA-Consumer", handler, MessageFilter.byRegion(Set.of("ASIA")));
```

**Critical Comment in Test (lines 266-272):**
```
In queue semantics, consumers within the same group compete for messages
due to timing, load balancing, and processing speed differences
```

#### The Distribution Mechanism

From `OutboxConsumer.java` (lines 258-269):

```java
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

**How it works:**
1. `FOR UPDATE SKIP LOCKED` - Atomically locks available messages
2. Only one consumer can lock a message
3. Locked messages are marked `PROCESSING`
4. Other consumers skip locked messages and move to next available
5. Result: **Each message processed by exactly ONE consumer**

### Finding 2: peegeeq.consumer.threads Configuration

#### Only Works Without Consumer Groups

From `OutboxConsumer.java` (lines 105-111):

```java
int consumerThreads = configuration != null ?
    configuration.getQueueConfig().getConsumerThreads() : 1;
this.messageProcessingExecutor = Executors.newFixedThreadPool(consumerThreads, r -> {
    Thread t = new Thread(r, "outbox-processor-" + topic + "-" + System.currentTimeMillis());
    t.setDaemon(true);
    return t;
});
```

**The Issue:**
- `peegeeq.consumer.threads` creates a thread pool **per consumer instance**
- When using `ConsumerGroup`, you don't create individual consumers
- Instead, you call `consumerGroup.addConsumer()` which creates consumers internally
- Those internal consumers don't use the configuration

### Finding 3: Manual Consumer Registration for Scaling

#### No Automatic Scaling

From tests, there is **NO** automatic instance discovery or scaling:

```java
// You must manually register each consumer instance
for (int i = 0; i < numConsumers; i++) {
    roundRobinGroup.addConsumer(
        "consumer-" + i,
        handler
    );
}
```

#### Scaling Pattern

To scale from 1 to 4 consumers:

**Before (1 consumer):**
```java
ConsumerGroup<OrderEvent> group = factory.createConsumerGroup("orders", "order-topic", OrderEvent.class);
group.addConsumer("consumer-1", handler);
group.start();
```

**After (4 consumers):**
```java
ConsumerGroup<OrderEvent> group = factory.createConsumerGroup("orders", "order-topic", OrderEvent.class);
group.addConsumer("consumer-1", handler);
group.addConsumer("consumer-2", handler);
group.addConsumer("consumer-3", handler);
group.addConsumer("consumer-4", handler);
group.start();
```

**In Distributed Systems:**
- Each instance must register its own consumers
- No automatic coordination
- Requires manual deployment/configuration changes

---

## Summary Table

| Aspect | Finding | Evidence |
|--------|---------|----------|
| **Message Distribution** | Queue semantics - messages distributed, not replicated | `FOR UPDATE SKIP LOCKED` in OutboxConsumer.java |
| **Multiple Groups** | Can subscribe to same topic; compete for messages | AdvancedProducerConsumerGroupTest comments |
| **consumer.threads** | Only works with standalone consumers, not ConsumerGroup | OutboxConsumer constructor logic |
| **Scaling** | Manual registration required per instance | ConsumerGroupLoadBalancingDemoTest pattern |
| **Filters** | Determine which consumers can process which messages | MessageFilter.byRegion(), byPriority() usage |

---

## Recommendations

1. **For Pub/Sub Semantics**: Use separate topics per consumer group if you need each group to get all messages

2. **For Scaling**: Implement a configuration service that registers consumers based on deployment count

3. **For Thread Tuning**: Create consumers manually and configure thread pools per consumer instance

4. **For Production**: Consider implementing consumer group coordination/discovery mechanism

---

## Section 2: Architecture Verification Analysis

### Analysis Complete: Consumer Group Architecture Verification

I've thoroughly examined the code, tests, and examples. Here are my findings:

### **Statement 1: "Consumer groups distribute messages among groups rather than each group having its own copy"**

✅ **TRUE - But More Nuanced**

PeeGeeQ uses **queue semantics**, NOT pub/sub:
- Multiple consumer groups can subscribe to the same topic
- Messages are **distributed** (not replicated) across all consumers
- Consumers **compete** for messages using PostgreSQL's `FOR UPDATE SKIP LOCKED`
- Each message is processed by exactly ONE consumer

**Key Code** (`OutboxConsumer.java` lines 258-269):
```java
UPDATE outbox
SET status = 'PROCESSING', processed_at = $1
WHERE id IN (
    SELECT id FROM outbox
    WHERE topic = $2 AND status = 'PENDING'
    ORDER BY created_at ASC
    LIMIT $3
    FOR UPDATE SKIP LOCKED  // ← Atomic lock - only one consumer gets each message
)
```

**Test Evidence** (`AdvancedProducerConsumerGroupTest.java` lines 266-272):
```java
// In queue semantics, consumers within the same group compete for messages
// due to timing, load balancing, and processing speed differences
```

---

### **Statement 2: "peegeeq.consumer.threads only works when no group is used"**

✅ **TRUE - Confirmed**

The `peegeeq.consumer.threads` configuration only applies to standalone consumers:

**Code** (`OutboxConsumer.java` lines 105-111):
```java
int consumerThreads = configuration != null ?
    configuration.getQueueConfig().getConsumerThreads() : 1;
this.messageProcessingExecutor = Executors.newFixedThreadPool(consumerThreads, ...);
```

**The Problem:**
- When using `ConsumerGroup`, you don't create individual consumers
- You call `consumerGroup.addConsumer()` which creates consumers internally
- Those internal consumers don't use the thread pool configuration

---

### **Statement 3: "Group consumers need manual registration for each instance"**

✅ **TRUE - Confirmed**

There is **NO automatic scaling or instance discovery**:

**Pattern from Tests** (`ConsumerGroupLoadBalancingDemoTest.java` lines 229-270):
```java
ConsumerGroup<WorkItem> group = factory.createConsumerGroup("group", "topic", WorkItem.class);

// Must manually register each consumer
for (int i = 0; i < numConsumers; i++) {
    group.addConsumer("consumer-" + i, handler);
}
```

**To scale from 1 to 4 consumers:**
- You must manually call `addConsumer()` 4 times
- In distributed systems, each instance must register its own consumers
- No automatic coordination or discovery

---

### Key Architectural Differences from Kafka/RabbitMQ

| Feature | Kafka | RabbitMQ | PeeGeeQ |
|---------|-------|----------|---------|
| **Multiple Groups** | Each group gets full copy | One group per queue | Groups compete for messages |
| **Message Replication** | Yes (per group) | No | No |
| **Distribution Model** | Pub/Sub | Queue | Queue |
| **Scaling** | Automatic partition assignment | Manual queue binding | Manual consumer registration |
| **Thread Config** | Per consumer group | Per queue | Per consumer instance |

