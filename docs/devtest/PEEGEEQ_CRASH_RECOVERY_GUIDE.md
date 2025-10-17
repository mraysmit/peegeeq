# PeeGeeQ Outbox Consumer Crash Recovery: Complete Guide

## Executive Summary

**Problem:** If the outbox consumer crashes after polling but before completing message processing, messages remain stuck in `PROCESSING` state with `processed_at` set.

**Status:** ✅ **CONFIRMED AND ADDRESSED** - The system has a comprehensive recovery mechanism in place.

**Current Solution:** Timeout-based recovery (default: 5 minutes)

**Faster Alternatives:** Available with different trade-offs

---

## Part 1: Problem Analysis & Current Solution

### The Vulnerable Code Path

**File:** `OutboxConsumer.java` (lines 258-269)

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

### What Happens During a Crash

1. Consumer polls messages and updates them to `PROCESSING` status
2. Sets `processed_at` timestamp to current time
3. Returns messages for processing
4. **[CRASH OCCURS HERE]** - Consumer process dies
5. Messages remain in `PROCESSING` state with `processed_at` set
6. Messages are never moved to `COMPLETED` status

### The Inconsistent State Created

| Field | Value | Problem |
|-------|-------|---------|
| Status | `PROCESSING` | Stuck indefinitely |
| processed_at | Set (not null) | Indicates processing started |
| retry_count | 0 | Never incremented |
| Result | Message orphaned | Will never be retried or completed |

---

## Part 2: Current Solution - Stuck Message Recovery

### Recovery Manager Implementation

**File:** `peegeeq-db/src/main/java/dev/mars/peegeeq/db/recovery/StuckMessageRecoveryManager.java`

The system includes a `StuckMessageRecoveryManager` that:

1. **Detects stuck messages** - Identifies messages in `PROCESSING` state where `processed_at` is older than a configurable timeout (default: 5 minutes)

2. **Recovers automatically** - Resets stuck messages back to `PENDING` status, clearing `processed_at` so they can be reprocessed

3. **Configurable timeout** - Can be tuned based on expected message processing time

4. **Can be disabled** - For systems that want to handle recovery differently

### How It Works

```
PROCESSING message with processed_at > timeout
    ↓
Recovery manager detects it
    ↓
Resets status to PENDING
    ↓
Clears processed_at timestamp
    ↓
Message is repolled and reprocessed
```

### Test Coverage

**OutboxConsumerCrashRecoveryTest.java** (319 lines)
- Tests the exact crash scenario
- Verifies messages can be stuck in `PROCESSING` state
- Confirms recovery mechanism handles it

**StuckMessageRecoveryIntegrationTest.java** (486 lines)
- Comprehensive integration tests
- Tests with simulated consumer crashes
- Tests with disabled recovery
- Tests with thread crashes
- Verifies recovery statistics

### Configuration

```java
PeeGeeQConfiguration config = new PeeGeeQConfiguration("prod");

// Default: 5 minutes
config.getQueueConfig().setRecoveryTimeout(Duration.ofMinutes(5));

// Default: 1 minute
config.getQueueConfig().setRecoveryCheckInterval(Duration.ofMinutes(1));

// Enable/disable recovery
config.getQueueConfig().setRecoveryEnabled(true);
```

---

## Part 3: Why 5-Minute Timeout?

### The Architectural Challenge

```
Poll (PENDING → PROCESSING)
    ↓
Process (async in thread pool)
    ↓ [CRASH HERE]
Complete (PROCESSING → COMPLETED)
```

### Why Not Real-Time Detection?

1. **Asynchronous Processing**
   - Handler runs in separate thread pool
   - System can't distinguish between crash and slow processing
   - Can't know if thread is alive or just slow

2. **Process Isolation**
   - Consumer could crash completely
   - No way to notify database immediately
   - Requires external monitoring

3. **Duplicate Prevention**
   - Conservative approach is safer
   - Better to wait than reprocess too early
   - Duplicates are worse than delays in financial systems

### Why This Design is Pragmatic

