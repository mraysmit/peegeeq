# Handover: phase 7b.3, PostgreSQL failover supervisor

**Written:** 2026-10-26, at the end of the P2 session. **Corrected:** 2026-10-27 (§1 Git, §7 Logs).
**Updated:** 2026-11-03, at the end of the P3 session and the P4 design.
**For:** the next session that continues Task 8, phase 7b.3, in `peegeeq-pg-failover`.
**Status of this document:** a briefing, not state. Status lives in code, tests, and logs. Check each claim
against the artifact before relying on it. The authoritative records are plan §9.20 to §9.24 in
`docs-design/failover and resilience/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_IMPLEMENTATION_PLAN.md`
and the Task 8 entries in `docs-design/tasks/tasks.md`.

## 1. Task and state

- **Task.** Task 8, phase 7b.3 (manual takeover) in `peegeeq-pg-failover`. The owner directed that PeeGeeQ
  duplicate Patroni's operations. The work follows strict TDD (red before green) and goes one increment at
  a time.
- **Done.**
  - Patroni comparison and operations matrix: plan §9.20.
  - P1, guarded local promotion with CHECKPOINT: plan §9.21.
  - P2, a standby follows the new writer: plan §9.22.
  - P3, the policy cutover on the new writer: plan §9.23. Implemented, green, mutation-probed, with
    regression, core, and guard runs green on 2026-11-03.
  - P4, member replication slots on the writer: designed in plan §9.24. Not implemented. No P4 test or
    stub exists.
- **Owner decisions, 2026-10-26.** OD-13: no failsafe mode. OD-14: pause mode deferred. OD-15: a planned
  supervisor stop stops PostgreSQL, then releases the lease. OD-15 is decided and not built.
- **Implementer decisions.** Reversible and not reviewed by the owner. OD-16 to OD-21 are in plan §9.21
  and §9.22. OD-22 to OD-26 (P3) are in §9.23. OD-27 to OD-30 (P4) are in §9.24.
- **Blocker for P4.** OD-27 refuses an unbounded `max_slot_wal_keep_size`. It needs the owner's review
  before the P4 tests are written. The implementer recommends the refusal. Without a bound, a member that
  stays down, which after every takeover includes the former writer, keeps WAL on the writer until
  `pg_wal` fills and the writer stops accepting commits for every application.
