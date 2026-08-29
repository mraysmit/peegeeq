# PeeGeeQ Consolidated Task Register

**Status:** ACTIVE
**Last reconciled:** 2026-08-29
**Repository revision reviewed:** `32ab0371` plus the verified D23, outbox-concurrency,
diagnostics, O4, and O2 worktree phases
**Latest full release gate:** Jenkins build #36 at `e8d07e53`

This is the **only live task register** under `docs-design`. Do not derive current work from
handover notes, design proposals, unchecked boxes in archived plans, or historical narrative.
Those documents provide context only. New work must be added here before implementation begins.

## Working Rules

- Execute one numbered phase at a time and report it before starting the next phase.
- Follow TDD for behavioral changes: failing focused contract, implementation, passing contract.
- Before Java/Maven edits, follow the mandatory pre-work in the repository `AGENTS.md`.
- Rebuild the affected reactor slice before targeted testing.
- Run the smallest applicable tagged test scope and report per-class test counts.
- The approximately 90-minute `-Pall-tests` run is an explicit release gate, not the normal
  edit/test loop.
- No Mockito or substitute mocking framework.
- No blocking Future bridges, `Thread.sleep`, `LockSupport.parkNanos`, error swallowing, or
  unobserved Futures.

## Verification Baseline

Jenkins build #36 ran the complete pipeline from the beginning:

| Suite | Result |
|---|---:|
| Java | 4,022 passed; 0 failures/errors/skips |
| Management UI unit | 95/95 |
| Management UI Playwright | 481 passed plus one flaky retry; 482 total |
| Utilities unit | 836/836 |
| Utilities Playwright | 91/91 |

Later focused worktree verification, not yet represented by a newer full Jenkins gate:

- D23 SSE readiness: guard 2/2; real-backend Playwright 36/36.
- Outbox concurrency: strict TDD failed 3/3 before implementation; final scope 6/6; async
  guard 1/1.
- Diagnostics isolation: `SystemInfoCollectorTest` 8/8; async guard 1/1; clean reactor build.
- O4 duplicate metrics: strict TDD initially failed because the meter was absent; final
  real-PostgreSQL contract 1/1; async guard 1/1; clean five-module build.
- O2 options-start lifecycle: controlled subscription-failure contract 1/1; state returned to
  `NEW`, no member became active, and the failure reached the caller.
- Schema contracts in commit `32ab0371`: TC-S14 1/1, TC-S15 1/1, P3 1/1, and applicable
  asynchronous guards green.

A green gate proves that the selected implementation and tests passed. It does not prove that
an unimplemented proposal exists or replace explicitly planned load, chaos, or failover gates.

## Current Execution Order

### 1. Configuration property/runtime reconciliation

**Priority:** High
**Status:** ACTIVE
**Objective:** every shipped or publicly documented property must be classified as supported,
unsupported, or intentionally external metadata. Supported properties require a production
consumer and non-default behavioral evidence.

#### 1.1 Build the authoritative property inventory

- Enumerate every `peegeeq.*` key from shipped main-resource profiles and public configuration
  documentation.
- Map each key to its parser, production call site, precedence, and non-default test.
- Classify each as `OK`, `BROKEN WIRING`, `UNSUPPORTED`, or `EXTERNAL/TEST METADATA`.
- Do not infer support merely because a property appears in a `.properties` file.
- Update or remove shipped profiles and documentation in the same phase as each decision.

Known supported core families include database coordinates, core pool sizing/timeouts, queue
retry/visibility/batch/polling settings, recovery jobs, canonical consumer threads, core
metrics enablement, notice handling, and the canonical circuit-breaker settings.

Known profile families with no direct production consumer found in the 2026-08-29 source scan:

- `peegeeq.backpressure.*` — `BackpressureManager` was deleted because it guarded no operation.
- `peegeeq.migration.*` and `peegeeq.maintenance.*`.
- `peegeeq.performance.async.*`, `performance.batch.*`, and `performance.monitoring.*`.
- `peegeeq.bitemporal.*.enabled` feature toggles.
- Queue prefetch, concurrent-consumer, buffer, and retention keys.
- Extended metrics collection/export/detail keys.

