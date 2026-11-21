# PeeGeeQ Task Register

**Status:** ACTIVE. This is the only live task register under `docs-design`.
**Last reconciled:** 2026-11-21, against `0ffaafb6` plus the uncommitted working tree (PP1 of Task 8). Every
status below was checked against the code or a saved log on that day; the method is in "How this register was
checked" at the end.

**What this file is.** One row per task: its status, where its detail lives, and its next step. It holds no run
records. A task's plan holds its design, its decisions, and its dated run records. The full text of this register
as it stood before 2026-11-21, with every earlier run record and test count, is in
[archive/TASKS_REGISTER_TO_2026-11-21.md](archive/TASKS_REGISTER_TO_2026-11-21.md).

New work is added here before implementation begins. When a task changes status, update its row in the same
change.

## Working rules

- One numbered phase at a time; rebuild, verify, and report it before the next one starts.
- Strict TDD for behaviour changes: a failing test for the behaviour and for each failure mode of each dependency,
  then the implementation, then the passing test.
- Before Java or Maven edits, follow the mandatory pre-work in `.claude/CLAUDE.md`. The root `AGENTS.md` carries
  the same text for tools that read that filename.
- Rebuild the affected reactor slice before targeted testing; run the smallest tagged scope that covers the change;
  report per-class `Tests run:` counts. Commands: `docs-design/testing/PEEGEEQ-TEST-COMMANDS.md`.