- **Git.** On 2026-11-03, HEAD was `5ead8746` on `master`.
  - `0176dcdc` holds the P3 tests, the `PgTakeover` stub, and the fixture's replication rules.
  - `5ead8746` holds the P3 implementation: `PgTakeover.cutover`, the new `PgPolicyCutover`, and the
    change of `PgBootstrap.serve` to call it.
  - Uncommitted on 2026-11-03: the plan (§9.23 verification record, §9.24 P4 design), `tasks.md` (P3
    verification and P4 design entries), and this document.

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
| `PgTakeover.cutover(held, mode)` | Refuses without fresh ownership of a withdrawn takeover record, a completed `checkpoint-after-promote` receipt of this operation, or a standby left after removing this node; refuses under quarantine. Writes the next pending policy (confirmed set minus this node, next revision) unless the record already carries it, then serves. Logs failures once: "Takeover cutover failed on node {}" |
| `PgPolicyCutover.serve` (package-private) | The serving sequence moved out of `PgBootstrap.serve`: install the pending policy, confirm, prepare the grant, publish `SERVING`, activate. `PgBootstrap.serve` calls it and keeps its log line |
| Test fixtures | `PgReplicationFixture` (writer and two standbys, a password file per standby; since `0176dcdc` every node's admission files carry the replication rules), `PgSupervisedContainer`, `NoResultCommandRunner`, `PromoteDoesNotPromoteCommandRunner`, `ObservedLeaseCoordinator` |
| `PgTakeoverIntegrationTest` | 9 tests of the cutover, one per row of the §9.23 failure-mode table |

## 3. Next increment: P4, member replication slots on the writer

- **Purpose.** The writer keeps the WAL that each member still needs, in one physical slot per member, so
  that a standby that falls behind or disconnects briefly resumes streaming. The retention is bounded, so
  a member that stays away cannot fill the writer's `pg_wal`. This is plan §9.20 row 20 and a correctness
  gap in P2 and P3 (S31).
- **Design.** Plan §9.24 holds the data model, contracts 1 to 7, OD-27 to OD-30, the failure-mode table,
  the known gap, and the order of work. Summary:
  - The slot name is derived, never stored: `peegeeq_`, then the identity in lower case with `-` and `.`
    replaced by `_`. `PgNodeConfig` refuses colliding names and names over 63 bytes.
  - The slots a writer holds are derived: the membership minus this node.
  - `PgDurabilityControl.ensureSlots` reads `pg_replication_slots` first and creates only the missing
    slots, because creating an existing slot fails.
  - `promoteStandby` creates the slots after its checkpoint. `PgPolicyCutover.serve` calls the slot step
    first. `follow` installs `primary_slot_name` and requires the receiver to stream through that slot.
- **Checks already run, 2026-11-03** (plan §9.24, "Checks run"), on throwaway containers of the test image,
  not on `PgReplicationFixture`:
  1. `pg_create_physical_replication_slot(name, true)` behaves as documented. A duplicate creation fails.
     The names `pg-node-2` and `Peegeeq_X` are rejected, so the derived name is required.
  2. A slot on a writer reserves from the last checkpoint's redo point. On a just-promoted standby it
     reserves from the current position. Creating slots before the checkpoint after promotion does not
     narrow the known gap. The cause of the difference was not established.
  3. A standby whose slot is missing retries every 5 seconds (`wal_retrieve_retry_interval = 5s`) and
     streams at the first retry after the slot exists.
- **Existing tests that change with P4.** Six tests promote their writer through the adapter, not through
  `promoteStandby`, so that writer holds no slot and their follow would fail at its 20-second budget:
  - `PgFollowIntegrationTest`: `followingTheNewWriterStreamsFromItWithAdmissionClosedAndACompletedReceipt`,
    `followingClosesAnOpenGrantAndAdmissionBeforeItChangesAnything`,
    `followingTwiceIsIdempotentAndKeepsTheReceiptCompleted`, `aRerunAfterAnUnknownReceiptCompletesTheSameReceipt`.
  - `PgSqlPromotionControlIntegrationTest`: `aStandbyFollowsTheNewWriterAndStreamsFromIt`,
    `theInstalledConnectionStringKeepsTheIdentityAndHoldsNoPassword`.

  The change for each: create the follower's slot on the new writer with `PgSqlDurabilityControl.ensureSlots`
  after the adapter promotion. `PgTakeoverIntegrationTest` needs no change. This list comes from reading the
  classes in full; none of them has been run with P4.
- **Known gap P4 leaves.** Slots on replicas, advanced to published member positions, are not built
  (OD-30). After a takeover a lagging surviving standby may need a rebuild. Publishing member positions is
  a new design item for the owner.
- **First steps once OD-27 is decided.** Tests and stubs that fail with "not implemented", including the
  new `PgSlotIntegrationTest`. Then the red run, and the rest of the sequence in §5.

## 4. Remaining 7b.3 work

1. P4, replication slots (plan §9.24). Waits for OD-27.
2. Takeover after real lease expiry. The P1 to P3 tests use a released lease, not an expired one.
3. Planned stop with release (OD-15).
4. Manual takeover.
5. Repeated failover.
6. Controlled re-join, with rewind.
7. A production entry point. Nothing outside tests calls `promoteStandby`, `followWriter`, or
   `PgTakeover.cutover`.
8. The real-device watchdog run on a Linux host.

Rows 9, 10, 15, 17, 19, 23, and 26 of plan §9.20 still await owner decisions (§9.20, text after the
table, read on 2026-11-03). None blocks P4.

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
- **TDD sequence used for P1 to P3.**
  1. Write the tests.
  2. Write stubs that fail with "not implemented". Run to red and read the failures.
  3. Implement and run to green.
  4. Run mutation probes. Record the SHA-256 of each production file before and after. Remove one behaviour at
     a time. Run only the guarding tests. Confirm that the failure message names the removed behaviour.
     Rebuild from the restored source afterwards.
  5. Run the module's integration classes that the change touches one at a time, then the core tests, then
     the guards.
- **Guards last.** Run the guard tests after the final edit to any test file. A guard result from before a
  later edit does not describe the final files. P1 was reported with a guard failure present for this reason.

## 6. Pitfalls found

- **Teardown noise.** A failed test's asynchronous teardown is not awaited. It leaks an
  "Interrupted while waiting for command ... pg_ctl stop" ERROR line and can fail the class-level ERROR log
  contract. This is register finding 13 (`tasks.md`, item 13 of the vertx-junit5 findings). The P3 red run
  and every failing mutation-probe run showed it. The red run also printed `RejectedExecutionException:
  event executor terminated`. Neither P3 green run logged it. Its correction is Task 9, correction 5 (plan §9.18). Do not
  treat the line as a new defect, and do not suppress it.
- **Vert.x timeouts.** `VertxExtension` reads `io.vertx.junit5.Timeout`, not JUnit's `@Timeout`. A setup
  method that provisions three nodes needs `@io.vertx.junit5.Timeout` of 120 seconds or more.
- **`Future.eventually`.** It discards a cleanup failure. Seven `.eventually(` teardown calls remain in four
  older test classes: `PgRenewalLoopIntegrationTest`, `PgPolicyInstallationIntegrationTest`,
  `PgBootstrapIntegrationTest`, `PgBootstrapServingIntegrationTest`. Use an explicit chain that collects
  every failure, as `PgPromotionIntegrationTest` does. This count is from 2026-10-26 and was not re-counted.
- **Close calls in `.onSuccess`.** Put them inside `context.verify(...)`, or the guard fails (Tier 2).
- **Standby admission.** A standby's `pg_hba.conf` already rejects applications. A test that proves a gate
  close must first open the grant and the gate.
- **Replication rules on a promoted standby.** Before `0176dcdc` the standby admission files had no
  replication rule, and `all` does not match a physical replication connection. A gate closed on a new
  writer then rejected its follower. Any new fixture node needs the same replication rules.
- **Logged ERROR versus returned failure.** Log a declared ERROR in `andThen`, not `onFailure`, so the
  caller sees the failure only after it is logged.
- **Windows arguments.** An embedded double quote does not reach a child process. Use `\x22` in SQL, or
  `\047` for a single quote inside `printf`. For a `docker exec` command that holds quotes or `$`, pipe a
  heredoc to `sh` through the Bash tool; PowerShell quoting dropped part of such a command on 2026-11-03.
- **IDE diagnostics during probes.** They report unused methods while a probe is applied and stay stale
  after the restore. Use the SHA-256 record, not the IDE, to confirm a restore.
- **Python.** It is not installed on this machine.
- **Jenkins.** Its Checkout stage failed on 2026-10-25 because of connection resets between the Jenkins host
  and GitHub. Its state was not checked after that. It is the owner's to run. Do not run whole-suite
  verification locally.

## 7. Evidence index

- **Plan.** §9.20 (Patroni matrix), §9.21 (P1), §9.22 (P2), §9.23 (P3, with an evidence table and a
  mutation-probe table), §9.24 (P4 design and checks), and the §8.1 status rows.
- **Register.** The Task 8 entries in `docs-design/tasks/tasks.md`, including the P3 verification entry and
  the P4 design entry of 2026-11-03.
- **Logs.** `logs/` is ignored by `.gitignore`.
  - P3: every log named in plan §9.23 was present on 2026-11-03, for example
    `peegeeq-pg-failover-cutover-impl-20261103.log`, `peegeeq-pg-failover-cutover-mutation1-red-20261103.log`
    to `...-mutation6-red-20261103.log`, `peegeeq-pg-failover-cutover-core-20261103.log`, and
    `peegeeq-test-support-cutover-guards-20261103.log`.
  - P1 and P2: on 2026-11-03 `logs/` held the P1 (`peegeeq-pg-failover-promotion-*-20261026.log`) and P2
    (`peegeeq-pg-failover-follow-*-20261026.log`) logs, with write times on 2026-10-26. The 2026-10-27
    version of this section said none of them existed. That statement does not describe the current
    directory. Why the 2026-10-27 search did not find them was not established.
  - The P4 checks ran in throwaway containers. Their output is recorded in plan §9.24, not in a log file.
- **Counts read from the logs on 2026-11-03.**
  - P3, `5ead8746`: `PgTakeoverIntegrationTest` 9 in each of two runs; regression
    `PgBootstrapServingIntegrationTest` 14, `PgPromotionIntegrationTest` 15, `PgFollowIntegrationTest` 10;
    core 395; guards 61. All with no failure, error, or skip.
  - P2, 2026-10-26: integration across the eight `peegeeq-pg-failover-follow-final-{a,b,c,d,e,f1,f2,f3}`
    logs 43 + 14 + 14 + 19 + 87 + 17 + 15 + 10 = 219; core 395 (`...-follow-core-20261026.log`); guards 61
    (`peegeeq-test-support-follow-guards-20261026-run2.log`). The first guard run of that day failed one test.
    These match the counts in the `e9189df1` commit message. Which commit each P2 log ran against is not
    recorded in the logs.
  - P1, 2026-10-26: core 389 (`...-promotion-core-20261026.log`); guards 61.
  - No whole-module integration run was made against `5ead8746`. Only the four classes above ran.

## 8. Not checked at handover

- P4 in code. No P4 test, stub, or implementation exists.
- Slot behaviour on `PgReplicationFixture` itself. The P4 checks used throwaway containers of the same image
  and server version, without the fixture's own `initdb` settings.
- The documented default of `wal_retrieve_retry_interval`. The PostgreSQL page returned HTTP 503. The value
  5 s is what the image reported.
- Why a slot on a just-promoted standby reserves from the current position and not the last redo point.
- The integration classes not re-run against `5ead8746`, among them `PgBootstrapIntegrationTest`,
  `PgSqlDurabilityControlIntegrationTest`, and `PgSqlPromotionControlIntegrationTest`. They depend on code
  that P3 changed: `0176dcdc` changed `PgReplicationFixture`, and `5ead8746` changed `PgBootstrap`. Run them
  one at a time before P4 changes them further.
- Following after a rewind, takeover after a real lease expiry, and repeated failover.
- Promotion with an armed watchdog device.
- Provisioning of the replication password file outside the test fixture.
- Member address configuration. The caller supplies the writer's address (OD-20).
- Any production entry point.
- The Jenkins build and the whole-repository `-Pall-tests` gate.
