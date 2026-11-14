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
| 7b.3 increment P2: a standby follows the new writer | Implemented and tested, 2026-10-26 | `PgPromotionControl.follow`, `PgWriterEndpoint` (host, port, password-file path; no password), `PgFailoverMonitor.followWriter` under a `follow-writer` receipt, no lease required. See §9.22. Not in P2: the policy cutover, slots, following after a rewind. |
| 7b.3 increment P3: the policy cutover on the new writer | Implemented and tested, 2026-11-03 | `PgTakeover.cutover(held, mode)` derives the next policy (confirmed set minus the new writer, next revision), persists it as pending, then runs the serving sequence shared with `PgBootstrap.serve`. Fixture finding: standby admission files lacked replication rules. See §9.23. |
| 7b.3 increment P4: member replication slots on the writer | Implemented and tested, 2026-11-12 | `PgNodeConfig.slotName` (derived, never stored); `PgDurabilityControl.ensureSlots` (creates missing physical slots; refuses an unbounded `max_slot_wal_keep_size` (OD-27) and a logical slot under a member name; its refusal in recovery was removed by P6, OD-35); `PgFailoverMonitor.ensureMemberSlots`; the slot step after the checkpoint in `promoteStandby` and first in `PgPolicyCutover.serve`; `follow` streams through the node's own slot; coverage fails at once for a required standby whose slot is lost (OD-33). See §9.24 and §9.25. Not in P4: slots on replicas, removing a departed member's slot. |
| 7b.3 increment P5: replacing a lost member slot for a rebuild | Implemented and tested, 2026-11-12 | `PgDurabilityControl.replaceLostSlot` (only a lost slot is dropped, then re-created reserved) and `PgFailoverMonitor.replaceLostMemberSlot` (ownership, membership, quarantine). The fixture rebuilds the member through the new slot. See §9.26. Not in P5: the rebuild decision and the member-side commands outside the fixture (§9.20 row 10). |
| 7b.3 increment P6: slots on standbys, advanced to published member positions | Implemented and tested, 2026-11-12 | A second coordinator port `PgMemberPositions` with its Consul adapter; every node holds the member slots (OD-35); `PgMemberSlots` publishes this node's position and advances a standby's slots to the smaller of the published and the replay position, same timeline only. Closes the gap of §9.24: a standby stopped before a takeover follows the new writer. See §9.27. Not in P6: the loop that runs it, removing a departed member's record. |
| 7b.3 increment P7: session-bound member positions, their contract, the monitor builder, candidate ranking | Implemented and tested, 2026-11-13; module green on Jenkins build #36 | `PgMemberPositionException` and `PgMemberPositionsContract` with the Consul binding; each member record is held by the member's own Consul session and disappears at its expiry, which removes a departed member's record; `PgFailoverMonitor.builder()`, with `publishPosition` and `advanceMemberSlots` as serialized monitor operations (`PgMemberSlots` deleted); `PgDurabilityControl.candidatePosition` and `PgFailoverMonitor.takeoverCandidacy`. See §9.28. Not in P7: the automatic initiation that acts on the candidacy (phase 8a), the supervisor loop. |
| 7b.3 manual takeover and re-join, remaining | Not implemented | Requires old-writer exclusion by expiry, planned stop with release, repeated failover, controlled re-join, and routed production-manager assertions. |
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
through the node's slot and waits in its streaming loop (`wait_event = WalReceiverMain`), or the budget ends. `PgWriterEndpoint` rejects a host or a password-file path outside a restricted character
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

**Result, 2026-10-26.** Implemented and tested. New: `PgWriterEndpoint`; `PgPromotionControl.follow` and its
adapter implementation in `PgSqlPromotionControl`; `PgFailoverMonitor.followWriter(observed, writer)`. The
shared receipt helper of P1 now takes the receipt parameters. `PgReplicationFixture` provisions a password
file per standby. Nothing in this increment is called by a production entry point.

| Step | Evidence under `logs/` |
|---|---|
| Red, behaviour, against stubs that fail with "not implemented" | `peegeeq-pg-failover-follow-core-red-20261026.log`: 6 run, 5 failures. `-follow-adapter-red-`: 18 run, 9 failures and 2 errors; the seven follow tests fail on `UnsupportedOperationException` or a wrong exception type, and three P1 tests fail only because a failed test's teardown leaks an "Interrupted ... pg_ctl stop" ERROR line into the next test (register finding 13). `-follow-monitor-red-`: 10 run, 7 failures and 3 errors. A compile-red log was not saved for this increment; the tests were written before the stubs, and the stubs were written before the first run |
| Green | `PgWriterEndpointTest` 6; `PgSqlPromotionControlIntegrationTest` 17; `PgFollowIntegrationTest` 9 (10 after one test was added for the gate): `-follow-core-green-`, `-follow-adapter-green1-`, `-follow-monitor-green1-` |
| Mutation probes | Eight removals; each named test failed with its own message (below). The three production files were restored and their SHA-256 equals the value recorded before the probes: `peegeeq-pg-failover-follow-mutation-sha-before-20261026.log`, `-sha-after-` |
| Regression, `-Pintegration-tests`, whole module | 13 classes, **219** tests, zero failures, on the final code: adapter 17, promotion 15, follow 10, monitor 23, bootstrap 7, renewal loop 13, policy installation 14, bootstrap serving 14, durability 19, Consul coordinator 61, process control 11, admission gate 7, elector queueing 4, fixture contract 4. Logs `peegeeq-pg-failover-follow-final-a-` to `-f3-20261026.log` |
| Default/core, whole module | 9 classes, **395** tests, zero failures: `peegeeq-pg-failover-follow-core-20261026.log` |
| Guards | `peegeeq-test-support`, 61 tests, zero failures after the fix below: `peegeeq-test-support-follow-guards-20261026-run2.log` |

Mutation probes, with the test that failed:

| Removed behaviour | Test | Failure message |
|---|---|---|
| Adapter: the confirmation that the receiver streams from the requested address | `aWriterThatCannotBeReachedIsNotReportedAsFollowedAndTheWaitEndsWithinItsBudget` | The asynchronous result was expected to have failed |
| Adapter: `pg_ctl reload` | `aStandbyFollowsTheNewWriterAndStreamsFromIt` | WAL receiver is not streaming from pg-node-2:5432 within the budget; last observed: streaming\|pg-node-1\|5432 |
| Adapter: the refusal of a writable server | `aServerThatIsNotInRecoveryCannotFollowAndChangesNothing` | A writable server was pointed at another writer |
| Supervisor: the quarantine check | `aQuarantinedNodeCannotFollow` | A quarantined node followed a writer |
| Supervisor: the writable-server check, alone | `aWritableNodeCannotFollowAndChangesNothing` | A refused follow must not record an effect |
| Supervisor: the self-follow check | `aRecordThatNamesThisNodeAsTheWriterIsRefusedAndChangesNothing` | A node was told to follow itself |
| Supervisor: closing the gate first | `followingClosesAnOpenGrantAndAdmissionBeforeItChangesAnything` | Admission stayed open while following |
| Endpoint: host validation | `aHostThatCouldChangeTheConnectionStringIsRejected` | Accepted host (blank) |

Findings:

- No test closed the gate until `followingClosesAnOpenGrantAndAdmissionBeforeItChangesAnything` was added. The
  follower's own admission file already rejects applications, so the other tests could not notice a missing
  gate close.
- In the first mutation batch the supervisor's writable check and the adapter's check were removed together,
  so the supervisor's check was not shown on its own. It was rerun alone.
- **Correction to §9.21.** The guard run recorded there (61 tests, zero failures) was made before the
  teardown of `PgPromotionIntegrationTest` was rewritten. The rewritten teardown called `admin.close()`
  inside `.onSuccess` outside `testContext.verify`, which `OnSuccessExceptionSwallowingGuardTest` rejects
  (Tier 2). The guards were not rerun after that edit, so P1 was reported with a guard failure present. The
  guard run of this section found it. Both new teardowns now use `context.verify`, and the guards pass.

**Not covered by P2.** The policy cutover on the new writer (P3), which this increment makes possible: the
follower can now be confirmed as a synchronous peer of the new writer. Replication slots (§9.20 row 20).
Following after a rewind of a former writer. The password file is provisioned by the fixture; no deployment
step provisions it. The follower reads the writer's address from the caller, not from member configuration
(OD-20). Following a writer that is itself still in promotion was not tested. No production entry point calls
`followWriter`. The Jenkins build and the whole-repository gate were not run.


### 9.23 Phase 7b.3, Increment P3: the Policy Cutover on the New Writer, 2026-11-03

The owner directed `proceed` after reading the handover of 2026-10-27
(`docs-design/tasks/HANDOVER-7B3-PG-FAILOVER.md`). P3 is the policy cutover that §9.21 and §9.22 left
open. It covers the second row of the system design §5.8 lifecycle table: node 1 fenced, node 2
promoted, node 2 serves with `ANY 1 ("pg-node-3")`.

**Purpose.** After `promoteStandby` (P1) on the new writer, and `followWriter` (P2) on the surviving
standby, the new writer must persist, install, confirm, publish, and activate a policy that requires
only the surviving standby. Admission stays closed until activation.

**Data model.** No stored field is added.

| Datum | Source of truth or derivable | Contract |
|---|---|---|
| Next required standby set | Derived: the confirmed `durabilityPolicy` set of the takeover record, minus the new writer, in the same order | The former writer is not added back. It returns only through re-join (§5.8 table, rows 3 and 4). An empty set is refused |
| Next policy revision | Derived: one more than the larger of the confirmed revision and the revision of any pending policy the record carries | A revision number never names two different standby sets within one incarnation |
| `pendingDurabilityPolicy` | Control record (existing); authoritative proposed policy | Written by `PgPrimaryElector.update` before any configuration effect (§5.8 step 1). A carried pending policy whose set equals the derived set is used unchanged; otherwise it is replaced |
| `durabilityPolicy`, `phase` | Control record (existing) | Confirmation and publication as in §9.17 |
| Promotion completed | Derived from the node-local `checkpoint-after-promote` receipt of this generation and operation, result `COMPLETED` | Local action evidence. It grants no authority; the lease does |
| Receipt `install-policy` | Node-local store (existing) | As in §9.16. Its key carries the new generation and operation, so it cannot collide with the former writer's receipt |
| Prepared and open grant | Node-local store (existing) | Policy revision is the cut-over revision |
| Coverage, sync state, WAL boundary | Derived live observations | Never stored |

**Contract of `PgTakeover.cutover(held, mode)`** (new class, sibling of `PgBootstrap`).

1. Refused before any effect, with no coordinator write and no receipt:
   - no fresh ownership of exactly `held`; phase other than `WITHDRAWN`; no `previousWriterNodeId`, or
     it names this node; no confirmed policy, or it does not name this node
     (`PgLeaseProtocolException`);
   - the `checkpoint-after-promote` receipt of this generation and operation is absent or not
     `COMPLETED` (`PgPromotionException`);
   - quarantine (`PgLocalStateException`);
   - the derived standby set is empty (`PgDurabilityException`). Committed-write admission stays closed
     until a standby is restored (§5.8, two-node pair).
2. When the record has no pending policy with the derived set, `PgPrimaryElector.update` writes
   `pendingDurabilityPolicy` at the derived revision. The phase stays `WITHDRAWN`. The confirmed policy
   is carried unchanged, which the elector permits although it names the new writer (§8.1, policy
   validation row).
3. The shared serving sequence runs on the record with the pending policy: `installPendingPolicy`,
   confirmation, `prepareWriter(confirmed, mode)`, publication of `SERVING`, `activateWriter`. This is
   the sequence of §9.17, moved out of `PgBootstrap.serve` without change (OD-22).
4. The result is the serving record. A failure at any step stops the sequence and is logged once at
   ERROR by `PgTakeover` (`Takeover cutover failed on node <id>`), through `andThen`.

State after a failure: before step 2 the record is unchanged. After step 2 it is withdrawn with the
pending policy, admission is closed, and ownership is kept, so a rerun with that record (read back from
the coordinator) skips step 2 and continues. After a failed coordinator update ownership is retired, as
in §9.17, and this elector instance cannot rerun.

