# Handover: phase 7b.3, PostgreSQL failover supervisor

**Written:** 2026-10-26, at the end of the P2 session. **Corrected:** 2026-10-27 (§1 Git, §7 Logs).
**Updated:** 2026-11-03, at the end of the P3 session and the P4 design. **Re-verified:** 2026-11-12, at
`993bf2c2`. **Updated:** 2026-11-12, at the end of the P4, P5, and P6 session.
**For:** the next session that continues Task 8, phase 7b.3, in `peegeeq-pg-failover`.
**Status of this document:** a briefing, not state. Status lives in code, tests, and logs. Check each claim
against the artifact before relying on it. The authoritative records are plan §9.20 to §9.27 in
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
  - P3, the policy cutover on the new writer: plan §9.23.
  - P4, member replication slots on the writer: design plan §9.24, implementation and verification §9.25.
    Implemented, red before green, 12 mutation probes, and the whole module green on 2026-11-12. A defect found
    after the first report, a lost slot not reported by coverage, is fixed as OD-33 (probe 13).
  - P5, replacing a lost member slot for a rebuild: plan §9.26. Implemented, probes 14 to 19, module green on
    2026-11-12. The same change fixed a test-structure defect in 18 sites (plan §9.26, "Test-shape finding").
  - P6, slots on standbys advanced to published member positions: plan §9.27. Every node holds a physical slot
    for each other member (OD-35). Each node publishes its WAL position through a second coordinator port,
    `PgMemberPositions` (Consul key `.../members/<nodeId>`). A standby advances its slots to the smaller of the
    published and its replay position, on the same timeline only. `PgMemberSlots` runs both steps. A standby
    stopped before a takeover now follows the new writer (`PgTakeoverIntegrationTest`). 13 probes, 12 detected;
    probe 23 (the cap at the replay position) is not detectable, because PostgreSQL applies the same cap. Module
    green on 2026-11-12.
- **Owner decisions, 2026-10-26.** OD-13: no failsafe mode. OD-14: pause mode deferred. OD-15: a planned
  supervisor stop stops PostgreSQL, then releases the lease. OD-15 is decided and not built.
- **Owner direction, 2026-11-12.** "Proceed using your best judgement", then "finish" P6 rather than stop for
  review. Under it, OD-27 (an unbounded `max_slot_wal_keep_size` is refused) and OD-35 to OD-41 were adopted as
  the implementer recommended. The owner has not reviewed them. OD-27 trades writer availability against standby
  rebuilds. OD-35 to OD-39 change the system design's data model and add a coordinator port. All stay reversible.
- **Implementer decisions.** Reversible and not reviewed by the owner. OD-16 to OD-21 are in plan §9.21
  and §9.22. OD-22 to OD-26 (P3) are in §9.23. OD-27 to OD-30 are in §9.24. OD-31 and OD-32 (P4) are in the
  §9.24 decisions of 2026-11-12, OD-33 there too. OD-34 (P5, no receipt) is in §9.26. OD-35 to OD-41 (P6) are in
  §9.27.
- **Git.** On 2026-11-12, HEAD was `993bf2c2` on `master`.
  - `0176dcdc` and `5ead8746` hold P3. `bba8db61` holds the 2026-11-03 documents.
  - `8b96af9a`, `2a333f22`, and `993bf2c2` (2026-11-08 and 2026-11-09) changed the test support module, the
    Vert.x version (5.1.10), and the teardowns of four test classes in this module. None changed
    `peegeeq-pg-failover/src/main`.
  - Uncommitted on 2026-11-12, 34 paths: all of P4, OD-33, P5, and P6. That is 8 changed and 4 new production
    files, 13 changed and 4 new test files, the plan (§8.1 rows, §9.24 decisions, §9.25 to §9.27), the system
    design (§1.1, §5.8, §5.10), the coordinator design (§1.1, §3.3), `tasks.md`, and this document. Run
    `git status` for the file list.

  The owner makes all commits. Do not commit and do not suggest committing. Run `git status` and `git log`
  to see what has changed since.

