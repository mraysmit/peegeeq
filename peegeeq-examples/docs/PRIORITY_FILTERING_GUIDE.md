# Message Priority and Filtering Integration Guide

**Purpose**: Guide for implementing priority-based message processing with PeeGeeQ's outbox pattern

**Audience**: Developers implementing priority-based message routing and competing consumers

**Example**: `springbootpriority` - Trade settlement processing with priority levels

---

## Overview

This guide demonstrates how to implement priority-based message processing using PeeGeeQ's outbox pattern with application-level filtering. The focus is on **priority routing patterns and competing consumers**, not framework features.

### Key Concepts

- **Priority Levels** - CRITICAL, HIGH, NORMAL for different message urgency
- **Application-Level Filtering** - Filter messages in consumer handlers using headers
- **Competing Consumers** - Multiple consumers compete for messages from same queue
- **Priority Headers** - Attach priority metadata to messages for filtering
- **SLA-Based Processing** - Different processing speeds based on priority

---

## Priority Levels

### Priority Definition

Define priority levels with numeric values, SLAs, and business context:

```java
public enum Priority {
    CRITICAL("CRITICAL", 10, "Settlement fails, breaks, urgent corrections"),
    HIGH("HIGH", 7, "Trade amendments, cancellations, time-sensitive updates"),
    NORMAL("NORMAL", 5, "New trade confirmations, standard processing");

    private final String level;
    private final int value;
    private final String description;

    // Constructor and getters
}
```

### Priority Assignment Strategy

| Priority | Business Scenario | SLA | Processing Team | Alert Level |
|----------|------------------|-----|-----------------|-------------|
| **CRITICAL** | Settlement fails requiring immediate investigation | < 1 minute | Senior operations team | Immediate alert + escalation |
| **HIGH** | Trade amendments before settlement cutoff | < 5 minutes | Operations team | Alert if SLA breached |
| **NORMAL** | New trade confirmations | < 30 minutes | Standard processing team | No alert |

### Real-World Examples

**CRITICAL Priority**:
- Settlement fails due to insufficient cash
- Reconciliation breaks requiring immediate resolution
- Urgent corrections to prevent settlement failure
- Custodian rejects requiring same-day resolution

**HIGH Priority**:
- Trade amendments received after initial confirmation
- Cancellations that must be processed before settlement
- Same-day settlement instructions
- Time-sensitive corrections

**NORMAL Priority**:
- New trade confirmations
- Standard settlement instructions
- Routine updates
- Non-urgent corrections

---

## Competing Consumers Pattern

### What is Competing Consumers?

**Competing Consumers** means multiple consumer instances compete for messages from the same queue:

- All consumers subscribe to the same queue
- Each message is delivered to **only one** consumer
- Consumers compete to process messages
- Enables horizontal scaling and load distribution

**Key Difference from Pub/Sub**:
- **Pub/Sub**: Each subscriber gets a copy of every message
- **Competing Consumers**: Each message goes to only one consumer

### How PeeGeeQ Implements Competing Consumers

PeeGeeQ's outbox pattern implements competing consumers using database-level locking:

1. **Message Published** - Producer writes to outbox table
2. **Consumers Poll** - Multiple consumers poll the outbox table
3. **Database Lock** - First consumer to lock the row wins
4. **Process Message** - Winning consumer processes the message
5. **Mark Complete** - Consumer marks message as processed
6. **Other Consumers Skip** - Other consumers see message is already processed

**Example with 3 Consumers**:
```
Outbox Table:
  Message 1 (CRITICAL) - Available
  Message 2 (HIGH) - Available
  Message 3 (NORMAL) - Available

Consumer A polls → Locks Message 1 → Processes
Consumer B polls → Locks Message 2 → Processes
Consumer C polls → Locks Message 3 → Processes

All three messages processed in parallel by different consumers!
```

### Why Competing Consumers for Priority Processing?

**Scenario**: 100 CRITICAL messages arrive

**Without Competing Consumers**:
- Single consumer processes sequentially
- Takes 100 minutes (1 minute per message)
- SLA breach for 99 messages

**With Competing Consumers** (10 consumers):
- 10 consumers process in parallel
- Takes 10 minutes (10 messages per consumer)
- All messages meet SLA

---

## Producer Pattern

### Attaching Priority Headers

Attach priority as a header when sending messages:

