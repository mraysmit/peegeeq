# Session Handover — 2026-08-12 (reconciled 2026-08-27)

**Status:** SUPERSEDED by
[`TEST-INTEGRITY-DEFECT-REMEDIATION-PLAN.md`](TEST-INTEGRITY-DEFECT-REMEDIATION-PLAN.md)
P12 and Jenkins build #36. This handover is historical context, not the current task list.
The final release gate has now run successfully from the beginning.

The dated continuation below records the current implementation review snapshot.
[`tasks.md`](tasks.md) remains authoritative as work is reproduced, implemented, and verified.

Companion to `TEST-INTEGRITY-DEFECT-REMEDIATION-PLAN.md`, which is the defect register.
This document covers what happened in this session, what state the working tree is in,
and what the next person needs to know before touching it.

## 2026-08-28 continuation (priorities 1-6 complete)

This section supersedes the status of items 1-6 in the 2026-08-27 queue below. The work
was completed in bounded phases with a clean affected-reactor rebuild before each focused
test run. `-Pall-tests` was not run; it remains the owner/release gate.

1. **Outbox consumer-group startup failure propagation: COMPLETE.**
   `OutboxConsumerGroup.startInternal(boolean)` now returns and composes the subscription
   Future. Members start and the group enters `ACTIVE` only after subscription readiness;
   startup failure restores `NEW` and reaches the public start Future. The full nested
   `OutboxConsumerGroupCoreTest` contract passed 64/64.

2. **Bundled pool timeout properties: COMPLETE.**
   Bundled profiles use `connection-timeout-ms` and `idle-timeout-ms` with deliberately
   converted numeric values. `PgPoolConfigPropertyBindingTest` passed 28/28 across the
   bundled profiles and expected timeout values.

3. **Dead `pool.min-size` contract: COMPLETE (removed).**
   The unsupported property was removed from production configuration surfaces, bundled
   resources, examples, Spring configuration bindings, and documentation. Remaining
   tracked references are negative contract assertions proving the key is absent or
   rejected. Focused verification passed: DB core 32/32, examples core 21/21, DB example
   2/2, bitemporal 1/1, native 1/1, outbox 1/1, and five Spring configuration classes 1/1
   each.

4. **Known async test-standard violations: COMPLETE.**
   `TracePropagationTest` now uses terminal success/failure handlers and passed 2/2.
   `ResilienceSmokeTest` now uses composed Futures and Vert.x test-context completion
   instead of blocking latches and passed 2/2.

5. **Discarded-subscription Future ratchet: COMPLETE at zero.**
   The baseline moved from 98 violations across 31 files to a header-only CSV. Subscription
   readiness now gates dependent sends, multi-consumer readiness is combined, and failures
   are terminally observed. During the final native phase, `RetryableErrorIT` was also made
   discoverable under the integration profile and its raw JDBC fixture was replaced with a
   Vert.x SQL client plus a deterministic sequence-backed `40P01` trigger.

   Focused final evidence: integration-tests 2/2; Spring integration 11/11 and performance
   3/3; examples 18/18; native groups 12/12, 7/7, 17/17, 18/18, and 13/13. The final
   zero-baseline `noDiscardedFuturesFromSubscribeInTestSources` guard passed 1/1.

6. **Remaining schema and instance-isolation coverage: COMPLETE.**
   TC-S14 is implemented by `OutboxSchemaSubscriptionIsolationTest`: two schemas use the
   same topic and subscription names without sharing delivery or subscription state. The
   clean five-module outbox reactor build passed, the targeted integration contract passed
   1/1, and the applicable async guard scope passed 7/7.

   TC-S15 is implemented by `OutboxOffsetWatermarkSchemaIsolationTest`: two schemas using
   the same topic and consumer-group names retain independent `OFFSET_WATERMARK` offsets and
   watermarks. The clean five-module outbox reactor build passed, the targeted integration
   contract passed 1/1, and the applicable async guard scope passed 7/7.

   The P3 negative multi-setup contract is implemented by
   `MultiSetupNativeCrossIsolationTest`. Both `LISTEN_NOTIFY_ONLY` consumers subscribe before
   production begins; setup A receives its own marker, setup B never observes that marker,
   and setup B's original live consumer receives a later setup B marker after setup A is
   destroyed. The clean 11-module integration-test reactor build passed, the targeted
   integration contract passed 1/1, and `VertxAsyncForbiddenPatternsGuardTest` passed 1/1.
   All three contracts passed against the existing runtime, so this priority required no
   production change.

**Next implementation item:** priority 7 below, replacing the D23 UI SSE fixed timing
assumption with observed readiness and verifying the focused real-backend Playwright scope.

## 2026-08-27 continuation (prioritized outstanding implementation work)

This queue comes from static source and configuration review at the current HEAD. It identifies
contracts that need implementation or focused coverage; it does not claim untested runtime
behavior as fact. Execute one numbered item as one phase, perform the mandatory reactor rebuild
after any Java or Maven change, and run the smallest applicable test scope before moving on.

1. **Fix outbox consumer-group startup failure propagation (highest priority, known fix).**
   `OutboxConsumerGroup.startInternal()` currently initiates the subscription from
   `OutboxConsumerGroup.java:532`, attaches only failure logging, starts members immediately,
   and marks the group active without composing subscription settlement into the returned
   startup contract. Make `startInternal()` return `Future<Void>`, compose subscription first,
   start members and enter `ACTIVE` only after subscription success, and restore `NEW` on
   failure. Add a controlled failure-path contract that proves the public start Future fails and
   no member becomes active. This is the first implementation phase to take.

2. **Canonicalize the bundled pool timeout properties (high priority, known fix).**
   `PeeGeeQConfiguration.java:451` reads `connection-timeout-ms` and `idle-timeout-ms`, while
   eight bundled profiles still declare the older names without the `-ms` suffix:
   `bitemporal-optimized`, `extreme-performance`, `high-performance`, `high-throughput`,
   `low-latency`, `parallel-test`, `reliable`, and `vertx5-optimized`. Correct each resource
   value deliberately because some profiles use ISO-8601 durations such as `PT10S`; do not use
   a blind key rename. Add one parameterized contract that loads every bundled profile and
   asserts both timeout values bind to the expected pool settings.

3. **Remove or deliberately implement the dead `pool.min-size` contract (high priority).**
   The property is accepted and validated in `PeeGeeQConfiguration.java:269` but is not applied
   to `PgPoolConfig`. Local inspection of the configured Vert.x 5.0.8 `PoolOptions` API found no
   minimum-size setting. Prefer removing the advertised property, validation, and bundled
   resource entries unless the owner explicitly chooses a separate connection pre-warming
   design. Pin the selected public configuration contract with a focused test.

