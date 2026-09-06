# Deadlock in BackfillScopePerformanceTest — teardown scope too broad

## Problem

`BackfillScopePerformanceTest.testAllRetainedScope_50kMessages_Throughput` fails intermittently with PostgreSQL error `40P01 — deadlock detected`.

The four test methods in this class run in parallel (JUnit ForkJoinPool, 4 workers). Each method creates its own UUID-based topic, so there is no direct topic collision. The deadlock is caused by the `@AfterEach tearDown()` method, not by the backfill logic itself.

`tearDown()` deletes rows using broad `LIKE` predicates:

```java
"DELETE FROM outbox WHERE topic LIKE 'perf-pending-only-%'"
+ " OR topic LIKE 'perf-all-retained-%'"
+ " OR topic LIKE 'perf-incr-completed-%'"
+ " OR topic LIKE 'perf-compare-%'"
```

When test A finishes and its `@AfterEach` fires, these predicates match topics that belong to test B, C, or D, which are still mid-backfill. This creates a deadlock cycle:

- **Backfill transaction** (test D): holds a `FOR UPDATE` lock on `outbox_topic_subscriptions`, then attempts to `UPDATE outbox ... WHERE id = ANY(...)` and `INSERT INTO outbox_consumer_groups`.
- **Teardown DELETE transaction** (test A's `@AfterEach`): acquires row locks on those same `outbox` rows (via the DELETE), then cascade-locks `outbox_consumer_groups` via the `ON DELETE CASCADE` foreign key. It is waiting for the backfill transaction to release its lock.

PostgreSQL detects the cycle and kills one of the two transactions with `40P01`.

## Solution

Track the exact topic names created by each test instance. In `@AfterEach`, delete only those specific topics rather than all rows matching a shared prefix.

**Changes to `BackfillScopePerformanceTest`** (test code only — no production change):

1. Add an instance field: `private final List<String> testTopics = new ArrayList<>();`
2. In `setupTopicAndMessages`, register the topic before the Future chain: `testTopics.add(topic);`
3. In `tearDown`, replace the four LIKE predicates with `WHERE topic = ANY($1::text[])` parameterised on the collected list. Skip the DELETE entirely if the list is empty.
4. Add imports for `java.util.ArrayList` and `java.util.List`.

This ensures each test's teardown only touches the rows it created, eliminating all interference with concurrently running tests.
