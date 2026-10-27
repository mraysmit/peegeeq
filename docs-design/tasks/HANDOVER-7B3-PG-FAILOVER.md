# Handover: phase 7b.3, PostgreSQL failover supervisor

**Written:** 2026-10-26, at the end of the P2 session. **Corrected:** 2026-10-27 (§1 Git, §7 Logs).
**For:** the next session that continues Task 8, phase 7b.3, in `peegeeq-pg-failover`.
**Status of this document:** a briefing, not state. Status lives in code, tests, and logs. Check each claim
against the artifact before relying on it. The authoritative records are plan §9.20 to §9.22 in
`docs-design/failover and resilience/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_IMPLEMENTATION_PLAN.md`
and the Task 8 entries in `docs-design/tasks/tasks.md`.

## 1. Task and state

- **Task.** Task 8, phase 7b.3 (manual takeover) in `peegeeq-pg-failover`. The owner directed that PeeGeeQ
  duplicate Patroni's operations. The work follows strict TDD (red before green) and goes one increment at
  a time.
- **Done in the last session.**
  - Patroni comparison and operations matrix: plan §9.20.
  - P1, guarded local promotion with CHECKPOINT: plan §9.21.
  - P2, a standby follows the new writer: plan §9.22.
- **Owner decisions, 2026-10-26.** OD-13: no failsafe mode. OD-14: pause mode deferred. OD-15: a planned
  supervisor stop stops PostgreSQL, then releases the lease. OD-15 is decided and not built.
- **Implementer decisions OD-16 to OD-21.** Reversible and not reviewed by the owner. They are listed in
  plan §9.21 and §9.22.
- **Git.** On 2026-10-27, HEAD was `9c66fa41` on `master` and the working tree was clean.
  - `2232a3cd` holds P1 and the P2 stubs, with failing P2 tests.
  - `e9189df1` holds the P2 implementation, the P1 teardown guard fix, and the plan and register updates.
  - `9c66fa41` adds this document.

  The owner makes all commits. Do not commit and do not suggest committing. Run `git status` and `git log`
  to see what has changed since.

## 2. What exists in the module

| Item | Purpose |
|---|---|
| `PgPromotionControl` and `PgSqlPromotionControl` | Local role control: `inRecovery`, `promote`, `checkpoint`, `follow`. SQL and `pg_ctl` on the local socket |
| `PgWriterEndpoint` | Writer host, port, password-file path. Builds `primary_conninfo` from the node identity. Holds no password |
| `PgFailoverMonitor.promoteStandby(held)` | Promotes the takeover target under the lease, with admission closed, then takes a forced checkpoint. Receipts `promote` and `checkpoint-after-promote` |
| `PgFailoverMonitor.followWriter(observed, writer)` | Points a standby at the writer. No lease needed. Receipt `follow-writer` carries host and port |
| `PgFailoverMonitor` constructor | Eight arguments. The last is the `PgPromotionControl` |
| Test fixtures | `PgReplicationFixture` (writer and two standbys, a password file per standby), `PgSupervisedContainer`, `NoResultCommandRunner`, `PromoteDoesNotPromoteCommandRunner`, `ObservedLeaseCoordinator` |

## 3. Next increment: P3, the policy cutover on the new writer

- **Purpose.** After `promoteStandby` and `followWriter`, the new writer must install, confirm, publish, and
  activate a policy that names only the surviving standby. System design §5.8 gives the lifecycle: the new
  writer serves with `ANY 1 ("pg-node-3")`. The two-peer policy returns only after re-join.
- **Likely reuse.** `elector.update` writes a `pendingDurabilityPolicy` at revision `confirmed + 1`. Then
  the same chain as `PgBootstrap.serve`: install, confirm, prepare, publish `SERVING`, activate. Decide
  whether `serve` can be generalised or whether a sibling method is cleaner.
- **Read before designing.** `PgBootstrap.serve`, `PgFailoverMonitor.installPendingPolicy`,
  `PgPrimaryElector.validateIntent`, and system design §5.8. Validation lets a takeover record carry the
  former writer's policy naming the new writer. A `SERVING` confirmed policy never names the writer.
- **Open question.** Each follower's own supervisor runs the follow in P2. Confirm that this ordering fits
  the cutover's coverage check.

## 4. Remaining 7b.3 work

1. P3, the policy cutover.
2. Replication slots (plan §9.20 row 20).
3. Takeover after real lease expiry. The P1 and P2 tests use a released lease, not an expired one.
4. Planned stop with release (OD-15).
5. Manual takeover.
6. Repeated failover.
7. Controlled re-join, with rewind.
8. A production entry point. Nothing outside tests calls `promoteStandby` or `followWriter`.
9. The real-device watchdog run on a Linux host.

Rows 9, 10, 15, 17, 19, 23, and 26 of plan §9.20 still await owner decisions. None blocks P3.

## 5. How to work here

- **Pre-work (mandatory, from `.claude/CLAUDE.md`).** Read `docs-design/dev/pgq-coding-principles.md` and
  `docs-design/testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md` in full. Read every file to modify and the
  neighbouring tests. Run the banned-pattern grep.