4. **Correct the two known async test-standard violations (high priority).**
   `ResilienceSmokeTest.java:29` justifies latch-based blocking waits even though the testing
   standard requires `VertxTestContext`-driven Future completion in extension-managed tests.
   `TracePropagationTest.java:105` uses a legacy completion callback with an explicit success
   branch instead of terminal success and failure handlers. Rewrite each class as its own phase,
   preserving its existing assertions and using composed Futures and terminal failure
   propagation. These are known conformance fixes, not new production behavior.

5. **Drive the discarded-subscription Future ratchet down in bounded phases.**
   The current Tier 9 baseline is 98 discarded `subscribe()` Futures across 31 files: native
   61/18, examples 20/7, examples-spring 14/5, and integration-tests 3/1. Remediate one file or
   tightly related class group per phase, make startup ordering explicit, surface every failure,
   rebuild the affected reactor slice, run that class's tagged scope, and lower the baseline in
   the same change. Do not treat the aggregate count as one mechanical edit.

6. **Complete the remaining schema and instance-isolation coverage.**
   Add TC-S14 for `OutboxConsumerGroup.start(SubscriptionOptions)` across two schemas and TC-S15
   for full `OFFSET_WATERMARK` offset and watermark isolation. Add the P3 negative multi-setup
   contract and prove that destroying setup A does not disturb setup B's live consumer. Treat the
   proposed P4 direct distinct-Vert.x guard as optional: prefer P1/P3 behavior contracts unless a
   production accessor is independently justified rather than exposed only for a test.

7. **Replace the D23 UI SSE timing assumption with observed readiness.**
   `queue-updates-sse.spec.ts:297` currently relies on a fixed one-second wait. Observe the SSE
   response or an equivalent readiness signal before publishing, then retain the end-to-end
   assertion on the received update. Verify the focused Playwright project against the real
   backend and report its exact test count.

8. **Decide and enforce `OutboxConsumerConfig.consumerThreads` semantics.**
   The production getter is currently unused. Decide whether to remove the option or define its
   concurrency and ordering guarantees. If retained, first add contracts for per-key ordering,
   handler concurrency, and overlapping poll cycles; then implement against those contracts.
   Do not imply parallel delivery merely by retaining an inert setting.

9. **Remove the remaining instance-scoping leak in diagnostics.**
   `SystemInfoCollector.java:198` falls back to PeeGeeQ-wide system properties when its
   configuration is null. This is lower-risk diagnostic code, but it conflicts with the
   instance-scoped configuration direction. Pass the owning instance configuration explicitly
   or define a truthful no-configuration result, with a focused multi-instance contract.

10. **Finish the low-risk observability and documentation cleanup.**
    Add the O4 duplicate/idempotency metric with its contract, then correct the outbox audit's
    stale O3 summary so it agrees with the body and task index that already mark O3 fixed. Keep
    the metric implementation and documentation correction as separate phases.

The following are deliberately **not** in the implementation-fix queue: durable subscriptions,
transactional REST, schema registry, authentication, and automated HA are product initiatives;
partitioned-consumption load and chaos scenarios are release-qualification work. Scope and fund
those independently rather than mixing them into the known-fix phases above.

## 2026-08-23 P11 completion (D17 and D18 fixed)

This section supersedes every older completion and next-work statement below. Historical sections
remain for audit context.

- **D17 is reproduced and fixed.** A controlled pending-subscription contract proved that
  `PgNativeConsumerGroup.close()` could settle before the shared startup continuation aborted.
  The red run failed 1/1. Close now waits for the pending start result before final resource
  cleanup, partitioned startup uses a stable local engine reference, and field clearing is
  conditional on that engine still being current. The focused contract passes 1/1, the final
  nested lifecycle class passes 65/65, and the live partitioned regression passes 1/1.
- **D18's complete P11 inventory is fixed.** Ten discarded factory-close Futures, six discarded
  group-start Futures, three log-and-continue group cleanup handlers, and the empty Vert.x close
  handler were remediated class by class. Factory and manager teardown is dependency ordered and
  failure preserving; starts precede sends; group stops settle before test completion; and the
  partitioned fixture uses its extension-owned Vert.x instance.
- The standardized consumer-mode performance class required a wider correction because the full
  file also contained latch bridges, discarded subscribe/send Futures, silent cleanup catches,
  and placeholder latency values. It now uses composed Futures and measures actual delivered
  message latency. Its first focused run correctly exposed missing schema migration for each new
  profile container; that run was stopped, schema initialization was restored, and the class then
  passed all 16 parameterized invocations.
- Required clean native reactor rebuilds passed after every Java sub-phase and compiled all 61
  native test sources. Final affected results are: partitioned core 37/37, consumer-group
  integration 26/26, six consumer-mode classes 26/26, standardized performance 16/16, legacy
  performance 2/2, consumer-group example 1/1, final lifecycle 65/65, and live partitioned 1/1.
  Green D18 logs contain no ERROR or unhandled-exception lines. The lifecycle log contains ten
  intentional, method-declared startup-failure ERROR records.
- Repository integrity guards pass 10/10: disabled tests 2/2, lifecycle/discarded-Future guards
  7/7, and forbidden async patterns 1/1. The Tier 9 baseline was tightened by deleting the two
  rows remediated in P11. Tier 7 permits zero discarded stop/close Futures repository-wide.
- At this checkpoint, the separate Tier 9 ratchet recorded 63 pre-existing discarded
  `subscribe()` Futures in 19 other native test files. The current baseline is 98 calls
  across 31 files overall, including 61 calls across 18 native files. This is explicit
  remaining debt, not part of the enumerated D18
  lifecycle inventory and not evidence against the completed P11 contracts. It should be handled
  as a new phased backlog if the owner wants the subscribe ratchet driven to zero.
- Authoritative logs include `p11-d17-red-test-nested-20260823.txt`,
  `p11-d17-green-test-20260823.txt`, `p11-final-consumer-group-lifecycle-20260823.txt`,
  `p11-d18-group-a-partitioned-core-green-20260823.txt`,
  `p11-d18-group-a-consumer-group-integration-20260823.txt`,
  `p11-d18-group-b-consumer-mode-20260823.txt`,
  `p11-d18-performance-standardized-test-green-20260823.txt`,
  `p11-d18-performance-legacy-test-20260823.txt`,
  `p11-d18-consumer-group-example-test-20260823.txt`,
  `p11-d18-repository-async-guards-green-20260823.txt`, and
  `p11-d18-disabled-tests-guard-20260823.txt`.
- No commit was created. The untracked Jenkins/WSL documentation files are unrelated user files
  and were preserved. `PgNativeQueueConsumerCapacityIT` remains the untracked P10 contract to
  include with the eventual native commit. `git diff --check` is clean apart from line-ending
  conversion notices. The approximately 90-minute owner-run `-Pall-tests` release gate was not
  run; it is the next release-level verification, not part of the focused edit-test loop.

## 2026-08-23 P11 pre-implementation audit (D17 not reproduced, D18 expanded)

