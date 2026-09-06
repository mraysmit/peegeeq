# PeeGeeQ Outbox Partitioned Ordering Guide

**Status:** IMPLEMENTED BASELINE — obsolete pre-implementation guidance removed
**Originally created:** 2025-10
**Reconciled:** 2026-09-06 against `7db748b8`

The former version of this guide correctly warned that ordinary concurrent consumption does not
guarantee per-key order, but it incorrectly continued to describe automatic assignment,
rebalancing, offsets, and fencing as future work. PeeGeeQ now provides those capabilities through
the `OFFSET_WATERMARK` completion-tracking mode.

The detailed implementation and historical TDD record are in the
[partitioned consumption design](../consumer-groups/PEEGEEQ_PARTITIONED_CONSUMPTION_DESIGN.md).
Current release work is controlled only by the
[consolidated task register](../tasks/tasks.md).

## 1. Choose the Correct Consumption Mode

PeeGeeQ supports two different consumer-group models:

| Capability | `REFERENCE_COUNTING` | `OFFSET_WATERMARK` |
|---|---|---|
| Completion tracking | Per-message group rows and counters | Per-group, per-partition committed offset |
| Concurrent throughput | Yes | Yes, across partitions |
| Built-in partition ownership | No | Yes |
| Strict processing order within one partition | Not guaranteed | Guaranteed by the partitioned engine contract |
| Reassignment after membership change | Reference-counting lifecycle only | Database-coordinated rebalance |
| Stale-owner protection | Per-message state | Monotonic generation fencing |
| Cleanup boundary | Reference counts | Topic watermark |

Use `REFERENCE_COUNTING` when fan-out delivery matters and handlers do not require strict ordering
by logical key. Use `OFFSET_WATERMARK` when messages sharing a logical partition key must be
processed sequentially while different partitions may proceed concurrently.

## 2. Partition Key

The existing `message_group` field is the partition key. Producers should choose a stable domain
identifier whose operations require ordering, such as an account, customer, aggregate, security,
or saga identifier.

Good partition keys:

- keep all state transitions for one entity in one partition;
- distribute traffic across enough independent entities;
- remain stable for the lifetime of the ordered stream; and
- avoid a single hot key dominating the workload.

A missing message group is mapped to the implementation's default partition. That preserves
compatibility but serializes all such messages together in ordered mode, so production workloads
should normally provide an explicit key.

## 3. Ordering Contract

For an `OFFSET_WATERMARK` topic, PeeGeeQ coordinates partition ownership in PostgreSQL and fetches
messages after the partition's committed offset in ascending identifier order.

The guarantee depends on all of these conditions:

1. the topic is configured for `OFFSET_WATERMARK` before consumption starts;
2. every logically related message uses the same stable `message_group`;
3. consumers use the partitioned consumer-group API or the automatically integrated native/outbox
   group path;
4. handler work completes successfully before its offset is committed;
5. stale generations are rejected after a rebalance; and
6. applications do not create a second, uncoordinated consumer path over the same ordered stream.

The guarantee is processing order within a partition. No global order is promised across
different partitions.

## 4. Assignment and Rebalancing

The partitioned engine discovers partition keys from stored messages. Active group members join
through the subscription service, and PostgreSQL serializes assignment changes. Assignments are
distributed consistently across the current members.

Membership changes cause a new rebalance generation. Fetches and commits carry that generation;
an owner from an older generation cannot commit progress after its partition has been reassigned.
Leaving a group removes that member's assignments and redistributes its partitions while
preserving committed offsets.

Heartbeats support stale-member detection. Rebalance and generation fencing replace the former
recommendation to build application-owned thread pools or in-memory queues for partition routing.

## 5. Offset and Failure Semantics

Each `(topic, group, partition)` has a committed offset and may have a pending in-flight boundary.
Successful handler completion advances the committed offset. A failed handler does not advance
past unprocessed work.

Delivery is at least once across crashes and ownership changes:

- committed work resumes after the committed offset;
- an uncommitted batch can be delivered again after takeover;
- a stale owner is fenced from committing after a newer generation exists; and
- external side effects remain the application's idempotency responsibility unless they share a
  deliberately coordinated PostgreSQL transaction.

Do not describe `OFFSET_WATERMARK` as universally exactly once. A transaction can make a
co-located PostgreSQL business write and offset update atomic, but arbitrary external effects
cannot participate in that local transaction.

## 6. Watermark Cleanup

The topic watermark is the minimum safe committed progress across relevant groups and partitions.
The watermark calculator advances that boundary and the cleanup path can complete messages below
it without creating per-message completion rows for every group.

Operators must monitor lagging groups because one stalled partition can hold back the safe cleanup
boundary and increase retained data.

## 7. API Surface

The implemented subscription service exposes operations to:

- join and leave a partitioned group;
- list an instance's current assignments;
- fetch a partition batch;
- commit a partition offset; and
- coordinate watermark progress.

Native and outbox consumer-group implementations detect `OFFSET_WATERMARK` topics and integrate
join, fetch, commit, heartbeat, and leave behavior into their lifecycle. Existing
`REFERENCE_COUNTING` topics retain their original behavior.

Refer to current interfaces and the partitioned-consumption design for exact method signatures;
do not copy signatures from older versions of this guide.

## 8. Operational Guidance

Monitor at least:

- assignment ownership and generation;
- heartbeat age and stale-member cleanup;
- committed and pending offsets per partition;
- partition lag and topic watermark;
- redelivery after failures or rebalances;
- hot-key distribution;
- handler latency and failure rates; and
- retained table size, database write load, and pool pressure.

An ordering alert must be based on an application sequence or another domain invariant. Database
identifiers prove fetch progression but do not prove that a producer supplied domain events in
the intended business order.

## 9. Application Adoption Checklist

This checklist belongs to each deployment. Unchecked items are not PeeGeeQ implementation tasks.

- [ ] Identify the domain entity that requires ordered processing.
- [ ] Select `OFFSET_WATERMARK` for the topic before production consumption.
- [ ] Populate a stable `message_group` for every ordered message.
- [ ] Confirm different partition keys may execute concurrently.
- [ ] Make external handler side effects idempotent.
- [ ] Define acceptable crash/rebalance redelivery behavior.
- [ ] Load-test the expected partition count and skew.
- [ ] Exercise consumer death, lease/heartbeat expiry, and reassignment.
- [ ] Monitor partition lag, watermarks, database load, and pool pressure.
- [ ] Record the application-specific ordering invariant and alert.

## 10. Remaining Project Release Gates

The implementation phases are complete. Long-duration stability, consumer-death chaos, OLTP
contention, concurrent tenant isolation, and partition lifecycle validation remain release gates.
Their status and eventual evidence belong in Task 6 of the consolidated task register, not in
this guide.

This document deliberately contains no throughput or latency promises. Capacity depends on
PostgreSQL configuration, hardware, handler behavior, partition skew, batch sizing, and the
number of active groups; it must be measured in the target environment.

## 11. References

- [Consolidated task register](../tasks/tasks.md)
- [Partitioned consumption design](../consumer-groups/PEEGEEQ_PARTITIONED_CONSUMPTION_DESIGN.md)
- [Consumer-group fan-out design](../consumer-groups/PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md)
- [Durable subscriptions option plan](PEEGEEQ_DURABLE_SUBSCRIPTIONS_OPTION_PLAN.md)
- [Coding principles](../dev/pgq-coding-principles.md)
- [Testing standards and antipatterns](../testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md)

