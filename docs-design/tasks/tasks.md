# PeeGeeQ Consolidated Task Register

**Status:** ACTIVE
**Last reconciled:** 2026-09-17
**Repository revision reviewed:** `263309d8` (`fix(docs): update reconciliation dates and status for transactional REST API designs`)
**Recorded from-beginning release baseline:** Jenkins build #36 at `e8d07e53`
**Latest successful release gate:** Jenkins build #11 at `263309d8` plus the checksummed
Task 6 release-gate overlay (`485c930dd264864cd9157fe3378e25e661c51b97afa2120d1cc5bde6c0b54f27`)
**Git reconciliation:** 2026-10-21 at `092cf23f` — commit history since 2026-10-08 and the
`peegeeq-pg-failover` sources were checked. No test was run and no Jenkins result was
re-verified.
**Partial reconciliation:** 2026-10-06 at `9e14170e` — structure, references, numbering, and the
backlog were checked against the repository. Jenkins results and recorded test counts were not
re-verified
**Task 8 reconciliation:** 2026-10-08 — implementation source and saved final build/test
summaries checked. Phase 7b.1 complete; phase 7b.2 protocol slice implemented and tested.
Optional-watchdog decision corrected. No tests rerun or Jenkins gate re-verified.
**Task 8 update:** 2026-10-09 — `peegeeq-pg-failover` rebuilt and its core and integration tests
rerun on a second development machine, with the guard tests. Local admission compiled and
tested. A Consul fixture readiness defect and the module's failure log levels corrected.
`mvn clean test -Pall-tests` was run once: it stopped at `peegeeq-db` on the open phase 5 test
(1,124 run, 1 failure), and the 17 modules after it passed when the run was resumed. The 318
`warn` calls of the other modules were classified (backlog). No Jenkins gate re-verified.

**Commit references predate a history rewrite.** The abbreviated hashes cited in this register
(`263309d8`, `e8d07e53`, `b19b708b`, `fe676bda`, `c62af5c3`, `7db748b8`, `32ab0371`, `322b7f06`,
`1dd6741b`, `19e3cbdb`) do not resolve in the repository as of `9e14170e`. The commit carrying
the subject recorded for `263309d8` is now `227d12d7`. The other nine have no recorded subject
and are not mapped. They remain as identifiers for the Jenkins builds that ran them.

This is the **only live task register** under `docs-design`. Do not derive current work from
handover notes, design proposals, unchecked boxes in archived plans, or historical narrative.
Those documents provide context only. New work must be added here before implementation begins.

A separate implementation plan for a specific feature or review is permitted and is required
where the work needs one. Each such plan belongs to one numbered task in this register, and that
task links to it. The plan holds the work list and the detail. This register holds the status,
the execution order, and the recorded test evidence for every plan.

## Working Rules

- Execute one numbered phase at a time and report it before starting the next phase.
- Follow TDD for behavioral changes: failing focused contract, implementation, passing contract.
- Before Java/Maven edits, follow the mandatory pre-work in `.claude/CLAUDE.md`. The root
  `AGENTS.md` carries the same text for tools that read that filename.
- Rebuild the affected reactor slice before targeted testing.
- Run the smallest applicable tagged test scope and report per-class test counts.
- The approximately 90-minute `-Pall-tests` run is an explicit release gate, not the normal
  edit/test loop.
- No Mockito or substitute mocking framework.
- No blocking Future bridges, fixed-duration thread delays, error swallowing, or
  unobserved Futures.

## Verification Baseline