This section supersedes the completion and next-work statements in every older section below.
The historical sections remain for audit context.

- The current base commit is `446893de` (`docs: update session handover dates and clarify
  deferred tasks in implementation plan`). The uncommitted native worktree contains the P8-P10
  production, test, and defect-register changes. This handover is also modified by the final
  review, and `PgNativeQueueConsumerCapacityIT` remains a new untracked source file until the
  eventual commit.
- D14-D16 retain their completed red/green evidence: startup readiness and failure rollback,
  handler/terminal-persistence shutdown drain, and atomic native capacity admission. The final
  P10 contract passed 1/1 with a private `maxSize=1, shared=false` PostgreSQL pool and deleted all
  32 distinct messages exactly once. The affected native regression, lifecycle, active-fetch,
  and quality-guard scopes previously passed 21/21, 64/64, 1/1, and 10/10 respectively.
- **D17 was exposed by static final-diff review and is `NOT REPRODUCED`.** When close wins while
  `PgNativeConsumerGroup` is `STARTING`, `close()` snapshots the engine/consumer currently stored
  in fields but does not compose the pending `startFuture`. A delayed mode-detection,
  subscription, or engine-start continuation can therefore create and stop resources after the
  public close Future has already settled. The partitioned abort continuation also calls
  `partitionedEngine.stop()` through the mutable field that close may have set to null, rather
  than through a stable local engine reference.
- The existing partitioned close-race integration test does not settle D17. It composes on
  `group.close()` and then waits for the separately captured startup Future to abort. That proves
  eventual startup cleanup and terminal `CLOSED` state, but it does not prove that close itself
  owns and awaits the cleanup boundary. The lifecycle test named "close during mode detection"
  uses an immediately failed missing-pool path rather than a controlled pending continuation.
- **D18 is a systemic open test-integrity violation, not one isolated teardown.** A whole-module
  static audit found 10 discarded `factory.close()` Futures across 10 native test classes. Those
  teardowns can start manager shutdown before factory-owned consumers have drained. The audit also
  found 6 discarded consumer-group `start()` Futures, 3 group cleanup handlers that only warn, and
  1 empty Vert.x close failure handler. The exact inventory is in P11 of the companion register.
  These sites predate the P8-P10 edits but directly contradict the current async/testing rules and
  cannot remain in the final change set.
- The companion defect register now contains planned P11. Strict TDD must first reproduce D17
  with controlled pending startup, make close await the shared startup-abort/resource-cleanup
  boundary, and use stable resource references. D18 then needs composed, aggregate,
  failure-propagating producer/factory/manager teardown. Required rebuilds, focused tests,
  regressions, and guards follow only after Java changes.
- The owner authorized rebuilding but not test execution. The clean native reactor rebuild passed
  on 2026-08-23 with `-DskipTests`, compiling all 61 native test sources; its log is
  `p11-preimplementation-rebuild-20260823.txt`. No test ran. The remaining audit used source/diff
  inspection, prohibited-pattern scans, and `git diff --check`. No newly added Mockito, disabled
  test, blocking bridge, sleep, `.recover`, or `.otherwise` pattern was found, and the diff
  whitespace check is clean.
- Do not commit this worktree and do not start the owner-run `-Pall-tests` release gate yet.
  D17 and D18 must be remediated and verified first.

## 2026-08-21 post-commit reconciliation (D1-D13 complete)

This section supersedes the status and next-work statements in every older section below.
The historical sections remain for audit context.

- Branch `master` and `origin/master` are aligned at `5ac99ac3`
  (`fix: close test-integrity gaps and async shutdown races`). The P4/D3/D7, P6/D11, and
  D10/D12 implementation, test, Maven, resource, and documentation changes are committed,
  and the post-commit check found a clean working tree.
- **D10 and D12 are fixed.** The reproducible defect was not slow cleanup: the public
  `closeReactive()` composition could lose its terminal signal when manager-owned Vert.x was
  terminated before that signal reached a caller composed on the same context. The method now
  caches one context-free public close Future, settles it after managed-component cleanup, and
  only then initiates manager-owned Vert.x termination. The termination Future remains observed
  and failures remain surfaced in the log.
- A regression contract failed before the manager fix and passed afterward. The final manager
  settlement class passes 2/2, the native class containing the original 60-second teardown
  observation passes 5/5, close log-level contracts pass 4/4, and resource-leak coverage passes
  1/1. The timeout budget was not increased.
- The downstream examples scope then exposed the same repeated-close contract defect in
  `OutboxConsumerGroup`: a second close call returned immediate success while the first close
  still awaited an in-flight handler. `close()` now caches and returns its actual settlement
  Future. The focused contract failed 1/1 before the fix and passes 1/1 afterward;
  `ConsumerGroupResilienceTest` passes 3/3.
- The manager error-propagation class initially failed as a parallel pair because both methods
  declared the same active expected-ERROR signature, making ownership correctly ambiguous
  under D11. The cleanup method now induces an authentication failure while its companion keeps
  the missing-schema failure, and both declare exact non-overlapping ERROR contracts. Parallel
  execution remains enabled and the class passes 2/2.
- The post-change banned-pattern scan is zero for the three close-settlement files changed in
  the final phases. The repository async integrity guards pass 8/8
  (`OnSuccessExceptionSwallowingGuardTest` 7/7 and
  `VertxAsyncForbiddenPatternsGuardTest` 1/1).
- The defect register audit now records every original item D1 through D13 as `FIXED`. No
  registered implementation defect remains open. The approximately 90-minute `-Pall-tests`
  release gate was not run; it remains an owner-run release gate rather than an edit-test-loop
  requirement.
- The next independent workstream is `peegeeq-utilities-ui`. Phase G.1b is already complete;
  T.6 was explicitly deferred on 2026-08-21. Its effort bands, architectural constraints,
  acceptance criteria, and restart triggers are preserved in the utilities UI implementation
  plan; dedicated-queue-per-run remains the current isolation contract.

### Committed state

Commit `5ac99ac3` records the completed remediation as one internally coherent change set of
40 paths: 25 modified and 15 new. It spans:

- root/test-support Maven activation plus the structured expected-ERROR model, capture
  coordinator, JUnit extension, service registration, and executable gate contracts;
- database background-task escalation/health policy, manager close settlement, and their
  focused behavior and integration contracts;
- outbox migrated-schema coverage, structured ERROR expectations, and repeated-close
  settlement;
- examples expected-ERROR migration;
- this handover and the companion defect register.

The `logs/` evidence files were intentionally excluded from the commit. `master` and
`origin/master` both resolve to `5ac99ac3`, and `git status -sb` reports no divergence or
working-tree changes before this documentation-only reconciliation. No additional build or
test run is required for this wording update. The owner-run `-Pall-tests` release gate remains
outstanding by design.

