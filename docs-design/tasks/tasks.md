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

As of 2026-10-08 Task 8 is ACTIVE. Phase 7a architecture selection is complete.
Phase 7b.1 is complete. Phase 7b.2 has a verified Consul protocol implementation.
Next is phase 7b.2 local supervision and admission using Docker/Testcontainers.
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
**Status:** ACTIVE — phase 7b.1 complete; phase 7b.2 partial, 2026-10-08
**Objective:** implement the design in
`docs-design/failover and resilience/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md` (design
revision 2026-10-08). HAProxy routes by sidecar writer eligibility, which combines current
node-owned lease, local admission, selected optional-watchdog checks, node identity, role,
and synchronous coverage. Manual profile B
precedes automatic profile A. The Consul protocol is implemented in `peegeeq-pg-failover`.
Node supervision and takeover are not implemented in that module.
Consul is selected for the initial A/B implementation; G-7 qualification proceeds alongside
the common supervisor implementation. `peegeeq-service-manager` handles federation and is outside this task. No profile
requires the Patroni product. The Patroni control approach is selected: per-node supervisor,
local lease-loss shutdown, and optional independent watchdog protection. Planned watchdog
modes are `automatic` (default), `off`, and `required`. Only `required` refuses writer
start/promotion because the watchdog is unavailable. This correction supersedes mandatory
watchdog wording in companion designs, which still require alignment. Optional support
does not prove old-writer exclusion during supervisor death or whole-VM pause.
Java changes follow strict TDD with real-component failure tests.

The implementation plan is
`docs-design/failover and resilience/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_IMPLEMENTATION_PLAN.md`.
It holds dated implementation findings and runs, adopted contracts P-0 to P-37, deployment
gates G-1 to G-7, and acceptance obligations S1 to S58. Status and execution order are controlled
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
   30-second deadline. The current source and result require fresh verification. P-14 now
   retains profile D for connection recovery and moves production convergence to phase 7.
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
   ownership. This protocol has no PostgreSQL start, promotion, release, or admission path.
   Remaining: correct unconditional watchdog timing coupling in `PgNodeConfig` and the
   elector's freshness calculation, implement local supervision/admission and durable
   grants/quarantine/receipts, then optional watchdog integration and verified operator-initiated
   bootstrap. Preserve conservative ownership deadlines and late-reply rejection. Complete
   G-7 qualification alongside these scopes. The next scope uses Docker/Testcontainers;
   machine-reset infrastructure is not a prerequisite. Rebuild, verify, and report local
   supervision/admission before bootstrap. **OPEN — 7b.3:** manual
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
    all S1 to S58 cases and measure the 45-second objective under a specified workload.
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

Test counts are recorded here only from a run made during the task.
The final phase 7b.1 and 7b.2 per-class summaries were reread during this reconciliation.
No runtime test was rerun. Known gaps remain companion design alignment, local supervision
and bootstrap, takeover/re-join, TLS/restore, independent exclusion under supervisor/VM pause,
the intermediate HTTP 403 cause, and recovery timing.

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