## 2. What exists in the module

| Item | Purpose |
|---|---|
| `PgPromotionControl` and `PgSqlPromotionControl` | Local role control: `inRecovery`, `promote`, `checkpoint`, `follow`. SQL and `pg_ctl` on the local socket. `follow` installs `primary_conninfo` and `primary_slot_name` and confirms streaming from the writer through the node's own slot |
| `PgWriterEndpoint` | Writer host, port, password-file path. Builds `primary_conninfo` from the node identity. Holds no password |
| `PgNodeConfig.slotName(nodeId)` | The member's slot name, derived and never stored: `peegeeq_`, then the identity in lower case with `-` and `.` as `_`. The configuration refuses colliding names and names over 63 bytes; that refusal replaced the case-only membership check (OD-31) |
| `PgDurabilityControl.ensureSlots(nodeIds)` | Creates the missing physical slots, with a reserved position, on the writer or on a standby (OD-35). Refuses an unbounded `max_slot_wal_keep_size` (OD-27) and a slot under a member name that is not physical. Never drops or moves a slot |
| `PgDurabilityControl.confirmCovered(policy)` | As before, and it fails at once when a required standby's member slot is `lost` (OD-33), because that standby must be rebuilt |
| `PgDurabilityControl.replaceLostSlot(nodeId)` | On the writer only. Drops the member's slot only when its `wal_status` is `lost`, then creates it again reserved, so the member can be rebuilt through it. Refuses recovery, a missing slot, and a slot that is not lost |
| `PgDurabilityControl.position(nodeId)` | This node's WAL position. On the writer: the timeline of `pg_walfile_name(pg_current_wal_flush_lsn())` and that flush position. On a standby: `received_tli` and `flushed_lsn` of the streaming WAL receiver. A standby that is not streaming has no position, and the call fails |
| `PgDurabilityControl.advanceSlots(positions)` | On a standby only. Moves each member slot to min(published, local replay), forward only, and only when the published timeline equals the receiver's timeline. Skips a missing, lost, or unreserved slot, another timeline, and a target at or below `restart_lsn`; returns an empty set without a streaming receiver. Fails on a slot under a member name that is not physical. Returns the members it advanced |
| `PgMemberPosition` | Record `(nodeId, timeline, lsn)`. Validates the identity, a timeline of at least 1, and a non-null position |
| `PgMemberPositions` and `consul/ConsulMemberPositions` | The second coordinator port (OD-36): `publish`, `read`, `close`. Consul key `peegeeq/pg/<cluster>/<incarnation>/members/<nodeId>`, value `{"timeline","lsn"}`, plain PUT, no session (OD-38). Refuses another node's position before sending. The read fails on the ACL-filtered header, a malformed value, and a key outside the membership; a 404 is an empty map. Failures are `PgLeaseProtocolException` (OD-41) |
| `PgMemberSlots` | `publishPosition()` and `advanceMemberSlots()` (OD-40). No lease. Both refused under quarantine. `advanceMemberSlots` runs `ensureSlots` for the other members, reads the positions, then `advanceSlots`. Each failure is logged once at ERROR and returned |
| `PgFailoverMonitor.replaceLostMemberSlot(held, nodeId)` | Serialized. Fresh ownership, another member, no quarantine, then `replaceLostSlot`. No receipt (OD-34) |
| `PgFailoverMonitor.promoteStandby(held)` | Promotes the takeover target under the lease, with admission closed, takes a forced checkpoint, then ensures the slots of every other member (OD-32). Slots the node already held as a standby are kept. Receipts `promote` and `checkpoint-after-promote`; the slot step has none |
| `PgFailoverMonitor.ensureMemberSlots(held)` | Serialized. Fresh ownership of exactly `held` and no quarantine, then the slots of the membership minus this node. No receipt (OD-28) |
| `PgFailoverMonitor.followWriter(observed, writer)` | Points a standby at the writer. No lease needed. Receipt `follow-writer` carries host and port |
| `PgFailoverMonitor` constructor | Eight arguments. The last is the `PgPromotionControl` |
| `PgTakeover.cutover(held, mode)` | Refuses without fresh ownership of a withdrawn takeover record, a completed `checkpoint-after-promote` receipt of this operation, or a standby left after removing this node; refuses under quarantine. Writes the next pending policy unless the record already carries it, then serves |
| `PgPolicyCutover.serve` (package-private) | The serving sequence: ensure the member slots, install the pending policy, confirm, prepare the grant, publish `SERVING`, activate. `PgBootstrap.serve` and `PgTakeover.cutover` call it |
| Test fixtures | `PgReplicationFixture`: writer and two standbys, a password file per standby, replication rules in every admission file, `max_slot_wal_keep_size = 256MB`, and each base backup taken with `-C -S <slot>`, so the writer holds both standbys' slots and each standby has `primary_slot_name`. `loseSlot`, `rebuildThroughSlot`, and `writeWal(runner, mb)`. Also `PgSupervisedContainer`, `NoResultCommandRunner`, `PromoteDoesNotPromoteCommandRunner`, `ObservedLeaseCoordinator` |
| P4 to P6 test classes | `PgSlotIntegrationTest` 11 (ensure, replace, rebuild, `PgMemberSlots` advance, quarantine, unreachable coordinator). `PgSqlMemberSlotIntegrationTest` 10 (position and advance on a real server). `consul/ConsulMemberPositionsIntegrationTest` 10 (one ACL-enabled Consul server). `PgMemberPositionTest` 2 (core). `PgTakeoverIntegrationTest` holds the end-to-end pair: a stopped standby follows through its kept slot, and without standby slots it cannot |

