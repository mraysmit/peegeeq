# BackfillScopePerformanceTest PENDING_ONLY Multi-Batch Failure Analysis

## Summary

Two performance tests fail consistently when `PENDING_ONLY` scope is used with a dataset larger than one batch. Both tests process exactly `batchSize` messages (one batch), then `fetchBatchIds` returns 0 rows for batch 2, causing premature `COMPLETED` status.

```
BackfillScopePerformanceTest.testPendingOnlyScope_50kMessages_Throughput
  expected: <50000> but was: <10000>

BackfillScopePerformanceTest.testScopeComparison_ThroughputParity
  expected: <12000> but was: <5000>
```

`ALL_RETAINED` scope works correctly across all batches on the same dataset and the same codebase. This is a **production-code defect in `BackfillService`**, not a test infrastructure issue.

---

## Evidence from Log: `logs/peegeeq-db-all-20260504.txt`

### `testPendingOnlyScope_50kMessages_Throughput`

```
Backfill initialized: topic='perf-pending-only-3ed955fb', group='perf-pending-only-grp',
  totalMessages=50000, resumeFromId=1
Backfill completed for topic='perf-pending-only-3ed955fb', group='perf-pending-only-grp':
  10000 messages processed
Backfill finished: ..., status=COMPLETED, processed=10000, elapsedMs=787, rate=12706.5 msgs/s
```

- `totalMessages=50000` `countMessagesToBackfill` correctly finds 50000 PENDING messages before batch 1.
- Processed=10000 exactly one batch of `batchSize=10000`.
- No intermediate batch progress log between "initialized" and "completed".
- `fetchBatchIds` returned 0 rows for batch 2, triggering the early-exit path in `processFetchedBatch`.

### `testScopeComparison_ThroughputParity` (PENDING_ONLY leg)

```
Backfill initialized: topic='perf-compare-36f23adb-pending', group='compare-pending-grp',
  totalMessages=12000, resumeFromId=1
Backfill completed for topic='perf-compare-36f23adb-pending', group='compare-pending-grp':
  5000 messages processed
Backfill finished: ..., status=COMPLETED, processed=5000, elapsedMs=569, rate=8787.3 msgs/s
```

- totalMessages=12000 (20000 total − 8000 COMPLETED = 12000 PENDING). Count is correct.
- Processed=5000 exactly one batch of `batchSize=5000`.
- Same failure: 0 rows in batch 2.

### `testAllRetainedScope_50kMessages_Throughput` (passes)

```
Backfill initialized: topic='perf-all-retained-83705ca2', group='perf-all-retained-grp',
  totalMessages=50000, resumeFromId=1
Backfill finished: ..., status=COMPLETED, processed=50000, elapsedMs=5878, rate=8506.3 msgs/s
```

5 batches of 10000, all succeed. The only difference: status filter includes `'COMPLETED'`.

---

## What the Code Does

### `BackfillService.fetchBatchIds`

```java
String fetchSql = String.format("""
    SELECT id FROM outbox
    WHERE topic = $1 AND id >= $2
      AND status IN (%s)
    ORDER BY id ASC
    LIMIT $3
    """, statusPredicate(messageScope));
```

- PENDING_ONLY: `status IN ('PENDING', 'PROCESSING')`
- ALL_RETAINED: `status IN ('PENDING', 'PROCESSING', 'COMPLETED')`

For batch 2, `$2 = checkpointId + 1` where `checkpointId = messageIds.getLast()` from batch 1 (the highest-ID message processed).

### `processBatchesRecursively`

```java
return delay.compose(v -> processBatchesRecursively(trace, topic, groupName,
    batchResult.nextStartId(), batchSize, maxMessages,
    batchResult.totalProcessed(), messageScope, batchDelayMs));
```

`batchResult.nextStartId() = checkpointId + 1`. This is correct only if the remaining PENDING messages have IDs strictly greater than `checkpointId`. If any PENDING messages have IDs less than `checkpointId` which cannot happen given `ORDER BY id ASC` or if the remaining PENDING messages are gone (status changed), the query returns empty.