### D10/D12 and final-integrity verification evidence

| Verification | Exact scope | Result | Log |
|---|---|---|---|
| Manager settlement red proof | first close settlement regression method | EXPECTED FAIL — 1/1 errored | `d10d12-red-close-settlement-20260820.txt` |
| Manager final rebuild | changed `peegeeq-db` reactor slice | PASS | `d10d12-final-clean-rebuild-20260820.txt` |
| Manager settlement | `PeeGeeQManagerCloseSettlementTest` | PASS — 2/2 | `d10d12-close-settlement-targeted-20260820.txt` |
| Original native observation | `PostgreSQLErrorHandlingTest` | PASS — 5/5 | `d10d12-native-error-handling-targeted-20260820.txt` |
| Close log levels | `PeeGeeQManagerCloseLogLevelTest` | PASS — 4/4 | `d10d12-close-log-level-targeted-20260820.txt` |
| Resource release | targeted `ResourceLeakDetectionTest` method | PASS — 1/1 | `d10d12-resource-leak-method-20260820.txt` |
| Outbox settlement red proof | repeated-close regression method | EXPECTED FAIL — 1/1 failed | `outbox-close-settlement-red-targeted-20260820.txt` |
| Outbox final rebuild | changed `peegeeq-outbox` reactor slice | PASS | `outbox-close-settlement-green-rebuild-20260820.txt` |
| Outbox settlement | repeated-close regression method | PASS — 1/1 | `outbox-close-settlement-green-targeted-20260820.txt` |
| Examples close regression | `ConsumerGroupResilienceTest` | PASS — 3/3 | `outbox-close-settlement-examples-regression-20260820.txt` |
| Parallel ERROR ownership | `PeeGeeQManagerCloseReactiveErrorPropagationTest` | PASS — 2/2 | `d11-parallel-expectation-final-targeted-20260820.txt` |
| Async integrity guards | two repository guard classes | PASS — 8/8 | `close-phases-static-integrity-guards-20260820.txt` |

## 2026-08-20 continuation update (P4 and P6/D11 complete)

This section supersedes the open-work and repository-state statements in every older
section below. The historical sections remain for audit context.

- Branch `master` and `origin/master` are at `a5625fa5` (`Refactor cleanup logic in
  HealthCheckManagerCoreTest and OutboxQueueBrowserIntegrationTest to aggregate failures`).
- The uncommitted implementation worktree contains the P4 Java source/test work plus the
  D11 Maven/Java/resource and module-migration changes through D11-D. No previous user change was discarded.
  The exact file list is in the companion remediation register and in `git status --short`.
- **P4/D3 is complete.** Applying the real migrations incrementally reproduced SQLSTATE
  `42703` after V001 for the retry query and after V010 for the detector query. V010 and
  V015 respectively reconcile those shapes. The green outbox test had been provisioning a
  hand-written V001-era fixture instead of completing the supported migration chain.
  `OutboxSchemaQuotingTest` now uses `PeeGeeQTestSchemaInitializer` and directly executes
  both services against reserved-word schema `table`.
- **P4/D7 is complete.** `BackgroundTaskFailureTracker` now supplies one policy to the
  depth-cache timer, retry job, and dead-consumer job: first failure WARN with the full cause,
  stack-free persistent ERROR summaries at counts 3/6/9 and so on, HEALTHY to DEGRADED to
  UNHEALTHY transitions, and reset to HEALTHY on the next success. Each task exposes a named
  health check.
- Required rebuilds and targeted verification are green: outbox fixture contract 6/6; D7
  core contracts 8/8; depth-cache integration 5/5; retry/detection lifecycle integration
  6/6. The approximately 90-minute `-Pall-tests` release gate was not run.
- **P6/D11-A is complete.** `peegeeq-test-support` now contains the immutable structured
  expectation model, repeatable method annotation, runtime registration API, concurrent
  ownership ledger, and an explicitly registered JUnit 5 extension that captures exact
  ERROR-level Logback events. The owner diagnostic records the JUnit unique ID and display
  name; root/wildcard logger expectations are rejected.
- The D11-A EngineTestKit bootstrap proves the integration changes JUnit results:
  unexpected, missing-expected, teardown, and after-all ERROR cases fail their method or
  container; exact, runtime-prefix, cause-chain, and parallel unique expectations pass. This
  selects the JUnit extension callback mechanism for D11.
- D11-A verification is green: ledger/model contracts 12/12 and bootstrap contracts 8/8,
  total 20/20. The approximately 90-minute `-Pall-tests` release gate was not run.
- **P6/D11-B is complete.** The extension is packaged through the JUnit service-provider
  descriptor and uses a reference-counted process coordinator plus one root-store lease per
  JUnit engine. Exactly one root Logback appender is shared across concurrent engine runs and
  removed by identity when the last root closes. Method and class-container ownership remain
  separate, and ambiguous owners are reported in stable sorted order.
- D11-B verification is green: ledger/model contracts 14/14, the D11-A explicit bootstrap
  8/8, and packaged integration contracts 7/7, total 29/29. The packaged proof covers service
  discovery, explicit-plus-automatic idempotence, removal, parallel classes, method isolation,
  stable diagnostics, and compatibility with a test-owned appender.
- **P6/D11-C is complete.** Thirty structured method expectations now own the observed
  green-path fault-injection ERRORs across DB, outbox, and examples. Test-authored ERROR
  narration was removed; all 20 retained narration calls are INFO. The packaged extension's
  cold-start Logback race was fixed by initializing the context during global extension class
  loading, before parallel test classes acquire the shared appender.
- D11-C targeted verification is green with auto-detection enabled: test-support 29/29,
  parallel DB 52/52 plus timer 5/5, outbox schema isolation 14/14 plus null-handler 1/1,
  examples 8/8, and the CORE narration fixture 6/6. Required clean reactor-slice rebuilds
  passed. The approximately 90-minute release gate was not run.
- **P6/D11-D is complete, and D11 is fixed.** Root Surefire configuration enables the
  packaged extension for the default CORE invocation and the integration, performance,
  smoke, and all-tests profiles. The real default-profile canary failed 1/1 and failed the
  Maven build while unclaimed, then passed 1/1 after receiving its exact structured
  expectation. Source contains 30 migrated application expectations plus 11 test-support
  infrastructure contracts, 41 annotation declarations in total.
- Default activation exposed nested EngineTestKit contamination in the previous shared
  ledger. The coordinator still installs one process-wide appender, but each JUnit engine
  now owns an isolated ledger and nested thread bindings form a stack. The final gate
  contracts pass 30/30 under the default Maven profile.
- Representative profile verification is green without an explicit auto-detection flag:
  DB integration 9/9, performance 1/1, smoke 1/1, scoped all-tests canary 1/1, outbox
  schema contracts 20/20, and the final asynchronous expected-ERROR method 1/1. The scoped
  all-tests command was not the approximately 90-minute release gate.
