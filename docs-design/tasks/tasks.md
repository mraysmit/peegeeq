# PeeGeeQ Consolidated Task Register

**Status:** ACTIVE
**Last reconciled:** 2026-09-02
**Repository revision reviewed:** `6d652f7f` plus the completed Tier-5 Task 2 worktree remediation
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
- UI semantic coverage in commit `322b7f06`: Management UI Vitest 128/128 across 14 files
  and Playwright 419/419; Utilities UI Vitest 836/836 across 55 files and Playwright 246/246.
  Inventory guards found 491 unique Management UI tests (419 functional + 72 screenshots)
  and 817 unique Utilities UI tests (246 functional + 571 screenshots), with type checks,
  builds, and lint green. The 2026-09-02 remediation wires both inventory guards into
  `test:all`, the command invoked by the Maven `all-tests` profiles and Jenkins full gate.
- Tier-5 Task 2: strict TDD exposed the missing circuit-breaker clock seam and both remaining
  event-loop test sleeps. The final 11-module reactor slice built cleanly;
  `CircuitBreakerRecoveryTest` passed 2/2, `VertxEventLoopBlockingJoinTest` passed 2/2, and
  `OnSuccessExceptionSwallowingGuardTest` passed all 8 checks with no blocking exemptions.
- HAProxy Task 3.1: strict TDD first failed the proxy endpoint contracts and then exposed a
  one-attempt-only LISTEN reconnect defect. The final six-module reactor slice built cleanly;
  configuration suites passed 31/31, notification failure paths passed 8/8, the real two-node
  HAProxy LISTEN/pool failover contract passed 1/1, and the workspace guard passed 8/8.
- Pool circuit breaking Task 3.2: strict TDD first proved two real connection failures left the
  breaker uncreated (`UNKNOWN`). The final four-module reactor slice built cleanly; the dedicated
  PostgreSQL/HAProxy outage and replacement-node contract passed 1/1, `CircuitBreakerManagerTest`
  passed 10/10, and the connection-manager, client-factory, and manager regression classes passed
  73/73.
- Streaming-replication failover Task 3.3: strict TDD first failed 1/1 with PostgreSQL `42P01`
  because the independent secondary did not contain the primary's durable marker. The final real
  `pg_basebackup` primary/standby contract passed 1/1 after explicit primary fencing and standby
  promotion, and the unchanged independent-node HAProxy regression passed 6/6.

A green gate proves that the selected implementation and tests passed. It does not prove that
an unimplemented proposal exists or replace explicitly planned load, chaos, or failover gates.

## Current Execution Order

### 1. Configuration property/runtime reconciliation

**Priority:** High
**Status:** COMPLETE — 2026-08-29
**Objective:** every shipped or publicly documented property must be classified as supported,
unsupported, or intentionally external metadata. Supported properties require a production
consumer and non-default behavioral evidence.

#### 1.1 Build the authoritative property inventory — COMPLETE

- `ShippedConfigurationPropertyContractTest` is the executable whitelist for all 11 bundled
  profiles and fails if an unowned or compatibility-only key is shipped.
- `docs/PEEGEEQ_CONFIGURATION_GUIDE.md` records every retained key's classification, parser or
  explicit external owner, production use, precedence, and behavioral evidence.
- All unsupported profile-only families were removed, including backpressure, migration,
  maintenance, generic performance toggles, bitemporal feature toggles, queue prefetch/buffer/
  retention keys, extended metrics flags, and parser-only settings presented as live controls.
- Public property snippets in the complete guide were canonicalized against the same inventory.

#### 1.2 Resolve the circuit-breaker property contract — COMPLETE

- Canonical names now map all eight settings to Resilience4j, including slow-call thresholds and
  configurable half-open calls.
- Historical `failure-threshold`, `wait-duration`, and `ring-buffer-size` spellings are
  compatibility aliases. Merge-source precedence applies first; canonical wins within one source.
- Bundled profiles use canonical spellings only. Configuration and manager tests cover
  non-default values and precedence.

#### 1.3 Unify and honor health configuration — COMPLETE

- `peegeeq.health.*` is canonical for manager and API adapter enablement, queue checks, interval,
  and timeout. The four `health-check.*` spellings are lower-layer compatibility aliases.
