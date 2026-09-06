# Partitioned Consumption Design and Implementation Record

**Status:** IMPLEMENTED BASELINE — PRE-GA VALIDATION OPEN

**Created:** 2026-04-09

**Last reconciled:** 2026-09-06

**Repository baseline:** `7db748b8e77f3aba850be7b73547d192dac5b83f`

## Authority and scope

This document records the implemented `OFFSET_WATERMARK` partitioned-consumption design. It does
not maintain a separate numbered task list. The only authoritative remaining work is
[Task 6 in the consolidated register](../tasks/tasks.md#6-partitioned-consumption-pre-ga-gates).

Earlier revisions called the release gates “Task 4” and mixed them with local implementation
phases. That numbering is retired to prevent it from being mistaken for consolidated Task 4,
which covers durable subscriptions.

## Outcome

The following implementation areas are complete:

- partitioned offset and assignment schema;
- generation-fenced cursor management;
- serialized assignment and rebalance;
- ordered, per-partition fetch and sequential handling;
- watermark calculation and cleanup;
- queue API and REST surfaces;
- native and outbox consumer-group lifecycle integration;
- periodic heartbeat and assignment reconciliation;
- retry and dead-letter automation for fan-out processing;
- fan-out trace branching; and
- focused lifecycle, ordering, failure, and concurrency tests.

The implementation is not being declared generally available from static inspection. Long-running
capacity, chaos, contention, isolation, and recovery evidence remains a release gate.

## Completion modes

PeeGeeQ keeps two completion models because they solve different problems:

| Mode | Cursor model | Ordering | Primary use |
|---|---|---|---|
| `REFERENCE_COUNTING` | Per-message completion by subscribed groups | No processing-order guarantee | High-throughput fan-out |
| `OFFSET_WATERMARK` | Per-group, per-partition committed offsets | Strict within one partition | Stateful per-key workflows |

The mode is configured explicitly for a topic. `REFERENCE_COUNTING` remains the default.

## Persistent model

The implementation uses tenant-local records for:

- topic and consumer-group subscription state;
- partition ownership and assignment generation;
- committed and pending offsets per group and partition; and
- topic watermarks used to determine safe cleanup.

Key database invariants are:

- one assignment row per topic, group, and partition;
- monotonic rebalance generations;
- cursor updates conditional on the current generation;
- committed offsets that move forward only; and
- cleanup that never passes the minimum safe active-group position.

## Assignment and rebalance

Joining or leaving a group serializes the rebalance through the subscription record. The assignment
service discovers currently active partitions, computes ownership, writes one generation, and
updates cursor generations in the same transaction.

The engine periodically:

- updates the instance heartbeat;
- reads its current assignments;
- initializes missing cursor state; and
- replaces its in-memory ownership snapshot.

That reconciliation removes stale ownership and picks up changes produced by a rebalance. New
partition keys are discovered by rebalance, not by heartbeat alone.

## Fetch, handle, and commit sequence

For each assigned partition, the engine:

1. acquires an in-memory in-progress guard;
2. reads from the committed offset in ascending message identifier order;
3. records the pending batch position under the current generation;
4. invokes the handler sequentially for every row in the batch;
5. commits the final offset only if all handlers succeed and the generation is still current; and
6. releases the guard on both success and failure.

A failure leaves the committed cursor unchanged, so the next cycle replays from the last committed
position. Rebalance fencing rejects a cursor update from a displaced owner. These are at-least-once
semantics; application handlers must tolerate repeats.

## Default partition

A message without `messageGroup` belongs to the synthetic `__default__` partition. This preserves a
defined ordering lane but serializes all ungrouped traffic. Production use of `OFFSET_WATERMARK`
should normally provide a stable business key on every send.

## Watermark cleanup

Cleanup calculates a safe topic watermark from committed partition offsets for active groups. It
advances the stored watermark monotonically and transitions only messages at or below the safe
position. A failed, inactive, or lagging group must be handled according to the subscription
lifecycle before it can stop influencing cleanup.

## Lifecycle contract

- Startup returns an asynchronous result that settles after mode detection and engine startup.
- Group members become active only after the partitioned engine is ready.
- Startup failure tears down partial state and leaves the group restartable.
- Graceful stop waits for in-flight fetch and assignment refresh work before leaving the group.
- Stop and close compose asynchronous resource cleanup through their returned results.
- Background retry, watermark, fetch, heartbeat, and reconciliation work is canceled during
  shutdown.

## Trace and observability contract

Each fan-out delivery creates a consumer-group child trace from the producer context when one is
present. Fetch, processing, completion, failure, assignment, cursor, and watermark operations
expose structured diagnostic context. Operators should alert on sustained stale-generation
rejection, rebalance churn, pending-offset age, assignment-heartbeat age, and watermark lag.

See the [fan-out trace record](../_archived/completed-records/CONSUMER_GROUP_FANOUT_TRACE_PROPAGATION.md)
for the implemented trace shape.

## Maintained test surfaces

The design is covered by focused suites for:

- offset initialization, monotonic commits, and generation rejection;
- concurrent join and leave, assignment uniqueness, and heartbeat updates;
- ordered partition fetch, row locking, failed-batch replay, and crash recovery;
- watermark advancement, group interaction, and cleanup;
- consumer-group start, stop, close, and partial-start failure;
- per-partition ordering and cross-partition concurrency; and
- REST join, leave, assignment, fetch, and commit operations.

Historical counts from old revisions are not current verification. Accepted test and Jenkins
evidence belongs in the consolidated register.

## Remaining release gates

Consolidated Task 6 requires dated and reproducible evidence for:

- long-duration fan-out and partition stability;
- consumer death, lease expiry, rebalance, and recovery chaos;
- transaction, table, and connection-pool contention;
- tenant/schema isolation under concurrent partition activity;
- cleanup correctness after interrupted work; and
- an operating envelope tied to a documented VM and database configuration.

## Evaluated but unapproved ideas

The following are not current implementation tasks:

- total global ordering through an exclusive, advisory-lock-backed consumer;
- a dedicated native-table partition fetch path;
- automatic hidden extraction of partition keys;
- cross-partition distributed transaction coordination; and
- additional hot-path metrics without an identified operational consumer.

Any of these requires a concrete use case, a decision in the consolidated register, and a bounded
TDD plan before implementation.

## References

- [Consolidated task register](../tasks/tasks.md)
- [Ordering patterns guide](../../docs/PEEGEEQ_ORDERING_PATTERNS_GUIDE.md)
- [Ordering patterns guide](../../docs/PEEGEEQ_ORDERING_PATTERNS_GUIDE.md)
- [Fan-out design](PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md)
- [Testing standards](../testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md)
