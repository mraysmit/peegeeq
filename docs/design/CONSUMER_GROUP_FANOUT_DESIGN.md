# Consumer Group Fan-Out Design: Hybrid Queue/Pub-Sub

**Status**: Design Specification
**Author**: Mark Andrew Ray-Smith Cityline Ltd
**Date**: 2025-11-11
**Version**: 2.0
**Last Updated**: 2025-11-12

> **Note**: For a comparison of alternative design options considered, see [CONSUMER_GROUP_FANOUT_DESIGN_OPTIONS.md](CONSUMER_GROUP_FANOUT_DESIGN_OPTIONS.md).

---

## Changelog

### Version 2.0 (2025-11-12) - Major Architectural Enhancements

**Breaking Changes**:
- Introduced two distinct **Completion Tracking Modes** (Reference Counting vs Offset/Watermark) with different schemas and semantics
- Changed default zero-subscription retention from 1 hour to **24 hours**
- Added mandatory schema changes for resumable backfill (checkpoint columns)

**New Features**:

1. **Completion Tracking Modes** (Section 4)
   - **Mode 1: Reference Counting (Bitmap/Counter)** - For ≤8-16 consumer groups
     - Uses `completed_consumer_groups INT` or `completed_groups_bitmap BIGINT`
     - Simple semantics, high write amplification (N × M updates/second)
     - Suitable for low-fanout scenarios

   - **Mode 2: Offset/Watermark** - For >16 consumer groups
     - Uses per-subscription cursors (`outbox_subscription_offsets`)
     - Watermark-based cleanup with partition dropping
     - Minimal write amplification (N updates/second regardless of message count)
     - Includes zombie subscription protection and forced retention caps

   - **Decision Matrix**: Provides clear guidance on when to use each mode
   - **Migration Paths**: Bidirectional migration between modes

2. **Enhanced Zero-Subscription Protection** (Section 3, Gap 3)
   - **Configurable minimum retention**: Per-topic retention (default: 24 hours, not 1 hour)
   - **Producer write blocking**: Optional fail-fast for PUB_SUB topics with zero ACTIVE subscriptions
   - **Configuration best practices**: Guidance for critical, audit, ephemeral, and queue topics
   - **Monitoring & alerts**: `ZeroSubscriptionMonitor` implementation

3. **Resumable Backfill with Adaptive Rate Limiting** (Section 6, Question 3)
   - **Persistent checkpoints**: Resume from last processed ID after crash
   - **Cancellation API**: Stop backfill mid-flight without data corruption
   - **Adaptive rate limiting**: Throttle based on database p95 latency
   - **Progress tracking**: Observable metrics (processed count, rate, ETA)
   - **Bounded impact**: Configurable max batch size and rate to protect OLTP workloads
   - **Schema additions**: `backfill_status`, `backfill_checkpoint_id`, `backfill_processed_messages`, etc.

4. **Partition-Aware Cleanup** (Section 4, Offset/Watermark Mode)
   - **Watermark-based partition dropping**: `DROP PARTITION` (instant, no WAL) vs `DELETE` (slow, WAL-heavy)
   - **Zombie subscription protection**: Exclude DEAD subscriptions from watermark, force-advance stale offsets
   - **Forced retention caps**: Prevent single subscription from pinning data forever (7-day TTL default)
   - **Autovacuum guidance**: Fillfactor, thresholds, and partition management automation

5. **Pre-GA Decision Checkpoints** (Section 14)
   - **Fanout write amplification test**: Prove mode selection with N∈{4,8,16,32,64,100} groups
   - **Kill-and-resurrect chaos test**: Verify dead cleanup + bounded resurrection backfill
   - **Backfill vs OLTP contention test**: Ratchet backfill rate until p95 of critical OLTP queries unaffected
   - **Success thresholds**: Table bloat < 20%, WAL rate < 50% I/O capacity, cleanup rate > production rate, p95 latency < 10ms
   - **Decision matrix**: All "Blocker for GA" tests must PASS before production deployment

**Improvements**:
- **Cleanup Job Operations** (Section 11): Added detailed performance analysis, scheduling recommendations, impact analysis, and monitoring strategies
- **Scalability Analysis**: Updated with concrete numbers for both completion tracking modes
- **Implementation Details**: Enhanced with validation logic, safe hash calculation, and state machine definitions

**Bug Fixes**:
- **Hash collision bug**: Fixed `Math.abs(Integer.MIN_VALUE)` edge case using `(hashCode() & Integer.MAX_VALUE)`
- **Subscription state machine**: Defined clear transitions with CANCELLED as terminal state

**Documentation**:
- Added 278 lines for Completion Tracking Modes section
- Added 530 lines for Cleanup Job Operations section
- Added 407 lines for Resumable Backfill implementation
- Added 279 lines for Pre-GA Decision Checkpoints section
- Total: ~1,500 lines of new content

**Migration Notes**:
- Existing deployments using reference counting can continue without changes
- New deployments with >16 consumer groups should use Offset/Watermark mode
- Backfill operations will automatically resume from checkpoints after upgrade
- Zero-subscription retention default changed from 1h to 24h (safer default)

---

### Version 1.1 (2025-11-12) - Design Gaps Documented

**Changes**:
- Added "Known Design Gaps & Open Questions" section documenting 6 critical gaps
- Proposed resolutions for message completion logic, zero-member lifecycle, registration timing, hash collision bug, subscription state machine, and backfill limits
- Added summary table showing all gaps with severity levels

---

### Version 1.0 (2025-11-11) - Initial Design

**Initial Features**:
- Hybrid queue/pub-sub semantics with per-topic configuration
- Reference counting with `required_consumer_groups` / `completed_consumer_groups`
- Consumer group registration via `outbox_topic_subscriptions`
- Dead consumer detection with heartbeat tracking
- Basic cleanup job implementation
- Subscription lifecycle (ACTIVE, PAUSED, CANCELLED, DEAD)
- Catch-up strategies (fromNow, fromBeginning, fromTimestamp)

---