**Answer to the open question of the handover.** Each follower's own supervisor runs the follow (P2).
The cutover does not run it and does not wait for it. The coverage check of step 3 observes one fresh
boundary and waits within the durability budget until the required standby streams from the new writer
and covers the boundary. The order of the follow and the cutover therefore does not matter within that
budget. A follow that lands after the budget fails the cutover with admission closed and the pending
policy persisted, and a rerun completes. Two tests cover this (below). This is static reading until
those tests run.

**Finding: the standby admission files of the fixture have no replication rules.** The gate's contract
(`PgHbaAdmissionGate` Javadoc) states that both admission files keep the replication rules. The
fixture writes them only into the writer's files. Each standby's files hold the local rule and one
`host all all all` rule. In `pg_hba.conf`, `all` does not match a physical replication connection. So
after `promoteStandby` closes the gate on the new writer, the installed file would reject the
follower's replication connection, and the coverage check could never pass. The P2 test did not meet
this, because its new writer was promoted through the adapter and its gate never installed a standby
file. This is static reading; no run has shown it. Fix: every node's admission files carry the same
replication rules. The rules name the standby roles only; the former writer has no replication role
until re-join.

**Decisions of the implementer, 2026-11-03, not reviewed by the owner and reversible.**

- OD-22: `PgTakeover` is a new class, and the serving sequence moves from `PgBootstrap.serve` into a
  package-private `PgPolicyCutover`, which both classes call. Reason: one mechanism with two callers.
  Takeover is not bootstrap, and the later takeover steps (manual takeover, the production entry point)
  belong in `PgTakeover`. `PgBootstrap.serve` keeps its contract and its log line, so
  `PgBootstrapServingIntegrationTest` is the regression check for the move.
- OD-23: the next required set is the confirmed set minus the new writer. Reason: §5.8 requires every
  remaining covered peer after a promotion, and only members of the last confirmed policy are covered.
- OD-24: the next revision is one more than the larger of the confirmed and carried pending revisions.
  A carried pending policy with the derived set is reused. Reason: revisions increase within an
  incarnation, and one revision must not name two sets. The reuse also makes the rerun after a failed
  installation write nothing new.
- OD-25: the cutover requires the completed `checkpoint-after-promote` receipt of its own operation.
  Reason: a policy must not be confirmed on a node whose promotion sequence did not complete. The role
  check inside the durability control would refuse a standby only after the coordinator write.
- OD-26: the cutover does not run or wait for the follow. Reason: the follower is another node with its
  own supervisor (P2); the coverage wait is the synchronisation point.

**Dependency failure modes, each with a failing test before its code** (`PgTakeoverIntegrationTest`).

| Dependency | Mode | Test |
|---|---|---|
| All steps | none fails | `theCutoverServesTheNewWriterWithOnlyTheSurvivingStandbyRequired` |
| Follower | follows only after the coverage wait has begun | `aCutoverWaitingForCoverageCompletesWhenTheFollowerFollowsDuringTheWait` |
| Durability control | the surviving standby is not covered within the budget; rerun after it follows | `aRerunAfterACoverageFailureReusesThePendingPolicyAndServesOnceTheFollowerFollows` |
| Control record history | a pending policy carried from the former writer | `aPendingPolicyCarriedFromTheFormerWriterIsReplacedAtTheNextRevision` |
| Confirmed policy | names only the new writer, so no standby remains | `aConfirmedPolicyThatNamesOnlyTheNewWriterLeavesNoStandbyAndIsRefusedBeforeAnyEffect` |
| Node-local store | no completed promotion of this operation | `aCutoverBeforeThisOperationsPromotionCompletedIsRefusedBeforeAnyEffect` |
| Node-local store | quarantine | `aQuarantinedNodeIsRefusedBeforeAnyEffect` |
| Elector | a record this node does not hold; a record that is not withdrawn | `recordsOtherThanTheHeldTakeoverRecordAreRefusedBeforeAnyEffect` |
| Coordinator update | the pending policy is not written | `aFailedPendingPolicyWriteLeavesTheRecordUnchangedAndInstallsNothing` |

Not repeated here, because the shared sequence is tested by `PgBootstrapServingIntegrationTest`: a
failed confirmation, a failed publication, a gate that does not open, a frozen coordinator, and expiry
during the installation.

**Order of work.** Tests and a stub that fails with "not implemented", written 2026-11-03. Then the
red run, the implementation, the green run, mutation probes with SHA-256 records, the module's
integration classes one at a time, the core tests, and the guards last.

**Status, 2026-11-03.** Implemented (commit `5ead8746`), green, mutation-probed, regression, core, and
guards green. Evidence below; every count is from a run made on 2026-11-03 against `5ead8746` with a clean
working tree.

