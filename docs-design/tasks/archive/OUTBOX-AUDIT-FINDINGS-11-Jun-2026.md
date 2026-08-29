> **ARCHIVED 2026-08-29:** Historical evidence only. Current tasks and status are maintained exclusively in [the consolidated register](../tasks.md).

# peegeeq-outbox — Module Audit Findings

## Status: COMPLETE — reconciled 29 Aug 2026

Current status: O1 through O4 are fixed. The detailed findings below retain their original
evidence; each heading now carries the reconciled state.

Audit of `peegeeq-outbox/src/main` for the defect classes uncovered the same day in the event-query
path (see `peegeeq-management-ui/docs/archive/PEEGEEQ_MANAGEMENT_UI_BACKEND_TASKS-06-11-2026.md` and
the F-tasks in `peegeeq-management-ui/docs/tasks/AGGREGATE-STREAM-IMPROVEMENTS-PLAN-7-Jun-2026.md`):
declared-but-ignored inputs, API contract violations, exception string-matching, silent truncation,
error erasure, and test-aware production code. Every finding below was verified against the code by a
second pass — two of the initial audit's claims did not survive verification and are recorded under
"Refuted" so they are not re-reported later.

Related, pre-existing documents (no overlap with the findings here):
- `OUTBOX-DLQ-FILTER-ERRORS-DEAD-CODE-AUDIT.md` — `resetFilteredMessageToPending()` retry_count gap
- `docs-design/testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md` — error-erasure taxonomy

---

## O1 — HIGH — FIXED: `OutboxConsumerConfig.consumerThreads` bounded concurrency contract

The option was retained and implemented on 29 Aug 2026. `OutboxConsumer` now reserves capacity
atomically before each claim, uses that reservation as the SQL limit, releases unused capacity,
and retains each claimed slot until the handler and terminal persistence settle. Claim queries are
serialized and ordered by creation time then ID. Within one consumer instance, rows sharing a
non-null `message_group` are chained in publication order; different groups and ungrouped rows may
execute concurrently up to the effective consumer-thread limit. Per-consumer configuration takes
precedence over the queue-wide setting.

Strict TDD evidence: the unchanged runtime failed all three real-PostgreSQL contracts (8 rows
claimed with configured limits 1 and 3, plus same-group overlap). The final focused scope passed
6/6: `OutboxConsumerConcurrencyIT` 3/3 and `OutboxParallelProcessingTest` 3/3. The mandatory clean
five-module reactor rebuild and `VertxAsyncForbiddenPatternsGuardTest` 1/1 also passed; final logs
contained zero ERROR or unhandled-exception lines.

Original finding:

- **Evidence**: the field is built, validated, stored, and exposed
  (`OutboxConsumerConfig.java` lines ~32, 40, 48), and **nothing in peegeeq-outbox ever calls
  `getConsumerThreads()`** — the only reference in the module is the getter itself.
- **Contrast**: peegeeq-native consumes the equivalent setting properly
  (`PgNativeQueueConsumer.java` lines ~403–406 use `consumerConfig.getConsumerThreads()` /
  `configuration.getQueueConfig().getConsumerThreads()` to size concurrency).
- **Impact**: a user configuring outbox consumer threads gets silently nothing — the documented
  API accepts the value and ignores it.
- **Fix options (decide before implementing)**: (a) wire it to actual processing concurrency the way
  peegeeq-native does, or (b) remove the field and fail at build time for anyone setting it. Option
  (a) only if outbox consumers are meant to support concurrent processing; do not keep a dead knob.
- **TDD**: RED test asserting configured thread count affects observable concurrency (or, for
  option b, that the field no longer exists).

## O2 — HIGH — FIXED: `OutboxConsumerGroup.startInternal()` reports ACTIVE before/despite subscription

The options-start path was already corrected in commit `99166fb9` even though that commit's
subject only described timeout-property refactoring. `start(SubscriptionOptions)` composes the
database subscription with `startInternal(true)`, and `startInternal(boolean)` returns and
composes the underlying consumer's subscription Future. It changes `STARTING` to `ACTIVE` and
starts members only after subscription success. On failure it restores `NEW`, stops any members
that were activated during a startup race, closes the failed startup consumer, and preserves the
original failure on the public start Future.

Focused current-worktree verification on 29 Aug 2026 ran the real-PostgreSQL contract
`OutboxConsumerGroupCoreTest$LifecycleStateMachine#startWithOptionsPropagatesSubscriptionFailure`.
It passed 1/1 and proved that a controlled subscription failure reaches the caller, restores
`NEW`, leaves the group inactive, and leaves the member inactive. Evidence:
`logs/o2-options-start-lifecycle-targeted-20260829.txt`.

Original finding:

- **Evidence**: `OutboxConsumerGroup.java` lines ~528–532:
  ```java
  underlyingConsumer.subscribe(this::distributeMessage)
          .onFailure(err -> logger.error("Failed to subscribe consumer group '{}' ...", ...));
  members.values().forEach(OutboxConsumerGroupMember::start);
  state.set(State.ACTIVE);
  ```
  The `Future` from `subscribe()` is discarded (log-only failure handler); members start and the
  state is set `ACTIVE` unconditionally and synchronously.
- **Impact**: if subscription fails, the group reports ACTIVE forever while receiving no messages —
  the same lifecycle-erasure family as the no-op close hook documented in the antipatterns case
  study. Callers of `start()` cannot observe the failure.
