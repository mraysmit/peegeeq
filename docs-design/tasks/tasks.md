# PeeGeeQ Consolidated Task Register

**Status:** ACTIVE
**Last reconciled:** 2026-09-05
**Repository revision reviewed:** `19e3cbdb` plus the Tasks 4.2–4.6 and Task 7 working-tree implementation
**Recorded from-beginning release baseline:** Jenkins build #36 at `e8d07e53`
**Latest successful resumed gate:** Jenkins build #48 at `19e3cbdb` plus the checksummed Task 7 working-tree overlay (both UI modules only)

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
with the larger functional-plus-screenshot inventory recorded below.

These passes belong to their respective revisions. They do **not** establish a fresh,
from-beginning full-suite pass at `b19b708b`. Such a run remains an explicit release validation,
not a requirement to rerun every module during a focused fix.

**Historical reporting gap:** #46's console reported `No test report files were found.` The existing
Jenkins publisher selects Surefire/Failsafe XML and allows empty results. The UI counts above
were verified from execution logs, not Jenkins' published test-result totals. This does not
change the observed SUCCESS result. Task 7 closed this gap in build #48.

### Focused verification and remediation evidence

Earlier focused verification is retained below. It must not be read as a single full gate
at the current revision:

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
- PgBouncer transaction pooling Task 3.4: strict TDD first failed 1/1 because PgBouncer rejected
  the `search_path` startup parameter, then failed with PostgreSQL `42P01` after PgBouncer accepted
  but could not restore that parameter on a multiplexed backend. The final real PgBouncer contract
  passed 1/1 with one backend PID shared across four alternating tenant transactions. Connection-
  manager regressions passed 22/22, schema regressions passed 9/9, the pool circuit-breaker
  regression passed 1/1, and the four-module reactor slice built cleanly.
- Durable subscriptions Task 4.1: strict TDD compilation failed on the absent durable options and
  bitemporal metadata model. The final API scope passed 34/34: `SubscriptionOptionsDurableTest`
  6/6, `BiTemporalSubscriptionInfoTest` 2/2, and the existing `SubscriptionOptionsValidationTest`
  26/26. The six-module bitemporal dependency slice compiled cleanly and async guards passed 9/9.
- Durable subscriptions Task 4.2 (2026-09-05, local working tree): the initial contract failed
  compilation on the missing coordinator/factory. A separate delivery-boundary contract then
  failed 1/1 when metadata registration incorrectly reported subscription success; registration
  is now a separate operation and durable delivery fails explicitly until implemented. The first
  complete persistence run exposed four lifecycle failures; explicit SQL parameter typing fixed
  them and the four-test rerun passed. After the required clean six-module rebuild, the final
  `integration-tests` scope passed `DurableBiTemporalSubscriptionIntegrationTest` 34/34 and
  `BiTemporalAppendMetricsIntegrationTest` 4/4. Core regressions passed
  `SubscriptionOptionsDurableTest` 6/6, `BiTemporalSubscriptionInfoTest` 2/2,
  `SubscriptionOptionsValidationTest` 26/26 (nested classes: Builder 11, Equals/HashCode 4,
  ToString 1, EdgeCase 7, FluentAPI 3), and `OnSuccessExceptionSwallowingGuardTest` 8/8.
  All 80 final checks passed without failures/errors/skips. This is focused local evidence,
  not a Jenkins rerun or a new full-suite release gate.
- Outbox capacity/filter fairness (`1dd6741b`): failing starvation and capacity contracts
  preceded the fix; the focused integration scope passed 74 tests and the async guard passed 8.
  Subsequent full-module verification passed 673 tests in #44 after the retry-metrics fix.
- Outbox retry metrics (`fe676bda`): three focused contracts failed before deterministic
  fail-once/succeed fixtures replaced permanently failing handlers. The two regression classes
  passed 10 and 14 tests, respectively; the async guard passed 8/8.
- WebSocket subscription ordering (`c62af5c3`): the test now distinguishes automatic queue-tail
  readiness from the explicit subscription acknowledgement. `WebSocketHandlerTest` passed 6/6,
  `WebSocketMessageStreamIntegrationTest` passed 2/2, and the async guard passed 8/8. The original
  failure was observed in CI; the local pre-change run did not reproduce it deterministically.
- Queue-name search (`b19b708b`): #45's SSE browser test failed before publishing because the
  management endpoint ignored the search query, leaving the target queue off the first page.
  A real HTTP/PostgreSQL regression failed 4 of 7 cases before the production fix. Afterwards,
  `ManagementQueueSearchIntegrationTest` passed 7/7, `ManagementApiIntegrationTest` passed 28/28,
  and the async guard passed 8/8. The focused filter/SSE Playwright scope passed 45/45 with
  retries disabled, followed by the successful #46 UI gate. Required reactor rebuilds passed;
  the browser test was not weakened to hide the endpoint defect.

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
**Status:** COMPLETE — locally verified 2026-09-05; no new full-suite/Jenkins gate claimed

