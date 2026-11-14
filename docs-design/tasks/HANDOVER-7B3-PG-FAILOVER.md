# Handover: phase 7b.3, PostgreSQL failover supervisor

**Written:** 2026-10-26, at the end of the P2 session. **Updated:** 2026-11-03 (P3, P4 design), 2026-11-12 (P4 to P6),
2026-11-13, at the end of the P7 session, after the owner stopped the regression run, and 2026-11-14, after the P7
regression, two defect fixes from Jenkins build #37, and the P7 records.
**For:** the next session that continues Task 8, phase 7b.3, in `peegeeq-pg-failover`.
**Status of this document:** a briefing, not state. Status lives in code, tests, and logs. Check each claim against the
artifact before relying on it. The authoritative records are plan §9.20 to §9.28 in
`docs-design/failover and resilience/PEEGEEQ_PG_CONNECTION_MANAGEMENT_HAPROXY_IMPLEMENTATION_PLAN.md` and the Task 8
entries in `docs-design/tasks/tasks.md`.

## 1. Task and state

- **Task.** Task 8, phase 7b.3 (manual takeover) in `peegeeq-pg-failover`. The owner directed that PeeGeeQ duplicate
  Patroni's operations. Strict TDD (red before green), one increment at a time.
- **Done before 2026-11-13.** P1 promotion with CHECKPOINT (§9.21); P2 follow (§9.22); P3 policy cutover (§9.23); P4
  member slots on the writer (§9.24, §9.25); P5 lost-slot replacement (§9.26); P6 slots on standbys advanced to published
  positions (§9.27).
- **Owner decisions of 2026-11-13.** The owner answered fifteen questions on the open decisions. They are recorded
  verbatim, with every conflict found against the design set and the code, in plan §9.28, first table. Summary:
  - Kept: OD-27 (unbounded `max_slot_wal_keep_size` refused), OD-35, OD-36, OD-37; adopting an existing cluster is not
    supported (§9.20 row 26).
  - Reversed: OD-38 (member records are session-bound), OD-39 (positions rank covered candidates), OD-40 (publication and
    advance move into the monitor, which gets a builder), OD-41 (own exception and a contract suite).
  - New scope: automatic rewind and rebuild with data-directory removal on a failed rewind (§9.20 row 10; reverses P-6);
    restart, reload, `pending_restart` (row 9); tags `nofailover`, `nosync`, `noloadbalance`, `clonefrom` (row 19);
    cluster-wide configuration in the coordinator with a guarded edit (row 15); `/cluster`, `/history`, a node status
    endpoint, `/metrics` (row 17); the five Patroni callbacks, non-blocking (row 23).
  - The answers are also in the assistant memory file `project-owner-answers-20261113.md`.
- **Owner direction, 2026-11-13.** "After verification proceed with implementation. Do not stop to ask me any
  questions. I expect you to complete all these tasks." The owner then stopped the session during the regression run
  and asked for this handover.
- **Done 2026-11-13: P7 (answers 5 to 8), implemented and probed; regression not finished.**
  - P7a: `PgMemberPositionException`; neutral `PgMemberPositionsContract`; `ConsulMemberPositionsIntegrationTest` is its
    Consul binding.
  - P7b: `ConsulMemberPositions` holds its own member session (TTL = lease TTL, `Behavior=delete`, `LockDelay=0s`, no
    checks, read back and validated). `publish` renews or re-creates the session and writes with `?acquire`. `read`
    fails on a record no session holds. The constructor takes the Consul node name.
  - P7c: `PgFailoverMonitor.builder()`; the public constructor is deleted; `publishPosition()` and
    `advanceMemberSlots()` are monitor operations, serialized; `PgMemberSlots` is deleted.
  - P7d: `PgDurabilityControl.candidatePosition` (larger of received and replayed WAL; timeline of the receiver, else of
    the last restartpoint; refused on a writer); `PgCandidacy`; `PgFailoverMonitor.takeoverCandidacy(observed)`.