- Disabled startup is proven against real PostgreSQL; non-default interval/timeout and canonical
  precedence are covered by focused configuration tests.
- Unsupported failure/recovery threshold keys were removed from profiles and active docs.

#### 1.4 Complete metrics property behavior — COMPLETE

- A real-PostgreSQL contract proves `metrics.enabled=false` prevents core, notice, and
  Resilience4j registry binding and prevents all periodic metrics samplers.
- Ineffective JVM/database flags were removed from `MetricsConfig` and all bundled profiles.
- Extended collection/export/sampling/detail keys and the parser-only reporting interval were
  classified unsupported and removed from shipped/public active configuration.

#### 1.5 Close remaining small configuration gaps — COMPLETE

- Native expired-lock cleanup intentionally remains a fixed internal 10-second housekeeping
  cadence; it is not exposed as a workload property.
- Both stale consumer-thread keys now use `peegeeq.consumer.threads`; the outbox test asserts the
  non-default parsed value and passes against real PostgreSQL.
- Source review and existing real-database contracts confirm per-consumer > global > local-default
  precedence for native and outbox consumers.
- Public docs state that outbox uses canonical queue settings, has no `peegeeq.outbox.*` property
  namespace or native visibility lock, and uses recovery processing timeout for stale work.

#### Configuration completion criteria — MET

- Every shipped key has a recorded classification and no ambiguous `Verify`/`Unknown` status.
- Shipped profiles and public documentation contain no silently ignored setting presented as live.
- Every retained behavioral setting has at least one non-default contract that would fail if the
  value were ignored.
- Each implementation phase has its required clean reactor rebuild and focused tagged tests.

### 2. Remove the final Tier-5 blocking calls

**Priority:** High
**Status:** COMPLETE — 2026-09-02

All executable `Thread.sleep` and `LockSupport.parkNanos` calls covered by the workspace test
guard have been removed. The `blocking-exempt` policy and empty Tier-5 baseline were deleted;
the guard now enforces zero tolerance for both blocking calls and exemption annotations.

Required phase order:

1. **COMPLETE — 2026-09-02.** `CircuitBreakerRecoveryTest` now advances an injected mutable
   clock across the reset timeout without blocking. The obsolete `blocking-exempt` tag and all
   three `LockSupport.parkNanos` calls are removed; focused recovery tests passed 2/2 and the
   Tier-5 guard passed 1/1 after a clean reactor build.
2. **COMPLETE — 2026-09-02.** `VertxEventLoopBlockingJoinTest` now proves event-loop queueing
   and worker/event-loop progress through ordered callbacks and a worker-thread phaser, without
   timing thresholds or sleeps. Its focused scope passed 2/2 after a clean reactor build.
3. **COMPLETE — 2026-09-02.** Removed the obsolete exemption tag and comments, deleted the
   intentionally unsafe `VertxAsyncTestPitfallsDemo` executable fixture, deleted the empty
   Tier-5 sleep baseline, documented the no-opt-out policy, and regenerated the tag inventory.
4. **COMPLETE — 2026-09-02.** The workspace guard passed 8/8, including zero-tolerance checks
   for blocking delays and exemption annotations. Direct source and generated-inventory scans
   found zero live `@Tag("blocking-exempt")` annotations.

Tier 4 and Tier 7 remain complete. Do not reopen them unless a current scan finds a real violation.

### 3. PostgreSQL/HAProxy resilience gaps

**Priority:** Medium
**Status:** PARTIAL — 3.1 through 3.3 complete; 3.4 is next
**Current evidence:** `HaProxyConnectionFailoverTest` proves pool connection failover between
independent PostgreSQL instances. `HaProxyNotificationFailoverIntegrationTest` now proves the
configured endpoint is shared by pooled and dedicated LISTEN connections, and that both recover
through HAProxy after primary loss. `PgPoolCircuitBreakerIntegrationTest` proves per-pool breaker
opening, structured rejection, isolation, and recovery against a stopped and replacement
PostgreSQL node through a fixed HAProxy endpoint. `HaProxyStreamingReplicationFailoverTest`
proves committed-data continuity and post-promotion writes through the same HAProxy endpoint.
Task 3.4 remains open.

#### 3.1 Route LISTEN/NOTIFY through the proxy — COMPLETE 2026-09-02