```java
@Service
public class TradeProducerService {

    private final MessageProducer<TradeSettlementEvent> producer;

    public CompletableFuture<Void> sendTradeEvent(TradeSettlementEvent event) {
        Map<String, String> headers = new HashMap<>();
        headers.put("priority", event.getPriority().getLevel());
        headers.put("trade-status", event.getStatus());
        headers.put("event-type", "trade-settlement");
        headers.put("timestamp", Instant.now().toString());

        return producer.send(event, headers);
    }
}
```

### Priority Assignment Examples

**CRITICAL - Settlement Fail**:
```java
public CompletableFuture<Void> sendSettlementFail(Trade trade, String failureReason) {
    TradeSettlementEvent event = TradeSettlementEvent.builder()
        .tradeId(trade.getTradeId())
        .counterparty(trade.getCounterparty())
        .amount(trade.getAmount())
        .currency(trade.getCurrency())
        .settlementDate(trade.getSettlementDate())
        .status("FAIL")
        .priority(Priority.CRITICAL)
        .failureReason(failureReason)
        .timestamp(Instant.now())
        .build();

    return sendTradeEvent(event);
}

// Usage:
sendSettlementFail(trade, "Insufficient cash in settlement account");
```

**HIGH - Trade Amendment**:
```java
public CompletableFuture<Void> sendAmendment(Trade originalTrade, Trade amendedTrade) {
    TradeSettlementEvent event = TradeSettlementEvent.builder()
        .tradeId(originalTrade.getTradeId())
        .counterparty(amendedTrade.getCounterparty())
        .amount(amendedTrade.getAmount())
        .currency(amendedTrade.getCurrency())
        .settlementDate(amendedTrade.getSettlementDate())
        .status("AMEND")
        .priority(Priority.HIGH)
        .timestamp(Instant.now())
        .build();

    return sendTradeEvent(event);
}

// Usage:
sendAmendment(originalTrade, amendedTrade);
```

**NORMAL - New Confirmation**:
```java
public CompletableFuture<Void> sendConfirmation(Trade trade) {
    TradeSettlementEvent event = TradeSettlementEvent.builder()
        .tradeId(trade.getTradeId())
        .counterparty(trade.getCounterparty())
        .amount(trade.getAmount())
        .currency(trade.getCurrency())
        .settlementDate(trade.getSettlementDate())
        .status("NEW")
        .priority(Priority.NORMAL)
        .timestamp(Instant.now())
        .build();

    return sendTradeEvent(event);
}

// Usage:
sendConfirmation(trade);
```

---

## Consumer Patterns

### Pattern 1: Single Consumer Processing All Priorities

**Use Case**: Single operations team handles all settlement events with different SLAs

Process all messages but apply different processing logic based on priority:

```java
@Service
public class AllTradesConsumerService {

    private final MessageConsumer<TradeSettlementEvent> consumer;
    private final AtomicLong criticalProcessed = new AtomicLong(0);
    private final AtomicLong highProcessed = new AtomicLong(0);
    private final AtomicLong normalProcessed = new AtomicLong(0);

    @EventListener(ApplicationReadyEvent.class)
    public void startConsuming() {
        consumer.subscribe(this::processMessage);
    }

    private CompletableFuture<Void> processMessage(Message<TradeSettlementEvent> message) {
        TradeSettlementEvent event = message.getPayload();
        Priority priority = event.getPriority();

        // Track metrics by priority
        switch (priority) {
            case CRITICAL -> criticalProcessed.incrementAndGet();
            case HIGH -> highProcessed.incrementAndGet();
            case NORMAL -> normalProcessed.incrementAndGet();
        }

        // Process with different SLAs based on priority
        return processTradeByPriority(event, priority);
    }

    private CompletableFuture<Void> processTradeByPriority(
            TradeSettlementEvent event, Priority priority) {

        return switch (priority) {
            case CRITICAL -> processCritical(event);  // < 1 minute SLA
            case HIGH -> processHigh(event);          // < 5 minutes SLA
            case NORMAL -> processNormal(event);      // < 30 minutes SLA
        };
    }

    private CompletableFuture<Void> processCritical(TradeSettlementEvent event) {
        // CRITICAL: Settlement fail - immediate action required
        log.error("CRITICAL: Settlement fail for trade {}: {}",
            event.getTradeId(), event.getFailureReason());

        // 1. Alert operations team immediately
        alertOpsTeam(event);

        // 2. Create incident ticket
        createIncident(event);

        // 3. Update trade settlement table
        updateTradeSettlement(event);

        // 4. Notify trading desk
        notifyTradingDesk(event);

        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> processHigh(TradeSettlementEvent event) {
        // HIGH: Trade amendment - process quickly
        log.warn("HIGH: Trade amendment for trade {}", event.getTradeId());

        // 1. Update trade settlement table
        updateTradeSettlement(event);

        // 2. Notify if approaching cutoff time
        if (isApproachingCutoff(event)) {
            alertOpsTeam(event);
        }

        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> processNormal(TradeSettlementEvent event) {
        // NORMAL: New confirmation - standard processing
        log.info("NORMAL: New trade confirmation for trade {}", event.getTradeId());

        // 1. Update trade settlement table
        updateTradeSettlement(event);

        return CompletableFuture.completedFuture(null);
    }
}
```