- **Fix**: compose on the subscribe future — members start and state transitions to ACTIVE only in
  `onSuccess`; failure transitions to a failed/stopped state and propagates out of the public
  `start()` future.
- **TDD**: RED test with a subscription forced to fail asserting the group does NOT become ACTIVE
  and `start()` fails.

## O3 — MEDIUM — FIXED: status-update failures swallowed in `markMessageCompleted` / `resetFilteredMessageToPending`

Current implementation returns and composes the status-update Futures. Failures are logged
without being converted to success, so they propagate to the caller.

- **Original evidence (now fixed)**: both paths (`OutboxConsumer.java` ~lines 590–602 and ~627–636) ended in
  `.onFailure(log).mapEmpty()` — the future returned to the processing pipeline succeeds even when
  the status UPDATE failed. The completion path at least logs `CRITICAL` and records a
  `COMPLETION_FAILURE` metric; the filtered-reset path only WARNs.
- **Important nuance (correcting the initial audit)**: messages stuck in PROCESSING are NOT lost —
  the `StuckMessageRecoveryManager` janitor reclaims them and at-least-once semantics make
  redelivery legitimate. The defect is that the pipeline silently depends on the janitor: a failed
  UPDATE looks like success locally, and the redelivery it causes is unattributable.
- **Implemented fix**: propagate the status-update failure through the returned future (the poll-loop caller
  already routes failures to its error path), keeping the metric. Decide explicitly whether the
  completion path's at-least-once contract means "propagate and let the poll cycle log it" or
  "keep swallowing but escalate via consecutive-failure counting" — pick one and document it.
- **TDD**: RED test forcing the UPDATE to fail (e.g. closed pool) asserting the processing future
  fails rather than succeeding.

## O4 — LOW — FIXED: duplicate idempotent sends are observable in metrics

Implemented on 29 Aug 2026. `MetricsProvider` now exposes `recordMessageDuplicate(topic)`,
`PgMetricsProvider` delegates it to `PeeGeeQMetrics`, and the no-op provider preserves the
disabled-metrics contract. `PeeGeeQMetrics` publishes both `peegeeq.messages.duplicates` and
the topic-tagged `peegeeq.messages.duplicates.by.topic`. `OutboxProducer.logSendOutcome()`
records the duplicate only when a non-null idempotency key produces `rowCount=0`; the existing
sent counter remains limited to inserts.

Strict TDD evidence: the real-PostgreSQL contract first completed both sends and errored because
`peegeeq.messages.duplicates` did not exist. After implementation,
`OutboxDuplicateMetricsIntegrationTest` passed 1/1, proving one inserted-send count, one global
duplicate count, and one topic-tagged duplicate count. The mandatory clean five-module reactor
rebuild and `VertxAsyncForbiddenPatternsGuardTest` 1/1 also passed; final logs contained zero
ERROR or unhandled-exception lines.

Original finding:

- **Evidence**: `OutboxProducer.logSendOutcome()` (~lines 487–495): when an idempotency-keyed send
  hits `ON CONFLICT DO NOTHING` (rowCount=0), only a debug log records it. `recordMessageSent` is
  correctly NOT incremented (nothing was inserted) — but no duplicate counter exists either.
- **Impact**: operators cannot distinguish "no traffic" from "all traffic was duplicates"; retry
  storms hitting idempotency keys are invisible in metrics.
- **Fix**: add a `recordMessageDuplicate(topic)`-style metric in the rowCount=0 branch, following
  the existing `PeeGeeQMetrics` method patterns.
- **TDD**: unit test asserting the duplicate counter increments on the second send with the same
  idempotency key.

---

## Refuted during verification (do not re-report)

1. **"`markMessageFailed` is fire-and-forget in `processRow`"** — false. `OutboxConsumer.java`
   ~line 449 *returns* the `markMessageFailed(...)` future from inside `.transform()`, so its
   failure propagates to the polling loop. (`.eventually()` afterwards preserves failures.)
2. **"Duplicate sends should increment `recordMessageSent`"** — wrong framing; a duplicate was not
   sent. Reframed as the missing duplicate counter (O4).

## Verified CLEAN (audit negatives, for trust in scope)

- All `OutboxProducer.send()` overloads propagate payload/headers/correlationId/messageGroup to the
  SQL INSERT across all three transaction variants.
- Consumer config precedence (`OutboxConsumerConfig` > `PeeGeeQConfiguration` > default) is honored
  for batch size, polling interval, and max retries — the values are genuinely used.
- `FilterErrorHandlingConfig` thresholds are all consumed by `FilterCircuitBreaker`.
- Server-side filter SQL merging binds parameters in the correct order.
- Idempotency key normalization and `ON CONFLICT` handling are sound.
- No exception string-matching for control flow; no test-aware production code (the
  `FilterCircuitBreaker.isLikelyTest` branch was removed 11 Jun 2026).

## Summary

| # | Finding | Severity | Status |
|---|---------|----------|--------|
| O1 | `consumerThreads` config accepted and ignored | HIGH | Fixed |
| O2 | Consumer group ACTIVE before/despite subscription | HIGH | Fixed |
| O3 | Status-update failures swallowed (janitor-dependent) | MEDIUM | Fixed |
| O4 | No duplicate-send metric for idempotent conflicts | LOW | Fixed |