- **Design first.** Write the data model and contracts into the plan before any test. Record each design
  choice that the owner has not reviewed as an implementer decision, marked reversible.
- **Style.** Short declarative sentences. Report what was not checked.
- **Commands.** Copy them from `docs-design/testing/PEEGEEQ-TEST-COMMANDS.md`. Use PowerShell and
  `Tee-Object`.
  - Rebuild: `mvn clean install -DskipTests -pl :peegeeq-pg-failover -am 2>&1 | Tee-Object -FilePath logs\<name>-<YYYYMMDD>.log`
  - One integration class: `mvn test -Pintegration-tests -pl :peegeeq-pg-failover "-Dtest=<Class>" 2>&1 | Tee-Object ...`
  - One method or several: `"-Dtest=Class#methodOne+methodTwo"`
  - Core tests: `mvn test -pl :peegeeq-pg-failover`
  - Guards: `mvn test -pl :peegeeq-test-support`
- **Run discipline.**
  - Run in the foreground, one command at a time, and do nothing else during a run.
  - The tool caps a command at 10 minutes. These classes take 5 to 6 minutes each, so run them separately:
    `PgBootstrapServingIntegrationTest`, `PgSqlDurabilityControlIntegrationTest`,
    `PgSqlPromotionControlIntegrationTest`, `PgPromotionIntegrationTest`.
  - Never pipe a live Maven stream through `Select-Object` or `Out-Null`.
  - Read the per-class `Tests run:` lines from the saved log. `BUILD SUCCESS` alone is not evidence.
- **TDD sequence used for P1 and P2.**
  1. Write the tests.
  2. Write stubs that fail with "not implemented". Run to red and read the failures.
  3. Implement and run to green.
  4. Run mutation probes. Record the SHA-256 of each production file before and after. Remove one behaviour at
     a time. Run only the guarding tests. Confirm that the failure message names the removed behaviour.
  5. Run the whole module's integration classes, the core tests, and the guards.
- **Guards last.** Run the guard tests after the final edit to any test file. A guard result from before a
  later edit does not describe the final files. P1 was reported with a guard failure present for this reason.

## 6. Pitfalls found

- **Teardown noise.** A failed test's asynchronous teardown is not awaited. It leaks an
  "Interrupted … pg_ctl stop" ERROR line into the next test and can fail that test in a red run. This is
  register finding 13.
- **Vert.x timeouts.** `VertxExtension` reads `io.vertx.junit5.Timeout`, not JUnit's `@Timeout`. A setup
  method that provisions three nodes needs `@io.vertx.junit5.Timeout` of 120 seconds or more.
- **`Future.eventually`.** It discards a cleanup failure. Seven `.eventually(` teardown calls remain in four
  older test classes: `PgRenewalLoopIntegrationTest`, `PgPolicyInstallationIntegrationTest`,
  `PgBootstrapIntegrationTest`, `PgBootstrapServingIntegrationTest`. Use an explicit chain that collects
  every failure, as `PgPromotionIntegrationTest` does.
- **Close calls in `.onSuccess`.** Put them inside `context.verify(...)`, or the guard fails (Tier 2).
- **Standby admission.** A standby's `pg_hba.conf` already rejects applications. A test that proves a gate
  close must first open the grant and the gate.
- **Windows arguments.** An embedded double quote does not reach a child process. Use `\x22` in SQL, or
  `\047` for a single quote inside `printf`.
- **Python.** It is not installed on this machine.
- **Jenkins.** Its Checkout stage failed on 2026-10-25 because of connection resets between the Jenkins host
  and GitHub. It is unresolved and the owner's to run. Do not run whole-suite verification locally.

## 7. Evidence index

- **Plan.** §9.20 (Patroni matrix), §9.21 (P1), §9.22 (P2), and the §8.1 status rows.
- **Register.** The Task 8 entries and the P1 and P2 verification records in `docs-design/tasks/tasks.md`.
- **Logs.** `logs/` is ignored by `.gitignore`. On 2026-10-27 it held no P1 or P2 log. The newest file was
  dated 2026-10-22, and no file name contained `promotion` or `follow`. A search of the repository found no
  P1 or P2 log in any other location. Plan §9.21 and §9.22 and the register name P1 and P2 log files, for
  example `peegeeq-pg-failover-promotion-core-20261026.log` and `peegeeq-pg-failover-follow-core-20261026.log`.
  None of the named files exists on this machine. The P1 and P2 run logs are not available as evidence.
- **Last reported counts, 2026-10-26.** Integration, whole module: 13 classes, 219 tests. Core: 395. Guards: 61.
  The source is the `e9189df1` commit message and the register. No saved log supports them. Re-run them
  before quoting them.

## 8. Not checked at handover

- Replication slots, the policy cutover, following after a rewind, and takeover after a real lease expiry.
- Promotion with an armed watchdog device.
- Provisioning of the replication password file outside the test fixture.
- Member address configuration. The caller supplies the writer's address (OD-20).
- Any production entry point.
- The Jenkins build and the whole-repository `-Pall-tests` gate.