**Benefits**:
- Simple architecture - one consumer service
- Centralized metrics tracking
- Flexible priority-based processing logic
- Easy to understand and maintain

**Drawbacks**:
- All priorities compete for same consumer resources
- CRITICAL messages may wait behind NORMAL messages
- Cannot scale different priorities independently

### Pattern 2: Multiple Consumers with Priority Filters

**Use Case**: Separate teams handle different priority levels with dedicated resources

#### Critical-Only Consumer

**Team**: Senior operations team dedicated to settlement fails

Process only CRITICAL priority messages:

```java
@Service
public class CriticalTradeConsumerService {

    private final MessageConsumer<TradeSettlementEvent> consumer;
    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong messagesFiltered = new AtomicLong(0);

    @EventListener(ApplicationReadyEvent.class)
    public void startConsuming() {
        consumer.subscribe(this::processMessage);
    }

    private CompletableFuture<Void> processMessage(Message<TradeSettlementEvent> message) {
        // Filter for CRITICAL priority only
        String priority = message.getHeaders().get("priority");

        if (!"CRITICAL".equals(priority)) {
            messagesFiltered.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

        messagesProcessed.incrementAndGet();
        return processCriticalTrade(message);
    }

    private CompletableFuture<Void> processCriticalTrade(
            Message<TradeSettlementEvent> message) {

        TradeSettlementEvent event = message.getPayload();

        log.error("CRITICAL SETTLEMENT FAIL: Trade {} - {}",
            event.getTradeId(), event.getFailureReason());

        // 1. IMMEDIATE ALERT - Page on-call team
        sendPagerAlert(event);

        // 2. Create P1 incident ticket
        createP1Incident(event);

        // 3. Update trade settlement table with FAIL status
        updateTradeSettlement(event, "FAILED");

        // 4. Notify trading desk immediately
        notifyTradingDesk(event);

        // 5. Start investigation workflow
        startInvestigation(event);

        // 6. Update metrics dashboard
        updateCriticalMetrics(event);

        return CompletableFuture.completedFuture(null);
    }
}
```

**Benefits**:
- Dedicated resources for critical issues
- No competition with lower priority messages
- Can scale CRITICAL consumer independently
- Clear separation of responsibilities

**Metrics**:
- Messages processed: Only CRITICAL
- Messages filtered: HIGH + NORMAL
- SLA: < 1 minute from message arrival to alert

#### High-Priority Consumer

**Team**: Operations team handling time-sensitive amendments

Process HIGH and CRITICAL priority messages:

```java
@Service
public class HighPriorityConsumerService {

    private final MessageConsumer<TradeSettlementEvent> consumer;
    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong messagesFiltered = new AtomicLong(0);

    @EventListener(ApplicationReadyEvent.class)
    public void startConsuming() {
        consumer.subscribe(this::processMessage);
    }

    private CompletableFuture<Void> processMessage(Message<TradeSettlementEvent> message) {
        // Filter for HIGH priority and above
        String priority = message.getHeaders().get("priority");

        if (!"HIGH".equals(priority) && !"CRITICAL".equals(priority)) {
            messagesFiltered.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

        messagesProcessed.incrementAndGet();
        return processHighPriorityTrade(message);
    }

    private CompletableFuture<Void> processHighPriorityTrade(
            Message<TradeSettlementEvent> message) {

        TradeSettlementEvent event = message.getPayload();
        String priority = message.getHeaders().get("priority");

        if ("CRITICAL".equals(priority)) {
            // Also process CRITICAL (backup for CriticalConsumer)
            log.error("HIGH PRIORITY CONSUMER: Processing CRITICAL trade {}",
                event.getTradeId());
            return processCritical(event);
        }

        // HIGH priority: Trade amendment
        log.warn("HIGH PRIORITY: Trade amendment for trade {}", event.getTradeId());

        // 1. Check if approaching settlement cutoff
        if (isApproachingCutoff(event)) {
            alertOpsTeam(event);
        }

        // 2. Update trade settlement table with amendment
        updateTradeSettlement(event, "AMENDED");

        // 3. Notify trading desk of amendment
        notifyTradingDesk(event);

        // 4. Update metrics
        updateHighPriorityMetrics(event);

        return CompletableFuture.completedFuture(null);
    }
}
```