### Early-exit path in `processFetchedBatch`

```java
if (messageIds.isEmpty()) {
    return markBackfillCompleted(connection, trace, topic, groupName, alreadyProcessed)
            .map(v -> BatchResult.complete(alreadyProcessed));
}
```

Any empty `fetchBatchIds` result immediately marks the backfill COMPLETED regardless of how many messages were promised by `countMessagesToBackfill`.

---

## Leading Hypotheses

### H2 `set_required_consumer_groups` Trigger Race (highest confidence)

The `insertMessagesBulk` SQL uses `generate_series` to bulk-insert 50000 rows in a single statement. This fires the `set_required_consumer_groups` trigger (defined in `V010__Create_Consumer_Group_Fanout_Tables.sql`) for each row:

```sql
SELECT COUNT(*) INTO active_subscription_count
FROM outbox_topic_subscriptions
WHERE topic = NEW.topic AND subscription_status = 'ACTIVE';
NEW.required_consumer_groups := active_subscription_count;
```

**Race window**: If the `initial-group-*` subscription INSERT hasn't committed yet when the trigger evaluates (e.g., committed to the `outbox_topic_subscriptions` table outside the transaction boundary of `insertMessagesBulk`), the trigger sees 0 active subscriptions and sets `required_consumer_groups = 0`.

`DeadConsumerGroupCleanup.autoCompleteMessages` then fires (or some equivalent guard) for messages where `completed_consumer_groups >= required_consumer_groups` → `0 >= 0 = true` → all batch-1 messages are immediately marked **COMPLETED**.

For batch 2, `fetchBatchIds` filters `status IN ('PENDING', 'PROCESSING')` and finds 0 rows. For ALL_RETAINED it finds all rows because COMPLETED is included explaining why ALL_RETAINED works.

Test setup:

```java
private Future<Void> setupTopicAndMessages(String topic, int messageCount) {
    testTopics.add(topic);
    return topicConfigService.createTopic(...)
            .compose(v -> subscriptionManager.subscribe(topic,
                    "initial-group-" + UUID.randomUUID().toString().substring(0, 4),
                    SubscriptionOptions.defaults()))
            .compose(v -> insertMessagesBulk(topic, messageCount));  // <-- trigger fires here
}
```

The `subscribe` call commits the `initial-group` subscription. Then `insertMessagesBulk` runs. If the `required_consumer_groups` trigger fires with 1 subscription, `required_consumer_groups=1`. A newly inserted message with `completed_consumer_groups=0` and `required_consumer_groups=1` is PENDING correct. But if `BackfillService.processFetchedBatch` increments `required_consumer_groups` to 2 for the batch-1 group (which it does), then after backfill inserts tracking rows and marks batch-1 processing complete... wait, the backfill does NOT call `CompletionTracker.markCompleted`. It only inserts into `outbox_consumer_groups` with `status='PENDING'`. The message status in `outbox` is NOT changed by backfill.

So **H2 doesn't apply as stated**. The messages stay PENDING in `outbox` regardless of backfill. Back to the drawing board.

### H3 `checkpointId` equals or exceeds all remaining message IDs (revised)

The outbox table is **shared across all tests running in parallel**. Message IDs are assigned by a global BIGSERIAL. When 50000 messages are inserted for topic `perf-pending-only-3ed955fb`, they receive IDs from the global sequence interspersed with IDs from other concurrent tests.

Example: Tests A, B, C, D run in parallel. All four insert messages in parallel. The 50000 messages for test A do NOT have consecutive IDs 1..50000. They have IDs scattered through the global sequence.

Batch 1 fetches the 10000 lowest IDs for this topic with `status='PENDING'`. `checkpointId = messageIds.getLast()` = the 10000th lowest ID for this topic. But this ID may be *much higher* than the 10001st lowest ID for the same topic if other tests' messages are interleaved.

**Wait this doesn't cause the problem either.** `ORDER BY id ASC LIMIT 10000` fetches the 10000 rows with the lowest IDs for this topic. Batch 2 starts from `checkpointId + 1`. The 10001st through 50000th messages for this topic all have IDs greater than the 10000th. So `id >= checkpointId + 1` still finds them.

