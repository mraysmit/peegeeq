# Partitioned Consumption Design and Implementation Record

**Status:** IMPLEMENTED — TASK 6 RELEASE GATE COMPLETE

**Created:** 2026-04-09

**Last reconciled:** 2026-09-17

**Repository baseline:** checked 2026-11-21 against `18c27376`; the earlier baseline `263309d8` no longer resolves after a history rewrite.

**Corrected:** 2026-11-21 against `18c27376`: baseline; retry and dead-letter claim removed;
known open defects added; `REFERENCE_COUNTING` use; watermark ignores `STOPPED` and `PAUSED`.

**Checked:** 2026-11-28 against `16cc90f9`. The release-gate figures below were compared with
the saved console of Jenkins build #11 and its host artifacts
(`logs/verify-cg-jenkins-build11-console-20261128.log`,
`logs/verify-cg-build11-host-baseline-20261128.log`,
`logs/verify-cg-build11-host-vmstat-20261128.log`); all match. The sequence, rebalance,
watermark, and lifecycle statements were compared with the six partitioned classes. The
watermark effect and defects P-4, P-5, P-6, P-13, and P-14 were run as probes on the Jenkins
host. Corrected on that date: the watermark effect is now a run result; P-6 and P-10 added to
the open defects.

**Release-gate source SHA-256:**
`485c930dd264864cd9157fe3378e25e661c51b97afa2120d1cc5bde6c0b54f27`

## Authority and scope