## 3. Next increment: takeover after real lease expiry

- **Purpose.** Every P1 to P6 test takes a lease that the former writer released. In an unplanned failover the
  former writer's host is lost, so nothing releases the lease. It expires, and the takeover target takes the
  record. Plan §8 row 7b.3 requires "unplanned safe-expiry takeover without failed-host acknowledgement".
- **What the code offers.** `PgLeaseCoordinator.acquireAfterRelease` "takes ownership of an unowned record".
  The Consul adapter creates every session with `"Behavior": "release"` and a TTL, so an expired session
  releases the lock and leaves the key unowned. The coordinator contract test
  `acquisitionAfterExpiryNeedsNoReplyFromFormerOwner` (`PgLeaseCoordinatorContract.java`) waits for real
  expiry and then takes the record with `acquireAfterRelease`. The coordinator level is therefore covered. What
  is not covered is the supervisor level: a takeover after real expiry through `promoteStandby`, `followWriter`,
  and `PgTakeover.cutover`, with the former writer excluded by expiry rather than by a stop.
- **Read before designing.** `PgLeaseCoordinator`, `ConsulLeaseCoordinator`, `PgPrimaryElector`,
  `PgLeaseCoordinatorContract` (the contract tests), the coordinator design document in
  `docs-design/failover and resilience/`, and the takeover sections of the system design. Write the data model
  and contracts into a new plan §9.28 before any test.
- **Existing test help.** `PgBootstrapServingIntegrationTest` already waits for a real freshness deadline
  (`expiringOwnership`, `awaitStale`), with a 30-second lease. `PgTakeoverIntegrationTest` has
  `loseTheWriterWhileTheFollowerIsStopped` and the member-slot helpers for a takeover with positions.

## 4. Remaining 7b.3 work

1. Takeover after real lease expiry.
2. Planned stop with release (OD-15).
3. Manual takeover.
4. Repeated failover.
5. Controlled re-join, with rewind.
6. A production entry point and the supervisor loop. Nothing outside tests calls `promoteStandby`,
   `followWriter`, `PgTakeover.cutover`, `ensureMemberSlots`, `replaceLostMemberSlot`, or `PgMemberSlots`.
7. The real-device watchdog run on a Linux host.