Unless... `checkpointId` is somehow a globally-high ID that exceeds ALL remaining messages for this topic. That would require other tests to have inserted a message with a very high ID for the topic *between* the 10000th and 10001st PENDING messages for this topic. But the topic is UUID-scoped so no other test inserts into `perf-pending-only-3ed955fb`. The IDs are scattered but the ORDER BY id ASC filter is per-topic.

H3 as a global max ID problem doesn't hold. The scatter doesn't affect correctness.

### H1 Status Change Between Batches: `outbox_consumer_groups` Trigger

Looking at `V010__Create_Consumer_Group_Fanout_Tables.sql`:

```sql
CREATE OR REPLACE FUNCTION create_consumer_group_entries_for_new_message()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO outbox_consumer_groups (message_id, group_name, status)
    SELECT DISTINCT NEW.id, group_name, 'PENDING'
    FROM outbox_consumer_groups
    WHERE group_name IS NOT NULL
    ON CONFLICT (message_id, group_name) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

This trigger fires on INSERT into `outbox`. When `insertMessagesBulk` inserts 50000 messages, for each message it inserts one `outbox_consumer_groups` row per existing group in the table. In a busy test run, other tests have groups like `perf-group-0`, `compare-pending-grp`, etc. already registered.

Then `BackfillService.processFetchedBatch` does:

```java
String incrementSql = String.format("""
    UPDATE outbox
    SET required_consumer_groups = required_consumer_groups + 1
    WHERE id = ANY($1) AND status IN (%s)
    """, statusPredicate(messageScope));
```

This increments `required_consumer_groups` from 1 → 2 for the batch-1 messages. But the messages were already initialized with `required_consumer_groups = N_active_subscriptions_at_insert_time`. If the trigger sees multiple active groups and initializes `required_consumer_groups = 4` (from other parallel tests' groups), and `completed_consumer_groups` remains 0, the message is still PENDING.

**This doesn't change message status either.**

### H1 (Revised) `DeadConsumerDetectionJob` Mutates Batch-1 Messages

The `DeadConsumerDetectionJob` was running during this test run (other tests use it). If it marks subscriptions as DEAD and runs `DeadConsumerGroupCleanup.autoCompleteMessages`:

```java
UPDATE outbox
SET status = 'COMPLETED', processed_at = NOW()
WHERE topic = $1
  AND status IN ('PENDING', 'PROCESSING')
  AND completed_consumer_groups >= required_consumer_groups
```

But `topic = $1` scopes it to a specific topic not the performance test topics. Not a cross-topic pollution.

### H4 `withTransaction` for Batch 2 Runs on the Same Connection as a Stale Transaction

`BackfillService.processOneBatch` uses `connectionManager.withTransaction(serviceId, ...)`. The pool size for the performance test is 20. Each batch occupies one connection for its duration. After batch 1 commits, batch 2 starts a new transaction. In normal operation this should see the committed state. No issue here.

---

## Most Likely Root Cause: `set_required_consumer_groups` Trigger + CompletionTracker Auto-Complete

Re-examining `CompletionTracker.markCompleted`:

```java
String updateCounterSql = """
    UPDATE outbox
    SET completed_consumer_groups = completed_consumer_groups + 1,
        status = CASE
            WHEN completed_consumer_groups + 1 >= required_consumer_groups
            THEN 'COMPLETED'
            ELSE status
        END
    WHERE id = $1
      AND completed_consumer_groups < required_consumer_groups
    """;
