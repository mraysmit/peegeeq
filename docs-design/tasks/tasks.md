# PeeGeeQ Consolidated Task Register

**Status:** ACTIVE
**Last reconciled:** 2026-09-17
**Repository revision reviewed:** `263309d8` (`fix(docs): update reconciliation dates and status for transactional REST API designs`)
**Recorded from-beginning release baseline:** Jenkins build #36 at `e8d07e53`
**Latest successful release gate:** Jenkins build #11 at `263309d8` plus the checksummed
Task 6 release-gate overlay (`485c930dd264864cd9157fe3378e25e661c51b97afa2120d1cc5bde6c0b54f27`)
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

As of 2026-10-09 Task 8 is ACTIVE. Phase 7a architecture selection is complete.
Phase 7b.1 is complete. Phase 7b.2 has the coordinator port, the Consul adapter, node-local
state, supervisor-owned start and lease-loss shutdown, and local admission implemented and
tested in `peegeeq-pg-failover`.
Next in phase 7b.2 is the timer that calls `renew` on the HA interval, using
Docker/Testcontainers.
Optional watchdog integration, bootstrap, takeover, and production qualification remain open.
Machine-reset testing and additional host/VM access are not prerequisites for this next scope.
No task is OPEN. Tasks 1, 2, 3, 4, 6, and 7 are COMPLETE.
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
**Status:** ACTIVE — phase 7b.1 complete; phase 7b.2 partial, 2026-10-09
**Objective:** implement the design in
`docs-design/failover and resilience/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md` (design
revision 2026-10-08). HAProxy routes by sidecar writer eligibility, which combines current
node-owned lease, local admission, selected optional-watchdog checks, node identity, role,
and synchronous coverage. Manual profile B
precedes automatic profile A. Failover depends on a coordinator port, not on Consul: Consul is
the first adapter and Qraft a planned second (P-38). The port `PgLeaseCoordinator` and the
adapter `ConsulLeaseCoordinator` are implemented in `peegeeq-pg-failover`. Node supervision is
implemented there for guarded start, lease-loss shutdown, and local admission. The renewal
loop, promotion, and takeover are not implemented in that module.
G-7 qualification proceeds alongside
the common supervisor implementation. `peegeeq-service-manager` handles federation and is outside this task. No profile
requires the Patroni product. The Patroni control approach is selected: per-node supervisor,
local lease-loss shutdown, and optional independent watchdog protection. Planned watchdog
modes are `automatic` (default), `off`, and `required`. Only `required` refuses writer
start/promotion because the watchdog is unavailable. This correction superseded mandatory
watchdog wording in the companion designs, which were aligned on 2026-10-08. Optional support
does not prove old-writer exclusion during supervisor death or whole-VM pause.
Java changes follow strict TDD with real-component failure tests.

The implementation plan is
`docs-design/failover and resilience/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_IMPLEMENTATION_PLAN.md`.
It holds dated implementation findings and runs, adopted contracts P-0 to P-38, deployment
gates G-1 to G-7, acceptance obligations S1 to S59, and three design decisions for phase
7b.2 (OD-1 to OD-3). The implementer decided each one. The owner has not reviewed them.
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
   Remaining, in order: the timer that calls `renew` on the HA interval; optional watchdog
   integration; verified operator-initiated bootstrap. Preserve conservative ownership deadlines and late-reply
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

Test counts are recorded here only from a run made during the task.
The final phase 7b.1 per-class summaries, and the phase 7b.2 summaries dated 2026-10-08, were
reread during the 2026-10-08 reconciliation and no runtime test was rerun then. On 2026-10-08
the four design documents were aligned with the optional-watchdog decision and the coordinator
port (P-38). That was a documentation change with no test run. The `peegeeq-pg-failover` counts
dated 2026-10-09 come from runs made on that day. Known gaps remain owner review of OD-1 to
OD-3, the renewal loop, bootstrap, takeover/re-join,
TLS/restore, independent exclusion under supervisor/VM pause, the cause of the intermediate
HTTP 403 of 2026-10-08, and recovery timing.