Left by P4 to P6 (plan §9.24 to §9.27): the rebuild decision and the member-side rebuild commands outside the
fixture (§9.20 row 10, awaiting the owner); removing a departed member's slot and member record (phase 14); how often
positions are published and slots advanced, which belongs to the supervisor loop (item 6); the bound's production
value; a cascading standby.

Rows 9, 10, 15, 17, 19, 23, and 26 of plan §9.20 still await owner decisions (§9.20, text after the
table). None blocks the next increment.

## 5. How to work here

- **Pre-work (mandatory, from `.claude/CLAUDE.md`).** Read `docs-design/dev/pgq-coding-principles.md` and
  `docs-design/testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md` in full. Read every file to modify and the
  neighbouring tests. Run the banned-pattern grep.
- **Design first.** Write the data model and contracts into the plan before any test. Record each design
  choice that the owner has not reviewed as an implementer decision, marked reversible. When the code ends up
  different from the design text, record the difference in the same section (plan §9.27 "As built").
- **Style.** Short declarative sentences. Report what was not checked.
- **Commands.** Copy them from `docs-design/testing/PEEGEEQ-TEST-COMMANDS.md`. Use PowerShell and
  `Tee-Object`.
  - Rebuild: `mvn clean install -DskipTests -pl :peegeeq-pg-failover -am 2>&1 | Tee-Object -FilePath logs\<name>-<YYYYMMDD>.log`
  - One integration class: `mvn test -Pintegration-tests -pl :peegeeq-pg-failover "-Dtest=<Class>" 2>&1 | Tee-Object ...`
  - One method or several: `"-Dtest=Class#methodOne+methodTwo"`; several classes: `"-Dtest=ClassA,ClassB"`
  - Core tests: `mvn test -pl :peegeeq-pg-failover`
  - Guards: `mvn test -pl :peegeeq-test-support`
- **Run discipline.**
  - Run in the foreground, one command at a time, and do nothing else during a run.
  - The tool caps a command at 10 minutes. Command times from the P6 regression on 2026-11-12:
    `PgSqlDurabilityControlIntegrationTest` 6 min 47 s (29 tests; close to the limit, so a new test there goes to
    a new class), `PgBootstrapServingIntegrationTest` 5 min 10 s, `PgPromotionIntegrationTest` 4 min 56 s,
    `PgSqlPromotionControlIntegrationTest` 4 min 50 s, `PgTakeoverIntegrationTest` 4 min 36 s,
    `PgPolicyInstallationIntegrationTest` 3 min 40 s, `PgFollowIntegrationTest` 3 min 3 s, `PgSlotIntegrationTest`
    3 min 1 s. Run each of these alone. Three commands cover the other ten classes:
    - `PgSqlMemberSlotIntegrationTest` and `ConsulMemberPositionsIntegrationTest`: 2 min 41 s.
    - Bootstrap, process control, fixture contract, admission gate, and elector queueing: 2 min 41 s.
    - Monitor, Consul coordinator, and renewal loop: 4 min 35 s.
  - Never pipe a live Maven stream through `Select-Object` or `Out-Null`. In the 2026-11-12 sessions the
    console output of `Tee-Object` was redirected with `> $null`, and the saved log was read after Maven ended.
    Every saved log ended with the Maven summary.
  - Read the per-class `Tests run:` lines from the saved log. `BUILD SUCCESS` alone is not evidence.
- **TDD sequence used for P1 to P6.**
  1. Write the tests.
  2. Write stubs that fail with "not implemented". Run to red and read the failures. For P4 the core rule
     (`slotName`) went red and green first, because the fixture calls it.
  3. Implement and run to green.
  4. Run mutation probes. Record the SHA-256 of each production file before and after. Copy the files to the
     scratchpad, remove one behaviour at a time, run only the guarding tests, and restore from the copy. Confirm
     that the failure message names the removed behaviour. Rebuild from the restored source afterwards. Count the
     probes from the saved logs, not from the plan of probes.
  5. Run the module's integration classes one at a time, then the core tests, then the guards.