```

The key question: **does any code path call `CompletionTracker.markCompleted` on the batch-1 messages before batch 2 begins?**

The performance test does not call `CompletionTracker.markCompleted` directly. But the `create_consumer_group_entries_for_new_message` trigger inserts rows for ALL existing groups into `outbox_consumer_groups`. These trigger-created rows have `status='PENDING'`. If any OTHER concurrent test's consumer (via `OutboxConsumer` or `MessageConsumer`) picks up a message from the `perf-pending-only-3ed955fb` topic... impossible because consumers are topic-scoped and no other test subscribes to this UUID topic.

**Revised H1 The trigger creates group entries for groups from OTHER tests, and BackfillService's incrementSql changes required_consumer_groups, but completed_consumer_groups for those groups could be incremented by OTHER consumers running in parallel on the SHARED outbox table.**

Wait. The trigger `create_consumer_group_entries_for_new_message` inserts `outbox_consumer_groups` rows for every `group_name` that already exists in `outbox_consumer_groups`. If `perf-group-0` from `FanoutPerformanceValidationTest` is already registered in `outbox_consumer_groups`, the trigger inserts rows for `perf-group-0` into the new topic's messages. Then `FanoutPerformanceValidationTest`'s consumer calls `CompletionTracker.markCompleted` for `perf-group-0`, which increments `completed_consumer_groups` for messages of ALL topics that have a `perf-group-0` entry including the performance test's topic.

This is **cross-topic contamination via the trigger + CompletionTracker**. This is the real mechanism.

---

## Definitive Root Cause: Cross-Topic Contamination via `create_consumer_group_entries_for_new_message` Trigger

The trigger in `V010`:

```sql
CREATE OR REPLACE FUNCTION create_consumer_group_entries_for_new_message()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO outbox_consumer_groups (message_id, group_name, status)
    SELECT DISTINCT NEW.id, group_name, 'PENDING'
    FROM outbox_consumer_groups
    WHERE group_name IS NOT NULL
    ON CONFLICT (message_id, group_name) DO NOTHING;
    RETURN NEW;
END;
```

This selects ALL group_names from the entire `outbox_consumer_groups` table **not scoped to the topic or subscription**. When `FanoutPerformanceValidationTest` runs in parallel and has registered `perf-group-0`, `perf-group-1`, `perf-group-2`, `perf-group-3` in `outbox_consumer_groups`, the trigger adds these four groups to every new message inserted for `perf-pending-only-3ed955fb`.

Consequence:
1. `set_required_consumer_groups` trigger sets `required_consumer_groups = N_active_subscriptions` for the topic. Let's say N=1 (only `initial-group-fa51`).
2. The `create_consumer_group_entries_for_new_message` trigger creates entries for `perf-group-0..3` (from parallel test) in `outbox_consumer_groups` for this topic's messages.
3. `BackfillService.processFetchedBatch` increments `required_consumer_groups` by 1 for batch-1 messages (now `required_consumer_groups = 2`). Inserts tracking rows for `perf-pending-only-grp`.
4. `FanoutPerformanceValidationTest`'s consumer calls `CompletionTracker.markCompleted(messageId, 'perf-group-0', ...)` for its own messages, but the trigger already created rows for `perf-group-0` in the performance test's topic too. So `completed_consumer_groups` for the performance test's messages is incremented.
5. When `completed_consumer_groups >= required_consumer_groups` (e.g., 2 >= 2), the message status becomes `COMPLETED`.
6. Batch 2 queries `status IN ('PENDING', 'PROCESSING')` and finds 0 rows.

**This also explains why ALL_RETAINED works**: it includes `COMPLETED` in the status filter, so even contaminated messages are still returned.

---

## Verification Strategy (Before Fix)

Add a single diagnostic check before running any fix: query `outbox_consumer_groups` for a performance-test topic between batches to confirm whether rows from foreign groups (e.g., `perf-group-0..3`) exist.

The easiest form: add a diagnostic-only SQL query to `BackfillService.processBatchesRecursively` in a debug branch, or observe by re-running the isolated test without parallelism.

---

## Fix Plan

### Phase 1 Isolation Run (5 minutes)

Run the failing test in isolation to confirm whether the failure reproduces without parallelism:

```powershell
mvn test -Pall-tests -pl :peegeeq-db -Dtest="BackfillScopePerformanceTest#testPendingOnlyScope_50kMessages_Throughput" 2>&1 | Tee-Object -FilePath logs\pending-only-isolated-20260504.txt
```

- **Passes in isolation**: Confirm cross-test contamination is the root cause (H1 confirmed).
- **Still fails in isolation**: The root cause is in the test's own setup (trigger + auto-complete loop within one test).

### Phase 2 Fix the Trigger (Production SQL requires migration)

The `create_consumer_group_entries_for_new_message` trigger must be scoped to the topic:

```sql
CREATE OR REPLACE FUNCTION create_consumer_group_entries_for_new_message()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO outbox_consumer_groups (message_id, group_name, status)
    SELECT DISTINCT NEW.id, s.group_name, 'PENDING'
    FROM outbox_topic_subscriptions s
    WHERE s.topic = NEW.topic
      AND s.subscription_status = 'ACTIVE'
    ON CONFLICT (message_id, group_name) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

