# PeeGeeQ Connection Management and HAProxy Failover — Implementation Plan

**Document revision**: 2026-10-07, coordination, bootstrap, and recovery prerequisites revision. Documents only.
Sections 2 to 4 retain dated baseline evidence. No recorded Java test was rerun for this revision.
The revised execution order and release gates are in §8.
**Created**: 2026-10-07 at commit `ff5c17da`
**Controls**: Task 8 of the [consolidated task register](../tasks/tasks.md). Status and execution
order are recorded in the register, not here.
**Design**: [PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md),
design revision 2026-10-07, coordination, bootstrap, and recovery prerequisites revision.

| Section | Content |
|---|---|
| §1 | Purpose and scope |
| §2 | Gap between the design and the implementation, by requirement |
| §3 | Findings, with evidence |
| §4 | Evidence from runs |
| §5 | Adopted design contracts and deployment gates |
| §6 | TDD method |
| §7 | Scenario coverage |
| §8 | Phases |
| §9 | Record of what was read, and document history |

---

## 1. Purpose and Scope

This plan defines the work and verification required to implement PeeGeeQ's PostgreSQL
connection, failover, and recovery design. It is for developers selecting the next phase,
reviewers assessing completion evidence, and operators selecting production dependencies.
The design documents specify the target behaviour. This plan records dated findings,
implementation order, acceptance obligations, deployment gates, and the evidence from runs.

**The outcome the work must deliver.** PeeGeeQ application instances must use a stable SQL
endpoint to reach one authorised PostgreSQL writer. A writer is a primary permitted to accept
application writes under current authority and provider admission. Failover must exclude the
former writer before a replacement serves, preserve acknowledged writes under the synchronous
policy, replace unusable pool and LISTEN connections, and complete required durable catch-up.
LISTEN is the database subscription mechanism for notification channels. Its notifications
request processing; durable database state determines the work still to deliver.

The intended production topology has one primary and two synchronous standbys, redundant
HAProxy and status endpoints, and optional redundant PgBouncer poolers. Sidecars observe
writer eligibility. The proposed `peegeeq-pg-failover` controller coordinates automatic
transitions through Consul. A separate node-control provider enforces **fencing**, meaning
confirmed database stop with restart inhibited, and guarded writer admission. Selecting
and proving that provider is a deployment dependency of both manual and automatic failover.

**How the execution order is organised.** Manual profile B uses operator-directed transitions
through the provider. Automatic profile A adds Consul ownership and controller orchestration
to the same boundary. The plan first requires selection of the provider, admission integration,
and endpoint topology. G-7 records the automatic coordinator before phase 8. Phase 7 first
implements production-manager commit enforcement, then bootstrap and manual transitions.
Manual transition and re-join tests precede automatic transition work.
Later phases complete operation deadlines, migration to shared pooled access, LISTEN recovery, readiness,
and endpoint failure handling. Full recovery qualification follows those integrations.

For example, a test that promotes a standby and runs a query does not finish the work for a
primary-crash scenario. Its evidence must also establish the former writer's fence, surviving
acknowledged data, replacement synchronous coverage, application connection recovery, and
required subscription catch-up. A local Docker provider test proves that adapter's behaviour.
Production provider qualification requires evidence on the selected deployment platform.
The 45-second recovery objective is a qualified workload target, not a result inferred from
configuration defaults or an earlier component test.

**How to interpret the records.** Requirement identifiers `R-` describe system obligations.
Scenario identifiers `S1` to `S53` describe acceptance cases in the system design. The plan's
`P-` entries record adopted design contracts; `G-` entries identify deployment selections and
qualification gates. `IMP-`, `TST-`, and `CFG-` entries record dated implementation, test, and
configuration findings. Read their dates and evidence before using them to scope new work.

Sections 2 to 4 retain historical assessments and run records. They do not establish the
current repository state or revised scenario coverage. A requirement table does not prove
an implementation. Completion requires the current code, configuration, and fresh logs for
the relevant scope. Record per-class executed test counts and failures. Keep dependency
failure and teardown failure visible. No old test count closes a new acceptance obligation.

