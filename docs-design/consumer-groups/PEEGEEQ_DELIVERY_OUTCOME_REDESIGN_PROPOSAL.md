# Delivery Outcome Redesign Proposal

**Status:** APPROVED for implementation, sixth revision. On 2026-10-12 the owner approved
the design with the corrections of this revision and ruled that no further design ruling
is required. Implementation proceeds one verified phase at a time. The
[task register](../tasks/tasks.md) holds the state of each phase.

**Written:** 2026-10-11, as a section of `PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md`.

**Moved to this file and revised:** 2026-10-12, at the owner's instruction, after the
owner's review of the nine decisions.

**Third revision:** 2026-10-12. The owner asked why listed files were still read in part.
211 test files and two more production classes were then read. The third revision said
they were read "in full" and that task 1 was complete. Both statements were wrong. The
[Reading Record](#reading-record) says what was read and how.

**Fourth revision:** 2026-10-12, after the owner's review of the third. It corrects the
reading, completion, and execution statements. It applies OD-1 to OD-16. It defines the
subscription lifecycle and the three replay contracts. It removes the unroutable expiry
rule, its setting, and `excluded_at`. It reorders the migration by the owner's eight
implementation phases. The owner ruled that the reading set is not expanded again.

**Fifth revision:** 2026-10-12, after the owner's review of the fourth. It reissues six
parts and nothing else. It adds the status `STOPPED` and revises the lifecycle table. It
restricts a keyed replay and corrects what other groups may receive. It fences the
administrative semantics migration with table locks and makes every claim and fetch
enforce the semantics. It moves backfill and the tracking table to phase 4. It defines the
operator dead-letter of one message. It corrects the wording on shared and per-group dead
letters. One point is new and not ruled: OD-22.

**Sixth revision:** 2026-10-12, after the owner's review of the fifth. The owner approved
corrections 1 to 6 and ruled on OD-22. Two choices of the fifth revision were rejected.
`STOPPED` now refuses claims, fetches, and heartbeats, and `stopGracefully()` is a
group-wide call. `close()` and `stop()` are instance-local and change no status; the owner
withdrew OD-17. A claim or a fetch on a topic whose semantics changed fails with an
explicit error and does not return an empty result.

This document is a proposal. It names tables, columns, settings, and types that do not
exist. No production code and no test code was changed to write it. The implemented
contract is [PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md](PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md).
The task list, the phase order, and the decisions are in the
[task register](../tasks/tasks.md), under "Finding 20 implementation tasks".

**Evidence labels.** *Run* means a test run produced the result, and the log is named.
*Read* means the statement comes from reading the source at the cited lines. *Test read*
means an existing test asserts the statement and the test was read. Read and Test read are
static findings. None was reproduced at run time. Line numbers refer to the working tree on
2026-10-11, `origin/master` at `f391a7f0`. No build, test, PostgreSQL, or Jenkins
execution was run for any 2026-10-12 revision.

A test file was read as a filtered print, not as the whole file
([Reading Record](#reading-record)). A statement marked Test read rests on that print. A
`peegeeq-db` test that extends `BaseIntegrationTest` runs on a hand-written schema, not on
a provisioned one (S-12). A statement marked Test read from such a test holds for that
schema.

---

## Table of Contents

1. [Purpose and End State](#purpose-and-end-state)
2. [Owner Rulings Applied](#owner-rulings-applied)
3. [The Three Contracts](#the-three-contracts)
4. [Data Model](#data-model)
5. [Subscription Lifecycle Contract](#subscription-lifecycle-contract)
6. [Migration Matrix](#migration-matrix)
7. [Delivery Outcome Contract](#delivery-outcome-contract)
8. [Replay Contracts](#replay-contracts)
9. [Queue Decisions](#queue-decisions)
10. [Retirement of REFERENCE_COUNTING and Backfill](#retirement-of-reference_counting-and-backfill)
11. [Consolidated Design Review](#consolidated-design-review)
12. [Reading Record](#reading-record)
13. [Phase Order and Test Plan](#phase-order-and-test-plan)
14. [Decisions](#decisions)
15. [Not Checked](#not-checked)

---

## Purpose and End State

One delivery of one message to one consumer group ends in exactly one outcome. Each
outcome has one durable effect in each delivery model. No outcome is carried by an
exception class, and no outcome is lost.

The work is finished when all of the following hold.

1. A message that a group's filter refuses is not delivered to that group again. It does
   not stop a later message for that group. It is not deleted while another consumer can
   process it. It is not dead-lettered for every consumer. Nothing expires it (OD-8).
2. A transient condition changes no durable progress and no retry count.
3. A failing handler is retried up to the configured limit. On a `QUEUE` topic and on a
   native queue the shared message is then dead-lettered for every consumer. On a
   `PUB_SUB` topic it is dead-lettered for that group only, and that group's later
   messages proceed.
4. A topic has one delivery model, fixed by its stored semantics. A topic with no
   `outbox_topics` row is a `QUEUE` topic. A consumer group whose semantics query fails
   does not start (OD-1). The normal create and update calls cannot change established
   semantics (OD-13).
5. Flyway and runtime provisioning produce the same schema, and tests run on it (OD-14).
6. One cleanup path deletes a message, and only when every obligation on it is terminal.
7. Outbox groups and native groups give the same outcome for the same input on a `QUEUE`
   topic. The native queue API refuses a `PUB_SUB` topic (OD-15).
8. A group stopped with `stopGracefully()` and started again resumes where it stopped
   (OD-12). An operator's pause survives a restart (OD-18).
9. Stopping, pausing, cancelling, or losing a consumer group never marks an unprocessed
   message as processed.
10. A group receives a message again only through one of three explicit replay
    operations. Nothing replays by itself.

## Owner Rulings Applied

The owner ruled on 2026-10-12, in three reviews. The task register holds the rulings. This
table says where each one lands in this document.

| ID | Ruling, in short | Where applied |
|---|---|---|
| OD-1 | No topic row means `QUEUE`. A query failure fails startup. | End state 4; [The Three Contracts](#the-three-contracts); T7-2, T7-5 |
| OD-2 | The migration stops and names every inconsistent topic. The operator resolves each. | [Migration Matrix](#migration-matrix), topic rows; MIG-1 |
| OD-3 | Create the exclusion table. Drop the tracking table only after every reader, writer, trigger, and test dependency is removed. | Data model; matrix, phase 4 |
| OD-4 | Amended in the third review. The log replay replaces backfill; the other two replays do not. Backfill, its six columns, and the tracking table go in phase 4, after phase 3 builds the log replay. | Matrix, phase 4; [Replay Contracts](#replay-contracts) |
| OD-5 | Breaking API changes are approved and recorded as breaking. `RejectedMessageException` is deleted when its replacement is complete. | Outcome rule 8; API inventory |
| OD-6 | Release per partition key. `PAUSED` holds retention and is exempt from dead detection. Topic watermarks go only after the replacement release path passes. | [Subscription Lifecycle Contract](#subscription-lifecycle-contract); matrix, phases 1 and 8 |
| OD-7 | No per-message rejection row on `PUB_SUB`. Its condition on backfill is replaced by the amended OD-4. | Replay contracts |
| OD-8 | Rejected. No dead letter from an incomplete consumer registry. Excluded messages stay pending until explicit replay or operator action. | [Queue Decisions](#queue-decisions) |
| OD-9 | Counters, cleanup fields, and topic watermarks stay until their replacements exist in the cleanup phase. | Matrix, phase 8 |
| OD-10 | Each red test and its narrow production correction land in the same phase. No committed phase is red. | [Phase Order and Test Plan](#phase-order-and-test-plan) |
| OD-11 | P-13 to P-15 are fixed before every `PUB_SUB` topic moves to offsets. | Phase 3, before phase 4 |
| OD-12 | A graceful stop performs no cleanup and keeps bounds and offsets. A restart reactivates the group. `cancel` stays explicit and terminal. The status it sets is now `STOPPED` (OD-18). | Lifecycle contract; phase 1 |
| OD-13 | Normal create and update calls cannot change established semantics. A separate administrative operation verifies both message stores, subscriptions, offsets, and assignments. | Matrix, "Administrative semantics migration"; phase 4 |
| OD-14 | The hand-written test schemas move to the Flyway test path. | Phase 2 |
| OD-15 | The native queue API is `QUEUE`-only and rejects `PUB_SUB`. | The Three Contracts; phase 4 |
| OD-16 | A heartbeat does not make a `DEAD` subscription `ACTIVE`. Reactivation needs an explicit start or a replay decision. | Lifecycle contract; phase 1 |
| OD-17 | Withdrawn by the owner in the fourth review: it conflated an instance close with a group transition. `stop()` and `close()` are instance-local and change no status. | Lifecycle contract, rule 7; R-9 |
| OD-18 | The recommendation was rejected. `STOPPED` is added. An operator's `PAUSED` survives a restart. | Lifecycle contract; phase 1 |
| OD-19 | Approved, with an atomic contract. | [Queue Decisions](#operator-dead-letter-of-one-message); phase 6 |
| OD-20 | Approved. `message_retention_hours` deletes terminal outbox `DEAD_LETTER` rows. Their dead-letter records stay. | Data model, Deletion; T12-2 |
| OD-21 | Approved. The replayed group receives later messages of the key again. The claim that no other group can receive a reopened row is removed. | Replay contracts 2 and 3 |
| OD-22 | Three cells accepted: a graceful stop on `PAUSED` stays `PAUSED`; `pause` on `STOPPED` gives `PAUSED`; `resume` on `STOPPED` fails. Two rejected: a heartbeat, a claim, and a fetch on `STOPPED` must fail; `close()` leaves the status unchanged. | Lifecycle contract; R-9, L-9 |

Also approved in the third review: `DeadConsumerGroupCleanup` is deleted with its callers
in phase 1; member dispatch stays in phase 5; native queue outcomes stay in phase 6; a
normal restart does not overwrite the stored start bound; the write validator counts
`ACTIVE`, `PAUSED`, and `STOPPED`.

Every decision is ruled. No design point is open.

## The Three Contracts

Topic delivery semantics, progress model, and rejection state are three contracts (owner,
2026-10-11). After `REFERENCE_COUNTING` is retired, the first fixes the other two.

| Topic semantics | Who consumes | Progress model | Source of truth for progress | Record of a group's permanent rejection |
|---|---|---|---|---|
| `QUEUE`. Also every topic with no `outbox_topics` row (OD-1). | Outbox consumers and groups, on `outbox`. Native consumers and groups, on `queue_messages`. | Shared status. Groups compete. One successful processing completes the message. | `outbox.status`. On the native queue, `queue_messages.status` and the existence of the row. | A row in `outbox_group_exclusions` or in `queue_message_group_exclusions` (both new) |
| `PUB_SUB` | Outbox groups only (OD-15) | Offsets. Each group processes each message, in order within a partition key. | `outbox_partition_offsets.committed_offset` per group and partition key | The group's offset moves past the message. No row is written (OD-7). |

The native queue API is `QUEUE`-only (OD-15). A native producer, consumer, or group on a
topic whose stored semantics are `PUB_SUB` fails. Today a native group on an
`OFFSET_WATERMARK` topic reads `outbox`, and a native producer writes `queue_messages`, so
that group never sees a natively produced message (P-11). That path is removed.

Every claim and every fetch enforces the topic's semantics, from phase 4. A mismatch is an
error. It is never an empty result: an empty result means only that no eligible message
exists. The queue claim of `OutboxConsumer`, for a plain consumer and for a group, fails
with a semantics-mismatch error on a topic whose stored semantics are `PUB_SUB`. The
offset fetch fails with it on a topic whose stored semantics are not `PUB_SUB`. The native
claim and the native send fail with it on a `PUB_SUB` topic. The consumer logs the error
at ERROR and stops. A check at start is not enough: a consumer created before a topic's
semantics changed keeps running
([Administrative semantics migration](#administrative-semantics-migration)).

The offset model has two users of the fetcher and the offset manager. The in-process
engine dispatches to a handler and commits. The caller-driven interface
(`SubscriptionService.joinPartitionedGroup`, `fetchPartitioned`, `commitOffset`, and the
REST routes over them) gives rows to a client that commits for itself. The outcome contract
binds the engine. A caller-driven client decides its own outcomes (rule 11). The phase 3
fixes are made in the fetcher and the offset manager, so both users get them.

## Data Model

Reissued in the fourth revision for the final review.

Approved in principle by the owner on 2026-10-12: `QUEUE` uses the shared `outbox.status`;
a queue group rejection is a `(message_id, group_name)` exclusion; `PUB_SUB` uses group and
partition offsets; a `PUB_SUB` rejection writes no per-message row; the abandoned
reference-counting fields and the bitmap are removed after their remaining readers are
replaced.

Changed in this reissue: `unroutable_retention_hours` and `excluded_at` are gone; every
removal names its phase; the start bound is kept by a restart; the three replay contracts
are defined and add no stored state. The fifth revision adds one status value, `STOPPED`,
and moves the backfill columns and the tracking table to phase 4. It adds no column.

### Stored state

Every stored field is either a source of truth or derivable. Derivable data is computed
and not stored. The phase numbers are those of the
[Phase Order and Test Plan](#phase-order-and-test-plan).

| State | Table and column | Classification | Decision |
|---|---|---|---|
| Delivery model of a topic | `outbox_topics.semantics` | Source of truth | Keep. It is the only input that selects the progress model. Once established it changes only through the administrative migration (OD-13). |
| Progress model of a topic | `outbox_topics.completion_tracking_mode` | Derivable from `semantics` once pub/sub always uses offsets | Remove in phase 4, after the two inconsistent pairings are resolved by the operator (OD-2). |
| Retention settings | `outbox_topics.message_retention_hours`, `zero_subscription_retention_hours`, `block_writes_on_zero_subscriptions` | Source of truth | Keep. |
| Group membership of a topic | `outbox_topic_subscriptions` row, `subscription_status` | Source of truth | Keep. A fifth value, `STOPPED`, is added in phase 1 (OD-18). The [lifecycle contract](#subscription-lifecycle-contract) fixes every transition. REST stores some subscriptions under a topic name that no message has (M-7). |
| Group start bound | `start_from_message_id`, `start_from_timestamp` | Source of truth | Keep. Set when the row is created. A later start keeps it (OD-12). Only the log replay lowers it. One predicate defines it for both models (P-6). The stored bound does not record which start option produced it. |
| Group liveness | `last_heartbeat_at`, `heartbeat_*`, `consecutive_misses`, `dead_after_misses` | Source of truth | Keep. A running in-process group sends the heartbeat (X-6). A heartbeat changes no status (OD-16). |
| Backfill progress | Six `backfill_*` columns | Progress of a job whose only message effects are reference-counting writes | Remove in phase 4, after phase 3 implements and tests the log replay (OD-4, amended). |
| Queue message state | `outbox.status` on a `QUEUE` topic | Source of truth | Values in use: `PENDING`, `PROCESSING`, `COMPLETED`, `DEAD_LETTER`. |
| Release of a log message | `outbox.status` on a `PUB_SUB` topic | Source of truth. It cannot be derived later, because subscriptions change after the release. | Values in use: `PENDING`, `COMPLETED`. Only the release path sets `COMPLETED`. Only the log replay sets it back to `PENDING`. |
| Shared retry count | `outbox.retry_count` | Source of truth on `QUEUE` topics | Keep. Not used on `PUB_SUB` topics. |
| Claim time, then terminal time | `outbox.processed_at` | Source of truth, with two meanings. While a row is `PROCESSING` it is the claim time, which stuck-message recovery tests (`OutboxConsumer` 382; `StuckMessageRecoveryManager` 151 to 153). On a terminal row it is the terminal time. | Keep both meanings. Every statement that sets a terminal status sets it (Q-8, P-8). `outbox.processing_started_at` has no production writer. |
| Partition key | `outbox.message_group` | Source of truth | Keep. A `NULL` value is the partition key `__default__` (`PartitionAssignmentService` 209). |
| Required group count | `outbox.required_consumer_groups` | Stored copy of a count that subscriptions and offsets give | Remove in phase 8, with `trigger_set_required_consumer_groups` (OD-9). Until then the present cleanup definitions name it. |
| Completed group count | `outbox.completed_consumer_groups` | No production statement increments it | Remove in phase 8. It is not replaced by a derived value. |
| Bitmap | `outbox.completed_groups_bitmap` | Written as 0, never read | Remove in phase 8, with the trigger that writes it. The bitmap design is deleted. |
| Per-group message rows | `outbox_consumer_groups` | State of the fan-out that is to be retired | Remove in phase 4, only after every reader, writer, trigger, and test dependency is gone (OD-3). The list is in the [matrix](#the-tracking-table-and-its-dependencies). |
| Queue exclusion | `outbox_group_exclusions` (new) | Source of truth | Add in phase 6. Two columns: the message and the group name. |
| Group progress on a log topic | `outbox_partition_offsets.committed_offset`, `generation` | Source of truth | Keep. |
| Last fetch | `outbox_partition_offsets.pending_offset`, `pending_since` | Source of truth for the last fetch | Keep. Its age is the operator's signal for a stopped partition. No production statement or branch reads either column (search of every `src/main` tree). |
| Retry state of the first undelivered message | `outbox_partition_offsets.retry_message_id`, `retry_count` (new) | Source of truth. It must survive a restart and a rebalance. | Add in phase 7. |
| Partition ownership | `outbox_partition_assignments` | Source of truth | Keep. |
| Rebalance fence | `outbox_topic_subscriptions.rebalance_generation` | Source of truth | Keep. The log replay raises it, as a rebalance does. |
| Topic watermark | `outbox_topic_watermarks.watermark_id` | Stored maximum of a topic-wide minimum. It never moves back (`WatermarkCalculator` 66 to 84), and the sweep uses it. | Remove in phase 8, after the per-partition release passes (OD-6, OD-9). Until then phases 1 and 3 correct how it is computed, and the log replay lowers it. |
| Dead letter record | `dead_letter_queue` row | Source of truth | Keep. Add nullable `group_name`, the consumer group, in phase 7. The existing column `message_group` is the partition key and not a group. |
| Native message state | `queue_messages.status`, `lock_until`, `retry_count` | Source of truth | Keep. |
| Native exclusion | `queue_message_group_exclusions` (new) | Source of truth | Add in phase 6. |
| Not classified | `outbox.max_retries`, `next_retry_at`, `processing_started_at`, `version`; `outbox_topic_subscriptions.durable_enabled` | `max_retries` is read by the retired retry service, and by `OutboxConsumer` 877 to 896 only when the consumer has neither a consumer config nor a configuration; `OutboxFactory` always supplies one. No production reader or writer was found for `next_retry_at` and `processing_started_at`. The producer writes `max_retries` from configuration at insert (test read: `RetryDebugTest`), so it is a stored copy of a setting. `durable_enabled` comes from `V012` and template `08b`; no production Java file names it. | Outside finding 20 unless the owner adds them. |

### New and changed objects

Proposed text. It has not been applied to any database. The final text is written in the
phase that adds each object.

```sql
-- Phase 6
CREATE TABLE outbox_group_exclusions (
    message_id  BIGINT       NOT NULL REFERENCES outbox(id) ON DELETE CASCADE,
    group_name  VARCHAR(255) NOT NULL,
    PRIMARY KEY (message_id, group_name)
);

CREATE TABLE queue_message_group_exclusions (
    message_id  BIGINT       NOT NULL REFERENCES queue_messages(id) ON DELETE CASCADE,
    group_name  VARCHAR(255) NOT NULL,
    PRIMARY KEY (message_id, group_name)
);

-- Phase 7
ALTER TABLE dead_letter_queue ADD COLUMN group_name VARCHAR(255);

ALTER TABLE outbox_partition_offsets
    ADD COLUMN retry_message_id BIGINT,
    ADD COLUMN retry_count      INT NOT NULL DEFAULT 0;
```

- An exclusion row exists only for a permanent rejection. The row is the outcome. It has
  no status column and no reason column, because the reason is always the group filter.
- It has no time column. No contract in this document reads the time of a rejection: the
  expiry rule is rejected (OD-8), and the queue replay selects by topic, group, and message
  id. One consequence: a replay cannot select rejections by when they happened.
- The foreign key deletes the row with its message. No job cleans exclusions.
- `group_name` is the group's name, not a subscription id. A group started with `start()`
  on a queue topic has no subscription row.
- The primary key serves the claim. The group claim in `OutboxConsumer` (380 to 394) gains
  one predicate. A plain consumer has no filter and does not get it.

  ```sql
  AND NOT EXISTS (SELECT 1 FROM outbox_group_exclusions x
                  WHERE x.message_id = outbox_message.id AND x.group_name = $n)
  ```
- `dead_letter_queue.group_name` is `NULL` when the message was dead-lettered for every
  consumer, which is the shared-status and native case. A value means that group only, and
  the source row stays for the other groups.
- `retry_message_id` names the message the count belongs to. Both columns are reset by the
  statement that moves the offset, forward or back.

### Derived values

None of these is stored.

- **Available to a group on a `QUEUE` topic:** `status = 'PENDING'`, inside the group's
  start bound, and no exclusion row for the group. A group whose subscription is `PAUSED`
  claims nothing. A claim on a `STOPPED`, `DEAD`, or `CANCELLED` subscription, or on a
  topic whose semantics are `PUB_SUB`, is an error and not an empty result. The semantics
  test arrives in phase 4 and the status test in phase 6 (Q-4).
- **Start bound:** the predicate at `OutboxConsumer` 350 to 359: `id >=
  start_from_message_id` when that is set, and `created_at >= start_from_timestamp` when
  that is set. The offset fetch applies the same predicate from phase 3 (P-6). The native
  queue claim already applies it (test read: `ConsumerGroupSubscriptionTest`, four start
  positions).
- **Position of a group on a partition key:** the committed offset of its offset row. A
  group with no offset row for the key has consumed nothing of it, and the start bound
  alone limits what it receives. Today a group or a key with no offset row holds nothing
  (P-13).
- **Obligation on a `PUB_SUB` topic:** a subscription holds an obligation on a row when
  its status is `ACTIVE`, `STOPPED`, or `PAUSED`, the row is inside its start bound, and the row's id
  is above the group's position for the row's partition key.
- **Release on a `PUB_SUB` topic:** a `PENDING` row is released when no subscription holds
  an obligation on it and either some `ACTIVE`, `STOPPED`, or `PAUSED` subscription had it inside its
  start bound, or the row is older than `zero_subscription_retention_hours`. Release sets
  `COMPLETED` and `processed_at`. The rule is per partition key. It replaces the
  topic-wide minimum in phase 8 (P-7). Until then the watermark stays, corrected in
  phase 1 to count `PAUSED` and `STOPPED` subscriptions and in phase 3 to count keys with
  no offset row.
- **Partition keys of a `PUB_SUB` topic:** the distinct `message_group` values of its rows
  that are not released. They are not stored. A running group finds new keys without a
  join from phase 3 (P-14).
- **Excluded backlog of a `QUEUE` topic:** the pending messages that have at least one
  exclusion row, their count, and the age of the oldest. It is the operator's view of
  rejected work (OD-8).
- **Rows released past a group:** the `COMPLETED` rows of a `PUB_SUB` topic that are inside
  the group's start bound and above its position for their key. A start on a `DEAD`
  subscription logs the count (OD-16).
- **Deletion:** a row is deleted when its status is `COMPLETED` or `DEAD_LETTER` and
  `processed_at` is older than `message_retention_hours` (OD-20). The dead-letter record
  of a `DEAD_LETTER` row stays in `dead_letter_queue` for 30 days (rule 10 of the outcome
  contract). Every statement that sets `DEAD_LETTER` sets `processed_at` (Q-8).
- **Counts shown by REST and the UI** are queries over these tables. `ForceRemoveResult`
  and `getBlockedMessageStats` lose their reference-counting fields in phase 4.

## Subscription Lifecycle Contract

Reissued in the sixth revision with the owner's ruling on OD-22. It applies OD-6, OD-12,
OD-16, OD-18, and OD-22. It adds one status value, `STOPPED`. It adds no column.

A graceful stop and an operator's pause are two contracts. `STOPPED` records the stop of
the whole group. `PAUSED` records only the operator's pause.

The subscription row belongs to the group. An instance is one running consumer group
object. A group can have several instances, in several processes, and they share the row.
Each call is either group-wide or instance-local.

- Group-wide: `start` and `subscribe`, `stopGracefully()`, `pause`, `resume`, `cancel`.
  They change the row.
- Instance-local: `stop()` and `close()`. They stop or release one instance and change no
  status. One instance cannot declare the whole group stopped.

### The five statuses

| Status | Meaning | A running instance may claim or fetch | Holds retention on a `PUB_SUB` topic | Counted by the write validator | Dead detection |
|---|---|---|---|---|---|
| `ACTIVE` | An instance of the group is expected to run. | Yes | Yes | Yes | Applies |
| `STOPPED` | The whole group was stopped with `stopGracefully()`. | No. The call fails and the instance stops. | Yes | Yes | Exempt |
| `PAUSED` | An operator paused the group. | No. The instance keeps running and waits. | Yes | Yes | Exempt |
| `DEAD` | The heartbeat timed out. | No. The call fails and the instance stops. | No | No | Done |
| `CANCELLED` | The subscription was cancelled. Terminal. | No. The call fails and the instance stops. | No | No | Done |

What changes to match the table.

- The check constraint on `subscription_status` gains `STOPPED` in phase 1. It is defined
  in three places: `V010` line 50, changed by a new Flyway migration; template `08b`
  line 7; and the hand-written test schema (`SharedPostgresTestExtension` line 272), which
  exists until phase 2.
- The enums `SubscriptionState` (`peegeeq-api`) and `SubscriptionStatus` (`peegeeq-db`)
  gain the value. Their tests assert four values with fixed ordinals and change. The event
  store's subscription shares `SubscriptionState`. It never writes `STOPPED`, and its own
  constraint is not changed.
- Dead detection reads `ACTIVE` subscriptions only. Today it reads `ACTIVE` and `PAUSED`
  (`DeadConsumerDetector` 167, 176, 235, 248, 257, 375).
- The watermark and, later, the release rule count `ACTIVE`, `STOPPED`, and `PAUSED`.
  Today the watermark counts `ACTIVE` only (`WatermarkCalculator` 49).
- `ZeroSubscriptionValidator` counts `ACTIVE`, `STOPPED`, and `PAUSED`. Today it counts
  `ACTIVE` only (line 71).
- A search of the main trees finds 17 production files that name `PAUSED`: the two enums,
  `V010` and template `08b`, `SubscriptionManager`, `DeadConsumerDetector`,
  `SubscriptionService`, `PeeGeeQInfoCodes`, two REST handlers, three management UI files,
  and four that belong to the event store or to the webhook status. Phase 1 reads each
  and adds `STOPPED` where the subscription status is listed.

How a status reaches a running instance. A heartbeat, a claim, and a fetch on a `STOPPED`,
`DEAD`, or `CANCELLED` subscription fail and name the status. The instance logs the error
at ERROR and stops. It does not start again by itself. On a `PAUSED` subscription the
heartbeat sets the time, and the claim and the fetch return nothing: the instance keeps
running and consumes again after `resume`.

Neither the claim nor the fetch tests the status today (Q-4, P-10). Phase 1 adds the
in-process heartbeat, so an instance learns of `STOPPED`, `DEAD`, or `CANCELLED` within one
heartbeat interval and stops. The fetch tests the status from phase 3 and the queue claim
from phase 6. Until then a paused group that is running keeps consuming, as it does today.

### Transitions

Each cell gives the status after the call. "Fails" means the returned `Future` fails and
the row is unchanged.

| Call | No row | `ACTIVE` | `STOPPED` | `PAUSED` | `DEAD` | `CANCELLED` |
|---|---|---|---|---|---|---|
| `start(SubscriptionOptions)`, `subscribe` | Creates the row `ACTIVE` with the start bound of the options. | `ACTIVE`. The stored bound is kept. | `ACTIVE`. Bound and offsets kept. | `PAUSED`. The instance starts and consumes nothing until `resume`. | `ACTIVE`. Bound and offsets kept. The call logs at WARN the count of rows released past the group. | Fails. |
| `stopGracefully()` | Nothing to change. | `STOPPED`. No message changes. | `STOPPED`. | `PAUSED`. | `DEAD`. | `CANCELLED`. |
| `stop()`, `close()` | Nothing to change. | `ACTIVE`. | `STOPPED`. | `PAUSED`. | `DEAD`. | `CANCELLED`. |
| `pause` | Fails. | `PAUSED`. | `PAUSED`. | `PAUSED`. | Fails. | Fails. |
| `resume` | Fails. | `ACTIVE`. | Fails. | `ACTIVE`. | `ACTIVE`. This is an explicit decision (OD-16). | Fails. |
| Heartbeat of a running instance | Fails. | `ACTIVE`. The heartbeat time is set and the miss count cleared. | Fails and names the status. | `PAUSED`. The time is set. | Fails and names the status. | Fails and names the status. |
| Claim or fetch by a running instance | Allowed on a queue topic for a group started with `start()`. | Allowed. | Fails and names the status. | Returns nothing. | Fails and names the status. | Fails and names the status. |
| Dead detection | | `DEAD`, after the timeout and the miss count. | `STOPPED`. | `PAUSED`. | `DEAD`. | `CANCELLED`. |
| `cancel` | Fails. | `CANCELLED`. | `CANCELLED`. | `CANCELLED`. | `CANCELLED`. | `CANCELLED`. |

Rules that the table implies.

1. No call in the table changes a message. The dead-group cleanup that marks pending
   messages `COMPLETED` loses its three callers in phase 1, and the class is deleted with
   them: the detection job, `cancel`, and force-remove (X-4, X-5, X-10).
2. `CANCELLED` is terminal. Before increment 1b of phase 1 it was not: `pause` moved a
   cancelled subscription to `PAUSED`, and `resume` then made it `ACTIVE` (X-11).
3. The stored start bound is authoritative (OD-23). The first subscription creates it. A
   later `subscribe` or start never changes it. A start uses the stored bound on the
   outbox path and on the native path (N-11). Only a replay moves an existing bound.
   The bound is a message id of one of two tables. The caller that creates the
   subscription names the table as a `SubscriptionStore`, and `FROM_NOW` is resolved
   against it. The store is not stored: it is derived from the queue.
   Before increment 1b of phase 1 every `subscribe` overwrote it, two test classes
   asserted that as intended, and the REST call `updateSubscriptionOptions` depended on
   it. This document did not name that call before phase 1. Increment 1b implemented
   the rule and the [REST settings update](#rest-settings-update). The automatic
   backfill follows the stored bound: it starts only when a `subscribe` names
   `FROM_BEGINNING` and the stored bound is the beginning. A `subscribe` on an existing
   subscription resolves no start position and reads no message table.
4. Only `resume` ends an operator's pause. A start, a graceful stop, and a heartbeat all
   leave `PAUSED` unchanged.
5. A heartbeat changes no status. It never revives `DEAD` (OD-16). Before increment 1b
   of phase 1 it made `DEAD` `ACTIVE` and could start a backfill (X-3).
6. `stopGracefully()` is group-wide. It sets `STOPPED`. Every running instance of the
   group stops when its next heartbeat, claim, or fetch is refused. Only an explicit start
   makes the group `ACTIVE` again.
7. `stop()` and `close()` are instance-local and change no status. The factory close hook
   closes each group at manager shutdown (`OutboxFactory` 141), and that changes no status
   either. The owner withdrew OD-17, which had made a planned close a group transition.
8. A planned outage of the whole group calls `stopGracefully()` or `pause` first. If every
   instance only closes, the row stays `ACTIVE`, no heartbeat arrives, and detection marks
   it `DEAD` after the timeout.
9. If the status update of a graceful stop fails, the group still stops, and the returned
   `Future` fails. That settles N-10 on the native behaviour.
10. On a `PUB_SUB` topic `start()` is `start(SubscriptionOptions.defaults())`. The offset
    path needs the subscription row: the rebalance locks it and fails without it
    (`PartitionAssignmentService` 189 to 204). On a `QUEUE` topic `start()` creates no row,
    as today.
11. Force-remove keeps its present effect on the subscription row and changes no message.

### REST settings update

Ruled by the owner on 2026-10-12, in the review of phase 1, increment 1a (OD-23). The
endpoint is `POST /api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription`.
Before the ruling it called `subscribe` on an existing row. `subscribe` then overwrote
the bound and the heartbeat settings and made the row `ACTIVE`.

| Request | Result |
|---|---|
| No subscription row exists. | The subscription is created from the request. |
| The row exists. The request names heartbeat settings. | Only the named heartbeat settings change. An omitted field keeps its stored value. |
| The row exists, in any status. | The lifecycle status is unchanged. |
| `startPosition` is omitted. | No bound change is requested. |
| The request names a bound equal to the stored bound. | Accepted. |
| The request names a bound that differs from the stored bound. | HTTP 409. Nothing changes. |
| The request names `FROM_NOW`, and the row exists. | HTTP 409. `FROM_NOW` does not identify the stored numeric bound. |

The response returns the persisted options, not the submitted object. The update path
does not call `subscribe`. It is a distinct settings-update operation:
`SubscriptionService.updateSubscriptionSettings`, implemented in increment 1b of phase 1.

The start position name in a response is derived from the stored bound. A stored message
id of 1 is reported as `FROM_BEGINNING`, and a request that names `FROM_BEGINNING` is
equal to it. `FROM_NOW` on an empty topic stores 1.

An invalid value, a malformed `startFromTimestamp`, and a request with no body return
HTTP 400. A heartbeat timeout is checked against the stored interval, and an interval
against the stored timeout.

### Consequences that must be stated

1. **`stopGracefully()` on one instance stops every instance.** Trigger: an application
   calls it on one instance while others keep running, for example in a rolling restart.
   Effect: every other instance logs ERROR and stops consuming. A start sets `ACTIVE`
   again, but an instance that has stopped does not start again by itself. In a rolling
   restart that calls `stopGracefully()` on each instance in turn, only the instance
   restarted last still consumes at the end. Detection: the ERROR log of each stopped
   instance. Protection: none in the code. An instance that leaves a group that keeps
   running calls `close()`.
2. **A shutdown that only closes ends retention after the timeout.** Trigger: a crash, a
   kill, or a shutdown in which every instance only closes, which is what manager shutdown
   does through the factory close hook. The row stays `ACTIVE` with no heartbeat and
   becomes `DEAD` after the timeout and the miss count. The defaults are 300 seconds and 3
   detection runs. From then its unread rows on a `PUB_SUB` topic can be released. On
   restart the group receives only rows still `PENDING`. Recovery: the log replay reopens
   released rows that are not yet deleted. After `message_retention_hours` they are
   deleted and cannot be recovered. Protection: the application calls `stopGracefully()`
   or `pause` before a planned outage of the whole group. Detection: the detection job
   logs the group; the start logs the count of rows released past it.
3. **A stopped or paused subscription holds retention with no bound.** Trigger: a group
   is stopped with `stopGracefully()` and never started again, or paused and not resumed.
   Effect: on a `PUB_SUB` topic every row above that group's position stays `PENDING`, and
   the table grows. Delivery to other groups is not affected. Detection: nothing today.
   Phase 8 adds one derived value to the health summary: the age of the oldest pending row
   that only stopped or paused groups hold (T12-8). Protection: none. The operator cancels
   the subscription.
4. **A live group that misses its heartbeat stops for good.** A database outage or a long
   pause in the process can make detection mark a running group `DEAD`. Its heartbeat then
   fails, and it stops consuming until an explicit start or `resume` (OD-16). Before
   OD-16 the next heartbeat revived it.
5. **`resume` does not know whether an instance runs.** It makes a paused subscription
   `ACTIVE`. If no instance runs, detection marks it `DEAD` after the timeout and its
   retention ends. A paused instance that runs still sends the heartbeat, so
   `last_heartbeat_at` shows the operator whether one does.

## Migration Matrix

Reissued in the fourth revision for the final review. Proposed. Not run. The order follows
the owner's eight implementation phases. Backfill, its columns, and the tracking table go
in phase 4, after phase 3 builds the log replay (OD-4, amended). Counters, cleanup fields,
and topic watermarks are dropped last, in phase 8, after their replacements exist (OD-9).

### Topic rows

The stored pairing of `semantics` and `completion_tracking_mode` decides what the phase 4
migration does. The column "What runs today" is Read, not Run.

| Stored `semantics` | Stored mode | What runs today | Phase 4 migration |
|---|---|---|---|
| No row | None | The queue path. Four tests assert it: the insert trigger, the present cleanup, the native group start, and the write validator. | No action. No row means `QUEUE` (OD-1). |
| `QUEUE` | `REFERENCE_COUNTING` | The queue path. This is the default pairing. | No action. |
| `PUB_SUB` | `OFFSET_WATERMARK` | The offset path, when the group has a connection manager and the mode query succeeds (M-2). | No action. |
| `PUB_SUB` | `REFERENCE_COUNTING` | An outbox group runs the queue claim with the subscription start bound. Groups compete. A native group reads `queue_messages` (test read). | The migration stops and names the topic (OD-2). The operator chooses. Keep `PUB_SUB`: set the mode to `OFFSET_WATERMARK`. Or make it a queue: run the administrative semantics migration to `QUEUE`. A topic with rows in `queue_messages` can only become `QUEUE` (OD-15). |
| `QUEUE` | `OFFSET_WATERMARK` | Mixed. `start(SubscriptionOptions)` runs the offset path, `start()` runs the queue claim (M-1), and the insert trigger counts the topic as a queue. The test suite holds this pairing: `PartitionedConsumptionRestIntegrationTest` inserts the mode alone, so the semantics default to `QUEUE`. | The migration stops and names the topic (OD-2). The operator chooses. Keep `QUEUE`: set the mode to `REFERENCE_COUNTING`. Or make it a log: run the administrative semantics migration to `PUB_SUB`. |

When no row is in the last two pairings, the mode is a function of the semantics on every
row. The migration then drops the column. It drops nothing while one such row exists.

A mode change keeps the semantics and uses `updateTopic`, which exists. A semantics change
uses the administrative migration below. Both must exist before the phase 4 migration is
run against a deployed schema.

Risk of the `OFFSET_WATERMARK` choice, read and not run. Offsets initialise to zero
(`PartitionedOffsetManager` 63 to 95) and the partition fetch ignores the subscription
start bound (P-6). A group moved to the offset path may be given rows still `PENDING` or
`PROCESSING` from before its subscription. Phase 3 fixes the start bound, and P-13 to P-15,
before phase 4 moves any topic (OD-11). MIG-2 must show the result.

### Administrative semantics migration

Required by OD-13. The owner accepted its fence in the fourth review. The fourth revision
had locked only the topic row. That does not stop a producer: both producers insert into
their message table without reading or locking `outbox_topics` (owner's review of the
fourth revision). The operation locks the tables, and every claim and fetch enforces the
semantics itself.

`createTopic` and `updateTopic` refuse a change of established semantics. Semantics are
established when the topic has an `outbox_topics` row, or a message in either store, or a
subscription. A topic with no row is `QUEUE`, so creating a `PUB_SUB` row for a name that
already holds messages or subscriptions is a change and is refused. Creating a `QUEUE` row
for it is not.

The administrative operation is a separate call on the topic configuration service. Its
name is set in phase 4. It runs in one transaction.

1. It sets a lock timeout for the transaction. The value is set in phase 4. The event
   store's writer barrier uses 5 seconds.
2. It locks five tables, always in this order: `outbox`, `queue_messages`,
   `outbox_topic_subscriptions`, `outbox_partition_assignments`,
   `outbox_partition_offsets`. The proposed mode is `SHARE ROW EXCLUSIVE`. It blocks every
   insert, update, and delete on the table, and only one transaction can hold it. It does
   not block a plain read.
3. It locks the topic row, if there is one. The sweep and the log replay take the same
   row lock.
4. It verifies the subscriptions. None may be `ACTIVE`. Each group is stopped, paused,
   dead, or cancelled, or the topic has none.
5. It verifies the assignments. `outbox_partition_assignments` holds no row for the topic.
6. It verifies both message stores. `outbox` holds no `PROCESSING` row and
   `queue_messages` holds no `LOCKED` row for the topic, so no delivery is in flight. For
   the target `PUB_SUB`, `queue_messages` holds no row for the topic at all (OD-15).
7. It handles the offsets. For the target `QUEUE` it deletes the topic's offset rows and
   its watermark row. For the target `PUB_SUB` it keeps existing offset rows as the
   groups' positions and reports their number.
8. It sets the semantics. It inserts the topic row when there is none.

The commit releases the locks. If a check fails the operation changes nothing and returns
a report that names what failed, with counts. On success it returns the counts of pending
messages per store, the subscriptions by status, and the offset rows deleted or kept.

**What the locks cost.** While the transaction runs, writes and starts wait on every
topic, not only on the migrated one.

- Every producer on both stores waits.
- Every claim, every offset commit, and every exclusion or status update waits.
- Every subscribe, start, stop, pause, resume, cancel, and subscription heartbeat waits.
- Every join, leave, and assignment heartbeat waits.

The wait lasts as long as the checks. The checks count rows of one topic in both message
tables. Their cost was not measured. If a lock is not granted within the timeout, the
operation fails and nothing changed. A transaction that has already written one of the
five tables and then needs another can deadlock with the operation. PostgreSQL ends one of
the two. If it ends the operation, nothing changed, and the operator runs it again.

**Consumers the checks cannot see.** A plain consumer has no subscription row. A group
started with `start()` on a queue topic has none either. The checks do not find them, and
the locks only delay them. So each claim and fetch enforces the semantics, and a mismatch
is an error, as [The Three Contracts](#the-three-contracts) states. An empty result must
mean that no eligible message exists.

- The queue claim fails with a semantics-mismatch error on a topic whose stored semantics
  are `PUB_SUB`. This holds for a plain consumer and for a group, and for one created
  before the change.
- The offset fetch fails with it on a topic whose stored semantics are not `PUB_SUB`.
- The native claim and the native send fail with it on a `PUB_SUB` topic.
- A new plain consumer's `subscribe` on a `PUB_SUB` topic fails.

The consumer logs the error at ERROR and stops. It does not poll again. The check reads
`outbox_topics` by its key. Its cost was not measured. The fifth revision let such a
consumer receive nothing with no signal. The owner rejected that.

**What the change does to pending messages**, stated in the report. Target `QUEUE`: every
`PENDING` row is delivered once more in total, to whichever group claims it, including a
row that some groups had already processed. Target `PUB_SUB`: each subscription receives
the `PENDING` rows inside its start bound and above its position.

After the change each application starts its group again. The start reactivates a stopped
subscription. A paused one needs `resume`.

### Schema objects, by phase

Phases 3 and 5 change no schema. If the phase 3 design for P-5 needs stored state, the
data model returns for approval before that phase continues.

| Phase | Object | Change | Precondition | Existing data |
|---|---|---|---|---|
| 1 | The check constraint on `outbox_topic_subscriptions.subscription_status` | Add the value `STOPPED` | None | None. Three definitions change: a new Flyway migration, template `08b`, and the hand-written test schema. |
| 2 | Runtime templates | Made equal to the Flyway result (S-1 to S-4) | T5-1 is red, then green | None |
| 2 | Hand-written test schemas (S-12) | Deleted from test code | OD-14 | None |
| 4 | `outbox_topics.completion_tracking_mode` | Drop | No row is in a stopped pairing. Every start path reads `semantics`. | Discarded. It is derivable. |
| 4 | Six `backfill_*` columns of `outbox_topic_subscriptions` | Drop | The log replay is implemented, and RP-L1 to RP-L8 are green. `BackfillService` and its API are deleted (OD-4, amended). | Discarded. |
| 4 | `outbox_consumer_groups`; `trigger_create_consumer_group_entries` and its function; `register_consumer_group_for_existing_messages`; its indexes | Drop | Every reader, writer, trigger, and test dependency below is gone (OD-3). MIG-4 has counted the rows. | Discarded. Irreversible. |
| 6 | `outbox_group_exclusions`, `queue_message_group_exclusions` | Create | None | Empty |
| 7 | `dead_letter_queue.group_name` | Add, nullable | None | Existing rows get `NULL`, which means every consumer. |
| 7 | `outbox_partition_offsets.retry_message_id`, `retry_count` | Add | None | `NULL` and 0 |
| 8 | `outbox_topic_watermarks` | Drop | The per-partition release is implemented. T12-3 and T10-10 are green (OD-6). | Discarded. It is derivable. |
| 8 | `outbox.required_consumer_groups`, `completed_consumer_groups`, `completed_groups_bitmap`; `trigger_set_required_consumer_groups` and its function; `idx_outbox_fanout_completion`, `idx_outbox_fanout_cleanup` | Drop | The one cleanup path is implemented and T12-1, T12-2, and T12-4 are green. No code and no test names them. | Discarded. No value is derived from it. |
| 8 | `cleanup_completed_outbox_messages()`, `mark_dead_consumer_groups()` | Drop | The one cleanup path exists. No production code calls either today (X-8). | None |

### The tracking table and its dependencies

`outbox_consumer_groups` is dropped in phase 4, and only when each of these is gone.

| Dependency | Kind | Removed in |
|---|---|---|
| `DeadConsumerGroupCleanup` | Reads and writes it and the counters | Phase 1, with its three callers (approved) |
| `ConsumerGroupFetcher` | Reads and writes it | Phase 4 |
| `ConsumerGroupRetryService`, `ConsumerGroupRetryJob` | Read and write it | Phase 4 |
| `DeadConsumerDetector.getBlockedMessageStats` | Reads it and the counters | Phase 4. The statistics become a query over subscriptions and positions. |
| `BackfillService` | Writes it and the required count | Phase 4, after phase 3 builds the log replay (OD-4, amended) |
| `trigger_create_consumer_group_entries` (`V001`) | Inserts one row for each known group name for every new message, on every topic (X-2) | Phase 4, with the table |
| Tests and benchmarks that name it | 19 test files by the 2026-10-12 search | Phase 4 at the latest |

After phase 4 the counter columns remain, written by the insert trigger and read by no
delivery path. They go in phase 8 with the cleanup that replaces their last readers.

One row of existing data needs a note. `ConsumerGroupRetryService` writes dead-letter rows
for one group. They carry `original_table = 'outbox_consumer_groups'` (test read:
`ConsumerGroupRetryServiceIntegrationTest.testMoveExhaustedToDlq`). Under the new column
they would read as every consumer. `DeadLetterQueueManager` knows only `outbox` and
`queue_messages` as source tables, so reprocessing such a row fails with "Unknown table".
No production statement creates the `FAILED` rows that service acts on (X-7), so no such
row is expected. MIG-4 counts them by `original_table` before the drop.

The object list is not complete. It came from reading `V001` and `V010`.
`SchemaContractTest` and `MigrationIntegrationTest` name objects it had omitted (S-13).
Phase 2 produces the full list of objects that depend on a removed table or column from
the catalog of a Flyway schema (`pg_proc`, `pg_trigger`, `pg_indexes`, `pg_depend`). The
phase 4 and phase 8 migrations are written from that list.

### Provisioning paths

| Path | How a change arrives | State |
|---|---|---|
| Flyway-provisioned schema | New versioned migrations after `V020`. | Written in the phase that makes each change. |
| New template-provisioned schema | The runtime templates are edited to give the same result. T5-1 proves the two are equal. | Phase 2 makes the two paths equal first. |
| Existing template-provisioned schema | The setup service applies no DDL to it: `connectToExistingSetup` never creates, and `createCompleteSetup` refuses an existing database (S-5). The migration runner would treat it as a baseline and apply later migrations to it; whether they succeed is not known. Three REST test classes already apply the migrations over a template-provisioned schema, through the test initializer, which has the same baseline setting (S-11). They were not run for this document. | Open. Phase 2 decides it, before phase 4 changes any schema. |
| `peegeeq-examples-spring` schema files | Edited by hand (S-6). | Phase 2. |
| Hand-written test schemas | Three test classes create the tables by JDBC (S-12). One is the schema of 62 test classes. | Phase 2 moves them to the Flyway test path (OD-14). |

Four constraints on phase 2, from the reading.

- A template file holds one statement. `SqlTemplateProcessor` runs each manifest file with
  one `query(...).execute()`, and its comment says the client runs only the first statement
  of a multi-statement string. Each function, trigger, and index needs its own file.
- Two start checks disagree. `PeeGeeQManager` requires four tables by a fixed list (781 to
  782), which names neither `outbox_topics` nor the `V017` tables. The setup service
  requires every table the base manifest creates (1737 to 1813). New tables must be added
  to the first.
- The shared test configuration turns dead-consumer detection and the retry job off
  (`PeeGeeQTestConfig` 164 to 168). Thirty-two test files set the detection property
  themselves, and one of them turns it on. Tests therefore run without two jobs that a
  default deployment runs.
- Every manager the setup service builds uses default queue settings (1418 to 1440), so it
  runs dead-consumer detection with cleanup, and the retry job, on a template-provisioned
  schema.

### Order and reversal

- Code that names an object is deleted in the same phase as the drop, or an earlier one.
- Every drop is irreversible. A migration that drops is written only after the tests of
  its phase are green.
- A stopped pairing stops the phase 4 migration before it drops anything.
- No phase is committed red (OD-10).

## Delivery Outcome Contract

| Outcome | Produced when | Terminal for the group |
|---|---|---|
| `COMPLETED` | The handler's `Future` succeeds. | Yes. It is the only successful outcome. |
| `REJECTED` | The group predicate returns false. | Yes. It is not successful processing. |
| `UNAVAILABLE` | No active member accepts the message. The selected member left before dispatch. No member has capacity. A member's filter circuit breaker is open. | No. |
| `FAILED` | The handler's `Future` fails. The handler throws. The handler returns null. The group predicate throws. A member predicate throws. The payload cannot be deserialized. | No, until the retry limit is reached. |
| `DEAD_LETTER` | The path that owns the retry counter finds the limit reached after `FAILED`. | Yes. It is not successful processing. |

Durable effect of each outcome. Each cell with more than one statement is one transaction.

| Outcome | `QUEUE` topic, shared status | `PUB_SUB` topic, offsets | Native queue |
|---|---|---|---|
| `COMPLETED` | `status = 'COMPLETED'`, `processed_at` set. | The offset moves past the message. | The row is deleted. |
| `REJECTED` | Insert the exclusion row. Set `status = 'PENDING'`. `retry_count` unchanged. | The offset moves past the message. No row is written (OD-7). | Insert the exclusion row. Set `status = 'AVAILABLE'`, `lock_until = NULL`. `retry_count` unchanged. |
| `UNAVAILABLE` | Set `status = 'PENDING'`. `retry_count` unchanged. The consumer holds an in-memory `(created_at, id)` scan position, compared in the claim's own order and reset after an empty scan. | The offset does not move. Messages ahead of this one that succeeded are committed first. | Set `status = 'AVAILABLE'`, `lock_until = NULL`. `retry_count` unchanged. The row is not deleted, and it is not claimed again at once (see below). |
| `FAILED`, limit not reached | `retry_count + 1`, `status = 'PENDING'`, as today (943 to 965). | `retry_message_id` set, `retry_count + 1` on the offset row. The offset does not move. Messages ahead that succeeded are committed first. | `retry_count + 1`, `status = 'AVAILABLE'`, as today (956 to 968). |
| `DEAD_LETTER` | Insert into `dead_letter_queue` with `group_name` `NULL`. Set `status = 'DEAD_LETTER'` and `processed_at`. | Insert into `dead_letter_queue` with the group name. Move the offset past the message. | Insert into `dead_letter_queue`, delete the row, as today (995 to 1016). |

Counting, test read. With a limit of N the handler runs N plus 1 times: the first call and
N retries. `OutboxRetryLogicTest` asserts it: limit 3, four calls, `retry_count` 3,
`DEAD_LETTER`. "Retried up to the limit" means that everywhere in this document. Six more
test classes of the outbox plain consumer assert the same count for a thrown exception, a
failed `Future`, and a null `Future`. The three failure forms of the outcome table are
therefore asserted today on the shared-status path.

Deserialization. A payload that cannot be deserialized fails before the handler runs. On
the offset path the engine logs ERROR "Failed to dispatch message" and the partition key
stops with no bound (test read: `PartitionedConsumerEngineLifecycleTest`; P-1). On the
shared-status path the failure does not reach the retry code (Q-10). A retry cannot
succeed. The contract counts it as `FAILED` under the normal limit, so that it reaches
`DEAD_LETTER`. One rule then covers every failure.

The native transient outcome needs one more rule, found by reading `PgNativeQueueConsumer`
in full. The native claim has no scan position. It takes rows in `priority DESC,
created_at ASC` order (565 to 607), and the consumer starts the next drain as soon as a
message settles (719 to 727). A row released as `AVAILABLE` would be claimed again at once,
ahead of every later row. With one consumer thread that is the finding 20 defect in native
form. The release must therefore delay the row, through `visible_at`, or the claim must
hold a scan position. The choice is made in phase 6. T11-4 is its red test.

Rules.

1. Dispatch returns the outcome as a value of a type in `peegeeq-api`. No consumer and no
   engine selects a durable effect by testing an exception class.
2. A failed dispatch `Future` that carries no outcome is `FAILED`.
3. Each predicate is evaluated once per delivery. The member does not evaluate it again.
4. Capacity is admission. A member at its limit is not eligible. It is never a handler
   failure. The member reports admission as a value. Today the only signal is an
   `IllegalStateException` whose message contains "max concurrency" (test read: four tests
   of `OutboxConsumerGroupReviewFixesTest` assert it). The native consumer already admits
   by capacity: with one consumer thread exactly one handler runs at a time and no row
   fails (test read: `PgNativeQueueConsumerCapacityIT`).
5. `UNAVAILABLE` never changes a retry count and never writes a row.
6. The retry limit on the offset path is the limit the shared-status path already uses
   (`OutboxConsumer` 902 to 906). A consumer's own `OutboxConsumerConfig.maxRetries`, when
   one is given, takes the place of the queue setting on both paths.
7. A filter change applies to later messages. Reconsidering old messages needs an explicit
   replay. The three contracts are in [Replay Contracts](#replay-contracts). The log
   replay is implemented and tested before backfill is removed (OD-4, amended).
8. `MessageFilteredException` (`peegeeq-outbox`) is deleted. `RejectedMessageException`
   (`peegeeq-api`) is deleted when the outcome type replaces it. Both are breaking changes
   and are recorded as such (OD-5).
9. A member predicate that throws is `FAILED` only while that member's circuit breaker is
   closed or half-open. While the breaker is open the member is not eligible, and the
   outcome is `UNAVAILABLE` with no retry count (`FilterCircuitBreaker` 50 to 81; test read:
   `CircuitBreakerRecoveryTest` asserts that an open breaker refuses without calling the
   predicate). The
   retry limit counts evaluations that throw. `FilterExceptionFrameworkRetryIntegrationTest`
   TC-3 asserts this today: the count stops rising when the breaker opens. A consequence:
   with a retry limit above the breaker's default minimum of 10 requests, the limit is reached one
   half-open trial at a time. It is bounded and slow.
10. A dead-letter row is not kept without limit. The manager purges rows older than 30 days
    every 24 hours (`PeeGeeQManager` 86, 965 to 984). Reprocessing a dead-letter row
    inserts a new message with a new id and deletes the row (`DeadLetterQueueManager`,
    `reprocessDeadLetterMessageRecord`).
11. The contract binds the in-process engine and both consumer group implementations. A
    client of the caller-driven offset interface fetches and commits for itself and decides
    its own outcomes. The contract gives that client no retry count and no dead letter.
12. A `ServerSideFilter` on a plain consumer is part of the claim statement. A row the claim
    does not return has no delivery and no outcome. Both implementations have this filter
    (test read: `OutboxServerSideFilteringTest`, `ServerSideFilteringTest`). It is not changed.

Consequence of the ruling that must be stated. A transient outcome has no bound. On a
`PUB_SUB` topic a message that no member accepts stops its partition key until the
membership changes. The age of `pending_since` is the signal. On a `QUEUE` topic the
message stays `PENDING` and is claimed again after each empty scan. Nothing bounds it on
either model. The 2026-10-11 text said a queue rule bounds it. That was wrong.

A permanent rejection on a `QUEUE` topic has no bound either (OD-8). The message stays
`PENDING` until another consumer processes it, a replay makes the group consider it again,
or an operator acts. [Queue Decisions](#queue-decisions) gives the consequences.

## Replay Contracts

Reissued in the fifth revision. None of the three adds stored state.

Only the log replay replaces backfill (OD-4, amended). It is built and tested in phase 3,
and backfill is removed in phase 4. The queue replay is built in phase 6 and the per-group
dead-letter replay in phase 7. They replace nothing that exists.

Rules common to the three.

- A replay runs only when an operator or an application calls it. Nothing replays by
  itself.
- Each replay is one transaction. If it fails, nothing changed.
- Delivery stays at least once. A handler sees a replayed message again and must tolerate
  it, as it must after a rebalance today.
- The method names are set in the phase that builds each one. They avoid `catchUp` and
  `replayBatchSize`, which already belong to the event store.

### 1. Queue replay: a group considers its rejected messages again

**Applies to.** A `QUEUE` topic on `outbox`, and a native queue. Built in phase 6.

**Input.** A topic, a group name, and an optional range of message ids.

**Effect.** It deletes the group's exclusion rows for the messages of the topic in the
range. One `DELETE` statement.

**After it.** Each of those messages that is still `PENDING` is available to the group
again. The group evaluates its current filter once per message. A message it still refuses
gets a new exclusion row. A terminal message is not affected: its exclusion rows are
deleted, and no claim reads a terminal row.

**What does not change.** No message status, no retry count, no other group's exclusions,
no start bound.

**Concurrency.** An exclusion that commits after the statement began is not deleted. An
instance that still runs the old filter can write such a row. The operator runs the replay
after every instance of the group runs the new filter. A second call removes what the
first missed.

**Subscription.** None is needed. An exclusion binds a group name, and a group started
with `start()` has no subscription row.

**Result.** The number of exclusion rows deleted.

**Limit stated.** The replay selects by topic, group, and message id. It cannot select
rejections by when they happened, because no time is stored.

### 2. Log replay: a group receives messages from an earlier position again

**Applies to.** A `PUB_SUB` topic. Built in phase 3.

**Input.** A topic, a group name, a message id to start from, and an optional partition
key. Without a key it is an all-key replay.

**Precondition.** The subscription is `ACTIVE`, `STOPPED`, or `PAUSED`. On a `DEAD` or
`CANCELLED` subscription it fails. A dead group is started first.

**Scope.** The start bound is one value for the whole group. That fixes what each form may
do.

- An all-key replay may lower the group's start bound.
- A keyed replay never changes the bound. It fails when its start id is below the bound.
  With an id bound that is a start id lower than `start_from_message_id`. With a timestamp
  bound it is a retained row of that key, at or above the start id, that was created
  before `start_from_timestamp`. The fourth revision let a keyed replay lower the bound.
  That was wrong: the lower bound would have let older rows of every key through.
- A keyed replay moves the offset of that key, and reopens rows of that key, and nothing
  else.

**Effect, in one transaction.** It takes the lock a rebalance takes, so it is a rebalance
that moves offsets and keeps ownership.

1. It locks the topic row. The sweep takes the same lock from phase 3, so the two never
   overlap.
2. It locks the subscription row and raises `rebalance_generation`. This is the statement
   a join runs (`PartitionAssignmentService` 189 to 204).
3. All-key replay only: if the start id is below the group's start bound, it sets
   `start_from_message_id` to the start id and clears `start_from_timestamp`. A keyed
   replay in that case has already failed.
4. For each offset row of the group in scope whose committed offset is at or above the
   start id, it sets the offset to the start id minus one and clears the pending and retry
   fields. A row whose offset is already below the start id is left alone, so the call
   never moves a group forward.
5. It sets every offset row and every assignment row of the group to the new generation.
   Each instance keeps its keys.
6. It reopens released rows in scope: every row of the topic, and of the key when one is
   given, with an id at or above the start id and status `COMPLETED` becomes `PENDING`,
   and its `processed_at` is cleared.
7. Until phase 8 it lowers the stored topic watermark to the start id minus one when the
   watermark is higher. Without this step the next sweep would mark the reopened rows
   `COMPLETED` again, because the stored watermark never moves back
   (`WatermarkCalculator` 66 to 84). Phase 8 removes the watermark and this step.

**What each party observes.**

- The instance that owns a key holds the old generation. Its next commit is refused,
  because the commit compares the generation (`PartitionedOffsetManager` 124 to 133).
  Phase 3 makes the engine treat a refused commit as not committed: it reloads its
  assignments, takes the new generation, and fetches from the stored offset. The messages
  of the batch in flight are delivered again. Today the engine logs WARN and reports
  success (engine 425 to 436).
- A rebalance waits for the replay or the replay waits for it. Both lock the subscription
  row.
- The sweep waits for the replay or the replay waits for it. Both lock the topic row.
  Today the watermark calculation runs outside a transaction; phase 3 puts the
  calculation, the advance, and the sweep in one.

**Other groups.** The replay moves no other group's offset and changes no other group's
bound. It cannot promise more than that. A reopened row is `PENDING` again for the whole
topic. Another group receives it when the row is inside that group's own start bound and
above that group's own position for the key. That holds for a group that has consumed
nothing of the key. It also holds for a group whose position is below the row, for example
one started again after it was `DEAD`. A group that has already passed the row does not
receive it. The fourth revision said other groups do not receive a reopened row. That was
too strong.

**Replay window.** A row that retention has deleted cannot be replayed. The result names
the lowest id that was reopened or was still pending at or above the start id, so the
caller sees where the replay really begins.

**Retention.** A reopened row is held by the group's obligation. It is released again
when every obligated group has passed it, and its deletion clock starts again then.

**Partition keys.** A key whose rows had all been released has pending rows again after
the reopen. The group takes it up at its next key discovery (P-14, fixed in phase 3).

**Result.** The number of offset rows moved, the number of rows reopened, and the lowest
replayed id.

**What it replaces.** With the start bound it covers both scopes of backfill. A new group
that subscribes from the beginning receives the rows still pending. To receive the
released rows that are still retained, the operator then runs an all-key replay from id 1.

### 3. Per-group dead-letter replay: one group receives a dead-lettered message again

**Applies to.** A `dead_letter_queue` row that has a group name. Such rows exist only for
`PUB_SUB` topics, from phase 7. Built in phase 7. A dead letter of a `QUEUE` topic or of a
native queue has no group name: the shared message was dead-lettered for every consumer.

**Why it needs its own contract.** The existing reprocess inserts a new row into `outbox`
and deletes the dead-letter row. A new row is above every group's offset, so every group
of the topic would receive it, including the groups that already processed the original.

**Entry point.** The existing reprocess call and its REST route. The call branches on the
group name. A row with no group name keeps today's behaviour.

**Effect for a row with a group name, in one transaction.**

1. It verifies that the source row in `outbox` still exists. If retention has deleted it,
   the call fails and the dead-letter row is kept.
2. It runs the keyed log replay for that topic, that group, the partition key of the
   source row, and the id of the source row. That id is not below the group's start
   bound, because the group was given the message.
3. It deletes the dead-letter row.

**After it.** The group receives that message again, and after it every later message of
the same partition key again (OD-21, approved). The order within the key is kept. The
message has its full retry budget again. If it fails to the limit again it is
dead-lettered again.

**Other groups.** As for the log replay. No other group's offset moves. A group whose own
position and start bound include a reopened row may receive it.

**Cost stated.** The group receives every later message of that key a second time. For a
key with many later messages that is many repeated deliveries.

**The alternative that was not taken.** Insert a copy of the message addressed to one
group. That needs a stored target-group column on `outbox` and a predicate in every fetch,
and it delivers the message after later messages of its key.

**Precondition.** The same as the log replay: the subscription is `ACTIVE`, `STOPPED`, or
`PAUSED`.

## Queue Decisions

**Decision 1. Every known group has rejected a queue message.** Ruled by OD-8. The message
stays `PENDING`. Nothing expires it and nothing dead-letters it by itself. Any plain
consumer, and any group with no exclusion row for it, may claim it.

The earlier versions proposed a rule that dead-lettered such a message after a period.
The owner rejected it: the database does not know every consumer of a queue topic. A plain
consumer has no row, and a group started with `start()` has no subscription row (M-1). A
rule built on that registry would dead-letter work that such a consumer could still
accept. The rule, its setting, its clock column, and its test T12-5 are removed.

What then removes such a message.

- A consumer processes it. It becomes `COMPLETED`.
- The queue replay makes a group consider it again, for example after a filter change.
- An operator dead-letters that one message (OD-19). The contract is below.
- An operator purges the queue. POST `/api/v1/queues/:setupId/:queueName/purge` exists
  today and deletes every message of the queue.

Consequences that must be stated.

- **Retention with no bound.** A message that every group refuses, and that nothing else
  consumes, stays in `outbox` with its exclusion rows until an operator acts.
- **Claim cost.** Excluded rows are the oldest pending rows, so each claim by a rejecting
  group passes them before it reaches a row it can take. The cost grows with the excluded
  backlog. It was not measured.
- **Detection.** The excluded backlog is a derived value: the pending messages with at
  least one exclusion row, their count, and the age of the oldest. Phase 6 adds it to the
  queue statistics. Nothing alerts on it by itself.

**Decision 2. A group created later may claim it.** While the message is `PENDING` and
inside the new group's start bound, the new group claims it like any other pending
message.

Reasons. On a queue topic a pending message is unprocessed work, and a new group is a new
competing consumer. An exclusion is a fact about one group name and one message. The start
bound already controls history: a group subscribed `FROM_NOW` does not see older rows.

Three rules follow. An exclusion binds the group name, not the filter object: a restart
with a different filter keeps the old exclusions until a queue replay. A group started
with `start()` has no bound and claims every pending row. A reused group name inherits
that name's exclusions.

### Operator dead-letter of one message

New in the fifth revision. Approved by OD-19 with an atomic contract. Built in phase 6. It
is explicit and takes a message id, so it does not rest on the consumer registry.

**Applies to.** A message of a `QUEUE` topic in `outbox`, or a message of a native queue
in `queue_messages`. It refuses a `PUB_SUB` topic. A log row is consumed through offsets,
and a shared dead letter would take it from every group. That limit is this document's
choice.

**Input.** The store (`outbox` or `queue_messages`), the topic, the message id, and a
reason text. All four are required.

**Effect, in one transaction.**

1. It locks the message row with `SELECT ... FOR UPDATE`, by id and topic in the named
   store. If there is no such row, the call fails.
2. It refuses a message that a consumer holds: status `PROCESSING` in `outbox`, or
   `LOCKED` in `queue_messages`. It also refuses a terminal `outbox` row, `COMPLETED` or
   `DEAD_LETTER`. Only a `PENDING` or an `AVAILABLE` row passes.
3. It inserts one row into `dead_letter_queue`: the store as `original_table`, the id,
   the topic, the payload, the headers, the correlation id, the partition key, the
   creation time, the row's present retry count, and the reason. `group_name` is `NULL`,
   so the dead letter is for every consumer.
4. On `outbox` it sets `status = 'DEAD_LETTER'`, `processed_at`, and the reason as
   `error_message`. On `queue_messages` it deletes the row.

The commit makes steps 3 and 4 visible together. If any step fails, nothing changed and
the call fails.

**A concurrent claim.** Both claims take rows with `FOR UPDATE SKIP LOCKED`
(`OutboxConsumer` 390; `PgNativeQueueConsumer` 573, 598). A claim that runs while the
operation holds the row skips it. A claim that took the row first has set `PROCESSING` or
`LOCKED`: the operation waits for that transaction, then reads the new status and refuses.
So a message is never both delivered and dead-lettered by this call. That is Read and not
run. OPD-4 tests it.

**After it.** The `outbox` row is terminal and is deleted after `message_retention_hours`
(OD-20). Its exclusion rows go with it. The dead-letter record stays for 30 days. The
existing reprocess of that record inserts a new message with no exclusion rows, so every
group evaluates it again.

**Result.** The id of the dead-letter row.

## Retirement of REFERENCE_COUNTING and Backfill

The per-message fan-out is not completed. Pub/sub uses offsets. Queue topics keep the
shared status.

The retirement is done by phase 4, except for the counters. OD-9 keeps the counters,
cleanup fields, and watermarks until phase 8 replaces their last readers.

1. **Phase 1.** The dead-group cleanup stops changing messages. Its three callers and the
   class go (the detection job, `cancel`, force-remove), with the cleanup fields of
   `ForceRemoveResult`.
2. **Phase 3.** The offset path is corrected: P-4 to P-6, P-10, and P-13 to P-15 (OD-11).
   Nine unverified defects stood against that path. Four of them, P-4, P-5, P-13, and
   P-14, describe messages that a group never receives. Each is reproduced or refuted by
   its red test in this phase and corrected in the same phase. The log replay is built
   here.
3. **Phase 4. Backfill.** Everything in the
   [Backfill removal inventory](#backfill-removal-inventory): `BackfillService`, its
   public surface, and the six columns. The log replay of phase 3 replaces it (OD-4,
   amended). Kept longer, it would be a public operation that writes tracking state no
   delivery path reads.
4. **Phase 4. New topics.** A topic has `semantics` and no mode.
   `TopicConfig.Builder.completionTrackingMode`, the `completion_tracking_mode` column, and
   both defaults (`TopicConfig` 104; template `08a` line 9; `V010`) are removed.
5. **Phase 4. Existing topics.** The [Migration Matrix](#migration-matrix) gives the rule
   for each stored pairing. Two pairings stop the migration until the operator resolves
   them (OD-2).
6. **Phase 4. Start.** Every start path of both group types reads `semantics` with one
   query. On an outbox group `PUB_SUB` selects the offset engine and `QUEUE` selects the
   queue path. A native group, consumer, or producer fails on `PUB_SUB` (OD-15). A failed
   query fails the start. No row means `QUEUE` (OD-1). No constructor and no builder path
   omits the connection manager (M-6). Every claim and fetch also enforces the semantics
   in its statement. This is task 7.
7. **Phase 4. Deleted production code.** `ConsumerGroupFetcher`.
   `ConsumerGroupRetryService`, `ConsumerGroupRetryJob`, and their wiring
   (`PeeGeeQManager` 1028 to 1040). The reference-counting fields of
   `getBlockedMessageStats`. The offset path of `PgNativeConsumerGroup`.
8. **Phase 4. The tracking table.** `outbox_consumer_groups`, its creation trigger, and
   its functions are dropped when their last dependency is gone
   ([list](#the-tracking-table-and-its-dependencies)).
9. **Phase 8. Cleanup and schema.** `CleanupService` is replaced by the one cleanup path.
   The watermark is replaced by the per-partition release. The counters, their trigger,
   and the two cleanup functions are dropped. The two fields of `OutboxMessage` that
   `PartitionedFetcher` selects go with the counter columns.
10. **Test code.** `CompletionTracker` and its tests, the tests of each deleted class, and
    the reference-counting benchmarks in `peegeeq-benchmarking/.../db/fanout/` go with the
    class they exercise. Each phase starts with a search that lists them, and its report
    gives each removed class and its test count. A search on 2026-10-12 counted the test
    files that name a removed object: `outbox_consumer_groups` 19, the two counter columns
    19, `completion_tracking_mode` 19, `outbox_topic_watermarks` 11. Most of them name it
    only to seed or to clean up.
11. **Kept.** Subscription rows, start bounds, pause, resume, cancel, heartbeat, and dead
    detection, under the [lifecycle contract](#subscription-lifecycle-contract). `DEAD`
    and `CANCELLED` change no message. On a `PUB_SUB` topic a `DEAD` or `CANCELLED` group
    stops holding retention.
12. **Documents.** `PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md`,
    `PEEGEEQ_PARTITIONED_CONSUMPTION_DESIGN.md` (it says "`REFERENCE_COUNTING` remains the
    default"), and the six files under `docs/` that name a mode. Each is updated in the
    phase that changes what it describes.

### Backfill removal inventory

From a search of the working tree for the word "backfill" on 2026-10-12. The number is the
count of lines that name it. A hit is not proof of dependence. Each file is read before it
is edited.

| Module | Production file | Lines |
|---|---|---|
| `peegeeq-api` | `BackfillScope` | 4 |
| `peegeeq-api` | `SubscriptionOptions` | 17 |
| `peegeeq-api` | `SubscriptionInfo` (DTO fields) | 27 |
| `peegeeq-api` | `SubscriptionService` | 17 |
| `peegeeq-api` | `StartPosition`, `ConsumerGroup`, `PeeGeeQErrorCodes`, `EventStore` | 2, 1, 3, 1 |
| `peegeeq-db` | `BackfillService` | 172 |
| `peegeeq-db` | `SubscriptionManager` | 72 |
| `peegeeq-db` | `Subscription` | 31 |
| `peegeeq-db` | `PeeGeeQManager` (wiring) | 4 |
| `peegeeq-db` | Template `08b-consumer-table-subscriptions.sql` | 6 |
| `peegeeq-migrations` | `V010__Create_Consumer_Group_Fanout_Tables.sql` | 9 |
| `peegeeq-rest` | `SubscriptionHandler` (three routes, JSON output) | 66 |
| `peegeeq-rest` | `ManagementApiHandler` (one route, JSON output) | 23 |
| `peegeeq-rest` | `SystemMonitoringHandler` (monitoring) | 10 |
| `peegeeq-rest` | `PeeGeeQRestServer` (route registration) | 5 |
| `peegeeq-management-ui` | `pages/ConsumerGroups.tsx`, `api/types.ts`, `pages/QueueDetailsEnhanced.tsx` | 45, 6, 6 |

Test and benchmark files that name it: `peegeeq-db` 10, `peegeeq-benchmarking` 4,
`peegeeq-rest` 3, `peegeeq-api` 2, `peegeeq-examples` 2, `peegeeq-migrations` 1, and one
end-to-end spec in `peegeeq-management-ui`. `V010` is an existing Flyway migration and is
not edited. A new migration drops the columns.

### API compatibility inventory

Public surface that this proposal removes or changes. The owner approved breaking API
changes for this redesign (OD-5). Each entry is recorded as a breaking change in the phase
that makes it. The list comes from the reading and from the search above. Each phase
starts with a search that completes its part.

| Where | Removed or changed | Phase |
|---|---|---|
| `peegeeq-api` | `SubscriptionState` gains `STOPPED`. `ConsumerGroup.stopGracefully` sets it for the whole group and no longer cancels; `stop` and `close` change no status. A heartbeat, a claim, and a fetch fail on a stopped subscription. A start keeps a stored start bound and leaves a paused subscription paused. A heartbeat no longer revives a `DEAD` subscription and fails for `DEAD` and `CANCELLED`. `pause` and `resume` fail on a cancelled subscription. | 1 |
| `peegeeq-api` | Three fields of `ForceRemoveResult`. | 1 |
| `peegeeq-api` | Added: the log replay. | 3 |
| `peegeeq-api` | `BackfillScope`. `SubscriptionOptions.backfillScope` and `fromBeginning(BackfillScope)`. Six backfill fields of `SubscriptionInfo`. `SubscriptionService.startBackfill` (two overloads) and `cancelBackfill`. The backfill entries of `PeeGeeQErrorCodes`. | 4 |
| `peegeeq-api` | `RejectedMessageException`, when the outcome type replaces it. | 5 |
| `peegeeq-api` | Added: the queue replay and the operator dead-letter of one message. | 6 |
| `peegeeq-api` | `DeadLetterMessageInfo` is a record. A group name changes its canonical constructor: an addition for readers and a break for code that constructs it without the builder. Added: the per-group dead-letter replay, through the existing reprocess call. | 7 |
| `peegeeq-outbox` | The five `OutboxFactory` constructors that pass no connection manager. A plain consumer's `subscribe` fails on a `PUB_SUB` topic, and its claim fails there with a semantics-mismatch error. | 4 |
| `peegeeq-outbox` | `MessageFilteredException`. | 5 |
| `peegeeq-native` | A native producer, consumer, or group fails on a `PUB_SUB` topic (OD-15). | 4 |
| `peegeeq-db` | `SubscriptionStatus` gains `STOPPED`. `DeadConsumerGroupCleanup`. `SubscriptionManager.setDeadConsumerGroupCleanup`. The subscription health summary gains a count of stopped subscriptions. | 1 |
| `peegeeq-db` | `BackfillService`. `SubscriptionManager.setBackfillService`. Six backfill fields of `Subscription`. The completion tracking mode of `TopicConfig`. `createTopic` and `updateTopic` refuse a change of established semantics (OD-13). Added: the administrative semantics migration. `ConsumerGroupFetcher`, `ConsumerGroupRetryService`, `ConsumerGroupRetryJob`. | 4 |
| `peegeeq-db` | `DeadLetterMessage` (twelve constructor arguments) and `DeadLetterQueueManager.moveToDeadLetterQueue` (ten arguments) gain the group name. | 7 |
| `peegeeq-db` | `CleanupService`. Two fields of `OutboxMessage`. | 8 |
| REST | The force-remove response. | 1 |
| REST | Four backfill routes: GET, POST, and DELETE on `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/backfill`, and POST on `/api/v1/management/consumer-groups/:setupId/:queueName/:groupName/backfill`. Request field `backfillScope`. Response fields `backfillStatus`, `status`, `processedMessages`, `scope`, and the action `backfill_cancelled`. The fields `backfillStatus`, `backfillProcessedMessages`, `backfillTotalMessages`, `backfillStartedAt`, and `backfillCompletedAt` in the consumer-group listing, and `backfillStatus` in `/api/v1/queues/:setupId/:queueName/consumers`. The blocked-message statistics of `/api/v1/setups/:setupId/consumer-alerts/blocked` (`blockedStats[]`, `totalBlockedGroups`). | 4 |
| REST | Added: a route for the operator dead-letter of one message. Its path is set in phase 6. | 6 |
| REST | POST `/api/v1/setups/:setupId/deadletter/messages/:id/reprocess` runs the per-group replay for a dead letter that has a group name. | 7 |
| Configuration | `peegeeq.queue.consumer-group-retry.enabled` and `peegeeq.queue.consumer-group-retry.interval`. | 4 |

## Consolidated Design Review

Every item was found by reading on 2026-10-11 unless the evidence column says Run.

### Shared-status path, outbox `QUEUE`

| ID | Defect | Location | Evidence |
|---|---|---|---|
| Q-1 | A rejected row with an earlier `created_at` and a higher id stops delivery of an acceptable row. The scan position is an id. The scan order is `created_at, id`. | `OutboxConsumer` 373 to 378, 388, 416, 851 | Run: `logs/probe-outbox-filtered-scan-order-20261010.log`, `logs/probe-outbox-filtered-scan-order-batch-20261010.log` |
| Q-2 | A rejection is not recorded. The row is claimed and reset on every pass with no bound: 21 passes in 10 s in the first run. | `OutboxConsumer` 775 to 786, 843 to 865 | Run: same logs |
| Q-3 | A thrown group predicate is turned into the rejection type and takes the same reset. No retry count, no dead letter. An ERROR is logged on every pass. | `OutboxConsumerGroup` 850 to 862 | Read. Three tests of `OutboxConsumerGroupCoreTest` assert it as intended. |
| Q-4 | The group name scopes no durable state. The claim does not test `subscription_status`, so a paused or cancelled group keeps claiming. | `OutboxConsumer` 347 to 363, 380 to 394 | Read |
| Q-5 | A member at its concurrency limit fails with `IllegalStateException`. The consumer counts a handler failure and dead-letters at the limit. The default limit is 1. It needs more than one consumer thread. With the default of one thread a single row is in flight (test read: `OutboxConsumerGroupFaultToleranceTest.hungHandlerBlocksPollLoop`). | Member 80, 355 to 371; `OutboxConsumer` 797, 902 to 906 | Read |
| Q-6 | A member predicate runs twice per delivery. If the second result is false the member returns success without calling the handler, and the message is marked `COMPLETED`. | `OutboxConsumerGroup` 878; member 374 to 380 | Read |
| Q-7 | A throw from the second evaluation leaves the member's in-flight count raised. | Member 364, 374 | Read |
| Q-8 | The dead-letter writer sets `status` and `error_message` and not `processed_at`. The row keeps the claim time of its last attempt. | `OutboxConsumer` 382, 1006 | Read |
| Q-9 | The field comment says the scan wraps "so changed filters can accept older rows later". The ruling overrules it. | `OutboxConsumer` 86 to 87 | Read |
| Q-10 | A payload that cannot be deserialized fails in `processRow` before the retry and dead-letter code runs. The failure is logged at ERROR and returned. What then resets the row was not traced. | `OutboxConsumer` 577 to 651 | Read |

### Offset path, `PUB_SUB`

| ID | Defect | Location | Evidence |
|---|---|---|---|
| P-1 | A failing handler stops its partition key with no bound. No retry count, no dead letter. | `PartitionedConsumerEngine` 363 to 374, 411 to 437 | Run: finding 4, `logs/probe-native-partitioned-handler-failure-20261009.log`, `logs/probe-outbox-partitioned-handler-failure-20261009.log` |
| P-2 | The engine does not branch on the outcome. A group rejection and a transient result stop the partition key the same way. | Engine 411 to 437 | Read |
| P-3 | The offset commits once per batch, and only if every handler succeeded. Messages ahead of a failing one are delivered again on every tick. | Engine 411 to 437; partitioned design 88 to 101 | Read. `ConsumerGroupFaultIntegrationTest` F1 asserts the repeated delivery as the expected result. |
| P-4 | The fetch takes `FOR UPDATE ... SKIP LOCKED` on a log read. A row that another transaction has locked is skipped, and the batch commit then moves the offset past it. | `PartitionedFetcher` 107 to 155; engine 425 | The skip is asserted as intended by `PartitionedFetcherIntegrationTest` 3.8 and 3.15. The loss after the commit is Read. |
| P-5 | Offsets are ids. A row whose transaction commits after a higher id was consumed is never fetched. The sweep later marks it `COMPLETED`, because the stored watermark never moves back. | `PartitionedFetcher` 121, 136; `WatermarkCalculator` 66 to 105 | Read. No test inserts rows out of id order. |
| P-6 | The start position is not applied. `initializeOffset` inserts 0, and the offset path does not read the stored start bound. | `PartitionedOffsetManager` 63 to 95; `SubscriptionManager` 208 to 245 | Read |
| P-7 | The watermark is the minimum over every partition key of every `ACTIVE` subscription. A key that receives no more messages holds it for the whole topic. | `WatermarkCalculator` 42 to 56 | The minimum over keys is asserted by `WatermarkCalculatorIntegrationTest` 4.1. The stall is Read. |
| P-8 | The sweep sets `COMPLETED` without `processed_at`. `CleanupService` deletes such a row only when `processed_at` is set, so it never deletes one. | `WatermarkCalculator` 93 to 105; `CleanupService` 89 to 99 | Read. No test asserts `processed_at` after a sweep. |
| P-9 | No statement deletes an offset row. Partition keys are found from `PENDING` and `PROCESSING` rows only. Keeping the offset row when an instance leaves is asserted (`PartitionAssignmentIntegrationTest` 2.5). | Search of every `src/main` tree; `PartitionAssignmentService` 207 to 224 | Read |
| P-10 | The engine, the fetcher, and the assignment service do not name `subscription_status`. A paused group keeps fetching, and the watermark stops counting it. `ZeroSubscriptionValidator` counts `ACTIVE` subscriptions only. | Search, 2026-10-11 | Read |
| P-11 | A native group on an `OFFSET_WATERMARK` topic reads `outbox`. It never sees a message produced to `queue_messages`. OD-15 removes the path: the native API refuses a `PUB_SUB` topic. | `PartitionedFetcher` 114 to 127 | Run for the read of `outbox`: finding 4, native log |
| P-12 | The WARN text "Fetch failed for partition" reports a handler failure. | Engine 368 | Run: finding 4 |
| P-13 | The sweep completes rows that no group has read. The watermark is taken over offset rows. A group or a key with no offset row adds nothing to it. A message for such a key is at or below the watermark as soon as every existing offset row has passed its id. | `WatermarkCalculator` 42 to 56, 93 to 105; engine 163 | Read |
| P-14 | A running group does not take up a key that was absent at its last rebalance. Discovery runs only inside a join or a leave. A group started on an empty topic delivers nothing until another instance joins or leaves. | `PartitionAssignmentService` 207 to 224; engine 262 to 318 | Asserted as intended by `PartitionedConsumerSafetyIntegrationTest.testNewMessageGroupAfterJoin_notAssignedUntilRebalance`. The tests named in the P-13 block and the release gate arrange their sends or joins around it. |
| P-15 | An instance that stops without leaving keeps its keys until another instance joins or leaves. A rebalance counts only instances that own an assignment row with a heartbeat under 30 s old. Nothing else reads that heartbeat. | `PartitionAssignmentService` 36, 226 to 243; search of every `src/main` tree | Read |

P-4 and P-5 in full. The trigger for P-4 is a row lock held when the fetch runs, on a key
that has more pending rows than the other transaction locked. The fetch then returns the
later rows, and the commit passes the locked ones. With fewer pending rows than the lock
covers, the fetch returns nothing and nothing is lost. Lock holders found by reading:
another group's fetch of the same key, for up to one batch of 100 rows; `BackfillService`,
which updates up to 10,000 rows of the topic in one batch; `DeadConsumerGroupCleanup`, which
updates every pending row of the topic; and a queue claim started against the topic (M-1,
M-2). The trigger for P-5 is two producer transactions on one key that commit in the
opposite order to their ids, which a transactional outbox produces whenever the
transactions differ in length. In both cases one group never receives the message, or no
group does. Nothing logs it, no counter changes, and the row ends `COMPLETED`. The one-hour
release gate reported every message received at 200 messages per second. It could not have
shown either trigger (test read: `PartitionedConsumptionReleaseGate`). One publisher per
tenant inserts a batch and starts the next only after the first returned, so ids commit in
order and P-5 cannot occur. A key receives about 6 rows per second and one fetch takes 100,
so the backlog that P-4 needs does not form. The gate adds one key under load and then
calls `joinGroup` by hand, which works around P-14.

P-13 with P-14, in full. This is a static finding. The current SQL can mark unread rows
`COMPLETED` when a partition key has no offset row or no assignment. Runtime loss was not
reproduced. It ranks with X-4, P-4, and P-5.

- **Trigger.** One `PUB_SUB` topic with two partition keys and one running instance. The
  first message of key K2 arrives after the instance's last rebalance. Key K1 then receives
  a message with a higher id, and the group commits it. Every engine runs the sweep for its
  topic every 60 s (`WatermarkJob`, default interval).
- **Effect.** K2 has no offset row, so the watermark is K1's offset. The sweep sets K2's
  message `COMPLETED`. The fetch and the discovery read `PENDING` and `PROCESSING` rows
  only. No group reads the message, and no later rebalance finds the key.
- **Scope of damage.** Every message of a key that was absent at the last rebalance, for
  every group of the topic. A key whose rows were all `COMPLETED` at a rebalance is dropped
  the same way, so the case is not limited to new keys.
- **Detection.** None. The row ends `COMPLETED`. No log line and no counter records it.
- **Established.** The no-rediscovery rule is asserted by an existing test, whose comment
  calls it a documented operational caveat. The three outbox ordering tests send every
  message before they start the group, with a comment that gives discovery at join as the
  reason. `EventSourcingCQRSDemoTest` holds the group start back until every key has a
  stored row, with the same reason in a comment. The release gate rejoins by hand after it
  adds a key. `WatermarkJob` runs one sweep when it starts and then one per interval (test
  read: `WatermarkJobFailureHealthCoreTest`). The sweep of an unread row is Read.
- **Treatment.** The release rule in the [Data Model](#derived-values) counts a missing
  offset row as offset 0, which closes P-13. P-14 and P-15 need the engine to discover keys
  and dead instances without a join. OD-11, ruled: all three are corrected in phase 3,
  before phase 4 moves any topic. Until phase 8 the P-13 correction is made in the
  watermark calculation.

Design precedents for the phase 3 corrections. The event store's durable subscription is a
second cursor reader in this code base (`DurableBiTemporalSubscriptionCoordinator`,
`DurableBiTemporalDelivery`; both read whole). It holds tested design precedents for five
items above. It does not solve them for the outbox: its tests were read and not run, and
the outbox model differs.

| Item | What the event store does | Its test |
|---|---|---|
| P-5 | Before each pass it takes `LOCK TABLE ... IN SHARE MODE` with a 5 s lock timeout, checks that the id sequence is ascending with cache 1, and reads the maximum id. The pass reads ids up to that boundary. A writer that holds a lower id commits or rolls back first. | `delayedLowerIdCommitCannotBeSkipped` |
| P-3 | The cursor is written after each message, in its own transaction. | `handlerFailurePreservesLastAcknowledgementAndCanResume` |
| P-10 | Pause waits for the pass in flight, sets the status, and clears the lease. The cursor write refuses a paused subscription. Resume delivers what arrived while paused. | `pauseResumeAndCancelRespectCommittedProgress` |
| P-15, X-6 | One owner holds a lease with an expiry. A timer renews it and writes the heartbeat. An expired lease lets another instance take over with no join. | `expiredLeaseAllowsTakeoverButFencesTheOldAcknowledgement`, `renewsLeaseWhileWaitingForHandlerAcknowledgement` |
| Refused commit | A stale owner's acknowledgement fails. The outbox engine logs WARN and reports success. | The same lease test |

Phase 3 mirrors these where they fit. Three differences limit that. The event store has one
cursor per subscription and no partition keys. Its table lock is taken on a table with one
kind of writer; the same lock on `outbox` stops every producer of every topic while it is
held, and that cost was not measured. A failing handler stops the event store's delivery
for good, with an ERROR and no retry count. That is P-1 again and not a model for it.

### Native queue

| ID | Defect | Location | Evidence |
|---|---|---|---|
| N-1 | A message that no member accepts is deleted. Three cases: the member predicate throws, the member predicate returns false, the only member was removed. | `PgNativeConsumerGroup` 755 to 763; member 229 to 236; `PgNativeQueueConsumer` 928 to 931 | Run: finding 3, `logs/probe-native-filter-throws-20261009.log`, `logs/probe-native-filter-variants-20261009.log` |
| N-2 | A group predicate that returns false gives a succeeded `Future`, the result the runs above delete on. | `PgNativeConsumerGroup` 739 to 747 | Read. Five tests of `PgNativeConsumerGroupLifecycleTest` assert the succeeded result as intended. |
| N-3 | Not a defect. A group predicate that throws is not caught in the group. The consumer catches it as a handler failure, raises `retry_count`, and dead-letters at the limit. That matches the ruling. The first two versions said the effect was not determined. | `PgNativeConsumerGroup` 739; `PgNativeQueueConsumer` 796 to 805, 956 to 993 | Read |
| N-4 | The member's second predicate check returns success without calling the handler. | Member 251 to 256 | Read |
| N-5 | A missing connection manager selects the queue path with no mode check. The start log names "REFERENCE_COUNTING mode". | `PgNativeConsumerGroup` 326 to 340, 391 to 392 | Read |
| N-6 | `start(SubscriptionOptions)` on a group that is already active succeeds for a native group and fails with `IllegalStateException` for an outbox group. | `PgNativeConsumerGroupLifecycleTest.succeedsWhenAlreadyActive`; `OutboxConsumerGroupCoreTest.startWithOptionsFailsWhenAlreadyActive` | Test read |
| N-7 | `setMessageHandler(null)` throws `IllegalArgumentException` for a native group and `NullPointerException` for an outbox group. | The two lifecycle test classes | Test read |
| N-8 | A failed mode query fails the start of a native group. An outbox group logs WARN and takes the queue path (M-2). | `PgNativeConsumerGroupLifecycleTest`; `OutboxConsumerGroup` 446 to 453 | Test read for native; Read for outbox |
| N-9 | `start(SubscriptionOptions)` with no database service starts a native group as active and creates no subscription. | `PgNativeConsumerGroupLifecycleTest.withoutDatabaseService_startsNormally` | Test read |
| N-10 | A failed cancel during `stopGracefully` is returned by a native group. An outbox group logs WARN and reports success. | `PgNativeConsumerGroup` 499 to 529; `OutboxConsumerGroup` 608 to 627 | Read. `OutboxConsumerGroupGracefulShutdownTest.stopGracefully_cancelFails_stillStops` asserts the outbox result as intended. Corrected in phase 1, increment 1a: both groups stop and return the failure, and the test is replaced. |
| N-11 | A native group started with options computes its start position from the options at every start and does not read the stored start bound. With `FROM_NOW` a restart skips every message produced while the group was stopped. The stored bound is computed from `outbox` ids; the native group reads `queue_messages`. | `PgNativeConsumerGroup` 409 to 481; `SubscriptionManager` 204 to 246 | Read in phase 1. Run in increment 1b: red before the correction, `logs/f20-p1b-red8-native-restart-bound-20261012.log`. Ruled on 2026-10-12 (OD-23): a start uses the stored bound on the outbox path and on the native path. Corrected in increment 1b: the native start resolves `FROM_NOW` against `queue_messages` when it creates the subscription, and it starts from the stored bound. The owner's review of increment 1b found that a subscription created through the subscription service or through REST for a native queue still stored an `outbox` id. The owner ruled on 2026-10-12: the caller of `subscribe` names a `SubscriptionStore`, `OUTBOX` or `NATIVE`, with no default; a queue factory declares its store; REST derives it from the queue factory; `SubscriptionManager` resolves `FROM_NOW` against the table of the store inside the creating transaction; the native group has no resolver of its own; no discriminator is stored; a queue with subscriptions keeps its implementation type. Corrected under that ruling in correction 1 of the review. The task register holds the record. |

No database-backed native test sets a group filter. `ConsumerGroupTest
.testConsumerGroupWithGroupLevelFilter` sets none; its comment says header support must be
fixed first. The native group-filter tests call the dispatch method with no database. T11-1
is the first test of a group filter on a native queue. The native outcome effects for
success and for failure below the limit are asserted today (`ConsumerGroupTest`: the row is
deleted; the row stays `AVAILABLE` with `retry_count` 1 and no lock).

Finding 5 in the task register, unbounded redelivery after a visibility timeout, is the
same class as P-1. It stays under its own number.

### Topic mode and configuration

| ID | Defect | Location | Evidence |
|---|---|---|---|
| M-1 | `start()` never reads the stored mode. It creates no subscription and applies no bound. | `OutboxConsumerGroup` 378 | Read |
| M-2 | `start(SubscriptionOptions)` selects the shared-status path in three cases without failing: no database service (WARN, no subscription), no connection manager, and a failed mode query (WARN). | `OutboxConsumerGroup` 415, 428, 431 to 437, 446 to 453 | Read |
| M-3 | `createTopic` is an upsert that checks no pairing. It can change the semantics of a topic that holds messages. | `TopicConfigService` 62 to 76 | Read. Four tests of `TopicConfigServiceIntegrationTest` and `TopicConfigServiceCoreTest` change a topic from `QUEUE` to `PUB_SUB` and assert the new value (OD-13). |
| M-4 | Both defaults are `REFERENCE_COUNTING`. | `TopicConfig` 104; template `08a` line 9 | Read |
| M-5 | For a topic with no row, `isOffsetWatermarkTopic` answers false and `requireOffsetWatermarkMode` fails. | Engine 516 to 530; `SubscriptionManager` 1069 to 1087 | Read |
| M-6 | `OutboxConsumerGroup` has six constructors and `PgNativeConsumerGroup` two. Production constructs them at `OutboxFactory` 303 and `PgNativeQueueFactory` 248. `OutboxFactory` has six public constructors, and five pass no connection manager. A group from a factory built by one of the five takes the shared-status path on any topic (M-2). `OutboxConsumerGroup.builder()` is a further construction path, and the tests that use it set no connection manager. | `OutboxConsumerGroup` 183 to 228; `PgNativeConsumerGroup` 117 to 130; `OutboxFactory` 86 to 116 | Read |
| M-7 | REST stores a group's subscription under a topic name the group does not consume. `ConsumerGroupHandler` creates the group on `queueName` and subscribes it under `setupId-queueName`. `ManagementApiHandler` subscribes under `queueName`. `SubscriptionHandler` uses the topic in the path. A failed subscribe in the first handler logs WARN and the request still answers 201. A search of `peegeeq-rest/src/main` finds no call that starts a consumer group. | `ConsumerGroupHandler` 138 to 157, 612; `ManagementApiHandler` 1340 | Read at those lines. The composite topic is asserted by `SubscriptionPersistenceAcrossRestartIntegrationTest`. Two test comments say that REST does not start a group (`ConsumerGroupMessageAckTest`, `RestClientConsumerGroupsContractSmokeTest`). `ConsumerGroupHandlerTest` calls no handler code. Ruled on 2026-10-12: `queueName` is the intended topic. Corrected in correction 1 of the review of increment 1b: `ConsumerGroupHandler` uses `queueName` for the subscription and for the group, and the persistence test asserts the queue name. The WARN and the 201 after a failed subscribe were corrected in increment 1b. |

### Retired fan-out that still runs in production

| ID | Defect | Location | Evidence |
|---|---|---|---|
| X-1 | No production code writes a group completion. `CompletionTracker` is test scope. `ConsumerGroupFetcher` has no production caller. | Search by class name of every `src/main` tree | Read. Three fan-out benchmarks and `LateJoiningConsumerDemoTest` consume through the fetcher, and the benchmarks complete through the test-scope tracker. No other code completes a group. |
| X-2 | The `V001` trigger inserts one `PENDING` tracking row for every group name already in `outbox_consumer_groups`, for every new outbox row, on every topic. `BackfillService` creates those names. | `V001`, `V010`; `BackfillService` 766 to 770 | Read |
| X-3 | `BackfillService` changes messages in two ways only: `required_consumer_groups + 1` and a tracking-row insert. It reads no semantics and no mode. The manager wires it. `subscribe` runs it on `FROM_BEGINNING`. A heartbeat that revives a `DEAD` group runs it. | `BackfillService` 755 to 770; `PeeGeeQManager` 660 to 666; `SubscriptionManager` 173 to 196, 571 to 598 | Read. `LateJoiningConsumerDemoTest` shows a late group reading history through the fetcher with no backfill, in three start positions. No test shows a message delivered because of a backfill. |
| X-4 | Dead-group cleanup marks unprocessed messages `COMPLETED`. Detail below. | `DeadConsumerGroupCleanup` 127 to 158, 259 to 278, 338 to 346; `DeadConsumerDetectionJob` 279 to 288, 316 to 330; `PeeGeeQManager` 1011 to 1021 | Read. The mechanism is asserted by existing tests, on `PUB_SUB` topics only. |
| X-5 | `cancel` and `forceRemoveConsumerGroup` run the same cleanup. | `SubscriptionManager` 387 to 416, 474 to 491 | Read |
| X-6 | No in-process group sends the subscription heartbeat. The REST handler is the only production caller of `updateHeartbeat`. The engine's heartbeat updates `outbox_partition_assignments` only. Detection marks a `PAUSED` subscription `DEAD` as it does an `ACTIVE` one (`DeadConsumerDetector` 244 to 260). `FlappingProtectionIntegrationTest.testPausedSubscriptionFlappingProtection` asserts that detection applies to a paused subscription. No test asserts that a running group sends the subscription heartbeat. `ConsumerGroupFaultIntegrationTest` F10 asserts the assignment heartbeat. `DeadConsumerDetectorComprehensiveTest` has two more tests that assert a paused subscription is marked `DEAD`. | Search; `SubscriptionHandler` 314; `PartitionAssignmentService` 164 to 173 | Read |
| X-7 | `ConsumerGroupRetryService` acts on `FAILED` tracking rows. No production statement writes one. Its job runs every 30 s by default. | `peegeeq-default.properties` 44 to 45; finding 4 run: retried 0, moved 0 | Run for the zero result; Read for the rest |
| X-8 | Two cleanup definitions exist and disagree. No production code calls either. No production job deletes outbox rows. | `CleanupService` 81 to 111; `V010` function; search by name | Read |
| X-9 | `getBlockedMessageStats` counts with `required_consumer_groups` and tracking rows. | `DeadConsumerDetector` 411 to 436 | Read |
| X-10 | A graceful stop cancels the group's subscription. `stopGracefully` on a group started with `start(SubscriptionOptions)` calls `cancel`, which runs the dead-group cleanup (X-5). `subscribe`, `resume`, and the heartbeat leave a cancelled row cancelled; `pause` does not (X-11). Detail below. | `OutboxConsumerGroup` 608 to 627; `PgNativeConsumerGroup` 499 to 529; `SubscriptionManager` 259 to 277, 428 | Read. Three tests assert the cancel as intended. |
| X-11 | `pause` moves a `CANCELLED` or `DEAD` subscription to `PAUSED`, and `resume` then makes it `ACTIVE`. `CANCELLED` is therefore not terminal today. | `SubscriptionManager` 806 to 829 | Read in the fourth revision. None of the five `pause` calls in the subscription tests follows a `cancel`. |

X-4 with X-6, in full. This is the most serious item in the review. It has not been run
end to end.

- **Trigger.** A group started in process with `start(SubscriptionOptions)` under a
  `PeeGeeQManager` with default settings. Nothing sends its heartbeat (X-6). Dead-consumer
  detection is on by default and runs every minute (`peegeeq-default.properties` 42 to
  43). With the default 300-second timeout and 3 misses, the subscription would be marked
  `DEAD` on the third detection run after the timeout, while the group is still running.
  A `PAUSED` subscription is marked the same way. Every manager the setup service builds
  has these defaults, so the trigger also applies to a template-provisioned setup reached
  through REST. A stopped consumer, `cancel`, force-remove, and a graceful stop (X-10)
  reach the same cleanup
  (X-5).
- **Effect.** Cleanup runs in a detection run that newly marks at least one subscription
  `DEAD`. In that run it covers every `DEAD` subscription, new and old
  (`DeadConsumerDetectionJob` 313 to 330). A run that marks none runs no cleanup. Step 1
  subtracts 1 from `required_consumer_groups` on each `PENDING` or `PROCESSING` row of the
  topic created after the group subscribed. Step 3 sets `COMPLETED` on every `PENDING` or
  `PROCESSING` row of the topic whose `completed_consumer_groups >=
  required_consumer_groups`. It tests no group and no semantics. On a `QUEUE` topic the
  counter starts at 1, so one dead group takes it to 0. The shared-status claim reads
  `PENDING` rows only, and the offset fetch reads `PENDING` and `PROCESSING` rows only. No
  consumer receives the row afterwards. The 2026-10-11 text said cleanup runs on every
  detection run. That was wrong.
- **Scope of damage.** Every pending message of the topic, for every consumer of the
  topic, including plain consumers and live groups.
- **Detection.** The scheduled job logs WARN for each dead group and a WARN summary that
  counts "Messages auto-completed (now eligible for deletion)" (`DeadConsumerDetectionJob`
  400 to 403, 440 to 461). It logs ERROR when a dead group blocks more than 1,000 messages
  or for more than 24 hours (506 to 516). The text presents the completion as cleanup, not
  as loss. `cancel` and force-remove log INFO only. The rows stay in `outbox` as
  `COMPLETED`. The 2026-10-11 text said there was no WARN and no ERROR. That was wrong.
- **Protection.** None in the code. Recovery is a manual status change.
- **Established.** `DeadConsumerGroupCleanupIntegrationTest
  .testDecrementToZeroAutoCompletesMessage` (435 to 476) asserts that two unprocessed
  messages of a single dead group become `COMPLETED` with `completed_consumer_groups` 0.
  The path from a running in-process group to that state has not been run. Every cleanup
  and detection test read uses a `PUB_SUB` topic with the default mode. None uses a `QUEUE`
  topic or an `OFFSET_WATERMARK` topic. Those cases rest on the SQL, which tests no
  semantics and no mode. `DeadConsumerDetectionJobIntegrationTest
  .testEndToEndDetectCleanupPipeline` covers only the case where the live group had already
  completed the messages. The default threshold of three consecutive missed runs is
  asserted by `FlappingProtectionIntegrationTest`. Every one of these tests runs on the
  hand-written schema (S-12). The shared test configuration turns detection off
  (`PeeGeeQTestConfig` 164 to 168), so a running group under default detection lies
  outside the existing tests by construction.
- **Treatment.** Phase 1, the first implementation phase (OD-10). The red tests R-1 to R-3
  and their correction land together: the detection job, `cancel`, and force-remove stop
  changing messages, and a running group sends its heartbeat (X-6).

X-10 in full. It was found in the test read. It has not been run.

- **Trigger.** A group started with `start(SubscriptionOptions)` is stopped with
  `stopGracefully()`. No fault and no timeout is needed. `stop()` and `close()` do not
  cancel.
- **Effect.** The stop calls `SubscriptionService.cancel`. The subscription becomes
  `CANCELLED`, and the cleanup of X-4 runs for the topic. A restart does not reactivate it:
  `subscribe` keeps `CANCELLED` and overwrites the start bound, `resume` fails, and a
  heartbeat leaves it. The third revision said the row cannot become `ACTIVE` again through
  the API. That was wrong: `pause` followed by `resume` reactivates it (X-11).
- **Scope of damage.** The pending messages of the topic, as in X-4. After the stop the
  watermark, the insert trigger, and `ZeroSubscriptionValidator` no longer count the group.
  A `PUB_SUB` topic that blocks writes with no subscription then refuses its producers, if
  this was its only group.
- **Detection.** `cancel` logs INFO.
- **Consequence for this proposal.** Q-4 and P-10 make the claim and the fetch test the
  status. Under today's stop contract a restarted group would then receive nothing. OD-12
  and OD-18 settle it: `stopGracefully()` sets `STOPPED`, and a start reactivates.
- **Established.** `OutboxConsumerGroupGracefulShutdownTest` and the native
  `ConsumerGroupSubscriptionIntegrationTest.stopGracefullyCancelsSubscription` assert the
  cancel as intended. `SubscriptionManagerCoreTest.testResumeCancelledSubscriptionFails` and
  `SubscriptionManagerIntegrationTest.testHeartbeatDoesNotResurrectCancelledSubscription`
  assert that `resume` and a heartbeat leave it cancelled. `PartitionedOrderingDemoTest` test 2d sets the status
  back to `ACTIVE` by SQL before it restarts the group. Its comment says the graceful stop
  cancelled the subscription and the update "simulates a consumer restart". What fails
  without that update was not determined. On the shared-status path no claim tests the
  status (Q-4), so a restarted group still claims; that is Read and not run.
- **Treatment.** OD-12, OD-18, and OD-22, ruled: `stopGracefully()` sets `STOPPED` for the
  whole group, runs no cleanup, and keeps bounds and offsets. Phase 1. R-7, R-8, and L-9
  are its red tests.

### Schema

| ID | Defect | Location | Evidence |
|---|---|---|---|
| S-1 | The runtime templates lack the three `V017` tables and `rebalance_generation`. A `PUB_SUB` group cannot run on a template-provisioned schema. | `base/.manifest`; `V017`; `V018` | Read. `DatabaseSetupConnectIntegrationTest` asserts the list of thirteen tables the setup service requires, and none is a partition table. |
| S-2 | Template `08c` allows no `DEAD_LETTER`, adds four columns, and declares no foreign key. | Template `08c` line 6 | Read |
| S-3 | The templates create no outbox function and no outbox trigger. | `base/.manifest` | Read |
| S-4 | `outbox_topics` has a different primary key in the two definitions. | [Schema](PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md#schema) in the implemented contract | Read |
| S-5 | The setup service changes no existing schema. `connectToExistingSetup` applies no DDL, and `createCompleteSetup` refuses an existing database. Flyway runs only from `RunMigrations`, a command-line runner in `peegeeq-migrations`. It sets `baselineOnMigrate(true)`, so it would treat a template-provisioned schema as a baseline and apply later migrations to it. Whether they succeed is not known. | `PeeGeeQDatabaseSetupService` 190 to 453; `RunMigrations` | Read |
| S-6 | `peegeeq-examples-spring/src/main/resources` holds seven `schema-springboot*.sql` files. Two name the reference-counting columns. They are a third schema definition. | Search, 2026-10-11 | Read |
| S-7 | `SchemaContractTest` checks the Flyway schema only, in schema `public`. It asserts column names and types for seven tables, eight indexes, and three functions. It asserts no trigger and nothing from `V017` or `V018`. It tests a status set by inserting each allowed value, and does not test that other values are refused. It requires the columns, indexes, and function that retirement removes. | `SchemaContractTest` | Read |
| S-8 | The test schema is patched after the migrations run: `ALTER TABLE IF EXISTS bitemporal_event_log ADD COLUMN IF NOT EXISTS causation_id`. Tests therefore run on a schema the migrations alone do not produce. | `PeeGeeQTestSchemaInitializer` | Read |
| S-9 | The manager's start check requires four tables by a fixed list. It names neither `outbox_topics` nor the `V017` tables, so a schema without them passes the check and fails later. | `PeeGeeQManager` 781 to 819 | Read. `MissingSchemaFailFastTest` and `OutboxSchemaIsolationCoverageTest` TC-S6 assert the four table names. |
| S-10 | `V019` records an earlier case of S-1 to S-3: a table the migrations had and the templates lacked made a timer fail on every run in setup-service deployments. | `V019` | Read |
| S-11 | Three REST test classes create their database through the template path and then run the full Flyway set over the same schema with `baselineOnMigrate(true)`. They test a schema that neither provisioning path produces alone. Which baseline version Flyway takes, and so whether `V001` runs, was not checked. Other REST tests use the template schema alone: they create subscriptions, run backfill, and cancel there, with no outbox row to act on. No test consumes through a group on a template schema. | `SubscriptionCreateAndBackfillIntegrationTest`, `SubscriptionLifecycleIntegrationTest`, `ConsumerGroupSubscriptionIntegrationTest`; `PeeGeeQTestSchemaInitializer` 119 to 131 | Test read |
| S-12 | Three test classes create the tables by hand. `SharedPostgresTestExtension` is the schema of `BaseIntegrationTest`: 55 test classes in `peegeeq-db` and 7 in `peegeeq-benchmarking`. It has no tracking-row trigger (X-2), no foreign key on tracking rows, no cleanup function, no fan-out index, and no `durable_enabled`. It holds its own text of the `set_required_consumer_groups` function. `PeeGeeQManagerCloseLogLevelTest` and `JsonbConversionValidationTest` hold two more copies. This is a static inventory. The affected tests were not rerun on the Flyway schema. | The three classes; a search of every test tree for `CREATE TABLE` of the seven tables | Test read |
| S-13 | `MigrationIntegrationTest` is a second contract test of the Flyway schema. Seven of its tests require what retirement removes: the tracking table, the three counter columns, `completion_tracking_mode`, `backfill_status`, three functions, and two triggers. `CustomSchemaIntegrationTest` requires the tracking table. Neither asserts a `V017` table. | The two classes | Test read |

Consequence of S-12 for this review. The cleanup, detection, backfill, subscription, topic
configuration, validator, fan-out trigger, and offset component tests of `peegeeq-db` all
run on the hand-written schema (a search confirmed it for the 22 classes this document
cites). `FanoutProducerIntegrationTest` tests that schema's copy of the trigger
function and not the text of `V010`. The `V001` tracking-row trigger never fires in those
tests. The outbox, native, REST, and example tests use the Flyway path or the template path.

### Contract texts that disagree

- `RejectedMessageException` says the message "should be moved to the dead letter queue
  rather than retried or reset to PENDING". `distributeMessage` (827) says a group
  rejection is reset to `PENDING`. The ruling says neither.
- `MessageFilteredException` says it is thrown when "a group filter or member filter
  rejects". The group filter throws the other type.
- `ConsumerGroup.start(SubscriptionOptions)` says the subscription exists before
  consumption starts. `OutboxConsumerGroup` 450 to 453 starts without one.
- `SubscriptionManager.pause` says a paused subscription receives no new messages. No
  claim and no fetch tests the status (Q-4, P-10).
- The Javadoc of `SubscriptionManager.cancel` (371) says a cleanup failure "does not fail
  the cancel operation". The code fails the cancel, and `CancelCleanupIntegrationTest
  .testCancelCleanupFailurePropagates` asserts that it does. The subscription is already
  `CANCELLED` when the failure is returned.
- The engine logs "Offset commit rejected (stale generation)" whenever `commitOffset`
  returns false (425 to 436). It returns false in four cases, each asserted by a test:
  stale generation, a lower offset, an equal offset, and no offset row.
- `ConsumerGroup.stopGracefully` cancels the subscription. `SubscriptionManager` keeps a
  cancelled row cancelled on `subscribe`, `resume`, and a heartbeat, and its comment calls
  the status terminal (428). `pause` followed by `resume` reactivates it (X-11). One test
  sets the row back to `ACTIVE` by SQL to restart a group (X-10).
- The two subscription implementations of this code base disagree on four points. The
  event store fails a registration on a cancelled row, keeps the cursor on a second
  registration, refuses a changed filter, and fails a backward or stale acknowledgement.
  The outbox `subscribe` succeeds on a cancelled row and leaves it cancelled, and it
  overwrites the start bound. The outbox engine reports a refused commit as success. The
  outbox side has no stored filter; rule 7 lets a group filter change.
- `EnhancedErrorHandlingDemoTest` says invalid data goes to a dead letter queue at once,
  and it subscribes to a topic `error-orders-dlq`. Nothing writes that topic. A failed
  `Future` is retried to the limit.
- Test names that state more than the test checks: `testRebalanceUnderLoad_noMessageLoss
  _noInfiniteRedelivery` asserts only that the sum of committed offsets is above zero;
  `testRebalance_stopsProcessingRevokedPartitions` checks only that a commit at the old
  generation returns false. The five tests of `PostgreSQLErrorHandlingTest` name
  serialization failure, deadlock, timeout, and rollback handling, and inject none of them.

### Existing violations of the coding principles, flagged and not copied

- Writes through `withConnection` without a transaction: `PartitionedOffsetManager`,
  `WatermarkCalculator`, `CleanupService`, `ConsumerGroupRetryService`,
  `TopicConfigService`.
- `SubscriptionManager` 189 to 194, 410 to 415, and 592 to 597 log a failed backfill or a
  failed cancel cleanup at WARN. The failure is also returned to the caller. The first two
  versions said the cancel "still reports the status change". That was wrong: the cancel
  fails. What is true is that the status change is already committed when it fails.
- `ConsumerGroupHandler` 149 to 151 does not chain the subscribe `Future`. A failed
  subscribe is logged at WARN and the request answers 201 (M-7).
- `OutboxConsumerGroupMember` holds `vertx` and `filterErrorConfig` fields that nothing
  reads after construction.
- `DeadConsumerDetector` runs its two updates, the miss count and the `DEAD` mark, through
  `withConnection` and not in one transaction (156, 228).
- `PeeGeeQManager` turns a failed job stop into success after a WARN (1076 to 1078, 1083 to
  1085).
- `PeeGeeQDatabaseSetupService.addQueue` applies the queue template outside a transaction
  (1214).
- `OutboxFactory.isHealthy` turns a failed check into `false` after a WARN (359 to 365).
- `SqlTemplateProcessor` treats an empty template as success after a WARN.
- `RunMigrations` takes its connection values from environment variables.

In the tests, from the test read. These patterns are not copied by any new test.

- Tests that pass whether the operation worked or failed. Five tests of the REST
  `ConsumerGroupSubscriptionIntegrationTest` complete with no assertion when the server
  answers 500. Six subscription tests and six dead-letter tests of `EventStoreIntegrationTest`
  accept any of several status codes. `SubscriptionPersistenceAcrossRestartIntegrationTest`
  test04 accepts 404 and 200. `PeeGeeQDatabaseSetupServiceEnhancedTest.testDynamicQueueAddition`
  turns any failure into success.
- Assertions that cannot fail: `deleted >= 0`, `messageCount >= 0`, `purgedCount >= 0`.
- Teardown that ignores a failed close or delete: `OutboxConsumerGroupFilteredMessageStatusTest`,
  `OutboxConsumerGroupSubscriptionEdgeCasesTest`, the REST `ConsumerGroupSubscriptionIntegrationTest`,
  `ManagementApiHandlerTest`.
- Fixed timers used as waits where a condition could be polled: several outbox group tests
  and `PartitionedNativeConsumerIntegrationTest` 6.1 to 6.4.
- Tests that cannot fail. `SimpleConsumerGroupTestTest` creates no consumer group and
  asserts `>= 0` on local counters. `ConsumerGroupHandlerTest` calls no handler code and
  holds `assertTrue(true)`. `SchemaValidationTest` asserts two constraint counts `>= 0`.
- A failed stop or close turned into success: `stopGracefully().transform(ar -> succeeded)`
  in `PartitionedOrderingDemoTest` and `EventSourcingCQRSDemoTest`; teardown that logs and
  continues in `NativeQueueFeatureTest`, `HighFrequencyProducerConsumerTest`, and
  `OutboxConsumerEdgeCasesCoverageTest`.
- `group.start()` not observed: `ConsumerGroupMessageAckTest`,
  `AdvancedProducerConsumerGroupTest`, `ConsumerGroupResilienceTest`,
  `HighFrequencyProducerConsumerTest`, and the two Spring consumer group tests.
- Assertions that accept two results or a range: `DeadConsumerDetectorComprehensiveTest`
  ("0 to 5"), `OutboxSchemaIsolationCoverageTest` TC-S9 (`FAILED` or `DEAD_LETTER`), and the
  backfill benchmarks, whose assertions sit inside `ifPresent`.
- `OutboxConsumerGroupGracefulShutdownTest` sets a private field by reflection.
- `PartitionedConsumerEngineLifecycleTest` installs the handler by reflection on a private
  field.

## Reading Record

Fourth revision, 2026-10-12. The third revision headed its lists "Read in full" and said
every listed file was read in full. That was wrong, for two reasons the owner gave. The
test files were read as a filtered print that left out Javadoc and log statements, which
hold contracts and detection behaviour. And the record itself lists files read in part.

Task 1 of the task list is therefore not complete under its read-in-full condition. The
owner ruled on 2026-10-12 that the reading set is not expanded again. This record states
what was read and how, and what was not.

**How the test list was set.** The owner's list names categories of tests: rejection,
retry, cleanup, offsets, and the tests that run the template path. The first two revisions
read a selection of 54 and listed the rest as read in part. That was a wrong judgment: the
selection was treated as the owner's list. Three searches then set the list.

1. A search of test file names for the listed subjects found 133 files. All are read.
2. A search of the test classes that call the setup service found 14 more that touch a
   fan-out object. All are read.
3. Two searches of test file contents, for the reviewed classes and tables and for retry
   and dead-letter terms, found 132 more. Every matching line was read. 42 files test a
   listed subject under another name and are read. 90 name a term only in passing
   and are not read; they are listed below.

With the setup-service, runtime setup, and schema contract tests that the owner's list
names, the files read number 211.

**Test trees, read as a filtered print.** 211 files, about 87,000 lines, of the 628 Java files in the
test trees: `peegeeq-db` 63, `peegeeq-outbox` 44, `peegeeq-rest` 21, `peegeeq-native` 19,
`peegeeq-examples` 18, `peegeeq-api` 11, `peegeeq-benchmarking` 9,
`peegeeq-integration-tests` 9, `peegeeq-examples-spring` 6, `peegeeq-migrations` 4,
`peegeeq-bitemporal` 3, `peegeeq-runtime` 3, `peegeeq-rest-client` 1. Each file was passed
through a filter, and the filtered print was read from its first line to its last. The
filter removed: blank lines; `import` and `package` lines; every line that begins a block
comment or continues one, which removes the licence and all Javadoc; statements that begin
with a logger call at INFO, DEBUG, TRACE, or WARN; lines that hold only closing
punctuation; `@Override` lines; and the connection settings of builder chains. It kept
line comments and ERROR log statements. It cut each line at 200 characters, and the print
command cut most at 135. The text of a long SQL string or assertion message beyond that
point was not read.

What this leaves out matters. A Javadoc block can state a contract, and a log statement
can be the only detection of a condition. Neither was read in the test files. Statements
in this document about what a test asserts rest on the code lines and the line comments.

**Production code and schema, recorded as read whole on 2026-10-11.**
`PEEGEEQ_CONSUMER_GROUP_FANOUT_DESIGN.md`; `PEEGEEQ_PARTITIONED_CONSUMPTION_DESIGN.md`;
`ConsumerGroup`, `RejectedMessageException`, `MessageFilteredException`;
`OutboxConsumerGroup`, `OutboxConsumer`, `OutboxConsumerGroupMember`;
`PartitionedConsumerEngine`, `PartitionedFetcher`, `PartitionedOffsetManager`,
`WatermarkCalculator`, `WatermarkJob`, `PartitionAssignmentService`;
`PgNativeConsumerGroup`, `PgNativeConsumerGroupMember`; `ConsumerGroupFetcher`,
`ConsumerGroupRetryService`, `CompletionTracker`, `DeadConsumerGroupCleanup`,
`CleanupService`, `BackfillService`, `TopicConfigService`; `SubscriptionManager` from line
56 to the end; migrations `V001`, `V010`, `V011`, `V016`, `V017`, `V018`; templates `04a`,
`08a` to `08e`, `09a` to `09e`, and `base/.manifest`.

**Production code and schema, recorded as read whole on 2026-10-12.** `PgNativeQueueConsumer` (1,506
lines); `DeadConsumerDetector` (521); `DeadConsumerDetectionJob` (586);
`ConsumerGroupRetryJob` (257); `FilterCircuitBreaker` (212); `OutboxFactory` (620);
`PeeGeeQManager` (1,091, from line 60); `PeeGeeQDatabaseSetupService` (1,912, from line
60); `SqlTemplateProcessor` (218); `ZeroSubscriptionValidator` (144); `TopicConfig` (214);
`RunMigrations` (196); `StuckMessageRecoveryManager` (255); `DeadLetterQueueManager` (554,
without its log statements); migrations `V012`, `V013`, `V014`, `V015`, `V019`, `V020`. In
the third revision: `DurableBiTemporalSubscriptionCoordinator` (606) and
`DurableBiTemporalDelivery` (110). The lines not read in the two large classes are the
licence, the imports, and the first field declarations.

The fourth revision did not check again how each production file was read. The two lists
above carry over the record made at the time.

Read again in the fourth revision, to write the lifecycle and replay contracts:
`PartitionAssignmentService` 66 to 108, 186 to 225, and 270 to 284; the SQL of
`PartitionedOffsetManager.commitOffset`; `SubscriptionManager` 250 to 280, 320 to 420, 510
to 560, and 800 to 860; the status predicates of `DeadConsumerDetector`,
`ZeroSubscriptionValidator`, and `WatermarkCalculator`; and the message routes of
`PeeGeeQRestServer`. These files were already in the reading set.

**Not read, or read in part.**

| What | Size | What was read | Why it is left |
|---|---|---|---|
| Test files that name a searched term only in a queue setting of a setup request, a stub method, a getter call, a comment, or the schema component list | 90 files | Every matching line | None tests rejection, retry, cleanup, offsets, or schema. The names follow. |
| Other Java files in the test trees | 327 files | Nothing | They match no search. |
| `ConsumerGroupHandler` | 949 lines | Lines 84 to 160 and 596 to 625 | M-7 rests on those lines. It is read whole by the phase that changes it. |
| `PeeGeeQTestConfig` | 176 lines | Lines 130 to 176 | The two defaults that this document cites. |
| REST handlers and management UI files named in the two inventories | not counted | Found by search | Read in the phase that changes each. |
| Javadoc, log statements below ERROR, and line text beyond the cut, in the 211 test files | not counted | Nothing | The filter removed them. The owner ruled that the reading set is not expanded again. |
| The seven Spring example schema files | 7 files | Searched for `CREATE TABLE` | T5-3 compares them. |

The 90 test files, by module. `peegeeq-api`: `DatabaseServiceTest`,
`DatabaseSetupResultQueueConfigTest`, `QueueConfigTest`. `peegeeq-benchmarking`:
`ConsumerModePerformanceStandardizedTest`, `ConsumerModePerformanceTest`,
`PeeGeeQRestPerformanceTest`, `PerformanceComparisonExampleTest`. `peegeeq-bitemporal`:
`PgBiTemporalEventStoreCloseLogLevelTest`. `peegeeq-db`: `AdvancedConfigurationExampleTest`,
`HealthCheckManagerCoreTest`, `HealthCheckManagerTest`, `MultiConfigurationExampleTest`,
`NativeVsOutboxComparisonExampleTest`, `PeeGeeQConfigurationTest`,
`PeeGeeQManagerIntegrationTest`, `PeeGeeQMetricsCoreTest`, `PeeGeeQMetricsTest`,
`PeeGeeQTestSuite`, `PgMetricsProviderCoreTest`, `PgQueueFactoryProviderCoreTest`,
`TestDatabaseService`. `peegeeq-examples`: `MultiConfigurationIntegrationTest`,
`SharedTestContainers`. `peegeeq-examples-spring`: `OrderConsumerServiceTest`,
`OutboxMessageOrderingSpringBootTest`, `SharedTestContainers`. `peegeeq-integration-tests`:
`MultiSetupNativeCrossIsolationTest`, `MultiSetupNativeListenIsolationTest`,
`NativeConcurrencySmokeTest`, `PeeGeeQCriticalPathSmokeTest`,
`RestClientMessagesStatsContractSmokeTest`, `RestClientSetupsContractSmokeTest`,
`SmokeTestBase`. `peegeeq-native`: `ConsumerModeBackwardCompatibilityTest`,
`ConsumerModeFailureTest`, `ConsumerModeMetricsTest`, `ConsumerModePropertyIntegrationTest`,
`ConsumerModeResourceManagementTest`, `ConsumerModeTypeSafetyTest`,
`HybridModeEdgeCaseTest`, `ListenNotifyOnlyEdgeCaseTest`, `ListenReconnectFaultInjectionIT`,
`MemoryAndResourceLeakTest`, `MessagePriorityExampleTest`, `MultiConsumerModeTest`,
`MultiTenantSchemaIsolationTest`, `NativeProducerNotifyFailureIntegrationTest`,
`NativeVsOutboxComparisonExampleTest`, `PeeGeeQConfigurationConsumerModeTest`,
`PgNativeQueueBrowserTailIntegrationTest`, `PgNativeQueueConsumerListenIT`,
`PgNativeQueueFactoryContractTest`, `PgNativeQueueFactoryIntegrationTest`,
`PgNativeQueueShutdownTest`, `PollingOnlyEdgeCaseTest`, `QueueFactoryConsumerModeTest`.
`peegeeq-outbox`: `MultiTenantSchemaIsolationTest`, `OutboxFactoryContractTest`,
`OutboxFactoryRegistrarTest`, `OutboxProducerIntegrationTest`,
`OutboxQueueBrowserIntegrationTest`, `OutboxQueueBrowserTailIntegrationTest`.
`peegeeq-rest`: `AdminConsumingReadGuardTest`, `BasicUnitTest`,
`BatchMessageProcessingIntegrationTest`, `CallPropagationIntegrationTest`,
`ConfigParserTest`, `ControllableSetupService`, `DatabaseInfrastructureDiagnosticTest`,
`DatabaseTelemetryHandlerIntegrationTest`, `ManagementApiErrorRateTest`,
`MessageOperationsE2ETest`, `PeeGeeQRestServerStopTest`, `QueueDeleteManualTest`,
`QueueDetailsConfigResponseTest`, `QueueManagementAdvancedE2ETest`,
`QueueManagementE2ETest`, `QueuePauseResumeIT`, `QueuePauseResumeManualTest`,
`QueueStatsStreamIntegrationTest`, `ServerSentEventsHandlerTest`,
`SetupManagementIntegrationTest`, `SseMessageStreamDemoIntegrationTest`,
`SystemMonitoringHandlerTest`, `WebSocketHandlerTest`,
`WebSocketMessageStreamIntegrationTest`, `WebhookPushDeliveryIntegrationTest`.
`peegeeq-rest-client`: `ClientConfigTest`, `PeeGeeQRestClientTest`. `peegeeq-test-support`:
`OnSuccessExceptionSwallowingGuardTest`.

## Phase Order and Test Plan

The owner set this order on 2026-10-12, in the review of the third revision. It replaces
the eight steps of the earlier order.

### This design phase

The fourth revision completed the first design task list. The owner did not approve it and
set a final list in the review of the fourth revision.

| Task | State |
|---|---|
| 1. Add `STOPPED` and revise the lifecycle table. | Done. [Subscription Lifecycle Contract](#subscription-lifecycle-contract). |
| 2. Restrict keyed replay and correct the other-group guarantee. | Done. [Replay Contracts](#replay-contracts), 2 and 3. |
| 3. Add migration fencing and the plain-consumer semantics rule. | Done. [Administrative semantics migration](#administrative-semantics-migration). |
| 4. Move backfill and tracking-table retirement to phase 4. | Done. [Migration Matrix](#schema-objects-by-phase) and phase 4 below. |
| 5. Define the operator dead-letter transaction. | Done. [Operator dead-letter of one message](#operator-dead-letter-of-one-message). |
| 6. Correct the shared-versus-group dead-letter wording. | Done. End state 3 and replay contract 3. |
| 7. Reissue only those sections and stop. | Complete. The owner approved corrections 1 to 6 in the fourth review, ruled on OD-22, and added one migration correction. The sixth revision applies both. Phase 1 follows. |

### Implementation phases, after approval

| Phase | Work | Tasks of the list |
|---|---|---|
| 1 | Dead-group and graceful-stop safety | None named. It corrects X-4, X-5, X-6, X-10, and X-11. |
| 2 | Canonical schema provisioning | 5 |
| 3 | Offset correctness and replay | 10, in part |
| 4 | Reference-counting retirement and controlled migration | 4, 7 |
| 5 | Outcome API | 6, 8 |
| 6 | Queue exclusions | 9, 11 |
| 7 | `PUB_SUB` outcomes | 10, the rest |
| 8 | Cleanup, release, and final schema deletion | 12 |

The owner's eight phases do not name member dispatch (task 8) or native parity (task 11).
This document places member dispatch in phase 5 and the native queue outcomes in phase 6.
The owner approved both placements. Tasks 13 and 14 apply to every phase and to the end.

Rules for every phase.

- The failing tests are written and run first. Each must fail for the stated reason.
- Each red test and its narrow production correction land in the same phase. No phase is
  committed red (OD-10).
- Tests use Testcontainers and no mocks. Each follows the pattern of the named existing
  class in the same module.
- The run is the smallest scope that carries the test's tag, through `Tee-Object`, with the
  per-class `Tests run:` lines read. Whole-module and UI runs are Jenkins builds.
- The shared test configuration turns dead-consumer detection and the retry job off. A
  test that needs either job sets the property itself.
- Until phase 2, a new test that touches the schema follows the pattern of its module. A
  `peegeeq-db` test extends `BaseIntegrationTest` and runs on the hand-written schema. A
  test in another module uses the Flyway test path through `PeeGeeQTestSchemaInitializer`.
  A change to a schema definition is made in every definition, and each definition is
  tested on its own path. The first version of this rule named the Flyway path for every
  new test; that conflicted with the pattern of `peegeeq-db`.

Patterns to follow, from the test read. Waiting: a helper that polls a condition with
`vertx.timer(100)` and an attempt limit (`OutboxRetryLogicTest.awaitTerminalState`,
`OutboxOffsetWatermarkSchemaIsolationTest.awaitDatabaseCondition`). A dependency that fails,
without a mock: `OutboxRetryResilienceTest`, which holds a row lock from a second
connection, ends the blocked backend with `pg_terminate_backend`, or exhausts a pool; and a
real subclass of the dependency that fails, as in `SetupQueueFactoryFailureIntegrationTest`
and `DeadConsumerDetectionJobIntegrationTest`. Catalog checks: a verify pool from
`PgConnectionManager`, as in `PeeGeeQDatabaseSetupServiceEnhancedTest`. Every loss or
duplicate test counts deliveries per message id; no existing rebalance test does.

More patterns. A DDL fault: rename a column or a table, and restore it in teardown
(`StuckMessageRecoveryManagerIntegrationTest`,
`DeadLetterQueueManagerFocusedIntegrationTest`). A database trigger that raises on its first
call (`RetryableErrorIT`). A transaction held open with a `Promise`
(`DurableBiTemporalReplayIntegrationTest.delayedLowerIdCommitCannotBeSkipped`). Handlers held
at a barrier to observe admission (`PgNativeQueueConsumerCapacityIT`). A clock passed to the
class under test (`CircuitBreakerRecoveryTest`). Teardown that attempts every close and then
fails with the first failure (`OutboxSchemaIsolationCoverageTest`).

"Red today" gives the reason the test is expected to fail now. Where the evidence is Read,
a test that passes instead refutes the item, and this document is corrected before the
phase continues.

### Phase 1. Dead-group and graceful-stop safety

It applies the [lifecycle contract](#subscription-lifecycle-contract). Its one schema
change is the new status value in the check constraint.

| Test | Asserts | Red today |
|---|---|---|
| R-1 | A group started in process with `start(SubscriptionOptions)` on a `QUEUE` topic, with a 1 s heartbeat timeout, 1 miss, and one detection run, keeps its pending messages `PENDING`. The same on an `OFFSET_WATERMARK` topic. | X-4, X-6. Read. No existing cleanup test uses either kind of topic. |
| R-2 | A running in-process group's subscription stays `ACTIVE` across three detection runs. | X-6. Read. |
| R-3 | `cancel` of one of two groups on a `QUEUE` topic leaves pending messages `PENDING`. | X-5. Read. |
| R-7 | A group started with options stops gracefully. Its subscription is `STOPPED`, with the same start bound and offsets. It starts again. The subscription is `ACTIVE`, and the group receives a message sent while it was stopped. On a `QUEUE` topic, and on an `OFFSET_WATERMARK` topic with one sweep run while it was stopped. | X-10. Read. Three tests assert the cancel and change. |
| R-8 | A graceful stop of the only group of a topic leaves its pending messages `PENDING`. | X-10, X-5. Run in phase 1: red before the correction (expected `PENDING`, was `COMPLETED`), `logs/f20-p1a-red6-outbox-graceful-20261012.log`. The cleanup changes only messages created at or after the time the group subscribed. |
| R-9 | Two instances of one group run. One is closed. The subscription stays `ACTIVE`, and the other instance keeps receiving. The same when the instance is stopped with `stop()`. | Expected green today for the status. It records the instance-local contract (OD-22). |
| L-1 | `pause`, `resume`, `subscribe`, and a heartbeat on a cancelled subscription each fail and leave it `CANCELLED`. | X-11: `pause` sets `PAUSED`. `subscribe` and the heartbeat succeed today. Read. |
| L-2 | A heartbeat on a `DEAD` subscription fails and leaves it `DEAD`. `resume` and a start each make it `ACTIVE`. | OD-16. Today a heartbeat revives it, and a test asserts that. |
| L-3 | Detection leaves a `PAUSED` and a `STOPPED` subscription unchanged after the heartbeat timeout. | OD-6. Three tests assert that a paused one becomes `DEAD` and change. |
| L-4 | A second start with other options keeps the stored start bound. | OD-12. `StartPositionDatabaseStateTest` and `SubscriptionManagerEdgeCaseTest` assert the overwrite and change. |
| L-5 | A start on a `DEAD` subscription logs the count of rows released past the group. | No such count. |
| L-6 | With write blocking on, a producer can send while the topic's only subscription is `PAUSED` or `STOPPED`. | The validator counts `ACTIVE` only. Read. |
| L-7 | A running group whose subscription is marked `DEAD` logs ERROR at its next heartbeat and stops consuming. | No in-process heartbeat exists (X-6). |
| L-8 | An operator pauses a running group. The group stops gracefully and starts again. The subscription is `PAUSED` after each step. After `resume` it is `ACTIVE`. | OD-18. Today the stop cancels the subscription. |
| L-9 | Two instances of one group run. One calls `stopGracefully()`. The subscription is `STOPPED`. The other instance's next heartbeat fails; it logs ERROR and stops. A message sent after that stays `PENDING`. After an explicit start the subscription is `ACTIVE` and the message is delivered. | Lifecycle rule 6 (OD-22). Today the stop cancels the subscription and nothing stops the other instance. |
| L-10 | `pause` on a stopped subscription gives `PAUSED`. `resume` on a stopped subscription fails. A heartbeat on a stopped subscription fails and leaves it `STOPPED`. | No `STOPPED` value exists. |
| T10-10 | Group A is `ACTIVE` and has consumed every row. Group B is `PAUSED` and group C is `STOPPED`, and neither has. One sweep leaves the rows they have not consumed `PENDING`. | The watermark counts `ACTIVE` subscriptions only. Read. |
| U-1 | A settings update that names one heartbeat setting changes that setting only. The other setting, the status, and the start bound are unchanged. | OD-23. The update called `subscribe`, and the parser inserted defaults for omitted fields. Run in increment 1b. |
| U-2 | A settings update on a `STOPPED` subscription leaves it `STOPPED`. | OD-23. `subscribe` made it `ACTIVE`. Run in increment 1b. |
| U-3 | A settings update that names the stored bound is accepted. One that names another bound, or `FROM_NOW`, returns 409 and changes nothing. | OD-23. The update overwrote the bound. Run in increment 1b. |
| U-4 | The response of a settings update returns the stored options. | OD-23. It returned the submitted object. Run in increment 1b. |
| U-5 | A native group started with `FROM_NOW` stops gracefully. A message is sent. The group starts again and receives the message. | N-11. Run in increment 1b. |

Run in increment 1b of phase 1: L-1, L-4, L-10, U-1 to U-5, and the delivery part of R-7
on a `QUEUE` topic. L-2 and L-8 are covered at the subscription service and not with a
running group. The task register names the red run and the green run of each, and it
states which red runs were reconstructed by a temporary change.

The correction, in one phase.

- The status value `STOPPED` is added: the check constraint in its three definitions, and
  the two enums. `StoppedSubscriptionStatusMigrationTest` runs the step from V020 to V021
  on a schema that holds rows.
- `stopGracefully` sets `STOPPED` on an `ACTIVE` subscription. `stop` and `close` change no
  status. If the update fails, the group still stops and the returned `Future` fails. That
  settles N-10; the outbox test `stopGracefully_cancelFails_stillStops` changes.
- The detection job, `cancel`, and force-remove no longer call the dead-group cleanup, and
  `DeadConsumerGroupCleanup` is deleted with its tests (approved).
- A running group sends the subscription heartbeat. When the heartbeat fails with
  `STOPPED`, `DEAD`, or `CANCELLED`, the instance logs ERROR and stops consuming.
- The transitions of the lifecycle table are enforced in the SQL of `SubscriptionManager`.
- The stored start bound is kept (OD-23). `subscribe` writes the bound only when it
  creates the row. The native start reads the stored bound. The REST update of an
  existing subscription changes only the heartbeat settings that the request names.
- Detection reads `ACTIVE` only. The watermark and the write validator count `ACTIVE`,
  `STOPPED`, and `PAUSED`.

Dependency failure modes written first: the heartbeat update fails; the heartbeat matches
no row; the status update at a graceful stop fails; a detection run fails; a lifecycle
call or a settings update fails in the database or matches no row; the read of the
stored bound at a native start fails or returns no row. In increment 1b the two native
modes were tested after the code, and the REST update has no test of a database failure.
The task register records both.

Existing tests that assert what phase 1 changes. The stop contract: three tests of
`OutboxConsumerGroupGracefulShutdownTest`, the native `stopGracefullyCancelsSubscription`,
and `PartitionedOrderingDemoTest` test 2d, which loses its SQL reactivation. The status
values: `SubscriptionStateTest` and `SubscriptionStatusTest`, which assert four values and
their ordinals. Paused detection:
`FlappingProtectionIntegrationTest.testPausedSubscriptionFlappingProtection` and two tests
of `DeadConsumerDetectorComprehensiveTest`. The start bound:
`StartPositionDatabaseStateTest.testUpdateSubscriptionChangesStartPosition` and
`SubscriptionManagerEdgeCaseTest`; the native `ConsumerGroupSubscriptionTest` and
`OutboxConsumerGroupSubscriptionTest`, which start one group name with different start
options; REST `SubscriptionOptionsCoreTest`; and
`RestClientConsumerGroupsContractSmokeTest`. The heartbeat: the revival tests of
`SubscriptionManagerIntegrationTest`, one test of `FlappingProtectionIntegrationTest`,
and `ResurrectionReBackfillIntegrationTest`. The
cleanup class: `DeadConsumerGroupCleanupIntegrationTest`, `CancelCleanupIntegrationTest`,
`ForceRemoveUnitTest`, `ForceRemoveIntegrationTest`, `DetectionJobTracingTest`,
`DeadConsumerDetectionJobFailureHealthCoreTest`, the cleanup half of
`DeadConsumerDetectionJobIntegrationTest`, and the tests whose teardown calls
`cleanupAllDeadGroups`.

### Phase 2. Canonical schema provisioning

| Test | Asserts | Red today |
|---|---|---|
| T5-1 | In one PostgreSQL container, a Flyway schema and a template schema have the same columns, types, defaults, constraints, indexes, functions, and triggers for every shared table. | S-1 to S-4. Read. |
| T5-2 | A `PUB_SUB` group starts and consumes on a template-provisioned schema. | S-1. Read. |
| T5-3 | The Spring example schemas name no column that the two paths do not have. | Not determined. The seven files (S-6) were not compared with either path. |
| T5-4 | No test class creates a PeeGeeQ table by hand: a search of every test tree for `CREATE TABLE` of the seven tables finds none. | S-12. Three classes do. |

This phase also decides the upgrade path for an existing template-provisioned schema
(S-5), because phase 4 changes schema. The three REST test classes that run Flyway over a
template schema (S-11) are changed to use one path. `SharedPostgresTestExtension` and the
two other hand-written schemas (S-12) move to the Flyway test path (OD-14). The
`peegeeq-db` tests that then meet the Flyway triggers for the first time are listed and
corrected here. The phase produces the catalog list of objects that depend on a removed
table or column, for phases 4 and 8.

Dependency failure modes written first: a template statement fails part-way; the two
definitions are applied to a schema that already holds an older version.

### Phase 3. Offset correctness and replay

P-13 to P-15 are corrected here, before phase 4 moves any topic (OD-11). The log replay is
built here. Tests in this phase use outbox groups or the engine itself, not native groups,
because the native offset path is removed in phase 4 (OD-15).

| Test | Asserts | Red today |
|---|---|---|
| T10-1 | A second connection locks the first rows of a key and leaves later rows unlocked. The group fetches, processes, and commits. The lock is released. The locked rows are then delivered. | P-4. Read. `PartitionedFetcherIntegrationTest` 3.8 and 3.15 assert the skip and change with the correction. |
| T10-2 | Transaction A inserts and stays open. Transaction B inserts and commits. The group consumes B's row and commits. A commits. The group then receives A's row. | P-5. Read. The event store has the same test (`delayedLowerIdCommitCannotBeSkipped`); it is the shape to mirror. |
| T10-8 | A `FROM_NOW` group does not receive a row older than its subscription. A `FROM_BEGINNING` group receives every pending row. A `FROM_TIMESTAMP` group receives the rows at or after the timestamp. | P-6. Read. `LateJoiningConsumerDemoTest` asserts the three cases through the retired fetcher. |
| T10-9 | A `PAUSED` group fetches nothing. After resume it receives every message sent while it was paused. | P-10. Read. |
| T10-11 | One running instance, two keys. The first message of the second key arrives after the start. The first key commits a higher id. One sweep runs. The second key's message is still `PENDING`. | P-13. Read. |
| T10-12 | One running instance receives a message for a key that did not exist when it started. | P-14. An existing test asserts the opposite and changes. |
| T10-13 | Two instances share the keys. One stops without leaving. The other takes over its keys with no further join. | P-15. Read. |
| T10-14 | A commit refused for a stale generation is not reported as success. The engine reloads its assignments and delivers the batch again. | The engine logs WARN and succeeds (425 to 436). Read. |
| RP-L1 | A group at offset 10 replays from 4. It receives 4 to 10 again, in order, and then new messages. Another group at offset 10 receives nothing again. | No such operation. |
| RP-L2 | Rows 4 to 10 had been released. After the replay they are `PENDING`. A sweep leaves them. They are released again when the group has passed them. | No such operation. |
| RP-L3 | The owning instance commits its batch in flight after the replay. The commit is refused, and the batch is delivered again from the replay position. | No such operation. |
| RP-L4 | An all-key replay from an id below the start bound lowers the bound. A replay from an id above a key's offset leaves that key unchanged. A replay on a `DEAD` or `CANCELLED` subscription fails. | No such operation. |
| RP-L5 | A keyed replay moves the offset of that key only and reopens rows of that key only. The group receives no row of another key again. | No such operation. |
| RP-L6 | A replay from an id that retention has deleted reports the lowest replayed id. | No such operation. |
| RP-L7 | A keyed replay from an id below the group's start bound fails. The bound, every offset, and every row status are unchanged. With an id bound and with a timestamp bound. | No such operation. |
| RP-L8 | After a replay, a second group that had passed the reopened rows receives none of them, and its offsets are unchanged. A third group that has consumed nothing of the key, and has the rows inside its start bound, receives them. | No such operation. |

The corrections for P-4 and P-5 are designed and reviewed before they are written. Each is
checked first against the event store's durable subscription, which holds design precedents
for P-3, P-5, P-10, and P-15 (precedent table in the review). The watermark calculation,
its advance, and the sweep move into one transaction that locks the topic row.

Dependency failure modes written first: the fetch transaction fails after it has set
`pending_offset`; the offset commit returns false because the generation changed; the
subscription row is missing when the start bound is read; the replay transaction fails
after the offsets moved and before the reopen; the lock on the topic row times out.

Existing tests this phase changes on purpose:
`PartitionedConsumerSafetyIntegrationTest.testNewMessageGroupAfterJoin_notAssignedUntilRebalance`;
`PartitionedFetcherIntegrationTest` 3.8 and 3.15; `PartitionedOffsetManagerIntegrationTest`
1.1 and edge case G, which assert that a new offset row starts at 0. The native
`PartitionedConsumerSafetyIntegrationTest` and `ConsumerGroupFaultIntegrationTest` reach the
engine through native groups; they move to outbox groups here.

### Phase 4. Reference-counting retirement and controlled migration

Prerequisites: phases 1 to 3 are complete, and the log replay tests RP-L1 to RP-L8 are
green. This phase removes backfill and the tracking table (OD-4, amended).

| Test | Asserts | Red today |
|---|---|---|
| R-4 | Two groups on a `PUB_SUB` topic created with defaults each receive every message. | The default mode runs the queue claim. Read. |
| R-5 | A subscribe with `FROM_BEGINNING` inserts no tracking row, and a later insert on another topic creates none. | X-2, X-3. Read. |
| R-6a | The schema holds no `completion_tracking_mode` column, no `backfill_*` column, no `outbox_consumer_groups` table, and none of its triggers and functions. | They exist. |
| MIG-1 | The migration stops and names the topic for a `PUB_SUB` plus `REFERENCE_COUNTING` row, and for a `QUEUE` plus `OFFSET_WATERMARK` row. It drops nothing. | No such migration exists. |
| MIG-2 | The operator sets a `PUB_SUB` topic to `OFFSET_WATERMARK`. The topic holds pending rows older and newer than two subscriptions, one `FROM_NOW` and one `FROM_BEGINNING`. After the migration each group receives exactly the rows inside its start bound. | P-6. Read. |
| MIG-3 | The administrative migration sets a `PUB_SUB` topic to `QUEUE`. Its offset rows are deleted. Each pending message is then delivered once in total. | No such operation. |
| MIG-4 | A schema with rows in `outbox_consumer_groups` migrates. The test records the row counts by status, and the dead-letter rows whose `original_table` names that table, before the drop. The table is absent after it. | No such migration exists. |
| MIG-5 | A native `PUB_SUB` plus `REFERENCE_COUNTING` topic with rows in `queue_messages`. The migration stops and its message says that the topic can only become `QUEUE`. After the administrative migration to `QUEUE`, every row in `queue_messages` is delivered. | No such migration exists. Today the mode selects the table (test read). |
| T7-1 | `start()` on a `PUB_SUB` topic creates the subscription row and consumes through offsets. | M-1. Read. |
| T7-2 | A start whose semantics query fails, because `outbox_topics` was dropped in the test schema, fails and leaves the group `NEW`. | M-2. Read. |
| T7-3 | A group cannot be built without a connection manager. | M-2, M-6, N-5. Read. |
| T7-4 | T7-2 and T7-3 for `PgNativeConsumerGroup`. | N-5. Read. |
| T7-5 | A topic with no row starts on the queue path. | Green today. It records OD-1 as a test. |
| A-1 | `createTopic` with other semantics for an existing topic fails. `updateTopic` cannot change semantics. Creating a `PUB_SUB` row for a name that already holds messages fails. | M-3. Four tests assert the change as intended and are rewritten. |
| A-2 | The administrative migration refuses with an `ACTIVE` subscription, with an assignment row, with a `PROCESSING` or `LOCKED` row, and, for the target `PUB_SUB`, with a row in `queue_messages`. It changes nothing and names what failed. A `STOPPED` or `PAUSED` subscription does not stop it. | No such operation. |
| A-3 | The administrative migration to `PUB_SUB` keeps offset rows. Each group then receives the pending rows inside its bound and above its position. | No such operation. |
| A-4 | While the administrative migration holds its locks, a producer insert on another topic waits and then completes. When another transaction holds a conflicting lock past the timeout, the operation fails and changes nothing. | No such operation. |
| A-5 | A plain outbox consumer and a group started with `start()`, both created while the topic was `QUEUE`, keep running while the topic becomes `PUB_SUB`. The next claim of each fails with a semantics-mismatch error. Each logs it at ERROR and stops polling. The pending row stays `PENDING`. An offset fetch on a topic that became `QUEUE` fails the same way. A new plain consumer's `subscribe` on a `PUB_SUB` topic fails. | The queue claim reads no semantics. Read. |
| NQ-1 | A native group start, a native consumer subscribe, and a native producer send each fail on a `PUB_SUB` topic. A native producer created while the topic was `QUEUE` fails to send after it becomes `PUB_SUB`. | P-11: today the group reads `outbox`. Run for that read. |

Dependency failure modes written first: the semantics query fails; the migration is run a
second time; the administrative migration cannot take a lock within the timeout; it
deadlocks with a transaction that holds one of the five tables.

Existing tests that assert what phase 4 removes, each changed in this phase.

- Backfill, deleted with it: `BackfillServiceIntegrationTest`,
  `BackfillRateLimitingIntegrationTest`, `BackfillServiceUnitTest`,
  `BackfillRateLimitingUnitTest`, four backfill benchmarks, three tests of
  `SubscriptionManagerIntegrationTest`, and two tests of `ManagementApiHandlerErrorTest`.
- Backfill fields: `SubscriptionTest`, `SubscriptionInfoTest`,
  `SubscriptionOptionsValidationTest`, `ManagementApiIntegrationTest` tests 18, 20, and 21,
  and `SubscriptionCreateAndBackfillIntegrationTest` tests 8 to 17.
- Mode and semantics: `PartitionedSubscriptionIntegrationTest
  .testJoinPartitionedGroup_rejectsReferenceCountingTopic` (the error text names
  `OFFSET_WATERMARK`); `TopicConfigTest` and `TopicConfigServiceCoreTest` (the mode); the
  four `TopicConfigService*` tests that change semantics; `EventSourcingCQRSDemoTest`,
  `PartitionedConsumptionReleaseGate`, and the other tests that write
  `completion_tracking_mode` by SQL; the ERROR text "Failed to detect completion-tracking
  mode".
- Schema contracts, in part: `SchemaContractTest`, `MigrationIntegrationTest`, and
  `CustomSchemaIntegrationTest`, for the mode column, `backfill_status`, the tracking
  table, and its triggers and functions. Their counter and cleanup-function assertions
  change in phase 8.
- The native offset path (OD-15): `PartitionedNativeConsumerIntegrationTest`, including
  `testReferenceCountingTopic_unchanged`; `PgNativeConsumerGroupLifecycleTest
  .withoutDatabaseService_startsNormally` (T7-4).
- Deleted with their class: `ConsumerGroupFetcherCoreTest`,
  `ConsumerGroupRetryJobLifecycleTest`, `ConsumerGroupRetryServiceIntegrationTest`, the three
  fan-out benchmarks, and the tests of `ConsumerTracingTest` that call the fetcher or the
  tracker. One test of `OutboxSchemaQuotingTest` builds the retry service.
- Late joining: `LateJoiningConsumerDemoTest` is rewritten on the offset path.
- T7-3 changes how the 45 tests of `OutboxFactoryIntegrationTest` build a factory, and the
  `peegeeq-examples-spring` code that builds one.

### Phase 5. Outcome API

| Test | Asserts | Red today |
|---|---|---|
| T6-1 | Handler success gives `COMPLETED`. A failed `Future`, a thrown exception, and a null `Future` each give `FAILED`. | The type does not exist. Six existing test classes assert the durable effect of the three failure forms on the shared-status path and stay green. |
| T6-2 | No production file outside `peegeeq-api` names `MessageFilteredException`, and none tests `RejectedMessageException` with `instanceof`. | `OutboxConsumer` 775, 782. |
| T6-3 | A payload that cannot be deserialized raises `retry_count` and is dead-lettered at the limit. | Q-10. Read. |
| T8-1 | A member predicate that returns true once and then false is evaluated once, and the handler runs once. | Q-6. Read. |
| T8-2 | One member with limit 1, a handler that holds its `Future`, `peegeeq.consumer.threads` set to 2, two messages: the second message keeps `retry_count` 0, has no dead-letter row, and is delivered after the first completes. | Q-5. Read. `PgNativeQueueConsumerCapacityIT` is the shape to mirror. |
| T8-3 | After a member predicate throws, the member accepts the next message. | Q-7. Read. |
| T8-4 | The native member: the same as T8-1, and a predicate that throws does not delete the message. | N-4. N-1, Run. |

Existing tests that assert the present behaviour and change here, each turned red first.

- `OutboxConsumerGroupCoreTest`: `groupFilterRejectionThrowsRejectedMessageException`,
  `noEligibleConsumerProducesMessageFilteredException`, `groupAcceptsMembersRejectIsTransient`
  (the result becomes an outcome value); `groupFilterThrowsException_rejectedAndMdcCleaned`,
  `groupFilterThrows_incrementsFilteredCount`, `groupFilterThrows_handlerNotInvoked` (a
  thrown group predicate becomes a failure, and the expected ERROR text changes);
  `hungHandler_secondMessageRejectedAtConcurrencyGate` (capacity becomes admission).
- `OutboxConsumerGroupReviewFixesTest`: `defaultMaxConcurrencyRejectsSecondMessage`,
  `customMaxConcurrencyHonored`, `concurrentProcessMessageRace`,
  `groupMemberUsesDefaultConcurrency`, `deactivatedMemberCausesFilteredException`,
  `filteredMessagesIncrementGroupTotal`, `handlerFailuresIncrementFailed`.

Three tests of `OutboxRetryResilienceTest` find a statement by its text
(`SET retry_count = $1`, `SET status = 'DEAD_LETTER', error_message = $1`,
`SET status = 'COMPLETED', processed_at = $1`). A reworded statement breaks them, which
bears on the correction for Q-8.

### Phase 6. Queue exclusions

It creates the two exclusion tables. It builds the queue replay and the operator
dead-letter of one message. It covers the outbox queue path and the native queue.

| Test | Asserts | Red today |
|---|---|---|
| T9-1 | The finding 20 reproduction, kept as a test. "Keep" is delivered. "Drop" has one exclusion row, `PENDING`, `retry_count` 0, no dead-letter row, and its predicate ran once across ten polls. | Q-1, Q-2. Run. |
| T9-2 | The same with three consumer threads and three rejected rows. | Q-1. Run. |
| T9-3 | Group A rejects and group B accepts, with both groups running: B processes it once and the message is `COMPLETED`. | Q-2. Read. `filteredMessageShouldBeAvailableForSecondGroup` stops group A before it starts group B, with a comment that this avoids the starvation. |
| T9-4 | A group predicate that throws raises `retry_count` and dead-letters at the limit. No exclusion row. | Q-3. Read. |
| T9-5 | No eligible member: the message stays `PENDING` with no exclusion and `retry_count` 0, a later acceptable message with an earlier `created_at` and a higher id is still delivered, and the first is delivered after a member that accepts it is added. | Q-1 on the transient path. Read. `memberRejectionsDoNotStarveLaterMessages` covers part of it today. It stays and must stay green. |
| T9-6 | After a filter change, a message rejected earlier is not delivered and a new one is. It replaces `groupRejectionsDoNotStarveLaterMessages`, which asserts the opposite. | The ruling. |
| T9-7 | A group created after every earlier group rejected a message claims it. | No exclusion state exists. |
| T9-8 | A group whose subscription is `PAUSED` claims nothing. | Q-4. Read. |
| T9-9 | A message that every group rejected stays `PENDING` past `message_retention_hours`, with no dead-letter row. The queue statistics report the excluded backlog. | OD-8. No exclusion state exists. |
| RP-Q1 | After a queue replay, a message the group had rejected is evaluated once with the current filter. If accepted it is processed. If refused it has one new exclusion row. | No such operation. |
| RP-Q2 | A queue replay deletes no other group's exclusions and changes no status and no retry count. | No such operation. |
| RP-Q3 | RP-Q1 and RP-Q2 on a native queue. | No such operation. |
| OPD-1 | The operator dead-letters a `PENDING` message of a queue topic. The row is `DEAD_LETTER` with `processed_at` set. One dead-letter row exists with no group name and the given reason. | No such operation. |
| OPD-2 | The same on a native queue: the row is deleted, and one dead-letter row exists. | No such operation. |
| OPD-3 | The call refuses a `PROCESSING` row, a `LOCKED` row, a `COMPLETED` row, a `DEAD_LETTER` row, a missing id, and a `PUB_SUB` topic. Nothing changes. | No such operation. |
| OPD-4 | A claim and the call run together on one message. Either the message is delivered and the call fails, or the message is dead-lettered and no handler runs. Never both. On both stores. | No such operation. |
| OPD-5 | The dead-letter insert fails inside the call. The message keeps its status, and no dead-letter row exists. | No such operation. |
| T11-1 | Group predicate false on a native queue: the row is not deleted, one exclusion row exists, and a second group receives the message. | N-2. Read. No database-backed native test sets a group filter today. |
| T11-2 | No eligible member, in the three cases of finding 3: the row is not deleted and `retry_count` is 0. | N-1. Run. |
| T11-3 | A group predicate that throws raises `retry_count`. | Expected green today (N-3). Read. |
| T11-4 | No eligible member, with one consumer thread: the message is not claimed again at once, and a later message is delivered while it waits. | The native claim has no scan position. Today the row is deleted (N-1). The claim honours `visible_at` on the server clock (test read: `PgNativeQueueConsumerClaimIT`), which supports the delay option. |

Dependency failure modes written first: the exclusion insert fails on a lost connection;
the insert violates the foreign key because the message was deleted; the insert conflicts
with an existing row; the status update matches no row because stuck-message recovery
reset it; the replay delete fails; the dead-letter insert of the operator call fails. Each
must end with the message `PENDING` or absent, and
with the error logged and passed on. None may end `COMPLETED`.

Existing tests that change here. `OutboxConsumerGroupIntegrationTest
.groupRejectionsDoNotStarveLaterMessages` asserts that a rejected row is delivered after a
filter change (T9-6). `OutboxConsumerGroupFilteredMessageStatusTest`: no assertion changes.
In `PgNativeConsumerGroupLifecycleTest`: `groupFilterRejects_incrementsFilteredCount`,
`noEligibleConsumers_incrementsFilteredCount`, `noActiveMembers_filteredAsNoEligible`,
`filterThrowsException_messageRejected`, `removeConsumerDuringDispatch_failsGracefully`.
Each asserts a succeeded result for a case that deletes the message. The parity
differences N-6 to N-9 are settled in this phase: each gets one rule and one contract test
for both implementations. N-10 is settled in phase 1.

### Phase 7. `PUB_SUB` outcomes

It adds the retry columns and the dead-letter group name. It builds the per-group
dead-letter replay.

| Test | Asserts | Red today |
|---|---|---|
| T10-3 | Two messages on one key, the group predicate rejects the first: the second is delivered, the offset is the second id, no dead-letter row. | P-2. Read. |
| T10-4 | A handler that always fails is called max-retries plus one times. A dead-letter row with the group name is written, the offset moves, the second message is delivered, and a second group still receives the first message. | P-1. Run. |
| T10-5 | Three messages, the third fails: the first two are delivered once. | P-3. Read. |
| T10-6 | No eligible member: the offset does not move. After a member is added the message is delivered once. | P-3 for the single delivery. Read. |
| T10-7 | The retry count survives an engine restart. | No stored count. |
| RP-D1 | A per-group dead letter is reprocessed. That group receives the message again and then the later messages of its key again, in order. A second group that had passed those rows receives none of them, and its offsets are unchanged. The dead-letter row is gone. | No such operation. Today's reprocess inserts a new row that every group receives. |
| RP-D2 | Reprocess of a per-group dead letter whose source row was deleted fails and keeps the dead-letter row. | No such operation. |
| RP-D3 | Reprocess of a dead letter with no group name behaves as today. | Green today (`testConcurrentReprocessSameMessageOnlyReinsertsOnce`). |

Dependency failure modes written first: the offset commit returns false because the
generation changed after the handler ran; the dead-letter insert fails inside the commit
transaction; the retry update fails. In each the offset must not move.

Existing tests this phase must keep green or change on purpose:
`PartitionedConsumerSafetyIntegrationTest.testHandlerFailure_offsetNotAdvanced` stops after
the second call and must still pass; `PartitionedConsumerEngineLifecycleTest` expects two
exact ERROR texts from `dispatchMessage`; `ConsumerGroupFaultIntegrationTest` F1 asserts the
repeated delivery of P-3 and changes; `JsonbConversionValidationTest` and the other callers
of `moveToDeadLetterQueue` gain the group argument.

### Phase 8. Cleanup, release, and final schema deletion

It replaces the cleanup and the watermark, and runs the last drops of the
[matrix](#schema-objects-by-phase): the watermark table, the counters with their trigger
and indexes, and the two unused cleanup functions.

| Test | Asserts | Red today |
|---|---|---|
| T12-1 | A manager with default settings deletes a `COMPLETED` queue row after its retention period. | X-8. Read. |
| T12-2 | A `DEAD_LETTER` queue row is deleted after `message_retention_hours` and its `dead_letter_queue` row stays (OD-20). | Q-8, X-8. Read. |
| T12-3 | Two partition keys, one finished: rows of the other key are released when every group has passed them. | P-7. Read. |
| T12-4 | A row on a topic with no subscription is kept for `zero_subscription_retention_hours`. | Records the present rule as a test. |
| T12-6 | Deleting a message deletes its exclusion rows. | Green after phase 6. Kept here as a cleanup test. |
| T12-7 | The schema holds one cleanup definition. | X-8. |
| T12-8 | The subscription health summary reports, for each group, the age of the oldest pending row that only stopped or paused groups hold. | No such value. |
| R-6b | The schema holds no counter column, no counter trigger, no watermark table, and neither unused cleanup function. | They exist. |

Dependency failure modes written first: the cleanup statement fails; the job overlaps its
own previous run; the job runs while a consumer holds a row in `PROCESSING`; the release
runs while a log replay holds the topic lock.

Existing tests that change or go here. Counters and trigger:
`FanoutProducerIntegrationTest`. Watermark: `OutboxOffsetWatermarkSchemaIsolationTest`, which
asserts the stored row. Schema contracts, the rest: the counter and cleanup-function
assertions of `SchemaContractTest` and `MigrationIntegrationTest`, and the cleanup helper of
`PeeGeeQTestSchemaInitializer`.

Finding 20 closes when one contract test class passes every outcome against the three
delivery models: an outbox group on a `QUEUE` topic, an outbox group on a `PUB_SUB` topic,
and a native group on a native queue.

## Decisions

The decisions are in the task register: [tasks.md](../tasks/tasks.md), "Finding 20
decisions". OD-1 to OD-22 are ruled, and this revision applies them. The owner ruled on
2026-10-12 that no further design ruling is required. Phase 1 raised one question after
that. The owner ruled on it in the review of increment 1a: OD-23, the start bound and the
REST settings update. The owner's review of increment 1b raised a second question: how a
generic subscription creation learns the message store of a topic. The owner ruled on it
on 2026-10-12: a typed `SubscriptionStore` that the caller names and that REST derives
from the queue factory, and `queueName` as the topic of a REST consumer group. The task
register states the ruling and the record of the correction.

## Not Checked

- No build, test, PostgreSQL, or Jenkins execution was run for any revision of this
  document. No Jenkins build has run on `f391a7f0`. Test runs made during implementation
  are recorded in the task register, not here.
- No item marked Read or Test read was reproduced at run time before implementation. That
  includes X-4, X-10, X-11, P-4, P-5, P-13, and P-14. Each has a red test in the plan. Not
  running them was a choice of scope, not a blocker. Phase 1 reproduced two: the graceful
  stop of X-10 marks a pending queue message `COMPLETED` (R-8), and a native group that
  starts again skips the messages sent while it was stopped (N-11). The task register
  names the logs.
- The lifecycle contract, the three replay contracts, the administrative migration, and
  the operator dead-letter were written from the code. None was prototyped. Whether the
  engine takes up a changed generation for a key it already owns at its next assignment
  refresh is Read, and RP-L3 tests it.
- The lock mode proposed for the administrative migration, `SHARE ROW EXCLUSIVE`, was not
  tried. A-4 tests it. The time the five table locks are held was not measured.
- That both producers insert without reading or locking `outbox_topics` is taken from the
  owner's review of the fourth revision. The producers were not read again.
- How a claim or a fetch reads the semantics and fails, in one statement or in one
  transaction, is not designed. It is designed in phase 4. Its cost was not measured.
- Javadoc, log statements below ERROR, and line text beyond the cut were not read in the
  211 test files. The files and line ranges in the "Not read, or read in part" table.
- How each production file on the two carried-over lists was read was not checked again.
- No template-provisioned schema and no hand-written test schema was inspected in
  PostgreSQL. S-1 to S-4 and S-12 come from reading the definitions. The tests on the
  hand-written schema were not rerun on the Flyway schema.
- What failed when `PartitionedOrderingDemoTest` test 2d restarted a group without setting
  the subscription back to `ACTIVE`, before phase 1. That run was not made. After
  increment 1a the test has no such statement and passes.
- What resets a row whose payload cannot be deserialized on the shared-status path (Q-10).
- Which baseline version Flyway takes on a template-provisioned schema, whether
  `RunMigrations` succeeds there, and whether the three REST test classes of S-11 pass today.
- Why `DeadConsumerDetectorComprehensiveTest` allows ranges with the comment "background job
  may detect some first". Its base class turns the detection job off.
- How many `peegeeq-db` tests change result when the hand-written schema is replaced.
- The present effect of force-remove on the subscription row was not read again for the
  lifecycle table.
- A search found 17 production files that name `PAUSED`. Which of them list the
  subscription status, and which name the queue pause or the webhook status, was not read.
  Phase 1 reads each.
- The proposed SQL was not executed. The migration matrix was not run against any schema,
  and its object list is not complete until phase 2.
- The cost of the claim's `NOT EXISTS` with an excluded backlog, of the per-partition
  release statement, of the topic-row lock shared by the sweep and the log replay, and of
  a table lock on `outbox` like the event store's was not measured.
- The two inventories are file lists from a search. Of the production files they name,
  these were read: `BackfillService`, `SubscriptionManager`, `PeeGeeQManager`,
  `OutboxFactory`.
- Retry and dead letter for clients of the caller-driven offset routes were not designed
  (rule 11).