The literal scan is discovery evidence, not proof against dynamically constructed keys. Confirm
each family against production source and behavior before removing it.

#### 1.2 Resolve the circuit-breaker property contract

The runtime currently consumes:

- `enabled`
- `failure-rate-threshold`
- `wait-duration`
- `ring-buffer-size`
- `failure-threshold`, which is actually mapped to Resilience4j `minimumNumberOfCalls`

The following advertised/profile keys are ignored:

- `minimum-number-of-calls`
- `sliding-window-size`
- `wait-duration-in-open-state`
- `slow-call-rate-threshold`
- `slow-call-duration-threshold`
- `permitted-calls-in-half-open-state`

Half-open permitted calls are hardcoded to `3`. Choose canonical names, define alias precedence
if compatibility is required, correct the misleading `failure-threshold` semantics, and add
non-default behavior contracts for every retained setting.

#### 1.3 Unify and honor health configuration

- `PeeGeeQManager` constructs `HealthCheckManager` from `peegeeq.health-check.*`.
- The parsed `peegeeq.health-check.enabled` flag is not consulted; health checks always start.
- `PgQueueConfiguration` separately exposes `peegeeq.health.enabled` and
  `peegeeq.health.check-interval`.
- Most profiles use `peegeeq.health.*`, so their interval/timeout settings do not configure the
  manager timer.
- `health.timeout`, `health.failure-threshold`, and `health.recovery-threshold` have no confirmed
  production consumer.

Select one namespace, implement or remove aliases deliberately, prove disabled startup behavior,
and add non-default interval/timeout contracts.

#### 1.4 Complete metrics property behavior

- Add a focused contract proving `peegeeq.metrics.enabled=false` prevents registry binding and
  periodic metrics collection.
- Decide whether `peegeeq.metrics.jvm.enabled` and `peegeeq.metrics.database.enabled` remain in
  the API. They are parsed into `MetricsConfig` but currently have no consumer.
- Classify/remove the extended collection, export, sampling, bitemporal, and detail keys unless
  a real monitoring consumer is identified.

#### 1.5 Close remaining small configuration gaps

- Decide whether the native expired-lock cleanup timer remains intentionally fixed at 10 seconds
  or becomes configurable. A fixed internal cadence is not automatically a bug.
- Replace the ineffective `peegeeq.queue.consumer-threads` key with the canonical
  `peegeeq.consumer.threads` in:
  - `OutboxConsumerSurgicalCoverageTest`
  - `peegeeq-bitemporal/src/test/resources/peegeeq-development.properties`
- Confirm per-consumer precedence remains: consumer config > global configuration > local default.
- Outbox uses canonical `peegeeq.queue.*` settings; do not invent a separate
  `peegeeq.outbox.*` namespace.
- Outbox has no native-style visibility lock. Stale PROCESSING recovery is governed separately by
  `peegeeq.queue.recovery.processing-timeout`.

#### Configuration completion criteria

- Every shipped key has a recorded classification and no ambiguous `Verify`/`Unknown` status.
- Shipped profiles and public documentation contain no silently ignored setting presented as live.
- Every retained behavioral setting has at least one non-default contract that would fail if the
  value were ignored.
- Each implementation phase has its required clean reactor rebuild and focused tagged tests.

### 2. Remove the final Tier-5 blocking calls

**Priority:** High
**Status:** ACTIVE — reopened after the no-exceptions rule superseded `blocking-exempt` policy

Five executable banned calls remain:

| File | Remaining calls | Required outcome |
|---|---:|---|
| `CircuitBreakerRecoveryTest` | 3 × `LockSupport.parkNanos` | Replace elapsed-time waiting with an observable, non-blocking state-transition test seam/pattern |
| `VertxEventLoopBlockingJoinTest` | 2 × `Thread.sleep` | Preserve the event-loop-blocking diagnostic without using a prohibited sleep |