## Table of Contents
1. [Overview](#overview)
2. [Design Principles](#design-principles)
3. [Known Design Gaps & Open Questions](#known-design-gaps--open-questions)
4. [Completion Tracking Modes](#completion-tracking-modes)
5. [Topic Semantics](#topic-semantics)
6. [Critical Design Questions & Solutions](#critical-design-questions--solutions)
7. [Database Schema Changes](#database-schema-changes)
8. [API Design](#api-design)
9. [Implementation Details](#implementation-details)
10. [Dead Consumer Group Cleanup](#dead-consumer-group-cleanup)
11. [Cleanup Job Operations](#cleanup-job-operations)
12. [Concurrency and Scalability Concerns](#concurrency-and-scalability-concerns)
13. [Scalability Analysis](#scalability-analysis)
14. [Pre-GA Decision Checkpoints](#pre-ga-decision-checkpoints)
15. [Migration Path](#migration-path)
16. [Comparison with Other Systems](#comparison-with-other-systems)

---

## Overview

This document specifies the **Hybrid Queue/Pub-Sub** design for implementing reliable fan-out with at-least-once delivery to multiple consumers in PeeGeeQ. This approach supports **pub/sub semantics** (message replication) while maintaining backward compatibility with existing **queue semantics** (message distribution).

### Key Features
- ✅ At-least-once delivery guarantee to multiple independent consumer groups
- ✅ Backward compatibility with existing queue-based consumers
- ✅ Support for late-joining consumers with configurable catch-up behavior
- ✅ Automatic cleanup of dead/inactive consumer groups
- ✅ Efficient message retention and cleanup
- ✅ Clear, explicit configuration per topic
- ✅ Scalable to 30,000+ messages/second with bitmap tracking

---

## Design Principles

1. **Backward Compatibility**: Existing queue consumers work unchanged
2. **Opt-In**: Only topics configured as `PUB_SUB` use new behavior
3. **Explicit Configuration**: Topic semantics are clearly defined
4. **Gradual Migration**: Topics can be migrated one at a time
5. **Performance**: Queue topics keep efficient `FOR UPDATE SKIP LOCKED`

---

## Known Design Gaps & Open Questions

This section documents design gaps and open questions identified during review that require resolution before implementation.

### Critical Gap 1: Message Completion Logic for Fan-Out

**Issue**: The design requires messages to be marked COMPLETED only when `completed_consumer_groups >= required_consumer_groups`, but the exact completion logic needs clarification for edge cases.

**Current Design** (Section: Question 2, lines 245-273):
```java
private Future<Void> markMessageProcessedByGroup(Long messageId, String groupName) {
    // Atomically increment completed_consumer_groups
    // Mark COMPLETED when completed_consumer_groups >= required_consumer_groups
}
```

**Gap**: What happens when `required_consumer_groups` changes between message insertion and completion?

**Scenarios to Address**:
1. **Consumer group dies after message inserted**: `required_consumer_groups` was 3, now should be 2
   - Should we decrement `required_consumer_groups` on existing messages?
   - Or should we track the "snapshot" value at insertion time?

2. **New consumer group joins with backfill**: `required_consumer_groups` was 2, now should be 3
   - Backfill increments `required_consumer_groups` for existing messages
   - What if message was already COMPLETED with 2/2 groups?
   - Should it revert to PENDING?

**Resolution** (Adopted):
- `required_consumer_groups` is **immutable** after message insertion (snapshot semantics)
- Dead consumer cleanup **decrements** `required_consumer_groups` for PENDING/PROCESSING messages only
- Late-joining consumers with backfill **increment** `required_consumer_groups` for PENDING/PROCESSING messages only
- COMPLETED messages are **never modified** (idempotent completion)

This approach provides clear semantics and prevents race conditions. See Question 2 for implementation details.

---

### Critical Gap 2: Zero-Member Consumer Group Lifecycle

**Issue**: The design does not specify behavior when a consumer group is started with zero members, or when all members are removed/filtered.

**Current Design** (Section: Implementation Details):
- `ConsumerGroup.start(SubscriptionOptions)` registers subscription
- `ConsumerGroup.addConsumer(...)` adds members
- No specification of ordering or validation

**Gap**: What should happen in these scenarios?

**Scenario 1: Start with Zero Members**
```java
ConsumerGroup<OrderEvent> group = queueFactory.createConsumerGroup("analytics", "orders.events", OrderEvent.class);
group.start(SubscriptionOptions.fromNow());  // ⚠️ No members yet!
// Messages arrive...
group.addConsumer("consumer-1", handler);  // Member joins later
```

**Questions**:
- Should `start()` fail if no members exist? (fail-fast)
- Should messages be buffered until first member joins? (complex)
- Should messages be marked as filtered/skipped? (data loss)
- Should subscription registration be deferred until first member? (lazy registration)

**Scenario 2: All Members Removed**
```java
group.removeConsumer("consumer-1");  // Last member removed
// Messages continue arriving...
```

**Questions**:
- Should the group automatically stop polling?
- Should messages be requeued to PENDING?
- Should the subscription be marked PAUSED?

**Scenario 3: All Members Filter Out Message**
```java
// All members have filters that reject a specific message
group.addConsumer("consumer-1", handler, msg -> msg.getPriority() > 5);
group.addConsumer("consumer-2", handler, msg -> msg.getPriority() > 5);
// Message arrives with priority = 3 (rejected by all filters)
```

**Questions**:
- Should this be treated as successful processing? (current TODO in design)
- Should the message be marked FILTERED and count toward completion?
- Should this be an error condition?

**Resolution** (Adopted - Fail-Fast Approach):

Consumer groups **MUST** have at least one member before calling `start()`. This provides clear failure semantics and prevents silent data loss.

**Behavior**:
1. `start()` validates that `members.size() > 0`, throws `IllegalStateException` if empty
2. `removeConsumer()` prevents removal of the last member if the group is started
3. Messages are **never** marked as filtered due to zero members
4. If all member filters reject a message, it is treated as successfully processed (filtered) for that group

**Rationale**: Fail-fast prevents silent data loss and makes the lifecycle explicit. Applications must add at least one member before starting.

See Implementation Details section for validation logic.

---

### Critical Gap 3: Consumer Group Registration Timing

**Issue**: The relationship between consumer group registration, message insertion, and `required_consumer_groups` calculation needs clarification.

**Current Design** (Section: Question 1, lines 219-241):
```sql
CREATE OR REPLACE FUNCTION set_required_consumer_groups()
RETURNS TRIGGER AS $$
BEGIN
    NEW.required_consumer_groups := (
        SELECT COUNT(*)
        FROM outbox_topic_subscriptions
        WHERE topic = NEW.topic
          AND subscription_status = 'ACTIVE'
    );
END;
$$ LANGUAGE plpgsql;
```

**Gap**: Race condition between message insertion and subscription registration.

**Scenario: Late Registration**
```
T0: Producer inserts message to topic "orders.events"
    - Trigger counts ACTIVE subscriptions: 0
    - required_consumer_groups = 0
    - Message immediately eligible for cleanup!

T1: Consumer group registers subscription
    - subscription_status = 'ACTIVE'
    - But message already has required_consumer_groups = 0

T2: Message cleaned up before consumer group can process it
    - Data loss!
```

**Questions**:
- Should topics require at least one subscription before accepting messages?
- Should `required_consumer_groups = 0` be treated specially (never cleanup)?
- Should there be a minimum retention period regardless of completion?
- Should producers fail if no subscriptions exist?

**Resolution** (Adopted - Configurable Minimum Retention + Optional Write Blocking):

Messages with `required_consumer_groups = 0` are protected by **two complementary strategies**:

1. **Minimum Retention Period** (passive protection)
2. **Producer Write Blocking** (active protection, optional)

#### Strategy 1: Configurable Minimum Retention (Default)

Messages with `required_consumer_groups = 0` are retained for a **configurable minimum retention period** before cleanup.

**Schema**:
```sql
ALTER TABLE outbox_topics ADD COLUMN IF NOT EXISTS
    zero_subscription_retention_hours INT DEFAULT 24;  -- Default: 24 hours (not 1 hour!)

-- Per-topic configuration
UPDATE outbox_topics
SET zero_subscription_retention_hours = 168  -- 7 days for critical topics
WHERE topic = 'audit.events';

UPDATE outbox_topics
SET zero_subscription_retention_hours = 1  -- 1 hour for ephemeral topics
WHERE topic = 'metrics.events';
```

**Cleanup Logic**:
```sql
DELETE FROM outbox
WHERE status = 'COMPLETED'
  AND (
      -- Queue semantics: delete after retention
      required_consumer_groups = 1
      OR
      -- Pub/Sub: all groups completed
      (completed_consumer_groups >= required_consumer_groups AND required_consumer_groups > 0)
      OR
      -- Zero subscriptions: use topic-specific minimum retention
      (required_consumer_groups = 0
       AND created_at < NOW() - (
           SELECT COALESCE(zero_subscription_retention_hours, 24) || ' hours'
           FROM outbox_topics
           WHERE topic = outbox.topic
       )::INTERVAL)
  )
LIMIT 10000;
```

**Rationale**:
- **24-hour default** (not 1 hour) handles slow provisioning, weekend deployments, and time zone issues
- **Per-topic configuration** allows critical topics (audit, compliance) to have longer retention (7-30 days)
- **Ephemeral topics** (metrics, logs) can use shorter retention (1 hour)

#### Strategy 2: Producer Write Blocking (Optional, Strict Mode)

For **PUB_SUB topics**, optionally **block producer writes** when zero ACTIVE subscriptions exist, preventing silent data accumulation.

**Schema**:
```sql
ALTER TABLE outbox_topics ADD COLUMN IF NOT EXISTS
    block_writes_on_zero_subscriptions BOOLEAN DEFAULT FALSE;

-- Enable for critical pub/sub topics
UPDATE outbox_topics
SET block_writes_on_zero_subscriptions = TRUE
WHERE topic = 'orders.events'
  AND semantics = 'PUB_SUB';
```

**Producer Logic**:
```java
public Future<Long> send(String topic, T payload) {
    return checkSubscriptions(topic)
        .compose(allowed -> {
            if (!allowed) {
                return Future.failedFuture(new NoSubscriptionsException(
                    "Topic '" + topic + "' has zero ACTIVE subscriptions and blocks writes"));
            }
            return insertMessage(topic, payload);
        });
}

private Future<Boolean> checkSubscriptions(String topic) {
    String sql = """
        SELECT t.block_writes_on_zero_subscriptions,
               COUNT(s.id) AS active_subscriptions
        FROM outbox_topics t
        LEFT JOIN outbox_topic_subscriptions s
            ON s.topic = t.topic AND s.subscription_status = 'ACTIVE'
        WHERE t.topic = $1
        GROUP BY t.topic, t.block_writes_on_zero_subscriptions
        """;

    return pool.preparedQuery(sql)
        .execute(Tuple.of(topic))
        .map(rows -> {
            if (rows.size() == 0) {
                // Topic not configured, allow write
                return true;
            }

            Row row = rows.iterator().next();
            boolean blockOnZero = row.getBoolean("block_writes_on_zero_subscriptions");
            int activeCount = row.getInteger("active_subscriptions");

            if (blockOnZero && activeCount == 0) {
                logger.warn("Blocking write to topic '{}' - zero ACTIVE subscriptions", topic);
                return false;
            }

            return true;
        });
}
```

**Behavior**:
- ✅ **QUEUE topics**: Never block (messages can be consumed later)
- ✅ **PUB_SUB topics with flag disabled**: Allow writes, rely on minimum retention
- ❌ **PUB_SUB topics with flag enabled**: Reject writes when zero ACTIVE subscriptions

**Use Cases**:
- **Enable blocking** for: Critical events that MUST be consumed (orders, payments, audit)
- **Disable blocking** for: Optional events, metrics, logs, or topics with intermittent consumers

**Error Handling**:
```java
try {
    producer.send("orders.events", orderEvent).toCompletionStage().toCompletableFuture().join();
} catch (CompletionException e) {
    if (e.getCause() instanceof NoSubscriptionsException) {
        // Handle: retry later, alert ops, fail transaction, etc.
        logger.error("Cannot publish order event - no active consumers!", e);
        throw new ServiceUnavailableException("Order processing unavailable");
    }
}
```

#### Configuration Best Practices

| Topic Type | Minimum Retention | Block Writes | Rationale |
|------------|------------------|--------------|-----------|
| **Critical PUB_SUB** (orders, payments) | 7-30 days | ✅ Enabled | Prevent silent data loss |
| **Audit/Compliance** | 90-365 days | ✅ Enabled | Regulatory requirements |
| **Standard PUB_SUB** (notifications) | 24 hours | ❌ Disabled | Balance safety and flexibility |
| **Ephemeral** (metrics, logs) | 1 hour | ❌ Disabled | Short-lived data |
| **QUEUE** (any) | N/A | ❌ Never block | Consumers can join later |

See Question 1 for subscription registration details and cleanup job implementation.

---

### Major Gap 4: Hash-Based Consumer Selection Edge Case

**Issue**: The consumer selection algorithm has a subtle bug with hash collisions.

**Current Design** (Section: Implementation Details):
```java
private OutboxConsumerGroupMember<T> selectConsumer(List<OutboxConsumerGroupMember<T>> eligibleConsumers,
                                                   Message<T> message) {
    int index = Math.abs(message.getId().hashCode()) % eligibleConsumers.size();
    return eligibleConsumers.get(index);
}
```

**Gap**: `Math.abs(Integer.MIN_VALUE)` returns `Integer.MIN_VALUE` (still negative).

**Scenario**:
```java
String messageId = "some-id";  // hashCode() returns Integer.MIN_VALUE
int hash = messageId.hashCode();  // -2147483648
int absHash = Math.abs(hash);     // -2147483648 (unchanged!)
int index = absHash % 3;          // -2 (negative!)
// IndexOutOfBoundsException!
```

**Resolution** (Adopted):
```java
private OutboxConsumerGroupMember<T> selectConsumer(List<OutboxConsumerGroupMember<T>> eligibleConsumers,
                                                   Message<T> message) {
    // Use bitwise AND to ensure non-negative value (handles Integer.MIN_VALUE edge case)
    int index = (message.getId().hashCode() & Integer.MAX_VALUE) % eligibleConsumers.size();
    return eligibleConsumers.get(index);
}
```

This fix is incorporated in the Implementation Details section.

---

### Open Question 1: Subscription Lifecycle State Machine

**Issue**: The design specifies subscription statuses (ACTIVE, PAUSED, CANCELLED, DEAD) but doesn't define valid state transitions.

**Current Design** (Section: Question 1, lines 112-113):
```sql
subscription_status VARCHAR(20) DEFAULT 'ACTIVE'
    CHECK (subscription_status IN ('ACTIVE', 'PAUSED', 'CANCELLED', 'DEAD'))
```

**Questions**:
- Can CANCELLED subscriptions be reactivated?
- Can DEAD subscriptions be resurrected?
- What's the difference between PAUSED and DEAD?
- Can ACTIVE subscriptions be manually set to PAUSED?

**Resolution** (Adopted State Machine):
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
                                                          │
  (cleanup job after retention period)                    │
  └──────────────> DELETED ──────────────────────────────┘
```

**Valid Transitions**:
- `ACTIVE → PAUSED`: Manual pause via API call
- `PAUSED → ACTIVE`: Manual resume via API call
- `ACTIVE → DEAD`: Heartbeat timeout (automatic)
- `DEAD → ACTIVE`: Heartbeat received (automatic resurrection)
- `ACTIVE → CANCELLED`: Graceful shutdown or manual cancellation
- `PAUSED → CANCELLED`: Manual cancellation while paused
- `DEAD → CANCELLED`: Manual cleanup of dead subscription
- `CANCELLED → DELETED`: Cleanup job after retention period (physical deletion)

**Invalid Transitions**:
- `CANCELLED → ACTIVE`: Cannot reactivate cancelled subscriptions (must create new subscription)
- `CANCELLED → PAUSED`: Terminal state
- `DELETED → *`: Physical deletion is irreversible

See Question 1 for implementation details and Dead Consumer Group Cleanup section for heartbeat logic.

---

### Open Question 2: Backfill Performance and Limits

**Issue**: The backfill operation for late-joining consumers could impact performance on large topics.

**Current Design** (Section: Question 3, lines 347-367):
```java
private Future<Void> backfillRequiredConsumerGroups() {
    String sql = """
        UPDATE outbox
        SET required_consumer_groups = required_consumer_groups + 1
        WHERE topic = $1
          AND status IN ('PENDING', 'PROCESSING')
          AND id >= $2
        """;
}
```

**Gap**: No limits or batching specified.

**Scenario**:
```
Topic "orders.events" has 10 million pending messages
New consumer group joins with SubscriptionOptions.fromBeginning(true)
Backfill updates 10 million rows in single transaction
→ Lock contention, timeout, or OOM
```

**Questions**:
- Should backfill be batched? (e.g., 10,000 rows at a time)
- Should there be a maximum backfill limit? (e.g., last 1 million messages)
- Should backfill be asynchronous with progress tracking?
- Should backfill be optional with explicit confirmation for large datasets?

**Resolution** (Adopted - Batched Backfill with Limits):

Backfill operations are **batched** to prevent lock contention and timeouts on large topics.

**Configuration**:
```java
public class SubscriptionOptions {
    private final long maxBackfillMessages;  // Default: 1,000,000
    private final int backfillBatchSize;     // Default: 10,000
    private final boolean asyncBackfill;     // Default: true for > 100k messages

    public static SubscriptionOptions fromBeginning(boolean backfill, long maxMessages) {
        return new SubscriptionOptions(SubscriptionPosition.EARLIEST, null, null, backfill, maxMessages, 10_000, true);
    }

    public static SubscriptionOptions fromBeginningUnlimited() {
        return new SubscriptionOptions(SubscriptionPosition.EARLIEST, null, null, true, Long.MAX_VALUE, 10_000, true);
    }
}
```

**Batching Strategy**:
1. Backfill updates are processed in batches of 10,000 messages (configurable)
2. Maximum backfill limit: 1,000,000 messages by default (configurable, can be unlimited)
3. Async backfill for > 100,000 messages (runs in background, returns immediately)
4. Sync backfill for ≤ 100,000 messages (blocks until complete)

**Implementation**:
```java
private Future<Void> backfillRequiredConsumerGroups(SubscriptionOptions options) {
    if (options.isAsyncBackfill()) {
        // Start background job and return immediately
        vertx.executeBlocking(promise -> {
            performBatchedBackfill(options).onComplete(promise);
        });
        return Future.succeededFuture();
    } else {
        // Block until complete
        return performBatchedBackfill(options);
    }
}

private Future<Void> performBatchedBackfill(SubscriptionOptions options) {
    String sql = """
        UPDATE outbox
        SET required_consumer_groups = required_consumer_groups + 1
        WHERE id IN (
            SELECT id FROM outbox
            WHERE topic = $1
              AND status IN ('PENDING', 'PROCESSING')
              AND id >= $2
              AND NOT EXISTS (
                  SELECT 1 FROM outbox_consumer_groups cg
                  WHERE cg.message_id = outbox.id AND cg.group_name = $3
              )
            ORDER BY id ASC
            LIMIT $4
        )
        RETURNING id
        """;

    return pool.preparedQuery(sql)
        .execute(Tuple.of(topic, startMessageId, groupName, options.getBackfillBatchSize()))
        .compose(rows -> {
            if (rows.rowCount() == 0) {
                // No more messages to backfill
                return Future.succeededFuture();
            } else if (rows.rowCount() < options.getBackfillBatchSize()) {
                // Last batch
                logger.info("Backfill complete for group '{}': {} messages", groupName, rows.rowCount());
                return Future.succeededFuture();
            } else {
                // More batches to process
                Long lastId = rows.iterator().next().getLong("id");
                return performBatchedBackfill(options.withStartMessageId(lastId + 1));
            }
        });
}
```

See Question 3 for complete backfill implementation.

---

### Summary of Design Decisions

All design gaps have been resolved. The following decisions have been adopted:

| Gap | Severity | Resolution | Documented In |
|-----|----------|-----------|---------------|
| Message Completion Logic | Critical | ✅ Immutable `required_consumer_groups` with snapshot semantics | Question 2 |
| Zero-Member Lifecycle | Critical | ✅ Fail-fast: `start()` requires at least one member | Implementation Details |
| Registration Timing | Critical | ✅ Minimum retention period (1 hour) for zero-subscription messages | Question 1 |
| Hash Collision Bug | Major | ✅ Safe hash calculation using bitwise AND | Implementation Details |
| Subscription State Machine | Resolved | ✅ Defined state transitions with CANCELLED as terminal state | Question 1 |
| Backfill Limits | Resolved | ✅ Batched backfill (10k batch size, 1M default limit) | Question 3 |

---

## Completion Tracking Modes

### Overview

The fan-out design supports **two distinct completion tracking modes** optimized for different fanout scales. The choice of mode has significant implications for write amplification, cleanup performance, and scalability.

| Mode | Fanout Scale | Write Amplification | Cleanup Strategy | Max Groups | Recommended For |
|------|--------------|---------------------|------------------|------------|-----------------|
| **Reference Counting** | Low (≤8-16 groups) | High (N updates per message) | Row-level DELETE | 64 (bitmap limit) | Strict all-ack semantics, small fanout |
| **Offset/Watermark** | High (>16 groups) | Low (1 update per group) | Partition DROP | Unlimited | High fanout, high throughput |

**Critical Decision Point**: Choose the mode based on your **maximum expected fanout** per topic, not current fanout. Migration between modes requires schema changes and downtime.

---

### Mode 1: Reference Counting (Bitmap/Counter)

**Use When**: ≤8-16 consumer groups per topic, strict "all groups must ack" semantics required.

#### How It Works

Each message tracks completion using either:
- **Counter**: `completed_consumer_groups INT` (simple, unlimited groups)
- **Bitmap**: `completed_groups_bitmap BIGINT` (efficient, max 64 groups)

Every consumer group acknowledgment **updates the same message row**:
```sql
UPDATE outbox
SET completed_consumer_groups = completed_consumer_groups + 1
WHERE id = $1
```

**Write Amplification**: With N consumer groups and M messages/second:
- **Total updates/second**: N × M
- **Example**: 16 groups × 10,000 msg/s = **160,000 row updates/second**

#### Schema (Reference Counting Mode)

```sql
-- Outbox table with reference counting
CREATE TABLE outbox (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    processed_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(50) DEFAULT 'PENDING',

    -- Reference counting columns
    required_consumer_groups INT DEFAULT 0,
    completed_consumer_groups INT DEFAULT 0,

    -- Optional: bitmap for ≤64 groups (more efficient)
    completed_groups_bitmap BIGINT DEFAULT 0,

    version INT DEFAULT 0,
    headers JSONB DEFAULT '{}'
);

-- Consumer group acknowledgments (dedupe table)
CREATE TABLE outbox_consumer_groups (
    id BIGSERIAL PRIMARY KEY,
    message_id BIGINT NOT NULL REFERENCES outbox(id) ON DELETE CASCADE,
    group_name VARCHAR(255) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(message_id, group_name)
);

-- Indexes
CREATE INDEX idx_outbox_completion_tracking
    ON outbox(topic, status, completed_consumer_groups, required_consumer_groups)
    WHERE status IN ('PENDING', 'PROCESSING');

CREATE INDEX idx_outbox_cleanup
    ON outbox(status, processed_at)
    WHERE status = 'COMPLETED';
```

#### Completion Logic (Reference Counting)

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
            SET completed_consumer_groups = completed_consumer_groups + 1,
                version = version + 1
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

#### Cleanup (Reference Counting)

```sql
-- Delete completed messages past retention
DELETE FROM outbox
WHERE status = 'COMPLETED'
  AND processed_at < NOW() - INTERVAL '24 hours'
  AND completed_consumer_groups >= required_consumer_groups
LIMIT 10000;
```

**Performance**: 10,000-30,000 deletes/second (generates WAL, requires VACUUM)

#### Pros & Cons

**Pros**:
- ✅ Simple to understand and implement
- ✅ Strict "all groups must ack" semantics
- ✅ Works with existing schema (minimal changes)
- ✅ Supports up to 64 groups efficiently with bitmap

**Cons**:
- ❌ **High write amplification** (N updates per message)
- ❌ **Hot row contention** (all groups update same row)
- ❌ **MVCC bloat** (frequent updates cause table bloat)
- ❌ **Cleanup generates WAL** (DELETE operations are expensive)
- ❌ **Limited scalability** (>16 groups causes performance degradation)

---

### Mode 2: Offset/Watermark

**Use When**: >16 consumer groups per topic, high throughput (>10,000 msg/s), unbounded fanout.

#### How It Works

Each consumer group maintains a **cursor (offset)** tracking the last processed message ID. Messages are deleted when **all active subscriptions** have advanced past them (watermark).

**Write Amplification**: With N consumer groups and M messages/second:
- **Total updates/second**: N (one per group, not per message)
- **Example**: 100 groups × 1 update/batch = **100 updates/second** (vs 1,000,000 in reference counting!)

**Key Insight**: Cleanup is **O(#subscriptions)** not **O(#subscriptions × #messages)**.

#### Schema (Offset/Watermark Mode)

```sql
-- Outbox table (simplified - no completion tracking)
CREATE TABLE outbox (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    status VARCHAR(50) DEFAULT 'PENDING',
    headers JSONB DEFAULT '{}',

    -- Partition key for time-based partitioning
    partition_key TIMESTAMP WITH TIME ZONE DEFAULT NOW()
) PARTITION BY RANGE (partition_key);

-- Create partitions (example: daily)
CREATE TABLE outbox_2025_11_12 PARTITION OF outbox
    FOR VALUES FROM ('2025-11-12 00:00:00+00') TO ('2025-11-13 00:00:00+00');

-- Subscription offsets (one row per consumer group)
CREATE TABLE outbox_subscription_offsets (
    id BIGSERIAL PRIMARY KEY,
    subscription_id BIGINT NOT NULL REFERENCES outbox_topic_subscriptions(id) ON DELETE CASCADE,
    topic VARCHAR(255) NOT NULL,
    group_name VARCHAR(255) NOT NULL,

    -- Offset tracking
    last_processed_id BIGINT NOT NULL DEFAULT 0,
    last_processed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

    -- Checkpoint for resumable processing
    checkpoint_id BIGINT,
    checkpoint_at TIMESTAMP WITH TIME ZONE,

    -- Version for optimistic locking
    version INT DEFAULT 0,

    UNIQUE(topic, group_name)
);

-- Watermark tracking (per topic)
CREATE TABLE outbox_topic_watermarks (
    topic VARCHAR(255) PRIMARY KEY,
    watermark_id BIGINT NOT NULL DEFAULT 0,  -- min(last_processed_id) across ACTIVE subs
    watermark_updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    oldest_partition TIMESTAMP WITH TIME ZONE,
    latest_partition TIMESTAMP WITH TIME ZONE
);

-- Indexes
CREATE INDEX idx_subscription_offsets_topic
    ON outbox_subscription_offsets(topic, last_processed_id);

CREATE INDEX idx_outbox_topic_id
    ON outbox(topic, id)
    WHERE status = 'PENDING';
```

#### Completion Logic (Offset/Watermark)

```java
private Future<Void> markMessageProcessedByGroup(Long messageId, String groupName) {
    // No per-message update! Just track in-memory and periodically flush offset

    // Update offset every N messages or every T seconds
    if (shouldFlushOffset()) {
        return flushOffset(messageId, groupName);
    }

    return Future.succeededFuture();
}

private Future<Void> flushOffset(Long messageId, String groupName) {
    String sql = """
        UPDATE outbox_subscription_offsets
        SET last_processed_id = GREATEST(last_processed_id, $1),
            last_processed_at = NOW(),
            version = version + 1
        WHERE topic = $2
          AND group_name = $3
        RETURNING last_processed_id
        """;

    return pool.preparedQuery(sql)
        .execute(Tuple.of(messageId, topic, groupName))
        .compose(rows -> {
            if (rows.size() > 0) {
                logger.debug("Flushed offset for group '{}': {}", groupName, messageId);
            }
            return Future.succeededFuture();
        })
        .compose(v -> updateTopicWatermark());
}

private Future<Void> updateTopicWatermark() {
    String sql = """
        UPDATE outbox_topic_watermarks
        SET watermark_id = (
            SELECT COALESCE(MIN(last_processed_id), 0)
            FROM outbox_subscription_offsets o
            INNER JOIN outbox_topic_subscriptions s
                ON s.topic = o.topic AND s.group_name = o.group_name
            WHERE o.topic = $1
              AND s.subscription_status = 'ACTIVE'
        ),
        watermark_updated_at = NOW()
        WHERE topic = $1
        RETURNING watermark_id
        """;

    return pool.preparedQuery(sql)
        .execute(Tuple.of(topic))
        .mapEmpty();
}
```

#### Cleanup (Offset/Watermark)

**Strategy**: Drop entire partitions below the watermark instead of row-level DELETEs.

```java
public class WatermarkCleanupJob {

    private final PgPool pool;

    public Future<Void> runCleanup() {
        return getTopicWatermarks()
            .compose(this::dropOldPartitions);
    }

    private Future<List<TopicWatermark>> getTopicWatermarks() {
        String sql = """
            SELECT w.topic, w.watermark_id, w.oldest_partition, w.latest_partition,
                   t.message_retention_hours
            FROM outbox_topic_watermarks w
            INNER JOIN outbox_topics t ON t.topic = w.topic
            WHERE w.watermark_id > 0
            """;

        return pool.preparedQuery(sql)
            .execute()
            .map(rows -> {
                List<TopicWatermark> watermarks = new ArrayList<>();
                for (Row row : rows) {
                    watermarks.add(new TopicWatermark(
                        row.getString("topic"),
                        row.getLong("watermark_id"),
                        row.getLocalDateTime("oldest_partition"),
                        row.getInteger("message_retention_hours")
                    ));
                }
                return watermarks;
            });
    }

    private Future<Void> dropOldPartitions(List<TopicWatermark> watermarks) {
        List<Future<Void>> dropFutures = new ArrayList<>();

        for (TopicWatermark wm : watermarks) {
            LocalDateTime cutoff = LocalDateTime.now()
                .minusHours(wm.retentionHours);

            if (wm.oldestPartition.isBefore(cutoff)) {
                dropFutures.add(dropPartition(wm.topic, wm.oldestPartition));
            }
        }

        return Future.all(dropFutures).mapEmpty();
    }

    private Future<Void> dropPartition(String topic, LocalDateTime partitionDate) {
        String partitionName = String.format("outbox_%s",
            partitionDate.format(DateTimeFormatter.ofPattern("yyyy_MM_dd")));

        // Safety check: ensure watermark has advanced past this partition
        String checkSql = """
            SELECT watermark_id
            FROM outbox_topic_watermarks
            WHERE topic = $1
              AND watermark_id > (
                  SELECT COALESCE(MAX(id), 0)
                  FROM """ + partitionName + """
              )
            """;

        return pool.preparedQuery(checkSql)
            .execute(Tuple.of(topic))
            .compose(rows -> {
                if (rows.size() == 0) {
                    logger.warn("Skipping partition drop - watermark has not advanced past partition: {}",
                        partitionName);
                    return Future.succeededFuture();
                }

                // Safe to drop
                String dropSql = "DROP TABLE IF EXISTS " + partitionName;
                return pool.query(dropSql).execute().mapEmpty();
            })
            .onSuccess(v -> logger.info("Dropped partition: {}", partitionName))
            .onFailure(ex -> logger.error("Failed to drop partition: {}", partitionName, ex));
    }
}
```

**Performance**: Dropping a partition is **O(1)** - instant, no WAL, no VACUUM needed.

**Comparison**:
```
Reference Counting Mode:
  DELETE 10,000,000 messages: 5-10 minutes, generates 10GB WAL, requires VACUUM

Offset/Watermark Mode:
  DROP PARTITION: <1 second, no WAL, no VACUUM
```

#### Zombie Subscription Protection

**Problem**: A DEAD subscription with a stale offset pins all data forever.

**Solution**: Exclude DEAD subscriptions from watermark calculation and force-advance after TTL.

```sql
-- Watermark calculation excludes DEAD subscriptions
UPDATE outbox_topic_watermarks
SET watermark_id = (
    SELECT COALESCE(MIN(last_processed_id), 0)
    FROM outbox_subscription_offsets o
    INNER JOIN outbox_topic_subscriptions s
        ON s.topic = o.topic AND s.group_name = o.group_name
    WHERE o.topic = $1
      AND s.subscription_status = 'ACTIVE'  -- ✅ Only ACTIVE subscriptions
)
WHERE topic = $1;

-- Force-advance stale offsets (zombie protection)
UPDATE outbox_subscription_offsets
SET last_processed_id = (
    SELECT watermark_id
    FROM outbox_topic_watermarks
    WHERE topic = outbox_subscription_offsets.topic
)
WHERE last_processed_at < NOW() - INTERVAL '7 days'  -- Configurable TTL
  AND last_processed_id < (
      SELECT watermark_id
      FROM outbox_topic_watermarks
      WHERE topic = outbox_subscription_offsets.topic
  );
```

#### Forced Retention Cap

**Problem**: Even with watermark cleanup, a topic with zero ACTIVE subscriptions will never advance the watermark.

**Solution**: Enforce a **maximum retention period** regardless of subscription state.

```sql
-- Drop partitions older than max retention (default: 30 days)
DROP TABLE IF EXISTS outbox_2025_10_01  -- Older than 30 days
WHERE NOT EXISTS (
    SELECT 1 FROM outbox_topic_watermarks w
    WHERE w.oldest_partition > '2025-10-01'
);
```

**Configuration**:
```java
public class TopicConfiguration {
    private final Duration messageRetention;      // Default: 24 hours
    private final Duration maxRetention;          // Default: 30 days (forced cap)
    private final Duration zombieSubscriptionTTL; // Default: 7 days
}
```

#### Pros & Cons

**Pros**:
- ✅ **Minimal write amplification** (1 update per group per batch, not per message)
- ✅ **No hot row contention** (each group updates its own offset row)
- ✅ **Instant cleanup** (DROP PARTITION vs DELETE)
- ✅ **No VACUUM overhead** (partitions are dropped, not deleted)
- ✅ **Unlimited fanout** (no 64-group bitmap limit)
- ✅ **Better scalability** (O(#subs) not O(#subs×#msgs))

**Cons**:
- ❌ **More complex** (requires partitioning, watermark management)
- ❌ **Eventual consistency** (offset updates are batched/delayed)
- ❌ **Partition management overhead** (create/drop partitions)
- ❌ **Requires PostgreSQL 10+** (declarative partitioning)
- ❌ **Looser semantics** (messages deleted when watermark advances, not when all groups ack)

---

### Mode Selection Guide

#### Decision Matrix

| Requirement | Reference Counting | Offset/Watermark |
|-------------|-------------------|------------------|
| Fanout ≤ 8 groups | ✅ Recommended | ⚠️ Over-engineered |
| Fanout 8-16 groups | ⚠️ Acceptable | ✅ Recommended |
| Fanout > 16 groups | ❌ Not scalable | ✅ Required |
| Throughput < 1,000 msg/s | ✅ Simple | ⚠️ Over-engineered |
| Throughput 1,000-10,000 msg/s | ⚠️ Monitor bloat | ✅ Recommended |
| Throughput > 10,000 msg/s | ❌ Will cause bloat | ✅ Required |
| Strict all-ack semantics | ✅ Guaranteed | ⚠️ Watermark-based |
| Audit compliance | ✅ Per-message tracking | ⚠️ Offset-based |
| Operational simplicity | ✅ Simple | ❌ Complex |

#### Load Test Thresholds

**Before choosing Reference Counting**, verify:
```
Load test: N consumer groups × M msg/s for 1 hour
Measure:
  - Table bloat (pg_stat_user_tables.n_dead_tup)
  - WAL generation rate (pg_stat_wal)
  - Cleanup throughput (messages deleted per second)
  - Query latency p95 (consumer fetch queries)

Acceptable thresholds:
  - Table bloat < 20% of table size
  - WAL rate < 50% of disk I/O capacity
  - Cleanup rate > production rate
  - Query latency p95 < 10ms
```

**If any threshold is exceeded**, migrate to Offset/Watermark mode.

---

### Migration Between Modes

#### Reference Counting → Offset/Watermark

**Scenario**: You started with 4 consumer groups, now have 20, experiencing write amplification and bloat.

**Migration Steps**:

1. **Create new schema** (offset tables, partitions)
2. **Dual-write period**: Write to both old and new tables
3. **Backfill offsets**: Initialize offsets from current `completed_consumer_groups`
4. **Switch reads**: Consumer groups read from new offset-based system
5. **Cleanup old data**: Drop old reference counting columns

**Downtime**: ~5-10 minutes (during step 4)

**Detailed Migration**:
```sql
-- Step 1: Create new schema
CREATE TABLE outbox_subscription_offsets (...);  -- As defined above
CREATE TABLE outbox_topic_watermarks (...);

-- Step 2: Backfill offsets from current state
INSERT INTO outbox_subscription_offsets (subscription_id, topic, group_name, last_processed_id)
SELECT s.id, s.topic, s.group_name,
       COALESCE(
           (SELECT MAX(message_id)
            FROM outbox_consumer_groups cg
            WHERE cg.group_name = s.group_name),
           0
       ) AS last_processed_id
FROM outbox_topic_subscriptions s
WHERE s.subscription_status = 'ACTIVE';

-- Step 3: Initialize watermarks
INSERT INTO outbox_topic_watermarks (topic, watermark_id)
SELECT topic, MIN(last_processed_id)
FROM outbox_subscription_offsets
GROUP BY topic;

-- Step 4: Create partitions (manual or automated)
-- ... partition creation logic ...

-- Step 5: Switch application to offset mode (deploy new code)

-- Step 6: After verification, drop old columns
ALTER TABLE outbox DROP COLUMN required_consumer_groups;
ALTER TABLE outbox DROP COLUMN completed_consumer_groups;
DROP TABLE outbox_consumer_groups;
```

#### Offset/Watermark → Reference Counting

**Scenario**: Rare, but possible if fanout decreases significantly or strict all-ack semantics are required.

**Migration Steps**: Reverse of above, with backfill from offsets to reference counts.

---

## Topic Semantics

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

#### Subscription Lifecycle State Machine

Subscriptions follow a defined state machine (see Open Question 1 for details):

**Valid State Transitions**:
- `ACTIVE → PAUSED`: Manual pause via API
- `PAUSED → ACTIVE`: Manual resume via API
- `ACTIVE → DEAD`: Heartbeat timeout (automatic)
- `DEAD → ACTIVE`: Heartbeat received (automatic resurrection)
- `ACTIVE/PAUSED/DEAD → CANCELLED`: Manual cancellation or graceful shutdown
- `CANCELLED → DELETED`: Cleanup job after retention period

**API for State Management**:
```java
public interface ConsumerGroup<T> {
    void start(SubscriptionOptions options);           // Sets status to ACTIVE
    void pause();                                       // ACTIVE → PAUSED
    void resume();                                      // PAUSED → ACTIVE
    void cancel();                                      // * → CANCELLED (graceful shutdown)
}

// Internal heartbeat mechanism
private void updateHeartbeat() {
    String sql = """
        UPDATE outbox_topic_subscriptions
        SET last_heartbeat_at = NOW(),
            subscription_status = CASE
                WHEN subscription_status = 'DEAD' THEN 'ACTIVE'  -- Resurrection
                ELSE subscription_status
            END
        WHERE topic = $1 AND group_name = $2
        """;

    pool.preparedQuery(sql).execute(Tuple.of(topic, groupName));
}

// Dead consumer detection (background job)
private void markDeadSubscriptions() {
    String sql = """
        UPDATE outbox_topic_subscriptions
        SET subscription_status = 'DEAD'
        WHERE subscription_status = 'ACTIVE'
          AND last_heartbeat_at < NOW() - (heartbeat_timeout_seconds || ' seconds')::INTERVAL
        """;

    pool.preparedQuery(sql).execute();
}
```

---

### ⚠️ Question 2: Message Retention

**Problem**: Pub/sub messages can't be deleted until ALL consumer groups process them.

**Solution**: Reference counting with automatic cleanup using **immutable snapshot semantics**.

#### Design Decision: Immutable `required_consumer_groups`

The `required_consumer_groups` value is set at message insertion time and represents a **snapshot** of active subscriptions at that moment. This value is immutable for COMPLETED messages but can be adjusted for PENDING/PROCESSING messages in specific scenarios:

1. **Message Insertion**: Trigger sets `required_consumer_groups` based on active subscriptions
2. **Dead Consumer Cleanup**: Decrements `required_consumer_groups` for PENDING/PROCESSING messages only
3. **Late-Joining Backfill**: Increments `required_consumer_groups` for PENDING/PROCESSING messages only
4. **Completed Messages**: Never modified (idempotent completion)

This approach prevents race conditions and provides clear completion semantics.

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

The cleanup job handles the minimum retention period for messages with zero subscriptions (see Critical Gap 3).

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
                  required_consumer_groups = 1  -- Queue: delete immediately after retention
                  OR
                  (completed_consumer_groups >= required_consumer_groups AND required_consumer_groups > 0)  -- Pub/Sub: all groups done
                  OR
                  (required_consumer_groups = 0 AND created_at < NOW() - INTERVAL '1 hour')  -- Zero subscriptions: minimum 1-hour retention
              )
            RETURNING id
            """;

        return pool.preparedQuery(sql)
            .execute()
            .map(rows -> rows.size());
    }
}
```

**Note**: Messages with `required_consumer_groups = 0` are retained for a minimum of 1 hour to allow late-joining consumer groups to register without data loss.

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

#### Backfill Implementation (Resumable with Adaptive Rate Limiting)

Backfill operations are **batched, resumable, and adaptive** to prevent lock contention, allow cancellation, and protect OLTP workloads (see Open Question 2 for details).

**Key Features**:
- ✅ **Persistent checkpoints**: Resume from last processed ID if process crashes
- ✅ **Cancellation API**: Stop backfill mid-flight without data corruption
- ✅ **Adaptive rate limiting**: Throttle based on database latency (p95 < target)
- ✅ **Progress tracking**: Observable metrics for monitoring
- ✅ **Bounded impact**: Configurable max batch size and rate

**Schema for Checkpoints**:
```sql
-- Add checkpoint columns to outbox_topic_subscriptions
ALTER TABLE outbox_topic_subscriptions ADD COLUMN IF NOT EXISTS
    backfill_status VARCHAR(20) DEFAULT 'NOT_STARTED'
        CHECK (backfill_status IN ('NOT_STARTED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'FAILED'));

ALTER TABLE outbox_topic_subscriptions ADD COLUMN IF NOT EXISTS
    backfill_checkpoint_id BIGINT DEFAULT 0;

ALTER TABLE outbox_topic_subscriptions ADD COLUMN IF NOT EXISTS
    backfill_total_messages BIGINT DEFAULT 0;

ALTER TABLE outbox_topic_subscriptions ADD COLUMN IF NOT EXISTS
    backfill_processed_messages BIGINT DEFAULT 0;

ALTER TABLE outbox_topic_subscriptions ADD COLUMN IF NOT EXISTS
    backfill_started_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE outbox_topic_subscriptions ADD COLUMN IF NOT EXISTS
    backfill_completed_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE outbox_topic_subscriptions ADD COLUMN IF NOT EXISTS
    backfill_error_message TEXT;

-- Index for backfill monitoring
CREATE INDEX idx_topic_subscriptions_backfill
    ON outbox_topic_subscriptions(backfill_status, backfill_started_at)
    WHERE backfill_status IN ('IN_PROGRESS', 'FAILED');
```

**Configuration**:
```java
public class BackfillConfiguration {
    private final int batchSize;                    // Default: 10,000
    private final long maxMessages;                 // Default: 1,000,000
    private final Duration targetLatency;           // Default: 10ms (p95)
    private final int maxRatePerSecond;             // Default: 50,000 messages/sec
    private final Duration checkpointInterval;      // Default: Every 100,000 messages or 30 seconds
    private final boolean resumable;                // Default: true

    public static BackfillConfiguration conservative() {
        return new BackfillConfiguration(
            5_000,                          // Small batches
            Duration.ofMillis(5),           // Strict latency budget
            10_000,                         // Low rate limit
            Duration.ofSeconds(10),         // Frequent checkpoints
            true
        );
    }

    public static BackfillConfiguration aggressive() {
        return new BackfillConfiguration(
            50_000,                         // Large batches
            Duration.ofMillis(50),          // Relaxed latency budget
            200_000,                        // High rate limit
            Duration.ofMinutes(5),          // Infrequent checkpoints
            true
        );
    }
}
```

**Implementation**:
```java
public class ResumableBackfillJob {

    private final PgPool pool;
    private final String topic;
    private final String groupName;
    private final BackfillConfiguration config;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicLong processedCount = new AtomicLong(0);
    private final Deque<Long> latencySamples = new ConcurrentLinkedDeque<>();

    /**
     * Start or resume backfill operation.
     */
    public Future<BackfillResult> start() {
        return loadCheckpoint()
            .compose(checkpoint -> {
                if (checkpoint.status == BackfillStatus.COMPLETED) {
                    logger.info("Backfill already completed for group '{}'", groupName);
                    return Future.succeededFuture(BackfillResult.alreadyCompleted());
                }

                if (checkpoint.status == BackfillStatus.IN_PROGRESS) {
                    logger.info("Resuming backfill for group '{}' from checkpoint: {}",
                        groupName, checkpoint.checkpointId);
                }

                return markBackfillStarted(checkpoint.checkpointId)
                    .compose(v -> performBackfill(checkpoint.checkpointId, checkpoint.processedCount));
            });
    }

    /**
     * Cancel backfill operation (safe mid-flight).
     */
    public Future<Void> cancel() {
        cancelled.set(true);
        logger.info("Backfill cancellation requested for group '{}'", groupName);
        return markBackfillCancelled();
    }

    /**
     * Load checkpoint from database.
     */
    private Future<BackfillCheckpoint> loadCheckpoint() {
        String sql = """
            SELECT backfill_status, backfill_checkpoint_id, backfill_processed_messages
            FROM outbox_topic_subscriptions
            WHERE topic = $1 AND group_name = $2
            """;

        return pool.preparedQuery(sql)
            .execute(Tuple.of(topic, groupName))
            .map(rows -> {
                if (rows.size() == 0) {
                    return new BackfillCheckpoint(BackfillStatus.NOT_STARTED, 0, 0);
                }

                Row row = rows.iterator().next();
                return new BackfillCheckpoint(
                    BackfillStatus.valueOf(row.getString("backfill_status")),
                    row.getLong("backfill_checkpoint_id"),
                    row.getLong("backfill_processed_messages")
                );
            });
    }

    /**
     * Mark backfill as started.
     */
    private Future<Void> markBackfillStarted(long checkpointId) {
        String sql = """
            UPDATE outbox_topic_subscriptions
            SET backfill_status = 'IN_PROGRESS',
                backfill_checkpoint_id = $1,
                backfill_started_at = COALESCE(backfill_started_at, NOW())
            WHERE topic = $2 AND group_name = $3
            """;

        return pool.preparedQuery(sql)
            .execute(Tuple.of(checkpointId, topic, groupName))
            .mapEmpty();
    }

    /**
     * Perform batched backfill with adaptive rate limiting.
     */
    private Future<BackfillResult> performBackfill(long startId, long initialProcessedCount) {
        processedCount.set(initialProcessedCount);
        return performBatch(startId);
    }

    private Future<BackfillResult> performBatch(long currentId) {
        // Check cancellation
        if (cancelled.get()) {
            logger.info("Backfill cancelled for group '{}' at checkpoint: {}", groupName, currentId);
            return saveCheckpoint(currentId, "Cancelled by user")
                .map(v -> BackfillResult.cancelled(processedCount.get()));
        }

        // Check limit
        if (processedCount.get() >= config.getMaxMessages()) {
            logger.info("Backfill limit reached for group '{}': {} messages", groupName, processedCount.get());
            return markBackfillCompleted()
                .map(v -> BackfillResult.limitReached(processedCount.get()));
        }

        // Adaptive rate limiting: check if we need to throttle
        return checkAndThrottle()
            .compose(v -> executeBatch(currentId))
            .compose(batchResult -> {
                processedCount.addAndGet(batchResult.rowsAffected);

                // Save checkpoint periodically
                if (shouldSaveCheckpoint()) {
                    return saveCheckpoint(batchResult.lastId, null)
                        .compose(v2 -> {
                            if (batchResult.hasMore) {
                                return performBatch(batchResult.lastId + 1);
                            } else {
                                return markBackfillCompleted()
                                    .map(v3 -> BackfillResult.completed(processedCount.get()));
                            }
                        });
                } else {
                    if (batchResult.hasMore) {
                        return performBatch(batchResult.lastId + 1);
                    } else {
                        return markBackfillCompleted()
                            .map(v2 -> BackfillResult.completed(processedCount.get()));
                    }
                }
            })
            .recover(ex -> {
                logger.error("Backfill failed for group '{}'", groupName, ex);
                return saveCheckpoint(currentId, ex.getMessage())
                    .compose(v -> markBackfillFailed(ex.getMessage()))
                    .map(v -> BackfillResult.failed(processedCount.get(), ex));
            });
    }

    /**
     * Execute single batch with latency tracking.
     */
    private Future<BatchResult> executeBatch(long startId) {
        long batchStart = System.nanoTime();

        String sql = """
            UPDATE outbox
            SET required_consumer_groups = required_consumer_groups + 1
            WHERE id IN (
                SELECT id FROM outbox
                WHERE topic = $1
                  AND status IN ('PENDING', 'PROCESSING')
                  AND id >= $2
                  AND NOT EXISTS (
                      SELECT 1 FROM outbox_consumer_groups cg
                      WHERE cg.message_id = outbox.id AND cg.group_name = $3
                  )
                ORDER BY id ASC
                LIMIT $4
            )
            RETURNING id
            """;

        return pool.preparedQuery(sql)
            .execute(Tuple.of(topic, startId, groupName, config.getBatchSize()))
            .map(rows -> {
                long latencyNanos = System.nanoTime() - batchStart;
                recordLatency(latencyNanos);

                if (rows.size() == 0) {
                    return new BatchResult(0, startId, false);
                }

                Long lastId = null;
                for (Row row : rows) {
                    lastId = row.getLong("id");
                }

                boolean hasMore = rows.size() >= config.getBatchSize();
                return new BatchResult(rows.size(), lastId, hasMore);
            });
    }

    /**
     * Adaptive rate limiting based on database latency.
     */
    private Future<Void> checkAndThrottle() {
        long p95Latency = calculateP95Latency();
        long targetNanos = config.getTargetLatency().toNanos();

        if (p95Latency > targetNanos) {
            // Database is under pressure, throttle
            long sleepMs = (p95Latency - targetNanos) / 1_000_000;  // Convert to ms
            sleepMs = Math.min(sleepMs, 5000);  // Cap at 5 seconds

            logger.debug("Throttling backfill for {}ms (p95: {}ms, target: {}ms)",
                sleepMs, p95Latency / 1_000_000, targetNanos / 1_000_000);

            return vertx.timer(sleepMs).mapEmpty();
        }

        return Future.succeededFuture();
    }

    private void recordLatency(long latencyNanos) {
        latencySamples.addLast(latencyNanos);
        if (latencySamples.size() > 100) {
            latencySamples.removeFirst();
        }
    }

    private long calculateP95Latency() {
        if (latencySamples.isEmpty()) {
            return 0;
        }

        List<Long> sorted = new ArrayList<>(latencySamples);
        Collections.sort(sorted);
        int p95Index = (int) (sorted.size() * 0.95);
        return sorted.get(Math.min(p95Index, sorted.size() - 1));
    }

    private boolean shouldSaveCheckpoint() {
        return processedCount.get() % 100_000 == 0;  // Every 100k messages
    }

    /**
     * Save checkpoint to database.
     */
    private Future<Void> saveCheckpoint(long checkpointId, String errorMessage) {
        String sql = """
            UPDATE outbox_topic_subscriptions
            SET backfill_checkpoint_id = $1,
                backfill_processed_messages = $2,
                backfill_error_message = $3
            WHERE topic = $4 AND group_name = $5
            """;

        return pool.preparedQuery(sql)
            .execute(Tuple.of(checkpointId, processedCount.get(), errorMessage, topic, groupName))
            .onSuccess(v -> logger.debug("Saved checkpoint for group '{}': {} (processed: {})",
                groupName, checkpointId, processedCount.get()))
            .mapEmpty();
    }

    private Future<Void> markBackfillCompleted() {
        String sql = """
            UPDATE outbox_topic_subscriptions
            SET backfill_status = 'COMPLETED',
                backfill_completed_at = NOW(),
                backfill_total_messages = $1
            WHERE topic = $2 AND group_name = $3
            """;

        return pool.preparedQuery(sql)
            .execute(Tuple.of(processedCount.get(), topic, groupName))
            .onSuccess(v -> logger.info("Backfill completed for group '{}': {} messages",
                groupName, processedCount.get()))
            .mapEmpty();
    }

    private Future<Void> markBackfillCancelled() {
        String sql = """
            UPDATE outbox_topic_subscriptions
            SET backfill_status = 'CANCELLED'
            WHERE topic = $1 AND group_name = $2
            """;

        return pool.preparedQuery(sql)
            .execute(Tuple.of(topic, groupName))
            .mapEmpty();
    }

    private Future<Void> markBackfillFailed(String errorMessage) {
        String sql = """
            UPDATE outbox_topic_subscriptions
            SET backfill_status = 'FAILED',
                backfill_error_message = $1
            WHERE topic = $2 AND group_name = $3
            """;

        return pool.preparedQuery(sql)
            .execute(Tuple.of(errorMessage, topic, groupName))
            .mapEmpty();
    }

    // Helper classes
    private record BackfillCheckpoint(BackfillStatus status, long checkpointId, long processedCount) {}
    private record BatchResult(int rowsAffected, long lastId, boolean hasMore) {}

    private enum BackfillStatus {
        NOT_STARTED, IN_PROGRESS, COMPLETED, CANCELLED, FAILED
    }
}
```

**Cancellation API**:
```java
// Start backfill
ResumableBackfillJob job = new ResumableBackfillJob(pool, "orders.events", "analytics", config);
Future<BackfillResult> result = job.start();

// Cancel mid-flight (safe)
job.cancel();

// Resume after crash
ResumableBackfillJob resumedJob = new ResumableBackfillJob(pool, "orders.events", "analytics", config);
resumedJob.start();  // Automatically resumes from last checkpoint
```

**Monitoring**:
```java
// Query backfill progress
SELECT topic, group_name, backfill_status,
       backfill_processed_messages, backfill_total_messages,
       ROUND(100.0 * backfill_processed_messages / NULLIF(backfill_total_messages, 0), 2) AS progress_pct,
       backfill_started_at,
       EXTRACT(EPOCH FROM (NOW() - backfill_started_at)) AS elapsed_seconds,
       ROUND(backfill_processed_messages / NULLIF(EXTRACT(EPOCH FROM (NOW() - backfill_started_at)), 0), 0) AS messages_per_second
FROM outbox_topic_subscriptions
WHERE backfill_status = 'IN_PROGRESS';
```

**Benefits**:
- ✅ **Crash-safe**: Resume from last checkpoint after process restart
- ✅ **Cancellable**: Stop backfill without data corruption
- ✅ **Observable**: Track progress, rate, and ETA
- ✅ **Adaptive**: Automatically throttles when database is under pressure
- ✅ **Bounded impact**: Configurable rate limits protect OLTP workloads
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

**Problem**: Consumer groups that never process messages block cleanup indefinitely.

**Solution**: Heartbeat tracking with automatic detection and cleanup.

#### The Dead Consumer Group Problem - Deep Dive

**What is a "Dead" Consumer Group?**

A consumer group becomes "dead" when it:
- ❌ Crashes without graceful shutdown
- ❌ Is decommissioned but subscription not cancelled
- ❌ Has network connectivity issues preventing heartbeats
- ❌ Is stuck in an infinite loop or deadlock
- ❌ Has been scaled down to zero instances

**Why This is Critical:**

In pub/sub semantics, messages cannot be deleted until ALL registered consumer groups process them:

```sql
-- Message can only be deleted when:
completed_consumer_groups >= required_consumer_groups
```

**Timeline of Failure Without Dead Consumer Detection:**

```
T0: Three consumer groups registered for topic "orders.events"
    - email-service (ACTIVE)
    - analytics-service (ACTIVE)
    - inventory-service (ACTIVE)

    Message inserted: required_consumer_groups = 3

T1: email-service processes message
    completed_consumer_groups = 1

T2: analytics-service processes message
    completed_consumer_groups = 2

T3: inventory-service CRASHES (pod killed, server dies, etc.)
    - No heartbeat sent
    - No graceful shutdown
    - Subscription remains ACTIVE in database

T4-T∞: Message stuck FOREVER
    - completed_consumer_groups = 2
    - required_consumer_groups = 3
    - Cleanup job cannot delete message (2 < 3)
    - Message accumulates in database
    - Eventually: OutOfMemory, disk full, performance degradation
```

**The Cascading Failure Scenario:**

```
Day 1: inventory-service crashes
       → 1,000 messages stuck waiting

Day 2: Still crashed
       → 50,000 messages stuck waiting

Day 3: Still crashed
       → 150,000 messages stuck waiting
       → Database size growing rapidly
       → Query performance degrading

Day 4: Database performance so bad that email-service and analytics-service start timing out
       → Now ALL services are failing
       → Complete system outage
```

**Why Manual Intervention Doesn't Scale:**

Without automatic detection:
- ❌ Requires 24/7 monitoring and manual intervention
- ❌ Outages during nights/weekends cause massive backlogs
- ❌ Human response time (minutes to hours) vs. automatic (seconds)
- ❌ Risk of human error when manually cleaning up
- ❌ No clear procedure for "how long to wait before declaring dead"

#### The Solution: Multi-Layered Dead Consumer Detection

**Layer 1: Heartbeat Protocol**
- Consumer groups send periodic "I'm alive" signals
- Configurable interval (default: 60 seconds)
- Lightweight UPDATE query
- Runs in background thread

**Layer 2: Timeout Detection**
- Scheduled job checks for missing heartbeats
- Configurable timeout (default: 300 seconds = 5 minutes)
- Marks subscriptions as DEAD
- Triggers cleanup process

**Layer 3: Automatic Message Cleanup**
- Decrements `required_consumer_groups` for pending messages
- Allows other groups to complete message processing
- Prevents indefinite blocking

**Layer 4: Graceful Shutdown Handling**
- Consumer groups mark themselves CANCELLED on shutdown
- Prevents false positives for dead detection
- Immediate cleanup without waiting for timeout

**Layer 5: Manual Admin Override**
- Admin API to force-remove consumer groups
- Emergency cleanup for orphaned messages
- Health monitoring dashboard

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

#### Pitfalls and Edge Cases

**Pitfall 1: Race Condition - Message Inserted During Dead Detection**

```
T0: Dead consumer detection job starts
    - Finds inventory-service is DEAD
    - Marks subscription_status = 'DEAD'

T1: New message inserted (BEFORE cleanup runs)
    - Trigger counts ACTIVE subscriptions
    - inventory-service still in database (status = 'DEAD')
    - Should required_consumer_groups include it? ❌ NO

T2: Cleanup job decrements required_consumer_groups
    - But new message already has wrong count!
```

**Solution:**
```sql
-- Trigger only counts ACTIVE subscriptions
CREATE OR REPLACE FUNCTION set_required_consumer_groups()
RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM outbox_topics WHERE topic = NEW.topic AND semantics = 'PUB_SUB') THEN
        NEW.required_consumer_groups := (
            SELECT COUNT(*)
            FROM outbox_topic_subscriptions
            WHERE topic = NEW.topic
              AND subscription_status = 'ACTIVE'  -- ✅ Only ACTIVE, not DEAD
        );
    ELSE
        NEW.required_consumer_groups := 1;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

**Pitfall 2: Consumer Group Flapping (Restart Loop)**

```
T0: inventory-service starts, sends heartbeat
T1: inventory-service crashes
T2: Kubernetes restarts pod
T3: inventory-service starts again, sends heartbeat
T4: inventory-service crashes again
... (repeat every 30 seconds)
```

**Problem:**
- Never stays dead long enough to be detected (timeout = 300s)
- But also never successfully processes messages
- Messages stuck in limbo

**Solution:**
```java
// Track consecutive failures in subscription metadata
ALTER TABLE outbox_topic_subscriptions ADD COLUMN
    consecutive_failures INT DEFAULT 0;

// Mark as DEAD after N consecutive heartbeat misses
UPDATE outbox_topic_subscriptions
SET subscription_status = 'DEAD',
    consecutive_failures = consecutive_failures + 1
WHERE subscription_status = 'ACTIVE'
  AND last_heartbeat_at < NOW() - (heartbeat_timeout_seconds || ' seconds')::INTERVAL
  AND consecutive_failures >= 3;  -- ✅ Require multiple failures
```

**Pitfall 3: Network Partition - Consumer Alive But Unreachable**

```
T0: inventory-service running normally
T1: Network partition - database unreachable
    - Consumer still processing messages locally
    - Cannot send heartbeat to database
    - Cannot fetch new messages

T2: Heartbeat timeout expires (5 minutes)
    - Marked as DEAD
    - required_consumer_groups decremented

T3: Network partition heals
    - Consumer reconnects
    - Tries to send heartbeat
    - Subscription status = 'DEAD' ❌
    - What happens to messages processed during partition?
```

**Solution:**
```java
private void sendHeartbeat() {
    String sql = """
        UPDATE outbox_topic_subscriptions
        SET last_heartbeat_at = NOW(),
            last_active_at = NOW(),
            subscription_status = CASE
                WHEN subscription_status = 'DEAD' THEN 'ACTIVE'  -- ✅ Auto-resurrect
                ELSE subscription_status
            END,
            consecutive_failures = 0  -- ✅ Reset failure counter
        WHERE topic = $1
          AND group_name = $2
          AND subscription_status IN ('ACTIVE', 'DEAD')  -- ✅ Allow resurrection
        """;

    pool.preparedQuery(sql)
        .execute(Tuple.of(topic, groupName))
        .onSuccess(result -> {
            if (result.rowCount() == 0) {
                logger.warn("Heartbeat failed - subscription may be CANCELLED");
                // Trigger re-registration
                registerSubscription(currentOptions);
            }
        })
        .onFailure(ex ->
            logger.error("Failed to send heartbeat for group '{}'", groupName, ex));
}
```

**Pitfall 4: Clock Skew Between Application and Database**

```
Application Server Time: 2025-11-12 10:00:00 UTC
Database Server Time:    2025-11-12 09:55:00 UTC (5 minutes behind)

T0: Consumer sends heartbeat
    - Application thinks: last_heartbeat_at = 10:00:00
    - Database records:   last_heartbeat_at = 09:55:00

T1: Dead detection job runs (on database)
    - Database time: 09:56:00
    - Checks: 09:56:00 - 09:55:00 = 1 minute (OK)

T2: 5 minutes later, dead detection runs again
    - Database time: 10:01:00
    - Last heartbeat: 09:55:00
    - Difference: 6 minutes > 5 minute timeout
    - Marked as DEAD ❌ (even though consumer is alive!)
```

**Solution:**
```sql
-- Always use database time (NOW()) for heartbeat timestamps
UPDATE outbox_topic_subscriptions
SET last_heartbeat_at = NOW(),  -- ✅ Database time, not application time
    last_active_at = NOW()
WHERE topic = $1 AND group_name = $2;

-- Dead detection also uses database time
UPDATE outbox_topic_subscriptions
SET subscription_status = 'DEAD'
WHERE subscription_status = 'ACTIVE'
  AND last_heartbeat_at < NOW() - (heartbeat_timeout_seconds || ' seconds')::INTERVAL;
  -- ✅ Both NOW() calls use same database clock
```

**Pitfall 5: Cleanup Job Itself Crashes Mid-Execution**

```
T0: Dead detection job starts
    - Marks inventory-service as DEAD
    - Begins decrementing required_consumer_groups

T1: Cleanup job crashes after processing 1,000 of 10,000 messages
    - 1,000 messages: required_consumer_groups decremented ✅
    - 9,000 messages: required_consumer_groups NOT decremented ❌
    - Subscription status = 'DEAD' (already committed)

T2: Cleanup job runs again
    - Finds inventory-service already marked DEAD
    - Doesn't process it again (already in DEAD state)
    - 9,000 messages stuck forever ❌
```

**Solution:**
```java
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
          -- ✅ IDEMPOTENT: Only decrement if group is still counted
          AND required_consumer_groups > (
              SELECT COUNT(*) FROM outbox_topic_subscriptions
              WHERE topic = $1 AND subscription_status = 'ACTIVE'
          )
        """;

    return pool.preparedQuery(sql)
        .execute(Tuple.of(topic, groupName))
        .onSuccess(rows ->
            logger.info("Decremented required_consumer_groups for {} messages from dead group '{}'",
                rows.rowCount(), groupName))
        .mapEmpty();
}

// Run cleanup job EVERY time, not just on status change
public Future<List<String>> detectAndCleanupDeadConsumerGroups() {
    String detectSql = """
        SELECT topic, group_name
        FROM outbox_topic_subscriptions
        WHERE subscription_status = 'DEAD'  -- ✅ Process ALL dead groups, not just newly detected
        """;

    return pool.preparedQuery(detectSql)
        .execute()
        .compose(rows -> {
            List<Future<Void>> cleanupFutures = new ArrayList<>();
            for (Row row : rows) {
                String topic = row.getString("topic");
                String groupName = row.getString("group_name");
                cleanupFutures.add(cleanupDeadGroupMessages(topic, groupName));
            }
            return Future.all(cleanupFutures).mapEmpty();
        });
}
```

**Pitfall 6: Consumer Group Removed But Messages Already In-Flight**

```
T0: inventory-service fetches batch of 100 messages
    - Messages marked FOR UPDATE SKIP LOCKED
    - Processing begins

T1: inventory-service crashes mid-processing
    - 50 messages processed ✅
    - 50 messages in-flight ❌

T2: Dead detection marks inventory-service as DEAD
    - Decrements required_consumer_groups for pending messages
    - But what about the 50 in-flight messages?

T3: In-flight messages timeout and return to PENDING
    - required_consumer_groups already decremented
    - Other groups process them
    - inventory-service resurrects and tries to complete them
    - Double processing! ❌
```

**Solution:**
```java
// Check subscription status before marking message complete
private Future<Void> markMessageProcessedByGroup(Long messageId, String groupName) {
    String sql = """
        WITH subscription_check AS (
            SELECT subscription_status
            FROM outbox_topic_subscriptions
            WHERE topic = $1 AND group_name = $2
        ),
        group_processing AS (
            INSERT INTO outbox_consumer_groups (group_name, message_id, processed_at)
            SELECT $2, $3, NOW()
            FROM subscription_check
            WHERE subscription_status = 'ACTIVE'  -- ✅ Only if still ACTIVE
            ON CONFLICT (group_name, message_id) DO NOTHING
            RETURNING message_id
        ),
        update_count AS (
            UPDATE outbox
            SET completed_consumer_groups = completed_consumer_groups + 1
            WHERE id = $3
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
        .execute(Tuple.of(topic, groupName, messageId))
        .compose(result -> {
            if (result.rowCount() == 0) {
                logger.warn("Message {} not marked complete - group '{}' may be DEAD",
                    messageId, groupName);
                // ✅ Don't fail - message will be reprocessed by other groups
            }
            return Future.succeededFuture();
        });
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

**Pitfall 7: Thundering Herd on Consumer Group Resurrection**

```
T0: 10 instances of inventory-service all crash simultaneously
    - All marked as DEAD
    - required_consumer_groups decremented for 100,000 messages

T1: All 10 instances restart simultaneously
    - All send heartbeat at same time
    - All marked ACTIVE
    - All try to re-register subscription

T2: New messages arrive
    - required_consumer_groups = 3 (email, analytics, inventory)
    - But what about the 100,000 old messages?
    - They still have required_consumer_groups = 2 (decremented when inventory was DEAD)
    - inventory-service never processes them ❌
```

**Solution:**
```java
// On resurrection, optionally backfill old messages
private Future<Void> registerSubscription(SubscriptionOptions options) {
    String sql = """
        INSERT INTO outbox_topic_subscriptions
            (topic, group_name, start_from_message_id, start_from_timestamp, subscription_status)
        VALUES ($1, $2, $3, $4, 'ACTIVE')
        ON CONFLICT (topic, group_name)
        DO UPDATE SET
            subscription_status = 'ACTIVE',
            last_active_at = NOW(),
            last_heartbeat_at = NOW()
        RETURNING (xmax = 0) AS is_new_subscription  -- ✅ Detect if this is resurrection
        """;

    return pool.preparedQuery(sql)
        .execute(Tuple.of(topic, groupName, options.getStartMessageId(), options.getStartTimestamp()))
        .compose(result -> {
            Row row = result.iterator().next();
            boolean isNewSubscription = row.getBoolean("is_new_subscription");

            if (!isNewSubscription) {
                logger.warn("Consumer group '{}' resurrected - checking for missed messages", groupName);
                return backfillMissedMessages();  // ✅ Re-increment required_consumer_groups
            }
            return Future.succeededFuture();
        });
}

private Future<Void> backfillMissedMessages() {
    String sql = """
        UPDATE outbox
        SET required_consumer_groups = required_consumer_groups + 1
        WHERE topic = $1
          AND status IN ('PENDING', 'PROCESSING')
          AND NOT EXISTS (
              SELECT 1 FROM outbox_consumer_groups cg
              WHERE cg.message_id = outbox.id AND cg.group_name = $2
          )
          -- ✅ Only backfill messages that were decremented
          AND required_consumer_groups < (
              SELECT COUNT(*) FROM outbox_topic_subscriptions
              WHERE topic = $1 AND subscription_status = 'ACTIVE'
          )
        """;

    return pool.preparedQuery(sql)
        .execute(Tuple.of(topic, groupName))
        .onSuccess(rows ->
            logger.info("Backfilled {} missed messages for resurrected group '{}'",
                rows.rowCount(), groupName))
        .mapEmpty();
}
```

**Pitfall 8: Subscription Deleted While Messages In-Flight**

```
T0: Admin manually removes consumer group
    DELETE FROM outbox_topic_subscriptions WHERE group_name = 'inventory-service';

T1: inventory-service still running, tries to send heartbeat
    - Row doesn't exist
    - Heartbeat fails silently ❌

T2: inventory-service continues processing messages
    - Tries to mark messages complete
    - Fails because subscription doesn't exist
    - Messages stuck in PROCESSING state ❌
```

**Solution:**
```java
// Heartbeat detects missing subscription and stops consumer
private void sendHeartbeat() {
    String sql = """
        UPDATE outbox_topic_subscriptions
        SET last_heartbeat_at = NOW(),
            last_active_at = NOW()
        WHERE topic = $1
          AND group_name = $2
          AND subscription_status IN ('ACTIVE', 'DEAD')
        """;

    pool.preparedQuery(sql)
        .execute(Tuple.of(topic, groupName))
        .onSuccess(result -> {
            if (result.rowCount() == 0) {
                logger.error("Subscription for group '{}' no longer exists - STOPPING CONSUMER",
                    groupName);
                // ✅ Stop processing immediately
                stopPolling();
                close();
            }
        })
        .onFailure(ex ->
            logger.error("Failed to send heartbeat for group '{}'", groupName, ex));
}

// Admin operation must handle in-flight messages
public Future<Void> removeConsumerGroup(String topic, String groupName) {
    return pool.withTransaction(conn -> {
        // Step 1: Mark subscription as CANCELLED (not deleted)
        String cancelSql = """
            UPDATE outbox_topic_subscriptions
            SET subscription_status = 'CANCELLED'
            WHERE topic = $1 AND group_name = $2
            """;

        return conn.preparedQuery(cancelSql)
            .execute(Tuple.of(topic, groupName))
            .compose(v -> {
                // Step 2: Wait for in-flight messages to complete or timeout
                // (Implementation depends on timeout strategy)
                return waitForInFlightMessages(conn, topic, groupName);
            })
            .compose(v -> {
                // Step 3: Decrement required_consumer_groups
                return cleanupDeadGroupMessages(topic, groupName);
            })
            .compose(v -> {
                // Step 4: Now safe to delete subscription
                String deleteSql = """
                    DELETE FROM outbox_topic_subscriptions
                    WHERE topic = $1 AND group_name = $2
                    """;
                return conn.preparedQuery(deleteSql)
                    .execute(Tuple.of(topic, groupName))
                    .mapEmpty();
            });
    });
}
```

**Pitfall 9: Database Connection Pool Exhaustion from Heartbeats**

```
Scenario: 1,000 consumer group instances
          Each sends heartbeat every 60 seconds
          = ~16 heartbeat queries per second

Problem: If each heartbeat holds a connection for 100ms
         = 1.6 connections in use at any time (OK)

         But if database is slow (1 second per query)
         = 16 connections in use at any time

         If connection pool size = 10
         = Pool exhaustion, heartbeats fail, all groups marked DEAD ❌
```

**Solution:**
```java
// Use dedicated connection pool for heartbeats
public class OutboxConsumerGroup<T> {

    private final Pool heartbeatPool;  // ✅ Separate pool
    private final Pool processingPool;  // ✅ Separate pool

    public OutboxConsumerGroup(
        Pool processingPool,
        Pool heartbeatPool,  // Small pool, e.g., size=2
        String topic,
        String groupName
    ) {
        this.processingPool = processingPool;
        this.heartbeatPool = heartbeatPool;
        this.topic = topic;
        this.groupName = groupName;
    }

    private void sendHeartbeat() {
        String sql = """
            UPDATE outbox_topic_subscriptions
            SET last_heartbeat_at = NOW(),
                last_active_at = NOW()
            WHERE topic = $1 AND group_name = $2
            """;

        // ✅ Use dedicated heartbeat pool
        heartbeatPool.preparedQuery(sql)
            .execute(Tuple.of(topic, groupName))
            .onFailure(ex -> {
                logger.error("Failed to send heartbeat for group '{}'", groupName, ex);
                // ✅ Implement exponential backoff
                scheduleNextHeartbeat(calculateBackoff());
            });
    }
}

// Alternative: Use fire-and-forget with timeout
private void sendHeartbeat() {
    String sql = """
        UPDATE outbox_topic_subscriptions
        SET last_heartbeat_at = NOW()
        WHERE topic = $1 AND group_name = $2
        """;

    heartbeatPool.preparedQuery(sql)
        .execute(Tuple.of(topic, groupName))
        .timeout(Duration.ofSeconds(5))  // ✅ Timeout to prevent blocking
        .onFailure(ex -> {
            if (ex instanceof TimeoutException) {
                logger.warn("Heartbeat timeout for group '{}' - database may be overloaded", groupName);
            }
        });
}
```

#### Manual Admin Operations
```java
public interface ConsumerGroupAdmin {

    /**
     * Manually mark a consumer group as dead and cleanup its pending messages.
     * Handles in-flight messages gracefully.
     */
    Future<Void> removeConsumerGroup(String topic, String groupName);

    /**
     * List all consumer groups and their health status.
     */
    Future<List<ConsumerGroupHealth>> listConsumerGroups(String topic);

    /**
     * Force cleanup of messages stuck waiting for dead consumer groups.
     * Use with caution - may cause message loss if groups are actually alive.
     */
    Future<Integer> forceCompleteOrphanedMessages(String topic, Duration olderThan);

    /**
     * Resurrect a DEAD consumer group and backfill missed messages.
     */
    Future<Void> resurrectConsumerGroup(String topic, String groupName, boolean backfill);

    /**
     * Get detailed metrics for a consumer group.
     */
    Future<ConsumerGroupMetrics> getConsumerGroupMetrics(String topic, String groupName);
}

public class ConsumerGroupHealth {
    private final String groupName;
    private final String topic;
    private final String status;  // ACTIVE, DEAD, CANCELLED
    private final Instant lastHeartbeat;
    private final Duration timeSinceLastHeartbeat;
    private final long pendingMessages;
    private final long processedMessages;
    private final long inFlightMessages;  // ✅ Messages being processed
    private final boolean isHealthy;  // ✅ Overall health indicator
}

public class ConsumerGroupMetrics {
    private final String groupName;
    private final String topic;
    private final long totalMessagesProcessed;
    private final long totalMessagesFailed;
    private final double averageProcessingTimeMs;
    private final long messagesPerSecond;
    private final Instant oldestPendingMessage;
    private final long lagMessages;  // ✅ How far behind
}
```

#### Operational Best Practices

**Best Practice 1: Heartbeat Interval Configuration**

```java
// ❌ BAD: Heartbeat too frequent
heartbeatInterval = 5 seconds   // Creates unnecessary database load
heartbeatTimeout = 15 seconds   // Too aggressive, false positives

// ❌ BAD: Heartbeat too infrequent
heartbeatInterval = 300 seconds  // 5 minutes between heartbeats
heartbeatTimeout = 600 seconds   // 10 minutes to detect dead consumer
                                 // Messages stuck for too long

// ✅ GOOD: Balanced configuration
heartbeatInterval = 60 seconds   // 1 minute - reasonable overhead
heartbeatTimeout = 300 seconds   // 5 minutes - allows for temporary issues
                                 // Ratio: timeout = 5 × interval

// ✅ BETTER: Adaptive based on message volume
if (messagesPerSecond > 1000) {
    heartbeatInterval = 30 seconds;   // More frequent for high-volume
    heartbeatTimeout = 120 seconds;   // Faster detection
} else {
    heartbeatInterval = 60 seconds;   // Standard for normal volume
    heartbeatTimeout = 300 seconds;
}
```

**Best Practice 2: Monitoring and Alerting**

```java
// Critical metrics to monitor
public class ConsumerGroupMonitoring {

    /**
     * Alert if any consumer group hasn't sent heartbeat in 80% of timeout period.
     * This gives time to investigate before group is marked DEAD.
     */
    public Future<List<String>> getConsumerGroupsNearTimeout() {
        String sql = """
            SELECT topic, group_name,
                   EXTRACT(EPOCH FROM (NOW() - last_heartbeat_at)) AS seconds_since_heartbeat,
                   heartbeat_timeout_seconds
            FROM outbox_topic_subscriptions
            WHERE subscription_status = 'ACTIVE'
              AND last_heartbeat_at < NOW() - (heartbeat_timeout_seconds * 0.8 || ' seconds')::INTERVAL
            """;

        return pool.preparedQuery(sql).execute()
            .map(rows -> {
                List<String> warnings = new ArrayList<>();
                for (Row row : rows) {
                    warnings.add(String.format(
                        "Consumer group '%s' on topic '%s' near timeout: %d/%d seconds",
                        row.getString("group_name"),
                        row.getString("topic"),
                        row.getLong("seconds_since_heartbeat"),
                        row.getInteger("heartbeat_timeout_seconds")
                    ));
                }
                return warnings;
            });
    }

    /**
     * Alert if messages are stuck waiting for consumer groups.
     */
    public Future<List<String>> getStuckMessages() {
        String sql = """
            SELECT topic, COUNT(*) as stuck_count,
                   MIN(created_at) as oldest_message,
                   EXTRACT(EPOCH FROM (NOW() - MIN(created_at))) / 3600 AS hours_stuck
            FROM outbox
            WHERE status IN ('PENDING', 'PROCESSING')
              AND completed_consumer_groups < required_consumer_groups
              AND created_at < NOW() - INTERVAL '1 hour'
            GROUP BY topic
            HAVING COUNT(*) > 100  -- Alert threshold
            """;

        return pool.preparedQuery(sql).execute()
            .map(rows -> {
                List<String> alerts = new ArrayList<>();
                for (Row row : rows) {
                    alerts.add(String.format(
                        "Topic '%s' has %d messages stuck for %.1f hours",
                        row.getString("topic"),
                        row.getLong("stuck_count"),
                        row.getDouble("hours_stuck")
                    ));
                }
                return alerts;
            });
    }

    /**
     * Alert if required_consumer_groups doesn't match active subscriptions.
     * This indicates a bug or inconsistency.
     */
    public Future<List<String>> getInconsistentMessages() {
        String sql = """
            SELECT o.topic, COUNT(*) as inconsistent_count
            FROM outbox o
            INNER JOIN outbox_topics t ON t.topic = o.topic
            WHERE t.semantics = 'PUB_SUB'
              AND o.status IN ('PENDING', 'PROCESSING')
              AND o.required_consumer_groups != (
                  SELECT COUNT(*)
                  FROM outbox_topic_subscriptions s
                  WHERE s.topic = o.topic
                    AND s.subscription_status = 'ACTIVE'
              )
            GROUP BY o.topic
            """;

        return pool.preparedQuery(sql).execute()
            .map(rows -> {
                List<String> alerts = new ArrayList<>();
                for (Row row : rows) {
                    alerts.add(String.format(
                        "Topic '%s' has %d messages with inconsistent required_consumer_groups",
                        row.getString("topic"),
                        row.getLong("inconsistent_count")
                    ));
                }
                return alerts;
            });
    }
}
```

**Best Practice 3: Graceful Degradation**

```java
// Handle database outages gracefully
public class ResilientHeartbeat {

    private int consecutiveFailures = 0;
    private static final int MAX_FAILURES_BEFORE_STOP = 10;

    private void sendHeartbeat() {
        String sql = """
            UPDATE outbox_topic_subscriptions
            SET last_heartbeat_at = NOW()
            WHERE topic = $1 AND group_name = $2
            """;

        heartbeatPool.preparedQuery(sql)
            .execute(Tuple.of(topic, groupName))
            .timeout(Duration.ofSeconds(5))
            .onSuccess(result -> {
                consecutiveFailures = 0;  // ✅ Reset on success

                if (result.rowCount() == 0) {
                    logger.error("Subscription no longer exists - stopping consumer");
                    stopConsumer();
                }
            })
            .onFailure(ex -> {
                consecutiveFailures++;

                if (consecutiveFailures >= MAX_FAILURES_BEFORE_STOP) {
                    logger.error(
                        "Heartbeat failed {} times - stopping consumer to prevent zombie processing",
                        consecutiveFailures
                    );
                    stopConsumer();  // ✅ Stop processing if can't send heartbeat
                } else {
                    logger.warn(
                        "Heartbeat failed ({}/{}): {}",
                        consecutiveFailures,
                        MAX_FAILURES_BEFORE_STOP,
                        ex.getMessage()
                    );
                    // ✅ Continue processing but with warning
                }
            });
    }
}
```

**Best Practice 4: Testing Dead Consumer Detection**

```java
@Test
public void testDeadConsumerGroupDetection() {
    // Setup: Create consumer group with short timeout
    TopicConfiguration config = TopicConfiguration.pubSub("test.topic", Duration.ofHours(1));
    queueFactory.configureTopic(config);

    ConsumerGroup<TestEvent> group = queueFactory.createConsumerGroup(
        "test-group",
        "test.topic",
        TestEvent.class
    );

    // Override timeout for testing
    setHeartbeatTimeout("test-group", 10);  // 10 seconds

    group.start(SubscriptionOptions.fromNow());

    // Publish message
    producer.send(new TestEvent("test"));

    // Verify message has required_consumer_groups = 1
    assertMessageRequiredGroups("test.topic", 1);

    // Simulate crash: stop heartbeat but don't close gracefully
    group.stopHeartbeatOnly();  // ✅ Test helper method

    // Wait for timeout + detection job interval
    Thread.sleep(15000);

    // Run dead consumer detection
    deadConsumerCleanup.detectAndCleanupDeadConsumerGroups().await();

    // Verify group marked as DEAD
    assertSubscriptionStatus("test-group", "DEAD");

    // Verify required_consumer_groups decremented
    assertMessageRequiredGroups("test.topic", 0);

    // Verify message can now be cleaned up
    cleanupJob.cleanupCompletedMessages().await();
    assertMessageDeleted("test.topic");
}

@Test
public void testConsumerGroupResurrection() {
    // Setup: Create and kill consumer group
    ConsumerGroup<TestEvent> group = createAndKillConsumerGroup("test-group");

    // Publish messages while dead
    for (int i = 0; i < 100; i++) {
        producer.send(new TestEvent("msg-" + i));
    }

    // Verify messages have required_consumer_groups = 0 (group is dead)
    assertMessageRequiredGroups("test.topic", 0);

    // Resurrect consumer group
    group.start(SubscriptionOptions.fromBeginning(true));  // ✅ With backfill

    // Verify messages have required_consumer_groups = 1 (group resurrected)
    assertMessageRequiredGroups("test.topic", 1);

    // Verify all 100 messages are processed
    waitForProcessing();
    assertMessagesProcessedByGroup("test-group", 100);
}
```

**Best Practice 5: Database Maintenance**

```sql
-- Regular maintenance queries

-- 1. Find orphaned consumer group entries (no messages to process)
SELECT s.topic, s.group_name, s.subscription_status,
       s.last_heartbeat_at,
       COUNT(o.id) as pending_messages
FROM outbox_topic_subscriptions s
LEFT JOIN outbox o ON o.topic = s.topic
    AND o.status IN ('PENDING', 'PROCESSING')
    AND NOT EXISTS (
        SELECT 1 FROM outbox_consumer_groups cg
        WHERE cg.message_id = o.id AND cg.group_name = s.group_name
    )
WHERE s.subscription_status = 'ACTIVE'
GROUP BY s.topic, s.group_name, s.subscription_status, s.last_heartbeat_at
HAVING COUNT(o.id) = 0
    AND s.last_heartbeat_at < NOW() - INTERVAL '7 days';

-- 2. Find messages with mismatched required_consumer_groups
SELECT o.topic, o.id, o.required_consumer_groups,
       (SELECT COUNT(*) FROM outbox_topic_subscriptions s
        WHERE s.topic = o.topic AND s.subscription_status = 'ACTIVE') as active_groups
FROM outbox o
INNER JOIN outbox_topics t ON t.topic = o.topic
WHERE t.semantics = 'PUB_SUB'
  AND o.status IN ('PENDING', 'PROCESSING')
  AND o.required_consumer_groups != (
      SELECT COUNT(*) FROM outbox_topic_subscriptions s
      WHERE s.topic = o.topic AND s.subscription_status = 'ACTIVE'
  );

-- 3. Cleanup old DEAD/CANCELLED subscriptions
DELETE FROM outbox_topic_subscriptions
WHERE subscription_status IN ('DEAD', 'CANCELLED')
  AND last_active_at < NOW() - INTERVAL '30 days'
  AND NOT EXISTS (
      SELECT 1 FROM outbox o
      WHERE o.topic = outbox_topic_subscriptions.topic
        AND o.status IN ('PENDING', 'PROCESSING')
  );
```

---

## Cleanup Job Operations

This section provides comprehensive details on how cleanup jobs operate, their scheduling, performance characteristics, and impact on the overall system.

### Overview of Cleanup Jobs

The system requires **three distinct cleanup jobs** to maintain database health and prevent unbounded growth:

| Cleanup Job | Purpose | Frequency | Impact | Critical? |
|-------------|---------|-----------|--------|-----------|
| **Message Cleanup** | Delete completed messages past retention | Every 5-15 minutes | Medium | Yes |
| **Dead Consumer Detection** | Mark dead subscriptions and adjust message counts | Every 1-5 minutes | Low | Yes |
| **Subscription Cleanup** | Remove old DEAD/CANCELLED subscriptions | Every 24 hours | Very Low | No |

### 1. Message Cleanup Job

#### Purpose
Deletes completed messages that have been processed by all required consumer groups and are past their retention period.

#### Implementation
```java
public class OutboxMessageCleanupJob {

    private final PgPool pool;
    private final ScheduledExecutorService scheduler;
    private final int batchSize;
    private final Duration cleanupInterval;

    public OutboxMessageCleanupJob(PgPool pool, CleanupConfiguration config) {
        this.pool = pool;
        this.batchSize = config.getCleanupBatchSize();  // Default: 10,000
        this.cleanupInterval = config.getCleanupInterval();  // Default: 5 minutes
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-message-cleanup");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(
            this::runCleanupCycle,
            cleanupInterval.toMillis(),
            cleanupInterval.toMillis(),
            TimeUnit.MILLISECONDS
        );
        logger.info("Started message cleanup job (interval: {}, batch size: {})",
            cleanupInterval, batchSize);
    }

    private void runCleanupCycle() {
        long startTime = System.currentTimeMillis();
        AtomicInteger totalDeleted = new AtomicInteger(0);

        cleanupBatch()
            .compose(deleted -> {
                totalDeleted.addAndGet(deleted);

                // If batch was full, there may be more to delete
                if (deleted >= batchSize) {
                    logger.debug("Batch full ({} deleted), scheduling immediate next batch", deleted);
                    return cleanupBatch();  // Recursive cleanup
                }
                return Future.succeededFuture(0);
            })
            .onSuccess(v -> {
                long duration = System.currentTimeMillis() - startTime;
                if (totalDeleted.get() > 0) {
                    logger.info("Cleanup cycle complete: {} messages deleted in {}ms",
                        totalDeleted.get(), duration);
                }
            })
            .onFailure(ex -> logger.error("Cleanup cycle failed", ex));
    }

    private Future<Integer> cleanupBatch() {
        String sql = """
            WITH topics_with_retention AS (
                SELECT topic, COALESCE(message_retention_hours, 24) AS retention_hours
                FROM outbox_topics
            ),
            messages_to_delete AS (
                SELECT o.id
                FROM outbox o
                INNER JOIN topics_with_retention t ON t.topic = o.topic
                WHERE o.status = 'COMPLETED'
                  AND o.processed_at < NOW() - (t.retention_hours || ' hours')::INTERVAL
                  AND (
                      -- Queue semantics: delete after retention
                      o.required_consumer_groups = 1
                      OR
                      -- Pub/Sub: all groups completed
                      (o.completed_consumer_groups >= o.required_consumer_groups
                       AND o.required_consumer_groups > 0)
                      OR
                      -- Zero subscriptions: minimum 1-hour retention
                      (o.required_consumer_groups = 0
                       AND o.created_at < NOW() - INTERVAL '1 hour')
                  )
                ORDER BY o.processed_at ASC  -- Delete oldest first
                LIMIT $1
                FOR UPDATE SKIP LOCKED  -- Allow concurrent cleanup jobs
            )
            DELETE FROM outbox
            WHERE id IN (SELECT id FROM messages_to_delete)
            RETURNING id
            """;

        return pool.preparedQuery(sql)
            .execute(Tuple.of(batchSize))
            .map(rows -> rows.size());
    }

    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
```

#### Performance Characteristics

**Query Execution Time** (depends on table size and indexes):
```
Outbox table size: 1,000,000 messages
Batch size: 10,000
Index on (status, processed_at): Present

Query execution time: 50-200ms per batch
Throughput: 50,000-200,000 messages/second (theoretical)
Actual sustained: 10,000-30,000 messages/second
```

**Database Impact**:
- **Locks**: Row-level locks with `FOR UPDATE SKIP LOCKED` (minimal contention)
- **I/O**: DELETE generates WAL (Write-Ahead Log) entries
- **Vacuum**: Deleted rows require VACUUM to reclaim space
- **Cascade**: If `outbox_consumer_groups` has `ON DELETE CASCADE`, deletes cascade automatically

**CPU Impact**:
- Minimal during query execution (index scan + delete)
- Moderate during VACUUM (background process)

**Memory Impact**:
- Minimal (batch size × row size ≈ 10,000 × 1KB = 10MB per batch)

#### Scheduling Recommendations

| Message Rate | Cleanup Interval | Batch Size | Rationale |
|--------------|------------------|------------|-----------|
| < 100 msg/sec | 15 minutes | 5,000 | Low urgency, reduce overhead |
| 100-1,000 msg/sec | 5 minutes | 10,000 | Balanced approach |
| 1,000-10,000 msg/sec | 2 minutes | 20,000 | Prevent backlog accumulation |
| > 10,000 msg/sec | 1 minute | 50,000 | Aggressive cleanup required |

**Warning**: If cleanup rate < production rate, the outbox table will grow unbounded!

---

### 2. Dead Consumer Detection Job

#### Purpose
Detects consumer groups that have stopped sending heartbeats, marks them as DEAD, and decrements `required_consumer_groups` for pending messages to unblock cleanup.

#### Implementation
```java
public class DeadConsumerDetectionJob {

    private final PgPool pool;
    private final ScheduledExecutorService scheduler;
    private final Duration detectionInterval;

    public DeadConsumerDetectionJob(PgPool pool, CleanupConfiguration config) {
        this.pool = pool;
        this.detectionInterval = config.getDeadConsumerDetectionInterval();  // Default: 1 minute
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dead-consumer-detection");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(
            this::runDetectionCycle,
            detectionInterval.toMillis(),
            detectionInterval.toMillis(),
            TimeUnit.MILLISECONDS
        );
        logger.info("Started dead consumer detection job (interval: {})", detectionInterval);
    }

    private void runDetectionCycle() {
        long startTime = System.currentTimeMillis();

        detectAndCleanupDeadConsumerGroups()
            .onSuccess(deadGroups -> {
                long duration = System.currentTimeMillis() - startTime;
                if (!deadGroups.isEmpty()) {
                    logger.warn("Detected {} dead consumer groups in {}ms: {}",
                        deadGroups.size(), duration, deadGroups);
                }
            })
            .onFailure(ex -> logger.error("Dead consumer detection failed", ex));
    }

    private Future<List<String>> detectAndCleanupDeadConsumerGroups() {
        // Step 1: Mark subscriptions as DEAD
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

                    // Step 2: Cleanup messages for each dead group
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
              AND required_consumer_groups > 0
              AND NOT EXISTS (
                  SELECT 1 FROM outbox_consumer_groups cg
                  WHERE cg.message_id = outbox.id
                    AND cg.group_name = $2
              )
            """;

        return pool.preparedQuery(sql)
            .execute(Tuple.of(topic, groupName))
            .onSuccess(rows -> {
                if (rows.rowCount() > 0) {
                    logger.info("Decremented required_consumer_groups for {} messages (dead group: '{}')",
                        rows.rowCount(), groupName);
                }
            })
            .mapEmpty();
    }
}
```

#### Performance Characteristics

**Query Execution Time**:
```
Subscriptions table size: 100 consumer groups
Dead groups detected: 1-5 per cycle (rare)
Messages affected per dead group: 1,000-100,000

Detection query: 5-10ms
Cleanup query per group: 50-500ms (depends on message count)
Total cycle time: 50-2,500ms
```

**Database Impact**:
- **Locks**: Row-level locks on `outbox_topic_subscriptions` (minimal contention)
- **I/O**: UPDATE generates WAL entries for affected messages
- **Impact on consumers**: None (uses `FOR UPDATE SKIP LOCKED` in consumer queries)

**CPU Impact**: Minimal (index scans + updates)

**Memory Impact**: Minimal (small result sets)

#### Scheduling Recommendations

| Heartbeat Timeout | Detection Interval | Rationale |
|-------------------|-------------------|-----------|
| 5 minutes | 1 minute | Fast detection, minimal delay |
| 10 minutes | 2 minutes | Balanced approach |
| 30 minutes | 5 minutes | Conservative (avoid false positives) |

**Best Practice**: Detection interval should be ≤ 20% of heartbeat timeout to ensure timely detection.

---

### 3. Subscription Cleanup Job

#### Purpose
Removes old DEAD and CANCELLED subscriptions that no longer have pending messages, preventing unbounded growth of the `outbox_topic_subscriptions` table.

#### Implementation
```java
public class SubscriptionCleanupJob {

    private final PgPool pool;
    private final ScheduledExecutorService scheduler;

    public SubscriptionCleanupJob(PgPool pool) {
        this.pool = pool;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "subscription-cleanup");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        // Run daily at 2 AM
        long initialDelay = calculateInitialDelay();
        scheduler.scheduleAtFixedRate(
            this::runCleanup,
            initialDelay,
            TimeUnit.DAYS.toMillis(1),
            TimeUnit.MILLISECONDS
        );
        logger.info("Started subscription cleanup job (daily at 2 AM)");
    }

    private void runCleanup() {
        String sql = """
            DELETE FROM outbox_topic_subscriptions
            WHERE subscription_status IN ('DEAD', 'CANCELLED')
              AND last_active_at < NOW() - INTERVAL '30 days'
              AND NOT EXISTS (
                  SELECT 1 FROM outbox o
                  WHERE o.topic = outbox_topic_subscriptions.topic
                    AND o.status IN ('PENDING', 'PROCESSING')
              )
            RETURNING topic, group_name
            """;

        pool.preparedQuery(sql)
            .execute()
            .onSuccess(rows -> {
                if (rows.size() > 0) {
                    logger.info("Cleaned up {} old subscriptions", rows.size());
                }
            })
            .onFailure(ex -> logger.error("Subscription cleanup failed", ex));
    }
}
```

#### Performance Characteristics

**Query Execution Time**: 10-100ms (small table, infrequent operation)

**Database Impact**: Minimal (runs once per day, affects few rows)

---

### Performance Impact on Consumer Groups and Fan-Out

#### Impact on Message Processing

**1. Lock Contention**
```
Cleanup Job Query:
  SELECT ... FROM outbox WHERE status = 'COMPLETED' ... FOR UPDATE SKIP LOCKED

Consumer Query:
  SELECT ... FROM outbox WHERE status = 'PENDING' ... FOR UPDATE SKIP LOCKED

✅ NO CONTENTION: Different rows (COMPLETED vs. PENDING)
```

**2. Index Contention**
```
Cleanup Job: Scans index on (status, processed_at)
Consumer: Scans index on (topic, status, created_at)

⚠️ MINOR CONTENTION: Both read same index pages
Impact: < 5% query latency increase during cleanup
```

**3. WAL (Write-Ahead Log) Pressure**
```
Cleanup Job: Generates WAL for DELETE operations
Impact on Consumers: Minimal (WAL is sequential write)

Concern: High cleanup rate can saturate WAL disk I/O
Mitigation: Use separate disk for WAL, monitor wal_buffers
```

#### Impact on Fan-Out Operations

**Scenario**: 10 consumer groups, 1,000 messages/second, cleanup every 5 minutes

```
Normal Operation (no cleanup):
  Consumer query latency: 0.5ms
  Message processing rate: 1,000 msg/sec

During Cleanup (10,000 message batch):
  Consumer query latency: 0.6ms (+20%)
  Message processing rate: 950 msg/sec (-5%)
  Cleanup duration: 200ms

Impact: Negligible (cleanup is 200ms every 5 minutes = 0.07% of time)
```

**Worst Case**: Cleanup backlog (100,000 messages to delete)

```
Cleanup duration: 10 batches × 200ms = 2 seconds
Consumer query latency during cleanup: 0.8ms (+60%)
Message processing rate during cleanup: 800 msg/sec (-20%)

Impact: Moderate but brief (2 seconds every 5 minutes = 0.67% of time)
```

#### Monitoring and Alerts

**Critical Metrics**:
```java
public class CleanupJobMetrics {

    // Message Cleanup
    private final Counter messagesDeleted;
    private final Timer cleanupDuration;
    private final Gauge cleanupBacklog;  // Messages eligible for cleanup

    // Dead Consumer Detection
    private final Counter deadGroupsDetected;
    private final Counter messagesAdjusted;  // required_consumer_groups decremented

    // Health Checks
    public boolean isHealthy() {
        // Alert if cleanup is falling behind
        long backlog = getCleanupBacklog();
        long productionRate = getMessageProductionRate();
        long cleanupRate = getCleanupRate();

        if (cleanupRate < productionRate) {
            logger.error("CRITICAL: Cleanup rate ({}/sec) < production rate ({}/sec)",
                cleanupRate, productionRate);
            return false;
        }

        if (backlog > 1_000_000) {
            logger.warn("WARNING: Cleanup backlog is {} messages", backlog);
            return false;
        }

        return true;
    }
}
```

**Recommended Alerts**:
1. **Cleanup Backlog > 1M messages**: Increase cleanup frequency or batch size
2. **Cleanup Rate < Production Rate**: System will eventually run out of disk space
3. **Dead Consumer Detection Failures**: Messages may be stuck indefinitely
4. **Cleanup Job Execution Time > 10 seconds**: Database performance degradation

---

### Configuration Best Practices

```java
public class CleanupConfiguration {

    // Message Cleanup
    private final Duration cleanupInterval;        // Default: 5 minutes
    private final int cleanupBatchSize;            // Default: 10,000

    // Dead Consumer Detection
    private final Duration detectionInterval;      // Default: 1 minute
    private final int heartbeatTimeoutSeconds;     // Default: 300 (5 minutes)

    // Subscription Cleanup
    private final Duration subscriptionRetention;  // Default: 30 days

    public static CleanupConfiguration forLowThroughput() {
        return new CleanupConfiguration(
            Duration.ofMinutes(15),  // Cleanup every 15 minutes
            5_000,                   // Small batches
            Duration.ofMinutes(5),   // Detect dead consumers every 5 minutes
            600,                     // 10-minute timeout
            Duration.ofDays(30)
        );
    }

    public static CleanupConfiguration forHighThroughput() {
        return new CleanupConfiguration(
            Duration.ofMinutes(1),   // Aggressive cleanup every minute
            50_000,                  // Large batches
            Duration.ofMinutes(1),   // Fast dead consumer detection
            300,                     // 5-minute timeout
            Duration.ofDays(7)       // Shorter retention
        );
    }

    public static CleanupConfiguration forCriticalSystem() {
        return new CleanupConfiguration(
            Duration.ofMinutes(5),   // Balanced cleanup
            10_000,                  // Medium batches
            Duration.ofMinutes(2),   // Conservative detection
            600,                     // 10-minute timeout (avoid false positives)
            Duration.ofDays(90)      // Long retention for audit
        );
    }
}
```

---

## Concurrency and Scalability Concerns

### ⚠️ Concern 1: Heartbeat Write Contention

**Problem**: High write contention on `outbox_topic_subscriptions` table with many consumer instances.

**Scenario:**
```
Topic: "orders.events"
Consumer Groups: 50 (email, analytics, inventory, shipping, etc.)
Instances per Group: 10 (for horizontal scaling)
Total Instances: 500

Heartbeat Interval: 60 seconds
Heartbeat Writes per Second: 500 / 60 = ~8.3 writes/second

But all instances share the same group_name!
Actual Rows Updated: 50 (one per consumer group)
```

**The Real Problem - Row-Level Lock Contention:**

```sql
-- All 10 instances of "email-service" try to update the SAME row
UPDATE outbox_topic_subscriptions
SET last_heartbeat_at = NOW()
WHERE topic = 'orders.events' AND group_name = 'email-service';

-- PostgreSQL acquires row-level lock
-- Instance 1: Acquires lock, updates row (10ms)
-- Instance 2: Waits for lock... (10ms wait + 10ms update)
-- Instance 3: Waits for lock... (20ms wait + 10ms update)
-- ...
-- Instance 10: Waits for lock... (90ms wait + 10ms update)

-- Total time: 100ms for all instances to update
-- If heartbeat interval = 60s, this is acceptable

-- But what if we have 100 instances per group?
-- Instance 100: Waits 990ms + 10ms = 1 second!
-- If heartbeat interval = 60s, still OK

-- But what if heartbeat interval = 10s?
-- 100 instances × 10ms = 1 second to update
-- Some instances will timeout!
```

**Detailed Analysis:**

```
Scenario: 100 consumer groups, 20 instances each = 2,000 total instances
Heartbeat interval: 30 seconds
Expected writes/second: 2,000 / 30 = 66.6 writes/second

PostgreSQL can handle ~10,000 simple UPDATE/second
So 66 writes/second should be fine, right? ❌ WRONG!

The problem is LOCK CONTENTION, not throughput:

T0.000s: All 20 instances of "email-service" send heartbeat simultaneously
         (They all woke up at the same time)

T0.000s: Instance 1 acquires row lock
T0.010s: Instance 1 releases lock
T0.010s: Instance 2 acquires row lock
T0.020s: Instance 2 releases lock
...
T0.190s: Instance 20 releases lock

Meanwhile, at T0.100s:
- Instances 11-20 are still waiting for lock
- If heartbeat timeout = 5 seconds, this is OK
- But if database is under load, each update takes 100ms instead of 10ms
- Instance 20 waits: 19 × 100ms = 1.9 seconds
- Still OK, but getting close to timeout

Now add 100 consumer groups all doing this:
- Database CPU spikes
- Lock wait times increase
- Some heartbeats timeout
- Consumer groups marked DEAD incorrectly ❌
```

**Solution 1: Randomize Heartbeat Timing**

```java
public class OutboxConsumerGroup<T> {

    private Future<Void> startHeartbeat() {
        int heartbeatInterval = configuration.getHeartbeatIntervalSeconds();

        // ✅ Add random jitter to prevent thundering herd
        int initialDelay = ThreadLocalRandom.current().nextInt(0, heartbeatInterval);

        heartbeatScheduler.scheduleAtFixedRate(
            this::sendHeartbeat,
            initialDelay,  // ✅ Random initial delay
            heartbeatInterval,
            TimeUnit.SECONDS
        );

        logger.info("Started heartbeat for consumer group '{}' (interval: {}s, initial delay: {}s)",
            groupName, heartbeatInterval, initialDelay);

        return Future.succeededFuture();
    }
}
```

**Solution 2: Heartbeat Batching (Advanced)**

```java
// Instead of each instance updating the row, use a shared coordinator
public class SharedHeartbeatCoordinator {

    private final Map<String, AtomicLong> lastHeartbeatPerGroup = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public void registerConsumerGroup(String topic, String groupName) {
        String key = topic + ":" + groupName;
        lastHeartbeatPerGroup.putIfAbsent(key, new AtomicLong(System.currentTimeMillis()));
    }

    public void recordHeartbeat(String topic, String groupName) {
        String key = topic + ":" + groupName;
        lastHeartbeatPerGroup.get(key).set(System.currentTimeMillis());
    }

    // ✅ Single thread updates ALL consumer groups
    public void startBatchHeartbeat(int intervalSeconds) {
        scheduler.scheduleAtFixedRate(() -> {
            List<Tuple> batch = new ArrayList<>();

            for (Map.Entry<String, AtomicLong> entry : lastHeartbeatPerGroup.entrySet()) {
                String[] parts = entry.getKey().split(":");
                String topic = parts[0];
                String groupName = parts[1];
                batch.add(Tuple.of(topic, groupName));
            }

            if (!batch.isEmpty()) {
                sendBatchHeartbeat(batch);
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private void sendBatchHeartbeat(List<Tuple> batch) {
        // ✅ Use UNNEST for batch update
        String sql = """
            UPDATE outbox_topic_subscriptions AS s
            SET last_heartbeat_at = NOW(),
                last_active_at = NOW()
            FROM (SELECT * FROM UNNEST($1::text[], $2::text[])) AS v(topic, group_name)
            WHERE s.topic = v.topic AND s.group_name = v.group_name
            """;

        String[] topics = batch.stream().map(t -> t.getString(0)).toArray(String[]::new);
        String[] groups = batch.stream().map(t -> t.getString(1)).toArray(String[]::new);

        pool.preparedQuery(sql)
            .execute(Tuple.of(topics, groups))
            .onSuccess(result ->
                logger.debug("Batch heartbeat updated {} consumer groups", result.rowCount()))
            .onFailure(ex ->
                logger.error("Batch heartbeat failed", ex));
    }
}
```

**Solution 3: Heartbeat Table Partitioning**

```sql
-- Partition by topic to reduce contention
CREATE TABLE outbox_topic_subscriptions (
    id BIGSERIAL,
    topic VARCHAR(255) NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    last_heartbeat_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    -- ... other columns
    PRIMARY KEY (topic, id)
) PARTITION BY LIST (topic);

-- Create partition per high-volume topic
CREATE TABLE outbox_topic_subscriptions_orders
    PARTITION OF outbox_topic_subscriptions
    FOR VALUES IN ('orders.events');

CREATE TABLE outbox_topic_subscriptions_payments
    PARTITION OF outbox_topic_subscriptions
    FOR VALUES IN ('payments.events');

-- Default partition for low-volume topics
CREATE TABLE outbox_topic_subscriptions_default
    PARTITION OF outbox_topic_subscriptions
    DEFAULT;

-- ✅ Reduces lock contention by isolating hot topics
```

### ⚠️ Concern 2: Trigger Performance on Message Insert

**Problem**: The `set_required_consumer_groups()` trigger runs on EVERY message insert.

**Scenario:**
```
Topic: "orders.events" (PUB_SUB semantics)
Consumer Groups: 50
Message Rate: 1,000 messages/second

For EACH message insert:
1. Check if topic is PUB_SUB (1 query)
2. Count active subscriptions (1 query)
3. Set required_consumer_groups

Total: 2 queries × 1,000 messages/second = 2,000 queries/second
```

**Detailed Analysis:**

```sql
-- Current trigger (runs for EVERY INSERT)
CREATE OR REPLACE FUNCTION set_required_consumer_groups()
RETURNS TRIGGER AS $$
BEGIN
    -- Query 1: Check topic semantics
    IF EXISTS (SELECT 1 FROM outbox_topics WHERE topic = NEW.topic AND semantics = 'PUB_SUB') THEN
        -- Query 2: Count active subscriptions
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

-- Performance impact:
-- 1,000 inserts/second × 2 queries = 2,000 queries/second
-- Each query takes ~1ms (with proper indexes)
-- Total overhead: 2 seconds of database time per second of wall time
-- This is UNSUSTAINABLE ❌
```

**Solution 1: Cache Topic Configuration in Trigger**

```sql
-- Use temporary table to cache topic configuration
CREATE UNLOGGED TABLE IF NOT EXISTS outbox_topic_cache (
    topic VARCHAR(255) PRIMARY KEY,
    semantics VARCHAR(20),
    active_consumer_count INT,
    last_updated TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Refresh cache periodically (every 60 seconds)
CREATE OR REPLACE FUNCTION refresh_topic_cache()
RETURNS void AS $$
BEGIN
    TRUNCATE outbox_topic_cache;

    INSERT INTO outbox_topic_cache (topic, semantics, active_consumer_count)
    SELECT t.topic, t.semantics,
           COALESCE((
               SELECT COUNT(*)
               FROM outbox_topic_subscriptions s
               WHERE s.topic = t.topic
                 AND s.subscription_status = 'ACTIVE'
           ), 0) AS active_consumer_count
    FROM outbox_topics t;
END;
$$ LANGUAGE plpgsql;

-- Optimized trigger using cache
CREATE OR REPLACE FUNCTION set_required_consumer_groups()
RETURNS TRIGGER AS $$
DECLARE
    cached_semantics VARCHAR(20);
    cached_count INT;
BEGIN
    -- ✅ Single query to cache table (much faster)
    SELECT semantics, active_consumer_count
    INTO cached_semantics, cached_count
    FROM outbox_topic_cache
    WHERE topic = NEW.topic;

    IF cached_semantics = 'PUB_SUB' THEN
        NEW.required_consumer_groups := cached_count;
    ELSE
        NEW.required_consumer_groups := 1;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Schedule cache refresh
-- (Use pg_cron or application-level scheduler)
SELECT cron.schedule('refresh-topic-cache', '*/1 * * * *', 'SELECT refresh_topic_cache()');
```

**Solution 2: Application-Level Caching**

```java
public class OutboxProducer<T> {

    private final LoadingCache<String, TopicMetadata> topicCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .refreshAfterWrite(Duration.ofSeconds(30))
        .build(this::loadTopicMetadata);

    private TopicMetadata loadTopicMetadata(String topic) {
        String sql = """
            SELECT t.semantics,
                   COALESCE((
                       SELECT COUNT(*)
                       FROM outbox_topic_subscriptions s
                       WHERE s.topic = t.topic
                         AND s.subscription_status = 'ACTIVE'
                   ), 0) AS active_consumer_count
            FROM outbox_topics t
            WHERE t.topic = $1
            """;

        return pool.preparedQuery(sql)
            .execute(Tuple.of(topic))
            .map(rows -> {
                if (rows.size() == 0) {
                    return new TopicMetadata(TopicSemantics.QUEUE, 1);
                }
                Row row = rows.iterator().next();
                return new TopicMetadata(
                    TopicSemantics.valueOf(row.getString("semantics")),
                    row.getInteger("active_consumer_count")
                );
            })
            .await();
    }

    public Future<Long> send(T payload, Map<String, String> headers) {
        TopicMetadata metadata = topicCache.get(topic);

        String sql = """
            INSERT INTO outbox (topic, payload, headers, status, required_consumer_groups)
            VALUES ($1, $2, $3, 'PENDING', $4)
            RETURNING id
            """;

        return pool.preparedQuery(sql)
            .execute(Tuple.of(
                topic,
                Json.encode(payload),
                Json.encode(headers),
                metadata.getRequiredConsumerGroups()  // ✅ Set from cache
            ))
            .map(rows -> rows.iterator().next().getLong("id"));
    }
}
```

**Solution 3: Remove Trigger, Use Application Logic**

```java
// ✅ Most performant: No trigger at all
public class OutboxProducer<T> {

    public Future<Long> send(T payload) {
        return getRequiredConsumerGroups(topic)
            .compose(requiredGroups -> {
                String sql = """
                    INSERT INTO outbox (topic, payload, status, required_consumer_groups)
                    VALUES ($1, $2, 'PENDING', $3)
                    RETURNING id
                    """;

                return pool.preparedQuery(sql)
                    .execute(Tuple.of(topic, Json.encode(payload), requiredGroups))
                    .map(rows -> rows.iterator().next().getLong("id"));
            });
    }

    private Future<Integer> getRequiredConsumerGroups(String topic) {
        // Use cache with 1-minute TTL
        return topicMetadataCache.get(topic)
            .map(TopicMetadata::getRequiredConsumerGroups);
    }
}

// Remove trigger entirely
DROP TRIGGER IF EXISTS trg_set_required_consumer_groups ON outbox;
DROP FUNCTION IF EXISTS set_required_consumer_groups();
```

### ⚠️ Concern 3: Consumer Group Query Performance

**Problem**: The pub/sub query has multiple JOINs and NOT EXISTS checks.

**Scenario:**
```
Topic: "orders.events"
Messages in outbox: 1,000,000 (high retention)
Consumer Groups: 50
Messages per group to fetch: 100

Query must:
1. JOIN outbox with outbox_topic_subscriptions
2. Filter by topic and status
3. Check NOT EXISTS in outbox_consumer_groups (potentially 50M rows)
4. Apply start position filters
5. ORDER BY created_at
6. LIMIT 100
```

**Query Analysis:**

```sql
-- Current pub/sub query
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
FOR UPDATE SKIP LOCKED;

-- EXPLAIN ANALYZE shows:
-- 1. Index scan on outbox (topic, status, created_at) - GOOD
-- 2. Nested loop join with outbox_topic_subscriptions - GOOD (only 1 row)
-- 3. NOT EXISTS subquery - POTENTIALLY SLOW
--    - For each candidate message, check if processed by this group
--    - If outbox_consumer_groups has 50M rows, this is expensive
--    - Even with index on (message_id, group_name)

-- Worst case:
-- 1,000,000 pending messages × NOT EXISTS check = 1,000,000 index lookups
-- Even at 0.1ms per lookup = 100 seconds ❌
```

**Solution 1: Optimize NOT EXISTS with Covering Index**

```sql
-- Create covering index for NOT EXISTS check
CREATE INDEX idx_consumer_groups_message_group_covering
    ON outbox_consumer_groups(message_id, group_name)
    INCLUDE (processed_at);  -- ✅ Covering index

-- PostgreSQL can use index-only scan
-- No need to access table heap
-- Reduces I/O significantly
```

**Solution 2: Partition outbox_consumer_groups Table**

```sql
-- Partition by message_id range to reduce index size
CREATE TABLE outbox_consumer_groups (
    id BIGSERIAL,
    message_id BIGINT NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    PRIMARY KEY (message_id, id)
) PARTITION BY RANGE (message_id);

-- Create partitions (e.g., every 10M messages)
CREATE TABLE outbox_consumer_groups_0_10m
    PARTITION OF outbox_consumer_groups
    FOR VALUES FROM (0) TO (10000000);

CREATE TABLE outbox_consumer_groups_10m_20m
    PARTITION OF outbox_consumer_groups
    FOR VALUES FROM (10000000) TO (20000000);

-- ✅ NOT EXISTS only scans relevant partition
-- Smaller index = faster lookups
```

**Solution 3: Use Anti-Join Instead of NOT EXISTS**

```sql
-- Rewrite query using LEFT JOIN + IS NULL (anti-join)
SELECT o.id, o.payload, o.headers, o.correlation_id, o.message_group, o.created_at
FROM outbox o
INNER JOIN outbox_topic_subscriptions s
    ON s.topic = o.topic AND s.group_name = $2
LEFT JOIN outbox_consumer_groups cg
    ON cg.message_id = o.id AND cg.group_name = $2
WHERE o.topic = $1
  AND o.status = 'PENDING'
  AND (s.start_from_message_id IS NULL OR o.id >= s.start_from_message_id)
  AND (s.start_from_timestamp IS NULL OR o.created_at >= s.start_from_timestamp)
  AND cg.message_id IS NULL  -- ✅ Anti-join: not processed by this group
ORDER BY o.created_at ASC
LIMIT $3
FOR UPDATE SKIP LOCKED;

-- PostgreSQL query planner often optimizes anti-joins better than NOT EXISTS
-- Especially with proper indexes
```

**Solution 4: Materialized View for Pending Messages**

```sql
-- Create materialized view of pending messages per group
CREATE MATERIALIZED VIEW outbox_pending_by_group AS
SELECT o.topic, s.group_name, o.id, o.created_at
FROM outbox o
CROSS JOIN outbox_topic_subscriptions s
WHERE o.status = 'PENDING'
  AND s.topic = o.topic
  AND s.subscription_status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM outbox_consumer_groups cg
      WHERE cg.message_id = o.id AND cg.group_name = s.group_name
  );

CREATE INDEX idx_pending_by_group ON outbox_pending_by_group(topic, group_name, created_at);

-- Refresh periodically (e.g., every 10 seconds)
REFRESH MATERIALIZED VIEW CONCURRENTLY outbox_pending_by_group;

-- Query becomes trivial
SELECT o.id, o.payload, o.headers, o.correlation_id, o.message_group, o.created_at
FROM outbox o
INNER JOIN outbox_pending_by_group p
    ON p.id = o.id
WHERE p.topic = $1
  AND p.group_name = $2
ORDER BY p.created_at ASC
LIMIT $3
FOR UPDATE SKIP LOCKED;

-- ✅ Much faster, but 10-second staleness
-- Trade-off: Performance vs. real-time accuracy
```

### ⚠️ Concern 4: Message Completion Write Amplification

**Problem**: Each consumer group completion requires multiple writes.

**Scenario:**
```
Topic: "orders.events"
Consumer Groups: 50
Message Rate: 1,000 messages/second
Total Completions: 50 × 1,000 = 50,000 completions/second

For EACH completion:
1. INSERT into outbox_consumer_groups (1 write)
2. UPDATE outbox.completed_consumer_groups (1 write)
3. Possibly UPDATE outbox.status = 'COMPLETED' (1 write)

Total writes: 50,000 × 2-3 = 100,000-150,000 writes/second ❌
```

**Detailed Analysis:**

```sql
-- Current completion logic (from design doc)
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
RETURNING id;

-- Performance issues:
-- 1. Three separate operations (INSERT, UPDATE, UPDATE)
-- 2. Each acquires locks
-- 3. Each generates WAL (Write-Ahead Log) entries
-- 4. High contention on outbox table

-- With 50 consumer groups:
-- - 50 concurrent UPDATEs to same outbox row
-- - Row-level lock contention
-- - Last group waits for 49 previous groups
```

**Solution 1: Batch Message Completion**

```java
public class OutboxConsumer<T> {

    private final List<Long> completedMessageIds = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService batchScheduler =
        Executors.newSingleThreadScheduledExecutor();

    public void start() {
        // ✅ Batch completions every 5 seconds
        batchScheduler.scheduleAtFixedRate(
            this::flushCompletedMessages,
            5, 5, TimeUnit.SECONDS
        );
    }

    private Future<Void> markMessageCompleted(Long messageId) {
        // ✅ Add to batch instead of immediate write
        completedMessageIds.add(messageId);
        return Future.succeededFuture();
    }

    private void flushCompletedMessages() {
        if (completedMessageIds.isEmpty()) {
            return;
        }

        List<Long> batch = new ArrayList<>(completedMessageIds);
        completedMessageIds.clear();

        String sql = """
            WITH group_processing AS (
                INSERT INTO outbox_consumer_groups (group_name, message_id, processed_at)
                SELECT $1, unnest($2::bigint[]), NOW()
                ON CONFLICT (group_name, message_id) DO NOTHING
                RETURNING message_id
            ),
            update_count AS (
                UPDATE outbox
                SET completed_consumer_groups = completed_consumer_groups + 1
                WHERE id IN (SELECT message_id FROM group_processing)
                RETURNING id, completed_consumer_groups, required_consumer_groups
            )
            UPDATE outbox
            SET status = 'COMPLETED', processed_at = NOW()
            WHERE id IN (
                SELECT id FROM update_count
                WHERE completed_consumer_groups >= required_consumer_groups
            )
            """;

        pool.preparedQuery(sql)
            .execute(Tuple.of(groupName, batch.toArray(new Long[0])))
            .onFailure(ex -> {
                logger.error("Failed to flush completed messages", ex);
                // ✅ Re-add to batch for retry
                completedMessageIds.addAll(batch);
            });
    }
}
```

**Solution 2: Asynchronous Completion with Queue**

```java
public class AsyncCompletionProcessor {

    private final BlockingQueue<CompletionTask> completionQueue =
        new LinkedBlockingQueue<>(10000);
    private final ExecutorService completionWorkers =
        Executors.newFixedThreadPool(4);  // ✅ Dedicated completion threads

    public void start() {
        for (int i = 0; i < 4; i++) {
            completionWorkers.submit(this::processCompletions);
        }
    }

    public Future<Void> markMessageCompleted(Long messageId, String groupName) {
        CompletionTask task = new CompletionTask(messageId, groupName);

        if (!completionQueue.offer(task)) {
            logger.warn("Completion queue full - applying backpressure");
            return Future.failedFuture("Completion queue full");
        }

        return Future.succeededFuture();
    }

    private void processCompletions() {
        List<CompletionTask> batch = new ArrayList<>(100);

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // ✅ Wait for first item
                CompletionTask first = completionQueue.poll(1, TimeUnit.SECONDS);
                if (first != null) {
                    batch.add(first);

                    // ✅ Drain up to 99 more items (non-blocking)
                    completionQueue.drainTo(batch, 99);

                    // ✅ Process batch
                    processBatch(batch);
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void processBatch(List<CompletionTask> batch) {
        // Group by consumer group name for efficiency
        Map<String, List<Long>> byGroup = batch.stream()
            .collect(Collectors.groupingBy(
                CompletionTask::getGroupName,
                Collectors.mapping(CompletionTask::getMessageId, Collectors.toList())
            ));

        for (Map.Entry<String, List<Long>> entry : byGroup.entrySet()) {
            String groupName = entry.getKey();
            List<Long> messageIds = entry.getValue();

            markBatchCompleted(groupName, messageIds).await();
        }
    }
}
```

**Solution 3: Optimistic Locking for Completion Counter**

```sql
-- Add version column for optimistic locking
ALTER TABLE outbox ADD COLUMN version INT DEFAULT 0;

-- Update with optimistic locking
WITH group_processing AS (
    INSERT INTO outbox_consumer_groups (group_name, message_id, processed_at)
    VALUES ($1, $2, NOW())
    ON CONFLICT (group_name, message_id) DO NOTHING
    RETURNING message_id
),
update_count AS (
    UPDATE outbox
    SET completed_consumer_groups = completed_consumer_groups + 1,
        version = version + 1  -- ✅ Increment version
    WHERE id = $2
      AND EXISTS (SELECT 1 FROM group_processing)
    RETURNING id, completed_consumer_groups, required_consumer_groups, version
)
UPDATE outbox
SET status = 'COMPLETED', processed_at = NOW()
WHERE id IN (
    SELECT id FROM update_count
    WHERE completed_consumer_groups >= required_consumer_groups
)
RETURNING id;

-- ✅ Reduces lock contention by using optimistic locking
-- ✅ Retries are handled at application level
```

### ⚠️ Concern 5: Cleanup Job Performance at Scale

**Problem**: Cleanup job must scan entire outbox table.

**Scenario:**
```
Outbox table size: 10,000,000 messages
Retention period: 24 hours
Messages to delete: 1,000,000 per day
Cleanup frequency: Every hour

Cleanup query must:
1. Scan 10M rows to find completed messages
2. Check retention period
3. Check completed_consumer_groups >= required_consumer_groups
4. Delete 1M rows
```

**Detailed Analysis:**

```sql
-- Current cleanup query
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
RETURNING id;

-- Performance issues:
-- 1. Correlated subquery for EACH row (10M subqueries!)
-- 2. Full table scan if no proper index
-- 3. Deleting 1M rows generates massive WAL
-- 4. Locks table during deletion
-- 5. Vacuum required after deletion (more I/O)

-- EXPLAIN ANALYZE shows:
-- Seq Scan on outbox (cost=0..1000000 rows=10000000)
--   Filter: (status = 'COMPLETED' AND ...)
--   SubPlan 1
--     -> Seq Scan on outbox_topics (cost=0..10 rows=1)  -- ✅ At least this is small
-- Planning Time: 1.2 ms
-- Execution Time: 45000 ms  -- ❌ 45 seconds!
```

**Solution 1: Optimize Cleanup Query**

```sql
-- Pre-join with outbox_topics to avoid correlated subquery
WITH topics_with_retention AS (
    SELECT topic, COALESCE(message_retention_hours, 24) AS retention_hours
    FROM outbox_topics
),
messages_to_delete AS (
    SELECT o.id
    FROM outbox o
    INNER JOIN topics_with_retention t ON t.topic = o.topic
    WHERE o.status = 'COMPLETED'
      AND o.processed_at < NOW() - (t.retention_hours || ' hours')::INTERVAL
      AND (
          o.required_consumer_groups = 1
          OR o.completed_consumer_groups >= o.required_consumer_groups
      )
    LIMIT 10000  -- ✅ Delete in batches
)
DELETE FROM outbox
WHERE id IN (SELECT id FROM messages_to_delete)
RETURNING id;

-- ✅ No correlated subquery
-- ✅ Batch deletion (10K at a time)
-- ✅ Reduces lock time
```

**Solution 2: Partition Outbox Table by Created Date**

```sql
-- Partition by month for efficient cleanup
CREATE TABLE outbox (
    id BIGSERIAL,
    topic VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    -- ... other columns
    PRIMARY KEY (created_at, id)
) PARTITION BY RANGE (created_at);

-- Create partitions
CREATE TABLE outbox_2025_01 PARTITION OF outbox
    FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');

CREATE TABLE outbox_2025_02 PARTITION OF outbox
    FOR VALUES FROM ('2025-02-01') TO ('2025-03-01');

-- ✅ Cleanup becomes trivial: DROP old partitions
DROP TABLE outbox_2024_11;  -- Instant deletion, no WAL

-- ✅ Queries only scan relevant partitions
-- ✅ Indexes are smaller (per partition)
```

**Solution 3: Background Vacuum and Incremental Cleanup**

```java
public class IncrementalCleanupJob {

    private static final int BATCH_SIZE = 1000;
    private static final Duration BATCH_INTERVAL = Duration.ofSeconds(10);

    public void startIncrementalCleanup() {
        scheduler.scheduleAtFixedRate(
            this::cleanupBatch,
            0,
            BATCH_INTERVAL.toMillis(),
            TimeUnit.MILLISECONDS
        );
    }

    private void cleanupBatch() {
        String sql = """
            WITH topics_with_retention AS (
                SELECT topic, COALESCE(message_retention_hours, 24) AS retention_hours
                FROM outbox_topics
            ),
            messages_to_delete AS (
                SELECT o.id
                FROM outbox o
                INNER JOIN topics_with_retention t ON t.topic = o.topic
                WHERE o.status = 'COMPLETED'
                  AND o.processed_at < NOW() - (t.retention_hours || ' hours')::INTERVAL
                  AND (
                      o.required_consumer_groups = 1
                      OR o.completed_consumer_groups >= o.required_consumer_groups
                  )
                ORDER BY o.processed_at ASC  -- ✅ Delete oldest first
                LIMIT $1
                FOR UPDATE SKIP LOCKED  -- ✅ Allow concurrent cleanup jobs
            )
            DELETE FROM outbox
            WHERE id IN (SELECT id FROM messages_to_delete)
            RETURNING id
            """;

        pool.preparedQuery(sql)
            .execute(Tuple.of(BATCH_SIZE))
            .onSuccess(result -> {
                int deleted = result.rowCount();
                if (deleted > 0) {
                    logger.info("Deleted {} completed messages", deleted);

                    // ✅ If batch was full, there may be more to delete
                    if (deleted == BATCH_SIZE) {
                        // Schedule immediate next batch
                        scheduler.schedule(this::cleanupBatch, 100, TimeUnit.MILLISECONDS);
                    }
                }
            })
            .onFailure(ex -> logger.error("Cleanup batch failed", ex));
    }
}
```

### ⚠️ Concern 6: Hot Partition Problem

**Problem**: Popular topics create hot spots in the database.

**Scenario:**
```
Topic Distribution:
- "orders.events": 10,000 messages/second (99% of traffic)
- "user.events": 50 messages/second
- "admin.events": 10 messages/second

All messages go to same outbox table
All queries filter by topic
"orders.events" creates hot partition in indexes
```

**Solution 1: Topic-Specific Tables**

```java
public class TopicPartitionedOutboxFactory {

    private final Map<String, String> topicToTable = Map.of(
        "orders.events", "outbox_orders",
        "payments.events", "outbox_payments"
        // Default: "outbox"
    );

    public <T> OutboxProducer<T> createProducer(String topic, Class<T> payloadType) {
        String tableName = topicToTable.getOrDefault(topic, "outbox");
        return new OutboxProducer<>(pool, tableName, topic, payloadType);
    }
}

-- Create dedicated tables for high-volume topics
CREATE TABLE outbox_orders (LIKE outbox INCLUDING ALL);
CREATE TABLE outbox_payments (LIKE outbox INCLUDING ALL);

-- ✅ Isolates hot topics
-- ✅ Smaller indexes
-- ✅ Better cache locality
-- ❌ More complex schema management
```

**Solution 2: Read Replicas for Consumer Queries**

```java
public class OutboxConsumer<T> {

    private final Pool writePool;  // Primary database
    private final Pool readPool;   // Read replica

    private Future<Void> processAvailableMessagesReactive() {
        // ✅ Read from replica to reduce load on primary
        return readPool.preparedQuery(fetchSql)
            .execute(Tuple.of(topic, groupName, batchSize))
            .compose(this::processMessages)
            .compose(messageIds -> {
                // ✅ Write completions to primary
                return writePool.preparedQuery(completionSql)
                    .execute(Tuple.of(groupName, messageIds))
                    .mapEmpty();
            });
    }
}

-- ✅ Distributes read load across replicas
-- ✅ Primary only handles writes
-- ❌ Replication lag may delay message visibility
```

**Solution 3: Sharding by Message Group**

```sql
-- Shard outbox table by message_group hash
CREATE TABLE outbox (
    id BIGSERIAL,
    topic VARCHAR(255) NOT NULL,
    message_group VARCHAR(255),
    payload JSONB NOT NULL,
    -- ... other columns
    PRIMARY KEY (message_group, id)
) PARTITION BY HASH (message_group);

-- Create 16 shards
CREATE TABLE outbox_shard_0 PARTITION OF outbox
    FOR VALUES WITH (MODULUS 16, REMAINDER 0);
CREATE TABLE outbox_shard_1 PARTITION OF outbox
    FOR VALUES WITH (MODULUS 16, REMAINDER 1);
-- ... create shards 2-15

-- ✅ Distributes load across shards
-- ✅ Queries with message_group are fast
-- ❌ Queries without message_group scan all shards
```

### ⚠️ Concern 7: outbox_consumer_groups Table Growth and Query Overhead

**Problem**: The `outbox_consumer_groups` table becomes a massive bottleneck at scale.

**Scenario:**
```
Topic: "orders.events" (PUB_SUB)
Consumer Groups: 50
Message Rate: 1,000 messages/second
Retention: 24 hours

Messages per day: 1,000 × 86,400 = 86,400,000
Rows in outbox_consumer_groups per day: 86,400,000 × 50 = 4,320,000,000 (4.3 BILLION)

After 7 days: 30 BILLION rows
After 30 days: 130 BILLION rows ❌
```

**The Compounding Query Overhead:**

Every operation touches this table:

**1. Message Fetch (Consumer Query)**
```sql
-- EVERY consumer query checks NOT EXISTS
SELECT o.id, o.payload, ...
FROM outbox o
WHERE o.topic = $1
  AND o.status = 'PENDING'
  AND NOT EXISTS (
      SELECT 1 FROM outbox_consumer_groups cg  -- ✅ Query against BILLIONS of rows
      WHERE cg.message_id = o.id
        AND cg.group_name = $2
  )
LIMIT 100;

-- With 50 consumer groups polling every second:
-- 50 queries/second × NOT EXISTS against 30B rows
-- Even with perfect indexes, this is expensive
```

**2. Message Completion**
```sql
-- EVERY message completion writes to this table
INSERT INTO outbox_consumer_groups (group_name, message_id, processed_at)
VALUES ($1, $2, NOW());

-- 1,000 messages/sec × 50 groups = 50,000 INSERTs/second
-- Index maintenance on 30B row table
-- WAL generation
-- Vacuum overhead
```

**3. Subscription Status Check (from Pitfall 6)**
```sql
-- Check subscription status before marking complete
WITH subscription_check AS (
    SELECT subscription_status
    FROM outbox_topic_subscriptions
    WHERE topic = $1 AND group_name = $2
),
group_processing AS (
    INSERT INTO outbox_consumer_groups (group_name, message_id, processed_at)
    SELECT $2, $3, NOW()
    FROM subscription_check
    WHERE subscription_status = 'ACTIVE'  -- ✅ Extra query overhead
    ON CONFLICT (group_name, message_id) DO NOTHING
    RETURNING message_id
)
...

-- 50,000 completions/second × subscription status check
-- Additional 50,000 queries/second to outbox_topic_subscriptions
```

**4. Dead Consumer Cleanup**
```sql
-- Cleanup checks which messages haven't been processed
UPDATE outbox
SET required_consumer_groups = required_consumer_groups - 1
WHERE topic = $1
  AND status IN ('PENDING', 'PROCESSING')
  AND NOT EXISTS (
      SELECT 1 FROM outbox_consumer_groups cg  -- ✅ Another scan of billions
      WHERE cg.message_id = outbox.id AND cg.group_name = $2
  );

-- For 1M pending messages × NOT EXISTS check
-- Potentially scans billions of rows
```

**The Storage Problem:**

```sql
-- Table size calculation
-- Assuming:
-- - message_id: 8 bytes (BIGINT)
-- - group_name: 50 bytes average (VARCHAR)
-- - processed_at: 8 bytes (TIMESTAMP)
-- - Row overhead: ~30 bytes (PostgreSQL tuple header)
-- Total per row: ~96 bytes

30 billion rows × 96 bytes = 2.88 TB of data
Plus indexes:
- PRIMARY KEY (group_name, message_id): ~1.5 TB
- Index on (message_id, group_name): ~1.5 TB

Total storage: ~6 TB just for tracking message processing! ❌
```

**The Index Maintenance Problem:**

```sql
-- Every INSERT into outbox_consumer_groups updates indexes
-- With 50,000 INSERTs/second:

-- Primary key index maintenance:
-- - Find insertion point in B-tree
-- - Insert new entry
-- - Possibly split pages
-- - Update parent pages
-- = ~5-10ms per INSERT on large index

-- With 30B rows, index depth = log(30B) ≈ 35 levels
-- Each INSERT must traverse 35 levels
-- Cache misses become frequent
-- I/O becomes bottleneck
```

**Solution 1: Aggressive Cleanup of outbox_consumer_groups**

```java
public class ConsumerGroupsCleanup {

    /**
     * Delete processed entries as soon as parent message is deleted.
     * Use CASCADE delete or explicit cleanup.
     */
    public Future<Integer> cleanupProcessedEntries() {
        String sql = """
            DELETE FROM outbox_consumer_groups
            WHERE message_id IN (
                SELECT cg.message_id
                FROM outbox_consumer_groups cg
                LEFT JOIN outbox o ON o.id = cg.message_id
                WHERE o.id IS NULL  -- ✅ Message already deleted
                LIMIT 100000  -- ✅ Batch deletion
            )
            RETURNING message_id
            """;

        return pool.preparedQuery(sql)
            .execute()
            .map(rows -> rows.size());
    }

    /**
     * More aggressive: Delete entries for completed messages immediately.
     */
    public Future<Integer> cleanupCompletedMessageEntries() {
        String sql = """
            DELETE FROM outbox_consumer_groups
            WHERE message_id IN (
                SELECT cg.message_id
                FROM outbox_consumer_groups cg
                INNER JOIN outbox o ON o.id = cg.message_id
                WHERE o.status = 'COMPLETED'
                  AND o.completed_consumer_groups >= o.required_consumer_groups
                  -- ✅ All groups have processed, safe to delete tracking
                LIMIT 100000
            )
            RETURNING message_id
            """;

        return pool.preparedQuery(sql)
            .execute()
            .map(rows -> rows.size());
    }
}

-- Add CASCADE delete to ensure cleanup
ALTER TABLE outbox_consumer_groups
    DROP CONSTRAINT IF EXISTS fk_message_id,
    ADD CONSTRAINT fk_message_id
        FOREIGN KEY (message_id)
        REFERENCES outbox(id)
        ON DELETE CASCADE;  -- ✅ Auto-cleanup when message deleted
```

**Solution 2: Partition outbox_consumer_groups by Time**

```sql
-- Partition by processed_at to enable fast cleanup
CREATE TABLE outbox_consumer_groups (
    id BIGSERIAL,
    message_id BIGINT NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    PRIMARY KEY (processed_at, id),
    UNIQUE (message_id, group_name, processed_at)
) PARTITION BY RANGE (processed_at);

-- Create daily partitions
CREATE TABLE outbox_consumer_groups_2025_11_12
    PARTITION OF outbox_consumer_groups
    FOR VALUES FROM ('2025-11-12 00:00:00') TO ('2025-11-13 00:00:00');

CREATE TABLE outbox_consumer_groups_2025_11_13
    PARTITION OF outbox_consumer_groups
    FOR VALUES FROM ('2025-11-13 00:00:00') TO ('2025-11-14 00:00:00');

-- Cleanup becomes trivial: DROP old partitions
DROP TABLE outbox_consumer_groups_2025_11_05;  -- ✅ Instant, no table scan

-- Queries only scan recent partitions
SELECT 1 FROM outbox_consumer_groups
WHERE message_id = $1 AND group_name = $2;
-- ✅ Only scans today's partition (much smaller)
```

**Solution 3: Use Bloom Filter for "Already Processed" Check**

```java
public class BloomFilterConsumerTracking {

    // ✅ In-memory bloom filter per consumer group
    private final BloomFilter<Long> processedMessages = BloomFilter.create(
        Funnels.longFunnel(),
        100_000_000,  // Expected 100M messages
        0.01          // 1% false positive rate
    );

    private Future<Void> processMessage(Message<T> message) {
        Long messageId = message.getId();

        // ✅ Fast in-memory check (no database query)
        if (processedMessages.mightContain(messageId)) {
            // Possible duplicate - verify with database
            return checkDatabaseForDuplicate(messageId)
                .compose(isDuplicate -> {
                    if (isDuplicate) {
                        logger.debug("Skipping duplicate message {}", messageId);
                        return Future.succeededFuture();
                    } else {
                        // False positive - process message
                        return doProcessMessage(message);
                    }
                });
        } else {
            // Definitely not processed - process message
            return doProcessMessage(message)
                .onSuccess(v -> processedMessages.put(messageId));
        }
    }

    private Future<Boolean> checkDatabaseForDuplicate(Long messageId) {
        String sql = """
            SELECT 1 FROM outbox_consumer_groups
            WHERE message_id = $1 AND group_name = $2
            LIMIT 1
            """;

        return pool.preparedQuery(sql)
            .execute(Tuple.of(messageId, groupName))
            .map(rows -> rows.size() > 0);
    }
}

// ✅ Reduces database queries by ~99%
// ✅ 1% false positive rate means 1% of messages do extra DB check
// ❌ Requires memory: 100M messages × 12 bytes = 1.2 GB per consumer group
// ❌ Lost on restart (must rebuild from database)
```

**Solution 4: Eliminate outbox_consumer_groups Table Entirely**

```sql
-- Alternative design: Track completion in outbox table directly
ALTER TABLE outbox ADD COLUMN completed_by_groups TEXT[];

-- Mark message as processed by group
UPDATE outbox
SET completed_by_groups = array_append(completed_by_groups, $1),
    completed_consumer_groups = array_length(array_append(completed_by_groups, $1), 1),
    status = CASE
        WHEN array_length(array_append(completed_by_groups, $1), 1) >= required_consumer_groups
        THEN 'COMPLETED'
        ELSE status
    END
WHERE id = $2
  AND NOT ($1 = ANY(completed_by_groups));  -- ✅ Idempotent

-- Query for pending messages
SELECT o.id, o.payload, ...
FROM outbox o
WHERE o.topic = $1
  AND o.status = 'PENDING'
  AND NOT ($2 = ANY(o.completed_by_groups))  -- ✅ Simple array check
LIMIT 100;

-- ✅ No separate table
-- ✅ No billions of rows
-- ✅ Simpler queries
-- ❌ Array operations can be slow with many groups
-- ❌ Row size grows with number of groups (50 groups × 20 bytes = 1KB per message)
```

**Solution 5: Hybrid Approach - Bitmap Tracking**

```sql
-- Use bitmap to track which groups have processed message
-- Assign each consumer group a bit position (0-63)
CREATE TABLE outbox_consumer_group_registry (
    topic VARCHAR(255),
    group_name VARCHAR(255),
    bit_position INT,  -- 0-63
    PRIMARY KEY (topic, group_name)
);

-- Track completion with bitmap
ALTER TABLE outbox ADD COLUMN completed_groups_bitmap BIGINT DEFAULT 0;

-- Mark message as processed by group
WITH group_bit AS (
    SELECT bit_position
    FROM outbox_consumer_group_registry
    WHERE topic = $1 AND group_name = $2
)
UPDATE outbox
SET completed_groups_bitmap = completed_groups_bitmap | (1::bigint << (SELECT bit_position FROM group_bit)),
    completed_consumer_groups = bit_count(completed_groups_bitmap | (1::bigint << (SELECT bit_position FROM group_bit))),
    status = CASE
        WHEN bit_count(completed_groups_bitmap | (1::bigint << (SELECT bit_position FROM group_bit))) >= required_consumer_groups
        THEN 'COMPLETED'
        ELSE status
    END
WHERE id = $3;

-- Query for pending messages
WITH group_bit AS (
    SELECT bit_position
    FROM outbox_consumer_group_registry
    WHERE topic = $1 AND group_name = $2
)
SELECT o.id, o.payload, ...
FROM outbox o, group_bit
WHERE o.topic = $1
  AND o.status = 'PENDING'
  AND (o.completed_groups_bitmap & (1::bigint << group_bit.bit_position)) = 0  -- ✅ Bit not set
LIMIT 100;

-- ✅ No separate tracking table
-- ✅ Fixed 8-byte overhead per message
-- ✅ Fast bitwise operations
-- ❌ Limited to 64 consumer groups per topic
-- ❌ Requires group registration with bit assignment
```

**Comparison of Solutions:**

| Solution | Storage Overhead | Query Performance | Complexity | Max Groups | Notes |
|----------|------------------|-------------------|------------|------------|-------|
| **Current (separate table)** | Very High (TB) | Slow (billions of rows) | Low | Unlimited | ❌ Doesn't scale |
| **Aggressive cleanup** | High (GB) | Medium | Low | Unlimited | ✅ Reduces but doesn't eliminate problem |
| **Partitioning** | High (GB) | Good | Medium | Unlimited | ✅ Good for time-based cleanup |
| **Bloom filter** | Medium (GB) | Very Good | High | Unlimited | ✅ Reduces DB queries by 99% |
| **Array tracking** | Medium (KB per msg) | Good | Low | ~100 | ⚠️ Array ops slow with many groups |
| **Bitmap tracking** | Low (8 bytes per msg) | Excellent | Medium | 64 | ✅ Best performance, limited groups |

**Recommended Hybrid Solution:**

```java
// Use bitmap for topics with ≤ 64 consumer groups (most cases)
// Use separate table with aggressive cleanup for topics with > 64 groups

public class HybridConsumerTracking {

    public Future<Void> markMessageCompleted(Long messageId, String topic, String groupName) {
        return getConsumerGroupCount(topic)
            .compose(count -> {
                if (count <= 64) {
                    return markCompletedBitmap(messageId, topic, groupName);
                } else {
                    return markCompletedSeparateTable(messageId, topic, groupName);
                }
            });
    }
}
```

---

## Scalability Analysis: Guaranteed Limits and Bottlenecks

### Overview: What Determines Maximum Throughput?

The maximum sustainable throughput is determined by the **slowest operation** in the critical path:

```
Message Flow:
1. Producer INSERT into outbox                    [Write Operation]
2. Consumer SELECT pending messages               [Read Operation]
3. Consumer process message                       [Application Logic]
4. Consumer mark message completed                [Write Operation]
5. Cleanup DELETE completed messages              [Write Operation]

Bottleneck = max(INSERT rate, SELECT rate, completion rate, cleanup rate)
```

### Baseline: PostgreSQL Performance Limits

**Hardware Assumptions (Modern Server):**
- CPU: 32 cores
- RAM: 128 GB
- Storage: NVMe SSD (500K IOPS, 3 GB/s throughput)
- Network: 10 Gbps

**PostgreSQL Theoretical Limits:**
- Simple INSERTs: ~50,000/second (single table, no triggers)
- Simple SELECTs: ~100,000/second (indexed queries)
- Simple UPDATEs: ~40,000/second (indexed, row-level locks)
- Simple DELETEs: ~30,000/second (with index maintenance)

**Reality Check:**
These are theoretical maximums. Real-world performance is 30-50% of theoretical due to:
- Lock contention
- Index maintenance overhead
- WAL (Write-Ahead Log) I/O
- Vacuum overhead
- Network latency
- Connection pool limits

### Solution 1: Current Design (Separate outbox_consumer_groups Table)

**Architecture:**
```
outbox table: Stores messages
outbox_consumer_groups table: Tracks which groups processed which messages
outbox_topic_subscriptions table: Tracks active consumer groups
```

**Scalability Analysis:**

**Message Production Rate:**
```sql
-- Producer INSERT
INSERT INTO outbox (topic, payload, status, required_consumer_groups)
VALUES ($1, $2, 'PENDING', $3);

-- With trigger to set required_consumer_groups:
-- 1. Check outbox_topics (cached, fast)
-- 2. Count active subscriptions (cached, fast)
-- 3. INSERT into outbox (main cost)

Bottleneck: INSERT throughput
Theoretical: 50,000 INSERTs/second
With trigger overhead: ~30,000 INSERTs/second
With application-level caching: ~40,000 INSERTs/second ✅

Maximum Message Rate: 40,000 messages/second
```

**Message Consumption Rate:**
```sql
-- Consumer SELECT (per consumer group)
SELECT o.id, o.payload, ...
FROM outbox o
WHERE o.topic = $1
  AND o.status = 'PENDING'
  AND NOT EXISTS (
      SELECT 1 FROM outbox_consumer_groups cg
      WHERE cg.message_id = o.id AND cg.group_name = $2
  )
LIMIT 100;

-- Cost analysis:
-- 1. Index scan on outbox (topic, status, created_at): Fast
-- 2. NOT EXISTS check against outbox_consumer_groups: SLOW
--    - With 30B rows: ~10ms per query (even with perfect index)
--    - With 1B rows: ~2ms per query
--    - With 100M rows: ~0.5ms per query

With 50 consumer groups polling every 1 second:
- 50 queries/second × 10ms = 500ms of database time per second
- Sustainable ✅

With 100 consumer groups polling every 1 second:
- 100 queries/second × 10ms = 1,000ms of database time per second
- At capacity limit ⚠️

With 200 consumer groups polling every 1 second:
- 200 queries/second × 10ms = 2,000ms of database time per second
- UNSUSTAINABLE ❌ (need 2 CPUs just for SELECT queries)

Maximum Consumer Groups: ~100 (with aggressive cleanup keeping table < 1B rows)
```

**Message Completion Rate:**
```sql
-- Consumer completion (per message per group)
INSERT INTO outbox_consumer_groups (group_name, message_id, processed_at)
VALUES ($1, $2, NOW());

UPDATE outbox
SET completed_consumer_groups = completed_consumer_groups + 1
WHERE id = $2;

-- Cost: 1 INSERT + 1 UPDATE per completion
-- With 50 consumer groups × 1,000 messages/second:
--   50,000 INSERTs/second into outbox_consumer_groups
--   50,000 UPDATEs/second to outbox

Bottleneck: INSERT into outbox_consumer_groups
Theoretical: 50,000 INSERTs/second
With index maintenance on 30B rows: ~10,000 INSERTs/second ❌

Maximum Throughput: 10,000 completions/second
With 50 consumer groups: 10,000 / 50 = 200 messages/second ❌
With 10 consumer groups: 10,000 / 10 = 1,000 messages/second ✅
```

**Cleanup Rate:**
```sql
-- Cleanup job
DELETE FROM outbox
WHERE status = 'COMPLETED'
  AND processed_at < NOW() - INTERVAL '24 hours'
LIMIT 10000;

-- With 1,000 messages/second × 86,400 seconds = 86M messages/day
-- Must delete 86M messages/day = 1,000 messages/second

Bottleneck: DELETE throughput
Theoretical: 30,000 DELETEs/second
With CASCADE to outbox_consumer_groups (50 rows per message): 30,000 / 50 = 600 DELETEs/second ❌

Maximum Cleanup Rate: 600 messages/second
If producing 1,000 messages/second, cleanup falls behind by 400 messages/second
After 1 day: 34.5M messages backlog ❌
```

**VERDICT: Current Design Scalability Limits**

| Metric | Limit | Bottleneck |
|--------|-------|------------|
| **Maximum Message Rate** | **200 messages/second** | Message completion INSERTs |
| **Maximum Consumer Groups** | **100 groups** | SELECT query overhead |
| **Maximum Topics** | **Unlimited** | (Partitioned by topic) |
| **Maximum Retention** | **7 days** | Cleanup rate vs. production rate |
| **Storage Growth** | **~6 TB/month** | outbox_consumer_groups table |

**Conclusion:** ❌ **NOT SUITABLE FOR HIGH-THROUGHPUT SCENARIOS**

---

### Solution 2: Bitmap Tracking (Recommended)

**Architecture:**
```
outbox table: Stores messages + completed_groups_bitmap (BIGINT)
outbox_consumer_group_registry: Maps group names to bit positions (0-63)
outbox_topic_subscriptions: Tracks active consumer groups
```

**Why This Solves the Scalability Issues:**

**1. Eliminates Billion-Row Table**
```
Before: outbox_consumer_groups with 30B rows
After: outbox table with completed_groups_bitmap column (8 bytes per message)

Storage reduction: 6 TB → ~700 MB (8 bytes × 86M messages)
Reduction factor: 8,500x ✅
```

**2. Eliminates NOT EXISTS Subquery**
```sql
-- Before: NOT EXISTS against 30B rows
SELECT o.id, o.payload, ...
FROM outbox o
WHERE o.topic = $1
  AND o.status = 'PENDING'
  AND NOT EXISTS (
      SELECT 1 FROM outbox_consumer_groups cg  -- ❌ Slow
      WHERE cg.message_id = o.id AND cg.group_name = $2
  );

-- After: Bitwise AND operation
WITH group_bit AS (
    SELECT bit_position FROM outbox_consumer_group_registry
    WHERE topic = $1 AND group_name = $2
)
SELECT o.id, o.payload, ...
FROM outbox o, group_bit
WHERE o.topic = $1
  AND o.status = 'PENDING'
  AND (o.completed_groups_bitmap & (1::bigint << group_bit.bit_position)) = 0;  -- ✅ Fast

Query time reduction: 10ms → 0.1ms
Speedup: 100x ✅
```

**3. Eliminates Separate INSERT on Completion**
```sql
-- Before: INSERT + UPDATE
INSERT INTO outbox_consumer_groups (group_name, message_id, processed_at)
VALUES ($1, $2, NOW());  -- ❌ Expensive

UPDATE outbox SET completed_consumer_groups = completed_consumer_groups + 1
WHERE id = $2;

-- After: Single UPDATE with bitwise OR
WITH group_bit AS (
    SELECT bit_position FROM outbox_consumer_group_registry
    WHERE topic = $1 AND group_name = $2
)
UPDATE outbox
SET completed_groups_bitmap = completed_groups_bitmap | (1::bigint << (SELECT bit_position FROM group_bit)),
    completed_consumer_groups = bit_count(completed_groups_bitmap | (1::bigint << (SELECT bit_position FROM group_bit)))
WHERE id = $3;

Write reduction: 2 operations → 1 operation
Throughput increase: 2x ✅
```

**Scalability Analysis:**

**Message Production Rate:**
```sql
-- Producer INSERT (unchanged)
INSERT INTO outbox (topic, payload, status, required_consumer_groups, completed_groups_bitmap)
VALUES ($1, $2, 'PENDING', $3, 0);

Bottleneck: INSERT throughput
Maximum: 40,000 messages/second ✅ (same as before)
```

**Message Consumption Rate:**
```sql
-- Consumer SELECT with bitmap check
WITH group_bit AS (
    SELECT bit_position FROM outbox_consumer_group_registry
    WHERE topic = $1 AND group_name = $2  -- ✅ Cached, ~50 rows total
)
SELECT o.id, o.payload, ...
FROM outbox o, group_bit
WHERE o.topic = $1
  AND o.status = 'PENDING'
  AND (o.completed_groups_bitmap & (1::bigint << group_bit.bit_position)) = 0
LIMIT 100;

-- Cost: Index scan + bitwise AND (CPU operation, no I/O)
-- Query time: ~0.1ms (100x faster than NOT EXISTS)

With 64 consumer groups polling every 1 second:
- 64 queries/second × 0.1ms = 6.4ms of database time per second
- Sustainable ✅

With 1,000 consumer groups polling every 1 second:
- ❌ Not possible (limited to 64 groups per topic)

Maximum Consumer Groups per Topic: 64 (hard limit) ⚠️
Maximum Total Consumer Groups: Unlimited (across different topics)
```

**Message Completion Rate:**
```sql
-- Consumer completion (single UPDATE)
WITH group_bit AS (
    SELECT bit_position FROM outbox_consumer_group_registry
    WHERE topic = $1 AND group_name = $2
)
UPDATE outbox
SET completed_groups_bitmap = completed_groups_bitmap | (1::bigint << (SELECT bit_position FROM group_bit)),
    completed_consumer_groups = bit_count(completed_groups_bitmap | (1::bigint << (SELECT bit_position FROM group_bit))),
    status = CASE
        WHEN bit_count(completed_groups_bitmap | (1::bigint << (SELECT bit_position FROM group_bit))) >= required_consumer_groups
        THEN 'COMPLETED'
        ELSE status
    END
WHERE id = $3;

-- Cost: 1 UPDATE (no INSERT)
-- With 64 consumer groups × 10,000 messages/second:
--   640,000 UPDATEs/second to outbox

Bottleneck: UPDATE throughput with row-level lock contention
Theoretical: 40,000 UPDATEs/second
With batching (100 messages per batch): 40,000 × 100 = 4,000,000 completions/second ✅

Maximum Throughput: 4,000,000 completions/second
With 64 consumer groups: 4,000,000 / 64 = 62,500 messages/second ✅
With 10 consumer groups: 4,000,000 / 10 = 400,000 messages/second ✅
```

**Cleanup Rate:**
```sql
-- Cleanup job (simpler, no CASCADE)
DELETE FROM outbox
WHERE status = 'COMPLETED'
  AND processed_at < NOW() - INTERVAL '24 hours'
LIMIT 10000;

-- No CASCADE delete to outbox_consumer_groups
-- Just delete from outbox table

Bottleneck: DELETE throughput
Theoretical: 30,000 DELETEs/second
With batching: Sustainable for 30,000 messages/second ✅

Maximum Cleanup Rate: 30,000 messages/second
```

**VERDICT: Bitmap Tracking Scalability Limits**

| Metric | Limit | Bottleneck | Notes |
|--------|-------|------------|-------|
| **Maximum Message Rate** | **30,000 messages/second** | Cleanup rate | Can burst higher, sustained by cleanup |
| **Maximum Consumer Groups per Topic** | **64 groups** | BIGINT bitmap size | Hard limit ⚠️ |
| **Maximum Total Consumer Groups** | **Unlimited** | N/A | Across different topics |
| **Maximum Topics** | **Unlimited** | N/A | Partitioned by topic |
| **Maximum Retention** | **30 days** | Storage (2.5 TB/month) | Configurable |
| **Storage Growth** | **~700 MB/day** | Disk space | 8 bytes × 86M messages |

**Conclusion:** ✅ **SUITABLE FOR HIGH-THROUGHPUT SCENARIOS**

**Limitations:**
- ⚠️ **64 consumer groups per topic** (hard limit due to BIGINT size)
- ⚠️ **30,000 sustained messages/second** (limited by cleanup rate)
- ⚠️ **Burst to 62,500 messages/second** (limited by completion rate)

**When to Use:**
- ✅ Topics with ≤ 64 consumer groups
- ✅ Message rates < 30,000/second sustained
- ✅ Need for high fan-out (many consumer groups)
- ✅ Long retention periods (weeks/months)

**When NOT to Use:**
- ❌ Topics with > 64 consumer groups
- ❌ Message rates > 50,000/second sustained
- ❌ Need for unlimited consumer groups per topic

---

### Solution 3: Partitioned outbox_consumer_groups with Aggressive Cleanup

**Architecture:**
```
outbox table: Stores messages (partitioned by created_at)
outbox_consumer_groups table: Tracks processing (partitioned by processed_at)
outbox_topic_subscriptions: Tracks active consumer groups
```

**Why This Solves Some Scalability Issues:**

**1. Reduces Query Scope via Partitioning**
```sql
-- Before: NOT EXISTS scans entire 30B row table
SELECT 1 FROM outbox_consumer_groups cg
WHERE cg.message_id = $1 AND cg.group_name = $2;
-- Scans: 30B rows across all partitions

-- After: NOT EXISTS scans only recent partitions
CREATE TABLE outbox_consumer_groups (
    ...
    processed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
) PARTITION BY RANGE (processed_at);

-- Query automatically scans only relevant partition
SELECT 1 FROM outbox_consumer_groups cg
WHERE cg.message_id = $1 AND cg.group_name = $2;
-- Scans: ~4B rows (today's partition only)

Query time reduction: 10ms → 3ms
Speedup: 3.3x ✅ (but still slow)
```

**2. Enables Fast Cleanup via Partition Dropping**
```sql
-- Before: DELETE scans and deletes millions of rows
DELETE FROM outbox_consumer_groups
WHERE processed_at < NOW() - INTERVAL '7 days';
-- Time: Hours, generates massive WAL

-- After: DROP partition instantly
DROP TABLE outbox_consumer_groups_2025_11_05;
-- Time: Milliseconds, no WAL ✅

Cleanup time reduction: Hours → Milliseconds
Speedup: 1,000,000x ✅
```

**3. Reduces Index Size per Partition**
```sql
-- Before: Single index on 30B rows
-- Index size: ~1.5 TB
-- Index depth: 35 levels
-- Cache hit rate: Low (index doesn't fit in memory)

-- After: 7 daily partitions × 4B rows each
-- Index size per partition: ~200 GB
-- Index depth: 32 levels
-- Cache hit rate: Higher (recent partitions fit in memory)

Index lookup time reduction: 10ms → 5ms
Speedup: 2x ✅
```

**Scalability Analysis:**

**Message Production Rate:**
```sql
-- Producer INSERT (unchanged)
INSERT INTO outbox (topic, payload, status, required_consumer_groups)
VALUES ($1, $2, 'PENDING', $3);

Maximum: 40,000 messages/second ✅ (same as baseline)
```

**Message Consumption Rate:**
```sql
-- Consumer SELECT with NOT EXISTS (still slow, but faster)
SELECT o.id, o.payload, ...
FROM outbox o
WHERE o.topic = $1
  AND o.status = 'PENDING'
  AND NOT EXISTS (
      SELECT 1 FROM outbox_consumer_groups cg  -- ✅ Scans only recent partition
      WHERE cg.message_id = o.id AND cg.group_name = $2
  )
LIMIT 100;

-- Query time: ~3ms (vs. 10ms without partitioning)

With 100 consumer groups polling every 1 second:
- 100 queries/second × 3ms = 300ms of database time per second
- Sustainable ✅

With 300 consumer groups polling every 1 second:
- 300 queries/second × 3ms = 900ms of database time per second
- Near capacity ⚠️

Maximum Consumer Groups: ~300 (3x improvement over baseline) ✅
```

**Message Completion Rate:**
```sql
-- Consumer completion (still 2 operations)
INSERT INTO outbox_consumer_groups (group_name, message_id, processed_at)
VALUES ($1, $2, NOW());  -- ✅ Goes to today's partition (smaller index)

UPDATE outbox
SET completed_consumer_groups = completed_consumer_groups + 1
WHERE id = $2;

-- INSERT into smaller partition index
-- With 4B rows per partition vs. 30B total:
--   Index maintenance: 5ms vs. 10ms

Bottleneck: INSERT into outbox_consumer_groups
With partitioning: ~20,000 INSERTs/second (vs. 10,000 baseline)

Maximum Throughput: 20,000 completions/second
With 50 consumer groups: 20,000 / 50 = 400 messages/second ✅
With 100 consumer groups: 20,000 / 100 = 200 messages/second ✅
```

**Cleanup Rate:**
```sql
-- Cleanup job (DROP partition)
DROP TABLE outbox_consumer_groups_2025_11_05;  -- ✅ Instant

-- Also cleanup outbox table
DELETE FROM outbox
WHERE status = 'COMPLETED'
  AND processed_at < NOW() - INTERVAL '7 days'
LIMIT 10000;

Bottleneck: DELETE from outbox (no CASCADE overhead)
Maximum: 30,000 DELETEs/second ✅

Maximum Cleanup Rate: 30,000 messages/second
```

**VERDICT: Partitioned Table Scalability Limits**

| Metric | Limit | Bottleneck | Notes |
|--------|-------|------------|-------|
| **Maximum Message Rate** | **400 messages/second** | Message completion INSERTs | 2x improvement over baseline |
| **Maximum Consumer Groups** | **300 groups** | SELECT query overhead | 3x improvement over baseline |
| **Maximum Topics** | **Unlimited** | N/A | Partitioned by topic |
| **Maximum Retention** | **30 days** | Storage (2 TB/month) | Partition dropping enables longer retention |
| **Storage Growth** | **~2 TB/month** | Disk space | Still significant |

**Conclusion:** ⚠️ **MODERATE IMPROVEMENT, STILL NOT SUITABLE FOR HIGH-THROUGHPUT**

**Limitations:**
- ⚠️ **400 messages/second sustained** (limited by completion rate)
- ⚠️ **300 consumer groups** (limited by query overhead)
- ⚠️ **Still requires 2 TB/month storage** (outbox_consumer_groups table)

**When to Use:**
- ✅ Need for > 64 consumer groups per topic
- ✅ Message rates 200-400/second
- ✅ Can tolerate 2 TB/month storage growth
- ✅ Want simpler migration from current design

**When NOT to Use:**
- ❌ Message rates > 1,000/second
- ❌ Need for minimal storage overhead
- ❌ Want maximum performance

---

### Solution 4: Bloom Filter + Separate Table

**Architecture:**
```
outbox table: Stores messages
outbox_consumer_groups table: Tracks processing (with aggressive cleanup)
In-memory Bloom filter per consumer group: Fast duplicate detection
```

**Why This Solves Some Scalability Issues:**

**1. Eliminates 99% of Database Queries**
```java
// Before: Every message fetch checks database
SELECT 1 FROM outbox_consumer_groups
WHERE message_id = $1 AND group_name = $2;
// 100 messages × 50 groups = 5,000 queries/second

// After: Bloom filter checks in-memory first
if (bloomFilter.mightContain(messageId)) {
    // Only 1% false positive rate
    // 100 messages × 50 groups × 0.01 = 50 queries/second ✅
}

Query reduction: 5,000 → 50 queries/second
Reduction factor: 100x ✅
```

**2. Reduces Database Load**
```
Before:
- 5,000 SELECT queries/second
- 50,000 INSERT queries/second (completions)
- Total: 55,000 queries/second

After:
- 50 SELECT queries/second (1% false positives)
- 50,000 INSERT queries/second (completions)
- Total: 50,050 queries/second

Load reduction: 9% ✅ (modest improvement)
```

**3. Enables Aggressive Cleanup**
```sql
-- Can cleanup aggressively because Bloom filter handles duplicates
DELETE FROM outbox_consumer_groups
WHERE processed_at < NOW() - INTERVAL '1 hour';  -- ✅ Very short retention

-- Keeps table small: ~3.6M rows (1 hour × 1,000 msg/sec × 50 groups)
-- vs. 4.3B rows (1 day × 1,000 msg/sec × 50 groups)

Table size reduction: 1,200x ✅
```

**Scalability Analysis:**

**Message Production Rate:**
```sql
-- Producer INSERT (unchanged)
Maximum: 40,000 messages/second ✅
```

**Message Consumption Rate:**
```sql
-- Consumer SELECT (still has NOT EXISTS, but against small table)
SELECT o.id, o.payload, ...
FROM outbox o
WHERE o.topic = $1
  AND o.status = 'PENDING'
  AND NOT EXISTS (
      SELECT 1 FROM outbox_consumer_groups cg  -- ✅ Only 3.6M rows
      WHERE cg.message_id = o.id AND cg.group_name = $2
  )
LIMIT 100;

-- Query time: ~0.5ms (vs. 10ms with 30B rows)

With 500 consumer groups polling every 1 second:
- 500 queries/second × 0.5ms = 250ms of database time per second
- Sustainable ✅

Maximum Consumer Groups: ~500 (5x improvement over baseline) ✅
```

**Message Completion Rate:**
```sql
-- Consumer completion (still 2 operations, but faster)
INSERT INTO outbox_consumer_groups (group_name, message_id, processed_at)
VALUES ($1, $2, NOW());  -- ✅ Small table, fast index

-- With 3.6M rows vs. 30B rows:
--   Index maintenance: 1ms vs. 10ms

Bottleneck: INSERT into outbox_consumer_groups
With small table: ~40,000 INSERTs/second

Maximum Throughput: 40,000 completions/second
With 50 consumer groups: 40,000 / 50 = 800 messages/second ✅
With 100 consumer groups: 40,000 / 100 = 400 messages/second ✅
```

**Cleanup Rate:**
```sql
-- Aggressive cleanup (1 hour retention)
DELETE FROM outbox_consumer_groups
WHERE processed_at < NOW() - INTERVAL '1 hour'
LIMIT 100000;

-- Small deletes, frequent
Maximum: 30,000 DELETEs/second ✅
```

**Memory Requirements:**
```java
// Bloom filter memory per consumer group
BloomFilter<Long> filter = BloomFilter.create(
    Funnels.longFunnel(),
    100_000_000,  // 100M messages
    0.01          // 1% false positive rate
);

// Memory: ~120 MB per consumer group
// With 50 consumer groups: 6 GB
// With 100 consumer groups: 12 GB
// With 500 consumer groups: 60 GB ⚠️
```

**VERDICT: Bloom Filter Scalability Limits**

| Metric | Limit | Bottleneck | Notes |
|--------|-------|------------|-------|
| **Maximum Message Rate** | **800 messages/second** | Message completion INSERTs | 4x improvement over baseline |
| **Maximum Consumer Groups** | **500 groups** | Memory (60 GB) | 5x improvement over baseline |
| **Maximum Topics** | **Unlimited** | N/A | Partitioned by topic |
| **Maximum Retention** | **7 days** | Storage (200 GB/week) | Bloom filter allows aggressive cleanup |
| **Storage Growth** | **~30 GB/day** | Disk space | 20x reduction vs. baseline |
| **Memory Requirements** | **120 MB per group** | RAM | Can be expensive with many groups |

**Conclusion:** ✅ **GOOD IMPROVEMENT, SUITABLE FOR MODERATE-THROUGHPUT**

**Limitations:**
- ⚠️ **800 messages/second sustained** (limited by completion rate)
- ⚠️ **500 consumer groups** (limited by memory: 60 GB)
- ⚠️ **Bloom filter lost on restart** (must rebuild from database)
- ⚠️ **1% false positive rate** (extra database queries)

**When to Use:**
- ✅ Need for > 64 consumer groups per topic
- ✅ Message rates 400-800/second
- ✅ Have sufficient RAM (120 MB per consumer group)
- ✅ Can tolerate 1% false positive rate
- ✅ Want to reduce database load

**When NOT to Use:**
- ❌ Message rates > 1,000/second
- ❌ Limited RAM (< 10 GB available)
- ❌ Frequent restarts (Bloom filter rebuild overhead)
- ❌ Need for zero false positives

---

### Solution Comparison: Scalability Matrix

| Solution | Max msg/sec | Max Groups/Topic | Max Total Groups | Storage/Month | Memory/Group | Complexity |
|----------|-------------|------------------|------------------|---------------|--------------|------------|
| **Current (Separate Table)** | 200 | 100 | Unlimited | 6 TB | Minimal | Low |
| **Bitmap Tracking** | **30,000** | **64** | Unlimited | 700 MB | Minimal | Medium |
| **Partitioned Table** | 400 | 300 | Unlimited | 2 TB | Minimal | Medium |
| **Bloom Filter** | 800 | 500 | Unlimited | 30 GB | 120 MB | High |
| **Hybrid (Bitmap + Partitioned)** | **30,000** | Unlimited | Unlimited | 1 TB | Minimal | High |

### Recommended Architecture by Use Case

**Use Case 1: High Throughput, Limited Fan-Out**
```
Scenario: 10,000 messages/second, 10 consumer groups
Recommendation: Bitmap Tracking ✅

Why:
- 10 groups << 64 limit
- 10,000 msg/sec << 30,000 limit
- Minimal storage overhead
- Best performance

Limits:
- Max: 30,000 messages/second
- Max: 64 consumer groups per topic
```

**Use Case 2: Moderate Throughput, High Fan-Out**
```
Scenario: 500 messages/second, 100 consumer groups
Recommendation: Bloom Filter + Partitioned Table ✅

Why:
- 100 groups > 64 (can't use bitmap)
- 500 msg/sec < 800 limit
- Acceptable memory: 12 GB
- Good performance

Limits:
- Max: 800 messages/second
- Max: 500 consumer groups
- Memory: 12 GB
```

**Use Case 3: Low Throughput, Many Consumer Groups**
```
Scenario: 100 messages/second, 200 consumer groups
Recommendation: Partitioned Table ✅

Why:
- 200 groups > 64 (can't use bitmap)
- 100 msg/sec << 400 limit
- Don't need Bloom filter complexity
- Simple to implement

Limits:
- Max: 400 messages/second
- Max: 300 consumer groups
```

**Use Case 4: Very High Throughput, Moderate Fan-Out**
```
Scenario: 50,000 messages/second, 50 consumer groups
Recommendation: Hybrid (Bitmap for hot topics, Partitioned for others) ✅

Why:
- 50,000 msg/sec > 30,000 single-topic limit
- Use multiple topics to shard load
- Bitmap for each topic (50 groups < 64)
- Distribute across topics

Architecture:
- Topic "orders.events.shard-0" → Bitmap (10,000 msg/sec, 50 groups)
- Topic "orders.events.shard-1" → Bitmap (10,000 msg/sec, 50 groups)
- Topic "orders.events.shard-2" → Bitmap (10,000 msg/sec, 50 groups)
- Topic "orders.events.shard-3" → Bitmap (10,000 msg/sec, 50 groups)
- Topic "orders.events.shard-4" → Bitmap (10,000 msg/sec, 50 groups)
Total: 50,000 msg/sec ✅

Limits:
- Max: Unlimited (via sharding)
- Max: 64 consumer groups per shard
```

**Use Case 5: Extreme Fan-Out**
```
Scenario: 100 messages/second, 1,000 consumer groups
Recommendation: Current Design with Aggressive Cleanup ⚠️

Why:
- 1,000 groups >> 64 (can't use bitmap)
- 1,000 groups >> 500 (Bloom filter too expensive: 120 GB memory)
- Low throughput (100 msg/sec << 200 limit)
- Accept storage overhead for extreme fan-out

Optimization:
- Aggressive cleanup (1 hour retention in outbox_consumer_groups)
- Partition by processed_at
- Use read replicas for consumer queries

Limits:
- Max: 200 messages/second
- Max: 1,000+ consumer groups
- Storage: ~500 GB/month
```

### Scalability Recommendations Summary

| Concern | Impact | Solution | Complexity | Effectiveness | Scalability Gain |
|---------|--------|----------|------------|---------------|------------------|
| **Heartbeat Contention** | Medium | Randomize timing | Low | High | Eliminates thundering herd |
| **Trigger Performance** | High | Application-level cache | Medium | High | 30K → 40K msg/sec |
| **Query Performance** | High | Covering indexes + anti-join | Low | High | 10ms → 0.1ms per query |
| **Write Amplification** | High | Batch completions | Medium | High | 2x throughput increase |
| **Cleanup Performance** | Medium | Incremental batches | Low | Medium | Prevents backlog |
| **Hot Partitions** | High | Table partitioning | High | Very High | Distributes load |
| **Consumer Groups Table Growth** | **CRITICAL** | Bitmap tracking | Medium | Very High | **200 → 30,000 msg/sec** |

### Final Scalability Limits by Solution

| Metric | Current Design | Bitmap | Partitioned | Bloom Filter | Hybrid |
|--------|----------------|--------|-------------|--------------|--------|
| **Max Messages/Second** | 200 | **30,000** | 400 | 800 | **Unlimited** |
| **Max Consumer Groups/Topic** | 100 | **64** | 300 | 500 | **64** |
| **Max Total Consumer Groups** | Unlimited | Unlimited | Unlimited | Unlimited | Unlimited |
| **Max Topics** | Unlimited | Unlimited | Unlimited | Unlimited | Unlimited |
| **Storage per 1M Messages** | 6 GB | **8 MB** | 2 GB | 300 MB | **8 MB** |
| **Memory per Consumer Group** | Minimal | Minimal | Minimal | 120 MB | Minimal |
| **Query Latency (SELECT)** | 10ms | **0.1ms** | 3ms | 0.5ms | **0.1ms** |
| **Completion Latency** | 15ms | **5ms** | 10ms | 8ms | **5ms** |
| **Cleanup Efficiency** | Poor | **Excellent** | Good | Good | **Excellent** |

### Key Takeaways

**For Production Deployments:**

1. **Use Bitmap Tracking for 90% of use cases**
   - Handles up to 30,000 messages/second
   - Supports up to 64 consumer groups per topic
   - Minimal storage overhead (8 bytes per message)
   - Best query performance (0.1ms)

2. **Use Hybrid Approach for extreme scale**
   - Shard hot topics across multiple bitmap-tracked topics
   - Can scale to unlimited throughput
   - Maintains 64 consumer group limit per shard

3. **Use Bloom Filter for high fan-out (> 64 groups)**
   - Supports up to 500 consumer groups
   - Handles up to 800 messages/second
   - Requires significant memory (120 MB per group)

4. **Avoid Current Design for production**
   - Limited to 200 messages/second
   - Storage grows at 6 TB/month
   - Query performance degrades over time

**Critical Bottlenecks to Monitor:**

1. **outbox_consumer_groups table size** (Current Design)
   - Alert when > 1 billion rows
   - Cleanup aggressively

2. **Completion rate vs. production rate**
   - Alert when completion lag > 1 minute
   - Scale consumers or reduce production rate

3. **Cleanup rate vs. production rate**
   - Alert when cleanup falls behind
   - Increase cleanup frequency or batch size

4. **Memory usage** (Bloom Filter)
   - Alert when > 80% of available RAM
   - Reduce consumer groups or switch to bitmap

**Recommended Configuration for High-Scale Deployments:**

```java
// For topics with > 1,000 messages/second
TopicConfiguration config = TopicConfiguration.pubSub("orders.events", Duration.ofHours(24))
    .withHeartbeatInterval(Duration.ofSeconds(30))  // More frequent
    .withHeartbeatTimeout(Duration.ofMinutes(2))    // Faster detection
    .withBatchSize(100)                              // Larger batches
    .withCompletionBatchInterval(Duration.ofSeconds(5))  // Batch completions
    .withPartitioning(PartitionStrategy.BY_MONTH)   // Partition by month
    .withCleanupBatchSize(10000)                    // Larger cleanup batches
    .withDedicatedTable(true);                      // Separate table for hot topic

// For topics with < 100 messages/second
TopicConfiguration config = TopicConfiguration.pubSub("admin.events", Duration.ofHours(24))
    .withHeartbeatInterval(Duration.ofMinutes(1))   // Standard
    .withHeartbeatTimeout(Duration.ofMinutes(5))    // Standard
    .withBatchSize(10)                               // Smaller batches
    .withPartitioning(PartitionStrategy.NONE);      // No partitioning needed
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

### Consumer Group Lifecycle Validation

Consumer groups must have at least one member before starting (see Critical Gap 2).

```java
public class OutboxConsumerGroup<T> {
    private final Map<String, OutboxConsumerGroupMember<T>> members = new ConcurrentHashMap<>();
    private volatile boolean started = false;

    public void addConsumer(String consumerId, MessageHandler<T> handler) {
        addConsumer(consumerId, handler, null);
    }

    public void addConsumer(String consumerId, MessageHandler<T> handler, Predicate<Message<T>> filter) {
        if (members.containsKey(consumerId)) {
            throw new IllegalArgumentException("Consumer '" + consumerId + "' already exists in group '" + groupName + "'");
        }

        OutboxConsumerGroupMember<T> member = new OutboxConsumerGroupMember<>(consumerId, handler, filter);
        members.put(consumerId, member);

        if (started) {
            member.start();
        }

        logger.info("Added consumer '{}' to group '{}'", consumerId, groupName);
    }

    public void removeConsumer(String consumerId) {
        if (started && members.size() <= 1) {
            throw new IllegalStateException(
                "Cannot remove last consumer from started group '" + groupName + "'. " +
                "Stop the group first or add another consumer."
            );
        }

        OutboxConsumerGroupMember<T> member = members.remove(consumerId);
        if (member != null) {
            member.stop();
            logger.info("Removed consumer '{}' from group '{}'", consumerId, groupName);
        }
    }

    @Override
    public void start(SubscriptionOptions options) {
        // Fail-fast validation
        if (members.isEmpty()) {
            throw new IllegalStateException(
                "Cannot start consumer group '" + groupName + "' with zero members. " +
                "Add at least one consumer before calling start()."
            );
        }

        TopicSemantics semantics = getTopicSemantics(topic);

        if (semantics == TopicSemantics.PUB_SUB) {
            registerSubscription(options)
                .compose(v -> {
                    if (options.isBackfillExisting()) {
                        return backfillRequiredConsumerGroups(options);
                    }
                    return Future.succeededFuture();
                })
                .compose(v -> startPolling())
                .onSuccess(v -> {
                    started = true;
                    logger.info("Consumer group '{}' started with {} members", groupName, members.size());
                });
        } else {
            // Queue semantics - no registration needed
            startPolling();
            started = true;
            logger.info("Consumer group '{}' started with {} members", groupName, members.size());
        }
    }
}
```

### Consumer Selection with Safe Hash Calculation

The consumer selection algorithm uses safe hash calculation to avoid the `Integer.MIN_VALUE` edge case (see Major Gap 4).

```java
public class OutboxConsumerGroup<T> {

    private CompletableFuture<Void> distributeMessage(Message<T> message) {
        // Apply group-level filter first
        if (groupFilter != null && !groupFilter.test(message)) {
            totalMessagesFiltered.incrementAndGet();
            logger.debug("Message {} filtered out by group filter", message.getId());
            return CompletableFuture.completedFuture(null);
        }

        // Find eligible consumers (those whose filters accept the message)
        List<OutboxConsumerGroupMember<T>> eligibleConsumers = members.values().stream()
            .filter(OutboxConsumerGroupMember::isActive)
            .filter(member -> member.acceptsMessage(message))
            .toList();

        if (eligibleConsumers.isEmpty()) {
            // All member filters rejected the message - treat as successfully processed
            totalMessagesFiltered.incrementAndGet();
            logger.debug("Message {} filtered by all members in group '{}'", message.getId(), groupName);
            return CompletableFuture.completedFuture(null);
        }

        // Select consumer using safe hash calculation
        OutboxConsumerGroupMember<T> selectedConsumer = selectConsumer(eligibleConsumers, message);

        logger.debug("Distributing message {} to consumer '{}' in group '{}'",
            message.getId(), selectedConsumer.getConsumerId(), groupName);

        return selectedConsumer.processMessage(message)
            .whenComplete((result, error) -> {
                if (error != null) {
                    totalMessagesFailed.incrementAndGet();
                } else {
                    totalMessagesProcessed.incrementAndGet();
                }
            });
    }

    /**
     * Selects a consumer from eligible consumers using consistent hash-based load balancing.
     * Uses bitwise AND to ensure non-negative index (handles Integer.MIN_VALUE edge case).
     */
    private OutboxConsumerGroupMember<T> selectConsumer(
            List<OutboxConsumerGroupMember<T>> eligibleConsumers,
            Message<T> message) {
        // Use bitwise AND with Integer.MAX_VALUE to ensure non-negative value
        // This handles the edge case where Math.abs(Integer.MIN_VALUE) == Integer.MIN_VALUE
        int index = (message.getId().hashCode() & Integer.MAX_VALUE) % eligibleConsumers.size();
        return eligibleConsumers.get(index);
    }
}
```

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

## Pre-GA Decision Checkpoints

This section documents the **mandatory load tests and chaos engineering scenarios** that must be completed before declaring the fan-out design production-ready (GA).

### Critical: Load Test Fanout Write Amplification

**Objective**: Prove that the chosen completion tracking mode can sustain target throughput without causing table bloat, WAL saturation, or query latency degradation.

#### Test Matrix

| Test ID | Mode | Consumer Groups | Msg/Sec | Duration | Success Criteria |
|---------|------|-----------------|---------|----------|------------------|
| **LT-1** | Reference Counting | 4 | 10,000 | 1 hour | All thresholds met |
| **LT-2** | Reference Counting | 8 | 10,000 | 1 hour | All thresholds met |
| **LT-3** | Reference Counting | 16 | 10,000 | 1 hour | All thresholds met |
| **LT-4** | Reference Counting | 32 | 10,000 | 1 hour | **Expected to FAIL** (prove limit) |
| **LT-5** | Offset/Watermark | 16 | 10,000 | 1 hour | All thresholds met |
| **LT-6** | Offset/Watermark | 64 | 10,000 | 1 hour | All thresholds met |
| **LT-7** | Offset/Watermark | 100 | 30,000 | 1 hour | All thresholds met |

#### Success Thresholds

**Table Bloat** (measured via `pg_stat_user_tables`):
```sql
SELECT schemaname, tablename,
       pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS total_size,
       n_dead_tup,
       ROUND(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup, 0), 2) AS bloat_pct
FROM pg_stat_user_tables
WHERE tablename IN ('outbox', 'outbox_consumer_groups', 'outbox_subscription_offsets')
ORDER BY n_dead_tup DESC;
```
- ✅ **Pass**: `bloat_pct < 20%` for all tables
- ❌ **Fail**: `bloat_pct >= 20%` for any table

**WAL Generation Rate** (measured via `pg_stat_wal`):
```sql
SELECT wal_bytes,
       ROUND(wal_bytes / (1024.0 * 1024.0 * 1024.0), 2) AS wal_gb,
       ROUND(wal_bytes / 3600.0 / (1024.0 * 1024.0), 2) AS wal_mb_per_sec
FROM pg_stat_wal;
```
- ✅ **Pass**: WAL rate < 50% of disk I/O capacity
- ❌ **Fail**: WAL rate >= 50% of disk I/O capacity

**Cleanup Throughput**:
```sql
-- Measure cleanup rate
SELECT COUNT(*) AS messages_deleted
FROM outbox
WHERE status = 'COMPLETED'
  AND processed_at < NOW() - INTERVAL '1 hour';

-- Run cleanup job and measure duration
-- Cleanup rate = messages_deleted / duration_seconds
```
- ✅ **Pass**: Cleanup rate > production rate (messages/sec)
- ❌ **Fail**: Cleanup rate <= production rate

**Query Latency** (consumer fetch queries):
```sql
-- Enable pg_stat_statements
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Measure p95 latency for consumer fetch query
SELECT query,
       calls,
       ROUND(mean_exec_time::numeric, 2) AS mean_ms,
       ROUND((percentile_cont(0.95) WITHIN GROUP (ORDER BY total_exec_time / calls))::numeric, 2) AS p95_ms
FROM pg_stat_statements
WHERE query LIKE '%FOR UPDATE SKIP LOCKED%'
GROUP BY query, calls, mean_exec_time;
```
- ✅ **Pass**: p95 latency < 10ms
- ❌ **Fail**: p95 latency >= 10ms

#### Test Procedure

1. **Setup**: Clean database, enable monitoring extensions
2. **Warmup**: Run at 10% target rate for 5 minutes
3. **Ramp-up**: Increase to 100% target rate over 5 minutes
4. **Sustained load**: Run at 100% target rate for 1 hour
5. **Measurement**: Capture metrics every 60 seconds
6. **Cooldown**: Stop producers, let consumers drain queue
7. **Analysis**: Evaluate against success thresholds

#### Expected Outcomes

- **Reference Counting**: Should pass up to 8-16 groups, fail at 32+ groups
- **Offset/Watermark**: Should pass at 100+ groups and 30,000 msg/sec

**If Reference Counting fails at < 8 groups**: Design has fundamental flaw, requires rework.

**If Offset/Watermark fails at < 64 groups**: Partition strategy or watermark calculation is inefficient.

---

### Critical: Kill-and-Resurrect Chaos Test

**Objective**: Verify that dead consumer detection, cleanup, and resurrection with bounded backfill keeps database growth stable and doesn't stall other consumer groups.

#### Test Scenario

```
T0: Start 3 consumer groups (email, analytics, inventory) on topic "orders.events"
    - Production rate: 1,000 msg/sec
    - All groups processing normally

T1 (5 minutes): Kill inventory-service (SIGKILL - no graceful shutdown)
    - Verify: No heartbeat sent
    - Verify: Subscription remains ACTIVE

T2 (10 minutes): Dead consumer detection job runs
    - Verify: inventory-service marked DEAD
    - Verify: required_consumer_groups decremented for pending messages
    - Verify: email and analytics continue processing normally

T3 (15 minutes): Cleanup job runs
    - Verify: Messages processed by email + analytics are deleted
    - Verify: Database size stable (not growing unbounded)

T4 (20 minutes): Resurrect inventory-service with bounded backfill
    - Start with SubscriptionOptions.fromBeginning(true, maxBackfill=100_000)
    - Verify: Backfill increments required_consumer_groups for ≤100k messages
    - Verify: Backfill does NOT process all 900,000 messages (15 min × 1k/sec)
    - Verify: Backfill completes within 5 minutes

T5 (25 minutes): Verify steady state
    - Verify: All 3 groups processing new messages
    - Verify: Database size stable
    - Verify: No messages stuck in PENDING
```

#### Success Criteria

- ✅ **Dead detection**: Subscription marked DEAD within 5 minutes of last heartbeat
- ✅ **Cleanup unblocked**: Messages deleted after email + analytics complete (not waiting for inventory)
- ✅ **Database growth bounded**: Size increase < 10% during dead period
- ✅ **Bounded backfill**: Resurrect processes ≤100k messages, not all 900k
- ✅ **No stalls**: email and analytics never blocked by dead or resurrecting inventory
- ✅ **Steady state recovery**: All groups processing normally within 30 minutes

#### Failure Modes to Test

1. **Zombie subscription**: Dead group never detected → messages accumulate forever
2. **Unbounded backfill**: Resurrection processes all 900k messages → OLTP starvation
3. **Cleanup stall**: Dead group blocks cleanup → database fills disk
4. **Cascade failure**: Dead group causes email/analytics to timeout → total outage

**If any failure mode occurs**: Design has critical flaw, requires fix before GA.

---

### Critical: Backfill vs OLTP Contention Test

**Objective**: Prove that backfill operations with adaptive rate limiting do not degrade OLTP query performance beyond acceptable thresholds.

#### Test Scenario

```
Setup:
  - Topic "orders.events" with 5,000,000 pending messages
  - 2 active consumer groups (email, analytics) processing at 1,000 msg/sec
  - Concurrent OLTP workload: 500 TPS (order inserts, updates, queries)

Test:
  T0: Measure baseline OLTP p95 latency (target: < 50ms)
  T1: Start backfill for new consumer group (inventory) with aggressive config
      - Batch size: 50,000
      - Max rate: 200,000 msg/sec
      - Target latency: 50ms
  T2-T30: Monitor OLTP p95 latency every 60 seconds
  T30: Backfill completes or is cancelled

Success Criteria:
  - OLTP p95 latency remains < 100ms (2x baseline)
  - Backfill automatically throttles when OLTP latency > 50ms
  - Backfill completes within 30 minutes OR throttles to sustainable rate
```

#### Metrics to Capture

```sql
-- OLTP query latency (from application)
SELECT operation, COUNT(*) AS count,
       ROUND(AVG(duration_ms), 2) AS mean_ms,
       ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration_ms), 2) AS p95_ms
FROM application_metrics
WHERE timestamp > NOW() - INTERVAL '1 minute'
GROUP BY operation;

-- Backfill progress
SELECT backfill_processed_messages,
       EXTRACT(EPOCH FROM (NOW() - backfill_started_at)) AS elapsed_sec,
       ROUND(backfill_processed_messages / NULLIF(EXTRACT(EPOCH FROM (NOW() - backfill_started_at)), 0), 0) AS msg_per_sec
FROM outbox_topic_subscriptions
WHERE backfill_status = 'IN_PROGRESS';

-- Database CPU and I/O
SELECT * FROM pg_stat_database WHERE datname = current_database();
```

#### Success Criteria

- ✅ **OLTP protected**: p95 latency < 100ms throughout backfill
- ✅ **Adaptive throttling**: Backfill rate decreases when OLTP latency spikes
- ✅ **Resumable**: Can cancel and resume backfill without data corruption
- ❌ **Fail**: OLTP latency > 100ms for > 5 minutes

**If OLTP is degraded**: Backfill rate limits are too aggressive, or adaptive throttling is not working.

---

### Recommended: Multi-Tenant Isolation Test

**Objective**: Verify that high-volume topics do not starve low-volume topics when sharing the same database.

#### Test Scenario

```
Setup:
  - Topic A "high-volume.events": 10,000 msg/sec, 10 consumer groups
  - Topic B "low-volume.events": 10 msg/sec, 2 consumer groups
  - Both topics share same database and connection pool

Test:
  T0-T60: Run both topics concurrently for 1 hour
  Measure: Topic B consumer fetch latency and processing lag

Success Criteria:
  - Topic B p95 fetch latency < 50ms (not starved by Topic A)
  - Topic B processing lag < 10 seconds
```

---

### Recommended: Partition Management Test (Offset/Watermark Mode)

**Objective**: Verify that partition creation, watermark advancement, and partition dropping work correctly under load.

#### Test Scenario

```
Setup:
  - Daily partitions for topic "orders.events"
  - 3 consumer groups with different processing rates
  - Retention: 7 days

Test:
  Day 1-10: Run at 10,000 msg/sec
  Day 8: Verify partition for Day 1 is dropped (7-day retention)
  Day 9: Kill one consumer group (test zombie protection)
  Day 10: Verify watermark excludes dead group and partition still drops

Success Criteria:
  - Partitions created automatically (daily)
  - Partitions dropped when watermark advances past retention
  - Zombie subscriptions do not pin partitions forever
```

---

### Decision Matrix

| Test | Status | Blocker for GA? | Action if Failed |
|------|--------|-----------------|------------------|
| **Fanout Write Amplification** | ⬜ Not Run | ✅ **YES** | Fix mode selection or thresholds |
| **Kill-and-Resurrect Chaos** | ⬜ Not Run | ✅ **YES** | Fix dead detection or backfill |
| **Backfill vs OLTP Contention** | ⬜ Not Run | ✅ **YES** | Fix adaptive rate limiting |
| **Multi-Tenant Isolation** | ⬜ Not Run | ⚠️ Recommended | Document limitations or fix |
| **Partition Management** | ⬜ Not Run | ⚠️ Recommended (for Offset mode) | Fix watermark or partition logic |

**GA Readiness**: All "Blocker for GA" tests must **PASS** before production deployment.

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

This document specifies the **Hybrid Queue/Pub-Sub** design chosen for PeeGeeQ's consumer group fan-out implementation. For a comparison of alternative approaches that were considered, see [CONSUMER_GROUP_FANOUT_DESIGN_OPTIONS.md](CONSUMER_GROUP_FANOUT_DESIGN_OPTIONS.md).

### Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Hybrid Queue/Pub-Sub** | Backward compatibility + new capabilities |
| **Explicit Topic Configuration** | Clear intent, no surprises |
| **Bitmap Tracking (Recommended)** | Scalable to 30,000 msg/sec, 64 consumer groups/topic |
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
| ⚠️ **Dead Consumer Groups** | Heartbeat tracking + automatic cleanup job with 9 critical pitfalls addressed |
| ⚠️ **Scalability** | Bitmap tracking for ≤64 groups, hybrid approach for >64 groups |
| ⚠️ **Concurrency** | 7 major concerns addressed with specific solutions |

### Scalability Guarantees

| Metric | Limit | Bottleneck |
|--------|-------|------------|
| **Messages/Second** | 30,000 sustained | Cleanup rate with bitmap tracking |
| **Consumer Groups/Topic** | 64 (bitmap), unlimited (hybrid) | BIGINT size / sharding |
| **Storage Overhead** | 8 bytes/message | Bitmap field in outbox table |
| **Query Latency** | <0.1ms | Bitwise operations (CPU-only) |
| **Completion Latency** | <0.2ms | Single UPDATE with bitmap |

### Document Organization

- **[CONSUMER_GROUP_FANOUT_DESIGN_OPTIONS.md](CONSUMER_GROUP_FANOUT_DESIGN_OPTIONS.md)** - Comparison of 4 design alternatives (Options 1-4)
- **This Document** - Complete specification of the chosen Hybrid Queue/Pub-Sub design including:
  - Design principles and topic semantics
  - Database schema changes
  - API design and implementation details
  - Dead consumer group cleanup (9 critical pitfalls)
  - Concurrency and scalability concerns (7 major issues)
  - Quantified scalability analysis with performance limits
  - Migration path and operational guidance

### Next Steps

1. **Review and Approve Design** - Stakeholder sign-off on chosen approach
2. **Create Implementation Tasks** - Break down into phases
3. **Prototype Core Logic** - Validate pub/sub query performance with bitmap tracking
4. **Write Integration Tests** - Cover all scenarios including edge cases
5. **Implement Phase 1** - Schema changes (outbox_topics, outbox_topic_subscriptions, bitmap fields)
6. **Implement Phase 2-4** - API and logic updates
7. **Performance Testing** - Validate 30,000 msg/sec target with 50+ consumer groups
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