**Reading guide and boundaries.** Begin with
[the system design](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md#1-purpose-and-scope) to understand
the architecture and terms. Use [the Consul design](PEEGEEQ_FAILOVER_CONSUL_DESIGN.md) and
[the sidecar guide](PEEGEEQ_PG_SIDECAR.md) for component contracts. In this plan, §5 contains
adopted decisions and deployment gates, §6 defines the test-driven implementation method,
§7 groups acceptance cases by phase, and §8 defines execution order. Section 9 records source
reads and revision history. Use Task 8 in the consolidated task register to locate assigned
work, then verify its reported state against the corresponding artifacts and run evidence.

The scope below includes connection management, database transitions, client recovery,
configuration contracts, and real-component acceptance. Application-instance federation and
routing are separate work. External side effects retain their application delivery and
idempotency contracts. This plan does not promise exactly-once processing or qualify an
unselected production deployment.

The data model and field sources of truth are in the design §1.1. Configuration is authoritative
for cluster identity and membership. Consul stores transition intent and ownership metadata.
The node-control provider stores action receipts, accepted cluster generation, installed node
guards, quarantine, and execution grants. Profile authority stores confirmed and pending
durability policy intent. Counts, role, peer health, replay boundary, and readiness are computed.
Bitemporal replay reuses the existing tenant cursor and lease with the stable writer barrier.
No parallel cursor, acknowledgement ledger, or generic idempotency-result table is introduced.

In scope:

- The single endpoint, sidecar eligibility, fencing, synchronous durability, and safe re-join.
- Shared pooled access, operation deadlines, error classification, LISTEN recovery, and health.
- A dedicated proposed `peegeeq-pg-failover` module and independent node-control integration.
- Real-component acceptance for S1 to S53, local infrastructure, and a scenario runbook.
- Configuration loader, defaults, property contract tests, and configuration guide updates
  when proposed keys are implemented.

Out of scope:

- PeeGeeQ federation and application-instance routing in `peegeeq-service-manager`.
- Patroni as an implementation requirement.
- An implicit exactly-once guarantee for external side effects or consumer delivery.
- Treating a local Docker adapter as qualification of a production fencing provider.

This documentation phase revises the five files in this folder. It does not change Java,
Maven, configurations, Compose files, the task register, or production state. Existing changes
outside this folder remain separate work.

Full production write recovery uses three PostgreSQL nodes with synchronous acknowledgement
coverage. A two-node pair preserves safety but cannot resume required synchronous writes
after promotion until a standby is restored. There is no automatic asynchronous downgrade.

## 2. Gap between the design and the implementation

**Historical baseline.** The table below applies to the earlier design and recorded source
assessment. It is retained for traceability. It is not a current implementation audit and does
not prove coverage of the revised contracts. Names and mechanisms superseded by §5 must not
be copied into new work.

Assessed 2026-10-07 at commit `ff5c17da` from a read of the source (§9.1) and from the runs in
§4. "Met" means a test proves it. "Not met" means the code or configuration does not do it.
"Unknown" means it has not been run.

| Requirement | State | What exists | What is missing | Finding |
|---|---|---|---|---|
| R-15 Automatic promotion | Not met | A design document, status PROPOSED | `PgFailoverMonitor` does not exist. A search of every `src` directory for `PgFailoverMonitor`, `PgPrimaryElector`, `PgNodeConfig`, and `peegeeq.pg.failover` found nothing. No `failover` package exists in `peegeeq-service-manager` | `IMP-18` |
| R-16 Promotion only under the Consul lock | Not met | `ConsulClient` is already used by `ConsulServiceDiscovery` | `PgPrimaryElector` does not exist | `IMP-18` |
| R-1 One writable node | Not met | — | Depends on R-2, R-3, R-4, and R-16. None is met | `IMP-05`, `IMP-06`, `IMP-18` |
| R-2 Role-based routing | Not met | The sidecar reports the role of a primary | All five HAProxy configurations use `pgsql-check` and a `backup` server. None uses `httpchk`. The sidecar is untested on a standby and untested behind HAProxy | `IMP-05` |
| R-3 Session shutdown on a role change | Not met | — | No configuration sets `on-marked-down shutdown-sessions`. A run showed a pooled connection staying on the previous node (§4.2) | `IMP-06` |
| R-4 Fencing | Not met | — | The session termination on lock loss, the rewind and re-join, and `wal_log_hints` are designed and not built. No test | `IMP-18`, `P-3` |
| R-5 Connection discard | Not met | Idle timeout | No maximum lifetime: `PgPoolConfig` has no such field. No handling of SQLSTATE `25006` | `IMP-06`, `IMP-15` |
| R-6 LISTEN reconnect without a limit | Partly met | The native queue consumer retries without a limit, capped at 30 seconds | The bitemporal handler stops after five attempts, 31 seconds | `IMP-02` |
| R-7 LISTEN wrong-node detection | Not met | — | Neither component probes its connection | `IMP-16` |
| R-8 LISTEN catch-up | Partly met, unknown | The bitemporal handler re-issues `LISTEN` for subscribed channels. The native consumer processes available messages on connect in `LISTEN_NOTIFY_ONLY` mode and polls in `HYBRID` mode | Durable replay after a reconnect has not been run | `IMP-16` |
| R-9 Breaker on every pooled operation | Not met | The breaker covers `PgConnectionManager.withTransaction` and `withConnection` | The outbox, the native queue, the dead-letter and recovery managers, the metrics collector, and the health checks use the raw pool. The bitemporal store builds its own pool | `IMP-01` |
| R-10 Breaker counts connection failures only | Not met | — | The breaker records every exception, including business SQL errors. Task 3.2 chose this and a test asserts it | `IMP-08` |
| R-11 Readiness reflects the database | Not met | `/api/v1/setups/:setupId/health` returns 503 when unhealthy | `/health` returns a fixed `UP` body. `/health/live` and `/health/ready` do not exist. No LISTEN check exists. The queue checks share the `database` breaker | `IMP-03`, `IMP-07` |
| R-12 Instance routing | Removed from the design 2026-10-07 | — | Routing between PeeGeeQ instances belongs to `peegeeq-service-manager`, the federation module, and is not part of database failover | See §3.3 |
| R-13 Timeouts to the millisecond | Not met | — | `PgConnectionManager` passes whole seconds. The bitemporal pool sets literal timeouts with no unit | `IMP-09` |
| R-14 Schema on every pooled operation | Partly met | Transaction-local schema on the `PgConnectionManager` path, tested through PgBouncer | Raw-pool callers and the bitemporal pool do not get it | `IMP-01`, `IMP-11` |

Design elements with no implementation:

| Design element | State |
|---|---|
| Keys `peegeeq.database.pool.max-lifetime-ms`, `peegeeq.database.listen.host`, `.listen.port`, `.listen.probe-interval-ms`, `.listen.reconnect-max-delay-ms` | Absent from `peegeeq-default.properties` and from `PeeGeeQConfiguration`. New keys must also be added to `ShippedConfigurationPropertyContractTest` and `docs/PEEGEEQ_CONFIGURATION_GUIDE.md` (Task 1 contract) |
| HAProxy configuration of design §5.3 | Absent. Every existing configuration uses `pgsql-check` |
| Failover monitor configuration keys of design §5.4 | Absent |
| Fencing rules of design §5.5 | Absent |
| Sidecar `pg.query-timeout-ms` | Absent. The sidecar has no query timeout |
| Compose stack for profiles A and B, and the runbook | Absent. Only the profile D stack exists |
| `PgConsulFailoverIntegrationTest` | Absent |

---

## 3. Findings

These are dated findings from the earlier assessment. Documentation corrections in this
revision do not close production or test defects. Reassess each finding against source and
new logs in its implementation phase. In particular, the old service-manager placement and
SQL-session fencing are superseded design choices.

Line numbers are at commit `ff5c17da`.

### 3.1 Implementation findings

| ID | Finding | Evidence | Confirmed by |
|---|---|---|---|
| IMP-01 | The pool circuit breaker and the transaction-local `search_path` apply only to calls made through `PgConnectionManager.withTransaction` and `withConnection`. 16 production files use that path, all but one in `peegeeq-db`. The outbox producer and consumer, the native queue consumer, the dead-letter manager, the stuck-message recovery manager, the metrics collector, and the health checks call the raw Vert.x `Pool`. The bitemporal event store builds a separate pool. | `PgConnectionManager` L245–L256; `PgClient.getReactivePool()` L71–L77; `PeeGeeQManager` L243–L270; `PgBiTemporalEventStore` L1978–L1990; search of `src/main` | Source read and search. The outbox and native classes were not read in full |
| IMP-02 | `ReactiveNotificationHandler` makes five reconnect attempts with delays of 1, 2, 4, 8, and 16 seconds, 31 seconds in total. It then logs "Reactive notifications will not be available until manual restart" and stops. No code restarts it. `PgNativeQueueConsumer` retries without a limit. | `ReactiveNotificationHandler` L87–L88, L440–L448; `PgNativeQueueConsumer` L364–L387 | Source read. Behaviour across a long outage needs a run |
| IMP-03 | The REST `/health` route returns a fixed `{"status":"UP"}` body. The route does not consult `HealthCheckManager`. | `PeeGeeQRestServer` L536–L540; `HealthHandler` L71–L74 | Source read |
| IMP-04 | Moved to §3.3. Not part of this task. | — | — |
| IMP-05 | No test runs the sidecar against a standby. No test puts HAProxy `httpchk` in front of the sidecar. The class Javadoc says another module covers the standby case. None does. | `PgPrimaryCheckVerticle` L86–L100; `PgPrimaryCheckIntegrationTest` L37–L39 | Source read |
| IMP-06 | No HAProxy configuration sets `on-marked-down shutdown-sessions` or `on-marked-up shutdown-backup-sessions`. A pooled connection opened to the backup stays there after the primary returns. | Five HAProxy configurations; run in §4.2 | **Run**, for the pool. Not run for LISTEN |
| IMP-07 | The four database health checks share one breaker named `database`. A check that returns an unhealthy status is recorded as a breaker error. An outbox backlog above 10,000, or more than 100 dead-letter rows in an hour, counts toward opening it. | `HealthCheckManager` L506–L529, L567, L587–L589, L615, L659, L679–L682 | Source read |
| IMP-08 | The pool breaker records every failure of the caller's operation, including SQL errors. `CircuitBreakerManager` records `Exception.class`. | `PgConnectionManager` L300–L304; `CircuitBreakerManager` L64; `PgPoolCircuitBreakerIntegrationTest` L184–L193 | Source read |
| IMP-09 | `PgConnectionManager` converts pool timeouts to whole seconds, so a value under 1,000 ms becomes 0. `PgBiTemporalEventStore` calls `setConnectionTimeout(30000)` and `setIdleTimeout(600000)` with no unit. Its comments say 30 seconds and 10 minutes. The default unit of Vert.x `PoolOptions` is believed to be seconds. | `PgConnectionManager` L363–L366; `PgBiTemporalEventStore` L1966–L1967 | Needs a check of the Vert.x version in use |
| IMP-10 | The seven-argument `PeeGeeQConfiguration` constructor sets `peegeeq.database.host` from its argument. A proxy host from an environment variable or a profile still replaces it. `PeeGeeQDatabaseSetupService` uses this constructor. | `PeeGeeQConfiguration` L120–L125, L512–L517 | Source read |
| IMP-11 | No test runs the outbox, the native queue, or the bitemporal store through PgBouncer in transaction mode. | `PgBouncerTransactionModeTest` L240–L277 | Needs a run |
| IMP-12 | `PgPrimaryCheckVerticle.stop` completes successfully when the pool close fails. | `PgPrimaryCheckVerticle` L103–L110 | Source read |
| IMP-13 | Moved to §3.3. Not part of this task. | — | — |
| IMP-14 | Every HAProxy configuration in the repository sets `timeout client` and `timeout server` to 60 seconds or less. A LISTEN connection that carries no traffic for that long may be closed by HAProxy. | Five HAProxy configurations | Needs a run |
| IMP-15 | `PgPoolConfig` has no maximum-lifetime field. Its class comment lists `maxLifetime` among concepts removed. Nothing closes a pooled connection on SQLSTATE `25006`. | `PgPoolConfig` L28–L31 | Source read |
| IMP-16 | Neither LISTEN component runs a probe on its connection. Each reconnects only from its close handler. | `ReactiveNotificationHandler` L393–L398; `PgNativeQueueConsumer` L289–L297 | Source read |
| IMP-17 | The sidecar has no query timeout on `pg_is_in_recovery()`. | `PgPrimaryCheckVerticle` L86–L100 | Source read |
| IMP-18 | The Consul failover monitor is not implemented. `PgNodeConfig`, `PgPrimaryElector`, `PgFailoverMonitor`, and the `peegeeq.pg.failover.*` keys appear in no source file. Without it the system has routing and no automatic promotion. | Search of every `src` directory, 2026-10-07 | Source search |
| IMP-19 | `PEEGEEQ_FAILOVER_CONSUL_DESIGN.md` states that HAProxy's `pgsql-check` calls `pg_is_in_recovery()` and routes writes only to the primary (its §1, §3.3 step 4, §8). `pgsql-check` only checks that PostgreSQL answers the startup packet. `PEEGEEQ_PG_SIDECAR.md` §1 states this correctly. The monitor design depends on role-based routing, which the sidecar provides and `pgsql-check` does not. | The two documents | Document read |
| IMP-20 | `PEEGEEQ_PG_SIDECAR.md` §8 creates the role with `PASSWORD ''`, and its comment says the `REVOKE`/`GRANT` pair restricts `haproxy_check` to the `postgres` database. The statements revoke `CONNECT` from every other role instead. Its §7 passes settings to the container with `-e`, and its own note says a native binary does not read them. Its two Overview links targeted paths where no file existed; they were corrected on 2026-10-07 when the guide moved into this folder. | `PEEGEEQ_PG_SIDECAR.md` L28–L29, L286–L302, L312–L316 | Document read |

### 3.2 Test and configuration findings

| ID | Finding | Evidence | State |
|---|---|---|---|
| TST-01 | `HaProxyConnectionFailoverTest` waited a fixed 4,000 ms and 8,000 ms with `vertx.setTimer`. | L516–L518, L539–L541, L607–L609 at `ff5c17da` | Repaired in the working tree (§4.2) |
| TST-02 | The same test stopped and started containers on the event loop. | L512, L531, L603 at `ff5c17da`; confirmed by the baseline log (§4.1) | Repaired in the working tree |
| TST-03 | The same test closed the injected `Vertx` in `@AfterEach`. | L297–L298 at `ff5c17da` | Repaired in the working tree |
| TST-04 | Phase 5 was named "failback" and asserted only that `SELECT 1` returned 1. | L490–L555 at `ff5c17da` | Repaired in the working tree. The repaired test is red (§4.2) |
| TST-05 | `HaProxyStreamingReplicationFailoverTest` checks streaming readiness by the exit code of a `psql` query, which is 0 when no row is returned. | L110–L112 | Open |
| TST-06 | The sidecar tests bind fixed HTTP ports 18008 to 18011. | `PgPrimaryCheckIntegrationTest` L52, L129; `PgPrimaryCheckLifecycleTest` L67, L80 | Open |
| TST-07 | Phases 1 to 4 of `HaProxyConnectionFailoverTest` declare a `Vertx` parameter they do not use. | Working tree | Open |
| CFG-01 | Comments in two HAProxy configurations state that `pgsql-check` detects "a read-only replica". It does not. | `haproxy-failover.cfg` L39–L41; `haproxy-failover-local.cfg` L25–L27 | Open |
| CFG-02 | The init scripts run `CREATE USER haproxy_check;`. The sidecar tests log in as a superuser with a password. No test uses a dedicated sidecar role. | Three init SQL files; `PgPrimaryCheckIntegrationTest` L56–L60 | Open |

### 3.3 Findings outside this task

`peegeeq-service-manager` is the discovery and federation manager for a PeeGeeQ cluster. Its pom
describes it as "Service discovery and federation manager for PeeGeeQ instances". The owner
stated on 2026-10-07 that it is a future design for a federated PeeGeeQ cluster and is not
related to database failover. The review had treated its instance routing as part of the
failover design. These findings about it were recorded during the source read. They are not
part of Task 8 and are listed here so that they are not lost.

| Finding | Evidence |
|---|---|
| `ConnectionRouter` treats every HTTP response as success and does not read the status code. It routes GET requests only. (Formerly `IMP-04`) | `ConnectionRouter` L150–L177 |
| `ConsulServiceDiscovery.discoverInstances` starts a `healthServiceNodes` call with no failure handler. The class branches on the environment names `test` and `test-unhealthy` in production code. (Formerly `IMP-13`) | `ConsulServiceDiscovery` L88, L98, L169–L180 |
| The Consul check that `ConsulServiceDiscovery` registers for each instance polls `/health`, which returns a fixed body, and sets no `deregisterAfter`. (Formerly part of `IMP-03`) | `ConsulServiceDiscovery` L88–L94 |

---

---

## 4. Evidence from runs

**Historical run records.** Counts below were recorded before this design revision. They were
not rerun in this documentation phase. They do not qualify the revised production design.
Statements about the working tree in these records describe the tree at the recorded run.

### 4.1 Baseline of the existing tests

Run 2026-10-07 on the development machine at commit `ff5c17da`. No Java file differed from
that commit.

Rebuild: `mvn clean install -DskipTests -pl ":peegeeq-db,:peegeeq-bitemporal,:peegeeq-pg-sidecar" -am`.
Seven reactor modules, `BUILD SUCCESS`, 51.4 seconds. Log:
`logs\rebuild-task8-phase4-20261007.log`.

All three test runs used `-Pintegration-tests` with a `-Dtest=` scope.

| Module | Class | Tests run | Failures | Errors | Skipped | Time |
|---|---|---:|---:|---:|---:|---:|
| `peegeeq-db` | `HaProxyConnectionFailoverTest` | 6 | 0 | 0 | 0 | 67.28 s |
| `peegeeq-db` | `HaProxyStreamingReplicationFailoverTest` | 1 | 0 | 0 | 0 | 6.48 s |
| `peegeeq-db` | `PgBouncerTransactionModeTest` | 1 | 0 | 0 | 0 | 3.31 s |
| `peegeeq-db` | `PgPoolCircuitBreakerIntegrationTest` | 1 | 0 | 0 | 0 | 6.07 s |
| `peegeeq-bitemporal` | `HaProxyNotificationFailoverIntegrationTest` | 1 | 0 | 0 | 0 | 30.76 s |
| `peegeeq-pg-sidecar` | `PgPrimaryCheckIntegrationTest` | 7 | 0 | 0 | 0 | 4.28 s |
| `peegeeq-pg-sidecar` | `PgPrimaryCheckLifecycleTest` | 2 | 0 | 0 | 0 | 1.74 s |
| | **Total** | **19** | 0 | 0 | 0 | |

Logs: `logs\peegeeq-db-failover-baseline-20261007.log`,
`logs\peegeeq-bitemporal-failover-baseline-20261007.log`, `logs\pg-sidecar-baseline-20261007.log`.

The line `[phase-5] primary2 started` was logged on `vert.x-eventloop-thread-0`, which confirms
`TST-02`. All seven classes test profile D behaviour or a single component. None tests a
role-based configuration.

### 4.2 Red run of the repaired failover test

Run 2026-10-07. `HaProxyConnectionFailoverTest` was changed, with no production change and no
HAProxy configuration change:

- Phases 5 and 6 identify the answering node by `system_identifier` from `pg_control_system()`.
- A 250 ms poll against a 30,000 ms deadline replaces the fixed waits.
- Container stop and start run through `vertx.executeBlocking`.
- `@AfterEach` no longer closes the injected `Vertx`.
- Phase 5 asserts that, after the replacement primary starts, a new connection through HAProxy
  reaches it and the pool that served queries during the outage reaches it.

Rebuild: `mvn clean install -DskipTests -pl ":peegeeq-db" -am`, `BUILD SUCCESS`, 49.8 seconds.
Log: `logs\rebuild-task8-phase5-red-20261007.log`.

Test: `mvn test -Pintegration-tests -pl ":peegeeq-db" "-Dtest=HaProxyConnectionFailoverTest"`.
Log: `logs\peegeeq-db-haproxy-failover-phase5-red-20261007.log`.

`Tests run: 6, Failures: 1, Errors: 0, Skipped: 0`. `BUILD FAILURE`. The failure is
`testFailbackAfterPrimaryRecovery`.

| Step in phase 5 | Result | Node that answered |
|---|---|---|
| Pool before the outage | Passed | Primary, `7693777672999833634` |
| Pool after the primary stopped | Passed | Secondary, `7693777684230651938` |
| New connection through HAProxy after the replacement primary started | Passed, about 2 seconds after the previous step | Replacement primary, `7693777790381756450` |
| Pool that served queries during the outage | **Failed at the 30,000 ms deadline** | Secondary, `7693777684230651938` |

Phase 6 passed.

What the run establishes: with `pgsql-check` and a `backup` server, HAProxy returns new
connections to a recovered primary and leaves established sessions on the backup. For at least
30 seconds one application held connections to two nodes. No error was raised.

What it does not establish: anything about a replicated pair, a role-based check, or a LISTEN
connection. It was one run.

The test is red in the working tree. See position `P-14` in §5.

---

## 5. Adopted Design Contracts and Deployment Gates

The user requested application of the review recommendations on 2026-10-07. These are the
resulting design contracts. They replace the former open-position table. They are design
decisions, not implementation completion or runtime evidence.

| ID | Adopted contract | Canonical design reference |
|---|---|---|
| P-0 | Automatic promotion belongs to dedicated proposed `peegeeq-pg-failover`. It is independent of federation. | §1, §5.1 |
| P-1 | No profile requires Patroni. | §4 |
| P-2 | Production HAProxy checks sidecar eligibility. Protocol checks are development only. | §5.3, §9 |
| P-3 | Confirmed independent fencing and restart inhibition precede replacement admission. Backend termination and pool close are insufficient. | §5.5 |
| P-4 | Consul loss or controller loss does not establish database failure. Reconcile before mutations. | §5.7 |
| P-5 | Writer ID names a database node. Session identifies the controller. Key, lock index, and session identify generation. | §1.1 |
| P-6 | Rewind or rebuild is operator-controlled under quarantine, followed by standby and WAL validation. | §5.5, §5.9 |
| P-7 | HAProxy uses scheduled observations and closes sessions when configured failures mark a backend down. Admission enforcement and fencing protect safety during delay. No fixed backup preference chooses a former writer. | §5.3 |
| P-8 | Every module uses the shared pooled access path and schema contract. | §6.1, §6.3 |
| P-9 | Maximum lifetime limits reuse at idle. Active operations have independent deadlines. Read-only errors require context. | §6.4, §6.7 |
| P-10 | Classify database availability failures. Business SQL, deliberate cancellation, and local saturation are distinct outcomes. | §6.5 |
| P-11 | LISTEN reconnects indefinitely with bounded jitter, checks eligibility, re-establishes channels, and catches up. | §6.6 |
| P-12 | Readiness checks eligibility, durability, and required catch-up. Liveness remains separate. | §7 |
| P-13 | Sidecar's request deadline covers acquisition and every eligibility dependency. | §9.1 |
| P-14 | Profile D remains a connection-recovery exercise. Move production convergence assertions to role-and-authority tests. Preserve the dated red evidence. | §4, §12 |
| P-15 | Effective endpoints apply consistently to setup service and every module. LISTEN can bypass transaction pooling. | §6.2, §8 |
| P-16 | Partition, multiple controllers, coordination quorum, and all S1 to S53 scenarios are acceptance obligations. | §10 |
| P-17 | At most one writer. Zero is permitted during recovery. | §1.1, R-1 |
| P-18 | Three-node production topology protects acknowledged commits and retains a synchronous peer after promotion. Two-node recovery waits for restored redundancy. | §5.8 |
| P-19 | Unknown mutation and commit outcomes require reconciliation. No automatic transaction retry or exactly-once claim. | §5.6, §5.8 |
| P-20 | Provider rejects obsolete generations at the node effect boundary. Controller-side checks alone are insufficient. | §5.1, §5.5 |
| P-21 | Cluster and incarnation namespace isolation protects independent deployments and coordination restoration. | §1.1, §5.7 |
| P-22 | Redundant proxies and tested stable-address transfer are production requirements. | §5.9 |
| P-23 | Replicated system identifier is cluster identity. Tests need independent node-local identity. | §12 |
| P-24 | Controller maintains ownership during normal failover. It does not expire its own session to trigger promotion. | §5.1, §5.6 |
| P-25 | Begin cluster generation, retire old effects, revoke, prepare, publish, and activate are explicit provider transitions. Matching open grant and current authority both precede eligibility. | §1.1, §5.6; Consul design §4 |
| P-26 | Generate individually quoted standby names. Every listed peer is required. Acknowledgement count is derived. | §5.8 |
| P-27 | Confirmed and pending policy intent govern repeated failover and standby re-join. A withdrawn quiescent cutover restores two-peer coverage. Pending peers are excluded. | §1.1, §5.8 |
| P-28 | Reuse tenant cursor, replay lease, short writer barrier, finite upper boundary, and one serialized scan. Delivery is stable append-ID order and at least once. | §6.6 |
| P-29 | Clients use authenticated read-only `/writer`, matching SQL node identity, and separate deadlines. Backoff resets after initialized channels and finite catch-up. | §6.2, §6.6, §9 |
| P-30 | Consul ACLs restrict access. Atomic session/revision conditions guard ownership-sensitive writes; ACLs do not impose lock ownership. | §1.1, §5.1; Consul design §3 |
| P-31 | Optional PgBouncer and its address owner receive the same redundancy and session-loss qualification as HAProxy. | §5.9, §8 |
| P-32 | G-7 records one automatic coordinator before phase 8. Consul is the reference protocol. Qraft requires explicit ownership, revision, read-consistency, generation, restoration, and security qualification before replacement. | §5.10 |
| P-33 | Production-manager commit enforcement precedes phase 7 application-write preservation assertions. Broader module migration remains phase 11. | §5.8, §6.3 |
| P-34 | Authenticated first-start bootstrap uses verified provisioning, closed admission, completed fences, guarded starts, confirmed initial policy, and prepare/publish/activate. Missing history is not bootstrap permission. | §1.1, §5.11 |
| P-35 | Native recovery requires one executed finite claim batch and its confirmed processing acknowledgements. Skipped/deferred work never establishes readiness. Delayed scheduling and final eligibility are observed. | §6.6 |

### 5.1 Required Deployment Selections

The design defines safety requirements. It does not invent a qualified deployment provider.

| Gate | Required artifact before implementation or release |
|---|---|
| G-1 Production node-control provider | Select platform and adapter before manual or automatic implementation. Bind the defined operations to real stop, restart inhibition, guarded initial-primary bootstrap start, cluster generation retirement, guarded node effects, grant persistence, inspection, independent access, and authentication. Qualify them before release. |
| G-2 Node admission integration | Select exact observation and enforcement bindings before either profile. Prove begin/revoke/prepare/activate, write quiescence, restricted standby start, and grant-to-authority matching. Test generation installation incomplete on one node and stale activation after revocation. |
| G-3 Stable production endpoints | Select and specify HAProxy SQL and HTTP endpoints and optional PgBouncer client endpoint before topology implementation. Prove ownership transfer, complete surviving routes, pooler/address-owner failure, active transactions, idle sessions, and LISTEN bypass. |
| G-4 Durability enforcement | Implement and verify the production-manager owned commit boundary in 7b.1 before transition assertions. Load quoted policy on real PostgreSQL. Prove weaker caller settings cannot produce acknowledged local-only writes, authenticated replication identity, confirmed/pending policy cutover, restored two-peer coverage, and repeated failover. Broader module migration remains a release gate in phase 11. Independent or privileged commits remain outside the API contract. |
| G-5 Replay and idempotency qualification | The selected existing algorithm and field contract are in design §6.6 and §9.4 below. Prove its writer barrier, lease fencing, finite completion, delayed low-ID commits, lost notifications, handler failure, and unknown cursor commits through failover. For native recovery, prove executed finite claims, capacity waiting, confirmed acknowledgements, delayed scheduling, and uncertain-outcome reconciliation. Do not claim commit-time ordering or exactly-once effects. |
| G-6 Runtime qualification | Pin deployed PostgreSQL, selected coordinator, HAProxy, PgBouncer, Vert.x, JVM, and native versions. Pin separate service runtimes independently. Qualify the recovery objective with specified backlog, handler latency, retry state, and timeout settings. No timeout sum or historical component count establishes an SLO. |
| G-7 Automatic coordinator selection | Record Consul retention or a replacement before phase 8. Specify atomic ownership/revision updates, ownership expiry, authoritative reads, generation ordering, namespace isolation, snapshots/restart/restore, credentials, and TLS. A Qraft selection requires its missing client coordination contracts and real fault evidence before PeeGeeQ integration. Replace Consul-specific contracts together; permit one backend per incarnation. |

Profiles A and B are not eligible for production release while applicable gates lack evidence.
Selected-coordinator evidence applies to A. Provider, durability, endpoint, and client evidence applies
to both. Documentation alignment does not waive a gate.

#### 5.1.1 Qraft Assessment and Decision Artifact

The Qraft option means the local Java project, not an unrelated public project with the same name.
The source assessment on 2026-10-07 read `../qraft/src/main/proto/distributed_state.proto` and
`DistributedStateGrpcService`. The reviewed API exposes unconditional Put/Delete requests and
Get/List read local state without a quorum-read step in those methods. It does not expose the
conditional ownership, revision, or lease fields required by the reference Consul protocol.
These are static observations of that API, not results of a Qraft runtime or full-source audit.

The reviewed POMs target Java 27 for Qraft and Java 25 for PeeGeeQ. Qraft's project standards
prohibit Vert.x in Qraft. If selected, use Qraft as an external service and define an asynchronous
PeeGeeQ adapter. Do not change either project's runtime standards to embed the other runtime.

G-7 must produce an operation-by-operation authority mapping and qualification evidence. Required
cases include simultaneous owners, obsolete leaders and minority partitions, ownership expiry,
stale reads, conflicting revisions, lost replies, restart/snapshot/restore, namespace isolation,
and unauthorised calls. Define how ownership generations reach the independent provider and
how sidecars observe authority. A Raft term alone is not the reference lock generation.
Retaining Consul also requires a recorded decision and its existing real-component acceptance.

No Qraft capability is marked implemented or qualified by this document. For Qraft work, read
its `docs/TESTING.md` and `docs/PROJECT_STANDARDS.md`, reuse their Maven commands in the visible
VS Code integrated terminal, and retain output through `Tee-Object`. Do not create verification
wrapper scripts. A replacement decision must revise the active Consul mappings before phase 8a.

## 6. TDD method

Every phase that changes Java follows these steps in order.

1. **Pre-work.** Complete the six mandatory steps in `AGENTS.md`. Read the coding principles
   and testing antipattern standard in full. Read every file the phase will
   modify, in full. Read the existing tests in the same module. Search each file for the banned
   patterns and record the count.
2. **Failure modes.** List the failure modes of each dependency the new code calls: a failed
   Future, a thrown exception, a null, a timeout, a connection that stays open and silent.
3. **Red.** Write one failing test per failure mode, and one for the normal path. Each test uses
   real containers. The test follows the pattern of the nearest existing test in the module.
4. **Prove red.** Rebuild with `mvn clean install -DskipTests -pl :<module> -am` through
   `Tee-Object`. Run the single test class with its profile. Read the saved log. Record the
   per-class `Tests run:` line and the failure message. A test that passes at this step is
   recorded as existing coverage, and no production change is made for it.
5. **Green.** Make the smallest production change that passes the test.
6. **Prove green.** Rebuild. Run the new class and the existing classes for the changed code.
   Record every per-class `Tests run:` line. `Tests run: 0` is a failure.
7. **Standards check.** Validate the changed files against
   `PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md`. Run the guard tests in `peegeeq-test-support`.
8. **Record.** Update Task 8 in the register with the counts. Update §2 to §4 of this plan.
   Report, and state what was not checked.

The test rules are in §12 of the design: identify the node, wait on an observable with a
deadline, assert the failure type, no mocks. In addition: one subject per test class, and every
endpoint is read from the live container.

---

## 7. Acceptance Coverage Obligations

The design §10 defines S1 to S53. No current runtime coverage audit was run in this documentation phase.
The historical seven-class baseline in §4 concerns the earlier design. Do not mark any revised
contract met from those counts.

| Scenario group | Implementation phase | Required evidence |
|---|---|---|
| S2 to S5, S6, S31, S32, S35 | 7: manual profile and re-join | Role, node identity, fencing, quarantine, data continuity, and replacement synchronous policy |
| S13, S25, S38, S40 | 7: durability | Acknowledged-write preservation, unknown commit outcomes, blocked writes without a synchronous peer, and target rejection |
| S1, S15 to S24, S30, S33, S34, S36, S37, S39 | 8: automatic authority and provider | Real control and node effects, generations, reconciliation, restart inhibition, and partition safety |
| S9, S14, S26 | 9 and 13: deadlines and connection failures | Specific failures, operation deadlines, connection discard, and observed cleanup |
| S7, S8, S27 | 10: LISTEN and durable replay | Unlimited reconnect, eligibility, acknowledged channels, stable append-ID catch-up after the writer barrier, and delivery identity |
| S11 | 11: shared pooled path and PgBouncer | Every module's configured schema and separate LISTEN endpoint |
| S12 | 12: readiness | Eligibility, durability, catch-up, specific failing checks, and recovery |
| S10, S28, S29 | 13: endpoint recovery | Redundant proxies, stable-address transfer, session recovery, and differing proxy observations |
| S41, S42 | 7b and 8a to 8c: provider admission | Every publication/activation/revocation interruption, incomplete node generation installation, and stale effects |
| S43, S44 | 7b and 8c: policy lifecycle | Loaded quoted syntax, effective policy, rebuilt-peer exclusion, restored two-peer coverage, and repeated failover |
| S45, S46, S47, S50 | 10 and 12: client recovery | Bounded retry, authenticated writer status, SQL identity matching, writer barrier, lease ownership, uncertain cursor commit, and finite readiness |
| S48 | 13: pooler and address recovery | Both pool modes, complete surviving paths, interrupted transactions, and LISTEN bypass |
| S49 | 8b: Consul conditions and credentials | Atomic rejection, unauthorised access, retained policy history, lost replies, and guarded release |
| S51 | 7b.2 and 8b/8c: first-start bootstrap | Verified provisioning, conditional initial intent, all-node fences, guarded starts, confirmed first policy, every interruption, and refusal against existing or ambiguous history |
| S52 | 10 and 12: native recovery | Executed bounded claim, capacity waiting, confirmed processing acknowledgements, delayed scheduling, continuous arrivals, uncertain outcomes, and no readiness from skipped or retired work |
| S53 | 7b.1 and 7b.3: application commit enforcement | Production manager rejects weaker durability outcomes before transition assertions; lost commit replies and acknowledged data are verified through eligible promotion |

Each dependency needs separate failed-Future, throw, null or malformed, timeout, lost-response,
and stale-completion cases where its interface admits that mode. Add class and method mappings
only after reading the relevant module's tests. Record fresh per-class counts and logs after
each implementation phase. A requirements table is not test evidence.

## 8. Phases

One phase at a time. Each Java or Maven phase rebuilds its affected reactor slice before
targeted verification. Report exact per-class counts and unverified paths. No phase advances
on zero executed tests or swallowed teardown failure.

| Phase | Work and completion condition |
|---|---|
| 1 to 4 | Earlier document review and baseline. Dated records remain in §4 and §9. They do not establish revised acceptance. |
| 5 | Finish the existing profile D test repair under P-14. Inspect the current source and rerun it. Move production convergence to phase 7 without deleting the historical red result. |
| 6 | This documentation phase. Align all five documents around §5. Validate links, obsolete contracts, prohibited patterns, and scenario consistency. No Java or Maven change. |
| 7a | Select and specify G-1/G-2 for both profiles and G-3 topology before their code. Specify guarded bootstrap and provisioning evidence. Record the G-7 coordinator decision before any phase 8 implementation. Map each provider operation and observation to the deployment platform. Record credentials, durable storage, generation ordering, admission enforcement, and failure domains. No provider qualification is inferred from a local Docker adapter. |
| 7b.1 | Implement the minimum production-manager owned commit-policy enforcement and bounded failure observation needed by G-4. Test weaker caller session/transaction settings, missing required peers, rollback, and lost commit replies using real synchronous PostgreSQL through the stable SQL endpoint. Rebuild and verify this sub-phase before bootstrap or transition assertions. Broad caller migration remains phase 11. |
| 7b.2 | Implement authenticated manual first-start bootstrap against the selected provider. Verify provisioning evidence, conditional initial intent, generation barrier, all-node stop/restart inhibition, guarded primary and standby start, initial policy confirmation, and prepare/publish/activate. Interrupt and reconcile every step; reject existing or ambiguous history. Use the verified 7b.1 manager path for committed-write evidence. |
| 7b.3 | Implement manual profile B transitions against the selected boundary. Use real synchronous nodes, sidecars, proxies, and provider effects. Prove revoke/prepare/publish/activate, quiescence, old-writer exclusion, quoted policy, repeated failover during re-join, surviving peer, two-node unavailability, restricted standby start, rewind failure/rebuild, and standby validation. Assert acknowledged-write preservation through the verified production manager. Address relevant historical sidecar and HAProxy findings. |
| 8a | G-7 must be recorded and any replacement contracts/qualification completed first. Extend the selected provider for the chosen automatic ownership generation; Consul is the reference mapping. Test cluster-wide retirement, incomplete node guard installation, effect races, uncertain actions, grant restart persistence, and stale activation rejection. |
| 8b | Implement cluster-scoped authority and controller reconciliation in proposed `peegeeq-pg-failover` against the G-7 backend; the reference protocol uses Consul. Add automatic bootstrap with conditional creation and preserved interrupted intent. Prove healthy-writer controller takeover, quorum loss, invalid records, namespace isolation, and restoration. |
| 8c | Implement guarded automatic transition and uncertain-outcome reconciliation. Prove stale commands, two controllers, restart at each state, missing fences, and partitioned old-writer exclusion. |
| 8d | Verify automatic database transition safety with real node control, three PostgreSQL nodes, selected coordination quorum, proxies, and confirmed policies. Use the verified manager commit path. Full client recovery and timing qualification wait for phases 10 to 14. Local evidence does not qualify the production adapter. |
| 9 | Complete operation and statement deadlines, millisecond precision, lifetime at idle, context-aware discard, and observed cleanup. Retain the verified phase 7 commit boundary and its required bounded failure observation. Add loader, defaults, property-contract, and guide changes for proposed keys. |
| 10 | Implement authenticated `/writer` observation, SQL node identity, separate LISTEN deadlines, initialized-success backoff reset, and shutdown. Reuse the stable writer-barrier replay and lease contract. Implement finite native recovery with executed/skipped/deferred distinctions, capacity waiting, confirmed acknowledgements, and delayed wake-ups. Verify continuous arrivals, uncertain mutations, and retired completions. No second progress mechanism. |
| 11 | Move each remaining module to the verified shared pooled access and classified availability breakers. Preserve phase 7 commit enforcement; do not first introduce it here. Verify schemas through PgBouncer transaction mode. One module per sub-phase. |
| 12 | Implement readiness for writer eligibility, durability, and required catch-up. Keep workload conditions separate from database availability. |
| 13 | Complete frozen-node, in-flight operation, redundant HAProxy/PgBouncer, SQL/HTTP address ownership, and differing-observation scenarios. Qualify the selected G-3 topology and remaining fault cases. |
| 14 | Build target stacks and runbook. Execute every scenario, including policy cutover/repeated failover, controlled membership/profile change, and restored coordination. Measure the recovery objective under specified backlog, handler latency, retry state, and deployed deadlines. Save platform-specific evidence. |
| 15 | Reassess every historical finding against code and fresh logs. Complete G-1 to G-7. Update task register and external documentation links. Close only when all revised requirements and S1 to S53 have matching evidence. |

Phase 7b.1 is a commit-enforcement component test. Use an isolated real synchronous fixture
with a fixed test-only proxy route to its known primary. It verifies the production manager
before the admission provider is implemented; it does not qualify profile A or B routing.
Bootstrap tests use separate newly provisioned fixtures. Phases 7b.2/7b.3 and 8 repeat application
write evidence through the full admitted stable endpoint. No component fixture closes an
admission, fencing, or whole-application recovery obligation.

The full production recovery objective is 45 seconds for the qualified three-node workload and
fault case. The deadline defaults do not prove it. Two-node synchronous promotion has no committed-write recovery target until a standby
is restored. Missing fencing preserves safety by refusing replacement admission.

This documentation phase does not mark implementation work complete. The task register and
documentation outside this folder need reconciliation in their own read-and-verify phase.

## 9. Records

### 9.1 What was read

Read on 2026-10-07 at commit `ff5c17da`. "Full" means every line.

| File | Lines | Read |
|---|---:|---|
| `docs-design/dev/pgq-coding-principles.md` | 665 | Full |
| `docs-design/testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md` | 1,155 | Full |
| `docs-design/testing/PEEGEEQ-TEST-COMMANDS.md` | 377 | Full |
| `peegeeq-db/.../connection/PgConnectionManager.java` | 675 | Full |
| `peegeeq-db/.../config/PeeGeeQConfiguration.java` | 798 | Full |
| `peegeeq-db/.../config/PgConnectionConfig.java`, `PgPoolConfig.java` | 368 | Full |
| `peegeeq-db/.../health/HealthCheckManager.java` | 766 | Full |
| `peegeeq-db/.../resilience/CircuitBreakerManager.java` | 230 | Full |
| `peegeeq-db/.../client/PgClientFactory.java`, `PgClient.java` | 463 | Full |
| `peegeeq-db/.../provider/PgConnectionProvider.java` | 174 | Full |
| `peegeeq-db/src/main/resources/peegeeq-default.properties` | 73 | Full |
| `peegeeq-db/.../PeeGeeQManager.java` | — | Part: constructor, L190–L287 |
| `peegeeq-db/.../provider/PgDatabaseService.java` | — | Part: L176–L211 |
| `peegeeq-bitemporal/.../ReactiveNotificationHandler.java` | 884 | Full |
| `peegeeq-bitemporal/.../PgBiTemporalEventStore.java` | 2,687 | Part: L170–L224, L1845–L2024 |
| `peegeeq-native/.../PgNativeQueueConsumer.java` | — | Part: L84–L113, L205–L391 |
| `peegeeq-native/.../VertxPoolAdapter.java` | — | Part: L90–L130 |
| `peegeeq-rest/.../PeeGeeQRestServer.java` | — | Part: the two health routes |
| `peegeeq-rest/.../handlers/HealthHandler.java` | — | Part: L55–L124 |
| `peegeeq-service-manager/.../ConnectionRouter.java`, `LoadBalancer.java`, `ConsulServiceDiscovery.java` | 836 | Full from L40 |
| `peegeeq-pg-sidecar/.../PgPrimaryCheckVerticle.java`, `PgPrimaryCheckMain.java` | 171 | Full |
| `peegeeq-pg-sidecar` test classes | 306 | Full |
| Four `peegeeq-db` resilience test classes | 1,642 | Full |
| `peegeeq-bitemporal/.../HaProxyNotificationFailoverIntegrationTest.java` | 327 | Full |
| Five HAProxy configurations, four init SQL files, `04-search-path.sql` | 135 | Full |
| Two Compose files under `scripts/local-infra/` | 228 | Full |
| `PEEGEEQ_FAILOVER_CONSUL_DESIGN.md` in this folder | 297 | Full |
| `PEEGEEQ_PG_SIDECAR.md` in this folder | 351 | Full |

Not read: the outbox producer and consumer, the native queue producer, the dead-letter and
recovery managers, the rest of `PgBiTemporalEventStore` and `PeeGeeQManager`, the sidecar
`pom.xml` beyond its identifiers, and the
other service-manager classes. Findings about those modules rest on searches. Each such finding
says so in its "Confirmed by" column.

### 9.2 Document history

- Until 2026-10-07 the design document was a description of the code at `f1c5d25d`, with status
  "CURRENT OPERATING REFERENCE". That version is in git at `ff5c17da`.
- Phases 1 and 3 corrected that description against the source, and a later edit added a
  section of status and gaps to it. Those edits were made in the working tree and never
  committed.
- On 2026-10-07 the owner directed that the design document describe the target system only,
  with no status and no gaps. It was rewritten on that basis. The status and gap content moved
  into §2 to §4 of this plan. The earlier checklists of document corrections (`HAP-`, `HAV-`)
  referred to the replaced text and are closed.
- The first rewrite named Patroni as the production path and placed the Consul failover monitor
  out of scope. That contradicted the purpose of the design, which is failover without Patroni.
  The review had read the headings of `PEEGEEQ_FAILOVER_CONSUL_DESIGN.md` and not its body. On
  the same day that document and `PEEGEEQ_PG_SIDECAR.md` were read in full, and the design was
  corrected: the sidecar, `PgPrimaryElector`, and `PgFailoverMonitor` are the failover mechanism,
  the monitor is in scope, and no profile uses Patroni. The failover sections of the design now
  restate the two source documents. Questions that reading raised are in §5.2, not in the design.
- On 2026-10-07 the owner directed that the related documents be held centrally in
  `docs-design/failover and resilience/`. Three files were moved there byte-for-byte:

  | File | Moved from | SHA-256 at the move |
  |---|---|---|
  | `PEEGEEQ_PG_SIDECAR.md` | `peegeeq-pg-sidecar/docs/` | `f588d45ba6961dca40da6ceafff4c41e8b25f13943c441e6e222dbe5759078f2` |
  | `PEEGEEQ_FAILOVER_CONSUL_DESIGN.md` | `peegeeq-service-manager/docs/` | `67204d59aaf92b5d61232bd99b1bc8b473de5ce23ee10171fd2cebdebb03244f` |
  | `PG_HAPROXY_PRIMARY_DETECTION_OPTIONS.md` | `docs-design/_archived/superseded-guides/` | `6c7167cb4c1d469ec65619122c14a26d3706d02435e8f014ac422410f39a7f0b` |

  The two module `docs` directories were empty afterwards and were removed. The moves were made
  on the file system and are not staged or committed. After the move, the two Overview links in
  the sidecar guide were corrected, which is the only content change to the three files. Links
  to the moved files were updated in the design document, the archived gaps record, and the
  source catalogue. The consolidation ledger entry for the detection-options document carries a
  "Current path" line. The archived consolidation checklist keeps the old path as a historical
  record. `PG_HAPROXY_PRIMARY_DETECTION_OPTIONS.md` was read in full on the same day.
- Documents that link to the design document expect the earlier content. Phase 15 updates them.

### 9.3 Safety-Contract Revision, 2026-10-07

The user requested application of the design review recommendations. All five documents were
read before revision. The coding principles and full testing antipattern standard were read.
No Java, Maven, or test file was edited in this phase.

The previous SQL-only fencing decision was wrong because terminating sessions permits new
connections and unreachable nodes can still serve applications. It was replaced with independent
confirmed fencing, durable restart inhibition, and generation enforcement at the node boundary.

The previous two-node immediate-write recovery contract was incompatible with synchronous
durability and no downgrade. Full production recovery now uses three nodes. Two-node deployment
keeps writes unavailable until a synchronous standby is restored.

The earlier sidecar and detection-option Java sketches discarded deployment and HTTP response
Futures. They were removed. The revised guides define eligibility and lifecycle contracts.

Sections 2 to 4 retain historical source and run evidence. No old count was promoted to current
coverage. The revised matrix defines 40 acceptance scenarios. Runtime behaviour, production
provider qualification, native packaging, and current test coverage remain unverified here.

Documentation verification is recorded in `logs/failover-design-validation-20261007.log`.
The scope is the five Markdown files in this folder. Check local file links and anchors,
balanced code fences, prohibited patterns, obsolete active contracts, and unique S1 to S40
identifiers. This is static document validation, not Java acceptance evidence.

### 9.4 Admission and Recovery Protocol Revision, 2026-10-07

The second review found seven unresolved design findings. This revision defines their
contracts across all five documents. It changes no Java, Maven, SQL resource, or deployment
configuration. Provider/platform selection and runtime qualification remain release gates.

| Finding | Design resolution | Required acceptance |
|---|---|---|
| Writer admission missing | Cluster generation barrier; revoke; prepare; publish; activate; grant-to-authority matching; interrupted-action reconciliation | S41, S42, plus S16/S19/S23/S24/S34 |
| Invalid standby-name syntax | Individually quote hyphenated names and verify loaded effective policy | S43 |
| Policy lifecycle incomplete | Confirmed/pending policy intent; quiescent cutover; rebuilt-peer exclusion; two-peer restoration; repeated-failure rules | S44, plus S25/S38/S39/S40 |
| Replay contract deferred | Reuse the existing writer-barrier, tenant cursor, and replay lease. Specify finite completion, ID order, notification overlap, and at-least-once outcomes | S27, S47, S50 |
| LISTEN initialization incomplete | Authenticated `/writer`, SQL node identity, separate deadlines, bounded jitter, backoff reset after finite catch-up, and retired-attempt rejection | S45, S46, plus S7/S8/S9/S12 |
| ACL ownership overstatement | ACL access isolation plus atomic session/revision transaction conditions | S49, plus S30/S37 |
| Optional pooler availability absent | Redundant PgBouncer and stable client address; complete surviving route; interrupted-transaction contract | S48, plus S10/S11/S28 |

The earlier decision to describe replay as commit-order delivery was wrong. The selected
source contract is stable append-ID order after a writer barrier. It does not promise
transaction commit-time ordering. The earlier unquoted synchronous names and ACL ownership
statement were also wrong. This revision replaces those active contracts.

The following artifacts were read in full for the replay decision. These are source
observations, not runtime results:

| Artifact | Source contract used |
|---|---|
| [DurableSubscriptionCoordinator](../../peegeeq-api/src/main/java/dev/mars/peegeeq/api/subscription/DurableSubscriptionCoordinator.java) | Existing cursor and lifecycle API |
| [DurableBiTemporalSubscriptionCoordinator](../../peegeeq-bitemporal/src/main/java/dev/mars/peegeeq/bitemporal/DurableBiTemporalSubscriptionCoordinator.java) | `stableMaximumId`, READ COMMITTED SHARE lock, sequence checks, finite paging, persisted cursor, and fenced lease |
| [DurableBiTemporalDelivery](../../peegeeq-bitemporal/src/main/java/dev/mars/peegeeq/bitemporal/DurableBiTemporalDelivery.java) | One scan per delivery instance; notifications and periodic reconciliation request the same replay |
| [DurableBiTemporalReplayIntegrationTest](../../peegeeq-bitemporal/src/test/java/dev/mars/peegeeq/bitemporal/DurableBiTemporalReplayIntegrationTest.java) | Existing test patterns for delayed lower-ID commit, replay/live overlap, owner takeover, handler failure, and restart; not rerun here |
| [Subscription schema](../../peegeeq-db/src/main/resources/db/templates/base/08f-bitemporal-subscriptions.sql) | Existing subscription identity, filters, cursor, and lease fields |
| [Event-store template](../../peegeeq-db/src/main/resources/db/templates/base/06-event-store-template.sql) | Existing row ID, event ID, and event data |

The current source read does not establish failover readiness integration, HTTP authority
observation, configured catch-up deadlines, or recovery after an uncertain cursor commit.
Those paths require the targeted acceptance cases above. No historical test count closes them.

Static validation for this revision is recorded in
`logs/failover-design-protocol-validation-20261007.log`. Validate all five files, local links
and anchors, complete S1 to S50 phase mapping, field sources of truth, prohibited patterns,
code fences, corrected configuration examples, and removal of superseded active contracts.
No runtime acceptance, provider qualification, native packaging, or recovery SLO is established
by that log. The task register and external documentation are unchanged by this phase.

### 9.5 Coordination, Bootstrap, and Recovery Prerequisites Revision, 2026-10-07

The review identified six remaining design findings. This documentation phase applies their
contracts across the five files. It changes no Java, Maven, SQL, deployment configuration,
task register, or Qraft source. Sections 2 to 4 remain historical evidence.

| Finding | Design correction | Required implementation evidence |
|---|---|---|
| Coordinator decision absent | G-7 records Consul retention or qualified replacement; Qraft assessment refers to the local Java API | Backend ownership, revision, fresh-read, expiry, restoration, and security qualification before phase 8 |
| Application durability implementation ordered too late | 7b.1 implements the production-manager commit boundary before bootstrap and transition assertions | S53 and G-4; preservation cases in 7b.3 and 8 |
| Native completion ambiguous | Executed finite claim, capacity waiting, confirmed acknowledgements, delayed scheduling, and retired-attempt rejection | S52 before native readiness in phase 12 |
| First-start bootstrap undefined | Verified provisioning, closed admission, guarded starts, first policy confirmation, interrupted-action reconciliation | S51 in 7b.2 and automatic ownership cases in 8 |
| Replay mapping still required commit order | Active coverage row uses stable append-ID order after the writer barrier | S27, S47, S50 |
| Routing requirements implied instantaneous freshness | R-2/R-3 and companion wording use scheduled observations and backend-down shutdown | S5, S15, S28, S29; measure delay independently from fencing |

S51 to S53 extend the active acceptance matrix. Earlier S1 to S40 and S1 to S50 references in
§9.3 and §9.4 describe their dated revisions. No historical count establishes current coverage.
Static document validation does not qualify a coordinator, node-control provider, native artifact,
or recovery objective. Those paths remain unverified until their implementation phases run.

Static verification for this revision is recorded in
`logs/failover-design-prerequisites-validation-20261007.log`. It covers five documents, 57 local
links and anchors, 53 unique scenario IDs and their phase mappings, seven deployment gates,
balanced code fences, prohibited patterns, obsolete active wording, and unchanged historical
baseline sections. No Java, Maven, or Qraft runtime test was run for this documentation phase.

