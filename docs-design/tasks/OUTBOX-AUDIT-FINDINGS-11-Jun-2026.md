# peegeeq-outbox — Module Audit Findings

## Status: OPEN — found 11 Jun 2026, NOT yet implemented

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

## O1 — HIGH: `OutboxConsumerConfig.consumerThreads` is dead configuration

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

## O2 — HIGH: `OutboxConsumerGroup.startInternal()` reports ACTIVE before/despite subscription

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

## O3 — MEDIUM: status-update failures swallowed in `markMessageCompleted` / `resetFilteredMessageToPending`

- **Evidence**: both paths (`OutboxConsumer.java` ~lines 590–602 and ~627–636) end in
  `.onFailure(log).mapEmpty()` — the future returned to the processing pipeline succeeds even when
  the status UPDATE failed. The completion path at least logs `CRITICAL` and records a
  `COMPLETION_FAILURE` metric; the filtered-reset path only WARNs.
- **Important nuance (correcting the initial audit)**: messages stuck in PROCESSING are NOT lost —
  the `StuckMessageRecoveryManager` janitor reclaims them and at-least-once semantics make
  redelivery legitimate. The defect is that the pipeline silently depends on the janitor: a failed
  UPDATE looks like success locally, and the redelivery it causes is unattributable.
- **Fix**: propagate the status-update failure through the returned future (the poll-loop caller
  already routes failures to its error path), keeping the metric. Decide explicitly whether the
  completion path's at-least-once contract means "propagate and let the poll cycle log it" or
  "keep swallowing but escalate via consecutive-failure counting" — pick one and document it.
- **TDD**: RED test forcing the UPDATE to fail (e.g. closed pool) asserting the processing future
  fails rather than succeeding.

## O4 — LOW: duplicate idempotent sends are not observable in metrics

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
| O1 | `consumerThreads` config accepted and ignored | HIGH | Open |
| O2 | Consumer group ACTIVE before/despite subscription | HIGH | Open |
| O3 | Status-update failures swallowed (janitor-dependent) | MEDIUM | Open |
| O4 | No duplicate-send metric for idempotent conflicts | LOW | Open |
