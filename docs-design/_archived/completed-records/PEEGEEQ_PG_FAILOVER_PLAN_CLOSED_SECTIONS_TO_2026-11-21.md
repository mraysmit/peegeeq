# PeeGeeQ Failover Implementation Plan: Closed Sections up to 2026-11-21 (archived)

**Status:** HISTORICAL. These sections were moved unchanged on 2026-11-21 out of
`docs-design/failover and resilience/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_IMPLEMENTATION_PLAN.md`, so that the
plan holds only its contracts, its status table, and its open design. Section numbers are unchanged, so a
reference such as "plan §9.32" in code or another document is found here. Relative links inside these sections
were written for the plan's folder. The contracts in §9.21 to §9.34 describe code that exists; the code and its
tests are the current reference for that behaviour.

**Corrected since the move.** 2026-11-24: dated corrections to §9.26 and §9.32 checks 4 and 10 (register D27, D28).
2026-11-28: commit hashes. On 2026-11-21 at 17:55 the history was rewritten to remove co-author trailers; each commit
kept its tree and dates and got a new hash, and the old hashes resolve in no branch. Every such hash below is replaced
by the `master` commit with the same tree; for example `0ffaafb6` is now `18c27376`. Jenkins consoles and logs from
before the rewrite show the old hashes.

---

## 2. Gap between the design and the implementation

**Historical baseline.** The table below applies to the earlier design and recorded source
assessment. It is retained for traceability. It is not a current implementation audit and does
not prove coverage of the revised contracts. Names and mechanisms superseded by §5 must not
be copied into new work.

Assessed 2026-10-07 at commit `41fe8f56` from a read of the source (§9.1) and from the runs in
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

Line numbers are at commit `41fe8f56`.

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
| TST-01 | `HaProxyConnectionFailoverTest` waited a fixed 4,000 ms and 8,000 ms with `vertx.setTimer`. | L516–L518, L539–L541, L607–L609 at `41fe8f56` | Repaired in the working tree (§4.2) |
| TST-02 | The same test stopped and started containers on the event loop. | L512, L531, L603 at `41fe8f56`; confirmed by the baseline log (§4.1) | Repaired in the working tree |
| TST-03 | The same test closed the injected `Vertx` in `@AfterEach`. | L297–L298 at `41fe8f56` | Repaired in the working tree |
| TST-04 | Phase 5 was named "failback" and asserted only that `SELECT 1` returned 1. | L490–L555 at `41fe8f56` | Repaired in the working tree. The repaired test is red (§4.2) |
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

Run 2026-10-07 on the development machine at commit `41fe8f56`. No Java file differed from
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
2026-10-11 in Jenkins build 30 at `ef72132f`: `peegeeq-db` ran 1,124 tests with that 1 failure.

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


### 9.1 What was read

Read on 2026-10-07 at commit `41fe8f56`. "Full" means every line.

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

- Until 2026-10-07 the design document was a description of the code at `6a602b2a`, with status
  "CURRENT OPERATING REFERENCE". That version is in git at `41fe8f56`.
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

**2026-10-08.** Commit `eb42d42e` implemented §8.2 items 1 to 4 and recorded their runs in §8.1.
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
| The red state of the coverage batch was not saved | Rebuilt in an isolated git worktree: the tests of `26e6a126` against the production files of `96d82ab3`. Compilation fails with `cannot find symbol: class PgStandbyNotCoveredException`. The worktree was removed. | `peegeeq-pg-failover-coverage-batch-red-20261025.log` |
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

- The Jenkins build. The changes of this section are pushed (`6419b66b`). On 2026-10-25 three builds
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

**Status, 2026-11-03.** Implemented (commit `cd6ee42c`), green, mutation-probed, regression, core, and
guards green. Evidence below; every count is from a run made on 2026-11-03 against `cd6ee42c` with a clean
working tree.

| Step | Evidence under `logs/` |
|---|---|
| Rebuild with tests and stub | `rebuild-peegeeq-pg-failover-cutover-20261103.log`: `BUILD SUCCESS` (run by the owner) |
| Red, behaviour, against the stub | `peegeeq-pg-failover-cutover-red-20261103.log`: `Tests run: 10, Failures: 7, Errors: 3`. Nine methods fail on `UnsupportedOperationException` or a wrong exception type. The tenth failure is the class-level ERROR log contract: the declared `PgTakeover` lines were never logged, and each failed test's unawaited teardown leaked an "Interrupted ... pg_ctl stop" ERROR line (register finding 13). The same teardown prints `RejectedExecutionException: event executor terminated` from the worker thread whose result arrives after Vert.x closed |
| SHA-256 before the implementation | `peegeeq-pg-failover-cutover-sha-before-implementation-20261103.log` (`PgBootstrap`, the stub `PgTakeover`, `PgFailoverMonitor`) |
| Implementation | `PgTakeover.cutover`; new package-private `PgPolicyCutover.serve`; `PgBootstrap.serve` delegates to it and keeps its log line |
| Rebuild of `cd6ee42c` | `rebuild-peegeeq-pg-failover-cutover-impl-20261103.log`: `BUILD SUCCESS`, 45 main sources |
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

**Status, 2026-11-12.** Implemented on `2108534b` plus the uncommitted P4 change. Green, mutation-probed, and the
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
Correction, 2026-11-24: "one with `-S <slot>` alone fails" is wrong, and no probe of this increment checked it.
§9.32 check 10 (2026-11-15) and a local probe on 15.13 on 2026-11-24 (`logs/review-lost-slot-probe-20261124.log`)
each took a base backup through a lost slot with exit 0; in the second, the slot afterwards held a position again. The
other reason above, that the writer keeps the WAL the backup needs from before the backup starts, was not probed.

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

**Status, 2026-11-14.** Implemented (stub and tests `51e0af37`, implementation `1ccd8f91`, rebuilt writer-check test
`45232321`), green, mutation-probed, and the touched classes, core, and guards green. Runs on the Jenkins host, logs in `/var/lib/jenkins/peegeeq-work/logs`.

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

**Status, 2026-11-14.** Implemented (`6afee3e5`), green, mutation-probed, and the touched classes, core, and guards
green. Runs on the Jenkins host, logs in `/var/lib/jenkins/peegeeq-work/logs`.

| Step | Evidence |
|---|---|
| Red, against the stub | `planned-stop-red-20261114.log`: 4 run, 3 failures and 1 error, each `UnsupportedOperationException: not implemented` |
| Green | `planned-stop-green-20261114.log`: 4 of 4 |
| Probes | `planned-stop-chain5-20261114.log`; SHA-256 of `PgFailoverMonitor.java` `c9cf160c...97d18f38` before and after. 1 release after an unconfirmed stop: "An unconfirmed stop was reported as a completed planned stop". 2 release before the stop: "The lease was released while PostgreSQL still ran". 3 ownership check: "A refused planned stop began a stop receipt". 4 retire after a failed stop: "An unconfirmed stop must retire ownership, so it cannot renew" |
| Touched classes | The demotion was split into `demoteNow` (retire, then the local stop) and `stopLocally`, and `ObservedLeaseCoordinator` gained a release hook: monitor 23, renewal loop 13, bootstrap 7, promotion 17, takeover 11, takeover after expiry 7, all 0 failures |
| Core | `planned-stop-core-20261114.log`: 410, 0 failures |
| Guards | `planned-stop-guards-20261114.log`, after the last test edit: 77, 0 failures |

Coordinator design §3.1 names `stopAndRelease` as the one release path besides a planned switchover (OD-47).

**Not checked.** A planned stop with an armed watchdog (the disarm after the confirmed stop is the existing demotion
code). The supervisor process that calls `stopAndRelease` on a shutdown signal: there is no production entry point yet.
Downstream modules on `6afee3e5`.

### 9.31 Phase 7b.3: Member Tags (Owner Answer 11), 2026-11-14 (design)

**Owner rulings, 2026-11-14.** Asked where tags live and how the two tags without a feature are handled: "Deployment
config (Recommended)"; "Accept all four now". So every node's configuration lists the tags of every member, as it
lists the membership, and `noloadbalance` and `clonefrom` are validated and kept although no feature reads them yet.

**Purpose.** An operator keeps a member out of promotion (`nofailover`) or out of every required standby set
(`nosync`, which implies no promotion: §9.28 row 11), as Patroni's member tags do. Success: a tagged member is never a
takeover target, a `nosync` member is never written into a new policy, and an automatic candidate does not defer to a
tagged rival.

**Data model.**

| Datum | Source of truth or derivable | Contract |
|---|---|---|
| Member tags: `nofailover`, `nosync`, `noloadbalance`, `clonefrom` | Deployment configuration: `PgNodeConfig.memberTags`, a map from member identity to `PgMemberTags`, the same on every node | Keys are members. `clonefrom` names another member. A member without an entry has no tags. Never stored elsewhere |
| Takeover eligibility | Derived: named by the confirmed policy, and neither `nofailover` nor `nosync` | A policy naming a tagged member stays valid history; the tag withdraws eligibility |
| Next required standby set | Derived (§9.23): the confirmed set minus the new writer, minus `nosync` members | Empty: refused, admission stays closed |
| Effect of `noloadbalance`, `clonefrom` | None yet | `noloadbalance` waits for a read-only replica endpoint (§9.20 row 16); `clonefrom` for the automatic rebuild (answer 9) |

**Contracts.**

1. `PgNodeConfig` refuses tags for a non-member, and a `clonefrom` that is not another member. The existing
   constructor keeps its signature and has no tags; `withMemberTags(map)` returns a copy with tags; `tagsOf(id)`
   returns a member's tags or none.
2. `PgPrimaryElector` refuses a new policy, initial or by update, that names a `nosync` member
   (`PgLeaseProtocolException`). A policy carried unchanged from history may name one, as it may name the writer.
3. `PgTakeover.acquire` and `PgFailoverMonitor.promoteStandby` refuse, before any effect, on a node tagged
   `nofailover` or `nosync` (`PgLeaseProtocolException`).
4. `PgTakeover.cutover` derives the next set without `nosync` members.
5. `PgFailoverMonitor.takeoverCandidacy` ignores rivals tagged `nofailover` or `nosync`: they can never take over, so a
   position they published is no reason to defer.

**Decision of the implementer, 2026-11-14, reversible.** OD-48: the `nosync` rule is enforced in the elector, which
validates every new policy before it is written, not separately in each caller. Reason: one enforcement point covers
bootstrap, cutover, and any later policy change.

**Dependency failure modes, each with a failing test before its code.**

| Dependency | Mode | Test |
|---|---|---|
| Configuration | tags for a non-member; `clonefrom` itself or a non-member; all four tags kept; no tags by default | `PgNodeConfigTest` (core) |
| Elector | a new policy names a `nosync` member | `PgLeaseCoordinatorContract.aNewPolicyNamingANosyncMemberCannotCreateOwnership` |
| Takeover | this node is `nofailover`; this node is `nosync`; automatic candidacy with a `nofailover` rival ahead | `PgTakeoverAfterExpiryIntegrationTest` |
| Promotion | this node is `nofailover` | `PgTakeoverAfterExpiryIntegrationTest` |
| Cutover | the only remaining standby is `nosync` | `PgTakeoverIntegrationTest` |