**Benefits**:
- Handles both HIGH and CRITICAL (provides redundancy)
- Dedicated resources for time-sensitive work
- Can scale independently from NORMAL processing
- Backup processing for CRITICAL if needed

**Metrics**:
- Messages processed: HIGH + CRITICAL
- Messages filtered: NORMAL
- SLA: < 5 minutes for HIGH, < 1 minute for CRITICAL

---

## Message Flow Scenarios

### Scenario 1: Settlement Fail (CRITICAL)

**Business Context**: Trade TRD-67890 fails settlement due to insufficient cash

**Timeline**:
```
09:00:00 - Custodian rejects settlement
09:00:05 - Producer sends CRITICAL message
09:00:06 - Message written to outbox table with priority=CRITICAL header
09:00:07 - CriticalConsumer polls outbox
09:00:07 - CriticalConsumer locks message (database lock)
09:00:08 - HighPriorityConsumer polls outbox
09:00:08 - HighPriorityConsumer sees message locked, skips
09:00:09 - AllTradesConsumer polls outbox
09:00:09 - AllTradesConsumer sees message locked, skips
09:00:10 - CriticalConsumer processes message:
            - Sends pager alert to on-call team
            - Creates P1 incident ticket
            - Updates trade settlement table
            - Notifies trading desk
09:00:15 - CriticalConsumer marks message complete
09:00:30 - On-call team responds to alert
```

**Consumer Behavior**:
- **CriticalConsumer**: ✅ Processes (matches CRITICAL filter)
- **HighPriorityConsumer**: ✅ Would process if CriticalConsumer failed (backup)
- **AllTradesConsumer**: ✅ Would process if both above failed (backup)

**Result**: Message processed in < 1 minute, SLA met

### Scenario 2: Trade Amendment (HIGH)

**Business Context**: Trade TRD-67891 amended - counterparty changed

**Timeline**:
```
10:30:00 - Trading desk submits amendment
10:30:02 - Producer sends HIGH message
10:30:03 - Message written to outbox table with priority=HIGH header
10:30:04 - CriticalConsumer polls outbox
10:30:04 - CriticalConsumer filters out (not CRITICAL)
10:30:05 - HighPriorityConsumer polls outbox
10:30:05 - HighPriorityConsumer locks message (database lock)
10:30:06 - AllTradesConsumer polls outbox
10:30:06 - AllTradesConsumer sees message locked, skips
10:30:07 - HighPriorityConsumer processes message:
            - Checks settlement cutoff time
            - Updates trade settlement table
            - Notifies trading desk
10:30:10 - HighPriorityConsumer marks message complete
```

**Consumer Behavior**:
- **CriticalConsumer**: ❌ Filtered out (not CRITICAL)
- **HighPriorityConsumer**: ✅ Processes (matches HIGH filter)
- **AllTradesConsumer**: ✅ Would process if HighPriorityConsumer failed (backup)

**Result**: Message processed in < 5 minutes, SLA met

### Scenario 3: New Trade Confirmation (NORMAL)

**Business Context**: New trade TRD-67892 confirmed

**Timeline**:
```
14:00:00 - Trade confirmation received
14:00:01 - Producer sends NORMAL message
14:00:02 - Message written to outbox table with priority=NORMAL header
14:00:03 - CriticalConsumer polls outbox
14:00:03 - CriticalConsumer filters out (not CRITICAL)
14:00:04 - HighPriorityConsumer polls outbox
14:00:04 - HighPriorityConsumer filters out (not HIGH)
14:00:05 - AllTradesConsumer polls outbox
14:00:05 - AllTradesConsumer locks message (database lock)
14:00:06 - AllTradesConsumer processes message:
            - Updates trade settlement table
14:00:08 - AllTradesConsumer marks message complete
```