- D10/D12 is the next separate open investigation. Utilities UI Phase G.1b remains complete;
  return to `peegeeq-utilities-ui` after D10/D12 or when the owner changes priority.

### P4 verification evidence

| Verification | Exact scope | Result | Log |
|---|---|---|---|
| V001/V010 diagnosis | real migration scripts plus current retry query | PASS — `42703` reproduced before V010; query plans after V010 | `p4a-v001-v010-relation-diagnosis-20260820.txt` |
| V010/V015 diagnosis | real migration scripts plus current detector query | PASS — `42703` reproduced before V015; query plans after V015 | `p4a-subscription-v001-v010-v015-diagnosis-20260820.txt` |
| Outbox fixture rebuild | changed outbox reactor slice | PASS | `p4b-outbox-fixture-rebuild-20260820.txt` |
| Supported-schema contract | `OutboxSchemaQuotingTest` | PASS — 6/6 | `p4b-outbox-schema-quoting-targeted-20260820.txt` |
| D7 rebuild | changed database reactor slice | PASS | `p4-d7-background-failure-rebuild-20260820.txt` |
| D7 policy/job core | three targeted classes | PASS — 8/8 | `p4-d7-background-failure-core-targeted-20260820.txt` |
| Depth-cache health | `PeeGeeQManagerTimerGuardTest` | PASS — 5/5 | `p4-d7-depth-cache-health-integration-targeted-20260820.txt` |
| Job lifecycle | retry and detector lifecycle classes | PASS — 6/6 | `p4-d7-background-job-lifecycle-targeted-20260820.txt` |
| D11-A rebuild | `peegeeq-test-support` reactor slice | PASS | `d11a-error-log-contract-rebuild-20260820.txt` |
| D11-A CORE contracts | ledger/model plus EngineTestKit bootstrap | PASS — 20/20 | `d11a-error-log-contract-core-targeted-20260820.txt` |
| D11-B rebuild | packaged `peegeeq-test-support` reactor slice | PASS | `d11b-packaged-error-log-integration-rebuild-20260820.txt` |
| D11-B CORE contracts | ledger/model, explicit bootstrap, packaged integration | PASS — 29/29 | `d11b-packaged-error-log-integration-core-targeted-20260820.txt` |
| D11-C extension rebuild | `peegeeq-test-support` reactor slice after cold-start fix | PASS | `d11c-logback-bootstrap-race-rebuild-20260820.txt` |
| D11-C extension contracts | ledger, bootstrap, packaged integration | PASS — 29/29 | `d11c-logback-bootstrap-race-targeted-20260820.txt` |
| D11-C DB parallel contracts | timer, provider, DLQ, setup | PASS — 52/52 | `d11c-db-parallel-contracts-final-20260820.txt` |
| D11-C outbox contracts | schema isolation | PASS — 14/14 | `d11c-outbox-contract-correction-targeted-20260820.txt` |
| D11-C examples contracts | database setup service | PASS — 8/8 | `d11c-examples-migration-targeted-20260820.txt` |
| D11-C narration fixture | `VertxOnSuccessExceptionSwallowTest` | PASS — 6/6 | `d11c-marker-level-targeted-20260820.txt` |
| D11-D unclaimed canary | default CORE profile, no auto-detection flag | EXPECTED FAIL — 1/1 failed; Maven build failed | `d11d-default-gate-canary-unexpected-fails-20260820.txt` |
| D11-D claimed canary | same default CORE canary with exact expectation | PASS — 1/1 | `d11d-default-gate-canary-expected-passes-20260820.txt` |
| D11-D final rebuild | `peegeeq-test-support` reactor slice | PASS | `d11d-final-clean-rebuild-20260820.txt` |
| D11-D final gate contracts | ledger, canary, bootstrap, packaged integration | PASS — 30/30 | `d11d-final-clean-targeted-20260820.txt` |
| D11-D integration profile | `PgConnectionManagerSchemaIntegrationTest` | PASS — 9/9 | `d11d-integration-profile-schema-targeted-20260820.txt` |
| D11-D performance profile | matrix-generation method | PASS — 1/1 | `d11d-performance-profile-targeted-20260820.txt` |
| D11-D smoke profile | `MessageTest` | PASS — 1/1 | `d11d-smoke-profile-targeted-20260820.txt` |
| D11-D all-tests profile | scoped gate canary only | PASS — 1/1 | `d11d-all-tests-profile-canary-targeted-20260820.txt` |
| D11-D outbox contracts | schema isolation and quoting | PASS — 20/20 | `d11d-outbox-structured-expectations-targeted-20260820.txt` |
| D11-D final async ERROR | missing-schema expected-ERROR method | PASS — 1/1 | `d11d-outbox-expected-error-final-20260820.txt` |

## 2026-08-19 continuation update (cleanup review completed)

This section supersedes the repository state and open-work statements in the historical
2026-08-12 snapshot below. The older narrative remains for audit context.

- Branch `master` and `origin/master` are at `5f2da208` (`test: propagate setup and teardown
  failures`). That pushed commit contains both Java lifecycle work and utilities UI G.1b; its
  subject does not describe the UI portion. Do not amend or rewrite it. Use a corrective,
  accurately scoped commit for the working-tree remediation below.
- The current working tree contains a completed cleanup-review remediation across seven Java
  source/test files plus this handover. The utilities UI G.1b files are no longer uncommitted;
  they are part of `5f2da208`.
- `PeeGeeQTestSchemaInitializer.cleanupTestData` no longer logs and swallows connection or SQL
  failures. It logs the full cause and throws a contextual `RuntimeException`; a live
  Testcontainers authentication-failure test proves the exception and cause reach the caller.
- `OutboxQueueBrowserIntegrationTest`, `HealthCheckManagerCoreTest`, and
  `OutboxSchemaIsolationCoverageTest` now attempt every owned close even after an earlier close
  fails. The first failure remains primary and every later failure is retained as a suppressed
  cause. Contract tests cover the multi-failure behavior. The browser's local integer producer
  and browser cleanup was fixed at the same time; it previously used an unobserved bare
  `onComplete` callback.
- The schema-parameter test now uses `PostgreSQLTestConstants.createStandardContainer()`, a
  per-test non-shared Vert.x PgClient pool, reactive queries, and transactions. The hand-built
  container, empty lifecycle methods, and raw JDBC verification were removed. A new workspace
  guard prevents schema-initializer tests from reintroducing raw JDBC verification or directly
  constructed PostgreSQL containers.
- The schema-isolation polling helper's pre-existing `Supplier` accessor rule violation was
  replaced with a purpose-built asynchronous condition interface.
- D1, D2, D4/D4-A, D5, D6, D8, D9, and D13 are closed. The tracked Java-source scan for
  pass-on-failure expression handlers returns zero. The six disabled native subscription
  tests are restored; the repository guard permits only the deliberate antipattern fixture.