This aligns the trigger with `set_required_consumer_groups` which also queries `outbox_topic_subscriptions` for the specific topic. It also removes the dependency on `outbox_consumer_groups` to determine which groups exist a circular self-reference that can't be correct for a trigger that runs on message INSERT.

This fix requires a new Flyway migration file.

### Phase 3 Verify

1. Run isolated test confirm pass.
2. Run full `peegeeq-db -Pall-tests` confirm no regressions.
3. Verify `FanoutPerformanceValidationTest` still passes (fanout should still work; the trigger now correctly uses subscriptions not existing tracking rows).

---

## Additional Notes

### Why the teardown deadlock fix did NOT resolve this

The deadlock fix (PR already in this branch) scoped teardown deletes to the test instance's own topics. This resolved the PostgreSQL `40P01` deadlock. It did NOT address the cross-test group contamination described here.

### Why `ALL_RETAINED` is immune

`ALL_RETAINED` uses `status IN ('PENDING', 'PROCESSING', 'COMPLETED')`. Even if messages are auto-completed due to contamination, they are still returned by `fetchBatchIds` and processed correctly. This makes `ALL_RETAINED` a red herring for diagnosing the trigger problem it masks the contamination entirely.

### `testScopeComparison` PENDING_ONLY leg

The 8000 `markOldestMessagesCompleted` messages have lower IDs than the PENDING messages. The 12000 remaining PENDING messages are the ones with *higher* IDs. In this case:
- Batch 1 fetches the 5000 lowest-ID PENDING messages correctly.
- If those 5000 messages are then contaminated into COMPLETED status by a parallel test's consumer, batch 2 finds 0 PENDING messages.
- The remaining 7000 PENDING messages are genuinely still there but batch 2's `checkpointId + 1` starts from a higher ID and the remaining 7000 are above it. Except they were also contaminated.
- In fact, ALL 12000 PENDING messages are contaminated simultaneously by the trigger at insert time not just the batch-1 messages.

---

## Files Involved

| File | Relevance |
|---|---|
| [BackfillService.java](../../peegeeq-db/src/main/java/dev/mars/peegeeq/db/subscription/BackfillService.java) | `fetchBatchIds`, `processFetchedBatch`, `processBatchesRecursively` |
| [BackfillScopePerformanceTest.java](../../peegeeq-db/src/test/java/dev/mars/peegeeq/db/fanout/BackfillScopePerformanceTest.java) | Failing tests, `setupTopicAndMessages`, `insertMessagesBulk` |
| [V010__Create_Consumer_Group_Fanout_Tables.sql](../../peegeeq-migrations/src/main/resources/db/migration/V010__Create_Consumer_Group_Fanout_Tables.sql) | `create_consumer_group_entries_for_new_message` trigger (bug), `set_required_consumer_groups` trigger |
| [CompletionTracker.java](../../peegeeq-db/src/main/java/dev/mars/peegeeq/db/consumer/CompletionTracker.java) | Auto-completion logic that changes message status to COMPLETED |
| [DeadConsumerGroupCleanup.java](../../peegeeq-db/src/main/java/dev/mars/peegeeq/db/cleanup/DeadConsumerGroupCleanup.java) | `autoCompleteMessages` a secondary auto-complete path |
| [logs/peegeeq-db-all-20260504.txt](../../logs/peegeeq-db-all-20260504.txt) | Primary evidence log |