**Order of work.** Tests and stubs; red; implementation; green; mutation probes with SHA-256 records; the touched
classes; core; guards last.

**Status, 2026-11-14.** Implemented (`bb9502c9`), green, mutation-probed, and the touched classes, core, and guards
green. Runs on the Jenkins host, logs in `/var/lib/jenkins/peegeeq-work/logs`.

| Step | Evidence |
|---|---|
| Red, against the stubs | `tags-chain6-20261114.log`. Configuration: 26 run, 4 failures (the eligibility rule and both validations; the stub copied the map, so the immutability test passed). Elector: "A policy requiring a nosync member created ownership". Takeover: 3 run, 2 failures and 1 error: a tagged node acquired, a tagged node was promoted, and the candidate deferred to the tagged rival. Cutover: "A refused or failed cutover changed the control record" |
| Green | `tags-chain7-20261114.log`: configuration 26, elector 1, takeover 3, cutover 1 |
| Probes | Same log; SHA-256 of the five changed files `4ca01500...16411a41` before and after. 1 eligibility rule, 2 membership validation, 3 the elector's `nosync` check, 4 the takeover check, 5 the cutover filter, 6 the promotion check, 7 the candidacy filter (the candidate then deferred: "pg-node-3 published timeline 1 at 0/5273568, ahead of this node's"): each failed its target test |
| Touched classes | Consul lease coordinator 62, takeover 12, takeover after expiry 10, candidacy 6, promotion 17, planned stop 4, all 0 failures |
| Core | 416, 0 failures |
| Guards | after the last test edit: 77, 0 failures |

**Not checked.** A tag change while the cluster runs: tags are deployment configuration, and a node reads them at
construction. How a deployment supplies the map: no production entry point builds a `PgNodeConfig` yet.
`noloadbalance` and `clonefrom` have no effect to test.

### 9.32 Phase 7b.3: Automatic Rewind and Rebuild (Owner Answer 9), 2026-11-15 (design)

The next item of §9.28's order of work. Owner answer 9 (§9.28, row 9): "Rewind and rebuild are automatic; when a rewind
fails, the data directory is removed and the member is rebuilt." It reverses P-6 (§5). It is consistent with S31 and
system design §5.9: quarantine is kept during the rewind or rebuild, a failed rewind is followed by a rebuild, and
admission waits for standby validation (invariant 6). Operator reconciliation stays required for missing history,
unexpected key deletion, two writable nodes, restored coordination state, missing policy history, and conflicting
receipts (system design §5.7, §5.8).

**Purpose.** A member whose data directory cannot stream from the current writer becomes a validated standby of that
writer without an operator. That member is either a former writer after a takeover, or a standby that is behind a
timeline switch or lost the WAL it needs. It is rewound when possible. Otherwise its data directory is removed and
rebuilt from a base backup. Success: the member streams from the writer through its own slot on the writer's
timeline, has replayed at least to the writer's flush position observed at validation, has the same system
identifier, keeps its own configuration files, and is no longer quarantined. It is still outside the required standby
set; the re-join policy cutover that restores two-peer coverage (system design §5.8, rows 3 and 4 of the lifecycle
table) is a later item.

**Sources read, 2026-11-15.** PostgreSQL 15 `pg_rewind` and `pg_basebackup` documentation pages (`app-pgrewind`,
`app-pgbasebackup`), the replication protocol commands `IDENTIFY_SYSTEM`, `READ_REPLICATION_SLOT`, and
`TIMELINE_HISTORY`. Code read in full: `PgFailoverMonitor`, `PgCtlProcessControl`, `PgSqlPromotionControl`,
`PgLocalStateStore`, `PgNodeConfig`, `PgMemberTags`, `PgWriterEndpoint`, `PgHbaAdmissionGate`, `PgControlRecord`,
`PgActionReceipt`, `PgQuarantine`, the port interfaces, `PgReplicationFixture`, `PgSupervisedContainer`,
`PgFollowIntegrationTest`, and `PgTakeoverAfterExpiryIntegrationTest`.

**Checks run, 2026-11-15,** on throwaway containers of `postgres:15.13-alpine3.20` on the Jenkins host, not on
`PgReplicationFixture`. Logs: `/var/lib/jenkins/peegeeq-work/logs/rewind-probe-20261115.log` and
`rewind-probe2-20261115.log`. The containers were removed afterwards.

1. `psql` with `replication=true` and the node's replication role runs `IDENTIFY_SYSTEM` (`systemid|timeline|xlogpos|`),
   `READ_REPLICATION_SLOT` (`physical|0/3027AA0|1`; a missing slot `||`; a lost slot `physical||`), and
   `TIMELINE_HISTORY`.
2. `pg_rewind --source-server` with the node's replication role works once the role has `EXECUTE` on
   `pg_ls_dir(text, boolean, boolean)`, `pg_stat_file(text, boolean)`, `pg_read_binary_file(text)`, and
   `pg_read_binary_file(text, bigint, bigint, boolean)`, and the source's host-based rules admit it to database
   `postgres`. Without the grants it fails: `permission denied for function pg_read_binary_file`.
3. A former writer that was stopped `-m immediate` has state `in production`. `pg_rewind` then runs single-user crash
   recovery, which fails while `standby.signal` exists: `standby mode is not supported by single-user servers`.
   Starting the target in recovery with the inhibitor in place and `primary_conninfo=''`, then a fast stop, leaves
   state `shut down in recovery`, and `pg_rewind` then succeeds: `servers diverged at WAL location 0/4027AD8 on
   timeline 1`, `rewinding from last common checkpoint`, `Done!`.
4. **A rewind removes `standby.signal`**, writes `backup_label`, and copies the source's `postgresql.auto.conf`, which
   holds the source's own `primary_conninfo` (its user) and `primary_slot_name`, and its `pg_hba.conf` and
   `pg_ident.conf` (a node-local marker in `pg_ident.conf` was lost). Started in that state, the node would end
   recovery and become writable: a second writer. Correction, 2026-11-24: the rewind also copies the source's
   `postgresql.conf`. This item did not name it. A local probe on 15.13 with different ports on source and target
   showed the target with the source's port after the rewind (`logs/review-rewind-facts-probe2-20261124.log`).
5. A standby that stopped before the fork: `servers diverged at WAL location 0/4027AD8 on timeline 1`, `no rewind
   required`, exit 0; `standby.signal` and `postgresql.auto.conf` unchanged. A source on the same timeline (a standby
   was named as source): `source and target cluster are on the same timeline`, `no rewind required`. So `pg_rewind`
   accepts a source in recovery.
6. After the rewind, with its own `postgresql.auto.conf` restored and the inhibitor recreated, the former writer
   started in recovery, followed the new writer (`streaming|2|peegeeq_pg_node_1|WalReceiverMain`), and replayed to the
   writer's flush position (`0/402CA70`); its 50 000 diverged rows were gone (1007 rows, as on the writer).
7. A target whose last run had `wal_log_hints=off` and no checksums: `target server needs to use either data
   checksums or "wal_log_hints = on"`, exit 1, before any change.