- **Guards last.** Run the guard tests after the final edit to any test file or document. A guard result from
  before a later edit does not describe the final files. `InvalidDurationLiteralGuardTest` also scans the
  documents. P1 was reported with a guard failure present for this reason.

## 6. Pitfalls found

- **Teardown noise (finding 13).** Under Vert.x 5.0.4 a failed test's asynchronous teardown was not awaited.
  It leaked an "Interrupted while waiting for command ... pg_ctl stop" ERROR line. Since 2026-11-08,
  `VertxTeardownAfterFailureExtension` gives the `@AfterEach` of a failed test a fresh context that
  vertx-junit5 5.1 awaits. On 2026-11-12, 19 runs had failing tests, and none of their logs held that line or
  `RejectedExecutionException`. That is an observation; no run isolated the extension as the cause. If the
  line appears again, report it. Do not suppress it.
- **Vert.x timeouts.** `VertxExtension` reads `io.vertx.junit5.Timeout`, not JUnit's `@Timeout`. A setup
  method that provisions three nodes needs `@io.vertx.junit5.Timeout` of 120 seconds or more.
- **Test cleanup.** `VertxAsyncForbiddenPatternsGuardTest` rejects `.eventually(` in test sources. Use
  `.transform(TestCleanup.cleanup(...))` or `TestCleanup.run(...)` (`dev.mars.peegeeq.test.vertx`), as
  `PgSlotIntegrationTest` does.
- **Close calls in `.onSuccess`.** Put them inside `context.verify(...)`, or the guard fails (Tier 2).
- **Chained refusal checks.** Attach each `transform` that judges a refusal to the operation it judges, and go on
  with `compose`. A `transform` after another step receives that step's failure too: an assertion failure loses its
  message, and a set-up failure of the expected type passes as the refusal. `PgSqlDurabilityControlIntegrationTest`,
  `PgCtlProcessControlIntegrationTest`, and `PgSqlPromotionControlIntegrationTest` have a `refused(outcome, prefix)`
  helper for this.
- **Standby admission.** A standby's `pg_hba.conf` already rejects applications. A test that proves a gate
  close must first open the grant and the gate.
- **Replication rules on a promoted standby.** Every fixture node's admission files carry the replication
  rules. Any new fixture node needs them.
- **Slots and the fixture.**
  - A writer promoted through the adapter, not through `promoteStandby`, holds only the member slots it held as
    a standby. The fixture's standbys hold none, so in `PgFollowIntegrationTest` the followers cannot stream until
    `ensureSlots` creates their slots, as its set-up does.
  - The base backup writes `primary_slot_name` with the derived name, which is the name that `follow` installs.
    A test that proves `follow` installs it must first run `alter system reset primary_slot_name`.
  - Creating a slot that exists fails with `replication slot ... already exists`.
- **Slots on a standby (PostgreSQL 15.13, plan §9.27 checks).**
  - A physical slot created reserved on a standby starts at its last restartpoint redo, not at its replay position.
  - `pg_replication_slot_advance` on a standby caps a target beyond replay at the replay position, without an
    error. A probe that removes the code's own cap is therefore not detectable (probe 23).
  - Advancing a slot backwards fails: `cannot advance replication slot to ..., minimum is ...`.
  - A slot on a standby survives `pg_ctl promote` with its position unchanged.
  - The adapter reads a standby's position and timeline only from a `pg_stat_wal_receiver` row with status
    `streaming`. A standby that is not streaming therefore has no position and no timeline.
- **Consul ACL for the members prefix.** On Consul 1.22.1 a `list` rule on the prefix lets a recursive read return
  every value, so an ACL-filtered read cannot be produced with it. A node's token holds `write` on its own key and
  `read` on the prefix, with no `list` rule (coordinator design §3.3).
- **Server-start overrides.** `pg_ctl start ... -o "-c name=value"` outranks `postgresql.conf` and
  `postgresql.auto.conf`. The P4 and P5 tests use it for `max_slot_wal_keep_size=-1`, `wal_level=logical`,
  `primary_slot_name`, and, in `loseSlot`, `max_slot_wal_keep_size=32MB` with `wal_keep_size=0`. A later line
  appended to `postgresql.conf` overrides an earlier one.
