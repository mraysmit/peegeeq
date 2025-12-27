# PeeGeeQ Consumer Groups - Getting Started Guide
#### © Mark Andrew Ray-Smith Cityline Ltd 2025
#### Version 1.1.0

**A gentle introduction to Consumer Groups in PeeGeeQ, progressing from basic concepts to advanced features.**

This guide takes you from zero to production-ready consumer group implementations with progressive examples and clear explanations.


---

## Table of Contents

1. [What Are Consumer Groups?](#what-are-consumer-groups)
2. [Quick Start (5 Minutes)](#quick-start-5-minutes)
3. [Core Concepts](#core-concepts)
4. [Basic Examples](#basic-examples)
5. [Intermediate Features](#intermediate-features)
   - [Message Filtering](#feature-1-message-filtering) (Client-Side and Server-Side)
6. [Advanced Features](#advanced-features)
7. [Production Patterns](#production-patterns)
8. [Troubleshooting](#troubleshooting)
9. [Next Steps](#next-steps)

---

## What Are Consumer Groups?

Consumer Groups enable **multiple independent consumers** to process messages from the same topic with different delivery semantics:

### Two Delivery Modes

| Mode | Behaviour | Pattern | Use Case |
|------|----------|---------|----------|
| **QUEUE** | Each message delivered to **one** consumer in the group | Competing Consumers | Load balancing, work distribution |
| **PUB_SUB** | Each message delivered to **all** consumer groups | Publish-Subscribe | Event broadcasting, multiple services |

**QUEUE semantics** implements the **Competing Consumers** pattern: multiple consumers compete for messages from the same queue, with each message processed by exactly one consumer. This enables horizontal scaling and load distribution.

**PUB_SUB semantics** implements the **Publish-Subscribe** pattern: each consumer group receives its own copy of every message, enabling independent processing by multiple services.

### Real-World Example

Imagine a **custody backoffice trade processing** system:

```
Trade Executed Event
├─ QUEUE: settlement-processing (3 workers) → One worker processes settlement (T+2)
├─ PUB_SUB: position-service → Updates fund positions
├─ PUB_SUB: cash-service → Updates cash balances
├─ PUB_SUB: regulatory-reporting → Reports to regulator (AIFMD, MiFID II)
└─ PUB_SUB: risk-service → Recalculates risk metrics
```

**QUEUE semantics** (Competing Consumers): The 3 settlement workers share the load - each trade settled by exactly one worker
**PUB_SUB semantics** (Publish-Subscribe): Every service receives every trade event - positions, cash, reporting, and risk all updated independently

---

## Quick Start (5 Minutes)

### Prerequisites

- **Java 21+**
- **Maven 3.8+**
- **Docker** (for PostgreSQL via TestContainers)

### Run Your First Consumer Group Example

```bash
# Clone the repository
git clone <repository-url>
cd peegeeq

# Run the consumer group load balancing demo
mvn test -Dtest="ConsumerGroupLoadBalancingDemoTest" -pl peegeeq-examples
```

**What you'll see**:
- ✅ 3 consumer workers sharing message load (QUEUE semantics)
- ✅ Round-robin distribution across workers
- ✅ Each message processed exactly once

---

## Core Concepts

### 1. Topics and Semantics

Every topic has a **semantic mode** that determines how messages are delivered:

```java
// QUEUE: Messages distributed across consumer groups (load balancing)
TopicConfig queueTopic = TopicConfig.builder()
    .topic("orders.processing")
    .semantics(TopicSemantics.QUEUE)
    .build();

// PUB_SUB: Messages replicated to all consumer groups (broadcast)
TopicConfig pubSubTopic = TopicConfig.builder()
    .topic("orders.events")
    .semantics(TopicSemantics.PUB_SUB)
    .build();
```

### 2. Consumer Groups

A **consumer group** is a named set of consumers that work together:

```java
// Create a consumer group for the "position-service"
ConsumerGroup<TradeEvent> positionGroup = queueFactory.createConsumerGroup(
    "position-service",        // Group name (unique per topic)
    "trades.executed",         // Topic name
    TradeEvent.class           // Message type
);
```

### 3. Starting Consumer Groups

There are **three patterns** for starting consumer groups:

#### Pattern 1: Simple Start (Most Common)
```java
// Add consumers and start immediately
positionGroup.addConsumer("consumer-1", message -> {
    // Process message
    return CompletableFuture.completedFuture(null);
});

positionGroup.start();  // Start consuming new messages
```

#### Pattern 2: Two-Step with Subscription Options (Advanced)
```java
// Step 1: Create subscription at database layer (for late-joining consumers)
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.messaging.StartPosition;

SubscriptionOptions options = SubscriptionOptions.builder()
    .startPosition(StartPosition.FROM_BEGINNING)  // Backfill historical messages
    .build();

subscriptionManager.subscribe("trades.executed", "position-service", options)
    .toCompletionStage().toCompletableFuture().get();

// Step 2: Start the consumer group
positionGroup.addConsumer("consumer-1", messageHandler);
positionGroup.start();
```

#### Pattern 3: Convenience Method (New in v1.1.0)
```java
// Combines subscription + start in one call
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.messaging.StartPosition;

SubscriptionOptions options = SubscriptionOptions.builder()
    .startPosition(StartPosition.FROM_BEGINNING)
    .build();

positionGroup.addConsumer("consumer-1", messageHandler);
positionGroup.start(options);  // Pass options directly
```

**Note:** Pattern 2 requires access to `SubscriptionManager` from the database layer. Pattern 3 is a convenience wrapper that validates the options but delegates to the database layer internally.

---

## Basic Examples

### Example 1: Simple QUEUE Consumer (Load Balancing)

**Use Case**: Distribute trade settlement processing across 3 workers

```java
// 1. Create topic with QUEUE semantics
TopicConfig config = TopicConfig.builder()
    .topic("trades.settlement")
    .semantics(TopicSemantics.QUEUE)
    .messageRetentionHours(24)
    .build();

// Create the topic (Vert.x Future → CompletionStage → CompletableFuture → blocking get)
topicConfigService.createTopic(config)
    .toCompletionStage()      // Convert Vert.x Future to Java CompletionStage
    .toCompletableFuture()    // Convert to CompletableFuture
    .get();                   // Block until topic creation completes

// 2. Create producer for sending trade events
MessageProducer<TradeEvent> producer = queueFactory.createProducer(
    "trades.settlement",      // Topic name
    TradeEvent.class          // Message type
);

// 3. Create consumer group with 3 workers
ConsumerGroup<TradeEvent> settlementWorkers = queueFactory.createConsumerGroup(
    "settlement-workers",
    "trades.settlement",
    TradeEvent.class
);

// 4. Track which worker processes which trade (for demonstration)
AtomicInteger processedCount = new AtomicInteger(0);
Map<String, Integer> workerStats = new ConcurrentHashMap<>();

// 5. Add multiple consumers to the group (simulating 3 workers)
for (int i = 1; i <= 3; i++) {
    String workerId = "settlement-worker-" + i;

    // Each worker has its own message handler
    MessageHandler<TradeEvent> handler = message -> {
        TradeEvent trade = message.getPayload();
        long startTime = System.currentTimeMillis();

        try {
            // Process settlement instruction to custodian (T+2)
            logger.info("🏦 {} processing settlement for trade: {} (Fund: {}, Security: {}, Qty: {})",
                workerId, trade.tradeId(), trade.fundId(), trade.securityId(), trade.quantity());

            // Simulate custodian API call
            sendSettlementToCustodian(trade);

            // Update statistics
            workerStats.merge(workerId, 1, Integer::sum);
            processedCount.incrementAndGet();

            long processingTime = System.currentTimeMillis() - startTime;
            logger.info("✅ {} completed trade {} in {}ms",
                workerId, trade.tradeId(), processingTime);

            return CompletableFuture.completedFuture(null);

        } catch (CustodianTimeoutException e) {
            // Transient error - will be retried
            logger.warn("⚠️ {} custodian timeout for trade {}: {}",
                workerId, trade.tradeId(), e.getMessage());
            return CompletableFuture.failedFuture(e);

        } catch (Exception e) {
            // Permanent error - log and acknowledge
            logger.error("❌ {} failed to process trade {}: {}",
                workerId, trade.tradeId(), e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    };

    // Add this worker to the consumer group
    settlementWorkers.addConsumer(workerId, handler);
}

// 6. Start the consumer group (all workers begin consuming)
settlementWorkers.start();  // Simple start - processes new messages

// 7. Send messages
logger.info("📤 Sending 10 trades for settlement processing...");
for (int i = 1; i <= 10; i++) {
    TradeEvent trade = new TradeEvent(
        "TRADE-" + i,
        "FUND-001",
        "AAPL",
        TradeType.BUY,
        100.0,  // quantity
        150.0   // price
    );

    // Send trade event (Vert.x Future → CompletionStage → CompletableFuture → blocking get)
    producer.send(trade)
        .toCompletionStage()      // Convert Vert.x Future to Java CompletionStage
        .toCompletableFuture()    // Convert to CompletableFuture
        .get();                   // Block until message is sent
}

// 8. Wait for processing to complete
Thread.sleep(5000);

// 9. Display results
logger.info("📊 Settlement Processing Results:");
logger.info("   Total trades processed: {}", processedCount.get());
workerStats.forEach((worker, count) ->
    logger.info("   {} processed {} trades", worker, count));

// 10. Cleanup
settlementWorkers.stop();
settlementWorkers.close();
```

**Result**: 10 trades distributed across 3 settlement workers (round-robin)

**Output Example**:
```
📤 Sending 10 trades for settlement processing...
🏦 settlement-worker-1 processing settlement for trade: TRADE-1 (Fund: FUND-001, Security: AAPL, Qty: 100.0)
🏦 settlement-worker-2 processing settlement for trade: TRADE-2 (Fund: FUND-001, Security: AAPL, Qty: 100.0)
🏦 settlement-worker-3 processing settlement for trade: TRADE-3 (Fund: FUND-001, Security: AAPL, Qty: 100.0)
✅ settlement-worker-1 completed trade TRADE-1 in 45ms
✅ settlement-worker-2 completed trade TRADE-2 in 48ms
🏦 settlement-worker-1 processing settlement for trade: TRADE-4 (Fund: FUND-001, Security: AAPL, Qty: 100.0)
...
📊 Settlement Processing Results:
   Total trades processed: 10
   settlement-worker-1 processed 4 trades
   settlement-worker-2 processed 3 trades
   settlement-worker-3 processed 3 trades
```

**📝 See Full Example**: [`ConsumerGroupLoadBalancingDemoTest.java`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/nativequeue/ConsumerGroupLoadBalancingDemoTest.java)

---

### Example 2: Simple PUB_SUB Consumer (Broadcasting)

**Use Case**: Broadcast trade events to multiple independent backoffice services

```java
// 1. Create topic with PUB_SUB semantics
TopicConfig config = TopicConfig.builder()
    .topic("trades.executed")
    .semantics(TopicSemantics.PUB_SUB)
    .messageRetentionHours(24)
    .build();

topicConfigService.createTopic(config).toCompletionStage().toCompletableFuture().get();

// 2. Create producer
MessageProducer<TradeEvent> producer = queueFactory.createProducer(
    "trades.executed",
    TradeEvent.class
);

// 3. Create multiple consumer groups (each receives ALL messages)
ConsumerGroup<TradeEvent> positionService = queueFactory.createConsumerGroup(
    "position-service",
    "trades.executed",
    TradeEvent.class
);

ConsumerGroup<TradeEvent> cashService = queueFactory.createConsumerGroup(
    "cash-service",
    "trades.executed",
    TradeEvent.class
);

ConsumerGroup<TradeEvent> regulatoryService = queueFactory.createConsumerGroup(
    "regulatory-reporting",
    "trades.executed",
    TradeEvent.class
);

// 4. Set up handlers for each service (v1.1.0 convenience method)
positionService.setMessageHandler(message -> {
    logger.info("📊 Position service: Updating positions for trade {}", message.getPayload().tradeId());
    return CompletableFuture.completedFuture(null);
});

cashService.setMessageHandler(message -> {
    logger.info("💰 Cash service: Updating cash balances for trade {}", message.getPayload().tradeId());
    return CompletableFuture.completedFuture(null);
});

regulatoryService.setMessageHandler(message -> {
    logger.info("📋 Regulatory service: Reporting trade {} to regulator", message.getPayload().tradeId());
    return CompletableFuture.completedFuture(null);
});

// 5. Start all consumer groups
positionService.start();
cashService.start();
regulatoryService.start();

// 6. Send one message
TradeEvent trade = new TradeEvent("TRADE-123", "FUND-001", "AAPL", TradeType.BUY, 100.0, 150.0);
producer.send(trade).toCompletionStage().toCompletableFuture().get();
```

**Result**: One message delivered to **all 3 services** (position, cash, regulatory)

**📝 See Full Example**: [`AdvancedProducerConsumerGroupTest.java`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/AdvancedProducerConsumerGroupTest.java)

---

## Intermediate Features

### Feature 1: Message Filtering

PeeGeeQ provides two complementary filtering approaches:

| Approach | Where Filtering Happens | Best For |
|----------|------------------------|----------|
| **Client-Side** (`MessageFilter`) | Java client after fetch | Consumer groups, complex logic |
| **Server-Side** (`ServerSideFilter`) | PostgreSQL before fetch | High-volume, header-based filters |

#### Client-Side Filtering (MessageFilter)

**Use Case**: Different consumer groups process different subsets of messages using Java predicates

```java
import dev.mars.peegeeq.api.messaging.MessageFilter;

// Create consumer group with message filter
ConsumerGroup<TradeEvent> largeTradeProcessor = queueFactory.createConsumerGroup(
    "large-trade-processor",
    "trades.executed",
    TradeEvent.class
);

// Add consumer with header-based filter
largeTradeProcessor.addConsumer("equity-processor", handler,
    MessageFilter.byHeader("assetClass", "EQUITY"));

// Add consumer with multiple allowed values
largeTradeProcessor.addConsumer("priority-processor", handler,
    MessageFilter.byHeaderIn("priority", Set.of("HIGH", "URGENT")));

// Combine filters with AND logic
largeTradeProcessor.addConsumer("vip-equity-processor", handler,
    MessageFilter.and(
        MessageFilter.byHeader("customerTier", "VIP"),
        MessageFilter.byHeader("assetClass", "EQUITY")
    ));

// Custom predicate for complex logic (e.g., payload-based filtering)
largeTradeProcessor.setMessageHandler(message -> {
    TradeEvent trade = message.getPayload();

    // Only process large trades (> $1M notional)
    double notional = trade.quantity() * trade.price();
    if (notional > 1_000_000.0) {
        logger.info("Processing large trade: {} (${})", trade.tradeId(), notional);
        // Special processing for large trades (e.g., additional compliance checks)...
    } else {
        logger.debug("Skipping small trade: {}", trade.tradeId());
    }

    return CompletableFuture.completedFuture(null);
});

// Start the consumer group
largeTradeProcessor.start();
```

#### Server-Side Filtering (ServerSideFilter)

**Use Case**: High-volume scenarios where filtering at the database level reduces network traffic and CPU usage

```java
import dev.mars.peegeeq.api.messaging.ServerSideFilter;
import dev.mars.peegeeq.pgqueue.ConsumerConfig;        // For native queue
import dev.mars.peegeeq.outbox.OutboxConsumerConfig;   // For outbox

// Simple header equality - only fetch EQUITY trades
ServerSideFilter filter = ServerSideFilter.headerEquals("assetClass", "EQUITY");

// Multiple values - fetch trades from specific regions
ServerSideFilter filter = ServerSideFilter.headerIn("region", Set.of("US", "EU", "ASIA"));

// Exclude specific values
ServerSideFilter filter = ServerSideFilter.headerNotEquals("status", "CANCELLED");

// Pattern matching
ServerSideFilter filter = ServerSideFilter.headerLike("eventType", "trade-%");

// Combine with AND
ServerSideFilter filter = ServerSideFilter.and(
    ServerSideFilter.headerEquals("assetClass", "EQUITY"),
    ServerSideFilter.headerEquals("priority", "HIGH")
);

// Combine with OR
ServerSideFilter filter = ServerSideFilter.or(
    ServerSideFilter.headerEquals("assetClass", "EQUITY"),
    ServerSideFilter.headerEquals("priority", "URGENT")
);

// Apply to consumer configuration (Native Queue)
ConsumerConfig config = ConsumerConfig.builder()
    .serverSideFilter(filter)
    .build();

MessageConsumer<TradeEvent> consumer = nativeFactory.createConsumer(
    "trades.executed", TradeEvent.class, config);

// Apply to consumer configuration (Outbox)
OutboxConsumerConfig outboxConfig = OutboxConsumerConfig.builder()
    .serverSideFilter(filter)
    .build();

MessageConsumer<TradeEvent> outboxConsumer = outboxFactory.createConsumer(
    "trades.executed", TradeEvent.class, outboxConfig);
```

**When to use each approach:**
- **Client-Side**: Consumer groups, complex filtering logic, payload-based filtering
- **Server-Side**: High message volumes, simple header filters, performance-critical applications

**See Full Example**: [`AdvancedProducerConsumerGroupTest.java`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/AdvancedProducerConsumerGroupTest.java) - `testMultipleConsumerGroupsWithFiltering()`

---

### Feature 2: Error Handling and Retries

**Use Case**: Handle transient failures with automatic retries

```java
ConsumerGroup<TradeEvent> resilientProcessor = queueFactory.createConsumerGroup(
    "settlement-processor",
    "trades.settlement",
    TradeEvent.class
);

resilientProcessor.start(SubscriptionOptions.defaults());

// Set handler with error handling
resilientProcessor.setMessageHandler(message -> {
    try {
        // Process settlement
        processSettlement(message.getPayload());
        return CompletableFuture.completedFuture(null);

    } catch (CustodianTimeoutException e) {
        // Transient error - custodian system temporarily unavailable, retry
        logger.warn("Custodian timeout for trade {}: {}", message.getPayload().tradeId(), e.getMessage());
        return CompletableFuture.failedFuture(e);

    } catch (InvalidAccountException e) {
        // Permanent error - invalid account number, send to DLQ
        logger.error("Invalid account for trade {}: {}", message.getPayload().tradeId(), e.getMessage());
        return CompletableFuture.completedFuture(null);
    }
});
```

**📝 See Full Example**: [`ConsumerGroupResilienceTest.java`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/ConsumerGroupResilienceTest.java)

---

### Feature 3: Heartbeat Monitoring

**Use Case**: Detect and recover from dead consumers

```java
// Subscribe with custom heartbeat configuration
SubscriptionOptions options = SubscriptionOptions.builder()
    .heartbeatIntervalSeconds(30)      // Send heartbeat every 30 seconds
    .heartbeatTimeoutSeconds(120)      // Mark dead after 120 seconds of no heartbeat
    .build();

ConsumerGroup<TradeEvent> monitoredGroup = queueFactory.createConsumerGroup(
    "settlement-processor",
    "trades.settlement",
    TradeEvent.class
);

monitoredGroup.start(options);

// Set up message handler
monitoredGroup.setMessageHandler(message -> {
    // Process settlement...
    // Heartbeat is sent automatically by the consumer group
    return CompletableFuture.completedFuture(null);
});

// Separately, run dead consumer detection (typically in a background job)
DeadConsumerDetector detector = new DeadConsumerDetector(connectionManager, "detector-1");
detector.detectDeadSubscriptions("trades.settlement")
    .toCompletionStage()
    .toCompletableFuture()
    .get();
```

**📝 See Full Example**: [`DeadConsumerDetectionDemoTest.java`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/DeadConsumerDetectionDemoTest.java)

---

## Advanced Features

### Feature 1: Late-Joining Consumers (Backfill)

**Use Case**: New analytics service needs to process all historical orders

#### Pattern 1: FROM_NOW (Standard - New Messages Only)

```java
// Standard consumer - only receives new messages
ConsumerGroup<TradeEvent> realtimeRisk = queueFactory.createConsumerGroup(
    "realtime-risk",
    "trades.executed",
    TradeEvent.class
);

realtimeRisk.setMessageHandler(message -> {
    // Process real-time risk calculations
    return CompletableFuture.completedFuture(null);
});

realtimeRisk.start();  // Default behavior: FROM_NOW
```

**Behavior**: Ignores all historical messages, only processes messages sent **after** subscription

---

#### Pattern 2: FROM_BEGINNING (Backfill All Historical Data)

**Approach A: Two-Step Process (Explicit Database Layer)**

```java
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.messaging.StartPosition;

// Step 1: Create subscription at database layer
SubscriptionOptions options = SubscriptionOptions.builder()
    .startPosition(StartPosition.FROM_BEGINNING)
    .build();

subscriptionManager.subscribe("trades.executed", "new-analytics-service", options)
    .toCompletionStage().toCompletableFuture().get();

// Step 2: Create and start consumer group
ConsumerGroup<TradeEvent> newAnalyticsService = queueFactory.createConsumerGroup(
    "new-analytics-service",
    "trades.executed",
    TradeEvent.class
);

newAnalyticsService.setMessageHandler(message -> {
    // Process historical + new messages for analytics
    return CompletableFuture.completedFuture(null);
});

newAnalyticsService.start();
```

**Approach B: Convenience Method (Single Call)**

```java
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.messaging.StartPosition;

// Single call - combines subscription + start
SubscriptionOptions options = SubscriptionOptions.builder()
    .startPosition(StartPosition.FROM_BEGINNING)
    .build();

ConsumerGroup<TradeEvent> newAnalyticsService = queueFactory.createConsumerGroup(
    "new-analytics-service",
    "trades.executed",
    TradeEvent.class
);

newAnalyticsService.setMessageHandler(message -> {
    // Process historical + new messages for analytics
    return CompletableFuture.completedFuture(null);
});

newAnalyticsService.start(options);  // Pass options directly
```

**Behavior**: Processes **all** historical messages from the beginning, then continues with new messages

**Use Case**: New analytics service deployed that needs to process all historical trades to build complete metrics

**Note:** Both approaches achieve the same result. Approach A gives you more control over the subscription lifecycle, while Approach B is more convenient for simple scenarios.

---

#### Pattern 3: FROM_TIMESTAMP (Time-Based Replay)

```java
import java.time.LocalDate;
import java.time.ZoneOffset;

// Replay from specific timestamp (e.g., start of trading day)
LocalDate tradingDay = LocalDate.of(2024, 11, 15);
Instant startOfDay = tradingDay.atStartOfDay(ZoneOffset.UTC).toInstant();

SubscriptionOptions options = SubscriptionOptions.builder()
    .startPosition(StartPosition.FROM_TIMESTAMP)
    .startFromTimestamp(startOfDay)
    .build();

ConsumerGroup<TradeEvent> dailyReconciliation = queueFactory.createConsumerGroup(
    "daily-reconciliation",
    "trades.executed",
    TradeEvent.class
);

dailyReconciliation.setMessageHandler(message -> {
    // Process messages from start of trading day
    return CompletableFuture.completedFuture(null);
});

dailyReconciliation.start(options);
```

**Behavior**: Processes messages from the specified timestamp onwards

**Use Case**: Daily reconciliation job that processes all trades from start of trading day

**📝 See Full Example**: [`LateJoiningConsumerDemoTest.java`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/LateJoiningConsumerDemoTest.java)

---

### Feature 2: Zero-Subscription Protection

**Use Case**: Prevent accidental data loss when no consumers are subscribed

#### QUEUE Topics (Always Allow Writes)

```java
// QUEUE topics always allow writes (backward compatible)
TopicConfig config = TopicConfig.builder()
    .topic("trades.settlement")
    .semantics(TopicSemantics.QUEUE)
    .messageRetentionHours(24)
    .build();

topicConfigService.createTopic(config).toCompletionStage().toCompletableFuture().get();

// Writes succeed even with zero subscriptions
producer.send(trade).toCompletionStage().toCompletableFuture().get(); // ✅ Success
```

---

#### PUB_SUB Topics (Configurable Protection)

**Option 1: Allow Writes with Retention (Default)**

```java
// PUB_SUB with zero-subscription retention (24 hours default)
TopicConfig config = TopicConfig.builder()
    .topic("trades.executed")
    .semantics(TopicSemantics.PUB_SUB)
    .blockWritesOnZeroSubscriptions(false)  // Allow writes
    .zeroSubscriptionRetentionHours(24)     // Keep messages for 24 hours
    .build();

topicConfigService.createTopic(config).toCompletionStage().toCompletableFuture().get();

// Writes succeed, messages retained for 24 hours
producer.send(trade).toCompletionStage().toCompletableFuture().get(); // ✅ Success
```

**Option 2: Block Writes for Protection**

```java
// PUB_SUB with write blocking (prevent data loss)
TopicConfig config = TopicConfig.builder()
    .topic("trades.executed")
    .semantics(TopicSemantics.PUB_SUB)
    .blockWritesOnZeroSubscriptions(true)   // Block writes
    .build();

topicConfigService.createTopic(config).toCompletionStage().toCompletableFuture().get();

// Check if writes are allowed before sending
ZeroSubscriptionValidator validator = new ZeroSubscriptionValidator(connectionManager, "validator-1");
boolean allowed = validator.isWriteAllowed("trades.executed")
    .toCompletionStage()
    .toCompletableFuture()
    .get();

if (allowed) {
    producer.send(trade).toCompletionStage().toCompletableFuture().get(); // ✅ Success
} else {
    logger.warn("Cannot send trade - no active subscriptions");
}
```

**📝 See Full Example**: [`ZeroSubscriptionProtectionDemoTest.java`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/ZeroSubscriptionProtectionDemoTest.java)

---

## Production Patterns

### Pattern 1: Backoffice Event Broadcasting

**Scenario**: Trade capture system broadcasts trade events to multiple downstream backoffice services

```java
// 1. Create PUB_SUB topic for trade events
TopicConfig config = TopicConfig.builder()
    .topic("trades.executed")
    .semantics(TopicSemantics.PUB_SUB)
    .messageRetentionHours(72)              // 3 days retention
    .zeroSubscriptionRetentionHours(24)     // 24 hours if no subscribers
    .blockWritesOnZeroSubscriptions(false)  // Allow writes (with retention)
    .build();

topicConfigService.createTopic(config).toCompletionStage().toCompletableFuture().get();

// 2. Trade Capture System - Publishes trade events
MessageProducer<TradeEvent> tradeProducer = queueFactory.createProducer(
    "trades.executed",
    TradeEvent.class
);

// 3. Position Service - Updates fund positions
ConsumerGroup<TradeEvent> positionService = queueFactory.createConsumerGroup(
    "position-service",
    "trades.executed",
    TradeEvent.class
);
positionService.start(SubscriptionOptions.defaults());
positionService.setMessageHandler(message -> updatePositions(message.getPayload()));

// 4. Cash Service - Updates cash balances
ConsumerGroup<TradeEvent> cashService = queueFactory.createConsumerGroup(
    "cash-service",
    "trades.executed",
    TradeEvent.class
);
cashService.start(SubscriptionOptions.defaults());
cashService.setMessageHandler(message -> updateCashBalances(message.getPayload()));

// 5. Regulatory Reporting - Reports to regulator (late-joining, backfills historical data)
ConsumerGroup<TradeEvent> regulatoryService = queueFactory.createConsumerGroup(
    "regulatory-reporting",
    "trades.executed",
    TradeEvent.class
);
SubscriptionOptions regulatoryOptions = SubscriptionOptions.builder()
    .startPosition(StartPosition.FROM_BEGINNING)  // Backfill all historical trades
    .build();
regulatoryService.start(regulatoryOptions);
regulatoryService.setMessageHandler(message -> reportToRegulator(message.getPayload()));

// 6. Publish trade event
TradeEvent trade = new TradeEvent("TRADE-123", "FUND-001", "AAPL", TradeType.BUY, 100.0, 150.0);
tradeProducer.send(trade).toCompletionStage().toCompletableFuture().get();
```

**Result**:
- ✅ Position service updates fund positions
- ✅ Cash service updates cash balances
- ✅ Regulatory service processes this trade + all historical trades for compliance reporting

---

### Pattern 2: Load-Balanced Settlement Processing

**Scenario**: Distribute settlement processing across multiple workers (T+2 settlement)

```java
// 1. Create QUEUE topic for settlement processing
TopicConfig config = TopicConfig.builder()
    .topic("trades.settlement")
    .semantics(TopicSemantics.QUEUE)
    .messageRetentionHours(24)
    .build();

topicConfigService.createTopic(config).toCompletionStage().toCompletableFuture().get();

// 2. Create producer
MessageProducer<TradeEvent> producer = queueFactory.createProducer(
    "trades.settlement",
    TradeEvent.class
);

// 3. Create consumer group with multiple workers (simulated with multiple handlers)
ConsumerGroup<TradeEvent> workers = queueFactory.createConsumerGroup(
    "settlement-workers",
    "trades.settlement",
    TradeEvent.class
);

// 4. Subscribe with heartbeat monitoring
SubscriptionOptions options = SubscriptionOptions.builder()
    .heartbeatIntervalSeconds(30)
    .heartbeatTimeoutSeconds(120)
    .build();

workers.start(options);

// 5. Set up message handler with error handling
workers.setMessageHandler(message -> {
    try {
        // Settlement processing (T+2)
        TradeEvent trade = message.getPayload();
        logger.info("Worker processing settlement for trade: {}", trade.tradeId());

        // Send settlement instruction to custodian
        processSettlement(trade);

        return CompletableFuture.completedFuture(null);

    } catch (CustodianTimeoutException e) {
        logger.error("Custodian timeout for trade {}: {}", trade.tradeId(), e.getMessage());
        return CompletableFuture.failedFuture(e); // Will be retried
    }
});

// 6. Send batch of trades for settlement
for (int i = 1; i <= 100; i++) {
    TradeEvent trade = new TradeEvent("TRADE-" + i, "FUND-001", "AAPL", TradeType.BUY, 100.0, 150.0);
    producer.send(trade).toCompletionStage().toCompletableFuture().get();
}
```

**Result**: 100 trades distributed across settlement workers with automatic load balancing

---

### Pattern 3: Dead Consumer Recovery

**Scenario**: Automatically detect and recover from dead settlement processors

```java
// 1. Subscribe with heartbeat monitoring
SubscriptionOptions options = SubscriptionOptions.builder()
    .heartbeatIntervalSeconds(30)      // Send heartbeat every 30 seconds
    .heartbeatTimeoutSeconds(120)      // Mark dead after 2 minutes
    .build();

ConsumerGroup<TradeEvent> processor = queueFactory.createConsumerGroup(
    "settlement-processor",
    "trades.settlement",
    TradeEvent.class
);

processor.start(options);

// 2. Set up message handler (heartbeats sent automatically)
processor.setMessageHandler(message -> {
    // Process settlement...
    return CompletableFuture.completedFuture(null);
});

// 3. Run dead consumer detection (in a background job)
DeadConsumerDetector detector = new DeadConsumerDetector(connectionManager, "detector-1");

// Detect dead subscriptions for specific topic
int deadCount = detector.detectDeadSubscriptions("trades.settlement")
    .toCompletionStage()
    .toCompletableFuture()
    .get();

logger.info("Detected {} dead settlement processors", deadCount);

// 4. Recovery: Restart the consumer group
if (deadCount > 0) {
    // Consumer group can be restarted by re-subscribing
    processor.start(options);
    logger.info("Settlement processor restarted");
}
```

**📝 See Full Example**: [`DeadConsumerDetectionDemoTest.java`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/DeadConsumerDetectionDemoTest.java)

---

## Troubleshooting

### Issue 1: Messages Not Being Delivered

**Symptoms**: Consumer group not receiving messages

**Checklist**:
1. ✅ **Verify subscription**: Check that `start()` was called
   ```java
   consumerGroup.start(SubscriptionOptions.defaults());
   ```

2. ✅ **Check topic semantics**: Ensure topic exists and has correct semantics
   ```java
   TopicConfig config = topicConfigService.getTopic("orders.events")
       .toCompletionStage()
       .toCompletableFuture()
       .get();
   logger.info("Topic semantics: {}", config.getSemantics());
   ```

3. ✅ **Verify message handler**: Ensure handler is set
   ```java
   consumerGroup.setMessageHandler(message -> {
       logger.info("Received: {}", message.getPayload());
       return CompletableFuture.completedFuture(null);
   });
   ```

4. ✅ **Check start position**: For FROM_NOW, messages sent before subscription are ignored
   ```java
   // Use FROM_BEGINNING to receive historical messages
   SubscriptionOptions options = SubscriptionOptions.builder()
       .startPosition(StartPosition.FROM_BEGINNING)
       .build();
   ```

---

### Issue 2: Consumer Marked as Dead

**Symptoms**: Consumer group stops receiving messages, marked as DEAD

**Causes**:
- Consumer stopped sending heartbeats
- Heartbeat timeout too short for processing time
- Network issues preventing heartbeat delivery

**Solutions**:

1. **Increase heartbeat timeout**:
   ```java
   SubscriptionOptions options = SubscriptionOptions.builder()
       .heartbeatIntervalSeconds(30)
       .heartbeatTimeoutSeconds(300)  // Increase to 5 minutes
       .build();
   ```

2. **Check processing time**: Ensure messages are processed within heartbeat interval
   ```java
   consumerGroup.setMessageHandler(message -> {
       long startTime = System.currentTimeMillis();

       // Process message...

       long duration = System.currentTimeMillis() - startTime;
       if (duration > 30000) {  // 30 seconds
           logger.warn("Slow processing detected: {}ms", duration);
       }

       return CompletableFuture.completedFuture(null);
   });
   ```

3. **Recover dead consumer**: Re-subscribe to resume
   ```java
   consumerGroup.start(SubscriptionOptions.defaults());
   ```

---

### Issue 3: Duplicate Message Processing

**Symptoms**: Same message processed multiple times

**Causes**:
- Message handler returning failed future (triggers retry)
- Multiple consumer groups with same name (not recommended)
- Consumer not acknowledging messages

**Solutions**:

1. **Ensure idempotent processing**:
   ```java
   consumerGroup.setMessageHandler(message -> {
       String messageId = message.getId();

       // Check if already processed (idempotency)
       if (isAlreadyProcessed(messageId)) {
           logger.info("Message {} already processed, skipping", messageId);
           return CompletableFuture.completedFuture(null);
       }

       // Process and mark as processed
       processMessage(message.getPayload());
       markAsProcessed(messageId);

       return CompletableFuture.completedFuture(null);
   });
   ```

2. **Handle errors correctly**:
   ```java
   consumerGroup.setMessageHandler(message -> {
       try {
           processMessage(message.getPayload());
           return CompletableFuture.completedFuture(null);  // Success

       } catch (TransientException e) {
           // Transient error - retry
           return CompletableFuture.failedFuture(e);

       } catch (PermanentException e) {
           // Permanent error - acknowledge to prevent infinite retries
           logger.error("Permanent error: {}", e.getMessage());
           return CompletableFuture.completedFuture(null);
       }
   });
   ```

---

## Next Steps

### 1. Run All Demo Examples

```bash
# Run all consumer group demo tests
mvn test -Dtest="*DemoTest" -pl peegeeq-examples
```

### 2. Explore Advanced Guides

- **[Consumer Group Architecture Guide](design/CONSUMER_GROUP_ARCHITECTURE_GUIDE.md)** - Deep dive into architecture and design
- **[Consumer Group Fanout Design](design/CONSUMER_GROUP_FANOUT_DESIGN.md)** - Complete design specification
- **[Implementation Review](devtest/IMPLEMENTATION_REVIEW_2025-11-13.md)** - Production readiness assessment

### 3. Review Example Code

| Example | Focus | Complexity |
|---------|-------|------------|
| [`ConsumerGroupLoadBalancingDemoTest.java`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/nativequeue/ConsumerGroupLoadBalancingDemoTest.java) | QUEUE semantics, load balancing | Beginner |
| [`AdvancedProducerConsumerGroupTest.java`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/AdvancedProducerConsumerGroupTest.java) | PUB_SUB semantics, multiple groups | Beginner |
| [`LateJoiningConsumerDemoTest.java`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/LateJoiningConsumerDemoTest.java) | Backfill, time-based replay | Intermediate |
| [`DeadConsumerDetectionDemoTest.java`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/DeadConsumerDetectionDemoTest.java) | Heartbeat monitoring, recovery | Intermediate |
| [`ZeroSubscriptionProtectionDemoTest.java`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/ZeroSubscriptionProtectionDemoTest.java) | Write protection, retention | Advanced |
| [`ConsumerGroupResilienceTest.java`](../peegeeq-examples/src/test/java/dev/mars/peegeeq/examples/outbox/ConsumerGroupResilienceTest.java) | Error handling, retries | Advanced |

### 4. Production Deployment

Before deploying to production:

1. ✅ **Review coding principles**: [`pgq-coding-principles.md`](devtest/pgq-coding-principles.md)
2. ✅ **Run integration tests**: `mvn test -Pintegration-tests`
3. ✅ **Configure monitoring**: Set up heartbeat detection and alerting
4. ✅ **Plan capacity**: Reference counting mode supports ≤16 consumer groups per topic
5. ✅ **Set retention policies**: Configure appropriate message retention hours

---

## Summary

You've learned:

- **Core Concepts**: QUEUE vs PUB_SUB semantics, consumer groups, subscriptions
- **Basic Patterns**: Load balancing, event broadcasting
- **Intermediate Features**: Message filtering (client-side with `MessageFilter`, server-side with `ServerSideFilter`), error handling, heartbeat monitoring
- **Advanced Features**: Late-joining consumers, zero-subscription protection
- **Production Patterns**: Microservices integration, dead consumer recovery

**Ready for production?** Review the [Implementation Review](devtest/IMPLEMENTATION_REVIEW_2025-11-13.md) for production readiness assessment.

---

**End of Getting Started Guide**