8. An unreachable source: `could not translate host name`, exit 1.
9. A base backup copies `postgresql.conf`, `pg_hba.conf`, `pg_ident.conf`, and the source's `postgresql.auto.conf`
   (with the source's user and slot). Without `-R` it writes no `standby.signal`, so the node would start writable.
   Into a non-empty directory it fails: `directory ... exists but is not empty`.
10. A base backup with `-S` through a **lost** slot succeeded. The slot stays lost and keeps no WAL afterwards.
    Correction, 2026-11-24: the second sentence is wrong. A local probe on 15.13 lost the slot under
    `max_slot_wal_keep_size = 1MB`, removed the limit, and took the base backup through it. Before the backup the slot
    read `lost` with no `restart_lsn`. Directly after it, and again after more WAL and a checkpoint, it read
    `reserved|0/E000000`, and `READ_REPLICATION_SLOT` returned `physical|0/E000000|1`
    (`logs/review-lost-slot-probe-20261124.log`). The data model row "Slot usable" below cites this check; the
    claim that a lost slot keeps no WAL after a base backup does not hold.
11. An absent data directory: `pg_ctl status` exit 4, `pg_controldata` exit 1. A partial one (no `global/pg_control`):
    `pg_ctl status` exit 3, `pg_controldata` exit 1 with the same message.

**Data model.** No stored field is added to any record.

| Datum | Source of truth or derivable | Contract |
|---|---|---|
| Rewind needed | Derived by `pg_rewind` from the target's control file and the source's timeline history | Never stored. "no rewind required" is a successful rewind |
| Target data directory: system identifier, cluster state, timeline | Derived live with `pg_ctl status` and `pg_controldata`, no SQL | Absent (status exit 4) is a state. Present but unreadable fails the observation |
| Source observation: system identifier, timeline, flush position (`IDENTIFY_SYSTEM`); this node's slot on it (`READ_REPLICATION_SLOT`); whether it is in recovery (`pg_is_in_recovery()` on database `postgres`) | Derived live from the source, through this node's replication role and password file | Never stored. Observed again right before the data directory is removed |
| Slot usable | Derived: the slot is physical and has a `restart_lsn` | A missing or lost slot is not usable (check 10) |
| Re-join quarantine | Node-local store (existing quarantine record), reason `PgFailoverMonitor.REJOIN_QUARANTINE` | Written before the first effect, cleared only after standby validation. A quarantine with any other reason refuses the re-join |
| Operator quarantine | Node-local store (existing), a reason starting `Operator reconciliation required:` | Written when the re-join finds another system or an unreadable data directory outside a re-join. Only an operator clears it |
| Receipts `rewind`, `rebuild` | Node-local store (existing), keyed by the observed record's generation and operation | Parameters: the source host and port, so a rerun cannot use another source |
| The node's own configuration files | The node's data directory (deployment), copied to `<data directory>.rejoin-saved` before the first destructive step | Every regular top-level `*.conf` file. Saved only when no saved copy exists, restored after the rewind or the base backup, removed after a successful one |
| Start inhibitor `standby.signal` | The data directory (existing) | Recreated directly after every rewind, whatever its outcome, and after every base backup (checks 4 and 9) |
| Rewind and rebuild binaries | Derived: `pg_rewind`, `pg_basebackup`, `pg_controldata` in the directory of the configured `pg_ctl` | Never configured separately |

**Contracts.**

1. `PgProcessControl.inspect()` (adapter `PgCtlProcessControl`): empty when the data directory does not exist;
   otherwise the system identifier, the cluster state, and the latest checkpoint's timeline from `pg_controldata`.
   An unreadable or malformed control file fails (`PgProcessControlException`).
2. `PgProcessControl.rewind(source, nodeId, budget)`: refused while PostgreSQL runs. When the cluster state is
   neither `shut down` nor `shut down in recovery`, it first starts PostgreSQL in recovery with the inhibitor in place
   and `primary_conninfo=''`, and stops it fast (check 3). Saves the configuration files, runs `pg_rewind -D <dir>
   --source-server '<connection info> dbname=postgres'`, then recreates the inhibitor and restores the configuration
   files whatever the outcome. The saved copy is removed only after a successful rewind. Bounded by `budget`.
3. `PgProcessControl.rebuild(source, nodeId, budget)`: refused while PostgreSQL runs. Saves the configuration files
   when the directory exists and no saved copy exists, removes the data directory, takes `pg_basebackup -D <dir> -Fp
   -Xs -S <own slot>` (no `-C`: the source's slot must exist), recreates the inhibitor, restores the saved
   configuration files, and removes the saved copy. Bounded by `budget`. The connection strings hold no password.
4. `PgPromotionControl.observeSource(nodeId, source)` (adapter `PgSqlPromotionControl`): the source observation of
   the table. A failed command, no result, and malformed output fail (`PgPromotionException`).
5. `PgPromotionControl.confirmCaughtUp(nodeId, writer)`: observes the writer's `IDENTIFY_SYSTEM` once, then waits
   within the budget until the local server is in recovery, has the writer's system identifier, and its WAL receiver
   streams on the writer's timeline (`WalReceiverMain`) with replay at or after the observed flush position.
6. `PgFailoverMonitor.rejoin(observed, writer, budget)`. Serialized. Refused before any effect and with no receipt:
   `observed` names this node as writer, or has no lease holder, because the source must be the current lease holder
   (`PgLeaseProtocolException`); a quarantine with a reason other than the re-join reason (`PgLocalStateException`).
   Then:
   1. Quarantine with the re-join reason. Close the grant.
   2. Inspect the data directory. Unreadable: rebuild when the node was already under the re-join quarantine (an
      interrupted rebuild); otherwise write the operator quarantine and fail.
   3. Present: close the gate and stop PostgreSQL within the stop budget (inhibitor first).
   4. Observe the source. Refused (`PgPromotionException`), with the re-join quarantine kept and nothing removed,
      when the source is in recovery or this node's slot on it is not usable. A different system identifier writes the
      operator quarantine and fails.
   5. Present: the rewind under the `rewind` receipt, then the standby start: close the gate, start in recovery,
      follow under the `follow-writer` receipt, and confirm the catch-up. Any failure of these steps is logged at
      ERROR and leads to step 6.
   6. Absent, or after step 5 failed: stop PostgreSQL if it runs, observe the source again with the rules of step 4,
      rebuild under the `rebuild` receipt, then the standby start of step 5. A failure fails the call; the re-join
      quarantine stays.
   7. Clear the quarantine. Admission stays closed: this node holds no lease and no grant.

**Danger, and the safety net.** The rebuild removes a data directory. This cannot be undone. The blast radius is one
member's copy of the cluster. Triggers: a rewind that fails, a rewound standby that does not follow or catch up
within its budget, and an absent or interrupted data directory. Before the removal the re-join requires a lease holder
that the record names, a source that is writable and of the same system, and a usable slot on it, observed right
before the removal. Detection: the `rebuild` receipt and an ERROR line naming the failed rewind. What is lost: the
former writer's WAL after the fork, which a rewind discards too. Under the required synchronous policy those
transactions were never acknowledged (R-17, S3). A slow catch-up that exceeds the budget causes a rebuild that a longer
wait would have avoided; that costs time, not data. Severity: correct operation keeps every acknowledged commit; a
misconfigured source endpoint is the main risk, and the system identifier and writable-source checks refuse it.

**Decisions of the implementer, 2026-11-15, under the owner's direction of 2026-11-13 to proceed without questions.
Reversible.**

- OD-49: the rewind, the rebuild, and the inspection are `PgProcessControl` operations. Reason: they run local
  binaries against the stopped data directory and need no local SQL (OD-16 kept promotion apart because it does). The
  source observation and the catch-up confirmation need SQL and replication connections, so they join `follow` in
  `PgPromotionControl`.
- OD-50: the rewind connection uses the node's replication role (OD-11) with the four function grants of check 2,
  and the deployment admits it to database `postgres` on every node. No new credential. The fixture provisions both.
- OD-51: the re-join recognises its own quarantine by its exact reason, and an operator quarantine by its reason
  prefix. Reason: the quarantine record has no kind field, and adding one changes stored data.
- OD-52: the rewind source and the rebuild source are the writer. `clonefrom` (§9.31) needs another member's
  address, and the supervisor holds no member address list (OD-20); it stays unused.
- OD-53: the rewind or rebuild budget is an argument of `rejoin`, as the stop budget is an argument of `stop`.
  `PgNodeConfig` holds no such setting, and `new PgNodeConfig(` or `PgNodeConfig.defaults(` appears at 39 places in
  20 files (counted 2026-11-15).

**Dependency failure modes, each with a failing test before its code.**

| Dependency | Mode | Test class |
|---|---|---|
| `pg_ctl` and `pg_controldata` | absent directory; present; partial; command failure; no result; malformed output | `PgRejoinControlIntegrationTest` (new) |
| `pg_rewind` | running server; crash-stopped diverged target; target that never diverged; `wal_log_hints` off; unreachable source; inhibitor and own configuration after each | `PgRejoinControlIntegrationTest` |
| `pg_basebackup` | running server; absent directory; existing directory; source slot missing; inhibitor and own configuration after | `PgRejoinControlIntegrationTest` |
| Source observation | writer; standby source; missing slot; unreachable; malformed output; no result | `PgRejoinControlIntegrationTest` |
| Catch-up confirmation | caught-up standby; standby not streaming within the budget | `PgRejoinControlIntegrationTest` |
| Re-join, end to end | crash-stopped diverged former writer rewound; rewind failure rebuilt; absent directory rebuilt; rewound node that cannot follow rebuilt | `PgRejoinIntegrationTest` (new) |
| Re-join refusals | record names this node; unowned record; foreign quarantine; source in recovery; slot not usable; another system; unreadable directory with and without the re-join quarantine; failed rebuild | `PgRejoinIntegrationTest` |

**Test infrastructure.** `PgReplicationFixture` provisions what the deployment must: `wal_log_hints = on` on the
writer (standbys copy it), a replication role and a password file for every member including the writer, the four
function grants to every member role, and, in every node's admission files, the replication rule and the rule for
database `postgres` for every member.

**Order of work.** R1, the adapters (contracts 1 to 5) with `PgRejoinControlIntegrationTest`; R2, `rejoin` with
`PgRejoinIntegrationTest`. Each: tests and stubs that fail with "not implemented", red, implementation, green,
mutation probes with SHA-256 records, the touched classes, core, and guards last. §5 row P-6 is marked reversed when
R2 lands.

**As built, where the code differs from the design text above.**

- Contract 1 and check 11: `pg_ctl status` exits 4 for a path that does not exist **and** for an existing directory
  that is not a database cluster. The first implementation read exit 4 as absent, so a rebuild would have run
  `rm -rf` on such a directory, for example a misconfigured data path. The first green run found it (the test of a
  directory without a control file and without `PG_VERSION` was reported as absent). Absence is now decided by the
  path itself (`test -e`). An existing path for which `pg_ctl status` exits 4 fails the inspection, and a rebuild
  refuses it: `<path> exists but is not a database cluster directory`. Test:
  `aDirectoryThatIsNotADatabaseClusterIsNeitherInspectedNorRemoved`. A base backup interrupted before it wrote
  `PG_VERSION` therefore needs the operator; a failed `pg_basebackup` removes its own directory, so only an interrupted
  supervisor or host leaves one.
- Contract 1: `pg_controldata` runs under `env LC_ALL=C`, because its labels are translated under other locales.
- Contract 5: the confirmation also requires the receiver's sender host and port to be the writer's.
- Contract 6, steps 3 and 4: the source is observed before the stop, so a refusal for the source leaves a running
  standby running. The rebuild observes the source again right before the removal.
- Contract 6, step 5: a failure of the rewind path that is a node-local store refusal (`PgLocalStateException`, for
  example a follow receipt for another address) fails the call without a rebuild: a rebuild cannot cure it, and no
  data directory is removed for it.
- Contract 6, step 2: an unreadable directory under the re-join quarantine is logged at ERROR before the rebuild, so the
  read failure is not lost.
- Test defect found by the first green run: a server started **in recovery** does not write its parameters to the
  control file, so `wal_log_hints=off` did not reach it and the rewind succeeded. The tests now run the node once
  outside recovery and assert `wal_log_hints setting: off` in `pg_controldata` before the rewind.
- Test-shape defect found by the R2 red run: two refusals judged in a chain of `transform` steps passed the first
  failed assertion to the second as its input (§9.26). That test now continues with `compose`.
- Tests added after the implementation, each with its red from a probe: `aStandbyWhoseReplayIsBehindTheWriterIsNot...`
  (probe 58), `aStorageRefusalAfterTheRewindFailsWithoutARebuild` (probe 69), and the running-server assertion in
  `aSourceInRecoveryIsRefusedWithTheDataDirectoryIntact` (probe 70).

**Status, 2026-11-16.** Implemented (`f588dfe7`; test additions `56ef23c5`), green, mutation-probed, and the module's
integration tier, core, and guards green. Runs on the Jenkins host clone `/var/lib/jenkins/peegeeq-phase5`; the
regression ran on files that equal `56ef23c5` in `peegeeq-pg-failover` and `peegeeq-test-support` (`git diff
origin/master`, 0 lines, checked 2026-11-16). Logs in `/var/lib/jenkins/peegeeq-work/logs`.

| Step | Evidence |
|---|---|
| Probes of PostgreSQL 15.13 | `rewind-probe-20261115.log`, `rewind-probe2-20261115.log` (checks 1 to 11) |
| R1 red, against the stubs | `rejoin-r1-red-20261115.summary`: 21 run, 11 failures, 10 errors, every one `UnsupportedOperationException: not implemented` or a wrong type caused by it |
| R1 green | `rejoin-r1-green-20261115`: 19 of 21; the two failures are the defects in "As built" (exit 4, the `wal_log_hints` test). After the fixes `rejoin-r1-green2-20261115`: 22 of 22 |
| R2 red, against the stub | `rejoin-r2-red-20261115.summary`: 12 run, 7 failures, 5 errors, all from the stub |
| R2 green | `rejoin-r2-green-20261115.summary`: 12 of 12. The tests added afterwards: `rejoin-green-corrected-20261115.log`, 3 of 3 |
| Probes 51 to 70 | `rejoin-probes-20261115.log`; SHA-256 of the three production files `24c514f4...0838fe41e` before and after. 19 of 20 detected. 53 (the crash-recovery step) was not: the adapter test's target had no `standby.signal`, so `pg_rewind` recovered it itself. With the inhibitor in place, as the monitor's stop leaves it, probe 53b failed both the adapter test and the end-to-end test (`rejoin-chain2-20261115.log`; SHA-256 of `PgCtlProcessControl.java` `c7609464...7942970f` before and after) |
| Module integration tier | `rejoin-regress-integration-20261115.log`: 23 classes, 348 tests, 0 failures, errors, or skips; re-join adapter 23 and re-join 13 among them |
| Core | `rejoin-regress-core-20261115.log`: 11 classes, 416, 0 failures |
| Guards, after the last test edit | `rejoin-regress-guards-20261115.log`: 14 classes, 77, 0 failures |

Not probed: the sender-address check of the catch-up confirmation (no test separates it, because a follow already
confirms the address), and the configuration-copy steps' own command failures.

**Not checked.** A real host loss and return (the tests stop PostgreSQL with `-m immediate`). A large data directory:
the budget and the speed of `pg_rewind` and `pg_basebackup` were measured only on test-sized clusters, and
`pg_basebackup` uses its default spread checkpoint. A rebuild from a `clonefrom` member (OD-52). A source that holds
the lease but whose PostgreSQL is not the writer. Configuration files outside the data directory. The caller that
decides when to call `rejoin`: there is no supervisor loop. The re-join policy cutover that restores two-peer
coverage. Downstream modules and a Jenkins job build of this code.

### 9.33 Phase 7b.3: Restart, Reload, and `pending_restart` (Owner Answer 10), 2026-11-17 (design)

The next item of §9.28's order of work. Owner answer 10 (§9.28, row 10; §9.20 row 9): "Full Patroni set" — restart
(conditional and scheduled), reload, and `pending_restart`. No design document describes these operations (a search
of the four design documents for `pending_restart` finds nothing). This section is their design. It changes no
existing contract.

**Purpose.** An operator applies configuration changes to one node without bypassing local supervision. A reload makes
PostgreSQL re-read its configuration files and is confirmed. The parameters that wait for a restart are observable. A
restart stops and starts the node through the supervisor's guarded paths, now or at a scheduled time, optionally only
when a restart is pending, when the node has a given role, or when its server version is below a given one. Success: a
writer restarts writable under its own lease and, if it was serving, serves again; a standby restarts in recovery; a
condition that is not met leaves PostgreSQL untouched; a scheduled restart runs once, or is cancelled when PostgreSQL
restarted since it was scheduled.

**Sources read, 2026-11-17.** Patroni REST API page (`POST /restart` with `restart_pending`, `role`, `postgres_version`,
`timeout`, `schedule`; `DELETE /restart`; `POST /reload`; `pending_restart` and `pending_restart_reason`). Patroni
v4.1.5 source, `patroni/ha.py` (`schedule_future_restart`, `delete_future_restart`, `restart_matches`,
`evaluate_scheduled_restart`, `restart`, `handle_long_action_in_progress`, `touch_member`) and
`patroni/postgresql/config.py` (`reload_config`). PostgreSQL 15 pages `view-pg-settings` (`pending_restart`, `context`)
and `app-pg-ctl` (`restart`, `reload`, `postmaster.opts`). Code and tests read in full this session: the manifest of the
2026-11-17 handover reading (handover §1).

Facts from those sources that the design rests on:

1. Patroni holds a scheduled restart in memory (`self.patroni.scheduled_restart`) and copies it into the member key on
   each loop. The schedule records the postmaster start time; when PostgreSQL has started again since, the schedule is
   cancelled ("postgres restart has already happened"). The conditions are checked when the restart runs, not when it is
   scheduled. The schedule is cleared after the attempt, in a `finally` block.
2. A Patroni leader keeps renewing its leader lock while its PostgreSQL restarts (`handle_long_action_in_progress`).
3. `pg_settings.pending_restart` is true for a parameter changed in a configuration file that needs a restart. Patroni
   reads it after a reload and reports the parameters with their values in effect and in the file.
4. `pg_ctl reload` sends SIGHUP and does not wait. `pg_ctl restart` is a stop followed by a start, and passes the
   contents of `postmaster.opts`, the options of the previous start, unless `-o` is given.

**Data model.** One stored record is added: the scheduled restart.

| Datum | Source of truth or derivable | Contract |
|---|---|---|
| Parameters pending a restart | Derived live: `pg_settings` rows with `pending_restart` | Never stored |
| Postmaster start time | Derived live: `pg_postmaster_start_time()` | Evidence that a restart happened; the cancellation token of a schedule |
| Server version | Derived live: `current_setting('server_version_num')` | Used by the version condition |
| Configuration load time | Derived live: `pg_conf_load_time()` | Evidence that a reload happened (as OD-9) |
| Configuration file errors | Derived live: `pg_file_settings.error`, `pg_hba_file_rules.error` | A reload that leaves an error fails |
| Configuration content | The node's configuration files (deployment). Cluster-wide configuration in the coordinator is a later item (answer 12) | A reload applies what the files hold. The admission gate keeps owning `pg_hba.conf` |
| Restart request: operation (UUID), `onlyIfPending`, role (`WRITER`, `STANDBY`, or any), `belowVersion` (or none) | Supplied by the caller | Stored only as receipt parameters and inside a schedule |
| Receipt `restart` | Node-local store (existing), keyed by the observed record's generation and the request's operation | Parameters: the request. Begun only after the conditions are met |
| Scheduled restart: the request, the time it is due (UTC), the postmaster start time when scheduled | Node-local store, new record `scheduled-restart.json`, one per node | The operator's intent; never derived. Removed by cancellation, by the cancellation rule, or after its attempt |

**Contracts.**

1. A new port `PgConfigurationControl` (adapter `PgSqlConfigurationControl`: `psql` on the local socket and
   `pg_ctl reload`, one budget per operation, the shape of `PgSqlDurabilityControl`). Every failure is a
   `PgConfigurationException`, including a failed command, no result, malformed output, and an exhausted budget.
   - `settings()`: the postmaster start time, the server version number, whether the server is in recovery, and the
     names of the parameters pending a restart. Fails while PostgreSQL is stopped.
   - `reload()`: first reads the error views. A `pg_hba_file_rules` error, and a `pg_file_settings` error on an entry
     with no parameter name (a syntax error) or a name that `pg_settings` does not know, fail the call with no reload
     sent: PostgreSQL would keep the old rules or apply nothing (checks 6 and 7). Then observes the load time, runs
     `pg_ctl reload`, and waits within the budget until the load time changes. Then reads the views again, with
     `pg_settings`: an error entry whose parameter is not `pending_restart` is an invalid value, and fails the call,
     naming the entries and stating that PostgreSQL applied the other changes (checks 5 and 8: PostgreSQL reports a
     valid pending restart and an invalid value with the same error text, and only after a reload does
     `pending_restart` tell them apart). The rules compare columns, not message text, which PostgreSQL can translate.
2. `PgFailoverMonitor.reload()`. Serialized. No lease, no receipt: the effect is observable (the load time), as OD-28
   and OD-34 decide for slot steps. Not refused under quarantine (OD-61).
3. `PgFailoverMonitor.restart(observed, request)`. Serialized. Returns `PgRestartResult(restarted, reason)`.
   1. Refused before any effect and with no receipt: quarantine (`PgLocalStateException`); PostgreSQL stopped
      (`PgProcessControlException`). When `observed` names this node as writer: no fresh ownership of exactly
      `observed`, or a server in recovery (`PgLeaseProtocolException`). Otherwise: a writable server
      (`PgPromotionException`), because a writable node that the record does not name needs a re-join, not a restart.
   2. A reload through contract 1, so that the pending flags are current; a failed reload refuses the restart before
      anything is stopped, because PostgreSQL does not start with an invalid value (check 8). Then the conditions,
      observed live through `settings()`: `onlyIfPending` and no parameter pending; a role other than
      the requested one; a version at or above `belowVersion`. Any unmet condition returns `restarted = false` with the
      reason, logged at INFO, with no receipt and no effect (OD-57).
   3. Begin the `restart` receipt with the request as parameters. Record `COMPLETED`, or `UNKNOWN` after a failure.
   4. Writer: note whether the grant is `OPEN` and the record `SERVING`; close the grant and the gate; stop PostgreSQL
      within the stop budget (the inhibitor first). An unconfirmed stop quarantines the node, as a demotion's does. Then
      the guarded start of `startPrimary`: apply the watchdog mode, remove the inhibitor, start writable. A failed start,
      or ownership lost while it ran, demotes the node and fails the call. When the grant was `OPEN` on a `SERVING`
      record, prepare a grant of the same mode for the same revision and activate it; a gate that does not open revokes
      admission and fails the call (OD-58). Otherwise admission stays closed.
   5. Standby: close the grant and the gate, stop, start in recovery, and confirm the server is in recovery.
   6. The result is `restarted = true` once the postmaster start time is later than before the stop.
4. `PgFailoverMonitor.scheduleRestart(request, due)`. Serialized. Observes the postmaster start time and stores the
   schedule. Refused while a schedule exists, while PostgreSQL is stopped, and under quarantine.
   `cancelScheduledRestart()` removes it and returns whether one existed.
5. `PgFailoverMonitor.runScheduledRestart(observed, now)`. Serialized. No schedule, or `now` before the due time: returns
   that nothing ran. A postmaster start time other than the stored one: removes the schedule, logs at INFO that a
   restart already happened, and returns that nothing ran. Otherwise runs the restart of contract 3 and removes the
   schedule whatever the outcome; a failure of the restart is returned, with a failure to remove the schedule attached.
   `now` is an argument, as the budget of `rejoin` is (OD-53). Nothing calls it outside tests until the supervisor loop
   exists.

**Danger, and the safety net.** A writer restart ends every application session; transactions in flight fail or have an
unknown outcome (P-19), and writes are unavailable until the restart and re-admission complete. A writer whose start
fails is demoted and its lease expires, so a covered standby can take over. A restart of a required standby blocks
commits on the writer until it returns (S25). A scheduled restart runs with no operator present; its conditions are
checked when it runs, and a restart that already happened cancels it. The renewal loop is not serialized with local
effects, so the lease is renewed while the writer restarts, as a Patroni leader keeps its lock (fact 2).

**Decisions of the implementer, 2026-11-17, under the owner's direction of 2026-11-13 to proceed without questions.
Reversible.**

- OD-54: a restart is the supervisor's own stop and guarded start, never `pg_ctl restart`. Reason: `pg_ctl restart`
  passes `postmaster.opts`, the options of the previous start (fact 4), such as the `-c primary_conninfo=''` of a
  re-join's crash recovery, and it would start a writer outside the guarded path (inhibitor removed under fresh
  ownership, watchdog mode, receipt).
- OD-55: reload and the settings observation form a new port, `PgConfigurationControl`, with its own exception. Reason:
  their subject is neither promotion nor durability, and the builder (P7c) removed the constructor-argument reason of
  OD-21 and OD-29. Cost: the 15 builder calls in 14 test classes gain the dependency.
- OD-56: a restart request carries its own operation identity, and the receipt key uses it with the observed record's
  generation. Reason: one control record can see several restarts; the record's own operation would make the second one
  reuse the first one's final receipt.
- OD-57: an unmet condition is a result, not a failure, and is logged at INFO. Reason: the caller asked for a conditional
  restart, and nothing failed. Patroni's REST endpoint answers it with an error status; that is a difference.
- OD-58: a writer restart restores admission only when the grant was `OPEN` and the record `SERVING` when it began, with
  the same mode and revision. Reason: a restart must not open admission that was closed, and must not leave a serving
  writer closed.
- OD-59: a scheduled restart is stored in the node-local store, not in memory as Patroni holds it. Reason: an operator's
  request must not disappear without a log line when the supervisor restarts. The postmaster-start-time rule (fact 1)
  keeps a stored schedule from restarting a node twice.
- OD-60: Patroni's `timeout` parameter is not adopted. The start is bounded by the process control's command timeout.
  `peegeeq.pg.failover.primary-start-timeout-ms` stays designed and unbuilt (§9.20 row 5).
- OD-61: reload is allowed under quarantine; restart is refused under any quarantine. Reason: a reload changes no role
  and no admission; a re-join owns the process of a node under its quarantine.

**Dependency failure modes, each with a failing test before its code.**

| Dependency | Mode | Test class |
|---|---|---|
| `psql` and `pg_ctl reload` through the runner | command failure; no result; malformed output; budget exhausted; server stopped | `PgSqlConfigurationControlIntegrationTest` (new) |
| Configuration files | a changed reloadable parameter takes effect; a changed restart parameter is reported pending; an invalid value in `postgresql.conf`; an invalid `pg_hba.conf` rule | `PgSqlConfigurationControlIntegrationTest` |
| Restart, writer | serving writer restarts and serves again; writer with closed admission stays closed; no fresh ownership; record names another writer; quarantine; stopped server; unconfirmed stop; failed start; gate that does not open on re-admission; receipt with other parameters | `PgRestartIntegrationTest` (new) |
| Restart, standby | standby restarts in recovery; writable node that the record does not name; failed start | `PgRestartIntegrationTest` |
| Conditions | pending required and none pending; pending required and one pending (applied after the restart); role mismatch; version at or above the bound; version below it | `PgRestartIntegrationTest` |
| Schedule, store | stored and read back; a second one refused; malformed record; record of another node; storage fault | `PgLocalStateStoreTest` (core) |
| Schedule, monitor | none; not due; postmaster restarted since; due and run; due and the restart fails; cancel with and without one | `PgRestartIntegrationTest` |

**Checks run, 2026-11-17,** on throwaway `postgres:15.13-alpine3.20` containers (PostgreSQL 15.13) on the owner's
workstation with local Docker (the Jenkins host did not answer), PostgreSQL started by `pg_ctl` as in
`PgSupervisedContainer`. Logs: `logs/restart-probe-20261117.log` (checks 1 to 5; its lines after check 5 are not
evidence: a stopped first run of the script kept writing to the same file), `logs/reload-probe2-20261117.log` (check 6
repeated with a 30-second wait), `logs/reload-probe3-20261117.log` (check 7). The containers were removed.

1. `pg_postmaster_start_time()` changed across a fast stop and start (`07:34:26.003936` then `07:34:28.014713`).
2. `server_version_num` is `150013`.
3. `ALTER SYSTEM SET max_connections = 150` (context `postmaster`), then a reload: the setting stayed `100` with
   `pending_restart = true`, and `pg_file_settings` listed both values with `applied = false`. After a fast stop and
   start: `150`, `pending_restart = false`, no parameter pending.
4. A reloadable change (`work_mem = 8MB` appended) took effect after a reload, and the load time changed at once.
5. `work_mem = 8MB` with `checkpoint_timeout = nonsense` in the same file: the reload changed the load time and applied
   `work_mem`; `pg_file_settings` reported "setting could not be applied" for `checkpoint_timeout`; the log said
   "contains errors; unaffected changes were applied".
6. `work_mem = 8MB` with an unknown parameter, and separately with a syntax error: `pg_file_settings` reported the error
   before the reload; after `pg_ctl reload` (exit 0) the load time did not change within 30 seconds and `work_mem` stayed
   `4MB`; the log said "contains errors; no changes were applied".
7. An unknown connection type in `pg_hba.conf`: `pg_hba_file_rules` reported it before the reload; the reload changed the
   load time; the log said "pg_hba.conf was not reloaded". (A host name such as `bogus-not-an-address` is valid syntax.)

8. Found by the first green run of RS1, then probed (`logs/postmaster-probe-20261117.log`): `pg_file_settings` reports
   `setting could not be applied` both for a valid changed `postmaster` parameter (`max_connections = 150`) and for
   invalid ones (`shared_buffers = nonsense`, `max_wal_senders = -5`), before and after a reload. After the reload only
   the valid one has `pending_restart = true`. A stop and start with the invalid values fails: `FATAL: configuration
   file ... contains errors`. So an error entry is an invalid value exactly when its parameter is not pending a restart
   after a reload, and a restart with one would leave the node stopped.

So the confirmation by load time alone (OD-9's rule) would wait out its budget on check 6 and report success on checks
5 and 7. Contract 1 refuses checks 6 and 7 before the reload and reports checks 5 and 8 after it. The first version of
contract 1 refused every error before the reload; the RS1 green run showed that this refuses every pending restart
(check 8), and the contract was corrected before the implementation was kept.

**Finding, 2026-11-17: the existing reloads rest on the load time alone.** Static reading when written; the RS1b red run
(below) confirmed it at all three sites. Three built
operations reload and wait for the load time to change: `PgSqlDurabilityControl.install` (then compares the setting in
effect, which catches check 5), `PgHbaAdmissionGate` `close` and `open` (then reads `pg_hba_file_rules`, which catches
check 7), and `PgSqlPromotionControl.follow` (waits for the WAL receiver instead). With a `postgresql.conf` that
PostgreSQL rejects whole (check 6), each waits out its budget and fails with a message that does not name the file
error. For the gate this means a closing admission fails on a writer whose `postgresql.conf` holds an unknown
parameter; whether PostgreSQL reloads `pg_hba.conf` in that case although the load time does not change is not known
and is check 9 of RS1b. The failure is visible, so no write is admitted wrongly; the cost is the budget and the
misleading message. RS1b applies contract 1's error read to the three sites.

**Order of work.** RS0: the two existing test-standard violations found on 2026-11-17 (the `compose` teardown of
`PgFailoverMonitorIntegrationTest`, the poll without a delay in `PgCtlProcessControlIntegrationTest`). RS1: the port and
adapter (contract 1). RS1b: the finding above, at the three existing reload sites. RS2: `reload` and `restart`
(contracts 2 and 3). RS3: the schedule (contracts 4 and 5). Each:
tests and stubs that fail with "not implemented", red, implementation, green, mutation probes with SHA-256 records, the
touched classes, core, and guards last.

**Not decided here.** Who calls `runScheduledRestart` (the supervisor loop). How an operator reaches these operations
(the status and control endpoints, answer 13). The callbacks `on_restart` and `on_reload` (answer 14). Restarting every
member in turn.

**RS0 status, 2026-11-17.** Done on the owner's workstation with local Docker (29.9.3): the Jenkins host did not answer
(`http://192.168.137.32:8080/login`, 8-second timeout). `PgFailoverMonitorIntegrationTest` teardown now runs every step
through `TestCleanup.run`; before, a failed `elector.close()` skipped the other closes. `PgCtlProcessControlIntegrationTest`
`waitForBackend` now waits 100 ms between polls (`vertx.timer`); before, it re-polled at once until its deadline. Logs
under `logs/`: rebuild `rebuild-peegeeq-pg-failover-rs0-20261117.log` BUILD SUCCESS; `peegeeq-pg-failover-rs0-20261117.log`:
monitor 23 of 23, process control 11 run with 1 error, `statusReportsStoppedThenRunning`, whose `@BeforeEach` `initdb`
exceeded the 30-second command limit (the two tests after it took 34.9 s and 25.6 s against 4 to 9 s for the others; no
other container ran; the cause of the slow window was not measured); the class alone again,
`peegeeq-pg-failover-rs0-processcontrol-run2-20261117.log`: 11 of 11, that test 3.8 s; guards
`peegeeq-test-support-guards-rs0-20261117.log`: 14 classes, 77, 0 failures. No production file changed.

**RS1 status, 2026-11-17.** Implemented, green, mutation-probed; local Docker (the Jenkins host did not answer). New:
`PgConfigurationControl`, `PgSqlConfigurationControl`, `PgConfigurationException`, `PgServerSettings`; test
`PgSqlConfigurationControlIntegrationTest` and the test runner `ReloadDoesNotReloadCommandRunner` (the shape of
`PromoteDoesNotPromoteCommandRunner`: `pg_ctl reload` becomes `pg_ctl status`, which exits 0 and sends no signal).
Nothing calls the port yet; the monitor gains it in RS2.

| Step | Evidence under `logs/` |
|---|---|
| Red, against the stub | `peegeeq-pg-failover-rs1-red-20261117.log`: 12 run, 8 failures, 4 errors, every one `UnsupportedOperationException: not implemented` or the wrong type it causes |
| First green | `peegeeq-pg-failover-rs1-green-20261117.log`: 12 run, 1 error: the pending-restart test was refused, which found check 8; contract 1 was corrected (above) |
| Red for the correction | `peegeeq-pg-failover-rs1-red2-20261117.log`: the changed invalid-value test, the new invalid restart-parameter test, and the pending-restart test, 3 run, 2 failures, 1 error, each on the refusal of every error before the reload |
| Green | `peegeeq-pg-failover-rs1-green2-20261117.log`: 13 of 13 |
| Probes 71 to 75 | `peegeeq-pg-failover-rs1-mutation71-` to `-75-20261117.log`; SHA-256 of `PgSqlConfigurationControl.java` `78540791...FA580641` before and after (`-mutation-sha-before-`, `-mutations-`). 71 refusal before the reload: the unknown-parameter test waited out the budget and the admission-rule test saw the load time change. 72 invalid-value check: both invalid-value tests saw a successful reload. 73 `pending_restart` exclusion: the pending test was refused for `max_connections`. 75 settings filter: every parameter was reported pending. 74, the wait for the load time, was not detected: the reload had taken effect before the test's next query |
| Test added for probe 74 | `aReloadCommandThatReportsSuccessButIsNotProcessedFailsWithinItsBudget`, with `ReloadDoesNotReloadCommandRunner`. Green on the real code (`-reloadtest-green2-`, 2 of 2, with the reloadable test). Probe 74b failed it for another reason, a `psql` call that exceeded the 3.7 s left of a 5-second budget, so its budget became 10 seconds; probe 74c (`-mutation74c-20261117.log`) failed it with "The operation succeeded; expected a failure starting with: The configuration load time did not change within the budget"; SHA-256 equal before and after |
| Final class | `peegeeq-pg-failover-rs1-final-20261117.log`: 14 of 14 |
| Core | `peegeeq-pg-failover-rs1-core-20261117.log`: 416, 0 failures |
| Guards, after the last test edit | `peegeeq-test-support-guards-rs1-20261117.log`: 77, 0 failures |

Not probed: the malformed-output and no-result branches have tests (`outputThatIsNotAnObservationIsRejected`,
`aRunnerThatReturnsNoResultFailsBothOperations`) but were not removed one at a time.

**RS1b status, 2026-11-17.** Check 9, run first (`logs/hba-with-broken-conf-probe-20261117.log`): with an unknown
parameter in `postgresql.conf`, a reload left the load time unchanged and still applied `pg_hba.conf` (a TCP connection
was rejected after the closed rules were installed and admitted after the open rules were restored). So the gate's
close, before this change, made the closed rules effective, waited out its budget, and failed before it ended the
application sessions; and its open admitted applications and reported a failure.

Built: `PgConfigurationFiles` (package-private), the one definition of the error query and its three lists, now used by
`PgSqlConfigurationControl` as well. `PgSqlDurabilityControl.install` and `PgSqlPromotionControl.follow` refuse before
their `ALTER SYSTEM` when a file holds a `rejected` entry. `PgHbaAdmissionGate.open` refuses before it installs the open
rules. `PgHbaAdmissionGate.close` with a `rejected` entry installs the closed rules, sends the reload (check 9: it applies
`pg_hba.conf`), ends the network sessions, and then fails: admission is not confirmed closed. Only the `rejected` list
applies at these sites; a `pg_hba.conf` error does not stop `postgresql.conf` from being applied, and the gate keeps its
own check of `pg_hba_file_rules` after a reload. OD-62, the implementer's, reversible: the gate's close does every step
that still has an effect before it fails, because a close is a safety step and its caller stops the writer on failure.

| Step | Evidence under `logs/` |
|---|---|
| Red, on the unchanged production code | `peegeeq-pg-failover-rs1b-red-20261117.log`: gate close and open, and follow, each waited out its budget with a message that does not name the file error; the install test failed in set-up (`initdb` over 30 s, a slow window); alone, `-rs1b-red-install-`: the install waited out its budget the same way. `initdb` measured alone: 2.3 to 2.4 s |
| Green | `peegeeq-pg-failover-rs1b-green-20261117.log`: `PgConfigurationFilesIntegrationTest` 2 of 2, `PgHbaAdmissionGateIntegrationTest` 9 of 9, `PgSqlConfigurationControlIntegrationTest` 14 of 14 |
| Probes 76 to 81 | `peegeeq-pg-failover-rs1b-mutation76-` to `-81-20261117.log`; SHA-256 of the four production files equal before and after (`-rs1b-mutation-sha-before-`, `-sha-after-`). 76 install refusal, 77 follow refusal, 78 the close's unconfirmed path, 79 the open refusal: each guarding test failed on a wait to the end of the budget. 80 the end of the sessions in the close's unconfirmed path: the application session was not ended and its `docker exec` reached the fixture's 30-second limit. 81 the unknown-parameter rule: both refusal tests waited out their budgets |

Not yet run for RS1b at this step: the other classes that reach the three sites (every monitor test closes the gate).
They run in the module's integration tier after RS2, which changes 14 of them. That run is recorded below under
"Regression, 2026-11-17 and 2026-11-18".

**RS2 status, 2026-11-17.** Built: `PgFailoverMonitor.reload()` and `restart(observed, request)` (contracts 2 and 3),
`PgRestartRequest`, `PgRestartRole`, `PgRestartResult`, and the builder's required `configuration` dependency (OD-55;
the 15 builder calls in 14 test classes pass it, and `PgFailoverMonitorBuilderTest` refuses a missing one by name).
A writer restart closes the grant and the gate, stops PostgreSQL (an unconfirmed stop quarantines the node), applies the
watchdog mode, and starts writable; a failed start or lost ownership demotes the node; a grant that was open for
exactly the observed serving record is prepared and activated again with the same mode and revision, and a gate that
does not open revokes admission. A standby restart stops and starts in recovery. Both are confirmed by a later
postmaster start time. Test: `PgRestartIntegrationTest` (new, 21 tests; one Consul dev agent, one PostgreSQL that only
the supervisor starts, a 60-second lease so ownership outlasts a restart without a renewal loop).

| Step | Evidence under `logs/` |
|---|---|
| Red, against the stubs | `peegeeq-pg-failover-rs2-red-20261117.log`: 20 run, 9 failures, 11 errors, all `not implemented` or the wrong type it causes, except one set-up `initdb` over 30 s; that test alone, `-rs2-red-standbystart-`: the wrong type from the stub |
| Builder | `peegeeq-pg-failover-rs2-builder-20261117.log`: 11 of 11 |
| Green | `peegeeq-pg-failover-rs2-green-20261117.log`: 20 run, 18 passed, 2 errors, both a `docker exec` over its limit (a gate query with 5.3 s left; a set-up `initdb` over 30 s); alone, `-rs2-green-rerun-` and `-rerun2-`: both passed (the first rerun's `initdb` was slow again) |
| Test added for the start-time confirmation | `aRestartWhoseStopAndStartReportSuccessWithoutEffectFails`, with a process control whose stop and start report success and do nothing; green on the real code (`-rs2-noeffect-green-`) |
| Probes 82 to 88 and 90 to 93 | `peegeeq-pg-failover-rs2-mutation82-` to `-93-20261117.log`; SHA-256 of `PgFailoverMonitor.java` `5C8BE002...A24D849F` before and after. 82 ownership check, 83 quarantine, 84 stopped server, 85 writable node the record does not name, 86 the reload before the conditions (2 tests), 87 the skip on an unmet condition (3 tests), 88 re-admission, 90 demotion after a failed start, 91 quarantine after an unconfirmed stop, 92 the start-time confirmation, 93 revocation after a failed re-admission: each guarding test failed with the message of its own behaviour |

**RS3 status, 2026-11-17.** Built: `PgScheduledRestart` (the node, the request, the due time, the postmaster start time
when scheduled; unknown fields and a record of another node refused) and three store operations,
`scheduledRestart()`, `scheduleRestart(schedule)` (refused while one exists and for another node), and
`clearScheduledRestart()` (returns whether one existed), in the file `scheduled-restart.json`; and the monitor's
`scheduleRestart(request, due)`, `cancelScheduledRestart()`, and `runScheduledRestart(observed, now)` (contracts 4 and
5). The restart body moved into a private `restartNow`, so `restart` and `runScheduledRestart` share it inside their
serialized operations; `restart`'s checks and logs are unchanged. Tests: 7 in `PgLocalStateStoreTest` (core), 9 in
`PgRestartIntegrationTest`.

| Step | Evidence under `logs/` |
|---|---|
| Red, against the stubs | `peegeeq-pg-failover-rs3-red-store-20261117.log`: 36 run, 2 failures, 5 errors, the 7 new tests, each on the stub; `-rs3-red-monitor-`: 9 run, 4 failures, 5 errors, each `not implemented` or the wrong type it causes |
| Green | `-rs3-green-store-`: 36 of 36; `-rs3-green-restart-`: `PgRestartIntegrationTest` 30 of 30 |
| Probes 94 to 103 | `peegeeq-pg-failover-rs3-mutation94-` to `-103-20261117.log`; SHA-256 of `PgLocalStateStore.java`, `PgScheduledRestart.java`, and `PgFailoverMonitor.java` equal before and after (`-rs3-mutation-sha-before-`, `-sha-after-`). 94 one schedule per node, 95 node check on write, 96 node check on read, 97 the clear's result, 98 unknown fields, 99 not due, 100 cancellation by a later postmaster start, 101 removal after the attempt (2 tests), 102 quarantine, 103 the stored start time: each guarding test failed with the message of its own behaviour |

After the RS3 green run the inner block that the refactor left in `restartNow` was removed; the change is whitespace
only (checked by comparing the file without whitespace), and the probes and the regression below ran on it.

**Slow `docker exec` on the workstation, 2026-11-17.** Seven times in the runs of this section a `docker exec` exceeded
its limit: five set-up `initdb` calls over 30 seconds (2.3 to 2.4 s when measured alone), one `psql` with 3.7 s left of
a budget, and one gate query with 5.3 s left. 150 idle `docker exec ... true` calls took 163 to 408 ms (median 208). The
stalls occur only during test runs; the cause was not established. Each affected test was rerun alone and passed; no
assertion and no fixture limit was changed for them, except the 10-second budget of the RS1 reload test (above).

**Regression, 2026-11-17 and 2026-11-18.** The module's integration tier ran once on 2026-11-17, from 18:20 to 20:13,
after the rebuild `rebuild-peegeeq-pg-failover-rs-final-20261117.log` (log
`peegeeq-pg-failover-rs-regress-integration-20261117.log`). It ran the code of the 20:08 commits (`d09ed730` to
`c8903e58`, tests `37425fa8` to `a5daf7b4`), checked 2026-11-18: no file under `peegeeq-pg-failover/src` was written
after 18:01:14, the module has no difference from `6a0b7c5e`, and the SHA-256 of `PgLocalStateStore.java`,
`PgScheduledRestart.java`, and `PgFailoverMonitor.java` at `6a0b7c5e` equal `-rs3-mutation-sha-after-20261117.log`.
Result: 26 classes, 396 tests, 1 failure, 11 errors, BUILD FAILURE. 16 classes passed whole, among them
`PgRestartIntegrationTest` 30, `PgFailoverMonitorIntegrationTest` 23, `PgRejoinIntegrationTest` 13,
`PgHbaAdmissionGateIntegrationTest` 9, and `PgConfigurationFilesIntegrationTest` 2. The 12 tests that did not pass are
in the other 10 classes. Each stopped on a `docker exec` that exceeded the limit it was given: seven set-up `initdb`
calls and two `pg_basebackup` calls at 30 seconds, two `psql` calls at 396 ms and 4999 ms, and one `pg_ctl reload` at
913 ms (the last three limits are what remained of an operation's budget). The one failure,
`PgFollowIntegrationTest.aStorageFaultWhenTheReceiptIsBegunFailsTheFollowBeforeAnyEffect`, is that reload: the gate's
close failed before the receipt step that the test faults (`followWriter` closes the gate before it begins the
receipt), so the test saw `PgProcessControlException`.
`PgPromotionIntegrationTest.promotingTheTakeoverTargetMakesItWritableWithItsDataAndAdmissionClosed` stopped on the
gate's count of network sessions, also inside the close. RS1b added one `psql` call to every close, inside the stop
budget (8 seconds in both classes), and to every open, inside the gate's command timeout. Whether that call caused
these two was not established. The log `peegeeq-pg-failover-rs-regress-rerun-20261117.log` (written 2026-11-18 05:28)
holds only Maven's project header and no result; it is not evidence.

On 2026-11-18, at `6a0b7c5e` with a clean working tree, on the owner's workstation with local Docker (the Jenkins host
did not answer within 8 seconds). Logs under `logs/`:

| Scope | Evidence |
|---|---|
| Rebuild | `rebuild-peegeeq-pg-failover-rs-rerun-20261118.log`: BUILD SUCCESS |
| The 12 methods, integration profile | `peegeeq-pg-failover-rs-rerun-failed-20261118.log`: 10 classes, 12 of 12: bootstrap serving 2, durability 2, candidacy, follow, policy installation, promotion, re-join control, renewal loop, configuration control, takeover 1 each |
| Core | `peegeeq-pg-failover-rs-core-20261118.log`: 11 classes, 424, 0 failures (416 after RS1, plus 7 store tests of RS3 and 1 builder test of RS2) |
| Guards | `peegeeq-test-support-guards-rs-20261118.log`: 14 classes, 77, 0 failures |

Second tier run, 2026-11-18, 13:12:50 to 15:12:01, on the classes of the 10:59 rebuild at `6a0b7c5e` (Maven reported
"Nothing to compile"; the CC1 files below were created after it started), log `peegeeq-pg-failover-rs-regress-integration-20261118.log`: 26 classes, 396 tests, 0
failures, 2 errors, BUILD FAILURE. 25 classes passed whole. Both errors are in `PgFailoverMonitorIntegrationTest`:
`primaryStartWithoutOwnershipIsRefusedAndStartsNothing`, a set-up `initdb` over 30 seconds; and
`activationWithServingIntentAndItsPreparedGrantAdmitsApplications`, "Ownership was lost while PostgreSQL started": the
class runs no renewal loop, so ownership lasts its 12-second budget (20-second TTL less the 8-second stop budget), and
the gate close, the receipt, and `pg_ctl start` did not finish inside it. The demotion that followed failed on a
`docker exec ... cp` killed at its 7999 ms limit, on the same container; the command that made the start late is not
logged. Rerun alone, `peegeeq-pg-failover-rs-rerun-monitor-20261118.log`: 2 of 2. The tier has not passed in one run.

The banned-pattern grep of `.claude/CLAUDE.md` step 5 over the 35 source files changed by this section found one match,
`restart.get()` in `PgRestartIntegrationTest.unchangedAfter`, a `Supplier.get()`, not a blocking call. The module's
integration tier did not pass in one run on the workstation. It passed in Jenkins build #41 (below).

**Jenkins builds #41 and #42, 2026-11-20 and 2026-11-21, on `18c27376`.** `18c27376` holds this section's code,
§9.32's re-join, and §9.34's CC1 to CC3; its parent commits after `6a0b7c5e` change only CC1 to CC3 and documents.
Consoles: `logs/jenkins-build41-console-20261120.log` and `logs/jenkins-build42-console-20261121.log`.
- Build #41 (`TEST_SUITE=all`, start `peegeeq-pg-failover`). Java stage `-Pall-tests -rf :peegeeq-pg-failover`,
  BUILD SUCCESS in 2 h 21 min. `peegeeq-pg-failover`: 39 classes, 27 of them integration classes, 863 tests,
  0 failures, 0 errors, 0 skipped, 2 h 10 min. Examples 178, benchmarking 153, migrations 56, integration-tests 109,
  all 0 failures; OpenAPI "No tests to run.". The pipeline's 150-minute limit (`Jenkinsfile` line 48) then stopped the
  UI stage after the management UI unit tests (128 of 128). Result `ABORTED`.
- Build #42 (same commit, start `peegeeq-management-ui`). The Java stage ran no test. Management UI unit 128 and e2e
  419; utilities UI unit 836 and e2e 246; all passed. Result `SUCCESS`.

Together the two builds pass every module from `peegeeq-pg-failover` on. Finding: with the Java stage at 2 h 21 min
from `peegeeq-pg-failover`, one build of that scope, or a build from `beginning`, cannot finish inside the 150-minute
limit.

**As built, recorded 2026-11-18 from a reading of `PgFailoverMonitor` at `6a0b7c5e`.** The contracts above do not state
these cases. No code was changed for them.

1. `runScheduledRestart`: a failed read of the schedule, or of the settings (for example while PostgreSQL is stopped),
   fails the call and keeps the schedule. Contract 5 removes a schedule only by the cancellation rule and after the
   restart's attempt. A stored schedule that cannot be read fails every run; `cancelScheduledRestart` removes it without
   reading it.
2. A restart whose grant close or gate close fails (for a writer, also its read of the grant) ends with the call
   failed, PostgreSQL running, no demotion, no quarantine, and the `restart` receipt `UNKNOWN`: the restart begins its
   receipt before it closes admission (contract 3.3, then 3.4 and 3.5). `promoteStandby` and `followWriter` close
   admission before they begin their receipts, so a failed close there leaves no receipt. Contracts 3.4 and 3.5 do not
   state this case.
3. `restartNow` checks a writer's ownership before the quarantine; contract 3.1 lists the quarantine first. Every
   refusal still comes before any effect and with no receipt. Only a quarantined writer without fresh ownership gets
   another exception: `PgLeaseProtocolException`, not `PgLocalStateException`.
4. A standby restart whose stop fails ends with the call failed, no quarantine, and the `restart` receipt `UNKNOWN`
   (`restartStandby` calls `process.stop` directly). Contract 3.4 quarantines only a writer after an unconfirmed stop;
   contract 3.5 does not state the standby case.

### 9.34 Phase 7b.3: Coordinator Configuration (Owner Answer 12), 2026-11-18 (design)

The next item of §9.28's order of work. Owner answer 12 (§9.28, row 12; §9.20 row 15): "Coordinator config" —
cluster-wide settings in the coordinator, changed by a guarded edit operation. This section is its design. §9.28 row 12
records the scope boundary: identity, membership, endpoints, the coordinator selection, and credentials stay in
deployment configuration; membership changes stay with phase 14 (S39); every stored value satisfies the timing rule of
system design §5.4.

**Purpose.** An operator changes the cluster's HA timing in one place, under a revision check, and every supervisor
uses the stored values without a redeployment. Success: a stored setting that breaks the timing rule is never written
and never used; two edits from the same read cannot both succeed; a lease always runs with the timing it was acquired
under.

**Sources read, 2026-11-18.** Patroni dynamic configuration page (settings, defaults, and the rule
`loop_wait + 2 * retry_timeout <= ttl`). Patroni v4.1.5 source: `patroni/dcs/consul.py` (`set_ttl`, `ttl`,
`set_retry_timeout`, `_do_refresh_session`, `_update_leader`, `attempt_to_acquire_leader`, `set_config_value`, the
config read in `_cluster_from_nodes`) and `patroni/config.py` (`set_dynamic_configuration`,
`_validate_and_adjust_timeouts`, `__DEFAULT_CONFIG`, `_safe_copy_dynamic_configuration`,
`_build_effective_configuration`). The five design documents in full, §1 to §8 of this plan, §9.20, §9.28, §9.31,
§9.33, and the code of `PgNodeConfig`, `PgLeaseCoordinator`, `ConsulLeaseCoordinator`, `ConsulMemberPositions`,
`PgPrimaryElector`, `PgRenewalLoop`, `PgTakeover`, and `PgBootstrap`.

Facts from those sources that the design rests on:

1. Patroni keeps the dynamic configuration in one DCS key and edits it with a compare-and-set on the key's version
   (`kv.put(config_path, value, cas=version)`). A node applies a new version on its next loop.
2. Patroni changes a settings value it does not accept instead of refusing it: a timing set that breaks the rule has
   `loop_wait` and `retry_timeout` rewritten, with a WARNING; keys outside its defaults are ignored.
3. On a TTL change Patroni drops its Consul session. The next leader update creates a new session, deletes the leader
   key with a compare-and-set, and writes it again under the new session (`_update_leader`).
4. Patroni takes `watchdog` from the local configuration only, and uses its defaults (`ttl` 30, `loop_wait` 10,
   `retry_timeout` 10) until the key exists. Its bootstrap writes the key's first value.
5. Here, a Consul session's TTL cannot change after creation, and every renewal checks that the session TTL equals the
   configured TTL (`ConsulLeaseCoordinator.validateSession`). The elector derives its freshness deadline from the same
   configured TTL (`PgPrimaryElector.cycle`). Writer exclusion rests on that pairing: a deadline computed from a longer
   TTL than the session's would let a writer consider itself the owner after Consul ended its lease.

**Data model.** One stored record is added: the cluster settings.

| Datum | Source of truth or derivable | Contract |
|---|---|---|
| Cluster settings: lease TTL, loop interval, retry budget (request timeout), stop budget, in milliseconds | Coordinator, one record per incarnation, `peegeeq/pg/<clusterId>/<incarnation>/config`; authoritative | Satisfies the lease and local-stop rule of system design §5.4 when written and when read. Unknown or missing fields fail. Never adjusted |
| Settings revision | Coordinator metadata (Consul `ModifyIndex`) | Guards the edit. Not stored in the value |
| Deployment timing values in `PgNodeConfig` | Deployment configuration | The bound of the settings read itself, the values used while no record exists, and the values bootstrap writes as the first record |
| Effective configuration of a lease | Derived: the deployment configuration with the settings read before the acquisition | Fixed for the life of that lease: adapter session TTL, elector deadline, loop interval, stop budget. Never stored |
| Watchdog mode and device, member tags, failover mode | Deployment configuration (unchanged) | Not in the record (OD-64) |

**Contracts.**

1. `PgClusterSettings` (record): `leaseTtl`, `loopInterval`, `requestTimeout`, `stopTimeout`. Its constructor applies
   the rule of system design §5.4 (`loop + 2 × retry <= TTL`, `loop + retry + stop < TTL`, positive whole
   milliseconds) through the same code as `PgNodeConfig`. `toJson` and `fromJson`: exactly the fields
   `leaseTtlMillis`, `loopIntervalMillis`, `requestTimeoutMillis`, `stopTimeoutMillis`; an unknown, missing, or
   non-integer field fails.
2. `PgNodeConfig.clusterSettings()` returns its timing as `PgClusterSettings`; `withClusterSettings(settings)` returns a
   copy with those values. The constructor's checks apply, so a node in `REQUIRED` watchdog mode refuses settings
   without watchdog slack.
3. A new port `PgClusterConfiguration` (exception `PgClusterConfigurationException`, every failure including a
   synchronous throw):
   - `read()`: an authoritative read; `Optional<PgClusterSettingsRecord>` with the revision and the settings. A stored
     value that fails contract 1 fails the read.
   - `create(settings)`: writes the first record; fails when one exists.
   - `update(expected, settings)`: replaces the settings only when the record is still at `expected`'s revision; a
     record that changed fails the call and changes nothing. Returns the new record.
   - `close()`.
   An unanswered request fails within the request deadline; a lost reply is not success.
4. `PgClusterConfigurationContract` states contract 3 as tests; `ConsulClusterConfigurationIntegrationTest` is its
   Consul binding. Consul: read `GET /v1/kv/<key>?consistent` (no known leader, or a result filtered by ACLs, fails);
   create `PUT ?cas=0`; update `PUT ?cas=<revision>`; a reply other than `true` fails; the written record is read
   back. The adapter refuses settings outside the TTL range Consul accepts for a session (whole seconds, 10 to 86 400),
   as the lease adapter does.
5. `PgBootstrap.start` creates the record from its deployment timing before the initial intent, when none exists. An
   existing record with equal settings is accepted; one with other settings refuses the bootstrap
   (`PgBootstrapException`), because the incarnation is not new.

**Decisions of the implementer, 2026-11-18, under the owner's direction of 2026-11-13 to proceed without questions.
Reversible.**

- OD-63: the record holds the four lease timing values only. Reason: they are the cluster-wide settings of system
  design §1.1 row "Lease TTL, HA-loop interval, retry budget, ..." that every supervisor must apply alike, and §5.4's
  rule binds them together. The designed and unbuilt `primary-start-timeout-ms` (§9.20 row 5, OD-60) joins the record
  when it is built.
- OD-64: watchdog mode and device, the failover mode, and member tags stay in deployment configuration. Reason: the
  device is a path on the host and `REQUIRED` mode is a property of that host's qualification, as Patroni keeps
  `watchdog` local (fact 4); the failover mode is a profile, and system design §11.2 selects a profile while stopped
  (S39, phase 14); the owner placed tags in deployment configuration (§9.31). This changes system design §1.1, whose
  row also names watchdog mode/device and manual/automatic mode.
- OD-65: a lease runs with the settings read before its acquisition until it ends. A changed record takes effect at the
  node's next acquisition; a serving writer keeps its lease's timing until it releases or loses the lease. Reason:
  fact 5. Patroni instead recreates the leader key under a new session (fact 3); here the control record is retained
  history and a delete-and-write opens a window in which another node can acquire. That is a difference from Patroni.
- OD-66: a settings value that breaks the rule, has an unknown field, or misses one is refused on write and fails on
  read; nothing is adjusted. Reason: a silent rewrite (fact 2) changes timing nobody asked for.
- OD-67: with no record, the deployment values apply, as Patroni's defaults do (fact 4). Reason: the per-lease rule of
  OD-65 keeps every lease consistent whichever valid values it uses, and bootstrap writes the record (contract 5).
- OD-68: the settings port is separate from the lease port, as OD-36 decided for member positions. Reason: the lease
  port and its contract suite stay unchanged, and a settings edit needs no lease.

**Danger, and the safety net.** A shorter lease TTL makes a lease expire sooner after a stall, so a successor can take
over sooner; a longer stop budget must still finish before the TTL. Both are checked by the rule on every write and
read. A writer never changes the TTL of a running lease (OD-65). An edit that a `REQUIRED`-mode node cannot use is
refused by that node when it builds its configuration, before any acquisition; the failure is visible and the node
keeps no lease.

**Dependency failure modes, each with a failing test before its code.**

| Dependency | Mode | Test class |
|---|---|---|
| Settings value | each rule broken; non-positive or fractional milliseconds; unknown, missing, or non-integer field; round trip | `PgClusterSettingsTest` (core) |
| Configuration derivation | settings applied; `REQUIRED` mode without watchdog slack refused | `PgNodeConfigTest` (core) |
| Settings port, every adapter | absent record; create and read back; second create refused; update at the read revision; update after another edit refused and nothing changed; malformed stored value; stored value that breaks the rule; unanswered request; unreachable service | `PgClusterConfigurationContract` through its Consul binding |
| Consul wire | read without a known leader; read filtered by ACLs; write answered `false`; TTL outside Consul's range refused before sending; token without write | `ConsulClusterConfigurationIntegrationTest` |
| Bootstrap | no record: created; equal record: accepted; different record: refused before the initial intent; unreachable settings service: refused before the initial intent | `PgBootstrapIntegrationTest` |

**Order of work.** CC1: `PgClusterSettings` and the `PgNodeConfig` derivation (contracts 1 and 2, core tests). CC2: the
port, its exception, the contract suite, and the Consul adapter and binding (contracts 3 and 4). CC3: bootstrap creates
the record (contract 5). Each: tests and stubs that fail with "not implemented", red, implementation, green, mutation
probes with SHA-256 records, the touched classes, core, and guards last. The design documents change in CC2 and CC3:
system design §1.1 and §5.4, coordinator design §1.1 and §3.3.

**Not decided here.** Who calls `read` before an acquisition and builds the components from the derived configuration
(the supervisor loop). How an operator reaches `update` (the status and control endpoints, answer 13). PostgreSQL
parameters in the coordinator (Patroni's `postgresql.parameters`), which §9.33's data model names as part of answer 12:
they need a managed configuration file and the reload and restart of §9.33, and are designed in §9.35. A derived observation that a running lease uses settings older than the stored record.

**CC1 status, 2026-11-18.** Built: `PgClusterSettings` (contract 1) holds the lease and local-stop rule;
`PgNodeConfig`'s constructor applies the rule through it, and its own copy of the rule and of `positiveMillis` is
deleted; `PgNodeConfig.clusterSettings()` and `withClusterSettings(settings)` (contract 2). The messages and exception
types of the rule are unchanged. Owner's workstation, local Docker not used (core only). Logs under `logs/`:

| Step | Evidence |
|---|---|
| Red, against the stubs | `rebuild-peegeeq-pg-failover-cc1-stub-20261118.log` BUILD SUCCESS; `peegeeq-pg-failover-cc1-red-20261118.log`: 38 run, 8 failures, 3 errors, all 11 on the stub ("not implemented", or a refusal not thrown); the 26 earlier `PgNodeConfigTest` tests passed. `loopAndTwoRetriesMayEqualTheLeaseTtl` passed on the stub, which accepts everything; probe 109 is its red |
| Green | `rebuild-peegeeq-pg-failover-cc1-impl-20261118.log` BUILD SUCCESS; `-cc1-green-`: `PgClusterSettingsTest` 9 of 9, `PgNodeConfigTest` 29 of 29 |
| Probes 104 to 114 | `peegeeq-pg-failover-cc1-mutation104-` to `-114-20261118.log`, summary `-cc1-mutations-`; SHA-256 of `PgClusterSettings.java` `4760A567...935F93E0` and `PgNodeConfig.java` `56E2EA89...0E808A8757` equal before and after. 104 loop and two retries, 105 stop budget, 106 positive milliseconds, 107 exact field set, 108 integer fields, 109 the boundary (equal to the TTL is accepted), 110 settings applied, 111 tags kept, 112 `clusterSettings` mapping, 113 the rule called from `PgNodeConfig`, 114 watchdog mode kept: each guarding test failed with the message of its own behaviour |
| Core | `rebuild-peegeeq-pg-failover-cc1-final-20261118.log` BUILD SUCCESS; `-cc1-core-`: 12 classes, 436, 0 failures (424 plus 9 and 3) |
| Guards | `peegeeq-test-support-guards-cc1-20261118.log`: 14 classes, 77, 0 failures |

The banned-pattern grep of `.claude/CLAUDE.md` step 5 over the four changed files found no match. Not run on this
change: the integration classes that construct `PgNodeConfig`; the rule's code moved without change, and its core tests
pass.

**CC2 status, 2026-11-18.** Built: `PgClusterConfiguration` (contract 3), `PgClusterConfigurationException`,
`PgClusterSettingsRecord` (revision and settings), and `ConsulClusterConfiguration` (contract 4); test
`PgClusterConfigurationContract` (14 cases) and its Consul binding `ConsulClusterConfigurationIntegrationTest` (13 wire
cases): one Consul 1.22.1 server with ACLs, key-scoped tokens, and local fake servers for a reply without a known
leader, a filtered reply, an unanswered request, and chosen write and read replies. Nothing outside tests calls the
port. Design documents changed: system design §1.1 (the row of cluster configuration is split into the coordinator's
cluster settings and each node's watchdog and profile settings) and §5.4; coordinator design §1.1, §2, and §3.3.
Owner's workstation, local Docker (the Jenkins host did not answer within 8 seconds just before the 13:12 tier run and
at 15:46; it was not checked between those times). Logs under `logs/`:

| Step | Evidence |
|---|---|
| Red, against the stubs | `rebuild-peegeeq-pg-failover-cc2-stub-20261118.log` BUILD SUCCESS; `peegeeq-pg-failover-cc2-red-20261118.log`: 21 run, 9 failures, 12 errors, every one "not implemented", the stub's exception where the port's was expected, or a constructor that refused nothing |
| Green | `rebuild-...-cc2-impl-`, `-cc2-green-`: 21 of 21; the read's response checks moved into one method, `-cc2-impl2-`, `-cc2-green2-`: 21 of 21 |
| Tests added before the probes | The probe plan showed four checks no test guarded: the `true` reply of a write when the read back is equal, the read-back equality, the revision advance of an update, and the key, revision, and cardinality of a read. Added `aSecondCreateWithEqualSettingsIsRefused` (contract) and five binding cases on a fake service; `-cc2-tests-`, `-cc2-green3-`: 27 of 27 on the real code. Their red is the probes below |
| Probes 115 to 127 | `peegeeq-pg-failover-cc2-mutation115-` to `-127-20261118.log`, summary `-cc2-mutations-`; SHA-256 of `ConsulClusterConfiguration.java` `0BA0D7BC...1CFF60FE` before and after. 115 known leader, 116 ACL filter, 117 refused status, 118 `cas=0` on create, 119 `cas=<revision>` on update, 120 the `true` reply, 121 read-back equality, 122 revision advance, 123 Consul's TTL range, 124 key, 125 positive revision, 126 cardinality, 127 the port's exception: each guarding test failed with the message of its own behaviour |
| Final class | `rebuild-...-cc2-final-`, `-cc2-final-`: 27 of 27 |
| Core | `-cc2-core-`: 12 classes, 436, 0 failures |
| Guards | `peegeeq-test-support-guards-cc2-20261118.log`: 14 classes, 77, 0 failures |

The banned-pattern grep over the six changed Java files found no match, and every `.onFailure` in them is
`context::failNow`. Not checked: TLS to Consul (G-7), and a Consul quorum of three servers; the binding uses one
server, as the member-position binding does.

**CC3 status, 2026-11-18.** Built: `PgBootstrap` takes a `PgClusterConfiguration` and, after the control-record check
and before the initial intent, writes its deployment timing as the first cluster settings record, accepts an equal
record, and refuses one with other values (`PgBootstrapException`); a failed read or write fails the bootstrap before
the initial intent (contract 5). The two callers, `PgBootstrapIntegrationTest` and `PgBootstrapServingIntegrationTest`,
pass a Consul adapter and close it in teardown. The refusal helper of `PgBootstrapIntegrationTest` now also asserts that
a refused bootstrap writes no settings record. Logs under `logs/`:

| Step | Evidence |
|---|---|
| Red, against the stub (the dependency added, not used) | `rebuild-peegeeq-pg-failover-cc3-stub-20261118.log` BUILD SUCCESS; `peegeeq-pg-failover-cc3-red-20261118.log`: 11 run, 3 failures, each a new case on its missing behaviour; the 7 earlier tests passed. `anEqualClusterSettingsRecordIsAcceptedAndLeftUnchanged` passed on the stub, which ignores the record; probe 129 is its red |
| Green | `rebuild-...-cc3-impl-`, `-cc3-green-`: 11 of 11 |
| Probes 128 to 131 | `peegeeq-pg-failover-cc3-mutation128-` to `-131-20261118.log`, summary `-cc3-mutations-`; SHA-256 of `PgBootstrap.java` `6AE213BB...EF761FC6` before and after. 128 the settings step, 129 create only when absent, 130 the equality check, 131 the step before the initial intent: each guarding test failed with the message of its own behaviour |
| Touched classes | `rebuild-...-cc3-final-` BUILD SUCCESS; `-cc3-touched-`: `PgBootstrapIntegrationTest` 11, `PgBootstrapServingIntegrationTest` 15, 0 failures |
| Core | `-cc3-core-`: 12 classes, 436, 0 failures |
| Guards | `peegeeq-test-support-guards-cc3-20261118.log`: 14 classes, 77, 0 failures |

The banned-pattern grep over the three changed files found no match, and every `.onFailure` in them is
`context::failNow`. CC1 to CC3 complete contracts 1 to 5. Open for answer 12: the caller that reads the settings before
an acquisition (the supervisor loop), the operator's entry point to `update` (answer 13), and PostgreSQL parameters in
the coordinator (§9.35).