- **Logged ERROR versus returned failure.** Log a declared ERROR in `andThen`, not `onFailure`, so the
  caller sees the failure only after it is logged.
- **Windows arguments.** An embedded double quote does not reach a child process. Use `\x22` in SQL, or
  `\047` for a single quote inside `printf`. For a `docker exec` command that holds quotes or `$`, pipe a
  heredoc to `sh` through the Bash tool. `ALTER SYSTEM` cannot run inside a multi-statement `psql -c`.
- **Git Bash paths.** Git Bash rewrites arguments that look like POSIX paths, such as `/dev/null` inside a
  `docker exec` command. Run `export MSYS_NO_PATHCONV=1` first.
- **IDE diagnostics during probes.** They report errors while a probe is applied and stay stale after the
  restore. Use the SHA-256 record, not the IDE, to confirm a restore.
- **Python.** It is not installed on this machine.
- **Jenkins.** Its Checkout stage failed on 2026-10-25 because of connection resets between the Jenkins host
  and GitHub. Its state was not checked after that. It is the owner's to run. Do not run whole-suite
  verification locally.

## 7. Evidence index

- **Plan.** §9.20 (Patroni matrix), §9.21 (P1), §9.22 (P2), §9.23 (P3), §9.24 (P4 design, and the decisions of
  2026-11-12), §9.25 (P4 verification and mutation probes), §9.26 (P5 and the test-shape finding), §9.27 (P6
  checks, design, decisions, "As built", status, probes), and the §8.1 status rows.
- **Register.** The Task 8 entries in `docs-design/tasks/tasks.md` of 2026-11-12: the re-verification, P4, the
  OD-33 correction, P5, the P6 design, P6 implemented, and the P6 records correction.
- **Logs.** `logs/` is ignored by `.gitignore`, so a log exists only on the machine that wrote it.
  - On this machine on 2026-11-12, `logs/` held no P1, P2, or P3 log, and a search of `C:\Users\markr` found
    none. An earlier session saw them on 2026-11-03. Why the two observations differ was not established.
  - Re-verification of `993bf2c2`: `logs/*-reverify-*-20261112.log`.
  - P4: `logs/*-slots-*-20261112.log`. OD-33: `logs/*-lostslot-*-20261112.log`. P5: `logs/*-replace-*-` and
    `logs/*-p5-*-20261112.log`. P6: `logs/p6-replica-slot-checks-*`, `logs/*-p6a-*`, `logs/*-p6bcd-*`, and
    `logs/*-p6-*-20261112.log`. Each increment has `...-mutation-sha-before-` and `...-sha-after-` records.
- **Counts read from the logs on 2026-11-12, on the working tree with P4 to P6.** Integration: 18 classes, 276
  tests, 0 failures; every test file with the integration tag. Core: 400 in 10 classes. Guards: 77 in 14 classes.
  The per-class counts are in plan §9.27. After the Javadoc correction of `PgDurabilityControl` (comment only),
  the module was rebuilt and core and guards were run again; the result is in the register entry "P6 records".

## 8. Not checked at handover

- Takeover after a real lease expiry at the supervisor level. The coordinator level is covered (§3).
- A re-joining former writer streaming through its advanced slot.
- A lost slot on a standby. The advance skips it by the same branch as an unreserved slot, which is tested.
- A non-physical slot under a member name on a standby. PostgreSQL 15 cannot create a logical slot on a standby.
- Slot behaviour with the production bound; the fixture uses 256 MB.
- The publication and advance cadence; there is no supervisor loop.
- Provisioning of each node's Consul token for its member key outside the test.
- A second coordinator adapter for either port.
- Promotion with an armed watchdog device.
- Provisioning of the replication password file outside the test fixture.
- Member address configuration. The caller supplies the writer's address (OD-20).
- Any production entry point.
- The Jenkins build and the whole-repository `-Pall-tests` gate.