- The whole-repository `-Pall-tests` gate is an owner-run release check, not part of the edit-test loop. On
  Jenkins, a build from `peegeeq-pg-failover` alone needed 2 h 21 min for its Java stage (build #41), so a build
  from `beginning` cannot finish inside the pipeline's 150-minute limit (`Jenkinsfile` line 48).
- No Mockito or other mocking; no blocking Future bridges, fixed delays, error swallowing, or unobserved Futures.
- Code comments, error messages, and documents state purposes and reasons in plain words. A reference to a plan
  section or decision number may follow a reason; it never replaces one.

## Owner direction

2026-11-17: Task 8 is completed before work on any other task starts, Task 9 included.

## Status

| Task | Status | Detail lives in | Next step |
|---|---|---|---|
| 1. Configuration property and runtime reconciliation | COMPLETE 2026-08-29 | Archived register, Task 1 | None |
| 2. Remove the last blocking calls in tests (Tier 5) | COMPLETE 2026-09-02 | Archived register, Task 2 | None |
| 3. PostgreSQL and HAProxy resilience gaps | COMPLETE 2026-09-02 | Archived register, Task 3 | None |
| 4. Durable subscriptions runtime (bitemporal) | COMPLETE 2026-09-05 for the bitemporal store | `docs-design/event-sourcing-messaging/PEEGEEQ_DURABLE_SUBSCRIPTIONS_OPTION_PLAN.md` | Outbox parity and REST endpoints are not built and not scheduled (backlog) |
| 5. Transactional REST API | REJECTED 2026-09-15 | `docs-design/_archived/superseded-designs/` | None |
| 6. Partitioned consumption pre-GA gates | COMPLETE, Jenkins build #11, 2026-09-17 | `docs-design/consumer-groups/PEEGEEQ_PARTITIONED_CONSUMPTION_DESIGN.md` | Open defects of this engine are Task 9 work (finding 4, P-1) |
| 7. Jenkins UI test-result publishing | COMPLETE, Jenkins build #48, 2026-09-05 | Archived register, Task 7 | None |
| 8. Connection management and PostgreSQL failover | ACTIVE | `docs-design/failover and resilience/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_IMPLEMENTATION_PLAN.md` | PP2 (below) |
| 9. Delivery-outcome redesign (finding 20) | ACTIVE, waits for Task 8 | `docs-design/consumer-groups/PEEGEEQ_DELIVERY_OUTCOME_REDESIGN_PROPOSAL.md` and its appendix | Owner review of increment 1b corrections (below) |
| 10. Reduce `docs/` from 24 files to 11 and correct it against the code | PROPOSED 2026-11-21, waits for Task 8 unless the owner releases it | `docs-design/tasks/PEEGEEQ_DOCUMENTATION_REDUCTION_PLAN.md` | Owner rulings OD-1 to OD-5 (plan section 9) |

## Task 8. Connection management and PostgreSQL failover

**Purpose.** A PeeGeeQ application keeps working when the PostgreSQL writer fails: a standby is promoted under a
coordinator lease (Consul first), HAProxy routes clients to the new writer, and the client tier recovers its
connections and listeners. The supervisor in `peegeeq-pg-failover` duplicates Patroni's operations, as the owner
directed on 2026-11-13.

**Done, verified 2026-11-21:**

- Phases 1 to 4, 6, 7a, and 7b.1 (commit-outcome handling in `peegeeq-db`).
- Phase 7b.2: lease port and Consul adapter, local state store, process control, admission gate, renewal loop,
  watchdog modes, bootstrap scopes A and B. Left: the real-device watchdog run.
- Phase 7b.3 components: promotion with checkpoint, follow, policy cutover, member slots and positions (P1 to P7),
  takeover after lease expiry, planned stop with release, member tags, automatic rewind and rebuild, restart and
  reload with `pending_restart`, cluster settings in the coordinator (CC1 to CC3), and PP1 (`PgClusterParameters`,
  uncommitted on 2026-11-21).
- Jenkins builds #41 and #42 on `0ffaafb6` (2026-11-20 and 2026-11-21) together passed every module from
  `peegeeq-pg-failover` on; `peegeeq-pg-failover` ran 863 tests with 0 failures. Build #41 was stopped by the
  150-minute limit in its UI stage; build #42 ran that stage.

**Not built: nothing runs failover in production.** No module depends on `peegeeq-pg-failover`, the module has no
`main`, and only tests construct the monitor, bootstrap, renewal loop, and takeover (checked 2026-11-21). No loop on
a standby watches the lease and starts a takeover.

**Pending, in order:**

1. PP2: the parameters operations on the cluster configuration port, their contract cases, and the Consul binding
   (plan §9.35, contract 2).
2. PP3 managed file, PP4 `applyParameters` (decides review finding 1), PP5 start in recovery (plan §9.35).
3. Owner decision: whether the cluster-wide parameters may contain settings that PostgreSQL runs as a shell command
   or loads as code (`archive_command`, `restore_command`, `shared_preload_libraries`, and others). Until it is
   decided, the write token for the parameters key carries the same trust as a shell on every node (plan §9.35,
   "Danger found in PP1").
4. Status and metrics endpoints (owner answer 13), then callbacks (owner answer 14).
5. The rest of 7b.3: the supervisor loop that reads the cluster settings before an acquisition and runs
   `runScheduledRestart`; the production entry point; the re-join policy cutover; manual takeover through an
   operator entry point; repeated failover; controlled re-join; production-manager assertions routed through
   HAProxy.
6. 7b.2: the real-device watchdog run on a Linux host.
7. Phase 5: a Jenkins build from `beginning` that passes `peegeeq-db` with the repair `f391a7f0`. Blocked by the
   150-minute pipeline limit; raising `timeout(time: 150, unit: 'MINUTES')` in `Jenkinsfile` needs an owner
   commit.
8. Phases 8 to 15 (plan §8): autonomous initiation, operation deadlines, LISTEN and writer-status recovery in the
   client tier, shared pooled access in every module, readiness, frozen-node qualification, the three-node target
   stacks with scenarios S1 to S59, and release evidence G-1 to G-7.

**Open questions carried from earlier records:** owner review of OD-1 to OD-3 (decided by the implementer);
removing a departed member's record (phase 14); TLS and restore; exclusion of the old writer while its supervisor or its VM is paused;
the cause of an HTTP 403 seen on 2026-10-08; recovery timing.

## Task 9. Delivery-outcome redesign (finding 20)

**Purpose.** Every delivery to a consumer group ends in one recorded outcome (completed, rejected, or unavailable),
across outbox, native, and database consumers, so that no message is deleted, completed unread, blocked without
bound, or withheld without a log line.

**Done, verified 2026-11-21:** increment 1a (`STOPPED` status, `stopGroup`; commit `092cf23f`); increment 1b built
but not approved (lifecycle transitions, settings update, `SubscriptionStore`; `092cf23f`); the native-queue part of
phase 6 (`DeliveryOutcome`, `queue_message_group_exclusions`, claim delay through `visible_at`; commit `09501400`,
2026-11-08).

**Pending, in order:** owner review of correction 1 (subscription store); corrections 2, 3, 4, 6, 7, 8, and 9 of
increment 1b (correction 5 is done in code: `TestCleanup` and its guard exist); increments 1c, 1d (delete
`DeadConsumerGroupCleanup`, defect D1 below), and 1e; then phases 2 to 8 of the proposal. Two rulings are open:
re-creating a queue (its trigger has no `CREATE OR REPLACE`), and what happens to old `setupId-queueName`
subscription rows.

## Defects found by the document review of 2026-11-21

Found while checking the documents against the code. Each is unscheduled until the owner places it. "Checked"
means the code was read on 2026-11-21 and confirms the statement; nothing was run.

| # | Defect | Evidence | Belongs to |
|---|---|---|---|
| D1 | `DeadConsumerGroupCleanup` marks `PENDING` and `PROCESSING` outbox messages `COMPLETED` once the required group count falls to the completed count, so removing dead groups can complete a message that nobody read | Checked: SQL in `peegeeq-db/.../cleanup/DeadConsumerGroupCleanup.java` (`autoCompleteMessages`); runtime effect not reproduced | Task 9, increment 1d |
| D2 | The LISTEN reconnect gives up after 5 attempts; the system design requires reconnect without an attempt limit | Checked: `MAX_RECONNECT_ATTEMPTS = 5` in `ReactiveNotificationHandler.java:87` (bitemporal) and `PgNativeQueueObserver.java:65` (native) | Task 8, phase 10 |
| D3 | A `NOT_EQUALS` server-side filter includes messages that lack the header; the filtering guide says they are excluded | Checked: `ServerSideFilter.java:191`. Decide which is intended, then fix the other | Unscheduled |
| D4 | A bitemporal store with both an all-events and an exact-type subscription may deliver each event twice; every notified event is fetched before it is matched | Read by a review agent, not confirmed; needs a probe | Unscheduled |
| D5 | A failed consumer-group cancel answers HTTP 204; 409 and 503 are chosen by matching the text "invalid" | Read by a review agent (`ConsumerGroupHandler.java:811-819`, `ManagementApiHandler.java`) | Task 9, correction 7 |
| D6 | The pool circuit breaker counts business SQL errors; the system design says only availability failures count | Read by a review agent (`CircuitBreakerManager.java:64`) | Task 8, phase 11 |
| D7 | `peegeeq_setup_metadata` and `peegeeq_object_registry` are created by the SQL templates only; no Flyway migration creates them | Read by a review agent; intent not determined | Task 9, phase 2 |
| D8 | `StartRestServer` and the sidecar's `PgPrimaryCheckMain` read environment variables or system properties, against the rule that configuration is explicit and instance-scoped | Read by a review agent | Unscheduled |
| D9 | Finding 17: test log configurations with detached or `OFF` loggers hide production ERRORs from the unexpected-ERROR gate | Checked: `logback-test.xml` of bitemporal, db, examples, outbox, rest; fixed only in native and service-manager | Unscheduled |
| D10 | A bitemporal subscription by aggregate ID with no event type (`subscribe(null, aggregateId, handler)`) is never delivered: dispatch looks up only the key with both parts null | Read by a review agent in `ReactiveNotificationHandler`; needs a probe | Unscheduled |
| D11 | The notify trigger lower-cases the channel prefix and the Java listener does not, so a schema or table name with capital letters may never be notified | Read by a review agent (event-store `03-function.sql` and `ReactiveNotificationHandler`); needs a probe | Unscheduled |
| D12 | `appendBuilder().correction(...).inTransaction(conn)` ignores the connection, and a correction drops the causation ID | Read by a review agent in `PgBiTemporalEventStore`; needs a test | Unscheduled |
| D13 | Smaller code faults found while correcting documents: the `EventStore.appendBuilder()` Javadoc example uses `.get()`; the `PgPrimaryCheckVerticle` Javadoc shows an HAProxy `backup` server, which the design forbids; two sidecar tests use the old generic `org.testcontainers.containers.PostgreSQLContainer<?>`; `peegeeq-api.yaml` does not describe the `Idempotency-Key` header; several Java comments name documents by paths that were archived before 2026-11-21 (for example `docs-design/tasks/SCHEMA-PROCESSING-GAPS-CRITICAL-17-Jun-2026.md`) | Read during the review | Unscheduled |
| D14 | `peegeeq-migrations` tests use AssertJ (92 `assertThat` lines in 5 classes); the coding principles make JUnit Jupiter assertions the standard and say AssertJ is not a dependency. AssertJ 3.24.2 is pinned in that module's pom only | Checked 2026-11-21: imports in `CustomSchemaIntegrationTest`, `MigrationConventionsTest`, `MigrationIntegrationTest`, `SchemaContractTest`, `SchemaValidationTest` | Unscheduled |
| D15 | `peegeeq-examples` tests: discarded `ConsumerGroup.start()` futures (`AdvancedProducerConsumerGroupTest` 3 sites, `ConsumerGroupResilienceTest` 3 sites); `try`/`catch` around the `Future`-returning `QueueFactory.close()` (`ServerSideFilteringTest`, `PartitionedOrderingDemoTest`); `ConsumerGroupResilienceTest` teardown completes when the manager close fails; `EventSourcingCQRSDemoTest` logs and swallows command failures; `configureOffsetWatermarkTopic` writes through `withConnection` (`EventSourcingCQRSDemoTest`, `PartitionedOrderingDemoTest`) | Read by a review agent on 2026-11-21 while removing the dead Spring hooks; not reproduced | Unscheduled |
| D16 | `peegeeq-migrations/dependency-reduced-pom.xml` is maven-shade output tracked in git. Every build rewrites it, and the tracked copy is stale (it still excludes `oshi-core` and `h2` and lists the removed Flyway 10.10.0, JUnit 5.10.1 and PostgreSQL 42.7.2 overrides) | Checked 2026-11-21: `git diff` after `mvn clean install` | Unscheduled: decide whether to generate it under `target/` and untrack it |

## Unscheduled backlog

Each item needs an owner decision before it becomes work. The dated detail of each is in the archived register.

| Item | State | Decision needed |
|---|---|---|
| Schema registry | Proposed, not built; `SchemaRegistry.tsx` is an unrouted placeholder | Approve product scope (`docs-design/analysis/PEEGEEQ_SCHEMA_REGISTRY_DESIGN.md`) |
| Unrouted Management UI placeholder pages | `DeveloperPortal`, `QueueDesigner`, `Monitoring`, `SchemaRegistry` show "coming soon"; `App.tsx` routes none; `Header.tsx` keeps their titles | Per page: approve scope, or delete the page and its title |
| Benchmarking enhancement | Proposed, not built; 103 unchecked `BENCH-*` items | Approve scope (`docs-design/performance/PEEGEEQ_BENCHMARKING_ENHANCEMENT_IMPLEMENTATION_PLAN.md`) |
| Authentication and authorization | Not built: no auth module, routes, or tables | Define the threat model and product boundary (`docs-design/analysis/PEEGEEQ_AUTHENTICATION_AND_AUTHORISATION_DESIGN_DECISIONS_SUMMARY.md`) |
| Durable subscriptions for the outbox, and their REST endpoints | Not built (Task 4 covered the bitemporal store) | Approve scope |
| Server-side filter for consumer groups and REST | Not built; the filter exists for single native and outbox consumers only | Approve scope |
| TypeScript REST client | Used by two Management UI pages; no test runs it against the real backend | Add tests against the real backend |
| Failures logged at WARN | 311 `warn` calls outside `peegeeq-pg-failover` (count of 2026-11-15); the 2026-10-09 classification found 196 that report an unresolved failure | Decide per module whether they become ERROR with `@ExpectedErrorLog` |
| `onFailure(log)` on a returned future where a test declares the line | 341 sites in 12 modules (2026-10-25); `peegeeq-pg-failover` already uses `andThen` | Fix each site when it shows the signature, or approve a sweep |
| Lockable Resources plugin on Jenkins | Installed, used by no pipeline | Uninstall (needs a Jenkins restart) |

## How this register was checked

On 2026-11-21 every document under `docs-design` outside the archive folders was read in full and its status
claims were checked against the code and git history. Code was read, nothing was run except the Jenkins results
named above, which come from the saved consoles `logs/jenkins-build41-console-20261120.log` and
`logs/jenkins-build42-console-20261121.log`. The register entries for Tasks 1 to 7 rest on the run records in the
archived register; their artifacts were confirmed to exist, their runs were not repeated.

Commit hashes written before a history rewrite do not resolve, for example `7db748b8`, `263309d8`, and `09157c82`.
They remain only in archived text, as labels of the Jenkins builds that ran them.

## Archived records

| Record | Covers |
|---|---|
| [Task register up to 2026-11-21](archive/TASKS_REGISTER_TO_2026-11-21.md) | Run records of Tasks 1 to 8, the failure-log classification, the remediation of 2026-11-07, Jenkins investigations, completed work without a task number |
| [Configuration property wiring audit](archive/CONFIG-PROPERTY-WIRING-AUDIT.md) | Task 1 |
| [Tier 4/5/7 remediation plan](archive/TIER5-BLOCKING-THREAD-VIOLATIONS-PLAN.md) | Task 2 |
| [PostgreSQL/HAProxy gap plan](archive/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_GAPS.md) | Task 3 |
| [Consumer Groups UI redesign](archive/CONSUMER-GROUPS-UI-REDESIGN-PLAN.md) | Completed work |
| [Management UI tests not running](archive/management-ui-tests-not-running.md) | Completed work |
| [Test integrity remediation](archive/TEST-INTEGRITY-DEFECT-REMEDIATION-PLAN.md) | Completed work |
| [Outbox DLQ/filter audit](archive/OUTBOX-DLQ-FILTER-ERRORS-DEAD-CODE-AUDIT.md) | Completed work |
| [Outbox module audit](archive/OUTBOX-AUDIT-FINDINGS-11-Jun-2026.md) | Completed work |
| [Outbox schema qualification](archive/OUTBOX-SCHEMA-QUALIFICATION-REGRESSION.md) | Completed work |
| [Schema processing gaps](archive/SCHEMA-PROCESSING-GAPS-CRITICAL-17-Jun-2026.md) | Completed work |
| [Bitemporal examples walkthrough](archive/bitemporal-examples-expansion-walkthrough.md) | Completed work |
| [Session handover 2026-08-12](archive/SESSION-HANDOVER-20260812.md) | Early verification baseline |

## Status definitions

- **OPEN**: approved work that has not started.
- **ACTIVE**: implementation or investigation is in progress.
- **PARTIAL**: verified deliverables exist and the remainder is listed.
- **COMPLETE**: implementation and proportionate verification are recorded.
- **PROPOSED**: needs a product decision before it becomes work.
- **REJECTED**: the product decision went against the proposal.