**Consumer Behavior**:
- **CriticalConsumer**: ❌ Filtered out (not CRITICAL)
- **HighPriorityConsumer**: ❌ Filtered out (not HIGH)
- **AllTradesConsumer**: ✅ Processes (no filter)

**Result**: Message processed in < 30 minutes, SLA met

### Scenario 4: Multiple CRITICAL Messages (Load Test)

**Business Context**: 10 settlement fails arrive simultaneously

**Timeline**:
```
09:00:00 - 10 CRITICAL messages arrive
09:00:01 - All 10 messages written to outbox
09:00:02 - CriticalConsumer instance 1 locks message 1
09:00:02 - CriticalConsumer instance 2 locks message 2
09:00:02 - CriticalConsumer instance 3 locks message 3
09:00:02 - HighPriorityConsumer instance 1 locks message 4
09:00:02 - HighPriorityConsumer instance 2 locks message 5
09:00:02 - AllTradesConsumer instance 1 locks message 6
09:00:02 - AllTradesConsumer instance 2 locks message 7
09:00:02 - AllTradesConsumer instance 3 locks message 8
09:00:02 - AllTradesConsumer instance 4 locks message 9
09:00:02 - AllTradesConsumer instance 5 locks message 10
09:00:15 - All 10 messages processed in parallel
```

**Competing Consumers in Action**:
- All consumers compete for messages
- Database locking ensures each message processed once
- Parallel processing meets SLA for all messages
- Demonstrates horizontal scalability

---

## Priority Processing Strategies

### Strategy 1: Dedicated Consumer Pools

**Architecture**: Separate consumer pools for each priority level

```
CRITICAL Pool (3 consumers):
  - CriticalConsumer-1
  - CriticalConsumer-2
  - CriticalConsumer-3

HIGH Pool (5 consumers):
  - HighPriorityConsumer-1
  - HighPriorityConsumer-2
  - HighPriorityConsumer-3
  - HighPriorityConsumer-4
  - HighPriorityConsumer-5

NORMAL Pool (10 consumers):
  - AllTradesConsumer-1
  - AllTradesConsumer-2
  - ...
  - AllTradesConsumer-10
```

**Benefits**:
- Guaranteed resources for each priority
- No competition between priorities
- Can scale each pool independently
- Clear SLA boundaries

**Drawbacks**:
- More complex deployment
- Resource underutilization if priorities unbalanced
- More monitoring required

### Strategy 2: Shared Consumer Pool with Filtering

**Architecture**: All consumers process all priorities, filter in handler

```
Shared Pool (10 consumers):
  - Consumer-1 (processes all, filters in handler)
  - Consumer-2 (processes all, filters in handler)
  - ...
  - Consumer-10 (processes all, filters in handler)
```

**Benefits**:
- Simple deployment
- Efficient resource utilization
- Automatic load balancing
- Easy to scale

**Drawbacks**:
- CRITICAL may wait behind NORMAL
- No guaranteed resources per priority
- SLA harder to guarantee

### Strategy 3: Hybrid Approach (Recommended)

**Architecture**: Dedicated pool for CRITICAL, shared pool for HIGH/NORMAL

```
CRITICAL Pool (3 dedicated consumers):
  - CriticalConsumer-1
  - CriticalConsumer-2
  - CriticalConsumer-3

Shared Pool (10 consumers):
  - SharedConsumer-1 (processes HIGH + NORMAL)
  - SharedConsumer-2 (processes HIGH + NORMAL)
  - ...
  - SharedConsumer-10 (processes HIGH + NORMAL)
```

**Benefits**:
- Guaranteed resources for CRITICAL
- Efficient resource use for HIGH/NORMAL
- Balanced complexity
- Good SLA guarantees

**Recommended for most use cases**

---

## Operational Considerations

### SLA Monitoring

**CRITICAL SLA**: < 1 minute from message arrival to processing complete

```java
// Track SLA compliance
private void trackSLA(Message<TradeSettlementEvent> message, Instant processingComplete) {
    Instant messageArrival = Instant.parse(message.getHeaders().get("timestamp"));
    Duration processingTime = Duration.between(messageArrival, processingComplete);

    if (processingTime.toSeconds() > 60) {
        log.error("SLA BREACH: CRITICAL message took {} seconds",
            processingTime.toSeconds());
        alertSLABreach(message, processingTime);
    }
}
```

