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
| D13 | Smaller code faults found while correcting documents: the `EventStore.appendBuilder()` Javadoc example uses `.get()`; the `PgPrimaryCheckVerticle` Javadoc shows an HAProxy `backup` server, which the design forbids; two sidecar tests use the old generic `org.testcontainers.containers.PostgreSQLContainer<?>` (fixed by D22 on 2026-11-22); `peegeeq-api.yaml` does not describe the `Idempotency-Key` header; several Java comments name documents by paths that were archived before 2026-11-21 (for example `docs-design/tasks/SCHEMA-PROCESSING-GAPS-CRITICAL-17-Jun-2026.md`) | Read during the review | Unscheduled |
| D14 | `peegeeq-migrations` tests use AssertJ (92 `assertThat` lines in 5 classes); the coding principles make JUnit Jupiter assertions the standard and say AssertJ is not a dependency. AssertJ 3.24.2 is pinned in that module's pom only | Checked 2026-11-21: imports in `CustomSchemaIntegrationTest`, `MigrationConventionsTest`, `MigrationIntegrationTest`, `SchemaContractTest`, `SchemaValidationTest` | DONE 2026-11-22: the 5 classes use JUnit assertions and AssertJ is removed from the pom. Three placeholder assertions in `SchemaValidationTest` (two `>= 0` counts, one `ResultSet` not-null) now assert UNIQUE and CHECK constraints exist and the as-of function returns no rows. 56 tests in 6 classes passed on the Jenkins host; a mutation run failed all four probed assertions |
| D15 | `peegeeq-examples` tests: discarded `ConsumerGroup.start()` futures (`AdvancedProducerConsumerGroupTest` 3 sites, `ConsumerGroupResilienceTest` 3 sites); `try`/`catch` around the `Future`-returning `QueueFactory.close()` (`ServerSideFilteringTest`, `PartitionedOrderingDemoTest`); `ConsumerGroupResilienceTest` teardown completes when the manager close fails; `EventSourcingCQRSDemoTest` logs and swallows command failures; `configureOffsetWatermarkTopic` writes through `withConnection` (`EventSourcingCQRSDemoTest`, `PartitionedOrderingDemoTest`) | Read by a review agent on 2026-11-21 while removing the dead Spring hooks; confirmed by reading on 2026-11-22 | DONE 2026-11-22, with the same defects in other classes: `MultiConfigurationIntegrationTest` also discarded factory close futures; `CleanupResultSupport.merge` closed factory and manager at the same time and is deleted for `TestCleanup.run`; three teardowns never closed the queue factory; `AdvancedProducerConsumerGroupTest` accepted 60% of messages and now asserts exact counts and filter routing; `PartitionedOrderingDemoTest` 2b to 2d sent before the topic was configured. 29 tests in 7 classes passed under `-Pintegration-tests` on the Jenkins host; a mutation run failed all three probes. Remaining items are D18 |
| D16 | `peegeeq-migrations/dependency-reduced-pom.xml` is maven-shade output tracked in git. Every build rewrites it, and the tracked copy is stale (it still excludes `oshi-core` and `h2` and lists the removed Flyway 10.10.0, JUnit 5.10.1 and PostgreSQL 42.7.2 overrides) | Checked 2026-11-21: `git diff` after `mvn clean install` | DONE 2026-11-22: the installed artifact is the thin jar (`finalName` writes the shaded jar to a separate file), and the reduced pom dropped Flyway and the PostgreSQL driver that it needs. The shade plugin now sets `createDependencyReducedPom=false`, as `peegeeq-pg-sidecar` does, and the tracked file is deleted. After `mvn clean install` the installed pom equals the source pom and the shaded jar still contains Flyway |
| D17 | `peegeeq-migrations` tests verify the schema through raw JDBC (`DriverManager`, `java.sql.Connection`) and build `new PostgreSQLContainer(...)` by hand; the testing standards ban both. Flyway itself runs on JDBC, so the replacement for the verification queries is a design choice for this module | Read on 2026-11-22 in the 5 classes of D14 | DONE 2026-11-22: the 5 classes follow `StoppedSubscriptionStatusMigrationTest` in the same module: `createStandardContainer()`, a Vert.x pool per test, Flyway on a worker thread, writes in `withTransaction`. Flyway still connects through JDBC, which is the behaviour under test. `SchemaInitializerTestInfrastructureGuardTest` now also scans the migration tests and matches a `java.sql.*` import, which it missed before; it failed on the old classes and passes on the new ones. 56 tests in 6 classes passed on the Jenkins host; a mutation run failed both probes |
| D18 | Items left after D15: `EventSourcingCQRSDemoTest` waits a fixed 500 ms before its CQRS assertions (2 sites); `ConsumerGroupResilienceTest` polls with `setPeriodic` and no deadline of its own; several example tests keep unused `Vertx vertx` parameters; `OutboxFactory.assertNotEventLoopForBlocking` has no caller in `src/main` | Read on 2026-11-22 | DONE 2026-11-22: the 500 ms waits are gone and an extra command or event fails the test when it is counted; the resilience tests poll with a 10 s deadline that fails the test; 21 unused `Vertx` parameters in 9 classes are removed; the dead guard method is deleted. The same fixed 500 ms wait in `OutboxServerSideFilteringTest` (4 sites) is replaced by sending in order and asserting the non-matching rows are still `PENDING`. Three more teardowns now close the factory before the manager (`OutboxServerSideFilteringTest`, `TransactionalOutboxAnalysisTest`, `DistributedSystemResilienceDemoTest`), and `TransactionalOutboxAnalysisTest` uses `createStandardContainer()`. 43 tests in 11 classes passed under `-Pintegration-tests` on the Jenkins host; a mutation run failed both probes. Remaining items are D20 |
| D19 | The Jenkinsfile sets `JAVA_HOME` to `/usr/lib/jvm/temurin-27-jdk-amd64` and checks that `bin/java` and `bin/javac` exist there; the Jenkins host has only `java-21-openjdk-amd64` and `temurin-25-jdk-amd64` under `/usr/lib/jvm`, so a PeeGeeQ job build stops at that check. Installing `temurin-27-jdk` needs sudo, which the `jenkins` user does not have | Checked 2026-11-22: `ls /usr/lib/jvm` on the host; the job's check was read, not run | DONE 2026-11-22: the owner installed `temurin-27-jdk`. Checked on the host: `/usr/lib/jvm/temurin-27-jdk-amd64/bin/java -version` reports Temurin 27.0.1.1+4, `bin/javac` exists, and `~/.m2/toolchains.xml` names that path. A PeeGeeQ job build has not been run since |
| D20 | Items found while fixing D18, not fixed: the `fundscustody` tests wait fixed delays before reading back what they wrote (39 calls in `NAVServiceTest`, `RegulatoryReportingServiceTest`, `TradeAuditServiceTest`, `TradeServiceTest`); `SharedTestContainers`, which almost every example test uses, and 7 other example test classes build `new PostgreSQLContainer(...)` by hand; `DistributedSystemResilienceDemoTest` fails requests with `Math.random()` at 70%, so its circuit-breaker assertion can fail by chance (about 0.16% of runs by my calculation; not measured) | Read on 2026-11-22 | DONE 2026-11-22. Fundscustody: the delays are removed; reads chain on the completed `append`, and "as reported" and change-period boundaries use the transaction times the store returns instead of the JVM clock. `RegulatoryReportingService` gains `getRegulatorySnapshot(..., Instant filingDateTime)` and `getAIFMDReport(..., Instant)`; the existing methods delegate with `Instant.now()`. `testLateTradeConfirmation` asserted nothing that could fail and now expects both trades. `FundsCustodyTestBase` teardown logged and passed a failed store or manager close, and `cleanupDatabase` passed any failure; both now fail the test through `TestCleanup`. Containers: `SharedTestContainers` starts from `createStandardContainer()` (reuse off, as the factory sets it) and keeps its shared-memory size and PostgreSQL settings; its two methods without callers are deleted; the 7 classes use `createStandardContainer()`. `SchemaInitializerTestInfrastructureGuardTest` now scans `peegeeq-examples/src/test/java` and checks only `@Container` fields of type `PostgreSQLContainer`; it failed on the old 9 files and passes now. `DistributedSystemResilienceDemoTest` fails requests in a fixed pattern and asserts exact counts. All 143 tests in 33 classes passed under `-Pintegration-tests` on the Jenkins host with Temurin 27; a mutation run failed all three probes |
| D21 | Items found while fixing D20, not fixed: `ShutdownTest` tests a private copy of a demo shutdown method, not PeeGeeQ code, and simulates work by blocking on latches nobody counts down; `peegeeq-examples-spring` has its own `SharedTestContainers` with hand-built containers (standalone project, outside the guard); `peegeeq-benchmarking` `PeeGeeQRestPerformanceTest` builds its container by hand | Read on 2026-11-22 | DONE 2026-11-22: `ShutdownTest` is deleted; the method it tested no longer exists in the demo, so its tests covered only a private copy, the JDK executor, and Testcontainers start and stop. The spring `SharedTestContainers` starts from `createStandardContainer()`, loses its latch polling loop (`start()` returns once the container accepts connections) and its two methods without callers; `PeeGeeQReactiveConfigTest` fallback database names follow. In `peegeeq-benchmarking` the two other hand-built containers are fixed too, and `VertxPerformanceOptimizationExampleTest` is deleted: every test asserted constants and "simulated" values the test set itself, and its container was never used. `SchemaInitializerTestInfrastructureGuardTest` now also scans `peegeeq-examples-spring` and accepts `SharedTestContainers.getSharedPostgreSQLContainer()` for a `@Container` field; it failed on the old spring class and passes now. On the Jenkins host: `peegeeq-examples-spring` core and integration, 131 tests in 32 classes passed; the two benchmarking classes under `-Pperformance-tests`, 8 tests, passed |
| D22 | Test infrastructure outside the guard, found on 2026-11-22 by search, not fixed: 47 test classes build `new PostgreSQLContainer(...)` by hand (`peegeeq-rest` 35, `peegeeq-db` 8, `peegeeq-bitemporal` 2, `peegeeq-integration-tests` 1, `peegeeq-rest-client` 1); 11 test classes use raw JDBC (`peegeeq-bitemporal` 4, `peegeeq-benchmarking` 2, `peegeeq-rest` 2, `peegeeq-test-support` 2, `peegeeq-db` 1). `PgBiTemporalEventStorePerformanceTest` and other bitemporal tests create event-store tables with hand-written DDL through JDBC; replacing that needs a test-support way to create a named event-store table from the production template | Counted 2026-11-22 with `grep` over `peegeeq-*/src/test`; the classes were not read | DONE 2026-11-22, module by module. Containers: every PostgreSQL container in the test trees comes from a test-support factory; the 47 classes use `createStandardContainer()` with their own settings chained after it. The count missed the diamond form `new PostgreSQLContainer<>(`: the two `peegeeq-pg-sidecar` classes of D13 used it and are fixed too. The image copies `PgTestImageConstant` (`peegeeq-db`) and `PgSidecarTestImageConstant` are deleted, and `SharedPostgresTestExtension` no longer sets container reuse. JDBC: `EventStoreTestTables` in the `peegeeq-db` test-jar creates a named event-store table from the production templates (`base/06-event-store-template.sql`, then `eventstore`); `PgBiTemporalEventStoreIntegrationTest`, `WildcardPatternComprehensiveTest` and `PgBiTemporalEventStorePerformanceTest` use it in place of hand-written DDL. `DatabaseWorkerVerticleTest` creates its second table on the manager pool, and its teardown no longer discards the store close futures or passes a failed manager close. `ReactiveNotificationHandlerLifecycleTest` loses a JDBC `ALTER TABLE IF EXISTS` on a table it never used. The two `peegeeq-rest` classes write in one `withTransaction` on the manager pool. `PeeGeeQTestBaseTest` checks connectivity through a Vert.x pool. `ParameterizedPerformanceDemoTest` is deleted: it asserted nothing and reported `Math.random` metrics. The one JDBC class left is `SharedPostgresTestExtension`, which Task 9 of `PEEGEEQ_DELIVERY_OUTCOME_REDESIGN_PROPOSAL.md` moves to the Flyway test path. `SchemaInitializerTestInfrastructureGuardTest` now scans the `src/test/java` tree of every top-level module, accepts every test-support container factory, and keeps `SharedPostgresTestExtension` as its one named JDBC exception, which fails once that class no longer imports JDBC. On the Jenkins host under `-Pintegration-tests`: `peegeeq-db` 819 tests, the 35 `peegeeq-rest` classes 320 tests, the 6 bitemporal classes 145, `peegeeq-pg-sidecar` 10, `RestClientIntegrationTest` 15, `PeeGeeQCriticalPathSmokeTest` 6, `PeeGeeQTestBaseTest` 4; under `-Pperformance-tests` `PgBiTemporalEventStorePerformanceTest` 4; all passed. The first `peegeeq-rest` run failed 2 tests in `PartitionedConsumptionRestIntegrationTest`: the payload was bound as a `String` to `$2::jsonb`, which stores a JSON string; it is now bound as a `JsonObject`, and the class passed 5 tests. A mutation run failed the three probes (missing template directory, wrong dead group, wrong connectivity value); a guard probe reported all four planted violations. Remaining items are D23 |
| D23 | Items found while fixing D22, not fixed: `PeeGeeQTestBase` sets System properties for its configuration and `PeeGeeQTestBaseTest.testSystemProperties` asserts them, against the rule that PeeGeeQ configuration uses no System properties; `PeeGeeQTestBaseTest.testMetricsRecording` asserts only that a metrics object is not null; the three negative-path tests in `TransactionParticipationIntegrationTest` (rollback after append, rollback after business insert, boundary integrity) accept any failure of the transaction without checking that it is the planted one; all 9 tests in that class build their own pool and close it only on the success path, so a failed test leaves its pool open | Read on 2026-11-22 | DONE 2026-11-22. System properties: no code read the six `test.database.*` properties; `PeeGeeQTestBase` and `ParameterizedPerformanceTestBase` no longer set them, and `testSystemProperties` is deleted. `PeeGeeQTestBase` teardown no longer logs and passes a failed container stop. `testMetricsRecording` reads the gauge and the timer back from the registry with the standard tags and asserts their values. `PeeGeeQTestContainerFactory.createBaseIntegrationTestContainer()` is deleted with its test: it had no other caller, and its stated reason (`BaseIntegrationTest` reuses its container) is false. Found while fixing: `ParameterizedPerformanceTestBase.runTestWithProfile` and `ConsumerModePerformanceTestBase.runConsumerModeTest` set the requested profile before the container switch, so the switch never ran and every profile ran on the STANDARD container from `@BeforeEach`. `setupContainerForProfile` now records the profile once the new container has started. `ParameterizedPerformanceTestBaseTest` now reads `synchronous_commit` and `max_connections` from the running server; it failed for BASIC and HIGH_PERFORMANCE before the fix and passes after it. Testcontainers starts every PostgreSQL container with fsync off, so fsync does not separate the profiles (seen on the BASIC run). `TransactionParticipationIntegrationTest`: the three negative-path tests assert the planted cause (SQL state `42P01`, and `IllegalArgumentException` "Event payload cannot be null"); every pool, including the one in `createBusinessTable`, closes on both paths through `TestCleanup.cleanup`; teardown closes the store and then the manager through `TestCleanup.run` instead of a `try`/`catch` around the store's `Future`-returning close; the batch test asserts 10 distinct transaction times instead of at most 10. On the Jenkins host: `ParameterizedPerformanceTestBaseTest` 4, `PeeGeeQTestBaseTest` and `PeeGeeQTestContainerFactoryTest` 13, `TransactionParticipationIntegrationTest` 9 under `-Pintegration-tests`; `ConsumerModePerformanceStandardizedTest`, `ConsumerModePerformanceTestBaseTest` and `HardwareProfilingIntegrationTest` 32 under `-Pperformance-tests`; all passed. A mutation run failed all four probes (profile set before the switch, a different planted SQL error, a different planted validation error, a missing metric tag). Remaining items are D24 |
| D24 | Items found while fixing D23, not fixed: `ParameterizedPerformanceTestBase.runTestWithProfile` and `ConsumerModePerformanceTestBase.runConsumerModeTest` catch every exception, log it at ERROR and return a failed result, so a caller that does not call the matching `validate...Thresholds` method passes when the operation failed (the callers were not each checked); `HardwareProfilingIntegrationTest` logs with Python-style `{:.1f}` placeholders, which SLF4J does not expand, simulates load with a busy loop, and asserts ranges such as `>= 0.0`; `PeeGeeQTestContainerFactoryTest` keeps narration log lines (`=== TEST METHOD STARTED ...`); in `TransactionParticipationIntegrationTest` the null-payload test counts events by a correlation ID that its append never sets, so that count is 0 whatever happens | Read on 2026-11-22 | Unscheduled |
| D25 | Container images named in tests, reported by the owner on 2026-11-22: 27 test classes passed an image string to a container (`haproxy:2.8-alpine` in 5, `edoburu/pgbouncer:v1.25.2-p0` in 1, `hashicorp/consul` in 21) and ran three Consul versions (1.22.1 in `peegeeq-pg-failover`, 1.15.3 in `peegeeq-service-manager`, 1.15 in `peegeeq-examples`); the guard checked only `PostgreSQLContainer` | Read on 2026-11-22 | DONE 2026-11-22. `TestContainerImages` (`peegeeq-test-support`, `test.containers`) holds `HAPROXY_IMAGE`, `PGBOUNCER_IMAGE` and `CONSUL_IMAGE`; every one of the 27 classes uses it, and every test runs Consul 1.22.1. `SchemaInitializerTestInfrastructureGuardTest` rejects a container constructor or `DockerImageName.parse` whose first argument is a string literal, and a test's own `String` constant named `...IMAGE...` set to a literal; before the fix it reported all 27 classes, and a probe class with `DockerImageName.parse("probe:1")` failed it. Dead image settings removed: the `peegeeq.test.container.image` System property in the `peegeeq-outbox` Surefire configuration (nothing reads it), and the `PeeGeeQRestTestSuite` Javadoc and `peegeeq-rest/docs/README.md` lines that documented `-Dtestcontainers.postgres.image`, `-Dpeegeeq.logging.level` (nothing reads either) and a `PeeGeeQRestPerformanceTest` that does not exist. Two `peegeeq-db` classes converted under D22 had their `PostgreSQLTestConstants` import above the licence comment; it is in the import block now. On the Jenkins host under `-Pintegration-tests`: `peegeeq-service-manager` 3 classes 20 tests, `peegeeq-examples` `ServiceDiscoveryExampleTest` 5, `peegeeq-db` 5 classes 24, `peegeeq-bitemporal` `HaProxyNotificationFailoverIntegrationTest` 1, `peegeeq-pg-failover` 17 classes 288; all passed. The run logs do not name the image a container started from. Remaining items are D26 |
| D26 | Items found while fixing D25, not fixed: `peegeeq-management-ui` and `peegeeq-utilities-ui` `src/tests/global-setup-testcontainers.ts` each name `postgres:15.13-alpine3.20` themselves, so a PostgreSQL version change must edit two TypeScript files beside `PostgreSQLTestConstants`; `peegeeq-test-support`, `peegeeq-rest` and `peegeeq-outbox` `test-profiles.properties` are read by no code or build file, and the test-support one sets `testcontainers.postgres.image` and `testcontainers.reuse.enable=true`; `peegeeq-examples` `peegeeq-development.properties` and `peegeeq-production.properties` set `peegeeq.logging.level`, which nothing reads; 27 files outside this change (22 in `peegeeq-outbox`, three in `peegeeq-rest/src/test/java/dev/mars/peegeeq/rest/examples`, plus `peegeeq-native` `PgNativeQueueProducer` and `peegeeq-benchmarking` `OutboxPerformanceTest`) place an import above the licence comment | Read on 2026-11-22 | Unscheduled |

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
