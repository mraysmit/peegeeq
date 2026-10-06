# PeeGeeQ Outbox Consumer Crash Recovery

**Status:** IMPLEMENTED — `StuckMessageRecoveryManager`
**Last reconciled:** 2026-10-06 against commit `f1c5d25d`

**Problem:** If the outbox consumer crashes after polling but before completing message
processing, the polled messages remain in `PROCESSING` state with `processed_at` set.

**Solution in the codebase:** timeout-based recovery. `StuckMessageRecoveryManager` resets
messages that have been in `PROCESSING` longer than a configurable timeout (default 5 minutes)
back to `PENDING`.

---

## Part 1: The crash window

### The poll statement

`peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxConsumer.java` (L380–L394) claims a
batch with one schema-qualified `UPDATE ... RETURNING`:

```sql
UPDATE <schema>.outbox
SET status = 'PROCESSING', processed_at = $1
WHERE id IN (
    SELECT outbox_message.id
    FROM <schema>.outbox outbox_message
    WHERE outbox_message.topic = $2
      AND outbox_message.status = 'PENDING'
      [AND outbox_message.id > $n]          -- optional scan position
    ORDER BY outbox_message.created_at ASC, outbox_message.id ASC
    LIMIT $3
    FOR UPDATE SKIP LOCKED
)
RETURNING id, payload, headers, correlation_id, message_group, created_at,
          EXTRACT(EPOCH FROM (now() - created_at)) * 1000.0 AS delivery_latency_ms
```

The table name is substituted from the validated schema-qualified identifier. The optional
`id > $n` condition carries the consumer's scan position.

### What happens during a crash

1. The consumer polls messages and updates them to `PROCESSING`.
2. It sets `processed_at` to the current time.
3. It returns the messages for processing.
4. The consumer process dies.
5. The messages remain in `PROCESSING` with `processed_at` set.
6. Nothing moves them to `COMPLETED`.

### The resulting state

| Field | Value | Effect |
|-------|-------|--------|
| `status` | `PROCESSING` | Excluded from the next poll (`status = 'PENDING'`) |
| `processed_at` | set | Marks when the claim was made |
| `retry_count` | unchanged | Never incremented |

Without recovery the message is never retried or completed.

---

## Part 2: Timeout-based recovery

### `StuckMessageRecoveryManager`

File: `peegeeq-db/src/main/java/dev/mars/peegeeq/db/recovery/StuckMessageRecoveryManager.java`.

Constructor: `StuckMessageRecoveryManager(Pool reactivePool, Duration processingTimeout, boolean enabled)` (L63).

Behaviour:

1. Counts messages where `status = 'PROCESSING' AND processed_at < $1`, with `$1` = now minus
   `processingTimeout` (L123–L128).
2. Resets them with `UPDATE outbox SET status = 'PENDING', processed_at = NULL WHERE status =
   'PROCESSING' AND processed_at < $1` (L151–L158).
3. Does nothing when `enabled` is false.

`PeeGeeQManager` constructs the manager from `QueueConfig.isRecoveryEnabled()` (L269) and
schedules it on a periodic timer from `QueueConfig.getRecoveryCheckInterval()` (L988).

```
PROCESSING message with processed_at older than the timeout
    ↓
Recovery manager detects it on its next check
    ↓
status reset to PENDING, processed_at cleared
    ↓
Message is polled again and reprocessed
```

### Configuration

Recovery is configured by three properties. `PeeGeeQConfiguration.QueueConfig` is immutable and
exposes only getters (`PeeGeeQConfiguration.java` L679–L681: `isRecoveryEnabled()`,
`getRecoveryProcessingTimeout()`, `getRecoveryCheckInterval()`).

| Property | Default | Profile overrides |
|---|---|---|
| `peegeeq.queue.recovery.enabled` | `true` | — |
| `peegeeq.queue.recovery.processing-timeout` | `PT5M` | `high-performance`: `PT2M`; `reliable`: `PT3M` |
| `peegeeq.queue.recovery.check-interval` | `PT10M` | `high-performance`: `PT5M`; `reliable`: `PT5M` |

Defaults are in `peegeeq-db/src/main/resources/peegeeq-default.properties` (L39–L41) and in
`PeeGeeQConfiguration.java` (L338–L345). Values are ISO-8601 durations. Set them in the profile
properties file or in the `Properties` overrides passed to
`PeeGeeQConfiguration(String profile, Properties overrides)`.

Worst-case recovery delay is `processing-timeout + check-interval`: 15 minutes with the
defaults, 7 minutes on `high-performance`, 8 minutes on `reliable`.

### Why a timeout and not real-time detection

- The handler runs asynchronously. The database cannot distinguish a crashed consumer from a
  slow one.
- A crashed process cannot notify the database.
- Reprocessing too early produces duplicates. The timeout errs toward delay rather than
  duplication.

### Test coverage

- `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/OutboxConsumerCrashRecoveryTest.java`:
  `testConsumerCrashMessageIsRecoveredToPending` (L160) exercises the crash window and confirms
  the claimed message returns to `PENDING`.
- `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/StuckMessageRecoveryIntegrationTest.java`:
  `testStuckMessageRecoveryWithRealCrash` (L146), `testDisabledRecovery` (L220), and
  `testDirectlyInsertedStuckMessageIsRecoveredToPending` (L252).

Both run against real PostgreSQL via TestContainers.

---

## Part 3: Alternatives not adopted

Heartbeat-based recovery (a `last_heartbeat` column updated during processing), a
consumer-lease pattern with TTL renewal, and an external process monitor that resets
`PROCESSING` rows for a dead consumer were considered as faster-detection designs. None is
scheduled. None has an entry in the consolidated task register
(`docs-design/tasks/tasks.md`). Each would require a Flyway migration plus a matching
template change (see `docs-design/schema-tenants-support/PEEGEEQ_SCHEMA_CONFIGURATION_DESIGN.md`
§5) and new recovery tests before adoption.