**HIGH SLA**: < 5 minutes

**NORMAL SLA**: < 30 minutes

### Alerting Strategy

**CRITICAL Messages**:
- Immediate pager alert to on-call team
- Create P1 incident ticket
- Escalate if not acknowledged in 2 minutes
- Alert trading desk immediately

**HIGH Messages**:
- Email alert to operations team
- Create P2 incident ticket if approaching cutoff
- Escalate if SLA breached

**NORMAL Messages**:
- No immediate alert
- Daily summary report
- Alert only if SLA consistently breached

### Scaling Guidelines

**When to Scale CRITICAL Pool**:
- Average processing time > 30 seconds
- Queue depth > 5 messages
- SLA breach rate > 1%

**When to Scale HIGH Pool**:
- Average processing time > 2 minutes
- Queue depth > 20 messages
- SLA breach rate > 5%

**When to Scale NORMAL Pool**:
- Average processing time > 15 minutes
- Queue depth > 100 messages
- SLA breach rate > 10%

### Metrics to Track

**Per Priority**:
- Messages processed count
- Messages filtered count
- Average processing time
- SLA compliance rate
- Queue depth

**Per Consumer**:
- Messages processed
- Processing errors
- Uptime
- Last message processed timestamp

**System-Wide**:
- Total throughput
- Error rate
- Database connection pool utilization
- Consumer health status

---

## Best Practices

### 1. Priority Assignment
- **CRITICAL**: Only for issues requiring immediate human intervention
- **HIGH**: Time-sensitive but can wait a few minutes
- **NORMAL**: Standard processing, no urgency
- Document priority criteria in runbook
- Review priority usage quarterly

### 2. Consumer Design
- **Dedicated Pools**: Use for CRITICAL to guarantee resources
- **Shared Pools**: Use for HIGH/NORMAL for efficiency
- **Competing Consumers**: Enable horizontal scaling
- Monitor consumer health continuously

### 3. Filtering Strategy
- Filter in message handler, not in subscribe
- Track filtered message counts for monitoring
- Return `CompletableFuture.completedFuture(null)` for filtered messages
- Log filtered messages at TRACE level

### 4. Error Handling
- Retry transient failures (network, database)
- Send to DLQ for permanent failures
- Alert on repeated failures
- Track error rate by priority

### 5. Monitoring
- Track messages processed by priority
- Track messages filtered by each consumer
- Monitor queue depth by priority
- Alert on critical message backlog
- Dashboard showing real-time metrics

---

## Summary

### Key Takeaways

1. **Competing Consumers** - Multiple consumers compete for messages from same queue using database locking
2. **Priority Headers** - Attach priority metadata to messages for filtering
3. **Application-Level Filtering** - Filter in consumer handlers based on priority headers
4. **SLA-Based Processing** - Different processing speeds and alerting based on priority
5. **Horizontal Scaling** - Add more consumers to handle increased load

### When to Use Priority-Based Processing

- ✅ Different message urgency levels (CRITICAL, HIGH, NORMAL)
- ✅ Different SLAs for different message types
- ✅ Separate processing teams for different priorities
- ✅ Need immediate alerting for critical issues
- ✅ Operational dashboards showing priority metrics

### When NOT to Use Priority-Based Processing

- ❌ All messages have same urgency
- ❌ All messages have same SLA
- ❌ No need for separate alerting
- ❌ Simple FIFO processing is sufficient

### Architecture Recommendations

**Small Scale** (< 1000 messages/day):
- Single consumer pool processing all priorities
- Filter in handler based on priority
- Simple metrics tracking

**Medium Scale** (1000-10000 messages/day):
- Dedicated pool for CRITICAL (3 consumers)
- Shared pool for HIGH/NORMAL (10 consumers)
- SLA monitoring and alerting

**Large Scale** (> 10000 messages/day):
- Dedicated pool for CRITICAL (5+ consumers)
- Dedicated pool for HIGH (10+ consumers)
- Dedicated pool for NORMAL (20+ consumers)
- Real-time metrics dashboard
- Automated scaling based on queue depth

### Reference Implementation

See `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/springbootpriority/` for complete working example.

---

## License

Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd

Licensed under the Apache License, Version 2.0