- Final cleanup-review verification is green: four behavior classes passed 56/56 in total
  (`OutboxQueueBrowserIntegrationTest` 14/14, `HealthCheckManagerCoreTest` 17/17,
  `OutboxSchemaIsolationCoverageTest` 14/14, and the two schema-initializer classes 11/11).
  The infrastructure guard and two async guards passed 9/9. Every Java phase had its required
  clean reactor-slice rebuild before targeted testing.
- Utilities UI G.1b verification is also green against the final worktree: production build
  (`tsc` + Vite, 3,209 modules), focused unit tests 52/52 across five files, real-backend ramp
  Playwright 3/3, and targeted ESLint with 0 errors and 0 warnings. The approximately
  90-minute `-Pall-tests` gate was not run.
- Remaining original gaps are P4/D3+D7 (reproduce the background-job DDL failure, then add
  bounded escalation and health), P6/D11 (unexpected ERROR-log enforcement), and the
  separate D10/D12 close-settlement investigation. These should not be conflated.
- Utilities UI Phase G is complete: G.1b consumed the T.4/T.5/T.7 telemetry and shipped in
  the worktree on 2026-08-19. T.6 remains an optional per-run precision/scoping decision,
  not an unfinished G.1b requirement. The next substantive choice is whether to take T.6 or
  return to P4/P6 and the separate D10/D12 close-settlement investigation.

Earlier Java commands and evidence are recorded in `TEST-INTEGRITY-DEFECT-REMEDIATION-PLAN.md`;
the cleanup-review evidence is recorded below.
The G.1b implementation and verification record is in
`peegeeq-utilities-ui/docs/PEEGEEQ_DEVOPS_UTILITIES_IMPLEMENTATION_PLAN.md`. Do not use the
old resume commands near the end of this file as the current plan.

### Cleanup-review verification evidence

| Verification | Exact scope | Result | Log |
|---|---|---|---|
| Schema failure contract | `PeeGeeQTestSchemaInitializerFailureTest` plus migrated schema parameter class | PASS — 11/11 | `final-review-schema-targeted-20260819.txt` |
| Browser lifecycle | `OutboxQueueBrowserIntegrationTest` | PASS — 14/14 | `outbox-browser-cleanup-targeted-20260819.txt` |
| Health lifecycle | `HealthCheckManagerCoreTest` | PASS — 17/17 | `final-review-health-targeted-20260819.txt` |
| Schema-isolation lifecycle | `OutboxSchemaIsolationCoverageTest` | PASS — 14/14 | `final-review-outbox-schema-targeted-20260819.txt` |
| Static guards | schema-initializer infrastructure plus both async guards | PASS — 9/9 | `final-review-static-guards-20260819.txt` |

The schema failure-contract log contains one intentional ERROR with a full PostgreSQL
authentication stack trace; the test asserts that exact failure path is propagated. The
schema-isolation log contains its existing labelled negative-path ERRORs, and the browser
aggregation contract deliberately logs and asserts its two synthetic close failures. Every
ERROR was traced to an asserted negative-path contract; no unhandled exception was found. The
approximately 90-minute `-Pall-tests` release gate was not run.

### Utilities UI Phase G.1b completion

Ramp mode now collects saturation evidence over the same phase boundaries used by its load
sequence:

- A T.7 database snapshot is captured before load, and another when the ramp completes,
  stops, or halts at its knee.
- The T.5 per-queue stats stream and T.4 system metrics stream are sampled during the run and
  tagged with the active ramp phase.
- The report summarizes per-step queue depth and backend rate, event-loop lag, pool
  acquire-wait, database active/pending sessions, implementation-table churn, and cluster
  deltas. Missing telemetry remains absent rather than being presented as zero.
- Findings name observed pressure signals without claiming they caused the knee. Queue and
  lifetime database statistics remain explicitly non-run-scoped without T.6; a dedicated
  queue is recommended when isolation matters.
- Start/finish failures surface in the UI, every stream is closed, and unmount cleanup aborts
  an in-flight baseline or active telemetry session.

The targeted browser run exposed and pinned one real lifecycle defect before completion.
React StrictMode's development setup/cleanup/setup rehearsal cleared the page's mounted guard,
so an asynchronous baseline could finish with controls locked and no ramp started. The effect
now re-arms that guard during setup. The final real-backend test verifies the completed
attribution path.

Verification logs are under `logs/`:

| Verification | Exact scope | Result | Log |
|---|---|---|---|
| Rebuild | `npm run build` | PASS — `tsc`, Vite 3,209 modules | `utilities-ui-g1b-build-20260819.txt` |
| Unit | G.1b collector, attribution, panel, shared mapper and generator page | PASS — 52/52 across 5 files | `utilities-ui-g1b-unit-20260819.txt` |
| Browser | `ramp.spec.ts`, project `11-ramp`, real REST backend + TestContainers PostgreSQL | PASS — 3/3 | `utilities-ui-g1b-ramp-e2e-20260819.txt` |
| Lint | 11 changed TypeScript/TSX source/test files | PASS — 0 errors, 0 warnings | `utilities-ui-g1b-eslint-20260819.txt` |

The unit count is `rampTelemetryService.test.ts` 3/3, `rampAttribution.test.ts` 4/4,
`RampAttributionPanel.test.tsx` 3/3, `telemetryService.test.ts` 15/15, and
`MessageGeneratorPage.test.tsx` 27/27.

No Java or Maven file changed as part of G.1b, so its rebuild was the UI production build.
The full 90-minute suite remains an owner-run release gate. The Vite large-chunk advisory and
the Playwright launcher’s Node child-process deprecation warning are non-failing diagnostics,
not test failures.

---

### Repository snapshot

Verified after Phase 3 remediation on 2026-08-12:

* Branch `master` is at commit `7ee3148dc26cff25a58d33c222163352460ad7a2`
  (`fix(tests,db,outbox): make failing tests fail — 53 swallowed failures, 8 skipped
  tests, 2 production defects`). The implementation and supporting documentation
  described below are committed there; the commit changes 82 files.
* The working tree contains four modified tracked files — `AGENTS.md`, `CLAUDE.md`,
  `TEST-INTEGRITY-DEFECT-REMEDIATION-PLAN.md`, and `PEEGEEQ-TEST-COMMANDS.md` — plus
  one untracked file: this handover document. These are the Phase 1–3 documentation
  remediations and are not committed.
* Commit `7ee3148d` also contains 20 modified and 4 added PNGs under
  `docs-design/peegeeq-management-ui/screenshots/`. The session attributed these files
  to a `mvn clean install` rebuilding the UI modules; that attribution has not been
  independently reproduced.