- **Done 2026-11-14.** P7 regression green on Jenkins (build #36). Two defects found by build #37 fixed:
  `PgSqlPromotionControl.follow` now requires the WAL receiver's `wait_event = WalReceiverMain` (PostgreSQL 15 shows
  `streaming` with the writer's address and slot before `START_REPLICATION` fails on a missing slot); 20 integration
  classes declare `io.vertx.junit5.Timeout` equal to their JUnit class limit. P7 records written (plan §8.1, §9.28;
  system design §1.1, §5.10; coordinator design §1.1, §3.3; `tasks.md`).
- **Git.** `origin/master` is `ec62d499`; the owner pushed the 26 one-file commits of 2026-11-14 (`14e52639` to
  `ec62d499`). The owner makes all commits unless they ask Claude to commit; do not reset or push without the owner's
  instruction.

## 2. What exists in the module

| Item | Purpose |
|---|---|
| `PgFailoverMonitor.builder()` | Requires config, elector, store, process, gate, watchdog, durability, promotion, positions; `build()` names a missing one (`NullPointerException` message = the name) |
| `PgFailoverMonitor.publishPosition()` | Serialized. Refused under quarantine. Observes `durability.position(self)` and publishes it |
| `PgFailoverMonitor.advanceMemberSlots()` | Serialized. Refused under quarantine. Ensures slots for other members, reads positions, advances. Returns the members advanced |
| `PgFailoverMonitor.takeoverCandidacy(observed)` | Serialized. Refused (synchronous, "rejected" log) when the confirmed policy does not name this node; refused under quarantine. Not preferred when another member of the confirmed policy published a higher timeline, or the same timeline beyond this node's candidate position. Missing records ignored |
| `PgCandidacy` | Record `(preferred, reason)`. Derived, never stored |
| `PgDurabilityControl.candidatePosition(nodeId)` | Standby only. SQL in `PgSqlDurabilityControl.CANDIDATE` |
| `PgMemberPositions` / `ConsulMemberPositions` | Port and adapter as in §1. Failures are `PgMemberPositionException` |
| `PgMemberPositionsContract` | Abstract contract: read-back, replace, empty, expiry, keep-alive, re-publish after expiry, unheld record, rejected write, malformed, non-member, filtered, unanswered, unreachable |
| Everything listed in the 2026-11-12 handover | Unchanged: promotion, follow, slots, lost-slot replacement, policy cutover, bootstrap, renewal loop, watchdog modes, admission gate, local store |

## 3. Evidence

**2026-11-14.** Jenkins build #36 (`4b1e2a87`): `peegeeq-pg-failover` 704 tests, downstream modules and UI suites
green. Jenkins build #38 (`ec62d499`): `peegeeq-pg-failover` 706 tests, 0 failures; for the downstream modules read
the build. Runs on the Jenkins host, logs in `/var/lib/jenkins/peegeeq-work/logs`: red runs of the follow test on the
pre-fix code (`red2-` to `red4-`), promotion class 20 of 20, integration tier 296 tests, core 410, guards 77,
acquire-refusal mutation probe with SHA-256 restored. Details in plan §9.28.

**2026-11-13, as reported at that handover.** The logs named below are not in `logs/` on the owner's workstation
(checked 2026-11-14); the numbers were not re-checked.

- **Red, then green, per increment.**
  - P7a/P7b red: `peegeeq-pg-failover-p7ab-red-20261113.log` (16 run, 12 failures, each the old exception type, no
    session, or a missing argument check). Green: `-p7ab-green-` 16 of 16, then `-p7ab-green2-` 18 of 18 after two added
    session tests.
  - P7c: the builder test was written after the builder code, so no compile-red log exists. Probe 43 is its behaviour red.
  - P7d red: `peegeeq-pg-failover-p7d-red-20261113.log` (9 run, 4 failures, 5 errors, all on the stub). Green:
    `-p7d-green-candidacy-` 6 of 6; `-p7d-green-adapter-` member-slot adapter 13 and positions binding 18;
    `-p7d-green-received2-` 1 of 1 (the paused-replay test, added before the probes; its first run hit the 30-second
    Vert.x default).
- **Mutation probes 34 to 50, all detected.** SHA-256 records: `-p7ab-mutation-sha-before-`/`-after-`
  (`ConsulMemberPositions.java` `20D3F01B...180D68`), `-p7c-mutation-sha-before-`/`-after-` (`PgFailoverMonitor.java`
  `1DE3229A...B2B102`, before P7d), `-p7d-mutation-sha-before-`/`-after-` (`PgFailoverMonitor.java` `72CC32B2...CB02728`,
  `PgSqlDurabilityControl.java` `95A83E1E...265C2C`). 34 acquire; 35 `Behavior=delete`; 36 unheld-record check; 37 session
  renewal; 38 re-create on 404; 39 session read-back; 40 and 41 quarantine in publish and advance; 42 slot step in
  advance; 43 builder null check; 44 higher-timeline rule; 45 same-timeline rule; 46 policy-name refusal; 47 candidacy
  quarantine; 48 writer refusal; 49 restartpoint-timeline fallback; 50 received position used.
- **Integration runs after P7c and before P7d** (`-p7c-*-20261113.log`): slot 11, takeover 11, monitor 23, bootstrap 7,
  renewal loop 13, bootstrap serving 15, promotion 17, follow 10, policy installation 14. Core after P7c: 410 in 11
  classes (`-p7c-core-`).
- **Regression on the final code** (rebuild `rebuild-peegeeq-pg-failover-p7-restored-20261113.log`, BUILD SUCCESS):
  durability 29, promotion adapter 19, promotion 17, bootstrap serving 15, all 0 failures (`-p7-regress-*-`). Also on
  the final P7d code: candidacy 6, member-slot adapter 13 plus the added test, positions binding 18. The takeover run
  (`-p7-regress-takeover-`) was stopped by the owner while running; its log has no result.

## 4. Next steps, in order

1. Done 2026-11-14: the P7 regression and the P7 records. §5 row P-6 is marked reversed by §9.28 answer 9 only when
   that increment lands.
2. Done 2026-11-14: the follow-confirmation and Vert.x timeout defects from build #37 (plan §9.28).
3. Done 2026-11-14 (`bafe52b2`, `6ac4b5b3`, `c5c44640`): takeover after real lease expiry (plan §9.29, `PgTakeover.acquire`). The
   original item text follows for reference. **Takeover after real lease expiry** (the item that was next before P7): `promoteStandby`, `followWriter`,
   `PgTakeover.cutover` with the former writer excluded by expiry. Use `takeoverCandidacy` before the acquisition in the
   automatic path. Write §9.29 before any test. Helpers: `expiringOwnership` and `awaitStale` in
   `PgBootstrapServingIntegrationTest`; `loseTheWriterWhileTheFollowerIsStopped` in `PgTakeoverIntegrationTest`;
   `acquisitionAfterExpiryNeedsNoReplyFromFormerOwner` in `PgLeaseCoordinatorContract`.
4. Done 2026-11-14 (`ed29d191`): planned stop with release (OD-15, plan §9.30, `PgFailoverMonitor.stopAndRelease`).
5. Member tags (answer 11).
6. Automatic rewind and rebuild (answer 9).
7. Restart, reload, `pending_restart` (answer 10).
8. Coordinator configuration (answer 12).
9. Status and metrics endpoints (answer 13).
10. Callbacks (answer 14).
11. Remaining 7b.3 items: manual takeover, repeated failover, controlled re-join, the production entry point and the
    supervisor loop (nothing outside tests calls the monitor operations), and the real-device watchdog run on Linux.

Each item is designed in its own plan section, with its data model and contracts, before its tests.

## 5. How to work here

- **Pre-work (mandatory, from `.claude/CLAUDE.md`).** Read `docs-design/dev/pgq-coding-principles.md` and
  `docs-design/testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md` in full. Read every file to modify and its tests in
  full. Run the banned-pattern grep and show its output.
- **Read before you ask or recommend.** On 2026-11-13 the owner's questions were framed before the document set and
  the code were read, and the owner corrected this. Read the five design documents, the plan, the Task 8 entries, and the
  module before any question, recommendation, or design text.
- **Design first.** Data model and contracts go into the plan before any test. Record each implementer choice as
  reversible. Record where the code differs from the design text ("As built").
- **Style.** Short declarative sentences. State only gaps the repository cannot answer.
- **Commands.** From `docs-design/testing/PEEGEEQ-TEST-COMMANDS.md`, PowerShell, `Tee-Object`, console output sent to
  `$null`, then read the saved log.
  - Rebuild: `mvn clean install -DskipTests -pl ":peegeeq-pg-failover" -am 2>&1 | Tee-Object -FilePath logs\<name>-<YYYYMMDD>.log`
  - One class: `mvn test -Pintegration-tests -pl ":peegeeq-pg-failover" "-Dtest=<Class>"`; methods: `"-Dtest=Class#a+b"`
  - Core: `mvn test -pl ":peegeeq-pg-failover"`; guards: `mvn test -pl ":peegeeq-test-support"`
- **Class run times on 2026-11-13** (Surefire "Time elapsed"; the command adds build time): durability 8 min 26 s
  (29 tests; the class is near the 10-minute limit, so new tests go elsewhere), promotion 6 min 4 s, promotion adapter
  6 min 1 s, bootstrap serving 5 min 33 s, slot 4 min 40 s, policy installation 4 min 30 s, takeover 4 min 19 s,
  member-slot adapter 3 min 53 s, follow 3 min 47 s, candidacy 2 min 47 s, monitor plus renewal loop plus bootstrap
  3 min 28 s together, positions binding 1 min 16 s.
- **TDD sequence.** Tests; stubs that fail with "not implemented"; red; implementation; green; mutation probes with
  SHA-256 before and after, restored from a scratchpad copy; the touched classes; core; guards last.

## 6. Pitfalls found

All pitfalls of the 2026-11-12 handover still apply (teardown noise, `io.vertx.junit5.Timeout`, `TestCleanup` instead of
`.eventually(`, close calls inside `context.verify`, chained refusal checks, standby admission, replication rules,
slots and the fixture, slots on a standby, Consul ACL `list` rule, server-start overrides, `andThen` for declared ERROR
logs, Windows quoting, Git Bash paths, stale IDE diagnostics, no Python, Jenkins). New on 2026-11-13:

- **One positions adapter per member.** The member session belongs to the adapter instance. A second adapter for the
  same member cannot acquire the key while the first adapter's session is live (`Consul did not store the member
  position under the member's session`). Each monitor holds one adapter; tests reuse the monitor for every publication.
- **Session timing.** Consul accepts session TTLs of 10 s to 86 400 s. A record can outlive its TTL by up to the TTL
  again; tests wait up to three TTLs.
- **ACL for publication.** A member token needs `session "<consul node>" { policy = "write" }` as well as write on its
  own key and read on the prefix.
- **Unexpected-ERROR gate in a contract.** A refusal before sending is logged under the adapter's own logger, so that
  case lives in the binding, not in the neutral contract.
- **Vert.x test-context timeout.** The extension reads only `io.vertx.junit5.Timeout` (method, then class; not
  inherited). Since 2026-11-14 each integration class declares it equal to its JUnit limit; a new class must do the same,
  and a concrete subclass of an abstract contract declares it itself.
- **Docker runs without Docker on the workstation.** Run probes and targeted tests on the Jenkins host through
  `Invoke-QraftJenkinsShell` (`.env/jenkins-tools.ps1`): a clone under `/var/lib/jenkins/peegeeq-work`, changed files
  copied in base64 chunks below 64 KB, long commands started with `nohup` and read from their log. Do not run there
  while a PeeGeeQ job build runs (shared `.m2`).
- **WAL receiver observations.** `pg_stat_wal_receiver` shows `streaming` with the sender address and slot before
  replication has started. Only `wait_event = WalReceiverMain` shows a receiver inside its streaming loop.
- **PostgreSQL 15.13 facts (observed in tests and probe 49).** After the writer is stopped, a standby has no
  `pg_stat_wal_receiver` row with a timeline, `pg_last_wal_receive_lsn()` keeps its last value, and
  `pg_control_checkpoint().timeline_id` was 1. With `pg_wal_replay_pause()`, the receiver keeps receiving while replay
  stays behind.
- **The `PgDurabilityControl` port grew a method.** Five test wrappers implement it (takeover, bootstrap serving, policy
  installation twice, promotion); each passes `candidatePosition` through.

## 7. Not checked at handover

- The follow confirmation rests on PostgreSQL 15 behaviour: the REL_15_STABLE source and one 3000-sample probe on
  15.13. Other major versions were not checked.
- `twoCandidatesAtTheSamePositionAreBothPreferred` assumes both standbys received the same WAL before the writer stopped;
  it passed once. Whether it can be timing-dependent under load was not measured.
- The items carried from 2026-11-12: takeover after real expiry, re-join through an advanced slot, a lost slot on a
  standby, the production retention bound, the publication and advance cadence, Consul token provisioning outside tests,
  a second coordinator adapter, an armed watchdog, password-file provisioning, member address configuration, any
  production entry point, the Jenkins build, and the `-Pall-tests` gate.