Required phase order:

1. Remediate `CircuitBreakerRecoveryTest` and run its smallest applicable scope.
2. Remediate `VertxEventLoopBlockingJoinTest` and run its smallest applicable scope.
3. Remove obsolete exemption comments/tags and Tier-5 baseline entries.
4. Run the no-blocking-pattern guard and confirm zero executable
   `Thread.sleep|LockSupport.parkNanos` occurrences.

Tier 4 and Tier 7 remain complete. Do not reopen them unless a current scan finds a real violation.

### 3. PostgreSQL/HAProxy resilience gaps

**Priority:** Medium
**Status:** OPEN
**Current evidence:** the existing `HaProxyConnectionFailoverTest` proves connection-level
failover between independent PostgreSQL instances. It does not prove the four capabilities below.

#### 3.1 Route LISTEN/NOTIFY through the proxy

- Define proxy host/port configuration whose unset default preserves direct PostgreSQL behavior.
- Apply the proxy address consistently to pool connections and long-lived
  `ReactiveNotificationHandler` connections.
- Extend failover coverage: subscribe, stop primary, switch proxy, issue NOTIFY on secondary,
  and prove delivery resumes.

#### 3.2 Add circuit breaking to pool operations

- Decide and document whether circuit breaking belongs around pool acquisition and transaction
  paths.
- If retained, use a per-pool breaker, check before acquisition, and record terminal
  success/failure without swallowing the original error.
- Prove threshold opening, structured open-circuit failure, and recovery to HALF_OPEN/CLOSED using
  real PostgreSQL behavior.
- Reconcile this work with Task 1.2 before implementation; do not build on ambiguous/dead
  circuit-breaker properties.

#### 3.3 Add a real streaming-replication failover test

- Keep the faster independent-node connection test.
- Add primary/standby replication, write a known row, stop/promote, reconnect through HAProxy,
  and prove the row exists after failover.
- Treat promotion/fencing as an operations decision; automatic promotion without fencing risks
  split brain.

#### 3.4 Validate PgBouncer transaction mode

- Run PgBouncer in transaction mode with an explicit reset strategy.
- Send/consume across multiple transactions and prove tenant `search_path` isolation survives
  multiplexed server connections.
- Keep this test independent of the HAProxy production-code phases.

### 4. Durable subscriptions runtime

**Priority:** Medium
**Status:** PARTIAL — schema only

Remaining implementation:

1. Define the public durable-subscription API and service lifecycle.
2. Persist and advance the replay cursor transactionally.
3. Implement bounded historical catch-up.
4. Implement a lossless catch-up-to-live handoff.
5. Add lease/ownership and recovery semantics for competing instances.
6. Add real-PostgreSQL contracts for restart, replay, concurrent ownership, failure recovery,
   schema isolation, and catch-up/live ordering.

The design reference is
`docs-design/event-sourcing-messaging/PEEGEEQ_DURABLE_SUBSCRIPTIONS_OPTION_PLAN.md`; status and
execution order are controlled here.

### 5. Transactional REST API product decision

**Priority:** Product decision
**Status:** PROPOSED

No domain-specific transactional REST endpoints currently exist. Decide whether PeeGeeQ should
expose them. If approved, add a bounded implementation plan here covering transaction ownership,
idempotency, authentication/authorization, failure semantics, and real-backend tests. Until that
decision, the proposal is not an implementation task.

Design reference:
`docs-design/transactional-rest-api/PEEGEEQ_TRANSACTIONAL_REST_API_DESIGN.md`.

### 6. Partitioned consumption pre-GA gates

**Priority:** Release gate
**Status:** OPEN

Before GA, run and record:

- long-duration fanout/partition stability;
- consumer death, lease expiry, and rebalance chaos;
- OLTP contention and pool-pressure behavior;
- schema/tenant isolation under concurrent partition activity;
- partition creation, assignment, recovery, and cleanup validation.

These are explicit owner/release runs, not automatic requirements after every code phase.