**Owner decision required for the 24 PNGs:** either retain them and record their inclusion
as intentional, or restore the 20 modified files and remove the 4 added files in a new,
path-scoped corrective commit. Do not rewrite the existing commit history. Until that
decision is made, do not alter the screenshots as part of the defect remediation work.

---

## 1. What started this

Reading `logs/peegeeq-outbox-integration-20260811.txt`, which reported:

```
Tests run: 537, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

while logging **46 ERROR-level exceptions**.

Everything in this session came from pulling that thread. None of it is new breakage —
the swallowing handlers date from 2026-03-20, the schema-quoting gap and the divergent
DDL are older. What changed is that we read logs instead of exit codes.

---

## 2. The single root cause

Three forms of one defect: something fails, and nothing that reports test results ever
learns about it.

| Form | Effect |
|---|---|
| `.onFailure(err -> { log; completeNow(); })` | Test passes whether the operation worked or not |
| Gated test that never activates | `Tests run: N, Skipped: N` printed as `BUILD SUCCESS` |
| Production failure handled by `logger.error` only | No test, health signal or caller sees it |

**D11 is the systemic fix** — make unasserted ERROR logs fail the build. Everything else
in the register is remediation of instances.

---

## 3. What was fixed and verified

### Production code (4 files)

**`PostgreSqlIdentifierValidator`** — added `quote(String)` and `qualify(String,String)`.
The class already owned identifier validation and had no quoting operation; the quoter
existed twice in `peegeeq-outbox` (`OutboxFactory`, `OutboxConsumer`), in a module
`peegeeq-db` cannot depend on. Adding a third copy would have violated "never keep both".

**`HealthCheckManager`** — `qualifiedTable()` now quotes the schema. Its `SQL_IDENTIFIER`
guard (`^[A-Za-z_][A-Za-z0-9_]*$`) blocks injection but matches every reserved word, so a
schema named `select` passed validation and produced `FROM select.outbox` — error 42601,
and every queue health check reported a permanently failing queue. Observed live in
`OutboxSchemaQuotingTest`, which exists to fix this exact defect (it calls it M1) but
asserts only on three outbox query methods.

**`PeeGeeQDatabaseSetupService`** — two `DROP TABLE IF EXISTS` statements built the same
way. **No test covers this change.**

**`OutboxProducer`** — guard added: `TransactionPropagation` off a Vert.x context now
returns a failed Future naming the requirement, instead of letting `Pool.withTransaction`
dereference the null context and surface a Vert.x-internal NPE.

### Test code

- **53 swallowed-failure handlers** converted to `failNow`, across 6 modules
  (native 18, outbox 13, examples 11, bitemporal 5, db 5, service-manager 1).
- **3 propagation tests** rewritten to run inside `vertx.getOrCreateContext().runOnContext`
  and assert success, plus a new test pinning the off-context contract.
- **2 tests** that accepted any failure now assert the contract read from source:
  `IllegalArgumentException("Message payload cannot be null")` and
  `IllegalStateException("Producer is closed")`.
- **3 performance classes** — removed a dead `@EnabledIfSystemProperty` gate.
- **`OutboxPerformanceTest`** — `failIfContextFailed` helper at three `awaitCompletion`
  sites, so a failed send surfaces its cause instead of an NPE on derived state.

### Verification runs (this session)

| Suite | Result |
|---|---|
| peegeeq-native integration | 189/189 green |
| peegeeq-bitemporal integration | 352/352 green |
| peegeeq-db (3 changed classes) | 45/45 green |
| `PostgreSqlIdentifierValidatorTest` | 20/20 green |
| `OutboxProducerTransactionTest` | 20/20 green |
| peegeeq-outbox (changed classes) | 66 tests green |
| performance, all 3 classes | 8 tests now execute (were 0) |

---

## 4. What is NOT verified

* **`PeeGeeQDatabaseSetupService`** — production change with no test behind it.
* **peegeeq-examples** (11 sites changed) — not re-run.
* **peegeeq-service-manager** (1 site changed) — not re-run.
* **peegeeq-db and peegeeq-outbox full integration** — not run since the production
  changes to `HealthCheckManager`, `PeeGeeQDatabaseSetupService` and `OutboxProducer`.
* **`-Pall-tests`** — not run.

---

## 5. Open work, ranked

### 5.1 — 24 remaining swallow sites

Found late in the session in a lambda form never scanned for:

```java
.onFailure(err -> testContext.completeNow())     // expression lambda, no braces
```

| Kind | Count | Notes |
|---|---|---|
| Teardown / setup | 10 | Same mechanical edit as the 53 |
| Test body | 10 | 8 of them in `PgBiTemporalEventStoreComplexTest` |
| Needs rewrite (D4-A) | 4 | 3 in peegeeq-rest, 1 in peegeeq-db |

The 10 test-body sites need reading, not converting. Two carry comments admitting what
they do: `// Either null or exception acceptable` and `// Expected in some implementations`.
A test where either outcome is acceptable asserts nothing about the event store.

**D4-A, the four that need rewriting:**

| Site | Why it always passes |
|---|---|
| `RealTimeStreamingIntegrationTest:210` | Three pass paths: swallowed connection failure; a 5 s `setTimer → completeNow` that fires *because* no message arrived; an assertion guarded by `if ("welcome".equals(type))` |
| `WebSocketHandlerTest:395` | `assertTrue(status >= 200 \|\| status >= 400)` — every status ≥ 200 satisfies clause one; clause two is unreachable |
| `CrossLayerPropagationIntegrationTest:383` | DB verification declared optional by comment |
| `HealthCheckManagerTest:468` | Swallows a close it calls "expected" without asserting it |

Expect red once honest. If the WebSocket layer is incomplete, that is what the tests will
say — which is the point, but it needs its own budget.

### 5.2 — `closeReactive()` returns a Future that never settles (D10/D12)

**The most serious open item. Production code.**

Third observation. `PostgreSQLErrorHandlingTest` teardown exceeded a **60 second** budget
while the log showed the close completing in 36 ms:

```
PeeGeeQManager.closeReactive() cleanup completed
Closing Vert.x instance (manager-owned)
Vert.x instance closed successfully
```

Neither `onSuccess` nor `onFailure` ran. This is not a swallowed failure — it is silence.

The budget had already been raised 30 s → 60 s on 2026-08-09, with the in-code comment
admitting "the slow-close mechanism under load is NOT diagnosed". It then failed at 60 s.
Raising it again is the threshold-masking anti-fix the standards doc bans by name.

**Untested hypothesis:** `closeReactive()` closes the manager-owned Vert.x as its final
step. If the continuation that completes the caller's Future is scheduled on that same
event loop, closing the loop first would drop the callback permanently. This fits every
observation — clean logs, passes in isolation, fails under load, elapsed time exactly the
budget. It also matches commit `e79030da`'s own note that "any caller chaining on
closeReactive() can wait forever."

It did **not** reproduce in two subsequent full-native runs, so it is load-dependent.