Completion requires that every requirement is met, that every scenario has a passing
real-container test with a recorded per-class count, that every finding is closed, and that the
runbook has saved output for every scenario.

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
| Jenkins: another job's Docker network changes blank Management UI pages | Seen 2026-10-10 on `http://192.168.137.32:8080`, build 18 of `PeeGeeQ` at `e07142b2`. The build passed. In the Management UI Playwright run 4 of 419 tests failed their first attempt and passed on retry: `visualization-scope-selector.spec.ts:25`, `consumer-groups-validation.spec.ts:91`, `message-browser.spec.ts:217`, and `message-browser-advanced-filters.spec.ts:97`. Each waited 10 s for the first element of the page. The four failure screenshots are the same blank white image, and the browser logged `net::ERR_NETWORK_CHANGED` three times before the fourth failure. The other 415 Management UI tests and all 246 Utilities UI tests passed at the first attempt. Build 6 of the `Qraft` job ran on the same node from 13:57:51 to 14:06:19 and created or removed a Docker network 27 times between 14:00:44 and 14:06:14. The Playwright report places the four failed attempts at 14:01:09, 14:02:14, 14:03:26, and 14:06:13; `Qraft` network events are at 14:01:15, 14:02:12, 14:03:25, and 14:06:14. The node has 4 executors and no lock spans jobs; the Lockable Resources and Throttle Concurrent Builds plugins are not installed. Seen again the same day in build 19 at `75160f41`, which also passed. The Playwright report gives the start time of every attempt. 425 attempts passed and 3 failed, all three with the same blank image: `event-stores-scope-filter.spec.ts:122` at 16:57:46 and again at 16:58:10, and `message-browser.spec.ts:145` at 16:59:28. Build 11 of `Qraft` created or removed a Docker network 27 times from 16:57:44 to 17:03:32, with events at 16:57:44, 16:58:15, and 16:59:30. No attempt failed in the 18 minutes of the run before the first event. No attempt failed during the 24 later events. Each of the 7 failed attempts in the two builds overlaps a network event or starts at most 2 s after one. The link is that match in time plus the browser error in the first build; it was not reproduced on purpose | Approved 2026-10-10: keep the 4 executors, because running jobs side by side is the purpose of the server, and make only the two conflicting stages wait for each other. Phase 1 is done in `75160f41`: the `Jenkinsfile` runs the `all` suite as `Full regression: Java modules` and `Full regression: UI modules`, and `scripts/ci/regression-stages.mjs` derives the Maven arguments of each stage (12 contracts in `scripts/ci/regression-stages.test.mjs`). Build 19, resumed at `peegeeq-integration-tests`, ran both stages and published 1,738 passing tests. Build 20, resumed at `peegeeq-utilities-ui`, ran no test in the Java stage, ran that one module in the UI stage, and published 1,082 passing tests. Phase 2 is done on the server, 2026-10-10 17:58: Lockable Resources `1560.va_b_cd589f23eb_` is installed and active, 93 plugins against 92 before, no plugin upgraded, no restart required, and the server's list of pipeline steps now contains `lock`. Phase 3 is in the working trees of both repositories and has not run on Jenkins: the `PeeGeeQ` UI stage and the `Qraft` `Docker cluster tests` stage (`https://github.com/mraysmit/qraft.git`, branch `main`) each hold the lock `host-network-interfaces`. Open: show in a run that the two stages wait for each other. Known limits: a `Qraft` build can wait about 38 minutes at its Docker stage, and that wait counts against its 60-minute timeout; the `PeeGeeQ-Cache` job does not hold the lock; a Jenkins server without the plugin has no `lock` step, which the `all` suite now uses. Retries hide these failures in the build result |

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
    passes. No production code was changed. The fix
    needs a decision: keep the scan order and make the position a `(created_at, id)` pair
    compared with a row comparison, or record rejections per group so a rejected row is not
    claimed again by that group.

The per-row tables, with the trigger and the evidence line for all 318 calls, were produced in
the review session and are not in the repository.

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