## Unscheduled Product and Coverage Backlog

| Item | Current verified state | Next decision/work |
|---|---|---|
| Schema Registry | Proposed; UI remains a “coming soon” placeholder; no backend exists | Approve product scope before implementation |
| Authentication and Authorization | Proposed; no auth module, JWT middleware, or tenant-management implementation exists | Define threat model and product boundary |
| TypeScript REST client coverage | Shared client is used by two UI pages | Add dedicated unit tests and live-server integration tests |

## Completed Work

| Work item | Completion evidence |
|---|---|
| Consumer Groups UI Redesign | REST/UI lifecycle implemented; management UI suites passed in build #36 |
| Management UI tests not running | npm permissions and Playwright browser setup fixed; build #36 ran the UI suites |
| Test Integrity D1–D23 | D23 guard 2/2 and real-backend Playwright 36/36; previous phases recorded in archive |
| Outbox DLQ/filter/dead-code remediation | Wrong-architecture filter retry/DLQ layer removed; regression coverage added; Steps 1–7 complete |
| Outbox module audit O1–O4 | Consumer concurrency, lifecycle propagation, validation, and duplicate metrics fixed; final O2 contract 1/1 |
| Outbox schema qualification | TC-S1–TC-S15 implemented; commit `32ab0371` records TC-S14 1/1 and TC-S15 1/1 |
| Schema processing remediation | Core remediation and P1–P3 complete; P3 1/1; P4 deliberately declined as test-only API exposure |
| Bitemporal examples expansion | Referenced examples exist and examples modules passed the full gate |
| Monitoring endpoints | WebSocket/SSE, lifecycle, CORS, and metrics work complete |
| Messaging pattern examples | Ten scenarios implemented; example modules passed the full gate |

## Archived Supporting Records

The following files are historical evidence only. Their unchecked boxes, “next steps,” and
old status blocks are superseded by this register.

| Archived record | Consolidated here |
|---|---|
| [Configuration property wiring audit](archive/CONFIG-PROPERTY-WIRING-AUDIT.md) | Task 1 |
| [Tier 4/5/7 remediation plan](archive/TIER5-BLOCKING-THREAD-VIOLATIONS-PLAN.md) | Task 2 |
| [PostgreSQL/HAProxy gap plan](archive/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_GAPS.md) | Task 3 |
| [Consumer Groups UI redesign](archive/CONSUMER-GROUPS-UI-REDESIGN-PLAN.md) | Completed Work |
| [Management UI tests not running](archive/management-ui-tests-not-running.md) | Completed Work |
| [Test integrity remediation](archive/TEST-INTEGRITY-DEFECT-REMEDIATION-PLAN.md) | Completed Work |
| [Outbox DLQ/filter audit](archive/OUTBOX-DLQ-FILTER-ERRORS-DEAD-CODE-AUDIT.md) | Completed Work |
| [Outbox module audit](archive/OUTBOX-AUDIT-FINDINGS-11-Jun-2026.md) | Completed Work |
| [Outbox schema qualification](archive/OUTBOX-SCHEMA-QUALIFICATION-REGRESSION.md) | Completed Work |
| [Schema processing gaps](archive/SCHEMA-PROCESSING-GAPS-CRITICAL-17-Jun-2026.md) | Completed Work |
| [Bitemporal examples walkthrough](archive/bitemporal-examples-expansion-walkthrough.md) | Completed Work |
| [Session handover 2026-08-12](archive/SESSION-HANDOVER-20260812.md) | Verification baseline and completed records |

## Status Definitions

- **OPEN** — approved work has not started.
- **ACTIVE** — implementation or investigation is in progress.
- **PARTIAL** — verified deliverables exist and the exact remainder is listed here.
- **COMPLETE** — implementation and proportionate verification are recorded.
- **PROPOSED** — requires a product decision before becoming implementation work.
- **RELEASE GATE** — an explicit owner/CI validation run, not a normal edit/test phase.

When a task changes status, update this file in the same phase. Do not create another task plan.