- Added canonical `peegeeq.database.proxy.host` and `.port` settings. Blank values independently
  fall back to the direct database host and port; nonblank proxy ports are range validated.
- `PeeGeeQConfiguration` resolves one effective `PgConnectionConfig`, so the default pool and
  `PgDatabaseService`-provided `ReactiveNotificationHandler` options use the same endpoint.
- The real HAProxy contract subscribes, stops the primary, issues `NOTIFY` directly on the
  secondary, proves LISTEN replay and delivery resume, and verifies a post-failover pooled query.
- The failover red phase exposed that a failed reconnect was never rescheduled. The handler now
  executes all bounded exponential-backoff attempts, treats intermediate failures as transient,
  and observes connection-close Futures; the deterministic retry regression passed 1/1.

#### 3.2 Add circuit breaking to pool operations — COMPLETE 2026-09-02

- Circuit breaking is retained around the complete terminal scope of `withConnection` and
  `withTransaction`: permission is acquired before the pool call, and both acquisition failures
  and caller-operation failures are recorded. The original failure Future is returned unchanged.
- Each logical pool uses `db.pool.<serviceId>`, so one failed pool does not block another.
  `getReactiveConnection` remains deliberately unwrapped for callers that explicitly own a
  connection and for independent recovery probes.
- `PeeGeeQManager` constructs one `CircuitBreakerManager` before the client factory and shares it
  with pool operations and health checks. Standalone connection managers/factories retain
  disabled behavior unless a breaker manager is explicitly supplied.
- The implementation consumes the canonical Task 1.2 `CircuitBreakerConfig`; it introduces no
  property names or compatibility paths.
- Strict TDD first failed 1/1 because no `db.pool.peegeeq-main` breaker existed. The final real
  PostgreSQL contract stops the active node behind HAProxy, opens after two terminal failures,
  verifies `CallNotPermittedException`, proves another pool remains usable, starts a replacement
  node, and verifies HALF_OPEN then CLOSED. It also proves transaction metrics and original
  `PgException` propagation.
- Verification: clean `mvn clean install -DskipTests -pl :peegeeq-db -am`; circuit-breaker core
  10/10; real outage/recovery 1/1; `PgConnectionManagerCoreTest` 9/9;
  `PgClientFactoryCoreTest` 36/36; `PgClientFactoryTest` 11/11; and
  `PeeGeeQManagerIntegrationTest` 17/17.

#### 3.3 Add a real streaming-replication failover test — COMPLETE 2026-09-02

- The existing independent-node connection test remains unchanged and passed 6/6.
- A real physical standby is created from the primary with `pg_basebackup -R`; the contract waits
  until a committed marker is queryable on the standby before inducing failure.
- The contract fences the primary, explicitly promotes the standby, reconnects through HAProxy,
  proves the pre-failure marker survives, and proves the promoted standby accepts a new write.
- Promotion and fencing remain explicit operations decisions. The fixture does not implement
  automatic promotion, which could create split brain without an external fencing authority.
- Strict TDD RED: the same durable-marker contract against independent nodes failed 1/1 with
  `42P01` because the failover node did not contain the table. GREEN: the streaming-replication
  contract passed 1/1 after a clean four-module reactor build.

#### 3.4 Validate PgBouncer transaction mode — NEXT

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
| UI semantic Playwright and inventory remediation | Commit `322b7f06` closed the reviewed semantic coverage gaps; 1,308 unique Playwright tests inventoried across both UIs; inventory guards wired into the Maven/Jenkins `test:all` path on 2026-09-02 |
| Tier-5 blocking-call remediation | Task 2 complete: deterministic clock and event-loop contracts replaced five blocking calls; exemptions and the sleep baseline removed; workspace async guard 8/8 |
| HAProxy LISTEN/NOTIFY routing | Task 3.1 complete: canonical optional proxy endpoint shared by pool and listener, bounded reconnect retries repaired, and real HAProxy failover contract green 1/1 |
| Pool-operation circuit breaking | Task 3.2 complete: canonical per-pool breakers guard connection/transaction Futures, preserve original failures, and recover through a real HAProxy/PostgreSQL outage contract 1/1 |
| Streaming-replication failover | Task 3.3 complete: real physical standby retained a committed marker across explicit primary fencing/promotion and accepted a post-promotion write through HAProxy; green 1/1 |

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
