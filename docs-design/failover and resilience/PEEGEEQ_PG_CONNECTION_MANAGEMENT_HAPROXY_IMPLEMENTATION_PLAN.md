# PeeGeeQ Connection Management and HAProxy Failover — Implementation Plan

**Document revision**: 2026-10-22, bootstrap scope B decisions OD-9 to OD-12 (§5.3) and
increment B1 (§9.15). Revision 2026-10-21: renewal loop (§9.12), optional watchdog modes
(§9.13), and first-start bootstrap scope A (§9.14). Earlier revision 2026-10-09: local admission record,
Consul fixture readiness correction, and failure-logging correction (§9.11).
Sections 2 to 4 retain dated baseline evidence. Current implementation evidence is in §8.1.
The whole-repository `-Pall-tests` run of 2026-10-09 was red on the phase 5 test (§8.1). That
test was repaired on 2026-10-11 (§4.3). No Jenkins build from `beginning` has run it since.
The next implementation scope is in §8.2.
**Created**: 2026-10-07 at commit `ff5c17da`
**Controls**: Task 8 of the [consolidated task register](../tasks/tasks.md). Status and execution
order are recorded in the register, not here.
**Design**: [PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md](PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY.md),
design revision 2026-10-08, Patroni-style local supervision with a coordinator port and optional watchdog.

**Contract corrections, 2026-10-08:** watchdog support is optional (§5.2.1), and failover
depends on a coordinator port, not on Consul (P-38). The four design documents were aligned
with both on 2026-10-08 (§9.10). Machine-reset testing and additional host/VM access are not
prerequisites for the next implementation phase.

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
application writes under current node-owned lease and local admission. Failover must exclude the
former writer before a replacement serves, preserve acknowledged writes under the synchronous
policy, replace unusable pool and LISTEN connections, and complete required durable catch-up.
LISTEN is the database subscription mechanism for notification channels. Its notifications
request processing; durable database state determines the work still to deliver.

The intended production topology has one primary and two synchronous standbys, redundant
HAProxy and status endpoints, and optional redundant PgBouncer poolers. Sidecars observe
writer eligibility. Per-node `peegeeq-pg-failover` supervisors will coordinate writer ownership
through a coordinator port. Consul is the first adapter. Qraft is a planned second adapter. Local lease-loss shutdown is the normal exclusion mechanism. Watchdog support
provides optional independent protection when the supervisor cannot execute shutdown.
The lease belongs to that node. Safe handover must establish old-writer exclusion.
The qualified expiry path requires no remote stop reply from an unreachable host.

**How the execution order is organised.** Manual profile B uses operator-directed transitions
through the same local supervisor, lease, admission, and configured watchdog mode as A. Automatic A
adds autonomous takeover initiation. Both modes use the same coordinator adapter. G-7 qualifies an adapter
and its safety claims incrementally alongside the supervisor implementation.
The common architecture supports Linux hosts/VMs,
Docker hosts, and Kubernetes. Deployment bindings are qualified before production support. Phase 7 first
implements production-manager commit enforcement, then bootstrap and manual transitions.
Manual transition and re-join tests precede automatic transition work.
Later phases complete operation deadlines, migration to shared pooled access, LISTEN recovery, readiness,
and endpoint failure handling. Full recovery qualification follows those integrations.

For example, a test that promotes a standby and runs a query does not finish the work for a
primary-crash scenario. Its evidence must also establish the former writer's fence, surviving
acknowledged data, replacement synchronous coverage, application connection recovery, and
required subscription catch-up. Local Docker process tests do not prove watchdog or whole-VM pause safety.
Production qualification requires real enforcement evidence for every claimed environment.
The 45-second recovery objective is a qualified workload target, not a result inferred from
configuration defaults or an earlier component test.

**How to interpret the records.** Requirement identifiers `R-` describe system obligations.
Scenario identifiers `S1` to `S59` describe acceptance cases in the system design. The plan's
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
for cluster identity and membership. The coordinator's control record stores transition intent and ownership metadata.
Each local supervisor stores action receipts, quarantine, and local execution grants. The writer's
supervisor owns the coordinator lease. Lease deadlines and watchdog health are derived, never stored
as reusable authority. The central provider and all-node generation barrier are removed. Profile authority stores confirmed and pending
durability policy intent. Counts, role, peer health, replay boundary, and readiness are computed.
Bitemporal replay reuses the existing tenant cursor and lease with the stable writer barrier.
No parallel cursor, acknowledgement ledger, or generic idempotency-result table is introduced.

In scope:

- The single endpoint, sidecar eligibility, fencing, synchronous durability, and safe re-join.
- Shared pooled access, operation deadlines, error classification, LISTEN recovery, and health.
- The existing `peegeeq-pg-failover` module, a planned supervisor per node, local process control,
  and optional independent watchdog support.
- Real-component acceptance for S1 to S59, local infrastructure, and a scenario runbook.
- Configuration loader, defaults, property contract tests, and configuration guide updates
  when proposed keys are implemented.

Out of scope:

- PeeGeeQ federation and application-instance routing in `peegeeq-service-manager`.
- Patroni as an implementation requirement.
- An implicit exactly-once guarantee for external side effects or consumer delivery.
- Treating a local Docker adapter as qualification of production watchdog enforcement.

Each revision updates this implementation plan and Task 8 from source and saved logs. Section 9
records what each revision read, changed, and ran.

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

### 4.3 Phase 5 repair of the profile D test, 2026-10-11

The red result of §4.2 stands as dated evidence. It was seen again on 2026-10-09 (§9.11) and on
2026-10-11 in Jenkins build 30 at `f318b68d`: `peegeeq-db` ran 1,124 tests with that 1 failure.

Under `P-14` the last step of `testFailbackAfterPrimaryRecovery` was removed: the wait for the
pool that served queries during the outage to reach the replacement primary. The test still
proves, by system identifier, that the pool reaches the secondary after the primary stops and
that a new connection through HAProxy reaches the replacement primary. No production file and
no HAProxy configuration was changed.

Why the removed step could not pass: that step polled the pool every 250 ms, so its connection
was never idle and the idle timeout never applied; `PgPoolConfig` has no maximum lifetime
(`IMP-15`); the configuration does not shut down backup sessions (`IMP-06`); and both nodes are
independent and writable, so no query is rejected.

What the repaired test no longer determines: where the pool points after the replacement
primary starts. The test stops querying the pool once it has reached the secondary. From then
on the pool's connection is idle, and the configured idle timeout is 30 seconds.

Rebuild: `mvn clean install -DskipTests -pl :peegeeq-db -am`, `BUILD SUCCESS`.
Log: `logs\rebuild-task8-phase5-repair-20261011.log`. The test's description was corrected
afterwards in three edits, comment text only, and the slice was rebuilt after each:
`logs\rebuild-task8-phase5-repair-comment-20261011.log`,
`logs\rebuild-task8-phase5-repair-comment2-20261011.log`, and
`logs\rebuild-task8-phase5-repair-comment3-20261011.log`, all `BUILD SUCCESS`. The test class
was last run by the reviewer after the first comment edit (6 tests, 0 failures) and was not
rerun after the second or third.

Test: `mvn test -Pintegration-tests -pl :peegeeq-db "-Dtest=HaProxyConnectionFailoverTest"`.
`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`.
Log: `logs\peegeeq-db-haproxy-failover-phase5-repair-20261011.log`. In that run 20.623 seconds
passed between the last answer from the primary and the first answer from the secondary. That
interval includes the container stop; the log has no line for the moment the stop completed.
3.020 seconds passed between that answer from the secondary and the answer from the
replacement primary on a new connection.

Still owed, and not covered by any test now: after a role change every connection PeeGeeQ
holds ends on the one primary (design §2 principle 3). It depends on proxy session shutdown
(`R-3`) and on connection discard and maximum lifetime (`R-5`). That assertion must be made by
future role-and-authority tests. Removing the step did not make the requirement met.

Phase 5 stays open until a Jenkins build from `beginning` passes `peegeeq-db`.

Not run in this repair: the whole `peegeeq-db` module, and a build from `beginning` on Jenkins.

---

## 5. Adopted Design Contracts and Deployment Gates

The user requested application of the review recommendations on 2026-10-07. These are the
resulting design contracts. They replace the former open-position table. They are design
decisions, not implementation completion or runtime evidence.

| ID | Adopted contract | Canonical design reference |
|---|---|---|
| P-0 | Local supervision belongs to the existing `peegeeq-pg-failover` module, one planned process per database node. It is independent of federation. | §1, §5.1 |
| P-1 | Implement Patroni's node-owned lease and local-demotion approach, with optional watchdog support, without requiring the Patroni product. | §4; plan §5.2.1 |
| P-2 | Production HAProxy checks sidecar eligibility. Protocol checks are development only. | §5.3, §9 |
| P-3 | Local self-demotion/watchdog excludes the writer before safe lease handover. Qualified expiry requires no failed-host stop acknowledgement. Voluntary release requires confirmed local stop. | §5.5 |
| P-4 | Route-only failure does not establish database failure. An active watchdog provides independent protection on writer-supervisor failure. Without it, supervisor-death exclusion is not established by the lease alone. A sidecar restart does not transfer ownership. | §5.7; plan §5.2.1 |
| P-5 | Writer ID names a database node. The lease belongs to its local supervisor. Control record, generation, and lease holder identify a writer generation. | §1.1, §5.10 |
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
| P-16 | Infrastructure partitions, supervisor/VM pauses, coordination quorum, and S1 to S58 are acceptance obligations. | §10 |
| P-17 | At most one writer. Zero is permitted during recovery. | §1.1, R-1 |
| P-18 | Three-node production topology protects acknowledged commits and retains a synchronous peer after promotion. Two-node recovery waits for restored redundancy. | §5.8 |
| P-19 | Unknown mutation and commit outcomes require reconciliation. No automatic transaction retry or exactly-once claim. | §5.6, §5.8 |
| P-20 | Local supervisor serialises node effects with lease-loss shutdown and configured optional watchdog protection. Controller-side checks or session cleanup alone are insufficient. | §5.1, §5.5; plan §5.2.1 |
| P-21 | Cluster and incarnation namespace isolation protects independent deployments and coordination restoration. | §1.1, §5.7 |
| P-22 | Redundant proxies and tested stable-address transfer are production requirements. | §5.9 |
| P-23 | Replicated system identifier is cluster identity. Tests need independent node-local identity. | §12 |
| P-24 | The writer's local supervisor alone renews its lease. Standbys and remote controllers cannot retain ownership for an unreachable writer. | §5.1, §5.6 |
| P-25 | Winner acquires safe ownership with withdrawn intent, applies the configured watchdog mode, and reconciles its local effects. Local prepare/publish/activate remains recoverable. Remove central generation installation and provider-owned manual authority. | §1.1, §5.6; Consul design §4; plan §5.2.1 |
| P-26 | Generate individually quoted standby names. Every listed peer is required. Acknowledgement count is derived. | §5.8 |
| P-27 | Confirmed and pending policy intent govern repeated failover and standby re-join. A withdrawn quiescent cutover restores two-peer coverage. Pending peers are excluded. | §1.1, §5.8 |
| P-28 | Reuse tenant cursor, replay lease, short writer barrier, finite upper boundary, and one serialized scan. Delivery is stable append-ID order and at least once. | §6.6 |
| P-29 | Clients use authenticated read-only `/writer`, matching SQL node identity, and separate deadlines. Backoff resets after initialized channels and finite catch-up. | §6.2, §6.6, §9 |
| P-30 | Consul ACLs restrict access. Atomic session/revision conditions guard ownership-sensitive writes; ACLs do not impose lock ownership. | §1.1, §5.1; Consul design §3 |
| P-31 | Optional PgBouncer and its address owner receive the same redundancy and session-loss qualification as HAProxy. | §5.9, §8 |
| P-32 | Consul is the first coordinator adapter for A/B supervision. G-7 qualifies an adapter: TTL-only ownership, conditional revisions, authoritative reads, safe release, restore, and security. Qraft is a second adapter behind the same port, qualified by the same contract suite. | §5.10 |
| P-33 | Production-manager commit enforcement precedes phase 7 application-write preservation assertions. Broader module migration remains phase 11. | §5.8, §6.3 |
| P-34 | Authenticated bootstrap requires verified provisioning, initial node-owned lease, configured watchdog-mode checks, closed admission, guarded start, and confirmed initial policy. Missing history is not bootstrap permission. | §1.1, §5.11; plan §5.2.1 |
| P-35 | Native recovery requires one executed finite claim batch and its confirmed processing acknowledgements. Skipped/deferred work never establishes readiness. Delayed scheduling and final eligibility are observed. | §6.6 |
| P-36 | Same containerised local-supervisor protocol on Linux hosts/VMs, Docker hosts, and Kubernetes. Runtime enforcement is qualified per environment before support is claimed. | §5.2, §12 |
| P-37 | Watchdog modes are planned as `automatic` (default), `off`, and `required`. Only `required` refuses writer start/promotion because the watchdog is unavailable. Qualify independent exclusion before claiming supervisor-death or whole-VM pause safety. Device/reset tests are conditional qualification work, not a prerequisite for implementing local supervision. | Plan §5.2.1; S54–S58 |
| P-38 | Database failover has no hard dependency on Consul. The supervisor, the sidecar, and the elector use a coordinator port, `PgLeaseCoordinator`, with neutral terms: control record, lease, lease holder, generation, revision, and intent. Consul is one adapter, `ConsulLeaseCoordinator`. A Qraft adapter is a separate module. One adapter serves an incarnation. Every adapter passes one shared contract suite against its real service. Directed by the owner on 2026-10-08, because Qraft may be used in place of Consul. | §3 R-20, §5.4, §5.10, S59; coordinator design §3 |

### 5.1 Required Deployment Selections

Phase 7a selects the common architecture. These gates qualify its implementation and deployment.

| Gate | Required artifact before implementation or release |
|---|---|
| G-1 Local supervision and optional watchdog | Implement the same node-local PostgreSQL lifecycle on all three environments. Bind writer start/promotion to own lease, admission, and the selected watchdog mode. Docker tests qualify local shutdown and restart quarantine. Actual device/reset and whole-VM tests qualify independent protection only when claimed. Without that evidence, freeze and orphan-writer safety remain unqualified. No failed-host stop reply is required by the qualified expiry path. |
| G-2 Local admission and observations | Implement local SQL/LISTEN admission, quiescence, grants/receipts, and sidecar read-only status. Prove withdrawn intent before effects, safe lease preparation, selected watchdog-mode checks, prepare/publish/activate, revocation, local reconciliation, and late-effect exclusion. Loaded grants start closed. |
| G-3 Stable production endpoints | Use configurable stable SQL/HTTP addresses through redundant proxies; optional redundant poolers. Bind the deployed address owner/load balancer before deployment. Qualify surviving routes, address loss, active transactions, idle sessions, and LISTEN bypass. No platform-specific address choice blocks common implementation. |
| G-4 Durability enforcement | Implement and verify the production-manager owned commit boundary in 7b.1 before preservation assertions. Prove quoted policy, weaker caller settings, authenticated peers, confirmed/pending policy cutover, restored coverage, and repeated failover. Full migration remains phase 11. |
| G-5 Replay and idempotency | Qualify the existing cursor/lease/writer-barrier finite replay and native finite claim/acknowledgement contract. Cover delayed low IDs, handler failure, unknown cursor/acknowledgement commits, capacity, scheduling, and stale completions. No second progress mechanism. |
| G-6 Runtime and timing | Pin separate service/artifact versions. Measure application recovery under declared workload, deadlines, and failure cases. Defaults and historical counts do not prove an SLO. |
| G-7 Coordinator qualification | An adapter is qualified by the shared coordinator contract suite (S59) against its real service. Consul is the first adapter; its protocol slice has recorded component evidence in §8.1. Complete stopped-before-release, restore/incarnation, TLS, supervisor integration, and expiry versus old-writer exclusion alongside the remaining implementation. These are qualification obligations, not a requirement to obtain machine-reset infrastructure before coding. A Qraft adapter requires Qraft to provide the port's operations first (§5.1.1). |

No production gate is closed by a document edit. G-1/G-2/G-7 apply to B as well as A because both
use the same continuously enforced writer lease. B's only difference is operator initiation.

#### 5.1.1 Qraft Assessment and Decision Artifact

The Qraft option means the local Java project, not an unrelated public project with the same name.
The source assessment on 2026-10-07 read `../qraft/src/main/proto/distributed_state.proto` and
`DistributedStateGrpcService`. The reviewed API exposes unconditional Put/Delete requests and
Get/List read local state without a quorum-read step in those methods. It does not expose the
conditional ownership, revision, or lease fields required by the coordinator port (P-38).
These are static observations of that API, not results of a Qraft runtime or full-source audit.

The reviewed POMs target Java 27 for Qraft and Java 25 for PeeGeeQ. Qraft's project standards
prohibit Vert.x in Qraft. If selected, use Qraft as an external service and define an asynchronous
PeeGeeQ adapter. Do not change either project's runtime standards to embed the other runtime.

G-7 must produce an operation-by-operation authority mapping and qualification evidence. Required
cases include simultaneous owners, obsolete leaders and minority partitions, ownership expiry,
stale reads, conflicting revisions, lost replies, restart/snapshot/restore, namespace isolation,
and unauthorised calls. Define how ownership generations reach local supervision and watchdog enforcement and
how sidecars observe authority. A Raft term alone is not the reference lock generation.
Retaining Consul also requires a recorded decision and its existing real-component acceptance.

No Qraft capability is marked implemented or qualified by this document. For Qraft work, read
its `docs/TESTING.md` and `docs/PROJECT_STANDARDS.md`, reuse their Maven commands in the visible
VS Code integrated terminal, and retain output through `Tee-Object`. Do not create verification
wrapper scripts. A Qraft adapter implements the coordinator port in its own module. No other
component changes when it is selected.

### 5.2 Phase 7a Architecture Selection, 2026-10-08

The canonical data model is system design §1.1. Phase 7a selects Patroni-style node-local
supervision. Linux hosts/VMs, Docker hosts, and Kubernetes are all deployment targets.
No selection of one hosting platform is required to finish this design phase.

| Selected input | Source of truth or derivable | Contract |
|---|---|---|
| Cluster/incarnation/membership/node identities | Authoritative configuration | Fixed namespace and one supervisor per node. Replica system identifier is not node identity. |
| Writer lease and intent | Authoritative coordinator metadata/value | Owned and renewed by writer's supervisor; same protocol in A/B. |
| Local receipts, quarantine, grants | Authoritative persistent node storage | No central store. On restart load grants closed and reconcile local effects. |
| Lease TTL, loop/retry/stop budgets, watchdog device/mode | Authoritative configuration | Separate local-demotion timing from optional watchdog timing. The modes and their safety limits are below. Their implementation is recorded in §8.1. |
| Lease freshness, watchdog health, roles, WAL, eligibility | Derived observations | No persisted lease expiry or watchdog-ready permission. |
| SQL/LISTEN/status/local control endpoints | Authoritative configuration; mapped test addresses derived from fixtures | Redundant stable routing; separate credentials; no platform API in takeover. |

#### 5.2.1 Common Runtime Selection

Use one PeeGeeQ supervisor per PostgreSQL node. Its entry point owns PostgreSQL process startup,
local admission, lease renewal, promotion, shutdown, and re-join. The sidecar remains read-only.
The supervisor renews ownership independently of SQL probes and slow actions. Do not let another
controller renew its lease. Do not start PostgreSQL through a second orchestrator entry point.

Use Linux local process control. Lease loss withdraws admission and stops local PostgreSQL
within the verified shutdown budget. Implement and test this path with the available Docker
workflow. It does not require a watchdog device or another Linux host/VM.

Optional watchdog support follows Patroni's modes:

| Mode | Contract |
|---|---|
| `automatic` (default) | Use the watchdog when available. Report absence or activation failure. Continue through verified lease and local-demotion checks without claiming independent watchdog protection. |
| `off` | Do not open or activate a watchdog. Retain lease-loss shutdown, guarded startup, and admission checks. |
| `required` | Refuse writer startup/promotion when the watchdog cannot be activated or its timing is unsafe. |