[Jenkins build #36](http://192.168.137.11:8080/job/PeeGeeQ/36/) ran the complete pipeline
from the beginning at `e8d07e53`. These are historical baseline counts, not counts for the
current revision:

| Suite | Result |
|---|---:|
| Java | 4,022 passed; 0 failures/errors/skips |
| Management UI unit | 95/95 |
| Management UI Playwright | 481 passed plus one flaky retry; 482 total |
| Utilities unit | 836/836 |
| Utilities Playwright | 91/91 |

### Resumed Jenkins verification — 2026-09-05 reconciliation

The remediation sequence reached **SUCCESS** in
[build #46](http://192.168.137.11:8080/job/PeeGeeQ/46/) at `b19b708b`.
Its parameters were `TEST_SUITE=all` and `ALL_TESTS_START_MODULE=peegeeq-management-ui`.
The pipeline first completed its clean install with tests skipped, then ran
`clean test -Pall-tests -rf :peegeeq-management-ui`. That test invocation covers the two
remaining UI modules, not the preceding Java modules.

| Build | Revision | Verified passing scope | Overall outcome |
|---|---|---|---|
| [#42](http://192.168.137.11:8080/job/PeeGeeQ/42/) | Not recorded here | Database module passed | Earlier module evidence only; not a green release gate |
| [#44](http://192.168.137.11:8080/job/PeeGeeQ/44/) | `fe676bda` | Outbox 673; native 381; bitemporal 480; runtime 48 | Failed later in REST |
| [#45](http://192.168.137.11:8080/job/PeeGeeQ/45/) | `c62af5c3` | REST 518; REST client 48; service manager 76; PG sidecar 9; examples 181; migrations 53; integration tests 109; OpenAPI/coverage stages green | Failed in Management UI: 418 of 419 browser tests passed; Utilities UI not run |
| [#46](http://192.168.137.11:8080/job/PeeGeeQ/46/) | `b19b708b` | Management UI: 128 unit + 419 browser; Utilities UI: 836 unit + 246 browser | SUCCESS |

Build #46's resumed Maven test reactor took **30 minutes 48 seconds**, finishing at
2026-09-05 06:45 UTC. Its 665 browser tests are the functional gate; they must not be confused
with the larger functional-plus-screenshot inventory recorded under Completed Work.

These passes belong to their respective revisions. They do **not** establish a fresh,
from-beginning full-suite pass at `b19b708b`. Such a run remains an explicit release validation,
not a requirement to rerun every module during a focused fix.

**Historical reporting gap:** #46's console reported `No test report files were found.` The existing
Jenkins publisher selects Surefire/Failsafe XML and allows empty results. The UI counts above
were verified from execution logs, not Jenkins' published test-result totals. This does not
change the observed SUCCESS result. Task 7 closed this gap in build #48.

A green gate proves that the selected implementation and tests passed. It does not prove that
an unimplemented proposal exists or replace explicitly planned load, chaos, or failover gates.

## Current Execution Order

As of 2026-10-21 two tasks are ACTIVE. Task 8 resumed on 2026-10-21. That day's work is
committed in `7054ca04` (renewal loop and watchdog modes) and `d5c69cb2` (bootstrap scope A).
Task 9 (delivery-outcome redesign, Finding 20) is in implementation: phase 1
increments 1a and 1b are committed in `092cf23f`, 1b is not approved, and 1c to 1e are not
started.
Task 8: phase 7a architecture selection is complete.
Phase 7b.1 is complete. Phase 7b.2 has the coordinator port, the Consul adapter, node-local
state, supervisor-owned start and lease-loss shutdown, local admission, the renewal loop
with queued ownership operations (OD-4), and the optional watchdog modes (OD-5) implemented in
`peegeeq-pg-failover`. The watchdog is tested only where no device exists; the real-device run
on a Linux host is open. First-start bootstrap scope A (OD-6 to OD-8) is implemented and
tested: verified request file, refusal against local and coordinator history, withdrawn initial
intent, guarded primary start with admission closed. The request is not operator-authenticated.
Bootstrap scope B increment B1 (OD-9 to OD-12, 2026-10-22, working tree, not committed) is
implemented and tested: the durability port and its SQL adapter, and the quoted policy and
role-bound coverage rule. Next in phase 7b.2 is scope B increment B2: guarded installation
under a receipt, revision-1 confirmation, writer activation, and the production-manager write.
No production entry point calls bootstrap or starts the renewal loop yet.
Takeover and production qualification remain open.
Machine-reset testing and additional host/VM access are not prerequisites for this next scope.
Tasks 1, 2, 3, 4, 6, and 7 are COMPLETE.
Task 5 is REJECTED. The document status ACTIVE means this register is the live register.

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

All executable fixed-duration blocking delays covered by the workspace test guard have been
removed. The `blocking-exempt` policy and empty Tier-5 baseline were deleted;
the guard now enforces zero tolerance for both blocking calls and exemption annotations.

Required phase order:

1. **COMPLETE — 2026-09-02.** `CircuitBreakerRecoveryTest` now advances an injected mutable
   clock across the reset timeout without blocking. The obsolete `blocking-exempt` tag and all
   three fixed-duration blocking calls are removed; focused recovery tests passed 2/2 and the
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

Verification: strict TDD exposed the missing circuit-breaker clock seam and both remaining
event-loop test sleeps. The final 11-module reactor slice built cleanly;
`CircuitBreakerRecoveryTest` passed 2/2, `VertxEventLoopBlockingJoinTest` passed 2/2, and
`OnSuccessExceptionSwallowingGuardTest` passed all 8 checks with no blocking exemptions.

Tier 4 and Tier 7 remain complete. Do not reopen them unless a current scan finds a real violation.

### 3. PostgreSQL/HAProxy resilience gaps

**Priority:** Medium
**Status:** COMPLETE — 2026-09-02
**Current evidence:** `HaProxyConnectionFailoverTest` proves pool connection failover between
independent PostgreSQL instances. `HaProxyNotificationFailoverIntegrationTest` now proves the
configured endpoint is shared by pooled and dedicated LISTEN connections, and that both recover
through HAProxy after primary loss. `PgPoolCircuitBreakerIntegrationTest` proves per-pool breaker
opening, structured rejection, isolation, and recovery against a stopped and replacement
PostgreSQL node through a fixed HAProxy endpoint. `HaProxyStreamingReplicationFailoverTest`
proves committed-data continuity and post-promotion writes through the same HAProxy endpoint.
`PgBouncerTransactionModeTest` proves transaction-local tenant schema selection and session-state
reset while two logical clients multiplex one PgBouncer backend connection.

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
- Verification: strict TDD first failed the proxy endpoint contracts and then exposed the
  one-attempt-only LISTEN reconnect defect. The final six-module reactor slice built cleanly;
  configuration suites passed 31/31, notification failure paths passed 8/8, the real two-node
  HAProxy LISTEN/pool failover contract passed 1/1, and the workspace guard passed 8/8.

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
- The red phase also proved that two real connection failures left the breaker uncreated
  (`UNKNOWN`). The final reactor slice was four modules. The circuit-breaker core class is
  `CircuitBreakerManagerTest`; the connection-manager, client-factory, and manager regression
  classes total 73/73.

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

#### 3.4 Validate PgBouncer transaction mode — COMPLETE 2026-09-02

- A real PgBouncer transaction-pooling contract uses two logical clients with the same database
  user and distinct tenant schemas. Four alternating send/consume transactions reuse exactly one
  PostgreSQL backend PID and retain the expected schema and payload isolation.
- PgBouncer explicitly accepts `search_path`, enables protocol-level prepared statements, and
  runs `DISCARD ALL` after every server release. The test also proves an unrelated custom session
  GUC does not leak to the next logical client.
- `PgConnectionManager.withTransaction` reapplies the registered service schema with parameterized
  transaction-local `set_config`. This is required because accepting the startup parameter alone
  did not restore `search_path` on a reused vanilla PostgreSQL backend.
- The failover-local compose stack retains session pooling on port 6432 and adds an opt-in
  `transaction-pool` profile on port 6433 with the verified reset and parameter-tracking settings.
- Strict TDD RED: PgBouncer first rejected `search_path` with `08P01`; configuration-only
  acceptance then exposed missing schema state with PostgreSQL `42P01`. GREEN: the dedicated
  contract passed 1/1 after a clean four-module reactor build. Focused regressions passed
  `PgConnectionManagerCoreTest` 22/22, `PgConnectionManagerSchemaIntegrationTest` 9/9, and
  `PgPoolCircuitBreakerIntegrationTest` 1/1.

### 4. Durable subscriptions runtime

**Priority:** Medium
**Status:** COMPLETE — committed in `7db748b8`; focused verification completed 2026-09-05; no new full-suite/Jenkins gate claimed

Implementation record:

1. **COMPLETE — 2026-09-02.** Defined the separate `BiTemporalSubscriptionService` lifecycle API,
   shared `DurableSubscriptionCoordinator` cursor contract, and immutable
   `BiTemporalSubscriptionInfo` metadata view. `SubscriptionOptions` now supports opt-in durability,
   stable subscription and consumer identity, and a positive bounded replay batch size while
   preserving non-durable defaults. This phase defines contracts only; it does not claim a runtime
   implementation or database behavior.
   Verification: strict TDD compilation failed on the absent durable options and bitemporal
   metadata model. The final API scope passed 34/34: `SubscriptionOptionsDurableTest` 6/6,
   `BiTemporalSubscriptionInfoTest` 2/2, and the existing `SubscriptionOptionsValidationTest`
   26/26. The six-module bitemporal dependency slice compiled cleanly and async guards passed 9/9.
2. **COMPLETE — 2026-09-05.** `DurableBiTemporalSubscriptionCoordinator` persists definitions,
   lifecycle/heartbeat state, and cursors in the existing tenant-local schema. The supported
   entry point is `EventStoreFactory.createBiTemporalSubscriptionService()` implemented by
   `BiTemporalEventStoreFactory`, with manager-owned pool/close-hook integration. Direct manager
   construction was superseded to avoid a database-to-bitemporal module dependency cycle.
   `registerDefinition(...)` is metadata-only; same-key registration preserves committed progress
   and rejects changed filters. Row-locked transactions enforce monotonic advancement, bounded
   explicit reset, and terminal-state checks; a caller-owned transaction can roll back advancement.
   PostgreSQL tests cover recreation/re-registration, lifecycle, cursor integrity, concurrent
   registrations/advancement, tenant isolation, invalid inputs, failure propagation, and shared-pool
   ownership. There is no automatic handler restoration or delivery in this phase.
   Verification (committed in `7db748b8`): the initial contract failed compilation on the missing
   coordinator/factory. A separate delivery-boundary contract then failed 1/1 when metadata
   registration incorrectly reported subscription success; registration became a separate
   operation and durable delivery failed explicitly until phases 3 to 6 implemented it. The first
   complete persistence run exposed four lifecycle failures; explicit SQL parameter typing fixed
   them and the four-test rerun passed. After the required clean six-module rebuild, the final
   `integration-tests` scope passed `DurableBiTemporalSubscriptionIntegrationTest` 34/34 and
   `BiTemporalAppendMetricsIntegrationTest` 4/4. Core regressions passed
   `SubscriptionOptionsDurableTest` 6/6, `BiTemporalSubscriptionInfoTest` 2/2,
   `SubscriptionOptionsValidationTest` 26/26 (nested classes: Builder 11, Equals/HashCode 4,
   ToString 1, EdgeCase 7, FluentAPI 3), and `OnSuccessExceptionSwallowingGuardTest` 8/8.
   All 80 final checks passed without failures/errors/skips. This is focused developer-machine
   evidence, not a Jenkins rerun or a new full-suite release gate.
3. **COMPLETE — 2026-09-05 (committed in `7db748b8`).** Typed finite replay fetches bounded ID-ordered
   batches, applies event/aggregate filters, and acknowledges only successful handlers. A short
   READ COMMITTED SHARE-lock barrier waits for pending inserts before capturing the boundary;
   the lock is released before handlers run. This requires the standard append-only ID sequence
   (ascending, non-cycling, CACHE 1, allocated by INSERT); explicit/preallocated IDs and sequence
   resets are unsupported. Lock waits fail after five seconds rather than skipping history.
   TDD first failed on unsupported replay, then reproduced a delayed lower-ID commit being
   skipped. Final clean reactor rebuild and integration scope: replay 4/4, persistence 34/34;
   async guard 8/8. These are focused local results, not a new Jenkins release gate.
4. **COMPLETE — 2026-09-05 (committed in `7db748b8`).** Typed subscribe establishes LISTEN before
   its first finite replay. Notifications only request another ordered scan; coalesced scans
   and one-second reconciliation cover the handoff and missed notifications. Handlers are
   serialized, close drains delivery, and `deliveryCompletion` surfaces terminal errors.
   A real PostgreSQL test appends during catch-up and again during live delivery, asserting
   ordered, duplicate-free delivery. Focused replay/handoff 5/5, persistence 34/34, guard 8/8.
5. **COMPLETE — 2026-09-05 (committed in `7db748b8`).** V020 and the fresh-schema template add
   expiring UUID owner leases and monotonically increasing generations. Each finite scan
   claims, renews, and releases its lease; live contenders reconcile as standbys while owned.
   Standalone catch-up fails explicitly when busy. Expiry permits takeover, but stale owners
   cannot commit acknowledgements. Reset/pause/cancel revoke old generations; administrative
   advancement cannot bypass a live lease. Delivery remains at-least-once across crashes or
   takeover: external handler side effects require idempotency. Focused PostgreSQL replay,
   ownership, takeover, renewal, and persistence: 42/42; migrations 11/11; async guard 8/8.
6. **COMPLETE — 2026-09-05 (committed in `7db748b8`).** Real PostgreSQL delivery contracts cover
   manager recreation, typed payloads, independent tenants, missed NOTIFY reconciliation,
   catch-up/live ordering, filters, handler acknowledgement/failure, competing owners, renewal,
   expiry, fencing, and pause/resume/cancel. New recovery contracts exposed and fixed a poisoned
   local handler registration and delivery-error/cleanup coupling. The intentional live-handler
   ERROR has an exact logger/message/throwable/count contract, not a broad exemption.
   Final clean reactor rebuild; replay/delivery 15/15, persistence 34/34, async guard 8/8.

Typed `subscribe(..., Class<T>, handler, options)` starts durable delivery. The untyped overload
fails explicitly rather than casting erased payloads. `catchUp` runs one finite replay;
`deliveryCompletion` exposes post-start terminal failures. Applications re-register handlers
after restart. Existing non-durable subscriptions are unchanged.

Final focused checks: replay/delivery 15, persistence 34, migrations 11, API options 6,
API metadata 2, API validation 26 (Builder 11, Equals/HashCode 4, ToString 1, EdgeCase 7,
FluentAPI 3), and async guard 8 — **102 passing checks**, zero failures/errors/skips.
The six-module Java slice rebuilt cleanly before verification. The deliberately failing live
handler is covered by an exact expected-error log contract.

The design reference is
`docs-design/event-sourcing-messaging/PEEGEEQ_DURABLE_SUBSCRIPTIONS_OPTION_PLAN.md`; status and
execution order are controlled here.

### 5. Transactional REST API product decision

**Priority:** Product decision
**Status:** REJECTED — 2026-09-15

The proposal to expose domain-specific transactional REST endpoints was rejected as out of
PeeGeeQ product scope. Both design documents under `docs-design/transactional-rest-api/`
(`PEEGEEQ_TRANSACTIONAL_REST_API_DESIGN.md` and
`PEEGEEQ_PLUGIN_MODEL_TRANSACTIONAL_PATTERNS_DESIGN.md`) carry the status
`REJECTED — OUT OF PEEGEEQ PRODUCT SCOPE`. Commit `227d12d7` removed this section from the
register without recording the outcome. The number is retained so that Tasks 6 and 7 keep
their identifiers.

### 6. Partitioned consumption pre-GA gates

**Priority:** Release gate
**Status:** COMPLETE — Jenkins build #11, 2026-09-17 UTC

The release gate is implemented by the explicitly selected
`PartitionedConsumptionReleaseGate`. Its filename deliberately omits the normal `Test` suffix, so
the one-hour workload is not added to routine core, integration, performance, or `all-tests`
runs. Jenkins build #10 qualified the stack-safe harness for two minutes; build #11 performed the
clean rebuild, focused fault suites, and full one-hour gate. Build #11 used source SHA-256
`485c930dd264864cd9157fe3378e25e661c51b97afa2120d1cc5bde6c0b54f27` over SCM revision
`263309d8`.

Builds #9, #10, and #11 are in a different build-number sequence from builds #36 to #48.
Builds #36 to #48 link to the Jenkins job at `192.168.137.11`, which
`docs-design/dev/PEEGEEQ_WSL_PASSWORDLESS_SSH_SETUP.md` names as host `ubu24-cicd`. The Task 6
envelope below records host `zorin-nuc`. This register does not record the job URL for builds
#9 to #11.

Release workload and operating envelope:

- Ubuntu 24.04 host `zorin-nuc`, Linux `7.0.0-31-generic`, 12 vCPU, 31 GiB RAM, 2 GiB swap,
  457 GiB filesystem with 417 GiB free, Docker `29.1.3`;
- PostgreSQL `15.13-alpine3.20` Testcontainer;
- 3,600-second window at 200 messages/second total: 100 messages/second in each of two isolated
  tenant schemas, 512-byte payloads, 50-row publish batches, four pool connections per tenant,
  two independent consumer groups per tenant, and 16 initial partitions;
- live expansion to 17 partitions and an explicit rebalance at the midpoint;
- 360,033 published messages per tenant and 360,033 deliveries to each group: 720,066 published
  rows and 1,440,132 handler deliveries across the two tenants;
- 99.99 messages/second measured per tenant, bucketed delivery p50/p95/p99 of 1,000 ms, and
  bucketed OLTP p50/p95/p99 of 10 ms;
- 34,792 and 34,789 successful OLTP probes, with zero probe failure and OLTP p95 well inside the
  five-second connection-timeout envelope;
- both groups drained completely after the sustained window, with zero order violations and zero
  cross-tenant deliveries;
- watermark `360017` in both schemas, zero pending rows at or below the safe watermark, and only
  the bounded 16-row cross-partition tail above it (`360017` completed + `16` pending = `360033`);
- all consumer assignments removed after orderly engine shutdown; and
- approximately 2.61 GB cluster WAL growth. Minute host samples generally showed 96–97% CPU
  idle; the final sample retained 31 GiB total / 2.2 GiB used memory, zero swap use, and 5% disk
  use.

The accepted Linux invocation, after the required clean reactor build and focused suites, was:

```bash
mvn test -Pperformance-tests -pl :peegeeq-native \
  -Dtest=PartitionedConsumptionReleaseGate \
  -Dpeegeeq.task6.duration.seconds=3600 \
  -Dpeegeeq.task6.message.rate=200 \
  -Dpeegeeq.task6.partition.count=16 \
  -Dpeegeeq.task6.groups.per.tenant=2 \
  -Dpeegeeq.task6.pool.size=4 \
  -Dtest.timeout.default=100m \
  -Dtest.timeout.method=95m \
  2>&1 | tee task6-sustained.log
```

The command above is the build #11 record and is not the current invocation.
`PartitionedConsumptionReleaseGate` is in `peegeeq-benchmarking`
(`src/test/java/dev/mars/peegeeq/pgqueue/`). The `Jenkinsfile` stage
`Partitioned consumption release gate` runs the same properties with
`-pl :peegeeq-benchmarking`. Use that module for any new run.

The command passes `-Dtest.timeout.method=95m`. `peegeeq-benchmarking/pom.xml` maps that
property to `junit.jupiter.execution.timeout.testmethod.default`. The gate method also declares
`@Timeout(value = 90, timeUnit = TimeUnit.MINUTES)`. The JUnit documentation states that a
`@Timeout` annotation overrides the configured default. Which limit applied in build #11 is not
recorded here and has not been verified by a run.

The focused release suites in build #11 passed **89/89** before the sustained gate:

- database assignment, watermark, dead-group cleanup/detection, and flapping protection:
  `12 + 15 + 12 + 11 + 10 = 60`;
- native partitioned integration, safety, and fault handling: `13 + 6 + 7 = 26`;
- outbox schema isolation: `1`; and
- OLTP/backfill contention: `2`.

The sustained class then passed `1/1`, so the complete build published **90 passing tests**, zero
failures/errors/skips, plus archived clean-build, focused-suite, sustained-workload, host-baseline,
and per-minute `vmstat` logs. The gate covers long-duration fan-out/partition stability, consumer
death and lease/rebalance recovery, pool pressure, concurrent schema isolation, live partition
creation, final drain, watermark cleanup, and assignment cleanup for the documented envelope.

Build #9 is intentionally not accepted as release evidence: its data workload reached the full
hour, but the first harness retained recursively composed publisher/probe futures and overflowed
the JVM stack while completing them. The harness was changed to timer-scheduled, stack-safe
asynchronous loops, qualified in build #10, and accepted only after build #11 completed cleanly.

This remains an explicit owner/release run, not an automatic requirement after every code phase.

### 7. Jenkins UI test-result publishing

**Priority:** CI reporting follow-up
**Status:** COMPLETE — Jenkins build #48, 2026-09-05

Build #46 passed both UI suites, but Jenkins did not publish their test counts. Add JUnit XML
output for the unit and browser suites in both UIs and include those reports in the pipeline
publisher. Keep this work in this register rather than creating another implementation plan.

Implemented 2026-09-05:

- Both Vitest configurations emit verbose console output plus `target/ui-reports/vitest.xml`.
- Both Playwright configurations emit `target/ui-reports/playwright.xml`, outside Playwright's
  cleaned `test-results` directory. Maven core/smoke profiles no longer override away JUnit.
- Management UI's all-tests command runs the unit inventory once; the redundant, currently
  empty integration invocation cannot overwrite its result. The separate manual integration
  command writes `integration.xml`. Both `test:ci` commands delegate to `test:all`.
- The pipeline is configured to remove the four known stale reports after rebuilding, validate
  reports expected for the selected reactor/suite, publish Java/UI XML, and retain artifacts
  even if JUnit parsing fails. Missing reports and real failures fail the build.
- `scripts/ci/check-ui-reports.mjs` reports totals for reconciliation. Its seven contracts
  cover full/resumed/unit/Java-only selections and missing/empty/failing report handling.
- Four real-emitter contracts each run a passing and deliberately failing Vitest or Chromium
  fixture using the production reporters; all four passed. All **11 reporting contracts** passed.
- The required clean three-module UI reactor rebuild passed. The actual Maven default/core
  test scope passed Management UI **128/128 across 14 files** and Utilities UI **836/836 across
  55 files**. Parsed JUnit totals matched both console totals, with no failures/errors/skips.
- No dependency versions were changed. npm reported existing engine/audit warnings during
  installation (Management 16 findings; Utilities 25); these were not hidden or auto-fixed.

Jenkins verification completed in
[build #48](http://192.168.137.11:8080/job/PeeGeeQ/48/) by replaying the successful UI-only
gate with `TEST_SUITE=all` and `ALL_TESTS_START_MODULE=peegeeq-management-ui`. Checkout used
SCM revision `19e3cbdba2b6a5691f4473fc3e38033212dee3ec`, then applied the pre-commit Task 7
files from `/tmp/peegeeq-task7-overlay.tar`. The replay verified the archive before
extraction with SHA-256
`f9792a15ed11dfc0f4d9ae91466b22959d8d1e08e872618a0f7edbd280c96dfe`.

Build #48 completed in 33 minutes with `SUCCESS`. The report-presence check printed the four
expected zero-failure summaries, and Jenkins published **1,629 passing tests, 0 failures,
0 skipped**:

| UI module | Vitest | Playwright | Published total |
|---|---:|---:|---:|
| Management UI | 128 | 419 | 547 |
| Utilities UI | 836 | 246 | 1,082 |
| **Total** | **964** | **665** | **1,629** |

The production XML files were retained under each module's top-level
`target/ui-reports/{vitest,playwright}.xml`. The reporting implementation is now committed in
`7db748b8`. Build #48 remains reproducible evidence for the exact recorded SCM revision plus
overlay hash; this register does not claim that Jenkins has run a plain SCM checkout of
`7db748b8`.

Focused reporting check (repository root):

```text
node --test scripts/ci/check-ui-reports.test.mjs scripts/ci/ui-report-contracts.test.mjs
node scripts/ci/check-ui-reports.mjs core beginning
```

Emitter contracts use one real unit/browser fixture per UI, not the 665-test browser gate.
They remove their own fixture XML afterwards; run the actual selected suite before validating
its reports. They must not be represented as full UI browser coverage.

Completion requires:

- Both UI suites emit reports to known, non-overlapping paths retained until publishing.
- A UI-only resumed build publishes per-suite counts in Jenkins instead of an empty-results
  warning, with counts reconciled against the execution logs.
- Actual unit/browser failures still fail the build; reporting must not mask test failures.
- Missing expected UI reports are detected explicitly; Java-only selections do not require UI
  reports for suites they did not run.

Task 4 phases 3 to 6 and Task 7 are complete and committed in `7db748b8`. Task 4 has focused
developer-machine PostgreSQL evidence but no new Jenkins/full-suite gate; Task 7 additionally
has the successful remote Jenkins publication evidence recorded above.

### 8. Connection management and HAProxy failover

**Priority:** Not assigned
**Status:** ACTIVE — phase 7b.1 complete; phase 7b.2 partial (renewal loop, watchdog modes, and bootstrap scope A done 2026-10-21; scope B increment B1 done 2026-10-22)
**Objective:** implement the design in
`docs-design/failover and resilience/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md` (design
revision 2026-10-08). HAProxy routes by sidecar writer eligibility, which combines current
node-owned lease, local admission, selected optional-watchdog checks, node identity, role,
and synchronous coverage. Manual profile B
precedes automatic profile A. Failover depends on a coordinator port, not on Consul: Consul is
the first adapter and Qraft a planned second (P-38). The port `PgLeaseCoordinator` and the
adapter `ConsulLeaseCoordinator` are implemented in `peegeeq-pg-failover`. Node supervision is
implemented there for guarded start, lease-loss shutdown, local admission, the renewal loop,
the optional watchdog modes, and bootstrap scope A. Promotion and takeover are not implemented
in that module.
G-7 qualification proceeds alongside
the common supervisor implementation. `peegeeq-service-manager` handles federation and is outside this task. No profile
requires the Patroni product. The Patroni control approach is selected: per-node supervisor,
local lease-loss shutdown, and optional independent watchdog protection. The watchdog
modes are `automatic` (default), `off`, and `required`. Only `required` refuses writer
start/promotion because the watchdog is unavailable. This correction superseded mandatory
watchdog wording in the companion designs, which were aligned on 2026-10-08. Optional support
does not prove old-writer exclusion during supervisor death or whole-VM pause.
Java changes follow strict TDD with real-component failure tests.

The implementation plan is
`docs-design/failover and resilience/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_IMPLEMENTATION_PLAN.md`.
It holds dated implementation findings and runs, adopted contracts P-0 to P-38, deployment
gates G-1 to G-7, acceptance obligations S1 to S59, and twelve design decisions for phase
7b.2 (OD-1 to OD-12). The implementer decided OD-1 to OD-3, and the owner has not reviewed
them. The owner decided OD-4 to OD-12.
Status and execution order are controlled
here. Design contracts do not establish runtime coverage.

Historical assessment on 2026-10-07 at `ff5c17da`: no requirement was fully met. R-6, R-8, and
R-14 were partly met. The source assessment found no Consul failover monitor or sidecar-based
HAProxy configuration. Three of the earlier 17 scenarios had partial tests, none against
the designed topology. This assessment concerns the earlier design. No runtime tests were
rerun for the current documentation and phase 7a architecture selection.

Phases:

1. **COMPLETE — 2026-10-07.** Two text corrections to the earlier document. Superseded by the
   design rewrite.
2. **COMPLETE — 2026-10-07.** Source read and findings at `ff5c17da`. The plan lists each file
   and whether it was read in full. No test was run in this phase.
3. **COMPLETE — 2026-10-07.** Corrections to the earlier document. Superseded by the design
   rewrite.
4. **COMPLETE — 2026-10-07.** Baseline run of the seven existing classes on the development
   machine at `ff5c17da`, after a clean seven-module rebuild, under `-Pintegration-tests`.
   `peegeeq-db`: `HaProxyConnectionFailoverTest` 6/6, `HaProxyStreamingReplicationFailoverTest`
   1/1, `PgBouncerTransactionModeTest` 1/1, `PgPoolCircuitBreakerIntegrationTest` 1/1.
   `peegeeq-bitemporal`: `HaProxyNotificationFailoverIntegrationTest` 1/1. `peegeeq-pg-sidecar`:
   `PgPrimaryCheckIntegrationTest` 7/7, `PgPrimaryCheckLifecycleTest` 2/2. 19 passed, zero
   failures, errors, or skips. This is focused developer-machine evidence for the existing
   tests only.
5. **OPEN — historical red run retained.** The 2026-10-07 integration run of
   `HaProxyConnectionFailoverTest` executed 6 tests with 1 failure. Its new connection reached
   the replacement independent database; its existing pool remained on the backup until the
   30-second deadline. P-14 now
   retains profile D for connection recovery and moves production convergence to phase 7.
   Rerun on 2026-10-09 inside `mvn clean test -Pall-tests`: `peegeeq-db` executed 1,124 tests
   with 1 failure, the same assertion in `testFailbackAfterPrimaryRecovery`. The test is still
   red. Output: `logs/all-tests-20261009.log`. The whole-repository gate cannot pass until this
   phase is finished.
   **Repaired and committed in `f391a7f0` (2026-10-11), not yet run on Jenkins.** Under P-14 the pooled
   convergence step was removed from `testFailbackAfterPrimaryRecovery`; the red evidence is
   kept in plan §4.2 and the repair in §4.3. Targeted run: `HaProxyConnectionFailoverTest`
   6 tests, 0 failures (`logs/peegeeq-db-haproxy-failover-phase5-repair-20261011.log`). The
   requirement that pooled connections converge on the primary is not met and has no test; it
   stays owed to the role-and-authority tests. This item closes when a Jenkins build from
   `beginning` passes `peegeeq-db`.
6. **COMPLETE — documentation contracts, 2026-10-08.** Five documents define the data model,
   admission and generation barrier, synchronous policy lifecycle, finite replay, bootstrap,
   coordinator gate, and corrected transition order. Static document checks do not qualify
   runtime safety, production providers, or recovery timing.
7. **7a COMPLETE — architecture selection, 2026-10-08.** Select one PeeGeeQ supervisor per
   PostgreSQL node, the writer's own Consul lease, local process/admission control, local
   persistent receipts/quarantine/grants, and optional independent watchdog modes. This
   supersedes the earlier mandatory-watchdog decision. Use the same
   containerised protocol on Linux hosts/VMs, Docker hosts, and Kubernetes. Remove the central
   provider, all-node generation barrier, and separate manual authority. Qualified expiry
   takeover does not require a failed-host stop reply. Production enforcement, VM pause/resume,
   endpoint bindings, runtime implementation, and timing remain unverified.
   **COMPLETE — 7b.1, 2026-10-08:** `PgConnectionManager` owns begin, commit, rollback,
   and observed connection release. It restores transaction-local `synchronous_commit=on`
   immediately before commit, rejects caller-completed transactions, and reports failed
   commit acknowledgements or commit warnings as `PgCommitOutcomeUnknownException`.
   Standby reads and existing managed connection operations retain their behavior.
   The new real-container fixture uses HAProxy, one primary, and two physical standbys with
   `ANY 2` synchronous coverage. Tests observe `SyncRep` before interrupting commit and
   check locally committed rows after the failed acknowledgement. This verifies the managed
   commit boundary; it does not implement automatic failover.
   **PARTIAL — 7b.2, 2026-10-08:** typed node configuration, immutable control metadata,
   and asynchronous node-owned Consul protocol are implemented. Real three-server Consul
   1.22.1 tests cover TTL-only session settings, atomic initial acquisition, session/revision
   conditions, consistent reads, quorum loss, ACL boundaries, retained history, restart
   refusal, malformed responses, bounded timeouts, and late replies. Generic close preserves
   the session and control history. Failed or changed control observations retire cached
   ownership.
   **7b.2 update, 2026-10-09:** the coordinator port and the Consul adapter are extracted behind
   a shared contract suite, with acquisition after release and guarded release (P-38, S59).
   Watchdog timing is separated from the lease rule in `PgNodeConfig` and in the elector's
   freshness calculation. OD-1 to OD-3 are decided by the implementer and not reviewed by the
   owner. Durable grants, quarantine, and receipts, supervisor-owned start and lease-loss
   shutdown, and local admission are implemented and tested, including the failure modes of the
   admission gate. Every failed, refused, or late operation in the module logs at ERROR, and each
   test that causes one declares it.
   **7b.2 update, 2026-10-21:** `PgRenewalLoop` calls `renew` on the loop interval. The owner
   decided OD-4: `PgPrimaryElector` queues `renew`, `update`, and `release` instead of refusing
   one while another is in flight. Plan §9.12 has the record.
   **7b.2 update, 2026-10-21:** the optional watchdog modes are implemented. The owner decided
   OD-5: a plain-Java device adapter that reads the timeout from sysfs, and real-device tests
   deferred to a Linux host. Plan §9.13 has the record.
   **7b.2 update, 2026-10-21:** bootstrap scope A is implemented. The owner decided OD-6 (a
   request file authenticated only by filesystem permissions), OD-7 (all-node evidence left
   until later), and OD-8 (two scopes). Plan §9.14 has the record.
   **7b.2 update, 2026-10-22:** bootstrap scope B increment B1, the durability boundary, is
   implemented. The owner decided OD-9 (`ALTER SYSTEM` and reload), OD-10 (the deployment
   provisions standby data), OD-11 (one replication role per standby), and OD-12 (the
   production-manager write is in scope B). Plan §9.15 has the record.
   Remaining, in order: bootstrap scope B increment B2; the real-device watchdog run on a Linux host. Preserve conservative ownership deadlines and late-reply
   rejection. Complete G-7 qualification alongside these scopes. The next scope uses
   Docker/Testcontainers; machine-reset infrastructure is not a prerequisite.
   **OPEN — 7b.3:** manual
   takeover, repeated failover, synchronous cutover, and controlled re-join.
8. **OPEN.** Enable autonomous initiation on the verified common boundary (8a), complete
   per-node authority/bootstrap reconciliation (8b), infrastructure fault tests including
   S54 to S58 (8c), and automatic database safety acceptance (8d). No second provider or
   remote stop-confirmation requirement. Full client recovery and timing follow phases 10 to 14.
9. **OPEN.** Complete operation deadlines, millisecond precision, idle lifetime, contextual
   connection discard, and observed cleanup. Retain the phase 7 commit boundary.
10. **OPEN.** Authenticated writer status, SQL identity, LISTEN initialization and reconnect,
    finite durable replay and native claim/acknowledgement recovery, and shutdown.
11. **OPEN.** Migrate remaining modules to shared pooled access, schema, and classified
    availability breakers. Verify PgBouncer transaction mode one module at a time.
12. **OPEN.** Readiness requires writer eligibility, durability, and required catch-up.
13. **OPEN.** Frozen-node, in-flight operation, redundant proxy/pooler, SQL/HTTP endpoint
    ownership, and differing-observation qualification.
14. **OPEN.** Build the replicated three-node target stacks and scenario runbook. Execute
    all S1 to S59 cases and measure the 45-second objective under a specified workload.
15. **OPEN.** Reassess historical findings against code and fresh logs. Complete G-1 to G-7,
    external references, configuration documentation, and release evidence. This Task 8
    reconciliation closes no runtime or production qualification obligation.

Phase 7b.1 verification, 2026-10-08:

- Required rebuild: `mvn clean install -DskipTests -pl :peegeeq-db -am` passed for the
  four-module reactor slice. Output: `logs/phase7b1-standby-compatible-rebuild-20261008.log`.
- Targeted `-Pintegration-tests` scope: `PgConnectionManagerDurabilityIntegrationTest`
  **15/15**, `PgConnectionManagerCoreTest` **22/22**, and
  `PgPoolCircuitBreakerIntegrationTest` **1/1**. Total **38**, zero failures/errors/skips.
  Output: `logs/phase7b1-completion-tests-20261008.log`.
- Core guards: `DisabledTestsGuardTest` **2/2**, `InvalidDurationLiteralGuardTest` **2/2**,
  `OnSuccessExceptionSwallowingGuardTest` **8/8**,
  `SchemaInitializerTestInfrastructureGuardTest` **1/1**, and
  `VertxAsyncForbiddenPatternsGuardTest` **1/1**. Total **14**, zero failures/errors/skips.
  Output: `logs/phase7b1-completion-guards-20261008.log`.
- The focused pre-implementation durability run failed **5/5** against the original manager.
  Output: `logs/phase7b1-red-durability-20261008.log`. Earlier fixture failures are retained
  in separate logs and are not accepted as durability evidence.
- Known gaps: caller SQL can physically commit before the manager rejects the contract
  violation. Callers must not complete the owned transaction. Direct-pool writes and other
  modules were not qualified. Consul leases, watchdog enforcement, VM pause/resume,
  promotion/rejoin, operation deadlines, and the 45-second objective remain unverified.

Phase 7b.2 protocol verification, 2026-10-08:

- Required rebuild: `mvn clean install -DskipTests -pl :peegeeq-pg-failover -am` passed
  for the three-module reactor slice. Output: `logs/phase7b2-lease-verified-rebuild-20261008.log`.
- Targeted `-Pintegration-tests` scope: `ConsulLeaseProtocolIntegrationTest` **28/28**.
  Output: `logs/phase7b2-lease-verified-integration-20261008.log`.
- Targeted default/core scope: `PgNodeConfigTest` **8/8**.
  Output: `logs/phase7b2-lease-verified-core-20261008.log`.
- Core guards: `DisabledTestsGuardTest` **2/2**, `InvalidDurationLiteralGuardTest` **2/2**,
  `OnSuccessExceptionSwallowingGuardTest` **8/8**,
  `SchemaInitializerTestInfrastructureGuardTest` **1/1**, and
  `VertxAsyncForbiddenPatternsGuardTest` **1/1**. Total **14**.
  Output: `logs/phase7b2-lease-verified-guards-20261008.log`.
  All final scopes report zero failures/errors/skips.
- The first integration run executed **18** failures/errors against the protocol stubs
  after the real quorum started. Output: `logs/phase7b2-lease-red-tests-20261008.log`.
  Subsequent failing contracts exposed unknown-leader absence, fractional revisions, and
  retained cached ownership after failed reads or observed deletion. Real tests also exposed
  transaction-result cardinality and lock-generation preservation errors in the implementation.
- Known gaps: local supervision, grants/quarantine/receipts, independent watchdog, guarded
  PostgreSQL startup, authenticated bootstrap, voluntary release, snapshot/restore, TLS,
  earliest expiry versus actual watchdog exclusion, VM pause/resume, takeover/rejoin,
  routed application acceptance, and recovery timing are unverified. The low-level initial
  intent API is not an authenticated bootstrap implementation. `PgNodeConfig` currently
  applies watchdog timing unconditionally; no optional modes or device support exist in code.
  Device/reset qualification is separate optional-support work. Additional host/VM access
  is not required for the next local-supervisor implementation using Docker.
- One intermediate close-verification read returned HTTP 403. Its cause is unverified.
  The isolated close test and the final full protocol class passed. No retry or delay was
  added to that test. G-7 remains open.

Phase 7b.2 supervision and admission verification, 2026-10-09:

- Required rebuild: `mvn clean install -DskipTests -pl :peegeeq-pg-failover -am` passed for the
  three-module reactor slice.
  Output: `logs/rebuild-peegeeq-pg-failover-gaps-green-20261009.log`.
- Targeted `-Pintegration-tests` scope: `ConsulLeaseCoordinatorIntegrationTest` **61/61**,
  `PgFailoverMonitorIntegrationTest` **20/20**, `PgCtlProcessControlIntegrationTest`
  **10/10**, and `PgHbaAdmissionGateIntegrationTest` **6/6**. Total **97**, zero
  failures/errors/skips.
  Output: `logs/peegeeq-pg-failover-integration-gaps-green-20261009.log`.
- Targeted default/core scope: `LocalCommandRunnerTest` **6/6**, `PgLocalStateStoreTest`
  **29/29**, and `PgNodeConfigTest` **17/17**. Total **52**, zero failures/errors/skips.
  Output: `logs/peegeeq-pg-failover-core-gaps-green-20261009.log`.
- Core guards: `DisabledTestsGuardTest` **2/2**, `InvalidDurationLiteralGuardTest` **2/2**,
  `OnSuccessExceptionSwallowingGuardTest` **8/8**,
  `SchemaInitializerTestInfrastructureGuardTest` **1/1**, and
  `VertxAsyncForbiddenPatternsGuardTest` **1/1**. Total **14**.
  Output: `logs/peegeeq-test-support-core-guards-errorlevels-20261009.log`.
- Red runs: the first full integration run failed **1** of **88** in the Consul fixture setup
  with HTTP 403. Output: `logs/peegeeq-pg-failover-integration-20261009.log`. With the error
  declarations added and the production code unchanged, core failed **23** of **51** and
  integration failed **43** of **91**, each with an expected-ERROR occurrence mismatch. Outputs:
  `logs/peegeeq-pg-failover-core-errorlevels-red-20261009.log` and
  `logs/peegeeq-pg-failover-integration-errorlevels-red-20261009.log`. A second round declared
  the refusals and late replies that were not logged: integration failed **14** of **97** the
  same way. Output: `logs/peegeeq-pg-failover-integration-gaps-red-20261009.log`. The new
  interrupted-command test failed **1** of **52** core tests on an undeleted output file.
  Output: `logs/peegeeq-pg-failover-core-gaps-red-20261009.log`.
- The six admission-gate tests were written against existing code. Each was then run against a
  gate with its behaviour removed, and each failed. Outputs:
  `logs/peegeeq-pg-failover-gate-mutation-red-20261009.log` and
  `logs/peegeeq-pg-failover-gate-mutation-c-red-20261009.log`. The gate source was restored to
  the same SHA-256 and the class passed **6/6**.
  Output: `logs/peegeeq-pg-failover-gate-restored-20261009.log`.
- Whole repository: `mvn clean test -Pall-tests` stopped at `peegeeq-db` with **1** failure in
  **1,124** tests, the phase 5 test. Output: `logs/all-tests-20261009.log`. Resumed with
  `mvn test -Pall-tests -rf :peegeeq-outbox`: all 17 remaining modules passed, **2,876** Java
  tests with zero failures/errors/skips, Management UI Vitest **128** and Playwright **419**,
  Utilities UI Vitest **836** and Playwright **246**.
  Output: `logs/all-tests-resume-outbox-20261009.log`. Plan §8.1 has the per-module counts.
- The Consul fixture waited for a leader only. A leader is visible before it has bootstrapped
  ACLs and registered the servers in the catalog. The binding now also waits for the catalog to
  list all three servers. No lease operation is retried. Plan §9.11 holds the probe evidence.
- The logs dated 2026-10-08 cited in this register are untracked files. They were not present
  on the machine used on 2026-10-09 and were not reread. Whether the intermediate HTTP 403 of
  2026-10-08 had the readiness cause above is not established.
- Known gaps: the renewal loop, promotion, watchdog integration, bootstrap, takeover/re-join,
  TLS/restore, independent exclusion under supervisor/VM pause, routed application acceptance,
  and recovery timing are unverified. The guard for a command runner that returns no result
  has no test, because the real runner never returns none. Plan §9.11 lists the other gaps.

Task 8 status review, 2026-10-21 at `092cf23f`:

- Source: `peegeeq-pg-failover` has no change since `23869e0f` (2026-10-09). `renew()` exists in
  `PgFailoverMonitor`; no source file schedules it (search for `setPeriodic` and `setTimer`
  in `src/main`: no match). `PgWatchdogMode` and the timing rules exist; no watchdog device code
  exists. `peegeeq-pg-sidecar/src` has no commit since `ff5c17da`.
- Rebuild: `mvn clean install -DskipTests -pl :peegeeq-pg-failover -am` passed.
  Output: `logs/rebuild-peegeeq-pg-failover-review-20261021.log`.
- `peegeeq-pg-failover` default/core: `LocalCommandRunnerTest` **6**, `PgLocalStateStoreTest`
  **29**, `PgNodeConfigTest` **17**. Total **52**, zero failures/errors/skips.
  Output: `logs/peegeeq-pg-failover-core-review-20261021.log`.
- `peegeeq-pg-failover` `-Pintegration-tests`: `ConsulLeaseCoordinatorIntegrationTest` **61**,
  `PgFailoverMonitorIntegrationTest` **20**, `PgCtlProcessControlIntegrationTest` **10**,
  `PgHbaAdmissionGateIntegrationTest` **6**. Total **97**, zero failures/errors/skips.
  Output: `logs/peegeeq-pg-failover-integration-review-20261021.log`.
- Phase 5: after `mvn clean install -DskipTests -pl :peegeeq-db -am` (passed), the scope
  `-Pintegration-tests -pl :peegeeq-db -Dtest=HaProxyConnectionFailoverTest` ran **6** tests with
  zero failures/errors/skips. The test is green in the working tree. The Jenkins gate from
  `beginning` has not run it. Outputs: `logs/rebuild-peegeeq-db-review-20261021.log` and
  `logs/peegeeq-db-haproxy-failover-review-20261021.log`.
- Result: the register's phase 7b.2 status is correct. Counts equal the 2026-10-09 record
  (52 and 97).
- Gap found: the `Jenkinsfile` choice `ALL_TESTS_START_MODULE` does not list
  `peegeeq-pg-failover`. The module is in the root `pom.xml` reactor, so a full run includes
  it, but a resumed run cannot start at it.
- Not checked: the five guard classes, other modules, Jenkins, and every item under "Known
  gaps" above.

Phase 7b.2 renewal loop verification, 2026-10-21 (committed in `7054ca04`):

- Red: `PgPrimaryElectorQueueingIntegrationTest` failed **3** of **4** against the refusing
  elector (`logs/peegeeq-pg-failover-elector-queue-red-20261021.log`).
  `PgRenewalLoopIntegrationTest` failed compilation before the loop existed
  (`logs/peegeeq-pg-failover-renewal-loop-red-20261021.log`). With rescheduling removed from the
  loop, its two timing tests failed
  (`logs/peegeeq-pg-failover-renewal-loop-mutation-red-20261021.log`).
- Rebuild: `mvn clean install -DskipTests -pl :peegeeq-pg-failover -am` passed.
  Output: `logs/rebuild-peegeeq-pg-failover-loop3-20261021.log`.
- `-Pintegration-tests`: `ConsulLeaseCoordinatorIntegrationTest` **61**,
  `PgFailoverMonitorIntegrationTest` **20**, `PgCtlProcessControlIntegrationTest` **10**,
  `PgHbaAdmissionGateIntegrationTest` **6**, `PgPrimaryElectorQueueingIntegrationTest` **4**,
  `PgRenewalLoopIntegrationTest` **13**. Total **114**, zero failures/errors/skips.
  Output: `logs/peegeeq-pg-failover-integration-loop-20261021.log`.
- Default/core: `LocalCommandRunnerTest` **6**, `PgLocalStateStoreTest` **29**,
  `PgNodeConfigTest` **17**. Total **52**. Output: `logs/peegeeq-pg-failover-core-loop-20261021.log`.
- Guards in `peegeeq-test-support` (61 tests, including the five guard classes, 14): zero
  failures. Output: `logs/peegeeq-test-support-core-guards-loop2-20261021.log`.
- After the mutation was reverted (same SHA-256) and rebuilt, `PgRenewalLoopIntegrationTest`
  passed **13** of **13** (`logs/peegeeq-pg-failover-renewal-loop-restored-20261021.log`).
- Known gaps: no production caller starts the loop; the mid-run timer-scheduling failure path
  and a queued renewal that misses its freshness deadline have no test; the elector's
  null-result check is unreachable on the renew, update, and release paths. No other module
  and no Jenkins build was run.

Phase 7b.2 watchdog mode verification, 2026-10-21 (committed in `7054ca04`):

- Red: the module's tests failed compilation before the port and adapter existed
  (`logs/peegeeq-pg-failover-watchdog-red-20261021.log`). With `required` handled like
  `automatic`, the `required` test failed
  (`logs/peegeeq-pg-failover-watchdog-mutation-red-20261021.log`); the source was restored to the
  same SHA-256.
- Rebuild: `mvn clean install -DskipTests -pl :peegeeq-pg-failover -am` passed.
  Output: `logs/rebuild-peegeeq-pg-failover-watchdog-final-20261021.log`.
- `-Pintegration-tests`: `ConsulLeaseCoordinatorIntegrationTest` **61**,
  `PgFailoverMonitorIntegrationTest` **23**, `PgCtlProcessControlIntegrationTest` **10**,
  `PgHbaAdmissionGateIntegrationTest` **6**, `PgPrimaryElectorQueueingIntegrationTest` **4**,
  `PgRenewalLoopIntegrationTest` **13**. Total **117**, zero failures/errors/skips.
  Output: `logs/peegeeq-pg-failover-integration-watchdog-20261021.log`.
- Default/core: `LocalCommandRunnerTest` **6**, `PgDeviceWatchdogTest` **7**,
  `PgLocalStateStoreTest` **29**, `PgNodeConfigTest` **17**. Total **59**.
  Output: `logs/peegeeq-pg-failover-core-watchdog-20261021.log`.
- Guards in `peegeeq-test-support` (61 tests, including the five guard classes, 14): zero
  failures. Output: `logs/peegeeq-test-support-core-guards-watchdog-20261021.log`.
- Known gaps: no test arms a watchdog, so keepalive, disarm, the safe-timeout check, and the
  unconfirmed-stop path are unverified until the Linux-host run; the mode is applied at
  primary start only; `nowayout` kernels are not detected; no configuration loader reads the
  watchdog keys.

Phase 7b.2 bootstrap scope A verification, 2026-10-21 (committed in `d5c69cb2`):

- Red: the module's tests failed compilation before the classes existed
  (`logs/peegeeq-pg-failover-bootstrap-red-20261021.log`). With the local-history check removed,
  the stored-grant and quarantine tests failed
  (`logs/peegeeq-pg-failover-bootstrap-mutation-red-20261021.log`); the source was restored to the
  same SHA-256.
- Rebuild: `mvn clean install -DskipTests -pl :peegeeq-pg-failover -am` passed.
  Output: `logs/rebuild-peegeeq-pg-failover-bootstrap-final-20261021.log`.
- `-Pintegration-tests`: `ConsulLeaseCoordinatorIntegrationTest` **61**,
  `PgFailoverMonitorIntegrationTest` **23**, `PgBootstrapIntegrationTest` **7**,
  `PgCtlProcessControlIntegrationTest` **10**, `PgHbaAdmissionGateIntegrationTest` **6**,
  `PgPrimaryElectorQueueingIntegrationTest` **4**, `PgRenewalLoopIntegrationTest` **13**.
  Total **124**, zero failures/errors/skips.
  Output: `logs/peegeeq-pg-failover-integration-bootstrap-20261021.log`.
- Default/core: `LocalCommandRunnerTest` **6**, `PgBootstrapRequestTest` **14**,
  `PgDeviceWatchdogTest` **7**, `PgLocalStateStoreTest` **29**, `PgNodeConfigTest` **17**.
  Total **73**. Output: `logs/peegeeq-pg-failover-core-bootstrap-20261021.log`.
- Guards in `peegeeq-test-support` (61 tests, including the five guard classes, 14): zero
  failures. Output: `logs/peegeeq-test-support-core-guards-bootstrap-20261021.log`.
- Known gaps: no operator authentication (OD-6); other members not inspected (OD-7); local
  receipts and the data directory not inspected for prior history; an interrupted bootstrap is
  refused, not resumed; scope B not implemented; no production entry point.

Phase 7b.2 bootstrap scope B increment B1 verification, 2026-10-22 (working tree, not committed):

- Red: the module's tests failed compilation before the classes existed
  (`logs/peegeeq-pg-failover-bootstrap-b1-red-20261022.log`). With the setting comparison and the
  quorum wait removed from the adapter, the missing-peer and override tests failed
  (`logs/peegeeq-pg-failover-bootstrap-b1-mutation-red-20261022.log`, `-red-2-`); the source was
  restored to the same SHA-256.
- Findings: a double quote inside a command argument is lost on Windows, so no argument in the
  module now contains one; an unquoted hyphenated standby name stops PostgreSQL at start. Plan
  §9.15.
- Rebuild: `mvn clean install -DskipTests -pl :peegeeq-pg-failover -am` passed.
  Output: `logs/rebuild-peegeeq-pg-failover-bootstrap-b1-final-20261022.log`.
- `-Pintegration-tests`: `ConsulLeaseCoordinatorIntegrationTest` **61**,
  `PgFailoverMonitorIntegrationTest` **23**, `PgBootstrapIntegrationTest` **7**,
  `PgCtlProcessControlIntegrationTest` **10**, `PgHbaAdmissionGateIntegrationTest` **6**,
  `PgPrimaryElectorQueueingIntegrationTest` **4**, `PgRenewalLoopIntegrationTest` **13**,
  `PgSqlDurabilityControlIntegrationTest` **10**. Total **134**, zero failures/errors/skips.
  Output: `logs/peegeeq-pg-failover-integration-bootstrap-b1-20261022.log`.
- Default/core: `LocalCommandRunnerTest` **6**, `PgBootstrapRequestTest` **14**,
  `PgDeviceWatchdogTest` **7**, `PgDurabilityPolicyTest` **12**, `PgLocalStateStoreTest` **29**,
  `PgLsnTest` **3**, `PgNodeConfigTest` **17**. Total **88**.
  Output: `logs/peegeeq-pg-failover-core-bootstrap-b1-20261022.log`.
- Guards in `peegeeq-test-support` (61 tests, including the five guard classes, 14): zero
  failures. Output: `logs/peegeeq-test-support-core-guards-bootstrap-b1-20261022.log`.
- Known gaps: nothing calls the port yet; a failed installation leaves its value in effect;
  coverage does not check client address, timeline, or `peegeeq.node_id`; the least-privilege
  supervisor role for `pg_stat_replication` is not defined; no test for a runner that returns no
  result; a failed fixture test can cascade through the class (§9.11).

Test counts are recorded here only from a run made during the task.
The final phase 7b.1 per-class summaries, and the phase 7b.2 summaries dated 2026-10-08, were
reread during the 2026-10-08 reconciliation and no runtime test was rerun then. On 2026-10-08
the four design documents were aligned with the optional-watchdog decision and the coordinator
port (P-38). That was a documentation change with no test run. The `peegeeq-pg-failover` counts
dated 2026-10-09 come from runs made on that day. Known gaps remain owner review of OD-1 to
OD-3, a production entry point for the renewal loop and bootstrap, bootstrap scope B,
takeover/re-join,
TLS/restore, independent exclusion under supervisor/VM pause, the cause of the intermediate
HTTP 403 of 2026-10-08, and recovery timing.

Completion requires that every requirement is met, that every scenario has a passing
real-container test with a recorded per-class count, that every finding is closed, and that the
runbook has saved output for every scenario.

### 9. Delivery-outcome redesign (Finding 20)

**Priority:** High
**Status:** ACTIVE — design approved 2026-10-12; phase 1 partial (increments 1a and 1b committed in `092cf23f`; 1b not approved)
**Objective:** replace the incomplete `REFERENCE_COUNTING` fan-out and the module-private
rejection semantics with one delivery-outcome contract across outbox, native, and database
modules. A rejection, a transient failure, and a processing failure each have a defined,
persisted result. No message is deleted, blocked without bound, or withheld without a log line.

The design is `docs-design/consumer-groups/PEEGEEQ_DELIVERY_OUTCOME_REDESIGN_PROPOSAL.md`
(sixth revision). The decisions OD-1 to OD-23, the implementation task list, the required order,
and the phase 1 run record are under "Finding 20" in the backlog section below. They stay there.
This task holds the status and the execution order.

Phases, one at a time, each verified before the next starts:

1. **PARTIAL — dead-group and graceful-stop safety.** Increments:
   - 1a `STOPPED` status and `stopGroup`: done and reviewed.
   - 1b lifecycle table, authoritative start bound (OD-23), REST settings update: implemented,
     not approved. The owner set nine corrections. Correction 1 is made; its last part, the
     subscription store, awaits review. Corrections 2 to 9 are not started.
   - 1c detection reads `ACTIVE` only; watermark and write validator count `ACTIVE`, `STOPPED`,
     `PAUSED`: not started.
   - 1d `DeadConsumerGroupCleanup` deleted with its callers: not started.
   - 1e in-process subscription heartbeat stops an instance on `STOPPED`, `DEAD`, `CANCELLED`:
     not started.
2. **OPEN.** Canonical schema provisioning: one definition for Flyway and runtime, with a
   real-PostgreSQL schema-equivalence test.
3. **OPEN.** Offset correctness and replay, including log replay.
4. **OPEN.** Reference-counting retirement and controlled migration (OD-2, OD-4).
5. **OPEN.** Outcome API and member dispatch.
6. **OPEN.** Queue exclusions and native queue outcomes.
7. **OPEN.** `PUB_SUB` outcomes.
8. **OPEN.** Cleanup path, release, and final schema deletion.

Release gates: affected modules pass, the branch is pushed, and a Jenkins build from `beginning`
passes. Task 8 phase 5 closes only when `peegeeq-db` passes in that build. Task 9 closes only when
outbox, native, and database delivery models all pass their contract tests.

Evidence so far: one whole-module run (`peegeeq-migrations`, increment 1a) and targeted runs
logged under `logs/f20-p1*`. No Jenkins build has run this work. The findings that motivate it
were static until phase 1 reproduced R-8.

## Unscheduled Product and Coverage Backlog

| Item | Current verified state | Next decision/work |
|---|---|---|
| Schema Registry | **PROPOSED — NOT IMPLEMENTED**; `SchemaRegistry.tsx` is a “coming soon” placeholder with no route in `App.tsx`; no backend exists | Approve product scope before implementation |
| Unrouted Management UI placeholder pages | `DeveloperPortal.tsx`, `QueueDesigner.tsx`, and `Monitoring.tsx` contain only “coming soon” text. `App.tsx` imports none of them and declares no route for them. `Header.tsx` still maps titles for `/schema-registry`, `/developer-portal`, `/queue-designer`, and `/monitoring` | Decide per page: approve product scope, or delete the page file and its `Header.tsx` title entry |
| Benchmarking enhancement | **PROPOSED — NOT IMPLEMENTED**; `docs-design/performance/PEEGEEQ_BENCHMARKING_ENHANCEMENT_IMPLEMENTATION_PLAN.md` (last updated 2026-09-18) holds 103 unchecked `BENCH-*` items and 0 checked | Approve scope, then add the approved phases to this register before implementation |
| Authentication and Authorization | Proposed; no auth module, JWT middleware, or tenant-management implementation exists | Define threat model and product boundary |
| TypeScript REST client coverage | Shared client is used by two Management UI pages (`AggregateStreamPage`, `CausationTreePage`). `PeeGeeQClient.test.ts` has nine cases: eight send requests over a real socket to the local `HttpTestServer` fixture, and one drives `streamEvents` through a hand-written `EventSource` replacement. No test runs the client against the PeeGeeQ REST backend | Add integration tests against the real REST backend; decide whether the `EventSource` replacement complies with the no-mocking rule |
| Failures logged at WARN outside `peegeeq-pg-failover` | Classified on 2026-10-09 at `ea045d7f`. The production sources of 12 modules hold 318 `warn` calls. 196 report a failed, refused, or lost operation that the code does not resolve. 26 report a condition the code resolves. 95 are notices. 1 relays a PostgreSQL warning and is a notice outside a commit and a failure during one. Of the 196, 134 are swallowed, 51 are returned to the caller, and 11 repeat with no bound and no escalation. All 318 have two reads. A text search of `debug`, `info`, and `trace` calls found 44 more failure sites below WARN: 34 swallowed, 10 returned. No level was changed. The unexpected-ERROR gate does not see WARN or lower, so no test declares these failures. The sites are listed in "Failure log level classification" below | Decide per module whether to raise the 196 to ERROR and add `@ExpectedErrorLog` to each test that causes one. A change in `peegeeq-db` needs the tests of every module that depends on it |
| Queue and stream defects confirmed by probe runs | Found 2026-10-09 at `ea045d7f`. Four defects were confirmed by runs against real PostgreSQL and are recorded as findings 3, 4, 5, and 9 under "Failure log level classification". A native consumer group member filter that throws deletes the message. A handler that always fails blocks an `OFFSET_WATERMARK` partition with no bound, no retry count, and no dead-letter row. A handler that exceeds the visibility timeout is redelivered with no bound, and `max-retries` does not apply. A WebSocket queue stream that is idle for 300 s drops every later message and stays open. The same day's runs added: a started consumer group with no member, or with a filter that returns false, also deletes the message (finding 3); the outbox consumer group blocks the same way (finding 4); a hanging handler starves every later message on the topic (finding 5); a stream that is quiet for its first 300 s never delivers (finding 9); an event posted with an unparseable `validTime` is stored with the current time and answered 201 (finding 18). Three more were confirmed by runs: a service manager with no Consul logs that it registered and reports `connected` (finding 11); a stopped database produced its first ERROR line after 10.9 s, from the depth cache and not from the health checks, which logged the refused connections at DEBUG (finding 15); and seven test log configurations hide production loggers from the unexpected-ERROR gate, confirmed in all seven modules (finding 17). Finding 16 did not reproduce under real connection loss. A scan found 17 catch blocks and 2 Future conversions that drop a failure with no log (finding 19). A run on 2026-10-10 at `e07142b2` confirmed that an outbox consumer group stops delivering an acceptable message while a rejected row with an earlier `created_at` and a higher id stays PENDING; this failed `OutboxConsumerGroupIntegrationTest.testGroupFiltering` in Jenkins build 17 (finding 20). No production code was changed | Decide the intended behaviour for each, then fix test-first. Finding 3 loses messages and finding 18 stores a wrong valid time; they come first. Finding 20 withholds a message with no log line and makes the release gate fail at random; it comes next |
| Jenkins: another job's Docker network changes blank Management UI pages | Seen 2026-10-10 on `http://192.168.137.32:8080`, build 18 of `PeeGeeQ` at `e07142b2`. The build passed. In the Management UI Playwright run 4 of 419 tests failed their first attempt and passed on retry: `visualization-scope-selector.spec.ts:25`, `consumer-groups-validation.spec.ts:91`, `message-browser.spec.ts:217`, and `message-browser-advanced-filters.spec.ts:97`. Each waited 10 s for the first element of the page. The four failure screenshots are the same blank white image, and the browser logged `net::ERR_NETWORK_CHANGED` three times before the fourth failure. The other 415 Management UI tests and all 246 Utilities UI tests passed at the first attempt. Build 6 of the `Qraft` job ran on the same node from 13:57:51 to 14:06:19 and created or removed a Docker network 27 times between 14:00:44 and 14:06:14. The Playwright report places the four failed attempts at 14:01:09, 14:02:14, 14:03:26, and 14:06:13; `Qraft` network events are at 14:01:15, 14:02:12, 14:03:25, and 14:06:14. The node has 4 executors and no lock spans jobs; the Lockable Resources and Throttle Concurrent Builds plugins are not installed. Seen again the same day in build 19 at `75160f41`, which also passed. The Playwright report gives the start time of every attempt. 425 attempts passed and 3 failed, all three with the same blank image: `event-stores-scope-filter.spec.ts:122` at 16:57:46 and again at 16:58:10, and `message-browser.spec.ts:145` at 16:59:28. Build 11 of `Qraft` created or removed a Docker network 27 times from 16:57:44 to 17:03:32, with events at 16:57:44, 16:58:15, and 16:59:30. No attempt failed in the 18 minutes of the run before the first event. No attempt failed during the 24 later events. Each of the 7 failed attempts in the two builds overlaps a network event or starts at most 2 s after one. The link is that match in time plus the browser error in the first build; it was not reproduced on purpose | Approved 2026-10-10: keep the 4 executors, because running jobs side by side is the purpose of the server, and make only the two conflicting stages wait for each other. Phase 1 is done in `75160f41`: the `Jenkinsfile` runs the `all` suite as `Full regression: Java modules` and `Full regression: UI modules`, and `scripts/ci/regression-stages.mjs` derives the Maven arguments of each stage (12 contracts in `scripts/ci/regression-stages.test.mjs`). Build 19, resumed at `peegeeq-integration-tests`, ran both stages and published 1,738 passing tests. Build 20, resumed at `peegeeq-utilities-ui`, ran no test in the Java stage, ran that one module in the UI stage, and published 1,082 passing tests. Phase 2 is done on the server, 2026-10-10 17:58: Lockable Resources `1560.va_b_cd589f23eb_` is installed and active, 93 plugins against 92 before, no plugin upgraded, no restart required, and the server's list of pipeline steps now contains `lock`. Phase 3: the `PeeGeeQ` UI stage holds the lock `host-network-interfaces` since `f3ab553e`. The same lock around the `Qraft` `Docker cluster tests` stage (`https://github.com/mraysmit/qraft.git`, branch `main`) is in that repository's working tree and was not on `origin/main` at 19:03 on 2026-10-10, so a normal `Qraft` build holds no lock until it is. Both directions were shown that day with a replay of the `Qraft` build that used the locked Jenkinsfile. `PeeGeeQ` build 21 held the lock and `Qraft` build 12 waited from 18:33:29 to 18:39:09; its first Docker network event, at 18:39:19, came after the last browser attempt had started at 18:39:05. `Qraft` build 13 held the lock from 18:48:25 to 18:53:40 and `PeeGeeQ` build 22 waited; its first browser attempt started at 18:54:28. Both `PeeGeeQ` builds published 1,082 passing tests and no browser attempt failed. Jenkins created the lock by itself; it needs no configuration. Open: put the `Qraft` Jenkinsfile change on `origin/main`. Research on 2026-10-10, after the lock was built, shows the lock treats a symptom. The cause is documented Chromium behaviour on Linux, not job concurrency: Chromium cancels every request in flight when any address changes on any interface of the host, it has no switch for this, and its bug 974711 is closed as WontFix. Docker gives each new host interface an IPv6 link-local address about 1 to 2 s after creating it, which matches the 2 s seen here. The published fix is the host setting `net.ipv6.conf.default.disable_ipv6=1`, which stops new interfaces from getting an IPv6 address; one project measured 0 events with it against 24 before. Those reports cover containers that start and stop. A new Docker network also puts an IPv4 address on a new bridge, and no source read covers that, so the effect on the `Qraft` stage is not known. Open: measure the address events on the Jenkins host with and without the setting; if they stop, delete the lock from both pipelines. Owner direction on 2026-10-10: use a different browser. Research the same day: Firefox reacts to the same event (Mozilla bug 1720833, open) and, unlike Chromium, has a switch for it, the preference `network.notify.changed`. In the working tree, not yet on Jenkins: both gate configs (`peegeeq-management-ui/playwright.config.ts`, `peegeeq-utilities-ui/playwright.config.ts`) run Firefox with that preference off, and the `Jenkinsfile` installs Firefox in place of Chromium. Local evidence is small: 10 Management UI tests passed on Firefox, and both configs still list 419 and 246 tests. Not known: how many of the 665 browser tests pass on Firefox, whether the Jenkins host has the operating-system packages Firefox needs, and whether Firefox with the preference off survives `Qraft`'s network changes; one utilities test grants Chromium-only clipboard permissions (`generator-output-degraded.spec.ts:151`). The gate then no longer tests in Chrome. Open: a Jenkins build of the UI stage with a `Qraft` build beside it; if no page load is lost, delete the lock from both pipelines. Known limits: a `Qraft` build can wait about 38 minutes at its Docker stage, and that wait counts against its 60-minute timeout; the `PeeGeeQ-Cache` job does not hold the lock; a Jenkins server without the plugin has no `lock` step, which the `all` suite now uses. **Container result, 2026-10-10 evening.** The Playwright CI guide runs browser tests in the image `mcr.microsoft.com/playwright`; a container has its own network interfaces. The Docker Pipeline plugin was installed on the server (`docker-workflow` 653, with `docker-commons` and `authentication-tokens`; 96 plugins, no restart). In the working-tree `Jenkinsfile` the UI stage runs in `mcr.microsoft.com/playwright:v1.60.0-noble` and holds no lock; it was run on Jenkins by replay. Build 28 (replay, checkout `b73da6b9`, Playwright configs of `f3ab553e`, so Chromium) passed: 1,738 published, Management UI 419 of 419 browser attempts passed at the first attempt, Utilities UI 246 of 246, no `ERR_NETWORK_CHANGED` line. `Qraft` build 17 ran beside it and made its 27 Docker network changes from 22:08:58 to 22:13:56, inside the Management UI run (22:06:02 to 22:23:57). Builds 18 and 19 lost 4 and 3 attempts in the same overlap on the host. Build 25 (replay, `f3ab553e`) passed the Utilities UI in the container. Build 27 (replay, `b73da6b9`, Firefox, in the container) failed: 12 Management UI tests, each waiting for the WebSocket or SSE status `Connected`, with the browser error `Firefox can't establish a connection to the server at ws://localhost:3000/ws/monitoring`; 255 tests did not run. Firefox on the host was not run. The container `Jenkinsfile` is on the branch since `c20f7a40`; the Firefox configs of `b73da6b9` are still there, so a normal build of that commit is expected to fail as build 27 did. In the working trees on 2026-10-11, not committed: both Playwright configs restored to their Chromium content of `f3ab553e`; the host browser install removed from the Rebuild stage; the lock and Firefox text removed from the CI guide; the lock removed from the `Qraft` `Jenkinsfile` (equal to its content at `f2119d3`) and from its two documents. Build 29 (replay of the working-tree `Jenkinsfile`, checkout `c20f7a40`, Chromium configs) passed with no browser installed on the host by the pipeline: Utilities UI 836 unit and 246 browser tests. Those changes are on the branches since `f318b68d` (PeeGeeQ) and `3579736` (`Qraft`). **Normal builds, 2026-10-11, at `f318b68d`.** Build 30 from `beginning` stopped in the Java stage at `peegeeq-db` on `HaProxyConnectionFailoverTest.testFailbackAfterPrimaryRecovery` (Task 8 phase 5); test-support 87 and api 228 passed, db ran 1,124 with that 1 failure, and no later module or UI stage ran. Build 31, resumed at `peegeeq-outbox`, passed in 71.9 minutes with 4,505 tests published and 0 failed: 14 Java modules with 0 failures, then the UI stage in the container, Management UI 128 unit and 419 browser tests, Utilities UI 836 and 246. All 665 browser attempts passed at the first attempt. `Qraft` build 20, with no lock, made its 27 Docker network changes from 18:16:14 to 18:21:13, inside the Management UI run (18:13:17 to 18:31:15). Closed. Left: a build from `beginning` cannot reach the UI stage while the `peegeeq-db` test is red; the Lockable Resources plugin is still installed and no pipeline uses it, and uninstalling it needs a Jenkins restart. Retries hide these failures in the build result |

### Failure log level classification, 2026-10-09

Four read-only review passes read the enclosing method of every `warn` call under
`src/main/java` at commit `ea045d7f`. A second read then covered all 196 failure sites: the
source around each site was read again and compared with the first reader's statement of the
trigger and of what the code does next. All 196 agree. Where the first read cited a line outside
that range, the cited line was read as well: the rethrow at `PgClientFactory.java:174`, the
periodic timers at `PartitionedConsumerEngine.java` 252 and 258, `PeeGeeQManager.java:908`, and
`HealthCheckManager.java` 266 and 272, the failure at `ZeroSubscriptionValidator.java` 127 to
129, and the release statement at `PgNativeQueueConsumer.java` 896 to 900.

The second read then covered the other 122 calls the same way. 121 agree with the first read
and keep their class. The unclear row is resolved; see below. A comparison of every `warn` call
in the sources with the classified rows found 318 on each side and no site missing or moved.
`peegeeq-pg-failover` is not in this list; its levels were corrected on 2026-10-09 (Task 8).

The audit above covers `warn` calls only. A text search then listed every `debug`, `info`, and
`trace` call in the same 12 modules whose message names a failure (fail, error, exception, could
not, unable, cannot, rejected, refused, timed out, lost). It found 103 calls. Each was read with
the lines around it. The search matches message text on the line of the call. A second scan
covered the calls whose message starts on a later line: 8 more calls name a failure. None is a
new failure site. Three are stop summaries that print a failure counter, one follows a failure
already logged at ERROR, and four are `OutboxConsumer` lines selected by the exception-type
test in finding 16. A call that names a failure in other words is in neither scan. Failure
paths that log nothing were scanned separately; see finding 19.

Of the 103, 44 report a failure. 34 are swallowed and 10 are returned to the caller. The other
59 are a second line for a failure already logged at WARN or ERROR on the same path (16), a
shutdown path guarded by a state flag (4), or not a failure (39). Paths are under
`src/main/java/dev/mars/peegeeq/` of the module. All 44 log at DEBUG.

- `peegeeq-rest` `rest/handlers/ManagementApiHandler.java`, 16 swallowed: 243, 277, 430, 592,
  700, 719, 857, 878, 971, 989, 1007, 1025, 1079, 1892, 1902, 2049. A failed read becomes 0 or
  an empty array and the response is built from it. This is finding 2 at a lower level.
- `peegeeq-rest` `rest/handlers/SystemMonitoringHandler.java`, 4 swallowed: 247, 716, 767, 876.
- `peegeeq-rest` `rest/handlers/ConsumerGroupHandler.java`, 3 swallowed: 211, 289, 573.
- `peegeeq-rest` `rest/handlers/WebSocketConnection.java`, 1 swallowed: 100. This is the drop in
  finding 9.
- `peegeeq-db`, 6 swallowed: `db/PeeGeeQManager.java` 337 and 919,
  `db/health/HealthCheckManager.java` 201 and 381, `db/performance/SystemInfoCollector.java`
  163 and 190.
- `peegeeq-db`, 3 returned: `db/consumer/PartitionedOffsetManager.java` 146 and 223 (a rejected
  offset commit returns false; the engine logs WARN at 431), and
  `db/resilience/CircuitBreakerManager.java` 105.
- `peegeeq-outbox`, 3 returned: `outbox/OutboxConsumer.java` 437 and 449,
  `outbox/OutboxConsumerGroupMember.java` 366.
- `peegeeq-native`, 1 swallowed and 1 returned: `pgqueue/PgNativeQueueObserver.java` 150,
  `pgqueue/PgNativeQueueConsumer.java` 1290.
- `peegeeq-service-manager`, 1 returned: `servicemanager/routing/ConnectionRouter.java` 175.
- `peegeeq-bitemporal`, 1 swallowed: `bitemporal/PgBiTemporalEventStore.java` 2044.
- `peegeeq-examples`, 1 swallowed: `examples/SSEErrorHandlingExample.java` 293.
- `peegeeq-benchmarking`, 1 swallowed: `test/metrics/PerformanceMetricsCollector.java` 452.
- `peegeeq-rest` `rest/PeeGeeQRestServer.java`, 2 returned: 564 and 570. A 4xx answer is logged
  at DEBUG. A comment at 557 to 559 states this as the rule.

Two of these decide the level by matching error text or exception type, with no check that a
shutdown is in progress. See findings 15 and 16.

Category rule. Failure: the call reports a failed, refused, or lost operation, unreadable or
undeliverable data, a resource that could not be released, or an unreachable dependency, and the
code does not make the operation succeed later. Handled: a bounded retry that escalates to ERROR
elsewhere, or a step that a comment or the coding principles declare optional. Notice: no
operation failed.

Each entry below is a failure site: a line number and what the code does after logging.
`S` is swallowed, so the caller sees success. `P` is returned to the caller. `R` is repeated with
no bound and no escalation. Paths are under `src/main/java/dev/mars/peegeeq/` of the module.

**`peegeeq-db`** — 113 calls: 68 failures, 6 handled, 38 notices, 1 unclear

- `db/client/PgClientFactory.java`: 164 P, 298 P, 312 P
- `db/config/MultiConfigurationManager.java`: 333 S, 358 S
- `db/config/PeeGeeQConfiguration.java`: 174 S, 460 S, 473 S, 610 S
- `db/connection/PgConnectionManager.java`: 267 P, 324 P, 391 P, 538 S, 610 P
- `db/consumer/PartitionedConsumerEngine.java`: 217 P, 276 R, 280 S, 368 R, 370 S, 431 S
- `db/deadletter/DeadLetterQueueManager.java`: 396 S
- `db/health/HealthCheckManager.java`: 306 R, 309 R, 385 R
- `db/metrics/PeeGeeQMetrics.java`: 453 S
- `db/PeeGeeQManager.java`: 300 P, 335 S, 466 S, 475 S, 485 S, 492 S, 503 S, 564 S, 928 R, 1077 S, 1084 S
- `db/performance/SystemInfoCollector.java`: 135 S, 247 S, 283 S
- `db/provider/PgConnectionProvider.java`: 133 S, 154 S
- `db/provider/PgDatabaseService.java`: 122 S, 154 S
- `db/provider/PgMetricsProvider.java`: 53 S, 62 S, 71 S, 80 S, 89 S, 98 S, 107 S, 116 S, 126 S, 135 S
- `db/setup/DatabaseTemplateManager.java`: 80 P
- `db/setup/PeeGeeQDatabaseSetupService.java`: 1122 P, 1141 S, 1231 P, 1238 P, 1906 S, 1908 S
- `db/setup/SqlTemplateProcessor.java`: 42 S
- `db/subscription/BackfillService.java`: 821 S
- `db/subscription/SubscriptionManager.java`: 191 P, 412 P, 594 P, 858 P
- `db/subscription/ZeroSubscriptionValidator.java`: 104 P
- `db/util/PostgreSqlIdentifierValidator.java`: 193 S

The unclear row is `db/connection/PgConnectionManager.java:724`. It relays a PostgreSQL server
warning. Outside a commit it is a notice. During a commit the same server warning fails the
commit; see finding 10.

Handled and notice rows that the level decision should look at. Each keeps its class under the
category rule.

- A message that used all its retries is moved to the dead letter queue with no ERROR line.
  `pgqueue/PgNativeQueueConsumer.java` logs WARN at 1001 and INFO at 1054.
  `outbox/OutboxConsumer.java` logs WARN at 788 for each failed attempt and INFO at 1013.
- Three handlers drop a single failed delivery and log WARN until the third consecutive
  failure, then ERROR: `bitemporal/ReactiveNotificationHandler.java:871`,
  `pgqueue/PgNativeQueueObserver.java:347`, and `outbox/OutboxQueueObserver.java:219`. Each
  counter resets on a success (856, 329, 201). The two observers are declared best-effort
  viewers in comments at 324 and 197.
- Three calls report that a requested setting was replaced by a default and the operation went
  on: `db/provider/PgQueueFactoryProvider.java:276` (an unknown preset name gives an empty
  preset), `servicemanager/routing/LoadBalancer.java:62` (two strategies are not implemented and
  use round robin), and `test/containers/PeeGeeQTestContainerFactory.java:201`.
- A group closed while it starts fails the start. `pgqueue/PgNativeConsumerGroup.java` logs WARN
  at 285 and DEBUG at 305. `outbox/OutboxConsumerGroup.java` logs WARN at 476 and ERROR at 496.
  The two modules give one condition two different levels.
- `db/provider/PgDatabaseService.java:144`: `runMigrations()` does nothing and returns success.
  The only callers are tests.

**`peegeeq-native`** — 36 calls: 30 failures, 5 handled, 1 notice

- `pgqueue/PgNativeConsumerGroup.java`: 290 P, 450 S, 514 P, 550 P, 711 P
- `pgqueue/PgNativeConsumerGroupMember.java`: 232 S
- `pgqueue/PgNativeMessages.java`: 73 S
- `pgqueue/PgNativeQueueBrowser.java`: 136 S
- `pgqueue/PgNativeQueueConsumer.java`: 232 P, 249 S, 293 R, 305 R, 309 S, 313 S, 331 P, 352 P, 356 S, 381 R, 401 S, 869 R
- `pgqueue/PgNativeQueueFactory.java`: 290 S, 306 S, 373 S
- `pgqueue/PgNativeQueueObserver.java`: 155 P, 212 S
- `pgqueue/PgNativeQueueProducer.java`: 203 P, 256 S, 270 S, 329 P
- `pgqueue/PgNotificationStream.java`: 143 S

**`peegeeq-outbox`** — 24 calls: 12 failures, 8 handled, 4 notices

- `outbox/OutboxConsumer.java`: 908 S, 1029 S
- `outbox/OutboxConsumerGroup.java`: 433 S, 451 S, 479 S, 604 P, 621 S, 697 S
- `outbox/OutboxFactory.java`: 361 S, 393 S, 446 P
- `outbox/OutboxQueueBrowser.java`: 146 S

**`peegeeq-bitemporal`** — 20 calls: 16 failures, 3 handled, 1 notice

- `bitemporal/PgBiTemporalEventStore.java`: 308 P, 344 P, 1694 P, 1702 S, 1710 S, 1715 P, 1730 P, 1734 P, 2392 P, 2480 P, 2487 S
- `bitemporal/ReactiveNotificationHandler.java`: 407 P, 425 P, 732 S, 737 S, 752 S

**`peegeeq-rest`** — 48 calls: 30 failures, 0 handled, 18 notices

- `rest/handlers/ConsumerGroupHandler.java`: 151 S, 153 S, 516 S, 758 S
- `rest/handlers/DatabaseSetupHandler.java`: 371 P
- `rest/handlers/EventStoreSSEConnection.java`: 133 S
- `rest/handlers/ManagementApiHandler.java`: 171 S, 348 S, 634 S, 792 S, 924 S
- `rest/handlers/QueueHandler.java`: 143 S, 301 S
- `rest/handlers/ServerSentEventsHandler.java`: 100 S, 140 S, 363 P
- `rest/handlers/SystemMonitoringHandler.java`: 260 P, 377 P
- `rest/handlers/WebhookSubscriptionHandler.java`: 210 S, 253 S, 372 S
- `rest/handlers/WebSocketConnection.java`: 88 S, 220 S, 231 S, 242 S
- `rest/handlers/WebSocketHandler.java`: 284 S, 356 S
- `rest/PeeGeeQRestServer.java`: 284 S, 291 P, 567 P

**`peegeeq-rest-client`** — 1 call: 1 failure

- `client/sse/SSEReadStream.java`: 171 P

**`peegeeq-service-manager`** — 14 calls: 8 failures, 3 handled, 3 notices

- `servicemanager/federation/FederatedManagementHandler.java`: 390 S, 402 S, 414 S, 426 S, 438 S
- `servicemanager/routing/ConnectionRouter.java`: 110 R
- `servicemanager/routing/LoadBalancer.java`: 43 P, 53 P

**`peegeeq-pg-sidecar`** — 2 calls: 2 failures

- `sidecar/PgPrimaryCheckVerticle.java`: 97 P, 108 S

**`peegeeq-test-support`** — 3 calls: 1 failure, 2 notices

- `test/base/PeeGeeQTestBase.java`: 153 S

**`peegeeq-benchmarking`** — 19 calls: 16 failures, 3 notices

- `test/hardware/HardwareProfiler.java`: 146 S, 183 S, 213 S, 261 S, 286 S, 318 S, 349 S
- `test/hardware/SystemResourceMonitor.java`: 248 S, 254 S
- `test/metrics/PerformanceMetricsCollector.java`: 111 S, 141 S, 172 S, 180 S, 477 S, 499 S, 533 S

**`peegeeq-examples`** — 6 calls: 3 failures, 3 notices

- `examples/ServerSentEventsConsumerExample.java`: 153 S
- `examples/SSEConnectionManagementExample.java`: 233 S
- `examples/SSEErrorHandlingExample.java`: 122 S

**`peegeeq-examples-spring`** — 32 calls: 9 failures, 1 handled, 22 notices

- `examples/springboot2/adapter/ReactiveOutboxAdapter.java`: 179 S
- `examples/springbootpriority/service/AllTradesConsumerService.java`: 316 S, 318 S
- `examples/springbootpriority/service/CriticalTradeConsumerService.java`: 113 S, 285 S, 287 S
- `examples/springbootpriority/service/HighPriorityConsumerService.java`: 114 S, 285 S
- `examples/springbootretry/service/TransactionProcessorService.java`: 109 P

Findings that a level change does not fix. Each states its evidence: a run, or reading only.
Findings 3, 4, 5, 9, 11, 12, 13, 15, 16, 17, and 18 were run on 2026-10-09 with temporary probe
tests against real PostgreSQL and real sockets. The probe sources are in `logs/probes-20261009/`
and the run logs are in `logs/`; neither is in the repository. No probe remains in a module.

1. `rest/handlers/ConsumerGroupHandler.java` 147 to 170: a failed subscription is answered 201
   with `subscriptionConfigured` true. Confirmed by the second read.
2. `rest/handlers/ManagementApiHandler.java` 346 to 352, 790 to 796, and 922 to 928: a read
   failure becomes an empty array and HTTP 200. Line 348 was confirmed by the second read.
3. `pgqueue/PgNativeConsumerGroupMember.java:232`: a member filter that throws deletes the
   message. This is message loss. Confirmed by a run
   (`logs/probe-native-filter-throws-20261009.log`, 1 test, 0 failures). One group, one member
   whose filter throws, one message. The filter ran once. The handler ran 0 times. The row left
   `queue_messages` within 266 ms of the send, and the consumer logged
   `Deleted processed message: 1`. `dead_letter_queue` held 0 rows after 15 s. Group counters:
   processed 0, failed 0, filtered 1. Nothing retried the message and nothing kept it. The path
   is member 231 to 234 (the catch returns false), group 755 to 762 (no eligible member, a
   succeeded Future), consumer 817 to 822 (`deleteMessage`). The only log line is the WARN at
   232, and the native test configuration does not print it (finding 17): the probe log holds no
   line from that logger. The same group branch deletes a message in two more cases, both run
   (`logs/probe-native-filter-variants-20261009.log`, 2 tests, 0 failures). With one member
   whose filter returns false, the row was deleted, the filter ran once, and the handler ran 0
   times. With a started group whose only member had been removed, the row was deleted with no
   filter call and no handler call; the group reported active with 0 active members. In both
   runs the consumer logged `Deleted processed message`, `dead_letter_queue` held 0 rows, and
   the group counters read processed 0, failed 0, filtered 1. A message sent while a group has
   no member is therefore lost, with no log line above DEBUG.
4. `db/consumer/PartitionedConsumerEngine.java` 363 to 374: a handler that always fails blocks
   its partition with no bound. Confirmed by a run
   (`logs/probe-native-partitioned-handler-failure-20261009.log`, 1 test, 0 failures). An
   `OFFSET_WATERMARK` topic held two messages on one partition key. The handler failed on every
   call. In 8.3 s the first message was delivered 9 times, once per second. The second message
   was never delivered. `committed_offset` stayed 0. `outbox.retry_count` stayed 0 on both rows.
   `dead_letter_queue` held 0 rows. One explicit run of
   `ConsumerGroupRetryService.processFailedMessages()` reported retried 0 and moved 0; that
   service reads `outbox_consumer_groups` rows and this mode wrote none. Each cycle logged WARN
   `Fetch failed for partition probe-partition: PROBE: handler failure`, so the text reports a
   fetch failure for a handler failure. The design states the replay and no bound:
   `docs-design/consumer-groups/PEEGEEQ_PARTITIONED_CONSUMPTION_DESIGN.md` 95 to 101 says the
   offset commits only if all handlers succeed and the next cycle replays from the last
   committed position. That document was read in full on 2026-10-09 and the other eight current
   documents that name the mode were searched for retry, dead-letter, poison, and block. None
   states a retry bound, a dead-letter step, or that a permanently failing handler is meant to
   block the partition. The design record lists "retry and dead-letter automation for fan-out
   processing" as complete (line 36); that is `ConsumerGroupRetryService`, which does not act
   in this mode. The only operational control the documents name is an alert on pending-offset
   age (design 130 to 131, `docs/PEEGEEQ_ORDERING_PATTERNS_GUIDE.md` 332 to 333).
   The outbox consumer group gives the same result
   (`logs/probe-outbox-partitioned-handler-failure-20261009.log`, 1 test, 0 failures): 9 calls
   on the first message in 8.2 s, the second message never delivered, `committed_offset` 0,
   `retry_count` 0, no dead-letter row. That run read 2 `outbox_consumer_groups` rows for the
   topic; the native run read 0. Their status was not read. In the outbox run
   `OutboxConsumerGroupMember` logged ERROR `Failed to process message` 9 times and the test
   passed, because that logger is detached (finding 17).
5. `pgqueue/PgNativeQueueConsumer.java` 813 to 815 and 859 to 900: a handler that exceeds the
   visibility timeout is redelivered with no bound. Confirmed by a run
   (`logs/probe-native-visibility-timeout-20261009.log`, 1 test, 0 failures). Configuration:
   visibility timeout 1 s, `max-retries` 2. The handler never settled. One message was delivered
   12 times in 12.1 s, once per second. `retry_count` was 0 at every reading.
   `dead_letter_queue` held 0 rows. Each expiry logged WARN
   `Message handler visibility expired for message 1; relinquishing the stale delivery`.
   `max-retries` does not apply on this path: the expired settlement returns at 813 to 815
   before `handleProcessingFailure`, and the release statement at 896 to 900 does not write
   `retry_count`. A handler that hangs on one message repeats its side effects every visibility
   timeout. It also starves the topic. Run
   (`logs/probe-native-hanging-handler-starvation-20261009.log`, 1 test, 0 failures): with the
   default single consumer thread, a hanging first message was delivered 12 times in 12.1 s
   and a second message sent after it was never delivered; its row stayed `AVAILABLE` with
   `retry_count` 0. `docs/PEEGEEQ_REST_API_REFERENCE.md` 2077 to 2085 states that a message
   "not acknowledged within visibility timeout (multiple times)" is dead-lettered and that
   `maxRetries` failures move a message to the dead letter queue. The runs contradict that
   statement for a visibility timeout.
6. `PeeGeeQManagerCloseLogLevelTest` and `PgBiTemporalEventStoreCloseLogLevelTest` say in
   Javadoc that close failures log at ERROR. Production logs them at WARN
   (`PeeGeeQManager.java` 466, 475, 485, 492, 503; `PgBiTemporalEventStore.java` 1694, 1702,
   1710, 1715).
   Each positive test asserts only that no WARN line with the close text exists. Confirmed by
   the 2026-10-09 run logs: both classes passed, and none of the cleanup-failure or close-failure
   messages was logged at any level. No close failure occurred in either test, so neither test
   checks the level. `PeeGeeQManagerCloseLogLevelTest` also imports `java.sql` (42 to 44, used at
   278) and builds its own containers (78, 167); both are banned patterns.
7. `db/config/PeeGeeQConfiguration.java`: an invalid integer or long value logs WARN (460, 473).
   An invalid duration logs ERROR (494). Confirmed by the second read.
8. `sidecar/PgPrimaryCheckVerticle.java:97`: a failed query and a healthy replica both answer
   HTTP 503. The WARN is the only signal that separates them. Confirmed by the second read.
9. `rest/handlers/WebSocketConnection.java` 86 to 112: a queue-stream connection that sends no
   data frame for 300 seconds drops every later message and stays open. Confirmed by a run
   (`logs/probe-rest-websocket-idle-drop-20261009.log`, 1 test, 0 failures, 341.7 s). A control
   message reached the client 826 ms after the REST send. After 305 s with no traffic, two more
   messages were sent 20 s apart. REST answered 200 for both. The client received neither. The
   server logged WARN `has been inactive for 305999 ms` and then `325997 ms`, one line per
   dropped message. The socket stayed open: the client close handler was not called, and an
   application `ping` sent afterwards was answered with `pong`. The server statistics at close
   read `Messages received: 3, sent: 1`. The client gets no close frame and no error frame, so
   it cannot detect the state; reconnecting is the only recovery. The limit is a literal at line
   87 with no configuration key. `lastActivityTime` is set in the constructor (57) and after a
   data send (107) and nowhere else. A stream on a queue that is quiet for its first 300 seconds
   therefore never delivers anything. Run
   (`logs/probe-rest-websocket-quiet-first-20261009.log`, 1 test, 0 failures, 340.5 s): no
   message was sent for 305 s after the stream reported `subscribed`; the first message and a
   second one 20 s later were both dropped; the server logged WARN `inactive for 306031 ms` and
   `326031 ms`; the socket stayed open and answered a `ping`; the server statistics at close
   read `Messages received: 2, sent: 0`. An earlier run of the first probe stopped on a defect
   in the probe after it had observed the same drop
   (`logs/probe-rest-websocket-idle-drop-run1-crashed-20261009.log`).
10. `db/connection/PgConnectionManager.java` 334 to 352: a commit whose outcome is unknown is
    not logged. A server warning during the commit, or a failed commit call, fails the Future
    with `PgCommitOutcomeUnknownException`. No production code logs that exception. Line 724
    logs the server warning at WARN, and only when a notice configuration is set (674 to 675).
    Run evidence, 2026-10-09: `PgConnectionManagerDurabilityIntegrationTest` ran 15 tests with
    0 failures. Five cases assert the exception. The log of that class holds no WARN line and
    no ERROR line. The caller receives the failure. Whether an operator sees it depends on the
    caller.
11. `servicemanager/PeeGeeQServiceManager.java` 100 to 109 and 199: a failed Consul registration
    is followed by a success line. Run on 2026-10-09 with no Consul listening
    (`logs/probe-consul-registration-failure-20261009.log`): the start logged WARN
    `Failed to register with Consul (continuing without Consul): Connection refused`, then INFO
    `Service Manager registered with Consul` 1 ms later. The deployment succeeded.
    `GET /health` answered 200 with `"consul":"connected"`. The health field tests only that the
    client object exists. A service manager with no Consul reports itself as registered and
    connected; the WARN line is the only true signal.
12. `rest/handlers/ManagementApiHandler.java` 1210 to 1217 and 1282 to 1289: the branch answers
    404 `Setup or queue not found` at WARN for every failure that is not a `ResponseException`.
    `getSetupResult` fails only with `SetupNotFoundException`
    (`PeeGeeQDatabaseSetupService.java` 1160 to 1167). Run
    (`logs/probe-rest-management-queue-404-20261009.log`, 1 test, 0 failures): `PUT` and
    `DELETE` for an unknown setup answered 404 `Setup or queue not found: Setup not found` and
    logged the WARN; for an unknown queue in a known setup they answered 404 `Queue not found`
    with no WARN; for an existing queue they answered 200; a second `DELETE` answered 404. The
    rest of each chain was read line by line: the queue factory map is the map built at setup
    time, and no other statement in either chain fails in a way that reaches the branch. The
    branch is broader than its one cause, and no run produced a second cause. Two facts from the
    same run and read: `PUT` with an empty body answered `configuration updated successfully`,
    and the handler applies nothing (1194 to 1202); `deleteQueue` discards the Future of
    `queueFactory.close()` at 1259, so a failed close is never seen. The branch had no test:
    the whole-repository run log of 2026-10-09 holds no line from it.
13. vertx-junit5 5.0.4 `VertxExtension.joinActiveTestContexts` (lines 171 to 173) returns at
    once when the test has already failed. An asynchronous `@AfterEach` is then not awaited and
    Vert.x is closed under it. Confirmed by the source and by a probe run
    (`logs/probe-teardown-after-failure-20261009.log`): the teardown of a passing test completed
    after 1.5 s; the teardown timer of a failing test got `CancellationException` 1 ms after it
    started. 244 test classes use `VertxExtension` with an `@AfterEach` that takes
    `VertxTestContext`. After a failed test, such a teardown can leave containers, pools, or
    child processes open, and the next test then fails for a second reason.
14. `servicemanager/PeeGeeQServiceManager.java` 53 to 54 reads `consul.host` and `consul.port`
    from system properties, and `PeeGeeQServiceManagerIntegrationTest.java` 47 to 48 sets them.
    The project rule bans system properties for configuration.
15. `db/health/HealthCheckManager.java` 366 to 387: an unhealthy result whose message contains
    `Connection refused`, `connection may have been lost`, `underlying connection`, or
    `Pool closed` is logged at DEBUG with the text `expected during shutdown`. The method does
    not test whether a shutdown is in progress. Its caller at line 300 runs on every health
    check cycle. Confirmed by a run (`logs/probe-health-check-outage-20261009.log`, 1 test,
    0 failures). A manager ran with a one-second health check interval. The PostgreSQL container
    was stopped and no shutdown was requested. In the next 8 s the `database`, `outbox-queue`,
    `native-queue`, and `dead-letter-queue` checks each logged the DEBUG line 4 times with
    `Connection refused`. The circuit breaker then opened and each check logged WARN
    `Health check failed: <name> - Circuit breaker open` 4 times. The other lines in the window
    were WARN: `Pool acquisition canary failed` twice, `Circuit breaker 'database' failure rate
    exceeded` once, and `Queue depth cache refresh failed (first failure)` once. No logger wrote
    an ERROR line during the outage, and the unexpected-ERROR gate passed the test.
    `manager.isHealthy()` returned false. A 60-second outage was then run
    (`logs/probe-health-check-long-outage-20261009.log`, 1 test, 1 failure raised by the
    unexpected-ERROR gate). The first ERROR came 10.9 s after the stop:
    `Queue depth cache refresh is still failing (3 consecutive failures, 3 total failures)`
    from `PeeGeeQManager`. It repeated at 6, 9, and 12 failures. That was the only ERROR text in
    60 s. `HealthCheckManager` logged no ERROR: each of the four database checks logged WARN
    `Circuit breaker open` 56 times, and the `Connection refused` DEBUG lines stopped once the
    breaker opened. `Pool acquisition canary failed` was logged at WARN 12 times.
16. `outbox/OutboxConsumer.java` 443 to 456 and 561 to 572: `isShutdownRelatedError` returns
    true when the cause chain holds `RejectedExecutionException` or `ClosedChannelException`,
    with the consumer still open. The catch block at 448 to 452 then logs DEBUG
    `Expected error during shutdown` and sets `closed` to true. `processAvailableMessages`
    returns at 307 to 309 without reading when `closed` is true. From reading, one such
    exception thrown synchronously in the claim path would stop the consumer for good with only
    a DEBUG line. A run did not reach that path
    (`logs/probe-outbox-connection-loss-20261009.log`, 1 test, 0 failures). 70 backends were
    terminated in 100 rounds under a subscribed consumer. A query on the same pool failed 50
    times with `io.vertx.sqlclient.ClosedConnectionException`, which is neither of the two
    types. Of 19 polls during the loss, 18 read normally and 1 failed; the consumer logged that
    one at ERROR twice (`Error querying messages` and `Reactive message processing failed`),
    logged no `Expected error during shutdown` line, and delivered a message sent afterwards.
    The statements inside the `try` block were read: the pool lookup returns a failed Future
    for every exception (`PgConnectionProvider.java` 65 to 89, `OutboxConsumer.java` 1117 to
    1137), and a throw inside a `compose` step becomes a failed Future. No statement in the
    block was found that can throw either type synchronously. The path is latent. The same
    test of exception type also selects DEBUG at 292, 437, 914, 956, 1018, and 1035, and
    selects WARN in place of ERROR at 824.
    The run passed although `OutboxConsumer` logged two ERROR lines, because that logger is
    detached in the module's test configuration (finding 17).
17. Seven test log configurations detach or switch off production loggers. The unexpected-ERROR
    gate attaches its capture appender to the root logger only
    (`UnexpectedErrorLogCaptureCoordinator.java` 99 to 104). A logger with `additivity="false"`
    does not pass events to the root logger, and a logger at `OFF` creates none. An ERROR from
    these classes cannot fail a test, and their WARN lines are not printed. Confirmed by a run in
    every one of the seven modules. Each run logged one undeclared ERROR per configured logger
    and one on an attached control logger. In every module the control failed its test with
    `Unexpected ERROR` and every configured logger passed unseen:
    `peegeeq-native` 3 tests, 1 failure (`logs/probe-detached-logger-gate-20261009.log`);
    `peegeeq-outbox` 3 tests, 1 failure; `peegeeq-rest` 3 tests, 1 failure; `peegeeq-db` 2
    tests, 1 failure; `peegeeq-bitemporal` 5 tests, 1 failure; `peegeeq-service-manager` 5
    tests, 1 failure; `peegeeq-examples` 2 tests, 1 failure (`logs/probe-gate-<module>-20261009.log`).
    Three other probe runs showed the effect on real paths: 9 ERROR lines from
    `OutboxConsumerGroupMember` (finding 4), 2 from `OutboxConsumer` (finding 16), and 1 from
    `EventStoreHandler` (finding 18) each left their test passing.
    - `peegeeq-native/src/test/resources/logback-test.xml`: `PgNotificationStream` is `OFF`
      (45); `PgNativeConsumerGroupMember` (67) and `PgNativeConsumer` (70) are detached. The
      member WARN lines 232 and 290 are therefore absent from every native test log.
    - `peegeeq-outbox`: `OutboxConsumer` (36) and `OutboxConsumerGroupMember` (43) are detached.
    - `peegeeq-rest`: `ConsumerGroupHandler` (25) and `EventStoreHandler` (28) are detached.
    - `peegeeq-db`: `PgQueueFactoryProvider` is `OFF` (86).
    - `peegeeq-bitemporal` (60, 63, 66, 69), `peegeeq-service-manager` (25, 28, 31, 34), and
      `peegeeq-examples` (26) detach outbox and native consumer loggers.
18. `rest/dto/EventRequest.java` 54 to 65 and `rest/handlers/EventStoreHandler.java:123`: an
    event posted with a `validTime` that is not a timestamp is stored with the current time as
    its valid time. This is silent replacement of business data in an append-only store. Run
    (`logs/probe-rest-event-valid-time-20261009.log`, 1 test, 0 failures). A request with
    `"validTime":"2020-01-01T00:00:00Z"` answered 201 and read back with that valid time. A
    request with `"validTime":"not-a-time"` also answered 201 `stored successfully`; it read
    back with a valid time equal to its transaction time, 2026-10-09T14:22:46.539431Z. No line
    was logged and the response carries no valid time, so the client cannot tell. The same text
    in `validFrom` answered 400 `Invalid request format` and stored nothing. The cause is a
    catch block that returns null for any parse failure, followed by a null check that falls
    back to `Instant.now()`. The correction request at `EventStoreHandler.java` 1070 to 1071
    has the same fallback; it was not run. `validTo` is accepted in the request and
    `storeEvent` never reads it.
19. Failure paths that log nothing. A scan of the production sources of 15 modules on
    2026-10-09 (all except `peegeeq-pg-failover`) listed 29 catch blocks with no log call, no
    throw, and no hand-off of the failure, and 31 `transform` lambdas that return success with
    no log call inside. Each was read. 12 of the 29 catch blocks log through a helper, pass the
    failure on, or handle an interrupt. The other 17 drop the failure:
    - six empty catch blocks: `api/logging/VertxSpanIdConverter.java:60`,
      `api/logging/VertxTraceIdConverter.java:49`, `rest/handlers/ManagementApiHandler.java`
      1073 and 2189 (around `browser.close()`), and `rest/handlers/SystemMonitoringHandler.java`
      200 and 216;
    - five that put a value in place of the failure: `rest/dto/EventRequest.java:60`
      (finding 18), `db/provider/PgConnectionProvider.java:122` (`hasClient` returns false for
      any exception), `rest/handlers/QueueHandler.java:571`,
      `bitemporal/ReactiveNotificationHandler.java:292`, and
      `test/metrics/PerformanceSnapshot.java:227`;
    - six that replace an invalid numeric request parameter with the default and answer no
      400: `rest/handlers/DeadLetterHandler.java:333`, `rest/handlers/EventStoreHandler.java`
      354 and 358, `rest/handlers/ServerSentEventsHandler.java:393`, and
      `rest/handlers/SystemMonitoringHandler.java` 1121 and 1131.
    Of the 31 `transform` lambdas, 15 follow an `onFailure` that logs; these are WARN sites in
    the list above. Four log through a helper or pass the failure on. Two follow logging
    handlers in `db/health/HealthCheckManager.java` (314, 324). Two return success for a
    failure with no log anywhere: `rest/handlers/ManagementApiHandler.java:1933` (a failed
    subscription listing becomes an empty list) and `rest/handlers/SubscriptionHandler.java:153`
    (a failed read after a subscribe becomes null and the response reports success). Eight in
    `db/health/HealthCheckManager.java` (535, 555, 568, 596, 616, 640, 660, 689) turn a failed
    check into an unhealthy status that keeps only the exception message; finding 15 shows how
    that text then selects the level. The six `onComplete` handlers in production sources were
    read and each logs or passes the failure on. The scan matches `catch`, `transform`, and
    `onComplete` only. A Future that is created and never observed is not in it;
    `queueFactory.close()` at `ManagementApiHandler.java:1259` is one that was seen.
20. `outbox/OutboxConsumer.java` 373 to 378, 388, 416, and 851, found 2026-10-10 at
    `e07142b2`: an outbox consumer group stops delivering an acceptable message for as long as
    a rejected row with an earlier `created_at` and a higher id stays PENDING. The claim
    statement scans in `created_at, id` order (388). After a filter rejection the consumer
    keeps the rejected row's id as its scan position (851), and the next claim adds
    `id > position` (373 to 378). The position is an id and the scan order starts with a
    timestamp, so the two disagree whenever a row has the earlier `created_at` and the higher
    id. Each scan then excludes the acceptable row. An empty scan resets the position to 0
    (416) and the next scan claims the rejected row first again. `OutboxProducer` takes
    `created_at` from the JVM clock (`OutboxProducer.java` 207, 336, 434) and PostgreSQL
    assigns the id at insert, so concurrent sends, and producers whose clocks differ, create
    that order. The default configuration claims one row per poll
    (`peegeeq.consumer.threads=1` in `peegeeq-default.properties`), which is the case that was
    run. Run (`logs/probe-outbox-filtered-scan-order-20261010.log`, 3 tests, 1 failure; source
    in `logs/probes-20261010/`). Two rows, "Keep" with id 53 and "Drop" with id 54, a group
    filter that accepts only "Keep", and "Drop" moved one minute earlier: in 10 s "Keep" was
    delivered 0 times and "Drop" was rejected 21 times; both rows stayed PENDING. The control
    with the same rows in insert order delivered "Keep". 50 concurrent sends from one producer
    over a pool of 3 connections left one pair in that order (ids 22 and 21). This is the cause
    of the `OutboxConsumerGroupIntegrationTest.testGroupFiltering` timeout in Jenkins build 17
    on 2026-10-10: the handler ran 4 times in 20 ms and the fifth accepted message did not
    arrive in the next 29 s. The log calls on this path are DEBUG (`OutboxConsumer.java` 417,
    776, 852; `OutboxConsumerGroup.java` 865), and the row is not a failure, so no retry count
    changes and no dead-letter row is written. The tests `groupRejectionsDoNotStarveLaterMessages`
    and `memberRejectionsDoNotStarveLaterMessages` send one message at a time, so id order and
    `created_at` order agree and they pass. A larger claim has the same defect when the
    rejected rows that sort first fill it. Run
    (`logs/probe-outbox-filtered-scan-order-batch-20261010.log`, 2 tests, 1 failure) with
    `peegeeq.consumer.threads=3`, "Keep" with id 5, and three "Drop" rows with ids 6 to 8
    moved one minute earlier: in 10 s "Keep" was delivered 0 times and the three rows were
    rejected 82 times in total. The control in insert order delivered "Keep". Each pass
    updates a rejected row to PROCESSING and back to PENDING, with no bound on the number of
    passes. No production code was changed.
    Scope, corrected 2026-10-11: the defect is in the reset-and-rescan path of `OutboxConsumer`
    after a group-level reset, from either `RejectedMessageException` or
    `MessageFilteredException`. Plain consumers keep the position at zero. `OFFSET_WATERMARK`
    groups use `PartitionedFetcher`, a separate fetch path ordered by id.
    **Owner ruling, 2026-10-11.** A group-filter rejection is permanent for that group. It is
    not a global dead-letter outcome; other groups stay eligible. Required treatment per
    outcome: group predicate returns false, persist `REJECTED` for `(message_id, group_name)`;
    group predicate throws, a processing failure with bounded retry and dead letter; no
    eligible member, or the selected member disappears, transient, reset to `PENDING` and use a
    `(created_at, id)` scan position compared in the claim's own order and reset after an empty
    scan; handler succeeds, `COMPLETED`; handler exhausts retries, `DEAD_LETTER`. `REJECTED`
    counts toward the group's terminal obligation and toward retention, and is not successful
    processing: the terminal set is `COMPLETED`, `REJECTED`, `DEAD_LETTER`. A filter change
    applies to later messages only; reconsidering old ones needs an explicit replay operation.
    Contradictions the ruling settles or exposes, none fixed yet:
    `groupRejectionsDoNotStarveLaterMessages` in `OutboxConsumerGroupIntegrationTest` changes
    the group filter and expects earlier rejected messages to be delivered, so it must change;
    `OutboxConsumerGroup.java:850` turns a thrown group predicate into a permanent rejection;
    cleanup recognises only `COMPLETED`, so a `REJECTED` or `DEAD_LETTER` row blocks it; the
    fan-out design says `REFERENCE_COUNTING` has no production completion writer; in
    `OFFSET_WATERMARK` a permanent rejection must advance the group offset and a transient one
    must not. Read on 2026-10-11: no file under `peegeeq-outbox/src/main` names
    `outbox_consumer_groups`, and that table's status check in `V001` allows no `REJECTED`
    value. This is a design change, not a cursor correction. Next: the data model and
    contracts, written into the fan-out design, before any code.
    Three contracts are kept separate in that design (owner, 2026-10-11): topic delivery
    semantics, progress model, and rejection state. A permanent rejection means: on a `QUEUE`
    topic, exclude the rejecting group while other groups compete, and the first successful
    group completes the shared message; on `PUB_SUB` reference counting, record that group's
    terminal outcome and include it in aggregate completion; on `OFFSET_WATERMARK`, record the
    terminal outcome and advance that group's partition offset. A queue exclusion ledger does
    not depend on reference-count fan-out. The design must also say what happens when every
    known queue group has rejected a message, and whether a group created later may claim it.
    The open-ended reading record that stood here was replaced on 2026-10-11, at the owner's
    instruction, by the ordered task list in "Finding 20 implementation tasks" below. The
    evidence it held is carried by the consolidated design review in
    `docs-design/consumer-groups/PEEGEEQ_DELIVERY_OUTCOME_REDESIGN_PROPOSAL.md`. That file is
    a proposal. The owner had it moved out of the implemented-contract document on 2026-10-12.

The per-row tables, with the trigger and the evidence line for all 318 calls, were produced in
the review session and are not in the repository.

#### Finding 20 implementation tasks

Set by the owner on 2026-10-11. The tasks are ordered.

1. **Complete the design input.**
   Read the remaining listed files without interim task-register updates. Produce one consolidated design review.

2. **Define the authoritative data model.**
   Specify the source of truth for queue exclusions, per-group terminal outcomes, offsets, global message status, and retention. Decide whether `completed_consumer_groups` is removed and derived. Delete the unused bitmap design.

3. **Resolve the two queue decisions.**
   Define what happens when every known group rejects a queue message. Define whether a group created later may claim it.

4. **Retire incomplete `REFERENCE_COUNTING` fan-out.**
   Disable it for new topics and use `OFFSET_WATERMARK` for pub/sub. Keep shared-status processing for queue topics. Do not complete the abandoned per-message fan-out implementation.

5. **Reconcile schema provisioning.**
   Establish one canonical schema definition. Make Flyway and runtime provisioning produce the same tables, columns, constraints, indexes, functions, and triggers. Add a real-PostgreSQL schema-equivalence test.

6. **Define a shared delivery-outcome contract.**
   Put permanent rejection, transient unavailability, processing failure, success, and retry exhaustion in a module visible to outbox, database, and native implementations. Remove module-private exception semantics from the cross-module boundary.

7. **Enforce topic mode at startup.**
   Every consumer-group start path must read the stored topic semantics. A lookup failure must fail startup. A topic with no configuration row is a `QUEUE` topic (OD-1, ruled 2026-10-12; the first wording said missing configuration fails startup). No path may silently switch to shared-status delivery.

8. **Fix member dispatch before persistence changes.**
   Evaluate each member filter once per delivery. Treat capacity as admission or backpressure, not a handler failure. Add failing tests for the double-filter completion defect and capacity-triggered dead-letter defect first.

9. **Implement queue rejection state.**
   Persist the rejecting group’s exclusion. Keep the shared message eligible for other groups. Apply the queue decisions from task 3. Use `(created_at, id)` only for transient scan progress.

10. **Implement offset outcomes.**
    Permanent rejection advances the group offset. Transient unavailability does not. Processing failure receives bounded retry and dead-letter handling. Earlier successful messages must not run repeatedly because a later message failed.

11. **Align native consumer groups.**
    Apply the same outcome and filter contracts to native groups and members. Add parity tests across native and outbox implementations.

12. **Implement one cleanup path.**
    Remove the duplicate unused cleanup definition. The retained production path must understand each delivery model and every terminal outcome. Wire and test its scheduler or caller.

13. **Verify one phase at a time.**
    For each implementation phase: write the failing tests, make the change, rebuild the affected reactor slice through `Tee-Object`, run the smallest tagged test scope, inspect per-class counts, update the design and task register, then stop.

14. **Close the release gates.**
    Run the affected modules. Push the branch. Run Jenkins from `beginning`. Close failover Task 8 item 5 only after `peegeeq-db` passes. Close finding 20 only after all three delivery models pass their contract tests.

The immediate task is **1–4 as one design phase**. Stop after the design document and phased test plan are ready for review. No production code should change in that phase.

#### Finding 20 required order

The owner reviewed the proposal three times on 2026-10-12 and set the lists below. The
order from the first review is superseded. The task definitions above still apply.

Design task list from the second review. Done in the fourth revision of the proposal.

1. Correct the completion, reading, execution, and changed-file statements.
2. Apply OD-1 through OD-16.
3. Define the queue, log, and per-group dead-letter replay contracts.
4. Correct the migration order. Move cleanup-field and watermark deletion after their
   replacements.
5. Remove the automatic unroutable expiry fields and rule.
6. Reissue the data model and migration matrix for one final review.
7. Stop.

Final design task list from the third review. The fourth revision was not approved: five
contract conflicts remained.

1. Add `STOPPED` and revise the lifecycle table.
2. Restrict keyed replay and correct the other-group guarantee.
3. Add migration fencing and the plain-consumer semantics rule.
4. Move backfill and tracking-table retirement to phase 4.
5. Define the operator dead-letter transaction.
6. Correct the shared-versus-group dead-letter wording.
7. Reissue only those sections and stop.

Implementation, after approval, one verified phase at a time.

1. Dead-group and graceful-stop safety.
2. Canonical schema provisioning.
3. Offset correctness and replay.
4. Reference-counting retirement and controlled migration.
5. Outcome API.
6. Queue exclusions.
7. `PUB_SUB` outcomes.
8. Cleanup, release, and final schema deletion.

The owner also ruled: do not expand the reading set again; no further reading is required;
no production work starts before the correction pass is approved.

Fourth review, 2026-10-12. The owner approved corrections 1 to 6, ruled on OD-22, and
required one migration correction. The ruling: apply these to the proposal and this
register, treat design task 7 as complete, and proceed to phase 1. No further design
ruling is required.

Status, 2026-10-12, sixth revision of the proposal.

- **Design phase: complete.** The owner approved the design in the fourth review. The
  sixth revision of
  `docs-design/consumer-groups/PEEGEEQ_DELIVERY_OUTCOME_REDESIGN_PROPOSAL.md` applies the
  OD-22 ruling and the migration correction.
  - `stopGracefully()` is group-wide and sets `STOPPED`. `STOPPED` refuses claims,
    fetches, and heartbeats, and a running instance that meets it stops. `close()` and
    `stop()` are instance-local and change no status.
  - A claim or a fetch on a topic whose semantics changed fails with an explicit
    semantics-mismatch error. The consumer logs it and stops.
- **Task 1 is not complete under its read-in-full condition.** 211 test files were read as
  a filtered print that left out Javadoc and log statements below ERROR and cut long
  lines. Other files are read in part. The proposal's Reading Record states what was read
  and how. The reading set is not expanded again.
- **Findings are static.** None was reproduced at run time before implementation began.
  Phase 1 reproduced one (R-8).
- **Phase 1, dead-group and graceful-stop safety: increment 1a of five is done.
  Increment 1b is not approved.** The owner reviewed 1b on 2026-10-12, found eight
  blocking defects, ruled on four questions, and set nine corrections. Correction 1 is
  made. Its last part, the subscription store, follows a second ruling of 2026-10-12 and
  awaits review. Corrections 2 to 9 are not started. See "Finding 20 phase 1" below.

#### Finding 20 decisions

The IDs in the text (for example X-4, P-6, T10-8, MIG-1) are defined in the proposal.

Ruled by the owner on 2026-10-12. OD-1 to OD-16 were ruled in the second review, OD-17 to
OD-21 in the third, and OD-22 in the fourth. Each of those rulings is applied in the
sixth revision of the proposal. OD-23 was ruled in the review of phase 1, increment 1a.
The proposal states it. Increment 1b implemented it.

| ID | Question | Ruling |
|---|---|---|
| OD-1 | Does a consumer group on a topic with no `outbox_topics` row start on the queue path, or refuse to start? | Approve. No topic row means `QUEUE`. A query failure fails startup. |
| OD-2 | What must happen to a stored `PUB_SUB` plus `REFERENCE_COUNTING` row, and to a stored `QUEUE` plus `OFFSET_WATERMARK` row, before `completion_tracking_mode` is removed? | Approve. Stop the migration and name every inconsistent topic. Require explicit operator resolution. |
| OD-3 | A new `outbox_group_exclusions` table and drop `outbox_consumer_groups`, or keep that table and add status `REJECTED`? | Approve. Create the exclusion table. Drop the old tracking table only after every reader, writer, trigger, and test dependency is removed. |
| OD-4 | Remove backfill? | Modify. Remove backfill only after the replacement replay operations are implemented and tested, not merely designed. Amended in the third review: "My earlier OD-4 condition was too broad. Queue replay and per-group dead-letter replay do not replace backfill. Log replay does." After phase 3 implements log replay, phase 4 removes `BackfillService` and its public surface, the six backfill columns, and `outbox_consumer_groups` with its creation trigger, after its remaining dependencies are removed. |
| OD-5 | Does this release permit a source-incompatible and binary-incompatible change to `peegeeq-api`? | Approve breaking API changes for this redesign. Record them as breaking changes. Delete `RejectedMessageException` when its replacement is complete. |
| OD-6 | Release per partition key, with `PAUSED` groups holding retention, and remove `outbox_topic_watermarks`? | Approve per-partition-key release. `PAUSED` subscriptions hold retention and are exempt from dead detection. Drop topic watermarks only after the replacement release path passes. |
| OD-7 | On a `PUB_SUB` topic a rejection writes no per-message row. Accept that? | Approve no per-message rejection row for `PUB_SUB`. Design and implement all three replay operations before removing backfill. The second sentence is replaced by the amended OD-4. |
| OD-8 | The unroutable rule for a queue message that every known group has excluded: which setting, and which clock? | Reject. Do not dead-letter based on an incomplete consumer registry. Keep excluded messages pending until explicit replay or operator action. |
| OD-9 | When is reference counting retired? | Amend. Retirement remains step 7, but counters, cleanup fields, and topic watermarks stay until their replacements are implemented in the cleanup phase. |
| OD-10 | Run the dead-group red tests early and make a narrow fix? | Approve. Add each red test and its narrow production correction in the same phase. Do not leave committed phases red. |
| OD-11 | Are P-13 to P-15 fixed before retirement? | Approve. Fix P-13 through P-15 before moving all `PUB_SUB` topics to offsets. |
| OD-12 | What does `stopGracefully` do to the subscription of a group started with options? | Reject the recommendation. Graceful stop sets the subscription to `PAUSED`, performs no cleanup, and preserves bounds and offsets. Restart reactivates it. `cancel` remains explicit and terminal. The status was changed to `STOPPED` by OD-18. |
| OD-13 | May `createTopic` or `updateTopic` change the semantics of an existing topic? | Modify. Normal create/update APIs cannot change established semantics. Provide a separate administrative migration operation that verifies both message stores, subscriptions, offsets, and assignments. The third review added: the operation must lock the message, subscription, and assignment tables in a fixed order before checking and changing semantics, with a lock timeout; every continuing consumer must also enforce semantics after the change. The fourth review added: a continuing consumer must not silently receive zero rows after its topic changes semantics; an empty result must mean that no eligible message exists; the queue claim and the offset fetch must fail with an explicit semantics-mismatch error, and the consumer must log the error and stop. |
| OD-14 | Do the hand-written test schemas move to a provisioning path? | Approve. Move the hand-written schemas to the canonical Flyway test path. |
| OD-15 | May the native queue API serve a `PUB_SUB` topic? Raised by the owner. | The native queue API is `QUEUE`-only. It must reject `PUB_SUB` until a separate native pub/sub producer and store contract exists. The producer currently writes `queue_messages`, while the offset path reads `outbox`. |
| OD-16 | May a heartbeat revive a `DEAD` subscription? Raised by the owner. | `DEAD` must not become `ACTIVE` from a heartbeat. Reactivation requires an explicit start or replay decision. |
| OD-17 | Do `stop()` and `close()` leave the subscription status unchanged? | Modify. `stop()` stays status-neutral. A planned `close()` uses the graceful-stop contract and preserves retention. Withdrawn in the fourth review: "My earlier OD-17 ruling was wrong. It conflated an instance close with a group lifecycle transition." `close()` and `stop()` are instance-local and leave the subscription status unchanged. |
| OD-18 | Is it accepted that a start reactivates a subscription that an operator paused? | Reject. Add `STOPPED`; an operator `PAUSED` state survives restart. |
| OD-19 | Is an explicit operator dead-letter of one queue message added? | Approve, with the atomic contract: accept the store, topic, and message ID; lock the message; refuse a message currently `PROCESSING` or `LOCKED`; insert a global dead-letter row; set an outbox row to `DEAD_LETTER` with `processed_at`, or delete the native row; perform the change atomically; test a concurrent claim. |
| OD-20 | Does `message_retention_hours` also delete `DEAD_LETTER` rows of a queue topic? | Approve. `message_retention_hours` deletes terminal outbox `DEAD_LETTER` rows while retaining their DLQ records. |
| OD-21 | Reprocessing a per-group dead letter replays the partition key from that message. Accepted? | Approve repeated delivery of later messages to the replayed group. Remove the unconditional claim that no other group can receive reopened rows. |
| OD-22 | Five cells of the lifecycle table that the `STOPPED` ruling left open. | Stop or close on `PAUSED`: accept, it stays `PAUSED`. `pause` on `STOPPED`: accept, it becomes `PAUSED`. `resume` on `STOPPED`: accept, it fails. Heartbeat, claim, and fetch on `STOPPED`: reject, they must fail. `close()` follows graceful stop: reject, `close()` leaves the shared subscription status unchanged. The contract: `stopGracefully()` is group-wide and sets `STOPPED`. `STOPPED` refuses claims, fetches, and heartbeats; a running instance that encounters it stops. `start()` changes `STOPPED` to `ACTIVE`. `close()` and `stop()` are instance-local and leave the subscription status unchanged. A planned whole-group outage must call `stopGracefully()` or `pause`. If all instances only close, the unchanged `ACTIVE` row eventually becomes `DEAD`. |
| OD-23 | What does an update of subscription options do to an existing start bound? Raised in phase 1, increment 1a. | Approve the recommendation with this complete contract. The stored bound is authoritative. The first subscription creates the bound. A later `subscribe` or start never changes it. A start uses the stored bound on both outbox and native paths; this resolves N-11. Replay is the only operation that can move an existing bound. The REST endpoint may create a missing subscription. For an existing subscription, the REST endpoint updates only explicitly supplied heartbeat settings. A REST update never changes lifecycle status. An omitted `startPosition` means no requested bound change. An explicitly supplied equivalent bound is accepted. A different bound returns HTTP 409. `FROM_NOW` on an existing subscription returns 409 because it does not identify the stored numeric bound. The response returns the persisted options, not the submitted object. Do not implement this by calling `subscribe` from the update path. Add a distinct settings-update operation. |

Notes on three rulings. OD-9 names the earlier step numbers; in the eight implementation
phases retirement is phase 4 and the cleanup phase is phase 8. For OD-12 the rejected
recommendation was to leave the subscription row unchanged at a graceful stop. For OD-18 the
rejected recommendation was to accept that a restart ends an operator's pause. For OD-22
the two rejected choices were that `STOPPED` allows a heartbeat, a claim, and a fetch, and
that `close()` follows the graceful-stop row.

Approved by the owner in the third review.

- `DeadConsumerGroupCleanup` is deleted with its callers in phase 1.
- Member dispatch stays in phase 5.
- Native queue outcomes stay in phase 6.
- A normal restart does not overwrite the stored start bound.
- The write validator counts `ACTIVE`, `PAUSED`, and `STOPPED`.

No decision of the design review awaits a ruling. The owner ruled on 2026-10-12 that no
further design ruling is required. Phase 1 raised one question after that ruling, and the
owner ruled on it in the review of increment 1a (OD-23): "No further ruling on the bound
contract is required."

The owner's review of increment 1b raised one more question: a subscription created
through REST for a native queue stored an `outbox` message id, and a generic subscription
creation had no way to learn the message store. The owner ruled on it on 2026-10-12. The
caller of `subscribe` names a typed `SubscriptionStore`, with no default. A queue factory
declares its store, and REST derives the store from the queue factory. No discriminator
is stored. A queue with subscriptions keeps its implementation type. `queueName` is the
topic of a REST consumer group. The ruling and the record of the correction are in
"Finding 20 phase 1", "Owner ruling on defect 1".

Two matters found in that correction have no ruling. Adding a queue name that exists
fails at the `CREATE TRIGGER` of the queue template, so a queue deleted through REST
cannot be created again. And six choices made inside the store ruling are listed in the
record for the owner.

#### Finding 20 phase 1

Dead-group and graceful-stop safety. The tests and the correction are in the proposal,
"Phase 1". This section records what was run. A statement here comes from a log named
beside it. A log of increment 1a is `logs/f20-p1a-<name>-20261012.log`. A log of
increment 1b is `logs/f20-p1b-<name>-20261012.log`. A log of the corrections after the
review of 1b is `logs/f20-p1b2-<name>-20261012.log`. A log of the subscription store
part of correction 1 is `logs/f20-p1b3-<name>-20261012.log`. The tables give `<name>`.

State, 2026-10-12. Increment 1a is done. The owner reviewed it, and the three corrections
the review required are made and verified by targeted runs. Increment 1b is implemented
and is not approved. The owner's review of it set nine corrections. Correction 1 is made.
The owner approved its first part and ruled on its last part, the subscription store.
That part is implemented and awaits review. Corrections 2 to 9 are not started.
Increments 1c to 1e are not started. Phase 1 is not complete. One whole-module run was
made, of `peegeeq-migrations`, in increment 1a. No Jenkins build was made.

Increments. Each leaves the tree green.

| Increment | Content | State |
|---|---|---|
| 1a | Status value `STOPPED`. `SubscriptionService.stopGroup`. `stopGracefully` stores `STOPPED` and runs no cleanup. A start makes a stopped subscription `ACTIVE`. | Done |
| 1b | The lifecycle table in the SQL of `SubscriptionManager` (L-1, L-2, L-8, L-10). The start bound and the REST settings update (OD-23: L-4, N-11, and the delivery part of R-7). The four REST handler corrections that the owner's review of 1a lists. | Not approved. Nine corrections. The first is made, and its last part awaits review. |
| 1c | Detection reads `ACTIVE` only. The watermark and the write validator count `ACTIVE`, `STOPPED`, and `PAUSED` (L-3, L-6, T10-10, R-7 on an offset topic). | Not started |
| 1d | `DeadConsumerGroupCleanup` loses its callers and is deleted (R-1, R-3). | Not started |
| 1e | The in-process subscription heartbeat. An instance stops on `STOPPED`, `DEAD`, or `CANCELLED` (R-2, L-5, L-7, L-9, R-9). | Not started |

Increment 1a, what changed.

- `SubscriptionState` (`peegeeq-api`) and `SubscriptionStatus` (`peegeeq-db`) gain
  `STOPPED` as the fifth value.
- `SubscriptionService.stopGroup(topic, groupName)` is new. `SubscriptionManager`
  implements it: an `ACTIVE` subscription becomes `STOPPED`; every other status is left
  unchanged; no row fails with "Subscription not found". It runs no cleanup and changes no
  start bound.
- `OutboxConsumerGroup.stopGracefully` and `PgNativeConsumerGroup.stopGracefully` call
  `stopGroup`, not `cancel`. When the status update fails, the outbox group now stops and
  returns the failure, as the native group did (N-10).
- The check constraint accepts `STOPPED` in its three definitions: migration
  `V021__Add_Stopped_Subscription_Status.sql`, template
  `08b-consumer-table-subscriptions.sql`, and the hand-written test schema in
  `SharedPostgresTestExtension`.
- Two exhaustive mappings gain the value: `SubscriptionManager.toSubscriptionState`, and
  `ManagementApiHandler.mapSubscriptionState`, which reports `"stopped"`.
- The Javadoc of `ConsumerGroup.stopGracefully` states the new contract.
- Tests. New: `SubscriptionStopGroupIntegrationTest` (`peegeeq-db`),
  `StoppedSubscriptionStatusIntegrationTest` (`peegeeq-rest`). Changed:
  `OutboxConsumerGroupGracefulShutdownTest` (its reflection helper is removed; two tests
  are new, two are replaced, and one is extended), native `ConsumerGroupSubscriptionIntegrationTest`,
  `SubscriptionStateTest`, `SubscriptionStatusTest`, and `PartitionedOrderingDemoTest`
  (test 2d no longer sets the status by SQL; the five places that discarded a failed
  `stopGracefully` now return it).

Proposal tests covered by increment 1a.

- R-8: covered and green.
- N-10: settled.
- R-7: in part. Covered: the status is `STOPPED` after a graceful stop, `stopGroup` keeps
  the stored bound, and a start makes the subscription `ACTIVE`. Not covered: the delivery
  of a message sent while the group was stopped, and the offset topic with a sweep. The
  first is increment 1b (OD-23); the second is increment 1c.
- L-4 was not implemented in increment 1a. Increment 1b implements it (OD-23).

Runs that failed first, each for the stated reason, and the run that passed after the
correction.

| Scope | Before the correction | Log | After | Log |
|---|---|---|---|---|
| `SubscriptionStateTest` (`peegeeq-api`, core) | 4 run, 4 errors: `STOPPED cannot be resolved` | `red1-api-enum` | 4 run, 0 failures | `green5-api-enum` |
| Rebuild `-pl :peegeeq-db -am` with the enum value only | Compile error: `SubscriptionManager.java:[934,15]`, switch not exhaustive | `rebuild1-db` | Build success | `rebuild2-db` |
| `SubscriptionStopGroupIntegrationTest`, before `stopGroup` existed | 10 run, 4 failures, 6 errors: "Stop group not supported"; null arguments accepted | `red2-db-stopgroup` | | |
| The same class, `stopGroup` implemented, schema unchanged | 10 run, 3 errors: check constraint `outbox_topic_subscriptions_subscription_status_check`, hand-written schema | `red3-db-constraint` | 10 run, 0 failures, 0 errors | `green1-db-stopgroup` |
| `OutboxConsumerGroupGracefulShutdownTest`, before the group change | 9 run, 3 failures, 1 error: `CANCELLED` stored (2); the failed update reported as success (1); the pending message `COMPLETED` (1) | `red6-outbox-graceful` | | |
| The same class, group changed, no `V021` | 9 run, 3 errors: the same check constraint, Flyway schema | `red7-outbox-flyway-constraint` | 9 run, 0 failures, 0 errors | `green2-outbox-graceful` |
| Native `ConsumerGroupSubscriptionIntegrationTest`, before the group change | 5 run, 1 failure: `CANCELLED` stored | `red8-native-sub` | 5 run, 0 failures, 0 errors | `green3-native-sub` |
| Rebuild `-pl :peegeeq-rest -am` before the mapping case | Compile error: `ManagementApiHandler.java:[892,15]`, switch not exhaustive | `rebuild12-rest` | Build success | `rebuild13-rest` |
| `StoppedSubscriptionStatusIntegrationTest`, template unchanged | 1 run, 1 error: the same check constraint, template schema | `red9-rest-template` | 1 run, 0 failures, 0 errors | `green4-rest-stopped` |

Reproduced at run time: R-8. Before the correction a graceful stop of the only group of a
`QUEUE` topic marked a message `COMPLETED` that the group had never received. The log
`red6-outbox-graceful` holds the two cleanup lines: "Decremented required_consumer_groups
on 1 message(s)" and "Auto-completed 1 message(s)".

Existing tests run after the change. All passed.

| Scope | Result | Log |
|---|---|---|
| `SubscriptionStatusTest` (`peegeeq-db`, core) | 8 run, 0 failures | `green6-db-enum` |
| `peegeeq-migrations`, integration profile, whole module | 5 classes, 53 run, 0 failures | `green7-migrations-module` |
| `OutboxOffsetWatermarkOrderingTest` | 3 run, 0 failures | `green8-outbox-ow-ordering` |
| `OutboxConsumerGroupCoreTest` | 64 run in 7 nested classes, 0 failures | `green9-outbox-group-core` |
| `OutboxOffsetWatermarkWiringTest` | 1 run, 0 failures | `green10-outbox-ow-wiring` |
| `OutboxOffsetWatermarkSchemaIsolationTest` | 1 run, 0 failures | `green11-outbox-ow-schema-isolation` |
| Native `ConsumerGroupSubscriptionTest` | 19 run in 2 nested classes, 0 failures | `green12-native-sub-test` |
| Native `PgNativeConsumerGroupLifecycleTest` (core) | 65 run in 10 nested classes, 0 failures | `green13b-native-lifecycle-core` |
| `PartitionedOrderingDemoTest` | 4 run, 0 failures | `green17-examples-partitioned-ordering` |
| `EventSourcingCQRSDemoTest` | 3 run, 0 failures | `green15-examples-es-cqrs` |
| `NativeVsOutboxComparisonTest` | 5 run, 0 failures | `green16-examples-native-vs-outbox` |

Runs on the final tree, after the last code edit: `SubscriptionStopGroupIntegrationTest`
10 run, 0 failures (`final1-db-stopgroup`); `OutboxConsumerGroupGracefulShutdownTest` 9
run, 0 failures (`final2-outbox-graceful`); native
`ConsumerGroupSubscriptionIntegrationTest` 5 run, 0 failures (`final3-native-sub`).

Wrong judgments and deviations in increment 1a.

- The first two versions of the R-8 test were wrong (`red4-outbox-graceful`,
  `red5-outbox-graceful`). I inserted the pending message before the subscription and
  judged that any pending message of the topic reaches the cleanup. The cleanup changes
  only messages created at or after the group subscribed, so that test passed before the
  correction. The second version had an SQL error in the test's own insert.
- `PgNativeConsumerGroupLifecycleTest` ran 0 tests under the integration profile
  (`green13-native-lifecycle`). The class is tagged core. It was run again under the
  default profile.
- One rebuild (`rebuild2-db`) had a `Select-String` filter after `Tee-Object`. The saved
  log is complete. The form was not used again.
- The rebuild of `peegeeq-migrations` rewrote the tracked file
  `peegeeq-migrations/dependency-reduced-pom.xml` (8 lines removed by the shade plugin). I
  restored it with `git checkout`. Every build of that module changes it again.

Owner review of increment 1a, 2026-10-12.

The owner found that increment 1a implements the scoped lifecycle behaviour correctly and
that it was not complete. The review ruled on the open question, required three
corrections before 1a is done, and listed four corrections for increment 1b. The three
are made.

The ruling is OD-23 in "Finding 20 decisions". The question was what an update of
subscription options does to an existing start bound. The approved rule "a start never
overwrites a stored start bound" (proposal, lifecycle rule 3; L-4) was not implemented in
1a, because `ConsumerGroupHandler.updateSubscriptionOptions`
(`POST /api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription`) calls
`subscribe` on an existing row and depends on the overwrite. The proposal did not name
that call. That was a wrong judgment in the design review: I searched for writers of the
bound by SQL text and found the one statement, and I did not follow every caller of
`subscribe`.

Correction 1. A test of the step from V020 to V021 on a schema that holds rows.
`StoppedSubscriptionStatusMigrationTest` is new (`peegeeq-migrations`, integration). Each
of its three tests migrates a schema of its own to V020, writes rows, and migrates to
V021.

- The `ACTIVE`, `PAUSED`, `CANCELLED`, and `DEAD` rows are kept, each with its status.
- `STOPPED` is rejected before the migration. After it, a row can be inserted with
  `STOPPED` and an existing row can be set to `STOPPED`.
- An unknown status is rejected after the migration, and the rejected insert leaves no
  row.

A rejection counts only when it is a check violation (SQLSTATE 23514) on
`outbox_topic_subscriptions_subscription_status_check`. The test was written after `V021`
existed. Each behaviour was therefore shown red by a temporary change to `V021`. The file
was restored after each; its checksum equals the one recorded before the changes.

| Temporary change to `V021` | Result | Log |
|---|---|---|
| The file moved out of the tree | 3 run, 3 errors: "No migration with a target version 021 could be found" | `red10-migration-v021-absent` |
| It drops the constraint and adds none | 3 run, 1 failure: the unknown-status test, "Status 'UNKNOWN' must be rejected by outbox_topic_subscriptions_subscription_status_check" | `red11-migration-probe-no-constraint` |
| It deletes the rows before it changes the constraint | 3 run, 3 failures: each test read an empty table where it expected rows | `red12-migration-probe-deletes-rows` |
| None | 3 run, 0 failures | `green18-migration-v020-to-v021` |

Correction 2. The four known places that discarded a failed `stopGracefully()`.

- `EventSourcingCQRSDemoTest`, in `runCqrsScenario` and in `runMultiAccountScenario`. The
  last step now returns `eventGroup.stopGracefully()`. That alone did not make a failed
  stop fail the test. Two strict checkpoints completed the test when the last command and
  the last event were counted, before the final assertions and the stop ran. The
  checkpoints are removed, so the `completeNow` at the end of the chain is the only
  completion. A command-handler exception and a failed event send now fail the test;
  before, each logged WARN and flagged a checkpoint.
- `NativeQueueFeatureTest.testNativeConsumerGroups`. The stop was called after the test
  had completed, and its result was dropped. The test now starts the group, sends, polls
  the group statistics on a 100 ms timer with a 10 s limit, asserts two active consumers,
  and returns the stop in the same chain.
- `ConsumerGroupLoadBalancingDemoTest.tearDown`. A failed stop or close of the group
  logged WARN, and the teardown passed. The teardown now runs the stop, the close, and
  the manager close in that order, runs each step whatever the result of the step before
  it, and fails with the first failure. It uses `CleanupResultSupport.merge`, the helper
  of that module.

The tests were corrected after the code they test. The red was therefore reconstructed:
`stopGracefully` of `OutboxConsumerGroup` and of `PgNativeConsumerGroup` was changed
temporarily to fail after the stop, `peegeeq-examples` was rebuilt with its upstream
modules, and the three classes were run. Both production files were then restored; their
checksums equal the ones recorded before the change.

| Class | With the temporary failure | Log | On the restored tree | Log |
|---|---|---|---|---|
| `EventSourcingCQRSDemoTest` | 3 run, 2 errors: `testCQRS` and `testCQRS_multipleAccounts_perAccountOrdering`, "MUTATION PROBE: stopGracefully failed" | `red13-examples-es-cqrs-mutation` | 3 run, 0 failures | `green19-examples-es-cqrs` |
| `NativeQueueFeatureTest` | 6 run, 1 error: `testNativeConsumerGroups`, the same message | `red14-examples-native-feature-mutation` | 6 run, 0 failures | `green20-examples-native-feature` |
| `ConsumerGroupLoadBalancingDemoTest` | 4 run, 2 errors: `testRoundRobinLoadBalancing` and `testStickySessionLoadBalancing`, the same message. The other two tests create no group. | `red15-examples-load-balancing-mutation` | 4 run, 0 failures | `green21-examples-load-balancing` |

Rebuilds, each with build success: `rebuild17-migrations`, `rebuild18-examples-mutation`,
`rebuild19-examples-restored`. The rebuild of `peegeeq-migrations` rewrote
`dependency-reduced-pom.xml` again, and I restored it again.

Correction 3. The proposal's phase 1 text on dependency failure modes named "the status
update at a graceful stop or a close". "or a close" is removed. `close()` is
status-neutral and makes no status update.

Wrong judgment in correction 2. In increment 1a I recorded that `EventSourcingCQRSDemoTest`
discarded a failed stop, and I judged that returning the stop's `Future` would correct it.
The run `green15-examples-es-cqrs` had already shown the teardown closing the manager
before the scenario's last step. The checkpoints, not the discarded result, decided when
the test completed. I found that only when I read the whole scenario for the correction.

Required by the owner's review when increment 1b changes the REST handler.

- Chain the subscription creation at `ConsumerGroupHandler` 148. Its `Future` is not
  observed.
- Stop converting a database failure to `null` at `ConsumerGroupHandler` 785.
- Remove the tests that treat HTTP 500 as success, `peegeeq-rest`
  `ConsumerGroupSubscriptionIntegrationTest` 358.
- Parse the presence of each update field separately. The builder inserts defaults for
  omitted fields.

Increment 1b, 2026-10-12.

It implements the lifecycle table in the SQL of `SubscriptionManager`, the start-bound
contract of OD-23, the REST settings update, and the four REST corrections above. It
changes no table, no column, and no constraint.

Data model. No field is added.

| Stored field | Source of truth | Written by |
|---|---|---|
| `start_from_message_id`, `start_from_timestamp` | Yes. The start bound. | The `subscribe` that creates the row. No later `subscribe`, start, or settings update writes them. |
| `subscription_status` | Yes. | The lifecycle calls. The settings update does not write it. |
| `heartbeat_interval_seconds`, `heartbeat_timeout_seconds` | Yes. | Every `subscribe` that the status accepts, from its options. The settings update, for the fields that the request names. |
| `last_heartbeat_at`, `consecutive_misses` | Yes. | The heartbeat, `subscribe`, and detection. The settings update does not write them. |

Derived, not stored: the start position name in a REST response. It is computed from the
stored bound. A stored message id of 1 is reported as `FROM_BEGINNING`, whichever option
created it. `FROM_NOW` on an empty topic stores 1, so it is reported as `FROM_BEGINNING`,
and the settings update accepts `FROM_BEGINNING` for it.

Deleted with the requirement that replaced it: the bound overwrite on a repeated
`subscribe`; the heartbeat's revival of a `DEAD` subscription, the re-backfill it
started, and `resetBackfillStatus`; `ConsumerGroupHandler.getSubscriptionOptionsInternal`.

Contracts.

- `subscribe`, and a start with options. No row: creates the row `ACTIVE` with the bound
  of the options. `ACTIVE`, `STOPPED`, `DEAD`: `ACTIVE`, bound kept, miss count cleared.
  `PAUSED`: stays `PAUSED`, bound kept. `CANCELLED`: fails, row unchanged.
- `pause`: from `ACTIVE`, `STOPPED`, `PAUSED`. `DEAD` and `CANCELLED` fail.
- `resume`: from `ACTIVE`, `PAUSED`, `DEAD`. `STOPPED` and `CANCELLED` fail.
- Heartbeat. `ACTIVE`: sets the time and clears the miss count. `PAUSED`: sets the time.
  `STOPPED`, `DEAD`, `CANCELLED`: fails and names the status. It revives nothing (OD-16).
- `cancel`: every status becomes `CANCELLED`.
- A refused call fails with `SubscriptionStateException`, which carries the refusing
  status. A call on no row fails with `IllegalStateException`, "Subscription not found".
  Each call is one SQL statement with the allowed statuses in its `WHERE` clause.
- `SubscriptionService.updateSubscriptionSettings(topic, groupName, update)` is new. In
  one transaction it locks the row, compares a named bound with the stored bound, checks
  the heartbeat pair against the stored values, and updates the named heartbeat settings
  only. A bound that differs fails with `SubscriptionBoundConflictException`. `FROM_NOW`
  always differs. It returns the row as stored after the call.
- REST `POST /api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription`. No
  row: the request creates the subscription. A row: the settings update. 200 returns the
  stored options. 400: an invalid value, a malformed `startFromTimestamp`, or no body.
  404: no group. 409: a bound conflict or a refusing status. 503: a database failure.
- The lifecycle endpoints of `SubscriptionHandler` and `ManagementApiHandler` return 409
  for `SubscriptionStateException`, by its type. The queue pause calls `pause` only for
  subscriptions that are not `DEAD` or `CANCELLED`. The queue resume calls `resume` only
  for `PAUSED` ones. Each returns the count of the calls it made.

What changed.

- `peegeeq-api`. New: `SubscriptionStateException`, `SubscriptionBoundConflictException`,
  the record `SubscriptionSettingsUpdate`, `SubscriptionService.updateSubscriptionSettings`,
  and `SubscriptionOptions.toBuilder()`. The Javadoc of `subscribe`, `pause`, `resume`,
  and `updateHeartbeat` states the contract.
- `peegeeq-db`, `SubscriptionManager`. The upsert of `subscribe` writes no bound on
  conflict, keeps `PAUSED`, and refuses `CANCELLED`. `pause`, `resume`, `cancel`,
  force-remove, and the heartbeat carry their allowed statuses in SQL.
  `updateSubscriptionSettings` is new.
- `peegeeq-native`, `PgNativeConsumerGroup.start(SubscriptionOptions)`. It resolves
  `FROM_NOW` against `queue_messages` and passes the result as an explicit message id
  when the subscription is created. After `subscribe` it reads the stored bound and
  starts from it. A failed read, or no row after `subscribe`, fails the start and leaves
  the group in `NEW`.
- `peegeeq-outbox`. No production change in 1b.
- `peegeeq-rest`. `ConsumerGroupHandler`: the update is the contract above, and
  `parseSubscriptionSettingsUpdate` reads the presence of each field. `SubscriptionHandler`
  and `ManagementApiHandler`: 409 by type, and the queue pause and resume as above.

The four REST corrections that the owner's review required.

| Required | Done | Red before the correction |
|---|---|---|
| Chain the subscription creation at `ConsumerGroupHandler` 148. | The group is created and registered only after `subscribe` succeeds. A refused `subscribe` returns 409 and leaves no group. | `testCreateConsumerGroupFailsWhenSubscriptionIsCancelled` (`red4-rest-cg-subscription`) |
| Stop converting a database failure to `null` at `ConsumerGroupHandler` 785. | The method is deleted. No source tree called it. `SubscriptionHandler.createSubscription` made the same conversion after its `subscribe`, and that is removed. | No test. See "Not done in increment 1b". |
| Remove the tests that treat HTTP 500 as success, `ConsumerGroupSubscriptionIntegrationTest` 358. | The class is rewritten to the OD-23 contract. No test of it accepts 500. It has no `@Order`. | |
| Parse the presence of each update field separately. | `parseSubscriptionSettingsUpdate`. An omitted field is null and changes nothing. | `testUpdateChangesOnlyNamedHeartbeatSetting` and `testUpdateWithTimeoutNotGreaterThanStoredIntervalReturns400` (`red4-rest-cg-subscription`) |

Runs that failed first, each for the stated reason, and the last run of the class after
the correction.

| Scope | Before the correction | Log | Last run | Log |
|---|---|---|---|---|
| `SubscriptionSettingsUpdateTest` (`peegeeq-api`, core) | Compile error: the types did not exist | `red1-api-settings-update` | 9 run, 0 failures | `final19-api-settings-update` |
| `SubscriptionOptionsValidationTest` (`peegeeq-api`, core) | 28 run, 2 errors: `toBuilder()` is undefined | `red9-api-tobuilder` | 28 run in 6 nested classes, 0 failures | `green15-api-tobuilder` |
| `SubscriptionLifecycleTransitionIntegrationTest` (`peegeeq-db`) | 26 run, 13 failures: 8 refusals, 4 tests of the kept bound and the kept pause, and the L-8 sequence | `red2-db-lifecycle` | 26 run, 0 failures | `final21-db-lifecycle` |
| `SubscriptionSettingsUpdateIntegrationTest` (`peegeeq-db`) | Temporary change to `updateSubscriptionSettings`: 18 run, 11 failures | `red3-db-settings-update-mutation` | 18 run, 0 failures | `final22-db-settings-update` |
| REST `ConsumerGroupSubscriptionIntegrationTest`, original handlers | 13 run, 5 failures | `red4-rest-cg-subscription` | | |
| The same class, `testUpdateKeepsLifecycleStatus` | 1 run, 1 failure: expected `STOPPED`, was `ACTIVE` | `red5-rest-update-keeps-status` | | |
| The same class, two input-error tests added | 15 run, 2 failures: 503 for no body and for a malformed timestamp | `red13-rest-update-input-errors` | 15 run, 0 failures | `final33-rest-cg-subscription` |
| `SubscriptionLifecycleRestIntegrationTest` (`peegeeq-rest`) | 4 run, 4 failures | `red6-rest-lifecycle` | 4 run, 0 failures | `final27-rest-lifecycle` |
| Native `ConsumerGroupSubscriptionIntegrationTest`, restart test | 6 run, 1 failure: the message sent while the group was stopped was not delivered | `red8-native-restart-bound` | | |
| The same class, two failure-mode tests added, original `PgNativeConsumerGroup` | 8 run, 3 failures: the two new tests and the restart test | `red11-native-bound-read-failure-modes` | | |
| The same class, temporary change: the missing-row check removed | 8 run, 1 failure: `NullPointerException`, expected `IllegalStateException` | `red12-native-nullcheck-mutation` | 8 run, 0 failures | `final29-native-sub-integration` |
| `OutboxConsumerGroupGracefulShutdownTest`, restart test, temporary change: the bound overwrite put back | 1 run, 1 failure: the restart stored bound 2 | `red10-outbox-restart-delivery-mutation` | 10 run, 0 failures | `green20-outbox-graceful` |

`SubscriptionStateExceptionTest` (`peegeeq-api`, core) has no red run. Its two tests were
moved out of `SubscriptionSettingsUpdateTest` after they passed. 2 run, 0 failures
(`final20-api-state-exception`).

Each temporary change was restored, and the checksum of the restored file equals the one
recorded before the change. `red7-native-restart-bound` is invalid: it ran without the
rebuild, on classes that the IDE had compiled.

Reproduced at run time: N-11. Before the correction a native group started with
`FROM_NOW`, stopped gracefully, and started again did not receive a message sent while
it was stopped. The consumer log of `red8-native-restart-bound` shows
`minimumMessageId: 1` at the first start and `2` at the second.

Proposal tests covered by increment 1b.

- L-1 and L-10: covered at the service and at the REST endpoints.
- L-2: the heartbeat and `resume`. The start is covered at `subscribe`
  (`subscribeMakesDeadSubscriptionActiveAndClearsMisses`). No test starts a group on a
  `DEAD` subscription.
- L-4: covered at the service, and at REST for the update.
- L-8: covered at the service (`operatorPauseSurvivesGroupStopAndStart`: pause, stop,
  subscribe, resume). No test runs the sequence with a running group.
- R-7, the delivery of a message sent while the group was stopped: covered on a `QUEUE`
  topic for the outbox group and for the native group. The offset topic with a sweep is
  increment 1c.
- OD-23, the REST update: U-1 to U-5 in the proposal.

Existing tests changed because the contract changed.

- `SubscriptionManagerEdgeCaseTest`. Three tests asserted the overwrite. They assert the
  kept bound and are renamed: `testResubscribeFromBeginningKeepsFromNowBound`,
  `testResubscribeFromMessageIdKeepsFromBeginningBound`,
  `testRepeatedResubscribeKeepsFirstBound`. 10 run, 0 failures (`green6-db-edge-case`).
- `StartPositionDatabaseStateTest`. `testResubscribeKeepsStartPositionInDatabase` replaces
  the test of the overwrite. 6 run, 0 failures (`green7-db-start-position`).
- `SubscriptionManagerIntegrationTest`. The two tests of the heartbeat revival and their
  helper are removed. 10 run, 0 failures (`green5-db-sub-manager-it`).
- `FlappingProtectionIntegrationTest`. `testHeartbeatAutoResurrectionResetsMissCount` is
  removed. 11 run, 0 failures (`green8-db-flapping`).
- `ResurrectionReBackfillIntegrationTest` is deleted. It tested the heartbeat revival.
- Native `ConsumerGroupSubscriptionTest`. Its tests start one group name on one topic
  with different start options. With a kept bound they depended on their order: 19 run,
  4 failures (`green18-native-sub-test`). The setup now deletes the topic's subscription
  rows. 19 run in 2 nested classes, 0 failures (`final23-native-sub-test`).
- `OutboxConsumerGroupSubscriptionTest`. The same cause and the same correction. 18 run,
  1 failure (`green21-outbox-sub-test`), then 18 run in 2 nested classes, 0 failures
  (`final25-outbox-sub-test`).
- REST `SubscriptionOptionsCoreTest.testUpdateSubscriptionOptions_FromNow` expected the
  submitted `FROM_NOW` in the response: 10 run, 1 failure
  (`final9-rest-sub-options-core`). It expects the stored `FROM_BEGINNING`. 10 run, 0
  failures (`final32-rest-sub-options-core`).
- `RestClientConsumerGroupsContractSmokeTest` is rewritten to the contract: create with
  message id 42, a heartbeat-only update, and a conflicting bound that returns 409. 1
  run, 0 failures under `-Psmoke-tests` (`final31-smoke-rest-client-cg`).
- REST `StoppedSubscriptionStatusIntegrationTest`, of increment 1a, uses
  `PostgreSQLTestConstants.createStandardContainer()`. 1 run, 0 failures
  (`final28-rest-stopped-status`).

Existing tests run after the change, unchanged. All passed.

| Scope | Result | Log |
|---|---|---|
| `SubscriptionManagerCoreTest` (`peegeeq-db`, integration profile) | 15 run, 0 failures | `green9b-db-sub-manager-core` |
| `SubscriptionStopGroupIntegrationTest` | 10 run, 0 failures | `final2-db-stopgroup` |
| `DeadConsumerDetectorComprehensiveTest` | 10 run, 0 failures | `final3-db-detector-comprehensive` |
| `ForceRemoveIntegrationTest` | 5 run, 0 failures | `final4-db-force-remove` |
| `CancelCleanupIntegrationTest` | 4 run, 0 failures | `final5-db-cancel-cleanup` |
| `BackfillServiceIntegrationTest` | 11 run, 0 failures | `final6-db-backfill-it` |
| `DeadConsumerDetectorIntegrationTest` | 4 run, 0 failures | `final7-db-detector-it` |
| `DeadConsumerDetectionJobIntegrationTest` | 10 run, 0 failures | `final8-db-detection-job` |
| Native `PgNativeConsumerGroupLifecycleTest` (core) | 65 run in 10 nested classes, 0 failures | `green17-native-lifecycle-core` |
| REST `ManagementApiIntegrationTest` | 28 run, 0 failures | `final11-rest-management-api` |
| REST `SubscriptionCreateAndBackfillIntegrationTest` | 17 run, 0 failures | `final12-rest-sub-create-backfill` |
| REST `SubscriptionLifecycleIntegrationTest` | 7 run, 0 failures | `green12-rest-sub-lifecycle-it` |
| REST `QueuePauseResumeStatusIntegrationTest` | 4 run, 0 failures | `green14-rest-queue-pause-resume` |
| REST `SubscriptionPersistenceAcrossRestartIntegrationTest` | 5 run, 0 failures | `final34-rest-sub-persistence` |
| REST `ManagementApiHandlerErrorTest` (core) | 14 run, 0 failures | `final35-rest-management-error` |
| REST `ConsumerGroupHandlerTest` (core) | 8 run, 0 failures. Its tests log documentation and call no handler. | `final36-rest-cg-handler` |
| REST `QueuePauseResumeIT` | 1 run, 0 failures | `final37-rest-queue-pause-resume-it` |
| REST `QueuePauseResumeManualTest` | 1 run, 0 failures | `final38-rest-queue-pause-resume-manual` |
| REST `QueueManagementE2ETest` | 11 run, 0 failures | `final39-rest-queue-management-e2e` |
| REST `QueueManagementAdvancedE2ETest` | 8 run, 0 failures | `final40-rest-queue-management-advanced` |
| `SubscriptionLifecycleSmokeTest` (`peegeeq-integration-tests`, smoke) | 6 run, 0 failures | `final41-smoke-subscription-lifecycle` |
| `QueueManagementSmokeTest` (smoke) | 5 run, 0 failures | `final42-smoke-queue-management` |
| `DeadConsumerDetectionDemoTest` (`peegeeq-examples`) | 3 run, 0 failures | `final15-examples-dead-consumer-demo` |
| `LateJoiningConsumerDemoTest` | 3 run, 0 failures | `final16-examples-late-joining` |
| `PartitionedOrderingDemoTest` | 4 run, 0 failures | `final17-examples-partitioned-ordering` |
| `EventSourcingCQRSDemoTest` | 3 run, 0 failures | `final18-examples-es-cqrs` |
| `OnSuccessExceptionSwallowingGuardTest` (`peegeeq-test-support`, core) | 8 run, 0 failures | `guard3-onsuccess-swallowing` |
| `VertxAsyncForbiddenPatternsGuardTest` (core) | 1 run, 0 failures | `guard4-forbidden-patterns` |

No test class in the table was edited after its run. The rows with a `green` log, and
`final2` to `final18`, ran before the last production edit. That edit is in
`ConsumerGroupHandler.updateSubscriptionOptions` and `parseSubscriptionSettingsUpdate`
only. Every test class that calls that endpoint ran after it:
`ConsumerGroupSubscriptionIntegrationTest`, `SubscriptionOptionsCoreTest`,
`SubscriptionPersistenceAcrossRestartIntegrationTest`, and
`RestClientConsumerGroupsContractSmokeTest`.

Rebuilds `rebuild1` to `rebuild23`: build success in each. Three hold a temporary
change by design (`rebuild3-db-mutation`, `rebuild10-outbox-mutation`,
`rebuild18-native-nullcheck-mutation`). `rebuild5-rest-old-handlers` and
`rebuild17-native-original-class` hold production classes of before 1b, for the red
runs. `rebuild22-all-upstream` built `peegeeq-integration-tests` and its ten upstream
modules on the final production tree. `peegeeq-examples` was last built in
`rebuild15-examples`.

Wrong judgments and deviations in increment 1b.

- The settings update of `SubscriptionManager` was implemented before its test class was
  run red. I judged that a test written beside the code needed no red run. The first
  version of the tests could not tell a kept value from a default, because the stored
  heartbeat settings were the defaults. The tests now store 45 and 200, and the red was
  reconstructed by a temporary change (`red3-db-settings-update-mutation`).
- The REST handlers were edited before their tests. I restored the original handlers,
  ran the tests red (`red4`, `red5`, `red6`), and applied the edit again.
  `testUpdateKeepsLifecycleStatus` first used `PAUSED` and passed on the original
  handler, because `subscribe` keeps `PAUSED`. It uses `STOPPED`.
- I judged increment 1b complete when its classes were green. Three defects were found
  only when this record was written.
  - The failure modes of the read that the native start gained were not listed before
    the code. Two tests were added after it (`red11`, `red12`).
  - The update endpoint returned 503 for a request with no body and for a malformed
    `startFromTimestamp`, in code written in this increment (`red13`). Corrected.
  - My note said that narration logging was removed from REST
    `ConsumerGroupSubscriptionIntegrationTest`. 25 such lines remained in its test
    methods. They are removed (`final33`). Its setup and teardown are unchanged.
- The validation against the testing standard found, in tests I had written in this
  increment: narration logging, `@Order` with no need, unused parameters, setup deletes
  outside a transaction, hand-built containers, and one test that asserted nothing of
  value. All are corrected, and the classes were run again (`final19` to `final28`).
- `RestClientConsumerGroupsContractSmokeTest` was rewritten after the handler change. It
  has no red run.
- One command had `| Out-Null; Select-String` after `Tee-Object`
  (`green2-db-lifecycle`). The saved log is complete.
- One message held two Maven commands. The logs show that they ran one after the other.
- `rebuild6-rest-new-handlers` used `-pl :peegeeq-rest` with no `-am`. The upstream
  modules had been installed by the rebuild before it.
- `SubscriptionManagerCoreTest` ran 0 tests under the default profile
  (`green9-db-sub-manager-core`). It inherits the integration tag. It was run again
  under the integration profile.

Found in increment 1b. None is changed.

- The unexpected-ERROR gate does not see `ConsumerGroupHandler` in the REST tests.
  `peegeeq-rest/src/test/resources/logback-test.xml` 25 to 30 gives the loggers of
  `ConsumerGroupHandler` and `EventStoreHandler` the level `ERROR` and
  `additivity="false"`. The gate's appender is on the root logger. Run: the class passes
  with two ERROR lines of `ConsumerGroupHandler` in its log
  (`final30-rest-cg-subscription`, lines 233 and 422). The cause is read, not shown by a
  probe. The same setting hides every WARN line of the two handlers.
- `ConsumerGroupHandler.updateSubscriptionOptions` logs ERROR for an invalid request
  that returns 400 (693; before 1b, 639). An invalid request is not a failure of the
  system.
- Tests that accept HTTP 500 or 503 as success, outside the class the owner named:
  `EventStoreIntegrationTest` 1941 to 2105, which includes a pause, a resume, and a
  heartbeat on a group that does not exist; `CrossLayerPropagationIntegrationTest` 476;
  `DeadLetterRequeueIntegrationTest` 203 and 284. Read.
- `ConsumerGroupHandler.deleteSubscriptionOptions` returns 204 when `cancel` fails (811
  to 815). `deleteConsumerGroup` logs WARN and answers "Consumer group deleted
  successfully" when the close of the group fails (524 to 536). Read.
- `ManagementApiHandler` 1498, 1563, and 1618 choose 409 or 503 by the text "invalid" in
  the message. `SubscriptionHandler.isSubscriptionNotFoundError` (902) decides 404 by
  message text. Read.
- `subscribe` with `FROM_BEGINNING` on an existing subscription keeps the stored bound
  and still starts the automatic backfill. The backfill is selected by the options, not
  by the stored bound (`SubscriptionManager` 195 to 196). Read, not run.
- A native group whose subscription was created by `SubscriptionService.subscribe` or by
  the REST endpoint with `FROM_NOW` has a bound computed from `outbox` ids. Only the
  native start resolves `FROM_NOW` against `queue_messages`. Read, not run.
- The management UI client does not call the changed endpoint.
  `PeeGeeQClient.updateSubscriptionOptions` sends `PUT` to the subscription URL with
  `/options` (`src/api/PeeGeeQClient.ts` 543 to 554). `PeeGeeQRestServer` defines no
  route that ends in `/options` (497 to 516). Read, not run.
- REST `ConsumerGroupSubscriptionIntegrationTest`, setup and teardown, existing code: a
  hand-built container, a blocking `awaitCompletion`, narration logging, and a teardown
  that ignores a failed setup delete. `SubscriptionOptionsCoreTest` uses `@Order`.

Not done in increment 1b.

- No test makes the database fail under the REST update endpoint, so its 503 path and
  the two removed `null` conversions have no test. The REST tests have no way to make
  the subscription service of an existing consumer group fail. `ControllableSetupService
  .withSubscriptionServiceForSetup` exists and no test uses it; a test also needs a
  hand-written `QueueFactory` and `ConsumerGroup`, which no REST test has. That is a new
  test fixture and needs a decision.
- No whole-module run, no `-Pall-tests` run, and no Jenkins build was made. Jenkins
  builds the remote branch, and these changes are not committed.
- Not run: every test class not named in this section. In `peegeeq-rest` that includes
  `EventStoreIntegrationTest` and `DeadConsumerAlertingIntegrationTest`. No test of
  `peegeeq-benchmarking`, `peegeeq-examples-spring`, or the management UI was run.
- `peegeeq-examples` was not rebuilt after `rebuild15-examples`. The later production
  edit is in `ConsumerGroupHandler` only.

Owner review of increment 1b, 2026-10-12.

Increment 1b is not approved. The review found eight blocking defects, ruled on four
questions, and set nine corrections. The owner: "No further owner ruling is required for
these corrections."

Blocking defects found by the review.

1. Native `FROM_NOW` is wrong through REST. Generic subscription creation computes the
   bound from `outbox`. Native delivery reads `queue_messages`. N-11 is incomplete.
2. A `subscribe` that names `FROM_BEGINNING` on an existing subscription keeps the stored
   bound and still starts the automatic backfill from the submitted options.
3. The native start resolves `FROM_NOW` before it reads the existing subscription. An
   existing stored bound depends on a `queue_messages` query that is not needed.
4. The transition SQL reads the old status without a lock before the guarded update. A
   concurrent transition can make the reported refusing status stale.
5. `SubscriptionSettingsUpdate` accepts contradictory fields, for example
   `FROM_BEGINNING` with a message id.
6. `ConsumerGroupHandler` turns failures into success: a failed group close, a failed
   subscription cancel (HTTP 204), and statistics exceptions logged at DEBUG.
7. The REST evidence is not trustworthy. The handler logger bypasses the
   unexpected-ERROR gate.
8. The management UI calls an endpoint that the server does not define.

Rulings of the review.

| Question | Ruling |
|---|---|
| The cleanup helper for tests | Approved. It runs every cleanup exactly once. It preserves an operation failure. It returns a cleanup failure when the operation succeeded. It adds cleanup failures as suppressed exceptions when both fail. It continues later cleanup after an earlier cleanup failure. It treats a thrown exception or a null cleanup future as a cleanup failure. Both standards documents are corrected. Production use of `eventually` is not banned. The unsafe test-cleanup forms are guarded after their call sites are migrated. |
| The REST fixture for dependency failures | Approved. `ControllableSetupService` with hand-written `QueueFactory` and `ConsumerGroup` implementations, and no mocking framework. Tests: initial lookup failure, update failure, create failure, reread failure, and a null reread. |
| The detached handler loggers | Removed. An HTTP 400 input error logs at WARN or below. An expected dependency failure stays ERROR and uses `@ExpectedErrorLog`. |
| The three test classes that accept HTTP 500 or 503 as success | Corrected. Each class is run separately after the correction. |

The review also approved the queue pause and resume of increment 1b: the pause applies to
eligible nonterminal subscriptions, and the resume applies to `PAUSED` only.

The nine corrections.

| # | Correction | State |
|---|---|---|
| 1 | Correct the two bound defects and add red tests. | Defects 2 and 3 are corrected and approved. Defect 1 is corrected under the owner's store ruling and awaits review: see "Correction 1, the subscription store" below. |
| 2 | Lock the lifecycle row before reading and changing its status. Add a concurrent-transition test. | Not started. `subscribe` locks the row since correction 1. |
| 3 | Reject contradictory start-bound fields. | Not started |
| 4 | Add the REST dependency-failure fixture and tests. | Not started |
| 5 | Install the cleanup helper, migrate the 1b tests, correct the standards, then add the guard. | Not started |
| 6 | Restore the REST error gate and remove the pass-on-500/503 assertions. | Not started |
| 7 | Fix the known error swallowing and the message-text status classification in the touched handlers. | Not started |
| 8 | Align the UI client with the approved REST endpoint and test the request contract. | Not started |
| 9 | Rebuild each affected reactor slice, run the targeted classes, inspect every per-class count, and rerun the guards. | Not started |

Correction 1, 2026-10-12.

Data model. No table, column, or stored field changes. The stored start bound stays the
only source of truth for where a group starts. Two decisions that read the submitted
options now read the stored row.

What changed.

- `SubscriptionManager.subscribe`. In one transaction it locks the subscription row and
  reads its status first.
  - A row exists: the start position of the options is not resolved, and no message
    table is read. A `CANCELLED` row fails the call from the locked status. Any other
    row is updated as before: `ACTIVE`, or `PAUSED` kept; the heartbeat settings of the
    options; the miss count cleared.
  - No row: the start bound is resolved and the row is inserted. When another
    transaction inserted the row first, that row is locked and updated, and its bound is
    kept.
- The automatic backfill. It starts only when the options name `FROM_BEGINNING` and the
  stored bound is the beginning: message id 1 and no timestamp. Before, it started
  whenever the options named `FROM_BEGINNING`. A subscription whose stored bound is the
  beginning keeps the behaviour it had.
- `PgNativeConsumerGroup.start(SubscriptionOptions)`. It reads the subscription first.
  When a subscription exists it does not read `queue_messages`. When none exists and the
  options name `FROM_NOW`, it reads the highest message id of the topic in
  `queue_messages` and creates the subscription with the next id. It then reads the
  stored bound and starts from it. `withNativeStartBound` is deleted.

Dependency failure modes, each with a test.

| Call | Failure mode | Test |
|---|---|---|
| Native: the read before the subscribe | The Future fails | `startFailsBeforeTheSubscribeWhenTheSubscriptionCannotBeRead`: the start fails, the group is `NEW`, and no subscription is created |
| Native: the `queue_messages` read | It fails when the start creates the subscription | `startThatCreatesTheSubscriptionFailsWhenTheQueuePositionCannotBeRead` |
| Native: the `queue_messages` read | It is not made when a subscription exists | `startOnExistingSubscriptionDoesNotReadQueueMessages` |
| Native: `subscribe` | The subscription is `CANCELLED` | `startFailsOnACancelledSubscription` |
| Native: the read after the subscribe | The Future fails. The read finds no row. | `startFailsWhenTheStoredBoundCannotBeRead`, `startFailsWhenTheSubscriptionIsMissingAfterSubscribe` |
| Db: the insert | Another transaction inserted the row first | `subscribeKeepsTheBoundOfAConcurrentCreator` |
| Db: every statement | No pool for the service | `lifecycleCallsReturnDatabaseFailure` (increment 1b) |

Not covered by a test: a failure of the `outbox` read that resolves `FROM_NOW` when
`subscribe` creates the row. No test makes that one statement fail.

Runs that failed first, each for the stated reason, and the run after the correction.

| Scope | Before the correction | Log | After | Log |
|---|---|---|---|---|
| `SubscriptionManagerIntegrationTest` (`peegeeq-db`) | 12 run, 1 failure: `testSubscribeFromBeginningStartsNoBackfillWhenTheStoredBoundIsLater`, backfill status expected `NONE`, was `COMPLETED` | `red1-db-backfill-stored-bound` | 12 run, 0 failures | `green1-db-sub-manager-it` |
| `SubscriptionStartBoundResolutionIntegrationTest` (`peegeeq-db`, new) | 2 run, 1 failure: a `subscribe` with `FROM_NOW` on an existing subscription waited for a lock on `outbox` | `red2-db-bound-resolution` | 2 run, 0 failures | `green2-db-bound-resolution` |
| The same class, a third test added, temporary change: the conflicting insert fails | 3 run, 1 error: "MUTATION PROBE: conflicting insert" | `red5-db-conflict-mutation` | 3 run, 0 failures | `green6-db-bound-resolution` |
| Native `ConsumerGroupSubscriptionIntegrationTest` | 12 run, 3 failures and 1 error | `red3-native-start-order` | 12 run, 0 failures | `green3-native-sub-integration` |

The four native tests that failed first: the start on an existing subscription read
`queue_messages`; the start created the subscription before its first read; and the two
tests of the read after the subscribe, whose helper now scripts the second read, which
the old start never made.

Four new tests passed before the correction and after it. They state behaviour that is
kept: `testSubscribeFromBeginningStartsBackfillWhenTheStoredBoundIsTheBeginning`,
`concurrentFirstSubscribesStoreOneStartBound`,
`startThatCreatesTheSubscriptionFailsWhenTheQueuePositionCannotBeRead`, and
`startFailsOnACancelledSubscription`.

The lock test has a schema of its own, `peegeeq_start_bound_test`, built by
`PeeGeeQTestSchemaInitializer`. It holds an `ACCESS EXCLUSIVE` lock on `outbox`, and
that lock must not block the tests that share the module schema. The temporary change of
`red5` was restored, and the checksum of `SubscriptionManager` equals the one recorded
before it.

Existing tests run after the correction. All passed, except the one named below.

| Scope | Result | Log |
|---|---|---|
| `SubscriptionLifecycleTransitionIntegrationTest` (`peegeeq-db`) | 26 run, 0 failures | `reg1-db-lifecycle` |
| `SubscriptionManagerEdgeCaseTest` | 10 run, 0 failures | `reg2-db-edge-case` |
| `StartPositionDatabaseStateTest` | 6 run, 0 failures | `reg3-db-start-position` |
| `SubscriptionSettingsUpdateIntegrationTest` | 18 run, 0 failures | `reg4-db-settings-update` |
| `SubscriptionManagerCoreTest` (integration profile) | 15 run, 0 failures | `reg5-db-sub-manager-core` |
| `BackfillServiceIntegrationTest` | 11 run, 0 failures | `reg6-db-backfill-it` |
| Native `ConsumerGroupSubscriptionTest` | 19 run in 2 nested classes, 0 failures | `reg7-native-sub-test` |
| Native `PgNativeConsumerGroupLifecycleTest` (core) | 65 run in 10 nested classes, 0 failures | `reg8-native-lifecycle-core` |
| `OutboxConsumerGroupGracefulShutdownTest` | 10 run, 0 failures | `reg9-outbox-graceful` |
| `OutboxConsumerGroupSubscriptionTest` | 18 run in 2 nested classes, 0 failures | `reg10-outbox-sub-test` |
| REST `SubscriptionCreateAndBackfillIntegrationTest` | 17 run, 0 failures | `reg11-rest-sub-create-backfill` |
| REST `ConsumerGroupSubscriptionIntegrationTest` | 15 run, 0 failures | `reg12-rest-cg-subscription` |
| REST `SubscriptionLifecycleRestIntegrationTest` | 4 run, 0 failures | `reg13-rest-lifecycle` |
| REST `ManagementApiIntegrationTest` | 28 run, 0 failures | `reg14-rest-management-api` |
| `RestClientConsumerGroupsContractSmokeTest` (smoke) | 1 run, 0 failures | `reg15-smoke-rest-client-cg` |
| `SubscriptionLifecycleSmokeTest` (smoke) | 6 run, 0 failures | `reg16-smoke-subscription-lifecycle` |
| `LateJoiningConsumerDemoTest` (`peegeeq-examples`) | 3 run, 2 failures | `reg17-examples-late-joining` |
| `PartitionedOrderingDemoTest` | 4 run, 0 failures | `reg18-examples-partitioned-ordering` |
| `OnSuccessExceptionSwallowingGuardTest` (core) | 8 run, 0 failures | `guard1-onsuccess-swallowing` |
| `VertxAsyncForbiddenPatternsGuardTest` (core) | 1 run, 0 failures | `guard2-forbidden-patterns` |

Rebuilds, each with build success: `rebuild1-native-red-tests` (the new tests on the
unchanged production code), `rebuild2-native-c1`, `rebuild3-downstream-c1` (13 modules:
`peegeeq-integration-tests`, `peegeeq-examples`, and their upstream modules),
`rebuild4-examples`, `rebuild5-db-conflict-mutation` (the temporary change), and
`rebuild6-db-restored`. `reg1` to `reg16` ran on `rebuild3`. The only source change
after it is in `LateJoiningConsumerDemoTest` and in the test class of `red5`.

Found in correction 1: `LateJoiningConsumerDemoTest` fails on its second run.

- 3 run, 2 failures (`reg17-examples-late-joining`). The `FROM_NOW` group received 15
  messages, expected 5. The `FROM_TIMESTAMP` group received 20, expected 10.
- Cause, read from the database. The examples module keeps one PostgreSQL container
  between runs (`SharedTestContainers`, `withReuse(true)`). The container was created
  at 14:56. The test used fixed topic names. The subscription of `orders.events` and
  `email-service` was created by the run of increment 1b at 17:09 with bound 238. Since
  increment 1b a later `subscribe` keeps that bound, so the run at 19:29 received every
  message from id 238.
- It is a defect of increment 1b, not of correction 1. The run of increment 1b
  (`f20-p1b-final16-examples-late-joining`) was the first run of the class on that
  container.
- Correction: each test of the class uses a topic that is unique to the run, as
  `PartitionedOrderingDemoTest` does. Two runs on the same container, each 3 run, 0
  failures: `green4-examples-late-joining-run1`, `green5-examples-late-joining-run2`.
- Sweep. Three example classes create subscriptions on the kept container.
  `PartitionedOrderingDemoTest` uses unique topics. `DeadConsumerDetectionDemoTest` uses
  fixed topics and passed on its second run: 3 run, 0 failures
  (`red4-examples-dead-consumer-second-run`; the log name is wrong, the run is green).
  `peegeeq-examples-spring` keeps a container and creates no subscription. The
  container of `peegeeq-db` is not kept between runs: `docker ps` showed one container,
  the examples one.

Wrong judgments in increment 1b, found in correction 1.

- I judged one green run of `LateJoiningConsumerDemoTest` sufficient. I knew that the
  examples database is kept between runs. I did not run the class a second time.
- The dependency failure modes of the native start were tested after the code in
  increment 1b, and the test helper returned the same result for every read. It could
  not tell the read before the subscribe from the read after it. The helper now scripts
  each read.

Owner ruling on defect 1, 2026-10-12: the subscription store.

Defect 1 of the review. `start_from_message_id` is an id of one of two tables: `outbox`
or `queue_messages`. Nothing stored says which. Before this correction every creation
through the subscription service or through REST resolved `FROM_NOW` against `outbox`,
also for a native queue. I proposed four mechanisms and recommended a message store in
`SubscriptionOptions`. The owner rejected that: "Storage identity does not belong in
`SubscriptionOptions`. It is not a delivery option, and a caller-supplied default can
silently select the wrong table." The ruling:

- A typed `SubscriptionStore` with `OUTBOX` and `NATIVE`.
- The store is mandatory when a subscription is created. There is no `OUTBOX` default.
- `QueueFactory` has a typed method that returns its subscription store. The owning
  factory or consumer group supplies the value.
- REST derives the store from the queue's factory. REST clients do not submit it.
- `SubscriptionManager` resolves `FROM_NOW` against the selected table inside the
  subscription-creation transaction. An existing subscription performs no position query.
- The native group's separate `FROM_NOW` resolver is deleted.
- No discriminator column is stored. The queue configuration is the source of truth. The
  store is derived.
- A change of a queue's implementation type is refused while the queue has subscriptions.
- A direct `SubscriptionService` caller names the store. A compile break is preferred to
  a silent `outbox` default.
- `queueName` is the intended topic. `ConsumerGroupHandler` uses it for the subscription
  and for the group.

Correction 1, the subscription store, 2026-10-12.

Data model. No table and no column changes. The store of a subscription is not stored. It
is derived from the queue: `peegeeq_object_registry.kind` stays the stored source of truth
for the implementation type of a queue, and the queue factory built from it declares the
store. `SubscriptionStore` is an input of the creating call only.

What changed.

- `peegeeq-api`. New enum `SubscriptionStore`. `SubscriptionService.subscribe(topic,
  group, store)` and `subscribe(topic, group, store, options)` replace the two overloads
  that had no store. `QueueFactory.getSubscriptionStore()` is new. New
  `QueueImplementationChangeException`. `SubscriptionOptions.toBuilder()` and its two
  tests are removed: the native resolver was its only caller.
- `peegeeq-db`. `SubscriptionManager` rejects a null store. It resolves `FROM_NOW` with
  `SELECT COALESCE(MAX(id), 0)` on `outbox` or on `queue_messages`, by store, on the
  connection of the creating transaction. `PeeGeeQDatabaseSetupService.addQueue` reads
  the registered kind and the subscription count of the queue name before it applies the
  queue template. It fails with `QueueImplementationChangeException` when the kind
  differs and the count is above zero.
- `peegeeq-native`. `PgNativeQueueFactory` returns `NATIVE`. `PgNativeConsumerGroup`
  subscribes with `NATIVE` and then reads the stored bound. `optionsForNewSubscription`,
  `nextQueueMessageId`, and the read before the subscribe are deleted.
- `peegeeq-outbox`. `OutboxFactory` returns `OUTBOX`. `OutboxConsumerGroup` subscribes
  with `OUTBOX`.
- `peegeeq-rest`. New `QueueSubscriptionStore`. It takes the store from the queue
  factory. `ManagementApiHandler.createConsumerGroup`,
  `SubscriptionHandler.createSubscription`, and the two creating paths of
  `ConsumerGroupHandler` use it. `ConsumerGroupHandler` uses `queueName` as the topic in
  its four subscription methods. `ManagementApiHandler.createQueue` and
  `DatabaseSetupHandler.addQueue` answer HTTP 409 for
  `QueueImplementationChangeException`.

Decisions I made inside the ruling. Each is open to the owner.

- `getSubscriptionStore()` is a default method that throws
  `UnsupportedOperationException`. It is not abstract. An implementation that declares no
  store has none, and the five hand-written factories in tests are unchanged.
- A queue whose factory declares no store answers HTTP 501 and logs at ERROR.
- `POST /api/v1/setups/:setupId/subscriptions/:topic` answers HTTP 404 when the topic is
  not a queue of the setup. Before, it created a subscription on any topic name. This
  changes the contract of that endpoint.
- The refusal counts every subscription row of the queue name, also a `CANCELLED` row.
  The ruling says "while it has subscriptions".
- `PgNativeConsumerGroup.start(options)` without a database service fails for
  `FROM_NOW`. The resolver it used is deleted. `PgNativeQueueFactory` always passes a
  database service.
- A queue that has subscriptions, and a request that names another type that is not
  registered: the request now fails with the refusal, before the queue template. Before,
  it failed after the template with `IllegalArgumentException`.

Found by a run.

- Adding a queue name that exists fails. The queue template ends with
  `CREATE TRIGGER "trigger_<queue>_notify"`, and that statement has no `OR REPLACE`. The
  run `red1-rest-store` shows HTTP 503 and SQLSTATE 42710 for the second
  `POST /api/v1/management/queues` of a name: with a subscription, without one, and
  after a REST delete of the queue. So before this correction a change of the
  implementation type through `addQueue` failed at the DDL in every case, and a queue
  deleted through REST cannot be created again. After this correction a queue with
  subscriptions answers HTTP 409 before any DDL. A queue without subscriptions passes
  the check and still fails at the trigger. The template is not changed: that needs a
  ruling.
- The Vert.x access log reports every 5xx response at ERROR. A REST test that expects
  HTTP 501 declares that line with `@ExpectedErrorLog`, as the existing error tests do.

Not covered by this correction.

- Subscription rows that `ConsumerGroupHandler` stored under `setupId-queueName` before
  this change. The handler now reads and writes `queueName`. No migration moves the old
  rows, so a group that REST configured before the change reads as not configured, and
  its old row stays in the table. Read, not run. I did not flag this when I reported the
  correction. It needs a ruling: migrate, delete, or leave.
- A subscription that a direct caller created on a topic that is not a registered queue.
  Nothing stored names its store. `addQueue` on that name cannot see a mismatch.
- A subscription created between the refusal check and the registry write of `addQueue`.
  The check and the subscribe lock no common row.
- The automatic backfill of `subscribe` reads `outbox` for every store. Not changed.

The eight required tests.

| # | Required | Tests | First red |
|---|---|---|---|
| 1 | Native `FROM_NOW` through each REST creation path uses `queue_messages`, with different ids in the two tables | REST `managementApi...`, `subscriptionApi...`, `consumerGroupApi...`, `subscriptionSettingsApiUsesQueueMessagesForANativeQueue` | `red1-rest-store` |
| 2 | Outbox `FROM_NOW` uses `outbox` | The four REST `...UsesOutboxForAnOutboxQueue`; db `subscribeFromNowResolvesAgainstOutboxForTheOutboxStore` | Two REST tests in `red1-rest-store`. The other two and the db test passed before the correction: the default was `outbox`. Mutation M1 fails them. |
| 3 | An existing subscription performs no position query, for either store | db `subscribeOnExistingSubscriptionReadsNoMessageTableForTheOutboxStore` and `...ForTheNativeStore`. A transaction holds `ACCESS EXCLUSIVE` on both tables. | Mutation M1 |
| 4 | A failed outbox position query fails the creation and creates no row | db `subscribeFailsAndCreatesNoRowWhenTheOutboxPositionQueryFails`. The table is locked, and `pg_cancel_backend` cancels the waiting query: SQLSTATE 57014. | Mutation M1 |
| 5 | A failed native position query fails the creation and creates no row | db `subscribeFailsAndCreatesNoRowWhenTheQueueMessagesPositionQueryFails` | `red2-db-store-stepA` |
| 6 | An unknown queue implementation fails and does not default to `outbox` | The four REST `...RefusesAQueueWithoutASubscriptionStore` (a hand-written factory, HTTP 501, no row); REST `subscriptionApiRefusesATopicThatIsNotAQueue` (HTTP 404, no row); db `subscribeWithoutAStoreIsRejectedAndCreatesNoRow`; api `QueueFactorySubscriptionStoreTest` | `red1-rest-store`, `red2-db-store-stepA`, mutation M2 |
| 7 | `ConsumerGroupHandler` uses the same topic for the subscription and the group | REST `consumerGroupApiUsesTheSameTopicForTheSubscriptionAndTheGroup`, `subscriptionSettingsApiActsOnTheSubscriptionUnderTheQueueName`. Each bound test also asserts one row, under the queue name. | `red1-rest-store` |
| 8 | A change of the queue implementation with a subscription is refused | REST `implementationChangeIsRefusedWhileTheQueueHasASubscription` (both add-queue routes), `recreationWithAnotherImplementationIsRefusedWhileTheQueueHasASubscription`; db `QueueImplementationChangeRuleCoreTest` | `red1-rest-store`: HTTP 503 from the DDL, not a refusal. Mutation M1 for the rule. |

Red and green by class. The green run is on the final tree.

| Module | Class | Red | Green |
|---|---|---|---|
| rest | `SubscriptionStoreRestIntegrationTest` (new) | `red1-rest-store`: 18 run, 16 failed, on the code before the correction. `red5-rest-store-mutation-m1`: 17 run, 8 failed. | `final6-rest-store`: 17 run, 0 failed |
| db | `SubscriptionStartBoundResolutionIntegrationTest` | `red2-db-store-stepA`: 9 run, 3 failed, with the store accepted and not used. `red3-db-store-mutation-m1`: 9 run, 6 failed. | `final7-db-store`: 9 run, 0 failed |
| db | `QueueImplementationChangeRuleCoreTest` (new) | `red4-db-change-rule-mutation-m1`: 3 run, 1 failed | `final8-db-change-rule`: 3 run, 0 failed |
| api | `QueueFactorySubscriptionStoreTest` (new) | `red8-api-default-store-mutation-m2`: 1 run, 1 failed | `final14-api-default-store`: 1 run, 0 failed |
| native | `ConsumerGroupSubscriptionIntegrationTest` | `red6-native-sub-it-mutation-m1`: 12 run, 1 failed | `final9-native-sub-it`: 12 run, 0 failed |
| native | `PgNativeConsumerGroupLifecycleTest` | `red9-native-lifecycle-core-mutation-m2`: 66 run, 1 failed | `final10-native-lifecycle-core`: 66 run, 0 failed |
| native | `PgNativeQueueFactoryIntegrationTest` | `red10-native-factory-it-mutation-m2`: 6 run, 1 failed | `final11-native-factory-it`: 6 run, 0 failed |
| outbox | `OutboxConsumerGroupSubscriptionTest` | `red7-outbox-sub-mutation-m1`: 19 run, 2 failed | `final12-outbox-sub`: 19 run, 0 failed |
| outbox | `OutboxFactoryIntegrationTest` | `red11-outbox-factory-it-mutation-m2`: 45 run, 1 failed | `final13-outbox-factory-it`: 45 run, 0 failed |

The first red run of the REST class held 18 tests. One was removed after it: the
re-creation of a deleted queue without a subscription. It fails at the trigger of the
queue template, and it cannot pass without a template change.

Mutation probes. Each was a temporary edit of production code. The six files were
restored, and their sha256 values match the values saved before the edit.

- M1, in `peegeeq-db`: the two tables swapped in the store-to-table mapping; the start
  position resolved before the row lock; the subscription count ignored in the refusal
  rule.
- M2: the `QueueFactory` default returns `OUTBOX`; the native factory returns `OUTBOX`;
  the outbox factory returns `NATIVE`; a native start without a database service accepts
  `FROM_NOW`.

Rebuilds. Each ended `BUILD SUCCESS`: `rebuild0-rest` (9 modules), `rebuild1-db-stepA`
(4), `rebuild2-all-stepB` (14), `rebuild3-db-mutation-m1` (4), `rebuild4-all-final` (14),
`rebuild5-mutation-m2` (6), `rebuild6-all-final` (14), `rebuild7-all-final` (14). The
final tree is the tree of `rebuild7-all-final`.

Regression runs on the final tree. One class per command.

| Module | Class | Log | Run, failed |
|---|---|---|---|
| db | `SubscriptionManagerIntegrationTest` | `reg12-db-manager-it` | 12, 0 |
| db | `SubscriptionLifecycleTransitionIntegrationTest` | `reg13-db-lifecycle` | 26, 0 |
| db | `SubscriptionManagerEdgeCaseTest` | `reg14-db-edge-case` | 10, 0 |
| db | `StartPositionDatabaseStateTest` | `reg15-db-start-position` | 6, 0 |
| db | `SubscriptionSettingsUpdateIntegrationTest` | `reg16-db-settings-update` | 18, 0 |
| db | `SubscriptionManagerCoreTest` | `reg17-db-manager-core` | 15, 0 |
| db | `BackfillServiceIntegrationTest` | `reg18-db-backfill-it` | 11, 0 |
| db | `SubscriptionStopGroupIntegrationTest` | `reg19-db-stop-group` | 10, 0 |
| native | `ConsumerGroupSubscriptionTest` | `reg20-native-cg-subscription` | 19, 0 |
| outbox | `OutboxConsumerGroupGracefulShutdownTest` | `reg21-outbox-graceful` | 10, 0 |
| rest | `ConsumerGroupSubscriptionIntegrationTest` | `reg1-rest-cg-subscription` | 15, 0 |
| rest | `SubscriptionPersistenceAcrossRestartIntegrationTest` | `reg2-rest-persistence` | 5, 0 |
| rest | `SubscriptionCreateAndBackfillIntegrationTest` | `reg3-rest-create-backfill` | 17, 0 |
| rest | `SubscriptionLifecycleRestIntegrationTest` | `reg4-rest-lifecycle-rest` | 4, 0 |
| rest | `SubscriptionLifecycleIntegrationTest` | `reg5-rest-lifecycle` | 7, 0 |
| rest | `ManagementApiIntegrationTest` | `reg6-rest-management-api` | 28, 0 |
| rest | `SubscriptionOptionsCoreTest` | `reg7-rest-options-core` | 10, 0 |
| rest | `DeadConsumerAlertingIntegrationTest` | `reg8-rest-dead-consumer-alerting` | 7, 0 |
| rest | `StoppedSubscriptionStatusIntegrationTest` | `reg9-rest-stopped-status` | 1, 0 |
| integration-tests | `RestClientConsumerGroupsContractSmokeTest` | `reg10-smoke-rest-client-cg` | 1, 0 |
| integration-tests | `SubscriptionLifecycleSmokeTest` | `reg11-smoke-subscription-lifecycle` | 6, 0 |
| examples | `LateJoiningConsumerDemoTest` | `reg22-examples-late-joining` | 3, 0 |
| examples | `DeadConsumerDetectionDemoTest` | `reg23-examples-dead-consumer` | 3, 0 |
| test-support | `OnSuccessExceptionSwallowingGuardTest` | `guard1-onsuccess` | 8, 0 |
| test-support | `VertxAsyncForbiddenPatternsGuardTest` | `guard2-forbidden-patterns` | 1, 0 |

Call sites of `subscribe`. A script inserted `SubscriptionStore.OUTBOX` as the third
argument at 218 call sites in 32 test files: 22 files of `peegeeq-db` (184 calls), 7 of
`peegeeq-benchmarking` (24), 2 of `peegeeq-examples` (8), 1 of `peegeeq-rest` (2). I did
not read these 32 files in full. The compiler accepted them. Two REST tests changed
their topic from `setupId-queueName` to the queue name:
`ConsumerGroupSubscriptionIntegrationTest` and
`SubscriptionPersistenceAcrossRestartIntegrationTest`.
`SubscriptionLifecycleIntegrationTest` takes the store from the queue factory.

Wrong judgments and deviations in this part.

- Five tests were written after the code they test: `QueueImplementationChangeRuleCoreTest`,
  `QueueFactorySubscriptionStoreTest`, the two factory store tests, and
  `withoutDatabaseService_fromNowFails`. Each was then seen red under a mutation probe.
- The first run after the implementation, `green2-rest-store`, failed four of 17 tests.
  The HTTP 501 tests did not declare the ERROR line of the access log. I had not read how
  the existing REST error tests declare a 5xx response before I wrote them.
- I read the queue template before I wrote the REST tests and saw that its trigger
  statement has no `OR REPLACE`. I still wrote a test that needs a second creation of a
  queue name to succeed. The red run showed that it cannot, and I removed the test.

Seen and not changed. Each is an existing violation of the standards.

- `PgNativeQueueFactoryIntegrationTest` has an empty catch block around `browser.close()`.
- `ManagementApiHandler.deleteQueue` discards the Future of `queueFactory.close()`.
- The class Javadoc of the native `ConsumerGroupSubscriptionIntegrationTest` cites
  production line numbers.
- The new and the changed native tests close their group in `.eventually(...)`.
  Correction 5 moves them to the cleanup helper.

Not checked in this part.

- The seven changed classes of `peegeeq-benchmarking` were compiled and not run. They
  are performance tests. A run of them is a Jenkins build, and Jenkins builds the pushed
  branch only. Nothing is pushed.
- No whole-module run, no `-Pall-tests` run, and no Jenkins build was made.
- Not run: every test class that the tables of this part do not name.

Not done in the first part of correction 1.

- The new native tests close their group in `.eventually(...)`, as the tests beside
  them do. Correction 5 moves them to the cleanup helper.
- No whole-module run, no `-Pall-tests` run, and no Jenkins build was made.
- Not run: every test class not named in the tables of this correction.

Found in phase 1, and not in the proposal before.

- N-11 (now in the proposal): a native group computed its start position from the
  options at every start and did not read the stored bound. Reproduced and corrected in
  increment 1b (`f20-p1b-red8-native-restart-bound`). OD-23 rules that a start uses the
  stored bound on both paths.
- A schema provisioned from the templates before this change keeps the old constraint.
  The template statement is `CREATE TABLE IF NOT EXISTS`, and no template alters an
  existing table. Such a schema rejects `STOPPED` until phase 2 gives it a migration path.
  Read, not reproduced.
- `EventSourcingCQRSDemoTest` discarded a failed `stopGracefully` in two places, and
  `NativeQueueFeatureTest` and `ConsumerGroupLoadBalancingDemoTest` in one place each.
  Corrected after the owner's review: correction 2 above.
- `Future.eventually` discards a failure of the cleanup it runs. Run on Vert.x 5.0.4, the
  version the project uses (`logs/f20-p1a-probe-eventually-20261012.log`). When the first
  `Future` succeeds and the supplied `Future` fails, the result succeeds with the first
  value. When the supplier throws, the result succeeds with a null value. When the first
  `Future` fails, the result fails with the first failure. A test that stops or closes a
  resource in `.eventually(...)` therefore passes when that stop or close fails.
  - 19 test lines pass `stopGracefully` to `eventually`: native `ConsumerGroupTest` (4),
    `PartitionedConsumerSafetyIntegrationTest` (4, and one more through its `closeGroups`
    helper), `PartitionedNativeConsumerIntegrationTest` (7), outbox
    `OutboxOffsetWatermarkOrderingTest` (3), and `OutboxOffsetWatermarkWiringTest` (1).
    Four tests that I wrote in phase 1 close their native group the same way
    (`ConsumerGroupSubscriptionIntegrationTest` 266, 309, 347, and 378), as the four
    tests beside them do.
  - 388 lines of the source trees contain `.eventually(`: 71 in production code and 317
    in tests. Which of them run a cleanup whose failure must be reported was not
    classified.
  - The coding principles state two things. Line 202: "The supplier's outcome does not
    change the outer Future's outcome". Line 208, in the example below it: "a failure in
    either step fails the test". The run shows that the second is false for the
    `eventually` step. The testing standard names `.eventually(() -> resource.close())` as
    the correct cleanup (lines 461 to 473, and Rule 7), and the project instructions
    require that a failed close fails the test. The two cannot both hold.
  - Not changed: no call site and neither standards document. The owner's correction
    list named the four places above. Changing the 19 lines by method name would leave
    the same defect in every other cleanup, and the standard that prescribes it. This
    needs a ruling.
  - Proposed correction, as a task of its own: one helper in `peegeeq-test-support` that
    runs every cleanup step and fails with the first failure, in the shape of
    `CleanupResultSupport.merge` of `peegeeq-examples`; the test cleanup sites moved to
    it; the two standards documents corrected; and a guard test beside
    `VertxAsyncForbiddenPatternsGuardTest`.

Not done in increment 1a.

- The management UI types do not list the new status: `src/api/types.ts` 110 and
  `src/pages/ConsumerGroups.tsx` 58. How the UI shows `"stopped"` was not run.
- The subscription health summary has no count for `STOPPED`
  (`SubscriptionManager.getSubscriptionHealthSummary`,
  `DeadConsumerDetector.getSubscriptionSummary`). That is increment 1c.
- The watermark, the write validator, and the insert trigger of `V010` still count `ACTIVE`
  only. A
  stopped group does not hold retention until increment 1c.
- The dead-group cleanup still runs from `cancel`, force-remove, and the detection job
  until increment 1d.

## Completed Work

Numbered Tasks 1 to 7 are recorded once, in their sections under Current Execution Order, and
are not repeated here. This table holds completed work that has no numbered section. Each row
is the single record of that work's verification evidence.

| Work item | Completion evidence |
|---|---|
| Consumer Groups UI Redesign | REST/UI lifecycle implemented; management UI suites passed in build #36 |
| Management UI tests not running | npm permissions and Playwright browser setup fixed; build #36 ran the UI suites |
| Test Integrity D1–D23 | D23 guard 2/2 and real-backend Playwright 36/36; previous phases recorded in archive |
| Outbox DLQ/filter/dead-code remediation | Wrong-architecture filter retry/DLQ layer removed; regression coverage added; Steps 1–7 complete |
| Outbox module audit O1–O4 | Consumer concurrency, lifecycle propagation, validation, and duplicate metrics fixed. Outbox concurrency: strict TDD failed 3/3 before implementation; final scope 6/6; async guard 1/1. O4 duplicate metrics: strict TDD initially failed because the meter was absent; final real-PostgreSQL contract 1/1; async guard 1/1; clean five-module build. O2 options-start lifecycle: controlled subscription-failure contract 1/1; state returned to `NEW`, no member became active, and the failure reached the caller |
| Outbox schema qualification | TC-S1–TC-S15 implemented; commit `32ab0371` records TC-S14 1/1 and TC-S15 1/1, with applicable asynchronous guards green |
| Schema processing remediation | Core remediation and P1–P3 complete; P3 1/1; P4 deliberately declined as test-only API exposure |
| Diagnostics isolation | `SystemInfoCollectorTest` 8/8; async guard 1/1; clean reactor build |
| Bitemporal examples expansion | Referenced examples exist and examples modules passed the full gate |
| Monitoring endpoints | WebSocket/SSE, lifecycle, CORS, and metrics work complete |
| Messaging pattern examples | Ten scenarios implemented; example modules passed the full gate |
| UI semantic Playwright and inventory remediation | Commit `322b7f06` closed the reviewed semantic coverage gaps: Management UI Vitest 128/128 across 14 files and Playwright 419/419; Utilities UI Vitest 836/836 across 55 files and Playwright 246/246. Inventory guards found 491 unique Management UI tests (419 functional + 72 screenshots) and 817 unique Utilities UI tests (246 functional + 571 screenshots), with type checks, builds, and lint green. The 2026-09-02 remediation wires both inventory guards into `test:all`, the command invoked by the Maven `all-tests` profiles and Jenkins full gate |
| Outbox capacity and retry-metrics CI remediation | Capacity/filter fairness (`1dd6741b`): failing starvation and capacity contracts preceded the fix; the focused integration scope passed 74 tests and the async guard passed 8. Retry metrics (`fe676bda`): three focused contracts failed before deterministic fail-once/succeed fixtures replaced permanently failing handlers; the two regression classes passed 10 and 14 tests; async guard 8/8. Full-module verification then passed 673 outbox tests in #44 |
| WebSocket subscription test ordering | `c62af5c3`: the test distinguishes automatic queue-tail readiness from the explicit subscription acknowledgement. `WebSocketHandlerTest` 6/6, `WebSocketMessageStreamIntegrationTest` 2/2, async guard 8/8; REST module passed 518 tests in #45. The original failure was observed in CI; the local pre-change run did not reproduce it deterministically |
| Concurrent consumer-group example test stabilisation | `a86bc056` (2026-09-24): `AdvancedProducerConsumerGroupTest` awaits every group's start Future before publishing, replaces periodic timer polling with message-driven completion, and composes group cleanup into the test lifecycle. This work was not entered here before implementation, and no test counts are recorded for it |
| Management queue-name search | `b19b708b`: #45's SSE browser test failed before publishing because the management endpoint ignored the search query, leaving the target queue off the first page. A real HTTP/PostgreSQL regression failed 4 of 7 cases before the production fix. Afterwards `ManagementQueueSearchIntegrationTest` 7/7, `ManagementApiIntegrationTest` 28/28, async guard 8/8, and focused filter/SSE Playwright 45/45 with retries disabled; both UI modules passed in resumed build #46. Required reactor rebuilds passed; the browser test was not weakened to hide the endpoint defect |

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
- **REJECTED** — the product decision was made against the proposal; no implementation work.
- **RELEASE GATE** — an explicit owner/CI validation run, not a normal edit/test phase.

When a task changes status, update this file in the same phase. Do not create another main task
plan.