1. **Simplicity** - No complex coordination needed
2. **Reliability** - Works even if consumer crashes completely
3. **Correctness** - Avoids duplicate processing
4. **Configurability** - Timeout can be adjusted per deployment
5. **Proven Pattern** - Standard in RabbitMQ, Kafka, etc.

---

## Part 4: Real-Time Recovery Alternatives

### Option 1: Heartbeat-Based Recovery ⭐ RECOMMENDED

**How it works:**
```
Processing thread sends heartbeats every N seconds
Recovery manager monitors heartbeats
Missing heartbeat → message reset to PENDING
```

**Pros:**
- ✅ Detects crashes in seconds (not minutes)
- ✅ Works with async processing
- ✅ Minimal code changes
- ✅ Can be added to existing system

**Cons:**
- ❌ Adds database writes during processing
- ❌ Requires schema change (add `last_heartbeat` column)
- ❌ Slight performance overhead

**Recovery Time:** 5-10 seconds (configurable)

### Option 2: Consumer Lease Pattern

**How it works:**
```
Consumer acquires lease on message (with TTL)
Lease must be renewed during processing
Expired lease → message available for reprocessing
```

**Pros:**
- ✅ Automatic recovery without polling
- ✅ Prevents duplicate processing
- ✅ Scales well with multiple consumers

**Cons:**
- ❌ Requires consumer group coordination
- ❌ Complex implementation
- ❌ Needs distributed lock mechanism

**Recovery Time:** Lease TTL (10-30 seconds)

### Option 3: Synchronous Processing with Transactions

**How it works:**
```
Keep message in PENDING
Process within database transaction
Only update to COMPLETED after success
If crash: transaction rolls back, message stays PENDING
```

**Pros:**
- ✅ Guaranteed atomicity
- ✅ No stuck state possible
- ✅ Simplest semantics

**Cons:**
- ❌ Breaks async architecture
- ❌ Reduces throughput significantly
- ❌ Not suitable for long-running handlers

**Recovery Time:** Immediate (on next poll)

### Option 4: External Process Monitor

**How it works:**
```
Separate monitoring service watches consumer process
Detects process crash via health checks
Resets PROCESSING messages for dead consumer
```

**Pros:**
- ✅ Works with existing code
- ✅ Can monitor multiple consumers
- ✅ Flexible recovery logic

**Cons:**
- ❌ Requires separate service
- ❌ Complex distributed coordination
- ❌ Risk of false positives

**Recovery Time:** 10-30 seconds

---

## Part 5: Recommendations & Implementation

### For Most Use Cases: Reduce Timeout (EASIEST)

```java
PeeGeeQConfiguration config = new PeeGeeQConfiguration("prod");
config.getQueueConfig().setRecoveryTimeout(Duration.ofSeconds(30));
config.getQueueConfig().setRecoveryCheckInterval(Duration.ofSeconds(5));
```

**Result:** Recovery in 5-35 seconds instead of 5 minutes
**Effort:** Minimal (1 line of code)

### For Critical Systems: Add Heartbeat (RECOMMENDED)

1. Add `last_heartbeat` column to outbox table
2. Update heartbeat during processing
3. Recovery manager checks for stale heartbeats
4. Recovery time: 5-10 seconds
**Effort:** Medium (1-2 days)

### For Ultra-Low-Latency: Synchronous Processing

```java
// Process synchronously within transaction
messageHandler.handle(message).get(5, TimeUnit.SECONDS);
// Automatic rollback on crash
```

**Effort:** High (architectural change)

### Implementation Priority

1. **Immediate** (no code changes):
   - Reduce recovery timeout to 30 seconds
   - Increase recovery check frequency to 5 seconds

2. **Short-term** (1-2 days):
   - Implement heartbeat-based recovery
   - Add monitoring/alerting for stuck messages

3. **Long-term** (if needed):
   - Implement consumer group coordination
   - Add distributed lease mechanism

---

## Conclusion

The 5-minute timeout is **not a bug**, it's a **design choice** for reliability and simplicity. The system is **production-ready** with automatic recovery ensuring no messages are permanently lost.

For faster recovery without major architectural changes, reduce the timeout to 30 seconds or implement heartbeat-based monitoring.