Implementation record:

1. **COMPLETE — 2026-09-02.** Defined the separate `BiTemporalSubscriptionService` lifecycle API,
   shared `DurableSubscriptionCoordinator` cursor contract, and immutable
   `BiTemporalSubscriptionInfo` metadata view. `SubscriptionOptions` now supports opt-in durability,
   stable subscription and consumer identity, and a positive bounded replay batch size while
   preserving non-durable defaults. This phase defines contracts only; it does not claim a runtime
   implementation or database behavior.
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
3. **COMPLETE — 2026-09-05 (local working tree).** Typed finite replay fetches bounded ID-ordered
   batches, applies event/aggregate filters, and acknowledges only successful handlers. A short
   READ COMMITTED SHARE-lock barrier waits for pending inserts before capturing the boundary;
   the lock is released before handlers run. This requires the standard append-only ID sequence
   (ascending, non-cycling, CACHE 1, allocated by INSERT); explicit/preallocated IDs and sequence
   resets are unsupported. Lock waits fail after five seconds rather than skipping history.
   TDD first failed on unsupported replay, then reproduced a delayed lower-ID commit being
   skipped. Final clean reactor rebuild and integration scope: replay 4/4, persistence 34/34;
   async guard 8/8. These are focused local results, not a new Jenkins release gate.
4. **COMPLETE — 2026-09-05 (local working tree).** Typed subscribe establishes LISTEN before
   its first finite replay. Notifications only request another ordered scan; coalesced scans
   and one-second reconciliation cover the handoff and missed notifications. Handlers are
   serialized, close drains delivery, and `deliveryCompletion` surfaces terminal errors.
   A real PostgreSQL test appends during catch-up and again during live delivery, asserting
   ordered, duplicate-free delivery. Focused replay/handoff 5/5, persistence 34/34, guard 8/8.
5. **COMPLETE — 2026-09-05 (local working tree).** V020 and the fresh-schema template add
   expiring UUID owner leases and monotonically increasing generations. Each finite scan
   claims, renews, and releases its lease; live contenders reconcile as standbys while owned.
   Standalone catch-up fails explicitly when busy. Expiry permits takeover, but stale owners
   cannot commit acknowledgements. Reset/pause/cancel revoke old generations; administrative
   advancement cannot bypass a live lease. Delivery remains at-least-once across crashes or
   takeover: external handler side effects require idempotency. Focused PostgreSQL replay,
   ownership, takeover, renewal, and persistence: 42/42; migrations 11/11; async guard 8/8.
6. **COMPLETE — 2026-09-05 (local working tree).** Real PostgreSQL delivery contracts cover
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
SCM revision `19e3cbdba2b6a5691f4473fc3e38033212dee3ec`, then applied the uncommitted Task 7
working-tree files from `/tmp/peegeeq-task7-overlay.tar`. The replay verified the archive before
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
`target/ui-reports/{vitest,playwright}.xml`. The reporting implementation remains uncommitted;
build #48 is reproducible evidence for the exact recorded SCM revision plus overlay hash, not a
claim that a plain SCM build already contains the working-tree changes.

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

Tasks 4.3, 4.4, 4.5, 4.6, and 7 are complete. Task 4 remains focused local PostgreSQL evidence;
Task 7 additionally has the successful remote Jenkins publication evidence recorded above.

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
| PgBouncer transaction pooling | Task 3.4 complete: tenant schema state is applied transaction-locally, session state is reset, and two logical clients safely multiplexed one real backend connection across four transactions; green 1/1 |
| Durable subscription public API | Task 4.1 complete: opt-in durable options, bitemporal lifecycle and metadata contracts, and the shared cursor coordinator surface are defined; focused API scope green 34/34 |
| Durable subscription persistence | Task 4.2 complete: supported event-store factory, tenant-local definitions/lifecycle/heartbeats, transactional cursors, and explicit unavailable-delivery boundary; persistence 34/34, append regressions 4/4, API 34/34, async guard 8/8 |
| Outbox capacity and retry-metrics CI remediation | `1dd6741b` and `fe676bda`; focused regressions green, then 673 outbox tests passed in #44 |
| WebSocket subscription test ordering | `c62af5c3`; readiness and explicit acknowledgement distinguished; REST module passed 518 tests in #45 |
| Management queue-name search | `b19b708b`; real HTTP/PostgreSQL regression 7/7, management API regression 28/28, focused Playwright 45/45; both UI modules passed in resumed build #46 |

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