Mode and device configuration are authoritative. Watchdog activation and health are derived.
No persisted watchdog-ready flag grants permission after restart. Device errors remain visible.
The modes are implemented in `PgFailoverMonitor` with the `PgDeviceWatchdog` adapter (OD-5,
§8.1). No test has armed a real device.
See [Patroni's watchdog documentation](https://patroni.readthedocs.io/en/latest/watchdog.html)
and [the pinned v4.1.5 mode implementation](https://github.com/patroni/patroni/blob/v4.1.5/patroni/watchdog/base.py).

Optional support does not remove old-writer exclusion from takeover. Local shutdown cannot
prove exclusion while its supervisor is dead or the whole VM is paused. Coordinator lease expiry
alone does not stop PostgreSQL. Record these cases as unqualified until independent exclusion
is demonstrated. A paused guest's own timer is not sufficient evidence. Implementing the
common runtime proceeds before this production qualification. The protocol does not call
Docker, Kubernetes, or hypervisor remote stop APIs.

Use local persistent node storage for quarantine, grants, and action receipts. The coordinator's control record owns
transition and durability intent. Do not create a separate central generation/admission service.
Local grants and admission observe current ownership and the selected watchdog-mode checks. First-start provisioning
requires verified node state; ordinary unreachable-host takeover does not require all-node replies.

#### 5.2.2 Local Acceptance Fixture Selection

Use the existing Docker/Testcontainers workflow.
Reuse [PostgreSQLTestConstants](../../peegeeq-test-support/src/main/java/dev/mars/peegeeq/test/PostgreSQLTestConstants.java)
factories/image constant and the production schema initializer.
The [streaming-replication test](../../peegeeq-db/src/test/java/dev/mars/peegeeq/db/resilience/HaProxyStreamingReplicationFailoverTest.java)
provides source patterns. Its exit-code-only readiness query and direct container construction
must not be copied. Assert real authenticated replication rows and fresh flush/replay.

7b.1 uses an isolated primary plus two physical standbys and a fixed test-only HAProxy route to
qualify the production manager's commit boundary. Weaker policy, missing peers, rollback, and
lost replies must have observable assertions. This fixture does not qualify writer admission.

7b.2 has implemented the coordinator port, the Consul adapter, local supervision and admission,
the renewal loop, the optional watchdog modes, and bootstrap scope A (§8.1). Bootstrap scope B
uses a separate fresh fixture of one primary and two standbys (OD-8). 7b.3 verifies
manual takeover through the full admitted route. Persist local node storage across restarts.
Destroying a test container is teardown, not restart-inhibition proof.

Docker/Testcontainers supplies the next implementation fixtures. No machine reset or additional
host/VM access is a prerequisite. If watchdog device support is qualified later, its reset tests
need an isolated environment for the actual reset scope. Container process tests do not prove
whole-VM or independent watchdog exclusion.

#### 5.2.3 Deployment Bindings Before Production Qualification

| Target | Same runtime contract | Binding to qualify |
|---|---|---|
| Linux host/VM | Supervisor owns local PostgreSQL, lease, and admission | Process privileges, persistent storage, restart control; optional watchdog device and pause/resume exclusion if claimed |
| Docker host | Same supervisor image/entry point | Storage, process privileges, restart through supervisor; optional device access and ownership if enabled |
| Kubernetes | Same supervisor image/entry point | Storage, scheduling/failure domains, lifecycle/startup; optional node/device policy and ownership if enabled |

When watchdog support is enabled, a watchdog resetting a host affects every colocated workload. Define exclusive device ownership
and the actual reset scope. Do not assume several database containers can independently own one
host watchdog. Verify VM resume behaviour against the actual facility before support is declared.

Stable endpoints are configured addresses through redundant proxies and optional poolers. Bind
the deployed load balancer/address owner and credentials before qualification. No specific
orchestrator service is a design dependency. G-1/G-2/G-3/G-7 remain implementation/qualification
gates. Phase 7a closes architecture selection only; it does not qualify production safety.

### 5.3 Open Design Decisions for Phase 7b.2

A full read of all five documents on 2026-10-08 found three points the design requires and
does not specify. Each is the owner's decision. The search covered every document in this
folder. Later scopes found five more, OD-4 to OD-8. All eight are decided below: OD-1 to OD-3
by the implementer, not reviewed by the owner; OD-4 to OD-8 by the owner.

| ID | What the design says | What is not specified | Blocks |
|---|---|---|---|
| OD-1 Local admission mechanism | A local admission gate covers every application SQL and LISTEN path, sits between HAProxy and PostgreSQL, blocks new work when closed, and observes quiescence of existing writes (system design §5.1, §5.2) | What the gate is. The documents name no mechanism: not `pg_hba.conf` management, not a local proxy, not a listen-address or firewall control. `pg_hba` appears twice in the set, both times about the sidecar role | §8.2 item 5 |
| OD-2 Local process control | The supervisor owns PostgreSQL start, stop, promotion, and rewind admission through local process control, and no second entry point can start a writable PostgreSQL (system design §5.1; coordinator design §2) | The commands used, the container entry point, and how the supervisor and PostgreSQL share a container or host | §8.2 item 4 |
| OD-3 Node-local storage | Grants, action receipts, and quarantine are durable node-local supervisor storage, with the fields in system design §1.1 | Location, format, and durability mechanism | §8.2 item 3 |

**OD-3 decided for implementation, 2026-10-08.** The owner instructed that all §8.2 steps
proceed without stopping. The implementer chose the mechanism; the owner has not reviewed it and
can reverse it. The choice is confined to `PgLocalStateStore`.

- Location: one existing directory on the node's persistent storage, given to the supervisor as
  configuration. The store does not create it. One supervisor process writes it.
- Format: one JSON file per record. `grant.json`, `quarantine.json`, and
  `receipts/<generation>+<operationId>+<action>+<targetNodeId>.json`. Unknown fields, unknown
  enum values, a record for another node, and a receipt whose content differs from its file
  name are rejected.
- Durability: write a temporary file opened for synchronous writes, flush, close, then replace
  the record by an atomic move. The parent directory is not synced; Vert.x offers no call for it.
- Nothing is cached. Every operation reads the files. Operations run one at a time in
  submission order.

**OD-2 decided for implementation, 2026-10-08.** Same basis as OD-3: the implementer's choice
under the owner's instruction to proceed, not reviewed by the owner, reversible. The choice is
confined to `PgCtlProcessControl` and `LocalCommandRunner`, behind the `PgProcessControl` port.

- Commands: PostgreSQL's own `pg_ctl status`, `start`, and `stop`, run as child processes of the
  supervisor against the local data directory. A configured command prefix runs them as the
  PostgreSQL operating-system user. `touch` and `rm` manage the inhibitor file.
- Co-location: the supervisor and PostgreSQL share one host or container. The container entry
  point is the supervisor. The image entry point does not start PostgreSQL.
- Start inhibitor: `standby.signal` in the data directory. While it exists, a start by any entry
  point comes up in recovery and accepts no writes. `stop` creates it before stopping. Only the
  guarded primary start removes it. An experiment against `postgres:15.13-alpine3.20` on
  2026-10-08 showed: `pg_ctl status` exits 0 when running and 3 when stopped; a fast stop ends
  an existing session; with the file present a direct `pg_ctl start` gives
  `pg_is_in_recovery() = t` and rejects `CREATE TABLE`; `pg_ctl promote` removes the file.
- Stop: fast stop for half the remaining budget, then immediate stop for the rest. The exit code
  of the stop command is not the evidence; the following `pg_ctl status` observation is.
- Not decided: promotion, rewind, and rebuild commands. They belong to 7b.3.

**OD-1 decided for implementation, 2026-10-08.** Same basis as OD-3 and OD-2: the implementer's
choice under the owner's instruction to proceed, not reviewed by the owner, reversible. This
entry was written on 2026-10-09 from the source and from the message of commit `ea045d7f`. The
choice is confined to `PgHbaAdmissionGate`, behind the `PgAdmissionGate` port.

- Mechanism: PostgreSQL host-based authentication. The deployment provisions two files. One
  admits application connections and one rejects them. Both keep the local socket for the
  supervisor and keep the replication rules. The gate copies one of them over `pg_hba.conf`,
  compares the result with the source, and runs `pg_ctl reload`.
- Reload evidence: the value of `pg_conf_load_time()` changes, and `pg_hba_file_rules` then has
  no row with an error.
- Close: installs the closed file and reloads. A reload blocks new connections only, so the gate
  also ends every client backend that has a client address and polls `pg_stat_activity` until
  none remains. All of it runs inside one budget. With PostgreSQL stopped, the gate installs the
  closed file for the next start and does nothing else.
- Open: requires PostgreSQL to be running. Installs the open file and reloads.
- Scope: connections over the network. A connection through the local socket is not gated.
- The gate is not the takeover fence. Writer exclusion is the local stop.
- Not decided: the content of the two files for a deployment, and how they are provisioned.

**OD-4 decided by the owner, 2026-10-21: ownership operations queue.** Before this decision
`PgPrimaryElector` refused `renew`, `update`, and `release` while another of them was in flight.
With a renewal timer, a tick during an update was refused, and `PgFailoverMonitor.renew()`
demoted the writer on that refusal; an update during a renewal was refused as well. The owner
chose queueing over a separate busy signal and over pausing the timer around updates.

- `renew`, `update`, and `release` run one at a time in arrival order. A waiting operation is
  checked against the ownership state when it starts, not when it arrives.
- The request deadline and the freshness deadline of a waiting renewal are measured from the
  start of its request. A reply after either deadline is still rejected.
- An operation queued behind one that failed meets the retired state and is refused.
- Acquisitions keep their refusal rule: they are the first operation of an instance.

**OD-5 decided by the owner, 2026-10-21: watchdog device mechanism and its verification.**
Linux sets a watchdog timeout with an `ioctl`, which plain Java cannot call. The owner chose a
plain-Java adapter over Java FFM `ioctl` calls and over a port without a device adapter, and chose
to defer real-device tests to a Linux host.

- `PgDeviceWatchdog` opens the device to arm it, writes one byte other than `V` per keepalive,
  and writes `V` before closing to disarm it (magic close). It never sets the timeout. The
  deployment sets it.
- Before opening the device the adapter reads the actual timeout from the device's sysfs
  `timeout` file. A device whose timeout cannot be read is never armed.
- The monitor accepts the timeout only when it is at most half the lease TTL, longer than one
  loop-and-retry cycle, and longer than the stop budget. Otherwise it disarms the watchdog and
  treats it as unavailable.
- Device activation, keepalive, magic close, `nowayout` behaviour, and the timeout check
  against a real device run later on a Linux host with `softdog soft_noboot=1`. The development
  machine's Docker kernel (WSL2 6.18) builds softdog as a module that is not loaded, and has no
  `/dev/watchdog` (`logs/probe-docker-watchdog-20261021.log`).

**OD-6, OD-7, OD-8 decided by the owner, 2026-10-21: first-start bootstrap.** System design
§5.11 does not define the form of provisioning evidence, how the initial writer learns the state
of the other members, or where policy installation is built.

- OD-6 Evidence: a request file, `bootstrap-request.json`, in the supervisor's state directory.
  Filesystem permissions are its only authentication. It has exactly the fields `clusterId`,
  `incarnation`, `memberNodeIds`, `initialWriterNodeId`, `operationId`, and
  `requiredStandbyNodeIds`, and each must match the node's configuration. The owner chose this
  over a signed request and over a caller-authenticated value. **This does not authenticate an
  operator, which system design §5.11 step 1 requires.** The difference is a recorded gap.
- OD-7 All-node evidence: left until later. The initial writer verifies its own node only.
- OD-8 Scope: two scopes. Scope A: request verification, refusal against local and coordinator
  history, the conditional initial intent, the renewal loop, the bootstrap receipt, and the
  guarded primary start with admission closed. Scope B: standby provisioning, the replication
  check and policy installer, revision-1 confirmation, and prepare/publish/activate, on a
  primary-plus-two-standby fixture shared with 7b.3.

**OD-9 to OD-12 decided by the owner, 2026-10-22: bootstrap scope B.** System design §5.8 and
§5.11 require a guarded policy installation, provisioned standbys, and replication identity
bound to authenticated configured nodes. They do not name the mechanisms.

- OD-9 Policy installation: `ALTER SYSTEM SET synchronous_standby_names` through the local
  socket, then `pg_ctl reload`. The reload is confirmed when `pg_conf_load_time()` changes. The
  setting in effect must then equal the generated value, and every required standby must report
  quorum synchronous state. The setting persists in `postgresql.auto.conf`. The owner chose this
  over a managed include file.
- OD-10 Standby data: the deployment provisions each standby's data directory by base backup with
  `standby.signal` before bootstrap. Scope B starts and validates standbys; it takes no base
  backup. A supervisor base-backup action stays with rebuild and re-join (7b.3).
- OD-11 Peer identity: each standby authenticates with its own replication role. A peer is bound
  to its node when `pg_stat_replication.usename` is that node's role and `application_name` equals
  the node identity. The implementer chose, and the owner can reverse, that the role name equals
  the node identity, so the mapping is derived from membership and not stored.
- OD-12 Production-manager write: included in scope B. `peegeeq-db` becomes a test-scope
  dependency of `peegeeq-pg-failover`. The write goes directly to the writer node, not through
  HAProxy, because the sidecar is still role-only.

Defaults applied without a separate decision, because each follows an existing pattern or
decision: SQL runs through `psql` on the local socket through `LocalCommandRunner`, as in
`PgHbaAdmissionGate`; the writer observes standbys only through its own `pg_stat_replication`,
as OD-7 leaves all-node evidence until later; each standby is started by its own supervisor.

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
8. **Record.** Update Task 8 in the register with the counts. Update §8.1 of this plan.
   Preserve the dated §2 to §4 baseline.
   Report, and state what was not checked.

The test rules are in §12 of the design: identify the node, wait on an observable with a
deadline, assert the failure type, no mocks. In addition: one subject per test class, and every
endpoint is read from the live container.

---

## 7. Acceptance Coverage Obligations

The design §10 defines S1 to S59. No current runtime coverage audit was run in this documentation phase.
The historical seven-class baseline in §4 concerns the earlier design. Do not mark any revised
contract met from those counts.

| Scenario group | Implementation phase | Required evidence |
|---|---|---|
| S2 to S5, S6, S31, S32, S35 | 7: manual profile and re-join | Role, node identity, fencing, quarantine, data continuity, and replacement synchronous policy |
| S13, S25, S38, S40 | 7: durability | Acknowledged-write preservation, unknown commit outcomes, blocked writes without a synchronous peer, and target rejection |
| S1, S15 to S24, S30, S33, S34, S36, S37, S39 | 8: automatic authority and local supervision | Real control and node effects, generations, reconciliation, restart inhibition, and partition safety |
| S9, S14, S26 | 9 and 13: deadlines and connection failures | Specific failures, operation deadlines, connection discard, and observed cleanup |
| S7, S8, S27 | 10: LISTEN and durable replay | Unlimited reconnect, eligibility, acknowledged channels, stable append-ID catch-up after the writer barrier, and delivery identity |
| S11 | 11: shared pooled path and PgBouncer | Every module's configured schema and separate LISTEN endpoint |
| S12 | 12: readiness | Eligibility, durability, catch-up, specific failing checks, and recovery |
| S10, S28, S29 | 13: endpoint recovery | Redundant proxies, stable-address transfer, session recovery, and differing proxy observations |
| S41, S42 | 7b and 8a to 8c: local admission | Own lease, selected watchdog-mode checks, withdrawn intent, local reconciliation, interrupted publication/activation/revocation, and lease loss during in-flight node effects |
| S43, S44 | 7b and 8c: policy lifecycle | Loaded quoted syntax, effective policy, rebuilt-peer exclusion, restored two-peer coverage, and repeated failover |
| S45, S46, S47, S50 | 10 and 12: client recovery | Bounded retry, authenticated writer status, SQL identity matching, writer barrier, lease ownership, uncertain cursor commit, and finite readiness |
| S48 | 13: pooler and address recovery | Both pool modes, complete surviving paths, interrupted transactions, and LISTEN bypass |
| S49 | 8b: Consul conditions and credentials | Atomic rejection, unauthorised access, retained policy history, lost replies, and guarded release |
| S51 | 7b.2 and 8b/8c: first-start bootstrap | Verified stopped or standby-only provisioning, conditional initial intent, guarded starts, confirmed first policy, every interruption, and refusal against existing or ambiguous history |
| S52 | 10 and 12: native recovery | Executed bounded claim, capacity waiting, confirmed processing acknowledgements, delayed scheduling, continuous arrivals, uncertain outcomes, and no readiness from skipped or retired work |
| S53 | 7b.1 and 7b.3: application commit enforcement | Production manager rejects weaker durability outcomes before transition assertions; lost commit replies and acknowledged data are verified through eligible promotion |
| S54 to S58 | Local fault cases in 7b.2/7b.3 and 8c/8d; independent exclusion at production qualification | Supervisor death and starvation, VM pause/resume, delayed renewal/keepalive, route-only failure, and safe takeover without failed-host remote acknowledgement. Device/reset cases apply to optional watchdog qualification. Unqualified cases remain open; they do not block local-supervisor implementation. |
| S59 | 7b.2: coordinator port | One contract suite run against each adapter's real service. The suite is `PgLeaseCoordinatorContract`; its Consul binding is `ConsulLeaseCoordinatorIntegrationTest`. It includes acquisition after release and guarded release. |

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
| 6 | Completed earlier document alignment. The optional-watchdog correction superseded its mandatory wording. The companion designs were aligned on 2026-10-08 (§9.10). No Java or Maven change in this reconciliation. |
| 7a | Architecture selection complete. One local supervisor, node-owned Consul lease, optional watchdog modes, local admission, persistent node storage, and configurable redundant endpoints. Same protocol on Linux hosts/VMs, Docker hosts, and Kubernetes. G-1 to G-7 remain qualification gates. |
| 7b.1 | Complete: production-manager owned commit-policy enforcement and bounded failure observation. Recorded evidence in §8.1. Broad caller migration remains phase 11. |
| 7b.2 | Partial: the coordinator port, the Consul adapter, acquisition after release, guarded release, the watchdog timing correction, local persistent contracts, supervisor-owned start and lease-loss shutdown, local admission, and the renewal loop with queued ownership operations are implemented and tested (§8.1, §9.11, §9.12). Optional watchdog modes are implemented with no-device tests (§9.13). Bootstrap scope A is implemented and tested (§9.14). Scope B increment B1, the durability boundary, is implemented and tested (§9.15). Remaining in 7b.2: scope B increment B2, and the real-device watchdog run on a Linux host. Complete G-7 incrementally. Machine-reset qualification is not an implementation prerequisite. |
| 7b.3 | Implement manual B takeover through local supervision. Test planned stop-before-release and unplanned safe-expiry takeover without failed-host acknowledgement. Prove old-writer exclusion, stale-effect rejection, policy cutover, repeated failover, two-node unavailability, and rewind/rebuild/re-join with production-manager data assertions. |
| 8a | Enable autonomous initiation on the verified common A/B boundary. No second provider, authority, or generation barrier. Test concurrent standby contenders, invalid requests, lease loss during effects, and local activation/revocation races. |
| 8b | Complete per-node automatic ownership and bootstrap reconciliation. Prove quorum loss, namespace/restore handling, invalid records, and distinction between sidecar/observer failure and writer-supervisor death. |
| 8c | Verify automatic infrastructure failover: host loss, asymmetric partition, supervisor death, pauses/resume, delayed ownership/keepalive, in-flight effects, and unreachable-host takeover. No remote stop receipt requirement. |
| 8d | Verify automatic database transition safety with three PostgreSQL nodes, Consul quorum, supervisors, selected watchdog mode, proxies, and confirmed policies. Use the production-manager commit path. Full application timing waits for phases 10 to 14. Qualify independent protection separately before claiming supervisor-death or whole-VM pause safety. |
| 9 | Complete operation and statement deadlines, millisecond precision, lifetime at idle, context-aware discard, and observed cleanup. Retain the verified phase 7 commit boundary and its required bounded failure observation. Add loader, defaults, property-contract, and guide changes for proposed keys. |
| 10 | Implement authenticated `/writer` observation, SQL node identity, separate LISTEN deadlines, initialized-success backoff reset, and shutdown. Reuse the stable writer-barrier replay and lease contract. Implement finite native recovery with executed/skipped/deferred distinctions, capacity waiting, confirmed acknowledgements, and delayed wake-ups. Verify continuous arrivals, uncertain mutations, and retired completions. No second progress mechanism. |
| 11 | Move each remaining module to the verified shared pooled access and classified availability breakers. Preserve phase 7 commit enforcement; do not first introduce it here. Verify schemas through PgBouncer transaction mode. One module per sub-phase. |
| 12 | Implement readiness for writer eligibility, durability, and required catch-up. Keep workload conditions separate from database availability. |
| 13 | Complete frozen-node, in-flight operation, redundant HAProxy/PgBouncer, SQL/HTTP address ownership, and differing-observation scenarios. Qualify the selected G-3 topology and remaining fault cases. |
| 14 | Build target stacks and runbook. Execute every scenario, including policy cutover/repeated failover, controlled membership/profile change, and restored coordination. Measure the recovery objective under specified backlog, handler latency, retry state, and deployed deadlines. Save platform-specific evidence. |
| 15 | Reassess every historical finding against code and fresh logs. Complete G-1 to G-7. Update task register and external documentation links. Close only when all revised requirements and S1 to S59 have matching evidence. |

Phase 7b.1 is a commit-enforcement component test. Use an isolated real synchronous fixture
with a fixed test-only proxy route to its known primary. It verifies the production manager
before the local admission integration is implemented; it does not qualify profile A or B routing.
Bootstrap tests use separate newly provisioned fixtures. Phases 7b.2/7b.3 and 8 repeat application
write evidence through the full admitted stable endpoint. No component fixture closes an
admission, fencing, or whole-application recovery obligation.

The full production recovery objective is 45 seconds for the qualified three-node workload and
fault case. The deadline defaults do not prove it. Two-node synchronous promotion has no committed-write recovery target until a standby
is restored. Do not admit a replacement where the selected fault case lacks established
old-writer exclusion. Optional watchdog mode alone is not exclusion evidence.

This reconciliation records completed implementation slices. It does not close any new
runtime or production qualification obligation. Task 8 carries the same status and next scope.

### 8.1 Current Implementation and Recorded Evidence, 2026-10-08 to 2026-10-22

Source inventory and saved logs were checked for the 2026-10-08 reconciliation. The runs dated
2026-10-08 are recorded runs, not new test executions. The runs dated 2026-10-09 were executed
for that day's revision. The rows dated 2026-10-21 and 2026-10-22 have their runs in §9.12 to
§9.15. None is a release gate.

| Slice | State | Artifact and limit |
|---|---|---|
| 7a architecture | Complete | Dedicated local-supervisor architecture. The mandatory-watchdog decision is superseded by §5.2.1. No deployment qualification follows from this status. |
| 7b.1 managed commit boundary | Complete | `PgConnectionManager` owns begin/commit/rollback and observed close, restores transaction-local `synchronous_commit=on`, checks caller-completed transactions, and reports uncertain commits through `PgCommitOutcomeUnknownException`. The fixture uses HAProxy, a primary, and two physical synchronous standbys. Direct-pool callers and post-promotion durability remain unqualified. |
| 7b.2 coordinator port (P-38, S59) | Implemented and component-tested, 2026-10-08 | `PgLeaseCoordinator` is the port with seven operations. `PgControlRecord` carries `controlName`, `generation`, `revision`, `leaseHolder`, and `intent`. `PgPrimaryElector(PgNodeConfig, PgLeaseCoordinator)` holds intent validation, the freshness deadline, retirement, late-reply rejection, and one ownership operation at a time (queued since OD-4); it makes no product call. `ConsulLeaseCoordinator` in package `dev.mars.peegeeq.failover.consul` holds every Consul HTTP call and the Consul TTL range check. A search of `peegeeq-pg-failover/src` outside `consul/` for `/v1/`, `X-Consul`, `session`, `LockIndex`, `ModifyIndex`, and `consul` returns no match. |
| 7b.2 acquisition after release, and guarded release | Implemented and component-tested, 2026-10-08 | Acquisition after release is one Consul transaction: `check-index`, `lock` with a new session, `get`. Release is one transaction: `check-session`, `check-index`, `unlock` with the value resent, `get`. The elector requires withdrawn intent that names this node, names the released writer as previous writer, and carries both policies unchanged. Release retires the elector whatever the outcome. Release does not require local freshness; the coordinator's conditions guard it. |
| 7b.2 policy validation | Corrected, 2026-10-08 | The earlier elector rejected any policy that named the writer. A takeover intent must carry the former writer's policy, which names the new writer. The rule is now: a policy this node introduces never names the writer; a policy carried unchanged from the prior record may; a `SERVING` confirmed policy never names the writer. |
| 7b.2 timing and watchdog configuration | Corrected and tested, 2026-10-08 | `PgWatchdogMode` is `AUTOMATIC`, `OFF`, or `REQUIRED`. `PgNodeConfig` applies the lease rule in every mode: `loop + 2 × retry <= TTL` and `loop + retry + stop < TTL`. Only `REQUIRED` rejects a configuration without watchdog slack (`loop + retry < TTL/2` and `stop < TTL/2`). `ownershipBudget()` is derived: `TTL − stop` in `OFF` or when watchdog timing is not usable; otherwise the smaller of that and `TTL − TTL/2`. The elector's freshness deadline uses it. The watchdog device adapter was added on 2026-10-21 (watchdog modes row). |
| 7b.2 local persistent contracts | Implemented and tested, 2026-10-08 | `PgLocalStateStore` with `PgWriterGrant` (mode, generation, operation, policy revision, node, state), `PgQuarantine`, and `PgActionReceipt` (generation, operation, action, target node, immutable parameters, result, effect). A grant loaded at open is closed durably before the store is returned. Quarantine blocks preparation and activation and survives restart. A receipt begun twice with equal parameters is one receipt; changed parameters are rejected; a final result is never replaced. `PgLocalStateStoreTest` runs 29 tests against a real directory: `logs/phase7b2-local-state-core-20261008.log`; guards 14: `logs/phase7b2-local-state-guards-20261008.log`. `PgFailoverMonitor` and `PgBootstrap` use the store. |
| 7b.2 supervisor-owned start and local shutdown | Implemented and tested, 2026-10-08 | `PgProcessControl` port; `PgCtlProcessControl` adapter; `LocalCommandRunner`. `PgFailoverMonitor` has `startStandby`, `startPrimary(held)`, `renew`, and `demote`. Primary start is refused without fresh ownership or under quarantine, runs with admission closed, and records a `start-primary` receipt. A failed renewal retires ownership, closes the grant, stops PostgreSQL within the stop budget, and records a `stop-writer` receipt. An unconfirmed stop fails the demotion, quarantines the node, and records the receipt as `UNKNOWN`. Local effects run one at a time; renewal does not queue behind them. Tests use a real PostgreSQL whose container entry point does not start it, a real Consul server, and a real state directory. The loop timer that calls `renew` was added on 2026-10-21 (renewal loop row). Not implemented: promotion. |
| 7b.2 local admission (OD-1) | Implemented 2026-10-08, compiled and tested 2026-10-09 | `PgAdmissionGate` port; `PgHbaAdmissionGate` adapter (§5.3). `PgFailoverMonitor` has `prepareWriter`, `activateWriter`, and `revokeWriter`; `PgPrimaryElector` has `holds`. Standby start and primary start close the gate first. Preparation requires fresh ownership and a confirmed policy. Activation requires fresh ownership of `SERVING` intent and the matching prepared grant; if the gate does not open, admission is revoked and the call fails. Revocation closes the grant and the gate and leaves PostgreSQL running. Commit `ea045d7f` contained this code before it had been compiled. `PgHbaAdmissionGateIntegrationTest` (6 tests) covers the gate's failure modes: an active file that does not hold the installed rules, a `pg_ctl status` exit code other than 0 or 3, a reload with invalid rules on open and on close, an exhausted or non-positive budget, and `open` while PostgreSQL is stopped. A close with invalid rules fails and PostgreSQL keeps the rules that admit applications, so the caller must stop the writer. Each test was confirmed by removing the behaviour from the gate (§9.11). |
| 7b.2 failure logging | Corrected and tested, 2026-10-09 | The module had twelve WARN statements, and eleven of them reported failed, refused, or forced operations. The test-support gate fails a test on an undeclared ERROR and does not see WARN, so no test had to declare them. Those eleven now log at ERROR or, for a requested demotion or revocation, at INFO. `pg_ctl stop` returning a non-zero exit code stays at WARN because the following status check decides. `PgLocalStateStore` reports a storage or decoding fault as "Node-local storage failed", apart from a refusal by one of its rules. Failures that were returned to the caller without any log are now reported too: `PgFailoverMonitor` logs every failed operation at its boundary, and `PgPrimaryElector` logs every refused operation and every late reply. 75 test methods declare the errors they cause with 82 `@ExpectedErrorLog` declarations (§9.11). |
| Sidecar | Role-only | `PgPrimaryCheckVerticle` answers `/primary` from `pg_is_in_recovery()` alone. The sidecar source has no commit since `ff5c17da`. It has no coordinator read, grant check, node identity check, coverage check, `/writer` route, authentication, or request deadline. |
| 7b.2 renewal loop (OD-4) | Implemented and tested, 2026-10-21 | `PgRenewalLoop` calls `PgFailoverMonitor.renew()` on the loop interval, never with a renewal in flight, and starts renewals at least one interval apart. Start requires fresh ownership and is allowed once. A failed renewal ends the loop after the monitor has demoted the writer; `completion()` fails with that failure. `stop()` cancels the next renewal, waits for one in flight, and changes neither ownership nor the writer. A timer that cannot be scheduled fails `start()`; mid-run it demotes the writer and ends the loop. `PgPrimaryElector` queues `renew`, `update`, and `release` (OD-4, §5.3). `PgBootstrap` starts the loop directly after the initial acquisition. No production entry point constructs the loop or `PgBootstrap`. |
| 7b.2 optional watchdog modes (OD-5) | Implemented 2026-10-21; no-device paths tested | `PgWatchdog` port; `PgDeviceWatchdog` adapter (§5.3). `PgFailoverMonitor` applies the mode before a primary start: `off` never uses the watchdog; `automatic` arms it or reports at ERROR and continues; `required` refuses the start when it cannot be armed with a safe timeout. An armed watchdog is fed only after a successful renewal; a failed keepalive demotes the writer. A confirmed stop disarms it; an unconfirmed stop leaves it armed and unfed. The armed state is in memory only. Nothing has run against a real device. Promotion does not exist yet, so the mode is applied at primary start only. |
| 7b.2 bootstrap scope A (OD-6 to OD-8) | Implemented and tested, 2026-10-21 | `PgBootstrapRequest` reads and verifies the request file. `PgBootstrap.start` refuses a quarantined node, a node with any stored grant, and an existing control record; a record of the same operation is reported as an interrupted bootstrap. It then creates the withdrawn initial intent with pending policy revision 1, starts the renewal loop, records the `bootstrap` receipt with the request as parameters, and starts PostgreSQL through the monitor with admission closed. A failed start stops the loop. The receipt has no result; completion is derived in scope B. A rerun is refused, not resumed. The request is not operator-authenticated (OD-6) and other members are not inspected (OD-7). |
| 7b.2 bootstrap scope B, increment B1 (OD-9 to OD-11) | Implemented and tested, 2026-10-22 | `PgDurabilityControl` port; `PgSqlDurabilityControl` adapter. It reads the writer's WAL flush boundary (refused in recovery), lists replication peers, and installs a policy by OD-9. `PgDurabilityPolicy` is the typed form of the control record's policy: it generates the quoted setting and holds the coverage rule (OD-11). `PgLsn` and `PgReplicationPeer` are derived observations. No command argument contains a double quote: the installed value is an escape string. Nothing calls the port yet. |
| 7b.2 bootstrap scope B, increment B2, step 1: guarded policy installation | Implemented and tested, 2026-10-24 | `PgDurabilityControl.confirmCovered` observes one fresh flush boundary and waits for every required standby to cover it within the adapter's budget. `PgFailoverMonitor.installPendingPolicy(held)` derives the policy from the held record's pending policy, closes the grant and the gate, confirms coverage, and installs under the `install-policy` receipt (`COMPLETED`, or `UNKNOWN` after a failed install). The control record is not changed. `PgFailoverMonitor` takes a `PgDurabilityControl`. See §9.16. |
| 7b.2 bootstrap scope B, increment B2, step 2: confirmation, publication, activation | Implemented and tested, 2026-10-24 | `PgBootstrap.serve(held, mode)` runs after `start`: install the pending policy, confirm revision 1 (the pending policy becomes `durabilityPolicy`), prepare the grant, publish `SERVING`, activate the grant. A failure at any step leaves admission closed and the record where the step left it. No bootstrap receipt result is written; completion is derived. See §9.17. |
| 7b.2 bootstrap scope B, increment B2, step 3: production-manager write (OD-12) | Implemented and tested, 2026-10-25 | `peegeeq-db` is a test-scope dependency. The production `PgConnectionManager` connects directly to the writer node (the fixture publishes its port), not through HAProxy. Three tests in `PgBootstrapServingIntegrationTest`: a write before serving is rejected by the closed gate; a write after serving is acknowledged by both standbys (flush position checked); a caller that sets `synchronous_commit = off` still waits for both standbys and gets `PgCommitOutcomeUnknownException` with one standby stopped. See §9.19. |
| 7b.2 bootstrap scope B, remaining | Not implemented | A production entry point that calls `PgBootstrap`, the renewal loop, and the watchdog; resuming an interrupted `serve` (the takeover path, 7b.3). Stopped-before-release remains open. |
| 7b.3 increment P1: guarded local promotion with CHECKPOINT | Implemented and tested, 2026-10-26 | `PgPromotionControl` and `PgSqlPromotionControl` (`pg_ctl promote`, role observation, forced and verified CHECKPOINT); `PgFailoverMonitor.promoteStandby(held)` under `promote` and `checkpoint-after-promote` receipts, with admission closed. See §9.21. Not in P1: slots, repointing standbys, the policy cutover, planned stop with release, expiry takeover. |
| 7b.3 manual takeover and re-join, remaining | Not implemented | Requires the policy cutover after promotion, repointing standbys, replication slots, old-writer exclusion by expiry, repeated failover, controlled re-join, and routed production-manager assertions. |
| G-7 full coordinator qualification | Open | Snapshot/restore, TLS, stopped-before-release, and expiry versus independent old-writer exclusion remain unverified. Component success does not close G-7. |

Recorded clean installs used `mvn clean install -DskipTests -pl :<changed-module> -am`:

| Module | Saved successful rebuild |
|---|---|
| `peegeeq-db` | `logs/phase7b1-standby-compatible-rebuild-20261008.log` |
| `peegeeq-pg-failover` | `logs/phase7b2-lease-verified-rebuild-20261008.log` |

Every class below reports zero failures, errors, and skips in its saved final run.

| Slice and profile | Class | Tests run | Saved log under `logs/` |
|---|---|---:|---|
| 7b.1 `integration-tests` | `PgConnectionManagerDurabilityIntegrationTest` | 15 | `phase7b1-completion-tests-20261008.log` |
| 7b.1 `integration-tests` | `PgConnectionManagerCoreTest` | 22 | `phase7b1-completion-tests-20261008.log` |
| 7b.1 `integration-tests` | `PgPoolCircuitBreakerIntegrationTest` | 1 | `phase7b1-completion-tests-20261008.log` |
| 7b.1 default/core guards | `DisabledTestsGuardTest` | 2 | `phase7b1-completion-guards-20261008.log` |
| 7b.1 default/core guards | `InvalidDurationLiteralGuardTest` | 2 | `phase7b1-completion-guards-20261008.log` |
| 7b.1 default/core guards | `OnSuccessExceptionSwallowingGuardTest` | 8 | `phase7b1-completion-guards-20261008.log` |
| 7b.1 default/core guards | `SchemaInitializerTestInfrastructureGuardTest` | 1 | `phase7b1-completion-guards-20261008.log` |
| 7b.1 default/core guards | `VertxAsyncForbiddenPatternsGuardTest` | 1 | `phase7b1-completion-guards-20261008.log` |
| 7b.2 `integration-tests` | `ConsulLeaseProtocolIntegrationTest` | 28 | `phase7b2-lease-verified-integration-20261008.log` |
| 7b.2 default/core | `PgNodeConfigTest` | 8 | `phase7b2-lease-verified-core-20261008.log` |
| 7b.2 default/core guards | `DisabledTestsGuardTest` | 2 | `phase7b2-lease-verified-guards-20261008.log` |
| 7b.2 default/core guards | `InvalidDurationLiteralGuardTest` | 2 | `phase7b2-lease-verified-guards-20261008.log` |
| 7b.2 default/core guards | `OnSuccessExceptionSwallowingGuardTest` | 8 | `phase7b2-lease-verified-guards-20261008.log` |
| 7b.2 default/core guards | `SchemaInitializerTestInfrastructureGuardTest` | 1 | `phase7b2-lease-verified-guards-20261008.log` |
| 7b.2 default/core guards | `VertxAsyncForbiddenPatternsGuardTest` | 1 | `phase7b2-lease-verified-guards-20261008.log` |
| 7b.2 port and timing `integration-tests` | `ConsulLeaseCoordinatorIntegrationTest` | 60 | `phase7b2-watchdog-timing-integration-20261008.log` |
| 7b.2 port and timing default/core | `PgNodeConfigTest` | 17 | `phase7b2-watchdog-timing-core-20261008.log` |
| 7b.2 port and timing guards | `DisabledTestsGuardTest` | 2 | `phase7b2-watchdog-timing-guards-20261008.log` |
| 7b.2 port and timing guards | `InvalidDurationLiteralGuardTest` | 2 | `phase7b2-watchdog-timing-guards-20261008.log` |
| 7b.2 port and timing guards | `OnSuccessExceptionSwallowingGuardTest` | 8 | `phase7b2-watchdog-timing-guards-20261008.log` |
| 7b.2 port and timing guards | `SchemaInitializerTestInfrastructureGuardTest` | 1 | `phase7b2-watchdog-timing-guards-20261008.log` |
| 7b.2 port and timing guards | `VertxAsyncForbiddenPatternsGuardTest` | 1 | `phase7b2-watchdog-timing-guards-20261008.log` |

Final runs for §8.2 items 1 to 4, after the last source change on 2026-10-08. Rebuild:
`phase7b2-process-control-rebuild-20261008.log`. Every class reports zero failures, errors, and skips.

| Profile | Class | Tests run | Saved log under `logs/` |
|---|---|---:|---|
| `integration-tests` | `ConsulLeaseCoordinatorIntegrationTest` | 60 | `phase7b2-process-control-integration-20261008.log` |
| `integration-tests` | `PgFailoverMonitorIntegrationTest` | 9 | `phase7b2-process-control-integration-20261008.log` |
| `integration-tests` | `PgCtlProcessControlIntegrationTest` | 10 | `phase7b2-process-control-integration-20261008.log` |
| default/core | `LocalCommandRunnerTest` | 5 | `phase7b2-process-control-core-20261008.log` |
| default/core | `PgLocalStateStoreTest` | 29 | `phase7b2-process-control-core-20261008.log` |
| default/core | `PgNodeConfigTest` | 17 | `phase7b2-process-control-core-20261008.log` |
| default/core guards | five guard classes | 14 | `phase7b2-process-control-guards-20261008.log` |

Red evidence for each scope: `phase7b2-port-red-20261008.log` (compilation),
`phase7b2-watchdog-timing-red-20261008.log` (compilation),
`phase7b2-watchdog-budget-red-20261008.log` (2 run, 1 failure: ownership lapsed before its
budget in `off` mode), `phase7b2-local-state-red-20261008.log` (compilation), and
`phase7b2-process-control-red-20261008.log` (compilation).

`ConsulLeaseProtocolIntegrationTest` (28 cases) was deleted on 2026-10-08. Its cases run in
`ConsulLeaseCoordinatorIntegrationTest`: 20 through the neutral contract suite
`PgLeaseCoordinatorContract`, and 8 as Consul wire cases in the binding. The rebuild for the
60-test run is `phase7b2-watchdog-timing-rebuild-20261008.log`.

The intermediate `phase7b2-lease-generation-tests-20261008.log` records 22 tests and one
failure: the close-verification read returned HTTP 403. The isolated close diagnostic ran
one test successfully. The final full class ran 28 successfully. The intermediate failure's
cause remains unverified. Do not report it as fixed or add a retry to conceal it.

Not checked by the 2026-10-08 reconciliation: new runtime behavior, Java regressions after a code
change, end-to-end admission/takeover, production TLS/restore, independent watchdog
exclusion, whole-VM pause/resume, or the 45-second recovery objective.

Runs of 2026-10-09, executed on a second development machine after the last source change of
that day. The logs dated 2026-10-08 named above are untracked files (`logs/` is ignored by git)
and were not present on that machine, so none of them was reread. Rebuild:
`rebuild-peegeeq-pg-failover-gaps-green-20261009.log`. Every class reports zero failures,
errors, and skips.

| Profile | Class | Tests run | Saved log under `logs/` |
|---|---|---:|---|
| `integration-tests` | `ConsulLeaseCoordinatorIntegrationTest` | 61 | `peegeeq-pg-failover-integration-gaps-green-20261009.log` |
| `integration-tests` | `PgFailoverMonitorIntegrationTest` | 20 | `peegeeq-pg-failover-integration-gaps-green-20261009.log` |
| `integration-tests` | `PgCtlProcessControlIntegrationTest` | 10 | `peegeeq-pg-failover-integration-gaps-green-20261009.log` |
| `integration-tests` | `PgHbaAdmissionGateIntegrationTest` | 6 | `peegeeq-pg-failover-integration-gaps-green-20261009.log` |
| default/core | `LocalCommandRunnerTest` | 6 | `peegeeq-pg-failover-core-gaps-green-20261009.log` |
| default/core | `PgLocalStateStoreTest` | 29 | `peegeeq-pg-failover-core-gaps-green-20261009.log` |
| default/core | `PgNodeConfigTest` | 17 | `peegeeq-pg-failover-core-gaps-green-20261009.log` |
| default/core guards | five guard classes | 14 | `peegeeq-test-support-core-guards-errorlevels-20261009.log` |

After these runs the gate source was changed for a probe and restored to the same SHA-256.
`PgHbaAdmissionGateIntegrationTest` then passed 6 of 6 again:
`peegeeq-pg-failover-gate-restored-20261009.log`.

Red evidence of 2026-10-09:

- `peegeeq-pg-failover-integration-20261009.log`: 88 run, 1 failure. The first test of
  `ConsulLeaseCoordinatorIntegrationTest` failed in its setup with HTTP 403 (§9.11).
- `pg-failover-consul-acl-red-run1-20261009.log` to `-run6-`: one contract test run alone six
  times before the fixture correction. Four runs failed, two with HTTP 403 in setup and two with
  HTTP 500 from session creation. `pg-failover-consul-acl-green-run1-20261009.log` to `-run10-`:
  the same test passed ten times after the correction.
- `peegeeq-pg-failover-core-errorlevels-red-20261009.log`: 51 run, 23 failures.
  `peegeeq-pg-failover-integration-errorlevels-red-20261009.log`: 91 run, 43 failures. These runs
  had the `@ExpectedErrorLog` declarations and the unchanged production code. Every failure is
  "Expected ERROR occurrence mismatch" with `observed=0`.
- `peegeeq-pg-failover-integration-gaps-red-20261009.log`: 97 run, 14 failures, the same reason,
  for the refusals and late replies that were not yet logged.
- `peegeeq-pg-failover-core-gaps-red-20261009.log`: 52 run, 1 failure. The new
  interrupted-command test found an output file that could not be deleted.
- `peegeeq-pg-failover-gate-mutation-red-20261009.log` and
  `peegeeq-pg-failover-gate-mutation-c-red-20261009.log`: the six gate tests against a gate with
  each behaviour removed. All six failed.

Whole-repository run of 2026-10-09, after the last source change. `mvn clean test -Pall-tests`
stopped at `peegeeq-db`: `peegeeq-test-support` 87 tests and `peegeeq-api` 228 tests passed;
`peegeeq-db` ran 1,124 tests with 1 failure. The failure is
`HaProxyConnectionFailoverTest.testFailbackAfterPrimaryRecovery`, at the step that §4.2 records
as red since 2026-10-07: the pool that served queries during the outage did not reach the
replacement primary within 30,000 ms. Log: `all-tests-20261009.log`. No file in `peegeeq-db` was
changed on 2026-10-09. After `mvn clean install -DskipTests -pl :peegeeq-db -am`, the run was
resumed with `mvn test -Pall-tests -rf :peegeeq-outbox`. All 17 remaining modules passed.
Log: `all-tests-resume-outbox-20261009.log`.

| Module | Tests run | Failures |
|---|---:|---:|
| `peegeeq-outbox` | 667 | 0 |
| `peegeeq-native` | 361 | 0 |
| `peegeeq-bitemporal` | 506 | 0 |
| `peegeeq-runtime` | 48 | 0 |
| `peegeeq-rest` | 521 | 0 |
| `peegeeq-rest-client` | 48 | 0 |
| `peegeeq-service-manager` | 76 | 0 |
| `peegeeq-pg-sidecar` | 9 | 0 |
| `peegeeq-pg-failover` | 149 | 0 |
| `peegeeq-examples` | 178 | 0 |
| `peegeeq-benchmarking` | 151 | 0 |
| `peegeeq-migrations` | 53 | 0 |
| `peegeeq-integration-tests` | 109 | 0 |
| `peegeeq-management-ui` | Vitest 128, Playwright 419 | 0 |
| `peegeeq-utilities-ui` | Vitest 836, Playwright 246 | 0 |

No module total reports a skipped test. Twelve classes report zero tests. Each is an outer class
whose tests are in nested classes, and the nested counts equal the test annotations in the
source. The whole-repository gate is not green: it cannot pass while the phase 5 test is red.

Not checked on 2026-10-09: end-to-end admission through a proxy, takeover, production
TLS/restore, independent watchdog exclusion, whole-VM pause/resume, and the 45-second recovery
objective.

### 8.2 Next Implementation Scope: Phase 7b.2

Continue in the existing `peegeeq-pg-failover` module. Use available Docker/Testcontainers.
No new host/VM access or machine-reset testing is required to begin or verify this scope.
Each numbered item is one scope: failing tests first, clean reactor rebuild, smallest tagged
test run through `Tee-Object`, per-class counts read from the saved log, then a report.

1. **Coordinator port (P-38, S59).** A refactor with no behaviour change, followed by the two
   missing operations. No open decision blocks it.
   1. Define the port `PgLeaseCoordinator` with the seven operations of system design §5.10.
      Make `PgControlRecord` neutral: control record name, generation, revision, lease holder,
      intent. Rename the TTL in `PgNodeConfig` to a lease TTL and move the Consul range check
      (whole seconds, 10 to 86,400) into the adapter.
   2. Move every Consul HTTP call out of `PgPrimaryElector` into `ConsulLeaseCoordinator` in
      package `dev.mars.peegeeq.failover.consul`. The elector keeps intent validation, the
      freshness deadline, epoch and retirement, and the single in-flight operation.
   3. Split `ConsulLeaseProtocolIntegrationTest` into a coordinator contract suite and its
      Consul binding. The suite states the port's obligations; the binding supplies the real
      three-server Consul quorum, tokens, and fault proxy. All 28 existing cases must pass
      through the port. Cases that assert Consul wire details (session TTL string, `Behavior`,
      `LockDelay`, check lists) stay in the Consul binding.
   4. Write failing tests, then implement in the adapter: acquisition after release at an
      expected revision, and guarded release. Failure modes for each: failed Future, synchronous
      throw, malformed or null result, timeout, lost reply, and late reply after retirement.
   5. Record by search that no source file outside the `consul` package contains Consul API
      paths, header names, or the words session, `LockIndex`, or `ModifyIndex`.
2. **Watchdog timing correction.** Write failing configuration and ownership-budget tests.
   Separate the lease and local-stop rule from watchdog timing in `PgNodeConfig` and in the
   elector's freshness deadline. Represent `automatic`, `off`, and `required`. A missing device
   must not prevent local process tests in `automatic` or `off`. Preserve request deadlines and
   retired-reply rejection.
3. **Local persistent contracts.** Define quarantine, execution grants, and action receipts as
   authoritative. Lease freshness, PostgreSQL role, process state, watchdog health, and
   eligibility are derived. Loaded grants start closed. Blocked on `OD-3` in §5.3.
4. **Supervisor-owned PostgreSQL startup and local shutdown.** Verify lease-loss admission
   withdrawal, process exit, existing session termination, bounded failure reporting, and
   inability to restart a writer through a second entry point. Blocked on `OD-2`.
5. **Serialized local admission**, quiescence, durable receipts and quarantine, and closed
   restart grants. Dependency throw, rejection, malformed or null result, timeout, lost reply,
   and stale completion each require a failing test before their calling code. Blocked on
   `OD-1`.

Items 1 to 4 are complete as of 2026-10-08 (§8.1, §9.11). Items 3, 4, and 5 used the OD-3, OD-2,
and OD-1 choices recorded in §5.3. Item 5 is implemented, and its tests were first compiled and
run on 2026-10-09. Its code was written before its tests ran. On 2026-10-09 the gate's failure
modes received tests, and each test was confirmed by removing the behaviour from the gate
(§9.11). One guard has no test: the gate and `PgCtlProcessControl` each reject a command runner
that returns no result, and the real runner never returns none. Item 5 is otherwise complete.

6. **Renewal loop.** Complete, 2026-10-21 (§8.1, §9.12). OD-4 in §5.3 records the queueing
   decision it required.

7. **Optional watchdog modes.** Implemented 2026-10-21 (§8.1, §9.13). OD-5 in §5.3 records the
   device mechanism. The real-device run on a Linux host is open.

8. **First-start bootstrap, scope A.** Complete, 2026-10-21 (§8.1, §9.14). OD-6 to OD-8 in §5.3.

9. **First-start bootstrap, scope B, increment B1: the durability boundary.** Complete,
   2026-10-22 (§8.1, §9.15). OD-9 to OD-12 in §5.3.

10. **First-start bootstrap, scope B, increment B2, step 1: guarded policy installation.**
    Complete, 2026-10-24 (§8.1, §9.16).

11. **First-start bootstrap, scope B, increment B2, step 2: confirmation, publication, and
    activation.** Complete, 2026-10-24 (§8.1, §9.17).

12. **First-start bootstrap, scope B, increment B2, step 3: the production-manager write.**
    Complete, 2026-10-25 (§8.1, §9.19). Scope B increment B2 is complete.

Next in 7b.2: the real-device watchdog run on a Linux host. Then 7b.3 (manual takeover), which also
resumes an interrupted `serve`. The text below describes the whole of scope B. Scope A covers
request verification, withdrawn intent, and the guarded primary start. Scope B adds system
design §5.11 steps 3 to 5: guarded standby start and validation, the fresh WAL boundary check,
policy installation, confirmation of revision 1, prepare/publish/activate, and a
production-manager write under the required commit policy. Manual takeover remains 7b.3;
autonomous initiation remains phase 8.

Known gaps remain production qualification of independent exclusion during supervisor death
or whole-VM pause, full G-7 security/restore, routed failover/re-join, the sidecar's eligibility
contract, and application recovery timing. These gaps must stay visible. They do not make
additional infrastructure a prerequisite for the next implementation scope.

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

### 9.6 Transition-Order Alignment, 2026-10-08

The transition summaries previously began the provider generation before persisting withdrawn
intent. That order contradicted the provider precondition. The revised sequence establishes
ownership, persists withdrawn intent, completes the generation barrier, then revokes admission.
Automatic takeover acquires ownership with withdrawn intent. An existing owner retains its
session and conditionally withdraws before provider effects. Manual begin-transition persists
intent before those effects at the provider's durable boundary.

S42, G-2, and phase 8a now require rejection without effects when matching withdrawn intent is
missing. All five documents use the same order. Sections 2 to 4 remain historical evidence.
This phase changes documentation only. Runtime enforcement and production qualification still
require the planned real-component acceptance.

### 9.7 Phase 7a Preparation, 2026-10-08

The source inspection read the local failover Compose definition, HAProxy configuration, scripts
guide, operations guide, streaming-replication test, and PostgreSQL test constants. The Compose
definition contains two independent databases, one HAProxy, and optional PgBouncer modes. It does
not define the required replicated three-node fixture or a production fencing provider.

Section 5.2 selects the existing Docker/Testcontainers workflow for local acceptance and records
the concrete production bindings required next. The production platform question was submitted
to the operator. No platform, fence, admission implementation, or endpoint owner is marked selected
by this preparation. No Java, Maven, Compose, or runtime configuration changed. No test was run.



### 9.8 Patroni-Style Supervision Revision, 2026-10-08

The owner directed adoption of the Patroni approach. The earlier decision to require one
production platform and a remote stop-confirmation provider was wrong. It made ordinary
takeover depend on contacting the failed infrastructure.

The active design now uses one PeeGeeQ supervisor per node, a node-owned Consul writer lease,
local self-demotion, required independent watchdog protection, and local restart quarantine.
Both manual and automatic modes use the same contract. Consul is selected for the first
implementation. Manual initiation is not a Consul-free authority.

The central provider, all-node generation-installation barrier, and provider-owned manual
generations are removed. Qualified expiry permits takeover without a failed-host reply.
Voluntary release still requires confirmed local stop. The replacement is a running standby;
local old-writer shutdown remains part of the mechanism. This is not token-only fencing.

The official Patroni watchdog, dynamic configuration, DCS failsafe, FAQ, and selected HA,
watchdog, and Consul adapter source sections were inspected. This is documentation/source
research, not a full Patroni source audit or runtime comparison.
The Consul session contract was checked for early invalidation and TTL lower-bound semantics.

S54 to S58 add infrastructure enforcement cases. G-1 includes real whole-VM pause/resume and
device/reset-scope qualification. Phase 7a architecture selection is complete; production
bindings, runtime watchdog/lease evidence, implementation, and recovery timing are open.
Sections 2 to 4 and prior dated §9 records remain historical evidence. No Java/Maven/runtime
configuration changed and no Java or watchdog test ran in this documentation phase.

### 9.9 Implementation Reconciliation and Watchdog Correction, 2026-10-08

The decision in §9.8 to make independent watchdog support mandatory was wrong. Patroni's
watchdog has `automatic`, `off`, and `required` modes. Its default is `automatic`; activation
failure blocks promotion in `required` mode. The correction is sourced in §5.2.1.

This revision reconciles the implementation plan and Task 8 with source inventory and saved
build/test summaries. It records 7b.1 completion and the 7b.2 Consul component evidence in §8.1.
It removes machine-reset infrastructure from the prerequisites for local-supervisor work.
It does not infer whole-VM or orphan-writer safety from Docker process tests.

Sections 2 to 4 and §9.1 to §9.8 retain dated evidence. Companion mandatory-watchdog design
wording remains to align with the current decision. No Java, Maven, deployment configuration,
or test was changed or run for this reconciliation. No new production gate is closed.

### 9.10 Coordinator Port and Companion Design Alignment, 2026-10-08

All five documents, Task 8, the `peegeeq-pg-failover` module with its tests, the phase 7b.1
changes in `peegeeq-db`, and `PostgreSQLTestConstants` were read in full before this revision.
Qraft's `distributed_state.proto` and `commands.proto` were read in full. Its client API has
`Put`, `Get`, `Delete`, and `List` only.

The owner directed that any hard dependency on Consul be abstracted away, because Qraft may be
used. P-38 records the contract. The design now defines a coordinator port with neutral terms
and seven operations, the obligations every adapter must meet, one shared contract suite, and
Consul as the first adapter (system design §5.10; coordinator design §3). R-20 and S59 were
added. The lease TTL key is `peegeeq.pg.failover.lease.ttl`, and `peegeeq.pg.coordinator.type`
selects the adapter. The Consul lock delay is no longer a neutral setting; the Consul adapter
fixes it at zero.

The four design documents were aligned with the optional-watchdog decision in §5.2.1. The
system design's default mode is now `automatic`. Statements that required an armed watchdog
now depend on the selected mode. Each document states that a deployment without an active
watchdog claims no old-writer exclusion for supervisor death or whole-VM pause.

The word "proposed" was removed from the design documents where it described the
`peegeeq-pg-failover` module or the configuration contract. The design documents describe the
target and carry no implementation status.

Three observations from the read were recorded as implementation state in §8.1: the coordinator
port is not implemented, the elector lacks acquisition after release and guarded release, and
the sidecar is still role-only. Three unspecified design points were recorded in §5.3 as
`OD-1` to `OD-3`. The next scope in §8.2 was reordered to extract the coordinator port first.

The file `PEEGEEQ_FAILOVER_CONSUL_DESIGN.md` keeps its name so that existing links resolve. Its
title is now "PostgreSQL Failover: Coordinator Lease and Local Supervision".

This revision changes documentation only. No Java, Maven, deployment configuration, or test was
changed or run. Sections 2 to 4 and §9.1 to §9.9 retain dated evidence and still use the Consul
vocabulary of their dates.

### 9.11 Local Supervision, Admission, and Verification, 2026-10-08 and 2026-10-09

**2026-10-08.** Commit `ea045d7f` implemented §8.2 items 1 to 4 and recorded their runs in §8.1.
It also contained the item 5 code: `PgAdmissionGate`, `PgHbaAdmissionGate`, the admission
methods of `PgFailoverMonitor`, `PgPrimaryElector.holds`, and nine admission tests. That code had
not been compiled. `PgFailoverMonitor` had been rewritten after its 9-test run. The commit
referred to this section, which did not exist, and did not record the OD-1 choice.

**2026-10-09, verification.** The module was rebuilt on a second development machine with
`mvn clean install -DskipTests -pl :peegeeq-pg-failover -am`. The item 5 code compiled without
change. `PgFailoverMonitorIntegrationTest` ran 18 tests with zero failures. The OD-1 choice is now
recorded in §5.3.

**2026-10-09, Consul fixture readiness.** The first full run of
`ConsulLeaseCoordinatorIntegrationTest` failed its first test in setup with HTTP 403. The fixture
waited for `/v1/status/leader` to name a leader and nothing else. A probe against four new
three-server quorums with ACLs showed the same sequence each time. For about 50 ms after a leader
is visible, the management token receives `403 ACL system must be bootstrapped`. Until about
190 ms, `PUT /v1/session/create` receives `500 Missing node registration`. The servers then
appear in the catalog one at a time. Only the first test of the class meets this window. The
binding now also waits, inside the same 30-second deadline, until `/v1/catalog/nodes` lists all
three servers for the management token. This is a readiness condition of the fixture. No lease
operation is retried. `PgFailoverMonitorIntegrationTest` uses the same leader-only wait against
one dev-mode agent. Four probe rounds found no window there, and it is unchanged.

§8.1 records an HTTP 403 in an intermediate run of 2026-10-08 whose cause was unverified. Its log
was not available on 2026-10-09. Whether it had this cause is not established.

**2026-10-09, test logging.** The module had no `logback-test.xml`. Logback's default
configuration applied, and one integration run wrote 1,349,968 bytes with 4,235 DEBUG lines.
`src/test/resources/logback-test.xml` was added from the `peegeeq-test-support` file with the
Testcontainers and Vert.x blocks used by `peegeeq-db`. It sets no logger to `OFF` and uses no
`additivity="false"`, so every ERROR event still reaches the capture appender on the root
logger.

**2026-10-09, failure logging.** The test logs held 73 WARN entries from the module. A read of
each found that 69 reported failures: coordinator failures that retire ownership, corrupt or
foreign node-local records, storage faults, refused operations, a command that could not run,
and demotion after lease loss. The other 4 were a demotion or revocation that the test had
requested. The rule now applied in the module is: a failed or refused operation is ERROR; a demotion or
revocation that the caller requested is INFO; WARN is for a condition the code resolves by
itself.

| Class | Statement | Level now |
|---|---|---|
| `PgPrimaryElector` | "Coordinator operation failed", "Coordinator request rejected" | ERROR. The exception logged is the one returned. |
| `ConsulLeaseCoordinator` | "Consul request rejected before sending" | ERROR |
| `PgFailoverMonitor` | "Demoting local PostgreSQL", "Revoking writer admission" | ERROR with the cause when a failure forces it; INFO when the caller requests it |
| `PgFailoverMonitor` | "Local supervision operation rejected" | ERROR |
| `PgFailoverMonitor` | "Local supervision operation failed" (new: any operation that fails after it has started) | ERROR |
| `PgPrimaryElector` | "Lease operation refused" (new), "Late coordinator reply rejected" (new) | ERROR |
| `PgLocalStateStore` | "Node-local state operation rejected", "Node-local state operation failed" | ERROR |
| `PgLocalStateStore` | "Node-local storage failed" (new: I/O failure or undecodable record) | ERROR |
| `LocalCommandRunner` | "Command could not run", "Interrupted while waiting for command" | ERROR |
| `PgHbaAdmissionGate` | "Admission command failed" | ERROR |
| `PgCtlProcessControl` | `pg_ctl stop` exit code | WARN, unchanged |

The tests were changed first. 60 existing test methods received `@ExpectedErrorLog`
declarations, one already had one, and three tests were added for paths that had none:
`failedPrimaryStartDemotesTheNodeAndFails`, `admissionCommandThatCannotRunRefusesStandbyStart`,
and `adapterRejectsRenewalOfARecordWithoutALeaseHolder`. With the production code unchanged, 23
core tests and 43 integration tests failed, each with "Expected ERROR occurrence mismatch" and
`observed=0`. After the production change every test passed on the first run. The final counts
and logs are in §8.1.

**2026-10-09, failures that had no log.** Three kinds of failure were returned to the caller
with no log entry: a refusal raised inside an asynchronous step of `PgFailoverMonitor` (for
example a primary start under quarantine, or with the closed admission file missing), an
operation that `PgPrimaryElector` refuses to start, and a reply that arrives after retirement or
after the freshness deadline. `PgFailoverMonitor` now logs every failed operation at its
boundary. `PgPrimaryElector` logs each refusal and each late reply. 14 tests declare them. With
the declarations and the unchanged production code, those 14 failed with `observed=0`.

**2026-10-09, admission gate failure modes.** `PgHbaAdmissionGateIntegrationTest` was added with
six tests against a real PostgreSQL (§8.1). The tests describe existing code, so they passed on
their first run. Each behaviour was then removed from the gate in turn and each test failed. The
gate source was restored to the same SHA-256. An experiment against `postgres:15.13-alpine3.20`
gave the facts the tests rest on: `pg_ctl status` exits 4 for a directory that is not a cluster;
a reload with an invalid rule exits 0, changes `pg_conf_load_time()`, reports one row with an
error in `pg_hba_file_rules`, and leaves the previous rules in force; `cp -f` onto a path that
is a directory exits 0 and `cmp` then exits 2.

**2026-10-09, interrupted command.** A test now interrupts the worker thread while it waits for
a child process. It found a defect: the runner killed the child and did not wait for it to
exit. On Windows the child still held the output capture file, the delete failed, and the file
was left behind. The runner now waits for the kill, as the timeout path does. The existing
timeout test decided whether the child survived by reading its command line. Windows reports no
command line for a child process, so that assertion could not fail there. It now compares
process identifiers.

Known gaps after 2026-10-09:

- The gate and `PgCtlProcessControl` each reject a command runner that returns no result. That
  guard has no test, because the real runner never returns none.
- A failed open or close leaves the file it installed in place. After a reload with invalid
  rules, `pg_hba.conf` on disk holds the invalid rules although PostgreSQL still applies the
  previous ones.
- When a test fails, its asynchronous `@AfterEach` is not awaited. vertx-junit5 5.0.4,
  `VertxExtension.joinActiveTestContexts`, returns at once when the extension context already
  holds an execution exception, and `interceptAfterEachMethod` relies on it. Vert.x is then
  closed under the running teardown. A probe confirmed it: after a passing test a 1.5-second
  teardown completed; after a failing test the teardown's timer was cancelled 1 ms after it
  started (`logs/probe-teardown-after-failure-20261009.log`). In this module the effect is that
  PostgreSQL can stay running after a failed test and the next test cannot start it. The same
  applies to every test class in the repository that uses `VertxExtension` with an `@AfterEach`
  that takes a `VertxTestContext`: 244 classes.
- The integration tests of `PgFailoverMonitor` never renew the lease. Their configuration gives a
  12-second ownership budget, and the slowest test took 8.89 seconds on the second machine.
- `ConsulLeaseCoordinator` reports the HTTP status of a rejected request and drops the response
  body.
- The other modules hold 318 `warn` calls in production sources. They were classified on
  2026-10-09 with two reads each, and none was changed. A text search found 44 more failure
  sites that log below WARN, and a further scan found 17 catch blocks and 2 Future conversions
  that drop a failure with no log. Probe runs on the same day confirmed queue, stream, and
  event-store defects outside this module; one deletes a message and one stores an event with
  the wrong valid time. Seven test log configurations in other modules detach or switch off
  production loggers, which hides their ERROR events from the unexpected-ERROR gate; a run
  confirmed it in each of the seven modules. The results are in the task register under
  "Failure log level classification".

### 9.12 Renewal Loop and Queued Ownership Operations, 2026-10-21

**Design finding.** Before writing the loop, a read of `PgPrimaryElector` showed that `renew`,
`update`, and `release` refused each other while one was in flight, and that
`PgFailoverMonitor.renew()` demotes the writer on any renewal failure. A timer would have demoted
a healthy writer whenever a tick met an update. The owner decided OD-4 (§5.3).

**Queueing, TDD.** `PgPrimaryElectorQueueingIntegrationTest` (4 tests, real Consul) was written
first. Against the refusing elector, 3 of 4 failed, each on the refusal of the overlapping
operation: `logs/peegeeq-pg-failover-elector-queue-red-20261021.log`. The fourth test, an
operation queued behind a failed one is refused, passed against both versions, because both
refuse it. After the change the class passed 4 of 4:
`logs/peegeeq-pg-failover-elector-queue-green-20261021.log`.

**Renewal loop, TDD.** `PgRenewalLoopIntegrationTest` (13 tests, real Consul, a real PostgreSQL
that only the supervisor starts, real node-local storage) first failed compilation:
`logs/peegeeq-pg-failover-renewal-loop-red-20261021.log`. The first run after implementation
passed 12 of 13. The failing test had declared the wrong expected log: a coordinator that
returns a null Future fails inside the elector's `.map` with a `NullPointerException`, logged as
"Coordinator request rejected", before the elector's null-result check runs. The declaration was
corrected; the production code was not changed. A second run failed with
`NoClassDefFoundError: PgSupervisedContainer` from stale test classes. After a clean rebuild the
class passed 13 of 13: `logs/peegeeq-pg-failover-renewal-loop-run3-20261021.log`.

The tests cover: ownership stays fresh beyond one ownership budget; renewals start at least one
interval apart; a lost lease, a coordinator that throws, one that returns a null Future, and one
that never replies each end the loop, demote the writer, and fail `completion()`; `stop()`
cancels later renewals, waits for the renewal in flight, and leaves the writer running; a
renewal due during an update waits and does not demote; start without ownership, a second
start, and a start after stop are refused; a timer that cannot be scheduled fails `start()`.
`ObservedLeaseCoordinator` wraps the real Consul adapter in these tests. It records renewal
start times and lets a test hold or replace the renewal or update call.

**Mutation check.** With rescheduling removed from `PgRenewalLoop`, the two timing tests failed:
"Ownership lapsed although the loop was running" and "Fewer than 4 renewals started":
`logs/peegeeq-pg-failover-renewal-loop-mutation-red-20261021.log`. The class teardown also
failed in that run, because Vert.x was closed under the teardown of a failed test (§9.11). The
source was restored to the same SHA-256, rebuilt, and the class passed 13 of 13:
`logs/peegeeq-pg-failover-renewal-loop-restored-20261021.log`.

**Guard.** `OnSuccessExceptionSwallowingGuardTest` first failed on a `WebClient.close()` inside
`.onSuccess` in the new teardown (Tier 2). The close moved into `.eventually(...)`. The guards
then passed: `logs/peegeeq-test-support-core-guards-loop2-20261021.log`.

**Module runs after the last source change.** Rebuild:
`logs/rebuild-peegeeq-pg-failover-loop3-20261021.log`. Every class reports zero failures, errors,
and skips.

| Profile | Class | Tests run | Saved log under `logs/` |
|---|---|---:|---|
| `integration-tests` | `ConsulLeaseCoordinatorIntegrationTest` | 61 | `peegeeq-pg-failover-integration-loop-20261021.log` |
| `integration-tests` | `PgFailoverMonitorIntegrationTest` | 20 | `peegeeq-pg-failover-integration-loop-20261021.log` |
| `integration-tests` | `PgCtlProcessControlIntegrationTest` | 10 | `peegeeq-pg-failover-integration-loop-20261021.log` |
| `integration-tests` | `PgHbaAdmissionGateIntegrationTest` | 6 | `peegeeq-pg-failover-integration-loop-20261021.log` |
| `integration-tests` | `PgPrimaryElectorQueueingIntegrationTest` | 4 | `peegeeq-pg-failover-integration-loop-20261021.log` |
| `integration-tests` | `PgRenewalLoopIntegrationTest` | 13 | `peegeeq-pg-failover-integration-loop-20261021.log` |
| default/core | `LocalCommandRunnerTest` | 6 | `peegeeq-pg-failover-core-loop-20261021.log` |
| default/core | `PgLocalStateStoreTest` | 29 | `peegeeq-pg-failover-core-loop-20261021.log` |
| default/core | `PgNodeConfigTest` | 17 | `peegeeq-pg-failover-core-loop-20261021.log` |
| default/core guards | five guard classes | 14 | `peegeeq-test-support-core-guards-loop2-20261021.log` |

The mutation run and the restored run followed this table. The restored run repeats the
`PgRenewalLoopIntegrationTest` count.

Known gaps after 2026-10-21:

- No production caller constructs or starts `PgRenewalLoop`. The supervisor entry point that
  acquires the lease, starts the loop, and then starts PostgreSQL does not exist.
- The mid-run path where a timer cannot be scheduled has no test. It demotes the writer and ends
  the loop. The start-time path has a test.
- No test shows a waiting renewal that misses its freshness deadline. By reading, the late reply
  is rejected and the monitor demotes; this was not run.
- The elector's null-result check in `bounded` is not reached by a coordinator that returns a
  null Future, because each caller calls `.map` on the result first. The failure is still
  reported and still demotes. The check is dead code on these paths.
- The `PgFailoverMonitorIntegrationTest` cases still never run the loop; the loop has its own class.

### 9.13 Optional Watchdog Modes, 2026-10-21

**Probe.** A privileged Alpine container on the development machine reported kernel
`6.18.54.1-microsoft-standard-WSL2`, `CONFIG_WATCHDOG=y`, `CONFIG_SOFT_WATCHDOG=m`, no loaded
watchdog module, and no `/dev/watchdog` (`logs/probe-docker-watchdog-20261021.log`). No module
was loaded and no device was opened. The owner decided OD-5 (§5.3).

**TDD.** `PgDeviceWatchdogTest` (7 core tests, real directory, no device) and three mode tests in
`PgFailoverMonitorIntegrationTest` were written first. The module's tests failed compilation
(`logs/peegeeq-pg-failover-watchdog-red-20261021.log`). After the implementation both classes
passed on their first run: 7 of 7 and 23 of 23
(`logs/peegeeq-pg-failover-watchdog-core-run1-20261021.log`,
`logs/peegeeq-pg-failover-monitor-watchdog-run1-20261021.log`).

The adapter tests cover: an absent device, an unreadable timeout, a malformed timeout rejected
before the device is opened, a timeout that is not positive, keepalive while not active, a failed
activation that leaves the watchdog inactive, and disarm while not active. The mode tests cover:
`off` starts and renews a writer with no device and no ERROR; `automatic` with no device starts
a writable primary and reports the absence once; `required` with no device refuses the start,
leaves PostgreSQL stopped, records no start receipt, and keeps ownership.

**Mutation check.** With `required` handled like `automatic`, the `required` test failed: "A
writer started in required mode without a watchdog"
(`logs/peegeeq-pg-failover-watchdog-mutation-red-20261021.log`). The same run reported the
teardown interruption after a failed test (§9.11). The source was restored to the same SHA-256.

**Module runs after the last source change.** Rebuild:
`logs/rebuild-peegeeq-pg-failover-watchdog-final-20261021.log`. Every class reports zero
failures, errors, and skips.

| Profile | Class | Tests run | Saved log under `logs/` |
|---|---|---:|---|
| `integration-tests` | `ConsulLeaseCoordinatorIntegrationTest` | 61 | `peegeeq-pg-failover-integration-watchdog-20261021.log` |
| `integration-tests` | `PgFailoverMonitorIntegrationTest` | 23 | `peegeeq-pg-failover-integration-watchdog-20261021.log` |
| `integration-tests` | `PgCtlProcessControlIntegrationTest` | 10 | `peegeeq-pg-failover-integration-watchdog-20261021.log` |
| `integration-tests` | `PgHbaAdmissionGateIntegrationTest` | 6 | `peegeeq-pg-failover-integration-watchdog-20261021.log` |
| `integration-tests` | `PgPrimaryElectorQueueingIntegrationTest` | 4 | `peegeeq-pg-failover-integration-watchdog-20261021.log` |
| `integration-tests` | `PgRenewalLoopIntegrationTest` | 13 | `peegeeq-pg-failover-integration-watchdog-20261021.log` |
| default/core | `LocalCommandRunnerTest` | 6 | `peegeeq-pg-failover-core-watchdog-20261021.log` |
| default/core | `PgDeviceWatchdogTest` | 7 | `peegeeq-pg-failover-core-watchdog-20261021.log` |
| default/core | `PgLocalStateStoreTest` | 29 | `peegeeq-pg-failover-core-watchdog-20261021.log` |
| default/core | `PgNodeConfigTest` | 17 | `peegeeq-pg-failover-core-watchdog-20261021.log` |
| default/core guards | five guard classes | 14 | `peegeeq-test-support-core-guards-watchdog-20261021.log` |

Known gaps after 2026-10-21:

- No test arms a watchdog. Every path that needs an armed watchdog is unverified: a second
  activation, keepalive after renewal, demotion on a failed keepalive, disarm after a confirmed
  stop, an armed and unfed watchdog after an unconfirmed stop, the safe-timeout check with a real
  timeout, and disarm after an unsafe timeout. They run on a Linux host with a watchdog driver.
- The mode is applied at primary start only. Promotion is 7b.3.
- `automatic` reports an unavailable watchdog at every primary start. It does not retry later.
- A kernel built with `nowayout` ignores magic close. The adapter cannot detect that; a disarm
  then succeeds while the device stays armed.
- No configuration loader reads the `peegeeq.pg.failover.watchdog.*` keys. The device and
  timeout paths are constructor arguments.

### 9.14 First-Start Bootstrap, Scope A, 2026-10-21

**Order of steps.** A receipt needs a generation, and a generation exists only after the
initial acquisition. The `bootstrap` receipt is therefore written directly after acquisition and
before the start effect. Before acquisition the request file is the only evidence; a run that
stops there leaves nothing, and a rerun repeats it.

**TDD.** `PgBootstrapRequestTest` (14 core tests, real directory) and `PgBootstrapIntegrationTest`
(7 tests, real Consul, a real PostgreSQL that only the supervisor starts, real node-local
storage) were written first. The module's tests failed compilation
(`logs/peegeeq-pg-failover-bootstrap-red-20261021.log`). After the implementation both classes
passed on their first run: 14 of 14 and 7 of 7
(`logs/peegeeq-pg-failover-bootstrap-core-run1-20261021.log`,
`logs/peegeeq-pg-failover-bootstrap-integration-run1-20261021.log`).

The request tests cover a valid request and its derived intent, membership in another order, and
rejection of a missing file, a file that is not JSON, an unknown field, a missing field, another
cluster, another incarnation, another membership, another initial writer, a standby list naming
the writer, naming a non-member, or repeating a member, and an operation that is not a UUID. The
integration tests cover: a valid request starts a writable primary that rejects application
connections, under withdrawn intent with pending revision 1, no confirmed policy, and no
previous writer, with a `PENDING` bootstrap receipt holding the request, a `COMPLETED`
start-primary receipt, and a running loop; without a request file no record is created and
nothing starts; an existing record of another operation refuses bootstrap and is not changed; a
rerun after an interrupted bootstrap is refused as interrupted and starts nothing; a stored grant
refuses bootstrap; quarantine refuses bootstrap; a failed start demotes, stops the loop, and
leaves the record and the `PENDING` receipt.

**Mutation check.** With the local-history check removed, a node with a stored grant
bootstrapped and its test failed: "Bootstrap ran on a node with a stored writer grant". The
quarantine test also failed, because the monitor's own quarantine check refused the start with a
`PgLocalStateException` instead of a bootstrap refusal
(`logs/peegeeq-pg-failover-bootstrap-mutation-red-20261021.log`). The source was restored to the
same SHA-256.

**Module runs after the last source change.** Rebuild:
`logs/rebuild-peegeeq-pg-failover-bootstrap-final-20261021.log`. Every class reports zero
failures, errors, and skips.

| Profile | Class | Tests run | Saved log under `logs/` |
|---|---|---:|---|
| `integration-tests` | `ConsulLeaseCoordinatorIntegrationTest` | 61 | `peegeeq-pg-failover-integration-bootstrap-20261021.log` |
| `integration-tests` | `PgFailoverMonitorIntegrationTest` | 23 | `peegeeq-pg-failover-integration-bootstrap-20261021.log` |
| `integration-tests` | `PgBootstrapIntegrationTest` | 7 | `peegeeq-pg-failover-integration-bootstrap-20261021.log` |
| `integration-tests` | `PgCtlProcessControlIntegrationTest` | 10 | `peegeeq-pg-failover-integration-bootstrap-20261021.log` |
| `integration-tests` | `PgHbaAdmissionGateIntegrationTest` | 6 | `peegeeq-pg-failover-integration-bootstrap-20261021.log` |
| `integration-tests` | `PgPrimaryElectorQueueingIntegrationTest` | 4 | `peegeeq-pg-failover-integration-bootstrap-20261021.log` |
| `integration-tests` | `PgRenewalLoopIntegrationTest` | 13 | `peegeeq-pg-failover-integration-bootstrap-20261021.log` |
| default/core | `LocalCommandRunnerTest` | 6 | `peegeeq-pg-failover-core-bootstrap-20261021.log` |
| default/core | `PgBootstrapRequestTest` | 14 | `peegeeq-pg-failover-core-bootstrap-20261021.log` |
| default/core | `PgDeviceWatchdogTest` | 7 | `peegeeq-pg-failover-core-bootstrap-20261021.log` |
| default/core | `PgLocalStateStoreTest` | 29 | `peegeeq-pg-failover-core-bootstrap-20261021.log` |
| default/core | `PgNodeConfigTest` | 17 | `peegeeq-pg-failover-core-bootstrap-20261021.log` |
| default/core guards | five guard classes | 14 | `peegeeq-test-support-core-guards-bootstrap-20261021.log` |

Known gaps after 2026-10-21:

- The request is not operator-authenticated (OD-6). Anyone who can write the state directory can
  request bootstrap.
- Other members are not inspected (OD-7).
- Existing local receipts are not inspected: the store has no listing operation. The data
  directory is not checked for prior writes; a stored grant or quarantine is the only local
  history checked.
- An interrupted bootstrap is refused, not resumed. Resuming needs the takeover path (7b.3).
- Scope B is not implemented, so bootstrap never confirms a policy or admits applications.
- No production entry point calls `PgBootstrap`.

### 9.15 First-Start Bootstrap, Scope B Increment B1: the Durability Boundary, 2026-10-22

**Decisions.** The owner decided OD-9 to OD-12 (§5.3). Scope B is split into two increments:
B1 is the durability port, its adapter, and the policy and coverage rule; B2 is the bootstrap
completion that uses them.

**Data model.** Nothing new is stored. `PgDurabilityPolicy` is the typed form of the control
record's `durabilityPolicy` and `pendingDurabilityPolicy`, which stay the source of truth. The
generated `synchronous_standby_names`, the acknowledgement count, WAL positions (`PgLsn`), and
peer observations (`PgReplicationPeer`) are derived on each check.

**Fixture.** `PgReplicationFixture` runs the writer and two standbys in three containers on one
network. Each provisioning does what OD-10 assigns to the deployment: `initdb` on the writer,
one start with application admission closed to create one replication role per standby, a base
backup per standby under its own role with `standby.signal`, then a stop.
`PgSupervisedContainer` gained a constructor that places a node on a network under its node
identity.

**Finding: a double quote in a command argument.** The first run failed in provisioning with
`syntax error at or near "-"`: the role name `"pg-node-2"` reached `psql` without its quotes. On
Windows, the Java process launcher does not escape a double quote inside an argument, so the child
process loses it. The supervisor's own `ALTER SYSTEM` statement had the same exposure. The
adapter now sends the value as an escape string (`E'ANY 2 (\x22pg-node-2\x22, ...)'`), and the
fixture creates roles with `format('%I')` in a `DO` block. No command argument in the module
contains a double quote. `LocalCommandRunner` is unchanged; on Linux the argument list passes
unchanged.

**Finding: an unquoted hyphenated name is rejected at server start.** The first override test
started the writer with `-c synchronous_standby_names=pg-node-2`. PostgreSQL refused to start,
because an unquoted standby name cannot contain a hyphen. The test now overrides with
`elsewhere`. This is the reason the design requires quoted names (§5.8).

**TDD.** `PgLsnTest` (3 core tests), `PgDurabilityPolicyTest` (12 core tests), and
`PgSqlDurabilityControlIntegrationTest` (10 tests, three real PostgreSQL nodes that only the test
starts) were written first. The module's tests failed compilation
(`logs/peegeeq-pg-failover-bootstrap-b1-red-20261022.log`). The core classes passed on their
first run (`logs/peegeeq-pg-failover-bootstrap-b1-core-run1-20261022.log`). The integration class
failed in provisioning on runs 1 and 2 for the two findings above
(`logs/peegeeq-pg-failover-bootstrap-b1-integration-run1-20261022.log`, `-run2-`) and passed 10
of 10 on run 3 (`-run3-`).

The policy tests cover the generated quoted value; rejection of an empty, repeated,
case-colliding, or invalid standby set and of revision 0; the control record form and its
rejection of other fields; and the coverage rule: every standby streaming under its own role at
the boundary passes; a missing standby, two connections for one standby, a standby name under
another role, a name that differs only in case, a standby role under another name, a standby that
is not streaming, and a standby behind the boundary in flush or replay each fail. The adapter
tests cover: the flush boundary equals the writer's flush position and advances with a write; a
server in recovery has no flush boundary; each standby replicates under its own role and reaches
the boundary; installation puts the quoted setting in effect, both peers report `quorum`, and the
value is persisted in `postgresql.auto.conf`; a missing required peer fails the installation
within its budget; a server-start option that overrides the setting is reported as a mismatch; a
stopped server fails the installation; a command that cannot run fails and is logged; output that
is not an observation is rejected; an exhausted budget fails before any command runs.

**Mutation check.** With the setting comparison and the quorum wait removed from the adapter, the
missing-peer test failed ("A policy was confirmed without a required peer") and the override test
failed ("The asynchronous result was expected to have failed")
(`logs/peegeeq-pg-failover-bootstrap-b1-mutation-red-20261022.log`, `-red-2-`). The first mutation
run did not reach the override check: the previous failure's teardown was interrupted (§9.11) and
left the writer running, so the next provisioning could not start. The override test was then run
alone. The source was restored to the same SHA-256.

**Module runs after the last source change.** Rebuild:
`logs/rebuild-peegeeq-pg-failover-bootstrap-b1-final-20261022.log`. Every class reports zero
failures, errors, and skips.

| Profile | Class | Tests run | Saved log under `logs/` |
|---|---|---:|---|
| `integration-tests` | `ConsulLeaseCoordinatorIntegrationTest` | 61 | `peegeeq-pg-failover-integration-bootstrap-b1-20261022.log` |
| `integration-tests` | `PgFailoverMonitorIntegrationTest` | 23 | `peegeeq-pg-failover-integration-bootstrap-b1-20261022.log` |
| `integration-tests` | `PgBootstrapIntegrationTest` | 7 | `peegeeq-pg-failover-integration-bootstrap-b1-20261022.log` |
| `integration-tests` | `PgCtlProcessControlIntegrationTest` | 10 | `peegeeq-pg-failover-integration-bootstrap-b1-20261022.log` |
| `integration-tests` | `PgHbaAdmissionGateIntegrationTest` | 6 | `peegeeq-pg-failover-integration-bootstrap-b1-20261022.log` |
| `integration-tests` | `PgPrimaryElectorQueueingIntegrationTest` | 4 | `peegeeq-pg-failover-integration-bootstrap-b1-20261022.log` |
| `integration-tests` | `PgRenewalLoopIntegrationTest` | 13 | `peegeeq-pg-failover-integration-bootstrap-b1-20261022.log` |
| `integration-tests` | `PgSqlDurabilityControlIntegrationTest` | 10 | `peegeeq-pg-failover-integration-bootstrap-b1-20261022.log` |
| default/core | `LocalCommandRunnerTest` | 6 | `peegeeq-pg-failover-core-bootstrap-b1-20261022.log` |
| default/core | `PgBootstrapRequestTest` | 14 | `peegeeq-pg-failover-core-bootstrap-b1-20261022.log` |
| default/core | `PgDeviceWatchdogTest` | 7 | `peegeeq-pg-failover-core-bootstrap-b1-20261022.log` |
| default/core | `PgDurabilityPolicyTest` | 12 | `peegeeq-pg-failover-core-bootstrap-b1-20261022.log` |
| default/core | `PgLocalStateStoreTest` | 29 | `peegeeq-pg-failover-core-bootstrap-b1-20261022.log` |
| default/core | `PgLsnTest` | 3 | `peegeeq-pg-failover-core-bootstrap-b1-20261022.log` |
| default/core | `PgNodeConfigTest` | 17 | `peegeeq-pg-failover-core-bootstrap-b1-20261022.log` |
| default/core guards | five guard classes | 14 | `peegeeq-test-support-core-guards-bootstrap-b1-20261022.log` |

Integration total 134; core total 88. The guard run executed 61 tests in `peegeeq-test-support`,
14 of them in the five guard classes.

Known gaps after 2026-10-22:

- Nothing calls `PgDurabilityControl`. Installation has no receipt and is not serialized with
  other local effects until B2 puts it in `PgFailoverMonitor`.
- A failed installation leaves the installed value in `postgresql.auto.conf` and in effect. With
  a required peer missing, later commits wait; admission must stay closed, as in the gate's gap
  after a failed reload (§9.11).
- The coverage rule checks role and application name. It does not check the client address
  against the node's configured host, and it does not compare standby timeline or node-local
  `peegeeq.node_id`; the writer observes standbys only through `pg_stat_replication` (OD-7).
- Reading `pg_stat_replication` positions requires a superuser or `pg_monitor` role on the local
  socket. The fixture uses the superuser. The least-privilege supervisor role is not defined.
- The adapter's check for a runner that returns no result has no test, because the real runner
  never returns none (as for the gate).
- A failed test in a class that uses `PgReplicationFixture` can leave servers running and fail the
  later tests of the class (§9.11, interrupted teardown).

### 9.16 First-Start Bootstrap, Scope B Increment B2, Step 1: Guarded Policy Installation, 2026-10-24

**Scope.** B2 is split into steps. This step installs the pending policy on the running writer
under a receipt, with admission closed. Revision-1 confirmation, prepare/publish/activate, and the
production-manager write are the remaining steps. No owner decision was needed. The implementer
chose three defaults, which the owner can reverse: the coverage wait lives in the durability
adapter and uses its budget; the installed policy is derived from the held record, not supplied;
and a coverage failure is retried until the budget ends whatever its cause.

**Data model.** Nothing new is stored. The policy to install is the held record's
`pendingDurabilityPolicy`, which stays the source of truth. The node-local store gains one action
name, `install-policy`: its parameters are the policy (revision and standby set), its result is
`COMPLETED` or `UNKNOWN`, and its effect is empty. The flush boundary, peer observations, and the
installed setting are derived on each call.

**Contracts.**

- `PgDurabilityControl.confirmCovered(policy)` observes one fresh writer flush boundary, then polls
  the replication peers until `PgDurabilityPolicy.requireCoveredAt` passes. A failed command or a
  malformed observation ends the wait at once. A coverage failure ends the wait only when another
  observation would not fit in the budget; the failure then names the boundary and the last
  uncovered reason.
- `PgFailoverMonitor.installPendingPolicy(held)` runs one at a time with the other local effects.
  It is refused without fresh ownership of exactly `held`, without `WITHDRAWN` intent, without a
  pending policy, and under quarantine. It then closes the grant and the gate, confirms coverage,
  begins the `install-policy` receipt, and installs. A refusal or a coverage failure before the
  receipt changes nothing and records no receipt. A failed installation records `UNKNOWN` and
  fails; admission stays closed. A successful installation records `COMPLETED`. Ownership lost
  during the installation fails the call after `COMPLETED` is recorded. A receipt with equal
  parameters is reused, so a rerun after `UNKNOWN` completes the same receipt and a rerun after
  `COMPLETED` is idempotent. A receipt with other parameters refuses the installation.
- The control record is not changed. `PgFailoverMonitor` takes a `PgDurabilityControl` as its
  seventh constructor argument; the 11 existing call sites were updated.

**Dependency failure modes, each with a test.**

| Dependency | Failure mode | Test |
|---|---|---|
| Elector | not the held record, or ownership retired | `installationWithoutOwnershipIsRefusedAndRecordsNothing` |
| Control record | intent not `WITHDRAWN`; no pending policy | `installationWhileTheIntentIsNotWithdrawnIsRefused`, `installationWithoutAPendingPolicyIsRefused` |
| Node-local store | quarantine; receipt with other parameters | `aQuarantinedNodeCannotInstallAPolicy`, `aReceiptWithDifferentParametersRefusesTheInstallationAndInstallsNothing` |
| Grant and gate | open before the call | `installationClosesAnOpenGrantAndAdmissionBeforeItChangesAnything` |
| `confirmCovered` | a required standby never connects; the server is in recovery; the command cannot run; output is not an observation; no time remains | `aRequiredStandbyThatNeverConnectsFailsTheInstallationBeforeAnyReceiptOrSetting`; in `PgSqlDurabilityControlIntegrationTest`: `aRequiredStandbyThatIsNotConnectedFailsCoverageWithinItsBudgetAndNamesTheStandby`, `coverageOfAServerInRecoveryFailsBecauseItHasNoBoundary`, `aCommandThatCannotRunFailsCoverageAndIsNotRetried`, `coverageObservedFromOutputThatIsNotAnObservationIsRejected`, `coverageWithoutTimeToRunFailsPromptly` |
| `install` | the setting in effect differs from the installed one | `aFailedInstallationRecordsAnUnknownReceiptAndLeavesAdmissionClosed` |
| Receipt | `UNKNOWN` from an earlier run; `COMPLETED` from an earlier run | `aRerunAfterAnUnknownReceiptCompletesTheSameReceipt`, `installingTwiceIsIdempotent` |
| Ownership | lost after the installation took effect | `ownershipLostWhileThePolicyIsInstalledFailsTheCallAndKeepsAdmissionClosed` |

**TDD.** The tests were written first. The module's tests failed compilation on the missing
members (`logs/peegeeq-pg-failover-policy-install-red-20261024.log`). After the implementation the
new class passed 11 of 11 on its first run (`logs/peegeeq-pg-failover-policy-install-run1-20261024.log`).
That run showed no test failed when the monitor skipped closing the grant and the gate, because
every test starts with admission closed. The test `installationClosesAnOpenGrantAndAdmissionBeforeItChangesAnything`
was added for it.

**Mutation checks.** Each temporary change was restored and the checksum of the restored file
equals the one recorded before the change. A combined run of the first four changes produced one
valid failure; the other three tests then failed in provisioning because the first failure left
servers running (§9.11), so each was run alone.

| Temporary change | Result | Log under `logs/` |
|---|---|---|
| The final ownership check removed | `ownershipLost...` failed: "An installation succeeded after ownership was lost" | `peegeeq-pg-failover-policy-install-mutation-red-20261024.log` |
| The grant and gate closure removed | the closure test failed: "The grant stayed open ==> expected: CLOSED but was: OPEN" | `peegeeq-pg-failover-policy-install-mutation-red-a-20261024.log` |
| The coverage step removed from the monitor | `aRequiredStandbyThatNeverConnects...` failed: the failure came from the quorum wait after the install began, not from coverage | `peegeeq-pg-failover-policy-install-mutation-red-b-20261024.log` |
| `UNKNOWN` replaced by `COMPLETED` after a failed install | `aFailedInstallation...` failed: "expected: UNKNOWN but was: COMPLETED" | `peegeeq-pg-failover-policy-install-mutation-red-c-20261024.log` |
| The coverage check removed from the adapter | the missing-standby port test failed: "Coverage was confirmed without a required standby" | `peegeeq-pg-failover-confirm-covered-mutation-red-20261024.log` |

**Runs after the last source change.** Rebuild:
`logs/rebuild-peegeeq-pg-failover-policy-install-final2-20261024.log`. Every class reports zero
failures, errors, and skips.

| Profile | Class | Tests run | Saved log under `logs/` |
|---|---|---:|---|
| `integration-tests` | `ConsulLeaseCoordinatorIntegrationTest` | 61 | `peegeeq-pg-failover-integration-policy-install-20261024.log` |
| `integration-tests` | `PgFailoverMonitorIntegrationTest` | 23 | same |
| `integration-tests` | `PgBootstrapIntegrationTest` | 7 | same |
| `integration-tests` | `PgCtlProcessControlIntegrationTest` | 10 | same |
| `integration-tests` | `PgHbaAdmissionGateIntegrationTest` | 6 | same |
| `integration-tests` | `PgPolicyInstallationIntegrationTest` | 12 | same |
| `integration-tests` | `PgPrimaryElectorQueueingIntegrationTest` | 4 | same |
| `integration-tests` | `PgRenewalLoopIntegrationTest` | 13 | same |
| `integration-tests` | `PgSqlDurabilityControlIntegrationTest` | 16 | same |
| default/core | seven classes | 88 | `peegeeq-pg-failover-core-policy-install-20261024.log` |
| default/core guards | `peegeeq-test-support`, 61 tests including the five guard classes (14) | 61 | `peegeeq-test-support-core-guards-policy-install-20261024.log` |

Integration total 152; core total 88.

**Known gaps after 2026-10-24.**

- Nothing calls `installPendingPolicy`. `PgBootstrap` does not install, confirm, prepare, publish, or
  activate, and no production entry point exists.
- A coverage failure that cannot resolve, such as a standby connected under the wrong role, is
  retried until the budget ends instead of failing at once.
- No test starts with a standby that is behind the boundary and catches up during the wait. The
  tests cover a standby that is covered at the first observation and one that never connects.
- Ownership loss is tested with a test decorator over the real durability control that retires the
  elector after the install. It is not tested with a real lease expiry.
- A failed installation leaves the installed setting in `postgresql.auto.conf` and in effect, as in
  §9.15. Admission stays closed, and commits wait while a required peer is missing.
- A storage fault of the node-local store while the receipt is begun or recorded has no test beyond
  the rule-based refusal (a receipt with other parameters).
- The IDE analysis reports a cognitive complexity of 21 (limit 15) for `installPendingPolicy`. It
  reports 19 and 20 for `startPrimary` and `activateWriter`. None was refactored.
- A failed test in `PgPolicyInstallationIntegrationTest` leaves servers running and fails the later
  tests of the class (§9.11); the mutation runs showed it.
- No other module and no Jenkins build was run.
- Correction, 2026-10-24: the `@BeforeEach` of `PgSqlDurabilityControlIntegrationTest` and of this
  class ran under a 30-second limit that no test had set. See §9.17.
- Superseded, 2026-10-24: §9.18 checks every gap of this list. Four gaps are closed (the coverage
  failure that cannot resolve, the standby that connects during the wait, the real lease expiry,
  and the storage faults). The rest are stated there with their evidence.

### 9.17 First-Start Bootstrap, Scope B Increment B2, Step 2: Confirmation, Publication, and Activation, 2026-10-24

**Scope.** `PgBootstrap.serve(held, mode)` completes first-start bootstrap on the writer that
`start` left running with admission closed. `start` is unchanged, so the scope A tests are
unchanged. No owner decision was needed. The mode is a parameter because `PgNodeConfig` holds no
failover mode. The production-manager write is the remaining step.

**Data model.** Nothing new is stored. The control record's `pendingDurabilityPolicy` becomes its
`durabilityPolicy` at confirmation, and its `phase` becomes `SERVING` at publication. The grant and
the `install-policy` receipt are as in §9.16. The `bootstrap` receipt keeps no result: completion is
derived from the confirmed policy, the `SERVING` intent, and the open grant, as §5.11 requires.

**Contract.** In order, each step starting only after the earlier one succeeded:

1. `installPendingPolicy(held)` (§9.16).
2. `PgPrimaryElector.update` writes the confirmation: the pending policy moves to `durabilityPolicy`;
   the phase stays `WITHDRAWN`.
3. `prepareWriter(confirmed, mode)` creates the prepared grant for revision 1.
4. `PgPrimaryElector.update` writes the `SERVING` intent.
5. `activateWriter(serving, prepared)` opens the matching grant and the gate.

A failure at any step stops the sequence and is logged once at ERROR by `PgBootstrap`. Admission
stays closed. The record stays where the failed step left it: before step 2 it is withdrawn with
the pending policy; after step 2 it is withdrawn with the confirmed policy and a prepared grant;
after step 4 it is `SERVING` with a prepared grant. A failed gate open closes the grant (§9.11). A
failed update retires the elector's ownership (§5.3), so the renewal loop demotes the writer.

**Dependency failure modes, each with a test** (`PgBootstrapServingIntegrationTest`).

| Dependency | Failure mode | Test |
|---|---|---|
| `installPendingPolicy` | a required standby never connects | `aRequiredStandbyThatNeverConnectsLeavesTheIntentWithdrawnAndAdmissionClosed` |
| Coordinator update | the confirmation is not written | `aFailedConfirmationLeavesThePolicyPendingAndAdmissionClosed` |
| Coordinator update | the `SERVING` publication is not written | `aFailedPublicationLeavesAPreparedGrantUnopenedAndAdmissionClosed` |
| Admission gate | the gate does not open | `aGateThatDoesNotOpenClosesTheGrantAndAdmitsNothing` |
| Elector | serving a record that already serves | `servingTwiceIsRefusedAndLeavesTheServingIntentUnchanged` |
| All steps | none fails | `servingInstallsConfirmsPreparesPublishesAndOpensAdmissionForRevisionOne` |

The normal-path test reads the record back from the coordinator, checks the open grant and its
revision, checks the installed setting, and commits a write over the application route under the
installed policy. The coordinator failures use `ObservedLeaseCoordinator`, a wrapper over the real
Consul adapter.

**TDD.** The tests were written first; the module's tests failed compilation on `serve`. After the
implementation the class passed 6 of 6 on its first run
(`logs/peegeeq-pg-failover-bootstrap-serving-run1-20261024.log`).

**Mutation checks.** Each file was restored and its checksum equals the one recorded before.

| Temporary change | Result | Log under `logs/` |
|---|---|---|
| The installation step removed from `serve` | the coverage-failure test failed: "Admission opened without a required standby" | `peegeeq-pg-failover-bootstrap-serving-mutation-red-a-20261024.log` |
| The activation step removed from `serve` | the normal-path test failed: "expected: OPEN but was: PREPARED" | `peegeeq-pg-failover-bootstrap-serving-mutation-red-b-20261024.log` |

**Finding: the Vert.x test-context limit.** The full integration run failed in
`PgSqlDurabilityControlIntegrationTest`: the `@BeforeEach` of its first test exceeded 30 seconds,
and the other tests of the class failed in provisioning after it (§9.11)
(`logs/peegeeq-pg-failover-integration-bootstrap-serving-20261024.log`). The class alone failed the
same way (`logs/peegeeq-pg-failover-durability-rerun-20261024.log`), and a single test of it passed
in 25.5 seconds (`logs/peegeeq-pg-failover-durability-single-20261024.log`). `VertxExtension`
reads `io.vertx.junit5.Timeout` from the executing method and class (checked with `javap` on
`vertx-junit5` 5.0.4). The classes here import JUnit's `@Timeout`, which the extension does not read, so
every `@BeforeEach` and test body ran under the 30-second default. The setup of that class
(provisioning, then starting the writer and both standbys) took more than 30 seconds in two runs
and passed in one single-test run of 25.5 seconds including container start. Three Kubernetes
containers of another project were running on the host; whether they slowed the setup was not
measured. The cause is the missing setup limit, not this step's code: the class is unchanged by this
step, and it passed with the limit raised. Fix: `@io.vertx.junit5.Timeout(120 seconds)` on the
`@BeforeEach` of `PgSqlDurabilityControlIntegrationTest`, `PgPolicyInstallationIntegrationTest`, and
`PgBootstrapServingIntegrationTest`. Test bodies keep the 30-second default. The other fixture
classes (`PgBootstrapIntegrationTest`, `PgFailoverMonitorIntegrationTest`, `PgRenewalLoopIntegrationTest`,
`PgCtlProcessControlIntegrationTest`, `PgHbaAdmissionGateIntegrationTest`) run under the same default and
were not changed; they passed in every run of this step.

**Runs after the last source change.** Rebuild:
`logs/rebuild-peegeeq-pg-failover-setup-timeout-20261024.log`. Every class reports zero failures,
errors, and skips.

| Profile | Class | Tests run | Saved log under `logs/` |
|---|---|---:|---|
| `integration-tests` | `ConsulLeaseCoordinatorIntegrationTest` | 61 | `peegeeq-pg-failover-integration-bootstrap-serving-run2-20261024.log` |
| `integration-tests` | `PgFailoverMonitorIntegrationTest` | 23 | same |
| `integration-tests` | `PgBootstrapIntegrationTest` | 7 | same |
| `integration-tests` | `PgBootstrapServingIntegrationTest` | 6 | same |
| `integration-tests` | `PgCtlProcessControlIntegrationTest` | 10 | same |
| `integration-tests` | `PgHbaAdmissionGateIntegrationTest` | 6 | same |
| `integration-tests` | `PgPolicyInstallationIntegrationTest` | 12 | same |
| `integration-tests` | `PgPrimaryElectorQueueingIntegrationTest` | 4 | same |
| `integration-tests` | `PgRenewalLoopIntegrationTest` | 13 | same |
| `integration-tests` | `PgSqlDurabilityControlIntegrationTest` | 16 | same |
| default/core | seven classes | 88 | `peegeeq-pg-failover-core-bootstrap-serving-20261024.log` |

Integration total 158; core total 88.

**Known gaps after 2026-10-24.**

- No production entry point calls `serve`, and no step restarts an interrupted `serve`. A rerun of
  `start` is refused (§9.14), and a second `serve` on a serving record is refused.
- A failed `serve` leaves the record at the step that failed. No step reverts it. After a failed
  publication the record holds the confirmed policy with a prepared grant, and nothing resumes it.
- The coordinator failures are injected into the real adapter's update call. They are not real
  network faults.
- A lease lost between steps is covered only through the injected update failure.
- The commit under the installed policy is a `psql` statement over the application route. It is not
  the production-manager write (OD-12), which is the remaining step.
- The two new test classes and the B1 class have no assertion on how long provisioning takes, so a
  slower host can still exceed the 120-second setup limit.
- No other module and no Jenkins build was run.
- Superseded, 2026-10-24: §9.18 checks every gap of this list and the list of §9.16.

### 9.18 Check of the Gaps Listed in §9.16 and §9.17, 2026-10-24

Every gap listed in §9.16 and §9.17 was checked by a test, a measurement, or a search of the
repository. Nothing is left as "not checked" except the items stated under "Not closed" below.

**Closed by a change and a test.**

| Gap | Result | Evidence |
|---|---|---|
| A coverage failure that cannot resolve was retried until the budget ended | `PgStandbyNotCoveredException` (a subclass of `PgDurabilityException`) marks the conditions that change by waiting: not connected, several connections, not streaming, behind the boundary. An identity mismatch has the base type and ends the wait at once. The adapter retries only the subclass. | Core: `conditionsThatChangeByWaitingAreDistinguishedFromIdentityMismatches` (`PgDurabilityPolicyTest` 13). Real nodes: `aStandbyReplicatingUnderAnotherNameFailsCoverageAtOnceNotAtTheEndOfTheBudget`; a standby is made to replicate as `imposter` by `ALTER SYSTEM SET primary_conninfo`. Mutation: with the old catch the test failed "The mismatch was retried until the budget ended" (`logs/peegeeq-pg-failover-checks-m1-red-20261024.log`) |
| No test of a standby that connects during the wait | `aStandbyThatConnectsDuringTheWaitIsConfirmedCovered`: a standby is stopped, coverage starts, and the standby starts during the wait. | Mutation: with a single observation the test failed with "pg-node-3 is not connected" (`logs/peegeeq-pg-failover-checks-m2-red-20261024.log`) |
| Ownership loss was tested only with a decorator | Two tests wait for the real freshness deadline (ownership budget 22 seconds, no renewal): `ownershipThatExpiredInRealTimeBeforeServingRefusesItAndAdmitsNothing`, and `ownershipThatExpiresInRealTimeDuringTheInstallationStopsBeforeConfirmation`. | Mutation: without the ownership check the first test failed "expected PgLeaseProtocolException but was PgDurabilityException" (`logs/peegeeq-pg-failover-checks-m5b-red-20261024.log`) |
| Storage faults of the node-local store had no test | `aStorageFaultWhenTheReceiptIsBegunFailsTheInstallationBeforeAnySetting` and `aStorageFaultWhenTheResultIsRecordedFailsTheCallAfterTheEffectAndLeavesThePendingReceipt`. The fault is a directory where the store must write its temporary receipt file, a real `FileSystemException` (`AccessDeniedException` on Windows). A failed record leaves the receipt `PENDING` and the setting in effect. | Mutations: skipping the begin, and skipping the `COMPLETED` record, each failed the tests (`logs/peegeeq-pg-failover-checks-m3-red-20261024.log`, `-m4-red-`) |
| Coordinator failures were injected into the adapter | `aCoordinatorThatStopsAnsweringBeforePublicationLeavesAPreparedGrantUnopenedAndAdmissionClosed` freezes the Consul container (`docker pause`) when the publication is sent. The adapter meets a real `Timeout 2000 (ms) fired`. The record state after an unanswered publication is indeterminate (the frozen server may apply the request later), so the test asserts only the prepared grant and the closed admission. | `logs/peegeeq-pg-failover-checks-run1-20261024.log` line 866 |
| A failed installation leaves the setting in effect and commits wait | The missing-peer test now asserts that `show synchronous_standby_names` still holds the installed value and that a `create table` commit does not complete within 5 seconds. | `logs/peegeeq-pg-failover-durability-final-20261024.log` (18 of 18) |
| Rerun behaviour after a failed `serve` was not stated | `aRerunAfterACoverageFailureCompletesOnceTheMissingStandbyConnects`: a rerun completes. `aRerunAfterAFailedPublicationIsRefusedBecauseOwnershipWasRetired`: a rerun is refused, and the grant stays `PREPARED`. A rerun on a serving record is refused (§9.17). | Mutation: without retirement of ownership after a failed update the second test failed (`logs/peegeeq-pg-failover-checks-m7-red-20261024.log`) |
| Cognitive complexity of `installPendingPolicy` was 21 | Split into `requireNoQuarantine`, `installAndRecord`, and `recordUnknownAndFail`. The IDE analysis no longer reports it. `startPrimary` (19) and the demotion method (20) were reported before this work and are not changed. | The 14 tests of `PgPolicyInstallationIntegrationTest` pass |
| The real-expiry tests ran close to the 30-second test-body limit | Both carry `@io.vertx.junit5.Timeout(120 seconds)` (§9.17 explains the annotation). | A mutation made one fail as a timeout before the annotation was added |

**Closed by measurement or search.**

| Gap | Result |
|---|---|
| No assertion on provisioning time | Per-test totals (setup plus body) from the full run `logs/peegeeq-pg-failover-integration-checks-20261024.log`: the ordinary tests of the three-node classes take 15 to 25 seconds; the two real-expiry tests take 36.1 and 38.0 seconds; the slowest of the older classes are two Consul expiry tests at 20.1 and 20.2 seconds and one renewal-loop test at 18.4 seconds; each waits for an ownership budget or a lease on purpose. The setup limit is 120 seconds and the test-body default is 30 seconds. No test of `PgFailoverMonitorIntegrationTest`, `PgBootstrapIntegrationTest`, `PgCtlProcessControlIntegrationTest`, or `PgHbaAdmissionGateIntegrationTest` took more than 15 seconds (the report lists only tests above 15). |
| Which modules depend on `peegeeq-pg-failover` | None. Only the root `pom.xml` names the module (search of every `pom.xml`). |
| Which code calls `PgBootstrap`, `PgFailoverMonitor`, or `PgRenewalLoop` | Only classes of the module itself (search of every `src/main` tree by class name). No production entry point exists. |
| The `Jenkinsfile` | It does not name `peegeeq-pg-failover` (search), so `ALL_TESTS_START_MODULE` cannot start a resumed run at it. A full run includes it through the root reactor. |
| A failed test leaves servers running and fails later tests (§9.11) | The mechanism is register finding 13: vertx-junit5 5.0.4 does not await an asynchronous `@AfterEach` after a failed test, so the teardown is interrupted. The mutation runs of this step logged `Interrupted while waiting for command: pg_ctl stop` after each deliberate failure. The correction belongs to the cleanup helper of Task 9, correction 5. It is not changed here. |

**Not closed.** Superseded on 2026-10-25: §9.19 closes each item below, except the Jenkins run.

- A runner that returns no result: the adapter, the gate, and the process control each guard against
  it, and no test exists, because the real runner never returns none and a test needs a stub of the
  runner, which the no-mocking rule excludes.
- The production-manager write (OD-12) is the remaining B2 step, not a check.
- No Jenkins build was run. Jenkins builds the pushed branch, and nothing is pushed.
- Provisioning time is bounded by the 120-second limit, not asserted. A host slower than the
  recorded one can still exceed it.
- The red state of this batch: the tests were written first, but the production change was made
  before a compile-red run was saved. The behaviour red is the mutation of the adapter to its earlier
  behaviour (`-m1-red-`).

**Runs after the last source change.** Every class reports zero failures, errors, and skips.

| Profile | Scope | Tests run | Saved log under `logs/` |
|---|---|---:|---|
| `integration-tests` | the whole module, ten classes | 167 | `peegeeq-pg-failover-integration-checks-20261024.log` |
| `integration-tests` | `PgPolicyInstallationIntegrationTest`, rerun after its fault helper changed | 14 | `peegeeq-pg-failover-policy-install-final-20261024.log` |
| `integration-tests` | `PgSqlDurabilityControlIntegrationTest`, rerun after its missing-peer test changed | 18 | `peegeeq-pg-failover-durability-final-20261024.log` |
| default/core | seven classes | 89 | `peegeeq-pg-failover-core-checks-20261024.log` |

### 9.19 Closing the Open Items of §9.18, 2026-10-25

Six items were open at the end of §9.18. Each now has a test or a check that fails when the item is
wrong. One item cannot be run from the workstation: the Jenkins build.

| Item | Result | Evidence under `logs/` |
|---|---|---|
| A failed test leaves servers running and fails the next test (§9.11) | `PgReplicationFixture.provision` and `PgSupervisedContainer.newDataDirectory` stop the cluster an earlier test left running before they provision. Contract class `PgFixtureLeftoverServerContractIntegrationTest` (4 tests). The mechanism is unchanged: vertx-junit5 does not await an asynchronous teardown after a failed test (register finding 13). The fixtures no longer depend on it. | Red: `peegeeq-pg-failover-fixture-contract-red-20261025.log` ("Starting the writer for provisioning failed ... could not start server"). Green: `-green-` |
| A runner that returns no result | The earlier statement that this needs a stub the no-mocking rule excludes was wrong. `NoResultCommandRunner` runs the real command and drops its result, the same shape as `ObservedLeaseCoordinator`. One test each for the process control, the admission gate, and the durability adapter. | Green first run: `peegeeq-pg-failover-no-result-run1-20261025.log`. With the three guards removed all three tests failed: `-no-result-mutation-red-` |
| The red state of the coverage batch was not saved | Rebuilt in an isolated git worktree: the tests of `76208e9c` against the production files of `64a84289`. Compilation fails with `cannot find symbol: class PgStandbyNotCoveredException`. The worktree was removed. | `peegeeq-pg-failover-coverage-batch-red-20261025.log` |
| Production-manager write (OD-12) | `peegeeq-db` is a test-scope dependency of this module; the reactor of this module now builds `peegeeq-api` and `peegeeq-db` upstream. `PgSupervisedContainer` publishes the writer's port. Three tests in `PgBootstrapServingIntegrationTest`: a write before serving is rejected by the closed gate (no row); a write after serving is acknowledged, and both standbys report a flush position at or after the write's WAL position with `quorum` state; a caller that sets `synchronous_commit = off` with one standby stopped still waits in `SyncRep`, and after the backend is cancelled fails with `PgCommitOutcomeUnknownException`, the row being locally committed. The manager connects to the writer directly, not through HAProxy, because the sidecar is role-only. | Red: `rebuild-peegeeq-pg-failover-production-write-red-20261025.log`. Green: `peegeeq-pg-failover-production-write-run1-20261025.log`. With the manager's restore of `synchronous_commit = on` removed the third test failed "The write completed before the missing standby returned": `-production-write-mutation-red-`. `peegeeq-db` has no change. |
| Provisioning time had no assertion | Both fixtures record the duration of their last provisioning. Contract tests assert 90 seconds for three nodes and 30 seconds for a data directory. Measured: 14.1 seconds and 4.2 seconds. The `@BeforeEach` limit stays 120 seconds. | `peegeeq-pg-failover-fixture-contract-final-20261025.log`. With a one-second budget the test failed "Provisioning took 12 s; the budget is 1 s": `peegeeq-pg-failover-budget-mutation-red-20261025.log` |
| The `Jenkinsfile` could not resume at this module | `peegeeq-pg-failover` is in the `ALL_TESTS_START_MODULE` choices. A contract test in `scripts/ci/regression-stages.test.mjs` asserts that every module of the root pom is a choice. `node scripts/ci/regression-stages.mjs java peegeeq-pg-failover` prints `-Pall-tests -pl !:peegeeq-management-ui,!:peegeeq-utilities-ui -rf :peegeeq-pg-failover`. The Environment stage already fails the build when the Docker CLI or the socket group is missing, so `docker exec` is available to the Java stage. The 21 CI contract tests pass. | Red: `ci-regression-stages-red-20261025.log`. Green: `ci-regression-stages-green-20261025.log` |

**Finding: an ERROR line logged after the test completed.** The core run of 2026-10-25 failed
`PgDeviceWatchdogTest.keepaliveWithoutAnActiveWatchdogFails` ("Expected ERROR occurrence mismatch ...
observed=0") together with a class-level "Unexpected ERROR" for the same line
(`peegeeq-pg-failover-core-close-20261025.log`). All seven expected lines were logged. The line reached
the ledger when no scope was open, so it was logged after the test's `afterEach`. The class alone
passed 7 of 7, and 300 repetitions of the same operation passed on the unchanged code
(`peegeeq-pg-failover-watchdog-order-red-20261025.log`), so a deterministic red could not be produced.
The cause is the pattern `future.onFailure(log)` on a future that is also returned to the caller:
Vert.x gives no order between two listeners of one future, and a future can emit to a listener added
after completion before it emits to a stored one (the standards document's "Callback ordering"). The
fix sequences the log into the returned future with `andThen` at the five sites that had the pattern:
`PgDeviceWatchdog.blocking`, `PgFailoverMonitor.serialized`, `PgBootstrap.start`, `PgBootstrap.serve`,
and `PgBootstrapRequest.load`. `PgDeviceWatchdogLogOrderTest` (300 repetitions, isolated) asserts that the
log exists when the caller's handler runs. It passes on the new code. The failure was seen in one of the
six saved full core runs of 24 and 25 October; the other five passed, one of them on the new code. One
passing run is weaker evidence than a red would be.

The testing standards document named `.onFailure(log)` on a returned Future as not an antipattern
("NOT AN ANTIPATTERN"). That holds for the production behaviour. It does not hold for a log line that a
test declares as expected. On 2026-10-25 the section gained an exception that requires `andThen` for such
a line, and `pgq-coding-principles.md` section 9 points to it.

**Runs after the last source change.** Every class reports zero failures, errors, and skips.

| Profile | Scope | Tests run | Saved log under `logs/` |
|---|---|---:|---|
| `integration-tests` | the whole module, eleven classes | 177 | `peegeeq-pg-failover-integration-andthen-20261025.log` |
| default/core | eight classes, including 300 repetitions of the log-order test | 389 | `peegeeq-pg-failover-core-andthen-20261025.log` |
| default/core guards | `peegeeq-test-support`, 61 tests including the five guard classes | 61 | `peegeeq-test-support-core-guards-andthen-20261025.log` |
| `node --test` | `scripts/ci`, two files | 21 | `ci-regression-stages-green-20261025.log` |

**Not closed.**

- The Jenkins build. The changes of this section are pushed (`240be0a7`). On 2026-10-25 three builds
  (32, 33, 34) were started on `http://192.168.137.32:8080`. Each failed in the Checkout stage, with the
  workspace empty and the full clone reset by the peer at 44%, 61%, and 45%. No test ran. See the register
  (`docs-design/tasks/tasks.md`) for the figures and the console logs. The cause is on the host or its
  route to GitHub.
- The race above has no deterministic red.
- The `.onFailure(log)`-on-a-returned-future pattern was removed from this module only. The other modules
  were searched on 2026-10-25: 341 `onFailure` sites that log in 89 files of 12 modules, and 340 declared
  `@ExpectedErrorLog` expectations in 65 test files. The exposure is a register backlog row. The standards
  documents now state the rule (`andThen` where a test declares the ERROR). No site outside this module
  was changed.
- No production entry point calls `PgBootstrap`, the renewal loop, or the watchdog. Resuming an
  interrupted `serve` needs the takeover path (7b.3).

### 9.20 Patroni Operations Matrix, 2026-10-25

The owner directed on 2026-10-25 that PeeGeeQ duplicate Patroni's operations, because Patroni's design
has had more operational review. This section lists each Patroni operation and its state in this design
and in `peegeeq-pg-failover`. It changes no design contract and no code. Rows marked **Decision** need
an owner decision before any work. No row is scheduled by this section.

Patroni sources read on 2026-10-25: the replication modes, watchdog, configuration
(`patroni_configuration`, `dynamic_configuration`, `yaml_configuration`), REST API, pause, DCS failsafe,
replica bootstrap, existing data, and standby cluster pages; the FAQ; and `patroni/ha.py` (read in two
parts). The HA loop diagram page does not exist as text. Statuses for PeeGeeQ come from §8.1, the three
design documents, the class list of `peegeeq-pg-failover/src/main`, and searches of the design folder and
the module source for `replication slot`, `CHECKPOINT`, `nofailover`, `failsafe`, `pg_rewind`, and
`timeline`. No test was run for this section.

| # | Patroni operation | PeeGeeQ state | Class |
|---|---|---|---|
| 1 | Leader key with TTL, renewed each loop; `loop_wait + 2 × retry_timeout <= ttl` | Built: Consul lease, `PgRenewalLoop`, the same rule in `PgNodeConfig` | Covered |
| 2 | Demote on failed renewal | Built: the monitor stops PostgreSQL and records a `stop-writer` receipt | Covered |
| 3 | Demote modes `graceful` (checkpoint, release key), `immediate`, `immediate-nolock`, `offline` | One stop path: fast stop, then immediate. No checkpoint-and-release path | Gap |
| 4 | Leader-race checks: `nofailover`, lag limit, timeline, watchdog healthy, sync members only, REST poll of other members' WAL, `failover_priority` | Designed, not built: promotion does not exist. Eligibility is coverage by the last confirmed policy. The choice between two covered standbys and the candidate timeline check are not specified | Gap |
| 5 | `primary_start_timeout`, `primary_stop_timeout` | Stop budget built. Start timeout designed (default 0), not built | Partly |
| 6 | DCS failsafe mode | Decided against for the baseline (system design §5.7). A coordinator outage makes the writer self-demote, so the cluster stops accepting writes | Decided OD-13: keep rejected |
| 7 | Pause (maintenance) mode | Not in the design. No way to maintain the coordinator without writer demotion | Decided OD-14: deferred |
| 8 | Switchover (immediate, scheduled, cancel) and manual failover | Designed, not built (7b.3). No scheduled switchover and no cancel | Gap |
| 9 | Restart (conditional, scheduled), reload, `pending_restart` | Not in the design | **Decision** |
| 10 | `reinitialize`, `pg_rewind`, `remove_data_directory_on_rewind_failure` | Operator-controlled rewind or rebuild under quarantine. Commands undecided and unbuilt | **Decision** |
| 11 | Bootstrap: `initdb`, initialize lock, `create_replica_methods`, `basebackup`, `clonefrom` | Scope A and B built. The deployment supplies standby data directories (OD-10). No supervisor base-backup action | Different |
| 12 | Synchronous mode, strict, quorum, `/sync` key | Built and stricter: fixed `ANY n` set, no downgrade, install then confirm. No dynamic replacement of a lagging sync standby | Covered |
| 13 | Backend cancelled while waiting for acknowledgement makes its changes visible before replication (Patroni's documented limit) | Not checked. Tests cover weakened `synchronous_commit` and a stopped standby, and the third OD-12 test cancels the backend while it waits. Whether visibility before replication is excluded is not established | Unchecked |
| 14 | Watchdog modes; activate before promotion; feed after lock update; disarm at stop | Modes built; no-device paths tested; applied at primary start only; no real device armed | Partly |
| 15 | Dynamic configuration in the coordinator (`edit-config`, `PATCH /config`) | Not in the design. Configuration is static per node | **Decision** |
| 16 | REST health endpoints `/replica`, `/sync`, `/read-only`, `/liveness`, `/readiness` | Not built. The sidecar answers `/primary` from `pg_is_in_recovery()` only | Gap |
| 17 | `/cluster`, `/history`, `/patroni`, `/metrics` | Not in the design | **Decision** |
| 18 | REST authentication, TLS, allowlist | Designed for the sidecar, not built | Gap |
| 19 | Member tags `nofailover`, `noloadbalance`, `clonefrom`, `nosync`, `nostream`, `replicatefrom` | Not in the design. An operator cannot exclude a node from promotion | **Decision** |
| 20 | Replication slots, permanent slots, `member_slots_ttl` | Not in the design or the code. Without slots a lagging standby can lose required WAL (S31) | Gap |
| 21 | CHECKPOINT after promotion | Not in the design or the code. Seen in `ha.py` (`checkpoint_after_promote`). The PostgreSQL `pg_rewind` requirement behind it was not re-read | Gap |
| 22 | Repoint standbys to the new leader | Designed ("attach surviving peer"), not built | Gap |
| 23 | Callbacks `on_start`, `on_stop`, `on_restart`, `on_reload`, `on_role_change` | Not in the design | **Decision** |
| 24 | Leader key released when the agent stops | Differs: closing the elector never releases. A planned supervisor stop leaves the lease until expiry | Decided OD-15: stop PostgreSQL, then release |
| 25 | System identifier mismatch is a fatal exit | Partly: cluster namespace and `peegeeq.node_id`; bootstrap validates a common database identity; no continuous check | Partly |
| 26 | Adopt an existing cluster | Not in the design. Bootstrap refuses existing history | **Decision** |
| 27 | Configuration generation and validation | `PgNodeConfig` validates timing. No generator | Partly |
| 28 | Cascading replication, standby clusters, Citus, Kubernetes coordinator | Out of scope for this design | Out of scope |

**OD-13 to OD-15 decided by the owner, 2026-10-26.** The implementer recommended each choice.

- OD-13 Failsafe mode (row 6): not adopted. The writer self-demotes when it cannot renew, as in system
  design §5.7. Loss of the Consul quorum stops committed writes. A three-server quorum tolerates the loss
  of one server (S17). This is an accepted limit and is revisited after 7b.3.
- OD-14 Pause mode (row 7): deferred. No code and no design change. Maintenance of the coordinator uses
  a rolling restart that keeps quorum. A full coordinator outage has no maintenance mode.
- OD-15 Planned supervisor stop (row 24): the supervisor stops local PostgreSQL, confirms the stop, then
  releases the lease through the guarded release that exists. This is the same order as planned
  switchover. Not built. It belongs to 7b.3. When it is built, the coordinator design §3.1 sentence
  "Closing the elector preserves the lease" must name this path as the one exception to elector close:
  the elector's own close still never releases.

Proposed handling, for the owner to accept or change: rows 20, 21, and 22 enter the 7b.3 promotion work,
because promotion and re-join depend on them. The remaining rows marked **Decision** (9, 10, 15, 17, 19,
23, 26) wait for the owner. Row 13 gets a test before any claim is made.

Not checked by this section: plan §9.1 to §9.15 history; the code of each class behind a "Built" status;
the `patronictl` page; Patroni's `update_lock` definition, which is outside the returned excerpt of
`ha.py`; the Kubernetes and Citus pages.

### 9.21 Phase 7b.3, Increment P1: Guarded Local Promotion with CHECKPOINT, 2026-10-26

The owner directed on 2026-10-26 that 7b.3 proceed under strict TDD. 7b.3 is large, so it is split. This
increment is P1. It covers one row of §9.20 (row 21, CHECKPOINT after promotion) and the promotion it
belongs to. Row 20 (replication slots) and row 22 (repointing standbys) are later increments. The policy
cutover after promotion (install, confirm, publish, activate on the new writer) is a later increment.
Planned stop with release (OD-15) is a later increment.

**Purpose.** After the former writer's lease is released or has expired, the surviving standby that holds
the lease must become a writable PostgreSQL, with application admission still closed, and its timeline
change must be durable in a checkpoint so that a former writer can later be rewound against it.

**Data model.** No stored field is added.

| Datum | Source of truth or derivable | Contract |
|---|---|---|
| Target eligibility | Derived from the held control record | The confirmed `durabilityPolicy` names this node. A pending policy is never used. The record is the takeover record: `WITHDRAWN`, `previousWriterNodeId` present and not this node |
| Promotion happened | Derived from the live role, with the `promote` receipt as local action evidence | The receipt does not grant authority. A node that is writable with no `promote` receipt of this operation was never promoted by it and is refused |
| Checkpoint taken | Derived from `pg_control_checkpoint()` after the command | Not stored. The `checkpoint-after-promote` receipt records only that the action ran |
| Receipts `promote`, `checkpoint-after-promote` | Node-local store (existing) | Parameters are empty. Identity (generation, operation, action, node) is the existing receipt key. Nothing derivable from the record is copied into them |
| Role, timeline, checkpoint position | Derived live observations | Never stored |

**Contract of `PgFailoverMonitor.promoteStandby(held)`.**

1. Refused before any effect and with no receipt: no fresh ownership of exactly `held`; phase is not
   `WITHDRAWN`; no `previousWriterNodeId` or it names this node; no confirmed policy or it does not name
   this node (`PgLeaseProtocolException`); quarantine (`PgLocalStateException`).
2. Close the grant and the gate. Apply the watchdog mode as `startPrimary` does.
3. Observe the role. In recovery: begin the `promote` receipt, promote, record `COMPLETED`. A failed
   promotion records `UNKNOWN` and fails. Writable: a `promote` receipt of this operation must exist,
   and the call continues; with no receipt the call is refused.
4. Ownership lost while promoting: demote the node (admission closed, local stop) and fail with
   `PgLeaseProtocolException`. A writable node that the lease no longer covers must not run.
5. Begin the `checkpoint-after-promote` receipt, run a forced CHECKPOINT, record `COMPLETED`. A failure
   records `UNKNOWN` and fails. The node stays writable with admission closed.
6. Success changes no control record, no policy, and no grant. Admission stays closed.

**Contract of the new port `PgPromotionControl`** (adapter `PgSqlPromotionControl`: `pg_ctl promote` and
`psql` on the local socket, in the shape of `PgSqlDurabilityControl`):

- `inRecovery()`: `pg_is_in_recovery()`.
- `promote()`: refused when not in recovery. Runs `pg_ctl promote -w`, then polls the role until it is
  writable or the budget ends. The exit code of the command is not the evidence; the role is.
- `checkpoint()`: refused in recovery. Records the latest checkpoint position, runs `CHECKPOINT`, and
  requires the position to have advanced and the checkpoint timeline to equal the timeline of the current
  WAL position.
- Every operation is bounded by one budget. A failed command, a command that returns no result, output
  that is not an observation, and an exhausted budget each fail with `PgPromotionException`.

**Decisions of the implementer, 2026-10-26, not reviewed by the owner and reversible.**

- OD-16: promotion is a separate port and adapter, not a method of `PgProcessControl`. Reason: the role and
  checkpoint observations need SQL, and `PgProcessControl` is defined not to depend on SQL.
- OD-17: eligibility is membership in the confirmed policy only. No WAL comparison between candidate
  standbys. Reason: under the required synchronous policy every named standby holds every acknowledged
  commit. Which covered standby wins is decided by lease acquisition. This differs from Patroni, which
  compares WAL positions among candidates, and is recorded as a difference.
- OD-18: the CHECKPOINT is verified by an advanced checkpoint position and a matching timeline. A forced
  CHECKPOINT writes a new checkpoint record even when the server is idle.

**Dependency failure modes, each with a failing test before its code.**

| Dependency | Modes | Test class |
|---|---|---|
| `pg_ctl promote` and `psql` through the command runner | failed Future; command exits non-zero; returns no result; output not an observation; budget exhausted; command succeeds and the role does not change | `PgSqlPromotionControlIntegrationTest` |
| PostgreSQL role | stopped server; server already writable; server in recovery asked to checkpoint | `PgSqlPromotionControlIntegrationTest` |
| `PgPromotionControl` as the monitor sees it | promote fails; checkpoint fails (fault wrapper over the real control) | `PgPromotionIntegrationTest` |
| Node-local store | receipt cannot be begun | `PgPromotionIntegrationTest` |
| Elector | ownership lost during promotion | `PgPromotionIntegrationTest` |
| Watchdog | `REQUIRED` mode cannot arm | `PgPromotionIntegrationTest` |

**Order of work.** Tests first, run to red (compilation, then behaviour against a stub), then the
implementation, then green, mutation probes, guards, and the existing module classes that construct
`PgFailoverMonitor`, whose constructor gains the promotion control.

**Result, 2026-10-26.** Implemented and tested. `PgPromotionControl`, `PgSqlPromotionControl`, and
`PgPromotionException` are new. `PgFailoverMonitor` has `promoteStandby(held)` and takes the promotion
control as its eighth constructor argument; the eleven construction sites in five existing test classes
were updated. Nothing in this increment is called by a production entry point.

| Step | Evidence under `logs/` |
|---|---|
| Red, compilation | `peegeeq-pg-failover-promotion-compile-red-20261026.log`: `BUILD FAILURE` at test compilation; missing `PgPromotionControl`, `PgSqlPromotionControl`, `PgPromotionException`, `promoteStandby`, `PROMOTE`, `CHECKPOINT_AFTER_PROMOTE` |
| Red, behaviour, against a stub that fails with "not implemented" | `peegeeq-pg-failover-promotion-adapter-red-20261026.log`: 13 run, 10 failures, 3 errors (12 methods and one class-level log-contract failure); `peegeeq-pg-failover-promotion-monitor-red-20261026.log`: 16 run, 12 failures, 4 errors (15 methods and one class-level failure). Every failure is `UnsupportedOperationException` or a wrong exception type |
| Green | `PgSqlPromotionControlIntegrationTest` 12 and `PgPromotionIntegrationTest` 15, zero failures: `peegeeq-pg-failover-promotion-adapter-green1-20261026.log`, `peegeeq-pg-failover-promotion-monitor-green1-20261026.log`; rerun on the final test code: `peegeeq-pg-failover-promotion-final-integration-20261026.log` (27) |
| Mutation probes | Seven behaviours were removed one at a time and each named test failed with its own message (below). The two production files were restored and their SHA-256 equals the value recorded before the probes: `peegeeq-pg-failover-promotion-mutation-sha-before-20261026.log`, `-sha-after-` |
| Regression, `-Pintegration-tests`, whole module | 13 classes, **204** tests, zero failures: monitor 23, bootstrap 7, renewal loop 13, policy installation 14, bootstrap serving 14, durability 19, Consul coordinator 61, process control 11, admission gate 7, elector queueing 4, fixture contract 4, and the two new classes 27. Logs `peegeeq-pg-failover-promotion-regress-a-` to `-e-20261026.log` and the final integration log above |
| Default/core, whole module | 8 classes, **389** tests, zero failures: `peegeeq-pg-failover-promotion-core-20261026.log` |
| Guards | `peegeeq-test-support`, 61 tests, zero failures: `peegeeq-test-support-promotion-guards-20261026.log` |

Mutation probes, with the test that failed:

| Removed behaviour | Test | Failure message |
|---|---|---|
| Adapter: role confirmation after `pg_ctl promote` | `aPromoteCommandThatReportsSuccessButLeavesTheServerInRecoveryFailsWithinItsBudget` | A promotion was reported although the role did not change |
| Adapter: the `CHECKPOINT` statement | `theCheckpointAfterPromotionAdvancesTheCheckpointPositionOnTheNewTimeline` | The checkpoint did not advance: 0/4000108 then 0/4000108 |
| Supervisor: demotion when ownership is lost | `ownershipLostWhilePromotingStopsTheNodeAndFailsTheCallWithoutACheckpoint` | A promotion succeeded after ownership was lost |
| Supervisor: closing the gate first | `promotionClosesAnOpenGrantAndAdmissionBeforeItChangesAnything` | Admission stayed open during the promotion |
| Supervisor: confirmed-policy membership check | `aTargetThatTheConfirmedPolicyDoesNotNameIsRefused` | A standby that the confirmed policy does not name was promoted |
| Supervisor: the receipt requirement for a writable node | `aWritableNodeWithNoPromotionReceiptOfThisOperationIsRefused` | A writable node with no promotion of this operation was accepted |
| Supervisor: skipping a completed checkpoint receipt | `promotingTwiceIsIdempotentAndTakesNoSecondCheckpoint` | A completed checkpoint receipt was run again |

Findings:

- A forced `CHECKPOINT` is needed to prove the checkpoint. On the test fixture the background checkpoint
  that promotion starts had finished before the explicit one ran, so only the explicit statement advances
  the position (mutation 2).
- The teardown of `PgPromotionIntegrationTest` collects every failure. `Future.eventually` discards a
  cleanup failure, and seven `.eventually(` calls remain in the teardown of four older test classes
  (`PgRenewalLoopIntegrationTest`, `PgPolicyInstallationIntegrationTest`, `PgBootstrapIntegrationTest`,
  `PgBootstrapServingIntegrationTest`). They are not changed here.

**Not covered by P1.** Replication slots (§9.20 row 20) and repointing the surviving standby to the new
writer (row 22). The policy cutover after promotion: install, confirm, publish, and activate on the new
writer. Planned stop with release (OD-15). Takeover after real lease expiry: the tests use a released
lease, not an expired one, and the former writer is stopped with `pg_ctl`, not by a host loss. Applying the
watchdog mode before promotion is tested only for `REQUIRED` without a device; no promotion has run with an
armed device. The checkpoint was verified on an idle server only. No production entry point calls
`promoteStandby`. The Jenkins build and the whole-repository gate were not run.


### 9.22 Phase 7b.3, Increment P2: a Standby Follows the New Writer, 2026-10-26

The owner directed `continue` after P1 (§9.21). P1 left the policy cutover as the next item. Reading the
cutover contract showed that it needs this increment first: the cutover's coverage check requires the
surviving standby to stream from the new writer, and after a takeover that standby still streams from the
former writer. P2 is therefore the standby follow operation (§9.20 row 22). The policy cutover is P3.

**Purpose.** A running standby, in its own supervisor, must start replicating from the node that the
control record names as writer, and must report streaming from it, so that the writer can confirm
synchronous coverage.

**Data model.** No stored field is added.

| Datum | Source of truth or derivable | Contract |
|---|---|---|
| Writer identity | Control record `writerNodeId` | Passed in as the observed record. A node never follows itself |
| Writer address (host, port) | Deployment configuration of the member | Supplied by the caller. The supervisor holds no member address list yet. Not stored by this increment |
| Replication user and `application_name` | Derived: the node's own identity (OD-11) | Never supplied and never stored |
| Replication credential | Deployment secret, held in a password file the deployment provisions | Only the path is supplied. No password appears in a command line, a log line, or a receipt |
| `primary_conninfo` | Derived from the four items above; persisted by `ALTER SYSTEM` | The built string contains no secret |
| Receipt `follow-writer` | Node-local store (existing) | Parameters are the host and port requested. Reuse with other parameters is rejected, so a rerun cannot silently follow a different writer |
| Streaming state, sender host and port | Derived live from `pg_stat_wal_receiver` | Never stored |

**Contract of `PgFailoverMonitor.followWriter(observed, writer)`.**

1. Refused before any effect and with no receipt: `observed` names this node as writer
   (`PgLeaseProtocolException`); the node is quarantined (`PgLocalStateException`); the local server is not
   in recovery (`PgPromotionException`). A writable server cannot follow a writer without a rewind, which is
   a later increment.
2. Close the grant and the gate.
3. Begin the `follow-writer` receipt with the host and port. Apply the new `primary_conninfo`, reload, and
   observe that the WAL receiver is streaming from exactly that host and port. Record `COMPLETED`, or
   `UNKNOWN` after a failure.
4. A rerun with equal parameters repeats the observed effect and keeps the receipt `COMPLETED`.
5. No lease is required. The follower never holds ownership. Control record, policy, and grant are not changed.

**Contract of `PgPromotionControl.follow(nodeId, writer)`** (the existing port; its documentation now covers
local role changes). The adapter refuses a server that is not in recovery, builds
`host=H port=P user=<nodeId> application_name=<nodeId> passfile=<path>`, installs it with `ALTER SYSTEM`,
reloads with `pg_ctl reload`, and polls `pg_stat_wal_receiver` until the receiver is streaming from H and P
or the budget ends. `PgWriterEndpoint` rejects a host or a password-file path outside a restricted character
set, so the built string needs no quoting.

**Decisions of the implementer, 2026-10-26, not reviewed by the owner and reversible.**

- OD-19: the credential reaches the follower through a password file, not through the command line. Reason:
  the command runner logs full command lines on failure, and a process list shows them. The alternative of
  reading the standby's current `primary_conninfo` and rewriting its host would put the password in a command.
- OD-20: the caller supplies the writer's address. The member address list of system design §5.4
  (`peegeeq.pg.nodes.<id>.host`) is not in `PgNodeConfig`, and adding it changes every construction site.
- OD-21: `follow` is added to `PgPromotionControl` instead of a new port, so that the monitor gains no ninth
  constructor argument. The name `PgPromotionControl` is now narrower than its role.

**Dependency failure modes, each with a failing test before its code.**

| Dependency | Modes | Test class |
|---|---|---|
| Command runner (`psql`, `pg_ctl reload`) | failed Future; no result; non-observation output; budget exhausted | `PgSqlPromotionControlIntegrationTest` |
| Local role | writable server; stopped server | `PgSqlPromotionControlIntegrationTest` |
| WAL receiver | never streams from the requested address within the budget | `PgSqlPromotionControlIntegrationTest` |
| Built `primary_conninfo` | other fields of the string are preserved by construction: user, application name, password file | `PgSqlPromotionControlIntegrationTest` |
| Writer endpoint | blank or unsafe host, port outside 1 to 65535, unsafe password-file path | `PgWriterEndpointTest` (core) |
| Node-local store | receipt cannot be begun; receipt exists with other parameters | `PgFollowIntegrationTest` |
| Port as the monitor sees it | follow fails (fault wrapper over the real control) | `PgFollowIntegrationTest` |

**Not in P2.** Following after a rewind of a former writer; the policy cutover (P3); replication slots; any
production entry point.