### 5.3 — `outbox_consumer_groups` has two incompatible definitions (D3)

Error code is `42703` (undefined_column), not `42P01` — the tables exist, the columns
differ.

| | Template `08c-consumer-table-groups.sql` | Migration `V001__Create_Base_Tables.sql` |
|---|---|---|
| message ref | `message_id` | `outbox_message_id` |
| group | `group_name` | `consumer_group_name` |
| timing | `claimed_at`, `completed_at` | `processing_started_at`, `processed_at` |
| locking | `lock_id`, `lock_until` | absent |

`V010` reconciles them with conditional `RENAME`/`ADD`/`DROP`. `ConsumerGroupRetryService`
queries the template shape, so any database between V001 and V010 fails every job tick.

**Status: NOT REPRODUCED.** Step one is reproduction — capture which relation the query
resolved to and what its columns are — not a fix.

### 5.4 — Remaining

* **D7** — background timer failures log unbounded ERROR with no escalation or health
  signal. One shared mechanism for `ConsumerGroupRetryJob`, `DeadConsumerDetectionJob`
  and the depth-cache timer.
* **D13** — 6 `@Disabled` tests in `ConsumerGroupSubscriptionTest`, one parked on
  "Native queue `setMessageHandler()` has a race condition - thread safety needs
  implementation fix". A known production defect behind a disabled test.
* **P3 remainder** — one test: `HealthCheckManager` against a schema named `select`.
  D1's fix is proven at contract level, not end to end.
* **D11** — the systemic fix. Needs its own data-model pass first: what counts as an
  expectation, where it is declared, how a fault-injection test registers one.

---

## 6. Awaiting the owner's decision

**D6 — `OutboxPerformanceTest.testThroughputPerformance`.** It fires 1000 concurrent sends
at a pool with a 128 wait queue and now fails with `ConnectionPoolTooBusyException`. Size
the pool, or bound in-flight sends? Those measure different things. **Not a production
defect** — the pool rejected work it was configured to reject.

---

## 7. Traps found the hard way

**Stale `.m2` after changing an upstream module.** A `-pl` scoped run resolves dependencies
from the local repository, not the reactor. Changing `peegeeq-api` or `peegeeq-db` and then
running a downstream module's tests produces `NoSuchMethodError` at runtime. Fix:

```powershell
mvn clean install "-DskipTests" "-pl" ":<changed-module>" "-am" 2>&1 |
    Tee-Object -FilePath logs\rebuild-<module>-<date>.txt
```

This rebuilds and installs the changed module plus its upstream reactor dependencies.
Use `-DskipTests` for this prerequisite only (it compiles tests but skips execution), then
run the targeted tests. Never use `-Dmaven.test.skip=true`.

**`-Dtest=` with the wrong profile reports `Tests run: 0` and `BUILD SUCCESS`.** Match the
profile to the class's tag. `PostgreSqlIdentifierValidatorTest` is CORE — adding
`-Pintegration-tests` runs nothing.

**Maven properties are not system properties.** `-Pperformance-tests` sets
`peegeeq.performance.tests` as a Maven property, but surefire is declared version-only in
`<pluginManagement>` with no `<systemPropertyVariables>`, so it never reaches the test JVM.
Any `@EnabledIfSystemProperty` gate on it can never be satisfied. Three classes had never
executed. Fixed by deleting the redundant gate — the `@Tag` already selects them.

**The standards doc contradicted itself.** Two passages required
`testContext.failNow(err); // do NOT swallow to completeNow()`; three blocks headed
"Correct Pattern" showed the opposite. Corrected, but check for the same pattern elsewhere
before trusting a doc example.

---

## 8. Reliability warning on the counts

**Treat every site count in this session as an estimate.**

The detector was wrong four times, and each correction came from stumbling into a new form
while editing rather than from testing the detector:

1. **75/52** — flat regex, 300-char window ran past the handler's closing brace and caught
   `completeNow()` in the following `else`.
2. **66/47** — body-scoped, but counted asserted negative tests as violations.
3. **53/38** — excluded handlers containing `assert`, but missed the capture idiom where
   the check lives outside the handler.
4. **35 → 53 fixed, 24 open** — blind to the single-line block form, then to the
   expression lambda form with no braces at all.

**Before quoting another count:** open one representative file, enumerate every
`.onFailure(` by hand, confirm the scanner's output matches, and state which file was used
to validate. `PgBiTemporalEventStoreComplexTest` is the right file — it has eight sites in
the form that was missed longest.

The current scanner is at `/tmp/scan5.awk` (not persisted). It covers three forms:
expression lambda, single-line block, multi-line block; and excludes handlers containing
`assert`, `.set(` (throwable capture) or `failNow`. It has **not** been hand-validated.

---

## 9. Resuming

```powershell
# 1. Rebuild and install the changed reactor slices before downstream tests
mvn clean install "-DskipTests" "-pl" ":peegeeq-db,:peegeeq-outbox" "-am" 2>&1 |
    Tee-Object -FilePath logs\rebuild-db-outbox-20260812.txt

# 2. Close the unverified gap
mvn test "-Pintegration-tests" "-pl" ":peegeeq-examples" 2>&1 | Tee-Object -FilePath logs\examples-20260812.txt
mvn test "-Pintegration-tests" "-pl" ":peegeeq-service-manager" 2>&1 | Tee-Object -FilePath logs\svcmgr-20260812.txt

# 3. Full regression on the modules with production changes
mvn test "-Pintegration-tests" "-pl" ":peegeeq-db" 2>&1 | Tee-Object -FilePath logs\db-20260812.txt
mvn test "-Pintegration-tests" "-pl" ":peegeeq-outbox" 2>&1 | Tee-Object -FilePath logs\outbox-20260812.txt
```

Read the per-class `Tests run:` lines in each log. `BUILD SUCCESS` alone means nothing —
that is what started this session.

---

## 10. Working agreement notes

* After every Java or Maven implementation change, the agent rebuilds and installs the
  affected reactor slice with `clean install -DskipTests -pl :<changed-module> -am`, then
  runs the smallest relevant test scope (`-Dtest=` method/class or a single module) with
  the correct profile. Both commands are piped through `Tee-Object`; the agent reads the
  saved logs and reports the exact scope and per-class counts. The approximately 90-minute
  `-Pall-tests` run is owner-run or executed only when explicitly requested as a release
  gate. `AGENTS.md`, `CLAUDE.md`, and `PEEGEEQ-TEST-COMMANDS.md` now state the same rule.
* `CLAUDE.md` Step 5 gained a pass-on-failure entry. Its previous closest rule required a
  Future be "observed via `.onFailure(...)`", which a swallowing handler satisfies — the
  list checked that a handler exists, never what it does.
* Status lives in the register, not in chat. Anything diagnosed from artifacts alone is
  marked `NOT REPRODUCED` rather than described as understood.