| Step | Evidence under `logs/` |
|---|---|
| Rebuild with tests and stub | `rebuild-peegeeq-pg-failover-cutover-20261103.log`: `BUILD SUCCESS` (run by the owner) |
| Red, behaviour, against the stub | `peegeeq-pg-failover-cutover-red-20261103.log`: `Tests run: 10, Failures: 7, Errors: 3`. Nine methods fail on `UnsupportedOperationException` or a wrong exception type. The tenth failure is the class-level ERROR log contract: the declared `PgTakeover` lines were never logged, and each failed test's unawaited teardown leaked an "Interrupted ... pg_ctl stop" ERROR line (register finding 13). The same teardown prints `RejectedExecutionException: event executor terminated` from the worker thread whose result arrives after Vert.x closed |
| SHA-256 before the implementation | `peegeeq-pg-failover-cutover-sha-before-implementation-20261103.log` (`PgBootstrap`, the stub `PgTakeover`, `PgFailoverMonitor`) |
| Implementation | `PgTakeover.cutover`; new package-private `PgPolicyCutover.serve`; `PgBootstrap.serve` delegates to it and keeps its log line |
| Rebuild of `5ead8746` | `rebuild-peegeeq-pg-failover-cutover-impl-20261103.log`: `BUILD SUCCESS`, 45 main sources |
| Green, `PgTakeoverIntegrationTest` | `peegeeq-pg-failover-cutover-impl-20261103.log` and `...-impl-run2-20261103.log`: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` in both runs. The same 9 methods as the red run; the red run's tenth entry was the class-level log contract. No WARN line; every ERROR line matched a declaration |
| SHA-256 around the probes | `peegeeq-pg-failover-cutover-mutation-sha-before-20261103.log` and `...-sha-after-20261103.log`: `PgTakeover.java` `387ECAD8...257D3E` in both. Rebuilt from the restored source afterwards (`rebuild-peegeeq-pg-failover-cutover-restored-20261103.log`) |
| Regression, `PgBootstrapServingIntegrationTest` | `peegeeq-pg-failover-cutover-regression-bootstrap-serving-20261103.log`: `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0` (14 `@Test` methods) |
| Regression, `PgPromotionIntegrationTest` | `peegeeq-pg-failover-cutover-regression-promotion-20261103.log`: `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0` (15 `@Test` methods) |
| Regression, `PgFollowIntegrationTest` | `peegeeq-pg-failover-cutover-regression-follow-20261103.log`: `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0` (10 `@Test` methods) |
| Module core tests | `peegeeq-pg-failover-cutover-core-20261103.log`: `Tests run: 395, Failures: 0, Errors: 0, Skipped: 0` across 9 classes |
| Guards | `peegeeq-test-support-cutover-guards-20261103.log`: `Tests run: 61, Failures: 0, Errors: 0, Skipped: 0`; the five guard classes 2, 2, 8, 1, 1 |

Mutation probes, each a removal in `PgTakeover.java`, a rebuild, and a run of the one target method
(`peegeeq-pg-failover-cutover-mutation<N>-rebuild-20261103.log` and `...-mutation<N>-red-20261103.log`). Each
probe failed its target test:

| N | Removal | Target test failure |
|---|---|---|
| 1 | Empty-set refusal | `aConfirmedPolicyThatNamesOnlyTheNewWriter...`: expected `PgDurabilityException`, was `IllegalArgumentException` from the `PgDurabilityPolicy` constructor |
| 2 | Receipt check | `aCutoverBeforeThisOperationsPromotionCompleted...`: expected `PgPromotionException`, was `PgDurabilityException` (the node is in recovery) |
| 3 | Quarantine check | `aQuarantinedNode...`: the control record changed; the pending policy was written at revision 25 |
| 4 | Reuse of a pending policy with the derived set | `aRerunAfterACoverageFailure...`: revision 3 instead of 2 |
| 5 | Use of the carried pending revision | `aPendingPolicyCarried...`: revision 2 instead of 3 |
| 6 | Ownership and record check | `recordsOtherThanTheHeldTakeoverRecord...`: the pending policy was written on a `FENCING` record |

Each failing probe run also failed the class-level log contract with one `LocalCommandRunner` "Interrupted
while waiting for command ... pg_ctl stop" ERROR. The line appears only when a test fails; neither green run
logged it. This is register finding 13: vertx-junit5 5.0.4 does not await an asynchronous `@AfterEach`
after a failed test, so Vert.x is closed while the teardown's `pg_ctl stop` waits. Its correction is the
cleanup helper of Task 9, correction 5, as §9.18 records; it is not changed here.

### 9.24 Phase 7b.3, Increment P4: Member Replication Slots on the Writer, 2026-11-03 (design)

The owner directed on 2026-11-03 that the implementer choose the next 7b.3 item. P4 is §9.20 row 20,
replication slots. It is the next item of the handover's ordered list (`HANDOVER-7B3-PG-FAILOVER.md` §4),
and §9.20 classes it as a gap, not a decision. It is a correctness gap in P2 and P3: without a slot the
writer may remove WAL that a lagging or reconnecting standby still needs, and that standby can then
neither follow nor be covered (S31). Takeover after real lease expiry tests existing code and follows.

**Purpose.** The writer keeps every WAL segment that each member still needs to stream, in one physical
slot per member, so that a standby that falls behind or disconnects briefly resumes streaming. The
retention is bounded, so a member that stays away cannot fill the writer's `pg_wal`.

**Sources read, 2026-11-03.** PostgreSQL 15 documentation: §27.2.6 replication slots;
`max_replication_slots`, `max_slot_wal_keep_size`, `wal_keep_size`, `primary_slot_name`; the
`pg_replication_slots` view; `pg_create_physical_replication_slot`, `pg_drop_replication_slot`,
`pg_replication_slot_advance`. Patroni dynamic configuration (`use_slots`, `slots`, `member_slots_ttl`).
Code read in full: `PgFailoverMonitor`, `PgBootstrap`, `PgPolicyCutover`, `PgSqlDurabilityControl`,
`PgDurabilityControl`, `PgSqlPromotionControl`, `PgPromotionControl`, `PgWriterEndpoint`, `PgNodeConfig`,
and the test fixture `PgReplicationFixture`. The test image is `postgres:15.13-alpine3.20`.

Facts from the PostgreSQL documentation that the design rests on:

- A slot keeps WAL from its `restart_lsn` until the consumer has received it. With
  `max_slot_wal_keep_size = -1` (the default) the retention is unlimited. With a bound, a slot that falls
  further behind reports `wal_status` `unreserved`, then `lost`, and a lost slot is no longer usable.
- `primary_slot_name` is set in the configuration file (reload). A change restarts the WAL receiver.
- `max_replication_slots` is set at server start only. The default is 10.
- `pg_create_physical_replication_slot(name, true)` reserves the WAL position at once.
- Slot names contain only lower-case letters, digits, and the underscore.

Patroni keeps a physical slot for each member on every node from PostgreSQL 11, advances the slots on
replicas from member positions published in its coordinator, and keeps a departed member's slot for
`member_slots_ttl` (default 30 minutes). For a fixed membership its documentation recommends permanent
slots named after the members. PeeGeeQ's membership is fixed per incarnation (`PgNodeConfig.memberNodeIds`).

**Data model.** No stored field is added.

| Datum | Source of truth or derivable | Contract |
|---|---|---|
| Member slot name | Derived from the member identity: `peegeeq_`, then the identity in lower case with `-` and `.` replaced by `_` | Never stored. `PgNodeConfig` refuses a membership in which two members derive the same name or a name is longer than 63 bytes |
| Slots a writer must hold | Derived: the membership minus this node | Includes the former writer after a takeover, so that its WAL from the slot's creation onward is kept for re-join |
| Slot existence, type, `restart_lsn`, `active`, `wal_status` | Derived live from `pg_replication_slots` | Never stored. A slot with another prefix is never created, dropped, or advanced (§9.20 row 20, Patroni `ignore_slots`) |
| Retention bound | Deployment configuration: `max_slot_wal_keep_size` in `postgresql.conf` | Observed, never set by the supervisor. An unbounded value is refused (OD-27) |
| A standby's `primary_slot_name` | Derived from its own identity | Installed with `primary_conninfo` by `follow`; written at provisioning by the deployment for an initial standby |
| Slot used by the WAL receiver | Derived live from `pg_stat_wal_receiver.slot_name` | Part of the follow confirmation. Never stored |

**Contracts.**

1. `PgNodeConfig.slotName(nodeId)` (static) returns the derived name. The record's validation adds the
   two refusals in the table.
2. `PgDurabilityControl.ensureSlots(List<String> nodeIds)` (adapter `PgSqlDurabilityControl`). Refused in
   recovery, and when `max_slot_wal_keep_size` is `-1` (`PgDurabilityException`). For each name, reads
   `pg_replication_slots` first and creates a physical slot with an immediately reserved position only when
   none exists: creating an existing slot fails with `replication slot ... already exists` (check 1 below).
   A slot that exists is not recreated, moved, or dropped. The call then observes every named slot as physical. A logical slot
   under a member slot name fails the call. A `lost` slot is reported in the failure of a later coverage
   check, not here: recreating it would not restore the WAL its standby needs.
3. `PgFailoverMonitor.ensureMemberSlots(held)`. Refused without fresh ownership of exactly `held`, and under
   quarantine. Derives the set from the membership and calls `ensureSlots`. No receipt: the effect is
   idempotent, and the slots themselves are the evidence (OD-28).
4. `promoteStandby` calls the slot step after the checkpoint and before it returns. A failure leaves the
   node writable with admission closed, as a checkpoint failure does, and a rerun repeats only the
   missing work. The P1 contract line "success changes no control record, no policy, and no grant" is
   unchanged.
5. `PgPolicyCutover.serve` calls `ensureMemberSlots` first. Bootstrap therefore refuses an unbounded
   retention before it installs a policy, and a takeover cutover confirms the slots that promotion created.
6. `PgPromotionControl.follow` also installs `primary_slot_name` with the derived name of this node, and
   the confirmation requires the WAL receiver to stream from the host and port through that slot.
7. The fixture, which stands for the deployment (OD-10), sets `max_slot_wal_keep_size` on every node and
   takes each base backup with `-S <slot> -C`, so that the writer holds the standbys' slots from
   provisioning and `-R` writes `primary_slot_name`.

**Decisions of the implementer, 2026-11-03, not reviewed by the owner and reversible.**

- OD-27: an unbounded retention (`max_slot_wal_keep_size = -1`) is refused. **Danger this addresses:** with
  no bound, any member that stays down keeps WAL on the writer without limit. After every takeover the
  former writer is such a member until re-join. `pg_wal` then fills, and the writer stops accepting
  commits for every application: a whole-cluster write outage, with no warning before the disk is full.
  With a bound, the same member loses its slot (`wal_status = lost`) and must be rebuilt under quarantine
  (S31), which the design already requires. The bound's value is a deployment choice. **This needs the
  owner's review**, because the bound trades writer availability against standby rebuilds.
- OD-28: slot creation has no receipt. Reason: it is idempotent and observable, as the gate close is.
- OD-29: `ensureSlots` is added to `PgDurabilityControl`, not to a new port. Reason: slots are writer-side
  WAL retention, next to the peer observation of that port, and the monitor gains no ninth argument.
- OD-30: slots are kept on the writer only. Patroni's slots on replicas are not built (see the gap below).

**Known gap left by P4.** After a takeover the new writer's slots reserve WAL from their creation. WAL
that the surviving standby needs from before that point is kept only by the new writer's own retention.
Under `ANY 1` of two standbys one standby may lag, so after a takeover it may need a rebuild. Patroni
closes this gap with slots on replicas, advanced to member positions that it publishes in its
coordinator. PeeGeeQ publishes no member positions; publishing them is a new design item for the owner.

**Dependency failure modes, each with a failing test before its code.**

| Dependency | Modes | Test class |
|---|---|---|
| Identity rules | colliding derived names; a derived name over 63 bytes; mixed case and `.` | `PgNodeConfigTest` (core) |
| `psql` through the command runner | failed Future; no result; non-observation output; budget exhausted | `PgSqlDurabilityControlIntegrationTest` |
| Local server | in recovery; unbounded retention; a logical slot under a member name; a slot that exists already | `PgSqlDurabilityControlIntegrationTest` |
| WAL receiver | streams from the host and port without the slot; the writer has no slot of that name | `PgSqlPromotionControlIntegrationTest` |
| Elector and store | no fresh ownership; quarantine | `PgSlotIntegrationTest` (new) |
| Port as the monitor sees it | `ensureSlots` fails during promotion (fault wrapper over the real control) | `PgPromotionIntegrationTest` |
| Retention end to end | a stopped standby's slot keeps the WAL it needs, and it resumes streaming after restart | `PgSlotIntegrationTest` |

**Checks run, 2026-11-03,** on throwaway containers of the test image `postgres:15.13-alpine3.20` (Docker
29.9.2), not on `PgReplicationFixture`. The image and the server version (`PostgreSQL 15.13`) are the
fixture's; the fixture's own `initdb` settings were not reproduced. The containers were removed afterwards.

1. **Slot creation.** With `wal_level=replica` and `max_slot_wal_keep_size=256MB`,
   `pg_create_physical_replication_slot('peegeeq_pg_node_2', true)` returned a position at once. The view
   showed `physical`, `temporary = f`, `active = f`, `wal_status = reserved`, and a `safe_wal_size` within the
   bound. A second creation under the same name failed with `replication slot "peegeeq_pg_node_2" already
   exists`. The names `pg-node-2` and `Peegeeq_X` were rejected with `contains invalid character`, so the
   derived name is required. `max_replication_slots` was `10`.
2. **Reserved position.** On a writer, an immediately reserved slot started at the redo point of the last
   checkpoint (`0/2000028`), not at the current WAL position (`0/30203D8`); after a `CHECKPOINT` a new slot
   started at the new redo point. On a standby promoted with `pg_ctl promote`, a slot created before the
   forced checkpoint started at the current position (`0/30204F0`), not at the earlier redo point
   (`0/2000028`). Creating the slots before the checkpoint after promotion therefore does not narrow the
   known gap above. Neither behaviour is stated in the documentation that was read; both are observations.
   Why the promoted server differs was not established.
3. **WAL receiver without its slot.** A standby started with `primary_slot_name` naming a slot the writer did
   not have logged `FATAL: could not start WAL streaming: ERROR: replication slot "peegeeq_pg_node_9" does not
   exist`, retried every 5.0 seconds (`wal_retrieve_retry_interval = 5s`, observed with `SHOW` and in the log
   timestamps), and showed no row in `pg_stat_wal_receiver`. After the slot was created on the writer, the
   next retry started streaming (`10:04:43.251`); the receiver then reported `streaming` with
   `slot_name = peegeeq_pg_node_9`, and the writer's slot became `active`. The longest wait after the slot
   exists is one retry interval. The PostgreSQL page for the setting returned HTTP 503 when fetched, so the
   documented default was not read.

Consequences for this design: `follow`'s 20-second budget in the tests covers one 5-second retry. A
5-second durability budget, as in `aRerunAfterACoverageFailure...`, can end before the next retry, so a slot
must exist before any coverage wait begins. Contract 4 (slots created inside `promoteStandby`) provides that.

**Classes that construct the changed types.** `PgDurabilityControl` gains a method, so every test
implementation of the port must implement it.

**Existing tests that follow a writer promoted through the adapter** (read in full, 2026-11-03; not run with
the change). Their writer is promoted with `PgPromotionControl.promote()`, not with `promoteStandby`, so it
holds no slot, and a follow under contract 6 would wait until its 20-second budget ends and fail:

| Class | Where the writer is promoted | Tests that need a successful follow (affected) | Tests not affected |
|---|---|---|---|
| `PgFollowIntegrationTest` | `setUp`: `newWriter.promote()` then `checkpoint()` | `followingTheNewWriterStreamsFromItWithAdmissionClosedAndACompletedReceipt`, `followingClosesAnOpenGrantAndAdmissionBeforeItChangesAnything`, `followingTwiceIsIdempotentAndKeepsTheReceiptCompleted`, `aRerunAfterAnUnknownReceiptCompletesTheSameReceipt` | the six that refuse or fail before an effect, or use the failing control |
| `PgSqlPromotionControlIntegrationTest` | in the test: `control(STANDBY).promote()` | `aStandbyFollowsTheNewWriterAndStreamsFromIt`, `theInstalledConnectionStringKeepsTheIdentityAndHoldsNoPassword` | the other fifteen; `aWriterThatCannotBeReached...` still fails on its unreachable host |

The change for both: after the adapter promotion, the set-up or test creates the follower's slot on the new
writer through `PgSqlDurabilityControl.ensureSlots`, the adapter step that `promoteStandby` runs in
production. `PgTakeoverIntegrationTest` promotes through `promoteStandby` and follows afterwards in every
test, so it needs no change.

**Order of work.** As P1 to P3: tests and stubs that fail with "not implemented", the red run, the
implementation, the green run, mutation probes with SHA-256 records, the module's integration classes one
at a time, the core tests, and the guards last.

**Not decided here.** The bound's value for a production deployment. Slots on replicas and published member
positions. Dropping the slot of a member removed from the membership, which belongs to controlled membership
change (§8, phase 14).

**Decisions and refinements, 2026-11-12, before the P4 tests.** On 2026-11-12 the owner directed the implementer
to proceed with best judgement. OD-27 is therefore adopted as recommended. It remains reversible, and the owner
has not reviewed the bound's trade-off. The items below refine the contracts above. None changes the data model.

- OD-27, adopted under the 2026-11-12 delegation: `ensureSlots` refuses `max_slot_wal_keep_size = -1`.
- OD-31: the membership check in `PgNodeConfig` that refuses two members equal apart from case is deleted. The
  refusal of colliding slot names subsumes it: two identities equal apart from case derive the same slot name, and
  so do two equal identities. Working agreement 1 forbids keeping both. The membership rule that remains is "this
  node is a member, and there is at least one other member". Reversible.
- OD-32: `promoteStandby` calls the adapter's `ensureSlots` directly after the checkpoint, inside the serialized
  promotion, with no receipt (OD-28) and no second ownership check. Promotion already demotes the node when
  ownership is lost before the checkpoint. The public `PgFailoverMonitor.ensureMemberSlots(held)` is a separate
  serialized operation; it runs the same step after its own ownership and quarantine checks. Reversible.
- Contract 2, detail: one SQL statement observes the recovery state, the setting of `max_slot_wal_keep_size`, and
  the named slots with their types. The refusals are checked in the order recovery, unbounded retention, logical
  slot. Each missing slot is created with its own statement. One more observation then requires every named slot
  to exist as a physical slot. The names come from `PgNodeConfig.slotName` and contain only lower-case letters,
  digits, and the underscore, so they are quoted with single quotes and need no escaping.
- Contract 6, detail: `primary_conninfo` and `primary_slot_name` are installed by two `ALTER SYSTEM` statements,
  because `ALTER SYSTEM` cannot run inside the implicit transaction of a multi-statement `psql -c`. The follow
  confirmation observes `status`, `sender_host`, `sender_port`, and `slot_name` of `pg_stat_wal_receiver`, and
  the receiver's `wait_event` in `pg_stat_activity`; it requires `WalReceiverMain`. PostgreSQL 15 sets `status`
  to `streaming` and records the sender address before `START_REPLICATION` runs, so without the wait event a
  receiver whose slot is missing on the writer was confirmed (Jenkins build #37, 2026-11-14). Probe on 15.13: with
  no slot, 2 of 3000 samples showed `streaming`, the address, and the slot with no wait event; with the slot, 3000
  of 3000 showed `WalReceiverMain`. Test: `aReceiverObservedBeforeItsReplicationStartsIsNotReportedAsFollowed`.
- The fixture's bound is `max_slot_wal_keep_size = 256MB`, the value of check 1.
- OD-33, added 2026-11-12 after the first P4 report: contract 2 says a `lost` slot "is reported in the failure
  of a later coverage check". The P4 code of §9.25 did not do that: `confirmCovered` observed only
  `pg_stat_replication`, so a required standby whose slot was lost waited out the whole budget and was reported
  as "not connected". Refinement: each coverage observation also reads `wal_status` of the required standbys'
  member slots. A required standby whose slot is `lost` fails coverage at once with a `PgDurabilityException`
  that names the slot and the standby, as an identity mismatch does, because waiting cannot restore the removed
  WAL. A missing slot changes nothing here; `serve` and `promoteStandby` create the slots before any coverage
  wait. `ensureSlots` still accepts a lost slot and does not recreate it. Reversible.

### 9.25 Phase 7b.3, Increment P4: Implementation and Verification, 2026-11-12

**Status, 2026-11-12.** Implemented on `993bf2c2` plus the uncommitted P4 change. Green, mutation-probed, and the
module's whole integration tier, core tests, and guards green. Every count below is from a run made on 2026-11-12
on this working tree, on Vert.x 5.1.10.

**What was built.**

- `PgNodeConfig.slotName(nodeId)` and the refusal of colliding slot names and names over 63 bytes. The case-only
  membership check is deleted (OD-31).
- `PgDurabilityControl.ensureSlots(nodeIds)` and its adapter in `PgSqlDurabilityControl` (contract 2).
- `PgFailoverMonitor.ensureMemberSlots(held)` (contract 3), and the slot step at the end of `promoteStandby`
  (contract 4, OD-32).
- `PgPolicyCutover.serve` runs `ensureMemberSlots` first (contract 5). `PgBootstrap.serve` and `PgTakeover.cutover`
  both reach it.
- `PgSqlPromotionControl.follow` installs `primary_slot_name` and confirms streaming through that slot (contract 6).
- `PgReplicationFixture` sets `max_slot_wal_keep_size = 256MB` and takes each base backup with `-C -S <slot>`
  (contract 7).

**Tests.** 3 core tests in `PgNodeConfigTest`. 7 new tests in `PgSqlDurabilityControlIntegrationTest`, and
`ensureSlots` added to its malformed-output and no-result tests. 2 new tests in `PgSqlPromotionControlIntegrationTest`,
and 3 of its follow tests changed. 2 new tests in `PgPromotionIntegrationTest`. The new `PgSlotIntegrationTest`
with 4 tests. 1 new test in `PgBootstrapServingIntegrationTest`. `PgFollowIntegrationTest` creates the follower's
slot in its set-up, and its streaming check requires the slot. `PgTakeoverIntegrationTest`'s main test asserts the
new writer's slots. The four test implementations of `PgDurabilityControl` pass `ensureSlots` through. Every failure
mode of the §9.24 table has a test, except one: the design table lists "streams from the host and port without
the slot". A receiver that streams from the right address with no slot is what probe 11 produced; the test that
guards it is `theInstalledSettingsKeepTheIdentityNameTheSlotAndHoldNoPassword`, and the test with a deterministic
wrong slot is `aReceiverThatStreamsFromTheWriterThroughAnotherSlotIsNotReportedAsFollowed`.

| Step | Evidence under `logs/` |
|---|---|
| Rebuild with stubs | `rebuild-peegeeq-pg-failover-slots-stubs-20261112.log`: `BUILD SUCCESS` |
| Red, core | `peegeeq-pg-failover-slots-config-red-20261112.log`: `Tests run: 20, Failures: 1, Errors: 2`. The 3 new tests fail: 2 on "not implemented", 1 because `[pg-node-1, pg_node_1]` was accepted |
| Green, core | `peegeeq-pg-failover-slots-config-green-20261112.log`: `Tests run: 20, Failures: 0` |
| Red, integration | Only the new and changed methods. Durability 9 run, 7 failures, 2 errors; promotion adapter 5 run, 1 failure, 3 errors (the refusal test with a stronger assertion passed, as its behaviour was unchanged); promotion 2 of 2 failed; slot class 4 of 4 failed; serving 1 of 1 failed; follow and takeover 2 of 2 failed, both because the follower's slot did not exist on the new writer. Logs `peegeeq-pg-failover-slots-red-*-20261112.log` |
| Green, integration | The same method sets: 9, 5, 2, 4, 1, and 2 run, 0 failures. Logs `peegeeq-pg-failover-slots-green-*-20261112.log` |
| SHA-256 around the probes | `peegeeq-pg-failover-slots-mutation-sha-before-20261112.log` and `...-sha-after-20261112.log`: identical for the 5 production files. Rebuilt from the restored source: `rebuild-peegeeq-pg-failover-slots-restored-20261112.log` |
| Regression, integration, whole module | 16 classes, **244** tests, 0 failures: durability 26 (334 s), promotion adapter 19 (257 s), promotion 17 (291 s), bootstrap serving 15 (303 s), policy installation 14 (201 s), follow 10, takeover 9, slot 4, bootstrap 7, process control 11, fixture contract 4, admission gate 7, elector queueing 4, Consul coordinator 61, monitor 23, renewal loop 13. Logs `peegeeq-pg-failover-slots-regress-*-20261112.log`. These 16 are every test file in the module with the integration tag |
| Module core tests | `peegeeq-pg-failover-slots-core-20261112.log`: `Tests run: 398, Failures: 0` across 9 classes |
| Guards, after the last test edit | `peegeeq-test-support-slots-guards-20261112.log`: `Tests run: 77, Failures: 0` across 14 classes |

Mutation probes, each one removal, a run of the guarding methods, and a restore from a copy
(`peegeeq-pg-failover-slots-mutation<N>-red-20261112.log`). Each probe failed its guarding test:

| N | Removal | Guarding test failure |
|---|---|---|
| 1 | Slot-name distinctness in `PgNodeConfig` | `rejectsMembersWhoseSlotNamesCollide`; also `rejectsCaseInsensitiveMemberCollision`, which OD-31 now rests on |
| 2 | 63-byte limit in `slotName` | `rejectsAnIdentityWhoseSlotNameExceeds63Bytes` |
| 3 | Refusal in recovery | `slotsAreRefusedOnAServerInRecoveryAndNoneIsCreated`: PostgreSQL created the slot on the standby |
| 4 | Refusal of an unbounded retention | the adapter test and `anUnboundedSlotRetentionRefusesServingBeforeAnyPolicyIsInstalled` |
| 5 | Refusal of a slot that is not physical | `aLogicalSlotUnderAMemberSlotNameFailsTheCallAndIsKept` |
| 6 | Creation only of missing slots | `aSlotThatExistsIsNeitherRecreatedNorMoved...`: `replication slot "peegeeq_pg_node_2" already exists` |
| 7 | Slot step in `promoteStandby` | both new `PgPromotionIntegrationTest` tests |
| 8 | Ownership check in `ensureMemberSlots` | `withoutFreshOwnershipNoSlotIsCreated` |
| 9 | Quarantine check in `ensureMemberSlots` | `aQuarantinedNodeCreatesNoSlot` |
| 10 | Slot step in `PgPolicyCutover.serve` | `anUnboundedSlotRetentionRefusesServingBeforeAnyPolicyIsInstalled` |
| 11 | Installation of `primary_slot_name` in `follow` | `theInstalledSettings...`: last observed `streaming\|pg-node-2\|5432\|`, a receiver with no slot |
| 12 | Slot in the streaming confirmation | `aReceiverThatStreamsFromTheWriterThroughAnotherSlot...`. `aWriterWithoutTheFollowersSlot...` passed under this probe: with no slot on the writer the receiver never streams, so that test cannot tell the two apart |

**Finding 13 on Vert.x 5.1.10.** 19 runs today had failing tests, among them every fixture-based red and probe run.
None of their logs holds "Interrupted while waiting for command" or `RejectedExecutionException`. Under 5.0.4 every
failing P3 run held the first line (§9.23). The one "Unexpected ERROR" entry in a red log is the red follow's own
failure, attached to the failing test. This is an observation from these logs. It agrees with the purpose of
`VertxTeardownAfterFailureExtension` (register, 2026-11-08), but no run here isolates that extension as the cause.

**Correction after the first report: a lost slot (OD-33).** The first P4 report listed "a `lost` slot" as not
checked. That was a wrong judgement: contract 2 claims the behaviour, and OD-27 rests on it, so it was an unmet
contract of this increment. The red run proved the defect on a real server. With `max_slot_wal_keep_size=32MB`,
`wal_keep_size=0`, a stopped standby, and 96 MB of WAL, the slot reached `wal_status = lost`. Coverage then waited
its 20-second budget and reported "Required standby pg-node-3 is not connected"
(`peegeeq-pg-failover-lostslot-red-20261112.log`). After the change, coverage fails at once with "Replication slot
peegeeq_pg_node_3 of required standby pg-node-3 is lost" (`...-lostslot-green-20261112.log`, 1 of 1). Probe 13
removed the check, and the test failed with the old message (`...-lostslot-mutation13-red-20261112.log`). The adapter's
SHA-256 is `FB14304D...A66AA3` before and after (`...-lostslot-mutation-sha-before-` and `...-sha-after-20261112.log`).
Regression after the change, every class that reaches `confirmCovered`, one at a time: durability 27, promotion 17,
bootstrap serving 15, policy installation 14, follow 10, takeover 9, slot 4, all 0 failures
(`peegeeq-pg-failover-lostslot-regress-*-20261112.log`). Core 398 (`...-lostslot-core-20261112.log`). With the 9
classes of the first regression that do not reach coverage, the module's integration tier is 16 classes and 245
tests.

**Known gap, unchanged from §9.24.** A new writer's slots reserve WAL from their creation. A surviving standby that
lagged before the takeover may need a rebuild.

**Not checked.** A production entry point; nothing outside tests calls `ensureMemberSlots`. What re-join does with a
lost slot: a rebuilt member's base backup with `-C` fails while the lost slot exists, so re-join must drop it; that
belongs to controlled re-join (7b.3). The bound's value for a production deployment. Slots on a cascading node. The
Jenkins build and the whole-repository gate.

### 9.26 Phase 7b.3, Increment P5: Replacing a Lost Member Slot for a Rebuild, 2026-11-12 (design)

**Purpose.** After OD-33 a writer reports a required standby whose slot is lost. That standby must be rebuilt
(S31). A rebuild takes a new base backup. While the lost slot exists, a base backup with `-C -S <slot>` fails,
because the name is taken, and one with `-S <slot>` alone fails, because a lost slot cannot be used. P5 adds the
writer-side step of a rebuild: replace exactly a lost member slot by a new slot with a reserved position. The
rebuild then takes its base backup through that slot, so the writer keeps the WAL the backup needs from before the
backup starts. Who decides to rebuild, and the rebuild commands on the member, stay with §9.20 row 10, which awaits
the owner. The fixture performs the member side, as it stands for the deployment (OD-10).

**Data model.** No stored field is added. Whether a slot is lost is derived live from `wal_status` in
`pg_replication_slots`. The slot name is derived (§9.24).

**Contracts.**

1. `PgDurabilityControl.replaceLostSlot(String nodeId)` (adapter `PgSqlDurabilityControl`). The same first
   observation as `ensureSlots`, so it is refused in recovery, with an unbounded retention, and for a slot under the
   member name that is not physical. It is also refused when the member slot does not exist, and when it exists and
   `wal_status` is not `lost`: a slot that keeps WAL is never dropped. Otherwise it drops the slot, creates it again
   with a reserved position, and confirms that the slot exists as a physical slot whose `wal_status` is not `lost`
   and whose `restart_lsn` is set.
2. `PgFailoverMonitor.replaceLostMemberSlot(held, nodeId)`. Serialized. Refused without fresh ownership of exactly
   `held` (`PgLeaseProtocolException`), for an identity that is not another member of the membership
   (`IllegalArgumentException`), and under quarantine (`PgLocalStateException`). Then calls `replaceLostSlot`.
3. Member side, in the fixture: stop the member, remove its data directory, take the base backup with
   `-S <slot> -R` and no `-C`, start it in recovery. Coverage of the member then succeeds again.

**Decision of the implementer, 2026-11-12, under the owner's direction to proceed with best judgement. Reversible.**

- OD-34: no receipt. The effect is observable. A rerun after a lost reply is refused, because the new slot is not
  lost; the caller observes the slot, which is reserved. A receipt would add a record whose content the slot
  already shows.

**Dependency failure modes, each with a failing test before its code.**

| Dependency | Modes | Test class |
|---|---|---|
| `psql` through the command runner | failed Future, no result, malformed output, budget (the shared path of `ensureSlots`) | `PgSqlDurabilityControlIntegrationTest`: the existing no-result and malformed-output tests gain the call |
| Local server | in recovery; unbounded retention; logical slot; slot missing; slot not lost | `PgSqlDurabilityControlIntegrationTest` |
| Elector, store, membership | no fresh ownership; quarantine; this node or a non-member | `PgSlotIntegrationTest` |
| End to end | lost slot replaced, member rebuilt through it, coverage succeeds | `PgSlotIntegrationTest` |

**Test infrastructure.** `PgReplicationFixture` gains `loseSlot` (stop the standby, restart the writer with
`max_slot_wal_keep_size=32MB` and `wal_keep_size=0`, write 96 MB of WAL, checkpoint until the slot is lost) and
`rebuildThroughSlot` (contract 3). The OD-33 test moves to `loseSlot`.

**Status, 2026-11-12.** Implemented, green, mutation-probed, and the module green on this working tree.

| Step | Evidence under `logs/` |
|---|---|
| Red, against stubs | `peegeeq-pg-failover-replace-red-20261112.log`: 12 run, 9 failures, 2 errors. The 11 new and changed methods fail on "not implemented" or the missing refusal. The twelfth, the OD-33 test on the new `loseSlot`, passes |
| Green | `peegeeq-pg-failover-replace-green-20261112.log`: 12 run, 0 failures. The end-to-end test loses the slot, replaces it, rebuilds the member through it, and confirms coverage and `streaming\|peegeeq_pg_node_3` |
| Probes 14 to 19 | `...-replace-mutation<N>-red-20261112.log`. 14: the "not lost" refusal (failure: `replication slot "peegeeq_pg_node_2" is active for PID 119`); 15: the "does not exist" refusal (the missing slot is reported as "not lost"); 16: the drop and re-creation (the adapter's own check: `wal_status 'lost'`); 17: the ownership check; 18: the membership check (the writer looks for its own slot); 19: the quarantine check. SHA-256 of both files equal before and after (`...-replace-mutation-sha-before-` and `...-sha-after-20261112.log`) |
| Regression, integration | 16 classes, **251** tests, 0 failures: durability 29 (416 s), promotion adapter 19, promotion 17, bootstrap serving 15, policy installation 14, follow 10, takeover 9, slot 8, the five small classes 33, monitor, Consul coordinator, and renewal loop 97. Logs `peegeeq-pg-failover-p5-regress-*-20261112.log` |
| Core | `peegeeq-pg-failover-p5-core-20261112.log`: 398, 0 failures |

**Test-shape finding, fixed in the same change.** Probe 14's first run showed a defect in test structure. A test
that judges several refusals in a chain of `transform` steps passes a failed assertion on to the next step as its
input. That step then fails with "Unexpected type", and the first message is lost. Where the step before the
`transform` is set-up, a set-up failure of the expected exception type is accepted as the refusal under test, so a
test can pass for the wrong reason. Sites: 6 in `PgSqlDurabilityControlIntegrationTest`, 1 in `PgSlotIntegrationTest`,
3 in `PgCtlProcessControlIntegrationTest`, 2 in `PgSqlPromotionControlIntegrationTest`, 2 in `PgLocalStateStoreTest`,
and 1 each in `PgBootstrapServingIntegrationTest`, `PgTakeoverIntegrationTest`, `PgLeaseCoordinatorContract`, and
`PgPrimaryElectorQueueingIntegrationTest`. The sites where set-up shared the expected type are
`PgLeaseCoordinatorContract.coordinatorRejectsAcquisitionOfHeldRecord`,
`PgLocalStateStoreTest.receiptIdentityRejectsUnsafeNames`, and
`PgLocalStateStoreTest.quarantineClosesTheGrantAndBlocksAdmissionAcrossRestart`. Each site now attaches its
`transform` to the operation it judges and continues with `compose`. Three classes use a `refused(outcome, prefix)`
helper, which replaced their `assertNoResult`. After the fix the second run of probe 14 named the cause. The scan
for the shape (a line that starts with `}).transform(`) finds no site in the module. Other shapes were not scanned.

**Not checked.** Who decides a rebuild, and the member-side rebuild commands outside the fixture (§9.20 row 10,
awaiting the owner). A rerun after a lost reply, which is refused by design (OD-34). The durability class takes
7 minutes in one command; the tool limit is 10.

### 9.27 Phase 7b.3, Increment P6: Slots on Standbys, Advanced to Published Member Positions, 2026-11-12 (design and implementation)

**Purpose.** Close the known gap of §9.24. The writer's slot for a standby keeps that standby's WAL on the writer.
When the writer's host is lost, that WAL is lost with it. The new writer kept only its own retention, so a
standby that was behind it cannot follow it and must be rebuilt. Patroni closes this with a physical slot for
each other member on every standby, advanced to positions that each member publishes in its coordinator (§9.20
row 20, `member_slots_ttl` aside). This section designs the same. It changes the coordinator port and the system
design's data model, so it is for the owner's review before any test is written.

**Correction to §9.24.** The known gap names a standby that lags under `ANY 1`. The case the checks below
reproduce is a standby that is stopped or disconnected when the writer fails, while the writer kept writing WAL.

**Checks run, 2026-11-12,** on throwaway containers of `postgres:15.13-alpine3.20` (PostgreSQL 15.13): one writer
with `wal_keep_size = 0` and `max_slot_wal_keep_size = 256MB`, and three standbys s2, s3, s4 taken with
`-C -S`. The shell script ran from the session scratchpad and is not kept; the outputs that matter are quoted
below, and the full output is in `logs/p6-replica-slot-checks-20261112.log` and
`logs/p6-replica-slot-checks-control-20261112.log` on this machine. The containers were removed afterwards.

1. A physical slot created on a standby with an immediately reserved position starts at the standby's last
   restartpoint redo (`0/2000028`), not at its replay position (`0/5025A88`).
2. `pg_replication_slot_advance` advances a physical slot on a standby to a given position. A target beyond the
   standby's replay position is capped at that position without an error (`FFFFFFFF/0` left the slot at
   `0/5025A88`).
3. Across a writer checkpoint and a standby restartpoint after 96 MB of WAL, the standby keeps the WAL from its
   slot's position: the oldest WAL file on s2 was `...05` with the slot, and `...0B` without it.
4. A slot on a standby survives `pg_ctl promote` with its position unchanged (`rs_s3|physical|0/5025A88|reserved`).
5. s3 and s4 stopped at `0/5025A88`. After the WAL and the promotion of s2, both were pointed at s2.
   - With a slot on s2 for s3 advanced to s3's position, s3 streamed on timeline 2 and replayed to the new writer's
     position. s4 also streamed, because s3's slot kept the same WAL; this run does not isolate s4.
   - Control, with no slot on s2 before promotion and the followers' slots created after it (as P4 does): both
     failed with `requested WAL segment 000000010000000000000005 has already been removed` and retried. This is the
     gap, observed for the first time.

**Data model.**

| Datum | Source of truth or derivable | Contract |
|---|---|---|
| A member's WAL position: timeline and the flush position of its WAL (on a standby, `received_tli` and `flushed_lsn` of the streaming WAL receiver in `pg_stat_wal_receiver`; on the writer, the timeline of `pg_walfile_name(pg_current_wal_flush_lsn())` and that flush position) | Derived live on that member | Observed by the member's own supervisor only. A standby without a streaming receiver has no position |
| Published member position | A copy of that observation in the coordinator, one record per member, written only by that member's supervisor | Not authority. It is used for one thing: to advance slots on standbys. It is never read for eligibility, promotion, coverage, routing, or policy. A stale value keeps more WAL, which is safe; a missing value leaves a slot unadvanced, which is also safe. No timestamp is stored |
| Slots a node holds | Derived: the membership minus this node, on the writer and on every standby | §9.24's rule extended to standbys. The writer's slots advance by streaming; a standby's slots advance only by this design |
| A slot's position and `wal_status` on any node | Derived live from `pg_replication_slots` | Never stored |

Why a published position cannot cause loss. A slot is advanced to `min(published position, local replay position)`
(check 2 caps the second term in PostgreSQL itself) and only when the published timeline equals the local replay
timeline. A member's flush position never decreases within a timeline. A published value is therefore at most the
member's present position, and a slot advanced to it still keeps every segment the member needs. Advancing never
moves a slot backwards: the call is made only when the target is above the slot's `restart_lsn`.

**Contracts.**

1. A new port, `PgMemberPositions`, beside `PgLeaseCoordinator`: `publish(nodeId, timeline, lsn)` and
   `read()` returning every published member position. Obligations: a member writes only its own record (access
   control); a malformed, partial, filtered, or unauthenticated response fails; reads need not be linearizable,
   because a stale value is safe. A failed or lost publish leaves the previous value. It has its own contract suite;
   the lease contract suite is unchanged.
2. Consul binding: KV key `peegeeq/pg/<clusterId>/<incarnation>/members/<nodeId>`, value
   `{"timeline": n, "lsn": "X/Y"}`, plain PUT without a session. A delayed older PUT can overwrite a newer one;
   that only keeps more WAL. ACL: each node's token may write its own member key and read the members prefix.
3. `PgDurabilityControl.ensureSlots` no longer refuses a server in recovery. P4's refusal (§9.25 probe 3) is
   replaced by the rule that every node holds the slots of the membership minus itself (OD-35).
4. `PgDurabilityControl.advanceSlots(Map<nodeId, position>)` on a standby: for each member slot that exists, is
   physical, is not lost, and whose published timeline equals the local replay timeline, advance it to
   `min(published, local replay)` when that is above its `restart_lsn`. Every other slot is left unchanged and
   reported in the result, not failed.
5. `PgFailoverMonitor.publishPosition()` (any node, no lease) and `PgFailoverMonitor.advanceMemberSlots()` (a
   standby, no lease, refused under quarantine). They run in the supervisor's loop once one exists; until then,
   tests call them.
6. `promoteStandby` is unchanged: its slot step (OD-32) now finds the advanced slots and keeps them (§9.24 contract
   2, existing slots are never recreated).

**Decisions of the implementer, 2026-11-12, under the owner's direction to proceed with best judgement. Reversible.
Each one needs the owner's review before tests, because together they change the port and the system design.**

- OD-35: every node holds the member slots (contract 3). The cost is WAL on each standby up to the bound.
- OD-36: a separate port for member positions, not new operations on the lease port. Reason: the lease port's
  obligations (linearizable reads, atomic conditions) are stricter than positions need, and its contract suite
  qualifies writer exclusion; positions must not weaken or widen it.
- OD-37: the writer also publishes its position, so standbys advance the writer's slot too. Without it each standby
  keeps the writer's slot unadvanced until the bound loses it.
- OD-38: no session or TTL on member records (Patroni ties member keys to the member's session). A departed
  member's record stays; removing it belongs to membership change (phase 14).
- OD-39: positions are not authority, and the system design §1.1 row "Node role, WAL positions, … Derived" gains
  this one published, non-authoritative copy. §5.8 step 3 ("do not persist live WAL positions as authority") is
  unchanged: coverage still observes live WAL.

**Design-document changes, to be made only after review.** System design §1.1 (a row for published member
positions), §5.10 (the second port, its obligations, and its contract suite), and §5.8's lifecycle note on
re-join. Coordinator design §1.1 and §3.3 (the members key, its ACL). §9.20 row 20 is a dated snapshot and is not
edited.

**Dependency failure modes, each with a failing test before its code.**

| Dependency | Modes | Test class |
|---|---|---|
| Coordinator, publish and read | failed Future, lost reply, ACL refusal, malformed value, filtered read, no record for a member | new `PgMemberPositionsContract` with a Consul suite |
| Local server, observation | in recovery or not; no receiver position yet; malformed output; budget; no result | `PgSqlDurabilityControlIntegrationTest` (or a new class: it is near the 10-minute limit) |
| Local server, advance | slot missing; logical; lost; other timeline; target at or below `restart_lsn`; target above replay (capped) | a new adapter test class |
| Store | quarantine | `PgSlotIntegrationTest` |
| End to end | the check-5 scenario on the fixture: a standby stopped, WAL written, the writer lost, the stopped standby follows the new writer and is covered; the same without positions fails | `PgTakeoverIntegrationTest` or a new class |

**Order of work, after review.** P6a the port, the Consul adapter, and its contract suite. P6b `publishPosition`.
P6c OD-35 and `advanceSlots`. P6d the end-to-end test. Each one: tests and stubs, red, implementation, green,
probes, the touched classes, core, guards.

**Not decided here.** How often a node publishes and advances (the supervisor loop does not exist yet). Removing a
departed member's record and slots. A cascading standby.

**Adopted, 2026-11-12.** The owner directed the implementer to finish P6 rather than stop for review. OD-35 to
OD-39 are adopted as written above and stay reversible. Refinements made before the tests:

- OD-40: publication and advancing live in a new class, `PgMemberSlots`, not in `PgFailoverMonitor`. Reason: the
  monitor would need a ninth constructor argument (OD-29 avoided one), and neither step needs the monitor's
  serialization. A published position is never authority, and advancing a slot to `min(published, local replay)` is
  safe whatever runs at the same time. `PgMemberSlots` refuses both steps under quarantine: a quarantined node's
  position can belong to a diverged history.
- OD-41: the new port's failures use `PgLeaseProtocolException`, the coordinator protocol failure of the lease
  port. Its tests are one Consul class, `ConsulMemberPositionsIntegrationTest`, written against the port. A second
  adapter moves them into a contract class, as the lease suite is.
- Contract 4, detail: `advanceSlots` refuses a writer (a writer's slots advance by streaming), refuses a
  non-physical slot under a member name (as `ensureSlots` does), and skips, without failing, a missing slot, a lost
  or unreserved slot, a member with no published position, a position on another timeline, a target at or below the
  slot's `restart_lsn`, and a standby with no streaming WAL receiver (its timeline is then unknown). It returns the
  members whose slots it advanced. The local timeline is `received_tli` of the streaming WAL receiver.
- Contract 3, detail: `replaceLostSlot` keeps its refusal in recovery: a lost slot is replaced on the writer for a
  rebuild. Only `ensureSlots` drops the refusal.

**As built, where the code differs from the design text above.** The code is the record; this list states each
difference once.

- Contract 1: `publish(PgMemberPosition)` takes one validated record (`nodeId`, `timeline` of at least 1, `lsn`),
  not three arguments. `close()` is the third operation.
- Data model, first row: the standby position is `received_tli` and `flushed_lsn` of the streaming WAL receiver, not
  `pg_last_wal_receive_lsn()`, so that the timeline and the position come from one row. The row above is corrected.
- Contract 4: `advanceSlots` returns the members whose slots it advanced. The slots it left unchanged are not
  reported. A non-physical slot under a member name fails the call (refinement above).
- Contract 5: superseded by OD-40. The two steps are `PgMemberSlots.publishPosition` and `advanceMemberSlots`, not
  `PgFailoverMonitor` methods. `advanceMemberSlots` first runs `ensureSlots` for the other members.
- Dependency table: `PgMemberPositionsContract` was not written (OD-41). The Consul suite is
  `ConsulMemberPositionsIntegrationTest`. Position observation and slot advance are tested in the new class
  `PgSqlMemberSlotIntegrationTest`, not in `PgSqlDurabilityControlIntegrationTest`.

**Status, 2026-11-12.** Implemented on the working tree, green, mutation-probed, and the module green. The system
design (§1.1, §5.8, §5.10) and the coordinator design (§1.1, §3.3) now describe member positions and member slots.

**What was built.** `PgMemberPosition` (record, validated); the port `PgMemberPositions`; its Consul adapter
`ConsulMemberPositions`; `PgDurabilityControl.position` and `advanceSlots` with their SQL adapter; OD-35 in
`ensureSlots`; `PgMemberSlots.publishPosition` and `advanceMemberSlots`; `PgReplicationFixture.writeWal`.

| Step | Evidence under `logs/` |
|---|---|
| P6a red | `peegeeq-pg-failover-p6a-red-core-` and `...-red-consul-20261112.log`: the core rejection test and all 10 Consul tests fail on the stub or the missing refusal |
| P6a green | `...-p6a-green-core-20261112.log` 2 of 2; `...-p6a-green-consul-run2-20261112.log` 10 of 10. The first green run failed one test because the test's ACL rule was wrong: a `list` rule on the prefix lets a recursive read return every value on Consul 1.22.1 (probe of the server, recorded in coordinator design §3.3). The test was corrected, not the adapter |
| P6b to P6d red | `...-p6bcd-red-adapter-` and `...-red-slot-takeover-20261112.log`: every new and changed test fails on a stub or on P4's refusal in recovery, except `withoutSlotsOnStandbysAStandbyStoppedBeforeTheTakeoverCannotFollowTheNewWriter`, which passes before and after: it is the gap on the real fixture, the standby's log showing `has already been removed` |
| P6b to P6d green | `...-p6bcd-green-adapter-` 11 of 11, `...-green-slot-takeover-` 5 of 5, `...-p6-green-timeline-20261112.log` 2 of 2 after the timeline assertions were added. End to end: a standby stopped before the takeover follows the new writer through the slot the new writer kept for it as a standby, is covered, and both nodes then publish positions on timeline 2 |
| Probes 20 to 31 and 33 | 13 probes; number 32 was not used. `...-p6-mutation<N>-red-20261112.log`; SHA-256 of the 4 files equal before and after (`...-p6-mutation-sha-before-` and `...-sha-after-`). 12 of 13 detected: 20 own-node check, 21 filtered read, 22 membership of a key, 24 timeline match, 25 forward only (`cannot advance replication slot to 0/2000027, minimum is 0/2000028`), 26 unreserved or lost skip, 27 no-receiver skip, 28 writer refusal, 29 the writer's timeline (a constant 1 fails the timeline-2 assertion), 30 quarantine, 31 the slot step on a standby, 33 the record's timeline check. Not detected: 23, the cap at the replay position. PostgreSQL applies the same cap (check 2), so the code's cap is a second layer that no test can separate; it is kept because it states the rule |
| Regression, integration | 18 classes, **276** tests, 0 failures: durability 29 (403 s), takeover 11, promotion adapter 19, promotion 17, bootstrap serving 15, policy installation 14, follow 10, slot 11, member-slot adapter 10, Consul member positions 10, the five small classes 33, monitor, Consul coordinator, and renewal loop 97. Logs `...-p6-regress-*-20261112.log` |
| Core | `...-p6-core-20261112.log`: 400, 0 failures |
| Javadoc correction after the regression | The `PgDurabilityControl` Javadoc of `ensureSlots`, `replaceLostSlot`, and `advanceSlots` still stated P4's refusal in recovery and called a non-physical slot a skip. Comment-only edit; `rebuild-peegeeq-pg-failover-p6-javadoc-20261112.log` BUILD SUCCESS; core and guards re-run after it (`...-p6-javadoc-core-` and `peegeeq-test-support-p6-javadoc-guards-20261112.log`) |

**Simplification during P6.** `PgMemberSlots` first filtered the published positions to the other members. The read
admits members only and a node holds no slot of its own, so the filter changed nothing and a probe of it could not
be detected; it was deleted before the probes.

**Not checked.** A lost slot on a standby (the advance skips it by the same branch as an unreserved slot, which is
tested). A non-physical slot under a member name on a standby, which PostgreSQL 15 cannot create (logical slots on a
standby arrive in PostgreSQL 16). How often a node publishes and advances; there is no supervisor loop yet, so
nothing outside tests calls `PgMemberSlots`. A re-joining former writer streaming through its advanced slot. A second
coordinator adapter.

### 9.28 Owner Decisions of 2026-11-13, and Increment P7: Session-Bound Member Positions, Their Contract, the Monitor Builder, and Candidate Ranking (design)

**Owner decisions, 2026-11-13.** The owner answered fifteen questions on the open decisions of §9.20, §9.24, and §9.27,
then directed: "After verification proceed with implementation. Do not stop to ask me any questions." The questions
were asked before the whole document set and the module were read. The full reading was done afterwards, on the same
day: the five design documents, this plan, the Task 8 register entries, every production file of
`peegeeq-pg-failover`, and its test classes. Each row below states the decision and every conflict that reading found,
with its location. Where a decision conflicts with a design document, the document is changed in the increment that
implements the decision, not before.

| # | Item | Decision | Conflicts found, and what changes |
|---|---|---|---|
| 1 | OD-27 | Kept: an unbounded `max_slot_wal_keep_size` is refused | None. OD-27 is reviewed |
| 2 | OD-35 | Kept: every node holds a slot for each other member | None |
| 3 | OD-36 | Kept: member positions have their own port | None |
| 4 | OD-37 | Kept: the writer publishes its position too | None |
| 5 | OD-38 | Reversed: each member's record is held by that member's own coordinator session, and disappears when the session expires (Patroni's member keys) | System design §1.1, row "Published member position" ("It has no lease and no expiry"); §5.10, "Member positions" ("It has no lease and no expiry"); coordinator design §3.3, row "Member positions" ("plain PUT with no session"). Built code: `ConsulMemberPositions`. Increment P7b |
| 6 | OD-39 | Reversed in part: published positions rank the takeover candidates, as Patroni's leader race does | System design §1.1, same row ("Never read for eligibility, promotion, coverage, routing, or policy"); §5.10 ("a position is never authority"); coordinator design §1.1 ("Never an eligibility, promotion, or policy input"); OD-17 (§9.21). Eligibility stays membership in the last confirmed policy (system design §5.8 "Only nodes covered by the prior acknowledged-commit policy are eligible targets"; S40). A position only orders eligible candidates; it never makes a node eligible. Increment P7d |
| 7 | OD-40 | Reversed: publication and slot advancing move into `PgFailoverMonitor`, which is built by a builder instead of a constructor | OD-21 (§9.22), OD-29 (§9.24), and OD-40 each avoided a ninth constructor argument. That reason is removed. OD-21 and OD-29 keep their placement, which has other reasons. Increment P7c |
| 8 | OD-41 | Reversed: the positions port gets its own exception and a contract suite now, with Consul as the first binding | None beyond §9.27 "As built". Increment P7a |
| 9 | §9.20 row 10 | Rewind and rebuild are automatic; when a rewind fails, the data directory is removed and the member is rebuilt | Reverses P-6 (§5, "Rewind or rebuild is operator-controlled under quarantine"). Consistent with S31 and system design §5.9: quarantine is kept during the rewind or rebuild, a failed rewind is followed by a rebuild, and admission waits for standby validation (invariant 6). Operator reconciliation stays required for missing history, unexpected key deletion, two writable nodes, and restored coordination state (system design §5.7), and for missing policy history or conflicting receipts (§5.8). Coordinator design §6 rewind prerequisites apply. Its own increment |
| 10 | §9.20 row 9 | Restart (conditional and scheduled), reload, and `pending_restart` | Not in the design. Its own increment |
| 11 | §9.20 row 19 | Member tags `nofailover`, `nosync`, `noloadbalance`, `clonefrom` | `nosync`: a member that is never in a required set is never covered, so it is never a promotion target (§5.8); `nosync` therefore implies no promotion. `noloadbalance` needs a read-only replica endpoint, which does not exist (§9.20 row 16). `clonefrom` selects a rebuild source (row 10). Its own increment |
| 12 | §9.20 row 15 | Cluster-wide settings in the coordinator, changed by a guarded edit | System design §1.1, row "Lease TTL, HA-loop interval, retry budget, watchdog mode/device, and manual/automatic mode" ("Authoritative cluster configuration"); §5.4. Identity, membership, endpoints, the coordinator selection, and credentials stay in deployment configuration, because the supervisor needs them to reach the coordinator, and membership changes stay with phase 14 (S39). Every stored value must satisfy the timing rule of §5.4. Its own increment |
| 13 | §9.20 row 17 | `/cluster`, `/history`, a node status endpoint, and `/metrics` | Sidecar guide §1.1 and system design §9.1 ("Other paths: 404"); the sidecar is read-only and observes only eligibility. The supervisor has no HTTP server. The placement is decided in that increment's design. Its own increment |
| 14 | §9.20 row 23 | Callbacks `on_start`, `on_stop`, `on_restart`, `on_reload`, `on_role_change`; each failure logged at ERROR, none blocks the operation | The optional-step pattern of the coding principles §3 applies. Its own increment |
| 15 | §9.20 row 26 | Adopting an existing cluster is not supported | None; bootstrap keeps refusing existing history (§5.11) |

The owner also asked for these decisions not to be lost. They are this table; the register entry of 2026-11-13 points
to it.

**Order of work.** P7 (answers 5 to 8) comes first, because the next item of §9.27, takeover after a real lease
expiry, depends on the monitor's construction and on the candidate rule. Then, in order: takeover after real lease
expiry; planned stop with release (OD-15); member tags (11); automatic rewind and rebuild (9); restart and reload (10);
coordinator configuration (12); the status and metrics endpoints (13); callbacks (14). Each is designed in its own
section before its tests.

#### P7 data model

| Datum | Source of truth or derivable | Contract |
|---|---|---|
| Published member position | Coordinator record per member, under the incarnation's `members/` prefix, held by that member's own session; a copy of the member's live observation | It exists only while the member's session lives. A member that stops publishing loses its record when the session expires. It is never authority for exclusion, coverage, policy, or routing. It orders covered candidates (P7d) |
| Member session | Coordinator metadata, created and renewed by the adapter; its identifier is held in the adapter's memory only | One per member supervisor, separate from the writer lease, so the lease port and its contract are unchanged (OD-36). TTL equals the lease TTL. Expiry deletes the records it holds. Closing the adapter does not destroy it: the record ends at expiry |
| Candidate order | Derived on each call from this node's live position and the published positions of the other covered candidates | Never stored |
| Candidate position of a standby | Derived live: the larger of its received and replayed WAL position, on the timeline of its WAL receiver, or of its last restartpoint when it has no receiver | Observed after the writer is lost, when the standby no longer streams. Never published |

#### P7 contracts

1. **P7a, own exception and contract suite.** Every failure of `PgMemberPositions` is a `PgMemberPositionException`,
   including a synchronous throw. `PgMemberPositionsContract` states the port's obligations as tests: a publication is
   read back by every member; a later one replaces the earlier; an empty namespace reads as no positions; another
   node's position is refused before anything is sent; a publication the service rejects fails; a malformed record and
   a record of a non-member fail the read; a filtered read fails; an unanswered request fails within the request
   deadline; an unreachable service fails both operations. `ConsulMemberPositionsIntegrationTest` becomes its binding
   and keeps the Consul wire cases.
2. **P7b, session-bound records.** `ConsulMemberPositions` takes the Consul node name, as the lease adapter does, and
   validates it and the TTL range. `publish` renews the adapter's session, or creates one when it has none or the
   renewal finds it gone, and then writes the record with `?acquire=<session>`. The session has the lease TTL,
   `Behavior=delete`, `LockDelay=0s`, and no checks; it is read back and any differing setting fails. A write that
   Consul does not acquire (`false`) fails. `read` fails on a record that no session holds, because it is not a
   session-bound publication. Contract additions: a record whose member stops publishing disappears after the session
   TTL; a record without a session fails the read. ACL: each node's token writes its own member key, reads the prefix
   without a `list` rule (§3.3), and writes sessions on its own Consul node.
3. **P7c, the monitor builder.** `PgFailoverMonitor.builder()` sets the configuration, elector, store, process control,
   gate, watchdog, durability control, promotion control, and member positions; `build()` refuses a missing one by name.
   The public constructor is deleted. `PgFailoverMonitor.publishPosition()` and `advanceMemberSlots()` replace
   `PgMemberSlots`, with its contract, and run serialized with the other local effects. `PgMemberSlots` is deleted.
   Their failures are logged by the monitor's single boundary log line.
4. **P7d, candidate ranking.** `PgDurabilityControl.candidatePosition(nodeId)` observes the candidate position of the
   table above; it is refused on a writer. `PgFailoverMonitor.takeoverCandidacy(observed)` is refused when the observed
   record's confirmed policy does not name this node (`PgLeaseProtocolException`), and under quarantine. It reads this
   node's candidate position and the published positions, and returns `PgCandidacy(preferred, reason)`: not preferred
   when another member of the confirmed policy published a position on a higher timeline, or on the same timeline
   strictly beyond this node's position. A missing or expired record is ignored, as Patroni ignores a member it cannot
   reach. Equal positions leave both preferred; the lease acquisition decides. A published position is at most the
   member's present position, so a node defers only to a member that is ahead. Manual takeover names its target and is
   not ranked. The automatic initiation that acts on the result is phase 8a.

#### P7 dependency failure modes, each with a failing test before its code

| Dependency | Modes | Test class |
|---|---|---|
| Positions port, every adapter | failed request, rejected write, malformed or filtered read, non-member record, unanswered request, unreachable service, another node's position | `PgMemberPositionsContract` through its Consul binding |
| Consul member session | creation rejected; setting differs on read-back; renewal finds the session gone; record not acquired; record expires with the session | `ConsulMemberPositionsIntegrationTest` |
| Monitor builder | each dependency missing | `PgFailoverMonitorBuilderTest` (core) |
| Monitor publication and advance | quarantine; unreachable coordinator; the advance end to end | `PgSlotIntegrationTest`, `PgTakeoverIntegrationTest` |
| Candidate position | writer; streaming standby; standby without a receiver; command failure; malformed output; no result; no budget | `PgSqlMemberSlotIntegrationTest` |
| Candidacy | not named by the confirmed policy; quarantine; another candidate ahead; equal; another candidate's record expired; unreachable coordinator | `PgCandidacyIntegrationTest` |

**Order of work.** P7a, P7b, P7c, P7d, each with tests and stubs, red, implementation, green, probes with SHA-256
records, the touched classes, core, and the guards last.

**As built, where the code differs from the design text above.**

- P7a and P7b were written as one TDD cycle: one red run covered the exception type, the session, and the argument
  checks.
- P7c: the builder test was written after the builder code; probe 43 (the builder's null check) is its red.
- P7d: `PgSqlDurabilityControl.CANDIDATE` reads `pg_last_wal_receive_lsn()`, `pg_last_wal_replay_lsn()`,
  `received_tli` of the receiver, and `pg_control_checkpoint().timeline_id`. The adapter takes the larger of the two
  positions, on the receiver's timeline, else on the checkpoint's; a standby with neither position fails. A
  paused-replay test shows that the received position is used when replay is behind.
- PostgreSQL 15.13 facts used by P7d: after the writer is stopped, a standby has no `pg_stat_wal_receiver` row with a
  timeline, `pg_last_wal_receive_lsn()` keeps its last value, and `pg_control_checkpoint().timeline_id` was 1; with
  `pg_wal_replay_pause()` the receiver keeps receiving while replay stays behind.
- One positions adapter per member: a second adapter for the same member cannot acquire the key while the first
  adapter's session lives. Test added 2026-11-14:
  `aPublicationThatConsulDoesNotAcquireFailsAndLeavesTheHeldRecord`.

**Status, 2026-11-14.** Implemented (commits `c038a4ba`, `4b1e2a87`, `025ec74d`), green, and the module green on
Jenkins. The design documents describe P7 (system design §1.1 and §5.10; coordinator design §1.1 and §3.3). The red,
green, and probe logs of 2026-11-13 that the handover names (`peegeeq-pg-failover-p7*-20261113.log`, probes 34 to 50)
are not in `logs/` on the owner's workstation; their results are as reported in the handover and were not re-checked.

| Step | Evidence |
|---|---|
| Module on `4b1e2a87` | Jenkins build #36: `all` from `peegeeq-pg-failover`, SUCCESS in 101 minutes; `peegeeq-pg-failover` 30 classes, 704 tests, 0 failures, errors, or skips; downstream modules and both UI suites green |
| Acquire refusal | `aPublicationThatConsulDoesNotAcquireFailsAndLeavesTheHeldRecord` on `025ec74d`: Jenkins build #37, `ConsulMemberPositionsIntegrationTest` 19 of 19. Probe on the Jenkins host (`logs/mutation-acquire-20261114.log`): with the `"true"` check removed the test fails ("A write that Consul did not acquire was reported as stored"); SHA-256 of `ConsulMemberPositions.java` `20d3f01b...7a180d68` before and after, equal to the handover's record |
| Build #37 defects | Build #37 failed two tests on `025ec74d`. (1) `aWriterWithoutTheFollowersSlotIsNotReportedAsFollowedAndTheWaitEndsWithinItsBudget`: `follow` confirmed a receiver whose slot did not exist on the writer. Fixed below. (2) `aStoppedServerFailsTheInstall` timed out at the 30-second Vert.x default. Fixed below |

**Follow confirmation, 2026-11-14 (defect from build #37).** PostgreSQL 15 sets `status = streaming` in
`pg_stat_wal_receiver` when the receiver starts, and records `sender_host` and `sender_port` once it has connected;
only then does it send `START_REPLICATION` with the slot, which fails when the slot is missing (walreceiver.c,
REL_15_STABLE). `follow` accepted the first row showing the writer's address and the slot, so a poll inside that window
confirmed a follow that never started, and `PgFailoverMonitor.followWriter` recorded a `FOLLOW_WRITER` receipt for it.
The confirmation now also requires the receiver's `wait_event` in `pg_stat_activity` to be `WalReceiverMain`, which the
receiver waits on only inside its streaming loop. Probe on PostgreSQL 15.13 (Jenkins host, two containers, 3000
samples each): with no slot, 2998 samples had no receiver row and 2 showed `streaming`, the address, and the slot with no
wait event; after the slot was created, 3000 of 3000 showed `WalReceiverMain`.

| Step | Evidence under `logs/` on the Jenkins host (`/var/lib/jenkins/peegeeq-work`) |
|---|---|
| Test | `aReceiverObservedBeforeItsReplicationStartsIsNotReportedAsFollowed`, with `ReceiverWindowCommandRunner`: the receiver query runs on the real follower, repeated inside one `DO` block until the row shows the writer's address and the slot, and that real result is returned. A short `wal_retrieve_retry_interval` makes the window recur. The test also asserts that at least one observation from inside the window was returned |
| Red | `red2-` and `red3-receiver-window-20261114.log`, and `red4-` with the committed test: on the pre-fix code, `follow` reported the receiver as following. The first attempt (`red-`) failed for a test defect: `CREATE FUNCTION` is refused on a read-only standby |
| Green | `green2-promotion-class-20261114.log`: `PgSqlPromotionControlIntegrationTest` 20 of 20 |
| Module | `green-module-20261114.log`: 19 classes, 296 tests, 1 failure, the new test's earlier assertion that the last observation lies inside the window, which the committed test replaced with the count above |
| Core | `core-pg-failover-20261114.log`: 11 classes, 410 tests, 0 failures |
| Guards | `guards2-20261114.log`, after the last test edit: 77 tests, 0 failures |

**Vert.x test-context timeouts, 2026-11-14 (defect from build #37).** The integration classes declare a JUnit class
timeout of 120 to 300 seconds, but the Vert.x extension reads only `io.vertx.junit5.Timeout` (method, then class;
checked in the 5.1.10 bytecode; the annotation is not inherited), so every method without its own annotation had 30
seconds. `aStoppedServerFailsTheInstall` runs a forced stop with a 20-second `pg_ctl` wait and an install with a
20-second budget, and timed out at 30.58 seconds. Each of the 18 classes that declare a JUnit class timeout, and the
two Consul subclasses of the abstract contracts, now declares `io.vertx.junit5.Timeout` equal to its JUnit limit (20
classes). In the module run above every class passed with these limits, apart from the one failure named there.

### 9.29 Phase 7b.3: Takeover after Real Lease Expiry, 2026-11-14 (design)

The next item of §9.28's order of work. §9.21 to §9.23 recorded the gap: every takeover test uses a released lease,
and the former writer is stopped with `pg_ctl` before the successor acquires.

**Purpose.** When the writer stops renewing its lease, because its host is lost or its supervisor is cut off from the
coordinator, a standby covered by the last confirmed policy takes over once the lease has expired, with no reply from
the former writer. The former writer is excluded by its own supervisor before the earliest moment the successor can
acquire (system design §5.5, §5.6; coordinator design §3.2, §4.1). The successor then promotes, the surviving standby
follows, and the policy is cut over (§9.21 to §9.23). Success: the successor serves with the cut-over policy, and the
former writer was stopped before the successor held the lease.

**Data model.** No stored field is added.

| Datum | Source of truth or derivable | Contract |
|---|---|---|
| Expiry of the former lease | Derived: an authoritative read returns the record with no lease holder | TTL-only (system design §5.5); the successor never shortens it. Consul can invalidate a session later than its TTL |
| Takeover intent | Derived from the observed unowned record: `writerNodeId` this node, `phase` `WITHDRAWN`, a new `operationId`, `previousWriterNodeId` the observed writer, `durabilityPolicy` and `pendingDurabilityPolicy` carried unchanged | Written by `acquireAfterRelease` at the observed revision; the elector already checks history preservation and the generation advance |
| Eligibility | Derived: the observed confirmed policy names this node | A pending policy is never used (§5.8) |
| Candidate preference | Derived: `takeoverCandidacy` (§9.28, P7d) | Consulted in `AUTOMATIC` mode only; a manual takeover names its target. Never stored |
| Former writer's exclusion | Local to the former writer: its supervisor's demotion within the ownership budget, recorded in its own `stop-writer` receipt; a qualified watchdog when the supervisor cannot act | The successor does not observe it. `PgNodeConfig` keeps one loop-and-retry cycle and the stop budget inside the lease TTL, so a running former supervisor stops PostgreSQL before the lease can expire |
| `FENCING` and `PROMOTING` phases | Named by the system design (§1.1, §5.6); not written | OD-42 below |

**Contract of `PgTakeover.acquire(observed, mode)`** (new).

1. Refused before any coordinator write, with no receipt:
   - `observed` has a lease holder: the former lease is live or not yet expired (`PgLeaseProtocolException`);
   - `observed` names this node as writer: that is a re-join, not a takeover (`PgLeaseProtocolException`);
   - no confirmed policy, or it does not name this node (`PgLeaseProtocolException`);
   - quarantine (`PgLocalStateException`);
   - in `AUTOMATIC` mode, `takeoverCandidacy(observed)` is not preferred (`PgLeaseProtocolException` carrying the
     candidacy reason). Its own refusals propagate.
2. `PgPrimaryElector.acquireAfterRelease(observed, intent)` with the derived takeover intent. An owner that appeared
   since the read, or a changed revision, fails the acquisition and changes nothing (coordinator design §3.2).
3. The result is the acquired record. Every failure and refusal is logged once at ERROR by `PgTakeover`
   (`Takeover acquisition failed on node <id>`), through `andThen`.
4. The caller starts `PgRenewalLoop` directly after the acquisition and before promotion (its Javadoc), then calls
   `promoteStandby`; the surviving standby's own supervisor calls `followWriter`; then `cutover`.

**Decisions of the implementer, 2026-11-14, not reviewed by the owner and reversible.**

- OD-42: `FENCING` and `PROMOTING` are not written. Reason: no component reads them; old-writer exclusion is
  established by expiry and the former writer's own supervisor, which the successor cannot observe; promotion is
  derived from the `promote` receipt and the live role. Writing them would store derivable state. The intent schema
  keeps accepting them. The system design and coordinator design state-machine tables are unchanged by this decision
  and differ from the code here.
- OD-43: `acquire` belongs to `PgTakeover`, not to the monitor. Reason: it is a takeover step that writes the
  coordinator (OD-22); the monitor owns local effects. `PgTakeover` gains no constructor argument: the monitor
  provides `takeoverCandidacy`, the store provides quarantine.
- OD-44: the acquisition does not check the local role. Reason: holding the lease has no PostgreSQL effect;
  `promoteStandby` refuses a writable node without its own `promote` receipt.

**Dependency failure modes, each with a failing test before its code** (`PgTakeoverAfterExpiryIntegrationTest`, new:
its lease TTL is 10 seconds, the Consul minimum, so expiry happens inside a test; `PgTakeoverIntegrationTest` keeps
80 seconds).

| Dependency | Mode | Test |
|---|---|---|
| Former writer's host | lost: PostgreSQL stopped hard, no renewal, no release | `aTakeoverAfterTheFormerWritersHostIsLostServesTheNewWriter` |
| Former writer's coordinator link | renewals fail while PostgreSQL runs | `aFormerWriterCutOffFromTheCoordinatorIsStoppedBeforeTheSuccessorAcquires` |
| Coordinator record | still owned when observed; the refusal must leave the elector able to acquire after expiry (an elector refuses every acquisition after its first attempt) | `anAcquisitionWhileTheFormerLeaseIsLiveIsRefusedAndALaterOneAfterExpirySucceeds` |
| Confirmed policy | does not name this node | `aNodeTheConfirmedPolicyDoesNotNameCannotAcquire` |
| Observed record | names this node as writer, and its carried confirmed policy names it too: the new writer's lease lapsed before the cutover | `aNodeTheRecordNamesAsWriterCannotTakeItOverAgain` |
| Node-local store | quarantine | `aQuarantinedNodeCannotAcquire` |
| Candidacy (automatic) | another covered candidate published a position ahead | `inAutomaticModeACandidateBehindAnotherDoesNotAcquireAndTheOneAheadDoes` |

**Order of work.** Tests and a stub that fails with "not implemented"; red; implementation; green; mutation probes
with SHA-256 records; the touched classes; core; guards last.

**Status, 2026-11-14.** Implemented (stub and tests `bafe52b2`, implementation `6ac4b5b3`; the rebuilt writer-check
test is on the working tree), green, mutation-probed, and the touched classes, core, and guards green. Runs on the Jenkins host, logs in `/var/lib/jenkins/peegeeq-work/logs`.

| Step | Evidence |
|---|---|
| Red, against the stub | `expiry-red-20261114.log`: 7 run, 5 failures and 2 errors, every one `UnsupportedOperationException: not implemented`. In the cut-off test the former writer's demotion ran (existing code); the successor failed on the stub |
| Green | `expiry-green-20261114.log` 7 of 7; after the writer-check test was rebuilt (below), `expiry-green2-20261114.log` 7 of 7 |
| Probes | `expiry-mutations-20261114.log`, `expiry-chain3-20261114.log`; SHA-256 of `PgTakeover.java` `1fec6936...69d547` before and after both sets. 1 holder check: the elector refused the owned record and used up its attempt, so the acquisition after expiry failed ("Acquisition after release is unavailable"). 2 writer check: not detected by the first version of its test, because the policy check refused first (a former writer is never named by its own confirmed policy); the test was rebuilt on a record whose carried policy names its writer, and probe 2b failed it ("A node took over a record that names it as writer"). 3 policy check, 4 quarantine, 5 automatic candidacy: each failed its test with its own message |
| Touched classes | `expiry-regress-takeover-20261114.log`: `PgTakeoverIntegrationTest` 11 of 11 |
| Core | `expiry-core-20261114.log`: 410, 0 failures |
| Guards | `expiry-guards-20261114.log`, after the last test edit: 77, 0 failures |

**As built.** The cut-off test observes the order directly: the former writer's renewal loop ends only after its
demotion has stopped PostgreSQL, and the successor polls only for expiry, in parallel. The test asserts that the end
of the former loop precedes the successor's acquisition, the former writer's `stop-writer` receipt is `COMPLETED`, and
its PostgreSQL is stopped. The cut-off is a renewal hook on the former writer's coordinator wrapper that fails each
renewal without sending it, so its Consul session expires as under a partition.

**Not checked.** Expiry with an armed watchdog and a dead or paused former supervisor (system design §5.5 makes no
exclusion claim without one; no device on the test host). A real network partition rather than a renewal hook. Consul
invalidating a session later than its TTL under load (the tests wait up to 60 seconds). The automatic initiation that
reads the record, waits, and calls `acquire` (phase 8a). Downstream modules on this working tree.

### 9.30 Phase 7b.3: Planned Stop with Release (OD-15), 2026-11-14 (design)

The next item of §9.28's order of work. OD-15 (§9.20, owner decision 2026-10-26): a planned supervisor stop stops local
PostgreSQL, confirms the stop, then releases the lease through the guarded release; the same order as a planned
switchover. Until now closing the elector never releases, so a planned stop leaves the lease until it expires.

**Purpose.** A writer that is taken down on purpose hands the lease back as soon as its PostgreSQL is confirmed stopped,
so a successor acquires at once instead of after expiry (system design §5.6: "In a planned switchover, the old node stops
locally before releasing its lease"). Success: the record is unowned history with the writer's intent unchanged, the
writer's PostgreSQL was stopped before the release, and a successor's `PgTakeover.acquire` succeeds without waiting for
expiry.

**Data model.** No stored field is added.

| Datum | Source of truth or derivable | Contract |
|---|---|---|
| Released record | Coordinator (existing): the record with no lease holder, generation and intent unchanged | Written by `PgPrimaryElector.release`, which retires the elector whatever the outcome |
| Confirmed local stop | Derived: `PgProcessControl.stop` observed the process stopped and the restart inhibitor in place | Recorded in the existing `stop-writer` receipt with the reason |
| Quarantine after an unconfirmed stop | Node-local store (existing) | As in a forced demotion |

**Contract of `PgFailoverMonitor.stopAndRelease(held, reason)`** (new). The caller stops its `PgRenewalLoop` first.

1. Refused before any effect, with no receipt: no fresh ownership of exactly `held` (`PgLeaseProtocolException`).
2. Logged at INFO, because the caller requested it. Then the local stop of a demotion, every step run even when an
   earlier one fails: close the grant; begin the `stop-writer` receipt with the reason; stop PostgreSQL within the stop
   budget and record `COMPLETED`, disarming a watchdog this monitor armed; close the gate.
3. Only when every step of 2 succeeded: `PgPrimaryElector.release(held)`. The result is the released record.
4. An unconfirmed stop quarantines the node, records `UNKNOWN`, and releases nothing (coordinator design §4.2: "Do not
   voluntarily release until exclusion is confirmed"). Any failed step fails the call without a release and retires
   the elector; the lease then expires.
5. A failed release leaves PostgreSQL stopped and fails the call; the elector is retired by the release, and the lease
   expires.

**Decisions of the implementer, 2026-11-14, not reviewed by the owner and reversible.**

- OD-45: the operation belongs to the monitor. Reason: the local stop and the receipt are the monitor's; the release
  must follow the confirmed stop inside one serialized operation, so no other local effect runs between them.
- OD-46: the release requires every local step to succeed, not only the process stop. Reason: a release is never
  needed for safety, because the lease expires; withholding it on any doubt costs only the expiry wait.
- OD-47: the elector's own `close` still never releases (coordinator design §3.1); `stopAndRelease` is the one path
  that releases on a planned stop.

**Dependency failure modes, each with a failing test before its code** (`PgPlannedStopIntegrationTest`, new, 10-second
lease TTL).

| Dependency | Mode | Test |
|---|---|---|
| All steps | none fails; the release follows the confirmed stop, and the successor acquires before the lease could expire | `aPlannedStopReleasesAfterTheConfirmedStopAndTheSuccessorAcquiresAtOnce` |
| Process control | the stop is not confirmed | `anUnconfirmedStopQuarantinesTheNodeAndKeepsTheLease` |
| Elector | a record this node does not hold | `aRecordThisNodeDoesNotHoldIsRefusedBeforeAnyEffect` |
| Coordinator release | fails | `aFailedReleaseLeavesPostgreSQLStoppedAndTheLeaseToExpire` |

**Order of work.** Tests and a stub that fails with "not implemented"; red; implementation; green; mutation probes
with SHA-256 records; the touched classes; core; guards last.