This document records the implemented `OFFSET_WATERMARK` partitioned-consumption design. It does
not maintain a separate numbered task list. The dated acceptance evidence for completed
[Task 6 is in the consolidated register](../tasks/archive/TASKS_REGISTER_TO_2026-11-21.md#6-partitioned-consumption-pre-ga-gates).

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
- fan-out trace branching; and
- focused lifecycle, ordering, failure, and concurrency tests.

Retry and dead-letter handling is not implemented on this path. `PartitionedConsumerEngine`
has no retry count and no dead-letter write. A handler that keeps failing stops its partition
key with no bound. See [Known open defects](#known-open-defects).

The implementation is not declared generally available from static inspection. Jenkins build #11
provided the dated runtime evidence for the documented Task 6 capacity, chaos, contention,
isolation, recovery, and cleanup envelope.

## Completion modes

The schema has two completion modes:

| Mode | Cursor model | Ordering | State in code |
|---|---|---|---|
| `REFERENCE_COUNTING` | Designed as per-message completion by subscribed groups | No processing-order guarantee | No production completion writer. A group on such a topic runs the queue claim, so groups compete and do not each receive every message. The [delivery outcome proposal](PEEGEEQ_DELIVERY_OUTCOME_REDESIGN_PROPOSAL.md#retirement-of-reference_counting-and-backfill) retires the mode. |
| `OFFSET_WATERMARK` | Per-group, per-partition committed offsets | Strict within one partition | Implemented. The only mode that delivers every message to every group. |

The mode is configured explicitly for a topic. `REFERENCE_COUNTING` remains the default. The
[fan-out design](PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md#completion-tracking-modes) states
the status of each mode.

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

`WatermarkCalculator.calculateWatermark` reads offsets only of subscriptions whose status is
`ACTIVE`. A `STOPPED` or `PAUSED` group is ignored. Its unread rows can therefore fall at or
below the watermark of the other groups, and the sweep then marks them `COMPLETED`. Run twice
on 2026-11-28 on the Jenkins host (`logs/verify-cg-probes-host-20261128.log`,
`logs/verify-cg-probes2-host-20261128.log`): two groups on one key, one committed past three
rows and one at 0 and then `PAUSED`; one `calculateAndSweep` marked all three rows `COMPLETED`.
The paused group never reads them. The proposal's phase 1 changes the watermark to count
`ACTIVE`, `STOPPED`, and `PAUSED`; that change is not in code.

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

## Release-gate evidence — complete

Jenkins build #10 qualified the stack-safe release harness for two minutes. Jenkins build #11 then
completed a clean reactor build, **89/89** focused database, native, schema-isolation, and OLTP
contention tests, followed by the explicitly selected `PartitionedConsumptionReleaseGate` at
**1/1**. The full build therefore published **90 passing tests** with zero failures, errors, or
skips. The release class deliberately has no normal `Test` suffix, so this one-hour owner gate is
not part of routine test profiles.

The sustained workload ran on an Ubuntu 24.04 host with 12 vCPU, 31 GiB RAM, 2 GiB swap, Docker
`29.1.3`, and a PostgreSQL `15.13-alpine3.20` Testcontainer. It ran for 3,600 seconds at 200
messages/second total across two isolated tenant schemas. Each tenant used two independent
consumer groups, four pool connections, a 512-byte payload, 50-row publish batches, and 16 initial
partitions. The test expanded both tenants live to 17 partitions and rebalanced at the midpoint.

Accepted results:

- each tenant published 360,033 messages at a measured 99.99 messages/second;
- every group received all 360,033 messages, for 720,066 stored messages and 1,440,132 handler
  deliveries overall;
- assertions found zero within-partition ordering violations and zero cross-tenant deliveries;
- both groups drained completely after the sustained window;
- each tenant reached watermark `360017`, with zero pending messages at or below the watermark
  and a bounded 16-row cross-partition tail above it;
- completed plus pending rows exactly matched published rows in both schemas;
- 34,792 and 34,789 OLTP probes succeeded, with bucketed p50/p95/p99 latency of 10 ms and no
  probe failures;
- bucketed delivery p50/p95/p99 latency was 1,000 ms;
- all assignments were removed after orderly engine shutdown;
- cluster-wide WAL growth was approximately 2.61 GB; and
- minute host samples generally showed 96–97% CPU idle, no swap use, and no capacity pressure.

Build #9 is diagnostic evidence only: its full-hour data workload completed, but recursively
composed harness futures overflowed the JVM stack during completion. Timer-scheduled, stack-safe
asynchronous publisher, probe, and drain loops were qualified in build #10 and accepted in build
#11. The consolidated register is authoritative for the exact focused-suite counts, host baseline,
source checksum, and release conclusion.

## Known open defects

The [delivery outcome proposal](PEEGEEQ_DELIVERY_OUTCOME_REDESIGN_PROPOSAL.md#offset-path-pub_sub)
records these defects of the offset path. None is fixed at `16cc90f9` (checked 2026-11-28).
The release gate above could not have shown P-4, P-5, or P-14; the proposal gives the reasons.
The Evidence column names the probe runs of 2026-11-28 on the Jenkins host
(`logs/verify-cg-probes-host-20261128.log`, `logs/verify-cg-probes2-host-20261128.log`); each
probe passed in both runs.

| ID | Defect | Evidence | Planned in |
|---|---|---|---|
| P-1 | A failing handler stops its partition key with no bound. No retry count, no dead letter. Finding 4 in the task register. | Run 2026-10-09 (proposal) | Proposal phase 7 |
| P-4 | The fetch takes `FOR UPDATE ... SKIP LOCKED`. A row that another transaction has locked is skipped, and the batch commit then moves the offset past it. | Run: two of five rows locked; the fetch returned the other three; after the commit the next fetch returned none, and the two rows stayed `PENDING` | Proposal phase 3 |
| P-5 | Offsets are ids. A row whose transaction commits after a higher id was consumed is never fetched, and the sweep later marks it `COMPLETED`. | Run: the lower id committed after the higher id was fetched and committed; it was never fetched and the sweep marked it `COMPLETED` | Proposal phase 3 |
| P-6 | The start position is not applied. `initializeOffset` inserts 0, and the offset path does not read the stored start bound. | Run: a `FROM_NOW` subscription stored a bound above two rows; the partitioned fetch returned both | Proposal phase 3 |
| P-10 | The engine, the fetcher, and the assignment service do not read `subscription_status`. A paused or stopped group keeps fetching. | Read: none of the three classes names the column (search 2026-11-28) | Proposal phase 3 |
| P-13 | The sweep completes rows that no group has read. A group or a key with no offset row adds nothing to the watermark. | Run: a row of a key with no offset row was marked `COMPLETED` by the sweep | Proposal phase 3 |
| P-14 | A running group does not take up a key that was absent at its last rebalance. Discovery runs only inside a join or a leave. | Run: after a new key appeared, 8 s of a running engine delivered and assigned only the first key | Proposal phase 3 |
| P-15 | An instance that stops without leaving keeps its keys until another instance joins or leaves. | Read | Proposal phase 3 |

## Evaluated but unapproved ideas

The following are not current implementation tasks:

- total global ordering through an exclusive, advisory-lock-backed consumer;
- a dedicated native-table partition fetch path;
- automatic hidden extraction of partition keys;
- cross-partition distributed transaction coordination; and
- additional hot-path metrics without an identified operational consumer.

Any of these requires a concrete use case, a decision in the consolidated register, and a bounded
TDD plan before implementation.

## Verification record, 2026-11-28

This record exists so that a reader can see which statement of this document was checked,
how, and with what result. Each claim of the document at `16cc90f9` was checked against the
code at `16cc90f9`. Line numbers are those of the document at `16cc90f9`. Evidence kinds:
**A** code read; **C** probe run on the Jenkins host; **D** saved log or console checked. The
probe runs, the probe source, and the guard run are those named in the verification record of
`PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md`: `logs/verify-cg-probes-host-20261128.log`,
`logs/verify-cg-probes2-host-20261128.log`, `logs/verify-cg-20261128/VerifyCgClaimsProbeTest.java`.

| Line | Claim | Verdict | Evidence |
|---|---|---|---|
| 9, 14-15 | Baseline; release-gate source SHA-256 `485c930d...` | CONFIRMED | D: build #11 console echoes the SHA-256 and checks out `263309d8`, which no longer resolves |
| 86-94 | Engine heartbeat and reconcile; new keys only through a rebalance | CONFIRMED | A: engine 259-321 (5 s), `PartitionAssignmentService` 66-140, 207-224; C: P-14 |
| 96-109 | Fetch, handle, commit sequence; generation fencing; at-least-once | CONFIRMED | A: engine 345-439, fetcher 58-81, offset manager 115-153 |
| 42-44 | No retry or dead letter on this path | CONFIRMED | A: engine has no retry count and no dead-letter write |
| 117-129 | Watermark counts `ACTIVE` only; a `PAUSED` group's unread rows are swept | CONFIRMED | A: `WatermarkCalculator` 42-56; C: three unread rows of a `PAUSED` group swept `COMPLETED` |
| 131-139 | Lifecycle contract | CONFIRMED (static) | A: engine 129-239; `OutboxConsumerGroup` start returns to `NEW` on failure |
| 168-173 | Build #11: 89 focused tests, then the gate 1 of 1, 90 in all, 0 failures | CONFIRMED | D: module totals 60, 26, 1, 2, then 1 |
| 175-179 | Host: Ubuntu 24.04, 12 vCPU, 31 GiB, 2 GiB swap, Docker 29.1.3, PostgreSQL 15.13 | CONFIRMED | D: `task6-host-baseline.log` artifact |
| 183-193 | 360,033 per tenant at 99.99/s; watermark 360017, 16 pending; OLTP 34,792 and 34,789; p50/p95/p99 10 ms; delivery 1,000 ms | CONFIRMED | D: `TASK6_SUMMARY` lines of the console |
| 195 | WAL about 2.61 GB | CONFIRMED | D: `walBytes=2610364416` |
| 196 | 96-97% idle, no swap | CONFIRMED | D: 59 of 61 vmstat samples at 96-97% idle, `swpd` 0 |
| 204-217 | Known open defects P-1, P-4, P-5, P-13, P-14, P-15, none fixed | CONFIRMED; P-6 and P-10 OMITTED | A; C: P-4, P-5, P-6, P-13, P-14 reproduced |

Logs of build #11: `logs/verify-cg-jenkins-build11-console-20261128.log`,
`logs/verify-cg-build11-host-baseline-20261128.log`,
`logs/verify-cg-build11-host-vmstat-20261128.log`.

## References

- [Consolidated task register](../tasks/tasks.md)
- [Ordering Guide](../../docs/PEEGEEQ_ORDERING_GUIDE.md)
- [Fan-out design](PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md)
- [Testing standards](../testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md)
